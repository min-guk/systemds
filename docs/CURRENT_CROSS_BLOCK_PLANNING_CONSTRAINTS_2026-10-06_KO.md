# SystemDS 현재 블록 간 배치 제약과 J_v의 범위

작성일: 2026년 10월 6일

분석 대상: `/home/mchoi/w1357-paper-aligned-refactor`의 현재 작업 트리. HEAD는 `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592`이며, 이 문서는 커밋되지 않은 소스 변경까지 포함해 확인한 내용이다.

> 후속 검토: 아래는 현재 구현의 제약을 설명하며, 모든 def의 공통 저장장소가 runtime의 일반적인 필요조건이라는 뜻은 아니다. 실제 공동 입력 사례와 실험별 개선 판단은 [J_v 정보 손실과 현재 실험의 개선 대상](JOINT_INPUT_WORKLOAD_IMPROVEMENT_REVIEW_2026-10-06_KO.md)에 정리했다.

현재 판단은 **기존 TW→TR 제약을 유지하고, 이번 논의를 근거로 J_v 확장을 구현하지 않는 것**이다. 현재 실험에서 그 확장이 필수라는 근거는 확인되지 않았다. 앞선 J_v 관련 문서들은 표현력 확장 제안이며, 아래 설명은 실제 코드의 제약을 정리한다.

## 1. 가장 중요한 제약

**같은 TR에 도달할 수 있는 모든 정의는 하나의 공통된 reader 후보를 지원해야 한다.**

TW는 블록에서 만들어진 변수 정의를 다음 사용에 노출하는 transient write이고, TR은 그 변수를 블록에서 읽는 transient read다. 하나의 TR에는 분기나 반복문 때문에 여러 TW가 연결될 수 있다.

```text
TW_true(X)  ──┐
              ├── TR(X) ── consumer
TW_false(X) ──┘
```

Runtime에서 한 번의 TR 실행에 실제로 도달하는 값은 실행된 경로의 값이다. Planner는 어느 경로가 실행되더라도 자신이 선택한 TR 계획이 유효하도록 모든 reaching definition을 검사한다. 이때 CFG의 TW→TR 연결 자체에는 다운로드·업로드·재분할을 수행하는 action이 없다. [CFG 제약 생성][S1]

따라서 사용자가 말한 “true와 false의 TW가 같은 TR에 연결되므로 공통된 배치를 만족해야 한다”는 해석은 현재 모델에서 맞다. 다만 여기서의 일치는 **상태 일치**와 **물리 realization 지원**이라는 두 단계로 구분해야 한다.

## 2. TR/TW가 선택할 수 있는 상태

TR/TW에는 다음 두 실행·출력 조합만 허용한다. [isLegalTransient][S2]

| 실행 위치 | 출력 위치 | TR/TW 허용 여부 |
|---|---|---|
| CP | LOUT | 허용 |
| FED | FOUT | 허용 |
| CP | FOUT | 불허 |
| FED | LOUT | 불허 |

CP는 coordinator의 local 실행, FED는 federated 실행을 뜻한다. LOUT은 local 결과, FOUT은 federated 결과다. 따라서 TR/TW를 배치 변환 연산처럼 사용해 `FED/FOUT → CP/LOUT`을 암묵적으로 처리할 수 없다.

이 표는 **TR/TW에 대한 제한**이다. 일반 계산 연산은 자신의 runtime capability와 privacy 조건에 따라 `FED/LOUT` 등을 지원할 수 있다. 일반 연산의 실행 조합과 변수 경계의 허용 조합을 혼동하면 안 된다.

## 3. 상태 일치: 무엇이 같아야 하는가

코드의 `PlacementState`는 다음 네 필드로 구성된다. Worker 주소나 partition range는 이 상태에 들어 있지 않다. [PlacementState][S3]

```text
PlacementState = (ExecType, LOUT/FOUT, FType, shapeDependent)
```

여러 정의가 같은 TR로 합쳐질 때, 해당 CFG 정의→TR edge에는 `SAME_PLACEMENT`가 적용된다. 이는 네 필드의 전체 equality다. 예를 들어 일반적인 두 branch의 TW가 합쳐진다면 다음 관계를 만족해야 한다. [제약 생성][S1], [제약 판정][S4]

\[
\operatorname{State}(TW_{true})
=\operatorname{State}(TW_{false})
=\operatorname{State}(TR).
\]

| true 쪽에서 선택한 TW | false 쪽에서 선택한 TW | 하나의 TR로 직접 연결 |
|---|---|---|
| CP/LOUT | CP/LOUT | 나머지 상태 필드와 값·shape 조건도 만족하면 가능 |
| CP/LOUT | FED/FOUT/ROW | 불가: 출력 위치가 다름 |
| FED/FOUT/ROW | FED/FOUT/COL | 불가: FType이 다름 |
| FED/FOUT/ROW | FED/FOUT/ROW | 상태 일치 가능. 물리 map 검사는 별도로 필요 |

CFG의 reaching source가 하나인 경우, 일반 정의→TR edge는 `SAME_VALUE_PLACEMENT`를 사용한다. 이 predicate는 출력 위치가 같고, FOUT이면 FType이 같은지를 확인한다. Predicate 자체는 ExecType이나 `shapeDependent`의 equality를 요구하지 않는다. 그렇다고 TR/TW의 두 허용 상태나 물리 호환성 검사를 우회하는 것은 아니다. 함수 반환 경계→TR 등에는 별도 경계 제약도 있으므로, 모든 종류의 단일 공급 edge가 이 predicate를 사용한다고 일반화하지 않는다. [제약 생성][S1], [제약 판정][S4]

## 4. 물리 호환성: ROW라는 사실만으로 충분하지 않다

다음 두 값은 모두 `FED/FOUT/ROW`여도 물리적으로 다를 수 있다.

```text
X_true  : worker pool A에 행 분할
X_false : worker pool B에 행 분할
```

현재 reader는 자신의 물리 realization을 지원하는 source 후보가 **모든 reaching definition에 존재할 때** 생성된다. `exactTransientReplay`는 source별 지원을 수집하고, 모든 source를 지원하지 못하는 reader realization을 제외한다. [Reader 후보 생성][S5]

물리 지원 조건은 사용하는 증명 방식에 따라 다르다.

| Reader realization의 근거 | 요구하는 지원 |
|---|---|
| Durable map / exact layout | 각 source가 공통된 물리 layout을 지원해야 함 |
| Native lineage / exact layout | 후보에 연결된 continuity proof로 공통된 exact layout을 증명해야 함 |
| Native lineage / dynamic range | 공통 worker endpoints와 해당 후보의 continuity proof가 필요함. 모든 range가 정적으로 같을 필요는 없음 |

Exact 경로는 `samePhysicalLayout`을, dynamic native 경로의 공통 witness 검사는 `samePhysicalWorkerEndpoints`를 사용한다. Native continuity의 matching도 FType과 endpoints를 확인하고, exact range가 필요한 경우에만 partition 구간 equality를 추가한다. [공통 witness 검사][S6], [Native continuity][S7]

따라서 현재 제약을 “모든 loop iteration의 partition range가 반드시 고정되어야 한다”라고 설명하면 부정확하다. **같은 endpoints에서 range가 동적으로 변하는 경우도 기존 proof가 지원할 수 있다.** 반면, 임의의 서로 다른 endpoints A/B를 실행 경로에 따라 고르는 대안을 하나의 reader에 표현하는 일반 기능은 현재 모델에 없다.

또한 같은 endpoints라는 사실만으로 이후 모든 연산의 입력 alignment가 증명되는 것은 아니다. TR의 map 전달과 consumer의 연산별 실행 조건은 별도로 만족해야 한다.

## 5. If/else에서는 어떻게 적용되는가

다음은 연결을 보여 주기 위한 의사 코드다.

```text
if (c) {
    X = true_branch_result;
} else {
    X = false_branch_result;
}
Z = f(X);
```

조건의 값을 정적으로 확정하지 못하면 두 branch의 마지막 X 정의가 이후 TR(X)의 공급자가 된다. 한 branch에서 X를 재정의하지 않으면 그 branch로 들어간 이전 X 정의가 공급자로 남는다. 조건을 정적으로 확정할 수 있는 경우에는 현재 CFG가 해당 arm만 연결할 수 있다. [중첩 CFG 구성][S8]

Planner의 선택 예시는 다음과 같다.

1. 두 branch의 X 결과를 모두 local로 만들 수 있으면 공통 CP/LOUT reader를 검토한다.
2. 두 결과가 공통 federated reader의 상태와 물리 지원 조건을 만족하면 그 FED/FOUT reader를 검토한다.
3. 한 branch는 local, 다른 branch는 federated인 상태로 그대로 합치는 계획은 제외한다.
4. 배치를 맞추는 명시적 이동이 가능한 위치와 후보가 있다면, 그 이동을 포함한 다른 계획을 검토할 수 있다. TW→TR edge 자체가 이동을 만들어 주지는 않는다.

이 제한은 **같은 변수의 같은 reader로 합쳐지는 정의들**에 적용된다. 두 branch 내부의 모든 연산을 동일한 실행 방식으로 선택해야 한다는 뜻은 아니다. 다른 변수 X와 Y가 프로그램 전체에서 같은 worker pool을 써야 한다는 뜻도 아니다.

## 6. Loop에서는 무엇이 유지되어야 하는가

```text
X = X0;
while (cond) {
    Y = g(X);
    X = h(Y);
}
Z = k(X);
```

이 예제에서 body의 `g(X)` 앞 TR은 첫 반복의 X0와 이전 반복에서 만든 X를 모두 공급자로 가진다.

```text
진입 정의 TW(X0) ───┐
                    ▼
                  TR(X) ── g ── h ── TW(X_body)
                    ▲                    │
                    └──── backedge ──────┘
```

따라서 entry와 backedge의 선택된 정의들이 공통 reader 계약을 지원해야 한다. 반복마다 데이터 값은 바뀔 수 있다. 유지해야 하는 것은 값 자체가 아니라 **선택된 실행·출력 상태와 증명된 물리 전달 계약**이다.

Loop 뒤의 TR도 loop를 빠져나올 수 있는 정의들을 검사한다. 0회 실행 경로를 포함하면 X0가 공급자로 남는다. 현재 `while`/`for` CFG 구성은 header에 entry와 body exit를 연결하고 header를 loop exit로 사용하므로, 이 분석에서는 entry 경로가 보수적으로 남는다. 다른 분석이 반복 횟수를 알고 있다는 이유만으로 여기서 entry가 자동 제거된다고 가정해서는 안 된다. [Loop CFG 구성][S8]

Body 노드는 정적으로 한 번 표현하고 backedge를 추가한다. 변수별 reaching-definition 집합을 고정점까지 전파하므로 반복 횟수만큼 노드를 복제하지 않는다. [정의 분석의 고정점][S9]

## 7. 함수와 중첩 구조에 적용되는 범위

함수에서는 실제 인자→형식 인자 경계, 반환 정의→호출 결과 경계를 연결한다. 경계 identity에는 callsite와 인자·반환 위치가 들어가므로, 같은 함수를 여러 번 호출해도 서로 다른 호출의 연결을 구별한다. [함수 경계 identity][S10]

Native federated 값을 전달하는 경계는 source와 target의 worker-pool 지원을 확인한다. Exact와 dynamic range의 증명 수준에 따라 layout 또는 endpoints 호환성을 검사한다. [함수 물리 경계 검사][S11]

함수 경계를 모두 “ExecType까지 동일하고 이동도 불가능한 edge”로 설명해서는 안 된다. 기존 경계 모델이 허용하는 명시적 materialization·relocation은 자신의 action 근거로 처리된다. 단순 값 alias가 그런 이동을 암묵적으로 수행하는 것은 아니다.

Nested if/else, loop 안의 if/else, 함수 안의 loop 등은 `connectSequence`가 구조를 재귀적으로 연결하고 정의 분석이 그 CFG에 적용된다. 이 구조 재귀는 변수별 정의 전달을 위한 것이다. 여러 변수의 공동 supplier tuple을 계산하는 J_v 구현을 의미하지 않으며, 실제 재귀 함수의 지원 범위를 주장하는 것도 아니다. [중첩 CFG 구성][S8]

## 8. 후보 생성과 최종 계획 선택은 모두 검사한다

후보 생성 시에는 “각 writer에 이 reader를 지원할 수 있는 후보가 있는가”를 확인한다. 이후 계획을 고를 때는 **실제로 선택한 writer 후보**가 **실제로 선택한 reader realization**을 지원해야 한다. 선택하지 않은 다른 후보가 호환된다는 사실로 최종 계획을 정당화할 수 없다. [선택된 realization 검사][S12]

최종 선택을 P, reader r에 도달하는 정의 집합을 RD(r)라고 쓰면 중심 조건은 다음과 같다.

\[
\forall w\in RD(r):
\operatorname{Compatible}\bigl(P(w),P(r)\bigr).
\]

여기서 Compatible은 coarse state와 물리 realization 지원을 함께 만족한다는 뜻이다. 함수 경계에는 앞서 설명한 별도 경계·action 조건도 적용된다.

이 조건에 의해 공통 reader를 만들 수 없는 조합이 제외되고, 비용 기반 planner는 지원되는 후보와 명시적 이동 비용을 바탕으로 계획을 선택한다. **한 branch에서만 싸거나 실행 가능하다는 이유로 나머지 reaching definition의 지원 검사를 생략할 수 없다.**

TR/TW 검사를 통과해도 일반 consumer에는 추가 조건이 남는다. Runtime의 연산 지원, 입력 shape와 필요한 alignment, privacy, 선택된 입력 후보의 지원 관계를 만족해야 한다. 데이터 이동도 planner가 가능성을 확인하고 계획과 비용에 반영해야 한다. [프로젝트의 실행 원칙][S13]

CP→FOUT 등의 업로드·재배치는 기존 실제 federated anchor를 근거로 계획해야 하며, 재컴파일 구간의 CP/FOUT 금지 역시 유지된다. 이는 TR/TW의 두 허용 조합과 별도로 적용되는 정책이다. [실행 원칙][S13], [재컴파일 정책][S14]

Runtime은 누락된 이동이나 배치를 임의로 보정하는 fallback을 사용하지 않는 것이 프로젝트 계약이다. 실제 `cpvar`도 원래 Data 객체를 다른 변수에 연결하며, 그 자체로 FederationMap을 변환하거나 federated 값을 local 값으로 수집하지 않는다. 이 구현이 CFG 값 전달 edge에 암묵적 이동을 가정하면 안 되는 직접적인 이유다. [cpvar 구현][S15]

## 9. J_v와의 관계 및 현재 결정

현재 중심 분석은 변수별 reaching-definition 집합과 공통 reader 지원 검사다. 다음과 같은 공동 입력 관계를 일반적으로 보존하고 모든 tuple에 대해 물리 계획을 검사하는 J_v 분석은 현재 구현으로 주장할 수 없다.

```text
실행 가능한 공동 입력: (X_true, Y_true), (X_false, Y_false)
변수별 정보:          X ← {X_true, X_false}
                     Y ← {Y_true, Y_false}
```

이 사실을 “현재 코드가 반드시 불가능한 cross-arm tuple을 직접 열거해서 탈락시킨다”라고 설명하는 것도 정확하지 않다. 앞서 논의한 A/B pool 예제의 핵심 제한은 각 변수에 대해 공통 reader realization을 요구하는 단계에 있다.

J_v는 어떤 X와 Y가 함께 도달하는지 보존할 수 있다. 그러나 **지금의 공통 reader 제약을 그대로 유지하면서 J_v만 추가하면, 서로 다른 pool A/B를 경로별로 전달하는 새 계획이 자동으로 생기지는 않는다.** 그런 계획을 지원하려면 reader의 물리 표현과 consumer의 실행 가능성 검사도 함께 확장해야 한다.

J_v 자체는 branch별 consumer를 복제하는 것도 아니다. 기존 consumer에 공동 공급 관계를 붙이는 분석이며, 같은 정적 consumer의 선택 계획을 가능한 tuple 모두에서 검증하는 것이 기본 의미다. Branch별로 consumer를 복제해 서로 다른 계획을 고르는 변환은 별도 설계다.

현재 실험에 그 확장이 필요하다는 근거는 확인되지 않았다. A/B 예제는 현재 모델이 표현하지 않는 계획을 설명한 구조적 예시였으며, 실제 benchmark 실패나 성능 손실을 재현한 결과가 아니다. 따라서 **이번 논의에 따른 추가 planner 구현은 진행하지 않고 현재 제약을 유지한다.** 이는 현재 모델 밖의 모든 runtime 실행 가능 계획까지 표현한다는 완전성 주장도, planner 전체에 버그가 없다는 주장도 아니다.

논문의 설명은 이 구분을 반영해야 한다. 현재 코드에 대한 문장이라면 “변수별 reaching definitions를 연결하고, 각 reader의 선택된 배치를 모든 가능한 공급 정의가 지원하도록 검사한다”라고 기술할 수 있다. J_v를 실제로 기록하고 물리 feasibility에 사용한다는 원문의 주장은 현재 구현과 맞도록 수정하거나 향후 확장으로 구분할 대상이다. 이 문서 작성으로 논문 원문을 수정한 것은 아니다.

## 10. 관련 문서와 확인 범위

- [블록 간 연결과 공동 입력 의존성 분석](CROSS_BLOCK_AND_JOINT_INPUT_DEPENDENCIES_2026-10-06_KO.md): 최초 소스 분석.
- [Correlated execution과 계획 완전성 검토](CORRELATED_EXECUTIONS_PLAN_COMPLETENESS_2026-10-06_KO.md): 표현력 제한에 대한 분석. 현재 실험의 실패 재현과 구분한다.
- [J_v 관계 노드 설계](JOINT_INPUT_RELATION_NODE_DESIGN_2026-10-06_KO.md): 구현하지 않은 확장 제안. 현재 진행 여부는 이 문서의 결정을 따른다.
- [세션 기록](SESSION_ISSUES_2026-10-06.md): 실험 관련성 재검토와 현재 코드 유지 판단의 근거.

이 보고서는 소스와 기존 조사 기록을 확인해 작성했다. 새 planner 구현, 빌드, 테스트 또는 E2E 실험을 수행한 결과 보고서가 아니다. 문서 검증은 코드 참조의 파일·행, 문서 링크, 수식·코드 블록 및 공백 검사로 제한한다.

[S1]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:6857
[S2]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java:748
[S3]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementState.java:34
[S4]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraph.java:594
[S5]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4595
[S6]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4776
[S7]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:3506
[S8]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java:295
[S9]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java:191
[S10]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:6776
[S11]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/LogicalBoundaryRealizations.java:418
[S12]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java:1424
[S13]: /home/mchoi/w1357-paper-aligned-refactor/AGENTS.md:1
[S14]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java:201
[S15]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/cp/VariableCPInstruction.java:1030
