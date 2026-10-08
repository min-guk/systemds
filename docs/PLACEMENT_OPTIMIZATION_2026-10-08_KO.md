# Placement 분석 최적화 및 ML training 연산 경로 확장

## 1. 결론과 범위

네 영역을 구현했다. 최종 **70 suites / 573 tests PASS**, failure/error/skip 0, Maven package **BUILD SUCCESS**. 독립 source review **APPROVE**, architecture **CLEAR**.

실제 full LogReg/GLM의 변경 전·후 Docker planning은 동일한 **60초** 제한 내 공통 분석을 완료하지 못했다. 완료 receipt가 없으므로 전체 속도 개선율, Local/Global DP 시간·SUFFIX cut 효과, ML10 전체 runtime 성공을 주장하지 않는다.

“모든 training 연산 적용”은 **고정 DML 10종의 모든 연산 family에 exact 경로와 불변 payload 공유 경로를 제공**한다는 의미다. 모든 연산을 shape-independent로 선언하거나 모든 tuple/support를 Cartesian product로 압축했다는 뜻이 아니다. 정확한 상관관계가 필요한 residual은 tuple별 rule 평가와 후보를 유지한다.

## 2. 구현

| 우선순위 | 변경 | 의미 보존 경계 |
|---|---|---|
| LogReg | immutable support-clause/PlacementProofKey hash 캐시; GroundedNativePreparation identity fast path; direct input lookup | 기존 hash 공식·collision·zero hash·equals 보존, 동일 값의 다른 owner는 identity authority로 구별 |
| GLM | 정확한 canonical text의 최대 96 UTF-16 문자 prefix; 동일/부분집합 union과 관찰 없는 transition의 ordered set 재사용 | prefix tie는 기존 exact comparator, comparator 소유권·canonical 동률 대표·환경 상한 유지 |
| 공통 proof | topology-hit default dependency list/미변경 dependency 공유, pin된 slot만 overlay; required-input 계산 proof-loop 밖 이동 | 불변 상태만 공유, pin owner identity/template-root 의미 유지, 실행 가능성은 lookup 시점 검사 |
| 후보 생성 | 추가 13 rule family determinant kernel; exact residual의 완전 evidence interning; immutable header/profile 공유 | 매 tuple fresh exact ShapeHint/forward rule, privacy 순서·오류 전파 유지. key/emission/support/owner authority는 합치지 않음 |

### 자원·합법성 경계

- Direct input index는 **binding >= 8 && required input >= 4**일 때만 proof당 한 번 만든다. invocation 내 최대 **65,536 retained bindings**를 캐시하며 포화 시 local index만 사용한다. 작은 proof는 기존 순서의 short-circuit 선형 검색을 유지한다.
- Exact residual evidence와 header/profile pool은 각각 build-local **최대 256 entries**다. 포화는 공유만 중단하며 rule 평가나 합법 후보를 중단하지 않는다.
- 공유 key는 모든 capability 필드, **순서 있는 notes**, 완전한 ShapeProof를 포함한다. mutable ShapeHint 자체는 공유하지 않는다.
- right-index literal bounds/anchor/filtered partition 증거를 추가할 때는 evidence-identity shortcut을 사용하지 않는다.
- Profile inference를 먼저 그대로 실행한 뒤 결과 값만 공유한다. scalar 정규화 등을 이유로 평가·예외를 생략하지 않는다.
- Runtime fallback, 새로운 dependency, 공개 정책 flag, TR/TW·recompile·privacy 완화, 근거 없는 후보 제외는 없다.
- PlacementProofKey는 cached field를 위해 record에서 final class로 변경됐다. 생성자/accessor/equals/hash/toString 계약은 유지하지만 외부 reflection의 Class.isRecord()는 달라진다.

### 실제 JFR에 따른 추가 hash 수정과 검토

v1에서는 긴 authority 문자열 hash가 proof-key 생성자로 이동해 남았다. 최종판은 proof-local helper에서 **cold segmented authority의 기존 정확한 String hash를 재사용**한다. 임의 raw hash를 외부에서 전달하지 않는다.

초기 추가 수정은 두 자원 회귀를 검토에서 발견해 바로잡았다. cold 경로의 signature cache/rope release 생략, 그리고 warm literal descriptor를 다시 materialize해 동일 내용의 String을 중복 보유하는 문제다. 최종 helper는 기존 proof-local/structural-cache String이 있으면 원래 String constructor를 사용하고, cold에만 factory+normalization/release를 수행한다. 실제 publication의 cold/warm/constructor-cache-hit String identity와 rope 해제를 영구 테스트로 잠갔다. Warm/literal의 모든 hashing이나 materialization 자체를 제거했다는 주장은 하지 않는다.

v2는 통합 테스트를 통과했어도 독립 architect WATCH 이후 최종 후보에서 제외했다. 완료된 v2 LogReg 진단과 검토 중 중단한 GLM 원자료는 별도로 보존했다. 최종 source reviewer APPROVE/architect CLEAR 및 **70 suites/573 tests**는 이 warm-cache 수정까지 포함한다.

### 추가 determinant kernel

UnaryElemwise, ReorgUnary, AggUnary, Reblock, CentralMoment, Solve, Quantile/Interquantile deny, Contains, Replace, PlacementAlias, VariableWrite, TransientWrite, TransientRead. arity/signature 경계를 가지며 dimension/FULL metadata를 읽지 않는 기존 forward rule에만 적용한다. 기존 weighted quaternary kernel도 유지한다.

MM/binary/index/function-output 등 tuple·shape에 민감한 family는 무리하게 shape-independent로 선언하지 않고 exact residual로 처리한다.

## 3. 실제 training DML inventory

권위 원본은 cofee-evaluation/campaign/run_ml10_campaign.py와 planning_study/native/input_templates/w1/programs다. 원본 SHA/DML bytes를 저장소 resources에 고정했다.

- PCA, ALS, KMeans, LM, **LogReg**, L2SVM, StepLM, **GLM**, GNMF, GMM-VVI: **10종**.
- 함수 내부/control predicate 포함 **12,909 occurrences**: constructed 9,717 + rewritten 3,192.
- **88 normalized operation families**: determinant relation **32**, exact residual **56**.
- [family 경로](../src/test/resources/fedplanner/training-operations/OPERATION_FAMILIES.tsv), [모든 발생 위치](../src/test/resources/fedplanner/training-operations/OPERATION_OCCURRENCES.tsv), [원본 provenance/SHA](../src/test/resources/fedplanner/training-operations/SOURCE_SHA256SUMS.txt).

null/ABSENT_LOCAL 포함 7개 입력 값: 단항은 전부, 다항은 첫 두 축 7×7과 나머지 축별 ROW/COL/BROADCAST/null perturbation, FULL metadata true/false/unknown을 비교했다. **고차 전체 Cartesian 공간의 exhaustive 증명은 아니다.**

초기 diagonal 표본에서 추정했던 CP_ONLY route는 제거했다. relation 없는 family는 모두 EXACT_RULE_RESIDUAL이며, CP-only 표본 관측은 별도 비권위 진단으로만 남겼다. Inventory 테스트는 **HOP rewrite 단계에서 종료**하며 전체 placement closure/runtime/privacy 성공을 증명하지 않는다.

Inventory X는 50,000×2,100이고 아래 성능 진단 X는 50,000×128이다. 서로 다른 연구 fixture임을 구분한다.

## 4. 검증

최종 명령은 artifact root R의 evidence/test-selector.txt를 사용한 offline Maven package다. TMPDIR 및 java.io.tmpdir는 R/tmp, MAVEN_OPTS는 -Xmx4g/-XX:ActiveProcessorCount=4, 옵션은 -o -B -Djacoco.skip=true -Dmaven.test.skip=false다.

- 신규: LogRegHashMembershipOptimizationTest, PlacementJointInputOrderedEnvironmentOptimizationTest, NativeProofOverlayReuseTest, RulesetShapeIndependentKernelContractTest, ExactRuleResidualTest, TrainingWorkloadOperationCoverageTest.
- 기존 CandidateRouteMetricsTest/SearchSpaceFineGrainedMetricsTest: 실제 residual routing, header/profile identity 공유, counter snapshot/reset/live 보강.
- 기존 DP numerical/failure/parity, owner/identity, native support, joint/invocation, canonical fingerprint 포함 **70 suites/573 tests**.
- 변경 전 expected-failure/isolated verification 로그도 보존했다. git diff --check 통과.
- 전체 저장소 green 주장은 아니다. 별도 표본 실행한 두 identity-contract suite의 3개 실패는 변경 전 binary에서도 재현됐다(function-root-unproven/null function-input-name); 별도 baseline 로그로 남겼다.
- Reviewer의 eager tiny-proof index 회귀 지적은 adaptive threshold/binding-count cap으로 수정한 후 통합 빌드를 실행했다.

## 5. Docker 성능 진단

공식 scripts/fedplanner/run_LAN_docker.sh --function-boundary-compare만 사용했다. 고정 image, 동일 input/config/cost, network none, 4 CPU quota, 16 GiB memory, 10 GiB heap, full LogReg(maxi30/maxii5)/GLM(moi20/mii5), PRIVATE_AGGREGATE X의 W1 compile-only다.

- Baseline/candidate collector OFF JFR 각각 LogReg/GLM 2회, candidate collector ON 상세 진단 2회: **최종 비교 6회**.
- 이외에 v1 진단 4회, 제외된 v2의 완료 LogReg 1회/검토로 중단한 GLM 1회도 별도 보존했다. 전체 12개 container를 시작했지만 v2는 최종 비교에 섞지 않는다.
- 설정은 JFR **55초**, watchdog **60초**로 유지했다. 검토로 중단한 v2 GLM은 이 시간까지 진행하지 않았으며 benchmark 통계에서 제외한다.
- **최종 비교 6회**는 모두 analysis_begin 이후 미완료, receipt 없음, 관찰 OOM 0이다. 이 6회의 약 62–63초 supervisor wall은 SIGQUIT/kill/cleanup 포함이며 planning 완료 시간이 아니다. 검토 중단된 v2 GLM에 이 wall-time 설명을 적용하지 않는다.
- 새 Global run은 하지 않았다. 공통 분석이 끝나지 않아 Local/Global 후단 DP cutoff 효과를 측정할 수 없다.

### 실제 후보 경로 및 payload 공유

Collector ON 마지막 live snapshot: LogReg 분석 55.043초 / GLM 57.438초. 동일 작업량 cutoff가 아니다.

| 계측 | LogReg | GLM |
|---|---:|---:|
| determinant execution-relation 호출 | 651 | 1,600 |
| exact residual 호출 | 850 | 3,091 |
| residual forward rule 평가 | 2,090 | 7,431 |
| 완전 evidence 재사용 | 804 | 2,569 |
| header 재사용 / 요청 | 808 / 3,055 | 2,583 / 9,862 |
| profile 재사용 / 요청 | 795 / 3,055 | 2,907 / 9,862 |
| evidence overflow | 0 | 0 |

이전 진단에서 0이던 determinant 경로가 실제 full LogReg/GLM에서 사용됨을 확인했다. 완료 A/B가 아니므로 이 횟수로 speedup을 계산하지 않는다. EXACT_RULE_RESIDUAL은 MRV/CARTESIAN traversal과 겹치는 분류로 route 합계를 전체 호출 수로 더하면 안 된다. 이번 snapshot의 MRV/CP-family는 여전히 0이다.

### JFR 및 메모리

최종 v3와 baseline의 Collector OFF, main-thread sampled stacks, depth64 기준이다. inclusive frame 비율은 중첩될 수 있으며 더하지 않는다. 표본 부재가 절대 실행 비용 0이라는 뜻은 아니다.

| workload / 관측 | Baseline | 최종 v3 |
|---|---:|---:|
| LogReg main samples | 3,482 | 3,130 |
| support-clause hash inclusive | 22.95% | 0% 관측 |
| proof-key hash inclusive | 21.37% | 0% 관측 |
| containsExact inclusive | 22.17% | 0.06% |
| direct binding inclusive | 79.67% | 73.64% |
| LogReg sampled cgroup peak (decimal GB) | 5.903 | 6.161 |
| GLM main samples | 3,725 | 3,183 |
| joint-input 아래 canonical comparison inclusive | 48.97% | 45.52% |
| joint-input 분석 inclusive | 54.34% | 51.08% |
| GLM sampled cgroup peak (decimal GB) | 5.597 | 5.679 |

v1에서 약18%였던 proof-key 생성자의 긴 authority hash 문제를 추가로 수정했다. 최종 LogReg에서는 생성자 inclusive **0.42%**, StringLatin1.hashCode **2.24%**가 관측됐다. Factory inclusive **7.83%**에는 여전히 필요한 String materialization 등이 포함된다. 캐시가 모든 hash/materialization 비용을 없앴다는 뜻은 아니다.

표본 비중은 전체 실행시간 개선율이 아니다. 서로 다른 진행 지점·JFR inlining·GC 영향도 있다. v1의 GLM canonical 비중35.46%처럼 더 낮았던 과거 표본을 최종판 수치 대신 채택하지 않았다.

LogReg direct binding은 여전히73.64%다. proof graph/materialization, NativePoolWitness 비교, GLM canonical fallback 비용이 남는다. 긴 공통 prefix가 원인일 수 있으나 prefix 길이별 비용은 계측하지 않았다. 최종 detailed snapshot의 proof-overlay self는 LogReg6.265초/GLM0.906초로 남아 있다.

미완료 단일 A/B이므로 총시간·memory 절감 효과는 확정할 수 없다. **최종 sampled cgroup peak도 두 workload 모두 baseline보다 높다.** v1 LogReg peak8.699GB도 별도로 조사했다. 그러나 측정 시점과 대상이 다른 JFR After-GC, 종료 직전 SIGQUIT heap, cgroup peak만으로 누수 유무를 확정하거나 최종판 memory 개선으로 일반화하지 않는다. 전체 Local/Global DP와 완료 work-equivalent 성능은 여전히 미검증이다.

## 6. 변경 파일과 보존

Production 9개: PlacementAnalysis, PlacementIdentity, PlacementRelationClosure, PlacementJointInputAnalysis, NativePlacementContinuity, PlacementCandidateGenerator, SearchSpaceMetrics, rules/Rulesets, rules/bridge/OracleFacade.

시작 시 27개 기존 파일 bytes/SHA를 동결했다. 위 production, 보강한 기존 2개 테스트, append-only session 문서 이외의 이전 dirty 파일은 그대로다. 이전 ExactCategoricalSolver/pruning/benchmark scripts를 덮어쓰지 않았고 commit/push하지 않았다.

Artifact root:
**/grid/3/cofee-lm-sweep-mchoi-20260914/placement-optimization-20261008**

- baseline JAR SHA: 5d52d5ecbf5a5bfac236f18a46089e23423050601b4a5691015fce5f794fee42
- candidate JAR SHA: 5368fb9e56752215b7d98a033ee7d7a0110315b727960dab0868f58f3e8de466
- evidence/artifact-manifest.json: source/JAR/full class tree/dependency/input seals.
- 각 launch.json: 실제 class tree hash. 각 command receipt: image/input/config/cost.
- evidence/maven-package.log, evidence/surefire/, evidence/review-verdict.json.
- 최종: baseline-jfr/, candidate-final-jfr/, candidate-final-detailed/: command/log/JFR/cgroup/last metrics.
- 이력: candidate-v1/, candidate-jfr/, candidate-detailed/; 제외된 candidate-v2/, candidate-v2-jfr/.
- evidence/performance-analysis.json, analyze.py: 재분석 방법과 수치.

## 7. 보존 archive

자체 stage 전체는 perf-stage.tar로 보존했다(498,288,640 bytes, SHA-256 1f08d7a67fbb28b6588f08f832a8323ec81afb3f253e743b12627cffcbef7ab4). archive 시점 stage 전체로, baseline/v1/v2/v3의 전체 class trees와 frozen dependency/input/probe, 12개 JFR, 최종 A/B result logs가 포함된다. 덮어쓰이기 전의 이력별 command/log는 artifact root의 각 run 디렉터리에 별도로 보존했다. 독립 verifier가 19,597개 sealed 파일과 12개 JFR의 bytes/SHA를 대조해 PASS 판정했다. 12개 자체 container의 부재를 확인한 뒤 자체 stage만 제거했다. 다른 workload/container는 건드리지 않았다.
