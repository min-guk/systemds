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

- **Status**: resolved and verified in immutable stage `71019ad`; the smoke exposed a
  separate hot-scorer recursion documented below
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
- **Immutable-stage evidence**:
  - commit `71019ad7133566abf356d101083d381017545cc6` was built into
    `/home/mchoi/cofee-w1357-stage-20260905-71019ad` and deployed with manifest
    `f5cd7f9342d6a62994642371ad202b0c14fc775abfdf079072f91ed1167c3700` to
    `so002`--`so009`;
  - GLM/LAN/worker=1/FedAll passed final certificate reconstruction but remained in the
    incremental exact emission scorer, proving the certificate recursion itself was removed.
- **Decision basis**: this is an exact factorization of certificate search, not a heuristic,
  top-K cap, candidate-space restriction, DML rewrite, or runtime fallback.

## Incremental exact emission scorer multiplies independent alternative demands

- **Status**: resolved and verified in immutable stage `0e39413`; the smoke exposed
  avoidable deterministic certificate overhead documented below
- **Environment/conditions**: immutable stage `71019ad`; GLM planning-only; FedAll; LAN;
  one worker; both preceding componentized searches already completed.
- **Observed symptom**: after 106.9 seconds at approximately two CPU cores, a live JVM stack
  showed 24 recursive frames in `RelocationSelections$ExactEmissionScorer.solve`, called from
  `minimumPhysicalEmissionCount`, `CandidateSelections$Search$ComponentSearch.solve`, and the
  outer `ExactPlacementSelector` leaf scorer. The campaign was interrupted and all containers
  were cleaned before changing source.
- **Cause analysis**: the allocation-free hot scorer maintained singleton emissions and anchor
  feasibility incrementally, but then recursively multiplied every selected alternative demand.
  Its choice interactions are the same exact factors as certificate reconstruction: alternatives
  interact only when they constrain the same consumer anchor or can emit the same physical
  relocation. Receipt-based suppression is already fixed at each scorer invocation and therefore
  creates no additional choice-variable edge.
- **Resolution**:
  1. Reuse preallocated primitive arrays to form a DSU over active alternative demands, preserving
     the scorer's allocation-free hot-path contract.
  2. Connect demands by consumer identity and by currently emitting physical-relocation identity.
  3. Solve each component with the existing exact recursion against the fixed singleton-emission
     baseline and sum its incremental minimum. Components share neither constraints nor variable
     emission identities, so this is an exact additive decomposition.
- **Files changed**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/RelocationSelections.java`
- **Verification**:
  - the bounded builtin-GLM exhaustive oracle passes against the independent global Cartesian
    relocation oracle;
  - relocation-anchor, Exact-selector branch-and-bound, and policy-selector regressions pass;
  - source packaging remains to be rerun after this change.
- **Immutable-stage evidence**:
  - commit `0e394139c37bd7781fc32d59a933218b21b66f72` was built into
    `/home/mchoi/cofee-w1357-stage-20260905-0e39413` and deployed with manifest
    `c0320fc2c86c1f7290410926472777fc7c2e118fd0ebcf22db0ab7bb502a2290` to
    `so002`--`so009`;
  - the hot scorer recursion disappeared from the live stack. The remaining time was spent in
    canonical relocation certificate reconstruction for tiny, mostly deterministic components.
- **Decision basis**: no planner policy, candidate, placement state, privacy rule, runtime
  capability, objective, or canonical certificate was changed; only independent exact factors
  are evaluated separately.

## Deterministic relocation components invoke millions of generic exact-search objects

- **Status**: resolved in source; successor immutable-stage GLM planning smoke pending
- **Environment/conditions**: immutable stage `0e39413`; GLM planning-only; FedAll; LAN;
  one worker; 338 decisions and 198 exact outer-search groups.
- **Observed symptom**: after roughly four minutes, the outer selector had visited 524,288
  prefixes and 17,926 complete placements. Candidate search had reached ID 16,384, while
  relocation certificate searches exceeded ID 2,097,152. Power-of-two trace samples were
  overwhelmingly one-demand deterministic searches; live stacks showed only one or two
  `RelocationSelections$Search.solve` frames, with time in deep identity hashing and canonical
  worker-address comparison rather than in a large interacting choice component.
- **Cause analysis**: componentization was exact, but every singleton/deterministic component was
  still wrapped in a fresh generic `Search`, `AnchorBindings`, maps, sets, ranked-choice lists, and
  canonicalization. Repeating that fixed work for more than two million components dominated the
  outer exact placement search even though no branch existed.
- **Resolution**:
  1. A one-demand component now selects directly by exact objective: prefer a non-emitting option,
     then the canonical choice rank.
  2. A multi-demand component with one option per demand now validates common physical-anchor
     compatibility once and emits its canonical receipts directly.
  3. Only components containing a genuine interacting alternative instantiate the original exact
     recursive search. Combined action/physical-emission deduplication and final canonical order
     remain unchanged.
- **Files changed**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/RelocationSelections.java`
- **Verification**:
  - builtin-GLM bounded exhaustive oracle passes;
  - relocation-anchor, Exact-selector, and policy-selector regressions pass together;
  - package and immutable-stage smoke remain to be rerun.
- **Decision basis**: deterministic evaluation is the closed form of the same exact recurrence;
  no legal candidate or physical movement is removed.

## Inlined function input provenance is incorrectly exposed as an emitted decision

- **Status**: initial null-dependent fix rejected in review; structural replacement and stronger
  regressions in progress. No version of the rejected fix was deployed.
- **Environment/conditions**: stage `58291b2`; GLM / LAN / one worker;
  `COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS`; private-aggregate input. No DML changes.
- **Observed symptom**: the single-pass diagnostic finished its candidate search but failed
  during normalized emission projection with `DP synthetic boundary has invalid exact authority
  cardinality: kind=FUNCTION_INPUT ... authorities=0`. The occurrence is the inlined
  `get_trust_boundary_point` input `pp`, sourced from `pp_CG` in `glm.dml:1001-1003`.
  The failed process wall time (11.41 s) is **not** a successful compilation/planning measurement.
- **Cause**: commit `59819b4` correctly allowed a named actual HOP to be absent after inlining
  and expression substitution, but still created a CP/LOUT **decision** for the now carrierless
  synthetic input. Its real expression remains in the rewritten HOP DAG; no separate
  FunctionCallCP input exists at this inlined site. Later normalized-plan validation correctly
  rejects an emitted boundary with no compiler-owned source authority.
- **Initial fix (superseded, not deployed)**:
  1. Lock the failure using the real builtin-GLM hermetic fixture. The new test failed before
     the implementation change and passes afterward.
  2. Only in the compiler-owned **inlined** call path, retain an absent rewritten input as a
     trace-only identity node with no placement domain and the explicit reason
     `NON_EMITTED_REWRITTEN_FUNCTION_INPUT`.
  3. Leave ordinary rewritten HOP nodes, candidate rules, value edges, privacy propagation,
     live input boundary constraints, and every non-inlined FunctionOp boundary unchanged.
  4. Validate the complete normalized single-pass result, not merely raw selector assignment.
- **Files**: `NeutralPlacementGraph.java`, `NeutralPlacementGraphBuilder.java`,
  `NeutralPlacementGraphUploadRelocationRedTest.java`.
- **Verification so far**:
  - RED: new GLM test rejected the emitted authorityless input before the source fix.
  - GREEN: the same test passes, including normalized selected-emission coverage.
  - Combined batch: 36/36 tests pass across three GLM tests, relocation anchors, exact-policy
    branch-and-bound, first-feasible policy selection, and shared privacy analysis.
  - Three additional old null/unnamed-FunctionOp fixture tests fail while creating frequency
    authority (`PLACEMENT_FUNCTION_ROOT_UNPROVEN` / null input name). Re-running those five
    tests with the **unchanged `58291b2` JAR** reproduces exactly the same three failures;
    they are not introduced here. Baseline log:
    `/home/mchoi/g014-runtime-4net-w1357-20260901-control/baseline-invalid-boundary-fixtures-58291b2.log`.
- **Review finding and corrected design**: a missing named HOP alone does not prove a valid rewrite,
  so null-dependent suppression is unsound. The correct distinction is structural: **all**
  compiler-owned AST-inlined input boundaries are provenance, not runtime input operations.
  `DMLTranslator.processExpression(DataIdentifier)` returns the existing `ids` HOP; the binding
  assignment stores that same RHS HOP. There is no FunctionOp or FunctionCallCP input carrier.
  The neutral builder separately covers all surviving compiled HOPs and `data-input` edges.
  Inlined input markers have no outgoing `function-formal-input` replay edge. They must therefore
  all be non-emitted, whether their old lexical names survive or not. Preserve their exact context,
  optional incoming argument constraint, but no executable placement anchors; use
  `NON_EMITTED_INLINED_FUNCTION_INPUT`.
  Non-inlined FunctionOp inputs continue to own real boundary state and fail closed on missing
  exact authority. Inlined output handling is unchanged.
- **Rejected alternative**: propagating new RHS tokens through every compiler rewrite, CSE,
  constant folding and clone would add broad redundant machinery merely to certify a nonphysical
  marker. The revised representation needs no such new rewrite/runtime mechanism.
- **Residual risks**: trace markers must not become an alternative source of candidate or movement
  state. Regressions must cover present/missing lexical names, literal/scalar/matrix arguments,
  real HOP privacy/candidate preservation, nested contexts, and ordinary function fail-closed checks.
- **Decision basis**: this removes a spurious decision with no emitted runtime carrier, not a
  runtime-supported state. It does not add CP/FOUT to transients or recompilation, waive privacy,
  introduce fallback, or alter any selector objective.

## Remaining legacy exact-policy FedAll search and explicit single-pass comparison

- **Status**: diagnosis complete; single-pass end-to-end validation in progress.
- `58291b2` eliminated the repeated deterministic relocation certificate searches (roughly
  eight times fewer generic certificate-search invocations at the same outer-search prefix).
  Packaging and immutable stage deployment succeeded, correcting the pending status above.
- The GLM legacy `COMPILE_FED_ALL` diagnostic still visits more than one million outer prefixes.
  Its component has 338 decisions / 198 variable groups. A sampled stack is now in
  `ExactPlacementSelector.candidateAwarePhysicalEmissionLowerBound` and candidate reachability,
  not the eliminated global candidate/relocation Cartesian products. It globally maximizes FED,
  then FOUT, then minimizes physical transfers and canonical tie-breaks; this exact policy
  optimization can remain exponential even after removing redundant inner products.
- The cost-based planner labeled **Exact** is a different optimizer; these observations about
  exact-policy FedAll must not be attributed to that cost-based optimizer.
- The prior smoke was interrupted and its containers cleaned to run a sequential, uncontended
  single-pass diagnostic on the same authenticated topology. No runtime cell was run or replaced.
- The existing `mkl-single-pass` config provides the previously implemented and validated
  frequency-aware first-feasible FedAll policy. It consumes the same shared filtered domain but
  is **not guaranteed to return the global lexicographic optimum** of legacy `mkl-fout`.
  The runner default and published historical results must not be silently relabeled. Any new
  comparison uses an explicit selector variant in the campaign identity and separate output root.
- GLM exact-policy did not complete, so plan parity or a numerical speedup against it cannot yet
  be claimed. Successful first-feasible planning, legality, and privacy are separate gates from
  equality to an unavailable exact-policy reference.

### Structural inlined-input correction: final safety review and regression outcome

- All AST-inlined input markers are non-emitted, have no selectable alternatives, and carry no
  placement anchors. Optional argument edges preserve trace provenance only. Anchor consumers
  can resolve all graph nodes, so keeping anchors on these markers would incorrectly assign
  executable authority to the call-site projection rather than the real argument HOP.
- Removed an invalid exclusivity check on inlined call physical RHS nodes. Nested calls such as
  `outer(A) { B=inner(A); }` legitimately share one compiled RHS. The function key, exact
  call-statement position, and boundary argument/output index continue to distinguish trace keys;
  graph duplicate-key validation and repeated-function DISTINCT_CONTEXT remain enforced.
- Added compiler-driven matrix/scalar/literal and nested-call regressions, PRIVATE and
  PRIVATE_AGGREGATE source/body/actual-TRead privacy and physical-edge checks, and preserved real
  FunctionOp emitted-input/missing-authority fail-closed tests. Inlined trace anchors must be empty
  while the real private federated source retains executable anchors.
- A test originally assumed the main-block federated source is the inlined body’s immediate HOP
  input. Fresh compiler evidence showed the exact inter-block TRead input; the assertion now
  checks that actual compiled edge and its unchanged propagated privacy instead. This was a test
  fixture error, not a source privacy fix.
- Final targeted batch: **43 tests, zero failures/errors/skips** after the anchor correction;
  `git diff --check` passes and the independent code review approves. Evidence:
  `/home/mchoi/g014-runtime-4net-w1357-20260901-control/inlined-markers-final-regressions-20260905.log`.
  Packaging and immutable-stage GLM smoke follow. The prior null-dependent JAR is superseded.
- PRIVATE_AGGREGATE parity is tested against the equivalent direct `F+1` physical HOP. The
  repository’s pre-existing policy permits coordinator-private local intermediates at this level;
  the inlined-marker change neither expands nor closes that domain. This is not a claim that PA
  data never leaves workers. Strict PRIVATE remains FED/FOUT-only. Broader PA threat-model
  semantics are a separate audit, not a policy change hidden inside this representation repair.
