# FedPlanner 고정점 분석의 분리와 공통 계산 재사용

작성일: 2026-10-13  
분석 workspace: `/home/mchoi/fedplanner-memo-20261013`  
Branch: `codex/fedplanner-memo-20261013`  
기준 HEAD: `4314a345c16c20752421ce6251bbc2f366c85825`  
분석 대상: 위 HEAD **및 현재 미커밋 작업 트리**. 아래 Memo 구현의 동작을 HEAD에 이미 포함된 기능으로 해석하지 않는다.  
최초 범위: 직전 대화의 고정점 통합 검토를 문서화했다. §1–10은 구현 전 분석이다.  
후속 구현: 사용자 승인에 따른 2026-10-13–14의 적용 범위와 검증 결과는 **§11–12**에 기록한다. §11의 보류 항목은 §12의 현행 결정으로 갱신한다. 최초 분석 당시의 중복 생성 설명과 후속 제거 결과를 구분한다.

## 1. 결론

**Privacy 외에도 shape, guard 의존성, 후보 간 feasibility 등에 고정점 계산이 있다. 그러나 모든 분석을 하나의 고정점으로 합치는 것보다, 공통 dependency 분석과 순회를 재사용하고 서로 다른 의미의 고정점은 분리하는 것이 단순하고 안전하다.**

우선순위는 다음과 같다.

1. 동일 planning request에서 중복 생성하는 privacy 분석과 cache를 재사용한다.
2. 정확한 binding/def-use/loop/function 관계의 조회와 index 구축을 재사용한다.
3. Loop-carried 값의 변화에 영향을 받는 body 부분만 재평가한다.
4. Privacy와 shape가 동시에 dirty인 경우에만 공통 body 방문을 활용하는 방안을 측정한다.
5. Semantic analysis와 optimizer의 후보-domain propagation은 분리한다.

새로운 범용 Constraint Graph, Proof 객체, 모든 속성을 묶는 범용 분석 엔진을 도입하는 것은 권고하지 않는다. 고정점 통합 자체는 GLM 전체 planning 완료나 성능 향상을 보장하지 않는다.

### 상태와 근거의 구분

- **[확인]**: 현재 소스에서 직접 확인한 동작.
- **[해석]**: 확인한 동작으로부터 도출한 구조적 결론.
- **[제안]**: 향후 구현할 최소 변경 방향. 이 문서 작성으로 구현되지는 않는다.
- **[미검증]**: 성능 효과 또는 별도 동등성 검증이 필요한 사항.

관련 설계는 [통합 아키텍처 명세](FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md), [Control Flow 및 DP 분석](FEDPLANNER_CONTROL_FLOW_DP_DESIGN_2026-10-13_KO.md), [Earliest Safe Pruning 분석](FEDPLANNER_EARLIEST_SAFE_PRUNING_2026-10-13_KO.md)이다. 이 문서는 고정점의 책임과 재사용 경계를 설명하는 보조 분석이며, 기존 문서 전체의 구현 완료를 선언하지 않는다.

**현재 사용자 정책:** 0회/1회/여러 회 loop를 정확하게 지원한다. Backedge transfer는 금지하고 entry/exit와 body 내부의 일반 transfer는 허용한다. Static body rule 정책도 유지한다. 이전 문서의 `T>1` 전제를 이 분석에 다시 적용하지 않는다.

## 2. 현재 반복 분석의 종류

### 2.1 소스에서 확인한 고정점과 반복적 정제

| 대상 | 반복하는 이유와 현재 의미 | 재사용 또는 분리 판단 | 근거 |
|---|---|---|---|
| Privacy/residency | 여러 carried 값과 함수 경계로 coarse 보호 상태가 전파된다. 상태가 증가할 때 dependent를 enqueue한다. | Loop 구조와 dependency 재사용 가능. Exact privacy와 동일시하지 않는다. | [R1] |
| 정확한 loop privacy invariant | Entry와 backedge privacy가 같은 후보를 검사하고, 다른 head의 가정이 깨지면 연관 invariant를 다시 검사한다. | Residency와 정보를 공유할 수 있지만 결과의 의미와 정제 규칙은 분리한다. | [R2] |
| Shape | Entry/backedge, transient, actual/formal 간 dimension 및 NNZ 정보가 바뀐다. | 같은 binding/loop 구조 재사용 가능. Shape 고유의 충돌·unknown 정책 유지. | [R3] |
| Guard/loop relevance closure | Binding 평가가 어떤 guard/loop에 의존하는지 전이적으로 계산한다. | 중복 index 구축을 줄일 대상. 분석마다 관련 edge는 다르다. | [R4], [R5] |
| Guard phase 추론 | FIRST/MIDDLE/LAST의 새로운 증명이 다른 guard 증명을 가능하게 한다. | Program Facts의 국소적 증명으로 유지한다. | [R6] |
| Rule-domain feasibility | 한 후보의 제거가 다른 producer/consumer 후보의 support를 없앨 수 있다. | Semantic analysis와 분리한다. Search 중 rollback 대상이다. | [R7] |

**[확인] 현재 worklist는 이미 변경된 값의 dependent만 다시 방문한다.** 현재 구현을 매번 전체 dependency graph를 처음부터 다시 분석하는 방식으로 설명하면 부정확하다. 초기 index 구축, 중복 분석 인스턴스, context별 재평가와 worklist의 실제 반복 비용을 구분해야 한다.

두 privacy 분석도 같은 계산이 아니다.

- `MemoResidency.solve()`는 coarse domain에서 상향 전파하는 positive released-residency proof를 계산한다. 그 결과만으로 정확한 provenance나 값의 존재를 증명하지 않는다.
- `MemoPrivacy.summarizeLoop()`는 entry와 동일한 **정확한 privacy invariant**의 후보를 반복 제거한다. 일반적인 may-privacy least fixed point를 계산하는 함수가 아니다.

따라서 두 함수를 이름만 바꾸어 하나로 대체하려면 먼저 각각의 결과 계약을 보존해야 한다.

### 2.2 반드시 별도 고정점으로 만들 필요가 없는 관계

- **Def-use 연결:** 현재 structured 분석은 body를 분석하면서 head placeholder를 만들고 정확한 entry/backedge binding으로 닫는다. 모든 reaching-definition 조합을 반복 생성할 필요가 없다. [R8]
- **비재귀 함수 분석 순서:** Call DAG의 callee-first 분석을 활용할 수 있다. 다만 함수 body를 공유하는 것과 actual별 분석 context를 합치는 것은 다르다. [R8]
- **Source availability:** 현재 `AvailabilityProof.loopHead()`는 entry가 존재함을 먼저 증명한 뒤 head를 귀납 가정으로 사용해 backedge를 검사한다. Privacy 수렴만으로 availability를 인정하지 않는다. [R9]
- **비용 계산:** Rule과 supply 선택의 비용 평가·cache invalidation은 필요하지만, 이를 별도의 프로그램 의미 고정점으로 만들 이유는 없다.

## 3. 무엇을 공유할 것인가

### 3.1 동일 privacy 분석의 중복 실행 제거

**[확인]** 다음 두 곳이 각각 `MemoPrivacy`를 생성한다.

- `MemoRuleBuilder.build()`: resolver와 privacy 생성. [R10]
- `MemoProgramModel` 생성자: 별도의 resolver와 privacy 생성. [R11]

`MemoPrivacy` 생성자는 `MemoResidency` 생성과 풀이, privacy dependency index/closure 계산도 실행한다. [R12]

**[제안]** 동일한 Facts, Sources, compile epoch와 정책 snapshot을 사용하는 한 planning request 안에서 분석 객체 또는 그 결과를 전달해 재사용한다. 기존 `RuleBuilder.Result`를 통해 shape를 전달하는 방식처럼 기존 연결점을 우선 사용한다. 새로운 전역 singleton이나 공용 mutable cache를 만들지 않는다.

이 변경의 이점은 동일 의미 분석의 두 번째 실행과 일부 cache 재충전을 없앤다는 것이다. 다만 request 종료 시 cache를 해제하고, source privacy/정책/compile epoch가 바뀌면 재사용하지 않아야 한다. 향후 병렬 실행 시 mutable cache의 thread-safety는 별도로 검토해야 한다.

### 3.2 공통 dependency 골격 재사용

공통으로 참조하는 관계는 다음과 같다.

```text
definition → use
entry → loop head
backedge → loop head
actual → formal
return → caller
```

**[제안]** Program Facts의 binding ID와 기존 역방향 관계를 재사용하여 동일 관계를 반복 탐색하는 비용을 줄인다. 필요한 경우 edge의 분석별 역할을 작은 mask 또는 compact 목록으로 구분한다.

그러나 다음 차이를 없애면 안 된다.

| 분석 | 보존할 dependency 의미 |
|---|---|
| Privacy | Payload, 정확한 입력 provenance, 함수 정상 반환 조건 |
| Dimension | Dimension에 영향을 주는 입력 및 scalar 조건 |
| Cost | Dimension뿐 아니라 NNZ, cost-relevant scalar, 실행 context |
| Availability | 실제 정의의 존재, entry grounding, 정상 completion |

**[확인]** 현재 privacy index에는 함수 전체 completion의 암묵적 dependency가 들어간다. Shape index는 dimension과 cost-statistics의 loop relevance를 구분한다. [R4], [R5]

따라서 모든 분석에 동일한 전이 closure를 강제하지 않는다. 공통 traversal을 공유하더라도 결과의 분석별 projection과 context 조건은 유지해야 한다.

### 3.3 Loop body 방문의 조건부 공유

**[제안]** 같은 loop와 정확한 call/guard context에서 여러 속성이 동시에 dirty라면 한 HOP 방문에서 필요한 transfer만 실행할 수 있다.

```text
visit hop:
    privacy input이 변경됨 → privacy transfer
    shape input이 변경됨   → shape transfer
    해당 입력이 그대로임  → 해당 분석의 기존 결과 재사용
```

Privacy 하나가 변했다고 shape까지 다시 평가하지 않는다. Guard proof의 변경이 특정 분석의 reachable arm을 바꾼다면 그 분석의 관련 dependency만 invalidation한다.

**[확인]** 현재 `derivePrivacyConstraint(Hop, inputPrivacy)`는 shape 분석 결과를 직접 인자로 받지 않는다. 현재 소스만으로 privacy와 shape를 반드시 하나의 상호의존 고정점으로 합쳐야 한다는 근거는 없다. [R13]

**[미검증]** 이러한 공동 방문이 기존의 독립적인 dependency-indexed worklist보다 빠른지는 실측이 필요하다. 우선 분석 재사용과 중복 index 구축 제거를 적용한 뒤 판단한다.

## 4. 합치면 안 되는 의미와 최소 반례

아래 DML은 분석 의무를 설명하는 최소 예시이며 이번 문서 작성 중 실행하지 않았다.

### 4.1 Privacy invariant는 availability 증명이 아니다

```dml
if (p) {
    A = matrix(0, rows=10, cols=1);
}
while (q) {
    A = abs(A);
}
```

`abs`의 privacy transfer가 안정적이어도 `p=false`이고 body가 실행되면 최초 A가 없다. Parser가 이런 경로를 거절하더라도 planner의 raw binding을 대상으로 하는 fixture에는 같은 의무가 남는다.

```text
privacy invariant 성립 ≠ entry 존재 ≠ 정상 실행 가능
```

Entry grounding, UNBOUND, 함수 정상 반환 검사를 독립된 조건으로 유지한다. `[PUBLIC]` payload 증명이 missing source를 만들어내서는 안 된다.

### 4.2 Privacy 수렴은 shape 수렴이 아니다

```dml
A = matrix(0, rows=1, cols=1);
while (q) {
    A = rbind(A, matrix(0, rows=1, cols=1));
}
```

Privacy는 계속 PUBLIC일 수 있지만 dimension은 계속 변한다. 구체적인 row 수가 같아질 때까지 함께 반복하는 방식은 종료 조건이 되지 않는다.

Shape에는 exact/invariant/unknown 및 충돌 처리의 독립적인 규칙이 필요하다. 현재 코드에는 cyclic shape 재평가가 충돌한 arm을 되살려 진동하지 않도록 absorbing conflict 처리도 있다. [R14]

### 4.3 Privacy의 중간 상태는 최종 합법성 증명이 아니다

PUBLIC entry와 PRIVATE backedge가 있는 loop에서 초기 전파 중 보이는 PUBLIC을 최종 결과로 사용하면 안 된다. 뒤늦게 PRIVATE가 도달할 수 있다.

- 안정화되지 않은 optimistic 상태로 collect/CP 합법성을 확정하지 않는다.
- Coarse `MAY_BE_PRIVATE` 또는 UNKNOWN을 정확한 PRIVATE와 동일시해 후보를 영구 제거하지 않는다.
- Aggregation으로 `PRIVATE_AGGREGATE`가 release되는 경우와 strict PRIVATE가 유지되는 경우를 기존 transfer 의미대로 구분한다. [R13]
- Head의 보수적 upper bound를 ZERO/ONE/MANY의 정확한 exit privacy로 대체하지 않는다.

### 4.4 Semantic analysis와 search domain은 수명이 다르다

```text
Privacy / Shape = 프로그램과 입력에 대한 사실
Rule domain     = 현재 탐색 분기에서 남아 있는 실행 선택
```

Optimizer가 PRIVATE 입력을 사용하는 FED 후보를 제거했다고 원래 값의 privacy가 PUBLIC으로 바뀌지는 않는다. Search는 domain을 제거했다가 backtracking으로 복원하므로, 그 변경이 semantic cache에 섞이면 다른 탐색 분기까지 오염된다.

고정점의 증가·감소 방향이 다르다는 사실 자체가 수학적인 통합 불가능성을 뜻하지는 않는다. 그러나 이 경우 **의미, cache 수명, rollback 경계가 다르므로 분리가 더 단순하고 안전하다.** 현재 optimizer는 revision stamp가 바뀐 check만 재검사하고 `Truth.NO`로 증명된 후보만 제거한다. [R7]

### 4.5 서로 다른 함수 호출을 실제 loop 순환으로 합치지 않는다

같은 formal을 사용하는 여러 actual을 합친 summary는 보수적인 전역 증명에 사용할 수 있지만, exact callsite 분석의 대체물이 아니다. Loop 내부 호출과 loop 이후 호출을 무조건 합치면 실제로 존재하지 않는 temporal dependency가 보일 수 있다.

공통 index나 body cache를 도입하더라도 binding identity, call frame, 관련 guard, loop phase/cardinality를 보존한다. Context 축약은 해당 분석 결과가 그 context와 독립적임을 증명한 경우에만 허용한다.

## 5. 권장 실행 구조와 개념 알고리즘

```text
Program Facts
    정확한 binding / guard / loop / function 관계
    필요한 국소 guard proof
        ↓
요청당 재사용하는 Semantic Analysis
    Shape
    Privacy / Residency
    Availability / Normal Completion
    공통 구조 재사용, 분석별 결과와 수렴 조건 유지
        ↓
Native Rule universe와 확정된 사실에 의한 filtering
        ↓
Active Domain Propagation
    Compatibility / shared producer / joint constraint
    변경된 domain의 dependent만 재검사
        ↓
Global Search
    Domain propagation 재사용
    Semantic analysis 전체 재실행 금지
```

Semantic 단계에 미해결 조건이 남으면 기존 contextual query와 UNKNOWN 계약으로 보존한다. 불확실성을 후보의 불가능성으로 바꾸거나, intermediate semantic 결과를 확정된 immutable rule universe의 근거로 사용하지 않는다.

Loop 분석의 개념적 구조는 다음과 같다. 새로운 일반-purpose scheduler API를 요구하는 pseudocode가 아니라 기존 함수의 책임 경계를 나타낸다.

```text
analyzeLoop(loop, exactContext):
    entry bindings와 availability 검증

    carried privacy 상태 안정화
        변경된 carried privacy에 의존하는 body 부분만 재평가
        coarse may-bound와 exact invariant 결과는 구분

    carried shape 상태 안정화
        변경된 dimension/statistics에 의존하는 부분만 재평가
        shape conflict와 unknown 정책 유지

    두 분석이 동시에 dirty인 body 방문은 공유할 수 있음
    실제 분석 간 dependency가 있으면 그 항목만 다시 enqueue
    각 분석의 update / join / termination 규칙은 독립 유지

    ZERO: entry에서 exit로 가는 binding 검증
    ONE: 첫 body 실행의 마지막 정의 검증
    MANY: 반복 invariant와 마지막 실행의 정의 검증

    반복 간 backedge는 DIRECT compatibility 검증
    entry / exit / body 내부의 일반 supply는 기존 합법성 검사
```

연산 결과의 privacy가 낮아질 수 있다는 이유로 순수한 `state = F(state)` 반복을 일반 해법으로 쓰지 않는다. May 분석에는 entry를 포함하는 적절한 join이 필요하며, exact invariant 검사는 별도 정제 규칙을 사용한다. 여러 carried 값의 간접 의존성과 nested loop summary도 변경된 입력에 따라 재검증한다.

## 6. Correctness와 최적해 보존 조건

분석 순회 또는 scheduling을 공유하는 변경은 다음을 만족해야 한다.

1. **의미 보존:** 기존 transfer, exact binding, guard correlation, availability, completion 의미를 바꾸지 않는다.
2. **수렴 보장:** 각 분석에 적절한 partial order와 update 규칙을 둔다. 유한 높이 또는 별도의 종료 근거가 있어야 하며 2~3회 같은 임의 cutoff로 성공 처리하지 않는다.
3. **공정한 재평가:** 관련 dependency가 바뀌면 영향을 받는 분석은 빠짐없이 재평가한다.
4. **Context 보존:** 관련 call frame, guard, phase/cardinality를 cache key에서 생략하지 않는다.
5. **Pruning 보존:** 변경된 scheduling 때문에 intermediate 상태를 최종 증명으로 사용하지 않는다. UNKNOWN은 불가능성 증명이 아니다.
6. **Search 격리:** Search branch의 후보 제거·복원이 immutable semantic 결과를 바꾸지 않는다.
7. **동등성 검증:** 기존의 합법적인 선택을 단순한 보수화로 잃지 않는지 feasible assignment 집합과 비용/최적해를 비교한다. 안전성이 유지된다는 사실만으로 최적해 완전성까지 증명되지는 않는다.

유한 lattice의 독립 성분을 product domain으로 구성하는 것은 이론적으로 가능하다. 그러나 모든 상태 조합을 열거할 필요는 없고, 가능한 상태 수의 곱을 새 Memo로 materialize해서도 안 된다. 이 문서의 권고는 그러한 product solver를 새로 만드는 것이 아니라 기존 분석과 작은 공통 관계를 재사용하는 것이다.

## 7. 시간과 메모리 영향

분석 i의 노드·edge 수를 `V_i`, `E_i`, 노드 상태의 최대 변경 횟수를 `h_i`, 한 번의 평가 비용 상한을 `c_i`라 하자. 유한 변경 횟수와 dependency-indexed 전파가 성립하면 다음 형태의 상한을 사용할 수 있다.

```text
T = O(sum_i ((V_i + h_i * E_i) * c_i))
```

`c_i`에는 input 재조회, set/BitSet 연산 및 native transfer 비용을 포함한다. Context별 호출은 별도로 합산해야 한다. Shape에서 `h_i`가 유한하다는 근거 없이 이 식을 적용하지 않는다. 이 식은 optimizer support search나 전체 global search의 다항 시간 보장이 아니다.

Coarse privacy의 유한 domain은 carried 상태의 증가 횟수를 제한하지만 body 방문 비용, 함수 context 수, dependency fan-out까지 없애지는 않는다. 또한 exact invariant 제거의 반복 횟수와 coarse privacy 상태 증가 횟수는 서로 다른 값이다.

| 변경 | 줄이는 작업 | 남거나 추가되는 작업 | 위험 및 확인 방법 |
|---|---|---|---|
| Request 단위 privacy 재사용 | 두 번째 residency solve/index 구축, 일부 cache 재충전 | 결과/cache 수명 관리 | 다른 epoch/source/policy의 결과를 재사용하지 않는지 검사 |
| 공통 dependency 구축 | 반복적인 binding/actual/return 탐색과 일부 adjacency 중복 | 분석별 edge mask/projection | Completion 또는 shape/NNZ dependency 누락 회귀 검사 |
| Loop-carried 국소화 | 불변·무관 body의 재평가 | Carried→affected body 역참조, nested summary invalidation | 간접 의존성 및 nested loop fixture 비교 |
| 동시에 dirty인 순회 공유 | Scheduling, 반복 lookup | Dirty bit와 분석별 버전 검사 | 독립 worklist 대비 transfer 수/방문 수/시간 실측 |
| Semantic/search 분리 | Search마다 의미 분석 전체 재실행 방지 | Domain revision 및 support cache 유지 | Rollback 후 동일 semantic 결과 확인 |

모든 변경마다 모든 분석을 실행하면 추가 비용이 이득을 상쇄할 수 있다. 결과 배열과 context별 cache는 여전히 필요하므로, 공통 index를 만들었다는 이유만으로 peak memory 감소를 단정하지 않는다. 실측 전 속도 향상 비율을 제시하지 않는다.

## 8. 최소 변경 순서와 검증 계획

| 순서 | 최소 변경 대상 | 완료 기준 |
|---|---|---|
| 1 | `MemoRuleBuilder.build`, `MemoRuleBuilder.Result`, `MemoProgramModel`의 privacy 분석 전달 | 동일 request의 중복 solve/index 제거, 결과 및 feasible plans 불변 |
| 2 | `indexPrivacyDependencies`, `computeBindingDependencies`의 공통 조회/index 구축 | 분석별 relevance 결과 동일, dependency 누락 없음 |
| 3 | `MemoResidency.solve`, `MemoPrivacy.summarizeLoop`의 loop-carried 재평가 범위 | 정확한 call context 및 간접 carried 영향 보존, body 재평가 감소 여부 측정 |
| 4 | 필요한 경우 `MemoShapes.drain`과 body scheduling의 제한적 재사용 | 기존 exact/unknown/conflict 의미 보존, 독립 방식보다 실제 작업량이 줄어드는지 검증 |

`MemoOptimizer.propagate()`는 semantic fixed point에 병합하지 않는다. 기존 domain revision/support cache를 유지한다. 어떤 단계에서도 runtime fallback, UNKNOWN→PUBLIC, UNBOUND 삭제로 성공을 만들지 않는다.

### 재사용할 테스트

| 테스트 | 보존할 의미 |
|---|---|
| [MemoLoopPrivacyTest](../src/test/java/org/apache/sysds/hops/fedplanner/memo/MemoLoopPrivacyTest.java) | Coupled invariant 제거, FIRST/MIDDLE, ZERO/ONE/MANY exit, nested loop, call-frame별 privacy |
| [MemoResidencyTest](../src/test/java/org/apache/sysds/hops/fedplanner/memo/MemoResidencyTest.java) | Grounded induction, 상호 carried, 비재귀 함수, aggregation release와 strict PRIVATE, UNBOUND/unknown source |
| [MemoShapesTest](../src/test/java/org/apache/sysds/hops/fedplanner/memo/MemoShapesTest.java) | Entry/backedge shape, changing shape, branch mismatch, actual별 shape, shared owner |

추가 비교 fixture는 PUBLIC entry + PRIVATE backedge, 여러 carried 값의 간접 전파, 같은 함수의 서로 다른 actual, shape만 변하는 loop, search rollback 전후 cache 격리를 포함한다. 작은 exhaustive fixture에서 분석 결과뿐 아니라 feasible plan 집합, 비용, 최적해를 비교한다.

### 성능 측정 항목

- Request당 `MemoPrivacy`/`MemoResidency` 생성 횟수와 solve 횟수
- 분석별 index 구축 시간과 adjacency 저장량
- 분석별 enqueue/pop, 실제 상태 변경, transfer 호출 횟수
- Loop/context별 carried 변경 횟수와 body 재평가 수
- Privacy/shape/context cache hit·miss 및 peak entry 수
- Feasibility check/support query/search node 수
- Analysis/optimization/total planning time, peak memory, allocation

LogReg/GLM은 동일 입력·정책·Docker 조건에서 단계별로 비교한다. 분석 시간만 줄고 solver 시간이 늘어나는지 구분한다. 이번 문서 작성에서는 테스트·워크로드를 새로 실행하지 않았다.

## 9. 미해결 사항과 구현 전 경계

- **[미검증]** Loop-carried 국소화가 기존 전역 conservative summary와 contextual exact query의 역할을 모두 유지하는지 독립 검증이 필요하다.
- **[미검증]** Shape의 수렴 및 충돌 처리와 privacy의 갱신을 공동 scheduling할 때 정확히 어느 방문이 줄어드는지는 측정되지 않았다.
- **[미검증]** 공통 index로 절감되는 메모리와 dirty/context 관리의 추가 메모리 중 어느 쪽이 큰지는 workload에 따라 다르다.
- **[해석]** 가장 작은 우선 변경은 동일 request의 중복 privacy 분석 제거다. 전체 product-domain solver 도입보다 근거가 명확하고 영향 범위가 작다.
- **잔여 실행 문제:** 이 문서 작성만으로 GLM 전체 planning, global optimum 또는 runtime 완료를 주장하지 않는다.
- **잠재 회귀:** 불완전한 dependency/cache key, 중간 상태 기반 pruning, source grounding과 privacy 혼동, search 상태의 semantic cache 오염을 §8의 fixture와 counter로 감지한다.

## 10. 소스 근거

라인은 문서 작성 시 현재 작업 트리 기준이며 후속 구현에서 이동할 수 있다. 함수명을 함께 기준으로 삼는다.

- **[R1]** [MemoResidency.java:110–122](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoResidency.java#L110): `solve`, 상태 증가와 dependent enqueue.
- **[R2]** [MemoPrivacy.java:467–521](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoPrivacy.java#L467): `summarizeLoop`, entry invariant의 반복 제거.
- **[R3]** [MemoShapes.java:1016–1040](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoShapes.java#L1016): `drain`, binding/owner shape worklist.
- **[R4]** [MemoPrivacy.java:279–334](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoPrivacy.java#L279): `indexPrivacyDependencies`, completion dependency와 guard/loop closure.
- **[R5]** [MemoShapes.java:135–147](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoShapes.java#L135), [311–365](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoShapes.java#L311): shape solve 이후 dimension/cost relevance 구축.
- **[R6]** [MemoProgramFacts.java:936–966](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramFacts.java#L936): `deriveSymbolicGuardPhases`.
- **[R7]** [MemoOptimizer.java:429–470](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java#L429): revision 기반 propagation, NO-only removal, support cache.
- **[R8]** [MemoProgramFacts.java:626–675](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramFacts.java#L626), [756–877](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramFacts.java#L756): 함수 분석과 loop entry/head/backedge/LAST 구성.
- **[R9]** [MemoBindingResolver.java:238–275](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoBindingResolver.java#L238): `AvailabilityProof`, entry 선행 증명과 backedge 귀납.
- **[R10]** [MemoRuleBuilder.java:62–79](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoRuleBuilder.java#L62): `build`의 분석 생성.
- **[R11]** [MemoProgramModel.java:115–119](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L115): model 측 분석 생성.
- **[R12]** [MemoPrivacy.java:251–275](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoPrivacy.java#L251): residency와 dependency index 초기화.
- **[R13]** [FederatedPlannerUtils.java:1532–1581](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerUtils.java#L1532): `derivePrivacyConstraint`, native privacy transfer.
- **[R14]** [MemoShapes.java:1305–1344](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoShapes.java#L1305): absorbing conflict 및 loop shape compatibility.

## 11. 후속 구현과 검증 결과

적용일: 2026-10-13–14. 사용자의 “계획대로 진행하고 검증” 요청에 따라 아래의 안전성이 확인된 부분을 구현했다. 최초 분석의 전체 고정점 재설계가 완료되었다는 의미는 아니다.

### 11.1 구현한 범위

| 상태 | 변경 | 의미와 비용 영향 |
|---|---|---|
| **구현·회귀 검증 완료** | `MemoRuleBuilder.Result`에 기존 `MemoPrivacy`를 전달하고 model에서 privacy와 연관 resolver를 재사용 | Builder→model 경로의 privacy/residency solve 및 privacy index 구축을 각각 2회에서 1회로 줄인다. 별도 shape resolver는 그대로다. |
| **구현·회귀 검증 완료** | `MemoPrivacy.resolverFor`에서 Facts/Sources 동일 snapshot 검증 | 다른 request의 cache를 사용하기 전에 실패한다. Privacy label map이 같다는 이유로 다른 source/pool snapshot을 허용하지 않는다. |
| **구현·회귀 검증 완료** | `MemoShapes.requireRequest`를 builder overload와 model에서 검사 | Foreign shape authority와 현재 privacy authority를 혼합하지 않는다. |
| **구현·회귀 검증 완료** | Facts에 immutable `callsForFunction` index 추가 | Privacy/residency/shape가 formal마다 모든 함수를 호출한 call 목록을 다시 훑지 않는다. 새 그래프나 source product는 만들지 않는다. |
| **보류** | `summarizeLoop`를 관련 carried 값만 검사하는 worklist로 교체 | 현재 관련-head 관계는 nested summary와 whole-call completion 의존성에 대해 완전하지 않다. 아래 반례를 테스트로 추가하고 기존 안전한 반복을 유지했다. |
| **측정 전 보류** | Privacy와 shape의 공동 scheduling | 서로 다른 domain/closure를 합치지 않았다. 실제 방문 감소와 수렴 동등성 근거가 필요하다. |

Production 변경은 6개 파일의 작은 경계 변경이다: `MemoRuleBuilder`, `MemoProgramModel`, `MemoPrivacy`, `MemoProgramFacts`, `MemoResidency`, `MemoShapes`. 최초 저장한 작업 트리와의 차이는 [production-changes.patch](../experiments/memo-migration-20261013/analysis-reuse/production-changes.patch)에 있다. 이전 미커밋 변경, DML, runtime, optimizer, native capability/비용/pruning 로직은 이번 구현에서 변경하지 않았다.

Per-function index는 기존 `facts.calls()`의 순서와 inlined call을 모두 보존한다. Privacy/residency는 `actualBindingIds`, shape는 기존의 `argumentBindingIds`를 계속 사용한다. Shape의 runtime formal 전파에 있던 `callOccurrenceId >= 0` 조건도 유지한다. 각 분석의 closure와 transfer 결과를 하나로 합치지 않았다.

Formal 수를 T, 전체 call 수를 C, 함수 f의 formal 수와 call 수를 각각 T_f, C_f라 하면, 이 부분의 반복 filtering 비용은 `O(T*C)`에서 index 구축 `O(F+C)`와 조회 순회 `O(sum_f T_f*C_f)`로 바뀐다. 추가 index 공간은 `O(F+C)`의 참조다. 전체 solver 복잡도와 peak memory 개선율은 이 식에서 결론 내리지 않는다.

### 11.2 Loop 국소 재검사를 보류한 구체적 이유

`MemoBindingResolver.collectLoopHeads()`는 `LOOP_HEAD`에서 멈추며, CALL_RETURN에서는 payload를 따라간다. 반면 exact privacy는 nested loop summary를 다시 계산하고 함수 전체 completion도 검사한다. 따라서 현재 `relevantLoopHeads()`만을 완전한 privacy invalidation 관계로 재해석하면 안 된다.

다음 fixture에서 X만 PRIVATE이고 나머지 initial matrix는 PUBLIC이다.

```dml
A=matrix(1,2,2); B=matrix(2,2,2); X=matrix(3,2,2);
for(i in 1:2) {
    C=matrix(0,2,2);
    for(j in 1:2) { B=abs(B)+C; C=abs(A); }
    A=abs(X);
}
print(sum(A)+sum(B));
```

Outer A의 PUBLIC invariant가 제거되면 inner C/B summary를 거쳐 outer B도 재검사되어야 한다. 새 `MemoLoopPrivacyTest.outerHeadRejectionInvalidatesDependentNestedSummaries`가 A/B 모두 UNKNOWN임을 검증한다. 변경 전 분석에 대해 이 fixture를 포함한 6개 loop privacy 테스트도 통과했다. 이는 현재 반복을 안전하게 유지할 근거이며, 모든 nested/function privacy 분석의 완전성 증명은 아니다.

다음 단계의 최소 선행 조건은 nested summary, whole-call completion, 이미 제거된 head를 통과하는 간접 dependency까지 포함하는 작은 관계를 검증하는 것이다. 그 증거 없이 검사 범위를 줄이거나 새로운 Proof/Closure 계층을 도입하지 않는다.

### 11.3 검증 결과와 한계

근거 디렉터리: [analysis-reuse](../experiments/memo-migration-20261013/analysis-reuse/).

| 검증 | 결과 | 근거 |
|---|---|---|
| 변경 전 주요 privacy/loop/shape/model/exhaustive 회귀 | 73개 PASS | `baseline-tests.log` |
| Request 재사용/격리 신규 red 회귀 | 기존 구현에서 의도한 4개 실패 확인 | `request-reuse-red-corrected.log` |
| Clean Maven compile/test-compile | BUILD SUCCESS | `clean-compile.log` |
| Request/index/loop focused 회귀 | 17개 PASS | `focused-clean-tests.log` |
| Memo component 전체 선택 suite | 27개 클래스, 244개 PASS | `full-unit-tests.log`, 최종 재검증 `final-unit-tests.log`, `unit-classes.txt` |
| Tiny exhaustive before/after | 5개 fixture, 2,604 assignments의 feasible 수와 exact 최적 비용 동일 | `exhaustive-before-after.json` |
| Actual rewritten GLM model의 고정 UNKNOWN 회귀 | 1개 PASS, search node budget 1 | `bounded-workloads.log` |
| Actual rewritten LogReg model의 제한 탐색 회귀 | 1개 PASS, 100,000-node 한도, feasible incumbent 존재, RESOURCE_EXHAUSTED | `bounded-workloads.log` |
| 독립 코드 검토 | 6 production + 3 test 파일, blocking finding 없음, APPROVE | Native read-only reviewer 결과 |

신규 request 회귀는 warmed cache의 답·cache hit 보존, 다른 Facts/Sources/Shapes 거절, 동일 Facts/Sources로 새 build를 했을 때 새 분석 생성, PUBLIC/PRIVATE request 분리를 검사한다. Index 회귀는 두 개 이상의 함수를 가진 runtime/inlined fixture의 이전 filtering 결과와 순서·개수·불변성을 비교한다.

검증 중 test fixture의 IPA flag가 parser-level inlining을 막는다는 가정이 틀린 것을 발견하여 retained loop 함수 fixture로 교정했다. 또한 보조 lane의 Maven output-directory 옵션이 격리되지 않은 문제가 있어 모든 보조 build를 중지하고 root 단독 clean compile 이후 결과만 최종 근거로 채택했다.

**보장 범위:** Tiny exhaustive 비교는 이번 변경 전후 fixture의 결과 보존 및 해당 모델에서 solver와 exhaustive optimum의 일치를 검사한다. 독립적인 전체 DML 의미 증명이나 GLM/LogReg global optimality 증명은 아니다. Workload 회귀는 bounded model 진단이며 Docker runtime 실행 또는 planning 성능 benchmark가 아니다. 이번 단계에서 성능 개선율을 주장하지 않는다.

## 12. 남은 분석 재사용 작업 적용 — 2026-10-14

### 12.1 최종 결정과 범위

**§11의 두 보류 항목은 이번 후속 작업으로 아래와 같이 처리했다.** §1–10과 §11은 각각 최초 분석과 첫 구현 시점의 기록이며, 현행 결정은 이 절이다.

| 항목 | 현행 결정 | 실제 변경 |
|---|---|---|
| Loop-carried 국소 재검사 | **구현·회귀 검증** | `MemoPrivacy.summarizeLoop()`에서 최초 전체 후보 확인 후, 제거된 head의 영향권에 있는 surviving backedge만 재평가한다. |
| Privacy/shape 공동 scheduling | **현재 구조에서는 도입하지 않음** | 두 의미 고정점은 분리한다. 단계별 작업 counter 및 workload 검증을 추가하고, request/index 재사용과 privacy 내부 국소 scheduling만 적용한다. |
| Semantic/optimizer 고정점 통합 | **도입하지 않음** | 후보 제거/rollback은 기존 optimizer 소유다. Privacy, residency, shape를 optimizer domain과 섞지 않는다. |

공동 scheduler를 구현한 뒤 되돌렸다는 뜻이 아니다. **대조 구현의 wall-time 비교는 수행하지 않았다.** 현재 코드에는 양 분석의 transfer가 서로의 결과를 다시 dirty로 만드는 edge가 없다. Shape는 request 생성 때 eager하게 수렴하고 privacy exact query/loop summary는 이후 context별로 실행된다. 아래 workload counter에서도 privacy 조회·탐색 중 shape worklist 재실행이 없었다. 제거할 중복 body transfer가 확인되지 않은 상태에서 mask/공용 queue/lifecycle 결합을 추가하지 않는 것이 최소 변경이다. 이는 향후 모든 workload에서 공동 순회가 불리하다는 증명이 아니다.

Production 변경은 **`MemoPrivacy.java`와 `MemoShapes.java` 두 파일**이다. Rule/Oracle/solver/cost/emission 및 DML은 변경하지 않았다. 이전 단계의 미커밋 변경도 보존했다. 새 범용 graph, Proof/Anchor, runtime fallback, arbitrary pruning 또는 production full-scan option을 추가하지 않았다.

### 12.2 영향 관계와 최소 알고리즘

`MemoPrivacy.indexPrivacyDependencies()`가 이미 만들던 direct binding 관계의 reverse 목록을 `int[][] bindingConsumers`로 보존한다. 별도의 HOP graph를 복제하거나 source/assignment 조합을 만들지 않는다.

반드시 포함하는 관계:

1. Binding children; DEFINITION의 실제 HOP input binding.
2. LOOP_HEAD의 **entry와 backedge 모두**. Nested head나 이미 제거된 head에서도 탐색을 중단하지 않는다.
3. FORMAL의 함수별 actual binding. 모든 callsite의 influence를 보수적으로 포함하되, exact privacy 값은 기존 call frame으로 분리한다.
4. CALL_RETURN → call completion; CALL_COMPLETION → function completion 및 **모든 반환 payload**.
5. Concrete inlined witness → symbolic CALL_RETURN wrapper. `MemoBindingResolver.resolve()`의 암묵적 remap을 반영했다. 모호한 wrapper도 influence만 추가하며 UNKNOWN을 exact로 바꾸지 않는다.
6. DEFINITION이 transient-read occurrence를 가리키면 정확한 `facts.valueBinding()`을 포함한다. 일반 Facts에서는 read가 이미 alias되어 추가 edge가 필요 없는 경우가 많다.

전체 dependency를 완성한 **뒤** reverse 목록을 생성한다. 뒤늦게 나타나는 wrapper가 앞선 binding을 참조해도 edge가 누락되지 않는다. 같은 index로 guard/loop relevance closure를 계산하므로 암묵적 의존성이 contextual cache projection에도 포함된다.

```text
summarizeLoop(loop, originalContext):
    cached complete-context summary가 있으면 반환
    entry privacy가 EXACT인 head만 candidates에 저장
    dirty = candidates
    while dirty가 비어 있지 않음:
        bound = originalContext + surviving candidate의 entry privacy
        bound = direct nested summaries를 bound 아래서 다시 계산
        remove = {}
        기존 carried 순서로 dirty backedge를 bound 아래서 평가
        entry와 다르거나 UNKNOWN이면 remove에 추가
        # 여기까지 같은 immutable 가정 아래 확인; 중간 제거 금지
        if remove가 비어 있으면 종료
        candidates -= remove
        affected = reverseReach(remove의 headBindingId)
        dirty = candidates 중 backedgeBindingId가 affected에 있는 것
    entry / rejected 결과로 기존 형식 summary 생성 및 cache
```

Reverse reachability는 lexical body에 갇히지 않는다. 함수/entry/backedge/nested 경계 밖을 통과할 수 있고, **재검사 target만 해당 loop의 surviving 후보로 한정**한다. 매 wave의 nested context는 originalContext에서 재구축하며 이전 wave의 nested exact 가정을 누적하지 않는다. 기존 summary/context cache key, guard/phase/cardinality, invocation-frame 분리는 유지했다.

**동등성 논거:** 기존 전체 재검사와 최초 후보·첫 batch가 동일하다. 다음 wave에서 결과가 바뀔 수 있는 surviving backedge는 제거된 가정에 전이적으로 의존한다. 보수적인 reverse relation이 이 경로를 포함하므로 해당 값은 반드시 dirty가 된다. 나머지는 입력/context 의미가 그대로이므로 이전 판단을 유지할 수 있다. Batch 제거와 기존 순서도 유지해 diagnosis가 임의의 방문 순서에 의존하지 않는다. 이 논거의 전제는 dependency 완전성이다. 향후 새로운 binding/implicit resolver dependency를 추가하면 이 index와 회귀도 함께 갱신해야 한다.

Availability·normal completion·ZERO/ONE/MANY·entry grounding은 기존 분석의 책임으로 남는다. 이 scheduling 변경은 privacy summary를 availability 증명으로 승격하지 않는다. UNKNOWN, missing/UNBOUND 및 privacy-changing loop의 temporal case도 그대로 유지한다.

### 12.3 복잡도와 실제 감소/추가 작업

`K`: 한 loop의 candidate 수, `R≤K`: 제거 wave 수, `B/E`: 전체 binding/influence edge 수, `A_r/E_r`: 해당 wave에서 도달한 binding/edge 수.

- 기존 backedge 평가: 최악 `O(K²)`회.
- 새 backedge 평가: 최초 `K` + 각 wave의 **dirty surviving candidate 수**. Nested summary 및 exact evaluation cache는 계속 재사용한다.
- 추가 reverse 탐색: `Σ_r O(A_r+E_r)`, 최악 `O(R(B+E))`.
- Context 복사와 carried metadata scan은 여전히 `O(RK)`이다. 모든 반복을 제거했다고 주장하지 않는다.
- Retained reverse index: `O(B+E)` 추가 공간. Wave scratch: `O(B)` bitset/queue. `K×B` closure나 candidate 조합은 저장하지 않는다.
- Index counter는 worklist pop/edge union 횟수, shape counter는 binding/owner pop 및 두 relevance closure의 합을 센다. 타이밍 추정을 위한 값이 아니라 수행 작업의 구분이다.

작은 독립-head fixture에서 기존 backedge 검사 **6회 → 4회**, 새 reverse 방문 **20 node / 20 edge**였다. 검사 감소와 추가 traversal 비용을 함께 기록한다. Cheap cached query가 많거나 conservative function fan-out이 큰 경우 순수 이득이 아닐 수 있다.

### 12.4 Workload component 측정과 공동 scheduler 결정

다음은 **host component 진단의 operation count**이며 runtime/performance benchmark가 아니다. 비교 baseline은 변경 직전 `MemoPrivacy.java` snapshot에 counter만 넣어 별도 class directory에서 컴파일했다. Production에 legacy 경로나 옵션을 되살리지 않았다. Shape는 의미 변경 없이 counter만 추가된 동일 구현이다.

| Counter | LogReg 전체 재검사 → 국소 재검사 | GLM 전체 재검사 → 국소 재검사 |
|---|---:|---:|
| Search node budget | 100,000 → 100,000 | 1 → 1 |
| Backedge checks | 84 → 50 | 41 → 41 |
| Summary rounds (nested/context 호출 포함) | 16 → 8 | 5 → 5 |
| Exact privacy evaluations | 1,007 → 606 | 1,411 → 1,411 |
| 추가 reverse binding visits | 0 → 3,464 | 0 → 3,159 |
| 추가 reverse edge visits | 0 → 4,632 | 0 → 4,797 |
| Privacy relevance index visits | 1,642 → 1,642 | 7,055 → 7,164 |
| Privacy relevance edge unions | 2,733 → 2,753 | 13,325 → 13,668 |
| Shape binding / owner visits | 3,117 / 494 (동일) | 4,835 / 990 (동일) |
| Shape relevance visits / edges | 1,711 / 649 (동일) | 4,456 / 3,490 (동일) |

새 index의 retained unique edge는 LogReg **1,187**, GLM **2,328**개이고 binding은 각각 **841**, **1,647**개다. Index 증가 일부는 implicit concrete-return dependency 보강 때문이다. Array 객체/header를 포함한 실제 retained/peak bytes는 이번에 측정하지 않았으므로 메모리 감소를 주장하지 않는다.

**관측:** LogReg의 privacy 작업은 줄었지만 GLM에서는 동일한 backedge 평가에 reverse 탐색이 추가됐다. 모든 workload에 대한 속도 개선 주장은 성립하지 않는다. Bounded GLM은 privacy fixed UNKNOWN 없이 `RESOURCE_EXHAUSTED`까지 진행했고 incumbent은 없다. Bounded LogReg는 동일 status에서 feasible incumbent이 있다. 둘 다 이 bounded 진단만으로 global optimum을 증명하지 않는다.

동일 request에서 privacy query를 반복해도 shape counter는 그대로였고, source override를 제외한 cacheable derived query는 두 번째 pass에서 추가 exact evaluation 없이 cache를 사용했다. Source override는 의도적으로 cache를 우회하므로 이를 잘못된 cache miss로 계상하지 않는다. 이 관측과 실제 transfer의 비상호의존성을 근거로 **privacy/shape 공동 scheduler를 이번 설계에 추가하지 않는 결정**을 확정했다.

### 12.5 검증·테스트 전환

근거: [analysis-reuse-remaining-20261014](../experiments/memo-migration-20261013/analysis-reuse-remaining-20261014/).

- 변경 전 기존 privacy/loop/shape **34개 PASS**; 초기 새 full-scan reference **4개 PASS**.
- Root 단독 clean compile/test-compile **SUCCESS**. 이후 completion 테스트 추가 compile도 SUCCESS.
- Focused **33개 PASS**; 뒤에 추가한 whole-call completion을 포함한 incremental suite **8개 PASS**.
- 기존 전체 선택 Memo suite에 신규 테스트를 합친 최종 **29개 클래스 / 256개 PASS** (`final-unit-tests.log`). 독립 code review는 **APPROVE, finding 0개**다.
- Tiny exhaustive **5 fixtures / 2,604 assignments**의 feasible 수와 exact 최적 비용이 이전 결과와 같다. 이는 전체 DML의 독립 의미 증명이나 workload 최적성 증명은 아니다.
- Test-only `FullScanReference`는 별도 분석 인스턴스, 기존 synchronous rejection 및 nested recursion으로 결과 map(상태·label·이유)을 비교한다. Production scheduler를 reference로 다시 호출하지 않는다.
- `MemoLoopPrivacyIncrementalTest`: 4 privacy label, 간접/3-wave rejection, 독립 head, nested/guard, ZERO/ONE/MANY 및 iteration phase, aggregation release, concrete inlined return, retained multi-return/call frame을 검증한다.
- Whole-call completion fixture: requested B payload와 독립적인 다른 반환 Z가 제거된 A head에 의존한다. 모든 반환값이 정의된 정상 DML에서 **initial candidate 2개, outer 검사 3회**로 completion-only 영향의 재검사를 확인하며 B는 올바르게 PUBLIC으로 남는다. 실제 UNBOUND 거절은 기존 `requestedReturnPrivacyRetainsWholeCallCompletionDependencies` 회귀가 따로 검증한다.
- `MemoAnalysisWorkTest`: shape 변화/privacy 불변 및 privacy 변화/shape 불변을 구분하고 GLM/LogReg request-local counter/cache 동작을 검증한다.

검증 중 발견한 fixture 오류도 보존했다: body에서 incoming A를 읽지 않는 `A=abs(X)`는 carried-variable fixture가 아니어서 `A=abs(A)+X`로 교정했다. Source override를 derived cache와 동일시한 assertion도 수정했다. 구현 첫 compile의 존재하지 않는 helper 호출은 기존 `DataOp/TRANSIENTREAD` 판정으로 교정했다. 실패 로그는 삭제하지 않았다.

### 12.6 Docker 실행과 남은 제품 범위

승인된 `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e`만 사용했다. 첫 `/grid` stage는 Snap Docker에서 보이지 않아 DML 시작 전에 실패했다. 이는 planner 실패가 아니다. 알려진 `/home/mchoi/fedplanner-memo-docker-stage-20261013` stage로 다시 실행해 class preflight와 model proof를 통과했다.

`remaining-fp-20261014-home-stage` 결과:

- **LogReg PASS:** 3-worker fixture, native rules 920, search nodes 2,933,891, `OPTIMAL`. Memo total planning **26.2756 s** (optimization 24.2537 s), 전체 compilation 27.4831 s, execution 2.621 s. CP와 FED model 8×2, 최대 차이 **1.6653345369377348e-16**. 이는 해당 cost model/실행 계약/fixture에서의 최적성 및 numeric parity다. Legacy 결과가 같다는 이유만으로 최적성을 주장한 것이 아니다. 단일 Docker 실행이므로 개선율을 제시하지 않는다.
- **GLM FAIL (당시 결과):** CP model은 생성했지만 FED는 `UNRESOLVED`로 emission 전 종료했다. 오류에 함께 붙은 `MOVEMENT_FACTOR_WIDTH origin=196 width=10`은 **실패 조건이 아닌 경고**이며, 196은 binding ID다. 후속 소스 추적 및 동일 fixture의 component 재구성에서 실제 즉시 종료 원인은 owner 974의 native coverage 미완료로 확인했다. 정상 함수 반환 조건을 잃은 transpose shape가 canonical-effect 증명을 막는다. Privacy UNKNOWN을 PUBLIC으로 바꾸거나 native coverage 게이트를 우회하지 않았다. Java entrypoint rc가 0이어도 결과 파일/OPTIMAL evidence가 없어 harness가 올바르게 실패 처리했다. 상세 정정: [movement 진단 분석](FEDPLANNER_GLM_MOVEMENT_DIAGNOSTIC_ANALYSIS_2026-10-14_KO.md).
- **후속 shape repair 상태:** 위 coverage 차단은 수정하고 Memo component266개/작은 exhaustive2,604 assignments를 검증했다. 새 Docker LogReg는 OPTIMAL/planning23.25095초/CP 수치 parity PASS. GLM은 coverage를 지나 optimizer에 진입했으나180초 case timeout으로 full OPTIMAL/emission은 미완료다. Bounded component probe에서 coverage=true, feasible incumbent, nodes10,000 RESOURCE_EXHAUSTED를 확인했다. **Fixed-point/shape 해결과 global search 완료를 구분한다.** 최신 구현·한계는 [분석 문서 §8](FEDPLANNER_GLM_MOVEMENT_DIAGNOSTIC_ANALYSIS_2026-10-14_KO.md#8-정상-반환-shape-repair-구현-및-검증--2026-10-14-후속-결과).

따라서 **이번 문서의 분석 재사용 작업 완료와 GLM 전체 실행 성공은 별개**다. GLM의 shape/native coverage 문제를 이번 privacy scheduler 수정으로 해결했다고 주장하지 않는다. Movement factor의 폭은 별도의 비용 탐색 성능 위험이며 이번 종료의 원인이 아니다. 변경 직전 privacy 전체 재검사 snapshot(counter-only instrumentation)을 별도 class/source stage에 넣은 Docker 대조에서도 **동일한 GLM 오류 메시지**를 재현했다(`remaining-fp-20261014-baseline-glm`). 양쪽 모두 class preflight와 model proof는 통과했다. 따라서 이 GLM blocker는 이번 국소 scheduling 변경이 새로 만든 회귀가 아니다. 상세 상태는 session 문서와 `validation.json`을 따른다.

주요 위치: `MemoPrivacy.indexPrivacyDependencies():302`, `summarizeLoop():511`, `affectedBindings():583`; `MemoShapes.statistics():87`, `computeBindingDependencies():323`, `drain():1027`; 새 두 테스트 클래스. 행 번호는 이번 미커밋 작업 트리 기준이며 HEAD 구현 완료를 뜻하지 않는다.
