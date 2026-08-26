# Session Issues (2026-08-26)

## 1. KMeans transient assignment payload was priced as a dense matrix

- **상태**: 해결; 공통 비용모델, 회귀 테스트, targeted Docker runtime 검증 완료
- **적용 원칙**: runtime-supported placement를 닫지 않고 DP/Exact가 공유하는 occurrence-exact 비용 fact를 수정한다.
- **환경/조건**: P2P2D KMeans, workers=1, WAN-light/WAN-mid, `COMPILE_COST_BASED`와 `COMPILE_EXACT`.
- **재현 절차**:
  - `CampaignBG014ExactKMeansWanRepeatedUploadRedTest#loopAssignmentPayloadUsesExpectedCardinalityAcrossTransientDefinitions`
  - `PlacementCostSemanticsSparseAssignmentTest`
  - 기존 whole-program cost probe에서 loop line 155의 `t(P)`가 50x50,000 dense output인 20,000,152 bytes로 계산됐다.
- **관측 증상**: Exact objective가 DP보다 작아도 실제 runtime에서 Exact가 더 느린 KMeans 셀이 남았다. 비용 breakdown에서 `P = D <= minD`, `P = P / rowSums(P)`, `t(P)`의 sparse assignment payload가 transient write/read 경계를 지나면 인식되지 않았고, `t(P) %*% X`의 local input preparation이 약 8.54초 과대평가됐다.
- **원인 분석**: 기존 `FederatedCostModel`의 HOP-local semantic estimator는 direct `D <= rowMins(D)`만 인식했다. 실제 whole-program graph에서 `minD`와 `P`가 transient write/read를 거치면 source HOP object의 direct child relation이 끊겨 generic dense estimate가 우선됐다.
- **해결 요약**:
  - shared `PlacementAnalysis`의 exact compiled-input edge, logical transient input, CFG reaching-definition, value-version signature를 따라 row-min indicator와 row-sum normalization을 occurrence-exact하게 인식한다.
  - 정의가 하나로 결정되지 않는 branch/loop merge에서는 추측하지 않고 일반 estimate로 fail closed한다.
  - transpose는 dimensions만 뒤집고 expected nnz를 유지한다.
  - 관계는 cost-surface 생성당 한 번 색인하고 occurrence별 결과를 memoize하여 반복 whole-program scan을 피한다.
  - HOP에 concrete NNZ가 있으면 이를 우선하며 expected-cardinality fallback은 사용하지 않는다.
  - in-memory estimate와 serialized wire estimate를 `ExactPhysicalCostModel`의 공통 physical surface에 적용하므로 DP와 Exact가 동일한 값을 소비한다.
  - 동일한 sparse assignment가 DML function actual/formal 경계를 통과할 때도 source occurrence의 expected wire bytes를 유지한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CampaignBG014ExactKMeansWanRepeatedUploadRedTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactSparseFunctionBoundaryCostTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemanticsSparseAssignmentTest.java`
- **검증**:
  - line 148 `P` serialized estimate: 800,013 bytes (50,000x50, nnz=50,000).
  - line 155 `t(P)` serialized estimate: 600,213 bytes (50x50,000, nnz=50,000).
  - current-worktree cost probe: WAN-light w1 Exact 31,958.179ms <= DP 32,074.126ms; WAN-mid w1 Exact 39,825.218ms <= DP 39,835.510ms.
  - workload/line/Hop-ID/worker-count 기반 특수 처리는 없다.
  - 새 immutable stage에서 KMeans WAN-light/w1과 WAN-mid/w1의 DP/Exact runtime-plan SHA-256가 각각 완전히 일치했고 `fed_refed=0`이었다.
  - WAN-mid runtime은 DP 66.650초, Exact 66.772초로 정렬됐다. FedAll 234.002초와 Heuristic 291.417초는 각각 91개의 반복 REFED를 유지했다.
- **잔여 이슈**: static estimate는 tie가 없는 보통 경우처럼 row당 한 assignment를 사용한다. 실제 데이터에 동일 minimum tie가 많으면 nnz를 과소평가할 수 있다.
- **잠재 회귀 위험**: ambiguous transient reaching definitions를 잘못 합치면 unrelated comparison을 sparse로 오인할 수 있다. exact occurrence/value-version relation과 ambiguous-definition negative regression으로 감지한다.
- **의사결정 근거**: candidate exclusion이나 Exact 전용 보정이 아니라 shared physical payload estimate를 수정했다.

## 2. Repeated FED instruction latency and coordinator control were modeled as substitutes

- **상태**: 해결; 공통 비용모델, 회귀 테스트, L2SVM targeted Docker runtime 검증 완료
- **적용 원칙**: runtime request path를 비용 surface에 반영하고 legal candidate는 유지한다.
- **환경/조건**: P2P2D L2SVM, LAN, workers=2--4, 30x20 nested-loop path.
- **재현 절차**:
  - `CampaignBG014ExactL2SvmInternalEmissionCostRedTest`
  - 이전 full ledger에서 Exact는 workers=2에서 `fed_refed=1200`, DP는 0이었고 warm runtime은 14.779초 대 10.156초였다.
- **관측 증상**: Exact가 nested loop 내부의 line-110 `Xd` relocation을 선택해 1,200개의 repeated REFED instruction을 만들었다. 기존 cost profile에서 `SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS=0`이면 network latency만, 양수이면 coordinator control만 부담하여 두 독립 stage 중 하나가 사라졌다.
- **원인 분석**: 하나의 non-metadata FED instruction은 (1) coordinator/runtime dispatch 처리와 (2) 병렬 worker request batch의 network round trip을 모두 소유한다. 기존 helper는 calibrated control이 있으면 latency top-up을 0으로 반환해 두 항을 같은 stage로 간주했다.
- **해결 요약**:
  - non-metadata FED instruction cost를 `execution frequency x (network latency + calibrated coordinator control)`로 정의한다.
  - worker 요청은 futures로 병렬 제출되므로 두 fixed stage 모두 worker count로 곱하지 않는다.
  - mapping-preserving transpose는 metadata-only exemption을 유지한다.
  - campaign은 network latency와 별개의 coordinator/runtime 항으로 `SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS=1`을 명시한다. 이 값은 독립 microbenchmark 결과가 아니라 기존 campaign에 사용하는 conservative calibration이다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModelFixedInstructionStageTest.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedCostModelFallbackTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CampaignBG014ExactL2SvmInternalEmissionCostRedTest.java`
- **검증**:
  - LAN w2/w3 current-worktree probes에서 Exact emitted `Xd` relocations=0.
  - Exact는 line-110 `Xd`를 `FED/FOUT/ROW`로 유지해 relocation choice가 non-emitted이고, DP는 `CP/LOUT`를 선택한다.
  - explicit-value unit test가 static environment 초기화와 무관하게 `7 x (1ms latency + 1ms control) = 14ms`를 검증한다.
  - `FederatedCostModelFallbackTest`가 arithmetic/indexing/control-dominated FED instruction의 call-site 조합을 검증한다.
  - 새 immutable stage의 L2SVM LAN w2/w3/w4에서 DP와 Exact는 worker 수별로 동일한 runtime-plan SHA-256와 instruction fingerprint를 가졌고 모두 `fed_refed=0`이었다.
  - runtime은 w2 DP/Exact 10.024/9.766초, w3 9.462/9.622초, w4 9.246/9.284초였다. 동일 physical plan에서 남는 작은 단일표본 차이는 selector 차이로 해석하지 않는다.
- **잔여 이슈**: 1ms coordinator control은 별도 microbenchmark가 아니라 기존 campaign의 conservative fixed calibration이다. 이번 targeted runtime은 repeated `fed_refed` 제거와 wall-clock 정렬을 확인했지만, 절대비용 예측 오차를 주장하려면 coordinator-only microbenchmark가 별도로 필요하다.
- **잠재 회귀 위험**: latency가 다른 helper에서 이미 중복 부과되면 remote-heavy plan을 과도하게 억제할 수 있다. instruction-level unit arithmetic, objective breakdown, emitted instruction fingerprint로 감지한다.
- **의사결정 근거**: L2SVM opcode/state를 닫지 않고 공통 frequency-weighted runtime cost를 수정했다.

## 3. DP compile-time reversal was a five-sample measurement artifact

- **상태**: 해결; 추가 full compile rerun 불필요
- **적용 원칙**: 동일 artifact의 반복 증거가 있는 경우 sampling noise를 source bug로 오진하지 않는다.
- **환경/조건**: WAN-mid LM workers=2, DP/Exact compile-only.
- **관측 증상**: 5 measured repetitions의 median에서 DP 0.373544초, Exact 0.355285초로 DP가 18.259ms 느리게 보였다.
- **원인 분석**: 같은 commit/tree/JAR/stage에서 이미 수행한 1 warm-up + 19 measured preflight는 DP 0.307445초, Exact 0.403679초였고 DP가 19/19 pair에서 모두 빨랐다. source에는 contained exact block 제거와 one-shot ordered local block solve가 적용되어 있어 반복 global fixed-point 경로가 없다.
- **해결 요약**: 기존 20-observation authenticated preflight를 authoritative receipt로 유지한다. 소스 또는 planner domain을 더 축소하지 않는다.
- **수정 파일**: 없음.
- **검증**:
  - full 84-cell compile ledger에서 reversal은 이 한 셀뿐이었다.
  - repeated preflight에서 DP median은 Exact보다 23.84% 짧고 모든 paired observation에서 더 짧았다.
- **잔여 이슈**: sub-second planner time을 5개 표본만으로 비교하는 figure는 작은 inversion을 보일 수 있다.
- **잠재 회귀 위험**: future change가 fixed-point revisit를 다시 도입할 수 있다. local block/revisit counters와 19-measured LM preflight로 감지한다.
- **의사결정 근거**: 충분한 fresh-JVM 반복 receipt가 기존 single median보다 강한 근거다.

## 4. LAN planner alignment and shared privacy legality audit

- **상태**: 해결; source/plan/privacy audit와 targeted runtime control cells 완료
- **적용 원칙**: 같은 runtime 숫자만으로 같은 fedplan이라고 판단하지 않고 emitted-plan identity와 privacy-filtered domain을 함께 검증한다.
- **환경/조건**: LAN ALS, LM, KMeans 및 production `COMPILE_COST_BASED` privacy paths.
- **관측 증상**: LAN에서 여러 planner의 runtime이 가까워 하나의 잘못된 fedplan을 공유한다는 의심이 있었다. ALS/LM도 KMeans와 같은 cost bug 가능성이 제기됐다.
- **원인 분석**:
  - LM: DP/Exact는 같은 fast federated plan을 선택하지만 FedAll과 Heuristic의 plan fingerprint는 다르며, 특히 Heuristic의 local tail이 훨씬 느리다.
  - ALS: DP/Exact가 동일 plan/objective를 선택하는 셀이 있고, workers=3--4에서는 local computation이 지배해 policy planners와 wall-clock이 가까워진다.
  - KMeans: planner별 plan hash와 movement counts가 다르며 WAN의 transient sparse-payload 오판은 LAN의 모든 ALS/LM 셀을 설명하지 않는다.
  - 따라서 “네 planner 모두 같은 잘못된 plan”은 ledger/plan evidence와 일치하지 않는다. ALS/LM은 수정 셀이 아니라 targeted control cell로만 재검증한다.
- **해결 요약**:
  - runtime 재실행은 직접 영향을 받은 KMeans WAN w1과 L2SVM LAN w2--w4에 한정한다.
  - ALS/LM은 사용자 지적을 검증하기 위한 최소 LAN control cells만 포함하고 plan hash, FOUT/LOUT, relocation, FED instruction fingerprint를 함께 비교한다.
  - production DP는 canonical privacy-filtered `PlacementAnalysis`의 legal alternatives만 optimize/emit한다.
- **수정 파일**: privacy source 변경 없음; 기존 contract tests로 재인증.
- **검증**:
  - `FederatedPlannerFactoryContractTest`, `SharedPrivacyPlacementAnalysisContractTest`, `FederatedPlanLocalCostPrivacyConstraintTest` 포함 combined suite 통과.
  - post-change combined suite: 66 tests, 0 failures/errors.
  - production route `COMPILE_COST_BASED -> FederatedPlanLocalCost -> ExactPhysicalModel.build(canonical PlacementAnalysis)`를 확인했다.
  - LM LAN w2의 runtime은 FedAll 2.643초, Heuristic 19.705초, DP 2.416초, Exact 2.399초였다. DP/Exact는 같은 plan이지만 Heuristic의 plan SHA/fingerprint는 다르므로 “LAN에서 네 planner가 같은 잘못된 plan을 실행한다”는 가설은 기각된다.
  - ALS LAN w3에서 FedAll/Heuristic은 같은 plan, DP/Exact는 서로 같은 별도 plan을 선택했다. DP/Exact plan은 둘 다 `fed_wdivmm=172`, `fed_fout=0`이며 single-warm runtime 41.943/45.083초였다. 같은 instruction plan의 이 차이는 selector 선택 차이로 귀속할 수 없다.
- **잔여 이슈**: targeted runtime의 host noise는 같은 plan의 1회 wall-clock inversion을 만들 수 있다. plan/objective/fingerprint가 동일한 경우 이를 algorithmic inversion으로 주장하지 않는다.
- **잠재 회귀 위험**: selector가 shared privacy domain 밖의 equal-but-new candidate를 합성할 수 있다. legal-alternative identity membership assertion과 `ReasonCode.PRIVACY` exclusion assertion으로 감지한다.
- **의사결정 근거**: privacy는 selector 내부의 late filter가 아니라 shared pre-selector feasible-domain contract다.

## Targeted experiment contract

- 새 source commit/JAR/immutable stage를 사용한다. 이전 stage/result tree는 수정하지 않는다.
- 실행기는 오직 staged `run_LAN_docker.sh`이다.
- cost profile은 network profile의 latency와 별도로 `SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS=1`을 기록한다.
- **직접 영향 셀**: KMeans WAN-light w1, KMeans WAN-mid w1, L2SVM LAN w2/w3/w4; 각 셀에서 네 planner를 비교한다.
- **control 셀**: ALS LAN w3, LM LAN w2; 각 셀에서 네 planner를 비교해 기존 plan-alignment 판정을 재확인한다.
- canary는 KMeans WAN-mid w1 Exact/DP와 L2SVM LAN w2 Exact/DP이며, cost/plan receipt와 `fed_refed` fingerprint를 확인한 후 나머지 targeted schedule로 확장한다.
- compile time은 이미 더 강한 19-measured LM-w2 receipt가 있으므로 full rerun하지 않는다. 새 source artifact의 planner-only smoke만 별도 기록한다.

## Targeted experiment results

- **Source commit**: `39473c7bcf2d6195a030699e2d044d7a6779841f`
- **Immutable stage**: `188ab65286ca7795910daad107a5533108f4d0be9881580d06b95cfd7034051b`
- **Campaign**: `/home/mchoi/g014-targeted-cost-fix-39473c7bcf-20260826-v2`
- **완료 상태**: 영향/control 셀 28/28 성공, 셀당 1 attempt, failure 0, fallback 0, oracle/scan failure 0, coordinator/worker restart 0, teardown 뒤 잔존 resource 0.
- **정규화 기준**: runtime 비교의 baseline은 **FedAll = 1**이다.

| Cell | FedAll | Heuristic | DP | Exact | DP/Exact runtime plan |
|---|---:|---:|---:|---:|---|
| KMeans WAN-light w1 | 82.490s | 117.660s | 38.133s | 41.877s | same |
| KMeans WAN-mid w1 | 234.002s | 291.417s | 66.650s | 66.772s | same |
| LM LAN w2 | 2.643s | 19.705s | 2.416s | 2.399s | same |
| L2SVM LAN w2 | 13.764s | 21.708s | 10.024s | 9.766s | same |
| L2SVM LAN w3 | 12.714s | 14.863s | 9.462s | 9.622s | same |
| L2SVM LAN w4 | 12.594s | 11.655s | 9.246s | 9.284s | same |
| ALS LAN w3 | 45.613s | 42.252s | 41.943s | 45.083s | same |

| Cell | DP planner | Exact planner | DP reduction vs Exact |
|---|---:|---:|---:|
| KMeans WAN-light w1 | 1.039457s | 1.507255s | 31.04% |
| KMeans WAN-mid w1 | 0.986010s | 1.312553s | 24.88% |
| LM LAN w2 | 0.399775s | 0.494860s | 19.21% |
| L2SVM LAN w2 | 1.093100s | 1.442515s | 24.22% |
| L2SVM LAN w3 | 1.152746s | 1.337022s | 13.78% |
| L2SVM LAN w4 | 1.107824s | 1.368057s | 19.02% |
| ALS LAN w3 | 0.383609s | 0.473292s | 18.95% |

DP federated-planner phase는 이 targeted schedule의 7/7 group에서 Exact보다 짧았다. DP와 Exact는 7/7 group에서 동일 runtime-plan SHA-256를 생성했다. 따라서 같은 plan의 single-warm wall-clock에서 DP가 근소하게 짧게 나온 KMeans WAN-light/ALS 표본은 global-objective 역전의 증거가 아니며, 선택된 physical plan 차이로 설명해서는 안 된다.

생성 artifact:

- 결과/plan/cost CSV: `/home/mchoi/g014-targeted-cost-fix-39473c7bcf-20260826-v2/plots/targeted_affected_cells_metrics.csv`
- raw runtime: `.../plots/targeted_affected_cells_runtime_raw.{png,svg}`
- FedAll-normalized runtime: `.../plots/targeted_affected_cells_runtime_fedall_normalized.{png,svg}`
- planner phase: `.../plots/targeted_affected_cells_planner_time.{png,svg}`
- authenticated summary receipt: `.../plots/targeted_affected_cells_summary_receipt.json`

## 5. Reusable FOUT-to-local materialization was priced as a conservative explicit collection

- **상태**: 해결; 공통 비용모델, plan-emission 회귀, 새 immutable stage의 targeted Docker runtime 검증 완료
- **적용 원칙**: legal CP/FED alternatives를 닫지 않고, planner가 실제로 emit하는 reusable materialization runtime path를 shared physical cost surface에 반영한다.
- **환경/조건**: P2P2D L2SVM, LAN, workers=2 canary, 30x20 nested loop, `COMPILE_COST_BASED`와 `COMPILE_EXACT`.
- **재현 절차**:
  - 이전 immutable stage의 `L2SVM/LAN/w2` Exact/DP warm Docker logs 및 statistics 비교.
  - `CampaignBG014ExactL2SvmInternalEmissionCostRedTest`에서 source line 106/110의 selected state, local materialization, emitted runtime program을 함께 검사한다.
- **관측 증상**:
  - 첫 번째 fixed-stage 수정 후 Exact 12.173초, DP 9.984초로 21.9% 차이가 남았다.
  - Exact는 nested-loop마다 `fed_>` 600회, `fed_*` 추가 600회, `fed_uak+` 추가 600회를 실행했다. DP는 한 번 선택된 local boundary를 iteration별 `prefetch` 600회로 materialize한 뒤 CP `>`와 fused CP `tak+*`를 사용했다.
  - Exact의 worker PUT은 732,664,688 bytes, DP는 492,482,288 bytes였고, DP의 600회 GET materialization 누적 acquire time은 1.402초였다.
  - 수정 전 shared objective는 Exact 2,476.380ms, DP 4,481.087ms로 실제 순서를 반대로 예측했다.
- **원인 분석**:
  - compiled FOUT-to-CP boundary는 planner가 한 producer materialization action을 선택하고 compatible local consumers가 그 값을 재사용하도록 emit된다.
  - 그러나 cost surface는 이를 generic `computeDownloadNetworkCost(bytes, type, workers)`로 계산했다. 이 helper는 독립적인 explicit whole collection을 위한 conservative W2C serialization path를 사용한다.
  - 400,304-byte vector와 workers=2에서 모델은 약 28ms/occurrence를 부과했지만, 실제 reusable GET_VAR batch는 약 2.34ms/occurrence였다. 그 결과 DP와 Exact가 공유하는 objective가 local materialization을 과도하게 억제했다.
- **해결 요약**:
  - `computeReusableMaterializationDownloadCost`를 추가해 one parallel GET_VAR response의 per-worker critical payload와 one latency/control stage만 가격화한다.
  - grouped compiled DOWNLOAD, FOUT source leg of REFED, native-local FOUT input 준비 중 실제 reusable materialization을 emit하는 세 경로에만 적용한다.
  - forwarded function boundary와 generic final collection은 기존 conservative download model을 유지한다.
  - plan regression은 Exact가 nested-loop 내부에서 CP prefetch, CP comparison, fused CP ternary aggregate를 선택하고 repeated FED comparison을 제거하는지 검증한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedCostModelFallbackTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CampaignBG014ExactL2SvmInternalEmissionCostRedTest.java`
- **검증**:
  - reusable materialization arithmetic regression은 400,304 bytes, workers=2에서 per-worker critical payload와 fixed batch stage의 합을 exact하게 검증한다.
  - L2SVM emission regression은 inner loop의 `CP prefetch`, `CP >`, `CP tak+*`, absence of `FED >`, source-line-106 materialization action 하나를 검증한다.
  - cost/privacy/plan combined suite: 66 tests, 0 failures/errors/skips.
  - `mvn -q -DskipTests package` 통과.
  - 새 stage에서 L2SVM LAN w2--w4의 DP/Exact plan hash와 instruction fingerprint가 worker 수별로 일치했고, repeated `fed_refed`가 0으로 제거됐다.
  - 기존 w2 inversion(Exact 12.173초 대 DP 9.984초)은 새 stage에서 9.766초 대 10.024초로 제거됐다.
- **잔여 이슈**: cell당 warm 표본이 하나이므로 동일 runtime plan 사이의 수 퍼센트 wall-clock 차이를 optimizer 품질 차이로 해석하지 않는다. 분산 자체를 주장하려면 별도 반복실험이 필요하다.
- **잠재 회귀 위험**: generic explicit collection까지 reusable GET_VAR cost로 낮추면 final output collection을 과소평가할 수 있다. helper의 제한된 call sites와 explicit-collection unit tests로 감지한다.
- **의사결정 근거**: workload/opcode guard나 candidate exclusion 없이 실제 emitted boundary primitive와 cost primitive를 일치시켰다.
