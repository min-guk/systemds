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

## 4. Live optimizer is named MinST although it no longer implements min-s-t-cut

- **상태**: 진행중
- **환경/조건**: `fedMinSTCut` production package, planner enum/factory/configuration,
  current categorical exact physical optimizer and obsolete legacy cut stack.
- **관측 증상**: current factory entry `COMPILE_MIN_ST_CUT`가 실제로는
  `MinStExactPhysicalOptimizer`/categorical solver를 실행한다. 동시에 old cut solver,
  selector, variant-search classes가 남아 있어 논문·실험·코드에서 알고리즘 정체성이
  혼동된다.
- **원인 분석**: 초기 min-cut 구현 이후 exact optimizer를 같은 package와 public selector
  이름 아래 교체했지만 live identifiers와 더 이상 호출되지 않는 cut implementation을
  정리하지 않았다.
- **해결 계획**: current physical exact optimizer와 공유 cost surface는 `Exact`로 rename해
  보존하고, dependency graph상 live exact path에 포함되지 않는 old min-cut solver/selector/
  projector/variant-search stack 및 전용 tests를 삭제한다. immutable runtime campaign stage는
  수정하지 않는다.
- **잔여 이슈**: live dependency closure를 확인한 뒤 별도 커밋으로 수행한다.
- **잠재 회귀 위험**: mixed producer가 current physical cost surface와 legacy cut facts를 한
  class에 포함한다. 단순 파일 삭제 대신 exact path를 먼저 분리하고 factory/config/tests로
  검증해야 한다.
- **의사결정 근거**: 구현된 알고리즘을 Exact로 명명하고 사용되지 않는 min-cut residue만
  제거해 research abstraction과 executable code를 일치시킨다.
