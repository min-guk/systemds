# SystemDS Joint Input Dependencies 도입 판단 보고서

작성일: 2026-10-06

이 보고서는 논문의 공동 입력 정의 관계 \(J_v\)를 SystemDS에 구현할 가치가 있는지, planning 복잡도와 pruning에 어떤 영향을 주는지, 현재 논문과 코드에서 어떻게 다루는 것이 적절한지를 판단한다. 다른 보고서를 읽지 않아도 판단 근거와 검증 조건을 이해할 수 있도록 작성했다.

분석 기준은 `/home/mchoi/w1357-paper-aligned-refactor`의 HEAD `0b51cc3ef8` 및 분석 당시 미커밋 수정이 포함된 작업 트리다. 검증된 소스 동작, 그에 대한 논리적 해석, 아직 측정하지 않은 성능 가설을 구분한다. 코드 변경이나 J_v 전후 성능 실험을 수행한 보고서는 아니다.

## 현재 권고

**현재 common-reader 제약은 실제 correlated executions보다 강하여, runtime이 지원하는 무재배치 계획을 표현에서 제외할 수 있다.** 아래의 서로 다른 worker pool 반례는 planner와 runtime 소스 경로로 뒷받침된다. 따라서 “현재 계약에서 J_v만 추가해도 효과가 없다”는 사실을 J_v 또는 관계적 분석이 불필요하다는 근거로 사용할 수 없다.

현재의 우선 권고는 **correlated/independent branch 대조 fixture로 이 완전성 손실을 고정하고, 경로별 map을 표현하는 reader와 공동 입력 정렬 증명을 제한적으로 검토하는 것**이다. 모든 프로그램에 명시적 J_v를 전면 열거하는 구현이 최선인지는 별도 판단해야 한다. 논문의 미구현 pairing 보존 주장은 정정해야 하지만, 표현 한계를 밝히지 않고 단순히 개념을 삭제하는 것으로 완전성 문제를 해결할 수는 없다. 기존 검사는 대체 관계의 soundness를 확보하기 전까지 유지한다.

| 질문 | 판단 | 근거의 성격 |
| --- | --- | --- |
| J_v가 분석을 더 정밀하게 만드는가 | 입력 사이의 경로 상관관계가 중요한 경우 그렇다 | 관계 표현의 의미 |
| J_v가 물리 계획을 더 좋게 만드는가 | 더 나은 합법 계획이 열리고 runtime이 이를 지원할 때 가능하다 | 조건부 추론, 현재 효과 미측정 |
| J_v가 pruning으로 planning을 빠르게 만드는가 | 보장되지 않는다. 입력 시나리오 감소와 물리 후보 감소는 다르다 | 논리적 구분 |
| 현재 코드에 J_v만 추가하면 유효한가 | 동일 reader 계약을 유지하면 추가 효과가 없을 수 있지만, 그 계약 자체가 제한적이다 | 소스 계약과 조건부 동치식 |
| 현재 논문에서 J_v를 빼도 되는가 | 구현된 기능이라는 주장은 정정해야 하며, runtime 계획 공간의 완전성 한계도 명시해야 한다 | 구현과 기술의 일치 |
| 코드의 기존 공동 입력 검사도 지워도 되는가 | 안 된다. J_v와 다른 필수 검증이다 | 현재 실행 가능성 계약 |

## Correlated executions에 대한 완전성 재검토

### 하나의 정적 FED 명령으로 가능한 반례

다음에서 네 입력은 같은 크기와 ROW FType을 가지며, A와 B는 서로 다른 worker endpoints를 가진 pool이다. A 안에서는 X_A와 Y_A의 partition이 정렬되고, B 안에서도 X_B와 Y_B가 정렬된다고 가정한다. 각 원본 값을 그대로 전달하는 무재배치 계획을 비교한다.

```text
if (c) {
    X = X_A;
    Y = Y_A;
} else {
    X = X_B;
    Y = Y_B;
}
Z = X + Y;       // 하나의 FED/FOUT/ROW 연산
```

실행 가능한 공급 쌍은 `(X_A,Y_A)`와 `(X_B,Y_B)`다. 어느 실행에서도 X와 Y는 서로 정렬된다. 프로그램이 요구하는 것은 **같은 실행 안에서의 정렬**이지, 서로 배타적인 두 실행의 X_A와 X_B가 같은 pool에 존재하는 것이 아니다.

runtime의 `BinaryMatrixMatrixFEDInstruction`은 매 실행마다 ExecutionContext에서 현재 X와 Y를 읽고, 현재 두 FederationMap의 alignment를 검사한다. aligned 경로는 현재 map의 ID와 worker를 사용해 직접 실행하며 broadcast나 download를 하지 않는다. 출력 map도 현재 입력 map에서 유도한다. 따라서 이 예는 명령 복제나 runtime fallback 없이 같은 정적 FED 명령으로 처리할 수 있다는 코드 수준 근거가 있다. [입력 조회와 aligned 실행](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixMatrixFEDInstruction.java:73), [출력 map 생성](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixMatrixFEDInstruction.java:285)

반면 현재 planner의 durable replay는 후보 seed 하나에 대해 모든 reaching writer가 `samePhysicalLayout`을 만족해야 reader 대안을 만든다. native replay도 공통 endpoints를 요구하므로 endpoints가 다른 A/B의 union을 일반적으로 표현하지 못한다. 다른 배치로 이동시키는 대안은 있을 수 있지만, 그것은 위의 원본 map을 그대로 사용하는 계획과 다르다. [durable replay](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4660), [native witness](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4776)

### 과도한 것은 전칭 검사 자체보다 공통 witness의 표현이다

모든 실제 실행에 안전해야 한다는 요구는 유지해야 한다. 문제는 그 요구를 “모든 실행에서 같은 물리 map을 사용한다”로 강화하는 데 있다. 고정된 계획 P에 대해 필요한 조건은 다음과 같다.

\[
\forall e\in ReachableExecutions,\quad
Aligned(Map_X(e),Map_Y(e))\ \land\ Executable(P,e).
\]

반례에서 현재 고정 map reader가 요구하는 형태는 더 강하다.

\[
\exists M_X:\ \forall e,\ Map_X(e)=M_X,
\qquad
\exists M_Y:\ \forall e,\ Map_Y(e)=M_Y.
\]

실제 구현에는 native-lineage 등 더 넓은 표현도 있지만, 서로 다른 endpoints A/B를 다루는 위 반례에는 공통 endpoint witness 요구가 남는다. 따라서 위 수식은 모든 native 표현을 고정 exact map으로 단순화하려는 것이 아니라, 반례에서 남는 공통 witness 제한을 설명한다.

“모든 source를 검사한다”는 전칭 조건을 “어떤 source 하나가 되면 된다”는 존재 조건으로 약화하면 안 된다. 대신 reader를 경로에 따라 달라지는 값의 관계로 표현하고, **하나의 선택 계획이 모든 실제 공동 입력 튜플에서 성립하는지** 검사해야 한다.

TWrite/TRead는 각 실제 실행에서 같은 객체의 map을 전달한다는 identity 계약을 계속 지킬 수 있다. 이 반례의 모든 경로는 동일한 FED/FOUT/ROW 상태이므로 coarse `SAME_PLACEMENT` 자체를 무조건 제거할 필요도 없다. 핵심은 reader realization의 물리 map 표현과 입력 사이의 관계다.

### 앞선 권고의 수정 범위

같은 reader 계약을 고정하면 J_v 검사와 기존 검사가 동치일 수 있다는 뒤의 수식은 유효하다. 그러나 그것은 **제한된 표현 내부의 동치**이며, 그 표현이 runtime-supported plan space를 완전히 포괄한다는 증명은 아니다. 이 조건부 동치에서 “관계적 분석을 구현할 필요가 없다”는 결론을 내리면 현재의 제한을 그 제한의 정당화 근거로 다시 사용하는 오류가 된다.

따라서 현재 평가는 “불필요한 정밀도 기능”보다 **물리 계획 표현의 완전성 개선 가능성**에 가깝다. 명시적 J_v, guarded alternatives, shared branch selector를 갖는 관계적 layout 표현 등 어느 방법이 적절한지는 실험과 설계로 비교해야 한다.

이 반례의 planner 배제 조건과 runtime 연산 지원은 소스로 확인했다. 다만 특정 DML을 전체 compiler, selector, lowering, runtime audit 경로로 실행하여 배제·재배치·비용 차이를 재현한 결과는 아니다. 그러므로 구조적 완전성 손실과 workload별 관측 손실을 구분한다. Exact solver가 현재 표현 안에서 최적이어도, 그 결과를 runtime이 지원하는 모든 계획 중 최적이라고 확대 해석할 수 없다.

## 공동 입력 정의 관계와 현재 구현

### J_v가 표현하는 정보

```text
if (condition) {
    X = x1;
    Y = y1;
} else {
    X = x2;
    Y = y2;
}
Z = combine(X, Y);
```

`x1`, `x2`, `y1`, `y2`는 설명용 정적 정의 식별자다. 입력별 공급자 집합은 다음과 같다.

\[
R_X=\{x_1,x_2\},\qquad R_Y=\{y_1,y_2\}.
\]

반면 같은 실행에서 함께 공급될 수 있는 정의 쌍은 다음뿐이다.

\[
J_Z=\{(x_1,y_1),(x_2,y_2)\}.
\]

`(x1,y2)`와 `(x2,y1)`은 이 분기에서 실제로 함께 도달할 수 없다. J_v는 입력별 가능성을 넘어서 이러한 pairing을 표현한다. 그래프에 TRead 경계를 남기는 실제 표현에서는 원래 writer에서 TRead를 거쳐 사용 연산으로 값이 전달된다.

| 용어 | 이 보고서에서의 의미 |
| --- | --- |
| reaching definition | 해당 사용 위치까지 덮어써지지 않고 도달할 수 있는 정의 |
| 입력 시나리오 | 한 실행에서 사용 연산에 함께 공급되는 정의들의 튜플 |
| 물리 계획 후보 | 연산 실행 위치, 출력 배치, 정확한 입력 구현, 필요한 이동을 선택한 대안 |
| reader realization | TRead가 값을 받는 방식에 대한 구체적인 물리 구현과 그 근거 |
| support clause | 한 물리 구현을 지원하는 입력 binding 및 증명 조건들의 묶음 |

### 현재 코드가 보존하는 정보

현재 CFG는 변수별 정의 집합을 독립적으로 합친다. `CfgAnalysis`는 reaching definitions 등을 저장하지만, 위 J_v와 같은 일반적인 다중 입력의 경로별 튜플 관계는 조사한 생성·소비 경로에서 확인되지 않았다. [정의 합집합과 저장 구조](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java:376)

대신 현재 코드는 다음을 검증한다.

- 각 reader에 선택한 동일한 realization을 모든 reaching writer가 지원하는가.
- 선택한 물리 입력 binding들이 함께 연산을 지원하는가.
- 한 decision owner가 서로 모순되는 두 realization을 동시에 선택하지 않는가.
- 원격 입력의 실제 worker pool 및 layout, 필요한 relocation에 정확한 근거가 있는가.

이 검사는 “같은 if arm의 정의들을 묶는다”는 의미의 J_v와 다르다. 따라서 현재 물리 input binding 검사를 삭제하거나 J_v라는 이름으로 바꾸는 것은 적절하지 않다. [S4], [S5], [S6]

## 효과와 비용에 대한 분석


### 입력 시나리오의 감소와 물리 계획 후보의 감소는 다르다

한 분기에서 `(x1,y1)` 또는 `(x2,y2)`만 생긴다면, J_v는 변수별 곱집합의 네 시나리오를 두 시나리오로 줄인다. 그러나 이것이 플래너가 탐색하는 물리 계획 수도 줄인다는 뜻은 아니다.

설명용으로, 같은 후보 공간에서 모든 시나리오에 안전한 계획을 요구하는 두 검사를 비교하자.

\[
\text{곱집합 검사}:\quad \forall t\in R_1\times\cdots\times R_k,\ Safe(P,t),
\]
\[
\text{공동 도달 검사}:\quad \forall t\in J_v,\ Safe(P,t).
\]

J_v가 곱집합의 부분집합이면 뒤의 검사는 불필요한 의무를 줄인다. 따라서 앞의 검사에서 탈락했던 계획이 뒤에서는 살아날 수 있다. **입력 시나리오는 줄지만 합법 물리 계획의 집합은 오히려 커질 수 있다.** 계획 품질이 좋아질 가능성과 최적화 탐색이 더 쉬워지는지는 별개의 문제다. 현재 구현이 이 전체 곱집합을 실제로 열거한다는 뜻은 아니며, 두 가지 pruning을 구별하기 위한 수식이다.

반대로, 독립적인 입력별 근거를 잘못 섞는 느슨한 검사와 비교하면 joint 검사가 잘못된 후보를 제거할 수도 있다. 어느 효과가 생기는지는 비교 기준에 달려 있다. 현재 코드에는 이미 동일 decision owner의 선택 일관성, 물리 support clause, 모든 reaching writer의 지원 검사가 있으므로, J_v를 넣으면 그런 검사를 처음 얻는다고 설명해서는 안 된다. [S4], [S5], [S6]

### 현재 공통 reader 계약에서는 효과가 제한될 수 있다

현재 선택한 reader realization r은 모든 writer와 호환되어야 한다. 같은 추상 placement p가 모든 정의에 요구되고, downstream feasibility가 이 p들에만 의존한다면, 정의 이름의 pairing을 더 알아도 검사 결과는 달라지지 않는다.

좀 더 일반적으로, 입력 i의 공통 reader를 r_i, 정의 u와의 호환성을 C_i(u,r_i)라고 하자. J_v의 i번째 원소들을 모은 집합이 기존 R_i와 같고, 입력 결합 검사가 고정된 reader들만 사용한다면 다음 두 검사는 동치다.

\[
\bigwedge_{(u_1,\ldots,u_k)\in J_v}\ \bigwedge_i C_i(u_i,r_i)
\quad\Longleftrightarrow\quad
\bigwedge_i\ \bigwedge_{u\in R_i} C_i(u,r_i).
\]

왼쪽에서 공동 튜플을 알아도 결국 각 입력의 모든 공급자에게 같은 조건을 확인하기 때문이다. 이 동치는 “정의 pairing만 추가하고 reader 계약을 유지하는 경우”에 대한 판단 근거다. J_v 정밀화로 R_i 자체에서 불가능한 공급자가 사라지는 경우나, downstream 검사가 경로별 관계를 새로 활용하는 경우는 이 전제에 포함되지 않는다.

물리 map이 달라지는 경우에는 더 섬세하다. 예를 들어 참 경로의 X/Y는 map A에서 정렬되고 거짓 경로의 X/Y는 map B에서 정렬된다는 사실이 유용할 수 있다. 하지만 현재 `exactTransientReplay`는 모든 source가 지원하는 reader realization을 만들고, native replay도 공통 layout 또는 worker endpoints를 확인한다. [해당 코드](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4587)

따라서 이 경우를 일반적으로 활용하려면 단순히 J_v 테이블을 추가하는 것 외에, 경로에 따라 달라지는 map을 어떻게 표현하고, downstream 연산의 정렬을 어떻게 증명하며, 선택과 emission이 그 근거를 어떻게 유지하는지도 설계해야 한다. 동적 native-lineage 지원이 이미 일부 존재하므로 모든 경우에 새 runtime 기능이 필요하다고 단정할 수는 없지만, **현재 common-reader 제약을 무시해도 된다는 권한을 J_v가 주지는 않는다.**

### 복잡도와 pruning의 기대 효과

입력 k개에 공급자 후보가 각각 d개라면 변수별 목록의 크기는 대략 k×d인 반면, 명시적인 공동 튜플 관계는 최대 d^k개의 튜플을 가질 수 있다. 이는 정보 표현 크기의 비교이며 전체 알고리즘 시간복잡도를 그 두 식으로 단정하는 것은 아니다.

| 프로그램 형태 | 공동 관계의 크기와 기대 효과 |
| --- | --- |
| 한 if가 입력 k개를 함께 정의 | 실제로 두 튜플만 있을 수 있음. 2^k 곱집합과 비교하면 큰 상관관계 이득 |
| 입력마다 독립적인 if가 존재 | 2^k 조합 모두 실제로 가능할 수 있음. J_v가 제거할 가짜 조합이 없음 |
| 여러 loop와 함수 호출이 결합 | relation 고정점, 호출 문맥, 반복 간 정의 상관관계의 관리가 추가로 필요 |

정적 정의 노드 집합 V가 유한하면 J_v도 유한하다. loop가 있다는 이유만으로 J_v 튜플 수가 무한해지는 것은 아니다. 다만 모든 실행 history나 symbolic path condition을 그대로 유지하려고 하면 별도의 크기·종료 문제가 생긴다. Clang의 공식 데이터흐름 문서도 loop의 symbolic flow condition이 계속 커질 수 있어 조건 제거 또는 추상화가 필요하다고 설명한다. [Clang flow condition 설명](https://clang.llvm.org/docs/DataFlowAnalysisIntro.html#flow-condition)

실용적인 중간 선택은 모든 경로를 보존하기보다 필요한 관계만 선택적으로 유지하는 것이다. 이런 정밀도와 비용의 균형은 trace partitioning 연구의 주제이기도 하다. 다만 그 연구의 성과를 현재 SystemDS의 속도 개선 증거로 사용할 수는 없다. [Rival과 Mauborgne의 Trace Partitioning 연구](https://www.di.ens.fr/~mauborgn/publi/toplas29.html)

물리 solver 측에서도 여러 input owner를 하나의 큰 joint factor로 직접 묶으면 factor scope와 elimination separator가 커질 가능성이 있다. symbolic 또는 factorized 표현으로 피할 여지가 있으므로 필연적인 결과는 아니다. 현재 solver가 실제로 separator/domain 크기를 계산하고 한도를 검사한다는 점 때문에 반드시 측정해야 하는 위험이다. [ExactCategoricalSolver의 factor 크기 검사](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java:1196)

### 권고하는 선택과 재검토 조건

| 선택 | 판단 |
| --- | --- |
| 현재 common-reader 표현을 고정한 채 전면 J_v만 추가 | 권하지 않음. 표현 한계를 해결하지 못할 수 있음 |
| correlated-pool 반례를 고정하고 관계적 reader와 joint-layout 증명을 제한적으로 검토 | 현재 우선 권고. 구조적으로 확인된 완전성 손실을 다룸 |
| 논문의 미구현 J_v 보존 주장 정정 | 필요. 현재 모델의 표현 한계와 encoded-space 최적성 범위도 함께 기술 |
| 기존 input binding, support clause, reaching-writer 검사도 삭제 | 권하지 않음. J_v와 다른 역할을 하는 실행 가능성 검증을 잃음 |

재검토를 시작할 최소 근거는 다음과 같다.

1. 실제 workload에서 공동 입력 상관관계의 손실 때문에 계획을 놓치거나 불필요한 이동이 생기는 지점을 특정한다.
2. 같은 분기의 상관된 입력, 독립 분기의 입력, loop-carried 입력을 구분한 작은 fixture로 개선할 수 있는 합법 계획과 runtime 지원을 증명한다.
3. 그 사용처에 필요한 입력 관계만 보존하는 분석으로 계획 차이를 확인한다.
4. 분석 시간·메모리·후보 수·factor 크기·목적값과 동일 Docker 조건의 실행 결과를 비교한다. 반복 실행되는 프로그램이면 추가 planning 비용의 상각도 고려한다.

실제 가능한 튜플을 임의의 개수만 남기는 방식은 안전한 근사가 아니다. 크기 제한이 필요하면 실제 실행을 빠뜨리지 않는 보수적 상위 관계로 합쳐야 한다. 논문도 임의 프로그램의 모든 실제 경로를 정확히 판정한다는 주장 대신, 구현이 계산하는 보수적 공동 도달 관계와 정확도 범위를 명시해야 한다.

현재 권고는 구조적 근거에 기반한 판단이며 J_v 전후 성능 실험 결과는 아니다. “항상 빨라진다”, “항상 느려진다”, “어떤 workload에서도 필요 없다” 중 어느 것도 현재 증거로 확정할 수 없다.


## 구현을 재검토할 최소 실험

전체 프로그램에 분석을 추가하기 전에, 다음 두 경우를 비교하면 pairing 자체의 가치를 분리할 수 있다. 입력의 크기와 FType은 같게 하고, A와 B의 실제 물리 배치만 다르게 둔다. 조건 c와 d는 분석이 상수로 제거하지 못하는 입력 조건으로 둔다.

```text
상관된 입력:
    if (c) { X = X_A; Y = Y_A; }
    else   { X = X_B; Y = Y_B; }
    Z = combine(X, Y);

독립적인 입력:
    if (c) { X = X_A; } else { X = X_B; }
    if (d) { Y = Y_A; } else { Y = Y_B; }
    Z = combine(X, Y);
```

두 경우의 변수별 공급자 집합은 동일하다. 첫 번째는 AA/BB만 가능하고 두 번째는 AA/AB/BA/BB가 모두 가능하다. `combine`은 입력 정렬에 민감하면서 조사 대상 runtime이 지원하는 연산을 선택해야 한다.

| 검증 항목 | 확인할 내용 |
| --- | --- |
| 기본 후보 공간 | 현재 코드의 reader 후보, 탈락 이유와 최종 선택을 기록 |
| 상관된 입력 | AA/BB 모두 동일한 선택 계획으로 실행 가능한지 확인 |
| 독립적인 입력 | AB/BA를 잘못 제외하지 않고, 불일치 처리 또는 명시적 재배치를 요구하는지 확인 |
| loop 회귀 | 첫 반복, 이후 반복, 0회 반복 및 X 갱신 뒤 Y 갱신 전의 사용을 구분 |
| 함수 회귀 | 서로 다른 호출의 argument와 return pairing이 섞이지 않는지 확인 |
| 선택 결과 | relocation 수, 이동 바이트 추정, 선택 비용과 결과 수치 비교 |
| planning 비용 | analysis와 solver의 시간, 최대 메모리, relation 크기, 후보 수 및 최대 factor 크기 비교 |
| 실행 효과 | 동일 Docker 조건에서 실제 전송량과 실행시간 비교 |

**중단 기준:** J_v를 추가해도 공통 reader의 물리 표현 때문에 같은 계획만 선택된다면, 그 단계에서 전면 도입으로 확대하지 않는다. runtime이 상관된 입력의 계획을 실제로 지원하지 않는다면 J_v만으로 해결되는 문제도 아니다.

**확대 기준:** 상관된 경우에서 더 좋은 합법 계획을 얻고, 독립적인 경우의 오류를 막으며, 대표 workload의 전체 비용에서 이익을 확인했을 때 제한된 관계 분석의 범위를 넓힌다. 계획이 한 번 만들어져 N회 재사용되는 상황에서는 다음 단순 기준도 참고할 수 있다.

\[
\Delta T_{planning}<N\,\Delta T_{execution}.
\]

이는 상각 관점을 설명하는 식이며 성능 판정의 유일한 기준은 아니다. 실제로는 planning 지연 한도, 최대 메모리, 재컴파일 빈도, 측정 변동도 함께 고려해야 한다.

## 논문에서 바꿀 내용과 코드에서 유지할 내용

### 논문 기술

현재 구현을 설명하려면 다음 세 내용을 중심으로 기술하는 것이 정확하다.

1. 제어 흐름을 따라 변수별 reaching definitions를 계산한다.
2. 하나의 reader realization은 모든 reaching writer와 호환되어야 한다.
3. 각 연산의 물리 input binding을 함께 검사하여 선택 일관성, 실제 배치와 변환의 실행 가능성을 검증한다.

Joint Input Dependencies subsection의 대체 문구는 다음처럼 쓸 수 있다. 이는 논문 원본에 적용한 변경이 아니라 보고서의 제안이다.

```latex
\subsection{Physical Input Compatibility}
\label{sec:physical-input-compatibility}

For each candidate physical realization, \system{} records supporting
input bindings and checks their compatibility jointly. At a transient
boundary, the selected reader realization must be supported by every
reaching writer. These checks preserve the identity and placement of
runtime values; required data movement must be represented explicitly
by supported physical actions.

The reaching-definition analysis maintains per-variable source sets.
It does not claim to preserve path-correlated tuples of definitions
across different inputs.
```

다른 subsection의 global-feasibility 수식이 J_v를 전제로 한다면 그 수식과 참조도 함께 정리해야 한다. 한 subsection만 삭제하고 뒤의 증명이나 제약식을 그대로 두면 문서의 논리적 연결이 깨진다. 전체 논문은 이번 질문에서 제공되지 않았으므로, 실제 수정 대상의 전체 목록은 확정하지 않았다.

논문의 핵심 기여가 경로에 따라 달라지는 물리 배치를 최적화하는 것이라면, 단순한 문구 삭제로 그 기여를 유지할 수는 없다. 이 경우 위 실험으로 구현 필요성과 성과를 입증한 후 논문 범위를 결정해야 한다.

### 코드의 유지 범위

| 코드 영역 | 판단 |
| --- | --- |
| 변수별 reaching-definition 고정점 | 유지 |
| entry와 backedge를 포함한 모든 writer의 지원 검사 | 유지 |
| `SAME_PLACEMENT` 및 정확한 transient compatibility | 유지 |
| input binding과 support clause의 공동 검증 | 유지 |
| 선택된 물리 이동의 authority와 비용 | 유지 |
| 미구현 J_v 전용 분석 | 현재 삭제할 구현이 확인되지 않음 |

J_v가 없다는 이유로 현재 검사가 불필요한 것은 아니다. 반대로 J_v를 추가한다는 이유로 기존의 runtime 값 전달 계약을 완화할 수도 없다.

## 증거 수준과 남은 확인

| 내용 | 증거 수준 |
| --- | --- |
| 현재 CFG가 변수별 집합을 합치고 common-reader 지원을 요구함 | 소스 생성·저장·소비 경로에서 직접 확인 |
| 같은 reader 계약과 같은 입력별 source 집합이면 pairing 추가 검사가 동치가 될 수 있음 | 전제를 명시한 논리적 도출 |
| 시나리오 정밀화가 합법 계획 공간을 넓힐 수 있음 | 같은 후보 공간의 전칭 조건 비교로 도출 |
| planning 시간 및 runtime이 실제로 개선됨 | 미측정 |
| 모든 workload에서 J_v가 불필요함 | 그런 결론은 내릴 근거가 없음 |
| 서로 다른 pool의 correlated 무재배치 계획에 대한 common-reader 표현의 한계 | planner 배제 조건과 runtime aligned 경로로 확인한 구조적 반례. 전체 실행 재현은 미수행 |

이 보고서는 앞선 독립 architecture 검토, 저장소 소스 확인 및 일반적인 정적 분석의 공식·원 논문 근거를 재구성한 의사결정 문서다. 새 빌드·테스트·성능 실험 결과를 주장하지 않는다. 외부 문헌은 정밀도와 비용의 일반적 절충을 뒷받침하며, SystemDS의 개선율을 제공하지 않는다.

## 주요 코드 근거

| 파일과 위치 | 확인 내용 |
| --- | --- |
| [PlacementProgramFacts.java 376행](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java:376) | 정의 갱신과 변수별 집합 합치기 |
| [PlacementRelationClosure.java 4587행](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4587) | 모든 reaching writer가 같은 reader realization을 지원 |
| [PlacementRelationClosure.java 4776행](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4776) | native replay의 공통 layout 또는 endpoints 검사 |
| [PlacementSupportRelations.java 598행][S4] | writer별 전칭 지원 검사 |
| [CandidateSelections.java 1424행][S5] | 동일 reader와 최종 선택의 호환성 |
| [PlacementRelationClosure.java 7453행][S6] | 물리 binding 조합과 동일 owner의 모순된 선택 방지 |
| [ExactPhysicalModel.java 1116행](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:1116) | writer/read pair별 hard factor 생성 |
| [ExactCategoricalSolver.java 1196행](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java:1196) | elimination 과정의 factor 크기와 제한 검사 |

관련 배경: [블록 간 의존성과 현재 구현 분석 보고서](/home/mchoi/w1357-paper-aligned-refactor/docs/CROSS_BLOCK_AND_JOINT_INPUT_DEPENDENCIES_2026-10-06_KO.md). 본 보고서의 판단을 이해하기 위해 해당 문서를 먼저 읽을 필요는 없다.

[S4]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementSupportRelations.java:598
[S5]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java:1424
[S6]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:7453
