# LogReg planning under 10 seconds

## Acceptance and evidence

- Target: total Memo planning (`factsNs + rulesNs + modelNs + optimizationNs + projectionNs`) < 10 seconds, same official Docker LogReg workload and JFR settings. Also report optimizer, compilation and execution separately.
- Baseline: previous final frozen `experiments/logreg-bottleneck-20261014/after-engine`, its measured planning 26.819446540s / optimizer 23.714206977s. Do not rerun an unchanged baseline or run host workload benchmarks.
- A candidate condition receives one Docker workload execution; do not repeat to select a faster sample. Keep every observation if another implementation is necessary.
- Required gates: OPTIMAL, exact objective and selected plan/output parity, CP/FED numeric comparison, no runtime audit violations, unchanged feasibility/privacy/UNKNOWN/DEFER/worker binding/exact cost contracts, regression tests and independent review.
- No speculative Cartesian source materialization, approximate arithmetic, arbitrary pruning or candidate deletion. No new dependencies. Preserve original workspace and existing uncommitted work.

## Work plan before edits

1. Use the current fresh AFTER JFR (movement 711/1696, support 333/1696, domain lookup 229/1696, 2,933,891 search nodes) and source to select stronger exact reductions.
2. Inspect reduced-core lower bounds: retain correlation between native unary cost and exact eliminated-tree boundary messages for the same owner; preserve term accounting, finite budgets and lexicographic ties.
3. Inspect model movement callbacks: reuse only immutable, fully identified analysis; evaluate dynamic selected layouts and exact accumulation in the existing order. Preserve lazy activation gates and failures.
4. Lock behavior with small differential/exhaustive regression fixtures before production edits, then implement bounded separate slices and review their interaction.
5. Compile and run relevant full regression suite; freeze candidate source/classes and hash manifests. Measure once through `run_LAN_docker.sh --joint-boundary-e2e --profile-jfr`.
6. Report target pass/fail honestly, exact parity, CPU/memory and remaining costs; no claim of under-10 success without the measured threshold.
