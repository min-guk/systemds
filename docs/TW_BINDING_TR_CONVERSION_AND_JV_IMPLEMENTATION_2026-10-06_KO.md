# TW–Binding–TR 보존, 분기별 변환 후보와 J_v 구현 방안

작성일: 2026-10-06. 분석 기준: `/home/mchoi/w1357-paper-aligned-refactor`, HEAD `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592` 및 현재 미커밋 변경. 아래 자료구조와 새 처리 단계는 구현 제안이다. 이번 작업은 소스 확인과 문서 작성이며, 기능 구현이나 runtime 검증 결과가 아니다.

**TW → Binding → TR의 순수 전달 구간에는 보존 제약을 유지한다. 배치가 다른 값을 전달하려면 명시적 변환 결과를 새 공급자로 연결한다. 변환은 최적화 전에 후보로 고려하고, 선택된 변환만 실제 코드에 삽입한다. `J_v`는 여러 입력이 함께 도달하는 관계를 저장하여 공동 합법성을 검사하는 분석 자료구조로 구현한다.**

| 질문 | 답변 |
|---|---|
| 1. TW → Binding → TR은 같은 `<CP,LOUT>` 또는 `<FED,FOUT>`이어야 하는가? | 맞다. TW/TR 자체가 두 canonical 상태를 지키는 순수 전달 구간이라는 전제다. 일반 계산 생산자의 exec까지 같게 묶지 않으며, 실행별 실제 map은 보존한다. |
| 2. If-else에서 배치가 맞지 않으면 명시적 변환을 두는가? | 맞다. 해당 source/target 변환이 합법인 후보에서만 적용하며, 변환 결과가 합류의 새 공급자가 된다. |
| 2-1. 양쪽에 변환을 항상 넣는가? | 양쪽 incoming edge에 필요한 선택지를 검토하지만, identity를 포함해 최적화하고 선택된 이동만 실행 코드로 만든다. |
| 2-1. 결국 LOUT/FOUT 중 하나로 통일해야 하는가? | 현재처럼 공통 TR 하나의 상태를 선택하는 모델에서는 변수별로 통일한다. 모든 변수나 모든 분기의 원본까지 같은 상태여야 한다는 뜻은 아니다. |
| 3. J_v는 어떻게 구현하는가? | CFG에서 공동 정의 관계를 보존하고, consumer별 tuple에 대해 선택된 후보·변환·물리 입력의 호환성을 검사한다. 분기 사이의 서로 다른 map을 유지하는 계획까지 살리려면 reader 후보 표현도 확장해야 한다. |

## 1. 보존 제약을 적용하는 정확한 구간

여기서 TW는 저장할 값을 계산하는 일반 producer가 아니라, 저장소 규칙상 `<CP,LOUT>` 또는 `<FED,FOUT>`만 허용되는 실제 transient write다. 이 전제에서 다음 연결은 보존 제약을 만족한다.

```text
TW(CP,LOUT)   → Binding(CP,LOUT)   → TR(CP,LOUT)
TW(FED,FOUT)  → Binding(FED,FOUT)  → TR(FED,FOUT)
```

Binding이 함수 인자나 반환값의 경계여도 동일하다. 행렬 runtime binding은 actual의 `Data`를 formal에 넣고, 반환 `Data`를 caller 결과에 연결한다. 이 전달 자체가 다운로드나 업로드를 실행하지는 않는다. [함수 입력 binding][S1], [함수 반환 binding][S2]

반면 원본 FOUT 값을 local로 바꿔 전달하려면 다음처럼 구분한다.

```text
원본 TW(FED,FOUT)
    → 명시적 LOCAL 변환
    → 변환 결과의 TW'(CP,LOUT) → Binding(CP,LOUT) → TR(CP,LOUT)
```

보존 equality는 `TW' → Binding → TR`에 적용한다. 원본 TW와 변환 이후의 TR을 직접 alias 연결한 것처럼 검사해서는 안 된다. 여기서 TW'는 변환 결과의 최종 정의를 뜻하며, 실제 구현에서 별도 DML 문장을 사용자에게 요구한다는 뜻은 아니다.

일반 계산 생산자는 전달 노드와 구분한다. `<FED,LOUT>` 계산 결과를 `<CP,LOUT>` TW에 쓰는 것은 이미 local인 결과를 저장하는 것이므로 가능하다. 생산 연산의 exec까지 TW와 같게 강제하지 않는다. 현재 코드도 일반 actual producer → 함수 경계에는 `SAME_VALUE_PLACEMENT`, 경계 → formal에는 `SAME_PLACEMENT`를 사용한다. Actual이 일반 계산 결과인 호출을 TW와 같은 전체 상태 equality로 바꾸지는 않는다. 여러 reaching TW를 가진 CFG 합류의 equality와 이 함수 입력 제약은 적용 대상이 다르다. [함수 입력 제약][S3]

여기서 중요한 구분은 **상태 equality와 분기 간 전체 물리 map의 고정 여부**다. `PlacementState`는 exec, output, FType, shape-dependent 여부를 담는다. `<FED,FOUT>`라는 두 항목만 같다고 worker와 partition range까지 같아지는 것은 아니다. [상태 자료구조][S4]

각 실행에서는 공급된 TW와 TR의 실제 map이 같아야 한다. 그러나 서로 배타적인 두 실행의 map까지 항상 같아야 한다는 규칙은 별도다.

```text
true 실행:  TW(FED,FOUT, map=A) → Binding → TR(map=A)
false 실행: TW(FED,FOUT, map=B) → Binding → TR(map=B)
```

이 예시도 실행별 값 보존은 만족한다. 다만 현재의 고정된 reader 후보가 이런 A/B 전달을 표현하는지는 다른 문제이며, 4절에서 설명한다. FType과 연산이 요구하는 물리 조건도 함께 검증해야 한다.

## 2. If-else에서는 무엇을 언제 통일하는가

변수 Y의 true 결과가 local이고 false 결과가 federated라고 하자. 공통 TR에 대해 최적화기가 비교할 기본 대안은 다음과 같다.

| 공통 TR의 선택 상태 | True의 원본 LOUT | False의 원본 FOUT |
|---|---|---|
| `<CP,LOUT>` | Identity: 이동 없이 전달 | LOCAL: 별도 local 값으로 materialize |
| `<FED,FOUT>` | 허용된 anchor로 upload | Identity 또는 목표 FED layout에 필요한 명시적 재배치 |

FOUT 원본을 같은 `<FED,FOUT>` reader에 보낸다고 항상 이동이 없는 것은 아니다. 고정된 목표 map이 있다면 원본 map과의 호환성을 확인해야 한다. 또한 GET은 privacy상 허용되어야 하고 upload/재배치는 실제 anchor, runtime 지원과 실행 구간 제한을 만족해야 한다. 변환이 불가능하면 그 **source 선택·target 선택·action의 조합**이 불법이다. 해당 연산이나 모든 FOUT/LOUT 후보를 일괄 제거하는 근거로 사용하지 않는다.

따라서 통일 대상은 “각 경로가 변환을 거친 후 공통 TR에 넘기는 상태”다. 분기의 원래 생산 결과까지 같은 상태로 만들 필요는 없다. 또한 X의 공통 TR은 FED/FOUT, Y의 공통 TR은 CP/LOUT으로 선택할 수 있다. 서로 다른 변수의 상태를 하나로 통일하지 않는다.

**변환 후보를 최적화 전에 고려하고, 명령 삽입은 최종 선택 후 수행한다.** 계획이 실패한 뒤 임시 GET을 붙이거나 runtime에서 보정하는 방식으로 구현하지 않는다.

구체적으로는 다음 순서다.

1. 합류하는 값과 각 incoming edge를 식별한다. 한쪽 arm이 값을 갱신하지 않으면 그 edge의 source는 진입 정의다.
2. Source 후보와 목표 reader 후보 사이에 identity 또는 합법적인 변환 선택지를 연결한다. 이동이 필요 없는 edge는 identity만으로 충분할 수 있다.
3. Producer 후보, 공통 reader 후보와 edge action을 같은 최적화 문제에서 선택한다. 다른 위치의 이동이나 기존 캐시 공유도 비용에 반영한다.
4. 선택된 identity는 별도 이동 명령을 만들지 않는다. 선택된 GET/upload/재배치만 해당 경로에 생성한다.
5. 변환 결과의 값 identity와 최종 정의를 binding/TR에 연결하고, 보존 제약을 검증한다.

논리적으로 양쪽 경로를 검사한다는 것과, 양쪽에 실제 이동 명령을 무조건 넣는다는 것은 다르다. 후보를 내부적으로 지연 생성할 수는 있지만, 해당 대안을 선택할 때는 합법성과 비용이 이미 최적화에 반영되어야 한다.

**변환 후보가 있는 edge의 기존 무조건적인 source → TR equality는 action 선택을 반영하는 제약으로 대체한다.**

```text
identity 선택:
    aliasCompatible(원본 source, reader)

conversion 선택:
    legalConversion(원본 source, action)
    AND aliasCompatible(action의 결과 정의, reader)
```

Conversion을 선택했는데도 원본 FOUT source와 local TR의 직접 equality를 동시에 요구하면, 새 후보가 다시 불법이 되어 문제를 해결하지 못한다. 직접 equality는 identity일 때 적용하고, conversion일 때에는 위 두 검사로 대체한다. 보존 검사를 없애는 것이 아니라 **보존할 구간을 변환 이후로 옮기는 것**이다. 변환 결과의 synthetic definition과 해당 source/action에 대한 증거가 최종 선택 및 lowering에 남아야 한다.

## 3. L2SVM에 적용한 구체적 후보

L2SVM의 Y 정규화 조건은 true에서만 Y를 다시 정의하고, false에서는 원래 Y를 유지한다. 원본에는 명시적인 else가 없다. [L2SVM 조건문][S5]

공통 local TR을 선택하는 분기 변환 계획은 다음과 같다.

```text
입력 Y_old(FOUT)
  ├─ true:  입력 공급 → CP 정규화 → Y_new(LOUT) → TW(CP,LOUT) ──┐
  └─ false: Y_old → LOCAL → Y_copy(LOUT) → TW'(CP,LOUT) ───────┤
                                                            ↓
                                                  Binding → TR(CP,LOUT)
```

False에서만 새 변환을 실행한다. True의 정규화 결과는 local로 유지한다. True의 계산 입력을 공급하기 위한 GET 등은 별개이며, 이 그림이 모든 통신의 제거를 뜻하지는 않는다.

다른 후보는 함수 호출 전에 Y의 local 인자 값을 만드는 것이다.

```text
caller Y_old(FOUT) → LOCAL → Y_arg(LOUT)
    → 함수 input binding(LOUT) → formal Y(LOUT)
        ├─ true:  새 local 정규화 결과
        └─ false: 기존 local Y_arg
```

이 후보에는 if 합류의 별도 변환이 필요하지 않다. **호출 전 변환과 분기 내부 변환을 대안으로 비교해야 하며, 둘을 항상 함께 넣지 않는다.** 원래 FOUT 객체의 map을 지우거나 그 객체를 local로 재분류하는 대신 별도 local 출력을 전달한다.

현재 계획된 LOCAL materialization은 별도 local 출력을 만드는 runtime 동작을 갖고 있다. 하지만 새 분기·함수 경계의 action을 그래프, 실행 위치와 emission 검증에 연결하는 작업은 여전히 필요하다. [LOCAL runtime 동작][S6], [선택된 LOCAL의 lowering][S7], [emission authority 검증][S8]

기존 [A/B 보고서][E1]는 함수 alias와 합류 제약의 결합 때문에 L2SVM의 기존 local-write 조합이 제외된 사례를 확인했다. 이 문서의 새 후보를 구현하여 성능이 개선되었다는 증거는 아니다.

## 4. J_v가 추가로 해결할 문제

L2SVM의 한 변수 Y를 local/FED 중 하나로 맞추는 문제는 위의 변환 후보로 다룰 수 있다. `J_v`는 다음과 같은 **여러 입력 사이의 공동 도달 관계**를 보존한다.

```text
if (c) {
    X = X_A; Y = Y_A;
} else {
    X = X_B; Y = Y_B;
}
Z = X + Y;

J_Z = { (X_A,Y_A), (X_B,Y_B) }
```

A와 B가 서로 다른 worker pool이어도 각 행의 X/Y가 해당 FED 연산에 필요한 정렬 조건을 만족할 수 있다. 이 경우 `(X_A,Y_B)`를 검사하여 실패하는 것은 실제 없는 실행을 이유로 계획을 제거하는 것이다. 반대로 X와 Y가 독립적인 조건문에서 선택되면 실제 가능한 AB/BA도 검사해야 한다.

여기서 구현 방향을 구분해야 한다.

| 합류 정책 | 위 AA/BB 예시에서 J_v의 효과 |
|---|---|
| 각 변수의 모든 경로를 고정된 전체 physical layout 및 필요한 입력 속성으로 정규화 | 그 정렬 사례에서는 J_v가 추가로 살릴 계획이 없다. 이동 선택과 비용이 주요 과제다. |
| 공통 canonical 상태를 유지하되 실행마다 실제 source map을 전달 | AA/BB가 각각 합법임을 증명하여 분기 사이의 불필요한 map 통일을 피할 수 있다. |

**의미 있는 J_v 확장을 구현한다면 두 번째 후보를 기존 고정-map 후보에 추가하겠다.** 모든 TW/TR에 대한 보존 검사를 제거하는 방식으로 구현하지 않는다.

현재 `exactTransientReplay`는 하나의 reader 물리 후보를 모든 reaching source가 지원하는지 검사한다. Durable 경로의 `samePhysicalLayout` 및 전체 source 지원 조건 때문에 서로 다른 pool의 AA/BB 후보가 consumer factor에 도달하기 전에 사라질 수 있다. [Reader 후보 생성][S9]

따라서 `J_v` 테이블만 추가하거나 마지막에 0/무한대 비용만 추가해서는 충분하지 않다. Reader의 물리 후보가 실행별 source map을 표현할 수 있어야 한다. 기존 realization에 여러 native pool을 섞는 것도 현재 계약에 맞지 않으므로, 별도의 관계를 참조하는 표현을 둔다. [기존 realization의 pool 제약][S10]

## 5. J_v의 자료구조와 생성 방법

최종 HOP/CFG 분석 시점에 consumer별 분석 자료구조로 저장한다. DML 파싱 단계에서 branch 모양만 기록한 뒤 끝내면 이후 rewrite와 함수 처리로 source identity가 달라질 수 있다. 기존 compiled occurrence와 CFG identity를 기준으로 만든다.

다음 이름은 새로 구현할 자료구조의 예시다.

```text
JointInputRelation {
    consumer: CompiledHopKey
    inputPositions: List<int>
    tuples: Set<SupplierTuple>
}

SupplierTuple {
    suppliers: List<ValueSourceRef>
}

ValueSourceRef {
    definition: 기존 occurrence / value-version / output 위치
    context: 호출 위치를 구분하는 기존 context
    delivery: 관련 alias·합류 edge를 찾을 수 있는 참조
}
```

관계 노드는 CP/FED로 실행하는 새 연산이 아니다. 기존 consumer에 연결되는 분석 데이터다. Tuple은 실제 source와 해당 input 위치를 연결하며, 변수 이름만으로 정의를 구분하지 않는다. 기존 HOP 입력 edge를 무시하고 다른 정의를 직접 operand로 바꾸는 용도로 사용하지 않는다.

**관계는 변수별 집합으로 합치기 전에 보존해야 한다.** 현재 CFG의 `Map<String,Set<Integer>>`와 변수별 union은 X와 Y의 pairing을 잃는다. 이미 만들어진 `{X_A,X_B}`와 `{Y_A,Y_B}`의 Cartesian product로 `J_v`를 복원하지 않는다. [CFG 상태][S11], [변수별 merge][S12]

Consumer가 필요로 하는 입력과 그 정의에 영향을 주는 값을 분석 대상으로 잡고, 같은 실행 지점의 정의 환경을 행 단위로 전달한다. 전체 프로그램의 모든 live 변수를 하나의 거대한 표로 만드는 방식은 피한다. 필요한 의존 값을 너무 일찍 제거하여 관계가 깨지지 않도록 projection 지점을 정한다.

| 구문 | 공동 관계를 갱신하는 규칙 |
|---|---|
| 순차 대입 | 각 행에서 갱신한 변수의 정의를 교체한다. 갱신하지 않은 변수는 그대로 유지한다. |
| If-else | 같은 진입 관계에서 각 arm을 분석하고, 결과 행들의 union을 취한다. 두 arm의 변수별 결과를 교차 결합하지 않는다. |
| 미갱신 arm | 그 행의 진입 정의를 유지한다. |
| 독립적인 후속 if | 앞의 각 가능한 행에서 새 조건의 가능한 arm을 진행한다. 실제 가능한 교차 조합도 남긴다. |
| 중첩 구문 | 내부 구문의 관계를 외부 순차 흐름에 합성한다. |
| Loop | Entry 관계와 body를 거쳐 돌아온 관계의 합집합을 고정점까지 계산한다. 0회 실행이 가능하면 entry를 exit에도 반영한다. |
| 비재귀 함수 | 호출별 actual tuple을 formal에 연결하고, 같은 호출의 반환 tuple을 caller에 전달한다. 함수 요약은 재사용한다. |

중복 tuple을 제거하고 같은 분석 결과를 공유한다. Loop의 header/backedge는 고정된 참조로 연결하고 runtime 반복 번호를 새 identity로 계속 누적하지 않는다. Loop를 반복 횟수만큼 펼치거나 호출마다 함수의 계산 그래프 전체를 복제하지 않는다. 공유 함수 몸체의 정적 후보 선택도 호출마다 임의로 다르게 고르지 않는다. 자기 재귀와 상호 재귀의 새로운 지원은 범위에 포함하지 않는다.

Loop에는 추가 주의가 필요하다. 같은 정적 if가 반복마다 다른 결과를 가질 수 있으므로 branch ID 하나를 영구적인 true/false 태그로 쓰면 안 된다. 같은 정의 ID도 반복마다 다른 실제 map을 전달할 수 있다. **정적 tuple 고정점과 별도로, entry에서 관계가 성립하고 body/backedge가 그 관계를 보존한다는 물리 map 증명이 필요하다.** 순환 참조를 다시 만났다는 이유만으로 증명이 성공한 것으로 처리하지 않는다.

모든 조건식의 의미적 실행 가능성을 정확히 풀겠다는 목표는 두지 않는다. 계산한 관계는 실제 가능한 tuple을 포함하는 `J_hat_v`로 취급한다. 과대근사된 행은 안전한 계획도 제외할 수 있으므로, 정확한 논문의 `J_v`와 항상 같다고 주장하지 않는다. 기존 후보를 임의로 제거하지 않으며, 새 관계 증명이 미완성인 경우는 runtime 미지원과 구분한다.

## 6. Reader 후보, 명시적 변환과 공동 factor를 연결한다

**첫째, 기존 고정-map reader를 유지하고 `VALUE_MAP` 형태의 reader 후보를 추가한다.** 명칭은 제안이다. 이 후보는 하나의 concrete pool을 선언하는 대신 “해당 실행에서 실제 도달한 source의 map을 보존한다”는 관계 참조를 갖는다. Canonical 상태와 FType 등은 기존 규칙을 지키고, 각 source에 대한 값 보존을 확인한다.

**둘째, 원래 공동 관계와 물리 변환 선택을 분리한다.** `J_v`는 제어 흐름에서 어느 정의가 함께 오는지를 보존하고, 물리 계획은 각 delivery edge의 action을 선택한다. 각 행을 검사할 때 그 선택을 적용하여 실제 공급될 값과 layout을 얻는다. GET을 선택했다면 해당 입력의 물리 공급자는 원본 FOUT가 아니라 별도 local 결과다. 전체 J_v를 계획마다 새로 구축하지 않는다.

**셋째, 각 행의 공동 합법성을 같은 물리 모델에서 검사한다.**

```text
jointLegal(consumerCandidate, relation, selectedPlan):
    for tuple in relation.tuples:
        inputs = resolveSuppliers(tuple, selectedPlan)
        # 선택된 producer, delivery action과 실제 source map 계약을 반영
        if not executableTogether(consumerCandidate, inputs):
            return false
    return true

hardCost = 0 if jointLegal(...) else infinity
```

검사에는 privacy, source/target 보존, kernel의 layout 요구와 map 계승 조건을 포함한다. 같은 producer가 여러 행에 나오면 동일한 선택을 참조한다. Runtime branch를 solver의 자유 선택 변수로 넣거나, 비용이 싼 행 하나만 선택해서는 안 된다.

공동 관계가 필요한 consumer 후보에만 이 조건을 붙이고, 기존 일반 후보의 조건은 유지한다. 현재 공통 물리 모델의 strict transient 및 realization support factor와 최종 선택 검증에 같은 의미의 검사를 연결한다. [기존 transient factor][S13], [realization support factor][S14]

각 행의 조건을 AND로 검사하는 경우에는 행별 hard factor로 분해할 수 있다. 행 하나의 다중 입력 조건을 근거 없이 입력별 unary 조건으로 쪼개면 원래 관계를 잃는다. Factor가 참조하는 producer·변환 선택은 모두 scope에 포함하고, scope 밖의 mutable 선택을 읽지 않는다.

Pruning은 불법 부분 조합 또는 남은 선택 중 지원이 전혀 없는 후보에 적용한다. 어떤 상대 후보 하나와 안 맞는다는 이유로 해당 후보 전체를 삭제하지 않는다. 최종 선택에서도 모든 행의 조건을 다시 검증한다.

## 7. 무겁게 만들지 않기 위한 구현 범위

- 변환은 합류 edge와 호출 경계의 작은 action 선택으로 표현한다. 매 계획마다 계산 노드를 복제하지 않는다.
- `J_v`는 관계가 필요한 consumer와 의존 범위에서 생성하고, 중복 tuple·동일 관계를 공유한다.
- 기존 고정-map 후보를 빠른 경로로 유지하고, source-map 관계가 필요한 후보에만 추가 증명을 요구한다.
- 행별 독립 조건은 작은 factor들로 나눈다. 현재 `Factor.lazy`도 freeze 시 domain 곱 크기의 dense table이 될 수 있으므로, “lazy니까 메모리 부담이 없다”고 가정하지 않는다. [Factor materialization][S15]
- Tuple 수, 최대 factor cell 수, 고정점 반복 수와 planning 시간·메모리를 먼저 측정한다. 일반적인 독립 분기에서는 조합 수가 증가할 수 있으며, 노드 비복제만으로 이를 없앨 수는 없다.
- 크기를 줄이려고 도달 가능한 행을 조용히 버리지 않는다. 압축·공유 또는 동치인 factor 분해를 적용하고, 관계의 근사로 인한 계획 손실은 별도로 보고한다.

J_v의 모든 행을 검사한다고 모든 행의 이동 비용을 더하는 것은 아니다. 이동 비용은 실제 실행 조건·호출/반복 빈도·값의 수명·캐시 공유에 따라 계산한다. Tuple 중복 제거 후의 행 개수를 확률이나 실행 횟수로 사용하지 않는다. 기존 실행 빈도 및 다운로드 공유 모델과 연결한다. [빈도 분석][S16], [공유 비용 모델][S17]

`VALUE_MAP`의 계산 비용도 고려해야 한다. 도달하는 map마다 worker 수나 partition 크기가 다르면 관련 layout에 기존 연산 비용 모델을 적용하고 실행 빈도 모델에 따라 집계한다. 첫 source의 worker 수를 모든 경로에 복사하거나 새 후보의 계산 비용을 0으로 두지 않는다. 여기서 사용하는 빈도와 비용은 추정값이며 runtime 성능 보장이 아니다.

Runtime에는 J_v 전체를 전달하는 새 실행기를 만들지 않는다. 기존 제어 흐름이 실제 값을 선택하고, 선택된 이동 명령과 연산을 실행한다. Source-map 기반 후보에는 계획된 입력 관계와 출력 map 계승 계약이 lowering·재컴파일 후에도 남아야 한다. 무재배치를 계획했는데 runtime이 임의 broadcast나 download로 보정하는 것은 허용하지 않는다.

## 8. 실제 작업 순서와 성공 기준

구현은 **경계 보존 및 명시적 변환**, **공동 관계 생성**, **관계형 reader와 joint 검증** 순서로 나누겠다. 첫 단계만으로 L2SVM의 local-write 조합을 검증할 수 있으며, 그것을 J_v 구현 완료라고 부르지 않는다.

| 단계 | 주 수정 지점 | 확인할 결과 |
|---|---|---|
| 1. 변환 후보 | `PlacementRelationClosure`, 기존 action identity·비용·emission 경로 | LOUT 합류에서는 필요한 경로만 LOCAL, FOUT 합류에서는 합법적인 이동만 선택한다. 변환 후 TW/Binding/TR 보존이 성립한다. |
| 2. 공동 관계 | `PlacementProgramFacts`와 `PlacementAnalysis` | 같은 if의 AA/BB와 독립 if의 AA/AB/BA/BB를 구분한다. 중첩·미갱신·loop·비재귀 호출의 관계가 보존된다. |
| 3. Reader 확장 | `exactTransientReplay`, realization 표현과 함수 boundary 전달 | 같은 FED/FOUT 상태에서 실행별 A 또는 B map을 보존하는 후보가 조기 제거되지 않는다. |
| 4. 합법성 연결 | `ExactPhysicalModel`, 선택 검증과 증명 identity | 모든 가능한 tuple을 같은 정적 계획으로 실행할 수 있을 때만 후보 조합이 합법이다. |
| 5. 실행 연결 | Projection, emission, 재컴파일 및 instruction 계약 | 원본 객체가 보존되고 선택된 이동만 실행되며, 계획하지 않은 runtime 보정이 없다. |

작은 fixture에서는 독립적인 완전 열거와 비교하여 후보·pruning의 합법 집합을 확인한다. Loop 안에서 분기 결과가 바뀌는 경우, 같은 함수의 두 호출, 한쪽 arm 미갱신, privacy상 변환 불가를 포함한다. L2SVM은 정규화 조건의 참/거짓을 각각 runtime까지 검증하고, 수치 결과와 실제 이동을 확인한다. 성능 비교는 같은 Docker 조건에서 수행한다.

이 검증들은 향후 구현의 완료 기준이다. 이 문서 작성에서는 새 변환·J_v·관계형 reader를 구현하거나 새 테스트를 실행하지 않았다.

관련 문서: [함수 binding과 분기 변환 설계](FUNCTION_BINDING_AND_BRANCH_CONVERSION_DESIGN_2026-10-06_KO.md), [J_v 관계 노드 확장 제안](JOINT_INPUT_RELATION_NODE_DESIGN_2026-10-06_KO.md), [공동 hard factor와 안전한 pruning](JOINT_HARD_CONSTRAINT_PRUNING_RECOMMENDATION_2026-10-06_KO.md). 기존 J_v 문서의 “구현 미수행” 상태는 유지하며, 이 문서는 이번 질문의 보존 제약 아래 필요한 구체적 작업을 정리한다.

[S1]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/cp/FunctionCallCPInstruction.java:163
[S2]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/cp/FunctionCallCPInstruction.java:243
[S3]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:6279
[S4]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementState.java:34
[S5]: /home/mchoi/w1357-paper-aligned-refactor/scripts/builtin/l2svm.dml:75
[S6]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/cp/PrefetchCPInstruction.java:47
[S7]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/lops/compile/Dag.java:416
[S8]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java:748
[S9]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4663
[S10]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java:1363
[S11]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java:191
[S12]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java:386
[S13]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:1116
[S14]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:1158
[S15]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java:481
[S16]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFacts.java:521
[S17]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java:1209
[E1]: /home/mchoi/function-boundary-all14-20261006/REPORT.md:47
