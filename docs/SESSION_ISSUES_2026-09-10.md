# Session issues — 2026-09-10

## KMeans multi-worker BROADCAST matrix-multiply capability gap (confirmed; not repaired)
- **Symptom:** w1 FULL permits FED initializer continuation; w3/w5 BROADCAST output at kmeans.dml:70 is followed by CP initialization sites100/105/109.
- **Cause:** BinaryMMRule excludes local×BROADCAST even though AggregateBinaryFEDInstruction implements native FOUT/LOUT for that input combination. The deployed-JAR standalone oracle probe returns CP/LOUT NOT_FEDERATED_INPUTS for BROADCAST, FED/FOUT for FULL, FED/LOUT for ROW.
- **Decision:** runtime-grounded candidate repair is needed; do not conflate replicated BROADCAST with ROW partial aggregation, and do not weaken privacy.
- **Changes:** no backend code changed in this audit. Diagnostic Java/Python/scripts/report are outside the repo at /home/mchoi/g014-workload-layout-audit-20260910/.
- **Verification:** 24 archived KMeans/ALS planning cells SHA-verified; standalone oracle probe executed; 19 existing FULL/ROW/function-return shape contract tests passed against frozen JAR20c6a2e116c7f4cf77080b82319dcd35c47a9156b9c8256aba3fc513a5f69755.
- **Remaining:** add failing oracle+candidate reachability+native local×BROADCAST runtime regressions, repair exact supported capability, compare affected plans before runtime reruns. No new runtime superiority claim.
- **Regression risk:** treating replicas as disjoint partitions duplicates sums; enabling unsupported combinations violates physical feasibility. Test exact layout/output/worker-pool contracts.

## StepLM static versus dynamic counts (explained; no demonstrated plan-loss bug)
- **Symptom:** selectedFED96/78/91/91 but similar dynamic operation counts.
- **Cause:** 25 of28 differing static selections are in inactive lmCG branch. At <=128 cols runtime uses lmDS. The actual lmDS:94 FOUT-vs-LOUT distinction survives in8,257 runtime instructions; equal opcode totals hide output residency.
- **Verification:** archived emitted selections and instruction-statistics CSV inspected. Residual stays FOUT through subtraction/squaring, only sum releases LOUT.
- **Remaining:** detailed state-restoration route not logged; no full internal authority-path proof. Do not infer that cost frequency for inactive branches is correct from runtime absence alone.

## LM-CG isolated workload exploration (planning verified; runtime pending)
- **Change:** standalone explicit lmCG script, same50Kx128/X PRIVATE_AGGREGATE/Y PUBLIC, icpt0/reg1e-7/tol1e-9/maxi20. Existing LM-DS and active campaign unchanged.
- **Verification:** 16/16 planning-only cells (LM-DS/LM-CG ×w1/w3×fourplanners) succeeded; all runtime0.000s, no model files. Real staged local privacy metadata avoided worker RPCs. No workers/containers launched by this probe.
- **Remaining/risk:** maxi20 does not guarantee convergence; check numerical objective/residual and actual iteration count before publishing runtime comparisons. Use separate LM-CG label, never relabel previous LM-DS results.
- **Full report:** /home/mchoi/g014-workload-layout-audit-20260910/REPORT.md

## BROADCAST gap repair follow-up (source verified; not deployed)
- The earlier “not repaired” entry records the pre-fix audit. BinaryMMRule local×BROADCAST profile/caps and TWrite/TRead BROADCAST identity preservation are now fixed.
- MM-only change was insufficient: full candidate audit identified X_samples TWrite as an additional layout loss gate. Branch/transient and privacy candidate regressions now cover this path.
- Verification: 37 targeted tests PASS; 24/24 paired KMeans planning-only runs PASS, workers1/3/5 and four selectors. FedFirst/AggLocal choices change; Global/Regional placements unchanged with two TRead input-descriptor changes. No runtime speedup claim.
- Existing OracleFacadeTest binaryFullMatrixWithLocalMatrixDoesNotRequireEncodedWidth failure reproduces on unchanged frozen JAR; remains outside this patch.
- Existing dirty source preserved; active campaign and frozen JAR not replaced. Targeted javac overlay used because disk is nearly full. No full Maven/package or benchmark runtime validation.
- Report: /home/mchoi/g014-workload-layout-audit-20260910/fix/REPORT.md

- Review follow-up: exact [local,BROADCAST] AggBinaryOp candidate profile incorrectly became ROW because local was widened to all matrix FTypes. Narrowly preserve the actual local input in NeutralPlacementGraphBuilder; exact BROADCAST profile assertion reproduces failure before the fix and passes after. Added non-TSMM MM arity regression.

## Publication verification follow-up
- Committed all local source/test changes for publication without overwriting the separate newer GitHub main history. Active experiment stage remains unchanged.
- BROADCAST targeted regressions: 37/37 PASS; paired compile-only probes: 24/24 PASS (see fix report above).
- Additional existing compiled tests (ExactSparseFunctionBoundaryCostTest, PlacementCostSemanticsAnalysisMemoryCostTest, FederatedRefedPolicyTest): 84 run / 16 failures. All 16 are in FederatedRefedPolicyTest; the identical 16 test methods also fail against the unchanged frozen JAR without the new overlay. This is not a full clean-source Maven verification and these failures remain unresolved; do not advertise an all-green repository suite.
- Logs: /tmp/cofee-systemds-publish-tests.log and /tmp/cofee-systemds-publish-baseline-tests.log. The publication task does not change runtime behavior to work around these pre-existing test failures.
