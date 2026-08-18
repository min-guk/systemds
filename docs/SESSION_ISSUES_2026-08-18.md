# Session Issues — 2026-08-18

## Privacy authority가 selector 내부 DP 전용 상태에 남아 있던 문제

- **상태**: 해결
- **환경/조건**: compiled federated planning, FedAll/Heuristic/DP/Exact, literal
  `federated(...)` inputs, branches/loops/functions/transient variables 포함
- **적용 원칙/제약**: privacy 및 runtime legality로 증명되는 경우에만 candidate를 닫고,
  runtime fallback을 추가하지 않는다. 네 planner는 동일한 final-HOP `PlacementAnalysis` identity를
  소비해야 한다.
- **의사결정 근거**: selector 알고리즘별 후처리가 아니라 selector 생성 전 공통 placement authority가
  privacy 획득·전파·candidate exclusion을 소유하도록 planner boundary를 수정했다.

### 관측 증상

- 기존 privacy acquisition/propagation은 DP rewire/enumerator 경로의 Hop-ID map과 결합되어 있었다.
- FedAll/Heuristic/Exact는 공통 whole-program graph를 받더라도 DP와 동일하게 privacy-filtered된
  candidate domain을 받는다는 구조적 보장이 없었다.
- missing predecessor privacy를 PUBLIC으로 취급하는 legacy helper가 있어 occurrence/value-version이
  갈라지는 branch, loop, function, transient 경계에서 fail-open 가능성이 있었다.

### 원인 분석

1. worker privacy metadata acquisition과 planner registry publication이 한 helper에 결합되어 있었다.
2. privacy propagation key가 exact compiled occurrence/value version이 아니라 Hop ID였다.
3. privacy filter가 neutral graph construction 완료 후가 아니라 DP-specific enumeration 경로에 있었다.
4. 따라서 planner factory 앞에서 생성되는 `PlacementAnalysis`가 privacy domain의 단일 authority가
   아니었다.

### 해결 요약

1. `FederatedPlannerUtils.resolveFederatedSourceMetadata(DataOp)`가 source별 privacy와 immutable worker
   partition metadata만 획득하도록 분리했다. 이 단계는 planner registry를 변경하지 않으며 unresolved
   metadata는 fail-closed한다.
2. `NeutralPlacementGraphBuilder.closePrivacyDomains(...)`를 whole-program candidate closure 뒤,
   selector factory 앞에 배치했다.
3. data edges, exact `ValueVersionKey`, transient definitions, branch/loop CFG predecessor versions,
   function argument/result boundaries를 따라 fixed-point privacy propagation을 수행한다.
4. `ExecPlacementPolicy.allowsCandidateEmission(...)`으로 각 exact candidate emission을 공통 privacy
   policy에 대조하고, 제외 결과를 `PRIVACY_EXCLUDED` 및 `ReasonCode.PRIVACY`로 보존한다.
5. immutable `PlacementPrivacyFacts`가 occurrence identity, value-version identity, predecessor list,
   effective privacy, distinct worker count를 보관하며 analysis fingerprint에도 포함된다.
6. DP의 legacy privacy acquisition/propagation을 제거하고 `analysis.requirePrivacy(...)` 및
   `analysis.numWorkers()`를 사용하게 했다. FedAll/Heuristic/Exact는 기존 final-HOP 호출 계약에 따라
   동일한 authoritative `PlacementAnalysis` instance를 받는다.
7. PRIVATE 값은 공통 domain에서 `<FED,FOUT>` 외 상태를 제거한다. 합법적인 emitted state가 하나도
   남지 않으면 selector 실행 전에 `DMLRuntimeException`으로 실패한다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerUtils.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementPrivacyFacts.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateRuleResolver.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/DpPlacementAdapter.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpRewireTransTable.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/placement/SharedPrivacyPlacementAnalysisContractTest.java`
- `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBG011PrivacyResolverOwnerContractTest.java`
- privacy source fixture를 사용하는 관련 placement/Exact tests

### 검증

다음 명령은 모두 종료 코드 0이었다.

```bash
mvn -q -Dspotless.check.skip=true -DskipTests test-compile

mvn -q -Dspotless.check.skip=true \
  -Dtest=PlacementAnalysisContractTest,PlacementAnalysisS2ContractTest,NeutralPlacementGraphCfgCoreTest,NeutralPlacementGraphExactCfgIdentityTest,PlacementAnalysisConstructionArchitectureTest,FederatedPlannerFactoryContractTest,FederatedPlannerFactorySourceGuardTest,CampaignBFedAllInvocationReceiptContractTest,CampaignBHeuristicInvocationReceiptContractTest,CampaignBG011DpProgramEntrypointOwnerContractTest,CampaignBR6MinStRootCutoverRedTest,CampaignBG014DpEnumeratorOracleOwnershipRedTest,CampaignBG014ProgramDynamicAuthorityParityRedTest,CampaignBG014PlacementCandidateRuleFactsSliceATest,SharedPrivacyPlacementAnalysisContractTest,CampaignBG011PrivacyResolverOwnerContractTest,CampaignBR4MinStOccurrenceDemandRedTest,CampaignBR5MinStExactSelectorShadowRedTest,CampaignBG014MinStDerivedFoutAnchorSelectionRedTest,CampaignBG014MinStL2SvmInternalEmissionCostRedTest,CampaignBG014MinStStepLmFunctionSourceLayoutRedTest,CampaignBG014MinStKMeansGroupedUploadAuthorityRedTest,CampaignBG014MinStLogRegParallelDispatchCostRedTest \
  test
```

`SharedPrivacyPlacementAnalysisContractTest`는 strict PRIVATE domain pruning, branch/loop/function
privacy propagation, selector 전 collection failure를 직접 검사한다. source guard는 final-HOP analysis
binding이 planner factory보다 앞에 있고 selector source에 acquisition/legacy propagation call이 없음을
검사한다.

### 잔여 이슈

- local-matrix federated init은 기존 정책대로 local `.mtd` privacy를 우선 사용하고, metadata가 없으면
  PUBLIC을 사용한다. literal remote source는 worker/local metadata 모두 실패하면 계속 fail-closed한다.
- privacy lattice transfer semantics 자체는 기존 COFEE 정책을 공통화한 것이며, 새로운 privacy level이나
  cryptographic runtime mechanism을 추가한 것은 아니다.

### 잠재 회귀 위험

- **위험**: CFG/function evidence label이 추가될 때 privacy edge 분류에서 누락될 수 있다.
  - **감지/차단**: 새 boundary evidence마다 occurrence/value-version privacy fixture를 추가하고,
    missing CFG owner 및 incomplete authority는 production invariant로 실패시킨다.
- **위험**: analysis-time metadata acquisition 때문에 hermetic compile test가 실제 worker에 연결될 수 있다.
  - **감지/차단**: fixture factory가 모든 statement/function/control-flow root를 순회해 source identity별
    test privacy를 등록하며 production에는 override가 설치되지 않는다.

## 구형 MinST Graph/Rewire planner가 현재 Exact와 함께 남아 있던 문제

- **상태**: 해결
- **환경/조건**: `COMPILE_MIN_ST_CUT` selector 및 MinST source/test tree
- **적용 원칙/제약**: 현재 Exact 구현인 `FederatedPlanMinSTCut` + `MinStExact*` + polynomial cut solver는
  유지하고, selector가 사용하지 않는 구형 구현만 삭제한다.
- **의사결정 근거**: factory/runtime call path의 ground truth는 현재 Exact entrypoint이며, 과거
  `FederatedPlanMinSTGraph/Rewire/CostEstimator/Planner`는 중복되고 서로 다른 formulation을 유지하는
  dead implementation이었다.

### 관측 증상 및 원인 분석

- production tree에 현재 Exact와 구형 graph/rewire implementation이 동시에 존재했다.
- 다수의 legacy test/bridge가 삭제 대상 클래스 내부 구현을 reflection/literal fixture로 고정해, 현재
  Exact optimizer의 shared representation과 다른 과거 architecture를 계속 유지하도록 만들었다.

### 해결 요약

- 다음 production class를 삭제했다.
  - `FederatedPlanMinSTGraph.java`
  - `FederatedPlanMinSTRewire.java`
  - `FederatedPlanMinSTCostEstimator.java`
  - `FederatedPlanMinSTPlanner.java`
- 위 클래스만을 검증하던 legacy tests/bridges/fixtures와 대형 integration test의 legacy-only methods를
  삭제했다.
- `FederatedPlannerFactory`의 Exact entrypoint인 `FederatedPlanMinSTCut`과 모든 `MinStExact*`,
  `MinStPolynomialCutSolver`, diagnostics/cost-sum components는 유지했다.

### 검증

- `rg 'FederatedPlanMinST(Graph|Rewire|CostEstimator|Planner)' src/main` 결과는 0건이다.
- 다음 현재-Exact 및 architecture suite는 종료 코드 0이었다.

```bash
mvn -q -Dspotless.check.skip=true \
  -Dtest=CampaignBR6MinStRootCutoverRedTest,MinStLayer1OwnerCarrierContractTest,MinStLayer2ProjectionLoggerContractTest,CampaignBArchitectureGuardTest,PlacementKernelBoundaryContractTest,CampaignBMinStDuplicateObligationRedTest,MinStDownloadAuthorityAmbiguityRedTest,MinStExactAnchorRelocationIdentityRedTest,MinStExactPhysicalModelCertificateTest,MinStExactPhysicalPlanSpaceOracleTest,MinStExactProjectionAuthorityPreservationRedTest,MinStPcaAuthorityClosureAndTWriteMetadataTest,CampaignBR3MinStExactProjectionBoundaryRedTest,CampaignBR7MinStExactPlacementProjectorTest,CampaignBR8MinStDiagnosticsSeamTest \
  test
```

### 잔여 이슈 및 잠재 회귀 위험

- 이름에 `MinST`가 남아 있다는 이유만으로 현재 Exact components를 삭제하면 안 된다. 삭제 기준은
  class name이 아니라 factory reachability와 current formulation이다.
- 외부 문서/스크립트가 삭제된 legacy class FQCN을 직접 참조하면 컴파일이 실패한다. production 및
  repository test compile은 이를 통과했으며 외부 consumer는 current Exact API로 이동해야 한다.

## Analysis-time privacy acquisition으로 hermetic Exact CLI fixtures가 worker RPC를 시도한 문제

- **상태**: 해결
- **환경/조건**: compile-only Exact workload tests, 실제 worker 미기동
- **적용 원칙/제약**: production의 fail-closed semantics는 완화하지 않고 test evidence만 hermetic하게
  만든다.
- **의사결정 근거**: 공통 privacy acquisition은 의도된 production 동작이므로 우회하지 않고, test가
  local `.mtd` metadata를 제공하도록 fixture를 수정했다.

### 관측 증상 및 원인 분석

- privacy acquisition이 selector 전으로 이동한 뒤 일부 Exact CLI tests가 dummy worker address에서
  privacy RPC를 시도했다.
- 이 tests는 physical worker execution이 아니라 compile/selection만 검증하므로 remote metadata
  dependency가 test 목적과 맞지 않았다.

### 해결 요약 및 수정 파일

- `MinStExactCliMetadataFixture`가 temporary local matrix path와 `private-aggregate` `.mtd`를 만들고,
  기존 DML argument가 그 metadata를 가리키게 했다.
- 적용 tests:
  - `CampaignBG014MinStDerivedFoutAnchorSelectionRedTest`
  - `CampaignBG014MinStKMeansGroupedUploadAuthorityRedTest`
  - `CampaignBG014MinStL2SvmInternalEmissionCostRedTest`
  - `CampaignBG014MinStStepLmFunctionSourceLayoutRedTest`
- `CampaignBG014MinStLogRegParallelDispatchCostRedTest`는 이미 local metadata를 사용했다.

### 검증/잔여 위험

- 위 5개 Exact CLI tests는 함께 종료 코드 0이었다.
- production worker resolution은 변경하지 않았다. test helper가 실제 workload script의 path semantics와
  어긋나지 않도록 `InitFEDInstruction.parseURL`로 실제 parsing되는 `worker:port//absolute/path` 형식을
  사용한다.

## 검증 환경 및 baseline-known failures

- **상태**: 해결(변경분 검증 완료), pre-existing failures는 별도 잔여 이슈
- **의사결정 근거**: 이번 변경과 무관한 failure를 privacy/legacy deletion 회귀로 잘못 분류하지 않기 위해
  checkpoint `e18fdde8ee` worktree에서 같은 test를 재실행했다.

### Baseline과 동일하게 실패한 tests

- `MinStExactProductionTractabilityCertificateTest`
  - `l2SvmSingleWorkerDefaultCostsKeepsTransientConstraintsLegal`
  - `sevenCampaignWorkloadsPublishExactPhysicalTractabilityCertificate`
- `FederatedPlanCostEnumeratorTest#testFederatedPlanCostEnumerator15`
- 기존 Exact ambiguity/facts tests 중
  `CampaignBMinStExactFactsBehaviorRedTest`, `G007MinStForwardMembershipAuthorityRedTest`,
  `CampaignBR10MinStFTypeMembershipAuthorityRedTest`의 알려진 failures/errors

위 failures는 checkpoint `e18fdde8ee`에서도 같은 assertion/error로 재현되어 이번 diff의 회귀로
분류하지 않았다.

### Build/static verification

```bash
mvn -q -DskipTests -Dcheckstyle.skip=false validate   # RC=0
mvn -q -Dspotless.check.skip=true -DskipTests package # RC=0
git diff --check                                      # RC=0
```

`mvn -q -DskipTests spotless:check`는 project/repository에서 Spotless plugin prefix를 찾지 못해
`NoPluginFoundForPrefixException`으로 실행할 수 없었다. 대신 checkstyle, Java test compilation,
targeted tests, package, `git diff --check`를 사용했다.

### Maven target symlink 복구

- repository의 `target`은 `/dev/shm/...`를 거쳐 `/tmp/g007-bb30-fresh-target-20260727`로 연결된
  symlink chain이다.
- `mvn clean`이 최종 target directory를 제거해 이후 compiler output path가 사라졌다.
- `/tmp/g007-bb30-fresh-target-20260727`를 다시 생성한 뒤 test-compile/package를 성공시켰다.
- **잠재 회귀 위험**: 다시 `mvn clean`하면 동일 현상이 발생할 수 있다. 이 checkout에서는 clean 대신
  target directory 존재를 확인하고 incremental `test-compile`/`package`를 사용한다.
