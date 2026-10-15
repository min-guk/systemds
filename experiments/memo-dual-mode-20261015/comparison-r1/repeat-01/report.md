# Memo Tree-DP benchmark report

Overall result: **INCOMPLETE/FAILED**.

Reproduction command:

```sh
/home/mchoi/cofee-tree-dp-20261014/scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark --engine /home/mchoi/cofee-tree-dp-20261014/experiments/memo-dual-mode-20261015/engine-v1 --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z --output-root /home/mchoi/cofee-tree-dp-20261014/experiments/memo-dual-mode-20261015/comparison-r1/repeat-01 --workloads glm,gnmf,logreg --workers 3 --algorithms EXACT,MEMO_LOCAL,TREE_DP_GLOBAL,TREE_DP_LOCAL,MEMO_GLOBAL --watchdog-seconds 900 --heap-mib 3072 --search-nodes 2000 --frontier-nodes 10000 --factor-cells 256 --cache-entries 4096 --dual-direct-exact true --dual-seed-work 100000 --dual-factor-cells 256 --dual-direct-cells 2000000 --dual-memory-bytes 9223372036854775807 --dual-diagnostic-nodes 9223372036854775807 --synthetic-repetitions 7 --runtime-smoke
```

The selected Memo algorithms use exact structural kernels or certified conflict search over the same immutable factors. Deferred cyclic, wide, shared-movement, and legality factors remain represented in the certified bound. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Heap MiB | Status | Planner seconds | DP seconds | Conflict seconds | Wall seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| glm | EXACT | 3072 | passed | 7.910026 | unavailable | unavailable | 10.754452 | 9.574277 | 1145682526 |  | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| glm | MEMO_LOCAL | 3072 | passed | 8.505941 | unavailable | unavailable | 11.452310 | 10.231316 | 1002438656 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| glm | TREE_DP_GLOBAL | 3072 | failed | unavailable | 7.274860 | 5.272055 | 22.064379 |  | 1394790629 | 366.189332578517516314003913817002417926960156080440356163308024406433105468750000 | 667.821051997678994881413287313846145407114640590862109092995524406433105468750000 | deterministic_resource_exhaustion |
| glm | TREE_DP_LOCAL | 3072 | failed | unavailable | 6.779087 | 4.832783 | 20.212508 |  | 1400159338 | 366.189332578517516314003913817002417926960156080440356163308024406433105468750000 | 667.821051997678994881413287313846145407114640590862109092995524406433105468750000 | deterministic_resource_exhaustion |
| glm | MEMO_GLOBAL | 3072 | passed | 8.059286 | unavailable | unavailable | 11.534033 | 10.021419 | 928304332 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| gnmf | EXACT | 3072 | passed | 0.883069 | unavailable | unavailable | 3.016907 | 1.798632 | 203318886 |  | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_LOCAL | 3072 | passed | 0.827746 | unavailable | unavailable | 2.864497 | 1.571959 | 156237824 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | TREE_DP_GLOBAL | 3072 | passed | 0.802951 | 0.136013 | 0.012006 | 2.841069 | 1.595202 | 170079027 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | TREE_DP_LOCAL | 3072 | passed | 0.649587 | 0.114564 | 0.010538 | 2.394033 | 1.320298 | 169449881 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 227.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_GLOBAL | 3072 | passed | 0.704978 | unavailable | unavailable | 2.537962 | 1.378358 | 169869312 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| logreg | EXACT | 3072 | passed | 3.621252 | unavailable | unavailable | 6.186232 | 4.960688 | 401814323 |  | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| logreg | MEMO_LOCAL | 3072 | passed | 3.306154 | unavailable | unavailable | 5.812708 | 4.477365 | 272000614 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| logreg | TREE_DP_GLOBAL | 3072 | failed | unavailable | 2.517146 | 1.556220 | 8.810191 |  | 839280230 | 2742.5359945775073230411065218167267210080950690098688937723636627197265625000 | 5729.6597890673082529890822712733723653943940234967158176004886627197265625000 | deterministic_resource_exhaustion |
| logreg | TREE_DP_LOCAL | 3072 | failed | unavailable | 2.072513 | 1.186406 | 7.565373 |  | 738407219 | 2742.5359945775073230411065218167267210080950690098688937723636627197265625000 | 5729.6597890673082529890822712733723653943940234967158176004886627197265625000 | deterministic_resource_exhaustion |
| logreg | MEMO_GLOBAL | 3072 | passed | 2.832271 | unavailable | unavailable | 5.162398 | 3.859501 | 340262912 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every reported certificate interval:

- `ml:glm:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=false
- `ml:gnmf:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true
- `ml:logreg:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=false

## Synthetic and Regional comparison

Synthetic same-model benchmark: **passed**. The Regional row uses an identical categorical variable/factor model; no Regional comparison is claimed for real workloads.
- adversarial-shared-wide: EXACT mean=2070307 ns, nodes=207, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: TREE_DP_GLOBAL mean=2388757 ns, nodes=20, conflicts=7, LB=1.1875, UB=1.1875
- adversarial-shared-wide: TREE_DP_LOCAL mean=1398809 ns, nodes=20, conflicts=7, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_GLOBAL mean=2762337 ns, nodes=0, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_LOCAL mean=1934080 ns, nodes=0, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_GLOBAL_SEARCH mean=11659487 ns, nodes=17, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_LOCAL_SEARCH mean=6819499 ns, nodes=17, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: MEMO_LOCAL_NO_SEED mean=8493509 ns, nodes=19, conflicts=0, LB=1.1875, UB=1.1875
- tree-32: EXACT mean=1919863 ns, nodes=64, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: TREE_DP_GLOBAL mean=1340672 ns, nodes=1, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: TREE_DP_LOCAL mean=1204003 ns, nodes=1, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: MEMO_GLOBAL mean=2020200 ns, nodes=0, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: MEMO_LOCAL mean=1765352 ns, nodes=0, conflicts=0, LB=7.96875, UB=7.96875
- cycle-16: EXACT mean=866210 ns, nodes=118, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: TREE_DP_GLOBAL mean=323006 ns, nodes=1, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: TREE_DP_LOCAL mean=310130 ns, nodes=1, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: MEMO_GLOBAL mean=1159452 ns, nodes=0, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: MEMO_LOCAL mean=1102300 ns, nodes=0, conflicts=0, LB=3.84375, UB=3.84375
- diamond-8: EXACT mean=369434 ns, nodes=38, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: TREE_DP_GLOBAL mean=304188 ns, nodes=5, conflicts=2, LB=1.84375, UB=1.84375
- diamond-8: TREE_DP_LOCAL mean=281866 ns, nodes=5, conflicts=2, LB=1.84375, UB=1.84375
- diamond-8: MEMO_GLOBAL mean=652529 ns, nodes=0, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: MEMO_LOCAL mean=664340 ns, nodes=0, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: MEMO_GLOBAL_SEARCH mean=7573138 ns, nodes=40, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: MEMO_LOCAL_SEARCH mean=7936510 ns, nodes=40, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: MEMO_LOCAL_NO_SEED mean=9593018 ns, nodes=68, conflicts=0, LB=1.84375, UB=1.84375
- shared-wide-8: EXACT mean=709481 ns, nodes=198, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: TREE_DP_GLOBAL mean=483807 ns, nodes=9, conflicts=4, LB=1.90625, UB=1.90625
- shared-wide-8: TREE_DP_LOCAL mean=485045 ns, nodes=9, conflicts=4, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_GLOBAL mean=835702 ns, nodes=0, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_LOCAL mean=824624 ns, nodes=0, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_GLOBAL_SEARCH mean=14400132 ns, nodes=60, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_LOCAL_SEARCH mean=10698156 ns, nodes=60, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: MEMO_LOCAL_NO_SEED mean=10336099 ns, nodes=61, conflicts=0, LB=1.90625, UB=1.90625
- epsilon-boundary-3: EXACT mean=143644 ns, nodes=14, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: TREE_DP_GLOBAL mean=166943 ns, nodes=5, conflicts=2, LB=100, UB=100
- epsilon-boundary-3: TREE_DP_LOCAL mean=111505 ns, nodes=1, conflicts=0, LB=100, UB=105
- epsilon-boundary-3: MEMO_GLOBAL mean=353755 ns, nodes=0, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_LOCAL mean=341285 ns, nodes=0, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_GLOBAL_SEARCH mean=1372101 ns, nodes=3, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_LOCAL_SEARCH mean=355106 ns, nodes=0, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: MEMO_LOCAL_NO_SEED mean=603201 ns, nodes=1, conflicts=0, LB=100, UB=105

## Runtime evidence

- Federated training/runtime suite: passed, tests=10, wall=9.039s, peak container usage=311531929 bytes.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
