# Session Issues — 2026-08-08

## 1. DP multi-worker function formal placements conflict

- **상태**: 해결 (코드/회귀 및 Docker canary 통과; 전체 캠페인 재검증 예정)
- **환경/조건**: `compile_cost_based`, private-aggregate P2P2D StepLM, LAN cost profile, 2+ row-partition workers, compile-only regression and prior Docker WAN-mid/LAN cells
- **재현 절차**:
  - `mvn -q -DskipTests=false -Dspotless.check.skip=true -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014DpSyntheticFunctionBoundaryComponentRedTest test`
  - RED log: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/red-20260808/dp-synthetic-boundary-lan-red.log`
  - Prior Docker example: `/home/mchoi/g014-wan-mid-lan-results-c6d418f-26882dd-20260807-v1/cells/222-ea4945b97c0f/phases/cell-1/cold-docker-e2e/raw_coordinator.log`
- **관측 증상**: `IllegalArgumentException: DP synthetic function input has conflicting selected formals`
- **원인 분석**: disconnected-completion component partitioning ignores legality edges when one endpoint is a synthetic function boundary. Exact formal occurrences owned by one boundary can therefore be optimized in independent components and select incompatible placements. The final synthetic-boundary projection is the first place the contradiction is detected.
- **해결 요약**: synthetic function-boundary legality relation을 exact ordinary formal occurrence 사이의 component hyperedge로 투영했다. Exact join 중에는 synthetic boundary가 선택할 수 있는 공통 상태가 적어도 하나 남는지 검사한다. 최종 output-decision refinement에도 같은 boundary가 소유한 formal occurrence만 묶어 LOUT/FOUT 양쪽을 기존 DP complete-forest score로 비교한다. Caller argument는 FOUT→LOUT materialization이 합법이므로 formal과 강제로 동일화하지 않았다. 따라서 전역 합법성 충돌만 닫고 DP의 local parent/child recurrence 철학은 유지한다.
- **수정 파일**:
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpSyntheticFunctionBoundaryComponentRedTest.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/DpPlacementAdapter.java` (conflict diagnostic only)
- **검증**:
  - RED: current base `c6d418fa32`에서 prior Docker와 동일한 conflicting-formals exception 재현.
  - GREEN: `CampaignBG014DpSyntheticFunctionBoundaryComponentRedTest`와 `CampaignBG014DpStepLmDecisionMapClosureRedTest` 동시 통과. 로그: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/green-20260808/dp-function-boundary-and-closure-attempt3.log`.
  - 기존 function-input candidate 계약 테스트도 현재 formal-owned tuple 계약으로 교정 후 통과. 로그: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/green-20260808/dp-function-input-current-contract-attempt2.log`.
  - commit `c1640231c6`의 인증된 Docker canary에서 WAN-mid DP L2SVM w2 `12.446s`, WAN-mid DP StepLM w2 `27.383s`, LAN DP StepLM w2 `20.704s` 모두 `oracle_passed=true`, `runtime_scan_clean=true`, `fallback=false`로 통과. 요약: `/home/mchoi/g014-planner-legality-canaries-c164023-26882dd-20260808-v1/summary.json` (`6/6`, failure `0`).
- **잔여 이슈**: 후속 DP incumbent 수정까지 포함한 새 immutable JAR로 전체 336-cell 캠페인 재검증.
- **잠재 회귀 위험**: over-grouping could turn DP completion into a global optimizer. Detect by retaining local DP domain ordering and grouping only graph-declared `SAME_PLACEMENT`, `SAME_FTYPE`, or `CONJUNCTIVE` legality relations incident to synthetic boundaries.
- **적용 원칙/의사결정 근거**: planner global-legality modeling; no runtime fallback and no runtime-supported candidate guard.

## 2. MinST selects derived FOUT without its exact durable anchor owner

- **상태**: 해결 (코드/회귀 및 Docker canary 통과; 전체 캠페인 재검증 예정)
- **환경/조건**: `compile_min_st_cut`, private-aggregate P2P2D StepLM, LAN cost profile, 1–4 Docker workers; focused regression uses 2 workers and compile-only
- **재현 절차**:
  - `mvn -q -DskipTests=false -Dspotless.check.skip=true -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.CampaignBG014MinStDerivedFoutAnchorSelectionRedTest test`
  - RED log: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/red-20260808/minst-derived-fout-anchor-red.log`
  - Prior Docker example: `/home/mchoi/g014-wan-mid-lan-results-c6d418f-26882dd-20260807-v1/cells/218-3f845130ac26/phases/cell-1/cold-docker-e2e/raw_coordinator.log`
- **관측 증상**: `PlacementEmissionException: Derived FOUT action does not name one selected exact compiled FOUT anchor owner`
- **원인 분석**: `MinStExactPhysicalModel` exposes a graph-owned `DerivedFoutMaterializationAction` on the producer alternative but has no hard factor that also selects the action's exact `durableAnchorOwner` as `FOUT` with the required FType. Emission correctly rejects this physically impossible assignment.
- **해결 요약**: exact physical model에 producer/anchor-owner hard factor를 추가했다. 그 exact graph-owned derived action이 선택된 경우에만 활성화되며, `durableAnchorOwner` identity가 가리키는 exact owner가 요구 FOUT/FType으로 선택되어야 한다. Producer와 owner가 동일한 unary 경우와 서로 다른 binary 경우를 모두 모델링한다.
- **수정 파일**:
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014MinStDerivedFoutAnchorSelectionRedTest.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactPhysicalModel.java`
- **검증**:
  - RED: base `c6d418fa32`에서 prior LAN StepLM과 동일한 emission prevalidation failure 재현.
  - GREEN: `CampaignBG014MinStDerivedFoutAnchorSelectionRedTest`, `CampaignBG014MinStStepLmFunctionSourceLayoutRedTest`, `CampaignBG014MinStStepLmRuntimeRecompileRefedRedTest`, `MinStExactPhysicalModelCertificateTest` 통과. 로그: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/green-20260808/minst-related-regressions.log`.
  - commit `c1640231c6`의 인증된 LAN Docker canary에서 MinST StepLM w2가 `21.538s`로 성공했고 `append:2;fed_refed:3;fedinit:2;tsmm:1`의 실제 federated plan을 실행했다. `oracle_passed=true`, `runtime_scan_clean=true`, `fallback=false`; summary는 issue 1과 동일하다.
- **잔여 이슈**: 후속 DP incumbent 수정까지 포함한 새 immutable JAR로 전체 336-cell 캠페인 재검증.
- **잠재 회귀 위험**: matching an owner by FType alone would accept a foreign FederationMap. Detect by scoping the factor to `action.key().durableAnchorOwner()` identity, not a same-FType node search.
- **적용 원칙/의사결정 근거**: planner must model runtime output/materialization constraints before selection; runtime remains a strict executor.

## 3. WAN-mid MinST ALS is not monotone from one to two workers

- **상태**: 해결 (비단조성이 동일 물리 문제의 optimizer 오류가 아니라 토폴로지 전환임을 검증; 임의 단조화 수정 없음)
- **환경/조건**: prior Docker campaign, `compile_min_st_cut`, WAN-mid, ALS P2P2D; observed approximately `w1=130.278s`, `w2=150.897s`, `w3=130.357s`, `w4=115.947s`
- **재현 절차**: inspect the immutable prior campaign under `/home/mchoi/g014-wan-mid-lan-results-c6d418f-26882dd-20260807-v1` and regenerate the execution-time matrix from its terminal receipts.
- **관측 증상**: runtime rises at worker 2 before decreasing at workers 3–4.
- **원인 분석**: worker 1은 하나의 `FULL` partition이고 MinST는 `fedinit` 뒤 coordinator-local plan을 선택한다. worker 2부터 입력이 `ROW` partition으로 바뀌며 federated WDivMM/derived-FOUT/refed hybrid plan을 선택한다. 따라서 w1→w2는 같은 물리 plan의 worker scaling이 아니라 합법 plan universe 자체가 달라지는 경계다. WAN-mid 통신/직렬화 비용이 2-worker compute 병렬화 이득보다 클 수 있다.
- **해결 요약**: 후보를 닫거나 worker 수를 보고 결과를 강제하는 단조화 guard를 추가하지 않았다. 대신 fixture에 `w1=FULL`, `w2=ROW` exact topology 계약을 고정했고, 실제 Docker plan fingerprint로 plan-family 전환을 검증했다. w2 이후에는 기존 전체 결과가 `150.897 → 130.357 → 115.947s`로 단조 감소한다.
- **수정 파일**: `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014AlsPartitionedComputeCostRedTest.java` (토폴로지 계약만 추가; optimizer 후보/비용 변경 없음).
- **검증**:
  - 회귀: `CampaignBG014AlsPartitionedComputeCostRedTest` 전체 통과. 로그: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/green-20260808/minst-als-partitioned-and-topology.log`.
  - commit `c1640231c6` Docker canary: w1 `131.122s`, fingerprint `fedinit:1`, `fed_fout=0`, `fed_refed=0`; w2 `150.701s`, fingerprint `!=:1;*:2;+:1;ba+*:2;fedinit:1;wdivmm:2`, `fed_fout=144`, `fed_refed=10`. 둘 다 oracle/runtime scan 통과.
- **잔여 이슈**: 새 전체 캠페인에서 w2→w4 단조성과 각 worker 안에서 MinST의 상대 우위를 재확인한다. w1→w2 단조성은 서로 다른 물리 토폴로지이므로 correctness invariant로 사용하지 않는다.
- **잠재 회귀 위험**: forcing monotonicity by closing FED candidates would violate the open legal candidate-space rule. Detect via the existing `CampaignBG014AlsPartitionedComputeCostRedTest` and plan-fact diffs.
- **적용 원칙/의사결정 근거**: inspect compute, size/memory, and boundary cost before changing candidate space.

## 4. LAN planner execution times are often close

- **상태**: 해결 (실제 plan 차이와 합리적 수렴을 구분; 시각적 차이를 강제하는 수정 없음)
- **환경/조건**: prior Docker LAN matrix, all four planners, P2P2D workloads, 1–4 workers
- **관측 증상**: several workloads have overlapping execution-time curves across planners.
- **원인 분석**: immutable plan fingerprints show real convergence for multiple cells (for example LM at workers 2–4 for Heuristic/DP/MinST and LogReg at workers 2–4 for FedAll/DP/MinST). LAN uses high bandwidth and low latency, so remaining placement differences can also be runtime-negligible relative to compute.
- **해결 요약**: policy 차이나 실행시간 차이를 인위적으로 만들지 않는다. 같은/근사 plan이 합리적인 셀은 수렴을 허용하고, 목적이 다른 셀은 plan fingerprint로 실제 선택 차이를 검증한다.
- **수정 파일**: none yet.
- **검증**: commit `c1640231c6` LAN StepLM w2 Docker canary에서 DP는 `fedinit:2;tsmm:1`/`20.704s`, MinST는 `append:2;fed_refed:3;fedinit:2;tsmm:1`/`21.538s`로 서로 다른 실제 plan을 실행했다. 실행시간이 가깝더라도 planner가 동일하게 동작한 것이 아니다. 둘 다 fallback 없이 oracle/runtime scan을 통과했다.
- **잔여 이슈**: 새 336-cell 캠페인에서 전체 plan-fingerprint matrix, execution-time matrix, ordering audit를 생성한다.
- **잠재 회귀 위험**: treating close runtime as a bug could introduce artificial planner guards. Detect by comparing normalized plan facts and cost receipts before any change.
- **적용 원칙/의사결정 근거**: optimize legal plans according to each policy; do not force visual separation in performance graphs.

## 5. Maven regression target pointed at an exhausted RAM filesystem

- **상태**: 해결
- **환경/조건**: canonical source checkout test build
- **관측 증상**: initial test attempts failed before compilation with missing generated NOTICE and then `No space left on device`.
- **원인 분석**: `target` was a dangling/stale symlink and then pointed to a 100%-full `/dev/shm`.
- **해결 요약**: preserve the old symlink as `target.dangling-20260808` / `target.shm-full-20260808` and use a fresh local-disk `target` directory.
- **수정 파일**: generated build path only; no source change.
- **검증**: both new regressions subsequently reached and reproduced their intended planner exceptions. 이후 `mvn -q -DskipTests package`도 local target에서 exit 0으로 통과했다. 로그: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/green-20260808/package.log`.
- **잔여 이슈**: none for correctness; local disk headroom must be monitored during packaging.
- **잠재 회귀 위험**: disk exhaustion during full packaging; detect with `df` before stage creation.
- **적용 원칙/의사결정 근거**: verification infrastructure repair only.

## 6. StepLM function-input regression asserted obsolete caller-state object identity

- **상태**: 해결
- **환경/조건**: `CampaignBG014DpStepLmFunctionInputCandidateRedTest`, current neutral function-input boundary contract
- **재현 절차**: `mvn -q -DskipTests=false -Dspotless.check.skip=true -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014DpStepLmFunctionInputCandidateRedTest test`
- **관측 증상**: production base와 수정본 모두 `Synthetic input boundary must retain exact caller state identity: X`에서 실패했다.
- **원인 분석**: `71fe7251ca`부터 function formal이 boundary-facing placement와 shape-proof provenance를 소유한다. Caller opcode의 `PlacementState` 객체 identity를 boundary에 복사하면 오히려 formal provenance가 손실될 수 있는데, 이전 테스트가 옛 caller-owned 계약을 계속 요구했다.
- **해결 요약**: 테스트를 현재 계약에 맞춰 교정했다. Boundary의 각 상태는 합법적인 CP/LOUT 또는 FED/FOUT이고, formal target의 exact placement tuple이며, caller가 LOUT 직접/다운로드 또는 동일 FType FOUT으로 공급 가능해야 한다. 모든 caller-supplyable formal tuple이 boundary에 남는지도 역방향으로 검사한다.
- **수정 파일**: `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpStepLmFunctionInputCandidateRedTest.java`
- **검증**: 교정 후 테스트 exit 0. 로그: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/green-20260808/dp-function-input-current-contract-attempt2.log`.
- **잔여 이슈**: 없음.
- **잠재 회귀 위험**: equals-only 검사로 provenance 오류를 놓칠 수 있다. 별도 graph-builder 테스트에서 formal shape proof가 유지되는지 감지하고, 본 테스트는 placement tuple과 caller supply relation을 책임진다.
- **적용 원칙/의사결정 근거**: 테스트를 현재 planner-neutral boundary 계약에 맞춘 것이며 runtime fallback이나 candidate-space 축소는 없다.

## 7. WAN-mid ALS DP refinement replaced an executable forest with an incompatible map

- **상태**: 해결 (RED/GREEN 및 관련 DP 회귀 통과; 새 Docker canary 대기)
- **환경/조건**: `compile_cost_based`, private-aggregate ALS P2P2D, WAN-mid cost profile, worker 1 `FULL`, production `maxi=10`
- **재현 절차**:
  - RED trace: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/red-20260808/dp-als-wan-mid-w1-hop25-trace.log`
  - GREEN: `mvn -q -DskipTests=false -Dspotless.check.skip=true -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.CampaignBG014DpAlsExecutableIncumbentRedTest test`
- **관측 증상**: 최종 projection 직전에 `DP output decisions do not form an executable plan forest ... incompatiblePlans=1 ... conflicts=[25]`. Trace상 initial map은 `incompatible=0`이었지만 predicate hop 25를 LOUT으로 바꾸는 refinement가 충돌을 TWrite hop 26으로 이동시킬 뿐 개수를 줄이지 못했고, 그 map이 incumbent가 되었다.
- **원인 분석**: `selectLowerCostDecisionMap`은 finite total cost와 root coverage만 검사하는 `isScorableDecisionMapScore`를 사용했다. 따라서 exact occurrence conflict가 남은 refinement도 비용 비교 대상이 되어 executable initial forest를 덮어쓸 수 있었다. 반대로 initial map을 즉시 incumbent로 만들면 L2SVM virtual additional-root의 더 비싼 합법 closure가 선택되지 않는 회귀가 발생했다.
- **해결 요약**: refinement incumbent는 `missingRootCount=0`뿐 아니라 `incompatiblePlanCount=0`인 executable map만 허용한다. Initial/locked map이 executable이면 incumbent가 아니라 **fallback**으로 보존한다. 따라서 합법 refinement가 있으면 virtual-root/family closure를 포함해 우선 적용하고, 모든 refinement가 불법일 때만 원래 executable DP forest를 반환한다. DP의 parent/child local recurrence와 후보군은 변경하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014DpAlsExecutableIncumbentRedTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014AlsPartitionedComputeCostRedTest.java` (공용 ALS fixture/토폴로지 계약)
- **검증**:
  - Production-size WAN-mid ALS w1 `maxi=10` regression 통과: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/green-20260808/dp-als-executable-fallback-attempt2.log`.
  - L2SVM virtual additional-root의 executable refinement가 initial fallback보다 우선되는 회귀 통과: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/green-20260808/l2svm-clone-executable-refinement.log`.
  - ALS/StepLM/L2SVM/LogReg/exact-component 관련 7개 class, 10 tests 통과: `/tmp/g014-red-candidate-r5-20260720T211639Z/artifacts/green-20260808/dp-incumbent-related-regressions-attempt2.log`.
- **잔여 이슈**: 새 immutable JAR의 WAN-mid DP ALS canary 및 전체 캠페인.
- **잠재 회귀 위험**: executable initial fallback이 비용이 더 낮다는 이유로 합법 closure refinement를 가로채거나, 반대로 invalid refinement가 다시 선택될 수 있다. 전자는 `CampaignBG014DpL2SvmCloneFamilyDecisionRedTest`, 후자는 `CampaignBG014DpAlsExecutableIncumbentRedTest`로 양방향 감지한다.
- **적용 원칙/의사결정 근거**: runtime fallback 없이 planner가 executable exact forest만 방출한다. Runtime-supported candidate는 닫지 않았고, DP의 기존 local 최적화 철학을 유지한 채 전역 실행 합법성만 incumbent gate에 반영했다.
