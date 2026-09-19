# Static Analysis Audit — 2026-09-14

대상: `/home/mchoi/systemds-lm-worker-count-fix` (so002 / dams-so002)

## Executive Summary

이번 산출물은 **정적 코드 감사와 후속 repair plan**이다. 이 감사에서는 Java/DML 코드, 테스트, 정책, 비용 계수, runtime 또는 실험 산출물을 수정하지 않았고, build/test/benchmark/workload를 실행하지 않았다. 요청된 이 Markdown 문서만 새로 생성한다.

**판정:** 코드 계약 수준의 확정 결함 4건(D1–D4), 높은 위험의 잠재 결함 3건(H1–H3), 추가 증거가 필요한 우려 3건(L1–L3)을 구분했다. 여기서 “확정”은 인용한 코드와 명시한 입력 조건에서 서로 충돌하는 계약 또는 잘못된 결과를 정적으로 도출할 수 있다는 뜻이다. 현재 실행 중이던 실험에서 해당 장애가 발생했다거나, production DML부터 끝까지 재현했다는 뜻이 아니다. 모든 신규 regression/dynamic validation은 **NOT_RUN**이다.

| ID | 판정 / 우선순위 | 핵심 문제 | 입증한 범위 |
|---|---|---|---|
| D1 | 확정 / P0 | relocation score cache가 서로 다른 realization/clause의 효과를 같은 키로 취급 | 캐시 동치 계약 불성립; 실제 scorer는 pool/binding에 의존 |
| D2 | 확정 / P1 | function-boundary compatibility를 추가했지만 검색 component/dependency closure에서 누락 | 연결 factor를 독립 component로 풀 수 있는 구조적 불일치 |
| D3 | 확정 / P1 | identity loop backedge를 replay에서 제거하지만 final all-reaching validator는 계속 요구 | 해당 compiled CFG에서 producer/validator 계약 충돌 |
| D4 | 확정 / P1 | realization별 worker-count helper가 endpoint 수 대신 partition 수 반환 | 같은 endpoint에 여러 partition이 있는 입력에서 기존 physical-worker 의미와 불일치 |
| H1 | 높은 위험 / P0 증명·회귀 우선 | SCC grounding에서 다른 OR 대안의 부분적인 ground를 빌릴 수 있음 | proof-kernel 반례; 실제 HOP graph 도달성은 추가 검증 필요 |
| H2 | 높은 위험 / P1 | known-empty predecessor domain을 ABSENT_LOCAL로 변환 | bottom/unknown/local의 구별 상실; 최종 오실행은 미입증 |
| H3 | 높은 위험 / P1 | unavailable all-local row도 FED/LOUT 유지 근거가 됨 | projection authority가 candidate status와 분리 |
| L1–L3 | 낮은 확신 / P2, 일부 P1 검증 | pool/partition 구별, null 계약, 초기 emitted-work/재생성 상태 손실 | 안전 장치가 존재하며 production 반례 부족 |

우선 D1의 캐시 키를 lossless하게 만들고, H1의 AND/OR proof 반례를 작은 순수 fixture로 검사해야 한다. D2는 Exact solver의 새 factor를 제거하는 방식이 아니라 **같은 관계를 모든 검색의 dependency/component 모델에 연결**해야 한다. D3는 all-reaching 검사를 완화해서 고치면 안 된다. D4는 계수 보정 문제가 아니라 입력 cardinality의 의미 문제다.

### 작업 상태와 동시 수정 주의

읽은 HEAD는 `ffb7be5bd85367156ed9ea86dacbaff4be0f035d`이며 commit 시각/제목은 `2026-09-14 08:37:44 +0200 Fix physical worker counting across federated data paths`이다. 기존 dirty 변경과 untracked helper/tests가 이미 있었다. `.omx/`, `build/`는 status에 보였지만 내부를 탐색하거나 변경하지 않았다.

감사 중 다른 작업자가 소스 및 session 문서를 변경했다. 따라서 단일 원자적 worktree snapshot을 확보했다고 주장하지 않는다. 최초 주요 파일은 서버 시각 `2026-09-14T23:10:33+02:00` 전후에 메모리로 읽었고, 변경 파일을 `23:23:12+02:00`, 다시 builder/boundary 파일을 `23:29:27+02:00` 전후에 재확인했다. 본문의 기본 줄 번호는 주 분석 snapshot(S1–S3, 마지막 builder 재읽기 23:29:27+02:00) 기준이며, 아래 SHA-256/read-time manifest로 식별한다. 문서 조립 중 추가된 S4 변경은 별도 delta 절에 기록한다. 기본 manifest의 파일과 S4 live 파일을 혼동하지 않는다. 구현 에이전트는 현재 파일의 메서드와 해당 해시를 먼저 대조해야 한다.

`SESSION_ISSUES_2026-09-14.md:72`의 helper “통합 예정” 설명은 마지막 코드보다 오래되었다. 최신 builder에는 `CandidateDomainRefinement` 호출이 있고, 감사 중 추가된 function-boundary selection gate 및 boundary fixed-point도 확인했다. 이들을 현재의 미구현 결함으로 보고하지 않는다. 세션 문서에 기록된 이전 테스트 pass/fail은 **다른 작업자의 기록**이며, 이 감사가 실행하거나 독립적으로 재검증한 결과가 아니다.

## Evidence notation and scope

아래 경로 약어는 모두 이 worktree 상대 경로이다.

- `P/` = `src/main/java/org/apache/sysds/hops/fedplanner/placement/`
- `E/` = `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/`
- `C/` = `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/`
- `TP/` = `src/test/java/org/apache/sysds/hops/fedplanner/placement/`
- `TE/` = `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/`

읽기 범위는 dirty diff 및 아래 manifest의 관련 소스/테스트다. 파일을 메모리에 읽은 것과 모든 줄/모든 호출자를 완전 검증했다는 것은 다르다. 본문에서 명시한 코드 경로를 집중 추적했고, test coverage 판단은 **읽은 테스트에서 해당 assertion/fixture를 찾았는지**에 한정한다. 저장소 전체의 테스트 부재를 주장하지 않는다.

## Current invariants understood from the code

**I1. Logical value와 physical choice는 별개의 identity다.** `ValueVersionKey`만으로 현재 map을 결정하면 안 된다. 후보 선택에는 rule, emission, exact realization, 선택된 support clause가 필요하다. `P/PlacementAnalysis.java:300–415`, `P/PlacementIdentity.java:249–413` 및 canonical receipt 검사가 이 구분을 나타낸다. 같은 shallow realization의 clauses는 OR이고 한 clause의 input bindings는 AND다. 서로 다른 clause가 같은 realization key 아래에서 다른 native pool을 주장하는 것은 생성자에서 거절된다.

**I2. TR/TW는 logical forwarding이지 임의의 materialization 연산이 아니다.** AGENTS 및 compiled transient predicates는 CP/LOUT 또는 FED/FOUT의 합법성을 별도로 유지한다. logical transient relation을 보강하면서 가짜 physical Hop input이나 CP/FOUT TR/TW를 만들면 안 된다.

**I3. 모든 reaching definition이 같은 reader realization을 지지해야 한다.** 각 writer→reader relation 내부는 대안의 집합이지만 reaching writers 간에는 AND다. `P/PlacementAnalysis.java:1901–1960`은 writer 집합의 완전성, 공통 reader realization, 부분 지지만 남은 join을 검사한다. function/formal와 ordinary writer가 섞인 경우에는 새 `LogicalBoundaryRealizations`의 모든 source 관계가 담당한다.

**I4. FType 또는 worker-count 일치는 map authority가 아니다.** ROW/COL partition axis, canonical endpoint, native lineage/anchor 및 exact selected source가 필요하다. `P/NativePlacementContinuity.java:758–805`, `P/PlacementIdentity.java:416–451`, `P/NeutralPlacementGraph.java:286–340`을 함께 읽어야 한다. 특히 ROW partition 경계가 다르면 endpoint가 같아도 자동 정렬된 것으로 볼 수 없다.

**I5. refinement는 실제 rebuild domain으로 증명한다.** `CandidateDomainRefinement.removedByRefinedInputDomain`은 후보를 생성/삭제하지 않고 이미 제거된 tuple이 명시적으로 refined된 위치에서 더 이상 허용되지 않는지만 증명한다. 최신 builder의 `hasRefinedPredecessorInvalidation`은 이 helper를 호출한다(`P/NeutralPlacementGraphBuilder.java:3708` 부근). 추후 projected node에서 domain을 재추정하는 것으로 되돌리면 안 된다.

**I6. publication은 nodes, candidate keys/facts, realizations/clauses, logical relations, actions 및 privacy의 닫힌 상태여야 한다.** builder는 semantic loop와 publication loop를 두고 CFG replay, direct/boundary grounding, physical rebuild, privacy 재적용, canonical action/state 재결합을 반복한다(`:639–856`). raw physical midpoint에서 안정성을 선언하면 안 된다. boundary 연쇄를 닫는 `LogicalBoundaryRealizations.close`도 최신 코드에서 확인했다(`:182–193`).

**I7. Privacy 거절은 이후 replay가 발급할 수 있는 허가가 아니다.** `buildExactLogicalTransientRead`는 privacy-excluded state를 건너뛰며, `closePrivacy`는 protected payload와 candidate emission을 다시 검사한다(`builder:3299–3307`, `:1053–1123`). public aggregate output도 protected input을 먼저 coordinator로 가져올 권한이 아니다. `C/ExecPlacementPolicy.java:95–99,125–145`가 원래 policy authority다.

**I8. Legal/excluded와 emitted-work polarity는 엄격하다.** `P/NeutralPlacementGraph.java:115–132`는 legal/excluded 겹침, state별 중복 exclusion 및 emittedWork/empty-domain 불일치를 거절한다. 최종 오류가 있다는 사실 자체가 이 guard를 삭제할 근거는 아니다.

**I9. 선택 후 projection은 최적화되지 않은 map 선택을 새로 해서는 안 된다.** `E/ExactPhysicalSelection`, `E/ExactPhysicalPlacementProjector.java:42–58,75–89`, normalized result와 emission transaction은 exact graph-owned receipt/action/state identity를 다시 검사한다. stale reference를 대충 동등한 객체로 바꾸거나 singleton fallback으로 임의 보완하면 안 된다.

**I10. Exact search의 cache와 component 분할도 legality 모델의 일부다.** 같은 cache key는 같은 scoring/feasibility 효과를 뜻해야 하고, 분리된 component 사이에는 아직 선택에 의존하는 factor가 없어야 한다. D1/D2는 이 불변식 위반이다.

## Confirmed defects

### D1 — Realization-dependent relocation score의 cache key 충돌

**위치 / 메서드.** `P/RelocationSelections.java:420–451` (`CandidateProblemIndex`, `ExactReceiptScoringEffect` 생성), `:454–487` (`receiptAffectsAction`, `requiresEmission`, `optionAllowed`); `P/CandidateSelections.java:2004–2021` (`Search` cache-effect ranks), `:2128–2142` (`ComponentSearch.solve` cache 조회); `P/NeutralPlacementGraph.java:300–337` (`isRelocationActive`).

**흐름과 위반.** 현재 effect key는 demand의 정적 option 목록, “영향을 줄 수 있는 action IDs” 및 infeasible flag로 구성된다. 같은 consumer 또는 같은 source value의 두 receipt는 실제 pool/clause가 달라도 같은 affected-action 목록을 갖는다. 그러나 scorer는 선택된 source pool이 target pool과 같은지, DIRECT/RELOCATION binding이 무엇인지에 따라 action을 활성화/억제한다. “같은 action을 건드림”은 “같은 효과를 냄”과 다르다. Search는 이 약한 키로 score와 `Integer.MAX_VALUE`까지 재사용한다. 따라서 I10의 캐시 동치 계약이 성립하지 않는다.

**발현 입력.** 동일 coarse FOUT state를 가진 source의 `S@P`, `S@Q`와 target pool P인 action a를 둔다. P의 직접 재사용과 Q의 이동 경로는 0/1 emission으로 달라질 수 있지만 source receipt의 정적 action 영향 목록은 같다. DIRECT/RELOCATION consumer clause도 정적 demand option이 같으면 effect compression에서 구별되지 않는다. `TP/PrivacyMovementCertificationTest.java:227–328`은 direct source/consumer 및 shifted source/relocated consumer를 만드는 구체적인 출발점이며 0/1을 기대한다. 해당 fixture를 graph-owned canonical alternatives로 구성한 뒤 Search cache 경로까지 연결해야 한다. 현재 실험에서 이 캐시 충돌이 실제 발생했다고 주장하지 않는다.

**현재 테스트.** 같은 파일 `:202–223`, `:340–354`는 canonical/indexed selection과 새 scorer의 parity를 비교한다. 하지만 매 선택마다 scorer를 새로 만들며 `CandidateSelections.Search.relocationScoreCache`에서 이전 leaf의 score를 재사용하는 경로는 검사하지 않는다. 캐시가 잘못 같다고 분류한 두 effect를 한 검색에서 방문하는 assertion이 필요하다.

**최소 수정.** 우선 exact selected receipt identity 또는 `(rule, realization key, selected clause)`를 lossless하게 cache key에 포함한다. 더 안전한 첫 패치는 이 경우 effect compression/cache를 끄되 실제 score 계산은 그대로 두는 것이다. 이후 압축을 복원하려면 activity, binding restriction, privacy feasibility의 동일성을 모두 증명한 signature를 사용한다. 비용 계수나 preference policy를 바꾸지 않는다.

**영향 범위 / 보존할 authority.** CandidateSelections search의 캐시 hit rate/메모리는 변할 수 있다. Relocation activity, privacy, exact input binding, physical-emission dedup 및 최종 canonical receipt 권한은 유지한다. action physical ID와 candidate proof identity를 하나로 합치지 않는다.

**회귀 / 통과 조건.** canonical 두-pool fixture에 대해 cache on/off와 uncached scorer/완전 열거 결과가 같아야 한다. pool/clause/후보 방문 순서를 뒤집고 DIRECT/RELOCATION, 0/1, feasible/infeasible, push/pop을 검사한다. 동일 effect라고 판정한 모든 row-pair에 대해 전체 선택 문맥별 실제 score 동치도 확인한다.

### D2 — Function-boundary factor가 검색 dependency/component closure에서 빠짐

**위치 / 메서드.** `P/CandidateSelections.java:248–282` (`PartialReachabilityIndex` dependencies), `:1165–1171` (`hasRealizationDependencies`), `:1345–1365` (shared compatibility gate), `:2047–2064,2097–2101,2174–2208` (`Search.solve`, `exactInteractionComponents`); `P/LogicalBoundaryRealizations.java:154–161,196–224,261–273`; `E/ExactPhysicalModel.java:657–675` (`addLogicalBoundaryFactors`).

**흐름과 위반.** shared selection과 Exact physical factor에는 이제 boundary compatibility가 있다. 반면 component union은 ordinary transient facts, `requiredInputSupport`, relocation/local/FOUT effects만 사용한다. `hasRealizationDependencies`도 boundary relation을 명시적으로 포함하지 않는다. boundary realization은 의도적으로 빈 physical inputBindings와 typed pool witness를 갖기 때문에 `requiredInputSupport`만으로 이 연결을 재구성할 수 없다. factor를 추가했으나 factor scope를 모든 consumer 분석에 전파하지 않은 것이다.

**발현 입력.** actual/formal 또는 function result/read가 같은 FType의 두 native pools P/Q를 가질 때를 본다. ordinary TR relation/relocation/shared-local action 없이 boundary relation만 source와 target을 연결하는 fixture가 필요하다. source component를 P로 먼저 고정하고 나중 target/descendant component를 독립 최적화하면 전역적으로 더 나은 Q 조합을 잃을 수 있다. 각 미선택 domain에는 부분적으로 호환 가능한 대안이 남아 있지만 서로 양립하는 전역 조합이 없는 경우에는 나중 component에서 불필요한 infeasible 오류도 가능하다. 이는 최종 validator가 잘못된 실행을 허가한다는 주장과는 다르다.

**현재 테스트.** `TP/FunctionBoundaryRuntimeAliasContractTest.java:20–44`는 coarse input/output alias 제약을 검사한다. `TP/SharedPlannerFunctionPlanPropagationRedTest`는 compiler-generated formal 및 function propagation을 다룬다. 읽은 테스트에서 boundary-only coupling을 가진 여러 pool을 component search와 Exact factor model 양쪽에서 전부 열거해 비교하는 검사는 확인하지 못했다.

**최소 수정.** `analysis.logicalBoundaryRealizations().relations()`를 authoritative factor scope로 사용하여 source/target을 component union에 추가한다. 같은 dependency를 partial reachability invalidation/index 및 separable-row preference guard에도 반영한다. 기존 graph constraint나 compiled physical input을 조작해 우연히 component가 연결되도록 만들지 않는다.

**영향 범위 / 보존할 authority.** FedAll/AggLocal 등 shared candidate selection의 component 크기가 증가할 수 있다. Exact legality와 shared legality를 일치시키되 planner preference 자체는 유지한다. 다른 planner가 반드시 같은 plan을 골라야 하는 것이 아니라, 같은 assignment/receipt를 같은 법적 의미로 판정해야 한다.

**회귀 / 통과 조건.** boundary-only two-pool actual/formal, nested function return chain, formal+ordinary mixed join, 같은 FType/다른 pool, 선택 순서 permutation을 추가한다. component 방식과 전역 완전 열거의 feasible set 및 각 planner의 기존 objective 결과가 일치해야 한다. 관계만 바뀌고 coarse domain은 같은 경우에도 dependency invalidation이 발생해야 한다.

### D3 — Identity loop backedge의 replay 축약과 all-reaching publication 계약 충돌

**위치 / 메서드.** `P/NeutralPlacementGraphBuilder.java:2215–2238` (`closeCfgValueVersions`), `:2828–2872` (`replayUniqueCfgTransientForward`), `:3140–3163` (`exactLoopPassThroughSource`), `:3210–3221` (`exactIdentityLoopBackedge`), `:3291–3343` (`buildExactLogicalTransientRead`); `P/PlacementAnalysis.java:1901–1960`, `indexCfgDefinitionSources`.

**흐름과 위반.** CFG value version에는 모든 reaching definition이 기록된다. identity backedge가 발견되면 replay의 `exactDefinitions`는 유일한 외부 seed 하나로 줄어든다. relation 생성은 `replay.sources()`만 순회하므로 backedge relation은 발급되지 않는다. 반면 final validator는 value version으로부터 얻은 전체 writer 집합과 공급된 relation 집합이 정확히 같기를 요구한다. 이 identity 축약은 단순한 첫 bootstrap이 아니라 후속 replay에서도 반복된다.

**발현 입력.** 외부 `x` 정의와 `TW(x, TR(x))` identity backedge를 둘 다 가진 compiled loop CFG. read/write가 같은 block/path, 이름/shape이고 write의 단일 입력이 해당 read Hop인 경우다. `x=seed; while (...) { x=x; ... }`의 대응 CFG를 **최적화로 identity copy가 제거되지 않도록** 순수 fixture로 직접 만든다. 일반 DML 컴파일러가 이 copy를 지우는 경우에는 경로가 나타나지 않을 수 있다. 모든 loop가 실패한다고 확대 해석하면 안 된다.

**현재 테스트.** `TP/PlacementRealizationAuthorityTest` 및 `TP/PrivacyMovementCertificationTest:420`의 missing-writer negative 검사는 validator가 엄격해야 함을 뒷받침한다. `CompiledTransientOperationKindTest`는 LOOP_PHI 자체의 분류를 다룬다. 읽은 builder 테스트에는 위 identity backedge가 보존된 CFG에서 full relation을 publish하는 positive fixture가 없다.

**최소 수정.** seed 축약은 초기 inductive bootstrap에만 사용한다. publication 전에 identity backedge까지 포함한 relation/proof를 복구한다. identity alias를 별도 canonical 논리로 정규화하려면 CFG value-version 집합과 relation producer/validator가 동일한 authoritative 정규화를 사용해야 한다. 가장 작은 수정은 complete backedge relation을 보존하는 쪽이다.

**영향 범위 / 보존할 authority.** loop native continuity와 all-definition privacy propagation에 영향이 있다. validator에서 identity writer를 무조건 제외하거나 ordinary writer를 임의로 지우지 않는다. cross-function/recompile occurrence identity, loop의 초기 값과 backedge AND 의미를 유지한다.

**회귀 / 통과 조건.** external seed + identity backedge의 local/FOUT 두 경우가 빌드되어야 한다. seed 없는 cycle은 거절되어야 한다. identity가 아닌 transpose 또는 unrelated pool backedge는 거절되어야 한다. nested loop와 서로 다른 occurrence를 가진 같은 variable-name을 추가한다. publication relation writer-set가 authoritative CFG set와 정확히 같음을 assert한다.

### D4 — Realization별 worker helper가 partition cardinality를 physical-worker count로 사용

**위치 / 메서드.** `E/ExactPhysicalCostModel.java:1270–1308` (`realizationWorkerCount`), `:1311–1327` (`nativeLocalInputWorkerCount`), `:1703–1709` (`workerCount`); `C/FederatedWorkerUtils.java:35–46`; `C/FederatedCostModel.java:2051–2090`; `src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationUtils.java:386–410`.

**흐름과 위반.** graph-level workerCount와 shared worker utility는 canonical endpoint/address의 distinct 수를 센다. 반면 새 realization/native-local helper의 anchor, native witness 및 relocation 경로는 모두 `partitions().size()`를 반환한다. 반환값은 reusable download의 per-worker critical path와 fan-in 등에 전달된다. 같은 의미의 worker-count API가 같은 물리 배치에 서로 다른 수를 공급한다.

**발현 입력.** 하나의 ROW anchor에 `w1:8001/a`, `w1:8001/b`, `w2:8002/c`의 세 disjoint partition이 있는 경우다. partition 수는 3, physical endpoint 수는 2다. 같은 endpoint에 여러 partition을 허용하는 source/realization 입력에서는 helper가 3을 반환한다. 현재 실험 데이터가 실제로 이 형태인지 읽거나 실행해 확인하지 않았다. 요청/partition fan-in을 모델링하려는 별도 의도가 있다면 worker 수와 별도 변수 및 계약으로 명시해야 하며 두 의미를 조용히 혼용하면 안 된다.

**현재 테스트.** `TE/ExactPhysicalWorkerCountTest.java:25–39`는 graph-level endpoint dedup만 검사한다. `TE/ExactNativeLocalAnchorFanoutCostTest.java:45–54,77–101`의 helper fixture는 `:115–124`에서 partition마다 다른 worker를 만들어 partition count와 worker count를 구별하지 못한다.

**최소 수정.** 선택된 pool에 대한 canonical distinct endpoint count를 공통 helper로 추출해 worker-count 인자에 사용한다. malformed endpoint는 명시적으로 거절/unknown 처리하고 null을 worker 한 개로 세지 않는다. partition/request multiplicity가 별도로 필요하면 그대로 보존한다. 비용 계수, calibration, planner policy를 조정해서 증상을 상쇄하지 않는다.

**영향 범위 / 보존할 authority.** Exact materialization/download/native-local upload cost의 입력 수가 달라질 수 있다. ROW/COL partition geometry, FULL의 single-partition legality, BROADCAST payload multiplicity는 독립 invariant이므로 anchor partition 목록 자체를 dedup하지 않는다. pool identity를 worker-count 숫자로 대체하지 않는다.

**회귀 / 통과 조건.** 단일 anchor 안의 중복 endpoint/서로 다른 path, 다른 port, native witness 및 emitted relocation target에 대해 검증한다. 관련 없는 graph worker가 결과를 바꾸지 않아야 한다. 3-partition/2-worker와 3-partition/3-worker를 구별하고, 비용 primitive에 실제 전달되는 count 및 작은/큰 response 경계 양쪽을 검사한다. runtime의 per-endpoint grouping 계약 확인은 실험 종료 후 별도 검증한다.

## High-risk latent defects

### H1 — SCC grounding의 OR 대안 사이에서 AND 증거를 빌리는 경로

**위치 / 메서드.** `P/NativePlacementContinuity.java:245–298` (`groundedCandidateStates`), 특히 `:282–290`; `proveCandidate`/proof materialization의 grounded-set 사용(`:156–228`).

**흐름 / invariant.** SCC의 `everyStateSupported`는 내부 또는 이미 grounded인 dependency만 가진 대안이 하나 존재하는지를 검사한다. 별도의 `hasGroundPath`는 **모든 대안 중 어느 하나의 dependency 하나만** 외부 grounded여도 참이 된다. 외부 ground를 제공하는 대안의 다른 AND dependency가 grounded인지 두 조건을 연결해서 확인하지 않는다. I1의 clause-AND 의미가 여기서는 다른 대안의 근거와 섞일 여지가 있다.

**정적 반례와 범위.** proof-kernel 입력을 `A -> {A} OR {G,U}`, `G -> directGround`, `U -> {U}`로 둔다. A는 첫 대안으로 everyStateSupported를 만족하고, 두 번째 대안의 G로 hasGroundPath를 만족한다. U는 여전히 ungrounded인데 A가 grounded될 수 있다. 올바른 근거 없는 cycle이 다른 불완전한 대안에서 ground를 빌린 셈이다. 이는 알고리즘 입력 수준 반례이며, 실제 `buildCandidateProofGraph`의 root pinning, 연산별 dependency 생성 및 선택된 clause가 이 형태를 만들 수 있는지까지는 재현하지 않았다. 따라서 production authority 우회를 확정하지 않고 높은 위험으로 분류한다.

**현재 테스트.** `TP/NativePlacementContinuityTest.java:305–318`은 grounded sibling G와 독립 ungrounded cycle을 하나의 AND로 묶은 경우를 검사한다. 그러나 위와 같이 **다른 cyclic OR 대안**이 everyStateSupported를 만족시켜 주는 조합은 없다. `:282–301`의 좋은/나쁜 sibling candidate 검사도 별개다.

**최소 수정 방향.** 우선 위 작은 proof graph가 public proof 경로에서 도달 가능한지 hermetic fixture로 확인한다. 수정이 필요하면 SCC support 판정과 ground-path 판정에 동일한 fully-supported clause/hyperedge 집합을 사용한다. 단순히 `hasGroundPath` 한 줄만 고치고, 사용할 수 없는 대안의 edge를 합친 SCC 자체를 그대로 신뢰하는 것으로 끝내면 부족할 수 있다. OR-of-AND의 도달 가능한 inductive support를 명시해야 한다.

**영향 / 보존.** native loop seed, candidate-specific sibling isolation, ROW/BROADCAST continuity에 넓게 영향을 준다. 모든 cycle을 무조건 거절하는 수정은 정상 grounded loop를 깨뜨린다. 새 runtime fallback이나 privacy 예외로 회피하지 않는다.

**회귀 / 우선순위.** P0는 먼저 증명/회귀를 닫으라는 의미다. 위 음성 반례, 합법적인 external-ground loop, 두 SCC 사이의 미충족 AND, root-pinned good/bad sibling, 다중 OR clauses 및 순서 변경을 검사한다. 작은 proof graph 완전 열거와 reference hypergraph evaluator 비교는 실험 종료 후 수행한다.

### H2 — Known-empty matrix predecessor를 ABSENT_LOCAL로 승격

**위치 / 메서드.** `P/NeutralPlacementGraphBuilder.java`, 일반 `inputDomains`의 `:7515–7534` 부근(핵심 `:7526`), function input domain의 `domain.isEmpty()` 처리; `closePostCfgPhysicalCandidateDependencies`, `CandidateDomainRefinement` 호출 경로. `P/NeutralPlacementGraph.java:122–123`의 empty-domain polarity와 함께 읽어야 한다.

**흐름 / invariant.** predecessor가 존재하지만 legalAlternatives가 비어 있으면 types는 empty이고 local도 false다. 그래도 `types.isEmpty()` 분기는 `[null]`을 반환한다. 이 null 원소는 candidate language에서 “모름”이 아니라 `ABSENT_LOCAL`이다. 따라서 known-bottom, 실제 local predecessor, 정보가 없는 외부/스칼라 입력을 구별하지 못한다. helper는 전달받은 domain 기준으로 올바르게 증명할 수 있어도, rebuild domain 자체가 잘못 local로 바뀌면 원인을 해결하지 못한다.

**발현 입력 / 확신 한계.** publication/grounding에서 모든 matrix realization이 사라진 predecessor를 가진 aggregate/REPLACE/후속 TW를 재생성할 때다. spurious all-local row, 원래 FED 대안의 손실, 나중의 empty physical model 또는 privacy failure가 가능하다. non-emitted synthetic carrier와 실제 사라진 emitted producer를 구분하는 전체 호출 전제는 더 확인해야 한다. 이 경로가 성공한 불법 local 실행으로 이어진다는 증거는 없다.

**현재 테스트.** `TP/CandidateDomainRefinementTest`의 4개 fixture는 이미 주어진 exact domain을 해석한다. builder가 known-empty predecessor를 local로 바꾸는지 검사하지 않는다. 세션 문서의 X0 domain 손실 사례는 관련 증상이나 이 분기가 유일한 원인이라는 근거는 아니다.

**최소 수정 방향.** known emitted matrix bottom은 empty domain/reason을 전파하거나 producer 경계에서 fail closed한다. null/ABSENT_LOCAL은 실제 LOUT 또는 명시적으로 허용된 local/scalar 입력 근거가 있을 때만 생성한다. 모든 missing Hop 입력을 일괄 거절하지 말고 compiler literal/formal/synthetic 경계를 구분한다.

**영향 / 보존.** candidate tuple cardinality, refinement invalidation, function expansion, orphan/non-emitted classification이 영향을 받는다. CandidateDomainRefinement의 policy-free 성격, 실제 CP/LOUT 및 protected-data 제한은 유지한다.

**회귀.** empty known matrix→consumer, 정상 CP/LOUT→consumer, literal/메타데이터 입력, function-return chain의 bottom, privacy-induced narrowing, empty inner domain의 정확한 의미를 검사한다. 입력 bottom에서 PRESENT/ABSENT_LOCAL 모두 새로 생기지 않아야 한다. 우선순위 P1.

### H3 — Unavailable inputless row가 FED/LOUT projection 유지 근거가 됨

**위치 / 메서드.** `P/NeutralPlacementGraphBuilder.java:5499–5546` (`projectCandidateNodesToExecutableStates`), 특히 `hasInputlessCandidate.merge`가 `AVAILABLE` status 검사보다 앞선 `:5506–5510`, FED/LOUT 허용 분기 `:5525–5528`; `E/ExactPhysicalModel.java:302–317`의 legal-singleton/empty-domain 처리.

**흐름 / invariant.** 하나라도 모든 input이 ABSENT_LOCAL인 row가 있으면 `hasInputlessCandidate`가 true다. 이 계산에는 PROFILE_ERROR/PRIVACY_EXCLUDED row도 포함된다. 이후 실제 executable realization이 없는 FED/LOUT state를 이 flag로 유지할 수 있다. “row가 기록되어 있음”과 “그 row가 실행을 증명함”이 분리되는 비대칭이다.

**발현 입력 / 확신 한계.** PRESENT-input native FED/LOUT row가 grounding에서 제거되었는데, 같은 parent에 unavailable all-local row가 남아 있는 경우. projection은 legal state를 유지하고 실제 physical model은 이를 뒷받침할 receipt를 못 만들 수 있다. 실제 scalar/native base authority가 따로 있는 일부 inputless 상태는 의도된 것일 수 있으므로 모든 inputless FED를 결함이라고 판정하지 않는다. 최종 privacy guard를 통과하는 유출은 입증하지 않았다.

**현재 테스트.** shared privacy 및 physical certificate 검사가 증상인 late failure를 잡을 수는 있다. 읽은 테스트에서 unavailable all-local row와 사라진 PRESENT FED/LOUT row를 조합하여 projection 단계 자체를 검사하는 assertion은 확인하지 못했다.

**최소 수정 방향.** candidate-free native-local base authority를 명시적으로 표현한다. 최소한 unavailable row의 존재만으로 authority를 인정하지 않도록 하되, 단순히 flag 계산을 AVAILABLE 아래로 옮기는 것이 scalar/legal-singleton 경로에 충분한지도 검사한다. CP/LOUT 기본 실행과 실제 FED/LOUT aggregation을 일괄 삭제하지 않는다.

**영향 / 보존.** executable projection, missing-anchor diagnostics, scalar native cost/selection, privacy rejection과 exact physical coverage가 영향을 받는다. “실행 가능 후보 없음”을 emittedWork=false로 조용히 바꿔 사라지게 하면 안 된다.

**회귀.** unavailable all-local + removed FED/LOUT 조합, unavailable PRIVATE_AGGREGATE row, 정상 inputless scalar, 합법적인 PRESENT-input FED aggregate, 전 상태 소실 후 producer-level 오류를 검사한다. 우선순위 P1.

## Lower-confidence concerns

### L1 — Worker-pool witness와 partition multiplicity를 같은 equality로 다룸

**위치 / 흐름.** `P/NativePlacementContinuity.java:758–805`의 `NativePoolWitness.from`, endpoint 목록 및 연산별 mixed ROW/BROADCAST witness 비교; `P/PlacementIdentity.java:416–451`의 `samePhysicalWorkerPool`/canonical partition 비교. endpoint가 partition마다 추가되므로 worker list는 set이 아니라 multiset이다.

**위험 조건 / invariant.** 같은 두 workers에 ROW가 세 ranges를 갖고 BROADCAST가 worker당 하나의 copy를 갖는 표현을 native runtime이 지원한다면, pool은 같지만 endpoint multiplicity가 달라 안전한 연산을 불필요하게 거절할 수 있다. 그러나 runtime의 해당 mixed-layout 연산이 동일 worker의 여러 ranges를 실제 지원하는지는 이 감사에서 확인하지 않았다. D4의 count 계약과 달리, 여기에는 partition 정렬 검사가 의도적으로 포함되어 있을 수 있다.

**현재 테스트.** `TP/NativeMixedWorkerPoolContinuityTest`, `TP/RelocationSelectionsPhysicalAnchorTest`는 mixed pool 및 shifted ROW/COL partition negative를 다룬다. 같은 endpoint에 여러 ranges인 positive runtime contract까지 검사하는지는 확인하지 못했다.

**수정 방향 / 영향.** 먼저 operation별 runtime map 계약을 증명한다. 필요할 때만 distinct endpoint set과 endpoint→partition-interval geometry를 분리한다. 전체 anchor 목록을 dedup하거나 같은 endpoint라는 이유만으로 ROW/COL alignment를 허용하면 안 된다. native continuity, retype/transpose, single-partition FULL이 영향 범위다.

**회귀.** 3 ranges/2 endpoints ROW와 2-copy BROADCAST, 같은 host/다른 port, endpoint 순서 변경, shifted-axis negative를 함께 검사한다. 우선순위 P2; runtime 지원이 확인되면 P1 completeness 수정으로 승격한다.

### L2 — CandidateDomainRefinement의 malformed-inner-input 계약이 불완전

**위치 / 흐름.** `P/CandidateDomainRefinement.java:31–47`. outer null/길이 불일치는 false로 거절한다. 하지만 inner domain null은 `:40`에서, null priorInput은 `prior::equals`에서, null refined index는 unboxing에서 예외가 날 수 있다.

**위험 조건 / invariant.** caller가 불완전한 nested list/set를 전달하는 경우다. 현재 읽은 builder 경로는 정상 리스트와 canonical states를 공급하므로 production NPE를 확정하지 않는다. null **FType 원소**는 합법적인 ABSENT_LOCAL 표현이며 null **domain 리스트**와 다르다.

**현재 테스트.** `TP/CandidateDomainRefinementTest`는 ROW 제거/유지, 무관한 refined input, domain 길이 불일치를 검사한다. inner-null/null-index/null-prior, empty-inner-domain 조합은 추가 계약 테스트가 필요하다.

**최소 수정 / 영향.** malformed input을 false로 거절할지 명시적 IllegalArgumentException으로 fail closed할지 API 계약을 일관되게 문서화한다. 리스트 원소 검증만 추가하고 후보 생성 정책을 넣지 않는다. empty inner domain은 실제 불가능성일 수 있으므로 무조건 malformed라고 거절하지 않는다. 영향은 helper 및 호출자의 error classification에 제한되어야 한다.

**회귀.** outer/inner null, null prior/index, out-of-range index, empty inner list, 여러 refined positions와 정상 `[null]` domain. 우선순위 P2.

### L3 — 초기 emitted-work 추적과 반복 재생성의 상태 손실에 대한 증거 공백

**위치 / 흐름.** `P/NeutralPlacementGraphBuilder.java:639–856`의 semantic/publication closure, `:695–698`의 `requiredEmittedNodes` 채집, `:2729–2785`의 baseline restore, `:1053–1123`의 privacy closure 및 `canonicalRelocationActions`/최종 state canonicalization. requiredEmittedNodes는 semantic closure를 거친 **후** 채집된다.

**위험 조건 / invariant.** 그 이전 단계에서 원래 emitted producer가 bottom/non-emitted로 바뀌는 경로가 있다면 후반 보존 검사가 원래 의무를 놓칠 가능성이 있다. proof/action/relation만 바뀌고 coarse legal states는 같은 경우에 모든 downstream dependency가 다시 처리되는지도 통합 검사가 필요하다. 그러나 여러 단계의 원본 노드 비교, nonconvergence 오류, privacy 재적용 및 최종 exact-reference 검사가 존재한다. 현재 증거로 stale privacy replay가 성공적으로 보호 데이터를 방출한다고 결론내릴 수 없다.

**현재 테스트.** `TP/TransientPlacementAlternativesTest`의 privacy narrowing/rebuild 안정성, authority tests의 foreign-reference 검사, session에 기록된 publication/stale-state failures가 관련된다. source epoch별 전체 state tuple의 idempotence 및 초기 emitted-work를 일관되게 비교하는 전용 fixture는 추가해야 한다.

**최소 수정 방향 / 영향.** 먼저 최초 emitted decision 집합과 compiler가 정당하게 non-emitted로 분류하는 이유를 구분해 기록한다. publication delta는 nodes뿐 아니라 keys/facts/clauses/relations/action identity/privacy까지 포함해야 한다. 기존 closure를 전면 교체하지 말고 실제 누락 dependency만 최소 보강한다. privacy policy, synthetic boundary 및 orphan-function 예외, exact graph identity를 유지한다.

**회귀.** proof-only 변화, action만 재생성, source clause 제거/추가, function-return 다단계, privacy narrowing 후 baseline replay, compiler가 정당하게 제거한 synthetic node와 잘못 소실된 emitted producer를 구별한다. 동일 authoritative 입력에 두 번 closure를 적용했을 때 완전한 구조적 상태가 같아야 한다. 우선순위 P2 조사, 기존 session failure와 직접 연결되는 최소 재현은 P1 검증.

## False positives / intentionally safe behavior

### F1 — Helper가 아직 통합되지 않았다는 판단은 현재 소스에는 맞지 않음

**위치/흐름:** `P/NeutralPlacementGraphBuilder.hasRefinedPredecessorInvalidation`에서 `CandidateDomainRefinement.removedByRefinedInputDomain`을 호출한다(`:3708` 부근). session 문서의 과거 설명보다 코드가 진전되었다. **Invariant/상황:** descendant tuple 제거를 실제 rebuild input domain으로 증명하는 경우. **테스트:** 주 분석 snapshot에는 helper 4개 테스트가 있었고, S4 재점검에서 2개가 추가되어 6개가 된 것을 확인했다. 모두 audit에서는 미실행이다. **수정:** 재통합/중복 checker 추가 불필요; H2의 domain 생성과 L2의 malformed 계약만 분리 검토. **보존/회귀:** 정책 없는 refinement와 무관한 input 변경 거절을 유지한다.

### F2 — Function-boundary selection 및 fixed-point가 전혀 없다는 판단도 폐기

**위치/흐름:** 최신 `PlacementAnalysis:1536–1538`, `CandidateSelections:1365`, `ExactPhysicalModel:657–675`, `LogicalBoundaryRealizations:182–193`에서 relation construction/validation, shared/Exact selection, boundary closure가 연결되어 있다. 감사 중 다른 작업자가 추가한 부분이다. **Invariant/상황:** formal/result와 ordinary writer가 섞인 native forwarding. **테스트:** 기존 coarse function alias 및 propagation 테스트는 관련 있으나 새 다중-pool 통합 검증은 필요. **수정:** gate를 다시 만들거나 지우지 않는다. 남은 D2의 dependency/component closure만 고친다. **영향/회귀:** existing pool/alias authority를 유지하며 다단계 formal-return과 mixed writer를 검사한다.

### F3 — Privacy/legal-excluded 오류는 guard를 완화할 이유가 아님

**위치/흐름:** `NeutralPlacementGraph.Node:115–132`, builder `closePrivacy:1053–1123`, transient replay `:3299–3307`. **Invariant/상황:** 보호된 source를 aggregation 이전에 local로 가져오거나 replay가 privacy-excluded state를 재발급하려는 경우 거절하는 것이 맞다. **테스트:** `PrivacyMovementCertificationTest`, `SharedPrivacyPlacementAnalysisContractTest` 계열. **수정:** policy 또는 constructor validation을 완화하지 않는다; 재생성/grounding 원인을 고친다. **영향/회귀:** legitimate public aggregation은 보존하되 raw protected payload 다운로드 및 arbitrary relocation은 계속 실패해야 한다.

### F4 — Shallow realization reference와 OR support clauses는 의도된 표현

**위치/흐름:** `PlacementIdentity`의 realization reference 및 `PlacementAnalysis:300–415`. shallow `(rule, realization key)`와 별도의 selected clause로 순환 proof signature의 무한 재귀를 피한다. 같은 key에 여러 clause가 있는 것 자체는 identity 혼동이 아니다. **Invariant/상황:** 동일 physical map을 여러 합법적 DIRECT/RELOCATION derivation이 지지하는 경우. **테스트:** `PlacementRealizationAuthorityTest`, `PrivacyMovementCertificationTest:291–317`. **수정:** recursive ancestor signature 복원 불필요. **영향/회귀:** 한 realization 안의 pool 일관성, selected clause ownership, foreign receipt 거절, 같은 FType/다른 map 분리를 유지한다. D1은 이 표현을 캐시가 충분히 구별하지 못하는 별도 문제다.

### F5 — 내부 staging lineage와 최종 실행 authority는 구분되어 있음

**위치/흐름:** `LogicalBoundaryRealizations.bind:221–224`, builder `removeUngroundedStagingRealizations` 및 `PlacementAnalysis`의 candidate support validator. **Invariant/상황:** 아직 source witness가 재결합되지 않은 physical rebuild 중간 단계는 final permission이 아니다. **테스트:** authority와 physical certificate tests가 관련. **수정:** 모든 staging row를 생성 시점에 바로 오류 처리하거나 임의 anchor로 채우지 않는다. **영향/회귀:** 최종 publication에서는 unsupported native state가 남지 않아야 하고, 합법적인 multi-pass function boundary는 조기에 사라지면 안 된다.

### F6 — CP function-call placeholder와 LOOP_PHI의 acyclic heuristic 제외

**위치/흐름:** `ExactPhysicalModel:395–405`는 DML FunctionOp를 실제 matrix transfer와 구분한다. `TP/FunctionBoundaryRuntimeAliasContractTest:20–44`와 `CompiledTransientOperationKindTest:45–55`, session의 acyclic continuation 설명도 함께 봤다. **Invariant/상황:** CP call placeholder는 protected input의 coordinator materialization과 동일하지 않다. acyclic local-continuation heuristic이 LOOP_PHI를 제외하는 것도 일반 loop legality를 부정하는 것이 아니다. **수정:** 이 둘을 D3 해결을 위해 변경하지 않는다. **영향/회귀:** 실제 actual/formal transfer authority, result alias, loop용 별도 inductive proof를 유지한다.

### F7 — 선택 의존 relocation을 lower bound에서 생략하는 것은 의도된 안전성

**위치/흐름:** `P/RelocationSelections.java:559–648`, `unavoidableCombinedPhysicalEmissionCount`. 아직 선택되지 않은 realization 때문에 비용이 달라질 수 있으면 해당 비용을 하한에서 생략한다. **Invariant/상황:** branch-and-bound의 admissible lower bound이지 최종 objective가 아니다. **테스트:** exact indexed/canonical parity 및 후속 exhaustive tests가 관련. **수정:** 하한을 근거 없이 높이거나 최종 비용을 0으로 고정하지 않는다. **영향/회귀:** pruning 안전성을 유지하고 실제 leaf scorer를 검사한다. D1의 잘못된 leaf score 재사용은 하한의 의도된 보수성과 별개의 결함이다.

## Recommended fixes — P0 / P1 / P2

P0/P1/P2는 **실험 종료 후의 구현 순서**다. 지금 실행 중인 실험을 멈추거나 현재 코드에 긴급 패치를 넣으라는 뜻이 아니다. 구현 전 최신 dirty 변경을 다시 읽고 다른 작업자의 수정을 보존해야 한다.

| 순서 | 작업 | 최소 patch boundary | 예상 영향 범위 | 변경하면 안 되는 것 | 완료 증거 |
|---|---|---|---|---|---|
| P0-1 | D1 cache 동치 복구 | CandidateProblemIndex effect key + CandidateSelections cache | row search 비용/메모리, 정확한 relocation score | 실제 scorer/계수/preference의 편법 변경 | cache on/off + 완전 열거 parity |
| P0-2 | H1 증명 반례 고정 및 필요 시 grounding 수리 | NativePlacementContinuity proof kernel | loop 및 native proof 전체 | 모든 cycle 일괄 거절, privacy/runtime 우회 | AND/OR negative와 grounded-loop positive 동시 통과 |
| P1-1 | D2 모든 boundary factor scope 연결 | shared dependency index, component union, separability guard | function shared selection/partial invalidation | fake Hop edge, Exact factor 삭제 | boundary-only factor의 global/component parity |
| P1-2 | D3 identity backedge publication 완성 | transient replay / identity-bootstrap | loop joins 및 all-writer relation | all-reaching validator 완화 | writer-set 동일성 + identity-loop positive |
| P1-3 | H2 bottom/local 구별 | inputDomains와 producer error propagation | candidate closure, function/transient consumer | null FType의 LOCAL 의미 일괄 변경 | bottom에서 local row 생성 금지 |
| P1-4 | H3 explicit native-local base authority | executable node projection | FED/LOUT coverage, scalar exceptions | 모든 FED/LOUT 삭제, 원래 emitted work 은폐 | unavailable-row negative + scalar positive |
| P1-5 | D4 worker cardinality 계약 통일 | selected-pool count helper 및 전달부 | Exact transfer cost의 입력 값 | calibration/계수/legality, partition geometry | 중복 endpoint에서 정확한 count |
| P2 | L1–L3 증거 확보와 제한적 보강 | witness 종류, helper validation, closure snapshot/invalidation | 고립된 방어 코드 + 통합 진단 | 전면 builder 교체/정책 재설계 | 명시한 최소 반례 또는 의도된 동작의 계약 테스트 |

### 후속 구현 에이전트를 위한 절차

현재 소스를 확인한 뒤 첫 변경은 각 결함을 고립시킨 regression fixture여야 한다. 이미 추가된 boundary gate/closure, canonical action/state binding, helper integration을 덮어쓰지 않는다. 패치마다 **shared facts 생성 → 후보 선택 → normalized result → Exact projection/emission prevalidation**을 동일 authority로 통과하는지 확인한다. 서로 다른 planner는 서로 다른 objective를 가질 수 있으므로 단순히 출력 plan이 같다는 것을 통과 기준으로 삼지 않는다.

전역 `git reset/checkout/clean`, dirty builder 파일 전체 치환, cost-policy 튜닝으로 오류 우회, privacy/TR/TW 예외 추가는 repair plan에 포함되지 않는다. 각 수정은 위 patch boundary 안에서 검토하고, 불가피하게 넓어지면 관련 authority와 재검증 범위를 명시한다.

## Required regression tests

아래 이름은 신규 테스트 설계안이며 아직 생성하지 않았다. 기존 tests도 이 감사에서는 실행하지 않았다.

| 제안 fixture | 필수 검증 | 직접 연관 |
|---|---|---|
| `RelocationScoreCacheRealizationParityTest` | canonical two-pool source, DIRECT/RELOCATION clauses, 방문 순서 변화, cache on/off/uncached exhaustive 동일 | D1 |
| `LogicalBoundaryComponentClosureTest` | actual/formal/result 관계만으로 연결된 P/Q 선택; dependency scope에 모든 relation 포함; partial-domain invalidation | D2 |
| `IdentityLoopTransientRelationTest` | compiler 최적화로 지워지지 않는 identity TW/TR CFG; seed 및 backedge 모두 relation에 존재 | D3 |
| `SelectedPoolWorkerCardinalityTest` | 하나의 anchor 내부 duplicate endpoint, path alias, port 구별; native witness/relocation target/legacy graph count 의미 비교 | D4 |
| `CandidateGroundingAlternativeLeakTest` | `A->{A} OR {G,U}`, `U->{U}` negative; grounded loop positive; OR/AND 및 root-pinning 구별 | H1 |
| `CandidateInputBottomDomainTest` | empty known matrix domain은 ABSENT_LOCAL이 아님; local scalar/literal 및 정상 CP/LOUT는 유지 | H2 |
| `ExecutableProjectionAuthorityTest` | unavailable all-local row가 FED/LOUT authority로 사용되지 않음; genuine native scalar authority 유지 | H3 |
| `BoundaryPublicationIdempotenceTest` | nested formal/result 및 transformencode primary lineage, mixed ordinary/formal join, proof-only/action-only 변화, privacy replay 후 complete fixed point | L3 / F2 / F5 |
| helper/witness 기존 test 확장 | null outer/inner distinction, malformed endpoint, shifted ROW/COL geometry, receipt/action/state foreign identity 거절 | L1/L2 및 공통 invariant |

### 공통 negative assertions

반드시 “예외가 났다”뿐 아니라 **어느 authority에서 왜 거절했는지**를 검사한다. 예: unsupported pool, missing reaching definition, malformed domain, privacy deny, foreign receipt/action을 구별한다. privacy violation 테스트는 emission registry 또는 runtime mutation 이전에 실패해야 한다. 반대로 정상 candidate를 profile/closure 오류로 떨어뜨린 것을 privacy pass로 계산하면 안 된다.

canonical fixture는 실제 analysis가 소유한 rule/emission/realization/clause 객체를 사용해야 한다. 별도 생성한 value-equal 객체를 넣어 foreign-object guard에 먼저 걸리는 테스트는 D1/D2의 원래 결함을 검사한 것이 아니다. pure helper/proof-kernel 반례와 end-to-end canonical fixture를 분리한다.

## Dynamic validations after experiments complete

**전 항목 NOT_RUN.** 현재 실험의 종료를 소유자가 확인하고 실행 예산/작업공간을 정한 뒤에만 수행한다. 이 목록은 예약 작업이나 이 감사가 나중에 자동 실행할 작업이 아니다.

1. **단일 격리 build/test epoch 확보.** 동시 Maven compiler/surefire와 output-directory 공유를 피한다. 새 evidence directory 및 정확한 source HEAD/dirty hashes, JDK/build 옵션을 기록한다. 현재 `target/surefire-reports`나 기존 실험 로그를 재현 근거로 덮어쓰지 않는다. 처음에는 위 순수 helper/graph tests만 좁게 실행한다.
2. **Cache/component exactness 비교.** 같은 작은 canonical graph에서 cache enabled/disabled 및 monolithic exhaustive/component search를 비교한다. 선정 objective, candidate receipt/selected clause, relocation choices, emitted actions, privacy feasibility를 모두 대조한다. pool과 row enumeration 순서도 바꾼다.
3. **기존 관련 회귀 묶음.** `CandidateDomainRefinementTest`, `NativePlacementContinuityTest`, `NativeMixedWorkerPoolContinuityTest`, `PlacementRealizationAuthorityTest`, `PrivacyMovementCertificationTest`, `TransientPlacementAlternativesTest`, `FunctionBoundaryRuntimeAliasContractTest`, `SharedPlannerFunctionPlanPropagationRedTest`, `RelocationSelectionsPhysicalAnchorTest`, `ExactTransientRealizationTest`, `ExactNativeLocalAnchorFanoutCostTest`, `ExactPhysicalWorkerCountTest`, `ExactPhysicalModelCertificateTest`를 순차적이고 제한된 범위로 확인한다. 현재 소스에서 이름/실행 환경을 확인하며, 과거 session 결과를 재사용해 pass로 세지 않는다.
4. **CFG/closure/privacy 통합.** branch의 same-pool/different-pool join, mixed formal+ordinary writer, identity loop, grounded update loop, unrelated local backedge, transpose, nested function/result/recompile boundary를 검사한다. raw rebuild에서 곧바로 통과를 선언하지 말고 privacy/action binding 후의 전체 publication state를 비교한다.
5. **Worker/map runtime 계약 확인.** 동일 endpoint의 다중 ranges, ROW×BROADCAST, partition-aware request와 physical worker count가 실제 runtime map/batch에서 어떤 의미인지 확인한다. locality/transfer cost만 확인하는 것이 아니라 실행 instruction의 실제 source FederationMap, emitted target pool, 불필요한 relocation 유무를 대조한다.
6. **마지막으로 기존 validation campaign.** 앞 단계 통과 후에만 AGENTS와 현행 campaign 문서가 정한 컨테이너/런타임 환경에서 LM, 관련 ML training fixtures, p1/p2 및 slice-line의 기존 검증 방식에 연결한다. 이번 감사가 그 campaign을 실행했다거나 완료했다고 보고하지 않는다. 수치 correctness, selected authority, protected payload 이동 금지, instruction legality를 먼저 통과해야 하며 성능 주장은 별도의 유효한 측정 이후에만 한다.

## Execution safety and audit trail

### 수행한 작업

Remote Desktop Commander의 연결 장치 식별, 제한된 `date/hostname/ps`, AGENTS와 session 문서 읽기, Git read-only status/log/diff/ls-files/grep 및 명시한 소스/테스트 읽기를 사용했다. `GIT_OPTIONAL_LOCKS=0`, `GIT_PAGER=cat`, `git -c core.fsmonitor=false status --short --untracked-files=normal`, `git diff --no-ext-diff --no-textconv` 계열을 사용하여 Git의 선택적 index write/외부 diff 실행을 피했다. 초기 diff stat 및 현재 dirty diff를 메모리로 읽고 관련 변경을 검토했다.

반복 source 검색/줄 추출/해시/문서 조립은 `nice -n 19 ionice -c 3 python3 -I -B -i -q`로 띄운 **감사 전용 프로세스 PID 3097103** 안에서 수행했다. 대부분 이미 읽어 둔 문자열에 대한 연산이며 테스트 코드나 planner를 import/실행하지 않았다. `-B`로 Python bytecode 파일 생성도 막았다. `rg`는 설치되어 있지 않아 실패했고 설치하지 않았다. 이후 범위가 한정된 `git grep` 또는 메모리 regex로 대체했다.

### 실행 중인 프로세스에 대한 관찰

시작/초반 `ps`에서 이 worktree의 다른 작업자 Maven 및 surefire 프로세스를 확인했다. 서버 시각 약 `23:14+02:00`의 관찰에는 Maven PID 3098620과 surefire PID 3098790/3098793이 포함됐다. 여러 코어를 사용 중이어서 audit은 낮은 우선순위 source reads만 유지했다. 후속 `23:28:56+02:00`의 같은 worktree Maven/surefire 패턴 조회에는 해당 프로세스가 보이지 않았다. **그것만으로 성공 종료, 실패 종료 또는 모든 실험 종료를 판정하지 않았다.** 어느 프로세스에도 signal을 보내지 않았다.

감사 프로세스는 `NI=19`, `ionice=idle`로 조회됐고, 확인 시 RSS 약 35 MB, `ps` 표시 CPU 0.0%였다. 이는 그 시점의 제한적인 관찰이며 audit의 누적 CPU/I/O가 정확히 0이라는 뜻은 아니다. 읽은 개별 파일은 약 1.6 MB 규모였고 대형 결과/데이터셋 스캔을 하지 않았다. diff와 재읽기는 별도 소량 I/O이므로 이 숫자를 전체 디스크 read-byte 측정값으로 제시하지 않는다.

### 하지 않은 작업 / 쓰기 경계

`kill`, `pkill`, reboot, 서비스/container 제어, Docker 실행/중지/재시작, Maven/JUnit 실행, benchmark/workload 실행, Git reset/checkout/clean/rebase/commit/push를 수행하지 않았다. experiment directory, logs, reports, results, Java/DML/test source, AGENTS, session 문서 및 기존 dirty 파일에 write하지 않았다. 프로세스의 열린 실험 파일을 추적하는 `lsof`/recursive `/proc` sweep이나 대형 디렉터리 스캔도 하지 않았다.

유일한 서버 측 write 대상은 요청된 `docs/STATIC_ANALYSIS_AUDIT_2026-09-14.md`이다. 기존 파일이 없는 것을 확인한 뒤 exclusive-create로 생성하여 다른 문서를 덮어쓰지 않는다. 증거 snapshot은 메모리에만 두었고 코드 백업 디렉터리나 test output을 생성하지 않았다.

**확인할 수 있는 결론:** 이 감사는 실험의 코드/설정/로그/출력/프로세스 생명주기를 의도적으로 변경하지 않았으며, 실행 기록상 그런 명령이 없다. **확인할 수 없는 결론:** 공유 서버에서 파일 읽기와 Git metadata 조회가 page cache, scheduler 또는 I/O에 물리적으로 0의 영향을 주었다는 것. 이를 증명할 별도 동적 계측은 수행하지 않았고, 그런 계측 자체가 요청 제한에 어긋날 수 있다. “실험 무영향”은 위 비침습적 작업 경계와 낮은 부하 관찰 범위에서만 주장한다.

### 동시 변경 처리

감사 중의 변경을 audit의 수정으로 오인하지 않도록 최초 상태, 재읽기 시각, 마지막 읽은 해시를 남긴다. 초기 snapshot 이후 boundary/selection/analysis 관련 파일과 session 문서가 다른 작업자에 의해 진전됐고, `LocalMaterializationSelections.java`의 selected provenance 변경도 새 dirty diff로 읽었다. 기존 dirty 상태를 복구하거나 정리하지 않았다. 아래 상태 비교는 다른 작업자의 활동을 포함하므로 “worktree 전체가 그대로다”라는 증거로 사용하지 않는다.


## Final concurrent-delta review (S4)

문서 조립 후의 제한된 재점검에서도 다른 작업자의 추가 변경을 발견했다. 아래 변경은 감사가 작성한 코드가 아니다. 기본 본문의 줄 번호를 사용할 때 아래 파일 해시와 메서드를 함께 대조한다.

- `CandidateDomainRefinement`에 `refinedInputPositions(priorRows, exactInputDomains)`가 추가됐다. prior marginal projection보다 exact domain이 strict subset인 위치만 인정하고 swap/expansion은 제외한다. builder에는 affected physical cone 전파와 이 증거의 결합이 추가됐다. 새 helper 테스트 두 개는 stale ordinal bookkeeping 및 ROW→COL replacement를 다룬다. 따라서 F1은 통합 완료라는 관찰을 유지하며, L2는 기존 removal helper와 새 helper의 malformed nested input 계약을 함께 점검해야 한다. 신규 6-test 소스는 읽었지만 실행하지 않았다.
- builder의 `closePrivacy`에 새 allowed result가 확정된 state의 이전 PRIVACY diagnostic만 제거하는 코드가 추가됐다. 이는 기존 legal/excluded 불일치에 대한 보완이며, privacy policy 자체의 완화라고 단정하지 않는다. 별도 다른 reason의 exclusion은 보존한다. L3의 replay/재검증 필요성은 남는다.
- transient write binding이 durable map뿐 아니라 typed native pool witness를 가진 source도 alias하도록 변경됐다. source의 transitive clause를 복제하지 않고 직접 source binding을 남긴다. 이는 stale/native lineage 소실을 보완하는 방향이다. D3의 identity-loop source-set 축약과 final all-reaching 요구는 이 변경에서 수정되지 않았다.
- `NativePlacementContinuity.operationPreservesWitness`에 transformencode multi-return FunctionOp 경로가 추가됐다. H1의 SCC grounding 조건은 바뀌지 않았다.
- `PlacementAnalysis` all-reaching 예외에 reaching/supplied/readers 진단이 추가됐다. 검사 의미는 동일하다.
- D1의 cache key/사용부, D2의 CandidateSelections dependency/component 처리, D4 worker cardinality 구현에는 이 재점검에서 관련 변경이 없었다.

S4는 위 제한된 diff를 검토한 것이며 이 구현들의 동적 통과를 보증하지 않는다. 이후 추가 동시 변경도 가능하므로 다음 구현 에이전트는 patch 전 최신 상태를 다시 확인해야 한다.

S4 검토 기록 시각: `2026-09-14T23:39:08.190232+02:00`

| S4 변경 파일 | 재점검 내용 SHA-256 |
|---|---|
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java` | `29615e60cb0059a3e4a57736332ee182aa87a18e1052483dfbbb635fd7205c79` |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java` | `8c25a0bdadbc69be425cbe597798e389863fb9a94d6853cf29b40ff597919968` |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java` | `b9f51d8437a4b2a7970216e16b1ec21d218b16335679856825e1245699a8adfc` |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateDomainRefinement.java` | `d4e11c5657bed401e1dc118ccd270f9f2d1fa6976d4927d09896f3949353e9eb` |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/CandidateDomainRefinementTest.java` | `8affa3f4f1014206e0450e1e906a7ba8f9d48b04b16ba57e549ca95287dc9457` |

## Read-file manifest (primary audit snapshots)

이 목록은 디스크에서 내용을 읽은 파일을 나타낸다. 큰 파일은 본문에 인용한 메서드 및 dirty hunk를 중심으로 분석했다. SHA는 해당 read의 내용이며 S4 live delta가 있으면 위 별도 해시를 함께 사용한다. stable=true는 한 번의 파일 읽기 전후 mtime이 같았다는 뜻일 뿐 여러 파일의 원자적 snapshot 증명은 아니다.

| 읽은 파일 | Bytes / lines | Read time (server) | SHA-256 | Stable read |
|---|---:|---|---|---|
| `AGENTS.md` | 10028 / 111 | 2026-09-14T23:10:33.868252+02:00 | `91d12a3a6b5cce17b09255a89bf2670c4e170b646a60320c60ad9844f6536dbc` | true |
| `docs/SESSION_ISSUES_2026-09-14.md` | 21123 / 85 | 2026-09-14T23:23:12.886563+02:00 | `8b74aa3f7ab048bc79235054ccd0049110825071c5df26dcb7c641652dd7b3b2` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java` | 18312 / 417 | 2026-09-14T23:25:46.671935+02:00 | `55791ab374cb1f6da75d864563963fad4d8460a7c0ebbb7373943ccf3ce30782` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java` | 101812 / 2274 | 2026-09-14T23:25:04.699921+02:00 | `2edd21861f7096a21aa5fa9abccfd03507162ed5a63800ce81ea80d81da1ea69` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedWorkerUtils.java` | 1708 / 49 | 2026-09-14T23:28:31.069102+02:00 | `c57130a56f56e6c00772c5fa4f490885d7e8cbd0553e0c6e50cf2c559f7f7969` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java` | 99949 / 1935 | 2026-09-14T23:10:33.851991+02:00 | `b92abdd62cde2503d2f2c561708b28d3e8e0b6dba95398c769753050ac4102d8` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java` | 50214 / 932 | 2026-09-14T23:23:12.886904+02:00 | `2b7e5bc1417d296163fd425a230046ae009134bffc58bf9d5b83f4307ffea0b1` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalPlacementProjector.java` | 6170 / 114 | 2026-09-14T23:10:33.853250+02:00 | `05b4ec4b04c79f590da9dda84fd8eb59dc802ffb40c7b41dd92b3d7a0a8ef7d0` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalSelection.java` | 16253 / 288 | 2026-09-14T23:10:33.853403+02:00 | `068d5e06ec02ff3776bc97dbcca84be8c390e7a7c4a6e8d8b5dde11233e7d87d` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateDomainRefinement.java` | 2038 / 49 | 2026-09-14T23:10:33.867028+02:00 | `5a2374b1f58e20610bf800c5af68f88fc6a8f9ee23c80cf5cbdd0f1066c0f016` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java` | 119509 / 2392 | 2026-09-14T23:23:12.887523+02:00 | `a0929c1b449566c8a874d742c52bb32269ca0662dce8bf5eed901cf0af75e7c0` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/LocalMaterializationSelections.java` | 23187 / 479 | 2026-09-14T23:30:25.307469+02:00 | `e56729132a32587356fcb9213997152143bf94abced1f939d826d42ff08c6586` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/LogicalBoundaryRealizations.java` | 16150 / 290 | 2026-09-14T23:29:27.600094+02:00 | `b3215164ed5c749a18f676e20d899d2b9eb086114c8bdb28c12ef89a18f8b9d1` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java` | 39085 / 825 | 2026-09-14T23:23:12.887746+02:00 | `6c1d53a02988b3d4d2f96d3111fcf1177f53cabf3b2cf3a59e49de303818ee6f` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraph.java` | 29509 / 689 | 2026-09-14T23:10:33.855236+02:00 | `c4fcdec1859f02b7204c4483324b18386901d7eee700b4d76dbf601761f6a48b` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java` | 421274 / 7695 | 2026-09-14T23:29:27.599906+02:00 | `517fa6858c93752202f9c8bd1af59401a451f9a7fa3f536f081437c58dd718f3` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java` | 151106 / 2783 | 2026-09-14T23:23:12.890040+02:00 | `392615d6e6ff3c3a29207d8befe7f3fa7bc34195ebc45f2c53f2fd48b6741933` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java` | 71206 / 1368 | 2026-09-14T23:23:12.890288+02:00 | `fea7e16d23cd4f6df82b52ac08a250a5ccd08549b6773704eca8624bc6b059fb` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java` | 38304 / 895 | 2026-09-14T23:10:33.864069+02:00 | `39eb844295f76700edeb07722ef83c4c79f3a8a48c0f7248ed9c2bc8911cb2b4` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/RelocationSelections.java` | 97778 / 2138 | 2026-09-14T23:10:33.864528+02:00 | `1a870b1ef359291d42eb873a1fd9c7fc51ebc2e0d16b37732e0a5317f578f6d3` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/ImmutableNormalizedPlannerResult.java` | 9999 / 172 | 2026-09-14T23:10:33.865306+02:00 | `7efb68774709704af4ca935d5600b4d78b46c34329151b6d9fb1beeb44ce4f8e` | true |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/NormalizedPlannerResults.java` | 10532 / 188 | 2026-09-14T23:10:33.865491+02:00 | `2074c7fa8ae257d2fdfce3c7aa1f0b26679ea5c48fa25c1f8489c9ca5317008c` | true |
| `src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationUtils.java` | 44590 / 1106 | 2026-09-14T23:29:27.653776+02:00 | `bee333d4ca87838a6b8b3e93fe64c55566a5cb584dbd2120a8df6fed85be59c2` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactNativeLocalAnchorFanoutCostTest.java` | 11571 / 192 | 2026-09-14T23:10:33.865668+02:00 | `38002bc736c28c768ab66b5d96d0c8985747f57601b9e1bd68bc2bc96faa3fca` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalWorkerCountTest.java` | 2773 / 60 | 2026-09-14T23:25:04.699475+02:00 | `c6d784c34c7c844e209d754421e498d2e3f5199008291e697890c0cdcfde43e9` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactTransientRealizationTest.java` | 5675 / 94 | 2026-09-14T23:10:33.868072+02:00 | `1a10e88cb80c23a78258098aaab6e14375c5366e15b1ce22283665c8acffd110` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/CandidateDomainRefinementTest.java` | 2293 / 62 | 2026-09-14T23:10:33.867340+02:00 | `394188e141ea6531b2a935193821ab88486a3caa2fab0fb653a7794c55164df4` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/CompiledTransientOperationKindTest.java` | 6308 / 129 | 2026-09-14T23:10:33.865865+02:00 | `69b406bd2198cd2cebc6df410e7d08c9d50552f10aa8fa0c814dd3287561fdbe` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/FunctionBoundaryRuntimeAliasContractTest.java` | 2860 / 52 | 2026-09-14T23:28:57.383882+02:00 | `2c42036e1cffeeeda4c8f4ded39d46dec909a1f1bf3f3306620013962a352205` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/HeuristicLocalContinuationTest.java` | 17633 / 324 | 2026-09-14T23:10:33.866012+02:00 | `993953eaf59ec0131a55abbfbb2154015c5f542aef99aaae5bc2184ef97dc725` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeMixedWorkerPoolContinuityTest.java` | 9487 / 167 | 2026-09-14T23:10:33.867450+02:00 | `bbcacf62bfff4a1eba694071511a224aa8ac2a49ae8bca92412de2ae32d61987` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuityTest.java` | 44770 / 845 | 2026-09-14T23:10:33.866276+02:00 | `01ea69becd9cd4c49a827b3a2b0a0c7e5c63ad85ce3bccfcba5874a4ab7e7fe0` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementRealizationAuthorityTest.java` | 14189 / 252 | 2026-09-14T23:10:33.867623+02:00 | `4f4f24d13d5821b7278307c5201cf439e38e1e0abd4cd337eace882520803da6` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/PrivacyMovementCertificationTest.java` | 37742 / 643 | 2026-09-14T23:10:33.866684+02:00 | `43cc57a537125d70e5ea05bc6c5a530bb03d2c86825615a78ee8d2fff27444b2` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/RelocationSelectionsPhysicalAnchorTest.java` | 9540 / 195 | 2026-09-14T23:28:57.384898+02:00 | `66805491c2f7216c8a7ee65191ed347bc104c1e4b43b3e1025dd7a3b36cd1bd8` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/SharedPlannerFunctionPlanPropagationRedTest.java` | 14638 / 256 | 2026-09-14T23:28:57.384376+02:00 | `27da7d3bd024cee6aabac744a4419d839da940d0f5c6fb4bd7ec27d7f4c5598a` | true |
| `src/test/java/org/apache/sysds/hops/fedplanner/placement/TransientPlacementAlternativesTest.java` | 20341 / 363 | 2026-09-14T23:23:12.890566+02:00 | `345324848c78c04b009d0ece611f922cb3e86acace53a58cb4242f097cd8b74c` | true |

## Git state and command inventory

### Initial recorded status

```text
 M docs/SESSION_ISSUES_2026-09-14.md
 M src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java
 M src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java
 M src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalPlacementProjector.java
 M src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalSelection.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraph.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/RelocationSelections.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/ImmutableNormalizedPlannerResult.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/NormalizedPlannerResults.java
 M src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactNativeLocalAnchorFanoutCostTest.java
 M src/test/java/org/apache/sysds/hops/fedplanner/placement/CompiledTransientOperationKindTest.java
 M src/test/java/org/apache/sysds/hops/fedplanner/placement/HeuristicLocalContinuationTest.java
 M src/test/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuityTest.java
 M src/test/java/org/apache/sysds/hops/fedplanner/placement/PrivacyMovementCertificationTest.java
?? .omx/
?? build/
?? src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateDomainRefinement.java
?? src/main/java/org/apache/sysds/hops/fedplanner/placement/LogicalBoundaryRealizations.java
?? src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactTransientRealizationTest.java
?? src/test/java/org/apache/sysds/hops/fedplanner/placement/CandidateDomainRefinementTest.java
?? src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeMixedWorkerPoolContinuityTest.java
?? src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementRealizationAuthorityTest.java
?? src/test/java/org/apache/sysds/hops/fedplanner/placement/TransientPlacementAlternativesTest.java
```

### Last pre-document status

```text
 M docs/SESSION_ISSUES_2026-09-14.md
 M src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java
 M src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java
 M src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalPlacementProjector.java
 M src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalSelection.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/LocalMaterializationSelections.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraph.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/RelocationSelections.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/ImmutableNormalizedPlannerResult.java
 M src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/NormalizedPlannerResults.java
 M src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactNativeLocalAnchorFanoutCostTest.java
 M src/test/java/org/apache/sysds/hops/fedplanner/placement/CompiledTransientOperationKindTest.java
 M src/test/java/org/apache/sysds/hops/fedplanner/placement/HeuristicLocalContinuationTest.java
 M src/test/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuityTest.java
 M src/test/java/org/apache/sysds/hops/fedplanner/placement/PrivacyMovementCertificationTest.java
?? .omx/
?? build/
?? src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateDomainRefinement.java
?? src/main/java/org/apache/sysds/hops/fedplanner/placement/LogicalBoundaryRealizations.java
?? src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactTransientRealizationTest.java
?? src/test/java/org/apache/sysds/hops/fedplanner/placement/CandidateDomainRefinementTest.java
?? src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeMixedWorkerPoolContinuityTest.java
?? src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementRealizationAuthorityTest.java
?? src/test/java/org/apache/sysds/hops/fedplanner/placement/TransientPlacementAlternativesTest.java
```

이 상태에서 감사 문서가 추가되는 것 외의 dirty 항목은 다른 작업자의 기존/진행 중 변경이다. 마지막 tracked diff는 401467 bytes, 21 files였다. 전체 diff는 메모리로 읽었고 기본 snapshot 이후의 관련 추가 변경은 위에서 별도로 검토했다.

### Recorded Git read commands

아래 명령은 해당 worktree에서 GIT_OPTIONAL_LOCKS=0 / GIT_PAGER=cat로 수행했다. 중복 호출은 한 줄로 모았다.

```sh
git -c core.fsmonitor=false status --short --untracked-files=normal
git diff --no-ext-diff --no-textconv --unified=3
git diff --name-only
git log -1 '--format=%H %ci %s'
git grep -n LogicalBoundaryRealizations -- src/main/java
git ls-files '*WorkerCount*' '*ExecPlacementPolicy.java' '*FederationUtils.java' '*FederatedCostModel.java'
git grep -n -E 'canonicalFederatedWorkerAddress|countDistinct.*Worker|computeReusableMaterializationDownloadCost' -- src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationUtils.java src/main/java/org/apache/sysds/hops/fedplanner
git ls-files 'src/test/java/org/apache/sysds/hops/fedplanner/placement/*Relocation*Test.java' 'src/test/java/org/apache/sysds/hops/fedplanner/placement/*Candidate*Test.java' 'src/test/java/org/apache/sysds/hops/fedplanner/placement/*Function*Test.java'
git diff --no-ext-diff --no-textconv -- src/main/java/org/apache/sysds/hops/fedplanner/placement/LocalMaterializationSelections.java
```

### Other read-only operations

`Remote Desktop Commander.list_devices`, `read_file`로 AGENTS/session을 읽었고, `start_process`로 제한된 shell read 명령 및 저우선순위 Python REPL을 실행했다. process 출력은 read/interact로 수집했다. `date -Is`, `hostname`, `ps -eo pid,ppid,lstart,etime,stat,pcpu,pmem,rss,args` 및 NI 포함 변형, 제한된 `head/grep`, `wc -l`, `sha256sum`, 명시 경로 `AGENTS.md` 존재 확인, `stat`, `ionice -p 3097103`를 사용했다. Python에서는 위 manifest 경로의 `Path.read_text/stat`, 해시, 문자열 줄 추출/regex/difflib, 메모리 문서 조립만 수행했다. `rg` 시도는 실행 파일 부재로 실패했고 설치/환경 수정은 하지 않았다. 문서 문자열 조립 중 한 차례 SyntaxError가 발생했으며 파일을 쓰기 전에 수정했다. planner/test 실행과는 무관하다.

### Generated / modified files

- 생성: `/home/mchoi/systemds-lm-worker-count-fix/docs/STATIC_ANALYSIS_AUDIT_2026-09-14.md` — 이 문서.
- 수정: 기존 파일 없음. Java/DML/tests, AGENTS/session, Git index, experiment logs/results/configurations에 의도적 write 없음.
- 테스트/benchmark/실험 실행: **0건**. 코드 수정: **0건**. 서비스/container/process 제어: **0건**.

이 문서의 후속 repair 및 validation은 제안일 뿐 수행 결과가 아니다.

## Post-write verification

서버 시각 `2026-09-14T23:40:16+02:00`의 저장 후 확인 결과:

- 문서 저장 직전/직후 읽은 37개 소스·테스트·지침 파일의 SHA-256 비교에서 변경된 파일은 0개였다. 이 비교는 문서 쓰기 구간의 검증이며, 전체 감사 기간에 다른 작업자의 변경이 없었다는 뜻은 아니다.
- 저장 전후 Git status set 차이는 `?? docs/STATIC_ANALYSIS_AUDIT_2026-09-14.md` 하나의 추가뿐이었다. 제거된 status 항목은 없었다. HEAD는 `ffb7be5bd85367156ed9ea86dacbaff4be0f035d`로 같았다.
- 기록한 Markdown과 디스크에서 다시 읽은 UTF-8 bytes가 같음을 확인했다. 필수 section 제목과 code-fence 짝도 검사했다.
- 감사 REPL PID 3097103은 NI=19 / ionice=idle이었다. 확인 당시 RSS 37,564 KB, ps CPU 0.0%였다. 해당 조회에서 worktree Maven/surefire 패턴은 보이지 않았지만 모든 실험이 끝났다고 추정하지 않았다.
- 이 기록을 추가한 뒤 문서를 다시 읽어 일치성을 확인하고 감사 REPL만 정상 종료한다. 실험 프로세스에 signal을 보내거나 서비스/container를 조작하지 않는다.
