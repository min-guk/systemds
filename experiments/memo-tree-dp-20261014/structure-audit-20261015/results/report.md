# Memo Tree-DP benchmark report

Overall result: **PASS**.

Reproduction command:

```sh
/home/mchoi/cofee-tree-dp-20261014/scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark --engine /home/mchoi/cofee-tree-dp-20261014/experiments/memo-tree-dp-20261014/structure-audit-20261015/engine --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z --output-root /home/mchoi/cofee-tree-dp-20261014/experiments/memo-tree-dp-20261014/structure-audit-20261015/results --workloads logreg,glm,gnmf --workers 3 --algorithms EXACT --watchdog-seconds 900 --search-nodes 20000 --frontier-nodes 10000 --factor-cells 256 --cache-entries 4096 --synthetic-repetitions 7 --skip-synthetic
```

Tree DP solves the retained unary/binary forest bottom-up. Deferred cyclic, wide, shared-movement, and legality factors contribute certified conditional minima to each node lower bound. Conflict resolution partitions one conflicting owner at a time while cached unaffected component messages remain reusable. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Status | Planner seconds | DP seconds | Conflict seconds | Wall seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| logreg | EXACT | passed | 3.171677 | unavailable | unavailable | 5.314411 | 4.201165 | 338060902 |  | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| glm | EXACT | passed | 8.508659 | unavailable | unavailable | 11.187651 | 9.88748 | 872205516 |  | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| gnmf | EXACT | passed | 0.775542 | unavailable | unavailable | 2.696791 | 1.535528 | 181403648 |  | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every Tree-DP interval:

- `ml:logreg:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true
- `ml:glm:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true
- `ml:gnmf:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true

## Synthetic and Regional comparison

Synthetic comparison was explicitly skipped.

## Runtime evidence

Runtime smoke was not requested; the table above is compile-only evidence.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
