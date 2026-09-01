# Session issues — 2026-08-06

## 1. WAN-light MinST StepLM이 함수 재호출마다 동일 `y`를 REFED함

- **상태**: 해결
- **환경/조건**: Docker `run_LAN_docker.sh`, WAN-light, MinST, worker=3, commit `abca6f91d43cf5c575cb88eeef696dedecc446f2`
- **재현 절차**: 기존 campaign 결과의 `warm-fresh-coordinator-jvm/raw_coordinator.log`를 확인한다. 대표 로그는 `/home/mchoi/g014-one-pass-continuation-2aa0f56-26882dd-20260806-v1/cells/098-637a87a8cad4/phases/cell-1/warm-fresh-coordinator-jvm/raw_coordinator.log`이다.
- **관측 증상**: worker=3에서 MinST 실행시간은 `154.592s`이고 `fed_fed_refed`가 `45.024s / 4201회`이다. 같은 실행에서 HOP DAG SB 재컴파일도 정확히 `4201회`, `.builtinNS::linear_regression`과 `.builtinNS::m_lm`은 각각 `2100회`이며, Federated I/O는 `Read/Put/Get=6/6300/12604`이다. 각 worker의 통계는 cold/warm phase마다 FED `tsmm` 1회와 `rmvar` 2101회뿐이고, 동적 `ba+*`나 그 밖의 FED compute는 없다. DP worker=3은 `15.221s`이며 이 동적 REFED가 없다.
- **원인 분석**: DML `FunctionOp`는 coordinator의 `FunctionCallCP`로 actual을 formal에 전달하는 논리 경계인데, REFED consumer 탐색이 이 forwarding placeholder를 물리 FED consumer로 취급했다. 그 결과 함수 내부의 실제 실행 HOP들이 모두 CP여도, 동적 재컴파일 때마다 local actual `y`를 worker에 업로드했다. 기존 로그의 `4201 recompile = 4201 REFED`와 worker에서 실질 FED compute 없이 `rmvar`만 반복된 현상이 이 잘못된 consumer 판정과 일치한다.
- **해결 요약**: `FederatedRefedPolicy`의 FED 입력 보장 및 physical FED consumer 검색에서 DML `FunctionOp` 논리 경계를 제외했다. relocation은 함수 내부에서 선택된 실제 executable HOP가 소유하며, runtime fallback이나 계획 후 보정은 추가하지 않았다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`, `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014MinStStepLmRuntimeRecompileRefedRedTest.java`
- **검증**: `CampaignBG014MinStStepLmRuntimeRecompileRefedRedTest#minStRecompileDoesNotUploadLocalYWithoutPhysicalFedConsumer` 통과. 2회 함수 재컴파일을 포함한 실제 local federated worker 실행에서 `fed_fed_refed <= 1`, Federated I/O `Read/Put/Get=2/0/2`, FED execute `0`을 확인했다. 관련 targeted test 묶음 전체도 errors/failures 0이다. 이어서 immutable stage `dcc70c65daaeef8bd9d740a6a02df41ff504cd38ef37f223fa32bee8bc0c4d7e`에서 StepLM MinST worker=3 WAN-light Docker cold/warm을 실행했다. warm runtime은 `154.592s -> 14.422s`, `fed_fed_refed`는 `4201 -> 1`, instruction fingerprint는 `fed_fed_refed:1;fed_fedinit:2;fed_tsmm:1`이며 semantic oracle, runtime scan, cold/warm plan equality, fresh network evidence, zero-restart/zero-residual-resource 검증을 모두 통과했다. 검증 row는 `/home/mchoi/g014-wan-light-affected-abca6f9-20260806-v1/cells/steplm-minst-w3/validated-row.json`이다.
- **잔여 이슈**: 구조적 결함 검증에는 없음. 다만 기존 worker=1/2/4 점은 이전 commit 측정이므로 새 commit의 all-worker scaling 근거로 혼합 해석하지 않는다.
- **잠재 회귀 위험**: 함수 내부에 실제 FED consumer가 있는 경우 actual/formal 경계에서 필요한 relocation이 누락될 수 있다. 함수 내부 exact placement가 relocation을 소유한다는 기존 function-boundary 테스트와 WAN-light Docker 로그의 FED instruction/transfer를 함께 확인한다.
- **의사결정 근거**: 논리 FunctionOp를 물리 consumer로 잘못 분류한 planner lowering 규칙을 수정했다. 후보 축소, runtime fallback, TRead/TWrite 완화는 하지 않았다.
- **적용 원칙/제약**: runtime fallback 금지, candidate-space 임의 축소 금지, 비용/메모리/boundary 측정 선행.

## 2. WAN-light ALS에서 비용 기반 DP/MinST가 FED WDivMM을 과소 선택함

- **상태**: 해결
- **환경/조건**: Docker `run_LAN_docker.sh`, WAN-light, ALS, worker=4, commit `abca6f91d43cf5c575cb88eeef696dedecc446f2`
- **재현 절차**: 기존 campaign의 ALS worker=3/4 네 planner warm 로그와 fedplan explain을 비교한다.
- **관측 증상**: worker=4에서 FedAll `82.459s`, Heuristic `83.338s`, DP `87.297s`, MinST `91.768s`이다. DP/MinST는 동일 plan family(`fed_wdivmm=20`, local `wdivmm=152`)이고 FedAll/Heuristic은 동일 plan family(`fed_wdivmm=72`, local `wdivmm=100`)이다. 후자는 transfer가 더 많지만 local WDivMM 시간이 약 18초 감소해 전체 실행시간이 짧다.
- **원인 분석**: ALS inner-CG의 정적 HOP은 `(W * (U %*% t(V))) %*% V` 또는 `t(U) %*% (W * (U %*% t(S)))` 형태이고, loop-carried factor의 한 축이 정적 분석 시 미확정이다. runtime recompile은 이를 WDivMM으로 fuse하지만 기존 비용 모델은 (1) pre-rewrite AggBinary의 낮은 generic compute cost를 사용하고, (2) 실제로 materialize되지 않는 `50000x2100` outer-product를 FED consumer에 업로드한다고 계산했다. MinST worker=4에서는 phantom upload가 각 방향에 약 `653409.5ms`로 가중돼 globally useful FED chain을 압도했다. 또한 partition-preserving Binary와 WDivMM worker compute를 serial/unscaled로 보던 기존 보정은 coordinator aggregation과 중복 계상했다.
- **해결 요약**: 공통 `PlacementCostSemantics`가 immutable compiled-edge/shape facts로 두 latent WDivMM 패턴을 보수적으로 인식한다. CP와 FED 모두에 rank-aware runtime-kernel floor를 적용하고, fuse되어 사라지는 outer matrix upload는 실제로 추가 전송되는 작은 factor broadcast로 교체했다. DP와 MinST가 같은 occurrence-exact cost semantics를 사용한다. 동시에 partitioned Binary/WDivMM compute는 worker 수로 scaling하고, partial-result GET/aggregation과 input preparation은 별도 stage cost로 유지했다. 어떤 legal candidate도 닫지 않았다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java`, `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014AlsPartitionedComputeCostRedTest.java`, `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**: WAN-light 비용 상수를 고정한 `CampaignBG014AlsPartitionedComputeCostRedTest`에서 MinST가 두 `50000x2100` inner elementwise stage의 FED alternative를 선택한다. DP에서는 FED alternative가 공통 candidate space에 남고 선택 결과가 legal alternative임을 검증했다. partitioned-vs-broadcast Binary, WDivMM partial aggregation, rank floor 단위 테스트 3개도 통과했다. 같은 immutable Docker stage에서 worker=4를 최소 재검증한 결과 MinST는 `91.768s -> 78.862s`, DP는 `87.297s -> 79.567s`로 개선됐다. 두 planner 모두 dynamic `fed_wdivmm`가 `20 -> 72`, `fed_fed_fout`이 `40 -> 144`로 바뀌어 의도한 FED WDivMM plan family를 실행했다. MinST는 DP보다 `0.705s` 빠르며, 두 셀 모두 semantic oracle, runtime scan, cold/warm plan equality, fresh network evidence, zero-restart/zero-residual-resource 검증을 통과했다. 검증 row는 각각 `/home/mchoi/g014-wan-light-affected-abca6f9-20260806-v1/cells/als-minst-w4/validated-row.json`, `/home/mchoi/g014-wan-light-affected-abca6f9-20260806-v1/cells/als-dp-w4/validated-row.json`이다.
- **잔여 이슈**: 구조적 결함 검증에는 없음. DP의 local/non-global recurrence 한계는 허용된 차이이며, 다른 worker 수의 이전 commit 측정과 새 worker=4 측정을 단일 fresh scaling curve로 간주하지 않는다.
- **잠재 회귀 위험**: 미확정 차원을 허용한 latent rewrite 인식이 다른 matmul을 오탐할 수 있다. exact transpose identity, 알려진 모든 shape 일치, unique parent, CP/LOUT-only outer intermediate, single-column-block rank라는 조건으로 제한했으며 ALS 회귀와 전체 CFG/rewire targeted suite로 감지한다.
- **의사결정 근거**: candidate-space를 인위적으로 닫지 않고 compute/size/boundary 측정 오류를 수정했으며 DP/MinST 공유 의미론을 유지했다.
- **적용 원칙/제약**: opcode guard 금지, compute/size/boundary cost 체크리스트 선행, DP/MinST cost parity 유지.

## 3. WAN-light 근소 차이를 planner 결함으로 오판할 위험

- **상태**: 해결
- **환경/조건**: 기존 WAN-light 성공 111개 cell의 fedplan/explain/runtime 통합 감사
- **재현 절차**: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/wan_light_audit_2026-08-06/audit.json`과 `blocks.csv`를 확인한다.
- **관측 증상**: KMeans worker=4, PCA worker=4, LM worker=3, ALS worker=2는 네 planner 실행시간 차이가 5% 이내다.
- **원인 분석**: LM은 동일 plan fingerprint이며 시간 차이는 측정 노이즈다. PCA는 두 plan family가 작은 transpose/FOUT 한두 개만 다르고 전체 시간은 local eigen이 지배한다. KMeans도 추가 FED transpose/FOUT 정도의 작은 구조 차이와 heavy hitter가 일치한다. ALS worker=2는 두 WDivMM plan family의 절감과 transfer 비용이 거의 상쇄된다.
- **해결 요약**: 근소 차이만을 이유로 plan을 강제하거나 candidate를 닫지 않는다. 구조적 설명이 가능한 near-tie는 그대로 유지하고 StepLM/ALS의 물질적 원인만 수정 대상으로 삼는다.
- **수정 파일**: 없음
- **검증**: 28개 workload×worker block 중 완전한 27개를 비교해 strict ordering 9/27, 5% tolerance ordering 20/27, material inversion 7개, all-planner near-tie 4개를 분류했다. 새 commit의 검증 완료 3개 셀을 기존 authenticated snapshot에 교체한 3행×7열 audit overlay는 `/home/mchoi/g014-wan-light-affected-abca6f9-20260806-v1/plots/runtime_grid_3x7_overlay_20260806T112244Z.png`이며 모든 y축은 0에서 시작한다. provenance/해시는 같은 basename의 `.json`에 기록했다.
- **잔여 이슈**: 영향 셀 3개의 최소 Docker 검증은 완료됐다. 구조 검증을 위해 112개 전체를 재실행하지 않는다. 논문용 동일-revision scaling 그래프가 필요할 때만 영향받은 planner/workload의 나머지 worker 수를 새 commit으로 측정하며, 현재 overlay는 mixed-revision audit임을 명시한다.
- **잠재 회귀 위험**: 단일 실행 노이즈를 최적화 대상으로 삼으면 graph-tuning이 발생한다. plan fingerprint와 heavy hitter가 동일한 경우 수정하지 않는 규칙으로 감지한다.
- **의사결정 근거**: runtime wall time ordering은 경험적 평가 항목이며 동일 plan의 5% 이내 차이는 correctness 실패가 아니다.
- **적용 원칙/제약**: 동일 Docker 조건, warm execution 우선, structural cause 없이 수정 금지.

## 4. Loop-carried transient의 CFG 후보가 첫 pass 이후에만 드러남

- **상태**: 해결
- **환경/조건**: ALS nested loop/function HOP, common placement graph, DP exact rewire
- **재현 절차**: `CampaignBG014AlsPartitionedComputeCostRedTest`, `NeutralPlacementGraphExactCfgIdentityTest`, `NeutralPlacementGraphCfgCoreTest`, `CampaignBG014RewireOccurrenceSnapshotRedTest`, `CampaignBG011DpTransientWriteExactOwnerRedTest`를 실행한다.
- **관측 증상**: ALS의 inner-CG `TRead(W)` 중 일부는 초기 CFG reaching-definition replay에서 source TWrite의 FED/FOUT 대안이 아직 닫혀 있어 exact logical transient fact를 얻지 못했다. 후속 physical dependency closure가 source 후보를 확장해도 transient replay를 다시 하지 않으므로 DP가 오래된 hop-id rewire table로 fallback할 수 있었다.
- **원인 분석**: transient replay와 post-CFG physical candidate closure가 단방향 한 번씩만 실행됐다. compiler-generated loop pass-through `TWrite(v) <- TRead(v)`가 phi의 추가 reaching definition으로 나타나는 경우도 실제 update와 구분하지 못했다.
- **해결 요약**: 두 단계를 monotone fixed-point로 반복하고, exact same-block/path/variable/shape의 identity loop backedge만 pass-through로 분류한다. 생성된 `LogicalTransientInputFact`를 DP exact source-child 수집이 우선 사용하며, legacy rewire는 exact fact가 없을 때만 fallback한다. arithmetic update, rename, shape 변화, cross-context, 다중 external seed는 replay하지 않는다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
- **검증**: exact CFG identity 9개, CFG core 6개, rewire occurrence 4개, transient exact-owner 2개 테스트가 모두 통과했다. ALS regression fixture의 모든 inner-loop `TRead(W)`가 unique logical TWrite source fact를 갖는다.
- **잔여 이슈**: 없음. 전체 Docker 런타임 검증은 이 변경만의 별도 full sweep가 아니라 영향받은 ALS 셀에서 함께 수행한다.
- **잠재 회귀 위험**: 실제 loop update를 identity로 오인하면 stale placement를 전달할 수 있다. exact HOP identity 입력과 동일 control path를 요구하고, 비-identity/ambiguous CFG 테스트로 감지한다.
- **의사결정 근거**: TRead/TWrite 제약을 완화하지 않고 공통 CFG authority와 DP exact rewire를 일치시켰다.
- **적용 원칙/제약**: `<CP,LOUT>`/`<FED,FOUT>` transient invariant, runtime fallback 금지, planner exact occurrence authority.

## 5. LogReg DP worker=1의 오래된 결과가 현재 소스의 실행 계획으로 오인됨

- **상태**: 해결
- **환경/조건**: Docker `run_LAN_docker.sh`, WAN-light, DP, worker=1, LogReg. 비교 대상은 predecessor cell `065-82c324e4aa6b`와 current immutable stage `dcc70c65daaeef8bd9d740a6a02df41ff504cd38ef37f223fa32bee8bc0c4d7e`이다.
- **재현 절차**: predecessor raw log `/home/mchoi/g014-one-pass-results-f9a307b-a32b188-20260805-v1/cells/065-82c324e4aa6b/phases/cell-1/warm-fresh-coordinator-jvm/raw_coordinator.log`와 current cold raw log `/home/mchoi/g014-wan-light-affected-abca6f9-20260806-v1/cells/logreg-dp-w1/phases/cell-1/cold-docker-e2e/raw_coordinator.log`를 비교한다. current 검증 row는 `/home/mchoi/g014-wan-light-affected-abca6f9-20260806-v1/cells/logreg-dp-w1/validated-row.json`이다.
- **관측 증상**: predecessor는 `CP uarsqk+ X`를 선택해 warm `63.306s`, `uarsqk+` 단독 `54.878s`였다. 이 점을 current 결과로 읽으면 DP가 initial federated formal `X`를 무료 local materialization으로 잘못 취급하는 것처럼 보인다.
- **원인 분석**: 해당 63초 점은 이전 source revision의 quarantined predecessor artifact였다. current revision의 fresh Docker log는 `FED uarsqk+ X ... FOUT`이며 cold/warm execution은 각각 `18.352s`/`16.045s`, heavy `fed_uarsqk+`는 `0.249s`이다. 즉 현재 DP placement bug가 아니라 서로 다른 revision 결과를 섞은 provenance 오류였다.
- **해결 요약**: 정확한 LogReg 차원(`50000x2100`)과 `multiLogReg` 호출을 사용하는 planner integration regression을 추가해 `rowSums(X^2)`의 selected state가 반드시 `<FED,FOUT>`임을 고정했다. predecessor 점은 current 그래프/정렬 근거에서 제외한다. runtime fallback이나 candidate 변경은 없다.
- **수정 파일**: `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**: `testDpLogRegKeepsInitialFederatedFormalForRowSumSquares`가 `<FED,FOUT>`을 확인하며 통과했다. current immutable Docker cell의 fingerprint는 `+:1;<:1;ba+*:5;contains:1;fedinit:2;replace:1;uamin:1;uarsqk+:1`이고 semantic/runtime/network/lifecycle validator를 모두 통과했다.
- **잔여 이슈**: placement/execution correctness에는 없음. 다만 fresh current Docker cold lifecycle에서 별도의 DP planner compile-time 병목이 발견돼 이 문서의 다음 이슈로 추적한다.
- **잠재 회귀 위험**: 이후 mixed-revision overlay가 predecessor 점을 다시 current 점으로 채택할 수 있다. 모든 점의 stage id/systemds commit과 raw runtime-plan fingerprint를 함께 확인한다.
- **의사결정 근거**: planner 동작을 추측으로 수정하지 않고 immutable Docker provenance와 exact selected state로 현재 동작을 판정했다.
- **적용 원칙/제약**: 동일 Docker 조건, stale artifact 격리, planner가 계획하고 runtime은 그대로 실행.

## 6. LogReg DP decision-map 점수 계산의 반복 전역 스캔

- **상태**: 해결
- **환경/조건**: DP planner, LogReg `50000x2100` integration fixture 및 StepLM decision-map closure fixture. 실행 성능과 별개인 compile/LopsBuild 구간이다. Docker 검증 commit은 `f96adf02d9016efabef28729d3c4300cd29cf425`, immutable stage는 `de6239e364c48257402405bc73603dfbc17af068eb58af8995af215934c18fce`이다.
- **재현 절차**: `mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpLogRegKeepsInitialFederatedFormalForRowSumSquares test`를 실행한다. Docker 기준 로그는 `/home/mchoi/g014-wan-light-affected-abca6f9-20260806-v1/cells/logreg-dp-w1/phases/cell-1/cold-docker-e2e/raw_coordinator.log`이다.
- **관측 증상**: 수정 전 current Docker cold는 total `210.540s`, compilation `192.187s`, FedPlanner `189.656s`, execution `18.352s`였다. 같은 selected-state integration test도 최초 `288.865s`였다. stack sample은 `cfgDefinitionSourcesInCanonicalOrder`의 전체 node scan, `DecisionMapScoreKey.equals`의 충돌 map 비교, 동일 score 안의 `collectConflictsSingleBFS` 반복, `assertOwnedOccurrence`의 전체 occurrence scan을 가리켰다.
- **원인 분석**: immutable CFG definition 관계를 decision 후보마다 다시 `O(V)`로 역검색했다. 또한 standard `Map.hashCode()`가 `Long.hashCode() ^ FederatedOutput.hashCode()`의 XOR 상쇄 때문에 다른 one-entry decision map에 체계적으로 같은 hash를 만들었고, score/simulation cache bucket이 긴 equality chain이 됐다. 각 decision-map score는 동일 output map으로 conflict forest를 세 번 재구성했으며 occurrence ownership도 매 호출 선형 탐색이었다.
- **해결 요약**: `PlacementAnalysis` 생성 시 CFG definition source를 exact identity별 immutable index로 만든다. decision/simulation cache는 equality 계약을 유지하면서 entry를 64-bit mixing한 order-independent hash를 사용한다. 한 score 안에서는 conflict forest를 한 번만 만들고 세 penalty 계산이 읽기 전용으로 공유한다. memo 소유 occurrence는 identity set으로 한 번 인덱싱한다. candidate, 비용 항, 선택 규칙은 변경하지 않았다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpMemoTable.java`, `src/test/java/org/apache/sysds/test/component/federated/placement/core/NeutralPlacementGraphExactCfgIdentityTest.java`, `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**: CFG index identity regression과 standard-map-hash collision regression은 각각 RED를 확인한 뒤 GREEN이다. LogReg selected-state test는 최종 변경에서 `101.871s`로 통과해 최초 `288.865s` 대비 64.7%, 직전 `177.496s` 대비 42.6% 감소했다. `CampaignBDpMemoOwnerContractTest`, `CampaignBG014DpStepLmDecisionMapClosureRedTest`, `NeutralPlacementGraphExactCfgIdentityTest` 묶음도 errors/failures 0이다. 새 immutable Docker canary에서 cold/warm 모두 `FED uarsqk+ ... FOUT`을 유지했다. cold FedPlanner는 `189.656s -> 58.933s`(68.9% 감소), total compilation은 `192.187s -> 61.666s`, validated full lifecycle은 `500.679s -> 252.542s`로 줄었다. cold/warm execution은 `18.332s`/`16.177s`이며 이전 `18.352s`/`16.045s`와 동등하다. runtime plan SHA-256 `95c4241f7792c243ac4b8408583f1445041e5de809b61a004637b1e811cee0f3`, FED fingerprint와 instruction fingerprint가 수정 전후 정확히 동일하고 semantic/runtime/network/lifecycle validator 및 zero-residue 검증을 통과했다. 검증 row는 `/home/mchoi/g014-logreg-dp-f96adf0-docker-20260806-v1/cells/logreg-dp-w1/validated-row.json`이다.
- **잔여 이슈**: 제거한 병목에는 없음. DP의 합법 decision-map 탐색 자체는 workload 크기에 따라 여전히 수십 초가 걸릴 수 있으며, 이는 선택 의미론을 바꾸지 않는 별도 성능 개선 대상으로만 다룬다.
- **잠재 회귀 위험**: mutable analysis graph에 index가 stale할 수 있으나 `PlacementAnalysis`/neutral graph는 생성 후 immutable contract다. shared conflict map을 score 항이 변경하면 상호 오염될 수 있으므로 세 소비 함수의 read-only 계약과 decision-map regression으로 감지한다. cache hash는 equality가 아니라 bucket 분산만 바꾸므로 collision이 남아도 correctness는 유지된다.
- **의사결정 근거**: legal plan space나 cost semantics를 바꾸지 않고 동일 immutable planner facts의 반복 계산만 제거했다.
- **적용 원칙/제약**: candidate-space 임의 축소 금지, runtime fallback 금지, 비용 의미론/DP local recurrence 철학 유지.
