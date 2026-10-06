# Cost/resource planner integration verification — 2026-10-06

This publication includes the cost/transfer and resource-policy changes, preserves the concurrent joint-boundary and structural-grounding changes from origin/main, and records outstanding integration failures. **It is not an all-green validation report.**

## Code and evidence identity

- Full integration campaign: `0e44222031faa38aedfe49e9346f0d11b5a37600`, including origin/main `eb64f9c939`.
- Last production/test fixes: `547f4799cd801ef1830923390b8e6d5d1971429a`.
- The last production difference is record-equality deduplication after native continuity proofs are projected to transient compatibility certificates. Different witnesses, exactness classes and dependencies remain distinct. The test-only difference projects auxiliary solver variables out before canonical decision validation.
- Frozen campaign: `/home/mchoi/fedplanner-main-integrated-20261006/engine`. Publication engine and runtime receipts are in the sibling `publication-engine` and `publication-runtime` directories.
- Compact receipts, exact probes, commands and build identities: [integrated evidence](experiments/global-exact-main-20261006/integrated/). Raw logs remain in the artifact directory, not in Git.
- The earlier [pre-integration evidence](experiments/global-exact-main-20261006/pre-integration/) describes a different code revision and does not certify the merged implementation.

## Verified behavior and limitations

Production Global requires a whole-program exact result or explicit failure; there is no partial Global success fallback. Fixed production time, assignment-count and retained-factor-slot budgets were removed. JVM/representation impossibility still fails explicitly. Local's quality-gap criterion remains; it is not a wall-clock or memory budget. Elimination ordering can improve resource use without deleting legal states.

The upstream joint-input environment limit of 16,384 is a separate analysis limitation and remains in the merged implementation. This report does not claim that every analysis in the repository is unbounded.

The integration also preserves exact materialized output authority across branch writes, uses one branch normalizer, caches immutable joint-environment keys, and keeps the Local incumbent when a subtree has exactly equal cost. The Local tie regression was reproduced independently of prefix pruning and its original Docker LogReg W3 case passed with the targeted fix before the subsequent upstream merge.

## Java and runtime verification

- Broad Maven run at `0e44222031`: 129 classes, 1,138 tests: **1,125 passed, 1 failure, 7 errors, 5 skipped**. All expected class reports were present. The report collector initially confused `SinglePartitionFactsTest` with `FunctionReturnSinglePartitionFactsTest`; the archived report uses exact class names.
- Final focused Maven run at `547f4799cd`: 8 classes, **134 passed, 1 existing skip, no failure/error**; `test jar:jar` succeeded. This fixes the native certificate and auxiliary-assignment failures from the broad run. It does not convert the other six errors into passes.
- Python harness tests: **18/18 passed**. Shell syntax and `git diff --check` passed.
- Final-code worker runtime: **6/6 passed**, numeric outputs correct, runtime fallback/repair counts zero. The first attempt failed before any workload because a host dependency symlink was outside the container mount; copying dependencies into the frozen engine corrected the harness packaging. Both attempts remain in raw artifacts.
- Joint-boundary Docker at the campaign revision: **12/12 passed**, including loop/function/branch upload and protected-input negative cases. No FED runtime conversion violations; model proof and class preflight passed. All 3,750 production class hashes match the campaign engine. This is explicitly the campaign revision, before the final one-line certificate deduplication.

## Outstanding failures

Campaign checkpoint at publication: all 28 cases per planner were started. Global has 18 passes and 9 failures; Local has 19 passes and 8 failures. Both `logreg_w1` processes are still running and have no terminal receipt. They are not counted as passes, resource failures, or completed validations. Every recorded failure has zero committed programs. The per-case [Global](experiments/global-exact-main-20261006/integrated/global14.json) and [Local](experiments/global-exact-main-20261006/integrated/local14.json) receipts preserve this distinction.

These are unresolved, not successful resource stops unless specifically identified as such:

1. **P1_FULL function-input replay:** no exact caller source state legal at a formal read, before optimization. Both planners fail with zero committed programs. This passed on the earlier pre-integration revision; upstream-versus-merge attribution is not established.
2. **GLM derived FOUT authority:** an upload of `abs(Y)` names a callee formal TRead as anchor owner, but the graph has no exact matching authority for that owner. The materialized output proof added for TW owners does not establish this cross-function formal-read proof. This is not the earlier GLM heap limit.
3. **Large cost-factor representation:** ALS and STEP-LM tests encounter `EXACT_VE_FACTOR_CELL_OVERFLOW`. A whole exact solver cannot run when the physical cost surface cannot be represented. No domain truncation or alternative planner was introduced to hide it.
4. **STEP-LM closure and ALS greedy policy:** the formal-binding integration test fails to converge; the FedAll policy test reports an empty owned-row domain. A greedy-policy conflict is not proof of global infeasibility.
5. **LogReg broad regression:** the full CLI regression exhausted its 3 GiB test JVM heap (`Java heap space`). Passing the earlier isolated Local tie case does not certify this later merged path.

Upstream-only probes at `49509ab7f8` also failed for the two STEP-LM methods, two ALS methods and the old raw KMeans relocation test. Several failed at earlier stages than the merged build. Those observations establish that those tests were not green on that baseline; they do not establish that every current failure has the same cause. The KMeans test now uses factorized hard/cost factors with the same forced relocation and canonical hard validation, and passes in the final focused run.

No arbitrary planner budget, candidate deletion, implicit runtime repair, or successful partial Global result was added as a workaround for these failures.

The 18 common successful campaign cases have equal graph-node, decision-node and coarse-alternative counts. This count equality is not proof of complete plan-space identity. Raw Global/Local certificates have different cost-surface fingerprints; 14 pairs show a positive Global-minus-Local difference of at most approximately `7.43e-7 ms`. The numerical comparison is archived, and its cause is not established here. These runs therefore do **not** serve as a same-cost-surface proof that every Global result is at most the Local result. Exactness is separately covered by solver/oracle tests; the remaining comparison discrepancy requires investigation.
