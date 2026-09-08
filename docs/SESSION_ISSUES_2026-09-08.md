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
