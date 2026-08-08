# Session Issues — 2026-08-08

## 1. DP multi-worker function formal placements conflict

- **상태**: 진행중 (코드/회귀 해결, Docker 검증 대기)
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
- **잔여 이슈**: 새 JAR로 WAN-mid DP와 LAN StepLM Docker canary 및 전체 캠페인 검증.
- **잠재 회귀 위험**: over-grouping could turn DP completion into a global optimizer. Detect by retaining local DP domain ordering and grouping only graph-declared `SAME_PLACEMENT`, `SAME_FTYPE`, or `CONJUNCTIVE` legality relations incident to synthetic boundaries.
- **적용 원칙/의사결정 근거**: planner global-legality modeling; no runtime fallback and no runtime-supported candidate guard.

## 2. MinST selects derived FOUT without its exact durable anchor owner

- **상태**: 진행중 (코드/회귀 해결, Docker 검증 대기)
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
- **잔여 이슈**: 새 JAR로 LAN StepLM Docker canary 및 전체 캠페인 검증.
- **잠재 회귀 위험**: matching an owner by FType alone would accept a foreign FederationMap. Detect by scoping the factor to `action.key().durableAnchorOwner()` identity, not a same-FType node search.
- **적용 원칙/의사결정 근거**: planner must model runtime output/materialization constraints before selection; runtime remains a strict executor.

## 3. WAN-mid MinST ALS is not monotone from one to two workers

- **상태**: 진행중 (evidence review; no guard-based fix authorized)
- **환경/조건**: prior Docker campaign, `compile_min_st_cut`, WAN-mid, ALS P2P2D; observed approximately `w1=130.278s`, `w2=150.897s`, `w3=130.357s`, `w4=115.947s`
- **재현 절차**: inspect the immutable prior campaign under `/home/mchoi/g014-wan-mid-lan-results-c6d418f-26882dd-20260807-v1` and regenerate the execution-time matrix from its terminal receipts.
- **관측 증상**: runtime rises at worker 2 before decreasing at workers 3–4.
- **원인 분석**: the worker-1 input is one `FULL` partition and selected mostly coordinator-local execution; worker 2 changes topology to row partitions and selects a hybrid federated WDivMM/derived-FOUT/refed plan. WAN-mid communication/coordination can outweigh two-worker compute parallelism. This is not yet proof of a planner bug because worker count changes the legal physical topology.
- **해결 요약**: no arbitrary monotonicity guard will be added. After the two proven legality defects are fixed, compare MinST's predicted objective and selected plan facts against a legal local/hybrid baseline, then use fresh Docker results to decide whether a compute/size/boundary cost term is missing.
- **수정 파일**: none yet; any cost correction must first be locked by a measurement/cost regression.
- **검증**: prior LAN ALS is monotone while WAN-mid changes plan family at w1→w2, supporting a network/topology explanation rather than a universal execution defect.
- **잔여 이슈**: fresh full campaign and predicted-vs-observed plan audit.
- **잠재 회귀 위험**: forcing monotonicity by closing FED candidates would violate the open legal candidate-space rule. Detect via the existing `CampaignBG014AlsPartitionedComputeCostRedTest` and plan-fact diffs.
- **적용 원칙/의사결정 근거**: inspect compute, size/memory, and boundary cost before changing candidate space.

## 4. LAN planner execution times are often close

- **상태**: 진행중 (semantic validation; not assumed to be a defect)
- **환경/조건**: prior Docker LAN matrix, all four planners, P2P2D workloads, 1–4 workers
- **관측 증상**: several workloads have overlapping execution-time curves across planners.
- **원인 분석**: immutable plan fingerprints show real convergence for multiple cells (for example LM at workers 2–4 for Heuristic/DP/MinST and LogReg at workers 2–4 for FedAll/DP/MinST). LAN uses high bandwidth and low latency, so remaining placement differences can also be runtime-negligible relative to compute.
- **해결 요약**: do not manufacture policy differences. Fresh results must prove that each planner consumed the LAN profile, emitted a valid policy-specific plan where its objective differs, and converged only where the same/near-equivalent plan is rational.
- **수정 파일**: none yet.
- **검증**: prior profile receipts contain LAN bandwidth/latency values and plan-hash convergence; new immutable campaign required after code changes.
- **잔여 이슈**: new 336-cell campaign, plan-fingerprint matrix, execution-time matrix, and ordering audit.
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
