# Memo Tree-DP benchmark report

Overall result: **PASS**.

Reproduction command:

```sh
/home/mchoi/cofee-tree-dp-20261014/scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark --engine /home/mchoi/cofee-tree-dp-20261014/experiments/memo-local-efficiency-20261015/engine-v1 --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z --output-root /home/mchoi/cofee-tree-dp-20261014/experiments/memo-local-efficiency-20261015/evaluation/comparison/after-06 --workloads logreg,glm,gnmf --workers 3 --algorithms MEMO_LOCAL,EXACT,MEMO_GLOBAL --watchdog-seconds 900 --heap-mib 3072 --search-nodes 2000 --frontier-nodes 10000 --factor-cells 256 --cache-entries 4096 --dual-direct-exact true --dual-seed-work 100000 --dual-factor-cells 256 --dual-direct-cells 2000000 --dual-cluster-cells 4096 --dual-improve-seed false --local-messages true --local-improve-seed false --factor-diagnostics false --factor-diagnostics-max-factors 10000 --dual-diagnostics false --dual-memory-bytes 9223372036854775807 --dual-diagnostic-nodes 9223372036854775807 --synthetic-repetitions 7 --skip-synthetic
```

The selected Memo algorithms use exact structural kernels or certified conflict search over the same immutable factors. Deferred cyclic, wide, shared-movement, and legality factors remain represented in the certified bound. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Heap MiB | Status | Planner seconds | DP seconds | Conflict seconds | Wall seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| logreg | MEMO_LOCAL | 3072 | passed | 4.583368 | unavailable | unavailable | 7.018693 | 5.65121 | 455711129 | 4027.6919033839307679491912296218004974068804813214228488504886627197265625000 | 4193.6771139917773391949515883144784800240500999279902316629886627197265625000 |  |
| logreg | EXACT | 3072 | passed | 2.727165 | unavailable | unavailable | 4.794008 | 3.651132 | 313943654 |  | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| logreg | MEMO_GLOBAL | 3072 | passed | 2.703831 | unavailable | unavailable | 4.942256 | 3.64053 | 341416345 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| glm | MEMO_LOCAL | 3072 | passed | 7.585629 | unavailable | unavailable | 10.553356 | 9.145171 | 1026346188 | 407.816699368096911075070332417983427417129682623908593086525797843933105468750000 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| glm | EXACT | 3072 | passed | 8.537149 | unavailable | unavailable | 11.408370 | 10.148193 | 910373683 |  | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| glm | MEMO_GLOBAL | 3072 | passed | 10.068694 | unavailable | unavailable | 12.831386 | 11.537008 | 1024353894 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| gnmf | MEMO_LOCAL | 3072 | passed | 0.791350 | unavailable | unavailable | 2.708929 | 1.408652 | 192308838 | 217.001402574278923762021458636947007780904073115380015224218368530273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | EXACT | 3072 | passed | 0.619000 | unavailable | unavailable | 2.369090 | 1.247087 | 158020403 |  | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_GLOBAL | 3072 | passed | 0.629789 | unavailable | unavailable | 2.744264 | 1.291629 | 154455244 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every reported certificate interval:

- `ml:logreg:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true
- `ml:glm:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true
- `ml:gnmf:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true

## Synthetic and Regional comparison

Synthetic comparison was explicitly skipped.

## Runtime evidence

Runtime smoke was not requested; the table above is compile-only evidence.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
