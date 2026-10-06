# Bounded regional solver implementation report

## Result

The Regional solver now admits the full immutable dense root cover without duplicating its table-cell charge, preserves the certified lower bound when persistent message merging exhausts its budget, and spends only the remaining bounded time/memory on exact conditional neighborhoods. Neighborhoods are ranked by incumbent-to-message-minimum regret and include a two-ring original-decision interaction closure plus the smaller owner block as a fallback when the expanded block fails resource preflight.

The conditional exact compiler retains the configured fast-order path. If that selected order exceeds the existing per-step elimination limit, it reselects from the same exact four-order portfolio while requiring both the existing materialization limits and the existing maximum-elimination-assignments limit. It does not raise a cap or remove candidates/factors.

## Source changes

- `ExactCategoricalSolver.java`: borrowed dense leaf accounting, boundary-message incumbent lookup, and exact portfolio selection constrained by the existing per-step work and materialization limits.
- `IncrementalRegionalOptimizer.java`: complete-cover release after lower-bound certification, regret-ranked conditional refinement, two-ring auxiliary/original interaction closure, expanded-block/base-block fallback, strict incumbent lifting/validation, and conditional checkpoints.
- `SharedRegionalPreparation.java`: separate root and additional conditional budgets, preflighted conditioned tables, preservation of configured fast-order behavior, bounded portfolio retry, and explicit conditional rejection reason.
- `RegionalSearchProblem.java`, `LocalPhysicalOptimizer.java`, `LocalCategoricalOptimizer.java`: recognized resource classification, conditional search integration, and trace/checkpoint reporting.

## Verification

Focused isolated compilation and JUnit run:

- 84/84 tests passed.
- Classes: `/home/mchoi/fedplanner-remaining-20261006/glm-search/bounded-order5-classes`
- Test log: `/home/mchoi/fedplanner-remaining-20261006/glm-search/bounded-order5-related.log`
- `git diff --check`: passed.

The new bounded-order regression shows an ordinary exact order with maximum step work 17,280 and an alternate exact order with maximum step work 8,640 under a 16,384 limit; both return the same objective and canonical assignment.

Actual Docker evidence:

- GLM W1 final objective: **20,956.641086880732 ms**, below the required known feasible 21,977.116965 ms.
- GLM performed 5 conditional attempts and 3 conditional improvements; final stop reason remained `RESOURCE` at the existing time boundary.
- L2SVM W3 final objective: **4,259.973128249209 ms**, improving the previous 6,279/7,289 outcomes.
- Runtime trace: `/home/mchoi/fedplanner-search-boundary-fix-20261006/all14/results/B/glm_w1.log`
- L2 trace: `/home/mchoi/fedplanner-search-boundary-fix-20261006/all14/results/B/l2svm_w3.log`

For the decisive GLM block `[758,759,760,762]`, the original memory-first portfolio choice was `MIN_SEPARATOR_CELLS` with maximum step work 3,799,552. The bounded retry selected `MIN_ELIMINATION_ASSIGNMENTS` with maximum step work 593,680, maximum factor cells 29,684, and materialized intermediate cells 788,256. The solve limit remained 1,000,000 maximum step assignments and approximately 8.09M materialized cells.

## Limits and guarantees

- The returned incumbent is evaluated against the complete original/canonical problem and accepted only on a strict non-worsening comparison. Optional conditional resource failures retain the prior feasible incumbent.
- The lower bound remains the certified bound from the complete Regional factor cover. Conditional refinement changes only the feasible upper bound.
- A final `RESOURCE` stop with a nonzero gap is not a global-optimality certificate. The GLM result is a verified feasible improvement within the unchanged time and memory budgets.
- Borrowed dense root tables are excluded only from additional Regional message-storage accounting because those arrays already exist and are aliased. Lazy tables and newly conditioned/intermediate tables remain charged. This is an additional solver-storage budget, not a claim about total JVM memory.
- Configured fast-order telemetry records the initial configured attempt; a bounded retry can select the actual final order when the initial order exceeds the per-step cap. The portfolio trace records both selections.
