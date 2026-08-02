# Interim authenticated Docker results (282/336)

This directory freezes the last authenticated no-duplicate results before the
`e4f6bad` MinST continuation stopped at its first failed cell.

- Coverage: DP 84/84, FedAll 84/84, Heuristic 84/84, MinST 30/84.
- Four-planner matched cells: 30.
- Required ordering: `MinST <= DP <= Heuristic` and `DP <= FedAll`.
- Exact ordering: 0/30 matched cells pass.
- Ordering with 5% timing tolerance: 4/30 matched cells pass.
- Median runtime relative to DP: FedAll 1.417, Heuristic 1.395, MinST 1.509.

The eight rows added after the 274-cell snapshot are successful attempt-1
MinST rows for 2-worker KMeans WAN-light/WAN-mid, PCA all three profiles, and
LM all three profiles. The campaign then stopped without retry at
`workers=2|planner=MinST|workload=l2svm|profile=lan` because exact membership
materialization found multiple rule emissions. The frozen failure marker records
282 successes, 54 remaining cells, no retry, and zero residual Docker resources.

These plots remain diagnostic rather than final performance evidence: the table
stitches authenticated rows from several committed planner binaries, and MinST
is incomplete. Missing MinST points are unexecuted cells, not zeros.

## Reproduce

```bash
ROOT=/home/mchoi/g007-all-planners-minst-kmeans-derived-anchor-e4f6bad-d60da24-20260802-v1
OUT=docs/experiments/minst-continuation-2026-08-02-interim-282
python3 "$OUT/build_interim_dataset.py" "$ROOT" "$OUT"
Rscript "$OUT/plot_interim_results.R" "$OUT/authenticated_rows_282.csv" "$OUT"
```

`build_interim_dataset.py` fails closed on response-hash mismatches, duplicate
cells, non-attempt-1 rows, failed semantic oracles, fallback markers, restarts,
teardown failures, campaign freeze-contract mismatch, or any cardinality other
than 282.

## Plots

- `runtime_{lan,wan_light,wan_mid}_interim_282.png`: execution time by workload,
  worker count, and planner.
- `matched_four_planner_ratios_interim_282.png`: runtime ratio to DP for the 30
  dimensions that currently contain all four planners.
- `coverage_by_planner_interim_282.png`: authenticated unique-cell coverage.
