# Post-certificate timing and trace comparison

## Issue: final certificate precedes complete FedPlanner time

- Status: implementation and bounded validation complete; 24/24 passed.
- Environment: so007, native JVM planning-only; PCA and GLM, worker 5 / WAN-Mid; X PRIVATE_AGGREGATE / Y PUBLIC when applicable. User explicitly authorized native planning previously and the current fair logging comparison. No Docker or workload execution is involved.
- Symptom: previous v8 PCA rep1 cert0.253167s/planner0.422913s; GLM cert1.048667s/planner1.899566s. Calling the difference cleanup was imprecise.
- Evidence: same compile clock starts in DMLTranslator and is sampled at checkpoints; post-checkpoint work includes selection construction, full canonical contribution audit, projection, adapter, emission and final boundary verification. Logs after GLM certificate contain7811 contributions and approximately11.1MB PlannerTrace text.
- Resolution: add identical phase boundaries to Global and Local; measure trace writes separately from phase remainder; opt-in summary trace suppresses detail output/formatting and optional Global alternative diagnostics. All canonical cost reevaluation and legality/emission verification remain. Receipt-required Emission-Select/Candidate/RegistryWrite detail records are also retained so the unchanged collector can verify exact record counts; summary therefore means reduced optional detail, not all detail disabled. Solver/model/threshold rules do not change. Default detail trace remains enabled.
- Files: FederatedPlannerTrace, ExactPhysicalCostModel, FederatedPlanLocalCost, FederatedPlanExact, DMLTranslator and focused tests. New run_postcert.py/analyze_postcert.py provide same-JAR detailed/summary comparison.
- Validation plan: targeted Java tests plus existing236-test suite; PCA/GLM×Global/Local×full/summary×3=24 fresh JVM runs, rotated positions, identical inputs/JAR/CPU8-15/common preprocessing; compare objective bits, cost/analysis fingerprints, emission plans and full bounds trajectories, preserving failed rows. Compare phase medians without pooling old campaigns.
- Remaining issues: GLM Local summary retains approximately284ms projection and270ms emission; its3-run median total improves7.9% with substantial variation. PCA median improves28.2%. These are observed medians, not stable causal speedup estimates. Trace-write timers exclude formatting; phase remainder includes formatting, normal work, GC and scheduling. Detailed-trace evaluation itself adds overhead; results are a diagnostic cohort, not production trace-off timing.
- Regression risk: suppressing logs must not suppress validators, certificates or receipts. Tests verify canonical mismatch still fails and summaries are retained; experiment checks plan and trajectory parity.
- Decision basis: observational trace/timing change, same policy applied to both algorithms. No privacy/placement/runtime policy is relaxed.

## Completion evidence

- Build12:245 Java tests/28 classes and3 Python tests pass; previous17 unrelated baseline failures in2 classes remain excluded.
- Campaign postcert-build12-pca-glm-w5-wanmid-3rep:24/24 complete; full/summary parity12/12, Global oracle certificate checks12/12, all final gaps<=5%.
- Global GLM summary has higher OPTIMIZATION times in first2 repeats despite equal recorded work counters; root cause unresolved. Do not attribute entire total difference to trace removal.
- Report: /home/mchoi/so007-incremental-regional-evidence-20260910/analysis/postcert-build12-pca-glm-w5-wanmid-3rep/POSTCERT_IMPLEMENTATION_AND_FINDINGS_KO.md
- This completion note was updated after the frozen campaign; Java sources remain build12.

## Four-planner decision/application separation (complete)

- User requests shared conversion/application and comparison of decision-planning time only.
- Cause: shared emitter existed, but four roots had separate handoffs and old FedPlanner time included selected-plan conversion, diagnostics, application and final validation.
- Resolution: common PlacementPlanApplication, shared immutable normalization (reuse only proven immutable exact-owner result), unchanged transaction/receipt validation; new ordered PlannerPipelineTiming. DML logs common preparation separately and disjoint successful timing. Statistics retains old total and exposes Decision/Diagnostics/Conversion/Application/Finalization plus CommonPreparation.
- Files: four planner roots, PlacementPlanApplication, PlannerPipelineTiming, PlacementPlannerAdapter, DMLTranslator, Statistics; new boundary/timing tests and separate runner/analyzer.
- Behavior lock: existing build12 tests245pass; extra pre-change41tests had40pass and1existingerror in PlacementEmissionDerivedAuthorityRedTest.structurallyEqualForeignDerivedActionRejectsBeforeMutation. Foreign action is rejected by constructor before test expects emission-time failure. Full log validation/planner-split-baseline-tests.log. Exclude only this method, keep other11 tests from class and all other controls. No legality change to make stale test pass.
- Verification: build13 Java294/37classes pass; Python4pass;24/24 PCA/GLM4plannersx3planning-only native JVM runs pass. Exact integer phase sum and printedStatsagreement verified24/24, all4 analysisfingerprints same percell;12/12 build12 DP objective/model/emissionparity;6/6 final Localcert<=5%.
- Risks: wrong timing phase, duplicate normalization, lost selected authority, changed plan or double emission. Detect with deterministic state tests, immutable/foreign-owner tests, all4receipt/application test, old emission regressions and frozen build12 plan parity.
- Scope: no solver or privacy/runtime change; user native-JVM authorization remains active.

- Results (3rep median Decision seconds): PCA FedFirst0.149370/AggLocal0.182604/Local0.247212/Global0.267748; GLM1.119668/0.951838/1.650911/3.543881. Existing total remains separate; no relabeling olddata.
- Report: /home/mchoi/so007-incremental-regional-evidence-20260910/analysis/planner-split-build13-pca-glm-w5-wanmid-3rep/PLANNER_DECISION_APPLICATION_REPORT_KO.md
- Timing includes in-searchtrace; onlypostselectiondiagnostics isolated. Common preparation observed~0.56–0.65s PCA and~5.65–6.65s GLM median and reportedseparately. Noclaim these costsdisappeared.
- Completed docsupdated afterfrozenstudy;Javaunchanged.


## Publication on current origin/main (integration verified)

- **Request:** commit the implemented planner changes and push to min-guk/systemds origin/main.
- **Problem:** tested build13 source was based on 28eb8072ee; origin/main advanced to ae0f624ef18dd080aa288fc8715eb55c04a2b406, which removed legacy DP/exhaustive policy planners and moved active policy roots into SinglePass classes.
- **Resolution:** preserved tested source as archive commit faa39f5f8f on publish/build13-planner-split-20260912. Integrated the required incremental planner implementation, common elimination-order preparation, tests and shared application/timing into a separate worktree based on current main. Kept upstream deletions, current factory enums, SyntheticBoundaryProjection and receipt imports. Applied shared PlacementPlanApplication handoff directly to FederatedPlannerFedAllMaxFedFoutSinglePass and FederatedPlannerFedHeuristicSinglePass. Combined historical session records without deleting either history.
- **Fresh validation:** build-14-main-integration clean Maven package compiled all 1,613 main and 1,664 test sources; selected regression suite 316/316 tests in 42 classes passed, no skips, failures or errors. Source manifests unchanged throughout build. git diff --cached --check and independent read-only integration review passed.
- **Build artifact:** SystemDS.jar SHA256 ba3fe22f2212313b594b3f1a4b4f84432d8c4ffc9dc6f1f6b49161180d8e85f0. Build command and logs: /home/mchoi/so007-incremental-regional-evidence-20260910/validation/build-14-main-integration/.
- **Planning-only integration smoke:** fresh frozen native JVM build14, PCA and GLM, worker5 WAN-mid, X PRIVATE_AGGREGATE / applicable Y PUBLIC, four planners, one repetition each. 8/8 passed. Audit verifies exact phase sum, Statistics agreement, common analysis identity and both Local certificate trajectories containing paired Global optimum with final gap <=5%. All 8 emitted plan hashes and analysis fingerprints match corresponding build13 observations; all 4 DP objective bits and cost fingerprints also match. This is correctness verification, not a new performance comparison or replacement for the prior 24-run study.
- **Evidence:** native/runs/planner-split-build14-main-smoke, analysis/planner-split-build14-main-smoke/audit.json and validation/build-14-main-integration/build13-plan-parity.json under the same evidence root.
- **Remaining validation limits:** targeted tests, not full-suite certification; style/license checks skipped by recorded build command. The prior known failing foreign-action test method remains excluded. Historical unrelated suites were not rerun in this integration; their current failure status is not inferred. No privacy, legality or runtime constraint was weakened to make tests pass.
- **Regression risk / decision:** receipt-owner migration and timing boundaries are integration risks, covered by current four-planner factory/receipt/emission tests and smoke plan parity. Preserve main cleanup; no force push and no resurrection of removed algorithms.
