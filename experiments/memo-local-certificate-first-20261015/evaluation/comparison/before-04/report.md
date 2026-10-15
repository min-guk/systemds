# Memo Tree-DP benchmark report

Overall result: **PASS**.

Reproduction command:

```sh
/home/mchoi/cofee-tree-dp-20261014/scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark --engine /home/mchoi/cofee-tree-dp-20261014/experiments/memo-dual-improvement-20261015/engine-v3 --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z --output-root /home/mchoi/cofee-tree-dp-20261014/experiments/memo-local-certificate-first-20261015/evaluation/comparison/before-04 --workloads gnmf,logreg,glm --workers 3 --algorithms EXACT,MEMO_GLOBAL,MEMO_LOCAL --watchdog-seconds 900 --heap-mib 3072 --search-nodes 2000 --frontier-nodes 10000 --factor-cells 256 --cache-entries 4096 --dual-direct-exact true --dual-seed-work 100000 --dual-factor-cells 256 --dual-direct-cells 2000000 --dual-cluster-cells 4096 --dual-improve-seed false --local-messages true --local-improve-seed true --factor-diagnostics false --factor-diagnostics-max-factors 10000 --dual-diagnostics false --dual-memory-bytes 9223372036854775807 --dual-diagnostic-nodes 9223372036854775807 --synthetic-repetitions 7 --skip-synthetic
```

The selected Memo algorithms use exact structural kernels or certified conflict search over the same immutable factors. Deferred cyclic, wide, shared-movement, and legality factors remain represented in the certified bound. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Heap MiB | Status | Planner seconds | DP seconds | Conflict seconds | Wall seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| gnmf | EXACT | 3072 | passed | 0.736851 | unavailable | unavailable | 2.651149 | 1.419448 | 169974169 |  | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_GLOBAL | 3072 | passed | 0.825679 | unavailable | unavailable | 2.785479 | 1.584887 | 142291763 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_LOCAL | 3072 | passed | 0.776837 | unavailable | unavailable | 2.946437 | 1.595107 | 172910182 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| logreg | EXACT | 3072 | passed | 2.866955 | unavailable | unavailable | 5.299166 | 4.02708 | 262982860 |  | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| logreg | MEMO_GLOBAL | 3072 | passed | 3.160229 | unavailable | unavailable | 5.598642 | 4.389913 | 296327577 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| logreg | MEMO_LOCAL | 3072 | passed | 3.053293 | unavailable | unavailable | 5.454445 | 4.127317 | 301151027 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| glm | EXACT | 3072 | passed | 8.485724 | unavailable | unavailable | 11.493986 | 10.113353 | 893806182 |  | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| glm | MEMO_GLOBAL | 3072 | passed | 8.134248 | unavailable | unavailable | 11.207655 | 9.818891 | 871681228 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| glm | MEMO_LOCAL | 3072 | passed | 8.121670 | unavailable | unavailable | 11.465426 | 10.043735 | 1062207488 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every reported certificate interval:

- `ml:gnmf:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true
- `ml:logreg:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true
- `ml:glm:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true

## Synthetic and Regional comparison

Synthetic comparison was explicitly skipped.

## Runtime evidence

Runtime smoke was not requested; the table above is compile-only evidence.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
