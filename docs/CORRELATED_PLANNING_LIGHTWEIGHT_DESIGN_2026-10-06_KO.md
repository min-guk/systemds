# SystemDS 상관된 입력 계획을 위한 경량 구현 설계

작성일: 2026년 10월 6일

후속 결정: 우선 [J_v 관계 노드를 명시적으로 구현하고 규모를 측정](JOINT_INPUT_RELATION_NODE_DESIGN_2026-10-06_KO.md)한다. 이 문서의 관계 질의 방식은 실제 부담이 확인된 부분의 최적화 대안으로 남긴다.

## 목적과 권고

현재 놓치는 correlated plan을 지원하면서 구현 부담을 줄이려면, 전체 공동 입력 튜플 집합 J_v를 구축하는 대신 각 연산이 필요로 하는 배치 관계만 증명하는 방식을 권한다. 예를 들어 FED 덧셈에는 가능한 모든 실행 이력보다, consumer에 도달할 때 두 입력이 항상 aligned라는 사실이 중요하다.

파싱부터 runtime까지 계획의 계약을 유지해야 한다는 목표는 그대로다. 구현을 줄일 대상은 일반적인 경로 분석 엔진, 모든 변수 조합의 열거, runtime 분기 이력 추적이다. 기존 CFG, 구체 map 검사, 후보 지원 관계, 선택 receipt, 재컴파일 identity와 runtime alignment 검사를 재사용한다.

이 문서는 `/home/mchoi/w1357-paper-aligned-refactor`의 코드 분석에 기반한 설계 제안이다. 제안한 관계 분석과 배치 표현을 구현하거나 새 E2E 실행으로 검증한 상태는 아니다. 전체 계약과 연결 지점은 [E2E 설계 문서](CORRELATED_PLANNING_E2E_DESIGN_2026-10-06_KO.md)에 정리되어 있으며, 여기서는 그 계약을 더 작은 구현으로 충족하는 방법을 다룬다.

## 유지할 목표

- 같은 분기가 선택하는 A/A와 B/B에서 합법적인 무재배치 계획을 보존한다.
- 독립적인 분기에서 가능한 A/B와 B/A를 잘못 제외하지 않는다.
- loop와 함수 호출 및 재컴파일 후에도 선택한 계획의 계약을 유지한다.
- privacy, TR/TW placement, 명시적 이동의 authority와 runtime fallback 금지를 유지한다.
- 기존 후보를 편의상 닫는 방식으로 구현 부담을 줄이지 않는다.

여기서 A와 B는 서로 다른 worker pool이다. 각 pool 내부의 X와 Y가 필요한 shape와 ROW 분할 정렬을 만족하고, 해당 연산 및 privacy가 FED 실행을 허용한다고 가정한다.

## 입력 튜플 대신 공통 배치 관계 보존

다음은 관계를 설명하는 의사 코드다.

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

경량 분석이 보존할 핵심은 다음과 같다.

```text
true 경로:  Aligned(X_A, Y_A)
false 경로: Aligned(X_B, Y_B)

합류 후:    Aligned(X, Y)
```

두 경로가 사용하는 pool은 다르지만 같은 배치 관계가 성립한다. 따라서 합류 후에는 X와 Y가 함께 실행될 때 항상 aligned라는 사실을 유지할 수 있다. 전체 프로그램의 가능한 경로, 모든 변수 정의 조합, 반복별 분기 이력을 저장할 필요는 없다.

이 규칙은 같은 if에서 왔다는 사실만으로 alignment를 승인하지 않는다. 각 경로에서 실제 producer의 배치를 검사해야 한다. 또한 worker 주소의 동일성, ROW 정렬, COL 정렬은 연산이 요구하는 조건에 맞게 구별해야 한다.

반대로 X와 Y가 독립적인 분기에서 결정되면 두 분기의 같은 순번끼리만 대응시키면 안 된다. 실제 A/B와 B/A가 가능하므로, 이 예제의 무재배치 aligned 계획은 같은 증명을 얻을 수 없다.

## 최소한으로 추가할 두 개념

| 개념 | 필요한 의미 |
| --- | --- |
| 실제 공급된 값의 map을 따르는 배치 표현 | TRead가 모든 실행에서 고정 pool을 요구하지 않고 해당 값의 map을 유지 |
| 연산에 필요한 관계 증명 | 선택된 producer들에 대해 모든 해당 실행 경로에서 필요한 alignment 등이 성립 |

분기별 값 출처는 기존 CFG의 predecessor 정보를 유지하는 작은 구조로 표현할 수 있다. 설명용으로는 구체 source, join의 predecessor별 source, 검증된 map 전달 관계 정도면 된다. 새로운 DML 문법이나 일반적인 조건식 해석기를 만드는 것이 출발점일 필요는 없다.

현재 `PlacementProgramFacts`는 CFG predecessor를 만들지만 최종 reaching information은 변수별 정의 집합으로 계산한다. 이 과정에서 consumer가 필요한 관계를 복원할 수 있도록 predecessor와 값 출처의 연결을 남기는 방향이다. [PlacementProgramFacts.java][S1]

같은 실행 시점의 join을 공유하는 두 입력은 대응하는 predecessor 쌍에 대해 관계를 검사할 수 있다. 그러나 서로 다른 호출이나 반복의 값을 단순히 같은 정적 join 식별자만으로 묶어서는 안 된다.

관계 질의는 consumer가 요구할 때 계산하고 결과를 재사용한다. 질의와 cache는 분석 revision, 값 identity 및 후보 의존성을 구별해야 한다. 다른 producer가 선택되었는데 이전 증명이 재사용되어서는 안 된다.

## 기존 realization 표현에서 반드시 바뀌는 부분

현재 `CandidateEmissionRealization`은 하나의 realization에 서로 다른 native worker pool을 섞는 것을 명시적으로 금지한다. 따라서 reader 생성부의 검사를 지우는 것만으로는 목표를 달성할 수 없다. [PlacementAnalysis.java][S2]

기존 support clause에 A와 B를 각각 넣는 방법도 충분하지 않다. 기존 clause는 하나의 물리 후보를 지지하는 대안적인 선택 증명이다. 실행 중 if의 양쪽 경로를 나타내는 구조와 의미가 다르다.

따라서 실제 source에 따라 map이 달라질 수 있음을 표현하는 typed 배치 확장은 필요하다. 다만 이를 일반적인 symbolic map 언어로 만들기보다, 값의 map 전달과 필요한 관계를 나타내는 범위로 제한할 수 있다. 증명 없는 문자열 표식이나 dynamic range 옵션으로 서로 다른 endpoints를 같은 pool처럼 취급해서는 안 된다.

기존 concrete map 검사와 coarse FED/FOUT/ROW 계약은 유지한다. 관계적 증명은 고정 pool equality보다 정확한 실행 조건을 표현하기 위한 추가 수단이다.

## 기존 planner와 지원 관계의 재사용

예제의 FED consumer 후보가 요구하는 조건은 다음처럼 표현할 수 있다.

```text
선택된 X_A와 Y_A가 aligned
AND
선택된 X_B와 Y_B가 aligned
```

이는 runtime이 두 branch를 동시에 실행한다는 뜻이 아니다. 어느 branch를 실행해도 같은 선택 계획이 유효해야 한다는 뜻이다. 분기 결과는 프로그램 실행이 결정하며 planner가 유리한 쪽으로 선택하지 않는다.

현재 선택 receipt와 검증에는 정확한 producer realization을 참조하는 구조가 있다. 새로운 관계 조건을 이 구조에 연결하면 planner 전체를 새로 만들 필요는 없다. [CandidateSelections.java][S3]

다만 각 branch에 호환되는 후보 하나가 존재한다는 사실만 확인하면 안 된다. 최종적으로 선택한 후보들이 관계를 만족해야 한다. 같은 static producer가 여러 증명을 지지한다면 모든 증명에서 같은 선택을 사용해야 한다.

DP의 부분 선택, 상태 병합 및 pruning에서도 이후 실행 가능성에 영향을 주는 관계 조건을 유지해야 한다. Exact에도 같은 의미의 제약을 연결해야 한다. 따라서 기존 solver를 교체하지 않는 방향은 가능하지만, solver와 선택 검증을 전혀 변경하지 않는다고 전제해서는 안 된다.

기존 `PlacementRelationClosure`의 공통 witness 기반 reader 경로는 유지하면서 관계적 reader를 추가하는 방향을 검토한다. 관계 분석이 증명하지 못한 경우를 runtime 미지원이라고 단정하거나 기존 합법 후보를 삭제해서는 안 된다. [PlacementRelationClosure.java][S4]

## Loop와 함수의 작은 관계 요약

Loop는 실행 이력을 열거하는 대신 특정 프로그램 지점의 관계적 불변식으로 처리한다. 예를 들어 필요한 사실은 consumer에 도달할 때 X와 Y가 항상 aligned라는 것이다.

검증할 내용은 다음과 같다.

1. entry에서 관계가 성립한다.
2. body가 그 관계를 보존한다.
3. 모든 backedge와 0회 반복 경로가 반영된다.

이 조건을 증명하면 A/A에서 B/B로 바뀌었다가 다시 A/A로 돌아오는 이력을 저장할 필요가 없다. 필요한 관계만 유한한 분석 상태로 추적할 수 있다.

X만 갱신한 중간 지점에서도 관계가 성립하는지는 별도로 계산해야 한다. 또한 이미 방문한 노드 쌍이라는 이유로 관계를 참으로 처리하면 순환 자기 증명이 된다. entry 근거와 body의 보존 증명이 있어야 한다.

함수도 필요한 입력 관계와 출력 map이 어느 입력을 따르는지를 요약하고 호출별로 연결할 수 있다. 공유 본문이라는 이유로 서로 다른 호출의 source를 섞어서는 안 된다. 관계 종류와 transfer 규칙의 구체 구현은 별도 설계가 필요하며, 모든 loop나 함수를 자동으로 정밀하게 처리한다고 전제하지 않는다.

## Runtime과 재컴파일의 최소 계약

Runtime에 전달할 정보는 분기 이력 전체보다 선택한 연산의 실행 계약이다.

```text
입력 0과 입력 1이 aligned여야 한다.
선택한 실행 경로는 추가 재배치를 허용하지 않는다.
출력 map은 승인된 입력 map 계승 규칙을 따른다.
```

기존 FED binary kernel은 실제 입력 map의 alignment를 검사한다. 이를 재사용하되, planner가 무재배치 실행을 선택했다면 불일치 시 기존 broadcast 경로로 넘어가지 못하도록 해야 한다. [BinaryMatrixMatrixFEDInstruction.java][S5]

이 방식에는 어떤 if 경로를 몇 번 지났는지 기록하는 일반적인 runtime 추적기가 필요하지 않다. 실제 입력에서 연산의 계약을 확인한다. 다만 선택된 source와 계획의 identity를 유지하는 기존 authority는 계속 필요하다.

재컴파일에는 관계 계약과 출력 map 계승 규칙을 기존 origin 및 authority 복원 경로로 전달한다. 첫 실행에서 관찰한 A를 이후 실행 전체의 고정 pool로 저장하면 안 된다. [Recompiler.java][S6]

Runtime 검사는 컴파일 단계의 전체 경로 증명을 대체하지 않는다. 정상적인 실행에서 계약 위반이 발생하지 않음을 compiler가 증명하고, runtime 검사는 구현 오류나 계약 불일치를 검출하는 역할을 맡는다.

## 줄일 수 있는 구현 범위

| 항목 | 경량 구현 방향 |
| --- | --- |
| 모든 J_v 튜플 열거 | consumer가 요구하는 배치 관계 질의로 대체 |
| 일반 symbolic execution과 SMT | 이번 구현에 도입하지 않음 |
| 모든 변수 쌍의 관계 분석 | 필요한 관계부터 계산하고 결과 재사용 |
| Runtime 분기 이력 추적 | 실제 입력 map의 계약 검사로 대체 |
| 새 planner와 solver | 기존 후보와 지원 관계 및 선택 구조 확장 |
| 새 분기 확률 비용 모델 | 기존 비용 체계에 실제 배치와 이동 비용 연결 |

위의 축소는 실행 가능성 검증을 생략한다는 뜻이 아니다. 비용 계산에서도 실제로 발생하지 않는 이동을 추가하거나 필요한 이동을 누락해서는 안 된다. pool별 비용 차이가 있다면 기존 비용 집계 방식 안에서 반영하고 추정의 한계를 명시한다.

필요한 관계만 분석하면 전체 조합의 명시적 저장을 줄일 수 있지만, 모든 경우의 planning 비용이 선형이 된다는 보장은 없다. 후보 선택과 관계의 상호작용에 따라 비용이 커질 수 있으므로 시간·메모리·관계 수를 측정해야 한다.

## 구현 순서와 E2E 수용 기준

첫 단위는 동일 분기의 A/A 또는 B/B와 하나의 FED binary consumer다. 이 예제를 독립 분기의 A/B 대조군과 함께 DP E2E로 연결한다. 이후 같은 관계 표현으로 loop와 함수 및 재컴파일까지 확장한다.

| 검증 대상 | 수용 기준 |
| --- | --- |
| 상관된 분기 | A/A와 B/B 모두 같은 정적 FED consumer로 무재배치 실행 |
| 독립 분기 | 실제 가능한 A/B와 B/A를 누락하거나 잘못 aligned로 승인하지 않음 |
| Producer 선택 변경 | 다른 realization을 선택하면 관계 조건을 다시 검증 |
| Loop | entry와 body 보존, 모든 backedge 및 0회 반복을 반영 |
| 함수 호출 | 호출별 입력 및 반환 관계 유지 |
| 재컴파일 | 원래 계약을 보존하고 첫 관찰 map에 고정되지 않음 |
| Runtime | 계획하지 않은 broadcast나 relocation으로 성공을 만들지 않음 |

분기 조건이 compile-time 상수로 제거되지 않았는지 확인해야 한다. 결과 값뿐 아니라 후보 존재, 선택 receipt, 생성 명령, 실제 map과 전송 내역도 확인한다. 후보가 존재하는지와 비용 기반 선택에서 실제로 선택되는지는 별개의 검증이다.

실행 실험은 저장소의 privacy 테스트 정책과 `run_LAN_docker.sh` 경로를 따른다. 첫 branch 테스트 통과만으로 loop와 함수 및 재컴파일까지 완료했다고 판단하지 않는다.

## 보장 범위와 한계

이 설계는 구조적으로 상관된 A/A와 B/B 계획, 필요한 관계가 증명되는 loop, 호출 및 재컴파일에서 계획 계약을 유지하는 목표를 겨냥한다. 일반 DML의 모든 의미적 경로를 정확히 판정하거나 모든 합법 계획을 빠짐없이 찾는다는 보장은 아니다.

서로 다른 조건식의 논리적 동치까지 발견해야 하는 경우에는 추가 분석이 필요할 수 있다. 또한 여러 입력을 함께 봐야만 판정되는 조건을 임의로 pairwise 검사로 대체해서는 안 된다. 연산의 실행 가능성이 해당 관계들의 conjunction으로 분해되는 경우에만 그 방식이 충분하다.

분석이 관계를 증명하지 못한 것과 runtime이 그 조합을 지원하지 않는 것은 다르다. 미증명 사례는 구분하여 기록하고, 기존 후보를 보존하면서 필요한 관계 표현을 확장해야 한다.

권고하는 최소 구성은 값의 map을 따르는 reader, 필요한 배치 관계만 증명하는 분석, 기존 선택 및 실행 계약의 재사용이다. 일반 경로 분석 엔진보다 작은 구현으로 시작하되, 선택과 실행 사이의 증명 연결은 생략하지 않는다.

## 코드 근거와 관련 문서

행 번호는 분석 시점의 작업 트리 기준이다.

| 참조 | 코드 | 확인한 역할 |
| --- | --- | --- |
| S1 | [PlacementProgramFacts.java][S1] | CFG predecessor 및 변수별 정의 분석 |
| S2 | [PlacementAnalysis.java][S2] | realization 내부의 native pool 일관성 |
| S3 | [CandidateSelections.java][S3] | 선택 receipt와 producer support 검증 |
| S4 | [PlacementRelationClosure.java][S4] | 공통 witness 기반 transient reader 생성 |
| S5 | [BinaryMatrixMatrixFEDInstruction.java][S5] | 현재 입력 alignment와 실행 경로 |
| S6 | [Recompiler.java][S6] | 재컴파일 상태와 authority 복원 경로 |

- [상관된 실행과 계획 공간의 완전성](CORRELATED_EXECUTIONS_PLAN_COMPLETENESS_2026-10-06_KO.md)
- [파싱부터 실행까지 E2E 설계](CORRELATED_PLANNING_E2E_DESIGN_2026-10-06_KO.md)
- [Joint Input Dependencies 도입 판단](JOINT_INPUT_DEPENDENCIES_ADOPTION_REVIEW_2026-10-06_KO.md)

[S1]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java:161
[S2]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java:1343
[S3]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java:1447
[S4]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4660
[S5]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixMatrixFEDInstruction.java:112
[S6]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/recompile/Recompiler.java:507
