# Memo Tree-DP benchmark report

Overall result: **PASS**.

Reproduction command:

```sh
/home/mchoi/cofee-tree-dp-20261014/scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark --engine /home/mchoi/cofee-tree-dp-20261014/experiments/memo-local-certificate-first-20261015/engine-v8 --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z --output-root /home/mchoi/cofee-tree-dp-20261014/experiments/memo-local-certificate-first-20261015/evaluation/runtime/runtime-and-regional --workloads gnmf --workers 3 --algorithms EXACT,MEMO_GLOBAL,MEMO_LOCAL --watchdog-seconds 900 --heap-mib 3072 --search-nodes 2000 --frontier-nodes 10000 --factor-cells 256 --cache-entries 4096 --dual-direct-exact true --dual-seed-work 100000 --dual-factor-cells 256 --dual-direct-cells 2000000 --dual-cluster-cells 4096 --dual-improve-seed false --local-messages true --local-improve-seed true --factor-diagnostics false --factor-diagnostics-max-factors 10000 --dual-diagnostics false --dual-memory-bytes 9223372036854775807 --dual-diagnostic-nodes 9223372036854775807 --synthetic-repetitions 7 --runtime-smoke
```

The selected Memo algorithms use exact structural kernels or certified conflict search over the same immutable factors. Deferred cyclic, wide, shared-movement, and legality factors remain represented in the certified bound. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Heap MiB | Status | Planner seconds | DP seconds | Conflict seconds | Wall seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| gnmf | EXACT | 3072 | passed | 0.775600 | unavailable | unavailable | 2.894834 | 1.612123 | 201012019 |  | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_GLOBAL | 3072 | passed | 0.650720 | unavailable | unavailable | 2.468904 | 1.341056 | 179725926 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_LOCAL | 3072 | passed | 1.133754 | unavailable | unavailable | 2.926961 | 1.791097 | 167562444 | 217.001402574278923762021458636947007780904073115380015224218368530273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every reported certificate interval:

- `ml:gnmf:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true

## Synthetic and Regional comparison

Synthetic same-model benchmark: **passed**. The Regional row uses an identical categorical variable/factor model; no Regional comparison is claimed for real workloads.
- adversarial-shared-wide: EXACT mean=2478373 ns, nodes=207, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: TREE_DP_GLOBAL mean=2417713 ns, nodes=20, conflicts=7, LB=1.1875, UB=1.1875
- adversarial-shared-wide: TREE_DP_LOCAL mean=1300227 ns, nodes=20, conflicts=7, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_GLOBAL mean=2412319 ns, nodes=0, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_LOCAL mean=3667903 ns, nodes=0, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_GLOBAL_SEARCH mean=4653452 ns, nodes=6, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_LOCAL_SEARCH mean=2360618 ns, nodes=0, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_LOCAL_NO_SEED mean=2919345 ns, nodes=0, conflicts=0, LB=1.1875, UB=1.1875
- tree-32: EXACT mean=2325332 ns, nodes=64, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: TREE_DP_GLOBAL mean=1127279 ns, nodes=1, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: TREE_DP_LOCAL mean=934350 ns, nodes=1, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: MEMO_GLOBAL mean=1550712 ns, nodes=0, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: MEMO_LOCAL mean=6266744 ns, nodes=0, conflicts=0, LB=7.78125, UB=8.15625
- cycle-16: EXACT mean=854303 ns, nodes=118, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: TREE_DP_GLOBAL mean=263547 ns, nodes=1, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: TREE_DP_LOCAL mean=279189 ns, nodes=1, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: MEMO_GLOBAL mean=1295090 ns, nodes=0, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: MEMO_LOCAL mean=3316218 ns, nodes=0, conflicts=0, LB=3.78125, UB=3.96875
- diamond-8: EXACT mean=354528 ns, nodes=38, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: TREE_DP_GLOBAL mean=252151 ns, nodes=5, conflicts=2, LB=1.84375, UB=1.84375
- diamond-8: TREE_DP_LOCAL mean=233545 ns, nodes=5, conflicts=2, LB=1.84375, UB=1.84375
- diamond-8: MEMO_GLOBAL mean=672237 ns, nodes=0, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: MEMO_LOCAL mean=1462231 ns, nodes=0, conflicts=0, LB=1.78125, UB=1.84375
- diamond-8: MEMO_GLOBAL_SEARCH mean=4999291 ns, nodes=14, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: MEMO_LOCAL_SEARCH mean=1300098 ns, nodes=0, conflicts=0, LB=1.78125, UB=1.84375
- diamond-8: MEMO_LOCAL_NO_SEED mean=1152150 ns, nodes=0, conflicts=0, LB=1.78125, UB=1.84375
- shared-wide-8: EXACT mean=589351 ns, nodes=198, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: TREE_DP_GLOBAL mean=337383 ns, nodes=9, conflicts=4, LB=1.90625, UB=1.90625
- shared-wide-8: TREE_DP_LOCAL mean=346151 ns, nodes=9, conflicts=4, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_GLOBAL mean=790654 ns, nodes=0, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_LOCAL mean=1299633 ns, nodes=0, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_GLOBAL_SEARCH mean=6555171 ns, nodes=21, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_LOCAL_SEARCH mean=1601119 ns, nodes=0, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_LOCAL_NO_SEED mean=1635016 ns, nodes=0, conflicts=0, LB=1.90625, UB=1.90625
- epsilon-boundary-3: EXACT mean=159020 ns, nodes=14, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: TREE_DP_GLOBAL mean=152009 ns, nodes=5, conflicts=2, LB=100, UB=100
- epsilon-boundary-3: TREE_DP_LOCAL mean=79866 ns, nodes=1, conflicts=0, LB=100, UB=105
- epsilon-boundary-3: MEMO_GLOBAL mean=402060 ns, nodes=0, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_LOCAL mean=196139 ns, nodes=0, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_GLOBAL_SEARCH mean=1356506 ns, nodes=3, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_LOCAL_SEARCH mean=194845 ns, nodes=0, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_LOCAL_NO_SEED mean=436505 ns, nodes=0, conflicts=0, LB=100, UB=100

## Runtime evidence

- Federated training/runtime suite: passed, tests=18, wall=12.719s, peak container usage=339004620 bytes.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
