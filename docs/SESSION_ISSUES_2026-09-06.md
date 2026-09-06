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

## GLM Docker promotion — resolved for the failed planning cell

- Committed backend: `6c659929b0f2cf5a04b4cce68900a0ef0d0f14ab`; pinned JAR SHA-256 `cc1224e0dcbd87d419e85dae455f3dc3eff35af9b08ec477c3e93506cbf51128`. Build approval and complete so002–so009 deployment receipts are under CONTROL. so001 is not an execution host.
- The previously failed `GLM/LAN/w3/FedAll` authenticated Docker planning canary passed at 2026-09-06T02:19:23Z: FedPlanner1.623969s, compilation11.337206s, execution0s, FED instructions/UDFs0, output files0. These trace-enabled planning timers are not overhead-free runtime-campaign measurements.
- Evidence: `/home/mchoi/g014-extra-ml-glm-w3-canary-20260906-6c65992/results.jsonl` and archived receipts; subsequent full54 planning root `/home/mchoi/g014-extra-ml-planning-20260906-6c65992-seeded`.
- Remaining: full54 planning and actual runtime/numeric parity. The conditional runtime continuation authenticates all54 planning archives before launching, uses the same immutable stage and shared lane, and preserves failed-run evidence. Root28/28 Python tests and independent4/4 focused tests pass. Runtime has not started at this update.
- Regression risk: successful planning is not proof of runtime feasibility or numerical equality; both are separate gates. Future source changes must not alter the running immutable stage.

## ALS DP selected transient carrier identity — in progress

- Symptom: current DP fails `No valid federated plan ... TRead W` for real ALS maxinneriter10, worker4/5. Exact and Heuristic pass. This is distinct from historical small single-run performance reversals.
- Cause isolated by debugger: exact transient reuse resolves a cloned TWrite to its original for validation, but then passes the original's TRead to a selected-plan identity check that correctly expects the clone's direct input. Clone and original identities are not interchangeable at this boundary.
- Repair plan: retain canonical metadata checks while using the selected carrier's own direct input for exact binding checks. Do not weaken function/copy/privacy guards or select an arbitrary alternate candidate. Regression RED and narrow implementation are in progress in the dedicated agent lane.
- Evidence: CONTROL/ALS_DP_TRANSIENT_CARRIER_REPAIR_20260906; initial inspector `/tmp/als-dp-failure-w5-predicates.log`.
- Remaining: root integration, affected-surface tests, actual planning delta and changed-cell runtime validation.
- Risk: carrier/canonical mixing can also affect function-copy bindings; require exact sibling/occurrence negatives, not merely a positive ALS canary.

## Native result W2C configuration precedence — in progress

- Symptom: generic codec210 and explicit W2C14.7 yield native FED/LOUT in-band210. A fresh-JVM property probe is RED; a dedicated in-band333 override is already honored.
- Cause: the native-result fallback explicitly prefers generic before directional W2C. Generic210 has C2W upload-hotspot provenance, not a measured native W2C codec benchmark. Native and explicit GET responses use the same Netty Java ObjectEncoder/ObjectDecoder plus MatrixBlock Externalizable serialization; their batching/materialization critical paths can still differ.
- Repair plan: dedicated in-band override > directional W2C > generic fallback, using the existing configuration capture rather than a duplicate helper. Preserve explicit zero/disabled codec semantics and fail-closed privacy/physical candidate domains. Test fresh JVMs to avoid captured-static property contamination.
- Evidence: CONTROL/KMEANS_INBAND_DIRECTIONAL_PRECEDENCE_20260906, isolated probe `/tmp/g014-inband-precedence.wVWmr4/receipt.log`.
- Remaining: integrate tests, compare DP/Exact selected plans, measure authenticated runtime; the absolute correctness of14.7 remains a calibration question, and the fallback change alone is not evidence that KMeans ordering is repaired.
- Risk: changing a shared estimated transfer rate can affect many cost-selected plans; immutable-stage versioning and changed-plan-only replay are required. Do not retune the constant solely to enforce an observed ranking.

## ALS carrier and native W2C repair integration — source verified; runtime pending

- Changes: `FederatedPlannerDpCostEnumerator.java` now supplies the selected TWrite carrier's direct input to the two exact binding predicates; canonical inputs remain in the logical-source and cycle guards. `FederatedCostModel.java` now obtains its in-band default from the existing directional W2C setting, which already inherits the generic default. No new helper, candidate exclusion, privacy relaxation, or runtime fallback was added.
- Regression evidence: the real worker-5 ALS test is RED before the carrier fix and GREEN afterward; worker-4/5 metadata-only planning also succeeds. Fresh Maven runs the new ALS test **1/1 PASS** and the complete cost fallback suite **47/47 PASS**, including four fresh-JVM configuration-precedence cases (generic, directional, dedicated, and explicit zero).
- Wider validation: `CONTROL/DP_CARRIER_W2C_INTEGRATED_20260906T023214Z.json` records **380 tests: 366 passed, 9 failures, 2 errors, 3 skips**. Every one of the eleven failing/error methods reproduces with the same semantic cause against the independently pinned pre-fix 6c JAR. This establishes no introduced failure in the affected-surface run; it does **not** make the wider suite green. Source hashes did not change during validation; independent bounded code review and `git diff --check` pass.
- Baseline attribution: `CONTROL/ALS_PINNED_6C_SIX_FAILURE_CLASSES_20260906.log` (SHA-256 `59301ac559d0f627ce362bf44a6b3430b55f4a856e3a3454fedde856326650cf`) and `CONTROL/ALS_DP_TRANSIENT_CARRIER_REPAIR_20260906.md` (SHA-256 `23e96eab029860257d3ae1582884bf11262fd60e00aaf61fe692cd6b40930c67`). Existing unsupported receipt assertions are separate from the real StepLM local-conflict and LogReg REXPAND failures. The three pre-existing public-fixture skips are retained, not expanded.
- Remaining: isolate the pre-existing StepLM failure, authenticate a new immutable backend, compare physical plans against the existing runtime evidence, and rerun changed cells only. Running GLM/GNMF/GMM-VVI stages remain on their independently pinned 6c backend; this working-tree repair must not replace their JAR mid-campaign.
- Calibration caveat: an isolated pinned-backend Docker codec diagnostic on so009 measured about 404–493 MiB/s median encode+decode throughput for the 10000 x 50 result block. Therefore the configured 14.7 MB/s must not be described as a measured pure native codec rate. The precedence repair fixes the configuration contract; actual transport/binding and matched workload runtime still require separate measurement. Evidence: `CONTROL/KMEANS_FEDERATED_RESPONSE_CODEC_BENCHMARK_20260906`.
- Regression risks: carrier-vs-canonical identity mistakes remain guarded by exact edges, occurrence identity, state/layout agreement, and grounded logical sources. Absolute transfer-rate accuracy remains open; runtime ordering is not an acceptance shortcut for tuning constants.

## Single-worker private LogReg REXPAND candidate omission — source verified, runtime replay pending

- **Symptom**: the actual one-worker private-aggregate LogReg program fails shared planning at its one-hot `REXPAND` even though the FED instruction supports the input. This is a deterministic candidate-space omission, separate from runtime ordering and stale legacy DP receipt assertions.
- **Cause**: `Rulesets.RexpandRule.caps` accepted only exact ROW. Runtime `ParameterizedBuiltinFEDInstruction` uses `isFederated(ROW)`, whose runtime type predicate includes FULL, and preserves FULL when copying/transposing the single-entry FederationMap. `SinglePartitionFacts` did not propagate that conditional single-endpoint fact through REXPAND.
- **Repair**: add native FED/FOUT/FULL only with an explicit single-partition proof; retain the representation guard and BROADCAST/unknown-or-multi-partition rejection. Propagate endpoint cardinality, not exact output dimensions or stale seed ranges. No privacy weakening, transient CP/FOUT, candidate pruning workaround, or runtime fallback.
- **Production files**: `rules/Rulesets.java`; `placement/SinglePartitionFacts.java`.
- **Regression files**: `RexpandRuleTest`, `SinglePartitionFactsTest`, `PrivateAggregateFourPlannerContractTest`, `RexpandFEDInstructionFullNativeTest`.
- **RED/GREEN**: two semantic failures before repair (FULL native emission; single-endpoint propagation), followed by 28/28 focused tests. Runtime tests already pass against the unchanged kernel: both directions use the real in-process worker/CP kernel, preserve correct numeric values/FULL ranges, and issue one EXEC_INST without GET/PUT during expansion. Test-only output inspection occurs after that assertion and is not a privacy-release claim.
- **Shared-domain validation**: fresh seven-suite run **81/81 PASS**, zero failures/errors/skips; source hashes unchanged during the run. Four production selectors consume the same privacy-filtered analysis fingerprint; one-hot protected states are FED/FOUT/FULL, with no local materialization or relocation. Fixture uses the production static `outer`-to-REXPAND rewrite; bare `sum(table(...))` was optimized away, and table-to-REXPAND depends on shape facts unavailable before metadata acquisition. Each rewritten fixture resets the previous planner run state and cleanup restores it.
- **Actual workload validation**: four fresh-JVM production CLI runs for real `multiLogReg` (50000x2100, one worker, PRIVATE_AGGREGATE X/Y, maxi30/maxii5) all reach HOP/LOP/instruction construction and commit 486 legal decisions, including one protected native FULL expansion. Production DP is `DP-LocalConflict`, not the legacy memo-DP receipt. All four report execution0, no model output, and zero fallback/repair. These local compile-only checks are **not Docker performance results**.
- **Evidence**: `CONTROL/LOGREG_FULL_REXPAND_SHARED_DOMAIN_WIDE_20260906.json`, its copied XML directory, `CONTROL/LOGREG_FULL_REXPAND_PRODUCTION_CLI_20260906/receipt.json`, and RED logs.
- **Remaining**: independent source review/commit, versioned immutable deployment, old/new physical-plan comparison, changed-cell-only Docker runtime. The older `CampaignBG014DpLogRegTransientForwardRedTest` now reaches its outdated `DpInvocationReceipt` assertion; its failure is preserved, not masked to claim a green wider suite.
- **Regression risks**: an unproved FULL or multi-endpoint map must not be admitted; negative caps/cardinality tests cover both. Cardinality propagation must not become exact dimension/range authority; tests explicitly reject fabricated geometry.

## Forwarded SSH agent stall — operationally recovered

- **Symptom**: normal SSH to so002/007/008/009 stalled before offering a public key; `ssh-add -l` itself exceeded five seconds. Remote banner/key exchange worked. No failed planner result can be inferred from this authentication stall.
- **Cause**: inherited `SSH_AUTH_SOCK` references a nonresponsive VSCode-forwarded agent. Bypassing only this lookup authenticates with the existing on-host key; there is no new credential or authentication bypass.
- **Repair**: run detached experiment supervisors with `env -u SSH_AUTH_SOCK`, preserving normal host-key verification/BatchMode. Verified so003/006/007/008/009; no global SSH configuration, server restart, host qdisc modification, or user-agent termination.
- **Evidence**: `CONTROL/SSH_AGENT_STALL_RECOVERY_20260906/receipt.json` and successful no-agent host probes. The earlier GNMF coordinator SSH255 is preserved separately; the same root cause for that earlier disconnect is plausible but not proven.
- **Remaining/risk**: fresh recovery root imports only the23 authenticated successes and retries failed+remaining31. Original failed artifacts must never be overwritten. The resumed process must not inherit a renewed VSCode agent socket from tmux.
