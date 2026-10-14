# Memo Optimizer 병목 소스 분석과 불변식 사전계산 설계

## 질문과 범위

`abb5a4822a` 기준선과 현재 `25662ee7b4` 사이에서 LogReg의 탐색 통계는 거의 같지만 optimization 시간이 25.20초에서 39.08초로 증가한 이유를 소스와 기존 실행 증거만으로 좁히고, `UNKNOWN`, privacy `DEFER`, worker binding, binary64 primitive의 정확한 `BigDecimal` 합, 전역 최적성 계약을 보존하는 최소 사전계산 구조를 설계한다. 새 workload나 benchmark는 실행하지 않았다.

## 순위별 종합

| 순위 | 설명 | 신뢰도 | 근거 |
|---:|---|---|---|
| 1 | 현재 LogReg 회귀의 주된 관측 병목은 movement factor 안에서 매 tuple마다 반복되는 binding-cost projection이다. | 높음 | 동일 추출 기준의 optimize stack에서 `movementGroupCost`는 기준선 301/1,906 samples(15.8%)에서 현재 2,012/3,900(51.6%)로 증가했다. 현재만 존재하는 `visitBindingCostContexts`가 1,778 samples, `firstUnfixedCostGuardForBinding`이 1,081 samples다. 노드 2,933,891과 cost evaluations 4,828은 동일하다. |
| 2 | 현재 GLM에서는 같은 shape/guard 반복이 movement뿐 아니라 input legality에도 큰 비용을 만든다. | 높음 | 최종 GLM optimize 18,231 samples 중 movement가 12,538(68.8%), disjoint input legality가 5,240(28.7%)다. `guardValue` 6,305와 `phaseProvesGuard` 6,440은 양쪽 경로에서 반복된다. |
| 3 | leaf-region reduced search의 domain/key 작업은 두 LogReg 버전에 공통인 기반 비용이지만 GLM 최종 표본의 우선 병목은 아니다. | 높음 | 기준선 LogReg reduced core는 1,876 samples/약 23.21초 및 33.16 GiB 추정 allocation이다. 반면 최종 GLM disjoint `solver-and-other` allocation은 10.28 GiB이고 movement/input legality가 440.86 GiB를 차지한다. |

GLM은 기준선 대비 성능 회귀를 계산할 수 있는 표본이 아니다. 기준선은 transpose native coverage에서 optimizer 전에 중단됐고, 현재 버전만 optimizer에 진입했다. 최종 299초 JFR은 timeout까지 관측한 현재 구현 내부 병목 증거이며, OPTIMAL 결과나 최종 memo search counter가 없으므로 남은 전역 탐색량과 정상 완료 시간은 알 수 없다.

## 직접 증거

### 실행 증거

- `experiments/oracle-cost-projection-20261013/oracle-projection-docker-comparison.json:3-12` — baseline/changed 각각 1회이며 aggregation은 없다.
- `experiments/oracle-cost-projection-20261013/oracle-projection-docker-comparison.json:45-95` — LogReg optimization은 25.200000352초에서 39.075807642초로 증가했고 모델 생성은 1.280704018초에서 0.958387339초로 감소했다. 결과 오차는 변하지 않았다.
- `experiments/oracle-cost-projection-20261013/baseline-docker-summary.json:82-118` — 기준선은 nodes 2,933,891, bound prunes 2,466,239, conditional-bound queries 11,135,047, cost evaluations 4,828, support queries 38,286,830이다.
- `/grid/3/cofee-lm-sweep-mchoi-20260914/fedplanner-oracle-cost-docker-20261013/oracle-projection-logreg-glm-01/cases/ml_logreg/fed.log:9-12` — 현재 실행은 같은 nodes 2,933,891, bound prunes 2,466,239, conditional-bound queries 11,135,047, cost evaluations 4,828을 기록했다. support queries는 6회, check cache hits는 12회만 증가했다.
- `experiments/oracle-cost-projection-20261013/oracle-projection-docker-comparison.json:97-127` — GLM 기준선과 현재 실행은 종료 지점이 달라 end-to-end 비교 불가로 명시돼 있다.

### 기준선 JFR 증거

- `experiments/optimizer-profile-20261014/baseline-logreg-profile.json:1-18` — instrumentation 없는 JFR sampling이며 execution samples 2,040개, 전체 추정 allocation weight 37,899,074,392 bytes다. 표본 수와 allocation weight는 절대 CPU 시간/실제 할당량과 동일하다고 해석하지 않는다.
- `experiments/optimizer-profile-20261014/baseline-logreg-profile.json:840-900` — leaf-region 표본 1,884개 전부가 `tryLeafRegionSolve`에 있고 1,876개가 `ReducedSearch.run/enumerateCore`에 있으며, 관측 span은 23.3755초다.
- `experiments/optimizer-profile-20261014/baseline-logreg-profile.json:1055-1064` — inclusive hot frames에 `BitSet.cardinality` 199, `HashMap.TreeNode.getTreeNode` 195, `HashMap.putVal` 192가 포함된다. 상세 leaf 목록에는 `BitSet.set`, `Arrays.hashCode`, `MemoOptimizer.stamp`, `support`도 상위에 있다.
- `experiments/optimizer-profile-20261014/baseline-logreg-profile.json:1414` — leaf-region 추정 allocation weight는 35,791,914,424 bytes, 약 33.33 GiB다.
- `experiments/optimizer-profile-20261014/baseline-logreg-profile.json:1035-1040` — 기준선 leaf-region에서도 `movementGroupCost` 내부 lambda가 217 inclusive samples를 차지한다. movement evaluator는 기준선에도 유의미했지만 현재 추가 projection의 증분은 current JFR이 있어야 판별할 수 있다.
- `experiments/optimizer-profile-20261014/baseline-logreg-profile.json:2804-2832` — normalization은 3 samples, 관측 span 약 0.075초다. LogReg의 주병목을 normalization 자체로 분류할 근거는 없다.

### 현재 JFR과 기준선 대조

- `experiments/optimizer-profile-20261014/current-logreg-profile.json:1-18` — 현재 profile은 execution samples 4,111개, 전체 추정 allocation weight 75,492,710,240 bytes다.
- `experiments/optimizer-profile-20261014/current-logreg-profile.json:904-920` — 현재 leaf-region은 3,870 samples, 관측 span 50.542초다. 기준선의 1,884 samples/23.376초와 비교해 optimizer 내부 증가가 재현됐다.
- `experiments/optimizer-profile-20261014/profile-comparison.json:1-327` — 전체 optimize stack의 통일된 추출 기준에서 movement는 기준선 301/1,906 samples(15.8%), 현재 2,012/3,900(51.6%)다. 현재 `visitBindingCostContexts`는 1,778, `firstUnfixedCostGuardForBinding`은 1,081 samples다. 아래 raw phase profile의 2,009/3,870은 leaf-region만 자른 값이라 분모가 다르다.
- `experiments/optimizer-profile-20261014/current-logreg-profile.json:1478` — 현재 leaf-region 추정 allocation weight는 71,922,530,640 bytes, 약 66.98 GiB다. 기준선 약 33.33 GiB의 두 배다.
- `experiments/optimizer-profile-20261014/current-logreg-profile.json:6994-6997` 및 `baseline-logreg-profile.json:7187-7190` — STW GC pause는 현재 0.447초, 기준선 0.367초다. GC 정지 시간만으로 leaf-region 관측 span 차이를 설명할 수 없다.
- JFR을 켠 wall time 자체는 비계측 39.08초와 직접 비교하지 않는다. 비교에 사용한 것은 동일 추출기의 phase stack 비율, 관측 span, allocation weight이며 모두 sampling/추정치다.

### 현재 GLM 최종 timeout JFR과 중간 snapshot의 관계

- `experiments/optimizer-profile-20261014/profile-comparison.json:499-539` — 최종 GLM optimize 구간은 18,231 execution samples다. `movementGroupCost` 12,538(68.773%), `visitBindingCostContexts` 11,990(65.767%), `firstUnfixedCostGuardForBinding` 9,384(51.473%), `provesRepeatHeadCostStats` 8,776(48.138%), `phaseProvesGuard` 6,440(35.324%), `guardValue` 6,305(34.584%)가 겹치는 inclusive stack으로 관측됐다.
- `profile-comparison.json:574-642` — disjoint input legality도 5,240 samples(28.742%)와 추정 135.927 GiB allocation을 차지했다. movement는 12,538 samples와 304.932 GiB, optimize 전체는 451.223 GiB다. allocation은 JFR weighted sampling 추정치이며 retained heap과 같지 않다.
- `profile-comparison.json:650-708` — normalization은 67 samples(0.368%), 관측 span 1.110초로 끝났다. reduced-core search는 17,957 samples(98.497%), 관측 span 281.751초다. timeout까지 CPU가 search 안에서 shape/guard/callback 반복 작업에 집중됐다는 결론은 이 최종 capture로 확인된다.
- `profile-comparison.json:710-721` — 전체 recording의 STW GC pause는 1.679초이고 sampled heap 최대는 467.351 MiB, after-GC 최대는 83.351 MiB다. 281.751초 reduced-core span을 GC 정지로 설명할 수 없다.
- `optimizer-profile-summary.json:235-240` — 유효한 GLM process 관측 245.096초 동안 CPU delta는 249.33초로 약 1.017 core이며 RSS/HWM 최대는 988,276 KiB다. 이 구간의 탐색은 사실상 단일 core에 집중됐다.
- 앞서 저장한 `current-glm-live-profile.json`은 약 79초 시점의 중간 snapshot이다. 그때 movement 비율 87.2%는 최종 68.8%보다 높았으므로 최종 구성비 대신 사용할 수 없다. 다만 두 snapshot 모두 `provesRepeatHeadCostStats -> withPhase -> phaseProvesGuard -> guardValue` 반복을 동일하게 포착했다.
- 모든 method sample은 inclusive이므로 합산할 수 없다. 또한 timeout에는 OPTIMAL 종료와 memo search counter가 없으므로 반복 작업이 관측 CPU의 주된 소비처라는 결론과, 전역 탐색이 얼마나 남았는지는 구분해야 한다.

### 기준선 대비 소스 차이

- `git diff --quiet abb5a4822a..25662ee7b4 -- MemoOptimizer.java`는 차이가 없음을 반환한다. 따라서 동일 검색 카운터에서 발생한 시간 차이를 optimizer 알고리즘 변경으로 설명할 직접 소스 증거는 없다.
- `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java:643-698` — 현재 movement evaluator는 edge/frame마다 activation과 origin을 다시 풀고, 각 origin에서 `visitBindingCostContexts`, contextual shape view, movement unit cost, activation map을 생성한다.
- `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java:1041-1048` — `visitBindingCostContexts`는 guard 하나를 찾을 때마다 `MemoShapes.firstUnfixedCostGuardForBinding`을 재호출하고 두 분기로 재귀한다. unresolved는 비용 0이나 `UNKNOWN`으로 바꾸지 않고 예외로 보존한다.
- `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoShapes.java:227-249` — 각 guard 검색은 호출마다 새 memo/active set/accumulator를 만든다.
- `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoShapes.java:265-376` — 검색은 binding DAG, SELECT, LOOP_HEAD, LAST, FORMAL, CALL_RETURN 및 cost-relevant definition inputs를 순회한다.
- 기준선 `MemoProgramModel.java`의 대응 경로는 `movementGroupCost`에서 resolved environment 하나당 contextual shape를 한 번 계산했으며 binding-specific guard projection 재귀는 없었다. 현재 변경은 정확한 source-only cost projection을 위해 추가된 동작이므로 제거 대상이 아니라 사전계산 대상이다.

### optimizer 반복·할당 경로

- `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java:429-470` — worklist visit마다 revision stamp `long[]`, support query마다 revision을 펼친 `int[]`과 `TupleKey`를 만든다.
- `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java:474-505` — support DFS는 partial tuple을 반복 평가하고, `evaluate`는 hit에서도 새 `TupleKey`를 만든다. miss에서는 predicate 전달 전에 다시 clone한다.
- `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java:635-645` — cost cache도 hit 전에 `TupleKey`를 만들고, miss에서 values를 clone한 뒤 factor evaluator를 호출한다.
- `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java:1048-1068` — conditional bound query는 현재 각 owner domain을 새 `int[]`에 직렬화하고 `TupleKey`로 감싼다.
- `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java:1113-1138` — remove/undo마다 revision 증가, bound invalidation, incident enqueue가 발생하며 `stamp`는 매번 새 배열이다.
- `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java:1204-1214` — 모든 `TupleKey`가 입력 `int[]`를 복제한다.

## 최소 사전계산 구조

### 1. gate-preserving movement projection cache와 guard fact index

현재 `movementGroupCost`는 selected tuple에서 `from`과 `target`을 먼저 정하고 `MemoMovement.kind`가 `DIRECT` 또는 `IMPOSSIBLE`이면 cost-context 탐색 없이 반환한다 (`MemoProgramModel.java:648-660`). 따라서 model construction에서 모든 edge의 `visitBindingCostContexts`와 `shapes.viewAt`를 eager 실행하는 `PreparedMovementFactor`는 안전하지 않다. 지금은 건너뛰는 tuple에서 `UNRESOLVED_COST_CONTEXT`를 새로 노출할 수 있기 때문이다.

최소 구조는 이 순서를 그대로 유지하는 두 단계다.

```text
MovementDemandSkeleton                         // model construction에서 안전하게 고정
  edge/use/frame/position/groupIdentity
  sourceLayoutResolver + targetLayoutRef

ProjectionKey                                 // DIRECT/IMPOSSIBLE gate 뒤에서만 생성
  binding + occurrence + inputPosition + callFrame
  projected guards + loops + iteration phases + cardinalities
  relevant selected rule/head identities (독립성이 증명되지 않은 경우)
  workerCount + source/target pair

ProjectionOutcome
  Complete(ordered Primitive[])
  Unresolved                                  // 같은 exact key에서 기존 예외를 재현
```

평가기는 먼저 현재 코드와 같은 `visitActive`, origin identity, selected `from/target`, retention-scope 검사를 수행한다. `DIRECT/IMPOSSIBLE`이면 cache를 보지도 않고 반환한다. 실제 movement인 경우에만 exact `ProjectionKey`로 bounded lazy cache를 조회하고, miss에서 현재 `visitBindingCostContexts -> shapes.viewAt -> movementCost`를 실행한다. selection이 shape proof에 영향을 주지 않는다는 정적 증명이 없다면 관련 selected rule과 loop-head selection도 key에 넣어야 한다. 이 방법은 아직 선택되지 않은 layout tuple을 미리 평가하지 않으며, 현재 skip 경계와 `UNRESOLVED` 관측 가능성을 보존한다.

GLM 표본이 드러낸 더 작은 선행 개선은 immutable facts index다. `guardPhases`는 생성자에서 `List.copyOf`로 고정되는데 (`MemoProgramFacts.java:231-256`), `guardValue(loopId,guardId,phase)`는 매번 전체 목록을 scan한다 (`MemoProgramFacts.java:297-303`). 생성 시 `(loopId,guardId) -> GuardPhase`를 충돌 검증과 함께 인덱싱하면 반환 의미를 바꾸지 않고 O(1) 조회가 가능하다. `guardPhases(loopId)`가 필요한 호출자를 위해 loop별 immutable list도 함께 만들 수 있다. 중복 key가 현재 허용되는지 먼저 assertion/test로 잠가야 하며, 임의로 first-wins/last-wins를 선택하면 안 된다.

`withPhase`는 guard map을 복제한 뒤 모든 guard에 `phaseProvesGuard`를 실행하고 (`MemoShapes.java:778-804`), 각 검사는 모든 syntactic guard와 두 call-frame prefix 열을 다시 돈다. 다음 두 cache는 facts와 shape graph가 immutable인 한 bounded lazy로 둘 수 있다.

- `PhaseGuardProofKey(loop decision, iteration, exact GuardDecision) -> boolean`: `LoopDecision`과 `GuardDecision` 양쪽의 call frame 및 canonical provenance가 key에 포함된다. syntactic guard id만으로 합치지 않는다.
- `RepeatHeadProofKey(loop-head binding/slot, LoopDecision, projected PhaseContextKey) -> boolean`: guards, loops, iteration phases, cardinalities, call frame 중 해당 binding의 실제 dependency를 포함한다. `MemoShapes.PhaseContextKey`의 dependency projection 원리는 재사용할 수 있지만 (`MemoShapes.java:658-708`), cost proof가 읽는 dependency 집합이 완전하다는 검증이 전제다. binding id나 shape dimension만으로 cache하면 안 된다.

repeat-head miss에서는 현재처럼 FIRST/ENTRY seed와 MIDDLE/BACKEDGE repeat를 같은 `PhaseEvaluation`으로 계산한다 (`MemoShapes.java:396-411`). cache에는 mutable evaluation/Hop view가 아니라 완결된 boolean outcome만 넣는다. proof 도중 unresolved 또는 예외가 가능한 경로는 boolean false로 흡수하지 않고 기존 결과/예외를 같은 exact key에서 재현해야 한다. memory cap을 넘으면 현재 계산으로 fallback한다.

projection 결과의 primitive는 현재 방문 순서, guard/phase activation, weight의 `doubleToLongBits`, source/target pair를 보존한다. retained event grouping도 `(identity,target,unitCostBits)`를 유지한다. worker 수는 model-local frozen 값이며 cross-worker cache를 만들지 않는다. privacy legality는 이 cache에 흡수하지 않고 기존 `Check`에서 `LEGAL/ILLEGAL/DEFER`를 그대로 판단한다.

같은 gated lazy 방식은 `entryCost`에도 적용할 수 있다. 현재 `entryCost` 역시 `visitBindingCostContexts`와 contextual output view를 evaluator마다 반복한다 (`MemoProgramModel.java:766-799`). 1차 구현은 GLM과 LogReg 모두에서 직접 관측된 movement/guard proof부터 적용하고 entry factor는 factor-kind 계측 뒤 확장하는 것이 최소 변경이다.

### 2. normalization 뒤 bounded partial-tuple memo를 배열로 고정

`normalizeAfterPropagation`은 singleton projection과 동일 scope grouping을 끝낸 뒤 dependency index와 네 캐시를 다시 만든다 (`MemoOptimizer.java:249-285`). 이 시점에 최종 scope와 immutable domain size가 정해지므로 다음 구조를 factor/check별로 한 번 만든다.

```text
CompiledCheck
  owners: int[]
  radix[i] = domainSize[owners[i]] + 1     // -1 partial sentinel 포함
  truth: byte[]                            // UNSET, NO, UNKNOWN, YES

CompiledCostFactor
  owners: int[]
  radix[i] = domainSize[owners[i]]         // cost는 complete tuple만 허용
  values: BigDecimal[]                     // lazy fill
```

radix 곱이 명시적 memory cap 이하인 scope만 배열을 사용하고, overflow 또는 wide scope는 현재 map 경로로 남긴다. `CompiledCheck`는 partial tuple의 `-1`을 별도 ordinal로 직접 인덱싱하므로 predicate가 partial input에서 반환한 `UNKNOWN`을 leaf relation으로 추론해 바꾸지 않는다. `CompiledCostFactor`는 normalization 이후의 grouped evaluator를 정확히 한 번 호출한 `BigDecimal`을 저장한다. 이는 82M check-hit 경로와 3.4K cost-hit 경로의 `TupleKey/int[] clone/LinkedHashMap` 비용을 제거하면서 predicate와 objective 의미를 유지한다.

eager Cartesian evaluation은 피한다. 호출된 tuple만 lazy fill해야 현재 탐색이 보지 않는 비싼 movement tuple을 미리 생성하지 않으며, wide factor의 조합 폭발도 만들지 않는다.

### 3. domain-content packed key로 support/bound revisit 재사용

두 번째 단계로, small scope에 한해 `SupportKeyPlan`과 `BoundKeyPlan`을 normalization 후 만든다. 각 owner의 현재 `BitSet` 내용을 충돌 없이 pack할 수 있을 때만 primitive word key를 사용한다.

- support key: `(fixedOwnerPosition, fixedValue, exact domain bits for every check owner)`
- bound key: `(exact domain bits for every factor owner)`
- pack 불가 또는 wide scope: 기존 `TupleKey` fallback

현재 support key는 revision number를 사용하므로 undo 후 동일 domain으로 돌아와도 새 revision 때문에 이전 상태를 재사용하지 못한다. domain contents는 support 결과를 결정하는 실제 상태이며, support budget이 동일한 한 정확한 key가 된다. hash-only fingerprint는 collision이 최적성 오류로 이어질 수 있으므로 금지하고, 모든 domain bits를 비교하거나 충돌 없는 packed representation만 허용한다.

이 단계는 1, 2보다 변경 범위가 크다. 먼저 per-path allocation 계측으로 `TupleKey`, stamp, domain serialization이 실제 상위 allocation인지 확인한 뒤 적용한다.

## 정확성 경계

- **UNKNOWN:** partial predicate와 support budget exhaustion의 `UNKNOWN`을 캐시에 그대로 저장한다. `UNKNOWN`을 `NO`로 바꾸면 합법 규칙을 제거하고, `YES`로 바꾸면 증명되지 않은 plan을 최적이라고 할 수 있다. optimizer는 complete assignment에 UNKNOWN이 남으면 unresolved lower bound를 보존한다 (`MemoOptimizer.java:382-410`, `474-494`).
- **DEFER/privacy:** privacy `DEFER`와 unresolved conditional visit는 `Truth.UNKNOWN`으로 승격된다 (`MemoProgramModel.java:306-338`). movement primitive 준비는 이 결과를 선결하거나 캐시하지 않는다.
- **coverage:** native universe coverage가 열려 있으면 optimizer는 탐색 전에 `UNRESOLVED`를 반환한다 (`MemoOptimizer.java:221-246`). 사전계산은 coverage를 완성된 것으로 간주할 수 없다.
- **worker binding:** layout normalization과 movement cost는 worker pool에 의존한다. 모든 prepared 구조는 한 `MemoProgramModel` 및 frozen worker 수에만 유효하다.
- **exact primitive:** native/movement evaluator의 binary64 결과는 `new BigDecimal(double)`로 exact dyadic value가 된다 (`MemoOptimizer.java:59-71`, `1174-1177`). primitive의 합산 순서, grouping, double bits를 바꾸지 않는다.
- **optimality:** prepared factor는 동일 owner scope, 동일 finite value, 동일 certified minimum을 제공해야 한다. 최종 선택은 원래 ungrouped cost ownership model로 재평가되어 optimizer cost와 같아야 한다 (`MemoProgramModel.java:247-275`).

## 권장 구현 우선순위

1. **immutable guard-phase index + exact-context proof cache** — 최종 GLM에서 `provesRepeatHeadCostStats` 8,776, `phaseProvesGuard` 6,440, 선형 `guardValue` 6,305 samples가 한 stack에 있다. O(1) fact index를 먼저 적용하고, full semantic key를 쓰는 bounded repeat-head/phase proof cache를 추가한다. 이 shape-layer 개선은 movement와 input legality 양쪽에 적용된다.
2. **gate-preserving lazy movement projection** — LogReg 현재 optimize의 51.6%, 최종 GLM의 68.8%가 movement evaluator에 있다. `DIRECT/IMPOSSIBLE` 판정 뒤에서만 exact-context descriptor를 만들고 재사용해 `UNRESOLVED` 관측 경계를 유지한다. model-construction eager projection은 사용하지 않는다.
3. **input-legality context reuse, 이후 optimizer key 개선** — 최종 GLM의 disjoint input legality 28.7%/135.927 GiB를 full semantic-context cache로 줄이되 `UNKNOWN`, caller prerequisite, continuation, loop/cardinality를 그대로 보존한다. 그 뒤 LogReg 공통 병목인 partial-tuple array와 exact domain-state key를 적용한다. GLM의 disjoint solver-and-other는 10.28 GiB로 먼저 이 두 callback 경로를 고치는 편이 근거상 우선이다.

## 최소 향후 profiling probe

1. `MemoOptimizer.evaluateCost`의 cache miss에서 factor id/scope width/kind별 `evaluatorNanos`와 호출 수를 누적한다. JFR은 movement stack 증가를 확인했지만, 이 counter가 있으면 sampling 없이 factor별 절대 시간을 회귀 검증 기준으로 쓸 수 있다.
2. `evaluate`, `support`, `computeConditionalCostBound`, `stamp`에 allocation-free 카운터를 추가해 `TupleKeyCreated`, cloned int count, stamp long count, serialized domain int count를 기록한다. 시간 측정은 outer path별 누적 nanos만 사용하고 이벤트별 로그는 남기지 않는다.
3. gate-preserving projection 후보를 shadow mode로 실행하되 기존 evaluator가 실제 projection을 호출한 tuple에만 비교한다. primitive 순서와 모든 `doubleToLongBits`, unresolved/exception 상태를 비교하고, `DIRECT/IMPOSSIBLE` tuple에서는 shadow projection도 호출되지 않았음을 별도 counter로 확인한다.

## 미확정 사항

- movement projection이 현재 표본의 주된 증분 stack이라는 점은 확인됐지만, JFR sampling 비율을 절대 인과 시간으로 환산할 수는 없다. factor-kind nanos counter가 최종 분해 증거다.
- 최종 GLM timeout JFR은 관측 CPU가 movement/input-legality의 shape/guard 반복에 집중됐음을 확인한다. 그러나 비교 가능한 baseline optimizer run, OPTIMAL 종료, 최종 memo search counter가 없어 회귀 배율, 남은 search 양, 이 개선만으로 timeout이 해소되는지는 확정할 수 없다.
- movement factor별 scope 폭과 실제 unique tuple 분포가 현재 trace에 없다. eager table 크기를 정할 근거가 없으므로 lazy bounded 구조가 필요하다.
- current `MemoOptimizer`가 unchanged라는 사실은 검색 알고리즘 변경을 배제하지만, factor closure의 비용 변화나 JVM 상태 변화 중 어느 쪽이 시간 차이의 주원인인지는 배제하지 못한다.
