# Memo Tree-DP benchmark report

Overall result: **INCOMPLETE/FAILED**.

Reproduction command:

```sh
/home/mchoi/cofee-tree-dp-20261014/scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark --engine /home/mchoi/cofee-tree-dp-20261014/experiments/memo-tree-dp-20261014/engine-v2 --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z --output-root /home/mchoi/cofee-tree-dp-20261014/experiments/memo-tree-dp-20261014/results-v2-logreg-20k --workloads logreg --workers 3 --algorithms TREE_DP_LOCAL --watchdog-seconds 900 --search-nodes 20000 --frontier-nodes 10000 --factor-cells 256 --cache-entries 4096 --synthetic-repetitions 7 --skip-synthetic
```

Tree DP solves the retained unary/binary forest bottom-up. Deferred cyclic, wide, shared-movement, and legality factors contribute certified conditional minima to each node lower bound. Conflict resolution partitions one conflicting owner at a time while cached unaffected component messages remain reusable. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Status | Planner seconds | DP seconds | Conflict seconds | Wall seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| logreg | TREE_DP_LOCAL | failed | unavailable | 9.978382 | 6.804141 | 21.616106 |  | 812960972 | 2765.5744753378631135936469554966778139348804188557551242411136627197265625000 | 5325.6597890673082538772606909734975977332993579693720676004886627197265625000 | deterministic_resource_exhaustion |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every Tree-DP interval:

- `ml:logreg:w3:source-fullcompile`: not-evaluated (EXACT baseline not selected)

## Synthetic and Regional comparison

Synthetic comparison was explicitly skipped.

## Runtime evidence

Runtime smoke was not requested; the table above is compile-only evidence.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
