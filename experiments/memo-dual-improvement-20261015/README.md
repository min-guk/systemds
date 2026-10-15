# Memo Tree DP and dual-mode evaluation artifacts

The implementation, tests, experiment drivers, captured text evidence and engine
SHA-256 manifests are versioned. Compiled frozen engines and dependency JARs remain
local artifacts and are excluded by the experiment directories' `.gitignore` files.
The same rule applies to the preceding `memo-tree-dp-20261014` and
`memo-dual-mode-20261015` experiments. Captured JSON/CSV/log/XML/text files preserve
their original bytes through directory-local Git attributes.

See [the final technical report](../../docs/COFEE_MEMO_DUAL_MODE_IMPROVEMENT_REPORT_2026-10-15_KO.md)
for results, limitations and reproduction commands. Build a new engine with
`scripts/fedplanner/prepare_memo_tree_dp_engine.py`; use a new output directory.
Experiments require the sealed input stage and pinned Docker image recorded in
their manifests. Historical absolute paths describe the original machine and
must be supplied or adjusted when reproducing experiments elsewhere. Frozen
binary hash verification in `delivery-validation.json` records the local delivery
check; a fresh clone does not contain those binary directories.

The latest audit covers 208 compile attempts (205 passed, 3 preparation OOM
failures), 139 certificates and 787 bound points. It does not claim that all ML
training was executed end to end. Small federated training has separate runtime
evidence. The default optimizer remains EXACT.
