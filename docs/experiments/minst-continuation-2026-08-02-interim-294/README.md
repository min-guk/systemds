# Interim authenticated Docker runtime grid (294/336)

This directory freezes 283 authenticated historical successes plus the first 11 authenticated successes from the
MinST `e18d326` unfinished-only continuation. The snapshot is diagnostic and incomplete; the live campaign continued
after it was frozen.

Primary graph:

- `runtime_grid_3x7_interim_294.png`
- rows: `LAN`, `WAN-light`, `WAN-mid`
- columns: `kmeans`, `pca`, `lm`, `l2svm`, `logreg`, `als`, `steplm`
- x-axis: worker count (`1..4`)
- y-axis: Docker execution time in seconds
- series: `DP`, `FedAll`, `Heuristic`, `MinST`

Each workload column uses one shared y-axis range across all three environment rows. Missing MinST points are
unexecuted cells, not zero-valued observations.

Coverage in this frozen snapshot is DP 84/84, FedAll 84/84, Heuristic 84/84, and MinST 42/84. All included cells are
attempt 1, semantic-oracle passing, fallback-free, restart-free, and cleanly torn down. The table stitches multiple
committed binaries and therefore is not a homogeneous final performance run.

Rebuild:

```bash
python3 build_interim_dataset.py \
  /home/mchoi/g007-all-planners-minst-native-local-e18d326-d60da24-20260802-v1 \
  "$PWD"
Rscript plot_runtime_grid.R authenticated_rows_294.csv runtime_grid_3x7_interim_294.png 294
```
