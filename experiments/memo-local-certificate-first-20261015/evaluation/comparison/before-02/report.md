# Memo Tree-DP benchmark report

Overall result: **PASS**.

Reproduction command:

```sh
/home/mchoi/cofee-tree-dp-20261014/scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark --engine /home/mchoi/cofee-tree-dp-20261014/experiments/memo-dual-improvement-20261015/engine-v3 --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z --output-root /home/mchoi/cofee-tree-dp-20261014/experiments/memo-local-certificate-first-20261015/evaluation/comparison/before-02 --workloads gnmf,glm,logreg --workers 3 --algorithms EXACT,MEMO_LOCAL,MEMO_GLOBAL --watchdog-seconds 900 --heap-mib 3072 --search-nodes 2000 --frontier-nodes 10000 --factor-cells 256 --cache-entries 4096 --dual-direct-exact true --dual-seed-work 100000 --dual-factor-cells 256 --dual-direct-cells 2000000 --dual-cluster-cells 4096 --dual-improve-seed false --local-messages true --local-improve-seed true --factor-diagnostics false --factor-diagnostics-max-factors 10000 --dual-diagnostics false --dual-memory-bytes 9223372036854775807 --dual-diagnostic-nodes 9223372036854775807 --synthetic-repetitions 7 --skip-synthetic
```

The selected Memo algorithms use exact structural kernels or certified conflict search over the same immutable factors. Deferred cyclic, wide, shared-movement, and legality factors remain represented in the certified bound. Every reported upper bound is a fully validated assignment.

Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic resource exhaustion and the external watchdog are failures, never approximate-success conditions.

## Source full-compile results

| Workload | Algorithm | Heap MiB | Status | Planner seconds | DP seconds | Conflict seconds | Wall seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| gnmf | EXACT | 3072 | passed | 0.754355 | unavailable | unavailable | 2.977600 | 1.596402 | 174902476 |  | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_LOCAL | 3072 | passed | 0.862412 | unavailable | unavailable | 3.352385 | 1.80355 | 150051225 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| gnmf | MEMO_GLOBAL | 3072 | passed | 0.867047 | unavailable | unavailable | 3.102984 | 1.697911 | 189582540 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 | 217.047325383116814386166933852680832606196759115846361964941024780273437500 |  |
| glm | EXACT | 3072 | passed | 9.009157 | unavailable | unavailable | 12.504050 | 10.869394 | 1042179686 |  | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| glm | MEMO_LOCAL | 3072 | passed | 8.272064 | unavailable | unavailable | 10.879285 | 9.663612 | 1137092591 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| glm | MEMO_GLOBAL | 3072 | passed | 9.768530 | unavailable | unavailable | 12.593025 | 11.198088 | 956511027 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 | 426.222945173122011978444871186836413398668632623866869835183024406433105468750000 |  |
| logreg | EXACT | 3072 | passed | 3.059226 | unavailable | unavailable | 5.364386 | 4.100689 | 317823385 |  | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| logreg | MEMO_LOCAL | 3072 | passed | 2.783342 | unavailable | unavailable | 5.041425 | 3.770594 | 311322214 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |
| logreg | MEMO_GLOBAL | 3072 | passed | 2.940260 | unavailable | unavailable | 5.284381 | 3.951792 | 294335283 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 | 4041.3312164761625187616576028123462072105809284039423801004886627197265625000 |  |

Each workload group used byte-identical source. Memo EXACT was executed independently on the same frozen model and checked against every reported certificate interval:

- `ml:gnmf:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true
- `ml:glm:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true
- `ml:logreg:w3:source-fullcompile`: passed; valid bounds=true, requested guarantee established=true

## Synthetic and Regional comparison

Synthetic comparison was explicitly skipped.

## Runtime evidence

Runtime smoke was not requested; the table above is compile-only evidence.

Machine-readable bounds, full progression, counters, commands, and failure categories are in `summary.json` and the per-attempt directories.
