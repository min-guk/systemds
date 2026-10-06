# SystemDS 상관된 입력 계획의 파싱부터 실행까지 E2E 설계

작성일: 2026년 10월 6일

## 목적과 결론

현재 per-variable compatibility가 놓치는 correlated plan을 지원하려면, 공동 입력 관계를 저장하는 것에 더해 그 관계가 물리 계획 선택과 실제 실행까지 유지되어야 한다. DML 문법을 바꿀 필요는 없다. 최종 HOP 분석 경계에서 관계를 추출하고, 조건부 map 표현과 연산의 실행 가능성 증명을 후보 생성·선택·코드 생성·재컴파일·runtime 검증에 연결하는 것이 핵심이다.

이 문서는 현재 작업 트리의 코드 경로를 근거로 한 구현 방향과 수용 기준이다. 아래의 조건부 map 및 관계적 증명은 제안하는 확장이다. 해당 확장을 구현하거나 새 E2E 실행으로 검증한 상태는 아니다.

분석 대상은 `/home/mchoi/w1357-paper-aligned-refactor`이다. 기존 TR/TW placement 계약, privacy, 명시적 이동의 authority 및 runtime fallback 금지는 유지한다.

## 해결해야 할 예제

다음은 배치 관계를 설명하는 의사 코드다. A와 B는 서로 다른 worker pool이고, 각 pool 안에서 X와 Y는 같은 shape와 호환되는 ROW 분할을 갖는다고 가정한다. 선택한 연산과 privacy 정책도 해당 FED 실행을 허용해야 한다.

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

실제로 consumer에 도달하는 입력은 A/A 또는 B/B다. 두 경우 모두 현재 입력 map끼리 정렬되어 있다면 같은 FED 덧셈 명령으로 처리할 수 있다. A와 B 자체가 같은 pool일 필요는 없다.

현재처럼 X와 Y 각각에 대해 모든 writer가 공통 reader map을 지원하도록 요구하면, 이 무재배치 계획을 표현하지 못할 수 있다. 이를 해결하려면 다음 세 가지를 함께 유지해야 한다.

- 각 입력이 어떤 실제 정의에서 왔는가.
- 어떤 입력 정의들이 함께 도달할 수 있는가.
- 그 공동 입력에서 선택한 연산과 출력 배치가 실행 가능한가.

기존 문제의 코드 근거와 완전성 분석은 [상관된 실행과 계획 완전성 보고서](CORRELATED_EXECUTIONS_PLAN_COMPLETENESS_2026-10-06_KO.md)에 정리되어 있다.

## 파싱과 최종 HOP 분석 경계

현재 컴파일 흐름은 다음과 같다.

```text
DML 파싱
  → StatementBlock 생성 및 검증
  → HOP 생성
  → rewrite와 함수 분석 및 정규화
  → placement analysis
  → planner 선택과 authority 확정
  → LOP 및 runtime instruction 생성
  → 실행과 필요 시 재컴파일
```

분기와 반복의 구조는 placement analysis 시점에도 남아 있다. 현재 상관관계가 표현되지 않는 지점은 parser가 아니라, CFG 분석의 변수별 정의 집합 merge다. `PlacementProgramFacts`는 predecessor의 정의를 합치고 각 변수의 reaching definitions를 계산한다. [PlacementProgramFacts.java][S2]

관계 facts를 생성할 위치는 rewrite·specialization·정규화가 끝난 final HOP boundary다. 현재 `prepareCommonSearchSpace`는 공통 정규화 후 `bindPlacementAnalysisAtFinalHopBoundary`를 호출한다. 원본 DML이나 그 이전 HOP에만 관계를 붙이면 이후 노드 변환과 어긋날 수 있다. [DMLTranslator.java][S1]

관계 분석은 다음 차이를 표현해야 한다.

```text
변수별 집합:
    X의 supplier = {X_A, X_B}
    Y의 supplier = {Y_A, Y_B}

공동 관계:
    분기 b의 true 경로  → (X_A, Y_A)
    분기 b의 false 경로 → (X_B, Y_B)
```

분기 식별자는 단순한 변수 이름 `c`가 아니라 어느 제어 흐름 위치에서 평가한 조건인지를 구별해야 한다. 같은 이름의 변수 재할당과 함수 호출을 혼동해서는 안 된다. 반복 안의 조건은 반복별 평가라는 점도 별도로 고려해야 한다.

## 조건부 map을 가진 reader와 consumer

제안하는 reader 표현의 의미는 다음과 같다. `Select`는 설명용 표기이며 현재 존재하는 API 이름이 아니다.

```text
X.map = Select(b, A, B)
Y.map = Select(b, A, B)

Z의 실행 방식 = FED
Z의 출력 방식 = FOUT
실행 조건 = Aligned(X.map, Y.map)
```

`Select`는 데이터를 이동시키는 명령이 아니다. DML의 제어 흐름이 실제로 선택한 값의 map을 나타낸다. 두 입력이 같은 selector를 공유하므로 true에서 A/A, false에서 B/B가 된다. 발생하지 않는 A/B를 만들지 않는다.

반대로 독립적인 분기가 두 입력을 결정한다면 서로 다른 selector를 사용한다.

```text
X.map = Select(b, A, B)
Y.map = Select(d, A, B)
```

이 경우 A/B와 B/A도 가능하다. 따라서 무재배치 aligned 계획의 증명은 성립하지 않는다. 다른 실행 경로나 이동이 필요하다면 planner가 그 지원과 비용을 명시적으로 모델링해야 한다.

공통 pool 검사를 단순히 삭제하거나, writer 하나만 지원되면 승인하는 방식은 안전하지 않다. 현재 every-writer support 검사가 지키는 전체 실행에 대한 안전성을 유지하면서 reader의 표현을 관계적으로 확장해야 한다. [PlacementSupportRelations.java][S3]

반례의 모든 상태는 같은 FED/FOUT/ROW이므로 coarse placement equality를 제거할 필요는 없다. 실제 값을 전달하는 identity 계약도 유지한다. 확장할 대상은 고정 map의 교집합만 허용하는 물리 표현과 다중 입력의 pairing이다.

## Planner의 선택과 공동 실행 가능성

Planner가 선택하는 것은 실행 방식, 출력 배치, 명시적 이동 등 물리 계획이다. 프로그램의 분기 결과를 planner가 유리한 쪽으로 선택해서는 안 된다.

필요한 조건은 다음과 같다.

\[
\exists P\;\forall s\in\widehat{\mathcal S}:
\operatorname{Legal}(P,s).
\]

P는 선택한 정적 계획이고, 실행 상황 집합은 분석이 보존한 가능한 상황들이다. Legal은 해당 상황의 실제 입력 map, shape, privacy에서 계획이 실행 가능하다는 뜻이다. 같은 consumer에서는 A/A와 B/B 모두 선택된 실행 방식을 지원해야 한다.

일반 DML의 모든 구체 실행을 정확하게 판정한다고 전제해서는 안 된다. 분석 관계는 실제 가능한 실행을 포함해야 하며, 불가능하다고 증명한 조합만 제거할 수 있다. 처리량을 줄이기 위해 가능한 상황 일부를 버리면 안전성이 깨진다.

| 지점 | 필요한 확장 |
| --- | --- |
| 후보 생성 | 조건부 map을 가진 reader와 consumer 후보 생성 |
| 지원 관계와 pruning | 실제 가능한 공동 입력 전체에 대한 지원 검사 |
| DP 상태와 병합 | 이후 legality에 영향을 주는 관계 정보 보존 |
| Exact 제약 | writer/read 쌍별 검사로 충분하지 않은 공동 조건 연결 |
| 최종 선택 검증 | 선택한 producer들과 consumer의 관계 증명 일치 확인 |

현재 `CandidateSelections`는 reaching writer들을 같은 선택된 reader에 대한 conjunctive 조건으로 검사한다. Exact의 strict transient factor도 writer/read 쌍별 compatibility에 기반한다. 새로운 관계적 후보는 이 검증과 제약에서도 같은 의미를 가져야 한다. [CandidateSelections.java][S4], [ExactPhysicalModel.java][S5]

관계를 구현하는 방법은 모든 튜플의 명시적 열거로 한정되지 않는다. 공유 selector, guard, factorized relation을 사용할 수 있다. 다만 실행 상황을 solver의 자유로운 선택 변수로 취급하여 불리한 상황을 제외해서는 안 된다.

## 비용 모델과 planning 복잡도

Legality에 사용한 실행 관계와 비용이 가정하는 실행 관계는 일치해야 한다. A/A 또는 B/B만 발생하는 예제에 실제로 필요하지 않은 A에서 B로의 이동 비용을 넣으면, 합법 후보를 보존해도 선택 결과가 왜곡될 수 있다.

분기 확률과 반복 횟수를 알 수 없다면 기대 비용 또는 보수적 비용 등 집계 정책을 명시해야 한다. 어떤 정책을 쓰더라도 비용 추정이 실행 가능성을 대신 판정해서는 안 된다. 상호 배타적인 실행의 비용과 같은 실행에서 실제로 발생하는 비용도 구별해야 한다.

관계를 추가하면 DP 상태나 factor 범위가 커질 수 있다. 반면 존재하지 않는 입력 조합에 대한 검사를 줄일 수도 있다. 따라서 planning이 무조건 빨라진다고 결론낼 수 없다. 최소한 planning 시간, 메모리, 후보 수, 관계 크기와 실제 전송량을 구분하여 측정해야 한다.

또한 불가능한 실행 조합의 pruning과 물리 계획의 pruning은 다르다. 불가능한 A/B 상황을 제거하면 오히려 무재배치 계획이 새로 합법화되어 feasible plan space가 넓어질 수 있다.

## 선택 결과와 코드 생성의 증명 전달

후보 단계에서만 관계를 알고 최종 선택에는 FED/FOUT/ROW만 남기면 E2E 보장이 끊긴다. 선택 결과에는 다음 계약을 표현할 수 있는 증명이 연결되어야 한다.

```text
이 consumer는 지정된 source들의 실제 값을 입력으로 받는다.
모든 도달 가능한 공동 입력에서 필요한 alignment가 성립한다.
추가 재배치 없이 선택된 FED 경로로 실행한다.
출력 map은 해당 연산의 map 계승 규칙을 따른다.
```

관계 증명은 candidate receipt, plan 정규화, plan hash, emission prevalidation 및 runtime authority까지 보존해야 한다. 현재 emission은 선택 결과와 receipt를 검증한 후 적용한다. 이 경로를 확장해야 증명과 실제 적용이 분리되지 않는다. [PlacementEmissionTransaction.java][S6]

관계 정보가 단순 진단 로그에만 있으면 충분하지 않다. 다른 producer나 이동 action을 선택했는데 이전 관계 증명이 재사용되는 일을 선택 검증과 identity 체계가 막아야 한다.

## Runtime의 실행 전후 검증

현재 audit의 `physicalSignature`는 worker 주소가 아니라 exec/output/FType다. A/A와 B/B가 모두 FED/FOUT/ROW이면 이 coarse 검사 자체가 두 상황의 공존을 막는 것은 아니다. 그러나 해당 signature만으로 입력 map의 공동 관계까지 검증되지는 않는다. [PlannerRuntimePlacementAudit.java][S7]

E2E 계약에는 다음 검증이 필요하다.

| 시점 | 검증할 내용 |
| --- | --- |
| 실행 전 | 실제 입력들이 승인된 공동 관계와 alignment 조건을 만족하는지 |
| 실행 경로 | 선택된 계획에 없는 이동이나 다른 경로로 우회하지 않는지 |
| 실행 후 | 출력 map이 승인된 입력 map 계승 규칙을 따르는지 |

검증을 위해 실제 입력의 provenance나 제어 흐름 식별 정보가 필요한지는 구체 표현에 따라 결정해야 한다. 단순히 X와 Y의 FType이 같다는 사실만으로 승인해서는 안 된다.

계약을 위반하면 계획에 없는 broadcast·download·relocation으로 보정하지 않도록 실행 전에 차단해야 한다. 다만 이러한 차단 장치는 compiler가 가능한 실행 전체를 증명해야 한다는 요구를 대체하지 않는다. 정상적인 입력과 실행 경로에서 계약 위반이 발생하지 않는 것이 성공 기준이다.

예제의 FED binary kernel은 현재 입력 map을 비교하여 aligned 경로로 실행하고 그 입력에서 출력 map을 유도할 수 있다. 따라서 첫 검증 대상은 새로운 연산 커널보다 관계 증명 전달과 audit 경로다. 이 사실을 모든 연산의 지원으로 일반화해서는 안 된다. [BinaryMatrixMatrixFEDInstruction.java][S8]

## Loop의 관계적 불변식

Loop에서는 정적인 정의 튜플만 추가해도 문제가 끝나지 않는다. 같은 정적 정의 노드가 반복마다 다른 실제 map을 가질 수 있다.

```text
첫 반복의 consumer 입력: A/A
둘째 반복의 consumer 입력: B/B
셋째 반복의 consumer 입력: A/A
```

필요한 불변식은 항상 pool A를 사용한다는 조건이 아니다. 이 예제에서는 consumer에 도달할 때마다 X와 Y의 map이 정렬되어 있다는 관계다. entry와 body의 상태 전이를 통해 이를 증명해야 한다.

- 최초 진입 상태에서 관계가 성립하는지 검사한다.
- 본문 실행 후 다음 반복에서도 관계가 유지되는지 검사한다.
- 0회 반복 후의 사용을 포함한다.
- X만 갱신하고 Y를 갱신하기 전에 사용하는 지점을 별도로 검사한다.

반복마다 다시 평가되는 분기 조건을 하나의 영구 boolean selector로 묶으면 안 된다. 서로 다른 반복에서 선택한 값을 잘못 묶어 존재하지 않는 correlation을 만들 수 있다.

구현은 loop의 관계적 고정점 또는 필요한 불변식을 보존하는 요약을 사용해야 한다. 상태를 근사할 때도 실제 실행을 누락해서는 안 된다. 정적 J_v가 유한하다는 사실과 반복에 따른 실제 map 관계를 충분히 표현한다는 주장은 구별해야 한다.

## 함수 호출과 재컴파일

함수 호출은 실제 인자와 형식 인자, 반환 값과 caller 결과의 관계를 호출별로 보존해야 한다. 공유 함수 본문이라는 이유로 서로 다른 호출의 A와 B를 섞어서는 안 된다. body를 specialization하는 경우에는 새 occurrence와 기존 호출 관계의 연결도 유지해야 한다.

재컴파일에서는 첫 실행에서 관찰한 A를 전체 프로그램의 고정 map으로 저장하면 이후 B에서 실패할 수 있다. 관찰한 현재 상태와 원래 계획이 지원하는 전체 관계를 구별해야 한다.

현재 재컴파일 경로에는 기존 Hop 상태, runtime signature 및 선택 authority를 복원한 후 새 LOP를 만드는 처리가 있다. 새로운 관계 증명도 clone과 rewrite 이후의 origin identity 및 authority를 따라 보존되어야 한다. [Recompiler.java][S9]

재컴파일은 계획에 없는 이동이나 새로운 전략을 선택하는 우회 수단으로 사용하지 않는다. 현재 map에 맞는 구체 값은 복원하되, 이미 선택한 계획의 관계 계약 안에서 실행해야 한다.

## E2E 테스트와 수용 기준

| 테스트 | 반드시 확인할 결과 |
| --- | --- |
| 같은 분기가 X와 Y를 함께 선택 | A/A와 B/B 모두 같은 FED consumer로 실행 |
| 독립적인 분기가 X와 Y를 선택 | A/B와 B/A를 누락하지 않고 잘못된 무재배치 계획을 승인하지 않음 |
| 분기 한쪽에서 변수 미갱신 | 진입 정의와 새 정의의 실제 pairing 보존 |
| Loop 0회와 1회 및 여러 회 | entry와 backedge 및 exit 사용 모두 정상 |
| 반복 중 A에서 B로 갔다가 A로 복귀 | 첫 관찰 map에 고정되지 않음 |
| 서로 다른 pool을 전달하는 함수 호출 | 호출별 관계 보존 |
| 재컴파일 활성화와 비활성화 | 동일한 계획 계약과 수치 결과 유지 |

분기 조건이 compile-time constant folding으로 제거되지 않았는지도 검사해야 한다. A 전용 프로그램과 B 전용 프로그램을 각각 통과시키는 것은 두 상황을 지원하는 하나의 정적 consumer 계획을 검증한 것이 아니다.

수치 결과만 비교하면 불필요한 재배치로 성공한 계획과 의도한 무재배치 계획을 구분할 수 없다. 다음 증거를 연결하여 확인한다.

```text
관계 facts
  → 무재배치 후보의 존재
  → 선택 receipt
  → 생성 instruction과 authority
  → 실제 입력 및 출력 map
  → 계획하지 않은 전송의 부재
  → 수치 결과
```

후보 존재와 실제 선택은 별도 검사다. 합법 후보가 존재해도 다른 후보의 비용이 더 낮으면 선택되지 않을 수 있다. 선택까지 검증하는 fixture에서는 무재배치 계획을 선택할 근거가 되는 비용 조건도 명확히 해야 한다.

저장소 정책에 따라 PUBLIC-only fixture를 기존 ignore 규칙의 우회 수단으로 사용하지 않는다. 해당 FED 실행을 허용하는 privacy 조건에서 테스트를 구성한다. 실행 실험과 성능 비교는 `run_LAN_docker.sh`를 사용하는 동일 Docker 조건으로 수행한다.

## 구현 순서

첫 구현 단위는 같은 coarse placement를 갖는 A/A 또는 B/B 분기와 하나의 FED binary consumer다. 먼저 이 작은 예제를 파싱부터 runtime까지 연결하면 관계 증명이 끊기는 경계를 확인하기 쉽다.

1. 기존 모델에서 correlated 무재배치 후보가 사라지는 지점을 재현하고 독립 분기 대조군을 만든다.
2. final HOP boundary에 공동 입력 facts와 조건부 map 표현을 추가한다.
3. 후보 생성과 지원 관계 및 DP 선택 검증을 동일한 관계 의미로 연결한다.
4. receipt와 plan hash 및 emission authority에 관계 증명을 보존한다.
5. 실행 전후 audit를 연결하고 두 branch 결과에서 실제 무재배치 실행을 확인한다.
6. loop와 함수 호출 및 재컴파일 대조로 적용 범위를 확장한다.
7. 다른 planner와 Exact에서도 같은 분석·합법성 의미를 소비하도록 적용하고 비용과 계획 공간을 비교한다.

이는 단계적 구현 순서다. 첫 분기 예제만 통과한 상태를 loop·함수·재컴파일까지 지원한 완료 상태로 취급하지 않는다. 기존 지원 후보를 편의상 닫는 방식으로 확장을 대신해서도 안 된다.

## 완료 판단과 남은 설계 사항

완료 기준은 프로그램이 한 번 성공하는 것이 아니다. 대상 실행 상황 전체에서 관계 facts, 선택 계획, 생성 명령과 실제 map이 일치하고 계획하지 않은 이동이 발생하지 않아야 한다. 독립 분기처럼 실제로 정렬이 깨질 수 있는 대조군도 올바르게 처리해야 한다.

구체적으로 결정할 항목은 guard 및 관계 자료구조, loop 요약, DP 상태와 factor 구성, 관계의 hash 및 identity 규칙, runtime 검증에 필요한 provenance와 비용 집계 정책이다. 이 문서는 그 계약과 연결 지점을 제안하며 세부 구현이 확정되었다고 주장하지 않는다.

목표는 일반 DML 전체에 대한 완전성을 선언하는 것이 아니라, 현재 놓치는 합법 계획을 안전하게 표현하고 그 계약이 실제 실행까지 유지됨을 검증하는 것이다.

## 코드 근거와 관련 문서

행 번호는 분석 시점의 작업 트리 기준이다.

| 참조 | 코드 | 확인한 역할 |
| --- | --- | --- |
| S1 | [DMLTranslator.java][S1] | 정규화 후 final HOP boundary의 분석 바인딩 |
| S2 | [PlacementProgramFacts.java][S2] | CFG의 변수별 reaching definitions 계산 |
| S3 | [PlacementSupportRelations.java][S3] | every-writer reader support 검사 |
| S4 | [CandidateSelections.java][S4] | 선택된 reader와 writer compatibility 검증 |
| S5 | [ExactPhysicalModel.java][S5] | writer/read별 strict transient factor |
| S6 | [PlacementEmissionTransaction.java][S6] | 선택 계획과 receipt의 emission 검증 |
| S7 | [PlannerRuntimePlacementAudit.java][S7] | exec/output/FType 기반 physicalSignature |
| S8 | [BinaryMatrixMatrixFEDInstruction.java][S8] | 현재 입력 map에 기반한 aligned 실행 |
| S9 | [Recompiler.java][S9] | 기존 상태와 runtime signature 복원 후 lowering |

- [블록 간 의존성과 공동 입력 분석](CROSS_BLOCK_AND_JOINT_INPUT_DEPENDENCIES_2026-10-06_KO.md)
- [Joint Input Dependencies 도입 판단](JOINT_INPUT_DEPENDENCIES_ADOPTION_REVIEW_2026-10-06_KO.md)
- [상관된 실행과 계획 공간의 완전성](CORRELATED_EXECUTIONS_PLAN_COMPLETENESS_2026-10-06_KO.md)

[S1]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/parser/DMLTranslator.java:526
[S2]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java:138
[S3]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementSupportRelations.java:598
[S4]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java:1424
[S5]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:1116
[S6]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java:338
[S7]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAudit.java:132
[S8]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixMatrixFEDInstruction.java:112
[S9]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/recompile/Recompiler.java:507
