# SystemDS 상관된 실행과 물리 계획 공간의 완전성 분석 보고서

작성일: 2026-10-06

이 보고서는 **현재 per-variable universal compatibility가 실제로 가능한 correlated executions보다 강한 제약을 만들어 legitimate physical plans를 제거하는가**라는 질문에 답한다. 분석 기준은 `/home/mchoi/w1357-paper-aligned-refactor`의 HEAD `0b51cc3ef8`와 분석 당시의 미커밋 수정이 포함된 작업 트리다.

**판정은 그렇다는 것이다.** 현재 common-reader replay는 모든 reaching writer에 공통 물리 layout 또는 worker endpoints의 근거를 요구한다. 그러나 runtime에는 실행 시점의 입력 map을 따라 동작하는 FED 연산 경로가 존재한다. 각 분기 안에서는 입력들이 정렬되지만 분기 사이에는 pool이 다른 경우, runtime이 처리할 수 있는 무재배치 계획을 현재 reader 표현이 담지 못하는 구조적 반례가 있다.

이 판정은 planner의 배제 조건과 runtime의 지원 경로를 대조한 **코드 수준의 구조적 분석**이다. 특정 DML을 전체 compiler와 runtime으로 실행하여 어떤 대체 계획이 선택되는지 또는 얼마나 느려지는지를 측정한 결과는 아니다.

## 판단 요약

| 질문 | 판정 |
| --- | --- |
| 현재 제약이 실제 실행에 필요한 조건보다 강할 수 있는가 | 그렇다. 모든 실행의 안전성과 모든 실행에 공통 map이 존재한다는 조건은 다르다 |
| 코드로 뒷받침되는 반례가 있는가 | 같은 분기에서 X/Y가 함께 pool A 또는 B를 선택하는 FED 덧셈 사례 |
| 제거되는 것은 무엇인가 | 분기별 원본 map을 그대로 사용하는 무재배치 물리 계획 |
| 프로그램 전체가 반드시 실패하는가 | 아니다. local 또는 relocation을 사용하는 다른 계획이 남을 수 있다 |
| 전칭 검사를 존재 검사로 바꾸면 되는가 | 아니다. 모든 실제 실행을 지원해야 한다는 조건은 유지해야 한다 |
| coarse SAME_PLACEMENT를 전부 제거해야 하는가 | 아니다. 반례는 양쪽 모두 FED/FOUT/ROW로 coarse 상태가 같다 |
| J_v를 저장하는 것만으로 해결되는가 | 아니다. reader의 map 표현과 consumer의 공동 입력 검증도 관계를 사용해야 한다 |
| 앞선 구현 보류 권고는 충분했는가 | 부족했다. 제한된 표현 내부의 동치를 그 표현의 충분성 근거로 사용할 수 없다 |

## 핵심 용어와 비교 범위

**Reaching definition**은 어떤 읽기 위치까지 덮어써지지 않고 도달할 수 있는 정의다. **Correlated execution**은 여러 입력의 공급자가 독립적으로 정해지는 것이 아니라 같은 분기 선택 등에 의해 함께 정해지는 실행이다.

**Reader realization**은 읽기 위치의 실행·출력 상태와 물리 배치에 관한 구체적인 구현 근거다. **Witness**는 해당 물리 배치나 연산 가능성을 정당화하는 증거이며, 이 보고서에서는 특히 worker pool과 partition layout의 근거를 뜻한다.

이 보고서의 **legitimate plan**은 프로그램의 값 의미를 보존하고, 필요한 입력 배치·연산 지원·privacy 조건을 만족하며, 허가되지 않은 암묵적 이동 없이 실행할 수 있는 계획을 뜻한다. 단지 “현재 후보 테이블에 들어 있다”는 의미로 정의하지 않는다. 그렇게 정의하면 후보 표현 자체의 완전성 문제를 논할 수 없기 때문이다.

여기서 비교하는 것은 다음 두 범위다.

- **Runtime이 지원하는 계획 범위:** 실제 제어 흐름에서 발생하는 입력들을 runtime 연산으로 올바르게 처리할 수 있는 계획.
- **현재 planner가 표현하고 검증하는 계획 범위:** 공통 reader 및 물리 witness 구조로 표현되어 후보·선택·emission 검사를 통과할 수 있는 계획.

현재 표현 안에서 모든 제약을 만족시키는 것과, runtime이 지원하는 모든 좋은 계획을 그 표현 안에 담는 것은 별개의 문제다.

## 구조적 반례

### 전제

다음은 반례를 분리하기 위한 설명용 프로그램이다. 실행 측정 결과가 아니라 소스 경로를 분석하기 위한 사례다.

1. `X_A`, `Y_A`, `X_B`, `Y_B`의 크기와 데이터 타입은 같다.
2. 네 입력은 모두 `FED/FOUT/ROW`로 공급된다.
3. `X_A`와 `Y_A`는 pool A의 같은 partition 경계와 대응 worker에 배치된다.
4. `X_B`와 `Y_B`는 pool B에서 같은 방식으로 정렬된다.
5. A와 B의 worker endpoints는 서로 다르다.
6. 조건 `c`는 컴파일 시 한쪽 arm으로 제거되지 않는다.
7. 덧셈과 FED 출력은 해당 입력의 정책상 허용되며, 원본 map을 그대로 전달하는 계획을 비교한다.

```text
if (c) {
    X = X_A;
    Y = Y_A;
} else {
    X = X_B;
    Y = Y_B;
}
Z = X + Y;       // 동일한 정적 FED/FOUT/ROW 명령
```

### 실제 공동 입력 관계

\[
J_Z=\{(X_A,Y_A),(X_B,Y_B)\}.
\]

| 입력 쌍 | 실제 발생 여부 | 입력 간 정렬 |
| --- | --- | --- |
| X_A, Y_A | c가 참일 때 발생 | 정렬됨 |
| X_B, Y_B | c가 거짓일 때 발생 | 정렬됨 |
| X_A, Y_B | 발생하지 않음 | 정렬을 요구할 이유가 없음 |
| X_B, Y_A | 발생하지 않음 | 정렬을 요구할 이유가 없음 |

필요한 것은 각 실제 실행에서 X와 Y가 서로 정렬된다는 사실이다. 서로 배타적인 실행의 `X_A`와 `X_B`가 같은 pool이어야 할 이유는 없다.

## Runtime이 이 계획을 처리할 수 있는 근거

### 값의 전달은 실제 객체와 map을 보존한다

`VariableCPInstruction.processCopyInstruction`은 현재 source 변수의 `Data` 객체를 가져와 destination에 그대로 설정한다. 따라서 참 경로에서는 A의 실제 map이, 거짓 경로에서는 B의 실제 map이 X와 Y에 전달될 수 있다. 그 과정에 서로 다른 경로의 map을 하나로 통합해야 한다는 runtime 동작은 없다. [R1]

이것은 TWrite/TRead identity 계약을 어기는 것이 아니다. **각 실행에서 실제로 선택된 정의의 값을 그대로 읽는다**는 계약을 지킨다. 서로 다른 실행에서 같은 변수 이름이 같은 물리 map을 가져야 한다는 조건은 그 계약에 포함되지 않는다.

### FED 덧셈은 현재 입력 map을 사용한다

`BinaryMatrixMatrixFEDInstruction.processInstruction`은 매 실행마다 ExecutionContext에서 현재 두 입력 객체를 읽는다. 이어서 두 FederationMap이 aligned이면 현재 입력의 ID와 map으로 worker 명령을 보낸다. 이 aligned 경로는 broadcast나 download를 수행하지 않는다. [R2]

`FederationMap.isAligned`는 비교 중인 두 map의 범위와 worker 주소를 확인한다. 검사 대상은 그 실행의 X와 Y다. 참 경로의 X와 거짓 경로의 X를 비교하는 검사가 아니다. [R3]

출력 map도 현재 federated 입력 map의 `copyWithNewID`를 통해 구성된다. 따라서 같은 명령이 A/A 입력으로 실행되면 A를 따르는 출력이, B/B 입력으로 실행되면 B를 따르는 출력이 생성될 수 있다. [R4]

이 경로에서는 경로별 명령 복제나 계획 실패 후의 runtime fallback이 필요하지 않다. 같은 정적 FED 명령이 자신에게 전달된 실제 입력 map을 사용하는 정상 실행이다.

## Planner가 무재배치 계획을 놓치는 지점

### Durable map의 공통 seed 요구

`exactTransientReplay`는 reader가 받을 수 있는 source들을 모은 뒤, 물리 seed 하나마다 모든 source의 지원을 찾는다. durable-map 경로에서는 source realization의 anchor가 seed와 `samePhysicalLayout`인지 검사한다. 모든 source의 지원이 모인 경우에만 reader 대안을 생성한다. [P1]

X의 원본 map을 그대로 유지하는 계획에서는 다음과 같다.

```text
seed A:
    X_A의 직접 공급 → 지원
    X_B의 직접 공급 → 미지원

seed B:
    X_A의 직접 공급 → 미지원
    X_B의 직접 공급 → 지원
```

어느 seed에도 every-source 지원이 모이지 않으므로, X의 A/B 대안을 그대로 보존하는 reader가 만들어지지 않는다. Y도 같다. 이는 consumer의 덧셈 정렬 검사를 하기 전에 reader 표현에서 발생하는 제한이다.

source를 A 또는 B로 명시적으로 재배치하는 후보가 있다면 공통 witness를 만들 수 있을 수 있다. 그러나 그 후보는 원래의 무재배치 계획과 다르므로, 대체 후보의 존재가 원래 계획의 보존을 뜻하지는 않는다.

### Dynamic native layout도 다른 endpoints의 union은 아니다

native replay는 물리 geometry를 더 유연하게 다루는 경로를 갖지만, `replayNativeWitness`는 exact 모드에서 동일 layout을, dynamic 모드에서도 동일 worker endpoints를 요구한다. [P2]

`NativePlacementContinuity`의 `withDynamicPartitionRanges` 역시 endpoints를 그대로 유지한다. `matches`는 dynamic range 여부와 관계없이 endpoints가 다르면 거절한다. 따라서 range가 동적이라는 사실만으로 pool A/B의 disjunction이 표현되지는 않는다. [P3]

reader 후보가 남지 않으면 `exactTransientReplay`는 `no-all-definition-compatible-realization`을 반환한다. 이후 대안 병합은 이미 이 조건을 통과한 대안을 합치므로, 실패한 source별 A/B를 새로 guarded union으로 승인하는 역할은 아니다. [P4]

### 최종 검증도 공통 reader 계약을 유지한다

`validateReachingDefinitionSupportForSlot`은 writer별 compatibility에서 가능한 reader realization을 모아 교집합한다. 교집합이 비면 오류를 발생시키고, 일부 writer만 지원하는 reader도 허용하지 않는다. 이는 현재 표현의 계약을 일관되게 검증하는 장치지만, 그 표현이 runtime의 모든 합법 계획을 포괄한다는 증거는 아니다. [P5]

## 전칭 조건과 공통 witness 조건의 차이

고정된 정적 계획을 q, 실제 공동 입력 튜플을 t, 그 입력 값으로부터 얻는 물리 map의 근거를 W_t라고 하자. 반례에 필요한 조건은 다음 형태다.

\[
\exists q\;\forall t\in J_Z\;\exists W_t:
ActualWitness(t,W_t)\land Executable(q,t,W_t).
\]

`ActualWitness`는 증거를 임의로 발명하는 것이 아니라, 해당 튜플이 실제로 전달하는 map과 일치해야 한다는 조건이다. q는 모든 튜플에 동일하다. 경로마다 아무 계획이나 새로 선택하는 것을 허용하는 식이 아니다.

반면 고정 공통 pool을 요구하는 표현은 이 반례에서 다음처럼 더 강한 형태가 된다.

\[
\exists q,W\;\forall t\in J_Z:
CommonWitness(t,W)\land Executable(q,t,W).
\]

앞의 식에서는 W_A와 W_B가 각각 참·거짓 실행의 실제 map을 가리킬 수 있다. 뒤의 식에서는 A/B가 모두 같은 W를 지원해야 한다. A와 B의 endpoints가 다르면 뒤의 조건은 성립하지 않는다.

따라서 문제를 “모든 source를 검사해서 생겼으니 하나만 만족하면 통과시키자”로 고치면 안 된다. **모든 실제 실행을 지원해야 한다는 전칭 조건은 보존하고, 그 실행을 표현하는 witness의 관계성을 확장해야 한다.**

## Soundness와 completeness에 대한 의미

### 보수적 안전 조건이 완전성을 보장하지는 않는다

공통 witness 요구는 지원되지 않는 map을 임의로 가정하는 일을 막는다. 하지만 충분히 강한 안전 조건이 있다는 사실만으로 실행 가능한 모든 계획을 보존한다고 결론낼 수는 없다. 이 반례에서는 공통 witness가 필요조건으로 취급되면서, 실제로 필요한 per-execution alignment보다 강한 제약이 된다.

이는 현재 planner 전체의 soundness를 새로 증명하거나 부정하는 주장이 아니다. 확인한 것은 해당 표현과 검증이 **plan-space completeness를 제한하는 한 구체적인 방식**이다.

### Exact 최적화의 범위

현재 모델에 인코딩된 후보 집합에서 Exact solver가 최적해를 찾더라도, runtime이 지원하는 더 좋은 계획이 후보 표현에서 빠졌다면 runtime 전체 계획 공간의 최적해를 찾은 것은 아니다.

논문에서 최적성을 주장할 때에는 적어도 다음 범위를 구분해야 한다.

- 공통 reader 제약으로 제한된 encoded plan space 안의 최적성.
- 프로그램의 correlated executions를 고려한 runtime-supported plan space에 대한 완전성.

특정 workload에서 실제 손해가 얼마나 큰지는 별도 측정해야 한다. 구조적 반례만으로 모든 workload가 느려진다거나 일정 비율의 비용 손실이 있다고 주장할 수는 없다.

## 앞선 판단에서 수정해야 하는 부분

앞선 보고서의 조건부 동치식은 reader 표현과 입력별 공급자 집합을 고정했을 때, J_v pairing을 추가해도 per-variable 검사의 결과가 같을 수 있음을 설명했다. 그 수식의 전제 안에서는 타당하다.

그러나 이것은 **제한된 모델 내부에서 정보가 추가로 쓰이지 않는다는 사실**이다. 모델 자체가 correlated plan을 표현하지 못한다면, 그 사실을 “상관관계 보존이 불필요하다”는 근거로 사용할 수 없다.

따라서 권고를 다음처럼 수정한다.

| 이전 판단의 한계 | 수정된 판단 |
| --- | --- |
| 공통 reader를 유지하면 J_v만 추가해도 효과가 없을 수 있으므로 구현 보류를 우선 권고 | 공통 reader 자체의 completeness 손실을 먼저 검증하고, 그 제한을 푸는 관계적 표현을 검토 |
| 논문에서 미구현 J_v 주장을 빼면 정리가 됨 | 미구현 주장을 정정하되 현재 plan space의 제한도 밝혀야 함 |
| J_v를 정밀도·planning 비용의 선택적 개선으로만 평가 | runtime-supported legitimate plans의 보존 문제로도 평가 |

## 수정할 대상과 유지할 계약

반례는 모든 경로가 FED/FOUT/ROW이므로 coarse `SAME_PLACEMENT` 동등성을 이미 만족한다. 이 제약을 일괄 삭제할 필요는 없다. 또한 cpvar가 실제 값을 보존한다는 identity 계약이나 명시적 이동의 authority를 완화할 이유도 없다.

검토할 대상은 **reader의 물리 map 표현과 여러 입력의 대안이 함께 선택되는 관계**다.

```text
X의 배치: c이면 A, 아니면 B
Y의 배치: c이면 A, 아니면 B

공유 조건 c에 의해:
    A/A 또는 B/B만 허용
```

이를 표현하는 방법이 반드시 모든 J_v 튜플의 명시적 열거일 필요는 없다. guarded alternatives, 공유 branch selector, factorized 관계 등도 비교할 수 있다. 핵심은 같은 조건에 의존하는 대안의 pairing을 잃지 않는 것이다.

| 영역 | 필요한 방향 |
| --- | --- |
| CFG facts | 함께 도달할 수 있는 정의 관계 또는 이를 보존하는 guard 표현 |
| Reader realization | 고정 pool 교집합만이 아니라 실제 값과 연결된 대안적 map 표현 |
| Consumer feasibility | 모든 실제 공동 입력에서 alignment와 연산 지원을 증명 |
| 출력 배치 | 선택된 입력 관계를 따라 출력 map의 조건을 전달 |
| 비용 모델 | 경로별 이동·실행 비용과 공유·재사용의 과계상 방지 |
| 선택과 emission | 하나의 정적 계획이 해당 관계를 실제로 유지하는지 검증 |
| 기존 안전 계약 | privacy, 값 identity, 명시적 변환 authority 및 무근거 fallback 금지는 유지 |

reader의 표현 확장과 다중 입력의 correlation은 구분해야 한다. 단일 입력에서는 입력 사이의 pairing 자체가 없지만 map 대안 표현은 여전히 관련될 수 있다. 다중 입력에서는 대안을 독립적인 집합으로만 넓히면 발생하지 않는 A/B 조합이 다시 나타나므로 공동 관계가 추가로 중요하다.

## 최소 검증 계획과 수용 기준

### 상관된 분기와 독립적인 분기

대조군은 X와 Y를 서로 다른 조건으로 선택한다.

```text
if (c) { X = X_A; } else { X = X_B; }
if (d) { Y = Y_A; } else { Y = Y_B; }
Z = X + Y;
```

이 경우에는 A/A, A/B, B/A, B/B가 모두 가능하다. 상관된 분기와 변수별 source 집합은 같지만, 재배치 없이 aligned 경로만 실행할 수 있다는 증명은 성립하지 않는다.

| 실험 | 수용 기준 |
| --- | --- |
| 현재 planner의 상관된 분기 | 원본 map을 유지한 reader 및 consumer 후보가 어디서 사라지는지 기록 |
| 관계적 표현을 사용하는 상관된 분기 | A/A와 B/B 모두 같은 FED 명령의 aligned 경로로 실행하며 불필요한 재배치가 없음 |
| 독립적인 분기 | A/B와 B/A를 잘못 제외하지 않으며, 필요 시 명시적 이동을 계획하거나 무재배치 후보를 거절 |
| Loop | 초기값, backedge, 0회 반복 및 갱신 사이의 사용을 구분하여 실제 튜플을 누락하지 않음 |
| 함수 호출 | 호출별 argument와 return 관계를 혼합하지 않음 |
| 최종 적용 | 후보 선택, lowering, runtime audit와 실제 출력 map이 같은 관계를 따름 |

실험에서는 planning 시간·메모리·후보 수·factor 크기와 실제 전송량·실행시간을 구분해 측정해야 한다. 프로그램 전체가 성공했는지만 확인하면, 불필요한 relocation으로 우회한 계획과 원래의 무재배치 계획을 구별할 수 없다. runtime 검증은 저장소 규칙에 따라 동일 Docker 조건에서 수행한다.

## 확인된 사실과 미확인 사항

| 항목 | 상태 |
| --- | --- |
| Durable replay가 모든 source의 공통 layout 지원을 요구 | 소스 확인 |
| Native dynamic range 표현도 다른 endpoints의 union을 허용하지 않음 | 소스 확인 |
| FED binary aligned 경로가 현재 입력 map으로 직접 실행 | 소스 확인 |
| 위 전제의 A/A 또는 B/B 원본 map 계획이 common-witness 표현과 양립하지 않음 | 소스 조건에서 도출한 구조적 반례 |
| 실제 DML 전체 경로의 후보 배제와 최종 대체 계획 | 새 실행으로 재현하지 않음 |
| 개선 후 성능 이득과 planning 비용 | 미측정 |
| 모든 연산과 모든 제어 흐름에 적용할 최종 관계 표현 | 미설계 |

독립적인 planner 및 runtime 코드 검토로 위 경로를 대조했다. 기존의 branch 관련 테스트 일부는 `@Ignore`이므로 테스트 존재를 새 PASS 증거로 취급하지 않았다. 이 문서 작성으로 production code, 테스트 또는 논문 원본을 변경하지 않았다.

## 코드 근거

행 번호는 분석 시점의 작업 트리 기준이다.

| 참조 | 파일과 함수 | 역할 |
| --- | --- | --- |
| R1 | [VariableCPInstruction.java 1030행][R1] | `processCopyInstruction`, 실제 Data 객체 전달 |
| R2 | [BinaryMatrixMatrixFEDInstruction.java 73행][R2] | 현재 입력 조회와 aligned FED 실행 |
| R3 | [FederationMap.java 325행][R3] | 실행 중인 입력 쌍의 범위 및 주소 alignment |
| R4 | [BinaryMatrixMatrixFEDInstruction.java 285행][R4] | 현재 입력 map에 기반한 출력 map |
| P1 | [PlacementRelationClosure.java 4660행][P1] | durable seed에 대한 every-source samePhysicalLayout |
| P2 | [PlacementRelationClosure.java 4776행][P2] | native replay 공통 witness |
| P3 | [NativePlacementContinuity.java 3506행][P3] | dynamic range에서도 endpoints 유지 및 비교 |
| P4 | [PlacementRelationClosure.java 4733행][P4] | 공통 realization 부재의 거절 |
| P5 | [PlacementAnalysis.java 3631행][P5] | writer별 reader realization 교집합 검증 |

관련 문서: [Joint Input Dependencies 도입 판단](/home/mchoi/w1357-paper-aligned-refactor/docs/JOINT_INPUT_DEPENDENCIES_ADOPTION_REVIEW_2026-10-06_KO.md), [블록 간 의존성 분석](/home/mchoi/w1357-paper-aligned-refactor/docs/CROSS_BLOCK_AND_JOINT_INPUT_DEPENDENCIES_2026-10-06_KO.md).

[R1]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/cp/VariableCPInstruction.java:1030
[R2]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixMatrixFEDInstruction.java:73
[R3]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationMap.java:325
[R4]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixMatrixFEDInstruction.java:285
[P1]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4660
[P2]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4776
[P3]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:3506
[P4]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4733
[P5]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java:3631
