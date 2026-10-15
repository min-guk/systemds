# Memo Tree-DP benchmark report

Overall result: **PASS**.

Reproduction command:

```sh
/home/mchoi/cofee-tree-dp-20261014/scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark --engine /home/mchoi/cofee-tree-dp-20261014/experiments/memo-local-efficiency-20261015/engine-v1 --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z --output-root /home/mchoi/cofee-tree-dp-20261014/experiments/memo-local-efficiency-20261015/evaluation/runtime/runtime-and-regional --workloads gnmf --workers 3 --algorithms EXACT,MEMO_GLOBAL,MEMO_LOCAL --watchdog-seconds 900 --heap-mib 3072 --search-nodes 2000 --frontier-nodes 10000 --factor-cells 256 --cache-entries 4096 --dual-direct-exact true --dual-seed-work 100000 --dual-factor-cells 256 --dual-direct-cells 2000000 --dual-cluster-cells 4096 --dual-improve-seed false --local-messages true --local-improve-seed false --factor-diagnostics false --factor-diagnostics-max-factors 10000 --dual-diagnostics false --dual-memory-bytes 9223372036854775807 --dual-diagnostic-nodes 9223372036854775807 --synthetic-repetitions 7 --runtime-smoke
```

The selected Memo algorithms use exact structural kernels or certified conflict search over the same immutable factors. Deferred cyclic, wide, shared-movement, and legality factors remain represented in the certified bound. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Heap MiB | Status | Planner seconds | DP seconds | Conflict seconds | Wall seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| gnmf | EXACT | 3072 | passed | 0.741084 | unavailable | unavailable | 2.823458 | 1.554648 | 186646528 |  | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_GLOBAL | 3072 | passed | 0.685409 | unavailable | unavailable | 2.650697 | 1.404208 | 162424422 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_LOCAL | 3072 | passed | 0.998134 | unavailable | unavailable | 2.827369 | 1.67081 | 175636480 | 217.001402574278923762021458636947007780904073115380015224218368530273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every reported certificate interval:

- `ml:gnmf:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true

## Synthetic and Regional comparison

Synthetic same-model benchmark: **passed**. The Regional row uses an identical categorical variable/factor model; no Regional comparison is claimed for real workloads.
- adversarial-shared-wide: EXACT mean=2211937 ns, nodes=207, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: TREE_DP_GLOBAL mean=2491383 ns, nodes=20, conflicts=7, LB=1.1875, UB=1.1875
- adversarial-shared-wide: TREE_DP_LOCAL mean=1642209 ns, nodes=20, conflicts=7, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_GLOBAL mean=2897974 ns, nodes=0, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_LOCAL mean=4583806 ns, nodes=0, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_GLOBAL_SEARCH mean=5817637 ns, nodes=6, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_LOCAL_SEARCH mean=2760868 ns, nodes=0, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_LOCAL_NO_SEED mean=2389412 ns, nodes=0, conflicts=0, LB=1.1875, UB=1.1875
- tree-32: EXACT mean=1924857 ns, nodes=64, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: TREE_DP_GLOBAL mean=1259175 ns, nodes=1, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: TREE_DP_LOCAL mean=982503 ns, nodes=1, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: MEMO_GLOBAL mean=1963414 ns, nodes=0, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: MEMO_LOCAL mean=5837612 ns, nodes=0, conflicts=0, LB=7.78125, UB=8.15625
- cycle-16: EXACT mean=896873 ns, nodes=118, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: TREE_DP_GLOBAL mean=277664 ns, nodes=1, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: TREE_DP_LOCAL mean=282626 ns, nodes=1, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: MEMO_GLOBAL mean=1065006 ns, nodes=0, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: MEMO_LOCAL mean=2603183 ns, nodes=0, conflicts=0, LB=3.78125, UB=3.96875
- diamond-8: EXACT mean=339173 ns, nodes=38, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: TREE_DP_GLOBAL mean=229085 ns, nodes=5, conflicts=2, LB=1.84375, UB=1.84375
- diamond-8: TREE_DP_LOCAL mean=210483 ns, nodes=5, conflicts=2, LB=1.84375, UB=1.84375
- diamond-8: MEMO_GLOBAL mean=596076 ns, nodes=0, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: MEMO_LOCAL mean=1152721 ns, nodes=0, conflicts=0, LB=1.78125, UB=1.84375
- diamond-8: MEMO_GLOBAL_SEARCH mean=4501750 ns, nodes=14, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: MEMO_LOCAL_SEARCH mean=1204950 ns, nodes=0, conflicts=0, LB=1.78125, UB=1.84375
- diamond-8: MEMO_LOCAL_NO_SEED mean=1128793 ns, nodes=0, conflicts=0, LB=1.78125, UB=1.84375
- shared-wide-8: EXACT mean=540836 ns, nodes=198, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: TREE_DP_GLOBAL mean=391772 ns, nodes=9, conflicts=4, LB=1.90625, UB=1.90625
- shared-wide-8: TREE_DP_LOCAL mean=392113 ns, nodes=9, conflicts=4, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_GLOBAL mean=797709 ns, nodes=0, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_LOCAL mean=1287365 ns, nodes=0, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_GLOBAL_SEARCH mean=6034959 ns, nodes=21, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_LOCAL_SEARCH mean=1291453 ns, nodes=0, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_LOCAL_NO_SEED mean=2153769 ns, nodes=0, conflicts=0, LB=1.90625, UB=1.90625
- epsilon-boundary-3: EXACT mean=147333 ns, nodes=14, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: TREE_DP_GLOBAL mean=141534 ns, nodes=5, conflicts=2, LB=100, UB=100
- epsilon-boundary-3: TREE_DP_LOCAL mean=76210 ns, nodes=1, conflicts=0, LB=100, UB=105
- epsilon-boundary-3: MEMO_GLOBAL mean=398085 ns, nodes=0, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_LOCAL mean=190049 ns, nodes=0, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_GLOBAL_SEARCH mean=1337380 ns, nodes=3, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_LOCAL_SEARCH mean=190753 ns, nodes=0, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_LOCAL_NO_SEED mean=430711 ns, nodes=0, conflicts=0, LB=100, UB=100

## Runtime evidence

- Federated training/runtime suite: passed, tests=18, wall=11.543s, peak container usage=331769446 bytes.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
