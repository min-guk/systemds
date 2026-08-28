# Session Issues — 2026-08-28

## P2 `nrow` was modeled as a full FOUT-to-local payload materialization

- **Status**: implementation fixed; Docker runtime-plan audit pending on the rebuilt stage
- **Applied principle**: planner/runtime alignment and shared pre-selector authority. This is not a candidate-space restriction: the runtime-supported metadata access remains legal, while a nonexistent payload transfer is removed from the shared cost/lowering model.
- **Environment/condition**: P2 preprocessing, `PRIVATE_AGGREGATE`, FedAll, LAN, 4 workers, runtime-plan audit, jar SHA prefix `f9ce3d40`.
- **Reproduction**:
  ```bash
  cd /home/mchoi/g014-p2-pipeline-privateagg-27201f202a-20260827-v1/workspace/experiments
  # Environment points SYSTEMDS_ROOT/CAMPAIGN_SYSTEMDS_ROOT at
  # /home/mchoi/g014-stage-runtime-domain-f9ce3d40b354141d-v4/systemds
  ./run_LAN_docker.sh --runtime-plan-audit --skip-net-check --no-net-cache \
    --workers 4 --dataset P2 --conf mkl-single-pass --net P2_PREP \
    --net-profile lan --continue-on-failure 0
  ```
- **Evidence log**: `/home/mchoi/g014-p2-pipeline-privateagg-27201f202a-20260827-v1/validation-runtime-domain-f9ce3d40-p2-audit-fedall-lan-w4-v1/results/fed4/mkl-single-pass/P2_PREP_dataset-P2_coordinator_mkl-single-pass_p2-audit-f9ce-fedall-v1_lan_coordinator1.log`
- **Observed symptom**:
  - Runtime completed, and every physical HOP state matched: `plannedPhysicalHops=74`, `loweredPhysicalHops=74`, `missingPhysicalHops=0`.
  - Synthetic audit failed with one missing action: `action=62fd6f9030b6ba1e stage=LOCAL ... opcode=prefetch`.
  - The action connected `.builtinNS::m_split` transient read `X` to the predicate `nrow(X)` in `scripts/builtin/split.dml:47`.
  - Runtime explain contained a direct `CP nrow X` for the predicate and no prefetch, while payload-using body/branch consumers did contain `CP prefetch`.
- **Root cause**:
  - `AggregateUnaryCPInstruction` handles `NROW`, `NCOL`, and `LENGTH` on federated matrix/frame objects by reading dimensions from `FederationMap` ranges (`getMaxIndexInRange`); it does not acquire or download the payload.
  - Shared local-materialization projection treated every `<CP,LOUT>` consumer as requiring a local payload.
  - Exact's compiled-transfer factors and both DP recurrence/reconciliation paths inherited the same false FOUT-to-CP download assumption.
  - The binary candidate input state was not itself the error: it represents FED-instruction operand authority. A CP metadata instruction has no worker payload operand. The missing abstraction was an analysis-owned compiled-edge access fact.
- **Resolution**:
  - Added `PlacementAnalysis.CoordinatorInputAccess` and captured `FEDERATION_MAP_METADATA` once for exact matrix/frame input 0 of `nrow`, `ncol`, and `length`.
  - Added an O(1), occurrence-exact producer/consumer query so reconciliation does not rescan the whole program.
  - Made FedAll/Heuristic shared materialization scoring and canonical lowering skip payload materialization only for a selected CP metadata consumer.
  - Made Exact omit the corresponding CP download factor while retaining FED relocation alternatives.
  - Made the local cost-based optimizer ignore the nonexistent materialization conflict.
  - Made DP's occurrence-local recurrence, local conflict reconciliation, clone-family correction, and legacy decision-map reconciliation price this boundary at zero.
- **Modified files**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/LocalMaterializationSelections.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/placement/CoordinatorMetadataInputAccessTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CoordinatorMetadataExactCostTest.java`
- **Verification so far**:
  ```bash
  mvn -q -Dskip.antlr=false \
    -Dtest=CoordinatorMetadataInputAccessTest,CoordinatorMetadataExactCostTest,\
CampaignBG014AbsentLocalMaterializationLoweringRedTest,\
CampaignBG014ExactSingleInputDirectFoutTest test
  ```
  Result: 6 tests passed. The tests prove the exact shared edge classification, preserve ordinary payload unary behavior, remove FedAll lowering action, and remove Exact's download factor/lowering action.
- **Remaining work**:
  - Build a fresh immutable stage.
  - Re-run P2 audit in required order: DP, FedAll, Heuristic, Exact; also audit P1_FULL.
  - Confirm the old missing synthetic action disappears without introducing physical HOP mismatches.
  - Run the full 96-cell matrix and regenerate runtime/compile plots only from complete receipts.
- **Potential regression risk**:
  - Over-broad metadata classification could incorrectly suppress a real payload transfer. Detection: the classifier is a closed whitelist (`NROW`, `NCOL`, `LENGTH`), requires matrix/frame input position 0, and the negative `abs` regression plus runtime-plan audit must stay green.
