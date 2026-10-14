# Independent review: optimizer scoped-domain cache

## Verdict

**Approved for the single AFTER measurement.** I found no correctness defect in the frozen production
diff. The change preserves exact domain identity, finite-budget `UNKNOWN`, lower-bound safety,
backtracking restoration, rule ordinal order, and lexicographic tie behavior. The implementation stays
inside `MemoOptimizer`; `FederatedPlanMemo` only exposes the new support-cache hit counter.

## Reviewed change

The baseline was the frozen
`experiments/optimizer-reuse-20261014/after-engine/main-sources/.../MemoOptimizer.java`.

The old support key used owner revisions. Both removal and restoration advance revisions, so an exactly
restored domain could not reuse support. The new key is:

```text
(check id, fixed owner, fixed value, ordered scoped owner BitSet contents)
```

The conditional-bound cache uses the same exact content identity in its own map. Lookup probes borrow
the live domains only during synchronous `Map.get`; stored keys clone every scoped `BitSet`. Hashes select
buckets only. Equality compares every complete `BitSet`, so equal cardinality, equal hash, or a Java
`BitSet.hashCode` collision cannot merge different candidate sets.

The tuple truth/cost caches use the same non-owning probe pattern. Their stored `TupleKey` still clones
the tuple. The optimizer is one-invocation/synchronous, and no predicate or evaluator is called during a
probe lookup, so mutable scratch state cannot escape or be observed recursively.

## Correctness invariants

### Support and finite budgets

`support()` checks the full scoped-domain cache before allocating choices or running DFS. A cache miss
executes the unchanged DFS with the unchanged support limit and ordinal traversal. The support limit is
immutable for one optimizer, so a completed `UNKNOWN` caused by budget exhaustion is deterministic for
the same exact domain and fixed owner/value. The solver still removes a rule only for `Truth.NO`.
Revision stamps remain in the propagation worklist and are not replaced by cache identity.

### Conditional bounds

`computeConditionalCostBound()` probes the exact domain before combination counting. Any stored value was
computed earlier under the same immutable term, same solver limit, and identical domains. On a miss, the
existing combination limit remains: a wide term returns only its certified minimum, and that relaxed value
is not inserted as an exact enumerated minimum. `minimumTerm` and `BigDecimal` evaluation order are
unchanged.

### Cardinality and backtracking

`domainCardinalities` is initialized from immutable domain sizes. Repository search found exactly two
post-construction domain mutation paths:

- `remove`: clear one present bit, decrement cardinality;
- `undo`: restore one absent bit, increment cardinality.

Both paths now assert that the maintained count equals `BitSet.cardinality()`. Owner selection, support DFS,
conditional-bound width, reduced search assignment, connectivity, and selected-result extraction read the
maintained value. Bit iteration and branch ordinal order still use the `BitSet`, so tie ordering is unchanged.
Normalization changes checks/terms and clears all four caches before further search.

### Bounded cache behavior

Capacity zero still inserts then immediately evicts, preserving the uncached reference behavior. Capacity
one is covered explicitly. Stored domain snapshots never reference a live solver `BitSet`; backtracking can
therefore restore and hit an older exact state without mutating the key already in the map.

## Regression assessment

The new `MemoOptimizerDomainCacheTest` covers:

1. cached, single-entry, and uncached status/cost/selection parity after restored cyclic domains;
2. actual support and conditional-bound cache hits after restoration;
3. unchanged wide-bound relaxation count on exact hits;
4. preservation of finite-budget `UNKNOWN` behavior;
5. distinct domain contents with colliding `BitSet` hashes.

The existing optimizer suite additionally covers finite search-node limits, cache capacities, finite support
limits, exact objective parity, reduced-search bounds, and deterministic lexicographic selection.

## Verification evidence

- Frozen behavior lock: `domain-cache-before-junit.log` — **30 tests passed**.
- Implementer final assertion-enabled run: `domain-cache-after-junit-06.log` — **30 tests passed**.
- Assertion patch targeted compilation: `final-assertion-compile.log` — passed with only incubating-module
  warnings.
- Independent run with assertions enabled:
  `MemoOptimizerDomainCacheTest` + `MemoOptimizerTest` — **25 tests passed**;
  log `independent-review-junit.log`.
- Maven `test-compile` without `clean`: **BUILD SUCCESS**, 42.981 s; log `final-compile.log`.
- Final assertions-enabled suite: **47 classes, 426 tests passed in 47.326 s**; class inventory
  `final-unit-classes.txt`, command `final-unit-command.txt`, and result `final-unit.log`.
- `git diff --check` for optimizer, trace, and new test: passed.

Earlier numbered logs contain intermediate RED/failing iterations and are superseded by the `-06` final
run and this independent run.

## Remaining limits

- The exact domain probe still computes `BitSet.hashCode` on each lookup. This is allocation-free but not
  constant time in domain word count. The AFTER JFR must determine whether it is sufficient.
- Wide relaxed bounds are deliberately not cached as exact minima, so repeated wide states can still repeat
  the certified-minimum decision. This preserves the existing proof boundary.
- The approved performance claim remains limited to one before/after JFR pair under the recorded LogReg
  conditions. It does not establish a general speedup distribution.
