# Session Issues — 2026-09-14

## Exact physical cost model counts data paths as workers

- **Status**: Resolved
- **Environment/condition**: DP exact physical cost model; a federated program whose `X` and `y` anchors use different files on the same worker endpoints.
- **Observed symptom**: Three physical workers were counted as six when both `host:port/data/features` and `host:port/data/labels` appeared in anchor metadata.
- **Root cause**: `ExactPhysicalCostModel.workerCount` deduplicated the full anchor `workerId`, which includes the data path, rather than the physical `host:port` endpoint.
- **Resolution**: Canonicalize every anchor partition with the existing `FederationUtils.canonicalFederatedWorkerAddress` before deduplication. Ports remain part of the canonical identity.
- **Scope**: No measured-runtime constant, network calibration, cost coefficient, solver limit, or candidate rule was changed. The production default 5% optimizer gap remains unchanged; any gap-zero planning run is experimental configuration outside this patch.
- **Modified files**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalWorkerCountTest.java`
- **Verification**: The focused unit test asserts that two files across the same three endpoints count as three workers and that two ports on one host count as two workers.
- **Decision basis**: Correct the physical cost input identity; do not reduce the planner candidate space or add runtime fallback.
- **Remaining issue**: This correction alone does not prove that the production-default DP-selected LM plan or measured runtime changes; both require a separate native experiment.
- **Potential regression risk**: Malformed anchor addresses could canonicalize to `null`; existing anchor construction requires non-blank identities, and the regression suite detects the known path/port identity cases. A future validation change should reject malformed endpoint metadata at anchor construction.

## LM-CG solver-gap and runtime follow-up

- **Status**: Verified for the bounded LM-CG experiment; general calibration remains open.
- **Environment**: Existing Docker WAN-Mid, 3 workers,50Kx128,8CPU/16GB. All runtime variants use this worker-count fix.
- **Intervention**: Existing per-run `sysds.fedplanner.regional.incremental.relativeGap=0`; production default remains0.05. No cost constants tuned.
- **Observation**: Strict solve reached exact lower=upper2809.0965728ms and removed Y prefetch; default corrected-worker objective2836.8670455ms retained it. Strict Cost-based and AggLocal have identical118 normalized runtime instructions.
- **Measured once per variant**: default Cost-based4.972s, strict Cost-based4.319s, AggLocal4.440s. Output parity passed at7 significant digits. Strict-vs-AggLocal difference is not an optimizer improvement claim because the plans are identical.
- **Validation artifact**: `/home/mchoi/lm-cg-followup-20260914/REPORT.md` and native receipts. One pre-execution network-invalid attempt retained and excluded; retry passed without relaxing validation.
- **Residual risk**: exact tolerance may cost more compilation time on larger workloads; worker-union accounting remains global rather than anchor-specific. Figures and existing published measurements unchanged.

- **Materialization diagnostic**: prefetch514.233ms; dispatch0.300ms, response wait504.074ms, coordinator bind/copy2.116ms. Waiting, not coordinator copy, dominates. Remote execution/codec/transport attribution remains open; no arbitrary cold-start surcharge was added. Diagnostic time excluded from runtime comparison.
