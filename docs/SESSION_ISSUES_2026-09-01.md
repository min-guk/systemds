# Session Issues — 2026-09-01

## 1. Single-worker ALS aborted Exact/FedAll while evaluating a stale lower-bound domain

- **상태**: 해결; targeted regression and selector suites pass.
- **적용 원칙**: runtime-supported states remain open; a candidate-incoherent partial branch is pruned instead of being scored or converted into a planner-wide failure.
- **환경/조건**: ALS with one federated worker, whose input has exact `FULL` topology; `COMPILE_FED_ALL` (`ExactPlacementSelector`) over the shared privacy-filtered `PlacementAnalysis`.
- **재현 절차**:
  - `mvn -q -DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CampaignBG014AlsPartitionedComputeCostRedTest#singleWorkerFullAlsHasCandidateReachableFedAllPlan test`
- **관측 증상**: planning aborted with `Active exact candidate has no source-reachable row`. The rejected row was the recompiled ALS line-130 transpose: the tentative assignment had fixed its input producer to `CP/LOUT`, whereas the stale tail domain retained only `FED/FOUT/{FULL,BROADCAST}` consumer states without an applicable input relocation.
- **원인 분석**: `orderedAlternatives` assigns one tentative state but deliberately reuses the tail domains computed before that assignment as a cheap ordering hint. `candidateAwarePhysicalEmissionLowerBound` passed those stale domains directly to the strict candidate-row projector. A tentative state can invalidate the reused tail domains, so an infeasible branch was treated as an internal invariant violation instead of receiving the worst ordering hint.
- **해결 요약**: before projecting rows for an incomplete assignment, the lower-bound path now checks the exact domain-aware candidate reachability index. An incoherent stale tail receives `Integer.MAX_VALUE`, which is the correct noncompetitive hint; complete assignments and feasible partial domains retain their existing exact scoring. Candidate-row failures now include the consumer, selected/domain states, anchor, and physical-input evidence.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/ExactPlacementSelector.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CampaignBG014AlsPartitionedComputeCostRedTest.java`
- **검증**:
  - new worker=1 FULL ALS FedAll regression: PASS;
  - complete `CampaignBG014AlsPartitionedComputeCostRedTest`: PASS;
  - `ExactPlacementSelectorTest`: PASS;
  - `PolicyFirstFeasiblePlacementSelectorTest`: PASS.
- **잔여 이슈**: the full four-planner planning-only matrix and distributed Docker canary still have to validate the immutable campaign artifact.
- **잠재 회귀 위험**: returning the worst hint for a domain that is actually feasible could only change traversal order, not the candidate universe or objective, but may increase compile time. The domain-aware reachability contract test and exact selector objective certificates detect semantic drift; compile measurements detect pathological ordering.
- **의사결정 근거**: this is a search-order/lower-bound bug, not a runtime capability or privacy-domain issue; no legal placement was removed.
