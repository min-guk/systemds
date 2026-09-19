# G009 search-space 생성 알고리즘과 GLM 병목 분석

작성일: 2026-09-19
기준 workspace: `/home/mchoi/systemds-g009-correctness`
기준 branch: `audit/g009-completeness`
기준 commit: `35d1f49507a9cf3566674e68c6b97ccc1354d079`
상태: **원인 경로 확인 / 장시간 GLM fixture 미완료**

## 1. 결론

현재 장시간 GLM fixture가 오래 걸리는 구간은 Exact/DP가 최적 plan을 선택하는 단계가 아니다.
모든 selector가 공유하는 `NeutralPlacementGraphBuilder.buildAnalysis(...)`가 neutral physical
search space를 만들고 각 `FED/FOUT` 후보의 정확한 worker-pool/layout continuity를 증명하는
단계다.

이 경로는 하나의 단일 알고리즘이 아니라 다음 계산을 결합한다.

1. 입력 FType 조합의 Cartesian-product 전수 열거
2. candidate별 AND/OR dependency proof graph 구축
3. reverse dependency를 이용한 불가능 대안 제거
4. Tarjan strongly connected component(SCC) 계산과 seed grounding fixed point
5. 입력 support 대안의 Cartesian-product 전수 열거
6. CFG, function boundary, transient relation, privacy와 physical realization이 안정될 때까지
   중첩 fixed-point closure 반복

현재 증거가 가장 강하게 지지하는 병목은 **candidate × durable seed × layout witness별 proof
graph 재구축을 여러 closure pass에서 반복하는 구조**다. 입력/support 조합 열거와 signature
생성·정렬·중복 제거도 비용에 기여하지만, 보존된 실행 자료만으로 각 원인의 정확한 시간 비율은
확정할 수 없다.

## 2. 분석 대상과 판정 경계

대상 테스트는 다음 메서드다.

`NeutralPlacementGraphUploadRelocationRedTest#rewrittenInlinedOutputRetainsItsCompilerDeclaredTargetAuthority`

테스트는 builtin GLM 프로그램을 compile한 뒤 `buildAnalysis(...)`를 호출하고 inlined function
result constraint의 존재만 확인한다.

- `src/test/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphUploadRelocationRedTest.java:254-262`
- GLM fixture 구성:
  `src/test/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphUploadRelocationRedTest.java:512-529`

이 메서드에는 planner selector, Exact solver 또는 DP 최적화 호출이 없다. 따라서 이번 장시간
실행을 "DP search가 느리다"고 해석하면 안 된다. 확인된 병목은 selector 이전의 공통
`PlacementAnalysis` 구축 경로다.

장시간 실행은 32 GiB fork heap과 shell/JUnit wall-clock timeout 없이 시작했지만 사용자 요청으로
중단했다. 완료 결과와 Surefire assertion 결과가 없으므로 **INCOMPLETE_NOT_PASS**다.

- 실행 metadata:
  `build/plan-space-audit-20260919/g009-correctness-unbounded-fork-v6/run.meta`
- 시작: `2026-09-19T03:17:25+02:00`
- 중단: `2026-09-19T05:10:45+02:00`
- 중단 사유: `user-requested-pause`

## 3. search-space 생성 알고리즘

### 3.1 프로그램 occurrence와 초기 candidate domain 생성

builder는 program의 HOP occurrence, CFG, shape, value version과 durable anchor를 구성한다. 각
occurrence에 대해 predecessor가 제공할 수 있는 local/FType domain을 수집하고 모든 입력 tuple을
열거한다.

- occurrence 및 node 구축:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:303-458`
- 입력 domain 구성:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:7718-7776`
- matrix FType 후보는 `ROW`, `COL`, `FULL`, `PART`, `BROADCAST`다:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateRuleResolver.java:27-29`

각 HOP의 입력별 domain 크기를 `d1, d2, ..., dk`라고 하면 초기 입력 tuple 수는 다음과 같다.

```text
d1 * d2 * ... * dk
```

구현은 이 조합을 lazy iterator로 처리하지 않고 재귀적으로 모두 열거해 `List`에 materialize한다.

- tuple별 rule/oracle 평가:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:5172-5264`
- Cartesian-product materialization:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:7850-7866`

각 tuple은 `oracle.decideWithEvidence(...)`와 profile inference를 거쳐 CP/FED, LOUT/FOUT, output
FType, shape proof, derived FOUT action 등의 candidate facts로 확장된다.

### 3.2 exact physical realization과 durable seed 확장

단순히 `FED/FOUT`이라는 논리 상태만 보존하지 않는다. 실제 실행 가능성을 판정하기 위해 다음
identity와 authority도 candidate realization에 포함한다.

- worker endpoint와 physical pool
- partition axis와 exact/dynamic range
- source realization identity
- direct input binding과 relocation binding
- durable anchor 또는 native-lineage proof
- CFG/function/transient relation의 reaching definition

`bindDirectNativeCandidateRealizations(...)`는 호환되는 FType의 durable anchors를 seed로 수집하고,
각 output candidate와 seed 조합에 대해 `NativePlacementContinuity.proveCandidateAlternatives(...)`를
호출한다.

- FType별 durable-anchor index:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:2542-2552`
- candidate와 seed별 continuity query:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:2656-2712`

이 FType index는 과거 각 candidate가 전체 node를 다시 scan하던 비용을 제거했지만, index 적용 뒤에도
대상 method는 180초 실행에서 완료되지 않았다. 따라서 전체-node seed scan은 과거 병목 중 하나였지만
현재 남은 주 병목을 설명하지 못한다.

### 3.3 candidate별 AND/OR proof graph

continuity query의 state는 개념적으로 다음 tuple이다.

```text
(compiled HOP occurrence,
 pinned realization identity,
 worker-pool/layout witness,
 template-root 여부)
```

한 realization이 가질 수 있는 여러 support clause는 OR 대안이고, 하나의 clause가 요구하는 여러
입력 dependency는 AND 조건이다.

각 query는 새 `graph`, `fixed` map과 root state를 만들고 DFS로 dependency graph를 확장한다.

- query별 새 graph 생성:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:211-219`
- recursive graph construction:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:435-445`
- rule → emission → realization → support-clause 대안 생성:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:449-530`
- CFG reaching definition과 physical input dependency 연결:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:533-576`

한 query 안에서는 `graph.containsKey(state)`와 active set으로 동일 state의 중복 traversal과 cycle
recursion을 막는다. support clause, anchor witness와 normalized candidate identity에 대한 작은 cache도
있다. 그러나 완성된 proof graph와 reverse-dependency 결과는 root candidate/seed query 간 공유되지
않는다. exact partition witness와 dynamic partition witness가 모두 가능한 경우에는 두 query가 별도로
수행된다.

- exact/dynamic witness 분기:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:172-197`
- 현재 query-local cache 필드:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:81-93`

### 3.4 dead-alternative pruning과 SCC grounding

생성된 graph는 다음 순서로 처리된다.

1. reverse dependency index를 만든다.
2. dependency가 없거나 이미 dead인 alternative를 queue로 제거한다.
3. Tarjan 알고리즘으로 SCC를 계산한다.
4. direct durable seed 또는 이미 grounded된 외부 component까지 경로가 있는 SCC를 fixed point로
   grounding한다.

- dead-alternative pruning:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:276-310`
- SCC 및 grounding fixed point:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:327-425`

따라서 순환 CFG가 단순히 무한 recursion에 빠지는 구현은 아니다. 다만 동일하거나 유사한 큰 proof
graph를 query별로 만들고 reverse index와 SCC를 다시 계산한다.

### 3.5 immediate support 조합 열거

root alternative가 grounded되면 각 input dependency가 선택할 수 있는 realization을 수집한 뒤 모든
조합을 다시 재귀 열거한다.

- option 수집과 proof 생성:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:228-261`
- Cartesian-product 열거:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:313-324`

입력별 viable support 수를 `r1, r2, ..., rk`라고 하면 생성 가능한 immediate proof 수는 최대 다음과
같다.

```text
r1 * r2 * ... * rk
```

완성된 proof는 authority와 input binding identity를 보존한 채 `distinct` 및 normalized-signature
정렬을 수행한다. 동일 비용 또는 동일 coarse placement라는 이유로 다른 proof witness를 합치지 않는다.

## 4. nested fixed-point 구조

위 continuity 계산은 한 번만 실행되지 않는다. builder에는 여러 closure가 중첩되어 있다.

```text
semantic fixed point
  -> relocation/action binding
  -> CFG/transient closure
       -> direct-native realization closure
       -> CFG replay
       -> physical candidate rebuild
       -> direct-native grounding 재실행
       -> exact relation replay
  -> privacy closure
  -> realization/action 재결합
```

- outer semantic closure:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:677-715`
- CFG/transient closure:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:2416-2533`
- CFG pass 내부 direct realization closure:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:2446-2463`
- physical rebuild 후 direct grounding 재실행:
  `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:2479-2515`

physical rebuild는 oracle-owned base emission을 복원하므로 candidate-specific native bindings를 다시
ground해야 한다. CFG/function/privacy 변화로 realization identity가 바뀔 수도 있어 exact relation도
다시 생성한다. 이는 correctness를 위한 반복이지만, 큰 GLM graph에서는 공통 upstream proof를
반복 계산하는 비용을 만든다.

## 5. 보존된 실행 증거

### 5.1 thread dump

두 thread dump의 main thread는 모두 `RUNNABLE`이며 다음 경로에 있었다.

```text
NeutralPlacementGraphBuilder.buildDetachedAnalysis
  -> closeCfgTransientCandidateDependencies
  -> bindDirectNativeCandidateRealizations
  -> NativePlacementContinuity.proveCandidateAlternatives
  -> buildCandidateProofGraph
  -> candidateProofAlternatives / candidateDependencies
```

- `build/plan-space-audit-20260919/g009-correctness-unbounded-fork-v6/thread-035014.txt`
- `build/plan-space-audit-20260919/g009-correctness-unbounded-fork-v6/thread-040040.txt`

첫 dump는 main CPU 약 1,945.6초 / elapsed 약 1,965.5초, 두 번째 dump는 CPU 약 2,567.3초 /
elapsed 약 2,591.2초였다. main thread가 대부분의 elapsed 동안 실제 계산을 수행했다는 증거이며,
network wait나 lock deadlock이 주원인이라는 설명과 맞지 않는다.

두 번째 dump는 `CandidateProofDependency` 생성과 `Objects.hash(...)`에서 채취됐다. 이는 dependency
state/signature 객체 생성 비용이 실제 hot path 안에 있음을 보여주지만, hash 계산 하나가 전체 병목의
대부분이라는 뜻은 아니다.

### 5.2 진행량과 heap

중단 직전 process는 약 116% CPU와 약 11.6 GiB RSS를 사용했다. swap은 사용하지 않았고 32 GiB heap
한도에 도달한 OOM 증거도 없다.

- `build/plan-space-audit-20260919/g009-correctness-unbounded-fork-v6/progress.log`

04:58 heap histogram에는 다음 live object가 있었다.

| 객체 | live instance 수 |
|---|---:|
| `CandidateRealizationInputBinding` | 470,658 |
| `CandidateRealizationSupportClause` | 364,738 |
| `CandidateRealizationReference` | 153,534 |
| `CandidateRuleFact` | 24,740 |
| `CandidateProofDependency` | 17,890 |
| `CandidateProofState` | 17,891 |

- `build/plan-space-audit-20260919/g009-correctness-unbounded-fork-v6/histogram-045818.txt`

이 값들은 실행 중 heap의 live object 수이며 final unique search-space cardinality가 아니다. histogram의
`byte[]` 전체를 proof signature 문자열로 해석해서도 안 된다. 다만 proof/support/binding 객체가 대량으로
생성·유지된다는 점은 직접 확인된다.

### 5.3 과거 bounded 진단과 현재 의미

같은 target의 과거 diagnostic은 한 시점에 약 2.3k graph nodes, 6.3k candidate facts, 6.5k
realizations를 관측했다. 별도 proof-query 표본은 약 2,827 states, 2,826 dependencies, 1,772
alternatives였고 query마다 reverse-dependency index가 다시 생성됐다.

- `docs/PLAN_SPACE_G009_PROGRESS_2026-09-19.md:118-149`

이 수치는 특정 진단 시점의 snapshot이며 이번 중단 실행의 final cardinality가 아니다. 그러나 seed index
최적화 이후에도 proof-state 탐색과 per-query graph/index 재구축이 남았다는 병목 판정과 일치한다.

## 6. 병목 순위

| 순위 | 설명 | 확신 | 근거 |
|---:|---|---|---|
| 1 | candidate × seed × witness별 proof graph 재구축 | 높음 | 두 actual thread dump가 동일 graph-build 경로에 있음 |
| 2 | CFG/physical/privacy fixed point에서 continuity 계산 반복 | 높음 | source의 nested closure와 `NativePlacementContinuity` 재생성 |
| 3 | input tuple 및 immediate support의 Cartesian-product 팽창 | 높음 | 두 재귀 enumerator가 source에 명시됨 |
| 4 | normalized signature, hashing, sorting, distinct 및 객체 allocation | 중간 | stack과 heap histogram이 비용 기여를 지지하지만 비율 미측정 |

## 7. 판정할 수 없는 내용

현재 증거로 다음을 주장하지 않는다.

- Exact/DP solver가 이번 장시간 실행의 병목이라는 주장
- 32 GiB heap 부족 또는 OOM이 원인이라는 주장
- CFG cycle이 무한 recursion을 만들었다는 주장
- `enumerateImmediateSupports(...)`가 전체 시간의 대부분이라는 주장
- 중단된 실행이 언젠가 유한 시간 안에 완료된다는 주장
- 일부 candidate/proof를 제거해도 completeness가 보존된다는 주장

각 phase의 정확한 시간 비율, closure pass 횟수, query별 graph 중복률과 최종 proof cardinality는 별도
instrumentation 없이는 확정할 수 없다. 성능 변경을 평가할 때는 root realization, durable seed,
fixed/pinned identities, exact/dynamic layout witness를 모두 포함한 query identity를 사용하고, 적용 전후
전체 proof set의 equality를 확인해야 한다. candidate cap, sampling, privacy 완화 또는 runtime fallback으로
완료 시간을 줄인 결과는 G009 correctness 해법이 아니다.

## 8. 최종 요약

현재 neutral search-space 생성기는 다음 구조다.

```text
exhaustive input-domain enumeration
  + candidate-specific physical realization expansion
  + recursive AND/OR continuity proof graph
  + reverse-dependency pruning
  + Tarjan SCC grounding
  + exhaustive support combination
  + nested semantic/CFG/privacy fixed points
```

GLM fixture의 큰 inlined function/CFG에서는 많은 후보가 공통 upstream proof를 공유하지만, 현재
구현은 proof graph 결과를 root query 간 공유하지 않는다. 이 반복 계산이 확인된 주 병목이다. 다만
완전한 proof witness와 authority identity를 보존해야 하므로, 향후 최적화는 search space를 축소하는
방식이 아니라 동일 proof set을 재사용하는 방식으로 검증되어야 한다.
