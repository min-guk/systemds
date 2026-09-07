# Session issues — 2026-09-07

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

All compiler fixtures use `PRIVATE_AGGREGATE`; there are no new public-policy fixtures. These are static compiler/solver tests. No runtime counter calibration or Docker workload performance comparison has been performed; any such follow-up must use `run_LAN_docker.sh` under the repository instructions.

### Issues encountered, remaining limitations and regression detection

1. **Resolved — generalized provenance reached a legitimate loop alias cycle in StepLM.** The older latent-only resolver had required acyclic inputs; applying it to all transfers exposed ordinary loop-backedge aliases. Incomplete provenance now preserves ordinary read scope while latent authority remains strict. Detect with `ExactPhysicalModelCertificateTest`'s seven-workload fixture.
2. **Resolved — contribution names hid existing fused-transfer tracing.** Monetary class and bound kinds now retain the `|RUNTIME_FUSED_INPUT|` token, while compatibility factors use a separate kind. Detect with the existing single-worker ALS reusable-input cost assertion.
3. **Resolved — old union test called unequal-frequency events duplicates.** It now separately checks equal-frequency duplicates and the conservative bound when no containment witness explains unequal counts. Detect with `CampaignBG014AlsPartitionedComputeCostRedTest.reusableRuntimeMaterializationUsesControlFlowEventUnion`.
4. **Resolved — parser fixtures must expose real live occurrences and legal download alternatives.** A single-block DML function was inlined, so the fixture now uses a multi-block function whose two caller contexts remain observable. The scope fixture uses declassified `rowSums` results with legal ROW/FOUT alternatives and consumes the carried value before updating it, preserving a real loop TRead. No privacy or placement rules were relaxed.

Remaining model limits: unknown overlap may be conservative and expensive to factor; unresolved alias provenance may overcharge; no new cross-group reuse proof is introduced; actual runtime counts/bytes have not been measured. Potential regressions are incorrect scope/context merging, loss of zero-frequency feasibility, arithmetic regrouping and factor-width growth. The new scope/context fixtures, exhaustive class tests, existing certificate tests and GLM preflight/scalability regression cover these risks at the encoded-objective level.
