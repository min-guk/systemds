# Independent review: solver P1 and MemoShapes precomputation

**Verdict: APPROVED — no must-fix finding.**

Reviewed current frozen source against
`experiments/logreg-bottleneck-20261014/after-engine/main-sources`. The review was read-only; no
production or test source was edited and no workload was run.

## Solver P1

The new reduced-search owner contribution is an admissible strengthening. For one retained owner
rule it precomputes the exact native cost plus every eliminated-tree boundary message conditioned on
that same rule (`MemoOptimizer.java:859-875`). Taking the minimum of this correlated sum over the
owner's current domain (`:946-965`) replaces the weaker independent minima. Eliminated trees are
conditionally independent once their shared retained owner rule is fixed, so summing their exact
messages is valid. Core cost terms remain separate in `coreTermBounds`; the exact leaf calculation
adds native costs, retained-only terms, and each boundary message once (`:980-1004`). The bound does
not duplicate any component used by `completeCore`.

Negative and binary64-derived dyadic costs remain exact `BigDecimal` values. The change only uses
`BigDecimal.add` and `compareTo`; it does not round or assume non-negativity. The new boundary test's
`-0.25` messages and `0.5` optimum exercise the case where independent native/message minima are
incompatible.

Missing boundary messages preserve the old legality behavior. A `null` message makes the correlated
owner/rule cost unavailable (`:868-874`), and `run` removes that exact boundary choice through the
ordinary reversible domain trail before search (`:895-906`). A domain emptied by those removals
returns before bound evaluation (`:913-916`). A boundary containing any `UNKNOWN` still aborts the
leaf reduction before `ReducedSearch` is accepted, and a complete-core `UNKNOWN` still marks the
result unresolved (`:980-986`). No partial boundary proof is published.

The node budget check remains before each reduced-search node (`:909-912`) and exhaustion stops
enumeration without publishing an optimal proof. Static value ordering is heuristic only: it sorts
by correlated message cost and rule id (`:876-883`), filters against the live domain (`:936-944`),
and final incumbent replacement still compares exact cost and the fully reconstructed assignment
lexicographically (`:996-1004`). Equal-bound pruning retains the existing conservative
`reducedLexicographicFloor` rule (`:918-920`, `:971-978`).

The scoped-domain cache lookup remains exact and state-safe. The transient probe borrows live
`BitSet` domains only for synchronous `Map.get`, resets all references in `finally`
(`:1218-1227`), and stored keys clone every scoped domain (`:1266-1277`). Hash collisions are resolved
by exact domain equality (`:1235-1247`). P1 does not modify this contract.

Validation evidence supplied with the frozen source reports 34 assertion-enabled tests passing:
four boundary-bound tests, three domain-cache tests, 22 optimizer tests, and five exhaustive tests.
Those tests cover correlated negative costs, equal-cost lexicographic ties, unknown boundary abort,
finite node budgets, restored domain states, finite support budgets, and `BitSet` hash collisions.

## MemoShapes

The occurrence dependency precomputation reproduces the previous per-call union of each immediate
input binding's phase and guard dependency sets. Binding dependency sets are immutable, occurrence
ids are allocated from the occurrence list size, and the new unions are stored in unmodifiable sets;
the index and mutation contracts are sound.

The `normalizeLoopPhases` fast return is behavior-preserving. `EvaluationContext` copies its maps and
call frame on construction. When every iteration-phase loop key is already present, the previous
`putIfAbsent` path produced an equal context without changing any loop value. Returning the existing
immutable context changes allocation and identity only; none of the call sites use reference
identity.

## Residual risk

The performance target still requires the single authorized Docker candidate measurement. Unit and
exhaustive tests establish solver behavior for their fixtures, while workload-level objective,
output, action, audit, Oracle, and native-universe parity remain measurement gates. Search-node
equality is intentionally not a gate because the stronger bound is expected to reduce nodes.

Reviewed source hashes:

- Frozen solver baseline: `e3a7d54fede43e377d35eb6c05477bcef0c08a3351707b932d5e007b0ac5974c`
- Reviewed `MemoOptimizer.java`: `361ada0ddb92b775062c5d54209b5ec847557397dfda7ade8e6fbf5316a03689`
- Reviewed `MemoShapes.java`: `02da917b6e8405ca10ad6ef5d6bd22db279e60fa352ea8b52bc00a27cacf309e`
