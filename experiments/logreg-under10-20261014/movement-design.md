# LogReg movement full-input reuse 설계

## 결론

Fresh AFTER JFR에서 `MemoProgramModel.movementGroupCost`는 optimizer 1,696 표본 중 711개
(41.92%), 그 안의 `visitBindingCostContexts`는 543개(32.02%)를 포함한다. 표본은 inclusive라
더할 수 없지만, 현재 23.714초 optimizer에서 movement가 가장 큰 단일 실행 경로임은 분명하다.
기존의 completed-context memo만 추가하면 binding DAG 재방문은 줄어도 각 callback의
`Context`/`ResolvedEnvironment` 재구성, `definitionActivationWeight`, `shapes.viewAt`, HOP clone,
shape 적용, byte 추정이 그대로 남는다. 이번 목표에는 충분하지 않다.

가장 큰 효과를 낼 수 있는 단순하고 정확한 변경은 다음 두 층이다.

1. movement group을 만들 때 이미 수행하는 edge-local activation 방문 결과를 immutable
   `MovementInputProjection`으로 보존한다. 이후 `inputCompatible`와 `movementGroupCost`는 같은
   ordered row를 재생한다.
2. 각 row에서 `DIRECT`/`IMPOSSIBLE`을 통과한 뒤에만 ordered cost contexts와 movement용
   immutable primitive를 lazy하게 완성한다. 각 primitive는 full exact environment와 현재 적용되는
   retained 또는 unretained weight 하나를 가지며, `(from,target)`별 기존 `MemoCostModel.movementCost`
   결과 bit를 저장한다. 이후 동일 row/layout 호출은 resolver, cost-guard recursion, contextual HOP
   view, frequency/activation map 계산을 모두 건너뛴다.

projection은 입력 edge 하나씩 만든다. 여러 입력의 source 조합을 함께 열거하거나 Cartesian
product를 사전 계산하지 않는다.

## 측정 근거와 현재 반복 경로

근거는 `experiments/logreg-bottleneck-20261014/logreg-bottleneck-after-summary.json` 및
`logreg-bottleneck-after-hotspots.json`의 단 한 번의 AFTER 실행이다.

| 항목 | 값 |
| --- | ---: |
| Memo planning | 26.819446540 s |
| optimization | 23.714206977 s |
| optimizer execution samples | 1,696 |
| `movementGroupCost` inclusive | 711 / 41.92% |
| `visitBindingCostContexts` inclusive | 543 / 32.02% |
| `MemoBindingResolver.visitJoint` inclusive | 758 / 44.69% |
| `MemoBindingResolver.enumerate` inclusive | 714 / 42.10% |
| `MemoShapes.resolvedBindingShape` inclusive | 217 / 12.79% |
| `MemoShapes.cloneView` inclusive | 59 / 3.48% |
| optimizer sampled allocation weight | 30.12 GB |

`movementGroupCost`는 현재 매 tuple마다 edge/frame별로 다음을 반복한다
(`MemoProgramModel.java:643-690`).

1. `context(selected, region, frame)` 생성
2. `visitActive`로 activation 및 reaching source 재열거
3. `layout(origin, selected)`와 full `OriginIdentity` 재해결
4. `DIRECT`/`IMPOSSIBLE` gate
5. `visitBindingCostContexts`의 false-then-true cost guard 재귀
6. refined environment, 빈도, active guard, definition phase 재구성
7. `shapes.viewAt`으로 contextual HOP DAG clone 및 shape 적용
8. `MemoCostModel.movementCost`에서 byte와 unit cost 계산

새 Shapes proof cache는 다음 guard를 찾는 증명을 재사용하지만 완성된 row나 primitive를 보존하지
않는다. 따라서 744,987/767,951 binding-guard cache hit에도 위 callback 경로가 543 표본으로 남는다.

## 정확한 불변 경계

### Selection과 무관해 재사용할 수 있는 값

한 `MemoProgramModel` 안에서 다음 값은 tuple의 rule ordinal과 무관하다.

- use index, input position, binding ID, consumer occurrence/region, call frame
- region/caller prerequisite guard의 canonical assignment와 reachability
- SELECT/loop/LAST/call/continuation이 만드는 exact environment 방문 순서
- full source identity의 kind, visible binding, native definition occurrence, compiled owner,
  boundary slot/phase, source call frame, export chain
- cost guard를 false 먼저, true 다음으로 방문하는 exact context 순서
- exact environment에서의 active guards, definition phases, phase/cardinality probability

Resolver에서 selected rule/layout/head layout은 `ResolvedOrigin` payload에만 들어간다
(`MemoBindingResolver.java:791-797`). Resolver control flow가 selection에서 읽는 것은
`headIsBound`뿐이다 (`MemoBindingResolver.java:496`, `692`). 현재 model lookup은 이를
`headsBySlot.containsKey(...)`로 답하므로 model 수명 동안 구조적으로 고정이다
(`MemoProgramModel.java:1238-1246`). Selection-neutral projection lookup도 이 bit를 그대로
제공해야 한다. `NO_SELECTION`을 그대로 사용하면 head가 unbound로 바뀌므로 틀리다.

`definitionActivationWeight`의 `selected` 매개변수도 비용 값에는 필요하지 않다
(`MemoProgramModel.java:700-716`). `context(selected, ...)`로 얻은 guards는 selection과 무관하고,
그 lookup을 넣은 `frequency`는 guards, iteration phases, cardinalities만 읽으며 selections를 읽지
않는다 (`MemoProgramModel.java:1364-1403`). 이 메서드는 structural-head lookup을 가진
selection-neutral evaluation과 사전 계산한 definition guard cube를 받아 row 완성 시 한 번만
계산할 수 있다.

### Tuple마다 남겨야 하는 값

- compiled owner 및 multi-return output slot의 selected source layout
- loop-head variable의 selected layout
- consumer rule input target layout
- worker 수로 normalize한 movement kind 및 network unit cost
- privacy legality. 특히 COLLECT의 `DEFER`/protected payload 처리는 기존
  `privacyCompatible` 소유이며 movement projection으로 합치면 안 된다.

Worker 수는 model 안에서는 고정이나 cache를 model 밖으로 공유하는 근거는 아니다.

### Shape-only 키가 금지되는 이유

`MemoCostModel.movementCost`는 contextual input HOP에 대해 upload이면
`getEffectiveUploadMemEstimate`, collect이면 `getEffectiveOutputMemEstimate`를 호출한다
(`MemoCostModel.java:194-219`, `322-335`). 이 estimator는 rows/columns뿐 아니라 NNZ,
operator semantics, transient-read source, child input estimates, sparse assignment semantics를 읽는다.
따라서 `(datatype, rows, columns)` 또는 `MemoShapes.Shape`만 같은 두 입력을 합치면 안 된다.

기존 `MemoNativeRules.RequestKey`도 movement key로 재사용하지 않는다. 그것은 native operator,
worker 수와 full shape hint에 대한 Oracle request identity이지 이 cache의 structural edge/frame
identity가 아니다. 구현은 `MovementProjectionKey(MovementEdge, frame)` 아래 row에 full
`MemoBindingResolver.OriginIdentity`와 exact environment를 저장한다. 각 primitive는 contextual HOP으로
기존 `movementCost`를 실행한 최종 unit double을 `(from,target)`별 raw bits로 저장한다. Directional
payload byte를 별도 key나 값으로 노출하지 않는다.

## 구현된 자료구조

아래는 최종 구현의 model-local 구조다.

```text
MovementInputProjection                 // (use, position, frame)에 귀속
  rows: ordered List<ActivationRow>
  terminalStatus: COMPLETE

ActivationRow
  layout-neutral ResolvedOrigin
  full OriginIdentity                   // export chain과 boundary slot 포함
  exact ResolvedEnvironment
  Lazy<ordered List<MovementPrimitive>> // 성공 완료 후에만 publish

MovementPrimitive
  exact ResolvedEnvironment
  weight                                // retained 또는 unretained 중 실제 분기 하나
  activeGuards/definitionPhases         // retained일 때만
  Map<(from,target), raw unit-cost bits> // 기존 movementCost 결과, model worker 고정
```

`MovementInputProjection`은 model-local `Map<MovementProjectionKey,MovementInputProjection>`에
저장한다. key의 stable edge/frame이 occurrence, binding, input position, call frame, region guard,
maxDepth 및 structural head-bound 상태의 namespace가 되며 큰 guard map을 매 요청마다 hash하지 않는다.
projection build는 `registerMovementCosts`가 이미 하는 edge-local 방문에서 수집하고 같은 rows로 group
identity를 등록한다. 즉 새로운 source 열거가 아니라 기존 construction 방문의 결과를 보존한다.

`ResolvedOrigin` 전체를 그대로 저장하면 selected ordinal/layout이라는 tuple payload가 섞인다.
저장 row는 kind/reference/compiled owner/boundary slot/phase/call frame만 가진 layout-neutral
descriptor를 쓰고, replay 시 현재 `selected`로 `layout`을 계산한다. `OriginIdentity`는 이미 layout을
포함하지 않으므로 그대로 저장할 수 있다.

completed cost rows는 base activation row 객체에 직접 귀속된다. 소스 검토상 Shapes의 cost-guard와
binding-shape resolution은 selection에서 structural `headIsBound`만 읽고 selected rule/layout payload를
control 또는 shape proof에 사용하지 않는다. `headIsBound`는 model의 `headsBySlot`로 고정되어 activation
projection과 동일하다. 따라서 별도 full-selected 또는 selected-layout key가 필요 없다. 실제 tuple
의존 값인 source/target layout은 replay 시 다시 계산하고 unit map의 `(from,target)` key에만 반영한다.

## Lazy gate와 실패 계약

activation projection replay 순서는 기존 edge → frame → resolver false/true 및 cardinality 순서와
같다. `movementGroupCost`는 각 row에서 현재 selected source/target을 계산한 뒤 기존처럼
`DIRECT`/`IMPOSSIBLE`이면 즉시 다음 row로 간다. 이 경우 `postGate`를 열지 않는다. 따라서 현재는
도달하지 않는 cost-only UNKNOWN, unknown-size exception, shape diagnostic을 미리 발생시키지 않는다.

UPLOAD/COLLECT일 때만 `postGate`를 연다. miss이면 현재 `visitBindingCostContexts`와 callback을 같은
순서로 끝까지 실행해 임시 builder에 모은다. 모든 row가 성공한 뒤 `List.copyOf` 결과를 publish한다.
다음은 cache하지 않는다.

- `UNRESOLVED_COST_CONTEXT: binding N`
- unknown/nonfinite payload size 등 `MemoCostModel.CostException`
- activation projection의 UNRESOLVED
- visitor 도중의 다른 exception 또는 부분 결과

`(from,target)` unit도 요청된 방향에서만 기존 `MemoCostModel.movementCost(Hop,...)`로 계산한다.
성공한 raw double bits만 primitive에 저장하고 실패는 저장하지 않는다. 다른 방향의 byte estimator를
미리 실행하지 않으므로 보지 않을 exception을 앞당기지 않는다.

`inputCompatible`와 `movementGroupCost`는 construction 중 COMPLETE로 끝나고 100,000-entry budget 안에
게시된 projection만 replay한다. metadata/scalar edge, UNRESOLVED projection, capacity 초과 또는 다른
cache miss는 기존 `visitActive` 경로를 그대로 실행한다. 따라서 cached UNRESOLVED를 재생하거나
construction 시점의 전체 방문으로 첫 `NO` 이후 작업을 앞당기지 않는다. replay에서도 metadata layout
equality, source/target layout, UPLOAD shape legality, COLLECT type legality와 privacy 소유권은 기존과 같다.

## 반복 호출의 fast path

완성된 row에서 per-tuple 작업은 다음으로 제한한다.

1. current selected source layout과 consumer target layout 계산
2. full `OriginIdentity` group filter
3. movement kind gate
4. `(from,target)`에 저장된 raw unit-cost bits 반환; miss일 때만 기존 contextual HOP과
   `MemoCostModel.movementCost` 실행
5. 기존 encounter order로 `unit * weight` 및 activation event 추가

unit miss는 현재 contextual HOP view를 만든 뒤 기존 `MemoCostModel.movementCost(Hop,...)`를 그대로
호출한다. 새 byte getter나 HOP-free cost 공식은 만들지 않는다. 이 호출이 성공한 경우에만 unit raw
bits를 저장한다. 따라서 validation, normalization, directional estimator, diagnostic 문구가 기존 코드와
하나의 구현을 계속 사용한다. `inputCompatible`는 activation projection만 공유하고 기존
`shapes.inputShape` legality 계산을 유지하므로 비용 cache가 legality나 privacy 판단을 대신하지 않는다.

## 산술 및 출력 순서 보존

다음 순서를 바꾸면 raw binary64와 `BigDecimal` objective가 달라질 수 있다.

- edge, frame, activation row, cost context의 encounter order
- unretained `unretainedCost += unit * weight`의 매 단계
- retained `LinkedHashMap<MovementActivation,List<Activation>>` 최초 삽입 순서
- 각 activation list의 append 순서
- `activationUnion` 후 `total += unit * activations` 순서

primitive를 identity별로 미리 합치거나 sort하지 않는다. weight와 unit은 decimal로 재계산하지 않고
현재 연산으로 만든 `double` 및 raw bits를 저장한다. `CostTerm.evaluateExact`은 최종 double을
`new BigDecimal(value)`로 바꾸므로(`MemoOptimizer.java:69-70`, `1168-1170`) 최종 double bit parity가
필수다. retained key도 현재처럼 `Double.doubleToLongBits(unit)`를 사용한다.

## 구현 결과

1. package-private reuse on/off constructor seam을 추가했다.
2. `registerMovementCosts`의 기존 edge-local 방문에서 COMPLETE activation rows를 수집하고 같은 rows로
   group을 등록한다. UNRESOLVED 또는 capacity miss는 이후 기존 visitor로 평가한다.
3. UPLOAD/COLLECT gate 뒤 기존 `visitBindingCostContexts` callback 안에서 기존 순서인
   weight → contextual view/unit → retained activation metadata → accumulation을 유지한다.
4. 전체 callback 방문이 성공한 뒤에만 ordered primitives를 게시한다. 각 primitive에는 실제 적용된
   retained 또는 unretained weight 하나만 저장한다.
5. 기존 `MemoCostModel.movementCost(Hop,...)` 결과를 `(from,target)`별 raw unit bits로 저장한다.
6. activation projection과 primitive payload는 각각 최대 100,000 entries를 charge하며, 빈 COMPLETE
   projection도 1 entry로 charge한다. 초과하면 기존 평가 경로를 사용한다.
7. `MovementReuseStatistics` 일곱 필드와 `FederatedPlanMemo` trace가 실제 요청/build/hit을 출력한다.

## 검증된 범위와 남은 전용 회귀

이번 lane에서 새로 추가하고 실행한 테스트는 2개다.

- `cachedMovementIsExactForEveryScopedSelection`: 작은 guarded FED fixture의 모든 selection에서 cache
  on/off legality `Truth`와 exact `BigDecimal` cost가 일치한다. 반복 평가에서 post-gate hit가 증가하고
  contextual view build는 증가하지 않는다.
- `directAndImpossibleLayoutsStayBehindThePostGateCache`라는 테스트 이름 아래 실제 LOCAL/DIRECT edge
  projection이 존재하지만
  반복 평가에서도 post-gate requests/builds/contextual views가 모두 0임을 확인한다.

같은 overlay에서 기존 `MemoFederatedModelTest`, `MemoLoopCostContextProjectionTest`,
`MemoGuardProvenanceTest`, `MemoRepeatedLoopCostTest`, `MemoCostContextProjectionTest`,
`MemoMultiOutputBuiltinTest`를 함께 실행했다. 신규 2개를 포함해 39 tests가 통과했다. 이 기존 suite가
loop phases/cardinalities, guard provenance/correlation, multi-output, source scaling, retained activation 및
selected-cost audit 범위를 제공한다.

다음 항목은 코드 검토로 계약을 확인했으나 이번 lane에서 독립 fault-injection 테스트를 새로 만들지는
않았다.

- callback 중 `UNRESOLVED_COST_CONTEXT` 또는 `MemoCostModel.CostException` 발생 시 publish 문에 도달하지
  않아 partial primitives와 unit이 row cache에 게시되지 않는지에 대한 직접 failure-injection 회귀
- 동일 크기지만 다른 NNZ/operator/transient source의 unit 분리 전용 fixture
- upload 성공/collect 실패 및 반대 방향의 unit slot 실패 재시도 전용 fixture
- cache capacity 100,000 초과 시 visitor fallback 전용 대규모 fixture

selected plan, reconstructed supplies, 공식 runtime parity와 `<10s` 성능은 root의 통합 full suite 및
승인된 단일 Docker workload 결과로 판정한다. 이 문서는 그 결과를 선행해 통과했다고 주장하지 않는다.

## 계측과 성공 판정

model trace에는 구현된 `MovementReuseStatistics`의 다음 일곱 counter만 출력한다.

- `activationRows`, `activationReplays`
- `postGateRequests`, `postGateBuilds`, `postGateHits`
- `primitiveContexts`, `contextualViewBuilds`

성공 증거는 post-gate 요청 대비 build와 contextual view build가 작고, source pair 사전 열거가 없으며,
기존 objective/selection/output/runtime audit가 완전히 같은 것이다. JFR의 inclusive 표본만으로 10초를
보장할 수 없다. 공식 단일 측정에서 total planning `<10s`를 만족해야 목표 달성으로 판정한다.

## Root의 MemoShapes 후보와의 경계

`MemoShapes`에는 이번 slice에서 view snapshot cache를 추가하지 않는다. Root 소유 변경은 occurrence별
phase/guard dependency 사전 계산과 already-normalized context 재사용에 한정된다. Model cache hit는
`viewAt`을 호출하지 않고, miss는 기존 `viewAt`과 기존 cost estimator를 그대로 실행한다. Shapes의
semantic key를 Model에 복제하지 않는다; stable edge/frame row 객체가 full syntactic context namespace다.

## 남는 위험과 판단

- Activation projection은 resolver control에서 structural `headIsBound`를 보존하고 layout payload를
  replay 시 복원하면 높은 확신으로 exact하다.
- Completed cost rows는 model-fixed structural `headIsBound`를 포함한 activation row 객체에 귀속된다.
  source/target layout은 replay 시 계산하며 binding-only 또는 shape-only key를 사용하지 않는다.
- Unit cache는 directional bytes를 재구성하지 않고 기존 contextual HOP estimator와 movement-cost
  공식이 산출한 최종 double bits를 저장한다.
- 이 변경은 41.9% inclusive movement 경로와 32.0% cost-context 경로를 직접 겨냥하지만, 표본 중첩과
  남은 solver 비용 때문에 `<10s` 도달 여부는 구현 후 한 번의 공식 측정 전에는 알 수 없다.

최종 구현 경계는 **stable edge/frame activation projection + post-gate complete-only ordered full-context
primitive + `(from,target)`별 기존 unit-cost raw bits**이다. 단순 shape key나 context 목록만의 memo가
아니며, source identity·environment·weight·activation metadata를 row에 함께 보존한다.
