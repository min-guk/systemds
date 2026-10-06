# 공동 입력 제약과 안전한 계획 pruning의 설계 권고

작성일: 2026년 10월 6일

코드 기준: 앞서 fetch하여 확인한 `origin/main`의 `3d0d683c1bca099f004edf9105f5387369b4abea`. 코드 링크는 해당 커밋의 `/home/mchoi/w1357-loop-entry-main-20261006` 작업 트리를 가리킨다.

**권고하는 구성은 공동 입력의 합법성 조건을 정의하고, 이를 `0/∞` hard factor로 표현하며, 같은 제약을 전파해 불법 선택을 일찍 pruning하는 것이다.** 기존 비용 기반 planner 구조를 재사용하면서 논문의 정의와 구현을 직접 연결할 수 있다.

합법 계획 보존은 무한대라는 숫자만으로 얻어지지 않는다. 필요한 물리 후보가 생성되어 있고, 함께 도달하는 입력 관계와 runtime 호환성 판정이 정확해야 한다. 이 조건이 충족되는 범위에서 합법적인 선택을 유지하면서 불법적인 선택을 제거할 수 있다.

## 공동 입력 합법성의 정의

계획 `P`는 각 노드에서 사용할 물리 후보와 명시적인 데이터 이동 등을 선택한 결과다. `J_v`는 연산 `v`의 한 실행에 함께 도달할 수 있는 입력 정의 tuple들의 집합이다. `Compatible(P,v,t)`는 선택한 supplier와 연산 전략이 tuple `t`를 처리하고 필요한 출력 계약을 만족하는지를 뜻한다.

공동 입력에 대한 hard factor는 다음과 같이 정의할 수 있다.

$$
H_v(P)=
\begin{cases}
0 & \text{if } \forall t\in J_v,\;\operatorname{Compatible}(P,v,t),\\
\infty & \text{otherwise}.
\end{cases}
$$

기존 변수 전달, privacy와 명시적 이동의 제약을 만족하는 후보 공간에서 목적함수는 다음 형태가 된다.

$$
\min_P\left(C_{\mathrm{execution}}(P)+\sum_v H_v(P)\right).
$$

`C_execution`은 실제 계산·통신·materialization의 추정 비용이다. Hard factor의 `0`은 연산이 무료라는 뜻이 아니라 제약을 만족한다는 뜻이다. 합법성 검사는 실행 빈도로 평균내지 않는다. 실제 발생할 수 있는 실행 하나를 처리하지 못하면 그 계획은 허용하지 않는다.

현재 코드에도 합법적인 선택에는 `0.0`, 불법적인 선택에는 `Double.POSITIVE_INFINITY`를 반환하는 제약이 있다. 일반 배치 제약과 선택된 TW/TR realization 호환성이 이 방식으로 표현되며, DP-local도 이 물리 모델을 사용한다. 일반적인 `J_v` 기능이 이미 구현되어 있다는 뜻은 아니다. [배치 제약 factor][S1], [TW/TR factor][S2], [DP 연결][S3]

무한대 비용으로 hard constraint를 표현하고 제약 전파와 결합하는 방식은 기존 constraint optimization 연구에서도 사용한다. 따라서 논문에서는 이 표현을 이용하여 SystemDS의 공동 입력 조건을 명확하게 정의할 수 있다. [Cooper 등의 연구][R1]

## 무한대를 주는 대상

하나의 if-else가 `(X_A,Y_A)` 또는 `(X_B,Y_B)`를 정의하고, 이후 `X+Y`가 두 변수를 읽는다고 하자. 같은 branch 안의 두 행렬은 해당 연산에 맞게 정렬되어 있으며, A와 B는 서로 다른 worker 집합일 수 있다.

```text
실제로 가능한 tuple:
    (X_A, Y_A)
    (X_B, Y_B)

이 조건문에서는 발생하지 않는 tuple:
    (X_A, Y_B)
    (X_B, Y_A)
```

| 입력 관계 | 선택한 계획의 지원 | 판정 |
|---|---|---|
| 실제로 가능한 tuple | 지원함 | 해당 검사를 통과 |
| 실제로 가능한 tuple | 지원하지 못함 | 그 계획 선택에 무한대 비용 |
| 발생하지 않는 tuple | 무관 | 공동 입력 검사 대상에서 제외 |

첫 번째 branch만 처리할 수 있는 계획에 대해 두 번째 branch를 버리고 실행하도록 허용하지 않는다. Branch는 프로그램이 결정한다. Planner가 `J_v` 중 가장 싼 tuple 하나를 고르는 구조가 되어서는 안 된다.

반대로 발생하지 않는 교차 tuple을 불법이라고 판단하여 전체 계획에 무한대를 주면 합법 계획을 과도하게 제거하게 된다. X와 Y가 독립적인 조건문에서 결정되어 교차 tuple도 실제 가능하다면 그 조합들은 검사해야 한다.

## 합법 계획 보존의 전제

| 전제 | 만족하지 않으면 생기는 문제 |
|---|---|
| 필요한 물리 후보가 domain에 존재한다 | 비용 검사 이전에 사라진 계획을 복구할 수 없다 |
| 실제 가능한 입력 관계를 누락하지 않는다 | 실행 중 나타나는 불법 입력을 놓칠 수 있다 |
| 지원 범위의 상관관계를 충분히 정확하게 표현한다 | 발생하지 않는 조합 때문에 합법 계획을 제거할 수 있다 |
| 물리 호환성 판정이 실제 runtime 조건과 일치한다 | 불필요한 배치 일치를 강제하거나 필요한 이동·출력 조건을 놓칠 수 있다 |

분석 결과가 실제 도달 tuple의 과대근사라면 실제 실행을 모두 포함하므로 다른 검증 조건이 올바른 한 실행 안전성을 유지할 수 있다. 그러나 불가능한 tuple도 검사하기 때문에 합법 runtime 계획을 일부 제거할 수 있다. 따라서 과대근사 관계를 사용하면서 모든 runtime 합법 계획을 보존한다고 주장해서는 안 된다.

Loop에서는 정적인 정의 tuple 외에 반복마다 유지해야 하는 map·shape 관계도 호환성 증명에 포함되어야 한다. 같은 body 정의가 여러 iteration에서 실행된다는 사실만으로 그 관계가 자동으로 보존되지는 않는다.

현재 main에서는 공통 물리 map의 지원을 요구하는 reader 생성과 realization 계약이 있다. 서로 다른 map을 전달하는 후보를 지원하려면 이 표현도 함께 보완해야 한다. Joint factor를 뒤에 추가하는 것만으로 앞 단계에서 생성되지 않은 후보가 복구되지는 않는다. [Reader 후보 생성][S4], [Realization 계약][S5]

## 조기 pruning을 결합하는 방법

권고하는 구현 흐름은 다음과 같다.

```text
물리 후보와 입력 관계 생성
          ↓
공동 입력 hard factor 구성
          ↓
같은 제약으로 불가능한 부분 선택을 조기에 제거
          ↓
남은 선택들을 기존 DP로 비용 비교
          ↓
선택된 전체 계획의 hard factor 검증과 emission
```

부분 선택이 이미 하나의 제약을 위반하며 남은 선택으로도 이를 해결할 수 없으면 해당 부분 선택을 더 확장하지 않는다. 개별 후보는 관련 제약에서 현재 남아 있는 다른 후보 중 자신을 지원하는 조합이 전혀 없을 때 제거할 수 있다.

어떤 상대 후보 하나와 맞지 않는다는 이유만으로 개별 후보 전체를 제거해서는 안 된다. 다른 선택과 함께 합법적인 완성 계획을 만들 수 있다면 그 가능성을 보존해야 한다. 여기서 지원 여부를 검사하는 대상은 실제로 선택할 물리 후보들의 조합이며, 실행 가능한 branch를 임의로 삭제한다는 뜻이 아니다.

보존 논증은 간단하다. 합법적인 전체 계획이 어떤 후보를 사용한다면, 그 계획의 나머지 선택들이 각 관련 제약에서 그 후보의 지원을 제공한다. 따라서 “지원이 전혀 없는 후보만 제거한다”는 규칙은 그 합법 계획에 필요한 후보를 제거할 수 없다. 이 논증은 원래의 후보 공간과 제약이 올바르고, 지원 정보를 갱신하는 구현도 정확하다는 전제에 의존한다.

이 방식은 모든 계획을 완성한 뒤에야 무한대 비용을 발견하는 불필요한 작업을 줄일 수 있다. 다만 국소적인 제약 전파가 모든 전역 충돌을 미리 찾아내는 것은 아니므로 최종 계획 검증은 유지한다.

## 계획 공간과 계산량의 구분

동일한 변수와 후보 domain에 hard factor를 추가하면 전체 선택 조합 수는 그대로이고, 허용되는 선택이 줄어든다. 무한대 비용을 부여하는 행위 자체가 새로운 후보나 연산 노드를 만들지는 않는다.

반면 더 정확한 joint 분석으로 기존의 과도한 제약을 제거하면 합법 계획이 복구될 수 있다. 실제 입력의 map을 사용하는 새로운 후보를 추가할 때에도 domain이 커질 수 있다. 이 두 효과는 무한대라는 비용 표현과 구분해야 한다.

계산량은 한 factor에 묶인 변수 수, 각 변수의 후보 수, tuple의 표현과 평가 방식에 영향을 받는다. 각각 후보가 10개인 변수 두 개의 표는 100개 셀이고, 네 개의 표는 10,000개 셀이다. 현재 solver에도 lazy factor를 freeze할 때 domain 크기의 곱만큼 dense 표를 만드는 경로가 있으므로, 콜백만 등록하면 메모리 문제가 사라진다고 가정해서는 안 된다. [Factor 표 생성][S6]

작은 factor로 분해할 수 있으면 기존 분해·지원 관계 처리 구조를 활용한다. **분해 전후 합법성이 같다는 근거 없이 공동 제약을 독립적인 pairwise 검사로 바꾸어서는 안 된다.** 지원 관계를 재사용하고 영향을 받는 부분을 갱신하는 방향이 대규모 표를 반복해서 만드는 방식보다 적합할 수 있으나, 실제 시간·메모리 효과는 측정해야 한다.

| 방식 | 특성 | 권고 |
|---|---|---|
| 완성된 계획에만 0/∞ 평가 | 정의가 간단하지만 불법 선택을 늦게 발견할 수 있다 | 최종 검증으로 유지 |
| 같은 제약을 전파하여 조기 pruning | 불법 선택의 추가 확장을 줄일 수 있다 | 기본 구현 방향 |
| 모든 reaching definition에 공통 concrete map 강제 | 표현이 단순하지만 합법적인 다른 map의 실행을 제외할 수 있다 | 해당 배치를 요구하는 후보의 조건으로 사용 |
| Branch마다 연산과 계획을 복제 | 별도 실행 전략의 표현이 가능하지만 노드·계획 관리가 늘어난다 | 현재 목표의 기본 구현으로 요구하지 않음 |

## 논문에서 구분할 주장

| 주장 | 의미 | 필요한 근거 |
|---|---|---|
| 합법성 pruning의 안전성 | 구축한 모델의 합법적인 전체 선택을 잘못 제거하지 않는다 | Pruning 규칙의 보존 논증과 독립 열거 비교 |
| Runtime 계획의 표현 범위 | 목표로 하는 runtime 합법 계획이 모델에 포함된다 | 후보 생성과 입력 관계 분석의 범위·정확성 |
| 비용 기반 pruning의 최적해 보존 | 더 비싼 합법 계획을 생략해도 최적해가 남는다 | 올바른 비용 하한 또는 같은 경계에서의 지배 관계 |
| Solver의 전역 최적성 | 모델 전체의 가장 싼 합법 선택을 찾는다 | 완전한 탐색 또는 해당 실행의 최적성 증명 |

합법성 pruning이 안전하다는 사실만으로 나머지 주장이 자동으로 성립하지 않는다. 특히 비용 기반 최적화는 더 비싼 합법 계획을 생략할 수 있으므로 모든 합법 계획을 끝까지 보관하는 알고리즘이라고 설명할 필요는 없다.

현재 DP에는 gap, 시간과 자원에 따른 종료 조건이 있다. 따라서 hard factor의 정확성과 별개로, 실제 run의 근거 없이 전역 최적해를 찾았다고 주장하지 않는다. [DP 종료 조건][S7]

Joint 기능과 그 검증을 구현한 뒤 논문에서는 다음처럼 설명할 수 있다.

> For each operator, the selected physical implementation must support every input tuple admitted by the joint-dependency analysis. We encode infeasible selections as infinite-cost factors and propagate these constraints to prune unsupported candidate combinations before and during cost optimization. Feasibility pruning preserves the feasible assignments of the constructed physical model.

이 문장은 분석이 허용한 tuple과 구축한 모델을 기준으로 한다. 모든 DML의 정확한 도달 관계를 계산하거나 모든 runtime 계획을 표현한다는 주장을 포함하지 않는다. 그 범위는 별도로 명시하고 검증해야 한다.

## 검증 방법과 완료 기준

먼저 작은 fixture에서 **같은 후보 domain과 같은 joint 의미**를 고정하고 pruning을 켜기 전후를 비교한다. 독립적인 완전 열거 판정기를 사용하여 각 전체 선택의 합법성을 판정한다. Production의 호환성 함수를 그대로 호출하는 검사만으로는 그 함수의 오류를 검증하기 어렵다.

비교해야 하는 것은 최종 선택 하나의 성공이나 단순 후보 수가 아니라, 해당 모델의 합법적인 전체 assignment 집합이다. 개수만 같고 구성원이 바뀔 수도 있으므로 실제 선택 조합도 비교한다. 국소 pruning 뒤에 남은 모든 조합이 이미 합법이어야 한다는 뜻은 아니며, 동일한 최종 합법성 판정을 적용한 집합을 비교한다.

비용 기반 pruning은 별도로 검증한다. 이 경우에는 합법 계획 일부가 제거될 수 있으므로, 독립 열거로 구한 최적 비용과 최적해의 보존 여부를 확인한다. 합법성 pruning의 집합 보존 검사와 섞지 않는다.

| 검증 사례 | 확인할 내용 |
|---|---|
| 같은 if-else에서 나온 두 입력 | 실제 branch pairing은 허용하고 불가능한 교차 tuple로 과잉 탈락시키지 않음 |
| 서로 독립적인 branch | 실제 가능한 교차 tuple을 누락하지 않음 |
| 서로 다른 map을 가진 단일 입력 | 다중 입력 pairing 없이도 가능한 후보가 표현되는지 확인 |
| Loop 0회·1회·여러 회 | 초기값, backedge, 종료 후 사용과 반복 상태 조건 유지 |
| 중첩 제어 흐름과 함수 호출 | 해당 범위의 관계를 보존하고 서로 다른 호출의 supplier를 혼합하지 않음 |
| 명시적인 데이터 이동 | 계획된 위치·빈도·비용과 실제 emission이 일치함 |

이후 선택한 계획을 DML 파싱부터 runtime까지 실행하여 물리 배치와 수치 결과를 확인한다. Runtime fallback이나 암묵적 보정으로 성공한 결과를 planner 합법성의 근거로 사용하지 않는다. 성능 평가는 별도로 planning 시간, 최대 메모리, factor 크기와 검사·제거한 조합 수를 측정해야 한다.

## 관련 문서

- [블록 간 배치 제약의 설계 권고](CROSS_BLOCK_CONSTRAINT_DESIGN_RECOMMENDATION_2026-10-06_KO.md)
- [공동 입력 정보 손실과 현재 실험의 개선 대상](JOINT_INPUT_WORKLOAD_IMPROVEMENT_REVIEW_2026-10-06_KO.md)
- [origin main의 loop 진입 변환 분석](ORIGIN_MAIN_LOOP_ENTRY_REVIEW_2026-10-06_KO.md)

[S1]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:1092
[S2]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:1116
[S3]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java:53
[S4]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4742
[S5]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java:1343
[S6]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java:481
[S7]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java:157
[R1]: https://miat.inrae.fr/degivry/Cooper10a.pdf
