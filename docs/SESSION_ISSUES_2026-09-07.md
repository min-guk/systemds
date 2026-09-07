# Explicit input binding search on so008

## Independent operator and ordered-use supply variables — implemented and verified

- **Problem**: the manuscript's alpha-only presentation does not expose the binding decisions needed to distinguish native supply, local materialization and relocation/reuse. The user requested an algorithm implementation with independent `X_o` and `Y_u`, not a wording qualification.
- **Environment**: so008 / `130.149.237.18`; baseline commit `8c929e1370fa7934b3daefe45b7a59ae01896945` is authenticated by the existing comparison-stage source provenance. Worktree branch `feature/explicit-input-bindings-20260907`; local and remote isolated source path `/home/mchoi/so008-explicit-binding-search-20260907`. Logs and source manifests live in `/home/mchoi/so008-explicit-binding-search-evidence-20260907`.
- **Observed implementation**: production `COMPILE_EXACT` invokes global categorical variable elimination; `COMPILE_COST_BASED` invokes `LocalPhysicalOptimizer`. The legacy `fedDp` implementation is not the factory route. `ExactPhysicalModel.inputAuthorityProducts` already enumerates admissible input authorities inside each physical alternative, including exact relocation identities. The new coordinates therefore expose existing packed choices rather than proving additional runtime capabilities.
- **Decision and applicable principles**: preserve the graph-owned admissible complete-plan catalog, privacy, runtime capabilities, exact value/use/action identities and grouped movement charging. Transform the solver coordinates into independent operator and supply domains, pull back compatibility/cost factors, and reconstruct the selected authority-bearing plan losslessly. No runtime fallback or implicit correction of a selected binding.
- **Implementation**: `ExactPhysicalBindingModel` separates semantic operator identity from `Alternative.inputAuthorities`. It creates an `X` domain per operator and `Y` domains per authority-bearing ordered use. `COMPILE_EXACT` solves these coordinates and the original max-demand auxiliaries. The old packed variables are not retained as extra solver decisions. An admission factor rejects products absent from the authorized catalog; original legality and cost factors are pulled back through the exact row mapping. `ExactPhysicalSelection` preserves the actual searched binding certificate, reconstructs the exact catalog rows, validates local-copy authority, and passes selected materializations explicitly to the normalized result. The objective certificate includes a binding fingerprint. LocalCost keeps its existing packed search and shares the corrected canonical cost surface.
- **Files**: `fedExact/ExactPhysicalBindingModel.java`, `ExactPhysicalOptimizer.java`, `ExactPhysicalSelection.java`, `ExactPhysicalPlacementProjector.java`, and `placement/adapter/NormalizedPlannerResults.java`; tests `ExactPhysicalBindingModelTest` and `ExactJointBindingIntegrationTest`. Paths are relative to `src/main/java/org/apache/sysds/hops/fedplanner/` or the corresponding test tree.
- **Verification**: final evidence and reproduction command are recorded below. The tests check catalog-row round trips, pulled-factor values, packed/global optimum parity, selection of different common anchors with *all* operator coordinates fixed, invalid native/copy combinations, and preservation or rejection of binding receipts at projection.
- **Residual issues**: full manuscript experiment reruns and distributed numerical correctness/performance have not been performed. The admitted materialization catalog is unchanged; this implementation does not create new runtime capabilities or enumerate arbitrary existing copies outside that catalog.
- **Potential regression and detection**: pullback scopes can increase induced width and hit resource limits; monitor solver statistics and retain explicit limit errors. Local requirements must distinguish native local values from downloaded copies without duplicating function-boundary or eliminated-input ownership. Tests must detect lost action identities, unsupported cross-products and noncanonical selected bindings being overwritten during commit.

### Search semantics and the second proposal

For each authorized packed row, the implementation separates its operator fields from its input authorities. A legal complete `X,Y` assignment reconstructs one exact row at each operator. Conversely, each legal complete packed assignment has one executable `X,Y` encoding once producer state determines whether a local requirement uses native local output or a downloaded copy. Costs are evaluated on that same reconstructed plan. Thus the optimum agrees with the corrected packed objective over the represented catalog.

Bindings retain source occurrence, consumer occurrence, ordered input position, boundary ownership, and exact authority/action identity. Compiled scalar/metadata placeholders and latent fused inputs are not turned into independent physical supplies. Logical function and transient inputs keep their own ownership.

`NATIVE_LOUT` and `LOCAL_COPY` describe distinct executable supplies; producer compatibility forbids choosing the wrong one. `DIRECT_FOUT` and `RELOCATION` preserve the exact admitted anchor/action witness. Source version, mapping, scope, privacy, and release legality remain enforced by the original graph-owned constraints and receipts.

No separate `Z_r` decision is necessary: existing max-demand auxiliaries charge a materialization exactly when a selected demand activates it. Singleton domains add no branching, and existing exact preprocessing removes infeasible values and merges only values indistinguishable to **all incident factors**. The coordinate layer still represents deterministic supply states; it does not promise to remove every conditional deterministic `Y` before factor construction. Equal isolated movement prices alone never justify dropping a binding, because sharing and compatibility may distinguish it.

The implementation keeps one reverse-index row per packed alternative. It normalizes `LOCAL_COPY` to the corresponding local-requirement coordinate during lookup instead of storing all `2^k` native/copy aliases. This normalization does not collapse the selected executable supply: the source compatibility factor and selected binding certificate retain it.

The conservative pullback currently includes a decision's full `X/Y` scope whenever an original factor references that decision. Dependency-specific factor scopes and direct construction without the packed catalog are possible future scalability work. The production limits remain 10,000,000 factor cells and 50,000,000 materialized cells; exceeding them remains an explicit failure with no packed-solver or runtime fallback.

A supported manuscript statement is:

> Global jointly optimizes operator alternatives and the input-supply choices admitted by the represented physical catalog, under the encoded objective and exact-solver resource limits.

This does not establish optimality over arbitrary physically possible bindings or a speedup over the original optimizer. In this baseline some binding alternatives were already packed into operator rows; exposing `Y` makes the decision structure explicit, while the shared-copy cost repair below can change actual selected plans.

## Shared local copies across compiled and logical consumers — fixed

- **Symptom**: compiled FOUT-to-local downloads were grouped for CP consumers, while FED consumers with `NATIVE_LOCAL` inputs added the same download separately on each edge. Shared use could therefore be charged repeatedly, and local-held CP/FOUT or derived-FOUT output could receive an unnecessary download charge.
- **Cause**: the canonical compiled-transfer grouping only activated CP consumers. The native-local input factor combined its per-consumer upload/preparation with a reusable producer download.
- **Repair**: extend the same grouped download factor with alternative-specific FED native-local demands, preserving frequency weights and bounded byte estimates. Charge the largest active weighted demand across CP and FED users. Download activation requires a native FED/FOUT source, excluding CP/FOUT, LOUT, and derived-FOUT sources that already hold a local value. Remove the duplicate edge download; retain consumer upload/preparation costs and exact anchor worker counts. Deduplicate endpoint entries introduced by alternative-specific demands.
- **Applicable principle**: correct the cost model rather than exclude supported plans or relax privacy/runtime constraints.
- **Files**: `fedExact/ExactPhysicalCostModel.java` and `ExactSharedLocalCopyCostTest.java`.
- **Validation**: a compiler-built private-aggregate graph verifies one shared group for CP plus two FED local-input consumers and max charging across active demands. A focused unequal-demand test checks inactive zero, FED/FED maximum, CP/FED maximum, and local-held source zero. Existing max-decomposition and native-anchor fanout tests are also included.
- **Test fixture correction**: the graph includes a mandatory CP consumer, so its nominal inactive assignment still has a positive baseline demand. The graph test compares against that baseline and the maximum of individual activations; a separate explicit factor tests truly inactive and unequal demands. Requiring the graph's FED demand to exceed its mandatory CP demand was an invalid fixture assumption.
- **Remaining risk**: the corrected shared objective can change both Exact and LocalCost choices. Planning tests cover these paths; published performance/quality tables require rerunning the Docker campaign before updating quantitative claims.
- **Final-review correction**: logical-function CP inputs were still charged separately even when lowering combined their obligations into the same local-copy action as compiled consumers. Their exact weighted demands now join the same source download group; pairwise logical downloads are removed. Nested function boundaries are included when they are actual effective logical sources. Logical relocation uploads also join the existing upload group by source, FType, boundary mode, and physical emission identity, retaining per-demand bytes, frequency, and refederation download costs. Original action compatibility and privacy constraints remain in force.
- **Logical fixture correction**: two function calls in separate statement blocks read different exact source occurrences, even when their DML variable names match. A same-source sharing fixture must prove source identity and activate each represented logical demand; factor-scope inclusion alone is insufficient evidence of shared charging.
- **Logical regression evidence**: a retained `f(A,A,rowSums(A))` call provides two distinct formal reads and one compiled consumer of the identical actual source. The test checks zero inactive demand, a positive download for each use in isolation, and the maximum when all three are active. A loop keeps the function boundary from being inlined. The original CP/two-FED-local test remains separate.
- **Upload coverage limit**: the retained fixture admits only `NATIVE_LOCAL` and `DIRECT_FOUT` for the two formals, so it cannot prove shared logical-relocation activation. The test instead verifies that those formals introduce no unauthorized upload demand. Logical relocation grouping is reviewed code with no dedicated positive shared-relocation fixture in this change; the existing max-demand and compiled grouped-upload tests cover the common algebra and compiled path. Distributed relocation/reuse execution and performance remain outside this validation.

## Missing selected local materializations silently regenerated — fixed

- **Symptom**: a negative integration test supplied an empty materialization list for a plan that required a local copy, but normalization accepted it.
- **Cause**: the legacy normalized carrier treats an empty list as an instruction to derive local materializations. That behavior cannot represent an explicit selected binding certificate with missing actions.
- **Repair**: add `createWithPhysicalSelections` with a strict comparison against the validated required local-materialization set, including the empty-list case. Exact projection uses this entry point. Legacy callers keep their established derive-first API. Joint optimizer results also reject a missing searched binding list.
- **Applicable principle**: preserve selected physical authority and reject incomplete plans; do not repair them implicitly during commit.
- **Files**: `placement/adapter/NormalizedPlannerResults.java`, `fedExact/ExactPhysicalOptimizer.java`, `ExactPhysicalPlacementProjector.java`, and `ExactJointBindingIntegrationTest.java`.
- **Validation**: removed binding receipts, omitted local copies, and a missing joint-search binding list must be rejected; valid alternative bindings survive normalized projection.
- **Remaining risk**: future additional local materialization candidates require extending the explicit representation and its validator together. This API still validates the current represented local-copy construction.

## Use-level bindings missing after normalized projection — fixed

- **Review finding**: although selected relocation/local-copy actions and the binding fingerprint survived projection, the individual use-level binding records stopped at `ExactPhysicalSelection`. The initial integration tests only compared movement lists and the fingerprint string.
- **Repair**: add a neutral immutable `InputBindingReceipt` with source/value version, consumer/use ownership and position, supply kind, and exact movement action. Carry it through normalization, `ExactPlacementInput`, emission receipt attachment, canonical content hashing, and commit prevalidation. Projection verifies that every selected record survives.
- **Regression proof**: compare full receipt lists, change only the binding list while keeping states/candidates/objective/fingerprint fixed and reject the tampered plan, then commit the original plan and retrieve the preserved receipt list. Also exercise a Y-only preference objective at fixed X and reject conflicting consumer anchors.
- **Validation**: `clean-binding-commit-v2/` passed all three joint integration tests from a clean compilation. Binding-only tampering changes the canonical content hash and is rejected before mutation; the original normalized plan commits and retains its full receipt list. The earlier 46-test run remains valid evidence for its recorded source snapshot, but did not cover these two review findings.
- **Fixture correction**: the emission test must use the parser's canonical final-Hop authority binding. An independently built detached analysis cannot authorize a program commit. The test now uses `CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary`; production authority checks are unchanged.
- **Validation boundary**: the neutral carrier validates supplied receipt identities, selected supplies, actions, and uniqueness. Exact selection and projection additionally enforce completeness and equality with the searched binding list. Legacy planner APIs still allow an empty receipt list. A receipt list alone is not a standalone proof of candidate compatibility or complete-plan validity; existing graph-owned candidate and relocation validators remain necessary.

## Reproducible isolated validation

- **Final source validation**: `clean-explicit-binding-final-v1/` passed **48 tests, zero failures, zero errors, zero skips** after a clean main/test compilation on so008, from 21:50:40 to 21:52:05 UTC on 2026-09-07. Source manifests before and after the run are identical. This snapshot includes all coordinate, shared compiled/logical download, neutral receipt, and commit repairs.
- The final suites cover categorical solving (12), exact reduction (11), max-demand decomposition (9), X/Y coordinates (4), joint selection/projection/actual commit (3), shared-copy cost (3), native-local anchor fanout (2), PA Exact StepLM and KMeans planning (1 each), the inlined-function physical certificate (1), and PA LocalCost StepLM planning (1). `git diff --check` also passed. Maven compiles the complete Java main/test source trees; no separate lint/static-analysis plugin is configured in this POM.
- The untouched baseline core run passed 38 tests in `baseline-core/`.
- The intermediate changed-source run in `clean-joint-regression-v1/` passed **46 tests, zero failures, zero errors, zero skips**, from a clean source/test compilation. It ran from 21:06:52 to 21:08:11 UTC on 2026-09-07; before/after source manifests are identical. This predates the neutral receipt carrier and logical transfer review repairs. Treat earlier non-clean runs as diagnostic evidence, not the final source-to-binary proof.
- Each run stores its command, Maven log, JUnit XML, start/end times, and SHA-256 manifests of `pom.xml` and all Java source files before and after the run. A changed manifest makes the runner fail.
- The initial Maven cache path was one directory too high and caused an offline parent-POM resolution failure before compilation. The correct cache path ends in `m2/repository`.
- Source synchronization preserved modification times, allowing a subsequent incremental build to reuse an older test class. The runner now uses `clean test`, rebuilding from the exact recorded source snapshot.
- Run only one build per isolated target directory. Build processes use CPUs 32–39 and one test fork; existing experiments and their workspaces are unaffected.

On so008, the final regression command is:

```bash
python3 /home/mchoi/so008-explicit-binding-search-evidence-20260907/run-tests.py \
  clean-explicit-binding-final-v1 \
  'ExactCategoricalSolverTest,ExactPhysicalReducedSolverTest,ExactMaxDemandFactorDecompositionTest,ExactJointBindingIntegrationTest,ExactPhysicalBindingModelTest,ExactSharedLocalCopyCostTest,ExactNativeLocalAnchorFanoutCostTest,CampaignBG014ExactDerivedFoutAnchorSelectionRedTest,CampaignBG014ExactKMeansGroupedUploadAuthorityRedTest,ExactPhysicalModelCertificateTest#inlinedFunctionPhysicalSelectionKeepsInputProvenanceAndOutputAuthority,StepLmPrivateAggregatePlanningContractTest'
```

Use a new evidence label to repeat the command. The runner invokes offline Maven with the existing dependency cache, `-DskipTests=false -Dmaven.test.skip=false`, and `clean test`. StepLM/KMeans checks compile and lower plans without executing their distributed numerical workloads. Any later workload or performance experiment must use `run_LAN_docker.sh`; no physical-host campaign was launched in this session.

## Activation-class materialization objective — resolved

- **Request**: replace reusable maximum-demand charges with creation-scope activation classes and Boolean OR factors; Global must continue to optimize the encoded finite objective exactly.
- **Isolation**: source base `8c929e1370` from the clean `/home/mchoi/g014-glm-followup-systemds-20260906` checkout. Local branch `codex/activation-class-materializations-20260907`, worktree `/home/mchoi/so009-activation-classes-20260907`. Independent source snapshot and build output at the same path on `so009` (`130.149.237.19`). Existing experiment stages and unrelated working-tree edits are outside this change.
- **Problem / cause**: `ExactPhysicalCostModel.addPhysicalCompiledTransferFactors` represents reusable transfers by `ExactMaxDemandFactorDecomposition`; two disjoint half-frequency demands are consequently charged only one half. Latent fused inputs already use a control-flow union estimate, but only same-event inputs receive bounded factorization.
- **Implementation boundary**: the paper's Global variable-elimination selector is the `fedExact` path. The common physical objective is also used by Local selection. Preserve the search algorithm, privacy rules, candidate domains, materialization identity and execution legality.
- **Execution plan**:
  1. Capture baseline and build in the isolated server workspace.
  2. Represent a single activation class with Boolean OR auxiliaries and an atomic `q_k * c_r` terminal charge. Keep each class as a separate canonical contribution so solver/canonical arithmetic have identical atomic terms.
  3. Derive class membership and multiplicities from branch and occurrence frequency/context facts at the reusable creation scope. Preserve separate productions and function call contexts.
  4. Replace reusable compiled-transfer max factors; reuse the same class semantics for latent fused inputs where control flow proves the required partition.
  5. Verify coactivation, exclusive branches, subsumption, loop-invariant and loop-updated scope, function contexts, unresolved overlap, fingerprint binding and exhaustive canonical/factorized objective parity. Run existing physical cost/solver regressions and package compilation.

### Semantics and implementation

The second user formulation is the semantic contract: the charge follows the union of demand activations **within one creation lifetime**, then sums over lifetimes. The first formulation supplies the finite factor encoding. Existing physical transfer groups have a common per-creation cost for a fixed producer alternative (source state, layout, direction and emission identity); they therefore use:

```
C_r(P) = sum_k q_k * c_r(P) * OR(selected demands belonging to class k)
```

- `OccurrenceExecutionFrequencyFacts` now retains branch decision identity, selected arm, conditional probability and enclosing loop IDs in each call-context profile. Caller conditions propagate into function bodies; internal function decisions retain distinct context identities. Existing scalar frequency assumptions, including unknown-branch/loop defaults, remain static estimates.
- `ExactMaterializationActivation` builds a finite partition when every pair is a proven duplicate, a proper branch-condition containment, or mutually exclusive. It builds a containment tree and assigns each node's residual multiplicity to the OR of its own and its ancestors' demands. No independence assumption is introduced.
- Identical conditions with unequal weights do **not** establish nesting: scalar counts alone are insufficient. These events use the conservative case unless a stronger witness is added in future. Inconsistent child multiplicities also use the conservative case.
- `ExactActivationClassFactorDecomposition` represents each class with Boolean accumulators. Each link enforces `next = previous OR demand`; the terminal source/accumulator factor charges `q_k*c_r`. Duplicate observations of one decision variable merge their masks. Original decision alternatives remain intact; deterministic factors use zero/infinity and introduce no monetary rounding.
- `ExactPhysicalCostModel` uses the same partition/encoding for ordinary compatible transfers and latent fused inputs. Latent demands first observe the conjunction of CP owner and FOUT read; read/source compatibility remains a separate feasibility factor, including for zero-frequency classes.
- Each class remains one canonical monetary contribution and one solver monetary term. This preserves raw-double-bit objective parity through the solver's compensated summation. Descriptors bind scope, event weights, literals, membership, source prices and demand masks into the existing cost fingerprint/preflight path.
- The unused `ExactMaxDemandFactorDecomposition` and its tests were removed after replacement. No changes were made to variable elimination, candidate generation, privacy rules or runtime execution.

### Creation lifetime and unknown overlap

- Ordinary transient/formal reads use compiler-owned logical provenance to recover the actual payload's creation profile when there is a unique source and matched context. A read inside a loop no longer automatically multiplies an outer value's creation cost. Updated or ambiguous reaching values retain their own production/read lifetime.
- A loop-backedge alias cycle cannot establish a unique creation source. Incomplete resolution propagates to ordinary costing, which retains the read scope; it does not authorize hoisting. Latent input authority still rejects unresolved sources, preserving its previous strict requirement. This is static scope handling, not a runtime fallback.
- A branch inside a consumer-only repeated loop is not a mutually exclusive **lifetime** event: both arms may occur during the same copy lifetime. These existence events receive distinct opaque arm identities and conservative scope-capped occurrence estimates. Stable branches outside those loops retain their conditional weights.
- Missing structured occurrence profiles are explicitly marked when the legacy frequency builder synthesizes them. Exact profile access rejects them rather than interpreting empty conditions as proven coactivation. Non-exact ordering APIs retain their prior conservative estimates.
- Physical transfer identity and grouping boundaries are preserved. This change does not introduce speculative sharing between distinct producer groups or unresolved source versions.

For unresolved correlations, the entire compatible group uses an explicit `CONSERVATIVE_CAPPED_ACTIVATION_UNION` factor: discard proven duplicates/subsumed active events, sum the remaining weights, and cap at the creation frequency. This keeps the unknown-overlap upper estimate for every selected subset. The factor is still part of the finite objective optimized exactly by Global, but it may have high arity and remains subject to the existing resource limits.

An arbitrary capped sum cannot always be replaced by fixed nonnegative OR classes. For example, three unknown events with marginal weight 0.6 and scope 1 would require every pair and the triple to have union 1. Inclusion-exclusion would then require a negative triple intersection. The implementation therefore does not fabricate an OR partition for this case. “Exact” refers to optimization of the encoded static objective, not measured transfer counts or unknown joint probabilities.

The second formulation also permits heterogeneous per-demand unit costs via the maximum of active unit costs within a class. Current compatible groups have homogeneous units, so the implementation uses OR. A future group with heterogeneous units must explicitly encode finite cost levels or a per-class maximum; averaging or taking a maximum of weighted frequencies would violate this contract.

### Validation evidence and reproduction

Build host: `so009` (`dams-so009`, `130.149.237.19`), Java 17, Maven 3.8.7. Both build output and dependency cache are isolated:

```
source: /home/mchoi/so009-activation-classes-20260907
cache:  /home/mchoi/so009-activation-classes-m2-20260907
branch: codex/activation-class-materializations-20260907
```

Use SSH with the already-known alias: `ssh -o BatchMode=yes -o HostKeyAlias=so009 mchoi@130.149.237.19`. No host-key checking was disabled. The remote Git repository starts with a source snapshot from `8c929e1370`; it is separate from the local repository's history and from existing campaign stages.

- Baseline isolated `test-compile`: **PASS** (`validation/baseline-build.log`).
- Baseline solver/max-factor/zero-frequency/fingerprint suite: **28 tests passed** (`validation/baseline-tests.log`).
- New class partition, Boolean OR and production-assembly exhaustive-assignment tests passed on so009, including opposite 0.5 branches charged as 1, coactivation, nesting, zero events, unknown correlation, loop scope, call conditions and raw-bit arithmetic.
- Existing seven-workload physical-model/certificate suite passed after conservative cycle resolution. The production scope/context fixtures also passed together (**7 tests**, no failures/errors): an actual PRIVATE_AGGREGATE `rowSums` result is reusable outside the loop, a loop-carried updated result incurs three charges, and a non-inlined function preserves both opposite caller contexts.
- `git diff --check`: **PASS**. Maven compilation uses the repository's configured `-Xlint:unchecked`; baseline unrelated warnings are not changed by this feature.

Final isolated verification: **`clean package` BUILD SUCCESS**, **95 tests, 0 failures, 0 errors, 0 skipped**, completed on so009 at `2026-09-07T23:28:36+02:00` in 2m23s. The build compiled production and test sources afresh, ran the targeted tests and produced `target/systemds-3.4.0-SNAPSHOT.jar` and the repository's additional package artifacts. GLM's exact factor profile was width 15, maximum factor cells 150,784, materialized factor cells 1,017,028. Final architect review found no remaining blocker.

Reproduce on so009 with `bash /home/mchoi/so009-activation-classes-20260907/validation/run-final-checks.sh`. This records the exact Maven arguments and all 15 selected test classes; the complete output is `validation/final-clean-package.log`. Local copies of the run script, log, test XML reports and reviewable patch are under `/home/mchoi/so009-activation-classes-validation-20260907`.

The new activation-specific compiler fixtures explicitly register `PRIVATE_AGGREGATE`. The original 95-test selection also included legacy fixtures with default `PUBLIC` source registration (including the certificate and GLM scalability fixtures); it must not be described as an exclusively PRIVATE_AGGREGATE suite. The initial blanket statement was incorrect and was corrected during the follow-up audit. These are static compiler/solver tests. No runtime counter calibration or Docker workload performance comparison has been performed; any such follow-up must use `run_LAN_docker.sh` under the repository instructions.

### Issues encountered, remaining limitations and regression detection

1. **Resolved — generalized provenance reached a legitimate loop alias cycle in StepLM.** The older latent-only resolver had required acyclic inputs; applying it to all transfers exposed ordinary loop-backedge aliases. Incomplete provenance now preserves ordinary read scope while latent authority remains strict. Detect with `ExactPhysicalModelCertificateTest`'s seven-workload fixture.
2. **Resolved — contribution names hid existing fused-transfer tracing.** Monetary class and bound kinds now retain the `|RUNTIME_FUSED_INPUT|` token, while compatibility factors use a separate kind. Detect with the existing single-worker ALS reusable-input cost assertion.
3. **Resolved — old union test called unequal-frequency events duplicates.** It now separately checks equal-frequency duplicates and the conservative bound when no containment witness explains unequal counts. Detect with `CampaignBG014AlsPartitionedComputeCostRedTest.reusableRuntimeMaterializationUsesControlFlowEventUnion`.
4. **Resolved — parser fixtures must expose real live occurrences and legal download alternatives.** A single-block DML function was inlined, so the fixture now uses a multi-block function whose two caller contexts remain observable. The scope fixture uses declassified `rowSums` results with legal ROW/FOUT alternatives and consumes the carried value before updating it, preserving a real loop TRead. No privacy or placement rules were relaxed.

Remaining model limits: unknown overlap may be conservative and expensive to factor; unresolved alias provenance may overcharge; no new cross-group reuse proof is introduced; actual runtime counts/bytes have not been measured. Potential regressions are incorrect scope/context merging, loss of zero-frequency feasibility, arithmetic regrouping and factor-width growth. The new scope/context fixtures, exhaustive class tests, existing certificate tests and GLM preflight/scalability regression cover these risks at the encoded-objective level.

## Follow-up robustness and ablation audit — continued September 8

- **Request**: distinguish the actual test coverage from a full-suite claim, assess robustness, and measure what changes relative to the max-demand baseline.
- **Isolation**: separate remote worktrees at `/home/mchoi/so009-activation-ablation-baseline-20260907` (source `8c929e1370`, remote snapshot `964805c`) and `/home/mchoi/so009-activation-ablation-current-20260907` (feature `a64f217`). Diagnostic test harnesses are copied only into those worktrees; the verified feature implementation is unchanged.
- **Independent oracle**: a fixed seed generates 32 structured branch trees. Sixteen concrete execution leaves are enumerated independently of the partitioner; every subset of five demands is checked with both source states. All **2,048 canonical-and-solver comparisons** pass. A separate fixed seed generates 64 concrete unknown-overlap event sets; all **2,048 subset bound checks** pass. The 3-test diagnostic suite passes on so009.
- **Encoding-only ablation**: the same current canonical cost factors and the same placement preferences are solved directly versus via OR auxiliaries. With 16 co-active consumers, objective and unique optimum agree, induced width changes from 16 to 2, maximum factor cells from 131,072 to 8, materialized cells from 262,177 to 257. With two exclusive classes over 16 consumers, width changes from 8 to 2 and maximum cells from 512 to 8. These are controlled factor-encoding measurements, **not** original-versus-new workload speedups; the original implementation already decomposed max factors.
- **Continuation**: see [the September 8 audit](SESSION_ISSUES_2026-09-08.md) for paired PRIVATE_AGGREGATE costs and selections, the two planning regressions that fail in both baseline and feature, the PRIVATE_AGGREGATE StepLM infeasibility, and the durable independent-oracle test. The original 95-test result does not establish a green full suite. Runtime creation/byte counters and Docker elapsed-time comparisons remain outside these static results.
