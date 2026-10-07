# Session issues: 2026-10-06

## Invariant FOUT staging의 logical lifetime 보존 — 해결

- **환경**: `/home/mchoi/w1357-derived-supply-sharing-20261006`, 기준 `eb64f9c939708735940f2ae095c5c8bd526decf7`과 이 worktree의 앞선 derived sharing 구현. 기존 다른 workspace/실험은 보존한다.
- **문제 정의/증상**: invariant FOUT 원본도 새 local staging MatrixObject 때문에 반복마다 REFED upload가 실행/과금됐다. 아래 과거 derived-sharing 보고서의 FOUT staging N× 설명은 수정 전 동작이다.
- **원인**: 비용의 FOUT 전용 fresh-stage 분류와 PREFETCH output을 owner로 삼는 runtime cache가 원본 source/version 수명을 끊었다. 추가로 FED map 변경이 local mutationVersion을 바꾸지 않고 이미 collect한 `_data`를 남길 수 있다.
- **해결 방향/근거**: exact selected staging을 명시적 `REFED_STAGED`로 묶어 원본 Lop/MatrixObject를 직접 입력으로 유지한다. 같은 creation profile로 비용과 공유 group을 도출한다. Runtime은 원본 version·FED map/remote ID·target layout/FType를 구분하고 변경된 FED read의 local cache를 무효화한다. 일반 candidate legality/privacy/TW/TR/function binding 규칙은 유지한다.
- **연결 보완**: selected native LOUT→derived FOUT 뒤에 다른 pool의 REFED가 오는 경우 중간 Lop의 LOUT 표시는 최종 source authority가 아니다. 이를 거부하는 guard 대신 선택된 FOUT materializer 결과를 staged REFED에 연결한다.
- **수정 파일**: `ExactPhysicalCostModel`, `FederatedRefed`, `Dag`, `FEDRefedInstruction`, `PlannerRuntimePlacementAudit`, `FederationUtils`, `CacheableData` 및 관련 회귀/실 worker proof.
- **검증**: 원본 identity/cache 관련 33 tests에서 baseline 7 failures → 수정본 33/33. Global/Local 동일 production surface의 canonical recost/selected lifetime/receipt 검증 통과. 통합 38 classes / 327 cases 중 323 통과, 기존 skip 4, 실패/오류 0. GET/PUT을 구분하도록 테스트 oracle을 고쳤으며 protected 비용의 구조/numeric bits는 유지되고 descriptor rename에 따른 fingerprint만 갱신했다. Docker model proof 12/12, DML E2E 12/12 기대 결과 충족, 원본/검증 class·source inventory 일치. 실제 3회 실행에서 invariant FOUT GET/PUT 1/1, updated FOUT 3/3 및 수치 결과/alias cleanup 통과.
- **잠재 회귀 위험/감지**: 변경된 remote ID가 이전 local bytes를 재업로드하거나, source와 target의 layout identity를 혼동할 위험. 생성 도중 mutation 거부, cleanup-disabled 함수 alias의 stale cache, nnz 확정, emitted stage 재파싱 및 Docker PUT/수치 결과로 검사한다.
- **잔여 사항**: source lifetime 단위 해제는 유지하며 exact last-consumer 해제는 추가하지 않는다. 임의의 외부 remote-ID in-place 덮어쓰기를 감지하는 새로운 분산 mutation protocol은 범위 밖이다. 상세 설계/최종 증거는 `INVARIANT_FOUT_SHARING_2026-10-06_KO.md`와 `.omx/invariant-fout-sharing/`에 기록했다.

## Local 및 Global DP 공통 모델과 그룹 충돌 처리 분석 완료

- **상태**: 보고서 작성 및 대상 단위 검증 완료.
- **문제 정의 및 증상**: 사용자가 최신 로컬 SystemDS의 공통 HOP 탐색 공간, Local/Global DP 알고리즘, Local의 그룹 간 충돌 해소 원리를 요청했다. 기본 `/home/mchoi/systemds`는 구형 체크아웃이므로 분석 기준을 혼동할 수 있었다.
- **환경 및 원인 확인**: 사용자가 지정한 `/home/mchoi/w1357-cost-model-main-20261006`, HEAD `e1fdfe4446180ef9d6fbd93fd0c4f5ab899d7440`을 기준으로 생산 호출 경로를 추적했다. 현재 Local의 본체는 feasible seed 이후 incremental boundary-message DP이며, 과거 local conflict repair 설명만으로는 부족하다.
- **해결 방법**: 공통 physical alternative와 hard/cost factors, Global variable elimination, Local seed repair와 factor cluster 병합을 구분하고, 공유 pivot 전체 bucket 병합 및 조건부 복원을 수식과 가상 비용 예시로 문서화했다.
- **수정 파일**: `docs/FEDPLANNER_LOCAL_GLOBAL_DP_REPORT_2026-10-06_KO.md`, 이 세션 기록. Java 소스 및 테스트 변경 없음.
- **검증 방법 및 결과**: `mvn -o -DskipTests=false -Dtest=IncrementalBoundaryMessageTest,IncrementalRegionalOptimizerTest,LocalPhysicalOptimizerIncrementalTraceTest test` 실행. 30 tests, 0 failures, 0 errors, 0 skipped. 새 Surefire 보고서 확인. 기존 비용 campaign 로그와 구별했다.
- **의사결정 근거**: 사용자가 지정한 로컬 커밋의 실제 생산 경로와 테스트를 기준으로 하고, canonical 모델의 정확성과 실측 runtime 성능을 구별한다.
- **잔여 이슈**: 이번 대상 테스트는 전체 workload의 완전성이나 실측 성능을 검증하지 않는다. 기존 세션에 기록된 별도 결함은 수정하거나 해소됐다고 주장하지 않는다.
- **잠재 회귀 위험 및 감지**: 실행 코드 수정에 따른 회귀는 없다. 후속 커밋에서 알고리즘·기본값이 변경되면 보고서가 오래될 수 있으므로 문서의 고정 커밋과 Factory/LocalPhysicalOptimizer 진입점을 대조한다.

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

## Remaining planner work after transfer deduplication — 최종 검증 완료

- **환경**: `/home/mchoi/w1357-cost-model-main-20261006`, baseline `10e14bc791d391a678ecab67ffd9f98949be32c6`; DP-LocalConflict 우선.
- **문제 정의**: 사용자가 남은 비용 누락, MMChain 감사, StepLM 오류, if 합류 제약, 실제 Docker 검증을 모두 요청했다. 작업 범위와 체크리스트는 `FEDPLANNER_REMAINING_WORK_2026-10-06.md`에 기록한다.
- **통신 비용 원인/해결**: 기본 FED instruction 및 LOUT result 비용 밖에서 실행되는 VAR mean, covariance means/weight sum, cumulative correction, CTABLE maxima, reshape metadata batch가 누락됐다. runtime별 추가 RTT/payload helper와 occurrence shape 전달을 추가했다. 실제 native result와 같은 batch인 GET을 다시 과금하지 않는다.
- **StepLM 원인/해결**: shared exact reduction은 성공했지만 hard-conflict repair가 이미 제거된 incumbent boundary category를 고정하여 원래 거대 lazy factor로 돌아갔다. unsupported boundary 원본 변수만 repair block에 포함하여 기존 encoded root에서 해결한다. 후보를 삭제하거나 cap을 높이지 않는다. isolated StepLM 및 solver 56건 통과; 통합 검증 진행 중.
- **if 합류 원인/해결 방향**: CFG-only alias edge에는 이동 명령을 붙일 수 없다. immutable analysis 이전에 분기 끝의 실제 TR→TW carrier를 만들어 그 input edge에서 LOCAL/REFED를 선택하게 한다. 합류 TW/TR와 일반 함수 binding equality는 유지한다. recompile clone marker, liveness, privacy, lowering 검증 진행 중.
- **MMChain/TSMM 감사**: 과거 probe는 root 한 개만 FED로 강제했고 complete feasible selection을 증명하지 않았다. final physical normalization 및 selected fusion boundary를 거치면 같은 instruction이 생성된다고 보장할 수 없다. forced-FOUT TSMM pattern에 일괄 비용을 추가하는 방식은 unfused 연산을 잘못 과금하므로 적용하지 않는다. 실제 DP runtime witness를 확인한다.
- **수정 파일**: `FederatedCostModel.java`, `PlacementCostSemantics.java`, `ExactPhysicalCostModel.java`, `LocalCategoricalOptimizer.java`, `SharedRegionalPreparation.java`, branch normalization 및 관련 테스트/검증 harness.
- **검증**: 초기 Maven production/test compile 성공. 나머지 로그는 `/home/mchoi/fedplanner-remaining-20261006/`; 최종 결과는 후속 항목에 갱신한다.
- **의사결정 근거**: runtime의 실제 batch/placement를 비용화하고, carrier의 명시적 이동만 허용한다. runtime fallback, TW/TR CP/FOUT, 후보 임의 축소는 허용하지 않는다.
- **잔여 이슈**: 통합 회귀, all14 W1/W3 compile, 실제 Docker worker 실행 및 예상/측정 구분이 미완료다. 업로드 중복 의심은 여전히 현재 feasible plan에서 재현되지 않았다.
- **잠재 회귀 위험/감지**: 보조 payload의 deferred dimensions, batch 중복, branch carrier 제거/잘못된 liveness, 공유 solver 보조변수 연결 누락. focused arithmetic tests, feasibility/lowering, 실제 true/false 및 반복 실행, all14 compile로 확인한다.


### 실제 Docker에서 추가 재현된 함수 경계 문제 — 수정/검증 진행 중

- **증상 1**: 같은 실제 행렬을 같은 함수 formal에 두 번 전달하면 `Exact logical function input fact is missing or ambiguous`. `runtime/run-ix2x1zx3/control.log`에서 분석 publication 단계 재현.
- **원인 1**: `(sourceArgument,targetRead,logicalPosition)`만으로 lookup하여 서로 다른 호출 boundary를 합쳤다.
- **해결 1**: boundary와 callInputPosition을 함께 검증하는 occurrence-exact lookup을 추가하고 physical consumer 및 비용 모델의 fact 검증에 사용한다. 호출별 실행 횟수와 컨텍스트는 유지한다.
- **증상 2**: 새 empty-else carrier가 컴파일되지만 실제 함수 최초 재컴파일에서 `exact local materialization requires a federated producer lop` 발생. `runtime/run-9k3brdl0/branch_true.log`, `runtime/run-onfb65gw/branch_false.log` 모두 재현.
- **원인 2**: then/else에 생성한 carrier가 같은 if parse range를 공유하여 recompile signature가 충돌했다. 서로 다른 FED/FOUT와 CP/LOUT 상태를 source 위치만으로 구분하지 못했다. branch 변수맵 오염이라는 초기 가설은 이 증거로 교정한다.
- **해결 2 방향**: 결정적인 branch path 기반 carrier identity를 clone 및 recompile signature에 보존한다. Dag의 mismatch 오류는 유지하며 이동을 생략하거나 runtime 보정하지 않는다.
- **검증 범위**: 초기 core439건 통과. Docker aggregate/shape/linear/control 수치 검사 통과. 실제 FED VAR 실행 및 GET/PUT/request·네트워크 byte 기록을 확보했다. shape 연산은 rewrite가 제거하지 않도록 위치별 weighted checksum으로 바꿨으며 현재 DP 선택은 CP 연산이므로 FED native 경로 검증으로 주장하지 않는다.
- **잠재 회귀/잔여**: 함수 boundary 조회의 foreign fact 허용, signature collision, carrier clone identity 손실을 새 회귀 및 최종 실제 true/false 실행으로 검사한다. 최종 통합 결과는 별도 receipt로 기록한다.

### 확장 검증에서 추가 발견한 회귀 — 해결 진행 중

- 2026-10-06 최종 후보 첫 all14 실행은 **26/28 compile PASS**. `kmeans_w3`는 executable realization/action closure 비수렴, `glm_w1`은 reduced solver 배열 materialization 중 Java heap 부족이다. 이전 28/28 결과와 구별하며 완료로 주장하지 않는다.
- GMM W3는 기존 270개 노드의 coarse 선택이 그대로인데 objective 18,305.96→8,760,104.45ms가 됐다. 새 보조 통신 연산이 선택되지 않았고 carrier 자체 비용은 기존 metadata 규칙으로 0이다. shape/frequency/physical realization 변경을 분해하여 확인한다.
- 실제 worker 최종 후보 첫 실행은 4/6 PASS. 동일 인자 반복 함수는 call identity ambiguity 수정 이후 hard model의 infeasibility를 드러냈다. 한 번 호출은 feasible이며 두 번 호출한 if-only/loop-only에서도 실패하므로 solver 예외를 숨기지 않고 function constraint encoding을 조사한다.
- Empty else의 실제 변환 유실은 함수의 2단계 재컴파일에서 재현했다. 1단계는 runtime 값으로 planner-keyed prefetch/mvvar를 만들지만 runtime 값이 없는 2단계가 자기대입을 빈 instruction 목록으로 덮어썼다. marked branch carrier block만 1단계 instruction을 보존하도록 수정했고 두 단계 재컴파일 회귀 4건 및 관련 recompile 44건을 통과했다. 실제 worker 재검증은 별도로 수행한다.
- 업로드 반례 초기 probe의 X가 PUBLIC으로 표시됐음을 확인하여 해당 결과를 mixed-privacy 증거에서 제외했다. PRIVATE_AGGREGATE X/public local Y로 보정한 feasible aggregate-binary probe에서도 선택된 명시적 REFED는 없었다. intrinsic FED matmul RHS upload와 REFED cache를 혼동하지 않는다.

- **추가 수정 확인**: 같은 source/formal/position에 대한 반복 함수의 중복 static input-authority link만 합쳐 hard factor의 zero-support 오류를 제거했다. 호출 boundary와 실행 횟수는 별개로 보존한다. 실제 혼합 privacy control 결과 304 및 전체 6/6 worker 수치/audit PASS, fallback=repair=0 (`runtime/run-1igaimzq`).
- **메모리 수정 확인**: support/quotient 관측이 끝난 뒤 frozen raw factor table을 축소 테이블로 순차 이전하여 두 전체 테이블 집합을 동시에 유지하지 않는다. 64MiB에서 1,500개 lazy factor 반례는 기존 reduce 배열 할당 OOM, 수정 후 모든 32×64 지원 상태 및 비용 보존 PASS. 실제 GLM W1은 기존과 같은 `-Xmx10g`에서 PASS. 한도 상향/후보 축소 없음.
- **고정점 수정 확인**: native rebinding이 기존 DIRECT support clause를 버리면서 서로 다른 worker layout을 3-pass 주기로 재발견했다. 이미 근거가 있는 clause의 합집합을 보존하고 기존 외부 support/action pruning이 소멸한 근거를 제거한다. staging empty-binding authority는 보존하지 않는다. kmeans W3 9.84초 compile PASS, 관련 closure 90건 통과.
- **GMM 원인 확인**: normalizer 단독 A/B 및 emission 전 contribution 합계의 objective bit 일치를 확인했다. carrier의 source 없는 TR이 UNKNOWN shape로 승격되어 합류를 오염시키고, downstream이 10GiB fallback을 적용했다. 실행 횟수·통신 보조 stage·carrier 자체 연산 비용의 문제가 아니다. 분기에서 실제 정의되지 않은 값을 새 자기대입으로 읽지 않도록 수정 중이다. 상세 근거 `gmm-cost-audit/REPORT.md`.


### 최종 결과 — 남은 항목 처리 완료

- 보조 통신 비용, StepLM repair, 분기별 명시적 이동, 반복 함수 boundary identity/static authority, 재컴파일 carrier 보존, k-means support 고정점, GLM 메모리, GMM undefined carrier 문제를 수정했다.
- GMM은 definite-bound 값에만 branch carrier를 생성하여 크기 정보 유실을 해소했다. 최종 objective는 W1 25,042.749584ms, W3 18,305.956167ms로 기존 수준이다. 기존 값이 있는 empty else 변환은 유지한다.
- 최종 소스의 Java 전체 컴파일/jar 성공, **77개 클래스 606 tests PASS (실패/오류/제외 0)**. **14×W1/W3 28/28 compile PASS**, 실제 Docker worker **6/6 PASS**, 모든 fallback/repair=0. 최종 jar SHA와 모든 source SHA, command, receipt freshness를 교차 확인했다.
- MMChain/forced-FOUT TSMM 및 반복 업로드 cache 의심은 complete feasible selection에서 추가 중복 비용이 확인되지 않았다. root-only 강제 probe와 all-PUBLIC probe를 혼합 privacy의 실행 증거로 사용하지 않는다. 감사 범위와 미확정 결론을 별도로 기록했다.
- 변경 전후 objective/그래프/후보 수와 측정 한계는 [최종 보고서](FEDPLANNER_REMAINING_WORK_2026-10-06.md), 소스 해시·명령·단위 검증 결과는 [validation.json](experiments/remaining-planner-work-20261006/validation.json)에 있다. 실제 네트워크는 loopback이므로 WAN 예측 정확도나 성능 개선율은 주장하지 않는다.
- 기존 Local/Global DP 보고서 및 verification 미커밋 자료는 보존했다. 위 진행 중 기록들은 발견·수정 순서의 이력이며, 현재 상태는 이 최종 결과를 기준으로 한다.

- **계획 비교의 한계**: 최종 GLM W1과 L2SVM W3의 예측 objective는 증가했으며, 두 경우 각각 기존 대응 노드 4개의 선택 배치가 바뀌었다. 전후 선택이 모두 같거나 모든 경우 더 저렴해졌다고 주장하지 않는다. 비용 보정과 로컬 탐색 품질의 영향 분리는 이번 28-case 비교만으로 확정하지 않았다.

### GLM W1 / L2SVM W3 비용 증가 분리 감사 — 진행 중

- **문제 정의**: 최종 objective 증가가 비용 보정인지, 기존 계획의 불가능화인지, 탐색 품질 문제인지 분리한다. 과거 수치만 비교하지 않고 현재 emission 이전의 동일 physical model/cost surface에서 complete assignment와 모든 hard factor를 검증한다.
- **수정 범위**: production/test/shared target은 변경하지 않는다. 외부 diagnostic overlay `/home/mchoi/fedplanner-plan-increase-20261006/`만 사용하고, 모든 실험은 `run_LAN_docker.sh --function-boundary-compare`로 실행한다.
- **현재 관측**: GLM bootstrap 46,212.52ms, incremental 단계 `RESOURCE_INITIAL`, merges/improvements=0. 현재 모델의 line983 주변 feasible 조합에서 27,027.08ms를 발견했다. 큰 전수 탐색은 중단하고 과거 coarse 배치로 제한한 완전 witness 검증을 진행한다. 초기 중단 로그와 source는 `glm/exhaustive-partial-results/`, `glm/exhaustive-partial.java`에 보존한다.
- **L2SVM 판단 주의**: 네 Y 노드만 바꾼 56개 조합의 infeasibility는 주변 alias/carrier를 함께 바꾼 과거 배치의 extension까지 불가능하다는 증명이 아니다. pinned heuristic 결과가 더 비싸다는 사실도 최적성 증명이 아니다. 기존 대응 노드를 고정하고 신규/보조 노드의 feasibility를 추가 검사한다.
- **의사결정 근거/잔여 위험**: 후보 삭제나 비용 변경 없이 현재 모델의 feasible witness로 탐색 품질을 판단한다. 모델 비용을 실제 WAN 실행시간으로 해석하지 않는다. 최종 결과는 후속 항목에 기록한다.

### GLM / L2SVM 증가 감사 — 확인 결과

- **GLM 확인 완료/수정 미착수**: 동일 현재 모델에서 46,212.521644ms보다 싼 21,977.116965ms complete witness를 확보했다. 모든 3,458 hard factor 위반 0. 실제 탐색은 초기 dense cover 예산으로 RESOURCE_INITIAL, merges/improvements=0. 따라서 탐색 품질 문제 확정. 후보/비용/런타임을 바꾸지 않고 확인했다.
- **L2SVM 조건부 확인 완료/잔여 분석**: 기존 191 coarse state를 고정하고 4개 새 carrier 및 alias/physical 선택을 푼 exact optimum은 27,412.450394ms, hardCost=0. 기존 배치가 불가능하다는 초기 가설은 폐기한다. 현재 9,014.979380ms 선택보다 비싸지만, unrestricted lower=3,358.047271ms로 전역 최적성은 미확정이다. 새 경계 이후 같은 배치의 비용이 달라진 세부 정당성은 추가 확인 대상이다.
- **수정 파일/검증**: 본체 변경 없음. diagnostic overlay 및 문서/receipt만 추가. 동일 Docker entrypoint의 compile/conditional exact solver로 확인했고 production source/jar가 이전 검증 SHA와 동일함을 재확인했다. 상세 근거는 [증가 감사 보고서](FEDPLANNER_PLAN_INCREASE_AUDIT_2026-10-06.md).
- **잔여 문제/회귀 위험**: GLM의 초기 DP factor 표현/예산 내 탐색 개선, L2SVM carrier 전후 물리 비용 차이 감사. 수정 시 후보 축소나 메모리 검사 제거로 우회하지 않으며 complete feasible witness와 all14 회귀로 확인해야 한다. 이번 확인 작업에서 코드 수정/commit/push는 하지 않았다.

### GLM 탐색 / L2SVM 경계 비용 수정 — 진행 중

- **요청/성공 조건**: 확인된 GLM RESOURCE_INITIAL 탐색 실패를 예산 내에서 고치고, L2SVM carrier 추가 후 물리 비용 차이의 원인을 수정한다. GLM 저비용 witness를 실제 DP가 찾는지, L2SVM 투명 경계의 비용/물리 권한이 보존되는지 검사한다.
- **실행 계획**: (1) 작은 solver/경계 비용 반례를 회귀 테스트로 고정, (2) 초기 factor 표현/투명 carrier cost authority를 최소 수정, (3) focused tests와 통합 build, (4) 동일 Docker GLM/L2SVM 및 14×W1/W3, 실제 2-worker 수치/audit 회귀, (5) source SHA와 결과 기록.
- **작업 경계**: solver와 cost model을 독립 lane으로 수정하고 root가 통합 build/검증을 담당한다. 기존 미커밋 수정/문서/verification은 보존한다. 후보 삭제, 메모리 cap 단순 상향, runtime fallback, TW/TR·recompile 규칙 완화는 하지 않는다.
- **근거 위치**: 기존 감사 `/home/mchoi/fedplanner-plan-increase-20261006/`; 이번 수정/회귀 `/home/mchoi/fedplanner-search-boundary-fix-20261006/`.
- **회귀 위험**: lazy/sparse message의 lower-bound·decode·소유권·실제 메모리 합계, carrier alias의 경로별 이동과 cache invalidation. 독립 비용 oracle/solver exact 대조 및 Docker fallback=repair=0으로 확인한다.

- **GLM 1차 수정 검증**: dense source table은 이미 CompactModel에서 공유하고 있었으므로 Regional 추가 메모리 예산의 중복 집계만 제거했다. 혼합 dense/lazy 소유권 회귀 및 solver 테스트 통과. 실제 GLM은 RESOURCE_INITIAL을 벗어나 10,311회 merge했으나 46,202.63ms에서 RESOURCE 종료하여 21,977ms witness에는 미달했다. 따라서 해결로 선언하지 않고, 남은 예산에서 경계를 고정한 작은 exact 개선 문제를 푸는 보강을 진행한다.
- **L2SVM 비용 원인/수정**: 여러 branch reaching definitions 때문에 GET 생성 수명을 inner-loop TRead(600회)로 대체하여 중복 과금했다. 실제 MatrixObject origin의 수명과 branch reachability predicate를 따로 유지한다. 동일 origin의 여러 경로는 합집합으로 과금하고, 반복마다 새 값인 loop phi는 단순 alias로 합치지 않는다. 빈 else·같은 원본 양쪽 경로·양쪽 새 값·nested/sequential branches·반복 분기 반대 arm 테스트를 추가했다.
- **L2SVM 비용 수치**: 같은 모델에서 기존 191개 coarse states의 exact extension은 27,412.45→7,289.35ms로 내려왔다. 이전 7,198.29ms와 남은 약 91.04ms 차이는 기존 runtime에도 있던 post-if `prefetch Y` 한 번의 GET 누락을 복원한 것으로 확인했다. 반복 GET 과금과 이 한 번의 비용을 구별한다.
- **추가 lowering 회귀**: 새 L2SVM 계획의 REFED anchor가 동일 ROW worker/partition을 legacy 1D와 full 2D로 표시하여, live Lop shape unknown일 때 문자열 충돌로 판정했다. complete counterpart의 일관된 전체 비분할 축 범위로만 canonicalize한다. 실제 다른 worker/partition/extent는 계속 거부한다. Dag 회귀 44건 isolated PASS, Docker 재검증 대기.
- **검증 실행 관리**: 한 초기 Docker 실행은 동시 Maven compile이 shared target/classes를 갱신하면서 ClassNotFound로 무효화됐다. 이후 전체 engine classes를 immutable 외부 snapshot으로 복사해 실행한다. 한 regression driver는 surefire:test만 호출해 새 테스트 class가 stale했다. 재현 로그는 보존하며, 최종 driver는 test lifecycle로 소스 컴파일부터 수행한다. 이 두 실행은 최종 성공 근거로 사용하지 않는다.

- **L2SVM lowering 검증 완료**: 동일 의미의 legacy/full anchor 정규화 수정 후 Docker L2SVM W3 compile/lowering PASS, objective 7,289.332817889578ms, LOCAL=6/REFED=1. 비용/anchor snapshot의 추가 25개 case도 모두 PASS.
- **GLM 보강의 안전성 검토**: persistent message cover를 검증하고 scalar lower bound를 보존한 뒤 message storage를 해제하여, 거절된 작은 neighborhood를 현재 incumbent에 조건화해 exact solve한다. 이미 root가 소유한 dense array는 추가 저장량에 중복 집계하지 않는다. 조건화 table 및 intermediate는 추가 cell 한도에 포함한다. 재compaction의 중복 배열을 막고 선택적 solve/lift의 resource rejection에서는 incumbent를 유지한다. 후보 삭제나 runtime fallback은 없다.
- **자원 제한의 정확한 범위**: 추가 numeric-cell storage 및 elimination당 assignment 제한이다. 전체 JVM 메모리 보장 또는 solve 내부의 강제 wall-clock interrupt로 해석하지 않는다. 시간은 conditional attempt 사이에 검사하며 frontier에 남은 원본 변수만 변경하므로 완전 탐색/전역 최적 보장이 아니다.
- **중간 검증**: 통합 86개 클래스 679 tests 실패/오류/제외 0. 이후 선택적 refine의 resource 처리와 noncompact 변경은 isolated 관련 81 tests PASS; 최종 소스로 다시 build/검증한다. GLM 실제 개선 여부는 아직 확인 중이다.

- **GLM 추가 원인 분리**: 처음 cheap neighborhood는 필요한 결합 연산을 포함하지 않았고, regret 우선순위만으로는 singleton 변경에 머물렀다. owned 원본을 복원하고 보조변수 관계 및 두 원본 hop을 포함해 네 연산 전체를 묶었다. 그 결과 필요한 `[758,759,760,762]` block이 생성되지만 아직 거절되는 것을 확인했다.
- **최종 거절 원인/수정**: 4-variable block의 기본 MIN_SEPARATOR 순서는 최대 단계 작업량 3,799,552라 1M cap에 걸렸지만, 이미 생성된 MIN_FILL/MIN_ELIMINATION 순서는 593,680, materialized 약 789k로 기존 한도 안이었다. bounded exact portfolio 선택에서 메모리와 단계 작업량을 함께 admission 기준으로 적용했다. configured fast path는 보존한다. 추가 compaction이나 cap 상향은 필요 없었다. 새 order 회귀 및 관련 84 tests PASS; 실제 최종 GLM 재실행 중.
- **예산 보존/우선순위**: positive potential 확인 후에만 두 단계 관계를 구성하고, 큰 영역과 작은 원본 영역을 모두 보존한다. 큰 영역 거절로 L2SVM에서 이미 가능한 작은 개선이 사라지지 않게 한다. 새로운 상태 후보 삭제나 실행 경로 변경 없이 기존 exact order portfolio만 한도에 맞춰 선택한다.


### GLM 탐색 / L2SVM 경계 비용 수정 — 최종 완료

- **최종 결과**: GLM W1 46,212.52→20,956.64ms, L2SVM W3 9,014.98→4,259.97ms. GLM은 동일 모델의 알려진 21,977.12ms witness보다 저렴한 feasible 선택을 실제 DP에서 찾았고 compile/lowering도 통과했다. 예측 비용이며 측정 학습시간으로 해석하지 않는다.
- **검증**: 전체 Java compile/jar, 86개 클래스 **681 tests PASS**(실패/오류/제외 0), **28/28 Docker compile PASS**, 실제 2-worker **6/6 PASS**, fallback=repair=0. source/jar SHA와 XML/command/result freshness를 확인했다. 정적 diff/shell/Python syntax 검사도 통과했다.
- **계획 공간/비교**: 28개 graph node/coarse alternative 수 유지. 0.01ms 허용 오차 기준 11개 objective 감소, 17개 유지, 증가 0개. Lowering 후 physical domain 수까지 불변이라고 주장하지 않는다. 후보 삭제, cap 상향, runtime fallback, TW/TR 후보 규칙 완화는 없다.
- **리뷰/잔여 한계**: 독립 리뷰에서 bounded order 선택과 factor cover/canonical acceptance에 blocker 없음. lower=12,645.75ms, selected=20,956.64ms로 GLM 전역 최적은 미증명이다. 추가 numeric-table 예산은 전체 JVM 메모리 보장이 아니며 시간은 시도 사이에 검사한다. configured-order counter는 최초 순서 시도를, pre-solve trace는 실제 bounded 순서를 나타낸다.
- **문서/근거**: [해결 보고서](FEDPLANNER_SEARCH_BOUNDARY_FIX_2026-10-06.md), [최종 validation](experiments/search-boundary-fix-20261006/validation.json). 앞선 실패/부분 성공 기록은 원인 분리 이력이다. 최종 상태는 이 항목을 기준으로 한다. 기존 미커밋 자료는 보존했고 commit/push는 하지 않았다.


## Local/Global DP 임의 resource budget 제거 — 완료

- **사용자 요구**: Global은 전체 문제를 exact로 풀어야 하며, 메모리/계산량에 유리한 elimination order를 사용할 수 있다. 실제 시스템 자원 소진을 막는 경우 외에는 Local/Global의 임의 시간·메모리 budget을 삭제한다. 이전 1M/8M/10초 예산 유지 결정은 이번 요청으로 대체된다.
- **감사 결과**: Global의 production 경로는 이미 전체 hard+cost model을 exact로 풀고 오류를 전파한다. 표현 변환이 부적격일 때도 원래 전체 문제로 돌아가며 일부 영역의 해를 Global 성공으로 내보내지 않는다. Local production의 1M assignment/8M retained slots/10s/16 후보 제한이 실제 제거 대상이다.
- **변경**: production Local의 네 제한과 해당 property 입력을 제거하고, test-only 명시적 Options로 한도 회귀를 유지한다. Local의 5% 상대 gap은 자원 예산과 다른 알고리즘 품질 종료 기준이므로 유지하며 Global에 적용하지 않는다. 새 PlannerResourceGuard는 실제 배열 할당과 JVM 최대 heap의 불가능성만 보호한다. 현재 heap 사용량에 collectible garbage가 들어 있으므로 그것만으로 새 고정 예산처럼 거절하지 않는다.
- **order 의미**: Global과 Local 내부의 exact 부분 문제는 같은 elimination-order portfolio를 사용한다. Local의 incremental 병합은 separator 크기, 작업량, conflict 점수를 이용해 순서를 정한다. fastOrderAssignments는 단일 순서 shortcut을 쓸지 전체 portfolio를 비교할지 결정하는 기준이며 전체 문제 풀이를 중단하거나 후보를 삭제하는 budget이 아니다. 한도를 초과해도 전체 exact 풀이로 진행함을 구별한다.
- **수정 파일/소유**: IncrementalRegionalOptimizer, SharedRegionalPreparation, RegionalSearchProblem, ExactCategoricalSolver, ExactPhysicalReducedSolver, 새 PlannerResourceGuard 및 회귀. 이전 비용/분기/런타임 변경은 보존한다.
- **검증 계획/현재**: 고정 1M를 초과하는 production Local solve, Global 전체 연결/비연결 factor의 완전 optimum 대조, infeasible component의 partial success 금지, 64MiB에서 불가능한 배열을 평가 전에 명시적 resource failure로 처리하는 focused 검증 통과. GC/실제 allocation 처리 보완 후 통합 suite와 Docker compile/runtime을 확인한다.
- **잔여/회귀 위험**: 임의 시간 한도가 없으므로 오래 걸릴 수 있다. 실제 자원 부족 또는 표현 한계는 명시적으로 남긴다. lower/canonical 검증과 Global 전체 성공/실패 경계가 유지되는지 검증한다. Local은 자원 부족에서 feasible incumbent를 반환할 수 있지만 Global exact 성공으로 표시하지 않는다.

- **계산량 개선/중간 결과**: 시간/메모리 cap 없이 실행하자 STEP-LM 기존 merge가 10분 이상 소요됐다. exact 비용과 lower bound가 모두 +Infinity인 조합에서 나머지 합산만 생략하는 정확한 단축을 추가했다. 17개 boundary 회귀 및 실제 STEP-LM Local+Global compile 테스트(3GiB heap, wall 72.16초) 통과. 이전 broad run의 88개 클래스/695 tests는 통과했으나 마지막 STEP-LM 진행 중 새 kernel로 대체하여 중단했고, 최종 소스로 전체 suite를 재실행한다. 중단 실행을 전체 PASS 근거로 쓰지 않는다.

- **실제 heap 소진에서 추가 결함 발견**: 최종 Docker L2SVM W1이 3.47B 누적 assignments, 약 1.42B retained slots까지 진행한 뒤 10GiB heap을 소진했다. `allocateInts`가 원래 OOME를 잡았지만 `Long.toString`으로 진단 메시지를 만드는 할당도 실패하여 raw OOME가 유출됐다. 고정 예산을 복원하지 않고, heap 소진 전에 준비한 typed resource exception을 진단 실패 시 사용하는 방식으로 보완한다. 해당 실패 receipt는 보존하며 최종 PASS 집계에서 제외한다. runner `COMPLETE`는 결과 파일 생성만 의미하므로 검증은 결과 JSON `status=passed`도 확인한다.

- **최종 완료**: 고정 production assignment/retained-slots/time/scored-candidate budget 제거. Global 전체 exact·실패 경계 유지. 실제 배열/진단 할당 실패를 처리하는 preallocated typed error 보완까지 완료.
- **최종 검증**: 최신 소스 Maven compile/test/jar, 89개 클래스 **699 tests PASS**, immutable engine의 **28/28 Docker compile PASS**, 실제 2-worker **6/6 PASS**, fallback=repair=0. 소스/3,713 classes/JAR hash 및 XML/command/result freshness 확인.
- **실험 결과**: 28개 graph/coarse 후보 수 유지. Local 예상 비용 4개 감소·22개 유지·2개 증가. 두 증가는 5% TARGET_REACHED에 따른 Local 결과이며 Global exact 성공으로 표시하지 않는다. 종료는 TARGET_REACHED 22, RESOURCE 4, EXACT 2, TIME 0. 실제 heap 소진의 L2SVM W1도 typed resource 처리 후 compile/lowering PASS.
- **잔여 한계/근거**: Local 5% 품질 조건 유지, JVM/Java 표현 한계 유지, 모든 metadata/native OOME 정규화를 보장하지 않음. [최종 정책 보고서](FEDPLANNER_RESOURCE_POLICY_2026-10-06.md), [validation](experiments/resource-policy-20261006/validation.json). 기존 미커밋 작업 보존, 이번 요청에서 commit/push 없음.


## Global 14×W1/W3 확인 및 origin/main 게시 — 진행 중

- **요청**: 남은 Global 전체 실험을 확인하고 이번 세션의 코드·테스트·문서를 origin/main에 commit/push한다.
- **통합**: 원격의 `58145e7366` Local boundary pruning과 `d57bca99d9` unknown-shape golden 검증을 보존하여 재base했다. 동일 파일 충돌에서 production 무예산 경로와 pruning counters, explicit baseline의 cut 비활성화를 함께 보존했다. `separateGlobalCalls=0` trace 누락도 복원했다.
- **검증 범위**: 95개 클래스의 통합 회귀, 동일 Docker fixture/cost 환경의 Global 28건 및 Local 28건, 실제 worker 6건. Global probe는 `planner=Exact`를 요구하고 whole-program commit 확인 후에만 성공을 기록한다. Lowering 후 별도 domain 재구성은 하지 않는다.
- **규칙/위험**: 고정 planner budget·runtime fallback·후보 축소를 추가하지 않는다. 실제 JVM/표현 한계 실패를 Global 부분 성공으로 바꾸지 않는다. 원격 변경과의 상호작용은 pruning/regional/kernel/unknown-shape 회귀 및 Docker로 검증한다.
- **게시 범위**: src/scripts/docs 포함, 이전 임시 `verification/` 로그는 untracked로 보존한다. 원본 근거 `/home/mchoi/fedplanner-global-publish-20261006/`.

## Joint input/boundary 구현과 최신 main 통합 — 해결·검증 완료

- **요청/환경**: 사용자가 병합·문제 수정·`origin/main` push를 명시적으로 요청했다. 원본 dirty 작업 트리 `/home/mchoi/w1357-paper-aligned-refactor`는 보존하고 `integrate/joint-boundary-main-20261006` / `/home/mchoi/w1357-joint-main-20261006`에서 작업한다. 통합 기준 main은 `58145e73667e0a4f43589e9d37199fc90d293ee8`다.
- **범위 결정**: 구현 시작 시 보관한 490개 main source baseline과 비교해 실제 joint 변경 25개를 추출했다. `_PLACEMENT` 타입·LOP lowering·zero compute 및 federation ID 0 alias 수명 수정, 새 joint source 6개, 관련 테스트와 Docker harness를 추가한다. 기존 physical snapshot export, STEP-LM/lmCG, 캠페인·보정 스크립트의 다른 미커밋 변경은 포함하지 않는다. Main의 반환 GET·RTT·loop-entry·pruning 수정은 유지한다.
- **병합 방식**: main 위에서 `git merge-file`로 구현 baseline/current/main의 3-way source 통합을 수행했다. 전체 dirty snapshot의 24개 충돌을 임의로 한쪽으로 해소하지 않고, 해당 기능의 delta만 사용했다. 운영 코드 충돌 2개는 `PlacementAnalysis`의 action-aware emission identity 및 `PlacementRelationClosure`의 양쪽 import를 보존하여 해결했다. 함수 alias-only 검사는 별도 순수 alias 회귀로 보존하고 typed transfer의 명시적 action·privacy·omission 거절도 함께 검사한다.
- **전체 빌드**: 첫 clean build는 병합된 테스트에서 `LocalMaterializationActionKey` import가 빠져 test-compile 실패했다. Import를 복원한 전체 main/test compile r2는 통과했다. 로그 `/home/mchoi/joint-main-integration-20261006/build-r2.log`.
- **비용 검증**: 반환 GET 11개, native result projection 6개, native batch ownership 7개가 C2W=3ms/W2C=7ms에서 **24/24 PASS**했다. J_hat의 동적 경로 비용과 main의 호출별 lifetime 및 batch RTT 계산을 함께 유지한다. 다중 원본 분기 값을 하나의 cache identity로 합치는 추가 최적화는 증명이 부족하여 채택하지 않았다.
- **진행 중 회귀**: 43개 관련 Java class를 실행 중이며 `LoopEntryCompletePlacementSpaceTest`의 identity fixture에서 추가 relocation이 나오는 행을 발견했다. 합법적인 추가 계획인지 잘못된 공급 증명인지 분석하며, 기존 main의 entry 이동·TW/TR 배치 보존 목적을 유지해 해결한다. 테스트를 끄거나 후보를 편의상 제거하지 않는다.
- **검증 경로**: 실제 runtime은 `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e`로만 확인한다. Harness는 이 통합 저장소의 전체 소스·main/test class·의존성을 복사·hash 고정하여 사용한다. 다른 작업 트리 overlay에 의존하지 않도록 바꿨고 Python 17개 테스트 및 Bash 문법 검사를 통과했다.
- **의사결정 근거/잔여 위험**: 순수 전달의 canonical TW/TR와 privacy는 유지한다. 명시적 이동만 계획·과금·lowering하며 runtime fallback을 사용하지 않는다. 병합 후 동일 최종 빌드의 전체 집중 회귀와 12개 Docker 사례가 완료되기 전에는 push하지 않는다. 변경·실행 증거는 `/home/mchoi/joint-main-integration-20261006`에 보관한다.

### Loop-entry 전체 계획 공간 oracle — 해결

- **증상/원인**: identity loop의 모든 합법 계획에서 relocation이 없어야 한다는 기존 단언이 실패했다. 실제 행의 이동 원본은 `p`, consumer는 `main/1/loop-body/0`의 `TWrite p`였다. 무관한 `sum(X)` 이동이나 잘못된 worker 공급이 아니었다. 공동 VALUE_MAP 증명이 직접 전달과 동일 layout의 명시적 normalization을 각각 보존하면서 후자의 합법적이지만 비싼 계획이 추가됐다.
- **해결**: `LoopEntryCompletePlacementSpaceTest`가 LOCAL 및 ROW/COL/BROADCAST의 직접 전달과 명시적 normalization을 구별하도록 수정했다. Canonical TW/TR 일치는 계속 요구하며 이동 원본·consumer·layout도 검사한다. 동일 상태에서 normalization 비용이 직접 전달보다 크고 Exact가 이동 없는 계획을 선택하는지 검증한다. 후보를 제거하여 테스트에 맞추지 않는다.
- **검증**: 격리 실행 2/2 PASS, raw 32,928행 / 합법 292행 / 물리 요약 10개. 추가로 `BranchPlacementNormalizationTest` 12/12 PASS: 서로 다른 concrete worker pool의 같은 FType 업로드가 full action signature로 구별되어 모두 남는다. 근거: `/home/mchoi/joint-main-integration-20261006/loop-verification`.
- **잔여 위험**: 합법 후보가 늘면 계획 공간 검사의 coarse summary도 달라진다. 후보 수만 고정하지 않고 공급 합법성·비용·최적 선택을 함께 검증한다.

### 큰 함수·loop 그래프의 증명 고정점 성능 — 해결·전체 재검증 통과

- **증상**: 43개 회귀 class 중 42개가 종료한 뒤 `ExactInputAuthorityOptimizationTest.solverHardFactorEncodingsExactlyProjectCanonicalTruth`가 끝나지 않았다. 888초 시점에 증거를 보존하고 이 실행의 마지막 JVM만 SIGTERM으로 종료했다. 회귀 결과를 PASS로 기록하지 않는다.
- **직접 관측**: 두 thread dump에서 실행 중인 경로가 `closePhysicalDependencies -> exactSinglePartitionRealizationProofs -> TreeMap`이었다. `CandidateRealizationReference` 비교에 필요한 canonical text를 반복 생성했다. 6.6초 동안 hot-thread CPU가 6.23초 증가했고 heap도 증가했다. Deadlock이 아닌 반복 증명 계산·할당 병목이다.
- **비교 범위**: 앞선 joint 빌드의 해당 method는 303.864초였다. 이번 845초 관측은 그보다 2.78배 길다. 이는 같은 Docker 조건의 workload 성능 실험이 아니라 Java 회귀 병목 진단이다.
- **수정 원칙**: 후보, FULL 증명, privacy, loop seed 및 joint 관계를 보존하면서 중복 증명·key 계산을 줄인다. 자원 제한을 늘리거나 합법 후보를 삭제하여 숨기지 않는다.
- **해결**: `PlacementRelationClosure.exactSinglePartitionRealizationProofs`의 내부 lookup을 exact reference의 구조적 equality/hash로 수행한다. 동기식 고정점 계산은 그대로 유지하여 순서에 의존하지 않는다. `PhysicalCandidateState`가 같은 fact revision의 증명 결과를 재사용하고, 모든 owner fact commit에서 이를 무효화한다. 정적 전역 cache나 변경되지 않은 것으로 추정하는 가드를 추가하지 않는다.
- **격리 검증**: `PhysicalSinglePartitionProofFixedPointTest` 5/5 PASS, `ExactInputAuthorityOptimizationTest` 8/8 PASS(97.52초, wall 98.48초). 후자는 기존 joint 약 313초, 이번 병합 수정 전 888초 중단과 비교한 진단 결과이며 workload 실행 성능 주장에는 사용하지 않는다. Hard-factor 집계 canonical 1,556 / factorized 115 / canonical cells 1,123,863,150 / encoded cells 29,124,985를 유지했다.
- **잠재 회귀/감지**: facts 변경 후 잘못된 cache 재사용이 위험하다. 모든 쓰기를 `PhysicalCandidateState.commit`에서 무효화하고 entry/backedge 증명 확대·철회 회귀 및 전체 모델 truth 비교를 다시 수행한다.
- **근거/재현**: `/home/mchoi/joint-main-integration-20261006/optimization-audit/{result.json,thread-1.txt,thread-2.txt,terminated-owned-test.json}`. 전체 r1 결과는 `regressions-r1.json`이다.

### Concrete action과 owner pool의 권한 확인 — 추가 제약 불필요

- **검토 질문**: derived upload의 owner 검사에 worker pool equality를 더 넣어야 하는지 확인했다. 실제 nested branch 두 pool의 16개 derived action 및 B-10∼B-16 지원 fixture에서 같은 owner가 서로 다른 pool을 선택하는 위반 행은 발견되지 않았다.
- **구현 근거**: exact lowering은 `action.durableAnchor()`를 runtime key로 등록하고, `Dag`는 concrete planner key가 있으면 live anchor를 사용하지 않는다. Runtime은 그 key의 worker/range/FType를 복원한다. Owner는 graph authority·수명 조건이고 action key가 실제 배치의 권한이다. Seed pool의 range를 출력 크기에 맞춰 투영하는 합법적 경우가 있어 owner와 output anchor의 전체 equality는 오히려 잘못된 제약이 된다.
- **결론/한계**: 이 검사 코드는 base/main/ours가 동일하며 새 guard를 추가하지 않았다. Fixture 조사는 모든 DML에 대한 완전 증명은 아니다. 근거: `/home/mchoi/joint-main-integration-20261006/owner-pool-audit`.

### 최종 재빌드 및 집중 Java 회귀 — 통과

- `mvn -q -Dskip.format=true -DskipTests test-compile`로 최종 main/test 전체를 다시 컴파일했다(40.729초, exit 0).
- 같은 소스의 43개 class를 다시 실행해 **351 PASS / 4 기존 제외 / 실패·오류 0**을 확인했다(355개 집계, 128.241초). 큰 그래프 증명 검사도 이 실행에 포함되어 종료했다.
- Build와 regression의 전체 Java source manifest SHA256은 동일한 `e29c937a479ac13d4273326e9e9af1b777abb75e583c701134026a12ac5a7cdf`다. 명령·class별 개수·XML은 artifact root의 `final-build.json`, `final-regressions.json`, `final-regressions-reports/`에 보존한다.
- Docker 첫 시도 `joint-main-20261006-final`은 DML 실행 전에 `/evidence/container-run.sh`를 찾지 못하여 종료했다. Runtime 성공/실패로 집계하지 않고 실행 경로의 mount 문제를 확인하여 다시 수행한다. 실패 결과와 `container.log`는 지우지 않는다.

### Docker 임시 경로와 최종 비용 검사 — 해결

- **원인/해결**: Docker daemon은 snap 경로 `/var/snap/docker/common/var-lib-docker`를 사용한다. `/tmp` staging 대신 저장소의 `target/joint-boundary-e2e-runtime`을 기본 경로로 사용하며 `--stage-root`로 명시할 수도 있다. 새 경로의 bind가 daemon에 보이고 입력 mount가 읽기 전용임을 확인했다. `exist_ok=False`를 유지하여 이전 실행 디렉터리와 섞지 않는다.
- **파일/회귀**: `scripts/fedplanner/run_joint_boundary_e2e.py`, `scripts/fedplanner/tests/test_run_joint_boundary_e2e.py`. 기본 경로·override·기존 stage 재사용 거절을 포함한 Python **18/18 PASS**, Bash 문법 통과.
- **최종 비용 회귀**: 최종 빌드에서도 반환 GET·응답 batch 3개 class **24/24 PASS**, C2W=3ms/W2C=7ms. 앞의 43개 class와 합쳐 **46개 class / 375 PASS / 4 기존 제외 / 실패·오류 0**이다. `final-cost-regressions.json`의 Java source manifest도 전체 빌드와 동일하다.
- **재실행**: `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e --run-id joint-main-20261006-final-stagefix --timeout-seconds 1800`. 같은 최종 빌드를 새 stage에 고정했으며 class preflight 6개와 model proof 10개는 통과했다. 실제 12개 사례의 최종 결과는 완료 후 아래에 기록한다.

### 통합 빌드 Docker 12개 E2E — 통과

- **결과**: `joint-main-20261006-final-stagefix` **12/12 PASS**. 성공 실행 9개와 privacy/불법 tuple에 따른 계획 거절 3개를 포함한다. Audit 오류 및 runtime conversion 위반은 0개다.
- **값·호출 확인**: L2SVM true/false SUM=-0.051876443681957596, NORM2=0.6933312394625315, 3×1; 함수 CALL_C=54/CALL_D=132; branch upload SUM=30, NORM2=56, 8×3. CP/FED 결과가 일치했다.
- **동일 빌드**: main class inventory digest `f33e5e0e1c3fea28d613415f90fb6a7e0937621d951b29f472079ed73d27a4ed`, main source inventory digest `c5005bbebd61d07abc41a9cc0db88090405d3ff98a0a24575bba4a27f5a0be64`. 핵심 class 6개 host/container SHA256 일치, model proof 10개 통과.
- **근거**: `/grid/3/cofee-lm-sweep-mchoi-20260914/joint-boundary-e2e-20261006/joint-main-20261006-final-stagefix/{result.json,manifest.json,artifact-inventories.json}`.
- **후속 main**: 검증 중 `d57bca99d9`(unknown-width golden 강화)가 도착했다. 기능 checkpoint `36faf7fdc2`에 실제 merge를 적용했고 세션 기록의 양쪽 추가를 보존했다. 운영 코드 변경이 없는 이 후속 커밋의 새 테스트를 별도로 확인한 뒤 최종 merge commit을 push한다.

### 후속 main golden과 공동 입력 표현의 통합 — 해결

- **증상**: `d57bca99d9`의 원문 테스트 4개를 최종 운영 class에 적용하면 2개가 통과하고 metadata/control-flow의 snapshot digest 2개만 달랐다. 강화된 unknown-width의 exact action ID, worker/range, support authority, consumer reference 및 early-pruning parity 단언은 처음부터 모두 통과했다.
- **차이 감사**: metadata NODE 36개와 control-flow NODE 54개의 privacy/placement 목록이 각각 동일했다. AVAILABLE owner/input key 34개·52개 및 각 outer emission shell도 같았다. Relocation은 각각 4개·8개로 유지됐다. 차이는 기존 `cfg-native-seed`에서 exact output/reader VALUE_MAP identity로의 전환, canonical worker endpoint, producer 연산 대신 정확한 TWrite 정의를 참조하는 내부 support 직렬화였다. 이는 inventory 비교이며 모든 DML의 전역 계획 공간 동일성을 주장하지 않는다.
- **해결/의도 보존**: `METADATA_AND_HANDLE_GOLDEN`과 `CONTROL_FLOW_GOLDEN` 두 digest만 갱신하고 이유를 주석으로 남겼다. Protected aggregate와 unknown-width digest, 최신 main의 강화된 authority/범위/parity 단언은 그대로 보존했다. Snapshot의 identity·support 내용을 생략하거나 테스트를 끄지 않았다.
- **검증/근거**: 격리 javac 통과, 4/4 PASS(6.389초). `/home/mchoi/joint-main-integration-20261006/latest-main-golden/result.json`, `updated-run.log`, main/integration snapshot 및 diff. 최종 Maven compile/test 결과와 운영 class의 Docker frozen build 일치도 별도로 확인한다.
- **잠재 회귀**: 무근거 golden 갱신은 잘못된 계획 삭제를 숨길 수 있다. 원문 upstream 테스트 결과와 노드·후보·이동 비교를 함께 보존하고, 더 강한 unknown-width 단언은 수정하지 않았다.

### 최종 통합 결과

- **범위/계보**: 구현 checkpoint `36faf7fdc2`는 main `58145e7366` 위에 범위를 한정한 joint 구현을 통합했다. 후속 main `d57bca99d9`는 실제 merge의 다른 parent로 보존한다. 원본 dirty 작업 트리의 다른 실험 변경은 그대로 남긴다.
- **검증 합계**: Java **47개 class / 379 PASS / 기존 제외 4개 / 실패·오류 0**, Python **18/18**, Docker **12/12 PASS**. 후속 main 반영 후 전체 compile(28.694초) 및 golden 회귀 4개(7.626초)도 통과했다. 추가 source 변경은 golden 테스트뿐이다.
- **빌드 연결 근거**: 최종 main source·의존성·운영 `.class` 3,742개·Docker model-proof class는 성공한 frozen Docker 빌드와 동일하다. Maven 재복사로 Python harness/test/cache resource 4개가 달라졌으나 실제 host runner의 hash는 같고 해당 파일은 DML/JVM runtime에서 실행하지 않는다. Test class 차이는 `EarlyPrivacyPruningLegalSpaceParityTest.class` 하나다. `final-docker-build-parity.json`에 전체 diff를 명시했다.
- **마지막 Java source manifest**: `d004b300ad456570da1625eae2c0a58e84ea2528cf82d3b1c791389ad1b4cc12`. Artifact root `/home/mchoi/joint-main-integration-20261006`의 `merged-main-build.json`, `merged-main-regressions.json`, `final-regressions.json`, `final-cost-regressions.json`을 함께 확인한다.
- **잔여 한계**: 구현은 구조적 공동 도달 관계 `J_hat`이며 임의 predicate의 논리 상관까지 풀지 않는다. 재귀 함수, 모든 operator/layout 조합, 보호된 공유 `rmempty` 반환의 기존 한계는 확장하지 않았다. 16,384개 공동 환경 resource limit 및 보수적인 일부 동적 map 비용은 [구현 검증 보고서](JOINT_BOUNDARY_IMPLEMENTATION_VERIFICATION_2026-10-06_KO.md)의 범위 설명을 따른다. 이번 통합으로 새로 확인된 미해결 기능 회귀는 없다.


### origin/main 동시 변경 통합 — 검증 중

- 원격 `49509ab7f8`의 joint-input/명시적 function transfer 변경을 보존해 병합한다. 앞선 `719bedf86c`의 742 tests, Global 26/28, Local 27/28, runtime 6/6 결과는 **병합 전** 근거로 분리했다. Global GLM 두 실패는 실제 10GiB heap/Java factor 표현 한계이며 whole-program commit은 0이었다.
- Local LogReg W3의 `INCREMENTAL_CONDITIONAL_DP_WORSENED`는 subtree 동률의 backtrace가 전체 compensated 합산 순서에서 기존 해보다 미세하게 비싸지는 문제였다. 동률에서 incumbent를 유지하고 strict subtree 개선만 decode한다. invariant는 유지했다. 작은 음성 회귀, 관련 42 tests 및 원래 Docker LogReg W3의 수정 overlay PASS를 확보했다. pruning baseline에서도 원래 실패하여 prefix pruning 원인설은 폐기했다.
- 분기 정규화는 원격의 `TR → _PLACEMENT → TW` 하나로 통일한다. 기존 normalizer는 이 구현의 호환 진입점이 된다. 정의되지 않은 arm 변수 읽기 방지, TR/TW의 branch origin marker, `_PLACEMENT`의 고유 recompile signature를 보존한다. 명시적 함수 인자 download가 추가되었으므로 기존 특정 이동 위치 가정은 선택된 source placement/action과 일치하는지를 검사하도록 갱신했다.
- 연속 분기의 업로드 authority는 앞선 TW가 실제 materialized output map을 전달한다는 증명을 그래프에 보존한다. 입력 anchor와 출력 map을 혼동하지 않고 exact derived-action proof/범위를 검증한다. 앞선 분기 실행 여부만으로 후보를 버리는 제안은 채택하지 않았다. exact runtime은 고정된 worker/range key를 사용하기 때문이다. 관련 positive/forged-range 9건 및 authority/cycle/branch upload 28건 isolated PASS.
- 확대된 123개 class 통합 회귀에서 ALS/LogReg의 late physical refinement, STEP-LM의 closure/표현 한계도 확인했다. 정상적인 native-domain 증가, exact runtime WDivMM 교정, executable fact가 없는 coarse 잔재를 기존 guard가 거부하는 경우를 구별해 수정한다. 근거 없는 native emission 삭제 검증은 유지한다. 수정 전 마지막 GLM 검사는 새 joint environment 비교가 장시간 반복되어 stack을 보관하고, 이미 소스가 수정된 검증 실행을 종료했다. 이 실행은 전체 PASS로 보고하지 않는다.
- 최종 source로 compile/unit/Docker를 다시 실행하고 별도 receipt에 기록한다. 원격 자체의 동작과 통합 변경을 구별하기 위한 baseline 재현도 진행한다. 임의 planner budget, 부분 Global 성공, runtime repair는 추가하지 않는다.
## 구조 인증을 이용한 중간 구현 — 아래 완전 삭제 결정으로 대체됨

- **문제 정의**: 정상 루프는 초기 entry 공급과 모든 reaching writer/input 관계를 강제하지만, native physical candidate 질의마다 다시 source-grounding 및 SCC 정제를 수행했다. 단순히 검사를 삭제하면 PRESENT 입력 부분집합이나 독립적인 source-free AND dependency를 잘못 수용할 수 있다.
- **해결/의사결정 근거**: immutable StructuralContext에 후보와 무관한 입력 순서를 한 번 계산한다. entry writer 중 하나가 앞서거나 일반 연산의 모든 가능한 placement input이 앞선 경우 순서를 부여하고, 순서 없는 의존성을 가진 상위 노드는 역전파로 제외한다. 이 구조 보장이 있는 cyclic 질의는 기존 dead-dependency propagation만으로 grounding이 따라오며 별도 SCC/source pass를 생략한다. DAG에서는 false empty leaf를 먼저 제거한 뒤 동일하게 추가 grounding pass를 삭제한다. 기존 정확한 worker/range, all-reaching writer, upload authority, selected input 제약은 유지한다.
- **수정 파일**: `NativePlacementContinuity.java`, `SearchSpaceMetrics.java`; native/real-DML/attribution 회귀 테스트. DP/Exact factor, 변수 및 비용식 변경 없음.
- **캐시 안전성**: structural revision은 입력 순서를 재생성한다. mutable Hop의 placement-data/alias 분류를 snapshot하여 datatype/op 분류가 달라지면 동일 Hop identity라도 기존 context 재사용을 거부한다. 입력 순서가 없는 loose/recursive cyclic helper는 기존 엄격한 검사를 유지하며 permissive fallback은 추가하지 않는다.
- **검증**: 수정 전 103 tests(실패/오류 0, 기존 skip 1). 새 loop 검사는 기존 SCC 2회 때문에 수정 전에 실패했으며 수정 후 SCC 및 PROOF_GROUNDING 호출 0. 25개 class의 확대 검증은 245 tests, 실패/오류 0, 기존 skip 8(실제 통과 237). ROW/COL/FULL nested loop의 모든 fixture candidate support를 기존 general cyclic algorithm과 정확히 비교했다. exhaustive fixture는 raw 29,400 / admitted 280 / physical 7 유지, entry upload cost의 T=1/2/10 불변성 유지. 2-worker runtime loop witness 포함. 분기, 함수 중첩 루프, zero-trip exit, entry 제거/복원, datatype 변화, 독립 seedless cycle 회귀 포함.
- **잠재 회귀 위험/감지**: 후보가 structural graph에 없는 의존성을 만들거나 native dependency builder의 metadata 제외 조건을 바꾸면 입력 순서 증명이 무효가 될 수 있다. 구조/physical dependency builder를 함께 수정하고 nested-loop reference equality 및 negative AND/seedless tests로 감지한다. 기존 graph builder의 alternative object uniqueness를 유지해야 한다.
- **잔여 범위**: 인증되지 않은 순환 그래프의 기존 grounding과 Greedy selected-value 검사는 유지한다. 모든 DML의 plan-space 완전성을 입증한 것은 아니다. 성능 수치는 동일 Docker 조건의 실제 결과를 확인한 후만 기록한다.
- **상세 계획/명령/결과**: `docs/STRUCTURAL_GROUNDING_2026-10-06.md`, `.omx/structural-grounding-evidence/`.

- **최종 Docker 확인**: 동일 pinned image/stage/profile/renderer의 logreg W3 WAN-Mid DP-local compile-only 전후 1회씩 모두 PASS. plan fingerprint `a220ac1a567116b566bda9a76a77ab1957aee9018185baa459d6b0098768112b`, objective bits `4682065009762557211`, raw values 35,873, raw factor cells 8,759,394 및 reduced counts 일치. lowering mismatches/missing physical/missing synthetic 모두 0. optimizer는 양쪽 RESOURCE stop이므로 전역 최적성 증거는 아니다. compile 29.371→29.294초, analysis 19.962→21.301초의 단일 cold sample로 속도 향상을 주장하지 않는다. Maven package 및 최종 diff check PASS.

## 재귀 함수 제외: candidate/DP source-grounding 코드 완전 삭제

- **문제/범위 확정**: 사용자가 재귀 함수는 지원 범위 밖이라고 명시했다. 이전에 추가한 구조 인증 및 uncertified fallback은 이 범위에 필요 없으므로 제거했다. 위 중간 구현의 설명·검증은 이력이며 현재 설계는 이 절과 상세 보고서를 따른다.
- **해결**: NativePlacementContinuity의 candidate SCC grounding/refinement, DAG grounding 및 coarse Boolean source 전파를 삭제했다. 추가했던 orderedInputClosure/inputKinds 인증도 없다. 기존 physical leaf authority, worker/layout, exact input bindings, dead dependency pruning, 최종 entry/input/fn-boundary factors는 유지했다. DP factor/state/비용식 및 runtime 변경 없음. SCC recording 및 mutable counters 삭제; 과거 diagnostic schema 값만 상수 0으로 유지한다.
- **보장 근거**: 비재귀 프로그램의 entry/입력 관계는 기존 최종 경계 제약이 강제한다. 임시 Native context가 함수 반환 entry를 모두 포함한다고 가정하지 않는다. 함수 반환과 ordinary backedge의 union은 LogicalBoundaryRealizations가 유지한다. Native helper는 완전한 프로그램 유도 가능성 검사가 아니라 조건부 물리 호환성 검사다.
- **회귀/검증**: 27 classes, 259 tests, failure/error 0, 기존 skip 8(251 pass). 완전히 미정의인 loop 변수/상호 미정의 변수의 frontend 거부, 일반 함수 반환→loop 초기값의 경계 union, branch/nested/zero-trip, finite 29,400→280→7 plan-space oracle, 초기 업로드 비용 불변성 및 2-worker 실제 실행 포함. 일반 함수 반환 테스트는 삭제 전 SHA-검증 JAR에도 PASS. package 및 diff check PASS. 독립 architecture review CLEAR.
- **시험 과정의 교정**: 한쪽 분기에서만 정의한 변수는 frontend가 반드시 거부하지 않는다. 기존 grounding도 그 보장을 하지 않았으므로 잘못된 시험 가정을 제거했다. source-free 인공 helper cycle 거부는 새 helper 계약 밖이며, 물리적 negative coverage는 초기 entry worker가 불일치하는 유효 loop로 유지했다. private 함수 반환 fixture의 배치 불가를 우회하는 production 변경은 하지 않았다.
- **잔여 범위/위험**: Greedy(FedAll/Heuristic)의 별도 selected-value 검사는 그대로다. 재귀 함수 및 모든 경로의 definite assignment 보장을 새로 제공하지 않는다. 새 종류의 cyclic 프로그램 표현을 지원하면 entry/boundary 계약을 다시 검토해야 한다. 전체 DML 완전성이나 성능 향상을 주장하지 않는다.
- **보고서**: `docs/STRUCTURAL_GROUNDING_2026-10-06.md`; 증거 `.omx/structural-grounding-evidence/deletion-*`.

- **완전 삭제본 최종 Docker**: baseline/deleted 모두 PASS, 비코드 manifest identity 전부 동일, 차이는 의도한 production 2파일과 JAR뿐. pruning receipt 전체 일치(plan fingerprint `a220ac1a567116b566bda9a76a77ab1957aee9018185baa459d6b0098768112b`, objective bits `4682065009762557211`, raw 35,873/8,759,394, reduced 25,952/5,742,281). lowering 불일치/누락 모두 0, cleanup 완료. RESOURCE stop 동일. compile 29.371068/29.872102초, analysis 19.962071579/19.693095447초이며 단일 표본으로 속도 향상 주장 없음. 최종 source hash와 실행 manifest 일치. 비교 증거: `deletion-docker-comparison.json`.

### source-grounding 삭제의 origin/main 통합 검증

- **상태/문제**: 사용자 요청으로 `origin/main`에 commit/push하기 전 최신 `49509ab7f8`로 rebase했다. 함수 경계/joint input 구현이 추가되어 기존 baseline과 달라졌다. SESSION 문서는 양쪽 추가 내용을 보존했고 production은 자동 병합됐다. `_PLACEMENT` native 지원, 전체 entry/함수 입력 제약 및 joint physical-map 제약을 유지했다. 독립 interaction review CLEAR.
- **검증**: 36 classes / 329 tests 중 317 pass, 기존 skip 8, failure 4, error 0. 네 실패를 모두 unmodified `49509ab7f8` production class로 재현했다. Native existential certificate 시험은 expected1/actual2, IndependentCompletePlacementSpace의 세 시험은 VALUE_MAP의 null attached anchor를 `geometry` helper가 거부한다. source-grounding 제거로 생긴 신규 실패는 확인되지 않았다. 기존 시험을 완화/제외하거나 production을 우회하지 않았다.
- **수정 범위/판단**: source-grounding 변경은 production 2파일, 해당 회귀/보고서뿐이다. 최신 main의 새로운 VALUE_MAP 표현에 맞춘 일반 oracle 수정은 별도 후속 범위로 남긴다. 기존 테스트 실패를 통과로 보고하지 않는다.
- **새 main 기준 oracle**: loop-entry 32,928 raw / 292 admitted / 10 physical 조합 PASS. 이전 29,400/280/7은 과거 baseline에 대한 기록이다. 함수/joint 경계, 비용, Native, undefined-input 및 실제 loop 실행 회귀 포함. package PASS.
- **잔여 이슈/잠재 위험**: 위 네 upstream 테스트 기대값 불일치가 남는다. 별도 후속 수정 시 VALUE_MAP의 선택된 입력 binding으로 map을 해석하고 독립 oracle 공간을 갱신해야 하며, null anchor를 임의의 map으로 바꾸어 통과시키면 안 된다. 검증 로그: `.omx/structural-grounding-evidence/main-integration-*`, `main-baseline-*.log`.

### main 통합 중 발견한 pre-privacy replay 인덱스 오류 수정

- **문제/원인**: 최신 unmodified main `49509ab7f8` 및 source-grounding 통합본 모두 logreg Docker compile에서 `closePrePrivacyValueMaps:899`의 `Index 756 out of bounds for length 756` 오류. old fact list 크기로 줄어든 replay fact list를 위치별 접근했다.
- **해결/근거**: 이미 사용하는 `changedCandidateOccurrences(before, after)`로 변경된 owner를 계산한다. 추가/삭제/재정렬을 의미 기준으로 비교하고 기존 replay.changedOrdinals의 union도 유지한다. 후보를 버리는 guard/fallback 없이 closure 갱신 추적을 바로잡는다.
- **수정 파일**: `PlacementRelationClosure.java`, `DirectedDirectClosureDirtyConeTest.java`. source-grounding 제거와 별도 커밋으로 구분한다.
- **검증**: shrink/reorder helper 계약을 포함한 focused class 22 tests PASS, 이어서 관련 16 classes 134/134 PASS(오류/skip 0), 독립 설계 검토 CLEAR. helper 시험만으로 기존 호출부의 red/green을 주장하지 않으며, 실제 Docker compile 재실행으로 확인한다.
- **잠재 회귀/잔여 문제**: owner identity와 전체 사실 집합의 의미를 보존해야 한다. 기존 네 VALUE_MAP oracle 기대값 불일치는 별도 미해결이며 이 수정으로 완화/제외하지 않는다. baseline Docker 증거 `.omx/structural-grounding-evidence/main-baseline-docker.log`; 수정 전 통합본 `main-integration-docker.log`.

- **수정본 최종 Docker runtime**: `joint_loop_toggle` 및 `joint_function_calls` 모두 PASS, CP/FED 숫자 fingerprint 일치, model-proof preflight PASS, runtime conversion 위반 0. 증거 `/grid/3/cofee-lm-sweep-mchoi-20260914/grounding-main-joint-20261006/grounding-main-final-20261006/result.json`. Package PASS. 큰 logreg compile은 인덱스 오류를 넘었으나 이후 cost surface에서 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 실패했다. factor 한도나 후보 공간을 변경해 우회하지 않으며 기준 빌드 비교로 귀속을 확인한다.

- **최종 귀속 확인**: 최신 main에 인덱스 수정만 적용한 기준 빌드도 같은 cost-surface 검증 지점에서 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 실패했다. 동일 Docker/input/profile, 공통 replay 수정, 차이는 NativePlacementContinuity/SearchSpaceMetrics 두 파일뿐임을 manifest로 확인했다. 기존 인덱스 오류는 양쪽에서 제거됐으며 큰 모델 한계는 upstream 후속 과제로 남긴다. 작은 loop/function Docker runtime 성공과 큰 logreg compile 실패를 구분한다. 컨테이너 cleanup 완료, 최종 source hash 일치. 증거 `main-final-docker-attribution.json`.

## Native/supply 분리와 파생 materialization 공유 — 진행중

- **문제**: 사용자는 transient/retained 공급 선택의 제거와 native 연산·이동 분리를 요청했다. 최신 main 조사에서 명시적 retained 후보는 없지만, node authority 행의 post-operation movement 결합 및 무조건적인 REFED owned caching을 확인했다.
- **환경**: origin/main `eb64f9c939`, 새 worktree `/home/mchoi/w1357-derived-supply-sharing-20261006`. 기존 worktree/실험은 변경하지 않는다.
- **설계/근거**: `DERIVED_SUPPLY_SHARING_DESIGN_2026-10-06_KO.md`. DIRECT_FOUT/RELOCATION 및 privacy/TW/TR는 유지한다. 기존 정확한 demand activation과 lifetime grouping을 재사용한다.
- **검증 기준**: 기준 8개 class 82/82 PASS. 새 native/supply 관계의 양방향 계획 보존, 실제 version/layout 구별, Global/Local canonical recost, selected lifetime 및 Docker runtime을 검사한다.
- **잔여 이슈**: 구현 및 이후 검증 진행중. 기준에 없는 retained 차원을 삭제했다고 주장하지 않는다.
- **회귀 위험/감지**: invariant loop의 반복 REFED를 매번 재생성하면서 한 번만 과금하지 않도록 cost-derived lifetime과 runtime 동작을 함께 검사한다.
# Derived supply sharing 추가 검증 이슈

- **상태**: 해결. 최종 회귀 및 Docker 통합 검증 완료.
- **환경**: 새 worktree `w1357-derived-supply-sharing-20261006`, 기준 `eb64f9c939708735940f2ae095c5c8bd526decf7`.
- **문제 정의**: (1) 비용이 같은 physical movement로 묶는 consumer별 receipt가 서로 다른 registry authority/Lop으로 내려갔다. (2) 연산 capability의 `nativeExec`를 선택된 execution으로 오해하면 CP 대안을 FED로 가격 매겨 `EXACT_FED_EXECUTION_LAYOUT_UNPROVEN`이 발생한다. (3) FOUT → 새 local staging → REFED를 원본 FOUT lifetime으로 과금하면 반복 upload를 누락한다.
- **해결**: emission에서 physical identity별로 exact obligation을 합치고 전체 원래 receipt는 normalized plan에 보존한다. Runtime audit은 실제 emitted representative를 검증한다. Native candidate의 execution은 선택된 emission에서 얻고, native output state와 구별한다. 새 staging upload는 consumer 실행 빈도로 계산한다.
- **원칙/판단 근거**: 합법 후보를 닫거나 runtime에서 보정하지 않고 representation·비용·lowering의 동일 계약을 수정한다. TW/TR, privacy, source/version/layout 권한은 유지한다.
- **수정 파일**: `ExactPhysicalNativeSupplyRepresentation`, `ExactPhysicalCostModel`, `PlacementEmissionTransaction`, `PlannerRuntimePlacementAudit`, runtime REFED/FOUT 및 관련 회귀 테스트.
- **검증**: 최초 focused 116 tests 통과. 이후 확장 검증에서 execution/capability 구별 결함을 찾아 수정했다. 최종 35개 class / 308 cases: 304 통과, 기존 skip 4, 실패/오류 0. Loop entry 유한 공간은 raw 32,928 / admitted 292 / physical 10으로 검증됐다. 최종 frozen Docker 12개 케이스 모두 기대 결과, model proof 10 tests 및 class preflight 통과, 계획 밖 runtime conversion 0. 상세 결과는 `DERIVED_SUPPLY_SHARING_RESULT_2026-10-06_KO.md` 참조.
- **추가 경계 수정**: FED/LOUT input upload의 유효한 `ACTION` identity를 prefix만으로 거부하지 않고 exact selected-action membership을 검증한다. 평균 실행 횟수가 같거나 작아도 consumer-only 반복 loop가 있으면 공유 lifetime을 도출한다. 두 경우를 production fixture 및 activation 테스트로 확인했다.
- **기존 실패 구분**: `IndependentCompletePlacementSpaceTest`의 3건(`bounded protected receipt needs an exact worker map`)과 별도 KMEANS oracle 실패는 frozen baseline에서도 재현했다. `.omx/derived-supply-sharing/baseline-independent-complete.log`, `baseline-kmeans-oracle-evidence.txt` 참조.
- **잔여 이슈**: 반복 공유 copy는 source value lifetime까지 유지한다. Exact last-consumer 해제나 shared memory budget 최적화는 구현하지 않았다.
- **잠재 회귀 위험/감지**: selection metadata가 재컴파일에서 유실되면 비용/runtime sharing이 달라질 수 있다. Registry snapshot, Lop/명령 round-trip, planner authority fail-closed, mutation/owner cleanup 및 격리 Docker 실행으로 검사한다.

### 비용·resource 변경 통합의 마지막 회귀 수정

- `nativeTransientCompatibilityProofs`는 native receipt를 existential certificate로 투영한 뒤 동일한 record만 `distinct()`로 합친다. witness, exactness, dependency 또는 layout이 다른 증명은 유지한다. upstream에서도 확인된 expected 1/actual 2 회귀를 해결했다.
- relocation 회귀 테스트의 factorized solver 결과에는 auxiliary 변수가 포함된다. production optimizer처럼 원래 decision prefix를 추출한 뒤 canonical hard factor를 검증하고 objective/selection에 전달한다. 테스트의 강제 relocation 조건은 유지한다.
- 최종 Maven focused 8 classes: 135 tests, 134 PASS, 기존 ignore 1, failure/error 0. `test jar:jar` 성공. 원본 통합본 129 classes의 확대 검증은 1,138 tests 중 1,125 PASS, failure 1, error 7, skip 5였으며, 이번 두 수정의 통과가 나머지 실패까지 해결했다는 뜻은 아니다.
- 미해결: 함수 입력 domain 호환성(P1), 함수 경계의 derived FOUT authority(GLM), 큰 cost factor 표현(ALS/STEP-LM), 일부 closure/greedy-policy 오류. 원격 baseline 재현이 있는 실패만 upstream에서도 관측됐다고 기록한다. P1/GLM 실패의 upstream/merge 귀속은 아직 미확정이다. 모든 실험 PASS나 publish-ready 품질 인증을 주장하지 않는다.
- 원문 근거: `/home/mchoi/fedplanner-main-integrated-20261006/build/regression-final-0e44222031.json`, `publication-focused.log`. Docker의 frozen code와 마지막 focused 수정은 최종 보고서에서 구분한다.
## VALUE_MAP 표현에 뒤처진 테스트 oracle 4개 — 수정 및 검증 완료

- **범위/의사결정 근거**: 사용자가 선택한 잔여 항목 2만 수행했다. production planner/DP/runtime은 수정하지 않고 최신 selected-receipt 계약에 테스트를 맞춘다. 이전 source-grounding 제거와 factor overflow 개선은 이번 변경 범위가 아니다.
- **증상/원인**: Native continuity 테스트는 endpoint certificate가 하나라고 가정했다. 실제로는 선택된 입력 증명별 projection이 필요하다. complete-space 테스트 3개는 모든 receipt가 직접 worker-map anchor를 가진다고 가정해 VALUE_MAP에서 실패했다.
- **해결**: native proof의 선택 입력별 identity와 추가·제거·복구 안정성, endpoint certificate projection의 multiplicity를 검사한다. VALUE_MAP은 선택한 support clause의 정확한 입력 owner/reference를 따라 실제 worker/range map을 계산한다. entry/if/else binding을 모두 요구하고, 동일 map의 분기도 생략할 수 없도록 negative mutant를 추가했다. 기대 공간은 literal fixture 입력 관계에서 독립 생성하며 전체 raw 조합 분류·집합 동등성·projection 유일성·계획 삭제/주입 검사를 유지한다.
- **fixture 한계/잔여 이슈**: 기존 unreduced 8x2 fixture에서 native output 폭 1 witness가 관측됐으며 fixture HOP dimension 지정만으로 해소되지 않았다. 이를 정답으로 고정하지 않고 테스트 입력을 같은 제어 흐름의 8x1 literal vector로 바꿨다. 다중 열 native shape 추론은 별도 미해결 범위로 남기며, 새 52개 계획을 이전 fixture 공간 보존이라고 주장하지 않는다.
- **수정 파일**: `NativePlacementContinuityTest.java`, `IndependentCompletePlacementSpaceTest.java`, 이 문서 및 `VALUE_MAP_TEST_ORACLE_2026-10-06.md`.
- **검증**: 관련 8개 클래스 124 tests 중 123 PASS / 기존 skip 1 / 실패·오류 0. PRIVATE와 PRIVATE_AGGREGATE 각각 raw 279,936개를 전수 분류하여 독립 기대 공간 52개와 정확히 일치했고 나머지 279,884개는 REJECTED, UNKNOWN 0이었다. 독립 read-only 리뷰 CLEAR. 최종 명령과 로그는 상세 보고서 및 `.omx/value-map-oracle-evidence/` 참조.
- **잠재 회귀 위험/감지**: geometry 집합으로 합치면서 동일 배치의 분기를 잃는 위험은 정확한 owner inventory 및 binding 삭제 mutant로 검출한다. 전체 공간이 같은지 확인하므로 단순 개수 변경으로 통과시키지 않는다. fixture는 범용 CFG/shape 정확성 증명이 아니다.

- **Docker 최종 검증**: `run_LAN_docker.sh --joint-boundary-e2e`의 frozen class hash 검증 PASS, 수정 oracle 6 tests PASS, loop/function 2 cases의 FED/CP 수치 fingerprint 일치, runtime conversion 위반·audit error 0. 결과: `/grid/3/cofee-lm-sweep-mchoi-20260914/value-map-oracle-20261006/protected-loop-function/result.json`.

- **게시 전 최신 main 통합**: 원격 `7b0656c29c`로 rebase했다. `547f4799cd`가 동일 endpoint certificate를 deduplicate하므로, 최신 기대값은 certificate 1개다. 선택 입력별 native proof 2/3/2개와 identity 안정성 검증은 유지한다. 앞선 multiplicity 설명은 `eb64f9c939` 기준 기록이며 최신 게시 계약은 이 항목으로 갱신한다. Production 변경을 되돌리지 않고 문서 양쪽 append를 보존했다.

- **최신 main 게시 검증 완료**: `7b0656c29c` 위에서 동일 8개 클래스 재실행 123 PASS / 기존 skip 1 / 실패·오류 0. Docker `main-publication`에서도 수정 oracle 6 tests 및 loop/function 2 cases PASS, class hash 일치, runtime conversion 위반·audit error 0. 근거 `/grid/3/cofee-lm-sweep-mchoi-20260914/value-map-oracle-20261006/main-publication/result.json`. 최종 변경은 테스트 2개와 문서 2개다.


## 8x2 native 출력 witness 폭 1 — 수정 및 회귀 검증 완료

- **범위**: 사용자 지정 후속 항목 1. `8f6bb285e5` 기준으로 원래 다중 열 fixture를 복원하며 다른 통합 실패는 별도 기록한다.
- **재현**: 8x2 ROW source → VALUE_MAP TRead → native `+1`에서 열 끝 좌표가 2 대신 1. 신규 `nativeMapsFromValueInputsPreserveFullGeometry`가 production 수정 전에 실패했다. `.omx/native-map-geometry-evidence/red.log`.
- **원인**: `NativePoolWitness`는 compatibility/cache용 partition-axis 추상화이며 `asAnchor`는 나머지 축을 1로 채운다. Closure가 VALUE_MAP 입력 때문에 durable 대신 native lineage를 게시할 때, 이미 계산한 전체 outputAnchor 대신 이 추상 witness를 정확한 출력 범위로 사용했다. Parser/HOP 크기 지정의 문제가 아니다.
- **수정/근거**: exact proof와 완전한 outputAnchor가 있는 native publication에서 canonical worker endpoint와 전체 출력 범위를 보존한다. Axis-only continuity·memo, VALUE_MAP과 DURABLE 구별, 기존 dynamic/unknown-shape 동작을 유지한다. 후보 삭제나 source-grounding 연산을 추가하지 않는다.
- **초기 검증**: 8x2 complete-space 클래스 7 tests 및 lineage normalization 테스트 PASS. ROW/COL·append·transpose 및 전체 인접 회귀를 확장 검증한다.
- **별도 기존 오류**: `DynamicNativeLayoutCompositionTest.transientReplayPreservesDynamicReverseAuthority`는 `One realization cannot mix unproven or physically distinct native worker pools`로 실패한다. 수정 전 `8f6bb285e5`에 해당하는 frozen publication main classes로 같은 테스트를 실행해 동일 오류·stack을 재현했다. 다른 dynamic 테스트 4개는 양쪽에서 PASS. 로그 `dynamic-stack.log`, `dynamic-baseline.log`; baseline classes는 `/grid/3/cofee-lm-sweep-mchoi-20260914/value-map-oracle-20261006/main-publication/frozen-inputs/main-classes`.
- **잔여 한계/회귀 위험**: unknown shape의 placeholder 의미를 전역 변경하는 수정은 아니다. 이미 알려진 output map을 잃는 오류만 고친다. Dynamic predecessor를 stale durable geometry로 승격시키거나 axis-only compatibility를 좁히지 않는지 기존 회귀로 확인한다.

- **최종 Java 검증**: 원래 8x2 fixture 7 tests PASS, COL8x4 행 높이 보존과 CBIND8x2→8x4 확장 2 tests PASS. 같은 새 COL/CBIND 테스트를 frozen baseline main classes로 실행하면 높이/폭 1로 각각 실패한다. 최종 인접 결과 집계 147 tests: 145 PASS, 기존 skip 1, baseline에서도 재현한 dynamic-reverse 오류 1. 최종 production 소스는 동일하며 별도 run 결과를 합친 집계다. 전체 suite가 all-green이라고 주장하지 않는다.
- **변경/검토**: production `PlacementRelationClosure` 5줄, 8x2 complete-space 테스트 복원 및 새 `NativeOutputGeometryTest`. 독립 read-only review CLEAR, diff whitespace PASS. 알려진 outputAnchor geometry만 복원하므로 memo/axis compatibility 또는 dynamic predecessor를 변경하지 않는다. 상세 보고서 `NATIVE_OUTPUT_GEOMETRY_2026-10-06.md`.
- **Docker 최종 검증**: frozen class hash PASS, 복원된 8x2 oracle 7 tests PASS, loop/function 2 cases PASS. Loop의 8x3·sum54·norm2 140 및 function fingerprint가 CP와 일치하고 runtime conversion 위반·audit error 0. 결과 `/grid/3/cofee-lm-sweep-mchoi-20260914/native-output-geometry-20261006/native-output-geometry/result.json`. 최종 source SHA와 Java 검증 manifest 일치.

## Dynamic native 배치 합성의 realization identity 충돌 — 수정 및 검증 완료

- **문제/환경**: `d7e88516a1`, PRIVATE_AGGREGATE, 2-worker ROW 입력, 양쪽 분기 `T=rev(A)` 뒤 `U=exp(T)`. `DynamicNativeLayoutCompositionTest.transientReplayPreservesDynamicReverseAuthority`가 realization 합성에서 실패했다. 이전 geometry 수정 전 baseline에서도 재현된 오류다.
- **재현/원인**: `.omx/dynamic-native-evidence/reproduction.log`. 일시적인 진단으로 동일 EXP owner+seed ID 아래 exact 8x2 witness와 endpoint-only 8x1 witness가 합쳐지는 것을 확인했다. 진단 코드는 제거했다. 입력 seed는 질의 identity이며, 여러 출력 배치/정확도 증명의 publication identity로 사용할 수 없다.
- **수정/의사결정 근거**: `PlacementRelationClosure`에서 generation query는 유지하고, publication lineage에 최종 출력 witness의 canonical layout과 exactness를 포함한다. TWrite alias도 기존 owner+pool ID에 exactness를 포함한다. 모든 지원 증명과 기존 merge invariant를 유지한다. source-grounding, DP factor, runtime fallback 또는 후보 삭제를 추가하지 않는다.
- **수정 파일**: `PlacementRelationClosure.java`, `DynamicNativeLayoutCompositionTest.java`; 실제 ROW reverse 실행을 위한 `run_joint_boundary_e2e.py`와 harness tests.
- **초기 검증**: 기존 dynamic 5건과 lineage/support union 7건, 총 12건 PASS. 정확/동적 혼합 분기 회귀 및 인접 18개 class 검증 진행 중. Python harness 20건 PASS.
- **잠재 회귀 위험/감지**: replay 중 key 변경으로 선택된 source reference를 잃거나 후보 증명이 축소될 위험을 complete-space/loop/support-union 테스트로 확인한다. 동적 authority를 durable 범위로 승격시키는 오류는 dynamic composition 검사와 2-worker Docker의 FED rev/exp 및 순서 민감 수치 비교로 감지한다.
- **잔여 범위**: 이 절의 최종 검증 전에는 전체 통과로 간주하지 않는다. 큰 factor 표현/메모리, P1/GLM 등 별도 통합 이슈는 이번 범위가 아니다.

### Dynamic native Docker 후속: REV lowering에서 출력 계약 누락 — 수정 및 검증 완료

- **증상**: 최초 Docker `dynamic-native-final`의 model proof 6 tests와 loop/function 2 cases는 PASS. 새 2-worker ROW reverse case는 analysis/DP 선택 후 `LOWERING_MISMATCH ... opcode=rev plannedPhysical=FED/FOUT/ROW actual=FED/NONE`로 실패했다. 원래 realization 합성 오류와 발생 단계가 다르다.
- **원인/해결 방향**: `Transform.getInstructions`가 FED REV의 `_fedOutput`을 직렬화하지 않는다. `ReorgFEDInstruction`의 parser는 이미 선택적 REV flag를 읽을 수 있다. 검사나 planner 계약을 완화하지 않고 emitter에서 계획된 FOUT/LOUT flag를 보존한다.
- **회귀**: `ReorgFEDInstructionFullTest.reverseLoweringPreservesExplicitOutputContract`로 Transform→FED parser 왕복에서 FOUT/LOUT 보존을 검증한다. 실제 2-worker Docker 수치 및 audit 검사로 lowering 이후 실행까지 확인한다.
- **범위/위험**: REV 직렬화만 변경한다. ROLL 등 다른 opcode의 별도 계약 문제로 범위를 넓히지 않는다. 기존 DIAG/TRANS/RESHAPE 직렬화 경로는 유지하며 인접 Reorg/Reshape unit tests로 확인한다.

- **최종 검증**: Java 20개 class 최신 결과 합계 230 tests / 229 PASS / 기존 skip 1 / failure·error 0, Python 20/20 PASS. 초기 신규 테스트의 잘못된 exact-output/support-clause 가정은 경계 관계에 맞게 교정했으며 production invariant는 유지했다. 기준 HEAD의 closure를 별도 컴파일하여 최종 dynamic 6 tests에 적용하면 원래/신규 branch 2건이 같은 합성 오류로 실패하고 수정본은 모두 통과한다.
- **최종 Docker**: `dynamic-native-runtimefix`의 dynamic ROW reverse·loop·function 3 cases PASS. model proof 6 tests 및 frozen class hash PASS, REV/EXP 실제 FED/FOUT/ROW audit MATCH. CP/FED 8x3 sum `124.07728482348034`, norm2 `1267.9351982067865`, weighted `571.954806162415` 일치, runtime conversion 위반·audit error 0.
- **잔여 이슈/회귀 위험**: 이 범위의 두 오류는 해결됐다. ROLL 직렬화 등 다른 opcode와 큰 factor/메모리 및 P1/GLM 이슈의 해결을 주장하지 않는다. replay 참조/후보 보존은 complete-space·loop·support-union 등 인접 회귀, 출력 flag는 FOUT/LOUT 왕복 검사로 감지한다. 독립 read-only 리뷰 CLEAR, diff whitespace PASS.
- **보고서/근거**: `docs/DYNAMIC_NATIVE_COMPOSITION_2026-10-06.md`, `.omx/dynamic-native-evidence/validation.json`. Docker: `/grid/3/cofee-lm-sweep-mchoi-20260914/dynamic-native-composition-20261006/dynamic-native-runtimefix/result.json`. 최종 Java source와 main/test class bytes는 성공한 frozen 빌드와 일치한다.


## Derived supply sharing의 최신 origin/main 통합 — 통합·검증 완료

- **환경/범위**: 별도 worktree `/home/mchoi/w1357-derived-supply-sharing-20261006`. 구현 커밋 `6b9ee37485`와 최신 원격 `0146f043e07ca445d9084257759aa78fe14ddf55`를 병합한다. 기존 worktree와 실행 중인 실험은 변경하지 않는다.
- **증상/원인**: 양쪽에서 `ExactPhysicalCostModel`의 materialization activation과 세션 문서를 수정하여 textual conflict가 생겼다. 최신 main은 branch guard가 있는 다중 alias origin, auxiliary FED 통신 비용, native geometry/realization 및 resource guard 수정을 포함한다.
- **해결/판단 근거**: upstream의 guarded alias creation과 auxiliary operator 비용을 유지하고, derived sharing의 `crossExecutionReuse`와 원본 source/version에 묶인 staged REFED upload를 함께 보존한다. 세션 기록은 양쪽 독립 항목을 모두 유지한다. Candidate legality/privacy/TW/TR 규칙을 완화하지 않는다.
- **수정 파일**: `ExactPhysicalCostModel.java`, 이 문서. 자동 병합된 `Dag`, recompile/branch normalization 및 native output authority 경로는 독립 read-only 검토했다.
- **검증**: 기능 회귀 38 classes / 328 cases 중 324 PASS·기존 skip 4, 확대 63 classes / 573 cases 중 567 PASS·기존 skip 5·기존 GLM 오류 1. 최신 main의 retirement/eviction을 포함한 OwnedRefedReuseTest 30/30 PASS. Python 20/20, test-compile/shell/diff PASS. Docker model proof 12/12 및 E2E 13/13 기대 결과 PASS, runtime conversion 위반 0. 상세 수치/재현은 게시 검증 보고서 참조.
- **추가 수정/회귀 위험**: dynamic native endpoint witness를 정확한 durable geometry로 오인하던 NativeSupplyRepresentation을 selected realization kind/lineage 보존으로 고치고 새 회귀를 통과했다. 기존 large-factor/GLM 등 다른 workload 이슈와 구별한다. 최종 production source/class는 성공한 Docker frozen 빌드와 일치하며 이후 변경한 테스트 1개는 별도로 검증했다.


### 게시 검증 중 확인한 GLM 오류의 baseline 귀속 — 기존 결함으로 확인

- **환경/증상**: 병합본 확대 Java 회귀 63 classes / 573 cases에서 567 PASS, 기존 skip 5, 오류 1. `actualBuiltinGlmDeadStraightenXIsZeroAndCgRemainsPositive`의 derived FOUT anchor authority 오류다.
- **원인/판단 근거**: `0146f043e0` frozen baseline의 tracked Java source 3,549개와 Git blob을 전수 대조해 모두 일치함을 확인했다. 같은 method 1개만 실행하여 동일 오류를 재현했다. Placement graph/closure 단계이며 이번 physical cost/model 생성 전이다.
- **해결/수정 파일**: 이 게시 작업에서 기존 GLM authority 규칙을 변경하거나 테스트를 skip하지 않는다. 재현과 귀속을 `DERIVED_SUPPLY_MAIN_PUBLICATION_2026-10-06_KO.md` 및 별도 evidence에 기록한다. 현재 기능의 38 classes / 328 cases는 324 PASS, 기존 skip 4, 실패/오류 0이다.
- **추가 main 통합**: 검증 도중 들어온 `e8e42e93fa`까지의 문서/retirement·eviction 테스트 커밋도 병합했다. Production source 변경은 없고 최종 `OwnedRefedReuseTest` 30/30 PASS다.
- **잔여 문제/회귀 위험**: GLM 함수 경계 authority 결함은 남아 있다. 이번 기능의 회귀와 분리해 같은 baseline method로 감지하며, 전체 저장소가 all-green이라고 주장하지 않는다. Immutable source-sharing과 cache retirement 후 재생성의 별도 계약을 유지한다.

## Dyadic certificate의 unresolved-union transport 기대값 — 해결

- **환경/재현**: 최신 `origin/main`의 `e8e42e93fa`에서 별도 worktree를 생성했다. `mvn -q -DskipTests=false -Djacoco.skip=true -Dtest-forkCount=1 -Dtest-perCoreThreadCount=false -Dtest=ExactPhysicalDyadicCertificateTest test` 결과 5 tests 중 1 failure, error/skip 0. `allFourConstructionCasesCarryTypedMetadataWithoutDescriptorParsing`에서 `expected:<IDENTITY> but was:<ONE_MONETARY_TABLE>`를 재현했다.
- **원인**: 비용 모델 통합 후 unresolved union도 event quotient의 보조 제약과 하나의 monetary table로 인코딩한다. 테스트만 과거 canonical factor 직접 사용을 뜻하는 `IDENTITY`를 기대했다. 수치 비용 오류가 확인된 것은 아니다.
- **해결/의사결정 근거**: production planner/runtime/비용 모델은 그대로 두고 테스트 계약을 현재 표현에 맞춘다. fixture가 실제 unresolved partition인지 확인하고 `ONE_MONETARY_TABLE`을 요구한다. 기존 canonical/encoded maximum의 raw-bit 동등성을 유지하며 monetary table이 ordinary factor의 유일 원소이고 solver factor 목록에 정확히 한 번 포함되는지, 나머지 보조 제약이 0 또는 +∞인지 검사한다. 실제 physical surface에서 canonical factor 및 solver ordinal과 동일한 `Identity`가 존재하는지도 명시적으로 검증한다. Descriptor 문자열 파싱으로 분기를 판정하지 않는다.
- **수정 파일**: `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalDyadicCertificateTest.java`, 이 문서.
- **검증 완료**: 아래 명령으로 8개 클래스 / 49 tests 모두 PASS, failure/error/skip 0. Event quotient의 64개 원래 변수 할당에 대한 canonical 비용 raw-bit 동등성, OR decomposition, activation 비용 및 dyadic 인증을 포함한다. `git diff --check`도 PASS. 원본 실패 로그/XML과 수정본 로그/XML/요약 JSON은 `/grid/3/cofee-lm-sweep-mchoi-20260914/dyadic-certificate-test-20261006/`에 보존한다.

  ```bash
  mvn -q -DskipTests=false -Djacoco.skip=true \
    -Dtest-forkCount=1 -Dtest-perCoreThreadCount=false \
    -Dtest=ExactPhysicalDyadicCertificateTest,ExactFunctionAliasActivationEncodingTest,ExactActivationClassFactorDecompositionTest,ExactMaterializationActivationTest,ExactActivationMaterializationCostTest,ExactDyadicCostsTest,ExactDyadicZeroElisionTest,ExactActivationIndependentOracleTest test
  ```

- **잔여 이슈/잠재 회귀 위험**: production 변경은 없다. 이 테스트 수정으로 다른 통합 실패의 해결이나 실행시간 개선을 주장하지 않는다. metadata 기대값만 변경하여 잘못된 과금을 숨길 위험은 위 단일 과금·hard constraint·기존 exhaustive equivalence 검사로 감지한다.

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

## Shared repair·비용 후속 변경의 최신 main 통합 및 공개 — 통합 검증 완료

- **요청/환경**: 사용자의 commit 및 `origin/main` push 요청으로 검증한 비용/StepLM/shared-repair 변경을 `33dfbdc4c8`에 커밋했다. 작업 중 진전된 원격 `0146f043e0`을 병합하며 joint-input/동적 native authority/resource-policy 변경을 보존한다. 이전 645건과 A/B workload 기록은 병합 전 source의 증거로 유지하고 통합 source를 별도로 검증한다.
- **충돌 해결**: main의 proactive `expandRepairBlock`과 이번 `unconditionalDomains` 및 prepare 시의 typed batched boundary 검사를 함께 유지한다. 선행 확장 후에도 unsupported 경계가 발견되면 typed 경로로 다시 확장한다. root/block budget 분리, conditional preflight/portfolio, `lastFallbackReason` 및 원래 값 projection을 보존했다. session 문서는 양쪽 기록을 합쳤다.
- **자동 병합의 의미적 결함 수정**: `ExactPhysicalCostModel.fedCostProjection`에 network-only 보조 비용과 full mixed auxiliary 비용이 함께 들어가 동일 RTT/payload가 이중 과금되는 것을 독립 검토에서 발견했다. 실제 runtime stage 전체를 소유하는 mixed 모델만 한 번 과금하고 incoming `ExactAuxiliaryCommunicationCostTest`의 기대값을 전체 보조 단계의 단일 소유 계약에 맞췄다. network-only helper/test 자체와 CTABLE GET/cache·per-execution PUT 및 MMChain ownership은 보존했다.
- **resource 계약 정렬**: 이전 early support-table 재구축의 raw `double[]` 할당을 main의 `PlannerResourceGuard.checkAdditionalCells/allocateDoubles`로 맞췄다. 이미 frozen된 unary/binary support factor의 domain을 줄이는 경로이므로 cell product는 원래 검증된 크기를 넘지 않는다. 큰 conceptual lazy product는 여전히 support 축소 후 checked materialization을 거친다.
- **의사결정 근거/위험**: 후보 허용 규칙이나 runtime fallback을 변경하지 않는다. 독립 solver/cost 검토 후 merged Maven package가 성공했다. 이전 회귀에 incoming resource/memory/Global exact/auxiliary cost/joint cost 검사를 더한 96개 클래스와 별도 Docker compile smoke로 중복 과금, mapping 손실, resource 오류 분류 및 최신 candidate 공간과의 충돌을 검사한다. 통합 뒤 관측되는 실패는 최신 원격과 비교하여 원인을 구분하며 과거 통과 기록으로 덮지 않는다.
- **검증 artifact**: `/home/mchoi/cost-followup-20261006/main-publication/`에 원본 source SHA, build 결과, 정확한 명령과 로그를 보관한다. 최종 결과는 아래에 추가한다.
- **통합 CTABLE oracle 정정**: 처음 실행한 96개 클래스 733건 중 6건이 실패했다. 그중 새 loop CTABLE 검사는 transfer key의 endpoint scope가 canonical contribution 하나와 반드시 일치한다는 가정으로 실패했다. main의 joint/activation factorization은 같은 scope에 여러 contribution과 같은 retained source의 대체 key를 허용한다. production을 바꾸지 않고 관련 contribution을 중복 없이 평가·합산하도록 검사를 고쳤다. 독립적으로 계산한 ROW reusable download 1회의 비용과 정확히 일치하는지, loop 1/2/10회의 cold GET 불변성과 invocation 비용 비례 관계를 유지한다. 별도 컴파일의 CTABLE 4건이 통과했다.
- **원격 baseline 대조**: 기존 StepLM 통합 실패는 현재 원격 `0146f043e0`의 production 7개 차이 파일을 모두 복원한 격리 class overlay에서도 새로 재현했다. 오류는 solver 진입 전 `PlacementRelationClosure`의 CFG transient candidate closure 비수렴이며, 이전에 해결한 unsupported-boundary overflow와 발생 단계가 다르다. 나머지 기존 실패도 같은 원격 overlay에서 메서드별로 대조한다. 최초 실패 로그는 보존하며 제외 목록을 명시한 후속 검증과 구분한다.
- **Docker 통합 smoke**: 고정 image와 기존 A/B의 pinned builtin tree로 StepLM W3·PCA W3 compile/lowering 2/2 성공. training runtime은 실행하지 않았다. 최초 fixture 복사에서 빠진 `runtime/scripts` 때문에 StepLM이 다른 builtin signature를 읽은 harness 오류는 원본 tree 1,110개 파일의 SHA를 확인해 복원했다. workload 인자나 production을 바꾸지 않았으며 실패 시도도 artifact에 보존한다.
- **L2SVM 테스트 격리 정정**: 기존 main에서도 실패하는 메서드 5개만 분리한 첫 재검증은 728건 중 727건 통과, L2SVM의 `Expected one committed program` 검사 1건 실패였다. 이 검사는 전역 `PlacementEmissionTransaction` receipt 수를 읽으면서 스스로 초기화하지 않아 앞선 테스트 2개의 기록을 포함했다. 이전에 우연히 실행되던 ALS 테스트의 reset에 의존한 것이다. L2SVM 시작/종료에 기존 `resetForTesting`을 추가하고 정확한 1개 receipt, 선택 비용·배치·emission 검사를 유지한다. production은 변경하지 않았으며 실패 실행은 `before-l2-isolation/`에 보존한다.
- **최종 baseline 판정**: 원격의 모든 production 차이 7개 파일을 origin blob으로 복원한 격리 overlay와 동일한 test source로 5개 메서드의 실패를 확인했다. 4개는 최초 통합 실행의 오류 signature가 일치한다. ALS FedAll은 임시 입력 경로가 program fingerprint/greedy tie에 영향을 줄 수 있어 외부 진단 fixture의 절대 경로를 고정해 재대조했다. 동일 fixture에서 origin과 통합본은 `empty owned-row domain`으로 실패하며 로그가 byte 단위로 같다. 최초 통합의 `no common relocation pool` 문구까지 원격에서 동일하게 재현했다고 주장하지 않는다.
- **최종 검증**: Java 17 Maven package 성공. 원래 733건을 실행한 최초 실패 기록과 원격에서 재현된 5개 메서드의 명시적 제외 목록을 보존했다. CTABLE/L2SVM 검사 정정 뒤 같은 목록으로 **96개 클래스의 나머지 728/728 통과**, failure/ignore 0이다. 전체 733건 무실패로 보고하지 않는다. 최종 source SHA가 빌드 때와 일치하고 production/JAR는 Docker 2건 성공 시점과 동일하다. [통합 검증 기록](experiments/shared-repair-reuse-20261006/main-publication.json)에 명령, runner, 실패·수정·재검증 이력, source/evidence SHA와 baseline 대조를 기록했다.
- **잔여 범위**: main의 StepLM CFG closure 비수렴, ALS FedAll greedy conflict, ALS/StepLM canonical factor overflow, LogReg privacy placement 실패 5건은 남아 있다. 이전 unsupported-boundary repair overflow 해결이나 병합 전 645건 통과와 구분한다. 이번 publication 검증은 training runtime 또는 latency 개선을 주장하지 않는다.
- **푸시 직전 main 동기화**: 검증 중 추가된 `db1064c3c9`까지의 main 변경도 보존한다. `0146f043e0` 이후의 차이는 문서와 `OwnedRefedReuseTest`·`ExactPhysicalDyadicCertificateTest`뿐이며 production 변경은 없다. 두 최종 test source를 별도 컴파일해 **26/26 통과**했고, 나머지 Java source는 728건 gate의 SHA와 일치한다. 세션 문서의 양쪽 추가 내용을 모두 유지했으며 [통합 기록](experiments/shared-repair-reuse-20261006/main-publication.json)에 마지막 source/명령/로그 hash를 추가했다.


### 추가 원격 비용/repair 수정 통합 — 통합·검증 완료

- **환경/증상**: 게시 직전 원격 main이 `79c26b40ce`로 진행했다. `e8e42e93fa` 이후 production auxiliary/CTABLE/MMCHAIN 비용 및 shared reduction repair 변경이 포함돼 새 검증이 필요하다.
- **해결/판단 근거**: `ExactPhysicalCostModel`은 자동 병합됐으며 operator-owned auxiliary PUT/통신과 supply-owned explicit movement, CTABLE cold GET collector 및 source/version lifetime을 독립 검토했다. 같은 이동의 중복 과금이나 candidate/constraint 완화는 발견하지 않았다. 세션 문서는 양쪽 기록을 모두 보존했다.
- **최종 검증**: 관련 79개 클래스 708 cases 중 703 PASS, 기존 skip 5, 실패/오류 0. 새 frozen Docker `invariant-fout-sharing-main-r2`의 proof 12/12, DML E2E 13/13 기대 결과 PASS, runtime conversion 위반 0. 실제 invariant GET/PUT 1/1·updated 3/3, 최종 main/test source/class 전부 SHA 일치. 이미 `0146`에서도 재현된 전체 GLM 오류는 앞선 확대 회귀/귀속 기록에 남기며 이번 관련 suite에는 포함하지 않았다.
- **잔여 문제/회귀 위험**: 상이한 creation/consumer profile과 CTABLE per-call preparation의 소유권을 혼동하면 중복/누락 과금이 생길 수 있다. CTABLE·native upload·공유·canonical 테스트와 real-worker 1×/N× 검증으로 감지한다. 새 dependency, solver mode, runtime fallback은 추가하지 않는다.

## 후보 곱집합 확장과 지연 합법성 검사 — 수정·ML 검증 완료, dense 표현 한계 유지

- **문제/관측**: 최신 `ada24ffd4b`의 실제 builtin multiLogReg는 baseline과 boundary-seed 구현 모두 300초 제한에 걸렸다. 후보 실행 전 `SharedRegionalPreparation.initialize → ExactPhysicalReducedSolver.reduce → freezeValidatedFactor → RepresentativeTruthEvaluator → Grounding.rows`에서 시간이 소요됨을 candidate thread dump로 확인했다. seed checkpoint는 출력되지 않았다. 정확한 최대 factor 차원/셀 수는 이 실행에서 계측하지 않았으므로 수치를 추정하지 않는다.
- **수정 원칙**: 개별 상태의 합법성과 조합의 합법성을 구분한다. 이미 증명된 전체 배치 일관성/상관관계 제약으로 확장 불가능한 prefix만 제거한다. 미할당 값은 불법이 아니라 unknown이다. 비싼 합법 후보를 임의로 삭제하지 않으며 비용 pruning은 기존 검증된 lower bound/정확 산술 계약 안에서만 수행한다. dense 저장 크기·stride·resource 예산 계산의 곱셈은 후보 생성과 구분한다.
- **사전 조사/최소 변경 계획**: (1) 일반 lazy callback의 방문/오류 계약은 유지하고, 순수 hard factor에 한해 모든 completion의 값이 0 또는 infinity임을 증명하는 opt-in partial evaluator를 추가한다. observation representative 및 지원 domain 축소에서 증명을 보존한다. (2) joint physical cost rows의 상관관계와 native authority의 동일 anchor 검사를 leaf에서 prefix로 당긴다. (3) boundary reference 조합의 동일 decision-owner 상충을 확장 중 제거하되 서로 다른 CFG writer의 이질적인 합법 worker pool은 유지한다. (4) 나머지 FType oracle/solver separator/grounded replay 경로를 분류하고 실제 합법 제약 없이 곱셈 자체를 지우지 않는다.
- **회귀 계획**: production 수정 전 기존 동작/탐색량을 고정한다. 작은 완전 열거와 결과 집합·최적 비용·동률 선택을 비교하고, 상관된 불법 prefix에서 callback/확장 감소를 검사한다. generic lazy 예외 순서, resource preflight, observation aliases, 축소 후 원래 값 mapping, boundary fixed→free 복구를 유지한다. 이후 planner/cost 회귀와 `run_LAN_docker.sh`의 보호된 X를 쓰는 실제 l2svm/lmCG/multiLogReg를 다시 실행한다.
- **최신 main 선행 검증**: 이전 boundary-seed 구현은 Java 24개 클래스 240건, Python 25건 통과. 동일 image/input/classes 조건의 builtin l2svm/lmCG는 CP/FED 전체 계수 일치, audit 오류 0. planner 시간은 l2svm 11.111→2.737초, lmCG 1.586→1.085초였다. 단일 공유 호스트 측정이며 통계적인 속도 향상으로 일반화하지 않는다. logreg는 양쪽 모두 실패(124)로 기록했다. 근거: `docs/experiments/boundary-seed-20261006/latest-main-ml-validation.json`.
- **잔여 이슈/회귀 위험**: black-box factor의 unknown prefix를 불법으로 취급하면 합법해를 잃는다. hard proof가 observation/domain remapping에서 손실되면 최적화가 무효해진다. dense 메모리 한도는 별도로 남을 수 있다. partial proof를 다른 조건부 문제에 캐시하지 않으며 완전 열거 parity와 resource/동률 회귀로 감지한다. 현재 이 항목은 계획·조사 기록이며 구현 완료 주장이 아니다.
- **구현/중간 검증**: 위 계획의 partial hard proof와 authority/source-reference/joint-row prefix 검사를 구현했다. [경로별 조사 및 계약](LEGAL_CANDIDATE_EXPANSION_2026-10-06_KO.md)에 수정한 경로와 남는 독립적 product·generic oracle·dense 저장을 구분했다. authority regression은 이전 class에서 suffix 방문 20(기대 0)으로 실패했다. 새 production을 별도 디렉터리에 직접 컴파일한 physical proof 및 authority 14건은 PASS이며 branch/loop/function hard table의 raw-bit parity를 확인했다. 일반 solver proof 8건과 source-row random oracle 200회도 별도 검증했다.
- **검증 중 build 격리 문제**: 병렬 하위 작업이 공용 Maven target을 재컴파일하여 최신 source보다 뒤 timestamp의 오래된 `ExactPhysicalModel.class`가 남았다. 최초 통합 실행은 main을 `Nothing to compile`로 건너뛰어 새 capability 4건과 authority suffix 1건이 실패했다(329건 중 5 실패). 이것을 production 검증 완료로 사용하지 않는다. 원래 로그를 `target/candidate-pruning-evidence/regression.log`에 보존하고 빌드를 하나로 직렬화한 뒤 1,642개 main source 전체 재컴파일을 확인했다. 최종 source SHA snapshot과 별도 `regression-final.log`로 재검증한다. 다른 worktree의 실행은 건드리지 않았다.
- **최종 회귀/검토**: Java **41개 클래스 329/329 PASS**, failure/error/skip 0, JAR 빌드 및 `git diff --check` PASS. production/test source SHA가 빌드 snapshot 및 frozen source와 일치한다. 독립 정적 검토에서 합법해 손실의 반례나 generic callback/resource 계약 회귀는 발견되지 않았다. dense table의 전체 논리 공간은 남는다는 한계를 명시했다.
- **최종 Docker 학습**: `legal-prefix-candidate-01`은 실제 builtin multiLogReg/l2svm/lmCG **3/3 PASS**, class preflight·physical proof 10건 PASS. 16/8/8 전체 계수가 CP와 일치(최대 절대 오차 각각 2.23e-16/8.42e-17/1.23e-15 미만). runtime audit mismatch/missing 및 runtime conversion 위반 0. 변경 전 300초 process timeout이던 logreg는 compilation 89.965초, execution 3.869초로 완료했다. 이 중 common analysis 43.897초, planner 44.728초다.
- **작업량 근거**: logreg의 25,006,592-cell factor에서 partialCalls 2,547,709, non-leaf proof 340개/subtreeCells 22,602,112, 준비 시간 20.175초를 기록했다. 대부분은 합법 zero 영역의 상수 증명이며 불법 후보 삭제 수와 구별한다. l2svm/lmCG planner는 직전 seed-only candidate의 2.737/1.085초에서 2.127/0.857초이며 최종 modeled 비용은 동일하다. 단일 공유 호스트 측정이다.
- **최종 근거/잔여 범위**: `docs/experiments/boundary-seed-20261006/legal-prefix-ml-validation.json` 및 위 설계 문서에 결과·source 8개·입력/image/dependency 대조·raw evidence 경로를 기록했다. logreg의 training timeout은 이 fixture에서 해결됐다. generic FType oracle, 독립적으로 모두 합법인 product, dense 테이블 저장과 exact quotient/최소화의 조합 비교는 남아 있다. 모든 곱셈/모든 불법 table cell을 제거한 구현으로 표현하지 않는다.

## Local DP 초기 seed의 경계 조합 최적화 분석

- **상태**: 분석 및 문서화 완료. 초기 경계 조합 개선은 미구현 제안이다.
- **문제/환경**: 사용자 요청으로 로컬 `origin/main`의 `0146f043e07ca445d9084257759aa78fe14ddf55`에서 초기 seed가 greedy인지, cluster별 최적해와 경계 조합만으로 개선할 수 있는지 확인했다. 다른 worktree의 후속 변경은 제외했다.
- **원인/관측**: Seed는 producer-before-consumer 순차 greedy와 조건부 exact hard-conflict repair로 구성된다. 일반 local/deferred block 목록은 비어 있고 revisit 횟수는 0이다. 현재 incremental merge는 모든 남은 외부 경계 조합에 대한 새 메시지를 만든다.
- **해결 방향/변경 요약**: 경계 조건별 cluster 최적 비용을 유지하고, 선택한 공유 경계들을 공동 탐색해 실행 가능한 seed를 개선하는 방법을 문서화했다. 관련 모든 factor와 auxiliary 관계를 반영하고, 전체 canonical 검증 후 비용이 감소하는 계획만 채택한다. 현재 자원 제약 이후의 conditional exact 개선과 초기 개선 제안을 구별했다.
- **의사결정 근거/적용 원칙**: Planner의 탐색 순서와 incumbent 개선에 관한 제안이다. Oracle/runtime 규칙, privacy, 후보 공간 및 비용식을 변경하지 않으며 runtime fallback을 추가하지 않는다. Factor 소유권과 lower-bound 인증을 보존한다.
- **수정 파일**: `docs/LOCAL_DP_SEED_BOUNDARY_OPTIMIZATION_2026-10-06_KO.md`, 이 세션 문서. Production/test 코드 변경 없음.
- **검증 방법/결과**: HEAD와 로컬 origin/main SHA 일치, production 경로 및 기존 regression assertion 확인. 문서의 소스 링크 20개와 줄 번호, 기준 SHA, code fence 및 공백 검사 PASS. `git diff --check` PASS. Java 테스트 실행 및 성능 실험은 이 문서화 범위에 포함하지 않는다.
- **잔여 이슈**: 초기 개선은 구현·측정 전이며 seed 품질, 최종 비용 및 총 planning 시간의 개선 폭은 미확정이다. 전역 최적성을 주장하지 않는다.
- **잠재 회귀 위험/감지**: 향후 구현에서 crossing factor 누락, 비용 중복, 경계 변수 상관관계 손실, 잘못된 lower-bound 갱신 가능성이 있다. 전체 hard/canonical 검증, 여러 변수 동시 변경 fixture, factor cover와 bound 유지 검사로 감지해야 한다. 이번 변경은 문서에 한정된다.

### 초기 경계 조합 개선 구현과 검증

- **상태**: 사용자 후속 요청으로 구현 및 회귀·Docker 검증 완료.
- **문제/기준**: 동일한 `origin/main` 기준 커밋에서 일반 비용 개선 없이 생성된 seed를 첫 persistent merge 전에 개선한다.
- **구현/근거**: 초기 factor cover와 lower bound 계산 후 owned-factor의 현재 비용과 조건부 최솟값 차이로 neighborhood를 구성한다. 기존 original 두 단계 인접 영역 및 auxiliary closure와 `SharedRegionalPreparation`의 조건부 exact solver를 재사용한다. 외부 original 선택은 고정하고 crossing factor를 포함하며, 새 persistent boundary table은 생성하지 않는다. 임시 conditioned/VE 테이블은 사용한다. `SEED_BOUNDARY` checkpoint 후 기존 merge 경로를 계속한다.
- **발견한 오류와 수정**: Singleton source와 auxiliary만 가진 비용 owner도 mutable consumer로 연결될 수 있으므로 direct original 집합이 비었다는 이유로 closure를 건너뛰지 않는다. 조건부 solver가 생략한 상수와 누산 순서 때문에 local tie가 전체 canonical 비용 악화가 될 수 있어, optional 조건부 후보는 전체 검증 후 strict improvement만 채택한다. Canonical 불일치와 lower-bound 위반 검사는 유지한다.
- **변경 파일**: `IncrementalRegionalOptimizer.java`, `RegionalSearchProblem.java`의 package constructor 접근성, 새 `IncrementalBoundarySeedTest.java`, 기존 `IncrementalRegionalOptimizerTest.java` 및 분석 문서.
- **초기 검증**: 변경 전 신규 기본 회귀 6건은 `SEED_BOUNDARY` 부재로 모두 실패했다. 최초 focused 실행 61 tests 중 신규 8건은 PASS였고, 기존 체크포인트/개선 시점 기대값 2건과 위 canonical tie 1건을 확인해 수정했다. 전체 재검증 결과는 아래에 기록한다.
- **최종 회귀/빌드**: 17 classes / 176 tests, 실패·오류·제외 0, `test jar:jar` 성공. 신규 11건은 경계 타협 9→4, 세 original equality 15→0, auxiliary-only owner 10→3, 네 original의 외부 고정, private 내부 복원, 중복 비용, deterministic tie, gap 종료와 자원 거절을 검증한다. 기존 canonical 수치 동률 regression도 통과했다. `git diff --check` PASS.
- **회귀 판별력**: 별도 테스트용 class에 auxiliary closure 전 empty-original skip을 복원하면 11건 중 auxiliary-only 사례만 기대 3/실제 10으로 실패한다. Production 파일이나 최종 classes에 변형을 적용하지 않았다. `target/boundary-seed-evidence/aux-owner-mutant.log`.
- **Docker 최종 검증**: `run_LAN_docker.sh --joint-boundary-e2e`에서 frozen class preflight, model proof 10 tests, loop/function 실제 worker 2 cases PASS. CP/FED 수치 fingerprint 일치, audit error 0, runtime conversion 위반 0. 변경한 source/class 4쌍의 SHA가 frozen inputs와 일치한다. `/grid/3/cofee-lm-sweep-mchoi-20260914/boundary-seed-20261006/boundary-seed-final/result.json`.
- **재현/근거**: `docs/experiments/boundary-seed-20261006/validation.json`에 Maven/Docker 명령, 클래스별 결과, source/class/JAR SHA를 저장했다. Raw build/test 로그는 `target/boundary-seed-evidence/`이며 상세 설명은 `docs/LOCAL_DP_SEED_BOUNDARY_OPTIMIZATION_2026-10-06_KO.md`다.
- **잔여 범위/위험**: 후보 공간/runtime 규칙을 바꾸거나 전역 최적을 보장하는 변경이 아니다. 큰 correlated/auxiliary 영역의 초기 conditional solve가 planning 시간을 늘릴 수 있다. 전체 workload 성능 비교는 수행하지 않았다. 독립 리뷰에서 정확성 차단 문제는 없으며, 지연 비용은 측정할 후속 범위로 남는다.

### ML training 후속 검증 — 완료, 내장 multiLogReg 병목 미해결

- **요청/기준**: 실제 학습 workload로 초기 경계 개선을 검증한다. 비교 기준은 구현 당시 `origin/main`인 `0146f043e07ca445d9084257759aa78fe14ddf55`로 고정한다. 검증 시작 시 remote-tracking `origin/main`은 `79c26b40cebae479a191f9e406586b5b74193d17`이므로 최신 원격 전체 변경과의 비교라고 주장하지 않는다.
- **실행 경로 문제/대응**: 정식 `--campaign`의 `/home/mchoi/cofee-evaluation` 외부 모듈이 없다. 저장된 staged 데이터는 존재하지만 campaign 자체를 실행할 수 없어, 기존 `run_LAN_docker.sh --joint-boundary-e2e`에 opt-in 실제 학습 케이스를 추가했다. Docker 외 실행 결과는 workload 실험 근거로 사용하지 않는다.
- **검증 조건**: 192×8 결정적 입력, PRIVATE_AGGREGATE X의 3-worker ROW 분할, 로컬 public label, 동일 입력의 CP 수치 기준. `multiLogReg`, `l2svm`, `lmCG`를 실제 호출하고 전체 모델 계수·실행 audit·DP checkpoint를 저장한다. 별도 public-only FED 실험은 수행하지 않는다. 단일 컨테이너 loopback·기본 비용 상수이며 LAN 실측이나 대규모 학습 성능 검증이 아니다.
- **변경 전 빌드**: 현재 class/resource tree를 복사하고 변경한 production 두 source family만 기준 HEAD에서 다시 컴파일했다. 나머지 byte는 수정본과 같다. `target/boundary-seed-evidence/ml-baseline/provenance.json`; baseline physical proof 10 tests PASS.
- **초기 관측**: 수정본 builtin logreg CP 학습은 완료했다. FED 실행의 136초 시점 thread dump는 `JointValueMapRelations.Grounding.rows` → hard factor materialization → `RegionalSearchProblem.reducedRoot`에 위치하며, 새 초기 경계 개선에는 아직 도달하지 않았다. 변경 전 같은 조건과 대조하기 전에는 seed 변경의 회귀나 기존 오류로 단정하지 않는다.
- **수정 파일/위험**: Docker Python harness와 해당 테스트만 확장한다. Planner/oracle/runtime/privacy 규칙을 완화하지 않는다. 수치 비교, nonzero 모델, 학습 손실 감소(추가 단순 반복 학습 케이스)로 잘못된 성공 판정을 감지한다. 최종 결과는 후속 항목에 기록한다.

- **최종 결과**: 내장 L2SVM·lmCG 및 logistic/L2 squared-hinge/least-squares GD(각 20회)의 5개 학습이 수정본과 baseline 양쪽에서 PASS. 각 모델 8개 계수를 전부 비교해 최대 절대 오차 1.23e-15 이하, GD 손실 감소, 실행 audit missing/mismatch 및 runtime conversion 위반 0. 내장 두 학습은 FED 행렬곱이 각각 17회 실행됐다. 세 Docker run 모두 class preflight 및 physical model proof 10건 PASS. Python harness 25 tests·py_compile·diff 공백 검사 PASS.
- **효과/한계**: 초기 seed modeled 비용은 66.07–84.22% 감소했다. L2SVM과 lmCG는 이 단계에서 gap에 도달해 추가 persistent merge가 없었다. 완료한 5개 케이스의 최종 modeled 비용은 baseline과 같다. 1회 대조 planner 시간은 L2SVM 6.279→2.216초, lmCG 1.020→0.888초, logistic GD 1.488→2.877초, L2 GD 3.164→4.607초, LS GD 1.749→3.094초였다. Seed 품질 개선을 전반적인 planning 속도 개선으로 해석하지 않는다. 간단한 GD에서는 초기 조건부 풀이의 추가 비용이 우세했다.
- **미해결 병목의 귀속**: 내장 `multiLogReg`는 CP만 완료, FED는 baseline과 수정본 모두 300초 timeout(exit 124). 양쪽 thread dump가 `Grounding.rows`→hard factor freezing→`reducedRoot`이며 INITIAL_BOUND/SEED_BOUNDARY checkpoint가 없다. 새 pass 이전 공통 단계의 병목으로 분리한다. runtime fallback이나 후보 삭제로 우회하지 않았다. Builtin logreg를 포함한 전체 suite 결과는 FAILED이고, 완료된 개별 5개 케이스 PASS와 구별한다.
- **근거/재현**: `/grid/3/cofee-lm-sweep-mchoi-20260914/boundary-seed-ml-20261006/{ml-candidate-01,ml-gd-candidate-01,ml-baseline-01}`의 raw logs·model CSV·frozen source/class·manifest. `docs/experiments/boundary-seed-20261006/ml-validation.json`과 상세 설계 문서의 ML training 절에 비용·시간·hash 대조를 기록했다. 동일 source/class/dependencies와 입력/스크립트 hash를 확인했다. ML 검증 동안 production 변경은 없고 이전 176-test 검증 source SHA와 일치한다.
- **잔여 범위/잠재 회귀 감지**: staged campaign 의존성 부재, 소규모 합성 데이터 및 단일 컨테이너 loopback, 1회씩 실행·trace/audit·공유 호스트라는 한계가 있다. 전체 데이터/네트워크 성능 검증과 builtin logreg의 factor 준비 병목은 미해결이다. 향후 초기 neighborhood 작업량을 조정하면 같은 수치·factor-cover·canonical 검증을 유지하면서 특히 GD planning 지연을 재측정해야 한다.

### 최신 origin/main fetch·통합 후 내장 ML 재검증 — 완료, logreg timeout 재현

- **요청/기준**: 사용자의 최신 main 갱신 후 재실행 요청에 따라 `git fetch origin main`으로 `ada24ffd4be4d17240f32f5bd1c2b5a98e1a0c8a`를 확인했다. 현재 branch를 해당 커밋으로 fast-forward하고 기존 seed 개선·회귀·하네스·문서를 보존했다. 작업 전 diff·파일 archive·SHA와 통합 기록은 `target/boundary-seed-evidence/latest-main/integration.json` 및 같은 디렉터리의 backup에 있다.
- **통합 충돌/해결**: 세션 문서의 독립 append만 충돌했다. 최신 main 기록과 이 기능의 기록을 모두 유지했다. 기존 feature 파일들은 통합 전 SHA와 동일하며 production merge 충돌이나 임의의 runtime/candidate 완화는 없다.
- **호환성/판단 근거**: 최신 main은 shared root의 unary/binary support reduction, hard-conflict repair 및 native supply 비용/재사용을 변경했다. 따라서 이전 커밋의 비용을 새 seed 개선의 대조값으로 사용하지 않는다. 최신 main baseline과 같은 main 위에 초기 경계 pass를 적용한 candidate를 비교한다. Baseline의 두 source family만 원본 HEAD에서 재컴파일했으며 나머지 class/resource byte는 candidate와 같다.
- **초기 검증**: 새 reduction/repair·native sharing 회귀를 포함해 Java 24 classes / 240 tests 모두 PASS, failure/error/skip 0, JAR 재빌드 성공. Python harness 25 tests·py_compile·diff 공백 검사 PASS. `target/boundary-seed-evidence/latest-main/{regression.json,regression.log,harness-tests.log}`.
- **실험 범위/잠재 위험**: 같은 192×8 입력·3-worker·PRIVATE_AGGREGATE X·로컬 labels와 Docker 설정으로 내장 `l2svm`, `lmCG`, `multiLogReg`만 재실행한다. GD 보조 스크립트는 이번 실행 대상이 아니다. 단일 대조 실행이며 새 root 준비가 seed 전으로 이동했으므로 전체 planner interval을 비교한다. 최종 수치·audit·시간·timeout 여부를 후속 기록한다.
- **최종 결과**: `latest-main-baseline-01`/`latest-main-candidate-01`의 l2svm·lmCG 2/2 CP/FED 전체 계수 일치, model proof 10건·class preflight PASS, audit 오류/변환 위반 0. planner 시간은 l2svm 11.111→2.737초, lmCG 1.586→1.085초다. 최종 modeled 비용은 양쪽 각각 21.385960545366007ms 및 21.2628479582568ms로 같다. multiLogReg는 양쪽 CP 완료, FED 300초 timeout(124), seed checkpoint 없음. 전체 suite 상태는 FAILED다.
- **증거/한계**: `docs/experiments/boundary-seed-20261006/latest-main-ml-validation.json`에 원문·hash 대조·회귀·시간을 보존했다. 두 run의 입력/스크립트/image 및 두 optimizer family 외 class/resource/dependency가 동일함을 검증했다. 이후 후보 pruning 변경 전의 동결 결과이며 소규모·단일 대조·공유 호스트 측정이다.

## ALS FedAll의 greedy 후보 충돌 — 수정 및 소형 실제 실행 검증 완료

- **요청/환경**: 최신 `origin/main` `79c26b40ce`에서 별도 worktree를 만들었다. 기존 workspace는 수정하지 않는다. 범위는 ALS FedAll selection 충돌이며 큰 exact cost-factor overflow와 STEP-LM closure는 별개다. 실제 실행 검증은 Docker의 작은 50×20 ALS로 제한한다.
- **재현**: `CampaignBG014AlsPartitionedComputeCostRedTest#singleWorkerFullAlsHasCandidateReachableFedAllPlan`이 `empty owned-row domain`으로 실패한다. 68번째 W reader 선택 뒤 loop의 W writer 후보가 전부 제거된다. 이 테스트의 50,000×2,100은 metadata-only shape이고 데이터 학습은 하지 않는다. `.omx/als-fedall-evidence/baseline.log` 및 고정 fixture의 `stable-diagnostic.log`에 기록했다.
- **원인 1/해결 원칙**: reader 후보가 특정 writer realization을 요구하고 해당 writer는 다른 reader realization을 요구하는 양방향 제약을 각각 검사하여, 동시에 성립하지 않는 두 지원을 허용했다. 입력 참조를 먼저 모두 등록한 뒤 domain pair마다 `(own realization, required opposite realization)` bucket으로 양방향 요구를 동시에 join한다. wildcard와 realization별 복수 support clause의 OR 의미를 보존한다. row Cartesian product, retry/backtracking, exact solver fallback은 추가하지 않는다. 전체 legal candidate universe와 runtime/oracle 허용 규칙은 바꾸지 않는다.
- **회귀 근거**: 작은 synthetic reader/writer fixture의 `reciprocalInputRequirementsMustHaveOneCommonRowPair`는 원래 selector에서 `commits=2` 충돌로 실패하고 수정본에서 FedAll/Heuristic 모두 통과한다. 같은 realization의 다른 support clause를 보존하는지와 최종 common authority validator도 검사한다.
- **원인 2/해결**: 양방향 지원 수정 뒤의 `no common relocation pool`은 실제 pool끼리의 불일치가 아니라, 선택한 q/wdivmm의 exact `RELOCATION@0`가 privacy 검사에서 탈락하여 선택 가능한 relocation이 0개가 된 결과였다. 기존 selector는 exact binding을 wildcard로 취급해 이 검사를 건너뛴다. exact RELOCATION은 source의 FType이 같아도 emission을 강제한다는 기존 `NeutralPlacementGraph.isRelocationActive` 계약을 따라, 최종 relocation 검증과 동일한 `isPrivacySafe(action, true)`를 선택 전에 적용한다. graph-owned consumer/action/input-position/required-placement를 hash index로 연결하여 불가능한 row만 제거한다. DIRECT/LOGICAL_TRANSIENT, pool/layout, runtime/oracle/privacy 허용 규칙은 유지한다. action×binding 전수 탐색은 추가하지 않는다.
- **수정 파일**: production은 `PolicyGreedyPlacementSelector.java` 한 파일이다. `PolicyGreedyGroundingTest.java`에 양방향/OR 회귀를 추가하고, 기존 `validate_greedy_docker.py` 및 `PolicyGreedyDockerProbe.java`에 선택형 `--als-only` CP/FedAll 수치 검증을 추가한다.
- **단위 검증**: 최종 source로 Maven package 성공. 6개 클래스/메서드의 34건 중 32건 통과, 2건은 기존 실패다. ALS single-worker 계획 회귀, greedy grounding 5건, greedy selector 11건, exact physical witness 5건, protected nested demotion 3건은 모두 통과했다. `HeuristicLocalContinuationTest`의 `mixedFederatedBranchDoesNotAuthorizeLocalPhi`와 `functionLoopKeepsLocalVectorTransposeAndLossInCP`는 selector 이전 `PlacementSupportRelations.verifyPublishedRelocationRealizations`의 `Final publication has an unbound relocation action`으로 실패한다. 변경 전 selector를 격리 class overlay로 복원한 대조에서도 두 오류를 재현했으며 builder/closure production은 이번 변경에 없다. 전체 34건 무실패로 보고하지 않는다. 로그는 `.omx/als-fedall-evidence/final-focused.log`와 `*-baseline.log`이다.
- **Docker 실제 ALS 검증**: `bash scripts/fedplanner/run_LAN_docker.sh --greedy-validation --image sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434 --als-only` 성공. 2 CPU/4 GiB, single-worker PRIVATE_AGGREGATE, 동일 sparse 50×20 입력(nnz 145), rank 10, maxi 2이다. CP/FedAll의 전체 V(10×20) 200개 값이 유한하며 최대 절대 차이 **0.0**, 출력 SHA도 동일하다. FedAll에서 `fed_wdivmm` 44회 등 실제 FED 계산을 수행했고 fallback/repair **0/0**, runtime audit physical **74/74**, missing physical/synthetic **0/0**, mismatches **0**이다. 최종 production JAR와 source SHA를 검증했다.
- **소형 공통 회귀**: 같은 wrapper에서 `--als-only`를 뺀 기존 Docker 검증도 성공했다. elementwise/nested/loop × FedAll/Heuristic **6건**과 synthetic structural scaling **12건** 통과. 런타임 fallback이나 exact solver 재시도를 추가하지 않았다.
- **시간/한계**: 한 번의 correctness 실행에서 CP compile 2.003s/run 0.929s, FedAll compile 22.512s/run 5.580s이다. 서로 다른 실행 배치/입력 privacy이고 수정 전후 성능 A/B가 아니므로 speedup을 주장하지 않는다. 대형 학습 모델은 실행하지 않았다. greedy는 여전히 backtracking하지 않는 불완전 탐색이며, 이번 해결은 해당 ALS 선택 충돌 두 원인에 대한 것이다.
- **실패 시도 보존**: 최초 Docker 실행은 read-only engine 아래 없는 lib mountpoint 때문에 container 시작 전에 실패했다. 별도 `/deps` mount로 harness만 수정해 성공했다. 별도 L2SVM 확장 테스트 실행은 selector 이전 CFG closure에 오래 머무는 동안 superseded source였으므로 중단했고 통과 집계에 넣지 않는다. 원본 실패 로그를 보존한다.
- **증거**: [검증 요약 JSON](experiments/als-fedall-greedy-20261006/validation.json)에 source/artifact SHA, baseline 대조, 34건의 정확한 결과와 실행 수치를 기록한다. 원본 계획 로그는 `/home/mchoi/als-fedall-greedy-20261006/.omx/als-fedall-evidence/`, ALS runtime은 `target/fedpolicy-greedy-docker/als-run-hb7j6rmy/`, 소형 회귀는 `target/fedpolicy-greedy-docker/run-7zo6ji1e/`에 있다.
- **잔여 이슈/잠재 회귀**: 기존 main의 두 unbound relocation publication 오류와 다른 exact-factor overflow/CFG closure 문제는 이 수정 범위 밖이다. 양방향 join의 과도한 제거는 wildcard/OR/common authority 회귀로, active relocation privacy 제거 오류는 ALS/physical witness 회귀와 runtime audit로 감지한다. production·harness 독립 검토에서 차단 문제는 없었다. 기존 workspace는 수정하지 않았다.

- **최신 main 통합/게시 검증**: push 요청 후 `origin/main`이 `ada24ffd4b`로 진행하여 derived supply sharing/cache lifecycle 변경을 통합했다. 코드 충돌은 없었고 세션 문서의 양쪽 추가 기록을 모두 보존했다. 통합 commit `c890fb651f`에서 Maven package 성공, ALS/greedy 및 incoming emission·audit·cache·derived-sharing **168건 중 166 PASS**, 기존 Heuristic analysis 오류 **2건**, 신규 failure 0이다. 동일 image에서 ALS를 다시 실제 실행해 전체 V 200개 최대 절대 차이 **0.0**, audit mismatch/fallback/repair **0/0/0**, 소형 Docker **6/6** 및 scaling **12/12**를 확인했다. main/test/builtin source **4,305개**의 고정 SHA와 최종 빌드 일치를 확인했으며 통합 검증은 같은 JSON의 `mainIntegration`에 별도 보존한다. 최종 ALS artifact는 `target/fedpolicy-greedy-docker/als-run-afnm0crr/`, 소형 회귀는 `target/fedpolicy-greedy-docker/run-djl7ot3a/`이다.

## Boundary seed·prefix pruning의 origin/main 게시 — 통합 검증 완료

- **요청/환경**: 사용자가 커밋·push 후 후속 개선 진행을 요청하고 대상이 `origin/main`임을 명시했다. 기존 검증된 변경을 seed, 실제 ML harness, source prefix pruning, partial hard proof, 문서의 다섯 커밋으로 나눴다. 원격 topic branch에는 push하지 않았다.
- **통합/문제 해결**: `git fetch origin main`의 `d053a8f24a`를 `bcda73d0cc`에서 병합했다. incoming production은 `PolicyGreedyPlacementSelector.java` 한 파일이며 이번 DP/partial hard proof source와 직접 겹치지 않는다. 세션 문서 append만 충돌하여 양쪽 기록을 보존했다. 강제 push나 원격 이력 재작성은 사용하지 않는다.
- **판단/위험**: shared closure의 same-owner prefix 검사와 incoming reciprocal greedy support join은 동일 realization 일관성을 요구한다. 독립 검토에서 의미 충돌이나 fallback 추가는 발견하지 않았다. incoming relocation privacy 제거의 직접 단위 테스트 부족은 기존 검증 한계로 남기고, ALS actual runtime과 exact witness 회귀로 통합 범위를 확인한다. 별도 Heuristic의 기존 두 분석 오류를 통과로 숨기지 않는다.
- **검증 계획/근거 경로**: 기존 329건에 greedy grounding/selector·physical witness·protected demotion·ALS 단일 계획 검사를 추가하고, 같은 Docker 이미지에서 실제 builtin 3종과 small ALS FedAll을 재실행한다. source SHA, 통합 정보, 로그는 `target/candidate-pruning-evidence/main-publication/`에 보존한다. 후속 factor 저장 최적화는 이 검증된 checkpoint를 게시한 뒤 별도로 진행한다.

- **최종 검증**: 통합본 Java **354/354 PASS**, failure/error/skip 0, package 성공. Python harness **25/25 PASS**. 별도 Heuristic 9건 중 7 PASS·기존 unbound relocation 오류 2건을 재현해 성공 집계와 분리했다. frozen main/test source·class 및 현재 빌드 snapshot SHA 일치를 확인했다.
- **실제 학습 재검증**: `legal-prefix-main-publication`의 multiLogReg/l2svm/lmCG **3/3 PASS**, CP와 전체 16/8/8 계수 일치 및 runtime audit/암묵적 conversion 위반 0. logreg compile **83.937초**, execution **4.094초**, planner **39.430초**, 최종 modeled upper **122.26631334184357**이다. ALS `als-run-7233_syb`는 CP/FedAll 전체 V 200개 최대 절대 차이 **0.0**, physical 74/74, missing/mismatch/fallback/repair 모두 0. 공유 호스트의 동시 correctness 실행이므로 성능 A/B로 사용하지 않는다.
- **근거/잔여 문제**: [게시 검증 JSON](experiments/boundary-seed-20261006/main-publication.json)에 정확한 통합 commit, artifact/source SHA, raw 경로와 검증 결과를 보존했다. dense table 저장은 남아 있으며 row별 합법성 conjunction 분해의 동치성과 저장량 감소를 후속 검증한다.

## LogReg P_1K의 protected 배치 후보 손실 — 공통 closure 수정

- **환경/원래 실패**: `79c26b40ce`에서 분리한 `/home/mchoi/w1357-logreg-privacy-20261006`, `fix/logreg-privacy-20261006`. `CampaignBG014ExactLogRegParallelDispatchCostRedTest`의 PRIVATE_AGGREGATE X/Y, WAN-light, worker 1 fixture에서 `TRead P_1K`의 `No privacy-safe physical placement`를 재현했다. privacy 직전 domain에는 CP/LOUT만 남았지만, CFG는 builtin 198행의 read를 정확한 187행의 단일 writer에 연결하고 있었다. 원본 테스트는 worker 1..4를 포함하며, 최초 worker에서 실패한다.
- **원인**: branch normalization 후 `closePrePrivacyValueMaps`가 transient/function-output 변경을 반영하면서 `closePhysicalDependencies`로 소비자 후보를 Oracle template에서 다시 만들었다. 이때 executable native 증명이 사라졌고 VALUE_MAP alias만 복구됐다. map을 바꾸는 `rightIndex`와 TWrite는 witness 없는 staging lineage에 머물러, TRead로 FED 후보를 전달하지 못했다. 남은 CP 후보를 privacy 필터가 올바르게 제거한 결과다. loop reaching-def 및 privacy 규칙은 변경할 필요가 없었다.
- **수정**: `PlacementRelationClosure.closePrePrivacyValueMaps`에서 관계가 바뀐 wave에는 기존 `closeCfgTransientCandidateDependencies` 전체 transfer를 적용해 native grounding과 CFG 전달을 완료한 뒤 고정점을 검사한다. nodes·ruleKeys·facts·logical bindings가 모두 같으면 기존처럼 즉시 종료한다. 공통 closure를 재사용하며 LogReg 전용 분기, 후보 삭제, privacy 완화 또는 runtime fallback은 추가하지 않았다. 독립 검토에서 postprivacy 재필터링과 loop-seed memo 문맥 분리도 확인했다.
- **회귀**: `LogregProtectedLoopSliceTest`는 nested loop와 conditional softmax update를 포함한 8행 축소본에 실제 branch normalization을 적용한다. worker 1/2 모두 수정 전 같은 P_1K privacy 오류로 실패하고, 최종 수정 후 각각 `FED/FOUT/FULL`, `FED/FOUT/ROW`가 유지된다. coarse state뿐 아니라 슬라이스의 executable native witness/input binding과 writer/read compatibility proof도 검사한다. branch normalization을 빼면 baseline에서도 통과하므로 재현에 필요한 조건을 명시적으로 검사한다.
- **최종 빌드/회귀 결과**: Java 17 Maven package 성공(100.49초), 신규 회귀 **2/2 통과**(87.87초), unchanged-wave 작업 횟수 회귀 **1/1 통과**. 12개 인접 클래스는 **153건 실행, 151 통과, 2 실패, 기존 ignore 1**이다. 2건은 아래 baseline golden 실패이며 새 회귀 실패는 없다. production/test SHA가 fresh build와 일치하고 `git diff --check`가 통과했다.
- **기존 golden 불일치**: `EarlyPrivacyPruningLegalSpaceParityTest`의 `loopBranchPhiAndFunctionRelationsKeepAllProtectedAlternatives`, `metadataAndFunctionHandleExceptionsDoNotReleaseFormalPayload`는 immutable baseline에서도 같은 observed digest(`b9b2d88c…`, `811d78e0…`)로 실패한다. 전체 digest와 재현 명령은 `golden-audit/` 및 `validation.json`에 보존했고 기준값은 변경하지 않았다.
- **실제 Docker W2 플래닝**: `run_LAN_docker.sh`로 PRIVATE_AGGREGATE X/Y, n=50,000, d=2,100의 실제 `multiLogReg`를 실행했다. 최종 코드의 DP-LocalConflict compile/lowering 성공, graph 531개 노드, P_1K의 rix/TWrite/TRead 모두 `FED/FOUT/ROW/SHAPE_INDEPENDENT`로 선택된다. probe wall 141.41초이며 `runtimeExecuted=false`다. Docker 비용 설정은 command receipt에 기록되어 있고 원래 host WAN-light 설정과 구분한다.
- **실제 Docker W1 검증/한계**: 최종 코드의 587.25초 스택은 첫 `closePrivacyDomains`(805행)를 지나 postprivacy replay 뒤 두 번째 `closePrePrivacyValueMaps`(823행)에서 native 증명을 계산 중임을 보여준다. 기존 P_1K 실패 지점 통과는 확인했지만, 20분 상한에도 전체 compile/lowering이 끝나지 않아 소유 컨테이너를 종료했다(종료 포함 1,215.44초, rc 143). planner/privacy/OOM 예외는 관찰되지 않았다. 최종 privacy 검사와 전체 플랜·선택 상태는 미확인이며, 후속 공통 배치 분석 지연이 남아 있다. 축소 W1 회귀의 FULL 증명 복구를 전체 builtin 플래닝 성공으로 확대 해석하지 않는다.
- **중간 수정/실행 이력**: 최초 수정(V1)은 변경 없는 wave에서도 closure를 호출해 작업 횟수 계약이 8회에서 10회가 됐다. 최종 V2의 전체 상태 no-op 검사로 기존 8회를 유지한다. V1 host W1 DP는 두 privacy 검사 이후 `closePlacementAndFeasibility`까지 진행했고 Docker W2도 성공했다. V1 W1/Exact의 미완료 실행은 최종 검증으로 대체하기 위해 소유 PID/container만 종료하여 `superseded`로 보존했다. 원래 Exact worker 1..4 테스트 전체가 통과했다고 주장하지 않는다.
- **검증 범위/기록**: 추가 grounding의 작업량 증가 가능성이 있으며 latency 개선 또는 distributed training 성공을 주장하지 않는다. 명령·로그·source SHA·baseline 대조·중간 실행의 구분은 `/home/mchoi/logreg-privacy-20261006/validation.json`과 하위 artifacts에 기록했다. 최종 Docker 판정은 `docker/verdict.json`, 원본 스택은 `docker/metadata/final-v2-logreg-w1-thread-dump-587s.log`, 증거 checksum은 `docker/metadata/final-v2-evidence-sha256.txt`에 있다. 소유한 Docker 컨테이너는 모두 종료했다.
- **최신 main 통합/게시 검증**: push 요청 시 `origin/main`이 `d053a8f24a`로 진행하여 derived supply sharing/runtime 및 ALS selector 변경 위에 rebase했다. 코드 충돌은 없었고 문서의 양쪽 기록을 모두 보존했다. 통합본에서 Java 17 Maven package 성공(92.86초), LogReg·neutral fixed-point 회귀 **14/14 통과**(136.52초), 같은 Docker image/비용 설정의 실제 W2 compile/lowering 성공(143.25초, probe 141.72초)을 확인했다. P_1K rix/TWrite/TRead는 모두 `FED/FOUT/ROW`이며 fresh build 전후 Java/builtin/POM **3,763개 파일**의 SHA가 일치한다. 앞선 153건 검사 및 W1 전체 시간 상한 기록과 구분하며, W1 전체와 golden 2건을 통합본에서 다시 실행한 것은 아니다. 통합 명령·로그·source SHA·Docker receipt는 `/home/mchoi/logreg-privacy-20261006/main-publication/validation.json`과 하위 artifacts에 보존한다.

## Boundary seed·prefix pruning 게시 직전 LogReg closure 통합 — 검증 완료

- **문제/해결**: push 직전 `origin/main`이 `825acfca9d`로 진행했다. native proof를 privacy 전에 복원하는 incoming closure 수정을 `0bb200fb5c`에서 통합했다. `PlacementRelationClosure.java`는 자동 병합됐고 문서의 양쪽 기록을 보존했다. 규칙 완화나 fallback 추가 없이 동일 owner prefix와 native grounding 복원을 함께 유지한다.
- **검증**: 신규 protected LogReg 회귀 2건을 포함해 **356/356 PASS**, failure/error/skip 0, package 성공. frozen main/test source·class와 빌드 snapshot SHA 일치. `legal-prefix-main-publication-r2`의 실제 multiLogReg/l2svm/lmCG **3/3 PASS**, CP와 전체 계수 일치, audit 및 runtime conversion 위반 0. logreg compile **89.101초**, execution **3.847초**이며 큰 factor 25,006,592셀은 여전히 남는다. ALS `als-run-c6xzdiy7`도 전체 200개 값 차이 0, missing/mismatch/fallback/repair 모두 0이다.
- **근거/범위**: [최종 게시 검증 JSON](experiments/boundary-seed-20261006/main-publication-r2.json)에 통합 SHA·source/artifact 검증·raw 경로를 보존했다. 앞선 별도 Heuristic 2건의 기존 오류는 이전 통합본에서 확인한 기록이며 이번 356건에 포함하지 않는다. 대규모 protected X/Y 전체 training이나 모든 planner의 성공으로 확대하지 않는다.
- **후속 방향/회귀 위험**: logreg의 큰 factor는 Grad loop-back 보존 연쇄를 포함한다. CFG row 분해가 실제 저장량을 줄인다는 근거 없이 production 표현을 바꾸지 않는다. 게시 후 별도 복사본에서 row별 support owners와 저장 셀 추정치를 계측하고, 실제 factor와 solver를 유지한 Docker 학습으로 타당성을 판단한다.

## Joint hard-factor row 분해의 실제 저장량 진단 — 완료, 해당 표현 미채택

- **문제/관측**: `origin/main`에 게시한 `1dc7fbd7ca`의 multiLogReg는 완료되지만 최대 truth factor 25,006,592셀을 유지한다. 기존 AA/BB 상관관계 테스트는 의미 동치성을 확인해도 row 분해의 저장 감소를 증명하지 않는다.
- **사전 계획/판단 근거**: production 변경 전에 기존 전체 relation과 각 single-row relation의 `Grounding.supportOwners()`를 비교한다. 같은 consumer·모든 reader guard·원래 decision identity를 유지한다. 기존 observation category를 재사용한 보수적 저장 추정은 canonical domain 곱과 truth+binary-link 합 중 작은 값이며, 전체 표현과 row 합계·최대값을 비교한다. 이는 candidate cap이 아니라 표현 선택의 타당성 조사다.
- **검증 경계**: 공유 source/target은 변경하지 않고 별도 main-source/class 복사본에 진단만 추가한다. 원래 canonical factor·partial proof·solver 동작을 그대로 두고 `run_LAN_docker.sh`로 동일 ml_logreg를 실행한다. 복사본 source/class diff와 SHA를 보존하고 전체 계수·audit·최종 modeled 비용을 게시본과 비교한다. 진단 오버헤드가 있으므로 실행 시간을 최적화 전후 성능 근거로 쓰지 않는다.
- **잔여 문제/회귀 위험**: pre-support cardinality 추정과 실제 domain 축소 후 allocation은 다르다. 어느 한 row가 전체 non-singleton 축을 포함하면 현재 observation 표현의 row 분해로는 해당 최대 factor를 줄일 수 없다. 이 경우 분해를 production에 넣지 않고 근거와 다음 필요한 의존 관계를 기록한다.

- **계측 결과/결정**: logreg의 joint relation 4개 모두 현재 observation keys를 유지하는 row별 분해로 총 저장량이 줄지 않았다. `gs=sum(S*Grad)`의 12개 row 중 3·7·11은 Grad 보존 연쇄를 포함한 전체 큰 축을 필요로 한다. 전체 저장 추정은 **25,029,616→75,142,200셀**, 최대 factor는 **25,006,592셀**로 동일하다. 이 특정 분해는 미채택했다. 더 거친 row 관찰·link 공유·pool-query 분해 전체가 불가능하다는 의미는 아니다.
- **실행/동치 확인**: `joint-row-diagnostic-01` Docker logreg **PASS**, 전체 16계수 CP 일치(최대 오차 2.220446049250313e-16), audit 및 runtime conversion 위반 0, 진단 오류 0이다. 게시본과 동일 input/image 및 최종 modeled upper **122.26631334184357**을 확인했다. 실제 freezer도 25,006,592셀과 동일한 partial/subtree 작업량을 유지한다. 복사본의 source/class freeze SHA 일치 및 production Java snapshot 불변을 확인했다.
- **수정 파일/근거**: production 변경 없음. 이 세션 문서·`LEGAL_CANDIDATE_EXPANSION_2026-10-06_KO.md`·[진단 JSON](experiments/boundary-seed-20261006/joint-row-diagnostic.json)에 측정 결과를 기록했다. 계측 patch·원본/계측 SHA·javac 명령은 raw root의 `joint-row-diagnostic-build`, 실행은 `joint-row-diagnostic-01`에 보존했다. 독립 검토에서 실제 canonical factor·encoding/solver 선택 불변과 추정식의 적용 범위를 확인했다.
- **남는 범위**: loop-back alias 연쇄의 의존성/관측을 더 작게 표현하는 증명이 필요하다. 임의 candidate cap, 합법해 삭제, runtime fallback으로 이 병목을 우회하지 않는다. 이 진단의 음성 결과는 이미 게시한 seed·prefix pruning 구현의 효과와 구분한다.

## Loop/function privacy golden 2건 — 원인 확인 및 테스트 보강 완료 (10월 7일 검증)

- **범위/환경**: `825acfca9d` 기준의 별도 worktree `/home/mchoi/w1357-loop-function-golden-20261006`에서 `EarlyPrivacyPruningLegalSpaceParityTest`의 metadata/function-handle 및 loop/branch/function 두 실패만 조사했다. PRIVATE_AGGREGATE A=4×2, ROW worker 2개의 hermetic compile/placement fixture다. immutable baseline에서 4건 중 같은 2건이 실패하며, observed digest는 각각 `811d78e0…`, `b9b2d88c…`다. 이전 unknown-width golden 수정과 별개다.
- **이력/원인**: 두 기대값은 merge `49509ab7f8`에서 갱신됐고 당시 4/4 통과 및 원문 snapshot이 `/home/mchoi/joint-main-integration-20261006/latest-main-golden/`에 남아 있다. 이후 `d7e88516a1`은 exact native output의 전체 geometry를 보존하도록 수정했다. 과거 snapshot은 continuity proof의 비분할 축 placeholder를 실제 배치로 사용해 4×2 행렬의 ROW 배치를 **4×1**로 기록했다. 현재는 producer→TWrite→TRead에 **4×2** 전체 범위를 전달한다. `0146f043e0`은 native/transient realization identity에 output layout과 exact/dynamic precision을 추가해 다른 배치의 식별자 충돌을 막았다. 따라서 차이는 단순 이름 변경만이 아니라 올바른 geometry의 반영도 포함한다.
- **원인 분리 검증**: 현재 `PlacementRelationClosure.java`의 두 변경만 독립적으로 되돌린 진단 overlay를 같은 immutable engine에서 실행했다. 두 변경을 함께 되돌리면 **두 과거 snapshot 전체가 바이트 단위로 정확히 복원**된다. 다른 production 코드, 최신 LogReg closure 수정, oracle/privacy 규칙은 되돌리지 않았다. 과거와 현재의 NODE privacy/합법 상태, AVAILABLE rule key, relocation/derived action은 모두 동일하다. metadata는 36 NODE·34 AVAILABLE·4 relocation, control은 54·52·8이다. emission 내부의 정확한 배치와 그 식별자·참조가 수정된 것이며, 물리적 배치 내용까지 과거와 같다는 뜻은 아니다.
- **해결/의사결정 근거**: production 변경 없이 해당 테스트만 보강하고 두 기대 hash를 갱신했다. 함수 결과 C의 1개 writer, 분기 D의 2개 writer와 reader를 모두 요구하며, producer·TWrite·TRead의 native support가 각 worker의 정확한 2열 범위를 유지하는지 검사한다. 모든 reaching writer에 native compatibility가 존재하고 경계가 실제로 저장한 source/reader reference가 해석되는지도 검사한다. 두 fixture에서 early pruning on/off **전체 snapshot 일치**를 추가했으며 기존 privacy 검사, 반복 compile 재현성, ID/support/input binding을 포함하는 전체 serializer와 unknown-width golden은 유지했다. runtime fallback이나 후보 제거·허용 규칙 변경은 없다.
- **오류 재주입**: 수정한 테스트에 geometry 보존만 되돌린 overlay를 적용하면 두 대상 모두 golden 비교 이전의 `native output must preserve both columns of the 4x2 payload` 검사에서 실패한다(실제 width 1). identity 보강만 되돌리면 구조 검사는 통과하고 두 전체 golden 비교가 실패한다. 현재 production에서는 4/4 통과한다. 따라서 hash만 바꿔 과거의 잘못된 배치를 승인한 것이 아니다.
- **최종 검증**: Java 17·Maven 3.9.7로 새 worktree의 main/test를 새로 컴파일하고 `EarlyPrivacyPruningLegalSpaceParityTest`, `EarlyPrivacyGenerationWorkTest`, `CandidatePrivacyInputPruningTest`, `NativeOutputGeometryTest`, `DynamicNativeLayoutCompositionTest`, `JointValueMapRelationsTest`, `CandidateRealizationCanonicalizationTest` **7개 클래스 72/72 통과, skip 0**을 확인했다. Maven `test` 전체 87.33초이며 Java/POM 3,584개 파일의 전후 SHA가 일치한다. 독립 read-only 검토에서 차단 문제가 없었고 `git diff --check`도 통과했다. 최소 재현은 Java 17 환경에서 `mvn -Djacoco.skip=true -Dtest=EarlyPrivacyPruningLegalSpaceParityTest test`다.
- **수정 파일/증거**: 테스트 파일 `src/test/java/org/apache/sysds/hops/fedplanner/placement/EarlyPrivacyPruningLegalSpaceParityTest.java`, 이 세션 기록, [검증 JSON](experiments/loop-function-golden-20261006/validation.json). JSON에 원인별 snapshot hash, 전체 구조 비교, source SHA, 정확한 실행 명령, 72건 결과 및 진단 overlay/원시 로그 경로를 보존했다. 원본 자료는 `/home/mchoi/loop-function-golden-20261006/`에 있으며 다른 worktree의 source/target은 변경하지 않았다.
- **잔여 이슈/잠재 회귀**: 요청한 두 불일치는 해결했다. full golden은 의도적인 identity/geometry 변경에도 다시 검토해야 하므로 이후 변경 시 구조 검사와 전체 snapshot 원인 대조를 함께 유지한다. 이 fixture는 크기 보존 ROW 경로이며 일반 exact/dynamic 합성은 인접 회귀로 보완했다. 실제 분산 학습이나 성능 비교는 이번 검증 범위가 아니다.
- **최신 main 게시 검증**: 커밋·push 요청 후 최신 `origin/main` `129a2ad268` 위에 rebase했다. production/test 충돌은 없었고 세션 문서의 양쪽 독립 추가 기록을 모두 보존했다. Java 17 Maven `test`로 main/test를 다시 컴파일하고 기존 7개 클래스와 incoming 변경의 `ReferenceProductPrefixPruningTest`, `JointPartialTruthTest`를 포함해 **9개 클래스 78/78 통과, failure/error/skip 0**을 확인했다(98.19초). 두 golden과 보강한 테스트 source는 그대로이며 Java/POM 3,589개 파일의 전후 SHA도 일치한다. 통합 기준·명령·로그·source SHA는 같은 검증 JSON의 `mainPublication`과 `/home/mchoi/loop-function-golden-20261006/main-publication/`에 보존한다.
## Heuristic 함수·분기 후보의 unbound relocation publication — 요청된 2건 수정·검증 완료

- **환경/재현**: 최신 `origin/main` `d053a8f24a`에서 `/home/mchoi/heuristic-relocation-20261006` worktree를 만들었다. 기존 workspace를 수정하지 않는다. `HeuristicLocalContinuationTest#functionLoopKeepsLocalVectorTransposeAndLossInCP+mixedFederatedBranchDoesNotAuthorizeLocalPhi` 2건 모두 selector 이전 `Final publication has an unbound relocation action`으로 재현했다.
- **원인**: relocation discovery는 coarse AVAILABLE 입력/출력 상태 및 anchor로 중간 후보를 생성한다. `p-grad/4`의 COL 출력 3×1에 FULL 4×3 X anchor를 적용하면 `nativeOutputAnchor`가 보존할 열 범위 3이 출력 열 수 1보다 커져 exact realization을 만들지 않는다. 다른 COL 3×1 anchor를 사용하는 합법적인 clause는 살아 있지만, 실행 근거가 없는 최초 discovery action도 최종 목록에 남아 validation이 실패했다. 원본 진단은 `.omx/heuristic-relocation-evidence/orphan-diagnostic.log`에 있다.
- **해결/의사결정 근거**: semantic fixed point가 완전히 수렴한 경우에만 live exact RELOCATION clause 또는 기존 directSourcePlacements proof가 소유하는 action을 게시한다. 중간 discovery와 rebinding은 유지하여 뒤늦게 생기는 합법적 support를 막지 않는다. candidate facts/OR clauses/physical geometry/privacy/TR-TW 규칙을 변경하지 않으며, 최종 validator도 그대로 유지한다. 기존 validator가 허용하지 않던 discovery-only action을 최종 publication에서 제외하는 것이다.
- **수정 파일**: production은 `PlacementRelationClosure.java`, `PlacementSupportRelations.java` 2개다. `PublicationSupportClosureTest.java`에 회귀 2건을 추가했고, `validate_greedy_docker.py`의 선택형 `--heuristic-continuation`과 `PolicyGreedyDockerProbe.java`에 소형 실행·수치·audit 검증을 보강했다. 새 production 옵션이나 dependency는 없다.
- **직접 회귀**: `mvn -q -Djacoco.skip=true -Dtest-forkCount=1 -Dtest=PublicationSupportClosureTest,HeuristicLocalContinuationTest test`로 재현/검증할 수 있다. 원래 오류 2건을 포함한 Heuristic 9건과 publication 10건, **19/19 통과**다. 새 회귀는 expired clause 제거 후 orphan action만 제외하고 exact action/direct-only proof의 객체·순서를 보존하는지, missing action/expired source가 여전히 최종 validator에서 실패하는지 검사한다. 로그·XML은 `.omx/heuristic-relocation-evidence/focused.log`, `focused-reports/`에 보존한다.
- **확장 검증/제한**: 완료된 XML을 메서드별로 중복 제거하면 13개 클래스 **99건 중 87 PASS, 기존 skip 5, error 7**이다. 19건의 직접 회귀도 이 합계에 포함되며 중복 가산하지 않는다. ALS single-worker 계획, greedy grounding/selection, publication fixed point, support deletion, relocation memo, plan-space와 privacy 인접 검사를 포함한다. error 7건은 `NeutralPlacementGraphUploadRelocationRedTest`의 외부 worker privacy 조회 실패로 수정된 경계에 도달하기 전 발생한다. 원래 main source와 일치하는 baseline classes/JAR에서도 **7/7 동일 privacy 오류**를 재현했다. privacy를 완화하거나 새 ignore를 추가하지 않았다. 전체 suite 통과로 보고하지 않는다.
- **미완료 검사**: full builtin GLM을 구성하는 `rewrittenInlinedOutputRetainsItsCompilerDeclaredTargetAuthority`, `inlinedGlmInputsAreTraceOnlyWithOrWithoutLexicalCarriers`, `candidateMaterializationSearchMatchesBoundedExhaustiveOracle` 3개 메서드는 최종 bounded 실행에서 제외했다. 앞선 두 확대 실행은 해당 GLM fixture가 수정 지점 이전 `PlacementJointInputAnalysis`/`closeBoundaryStructure`에 머무는 동안 중단했다. 미완료 검사를 성공으로 세지 않으며 `expanded-stop.json`, `remaining-stop.json`, thread dump와 로그를 보존한다.
- **빌드**: `mvn -q -Djacoco.skip=true -Dtest-forkCount=1 -Dtest=StartupTest package` 성공, StartupTest 7/7 통과다. 앞선 `-DskipTests` package는 이 POM의 실제 skip 속성인 `maven.test.skip`을 사용하지 않아 기본 테스트 10건을 실행했고, `StartupTest.testStartupCorrect`에서 로그 개수 4/5 불일치 1건이 발생했다. source 변경 없는 재시도에서 통과했으며 최초 실패 로그/XML도 보존한다.
- **Docker 실제 실행**: `bash scripts/fedplanner/run_LAN_docker.sh --greedy-validation --image sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434 --heuristic-continuation` **2/2 성공**. 2 CPU/4 GiB, 외부 network 없음, container loopback worker 1개, X=4×3 PRIVATE_AGGREGATE/y=4×1 PUBLIC, 2회 반복이다. 원래 두 unit fixture의 함수·루프·분기 구조로 실행했다. 독립 scalar Python 기준과 p의 세 값 및 loss가 모두 정확히 일치한다: function-loop `[17.5,20,22.5,30]`, mixed-branch `[-17.5,-20,-22.5,30]`, 최대 절대 차이 **0.0**. 각각 실제 FED compute 8회, fallback/repair **0/0**, audit physical **37/37**, missing physical/synthetic **0/0**, mismatches **0**이다.
- **실행 시간/한계**: 이번 한 번의 최종 correctness 실행에서 function-loop compile/run **34.645s/1.611s**, mixed-branch **30.254s/0.647s**다. 수정 전은 후보 분석에서 실패하므로 latency A/B나 speedup을 주장하지 않는다. 대형 모델은 실행하지 않았다.
- **검증 harness 수정 이력**: 첫 Docker 실행의 실제 계산·audit는 모두 정상이었지만 수치 parser가 audit instruction 안의 `FEDPOLICY_NUMERIC=` 문자열까지 읽어 실패 판정했다. 전체 출력 행으로 marker를 제한하고 누락·추가·NaN/Infinity marker 거부를 유지했다. audit summary가 존재하고 모든 summary의 missing physical/synthetic 및 mismatch가 0이어야 통과하도록 보강했다. 동일 production JAR로 wrapper를 다시 실행해 성공했으며 최초 실행 `heuristic-continuation-run-slmb1gus/`도 보존한다. production/회귀와 harness에 대한 독립 리뷰에서 남은 차단 지적은 없다.
- **증거/최종 일치**: [검증 요약 JSON](experiments/heuristic-relocation-20261006/validation.json)에 실패·중단·통과를 분리하고 메서드 목록, SHA와 로그 경로를 기록했다. 원본은 `/home/mchoi/heuristic-relocation-20261006/.omx/heuristic-relocation-evidence/`, 최종 Docker artifact는 `/home/mchoi/heuristic-relocation-20261006/target/fedpolicy-greedy-docker/heuristic-continuation-run-y6kbicsd/`다. manifest의 production/pom **2,184개 파일**, harness/probe source, JAR/class SHA가 최종 파일과 일치한다. `git diff --check`, Python compile, wrapper Bash syntax 검사도 통과했다.
- **잔여/잠재 회귀**: 요청된 두 analysis 오류는 해결했다. 위 외부 worker fixture 오류와 full GLM 검증 공백, 기존의 다른 CFG closure/exact-factor 문제는 이번 해결 범위에 포함하지 않는다. stable 이전에 projection하면 뒤늦게 생기는 합법 support를 잃을 수 있어 호출 위치를 고정점 종료 경계로 제한했다. exact/direct authority 보존과 fail-closed validator 회귀로 과도한 제거 및 dangling 참조를 감지한다. 기존 workspace는 수정하지 않았다. 후속 사용자 요청에 따라 검증된 변경을 새 worktree의 로컬 branch에 커밋하며, 원격 push는 이번 요청에 포함하지 않는다.

## Cost-based → Heuristic 합법성 공유 조사 — 누락 재현, 구현 미실시

- **요청/범위**: 앞선 relocation publication 수정을 `82b2f63735`로 커밋했다. 이어 cost-based의 어떤 처리가 Heuristic의 합법 계획 선택에도 필요한지 조사했다. 추가 production/test 변경이나 원격 push는 하지 않았다.
- **확인된 문제**: 독립 분기에서 선택한 두 PRIVATE_AGGREGATE VALUE_MAP 입력의 worker pool이 실행 가능한 일부 조합에서 다르다. cost-based의 joint hard factor는 이를 거부하지만 Heuristic과 공통 normalization/emission prevalidation에는 동등한 최종 검사가 없다.
- **재현/근거**: canonical 공통 search-space 준비를 사용한 8×2 metadata fixture에서 상관 AA/BB는 Exact/Heuristic 모두 허용하고 2행 모두 정렬된다. 독립 AB/BA는 Exact가 `EXACT_VE_NO_FEASIBLE_ASSIGNMENT`로 거부하지만 Heuristic은 93개 candidate의 계획을 정규화하고 emission 사전 검사도 통과한다. 소비자는 FED/FOUT/ROW, DIRECT/DIRECT, relocation 0이며 4행 중 2행의 pool이 불일치한다. 수정 전 main `d053a8f24a`에서도 같은 결과다. 독립 리뷰로 probe가 ownership이나 검사를 우회하지 않는지 확인했다.
- **해결 방향/의사결정 근거**: `ExactPhysicalModel.addJointFactors`의 row별 pool 정렬 hard predicate를 공유 legality 검증·선택 경로에 반영할 필요가 확인됐다. 비용 점수나 solver 전체 이식은 필요하지 않다. LOCAL/broadcast 및 relocation target의 기존 의미, 상관 분기별 다른 pool을 보존한다. scope를 무작정 키우거나 모든 source를 한 pool에 고정하는 방법은 사용하지 않는다. 이번 요청은 조사이므로 이 추가 수정은 아직 구현하지 않았다.
- **추가 발견**: latent/direct WDIVMM owner–weights 조건은 cost-based에서 이항 hard factor로 검사하고 Heuristic은 동일 공통 predicate를 최종 receipt 검증에서 검사한다. 조기 전파의 개선 후보이나 별도 workload 실패는 미확인이다. 3-node 재합류 fixture에서는 합법 CP 대안이 있음에도 FED_FIRST/AGG_LOCAL 모두 첫 commit 뒤 실패함을 재현했다. 이는 합법성 누락과 별개의 no-backtracking 탐색 한계다.
- **잔여/위험**: VALUE_MAP 누락은 미수정 상태다. 실제 emission commit, lowering, worker runtime은 실행하지 않았으므로 최종 실행 결과를 주장하지 않는다. runtime read–source hard factor와 기존 transient/alias 지원의 parity는 추가 확인 대상이다. 수정 시 상관된 분기와 명시적 합법 movement까지 제거하지 않도록 양성/음성 대조가 필요하다.
- **보고서/증거**: [합법성 비교 보고서](HEURISTIC_LEGALITY_PARITY_2026-10-06.md), [요약 JSON 및 probe/log](experiments/heuristic-legality-20261006/analysis.json). 원본은 `/home/mchoi/heuristic-relocation-20261006/.omx/heuristic-legality-analysis/`다.


## Heuristic 합법성 공통화 및 충돌 복구 구현 — 완료

- **요청/격리**: 조사 보고서의 모든 후속 작업을 수행한다. 최신 `origin/main` `825acfca9d`를 fetch하고 `/home/mchoi/heuristic-legality-20261006`을 새로 만들었다. 앞선 publication 수정/조사 `141439ee29`를 병합한 시작점은 `0ccf4c9c9f`다. 기존 workspace는 수정하지 않는다. 문서 충돌은 양쪽 기록을 모두 보존해 해결했고 LogReg native-proof 수정도 유지한다.
- **구현 계획**: (1) 기존 joint VALUE_MAP row 정렬 의미를 공통 validator와 Exact factor에서 재사용하고, 정상/불법/LOCAL·relocation 대조를 회귀로 고정한다. (2) Heuristic의 latent/direct WDIVMM owner–weights 관계를 선택 전 작은-scope support로 전파한다. (3) 기존 빠른 greedy 경로를 유지하면서 충돌에 한해 제한된 가역 선택 복구를 추가해 재합류의 합법 대안을 찾는다. (4) runtime read–source 조건은 기존 transient/function/alias 지원과 대조하고, 확인된 누락만 동일 hard predicate로 보완한다.
- **검증 계획**: 회귀를 먼저 고정해 이전 compiled baseline의 RED를 보존한다. 공통/Exact/Heuristic/LogReg 및 선택 복구의 domain 원상복구·결정성·budget 실패·no-conflict scaling을 검사한다. 실제 실행은 `run_LAN_docker.sh`에서 작은 protected correlated/independent/mixed VALUE_MAP, WDIVMM/ALS 및 기존 function-loop/branch 사례만 사용한다. 수치값·audit·fallback/repair와 source/JAR SHA를 기록한다. 대형 모델은 실행하지 않는다.
- **고정 제약/위험**: 비용 점수·거대한 dense factor·새 dependency·runtime fallback을 추가하지 않는다. LOCAL/broadcast/relocation target의 합법 대안과 서로 다른 분기에서의 다른 pool을 유지한다. 조건부 삭제는 rollback 시 복구하며 immutable 분석의 후보군을 영구 변경하지 않는다. 제한된 탐색이 중단돼도 전체 infeasible로 오인하지 않는다. 외부 worker 오류·기존 실패·미완료 검사는 별도로 기록한다.
- **통합 과정에서 발견한 회귀**: 첫 공통 validator를 적용한 기존 ALS 회귀가 JOINT 거부와 repair budget 소진으로 실패했다. Exact의 `inputAuthorityProducts`는 CP와 DML function forwarding 입력을 NATIVE_LOCAL로 취급한다. 공통 검사도 실제 FED/non-function/PRESENT 입력에만 pool 정렬을 적용하고 receipt 없는 CP/LOUT 대안은 assignment에서 확인하도록 수정했다. 이 parity 수정 후에도 ALS는 실패했고, 정확한 잔여 원인은 `alsCG.dml:135`의 scalar `alpha` × VALUE_MAP `HS`였다. 실제 물리 입력은 PRESENT/BROADCAST HS 하나인데 grounded rows 0/2를 이유로 정렬 검사에서 거부했다. 단일 FederationMap에는 다른 map과의 정렬 의무가 없으므로 common과 Exact 양쪽에서 distinct physical input position이 0~1개면 joint 검사를 항진으로 처리했다. 일반 input authority 검사는 유지하며 격리 ALS 실제 실행 1/1이 통과했다. scope backjump나 예산 증가는 채택하지 않았다. 최초 로그/XML/hash는 `.omx/heuristic-legality-evidence/integrated/`에 보존했다.
- **리뷰 반영**: 일반 ownership/invariant 예외를 선택 복구로 숨기지 않도록 typed joint/greedy/grounding 충돌만 복구한다. runtime read–source는 명시적 업로드 경계를 가로지르면 합법 대안을 거부함을 회귀로 확인해 독립 제약으로 이식하지 않는다. Docker 양성 mixed 사례는 public movement 대안의 실행 가능성을 검증하며, LOCAL authority가 실제 선택됐다는 별도 주장은 하지 않는다.
- **최종 검증**: Java 17 Maven package 및 14개 클래스 **80/80 통과**, failure/error/skip 0. 테스트용 budget 생성자의 visibility를 줄인 뒤 greedy/grounding **19/19와 package를 재검증**했다(80건에 중복 가산하지 않음). 최종 probe만 별도 javac 후 동일 production JAR로 Docker joint **6/6**, 기존 소형 FedAll/Heuristic **6/6**, scaling **12/12**, 소형 ALS CP/FedAll 비교를 모두 통과했다. ALS의 V 200개 값은 최대 오차 **0.0**이며 `fed_wdivmm` 44회, physical audit **74/74**, missing/mismatch 및 fallback/repair **0**이다. 모든 runtime production/POM SHA와 최종 JAR SHA가 일치한다.
- **검증 harness 이력**: 최초 joint run의 independent 계획은 정상적으로 compile rejection됐지만 raw CP heavy-hitter 합계가 constant folding까지 포함하여 probe가 잘못 실패했다. runtime timer 0, FED 실행 없음, authority generation 0 및 정확한 typed 원인을 확인하도록 보정하고 같은 JAR로 6건을 모두 재실행했다. 최초 로그는 보존했다.
- **잔여 범위/수정 위험**: bounded repair는 여전히 불완전하며 예산 소진은 global infeasibility가 아니다. WDIVMM의 직접/latent 합법 선택은 검증했지만 unsupported weights-state end-to-end 음성 fixture는 미확보다. L2SVM full fixture는 selector 전 closure 확장 중 중단했고 성공 수에 포함하지 않았다. 대형 모델·전체 workload·paired latency 개선은 주장하지 않는다. 과도한 후보 제거는 joint 양성/음성·LOCAL/relocation·단일-map ALS 대조로, rollback 오류는 재합류 및 budget 회귀로 감지한다.
- **최종 보고서/요약 JSON**: [구현 보고서](HEURISTIC_LEGALITY_IMPLEMENTATION_2026-10-06.md), [검증 요약](experiments/heuristic-legality-20261006/implementation-validation.json). 원본 artifact 절대 경로와 실행 명령, SHA, 실패·중단·최종 성공 구분을 포함한다. 수정은 planner의 공통 legality 및 compile-time 선택에 한정되며 runtime fallback, privacy/TR-TW 완화, 거대한 factor, 새 dependency는 추가하지 않았다.

## Derived supply sharing 자동 E2E 범위와 메모리 정책 설명 정정 — 문서화 완료

- **환경/조건**: 전용 worktree `/home/mchoi/w1357-derived-supply-sharing-20261006`, 코드 기준 `ada24ffd4be4d17240f32f5bd1c2b5a98e1a0c8a`. 사용자는 남은 자동 DML 검증과 메모리 확인의 상세 설명을 문서로 요청했다.
- **문제 정의/증상**: real-worker 1×/N× proof를 optimizer 자동 선택까지 연결된 전체 DML 검증으로 오해할 수 있고, 앞선 설명과 게시 보고서에서 planned copy 유지와 일반 cache eviction의 차이가 불명확했다.
- **원인/의사결정 근거**: worker proof는 `forcedUpload`와 직접 구성한 staged instruction을 사용한다. 별도 Global/Local 검사는 canonical 비용과 lifetime을 검증한다. `FederationUtils.evictOwnedRefedEntries`는 `_planned`를 건너뛰므로 일반 cache 퇴출 후 재생성 테스트를 planned sharing의 정책으로 일반화할 수 없다. Oracle·planner·runtime 규칙은 수정하지 않는다.
- **해결 방법/변경 요약**: 자동 DML 선택·lowering·실행의 성공 기준, planned/legacy/single-use 유지 정책, 메모리 측정 항목 및 측정 후의 최소 대응을 별도 보고서에 정리했다. 게시 보고서의 retirement/eviction 표현도 같은 구분으로 정정했다.
- **수정 파일**: `docs/DERIVED_SUPPLY_E2E_AND_MEMORY_VALIDATION_2026-10-06_KO.md`, `docs/DERIVED_SUPPLY_MAIN_PUBLICATION_2026-10-06_KO.md`, 이 세션 문서.
- **검증 방법/결과**: production cache 코드, `ExactCompiledSupplySharingTest`, `ExactStagedSupplyCanonicalCostTest`, `InvariantFoutSharingDockerProof`, `OwnedRefedReuseTest`의 해당 메서드와 기존 게시 증거를 대조했다. 문서만 변경하며 새 DML 실행·메모리 실험 결과를 추가하지 않는다.
- **잔여 이슈**: 특정 staged REFED sharing이 자동 선택·실행되는 DML 회귀와 실제 메모리 압박 측정은 추가 작업이다. 정확한 last-consumer 해제와 residency 비용 최적화는 현재 구현 범위 밖이다.
- **잠재 회귀 위험/감지**: 실행 코드 변경으로 인한 회귀는 없다. 기존 검증과 새 계획을 혼동하거나 planned copy의 LRU 제외를 worker RAM 상주 보장으로 오해하지 않도록 코드 링크·검증 범위·측정 기준을 분리했다.

## Derived supply sharing 자동 DML 실행과 메모리 측정 — 검증 완료, 후속 진단 분리

- **요청/환경**: 사용자가 후속 검증 보고서대로 실행을 요청했다. 같은 전용 worktree의 `ada24ffd4b`를 기준으로 진행하며 기존 실험은 수정하지 않는다.
- **문제 정의**: 후보 지정 없이 선택된 staged REFED sharing의 DML E2E 및 planned copy 유지에 따른 실제 메모리 관측을 한 실행에서 연결해야 한다.
- **해결 계획**: source pool A의 public 공급을 A/B의 보호된 연산에서 사용하는 invariant/updated DML을 작성한다. test-only probe가 DMLScript 전체 실행, 선택 receipt와 canonical recost, 실제 공급 생성 횟수를 연결한다. 기본 OFF인 관측 계측으로 planned/legacy/single-use 생성·재사용·정리 사유를 기록하며 cache key/lifetime/비용/합법성은 바꾸지 않는다. 새로운 Docker lane과 매 case의 새 worker에서 RSS·GC·spill을 측정한다.
- **수정 범위**: 별도 Docker runner와 회귀, test-only DML probe, REFED ownership 관측 및 해당 회귀. root가 shared target 빌드·Docker 실행·최종 증거를 통합한다.
- **검증/잔여 이슈**: 작은 자동 선택 fixture를 먼저 고정하고 데이터 크기·동시 copy 수를 확장한다. 결과는 후속 기록에 추가하며 구현 중 상태를 완료로 해석하지 않는다.
- **잠재 회귀 위험/감지**: 관측이 객체를 추가 retain하거나 cleanup 예외를 숨기는 위험을 unit 및 코드 리뷰로 검사한다. 자동 선택 없는 정상 실행, 다른 source/version의 공유, 누락된 group, dropped audit event를 성공으로 계산하지 않는 harness 회귀를 둔다.

### 자동 공유 검증 중 확인한 fixture·관측 문제 — 수정 및 재검증 중

- **증상/원인**: 작은 S의 matmul은 native 요청에 실어 보내는 계획이 REFED보다 싸서 공유 공급을 선택하지 않았다. Updated matmul producer도 합법적인 FED/LOUT 결과를 택해 intended FOUT staging 경로를 실행하지 않았다. 이는 coverage 미충족으로 실패 처리했으며 수치 결과 성공을 공유 성공으로 집계하지 않았다. 보호 입력의 elementwise 결과를 더하는 대안은 compiler가 Nary plus로 합쳐 현재 oracle의 privacy-safe placement 부재로 실패했다.
- **대응/의사결정 근거**: 후보·privacy·비용 규칙을 변경하지 않고 실제 자동 선택과 native supply 비용을 조사한다. 원래 실패 증거는 각 frozen Docker run에 보존한다. 반복 간 공유가 필요해지는 invariant 데이터 크기를 늘린 결과 Local/Global에서 GET/PUT 1회가 확인됐다.
- **관측 도구 수정**: DML 종료 이후 통계용 UDF를 같은 coordinator에서 보내면 strict runtime audit가 거부하므로 별도 observer JVM으로 분리했다. observer와 실패한 DML probe의 Netty client 종료를 추가했다. audit의 lifecycle과 supply를 source/version/group/layout으로 연결하고 실제 dispatch GET/PUT과 대조하며 dropped event·logging 실패를 거부한다.
- **정리 증거 보강**: WORKER_RESET의 metadata discard만으로 remote cleanup을 성공이라 보고하지 않는다. 기존 CLEAR의 모든 worker 성공 응답을 기다린 결과만 기본 OFF audit에 전달한다. 요청·cleanup 정책은 변경하지 않는다.
- **현재 메모리 관측**: 24 MiB copy 3개, 총 약72 MiB를 1 GiB/256 MiB worker heap에서 모두 1회 생성 후 재사용했다. 일반 cache 예산64 MiB를 넘는 planned 유지가 관측됐다. Worker cache가 비활성화되어 FS spill은0이며 RSS·GC·수명 이벤트를 기록한다. 최종 source의 재실행 결과는 결과 보고서에 정리한다.
- **확대 회귀**: 관련14개 클래스75건 중68건 통과, 기존 ordinal reflection fixture 두 클래스7건에서 constructor lookup 오류가 발생했다. production ordinal 코드는 이번에 수정하지 않았으며 HEAD baseline overlay로 귀속을 재현 중이다. 이 실패를 전체 통과로 표시하지 않는다.
- **잠재 회귀 위험/감지**: 기본 OFF 계측이 cache 소유·예외 처리에 영향을 주지 않도록 audit/owned-cache 회귀를 수행한다. Worker CLEAR 성공과 coordinator metadata retirement를 분리해서 검증한다.

### 최종 결과 — 2026-10-07 완료

자동 DML4/4 및 메모리 profile2/2가 통과했다. Updated는 새 outer version3개를 각각 inner loop에서3회 사용하는 사례로,9번 공급·3번 생성·6번 hit와 GET/PUT3회를 검증했다. Java75건 중68건 통과와 기존 reflection 오류7건의 HEAD 재현, Python35건 통과를 구분해 기록한다. 자세한 완료·잔여 범위는 [다음 날짜 세션 기록](SESSION_ISSUES_2026-10-07.md)과 [최종 결과 보고서](DERIVED_SUPPLY_AUTOMATIC_E2E_RESULTS_2026-10-07_KO.md)에 있다.

### P1_FULL / GLM 함수 경계 correctness 수정 — 10월 7일 main 병합 검증으로 이어짐

- **요청/원칙**: provenance/authority/binding 오류를 수정한다. runtime repair, privacy 완화, 임의 후보 삭제, planner 시간·메모리 budget 또는 부분 Global 처리는 추가하지 않았다. TW/TR 배치와 recompile CP/FOUT 규칙을 유지한다. base commit은 `7b0656c29c456cec47ab04ea456265c057763b83`, 증거 root는 `/home/mchoi/fedplanner-boundary-correctness-20261006`이다.
- **환경/재현**: 실제 P1_FULL 및 GLM, worker 1개/3개, 동일 Docker image와 cost environment. `bash scripts/fedplanner/run_LAN_docker.sh --function-boundary-compare --artifact-root <증거 root>/<campaign> --variant B --cases <case names> --jobs 2`. 각 campaign의 `runner-settings.json`, `cases`, `results/B/*.command.json`에 config/input/실행 명령을 보존했다. 이 lane은 compile-only이며 runtime 성공과 구분한다.

**문제 정의와 원인**

1. GLM의 `abs(Y)` 결과를 FOUT으로 materialize할 때 함수 formal TRead의 native worker-map authority가 graph action에 보존되지 않았다. 다른 owner나 선택되지 않은 clause의 worker pool을 빌리면 잘못된 계획이 되므로 exact selected authority가 필요하다.
2. P1의 PCA→kmeans 및 반복 `scale` 호출에서 physical realization 교체 후 DIRECT binding과 함수 반환 alias가 낡았다. VALUE_MAP을 통해 보장되는 고정 worker pool도 native 연산의 authority로 전달되지 않았다. 반복 함수 formal의 임시 연결과 최종 binding 완결 조건을 구분하지 못했다.
3. 통합 과정에서 BROADCAST 출력의 worker 정보를 ROW/FULL **원본 seed anchor**로 돌려주는 resolver와 이를 비교하는 publication이 불일치했다. 이를 고친 뒤에도 GLM `glm_log_likelihood_part`, `glm.dml:869:28 b(*)` input1에 `Final publication has an unbound relocation action`이 남았다. 정확한 source는 유일한 `abs(Y)`의 `value-523`이며, AVAILABLE **CP/FOUT/BROADCAST DURABLE_MAP** 출력과 목적지 X의 ROW anchor는 같은 worker 19101/19102/19103을 사용한다. 기존 seed resolver는 FED FOUT/input lineage 중심으로 조회하여 이미 materialize된 CP 출력의 exact owned BROADCAST anchor를 놓쳤다. binder는 이 출력으로 DIRECT 연결하지만 publication에는 직접 연결 가능성이 기록되지 않았다.
4. 복원된 native TRead metadata leaf는 candidate inputBindings 없이도 정확한 CFG writer 관계를 갖는다. joint origin 추적이 이를 놓쳐 정상 VALUE_MAP projection을 거부하고 support scope를 2→8로 늘렸다.

**해결과 의사결정 근거**

- `DerivedFoutAnchorAuthority`로 exact owner realization/clause를 보존하고, selection·Exact factor·emission이 동일한 selected-owner pool 계약을 검사한다. `DerivedFoutAnchorCompatibility`와 fixed-pool VALUE_MAP proof는 모든 선택된 source를 검사하며, 선택되지 않은 좋은 clause나 seed 없는 cycle의 authority를 빌리지 않는다. 가능한 map의 `Node.anchors` 합집합으로 우회하지 않는다.
- 함수 반환 alias 생성 책임을 `LogicalBoundaryRealizations`로 일원화하고 physical 재생성 뒤 DIRECT/VALUE_MAP closure를 완결한다. 반복 호출의 formal input은 분석 중 provisional VALUE_MAP으로 연결하되 최종 AVAILABLE fact는 완전한 binding을 요구한다. 전이적 metadata 변경은 native proof cache를 무효화한다. provisional/coupled closure를 제거한 격리 비교에서 원래 P1 실패가 재현되어 이 변경을 유지했다.
- 고정 VALUE_MAP pool을 DIRECT와 relocation binder에도 연결한다. DIRECT의 partition-axis exactness와 relocation 생략에 필요한 전체 physical geometry exactness를 별도로 추적한다. ROW의 열 범위가 다른 경우 DIRECT authority는 유지해도 전체 geometry 동일성은 인정하지 않는다.
- **출력 후보와 materialization FType이 BROADCAST임을 별도로 확인한 경로에서만**, seed anchor와 목적지의 정규화된 worker endpoint 집합을 비교한다. ROW/COL/FULL은 기존 엄격한 physical-pool 비교를 유지한다. 정확한 source occurrence/value-version, consumer/input obligation, selected receipt 및 runtime residency 검사를 유지한다. 사용되지 않은 action을 삭제해 실패를 숨기지 않는다.
- native TRead metadata leaf의 joint origin 추적은 `analysis.transientCompatibilityForReader(reference)`의 exact owned writer realization을 따른다. 같은 pool·변수 이름·계산 operand를 alias 증거로 삼지 않는다. 불변 source projection의 완료 결과만 identity key로 memoize한다. 동일 106 nodes/326 alternatives/3 rows/25 VALUE_MAP clauses를 보존하고 support scope 8→2를 확인했다.
- **수정 파일**: `PlacementRelationClosure`, `LogicalBoundaryRealizations`, `NativePlacementContinuity`, `PlacementIdentity`, `NeutralPlacementGraph`, `PlacementAnalysis`, `DerivedFoutAnchorCompatibility`(신규), `JointValueMapRelations`, `CandidateSelections`, `ExactPhysicalModel`, `PlacementEmissionTransaction` 및 해당 Java 회귀 테스트. 비용식/runtime 정책은 변경하지 않았다.

**검증 근거와 진행 상태**

- 최종 검증 snapshot은 `engine-v29/freeze-receipt.json`이다. source 19개와 production overlay 139개 class 해시를 기록했다. Closure SHA256 `e3bfc11da67be0c50bc08ba353d312206ceb38a1b9b5b08cca02a0b174e76bde`, PlacementIdentity `e3214519b33482d991636531eabb93a207484627129a02e92500e862155bebb2`. 독립 architecture 검토 CLEAR.
- v29c focused native VALUE_MAP **11/11 PASS**, 원래 P1 producer-replacement 회귀 **1/1 PASS**. 실제 ROW seed를 가진 derived BROADCAST output, 다른 endpoint 거부, partition/full geometry 구분, mixed-pool 및 dynamic-layout 음성 검사를 포함한다. `p1-broadcast-resolver-v29c/{test,p1-single}.log`.
- **v29 실제 Docker P1_FULL**: Local W1 **PASS 70.537초**, Local W3 **PASS 56.334초**, Global Exact W3 **PASS 53.648초**. 모두 `planningSucceeded=true`, `runtimeExecuted=false`; 전체 계획 선택 성공이며 부분 Global 결과가 아니다. `p1-v29-local/results/B/P1_FULL_w{1,3}.json`, `p1-v29-global/results/B/P1_FULL_w3.json`.
- **v29 Maven**: 20 classes, 207 tests 중 **206 PASS/기존 skip1**, failure/error0, `test jar:jar` 성공 (`v29-maven-results.json`). 수정 source 전체 hash가 frozen engine과 일치하며 관련 308 class의 `javap -p -c -s -constants` 출력도 동일하다 (`v29-maven-frozen-parity.json`). Docker 계획 선택 overlay와 Maven runtime jar 사이에 의미상 class 차이가 없음을 확인했다.
- **v29 추가 검증**: frozen joint model proof **10/10 PASS**, exit0, 329.171초 (`v29-joint-proof-result.json`, `v29-joint-proof.log`). 새 Maven jar의 aggregate/shape/linear/control/branch_true/branch_false 수치 runtime **6/6 PASS**, 기대값 일치, fallback·repair 각0 (`v29-numeric-summary.json`, `v29-numeric-runtime/run-geo_fgnh/receipt.json`).
- **v29 GLM 실패/진행 중**: Local W1/W3 모두 `Final publication has an unbound relocation action`으로 실패했다 (985.787초/803.464초, `glm-v29-local/results/B/glm_w{1,3}.json`). BROADCAST seed FType 비교 수정만으로 GLM 전체 문제를 해결했다는 가설은 기각한다. 새 first-action inventory (`glm-v29-local/diag-unbound-v1`)와 축소 DML을 재현 중이다. 별도 scratch에서 explicit FOUT output anchor를 publication resolver가 무시하는 RED를 확인했지만, 실제 실패와 대조 전이므로 main에 반영하지 않았다.
- **v30/v31 최종 수정 검증 중**: `directSourcePlacements` 전용 조회에 exact AVAILABLE source realization의 owned output pool 증명을 추가했다. 전역 resolver/후보 domain/CP-FOUT generation gate는 바꾸지 않았다. 기존 seed 증명도 유지한다. 실제 CP/FOUT/BROADCAST 원인을 포함한 회귀 13/13 및 원래 P1 회귀 1/1 PASS, 동일 coarse state의 pool A/B 선택 음성 검사 PASS, 독립 검토 CLEAR. `glm-publication-authority-v31b`; 실제 first-action 증거는 `glm-v29-local/diag-unbound-v1/first-action-compact.txt`. main에 반영한 뒤 새 Maven을 실행 중이다 (`v31-maven-command.json`). 실행 중인 frozen `engine-v30-candidate`와 main의 production 차이는 같은 helper의 설명 주석뿐이며 Maven 후 class 의미 동등성을 검사한다. 실제 P1 Local W1/W3·Global W3 모두 PASS (82.268/65.159/63.865초, `p1-v30-{local,global}`), 추가 joint model proof 10/10 PASS (293.576초, `v30-joint-proof-result.json`). GLM W1/W3는 `glm-v30-local`에서 실행 중이다.
- joint origin 회귀는 수정 전 RED 1/3, 수정 후 관련 **14/14 PASS** (`native-alias-origin-{red,green}`). root origin fix와 frozen v23를 사용한 host joint model proof **10/10 PASS** (`native-alias-origin-green/joint-proof.log`); 이는 v29 전체 최종 검증을 대신하지 않는다.

**실패 이력과 기각한 가설**

- v23 P1 Local W1/W3·Global W3는 통과했지만 GLM W1/W3는 unbound relocation으로 실패했다. v24 fixed VALUE_MAP relocation bridge만으로도 GLM은 실패했다. v26은 전체 geometry 검사를 DIRECT까지 적용하고 BROADCAST raw seed 비교를 고치지 못해 P1·GLM 모두 실패했다. 각각 `final-glm-local`, `glm-v24-local`, `p1-v26-{local,global}`, `glm-v26-local`에 보존했다.
- P1 v26의 `value-31`에 여러 producer가 섞였다는 가설은 **기각**했다. division `Y` producer는 유일하며 두 사용 위치는 같은 compiled key와 같은 Hop 객체를 참조한다 (`p1-v26-local/diag-source-identity-v1/result-summary.txt`). 해당 noncausal multiple-producer 확장은 제거했다. GLM의 formal Y도 abs 출력의 predecessor일 뿐 동일 value가 아니다.
- 앞선 GLM v2 계획 선택, v3 numeric runtime 6/6, v4 joint runtime 12/12 성공은 과거 snapshot 근거로만 보존한다. v23 확대 joint runtime은 model-proof 고정 300초 제한 및 l2svm candidate-audit JSON 직렬화의 3GiB heap 부족을 겪었다. 이 실패를 planner budget이나 후보 축소로 우회하지 않았다. mutable target을 Maven이 교체하는 동안 실행한 별도 host proof의 ClassNotFound도 유효한 회귀 결과로 계산하지 않는다.

**잔여 이슈와 잠재 회귀 위험**

- GLM v29 실패의 exact output-authority 누락은 반영했으며 v30 전체 계획 선택/최종 Maven 재검증이 진행 중이다. 별도 합성 `NullFunctionOpBoundaryIdentityPcaContractTest`의 `PLACEMENT_FUNCTION_ROOT_UNPROVEN|function=pca`는 exact base commit `7b0656c`의 깨끗한 detached worktree에서도 원본 test 1개가 동일하게 실패했다. test blob도 동일하므로 기존 baseline 문제로 구분하고 수정하지 않았다 (`null-function-exact-head-7b0656c/{result-summary.txt,test.log,evidence-sha256.txt}`). 큰 Global cost-factor 표현/리소스 문제, 전체 14개 workload 및 대규모 P1/GLM 수치 runtime은 이 작업의 완료 주장에 포함하지 않는다.
- 잘못된 metadata authority는 다른 worker나 선택되지 않은 clause의 데이터를 사용할 수 있다. exact owner/foreign-clause/다른 endpoint/geometry 음성 테스트로 감지한다. closure 누락·순서 의존은 반복 함수, cache withdrawal, source 순서 교환 회귀로 검사한다. runtime은 계획을 그대로 실행하며 fallback·repair를 추가하지 않는다.

- **10월 7일 상태 정정**: 위 v30/v31의 실행 중 표기는 과거 체크포인트다. v31 Maven은 209 tests 중 208 PASS/기존 skip1, failure/error0으로 완료했다. v30 GLM W3는 final graph publication을 통과해 planner 단계에 도달했지만, 전체 완료 전에 사용자의 origin/main 병합 요청으로 두 GLM container를 종료했다. 실패로 분류하지 않으며 병합본으로 다시 검증한다. 상세 통합 기록은 `SESSION_ISSUES_2026-10-07.md`에 이어 쓴다.
