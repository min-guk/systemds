# Memo Tree-DP benchmark report

Overall result: **INCOMPLETE/FAILED**.

Tree DP solves the retained unary/binary forest bottom-up. Deferred cyclic, wide, shared-movement, and legality factors contribute certified conditional minima to each node lower bound. Conflict resolution partitions one conflicting owner at a time while cached unaffected component messages remain reusable. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Status | Planning seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---|
| logreg | TREE_DP_GLOBAL | failed | 0.000000 |  | 734632345 | 2734.2283620653551280237767407929085072826325131245539523661136627197265625000 | 5729.6597890673082529890822712733723653943940234967158176004886627197265625000 | deterministic_resource_exhaustion |
| logreg | TREE_DP_LOCAL | failed | 0.000000 |  | 757281587 | 2734.2283620653551280237767407929085072826325131245539523661136627197265625000 | 5729.6597890673082529890822712733723653943940234967158176004886627197265625000 | deterministic_resource_exhaustion |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every Tree-DP interval:

- `ml:logreg:w3:source-fullcompile`: not-evaluated (EXACT baseline not selected)

## Synthetic and Regional comparison

Synthetic comparison was explicitly skipped.

## Runtime evidence

Runtime smoke was not requested; the table above is compile-only evidence.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
