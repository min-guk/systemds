# Session issues: 2026-10-06

## Loop initial conversion — isolated implementation verified

### 1. 초기 로컬값에서 반복 FED 배치로 진입하는 유효 후보 누락 — 해결

- **환경/범위**: 새 worktree `/home/mchoi/w1357-loop-entry-20261006`, branch `fix/loop-entry-placement-20261006`, baseline snapshot `88e733d303fb453158ae67a2157deaeb80961f6a`. 원래 작업 디렉터리의 변경을 보존한 별도 snapshot에서 작업했다.
- **증상**: 초기 `p`가 CP/LOUT이면 반복 reader가 로컬 후보만 받고, 한 번 업로드한 뒤 FED 배치를 유지하는 계획이 빠졌다. 초기 후보를 확장하면서 LOOP_PHI로 분류된 실제 TRead/TWrite에 CP/FOUT이 생기는 기존 우회도 발견했다.
- **원인**: 반복 진입 producer에 사용할 수 있는 별도 concrete anchor가 후보 생성에 전달되지 않았고, continuity의 근거 노드가 native FED/FOUT만 인정했다. 기존 alias 제외 조건은 실제 HOP가 아닌 중간 node kind만 검사했다.
- **해결**: 진입 정의의 실제 producer와 그 시점에 사용 가능한 literal FEDERATED anchor를 찾는다. 허용된 CP/LOUT 연산에 기존 유료 materialization action을 추가한다. declared action/source/owner/proof/output-layout가 모두 맞는 업로드만 continuity 근거로 사용한다. 여러 초기 정의는 공통 배치를 seed하고 최종 all-definition/backedge 검사를 유지한다. 실제 TRead/TWrite도 검사해 CP/FOUT을 금지한다.
- **수정 파일**: `PlacementRelationClosure.java`, `NativePlacementContinuity.java`, `PlacementAnalysis.java`.
- **검증**: `LoopEntryMaterializationTest` 9건, `MaterializedContinuityTest` 4건. 독립 유한 oracle은 raw assignment 29,400개와 유효 receipt 280개를 모두 확인하고 예상한 7개 physical entry/steady 조합과 집합이 일치했다. `T=1,2,10`의 초기 업로드 비용은 양수이며 동일하다. 실제 ROW/BROADCAST 2-worker 실행에서 entry upload 1회, FED update 3회, 수치 결과, fallback/repair 관측값 0을 확인했다.
- **의사결정 근거**: 초기 변환을 명시적 비용·authority로 표현하며 privacy, transient identity, recompile CP/FOUT 금지, runtime fallback 금지 원칙을 유지했다.
- **잔여 이슈**: 모든 DML의 완전성을 입증한 것은 아니다. 함수 인자 anchor, branch-correlated worker pool, 반복별 다른 schedule, zero/single-trip CFG 특수화는 이번 범위 밖이다. 상세 범위는 `LOOP_ENTRY_IMPLEMENTATION_2026-10-06.md` 참고.
- **잠재 회귀 위험/감지**: 초기 업로드를 매 반복 과금하거나 alias에 업로드를 넣는 오류. 비용 trip-count 검사, exhaustive set equality, 실제 lowering/worker 검사가 이를 감지한다.

### 2. 계획한 업로드 범위와 실제 worker/range 배치 불일치 — 해결

- **증상**: native 연산의 범위 변환을 업로드에 재사용하면 바뀐 크기/축에서 틀린 범위를 예측했다. ROW/COL key가 한 축만 저장해 uneven range가 유실됐고, worker 정렬 순서와 데이터 범위 순서가 반대이면 실행 배치도 달라졌다. 원래 학습 예제에서는 새로운 4-coordinate key와 기존 2-coordinate live key가 문자열 비교에서 충돌했다.
- **원인**: runtime materializer의 범위 보존/균등 분할 규칙과 planner projection의 차이, live FederationMap의 입력 순서, 서로 다른 key encoding.
- **해결**: 업로드용 범위 계산을 기존 cost/placement utility에 추가하고 key serialization과 동일한 range-axis 순서를 사용한다. worker/range 쌍은 함께 이동한다. exact 업로드는 선택된 durable key를 사용한다. 현재 key producer/parser는 전체 두 축을 보존하고, 기존 축-only key와의 비교는 알려진 Lop 크기로 생략된 축을 복원한다. 크기가 없으면 기존 2-coordinate끼리의 동등성만 유지하고 4-coordinate와는 일치로 취급하지 않는다. 대상 크기와 같은 anchor의 gap/overlap은 runtime이 거부하므로 후보에서도 거부한다.
- **수정 파일**: `PlacementCostSemantics.java`, `ExactPlacementRegistration.java`, `FederatedRefedPolicy.java`, `FederationUtils.java`, `Dag.java`.
- **검증**: uneven/reversed ranges, 다른 크기·축, cardinality, legacy keys, key 충돌 검사를 통과했다. 실제 ROW 실행은 높은 port에 앞쪽 range를 배치하고 range-sensitive 결과 5564를 검증했다. 원래 training/validation 예제의 HOP 및 runtime instruction 컴파일도 통과했다.
- **의사결정 근거**: 선택된 물리 배치를 정확히 실행하도록 메타데이터를 보존한 수정이다. runtime에서 대체 anchor를 고르거나 잘못된 범위를 보정하지 않는다.
- **잔여 이슈**: 큰 원래 데이터의 학습 실행/성능 측정은 수행하지 않았다. 2-worker 수치 검증은 작은 실제 행렬에 수행했다.
- **잠재 회귀 위험/감지**: legacy key 처리, worker/range pairing 손실, shape 변화. 기존 key/lowering 검사와 새 실제 worker 검사가 이를 감지한다.

### 3. BROADCAST elementwise 연산의 유효 후보 누락 — 해결

- **증상**: `p + scalar`, `p - gradient`에서 BROADCAST 입력이 있어도 scalar 여부나 vector hint 때문에 CP로 제한했다. 초기 확장판의 `List.of(...).contains(null)`은 원래 예제의 profile 계산에서 NPE를 일으켰다.
- **원인**: explicit FED matrix-scalar/matrix-matrix runtime의 지원 범위와 rule의 불일치, immutable collection의 null 검사 방식.
- **해결**: runtime이 지원하는 BROADCAST+scalar, BROADCAST+BROADCAST, BROADCAST+local matrix와 반대 operand 순서를 rule에서 허용한다. representation guard와 실제 공통 worker pool continuity 검사는 유지한다. 기존 null-safe 반복 helper를 재사용한다.
- **수정 파일**: `rules/Rulesets.java`, `rules/BinaryElemwiseBroadcastMatrixRuleTest.java`.
- **검증**: 비가환 MINUS의 세 matrix 조합, 128×64 full matrix shape, guard-off, immutable input collection 검사 3건과 실제 BROADCAST 반복 덧셈을 통과했다.
- **의사결정 근거**: `BinaryMatrixScalarFEDInstruction`와 `BinaryMatrixMatrixFEDInstruction`가 명시적으로 지원하는 조합을 rule에 반영했다. BROADCAST를 전역에서 임의로 federated-like로 바꾸지 않았다.
- **잔여/회귀 위험**: 다른 연산의 모든 FType 조합을 감사한 것은 아니다. 새로운 shape/pool 조합은 기존 runtime legality/continuity 검증을 계속 거쳐야 한다.

### 4. 여러 anchor와 반복 replay에서 후보 identity 충돌 — 해결

- **증상**: 같은 출력 tuple의 여러 유료 업로드가 duplicate emission으로 거부됐다. 동일 worker pool의 다른 source geometry가 같은 출력 map을 만들면 realization reference가 충돌했다. 최종 확대 회귀에서는 `LoopSeedReplayWideningTest.repeatedReuseUpdateLoopConvergesWithOneAnalysisScopedSeed`가 `bindExactDerivedFoutAuthorities`에서 실패했다.
- **원인**: state만으로 emission을 식별하거나, provisional owner 두 개가 같은 canonical owner로 재결합된 뒤 생기는 중복을 합치지 않았다. 진입 단계 dedup만으로는 이 후속 중복을 해결할 수 없었다.
- **해결**: 기존 action-aware `selectionSignature()`로 emission을 식별한다. upload realization ID는 action signature에 결합하고 물리 배치 비교는 그대로 유지한다. replay 중복과 최종 authority 재결합 중복을 각각 해당 단계에서 정규화한다. 최종 중복은 기존 realization merge를 재사용해 모든 지원 clause를 보존한다.
- **수정 파일**: `PlacementAnalysis.java`, `PlacementRelationClosure.java`.
- **검증**: 서로 다른 pool, 같은 pool의 다른 geometry, 기존 반복 reuse/ALS/recompile 회귀를 통과했다. 마지막 코드 리뷰에서 distinct action 보존과 동일 realization의 clause 합집합을 확인했다.
- **의사결정 근거**: 유효 후보를 제거하지 않고 같은 exact action으로 확정된 표현 중복만 합친다.
- **잠재 회귀 위험/감지**: 과도한 dedup으로 서로 다른 유효 action을 제거하는 위험. 다중 anchor 회귀, fingerprint 재구성 일치, 독립 finite oracle이 감지한다.

### 최종 검증 및 재현

- Java 17 Maven build + targeted regression suite: **119건, 실패 0, 오류 0, 제외 7**; 실제 통과 112건. 제외 7건은 원래부터 적용된 PUBLIC-only fixture 정책이며 성공으로 계산하지 않았다.
- 명령과 test 목록: `.omx/loop-entry-evidence/validation.json`; 로그: `.omx/loop-entry-evidence/final-regressions-3.log`.
- 원래 조건의 예제: `.omx/loop-entry-evidence/training-validation/run.py`; `n=20,000,000`, `m=5,000,000`, `d=128`, `T=10`, ROW worker 2개, X PrivateAggregation, y/V/z Public, 기존 WAN-Mid 설정. `run.py`는 compile-only이며 큰 학습 실행은 하지 않는다.
- 그래프는 이전 `render.py`와 기존 Graphviz renderer를 재사용했다. 고정된 과거 relocation 개수 대신 이번 실제 instruction에서 개수를 검증한다. 최종 산출물과 checksum은 validation receipt에 기록한다.
- `git diff --check` 통과. 독립 code-reviewer의 최종 정규화 변경 리뷰에서 미해결 blocker 없음. 새 의존성, 옵션, runtime fallback 또는 원본 workspace 수정 없음.

### origin/main 통합 검증 — 해결

- **문제/원인**: 원래 검증 snapshot에는 이번 수정과 무관한 기존 미커밋 비용 모델·실험 변경도 포함됐고, `origin/main`에는 별도 후속 커밋이 있었다. snapshot 전체를 병합하면 요청 범위 밖의 변경까지 게시된다.
- **해결/수정 범위**: 새 worktree `/home/mchoi/w1357-loop-entry-main-20261006`에서 `origin/main`의 `adaebee9cc706cda52760f19ec8328a065118b8d` 위에 이번 20개 파일 변경만 적용했다. `PlacementCostSemantics.java` 충돌은 main의 비용 구현을 유지하고 materialized-output layout helper만 추가해 해결했다. 이 문서에는 이번 세션 이슈만 포함했다.
- **검증**: 통합 상태에서 production/test 전체 컴파일 및 동일 targeted suite 실행 결과 **116건, 실패 0, 오류 0, 기존 제외 7; 실제 통과 109건**. 실제 worker 검증 2건과 loop replay 6건 모두 통과했다. main의 기존 선택 test 클래스에는 원래 snapshot보다 3건이 적다. 독립 dependency review 및 `git diff --check`도 통과했다. 명령은 `LOOP_ENTRY_IMPLEMENTATION_2026-10-06.md`, 로컬 로그는 `.omx/loop-entry-publish/main-regressions.log`에 있다.
- **잔여 이슈/회귀 위험**: 위 A/B 비용·그래프 검증은 원래 snapshot 기준이며 main의 비용 수치로 일반화하지 않는다. main에 없는 snapshot 전용 API 의존성은 발견되지 않았다. 기존 completeness 제한은 그대로 유지한다.


## Function binding alias and GET sharing — origin/main integration

- **문제 정의**: 함수 input binding에서만 값 배치 변환을 허용하던 규칙을 TW/TR 및 output binding과 일원화했다. 이후 같은 MatrixObject를 caller/formal이 읽어도 호출 문맥별로 GET을 중복 과금하는 비용 오류를 확인했다.
- **변경 요약**: actual→input binding을 SAME_VALUE_PLACEMENT로 하고 formal 후보/분석/anchor projection을 일치시켰다. GET 공유는 정확한 함수 인자 alias origin·생성 호출 ancestry·native 배치·단가가 증명된 경우만 허용한다. CP/FOUT 새 업로드와 derived/relocation 및 증명 불충분한 값은 공유하지 않는다. 새 값 생성 횟수는 보존한다.
- **통합 배경**: 원래 작업 트리는 다른 미커밋 비용·실험 변경을 포함했다. 별도 worktree에서 origin/main 3d0d683c1b 위에 이번 변경만 옮겼다. 함수 경계 patch는 적용 가능했지만 main에는 global GET grouping/ancestry API가 없어 필요한 부분만 이식했다. 업로드·latent/fused resolver 및 일반 비함수 다운로드는 기존 경로를 보존한다.
- **통합 중 검출/해결**: 초기 포트는 일반 변수까지 그룹화하여 기존 cost fingerprint 변화와 LogReg/GLM/SliceLine의 EXACT_VE_FACTOR_CELL_OVERFLOW를 일으켰다. 공유 대상을 증명된 function alias origin으로 한정하고 일반 factor/기존 provenance resolver를 보존하며, unresolved activation union에는 Boolean observation factorization을 사용한다. 후보를 제거하거나 비용을 임의로 축소하지 않는다.
- **수정 파일**: ExactPhysicalCostModel.java, OccurrenceExecutionFrequencyFacts.java, 함수 경계 placement5파일, 관련 alias/anchor tests 및 신규 ExactFunctionAliasGetCostTest, Docker 비교 runner와 진입점, FUNCTION_BOUNDARY_GET_SHARING_2026-10-06.md.
- **기존 snapshot 증거**: 동일 수정 비용 모델로 boundary A/B 14workloads×W1/W3=56compile-only 성공. 22조건은 배치·명령·목적값 동일,6조건 변경. main에 없는 미커밋 비용 변경이 포함된 snapshot의 결과이므로 main 통합 비용으로 일반화하지 않는다. 자세한 수치와 provenance는 위 상세 문서를 참고한다.
- **미해결 문제**: L2SVM W1에서 FOUT formal과 if 합류 equality가 결합해 CP 정규화 결과의 추가 업로드를 요구한다. 분기별 변환 후 합류를 표현하는 기능은 이번에 구현하지 않았다. 기존 local 계산/local 저장 조합이 제외되므로 무해한 plan-space 정리라고 주장하지 않는다.
- **잠재 회귀 위험/검증 원칙**: 서로 다른 값/호출의 GET을 합치거나, 일반 비용과 업로드 수명까지 바꾸는 위험. 생성 횟수·중첩·독립 origin 회귀, fresh upload 제외 조건 코드 리뷰와 기존 비용 golden을 유지하고 검증한다. 분산 실행/실측 성능/캐시 eviction은 검증 범위 밖이다.
- **의사결정 근거**: binding의 alias 계약과 MatrixObject 생성 수명에 비용을 맞춘다. 런타임 fallback, privacy 완화, recompile CP/FOUT 허용을 추가하지 않는다. if 문제는 별도 명시적 변환 표현으로 풀어야 한다.

- **통합 최종 검증**: Maven compile/test-compile 성공, 신규6 GET 회귀 및64-assignment 인코딩 동등성을 포함한120 tests PASS. 기존 main에서도 재현한 GLM/fingerprint 메서드2개를 제외하고 기록했으며 non-hermetic worker metadata fixture 클래스1개는 성공으로 계산하지 않았다. 고정한 최종 engine으로 frozen 실험 DML의14workloads×W1/W3 Docker compile-only28/28 PASS. production SHA 일치, 독립 리뷰 APPROVE, Python/shell/whitespace 검사 PASS. 상세 한계와 portable receipt는 FUNCTION_BOUNDARY_GET_SHARING_2026-10-06.md 및 docs/experiments/function-alias-main-20261006/validation.json.


## Remaining cost model — origin/main integration

- **상태**: 통합 및 검증 완료. 기준 main은 `904b4445c02d172d1293a7d266521a72447c592e`.
- **문제 정의**: R54–R60에서 구현한 연산량·통신·융합 입력 재사용·실험 profile 변경이 원래 작업 폴더에만 남아 있었다. 사용자가 해당 변경의 main 커밋/push를 요청했다.
- **해결**: 별도 최신 main worktree에서 ComputeCost, FederatedCostModel, PlacementCostSemantics, ExactPhysicalCostModel과 관련 회귀/실험 설정을 통합한다. CP/FED 공통 실행 비용, worker별 입력/출력량, 방향별 RPC stage 및 coordinator NIC 병목, 융합 이후 실제 연산/전송 소유권, 정확한 배치의 입력 재사용을 반영한다.
- **최신 main 보존**: materializedOutputAnchor/range 정렬 helper와 함수 alias 생성 문맥 및 bounded activation 분해를 보존한다. 원본 PlacementAnalysis/closure를 덮어쓰지 않는다. 원본에서 미추적이던 PlacementCostSizeBounds는 이미 main `535c52b1e6`에 동일하게 반영돼 있어 추가 변경하지 않는다.
- **수정 파일**: 핵심 Java 4개, 비용 회귀 테스트, `scripts/fedplanner`의 profile/calibration 및 비용 조건 전달 코드. 무관한 runtime/heuristic/plan-diff 작업은 제외한다.
- **통합 중 문제**: 공유 target에서 Maven을 동시에 실행한 초기 검증은 클래스 누락으로 무효화했다. 모든 보조 빌드를 중단하고 단일 clean test-compile 뒤 다시 검증한다. Docker fixture의 상대경로 code/scripts 의존성도 새 artifact root에 연결한다. STEP-LM의 max_features 인자는 main 기본 builtin에 없는 frozen campaign 확장이므로, 이전 비교와 동일한 frozen builtin을 해당 Docker fixture에서 사용한다. 이 builtin 확장은 이번 source commit에 포함하지 않는다. 이 준비 실패를 planner 동작 실패나 성공으로 계산하지 않는다.
- **의사결정 근거**: 실행 비용과 실제 데이터 이동을 맞추며 candidate legality/privacy/runtime fallback 규칙은 바꾸지 않는다.
- **잔여 이슈**: 기존 if 합류 배치 제약은 별도 문제로 남는다. compile-only 검증은 분산 실행 정확성이나 실측 성능 검증이 아니다. 자동 profile의 실제 원격 측정은 이번 publish 검증에 포함하지 않는다.
- **잠재 회귀 위험**: worker layout/응답 크기 추정, GET 재사용 수명, fused kernel 소유권, profile 계수 변경으로 선택 계획이 달라질 수 있다. 관련 Java/Python 회귀 및 고정 Docker 14 workloads × W1/W3 compile-only로 검증한다.

- **단위 검증**: 단일 `mvn -q -DskipTests -Dcheckstyle.skip -Drat.skip=true clean test-compile` 성공. 기존 비용 suite와 최신 loop-entry/function alias suite를 합친 53개 클래스에서 353 tests 실행, 352 PASS / 1 FAIL. 실패 `FederatedPlannerFallbackIntegrationTest.testDpPlansSteplmWithSameNamedFormalTransientBinding`의 `EXACT_VE_FACTOR_CELL_OVERFLOW`는 이전 main `904b4445c0`의 production/test classes 및 builtin에서도 동일하게 재현했다. 테스트를 삭제하거나 ignore하지 않는다. Python 관련 46 tests, shell syntax 및 diff whitespace 검사도 수행한다.
- **외부 profile 의존성**: sibling cofee-evaluation의 최소 공개 커밋은 `639496a6ab7f17b76d0ce0c2a24cf1b0eb669407`이며 현재 원격 `c9514557ba853bd1e5f131ccb463844cf9f37236`에 필요한 provider/probe/driver API가 포함돼 있음을 확인했다. 미공개 외부 변경에는 의존하지 않는다. 외부 checkout이 없는 독립 clone에서는 자동 campaign profiling 준비가 명시적으로 실패한다.

- **최종 결과**: Docker compile-only 14 workloads × W1/W3 = 28/28 PASS. Java352 PASS + 기존 main 재현 실패1, Python46 PASS. 핵심 비용 코드4파일 독립 리뷰 승인, Exact layout/activation 보존 검토 완료. 상세 소스 SHA·테스트 목록·fixture 한계는 [validation.json](experiments/cost-model-main-20261006/validation.json)에 기록했다. 원본 dirty worktree와 실행 target은 변경하지 않았다.


## 이동 비용 전수 감사 — 조사 완료, 발견한 비용 오류는 미수정

- **상태**: 현재 비용 발생 경로에 대한 감사 및 격리 재현 완료. 생산 코드 수정 없음.
- **환경/조건**: `/home/mchoi/w1357-cost-model-main-20261006`, HEAD `e1fdfe4446180ef9d6fbd93fd0c4f5ab899d7440`. DP/Exact 공통 physical cost surface, generic GET, native result, broadcast/slice, REFED/FOUT 및 function alias를 조사했다.
- **문제 정의/증상 1**: 함수에서 federated 행렬을 CP로 한 번 읽고 그 행렬을 그대로 반환하면, 호출자 CP read가 같은 MatrixObject의 로컬 사본을 재사용해도 비용은 GET을 다시 청구한다. Production hard factors를 만족하는 선택 상태에서 GET factor 2개, 각각 `1.001312255859375ms`, 합계 `2.00262451171875ms`를 재현했다. 강제한 유효 상태의 반례이며 무제약 최적해 선택을 주장하지 않는다.
- **원인 1**: `ExactPhysicalCostModel.runtimeMaterializationSources`는 transient/function input alias만 추적하고 function output alias를 원본에 연결하지 않는다. 반면 `FunctionCallCPInstruction`의 input/output binding과 `cpvar`는 동일 Data 객체를 유지한다.
- **문제 정의/증상 2**: contains, 비가중 central moment, FED+local covariance, WSLOSS/WCEMM, forced-local Spoof는 계산과 결과가 같은 runtime batch인데 base instruction RTT 뒤에 result RTT를 또 붙이는 경로가 있다. Payload 중복이 아니라 실행당 RTT 1회 과다 과금이다. Aligned covariance의 여러 batch에는 이 결론을 일반화하지 않는다.
- **원인 2**: `FederatedCostModel.nativeResultIsInBand`와 연산별 override의 runtime batch 분류 누락. Contains/moment/mixed covariance는 production 유효 선택 및 실제 FED instruction의 mock transport 요청 수로 확인했다. WSLOSS/WCEMM은 rule caps·비용 helper 실행·runtime 소스로 확인했고 Spoof는 rule/runtime/비용 소스 근거다.
- **업로드 판정**: B-01~B-22 fixture 및 반복 함수/서로 다른 if block의 stable local alias probe에서 유효 업로드 중복을 재현하지 못했다. Legacy function `callWeight × upload`, 서로 다른 producer의 alias 업로드, stable FEDFout 반복 캐시 비용은 도달 가능한 선택 반례가 없어 조건부 위험으로 남긴다. 버그 부재의 완전성 증명이 아니다. REFED cache hit도 새 worker alias 제어 RPC는 발생하므로 무조건 무료로 취급해서는 안 된다.
- **별도 발견**: VAR/일부 covariance/cumulative unary/CTABLE/reshape/forced-FOUT TSMM의 보조 runtime stage 누락과 MMChain 융합 후 source cost 잔존은 중복 GET과 분리해서 기록했다. MMChain의 선택 배치별 전송 중복액은 미확정이다.
- **해결/변경 요약**: 사용자 요청은 조사이므로 비용식, 후보 규칙, runtime은 수정하지 않았다. 격리 diagnostic과 전체 경로별 판정표를 `/home/mchoi/cost-transfer-audit-20261006/REPORT.md` 및 `downloads/REPORT.md`, `uploads/REPORT.md`, `native/REPORT.md`에 기록했다. 이 세션 문서에만 감사 결과를 추가한다.
- **검증/재현**: 전체 보고서에 Java probe 및 명령을 보존했다. Download 관련 23 tests, upload/runtime/function 34 tests, native 60 tests PASS. 집합 일부가 겹치므로 고유 테스트 수로 합산하지 않는다. Mock transport는 실제 instruction/UDF 요청 생성을 실행하지만 분산 Netty 실측 성능 검증은 아니다. 모든 shape/privacy/cache 상태를 완전 탐색한 것은 아니다.
- **의사결정 근거**: 유효 후보 도달 가능성, 같은 runtime 객체/생성 수명, 실제 batch 소유권을 각각 증명한다. 단순한 비용 helper와 cache 코드의 차이만으로 중복 버그를 확정하지 않는다. 후보 공간/함수 경계 제약은 변경하지 않는다.
- **잔여 버그**: function output GET 원본 추적 누락, 일부 native result의 RTT 중복은 남아 있다. 업로드 조건부 위험과 보조 stage 과소계상은 추가 유효 계획별 검증이 필요하다.
- **잠재 회귀 위험/감지**: 감사로 인한 실행 코드 회귀는 없다. 후속 수정에서 새 반환값이나 다른 호출의 값을 같은 alias로 합치거나 실제 별도 GET이 필요한 binary/rightIndex의 RTT를 제거하면 오계상한다. same-object return/새 객체 return/반복 호출 생성 문맥과 instruction batch 수 반례를 함께 검증해야 한다.


## 확인된 이동 중복 과금 수정 — 수정 및 검증 완료

- **문제 정의**: 위 감사에서 확인한 function return GET 재과금과 native result RTT 중복을 사용자가 수정 요청했다.
- **수정 범위/방법**: 반환 경계의 같은 runtime 값과 생성 호출 문맥을 추적해 GET을 공유하고, 실제 계산 batch에 결과가 포함되는 연산만 RTT 추가 청구를 제거한다. Candidate legality, alias 배치 규칙, runtime은 유지한다.
- **검증 계획**: 수정 전 실패하는 비용 회귀, 함수 새 값/반복 호출 반례, 별도 GET binary/rightIndex 보호, 실제 instruction 요청 batch 테스트를 추가한다. 이후 공통 비용/DP 관련 회귀와 Docker compile 검증을 수행한다.
- **잔여 이슈/위험**: 업로드 중복은 미확정이므로 이번 수정 대상으로 확정하지 않는다. 별도 보조 stage 누락과 MMChain 문제는 이번 두 결함 수정과 분리한다. 생성 문맥을 잘못 합치거나 실제 별도 GET RTT를 제거하는 오류를 회귀로 감지한다.

- **최종 변경/검증 결과**: function output의 생성 원본·호출 문맥을 추적하고 native response의 batch 소유권을 반영했다. 원래 반환 반례의 GET은 2→1회, 비용 2.00262451171875→1.001312255859375ms. Contains/mixed covariance 재현은 결과 비용의 RTT 10ms 중복이 제거됐다. 새 회귀 17개 포함 372 tests 중 371 PASS; 유일한 실패는 이전 main에서도 기록된 StepLM `EXACT_VE_FACTOR_CELL_OVERFLOW`다. Docker DP 14 workloads×W1/W3 28/28 compile PASS. 상세 변경·명령·잔여 한계는 [TRANSFER_COST_DEDUP_FIX_2026-10-06.md](TRANSFER_COST_DEDUP_FIX_2026-10-06.md) 참고.
- **수정 파일/안전성**: production `ExactPhysicalCostModel.java`, `FederatedCostModel.java`; 테스트 4개 파일. Candidate/runtime 변경 없음. 반환 문맥이 불명확하면 기존 개별 과금을 유지하고 latent WDivMM의 기존 원본 해석을 보존한다. 별도 반복 업로드 위험/보조 stage 누락/MMChain 및 기존 StepLM overflow는 미해결로 분리한다.


## DP 설명 보고서 — 작성 및 검산 완료

- **요청/범위**: 공유 HOP, 선형 구간의 조건별 요약, 변수 제거 DP, loop backedge의 배치 제약을 DML 예제와 그림으로 설명하는 한국어 보고서. 기준 코드는 게시된 `3d0d683c1bca099f004edf9105f5387369b4abea`이다.
- **산출물**: `dp-planner-explanation-20261006/REPORT.md`, 그림을 포함한 `report.html`, DML 예제 2개, DOT/SVG/PNG 그림 5개, 재생성 스크립트와 `validation.json`. 기존 Graphviz renderer를 재사용했다.
- **검증/수정**: 설명용 8개 선택 조합의 완전 열거와 단계별 DP 결과가 일치하며, 유일한 최적 선택은 `(C=F,A=L,B=F)`, 비용 11이다. 그림 5개의 한글과 배치를 확인했다. 검토에서 반복 비용과 일회성 비용의 분리, 경계 선택과 내부 연산 선택의 구분, 시간 예산이 엄격한 wall-clock 상한이 아니라는 설명을 보강했다.
- **검증 범위**: 비용과 그림은 교육용 모델이며 실제 컴파일 플랜이나 측정값이 아니다. Java planner 변경, DML 실행, 성능 측정은 수행하지 않았다. HTML의 그림 포함 여부는 검사했으며 전체 브라우저 레이아웃 검증은 포함하지 않았다.

## 논문용 제약 모델 문서 — 해결

- **문제/원인**: conflict를 다중 부모만으로 설명하면 다중 reaching definition과 loop backedge가 요구하는 저장·읽기 호환성을 빠뜨린다. 초기 계산의 배치와 변환 후 저장 배치를 구분하지 않으면 모든 반복값의 실행 위치가 같아야 한다는 오해도 생긴다.
- **해결/근거**: 공유 occurrence의 단일 선택, writer–reader 호환성, 경계 상태별 DP 요약을 구분했다. 루프는 L_0/E_0/S/E로 초기 진입과 반복 연결 조건을 따로 정의했다. 초기 비용, 별도 일회성 재사용 비용, 반복 비용을 분리하고 논문용 서술을 추가했다. 실제 선택 domain, transient compatibility factor, 변수 제거 구현을 근거로 하며 runtime 규칙은 변경하지 않았다.
- **수정 파일**: `dp-planner-explanation-20261006/PAPER_FORMULATION.md`와 `paper-formulation.html` 추가. 기존 `REPORT.md`에 연결을 추가하고 `build-report.py`를 두 문서의 HTML 생성에 재사용했다. 관련 HTML과 `validation.json`도 갱신했다.
- **검증**: 독립 읽기 검토에서 발견한 반복 인덱스와 Entry(S)의 진입 제약 및 일회성 비용 표기를 수정했다. 생성 스크립트 실행, 기존 8개 조합 검산, 두 문서의 원문 hash, Markdown/HTML 링크, 코드 경로와 줄 번호, SVG 포함 여부(기존 5개·신규 1개), Python 구문 검사를 통과했다.
- **잔여 이슈/잠재 회귀 위험**: T=0/1 특수화나 반복별 다른 플랜 및 모든 DML 후보의 완전성을 이 문서가 증명하지 않는다. 공유 일회성 비용이 숨은 내부 선택에 의존하면 경계를 확장해야 한다는 조건을 명시했다. planner 실행 동작 변경은 없으며, HTML 전체의 브라우저 렌더링은 검증 범위에 포함하지 않았다.

## Pruning 경로 점검 — 감사 완료 및 golden 불일치 미해결

- **문제 정의**: 후보 생성과 DP에서 불법 조합·비싼 후보를 얼마나 일찍 제거하는지 확인하고, incumbent와 하한에 의한 조합별 pruning 가능성을 검토했다. 이전 설명에는 solver의 exact quotient 전처리가 빠졌고, 이후에도 후보 생성 전 pruning과 incremental DP의 dense 열거를 분리할 필요가 있었다.
- **원인/확인**: privacy domain mask와 relocation prefix 검사는 이미 존재한다. 모든 tuple의 legality를 prefix에서 판단하는 일반 기능은 없으며, solver support/동등성 축약은 factor freeze 이후다. 일반 비용 dominance와 incremental incumbent cut은 없다. 원문 근거와 한계는 `PRUNING_AUDIT_2026-10-06.md`에 기록했다.
- **해결/변경 파일**: production과 기존 테스트는 변경하지 않고 audit 문서 및 `.omx/pruning-audit-20261006/` 진단 소스·결과·로그를 추가했다. 안전성 근거가 없는 후보 삭제나 기준 hash 갱신은 하지 않았다.
- **검증 결과**: 기존 테스트 66건 중 65건 통과, 실패 1건, 오류/제외 0건. 현재 reducer는 단일 변수 비용 `[7,10]`을 둘 다 유지하고 `[0,0,∞]`는 하나로 축약한다. ternary factor의 unsupported 값은 해당 전처리에 남는다. 별도 정수 모델에서 bound cut으로 4개 중 2개를 제외해도 최적값 4가 유지됐다. 이는 새 production pruning의 검증이나 성능 측정이 아니다.
- **잔여 실패**: `EarlyPrivacyPruningLegalSpaceParityTest.unknownShapeKeepsSafeAggregateAndCenteringAlternatives`의 과거 golden hash가 현재 snapshot과 다르다. 요구한 핵심 후보와 반복 컴파일 재현성은 통과했다. 추가 unknown-width early on/off 진단은 두 snapshot이 정확히 같음을 확인했다. 과거 golden과의 차이가 의도된 변화인지는 아직 미해결이다.
- **잠재 회귀 위험/적용 원칙**: 임시 loop/CFG bottom을 최종 불법으로 pruning하면 유효 후보를 잃을 수 있다. 비용 pruning은 외부 조건 전체에 대한 우월성 또는 안전한 하한이 필요하다. incumbent cut을 ∞ 처리만으로 넣으면 메시지 의미·lower bound·복원 경로가 깨질 수 있다. 코드 변경 없이 이 적용 조건과 필요한 검증 범위를 문서화했다.

## Pruning 단계별 구현 — 완료, 기존 golden 실패는 별도 유지

- **문제/원인**: 기존 CostBased root는 모든 비용 관찰이 완전히 같은 값만 합쳤고, support 전파는 unary/binary에 한정됐다. `mergeBoundary`는 이미 불가능하거나 같은 경계의 최선값을 개선할 수 없는 조합도 끝까지 합산했다.
- **결정 근거**: 중립 runtime legality를 비용 때문에 축소하지 않는다. solver 안에서 외부 조건이 구별할 수 없는 후보를 더 싼 대표로 매핑하고, 명시된 factor의 finite support 및 인증된 비용 하한으로만 조기 종료한다. 전역 incumbent를 이용한 경계 삭제는 현재 exact conditional-message 계약 밖이다.
- **변경**: CostBased root에 canonical/encoded 이중 합산 인증을 요구하는 unary dominance를 추가했다. Exact 준비 경로의 value-preserving quotient는 유지한다. n-ary support 고정점을 추가했다. DP 병합은 흡수적인 ∞ prefix 및 53-bit common-lattice 합산이 정확한 경우의 `partial >= boundaryBest`를 잘라낸다. 초기 seed는 대표로 매핑 후 기존 canonical 검증을 수행한다.
- **발견한 수치 문제/해결**: 정확한 실수 합 A<B인데 기존 Neumaier/DD 결과는 A>B인 6-unary 반례를 발견했다. 무조건 BigDecimal 최소 대표를 적용하지 않고 기존 dyadic certificate를 재사용했다. 인증되지 않은 모델의 기존 후보를 보존하며 반례는 회귀 테스트로 추가했다.
- **수정 파일**: `ExactPhysicalReducedSolver.java`, `ExactDyadicCosts.java`, `RegionalSearchProblem.java`, `IncrementalRegionalSeed.java`, `IncrementalRegionalOptimizer.java`, `ExactCategoricalSolver.java`; 새 `CostBasedPruningTest.java`, `BoundaryPruningTest.java`; 기존 seed/projection/resource 검사 fixture 3곳은 변경된 대표 선택 후에도 원래 검사 목적을 유지하도록 조정했다. 상세 보고서는 `PRUNING_IMPLEMENTATION_2026-10-06.md`다.
- **검증 결과**: 1단계 61건 통과, 2·3단계 초기 83건 통과, 최종 146건 중 145건 통과/기존 golden 실패 1건/오류·제외 0건. 작은 모델에서는 후보 6→3, cell 42→21 및 8→4를 확인했다. 무작위 모델의 유효 assignment 보존, lower/argmin parity, 혼합 크기 domain, 반올림 반례를 검사했다. 독립 correctness review와 diff 공백 검사도 통과했다.
- **실제 DML 비교**: PrivateAggregation ROW worker 2개, X=8×2인 공유 aggregate/identity loop/회귀 loop의 HOP 모델을 계획했다. 세 모델에서 전처리 후 후보 수와 cell 수의 추가 감소는 없었다. child 비용 조회는 identity loop 54,524→9,588(82.4%), 회귀 loop 8,852,780→3,069,965(65.3%)로 줄었다. 세 모델 모두 EXACT 종료, canonical 비용 유지, 복원된 물리 선택의 유효성 확인. worker 실행이나 wall-clock 성능 실험은 하지 않았다.
- **잔여 이슈**: 기존 unknown-width golden hash 실패는 변경 전과 동일한 observed hash로 재현된다. golden 교체/테스트 제외는 하지 않았다. zero-survivor privacy seed mask 제거, global-U 경계 pruning, raw factor cap 이전 축약은 미적용이다.
- **잠재 회귀 위험/감지**: certificate 조건 또는 경계 키를 약화하면 외부 공유 선택이나 반올림에 따른 최적값을 잃을 수 있다. 관련 반례 테스트 및 physical canonical 검증으로 감지한다. support/인증 순회 비용과 wall-clock 개선 여부는 실제 Docker 실험 전에는 보장하지 않는다.


## ML10 pruning ablation — 진행 중

- **증상**: 공용 multi-host Docker lane이 다른 캠페인으로 점유되어 있다. 기본 stage와 r63b stage는 최신 외부 renderer의 STEP-LM hash와 맞지 않아 실행 전 거부된다.
- **원인**: 기존 stage는 과거 STEP-LM 정의를 담고 있으며 최신 canonical 템플릿은 `maxi=10,max_features=2`이다. 기본 wrapper의 stage 경로가 최신 정의를 따라가지 않았다.
- **해결/근거**: 별도의 grid stage에 r63b를 복사하고 기존 `overlay_canonical_templates`와 `seal` 도구로 Git HEAD 검증을 통과한 canonical 템플릿을 적용했다. data attestation 2,216개는 원본과 완전히 동일함을 확인했다. 기존 stage/renderer/다른 캠페인은 수정하지 않았다. 새 `run_LAN_docker.sh --pruning-ablation` lane은 network-none coordinator에서 정규 full compile과 lowering audit를 실행한다. 이미 측정된 W3 WAN-Mid cost profile을 전 variant에 고정하며 실제 학습 실행은 하지 않는다.
- **수정 파일**: `scripts/fedplanner/run_pruning_ablation.py`, Docker wrapper, 해당 harness tests, `docs/PRUNING_ABLATION_2026-10-06.md`; Java experimental pruning gate 및 initial-U 제한은 별도로 검증한다.
- **검증**: 새 stage ML10 전체 pure render 성공, data hash 보존 및 cost profile 선택 검증 성공. harness 회귀 6건 통과. 실측은 Java 회귀/build 후 진행한다.
- **증거**: `/grid/3/cofee-lm-sweep-mchoi-20260914/pruning-ablation-20261006/stage-preparation.json`, `prepare-stage.py`, `prepare-stage.log`.
- **잔여 이슈**: full runtime 학습 결과/실제 네트워크 성능은 이 ablation 범위 밖이다. global 초기-U 값별 pruning 결과를 동적 joint-boundary pruning의 결과로 일반화할 수 없다.
- **잠재 회귀 위험/감지**: 실험 flag가 기본 solver 동작을 바꾸는 위험은 default parity/flag 회귀로 검사한다. 잘못된 결과를 latency에 섞는 위험은 manifest identity, full compile receipt, objective/plan/stop 비교, 실패 sample 분리로 검사한다.


### Ablation canary의 mount 및 STEP-LM 버전 불일치 — 해결 중

- **증상/원인**: Docker snap daemon에서 `/grid/3`가 보이지 않아 첫 canary는 컨테이너 시작 전 bind mount 실패했다. home 경로로 옮긴 L2SVM canary 5개는 모두 성공했다. 본 측정 STEP-LM은 `Named function call parameter 'max_features' does not exist`로 파싱에서 실패했다. 최신 실험 템플릿이 현재 engine builtin보다 새롭다.
- **해결**: 실행 mount/output은 별도 home 디렉터리를 사용하고 데이터는 read-only hardlink로 공간을 절약했다. canonical 템플릿/manifest의 hardlink는 교체 전에 끊었다. STEP-LM은 validation을 완화하거나 engine을 변경하지 않고, 원래 sealed stage와 Git `0dce65492201ad20057ce8b4015b3accd53d72ef`의 renderer를 사용한 별도 호환 cohort를 측정한다. 현 9개 ML workload의 본 측정과 실패 기록은 유지한다.
- **근거**: old/current stage의 data 2,216개 및 dependency JAR 316개 attestation이 동일하다. frozen renderer SHA `1fbad904e7f2d2051b56dc88a078e7c3a9ad9a5ab4a0708ba2c48188fc3a3c62`로 old stage ML10 모두 pure-render 검증 성공. 원래 STEP-LM은 `maxi=20`이며 `max_features`가 없다.
- **수정 파일**: `run_pruning_ablation.py`에 exact Git renderer revision 선택 및 identity 기록을 추가했다. 실행 중 본 측정은 이미 로드된 이전 runner를 사용하며 그 bytes는 `study-v1/frozen-harness`에 보존했다.
- **검증/잔여**: 수정 후 harness 6건 통과. STEP-LM 호환 Docker 측정은 본 측정 후 순차 실행한다. 실제 학습 runtime은 실행하지 않는다.
- **회귀 위험/감지**: 서로 다른 STEP-LM 정의의 결과를 섞을 위험은 별도 cohort/source hash/origin result로 방지한다. 처음 mount 실패는 성공/latency 집계에 넣지 않는다.

### Ablation 도중 외부 경로 및 작업 저장소 삭제 — 복구 완료

- **증상**: 첫 study 150개 중 95개가 성공했다. STEP-LM signature 불일치 10개 외에, 외부 `/home/mchoi/cofee-evaluation`이 삭제된 뒤 topology 재검증에서 45개가 실패했다. 이어 `/home/mchoi/w1357-loop-entry-main-20261006`도 다른 workspace 정리 작업에서 삭제되어 후속 실행 시작이 실패했다. planner 계산 실패나 timeout으로 분류하지 않는다.
- **해결**: 공유 삭제 경로를 다시 만들지 않고, 외부 Git `c9514557ba853bd1e5f131ccb463844cf9f37236`의 dependency를 실험 소유 `evaluation-snapshot`으로 export했다. study에 이미 동결된 renderer bytes를 유지했고 topology/profile validator SHA도 일치한다. STEP-LM의 호환 renderer는 별도 파일에 동결했다.
- **저장소 복구**: cleanup archive `workspace-cleanup-backups-20261006/round3/c5092297ca1f/uncommitted.tar.gz`를 같은 HEAD의 새 worktree `/home/mchoi/w1357-pruning-ablation-20261006/repository`에 overlay했다. 기존 모든 미커밋 작업을 보존했다. production source 2,175개, runner 및 JAR hash가 삭제 전 manifest와 정확히 일치한다. JAR bytes를 검증한 뒤 복구된 checkout의 mtime 차이만 반영했다. 새 engine build나 비용 모델 변경은 없다.
- **검증/재개**: 복구된 surefire receipt의 Java 67건은 모두 통과했다. harness 회귀 7건 통과. canonical workload index와 실제 반복 번호를 이용해 누락된 non-STEP r3 40개를 원래 key/순서 그대로 재개한다. 호환 STEP 15개는 별도 stratum으로 측정하며 원래 STEP 정의의 완료로 계산하지 않는다.
- **증거**: 실험 root의 `recovery-verification.json`, `recovery-r3-v1/ablation.json`, `steplm-compatible-v1/ablation.json`; 원래 실패 55개는 `study-v1`에서 보존한다. engine SHA `aeb0deda220ed7dc96cccd9661e96d6d7d2ca8658373d9ccb67a1e2477c007f6`.
- **회귀 위험/감지**: 다른 시점/정의의 결과를 섞을 위험은 source/command/renderer hash, 환경 동일성, sample key 및 workload별 rendered DML 비교로 검사한다. 실패 sample을 latency 집계에 포함하지 않는다. 후속 분석에서는 host 공유 및 시점 차이의 한계를 명시한다.

### ML pruning ablation — 최종 완료

- **측정 결과**: 기존 9개 workload×5설정×3반복 135개와 별도 STEP 호환군 15개 모두 정규 compile/lowering audit 통과. audit mismatch·missing·실행/dispatch는 모두 0이며 50개 조합의 플랜/목적함수는 반복 간 일치한다. 원래 실패 55개를 포함한 전체 205개 시도는 보존했다.
- **비교 방법**: 주 지표는 같은 workload·반복 번호의 27개 대응 시간비의 기하평균이다. 중앙값 비율은 기술 통계로 별도 표기한다. STEP 호환군은 주 9개 집계에서 제외하며 원래 STEP 정의의 완료로 대체하지 않는다.
- **관측**: global/local은 전체 초기 planning +1.39%, optimizer +27.00%. local/baseline은 전체 +0.03%, optimizer −4.48%. global의 선택 비용은 L2SVM −26.46%, GNMF +2.37%, 나머지 동일하다. GNMF는 변경된 탐색 경로에서 gap 2.959%로 종료했다. logreg/GLM은 최종 domain이 그대로인데 전역 준비 시간이 추가됐다. STEP 호환군의 global은 `UNSUPPORTED_CANONICAL`이므로 실제 전역 제한을 적용하지 않았다.
- **검증**: Java 67건, 실행/분석 harness 12건 통과. full raw aggregation, 셀·렌더링·환경 동일성, 중복 성공 거부, 실패 분리, bounds/선택 fingerprint 검증 완료. Python compile, shell syntax, diff 공백 검사 통과. 독립 구현/집계/보고서 리뷰와 최종 그림 검수 완료.
- **산출물**: `PRUNING_ABLATION_2026-10-06.md`, `pruning-ablation-20261006/{analysis.json,ablation.csv,tables.md,latency.png,latency.svg}`; 원시 보존본은 grid의 `pruning-ablation-20261006/raw-results`다. 동결된 JAR과 측정 중 production source는 변경하지 않았다.
- **구현 상태/범위**: global은 명시적 실험 옵션으로만 활성화하며 기본 비활성 상태다. 기존 local pruning은 탐색량을 줄였지만 전체 latency 개선은 확인하지 못했다. 3회 cold-JVM/shared-host 측정으로 유의성을 주장하지 않고 실제 training runtime으로 일반화하지 않는다. 기본 10초 cutoff를 유지한 production latency의 별도 실험도 아니다.

## 도움이 확인된 pruning만 기본 활성화 — 검증 완료, 기존 local 유지

- **요청/문제**: 사용자는 효과가 있는 기법만 사용하기를 요청했다. 기존 누적 ablation은 dominance→support→local 순서이므로 local 단독 효과를 직접 증명하지 않는다. 누적 결과만으로 세 기법을 모두 기본 활성화할 근거도 부족하다.
- **최소 변경 계획**: 기존 equality quotient, unary/binary support, privacy/runtime legality는 유지한다. 신규 dominance·다항 support·global은 명시적 실험 설정에서만 켜고, boundary prefix pruning만 켜는 `local_only`를 기본 후보로 검증한다. 과거 5개 ablation 이름과 기본 실험 순서는 보존한다. 새로운 일반 옵션 체계나 cost model 변경은 추가하지 않는다.
- **검증 계획**: 먼저 기본/명시적 local_only의 gate, reduced domain, boundary 값/lower/argmin 일치와 feature별 기존 회귀를 검증한다. 동일한 Docker image·CPU·JVM·WAN-Mid profile에서 baseline/local_only/기존 local 조합을 3회씩 비교한다. 9개 기존 workload를 주 비교군으로, 원래 호환 STEP-LM을 별도로 다룬다. 과거 JAR/원시 결과는 수정하지 않는다.
- **의사결정 근거**: local infinity-prefix 제거는 흡수원 성질만 사용하며 cost-prefix 제거는 실제 입력 message의 비음수·정확 합산 인증을 매번 검사한다. 선행 dominance나 다항 support에 의존하지 않는다. 변경 대상은 선택적 최적화의 기본값이며 runtime 지원/불법 조합 규칙은 바꾸지 않는다.
- **잠재 회귀 위험/감지**: reduction을 끄면 domain/탐색 순서가 달라 자원 제한에서 선택 품질이 달라질 수 있다. 작은 모델의 bounds/argmin 검증과 실제 workload의 비용·fingerprint·종료 이유를 함께 비교한다. 유의하지 않은 소폭 시간 차이를 확정적 개선으로 보고하지 않는다.
- **실측 결과**: baseline/local_only/기존 누적 local × ML9 및 별도 STEP 호환군 × 3회, 총 90/90 성공. ML9의 27개 대응 비율 기하평균으로 local_only/baseline은 전체 +1.56%, optimizer −8.98%; 누적 local/baseline은 전체 −1.15%, optimizer −8.83%; local_only/누적 local은 전체 +2.74%, optimizer −0.16%였다. local_only가 누적 local보다 전체 시간이 늘어난 workload는 6/9개다. 전처리를 추가로 끄는 기본값 변경을 뒷받침할 이득이 없어 기존 누적 local을 유지한다. 소폭 전체 차이를 전처리의 확정적인 인과 효과로 주장하지 않는다.
- **최종 변경**: 임시 local_only 기본값을 누적 `LOCAL`로 복구했다. dominance+추가 support+boundary prefix pruning은 기본 유지하고 global은 명시적인 실험 옵션으로만 켠다. `LOCAL_ONLY`도 비교용 명시적 옵션으로 보존한다. 이번 후속 작업의 production 파일 변경은 `PruningAblation.java` 하나뿐이며 최종 기본 gate는 이전과 같다. 실행 스크립트는 여섯 번째 실험 선택지를 허용하지만 과거 다섯 설정의 기본 순서는 바꾸지 않는다. 기본/명시적 local 동치 및 feature별 명시적 설정 테스트와 새 분석·보고서를 추가/갱신했다.
- **품질/증거**: 30개 workload/설정 조합의 선택 비용 bits·플랜 fingerprint는 반복 간 동일했고 세 설정 간 비용·플랜·종료 이유도 같다. 누적 local의 GNMF 하한만 baseline/local_only보다 1 ULP 낮아 bounds 일치는 ML9 24/27이다. upper/선택 비용 악화는 없다. lowering mismatch·missing·workload 실행은 0이다. 90개 image/CPU/memory/JVM/비용 환경·명령·렌더 결과를 검증했고 container 실행 구간은 겹치지 않았다. STEP 호환군은 ML9 결론에 합치지 않는다.
- **최종 검증**: 기본값 복구 후 새 Java 회귀 83건(실패·오류·제외 0) 및 package 성공, Python 실행/분석 테스트 13건 통과. 실제 결과/manifest의 hash와 집계 수치를 독립 재계산한 리뷰도 통과했다. 측정 JAR은 `2cfb6398b03eb844699f0b513c341a12d6ed8af7eb491a75e54e2e95158c4a55`, 최종 JAR은 `4ece87ebdfa652a4f40752f38cb63faca68af42f78cc127af7cf1632de406957`이며 production source 차이는 implicit 기본 반환값 한 줄이다. 측정은 모두 explicit variant이므로 그 세 경로는 변경되지 않았다.
- **산출물/잔여**: `PRUNING_DEFAULTS_2026-10-06.md`, `local-only-pruning-20261006/{analyze.py,analysis.json,stats.csv,tables.md,validation.json}`. 원시 자료는 `/home/mchoi/w1357-pruning-ablation-20261006/local-only-study-v1`이며 과거 연구 결과는 덮어쓰지 않았다. 3회 cold-JVM/shared-host 조건의 탐색적 측정이고 actual training runtime은 실행하지 않았다. 이전 unknown-shape golden 실패는 별도 미해결 상태다. 기본값 변경은 채택하지 않았으므로 선택 품질/순서의 새로운 기본 회귀는 도입하지 않는다.

## Local을 고정한 dominance/support 개별 ablation — 완료, 기본 local-only

- **요청/문제**: 사용자는 global을 폐기하고 local은 유지하되, L / D+L / S+L / D+S+L을 각각 비교하여 효과적인 기법만 반영하라고 명시했다. 앞선 L 대 D+S+L 비교는 dominance와 추가 n-ary support의 개별 기여를 식별하지 못하므로 두 기법을 함께 유지한 결정의 근거로 충분하지 않았다.
- **실험 계획/재사용**: `local-only-study-v1`의 L 및 D+S+L 60개 성공 결과를 그대로 사용한다. 기존 `support`는 D+S이며 S 단독이 아니므로 재해석하지 않는다. `dominance_local`과 `support_local`을 추가하여 동일 ML9+별도 STEP 호환군, WAN-Mid W3, Docker 설정에서 각 3회씩 빠진 60개를 측정한다. 기존 데이터·profile·renderer·코드·binary hash 및 원시 결과를 보존한다.
- **분석/판단 기준**: 같은 workload/반복 번호를 정렬하되, 과거/신규 측정이 서로 다른 시점의 cohort라는 사실을 명시한다. D의 조건부 효과는 DL/L 및 DSL/SL, S는 SL/L 및 DSL/DL로 계산한다. 두 수준의 평균 log effect도 별도로 제시한다. 선택 비용/플랜 유효성 비악화를 우선 확인하고 전체 planning 시간, optimizer 시간, 실제 domain/cell/조회 감소를 함께 평가한다. 작은 혼합 시간 차이를 확정 효과로 간주하거나 optimizer만 유리한 비교를 선택하지 않는다.
- **최소 구현/정리 계획**: 먼저 enum의 두 gate 조합만 추가하고 기존 6개 variant의 의미를 보존하여 측정 경로 변경을 최소화한다. 측정 완료 후 global 실험 경로·전용 수치 helper·테스트/계측의 참조를 확인해 더 이상 사용하지 않는 부분만 제거한다. 기존 regression으로 동작을 잠근 뒤 제거하며 local 및 선택된 D/S 기본값을 fresh test/build로 검증한다. privacy/runtime legality와 기존 unary/binary support는 유지한다.
- **잠재 회귀 위험/감지**: global 전용으로 보이는 helper가 local numeric certificate나 equality quotient에서도 사용될 수 있으므로 call-site를 확인한다. source/JAR 세대 차이와 시점 차이는 provenance에 남긴다. conditional/marginal 효과가 충돌하거나 효과가 작으면 불확실성을 그대로 보고한다. 정상 측정 표본을 유리한 표본으로 교체하지 않는다.
- **시점 차이 보정용 대조군**: 검토에서 old L/DSL 대 new DL/SL의 대각선 cohort 설계만으로는 현재 DSL에서 D/S를 각각 뺀 시간 효과와 호스트 시점 차이를 구별하기 어렵다는 점을 확인했다. 새 측정에 DSL 대조군 30개를 같은 회전 block으로 포함한다. 총 새 측정은 90개(미측정 조합 60+대조 30)이며 과거 60개는 계속 재사용한다. `new DSL/SL`, `new DSL/DL`은 같은 cohort 비교이고, `old DSL/new DSL`은 시점 차이의 관찰값이다. 모든 조합 전체를 다시 측정하지 않는다.
- **진행 검증**: 두 명시적 variant만 추가한 production 변경 후 Java 83건과 package 성공. 새 측정 JAR SHA `21c9f59473ff9fd11a09e438eab665241e1fa7a720346551887d2cc68839f2af`. 이전 source와 다른 production 파일은 `PruningAblation.java` 하나뿐이고 image/probe/dependency/CPU/JVM/비용 profile/renderer/stage는 동일하다. global 제거는 별도 scratch 패치로 준비하여 측정 중 live source/바이너리를 바꾸지 않는다. 분석기 4개 합성 회귀는 공통 cohort shift 취소, same-cohort conditional, 비용 악화 방향/크기, 누락 및 profile 변조 거부를 확인한다.

- **개별 효과 1차 완료**: 기존 L/DSL 60개 재사용 + 새 DL/SL 60개 + 새 DSL 대조 30개, 선택 150개 전부 비용 bits/플랜/종료 이유가 같다. 원시 두 cohort 180개를 검증했다. 같은 새 cohort에서 D|S는 전체 +0.91%, optimizer +9.05%이며 ML9 9/9개에서 optimizer가 느려졌다. D를 기본값에서 끄기로 했다. S|D는 전체 +2.72%, optimizer +1.15%로 개선 근거가 없으나, D를 끈 뒤의 SL/L은 다른 시점 비교라 별도 확인이 필요하다. STEP 호환군에서 D|S는 전체 −13.86%, optimizer −11.23%로 반대였으며 기본 ML9 집계와 분리한다. GLM DL r3의 analysis 증가 표본도 그대로 보존했다.
- **GLOBAL 제거 완료**: live 4개 Java/test 파일에서 GLOBAL enum/gate, 초기-U bound preparation/marginals/domain restriction, 전용 receipt 6필드와 테스트 4개를 제거했다. explicit global 거부 테스트를 추가했다. 공용 canonical 합산 인증, local certificate/seed/cost evaluation은 보존했다. runner의 global 선택지와 receipt 요구도 제거하고 기본 실험 순서를 L/DL/SL/DSL로 바꿨다. 과거 누적 ablation 분석기와 frozen harness는 역사적 결과 재현용으로 보존한다.
- **제거 검증/마지막 비교**: Java17 clean targeted test 80건과 package test 10건, Python runner/analyzer 19건 통과. target/JAR 내 삭제된 GlobalPreparation/FactorMarginal/GlobalBounds class가 0건임을 확인했다. global 없는 JAR `15fab31bdf9ed80279b8735a679ad5db511149ff5bd31ca590fc5fbe3c579ff9`로 L/SL × ML9+STEP 호환 × 1회 = 20개를 같은 시점에 측정한다. 전체를 다시 측정하지 않고 S|D=off의 시점 혼동 해소와 삭제 후 compile 검증을 겸한다. `support-bridge-v1`에 frozen source/runner/overlay를 보존한다.
- **최종 support 판단**: bridge 20/20 통과. SL/L은 ML9 전체 +6.276%, optimizer −0.417%, analysis +8.538%; 시간 감소는 전체 3/9개, optimizer 4/9개다. STEP 호환은 전체 +3.528%, optimizer +5.836%로 별도 보고한다. 비용 bits·플랜·종료 이유는 모든 비교가 일치하며 GNMF 하한만 1 ULP 차이다. shared-host/cold-JVM 1회차의 큰 analysis 변동을 support 자체의 인과 효과로 주장하지 않는다. 기존 원시/균형 효과와 함께 보아 일관된 latency 이득 근거가 없으므로 추가 S는 기본 비활성으로 정했다. 선택 결과 총 170개(재사용 60+신규 110), baseline 포함 원시 200개를 검증했다.
- **최종 반영**: implicit `LOCAL_ONLY`로 변경하여 D=false/S=false/L=true. D/S factorial 조합은 명시적 실험 옵션으로 보존한다. 기존 equality quotient, unary/binary support, privacy/runtime legality는 유지한다. GLOBAL은 코드/active runner에서 완전히 제거하며 explicit global은 오류로 거부한다. bridge JAR 대비 production 차이는 기본 반환값과 Javadoc 각 한 줄이며 explicit 측정 경로는 동일하다.
- **최종 검증**: 기본값 변경 뒤 Java 대상 80건, package 10건이 실패/오류/제외 없이 성공했다. 최종 JAR SHA `4894a32e4fbf0ff18f9a1c23f381884fd7d60160d6c978fa7b718af0764fd4ac`. Python 19건, compile/shell/diff 확인 성공. 분석기 실행 시 frozen matrix import 누락과 canonical workload 순서 불일치를 발견해, 원본 HEAD와 동일한 import 3개를 동결하고 runner의 원래 회전 순서를 그대로 검증하도록 수정했다. 이전 cohort 전체를 검증한 후 reference를 사용하는 방식과 import hash 증거도 보강했다. 독립 raw 재계산 및 경로 제거 리뷰 완료.
- **산출물/잔여/회귀 위험**: `PRUNING_FACTORIAL_2026-10-06.md`, `pruning-factorial-20261006/{analysis.json,stats.csv,bridge-analysis.json,bridge-pairs.csv}`. grid archive `factorial-study-v1`, `support-bridge-v1`, `factorial-delivery`에 기존 결과를 덮어쓰지 않고 보존한다. 기존 unknown-shape golden mismatch는 이번 변경과 별도 미해결이다. 실제 학습 runtime 및 기본 10초 cutoff 성능은 측정하지 않았다. D/S를 끄면 workload별 자원 제한 탐색 경로가 달라질 수 있어 비용/플랜/종료 이유의 후속 비교로 감지한다.

## Dominance와 추가 support 구현 삭제 및 main 반영 — 검증 완료

- **요청/문제**: 사용자는 D/S를 실험용 옵션으로도 남기지 말고 삭제한 뒤 커밋하여 origin/main에 푸시하라고 요청했다. 성능 판단은 앞선 결과를 재사용한다.
- **정리 계획**: 기존 local-only 회귀를 먼저 실행하여 동작을 고정한다. (1) unary-cost dominance와 추가 n-ary support의 구현·인자·전용 인증·테스트를 삭제한다. (2) baseline/local_only만 active ablation 옵션으로 남기고 D/S 및 과거 DSL 명칭은 거부한다. (3) 기존 equality quotient, unary/binary support와 local prefix pruning을 보존하는 회귀를 검증한다. (4) 원시 실험/보고서는 역사적 근거로 유지하고 현재 구현 설명을 수정한다. 새 추상화나 의존성은 추가하지 않는다.
- **통합 계획/환경**: 현재 작업 HEAD는 `3d0d683c1b`이며 origin/main에는 비용 모델 관련 3개 커밋이 추가되어 `10e14bc791`이다. 최신 main의 변경을 보존해 통합하고 최종 source에서 재검증한다. force push는 사용하지 않는다. 기존 비관련 작업은 보존한다.
- **검증 계획**: 대상 Java/실행 harness 테스트와 clean package, Docker wrapper를 통한 삭제 후 실제 compile 확인, source/JAR의 제거 심볼 검사, diff 검토 후 명시적 main ref로 push 및 원격 commit 확인.
- **의사결정 근거/위험**: 후보 legality/비용 모델을 바꾸지 않는 전처리 삭제다. local numeric certificate나 기존 exact reducer가 공유하는 helper를 잘못 삭제할 위험은 호출부 검사와 기존 quotient/seed/boundary 회귀로 감지한다. 최근 main 비용 모델 변경 때문에 과거 비용 수치를 현재 값과 무조건 같다고 요구하지 않으며, 동 세대 baseline/local-only의 plan/cost 및 lowering 유효성을 확인한다.

- **구현 완료**: D/S gate와 unary-cost 최소 대표 선택, n-ary support pass, canonicalSumCertified 및 certifyFactors를 제거했다. Exact reducer와 seed는 기존 value-preserving quotient 구현으로 복구했다. L을 유지하는 certifyTables/53-bit nonnegative-prefix 인증은 남겼다. active runner와 Java enum은 baseline/local_only만 허용하며 과거 DSL 이름 local도 오류로 거부한다. D 때문에 변경했던 기존 seed/optimizer 테스트 fixture를 원래 계약으로 복원했다.
- **독립 검토/계측 정리**: receipt 전용 rawValues/rawCells의 전수 계산을 explicit ablation 분기 안으로 옮겨 일반 실행의 scan을 제거했다. lowerMinMarginals는 실행 경로에 없는 package-private test accessor이며, local pruning 전후 경계별 하한 보존을 검사하므로 유지했다.
- **main 통합**: 최신 `10e14bc791` 위로 rebase했다. SESSION 문서의 양쪽 추가 내용을 모두 보존하고 Docker wrapper에 pruning/function-boundary/transport/campaign 진입점을 함께 유지했다. production source 충돌은 없었다.
- **확장 검증에서 발견한 문제**: 통합 후 clean test 82개 중 81개 통과, trace schema 검사 1개가 이미 삭제한 separateGlobalCalls 필드를 기대했다. production/consumer 참조가 없는 폐기된 계측 필드임을 확인하고 해당 테스트의 키 목록·oracle 문자열만 현재 스키마로 수정했다. numeric round-trip/locale 검사 및 나머지 필드 검증은 그대로다. 최종 재검증/패키지/Docker 결과는 아래에 기록한다.

- **최종 검증 결과**: 최신 main 통합 후 대상 Java 82개와 package 검사 10개가 실패/오류/제외 없이 통과했다. Python 실행·분석 회귀 19개, Python compile, shell syntax, diff 검사도 통과했다. 독립 코드 검토 승인. Docker wrapper로 logreg/GNMF × baseline/local_only 4개 정규 compile/lowering audit를 실행해 전부 성공했고, 동 workload의 비용 bits·플랜 fingerprint·lower/upper·종료 이유가 동일했다. 결과는 새로운 성능 비교로 해석하지 않는다.
- **증거/산출물**: `docs/pruning-factorial-20261006/removal-validation.json`, home 실험 root의 `pruning-delete-main-tests-final.log`, `pruning-delete-main-package.log`, `pruning-deletion-main-v1/`. 최종 JAR SHA `58c889c77fb9d6dcd2ba54659a9410b608303d1f594b542eca7447239f594dc1`. clean build의 enum은 BASELINE/LOCAL_ONLY만 포함하고 삭제된 GLOBAL 내부 class는 없다. 측정 시점의 production source와 최종 source hash가 동일하다. 기존 ablation 원시 자료와 독립적인 새 보존본을 생성한다.
- **잔여 이슈/위험**: 기존 unknown-shape golden mismatch는 별도 미해결로 유지한다. 실제 training runtime은 실행하지 않았다. 이번 삭제는 기본 local-only의 동작을 유지하며, 더 이상 지원하지 않는 D/S/global/과거 local property를 사용한 외부 명령은 명시적 설정 오류를 받는다. 원래 의미를 바꿔 조용히 실행하지 않는다.

## Unknown-shape golden 불일치 — 원인 조사 기록 (후속 테스트 수정은 아래 참조)

- **요청/범위**: `EarlyPrivacyPruningLegalSpaceParityTest.unknownShapeKeepsSafeAggregateAndCenteringAlternatives`의 기존 불일치 원인만 조사했다. production 코드, 테스트 및 golden은 변경하지 않았다. 아래 결과가 앞선 항목들의 “golden 원인 미해결” 상태를 대체한다. 테스트 기준의 갱신은 이번 범위에 포함하지 않는다.
- **환경/조건**: Java 17, Maven 3.9.7. DML은 ROW worker 2개의 `A`에 `PrivateAggregation`을 부여하고 `colMean=colMeans(A); X=A-colMean; print(sum(X));`를 분석한다. HOP 생성 후 source `A`의 열 크기만 `-1`로 설정한다. worker를 실행하지 않는 기존 unit fixture이며 runtime/성능 실험은 수행하지 않았다.
- **관측 증상 — Evidence**: 기대 SHA256은 `2bded4649153d1e1f4542c78d3e5862c19e3fa6fb7c52a00f96fad499050d3d0`, 관측값은 `ff0e870b4afd1d7ebb1708ea4b0c21fa99345dd369d91d1b8f5a65d2e68451b7`다. 해시 검사에 앞선 unknown source width, aggregate `FED/LOUT/ROW`, centering `FED/FOUT/ROW`, 반복 컴파일의 결정성 검사는 통과했다.

| 독립 checkout | 대상 테스트 | 전체 snapshot 해시 |
| --- | --- | --- |
| 변경 직전 `adaebee9cc` | 1건 통과 | `2bded464…` — 기존 golden과 동일 |
| 바로 다음 `3d0d683c1b` | golden 비교 1건 실패 | `ff0e870b…` |
| 현재 `58145e7366` | golden 비교 1건 실패 | `ff0e870b…` — 변경 직후와 원문 전체 동일 |

- **원인 — Evidence**: `3d0d683c1b` (`fix(fedplanner): allow one-time loop entry materialization`)의 `PlacementRelationClosure.bindDerivedFoutRealizations` 변경이다. derived FOUT 출력의 durable anchor ID를 `native-output:<producer occurrence>`에서 `materialized-output:SHA256(action.normalizedSignature())`로 바꿨다. 이 공용 처리는 루프가 없는 해당 fixture에도 적용된다. 테스트 파일과 `semanticSnapshot` 구현은 세 checkout에서 byte 단위로 동일하다.
- **정확한 차이 — Evidence**: 52줄 snapshot 중 41, 43번째 AVAILABLE 행만 다르다. 하나는 `colMean`의 derived `FED/FOUT/BROADCAST` realization, 다른 하나는 `X=A-colMean`이 그 realization을 참조하는 input binding이다. 두 위치의 동일 anchor ID와 이를 포함하는 직렬화 길이 prefix만 바뀌었다. 새 ID는 `materialized-output:182437269d052d1054f017c135a2a5cc6b6b5bc82b7d0bade415804154931fae`다.
- **후보 보존 — Evidence**: NODE 22개, AVAILABLE rule row 23개, relocation 6개, derived FOUT action 1개가 모두 동일하다. 노드별 실행·출력·FType·shape 조건, rule key, worker/range, emission, support/proof 및 action은 anchor 이름의 일대일 대응을 제외하면 같으며 순서도 같다. 진단 스크립트가 **그 ID 하나와 두 참조만 치환하고 중첩 길이를 재계산했을 때, 변경 전 snapshot 전체가 변경 후 snapshot과 byte 단위로 같음**을 assert한다. 후보 수만 비교하거나 다른 필드를 삭제한 검사가 아니다.
- **물리 배치 — Evidence**: 해당 anchor는 BROADCAST이며 두 worker `localhost:1234/X1`, `localhost:1235/X2` 모두 `[0,0] → [1,2]`를 유지한다. fixture는 source 폭만 뒤늦게 unknown으로 만들므로 `colMean`의 알려진 출력 geometry가 남는다. `PlacementIdentity.samePhysicalLayout`은 FType와 partitions로 비교하고 placement ID는 비교하지 않는다.
- **pruning 대조 — Evidence**: 기존 `UnknownWidthPruningProbe`를 재사용해 세 revision 각각 early pruning on/off를 실행했다. 각 revision에서 on/off snapshot 전체가 동일하다. 따라서 이 fixture의 불일치를 early privacy pruning이나 이후 D/S/global/local 변경으로 설명할 근거는 없다. 같은 커밋의 binary BROADCAST 규칙 확장도 이 snapshot에 후보를 추가·삭제하지 않았다.
- **판정 — Inference, 높은 확신**: 이 fixture에서는 합법 후보 집합의 축소/확장이 아니라 **materialization 식별자 체계 변경에 golden이 뒤따르지 않은 것**이다. action별 ID는 같은 물리 배치를 만들더라도 업로드를 허용하는 anchor owner·scope·source 등 선택 근거가 다른 작업을 구별한다. 단순히 모든 ID를 무시하도록 golden 검사를 약화하면 다른 회귀를 놓칠 수 있으므로, 이번 이름 치환은 원인 검증용으로만 사용했다.
- **의사결정 근거/수정 파일**: oracle, planner, runtime 및 golden을 수정하지 않았다. 이 문서에 원인·재현 근거만 추가했다. 진단 소스·snapshot·로그는 ignored `.omx/unknown-shape-golden-20261006/`에 보존했다.

재현 명령은 각 revision의 checkout에서 동일하다.

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  /home/hadoop/apache-maven-3.9.7/bin/mvn test \
  -Djacoco.skip=true -Dtest-forkCount=1 -Dtest-perCoreThreadCount=false \
  '-Dtest=EarlyPrivacyPruningLegalSpaceParityTest#unknownShapeKeepsSafeAggregateAndCenteringAlternatives'

python3 .omx/unknown-shape-golden-20261006/compare_snapshots.py
```

- **검증 자료**: [비교 결과](../.omx/unknown-shape-golden-20261006/comparison.json), [검증 스크립트](../.omx/unknown-shape-golden-20261006/compare_snapshots.py), [원문 diff](../.omx/unknown-shape-golden-20261006/before-current.diff), [revision/fixture SHA](../.omx/unknown-shape-golden-20261006/revisions.json). 같은 디렉터리의 `before/after/current`에 early on/off 전체 snapshot과 probe 로그를 보존했다. `before-loop-entry-test.log`, `after-loop-entry-test.log`, `current-test.log`가 fresh Maven 결과다. 역사적 checkout은 `/home/mchoi/w1357-unknown-shape-audit-20261006/{before-loop-entry,after-loop-entry}`에 있다.
- **잔여 이슈/한계**: 원인 조사는 완료했으나 기존 golden을 유지했으므로 현재 테스트의 hash assertion 실패는 그대로다. 이 fixture의 후보 보존을 입증한 것이며 모든 unknown-shape workload 또는 runtime 실행의 완전성을 주장하지 않는다.
- **잠재 회귀 위험/감지**: production 변경이 없어 이번 조사로 실행 동작의 회귀를 만들지 않는다. 이후 테스트 기준을 정비할 때는 단순 hash 교체 외에 action별 identity 구별과 정확한 support 참조의 보존을 검증해야 한다. 이번 진단의 특정 ID 치환을 일반적인 ID 무시 규칙으로 사용하지 않는다.

### Unknown-shape golden 및 구조 검증 보강 — 해결

- **요청/문제**: 원인 확인 후 사용자가 안전한 테스트 수정을 요청했다. 원인 조사에서 입증한 현재 식별자 체계를 반영하면서, 단순 hash 교체가 후보나 action authority 손실을 가리지 않도록 한다.
- **변경/근거**: unknown-width golden 하나만 검증된 `ff0e870b…`로 갱신하고, 변경을 도입한 `3d0d683c1b` 및 직전 비교 revision을 주석에 기록했다. 다른 세 golden과 `semanticSnapshot` 구현은 그대로다. ID 정규화/제거, 예외 무시, 테스트 제외를 추가하지 않았다.
- **구조 검증**: `colMean`의 단일 derived upload, ROW 실행, 작업 signature에 결합된 출력 ID, 두 worker의 정확한 BROADCAST 1×2 범위, 모든 support clause의 해당 작업 proof를 검사한다. centering의 오른쪽 입력은 정확한 uploaded realization과 producer value version을 참조하고 BROADCAST relocation을 사용해야 한다. 기존 same-row LOUT source 검사도 이 fixture에 적용했다.
- **pruning 검증**: 별도의 동일 DML 분석에서 early privacy pruning을 끄고, 기본 분석과 전체 semantic snapshot의 byte 동일성을 검사한다. 기존 재컴파일 결정성 및 full golden 검사를 함께 유지했다.
- **검사 작성 중 확인**: 첫 assertion은 centering의 입력 경로를 DIRECT로 가정해 실패했다. 실제 support clause를 추출해 LOUT-origin relocation과 derived-FOUT-origin relocation이 함께 존재함을 확인했다. derived-FOUT 경로에 대한 검사를 실제 RELOCATION 계약과 exact source authority로 수정했다. production 후보나 경로는 바꾸지 않았다.
- **최종 검증**: Java 17 Maven test에서 `EarlyPrivacyPruningLegalSpaceParityTest` 4건 및 `EarlyPrivacyGenerationWorkTest`, `CandidatePrivacyInputPruningTest`, `DerivedFoutMaterializationAuthorityTest`, `PlacementRealizationAuthorityTest`, `MaterializedOutputLayoutTest`를 포함해 **31건 통과, 실패/오류/제외 0**. 독립 diff 검토와 `git diff --check`도 통과했다.
- **음성 대조**: 수정된 테스트 class만 이전 `adaebee9cc`의 production/test classpath 위에 올려 JUnit으로 실행했다. 다른 3건은 통과하고 unknown-width 1건은 **golden 비교 전** 새 action-ID assertion에서 예상대로 실패했다. 실행에는 Maven과 동일하게 `--add-modules=jdk.incubator.vector`가 필요하며 이를 빠뜨린 첫 진단 실행의 환경 오류는 보완 후 재검증했다. 이 대조는 과거 구현이 새 식별자 계약을 만족하지 않음을 검사하며, 과거 구현 자체가 잘못됐다고 주장하는 것은 아니다.
- **수정 파일/의사결정 근거**: `src/test/java/org/apache/sysds/hops/fedplanner/placement/EarlyPrivacyPruningLegalSpaceParityTest.java`와 이 문서. 원인은 snapshot이 기록하는 identity 계약의 의도된 변경이므로 테스트만 보강했다. production oracle/planner/runtime 및 비용 모델 변경은 없다.
- **재현/증거**: 앞선 Maven 명령의 `-Dtest`에 위 6개 클래스명을 쉼표로 연결한다. [검증 결과](../.omx/unknown-shape-golden-20261006/test-fix/verification.json), [31건 실행 로그](../.omx/unknown-shape-golden-20261006/test-fix/golden-test-fix-final.log), [이전 구현 음성 대조](../.omx/unknown-shape-golden-20261006/test-fix/golden-test-legacy-negative-control-final.log). 이전 원인 조사 snapshot과 로그는 그대로 보존했다.
- **잔여 이슈/회귀 위험**: 요청된 golden 불일치는 해결됐다. 이 검증은 해당 fixture와 관련 unit 계약을 대상으로 하며 전체 ML training runtime 검증을 대신하지 않는다. 이후 식별자 계약의 의도된 변경도 테스트 실패를 일으키므로, 다시 전체 snapshot 차이를 확인한 뒤 기준을 갱신해야 한다. 명시적인 worker/range·proof·참조·pruning parity 검사로 후보 손실을 감지한다.

## 보조 실행 단계·MMChain·업로드 중복 비용 후속 점검 — 점검 및 확인된 결함 수정 완료

- **범위/환경**: 사용자 요청으로 `d57bca99d9`에서 분리한 `/home/mchoi/w1357-cost-followup-20261006`, `fix/cost-followup-20261006`에서 작업한다. DP/Exact 공통 비용 모델을 점검하며 후보 legality/privacy/runtime 계약은 변경하지 않는다. 기존 compiler-only PUBLIC 비용 fixture 예외 문서를 따른다.
- **계획**: 실제 instruction mock-transport 계약으로 보조 batch 수와 payload를 고정하고 비용 회귀를 추가한다. 공통 모델에서 확인된 누락을 수정한다. MMChain은 전체 feasible assignment를 적용한 뒤 실제 Lop 융합 여부를 확인하여 비용 소유권을 검증한다. 업로드 중복은 기존 B01–B22와 alias/cache 검증을 재사용하고 추가 대조로 재현 여부를 판정한다. targeted/인접 회귀 후 Docker wrapper로 compile/lowering을 확인한다.
- **초기 증거**: VAR 2 batches, aligned covariance 3/weighted 4, ROW cumulative 3/sum-product 4, reshape metadata PUT 1 추가 batch, CTABLE dimension discovery 각 FED operand당 1 batch를 실제 instruction 경로에서 확인했다. MMChain은 FED fixture에서는 explicit kernels이나 별도 CP-only feasible plan에서는 실제 융합되므로 조건부 비용 소유권이 필요하다. 업로드 중복은 현재 bounded audit에서 재현되지 않았다.
- **수정 원칙/위험**: 실제 전송을 빼거나 compiler가 유지한 중간 kernel을 지우지 않는다. 결과 GET이 main batch에 포함되는 경우 보조 단계와 구분해 RTT를 중복 과금하지 않는다. worker 수는 병렬 fanout이므로 RTT 배수가 아니다. geometry/sparsity와 data cache에 의존하는 크기/재사용 추정의 한계를 별도로 기록한다.

- **수정 파일/해결**: `FederatedCostModel.java`에서 VAR/COV/ROW cumulative/CTABLE dimension/reshape의 보조 RTT·payload·compute를 기존 공통 primitive로 과금한다. aligned COV의 main GET은 worker별 scalar와 in-band 계약으로 정정했다. `PlacementCostSemantics.java`는 실제 lowering과 CP/LOUT 고정성을 확인한 local MMChain의 exclusive kernel만 fused owner로 넘기며 공유 노드는 보존한다. 후보 legality/runtime/EPC factor 구조를 수정하지 않는다. 새 수량·runtime 요청·MMChain 회귀와 기존 결과-batch 테스트를 보강했다.
- **검증 중간 결과**: Maven package 성공. 최종 production source에 대해 65개 클래스, **447개 테스트 중 446개 통과/1개 기존 실패**다. 실패는 `FederatedPlannerFallbackIntegrationTest.testDpPlansSteplmWithSameNamedFormalTransientBinding`의 `EXACT_VE_FACTOR_CELL_OVERFLOW`로, 수정 전 `d57bca99d9` compiled classes에 동일 메서드만 실행해 같은 오류를 새로 재현했다. 이전 별도 비용 수정의 372개 테스트 기록과도 일치한다. 이 solver 문제는 이번 변경에서 수정하거나 테스트 제외하지 않았다.
- **업로드 대조**: 최종 classes로 기존 probe 3개를 재컴파일·재실행했다. B01–B22의 formal relocation/upload 및 반복 CP/FOUT/선택 emission 지표 모두 0이다. 반복 함수와 cross-block stable alias도 선택 업로드 0이다. loop-entry 및 runtime cache/alias 회귀는 최종 447개 suite에 포함된다. 따라서 확인되지 않은 중복 비용을 제거하지 않는다.
- **수정하지 않은 경로/잔여 이슈**: 강제 FOUT TSMM은 현재 oracle의 native 실행이 아니므로 비용을 추가하지 않았다. native FOUT CTABLE 후처리는 검사한 fixture의 Exact domain에 실현 가능한 선택이 없어 추측성 EPC 변경을 제거했다(전체 불가능성 증명은 아님). two-FED CTABLE의 secondary collect/rebroadcast는 coordinator cache와 기존 materialization 소유권을 구분해야 하므로 별도 미해결로 기록한다. source-level CTABLE FOUT range/type 및 weights-only 경로 문제도 runtime 이슈로 분리한다.
- **잠재 회귀 위험/감지**: 중간 kernel을 공유하는 경우의 과소계상, runtime에서 실행하지 않는 branch 과금, ROW 보정의 geometry/sparsity 오판이 위험이다. shared/fusion-disabled/FED negative control, actual request batch mock, unknown shape·worker skew·dense correction 수량 테스트로 검사한다. 기존 byte fallback/CPU·network calibration에 의존하므로 이 수정으로 실측 latency 정확성을 보장하지 않는다.
- **문서/증거**: `docs/COST_MODEL_FOLLOWUP_2026-10-06.md`, `docs/MMCHAIN_COST_REACHABILITY_2026-10-06.md`, `docs/AUXILIARY_COST_REACHABILITY_2026-10-06.md`. 로그·probe·실행 명령은 `/home/mchoi/cost-followup-20261006/`에 보존한다. Docker 최종 결과는 아래에 누적한다.

- **최종 Docker/일치성 검증**: `bash scripts/fedplanner/run_LAN_docker.sh --function-boundary-compare --artifact-root /home/mchoi/cost-followup-20261006/docker --variant B --jobs 2`로 14개 workload × W1/W3 **28/28 compile/lowering 성공**. 실제 training runtime/latency 측정이 아니다. 테스트 시작 시 저장한 두 production 파일 SHA-256과 최종 파일이 동일하고 `git diff --check` 통과했다. [검증 요약](experiments/cost-followup-20261006/validation.json)에 base commit, 파일/JAR SHA, test·Docker·probe 결과와 잔여 범위를 보존한다.

## Two-FED CTABLE 수집·재업로드 비용 후속 구현 — 해결 및 검증 완료

- **요청/범위**: 남은 two-FED CTABLE 비용을 이어서 수정한다. 기존 worktree와 미커밋 변경을 보존한다. source privacy 및 compiler-only 비용 fixture 예외를 유지하고, 후보 축소나 runtime fallback으로 해결하지 않는다.
- **계획**: cold/warm secondary input의 실제 cache 재사용 계약을 추가 테스트로 고정한다. 전체 제약을 만족하는 CTABLE 플랜과 lowering을 확인한다. WDivMM에 이미 있는 runtime-input GET 수집/alias/relocation 소유권을 재사용하고, native sliced PUT은 연산 실행마다 과금한다. local/relocated/shared/repeated/weights 대조와 기존 비용 회귀 후 Docker compile/lowering을 검증한다.
- **추가 발견**: `nativeLocalTargetCost`가 `mixed.hasInputPreparation()` 전체를 논리 입력 upload의 소유권으로 간주한다. 앞선 auxiliary 비용은 이 조건을 만족하면서 실제 logical input PUT을 포함하지 않는 COV/CTABLE 경로가 있어 upload를 숨길 수 있다. 보조 작업 존재와 해당 입력 전송 소유권을 구분하는 회귀를 먼저 추가하고 수정한다.
- **위험/검증 원칙**: direct FOUT과 relocation-created MatrixObject의 cache 수명을 섞지 않고 기존 materialization activation에 합류시킨다. aligned weights는 재사용하며 실제 rebroadcast가 있는 경우만 PUT을 더한다. 변경 전 증거는 `/home/mchoi/cost-followup-20261006/`에 유지하고 후속 자료는 `ctable-followup/`에 쌓는다.

- **중간 구현/도달성 증거**: 동일 worker/range의 두 FED 입력으로 CTABLE LOUT를 전체 hard+cost solve하고 실제 `FED°ctable…°LOUT` 및 `CtableFEDInstruction` lowering을 확인했다. DIRECT_FOUT authority를 바꾸지 않고 기존 runtime-input GET demand와 MatrixObject 생성/alias activation에 연결했다. PUT·slice·강제 LOUT에서도 수행하는 min/max scan은 연산별 auxiliary 비용이다. local COV weights PUT 누락은 operand별 upload 소유권 predicate로 수정했고 ROW slicing을 scalar output shape와 분리했다.
- **RED/GREEN 증거**: 변경 전 local COV weights의 기대 PUT `0.06103515625`가 `0.0`으로 사라지는 것을 재현했고, 수정 후 CTABLE/COV 및 인접 WDivMM/network 42건이 통과했다. runtime cold/warm/alias/distinct MatrixObject와 aligned/nonaligned weights 계약도 추가했다. 리뷰에서 secondary dimension의 독립 응답 수와 singleton broadcast의 slice 복사 부재를 확인해 두 추가 RED regression으로 잠근 뒤 수정했다. 전체 후속 검증은 아직 진행 중이다.
- **선택 플랜·반복·공유 검증**: `ExactCtableMaterializationCostTest`의 전체 hard+cost solve 4건이 통과했다. source의 FED/FOUT/ROW 상태와 실제 FED CTABLE LOUT lowering, 하나의 cold GET 가격, 동일 worker의 별도 MatrixObject 두 개에 대한 각각의 GET, T=1/2/10에서 GET 고정 및 연산별 비용 비례를 확인했다. CP 공유 검증은 CTABLE에 닿는 key만 세면 별도 CP GET을 놓칠 수 있으므로, 같은 S value의 모든 CTABLE/TR/TW/CP download contribution을 합산하여 정확히 한 GET인지 확인하도록 보강했다. 원래 base classes를 이용한 음성 대조에서는 CTABLE 수집 key 누락으로 4건 중 3건이 예상대로 실패한다. 나머지 공유 예제는 CP 소비자가 원래 GET을 유발하므로 base에서도 통과한다.
- **최종 검증**: Maven package 성공. 67개 클래스의 460건(459 통과/기존 StepLM 실패 1건) + 전체 CTABLE 선택/공유/반복/lowering 4건 통과, 합계 **68개 클래스 464건 중 463건 통과**다. 이번 후속 추가 17건 모두 통과했다. Docker wrapper에서 14개 workload × W1/W3 **28/28 compile/lowering 성공**. 실제 분산 training latency는 측정하지 않았다. build 직전 저장한 production 3파일의 SHA와 최종 source가 일치하고 `git diff --check` 통과했다. 최신 증거는 `docs/experiments/cost-followup-20261006/ctable-validation.json`이다.
- **수정 파일/의사결정 근거**: FCM의 CTABLE prep·입력별 upload 소유권, PCS의 runtime collect/alignment 의미, EPC의 기존 materialization GET 수집기를 수정했다. 새 일반 cache 계층이나 후보 상태는 만들지 않고 WDivMM에서 이미 쓰는 수명·alias·relocated-object 처리를 재사용했다. 비용 누락이므로 oracle/privacy/runtime 계약은 바꾸지 않는다. 회귀는 `CtableInputPreparationCostTest`, `ExactAuxiliaryNativeInputUploadTest`, `ExactCtableMaterializationCostTest`, `AuxiliaryStageBatchContractTest`에 있다.
- **잔여/위험**: 이번 요청의 two-FED CTABLE 수집·재업로드 누락은 해결했다. 기존 StepLM factor-cell overflow는 별개로 남는다. 검사한 fixture에서 CTABLE relocation/native FOUT 전체 feasible witness가 없으므로 해당 경로의 실증을 주장하지 않는다. 기존의 cache eviction 및 unknown-shape byte fallback 추정 한계는 유지하며, 실제 request 계약과 선택 assignment별 비용·반복/공유 회귀로 누락/중복을 감지한다.

## StepLM FACTOR_CELL_OVERFLOW 원인 분석 — 직접 원인 확인, solver 수정 미실시

- **요청/범위**: 기존 실패가 왜 생기는지 사용자 질문에 대해 분석했다. production/test source와 target classes는 변경하지 않았고, 외부 artifact root에 진단 출력만 추가한 class overlay를 만들어 동일 JUnit 메서드를 실행했다.
- **재현**: `FederatedPlannerFallbackIntegrationTest.testDpPlansSteplmWithSameNamedFormalTransientBinding`; 입력은 X=20×5, y=20×1이다. `/home/mchoi/cost-followup-20261006/steplm-diagnosis/`의 `final-probe.log`, `counts.json`, overlay source와 `SingleMethodTest.java`가 증거다. shared preparation의 resource-error catch는 실행되지 않는다. 따라서 분석 중 검토했던 “shared root의 자원 한도 초과” 가설은 이 재현의 원인이 아니다.
- **실제 진입 원인**: shared encoded root는 정상 생성된다. hard-conflict repair의 임시 assignment에서 block 외부 함수 입력 y가 shared reduced domain에 없는 값을 사용한다. 관측된 원래 변수는 454(`linear_regression` input y), 113(`m_lmCG` input y), 선택값은 둘 다 0이다. `SharedRegionalPreparation.prepareReduced`의 `root.reducedValue(...) < 0` 분기가 `null`을 반환한다.
- **오류 전파**: `FactorizedBlockSolver`는 `null`을 받으면 original decision/canonical factor만 갖는 Local context로 전환한다. 비용 모델에는 이 materialization activation union의 Boolean `SolverFactorization`이 이미 있지만, 이 경로의 `reduceFactor`는 이를 전달하지 않는다. 원래 19변수 canonical factor가 외부 변수 고정 후 18변수로 남는다. X/X_orig의 Fed source, TRead/TWrite, StepLM 98/132/160/163행의 rix 선택들이 포함된다.
- **정량 증거**: domain 크기는 `[1,2,4,2,17,3,3,5,3,5,3,17,3,17,3,17,3,1]`, 곱은 **73,064,170,800**이다. lazy factor1061에 대해 `freezeInputs → validateInputs → checkedCells`가 `Integer.MAX_VALUE=2,147,483,647` 한도를 검사하여 실제 table 할당/평가 전에 실패한다. 이 검사는 해당 factor의 arc consistency/domain 축소보다 앞선다. compact 실패 후 noncompact 재시도도 같은 입력 검사에서 실패한다.
- **수정 방향(아직 미구현)**: unsupported 외부 boundary를 명시적으로 repair 대상으로 반환하고 block에 포함해 기존 encoded factorization으로 재시도하는 방향이 타당하다. 필요한 auxiliary 변수·관계를 함께 보존하고 결과를 original decision prefix로 projection해야 한다. factor 목록만 치환하거나 cap만 올리는 수정은 안전한 해결책이 아니다. hard-conflict 상태를 그대로 완료 처리하지 않는다.
- **잔여/검증 위험**: 정확한 repair block 확장/종료 및 자원 제한 계약은 구현 후 별도 회귀가 필요하다. 특정 y 후보가 root에서 제외된 개별 hard constraint까지는 이번 직접 overflow 원인 분석 범위에서 추적하지 않았다. 후보/privacy/runtime 제약 변경은 없으며 원래 StepLM 테스트 실패는 유지된다.

## StepLM 복구의 factor 분해 보존 및 materialization 전 합법성 축소 — 해결 및 검증 완료

- **요청/문제**: 사용자가 두 개선을 모두 구현하도록 요청했다. hard repair 중 unsupported boundary 때문에 encoded factorization을 버리는 경로와, 기존 unary/binary support 축소 전에 전체 table 크기를 검사하는 순서를 수정한다.
- **최소 변경 계획**: unsupported original decision을 명시적으로 전달해 repair block을 확장하고 기존 encoded solver로 다시 푼다. caller가 확장된 원래 변수의 assignment와 incident hard/cost를 함께 갱신해야 한다. 기존 unary/binary support만 materialization 앞으로 옮기며 quotient와 원래 값으로의 projection을 보존한다. 삭제한 선택적 dominance·다항 support·global pruning은 복원하지 않는다.
- **검증 계획**: 기존 StepLM RED 증거를 재사용하고 작은 repair/경계 회귀를 먼저 추가한다. 큰 raw Cartesian product가 합법성 축소 뒤 작아지는 사례와 작은 전수 열거 대조로 legal optimum 보존을 검증한다. malformed input 검증, true infeasible/resource limit, compact/plain, source-value projection, 기존 planner/cost suite와 Docker compile/lowering을 확인한다.
- **의사결정 근거**: planner의 문제 표현 및 정확한 불가능성 판정 순서만 수정한다. runtime·privacy·후보 허용 규칙을 바꾸거나 비용이 비싼 합법 후보를 임의로 제외하지 않는다.
- **잠재 회귀 위험/감지**: block 확장 시 새 hard 관계/auxiliary 누락, reduction 전후 값 매핑 오류, 잘못된 입력의 검증 누락, 불필요한 준비 비용 증가를 회귀와 기존 workload 결과로 검사한다. StepLM 통과와 전체 latency 개선은 별개의 주장으로 취급한다.
- **첫 수정 검증**: hard repair가 unsupported original boundary를 명시적으로 받아 free block에 추가하고, incident hard/cost 및 auxiliary closure를 다시 준비하도록 변경했다. canonical fallback 통계를 늘리거나 assignment를 그대로 성공 처리하지 않는다. 실제 shared model의 두 conflict component를 비용 factor로 연결한 회귀를 먼저 추가하여 변경 전 실패를 확인했다. repair-only overlay에서 compact/plain·경계 캐시·전수 oracle 등 35건 통과, 기존 StepLM 통합 테스트도 기대값 변경 없이 통과했다. 증거: `/home/mchoi/cost-followup-20261006/steplm-fix/repair/{red,targeted,steplm}.log`. 두 번째 축소 순서 변경과 통합 검증은 진행 중이다.
- **두 번째 수정/검증**: raw scope 및 dense 입력 검증 → unary table 준비/기존 AC → 지원되는 값으로 제한 → binary table 준비/기존 AC → 제한된 나머지 factor 준비 → 기존 quotient/solve 순서로 변경했다. 50,000×50,000 binary와 ×2 ternary의 raw 25억/50억 셀은 각각 1/2회 평가로 해결된다. 일반 조합 합산의 local prefix pruning도 그대로 유지한다. 이미 할당된 dense table에는 원래 자원 한도를 적용하고, 제한 후에도 큰 lazy table은 기존 한도로 거부한다.
- **검토 보강**: 축소된 인덱스와 원래 값의 인덱스를 분리하고 tie cost는 모든 원래 값에서 검증/캐시한다. 동일 Factor 객체의 여러 occurrence도 각각 frozen snapshot을 유지하며, 축소되지 않은 scope는 기존 변수/factor/table을 재사용한다. raw dense 한도·잘못된 값은 lazy callback 전에 거부한다. 80개 random model의 기존 oracle 테스트가 reduced solver의 잘못된 infeasible을 정상으로 오인하지 않도록 expected solve와 actual solve의 예외 처리를 분리했다. 별도 리뷰의 두 correctness 지적(dense budget, duplicate occurrence)을 수정한 뒤 승인받았다.
- **통합 검증 진행**: 중간 통합 solver 13개 클래스 148건 통과, 후속 validation 보강의 독립 solver 54건 통과. 최종 source로 Maven package 성공했으며, 85개 클래스와 Docker 14 workload×W1/W3를 검증 중이다. 구성·명령·source hash는 `/home/mchoi/cost-followup-20261006/steplm-fix/`에 보존한다. 실제 training latency 개선을 주장하지 않는다.
- **최종 검증**: 최종 Maven package 성공. 85개 클래스 **637/637 테스트 통과**, 기존 `testDpPlansSteplmWithSameNamedFormalTransientBinding`도 기대값 변경/제외 없이 통과했다. Docker wrapper에서 14 workload×W1/W3 **28/28 compile/lowering 성공**, 모든 receipt의 planning 성공·executeScript 반환·runtime 미실행과 고정 image를 확인했다. build 직전 production 7개 파일의 SHA와 최종 source가 일치하고 `git diff --check` 통과했다. 이전 미커밋 비용 모델 수정도 전체 suite에 함께 검증했다. [기계 판독 검증 기록](experiments/cost-followup-20261006/steplm-validation.json)에 source/test/JAR SHA, 명령과 원시 로그 경로를 보존한다.
- **수정 파일**: production은 `LocalCategoricalOptimizer.java`, `SharedRegionalPreparation.java`, `ExactPhysicalReducedSolver.java`, `ExactCategoricalSolver.java`다. 회귀는 `LocalCategoricalOptimizerTest`, `SharedRegionalPreparationTest`, `SharedRegionalCompactionParityTest`, `ExactPhysicalReducedSolverTest`에 추가/갱신했다. 별도 옵션·dependency·runtime fallback·oracle 허용 규칙은 추가하지 않았다.
- **잔여/범위**: 이번 StepLM unsupported-boundary overflow와 축소 전 큰 lazy table 검사는 해결했다. 본질적으로 큰 separator나 진짜 resource-limit 상황의 기존 fallback 정책은 유지한다. 임의의 그래프에서 모든 조합 폭발을 없앤다는 뜻이 아니다. 원래 dense table은 기존 input cap을 지키며, conceptual lazy product만 실제 materialization 전에 줄어들 수 있다. source-value/tie mapping 및 feasible optimum 보존은 전수 oracle/seeded random/compact/plain 회귀로 검사했고, 실제 분산 학습 시간의 개선은 측정하지 않았다.

## Shared root 축소와 hard repair의 domain 정렬 — 구현 및 검증 완료

- **요청**: 무조건 축소만 복구에서 재사용하고, 경계 고정에 의존한 축소는 경계가 풀릴 때 다시 계산한다. StepLM에 한정한 예외 대신 구조적으로 두 경로를 정렬하며 실제 탐색량을 측정한다.
- **확인된 근거**: `RegionalSearchProblem.reducedRoot`는 고정된 encoded variables/factors/limits만 입력받으며 현재 assignment를 고정하지 않는다. 이 root의 finite-support 제거는 해당 문제의 모든 유효 해에 적용된다. root의 exact quotient는 불가능성 제거와 다르며, 동등한 원래 값들은 `reducedValue >= 0`으로 계속 지원된다. `SharedRegionalPreparation.prepareReduced`의 경계 slice 및 compact의 추가 reduction은 조건부다. slice cache는 고정 값과 free marker(-1)를 모두 비교하므로 기존 캐시에서 잘못된 조건부 축소 누출은 확인되지 않았다.
- **최소 변경 계획**: assignment 인자를 받지 않는 `unconditionalDomains` 계약으로 root에서 지원되는 모든 원래 값을 초기 선택·직접 탐색·canonical conditional fallback에 전달한다. exact quotient의 다른 유효 원래 값은 임의로 버리지 않는다. 조건부 모델은 매번 immutable root에서 현재 block/free pattern/경계를 적용해 재구축하며, 조건부 reduction을 root에 저장하지 않는다. 선택 factor의 경계를 먼저 검사해 unsupported original들을 한꺼번에 확장하고 이후 table을 만든다. 추가 conditional compiled-cache나 provenance 그래프는 현재 필요가 입증되지 않아 만들지 않는다.
- **회귀/검증 계획**: 초기 후보 축소 RED, 조건부 값 복원(경계 값 변경 및 fixed→free), 다중 경계 확장, noncontiguous 원래 값 mapping, auxiliary closure, compact/plain 및 작은 전수 oracle를 고정한다. 기존 StepLM과 전체 cost/planner suite를 유지한다. 기존 class/source를 별도 baseline에 동결한 뒤 동일 DML/config/image에서 `run_LAN_docker.sh`로 대표 workload의 후보·repair·solver 작업량을 비교한다.
- **의사결정 근거**: 전체 문제에서 유한한 해에 참여할 수 없다는 기존 unary/binary support 증명만 재사용한다. root를 만들 때 포함된 고정 정책/forced constraint는 그 root의 수명 동안 유지되는 전제다. 임시 boundary를 고정하여 만든 조건부 결과는 다른 block에 재사용하지 않는다. dominance/global/n-ary pruning과 runtime/oracle 정책은 추가하지 않는다.
- **잠재 회귀 위험/감지**: quotient를 불가능성으로 오인해 원래 상태를 버리는 오류, 조건부 domain의 영구화, fallback의 원래 값 projection 누락, 큰 block 확대와 준비 비용 증가를 직접 테스트 및 동결 baseline 비교로 감지한다. 탐색량과 latency를 구분하며 측정 없는 성능 개선 주장은 하지 않는다.
- **구현 완료**: `LocalCategoricalOptimizer`가 immutable root의 지원되는 원래 값 목록을 직접 block 탐색·factorwise 검사·original-factor 재시도에서 공유한다. 초기 greedy seed 선택은 아래 비교 실험 후 기존 domain/대표 선택을 유지했다. noncontiguous domain의 좌표는 factor 평가와 결과에서 원래 값으로 되돌린다. `SharedRegionalPreparation`은 quotient alias도 지원값으로 남기고, table 생성 전에 선택 factor의 unsupported 경계를 모두 보고한다. hard repair가 이 경계들을 추가한 뒤 root부터 다시 준비한다. 조건부 reduction은 기존처럼 해당 solver에만 속한다.
- **회귀/검토 완료**: 후보 열거 9→5를 검사하는 새 회귀는 수정 전 class에서 9로 실패하고 수정 후 통과했다. resource 재시도의 원래 값 projection, 경계 값 변경/fixed→free, 조건부 infeasible 확장, prepared solver 불변성, auxiliary closure, 다중 경계 보고 및 작은 seeded 모델의 전수 oracle 대조를 추가했다. 독립 검토에서 정확성 차단 이슈는 없었다. Java 17 Maven package 성공 후 최종 classes로 **86개 클래스 645건 통과, 실패 0**; 기존 StepLM과 compact/plain 회귀도 포함한다. source SHA가 빌드 시작 시점과 동일하고 `git diff --check`도 통과했다.
- **설계/증거**: [설계 및 검증 보고서](SHARED_REDUCTION_REPAIR_2026-10-06.md). 새 작업의 baseline·RED/GREEN·최종 로그는 `/home/mchoi/cost-followup-20261006/shared-repair-reuse/`에 보존한다. 기존 비용/StepLM 변경을 포함한 baseline과 이번 두 production 파일의 delta를 별도로 기록했다.
- **최초 A/B 실험과 범위 수정**: 4 workload × W1/W3의 16개 Docker compile/lowering이 모두 성공했다. seed까지 축소하면 후보 합계는 757,506→748,963으로 줄지만 StepLM W3의 기존 hard repair 2,188 assignments가 없어지면서 최종 anytime 비용이 1.543% 높아지고 incremental assignments가 33.99% 늘었다. 유효 최적해 손실이 아니라 초기 선택/repair 경로 변경이며, 성능 개선으로 채택하지 않았다. `selectLocalState`의 기존 전체 domain/대표 선택만 복원하고 실제 repair의 지원값 재사용은 유지한다. 이 수정의 focused 43건이 통과했고, 작은 fixture에서 seed 9개를 유지하면서 block assignments는 plain 5→2, compact 2→0이다. 제외한 구현·실험은 `seed-reduced-rejected/`에 보존하며 같은 A를 재사용해 최종 B를 다시 비교한다.
- **최종 검증/판정**: 최종 source로 Maven package 및 **86개 클래스 645건**을 다시 통과했다. 최종 B의 Docker 8건도 모두 성공했다. 같은 A8과 비교해 8/8에서 objective certificate·선택 상태·종료 단계·upper/lower/gap·초기 후보·incremental 작업량이 동일하고, repair가 발생하는 두 조건의 solver-reported block assignments와 비교 가능한 factor cell 수도 같다. 최종 구현은 대표 workload의 추가 작업량 감소를 보이지 않았다. root의 지원값 8,543개 제거 결과를 실제 repair/direct/factorwise/fallback 경로에 공유하는 구조적 개선과 작은 회귀의 탐색 감소만 주장한다. training runtime 또는 latency 향상을 주장하지 않는다.
- **최종 위험/한계 및 기록**: 초기 선택 정책은 유지하지만 root 준비는 앞당겨진다. production은 이후 incremental 진입 시 같은 cached root를 반드시 사용하므로 bootstrap trace의 rootBuilds 0→1은 전체 root 구성 증가의 증거가 아니다. 추가 lazy Context나 pruning 모드를 도입하지 않았다. 진짜 resource-limit fallback 정책은 유지한다. source SHA 일치·독립 검토·diff 검사, 재사용 소비를 끈 음성 대조(비용 평가 2회 기대/3회 관측 실패), 전체 회귀와 최종 비교를 [보고서](SHARED_REDUCTION_REPAIR_2026-10-06.md) 및 `docs/experiments/shared-repair-reuse-20261006/`에 기록한다.
