# 2026-09-12 planner changes

## Shared application and planning-time measurement

**Completed in `8fa253ccfa`.** FedFirst, AggLocal, DP-Local and DP-Global use `PlacementPlanApplication` for the common conversion/application boundary. `PlannerPipelineTiming` separates Decision, Diagnostics, Conversion, Application and Finalization; DML reports common preparation separately and retains the prior total.

Main integration preserved the upstream removal of legacy DP and exhaustive policy selectors. It compiled all sources, passed 316 selected tests and passed eight native planning-only checks (PCA/GLM, four planners, worker5 WAN-mid). Analysis fingerprints and emitted plans matched build13; all four DP costs and model fingerprints matched. The split timing sums and Local certificates were checked against Global.

Evidence: `/home/mchoi/so007-incremental-regional-evidence-20260910/validation/build-14-main-integration/` and `analysis/planner-split-build14-main-smoke/`. Earlier experiment notes remain in [the publication revision](https://github.com/min-guk/systemds/blob/8fa253ccfa52d652555f7146f22390390562aad8/docs/SESSION_ISSUES_2026-09-12.md).

Validation is targeted, not full-suite certification. The known foreign-action test that fails before its expected assertion was excluded; other tests in that class remained enabled. No privacy or runtime rule was relaxed.

## Remove superseded Regional algorithms

**Completed and verified.** User requested removing pre-v8 versions and related redundant material, then committing and pushing the cleanup.

The prior tree retained selectable old Certified/MBE, cost-shift, region-dual and remaining-exact paths alongside the current incremental optimizer. Cleanup makes the current v8 path the sole DP-Local implementation, removes those old engines and their exclusive tests, and removes old algorithm proposals/results/figures. Git history and external experiment data are retained. [Incremental Regional](INCREMENTAL_REGIONAL.md) documents the supported implementation and configuration.

The shared compensated factor evaluator moves from the removed controller into `RegionalSearchProblem`. Canonical objective/ownership checks, exact hard-conflict repair, root-table reuse, conservative bounds and the common four-planner application remain. Removed mode selections reject rather than silently changing algorithms; historical neutral runner markers remain accepted.

Before edits, 56 current incremental/seed/shared-preparation/timing/factory tests passed. The cleanup plan and exact deletion manifest were independently reviewed and recorded at `validation/v8-cleanup/` under the evidence root.

Main regression risks are changed seed assignments, lost auxiliary/hard constraints, weakened numerical validation and stale configuration selecting a different algorithm. Verification compares retained tests and fresh four-planner PCA/GLM plan hashes, DP cost bits, timing sums and every Local bound checkpoint with the build14 reference. No new performance claim is made from the smoke run.


Final verification: clean build16 compiled all 1,604 main sources and retained test sources. 255 selected tests in 39 classes passed. Two failures in additional KMeans/LM suites also reproduced on the unchanged build14 JAR with identical test sources; only those methods were excluded, retaining the other tests in both classes. The earlier foreign-action exclusion remains. Logs include the failed first build and the unchanged-build control; no whole-suite or style-check success is claimed.

The first cleanup review caught accidental activation of forced-state audit on the Local path. It was removed before the final build to preserve existing v8 behavior; this cleanup does not add forced-state semantics.

The final planning-only smoke ran PCA/GLM with four planners, worker5 WAN-mid and one fresh JVM per combination: 8/8 passed. Local ran without `incremental.enabled`; the retired, previously unused `initialBound` option was removed from the smoke protocol. All eight emitted plan hashes and analysis fingerprints match build14; all four DP objective bits and cost fingerprints match. Timing phase sums, Statistics agreement and both Local certificate trajectories passed. Final certificates are unchanged: PCA 2.395639%, GLM approximately 2.13e-13%. No performance conclusion is inferred from these eight runs.

Removed 63 obsolete source/test/document/figure files, including nine older engines. Java source/test references and final JAR entries for those engines are absent. `git diff --check` passed. The current model/evaluator change received independent review; root changes were reviewed separately. Final Java/test hashes match the frozen build16 source manifest; later edits only complete documentation.

Artifacts under the evidence root: `validation/build-16-v8-cleanup/`, `validation/v8-cleanup/{deletions.json,static-audit.json,build14-plan-parity.json,build14-extra-test-control.log}`, and `analysis/v8-cleanup-build16-smoke/audit.json`. Final JAR SHA256: `3336e2e3cc85ccf3efe681ea74f7b24373e7bbff71e151f3d64235645105cb59`.
