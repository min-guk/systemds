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
