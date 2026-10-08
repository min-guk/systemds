# General factorized plan space evidence

Baseline: `e468797556e6789100736355ca2463941302221f`.

Current validation: `verification.json`, `tests.log`, `main-sha256.json`, `tests-sha256.json`, and `source-verification.json`. Full Maven suite was not run. `verify.py` rebuilds changed sources over the pinned baseline engine and runs the targeted integration suite.

Current Docker comparison: `workload-comparison.json`. The weighted authority regression is independently checked in `final-weighted-authority-comparison.json`. It compares exact selected source, action, binding and proof information, beyond equal outputs or costs. `compare_receipt_authority.py` and its tests reject missing inventories.

Historical evidence remains intentionally available: `pre-fix-weighted-authority-comparison.json` failed, and `workload-comparison-pre-authority-fix.json` contains invalidated weighted optimization numbers. Files containing `provisional`, `pre-fix`, or early `integration-*` are not the final verdict. `root-selected-authority-comparison.json` is the diagnostic that exposed the pre-fix weighted defect, not a final success report.

Baseline/revised hash probes establish that the updated old fingerprint constants already matched the baseline; they do not justify arbitrary golden regeneration.

The Korean implementation report, scope limitations, measurement conditions, and reproduction commands are in [FEDPLANNER_GENERAL_RELATIONS_RESULT_2026-10-08_KO.md](../../docs/FEDPLANNER_GENERAL_RELATIONS_RESULT_2026-10-08_KO.md). Full engines and Docker logs are archived under `/grid/3/cofee-lm-sweep-mchoi-20260914/general-factorized-plan-space-20261008/`.

Raw generated logs/JSON evidence are retained in the preserved workspace on `/grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/`; they are not required checked-in inputs. The compile/test command manifests are retained here because the follow-up verifier uses their pinned source/test inventory. Current large-workload status is in `docs/FEDPLANNER_COFEE_LARGE_VALIDATION_2026-10-08_KO.md`; historical synthetic results do not establish large-workload planning performance.
