# FedFirst·AggLocal 로컬 정책 우선 경량 first-feasible 계획

- 날짜: 2026-09-21, 사용자 최신 요구 반영.
- 이 문서는 수정된 실행 계획이다. **후속 구현·실험의 실제 상태는 [실행 보고서](G009_FEDALL_HEURISTIC_STREAMING_EXECUTION_STATUS_2026-09-21.md)에 기록한다.**
- 이 문서는 이전 strict streaming/single-pass 계획을 대체한다. 기존 링크를 유지하기 위해 파일명은 그대로 둔다.
- 핵심 계약: **각 단계에서 정책에 가장 가까운 대안을 먼저 시도하고, 충돌하면 필요한 선택을 되돌리며, 완전한 합법 계획 하나를 찾으면 종료한다.**
- 공통 candidate 공간·privacy·runtime legality·support/authority 보존. DP-local/global의 목적함수와 탐색은 변경하지 않는다.

## 1. 요구사항과 이전 계획에서 제거할 조건

| 구분 | 새 계약 |
|---|---|
| 목적 | 전역 최적 계획이 아닌, 로컬 정책 선호를 우선한 합법 계획 하나 |
| 단계별 선택 | 아무 feasible 대안이 아니라 명시적 정책 순서의 첫 대안부터 시도 |
| 되돌림 | 허용. 상위 선택과 support/함수/alias/이동 제약이 충돌할 때 차선으로 복구 |
| 종료 | 첫 **완전한** 합법 witness에서 종료. 더 좋은 전역 비용·FED 수·canonical tie를 찾지 않음 |
| 스트리밍 | 가능하면 기존 relation을 지연 소비한다는 구현 방향이지 엄격한 single-pass 보증이 아님 |
| 금지 | 후보를 공통 공간에서 삭제, privacy 완화, 미확정 계획의 성공 receipt, DP/runtime fallback |

다음은 더 이상 선행 완료 조건이 아니다: rollback=0, 재귀 호출=0, 모든 결정 전 공동 continuation summary 인증, 14개 workload 전체 frontier 폭/Cartesian 상한 census. 합류·함수·loop 반례는 **국소 복구와 합법성 회귀 테스트**로 유지하되 일반적인 무백트래킹 알고리즘을 증명하려고 구현을 멈추지 않는다.

독립 search-space 전수 인증은 별도 과제다. 이번 변경에는 작은 보호 입력 oracle·공간 불변성 검사를 적용하고, 전체 workload 공간 인증을 경량 selector 구현의 선행 조건으로 끌어오지 않는다.

## 2. 현재 코드와 실제 병목

아래 소스 prefix는 `src/main/java/org/apache/sysds/hops/fedplanner/placement/`다. 행 번호는 계획 수정 시 작업 트리 기준이며 중단된 미검증 변경을 포함한다.

| 관측 사실 | 근거 |
|---|---|
| 두 adapter는 같은 state selector를 사용 | `adapter/FedAllPlacementAdapter.java:57–71`, `adapter/HeuristicPlacementAdapter.java:55–80` |
| state 선택은 이미 첫 해에서 멈추지만 뒤에서 candidate 조합을 별도 선택 | `selector/PolicyFirstFeasiblePlacementSelector.java:111–123,165–195,418–475`, `selector/PolicyCandidateSelectionView.java:52–58` |
| candidate 첫 feasible probe 뒤 최적 탐색 또는 canonical 최적화가 계속됨 | `CandidateSelections.java:2187–2255`의 `solve`, `canonicalizeProvenZeroEmissionOptimum` |
| candidate leaf·최종 materialization·receipt 재구성에도 relocation 최적화가 남음 | `CandidateSelections.java:2279–2297,2456–2489,2728–2762` |
| support 재사용만으로는 전체 prefix 검사·잘못된 조합의 반복 방문을 제거하지 못함 | `CandidateSelections.java:1403–1434,2416–2425,2500–2525` |
| AggLocal은 단순 이동 최소화가 아니라 demotion/local-prefix/reentry 정책을 가짐 | `adapter/HeuristicPlacementAdapter.java:199–293` |
| certificate에 incumbent/upper-bound 결합과 성공 시 bound 검사 존재 | `selector/PlacementCertificate.java:12–43` |

기존 GLM Heuristic 진단은 **첫 feasible candidate probe 자체**에서 오래 걸렸다. 45.80초·79.57초 stack이 같은 component feasibility 경로에 있었으므로, “첫 해 뒤의 최적화만 제거하면 해결”이라고 가정하지 않는다. 두 축을 함께 수정한다: **최적화 재탐색 제거 + 첫 해까지의 충돌 전파/중복 검사 감소**. 기존 원시 로그·해시는 [이전 실행 상태](G009_FEDALL_HEURISTIC_STREAMING_EXECUTION_STATUS_2026-09-21.md)와 본 문서 이전 Git revision의 진단 기록을 참조한다. Timeout은 성공 성능 표본이 아니다.

## 3. 단계별 로컬 정책의 정확한 의미

### 3.1 hard constraint와 선호도 구분

먼저 privacy/runtime/authority와 **해당 selector의 명시적인 정책 제약**을 적용한다. 그 안에 남은 대안은 우선순위로 정렬한다. 선호도가 낮다는 이유로 공통 candidate를 삭제하거나 해당 selector의 차선 복구 경로까지 없애지 않는다.

- **FedFirst:** 실행 가능한 FED/FOUT 우선. 총 FED 수나 전체 이동 수의 전역 maximum/minimum을 구하지 않는다.
- **AggLocal:** 기존 demotion·local-prefix·허가된 reentry를 먼저 보존한다. marker에서는 허용된 FED/LOUT를 우선하되 기존 CP/LOUT 대안도 남긴다. local-prefix에서는 기존 정책대로 CP/LOUT를 유지한다. 정책이 금지한 재업로드를 더 좋은 비용이라는 이유로 허용하지 않는다.
- AggLocal을 무조건 MOVEMENT_FIRST로 바꾸지 않는다. 현 기본 adapter는 같은 FedFirst state ordering 위에 policy projection을 적용하므로 이 경계를 유지한다(위 소스표).

### 3.2 제안 우선순위

이미 선택한 prefix를 S, 현재 결정 대안을 a라고 할 때 다음 사전식 순서를 사용한다. 이는 **로컬 선택 정책**이며 전체 계획 점수의 합을 최적화하는 식이 아니다.

`K(a | S) = (policy state rank, input rank, anchor rank, new-action count, original identity rank)`

1. **Policy state rank:** FedFirst 기본은 `FED/FOUT → FED/LOUT → CP/FOUT → CP/LOUT`. 실제 legality 또는 AggLocal projection에서 금지된 상태는 순위와 무관하게 제외한다. TR/TW·recompile의 기존 금지는 유지한다.
2. **Input rank:** 같은 상태에서는 FED의 PRESENT 입력이 많은 순서, CP의 PRESENT 입력은 적은 순서. 서로 다른 row의 입력을 합쳐 가상의 최선 row를 만들지 않는다.
3. **Anchor rank:** 같은 조건이면 실제 geometry·worker·authority가 정렬된 입력이 많은 순서. 기존 helper가 boolean만 반환하면 이를 개수와 혼동하지 않고 해당 비교 계약을 명시적으로 맞춘다.
4. **New-action count:** 현재 prefix가 이미 선택한 동일 물리 action은 재사용하고, 이번 대안이 실제로 추가하는 relocation·local/FOUT materialization 수가 적은 순서. 미래 consumer의 예상 공유 효과나 전체 최소 이동 수는 계산하지 않는다. 아직 근거가 없는 action을 무료로 간주하지 않고 필요한 binding을 같은 지역 선택 단계에서 결정한다.
5. **동률:** 기존 occurrence/rule/emission/support/action의 안정적인 original identity rank. HashMap·메모리 주소·allocation 순서에 의존하지 않는다. **결정적 직렬화와 전역 canonical optimum 탐색은 다르다.** 전자는 유지하고 후자는 제거한다.

상태·row·action을 여러 단계에서 결정해도 위 우선순위를 뒤집지 않아야 한다. 예를 들어 높은 FED/FOUT 상태에서 지원 row가 없으면 그 상태를 확정하고 끝내지 말고 state 선택 단계까지 복구한다.

### 3.3 차선으로 가는 조건

- 높은 순위 대안이 현재 exact support/권한/경계와 충돌하면 다음 대안을 시도한다.
- 뒤에서 충돌이 드러나면 선택 trail을 되돌려 원인이 되는 가장 가까운 미해결 결정의 차선을 시도한다. 최초 구현은 검증하기 쉬운 stack 기반 복구를 사용하며, conflict-directed jump는 정확한 원인 집합이 있을 때만 추가한다.
- 높은 순위 대안으로 완전한 합법 계획을 얻었으면, 낮은 순위 대안이 더 좋은 전역 결과를 낼 수 있어도 탐색하지 않는다.
- 높은 순위 분기가 단지 오래 걸렸다는 이유로 불가능하다고 판정하지 않는다. 자원 한도 도달은 `POLICY_SEARCH_LIMIT`/`TIMEOUT` 같은 미완료이며, `NO_LEGAL_PLAN`도 성공도 아니다. 열거를 끝낸 범위 밖에 infeasible 증명을 확대하지 않는다.

예: 현재 FED 선택이 새 이동 1개, CP 선택이 이동 0개라도 FedFirst는 먼저 FED를 시도한다. 같은 FED 대안 중 기존 action 재사용과 새 action 생성이 경쟁하면 재사용을 먼저 시도한다. 그 선택이 뒤의 공유 source 제약과 충돌할 때만 차선으로 복구한다.

## 4. 실행 알고리즘과 변경 경계

```text
immutable full candidate/support 관계
  → selector-local policy projection + 기존 인덱스
  → 결정적 producer/dependency 순서의 결정 단위
  → 현재 prefix와 양립 가능한 대안을 로컬 정책순으로 시도
  → 실제 선택 row/support/action을 trail에 기록하고 영향받은 제약만 전파
  → 충돌이면 복구, 아니면 다음 결정
  → 완전한 state + candidate + relocation witness 확보
  → 기존 합법성 검증과 authorized application
  → POLICY_FEASIBLE 반환, 추가 해 탐색 0
```

### A. state·candidate 연결

`PolicyFirstFeasiblePlacementSelector`의 기존 state propagation·alias grouping·독립 component 분해는 재사용한다. producer/dependency의 결정 순서와 cycle 동률을 고정한다. 변수 방문 순서가 몰래 정책 자체를 바꾸지 않도록 테스트한다.

**component 완료 조건을 state-only에서 실제 witness까지 연결**한다. candidate/relocation 완성이 실패하면 해당 state 선택에 실패가 전달되어 다른 state를 시도할 수 있어야 한다. state assignment 하나를 고정한 뒤 candidate 실패를 전체 불가능으로 반환하는 경계를 남기지 않는다. component 사이 독립성은 기존 모든 legality/support/authority/action 관계를 포함해야 하며, 단순 데이터 DAG 연결만으로 분리하지 않는다.

### B. candidate 첫 해 선택

`PolicyCandidateSelectionView`에 명시적인 policy-first-feasible 경로를 연결한다. `CandidateSelections`의 기존 exact API와 DP 호출은 보존한다.

- 첫 probe 뒤의 exact optimization, zero-emission optimum canonicalization, 더 좋은 incumbent 탐색을 policy 경로에서 제거한다.
- row를 현재 prefix의 로컬 정책순으로 정렬/지연 방문한다. 선호가 낮지만 유일한 합법 continuation인 row를 사용할 수 있도록 낮은 순위 대안도 남긴다. 현 materialization-maximal projection을 그대로 쓰면 차선을 잃는지 테스트하고, 새 정책은 단순 목적함수 top-row 절단 대신 순서를 사용한다.
- support reference→owner/row 인덱스와 이미 선택된 source witness를 이용해 명백히 맞지 않는 row를 재귀 진입 전에 거절한다. 참조의 exact identity·proof/authority를 유지한다.
- 전체 후보 Cartesian list를 새로 만들지 않는다. 동일 prefix의 지원 목록 재정렬·전체 selected map 재생성·전 관계 검사를 줄인다.

### C. relocation도 첫 해로 종료

`RelocationSelections`에서 후보 witness의 실제 privacy/anchor 검사를 재사용하되 policy용 completion은 **새 물리 이동 적음 → 안정적 tie** 순서의 첫 합법 action 조합에서 멈춘다.

candidate leaf, `materializeBest`, `requireBest`, adapter의 후검증까지 점검한다. 끝에서 `minimumPhysicalEmissionCount()` 또는 exact canonical selector를 다시 호출하면 가벼운 경로가 아니므로 policy의 성공 판정·점수는 **실제로 선택한 action의 개수**로 계산한다. 검증기에서 최적 action을 재선택하지 않고 선택된 action의 합법성만 검사한다. 정확/DP용 최소비용·최소이동 API는 바꾸지 않는다.

### D. 결과·실패 계약

- 성공은 `POLICY_FEASIBLE`, `optimalityProven=false`에 해당하는 명시적인 의미를 갖는다.
- upper bound를 incumbent와 같게 채워 global optimum을 증명한 듯 표현하지 않는다. 기존 certificate 구조를 재사용한다면 POLICY_FEASIBLE에서 bound의 의미/검사를 분리하고, DP의 EXHAUSTED/TIGHT_BOUND_EQUALITY 계약은 그대로 검증한다.
- 실패 원인은 exact conflict, policy projection 제한, 검색 자원 한도, invariant bug를 구별한다. 후검증 실패를 runtime으로 넘기거나 다른 planner로 자동 대체하지 않는다.

## 5. 구현 순서와 게이트

| 순서 | 작업 및 주요 파일 | 통과 기준 |
|---|---|---|
| L0 | 중단된 작업 diff 분리, 정책 계약·작은 테스트 고정 | 기존 exact 기대와 새 local-first 기대를 분리. C0를 다시 만들거나 이미 끝난 push를 반복하지 않음 |
| L1 | candidate/relocation의 최적성 재탐색 제거 — `CandidateSelections`, `PolicyCandidateSelectionView`, `RelocationSelections` | 각 단계 local rank 적용; 첫 완성 witness 뒤 추가 탐색 0; exact/DP API는 기존 oracle 통과 |
| L2 | state→row→action 실패 전달·국소 복구 — `PolicyFirstFeasiblePlacementSelector` 및 두 adapter | 상태만 가능한 거짓 성공 방지; 높은 정책 branch 성공 시 차선 탐색 0; 불가능한 branch는 다른 합법 state로 복구 |
| L3 | 첫 해 도달 비용 개선 — 기존 support/reachability index, selected receipt map, trail/refcount | cached support·현재 source 조건 재사용; 영향받은 제약만 검사; 작은 fixture에서 기존 full validator와 일치. 관계 삭제·무근거 candidate cap 없음 |
| L4 | certificate·후검증·DP 격리 점검 | policy score는 실제 선택 action과 일치; 최적성 주장 없음; 공통 분석 fingerprint/decoded 작은 공간 불변 |
| L5 | 표적 테스트·빌드 후 GLM canary | worker4·보호 입력·planning-only 각 planner 1회. first-feasible 도달/선택/전체 planning 시간 분리. GLM 전체 <60초 목표 |
| L6 | 남은 입력 준비 및 최종 56-cell 검증·보고 | COVTYPE worker4 준비 후 동일 최종 JAR 56/56 정상 receipt. 미실행·실패·timeout은 완료 아님 |

L1만 구현하고 성능 해결을 선언하지 않는다. 이미 **첫 probe 병목** 증거가 있으므로 L2/L3도 필수다. 반대로 일반 factor-graph summary 엔진·전역 width census·대규모 계측 체계를 새로 만들지 않는다. 작은 반례 → 해당 수정 → 필요한 GLM 단회로 피드백을 짧게 유지한다.

## 6. 작은 검증 목록

1. **임의 first-feasible 방지:** 두 대안 모두 완성 가능할 때 높은 로컬 정책 대안 선택.
2. **전역 optimum과 구분:** 높은 로컬 선호가 전체 이동 합계에서는 불리한 fixture에서도 로컬 우선 winner 유지. 전체 합계가 더 좋다는 이유로 낮은 선호를 고르지 않음.
3. **차선 보존:** 가장 선호하는 row가 support 충돌일 때 낮은 선호의 합법 row 사용.
4. **상태까지 복구:** state-only 가능하지만 candidate/action witness가 없는 첫 상태를 철회하고 다른 상태로 완료.
5. **합류·alias·TR/TW·함수·loop:** 공유 source에 서로 다른 realization을 동시에 요구하는 반례, 실제 grounded support 없는 순환, owner/authority 충돌을 거절. 원래 합법 해가 있는 복구 fixture는 성공.
6. **action 재사용:** 동일 physical action 재사용 우선, 서로 다른 authority의 action은 비용이 같아도 합치지 않음. relocation 첫 해는 합법하되 전역 최소이동과 다를 수 있음.
7. **AggLocal 계약:** demotion/local-prefix/reentry가 FedFirst 선호나 이동 tie에 의해 무시되지 않음.
8. **결정성:** node·row 입력 순서 shuffle에도 명시적 순서와 동일한 결과. 원본 identity를 보존.
9. **종료/예산:** state/candidate/relocation별 작은 counter로 첫 성공 뒤 branch 확장 0 확인. 검색 한도 종료에는 성공 receipt 없음. 제한으로 잘린 대안은 불법으로 분류하지 않음.
10. **DP/공통 공간:** 기존 exact oracle 및 selector 호출 전후 공간 불변성 검증. policy 결과를 exact optimum과 같아야 한다는 assertion으로 검증하지 않음.

보호 입력 테스트를 우선한다. PUBLIC-only fixture는 repo 규칙대로 ignore하며, 보호 입력에서 합법적으로 공개된 aggregate 이동은 원시 PUBLIC 입력 fixture와 구별한다. 무시한 case와 검증 공백은 보고한다.

## 7. 검증 workload와 조건 — 기존 합의 유지

| 대상 | 입력/변형 | planner 수 |
|---|---|---:|
| ML10 | `pca, als, alsCG, kmeans, gnmf, gmm, logreg, l2svm, steplm, glm`; P2P2D X=50,000×2,100, supervised Y=50,000×1 | 40 |
| P1 | `P1_FULL`, corrected scalar aggregate sink; X=100,000×68 | 4 |
| P2 | `P2_PREP`, protected preprocessing·명시적 metadata release; X=100,000×1,001, Y=100,000×1 | 4 |
| SliceLine | prepared **ADULT 32,561×13**, **COVTYPE 581,012×54**, 대응 prepared error vector | 8 |
| 합계 | worker=4·privacy 적용·planning-only | **56** |

- FedFirst=`mkl-single-pass`, AggLocal=`mkl-heuristic-first`, DP-local=`mkl-cost`, DP-global=`mkl-exact`. 실제 enum/factory를 receipt와 config에서 확인한다. legacy config로 대체하지 않는다.
- 공식 경로는 `/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/experiments/run_LAN_docker.sh --planning-validation`. 기존 frozen campaign과 별도다. 직접 Java/`run_LAN.sh` 실험을 최종 receipt로 대체하지 않는다.
- coordinator 16GiB·8 active processors의 실제 container 설정, 입력/script/config/privacy/JAR SHA를 고정. X/Y 및 worker partition/descriptor 보호를 유지하고 P2 metadata release opt-in은 P2에만 적용.
- 조건당 **최종 JAR로 1회**, 직렬 실행. 반복 pilot/ABBA 없음. GLM은 DP-local→FedFirst→AggLocal→DP-global; 동일 최종 JAR/manifest면 56행에 재사용한다.
- 기존 planning timeout 180초는 자원 한도이고 GLM 정상 전체 planning **60초 미만**은 목표다. timeout/실패는 성공 baseline이 아니다. 단일 측정으로 통계적 우열을 주장하지 않는다.
- 결과: 전체 초기 planning, 공통 analysis/candidate 구성, selector(첫 해 도달 포함), 검증/application/lowering, wall·RSS를 분리. 포함 관계의 시간을 합산하지 않는다. 최소 기존 timer와 탐색 counter를 사용하고 매-row trace/새 계측 framework는 만들지 않는다.
- 최종 실행 receipt는 `runtime_executed=false`, `execution_seconds=0` 및 완전한 planning/lowering을 확인해야 한다. 입력 분할은 별도 데이터 준비이며 ML 학습 runtime을 실행하지 않는다.

위 입력/실행 조건은 기존 [실행 상태](G009_FEDALL_HEURISTIC_STREAMING_EXECUTION_STATUS_2026-09-21.md), 외부 `experiments/tools/planning_validation.py:14–94`, `experiments/run_LAN_docker.sh:1612–1623,1680–1695`에 근거한다. 이번 계획 수정에서 benchmark를 재실행한 것이 아니다.

## 8. 비용 분석과 위험 관리

전체 planning은 `T_initial = T_frontend + T_analysis + T_policy + T_validate/lower`다. T_policy만 줄여도 공통 analysis가 오래 걸리면 60초를 달성하지 못한다. 이전 DP-local/global의 45.779/44.644초는 참고값이지 이번 새 결과가 아니다.

대안 인덱스 크기를 I, 실제 시도한 state/row/action 수를 A, 처리한 dependency 참조 수를 U라고 하면 목표 비용은 `O(I + Σ local-sort + A + U)`다. **A는 후보 수보다 작거나 선형이라는 보장이 없다.** 첫 해 탐색도 상관된 제약에서 최악에는 지수적으로 커질 수 있다. 이 점을 단순히 “first-feasible이므로 빠름”으로 숨기지 않는다.

경량화의 직접 근거는 다음으로 검증한다: 첫 해 이후 탐색 0, 전체 최적성용 upper/lower-bound 및 canonical 재탐색 0, support 불일치의 조기 전파, exact identity 기반 반복 계산 재사용, 국소 복구. 그래도 첫 해에 시간이 많이 들면 작은 충돌 재현으로 원인을 줄이며, timeout을 피하려고 공통 후보나 보호 규칙을 낮추지 않는다.

| 위험 | 대응 |
|---|---|
| first-feasible만 붙이고 안쪽 relocation 최적화가 남음 | leaf·final score·receipt·adapter까지 call graph 및 종료 counter 검증 |
| 최선 로컬 row만 미리 남겨 유일한 합법 차선을 잃음 | 선호는 ordering으로 표현; 낮은 rank 복구 테스트 |
| state-only 선택이 row 실패를 복구하지 못함 | complete witness callback/failure propagation 및 상태 복구 테스트 |
| 전역 canonical 최적해와 deterministic 출력을 혼동 | original rank tie·직렬화 유지, 최적 tie 재탐색 제거 |
| marker/공유 authority 제약 누락 | AggLocal·protected boundary 테스트와 최종 기존 검증 유지 |
| 전역 전수 인증을 추가해 또 과도한 선행 작업 발생 | 별도 인증 과제와 분리; 이번 변경을 덮는 작은 oracle만 선행 |

## 9. 계획 작성 당시 상태와 실행 판정 기준

- C0 `fe000959c48ffa1172399e49124d082fe42d0c6d`의 `origin/main` push는 이전 실행에서 완료했다. 다시 실행할 단계가 아니다.
- 계획 작성 당시 입력 사전검사는 ADULT worker4 준비 후 **52/56 통과**, COVTYPE 4개 cell 준비가 남았다. 이는 planning 성공 52개가 아니었다. 후속 준비·실행 결과는 실행 보고서에 구분해 기록한다.
- 계획 작성 당시 `CandidateSelections` support 재사용과 boundary 테스트, `RelocationSelections`의 미검증 부분 diff가 있었다. 후속 구현은 이 diff부터 검토·테스트했다.
- 병행 작업의 독립 공간 인증 파일은 이 계획의 소유 범위 밖이며 함께 수정/커밋하지 않는다.
- 다음 실행의 완료 기준은 **로컬 정책 테스트 + 첫 해 종료 계약 + DP/공통 공간 보존 + 동일 최종 JAR 56/56 정상 planning + GLM 시간 보고**다. 알고리즘 구현·workload 성공·60초 목표 달성은 각각 구별한다.
- 실행 후에도 56개 중 실패·timeout을 통과로 세지 않으며, 60초 목표와 전체 workload 완료를 별도 판정한다.
