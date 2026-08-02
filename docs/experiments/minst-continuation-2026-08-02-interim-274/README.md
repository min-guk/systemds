# Interim authenticated Docker results (274/336)

This directory freezes the no-duplicate launch baseline immediately before the
remaining 62 MinST cells started on the `e4f6bad` binary.

- Coverage: DP 84/84, FedAll 84/84, Heuristic 84/84, MinST 22/84.
- Four-planner matched cells: 22.
- Required ordering: `MinST <= DP <= Heuristic` and `DP <= FedAll`.
- Exact ordering: 0/22 matched cells pass.
- Ordering with 5% timing tolerance: 1/22 matched cells pass.
- Median runtime relative to DP: FedAll 1.184, Heuristic 1.182, MinST 1.659.

The four newly represented dimensions after the 270-cell snapshot are the
StepLM layout canary, two successful StepLM WAN prefix cells, and the successful
KMeans-derived-anchor canary.
These plots remain diagnostic rather than final performance evidence: the table
stitches successful attempt-1 rows from several committed planner binaries, and
MinST is still incomplete. Missing MinST points are unexecuted cells, not zeros.

## Reproduce

```bash
ROOT=/home/mchoi/g007-all-planners-minst-kmeans-derived-anchor-e4f6bad-d60da24-20260802-v1
OUT=docs/experiments/minst-continuation-2026-08-02-interim-274
python3 "$OUT/build_interim_dataset.py" "$ROOT" "$OUT"
Rscript "$OUT/plot_interim_results.R" "$OUT/authenticated_rows_274.csv" "$OUT"
```

`build_interim_dataset.py` fails closed on response-hash mismatches, duplicate
cells, non-attempt-1 rows, failed semantic oracles, fallback markers, restarts,
teardown failures, or any cardinality other than 274.

## Plots

- `runtime_{lan,wan_light,wan_mid}_interim_274.png`: execution time by workload,
  worker count, and planner.
- `matched_four_planner_ratios_interim_274.png`: runtime ratio to DP for the 22
  dimensions that currently contain all four planners.
- `coverage_by_planner_interim_274.png`: authenticated unique-cell coverage.
