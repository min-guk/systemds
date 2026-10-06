# Source-grounding removal from candidate generation and DP

Worktree: `/home/mchoi/w1357-structural-grounding-20261006`
Baseline: `58145e73667e0a4f43589e9d37199fc90d293ee8`
Scope: validated nonrecursive DML, shared candidate generation and Exact/DP. Recursive functions are explicitly excluded by the user. This report supersedes the intermediate structural-certificate implementation and its earlier measurements.

## Decision and cleanup plan

The user's loop has an initial supply, a carried read, a body output/backedge, and an exit. The initial supply may use a different placement: its selected conversion must satisfy the existing entry relation. The read and backedge must obey the existing loop compatibility constraint. These constraints remain; an additional search proving source reachability is redundant within this program model.

Before deleting production code, the plan was to retain physical legality and all selected-input obligations, remove both source traversal and the intermediate certificate/fallback, move wholly undefined-variable rejection tests to the frontend, retain physical negative tests using incompatible initialized entries, and verify loop/function boundaries, finite plan sets/costs, runtime witnesses and the Docker compile lane. Independent architectural review accepted the plan and the final production diff.

## Implementation

- `NativePlacementContinuity.java`: delete candidate SCC grounding, recursive SCC refinement, acyclic source propagation and coarse Boolean source propagation. Delete the intermediate `orderedInputClosure` certificate, input-kind snapshot and conditional fallback. After existing dead-dependency pruning, surviving alternatives supply the physical support relation directly.
- Preserve worker/range/layout compatibility, direct map/materialization authority, exact producer/input bindings, generated-root obligations and dead-alternative pruning. A non-direct empty leaf remains invalid. Names of support-state locals now describe support, not a source-grounding proof.
- `SearchSpaceMetrics.java`: remove the SCC recording operation and mutable counters. Historical SCC snapshot fields remain constant zero; `PROOF_GROUNDING` remains an unused diagnostic schema entry. Existing dependency-pruning work has its own accurate phase name. Neither item performs validation.
- No new DP factor, decision variable, rank, boundary state, cost term, dependency, runtime fallback or feature flag. Structural dependency components still used for memo invalidation and ordinary cycle-safe graph traversal remain necessary.

The Native helper now has an explicit **conditional physical compatibility** contract. Not every temporary helper context contains every program source: ordinary reaching-definition contexts can omit a nonrecursive function result. Final `LogicalBoundaryRealizations` combines function-result/formal and ordinary CFG sources, and Exact's existing logical-boundary factors check the selected source/target compatibility. Therefore complete program derivability is not asserted independently by each temporary helper.

Relevant unchanged enforcement points:

- `PlacementProgramFacts.java`: incoming CFG definitions and loop backedges.
- `LogicalBoundaryRealizations.java`: all function and ordinary boundary sources, per-source relations and exact selected compatibility.
- `ExactPhysicalModel.java`: strict transient and logical-boundary factors plus physical realization support.
- `CandidateSelections.java`: conjunctive checks over ordinary and logical reaching writers.

## Test boundary and corrections

Frontend tests reject an entirely undefined loop-carried variable and mutually undefined carried variables before Hop/planner construction. **Frontend validation does not prove definite initialization on every conditional path.** A trial test assuming that it rejected one-sided branch initialization was incorrect and was removed; the old grounding algorithm did not prove that property either. This change preserves the existing feasibility contract rather than introducing a new definite-assignment guarantee.

Four tests exclusively of the deleted private SCC algorithm were removed from `NativePlacementGroundingAndSignatureTest`; its three signature/overlay tests remain. Source-free synthetic helper cycles are outside the revised helper contract. Existing AND-input, cache revision, binary/nary and reaching-definition rejection tests now use incompatible initialized loop entries, preserving the physical negative coverage. Root-pin deduplication still tests deduplication, without asserting source validity of its invented self-cycle. The intermediate certificate-specific tests were removed or replaced by compatible/incompatible entry-revision and memo-independence tests.

A trial PRIVATE_AGGREGATE function-result-to-loop fixture had no privacy-safe placement; the supported public fixture is used for the function-boundary regression. No production privacy rule was relaxed. Ordinary PRIVATE_AGGREGATE loop fixtures remain covered.

## Verification before integration with newer main

Final Java verification: **27 classes, 259 tests, 0 failures, 0 errors, 8 existing skips; 251 passed**. Core classes were rerun after the final production edits; the six frontend/real-program tests pass. `mvn -q -DskipTests package` and `git diff --check` pass. The new nonrecursive function-result entry test also passes against the frozen baseline Docker JAR (SHA256 `58c889c77fb9d6dcd2ba54659a9410b608303d1f594b542eca7447239f594dc1`), so it checks an existing supported case.

Logs/receipts are under `.omx/structural-grounding-evidence/`: `deletion-extended.log`, `deletion-final-core.log`, `deletion-frontend-function.log`, `deletion-regression-summary.json`, `deletion-function-baseline.log` and `deletion-package.log`. The final-core log records the earlier incorrect function test assertion; the later frontend-function log and fresh XML summary establish the corrected six-test pass. Failed experimental assertions are kept as evidence. Previous certificate-version totals and timings are historical, not final deletion evidence.

No default dedicated Java lint/security-analysis gate is configured here. Java compilation/test compilation, scope review, independent architecture review and whitespace checks are the static checks performed.

The independent finite loop-entry oracle covers 29,400 raw assignments, 280 admitted receipt assignments and seven physical entry/steady-layout combinations. Entry upload costs remain positive and equal at T=1,2,10. Two-worker ROW/BROADCAST runtime witnesses pass. Tests also cover nested loops in nonrecursive functions, zero-trip exits, multiple reaching writers, generated roots, mixed worker pools, exact receipt assignments, DP reductions and regional boundaries, memo revisions and fixed-point stability.

## Scope limits

`PolicyGreedyPlacementSelector` retains its separate selected-value validation. It serves FedAll/Heuristic, not the Exact/DP lane requested here. This is not a claim of deletion across every planner. Recursive functions are outside scope. The bounded oracle and compile comparison do not prove all-DML completeness or training-runtime speedup. No new test is skipped to obtain a pass.

## Commands

```sh
mvn -q -Dtest=NativePlacementContinuityTest,NativePlacementGroundingAndSignatureTest,NativePlacementPruneOwnerTest,LoopEntryMaterializationTest,LoopEntryCompletePlacementSpaceTest,LoopEntryRuntimeWitnessTest,PolicyGreedyGroundingTest,GlobalReceiptPlanSpaceCompletenessTest,MaterializedContinuityTest,MaterializedOutputLayoutTest,TransientPlacementAlternativesTest,LoopSeedReplayWideningTest,IndependentCompletePlacementSpaceTest,BaseCandidateOwnershipTest,DerivedFoutNormalizationTest,DerivedFoutMaterializationAuthorityTest,NativeMixedWorkerPoolContinuityTest,NativeLineagePlanSpaceCompletenessTest,CandidateReceiptAssignmentCompletenessTest,ExactPhysicalReducedSolverTest,IncrementalBoundaryMessageTest,IncrementalRegionalOptimizerTest,ExactFunctionAliasGetCostTest,NeutralPlacementFixedPointCompositionTest,SearchSpaceAttributionTimingTest,SearchSpaceLiveMetricsTest -Dcheckstyle.skip -Drat.skip=true test
mvn -q -Dtest=StructuralGroundingPlanSpaceTest test
mvn -q -DskipTests package
git diff --check
bash scripts/fedplanner/run_LAN_docker.sh --pruning-ablation \
  --root /home/mchoi/w1357-structural-grounding-20261006-docker-deleted \
  --stage /home/mchoi/w1357-stage-privacy-test-only-e21cac2-07726cd \
  --evaluation-root /grid/3/cofee-lm-sweep-mchoi-20260914/pruning-ablation-20261006/evaluation-snapshot \
  --renderer-file /home/mchoi/w1357-pruning-ablation-20261006/pruning-deletion-main-v1/renderer-source.py \
  --cost-profile /home/mchoi/w1357-pruning-ablation-20261006/pruning-deletion-main-v1/cost-profile.json \
  --variants baseline --workloads logreg --workers 3 --profile wan_mid --repetitions 1
```

## Docker comparison before integration with newer main

Both frozen baseline and full-deletion candidate pass `run_LAN_docker.sh --pruning-ablation`, logreg, W3, WAN-Mid, DP-local, one fresh compile-only JVM each. All non-code manifest identity fields match (image, dependencies, input, renderer, profile and resources); only JAR and the two intended production source hashes differ. Every production file at that measurement revision matched the candidate manifest. Containers were cleaned up successfully.

- Entire pruning receipts are identical, including plan fingerprint `a220ac1a567116b566bda9a76a77ab1957aee9018185baa459d6b0098768112b` and objective bits `4682065009762557211`.
- Raw values/cells: 35,873 / 8,759,394. Reduced values/cells: 25,952 / 5,742,281.
- Missing physical hops, missing synthetic operations and lowering mismatches: zero on both.
- Both stop at the existing RESOURCE limit; this does not establish global optimality.
- Baseline/deletion compile seconds: 29.371068 / 29.872102; analysis seconds: 19.962071579 / 19.693095447. These single cold samples establish no reliable speedup. No workload training execution was performed in this Docker lane.

Frozen result roots: `/home/mchoi/w1357-structural-grounding-20261006-docker-baseline/` and `/home/mchoi/w1357-structural-grounding-20261006-docker-deleted/`. Machine-readable comparison: `.omx/structural-grounding-evidence/deletion-docker-comparison.json`.

## Integration into origin/main

The original baseline above is the source of the deletion A/B comparison. Before publishing, this commit was rebased onto newer `origin/main` commit `49509ab7f8` (joint input and function boundary integration). The session issue log had an append conflict; both histories were retained. Production code merged automatically. The new `_PLACEMENT` unary handling and final joint/function boundary constraints were preserved. Independent interaction review found no conflict with the source-grounding removal contract. Regression and Docker smoke results for this integrated revision are recorded separately below; the original A/B timings are not measurements of this newer main.

Integrated Java verification: **36 classes, 329 tests: 317 passed, 8 existing skips, 4 failures, 0 errors**. All four failures reproduce with the same test methods against the clean, unmodified `49509ab7f8` production classes in `/home/mchoi/w1357-joint-main-20261006/target/classes`; they are inherited baseline test/representation mismatches, not a passing suite:

- `NativePlacementContinuityTest.transientReplayProjectsNativeAlternativesToStableExistentialCertificate`: expected one projected certificate, observed two on both builds. The unchanged assertion predates the upstream retained boundary alternatives.
- `IndependentCompletePlacementSpaceTest` methods `privateExactHardModelMatchesIndependentCompleteUniverse`, `privateAggregateExactHardModelMatchesIndependentCompleteUniverse`, and `deletingOneAdmittedModelRowFailsCompleteness`: `geometry(receipt.provenWorkerPool())` assumes an attached exact anchor, while upstream VALUE_MAP receipts can carry their map through selected input bindings. All three fail at the same assertion on both builds. This oracle was not weakened or disabled.

The updated upstream loop-entry oracle passes with **32,928 raw assignments, 292 admitted assignments, ten physical combinations**; upstream intentionally added body-normalization alternatives. The older seven-combination result above belongs only to the original baseline. New joint/function boundary, map, cost and source-removal regressions pass. Packaging (`mvn -q -DskipTests package`) and whitespace validation pass. Logs: `main-integration-tests.log`, `main-integration-summary.json`, `main-baseline-*.log`, `main-integration-package.log` under the same evidence directory. The four inherited oracle failures remain follow-up work for the joint-boundary changes; no production fix or test skip was introduced for them in this source-grounding commit.

## Separate integration repair: replay fact-list resizing

The newer unmodified `49509ab7f8` fails the same logreg Docker compile with `ArrayIndexOutOfBoundsException: Index 756 out of bounds for length 756` in `closePrePrivacyValueMaps`. The source-grounding integration fails identically before this repair. Frozen roots are `...-docker-main-baseline` and `...-docker-main`. This is an additional upstream production defect, distinct from the four stale test expectations above.

A separate small fix in `PlacementRelationClosure.java` replaces positional old/new fact-list comparison with the existing `changedCandidateOccurrences` helper. It reports changed owners for additions, removals and reordered facts while preserving the existing changed-ordinal union. No candidate is skipped to avoid the error. `DirectedDirectClosureDirtyConeTest` adds a shrinking/reordered-list contract test; it validates the replacement helper semantics, while the actual call-site regression is established by the Docker failure and rerun. Independent review: CLEAR.

After this repair, the 16 affected integration test classes pass **134/134**, with no errors or skips (`main-replay-fix-tests.log`, `main-replay-fix-test-summary.json`). This focused rerun does not erase the four pre-existing oracle failures documented above.

Final integrated Docker runtime validation passes for `joint_loop_toggle` and `joint_function_calls`: both CP and federated numeric fingerprints match, model-proof preflight passes, and runtime conversion violations are zero. Evidence: `/grid/3/cofee-lm-sweep-mchoi-20260914/grounding-main-joint-20261006/grounding-main-final-20261006/result.json`. Command:

```sh
bash scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e \
  --run-id grounding-main-final-20261006 \
  --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/grounding-main-joint-20261006 \
  --case joint_loop_toggle --case joint_function_calls
```

The large logreg compile now passes the repaired replay stage but reports `EXACT_VE_FACTOR_CELL_OVERFLOW` when constructing its cost surface. This run is a failure, not a passing full-workload compile. Its baseline attribution is recorded below. Neither the factor limit nor legal candidate space was reduced to bypass it.

Attribution complete: latest main **with only the replay-index repair** also fails logreg with `EXACT_VE_FACTOR_CELL_OVERFLOW` at the same cost-surface validation site. Both repaired runs pass the former index-error location. Manifest comparison confirms identical non-code inputs and identical replay repair; the only production source differences are NativePlacementContinuity and SearchSpaceMetrics. Thus this is an inherited large-model limitation, not a newly observed source-grounding regression. The experiment does not establish a performance speedup or successful large logreg compile on the new main. Both containers were cleaned up. Evidence: `main-final-docker-attribution.json`, `main-baseline-replay-fixed-docker.log`, `main-replay-fix-docker.log`; frozen roots end in `-docker-main-baseline-fixed` and `-docker-main-fixed`. The final production source hashes match the candidate Docker manifest.
