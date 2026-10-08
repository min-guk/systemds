# Functional-map sparse storage microbenchmark

This benchmark compares the frozen OLD engine with the NEW engine using one benchmark class compiled once against OLD. It constructs a `domain × domain` functional relation with three legal rows, then runs the production boundary-leaf and exact-solve paths.

Run:

```bash
experiments/factorized-plan-space-20261008/functional-map-benchmark/run.sh
```

The default is `domain=4000`, two warmups, and five measured repetitions per engine. Override with `DOMAIN`, `WARMUPS`, and `REPEATS`. Results include planner-reported retained/materialized rows, median wall time, approximate current-thread allocated bytes, and process peak RSS from `/usr/bin/time`. The row counters are logical planner telemetry; they do not measure physical allocation. Allocation bytes and peak RSS are the physical-memory indicators in this benchmark.

This is a bounded synthetic representation benchmark. It demonstrates whether the functional relation remains proportional to its legal rows in these paths; it does not establish end-to-end FedPlanner runtime performance.

`run-production.sh` separately benchmarks `ExactPhysicalModel.build` and hard-factor freezing on the existing flat-private production fixture. Its recorded result is in `PRODUCTION_RESULTS.md`.
