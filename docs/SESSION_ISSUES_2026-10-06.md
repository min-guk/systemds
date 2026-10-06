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
