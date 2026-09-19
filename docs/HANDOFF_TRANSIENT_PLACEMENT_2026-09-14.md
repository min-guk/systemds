# Handoff: transient placement / exact physical closure (2026-09-14)

## Scope and safety

Workspace: `/home/mchoi/systemds-lm-worker-count-fix` on `dams-so002`.
Branch: `fix/lm-worker-count`, HEAD `ffb7be5bd8`.
The worktree was already heavily dirty before this session and remains dirty. Do **not** reset, checkout, commit, push, or overwrite unrelated changes.
A pre-existing patch backup from the earlier session is at `/tmp/transient-resume-20260914-171706-dc/pre-existing.patch`.
Main evidence directory from this debugging session: `/tmp/transient-final-20260914-9DuAs0`.

This handoff was written while another agent was also modifying/testing the same worktree. Before any new edit, re-read `AGENTS.md`, `docs/SESSION_ISSUES_2026-09-14.md`, `git status`, and the current diff.
Run Maven tests sequentially with `-Dtest-forkCount=1`; overlapping Maven runs have produced stale surefire evidence before.

## Current bottom line

The original transient-domain blocker is substantially closed. The exact predecessor-domain refinement fix now passes the focused transient/refinement regression.
Fresh command evidence: `/tmp/transient-final-current-ordinal.log` was produced by
`mvn -q -DskipRat -Dmaven.compiler.useIncrementalCompilation=false -Dtest-forkCount=1 -Dtest=CandidateDomainRefinementTest,TransientPlacementAlternativesTest test`
and exited `0`.
`CandidateDomainRefinementTest` currently has 6 test methods and `TransientPlacementAlternativesTest` has 4.

A later concurrent authority run is recorded at `/tmp/transient-final-20260914-9DuAs0/authority-09.log`.
That run reports `ExactPhysicalModelCertificateTest` 8/8 PASS and `LogicalBoundaryRealizationsTest` 6/6 PASS.
The only failure in that 20-test run is `SharedPlannerFunctionPlanPropagationRedTest.fedAllPublishesSelectedFunctionBodyStatesForRecompile`.
The assertion is at line 167: `Selected compiled function-body states must be published for recompile`.
## What was fixed / changed in the current worktree

### Exact input-domain refinement

`CandidateDomainRefinement` is now integrated with the same `exactInputDomains` passed to `buildNode` during post-CFG physical rebuild.
It has two proof surfaces:
- `removedByRefinedInputDomain(...)` proves that an old tuple is no longer admitted at a refined input position.
- `refinedInputPositions(...)` detects strict subset refinement from the prior candidate projection to the exact rebuild domain, even when an outer changed-ordinal set is stale.

The motivating protected transient trace showed a matmul RHS changing from `[null, BROADCAST, ROW]` to `[ROW]` while the predecessor ordinal was absent from stale `refinedOrdinals` bookkeeping.
The helper now treats that exact strict subset as local refinement evidence without accepting swaps or expansions.
This closes the prior false `Post-CFG physical candidate closure is not monotone` failure.

### Transient realization/support closure

`bindDirectNativeCandidateRealizations` no longer preserves an entire merged realization merely because one support clause is relocation-backed; only relocation clauses are carried forward before direct grounding is regenerated.
This removed pass-to-pass support-clause accumulation.
Transient replay seed construction now canonicalizes anchors by `PlacementIdentity.samePhysicalLayout`, not by provenance/placement id.
This prevents identical exact partition geometry from being replayed once per `native-output:<candidate>` id and removes combinatorial support-clause growth.
Exact durable-map compatibility still compares exact geometry; this was not weakened to worker-pool-only equivalence.

A compiled `TRead` is not grounded as an ordinary physical computation; its realization authority is owned by exact CFG replay.
Post-physical direct grounding is followed by exact CFG relation regeneration so compatibility edges reference current realizations.
Publication convergence is compared only after physical rebuild + direct grounding + CFG relation replay, not at an intermediate raw physical state.
### Other current worktree changes observed from the concurrent agent

The current tree also contains a new `LogicalBoundaryRealizations.java` and its tests, plus function-boundary and exact-cost-model changes. These were being actively developed by another agent; do not assume they are part of the transient-only patch.
Observed fresh evidence from `authority-09.log`:
- `ExactPhysicalModelCertificateTest`: 8 tests, 0 failures, 0 errors.
- `LogicalBoundaryRealizationsTest`: 6 tests, 0 failures, 0 errors.
- `SharedPlannerFunctionPlanPropagationRedTest`: 6 tests, 1 failure, 0 errors.
- Aggregate: 20 tests, 1 failure.

The remaining failure is specifically:
`SharedPlannerFunctionPlanPropagationRedTest.fedAllPublishesSelectedFunctionBodyStatesForRecompile`
with `FederatedPlannerUtils.snapshotPlannerRecompileStates().isEmpty()` unexpectedly true after a FedAll plan.
Do not work around this by weakening the assertion. Trace where selected compiled function-body states stop being published into planner recompile state.

The current `PlacementAnalysis` also filters CFG definition-source indexing to real compiled `TRANSIENTWRITE` / `FUNCTIONOUTPUT` owners, avoiding TReads that share a `ValueVersionKey` being misclassified as reaching writers.
The current `ExactPhysicalCostModel` contains a narrow loop-carrier source-cycle change for compiler-inserted `W=W`-style pass-throughs.
Re-read those diffs before changing them because they were modified concurrently after the transient fix.

## Fresh evidence worth preserving

`/tmp/transient-final-current-ordinal.log`: focused refinement + transient suite, EXIT=0.
`/tmp/transient-final-20260914-9DuAs0/protected-trace.log`: trace that exposed `[null,BROADCAST,ROW] -> [ROW]` exact RHS narrowing.
`/tmp/transient-final-20260914-9DuAs0/support-clause-details.out`: evidence of the former 39-clause realization explosion.
`/tmp/transient-final-20260914-9DuAs0/authority-09.log`: latest clean sequential authority run from the concurrent agent.
`/tmp/transient-final-20260914-9DuAs0/authority-09.exit`: value `1` only because of the single function recompile-state assertion above.
## Do not use the aborted overlapping regression as evidence

I started a broad targeted regression under `/tmp/transient-handoff-20260914-235918` before noticing that another agent had already started Maven.
To avoid surefire/report contamination, I terminated only my newer Maven process and left the other agent's run untouched.
Therefore `/tmp/transient-handoff-20260914-235918/targeted-regression.log` is **aborted evidence** and must not be cited as pass/fail.
The worktree currently has no active Maven from my session.

## Diagnostic trace cleanup still required

The following debugging-only instrumentation is still present in `NeutralPlacementGraphBuilder.java` and should be removed after the function-publication blocker is fixed and before final validation:
- all 18 `traceTransientPublicationPhase(...)` calls,
- the `traceTransientPublicationPhase(...)` helper,
- `Neutral-TransientAlternatives`,
- `Neutral-TransientPrivacyReject`,
- `Neutral-RefinementMissingKey`,
- `Neutral-RefinementReject`,
- `Neutral-RefinementMissingState`.

Do not remove unrelated production tracing without checking the inherited diff. The list above is the known temporary instrumentation from this debugging path.
After cleanup run `git diff --check` and rerun the relevant tests; do not assume trace removal is behavior-neutral without a fresh test.

## Recommended next test sequence

First, with no other Maven active:
`mvn -q -DskipRat -Dmaven.compiler.useIncrementalCompilation=false -Dtest-forkCount=1 -Dtest=SharedPlannerFunctionPlanPropagationRedTest,LogicalBoundaryRealizationsTest,ExactPhysicalModelCertificateTest test`

After that is green, run the broader planner/authority set sequentially:
`mvn -q -DskipRat -Dmaven.compiler.useIncrementalCompilation=false -Dtest-forkCount=1 -Dtest=CandidateDomainRefinementTest,NativeMixedWorkerPoolContinuityTest,LocalBroadcastMatmulCandidateTest,TransientPlacementAlternativesTest,SharedPrivacyPlacementAnalysisContractTest,ExactPhysicalModelCertificateTest,NativePlacementContinuityTest,PlacementRealizationAuthorityTest,PrivacyDerivedMaterializationClosureTest,ExactTransientRealizationTest,RelocationPrivacyIndexReuseTest test`
Then run `git diff --check` and copy fresh surefire reports/logs into a dedicated evidence directory before starting any runtime campaign.
## Runtime/workload validation status

The requested ML10 + P1/P2 + SliceLine runtime matrix has **not** been completed in this session. Do not report it as complete.
The canonical ML10 workload tuple in `/home/mchoi/cofee-evaluation/campaign/run_ml10_campaign.py` is:
`pca, als, kmeans, lm, logreg, l2svm, steplm, glm, gnmf, gmm`.
The multihost driver `/home/mchoi/cofee-evaluation/driver/run_multihost_campaign_network_quality_v2.py` defines:
- `p1`: `P1_FULL`
- `p2`: `P2_PREP`
- `sliceline`: `ADULT, COVTYPE, KDD98, USCENSUS`

The LM reference procedure is documented in `/home/mchoi/lm-cg-followup-20260914/REPORT.md` and implemented by `run_variant.py`, `run_runtime.sh`, and `prepare_stages.py`.
The fixed LM stage is `/home/mchoi/lm-cg-followup-20260914/stage-fixed`.
Use the same lifecycle requirements: pinned Docker image, strict network-profile validation, runtime lane lock, CP/reference result comparison, tolerant-semantic output hashing, and archived per-cell receipts. Do not weaken a network guard to make a cell pass.

Important preflight discovered before handoff:
- so002, so003, and so004 have pinned image `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`.
- so007's `cofee-experiment:20260901` currently resolves to `sha256:26eaea7a28a130f2c4c2fd4492b74d0e32d4a1df4f1d3e4f0b28c15b59eca8cc` instead.
Therefore do **not** start the full Docker workload campaign until image identity/parity on so007 is resolved or the topology is explicitly changed by the campaign's existing lifecycle authority.
Do not silently retag or replace an image without preserving the campaign's identity checks.

ML CP/reference assets are available under `/home/mchoi/cofee-ml10-n50000-d128-20260909/stage/references` and under the LM fixed stage references.
Reconfirm stage content hashes and source/build identity before runtime execution because the planner worktree has changed since the earlier LM run.
## Current worktree notes for the next agent

`git diff --check` was clean at handoff time.
The tree contains both modified tracked files and untracked implementation/tests, including `CandidateDomainRefinement.java`, `LogicalBoundaryRealizations.java`, `ExactTransientRealizationTest.java`, `CandidateDomainRefinementTest.java`, `LogicalBoundaryRealizationsTest.java`, `NativeMixedWorkerPoolContinuityTest.java`, `PlacementRealizationAuthorityTest.java`, and `TransientPlacementAlternativesTest.java`.
`docs/STATIC_ANALYSIS_AUDIT_2026-09-14.md` is also currently untracked and appears to be another agent's artifact; preserve it.

Do not infer ownership from `git status`: several files were edited concurrently by different agents.
Before every surgical edit, re-read the exact current hunk and compare mtime/hash if another agent is active.
Never use the old intermediate logs to claim a regression is still failing if a later fresh run exists.

### Highest-priority continuation

1. Fix `fedAllPublishesSelectedFunctionBodyStatesForRecompile` without bypassing the publication contract.
2. Rerun the 20-test authority command and require 20/20.
3. Remove the temporary diagnostic trace points listed above.
4. Run the broad targeted planner regression and `git diff --check` sequentially.
5. Update `docs/SESSION_ISSUES_2026-09-14.md` with only fresh evidence.
6. Resolve the so007 Docker image identity mismatch.
7. Only then run the requested ML10, P1/P2, and SliceLine Docker/CP-equivalence/network/artifact validation matrix.

No commit or push was performed by this session.
