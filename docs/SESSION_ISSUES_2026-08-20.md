# Session Issues — 2026-08-20

## 1. Lossy DP frontier pruning by arbitrary top-K limits

- **상태**: 해결
- **환경/조건**: `COMPILE_FEDERATED_COST` (DP), exact occurrence-aware memo frontier,
  shared DAGs and materialization-sensitive CP/FOUT alternatives.
- **재현 절차**:
  ```bash
  mvn -q -Dspotless.check.skip=true -DskipITs \
    -Dtest=FederatedPlannerDpBoundaryFrontierTest test
  ```
- **관측 증상**: 기존 구현은 일반 output frontier를 최대 8개, materialization-sensitive
  CP frontier를 최대 4개로 절단했다. 서로 다른 placement/FType/materialization/child
  authority를 가진 12개 후보 fixture에서 8개만 남았고, 동일 boundary의 세 비용 후보도
  하나로 dominance 처리되지 않았다.
- **원인 분석**: 후보 수를 제한하는 top-K는 future-observable boundary의 동치성을
  증명하지 않고 순위만으로 상태를 버렸다. 따라서 나중의 parent, shared consumer,
  function/transient boundary가 요구할 수 있는 합법 상태가 사라질 수 있었다.
- **해결 요약**: cardinality cap을 전부 제거했다. result placement, exec/output,
  FType/upload FType, derived/materialization/recurrence authority, candidate/relocation
  authority, occurrence-aware ordered child boundaries로 `CompleteBoundarySignature`를
  만들고, 각 signature 안에서만 cumulative cost 최소 대표 하나를 유지한다. 서로 다른
  signature는 비교 불가능한(non-dominated) 상태로 모두 보존한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpMemoTable.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpBoundaryFrontierTest.java`
- **검증**: 위 회귀 테스트 통과. 8개를 넘는 distinct signature 보존, 동일 signature의
  최소비용 대표 선택, materialization 및 CP upload FType 구분을 각각 검증했다.
- **잔여 이슈**: 실제 workload별 frontier cardinality와 peak heap은 별도의 compile-only
  campaign에서 계속 측정해야 한다.
- **잠재 회귀 위험**: 새로 추가되는 execution-relevant `FedPlan` field가 signature에
  포함되지 않으면 잘못 dominance 처리될 수 있다. 새 field를 추가할 때 동일 signature
  회귀 fixture도 함께 확장해 감지한다.
- **의사결정 근거**: runtime-supported 후보를 임의로 닫지 않고 상태 표현을 완전하게
  만들어 비용 기반 dominance만 적용한다는 planner 원칙을 따른다.

## 2. DP exact-component reconciliation returned the first coherent assignment

- **상태**: 해결
- **환경/조건**: general DAG/function/transient component의 retained DP arms를 exact
  occurrence constraints와 결합하는 local component search.
- **재현 절차**:
  ```bash
  mvn -q -Dspotless.check.skip=true -DskipITs \
    -Dtest=CampaignBG014DpExactComponentJoinOracleRedTest test
  ```
- **관측 증상**: domain 순서상 먼저 방문한 coherent assignment의 local objective가 10이고
  뒤의 coherent assignment가 1인 fixture에서 기존 search는 10을 반환했다.
- **원인 분석**: 재귀 search가 첫 feasible leaf를 찾은 뒤 호출 스택 전체에서 즉시
  반환해, retained plan space를 비용 최적화하지 않고 feasibility probe로만 사용했다.
- **해결 요약**: 모든 coherent assignment를 탐색해 최소 objective incumbent를 갱신한다.
  비용은 비음수이므로 incumbent 이상의 prefix를 branch-and-bound하고, 동일한 future
  search state는 더 싼 prefix만 memo에 남긴다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpExactComponentJoinOracleRedTest.java`
- **검증**: production private search를 reflection으로 호출하는 회귀 fixture에서 objective
  1 선택을 확인했다. synthetic function boundary, L2SVM clone family, transient-write exact
  owner 관련 타깃 테스트도 함께 통과했다.
- **잔여 이슈**: component width가 큰 경우 exact local reconciliation의 조합 수는 계속
  증가할 수 있다. 다만 profiling상 현재 workload에서는 전체 DP sample의 약 0.3%였다.
- **잠재 회귀 위험**: memo signature가 future constraint를 누락하면 유효한 prefix를 잘못
  prune할 수 있다. legality/authority 축을 추가할 때 memo-key 회귀 테스트로 감지한다.
- **의사결정 근거**: retained feasible domain 안에서 첫 해가 아니라 실제 최소비용
  coherent 해를 선택해야 DP의 비용 최적화 의미가 유지된다.

## 3. Repeated transient/function conflict discovery inflated DP planning time

- **상태**: 부분 해결. immutable topology 사전색인은 완료했지만 반복적인
  decision-dependent reconciliation 비용은 남아 있다.
- **환경/조건**: final authenticated compile-only campaign은 PlannerTrace/JFR/GC logging을
  끄고 runtime instruction을 실행하지 않았다. JFR은 timing과 분리한 새 JVM diagnostic run에서만
  활성화했다.
- **재현 절차 및 산출물**:
  - timing/validation:
    `/home/mchoi/g014-planner-compile-benchmark-7589d9823b-20260820-v1/results/full-blocked-wan-light-r1`
  - LOGREG-w1 four-planner JFR:
    `.../results/profiling-logreg-w1-r2`
  - KMEANS-w2 DP/Exact JFR:
    `.../results/profiling-kmeans-w2-r1`
  - `/tmp/cofee-dp-steplm-profile-1787214646.*`는 Maven/Surefire/JaCoCo가 개입한 초기 smoke
    artifact이며 final 외부 fresh-JVM 성능 근거로 사용하지 않는다.
- **관측 증상**: 7 workloads × 4 worker counts × 4 planners × 5 measured repetitions에서
  DP의 FedAll-normalized cell geometric mean은 3.150배였다. LOGREG-w1 DP JFR 934 planner
  samples 중 transient-read sibling 수집 131, output-closure refinement 127,
  output-decision 계산 121, original-Hop identity resolution 111이 나타났고,
  `collectConflictsSingleBFS`는 inclusive 44(4.7%), leaf 21(2.2%)였다. KMEANS-w2에서는
  conflict BFS가 inclusive 80/603이지만 leaf는 23/603(3.8%)였으며 `ConflictEntry` 생성만
  sampled allocation 2.28 GB를 차지했다.
- **원인 분석**: static logical transient/function topology 자체는 memo 생성 때 한 번
  index된다. 그러나 선택 output/plan/child boundary가 바뀔 때마다 active conflict가 달라지므로
  여러 refinement 경로가 conflict map, queue, visited set, occurrence variant, identity/signature,
  parent/child sharing cost를 다시 구성한다. 따라서 병목은 단일 BFS가 아니라 전체
  decision/closure/conflict reconstruction 경로다.
- **해결된 범위**: `PlacementAnalysis`로부터 exact occurrence map과 immutable
  `TransientConflictRelation` topology를 memo 생성 시 한 번 구축한다. 선택된 plan과 output
  decision에 의존하는 legality/filtering/cost resolution은 보존했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpMemoTable.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
- **검증**:
  ```bash
  mvn -q -Dspotless.check.skip=true -DskipITs \
    -Dtest=FederatedPlannerDpBoundaryFrontierTest,CampaignBG014DpExactComponentJoinOracleRedTest,CampaignBG014DpSyntheticFunctionBoundaryComponentRedTest,CampaignBG014DpL2SvmCloneFamilyDecisionRedTest,CampaignBG011DpTransientWriteExactOwnerRedTest test
  mvn -q -Dspotless.check.skip=true -DskipTests compile
  git diff --check
  ```
  모두 통과했다. final campaign은 28/28 blocks, 672/672 observations, 0 failures이고
  672개 receipt/log hash가 모두 일치한다. runtime-nonzero observation, PlannerTrace,
  runtime-explain marker는 모두 0이다.
- **잔여 이슈**: immutable multi-parent/transient topology와 conflict-entry skeleton을 재사용하고,
  decision 변경의 affected component만 bottom-up 증분 갱신해야 한다. `resolveOriginalHopId`,
  occurrence variant lookup, normalized identity/signature도 cache 후보이다. 다만 active conflict는
  selected state에 의존하므로 static conflict list로 단순 대체하면 합법 후보를 누락할 수 있다.
- **잠재 회귀 위험**: function formal 하나가 여러 binding write를 갖는 합법 IR이 생기면
  현재 invariant check가 실패한다. 그런 fixture가 등장하면 topology를 multimap으로
  확장하되 constraint를 누락하지 않는 테스트로 감지한다.
- **의사결정 근거**: candidate space나 legality를 축소하지 않고, 결정과 무관한 graph
  topology만 사전색인하는 behavior-preserving 최적화다. final profile은 이 최적화만으로
  DP compile-time 문제가 해결되었다고 주장할 수 없음을 보여준다.

## 4. Live optimizer was named MinST although it no longer implemented min-s-t-cut

- **상태**: 해결
- **환경/조건**: formerly `fedMinSTCut` production package, planner enum/factory/configuration,
  current categorical exact physical optimizer and obsolete legacy cut stack.
- **관측 증상**: former factory entry `COMPILE_MIN_ST_CUT`가 실제로는 categorical variable
  elimination 기반 physical optimizer를 실행했다. 동시에 호출되지 않는 cut solver,
  selector, variant-search classes와 min-cut 전용 fixture가 남아 논문·실험·코드에서
  알고리즘 정체성이 혼동됐다.
- **원인 분석**: 초기 min-cut 구현 이후 exact physical optimizer를 같은 package와 public
  selector 이름 아래 교체했지만, live identifiers와 더 이상 호출되지 않는 cut
  implementation을 정리하지 않았다. 또한 현재 exact cost closure와 과거 cut facts가
  하나의 대형 producer에 섞여 있었다.
- **해결 요약**:
  - 실행되는 categorical optimizer를 `fedExact` package의 `FederatedPlanExact`,
    `ExactPhysicalModel`, `ExactPhysicalCostModel`, `ExactCategoricalSolver`로 분리·명명했다.
  - planner enum/configuration을 `COMPILE_EXACT`/`compile_exact`로 바꾸고 factory,
    translator, placement receipt/adapter, trace stage, campaign plot/ledger 명칭을 함께 갱신했다.
  - old min-cut solver, selector, variant search, cut-fact/diagnostics/projector stack과 그 경로만
    검증하던 tests 및 offline cut fixtures를 삭제했다.
  - categorical selection과 무관한 two-node cut oracle, source/sink partition projection,
    cut-named receipt fields, 항상 비어 있던 legacy obligation carrier도 제거했다.
  - 현재 exact physical model의 categorical alternatives, hard factors, shared cost factors,
    reusable transfer identities, occurrence/frequency model은 `ExactPhysicalCostModel`에
    보존했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/*`
  - `src/main/java/org/apache/sysds/hops/fedplanner/FTypes.java`
  - `src/main/java/org/apache/sysds/hops/ipa/FederatedPlannerFactory.java`
  - `src/main/java/org/apache/sysds/parser/DMLTranslator.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/ExactPlacement{Adapter,Input}.java`
  - `scripts/federated_campaign/*`
  - `src/test/scripts/functions/privacy/fedplanning/SystemDS-config-exact.xml`
- **검증**:
  ```bash
  mvn -q -Dspotless.check.skip=true -DskipTests test-compile
  mvn -q -Dspotless.check.skip=true \
    -Dtest='<fedExact package tests plus DP boundary, factory, ownership, trace, and placement guards>' test
  python3 -m unittest discover -s scripts/federated_campaign/tests -p 'test_*.py'
  ```
  Java target suite는 29 classes/94 tests 모두 failure/error 없이 통과했고, campaign Python suite는
  107 tests가 통과했다. live `src/main`, `src/test`, `scripts`에는 old planner token이나
  old package/file path가 남지 않았다.
- **잔여 이슈**: `compile_min_st_cut`를 직접 지정하던 외부 configuration은
  `compile_exact`로 migration해야 한다. 의도적으로 compatibility alias를 두지 않아
  obsolete algorithm name이 다시 실행 surface에 남지 않게 했다. 과거 실험 산출물과
  historical documentation의 MinST label은 당시 provenance이므로 live code와 분리해 보존한다.
- **잠재 회귀 위험**: categorical variable elimination은 induced width와 materialized factor
  cell limit에 민감하다. `ExactPhysicalModelCertificateTest`의 brute-force oracle 및 production
  limit tests로 최적성/tractability contract를 계속 검증한다.
- **의사결정 근거**: 구현된 알고리즘을 Exact로 명명하고 사용되지 않는 min-cut residue만
  제거해 research abstraction과 executable code를 일치시킨다.

## 5. Controlled compile-only planner benchmark

- **상태**: 완료
- **목적**: runtime 실행과 trace/logging overhead를 제거하고 각 planner의 실제
  `Compile Phase FedPlanner`만 동일 조건에서 비교한다.
- **설계**: 7 workloads × 4 worker counts × 4 planners, cell마다 fresh-JVM warm-up 1회와
  measured 5회. workload/worker block 안에서 planner 순서를 회전했다. 정규화 baseline은
  FedAll=1이다.
- **결과**:
  - FedAll-normalized cell geometric mean: FedAll 1.000, Heuristic 0.915,
    DP 3.150, Exact 0.809.
  - mean planner seconds: FedAll 2.403, Heuristic 1.845, DP 6.921, Exact 1.659.
  - DP의 HOP-count-to-cell-mean 상관: Pearson 0.883, Spearman 0.939.
  - planner timer 밖 `LopsBuild - FedPlanner` 평균: FedAll 1.367, Heuristic 1.362,
    DP 1.326, Exact 1.356초.
- **타이밍 경계**: `DMLTranslator`는 physical normalization과 canonical
  `PlacementAnalysis` binding을 완료한 뒤 planner timer를 시작한다. 따라서 DP 격차는
  공통 privacy/placement analysis 생성 비용이 아니라 planner-specific rewrite/reconciliation
  경로에서 발생한다.
- **Exact 해석**: 현재 workload의 factor graph에서는 Exact가 빠르지만, KMEANS-w2 diagnostic
  profile의 sampled peak는 Exact 2.196 GiB, DP 1.651 GiB였다. Exact의 dense-factor variable
  elimination은 induced width/domain cardinality/materialized cells에 민감하므로 이번 속도를
  일반적 tractability 보장으로 해석하지 않는다.
- **검증**: machine-readable `validation.json`의 모든 check가 true이며 28/28 blocks,
  112/112 cells, 672/672 total observations, 560/560 measured observations, 0 failures이다.
  672개 receipt/log의 실제 SHA-256이 ledger와 일치하며 runtime execution, PlannerTrace,
  runtime explain은 모두 0이다.
- **산출물**:
  - `/home/mchoi/g014-planner-compile-benchmark-7589d9823b-20260820-v1/EXPERIMENT_REPORT.md`
  - `.../results/full-blocked-wan-light-r1/validation.json`
  - `.../results/full-blocked-wan-light-r1/summary/planner_compile_grid.{png,pdf}`
  - `.../results/profiling-logreg-w1-r2/profile-comparison.{csv,json}`
  - `.../results/profiling-kmeans-w2-r1/profile-comparison.{csv,json}`
- **범위 제한**: 이 campaign은 planning overhead만 검증한다. trace를 의도적으로 껐으므로
  DP/Exact의 selected physical plan quality 차이는 normalized plan/assignment/objective
  certificate를 별도로 대조해야 한다.

## 6. Local DP selected loop-amplified FOUT materialization basins

- **상태**: 코드·회귀 검증 완료, fresh artifact runtime 검증 진행 중.
- **환경/조건**: WAN-light L2SVM의 30회 outer loop와 20회 inner loop 안에서 생성되는
  `1-Y*Xw` 계열 중간값, local-cost planner(`COMPILE_FEDERATED_COST`)의 selected
  FED/FOUT state와 coordinator-local consumer.
- **관측 증상**: 첫 factor-neighborhood 수정 후에도 L2SVM-w2는 73.211초였고,
  Heuristic 22.228초, 이전 DP 6.122초, Exact 4.863초보다 느렸다. runtime explain에는
  30x20과 30의 frequency가 적용된 두 loop-local `b(1-*)` 결과가 FED/FOUT으로 남아
  coordinator에서 각각 반복 materialize되면서 `fed_1-*`와 `prefetch`가 총 630회
  발생했다.
- **버그와 구조적 한계의 구분**:
  - 첫 구현은 factor/shared-producer 관계 일부를 local interaction scope에 포함하지 않아
    합동 변경을 시도하지 못한 구현 결함이 있었고, 이는 이전 커밋에서 수정했다.
  - 그 뒤 남은 현상은 개별 producer 또는 작은 star만 바꾸면 당장 비용이 증가하지만,
    선택된 downstream FOUT chain과 local boundary를 함께 바꾸면 총비용이 감소하는
    local cost barrier였다. 이는 privacy/physical feasibility 위반이 아니라 제한된
    neighborhood descent의 구조적 local-minimum 가능성이다.
- **해결 요약**:
  - 현재 fixed point에서 실제로 FED/FOUT 결과를 local input으로 materialize하는 source만
    찾는다.
  - 해당 source에서 선택된 physical FOUT edge만 따라 downstream component를 구성하고,
    각 component node의 direct input/consumer fringe를 하나의 conflict block으로 푼다.
    non-FOUT decision에서 traversal을 중단하므로 whole-program/global enumeration으로
    확장하지 않는다.
  - 공통 value-boundary hard closure를 같은 block에 적용해 occurrence/value/FType 관련
    합법성 제약을 보존한다. 모든 후보는 이미 공통 privacy-filtered domain에서 나오며,
    block update는 incident shared-cost가 엄격히 감소할 때만 수용하고 마지막에 모든
    hard factor를 다시 인증한다.
  - block preparation, incident hard/cost factors, variable-to-dependent-block index와
    producer/consumer/function-input adjacency를 한 번만 만든다. deferred block이 상태를
    바꾸면 영향받은 기존 block만 재방문한다.
- **탐색 범위 근거**: 고정 2-hop 확장은 L2SVM에서는 문제를 해결했지만 6,742,309개 block
  assignment를 평가했다. 최종 selected-FOUT-component-plus-fringe 범위는 같은 회귀를
  해결하면서 2,035,001개를 평가했고, largest block은 기존 base block의 42 variables,
  deferred 최대 탐색은 424,124 assignments였다. 임의 top-K나 planner fallback은 없다.
- **검증**:
  ```bash
  mvn -q -DskipITs -DskipTests=false \
    -Dtest=LocalCategoricalOptimizerTest,CampaignBG014LocalL2SvmLoopRelocationRedTest test
  mvn -q -DskipITs -DskipTests=false \
    -Dtest=FederatedPlannerFactoryContractTest,FederatedPlannerFactorySourceGuardTest,CampaignBArchitectureGuardTest,SharedPrivacyPlacementAnalysisContractTest,LocalCategoricalOptimizerTest,FederatedPlanLocalCostIntegrationTest,CampaignBG014LocalL2SvmLoopRelocationRedTest,CampaignBG014ExactKMeansWanRepeatedUploadRedTest,ExactPhysicalModelCertificateTest test
  git diff --check
  ```
  모두 통과했다. L2SVM 회귀는 line 106/120의 두 반복 `b(1-*)`가 CP로 선택되고 selected
  local-materialization source가 아님을 검증한다.
- **보장 범위**: 이 local optimizer는 shared feasible domain/cost model 안에서 monotone
  cost descent와 legality를 보장하지만 전역 최적성이나 임의의 모든 DAG에서
  FedAll/Heuristic보다 항상 낮은 runtime을 수학적으로 보장하지는 않는다. 그런 보장은
  두 baseline plan을 함께 평가하는 fallback 또는 global search가 필요하며 현재 planner의
  독립적 local-cost 철학과 compile-time 목표에 어긋난다. 본 수정의 주장은 관측된
  loop-amplified materialization basin을 conflict-local refinement로 제거한다는 것이다.
- **잔여 검증**: 새 commit/JAR/stage에서 logging 없는 compile-only benchmark와 L2SVM/PCA
  runtime canary를 실행해 compile overhead와 실제 RPC/materialization 감소를 확인한다.
