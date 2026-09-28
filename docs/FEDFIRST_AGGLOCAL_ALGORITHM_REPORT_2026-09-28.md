# FedFirst · AggLocal 구현 알고리즘 보고서

> 이 문서는 `fe758c413d4d156cec249248f99a744eaa45edb5`의 수정 전 기록입니다. 후속 구현·검증은 [v2 구현 보고서](FEDFIRST_AGGLOCAL_IMPLEMENTATION_2026-09-28.md)를 참고하세요.

- **대상 저장소:** /home/mchoi/w1357-paper-aligned-refactor
- **분석 기준:** 2026-09-28, HEAD **fe758c413d4d156cec249248f99a744eaa45edb5**
- **범위:** 현재 Java 구현의 컴파일 시 계획 생성·선택·적용 경로. 소스와 테스트는 수정하지 않았다.
- **검증 수준:** 소스 및 테스트 assertion의 정적 대조. 이번 문서 작업에서 Java 테스트, Docker 실험, 런타임 성능 측정은 실행하지 않았다.
- **문서 검증:** 소스 링크·행 범위 33개 및 근거 ID 검사 통과, 독립 정적 검토 승인.
- **표기:** **[근거]**는 코드에 직접 나타나는 사실, **[해석]**은 그 사실에서 도출한 설명, **[한계]**는 이번 조사에서 입증하지 않은 사항이다. [Sxx]·[Txx]는 마지막 절의 파일/행 근거다.

## 1. 질문과 핵심 결론

**질문:** 이 저장소에서 FedFirst와 AggLocal은 어떤 정보를 입력받고, 어떤 순서와 기준으로 계획을 선택하며, 서로 어떻게 다른가?

두 이름은 여기서 **연합학습 모델의 가중치를 평균하는 학습 알고리즘이 아니라, SystemDS 연산의 실행 위치와 출력 데이터 위치를 결정하는 컴파일러 정책**을 뜻한다. 즉 같은 DML 프로그램에 대해 어떤 연산을 워커에서 FED로 수행하고, 어떤 중간 결과를 코디네이터에 둘지 결정한다. [S01–S04]

### 근거 강도순 요약

| 순위 | 결론 | 신뢰도 | 근거 |
|---|---|---|---|
| 1 | 두 정책은 같은 공통 PlacementAnalysis에서 출발하며, 합법적인 물리 계획 중 정책 순서상 처음 찾은 완전한 해를 선택한다. | 높음 | 공통 분석 전달, 기본 selector, candidate witness [S02–S04, S12–S16] |
| 2 | FedFirst는 FED/FOUT을 우선한다. 다만 전체 FED 수의 최대화나 전체 이동량의 최소화를 증명하지 않는다. | 높음 | 상태 정렬·POLICY_FEASIBLE·최적성 미증명 [S12–S15] |
| 3 | AggLocal은 특정 집계 이항 연산의 벡터 결과를 LOUT으로 내려놓고, 가능한 후속 경로를 CP/LOUT으로 이어가는 정책이다. | 높음 | marker 조건·경로 fact·정책 투영 [S18–S24] |
| 4 | SinglePass라는 이름과 달리 backtracking이 있으며, AggLocal에는 엄격한 선호가 불가능할 때의 계획 단계 정책 완화도 있다. | 높음 | 재귀 복원·전체 재탐색·relaxation 분기 [S13, S16, S25] |
| 5 | 실제로 어느 정책이 더 빠른지는 이 코드 조사만으로 결정할 수 없다. | 미측정 | 데이터 크기·배치·통신·연산 및 실제 선택 계획에 의존 |

**한 문장씩 요약하면:**

- **FedFirst:** “실행 가능성을 지키면서, 먼저 FED로 실행하고 결과도 FED에 남기는 쪽을 시도한다.”
- **AggLocal:** “합법적으로 로컬에 돌려줄 수 있는 집계 벡터를 경계로, 이후의 벡터/스칼라 처리를 가능한 한 로컬에서 이어간다.”

## 2. 설정값과 실제 클래스

**[근거]** 설정 키는 **sysds.federated.planner**이다. 템플릿의 기본값은 runtime이므로, 두 컴파일 정책을 쓰려면 해당 이름을 명시해야 한다. [S01]

| 사용자에게 보이는 이름 | 설정값 | 실제 planner 클래스 |
|---|---|---|
| FedFirst | compile_fed_all_max_fed_fout_single_pass | FederatedPlannerFedAllMaxFedFoutSinglePass |
| AggLocal | compile_fed_heuristic_single_pass | FederatedPlannerFedHeuristicSinglePass |

예를 들어 FedFirst 설정은 다음과 같다. AggLocal은 태그 값만 두 번째 설정값으로 바꾼다.

~~~xml
<sysds.federated.planner>compile_fed_all_max_fed_fout_single_pass</sysds.federated.planner>
~~~

**현재 주요 호출 경로:**

~~~text
DMLTranslator: final-Hop 경계
  → prepareCommonSearchSpace(...)
  → 공통 PlacementAnalysis
  → FederatedPlannerFactory.create(...)
      ├─ FedFirst planner → FedAllPlacementAdapter
      └─ AggLocal planner → HeuristicPlacementAdapter
  → PolicyFirstFeasiblePlacementSelector
  → 선택된 state + candidate/relocation 증거
  → PlacementPlanApplication.complete(...)
  → PlacementEmissionTransaction.emit(...)
~~~

**[근거]** final-Hop 경계에서 공통 분석을 준비한 뒤 planner에 같은 객체를 전달한다. 각 planner는 그 분석의 소유권을 확인하고 선택 결과를 공통 적용 단계로 넘긴다. planner 내부에서 또 다른 독립 후보 공간을 구축하는 구조가 아니다. [S02–S05]

## 3. 두 알고리즘을 이해하는 공통 모델

### 3.1 실행 위치와 결과 위치는 별개의 결정이다

| 상태 | 의미 |
|---|---|
| CP/LOUT | 코디네이터에서 계산하고 결과도 로컬에 둔다. |
| FED/LOUT | 워커에서 연합 연산을 수행하되 결과는 코디네이터의 로컬 결과로 제공한다. |
| FED/FOUT | 연합 연산을 수행하고 최종 결과를 federated 상태로 둔다. |
| CP/FOUT | 로컬 계산 결과를 검증된 placement에 업로드하여 federated 결과로 만든다. 항상 가능한 상태는 아니다. |

**[근거]** PlacementState는 실행 유형, 출력 위치, FType, shapeDependent를 함께 기록한다. FType에는 ROW·COL·FULL 등 분할 형태가 들어간다. 또한 PlacementEmissionState의 **derivedFedFout**가 native FED/FOUT과 “FED/LOUT 결과를 다시 FOUT으로 materialize한 경우”를 구별한다. 따라서 FED/FOUT이라는 표기만으로 중간 수집·재업로드가 없다고 판단할 수 없다. [S06, S08]

HOP은 고수준 연산 노드이고, **occurrence**는 함수 호출·제어 흐름·재컴파일 문맥까지 구분한 그 연산의 출현이다. 선택은 단순히 연산 이름별로 한 번 하는 것이 아니라 occurrence별로 이루어진다. **ValueVersionKey**는 어느 버전의 데이터인지, **durable anchor**는 어느 worker/range/layout을 사용해 이동할 수 있는지를 식별하는 데 쓰인다. [S07, S09]

### 3.2 합법적인 상태 목록만으로는 계획이 완성되지 않는다

**[근거]** 실제 선택 결과에는 다음 정보가 함께 필요하다. [S07, S16, S27]

1. **노드 상태 할당:** 각 decision occurrence가 어느 PlacementState를 택했는가.
2. **Candidate row:** 그 결과를 만들 때 어떤 입력별 federated/local 상태와 runtime capability를 사용했는가.
3. **Realization/support:** 해당 입력들을 어떤 정확한 원본·layout·지원 관계로 실제 공급할 수 있는가.
4. **이동 증거:** local materialization, relocation 또는 파생 FOUT materialization이 필요하다면 어떤 공통 action으로 수행하는가.

여기서 candidate의 **PRESENT**는 해당 입력 slot을 federated 입력으로 사용한다는 뜻이다. 그것이 반드시 “원래부터 원격에 있던 입력”이라는 뜻은 아니다. 적법한 relocation으로 federated 입력을 만든 경우도 구별해야 한다. **ABSENT_LOCAL**은 그 slot이 로컬 입력인 경우다.

**[해석]** 따라서 “모든 노드에 CP/FED 플래그를 붙였으니 끝”이 아니라, **그 플래그 조합을 실제 데이터 공급·이동으로 구현할 수 있다는 완전한 증거(witness)**까지 있어야 성공한다.

### 3.3 공통 분석: 정책을 적용하기 전에 합법성을 확정한다

**[근거]** 현재 리팩터링된 공통 흐름은 다음과 같다. [S05, S07–S11]

~~~text
PlacementProgramFacts.analyze
  → 프로그램 occurrence·CFG·shape 등 분석
PlacementCandidateGenerator
  → 입력 조합별 Oracle/runtime capability와 출력 후보 구성
PlacementRelationClosure
  → boundary 구조
  → 함수 입출력 관계
  → privacy 관계
  → placement 및 실행 가능성 관계 수렴
  → immutable PlacementAnalysis 발행
~~~

이 분석에는 여러 고정점 계산이 있다. 분석 결과에는 공통 그래프, 후보 규칙, 정확한 입력 edge, transient 관계, privacy/shape fact 및 AggLocal이 사용할 정책 fact가 포함된다. 후보 분석 자체는 HOP 그래프나 refed registry를 바꾸지 않았는지도 검사한다. [S10]

대표적인 공통 제한은 다음과 같다.

- 미공개 PRIVATE 또는 PRIVATE_AGGREGATE payload를 로컬로 수집하여 privacy를 우회할 수 없다. 공개 가능한 집계 결과와 원본 payload는 구별한다. [S11]
- 업로드·파생 FOUT에는 유효한 anchor, shape/FType 및 정확한 action 증거가 필요하다. [S08]
- transient read/write의 공통 후보 생성은 CP/LOUT 또는 FED/FOUT 조합만 허용한다. 재컴파일 문맥의 CP/FOUT도 제외한다. 하위 helper 하나의 허용 플래그가 곧 최종 후보 공간은 아니다. [S08, S29]
- 정책의 선호 때문에 공통 후보를 불법으로 바꾸는 것과, runtime/privacy상 실제 불가능한 후보를 제거하는 것은 다른 일이다.

## 4. FedFirst 알고리즘

### 4.1 목적: 최적해가 아니라 FED 우선 첫 실행 가능 해

**[근거]** FedAllPlacementAdapter의 기본 selector는 FEDERATED_FIRST 순서를 쓰는 PolicyFirstFeasiblePlacementSelector다. 상태 순서는 다음과 같다. [S12, S14]

~~~text
FED/FOUT → FED/LOUT → CP/FOUT → CP/LOUT
~~~

위 순서는 **이미 합법적인 domain 안의 후보를 시도하는 순서**다. 없는 후보를 새로 만들거나 불법 CP/FOUT을 허용하지 않는다.

같은 상태 순위 안에서는:

1. FED 후보에 대해, 현재 할당 및 남은 domain과 양립 가능한 candidate row의 **PRESENT 입력 수가 더 큰 것**을 우선한다.
2. 국소 이동 힌트가 더 작은 것을 우선한다.
3. 그래도 같으면 normalized signature로 결정한다.

서로 양립하지 않는 row의 입력별 최선값을 합쳐 가상의 후보를 만드는 것이 아니라, 실제 도달 가능한 row를 기준으로 PRESENT 수를 구한다. [S14]

이동 힌트는 인접한 relocation의 불가피성, source 준비, derived FOUT, anchor 정렬 등을 고려한다. 하지만 **FED 우선순위보다 먼저 전체 통신비용을 최소화하는 목적함수는 아니다**. FED 후보가 추가 이동을 요구하더라도 우선 시도될 수 있다.

### 4.2 먼저 결정할 노드: 생산자 우선, 제약 전파 병행

**[근거]** selector는 다음 구조를 사용한다. [S13–S15]

1. 합법성 제약, candidate 입력 의존성 및 공유 이동 action으로 서로 영향을 주는 노드를 component로 나눈다.
2. SAME_PLACEMENT로 연결된 노드를 decision group으로 묶고 domain 교집합을 구한다.
3. binary constraint의 arc consistency와 candidate reachability를 사용하여 불가능한 상태를 제거한다.
4. FEDERATED_FIRST에서는 아직 결정되지 않은 그룹을 **생산자 우선** 순서로 선택한다.
5. 해당 그룹의 후보 상태를 정책 순서로 시도하고, 제약 전파 후 다음 그룹으로 진행한다.

생산자 우선 정렬은 정렬된 dependency에 대한 **반복형 DFS 후위순서**다. 순환에서는 방문 표시로 backedge를 건너뛰어 종료한다. selector 자체를 “SCC를 축약한 DAG 위의 위상 정렬”이라고 설명하는 것은 정확하지 않다. 비순환 의존성에서는 생산자를 먼저 보지만 순환 내부에는 canonical traversal 순서가 적용된다.

### 4.3 실패하면 되돌아간다

**[근거]** choose(...)는 상태를 고정한 뒤 제약을 전파하고 재귀 호출한다. 실패하면 domain과 임시 할당을 복원하여 다음 상태를 시도한다. 첫 성공에서 반환한다. [S13]

또한 component별로 얻은 상태 할당을 합친 후 candidate/relocation의 완전한 witness가 없으면, **전체 그래프에 대한 상태 탐색을 다시 수행하며 각 완성된 할당에서 witness까지 확인**한다.

따라서 다음 설명은 틀리다.

- “FedFirst는 모든 노드를 한 번만 방문하고 절대 결정을 번복하지 않는다.”
- “MaxFedFout이므로 전역 FED/FOUT 개수가 최대임을 증명한다.”
- “SinglePass이므로 전체 계획 시간이 반드시 선형이다.”

### 4.4 고정된 상태를 실제 candidate와 이동으로 연결한다

**[근거]** PolicyCandidateSelectionView가 호출하는 현재 production 경로는 **CandidateSelections.selectPolicyFirstFeasible**이다. 가능한 candidate row를 남겨두고 다음 순서로 시도한다. [S16–S17]

1. FED consumer에서는 PRESENT 입력 수가 많은 row, 비FED consumer에서는 적은 row.
2. PRESENT relocation이 anchor와 정렬된 row.
3. 새로 추가해야 할 물리 action의 국소 힌트가 작은 row.
4. canonical candidate rank.

row 선택 과정에서도 source/anchor 충돌, support 관계, 남은 relocation 수요의 완성 가능성을 검사하고 backtracking한다. 최종적으로 정확한 relocation witness까지 선택되어야 성공한다.

**주석 해석 주의:** PolicyCandidateSelectionView의 상단 주석에는 materialization-maximal quotient라는 표현이 남아 있다. 그러나 현재 실제 호출은 위 first-feasible 경로이며, 최대 PRESENT row만 남기는 별도 helper를 호출하지 않는다. **PRESENT 선호는 이 경로에서 탐색 순서이지, 나머지 합법 row를 모두 삭제하는 규칙이 아니다.**

### 4.5 의사코드

~~~text
FedFirst(A):
    G ← A의 공통 그래프
    reachability / relocation index 준비
    components ← 물리적으로 결합된 결정들의 component

    각 component에 대해:
        SAME_PLACEMENT 그룹 구성
        제약 및 candidate support 전파
        생산자 우선 그룹 순서 구성
        FED/FOUT → FED/LOUT → CP/FOUT → CP/LOUT 순서로 DFS
            같은 순위면 PRESENT 수 → 국소 이동 힌트 → canonical 순서
            모순이면 복원하고 다음 후보
        첫 실행 가능한 상태 할당 채택

    assignment ← component 할당 병합
    witness ← candidate row + realization + relocation의 첫 완전한 증거

    witness가 없으면:
        전체 그래프 상태 DFS를 수행
        각 leaf에서 완전한 witness까지 요구
        첫 성공 반환

    assignment, witness, score, POLICY_FEASIBLE certificate 반환
~~~

### 4.6 score와 certificate를 읽는 법

**[근거]** 결과에는 FED 수, FOUT 수, 물리 이동 수와 정규화된 signature가 기록된다. 이동 수에는 explicit relocation만 아니라 local materialization과 FOUT materialization도 반영된다. [S12–S13]

종료 사유는 **POLICY_FEASIBLE**이며 FedAll adapter certificate의 **optimalityProven은 false**다. 기록된 structural upper envelope는 노드별 가능성을 독립적으로 합친 느슨한 상계이지, 그 값을 동시에 달성하는 계획이 있다는 보증이 아니다. exploredCount 역시 내부의 모든 candidate 탐색 횟수를 뜻하지 않으므로 “1이면 한 번 순회”라고 읽으면 안 된다. [S12–S13, S28]

## 5. AggLocal 알고리즘

### 5.1 무엇을 로컬로 내리는가?

**[근거]** AggLocal의 marker는 공통 분석이 만든 HeuristicPolicyFacts에서 가져온다. planner가 별도로 임의의 연산을 demotion 대상으로 고르는 구조가 아니다. [S04, S18–S19]

핵심 대상은 **벡터 결과를 내는 AggBinaryOp**이며 다음 조건을 충족해야 한다.

- 결과 abstract shape가 matrix이고,
- ROW 입력 layout이면 결과가 확실한 column vector,
- COL 입력 layout이면 결과가 확실한 row vector,
- FULL layout이면 결과가 확실한 vector이며,
- 공통 제약의 support를 가진 **FED/LOUT, shapeDependent=true** 후보가 존재한다.

즉 함수명 isAggregateBinaryVectorInput의 VectorInput만 보고 “벡터 입력이면 전부 대상”으로 해석하면 안 된다. 실제 shape 검사는 해당 연산의 **출력 shape**에 적용된다. sum 등 모든 집계를 무조건 marker로 삼는 것도 아니다. [S18–S19]

또한 **벡터라는 구조적 조건이지, 바이트 수가 어떤 임계값보다 작다는 조건은 아니다**. 긴 벡터도 대상이 될 수 있으므로 “항상 작은 데이터만 수집한다”고 단정할 수 없다.

**Demotion의 의미는 우선 결과를 FOUT 대신 LOUT으로 돌리는 것이다.** 계산 자체는 여전히 FED일 수 있다. 대표적으로 X %*% v를 워커에서 계산한 뒤 벡터 결과만 코디네이터로 가져오는 FED/LOUT이다.

### 5.2 합법성에 맞지 않는 demotion 선호는 미리 철회한다

**[근거]** 공통 분석은 hard constraint의 supported states를 먼저 구한다. 이 계산은 필요한 인접 support를 확인하는 것이며, 전체 실행 가능 계획의 존재를 증명하는 것은 아니다. [S19]

그다음 demotion에서 시작한 local prefix의 후속 노드가 CP/LOUT을 지원하지 않으면 해당 demotion 선호를 제거하고 경로를 다시 분석한다. 이때 **원래 base 후보를 삭제하는 것이 아니라 AggLocal의 선호를 철회**한다. 공유 함수 formal이나 transient 경계가 FOUT을 요구하는 경우가 대표적인 이유다.

### 5.3 local prefix를 어떻게 확장하는가?

**[근거]** 각 demotion에서 정확한 compiled-input edge와 허용된 CFG transient-forward edge를 따라 후속 경로를 추적한다. 주요 우선순위는 다음과 같다. [S20–S22]

1. **다음 연산도 demotion인 경우:** 정확한 native FED/LOUT continuation이 있으면 그 경로를 우선 인정한다. 무조건 두 번째 집계를 CP로 만들지 않는다.
2. **스칼라/벡터 전용 로컬 후속 연산:** 결과와 입력이 scalar 또는 확실한 vector이고, 정확한 CP/LOUT 후보와 입력 support가 있으면 local prefix로 확장한다.
3. **명시적 reentry:** 정확한 relocation을 통해 다시 FED로 들어갈 수 있는 frontier이면 기록하고 그 경로의 로컬 확장을 멈춘다.
4. **Native FED/FOUT continuation:** 로컬 값을 그대로 입력받는 정확한 연합 후보가 있으면 경계로 기록한다.
5. 그 밖의 지원되는 벡터 경로를 확장하거나, 지원되는 최종 consumer를 local terminal로 포함한다. 함수 경계를 무조건 넘지는 않는다.

스칼라/벡터 continuation은 consumer를 로컬로 선택하는 것이지 모든 sibling producer를 CP로 바꾸는 규칙은 아니다. 공개 가능한 federated vector sibling은 별도의 정확한 local materialization을 통해 공급할 수 있다. 반대로 미공개 보호 입력을 같은 방식으로 수집하는 것은 공통 privacy legality가 막는다.

### 5.4 로컬 경로에서 FED로 돌아가는 두 방식

| 구분 | Native continuation | Explicit reentry |
|---|---|---|
| 로컬 입력 slot | ABSENT_LOCAL | relocation 후 PRESENT |
| 데이터 공급 | runtime FED 연산이 로컬 입력과 federated sibling을 함께 받는다. | 로컬 값을 공통 relocation action으로 다시 federated placement에 둔다. |
| 필요한 근거 | 정확한 candidate, FED sibling, execution FType 및 유일한 proof | source value version, consumer/input slot, action obligation, durable anchor와 sibling proof |
| 의미 | 별도 입력 re-upload를 선택하지 않아도 혼합 입력 FED 연산이 가능하다. | 명시적으로 인증된 재업로드 경계다. |

**[근거]** native continuation은 로컬 slot과 하나의 FED 입력 slot, 정확한 concrete sibling/state 등으로 증명한다. explicit reentry는 source·consumer·slot에 대응하는 relocation과 일치하는 anchor/layout 증거를 요구한다. reentry proof가 여럿이면 유일하게 anchor-aligned인 것을 택할 수 있으나, 임의로 하나를 골라 권한을 만드는 규칙은 아니다. [S21]

**[해석]** AggLocal은 “한 번 LOUT이 되면 끝까지 CP”도 아니고 “FED consumer를 만나면 아무 데서나 재업로드”도 아니다. **로컬 구간을 유지하되 증명된 경계에서만 원격 계산으로 다시 연결**한다. Native continuation의 로컬 입력도 runtime 연산에서 전송될 수 있으므로 “명시적 relocation 없음 = 네트워크 통신 0”이라는 뜻은 아니다.

### 5.5 함수·분기·루프와 transient 값

**[근거]** local 경로는 단일 HOP DAG에만 한정되지 않는다. 정확한 reaching definition을 통해 TWrite→TRead를 연결한다. source/read의 벡터 조건, concrete occurrence 및 함수/재컴파일 문맥 일치 등을 확인한다. 여러 정의가 합쳐지면 **모든 reaching definition이 로컬로 증명된 경우에만** 로컬 forwarding으로 인정한다. [S22]

이는 경로를 재추적하는 고정점 과정이며, 정해진 한도 내에 수렴하지 않으면 실패한다. named function이나 loop body를 일괄 제외하지는 않지만, synthetic 경계 및 clone/recompile 종류에 대한 제한을 적용한다. 따라서 “분기 한쪽만 로컬이어도 합류 이후 전부 로컬” 또는 “루프의 모든 backedge가 자동 허용된다”는 설명은 부정확하다.

### 5.6 정책 그래프를 만든 뒤 FedFirst selector를 재사용한다

**[근거]** HeuristicPlacementAdapter는 공통 그래프 자체를 변경하지 않고 다음과 같은 **정책 투영 그래프**를 만든다. [S23–S24]

| 노드/관계 | 엄격한 AggLocal 정책에서 남기는 선택 |
|---|---|
| 일반 demotion marker | CP/LOUT 및 조건을 충족한 FED/LOUT. 기본 selector가 FED/LOUT을 먼저 시도한다. |
| 앞선 local prefix 안으로 들어온 downstream marker | CP/LOUT |
| 나머지 local-prefix 노드 | CP/LOUT |
| 정책과 무관한 노드 | 원래 base alternatives |
| native/reentry sibling 관계 | 증명된 조합을 요구하는 CONJUNCTIVE 제약 추가 |
| 로컬 prefix에서 출발하는 relocation | exact frontier와 일치하는 obligation만 유지 |
| 파생 FOUT materialization | source/target/anchor-owner 상태가 투영에 남아 있는 action만 유지 |

기본 선택기는 **FEDERATED_FIRST**다. 따라서 AggLocal을 단순히 “전체 그래프를 MOVEMENT_FIRST로 탐색하는 알고리즘”이라고 설명하면 현재 기본 경로와 다르다.

**[해석]** 기본 AggLocal은 **“FedFirst와 같은 탐색기 + 집계 벡터의 로컬 연속 처리를 반영한 정책 공간”**으로 이해하는 것이 가장 정확하다.

### 5.7 엄격한 정책이 불가능할 때의 완화

**[근거]** 엄격한 그래프에서 selector가 IllegalStateException을 던지고 메시지가 **placement policy graph has no **로 시작하면, adapter는 다음 작업을 수행한다. [S25]

1. 투영 그래프 대신 원래 공통 base graph로 돌아간다.
2. 새로운 **MOVEMENT_FIRST** selector로 첫 완전한 실행 가능 해를 찾는다.
3. 정책명 **LOCAL_CONTINUATION_FIRST_POLICY_V3_RELAXED**를 기록한다.
4. **POLICY_PREFERENCE_RELAXED|strict-view-infeasible**, certificate의 **fallbackUsed=true**를 기록한다.

엄격 모드의 정책명은 **LOCAL_CONTINUATION_FIRST_POLICY_V3**다. 완화된 MOVEMENT_FIRST도 인접 이동 힌트의 탐색 순서를 바꾸는 정책이며, 전역 통신비용 최소화를 증명하지 않는다.

모든 오류를 이 방식으로 복구하지는 않는다.

- 정책 그래프를 만드는 도중 emitted 노드의 후보가 비어 발생한 HeuristicPolicySafetyException은 이 try/catch 바깥이다.
- 다른 메시지의 IllegalStateException은 진단을 붙여 다시 던진다.
- 완화된 재탐색 자체의 실패도 전파한다.

**이 완화는 컴파일 계획 단계의 정책 선택이며, runtime에서 불가능한 계획을 임의로 수정하는 fallback이 아니다.** planner InvocationCounters의 fallbackCount=0과 adapter certificate의 fallbackUsed=true는 서로 다른 계층의 지표다. 전자만 보고 정책 완화가 없었다고 판단하면 안 된다. [S04, S25]

### 5.8 의사코드

~~~text
공통 분석 단계:
    supported ← hard constraints가 지지하는 상태
    D ← 정확한 집계 벡터 FED/LOUT 후보가 있는 demotion
    반복:
        paths ← D에서 local continuation / native / reentry 경로 분석
        모든 정의가 로컬인 CFG forwarding을 고정점까지 반영
        후속 CP/LOUT support가 없는 demotion 선호를 D에서 제거
    D가 더 줄어들지 않으면 HeuristicPolicyFacts 발행

AggLocal(A):
    markers ← A.heuristicPolicyFacts.demotions의 value version
    Gpolicy ← marker / local prefix / frontier를 투영한 그래프

    try:
        result ← FEDERATED_FIRST first-feasible(A, Gpolicy)
    지정된 "placement policy graph has no ..." 실패이면:
        result ← MOVEMENT_FIRST first-feasible(A, A.baseGraph)
        POLICY_PREFERENCE_RELAXED와 fallbackUsed 기록

    공통 후보·이동 증거를 검증
    선택 결과를 공통 plan application / emission으로 적용
~~~

## 6. 예제로 보는 차이

다음은 개념을 설명하기 위한 예다. 실제 선택은 FType, shape, privacy 및 candidate support에 따라 달라진다.

~~~text
z = X %*% v
r = z * Y
s = sum(r)
~~~

### 6.1 Y가 합법적으로 로컬 수집 가능한 벡터인 경우

- **FedFirst의 선호:** 가능한 FED/FOUT 후보가 있으면 z를 federated로 유지하고 후속 FED 계산을 먼저 시도한다. 이는 모든 입력 조건에 대한 확정 결과가 아니라 정책의 방향이다.
- **AggLocal:** z를 집계 벡터 marker로 인정하면 FED/LOUT을 우선 시도하고, r 및 최종 스칼라 처리를 CP/LOUT으로 이어갈 수 있다.
- Y의 producer는 FED/FOUT 상태를 유지하면서, r에 필요한 로컬 view만 공통 local-materialization action으로 제공할 수 있다.

**[근거]** 이 구조와 assertion은 HeuristicLocalContinuationTest의 실제 fixture에 있다. 로컬 z를 r 때문에 재업로드하지 않고, 공개 Y 벡터의 정확한 local materialization을 요구한다. 다만 이 문서 작업에서 해당 테스트를 재실행한 것은 아니다. [T03]

### 6.2 sibling이 보호된 원본 벡터 또는 일반 행렬인 경우

- Y가 미공개 PRIVATE_AGGREGATE 값이면 AggLocal의 선호로 privacy를 무시하고 수집하지 못한다.
- sibling이 벡터가 아닌 행렬 M이면, 단지 local prefix를 늘리기 위해 M 전체를 내려받는 경로로 해석하지 않는다. exact reentry/native 경계를 검토한다.
- 예를 들어 로컬 residual과 federated X로 gradient를 계산할 때, X를 수집하는 대신 native FED/LOUT을 선택하여 결과만 로컬로 돌려줄 수 있다.

**[근거]** 보호 sibling, 행렬 frontier, gradient native continuation을 각각 검증하는 assertion이 있다. [T03–T04]

## 7. 비교표와 복잡도

| 항목 | FedFirst | AggLocal 기본 경로 |
|---|---|---|
| 정책 입력 | 공통 합법 그래프 | 같은 공통 그래프 + 분석 소유 demotion/path fact |
| 우선하는 결정 | FED 실행, FOUT 결과 유지 | 집계 벡터 LOUT, 후속 로컬 경로 |
| 기본 selector | FEDERATED_FIRST | 정책 투영 후 FEDERATED_FIRST |
| 상태 선택 방식 | 제약 전파를 곁들인 first-feasible DFS | 같은 방식 |
| 실패 처리 | 상태/row backtracking, 필요 시 전체 witness 재탐색 | 같은 탐색 + 조건부 base-graph MOVEMENT_FIRST 완화 |
| 이동 취급 | 정책 동순위의 국소 힌트 및 실현 가능성 검사 | 로컬 경로·frontier 정책 + 동일 힌트 |
| 전역 비용 최적성 | 증명하지 않음 | 증명하지 않음 |
| 실행 계획 적용 | 공통 normalize + atomic emission | 동일 |

### 계산량 해석

**[해석]** 결정 그룹의 상태 수가 각각 d₁, …, d_g라면 일반적인 조합 공간은 최대 ∏dᵢ 규모가 된다. 각 상태 할당에서 candidate row와 relocation의 호환성을 선택하는 조합도 존재한다. first-feasible 종료, component 분해, index 및 제약 전파가 실제 탐색을 줄이지만 **최악의 경우 지수적 탐색 가능성을 없애는 선형 시간 알고리즘은 아니다**. [S13–S17]

공통 search-space 생성 및 AggLocal의 경로 고정점 비용도 따로 있다. 따라서 이름의 SinglePass는 “전체 컴파일 파이프라인을 한 번만 선형 순회한다”는 복잡도 보장으로 사용하면 안 된다.

## 8. 선택 이후: runtime에 실행 가능한 계획을 전달하는 방법

**[근거]** 두 planner 모두 PlacementPlanApplication.complete를 사용한다. 이 단계는 이미 선택된 계획을 진단·정규화하고, PlacementEmissionTransaction에 넘긴다. 여기서 더 좋은 계획을 새로 고르지 않는다. [S26]

Emission은 적용 전에 다음을 검사한다. [S27]

- 선택 상태가 analysis가 소유한 정확한 합법 후보인가.
- concrete HOP의 여러 occurrence가 충돌하는 결정을 갖지 않는가.
- privacy, 재컴파일, 그래프 및 입력 이동 제약을 만족하는가.
- 선택 candidate, relocation, local/FOUT materialization이 서로 일치하는가.

검증 후 HOP의 execType, forcedExecType, federatedOutput, derived flag와 계획 선택 표시를 설정하고, 필요한 runtime action/registry 및 재컴파일 상태를 등록한다. 적용 도중 예외가 나면 HOP과 registry의 snapshot을 복원한다.

**[해석]** AggLocal의 policy relaxation은 이 최종 적용 **이전**에 어떤 합법 계획을 고를지 다시 정하는 것이다. emission의 “검증 실패 시 원상복구”는 다른 계획으로 실행하는 fallback이 아니다.

## 9. 검증 근거와 남는 한계

### 9.1 이번 보고서에서 확인한 테스트 계약

아래는 **테스트 소스에 존재하는 assertion의 의미**이며, 이번 작업의 실행 통과 결과가 아니다.

| 테스트 | 확인하는 계약 | 근거 |
|---|---|---|
| PolicyFirstFeasiblePlacementSelectorTest | 첫 feasible 종료, 최적성 미증명, producer-first, reconvergent backtracking, 순환 종료 | [T01] |
| FedFirstRemoteInputPreferenceTest | 주어진 호환 domain 아래에서 도달 가능한 remote-input 조합 선호 | [T02] |
| HeuristicLocalContinuationTest | 로컬 벡터 후속 처리, 보호 입력 제한, 행렬 frontier, native gradient, branch forwarding | [T03] |
| HeuristicProtectedNestedDemotionTest | 보호 sibling이 있는 nested marker 처리, incompatible demotion 철회, marker가 없을 때 FedFirst와 같은 할당 | [T04] |

### 9.2 해석 시 주의사항

1. **클래스명보다 실제 호출을 우선해야 한다.** MaxFedFout/SinglePass라는 이름은 전역 최적화나 no-backtracking의 증거가 아니다.
2. **모든 aggregate를 CP로 옮기는 정책이 아니다.** marker 조건은 특정 AggBinaryOp의 벡터 결과이며, 그 계산은 FED/LOUT일 수 있다.
3. **작은 결과라는 직관과 구현 조건은 다르다.** 여기서 확인한 marker는 shape 기반이며 전송 바이트 임계값 기반이 아니다.
4. **두 정책의 hard legality는 공통이지만 실제 탐색 view는 다르다.** AggLocal의 로컬 선호가 공통 후보 자체의 합법성 정의는 아니다.
5. **기본 AggLocal과 relaxed AggLocal을 구별해야 한다.** 정책명, stateOrdering, fallbackUsed를 함께 읽어야 한다.
6. **미실행 범위:** 이번 작업은 성능·수치 출력 일치·전체 workload 성공을 입증하지 않는다. 특정 입력의 실제 계획은 해당 입력/설정의 planner receipt와 테스트 또는 Docker 실행으로 별도 확인해야 한다.
7. **원 논문과의 일치 여부는 별도 질문이다.** 이 보고서는 현재 checkout의 구현을 설명하며 외부 논문을 찾아 비교하지 않았다.
8. **정책 완화 분기의 독립 실행 검증은 하지 않았다.** 해당 분기의 존재와 조건은 코드로 확인했지만, 이 보고서에서는 branch coverage나 재현 성공을 주장하지 않는다.

## 10. 소스 근거 색인

아래 링크는 이 보고서가 위치한 docs 디렉터리 기준 상대 경로다. 행 번호는 위 HEAD의 소스 기준이다. 범위 표기 [Sxx–Syy]는 그 사이 색인들을 뜻한다.

| ID | 확인 대상 | 파일 및 행 |
|---|---|---|
| S01 | 설정값 | [SystemDS-config.xml.template:127–132](../conf/SystemDS-config.xml.template#L127-L132) |
| S02 | 실제 클래스 선택 | [FederatedPlannerFactory.java:34–47](../src/main/java/org/apache/sysds/hops/ipa/FederatedPlannerFactory.java#L34-L47) |
| S03 | final-Hop 공통 준비 및 planner 호출 | [DMLTranslator.java:349–412](../src/main/java/org/apache/sysds/parser/DMLTranslator.java#L349-L412) |
| S04 | AggLocal 진입·marker·적용·counter | [FederatedPlannerFedHeuristicSinglePass.java:46–110](../src/main/java/org/apache/sysds/hops/fedplanner/fedHeuristic/FederatedPlannerFedHeuristicSinglePass.java#L46-L110) |
| S05 | 공통 분석 수명과 assembly | [NeutralPlacementGraphBuilder.java:148–187](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java#L148-L187) |
| S06 | 상태 표현 | [PlacementState.java:28–47](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementState.java#L28-L47) |
| S07 | candidate emission 및 규칙 증거 | [PlacementAnalysis.java:1035–1072](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L1035-L1072) |
| S08 | Oracle·shape·anchor·파생 FOUT 후보 | [PlacementCandidateGenerator.java:77–206](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L77-L206) |
| S09 | occurrence와 value/anchor 식별자 | [PlacementIdentity.java:276–391](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java#L276-L391) |
| S10 | 공통 closure와 발행 | [PlacementRelationClosure.java:386–399](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L386-L399) |
| S11 | privacy 상태별 gate | [ExecPlacementPolicy.java:279–325](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java#L279-L325) |
| S12 | FedFirst adapter와 certificate | [FedAllPlacementAdapter.java:54–99](../src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/FedAllPlacementAdapter.java#L54-L99) |
| S13 | component 선택·전체 witness 재탐색 | [PolicyFirstFeasiblePlacementSelector.java:95–155](../src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/PolicyFirstFeasiblePlacementSelector.java#L95-L155) |
| S14 | 상태 DFS·PRESENT·movement 정렬 | [PolicyFirstFeasiblePlacementSelector.java:475–604](../src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/PolicyFirstFeasiblePlacementSelector.java#L475-L604) |
| S15 | producer-first 순서 | [PolicyFirstFeasiblePlacementSelector.java:803–855](../src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/PolicyFirstFeasiblePlacementSelector.java#L803-L855) |
| S16 | policy candidate 선택 진입 | [CandidateSelections.java:1142–1163](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java#L1142-L1163) |
| S17 | candidate row 탐색·정렬·완전 witness | [CandidateSelections.java:2279–2389](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java#L2279-L2389) |
| S18 | 집계 벡터의 정확한 layout/shape 조건 | [PlacementCandidateGenerator.java:518–537](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L518-L537) |
| S19 | demotion과 hard-constraint support | [PlacementRelationClosure.java:1562–1644](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1562-L1644) |
| S20 | 로컬 경로·경계 우선순위 | [PlacementRelationClosure.java:1647–1832](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1647-L1832) |
| S21 | explicit reentry 및 native continuation 증거 | [PlacementRelationClosure.java:1891–2086](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1891-L2086) |
| S22 | CFG forwarding 및 occurrence eligibility | [PlacementRelationClosure.java:1835–1888](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1835-L1888) |
| S23 | AggLocal 정책 관계 구성 | [HeuristicPlacementAdapter.java:221–273](../src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/HeuristicPlacementAdapter.java#L221-L273) |
| S24 | AggLocal 상태·이동 action 투영 | [HeuristicPlacementAdapter.java:280–375](../src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/HeuristicPlacementAdapter.java#L280-L375) |
| S25 | AggLocal relaxation·정책명·certificate | [HeuristicPlacementAdapter.java:65–186](../src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/HeuristicPlacementAdapter.java#L65-L186) |
| S26 | 선택 후 공통 정규화·적용 | [PlacementPlanApplication.java:24–60](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementPlanApplication.java#L24-L60) |
| S27 | atomic emission·rollback·사전 검증 | [PlacementEmissionTransaction.java:126–393](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java#L126-L393) |
| S28 | 정책 rank 및 상계 | [PolicyFirstFeasiblePlacementSelector.java:1299–1321](../src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/PolicyFirstFeasiblePlacementSelector.java#L1299-L1321) |
| S29 | 공통 transient 후보 제한 | [PlacementCandidateGenerator.java:653–655](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L653-L655) |
| T01 | first-feasible·복원·생산자·순환 회귀 | [PolicyFirstFeasiblePlacementSelectorTest.java:47–213](../src/test/java/org/apache/sysds/hops/fedplanner/placement/selector/PolicyFirstFeasiblePlacementSelectorTest.java#L47-L213) |
| T02 | remote input 조건부 선호 회귀 | [FedFirstRemoteInputPreferenceTest.java:31–96](../src/test/java/org/apache/sysds/hops/fedplanner/placement/selector/FedFirstRemoteInputPreferenceTest.java#L31-L96) |
| T03 | AggLocal 로컬 경로 회귀 | [HeuristicLocalContinuationTest.java:39–285](../src/test/java/org/apache/sysds/hops/fedplanner/placement/HeuristicLocalContinuationTest.java#L39-L285) |
| T04 | 보호·nested demotion·marker 부재 회귀 | [HeuristicProtectedNestedDemotionTest.java:28–108](../src/test/java/org/apache/sysds/hops/fedplanner/placement/HeuristicProtectedNestedDemotionTest.java#L28-L108) |

추가로 함께 대조한 세부 위치:

- 분석 발행·원본 비변이 검사: PlacementRelationClosure.java:926–1025.
- candidate rule record: PlacementAnalysis.java:1370–1403.
- derived 여부의 별도 표현: PlacementEmissionState.java:11–20.
- FedFirst planner 진입·공통 적용: FederatedPlannerFedAllMaxFedFoutSinglePass.java:77–94.
- 정책 상태 rank: PolicyFirstFeasiblePlacementSelector.java:176–184.
- SAME_PLACEMENT grouping: PolicyFirstFeasiblePlacementSelector.java:1174–1214.
- 정책 view 주석과 실제 호출: PolicyCandidateSelectionView.java:26–65.
- 로컬 스칼라/벡터 후보 조건: PlacementRelationClosure.java:1750–1832.
- transient/clone eligibility: PlacementRelationClosure.java:2089–2144.
- emission 사전 검증: PlacementEmissionTransaction.java:330–393.
- HOP 상태 및 registry 적용: PlacementEmissionTransaction.java:1160–1181.

---

**최종 정리:** 현재 구현의 본질은 **공통의 실행 가능한 물리 후보 공간 위에 서로 다른 선호를 적용하는 first-feasible 계획 선택**이다. FedFirst는 원격 실행·원격 결과 유지, AggLocal은 집계 벡터를 기준으로 한 로컬 후속 처리를 우선한다. 두 정책 모두 실제 입력 공급과 이동 증거가 맞아야 성공하며, 정책 선호를 성능 최적성이나 runtime 우회로 해석해서는 안 된다.
