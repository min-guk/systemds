# SystemDS 중첩 제어 흐름의 상관된 배치 계획 구현 계획

작성일: 2026년 10월 6일

후속 결정: 관계 표현은 [J_v 관계 노드를 먼저 구현하는 설계](JOINT_INPUT_RELATION_NODE_DESIGN_2026-10-06_KO.md)를 우선 적용한다. 아래의 필요한 관계 질의 우선 구현은 최적화 대안으로 남기며, 선택·비용·emission·runtime·재컴파일의 E2E 계약은 유지한다.

## 구현 결정

비재귀 함수와 중첩 if/else 및 loop를 대상으로, 실제 값의 map을 따르는 reader와 필요한 배치 관계만 증명하는 분석을 구현한다. 전체 J_v 튜플이나 전체 실행 경로를 열거하지 않는다. 기존 후보 생성, 공통 물리 제약 모델, 선택 receipt, emission과 재컴파일 authority를 확장한다.

새 production Java 파일은 값 출처를 담는 `PlacementValueFlow.java`와 관계를 분석하는 `PlacementRelationAnalysis.java` 두 개를 기본으로 한다. 작은 식별자와 계약 record는 기존 소유 클래스에 둔다. 별도 solver, SMT 의존성, runtime 분기 추적기 또는 새 전역 registry를 추가하지 않는다.

실제 재귀 함수와 상호 재귀 함수의 지원 확장은 범위에서 제외한다. 함수 안의 loop, loop 안의 함수 호출, 여러 단계의 비재귀 함수 호출과 중첩 if/else는 포함한다. 이 문서는 구현 계획이며 production code 변경이나 새 E2E 통과 결과를 의미하지 않는다.

## 완료 목표와 범위

| 항목 | 이번 구현의 목표 |
| --- | --- |
| 상관된 분기 | A/A 또는 B/B로 도달하는 입력의 합법적인 무재배치 FED 계획 보존 |
| 중첩 제어 흐름 | if 안의 loop, loop 안의 if, 비재귀 함수 안의 중첩 구조를 같은 분석 규칙으로 처리 |
| 값 전달 | TRead/TWrite와 함수 인자·반환이 실제 값의 map을 보존 |
| 선택 일관성 | 관계 증명이 최종 선택한 producer realization들과 일치 |
| 실제 실행 | 계획하지 않은 broadcast, download 또는 relocation으로 보정하지 않음 |
| 재컴파일 | 첫 관찰 pool에 고정되지 않고 원래의 배치 관계 계약 보존 |
| 기존 동작 | 기존 후보와 concrete map 계약 보존, DP부터 검증 |

첫 수직 구현은 같은 shape와 coarse FED/FOUT/ROW를 갖는 입력 쌍, map을 보존하는 연산, aligned FED binary consumer로 만든다. 기존 규칙이 증명하는 map 전달 및 변환을 재사용하여 해당 연산들이 중첩된 프로그램까지 연결한다. 이는 첫 증명 규칙과 검증 대상의 범위이며, 다른 연산의 기존 후보를 차단하는 opcode 가드를 추가한다는 뜻이 아니다.

임의의 조건식 사이의 논리적 동치 판정, 모든 runtime-supported 계획의 완전성, 모든 연산의 새로운 관계 규칙까지 이번 완료 조건으로 삼지 않는다. 새 관계가 필요한 미지원 표현은 명시적인 분석 과제로 남기며 runtime 미지원이라고 위장하지 않는다. 일반적인 병렬 loop의 새로운 합법성 규칙도 이번 변경에 포함하지 않는다.

## 현재 코드에서 확인한 통합 경로

현재 DP는 과거의 별도 HOP memo DP를 수정하는 형태가 아니다. `COMPILE_COST_BASED`는 `FederatedPlanLocalCost`를 사용하고, 다음 공통 모델 경로로 실행된다. 따라서 이번 DP 우선 구현에도 이름이 `Exact`로 시작하는 공통 모델 파일의 수정이 필요하다. [FederatedPlannerFactory.java][S1], [FederatedPlanLocalCost.java][S2]

```text
최종 HOP 경계의 PlacementAnalysis
  → ExactPhysicalModel.build
  → ExactPhysicalCostModel.physicalCostSurface
  → LocalPhysicalOptimizer
  → ExactPhysicalSelection
  → ExactPhysicalPlacementProjector
  → PlacementPlanApplication
  → PlacementEmissionTransaction
  → LOP 및 Instruction
```

Local optimizer의 상태 구별에는 alternative signature가 사용된다. 관계 조건이 다른 후보를 같은 상태로 합치지 않도록 이 signature까지 확장한다. 별도의 구식 DP memo 병합 계층을 새로 만들지 않는다. [LocalPhysicalOptimizer.java][S3]

현재 CFG는 중첩 구문을 재귀적으로 연결하지만 정의 정보를 변수별 집합으로 합친다. 기존 정의 분석은 유지하면서 필요한 값 출처를 함께 보존한다. [PlacementProgramFacts.java][S4]

## 추가할 자료구조

아래 이름은 구현할 API와 record의 제안 명칭이다. 현재 존재하는 API라고 가정하지 않는다.

| 자료구조 | 소유 위치 | 역할 |
| --- | --- | --- |
| `ValueFlowRef` | `PlacementIdentity` | 기존 occurrence와 값 위치에 연결되는 안정적인 값 출처 참조 |
| `PlacementValueFlow` | 신규 파일 | 정의, predecessor별 merge, loop 연결, 함수 parameter와 result를 가진 분석 전용 그래프 |
| `VALUE_MAP` | `PlacementLayoutKind` 확장 | 고정 anchor 대신 실제 공급된 값의 map을 따르는 realization |
| `RelationRequirement` | `PlacementAnalysis` | consumer가 요구하는 typed 관계와 관련 값 참조 |
| `RelationProof` | `PlacementRelationAnalysis` 내부 | 관계를 지지하는 구체 source 선택과 하위 증명 의존성 |
| `MapExecutionContract` | runtime `Instruction`의 작은 계약 record | 실제 입력 검사와 출력 map 계승에 필요한 정보 |

`ValueFlowRef`는 변수 이름이나 문자열 lineage만으로 식별하지 않는다. program fingerprint, 기존 occurrence 및 value identity, 논리적 입력·출력 위치를 사용한다. 분석 중의 정수 handle은 캐시용으로 사용할 수 있지만 plan hash에는 안정적인 identity를 사용한다. 순환 그래프를 재귀적으로 문자열에 펼쳐 hash하지 않는다.

`VALUE_MAP`에는 typed 값 참조를 둔다. 기존 `NATIVE_LINEAGE`의 문자열이나 dynamic range 옵션을 임의의 pool union으로 재해석하지 않는다. 현재 realization은 하나의 후보 안에서 서로 다른 native pool을 섞는 것을 금지하므로, 새 종류의 의미를 명시적으로 추가한다. 기존 종류의 invariant는 그대로 둔다. [PlacementIdentity.java][S5], [PlacementAnalysis.java][S6]

## 값 출처 그래프 생성

`PlacementProgramFacts`가 final HOP boundary에서 facts를 만들 때 `PlacementValueFlow`를 함께 생성한다. 원본 DML 문법은 수정하지 않는다. HOP rewrite와 함수 specialization이 끝난 위치에서 만들고, canonical analysis의 수명 안에서 사용한다. [DMLTranslator.java][S7]

최소 node 종류와 처리 방법은 다음과 같다.

| 종류 | 구성 |
| --- | --- |
| 정의 | 기존 compiled occurrence와 출력 위치를 참조 |
| 합류 | join identity와 predecessor별 값 참조를 보존 |
| loop header | entry 값과 backedge 값을 참조하는 순환 merge |
| 함수 parameter | 함수의 형식 인자 위치를 참조 |
| 함수 result | callsite와 반환 위치 및 함수 요약을 참조 |

실제 연산의 입력 그래프는 기존 compiled input edge를 재사용한다. 모든 연산을 새 IR로 복제하지 않는다. 함수 인자·반환의 기존 callsite boundary도 재사용한다. [PlacementRelationClosure.java][S8]

한쪽 branch가 변수를 갱신하지 않으면 그 predecessor에는 진입 값을 연결한다. Loop는 header 참조를 먼저 만들고 body 분석 후 backedge를 연결한다. 반복 횟수만큼 node를 만들지 않는다. 순차 갱신 사이의 사용은 해당 지점의 값 참조를 사용하므로, X만 갱신한 시점과 X/Y를 모두 갱신한 시점을 구별한다.

## 필요한 관계만 증명하는 분석

`PlacementRelationAnalysis`는 다음 성격의 질의를 처리한다.

```text
relation(input values, selected or remaining candidate rows)
    → typed proof and required source choices
```

초기 관계 종류는 runtime의 실제 검사에 맞춰 ROW alignment, COL alignment, 필요한 worker pool 동일성으로 구분한다. 출력 map 전달은 해당 연산의 기존 map 계승 규칙으로 표현한다. 같은 endpoints라는 이유만으로 range alignment까지 증명했다고 처리하지 않는다.

처음에는 caller가 필요한 관계만 요청한다. 구체 leaf에서는 기존 map 및 native continuity 검사를 재사용하고, 관계 질의와 하위 증명을 memoize한다. 단순 `true` 문자열 표식이 아니라 실제 producer 후보 선택과 source identity에 연결된 증명을 반환한다. 기존 후보에는 support dependency와 정확한 source reference 구조가 있다. [PlacementAnalysis.java][S6], [CandidateSelections.java][S9]

### 순차 구문과 if/else

순차 구문은 앞 구문의 출력 관계를 다음 구문의 입력으로 전달한다. 값 재정의가 있으면 이전 값의 관계를 새 값에 자동으로 복사하지 않는다. map 보존 규칙이 있는 연산만 해당 관계를 전달한다.

같은 실행 시점의 join에 대해 다음 규칙을 사용한다.

```text
Aligned(merge_j(X_then, X_else), merge_j(Y_then, Y_else))
  requires Aligned(X_then, Y_then)
       AND Aligned(X_else, Y_else)
```

독립적인 join은 같은 순번끼리 대응시키지 않는다. 필요한 모든 가능한 조합에 대한 조건을 유지하거나 해당 질의를 미증명으로 남긴다. Nested if는 이 규칙을 내부 블록부터 합성한다. 조건식을 일반적으로 풀기 위한 SMT는 도입하지 않는다.

### Loop

Loop에서는 같은 정적 branch ID가 다른 반복에서도 같은 선택을 의미한다고 가정하지 않는다. 필요한 관계를 loop header의 현재 값들에 대한 불변식으로 계산한다.

```text
1. 요청된 관계와 그 증명에 필요한 관계를 수집한다.
2. entry에서 지지되는 관계와 source 선택 조건을 구한다.
3. 그 관계를 전제로 body의 순차 갱신 및 모든 branch를 분석한다.
4. 모든 backedge가 보존하는 관계만 유지한다.
5. 관계와 선택 의존성이 안정될 때까지 변경된 부분을 다시 계산한다.
6. 0회 반복 및 exit의 관계를 검증한다.
```

고정점은 유한한 관계 atom과 기존 occurrence·candidate 참조 위에서 계산한다. 임의 반복 횟수를 펼치거나 매 iteration마다 새 symbolic 식을 생성하지 않는다. 이미 방문한 쌍이라는 이유로 증명을 참으로 닫지 않는다. Entry의 실제 근거와 body의 보존 조건을 모두 만족해야 한다.

Nested loop는 안쪽의 관계 요약을 바깥 분석에 사용한다. 바깥의 가정 또는 관련 후보가 바뀌면 의존하는 안쪽 결과를 무효화한다. 불변식이 지지하는 것은 입력 쌍의 관계이지 항상 고정 pool을 사용한다는 주장이 아니다.

### 비재귀 함수

기존 call graph의 비순환 부분을 callee부터 분석한다. 함수 요약은 구체 pool A에 고정하지 않고 형식 인자에 대해 필요한 입력 관계와 보장되는 출력 map 관계를 기록한다.

```text
requires: Aligned(arg0, arg1)
ensures:  Aligned(result0, result1)
          Map(result0) follows Map(arg0)
          Map(result1) follows Map(arg1)
```

각 callsite에서 실제 인자를 대입한다. 여러 return은 같은 호출의 반환 tuple로 연결한다. 기존 함수 경계는 별도 pool 검사와 closure를 수행하므로, `LogicalBoundaryRealizations`에도 `VALUE_MAP` 전달 의미를 추가한다. TRead만 수정하고 함수 경계를 그대로 두지 않는다. [LogicalBoundaryRealizations.java][S10]

공유 함수 본문은 같은 static 후보 선택을 유지한다. 호출마다 비용이 좋은 다른 내부 계획을 사용한 것처럼 증명을 만들지 않는다. 이미 specialization된 함수만 기존 occurrence 구분에 따라 별도 선택할 수 있다. 자기 호출과 상호 재귀의 새로운 요약 고정점은 구현하지 않으며 기존 처리 및 진단을 변경하지 않는다.

## 후보 생성과 선택 조건

`exactTransientReplay`의 기존 common-witness 후보를 보존하고, 실제 writer 값의 map을 전달하는 `VALUE_MAP` reader를 추가한다. Reader의 각 source 연결은 그 source가 선택된 경로에서 해당 값의 map이 전달됨을 증명한다. source들 모두가 같은 concrete map을 가져야 한다고 다시 요구하지 않는다. [PlacementRelationClosure.java][S11]

새 reader의 FType, shape, privacy 및 coarse placement는 기존 계약과 일치해야 한다. consumer 후보에는 필요한 `RelationRequirement`를 붙인다. 출력이 입력 map을 계승하면 출력 realization도 해당 값 참조를 보존하여 downstream consumer가 사용할 수 있게 한다.

Runtime branch의 두 arm은 모두 지원해야 하는 조건이다. 반면 동일 producer의 여러 물리 후보는 planner가 선택하는 대안이다. 이 둘을 ordinary support clause의 OR로 섞지 않는다.

관계 분석의 결과를 다음처럼 구별한다.

| 결과 | 의미와 처리 |
| --- | --- |
| 증명됨 | 현재 선택 또는 명시한 source 선택 조건 아래 관계 성립 |
| 선택 대기 | 필요한 producer 선택이 남아 있으므로 조건을 보존 |
| 반례 확인 | 실제 가능한 경로에서 현재 후보 조합이 계약을 위반 |
| 분석 미완성 | 규칙이나 출처 정보가 부족하며 runtime 미지원의 증거가 아님 |

선택 대기를 실패로 처리하여 후보를 미리 지우지 않는다. 최종 plan의 새로운 관계 조건은 모두 증명되어야 한다. 구현 범위의 테스트에서 분석 미완성이 남으면 해당 단계는 완료가 아니다. 이를 runtime unsupported reason으로 바꾸거나 임의의 기존 후보를 삭제해서는 안 된다.

## 공통 물리 모델과 DP 연결

`ExactPhysicalModel`에 관계 hard factor를 추가한다. 기존 strict transient, function boundary 및 realization support factor와 같은 모델 안에서 처리한다. 해당 관계를 요구하는 consumer 후보가 선택된 경우에만 factor를 활성화한다. 그 외 후보에는 새 요구를 부과하지 않는다. 활성 factor는 소비자와 전이적으로 의존하는 모든 producer decision들을 scope로 가지며, 해당 tuple의 선택이 관계를 만족하면 0, 위반하면 무한 비용을 반환한다. Scope 밖의 mutable 선택 상태를 읽지 않는다. [ExactPhysicalModel.java][S12]

Branch별 조건은 가능한 경로 모두에 대한 AND로 구성한다. Branch 결과를 solver의 자유로운 선택 변수로 넣지 않는다. Producer 선택에는 기존 owner 일관성을 적용하여 같은 static owner가 서로 다른 증명에서 다른 realization을 선택하지 못하게 한다.

관계 factor는 lazy 평가와 기존 support factor 분해를 사용한다. A/A 및 B/B처럼 독립적인 경로별 조건으로 분해할 수 있으면 작은 factor를 결합한다. 본질적으로 여러 입력을 함께 봐야 하는 조건을 근거 없이 pairwise로 분해하지 않는다. 전역 J_v 테이블은 만들지 않는다.

`CandidateSelections`의 부분 선택 검증은 남은 조건을 유지하고, 최종 선택 검증은 동일한 관계 분석기로 완전히 평가한다. 모델 factor와 최종 validator가 별도의 의미를 갖지 않도록 질의 구현을 공유한다. [CandidateSelections.java][S9]

Relation identity, 값 출처와 필요한 source 선택은 candidate 및 alternative signature에 포함한다. 관계가 다른 상태를 local optimizer가 하나로 합치지 않도록 한다. `LocalPhysicalOptimizer`의 검색 알고리즘 자체를 교체하지 않는다. [LocalPhysicalOptimizer.java][S3]

## 비용 모델의 구체 처리

`VALUE_MAP`을 추가할 때 worker 수나 layout을 첫 source에서 임의로 가져오거나 비용을 0으로 처리하지 않는다. `physicalValueLayout`, worker 수 추정과 실행 layout 구성에 값 출처 질의를 연결한다. [ExactPhysicalCostModel.java][S13]

첫 구현에서는 다음 집계 규칙을 사용한다.

1. 같은 물리 비용 특성을 갖는 source 대안은 하나의 비용 요약으로 합친다.
2. 대안마다 worker 수나 partition 크기가 다르면 기존 연산 비용 함수를 각 필요한 layout 대안에 적용한다.
3. 실행 경로별 확률 모델은 새로 만들지 않고, 새 관계 후보의 해당 occurrence에는 그 추정값들의 최댓값을 사용한다.
4. 기존 occurrence frequency는 한 번만 곱한다. 배타적인 branch 비용을 동시에 실행한 것처럼 모두 더하지 않는다.
5. 이동은 선택된 명시적 action 또는 기존에 모델링된 runtime stage의 비용만 반영한다. 무재배치 관계에 가상의 공통 pool 이동을 추가하지 않는다.

이 최댓값은 기존 비용 추정치들의 보수적 집계이며 실제 실행시간의 수학적 상한을 주장하지 않는다. 기존 concrete 후보의 비용 정책은 바꾸지 않는다. 이렇게 얻은 비용 모델에 대한 선택과 실제 실행 성능은 별도로 검증한다.

Loop의 동적 shape와 범위에는 기존의 shape 및 비용 추정 규칙을 재사용한다. 비용용 요약을 legality의 concrete map 근거로 사용하지 않는다. `VALUE_MAP` 자체는 임의 CP→FOUT 업로드를 허용하는 새 anchor가 아니다. 이동이 필요한 경우 기존 action의 실제 anchor 및 authority를 계속 요구한다.

## Emission과 재컴파일에 보존할 정보

관계 requirement와 source 의존성을 support clause와 선택 receipt에 연결한다. 그 signature가 정규화된 plan hash와 emission prevalidation에 포함되게 한다. 기존 emission transaction의 atomic commit과 rollback 경로를 그대로 사용한다. [PlacementIdentity.java][S14], [PlacementEmissionTransaction.java][S15]

선택이 끝나면 전체 분석 그래프를 runtime에 전달하지 않는다. 다음 정도의 작은 `MapExecutionContract`로 낮춘다.

```text
consumer identity
required relation: input0와 input1의 ROW alignment
execution path: aligned path
unplanned relocation: forbidden
output map rule: 실제 실행 입력의 map을 계승
```

계약은 기존 program-owned `PlannerRecompileState`와 snapshot에 보존한다. Hop origin 및 signature를 통해 재컴파일된 instruction에 다시 연결한다. 같은 authority에 속한 반복과 호출에서도 actual map은 달라질 수 있으므로 concrete pool A로 계약을 덮어쓰지 않는다. [FederatedPlannerUtils.java][S16], [Recompiler.java][S17]

새 global registry는 추가하지 않는다. 구조가 바뀌는 rewrite와 instruction 교체는 기존 identity 전달 경로에서 계약도 함께 옮기도록 한다. 하나의 instruction으로 합쳐지는 계획은 필요한 계약들이 함께 성립하는지도 검증한다.

## Audit 설정과 무관한 runtime 검사

현재 `PlannerRuntimePlacementAudit`는 등록, lowering 검증 및 실행 검증에서 `isEnabled()`를 확인한다. 따라서 새 무재배치 계약을 여기에만 넣으면 audit가 꺼진 실행에서 보장이 사라진다. [PlannerRuntimePlacementAudit.java][S18]

계약 연결은 `Dag`의 instruction 생성 후 기존 owner/origin 정보를 사용하여 수행하고 optional audit의 early return과 분리한다. `Instruction`에 immutable 계약을 보관한다. `setLocation(Lop)`, `setPlannerLocation(Lop)`, `setLocation(Instruction)`의 초기화·복사 의미를 각각 확인하여 계약이 빠지지 않게 한다. [Dag.java][S19], [Instruction.java][S20]

`ProgramBlock`의 preprocess 이후 실행 경계에서도 program-owned authority가 계약을 요구하는지 확인한다. 계약을 요구하는 선택인데 instruction에 계약이 없으면 실행 전에 실패한다. 단순히 `contract != null`일 때만 검사하는 구현은 허용하지 않는다. 이 필수 누락 검사는 optional audit와 분리한다. [ProgramBlock.java][S24]

첫 binary 경로에서는 실제 입력을 얻은 뒤, 어떠한 원격 실행이나 broadcast를 시작하기 전에 선택 계약을 검사한다. alignment 불일치 시 계획 위반으로 종료하고 unaligned broadcast 경로로 진행하지 않는다. 이미 존재하는 alignment 연산을 재사용하고 runtime branch 이력을 기록하지 않는다. [BinaryMatrixMatrixFEDInstruction.java][S21]

출력은 실제로 실행에 사용한 입력 map의 worker와 range 관계를 검증한다. Kernel의 commutative operand swap 이후의 실제 입력 역할을 기준으로 계약을 해석하며, 원래 operand 순번을 무조건 고정하지 않는다. 새 결과의 federation data ID가 입력과 달라지는 정상 동작을 map 불일치로 판단하지 않는다. 계약 없는 기존 instruction의 지원 경로는 그대로 유지한다. 새 관계 기반 plan에는 계약 누락을 허용하지 않는다.

## 파일별 수정 책임

| 파일 또는 영역 | 구현할 변경 |
| --- | --- |
| 신규 `placement/PlacementValueFlow.java` | 값 출처와 merge 및 loop 연결, 함수 parameter/result 참조 |
| 신규 `placement/PlacementRelationAnalysis.java` | 관계 질의, 증명 의존성, loop 고정점, 비재귀 함수 요약 |
| `PlacementProgramFacts` | 기존 facts 생성 시 value-flow 구성 |
| `PlacementIdentity`, `PlacementAnalysis` | typed `VALUE_MAP`, relation requirement와 canonical identity |
| `PlacementRelationClosure`, `PlacementSupportRelations` | 새 reader 및 관계를 가진 consumer와 support 유지 |
| `LogicalBoundaryRealizations` | 함수 경계에서 값 map과 공동 반환 관계 전달 |
| `CandidateSelections`, `ExactPhysicalModel` | 선택 대기 조건과 최종 증명, shared hard factor |
| `ExactPhysicalCostModel` | 새로운 배치의 layout 및 비용 요약 |
| `PlacementEmissionTransaction` | hash와 검증 및 계약 commit/rollback |
| `FederatedPlannerUtils`, `Recompiler` | program-owned 계약 snapshot과 origin 복원 |
| `Dag`, `Instruction`, `ProgramBlock`, 관련 FED instruction | 항상 수행되는 계약 연결, 누락 검사와 실제 map 검사 |
| `PlannerRuntimePlacementAudit` | 기존 감사에 계약 관측 추가, production enforcement와 구별 |

파일 수를 줄이려고 모든 처리를 `PlacementRelationClosure`에 넣지 않는다. 새 개념은 두 분석 파일에 모으되, 기존의 각 경계는 자기 계약만 확장한다. 행 수나 일정의 확정 추정치는 코드 변경 후 차이를 확인하기 전에는 제시하지 않는다.

## 단계별 구현과 통과 기준

| 단계 | 작업 | 다음 단계로 가기 위한 기준 |
| --- | --- | --- |
| 1 | 현재 후보 손실 재현과 nonconstant correlated/independent fixture 작성 | 같은 변수별 source 집합에서 공동 관계 차이를 확인 |
| 2 | value-flow와 `VALUE_MAP` 및 기본 관계 질의 | nested branch, 미갱신 arm, overwrite의 출처와 관계 단위 테스트 통과 |
| 3 | support, shared factor, signature 및 비용 연결 | DP 선택과 최종 validator가 같은 선택 조합을 승인·거절 |
| 4 | emission, instruction 계약, runtime 검사 | 단순 AA/BB의 실제 무재배치 실행과 audit off 검사 통과 |
| 5 | loop와 비재귀 함수 요약 및 recompile 연결 | nested 조합, 0/1/다회 loop, 두 callsite, recompile on/off 통과 |
| 6 | 회귀와 planning 부담 측정 | 기존 후보 보존, 수치·map·전송 검증, 분석 비용 기록 |

단계 4의 branch 예제만 성공한 상태를 최종 완료로 취급하지 않는다. 사용자가 요청한 중첩 loop와 비재귀 함수는 단계 5의 필수 완료 조건이다. 다른 planner의 광범위한 정상화는 이번 작업에서 별도로 벌이지 않되, 공통 모델 수정에 대한 회귀는 수행한다.

## 테스트와 E2E 증거

새 테스트 명칭은 제안이며 구현 시 생성한다. `PlacementValueFlowTest`, `PlacementRelationAnalysisTest`, `CorrelatedPhysicalFactorTest`, `CorrelatedPlanningContractTest`로 분석·선택·실행 계약을 분리하고, DML fixture는 중첩 구조를 실제 parser와 compiler로 통과시킨다.

| ID | 테스트 | 필수 assertion |
| --- | --- | --- |
| T1 | 동일 if에서 AA/BB 선택 | 양쪽 정의가 남은 graph, 무재배치 후보 존재, 선택된 FED consumer의 두 실제 실행 |
| T2 | 독립 if에서 X/Y 선택 | AA/AB/BA/BB 중 AB/BA를 누락하지 않고 forced aligned 후보 조합 거절 |
| T3 | nested if와 한쪽 미갱신 | entry source 포함, predecessor pairing 보존 |
| T4 | 같은 coarse placement의 producer 후보 교체 | relation factor 결과 및 plan identity가 적절히 바뀜 |
| T5 | loop 0회와 1회 및 여러 회 | entry와 exit 및 모든 backedge 관계 유지 |
| T6 | 반복 중 A에서 B로 갔다가 A로 복귀 | 첫 pool 고정이나 반복 사이 selector 혼동 없음 |
| T7 | X 갱신 직후 Y 갱신 전 consumer | 그 지점의 실제 비정렬을 잘못 승인하지 않음 |
| T8 | 함수 안의 loop와 if, 호출 안의 호출 | 비재귀 함수 요약의 합성 및 인자·반환 위치 일치 |
| T9 | 공유 본문에 AA와 BB를 전달하는 두 callsite | 내부 선택 일관성, 호출별 actual map 보존 |
| T10 | 재컴파일과 audit 각각 on/off | 네 조합에서 계약 보존, audit off에서도 위반 전송 차단 |
| T11 | 잘못된 actual map 및 계약 누락 주입 | 원격 실행이나 broadcast 전에 실패, 조용한 보정 없음 |
| T12 | 기존 concrete 계획 회귀 | 후보·authority와 결과 보존, 관계 없는 기존 경로의 불필요한 변경 없음 |
| T13 | preprocess 교체와 commutative operand swap | 물리 입력 역할과 출력 map 계승 계약 유지 |
| T14 | 두 호출의 내부 선택 요구가 충돌 | 공유 body에 서로 다른 static 선택을 사용한 것처럼 승인하지 않음 |
| T15 | A/B worker 수 차이와 nested branch 비용 | leaf 비용을 집계한 후 frequency를 한 번 적용하고 pool 순서 변경에 불변 |

T1의 branch 조건은 컴파일 시 상수로 제거되지 않도록 만들고 실제 graph에서 양쪽 arm이 살아 있는지 검사한다. 수치 결과만으로 성공을 선언하지 않는다. source hash, 선택 receipt, 생성 instruction과 계약, 입력·출력 map, 실제 전송 내역을 함께 보존한다.

후보 존재와 cost-based 선택을 구분한다. 새 후보를 정확히 검증하는 forced-selection 단위 검사와, 비용 조건을 통제한 production DP 선택 검사를 둘 다 둔다. DP local search의 선택을 전역 최적성 증명으로 해석하지 않는다.

Privacy는 해당 연산을 허용하는 보호된 fixture로 구성한다. 기존 PUBLIC-only ignore 규칙을 우회하지 않는다. 실제 federated 실행은 다음 Docker 경로로 한정한다.

## 실행 명령과 산출물 계획

기존 DP 및 선택 인코딩 회귀를 먼저 실행한다. 아래는 기존 클래스에 대한 예정 명령이며 이 문서 작성 중 실행하지 않았다.

```bash
mvn -Dmaven.test.skip=false -DskipTests=false \
  -Dtest=FederatedPlanLocalCostIntegrationTest,ExactPhysicalRealizationSupportFactorCacheTest,LocalCategoricalOptimizerTest,LocalPhysicalOptimizerIncrementalTraceTest,CandidateSelectionPruningOracleTest,ExactPhysicalWorkerCountTest \
  test
```

기존 DP 통합 테스트는 선택 및 emission 검증의 시작점으로 사용한다. 새 correlated E2E가 이미 있다는 뜻은 아니다. [FederatedPlanLocalCostIntegrationTest.java][S22]

현재 `run_LAN_docker.sh`의 명시적 correctness lane은 greedy 검증용이므로, 새 테스트 전용의 작은 `--correlated-validation` lane과 `validate_correlated_planning_docker.py`를 추가한다. 기존 Docker lease와 artifact 기록 유틸리티를 재사용하고 진행 중인 다른 실험을 건드리지 않는다. 이미 존재하는 Docker image를 지정하고 새로운 image pull이나 host 실행 경로를 도입하지 않는다. [run_LAN_docker.sh][S23]

```text
구현할 실행 인터페이스:
run_LAN_docker.sh --correlated-validation
    --image <기존 로컬 검증 이미지>
    --planner COMPILE_COST_BASED
```

이 lane은 격리된 작은 worker pool A/B/C로 테스트 fixture를 실행하고 source/JAR hash, 설정, scenario별 수치 결과, 선택 계약, 실제 map, 전송 및 성공·실패 이유를 저장한다. 무거운 성능 campaign을 자동 실행하지 않는다. 신규 lane과 위 옵션은 아직 구현되지 않았다.

Planning 부담은 value-flow node 수, 질의 수, cache 재사용, proof node와 relation dependency/factor 수, 최대 factor scope와 cell 수, 시간과 메모리를 기록한다. Loop 횟수만 바꾼 fixture에서는 분석 node와 요약 크기가 반복 횟수만큼 늘어나지 않는지 확인한다. Cache eviction은 재계산만 유발해야 하며 가능한 실행이나 후보를 버리는 근거가 되어서는 안 된다. 선형 시간이나 일정 비율의 성능 개선을 미리 주장하지 않는다.

## 주요 위험과 대응

| 위험 | 대응과 검증 |
| --- | --- |
| 후보의 OR와 runtime 경로의 AND 혼동 | typed relation 조건과 T2/T4로 검증 |
| loop 순환 자기 증명 | entry 근거 및 body 보존을 요구하고 T5/T7로 검증 |
| 다른 호출의 값 혼합 | formal 위치와 callsite identity를 보존하고 T8/T9로 검증 |
| 최종 선택과 무관한 증명 재사용 | 후보 의존성을 signature와 cache key에 포함하고 T4로 검증 |
| audit off에서 계약 소실 | production binding/enforcement를 분리하고 T10/T11로 검증 |
| 첫 pool 또는 0 비용 사용 | 실제 layout 대안 집계와 worker 수가 다른 비용 단위 검사 |
| 분석 미완성을 runtime 미지원으로 처리 | 별도 진단과 범위 내 미완료 판정, 기존 후보 보존 |
| 재컴파일·복사·rollback에서 계약 누락 | 기존 owner snapshot과 instruction 교체 회귀에 계약 검사 추가 |

최종 완료는 DP에서 범위 내 중첩 if/loop/비재귀 함수 DML이 파싱부터 실행까지 통과하고, 독립 분기 및 위반 주입 대조군이 안전하게 처리되며, 기존 후보와 계약이 보존된 상태다. 재귀 함수 지원, 전역 J_v 열거 또는 범용 경로 해석기는 이 완료 조건에 필요하지 않다.

## 코드 근거와 관련 문서

행 번호는 문서 작성 시점 작업 트리 기준이다. 제안한 신규 파일과 API는 코드 근거 링크와 구별하여 본문에 표시했다.

- [계획 공간의 완전성 분석](CORRELATED_EXECUTIONS_PLAN_COMPLETENESS_2026-10-06_KO.md)
- [E2E 계약 설계](CORRELATED_PLANNING_E2E_DESIGN_2026-10-06_KO.md)
- [경량 구현 방향](CORRELATED_PLANNING_LIGHTWEIGHT_DESIGN_2026-10-06_KO.md)

[S1]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/ipa/FederatedPlannerFactory.java:44
[S2]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java:44
[S3]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java:121
[S4]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java:138
[S5]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java:400
[S6]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java:1295
[S7]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/parser/DMLTranslator.java:526
[S8]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:6780
[S9]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java:1424
[S10]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/LogicalBoundaryRealizations.java:426
[S11]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4660
[S12]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:1116
[S13]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java:1031
[S14]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java:940
[S15]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java:125
[S16]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerUtils.java:101
[S17]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/recompile/Recompiler.java:507
[S18]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAudit.java:328
[S19]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/lops/compile/Dag.java:222
[S20]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/Instruction.java:265
[S21]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixMatrixFEDInstruction.java:112
[S22]: /home/mchoi/w1357-paper-aligned-refactor/src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCostIntegrationTest.java:16
[S23]: /home/mchoi/w1357-paper-aligned-refactor/scripts/fedplanner/run_LAN_docker.sh:37
[S24]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/controlprogram/ProgramBlock.java:238
