# Session issues — 2026-09-06

## GLM partitioned native candidate proof — in progress

- Symptom: backend73533ea authenticated GLM/LAN/w1 three selectors passed; w3/FedAll fails `No privacy-safe physical placement` at `glm.dml:869` PRIVATE_AGGREGATE Binary multiply. Campaign status3/54 complete, fourth cell failed; no new runtime result.
- Evidence: `/home/mchoi/g014-runtime-4net-w1357-20260901-control/GLM_W3_CANARY_FAILED_73533ea_COORDINATOR.log`; its archived coordinator SHA-256 is `08fe8a298284e50d732b1280da922b7e11087b2b587ef5f0854e9db59f8b31f9` (compressed archive, not extracted log hash).
- Root cause verified: `BinaryElemwiseRule.caps` consulted unknown concrete output/input dimensions while exploring other paths before reaching the exact ROW/ROW case. `ShapeHint` records consultations monotonically, so `OracleFacade` exported irrelevant missing shape facts and `NeutralPlacementGraphBuilder` excluded the valid native emission as `UNKNOWN_METADATA`. The common abstract shape at the failed multiply is actually exact 50000 x 2. Privacy correctly rejects the remaining rows because they require one protected local input.
- Repair: recognize exact ROW/ROW and COL/COL native candidates before speculative shape probes; retain the representation guard and downstream exact candidate/worker-range certification. Delete the now-unreachable old soft-axis block. Do not clear ShapeProof globally or alter the privacy filter.
- Modified production file: `src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java`. Regression files: `OracleFacadeTest.java` and `GlmPrivateAggregatePlanningContractTest.java`.
- Repair plan: `CONTROL/GLM_PARTITIONED_NATIVE_CONTINUITY_REPAIR_20260906.md`.
- Root test change: add 3/5-worker balanced PRIVATE_AGGREGATE fixtures to `GlmPrivateAggregatePlanningContractTest` and exercise the same three additional-ML selectors. Existing w1 method remains available. Test fixtures compile local metadata only, not authenticated runtime.
- Validation: actual GLM w3/w5 is RED against73533ea. Isolated Rulesets-only patch passes all three additional-ML selectors for w1/w3/w5; each selector consumes its own fresh equivalent shared analysis and legal domain. Oracle unit coverage includes unknown-dimension ROW/ROW and COL/COL positive cases and mixed-axis negative shape requirements. Root integration/regression and authenticated Docker recheck remain pending.
- Deferred unrelated change: the earlier NativePlacementContinuity ROW/COL extension passed its micro tests but did not fix the failed pre-domain exclusion. Its diff is preserved at `CONTROL/DEFERRED_NATIVE_ROW_COL_CONTINUITY_20260906.diff`; it was removed from the integration tree. The GLM Rulesets-only pass uses the unchanged baseline continuity implementation.
- Remaining issue: verify exact physical-map compatibility independently, integrate/build, then rerun the failed authenticated cell before resuming the campaign.
- Regression risk: overpermissive range alignment; detect with mismatched shape/range/anchor negative tests. No protected collection/CP reupload/runtime fallback permitted.

## Detached campaign process lifetime — resolved operationally

- Symptom: shell nohup launcher disappeared before writing any campaign event when its tool process tree ended.
- Resolution: preserved prestart log/pid, relaunched in detached tmux `cofee-extra-ml-planning-73533ea`. Verified stable PID and first cell_started event23:38:59UTC, followed by three successful cells.
- Remaining issue: campaign subsequently stopped on the real w3 planner failure above, not on process-lifetime loss.
- Risk: never infer running state from PID file alone; require live process/session and campaign event/receipt evidence.

## Runtime reversal classification — evidence complete, repairs open

- Required order: Exact <= DP <= {FedAll, Heuristic}; no ordering between the two baselines.
- Authenticated archived instruction audit: 74 in-scope pairs = 34 identical full streams + 9 identical after scratch-process normalization + 31 changed streams. The 54 baseline-to-baseline comparisons are excluded. No evidence is missing or hash-invalid.
- Evidence: `CONTROL/PLANNER_REVERSAL_PHYSICAL_PLAN_CLASSIFICATION_20260906.csv` and `.md`. Same-plan does not prove absence of runtime variability, and changed-plan does not by itself prove a cost defect. No pair is marked repaired from this classification.
- Next: concentrate candidate/cost diagnosis on the 31 changed-plan pairs and use repeated matched-plan measurements for the other 43. Exact worker-scaling cases remain a separate 30-case investigation.

## StepLM output-byte estimate collapse — in progress

- Symptom: current Exact assigns equal result-download estimates to dynamic A=t(X)%*%X and b=t(X)%*%y, although b has a proven one-column abstract fact.
- Corrected diagnosis: an occurrence-aware probe shows both generic effective outputs equal 268435456 bytes (256 MiB), raw HOP estimates 32178700288 bytes, and no direct node anchors. The weighted 80747916.68-ms trace term is compatible with that generic unknown-dimension envelope. It is NOT evidence that these particular A/b values were sized from a 50000 x 2100 durable anchor.
- Evidence: `/tmp/steplm-output-probe.log`; historical/current causal artifacts under CONTROL. A helper that treats anchor geometry as exact derived geometry is separately suspect, but changing it alone does not address this reproduced symptom.
- Decision: investigate a shared occurrence-aware partial/exact shape estimate without inventing a 2100 feature bound from a worker-pool anchor. No production cost patch applied yet. DP/Exact objective parity must be tested together.
- Remaining risk: a conservative estimate is not a physical upper bound; report the distinction and validate selected-plan changes before runtime claims.

## GLM integration verification update

- Fresh Maven affected-surface suite: **298/298 PASS**, zero failures/errors/skips, 49 suites; receipt `CONTROL/GLM_SAME_AXIS_WIDE_VERIFIED_20260906T015700Z.json`.
- Fresh actual GLM PRIVATE_AGGREGATE same-analysis selector canaries: worker1/3/5 x FedAll/Heuristic/Exact = **9/9 PASS**; receipt `CONTROL/GLM_SAME_AXIS_SELECTOR_CANARY_20260906T015857Z.json`.
- Added two-worker shifted ROW/COL partition regression to `RelocationSelectionsPhysicalAnchorTest.java`; **6/6 PASS** (four existing plus two new), `CONTROL/GLM_SHIFTED_PARTITION_SAFETY_20260906.log`. Matching endpoints are not sufficient to align partition boundaries.
- Composite receipt: `CONTROL/GLM_SAME_AXIS_INTEGRATED_REGRESSION_20260906.json`. `git diff --check` is clean. Docker canary remains the next gate; these are not runtime performance results.
- Read-only review correction: an empty inferred profile is not `PROFILE_ERROR`; `CandidateProfileFact.available()` checks the evaluation failure string. The exact GLM ROW/ROW fact is AVAILABLE with no profile failure and retains its native emission. No unrelated profile expansion is included.
