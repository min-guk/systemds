# Memo Tree-DP benchmark report

Overall result: **PASS**.

Reproduction command:

```sh
/home/mchoi/cofee-tree-dp-20261014/scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark --engine /home/mchoi/cofee-tree-dp-20261014/experiments/memo-local-certificate-first-20261015/engine-v8 --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z --output-root /home/mchoi/cofee-tree-dp-20261014/experiments/memo-local-certificate-first-20261015/evaluation/memory/heap-1536 --workloads logreg,glm,gnmf --workers 3 --algorithms EXACT,MEMO_LOCAL --watchdog-seconds 900 --heap-mib 1536 --search-nodes 2000 --frontier-nodes 10000 --factor-cells 256 --cache-entries 4096 --dual-direct-exact true --dual-seed-work 100000 --dual-factor-cells 256 --dual-direct-cells 2000000 --dual-cluster-cells 4096 --dual-improve-seed false --local-messages true --local-improve-seed true --factor-diagnostics false --factor-diagnostics-max-factors 10000 --dual-diagnostics false --dual-memory-bytes 9223372036854775807 --dual-diagnostic-nodes 9223372036854775807 --synthetic-repetitions 7 --skip-synthetic
```

The selected Memo algorithms use exact structural kernels or certified conflict search over the same immutable factors. Deferred cyclic, wide, shared-movement, and legality factors remain represented in the certified bound. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Heap MiB | Status | Planner seconds | DP seconds | Conflict seconds | Wall seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| logreg | EXACT | 1536 | passed | 2.946092 | unavailable | unavailable | 5.134397 | 3.964418 | 285317529 |  | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| logreg | MEMO_LOCAL | 1536 | passed | 5.309186 | unavailable | unavailable | 7.621007 | 6.405497 | 476158361 | 4027.6919033839307679491912296218004974068804813214228488504886627197265625000 | 4193.6771139917773391949515883144784800240500999279902316629886627197265625000 |  |
| glm | EXACT | 1536 | passed | 9.210022 | unavailable | unavailable | 11.825101 | 10.645179 | 727397171 |  | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| glm | MEMO_LOCAL | 1536 | passed | 11.127773 | unavailable | unavailable | 13.753494 | 12.505122 | 882900992 | 407.816699368096911075070332417983427417129682623908593086525797843933105468750000 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| gnmf | EXACT | 1536 | passed | 0.826080 | unavailable | unavailable | 2.603986 | 1.501161 | 171966464 |  | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_LOCAL | 1536 | passed | 1.180243 | unavailable | unavailable | 3.331904 | 2.004491 | 191050547 | 217.001402574278923762021458636947007780904073115380015224218368530273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every reported certificate interval:

- `ml:logreg:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true
- `ml:glm:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true
- `ml:gnmf:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true

## Synthetic and Regional comparison

Synthetic comparison was explicitly skipped.

## Runtime evidence

Runtime smoke was not requested; the table above is compile-only evidence.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
