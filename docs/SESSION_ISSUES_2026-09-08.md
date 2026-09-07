# Session issues — 2026-09-08

## Merge explicit input-binding search into the current experiment source

- **Status**: source integration; delivery evidence is recorded in `MERGE_RECEIPT.json` under `/home/mchoi/so008-explicit-binding-search-evidence-20260907/`.
- **Request**: merge the verified explicit-binding implementation into the latest source used by the server experiments.
- **Target identification**: so008's `cofee-w1357-comparison-stage-20260905-8c929e1/FINAL_BACKEND_STAGE_PROVENANCE.json` identifies `/home/mchoi/g014-glm-followup-systemds-20260906` at `8c929e1370fa7934b3daefe45b7a59ae01896945`. The original branch is `cofee-glm-followup-20260906`; it was clean with no commits beyond that experiment baseline. The remote experiment stages contained pinned binaries, not a Git source repository. No Cofee process or Docker container was running on so008 at inspection.
- **Integration**: merge implementation commit `70b64d7345d02f2d22a9445f7c935d32865a263e` into that original branch, preserving both parent commits. There are no source conflicts. Materialize the merged source on so008 at the same canonical repository path, with its own Git metadata and source-to-test hash verification.
- **Changes**: independent operator/input-supply coordinates; exact catalog admission and compatibility factors; shared compiled/logical local-copy costs; selected binding receipts through projection, hashing, validation, and commit. See `SESSION_ISSUES_2026-09-07.md` for implementation and algorithm limits. This merge adds only this integration record beyond the reviewed feature.
- **Applicable principle**: merge source without weakening runtime, privacy, candidate, or value/use identity rules. Historical experiment stage provenance and pinned binaries remain historical evidence; source integration is not a replacement of an old experiment's backend.
- **Validation**: the feature's clean so008 build passed 48 tests with zero failures/errors/skips in `clean-explicit-binding-final-v1/`. The merged Java/POM source is compared against that run's complete 3,291-file SHA-256 manifest; `git diff --cached --check` validates the merged diff. There is no code conflict or additional implementation change requiring a repeat of the same test suite.
- **Remaining limits**: optimality is over the represented authorized catalog under the encoded objective and resource limits. Conservative factor scopes can increase induced width. There is no positive shared logical-relocation fixture, and no new distributed numerical/performance campaign was run. A new experiment must identify the new merged commit and its own built JAR; it must not reuse an old stage's approval or result identity.
- **Potential regression**: accidentally synchronizing a stale or different source tree, losing the feature during branch integration, or mixing new code with old experiment receipts. Detect through both-parent ancestry checks, a clean Git worktree, exact source-manifest equality, and the separately hashed merge receipt.

## Activation robustness and paired ablation audit — complete, limits recorded

### Question, scope and source authority

The user asked whether all tests were run, whether the activation change is robust, and what an ablation actually changes. The earlier successful build ran a selected 15-class, 95-test suite; it was never a full repository test run. The suite also included legacy compiler fixtures that register PUBLIC privacy. The earlier blanket claim that all compiler fixtures were PRIVATE_AGGREGATE was incorrect; the September 7 session document now explicitly corrects it. Follow-up compiler workload comparisons enforce PRIVATE_AGGREGATE.

Baseline source is `8c929e1370fa7934b3daefe45b7a59ae01896945`, represented by remote snapshot `964805c7dad22b47e79e490fb1407a3b28f6abaf`. Feature source is local `72ddfffd92a78413681fa338a643ababd02caa1b`, remote `a64f21748be4065c0dec4d352608dbb320116718`. Comparison worktrees on so009 are `/home/mchoi/so009-activation-ablation-{baseline,current}-20260907`. They have separate build outputs and share the isolated Maven cache `/home/mchoi/so009-activation-classes-m2-20260907`.

No production implementation was changed during this audit. The independent event-oracle test is now a durable source regression, `ExactActivationIndependentOracleTest.java`, with optional diagnostic report output. Candidate domains, privacy policy, lowering authority and runtime execution remain the contracts under test.

The durable activation suite was rerun in the primary isolated feature checkout: **35 tests, 0 failures/errors/skips, package BUILD SUCCESS**, completed `2026-09-08T00:05:49+02:00`. This is six selected test classes, including the new three-test oracle; it overlaps the original 95-test suite and must not be added to that count as 35 distinct new tests. Compiler fixtures in this follow-up suite explicitly use PRIVATE_AGGREGATE; synthetic factor tests have no source privacy input.

```
mvn -B -Dtest-forkCount=1 -Dtest-threadCount=1 \
  -Dmaven.repo.local=/home/mchoi/so009-activation-classes-m2-20260907 \
  -Dtest=ExactActivationIndependentOracleTest,ExactActivationClassFactorDecompositionTest,ExactMaterializationActivationTest,ExactActivationMaterializationCostTest,ExactCompiledMaterializationScopeTest,OccurrenceActivationContextTest \
  package
```

### Independent semantic oracle and encoding ablation

The diagnostic oracle passed three JUnit tests on so009:

- A fixed seed generates 32 structured branch trees. An independent enumerator checks their sixteen concrete execution leaves, all subsets of five demands and both producer states: **2,048** canonical-and-solver comparisons agree with explicit creation events.
- A second seed generates 64 sets of arbitrary concrete events. All subsets of five demands yield **2,048** checks that the unknown-overlap estimate bounds the actual union and lies between the largest marginal and the creation-scope cap.
- Holding the new canonical objective and placement preferences fixed, direct factors and the OR encoding select the same unique assignment and return identical raw objective bits.

| Controlled encoding fixture | Direct width → OR width | Maximum factor cells | Materialized cells |
| --- | --- | --- | --- |
| 16 co-active demands | 16 → 2 | 131,072 → 8 | 262,177 → 257 |
| 16 demands in two exclusive classes | 8 → 2 | 512 → 8 | 2,079 → 255 |

This is an encoding-only ablation of the **same new objective**. The old implementation already decomposed maximum-demand factors, so these numbers are not old-versus-new speedups. The oracle has small finite domains and fixed seeds; it does not establish arbitrary-program correctness or runtime counter accuracy.

### Additional PRIVATE_AGGREGATE regressions — existing failures reproduced

The same explicit test selection was run in both comparison worktrees:

```
mvn -B -Dtest-forkCount=1 -Dtest-threadCount=1 \
  -Dmaven.repo.local=/home/mchoi/so009-activation-classes-m2-20260907 \
  -Dsysds.test.glm.private.aggregate.selector=EXACT \
  '-Dtest=CampaignBG014ExactKMeansWanRepeatedUploadRedTest,CampaignBG014ExactLmWanHeavyBlockingLoutRedTest,FederatedPlanLocalCostPrivacyConstraintTest#localCostDpPreservesPrivacyAcrossBranchLoopAndFunctionBoundaries,GlmPrivateAggregatePlanningContractTest#optInWorkerOneBinomialGlmPlansWithSelectedAdditionalSelector' \
  test
```

Both baseline and feature run **8 tests: 6 pass, 2 fail**, with identical failing assertions:

1. `CampaignBG014ExactKMeansWanRepeatedUploadRedTest.loopAssignmentPayloadUsesExpectedCardinalityAcrossTransientDefinitions`: expected cardinality `800013.0`, actual `0.0`.
2. `CampaignBG014ExactLmWanHeavyBlockingLoutRedTest.exactUsesTheOccurrenceBoundLmLoopFrequencyOnWanHeavy`: expected the cached-X projection to choose `CP`, actual `FED`.

The new feature did not introduce these two failures in this comparison. Their deeper causes are not repaired by this audit: payload/cardinality estimation and the desired LM plan remain unverified contracts. No expected values were weakened and no legal FED alternatives were removed to make the tests pass. The Local branch/loop/function privacy test and the Exact PRIVATE_AGGREGATE GLM planning test pass in both versions; GLM contains 1,971 decisions. Its one-off compile timings are not a performance benchmark.

Logs and XML: `/home/mchoi/so009-activation-classes-validation-20260907/ablation/results/{baseline,current}-pa-regressions.log` and the corresponding `{baseline,current}/pa-surefire-reports/` directories.

### Paired cost semantics and selected plans

A cross-revision harness compiles the same fixtures, registers PRIVATE_AGGREGATE and binds compiler-owned placement authority at the final HOP boundary. An initial detached-analysis harness produced an artificial KMeans feasibility failure; the harness was corrected to use `CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary`. No production feasibility policy was changed. It records every workload before failing JUnit if any workload is unsuccessful.

The concrete invariant/carried transfer fixture has three consumer executions:

| Forced transfer contribution | Old model | Activation model |
| --- | ---: | ---: |
| Outer invariant value, consumed three times | 3.002471923828125 | 1.000823974609375 |
| Loop-carried updated value | 3.002471923828125 | 3.002471923828125 |

These are modeled contribution costs at matched producer/consumer alternatives, not measured time or bytes. The invariant contribution changes from three unit charges to one, while the updated value retains three charges.

Six PRIVATE_AGGREGATE workload fixtures produce finite plans in both versions. Their original decision keys and domain sizes match exactly. KMeans, PCA, LM, LogReg and ALS select identical alternatives. L2SVM changes two alternative selections, of which one changes execution/output state: the `tmp` ternary operation at `scripts/builtin/l2svm.dml:105` changes from `FED/FOUT/ROW` to `CP/LOUT`.

StepLM returns `EXACT_VE_NO_FEASIBLE_ASSIGNMENT` in both versions under this fixture's PRIVATE_AGGREGATE policy. It is recorded as an unsuccessful comparison, not skipped or counted as a pass. A source-registration change from the original PUBLIC certificate fixture does not establish that its old selected plan is legal under PRIVATE_AGGREGATE.

Cross-evaluation reconstructs each other-version assignment by normalized decision key and exact alternative signature, checks domain sizes and hard feasibility, and evaluates that fixed assignment under the current cost surface. All six successful workloads have feasible cross-assignments. The old and new optimizers each prefer their own selected assignment under their respective objective.

For L2SVM, the actual cost matrix is:

| Cost model | Old selected plan | New selected plan |
| --- | ---: | ---: |
| Old max-demand model | 2465.5525743301587 | 2541.8755113418774 |
| Activation model | 2465.5525743301587 | 2383.1848680313306 |

Four changed contributions explain this reversal. Running the ternary operation locally saves 100 modeled compute units. Two movement contributions of 305.291748046875 exchange roles and cancel in the total difference. The additional `Xd` download costs **176.32293701171875** under the old model but **17.632293701171875** under the activation model: `Xd` is produced in the outer loop and reused in the inner loop. Thus the local plan's net penalty of 76.32293701171875 becomes a saving of 82.367706298828125. This is a fixed-assignment cost-model difference, not a measured runtime improvement.

The other five successful workloads have identical selected alternatives and identical canonical objective bits under both models. Solver representation still changes:

| Workload | Auxiliary variables, old → new | Maximum factor cells, old → new |
| --- | --- | --- |
| KMeans | 219 → 77 | 289 → 289 |
| PCA | 102 → 36 | 24 → 16 |
| LM | 75 → 26 | 30 → 30 |
| L2SVM | 246 → 85 | 48 → 28 |
| LogReg | 335 → 116 | 24 → 21 |
| ALS | 86 → 31 | 25 → 25 |

The workload report also records factor sizes. Across these six fixtures, every `GENERIC_DIRECT` contribution has scope at most two and raw domain-product size at most 1,008. This category includes possible untagged ordinary conservative unions as well as other direct costs. No tagged latent `CONSERVATIVE_UNION` contribution is observed; that does **not** prove that all ordinary groups have resolved overlap, because ordinary contribution IDs remain generic. These workloads do not establish scalability for large unresolved unions. GLM passed its separate planning contract, but this report does not instrument its unresolved-factor distribution.

Raw paired reports, including failures, are under `/home/mchoi/so009-activation-classes-validation-20260907/ablation/results/cross-evaluation/{baseline,current}/`. Files include `workloads.tsv`, `selected-states.tsv`, `materialization-scope.tsv`, `cross-evaluation.tsv`, `contribution-deltas.tsv` and `factor-stats.tsv`. The companion `ablation/summarize_ablation.py` verifies key/domain matching, cross-assignment feasibility and objective ordering, then writes `results/paired-summary.json`. It completed successfully. Both reporting Maven runs intentionally remain **BUILD FAILURE** because StepLM is infeasible; the six successful workload rows are separate observations, not a successful seven-workload suite.

To reproduce in either comparison worktree, use its copied `ExactActivationAblationReportTest` with:

```
mvn -B -Dtest-forkCount=1 -Dtest-threadCount=1 \
  -Dmaven.repo.local=/home/mchoi/so009-activation-classes-m2-20260907 \
  -Dactivation.ablation.output=/path/to/new-report-directory \
  -Dactivation.ablation.reference=/path/to/other-revision-report-directory \
  -Dtest=ExactActivationAblationReportTest test
```

The optional reference directory must contain the preserved `selected-states.tsv`; the output directory should be separate. The source harness is retained in the local validation directory's `ablation/` folder. Main source and original repository cleanliness were checked independently; follow-up source hashes, commits and test evidence are recorded in `followup-verification-receipt.json` alongside the original historical receipt.

### Remaining limits and regression risks

- Unknown overlap retains a conservative scope-capped union factor, which may have high arity. A finite-objective exactness claim applies when the solver completes within its resource limits. This audit must not imply that all unresolved unions received bounded OR factorization.
- Ambiguous or cyclic source provenance can overcharge; no new cross-group sharing proof is introduced.
- Static branch and loop frequencies remain estimates. No independence assumption is made, but conservative upper bounds can still alter plan quality.
- There are no measured runtime creation counts, transfer bytes, live-worker checks or Docker workload timings in these results. Any runtime performance comparison must use `run_LAN_docker.sh` under the repository instructions.
- The additional planning suite has two existing failures, and the PRIVATE_AGGREGATE StepLM fixture remains infeasible. Therefore the repository is not reported as fully green or comprehensively robust.

Decision rationale: refine and verify the encoded cost objective while preserving compiler authority, privacy and runtime feasibility rules. Broader pre-existing planner defects are documented rather than concealed by candidate restrictions or runtime fallback.

## Merge activation classes with the latest explicit-binding source — verified

- **Status**: source conflicts resolved and fresh merged-source validation passed. Final source delivery and both-parent ancestry are recorded in `MERGE_RECEIPT.json` under `/home/mchoi/activation-latest-integration-validation-20260908`.
- **Request / target**: merge activation-aware reusable materialization accounting and its validation report into `/home/mchoi/g014-glm-followup-systemds-20260906`, branch `cofee-glm-followup-20260906`, starting at `4fa24c5d6370337ecacd8423b43abe1a46e0002e`. That commit already integrates explicit input binding. The activation parent is `codex/activation-class-materializations-20260907` at `04fcd88a3e`.
- **Observed conflict**: both implementations change compiled reusable-copy grouping, and both add September 7/8 session documents. Taking either whole side would discard either binding-aware sharing or activation semantics. The target also has an unrelated uncommitted planning/ablation addition to this session document.
- **Isolation / preservation**: prepare the merge in `/home/mchoi/g014-activation-integrated-systemds-20260908`, branch `integration/activation-latest-source-20260908`. Preserve both document histories. The original target status, dirty patch, full session document, and source identity are backed up under `/home/mchoi/activation-latest-integration-validation-20260908`; retain that existing uncommitted work when advancing the target.
- **Resolution**: retain explicit-binding CP/native-local/logical-function sharing, source-state masks, per-demand byte bounds, forwarding charges, relocation identities, and compiler-owned provenance. Resolve logical function activation through the physical caller occurrence. Group costs at the producer creation scope; use Boolean OR activation classes. For unequal per-creation prices, decompose the expected active maximum into positive price increments times the activation union above each price threshold. Equal prices use the compact existing activation encoding. Unknown overlap keeps the conservative scope-capped bound at each threshold.
- **Modified integration files**: `ExactPhysicalCostModel.java`, `ExactSharedLocalCopyCostTest.java`, new `ExactActivationBindingCostTest.java`, and the session documents, alongside the activation parent changes and report.
- **Validation plan**: fresh PRIVATE_AGGREGATE compiler fixtures and synthetic solver tests for activation, creation scopes, heterogeneous binding prices, canonical/OR objective equality, explicit binding and joint binding receipts; compile and package the merged tree on so009. Local disk space is insufficient for an additional isolated Maven build. Do not rerun PUBLIC fixtures or raise solver resource limits.
- **Initial validation evidence**: all 8,283 tracked source/script/configuration/POM files matched the remote candidate. Fresh main/test compilation succeeded. The first ten-class run contained 49 tests: 48 passed, one failed, zero errors/skips. `ExactSharedLocalCopyCostTest.mixedConsumersShareOneMaximumDemand` reported `Shared materialization must have priced activation factors`; its fixture was repaired as described below. The unsuccessful run remains in `candidate-v1/{clean-package.log,result.json,surefire-reports/}` under the validation evidence root; it is not a successful package build.
- **Fixture repair**: the original full-range left-index assignments could use the old matrix only as coordinator metadata, and selecting any native-local authority could select the other operand. Replace them with data-bearing `A+B` / `A-B` consumers, exclude metadata-only edges, and select native-local supplies by exact compiled input position. Retain one CP and two actually charged FED-local demands, zero inactive cost, one coactive per-creation maximum, and zero download from an available local-held source. The logical-function fixture now consumes a formal inside a callee loop and verifies the actual caller profile remains weight one with no loop context. The synthetic priced-factor test separately validates unequal unit costs, opposite branch activations, source-dependent price ordering, conservative unknown overlap, and canonical/OR raw-bit equality. Do not require unequal unit prices from a real fixture whose unit prices are equal.
- **Final verification**: ten selected classes, **49 tests, zero failures/errors/skips**, and `package` **BUILD SUCCESS** at `2026-09-08T01:16:03+02:00`. Main source did not change after the successful clean compilation; only the shared-copy test fixture changed before the complete selected suite was rerun. The 8,283-file final source manifest matches before and after this run. Evidence: `merged-final/{package.log,result.json,surefire-reports/}` and `final-build-source-manifest.json`. Packaged JAR SHA-256: `7df626ff7d0508c9092cc4324df91ea5faee707b07ca3b8a868287e5ca158674`. Whitespace/conflict checks and independent static integration review also pass. This is selected compiler/solver coverage, not the full repository test suite or a new runtime ablation.
- **Reproduction**: run `bash /home/mchoi/activation-latest-integration-validation-20260908/run-final-merged-checks.sh` on so009. It uses the isolated Maven cache, one test fork/thread, and the exact ten-class selection recorded in `merged-final/result.json`. The initial clean-compilation command is retained in `run-merged-checks.sh`.
- **Remaining issues / risks**: previous baseline cardinality/LM assertions and PRIVATE_AGGREGATE StepLM infeasibility remain historical failures, not evidence against or for this new merge. Price levels may increase factor count; unresolved unions may retain high arity. Detect integration regressions with real mixed compiled/native-local and compiled/logical sharing fixtures, exact selected-binding validation, and source-to-build manifests. No runtime counters or Docker workload performance are claimed.
- **Decision basis**: change the encoded objective and its factorization while retaining runtime capability, privacy, candidate, and value/use identity rules. Existing experiment stage binaries and provenance are not deployment targets for this source merge.
