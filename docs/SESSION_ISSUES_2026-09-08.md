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

## Certified / Anytime Regional implementation on so007 — initial verification

- **Problem / symptoms**: Regional solves local neighborhoods exactly but exposes no
  global modeled suboptimality bound and does not revisit earlier overlapping regions.
- **Baseline / environment**: isolated source `/home/mchoi/so007-certified-regional-20260908`,
  based on `ad5b3ba52fa6a59154e99a34d0a76641a01c1500`. Source is mirrored to so007;
  historical stages and the original integration checkout are preserved. Java 17,
  Maven 3.9.7. The source provenance of the new build is recorded separately.
- **Cause**: a conditional regional optimum is an upper bound on the global optimum,
  while its local objective is not a global lower bound. Increasing relaxation width
  without preserving partitions also does not imply monotone raw bounds.
- **Solution**: global bounded-width min-sum relaxation with downward arithmetic;
  a max-L/min-feasible-U envelope; cumulative exact regions selected by relaxed
  argmin disagreement; typed original/auxiliary model boundary; full canonical/hard
  reevaluation; explicit resource/time/gap/Global stop reasons and trace metrics.
  The existing `COMPILE_COST_BASED` route enables it with
  `sysds.fedplanner.regional.mode=certify|anytime`, default `off` for controlled ablations.
- **Changed files**: `MiniBucketLowerBound.java`, `CertifiedRegionalOptimizer.java`,
  `LocalPhysicalOptimizer.java`, `FederatedPlanLocalCost.java`, corresponding new
  tests, and `docs/CERTIFIED_REGIONAL_ABLATION_PLAN.md`.
- **Decision rationale / principles**: change search and certificate computation
  only. Preserve the same cost surface, privacy/candidate/placement authority,
  original hard constraints, and executable emission path; no runtime fallback.
- **Fresh validation so far**: baseline selected tests 34/34 passed on so007.
  New core plus baseline selected tests 54/54 passed with fresh main/test compilation.
  Forty independently enumerated five-variable cost networks under three policies
  verify all checkpoint bounds and monotonicity. Shared-producer barrier, initial
  infeasibility, zero budget, exact endpoint, resource limits and seeded determinism
  are explicit regressions. Detailed logs are
  `/home/mchoi/so007-certified-regional-{baseline,core}-tests.log` on so007.
- **Remaining work**: complete PRIVATE_AGGREGATE compiler/emission regression,
  build the artifact, and run an isolated Docker planning ablation pilot through
  `run_LAN_docker.sh`. Final counts/results will be appended below.
- **Potential regressions / detection**: invalid encoded/canonical pairing, stale
  raw bound after cancellation, swallowed malformed factors and underreported gap.
  Detect with typed owner checks, original-model reevaluation, independent optimum
  oracles, explicit aborted-bound markers and conservative arithmetic tests.
- **Limits**: scheduling time begins after the feasible Regional seed. Existing
  exact solves check time only at phase boundaries, so this is not hard preemption.
  Native high-arity factors must fit existing ceilings. Disagreement is a heuristic,
  with possible tie ambiguity. No model/runtime approximation ratio or speedup is
  inferred from unit tests or a single pilot repetition.

### Final implementation build and compiler ablation evidence

- **Validated on so007**: the final targeted `mvn package` completed successfully
  with **97 tests, zero failures/errors/skips**, including the PRIVATE_AGGREGATE
  compiler/emission tests and existing activation, privacy and exact/local solver
  regressions. The optional eight-variant physical ablation report was enabled.
- **Artifact**: `target/SystemDS.jar`, SHA-256
  `3ce3aa9ec8a95b6407afdc0bdf1f60b397ba414981e51bcd65fd5ed079f4d9b0`.
  The 7,488 source/resource/POM files in the provenance manifest match local and
  so007. `git diff --check` passes. No dependency was added or cost-model behavior
  deliberately changed; baseline mode remains off for controlled comparisons.
- **Observed compiler pilot**: all eight variants share the same Global optimum
  `2.5008440979570152`. Certify/Bound-only preserve the initial assignment; increasing
  widths tighten L and all full-region variants reach L=U. The seed is already
  optimal, so this fixture does not show a policy advantage. The independent
  shared-producer barrier demonstrates U=8→2 only after all three coupled decisions
  are included. This is correctness evidence, not a workload speedup result.
- **Durable evidence**: `/home/mchoi/so007-certified-regional-evidence-20260908/validation/`
  contains the build log, JUnit XML, source/JAR provenance, raw compiler ablation TSV
  and summary. `docs/CERTIFIED_REGIONAL_RESULTS.md` records the exact reproduction
  command, results and Docker pilot disposition. The ablation plan specifies eight
  controlled rows, paired operation budgets and the later workload campaign.
- **Regression boundary**: no full-suite green claim is made. The pre-existing
  workload failures documented above remain outside this implementation. Scheduling
  deadlines, native factor ceilings and disagreement tie ambiguity remain explicit.

### Final Docker planning-only ablation evidence

- **Scope**: KMeans/P2P2D, PRIVATE_AGGREGATE, 50,000 × 2,100 features, one worker
  placement, LAN, one pilot replication across eight planned ablation variants.
  Only `run_LAN_docker.sh --planning-only` was used. All successful receipts prove
  zero execution time and no workload output; worker JVMs and CP reference
  generation were excluded.
- **Staging issue and repair**: the preserved archive has exact four-worker data
  bytes but lacks its original publisher filesystem lifecycle proof. A separate
  `g007-planning-stage-descriptor-v1` binds the archive to one-worker compile-only
  execution and records `publisher_lifecycle_verified=false`. Normal runtime,
  publisher and five-worker gates were not changed. The planning stage validates
  complete data hashes, privacy, seed streams, reference payload, source/JAR and
  the invoked experiment paths; runtime/lifecycle use of it is rejected.
- **Harness failures and detection**: initial smoke attempts failed before a
  valid receipt because Compose was absent, then because the receipt CLI could
  not import its stage validator and its failure branch read an unset runtime
  bundle variable. An existing Compose binary was selected in an experiment-local
  configuration. CLI path handling and the planning failure branch were repaired;
  an unrelated-working-directory subprocess regression was added. Those failed
  attempts remain failures. No ordinary reference or model validation was relaxed.
- **Validation**: 42 harness tests and 9 collector tests pass on so007, plus shell
  syntax and Python compilation checks. Stage identities, receipt self-hashes,
  log/config hashes, full traced budget/seed settings, canonical objective bits,
  gap arithmetic and monotone checkpoints are checked before a row can pass.
- **Observed result**: all eight rows pass, and all ten paired comparison checks
  pass. Global is `29057.63845842688` modeled ms; Regional and all six feature rows
  return `29701.732755786634`, an actual modeled gap of **2.216609%**. The valid
  certificate is approximately `[28020.9543606126, 29701.7327557866]`, or at most
  **5.998291%** relative gap. Every checkpoint encloses Global. Certify and BoundOnly
  preserve the Regional emission plan exactly.
- **Negative result and limits**: width 2→4 provides no meaningful L improvement,
  and Structural/Random/GuidedFixed/Anytime complete three region passes (24 original
  decisions) without U improvement. No 1% certificate or policy advantage is shown.
  Planner times range from 0.676193 to 1.049665 seconds in this single pilot;
  neither speedup nor uncertainty claims are supported. The larger paired
  multi-seed/multi-workload planning campaign remains a documented future study.
- **Artifacts**: the final stage is `ce823de74bc0387ff2f493ada32f8d4465f3b36fcdb42156e5efa0b6b100d07a`,
  harness commit `0d2ebd0`. Evidence is under
  `/home/mchoi/so007-certified-regional-evidence-20260908/docker/`, with comparison
  in `runs/20260908t0351ablation-kmeans/comparison.json`, exact commands, frozen
  descriptors, raw logs/receipts, harness source/patch, independent review and
  byte-checked portable copies. The SystemDS implementation/JAR is unchanged from
  the 97-test package above.

### Detailed Korean implementation report — resolved

- **Request / symptom**: the implementation and pilot evidence were distributed
  across source, a short results document and raw artifacts; a detailed report
  file was requested for review and subsequent research.
- **Resolution**: added `docs/CERTIFIED_REGIONAL_IMPLEMENTATION_REPORT_KO.md`,
  covering the implemented algorithm, model/canonical-cost contract, proof outline,
  configuration, tests, all eight planning ablations, phase work, provenance,
  limitations and clearly unexecuted follow-up studies.
- **Decision rationale / scope**: documentation only. Preserve the user's
  planning-only experimental boundary and the existing source, JAR, frozen stage,
  raw pilot results and failed-attempt history. No new build or experiment ran.
- **Validation**: checked the report against source; regenerated expected pilot,
  phase and bound table rows from the stored JSON and the Java test table from
  all 13 JUnit XML reports. All 17 file links resolve; UTF-8, code fences, formula
  delimiters and missing-placeholder checks pass. The validation record is
  `/home/mchoi/so007-certified-regional-evidence-20260908/reports/implementation-report-validation.json`.
  Independent report review passed; the Java/Maven label was narrowed to the
  directly evidenced so007 build environment to avoid implying container versions.
  A so007 link audit found two missing derived JSON summaries (`provenance.json`
  and `physical-ablation-summary.json`); their existing local copies were saved
  in the corresponding evidence directory without rerunning the experiments.
- **Remaining issues**: implementation limitations remain as documented above.
  In particular, non-nested MBE partitions, soft deadlines and the pilot's lack
  of incumbent improvement are explicit; no runtime or policy-advantage claim
  was added.
- **Potential regression / detection**: future source or evidence changes can
  make the report stale. Preserve commit/artifact identities, check the recorded
  evidence hashes and regenerate numerical comparisons before revising claims.

### Threshold Regional proposal report — resolved

- **Request / symptom**: the proposed threshold-driven algorithm needed a durable
  report that distinguishes its design from the already implemented optimizer.
- **Resolution**: added `docs/THRESHOLD_REGIONAL_ALGORITHM_PROPOSAL_KO.md` with
  cost-bound/error definitions, threshold residual scheduling, explicit nested
  relaxation requirements, mandatory coverage, proof conditions, pseudocode,
  KMeans necessity calculations and future planning-only ablations.
- **Decision rationale / scope**: document the requested proposal without changing
  planner/runtime behavior or experimental conditions. The report marks all new
  scheduler/relaxation features and follow-up tests as unimplemented/unexecuted.
- **Validation**: recomputed the 11 displayed numerical quantities from the stored
  pilot comparison with decimal arithmetic. Both one-sided improvement floors
  exceed 1%. Checked eight local evidence/source links and document formatting;
  repaired one relative evidence link before delivery. Validation metadata is
  `/home/mchoi/so007-certified-regional-evidence-20260908/reports/threshold-regional-proposal-validation.json`.
  Independent mathematical/report review passed; consistency restoration was
  clarified to cover replicated auxiliary variables as well as original decisions.
- **Remaining issues**: implementation and empirical evaluation of this proposed
  extension remain future work. The existing 148 tests validate the earlier
  implementation and do not establish correctness or speedup for the proposal.
- **Potential regression / detection**: misleading completeness or convergence
  claims. Keep resource/fairness assumptions, distinguish exact relaxation optima
  from incomplete raw bounds, and retain the explicit proposal status when
  reusing this document.

### Three supplied Regional algorithms — implementation in progress

- **Request**: implement Threshold, Target-gap and cached-probe Regional search
  separately and evaluate them on so007 at planning only.
- **Isolation**: branch `feature/regional-threeway-20260908`, source
  `/home/mchoi/so007-regional-threeway-20260908`, evidence
  `/home/mchoi/so007-regional-threeway-evidence-20260908`. Earlier source/JAR and
  completed pilot are preserved. An unrelated existing coordinator container
  on so007 is outside this task and is not stopped or modified.
- **Design**: shared original-model canonical validation and conditional factors;
  persistent replica equality relaxation for algorithm 1; complete frontier
  partitions with deferred-node accounting for algorithms 2 and 3. New selector
  `regional.algorithm` requires `regional.mode=anytime`; default `legacy` keeps
  the existing planner path. Source plan is
  `docs/REGIONAL_THREEWAY_IMPLEMENTATION_PLAN.md`.
- **Review**: an independent architecture pass identified factor ownership,
  overlapping probe partitions, auxiliary conditioning, work-based scheduling
  reproducibility, config compatibility and threshold-specific experiment
  controls. Its artifact is in the new evidence root `reviews/architecture-plan.md`.
- **Verification pending**: new targeted tests, existing targeted regressions,
  fresh so007 package, planning-only smoke and paired measurements. No new
  correctness or performance result is claimed by this in-progress entry.
- **Risks / detection**: weak bounds may still prevent requested thresholds;
  exact phases have soft deadlines; interrupted probes must preserve coverage;
  deferred nodes stay in global LB. Preserve all failed/censored rows and verify
  initial objectives, model/order identity and algorithm-specific work traces.

## 기존·수정 Anytime를 포함한 네 환경 native JVM 검증 — 진행 중

- **상태**: Target Anytime 구현·테스트 완료, 전체 반복 측정 전 사전 점검 중.
- **문제 정의**: 사용자는 10 ML + P1/P2 + 실제 SliceLine 입력에 대해 Global,
  Regional, 기존 Anytime, 수정 Anytime, Algorithm 1/2/3을 네 network cost
  profile에서 비교하도록 요청했다. 기존 Anytime도 U/L 개선과 threshold 검사가
  있었으므로 단순히 같은 검사를 새 기능으로 설명해서는 안 된다.
- **해결 / 수정 파일**: `TargetAnytimeOptimizer.java`를 별도 selector
  `algorithm=anytime-target`로 추가했다. typed whole preflight, 누적 region growth,
  무개선 시 growth 가속, 목표 residual 기여가 작은 후속 MBE width 중단을 구현했다.
  `RegionalSearchOptimizer`, `RegionalSearchProblem`, `FederatedPlanLocalCost`와
  관련 테스트만 확장했다. Legacy 및 Algorithm 1/2/3 정책 소스는 그대로다.
- **규칙과 의사결정 근거**: 최신 사용자가 Docker를 실행하지 않고 네 환경의
  bandwidth/latency 값만 채택한 로컬 JVM planning을 명시했다. 따라서 이전
  Docker-only 실험 지침에 대한 해당 세션의 override로 native compile-only 경로를
  사용한다. `run_LAN.sh`나 Docker/worker/workload/CP reference 실행은 사용하지 않는다.
  privacy, legality, runtime, cost surface 규칙은 수정하지 않았다.
- **구현 검증**: commit `370d6a8ba3eb99425c3b5c1c83ffb113ee554e07`, so007 clean
  package 19 classes / 136 tests, 실패·오류·skip 0. JAR SHA-256
  `0b896e12ebbdf0a061bece458e826ac02923eeadae0307fea143995e5909ed4a`.
  근거 `/home/mchoi/so007-sevenway-evidence-20260908/validation/implementation-provenance.json`.
  [구현 보고서](ANYTIME_TARGET_IMPLEMENTATION_KO.md)에 설정·정리·테스트를 설명한다.
- **실험 조건**: 기본 16 workloads × 4 profiles × 7 methods × 5 paired repetitions,
  상대 목표 1%, seed 이후 soft budget 20초, JVM watchdog 60초. 별도 KMeans/L2SVM
  LAN/WAN-heavy × 7 × 3회에서는 초기 whole exact gate를 0으로 설정한다.
  이 항목 작성 시 protocol은 draft이고 본 측정은 아직 시작하지 않았다.
- **사전 점검**: 새 JAR의 KMeans/PCA/L2SVM LAN 21행 모두 compile-only receipt 통과.
  같은 encoded model, Regional initial U, initial MBE 및 oracle enclosure 검증 통과.
  다른 workload와 WAN 설정 점검은 진행 중이며 smoke는 성능 집계에서 제외한다.
- **잔여 이슈**: phase는 soft deadline을 초과할 수 있다. 초기 whole closure를 사용한
  결과는 누적 Regional 정책 자체의 효과와 구분한다. Legacy와 Global에는 새 controller의
  work-estimate gate가 적용되지 않으므로 동일 설정 문자열만으로 동일 자원 정책이라고
  주장하지 않는다. 5회 cyclic 실행 순서는 cell별 7개 position의 완전 균형이 아니다.
- **잠재 회귀 위험 / 감지**: false certificate, 잘못된 full-Q closure, output 생성,
  초기 model/seed 불일치를 targeted tests, raw traces와 독립 Global로 검출한다.
  source/input/runtime hash는 각 campaign 전후, 입력과 JAR는 각 JVM 전에 확인한다.

### P2_PREP 공통 배치 실패 — 기존 제약 확인, 실패 행 보존

- **상태**: 원인 확인; privacy 규칙을 완화하지 않고 해당 입력의 실패로 기록.
- **조건 / 재현**: 기존 P2 X/Y metadata의 `PRIVATE_AGGREGATE`를 보존한
  `native/inputs-v1/programs/P2_PREP.dml`, worker 1, LAN, 7개 selector.
- **관측**: `No privacy-safe physical placement`가 transformencode의 metadata
  `DataOp:FunOut M:M`에서 발생한다. optimizer 선택 이전의 공통 legality 분석이다.
- **원인**: metadata M은 local 출력이고 PRIVATE_AGGREGATE로 전파된 해당 상태를
  원래 규칙 아래 배치할 수 없다. 2026-09-06 세션에도 같은 P2 조건이 기록되어 있다.
- **해결**: template의 M output, metadata privacy, candidate space를 변경하지 않는다.
  실제 실패를 남기고 인증 성공률의 분모에 포함한다. hash가 일치하는 raw log에서
  privacy 실패를 구분하며, 실패한 행에 계획·LB·certificate를 채우지 않는다.
- **수정 파일 / 검증**: planner 수정 없음. native analyzer의 실패 분류만 보강했고
  hash-bound 분류 회귀 테스트를 포함한 analyzer 9개 Python 테스트가 통과했다.
  input smoke의 Regional 1행과 새 JAR의 7개 방법에서 같은 배치 실패를 관측했다.
- **로그 근거**: `/home/mchoi/so007-sevenway-evidence-20260908/native/runs/remaining-workloads-smoke-v1/`.
- **잔여 이슈 / 위험 감지**: P2의 feasible 계획 성능은 이 조건에서 비교할 수 없다.
  privacy를 변경한 후속 실험은 다른 문제이므로 이번 행을 대체할 수 없다.
  main campaign에서 다른 failure 원인이 발생하면 raw log로 따로 분류한다.

### 실험 launcher의 중단 복구 — 보강 완료, 새 smoke 검증 중

- **문제**: JSONL append 중 중단되면 마지막 fragment 때문에 resume parsing이
  실패할 수 있고 Python interruption 정리가 없으면 child JVM이 lock보다 오래 살아
  다음 측정에 영향을 줄 수 있다. 정상 완료 행의 certificate 의미와는 별개다.
- **해결**: newline으로 완료된 record를 먼저 모두 검증하고 마지막 미완료 fragment만
  hash 이름으로 보존한 뒤 atomic `collected.json`으로 복구한다. parent는 Popen 전에
  SIGTERM/SIGINT를 잠시 block하고, child는 exec 전에 원래 mask를 복원한다. parent는
  process group과 pipe 소유권을 얻은 뒤 pending signal을 처리하므로 시작 직후 중단도
  해당 group의 kill/reap을 거친다. 원래 handler/mask와 raw log를 보존한다.
- **수정 파일 / 검증**: native `run_sevenway.py`와 테스트만 변경. runner 12개,
  analyzer 9개 Python 테스트가 live 통합 후 통과했다. injected launch-window SIGTERM,
  child mask와 graceful watchdog, malformed committed JSONL, 마지막 fragment 복구를
  검증했고 독립 review가 CLEAR for integration으로 판정했다. 이후 WAN smoke가 새
  runner SHA `dc43037d8241609ca354ed5c17e76a42cff577109b89ec5a45edd6e6794ff179`를 사용한다.
- **잔여 위험**: Linux의 single-threaded launcher를 전제로 한다. SIGKILL이나 host crash는
  Python cleanup 자체를 실행할 수 없으므로 재개 전 해당 실행의 process 상태를 확인해야 한다.
  일반 SIGTERM/SIGINT cleanup은 대상 group만 종료하며 다른 workload/컨테이너는 건드리지 않는다.


## C / AnytimeTarget 재설계 및 기존 실험 중단 — 진행 중

- 증상: C는 LM에서 충분한 primal 개선 없이 LB 탐색을 지속했고, Target은 raw
  admission 때문에 reduced exact solve가 가능한 작업도 거절했다. Regional의 기존
  block은 연결된 assignment가 바뀌어도 재방문하지 않았다.
- 사용자 변경: 기존 실험을 2,072행에서 중단. C/Target을 개선하고 Global과 함께
  5%, 3%, 1%에서 새로 비교한다. 이전 84행 후속 실험은 취소했다.
- 해결 방향: prepared reduced admission, C 최대2회 무조건부 incumbent 개선,
  bounded seed revisit, Target의 보류 width 재개. 상세 계획은
  REGIONAL_REFINEMENT_PLAN_KO.md를 참조한다.
- 수정 파일: ExactPhysicalReducedSolver, ExactCategoricalSolver의 좁은 통계 accessor,
  RegionalSearchProblem/Optimizer, BranchingRegionalOptimizer, TargetAnytimeOptimizer,
  LocalCategorical/PhysicalOptimizer, FederatedPlanLocalCost 및 관련 테스트.
- 검증: 새 worktree에서 clean package와 focused19클래스147테스트 통과(실패·오류·skip0).
  독립 구현 검토와 so007 native pilot은 진행 중이다. 최종 성능은 아직 주장하지 않는다.
- 잔여 이슈: 100k reduced gate로도 StepLM/LM 전체 exact는 제한될 수 있다. Seed의
  추가 비용 및 준비 단계의 실측 비용을 ablation에서 확인해야 한다.
- 잠재 회귀: 잘못된 캐시 재사용, partial-region infeasibility의 오해, 추가 seed 비용,
  floating-point false certificate. 조건·limit 변경 테스트 및 모든 checkpoint의
  독립 Global oracle enclosure/monotonicity 검증으로 탐지한다.
- 적용 원칙: planner 정책만 변경한다. Privacy/placement legality, canonical objective와
  runtime 계약은 유지한다. 사용자의 native JVM 지시가 과거 Docker-only 절차를 대체한다.

### 개선 실험의 raw auditor API 호출 오류 — 수정, native 재검증 중

- 상태/증상: baseline42행의 so007 원본 감사에서 `validate_compile_config() takes 1 positional argument but 2 were given`가 발생했다.
- 원인: 감사 helper가 keyword-only `allow_runtime`을 positional로 전달했다. 로컬 snapshot에는 외부 설정 파일이 없어 해당 경로가 실행되지 않았다. 원본 trial 실패와 다른 수집 후 검증 도구의 오류다.
- 해결: `validate_compile_config(config_path, allow_runtime=False)`로 수정하고 반환 SHA 및 planner를 receipt와 대조한다. 기존 실패 감사 파일은 보존한다.
- 수정 파일: 새 evidence/native/audit_raw_evidence.py 및 test_audit_raw_evidence.py. Frozen runner/수집 모듈과 raw trial은 수정하지 않았다.
- 검증: 실제 존재하는 compile-only config를 이용한 회귀와 planner mismatch 거절 검증을 포함해 Python37테스트 통과. Native 원본 전체 감사 재실행 중. 최초 native 감사에서 외부 asset479개의 SHA는 모두 일치했다.
- 잔여 이슈/회귀 위험: 로컬 snapshot에 없는 외부 자산의 검증을 완료로 오해할 수 있다. 최종 감사는 실제 경로가 존재하는 so007에서 실행하고 matched/absent 수를 별도 기록한다.
- 의사결정 근거: planning-only receipt의 기존 계약을 더 정확히 검사하며 privacy나 실행 범위를 변경하지 않는다.

### Regional seed 재방문의 기본값 재조정 — v2 검증 중

- 증상/조건: core(seed0,rescue0)와 seed(seed2,rescue0)의 7개 입력 ×LAN/WAN-mid 비교42행씩에서, 28개 certified 행의 초기 U가 모두 동일했고 성공 수(Target10/14,C11/14)도 같았다. 재방문 자체는 여러 입력에서 발생했다.
- 원인/판단: 기존 block의 재해결이 이 pilot의 계획 비용을 추가 개선하지 못했다. 이 결과는 모든 모델에서 재방문이 무효라는 정리는 아니다.
- 해결: 재방문 기능과 정확성 테스트는 유지하고 기본 pass 수를0으로 되돌린다. `seedRevisitPasses=2` 등으로 명시적인 ablation이 가능하다. CONFIG trace는 실행 경로와 동일한 검증 helper를 사용해 기본값/유효 범위의 중복 파싱을 없앤다.
- 수정 파일: LocalPhysicalOptimizer.java, FederatedPlanLocalCost.java, LocalCategoricalOptimizerTest.java.
- 검증 근거: evidence/validation/pilot-comparison-seed-v1/pilot_comparison.json; 두 campaign의 native raw42행 각각 전체 asset 재해시 통과, 모든14개 paired cell 검증 통과. v2 package/전체 focused suite는 진행 중이다.
- 잔여 이슈/회귀 위험: pilot1회 결과로 timing speedup을 확정하지 않는다. 기본값과 CONFIG가 달라지는 오류는 property regression 및 새 JAR smoke로 검출한다. 실행 중인 v1 rescue pilot의 JAR/소스/설정은 수정하지 않는다.
- 의사결정 근거: 원래 Regional 비용 계약을 유지하면서 실측 품질 이득이 없는 추가 작업을 기본 실행에서 제외한다.


---

# AnytimeTarget 성능 개선 이슈 — 2026-09-08

## 1. 3%·5% 인증의 preparation 비용 (구현·집중 검증 완료)

- 증상: GLM/WAN에서 AnytimeTarget preparation/진단이 약 10–14초로 MBE 및 Regional solve보다 크고, Global보다 오래 걸린 뒤 목표에 미달한다.
- 원인 근거: 조건부 모델의 모든 auxiliary를 유지한 뒤, reduction으로 singleton이 된 변수도 네 번의 elimination-order planning에 포함한다. 전체 진단 시간은 측정값이나 내부 함수별 비율은 아직 profiler로 분해하지 않았다.
- 변경: 기존 raw cap과 hard feasibility reduction 뒤 singleton을 정확히 대입한 compact preparation 경로를 추가한다. AnytimeTarget만 targetCompactPreparation 설정(기본 true)으로 사용하며 Global의 기존 prepare/solve 호출은 유지한다.
- 적용 원칙: 후보 legality/privacy/runtime 규칙을 바꾸지 않는다. 대입은 hard reduction과 objective-preserving quotient로 singleton이 된 변수만 대상이다. 모든 auxiliary를 incumbent에 임의 고정하지 않는다.
- 수정 파일: ExactPhysicalReducedSolver.java, RegionalSearchProblem.java, RegionalSearchOptimizer.java, FederatedPlanLocalCost.java 및 focused regressions.
- 검증: all-singleton, 혼합 scope와 상수, 원래 representative 복원, exhaustive objective bit parity, raw cap의 사전 거절, 준비 결과 1회 재사용을 검사했다. 로컬·native clean package는 각각 19개 클래스 158개 테스트, failures/errors/skipped 0이며 JAR SHA가 동일하다. 독립 구현 검토 CLEAR. 세 단계 pilot 각 32회와 GLM 4개 환경·두 threshold·각 2회 확인 32회를 완료했다. 모든 128행의 raw audit, 각 479개 외부 자산 해시와 64개 paired oracle 검증이 통과했다. 초기 모델·seed assignment·MBE·order도 비교했다.
- 추가 조정: compaction만으로 GLM/WAN-mid는 목표 미달이었다. Compact root의 예상 작업량 688,683을 100k 한도가 거절한 뒤 13개 region에 7,400,179 작업을 사용했다. Whole gate를 기존 region gate와 같은 1m으로 맞춘 config-only 비교에서 이미 준비된 root를 exact로 닫았다. Generic Java 기본값은 100k를 유지한다.
- 결과: GLM 최종 16개 Target 실행 모두 3%·5%를 달성했고 paired Global보다 planner/launcher TTT가 짧았다. WAN 세 환경은 exact gap 0, LAN은 초기 1.888% gap이다. 세 pilot의 Target 성공은 14/16→14/16→16/16이었다. 각 최종 cell 2회이므로 보편적·통계적 speedup은 주장하지 않는다.
- 잔여 이슈: Global에는 같은 compact kernel을 적용하지 않아 동일 kernel 대비 threshold 탐색의 이득은 분리하지 못했다. LM·StepLM은 seed·setup 비용이 남아 Global보다 일관되게 빠르지 않다. 후속 reduced global MBE는 미구현 제안으로 남겼다. 상세는 ANYTIME_FAST_RESULTS_KO.md를 참조한다.
- 잠재 회귀: compact/원래 assignment 인덱스 혼동, 상수 누락, auxiliary 값의 잘못된 복원. 모든 checkpoint의 independent Global enclosure와 canonical raw-bit 검증으로 탐지한다.

## 2. 이전 대규모 비교의 중단과 증거 보존 (해결)

- 사용자 요청: Anytime 하나에 집중해 3%·5%를 Global보다 빠르게 인증할 방법을 개선·검증한다.
- 처리: 2026-09-08 16:55 UTC에 기존 main-refined-5-3-1-v2의 supervisor를 검증 후 SIGTERM했다. 완료 350행과 원본 자료를 보존하고 나머지 1,378행은 취소했다. 소유한 실행 프로세스가 남지 않았음을 확인했다.
- 근거: /home/mchoi/so007-regional-refinement-evidence-20260908/validation/main-v2-user-priority-stop.json.
- 잔여 이슈: 중단된 350행을 완료된 1,728행 benchmark로 보고하지 않는다. 기존 3%·5% 보고서는 첫 283행에 기반한 시점별 중간 기록이다.
- 잠재 회귀: 이전 WORK_STATE의 계속 실행 지시로 자동 재개하는 문제. 기존 파일 최상단에 최신 중단 상태와 새 작업 경로를 기록했다.

## 3. Native build 메타데이터의 종료 시각 (정정 기록 완료)

- 증상: native build command JSON이 local command를 복사하면서 local `finished_utc`를 남겨 native `started_utc`보다 앞선 시각이 표시됐다.
- 처리: 원본 command JSON은 보존하고 `validation/native-build-command-compact-v1.correction.json`에 잘못된 필드와 정정 근거를 기록했다. 실제 native subprocess 종료 시각은 별도로 관측되지 않았으므로 새 값을 만들어 넣지 않았다. Native test report의 `completed_utc`는 build와 검증 완료가 확인된 상한 시각이다.
- 검증: native test report는 subprocess returncode=0 뒤 작성됐으며 158개 테스트, source 7,502개, local/native 동일 JAR을 별도로 검증했다. 이 메타데이터 문제는 raw trial, 성능 시간이나 production code를 변경하지 않는다.
- 잔여 위험: native build duration 계산에 원본의 `finished_utc`를 사용하면 안 된다. 이번 보고서는 build 실행 시간을 성능 비교에 사용하지 않는다.

## 4. 동일 compact Global과 incremental Anytime 비교 (완료, 성능 한계 기록)

- **최신 요청/원칙**: Global도 compact+exact를 사용하고, Anytime은 global relaxation의 일부 coupling을 점진적으로 복원해 3%·5%에서 종료한다. 1%는 exact 권장 범위다. 앞선 사용자 native JVM planning-only 지시가 이 세션의 Docker-only 지침보다 우선한다. Privacy, legality, auxiliary와 canonical objective 계약은 유지한다.
- **구현**: `ExactPhysicalReducedSolver.CompactModel`을 Global과 새 `ANYTIME_INCREMENTAL` 입력에 공유한다. `IncrementalReplicaBound`는 명시적 MBE replica 공간의 독립 component exact 결과를 재사용한다. 한 encoded variable의 consistency를 복원할 때 영향을 받는 component만 재계산한다. `IncrementalAnytimeOptimizer`는 원래 feasibility와 canonical 비용으로 검증한 계획만 U에 반영하고, 목표에 도달하면 중단한다. 전체 exact shortcut은 호출하지 않는다.
- **V1 증상/원인**: GLM 4개 profile × 3%·5%의 Anytime 8회 모두 64-step 제한에서 목표 미달이었다. Replica projection만으로 만든 초기 U가 약했고, equality 하나씩 복원하는 작업은 진전이 느렸다. 완료 16행의 원본 감사 및 paired oracle 8개 검증은 통과했다. 성공한 JVM과 목표 달성을 구분한다.
- **V2 해결**: 기존 ordered greedy + hard repair를 neighborhood pass 없이 사용하고, 더 나은 검증된 projection과 비교해 seed를 고른다. 반복 canonical projection은 원래 assignment 단위로 최대 256개 캐시한다. Equality는 한 변수의 모든 replica group을 한 번에 복원한다. Primal region은 선택한 coupling에서 BFS로 최대 24개 active original을 택하고, 최대 2회만 시도한다. Optional greedy가 resource 한도를 넘으면 이미 검증한 projection을 보존한다.
- **V2 검증**: 구현 `8a91a903820208aad9677fcbbf8b0ba69841bc19`; local/native 각각 22개 클래스 187개 테스트, failures/errors/skips 0. JAR SHA-256 `80dd696e1338a14306052cc26b2c8557082d1b634d6b4aa58d92944f22280cd8`. Python 43개 테스트 통과. Native GLM 16행 완료, 8개 paired oracle 및 모든 checkpoint enclosure/monotonicity 검증 통과. 16개 raw trial과 선언된 외부 자산 전체 SHA 감사 통과.
- **V2 결과**: Anytime 8/8회가 목표를 달성했고 첫 목표 checkpoint의 `fullyRestored=0`이다. LAN은 INITIAL_BOUND에서 gap 2.79298%, planning 2.321/2.425초로 compact Global 3.066/3.020초보다 짧았다. LAN에서는 incremental action을 실행할 필요가 없었다. WAN은 31~54회 action 후 목표에 도달했지만 planning 12.159~22.146초로 Global보다 느렸다. Profile·threshold별 1회이므로 통계적 speedup을 주장하지 않는다.
- **남은 병목**: WAN의 bound 계산 8.933~18.603초 중 component preparation이 6.974~15.048초다. 제한된 primal solve는 0.225~0.298초에 불과하다. 초기 약 12~14개 consistency action은 global L을 높이지 못했다. 이미 복원한 equality를 명시적 factor로 계속 유지해 replica가 큰 compile 입력에 남아 있는 것이 다음 개선 대상이다.
- **V3 조치/검증 완료**: 복원한 equality class만 정확히 변수 하나로 대입하여 affected component compile 크기를 줄였다. Trial의 결과와 전체 replica assignment를 복원하고, 중단/실패 시 이전 인증 상태를 보존한다. Local/native 각각 187개 focused 테스트와 GLM 16회 raw audit를 통과했다. GLM의 8개 목표 모두 partial 상태로 달성했고, WAN Anytime planner 범위는 8.634~11.428초였다. 같은 compact Global보다 여전히 느렸다.
- **V4 조치/검증 완료**: 후보 우선순위를 modal minority groups / cached component elimination assignments로 바꾸고 기본 probe를 1개로 제한했다. 최빈값과 다른 group 수는 replica 순서 의존성을 줄이는 휴리스틱이며 argmin-set tie 분석이나 실제 gain 보장은 아니다. 190개 focused 테스트, GLM 16회 및 본 측정 256회 원본 감사를 통과했다. GLM WAN planner는 3.131~4.360초였으나 cell당 1회로 개별 변경의 독립 기여나 통계적 speedup을 주장하지 않는다.
- **V4 본 결과**: 128개 Anytime 시도 중 112개 목표 인증(초기 partial 13개, 실제 incremental partial 99개), partial planner 승리 24개, 그중 실제 incremental 승리 14개였다. 검증 가능한 120쌍의 planner 중앙값 Global 1.153초 / Anytime 1.447초다. P1 8회는 STEP_LIMIT64 미달, P2는 양쪽 각각 8회가 기존 privacy-safe placement 실패여서 oracle 검증에서 분리했다. 실패와 미달은 전체 분모에 남겼다.
- **V5 조치/빌드 검증 완료**: replica→component 배열을 초기화 및 성공한 merge에서만 재구성해 후보 조회를 O(1)로 줄이고, 첫 등장 순서를 유지하는 집합으로 replica root 중복 검사를 수행한다. 선택 순서/수치/자원 실패 계약은 유지한다. 세 번의 merge 사이에 cancellation을 넣는 회귀를 추가했다. Source `db092e3fea85789b28e4e4570f14f9ffc48a0036`, local/native 각각 191개 테스트(22 classes, 실패·오류·skip 0), JAR `a9c8afd660d5fdff9a3149a9748646a79a639a36611955465570054b6caa3b1f`가 일치한다. 독립 index review CLEAR.
- **V5 실험 변경/상태**: V4 P1이 after-seed 1초 미만에 64-step 한도로 멈춘 근거에 따라 시간·work·cell 제한은 유지하고 maxSteps/rounds를 256으로 늘렸다. 따라서 index 변경만의 ablation은 아니다. 2026-09-08 21:44 UTC에 시작한 새 256회 native planning은 완료했다. 선언된 256회 원본 감사 및 479개 외부 자산 대조가 통과했고, 정상 planning 120쌍의 독립 oracle와 모든 checkpoint 검증에 모순이 없었다. P2의 양쪽 각 8회는 공통 privacy 배치 실패다.
- **증거/문서**: `/home/mchoi/so007-anytime-incremental-evidence-20260908/native/runs/glm-incremental-v1`, `glm-incremental-v2`; `docs/ANYTIME_INCREMENTAL_RESULTS_V1_KO.md`, `docs/ANYTIME_INCREMENTAL_RESULTS_V2_KO.md`.
- **잔여 이슈/회귀 위험**: 처음부터 split이 없거나 모든 equality가 복원된 결과를 부분 강화의 성공으로 분류하지 않는다. 새로운 contraction은 비용 factor/상수 누락, source/replica index 혼동, trial 도중 equality commit을 exhaustive oracle와 cancellation 회귀로 검출해야 한다. Initial component work에는 별도 누적 cap이 없으며 after-seed budget은 soft deadline이다.

## 5. V2 build 증거 재생성 및 runtime freeze 경로 (해결)

- **증상/원인**: 하위 구현자가 완료 handoff 뒤 추가 회귀 테스트와 Maven을 실행해 parent package와 겹쳤다. 또한 freeze helper의 버전 문자열 일괄 변경으로 존재하지 않는 inputs-v2 경로가 잠시 만들어졌다.
- **해결**: 겹친 package 결과를 성능 근거로 사용하지 않고 superseded 파일로 보존했다. 최종 source를 고정한 뒤 양쪽 host에서 clean package를 다시 수행하고 187개 테스트 및 동일 JAR을 확인했다. 기존 frozen inputs-v1 절대 경로를 유지하도록 helper를 고쳤다. 최종 build 이후 freeze가 성공하기 전에는 V2 trial을 시작하지 않았다.
- **검증**: `validation/local-v2-rebuild-reason.json`, `local-tests-v2.json`, `native-tests-v2.json`, `prefreeze-v2.json` 및 raw audit. Source manifest는 build 전후 7,506개 파일을 대조했다.
- **잔여 이슈**: 없음. 다음 버전도 구현 handoff 후 source/test 변경을 중지하고, parent가 최종 package와 runtime freeze를 소유한다.
- **잠재 회귀**: 동시 Maven이 target/surefire 결과를 섞을 수 있다. 최종 source manifest, clean build의 독립 테스트 개수와 양쪽 JAR hash를 다시 확인한다.

## 6. 실패·초기 성공의 집계 및 phase 명칭 (해결)

- **증상/원인**: 기존 요약 helper는 Global 또는 Anytime의 planning JVM 실패가 있는 campaign의 전체 비교를 거부했고, 일부 초기-bound 성공을 incremental 승리와 혼동할 여지가 있었다. 독립 검증이 없는 특수 partial target을 fully restored로 분류하는 분기도 발견했다. 기존 `mbe_bound_seconds` 명칭은 초기 MBE만의 시간으로 오해할 수 있었다.
- **해결**: 모든 선언된 paired 시도를 보존하고 oracle/model identity가 검증된 결과에만 인증·속도 승리를 부여한다. Initial partial, 실제 incremental partial, 기타 partial, fully restored 및 실패/미달을 분리한다. Bound 시간은 `bound_control_seconds`로 추가 명시하고 기존 필드는 호환 alias로 남겼다. Component preparation/solve는 그 내부 항목임을 문서화했다.
- **수정 파일**: evidence/native/summarize_incremental.py, test_summarize_incremental.py 및 보고서 생성 helper. Frozen runner 네 파일과 raw trial은 변경하지 않았다.
- **검증**: 수정 후 부모 에이전트가 fresh Python suite 45개를 실행하여 실패·오류 0을 확인했다. 근거 `validation/harness-final-tests-v5.json`은 실제 검사한 파일 해시를 포함한다. V4 본 128쌍의 합계는 초기 13 + incremental 99 + 미달·실패 16으로 일치한다.
- **잔여 이슈/잠재 회귀**: P2의 실패는 이 집계 수정으로 해결되는 것이 아니다. 최종 표에서 검증 분모·전체 시도 분모와 성공-only TTT를 혼동하지 않도록 원본 감사와 보고서 합계 검사를 함께 유지한다.
- **적용 원칙**: 실패를 성공으로 바꾸거나 privacy/placement를 완화하지 않고, 실제로 검증한 범위만 보고한다.

## 7. V5 최종 결과와 비교 해석 (완료)

- **결과**: 3%와 5% 각각 60/64회 목표 인증, 합계 120/128회다. 남은 8회는 P2_PREP의 공통 planning 실패다. 성공 120회는 초기 partial 13회와 실제 incremental partial 107회로 나뉘며 fully restored 성공은 0회다. Root whole closure도 정상 Anytime 120회 모두 시도·완료 0이다.
- **속도**: 같은 조건의 Global보다 빠른 partial 인증은 38회이며 이 중 실제 incremental 강화 후 빠른 경우는 26회다. 120개 검증 쌍의 전체 planner 중앙값은 Global 1.2547505초 / Anytime 1.446368초다. 3%는 1.289219 / 1.462774초, 5%는 1.2384975 / 1.446368초다. 각 조건 1회이므로 보편적·통계적 speedup을 주장하지 않는다.
- **V4/V5 경로 검증**: 두 버전의 성공한 240개 JVM 시도에서 analysis/cost fingerprint가 같고, 120개 Anytime의 초기 L/U, seed·초기 assignment와 replica partition도 같다. 112개는 전체 semantic checkpoint 경로가 같았다. P1 8개는 첫 64개 action까지 같고 V4의 STEP_LIMIT 종료 뒤 V5가 계속 진행했다. P1 목표 인증은 0/8→8/8이지만 V5의 planner 중앙값은 Global 3.531초 / Anytime 4.231초로 더 길다.
- **해결/변경 범위**: Global과 Anytime의 같은 compact kernel, 점진적 component LB 강화, contraction, 재사용, 저렴한 1-probe 후보 선택과 lookup index를 구현·검증했다. 허용 gap이 커졌다는 사실만으로 초기화·진단·projection overhead가 사라지는 것은 아니다.
- **남은 이슈**: P2는 동일 원래 privacy/placement 계약에서 feasible planning을 비교할 수 없다. 모든 입력에서 Anytime의 속도 우위는 확인되지 않았다. Soft deadline, 초기 component 누적 work cap 부재, resource-blocked key 재시도 한계는 구현 보고서에 남겼다.
- **증거/보고서**: `ANYTIME_INCREMENTAL_RESULTS_KO.md`, `ANYTIME_INCREMENTAL_RESULTS_MAIN_V5_KO.md`, `ANYTIME_INCREMENTAL_IMPLEMENTATION_REPORT_KO.md`; evidence `validation/main-incremental-v5-audit.json`, `incremental-final-aggregate.json`, `main-v4-v5-comparison-summary.json` 및 `native/runs/main-incremental-v5`.
- **잠재 회귀/감지**: 미검증·실패를 성공 분모에서 제거하거나 초기 성공을 incremental 성과에 합치는 오류는 분류 회귀와 보고서 합계 검사로 검출한다. Index 변경만으로 성능 개선을 단정하지 않고, 명시한 64→256 단계 상한 변경과 실제 공통 경로 대조를 함께 보존한다.

<!-- Existing main session records retained during publication merge. -->

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
