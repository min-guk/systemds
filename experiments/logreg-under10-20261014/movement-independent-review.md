# Movement reuse 독립 정확성 검토

## 판정

최종 동결본에서 중대한 정확성 문제를 찾지 못했다. activation projection과 movement primitive
재사용은 model-local immutable facts에 귀속되고, 현재 tuple에 남아야 하는 source/target layout과
movement kind는 매 평가에서 다시 계산한다. 검토 중 발견된 세 가지 순서/한도 문제는 동결 전에
수정되었다.

이 판정은 코드 의미와 targeted 회귀에 대한 것이다. LogReg/GLM 성능 workload는 이 검토에서
실행하지 않았으며 10초 목표 달성을 주장하지 않는다.

## 확인한 정확성 경계

- `MovementProjectionKey`는 edge와 call frame을 고정하고 frame을 복사한다
  (`MemoProgramModel.java:87-105`). projection row는 객체에 귀속되므로 서로 같은 record 값처럼
  보이는 별도 activation row의 lazy primitive가 병합되지 않는다.
- projection 생성은 선택 rule/layout이 없는 model-local context를 사용하지만 structural
  `headIsBound`는 기존 `context(...)` lookup을 그대로 사용한다. 저장한 `ResolvedOrigin`의 선택 payload는
  `-1/null`이고 replay의 `layout(origin, selected)`가 현재 compiled owner, output slot 또는 loop-head
  선택을 다시 읽는다 (`MemoProgramModel.java:631-654`, `797-807`). Shapes의 cost proof와 view 생성은
  rule/layout payload를 shape authority로 사용하지 않고 structural head-bound 상태를 semantic key에
  포함한다 (`MemoShapes.java:748-778`).
- COMPLETE이며 한도 안에 실제 게시된 projection만 legality와 cost에서 replay한다
  (`MemoProgramModel.java:404-427`, `656-657`, `739-787`). metadata/scalar edge, UNRESOLVED projection,
  cache-limit 초과는 기존 `visitActive` 경로로 돌아가므로 precollection이 기존 `NO` short-circuit 뒤의
  resolver/identity 작업이나 UNKNOWN을 앞당기지 않는다.
- activation cache는 빈 projection도 `max(1, rows.size())`로 과금한다
  (`MemoProgramModel.java:646-653`). 따라서 row 수가 0인 key로 map entry 제한을 우회하지 않는다.
- group identity filter와 DIRECT/IMPOSSIBLE gate가 post-gate lookup보다 먼저 실행된다
  (`MemoProgramModel.java:797-810`). 다른 retained identity의 weight를 계산하지 않으며, unretained row도
  실제 해당 group과 non-direct movement일 때만 weight를 만든다.
- primitive miss는 기존 callback 순서를 보존한다. weight를 먼저 계산하고 contextual private HOP 및
  `MemoCostModel.movementCost`가 성공한 다음 retained guard/phase descriptor를 만든다
  (`MemoProgramModel.java:815-835`). 이로써 later UNKNOWN 또는 guard/phase 오류가 앞선 unit-cost 오류보다
  먼저 나타나지 않는다. visitor 전체가 성공한 뒤에만 immutable list를 게시하므로 partial failure는
  cache hit로 바뀌지 않는다 (`MemoProgramModel.java:836-841`).
- unit cache key는 exact `(from,target)` pair이고 결과는 raw binary64 bits로 저장한다
  (`MemoProgramModel.java:107-118`, `859-870`). UPLOAD/COLLECT 및 ROW/COL/FULL 방향을 합치지 않으며,
  기존 contextual HOP estimator를 miss에서 그대로 호출한다. worker pool은 model 수명 동안 고정이고
  cache가 model 밖으로 공유되지 않는다.
- cached replay도 원래 edge, frame, activation row, cost-context 순서를 유지한다. unretained 합산과 retained
  `LinkedHashMap` event 삽입 순서는 바뀌지 않아 최종 double과 `BigDecimal` objective의 순서 의미를
  보존한다 (`MemoProgramModel.java:730-795`, `843-857`).
- `definitionActivationWeight`에서 제거된 selected payload는 frequency 계산에 사용되지 않는다. guards,
  iteration phases, cardinalities, call frame과 structural head lookup만 유지된다
  (`MemoProgramModel.java:872-893`, `1497-1592`).

## 수정 과정에서 해소된 사항

1. 빈 COMPLETE projection이 row counter를 늘리지 않아 activation cache map이 무제한 성장할 수 있던
   조건은 최소 1 entry charge로 수정되었다.
2. cache miss에서 모든 primitive를 먼저 모으면 뒤쪽 UNKNOWN이 앞쪽 cost 오류보다 먼저 보일 수 있던
   방식은 callback 안에서 기존 순서대로 즉시 평가하고 성공 후 게시하는 방식으로 수정되었다.
3. retained miss에서 `activeGuards`/`definitionPhases`를 unit estimator보다 먼저 평가하던 순서는 unit 성공
   뒤로 이동했다. 미게시/미지원 projection은 원래 visitor fallback을 사용한다.

## 검증 증거

- `MemoMovementReuseTest.cachedMovementIsExactForEveryScopedSelection`은 작은 guarded federated fixture의
  모든 선택 조합에서 reuse on/off legality와 exact total cost를 비교하고, 반복 평가가 contextual HOP
  view를 다시 만들지 않는 것을 확인한다 (`MemoMovementReuseTest.java:28-67`).
- `directAndImpossibleLayoutsStayBehindThePostGateCache`의 실제 local DIRECT fixture는 activation replay가
  발생해도 post-gate request/build 및 contextual view build가 0임을 확인한다
  (`MemoMovementReuseTest.java:69-88`).
- 작성자 제공 최종 격리 실행은 신규 2개와 기존 37개, 총 39개 테스트를 assertions 활성화 상태에서
  통과했다: `model-targeted-junit-03.log`, 17.067초. Production/test `javac` 로그도 성공했고
  `git diff --check`가 통과했다.
- 동결 SHA-256: `MemoProgramModel.java`
  `3960ed8bff6d983a4e12b7c010a8779819ce243bdf9a119020c77627bd847178`,
  `MemoMovementReuseTest.java`
  `009d9733377a4abd9995a42d8d0ae591145cade35076538fcb8e0b933105f4eb`.

## 남는 제한

- 신규 differential fixture는 top-level guard와 한 federated source graph를 깊게 전수하지만 nested call
  frame, loop FIRST/MIDDLE/LAST, multi-return slot, export chain, privacy DEFER 각각을 reuse on/off로 직접
  전수하지는 않는다. 이 의미들은 기존 37개 회귀와 source-level fallback/identity 검토에 의존한다.
- DIRECT gate는 신규 테스트가 명시적으로 증명하지만 IMPOSSIBLE gate는 같은 조건문을 공유한다는
  코드 검토에 의존하며 별도 fixture가 없다.
- cache limit 초과, visitor 중간 exception 후 재호출, 오류 메시지 우선순서를 직접 관찰하는 신규
  fault-injection 테스트는 없다. complete-only publish와 fallback 구조는 확인했지만 향후 해당 코드가
  분리되면 이 세 경계에 회귀를 추가하는 편이 안전하다.
- `MemoProgramModel`과 내부 cache는 기존 optimizer의 단일-thread model 평가 전제를 따른다. 병렬
  cost-evaluator 공유를 지원하는 동기화 계약은 이번 변경에 추가되지 않았다.

위 제한은 현재 동결본을 막을 정도의 구체적 결함은 아니다. 최종 통합 suite와 공식 단일 workload
측정은 root lane이 수행해야 한다.
