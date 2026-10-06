# 함수 binding과 if-else 합류의 배치 제약 및 명시적 변환 설계

작성일: 2026-10-06. 대상 질문: “함수 input/output binding도 TW/TR처럼 동일한 `<CP,LOUT>` 또는 `<FED,FOUT>`이어야 하는가? 아니면 if-else에 이동 edge를 넣어야 하는가?”

**함수 binding의 값·배치 보존 규칙은 유지할 수 있다. 다만 배치를 바꿔 전달하는 합법적인 계획까지 보존하려면, binding 전후 또는 분기 경로에 명시적인 변환 후보가 있어야 한다.** 함수 binding, 변환의 위치, 다중 입력의 상관관계, 이동 비용은 서로 연결되지만 각각 다른 문제다.

이 문서는 현재 구현과 제안하는 확장을 구분한다. 코드 근거는 `/home/mchoi/w1357-paper-aligned-refactor`의 HEAD `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592`에 미커밋 변경이 포함된 작업 트리다. 아래 함수 binding 동작을 `origin/main`의 커밋 내용으로 간주해서는 안 된다. 이 문서 작성에서 실행 코드는 변경하지 않았다.

## 1. 무엇을 각각 해결해야 하는가

| 구분 | 지켜야 할 조건 또는 해결할 문제 | 필요한 처리 |
|---|---|---|
| 함수 input/output binding | 전달받은 행렬의 값과 배치를 binding 자체가 바꾸지 않는다. | 실제 전달값과 경계 사이의 보존 제약을 유지한다. |
| 변환 후보 | FOUT 원본을 로컬 값으로 만들어 전달하는 계획이 사라질 수 있다. | 명시적 GET/local materialization 등을 별도 후보로 표현한다. |
| if-else 합류 | 공통 TR이 요구하는 배치를 각 경로에서 어떻게 만족시킬지 결정해야 한다. | 필요한 경로에 변환을 배치하고, 변환 결과를 새 공급자로 연결한다. |
| 공동 입력 `J_v` | 여러 변수의 정의가 실제로 함께 도달할 수 있는지 구분해야 한다. | 가능한 입력 tuple만으로 연산의 공동 호환성을 검사한다. |
| 비용 | 같은 이동도 실행 경로, 호출 횟수, 값의 수명과 캐시에 따라 비용이 달라진다. | 선택된 이동의 실행 조건과 실제 값의 공유 관계를 비용 모델에 연결한다. |

따라서 “binding equality를 유지할 것인가”와 “분기 이동을 추가할 것인가”는 양자택일이 아니다. **Binding은 배치를 보존하고, 별도 변환이 배치를 바꾸도록 구성할 수 있다.**

## 2. 함수 binding에서 ‘동일하다’의 정확한 의미

행렬 인자의 runtime binding은 caller에서 가져온 `Data`를 formal 변수에 넣는다. 반환도 함수가 만든 `Data`를 caller 결과 변수에 연결한다. 이 동작 자체에 GET이나 upload가 포함되는 것은 아니다. 스칼라 인자의 타입 변환은 별도 처리이며, 여기서는 행렬의 물리 배치를 논의한다. [입력 binding 코드][S1], [반환 binding 코드][S2]

따라서 입력과 반환 각각에 다음 계약을 적용한다.

```text
실제로 전달할 값 → input binding → formal 변수
실제로 반환할 값 → output binding → caller 결과 변수
```

각 연결은 같은 값과 배치를 보존한다. 여기서 다음 두 equality는 구분해야 한다.

- **생산자와 경계 사이:** 생산자가 내놓은 값의 배치를 보존한다. 생산 연산의 실행 위치까지 같을 필요는 없다.
- **경계와 TW/TR 형태의 전달 노드 사이:** canonical 상태인 `<CP,LOUT>` 또는 `<FED,FOUT>` 및 해당 값의 물리 배치를 일관되게 유지한다.

예를 들어 생산 연산이 `<FED,LOUT>`이면 이미 로컬 결과를 내놓는다. 이를 `<CP,LOUT>` 경계에 전달하는 것은 배치 변환이 아니다. 허용된 명시적 upload를 갖는 일반 생산자의 `<CP,FOUT>` 결과도 `<FED,FOUT>` 경계로 전달할 수 있다. 단, 후자의 예시는 **그 생산자와 실행 구간에서 upload가 합법인 경우에 한정**된다. TW/TR의 `<CP,FOUT>` 금지 및 재컴파일 구간의 기존 제한을 완화하지 않는다.

현재 입력 그래프도 actual producer → input boundary에는 `SAME_VALUE_PLACEMENT`, input boundary → formal에는 `SAME_PLACEMENT`를 사용한다. 반환 authority → output boundary 역시 `SAME_VALUE_PLACEMENT`다. [입력 제약 생성][S3], [반환 제약 생성][S4]

`SAME_PLACEMENT`는 `PlacementState` 전체 equality이고, `SAME_VALUE_PLACEMENT`는 output과 FOUT일 때의 FType을 비교한다. **이 상태 비교만으로 worker/range까지 같은 물리 map임이 증명되지는 않는다.** 실제 map과 연산의 호환성 검증은 별도로 유지해야 한다. [상태 비교 구현][S5]

또한 이 보존 원칙은 함수 입력과 함수 출력의 배치가 서로 같아야 한다는 뜻이 아니다. 함수는 FOUT 입력으로 계산하여 LOUT 결과를 반환할 수 있다. 서로 다른 인자나 호출의 배치를 무조건 같게 묶는 원칙도 아니다. 다만 하나의 고정된 함수 몸체 계획을 공유한다면, 그 계획이 지원하는 입력 배치에 따른 추가 제약은 별도로 검토해야 한다.

## 3. 계획이 사라지는 지점은 binding 앞뒤의 변환 표현이다

다음 두 계획은 모두 binding 자체의 보존 규칙을 지킨다.

```text
계획 1: 원본 FOUT 값 ─────────────────→ binding(FOUT) → formal(FOUT)
계획 2: 원본 FOUT 값 → 명시적 GET → 별도 LOUT 값 → binding(LOUT) → formal(LOUT)
```

계획 2에서 binding의 입력은 변환된 LOUT 값이다. 원본 FOUT 값과 LOUT binding을 변환 없이 직접 연결한 것이 아니다. 반환도 필요하면 함수 내부의 반환 직전 또는 caller의 반환 직후에 명시적인 변환을 둘 수 있다.

따라서 기존 함수 경계의 변환 후보를 없애고 alias binding만 남겼다면, 동일한 변환을 다른 위치에서 표현할 수 있는지 확인해야 한다. “안쪽 소비자가 필요할 때 GET을 한다”는 사실만으로 동등한 계획 공간이 보장되지는 않는다. 소비자용 로컬 operand를 만드는 것과, 이후 변수 전달에 사용할 별도 로컬 정의를 만드는 것은 역할이 다르다.

별도 [함수 binding A/B 보고서][E1]의 L2SVM W1에서는 기존 local 정규화/local write 조합이 제외되었다. 변경된 선택 계획은 CP로 만든 새 Y를 다시 upload하여 합류 제약을 만족시켰다. 이는 compile-only planning에서 확인된 표현력 차이이며, 분산 runtime 성능이나 수치 정확성 검증 결과는 아니다.

## 4. L2SVM에는 두 가지 변환 위치가 있다

L2SVM은 label 범위가 `-1/+1`이 아닐 때만 Y를 정규화한다. 조건이 거짓이면 원래 Y가 그대로 다음 연산으로 전달된다. 원본 DML에는 명시적인 else가 없다. [L2SVM 조건문][S6]

```text
입력 Y_old(FOUT)
  ├─ true:  정규화 계산 → Y_new(LOUT)
  └─ false: 원래 Y_old(FOUT)
                         ↓
                  공통 Y 읽기(TR)
```

공통 TR의 상태를 하나로 고정하고 변환을 표현하지 않으면, 현재 합류 제약은 양쪽 공급자에게 같은 상태를 요구한다. 이때 false 경로의 원본이 FOUT인 것이 true 경로의 새 결과에도 영향을 준다. [CFG transient 제약][S7]

아래 두 대안은 Y를 로컬로 수집하는 것이 privacy와 runtime 규칙상 허용된 경우의 후보다. 보호된 원본을 임의로 다운로드할 수 있다는 가정은 하지 않는다.

**대안 A — 함수 호출 전에 로컬 인자를 만든다.**

```text
caller Y_old(FOUT)
  → 명시적 LOCAL materialization → Y_arg(LOUT)
  → input binding(LOUT) → formal Y(LOUT)
      ├─ true:  CP 정규화 → Y_new(LOUT)
      └─ false: Y_arg(LOUT)
                            → 공통 TR(CP,LOUT)
```

이 경우 함수 진입부터 Y가 local이므로 두 경로가 공통 local TR에 연결될 수 있다. 이 조합을 표현하기 위해 if-else에 새 이동 edge를 반드시 추가해야 하는 것은 아니다. 실제 call operand를 `Y_arg`로 연결하고, 원래 caller의 FOUT 객체는 보존한다.

**대안 B — formal은 FOUT으로 유지하고 false 경로에서 변환한다.**

```text
input binding(FOUT) → formal Y_old(FOUT)
  ├─ true:  필요한 입력 공급 → CP 정규화 → Y_new(LOUT) → TW(CP,LOUT)
  └─ false: Y_old(FOUT) → 명시적 LOCAL → Y_copy(LOUT) → TW(CP,LOUT)
                                                        ↓
                                                  공통 TR(CP,LOUT)
```

이 경우에는 false 경로에 실제로 실행되는 물리 변환 위치가 필요하다. 원래 DML에 else가 없어도 컴파일러의 물리 계획에 그 위치를 표현할 수 있다. 공통 TR의 false 공급자는 원래 FOUT 정의가 아니라 **변환된 `Y_copy`의 정의**가 된다.

True 경로에서는 새 local 결과를 합류 equality 때문에 다시 upload할 필요가 없어진다. 그렇다고 true 경로의 모든 통신이 사라지는 것은 아니다. 정규화 계산에 원래 Y를 공급하는 비용은 선택된 실행 방식과 캐시 상태에 따라 남을 수 있다.

A와 B의 비용이 같거나 B가 항상 더 싸다고 단정할 수는 없다. L2SVM은 조건문 전에 Y의 최솟값·최댓값과 label 개수도 계산한다. 앞선 소비, 호출별 실행 빈도, 같은 원본의 다운로드 공유, 이후 연산의 요구 배치를 함께 비교해야 한다. 기존 FOUT 유지 계획도 비교 후보로 남긴다.

## 5. ‘이동 edge 추가’가 실제 구현에서 뜻하는 것

그래프에 FOUT → LOUT 화살표를 그리는 것만으로는 부족하다. 선택된 변환에는 입력 값의 버전, 실행 위치와 조건, 로컬 결과, 실제 명령 생성 및 비용이 연결되어야 한다.

현재 planner가 선택한 LOCAL/REFED_LOCAL의 `PrefetchCPInstruction`은 값을 동기적으로 materialize하여 별도의 local 출력을 만든다. 일반 prefetch는 원래 MatrixObject를 alias하므로, 단순히 prefetch 이름만 추가하는 것과 구분해야 한다. [계획된 local materialization][S8]

이 기존 primitive를 재사용할 여지는 있지만, **새 함수 경계/분기 변환까지 이미 연결되었다는 뜻은 아니다.** 현재 emission 검증은 실제 compiled occurrence, source value version, scope, 물리 상태와 정확한 consumer input edge를 요구한다. 새 변환 위치도 이 검증을 만족하는 계획·lowering 표현으로 연결해야 한다. 가상 edge를 추가하고 검증을 건너뛰는 방식으로 처리하지 않는다. [LOCAL emission 검증][S9]

최소 구현 단위는 다음과 같다.

1. 필요한 경계에서만 변환 후보를 만들고, 원본과 변환 결과를 별도 값으로 식별한다.
2. 변환 결과를 실제 call operand 또는 해당 분기의 최종 정의로 연결한다.
3. 기존 TW/TR과 binding 보존 제약은 변환 **이후의 값**에 적용한다.
4. 선택된 경로와 호출에서만 명령이 실행되도록 lowering하고, 원본 객체의 alias와 FederationMap을 보존한다.
5. Privacy, 물리 map, 실행 구간 제한을 검사하고 합법적인 이동 비용을 최적화 전에 반영한다.

분기 실행 빈도와 다운로드 공유를 다루는 기반은 이미 있다. 새 변환을 그 기반에 올바르게 연결하는 것이 과제다. 같은 이름 Y라도 `Y_old`, `Y_new`, 다른 호출이나 반복에서 생성된 값은 무조건 같은 캐시 항목으로 묶을 수 없다. [분기 빈도 분석][S10], [다운로드 공유 비용 모델][S11]

## 6. 이 문제에서 J_v가 하는 일과 하지 않는 일

위 L2SVM 예시는 **한 변수 Y의 합류에서 서로 다른 배치를 어떻게 공통 요구에 맞출 것인가**의 문제다. 이 조합을 표현하려고 일반적인 다중 입력 `J_v` 전체를 먼저 구현할 필요는 없다.

`J_v`가 필요한 별도 상황은 다음과 같다.

```text
true:  (x1, y1)
false: (x2, y2)
이후:  z = op(x, y)

가능한 공동 공급: (x1,y1), (x2,y2)
같은 분기 결정에서 발생하지 않는 공급: (x1,y2), (x2,y1)
```

각 입력의 공급자 집합만 따로 보면 불가능한 교차 조합까지 검사하여 합법 후보를 제거할 수 있다. `J_v`는 실제 가능한 조합으로 이 검사를 한정한다. 그러나 **누락된 GET/upload 후보를 자동으로 만들거나, 필요한 runtime 명령을 생성하지는 않는다.**

분기별로 계산 노드를 전부 복제하는 것도 필수는 아니다. 공통 TR과 소비자를 유지하면서 필요한 변환 결과와 입력 관계만 추가하는 설계부터 시작할 수 있다. 원래의 서로 다른 map을 변환 없이 공통 소비자로 그대로 보내려는 목표는 reader/consumer의 지원 상태를 넓히는 별도 확장이다.

합법성은 같은 0/무한대 hard factor 틀로 표현할 수 있다. Binding 위반, privacy 위반, 지원되지 않는 이동·입력 조합에는 무한대 비용을 부여한다. 다만 없어진 후보를 hard factor가 복원하지는 않는다. 또한 planner가 유리한 runtime 분기만 고를 수 있는 것은 아니므로, 도달 가능한 모든 경로에서 선택 계획이 합법이어야 한다.

## 7. 권장 구현 순서와 완료 기준

**먼저 binding의 보존 규칙을 유지하면서 명시적 인자/결과 변환을 표현하고, 그다음 분기별 이동 위치를 확장하는 순서를 권장한다.** 이 작업을 일반 `J_v` 구현과 한꺼번에 시작할 필요는 없다.

| 순서 | 작업 | 완료를 확인할 증거 |
|---|---|---|
| 1 | 함수 binding의 값 보존과 생산자 exec 구분을 유지한다. | FED/LOUT 생산 결과 → CP/LOUT 경계가 허용되고, 이동 없는 FOUT → LOUT binding은 거절된다. |
| 2 | 호출 인자/반환값의 명시적 변환 후보를 연결한다. | L2SVM 대안 A가 계획·lowering되고, 원본 FOUT 객체를 보존한 별도 local 값이 전달된다. |
| 3 | 필요한 분기 경로에 변환 후보를 배치한다. | 대안 B의 두 predicate 결과에서 올바른 값이 전달되고, false 변환이 true 경로에서 실행되지 않는다. |
| 4 | 실행 조건과 캐시를 비용 모델에 연결한다. | 실제 같은 원본의 공유는 반영하고, 새 값·다른 호출·반복의 비용을 잘못 합치지 않는다. |
| 5 | 다중 입력 상관관계에 따른 추가 손실을 검증한다. | AA/BB만 가능한 fixture와 AB/BA도 가능한 독립 분기 fixture를 구분해 필요한 공동 입력 분석 범위를 정한다. |

새 기능은 작은 fixture의 후보·합법성 검사부터 확인하고, 이후 L2SVM의 두 조건을 실제 runtime까지 실행하여 수치 결과, privacy, 이동 위치와 원본 객체 보존을 검증해야 한다. 위 표는 향후 구현의 완료 기준이며, 이 문서 작성에서 완료한 테스트 목록이 아니다.

관련 문서: [함수 binding 변경 및 A/B 비교](FUNCTION_BOUNDARY_ALIAS_ABLATION_2026-10-06_KO.md), [공동 입력 hard factor와 pruning](JOINT_HARD_CONSTRAINT_PRUNING_RECOMMENDATION_2026-10-06_KO.md), [Cross-block 제약 설계 권고](CROSS_BLOCK_CONSTRAINT_DESIGN_RECOMMENDATION_2026-10-06_KO.md).

[S1]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/cp/FunctionCallCPInstruction.java:163
[S2]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/cp/FunctionCallCPInstruction.java:243
[S3]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:6279
[S4]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:6343
[S5]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraph.java:594
[S6]: /home/mchoi/w1357-paper-aligned-refactor/scripts/builtin/l2svm.dml:75
[S7]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:6901
[S8]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/cp/PrefetchCPInstruction.java:47
[S9]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java:748
[S10]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFacts.java:521
[S11]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java:1209
[E1]: /home/mchoi/function-boundary-all14-20261006/REPORT.md:47
