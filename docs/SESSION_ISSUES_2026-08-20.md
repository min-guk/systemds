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

- **상태**: 해결 (compile-only profile 기준)
- **환경/조건**: trace/logging 비활성, StepLM compile-only DP, runtime instruction execution 없음.
- **재현 절차**: 기존 profile artifact는 다음 경로에 보존했다.
  - `/tmp/cofee-dp-steplm-profile-1787214646.jfr`
  - `/tmp/cofee-dp-steplm-profile-1787214646.json`
  - `/tmp/cofee-dp-steplm-profile-1787214646.log`
- **관측 증상**: JFR 625 DP samples 중 output-decision 계열이 약 27%, conflict BFS가 약
  14.2%였고, exact component join은 약 0.3%였다. `collectTransientReadParents`,
  `collectConflicts`, `augmentLogicalTransientConflictUsages`,
  `recordAnalysisTransientRelation`이 반복적으로 나타났다.
- **원인 분석**: decision-dependent BFS마다 변하지 않는 logical transient,
  function-input binding, function-formal group, CFG reaching-definition 관계를 재발견했고,
  각 TWrite마다 conflict map 전체를 다시 역탐색했다.
- **해결 요약**: `PlacementAnalysis`로부터 exact occurrence map과 immutable
  `TransientConflictRelation` topology를 memo 생성 시 한 번 구축한다. conflict map도
  identity별 TWrite-to-TRead inverse index를 한 번 생성해 BFS에서 재사용한다. 선택된 plan과
  output decision에 의존하는 filtering/cost resolution은 기존 위치에 유지했다.
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
  모두 통과했다. 같은 StepLM compile-only 단일 관측에서 FedPlanner는 9.231489초였다.
  이 단일 수치는 성능 결론이 아니라 회귀 smoke evidence로만 사용한다.
- **잔여 이슈**: 반복·warm-up을 통제한 planner-only benchmark와 heap/GC 비교가 필요하다.
  실행 중인 runtime campaign을 보호하기 위해 이번 세션에서는 추가 runtime workload를
  실행하지 않았다.
- **잠재 회귀 위험**: function formal 하나가 여러 binding write를 갖는 합법 IR이 생기면
  현재 invariant check가 실패한다. 그런 fixture가 등장하면 topology를 multimap으로
  확장하되 constraint를 누락하지 않는 테스트로 감지한다.
- **의사결정 근거**: candidate space나 legality를 축소하지 않고, 결정과 무관한 graph
  topology만 사전색인하는 behavior-preserving 최적화다.

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
