# Session Issues (2026-08-23)

## 1. Exact selected a slower KMeans whole-program plan because native in-band results were priced as explicit collection

- **상태**: 수정 구현 및 단위 검증 완료, 새 artifact의 compile/runtime 검증 진행중
- **적용 원칙**: runtime-supported 후보를 닫지 않고 공통 DP/Exact 비용모델의 잘못된 boundary cost를 수정한다.
- **환경/조건**: source `a738893dc70b527afc4381d5d00951c03f1bb609` 기반, WAN-light,
  KMeans P2P2D, workers 2--4, current `COMPILE_COST_BASED`와 `COMPILE_EXACT`.
- **재현 절차**:
  - 결과: `/home/mchoi/g014-four-planner-completion-a738893dc7-20260822-v1/runtime/current-dp-exact-168-v2/rows.jsonl`
  - workers=2 warm log의 DP는 `prefetch` 46회/6.762초이고 total 30.140초이다. Exact는
    반복 loop의 elementwise/aggregate FED instruction을 유지하며 total 33.026초이다.
  - workers=3은 DP 23.510초/Exact 27.671초, workers=4는 DP 19.959초/Exact 24.607초이다.
- **관측 증상**: Exact는 공유 encoded objective에서는 DP보다 낮은 assignment를 선택했지만 실제 runtime은
  더 길었다. Exact plan은 매 iteration마다 `fed_*`, `fed_+`, `fed_/`, `fed_<=`, `fed_uak+`,
  `fed_uark+`, `fed_uack+` 등을 추가 실행한다. DP는 큰 `ba+*`를 FED에서 실행한 뒤 50,000x50
  결과를 local로 가져와 후속 작은 연산을 CP에서 수행한다.
- **원인 분석**:
  - `computeInBandWorkerResultDownloadCost`가 이미 열린 `FederationMap.execute` batch의 worker response를
    standalone W2C collection과 같은 `total/network + total/14.7MB/s` payload로 계산했다.
  - 실제 runtime은 모든 worker request를 먼저 제출하고 worker별 persistent Netty channel/future에서 응답을
    병렬 처리한다. workers=2의 약 19MiB 결과는 평균 약 147ms/iteration인데 기존 모델은 약
    1.45s/iteration을 부과했다. 이 10배 수준의 과대평가가 loop frequency와 곱해져 Exact가 수십 개의
    작은 remote stage를 유지하도록 만들었다.
  - explicit FED-to-CP collection은 coordinator가 전체 logical result를 materialize하므로 기존
    `wire(total/fan-in) + serdes(total)` 계약을 유지해야 한다. 두 runtime path를 한 공식으로 처리한 것이
    category error였다.
- **해결 요약**:
  - native FED/LOUT 및 worker partial-result response에 별도 in-band response critical-path 공식을 사용한다:
    `wire(total/fan-in) + response-serdes(total/fan-in)`. FED unary가 같은 batch의 fixed latency를 이미
    소유하므로 여기서는 payload만 계산한다.
  - response serdes는 `SYSDS_FED_COST_INBAND_RESULT_SERDES_BW_W2C`로 독립 override할 수 있고,
    기본값은 generic MatrixBlock serdes calibration이다. standalone directional W2C 14.7MB/s와 explicit
    collection 공식은 변경하지 않는다.
  - aggregate-binary, aggregate-unary, WDivMM/partial aggregation이 모두 같은 shared helper를 소비한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedCostModelFallbackTest.java`
- **단위 검증**:
  - `FederatedCostModelFallbackTest`: 43/43 통과. in-band response가 worker별 critical path를
    사용하고 explicit collection은 기존 full-result serdes 계약을 유지함을 함께 검증한다.
  - `CampaignBG014ExactKMeansWanRepeatedUploadRedTest`: 3/3 통과.
  - `CampaignBG014AlsPartitionedComputeCostRedTest`: 5/5 통과.
- **후속 검증**: 새 immutable artifact의 KMeans/LM/ALS compile plan 및 Docker runtime 결과를 이 절에
  기록한다.
- **잔여 이슈**: runtime n=1 차이는 host noise를 포함하므로 objective 순서가 모든 cell의 wall-clock 순서를
  완벽히 보장한다고 주장하지 않는다. 이번 수정은 plan/runtime decomposition으로 직접 확인된 systematic
  KMeans inversion을 대상으로 한다.
- **잠재 회귀 위험**: in-band 결과를 과소평가하면 반복적인 큰 native local result를 과선택할 수 있다.
  explicit collection 회귀 테스트, KMeans plan fingerprint, PCA 및 ALS/LM targeted runtime으로 감지한다.
- **의사결정 근거**: candidate exclusion이나 Exact 전용 보정이 아니라 DP와 Exact가 공유하는 physical cost
  surface에서 실제 runtime batch semantics를 분리했다.

## 2. Local DP가 포함관계의 exact conflict block을 중복 최적화했다

- **상태**: 수정 구현 및 단위/인증 검증 완료, 최종 compile benchmark 진행중
- **적용 원칙**: DP의 feasible domain/objective를 축소하지 않고 수학적으로 중복인 exact subproblem만 제거한다.
- **환경/조건**: current `FederatedPlanLocalCost` / `LocalCategoricalOptimizer`, PCA와 L2SVM compile-only.
- **관측 증상**: 같은 conflict neighborhood에 `{x,a}`와 `{x,a,b}` 같은 포함 block이 모두 생성되면 둘을
  각각 exact solve하고 dependency change 때 다시 방문했다. superset solve는 나머지 변수를 고정한 subset의
  모든 move를 이미 포함하므로 subset solve가 새 개선을 만들 수 없다.
- **원인 분석**: block 생성이 exact duplicate만 제거하고 strict subset은 유지했다.
- **해결 요약**: active maximal exact neighborhood만 유지한다. 새 superset이 오면 contained block을 retire하고,
  active superset이 이미 있으면 새 subset을 추가하지 않는다. inactive block은 initial queue와 revisit queue에서
  제외한다. 모든 factor dependency는 superset에 포함되므로 fixed-point semantics는 유지된다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizerTest.java`
- **사전 검증**: PCA local blocks 30→13, evaluated assignments 94,039→72,394이며 Exact와 objective bit가
  동일했다. L2SVM blocks 51→15, revisits 60→12, assignments 2,000,765→1,430,575였다. 성능 campaign과
  겹친 wall-clock 측정은 폐기하고 새 격리 compile-only 결과만 최종 근거로 사용한다.
- **단위/인증 검증**: `LocalCategoricalOptimizerTest` 9/9,
  `ExactPhysicalModelCertificateTest` 8/8, `FederatedPlanLocalCostIntegrationTest` 1/1 통과.
- **잔여 이슈**: 최종 동일 artifact compile-only DP/Exact 비교가 남았다.
- **잠재 회귀 위험**: block dependency가 incomplete하면 retired subset의 revisit를 놓칠 수 있다. exact-superset
  equivalence unit test와 DP-vs-Exact objective certificate로 감지한다.
- **의사결정 근거**: arbitrary top-K/pruning이 아니라 동일 exact move space의 dominance 제거다.

## 3. 새 DP의 privacy-filtered domain 소비 계약

- **상태**: 단위 검증 완료, broader suite 진행중
- **적용 원칙**: privacy 획득/전파/candidate exclusion은 selector 이전의 shared `PlacementAnalysis`가 소유하고,
  DP는 excluded candidate를 복구하거나 재추론하지 않는다.
- **환경/조건**: PRIVATE federated chain 및 기존 branch/loop/function privacy propagation fixtures.
- **관측 증상**: 새 local-conflict DP가 공통 privacy constraint를 우회하지 않는다는 planner-specific 증거가
  필요했다.
- **해결 요약**: `FederatedPlanLocalCostPrivacyConstraintTest`가 shared analysis를 DP에 직접 전달하고,
  selected state마다 (1) 같은 analysis identity, (2) legal-alternative identity membership,
  (3) `ReasonCode.PRIVACY` exclusion과 불일치, (4) PRIVATE node의 FED/FOUT 선택을 검증한다.
- **수정 파일**:
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCostPrivacyConstraintTest.java`
- **검증**: `FederatedPlanLocalCostPrivacyConstraintTest` 2/2 통과. 첫 테스트는 PRIVATE chain의
  legal-domain identity membership과 FED/FOUT 결정을, 둘째 테스트는 branch/loop/function의
  PRIVATE_AGGREGATE 전파 후 같은 계약을 검증한다. `SharedPrivacyPlacementAnalysisContractTest` 4/4도
  branch/loop/function을 가로지르는 privacy acquisition, propagation, pre-selector exclusion과
  privacy-safe domain 부재 시 selector 이전 실패를 별도로 검증한다.
- **잔여 이슈**: 최종 targeted/broader suite를 새 cost patch와 함께 재실행한다.
- **잠재 회귀 위험**: selector가 legal state와 equal하지만 analysis-owned object가 아닌 새 state를 만들 수 있다.
  identity membership assertion으로 감지한다.
- **의사결정 근거**: privacy는 selector별 후처리가 아니라 모든 selector가 공유하는 feasible-domain fact다.

## 4. Baseline LM/ALS campaign의 잘못된 immutable stage

- **상태**: 원인 해결 및 replacement campaign 완료
- **적용 원칙**: 실패 cell을 in-place retry하지 않고 올바른 immutable stage/new output으로 다시 시작한다.
- **관측 증상**: 첫 baseline launch의 LM/Exact workers=2가
  `emission planner identity mismatch: expected None, found Exact`로 실패했다.
- **원인 분석**: runtime JAR 문제가 아니라 wrapper가 `COMPILE_EXACT` receipt 지원 이전 stage
  `g007-stage-84c...`를 가리켰다. current Exact results가 사용한 올바른 stage는 `g007-stage-29a...`이다.
- **해결 요약**: 실패 output은 폐기하고 stage `29a...` 및 새 output `...-v2`로 8개 baseline cell을
  처음부터 실행한다.
- **수정 파일**: production 변경 없음.
- **검증**: replacement output
  `/home/mchoi/g014-targeted-dp-cost-frequency-20260823-v1/runtime/a738-baseline-lm-als-wan-light-w2-w4-v2`
  의 8/8 cell이 oracle/scan/teardown을 통과했고 fallback은 없었다. LM workers=2/4에서 DP와 Exact는
  모두 `fed_ba+*:91;fed_fedinit:2;fed_r':46` 및 runtime fingerprint
  `ba+*:3;fedinit:2;r':2`를 선택했다. ALS workers=2/4에서도 모두
  `fed_!=:1;fed_fedinit:1;fed_wdivmm:172` 및
  `!=:1;*:2;ba+*:2;fedinit:1;r':1;wdivmm:2`를 선택했다. warm runtime은 LM w2
  DP/Exact 5.380/5.361초, LM w4 4.760/4.717초, ALS w2 92.358/92.153초, ALS w4
  49.933/49.939초였다.
- **판정**: LM과 ALS의 작은 DP/Exact 차이는 동일한 물리 fingerprint 위의 n=1 변동이며,
  KMeans의 서로 다른 반복 물리 계획과 같은 모델 inversion 증거가 아니다. 수정 후 같은 plan equivalence를
  회귀 확인한다.
- **잔여 이슈**: 없음.
- **잠재 회귀 위험**: 다른 stage의 rows를 섞을 수 있다. stage descriptor/JAR/source hash로 fail-closed 비교한다.
- **의사결정 근거**: provenance failure를 planner/runtime failure로 오진하지 않는다.
