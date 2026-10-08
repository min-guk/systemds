# 이전 데이터의 anchor에서 독립적인 업로드 배치 설계

**업로드 목적지를 이전 데이터의 anchor에서 분리하자는 방향에 동의한다. 전체 worker가 정해져 있다면, 업로드할 값의 크기와 ROW·COL·BROADCAST 분할 정책으로 목적 배치를 정의할 수 있다. 그 배치가 A에서 유래했는지 B에서 유래했는지를 별도 후보로 늘릴 이유는 없다.**

다만 실제 worker별 데이터 구간은 실행에 필요하다. 따라서 없앨 것은 **목적 배치의 identity에 섞인 이전 데이터의 출처**이고, 목적 배치는 독립적인 명세로 표현하는 것이 적절하다. Source 값의 identity와 privacy 등은 별도로 유지한다.

이 판단은 앞서 실행한 [작은 DML의 후보 생성 결과](FEDPLANNER_EXECUTED_PLAN_SPACE_EXAMPLE_2026-10-08_KO.md)와 현재 main `276f958efc`의 planner·runtime 구현을 근거로 한다. 아래는 최초 설계의 근거다. 이후 구현과 실제 검증 결과는 [구현 결과 문서](FEDPLANNER_PLAN_SPACE_OPTIMIZATION_RESULT_2026-10-08_KO.md)에서 구분해 기록한다.

구현 계획에는 중복 원인 분석, 입력 정규화, 조기 pruning, 생성 중 deduplication, 증분 병합, 표현 압축의 여섯 단계를 반영했다. anchor 출처에서 독립적인 목적 배치 정의는 2단계의 핵심 작업이며, 상세 순서와 검증 기준은 아래 실행 계획을 따른다.

## 현재 코드에서 확인한 사실

| 판단 | 근거 | 확실성 |
|---|---|---|
| 업로드에 살아 있는 이전 데이터 객체가 반드시 필요한 것은 아니다. | runtime이 literal 주소·range·FType으로 map을 복원한다. | 높음 |
| 같은 물리 목적지가 출처 때문에 별도 경로로 남는 문제가 있다. | 실제 예제에서 A/B 유래 anchor를 사용하는 입력별 다섯 공급 선택이 남았다. | 높음 |
| FType과 worker 수만으로 모든 배치의 동일성을 판정할 수는 없다. | runtime 연산은 partition 구간과 worker의 대응을 검사한다. | 높음 |

`FederatedFoutMaterialize`에는 이전 anchor 연산을 입력으로 연결하지 않고 **문자열 명세만 받는 생성자**가 있다. runtime의 `FederationUtils.buildAnchorMapFromKey()`는 주소와 구간으로 `FederationMap`을 구성한다. 이때 `FederatedData`의 원본 파일은 `null`이다. 즉, 업로드 목적지를 설명하려고 이전 데이터의 payload나 파일을 요구하지 않는다. [lowering 코드](../src/main/java/org/apache/sysds/lops/FederatedFoutMaterialize.java#L52), [map 복원 코드](../src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationUtils.java#L654)

현재 `anchor`라는 이름이 남아 있지만, 이 경로가 실제로 사용하는 것은 독립적으로 전달 가능한 배치 메타데이터다. **사용자 제안은 runtime에 전혀 없는 기능을 새로 요구하는 방향이 아니다. planner의 후보 표현을 이 메타데이터 중심으로 정리하는 방향이다.**

## 전체 worker에 몇 가지 분할로 올리는 기본 모델

전체 worker가 `w1, w2`이고 업로드할 값 X가 `8×2`라면, 기본 목적 배치는 다음처럼 정의할 수 있다.

| 목적 배치 | w1에 둘 데이터 | w2에 둘 데이터 |
|---|---|---|
| ROW 균등 분할 | X의 행 `[0,4)`, 모든 열 | X의 행 `[4,8)`, 모든 열 |
| COL 균등 분할 | X의 모든 행, 열 `[0,1)` | X의 모든 행, 열 `[1,2)` |
| BROADCAST | X 전체 | X 전체 |

이 배치들은 X가 어떤 source에서 만들어졌는지와 독립적이다. 설정된 worker pool과 X의 shape로 생성할 수 있다. worker 순서와 나머지 행·열 배분 규칙도 고정해야 같은 입력에서 같은 명세가 나온다.

현재 runtime에도 output shape와 worker 수로 균등 ROW/COL 구간을 만드는 로직이 있다. 같은 type/shape의 기존 map을 사용하는 경우에는 그 map의 구간을 보존하는 경로도 있다. [구간 생성 코드](../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDLocalMaterializeUtil.java#L125)

따라서 새 업로드의 기본 후보를 **전체 worker의 ROW·COL·BROADCAST라는 작은 집합**에서 시작할 수 있다. 단, 항상 세 가지가 모두 가능한 것은 아니다. 현행 runtime은 모든 worker에 비어 있지 않은 조각을 놓기 위해 ROW에서는 `행 수 ≥ worker 수`, COL에서는 `열 수 ≥ worker 수`를 요구한다. 앞의 `1×2` 집계 결과를 두 worker에 ROW 분할하는 것은 이 조건을 만족하지 않는다. 연산별 capability와 privacy도 계속 적용된다. [업로드 조건](../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDLocalMaterializeUtil.java#L180)

## 이전 데이터의 출처와 실제 partition 구간은 구분해야 한다

다음 두 X의 배치는 모두 전체 worker 두 개를 사용하는 ROW다.

| 배치 | w1의 행 | w2의 행 |
|---|---|---|
| 입력 X의 실제 배치 | `[0,3)` | `[3,8)` |
| 균등 ROW 목적 배치 | `[0,4)` | `[4,8)` |

X는 첫 배치에 있고 Y를 두 번째 배치로 업로드했다면, 같은 worker에서 X/Y의 조각을 그대로 더하는 정렬 조건은 맞지 않는다. 어떤 값을 재분할할지 또는 Y를 X와 같은 구간으로 올릴지를 계획해야 한다. runtime의 `FederationMap.isAligned()`도 구간과 worker 주소를 함께 확인한다. [정렬 검사](../src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationMap.java#L303)

여기서 필요한 것은 “X의 anchor를 선택했다”는 출처가 아니라 다음 명세다.

```text
workers = {w1, w2}
type = ROW
ranges = {w1: [0,3)×[0,2), w2: [3,8)×[0,2)}
```

다른 입력 Z가 같은 구간과 worker 대응을 제공했다면 목적 배치는 하나로 합쳐도 된다. 반대로 X/Z의 값 자체나 서로 다른 구간을 합쳐서는 안 된다.

권장하는 후보 생성 방식은 다음과 같다.

1. 새로운 업로드에는 전체 worker를 사용하는 기본 ROW·COL·BROADCAST 배치를 만든다.
2. 기존 입력이나 native 연산 결과의 실제 layout은 메타데이터로 보존한다.
3. 연산 정렬에 유용한 기존 구간도 목적 배치로 고려하되, **동일한 물리 명세는 출처와 관계없이 한 번만 등록**한다.

모든 입력과 중간 결과가 미리 정한 표준 partition만 따른다는 더 강한 실행 계약을 도입한다면 목적 배치를 더 엄격히 제한할 수 있다. 현재의 “전체 worker 사용” 가정만으로 모든 partition 경계까지 같아지는 것은 아니다. 또한 native 결과의 PART 같은 부분 결과 의미를 BROADCAST와 동일시해서는 안 된다.

## 앞서 본 다섯 공급 선택은 어떻게 단순해질 수 있는가

실측 예제에서 U의 공급 선택은 다음 다섯 가지였다.

```text
U_F 직접 사용
U_L → anchor A를 기준으로 업로드
U_L → anchor B를 기준으로 업로드
U_F → anchor A를 기준으로 relocation
U_F → anchor B를 기준으로 relocation
```

이 예제의 목적은 모두 같은 두 worker에 `1×2` 값을 BROADCAST하는 것이다. 목적 명세를 먼저 구체화하면 다음과 같이 표현할 수 있다.

```text
목적 P = BROADCAST({w1,w2}, shape=1×2)

U_F가 이미 P에 있음 → 직접 사용
U_L가 coordinator에 있음 → P로 업로드
```

A/B에서 유래한 동일 목적지는 하나로 합친다. U_F의 실제 배치가 P와 같고 그 값을 그대로 재사용할 수 있다면, 동일 배치로 다시 보내는 경로도 별도 공급 대안으로 만들 필요가 없다. 이 비교는 이전 ROW anchor와 U_F를 비교하는 대신 **업로드 후 실제 목적 배치 P와 U_F를 비교**해야 한다. runtime에는 이미 실제 materialization 구간과 source의 배치를 대조하는 `matchesPlannedLayout()`이 있다. [실제 목적 배치 비교](../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDLocalMaterializeUtil.java#L73)

**이러한 동치성과 값의 재사용 조건을 보존하면, 앞서 본 한 목적 배치의 5×5 공급 목록을 2×2로 표현하는 것이 설계상 가능하다.** 이 문단은 최초 설계 예측이다. 이후 구현에서 확인한 수치는 구현 결과 문서에 별도로 기록한다. 기존 151개 support 전체가 4개로 줄어든다는 뜻도 아니다. 각 source를 만드는 비용과 의존성, 다른 출력 경로는 계속 남는다.

## 목적 배치와 값을 만드는 경로를 분리하는 표현

다음 정도의 구분이면 이전 데이터의 출처를 목적 배치 identity에서 제거할 수 있다. 이름은 설계 설명용이다.

```text
WorkerPool
  실제로 사용할 worker endpoint 목록

Layout
  FType + shape + worker별 range
  또는 이것을 유일하게 결정하는 분할 정책과 shape 조건

PlacedValue
  어떤 값의 버전인가 + 어떤 Layout에 있는가

Conversion
  source 값/표현 → target Layout으로 만드는 실행 방법
```

Layout key에는 `A에서 유래`, `B에서 유래`, 원본 파일 경로를 넣지 않는다. 값의 identity에는 A/B의 차이를 유지한다. 실행 가능한 control-flow 범위와 lifetime, privacy 및 실제 변환 방법도 목적 layout과 별도로 검증한다. shape가 아직 확정되지 않았다면 구체 range를 안다고 가정하지 않고 명세의 조건을 유지한다.

같은 `PlacedValue`를 만드는 여러 producer가 있을 때는 그 경로들을 같은 결과에 연결할 수 있다. 그러나 결과 layout이 같다는 이유만으로 입력 의존성과 비용까지 같아지는 것은 아니다. 공유 중간 결과나 변환을 한 번만 실행하는 조건도 있으므로, 최솟값 선택은 그 조건을 표현한 상태에서 해야 한다.

현재는 `DurableAnchorKey`가 `placementId`와 partition을 함께 저장하고, `RelocationGroup`도 exact anchor를 key에 넣는다. 반면 `samePhysicalWorkerPool()`은 메타데이터 출처가 다른 물리 pool을 뜻하지 않는다고 명시하고 일부 비교에서 출처를 무시한다. 이 두 기준을 **실제 목적 layout의 일관된 identity**로 정리하는 것이 핵심 변경이다. [현재 anchor key](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java#L429), [물리 pool 비교](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java#L685), [relocation key 구성](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L13032)

기존 planner의 “기존 anchor가 있어야 업로드 후보를 만든다”는 조건은 확인된 worker 배치와 실행 가능성을 확보하는 역할을 한다. 이를 바꾼다면 **명시적으로 설정·검증된 worker pool과 완전한 목적 명세**가 같은 근거를 제공하도록 해야 한다. 기존 데이터의 출처가 이 근거를 제공하는 유일한 방법은 아니다.

이 설계는 anchor 출처 때문에 늘어나는 중복을 줄이는 방향이다. 여러 입력의 실행 선택, 서로 다른 실제 partition, 연산별 정렬 조건에서 생기는 조합은 여전히 존재한다. 구현 검증에서는 이전 예제의 후보 수뿐 아니라 실제 runtime 결과, 기존 불균등 partition의 직접 사용, 동일 목적 변환의 공유 비용까지 함께 확인해야 한다.

## 중복 생성과 반복 처리를 줄이는 여섯 단계 실행 계획

**상태: 검증된 적용 범위의 구현 완료.** 실제 적용 범위와 남은 확장 지점은 [구현 결과](FEDPLANNER_PLAN_SPACE_OPTIMIZATION_RESULT_2026-10-08_KO.md)를 따른다. 아래 순서대로 진행하며, 각 단계에서 원래의 실행 가능한 선택과 비용 의미를 보존하는지 확인한다. 기존 pruning·memoization·증분 처리와 겹치는 부분은 현재 구현을 확장한다.

| 단계 | 개선 방향 | 구체적인 방법 | 산출물과 완료 기준 |
|---|---|---|---|
| 1 | 원인 분석 | 925건의 중복 병합을 생성 단계 중복, 서로 다른 경로의 중복, closure 반복 병합으로 분류한다. | 생성 위치·merge 호출·closure revision별 집계와 대표 trace. 기존 counter와 분류 합계가 대응하고, 계측 전후 분석 결과가 같아야 한다. |
| 2 | 입력 정규화 | 실제 목적 layout을 먼저 계산한다. 동등한 source/action/binding을 product 생성 전에 통합하고, 출처만 다른 relocation과 재사용 가능한 동일 배치 전환을 정리한다. | 입력별 정규화 전후 binding 수, 중복 원인, 기존 후보에서 새 표현으로의 대응. 다른 값·구간·실행 조건은 보존해야 한다. |
| 3 | 조기 pruning | 부분 binding 선택 단계에서 worker-pool 및 partition 호환성, 같은 source owner의 선택 일관성, source 의존성, privacy를 검사한다. | 사유별 탈락 prefix와 피한 leaf 수. 완성 가능한 합법 조합을 조기에 제거하지 않았다는 작은 예제의 전수 비교. |
| 4 | 생성 중 deduplication | Canonical support key와 product descriptor를 사용해 동일 문맥에서 이미 생성한 조합은 다시 만들지 않는다. | 중복 생성 시도·객체 할당·merge 입력량 감소. 문맥 변경 후 필요한 후보가 다시 생성되는지와 hash 충돌 시 구조 비교를 검증한다. |
| 5 | 증분 병합 | 새로 추가되거나 변경된 clause만 전파하는 delta 기반 merge를 적용한다. 삭제·무효화도 의존 관계에 따라 반영한다. | 변경 없는 closure pass의 재병합 감소. 전체 재계산과 같은 고정점에 도달하고 오래된 support가 남지 않아야 한다. |
| 6 | 표현 압축 | 독립적인 입력 선택은 factorized support로 보관하고, source 공유·정렬·privacy 등의 공동 제약을 별도로 표현한다. | 작은 사례에서 명시적으로 전개한 관계와 정확히 일치. 실제 할당·메모리·전체 분석 시간이 개선되고 비용이 후속 소비자로 이동하지 않아야 한다. |

### 1단계에서 925건의 의미를 먼저 분해한다

925는 [기존 집계 예제 trace](../experiments/plan-space-example-20261008/aggregated/trace.json)의 `realizationMergeDuplicateClauses` 값이다. **고유한 중복 clause 925개나 불필요한 객체 할당 925회를 뜻하지 않는다.** 같은 support 목록을 다시 병합하는 빠른 경로도 이 counter를 증가시킨다. [counter 갱신](../src/main/java/org/apache/sysds/hops/fedplanner/placement/SearchSpaceMetrics.java#L639), [동일 목록의 병합 처리](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L1767)

진단에서는 생성 경로 ID, product 호출 ID, owner/rule/emission, exact clause key, merge 호출 ID, 의존성 revision 및 closure pass를 연결한다. 집계 counter는 전체를 세고, 상세 trace는 대표 사례로 제한한다. 상세 분류 계측은 성능 비교 실행과 분리한다.

| 분류 | 확인할 원인 | 주된 대응 단계 |
|---|---|---|
| 생성 단계 중복 | 한 product 생성 호출 내부에서 같은 조건을 중복 산출하는가 | 2·4 |
| 서로 다른 경로의 중복 | native·relocation 등 다른 생성 경로가 같은 exact clause에 도달하는가 | 2·4 |
| closure 반복 병합 | 관련 의존성이 바뀌지 않았는데 이전 clause 목록을 다시 병합하는가 | 5 |

한 중복 병합 사건은 세 분류 중 하나에만 배정한다. 변경 없는 이전 결과의 replay 여부를 먼저 확인하고, 나머지는 생성 호출 내부 중복과 다른 경로의 합류로 구분한다. 의존성이 실제로 바뀌어 필요한 재평가였는지도 별도 표시한다. 출처를 추적하지 못한 사건은 미분류로 남겨 계측을 보완하며 비율을 추정해서 채우지 않는다.

또한 **현재 key가 달라 중복 counter에 잡히지 않는 물리적으로 동등한 A/B anchor 경로**는 별도 항목으로 센다. 이것을 기존 925건에 억지로 포함하지 않는다. 최초 trace만으로 세 분류의 건수를 역산할 수 없다. 이후 계측을 추가한 기준 재실행의 분류는 구현 결과 문서에 기록했다.

### 2단계부터 4단계는 product 앞과 생성 도중에 적용한다

2단계에서는 앞서 정의한 `Layout`을 정규화 기준으로 사용한다. worker endpoint, 실제 출력 shape, FType, worker별 range를 기준으로 목적지를 통합한다. source 값 버전과 실행 가능한 범위, 변환 방법, 공유·수명 및 privacy 조건이 다른 경우에는 별도 선택으로 유지한다. 목적 layout이 같다는 사실만으로 비용이 다른 producer를 삭제하지 않는다.

3단계는 현재 [binding 부분 선택의 source 충돌 검사](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L10621)를 확장한다. 남은 입력을 어떤 방식으로 선택해도 합법적인 완성이 불가능하다고 증명되는 prefix만 제거한다. pool이 달라도 선택한 relocation으로 호환될 수 있으므로 **변환 후 입력 배치**를 검사한다. 아직 미정인 shape·privacy·source 정보는 불가능으로 처리하지 않는다.

4단계의 canonical support key는 최소한 owner/실행 문맥, emission 및 materialization 의미, 목적 layout, 입력 위치별 source/binding/action, 필요한 proof와 공동 조건을 구분해야 한다. source/action 정규화가 성립했다고 해서 proof의 조건까지 자동으로 동일하다고 보지 않는다. 해시 일치 뒤에는 구조적 동일성도 확인한다. 의존성이 바뀌거나 기존 support가 삭제되면 해당 생성 이력도 갱신한다.

현재 [native support product descriptor 중복 방지](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java#L2221)가 이미 있으므로 이를 재사용한다. 생성 후 큰 문자열 signature를 반복 계산하는 방식으로 dedup 비용이 조합 생성 비용을 대신하지 않도록, canonical 객체/ID를 재사용하고 key 구성 비용도 계측한다.

### 5단계는 추가뿐 아니라 삭제와 변경을 다룬다

기존 fact 단위의 recompute/reuse 처리와 연계해 clause 단위의 변화량을 관리한다. 새 clause와 변경된 입력 선택이 영향을 주는 consumer만 queue에 넣고, 새 binding이 추가될 때는 적어도 하나의 변경 항목을 포함하는 product 부분만 갱신한다.

privacy 갱신, source pruning, proof 무효화로 clause가 사라지는 경우에는 reverse dependency를 따라 파생 support도 제거하거나 재검증한다. 다른 유효한 derivation이 남아 있으면 support를 유지해야 한다. 단순히 집합에 새 항목만 추가하는 구조로 바꾸지 않는다. 검증은 delta 실행과 전체 재계산의 최종 관계를 비교하며, 추가·삭제·변경 없는 pass를 모두 포함한다.

### 6단계는 실행 가능한 조합을 전부 객체로 만들지 않는다

예를 들어 다음 관계를 매번 모든 tuple로 전개하지 않고 저장한다.

```text
Support(C, 목적 배치 P)
  = Choices(U, P) × Choices(V, P)
    중 공동 제약을 만족하는 조합
```

공동 제약에는 같은 source owner의 일관된 선택, 입력 정렬, 합법적인 변환, privacy 및 필요한 proof를 포함한다. 이를 생략하고 입력별 선택 목록만 독립적으로 저장하면 실제로 불가능한 조합이 허용될 수 있다.

완전히 독립적인 선택은 `∏ bᵢ`개의 명시적 tuple 대신 입력별 목록 `Σ bᵢ`와 작은 관계 descriptor로 표현할 수 있다. 공동 제약 처리와 최종 탐색의 최악 복잡도까지 선형으로 바뀐다는 뜻은 아니다. selector·비용 평가·receipt 생성이 곧바로 전체 관계를 다시 전개하면 비용이 뒤로 이동할 뿐이므로, 해당 소비자가 필요한 부분만 조회하도록 함께 바꾼다. 기존 `factorizedClauses` 등의 계측 이름만으로 Cartesian product 저장이 이미 제거됐다고 판단하지 않는다.

## 단계별 검증과 비교 기준

- **기준 실행 보존**: 기존 두 DML의 소스·설정·privacy·전체 worker pool과 JAR hash를 고정한다. 최종 clause 수와 누적 생성·병합 횟수를 분리해 비교한다. 925는 해당 기준 실행의 값이며 모든 workload의 공통 목표값이 아니다.
- **의미 보존**: 작은 예제의 실행 가능한 관계를 전수 비교한다. 정규화 후 ID가 달라지는 경우에는 동치가 증명된 대응으로 비교한다. 같은 값/동일 layout, 다른 값/동일 layout, 같은 ROW/다른 구간, shared source, 서로 다른 proof 조건을 회귀 사례에 포함한다.
- **단계별 측정**: 입력 binding 수, 방문 prefix/leaf, 생성 시도·실제 할당·dedup 적중, merge 입력·고유/중복 처리, delta 추가/삭제/재사용, 보관 메모리와 분석 시간을 기록한다. 한 번에 모든 단계를 바꾸지 않고 단계별 효과와 비용을 확인한다.
- **실제 실행 검증**: DP를 우선 검증하고 관련 소비자로 범위를 넓힌다. 기존 Docker 실행 경로에서 수치 결과, runtime 합법성, 변환의 중복 실행 여부와 공유 비용을 검사한다. workload 실행과 성능 비교는 `run_LAN_docker.sh`를 사용한다.
- **완료 판단**: 합법적인 선택과 전역 비용 의미가 보존되고, 목표로 한 중복/반복 작업이 줄며, 전체 분석 시간·메모리 또는 할당량에서 효과가 확인돼야 해당 단계를 완료로 기록한다. 후보 수만 줄었다는 이유로 완료 처리하지 않는다.
