# FedPlanner의 단계별 동작과 조합·반복 처리 병목

작성일: 2026-10-13

본문 1–11절의 코드 기준: `33384c0054becbfefba6a4e51a5de7aefe745988`. 이후 통합·실측은 12절 이후에 별도로 기록한다.

성능 근거: 동일 COFEE 50K×128 W1 Docker 실행을 엔진별로 구분한다. 최신 완료 실행 v47은 LogReg 376.51초, GLM 122.99초이며, 20초 목표는 미달성이다. 최신 측정과 확인된 병합 거절 원인은 15절에 기록한다.

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

## 12. 후속 확인: v45에서 드러난 두 종류의 반복 작업

커밋 `5660e734a4`의 동일 COFEE 실제 실행은 LogReg **438.336초**, GLM **117.548초**였다. v43 대비 각각 **49.564초 증가**, **3.828초 감소**다. 두 workload의 수치 결과·선택 계획·objective raw bits/certificate·Cost Surface fingerprint·receipt audit은 일치했다. 20초 목표는 미달성이다.

첫 번째 문제는 캐시를 찾는 비용이다. 새 구현은 root의 직접 입력뿐 아니라 proof graph 안의 모든 입력 gate에 대해, 저장 가능한지 판단하려고 다시 하위 의존성을 탐색했다. LogReg에서 footprint의 raw edge 방문이 **92,488,567→1,462,463,009회**로 증가했다. Graph 수는 103,616으로 같았고 graph state·alternative·edge 수도 늘었다. 이미 상위 관계를 재사용할 수 있는데 작은 하위 관계까지 반복해서 조사한 셈이다. Gate의 직접 eviction을 막아도, gate가 차지한 공간 때문에 나중에 기존 큰 summary가 간접 축출될 수 있었다. 원격의 실제 child 재사용 개선을 유지하면서, 전역 하위 gate 발견 호출을 제외하는 방향으로 수정 중이다.

두 번째 문제는 **이미 정확하게 압축한 관계를 Closure가 다시 펼치는 것**이다. v43 LogReg의 proof 소비 6,121,451회 중 2,555,577회는 partitioned publication에서 기존 관계와 합치지 못한 product를 순회하는 경로였다. 이 경로는 residual 생성에 성공하면 residual을, 실패하면 원래 product를 순회한다. 현재 계측은 그 둘을 구별하지 않으므로 2,555,577회 전체가 제거 가능하다고 주장할 수 없다. 또한 이는 새 Clause 객체 개수와 다르다.

예를 들어 기존에 검증된 입력 조합이 `E={a1}×{b1,b2}`, 새로 검증된 영역이 `B={a1,a2}×{b1,b2,b3}`라면, 추가 영역은 `B\E`다. 기존 흐름은 추가 영역을 개별 조합으로 복원한 뒤 다시 합칠 수 있다. 수정 방향은 포함 관계를 입력 축에서 증명하고, **B와 조합별 원래 proof 소유권을 함께 보관하는 것**이다. 전체 Cartesian product를 순회하지 않고, 최종 선택된 조합이 E에 속하면 기존 proof, 그 외에는 새 관계의 proof를 복원한다. 다른 source owner·proof·layout·staging이 섞인 경우에는 이 등식을 기계적으로 적용하지 않는다.

DP에서도 두 반복을 구분한다. 검증이 끝난 동일 원본 선택을 conditional replay한 뒤 전체 비용 복원을 다시 하던 경로는 제거했다(실제 호출 2→1인 회귀 테스트). 같은 incumbent를 여러 neighborhood에서 매번 원본 decision으로 펼치던 경로는 호출 내부에서 한 번만 펼치고, incumbent가 개선되어 교체될 때 다시 펼치도록 후속 변경 중이다. 이 단위 테스트의 작업 감소를 실제 optimizer 시간 감소로 환산하지 않는다.

현재 해결 대상은 ‘불법 후보를 더 많이 버리기’만이 아니다. **합법 관계를 다시 펼치는 작업, 변하지 않은 선택을 다시 계산하는 작업, 재사용 대상을 찾으려고 같은 graph를 반복 방문하는 작업**을 줄여야 한다. 후보 생성 개수·논리적 후보 개수·객체 개수·실제 방문 횟수를 구별해서 검증해야 한다.

근거: `evidence/cofee-50k128-v45-validation/v43-v45-comparison.json` (SHA-256 `235eb25c1a08bb4eb4ce8e3cb0aeda90e503af6da83add02247e179c439cdeb0`). 후속 변경의 검증과 한계는 [세션 기록](SESSION_ISSUES_2026-10-13.md)에 누적한다.

## 13. 후속 확인: v46의 실제 효과와 남은 핵심 병목

동일 COFEE 50K×128 W1 입력·privacy·DML·seed·cost profile·Docker/JVM 조건으로 실행했다. 엔진은 `a59c23069b50fe75238f040413817510dfd91f31`이며, 실행 도중 도착한 origin/main 변경은 이 JAR에 포함하지 않았다.

| 지표 | LogReg | GLM |
|---|---:|---:|
| 전체 초기 플래닝 | **379.142초** | **118.345초** |
| Analysis·Closure | 287.023초 | 74.842초 |
| Physical Model | 6.539초 | 4.513초 |
| Cost Surface | 8.395초 | 9.389초 |
| Optimizer | 73.385초 | 24.998초 |
| Coordinator peak | 8,482,996,224 bytes | 4,977,336,320 bytes |
| Worker peak | 748,265,472 bytes | 755,060,736 bytes |
| v45 대비 전체 시간 | −59.194초 | +0.796초 |
| gate 추가 전 v43 대비 | −9.629초 | −3.031초 |

각 workload 한 번의 관측이다. 동일 frozen engine에서 20초 이하 3회라는 완료 조건을 충족하지 못했으며 evaluator는 FAIL이다. 전체 시간은 phase 합계 밖의 준비 작업도 포함한다.

**실제로 제거한 반복**은 전역 gate suffix 탐색이다. LogReg footprint raw edge 방문은 v45의 1,462,463,009회에서 **92,488,567회**로 줄어 v43과 같아졌다. Request 159,880회, unique edge 29,950,314회도 v43과 같다. 추가 cache를 찾기 위한 반복 탐색 회귀를 제거한 증거다.

반면 **주요 조합 전개는 아직 그대로**다. LogReg proof 소비 6,121,451회, PARTITIONED_RETAINED 순회 2,555,577회, graph 103,616개, row 16,687,362개는 v43과 같다. GLM proof 소비도 688,373회, retained 순회 113,551회로 같다. v45 대비 새 Clause 객체 감소는 LogReg **40개**, GLM **51개**이고 key/fact 생성 수는 변하지 않았다. Ordinary subset/complement 재결합의 단위 테스트 성공을 실제 workload의 대규모 전개 제거로 일반화할 수 없다.

따라서 다음 우선순위는 retained 관계 병합이 실패하는 실제 경로를 압축 상태로 연결하는 것이다. 같은 제외 영역을 유지하며 여러 입력 축이 커지는 conditional 관계를 대상으로 구현·검증 중이다. Explicit clause나 staging 때문에 exact 경로가 필요한 경우와, 표현 능력 부족 때문에 재전개하는 경우도 기존 분기 카운터로 구분한다. 단순히 캐시를 더 늘리거나 불법 후보 rejection 수를 높이는 것을 목표로 삼지 않는다.

정확성은 두 workload 모두 numeric comparator·receipt audit 및 v43/v45 대비 선택된 candidate 원문·section·objective raw bits/certificate·Cost Surface fingerprint·selection fingerprint가 일치했다. 이는 실제 선택 결과와 회귀 테스트의 증거이며 모든 가능한 assignment를 전수 비교한 것은 아니다.

검증된 수정과 최신 원격 통합은 `9fa1eceb10`까지 origin/main에 게시했다. Frozen v46은 selected 회귀 1,521 PASS와 probe 9 PASS, 이후 원격 병합 코드는 별도 selected 회귀 1,529 PASS로 검증했다.

근거: `evidence/cofee-50k128-v46-validation/v43-v45-v46-comparison.json`, SHA-256 `1e5e69f48d578ab6dc32fb07b1ee5139b2260bdc0517d7b8db3049061cb87acc`; 실제 parity binding SHA-256 `4006dcad84c727cf356eb2724d41067655cff75718d837992f9366d0087021ef`.


## 14. 후속 구조 수정: relation이 커져도 기존 조합을 다시 만들지 않기

현재 검증 중인 v47은 conditional relation의 반복 갱신을 대상으로 한다. 예를 들어 두 입력 축이 각각 `{a1,a2}`, `{b1,b2}`이고 제외 영역이 `{a1}×{b1}`이면 합법 조합은 3개다. 두 축에 선택지가 하나씩 추가되면 `3×3−1=8`, 다시 추가되면 `4×4−1=15`가 된다. 논리적으로 가능한 조합 수는 늘어나지만, 각 단계에서 8개·15개의 clause를 새로 만들 필요는 없다.

수정은 같은 제외 영역과 exact source·proof·layout 권한이 보존되는 경우, 확장된 입력 축과 제외 영역을 그대로 보관한다. 선택한 조합이 이전 영역에 속하면 이전 clause 소유자에서 복원하고, 새 영역에 속하면 이번 relation의 소유자에서 복원한다. 여러 차례 갱신돼도 이전 소유자 목록의 순서와 정확한 객체 권한을 보존한다. Hole이 달라지거나 source owner가 다르거나 기존 보관 한도를 넘으면 기존 exact 경로를 유지한다.

실제 binder 단위 테스트에서 8개 관계를 명시적 목록으로 만들던 경로를 native relation으로 유지했고, 두 번째 확장의 15개 관계에서도 선택 전 새 handle 수는 0이다. 이미 읽었던 이전 8개 clause는 같은 객체로 복원됨을 검사한다. 이 fixture의 기존 retained-loop counter는 0이었다. 따라서 논리적으로 새로 생긴 5개 조합을 ‘실측 순회 5회 제거’라고 보고하지 않는다.

별도 문제로, 두 realization의 중복 여부를 비교할 때 record의 자동 `equals`가 key보다 support를 먼저 읽어 사용하지 않을 native clause를 만들었다. Key가 다르면 즉시 비교를 끝내고, key가 같을 때는 기존 record equality를 그대로 적용하도록 수정했다. 이는 합법 후보나 source authority를 합치는 변경이 아니라 결과가 이미 정해진 비교를 조기에 끝내는 변경이다.

실제 COFEE 적용 여부는 별도로 검증한다. Retained 순회를 ordinary/conditional 및 전체/residual의 네 종류로 나누고, residual 생성 거절을 staging·explicit clause·relation 수·표현 불가로 구분한다. 기존 분기에 카운터만 추가했으며 별도 조합 순회나 profiler는 추가하지 않았다. 전체 retained counter가 네 세부 counter의 합과 같은지도 검증한다. 이 결과를 통해 작은 fixture의 성공과 실제 대규모 반복 제거를 구분한다.


## 15. 실제 병합 거절 원인 확인: 기존 explicit clause와 새 native relation 사이의 단절

v47은 `6713d749c9a843604c2f923212dfbffcd2aebb75`를 동일 COFEE 조건에서 실행했다. Conditional growth 및 key-first 비교를 포함하고, 이후 들어온 query-local gate interning은 포함하지 않는다.

| 지표 | LogReg | GLM |
|---|---:|---:|
| 전체 초기 플래닝 | **376.507초** | **122.992초** |
| Analysis·Closure | 283.124초 | 78.535초 |
| Physical Model | 7.403초 | 4.460초 |
| Cost Surface | 6.391초 | 8.529초 |
| Optimizer | 75.890초 | 27.545초 |
| Coordinator peak (bytes) | 8,674,181,120 | 4,874,715,136 |
| Worker peak (bytes) | 757,227,520 | 770,875,392 |
| v46 대비 전체 시간 | −2.635초 | +4.648초 |
| 새 rule key / rule fact | 25,137 / 208,886 | 16,157 / 125,264 |
| 새 explicit clause / indexed handle | 2,312,553 / 2,232 | 1,088,634 / 607 |

각 1회 관측이며 개선의 인과 추정이나 반복 성공으로 해석하지 않는다. LogReg의 explicit clause 생성 수는 v46과 같고 handle만 22개 줄었다. GLM은 clause 2개, handle 27개가 줄었다. 두 workload의 주요 proof 소비·retained 순회는 그대로다. **이번 제한된 relation 성장 최적화는 실제 주요 전개 경로를 해결하지 못했다.**

새 분기 counter로 어느 지점에서 압축이 끊기는지 확인했다.

| Residual 병합 요청 결과 | LogReg | GLM |
|---|---:|---:|
| 전체 요청 | 60,677 | 20,927 |
| 기존 explicit clause 때문에 거절 | **60,461** | **20,882** |
| 남은 표현 불가 | 216 | 45 |
| Same-key staging/authority 충돌 | 0 | 0 |
| Retained relation 수 조건 실패 | 0 | 0 |
| Residual 생성 성공 | **0** | **0** |

전체 요청의 약99.64% / 99.78%가 explicit 경계에서 멈춘다. 이는 모든 플래닝 시간의 같은 비율을 설명한다는 뜻은 아니다. 이 counter는 standalone 요청도 포함하며, 아래 실제 partitioned 순회량과 구분해야 한다.

- LogReg retained 순회 2,555,577개 = ordinary 전체 product 2,542,629개 + conditional 전체 product 12,948개. Residual만 순회한 경우는 0이다.
- GLM retained 순회 113,551개 = ordinary 전체 product 113,551개. 나머지 세 분류는 0이다.
- 전체 proof 소비는 LogReg 6,121,451회, GLM 688,373회다. Retained 순회는 그 일부이며 전부가 제거 가능하다고 증명된 것은 아니다.

문제의 구체적인 흐름은 다음과 같다.

```text
이전 Closure wave: 같은 출력에 explicit clause 목록이 남음
          ↓
새 wave: Oracle/proof 관계를 native product로 확보
          ↓
동일 출력에 explicit 목록이 있다는 이유로 native 병합 거절
          ↓
새 product 전체를 proof별로 순회 → 다시 명시적 목록으로 병합
```

기존 clause가 새 product 안의 정확히 같은 조합이어도 이 경계에서 전개될 수 있다. 따라서 다음 구현은 기존 clause를 없애거나 다른 representative로 치환하는 것이 아니다. **동일 member임을 인덱스로 증명하고 기존 clause를 원래 권한의 donor로 보관하면서, 나머지 새 영역은 native relation으로 유지하는 것**이다. 최종 병합에서도 이 혼합 관계를 다시 펼치지 않아야 한다.

첫 적용 범위는 exact-native overlap을 기존 donor 수·보관 한도 안에서 표현할 수 있는 경우로 제한한다. Native domain 밖의 clause, 다른 proof/source owner/layout, non-native proof, staging·공유 권한을 증명하지 못하는 경우는 기존 exact fallback을 유지한다. 이 새 구현의 실제 적용률과 시간 효과는 아직 검증 전이다.

정확성: 두 workload 모두 numeric comparator·receipt audit을 통과했다. v46 및 v43 대비 selected 원문·필드·section, objective raw bits/certificate, Cost Surface fingerprint, selection fingerprint가 모두 일치했다. 모든 assignment의 비용 전수 비교를 의미하지 않는다. Gate는 1,534 PASS 및 probe9 PASS이며 20초 evaluator는 FAIL, workload별 accepted 0/3이다.

근거: `evidence/cofee-50k128-v47-validation/v43-v46-v47-comparison.json`, SHA-256 `9483734cc27570cf2cc0157cc573e7d0f7fbf7b62ebeec99cea537bab2edaa1d`; parity binding SHA-256 `3bade438ae4dae85074295dc6eb3d13b6083f70364abac8cc4778e72bb8d1005`.
