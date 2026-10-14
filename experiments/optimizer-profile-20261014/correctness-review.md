# MemoOptimizer 정확성 검토

검토 기준은 `25662ee7b485af0880a11c9b219620e155cfbee7`이다. 이 문서는 읽기 전용 소스 분석 결과이며 production/test 변경이나 benchmark 실행 결과를 포함하지 않는다.

## 결론

현재 normalization은 exactness를 유지한다. 초기 propagation에서 영구히 제거된 ordinal을 고정하고, 같은 scope의 check를 논리곱으로 묶으며, 비용은 각 binary64 primitive를 먼저 `BigDecimal`로 바꾼 뒤 합한다. normalization 뒤에는 check/term id와 scope에 의존하는 네 cache와 dependency index를 모두 다시 만든다.

소스상 unary cost folding에는 불필요한 전개가 있다. 현재 `foldAndGroupCosts`는 unary group마다 `0..domainSizes[owner)` 전체를 즉시 평가한다. 이는 고차원 factor의 Cartesian product를 펼치는 코드는 아니지만, 초기 propagation에서 제거된 ordinal도 평가하고 `Limits.supportEvaluations()`의 제한도 받지 않는다. 동일 owner에 native-cost primitive가 많이 붙으면 비용은 대략 `원래 domain 크기 × 같은 unary scope의 primitive 수`까지 늘 수 있다.

후속 JFR 실측에서는 이 경로가 현재 병목이라는 가설이 기각됐다. GLM normalization은 67개 실행 표본, 1.110초 관측 span, optimizer 표본의 0.37%였고, ReducedSearch core는 17,957개 표본, 281.751초 관측 span, 98.50%였다. 따라서 아래 unary 개선은 정확성상 가능한 후순위 정리이며 P0가 아니다. P0는 `MemoProgramFacts.guardValue`의 immutable phase index와 exact-frame `GuardDecision` 재사용이다. GLM에서 `guardValue`는 inclusive 34.58%, `guardDecision`은 24.07%였고 movement/input legality의 반복 shape proof 아래에서 호출됐다. 이 비율은 중첩 stack이므로 서로 더하지 않는다.

후순위 unary 개선을 한다면 안전한 선택지는 다음 둘 중 하나다.

1. unary group을 `nativeCosts`에 접지 않고 lazy `CostFactor`로 남긴다. tree DP, global search, lower bound는 이미 unary term을 처리한다. zero-scope만 `fixedCost`로 접으면 된다.
2. folding을 유지한다면 현재 `domains[owner]`에 남은 ordinal만 평가하고, 예상 primitive evaluation 수가 명시적 normalization budget을 넘으면 그 unary group을 lazy factor로 남긴다. 초기 propagation에서 제거된 ordinal은 이후 search의 `undo`로 복원되지 않으므로 생존 ordinal만 접어도 선택 가능한 해의 목적함수는 같다.

첫 번째가 구현과 증명 면에서 더 단순하다. 다만 branch ordering은 `nativeCosts`만 보므로 unary term을 남기면 탐색 순서와 node 수가 달라질 수 있다. exact status/cost/lexicographic winner는 유지되어야 하며, 제한된 node budget의 incumbent 발견 시점은 달라질 수 있다. 측정 비중이 작으므로 guard/phase lookup과 exact-context proof 재사용보다 먼저 적용할 근거는 없다.

### 측정 후 P0/P1 정확성 경계

- `(loopId, guardId)` index는 `MemoProgramFacts` 생성자가 builder의 최종 `guardPhases`를 freeze한 뒤 만들어야 한다. 현재 조회가 `findFirst`이므로 중복 fact가 혹시 존재할 때도 처음 등록된 항목이 이긴다. index는 원래 순서의 `putIfAbsent`로 같은 의미를 보존하거나, 먼저 uniqueness를 검증해 위반 시 fail closed 해야 한다.
- `loopId -> GuardPhase 목록`은 원래 등록 순서를 보존해야 한다. 순서를 바꾸는 set/hash iteration은 proof 선택이나 diagnostics 관측 순서를 바꿀 수 있다.
- canonical `GuardDecision` 재사용 key는 `(syntactic guardId, exact callFrame)` 전체다. `GuardDecision`은 call/value frame을 방어 복사하는 immutable record이므로 완성된 결과의 model-local 재사용은 안전하다. guard id만으로 합치면 caller/callee alias, inversion, SELECT/LOOP_HEAD/LAST barrier가 손실된다.
- repeat-head proof cache는 binding/head slot, exact call frame, guard 값과 provenance, loop phase/cardinality, 관련 selection과 shape generation을 포함한 완전한 의미 key가 필요하다. 가장 가까운 active head를 고르는 recursion-path 의존 단계 전체를 binding id 하나로 cache하면 안 된다. in-progress cycle을 성공으로 publish해서도 안 된다.
- movement/context descriptor는 현재 `from/target` 판정 뒤 `DIRECT` 또는 `IMPOSSIBLE`이면 projection을 호출하지 않는 gate를 유지해 lazy하게 준비해야 한다. 생성자 eager 준비는 원래 방문하지 않던 비용 분기의 `UNRESOLVED_COST_CONTEXT`를 앞당겨 status/실패 의미를 바꿀 수 있다.

## 단계별 정확성 불변식

### 생성자와 초기 구조

- 원본 `domainSizes`, `coverage`, native cost를 복사한다. native cost의 각 `double`은 `new BigDecimal(double)`로 변환되어 binary64 값을 정확히 보존한다 (`MemoOptimizer.java:145-205`).
- 처음부터 domain 크기가 1인 owner만 check/cost scope에서 제거하며 ordinal 0을 원래 위치에 복원하는 wrapper를 둔다 (`150-160`, `208-218`). 이 변환은 domain 크기 1이라는 정적 사실에만 의존한다.
- check/cost incidence와 branch adjacency는 현재 scope에서 생성한다 (`179-205`). 이후 normalization이 scope를 바꾸면 재생성한다.
- 개선 시 주의: `MemoProgramModel` 수준에서 원본 incidence나 scope metadata를 사전 계산해 재사용할 수는 있지만, mutable domain, revisions, queues, bounds, caches는 optimizer invocation마다 새로 만들어야 한다. normalization 결과는 초기 propagation과 `supportEvaluations` 한도에 영향을 받으므로 model-wide immutable 결과로 미리 고정하면 안 된다.

### 초기 propagation

- 각 check는 scope owner의 revision stamp가 달라질 때만 다시 처리한다 (`429-450`). rule 제거는 incidence check를 다시 enqueue한다.
- support cache key는 check id, 고정 owner/value, scope 전체 revision을 포함한다 (`454-471`). remove와 undo가 revision을 증가시키므로 오래된 support proof가 다른 domain 상태에 재사용되지 않는다.
- support DFS가 budget을 소진하면 `UNKNOWN`을 반환하며 rule을 제거하지 않는다 (`474-494`). 따라서 제한은 pruning 강도만 낮추고 거짓 `NO`를 만들지 않는다.
- 개선 시 주의: revision stamp를 단순히 owner/value key로 줄이면 domain 축소 전의 `YES` witness를 축소 후에도 재사용할 수 있어 unsound하다. cache key 단순화는 정확한 domain membership encoding이나 동등한 generation proof가 필요하다.

### normalization

- 초기 fixed point 뒤 singleton owner를 check/cost scope에서 제거하고 실제 singleton ordinal을 wrapper에 고정한다 (`249-273`, `341-353`). ordinal 0을 가정하지 않는다.
- 같은 scope의 check conjunction은 하나라도 `NO`면 `NO`, 아니면 하나라도 `UNKNOWN`이면 `UNKNOWN`, 모두 `YES`일 때만 `YES`다 (`292-308`). 서로 다른 check의 witness를 따로 인정하지 않으므로 conjunction semantics가 보존된다.
- 같은 scope 비용은 원래 순서대로 각 evaluator의 `BigDecimal` 결과를 더한다 (`310-323`). double로 먼저 합하지 않는다. certified minimum도 합산한다.
- zero-scope는 한 번 평가해 `fixedCost`에 더하고, unary는 owner별 native vector에 접으며, 2개 이상 owner의 factor만 lazy하게 남긴다 (`324-332`). 여기서 unary는 원래 domain 전체를 평가한다 (`327-328`). 이 loop에는 support/normalization budget이 없다.
- normalization이 실제로 일어나면 dependency indexes를 재생성하고 truth/cost/bound/support cache를 모두 비운다. owner/term lower-bound state도 초기화한다 (`278-285`, `355-373`). 이 순서를 일부만 생략하면 바뀐 factor id나 scope로 오래된 cache 값을 읽을 수 있다.
- exactness상 `fixedCost` 및 optimizer-private `nativeCosts` mutation은 한 invocation에서 normalization이 한 번만 실행되므로 중복 합산되지 않는다. 이 배열은 생성자에서 원본을 복사했으므로 `MemoProgramModel.evaluateCost`의 독립 audit 입력을 오염시키지 않는다.

### check/cost/bound cache

- truth cache는 `(normalized check id, exact partial/complete tuple)`에 대해 pure predicate 결과를 저장한다 (`497-504`, `1204-1214`). predicate purity는 public contract다.
- cost cache도 `(normalized factor id, exact complete scoped tuple)`를 사용하며 certified minimum을 매 miss에서 검증한다 (`MemoOptimizer.java:619-632` 부근의 `evaluateCost`).
- conditional bound cache는 단순 revision이 아니라 각 owner의 현재 domain cardinality와 모든 ordinal을 key에 넣는다. 따라서 backtracking으로 같은 domain이 복원되면 안전하게 재사용할 수 있다 (`computeConditionalCostBound`).
- 모든 cache는 bounded `LinkedHashMap`이며 capacity 0이면 삽입 직후 제거된다 (`1187-1193`). cache 유무는 결과 의미를 바꾸지 않아야 한다.
- 단순 개선 후보: `TupleKey` 생성과 `values.clone()`이 profile상 크다면 check/factor별 소규모 dense table을 고려할 수 있다. 단, partial tuple의 `-1`, normalization 후 바뀐 id/scope, cache capacity 0 의미를 모두 보존해야 한다. GLM 전용으로 owner ordinal만 key로 쓰는 일반화는 multi-owner/partial check에 사용할 수 없다.

### tree, leaf-region, global search

- tree DP는 complete coverage와 무제한 node budget에서만 사용하며 unary/binary scope forest만 받는다. `UNKNOWN`을 만나면 fast path를 포기한다 (`508` 이후).
- leaf-region solver는 제거된 tree를 exact boundary message로 만들고 cyclic core만 search한다. boundary choice에 exact message가 없으면 해당 ordinal을 제거하며, `UNKNOWN` tree는 fast path를 포기한다.
- global/reduced search의 lower bound는 native owner minima와 factor별 minima의 합이다. 넓은 factor는 부분 열거 결과를 proof로 쓰지 않고 certified minimum으로 완화한다.
- 개선 시 주의: adjacency를 program structure에서 사전 계산할 때 normalization 후 singleton projection으로 사라진 edge까지 보수적으로 남기는 것은 branch heuristic에는 안전하지만 tree/leaf forest 판정에 그대로 쓰면 exact fast path를 불필요하게 막는다. 반대로 원본 edge를 누락하면 factor가 중복되거나 독립 component로 잘못 분리될 수 있다. fast-path adjacency는 반드시 현재 normalized check/term scope에서 만든다.

### 선택 결과 audit와 supply reconstruction

- `MemoProgramModel.optimize`는 `OPTIMAL` 결과를 원본 ungrouped cost primitives로 다시 평가하고 optimizer cost와 다르면 실패한다 (`MemoProgramModel.java:247-251`). normalization/folding 최적화에서 이 검사를 제거하거나 normalized factors로 대체하면 안 된다.
- supply reconstruction은 `OPTIMAL`에만 실행되고 각 selected use의 compatibility가 다시 `YES`인지 검사한다 (`252-254`, `820-824`). optimizer profiling을 위해 audit와 reconstruction을 분리 계측할 수 있지만 실행 순서와 fail-closed 동작은 유지해야 한다.
- 사전계산 후보는 occurrence/frame/binding처럼 선택과 무관한 구조뿐이다. selected layout, loop head layout, movement recipe는 assignment에 의존하므로 selection 이전에 materialize하거나 owner-only cache로 줄이면 안 된다.

## 기존 테스트와 불변식 매핑

| 불변식 | 기존 테스트 | 보장 범위 |
|---|---|---|
| 정적 size-1 scope reduction | `MemoOptimizerTest.constantScopeReductionKeepsCostsAndConstraintsExactly` | check와 constant/edge cost의 exact selection 및 infeasible check |
| 초기 propagation의 nonzero singleton projection | `initialPropagationFoldsNonzeroSingletonOrdinalsWithoutChangingPredicatesOrCosts` | ordinal 0 가정 방지, cost 유지, fixed `UNKNOWN` 유지 |
| same-scope check conjunction | `sameScopeCheckConjunctionRejectsDisjointSupportWitnesses` | 서로 다른 witness를 잘못 합치는 오류 방지 |
| zero/unary cost folding과 tie-break | `constantAndConflictingUnaryCostsFoldExactlyIntoNativeVectors` | constant와 상충 unary 합, lexicographic winner |
| binary64 primitive별 exact 합 | `exactBinary64SumsDistinguishPlansBeyondRoundedDoubleTotal`, `groupedCostsPreserveEachBinary64TermBeforeBigDecimalSummation` | double pre-sum 방지 |
| certified negative bound | `negativeOwnedCorrectionUsesCertifiedLowerBound` | 음수 correction을 0으로 잘못 완화하지 않음 |
| cache 독립 exactness | `exhaustiveSmallFixturesPreserveFeasibilityCostAndTieBreakWithOrWithoutCache` | cache 0/4096에서 exhaustive oracle parity |
| conditional bound budget/backtracking | `lazyConditionalBoundsPreserveOptimumAcrossBudgetsAndBacktracking` | support budget 1/256, cache 0/64 parity |
| odd cycle/global infeasibility | `arcConsistentOddCycleIsGloballyInfeasible` | arc consistency만으로 끝내지 않고 global search 수행 |
| tree/leaf residual exactness | `cyclicResidualWithTreeBranchMatchesExhaustiveOracleWithoutCache`, `residualCycleUsesIncrementalPropagationAndBoundsAfterTreeDP`, `reducedCoreBoundsReuseOnlyTermsIncidentToChangedDomains` | tree message, cyclic core, incremental bound |
| unknown/open coverage 상태 | `unresolvedCoverageAndUnknownNeverBecomeInfeasibleOrOptimal`, `fixedUnknownCannotBeResolvedBySearchingUnrelatedChoices` | UNKNOWN을 INFEASIBLE/OPTIMAL로 승격하지 않음 |
| node budget 상태 | `resourceLimitDoesNotCertifyAnIncumbent`, `reducedSearchRetainsBestIncumbentWhenNodeBudgetExpires` | exhausted status와 diagnostic incumbent |
| 실제 프로그램 전체 exact parity | `MemoExhaustiveParityTest`의 5개 fixture | 원본 model check/cost exhaustive enumeration과 selected result 비교, cache 0/4096 |
| GLM/LogReg model 및 bounded optimize | `MemoFederatedModelTest.rewrittenGlmConstructsTheCompleteMemoModel`, `rewrittenGlmNormalReturnDoesNotLeaveAFixedPrivacyUnknown`, `rewrittenLogRegOptimizationHonorsFiniteNodeBudget` | 대형 fixture가 fixed UNKNOWN으로 조기 중단하지 않고 budget을 지킴 |
| selected supply 의미 | `MemoControlFlowParityTest`, `MemoProgramModelTest`, runtime 계열 테스트 | selected use/layout/recipe와 loop supply 계약 |

## 남은 검증 공백

1. GLM normalization에서 unary factor evaluation 수를 직접 제한하거나 측정하는 테스트가 없다. `costEvaluations`는 normalization과 search를 합산하므로 단계별 원인을 구별하지 못한다.
2. unary folding을 끈 결과와 현재 결과를 같은 generated fixture에서 비교하는 differential test가 없다. 개선 시 package-private strategy 또는 독립 reference evaluator로 status, exact cost, selected rules를 비교해야 한다.
3. 초기 propagation이 제거한 ordinal만 건너뛰는 최적화에 대해, nonzero singleton과 여러 생존 ordinal 모두를 포함한 cost parity fixture가 없다.
4. 같은 scope에 많은 unary primitive가 있는 GLM형 fixture에서 evaluation 수가 `생존 domain cardinality × group primitive 수` 이내인지 고정하는 회귀가 없다.
5. grouped check는 첫 check의 `site`만 보존한다. 현재 solver 결과에는 site가 노출되지 않아 의미 오류는 없지만, JFR event를 site별로 집계하면 나머지 site가 첫 site로 합쳐진 것처럼 보일 수 있다. profiling event에는 normalized group id와 original check count를 따로 기록해야 한다.
6. 실제 GLM test는 model construction과 finite-budget 상태를 확인하지만 exact-default GLM의 selected cost/plan을 exhaustive oracle과 비교하지 않는다. 규모상 exhaustive 검증은 부적절하므로, 변경 전후 selected assignment와 원본 `evaluateCost`, status, diagnostics, supply signature를 frozen comparison으로 남기는 방식이 현실적이다.

## profiling 단계 경계 권고

정확성 판단에 유용한 최소 구분은 다음과 같다.

- `model-construction`: `MemoProgramModel` 생성자 전체. occurrence/frame 탐색, native primitive projection, checks/costs/movement 등록을 포함한다.
- `optimizer-construction`: `new MemoOptimizer(...)`. scope 축소, binary64 변환, domain/index/cache 초기화를 포함한다.
- `initial-propagation`: 최초 queue enqueue부터 `propagate` 종료까지.
- `normalization`: singleton projection, group/fold, dependency rebuild와 cache reset. unary factor evaluation 수와 생존/원래 ordinal 수를 별도 기록한다.
- `tree-solve`, `leaf-region-solve`, `global-search`: 각 fast path의 시도와 성공/포기 이유를 구분한다. fast path가 `false`를 반환했다고 해서 모두 실패는 아니므로 `not-applicable`, `unknown`, `solved`를 구분해야 한다.
- `selected-cost-audit`: 원본 primitive `evaluateCost` 비교.
- `supply-reconstruction`: compatibility 재검사와 recipe 생성.

계측 자체가 predicate/cost evaluator를 한 번 더 호출해서는 안 된다. 기존 counter와 구조 크기를 event payload로 기록하고, JFR이 꺼져 있을 때 solver의 호출 횟수와 cache 상태가 그대로여야 한다.
