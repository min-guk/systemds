# Session Issues — 2026-09-05

## Fixed-invocation dead branches were charged positive execution cost

- **Status**: implemented; fresh integrated regression 104/104 PASS. Immutable Docker promotion and physical-plan comparisons remain pending; no repaired-frequency runtime result is approved yet.
- **Conditions**: GLM PRIVATE_AGGREGATE 50,000 × 2,100, dfam=2/link=2, fixed campaign invocation. Commit 2d2f7f3 already repairs the independent Exact factor-array/search resource defect.
- **Observed symptom**: the 2d2f7f3 Docker planning trace assigns positive frequency and cost to `straightenX` / glm.dml:1065 even though fixed invocation facts prove `desired_eta=0` and the `desired_eta != 0` branch does not execute. The old objective 111441.826853 is in milliseconds, not seconds. Incident factor sums overlap and cannot be added across decisions.
- **Cause**: shared occurrence-frequency analysis assigns 0.5 to both IF arms without proving invocation-specific scalar predicates. Downstream strict cost consumers originally reject zero; legacy forwarding and local policy defaults can revive a known-zero demand as one execution.
- **Repair**: prove only safe occurrence-/call-scoped scalar predicates; retain every occurrence, candidate, privacy constraint, and hard compatibility factor. Propagate zero for unreachable regions and the full parent weight for the proven reachable arm; unknown predicates retain existing expectations. Preserve definition-time and call-time scalar bindings, unknown branch joins, loop-carried unknowns, typed runtime arithmetic, and nested invocation identity. Accept proven zero in the shared DP/Exact physical objective, filter zero events only from cost unions, and distinguish absent policy evidence from present zero-demand evidence.
- **Diagnostics**: trace-only DP/Exact records enumerate each original physical cost contribution once with its full encoded identity, scope positions, millisecond value, and binary64 bits; completion is emitted only if the independently accumulated objective matches the selected canonical objective bits. Solver auxiliary and incident costs are not additive attribution.
- **Files**: `OccurrenceExecutionFrequencyFacts.java`, `ExactPhysicalCostModel.java`, `FederatedPlanExact.java`, `FederatedPlanLocalCost.java`, `PolicyFirstFeasiblePlacementSelector.java`; focused frequency/zero/trace/policy tests.
- **RED evidence**: control `ZERO_FREQUENCY_COST_RED_20260905.log` (three failures of four tests) and `ZERO_POLICY_FREQUENCY_RED_20260905.log` (new zero-policy test fails, eight older tests pass). Focused frequency tests independently reproduce the previous 0.5 assignment.
- **Residual risk**: a false constant proof could alter planner choices; unsupported values must remain unknown rather than be guessed. No candidate deletion or legality relaxation is authorized, even for a zero-cost occurrence. Diagnostic planning time includes logging and is not an overhead-free compile benchmark.
- **Next validation**: integrated regression suite, actual builtin GLM private-aggregate fixture, immutable new-stage Docker planning, all 576 authenticated old/new physical comparisons, and only then changed-cell runtime replay.
- **Decision basis**: repair shared invocation-frequency and cost evidence, not runtime capability, privacy policy, DML, or measurement ordering.

## Historical observer stage contains volatile Python bytecode

- **Status**: resolved in new immutable observer v3 stages; v2 originals and failed
  partials preserved.
- **Symptom/cause**: full-manifest verification on so003--so005 rejected generated
  `__pycache__/*.pyc` bytes after cloning a historical stage. These files were
  already excluded from executable-input authority but were incorrectly included
  in the observer's complete-file manifest. This was an artifact construction
  defect, not a SystemDS planner/data difference.
- **Resolution**: record and remove only the exact generated cache-path set from
  the clone; never modify source-stage hard links in place. Rebuild as v3; verify
  all 4,420 remaining files and the unchanged 3,960 executable inputs. Preserve
  the authenticated failed v2 partials by atomic rename, then deploy to
  so002--so009 (never proxy so001).
- **Files/evidence**: control `build_trace_observers_20260905.py`,
  `deploy_trace_observers_20260905.py`, their tests, and
  `TRACE_OBSERVER_V3_POSTDEPLOY_VERIFICATION_20260905.json`.
- **Verification**: 11/11 targeted tests; 7/7 remote hosts, 14/14 observer stages
  passed complete-file/JAR verification. Original JARs stayed unchanged.
  Original/observer Docker planning canaries for PCA/FedAll and LM/Heuristic
  emitted exactly equal legacy emission records (188 and 220 respectively).
  Both observer traces also passed the complete-authority comparator's self
  check; all four canaries executed zero runtime work and produced no outputs.
- **Residual risk**: generated caches can reappear during ordinary Python use;
  they remain outside executable-input authority. No permission to ignore an
  actual source, data, harness, JAR, or complete physical-authority mismatch.
- **Decision basis**: repair reproducible artifacts without changing algorithms,
  candidate space, privacy, DML, runtime configuration, or archived measurements.

## Four wider Exact regressions also fail in the immutable baseline JAR

- **Status**: baseline failures authenticated, then all four historical test
  contracts audited and repaired against current physical-emission semantics.
  Focused tests 6/6 and final integrated suite 104/104 PASS; this is not a waiver.
- **Conditions**: broader fedExact batch reported 96/100 passing. The failing
  tests are sparse function-boundary cost, B09 compact clone predecessor identity,
  L2SVM internal relocation-anchor multiplicity, and StepLM recompiled REFED count.
- **Verification**: run the same compiled test fixtures with immutable `211e8f8`
  JAR SHA `2b106c1fbadd767fccf5e0e2fb46e9c49e8fb5d9acee676ea2db038a2c0dd87c`
  replacing `target/classes` in the JUnit classpath. Required JVM module/open flags
  are taken from `pom.xml`. Sparse cost reproduces expected 1.0008316040039062
  versus 1.0004158020019531; B09 reproduces compact `input-0:M1#0@main/4:ORDINARY`;
  StepLM reproduces four REFED instructions. L2SVM in an isolated baseline JVM
  reproduces the same independent-anchor assertion as the new implementation.
- **Evidence**: control `GLM_BASELINE_FOUR_FAILURES_211_MODULES_20260905.json`,
  `GLM_BASELINE_L2_ISOLATED_211_20260905.json`, their SHA-bound logs, and
  `target/glm-exact-repair-evidence/{reduced,unreduced}`.
- **Diagnostic pitfalls**: an initial manual command omitted the vector module
  and produced six linkage errors; this is retained but is not regression
  evidence. Running the L2SVM fixture after other classes in one manual JVM also
  exposes unreset static receipt state; the isolated invocation eliminates that
  confounder.
- **Resolution/risk**: the follow-up audit is recorded in
  `EXACT_HISTORICAL_REGRESSION_CONTRACT_AUDIT_20260905.md`. Tests now derive
  worker-aware sparse costs, actual clone value-version identity, emitted versus
  direct relocation obligations, and runtime REFED authorization from committed
  physical actions. Production semantics were not changed to satisfy stale
  expectations. New backend runtime evidence is still required.
- **Decision basis**: prioritize evidence over attributing every pre-existing
  test failure to the GLM repair; preserve privacy and physical feasibility gates.

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

## GLM cost-based Exact: physical cost fingerprint exceeds JVM array limit

- **Status**: reproduced in authenticated Docker planning-only; structural repair in progress.
- **Environment**: stage `211e8f8`, private-aggregate GLM / LAN / one worker.
  FedAll explicit single-pass and Heuristic both compiled successfully with zero runtime;
  Exact token `8cb4c3dbea47b27caa29` failed before optimization.
- **Symptom**: `OutOfMemoryError: Requested array size exceeds VM limit` in
  `ExactPhysicalCostModel.appendPhysicalFactorValues` while constructing the cost certificate.
- **Cause**: complete Cartesian cost tables were appended into one StringBuilder. Reusable
  movement is a maximum/union over consumer demands, yet its scope was represented as a
  dense clique both for hashing and for Exact variable elimination. A larger heap cannot
  remove Java's maximum-array-length limit.
- **Repair boundary**: retain the canonical objective and full original physical decision
  domains; stream ordinary fingerprints, bind structured cost coefficients directly, and
  use exact deterministic auxiliary representations for reusable max-demand factors.
  Auxiliary variables must not leak into placement/selection authority. Dense solver
  limits remain fail-closed, not approximation or candidate pruning.
- **Evidence**: `/home/mchoi/g014-extra-ml-planning-20260905-211e8f8/` and its authenticated
  diagnostic coordinator archive. No runtime measurement was produced by the failed cell.
- **Residual risk**: eliminating one huge fingerprint does not prove the remaining Exact
  induced width is affordable. An actual GLM compile and canonical-cost equality are required.

## Physical comparison: incomplete runtime registry trace authority

- **Status**: trace schema repaired; comparison/controller safety review remains in progress.
- **Problem**: flattened REFED consumer lists lose grouping by AuthoritySpec and planner
  action keys. Derived-FOUT actions were opaque hashes. Such traces cannot prove complete
  physical equality across source revisions; unknown evidence must not authorize reuse.
- **Resolution**: add trace-only Base64URL JSON carrying all applied per-authority fields
  and full derived-action signatures, preserving existing fields for diagnostics. No
  selection, lowering, registry application or runtime behavior changes.
- **Files**: `PlacementEmissionTransaction.java`, `PhysicalEmissionTraceFormatter.java`,
  `PhysicalEmissionTraceFormatterTest.java`, `docs/PHYSICAL_EMISSION_TRACE_SCHEMA_20260905.md`.
- **Verification**: RED missing formatter symbols, then **3/3 formatter tests pass**; parent
  source review and `git diff --check` pass. Formatting is inside the existing trace-enabled
  guard, so logging-free runtime does not invoke the new serializer.
- **Remaining work**: authenticated trace-only observer backports to old baseline binaries,
  same-schema comparison, checkpoint/driver/input/remote-archive provenance regression.
- **Risk**: process-local Hop IDs cannot be compared literally or dropped; comparisons must
  preserve their canonical physical-sharing equivalence classes and registry consumer groups.

## Retracted materialization-lifetime hypothesis: prefetch count is not download count

- **Status**: disproven hypothesis; no runtime/cost/lowering repair warranted.
- Initial aggregate instruction-count inspection suggested that LM/LogReg/L2SVM downloaded
  immutable inputs once per loop iteration. Per-instruction timing and `CacheableData`
  refute this: only the first acquire is seconds-scale; later acquires are microsecond cache
  hits. Function argument aliasing and pinned MatrixObject lifetime preserve this reuse.
- Do not hoist DML/LOPs or multiply the one-time materialization cost by prefetch execution
  count. The first download can be slow, but attributing that to repeated network traffic
  is incorrect. Detailed correction: `docs/MATERIALIZATION_LIFETIME_REPAIR_20260905.md` and
  control `REMAINING_ML_ANOMALY_DIAGNOSIS_20260905.md`.
- 576 original successful runtime cells, 128 pairwise reversals and 30 Exact upward worker
  transitions remain inventoried. Ordering is diagnostic, not a runtime invariant. No
  existing result is discarded/replaced solely because its numerical order is unexpected.

## GLM Exact repair: final source verification and promotion boundary

- **Status**: implementation and independent code review complete; immutable Docker
  planning canary is the next gate, not yet a runtime-success claim.
- **Resolution**: replace Cartesian fingerprint expansion with preflighted frozen
  ordinary factors and exact structured max-demand descriptors. Exact uses deterministic
  auxiliary chains, sound unary/binary arc consistency and complete factor-observation
  equivalence classes. Neither privacy/physical domains nor production factor budgets
  are relaxed. Original canonical cost is independently checked by raw binary64 bits.
- **Files**: `fedExact/ExactPhysicalCostModel.java`, `ExactCategoricalSolver.java`,
  `ExactMaxDemandFactorDecomposition.java`, `ExactPhysicalReducedSolver.java`,
  `ExactPhysicalOptimizer.java`, `FederatedPlanExact.java`; regression tests and
  `docs/GLM_EXACT_COST_FACTOR_REPAIR_20260905.md` describe the proof and scope.
- **Fresh parent verification**: 64 tests, zero failures/errors/skips, including actual
  built-in GLM optimize/canonical-certify/project (51.150 s), shared privacy, movement
  legality, inlined boundaries, forced-state audit, certificate and trace formatting.
  Evidence: `/home/mchoi/g014-runtime-4net-w1357-20260901-control/INTEGRATED_FINAL_REGRESSION_20260905.json`
  and `integrated-regression-final-20260905.log`; `git diff --check` passes.
- **Known validation gaps**: four wider tests fail both here and against the immutable
  pre-change `211e8f8` JAR. The sparse-boundary expected price, normalized B-09 predecessor,
  L2SVM multiple-emission-anchor assertion, and StepLM REFED-count assertion are retained,
  not weakened. Baseline reproduction proves they are not newly introduced by this
  repair; it does not establish that each old expectation or behavior is correct.
- **Potential regression**: exact-equivalent classes may pick another equal-cost physical
  representative. Full-authority old/new planning comparison, not objective equality alone,
  controls selective runtime reruns. Nontrivial control-event unions still use the original
  bounded exact evaluator and can fail explicitly on an intractable induced width.
- **Experiment provenance**: old-observer and extra-ML stages are separate. Baseline
  comparison preserves all original harness/data/config bytes; extra ML deliberately
  retains its authenticated GLM/GNMF/GMM harness version. Historical FedAll exact-policy
  is not relabeled as the explicitly selected new-ML single-pass variant.


## PRIVATE_AGGREGATE raw release and GNMF parity failure — newly confirmed

- **Status**: diagnosis confirmed; repair design and regressions in progress. All experiment queues stopped diagnostically.
- **Symptom/evidence**: authenticated source5215f1a GNMF LAN/w1 FedAll runtime168.639s contains full original 50000x2100 X prefetch, REFED, and second shifted-X prefetch; only6 FED instructions, whole GNMF loop CP. Source/control report `GNMF_FIRST_CELL_DIAGNOSIS_20260905.md` binds all4 planning/runtime archives by SHA/size.
- **Cause**: `ExecPlacementPolicy` explicitly permits CP/LOUT for effective PRIVATE_AGGREGATE; shared node closure categorically denies local only for PRIVATE. This preserves propagation identity but violates aggregate-only origin-bound release. Existing permissive tests are not proof of correct privacy semantics.
- **Decision boundary**: fix common candidate/value/input-movement authority, not GNMF-specific forced placement, data/public relabeling, runtime fallback, or opaque cost penalties. CP call placeholders must be distinguished from real local data release. Public aggregate output must not authorize collecting protected inputs first. All selectors must consume the same corrected domain.
- **Separate defect under diagnosis**: GNMF FedAll/Heuristic/Exact numeric models differ even at7 significant digits; builtin initialization has unseeded rand. Do not attribute differences to random seeds until verified, and do not relax numeric gate. Runtime driver stopped after Exact cell at11:21:51UTC.
- **Verification plan**: RED raw-release tests (source/elementwise/transient/function/branch, public aggregate raw-input CP, relocation) then common gate repair; retain safe FED aggregate release and runtime-supported federated states, reject impossible plans before execution. Independently diagnose initialization and regression-lock reproducibility.
- **Residual risk**: closing invalid raw release may reveal missing legitimate FED control-flow/function candidates. Repair candidate/representation gaps, do not reopen unsafe materialization. Existing576 and new measured cells remain preserved but require re-certification under new authority before privacy-safe final claims.

## Performance repair acceptance categories — latest user clarification

- **Status**: 128 planner-pair reversals are open defects to resolve, not closed as normal variation; 30 Exact upward worker transitions require separate evidence-based bug/non-bug classification. These are comparison cases, not 158 independently proved implementation faults.
- **Closure**: identify the causal model/space/selector/lowering/runtime/measurement defect, apply a general correction, then authenticate planning and rerun physically changed cells. Do not fabricate costs, force rankings, or discard successful raw measurements merely for their order.
- **Scaling**: a legitimate increase in synchronization, fan-in, computation or materialization with more workers may explain a scaling increase; close as non-bug only with code/plan/runtime evidence, not an unsupported worker-times-RTT assumption.
- **Tracker**: `/home/mchoi/g014-runtime-4net-w1357-20260901-control/PERFORMANCE_REPAIR_TRACKER_20260905.json` retains all 128+30 input rows and explicit distinct statuses without changing source CSVs.

## Protected CFG closure and DP loop-join dispatch

- **Status**: shared CFG repair and DP dispatch implemented; the new branch/loop/function
  regression passes all four production selectors. Broader regression and Docker
  recertification are not complete; no runtime reversal is closed by this unit result.
- **Symptom**: after enforcing origin-bound PRIVATE_AGGREGATE, a function-return loop
  failed because previously replayed transient reads retained stale candidate domains.
  After that repair, DP alone still rejected the post-loop read even though all three
  reaching B writes had native FED/FOUT ROW states.
- **Cause**: shared function closure could change candidate facts/anchors without a new
  Node identity, while CFG replay did not revisit prior reads. In DP,
  `canTransientReadReuseMatchedFoutWrite` rejected arithmetic updates depending on B
  before dispatch to the existing cycle-aware multi-definition join path.
- **Resolution**: replay CFG candidates when function/physical closure changes exact
  inputs, replace stale rows rather than append them, and restore pre-replay authority
  if a prior exact proof is lost. Loop seeds are provisional and every backedge must
  subsequently certify the same physical tuple. Recompute inherited anchors from
  current inputs. DP now collects every exact source domain and invokes its joined
  transient routine before applying single-source self-dependency checks; exact input
  authority, common placement equality and candidate receipts remain mandatory.
- **Files**: `NeutralPlacementGraphBuilder.java`,
  `fedDp/FederatedPlannerDpCostEnumerator.java`,
  `PrivateAggregateFourPlannerContractTest.java`, `PrivacyMovementCertificationTest.java`.
- **Fresh evidence**: control `DP_JOIN_ALL4_LOOP_PASS_SAMEPOOL_RED_20260905.xml`
  records the full loop/branch/function method PASS for FedAll, Heuristic, DP and Exact;
  raw protected print rejection also PASS. The independent same-pool positive still
  fails before selection and is the next separate bug, not a reason to weaken privacy.
- **Residual risk**: provisional anchors must not survive incompatible loop updates.
  The square-transpose negative preserves matrix shape while changing ROW/COL, and
  the unrelated-local-backedge negative checks that external seed authority alone is
  insufficient. The strengthened fixtures require fresh combined validation.
- **Decision basis**: restore graph-proven legal FED candidates and route DP to its
  existing CFG recurrence; no CP/FOUT transient states, fallback, or privacy relaxation.

## Independent aligned federated sources lose a legal native binary candidate

- **Status**: reproduced; correction under implementation in shared graph construction.
- **Reproducer**: two independent PRIVATE_AGGREGATE federated matrices A/B with identical
  worker endpoints and ROW ranges but different file paths, then `C=A+B; print(sum(C));`.
  `PrivateAggregateFourPlannerContractTest.fourPlannersPreserveNativeSamePoolPrivateAggregateInputs`
  fails during PlacementAnalysis creation, before any selector is invoked.
- **Cause**: inherited-anchor logic requires a single durable identity instead of
  accepting distinct identities with the same physical worker/range layout. Runtime
  `BinaryMatrixMatrixFEDInstruction` uses `FederationMap.isAligned`, which permits this
  native case without collecting/redistributing raw values.
- **Repair boundary**: prove physical equivalence with
  `PlacementIdentity.samePhysicalWorkerPool`, retain oracle validation, and choose a
  deterministic representative. Merely equal FType is insufficient; mismatched
  workers or partition-axis ranges must not gain direct native authority.
- **Evidence**: the same XML above preserves this distinct RED. Test names and log
  filenames are not success evidence; read the individual testcase result.
- **Residual risk**: choosing a representative must not invent alignment or erase
  distinct value identities. Positive aligned and negative misaligned fixtures are
  required before promotion.


## Shape-proof observation pollution and literal FED source dimensions

- **Status**: focused Oracle 19/19 and actual four-planner same-pool/CFG methods PASS;
  full integrated regression running. No performance case closed or runtime restarted.
- **Symptom**: ROW+ROW native FED candidates were rejected for a missing
  `fullSinglePartition` fact that the rule did not require. Literal FED HOP dimensions
  could also remain unknown despite exact source ranges.
- **Cause**: `OracleFacade.mergeFullSinglePartitionHint` used proof-recording accessors
  merely to test optional metadata presence and copy fields. This made observation
  become an artificial rule precondition. An intermediate repair inferred dimensions
  from inherited anchors, but placement metadata does not prove derived value shape.
- **Resolution**: extend the existing non-recording immutable diagnostic snapshot for
  structural copying; keep all rule-facing accessors recording actual dependencies.
  Fill unknown literal FED source dimensions from its own ranges, matching
  `InitFEDInstruction.processFedInit`; never derive value dimensions from an arbitrary
  inherited anchor. Pass the exact input shape facts to the oracle. Temporary debug
  traces removed. Physical source identity is retained separately from pool alignment.
- **Files**: `rules/RulesApi.java`, `rules/bridge/OracleFacade.java`,
  `placement/NeutralPlacementGraphBuilder.java`, `OracleFacadeTest.java`,
  `PrivateAggregateFourPlannerContractTest.java`.
- **Verification**: initial three new Oracle methods produced two expected RED failures;
  after the change all Oracle 19 methods PASS and rule-required UNKNOWN still records
  as missing. `SHAPE_HINT_PROOF_RED_20260905.*`,
  `SHARED_SHAPE_PRIVACY_REGRESSION_20260905.*` preserve evidence. Same-pool private
  A+B and function/branch/loop tests invoke all four production selectors successfully.
- **Residual risk**: occurrence shapes must come from exact value facts, not stale
  input/placement dimensions. The new transpose fixture asserts the source's 4x2
  ranges do not become t(A)'s 4x2 output shape. Wider integration is pending.
- **Decision basis**: preserve runtime-supported legal candidates and exact shape
  preconditions; do not fabricate capabilities or weaken privacy to regain states.

## Non-vacuous FULL cross-pool privacy regression

- **Status**: fixture corrected, fresh PrivacyMovement 7/7 + MixedPrivacy 2/2 PASS.
- **Symptom**: the old `sum(A+B)` FULL negative unexpectedly admitted a plan.
- **Cause**: `rewriteHopsDAG` transformed it into independent federated partial sums
  plus a local scalar addition. The raw matrix binary no longer existed, and this
  aggregate-release plan was legal; assuming a raw movement bug was incorrect.
- **Resolution**: use `sum(exp(C))` and assert protected `C=A+B` remains in the actual
  graph. Existing common post-action pool constraints reject the true cross-pool raw
  binary. No production guards were added and no legal plan was excluded.
- **New mixed fixture**: all four planners keep protected A direct at pool P and move
  only public B from Q to P; all four reject two protected ROW sources from incompatible
  pools after analysis. These are privacy-sensitive mixed-input correctness fixtures,
  not relabeled public benchmarks. The specialized FULL negative invokes FedAll.
- **Evidence**: control `P3_MIXED_FULL_PRIVACY_RELOCATION_20260905.log`,
  `P3_PRIVACY_MOVEMENT_FULL_ALIGNMENT_20260905.xml`,
  `P3_MIXED_PRIVACY_RELOCATION_20260905.xml`; independent read-only review found
  no HIGH issue in the bridge/common post-action alignment changes.
- **Residual risk**: negative tests must assert the intended operation survives rewrites
  and that failure is relevant. This fixture correction closes no 128/30 runtime case.


## Superseding runtime-order contract — user clarification

Latest required order is **Exact <= DP <= {FedAll, Heuristic}**, not a total order between baselines. The original 128-row audit included 54 FedAll-vs-Heuristic rows; these are now explicitly out of scope, not "fixed". Remaining comparisons: 23 cost-based-vs-baseline +51 Exact-vs-DP =74 open cases. Exact upward-worker transitions remain30 separate causal classifications. No Heuristic policy change will be made merely to order the baselines. PAIR017 remains explanatory evidence only. Original audit and pre-correction tracker are preserved; see control PERFORMANCE_REPAIR_SCOPE_CORRECTION_20260905.md. This supersedes the earlier "128 open defects" paragraph without altering raw measurements.

## Runtime-order audit scope corrected and regression-locked

- **Status**: control tests43/43 focused,164/164 full PASS; runtime still held.
- **Problem**: the historical inventory used a total order that wrongly classified
  FedAll-vs-Heuristic differences. Latest user contract is only Exact<=DP<=each baseline.
- **Resolution**: authenticate the entire original128-row frozen CSV, then return74
  required-order comparisons with original ordinals. Both directions of baseline
  ordering are non-defects. Physical comparison1728 remains complete and independent.
  The instruction audit reuses the same authority; queue gate requires74, rejects128.
- **Files**: control compare_cross_planner_authority_20260905.py,
  analyze_all_instruction_deltas_20260905.py, run_final_integrated_queue_20260905.py
  and three corresponding test modules. No selector policy changed for baseline ranking.
- **Verification**: control RUNTIME_ORDER_SCOPE_VERIFICATION_20260905.json; all74 IDs
  exactly match original authenticated ordinals and tracker, no evidence altered.
- **Residual**: old immutable queue definition intentionally no longer matches code SHA.
  Reissue a new final-stage-bound definition before launch, never edit old authority in place.
- **Regression risk**: filtering before authentication or renumbering would hide corrupt
  excluded evidence or break PAIR identity; explicit negative tests guard both.
- **Decision basis**: user-defined partial-order audit scope, not numerical manipulation.

## GLM Exact identically-zero maximum-demand auxiliary encoding

- **Status**: focused24/24 PASS, actual GLM resource gate restored, not packaged/deployed.
- **Problem**: expanded lawful shared graph created zero-cost max-demand auxiliaries
  that inflated GLM materialization to18,270,552 cells (gate6M), maxfactor3,276,800.
- **Resolution**: after validating all demands, elide only private solver auxiliaries
  when the canonical nonnegative finite maximum is exactly +0 across its full scope.
  Keep canonical contribution/descriptor authority and hard/privacy feasibility factors.
  Reachable nonzero costs retain encoding; NaN/Infinity/-0 remain invalid.
- **Files**: ExactMaxDemandFactorDecomposition.java and its test.
- **Verification**: decomposition9,reduced11,fingerprint3,actualGLM1 allPASS.
  GLM maxfactor7424, materialized446476; optimizer/projection receipts consistent.
  Control EXACT_ZERO_MAX_DEMAND_REGRESSION_20260905.json preserves XMLs; independent
  review approved bounded change. This is not old/new campaign-plan equality proof.
- **Residual**: StepLM growing-loop and L2SVM final-materialization contracts remainRED;
  entire backend has not passed integration. No runtime-ranking case closed.
- **Regression risk**: zero costs must not remove legality; tests cover dormant and
  zero-frequency costs separately from reachable-positive and malformed price domains.
- **Decision basis**: exact algebraic simplification of internal cost encoding only.

## StepLM growing-column loop loses exact transient replay

- **Status**: ROW and single-worker FULL minimal reproductions bothRED; repair underway.
- **Environment/reproducer**: PRIVATE_AGGREGATE A; B=A; loop B=cbind(B,A); print(sum(B)).
  See PrivateAggregateFourPlannerContractTest growing-loop methods and control
  GROWING_LOOP_PRIVATE_PLACEMENT_RED_20260905.log / GROWING_LOOP_PRIVATE_TRACE_20260905.log.
- **Cause status corrected**: the trace proves that the backedge gains FED/FOUT but
  final replay leaves the read CP-only; origin-bound privacy then correctly rejects it.
  It does NOT prove `sameLogicalValueShape` as the rejecting predicate. The fresh
  compiled-HOP diagnostic shows all relevant dimensions unknown (-1,-1); literal Fed
  sources are separately seeded by the builder. A bounded builder-fact/anchor/context
  diagnostic is locating the exact rejection branch before changing any predicate.
- **Repair constraints**: runtime AppendFEDInstruction is authority for pool preservation
  and exact result range updates; do not drop arbitrary shape/anchor guards or reuse stale
  FULL ranges. Provisionally seeded cycles must still prove every backedge at final closure.
- **Required negatives**: unrelated local backedge, different protected pools, transpose,
  partition-axis-changing append, and unknown FULL output extents. Positive tests must
  assert retained append node, expected FType, no raw active movement and correct geometry.
- **Residual**: no production fix for this issue yet; existing 150-test run remains147PASS.
- **Regression risk**: confusing stable pool identity with evolving value shape could
  fabricate index bounds or alignment. Validate these as independent facts.
- **Decision basis**: restore proven runtime-supported private placement, not relax privacy.

## Derived-FOUT action must retain its privacy-safe native source (L2SVM)

- **Status**: exact-row dependency repair implemented; new 4/4 tests PASS after
  3/4 RED failures. Wider privacy regression 24/24 PASS. Actual L2SVM now compiles;
  its legacy test fails a forbidden CP/raw-label materialization expectation, currently
  being audited against the unchanged PRIVATE_AGGREGATE fixture.
- **Problem/cause**: privacy filtering independently retained a FOUT target while removing
  its actual FED/LOUT source. Exact identity binding then correctly rejected the dangling
  materialization. Candidate feasibility is a two-step action, not target residency alone.
- **Repair plan**: after individual privacy/input checks, close each candidate row over its
  non-action native source emissions; retain a derived target only if its exact source
  placement remains in the same row. Compute retained node-state union only afterwards.
- **Constraint**: native FOUT with equal final tuple stays available independently; do not
  borrow a native source from another input signature, reopen private LOUT, or erase an
  action while retaining its target. This is a privacy/physical dependency, not search pruning.
- **Tests**: PrivacyDerivedMaterializationClosureTest, then actual L2SVM compile-only fixture.
  Historic CP label/Hessian assertions in that fixture require privacy-aware reassessment.
- **Evidence**: control L2SVM_DERIVED_FOUT_PRIVACY_CLOSURE_DIAGNOSIS_20260905.md.
- **Regression risk**: mistakenly deleting legal native FOUT or safe released-aggregate
  rematerialization; exact-row positive and same-final-tuple tests cover these distinctions.

## Worker-pool continuity must not masquerade as exact value geometry

- **Status**: independent review found a HIGH correctness risk; repair design underway.
- **Problem**: samePhysicalWorkerPool intentionally ignores the FULL matrix extent
  (and the ROW/COL non-partitioned extent), but some joins retain an old
  DurableAnchorKey as an exact representative. Growing append values can therefore
  inherit stale range/byte/index authority even if their endpoint pool stays stable.
- **Decision**: do not remove shape guards blindly or publish a seed key as an exact
  current map. Separate native runtime pool continuity from exact value-range facts.
  The live AppendFEDInstruction map updates are the runtime authority.
- **Affected paths**: cfgTransientReadAnchor, exactTransientReplay, inherited output
  anchors, commonBoundaryAnchors, and worker-pool materialization resolution.
- **Validation required**: growing FULL/ROW direct FOUT retains privacy with no raw
  movement; no stale anchor reaches right-index, cost geometry or emitted actions;
  exact known current dimensions may create fresh ranges; cross-pool cases remain
  infeasible. Existing public/aggregate materialization space must not be arbitrarily
  closed as a shortcut.
- **Risk**: changing exact-anchor propagation can expose consumers that previously
  conflated placement with shape. Regression and final immutable plan audit required.

## Loop-backedge physical TWrite rejected by semantic LOOP_PHI classification

- **Status**: precise cause reproduced; bounded operation-aware repair implemented,
  focused tests next. This does not close the separate stale-geometry review issue.
- **Evidence**: control GROWING_LOOP_NODE_KIND_DIAGNOSIS_20260905.log/.xml shows
  initial source TRANSIENT_WRITE/ORDINARY and backedge LOOP_PHI/LOOP_BACKEDGE;
  both have matching contexts and unknown compatible shapes, but seed=null.
- **Cause**: four exact replay/seed/identity gates require TRANSIENT_WRITE kind,
  whereas physicalNodeKind classifies actual loop-latch TWrite as LOOP_PHI first.
- **Repair**: shared isCompiledTransientWrite requires the actual TRANSIENTWRITE HOP
  and kind TRANSIENT_WRITE or LOOP_PHI. Keep all reaching definitions, final
  consistency proof, state constraints, namespace/context, and clone exclusions.
- **Files**: NeutralPlacementGraphBuilder.java; existing growing ROW/FULL regressions
  reproduce the defect before repair. No privacy weakening or global reclassification.
- **Residual/risk**: opening valid replay can expose stale geometry previously hidden
  by the rejection. Native continuity and exact range authority must be verified
  separately before any new backend is packaged or deployed.

## Resume: exact transient operation and finite append shape regressions

- **Status**: focused operation/L2/FULL legality 10/10 PASS; finite append + ROW loop
  4/4 PASS. The stale geometry repair is still in progress, not deployed.
- **Cause correction**: the earlier report's claim that equal iteration dimensions
  were the first rejecting predicate is superseded. The actual first rejection was
  semantic LOOP_PHI versus physical TRANSIENTWRITE. After fixing this, ROW append
  still lacked a proven invariant row count in oracle facts.
- **Repair**: require actual compiled DataOp direction without discarding semantic
  loop/branch kinds. Seed shared abstract shape with the source's own authoritative
  shape; implement CBIND/RBIND invariant-axis equality and growing-axis exact sum,
  widening changing/unknown/overflow dimensions. Publish only final exact dimensions
  into builder-owned oracle facts; do not mutate HOPs or infer value shape from a pool.
- **Files**: PlacementAnalysis.java, NeutralPlacementGraphBuilder.java,
  PlacementAbstractShapeAnalysis.java, CompiledTransientOperationKindTest.java,
  PlacementAppendAbstractShapeTest.java, PrivateAggregateFourPlannerContractTest.java.
- **Evidence**: control L2_PRIVACY_AND_FULL_LOOP_REGRESSION_20260905.* (10 PASS),
  APPEND_SHAPE_AND_GEOMETRY_RED_20260905.* (4 assertion failures),
  APPEND_SHAPE_ROW_LOOP_REGRESSION_20260905.* (4 PASS).
- **L2 test contract**: actual compile-only L2SVM now passes the strict protected
  origin/native FOUT requirements. Obsolete CP raw-label/Hessian prefetch assertions
  were removed, not privacy-relaxed. Derived-FOUT candidates must retain their exact
  row's safe native source; source-closure 4/4 remains part of the 10-test receipt.
- **Remaining bug**: growing FULL values still inherited the initial 4x2 exact anchor.
  A new non-vacuous assertion reproduces this independently of legal FED/FOUT selection.
- **Risk/control**: operation-kind repair must reject clones and wrong-direction DataOps;
  lattice repair must not freeze changing dimensions. New tests cover these boundaries.

## Native loop continuity versus movement-target templates: repair in progress

- **Problem**: generic WorkerPoolAnchorResolver answers which original anchor is a
  usable movement TARGET. It does not certify the native output map of a partition-axis
  append/transpose, and its logical-source union cannot prove all loop backedges.
- **Decision**: preserve that target space unchanged. Use a separate typed native
  continuity proof for the mixed exact/no-exact-map replay case. Require every
  reaching definition and native candidate transfer; all dependency cycles must be
  grounded in an original source witness. Never attach this witness to Node.anchors.
- **Changes under verification**: unknown compute extents cannot inherit exact ranges;
  actual TWrite aliases may retain their input's exact map. CFG/function exact-map
  joins compare both axes, not only endpoints. A provisional real loop seed gives
  residency, not old value geometry; null logical/read anchor remains null.
- **Tests**: the new NativePlacementContinuityTest staged baseline produced 2 assertion
  failures / 3 tests / 0 errors; native implementation is next. The initial derived
  test setup violated identity ownership; fixture corrected before recording this
  assertion-level RED. Both logs/XMLs are preserved separately.
- **Evidence**: control NATIVE_PLACEMENT_CONTINUITY_ASSERTION_RED_20260905.*;
  LOOP_NATIVE_CONTINUITY_REPAIR_PLAN_20260905.md.
- **Remaining/risk**: native proof must not silently certify a derived/movement-only
  state, an ungrounded cycle, a different pool, or ROW/COL partition-axis changes.
  FULL/ROW/COL growing positives must retain all reaching sources and no stale ranges.
- **Runtime**: campaigns remain held; 74 active runtime-order comparisons are still
  open and the 30 worker-scaling transitions remain separately unclassified.


## Invocation-local FULL metadata and native/CFG bridge integration

- **Status**: implemented, focused integrated regression running; not deployed.
- **Problem**: explicit UNKNOWN FULL cardinality was overwritten from a previous
  program's global lexical-name signature. Standalone FULL loop passed while a
  prior multi-worker same-name program made the same fixture fail.
- **Evidence**: FOCUSED_DP_ORACLE_STEPLM_20260905T192402Z has actual Oracle isolation
  assertion FAIL. An earlier 1938-named attempt failed compilation; its stale copied
  XML is explicitly marked NOT test evidence. Do not count it as RED/PASS.
- **Repair**: reuse the shared CFG/formal-input value relation in invocation-local
  SinglePartitionFacts. Require literal one-range grounding, complete same-endpoint
  dependencies, and supported native map-preserving transfers. Unknown function
  returns/foreign sources/multi-range endpoints/ungrounded cycles stay UNKNOWN.
  Supplied Oracle ShapeHint is authoritative, including UNKNOWN; legacy null-hint
  callers remain a separate audit surface. Never publish cardinality as exact ranges.
- **Native proof correction**: native plus derived fallback in one row is not a
  derived-only row. A real native emission may certify native continuity while
  ignoring fallback alternatives; no derived action itself proves native continuity.
  Binary matrix-scalar kernel copies its input map, now represented precisely.
- **DP correction**: raw CFG definition dependencies and actual SAME_PLACEMENT
  constraints have typed all-source receipts separate from zero physical TRead arity.
  Partial-source/foreign-constraint receipts remain invalid, and every published
  arm must be compatible with the actual constraints. No CP/FOUT transient exception.
- **Files**: NeutralPlacementGraphBuilder, PlacementAbstractShapeAnalysis,
  SinglePartitionFacts, OracleFacade, NativePlacementContinuity,
  FederatedPlannerDpCostEnumerator, adapter/DpPlacementAdapter; corresponding tests.
- **Remaining**: integrated test gate, shared function-return cardinality precision,
  P1/P2/SliceLine metadata release authorization, immutable stage and Docker reruns.
- **Risks**: class-based native transfer must not accidentally admit a future
  repartitioning opcode; inspect live runtime behavior and add negative tests.
  Cardinality does not certify chosen placement, alignment, privacy, or movement.
- **Decision basis**: source/runtime evidence repair in the common pre-selector
  model and exact typed DP consumption, not ranking-driven state-space pruning.

## KMeans derived FULL chain: precise occurrence and assertion RED

- **Status**: diagnosed; repair in progress, runtime held.
- **Symptom**: KMeans shared domain lacks FED/FOUT/FULL at hop286 `ba(+*)`,
  directly consumed by hop287 `b(*)` in the 50-iteration centroid-selection loop.
- **Cause**: the new endpoint lattice joins the local selection matrix's UNKNOWN
  with the literal one-range FULL input. It consequently loses the cardinality of
  hop185's legitimate FULL result, its TWrite/read223 aliases, and downstream MM.
  Candidate audit proves hop185 FULL survives privacy; hop263 `[local,FULL]` has
  missing `fullSinglePartition` BEFORE privacy and hop286 sees only local inputs.
- **Corrected hypotheses**: actual/formal X binding is present (165 -> source736).
  Neither missing function input binding nor privacy removal of hop185 is the cause.
- **Evidence**: control `KMEANS_FULL_CHAIN_CAUSAL_EVIDENCE_20260905.json` and immutable
  KMEANS_FULL_CANDIDATE_DIAG_20260905T195312Z audit/log/XML; minimal
  `FULL_MM_CHAIN_CARDINALITY_RED_20260905T200131Z` has 5 tests, 1 assertion failure.
  The preceding 200045Z constructor-compilation attempt has stale XML marked invalid.
- **Repair contract**: distinguish the conditional range count of a legal FULL
  result from actual availability, endpoint alignment, and exact output geometry.
  Ordinary MM's native FULL paths require single-FULL providers and preserve their
  count; local matrix operands do not determine that count. Retain literal grounding,
  all-source CFG joins, and per-input FULL guards. Do not create a FULL state from
  a cardinality fact, use global worker/varname fallback, or weaken privacy.
- **Risk/negative coverage**: ungrounded MM cycles, unknown/multi-range FULL operands,
  TSMM/LOUT-only outputs, different pools, and unsupported materialization must not
  gain authority. These checks remain independent of the intended output implication.

## Production DP dispatch versus legacy CFG compatibility (correction)

- **Status**: production DP privacy regression passes; one legacy PCA gap remains.
- Factory COMPILE_COST_BASED invokes FederatedPlanLocalCost/LocalPhysicalOptimizer,
  not the fedDp enumerator. Four-planner tests now use that actual factory path;
  the explicitly named legacy CFG method remains a separate compatibility test.
- Source/test fixes to the legacy typed CFG bridge cover only matrix TReads with
  real compiled TWrite sources and exact SAME_VALUE_PLACEMENT/SAME_PLACEMENT receipts.
  Scalar TReads retain their legacy exact rewire-forward receipt contract.
- Fresh legacy receipt run: 5 tests, 4 PASS, 1 FAIL. Remaining PCA-MULTIRETURN read
  has a synthetic FUNCTION_OUTPUT -> TRead `cfg-function-output-value:X` edge, not
  a compiled TWrite source. It requires its own typed compatibility design.
- Do not disguise a function-output boundary as TWrite, narrow the fixture, delete
  the failing assertion, or claim this as a production DP runtime failure.
- Evidence: control `LEGACY_TYPED_CFG_REPAIR_20260905T195021Z.json`; production path
  evidence `PRODUCTION_DP_PRIVACY_AND_LEGACY_RECEIPTS_20260905T194024Z.json`.
- Potential risk: direct users of the legacy enumerator still hit the nested return
  gap. Packaging/final reporting must disclose this separately from production gates.

## Conditional FULL transfer: stale-provider safety repair (20:22 UTC)

- **Status**: targeted proof/KMeans regressions pass; integration still in progress,
  not packaged or deployed. Architect re-review CLEAR after the changes below.
- **Problem**: the first ordinary-MM cardinality repair kept its previous endpoint
  while ignoring UNKNOWN operands. A later-closing conflicting CFG definition could
  widen the only provider to UNKNOWN without invalidating the consumer's certificate.
  In-place IdentityHashMap closure could also yield traversal-dependent recursive facts.
- **RED evidence**: `FULL_MM_TRANSFER_SAFETY_RED_20260905T201452Z`: 9 tests, 2 assertion
  failures (late provider invalidation, TSMM all-input preservation), no errors.
- **Repair**: each round reads a snapshot, computes a fresh MM transfer, then joins
  with the old fact. No exact provider + unresolved bottom waits; all-final-UNKNOWN
  becomes UNKNOWN; conflicts stay UNKNOWN. TSMM keeps the conservative all-input
  transfer. No unconditional FULL state/geometry/placement/privacy authority is added.
- **Verification**: `FULL_MM_TRANSFER_SAFETY_GREEN_20260905T201716Z`: 44 tests, 43 pass,
  1 failure. SinglePartitionFacts 9/9, KMeans 1/1, Oracle 20/20 and native continuity
  pass. The failure is the explicit legacy typed-CFG fixture, not production DP.
  `LocalCostPlacementPrivacyContractTest` in that command was nonexistent and is
  NOT counted as executed; real `FederatedPlanLocalCostPrivacyConstraintTest` must run.
- **Remaining legacy fixture issue**: selecting only matrix TReads reveals that the
  simple four-planner fixture contains non-replayable raw CFG evidence only for a
  scalar. A dedicated real matrix CFG fixture is needed without deleting all-source,
  partial-receipt or foreign-identity assertions. Separate legacy PCA return gap remains.
- **Files**: SinglePartitionFacts.java, SinglePartitionFactsTest.java,
  PrivateAggregateFourPlannerContractTest.java.
- **Risk**: Hop-level conditional cardinality must never be interpreted as actual
  availability or endpoint alignment. Per-selected-FULL guards, all-source joins,
  source grounding, exact materialization and origin-bound privacy remain independent.

## Release-boundary scope correction: P1 is not the P2 recode pipeline

- **Status**: source-verified; no runtime resumed (so007 empty at 20:15 UTC).
- **Problem**: listing P1/P2/SliceLine as a single metadata-support gap overstates the
  blocked scope. Actual P1_FULL.dml has no transformencode/recode; GLM/GNMF/GMM-VVI
  templates also use numeric federated input directly.
- **Decision**: these numeric workloads may pass their own fresh privacy/planning/
  Docker correctness gates independently. P2_PREP and SliceLine remain blocked on
  transform-spec-specific release authority, including internal worker encoder replies
  and coordinator dictionary merge, not only the final metadata-frame output.
- **Evidence**: EncoderFactory implicit recode; ColumnEncoderRecode#getMetaData;
  MultiReturnParameterizedBuiltinFEDInstruction CreateFrameEncoder/merge/setFrameOutput;
  SharedPrivacyPlacementAnalysisContractTest.privateAggregateRecodeMetadataCannotExposeDistinctRawValues.
- **Residual test conflict**: CampaignBG014ExactSingleInputDirectFoutTest still expects
  native transformencode under PRIVATE_AGGREGATE; this is an old permissive privacy
  fixture and cannot serve as release certification. Track separately before any
  full-suite success claim. Do not relabel experimental input or alter the benchmark.
- **Risk**: ignoring dead metadata output is not enough: dictionary exchange survives.
  Reuse no invalid old runtime solely because its physical plan hash is unchanged.

## Exact direct-unary fixture: separate legal consumption from illegal recode release

- **Status**: test contract split, revalidation pending. No production/DML benchmark edit.
- **Evidence**: LEGACY_MATRIX_AND_RELEASE_AUDIT_20260905T202308Z reproduces the old
  positive fixture failing closed at PRIVATE_AGGREGATE `FunOut M`; LocalCost 2/2
  and the nine normal four-planner contracts pass in the same receipt.
- **Repair**: retain all two-label split/direct-FOUT unary authority and cost-choice
  assertions on protected numeric input (no recode dependency). Keep the original
  protected recode pipeline as an explicit negative test, including unused M.
  Runtime-native transform support is not aggregate declassification authority.
- **Files**: CampaignBG014ExactSingleInputDirectFoutTest.java only.
- **Risk**: the unit-fixture separation must never be misrepresented as fixing P2.
  Neither experimental dataset privacy nor workload scripts were changed. P2 remains
  fail-closed until a real spec-specific encoder/dictionary release protocol exists.

## Direct split-label test diagnosis: lexical aliases, not a missing FED domain

- **Status**: selector-test identification repaired; wide verification running.
- **Evidence**: NUMERIC_SPLIT_FOUT_DIAG_20260905T202749Z reports four sum inputs
  named `_sbcvar0`...`_sbcvar3`, ALL with PRIVATE_AGGREGATE FED/FOUT/ROW domains.
  The old `getName().startsWith("y")` filter saw zero solely because HOP rewrite
  renames the user-level split outputs. This observation is NOT a candidate-space bug.
- **Repair**: identify the two label vectors through shared column-size facts (one
  column), retaining the count=2 and exact input-authority/cost-choice assertions.
  Strengthen the recode negative to require the actual `FunOut M` rejection site.
- **Risks**: unknown label shape must fail the assertion rather than guess a name;
  no planner-domain modification or experimental script change was made.

## Non-replayable matrix CFG regression: actual legacy scheduling conflict

- **Status**: new negative evidence, not a production DP failure; investigation pending.
- A shape-changing conditional uses ROW `cbind(A,A)` vs `A+1`: same partition axis,
  unequal column extents, real compiled TWrite sources, logical replay unavailable.
  The earlier rbind fixture changed the ROW partition axis and was legitimately not
  native-continuous; cbind fixes that fixture precondition without weakening receipts.
- **Evidence**: LEGACY_MATRIX_RELEASE_CONTRACT_20260905T202515Z reaches legacy
  `TRANSIENT_FORWARD_DEPENDENCY_AUTHORITY_DIFFERS`. This is distinct from the prior
  scalar-only fixture mismatch. The nine production factory contracts and LocalCost
  privacy 2/2 still pass.
- Adapter's scheduling-only rewire fallback requires an unused CP/LOUT/null child.
  A collected FED child that is not matched to raw CFG/logical/physical authority
  violates that contract. The exact failed parent/source is still to be identified;
  do not call it fixed or simply change the fixture again.
- **Boundary**: retain all-definition/partial/foreign-identity assertions. Do not
  create TWrite authority for synthetic function returns, relax privacy, or label
  this legacy enumerator path as runtime DP's LocalPhysicalOptimizer algorithm.

## Legacy branch-PHI cause confirmed and minimum operation-kind repair

- **Status**: corrected; focused branch/CFG/production privacy tests pass. Separate
  legacy PCA synthetic-function-return gap still fails and remains disclosed.
- **Newest evidence supersedes the earlier fixture hypothesis**:
  LEGACY_MATRIX_PARENT_DIAG_20260905T203609Z identifies the actual parent as TRead B
  with BOTH compiled CFG definitions and BOTH logical transient facts present.
  Unknown joined width plus native continuity legitimately retains logical facts;
  different known branch widths alone do not guarantee non-replayable analysis.
- **Root cause**: DpPlacementAdapter required semantic NodeKind.TRANSIENT_READ,
  misclassifying an actual TRead labeled BRANCH_JOIN as a scheduling-only carrier.
- **Repair**: dispatch on the analysis-owned actual DataOp TRANSIENTREAD, with the
  existing zero-input, exact logical fact/state identities, all-source closure and
  equal logical-slot FType checks unchanged. Independent architect review: CLEAR.
- **Regression separation**: preserve the original protected cbind branch as a new
  logical branch-PHI test; exercise the separate raw-CFG path using local matrices
  released by approved colSums of PA input. No raw source is relabeled or collected.
  Partial/foreign CFG receipt assertions are retained and pass.
- **Receipt**: LEGACY_PHI_OPERATION_REPAIR_20260905T204124Z: 18 tests, 17 PASS, 1 FAIL.
  Four-planner/branch/CFG class 11/11; LocalCost privacy 2/2; legacy snapshot 4/5.
  Only remaining failure is the previously known PCA-MULTIRETURN function output.
  The 204040Z attempt failed compilation due to an unqualified enum reference; no
  test XML was produced or counted. The reference was corrected to Types.OpOpData.
- **Remaining gate**: fresh wider regression after this minimal adapter change;
  packaging/immutable stage and authenticated numeric-workload canaries not yet run.

## Verified numeric-production candidate checkpoint (20:48 UTC)

- Fresh `POST_PHI_INTEGRATED_WIDE_20260905T204429Z` completes 194/194 tests across
  all 37 requested classes; 0 failures/errors/skips/missing classes. This includes
  the new branch-PHI logical receipt and raw-CFG tests, production four-selector
  privacy, KMeans FULL, Exact/GLM, numeric split, and GNMF seed regressions.
- `git diff --check` passes. This is a focused numeric-production candidate gate,
  not an assertion that the complete repository suite or all workloads pass.
- Known separate failure: legacy `CampaignBG014CandidateOccurrenceSnapshotRedTest`
  PCA synthetic FUNCTION_OUTPUT -> TRead (4/5 in its last run). Current production
  DP factory uses LocalPhysicalOptimizer, not this legacy compatibility path.
- P2/SliceLine PA recode release remains unsupported/fail-closed. Numeric extra ML
  and P1 do not require that protocol, but still need immutable-stage Docker checks.
- Packaging this source does not certify any old runtime measurement. New authenticated
  planning and numeric parity must precede accepting replacement runtime evidence.
- Missing operational piece found: local seeded-GNMF stage preparer exists, but
  there is no remote seeded-stage deployer. A bounded separate helper/test/contract
  is being implemented under the control directory; no servers/stages modified yet.

## Authenticated GLM private-aggregate cbind planning failure (21:16 UTC)

- **Status**: reproduced in the first new Docker planning cell; diagnosis in progress.
  Both ordinary and seeded immutable stages are now authenticated on so002-so009.
- **Symptom**: `ml|glm|lan|w1|FedAll`, token `5053cbb1863eccc05b1b`, fails before
  selector execution at builtin `binomial_probability_two_column`, glm.dml:903,
  `b(cbind):Y_prob`: no privacy-safe physical placement, PRIVATE_AGGREGATE.
  The new 54-cell planning campaign stopped fail-closed after this first attempt;
  zero accepted planning or runtime cells. Coordinator archive SHA-256:
  `8d399e265475f353a54fd98394275a85f9e590c09507afb08fef19e83ef2436e`.
- **Evidence**: control/glm-fedall-planning-failure-ac145cd-20260905 holds the
  size/hash-authenticated coordinator log and generated DML. Inputs are 50000x2100
  and 50000x1, PA, one worker; binomial link=2, moi=20, mii=5.
- **Coverage gap**: the passing ExactGlmCostSurfaceScalabilityTest exercises the
  actual builtin graph but with 8x4 PUBLIC hermetic inputs and moi/mii=2. Its exact
  resource gate is not a PRIVATE_AGGREGATE campaign planning/correctness gate.
- **Initial diagnosis**: runtime supports native FULL/ROW cbind; missing native
  candidate/cardinality authority is under investigation. The failing branch may
  have zero frequency for link=2, but deleting dead domains or relaxing privacy is
  not a valid repair. Aggregate output release never licenses collecting raw input.
- **Regression**: add GlmPrivateAggregatePlanningContractTest with actual campaign
  shapes/options/PA metadata and production final-Hop-boundary analysis. No worker
  or training is executed; RED verification precedes a source repair.
- **Decision boundary**: repair proven candidate/continuity omissions, not privacy
  filtering, benchmark DML, TR/TW state rules, or runtime fallback.
- **Remaining issues/risks**: exact missing proof not yet isolated; after repairing
  it, later GLM occurrences may expose additional gaps. Keep full-source/CFG joins,
  endpoint identity and unknown cardinality negatives, then rerun authenticated
  planning before accepting any replacement runtime result.

### Causal isolation: rewritten elementwise ternaries erase the FULL proof

- **RED evidence**: actual GLM production-boundary regression reproduces the exact
  authenticated failure (1 error, 12.921s). Unit SinglePartitionFactsTest: 12 tests,
  1 assertion failure on native `+*`, 0 errors. Receipts:
  `GLM_PRIVATE_AGGREGATE_PRODUCTION_RED_20260905T2127Z` and
  `GLM_TERNARY_CARDINALITY_UNIT_RED_20260905T2132Z` under control.
- **Exact chain**: hop511 cbind <- hop510 minus <- hop507 exp <- hop506 formal
  linear_terms <- hop3359 formal join. That join includes hop1385; its eight CFG
  sources include writes derived from hop3674 `t(+*)` (glm:584) and hop3676 `t(-*)`
  (glm:596). Both ternaries had no cardinality transfer despite already having
  native FULL candidates. Other caller MM sources are already proven single.
- **Diagnostic**: `GLM_SINGLE_FACTS_DEPENDENCY_SLICE_20260905.json`, 126-node closure.
  Temporary audit-gated source instrumentation was removed after the run; neither
  timing claims nor permanent hot-path logging are introduced.
- **Minimum repair**: recognize only PLUS_MULT/MINUS_MULT/IFELSE map-copy kernels
  in SinglePartitionFacts. Runtime TernaryFEDInstruction#setOutputFedMapping copies
  the selected map; Rulesets.TernaryElemwiseRule supports these three opcodes.
  Every matrix input still needs the same known endpoint. All-source joins,
  unknown/foreign sources, incomplete function returns and selected FULL guards stay.
- **Negative scope**: conflicting/unknown matrix input remains unproved; CTABLE is
  not covered merely because it uses the same HOP class. No exact range, placement
  availability, privacy release or relocation permission is created by this fact.
- **Validation**: GREEN targeted unit/production-boundary checks pending; later GLM
  failures must be recorded separately, not silently called fixed by the first repair.

## Parallel native-map proof repairs and bulk GLM audit (22:04 UTC)

- **Status**: three bounded shared-analysis/oracle repairs verified; actual PA GLM
  still RED. Runtime has not restarted. Native subagents and root are working on
  disjoint implementation and read-only review lanes rather than serial remote retries.
- **Fixed cause**: native map-copy ternary, nary and REPLACE chains lost cardinality
  or native continuity. Nary MULT also lacked supported FULL input signatures.
- **Changes**: exact opcode allowlists in SinglePartitionFacts/NativePlacementContinuity;
  Nary FULL oracle now mirrors runtime. FULL plus one local matrix requires a true
  single-range proof; all-FED aligned FULL operands do not require this local-upload
  guard. Unknown/conflicting sources, unavailable/derived-only native candidates,
  unsupported topology, and multiple local matrices remain fail-closed.
- **Verification**: current focused component set is 31/31 PASS (15 + 7 + 9), independent
  architecture review CLEAR. Normal-source wider set GLM_PARALLEL_WIDE_20260905T215915Z:
  211 tests across 38 requested classes, 210 PASS, no assertion failures/skips/missing
  classes; only actual GLM planning errors at glm.dml:932 LIX. Explicit PUBLIC GLM
  resource fixture and PUBLIC print control excluded, so denominator differs from
  the earlier 194 gate. This is not the complete repository suite or runtime validation.
- **Bulk diagnosis**: one local diagnostic captured all 25 privacy-empty occurrences,
  still throwing before returning PlacementAnalysis; then the original builder bytes
  were restored. Evidence GLM_ALL_PRIVACY_EMPTY_DOMAINS_20260905.json. The 25 nodes
  reduce to two blocker groups, not 25 unrelated opcode bugs.
- **Remaining group A (16 occurrences)**: glm:932 local LHS + protected FULL RHS,
  then glm:933 FULL/FULL LIX. Runtime currently cannot consume FED RHS without
  coordinator broadcast/collection. Also existing FULL-LHS/matrix-RHS candidates
  are overadvertised by a copy-only legacy path. Debugger owns exact native runtime,
  oracle and negative tests; architect reviews geometry/endpoint/movement cost.
- **Remaining group B (9 occurrences)**: glm_dist g_Y function return stays CP-only.
  Direct-Literal-only isSafeLiteral veto discards exact scalar values forwarded through
  m_glm to glm_dist, retaining a CP initializer in conservative function-exit joins.
  Root owns preliminary qualified all-call scalar binding; independent architect review.
- **Regression risks**: no shortcut from worker pool to exact range, no raw protected
  GET, no partial callset exact proof, no namespace-name conflation, no silent fallback.
  LIX local-LHS native upload has a separate global-vs-anchor fanout cost review;
  support must not be closed to hide a cost error. General append expansion is deferred
  because it is not causal in the bulk failure inventory.
- **Decision boundary**: repair runtime capability and shared facts, not privacy filtering,
  TR/TW residency policy, benchmark options or forced planner rankings. New source is
  uncommitted/unpackaged and no runtime performance case is declared resolved.

## 22:37 UTC — Parallel scalar/LIX integration and native-local fanout repair

- **Status**: component implementation verified; broader gate and actual selector canary in progress. No new authenticated runtime cell accepted.
- **Symptom/cause**: nested GLM forwarded scalar selectors were not resolved from all exact qualified call sites. This retained infeasible mixed local/protected function-return joins. Native single-FULL left indexing also lacked runtime/oracle and endpoint-continuity support. An inherited output anchor could incorrectly waive a LIX native-row proof.
- **Changes**: exact all-call scalar binding; native matrix local/FULL and same-endpoint FULL/FULL LIX execution with no protected GET; matrix-kind, range, ID, endpoint and scalar-bound validation; unconditional actual native row for LIX continuity; RHS-grounded endpoint transfer without fabricated geometry.
- **Verification**: `GLM_LIX_SCALAR_VERIFIED_20260905T223127Z` ran 83 tests: 81 PASS, 2 graph test fixture arity errors (requested 2 rather than actual 6 HOP inputs). Core scalar12, cardinality18, continuity11, runtime15 including real worker-kernel numeric updates, LIX rules12, Nary9, oracle3 and actual PA GLM analysis1 all PASS. Fixed fixture signatures. Subsequent graph test write overlapped compilation and will be freshly compiled in the broad gate; do not rely on that intermediate graph receipt.
- **New cost defect (confirmed)**: `ExactNativeLocalAnchorFanoutCostTest` on the real physical cost surface priced the same local-to-single-FULL upload at 0.00048828125 in isolation and 0.00146484375 with two unrelated ROW workers: exactly 3x. `GLM_NATIVE_FANOUT_RED_20260905.log` is the pre-fix evidence.
- **Cost correction**: `ExactPhysicalCostModel` now uses the exact selected consumer input-authority anchor's partition count for native-local upload, including bounded/fused variants. Conflicting anchors fail closed consistently with existing physical-product legality. Without exact authority retain prior conservative fallback. Source FOUT download fan-in remains unchanged. Candidate space is not pruned; DP and Exact consume the same corrected physical surface.
- **Regression protection**: FULL unrelated-worker invariance, BROADCAST N recipients, duplicate authority not double-counted, unknown authority fallback, conflicting-anchor rejection.
- **Risks/remaining**: legacy null-hint oracle still has name-registry range inference; shared builder explicitly supplies occurrence hints and does not supplement unknown evidence. Kernel numeric fixture is not authenticated PA E2E. Actual selector compilation, worker-backed authenticated GLM numeric/runtime, wider workloads, P2/SliceLine PA release boundaries and the tracked performance cases remain open.

## 23:13 UTC — Shared provenance and legality-aware heuristic policy

- **Status**: actual PA GLM selectors now pass isolated local planning-only checks; final broad gate in progress. No new authenticated runtime has run.
- **Observed blockers**: FedAll candidate consistency and Exact `glm:870` physical domain were empty despite AVAILABLE FULL/FULL rows. Function-return aliases and supported unary/two-matrix elementwise chains lost worker-pool provenance. Heuristic separately forced a protected nested reduction or an actual feeding a shared FOUT-only formal into its CP local prefix.
- **Repair**: exact declared function-return/CFG output edges now participate in the all-source worker-pool intersection with ordinary CFG/formal inputs. Unknown/conflicting/cyclic evidence grants no authority. Native continuity uses the existing oracle opcode allowlists, exact native non-derived rows, and single-endpoint FULL/FULL input checks. A matching inherited anchor cannot waive a missing native row for LIX, unary or two-matrix binary operations.
- **Heuristic correction**: an exact ABSENT_LOCAL/PRESENT FED/LOUT continuation can implement a protected nested aggregate without collecting its protected sibling. Independently, a copied-domain binary constraint support closure identifies impossible demotion preferences. It preserves direction, same-state self relations and conjunction of parallel constraints. A marker or its no-upload local prefix lacking necessary hard-constraint support is omitted and remaining paths are retraced. The graph/candidate/privacy/movement domain is not changed and no baseline/solver fallback is used. This necessary filter is not a complete CSP solver; batch removal is legality-safe but may conservatively decline interacting preferences.
- **Validation**: wide scalar/LIX/fanout snapshot `GLM_PARALLEL_WIDE_VERIFIED_20260905T223726Z`: 264/264 PASS. Actual `GLM_PARALLEL_SELECTOR_CANARY_20260905T231032Z`: FED_ALL/HEURISTIC/EXACT each PASS, all 1971 decisions selected with owned legal states, protected append and all PA decisions remote, no protected relocation. Latest isolated integrated `GLM_FAST_COMPILE_20260905T231157Z`: 31/31 PASS in 6.08s including mixed protected function calls with branches, protected nested reductions, parallel/self/directional support, alias joins and native continuity. This is compile-only correctness, not performance or authenticated distributed execution.
- **Invalid test assumption corrected**: campaign GLM uses link=2; LIX at glm:932/933 belongs to unreachable link=4 cloglog. They are PUBLIC before and after selection, same HOP identities. Requiring two PA LIX nodes in the link=2 canary was erroneous, not a privacy failure. Removed that assertion while retaining all-PA remote invariants; actual PA LIX graph and worker-kernel numeric tests remain separate. Canary stdout now emits certificate length/fingerprint instead of multi-megabyte full certificate strings.
- **Changed files**: NeutralPlacementGraphBuilder, PlacementAnalysis, NativePlacementContinuity; HeuristicProtectedNestedDemotionTest, ConstraintSupportedPolicyStatesTest, WorkerPoolAnchorResolverFunctionReturnTest, NativePlacementContinuityTest, GlmPrivateAggregatePlanningContractTest.
- **Remaining**: authenticated Docker planning/correctness, broader current plan comparison and runtime reruns. KMeans wan_mid/w5 top baseline reversals have real Heuristic-vs-cost-based plan differences (691 vs 94 FED rows); current lines134/155 selected states/costs must be captured before cost edits. No performance case is closed by these component tests.
- **Risk detection**: retain unknown/conflict/cycle tests; unsupported/derived-only native row negatives; PA mixed-function and nested-reduction selectors; candidate reachability certification; authenticate source/JAR and compare physical plan hashes before runtime acceptance. No numeric timing statement derives from diagnostic or isolated test logs.

- **Final source gate (23:17 UTC)**: `GLM_PARALLEL_WIDE_VERIFIED_20260905T231243Z` completed **284/284 PASS** across 46 suites, zero failures/errors/skips/missing suites, 200.10s, recorded source hashes unchanged. `GLM_ALL_SELECTOR_SHARED_ANALYSIS_20260905T231604Z` completed PASS on fresh Maven classes: FedAll/Heuristic/Exact using the same PA input paths and equal analysis fingerprints, all 1971 decisions legal and all protected data remote. Total local canary duration41.60s is not a benchmark. `git diff --check` PASS; full repository test suite and separate static analyzer were not run.
