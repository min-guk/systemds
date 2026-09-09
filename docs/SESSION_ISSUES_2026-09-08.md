# Session issues — 2026-09-08

## Activation robustness and paired ablation audit — complete, limits recorded

### Question, scope and source authority

The user asked whether all tests were run, whether the activation change is robust, and what an ablation actually changes. The earlier successful build ran a selected 15-class, 95-test suite; it was never a full repository test run. The suite also included legacy compiler fixtures that register PUBLIC privacy. The earlier blanket claim that all compiler fixtures were PRIVATE_AGGREGATE was incorrect; the September 7 session document now explicitly corrects it. Follow-up compiler workload comparisons enforce PRIVATE_AGGREGATE.

Baseline source is `8c929e1370fa7934b3daefe45b7a59ae01896945`, represented by remote snapshot `964805c7dad22b47e79e490fb1407a3b28f6abaf`. Feature source is local `72ddfffd92a78413681fa338a643ababd02caa1b`, remote `a64f21748be4065c0dec4d352608dbb320116718`. Comparison worktrees on so009 are `/home/mchoi/so009-activation-ablation-{baseline,current}-20260907`. They have separate build outputs and share the isolated Maven cache `/home/mchoi/so009-activation-classes-m2-20260907`.

No production implementation was changed during this audit. The independent event-oracle test is now a durable source regression, `ExactActivationIndependentOracleTest.java`, with optional diagnostic report output. Candidate domains, privacy policy, lowering authority and runtime execution remain the contracts under test.

The durable activation suite was rerun in the primary isolated feature checkout: **35 tests, 0 failures/errors/skips, package BUILD SUCCESS**, completed `2026-09-08T00:05:49+02:00`. This is six selected test classes, including the new three-test oracle; it overlaps the original 95-test suite and must not be added to that count as 35 distinct new tests. Compiler fixtures in this follow-up suite explicitly use PRIVATE_AGGREGATE; synthetic factor tests have no source privacy input.

```
mvn -B -Dtest-forkCount=1 -Dtest-threadCount=1 \
  -Dmaven.repo.local=/home/mchoi/so009-activation-classes-m2-20260907 \
  -Dtest=ExactActivationIndependentOracleTest,ExactActivationClassFactorDecompositionTest,ExactMaterializationActivationTest,ExactActivationMaterializationCostTest,ExactCompiledMaterializationScopeTest,OccurrenceActivationContextTest \
  package
```

### Independent semantic oracle and encoding ablation

The diagnostic oracle passed three JUnit tests on so009:

- A fixed seed generates 32 structured branch trees. An independent enumerator checks their sixteen concrete execution leaves, all subsets of five demands and both producer states: **2,048** canonical-and-solver comparisons agree with explicit creation events.
- A second seed generates 64 sets of arbitrary concrete events. All subsets of five demands yield **2,048** checks that the unknown-overlap estimate bounds the actual union and lies between the largest marginal and the creation-scope cap.
- Holding the new canonical objective and placement preferences fixed, direct factors and the OR encoding select the same unique assignment and return identical raw objective bits.

| Controlled encoding fixture | Direct width → OR width | Maximum factor cells | Materialized cells |
| --- | --- | --- | --- |
| 16 co-active demands | 16 → 2 | 131,072 → 8 | 262,177 → 257 |
| 16 demands in two exclusive classes | 8 → 2 | 512 → 8 | 2,079 → 255 |

This is an encoding-only ablation of the **same new objective**. The old implementation already decomposed maximum-demand factors, so these numbers are not old-versus-new speedups. The oracle has small finite domains and fixed seeds; it does not establish arbitrary-program correctness or runtime counter accuracy.

### Additional PRIVATE_AGGREGATE regressions — existing failures reproduced

The same explicit test selection was run in both comparison worktrees:

```
mvn -B -Dtest-forkCount=1 -Dtest-threadCount=1 \
  -Dmaven.repo.local=/home/mchoi/so009-activation-classes-m2-20260907 \
  -Dsysds.test.glm.private.aggregate.selector=EXACT \
  '-Dtest=CampaignBG014ExactKMeansWanRepeatedUploadRedTest,CampaignBG014ExactLmWanHeavyBlockingLoutRedTest,FederatedPlanLocalCostPrivacyConstraintTest#localCostDpPreservesPrivacyAcrossBranchLoopAndFunctionBoundaries,GlmPrivateAggregatePlanningContractTest#optInWorkerOneBinomialGlmPlansWithSelectedAdditionalSelector' \
  test
```

Both baseline and feature run **8 tests: 6 pass, 2 fail**, with identical failing assertions:

1. `CampaignBG014ExactKMeansWanRepeatedUploadRedTest.loopAssignmentPayloadUsesExpectedCardinalityAcrossTransientDefinitions`: expected cardinality `800013.0`, actual `0.0`.
2. `CampaignBG014ExactLmWanHeavyBlockingLoutRedTest.exactUsesTheOccurrenceBoundLmLoopFrequencyOnWanHeavy`: expected the cached-X projection to choose `CP`, actual `FED`.

The new feature did not introduce these two failures in this comparison. Their deeper causes are not repaired by this audit: payload/cardinality estimation and the desired LM plan remain unverified contracts. No expected values were weakened and no legal FED alternatives were removed to make the tests pass. The Local branch/loop/function privacy test and the Exact PRIVATE_AGGREGATE GLM planning test pass in both versions; GLM contains 1,971 decisions. Its one-off compile timings are not a performance benchmark.

Logs and XML: `/home/mchoi/so009-activation-classes-validation-20260907/ablation/results/{baseline,current}-pa-regressions.log` and the corresponding `{baseline,current}/pa-surefire-reports/` directories.

### Paired cost semantics and selected plans

A cross-revision harness compiles the same fixtures, registers PRIVATE_AGGREGATE and binds compiler-owned placement authority at the final HOP boundary. An initial detached-analysis harness produced an artificial KMeans feasibility failure; the harness was corrected to use `CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary`. No production feasibility policy was changed. It records every workload before failing JUnit if any workload is unsuccessful.

The concrete invariant/carried transfer fixture has three consumer executions:

| Forced transfer contribution | Old model | Activation model |
| --- | ---: | ---: |
| Outer invariant value, consumed three times | 3.002471923828125 | 1.000823974609375 |
| Loop-carried updated value | 3.002471923828125 | 3.002471923828125 |

These are modeled contribution costs at matched producer/consumer alternatives, not measured time or bytes. The invariant contribution changes from three unit charges to one, while the updated value retains three charges.

Six PRIVATE_AGGREGATE workload fixtures produce finite plans in both versions. Their original decision keys and domain sizes match exactly. KMeans, PCA, LM, LogReg and ALS select identical alternatives. L2SVM changes two alternative selections, of which one changes execution/output state: the `tmp` ternary operation at `scripts/builtin/l2svm.dml:105` changes from `FED/FOUT/ROW` to `CP/LOUT`.

StepLM returns `EXACT_VE_NO_FEASIBLE_ASSIGNMENT` in both versions under this fixture's PRIVATE_AGGREGATE policy. It is recorded as an unsuccessful comparison, not skipped or counted as a pass. A source-registration change from the original PUBLIC certificate fixture does not establish that its old selected plan is legal under PRIVATE_AGGREGATE.

Cross-evaluation reconstructs each other-version assignment by normalized decision key and exact alternative signature, checks domain sizes and hard feasibility, and evaluates that fixed assignment under the current cost surface. All six successful workloads have feasible cross-assignments. The old and new optimizers each prefer their own selected assignment under their respective objective.

For L2SVM, the actual cost matrix is:

| Cost model | Old selected plan | New selected plan |
| --- | ---: | ---: |
| Old max-demand model | 2465.5525743301587 | 2541.8755113418774 |
| Activation model | 2465.5525743301587 | 2383.1848680313306 |

Four changed contributions explain this reversal. Running the ternary operation locally saves 100 modeled compute units. Two movement contributions of 305.291748046875 exchange roles and cancel in the total difference. The additional `Xd` download costs **176.32293701171875** under the old model but **17.632293701171875** under the activation model: `Xd` is produced in the outer loop and reused in the inner loop. Thus the local plan's net penalty of 76.32293701171875 becomes a saving of 82.367706298828125. This is a fixed-assignment cost-model difference, not a measured runtime improvement.

The other five successful workloads have identical selected alternatives and identical canonical objective bits under both models. Solver representation still changes:

| Workload | Auxiliary variables, old → new | Maximum factor cells, old → new |
| --- | --- | --- |
| KMeans | 219 → 77 | 289 → 289 |
| PCA | 102 → 36 | 24 → 16 |
| LM | 75 → 26 | 30 → 30 |
| L2SVM | 246 → 85 | 48 → 28 |
| LogReg | 335 → 116 | 24 → 21 |
| ALS | 86 → 31 | 25 → 25 |

The workload report also records factor sizes. Across these six fixtures, every `GENERIC_DIRECT` contribution has scope at most two and raw domain-product size at most 1,008. This category includes possible untagged ordinary conservative unions as well as other direct costs. No tagged latent `CONSERVATIVE_UNION` contribution is observed; that does **not** prove that all ordinary groups have resolved overlap, because ordinary contribution IDs remain generic. These workloads do not establish scalability for large unresolved unions. GLM passed its separate planning contract, but this report does not instrument its unresolved-factor distribution.

Raw paired reports, including failures, are under `/home/mchoi/so009-activation-classes-validation-20260907/ablation/results/cross-evaluation/{baseline,current}/`. Files include `workloads.tsv`, `selected-states.tsv`, `materialization-scope.tsv`, `cross-evaluation.tsv`, `contribution-deltas.tsv` and `factor-stats.tsv`. The companion `ablation/summarize_ablation.py` verifies key/domain matching, cross-assignment feasibility and objective ordering, then writes `results/paired-summary.json`. It completed successfully. Both reporting Maven runs intentionally remain **BUILD FAILURE** because StepLM is infeasible; the six successful workload rows are separate observations, not a successful seven-workload suite.

To reproduce in either comparison worktree, use its copied `ExactActivationAblationReportTest` with:

```
mvn -B -Dtest-forkCount=1 -Dtest-threadCount=1 \
  -Dmaven.repo.local=/home/mchoi/so009-activation-classes-m2-20260907 \
  -Dactivation.ablation.output=/path/to/new-report-directory \
  -Dactivation.ablation.reference=/path/to/other-revision-report-directory \
  -Dtest=ExactActivationAblationReportTest test
```

The optional reference directory must contain the preserved `selected-states.tsv`; the output directory should be separate. The source harness is retained in the local validation directory's `ablation/` folder. Main source and original repository cleanliness were checked independently; follow-up source hashes, commits and test evidence are recorded in `followup-verification-receipt.json` alongside the original historical receipt.

### Remaining limits and regression risks

- Unknown overlap retains a conservative scope-capped union factor, which may have high arity. A finite-objective exactness claim applies when the solver completes within its resource limits. This audit must not imply that all unresolved unions received bounded OR factorization.
- Ambiguous or cyclic source provenance can overcharge; no new cross-group sharing proof is introduced.
- Static branch and loop frequencies remain estimates. No independence assumption is made, but conservative upper bounds can still alter plan quality.
- There are no measured runtime creation counts, transfer bytes, live-worker checks or Docker workload timings in these results. Any runtime performance comparison must use `run_LAN_docker.sh` under the repository instructions.
- The additional planning suite has two existing failures, and the PRIVATE_AGGREGATE StepLM fixture remains infeasible. Therefore the repository is not reported as fully green or comprehensively robust.

Decision rationale: refine and verify the encoded cost objective while preserving compiler authority, privacy and runtime feasibility rules. Broader pre-existing planner defects are documented rather than concealed by candidate restrictions or runtime fallback.

## Activation + AggLocal worker-1/3/5 experimental integration — in progress

- **Symptom / cause:** the approved d55fc68 experiment JAR includes AggLocal movement-first policy but predates activation-aware materialization. The activation-only rollback tree lacks that newer policy. Neither alone is the user's requested combined experiment source.
- **Fix:** start from clean `ad5b3ba52f` (tracked tree identical to activation-only `04fcd88a3e`); apply only the six `src/` files in `git diff 8c929e1370 be18ce7a41`. Keep Explicit Binding removed. Do not import the uncommitted CertifiedRegional work from the other active session.
- **Changed files:** `FederatedPlannerFedHeuristicSinglePass`, `HeuristicPlacementAdapter`, `PolicyFirstFeasiblePlacementSelector`, and their three existing policy regression files. Activation code is inherited unchanged.
- **Validation:** source diff checks and isolated build/selected tests precede planning-only in Docker. Check PRIVATE_AGGREGATE four-planner domains and activation factors. The historical L2 locality fixture is PUBLIC despite sidecars; do not treat it as protected-data evidence. Fresh actual-stage planning binds PA inputs and exact emitted authority.
- **Experiment contract:** workers 1/3/5, original coordinator so007 and workers so002..so006; so001 proxy only; worker7 deferred. Reclaimed so006 was idle before build. Build finishes before it becomes a measured worker. New immutable stage and receipts, no mutation of historical stages/results.
- **Rerun authorization:** compare complete authenticated physical plans to those underlying actual retained runtime. Only changed plans enter runtime; missing evidence stays unknown. L2 labels-v1 additionally requires its distinct reference gate. P2 historical privacy exclusion is not overridden.
- **Remaining issues:** targeted tests, full planning, baseline recovery, changed-only runtime are not yet complete. Pre-existing KMeans/LM fixture failures and PA StepLM fixture limits above remain recorded. No claim of full-suite success or empirical ordering guarantee.
- **Regression risk / detection:** combining independent policy and cost changes can select different legal layouts; detect with selected-candidate/input/output/registry comparisons, lowering authority validation, runtime semantic checks, and per-cell provenance.
- **Decision rationale:** integrate already reviewed policy and cost changes without changing oracle legality, privacy constraints, runtime kernels, or search algorithms beyond the explicitly requested policy.


## Candidate-space failure transparency and left-index isolation — validated fix, bounded planning follow-up

- **상태**: 원인 수정/회귀 검증 완료; 아래 16-cell Docker planning 재검증 결과는 artifact 보고서 참조.
- **환경/조건**: isolated branch `fix/candidate-failfast-20260908`, base `3033340146`.
  PRIVATE_AGGREGATE StepLM/WAN-Mid/w3, KMeans/LAN/w1, LM/WAN-Heavy/w3,
  P1_FULL/WAN-Mid/w3, all four planners. `run_LAN_docker.sh` saved commands,
  `BENCHMARK_COMPILE_ONLY=1`, `SKIP_WORKER_CONTROL=1`, `--network none`.
  기존 실험의 source/JAR/metadata/worker는 수정하지 않음.
- **증상**: rule/profile 예외가 empty profile, CP fallback, RULE_ERROR 제외로 변환됨.
  fail-fast 후 StepLM/KMeans는 LeftIndexRule NPE, P1은 Spark 설정 초기화 중
  `UnknownHostException: coordinator`로 실패. ML 오류를 출력한 wrapper가 exit=0을
  반환하여 종료 코드만으로는 성공 판정이 불가능했음.
- **원인**: immutable `List.of(ROW).contains(null)`은 NPE를 발생시킴. 또한 FED
  semantic opcode 분류가 Spark broadcast-memory-budget 기반의 물리 전략 예측을
  수행함. 두 opcode alias는 FED rule/runtime에서 같은 구현을 사용함.
- **수정**: RulesCore 및 builder의 unexpected RuntimeException은 문맥/원인을 보존해
  전파; JVM Error도 전파. TransformEncode/Covariance/Spoof 내부 masking 제거.
  LeftIndex null 검사를 null-safe iteration으로 교체. OracleFacade/logger는 모든
  LeftIndexingOp을 LEFT_INDEX로 정규화하고 중복 Spark 예측 helper 삭제.
  정상 unsupported/empty/no-rule과 runtime MAPLEFTINDEX alias는 유지.
- **수정 파일**: `RulesCore.java`, `Rulesets.java`, `OracleFacade.java`,
  `NeutralPlacementGraphBuilder.java`, `FederatedPlannerLogger.java`; 새 regression
  6개 클래스(22 tests), 기존 OracleFacadeTest의 직접 영향받은 canonical opcode
  assertion 1개. 비용/선택 정책/privacy/runtime/DML/Explicit Binding은 수정 안 함.
- **검증**: test-first fail-fast/LeftIndex RED logs와 GREEN을 보존.
  통합 회귀 125 tests: 124 pass, 1 failure. 유일한 failure
  `OracleFacadeTest.binaryFullMatrixWithLocalMatrixDoesNotRequireEncodedWidth`는
  원래 staged JAR에서도 동일하게 재현(원본 24 tests 중 동일 1 failure).
  assertion을 약화하거나 ignore하지 않음. 별도 diagnostic validator 5 tests pass;
  로그 오류, compilation 완료, execution=0, complete physical authority, audit를 확인.
  `git diff --check` clean. 전체 suite 성공으로 보고하지 않음.
- **재현/근거 경로**: `/home/mchoi/g014-candidate-space-audit-20260908`.
  `replay_planning.py`, `validate_replay.py`, `compare_replays.py`,
  `logs/regression-v2.log`, `logs/baseline-oracle-regression.log`,
  `REPORT_CANDIDATE_SPACE_20260908.md` 참조.
- **잔여 이슈**: unknown-shape materialization의 positive-shape 증명 및 complete
  runtime-vs-domain audit는 별도 범위. StepLM hop1400은 m_lm occurrence이며 현재
  abstract rows/cols/orientation UNKNOWN이라 기존 fact만으로 positive shape를
  증명할 수 없음. known-positive gate를 무조건 제거하지 않음.
- **잠재 회귀 위험/감지**: 이전에 감춰졌던 다른 예외가 compile failure로 드러날 수 있음.
  원인을 고쳐야 하며 candidate 제외/CP fallback으로 복구하지 않음. Full trace와
  raw domain을 비교하고, authority-record 복원과 실제 instruction 변화도 구분.
- **의사결정 근거/원칙**: oracle/compiler 경계의 결함만 수정; privacy/legality
  완화와 runtime fallback 금지. 플래너 간 성능 차이를 인위적으로 확대하지 않음.


### 최종 16-cell 재검증 결과

- 수정본 `bd3bb4c2db01f3e880091df90c18f2f71b9b2cbd7b3ac7cf894fa0b4e161d566`:
  StepLM/KMeans/LM/P1 × 4 planners 전부 application-level planning 성공.
- 16개 pre-/post-privacy output domain과 exact runtime program 텍스트 동일.
- comparator는 P1 4개를 candidate-authority changed로 표시: 각 셀에서 CP/LOUT
  local indexing 후보 기록 5개만 복원. selection/registry/instruction은 동일.
  이 차이를 숨기지 않고 `repair-v2/comparisons/REPAIR_CLASSIFICATION.json`에 분류.
- 최초 fixed 시도의 exit=0 ML 12개를 성공으로 해석하면 안 됨: 실제로 LM 4개만
  성공, StepLM/KMeans 8개 application error. P1 4개도 실패. 원본 receipt 보존 후
  별도 validated status 작성. 수정본의 최종 16/16은 로그/trace/audit까지 검증한 수치.
- 이번 결함 수정에 따른 runtime improvement는 관측/주장하지 않음. active campaign
  source/staged JAR는 원본 그대로이며, 동일 instruction의 runtime 재실행은 안 함.
