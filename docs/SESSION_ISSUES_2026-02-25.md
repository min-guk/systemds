# Session Issues (2026-02-25)

## 1) KMeans (P2P2D, w=2, LAN): MinST chooses a plan that repeatedly uploads 3000x2100 intermediates

- **Status**: Resolved (root-cause identified + config-level fix applied)
- **Principle(s)**:
  - Do **not** close runtime-supported combinations to “force” a better plan.
  - Fix the **cost model / measurement assumptions** first when a cost-based planner mis-chooses.

### Environment / Conditions
- Workload: `scripts/builtin/kmeans.dml` (kmeans++ init loop; `k=50`, `runs=1`)
- Dataset: `P2P2D`, workers=2, net profile=`lan`
- Planners compared:
  - `compile_min_st_cut` (MinST)
  - `compile_cost_based` (DP)
- Repro runner: `experiments/run_LAN_localproc.sh` with `SYSTEMDS_SNAPSHOT_RUNNER=.../systemds_snapshot_exec_fedstats.sh` (`-fedStats`)

### Reproduction (baseline)
MinST, LAN profile as 10Gbps cost input (1250 MB/s):
- Log: `experiments/results/fed2/mkl-min-st-cut/..._kmeans_w2_lan_...log`
- Key observation:
  - `fed_fout` at **hop 283** uploads a **3000x2100** matrix **50×** (broadcast to 2 workers).
  - `Server I/O bytes (read/written)` is ~`5.28GB / 5.16GB`.
  - Total execution time ~`75s`.

DP under the same profile:
- Total execution time ~`41s`.
- `Server I/O bytes written` ~`116MB` (no repeated 3000x2100 upload hotspot).

### Observed symptom (why it is bad)
MinST ends up with a mixed plan in the kmeans++ init loop that:
- builds a 3000x2100 intermediate locally and
- repeatedly materializes it via `fed_fout` for a federated `rowSums` (`fed_uark+`),
causing **multi-GB** coordinator↔worker traffic and large wall-clock overhead.

### Root Cause
The cost model used a **link-only** transfer model:

`t(bytes) ~= latency + bytes / net_bw`

but SystemDS federated PUT/GET time also includes significant **per-byte** overhead from
serialization/deserialization + RPC/Netty framing, so the **end-to-end** throughput for large
matrix transfers is much lower than raw iperf/tc bandwidth.

Concretely, under `lan` (tc/iperf ~10Gbps → 1250 MB/s), the observed kmeans hotspot implies an
effective end-to-end throughput of ~`180MB/s`, i.e., the planner under-estimated transfer cost
by ~7×. That gap is enough for MinST to prefer “FED + CP/FOUT materializations inside the kmeans++
loop”, even though it is a poor runtime choice.

> The matrix-size estimate itself is consistent (bytes-on-wire closely matches 3000×2100 FP64 × fanout);
> the dominant mis-estimation is **effective throughput**, not dimensions.

### Fix
1) **Remove** the bandwidth “efficiency factor” knob (do not rely on a hidden multiplier):
   - Removed `SYSDS_FED_COST_NET_BW_EFF` from:
     - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
     - `experiments/run_LAN_docker.sh` (env propagation/logging)

2) Make the network model reflect **federated PUT/GET** (network + ser/deser), without hardcoding
   an “effective BW” as a single knob:
   - Added a **serdes per-byte term** to the cost model:
     - `SYSDS_FED_COST_NET_SERDES_BW[_C2W/_W2C]` (MB/s)
     - Model: `t ~= latency + bytes/net_bw + bytes/serdes_bw`
   - In the experiment net profile (`experiments/docker/net_profiles.sh`):
     - keep `SYSDS_FED_COST_NET_BW*` derived from tc/iperf (link BW)
     - set `PROFILE_COST_SERDES_BW_MB=210` (from the measured hotspot), exported as
       `SYSDS_FED_COST_NET_SERDES_BW*`.

### Verification
Re-run MinST with the updated LAN cost bandwidth:
- Total execution time drops to ~`42.5s` (close to DP).
- `Server I/O bytes (read/written)` drops to ~`5.26GB / 96MB` (no repeated 3000x2100 uploads).

### Remaining / Follow-ups
- Consider documenting that `SYSDS_FED_COST_NET_BW*` is intended to be **effective end-to-end throughput**
  for SystemDS federated transfers, not raw iperf bandwidth.
- Validate other workloads / net profiles; WAN profiles still derive cost BW from shaped rate by default.
