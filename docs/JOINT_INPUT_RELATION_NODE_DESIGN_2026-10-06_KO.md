# SystemDS 공동 입력 관계 노드를 먼저 구현하는 설계

작성일: 2026년 10월 6일

> 후속 결정: 현재 실험에서 J_v 확장이 필수라는 근거가 확인되지 않아, 이번 논의에 따른 구현은 진행하지 않고 기존 공통 reader 제약을 유지한다. 아래 내용은 구현하지 않은 확장 제안으로 보존한다. 현재 코드의 제약과 판단은 [현재 블록 간 배치 제약 보고서](CURRENT_CROSS_BLOCK_PLANNING_CONSTRAINTS_2026-10-06_KO.md)를 따른다.

## 결정과 이전 권고의 변경

J_v를 consumer별 분석용 관계 노드로 명시적으로 저장하는 방식부터 구현한다. 같은 실행에서 함께 공급될 수 있는 정의들을 tuple로 보존하고, planner는 각 tuple에서 선택한 물리 계획의 실행 가능성을 검사한다.

앞선 경량 설계는 조합 증가를 미리 피하기 위해 필요한 배치 관계만 증명하는 분석을 우선 제안했다. 그러나 실제 workload에서 J_v의 크기와 생성 비용을 측정하지 않은 상태에서 명시적 표현을 피해야 한다고 판단할 근거는 충분하지 않았다. 먼저 관계를 직접 표현하여 정확성과 규모를 확인하고, 실제 부담이 확인된 부분에 공유·압축·지연 계산을 적용한다.

이 문서는 관계 표현의 우선 선택을 수정한다. 기존 E2E 설계의 privacy, TR/TW placement, 실제 선택과 증명의 일치, 재컴파일 계약 및 runtime fallback 금지 요구는 유지한다. 중첩 if/else·loop·비재귀 함수는 포함하며, 실제 재귀 함수와 상호 재귀 함수의 지원 확장은 제외한다.

현재 상태는 설계 문서다. 새 관계 노드의 코드 구현, tuple 규모 측정 및 E2E 실행은 수행하지 않았다.

## 관계 노드의 의미

다음 예제에서 A와 B는 서로 다른 worker pool이다. 각 pool의 X와 Y는 해당 연산에 필요한 shape, FType과 partition alignment를 만족하고 privacy가 FED 실행을 허용한다고 가정한다.

```text
if (c) {
    X = X_A;
    Y = Y_A;
} else {
    X = X_B;
    Y = Y_B;
}
Z = X + Y;
```

Z의 공동 입력 관계를 다음처럼 표현한다.

```text
J_Z 관계 노드
┌─────────────────┐
│ (X_A, Y_A)      │
│ (X_B, Y_B)      │
└─────────────────┘
        │
        ▼
     Z = X + Y
```

관계 노드는 runtime에서 실행하는 새 연산이 아니다. 분석 그래프의 relation 또는 factor로 두고, 기존 consumer에 공동 입력 제약을 제공한다. 이 노드 자체에 CP/FED 실행 방식이나 데이터 이동 비용을 부여하지 않는다.

최소 자료구조의 의미는 다음과 같다. 명칭은 구현 제안이다.

```text
JointInputRelation
    consumer          : 관계를 소비하는 정적 연산
    inputPositions    : tuple의 각 칸이 대응하는 입력 위치
    tuples            : 함께 도달할 수 있는 supplier 정의 tuple의 집합
```

Tuple 원소는 기존 compiled occurrence 및 값·함수 경계 identity를 참조한다. 변수 이름만으로 정의를 식별하지 않는다. 선택된 물리 realization과 실제 map은 이 정의들의 기존 후보 및 map 전달 규칙에서 얻는다. Tuple을 저장했다는 사실만으로 map 호환성이 증명되지는 않는다.

## Planner의 검사 방법

Z의 무재배치 FED 후보에는 다음 조건을 적용한다.

```text
선택된 X_A와 Y_A에서 Z의 FED 실행이 가능
AND
선택된 X_B와 Y_B에서 Z의 FED 실행이 가능
```

A와 B가 같은 pool일 필요는 없다. 각 행에서 실제로 함께 들어오는 입력들이 실행 조건을 만족하면 된다. Optimizer가 두 행 중 유리한 하나만 골라 검사해서는 안 된다.

선택 계획을 P라고 하면 검사 형태는 다음과 같다.

\[
\operatorname{Valid}(P,v)
=\bigwedge_{t\in\widehat J_v}\operatorname{Executable}(P,v,t).
\]

여기서 분석 관계는 실제 가능한 tuple을 누락하지 않아야 한다. 일반 DML의 모든 경로 조건을 정확히 판정한다고 전제하지 않으며, 정확히 판정하지 못한 가능성을 포함할 수 있다. 따라서 분석 결과를 실제 의미적 J_v와 항상 같다고 주장하지 않는다.

각 정적 producer는 하나의 선택된 realization을 사용한다. 서로 다른 행에서 같은 producer의 다른 후보를 임의로 선택해서는 안 된다. Runtime 경로에 대한 전칭 검사와 planner의 물리 후보 선택을 구별한다.

현재 DP도 `ExactPhysicalModel`을 만들어 기존 local optimizer에 전달한다. 관계 노드는 별도 solver를 추가하기보다 이 공통 모델의 조건으로 연결한다. 해당 consumer 후보가 선택된 경우에만 관계 조건을 활성화하고, 실제로 참조하는 producer 선택을 factor scope에 포함한다. [FederatedPlanLocalCost.java][S1], [ExactPhysicalModel.java][S2]

## 함께 수정해야 하는 세 부분

### 변수별 merge 이전의 공동 정의 정보

현재 분석은 predecessor의 정의를 변수별 집합으로 합친다. 다음 결과만 남기면 실제 pairing을 복원할 수 없다. [PlacementProgramFacts.java][S3]

```text
X의 정의 = {X_A, X_B}
Y의 정의 = {Y_A, Y_B}
```

이 집합만으로는 AA/BB인지 AB/BA인지 구별할 수 없다. 따라서 CFG 분석 중 관련 입력들의 공동 정의 관계를 유지하고 consumer의 tuple로 투영한다. 변수별 집합을 먼저 만든 뒤 Cartesian product를 취하는 것으로 대신하지 않는다.

관계 노드는 결과를 저장하는 단순한 형식이다. 그 내용을 올바르게 계산하는 분석 규칙은 여전히 필요하다. 다만 처음부터 일반적인 symbolic 실행 엔진을 만드는 대신, 정적 정의와 기존 CFG를 이용한 명시적 관계 계산으로 시작한다.

### 고정된 공통 map을 요구하는 reader 검사

J_v를 추가해도 앞단에서 X_A와 X_B가 같은 reader map을 지원해야 한다고 요구하면, 새 후보는 consumer에 도달하기 전에 사라진다. 현재 `exactTransientReplay`의 durable 경로는 각 seed와 같은 물리 layout을 모든 source가 지원하는지 검사한다. [PlacementRelationClosure.java][S4]

새 관계 경로에서는 TRead가 실제 공급된 값의 map을 전달하도록 표현하고, consumer의 물리 호환성을 관계의 각 행에서 검사한다. 기존 concrete map 후보와 coarse placement, privacy 및 값 identity 계약은 유지한다.

현재 realization은 서로 다른 native pool을 같은 후보의 ordinary support clause에 섞는 것도 제한한다. J_v의 여러 행을 그 clause의 대안 선택과 혼동하지 않고 typed 관계로 연결해야 한다. [PlacementAnalysis.java][S5]

### 중첩 제어 흐름과 값의 map 전달

| 구조 | 관계를 계산하는 방법 |
| --- | --- |
| if/else | 각 arm에서 도달하는 공동 tuple을 합침 |
| 한쪽 arm에서 미갱신 | 해당 변수의 진입 정의를 그 arm의 tuple에 유지 |
| 순차적인 독립 분기 | 앞 관계를 다음 분기로 전달하여 실제 가능한 교차 조합도 포함 |
| 중첩 if/else | 안쪽 관계를 바깥 구문에 연결하고 필요한 입력으로 투영 |
| loop | entry와 backedge 관계를 정적 정의 기준으로 고정점까지 계산 |
| 비재귀 함수 | 호출별 실제 인자를 형식 인자에 연결하고 같은 호출의 반환 관계 전달 |

Loop를 실행 횟수만큼 펼치지 않는다. 다만 같은 정적 정의가 반복마다 다른 실제 map을 전달할 수 있으므로, 정적 supplier tuple만으로 물리 증명이 완성되는 것은 아니다. 연산의 map 전달 규칙과 필요한 loop 관계를 함께 검사한다. 서로 다른 반복의 값을 같은 실행의 값처럼 묶거나 순환 참조만으로 증명을 성공 처리해서는 안 된다.

함수 역시 공유 본문의 정적 계획 선택을 유지하면서 호출별 map을 연결한다. 비재귀 호출 graph를 이용하고 실제 재귀 함수의 새로운 분석은 추가하지 않는다.

## Runtime과 비용에서 관계 노드를 사용하는 범위

Runtime에 모든 tuple을 전달하거나 그중 하나를 고르는 새 실행기를 만들지 않는다. 기존 DML 제어 흐름이 실제 값을 선택하고, 기존 FED instruction이 그 값의 map으로 실행한다. 선택된 무재배치 계약의 검사와 출력 map 계승은 기존 E2E 계획에 따라 유지한다.

기존 FED binary kernel은 실제 입력 alignment에 따라 직접 실행하거나 broadcast하는 경로를 가진다. 따라서 새 계획이 무재배치를 약속했다면, 계약 위반 시 broadcast 경로로 조용히 넘어가지 않도록 해야 한다. [BinaryMatrixMatrixFEDInstruction.java][S6]

관계의 모든 행을 legality 검사한다고 해서 모든 행의 실행 비용을 더하는 것은 아니다. 행들은 한 실행에서 선택되는 서로 다른 상황일 수 있다. 비용의 집계 정책은 기존 구현 계획에서 명시한 방식으로 별도 적용하고, 관계 노드 자체에 가상의 이동 비용을 붙이지 않는다.

## 구현 순서

1. Consumer별 `JointInputRelation`과 supplier tuple 표현을 추가한다.
2. CFG에서 관련 입력의 공동 정의를 계산하고 관계 노드에 저장한다.
3. 기존 공통-map 요구 때문에 사라지던 후보가 관계를 통해 실제 source map을 전달하도록 확장한다.
4. 공통 물리 제약 모델과 최종 선택 검증에서 각 tuple의 실행 가능성을 검사한다.
5. 선택 계약을 emission과 재컴파일 및 runtime까지 연결한다.
6. Nested if·loop·비재귀 함수의 DP E2E와 독립 분기 대조군을 통과시킨다.
7. 실제 관계 크기와 분석 비용을 측정한 후 부담이 확인된 부분만 공유·압축·지연 계산으로 개선한다.

이 순서는 앞선 구현 계획에서 필요한 관계만 질의하는 분석을 먼저 만드는 선택보다, 명시적 관계를 먼저 확인하는 선택을 우선한다. 기존 E2E 계획의 선택 일관성, 함수 경계, 비용, authority, audit off 검사와 재컴파일 요구는 그대로 적용한다.

## 규모 판단과 검증 기준

같은 분기에서 AA/BB만 생기는 예제는 두 행이면 충분하다. 반면 여러 독립적인 정의 선택이 조합되면 tuple 수는 커질 수 있다. 입력별 정의 수의 곱은 가능한 정적 tuple 수의 상한이 될 수 있지만, 실제 관계 크기가 항상 그 상한에 도달하는 것은 아니다.

먼저 다음을 측정한다.

- Consumer별 tuple 수와 최대 크기 및 전체 저장량.
- Loop 고정점의 반복 횟수와 관계 생성 시간 및 메모리.
- 기존 모델 대비 후보 수와 hard factor의 범위 및 평가 비용.
- 실제 선택된 계획의 이동량과 실행 결과.

동일 tuple의 집합 중복 제거는 처음부터 수행한다. 그 이상의 압축 엔진은 측정 결과를 보고 결정한다. 임의의 tuple 개수 제한으로 가능한 실행을 버려서는 안 된다.

| 검사 | 통과 기준 |
| --- | --- |
| 상관된 분기 | AA/BB가 보존되고 불가능한 AB/BA가 추가되지 않음 |
| 독립 분기 | 실제 가능한 AB/BA를 누락하지 않음 |
| 후보 선택 | 각 행이 최종 선택한 producer realization을 참조 |
| Loop | 0회·1회·다회, 갱신 사이의 사용과 반복별 map 변화 반영 |
| 비재귀 함수 | 호출별 인자·반환 pairing과 공유 본문 선택 일관성 유지 |
| 실행 | 올바른 수치 결과, 실제 map, 무재배치 계약과 전송 내역 확인 |

관계가 작고 E2E를 통과하면 명시적인 표현을 유지한다. 실제로 큰 관계가 나타나면 그 사례를 근거로 표현을 최적화한다. 아직 측정하지 않은 최악의 조합 수 때문에 처음부터 더 복잡한 분석 구조를 필수로 만들지 않는다.

## 관련 문서와 코드 근거

- [기존 구현 계획과 유지할 E2E 계약](CORRELATED_PLANNING_IMPLEMENTATION_PLAN_2026-10-06_KO.md)
- [경량 분석을 우선 제안했던 설계](CORRELATED_PLANNING_LIGHTWEIGHT_DESIGN_2026-10-06_KO.md)
- [상관된 실행과 계획 공간의 완전성](CORRELATED_EXECUTIONS_PLAN_COMPLETENESS_2026-10-06_KO.md)

[S1]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java:44
[S2]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:1116
[S3]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java:192
[S4]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4660
[S5]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java:1343
[S6]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixMatrixFEDInstruction.java:112
