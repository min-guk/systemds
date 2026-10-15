# MEMO_LOCAL: 5% 인증을 먼저 목표로 하는 선택적 최적화 계획

작성일: 2026-10-15. 기준 구현은 `5734de2021f2841d82539063a6b450db69b9f7cb`다. 이 문서는 새 요구사항에 대한 **구현 전 계획**이다. 기존 실험 결과와 앞으로 검증할 목표를 구분하며, 이 계획 작성 과정에서 optimizer 코드는 변경하지 않았다.

## 1. 결정과 범위

**MEMO_LOCAL의 실행 정책을 MEMO_GLOBAL에서 분리한다.** Global은 지금처럼 효율적인 direct exact를 우선하고 exact 증명을 완료한다. Local은 저렴한 전역 LB와 첫 합법 UB를 먼저 만들고, 5% 인증에 부족한 계산만 추가한다. Tree DP, VE, 원본 factor 평가, 검증과 복원은 공유하되, 호출 순서·대상 영역·종료 정책은 별도로 둔다.

Local에서 메모리는 선택한 작업의 실행 가능 여부와 cache 크기에 영향을 준다. **메모리가 충분하다는 이유로 시작 정책을 전체 exact로 바꾸지 않는다.** 반대로 메모리 부족이 Local 정책의 진입 조건도 아니다. 실제 자원이 고갈되면 인증되지 않은 성공을 반환하지 않는다.

여기서 “최소 계산”은 줄여야 할 성능 목표다. 모든 입력에서 가장 적은 계산으로 인증하는 작업 순서를 미리 안다는 보장은 할 수 없다. 같은 ε에도 일부 문제는 거의 전체 탐색이 필요하며, 작은 tree/VE 문제에서는 기존 exact가 더 빠를 수 있다. 이 경우도 비교 결과에 포함한다.

## 2. 현재 구현에서 바꿔야 하는 지점

| 현재 동작과 근거 | 필요한 변경 |
|---|---|
| [두 모드 모두 root direct를 먼저 호출](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoDualModeOptimizer.java#L144) | Global 경로 유지, Local은 별도 controller로 진입 |
| [cluster 전체를 만든 뒤 root LB와 seed 계산](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoDualModeOptimizer.java#L162) | 저렴한 LB와 첫 UB를 앞당기고 cluster는 요청받은 순서로 생성 |
| [seed repair 영역 확장 및 fullProof 반환](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoFeasibleSeed.java#L123) | Local 초기 seed는 FIRST_FEASIBLE; whole-model exact로 묵시적으로 전환하지 않음 |
| [seed 내부 Direct 및 비용 최소화 열거](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoFeasibleSeed.java#L277) | 첫 YES witness에서 controller로 반환; 기존 [fullProof 테스트 계약](../src/test/java/org/apache/sysds/hops/fedplanner/memo/MemoFeasibleSeedTest.java#L33)은 기존 API에 유지 |
| [조건부 direct에서도 tree는 VE cell 한도 이전에 실행](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java#L334) | `directCells=0` 우회에 의존하지 않고, 작업 scope와 계산량을 명시한 API 사용 |
| [DFS와 고정 degree 기반 분기](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoDualModeOptimizer.java#L228), [incumbent 우선 값 선택](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoDualModeOptimizer.java#L489) | Local은 인증을 막는 최소 LB 영역을 우선 처리; UB 개선 순서와 증명 순서를 분리 |
| [전역 LB와 정확한 5% 검사](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoDualModeOptimizer.java#L430) | 수학적 계약 유지; Local 작업 완료 직후 검사하도록 이동 |
| [기존 dispatch와 원본 계획 검증](../src/main/java/org/apache/sysds/hops/fedplanner/memo/FederatedPlanMemo.java#L126), [모델 결과 재검증](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L554) | 후보·비용·합법성·복원은 그대로 사용 |

이전 실험의 기본 설정에서는 dual 84회 모두 DIRECT/OPTIMAL이었다. GNMF 강제 탐색에서는 cluster가 인증 시간을 줄였지만, cluster 준비 때문에 첫 UB가 늦어졌다. Seed 개선은 UB를 낮추면서도 탐색 노드를 111→4,052로 늘렸다. 따라서 “direct만 끄기”, “cluster를 전부 먼저 만들기”, “더 좋은 seed가 항상 좋은 탐색 순서”라는 세 접근을 이번 설계의 기본으로 삼지 않는다. [실측 근거](COFEE_MEMO_DUAL_MODE_IMPROVEMENT_REPORT_2026-10-15_KO.md#42-gnmf-conflict-search-ablation-각-설정-3회)

## 3. Local의 실행 순서

```text
원본 모델·candidate coverage 확인
  → 저렴한 전역 하한과 proof ledger 생성
  → 첫 합법 계획 탐색: greedy + 필요한 feasibility repair
  → 전체 원본 check=YES / 전체 비용 재계산
  → 5% 검사
      만족: 즉시 반환
      부족: 값싼 초기 seed 개선을 선택적으로 수행 → witness마다 인증 검사
          → 이후 인증을 막는 conflict 중심으로 작은 작업 하나 선택
            - factor 하한 정밀화
            - tree 경계 message / 작은 cluster 하한
            - 조건부 UB 개선
            - 인증을 막는 영역의 boundary 분기
          → 검증된 결과만 ledger에 반영 → 다시 5% 검사
```

전체 root에 대한 monolithic `optimizeDirect()`/`optimize()` 호출은 Local의 시작 경로에서 금지한다. 첫 인증 가능 시점 전에 seed, propagation, tree helper가 그 작업을 대신 수행하는 숨은 경로도 금지한다. 공통 준비 단계는 candidate completeness와 sound한 간단한 propagation 등으로 한정하고, 큰 표 생성이나 반복 propagation은 선택 작업으로 넘긴다.

부분 최적화가 전체 모델을 일부만 읽어도 된다는 뜻은 아니다. 원본 후보의 completeness, 모든 factor scope, shared cost ownership을 파악하고 최종 witness 전체를 검증하는 작업은 필요하다. 줄이는 대상은 불필요한 최적화·표 생성·탐색이며, 모델 구축 비용은 별도 측정한다.

실제로 순수 tree인 문제에서는 필요한 작은 message들을 계산하다가 전체 exact 증명이 자연스럽게 끝날 수 있다. 그것은 허용한다. 다만 “tree이니 먼저 전체를 풀고 나중에 ε를 본다”는 호출 순서는 사용하지 않는다. 첫 UB/LB가 이미 5%를 만족하면 tree message도 더 만들지 않는다.

### 3.1 최초 LB는 가능한 한 싸게

원본 목적함수를 native 비용과 numeric factor의 합으로 둔다. 초기 domain `D`에서

`LB₀ = Σ_v min(r∈D_v) native_v(r) + Σ_f certifiedMinimum(f)`

를 사용한다. 모든 factor에 기존의 검증된 floor가 있다는 계약을 재사용하며, check를 생략하는 것은 하한에만 적용하는 relaxation이다. 음의 비용과 상수도 원래 값 그대로 포함한다. 원본 비용에 인위적인 상수를 더하거나 빼서 상대 오차를 쉽게 만들지 않는다.

초기에는 모든 factor의 Cartesian minima, cost-class 복사, 모든 cluster table을 계산하지 않는다. Native 최소값도 기존 배열을 읽어 필요한 값만 exact 변환한다. 이후 요청된 factor에 대해서만 더 강한 certified minimum을 계산한다. 기존 [termBound](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoDualModeOptimizer.java#L361)는 이 선택적 정밀화의 재사용 후보이며, 처음부터 모든 term에 호출하지 않는다.

### 3.2 첫 합법 계획을 즉시 확보하고 좋은 초기 seed로 개선

기존 closed-factor greedy를 재사용한다. 막힌 경우 위반한 check의 실제 scope에서 repair를 시작하되, Local의 feasibility 탐색은 **첫 원본 YES witness에서 반환**한다. 조건부 optimum을 찾기 위해 witness 발견 후 계속 열거하지 않는다.

작은 작업량을 소비하면 controller로 yield하고 이어서 진행할 수 있게 한다. Seed 작업 한도 도달은 실패한 heuristic 단계일 뿐 원본 infeasibility나 성공 판정이 아니다. 합법 계획을 찾기 어려워 repair 영역이 커져도, 전체 비용 exact solver로 자동 전환하지 않고 feasibility 상태를 이어 간다. 최악의 경우 전체 변수의 합법성 탐색은 필요할 수 있다.

완전한 witness가 없으면 UB는 아직 존재하지 않는다. 부분 선택이나 UNKNOWN check가 있는 계획을 UB로 게시하지 않는다. 최초 합법 witness를 얻으면 모든 원본 cost/check 및 shared movement를 검증한 뒤, 추가 seed 개선 전에 즉시 인증을 검사한다. 기존 [조건부 improve](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoFeasibleSeed.java#L97)는 이후 선택 작업으로만 사용한다.

### 3.3 좋은 seed와 conflict 처리 순서를 분리

후속 요구를 반영해 첫 witness 이후, 인증이 아직 부족하면 **값싼 초기 seed 개선을 선택하고 이후에는 LB를 막는 conflict 정밀화에 집중하는 정책**을 기본 후보로 둔다. FIRST_FEASIBLE은 좋은 seed를 포기한다는 뜻이 아니다. 검증 가능한 첫 UB를 controller에 즉시 알리는 계약이며, 그 뒤 별도 작업으로 seed를 개선할 수 있다.

초기 개선 후보는 비용을 고려한 다른 greedy 순서, shared movement 비용이 큰 지역의 조건부 개선, 관련 boundary를 함께 바꾸는 작은 block 개선이다. 각 후보를 원본 모델에서 검증하고 비용이 가장 낮은 합법 계획을 incumbent로 유지한다. 모든 후보를 반드시 생성하거나 최적 seed를 증명하지 않는다. 첫 구현에서는 기존 conditional improve를 독립된 작은 작업으로 사용하고, multi-start는 별도 ablation을 통과한 경우에만 추가한다.

Seed 작업은 factor 평가·cell 수 기준의 선택적 작업량 안에서 수행하고, 매 witness마다 인증을 검사한다. 초기 개선의 추가 이득이 작거나 더 싼 LB 정밀화 작업이 있으면 conflict 처리로 전환한다. 이 판단은 성능 휴리스틱이며 seed 단계 종료 자체는 전체 Local의 성공이 아니다. 정확한 초기 작업량은 실험으로 결정한다. 초기 UB를 얻기 어려운 경우에는 3.2의 feasibility 진행 규칙을 따른다.

좋은 UB는 인증 목표를 낮춘다. 예를 들어 UB가 120에서 105로 내려가면 필요한 LB는 약 114.286에서 100으로 낮아진다. **같은 유효한 LB에 대해 더 작은 UB는 인증 조건을 악화시키지 않는다.** 다만 seed 생성 비용과 그에 따라 바뀌는 탐색 순서는 별개의 성능 요인이다. 이전 GNMF의 seed 회귀는 [incumbent 값을 우선하는 DFS](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoDualModeOptimizer.java#L492)와 결합된 결과이므로, 그 순서를 새 Local에 그대로 적용하지 않는다.

두 상태를 명시적으로 분리한다.

- **Feasible incumbent:** 현재 최선의 완전한 계획과 UB. UB repair의 시작점이며, scalar UB는 인증·안전한 pruning에 사용한다.
- **Proof frontier와 relaxation state:** 모든 대안 영역, 각 LB, factor ownership, boundary별 message와 불일치 정보. 이 상태에서 다음 conflict를 선택하고 증명 작업을 진행한다.

기본 proof 순서는 incumbent의 변수 값을 따라가도록 만들지 않는다. 합법 seed의 외부 값을 고정하는 것은 UB repair에만 사용한다. Proof 작업의 우선순위는 최소 전역 LB에 영향을 주는 영역, relaxed boundary의 불일치와 하한 약점, 예상 작업량, 재사용 가능한 message로 결정한다. UB 개선 후에도 같은 proof 상태는 유지되며 scalar UB에 따른 인증·pruning만 갱신한다. 후속 conflict 처리 중 더 좋은 합법 계획을 발견하면 언제든 incumbent를 교체한다. 초기 seed를 최종 계획으로 고정하지 않는다.

## 4. 일부만 계산해도 전역 보장을 유지하는 방법

### 4.1 지역 비용을 더할 때와 대안 영역의 최소를 취할 때를 구분

원본 numeric primitive를 서로 겹치지 않는 집합 `I_g`에 배정한다. 변수 scope는 겹칠 수 있다. 그룹의 domain 전체와 포함된 check를 대상으로 얻은 하한 `l_g(D)`는 그 그룹의 모든 전역 합법 선택에 대한 비용 이하이다.

`L(D) = Σ_g l_g(D)`

아직 정밀화하지 않은 그룹도 certified floor로 이 합에 포함한다. 한 factor는 한 번만 계산한다. 새 그룹을 만들거나 합칠 때 기존 기여를 제거하고 대체하며, old/new 하한을 중복해서 더하지 않는다. 기존 [MemoClusterBounds의 numeric ownership 규칙](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoClusterBounds.java#L26)을 유지한다.

반면 domain 분기로 만들어진 영역들은 서로 **대안**이다. 원본 후보 공간을 덮는 영역 `R`마다 하한 `L_R`를 유지하면 전역 하한은 합이 아니라 최소다.

`LB = min(UB, min_open L_R, min_unknown L_R, min_deferred L_R)`

없는 항은 `+∞`로 취급한다. `deferred`는 아래의 ε 기준으로 추가 작업을 생략한 영역이다. 원본 전체 공간에서 합법 해가 존재할 가능성이 있는 모든 미해결 영역이 이 ledger 안에 남아야 한다. 정확히 배제한 infeasible 영역은 제거할 수 있다.

원본 전체 factor graph에서 진짜 독립인 connected component들은 별도로 LB/UB를 구해 더할 수 있다. 공유 비용·check가 하나라도 남으면 그 독립성은 성립하지 않는다. **독립 component 사이에서는 합, 같은 component의 대안 cover 사이에서는 최소**라는 계층을 혼동하지 않는다.

### 4.2 Boundary를 incumbent에 고정한 결과는 UB 작업

Tree/cluster 내부를 exact로 처리해도 외부 boundary를 임의의 한 값에 고정하면 원본 문제의 relaxation이 아니다. 그 비용은 전역 LB로 쓸 수 없다.

- **UB repair:** 밖을 incumbent에 고정하고 내부를 바꾼다. 전체 계획을 재검증한 뒤 UB만 개선한다.
- **LB refinement:** 모든 허용 boundary를 포함해 최소화하거나, boundary별 exact message를 남기고 바깥 문제와 연결한다. 미계산 boundary에도 기존 certified floor를 남긴다.
- **Exact contraction:** 제거할 내부 변수에 연결된 모든 원본 factor를 처리하고 완전한 separator state를 유지한다. 빠진 factor가 있으면 exact contraction이라고 표시하지 않는다.

이 세 가지를 반환 타입 또는 `proofKind=FEASIBLE_WITNESS|RELAXED_LOWER|EXACT_MESSAGE`로 구분한다. UNKNOWN을 허용한 메시지는 RELAXED_LOWER이며, 증명 없이 EXACT_MESSAGE로 승격하지 않는다. 단순히 모든 결과에 같은 `cost` 필드를 붙여 controller의 해석에 맡기지 않는다.

공유 producer·movement, 함수 인자/반환, branch 선택, loop entry/head/last 등은 모두 원본 scope에 따라 boundary에 남긴다. HOP input edge만 보고 cluster를 잘라서는 안 된다. [기존 의존성 설명](COFEE_TREE_DP_DEPENDENCIES_AND_MATERIALIZATION_2026-10-15_KO.md)

### 4.3 5%에 충분한 영역은 exact까지 풀지 않음

유효한 UB가 있고 `LB>0`, `UB≤1.05×LB`이면 즉시 EPSILON_OPTIMAL이다. `LB=UB`로 비용 최적성이 증명되면 OPTIMAL을 반환할 수 있다. Global의 canonical tie 증명 계약은 별도로 유지한다.

Cover 영역 `R`가 `L_R>0 && 1.05×L_R≥UB`를 만족하면 그 영역을 더 정밀화할 필요가 없다. 이 영역은 **ε 목적상 보류**하며, floor 또는 그 최소값을 ledger에 보존한다. Exact로 해결됐다고 표시하거나 bound 없이 삭제하지 않는다. UB가 내려가면 이 판정은 계속 유효하다. `LB≤0`에는 상대 오차 종료를 적용하지 않는다.

여기서 `L_R`는 전체 목적함수의 하한이다. 독립 component `c` 안의 지역 하한 `l_{c,R}`만 있다면, 다른 component의 하한까지 더한 `l_{c,R}+Σ_{d≠c}LB_d`를 전역 UB와 비교한다. Component 비용만을 전역 UB와 직접 비교하지 않는다. 상수 항도 이 합에서 정확히 한 번 포함한다.

보류 영역이 있을 때 OPTIMAL을 반환하려면 그 영역의 floor까지 UB 이상이라는 비용 증명이 필요하다. `1.05×L_R≥UB`만으로 exact 완료를 주장하지 않는다. 이미 보류한 floor가 너무 낮아 LB=UB를 증명하지 못하면, 그 상태에서는 EPSILON_OPTIMAL이라는 정확한 상태를 유지한다.

예를 들어 완전히 독립인 두 component의 interval이 `[900,900]`, `[60,100]`이면 전체는 `LB=960`, `UB=1000`으로 gap은 약 4.167%다. 두 번째 component의 자체 gap이 커도 전체 계산은 종료한다. 모든 cluster를 개별 5%까지 맞추는 방식은 불필요한 계산을 유발한다. 이 수치는 설명용이며 실험 결과가 아니다.

### 4.4 Atomic publication과 UNKNOWN

표 일부를 계산한 최솟값은 전체 표의 하한이 아니다. 불완전한 표를 공개하려면 미계산 부분의 floor까지 포함해야 하며, 첫 구현에서는 완성된 작은 표만 반영하고 중단 시 이전 하한을 유지한다. 부모를 child/sibling으로 교체하기 전에 두 설명을 확보하고, 각 하한에 부모 하한의 max를 적용한다.

UNKNOWN은 원본 YES witness로 인정하지 않는다. LB relaxation에서는 허용할 수 있지만 unresolved 영역의 floor를 남겨야 한다. 그 floor만으로도 전체 5%가 증명되면 성공은 가능하다. UNKNOWN이 인증을 막고 더 이상 해결 가능한 작업이 없으면 UNRESOLVED로 종료한다. Cache를 버려도 이미 게시한 영역별 하한과 미해결 의무는 버리지 않는다.

## 5. 어떤 작업부터 선택할 것인가

목표 부족분은 `Δ = UB − 1.05×LB`다. 양수일 때만 추가 작업이 필요하다. 이 식은 검사와 정책 설명에 쓰며, 새로운 추정치를 certified LB로 승격하지 않는다.

| 상황 | 우선 작업 | 근거/주의 |
|---|---|---|
| UB 없음 | 첫 합법 witness를 위한 greedy/feasibility repair | 하한만 있어서는 ε 인증 불가 |
| 특정 factor/group 하한이 느슨함 | 해당 factor 최소값, 작은 cluster 병합, boundary message | 전체 표를 일괄 생성하지 않음 |
| 합법 incumbent 주변에 값싼 개선 가능성 | 제한된 조건부 UB 개선 | proof domain과 탐색 선호값을 덮어쓰지 않음 |
| 최솟값을 만드는 cover가 남음 | 그 영역의 공유 boundary를 분기/조건화 | 다른 높은-LB 영역을 깊이 풀어도 전역 LB가 오르지 않을 수 있음 |
| 순수 tree/작은 separator | 해당 지역의 exact tree/VE 작업 | 원본 factor 기준 독립성과 separator 상태 보존 필요 |

한 proof 영역 안에서는 relaxed boundary 불일치, 아직 certified floor만 가진 factor, 예상 factor 평가 수·table cells·기존 cache를 이용해 proof 작업 순서를 정한다. `incumbent의 그룹 비용 − 그룹 하한`은 UB 개선 후보를 찾는 보조 점수로 사용하며 proof의 branch 값 선택을 incumbent 쪽으로 강제하지 않는다. Incumbent가 해당 영역에 속하지 않으면 그 차이를 실제 gap 기여로 해석하지 않는다. Cover 전체에서는 먼저 최소 LB에 영향을 주는 영역을 대상으로 삼는다. 이 점수들은 **휴리스틱**이며 결과 보장의 일부가 아니다.

합법 UB가 확보된 뒤에는 그 incumbent의 hard legality conflict는 이미 해결돼 있다. 이후의 conflict는 주로 relaxed cluster들이 공유 변수·movement·boundary에 서로 다른 선택을 요구하는 불일치다. 이런 경우 작은 관련 그룹을 합쳐 공동 최소값을 계산하거나 boundary message를 정밀화하고, 그래도 부족하면 해당 boundary를 조건화하여 모든 대안을 보존한다. 명시적인 불일치가 없어도 사용한 floor가 느슨하면 추가 하한 계산이 필요하다. 따라서 conflict가 눈에 보이지 않는다는 이유로 인증 완료를 선언하지 않는다.

초기 구현은 복잡한 학습형 selector 대신 deterministic 우선순위와 작업량 추정으로 시작한다. 반복해도 진전 없는 동일 scope/domain 작업은 재시도하지 않는다. 유한한 선택 작업 후에는 반드시 새 domain split 또는 아직 평가하지 않은 leaf를 처리하는 진행 규칙을 둔다. 따라서 finite domain, 결정 가능한 check, 충분한 자원이면 휴리스틱 반복만으로 무한 정체하지 않는다.

작업별 cell/evaluation quantum은 yield 단위다. quantum이 끝났다는 이유로 Local을 성공 종료하지 않는다. 대형 cluster가 부적합하면 작은 작업이나 domain split을 선택하고, 필수 ledger를 유지할 수 없으면 RESOURCE_EXHAUSTED다. 메모리가 충분하면 exact message/cache를 더 유지할 수 있으나 필요 없는 지역을 계산하는 이유가 되지는 않는다.

첫 버전은 **lazy cluster 하한 + 필요한 tree message + 최소-LB cover 정밀화**로 범위를 제한한다. Min-sum dual decomposition은 선택 가능성을 남기되 첫 구현에 함께 넣지 않는다. 넣으려면 reparameterization 후 비용 보존과 매 단계 dual lower bound를 별도 검증해야 한다. 일반 loopy min-sum의 수렴값을 인증서로 쓰지 않는다.

## 6. 구현 순서와 변경 위치

각 단계는 아래 검증을 통과한 뒤 다음 단계로 진행한다. 기본 EXACT와 MEMO_GLOBAL의 기존 성능 경로를 유지한다.

| 단계 | 구현 내용과 대상 | 완료 조건 |
|---|---|---|
| P0: 동작 계약을 먼저 고정 | `MemoDualModeOptimizerTest`, 새 `MemoLocalOptimizerTest`; kernel/seed/action 호출 추적 seam | 충분한 메모리에서도 Local의 root direct·숨은 seed exact가 호출되면 실패하는 테스트 확보 |
| P1: Local 진입점 분리 | 새 `MemoLocalOptimizer`; [기존 dual facade](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoDualModeOptimizer.java#L144), [model bridge](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L554) | Mode.LOCAL만 새 controller로 dispatch; Result/원본 검증·reconstruction 호환; Global 기존 tests 유지 |
| P2: 인증 우선 시작 | [MemoFeasibleSeed](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoFeasibleSeed.java#L79)에 별도 FIRST_FEASIBLE 동작; cheap floors, 초기 ledger, 즉시 검사 | 초기 UB/LB가 5%이면 cluster 생성·proof branching·전체 tree/VE 0회 |
| P2b: 좋은 초기 UB 선택 | 값싼 조건부 seed 개선 작업, incumbent/proof 상태 분리 | 개선된 UB가 proof domain·message를 덮어쓰지 않음; 매 witness 뒤 즉시 검사; seed 개선비용 별도 계측 |
| P3: 지연 정밀화 | [MemoClusterBounds](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoClusterBounds.java#L38)의 새 incremental builder API, [exact kernel](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java#L324)의 scoped message API | 필요한 그룹 하나씩 완성/반영; ownership·boundary·eviction oracle tests 통과; 기존 Global 호출 결과 동일 |
| P4: 인증 중심 cover 정책 | Local 전용 frontier/ledger, 최소-LB 선택, ε 보류, scoped repair와 finite-progress 규칙 | 미탐색·UNKNOWN·보류 영역을 포함한 LB 증명; 자원 거절에도 cover 보존 |
| P5: 계측·통합·평가 | [dispatch/receipt](../src/main/java/org/apache/sysds/hops/fedplanner/memo/FederatedPlanMemo.java#L126), [benchmark harness](../scripts/fedplanner/run_memo_tree_dp_benchmark.py), [감사기](../experiments/memo-dual-improvement-20261015/analyze_results.py) | 모든 인증 독립 감사; 충분한 메모리의 기본 설정에서 선택적 실행 증거; 회귀 포함 보고서 |

기존 `MemoDualModeOptimizer.Result`를 외부 receipt 계약으로 유지하는 것이 우선이다. 내부 구현을 분리하되 Global을 공통 framework로 대규모 재작성하지 않는다. Local 통계는 별도 부가 진단으로 시작하고, 기존 필드를 남겨 감사기와 benchmark compatibility를 유지한다. `directExact`와 `directCells`는 Local의 root exact 허용 스위치가 되지 않게 mode별 의미를 명시한다.

추가할 최소 통계는 `policy=LOCAL_CERTIFICATE_FIRST`, first-UB/first-LB/certificate 시점, root whole-exact 호출 수, action 종류·scope·실제 작업량, touched original factor/variable 수, completed/untouched message 수, feasible-repair 작업량, active/UNKNOWN/ε-deferred 영역 수, 각 단계 LB/UB다. Selector 평가 시간도 planning time에 포함한다. “부분만 풀었다”는 주장은 상태 이름이나 작은 node 수만으로 판정하지 않는다.

## 7. 검증과 수용 기준

### 정확성과 동작 계약: 모두 필수

1. **초기 인증 fixture:** root direct가 가능한 충분한 메모리, `LB=100`, `UB=105`에서 EPSILON_OPTIMAL. 전체 exact, cluster refinement, proof branch 모두 0회. `105+δ`에서는 조기 성공 금지.
2. **작업 직후 종료:** seed/한 group/한 message/한 split 중 어느 작업이든 최초로 인증 조건을 만족하면 다음 계산 작업 호출 0회. 내부 긴 작업은 명시적 quantum 뒤 controller로 반환.
3. **seed 숨은 exact 방지:** greedy dead end 및 repair 확대 fixture에서 첫 YES witness 뒤 비용 최적화 열거가 이어지지 않음. Root exact API 호출은 spy에서 실패. Seed cap은 후보 삭제나 infeasibility 증명이 아님.
4. **선택성 fixture:** 한 느슨한 cluster만 해결하면 5%가 되는 문제에서 무관한 큰 cluster를 계산하지 않음. 독립 component 예제는 두 번째를 자체 5%까지 풀지 않고 종료.
5. **원본 exhaustive oracle:** 작은 signed/high-arity/random 문제의 모든 공개 지점에 `LB≤OPT≤UB`; LB 비감소, UB 비증가; exact 종료 시 oracle cost 일치; ε 종료 시 exact decimal 부등식 만족.
6. **경계·공유 비용:** multi-parent, shared movement, cross-subtree cost/check에서 각 primitive 1회 계산. Boundary 고정 해를 LB에 넣는 잘못된 구현을 검출하는 반례 포함.
7. **program semantics:** 함수·branch·loop·복수 output의 기존 모델 tests 및 reconstruction/실행 parity 유지. Candidate fingerprint와 cost-model identity는 solver 사이 동일.
8. **cover 유지:** 최소 LB가 먼 sibling이나 ε 보류/UNKNOWN 영역에 있는 fixture, 부분 table 중단, cache eviction, parent 교체 중 admission 거절에서 bound 누락 없음.
9. **어려운 문제:** 5%를 일찍 증명할 수 없는 fixture는 충분한 자원에서 거의 전체 작업 후 올바르게 종료. 작업량 quantum 반복만으로 성공하거나 영원히 같은 작업을 재시도하지 않음.
10. **비양수·UNKNOWN·불가능:** LB≤0에는 상대 종료 금지. Zero/negative optimum은 exact proof 필요. Unknown witness 성공 금지. 원본 전체 infeasible만 INFEASIBLE. 필수 자원 고갈은 명시적 실패.
11. **메모리와 정책 분리:** 여러 충분한 메모리 조건에서 모두 같은 Local 진입 정책; 큰 메모리만으로 root direct가 활성화되지 않음. 작업 선택/cache 차이는 허용하되 인증은 동일 계약.
12. **기존 solver 회귀:** 기존 EXACT, Tree Global/Local, MEMO_GLOBAL의 oracle·canonical tie·direct-no-search 테스트 통과. Local은 비용 exact 증명과 Global canonical tie 증명을 혼동하지 않음.
13. **seed와 proof 순서 분리:** 서로 다른 seed를 넣어도 proof domain/ownership/LB cache가 동일하게 유지됨. 인증·pruning 대상이 동일한 고정 proof snapshot에서는 다음 conflict와 branch의 기본 순서가 seed 변수 값 때문에 바뀌지 않음. 개선된 UB로 pruning이 늘어나는 것은 허용.
14. **초기 품질과 총비용 분리:** 초기 seed 개선 후 UB가 더 낮아지고도 전체 인증 시간이 길어질 수 있는 사례를 기록. 초기 witness 발견 즉시 이미 5%면 seed 개선 0회. Refinement에서 발견한 더 좋은 합법 witness는 seed를 대체.

### 성능 검증: 효과가 없거나 느린 경우도 결과

- Frozen `5734de2021`의 EXACT/MEMO_GLOBAL/기존 MEMO_LOCAL과 새 MEMO_LOCAL을 같은 원본 모델·후보·cost 하에서 비교한다. 기존 Tree와 legacy Regional은 앞선 보고서처럼 동등한 문제에서만 비용을 직접 비교한다.
- LogReg/GLM/GNMF의 **충분한 기본 heap에서도** 새 Local을 측정한다. `directCells=0`이나 메모리 부족을 강제한 결과만으로 원하는 동작을 달성했다고 판단하지 않는다. 기존 heap 조건은 별도 보조 실험이다.
- Docker는 기존 `run_LAN_docker.sh`만 사용한다. Cold JVM 반복 7회, 순서 혼합, 동일 image/stage/CPU/heap. Planning median/range, first UB, certificate time, peak memory, factor 평가·message cells·touch 범위와 전체 LB/UB를 남긴다. 이전 결과는 덮어쓰지 않는다.
- Ablation은 새 Local의 lazy refinement, 최소-LB 순서, 조건부 UB 개선, exact tree message를 각각 끄고 비교한다. “root direct만 끈 기존 Local”도 기준선으로 넣어 정책 분리의 실제 효과를 측정한다.
- Seed 비교는 첫 feasible만 사용, 초기 조건부 개선 사용, 기존 incumbent 선호 DFS와 분리된 conflict 순서를 조합해 수행한다. 특히 이전 GNMF 111→4,052노드 회귀가 새 순서에서도 발생하는지 확인한다. 초기 UB 비용·seed 평가량·LB 진행·총 인증 시간을 함께 비교한다.
- 독립 감사기로 모든 인증을 같은 문제 EXACT optimum과 비교한다. 소규모 federated runtime은 따로 실행하고, 대형 source compile 결과를 대규모 전체 학습 처리량으로 표현하지 않는다.
- 첫 구현의 성능 목표는 **ε가 쉬운 문제에서 exact보다 적은 평가 작업과 빠른 인증**, 실제 ML에서 그 조건이 존재하는지 확인하는 것이다. 모든 workload의 속도 향상을 기능 완료 조건으로 꾸미지 않는다. 기본 EXACT 변경은 이 계획 범위 밖이다.

## 8. 위험과 대응

| 위험 | 대응 |
|---|---|
| 첫 greedy UB가 너무 나빠 하한 개선만으로 진행 불가 | 전체 feasibility를 먼저 확보하고, UB 개선과 LB 정밀화를 번갈아 선택할 수 있게 함 |
| 초기 floor가 너무 약함 | 비싼 전체 표 대신 관련 factor→작은 cluster→boundary conditioning 순서로 강화; 효과 없는 작업 재시도 방지 |
| Seed 품질 향상이 탐색 순서를 악화 | incumbent를 무조건 선호하는 DFS를 Local proof 순서와 분리; UB improvement를 ablation으로 평가 |
| Tree/cluster 구축 비용이 이득 초과 | 준비도 선택 작업으로 계측; 첫 인증 이전 대형 message 준비 금지 |
| 가장 낮은 LB 영역만 계속 막힘 | UNKNOWN 의무를 보존하면서 다른 UB/영역 작업 허용; 유한 작업과 strict domain 분기로 진행 보장 |
| 메모리 부족으로 frontier 손실 | optional cache 우선 퇴거, parent/child 교체 원자성, 필수 ledger 확보 실패는 명시적 실패 |
| Shared helper 수정이 Global을 느리게 함 | 기존 API 동작 보존, 새 scoped API 추가, Global direct tests 및 같은 조건 timing 비교 |

이 계획의 첫 구현 대상은 **P0–P2의 호출 계약과 첫 인증 경로**다. P2b에서 좋은 초기 UB 선택과 proof 순서의 분리를 추가하고, P3–P4를 붙여 “전체 exact를 하지 않는다”를 넘어 “인증에 필요한 지역만 추가 계산한다”는 실제 동작을 완성한 뒤 P5에서 검증한다. 문서상 정책 변경이나 단순 flag 변경만으로 완료 처리하지 않는다.
