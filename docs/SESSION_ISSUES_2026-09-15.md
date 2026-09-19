# Session Issues — 2026-09-15

## 공유 분석의 경우의 수 증가와 planner 공통 전처리 경계

**상태: 공유 분석의 의미 보존 표현 최적화와 해당 회귀/대조 측정 완료. 전체 plan-space 완전성 감사와 Docker runtime qualification은 미완료.**

### 문제 정의와 고정 계약

이번 우선 과제는 (1) 모든 valid physical plan의 보존, (2) planning 전 후보 증가 위치와 나이브한 확장 여부, (3) DP 전처리 중 AggLocal/FedFirst와 공유할 범위를 답하는 것이다. 새 표현 계층 구현이나 전체 runtime campaign을 시작하는 단계가 아니다.

공유 표현 S의 계약은 `Decode(S) = 모든 합법적인 원래 physical plan의 집합`이다. 모든 조합을 미리 열거할 필요는 없지만, 선택된 source realization, support clause, DIRECT/RELOCATION action, worker pool, 함수 경계 및 모든 reaching writer의 권한 관계가 정확히 복원되어야 한다. 동일한 최소 목적값만으로 이 계약을 검증할 수 없다.

기존 privacy, TR/TW의 CP/LOUT 또는 FED/FOUT 제약, recompile 구간 CP/FOUT 금지, runtime fallback 금지를 유지한다. 비용 계수와 planner 정책은 변경하지 않는다. 후보 수 제한, top-k, equal-FType/equal-cost만을 근거로 한 공통 후보 삭제는 해결책이 아니다.

### 환경과 관측 근거

- 저장소: `/home/mchoi/systemds-lm-worker-count-fix`, host `dams-so002`, HEAD `ffb7be5bd8`. 기존 dirty tree를 보존했다.
- 기존 probe: PUBLIC X=50,000×2,100, Y=50,000×1, localhost:1234/1235 두 worker의 hermetic source privacy를 사용하는 multiLogReg compiler fixture. Planning-only이며 workload runtime 결과가 아니다.
- 증거 디렉터리: `/grid/3/cofee-lm-sweep-mchoi-20260914/shared-analysis-probe-hvamla3y`.
- `SharedAnalysisProbe.java`, `launch.json`, `classpath.txt`, `profiled.log`, `builder.jfr`, `source-class-sha256.txt`가 남아 있다. `probe.log`는 Vector API module 옵션 누락으로 실패한 첫 시도이며 성능 근거로 사용하지 않는다. 수정된 JVM 옵션의 실행은 기존 기록에서 `PROFILE_EXIT=0`으로 완료되었다.
- 이번 resume에서 `profiled.log`를 다시 읽고 manifest의 5개 Java 소스와 5개 최상위 class hash 일치를 확인했다. 이것은 전체 소스/모든 nested class의 build correspondence를 새로 인증한 것이 아니다.

| 측정 항목 | 저장된 결과 |
| --- | ---: |
| compileFixture: parsing/HOP construction/rewrite/privacy 등록 | 1.2640078 s |
| buildAnalysis, JFR profiling 포함 | 64.955682101 s |
| 최종 candidate rule facts / AVAILABLE | 531 / 531 |
| 최종 emissions / realizations / support clauses | 565 / 597 / 771 |
| input bindings / realization당 최대 clauses | 252 / 37 |
| transient relations / function-boundary relations | 174 / 12 |
| JFR execution samples | 2,809 |

최종 fact/realization/clause 수는 전체 valid assignment 수가 아니며 중간 상태의 최대 크기도 아니다. 최종 표현이 작다는 이유로 실제 plan space가 작다고 단정하지 않는다.

JFR 집계는 각 stack의 leaf에서 처음 만나는 fedplanner frame으로 분류했다. `PlacementIdentity.appendToken` 1,112개와 `CandidateRealizationSupportClause.normalizedSignature` 864개가 합계 1,976/2,809, 약 70.3%이다. 이는 sampled execution attribution이지 wall-clock phase 비율이나 호출 횟수가 아니다.

`jdk.ObjectAllocationSample.weight` 합계는 306,978,038,568 bytes, 약 307 GB이다. 이는 builder 기록 구간의 누적 allocation 추정치이며 peak heap/RSS 또는 동시에 살아 있는 객체 크기가 아니다. 상위 allocation attribution 역시 signature/fields/appendToken 계열이다.

**관측에 가장 잘 맞는 해석:** 이 fixture에서는 shared builder의 반복 signature 생성·비교·정규화가 주요 병목이라는 근거가 강하다. 그러나 intermediate product counters와 수정 전후 대조 실험이 없으므로 Cartesian 생성, closure 반복, signature 처리 각각의 정확한 비용 및 예상 개선율은 미확정이다.

### 코드에서 확인한 확장 위치

아래 경로는 `/home/mchoi/systemds-lm-worker-count-fix/src/main/java/org/apache/sysds/hops/fedplanner/` 아래의 현재 working-tree 기준이다.

| 단계 | 직접 확인한 코드 | 의미와 판단 |
| --- | --- | --- |
| 입력 FType tuple | `placement/NeutralPlacementGraphBuilder.java:4934`, `:7579-7594` | 입력별 domain의 Cartesian product를 먼저 list로 만들고 각 tuple에 oracle을 호출한다. 원시 tuple 수는 입력 domain 크기의 곱이다. 이 곱 자체가 완전성을 위해 항상 물리적으로 열거되어야 한다는 뜻은 아니다. |
| Native direct support | `placement/NativePlacementContinuity.java:201-240`; builder `:2593-2620` | grounded immediate input options를 곱으로 열거한다. Builder는 호환 FType의 seed anchor를 전체 nodes에서도 수집해 검사한다. 이들 확장이 해당 fixture 시간의 몇 %인지는 미측정이다. |
| Relocation binding | builder `:5303-5364`, `:5402-5413` | target pool별로 각 입력의 DIRECT/RELOCATION bindings를 모은 뒤 전체 assignment list를 생성한다. 재귀 열거 helper 자체에는 입력 간 partial consistency 검사가 없다. 개별 binding 필터링은 그 전에 존재한다. |
| CFG/physical/privacy closure | builder `:2360-2477`, `:643-835` | direct closure, transient replay, physical rebuild, grounding, privacy, relocation publication이 반복된다. 하나의 전체 pass뿐 아니라 nested direct fixed point도 있다. 다만 `closePostCfgPhysicalCandidateDependencies`에는 이미 changed-ordinal worklist가 있으므로 전체 builder가 매번 무조건 모든 node를 rebuild한다고 표현하면 부정확하다. |
| 정렬 및 병합 | `placement/PlacementAnalysis.java:321-329`, `:466-487`; builder `:5638-5639` | clause 비교 때 signature를 새로 만들고 TreeSet으로 병합한다. 일부 호출자는 생성자 병합 전에도 distinct/sorted를 수행한다. JFR에서 관측된 비용과 직접 연결되는 경로다. |

**중요한 반례/범위:** transient writer→reader replay 자체를 여전히 모든 writer 선택의 곱이라고 설명하면 틀리다. Builder `:2895-2930`은 reader realization과 source별 compatibility relation을 별도로 보관한다. 일반 multi-input AND/OR 관계를 단순 pairwise relation으로 바꾸는 것은 별도의 losslessness 증명 없이 허용되지 않는다.

실제 선택 가능성의 증가와 표현/계산량의 증가는 분리한다. 복구한 함수/loop 의존성이 필요 없었다고 되돌릴 근거는 없으며, Local/Global의 동일 목적값도 builder completeness의 증거가 아니다.

### DP 전처리를 어디까지 공통화할 것인가

**결론: objective-free 합법성 관계·인덱스·강제 상태까지 공유하고, 특정 정책/목적함수/경계 선택에 의존하는 축약은 planner-local view로 유지한다.** DP reducer 전체를 shared builder 뒤에 붙이는 것으로 이미 발생한 builder의 곱 열거 비용이 사라지지는 않는다.

| 작업 | 공통화 판단 | 필요한 조건 |
| --- | --- | --- |
| canonical identity/receipt 재사용, forward/reverse dependency index | 적합 | source/realization/clause/action identity 및 기존 canonical order를 보존하고 analysis 소유권·수명을 유지한다. |
| SAME_PLACEMENT 그룹과 component incidence | 적합 | 합쳐지는 것은 실제 equality decision이며 세부 physical alternatives를 임의로 합치지 않는다. 함수 경계·역방향 support·공유 relocation 등 모든 결합을 포함한다. 비용 factor가 component를 추가로 연결하면 solver에서 반영한다. |
| hard-constraint support propagation | 조건부 적합 | 실제 불가능성이 증명된 값만 제거한다. 아직 생성되지 않은 loop/relocation support를 불가능으로 오인하지 않는다. unary/binary arc consistency 통과는 전역 feasible함의 증명이 아니므로 원래 higher-arity 관계를 유지한다. |
| hard legality가 강제한 singleton | 적합 | 원래 forced value와 occurrence-owned identity를 정확히 복원한다. |
| 비용 및 tie-cost에 따른 quotient | 현재 구현 그대로 공통 universe에 적용하지 않음 | DP는 모든 incident factor의 raw values와 tie cost를 비교한다. 동일 비용만으로 다른 planner 정책이나 물리적 identity의 동치가 증명되지는 않는다. |
| quotient 후 singleton, boundary-conditioned reduction, VE order/table | solver-local | 목적함수/고정된 경계/solver 자원에 의존한다. AggLocal/FedFirst의 공통 전제조건으로 만들지 않는다. |
| AggLocal demotion, FedFirst PRESENT preference | policy-local | 공유 analysis는 유지하고 정책별 view/선택 단계에서 적용한다. |

근거:

- `fedCostBased/fedExact/ExactPhysicalReducedSolver.java:560-606`: 현재 support propagation은 unary/binary factor의 finite-support 유무를 검사한다. 호출 시 factor 집합에 비용과 선택적 forced-state factor도 들어갈 수 있으므로 현재 메서드를 그대로 이동하는 것은 공통 hard-legality 분석과 다르다.
- `fedCostBased/fedExact/ExactPhysicalOptimizer.java:41-60`, reducer `:619-709`: hard factors와 cost factors를 합친 뒤 quotient하며 raw double bits와 tie cost를 비교한다.
- Reducer `:89-120`, `:472-482`: source→reduced mapping은 보관하지만 기본 `sourceValue`/`expandAssignment`는 대표값 하나를 반환한다. **따라서 quotient 자체가 본질적으로 비가역이라고 단정하면 안 된다.** 원본과 전체 member mapping을 유지하면 동치류 표현이 가능하다. 다만 현재 대표값 복원 인터페이스를 모든 planner의 원래 후보 집합으로 대체하는 것은 요구 계약과 다르다. 이 분석은 현재 DP quotient를 버그로 판정한 것이 아니다.
- `fedCostBased/fedExact/RegionalSearchProblem.java:31-45`, `LocalPhysicalOptimizer.java:68-89`: local seed와 incremental 단계는 이미 동일 reduced root를 재사용한다.
- `fedCostBased/fedExact/SharedRegionalPreparation.java:165-202`: conditioned factor cache가 있으며 non-compact slice는 root 전처리를 재사용한다. Compact slice는 다시 prepareCompacted를 호출하나, boundary conditioning으로 새 support/동치가 생길 수 있어 무조건 불필요한 중복이라고 단정할 수 없다.
- Reducer는 freeze→reduced dense rebuild→compact dense rebuild 경로를 가진다. 이는 별도 materialization 최적화 후보이지 이 fixture에서 확인된 DP 병목이나 plan-space 폭증의 실측 증거는 아니다.
- `placement/adapter/FedAllPlacementAdapter.java:59-71`은 기본 PolicyFirstFeasible selector를 사용한다. `placement/adapter/HeuristicPlacementAdapter.java:65-80`은 AggLocal demotion에 해당하는 별도 policy view를 같은 selector에 전달한다. `placement/selector/PolicyFirstFeasiblePlacementSelector.java:219-242`, `:1052-1080`에는 component 구성과 SAME_PLACEMENT grouping이 이미 존재한다. 공유할 것은 이들 공통 불변 정보이지 모든 정책을 DP solve로 바꾸는 것이 아니다.

### 정확도에 대한 현재 한계와 회귀 설계

전체 valid planning space 보존을 완료로 선언하지 않는다. `placement/CandidateSelections.java:1189-1201`의 consumer별 separability gate는 자신의 input bindings와 transient/function endpoints를 확인하지만, 다른 consumer의 support가 자신을 참조하는 모든 incoming 관계를 직접 검사하지 않는다. 도달 가능한 실제 후보 손실 fixture는 아직 없으므로 이는 감사 대상이지 production 실패를 재현한 결론이 아니다. `LogicalBoundaryComponentClosureTest`의 기존 reflection은 이전 전역 helper를 호출하므로 이 per-consumer gate의 완전성을 인증하지 않는다.

의미 변경 전 최소 acceptance는 작은 독립 oracle의 전체 valid-plan 집합과 변경 후 복원 집합을 비교하는 것이다. 최소 목적값 비교만으로 대체하지 않는다. Fixtures에는 다중 입력 AND/OR correlation, 반복 producer 입력, 다른 source realization과 action, 여러 pool/partition, 함수 actual/formal/result, 모든 reaching writer, seed가 있는/없는 identity loop, privacy 및 recompile 경계를 포함한다. 후보 방문 순서·cache on/off·closure 재실행에도 같은 집합과 동일한 authority가 나와야 한다. canonical 선택 순서를 바꾸지 않는 최적화는 기존 receipt/order까지 비교한다.

### 해결 방향과 잔여 작업

첫 수정 후보는 새로운 IR이나 후보 삭제가 아니라 기존 immutable identity/receipt 및 동일 결과의 재사용, 반복 signature 생성·병합의 제거이다. 이후 증분 dependency 갱신과 확정 hard-support 검사를 실제 곱 생성 지점에 연결할 수 있는지 평가한다. 전체 곱을 생성한 뒤 DP 전처리만 앞쪽 이름으로 이동시키는 것은 충분하지 않다. Lazy/factorized 표현은 원래 선택들을 전부 복원할 수 있을 때만 채택한다.

아직 필요한 계측은 각 확장 지점의 attempted/retained/deduplicated counts, 최대 intermediate size, direct/CFG/semantic/publication pass 횟수와 변경량이다. 최종 cardinality 및 JFR sampling은 이를 대체하지 않는다. 안전한 표현 최적화 전후 대조 실험 전에는 개선율을 주장하지 않는다.

**잠재 회귀 위험:** 불완전한 dependency key로 인한 stale-cache 재사용, canonical order 변경, unbounded signature cache의 retained-memory 증가, 아직 생성 중인 support의 조기 삭제, AND/OR correlation 및 loop grounding 손실. 전체 집합 비교·cache 교차 검증·closure idempotence·메모리 계측으로 검출한다.

이전 Local/Global 비교에서 `analysis_s`는 compileFixture+builder였고, 이번 분리 probe는 그 경계를 명확히 했다. 이전 Local entry point는 one-shot seed뿐 아니라 incremental optimization을 포함하며, 반환된 zero counters는 전체 local 실행의 무탐색을 뜻하지 않는다. 두 optimizer가 선택한 목적값/assignment의 일치는 구성된 모델 내부의 결과이며 모든 workload나 전체 valid space를 인증하지 않는다.

### 이번 resume의 변경·검증 상태

- production에서 immutable `CandidateRealizationSupportClause` 및 `CandidateEmissionRealization`의 canonical signature를 기존 compiler-thread-local weak-key cache로 재사용한다. `CandidateEmissionFact.mergeRealizations`는 realization이 하나이면 이미 canonical인 원래 객체/OR clause를 그대로 보존한다. 후보 생성·제거, equality/hash, 비용 계수, policy, privacy/loop/recompile authority는 바꾸지 않았다.
- `CandidateRealizationCanonicalizationTest`는 cold/warm bytes와 ordering, same-object serialization reuse, hash collision, immutable input, 모든 작은 alternative 부분집합·순열·중복의 전체 realization/clause/receipt relation, singleton authority, invalid input, compiler-thread/cache reset을 잠근다. realization-signature 재사용 테스트는 production 변경 전에 동일 bytes이지만 `assertSame`이 실패하는 red를 확인한 뒤 green으로 전환했다.
- fresh focused authority suite와 16-class planner/privacy/closure regression은 모두 `EXIT 0`이다. 여기에는 이전 handoff blocker였던 `SharedPlannerFunctionPlanPropagationRedTest`, `ExactPhysicalModelCertificateTest`, `LogicalBoundaryRealizationsTest`, native continuity, transient alternatives, publication closure, privacy movement 및 exact transient/native-local 회귀가 포함된다.
- 전체 분석 결과를 단순 최적값이 아니라 `graph`, `analysis fingerprint`, 전체 `rule facts`, 전체 `receipts`, `transient relations`, `function-boundary relations` 6개 snapshot으로 비교했다. original-before → after → after → original-before 네 fresh JVM의 SHA-256가 전부 byte-for-byte 같았고, realization signature cache 추가 뒤 두 fresh JVM도 같은 original snapshot SHA-256와 일치했다.
- 첫 의미 보존 최적화 전 builder 평균은 `(61.539107056 + 64.947788034) / 2 = 63.243447545 s`, support-clause cache + singleton fast path 뒤 평균은 `13.207969261 s`, realization-signature cache까지 적용한 현재 평균은 `13.012097794 s`이다. original 대비 약 `4.86x`, wall time 약 `79.43%` 감소다. 두 번째 cache 자체의 시간 개선은 약 `1.48%`로 작아 추가 문자열 cache의 수익은 빠르게 줄고 있다.
- JFR sampled allocation weight 평균은 original `307,002,932,744 bytes` → 첫 최적화 `13,347,471,068 bytes` → 현재 `8,765,086,600 bytes`이다. 이는 peak heap/RSS가 아니라 allocation sampling weight이며, original 대비 약 `97.14%` 감소다. 현재 hotspot은 `PlacementCostSemantics.latentWdivmmTransposePairFact`, identity hash/equality, direct-native binding/replay 등으로 분산되어 있어 반복 signature 하나가 절대적으로 지배하던 초기 상태와 다르다.
- 첫 대조/원본 class evidence: `/grid/3/cofee-lm-sweep-mchoi-20260914/canonical-reuse-20260915-gn1GDa`. realization-signature cache의 fresh 회귀/JFR/snapshot evidence: `/grid/3/cofee-lm-sweep-mchoi-20260914/realization-signature-cache-20260915-224741`.
- 기존 D1/D3/H2/H3 후속 검증, `CandidateSelections` incoming-support separability 감사, BG014 및 ML10/P1/P2/SliceLine Docker runtime qualification은 이 최적화로 완료된 것으로 표시하지 않는다. Commit/push/reset/checkout은 하지 않았다.

## 구현: canonical signature 재사용과 singleton 병합 제거

**상태: 구현·회귀·fresh before/after snapshot 대조 완료.**

- immutable support clause와 realization의 동일 canonical serialization을 기존 compiler-thread-local weak-key cache에서 재사용한다. realization이 정확히 하나인 emission merge는 원래 realization을 유지한다.
- 캐시가 만드는 문자열 bytes와 `compareTo` 순서는 기존 직접 serialization과 동일하다. equal/hash semantics와 ownership identity는 record 자체가 계속 담당하고 cache는 immutable serialization만 보관한다.
- 작은 exhaustive relation oracle은 subset/permutation/duplicate 입력에서도 모든 exact AND clause와 OR alternative가 남는지 비교한다. singleton fast path에서도 이미 합쳐진 여러 OR clause와 원래 authority 객체를 보존한다.
- compiler-only multiLogReg fixture의 최종 cardinality는 변경 전후 모두 `531 rules / 565 emissions / 597 realizations / 771 clauses / 252 bindings / max 37 clauses / 174 transient relations / 12 function relations`로 동일하다. 더 강한 검증으로 위 6개 전체 canonical snapshot SHA가 모든 before/after run에서 일치한다.
- 두 번째 realization cache 뒤에도 builder 시간은 약 13초로 남는다. 다음 성능 작업은 signature cache 확대를 기본값으로 하지 않고, expansion 지점별 attempted/retained/deduplicated/intermediate-size 및 closure changed-count를 계측해 실제 남은 비용을 분해한 뒤 결정한다. 이 계측은 후보를 제거하지 않으며 shared-analysis completeness 계약을 변경하지 않는다.

## 감사/수정: incoming realization support의 separability 완전성

**상태: per-consumer dependency gate의 계약 불일치를 실제 compiler fixture에서 확인하고 test-first로 수정·회귀 완료. 현재 관측 workload에서 실제 최적 plan 누락은 재현하지 않았다.**

- component union 자체는 이미 transient/function endpoint와 `requiredInputSupport()` source owner를 연결하고 있었다. 그러나 `consumerHasRealizationDependencies`는 consumer 자신의 support clause만 검사해, **다른 consumer가 이 consumer의 exact realization을 참조하는 incoming support**를 놓칠 수 있었다.
- compiler-only multiLogReg fixture에서 실제 incoming-only participant를 확인했다: `b(<):parsertemp644`의 realization support가 `Fed Y:Y`의 exact durable realization을 5회 참조하지만, 수정 전 `Fed Y:Y`의 consumer-local gate는 `false`였다.
- 더 작은 실제 fixture `Y=federated(...); Z=(Y<0)+1; write(Z,...)`에서도 같은 비대칭을 재현했다. 이를 `CandidateIncomingSupportCompletenessTest`로 고정했고, production 변경 전에는 `expected true but was false`로 red, 변경 후 green임을 확인했다.
- 최소 수정은 objective/cost/tie를 바꾸지 않고 objective-free realization dependency participant 집합을 양방향으로 계산한다. transient/function relation의 양 endpoint, support clause owner, 그리고 모든 `requiredInputSupport()` referenced owner를 같은 identity set에 넣는다. `PartialReachabilityIndex`와 complete-assignment materialization reduction은 이 집합을 재사용한다.
- 이 수정은 dependency participant에 대해 consumer-separable row reduction을 **덜 적용**하므로 valid row를 새로 제거하지 않는다. 즉 plan-space 보존 측면에서 보수적인 방향이다.
- 현재 multiLogReg에서 incoming-only `Fed Y:Y`가 활성화되는 상태를 강제로 검사했을 때 그 exact state의 feasible row는 1개였다(`feasible=1`, `maximal=1`). 따라서 이 workload에서 기존 gate 때문에 실제 row/plan이 누락됐다는 결론은 내리지 않는다. 확인한 것은 정적 계약 불일치와 향후 다중-row participant에서의 손실 위험이다.
- fresh regression: `CandidateIncomingSupportCompletenessTest + LogicalBoundaryComponentClosureTest`는 `EXIT 0`. 이어서 canonicalization, transient/function authority, native continuity, publication/worker-pool closure, privacy, `ExactPhysicalModelCertificateTest`, `SharedPlannerFunctionPlanPropagationRedTest` 등을 포함한 17-class sequential suite도 `EXIT 0`; `git diff --check` clean이다.
- 보존된 pre-optimization class와 현재 class를 같은 multiLogReg fixture에서 global DP까지 실행한 결과 objective raw bits `4671804882457794898`과 전체 categorical assignment가 동일했다. builder는 해당 두 실행에서 `59.623106302 s -> 11.929883048 s`였다. 이 대조는 동일 optimum 증거이지 전체 valid plan-space 완전성 증명으로 사용하지 않는다.
- 현재 분석을 다시 생성한 6개 canonical snapshot(`graph`, `analysis fingerprint`, 전체 `rule facts`, 전체 `receipts`, transient relations, function relations)의 SHA-256도 원래 보존 baseline과 전부 동일했다. fresh builder/JFR run은 `12.408880979 s`, `531/565/597/771 rules/emissions/realizations/clauses`, `252 bindings`, `174 transient`, `12 function`으로 기존 cardinality와 동일하다.
- evidence: `/grid/3/cofee-lm-sweep-mchoi-20260914/incoming-support-completeness-20260915-230734`.

전체 plan-space 완전성은 아직 완료로 선언하지 않는다.

## correctness 감사: D3 / H2 / H3

### D3 — identity-loop backedge와 all-reaching publication

**상태: 현재 production replay가 이미 의도한 계약을 만족함을 compiler fixture로 검증했다. validator 완화나 production 수정은 하지 않았다.**

- 일반 `rewriteHopsDAG`는 `B=B` identity copy를 제거하므로 해당 입력은 D3 경로 자체를 만들지 않았다. 이를 결함이 없다는 근거로 쓰지 않고, parser/live-variable/validation/HOP construction은 그대로 사용하되 rewrite만 생략한 fixture로 exact `TWrite(B <- TRead(B))` loop backedge를 보존했다.
- `TransientPlacementAlternativesTest.identityLoopBackedgeRestoresAllReachingTransientRelations`는 loop read의 authoritative CFG writer 집합에 외부 seed와 identity backedge가 모두 있음을 먼저 확인한다. 그 다음 최종 `logicalTransientInputsForReader` writer 집합이 CFG writer 집합과 정확히 일치하고, 각 executable reader realization이 **모든** reaching writer의 support를 가지며, rebuild candidate/relation signature가 동일함을 검사한다.
- 현재 builder의 provisional loop seed는 첫 closure bootstrap에만 쓰이고 `installedLoopSeeds` 이후 replay는 전체 `definitions`를 사용한다. focused test와 전체 `TransientPlacementAlternativesTest`가 green이다. `PlacementAnalysis`의 all-reaching validator는 그대로 유지했다.

### H2 — known-bottom matrix input을 `ABSENT_LOCAL`로 바꾸는 경로

**상태: 계약 불일치를 test-first RED로 재현하고 최소 수정했다.**

- 기존 일반 `inputDomains`는 predecessor node가 존재하지만 `legalAlternatives()`가 empty여도 `types.isEmpty()`를 통해 `[null]`, 즉 `ABSENT_LOCAL`을 만들었다. `CandidateInputBottomDomainTest`가 수정 전 `known-bottom matrix input must have an empty candidate domain, not ABSENT_LOCAL`로 red였다.
- 수정 후 존재하는 predecessor가 empty legal domain이면 해당 input의 exact domain을 empty로 유지한다. 실제 CP/LOUT predecessor는 기존대로 `[null]`을 유지하므로 genuine coordinator-local input 의미는 바꾸지 않는다.
- `buildNode`는 input domain 중 하나라도 empty면 CP/LOUT 후보를 새로 만들지 않고 node를 bottom으로 전파한다. 그렇지 않으면 empty domain만 고쳐도 기본 CP state가 남아 같은 불가능성을 다른 표현으로 되살릴 수 있기 때문이다.
- `CandidateDomainRefinement`는 이미 empty exact domain을 strict refinement/remove witness로 처리하므로 별도 heuristic pruning을 추가하지 않았다. 이는 valid row를 임의로 삭제하는 규칙이 아니라 upstream authoritative domain이 이미 bottom인 경우 그 불가능성을 downstream에 보존하는 것이다.
- 이 변경으로 PRIVATE/PRIVATE_AGGREGATE 경로의 bottom이 privacy closure보다 늦게 드러나는 경우 기존 privacy 오류가 generic `NO_EXECUTABLE_REALIZATION`로 재분류되는 회귀가 한 번 발생했다. 불법 state를 복원하지 않고, final required-emitted check에서 authoritative privacy가 origin residency를 요구하면 기존 `No privacy-safe physical placement` fail-closed `DMLRuntimeException`을 유지하도록 원인 분류만 보정했다. `matchingFTypeDoesNotAlignDifferentWorkerPools`가 다시 green이다.
- 특수 `reachesFunctionInput` domain 경로는 여러 call-site/formal source의 union 의미가 있어 같은 규칙을 기계적으로 적용하지 않았다. 별도 bottom fixture 없이 function-boundary domain을 축소하지 않는다.

### H3 — unavailable all-local row가 FED/LOUT authority로 사용되는 경로

**상태: projection 단계에서 test-first RED로 재현하고 status gate를 최소 수정했다.**

- 기존 `projectCandidateNodesToExecutableStates`는 `hasInputlessCandidate`를 `AVAILABLE` status 검사 전에 계산했다. 따라서 `PROFILE_ERROR`/`PRIVACY_EXCLUDED` all-`ABSENT_LOCAL` diagnostic row만 있어도 executable realization이 없는 FED/LOUT state를 유지할 수 있었다.
- `ExecutableProjectionAuthorityTest.unavailableAllLocalRowCannotKeepFederatedLocalState`는 수정 전 정확히 이 state가 남아 red였다.
- 수정은 `hasInputlessCandidate` authority를 `AVAILABLE` candidate에만 한정한다. 정상 AVAILABLE all-local native FED/LOUT 예외는 유지하고, CP/LOUT는 candidate-realization authority와 무관하게 계속 유지한다. 비용/정책/tie-breaker는 변경하지 않았다.

### fresh 회귀 결과

- D3/H2/H3 focused bundle은 모두 green이다.
- 최종 fresh sequential Maven bundle은 **19 classes / 123 tests / 0 failures / 0 errors**였다. `ExactPhysicalModelCertificateTest` 8/8, `LogicalBoundaryRealizationsTest` 6/6, `SharedPlannerFunctionPlanPropagationRedTest` 6/6, `PrivacyMovementCertificationTest` 11/11을 포함하며 incoming-support, canonicalization, native continuity, worker-pool traversal, transient authority, publication closure, shared privacy, exact transient/native-local cost 회귀도 함께 통과했다.
- full valid-plan set oracle은 여전히 별도 미완료다. 위 결과는 D3/H2/H3 계약과 기존 authority 회귀를 강하게 잠그지만, 모든 legal physical plan의 전역 completeness 인증을 대신하지 않는다.
- BG014 및 ML10/P1/P2/SliceLine Docker runtime qualification도 완료로 표시하지 않는다. 기존 handoff의 so007 image digest parity 문제를 lifecycle authority로 해결하기 전 full campaign을 시작하지 않는다.
- commit/push/reset/checkout은 하지 않았다.

## 후속 correctness 감사: D1 / H1 / D4

### D1 — relocation score cache exactness

**상태: unsafe effect compression을 제거했고 focused/broad regression을 fresh 재검증했다.**

- 기존 `CandidateSelections.Search`의 relocation score cache는 서로 다른 exact realization/support-clause authority를 하나의 coarse effect로 재사용할 수 있었다. DIRECT/RELOCATION 활성화, source worker pool, proof/authority가 실제 feasibility와 physical emission 수를 바꿀 수 있으므로 동치가 증명되지 않은 receipt를 합치면 안 된다.
- 처음에는 `RelocationSelections.CandidateProblemIndex`의 effect key에 exact receipt signature를 포함해 lossless하게 만들었다. 그러나 canonical candidate domain에서는 서로 다른 exact receipt의 signature가 서로 다르므로 이 조건에서는 cache compression/hit 자체가 성립하지 않는다. 즉 correctness를 유지하면서 얻는 재사용이 없는 dead optimization이 된다.
- 최종 수정은 `CandidateSelections.Search.relocationScoreCache` 및 effect-rank/multiplier bookkeeping과 `RelocationSelections.exactScoringEffect` projection을 제거하고, 각 exact leaf에서 기존 incremental `ExactEmissionScorer.minimumPhysicalEmissionCount()`를 직접 사용한다. scorer 정책, relocation 비용, canonical order, candidate domain은 변경하지 않았다.
- `PrivacyMovementCertificationTest#indexedRelocationHonorsExactDirectAndRelocationBindings`는 same-pool DIRECT, shifted-pool RELOCATION, 그리고 physical shape는 같지만 proof authority가 다른 exact source receipt를 각각 canonical selector와 indexed scorer에 대조한다. fresh 결과는 **11/11 PASS**다.
- D1 변경 뒤 fresh broad regression: `CandidateIncomingSupportCompletenessTest` 1/1, `LogicalBoundaryRealizationsTest` 6/6, `NativePlacementContinuityTest` 24/24, `SharedPrivacyPlacementAnalysisContractTest` 16/16, `PlacementRealizationAuthorityTest` 9/9, `SharedPlannerFunctionPlanPropagationRedTest` 6/6, `ExactPhysicalModelCertificateTest` 8/8, `ExactNativeLocalAnchorFanoutCostTest` 6/6, `ExactPhysicalWorkerCountTest` 2/2. D1 focused test까지 합하면 **89 tests / 0 failures / 0 errors**다.
- 이전 `NeutralPlacementGraphUploadRelocationRedTest#candidateMaterializationSearchMatchesBoundedExhaustiveOracle`의 60초 timeout은 production correctness failure로 판정하지 않는다. 해당 GLM 기반 bounded oracle fixture의 scalability 문제는 미해결이다. 현재 helper의 `variableConsumers` 중복 추가 버그는 실제 코드에 없으므로 timeout 원인으로 기록하지 않는다.

### H1 — SCC grounding OR-alternative leakage

**상태: proof-kernel 반례는 현재 회귀로 닫혔고, production HOP graph reachability E2E는 별도 미인증이다.**

- 감사 반례 `A -> {A} OR {G,U}`, `G=grounded`, `U -> {U}`에 대해 `NativePlacementContinuityTest#candidateSccGroundingCannotBorrowGroundFromIncompleteAlternative`가 incomplete AND branch의 일부 ground를 cyclic OR branch가 빌려 쓰지 못하게 잠근다.
- `candidateSccGroundingPreservesExternallyGroundedLoop`는 실제 external ground가 있는 정상 loop를 유지한다. fresh `NativePlacementContinuityTest`는 **24/24 PASS**다.
- 따라서 SCC/proof-kernel 수준 leakage는 현재 닫힌 것으로 보지만, 실제 production HOP proof graph 생성 + root pinning을 통해 동일 형태가 도달 가능한지까지 독립 E2E fixture로 인증한 것은 아니다.

### D4 — worker cardinality semantics

**상태: partition 수가 아닌 canonical distinct physical endpoint 수를 사용하도록 구현/회귀가 들어가 있으며 fresh 재검증했다.**

- `ExactPhysicalCostModel`의 realization/native-local worker count는 선택된 durable/native worker pool의 `FederationUtils.canonicalFederatedWorkerAddress(partition.workerId())` distinct count를 사용한다. 같은 `host:port`의 다른 path/partition은 worker 1개이고, 다른 port는 별도 worker다.
- partition geometry/request multiplicity는 worker cardinality와 분리해 보존한다. 비용 계수는 변경하지 않았다.
- fresh `ExactNativeLocalAnchorFanoutCostTest` **6/6 PASS**, `ExactPhysicalWorkerCountTest` **2/2 PASS**다.

### 현재 남은 correctness / qualification 경계

- **전체 valid plan-space completeness는 아직 인증하지 않았다.** 다만 독립 full-set micro-oracle 범위는 확대했다. `CandidateReceiptAssignmentCompletenessTest`는 optimizer/search/production reachability predicate를 legality oracle로 사용하지 않고 raw compiler facts에서 exact **선택된** source realization, DIRECT/RELOCATION binding, derived-FOUT action authority, target/anchor/obligation을 직접 검사한다. 초기 oracle이 raw domain 안의 임의 compatible source 존재만 확인하던 약점은 제거해, 현재 조합에서 실제 선택된 upstream receipt와 exact binding이 일치해야 legal로 인정한다.
- candidate-receipt full-set fixture는 현재 5개다. cross-pool은 raw 4개 = legal 4개, incoming-support는 raw 6개 중 legal 2개이며 production feasible assignment set과 정확히 일치한다. 같은 worker pool의 multi-input `A+B`는 현재 native-layout exactness 계약 아래 raw product 6개를 전수열거하고 derived-FOUT authority까지 독립 검사해 production set과 일치한다. `A+A` repeated-producer fixture도 exact source identity를 선택 조합별로 검사하며 production set과 일치한다. 마지막으로 consumer/row enumeration 순서를 뒤집고 relocation-action visit order도 뒤집어도 complete raw/legal/production assignment set이 동일함을 확인한다. fresh `CandidateReceiptAssignmentCompletenessTest`는 **5/5 PASS**다.
- 함수 경계 쪽은 `LogicalBoundaryRealizationsTest#completeBoundaryAssignmentSetMatchesIndependentConjunctiveOracle`를 추가했다. function argument + ordinary reaching writer + reader가 각각 pool A/B를 선택하는 **2^3 = 8개 전체 조합**을 전수열거하고, 독립 oracle의 `readerPool == argumentPool == writerPool` 두 조합과 production relation의 complete legal set이 정확히 일치한다. 해당 class는 fresh **7/7 PASS**다.
- transient 쪽에는 두 개의 추가 full-set oracle을 넣었다. branch-join의 모든 reaching TWrite와 reader, 그리고 rewrite를 생략해 실제 identity `TWrite(B <- TRead(B))` backedge를 보존한 loop에서, **LOCAL + DURABLE_MAP** receipt assignment 전체 곱을 독립 physical-layout oracle로 전수열거하고 `CandidateSelections.realizationsCanStillBeCompatible`가 남기는 전체 set과 정확히 비교한다. 두 테스트 모두 fresh PASS다. 이 과정에서 NATIVE_LINEAGE는 단순 endpoint/layout equality가 아니라 `NativePlacementContinuity`의 exact proof authority가 필요함을 다시 확인했으므로 독립 oracle이 그 production proof를 재사용하지 않도록 의도적으로 범위에서 제외했다. 따라서 native-lineage transient full-set completeness와 실제 HOP proof-graph/root-pinning E2E는 여전히 미인증이다.
- fresh sequential broad authority suite는 `PrivacyMovementCertificationTest`, incoming/bottom/projection/canonicalization, receipt full-set, function/transient/native continuity, shared privacy/publication, propagation, exact physical-model/native-local/worker-count를 포함한 **15 classes / 114 tests / 0 failures / 0 errors / 0 skipped**로 `EXIT 0`이었다. 이후 visit-order oracle을 추가한 `CandidateReceiptAssignmentCompletenessTest`를 다시 단독 실행해 **5/5 PASS**를 확인했다. `git diff --check`도 clean이다.
- 위 micro-oracle들은 “선택된 placement assignment 아래의 receipt space”, 함수 boundary coupling, 일부 transient all-reaching/loop coupling을 강하게 잠그지만, 전체 placement-state universe까지 인증하지 않는다. 남은 독립 set-equality 범위는 NATIVE_LINEAGE continuity, privacy/recompile 결합 및 더 일반적인 higher-arity AND/OR fixture다. 최소 목적값 비교로 대체하지 않는다.
- BG014 / ML10 / P1 / P2 / SliceLine runtime qualification은 아직 미완료다. 기존 handoff에서 `so007` Docker image digest가 `so002/so003/so004`와 다르므로 image parity를 lifecycle authority로 해결하기 전 full campaign을 실행하지 않는다.
- commit/push/reset/checkout은 하지 않았다.

## 2026-09-16 최신 소스 재인증 및 runtime 진입 조건

**상태: 최신 concurrent-edit 통합 상태에서 correctness authority 재인증 완료. Runtime workload campaign 자체는 아직 시작하지 않았다.**

- `NeutralPlacementGraphBuilder`의 exact right-index replay는 transient reader의 대표 anchor 하나를 exact runtime input map으로 가정하지 않는다. 실행 가능한 모든 FOUT support clause와 선택 receipt가 동일한 exact geometry를 증명할 때만 exact input anchor를 사용하고, 그렇지 않으면 shape-independent oracle 결과를 유지한다. 이 변경은 valid source realization/support clause를 제거하지 않는다.
- 이후 들어온 `NativePlacementContinuity`의 dynamic native-layout/range 추론과 통합한 최신 소스에서 `PlacementRealizationAuthorityTest`와 full `ExactPhysicalModelCertificateTest`를 재검증했고, 이어 최소 broad authority 집합을 fresh sequential Maven으로 다시 실행했다.
- 최신 broad suite는 다음 **17 classes / 120 tests / 0 failures / 0 errors / 0 skipped**, Maven `EXIT 0`이다: `PrivacyMovementCertificationTest`, `CandidateIncomingSupportCompletenessTest`, `CandidateInputBottomDomainTest`, `ExecutableProjectionAuthorityTest`, `CandidateRealizationCanonicalizationTest`, `CandidateReceiptAssignmentCompletenessTest`, `LogicalBoundaryRealizationsTest`, `TransientPlacementAlternativesTest`, `NativePlacementContinuityTest`, `HeuristicNativeContinuationContractTest`, `SharedPrivacyPlacementAnalysisContractTest`, `PublicationSupportClosureTest`, `SharedPlannerFunctionPlanPropagationRedTest`, `ExactPhysicalModelCertificateTest`, `ExactNativeLocalAnchorFanoutCostTest`, `ExactPhysicalWorkerCountTest`, `PlacementRealizationAuthorityTest`.
- 이 실행의 surefire XML mtime은 2026-09-16 01:55:25~01:56:44 +0200 범위이며, 핵심 source mtime은 실행 전후 변하지 않았다. `git diff --check`도 clean이었다. Stale surefire report가 아니라 이 fresh invocation의 XML을 직접 집계했다.
- 전체 valid physical plan-space의 전역 독립 completeness 증명은 여전히 별도 범위다. 위 결과는 현재 구현의 authority/receipt/function/transient/native/privacy/worker-count 계약과 exact certificate 회귀를 잠그는 근거이며, 동일 optimum만으로 completeness를 주장하지 않는다.
- 과거 handoff의 `so007` Docker tag digest 불일치는 현재 lifecycle authority의 **content identity** 기준에서는 active blocker가 아니다. `/home/mchoi/cofee-evaluation/driver/run_multihost_campaign_network_quality_v2.py`의 `certify_execution_identity(...)`를 ML10 frozen stage와 기존 content-addressed image `cofee-experiment:content-0861f4ff197c868f42abf6478b66505f650325c267caa8be2e82b4b6317c7ff0`에 직접 적용했을 때 so007 + so002..so006 전체가 동일 image content `0861f4ff197c868f42abf6478b66505f650325c267caa8be2e82b4b6317c7ff0`로 인증됐다. 과거 tag-level digest 차이를 retagging으로 우회하지 않는다.
- 위 parity PASS는 **옛 ML10 stage**의 infrastructure identity만 증명한다. 현재 corrected planner를 runtime qualification하려면 최신 correctness green source로 새 JAR를 만들고, frozen stage 구조/data/reference/wrapper를 보존한 새 immutable stage에 그 JAR와 갱신된 `STAGE_CONTENT.sha256`/build identity를 넣은 뒤, 같은 absolute stage content를 모든 execution host에 배포하고 lifecycle certification을 다시 통과해야 한다.
- commit/push/reset/checkout은 하지 않았다.
