# 공통 FED planning DAG, 4개 planner, FedAll 복잡도 분석 보고서

- **작성일**: 2026-08-14 (Europe/Berlin)
- **질문**: FedAll은 “가능한 많은 Hop을 FED/FOUT으로” 만들면 되는데, 왜 한 번의 greedy/online DAG traversal이 아니며 compile이 오래 걸리는가?
- **짧은 답**: 현재 FedAll은 한 번의 traversal이 아니다. 공통 graph의 전역 합법성, exact candidate row, 공유 relocation/materialization을 모두 만족하면서 `(FED 최대, FOUT 최대, 물리 전송 최소)`를 exact하게 구하는 제약 최적화다. 최악의 경우 지수적이다. 다만 **FedAll의 1차 정책 자체는 대부분 고정점 propagation으로 선형/준선형에 가깝게 만들 수 있고**, exact search는 공유 전송 충돌 component에만 한정하는 구조가 합리적이다.

## 1. 현재 공통 planning 파이프라인

### 1.1 공통 입력은 단순 Hop DAG가 아니다

`NeutralPlacementGraphBuilder` 가 최종 normalized Hop program으로부터 다음을 하나의 immutable `PlacementAnalysis`로 만든다.

1. **Exact occurrence identity**
   - top-level statement block, if/else, while/for, function body/call site, dynamic-recompile clone를 구분한다.
   - 같은 numeric Hop ID나 변수명만으로 합치지 않는다.
2. **Value-version/CFG**
   - transient write/read의 reaching definition, branch join, loop carried value, function input/output version을 표현한다.
3. **Node state domain**
   - 각 occurrence의 runtime-supported `ExecType × FederatedOutput × FType × shapeDependent` 대안과 exclusion reason을 보유한다.
4. **Constraint**
   - `DOMINATES`: producer→consumer data edge
   - `SAME_PLACEMENT`: TRead/TWrite, transparent formal binding 등 exact placement 동일성
   - `SAME_VALUE_PLACEMENT`: 함수 formal exit와 positional caller output이 같은 `LOUT` 또는 exact `FOUT/FType` 값을 가리킴. producer의 CP/FED 실행 위치 자체는 같을 필요가 없음
   - `SAME_FTYPE`: 배치 형태 동일성
   - `CONJUNCTIVE`: 특정 state pair 및 materializing boundary 제약
   - `DISTINCT_CONTEXT`: 호출 문맥 분리
5. **Exact candidate rows**
   - 각 operation에 대해 ordered input별 FType/anchor 조합, oracle capability, native output, derived-FOUT action을 묶는다.
6. **물리 action**
   - CP→FOUT, FED→LOUT→FOUT, REFED, FOUT→LOUT local materialization을 consumer/input/action identity로 명시한다.
7. **비용·shape·policy fact**
   - 출력 크기, worker 수/FType별 upload/download, compute cost, heuristic demotion marker를 공통 사실로 게시한다.

이 공통 graph를 만들 때 function/CFG/materialization 후보가 새로 열리면 그 영향을 받는 descendant의 candidate row를 다시 폐쇄한다. 특히 DML 함수 출력은 aggregate `FunctionOp`의 상태가 아니라 formal output의 모든 exact exit definition을 추적하고, 반환된 동일 `Data` 객체를 읽는 caller TRead의 후보·anchor·하위 물리 action까지 재전파한다. 따라서 graph의 “Hop 방문” 자체는 한 번이어도, 법적 candidate domain의 고정점은 복수 pass가 필요할 수 있다.

## 2. 플래너별 철학과 알고리즘

| Planner | 철학 | 현재 방법 | 최적성의 범위 |
|---|---|---|---|
| **DP** | 자신 Hop과 직접 자식 Hop arm의 compute/forwarding 비용으로 지역적 최소 계획 | occurrence별 LOUT/FOUT/FType plan arm을 bottom-up memoization하고, 최종에 exact occurrence/family 합법성을 맞춘다 | local recurrence 최적. 전체 plan space global optimum은 보장하지 않음 |
| **FedAll** | 합법한 범위에서 FED 연산 최대, 그 다음 FOUT 최대, 그 다음 distinct physical transfer 최소 | common graph 전체의 lexicographic exact CSP. 작은 graph는 Cartesian enumeration, 큰 graph는 component branch-and-bound | encoded FedAll objective의 exact optimum |
| **Heuristic** | FedAll의 max-FED 기본을 유지하되 heuristic이 LOUT으로 판정한 marker/prefix는 REFED하지 않음 | common graph에 policy exclusion/frontier를 투영한 후 남은 domain에 `ExactPlacementSelector` 적용 | heuristic으로 제한된 plan space에서 exact FedAll-style optimum |
| **MinST** | 공통 compute/network/materialization 비용의 전역 최소 | 각 physical alternative를 categorical variable로, legality/input authority/transfer cost를 factor로 만든 후 exact min-sum variable elimination | encoded legal plan space와 cost surface에 대한 global optimum |

DP와 MinST의 개별 Hop compute/size/network cost primitive은 공통 `PlacementCostSemantics`/`FederatedCostModel`을 사용하도록 맞추고 있다. 두 플래너의 차이는 주로 “같은 원시 비용을 어떻게 조합하는가”에서 나와야 한다.

## 3. FedAll이 현재 single-pass가 아닌 정확한 이유

### 3.1 이름과 실제 구현이 다르다

`FederatedPlannerFedAllMaxFedFoutSinglePass` 는 현재 다음과 같은 **compatibility alias**일 뿐이다.

```java
public final class FederatedPlannerFedAllMaxFedFoutSinglePass
    extends FederatedPlannerFedAll { }
```

실제 `FederatedPlannerFedAll` → `FedAllPlacementAdapter` → `ExactPlacementSelector`로 진입한다. `ExactPlacementSelector` 주석도 “Exhaustive exact selector”이며, 정책 objective는 다음 순서다.

1. `emittedFedCount` 최대
2. `foutCount` 최대
3. `distinctRelocationCount` 최소
4. normalized assignment으로 deterministic tie break

따라서 현재 구현을 “single-pass”라고 부르는 것은 부정확하다.

### 3.2 순수 greedy가 exact 현재 objective를 보장하지 못하는 사유

#### A. 지역적으로 FED가 가능해도 입력 authority가 없을 수 있다

어떤 Hop의 FED candidate는 exact ordered input FType 조합과 durable anchor를 필요로 한다. 현재 Hop만 보고 FED를 선택했다가 상류 source의 다른 경로가 anchor를 제거하면 실행 불가 plan이 된다.

#### B. 같은 placement tuple에 두 물리 방법이 있다

`FED/FOUT/ROW`가 native FOUT일 수도 있고 `FED/LOUT→FOUT` derived materialization일 수도 있다. 표면 state는 같지만 전송 수와 runtime instruction은 다르다. state만 greedy하게 고르면 물리 objective를 결정할 수 없다.

#### C. 하나의 relocation을 여러 consumer가 공유할 수 있다

소스를 한 번 REFED/FOUT하면 여러 input obligation을 동시에 충족할 수 있다. 각 Hop에서 독립적으로 “이 전송이 싼가”를 결정하면 distinct physical transfer를 중복 계산하거나 공유 기회를 놓친다.

#### D. CFG join/function/recompile은 양방향 제약을 만든다

if/else의 여러 TWrite가 하나의 TRead로 합류하거나, 하나의 function formal이 여러 call site를 받으면 한 경로의 선택이 다른 경로의 합법 state를 바꾼다. 순수 topological single pass로는 back-propagation 없이 이 고정점을 보장할 수 없다.

실제로 이번 PCA/ALS/StepLM/LogReg 감사에서 formal exit를 고친 뒤에도 caller TRead가 앞선 방문에서 만든 CP-only candidate를 유지하는 결함이 발견됐다. 이를 해결하려면 `formal input → output exit → caller TRead → descendant materialization`을 변화가 없을 때까지 반복해야 했다. 이는 FedAll의 지수 탐색과는 다른 공통 전처리 고정점 비용이며, 변경된 node만 queue/replay하는 방식으로 제한한다.

### 3.3 현재 복잡도

각 decision group의 domain 크기를 `d_i`라 하면 최악의 exact assignment 수는:

```text
∏_i d_i
```

이다. binary state `N`개라면 `2^N`, ternary state `N`개라면 `3^N`이다. 현재 selector는 이를 줄이기 위해:

- `SAME_PLACEMENT` quotient group
- constraint degree 순서
- independent component 분할
- partial legality pruning
- candidate reachability pruning
- optimistic FED/FOUT upper bound
- unavoidable relocation/local/FOUT-materialization lower bound
- exact score cache 및 incremental scorer

를 사용한다. 그러나 최악 복잡도는 지수적이며, 그래프가 큰 LogReg에서 candidate reachability와 shared relocation 하한을 매 prefix마다 재계산하던 것이 compile time의 주요 원인이었다.

### 3.4 실측 compile time

현재 작업에서 FedAll LogReg local exact regression의 `Compile Phase FedPlanner` 시간은:

- 최적화 전: 약 **137.6 s**
- candidate/relocation/local-materialization index와 incremental scorer 후: **65.3–73.3 s**
- 이후 동일 168-decision/59-group exact universe의 두 측정:
  - `/tmp/g014-fedall-logreg-trace-v170.log`: planner **35.809 s**, exact search **33.391 s**
  - `/tmp/g014-fedall-logreg-effect-index-v176.log`: planner **41.517 s**, exact search **37.864 s**

근거:

- `/tmp/g014-fedall-logreg-incremental-all-scorers-v151.log`
- `/tmp/g014-exact-selector-lowering-bundle-v152.log`

즉 현재의 긴 시간은 단순 DAG enumeration이 아니라 exact global 선택의 부수 비용이다. 이번 incremental 수정은 candidate space를 닫지 않고 동일 정답을 더 빨리 계산한다.

두 최신 값의 차이는 JIT/trace/host noise를 포함하므로 “항상 35초”라고 단정하지 않는다. 또한 workers=1 LogReg가 포함된 production certificate에서는 더 큰 residual search가 드러났고, 최적화 전 `/tmp/g014-minst-tractability-logreg-w1-v183.log`가 300초에 timeout됐다. JFR은 DAG 작성이 아니라 (a) prefix별 candidate-aware lower bound, (b) complete leaf별 relocation/candidate canonical index, (c) canonical group 문자열 비교가 주 비용임을 확인했다.

후속 격리 측정에서 원인이 한 단계 더 구체화됐다. 기존 DFS는 첫 complete assignment에서 물리전송 63개인 약한 incumbent를 만든 뒤 180초 동안 2,097,152 prefix를 방문해도 끝나지 않았다(`/tmp/g014-isolated-fedall-logreg-w1-v202.log`). 동일한 legal state, objective, pruning bound를 유지하면서 **FED/FOUT이 같은 대안끼리만** admissible physical-emission lower bound가 작은 상태를 먼저 방문하도록 열거 순서를 바꾸자, 첫 complete assignment의 물리전송이 16개로 낮아졌다. 결과는 다음과 같다.

- exact search: **22.702초**
- 전체 격리 경로: **29.62초**
- visited prefix: **376,216**
- complete assignment: **1,467**
- production filtered certificate: **76.39초, RC=0**

근거 로그는 `/tmp/g014-isolated-fedall-logreg-w1-v204.log`, `/tmp/g014-minst-tractability-logreg-w1-v205.log`다. 이는 greedy approximation이 아니라 동일 exact search tree의 방문 순서 개선이다.

## 4. 사용자가 제안한 single-pass 아이디어에 대한 비판적 판단

### 4.1 맞는 부분

FedAll의 1차 선호도는 cost optimization이 아니라 강한 정책 순서이다. 대부분의 독립 Hop은 다음으로 결정할 수 있다.

1. runtime-supported FED candidate가 있는가?
2. exact input authority/anchor를 만들 수 있는가?
3. 가능하면 FED를 유지하고 FOUT을 선호한다.

이 부분을 전체 exponential search에 넣을 이유는 없다.

### 4.2 그대로 적용하면 안 되는 부분

순서에 민감한 online greedy로 바꾸면 다음을 잃을 수 있다.

- branch/function/loop에서 동일 변수 family의 exact placement 일관성
- 공유 REFED/FOUT action의 distinct transfer 최소성
- native vs derived FOUT 물리 선택
- 순환/합류 제약의 고정점
- deterministic plan fingerprint

따라서 “한 Hop을 보고 즉시 영구 결정”하는 pure online algorithm은 현재 objective의 exact implementation이 아니다.

### 4.3 권장 구조

정확성과 compile time을 동시에 만족하는 방향은 다음이다.

1. **Phase A — maximal-FED constraint propagation**
   - legal domain에서 FED가 필수/불가능한 node를 즉시 고정
   - SAME_PLACEMENT/function/CFG/anchor 제약을 queue로 전파
   - 변경된 component만 재방문
   - 순수 DAG가 아닌 고정점이지만 통상 `O(V+E+candidate facts)`에 가까움
2. **Phase B — residual exact selection**
   - 여전히 두 개 이상의 max-FED/FOUT 해가 남은 component만 구성
   - shared relocation, native/derived FOUT, function join이 없는 component는 deterministic greedy로 종료
   - 충돌 component에만 branch-and-bound 적용
3. **Phase C — exact certificate**
   - propagation으로 고정된 state와 residual search를 합쳐 기존과 같은 objective certificate/plan fingerprint를 생성

이 방식은 후보를 닫는 핫픽스가 아니라 **동일 exact objective의 decomposition**이어야 한다. 현재의 incremental scorer 작업은 Phase B의 내부 비용을 줄인 상태이며, Phase A/B 구조 분리는 아직 완료되지 않았다.

## 5. planner plan 차이가 실제 실행을 바꾸는 방식

| Workload/경계 | Plan 차이 | Runtime instruction/전송 차이 | 기대 영향 |
|---|---|---|---|
| **LM inner `t(X)%*%X`** | direct FOUT vs LOUT/no-REFED vs REFED | `fed_fed_fout`, `fed_refed`, 또는 단순 `fed_ba+*`; MMChain fusion 가능 여부 | materialization 1회, 중간 데이터 재배치, fusion 차이 |
| **KMeans loop-carried centers** | 반복문 내 local center를 매번 upload할지, FOUT/REFED authority를 유지할지 | 반복별 `fed_refed`/upload 횟수, recompile에서 action 재사용 여부 | WAN에서 반복 전송이 실행시간을 크게 바꿈 |
| **LogReg workers=1 FULL transpose** | FULL anchor로 direct FED/FOUT할지 불필요 download→upload할지 | `prefetch`/`fed_fout`/`fed_refed` 추가 여부 | worker=1에서만 특이하게 튤는 시간은 FULL path 버그의 강한 신호 |
| **L2SVM vector aggregation** | FED로 계산해도 output이 LOUT-only인 operator 후 재-FED 여부 | FED→LOUT 후 REFED 또는 local continuation | Heuristic marker가 있다면 FedAll과 서로 다른 전송 plan이 나와야 함 |
| **ALS/KMeans repeated input** | source FType/worker-pool 일치 및 공유 transfer 선택 | 중복 upload 제거 또는 반복 | worker 수에 따른 network/compute trade-off |
| **PCA/unknown shape** | sentinel이 아닌 actual inferred size로 broadcast/download 비용 계산 | CP/FED, FOUT/LOUT 경계 변경 | 잘못된 0/unknown 비용은 cost planner 오선택을 유발 |

LM의 구체적 검증에서 workers=2–4의 runtime-plan fingerprint는 다음처럼 분리됐다.

- DP/MinST: `ba+*:3;fedinit:2;r':2`
- FedAll: `ba+*:3;fed_fout:1;fedinit:2;r':2`
- Heuristic: `ba+*:3;fed_refed:1;fedinit:2;r':2`

이는 placement log의 차이가 실제 instruction 차이로 이어진 예시다. 반대로 placement fingerprint는 다른데 runtime-plan이 같다면, 정상적 동치인지 lowering이 계획을 지운 것인지 exact action/Hop audit로 판별해야 한다.

## 6. 실험에서 무엇을 대조해야 하는가

실행시간 그래프만으로는 planner 구현을 판정할 수 없다. 각 cell에서 다음 연결을 전수 대조해야 한다.

```text
common analysis fingerprint
  → planner-selected exact occurrence states
  → candidate/relocation/local/FOUT action identities
  → emitted Hop fields and registries
  → lowered instruction identities
  → coordinator execution counts
  → federated request/worker fragment counts
  → actual output FederationMap/FType
  → semantic result and runtime
```

그 후 workload별로 다음을 감사한다.

- FedAll의 selected FED/FOUT 수와 exact physical transfer 수
- Heuristic의 demotion marker가 실제로 no-REFED LOUT decision으로 제한됐는지
- DP의 지역 recurrence 선택이 exact family 합법성을 위반하지 않았는지
- MinST objective/cost contribution이 실제 계산·upload/download 경계와 일치하는지
- plan이 다른 cell은 runtime instruction/전송이 어떻게 달라졌는지
- plan이 같은 cell은 공통 runtime optimum이 합리적인지, 아니면 후보/비용/투영이 잘못 닫혔는지

## 7. 현재 판정과 다음 작업

1. FedAll이 복잡한 것은 단순히 “DAG를 여러 번 보기 때문”이 아니라, 현재 정책을 exact global CSP로 풀고 있기 때문이다.
2. 현재 class name의 `SinglePass`는 구현을 설명하지 못하는 compatibility 이름이다.
3. 사용자의 직관처럼 max-FED 대부분은 propagation/greedy로 고정할 수 있다. 단, shared transfer/function/CFG/native-vs-derived 충돌 component에는 residual exact solve가 필요하다.
4. 함수 formal exit와 caller TRead까지 포함한 source-level 정확성 closure는 최신 회귀에서 통과했다. 동일 plan fingerprint/certificate를 보존하는 Phase A/B decomposition은 336-cell Docker runtime audit 뒤 별도 compile-time 최적화 작업으로 수행하는 것이 안전하다.
5. exact scorer/index와 incumbent ordering 최적화로 workers=1 LogReg production residual case가 300초 timeout에서 76.39초 RC=0으로 바뀌었다. 추가 후보 폐쇄나 runtime fallback은 하지 않았다.
6. 함수-output, caller TRead, DP latent/fixed synthetic incident, graph-only relocation, qualified namespace 및 recursive alias 수정까지 포함한 최종 fresh 회귀는 shared placement/core 200 tests, DP 119, FedAll 7, Heuristic 2, MinST-short 80, lowering 158 tests가 모두 RC=0이다(`/tmp/g014-placement-core-current-v296.log`, `/tmp/g014-ordered-dp-current-v292.log`, `/tmp/g014-ordered-fedall-current-v293.log`, `/tmp/g014-ordered-heuristic-current-v294.log`, `/tmp/g014-ordered-minst-short-current-v295.log`, `/tmp/g014-function-output-runtime-lowering-current-v297.log`). 최신 전체 certificate는 `/tmp/g014-minst-production-synthetic-current-v298.log`에서 8 JUnit methods 내부의 28 production cases, 512.072 test-sec/520 wall-sec, RC=0으로 통과했다(SHA-256 `35bba66a9d6f8bd40729255e1e8c38c4d67eeb8df3314770cfa09478be91504d`).
7. 위 시간은 JVM source regression/정확성 certificate의 wall-clock이며 동일 Docker 조건의 성능 결과가 아니다. planner compile-time 비교는 새 immutable JAR/stage의 Docker cell에서만 채택한다.
8. 최신 source는 clean snapshot `cd23d21…`/tree `ae0ddbad…`, JAR `f073be1…`, stage `ba7a584d…`로 고정됐고 stage-local replay가 통과했다. 현재 Docker WAN-Light→WAN-Mid→LAN 336-cell 전수 audit이 fresh 실행 중이다. 각 cell에서 planner plan 차이를 lowering instruction, coordinator execution, federated request/worker fragment, 실제 output placement, runtime 차이와 exact receipt로 연결한다.
9. 이전 snapshot `506368f...`, JAR `6d65034a...`, stage `4b9bb1...`는 함수-output 수정 전 predecessor이므로 21개 row 전체를 역사적 진단 자료로 격리한다. 현재 campaign은 planning-hash 추정으로 predecessor를 합치지 않고 새 authority에서 모든 logical cell을 한 번씩 실행한다.

## 8. Fresh Docker에서 확인하는 plan→실행 연결

이 절은 이전 immutable predecessor stage에서 얻은 역사적 관측이다. 해당 KMeans plan은 최신 stage와 plan-hash parity를 확인한 뒤에만 재사용하고, 이 절의 PCA 관련 추론은 새 실행으로 대체한다.

최신 immutable stage의 첫 cell(DP/KMeans/worker=1/WAN-Light)은 planner decision을 단순 로그 문자열로만 세지 않고 다음 연쇄를 한 row에 묶었다.

    327 normalized decisions
      → 317 concrete compiled occurrences + 10 synthetic decisions
      → 140 independent physical Hop instructions + 5 synthetic local actions
      → lowering 140/140
      → coordinator execution kinds 284
      → FED dispatch kinds 45
      → worker fragment kinds 62
      → semantic output ARI=1.0, SSE relative error=0

cold/warm normalized runtime plan SHA와 FED instruction fingerprint가 같았고, 두 coordinator 모두 missingPhysicalHops=0, missingSynthetic=0, mismatches=0이었다. 이는 “planner가 FED/FOUT이라고 써 놓았지만 runtime이 다른 것을 실행”하는 과거 계층 단절을 실제 Docker 경계에서 검출할 수 있는 형태다.

다만 이 한 cell은 DP 증거일 뿐 FedAll의 compile-time 알고리즘을 정당화하는 증거는 아니다. FedAll/Heuristic/MinST 및 나머지 workload/worker/profile는 동일 audit를 통과해야 한다. 특히 FedAll 보고서의 결론은 다음과 같이 유지한다.

- 단순 DAG enumeration과 runtime capability 판정은 single pass/linear propagation으로 축소할 수 있다.
- 그러나 공유 relocation, exact FOUT materialization, CFG/function equality가 남은 component는 전역 결합 문제다.
- 현재 긴 compile time은 DAG를 만드는 비용이 아니라 그 residual exact assignment를 반복 평가하는 비용이다.
- 최종 목표는 pure greedy로 의미를 바꾸는 것이 아니라, maximal-FED propagation으로 대부분을 고정한 뒤 충돌 component만 exact solve하는 것이다.
- 이 구조 개선이 구현되기 전까지는 현 exact selector의 비용을 정직하게 compile-time 그래프에 표시하고 runtime 성능과 분리해 해석한다.

이 predecessor Docker continuation은 21/336에서 중단됐고 함수-output 이후 추가 source 변경이 있으므로 `/home/mchoi/g014-full-results-506368f-d712daf-20260814-v2`는 역사적 진단 자료로만 유지한다. 아래 KMeans 수치는 당시 동일 stage 안에서 plan→runtime 차이가 실제로 관측됐다는 근거다. 최신 최종 성능 결과를 만들 fresh campaign은 `/home/mchoi/g014-full-results-cd23d21-d712daf-20260814-v1`에서 실행 중이다.

### 8.1 FedAll KMeans 실측: 복잡한 구조와 실제 residual 난이도의 구분

FedAll/KMeans/worker=1/WAN-Light의 fresh Docker cold compile은 다음을 기록했다.

- Compile Phase FedPlanner: 3.113008초
- Compile Phase LopsBuild: 5.207499초
- 327 decisions, 52 FED, 48 FOUT
- Exact-Search-Complete 154회
- policy 합계 explored=158, pruned=660
- 가장 큰 관측 component: 122 decisions/44 equality groups이지만 prefixes=984, explored leaves=5, pruned=660, elapsed=245ms
- final physical actions: explicit relocation 0, local materialization 2, derived-FOUT materialization 2

따라서 이 cell에서는 “복잡한 exact selector가 존재한다”와 “실제로 exponential 시간을 썼다”가 같은 말이 아니다. 대부분 component는 propagation/constraint로 단일해에 가까웠고 FedPlanner 자체는 3.1초였다. 오히려 LopsBuild가 5.2초로 더 길었다. 긴 compile time 문제는 LogReg workers=1처럼 residual ambiguity와 shared physical action이 큰 경우에 집중되며, 모든 FedAll workload가 느린 것은 아니다.

이 선택은 runtime에서도 사라지지 않았다. DP 대비 FedAll은 selected FED 35→52, FOUT 31→48로 증가했고, normalized runtime plan에는 fed_fout 2개가 추가됐다. 반복 실행 count 기준 fed_fed_fout은 51회, FED dispatch kinds는 45→74, worker fragment kinds는 62→94로 증가했다. 그 결과 warm runtime은 DP 84.868초에서 FedAll 59.737초로 감소했다. 즉 KMeans worker=1 WAN-Light에서는 추가 FOUT materialization 비용보다 반복 local round-trip 회피 이득이 컸다는 실제 관측이다.

### 8.2 같은 KMeans DAG에서 네 철학이 만든 실제 차이

worker=1/WAN-Light KMeans의 공통 분석 결과는 네 planner 모두 327 normalized decisions와 317 concrete compiled occurrences로 같다. 즉 입력 DAG/CFG/function boundary/candidate authority를 만드는 전처리는 동일하다. 달라지는 것은 그 공통 domain에서 고른 exact physical state와 action이다.

| planner | 선택 철학 | FED/FOUT | FedPlanner | runtime FED plan 특징 | warm runtime |
|---|---|---:|---:|---|---:|
| DP | hop-직접 자식 local cost recurrence + legality closure | 35/31 | 19.211210 s | `fed_fout` 없음, dispatch 45 | 84.868 s |
| FedAll | 합법 해 중 FED 최대, FOUT 최대, 물리 action 최소 | 52/48 | 3.113008 s | normalized `fed_fout` 2개, loop 반복 51회, dispatch 74 | 59.737 s |
| Heuristic | FedAll base에서 marker가 지정한 경계만 LOUT/no-REFED로 demotion | 48/44 | 3.534370 s | demotion 1, `fed_fout` 반복 1회, dispatch 64 | 95.024 s |
| MinST | 공통 exact physical domain의 전역 비용 최소화 | 24/19 | 1.948997 s | `fed_fout` 없음, dispatch 38 | 46.028 s |

이 결과가 보여 주는 핵심은 두 가지다.

1. 플래너 차이는 metadata에만 남지 않았다. normalized runtime-plan SHA, FED dispatch 수, 반복 `fed_fout` 수, worker instruction row가 모두 달랐다.
2. 알고리즘의 점근적 최악 복잡도와 한 instance의 실측 compile 시간은 같은 순서를 보장하지 않는다. 이 KMeans residual은 propagation으로 강하게 잘려 FedAll exact search가 3.1초에 끝났고, MinST domain도 작은 반면 DP 구현은 local recurrence 이후 boundary/clone-family closure와 79,254개 trace receipt를 생성해 19.2초가 걸렸다. 따라서 이 한 점의 `MinST < FedAll < Heuristic < DP` compile 순서는 복잡도 이론의 반증도, planner 오류의 증명도 아니다.

사용자의 single-pass 제안은 여기서도 절반은 맞다. 327개 occurrence의 capability와 강제 placement는 공통 graph를 한 번 순회하며 propagation할 수 있다. 그러나 Heuristic demotion 이후 shared action이 달라지거나, direct FOUT과 derived FOUT이 경쟁하거나, function/CFG equality를 함께 만족해야 하는 residual component는 단순 online greedy가 이전 선택을 되돌리지 않고 exact objective를 보장할 수 없다. 따라서 목표 구조는 여전히 `공통 graph 1회 구성 → maximal-FED propagation → 충돌 component exact solve → atomic emission`이다.
### 8.3 FedAll과 Heuristic이 같아도 정상일 수 있는 정확한 조건

KMeans worker=2/WAN-Light에서 FedAll과 Heuristic의 warm runtime은 37.225초와 37.146초이고 normalized runtime plan도 같다. 로그를 보지 않으면 “플래너 구분이 사라졌다”고 의심할 수 있지만, 이 cell의 exact policy 증거는 다음과 같다.

- FedAll: FED/FOUT 35/33, derived FOUT 4, local materialization 1
- Heuristic: marker 0, local prefix 0, frontier edge 0, FED/FOUT 35/33, derived FOUT 4, local materialization 1
- 두 planner: 같은 emission placement SHA, 같은 runtime-plan SHA, `fed_fout` 반복 136, dispatch 52

현재 Heuristic 정의는 독립적인 cost optimizer가 아니라 “FedAll 해에서 marker가 지정한 LOUT/no-REFED 경계만 demote”하는 정책이다. 따라서 marker가 0이면 FedAll과 완전히 같은 것이 철학에 맞다. 중요한 검증은 매 workload/worker에서 marker가 계산돼야 할 상황인데 0으로 누락됐는지 여부다. worker=1 KMeans에는 marker 1개가 존재해 Heuristic이 FedAll과 다른 plan을 냈으므로, 적어도 이 두 cell에서는 marker 전달 경로가 전부 꺼진 상태가 아니다. 이후 LM, LogReg, L2SVM의 known marker boundary에서 같은 대조를 계속한다.

## 9. 최신 `cd23d21…` fresh campaign의 첫 네 planner 관측

새 source/JAR/stage에서 `KMeans/workers=1/WAN-Light` 네 planner가 다시 완료됐다. predecessor row를 합치지 않은 독립 실행이며 결과는 다음과 같다.

| planner | FED/FOUT | FedPlanner | runtime plan 특징 | warm runtime |
|---|---:|---:|---|---:|
| DP | 33/29 | 13.668200 s | `fed_fout` 없음, dispatch 45 | 85.403 s |
| FedAll | 50/46 | 3.326557 s | normalized `fed_fout` 2개, dispatch 74 | 59.228 s |
| Heuristic | 46/42 | 3.450128 s | marker demotion 반영, `fed_fout` 1개, dispatch 64 | 94.019 s |
| MinST | 24/19 | 1.568093 s | `fed_fout` 없음, dispatch 38 | 46.178 s |

네 emission placement SHA와 runtime-plan SHA는 모두 서로 다르고, 두 coordinator와 worker audit의 mismatch/missing/fallback은 모두 0이다. 따라서 predecessor에서 관측한 plan 차이는 최신 함수-output/namespace/recursive 수정 뒤에도 실제 runtime action 차이로 전달된다.

이 한 block의 runtime 순서는 `MinST < FedAll < DP < Heuristic`이다. DP가 FedAll보다 느린 사실만으로 전달 버그라고 판정할 수는 없다. DP는 사용자 정의대로 hop과 직접 자식의 local recurrence이므로 전역 runtime 최적을 보장하지 않고, maximal-FED가 우연히 round-trip을 더 많이 제거할 수 있다. 다만 26초 차이는 noise로 넘기지 않고 workers=2–4 및 다른 workload에서 DP cost contribution, boundary action, 실제 반복 dispatch를 계속 대조한다.

compile 시간도 점근적 최악 복잡도 순서를 따라야 하는 값이 아니다. 이 instance의 MinST exact residual은 강하게 전파되어 1.57초에 닫힌 반면 DP는 local recurrence 뒤 legality/clone-family/boundary closure와 79,289개 trace receipt를 만들었다. 따라서 `MinST < FedAll ≈ Heuristic < DP`인 이 한 점은 MinST가 exact solve를 생략했다는 증거가 아니다. exact production certificate와 `MinST-PhysicalOptimize/Complete` runtime receipt를 함께 통과한 상태에서 residual 크기와 구현 상수 차이로 해석하고, 전체 Docker compile graph에서 다시 평가한다.

### 9.1 derived FOUT이 단일 FED instruction이 아닌 경우

FedAll의 “가능한 한 FOUT”은 모든 opcode가 원하는 FType을 직접 생성한다는 뜻이 아니다. 예를 들어 KMeans row 668은 최종 목표가 `FED/FOUT/BROADCAST`지만 scalar-binary 실행 자체의 입력 anchor는 ROW다. 공통 physical model은 이를 다음 두 단계로 표현했다.

```text
FED scalar-binary on ROW input, forced LOUT
  → coordinator local MatrixBlock
  → planner-proved LOUT→FOUT/BROADCAST materialization
```

첫 단계의 instruction에 `LOUT` 토큰이 실제로 직렬화됐지만 기존 `BinaryMatrixScalarFEDInstruction`은 그 플래그를 무시하고 `FOUT/ROW` mapping을 남겼다. 그래서 row 5가 `plannedPhysical=FED/LOUT/ROW`, `actual=FED/FOUT/ROW`로 중단됐다. 이는 FedAll selector의 복잡도나 greedy 여부와 무관한 runtime 계약 버그다.

수정은 planner를 보수적으로 닫는 방식이 아니다. matrix-scalar runtime이 matrix-matrix와 동일하게 `LOUT`이면 worker 결과를 회수·결합하고 cleanup하며, `FOUT`이면 기존 mapping을 유지하게 했다. 따라서 FedAll의 exact selector는 direct/derived FOUT의 실제 물리 비용을 계속 비교하고, runtime은 선택된 2단계 action을 그대로 실행한다. 새 stage의 실패 cell 재검증에서 이 chain의 두 단계가 모두 exact audit로 연결돼야 해결로 확정한다.

재검증은 새 stage `c50e9a15c9c871a8b01ddd40d15b347ae0dc61085a51a0f83d0049286d430310`의 동일 row 5에서 완료됐다. cold/warm 모두 142개 planned physical Hop이 142개로 lowering됐고 missing/mismatch가 0이며, semantic oracle과 runtime scan도 통과했다. 즉 FedAll의 selector가 고른 derived-FOUT 2단계 계획은 이제 runtime에서 암묵적 `FOUT/ROW`로 바뀌지 않고 의도한 물리 chain으로 실행된다. 이 결과는 selector를 greedy로 단순화해야 한다는 증거도, exact selector가 이 버그를 만들었다는 증거도 아니다. 버그 경계는 명시된 `LOUT`을 무시한 matrix-scalar instruction이었다.
