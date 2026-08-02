# Interim authenticated Docker results (270/336)

This directory is a reproducible snapshot of every authenticated, unique, successful
Docker cell available when the MinST continuation first failed on StepLM.

- Coverage: DP 84/84, FedAll 84/84, Heuristic 84/84, MinST 18/84.
- Four-planner matched cells: 18.
- Required ordering: `MinST <= DP <= Heuristic` and `DP <= FedAll`.
- Exact ordering: 0/18 matched cells pass.
- Ordering with 5% timing tolerance: 1/18 matched cells pass.
- Median runtime relative to DP: FedAll 1.167, Heuristic 1.138, MinST 1.632.

These plots are diagnostic rather than final performance evidence. The table stitches
successful attempt-1 rows from several committed planner binaries without rerunning
completed cells, while MinST is incomplete. Missing MinST points are unexecuted cells,
not zero-valued observations. Final ordering must therefore be audited again after all
336 cells have succeeded under the accepted Docker-only campaign protocol.

## Reproduce

```bash
ROOT=/home/mchoi/g007-all-planners-minst-l2svm-worker-pool-5126afc-d60da24-20260802-v1
OUT=docs/experiments/minst-continuation-2026-08-02-interim-270
python3 "$OUT/build_interim_dataset.py" "$ROOT" "$OUT"
Rscript "$OUT/plot_interim_results.R" "$OUT/authenticated_rows_270.csv" "$OUT"
```

`build_interim_dataset.py` fails closed on response-hash mismatches, duplicate cells,
non-attempt-1 rows, failed semantic oracles, fallback markers, or an unexpected
`262 + 8 = 270` cardinality. See `summary.json` for hashes and aggregate values.

## Plots

- `runtime_{lan,wan_light,wan_mid}_interim_270.png`: execution time by workload,
  worker count, and planner.
- `matched_four_planner_ratios_interim_270.png`: runtime ratio to DP for the 18
  dimensions that currently contain all four planners.
- `coverage_by_planner_interim_270.png`: authenticated unique-cell coverage.
