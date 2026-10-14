# LogReg exact planning performance evidence

The final measured candidate plans the unchanged Docker `ml_logreg` workload in6.672535049s. See `candidate03-report.md`, `candidate03-evaluation.json`, `candidate03-differential.json` and `final-verification.json`. The Korean implementation report is `../../docs/FEDPLANNER_LOGREG_UNDER10_RESULT_2026-10-14_KO.md`.

Committed evidence includes plans, review conclusions, commands, test logs, measurement summaries, analysis scripts and SHA-256 manifests. Frozen engine directories, compiled probe outputs, raw JFR recordings, expanded JFR event streams and process IDs remain local artifacts; they are not source dependencies. Manifests preserve their provenance and reference the original archived paths. Commands containing absolute paths require corresponding local paths when reproduced.

The50-class suite is listed in `candidate03-unit-command.txt`; its440tests passed with assertions and host `-Xss8m`. Docker JVM/resource/workload conditions were unchanged. Each distinct candidate received exactly one official Docker JFR run. Known exclusions and measurement limitations are recorded in the final report.
