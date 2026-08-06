# Session issues — 2026-08-06

## 1. WAN-light MinST StepLM이 함수 재호출마다 동일 `y`를 REFED함

- **상태**: 코드 수정 및 targeted runtime test 해결, WAN-light Docker 재검증 진행중
- **환경/조건**: Docker `run_LAN_docker.sh`, WAN-light, MinST, worker=2/3/4, commit `2aa0f5666ffcfb1d96bd1abcd5aeae8f3c3b9500`
- **재현 절차**: 기존 campaign 결과의 `warm-fresh-coordinator-jvm/raw_coordinator.log`를 확인한다. 대표 로그는 `/home/mchoi/g014-one-pass-continuation-2aa0f56-26882dd-20260806-v1/cells/098-637a87a8cad4/phases/cell-1/warm-fresh-coordinator-jvm/raw_coordinator.log`이다.
- **관측 증상**: worker=3에서 MinST 실행시간은 `154.592s`이고 `fed_fed_refed`가 `45.024s / 4201회`이다. 같은 실행에서 HOP DAG SB 재컴파일도 정확히 `4201회`, `.builtinNS::linear_regression`과 `.builtinNS::m_lm`은 각각 `2100회`이며, Federated I/O는 `Read/Put/Get=6/6300/12604`이다. 각 worker의 통계는 cold/warm phase마다 FED `tsmm` 1회와 `rmvar` 2101회뿐이고, 동적 `ba+*`나 그 밖의 FED compute는 없다. DP worker=3은 `15.221s`이며 이 동적 REFED가 없다.
- **원인 분석**: DML `FunctionOp`는 coordinator의 `FunctionCallCP`로 actual을 formal에 전달하는 논리 경계인데, REFED consumer 탐색이 이 forwarding placeholder를 물리 FED consumer로 취급했다. 그 결과 함수 내부의 실제 실행 HOP들이 모두 CP여도, 동적 재컴파일 때마다 local actual `y`를 worker에 업로드했다. 기존 로그의 `4201 recompile = 4201 REFED`와 worker에서 실질 FED compute 없이 `rmvar`만 반복된 현상이 이 잘못된 consumer 판정과 일치한다.
- **해결 요약**: `FederatedRefedPolicy`의 FED 입력 보장 및 physical FED consumer 검색에서 DML `FunctionOp` 논리 경계를 제외했다. relocation은 함수 내부에서 선택된 실제 executable HOP가 소유하며, runtime fallback이나 계획 후 보정은 추가하지 않았다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`, `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014MinStStepLmRuntimeRecompileRefedRedTest.java`
- **검증**: `CampaignBG014MinStStepLmRuntimeRecompileRefedRedTest#minStRecompileDoesNotUploadLocalYWithoutPhysicalFedConsumer` 통과. 2회 함수 재컴파일을 포함한 실제 local federated worker 실행에서 `fed_fed_refed <= 1`, Federated I/O `Read/Put/Get=2/0/2`, FED execute `0`을 확인했다. 관련 targeted test 묶음 전체도 errors/failures 0이다.
- **잔여 이슈**: 실제 P2P2D WAN-light StepLM MinST worker=3 Docker cell에서 `fed_fed_refed`가 4201회에서 제거되고 runtime이 정상화되는지 확인해야 한다.
- **잠재 회귀 위험**: 함수 내부에 실제 FED consumer가 있는 경우 actual/formal 경계에서 필요한 relocation이 누락될 수 있다. 함수 내부 exact placement가 relocation을 소유한다는 기존 function-boundary 테스트와 WAN-light Docker 로그의 FED instruction/transfer를 함께 확인한다.
- **의사결정 근거**: 논리 FunctionOp를 물리 consumer로 잘못 분류한 planner lowering 규칙을 수정했다. 후보 축소, runtime fallback, TRead/TWrite 완화는 하지 않았다.
- **적용 원칙/제약**: runtime fallback 금지, candidate-space 임의 축소 금지, 비용/메모리/boundary 측정 선행.

## 2. WAN-light ALS에서 비용 기반 DP/MinST가 FED WDivMM을 과소 선택함

- **상태**: 비용 모델 및 회귀 테스트 해결, WAN-light Docker 재검증 진행중
- **환경/조건**: Docker `run_LAN_docker.sh`, WAN-light, ALS, worker=3/4, commit `2aa0f5666ffcfb1d96bd1abcd5aeae8f3c3b9500`
- **재현 절차**: 기존 campaign의 ALS worker=3/4 네 planner warm 로그와 fedplan explain을 비교한다.
- **관측 증상**: worker=4에서 FedAll `82.459s`, Heuristic `83.338s`, DP `87.297s`, MinST `91.768s`이다. DP/MinST는 동일 plan family(`fed_wdivmm=20`, local `wdivmm=152`)이고 FedAll/Heuristic은 동일 plan family(`fed_wdivmm=72`, local `wdivmm=100`)이다. 후자는 transfer가 더 많지만 local WDivMM 시간이 약 18초 감소해 전체 실행시간이 짧다.
- **원인 분석**: ALS inner-CG의 정적 HOP은 `(W * (U %*% t(V))) %*% V` 또는 `t(U) %*% (W * (U %*% t(S)))` 형태이고, loop-carried factor의 한 축이 정적 분석 시 미확정이다. runtime recompile은 이를 WDivMM으로 fuse하지만 기존 비용 모델은 (1) pre-rewrite AggBinary의 낮은 generic compute cost를 사용하고, (2) 실제로 materialize되지 않는 `50000x2100` outer-product를 FED consumer에 업로드한다고 계산했다. MinST worker=4에서는 phantom upload가 각 방향에 약 `653409.5ms`로 가중돼 globally useful FED chain을 압도했다. 또한 partition-preserving Binary와 WDivMM worker compute를 serial/unscaled로 보던 기존 보정은 coordinator aggregation과 중복 계상했다.
- **해결 요약**: 공통 `PlacementCostSemantics`가 immutable compiled-edge/shape facts로 두 latent WDivMM 패턴을 보수적으로 인식한다. CP와 FED 모두에 rank-aware runtime-kernel floor를 적용하고, fuse되어 사라지는 outer matrix upload는 실제로 추가 전송되는 작은 factor broadcast로 교체했다. DP와 MinST가 같은 occurrence-exact cost semantics를 사용한다. 동시에 partitioned Binary/WDivMM compute는 worker 수로 scaling하고, partial-result GET/aggregation과 input preparation은 별도 stage cost로 유지했다. 어떤 legal candidate도 닫지 않았다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java`, `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014AlsPartitionedComputeCostRedTest.java`, `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**: WAN-light 비용 상수를 고정한 `CampaignBG014AlsPartitionedComputeCostRedTest`에서 MinST가 두 `50000x2100` inner elementwise stage의 FED alternative를 선택한다. DP에서는 FED alternative가 공통 candidate space에 남고 선택 결과가 legal alternative임을 검증했다. DP는 loop-carried `W`의 FOUT 미래가치를 local recurrence에서 보존하지 못해 CP를 선택할 수 있으며 이는 사용자가 허용한 local/non-global 한계다. partitioned-vs-broadcast Binary, WDivMM partial aggregation, rank floor 단위 테스트 3개도 통과했다.
- **잔여 이슈**: ALS MinST worker=4 WAN-light Docker에서 dynamic `fed_wdivmm` 증가, local `wdivmm` 감소, runtime 성공을 확인해야 한다. 공유 비용 경로가 DP에도 영향을 주므로 DP worker=4도 같은 조건에서 최소 재검증한다.
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
- **검증**: 28개 workload×worker block 중 완전한 27개를 비교해 strict ordering 9/27, 5% tolerance ordering 20/27, material inversion 7개, all-planner near-tie 4개를 분류했다.
- **잔여 이슈**: 수정 후 영향받은 cell의 최소 Docker 검증이 끝나면 전체 112-cell 재실행 필요성을 다시 판단한다.
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
