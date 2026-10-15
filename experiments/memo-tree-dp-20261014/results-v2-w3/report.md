# Memo Tree-DP benchmark report

Overall result: **INCOMPLETE/FAILED**.

Tree DP solves the retained unary/binary forest bottom-up. Deferred cyclic, wide, shared-movement, and legality factors contribute certified conditional minima to each node lower bound. Conflict resolution partitions one conflicting owner at a time while cached unaffected component messages remain reusable. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Status | Planning seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---|
| logreg | EXACT | failed | 0.000000 |  |  |  |  | container_failure |
| logreg | TREE_DP_GLOBAL | failed | 0.000000 |  |  |  |  | container_failure |
| logreg | TREE_DP_LOCAL | failed | 0.000000 |  |  |  |  | container_failure |
| glm | EXACT | failed | 0.000000 |  |  |  |  | container_failure |
| glm | TREE_DP_GLOBAL | failed | 0.000000 |  |  |  |  | container_failure |
| glm | TREE_DP_LOCAL | failed | 0.000000 |  |  |  |  | container_failure |
| gnmf | EXACT | failed | 0.000000 |  |  |  |  | container_failure |
| gnmf | TREE_DP_GLOBAL | failed | 0.000000 |  |  |  |  | container_failure |
| gnmf | TREE_DP_LOCAL | failed | 0.000000 |  |  |  |  | container_failure |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every Tree-DP interval:

- `ml:logreg:w3:source-fullcompile`: failed (EXACT baseline costs missing)
- `ml:glm:w3:source-fullcompile`: failed (EXACT baseline costs missing)
- `ml:gnmf:w3:source-fullcompile`: failed (EXACT baseline costs missing)

## Synthetic and Regional comparison

Synthetic same-model benchmark: **failed**. The Regional row uses an identical categorical variable/factor model; no Regional comparison is claimed for real workloads.

## Runtime evidence

- Federated training/runtime suite: failed, tests=None, wall=0.359s, peak container usage=None bytes.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
