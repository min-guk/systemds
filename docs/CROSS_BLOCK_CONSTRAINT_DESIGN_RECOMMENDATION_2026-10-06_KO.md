# SystemDS 블록 간 배치 제약의 설계 권고

작성일: 2026년 10월 6일

코드 기준: 앞서 fetch하여 확인한 `origin/main`의 `3d0d683c1bca099f004edf9105f5387369b4abea`. 소스 참조는 해당 커밋의 `/home/mchoi/w1357-loop-entry-main-20261006` 작업 트리를 가리킨다.

**최종 제약은 “선택한 하나의 실행 계획이 실제로 가능한 모든 입력 조합에서 동작한다”로 두는 것을 권한다.** 모든 reaching definition을 검증하는 원칙은 유지하되, 모든 정의의 worker와 range가 같아야 한다는 조건은 해당 물리 후보가 요구할 때 적용한다.

현재 공통 배치 후보와 loop-entry upload 후보는 유지한다. 추가 표현이 필요한 경우에는 실제 입력의 map을 사용하는 후보를 확장하고, 여러 입력의 관계가 필요한 연산에 공동 입력 정보를 연결한다. 전체 `J_v` 구현을 첫 작업으로 잡기보다, 실제 workload의 후보 손실 원인을 확인하여 필요한 표현부터 보완한다.

## 제약을 거는 위치

TW는 블록에서 만든 변수 정의를 다음 사용에 노출하는 transient write이고, TR은 전달된 변수를 읽는 transient read다. Reaching definition은 특정 TR에 도달할 수 있는 정의를 뜻한다. Physical candidate는 실행 위치, 출력 형태, 입력 요구사항과 필요한 이동을 포함하는 물리 실행 대안이다.

| 위치 | 필요한 제약 | 의미 |
|---|---|---|
| TW → TR | 해당 실행에서 전달된 값과 map을 보존한다 | 이 연결 자체에서 숨겨진 upload나 download를 만들지 않는다 |
| 연산의 physical candidate | 선택한 실행 방식이 실제로 가능한 모든 입력 조합을 처리한다 | 연산별로 필요한 shape, 분할, 정렬, 출력 조건을 검사한다 |
| 데이터 이동 | 이동의 실행 가능성, privacy와 비용을 검증한다 | 필요한 upload, download, 재배치를 계획에 명시한다 |
| Loop | 초기값이 반복에 필요한 조건을 만족하고 body가 그 조건을 보존한다 | 첫 iteration, backedge와 종료 후 사용을 모두 검증한다 |

TR/TW의 허용 상태는 기존대로 `<CP,LOUT>` 또는 `<FED,FOUT>`이다. CP는 coordinator의 local 실행, FED는 federated 실행이며, LOUT과 FOUT은 각각 local 결과와 federated 결과다. 실제 입력의 map을 사용한다는 이유로 이 상태 규칙을 완화하거나, 실행 중 임의로 CP와 FED를 전환하도록 허용하지 않는다. Privacy, recompile 경로의 CP/FOUT 제한과 runtime fallback 금지도 유지한다. [프로젝트 실행 계약][S1]

**모든 source를 검사하는 것과 모든 source에 동일한 concrete map을 요구하는 것은 서로 다른 조건이다.** Map은 worker, partition range, 분할 형태 등의 배치 정보를 뜻한다. Map이 같다는 것도 데이터 값이나 원격 변수 ID가 같다는 뜻은 아니다.

## TW와 TR에서 보존해야 하는 것

두 branch가 서로 다른 worker 집합에서 X를 만든다고 하자.

```text
true 실행:  TW(X_A, map A) → TR(X, map A)
false 실행: TW(X_B, map B) → TR(X, map B)
```

각 실행에서는 TW가 전달한 값을 TR이 그대로 받는다. 이 의미만으로 `A = B`가 따라오지는 않는다. A와 B 사이에서 데이터를 이동하지 않아도, 실제 도달한 값의 map을 전달할 수 있기 때문이다.

현재 코드의 CFG 연결은 이러한 변수 바인딩에 암묵적인 이동을 허용하지 않는다. 다만 현재 planner는 다중 source의 상태 일치와 공통 물리 reader 지원을 추가로 요구한다. [CFG 상태 제약][S2], [공통 reader 생성][S3]

따라서 “runtime에서 실제 map을 전달할 수 있다”와 “현재 planner가 서로 다른 map의 합류를 표현할 수 있다”는 구분해야 한다. 후자는 후보 표현과 후속 검증까지 확장해야 얻을 수 있는 기능이다.

## 단일 입력은 각각의 도달 정의를 검사한다

```text
if (c) {
    X = X_A;   // worker 집합 A에 ROW 분할
} else {
    X = X_B;   // worker 집합 B에 ROW 분할
}
Z = X + 1;
```

두 X의 크기와 연산에 필요한 타입 조건이 맞고, 선택한 결과 형태가 FOUT이라고 하자. 이때 검사할 것은 A에서 온 X에도, B에서 온 X에도 동일한 FED matrix-scalar 덧셈을 적용할 수 있는가이다.

실제 `BinaryMatrixScalarFEDInstruction`은 입력 `MatrixObject`의 map을 사용해 실행한다. 입력 map이 없으면 CP fallback으로 보정하지 않고 오류를 내며, FOUT 결과에는 입력 map의 배치와 새로운 결과 ID를 사용한다. [실제 입력 map 사용][S4], [결과 map 생성][S5]

이 경우에는 서로 pairing할 행렬 입력이 하나뿐이므로 `J_v`의 다중 입력 상관관계가 필요하지 않다. 필요한 확장은 가능한 source map들을 보존하고, 선택한 연산과 그 이후의 consumer가 각각을 처리할 수 있도록 표현하는 것이다.

`Z`도 실행 경로에 따라 A 또는 B에 놓이므로, `X + 1`의 실행 가능성만 확인하고 분석을 끝내서는 안 된다. 결과 map의 가능성을 다음 연산까지 전달해야 한다.

## 여러 입력에는 함께 도달하는 조합을 검사한다

```text
if (c) {
    X = X_A;   // worker 집합 A에 ROW 분할
    Y = Y_A;   // X_A와 정렬된 배치
} else {
    X = X_B;   // worker 집합 B에 ROW 분할
    Y = Y_B;   // X_B와 정렬된 배치
}
Z = X + Y;
```

네 행렬의 크기는 같고, A와 B는 서로 다른 worker 집합이며, privacy가 해당 FED 연산을 허용한다고 하자. 분할별 FED 덧셈 후보가 요구하는 조건은 다음과 같다.

| 실행 | 실제 입력 | 검사할 조건 |
|---|---|---|
| true | X_A, Y_A | 두 입력이 해당 덧셈에 맞게 정렬되어 있다 |
| false | X_B, Y_B | 두 입력이 해당 덧셈에 맞게 정렬되어 있다 |

**A와 B가 서로 같을 필요는 없다.** 두 실행 모두 실제 입력이 정렬되어 있으므로, 입력 map을 사용하는 하나의 FED 덧셈 전략으로 처리할 수 있다. 현재 matrix-matrix FED instruction에도 실제 입력 map의 정렬을 검사하고 실행하는 경로가 있다. [FED binary 정렬 경로][S6]

이때 공동 입력 정보는 다음과 같다.

```text
J_v = {(X_A, Y_A), (X_B, Y_B)}

이 조건문에서는 발생하지 않는 조합:
    (X_A, Y_B), (X_B, Y_A)
```

`J_v`는 한 연산의 입력들이 어느 조합으로 함께 올 수 있는지 알려준다. 연산 노드를 두 배로 복제하는 것과 같지 않다. 하나의 연산 후보가 위 두 조합을 모두 지원하는지 확인하면 된다.

반대로 X와 Y를 서로 독립적인 조건문에서 선택하면 교차 조합도 실제로 가능할 수 있다. 이때는 네 조합 모두 검사해야 한다. 정렬되지 않는 조합이 있으면 정렬된 입력만 처리하는 후보는 그 프로그램 전체를 지원하지 못한다. 이동을 포함하는 다른 후보는 이동의 가능성, privacy와 비용을 함께 검증한 뒤 비교할 수 있다.

Runtime에 정렬 실패 시 `broadcastSliced`를 실행하는 코드가 있다는 이유로 이를 무료 보정처럼 사용해서는 안 된다. 선택한 전략에 그 통신이 포함되는지 planner가 사전에 모델링해야 한다. [정렬되지 않은 입력의 통신 경로][S7]

**선택한 후보가 모든 가능한 조합을 지원해야 한다.** 각 조합에 맞는 서로 다른 후보가 후보 목록 어딘가에 존재한다는 것만으로는 하나의 정적 실행 계획을 정당화할 수 없다.

## Loop에서는 반복 상태의 조건을 보존한다

최신 main이 추가한 다음 계획은 유지할 가치가 있다.

```text
local 초기 계산
    │
    └─ 명시적 upload ─→ TW(p, 배치 S)
                            │
                            ▼
                        TR(p, 배치 S)
                            │
                           body
                            │
                        TW(p, 배치 S)
                            └──── backedge ─→ TR
```

이 후보는 loop-carried 변수 p에 대해 공통 배치 S를 유지한다. 필요한 조건은 다음 세 가지다.

1. **진입 조건:** 초기값을 S에 놓는 이동이 허용되고 실행 가능하며, 비용에 포함된다.
2. **반복 조건:** Body의 모든 가능한 경로가 다음 iteration에서 사용할 수 있는 배치 S의 p를 만든다.
3. **종료 조건:** Loop가 0회 실행되거나 여러 번 실행된 뒤에도 후속 연산이 도달한 값을 처리한다.

초기 producer가 top-level에서 한 번 실행되고 body가 T번 실행된다면 비용은 다음 형태다.

```text
초기 계산 비용 + 진입 upload 비용 + T × 반복 비용 + 필요한 종료 후 이동 비용
```

최신 main의 upload는 허용된 초기 producer의 명시적 materialization 후보다. TR/TW에 CP/FOUT을 허용하는 변경이 아니다. 사용 가능한 anchor, 원래의 local 계산 가능성과 runtime에서 만들 수 있는 배치 등을 확인하며, recompile occurrence는 이 확장에서 제외한다. [Loop 진입 후보 생성][S8]

“한 번”은 해당 초기 producer의 실행 한 번을 기준으로 한다. 초기화가 바깥 loop 안에 있으면 바깥 반복마다 실행될 수 있고, 초기화 시점에 upload하는 후보는 안쪽 loop가 0회 돌아도 upload를 수행할 수 있다. [최신 loop-entry 분석](ORIGIN_MAIN_LOOP_ENTRY_REVIEW_2026-10-06_KO.md)

**S를 고정하는 것은 이 후보가 선택한 계약이다.** 일반적으로 모든 loop가 하나의 concrete map을 유지해야 한다는 뜻은 아니다. 다른 map을 허용하는 후보를 추가하려면, 허용한 모든 초기 map에 대해 body 실행과 다음 iteration의 조건이 유지된다는 것을 증명해야 한다. Body 내부의 모든 중간값까지 S에 고정한다는 뜻도 아니다.

정적인 정의 tuple만 저장해도 이 증명이 자동으로 생기지는 않는다. 같은 body 정의가 여러 iteration에서 다른 shape나 range를 만들 수 있으므로, 반복마다 필요한 배치 관계를 보존하는지 별도로 확인해야 한다.

## 현재 구현과 확장할 지점

현재 main의 exact transient replay는 모든 source가 공통 layout을 지원해야 reader 대안을 만든다. Native replay에는 range를 정확히 고정하는 경로와 worker endpoints를 공통으로 유지하는 경로가 있으며, 후자는 해당 continuity 증명을 사용한다. 현재 구현이 언제나 모든 range를 고정한다는 뜻은 아니다. [Exact reader 지원][S3], [Native reader의 공통 witness][S9]

`CandidateEmissionRealization`도 하나의 realization에 서로 다른 native worker pool을 함께 담지 못하게 검사한다. 따라서 `J_v` tuple만 추가해도 위 A/B 예시가 곧바로 지원되는 것은 아니다. [Realization 계약][S10]

| 대상 | 유지할 내용 | 확장이 필요한 경우의 작업 |
|---|---|---|
| Reader와 물리 후보 | 현재 공통 배치 후보 | 실제 도달한 값의 map을 사용하는 후보와 그 출처 표현 |
| 단일 입력 연산 | 모든 source에 대한 capability와 privacy 검사 | 각 가능한 map에서 실행되는지 검사하고 결과 map 전파 |
| 다중 입력 연산 | 연산별 shape와 정렬 등 요구사항 | 실제 공동 입력 조합별로 선택한 전략의 지원 확인 |
| Loop | 기존 공통 배치와 유료 entry 변환 후보 | 다른 map을 허용할 때 진입과 backedge의 조건 보존 증명 |
| 비용과 emission | 계획된 이동과 실행 빈도 | 추가 표현에서도 같은 이동·출력 계약을 비용과 runtime 명령에 반영 |
| 최종 계획 검증 | 전역 합법성 검증 | 실제 선택된 writer와 consumer가 모든 가능한 실행을 지원하는지 확인 |

공통 map 검사만 삭제하면 현재 후보 표현이 보장하던 사실을 잃는다. 입력 map을 사용하는 후보를 표현하고, 그 입력 요구사항·결과 map·비용·emission·최종 검증을 함께 맞추어야 한다. 기존의 검증을 약화하는 방식으로 처리하지 않는다.

## 구현 우선순위와 완료 조건

1. **실제 후보 손실을 먼저 고정한다.** Workload와 최종 HOP, 선택 가능한 writer와 reader를 확인하고, 공통 map 표현 부족인지, 입력 pairing 손실인지, 이동 후보나 비용의 문제인지 구분한다.
2. **기존 후보를 보존한다.** 현재 공통 배치 후보와 loop-entry upload 후보를 계속 비용으로 비교한다.
3. **필요한 map 표현부터 확장한다.** 단일 입력의 실제 map 사용을 먼저 검증하고, 결과의 배치를 다음 consumer까지 전달한다.
4. **공동 입력 증명이 필요한 consumer에 관계를 연결한다.** 구조적으로 입증한 branch pairing 등을 사용한다. 서로 함께 올 수 없다는 근거가 없는 조합은 임의로 제거하지 않는다. 전체 프로그램의 정확한 경로와 tuple을 처음부터 모두 열거할 필요는 없다.
5. **선택된 계획을 E2E로 검증한다.** DML 파싱부터 후보 생성, 선택, emission과 runtime까지 동일한 입력·출력·이동 계약이 유지되어야 한다.

검증의 핵심은 양쪽 branch 실행, 가능한 교차 조합의 누락 방지, 불가능한 교차 조합으로 인한 과도한 탈락 방지, loop 0회·1회·여러 회 실행, 실제 선택된 supplier의 일관성과 계획된 이동 횟수다. Runtime이 실행 중 임의로 고쳐서 성공한 결과를 planner의 정확성으로 인정하지 않는다.

현재 실험에서 map 표현이나 공동 입력 관계를 확장했을 때 복구되는 물리 계획 수와 성능 이득은 아직 확인되지 않았다. 따라서 첫 작업은 전체 `J_v` 도입보다 구체적인 후보 손실의 진단이다. 실험상의 손실이 아직 확인되지 않았다는 사실도 공통 저장장소 제약이 runtime의 보편적인 필수조건이라는 근거는 아니다. [Workload별 정보 손실과 개선 대상](JOINT_INPUT_WORKLOAD_IMPROVEMENT_REVIEW_2026-10-06_KO.md)

## 관련 문서

- [현재 구현의 블록 간 배치 제약](CURRENT_CROSS_BLOCK_PLANNING_CONSTRAINTS_2026-10-06_KO.md)
- [origin main의 loop 진입 변환 분석](ORIGIN_MAIN_LOOP_ENTRY_REVIEW_2026-10-06_KO.md)
- [공동 입력 정보 손실과 현재 실험의 개선 대상](JOINT_INPUT_WORKLOAD_IMPROVEMENT_REVIEW_2026-10-06_KO.md)

[S1]: /home/mchoi/w1357-paper-aligned-refactor/AGENTS.md:1
[S2]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:6939
[S3]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4742
[S4]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixScalarFEDInstruction.java:58
[S5]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixScalarFEDInstruction.java:107
[S6]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixMatrixFEDInstruction.java:112
[S7]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixMatrixFEDInstruction.java:120
[S8]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:8212
[S9]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4858
[S10]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java:1343
