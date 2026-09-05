# Session Issues — 2026-09-05

## Candidate-row materialization search expands a whole-program Cartesian product

- **Status**: resolved and verified in immutable stage `7ac6818`; the smoke exposed a
  subsequent independent certificate-reconstruction bottleneck documented below
- **Environment/conditions**: `cofee-fournet-w1357-20260901` at `59819b48d9`; GLM planning-only; FedAll (`mkl-fout`); LAN; one worker; PRIVATE_AGGREGATE input.
- **Reproduction**: run the single GLM planning cell from
  `/home/mchoi/g014-runtime-4net-w1357-20260901-control/run_extra_ml_campaign_20260904_1785d1f.py`
  with planner `FedAll`, or compile the hermetic builtin-GLM fixture in
  `NeutralPlacementGraphUploadRelocationRedTest` through `FedAllPlacementAdapter`.
- **Observed symptom**: the planner did not return after 29 minutes. The outer trace reported
  `decisions=338 groups=198 fixed=91 constraints=182 relocations=532` and stopped after
  `prefixes=128 assigned=308 explored=0 pruned=0 best=-`. Two JVM thread dumps independently
  placed the main thread in `CandidateSelections$Search.solveVariable`, reached through
  `CandidateSelections.selectMaterializationMaximal` while scoring a complete placement.
- **Cause analysis**: after the placement selector chooses a complete placement assignment,
  candidate-row selection recursively enumerates the product of every multi-row consumer.
  Relocation, local materialization, and derived-FOUT effects are usually confined to small
  independent subsets, but the current recursion does not use that factorization. Consequently,
  independent binary choices multiply globally even though their objective contributions do not
  interact.
- **Resolution**:
  1. Expose exact, immutable interaction identities from the relocation and local-materialization
     scorers. The identities cover relocation anchor constraints, action suppression, shared
     physical relocation emissions, local producer materialization, and shared derived-FOUT
     actions.
  2. Build connected components over variable candidate consumers. An edge exists only when two
     consumers can affect the same exact constraint or physical-emission factor.
  3. Keep singleton rows fixed, solve each interacting component exactly, and retain the existing
     physical-emission objective and canonical row tie-break. This changes search organization,
     not the candidate domain, privacy filtering, legality rules, or objective.
  4. Added a bounded exhaustive oracle regression over the actual builtin-GLM candidate domain.
     The production assignment exposes 1,860 consumers, 30 variable domains, and a saturated
     global product of 2,147,483,648 rows. The regression leaves a genuine product of at most 64
     rows open, pins the remaining consumers to a certified legal row, and compares every output
     of the factorized solver with an independent Cartesian oracle.
- **Files changed**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/RelocationSelections.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/LocalMaterializationSelections.java`
  - candidate-selection regression tests under
    `src/test/java/org/apache/sysds/hops/fedplanner/placement/`
- **Verification**:
  - `mvn -q -Dtest=NeutralPlacementGraphUploadRelocationRedTest#candidateMaterializationSearchMatchesBoundedExhaustiveOracle test`
    passed; the test itself completed in 14.578 s, including builtin expansion, a full
    analysis-aware policy selection, and the bounded independent oracle.
  - `mvn -q -Dtest=ExactPlacementSelectorBranchAndBoundTest test` passed 12/12.
  - A combined targeted run passed 28/28 relevant tests across relocation anchors, CP/FOUT and
    local-materialization authority, policy selection, Exact selection, GLM function-boundary
    preservation, and the new exhaustive oracle.
  - `mvn -q -DskipTests package` passed.
  - A direct hermetic GLM diagnostic measured the analysis-aware first-feasible selector at
    1.211 s after the change; the same path previously remained inside the candidate product for
    more than 29 minutes before manual interruption.
  - An additional combined test invocation included
    `DerivedFoutMaterializationAuthorityTest`, whose fixture attempted live privacy RPCs to
    `localhost:1234/1235` and failed closed. That pre-existing, non-hermetic test was excluded from
    the authoritative regression batch in accordance with the repository instruction to ignore
    public/live privacy cases; all other 26 tests in that invocation passed.
- **Immutable-stage evidence**:
  - commit `7ac681894383064c19a491634cb3757d63471c0d` was built into
    `/home/mchoi/cofee-w1357-stage-20260905-7ac6818` and deployed with matching JAR and
    manifest hashes to `so002`--`so009` (never the proxy `so001`);
  - GLM/LAN/worker=1/FedAll passed the candidate-row component solve, but then remained in
    the downstream relocation certificate search. This establishes that the first Cartesian
    product was removed rather than merely shifted within `CandidateSelections`.
- **Potential regression risk**: omitting a shared factor would make sequential component choices
  non-exact. Detect this through exhaustive-oracle equality tests and canonical post-selection
  validation of relocation/local/FOUT emission counts.
- **Decision basis**: changed only the exact search decomposition. Runtime capability, privacy
  constraints, candidate availability, and planner policy remain authoritative and unchanged;
  no candidate, placement state, or physical movement was removed.

## Final relocation certificate reconstruction expands independent demands globally

- **Status**: resolved in source; successor immutable-stage GLM planning smoke pending
- **Environment/conditions**: immutable stage `7ac6818`; GLM planning-only; FedAll; LAN;
  one worker; the candidate-row component solve above already completed.
- **Observed symptom**: after approximately 157 seconds the coordinator still had not emitted
  the compile timer. A live JVM stack showed hundreds of recursive frames in
  `RelocationSelections$Search.solve` (then lines 1543/1579), called from
  `RelocationSelections$CandidateProblemIndex.select`, then
  `CandidateSelections$Search.requireBest`, while scoring the selected placement.
- **Cause analysis**: the indexed scorer correctly factors candidate-row optimization, but its
  final canonical certificate reconstruction rebuilt all selected relocation demands and ran a
  second global Cartesian recursion. Most demands are independent. Only demands sharing the same
  physical consumer constrain a common anchor, while only demands capable of emitting the same
  physical relocation share the minimum-emission objective. Multiplying all other demands is
  unnecessary and does not change the exact result.
- **Resolution**:
  1. Build exact connected components over relocation demands using the authoritative consumer
     identity and physical-emission identity already assigned by `RelocationOrder`.
  2. Solve every component with the existing exact recursion, then merge component certificates
     in global canonical-rank order and recompute the unique physical-emission count.
  3. Retain the original global recursion for constrained `requiredEmitted` queries and for a
     single component. Deterministic components now also materialize their canonical rank vector
     so they can be merged without a special case.
- **Correctness argument**: anchor feasibility is local to a consumer component; emission cost is
  the cardinality of physical emission identities and therefore additive between components that
  share no such identity; and the global canonical tie-break is the ordered merge of the
  lexicographically minimal subsequence from each independent component. Thus the decomposition
  preserves the feasible domain, exact objective, and canonical result.
- **Files changed**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/RelocationSelections.java`
- **Verification**:
  - the bounded builtin-GLM exhaustive oracle still passes and compares the production
    componentized path against the independent original global relocation search;
  - `RelocationSelectionsPhysicalAnchorTest`, `ExactPlacementSelectorBranchAndBoundTest`, and
    `PolicyFirstFeasiblePlacementSelectorTest` pass together;
  - the wider legacy fixtures that contact `localhost:1234/1235` fail closed when no privacy
    workers are running. This is expected non-hermetic fixture behavior and is not treated as
    evidence about this change.
- **Remaining issues**:
  - package and commit the source change;
  - build and deploy a successor immutable stage;
  - rerun GLM planning-only for FedAll, Heuristic, and Exact, inspect privacy/feasibility receipts
    and plan fingerprints, and only then resume the remaining planning campaign.
- **Decision basis**: this is an exact factorization of certificate search, not a heuristic,
  top-K cap, candidate-space restriction, DML rewrite, or runtime fallback.
