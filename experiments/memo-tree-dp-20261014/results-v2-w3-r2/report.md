# Memo Tree-DP benchmark report

Overall result: **INCOMPLETE/FAILED**.

Reproduction command:

```sh
/home/mchoi/cofee-tree-dp-20261014/scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark --engine /home/mchoi/cofee-tree-dp-20261014/experiments/memo-tree-dp-20261014/engine-v2 --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z --output-root /home/mchoi/cofee-tree-dp-20261014/experiments/memo-tree-dp-20261014/results-v2-w3-r2 --workloads logreg,glm,gnmf --workers 3 --algorithms EXACT,TREE_DP_GLOBAL,TREE_DP_LOCAL --watchdog-seconds 900 --search-nodes 2000 --frontier-nodes 10000 --factor-cells 256 --cache-entries 4096 --synthetic-repetitions 7 --runtime-smoke
```

Tree DP solves the retained unary/binary forest bottom-up. Deferred cyclic, wide, shared-movement, and legality factors contribute certified conditional minima to each node lower bound. Conflict resolution partitions one conflicting owner at a time while cached unaffected component messages remain reusable. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Status | Planner seconds | DP seconds | Conflict seconds | Wall seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| logreg | EXACT | passed | 2.536236 | unavailable | unavailable | 4.898759 | 3.624459 | 346344652 |  | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| logreg | TREE_DP_GLOBAL | failed | unavailable | 2.510226 | 1.338351 | 8.469036 |  | 783495987 | 2742.5359945775073230411065218167267210080950690098688937723636627197265625000 | 5729.6597890673082529890822712733723653943940234967158176004886627197265625000 | deterministic_resource_exhaustion |
| logreg | TREE_DP_LOCAL | failed | unavailable | 2.209135 | 1.370725 | 8.720911 |  | 978216550 | 2742.5359945775073230411065218167267210080950690098688937723636627197265625000 | 5729.6597890673082529890822712733723653943940234967158176004886627197265625000 | deterministic_resource_exhaustion |
| glm | EXACT | passed | 7.426108 | unavailable | unavailable | 10.447175 | 9.111425 | 1056964608 |  | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| glm | TREE_DP_GLOBAL | failed | unavailable | 6.434639 | 4.780798 | 20.045217 |  | 1258425417 | 366.189332578517516314003913817002417926960156080440356163308024406433105468750000 | 667.821051997678994881413287313846145407114640590862109092995524406433105468750000 | deterministic_resource_exhaustion |
| glm | TREE_DP_LOCAL | failed | unavailable | 8.822809 | 6.870005 | 25.597834 |  | 1239098064 | 366.189332578517516314003913817002417926960156080440356163308024406433105468750000 | 667.821051997678994881413287313846145407114640590862109092995524406433105468750000 | deterministic_resource_exhaustion |
| gnmf | EXACT | passed | 0.622825 | unavailable | unavailable | 2.595100 | 1.340158 | 170183884 |  | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | TREE_DP_GLOBAL | passed | 0.797597 | 0.142719 | 0.012321 | 2.885860 | 1.585193 | 193881702 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | TREE_DP_LOCAL | passed | 0.661564 | 0.129682 | 0.013136 | 2.804851 | 1.513497 | 141033472 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 227.047325383116814386166933852680832606196759115846361964941024780273437500 |  |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every Tree-DP interval:

- `ml:logreg:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=false
- `ml:glm:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=false
- `ml:gnmf:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true

## Synthetic and Regional comparison

Synthetic same-model benchmark: **passed**. The Regional row uses an identical categorical variable/factor model; no Regional comparison is claimed for real workloads.
- adversarial-shared-wide: EXACT mean=1628684 ns, nodes=207, conflicts=0, LB=1.1875, UB=1.1875
- adversarial-shared-wide: TREE_DP_GLOBAL mean=1901510 ns, nodes=20, conflicts=7, LB=1.1875, UB=1.1875
- adversarial-shared-wide: TREE_DP_LOCAL mean=1313229 ns, nodes=20, conflicts=7, LB=1.1875, UB=1.1875
- tree-32: EXACT mean=1925068 ns, nodes=64, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: TREE_DP_GLOBAL mean=1030721 ns, nodes=1, conflicts=0, LB=7.96875, UB=7.96875
- tree-32: TREE_DP_LOCAL mean=837130 ns, nodes=1, conflicts=0, LB=7.96875, UB=7.96875
- cycle-16: EXACT mean=1088172 ns, nodes=118, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: TREE_DP_GLOBAL mean=271517 ns, nodes=1, conflicts=0, LB=3.84375, UB=3.84375
- cycle-16: TREE_DP_LOCAL mean=249921 ns, nodes=1, conflicts=0, LB=3.84375, UB=3.84375
- diamond-8: EXACT mean=443748 ns, nodes=38, conflicts=0, LB=1.84375, UB=1.84375
- diamond-8: TREE_DP_GLOBAL mean=242925 ns, nodes=5, conflicts=2, LB=1.84375, UB=1.84375
- diamond-8: TREE_DP_LOCAL mean=241636 ns, nodes=5, conflicts=2, LB=1.84375, UB=1.84375
- shared-wide-8: EXACT mean=630347 ns, nodes=198, conflicts=0, LB=1.90625, UB=1.90625
- shared-wide-8: TREE_DP_GLOBAL mean=377170 ns, nodes=9, conflicts=4, LB=1.90625, UB=1.90625
- shared-wide-8: TREE_DP_LOCAL mean=382783 ns, nodes=9, conflicts=4, LB=1.90625, UB=1.90625
- epsilon-boundary-3: EXACT mean=163579 ns, nodes=14, conflicts=0, LB=100, UB=100
- epsilon-boundary-3: TREE_DP_GLOBAL mean=124429 ns, nodes=5, conflicts=2, LB=100, UB=100
- epsilon-boundary-3: TREE_DP_LOCAL mean=68374 ns, nodes=1, conflicts=0, LB=100, UB=105

## Runtime evidence

- Federated training/runtime suite: passed, tests=10, wall=8.793s, peak container usage=299368448 bytes.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
