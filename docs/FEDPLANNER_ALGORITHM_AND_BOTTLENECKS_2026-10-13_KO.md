# FedPlanner의 단계별 동작과 조합·반복 처리 병목

작성일: 2026-10-13

코드 기준: `33384c0054becbfefba6a4e51a5de7aefe745988`

성능 근거: v41의 동일 COFEE 50K×128 W1 Docker 실행. 최신 코드인 v43의 실측 결과가 아니다.

## 1. 현재 문제에 대한 판단

현재 가장 강하게 확인되는 문제는 **관계를 압축한 뒤 다음 단계에서 다시 개별 조합으로 처리하고, 합법성을 확인하기 위해 이전 HOP의 의존 경로를 반복 방문한다는 것**이다.

사용자가 제기한 세 가지 의심은 모두 코드에 근거가 있다.

| 의심 | 확인된 동작 | 판단의 한계 |
|---|---|---|
| 필요 이상으로 많은 경우의 수를 펼치는가? | Oracle의 압축 영역을 일반 candidate 경로가 다시 순회한다. Physical Model에도 relation과 support 전개 경로가 있다. | 전개된 모든 조합이 불법이거나 서로 동등하다는 뜻은 아니다. 개별 객체 없이 표현 가능한 경우에도 전개하는 것이 문제다. |
| 중복 연산을 많이 하는가? | 후보 갱신, proof graph 구성, proof 소비가 반복된다. 캐시로 일부 조회를 줄여도 전체 proof 소비량이 그대로인 실측이 있다. | 누적 작업량 전부를 제거 가능한 중복으로 간주할 수는 없다. |
| 한 HOP의 일이 여러 HOP에 걸쳐 길어지는가? | 현재 HOP의 source를 증명하는 query가 producer와 그 조상들의 candidate 의존성을 따라간다. | 공유 source, loop, 함수 경계 때문에 HOP 간 제약 자체는 필요하다. |

**Oracle 호출 감소, 객체 생성 감소, 논리적 후보 수 감소, 전체 플래닝 시간 감소는 서로 다른 성과다.** 현재 최적화는 일부 조회와 객체 생성을 줄였지만 전체 반복 작업을 충분히 제거하지 못했다.

이 문서에서는 직접 확인한 코드·측정을 **확인 사실**, 그에 따른 원인 판단을 **추론**, 근거가 부족한 부분을 **미확인**으로 구분한다.

## 2. 전체 처리 흐름

```text
HOP와 입력의 shape·privacy·배치 정보
    ↓
Oracle: 입력 조건별 실행 규칙 판단
    ↓
Candidate Generation: 실행·출력 후보 생성
    ↓
Support 구성: 정확한 source와 DIRECT/RELOCATION 선택 연결
    ↕
Closure: 함수·loop·VALUE_MAP·privacy·source 의존성을 함께 갱신
    ↓
Physical Model: 선택 가능한 Alternative와 공동 제약 구성
    ↓
Cost Model: 연산·전송·공유 비용 구성
    ↓
Local/Exact 최적화: 함께 성립하는 선택 중 비용 비교
    ↓
선택된 계획·proof·receipt 및 실행 적용
```

Support 구성과 Closure는 단방향으로 한 번씩 실행되는 독립 단계가 아니다. Source 후보가 바뀌면 support와 경계 관계가 바뀌고, 그 결과가 downstream 후보에 다시 영향을 준다.

| 단계 | 답하려는 질문 | 현재 남아 있는 비용 |
|---|---|---|
| Oracle | 이 입력 상태에서 이 연산이 실행 가능한가? | 의존 입력 축의 조합 검사. 관계를 준비할 수 없는 경우 exact 경로 사용 |
| Candidate 생성 | 어떤 실행 방식과 출력 상태를 후보로 남기는가? | 압축 영역을 조합별 key/fact로 다시 생성 |
| Support 구성 | 각 입력을 어느 실행 결과에서 어떤 방법으로 공급하는가? | 입력별 binding 조합 및 개별 proof 소비 |
| Closure | 이 source와 경계·전송·privacy 조건들이 함께 성립하는가? | 조상 proof 탐색, 변경 전파, 후보 재생성, 안정될 때까지 반복 |
| Physical Model | 무엇을 하나의 선택값으로 만들고 어떤 선택들을 연결하는가? | 지원되는 region 외에는 clause와 authority별 Alternative 생성 |
| Cost·DP | 공동 제약과 공유 비용을 만족하는 저비용 선택은 무엇인가? | 큰 domain과 제약의 결합, 중간 관계 계산 |

진입 순서는 [PlacementRelationClosure.close](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L500), 최적화 측 연결은 [FederatedPlanLocalCost](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java#L45)에 있다.

## 3. Oracle에서 줄인 일이 Candidate Generation에서 다시 커진다

### 3.1 Oracle이 압축하는 대상

Oracle은 실행 규칙이 실제로 의존하는 입력 위치를 이용해 관계를 만든다. 모든 입력 조합을 각각 판단하는 대신, 같은 판단을 공유하는 입력 영역을 표현할 수 있다.

설명용으로 다음을 가정하자. 실제 workload의 측정값은 아니다.

```text
A 입력: 4개 선택
B 입력: 5개 선택
C 입력: 6개 선택

실행 규칙의 판단은 A에만 의존한다.
```

이때 다음 네 영역으로 실행 규칙을 표현할 수 있다.

```text
{a1} × B × C → 실행 규칙 r1
{a2} × B × C → 실행 규칙 r2
{a3} × B × C → 실행 규칙 r3
{a4} × B × C → 실행 규칙 r4
```

관련 코드: [OracleFacade.prepareExecutionRelation](../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L445). 기존 정방향 rule과 선언된 의존 입력을 재사용한다. 이 관계는 실행 규칙의 결과를 공유하며, source·전송까지 포함한 완성된 전체 계획의 합법성을 이미 증명한 것은 아니다.

### 3.2 현재의 단절 지점

일반 candidate 경로는 영역 내부를 `enumerateExecutionRegion()`으로 다시 순회한다. 각 leaf에서 처리 함수가 `CandidateRuleKey`와 fact를 생성한다.

위 예시에서는 다음과 같은 일이 가능하다.

```text
Oracle 판단: 4회
조합별 후보 처리: 4 × 5 × 6 = 120회
```

**Oracle을 적게 호출했어도 다음 단계의 객체 생성과 순회 비용은 그대로 남는다.**

현재 `supportsFedRuleRelation()`이 허용하는 FED family 경로는 조건을 충족하는 scalar `WSLOSS/WCEMM`에 제한돼 있다. CP family와 이후 native support 압축은 별도로 존재하므로, 이 제한을 “다른 연산에는 어떤 압축도 없다”로 해석하면 안 된다.

관련 코드:

- [조합별 key 생성](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L243)
- [조합별 fact 생성과 family 분기](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L318)
- [FED family 적용 조건](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L466)
- [영역 내부 재귀 열거](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L1284)

## 4. Source와 support에서 추가되는 경우의 수

Oracle의 입력 상태가 `ROW`라고 해도, 그 ROW 입력을 공급하는 구체적인 실행 결과가 여러 개일 수 있다.

```text
입력 A의 ROW 공급 방법
  - 앞선 HOP의 ROW 결과를 DIRECT로 사용
  - 다른 source 결과를 특정 relocation action으로 변환

입력 B의 공급 방법
  - 또 다른 source를 DIRECT로 사용
  - 허용된 전송 경로로 공급
```

따라서 다음 두 경우의 수를 구분해야 한다.

1. Oracle이 보는 입력 상태의 조합: LOCAL, ROW, COL 등의 조합.
2. 그 상태를 실제로 공급하는 binding의 조합: 정확한 source, DIRECT/RELOCATION, action, proof 등의 조합.

첫 번째를 줄여도 두 번째는 클 수 있다. 모든 worker를 사용하는 조건만으로 source와 action의 대안까지 하나가 되지는 않는다.

Relocation binding은 현재도 입력 축별 action이 일정하고 상관관계를 표현할 수 있으면 factorized relation으로 남긴다. 같은 owner를 공유하는 축을 무조건 독립으로 취급하지도 않는다. 반면 한 축에서 action이 섞이거나 지원되는 상관관계 표현으로 안전하게 묶을 수 없으면 exact 열거로 돌아간다. [Relocation product와 fallback](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L12460)

DIRECT support 역시 압축 publication을 시도하지만, 남은 경로에는 `supportResult.proofs()`를 순회하며 개별 proof를 소비하는 처리가 있다. [DIRECT proof 소비](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L6100)

합법인 조합의 ID만 저장하더라도, 합법인 조합이 많으면 ID 목록 자체가 product 크기가 된다. **인덱스 사용과 factorization은 별개다.** 인덱스는 객체와 비교 비용을 줄이고, factorization은 조합을 하나씩 저장·방문하는 일 자체를 줄인다.

## 5. 왜 현재 HOP의 검사가 이전 HOP들까지 이어지는가

### 5.1 현재 proof query의 동작

```text
X → A → B → C
```

C의 특정 source와 배치가 성립하는지 확인하는 query는 B의 candidate를 확인하고, 그 candidate가 의존하는 A와 X를 따라갈 수 있다.

코드에서는 query별 root state와 fixed boundary를 만든 뒤 `buildCandidateProofGraph()`가 의존 상태를 DFS로 방문한다. 이후 불가능한 의존 경로를 제거하고 support를 구성한다.

**과거의 모든 계획을 C의 support 객체에 통째로 복사한다는 뜻은 아니다.** 직접 입력의 참조를 유지하면서, 그 참조가 실제로 성립하는지 확인하기 위해 조상 HOP들을 재방문한다는 뜻이다.

Exact query memo와 하위 그래프 재사용은 이미 존재한다. 그렇지만 query의 root가 다르거나 의존성 변경으로 재사용 조건을 만족하지 못하면 새 탐색이 필요하다.

관련 코드: [query memo](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java#L2087), [graph 구성](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java#L2647), [DFS와 component 재사용](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java#L3310).

### 5.2 재사용을 제한하는 정확성 조건

같은 owner가 의존 경로에서 다시 나타나면 현재 query의 정확한 root source로 연결해야 한다. 이전 query의 다른 source를 가져오면, 존재하지 않는 실행 경로를 합법이라고 판단할 수 있다.

그래서 하위 그래프의 전체 의존 owner 목록에 현재 root owner가 포함되면 재사용을 제한한다. 이 목록에는 숨은 metadata 의존성도 포함된다. [고정된 root의 의존성 적용](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java#L4888)

이 조건 자체를 없애는 것은 안전한 최적화가 아니다. 개선하려면 **root와 무관한 공통 관계를 공유하고, root에 의존하는 조건만 별도로 적용**할 수 있어야 한다.

### 5.3 Native gate의 반복 구성

Native product는 축별 선택을 나타내는 OR gate들과 이들을 결합하는 AND 구조로 표현된다. 이 자체는 전체 product 열거를 피하는 표현이다.

하지만 현재 코드의 gate는 query마다 새 객체로 생성되고 identity로 비교된다. 또한 gate state는 기존 acyclic component summary 재사용에서 제외된다.

따라서 **product를 개별 tuple로 전부 펼치지 않는 경로에서도, 같은 축의 선택 목록과 의존 그래프를 반복 구성·순회할 수 있다.**

관련 코드: [gate 재사용 제외](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java#L3327), [축별 gate 구성](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java#L4022), [새 gate 생성](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java#L4151).

반복 할당·순회의 가능성은 코드로 확인된다. 그중 정확히 몇 회와 몇 초를 제거할 수 있는지는 미확인이다. 이 개선은 문서 기준 커밋에 통합되지 않았다.

## 6. 각 HOP에서 끝내도 되는 일과 넘겨야 하는 조건

각 HOP에서 입력의 shape·배치에 대한 실행 규칙을 판단하는 것은 가능한 한 그 HOP 안에서 끝내는 것이 맞다. 그러나 각 HOP이 개별적으로 가능하다는 사실만으로 전체 선택도 가능하다고 판단할 수는 없다.

```text
      ┌→ A ─┐
X ────┤     ├→ C
      └→ B ─┘
```

설명용으로, X가 하나의 실행 선택을 공유해야 하는 owner라고 가정하자.

```text
A의 후보 a1: X=x1을 요구
B의 후보 b2: X=x2를 요구
```

A와 B가 각각 가능해도 `C(a1, b2)`는 공유 X의 선택이 충돌해 불가능할 수 있다. 실제로 복수 materialization이 허용된 관계라면 그 action과 비용까지 모델링해야 하며, 이 예시를 모든 source에 일률적으로 적용하면 안 된다.

필요한 표현은 다음과 같다.

```text
A의 관계: a1 ⇒ X=x1
B의 관계: b2 ⇒ X=x2
C의 관계: A·B 관계를 결합하고 공유 X 조건을 유지
```

HOP 간에 넘겨야 하는 것은 정확한 source 선택과 공동 제약이다. **그 조건을 유지하는 것과, query마다 전체 과거 경로를 다시 증명하는 것은 다른 일이다.**

단순 체인에서 과거 경로를 매번 다시 확인하고 재사용이 없다면 방문량이 `1 + 2 + … + n`처럼 늘 수 있다. 이는 반복 탐색의 문제를 설명하는 예시이며, 현재 구현 전체가 항상 O(n²)라는 주장도, 모든 그래프를 O(n)에 풀 수 있다는 주장도 아니다. 공유 입력, cycle, 비용 결합이 있으면 필요한 관계 자체가 커질 수 있다.

## 7. Closure의 반복은 어디에서 생기는가

현재 Closure의 핵심 반복은 다음과 같다.

```text
입력·함수 경계 변경
→ 영향받는 HOP의 candidate 재생성
→ DIRECT support 연결
→ relocation 연결
→ CFG·loop·VALUE_MAP 관계 반영
→ DIRECT support 재확인
→ privacy·support pruning
→ 최종 action과 실행 가능한 출력 상태 반영
→ 바뀐 downstream을 다음 반복에 전달
```

이미 dirty owner, worklist, component별 처리, query 의존성 기록, 변경 없는 결과의 재사용이 구현돼 있다. “매번 전체를 처음부터 계산한다”는 설명은 정확하지 않다.

다만 다음 반복 비용은 남아 있다.

- 변경된 producer의 consumer를 처리할 때 `buildNode()`로 해당 HOP의 후보를 다시 만든다.
- 여러 입력이 순서대로 바뀌면 같은 consumer가 다시 처리될 수 있다.
- 경계 전파와 DIRECT grounding이 서로의 정확한 source 참조를 바꿀 수 있어 바깥 반복에서 다시 확인한다.
- 출력이 계속 ROW라는 사실만으로 안정됐다고 볼 수 없다. 내부 source와 action의 허용 관계가 달라질 수 있다.

관련 코드: [물리 후보 worklist](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L9077), [증분 DIRECT component 처리](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L4926), [semantic closure 반복](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1048).

현재 비용은 단순한 입력 조합 수 하나로 설명되지 않는다. 대략 다음 항목을 함께 봐야 한다.

```text
총 작업량
≈ 각 revision에서의 후보 생성량
 + 각 proof query에서 실제 방문한 의존 상태·간선 수
 + 개별 proof·clause 소비량
 + 경계·전송 관계 재결합 비용
 + Physical·Cost·DP의 처리량
```

입력별 선택 수가 `d₁, …, dₖ`이면 explicit product에는 최대 `∏dᵢ`개의 leaf가 생긴다. 독립 product를 저장하는 비용은 `∑dᵢ` 수준으로 줄일 수 있지만, 그 표현을 이후 다시 전개하면 순회 비용은 다시 product 크기에 비례한다. Arbitrary correlated relation과 비분리 비용까지 항상 같은 수준으로 압축할 수 있는 것은 아니다.

## 8. Physical Model과 DP에서도 압축이 끝까지 유지되지는 않는다

Physical Model은 지원되는 support region을 한 Alternative로 유지한다. 반면 clause별 구분이 필요하거나 입력 authority를 하나의 압축 영역으로 표현할 수 없으면 개별 clause와 binding을 순회해 Alternative를 만든다.

특히 `CandidateRuleRelation`을 소비하는 경로 자체에도 `forEachRelationInputs()`가 있다. 따라서 Candidate 단계에서 family로 보관했다는 사실만으로 downstream 전개가 제거됐다고 볼 수 없다. [Physical Alternative 구성](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L1071)

Cost Model은 연산 비용뿐 아니라 전송과 공유 source의 비용을 다룬다. 같은 배치라도 source·action·공유 여부가 다르면 동일 비용이라고 볼 수 없다. 정확한 행동과 비용이 같은 영역을 증명한 뒤 묶어야 한다.

현재 Local 경로는 regional seed를 만든 뒤 incremental regional optimization을 수행한다. Exact solver에는 conditional support와 sparse join·elimination 경로가 있지만, dense fallback과 중간 결과 계산도 남아 있다. [Local 경로](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java#L65), [Exact elimination](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L3180)

기존 정상 실측에는 optimizer 전체 시간은 있지만 Local seed, Exact 준비, Exact solve의 세부 시간이 없어 현재 자료만으로 이 내부 비중을 단정할 수 없다.

## 9. 실제 workload에서 확인된 작업량과 시간

v41은 동일 입력·seed·privacy·cost profile·JVM·CPU/메모리 조건의 COFEE 50K×128 W1 실행이다. 각 workload 1회 관측이며, 아래 시간은 학습 실행 시간을 제외한 초기 플래닝 시간이다.

| 단계 | LogReg | GLM |
|---|---:|---:|
| PlacementAnalysis·Closure | 277.728초 | 81.282초 |
| Physical Model | 8.138초 | 5.151초 |
| Cost Surface | 6.212초 | 8.136초 |
| Optimizer | 71.785초 | 25.579초 |
| 전체 초기 플래닝 | **367.466초** | **124.110초** |

전체에는 위에 따로 나열하지 않은 단계와 측정 경계의 비용도 포함되므로 표의 네 단계 합과 정확히 같지는 않다.

| 누적 작업 | LogReg | GLM |
|---|---:|---:|
| CandidateRuleFact 객체 생성 | 217,777 | 135,684 |
| 명시적 support clause 객체 생성 | 2,324,234 | 1,104,114 |
| Proof graph 구성 | 103,616 | 33,029 |
| Proof 소비 | 6,121,451 | 688,373 |

이 값들은 서로 다른 최종 합법 계획의 개수가 아니다. 특히 객체 생성과 proof 소비에는 재생성·반복 처리가 포함된다.

v38→v41 LogReg에서는 native logical-member 조회 12,421,044회를 생략했고, 명시적 Clause 생성은 19,402개 줄었다. Topology 단계 시간은 약 7.351초 줄었지만 proof 소비량 6,121,451회와 rule fact 생성량 217,777개는 그대로였다. 전체 시간은 1.193초 감소에 그쳤고 GLM은 0.444초 증가했다. 단일 관측으로 일관된 전체 성능 개선을 주장할 수 없다.

LogReg 내부의 주요 exclusive phase 시간은 다음과 같다.

| 정상 계측 phase | 시간 | 해석 |
|---|---:|---|
| PROOF_OVERLAY | 76.425초 | `buildCandidateProofGraph` 전체를 포함한다. Overlay list 구성만의 시간이 아니다. |
| PROOF_TOPOLOGY | 46.963초 | Topology 준비·전개 관련 비용 |
| DIRECT_PROOF_CONSUMPTION | 28.596초 | 반환된 proof를 소비하는 비용 |
| PROOF_DEPENDENCY_PRUNING | 26.699초 | Proof 의존 관계의 불가능한 경로 제거 |
| CLOSURE_REPLAY | 21.136초 | Closure replay 관련 비용 |
| RELOCATION_BINDING | 18.051초 | 전송 binding 구성 관련 비용 |

숫자상 가장 큰 구간은 PlacementAnalysis·Closure이며, 그 내부에서도 proof graph 구성·topology·개별 proof 처리가 큰 비중을 차지한다. **Oracle 판정 횟수만 줄이면 전체 문제가 해결된다는 설명은 이 측정과 맞지 않는다.**

검사 카운터가 0인 경우에도 어떤 경로의 어떤 사건을 세는지 확인해야 한다. 예를 들어 execution-relation 경로를 사용하면 MRV fallback이 실행되지 않을 수 있고, source 충돌이 없는 합법 product는 conflict pruning으로 줄일 수 없다. 특정 rejection counter의 0만으로 전체 최적화가 꺼졌다고 판단하면 안 된다.

근거: [기존 COFEE 실측 보고서](FEDPLANNER_COFEE_LARGE_VALIDATION_2026-10-08_KO.md), workspace 밖의 비교 artifact `evidence/cofee-50k128-v41-validation/v38-v41-comparison.json` (SHA-256 `5514270bb45204def7880f91345badeba552584905a91749375c1a0c3acf0508`).

## 10. 개선 방향과 정확성 경계

현재 근거에 따른 우선순위는 다음과 같다. 구현 완료 목록이 아니라 원인 분석에서 도출한 방향이다.

| 우선순위 | 방향 | 보존해야 할 조건 |
|---|---|---|
| 1 | 일반 FED candidate에서도 조합별 fact 생성 없이 relation 유지 | 실행·출력·privacy·shape·emission authority가 정확히 같은 영역만 공유 |
| 2 | HOP 간 source 조건을 압축 관계로 연결하고 공통 proof 재사용 | 동일 owner의 상관관계, exact source, root-dependent 조건, metadata 의존성 유지 |
| 3 | Closure에서 변경된 relation 조각만 전파 | source 복원·철회, 함수·loop 경계, action 교체가 누락되지 않아야 함 |
| 4 | Physical Model이 relation을 직접 선택 domain과 제약으로 소비 | 임의의 representative가 다른 source/action authority를 대체하지 않아야 함 |
| 5 | Cost·DP의 join·projection·elimination에서도 가능한 압축 유지 | 공유·비분리 비용과 목적값 raw bits, 정확한 선택 복원 보존 |

캐시는 이 구조의 반복 비용 일부를 줄일 수 있다. 그러나 캐시만 늘려도 충분한지는 별도로 검증해야 하며, 보관 메모리와 invalidation 비용이 증가할 수 있다.

현재 미확인 또는 미완료인 부분은 다음과 같다.

- 개별 생성·방문의 몇 퍼센트가 안전하게 제거 가능한 중복인지에 대한 전수 분류.
- 문자열 비교·직렬화만이 차지하는 정확한 시간 비중. 관련 작업은 존재하지만 주원인이라는 증거는 없다.
- 최신 코드의 반복 실측에서 일관된 성능 개선이 나타나는지. 아래 v43 측정은 각 workload 1회 관측이다.
- 모든 assignment의 비용 전수 동등성. 선택된 계획과 목적값 등의 parity 통과와 구분해야 한다.
- 두 workload 모두 20초 이하라는 목표. 현재 실측은 이 조건을 충족하지 못한다.

핵심 설계 과제는 **각 HOP에서 판단할 수 있는 것은 그곳에서 끝내고, 이후에는 공유 source·경계·비용에 필요한 조건만 관계로 전달하는 것**이다. 압축된 관계를 계속 개별 조합으로 되돌리거나 과거 경로를 반복 증명하는 지점을 줄여야 전체 작업량이 줄어든다.

## 11. 후속 확인: v43 실제 COFEE 측정

위 코드 설명의 기준 커밋 `33384c0054`를 실제 harness에서 실행한 결과다. 입력·script·privacy·seed·cost profile·JVM·CPU/메모리 조건을 v41과 동일하게 유지했다.

| 단계 | LogReg | GLM |
|---|---:|---:|
| PlacementAnalysis·Closure | 295.127초 | 77.996초 |
| Physical Model | 7.391초 | 4.848초 |
| Cost Surface | 7.003초 | 8.707초 |
| Optimizer | 74.940초 | 25.419초 |
| 전체 초기 플래닝 | **388.771초** | **121.376초** |
| v41 대비 전체 시간 | +21.305초 | −2.734초 |
| Coordinator peak memory | 8,720,678,912 bytes | 5,034,033,152 bytes |
| Worker peak memory | 728,416,256 bytes | 764,141,568 bytes |

두 workload 모두 numeric comparator와 receipt audit을 통과했다. 선택된 candidate의 원문, section별 내용, objective raw bits, objective certificate, Cost Surface fingerprint는 v41과 일치했다. 모든 assignment의 비용을 전수 비교했다는 뜻은 아니다.

LogReg에서는 skeleton 요청 약 1,970만 회 중 371만 회를 재사용했지만, proof 소비 6,121,451회와 proof graph 103,616회는 그대로였다. Rule fact 객체 생성도 줄지 않았다. 명시적 clause 객체만 1,536개 줄었다. **일부 재사용이 늘었다는 사실만으로 전체 작업량이나 시간을 충분히 줄였다고 할 수 없다.** 단일 관측에서 LogReg은 악화했고 GLM은 소폭 줄었다. 이 결과만으로 어느 변경이 시간 차이를 일으켰는지 단정하지 않는다.

두 workload 모두 20초를 넘었고, 동일 frozen engine에서 workload별 성공 3회라는 완료 조건도 충족하지 못했다.

근거: workspace 밖 `evidence/cofee-50k128-v43-validation/v41-v43-comparison.json`, SHA-256 `e8070a6df3e19ed0fd5f018d4141ff8c307a47d4ab6ecf1c1b3a7c07e430003a`. Frozen JAR SHA-256 `9862117114b2746f579c5b504f441a3a46e8370f14907662cf47a80573e29eb8`.
