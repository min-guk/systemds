# LogReg under-10 solver design

## Evidence and target

The latest allowed AFTER run reports planning 26.819 s and optimizer time 23.714 s with an unchanged
2,933,891-node exact search. Of those nodes, 2,466,239 are lower-bound prunes. The support-domain repair
worked as intended—38,226,419 support-cache hits versus 171,750 misses—but did not reduce the search tree.
The next change must therefore strengthen exact pruning or collapse exact residual subproblems. No workload
was run for this analysis.

The acceptance target is total Memo planning below 10 seconds; optimizer time is reported separately. With baseline non-optimizer phases totaling about 3.105 seconds, optimizer time must fall below about 6.895 seconds unless those phases also improve. An allocation-only change may therefore be insufficient. The
recommendations below preserve complete enumeration semantics, certified lower bounds, finite budgets,
exact `BigDecimal` costs, and lexicographic tie selection.

## Priority 1: correlate native and boundary-tree costs by core-owner rule

### Current lost correlation

`tryLeafRegionSolve` exactly eliminates every degree-one forest around the cyclic core. Each eliminated
tree produces a `BoundaryTree` message indexed by the adjacent core owner's rule
(`MemoOptimizer.java:643-793`). The message is exact: `tree.costs[r]` is the minimum cost of that entire
eliminated tree conditioned on boundary rule `r`; UNKNOWN messages abort this solver path
(`MemoOptimizer.java:747-750`).

The reduced lower bound currently loses that conditioning. For each core owner it independently adds

```text
min_r nativeCost(owner,r)
```

and then, for every attached boundary tree, independently adds

```text
min_r treeCost(tree,r).
```

These calculations are at `MemoOptimizer.java:930-952`. Their minima may select different rules for the
same owner even though a complete assignment must use one rule in all terms. `completeCore` later charges
the native cost and all boundary messages at the single selected owner rule
(`MemoOptimizer.java:968-990`). The lower bound is sound but can be much weaker than the exact unary cost
already available after tree elimination.

### Exact stronger bound

For each live core owner and each rule, precompute once after boundary construction:

```text
ownerMessageCost[owner][rule]
  = nativeCost(owner,rule)
  + sum(tree.costs[rule] for every BoundaryTree whose boundary == owner)
```

Then use

```text
min_{rule in current domain(owner)} ownerMessageCost[owner][rule]
```

as that owner's contribution. Remove the separately summed boundary minima from `coreLowerBound`; all
remaining multi-owner term bounds stay unchanged.

For any complete assignment `x`, the eliminated subtrees are disjoint and conditionally independent given
their boundary owner. Their exact contribution is the sum of the messages at `x[owner]`. Therefore

```text
min_r (native_r + sum_j boundary_jr)
  >= min_r native_r + sum_j min_r boundary_jr
```

and the left side is still no greater than the contribution of every feasible completion. The new bound is
admissible and never weaker. No source tuple, physical alternative, or approximation is introduced.

The same immutable `ownerMessageCost[owner][rule]` should order branch values before ordinal tie-breaking
at `MemoOptimizer.java:917-926`. This changes traversal order only. `completeCore` should retain its current
cost assembly and full `lexicographic(candidate,bestAssignment)` comparison so objective representation and
tie selection remain byte-for-byte compatible.

### Minimal implementation shape

- Build a per-owner list of boundary indices, which already exists as `boundaryIncident`.
- Build `BigDecimal[][] ownerMessageCosts` only for core owners. A null boundary entry is already removed
  before search (`MemoOptimizer.java:877-884`); retain that invariant explicitly.
- Replace the core owner bound calculation with the minimum allowed precomputed message cost.
- Delete or stop adding the separate `boundaryBounds` contribution. On owner mutation, dirtying the core
  owner is sufficient; no separate boundary-bound invalidation is needed.
- Sort allowed values by precomputed message cost and then rule ordinal. Do not change owner selection.

This adds `O(sum core domain sizes + boundary messages)` preparation and leaves each dirty owner-bound
refresh `O(current owner domain)`, the same asymptotic work as the current native minimum plus boundary
scans.

### Expected sufficiency and limits

This is the first implementation candidate because it strengthens the bound at all 2.9 million search
nodes and may find the final incumbent earlier, while remaining small and locally provable. The run has a
large prune population (84.1% of nodes), so a stronger bound can materially shrink the tree. The available
trace does not report boundary count or per-rule message disagreement, so a twofold node reduction cannot
be promised before a measured run. If most boundary messages prefer the same rule as native cost, the gain
will be small.

### Required tests

1. A cyclic core owner with two eliminated leaves whose independent minima prefer conflicting owner rules.
   Assert the stronger bound prunes nodes while status, exact cost, and selected assignment equal exhaustive
   enumeration.
2. Equal combined costs with different rules and different reconstructed tree assignments. Assert the full
   lexicographically smallest assignment is unchanged.
3. Multiple boundary trees on one owner, null/infeasible boundary rules, negative certified-safe costs, and
   exact fractional `BigDecimal` inputs.
4. Cache capacities 0/1/default and finite `searchNodes` budgets. A stronger exact bound may finish within a
   budget that previously exhausted, but must never return OPTIMAL without a complete proof.
5. Existing randomized `MemoExhaustiveParityTest`, resource-budget tests, and reduced-search tests.

## Priority 2: exact conditional-forest completion after fixing a cycle cutset

If the correlated unary bound does not cut optimizer time below ten seconds, the next structural step is
to stop enumerating a residual core once its unfixed interaction graph becomes a forest.

At each reduced-search node, project every check and cost scope over owners whose current domains are not
singleton. Fixed owner values remain arguments to the original predicate/evaluator. When every projected
scope has at most two unfixed owners and the projected graph is acyclic, run exact tree dynamic programming
over the remaining domains instead of descending to singleton leaves. Include the precomputed boundary
messages as unary owner costs. Reconstruct the tree optimum, then apply the existing full-assignment
lexicographic comparison.

This is exact cycle-cutset conditioning: the recursive search enumerates only assignments needed to break
cycles; the forest tail is solved in polynomial time. It can reduce nodes exponentially when the core has a
small feedback vertex set. It generalizes the one-time initial tree elimination, which does not reconsider
the graph after core owners become singleton (`MemoOptimizer.java:643-669`, `889-929`).

Source inspection also confirms that disconnected residual core components are currently searched as one
global Cartesian product. `enumerateCore` chooses one owner from the full `removed == false` set and reaches
`completeCore` only after every such owner is singleton (`MemoOptimizer.java:900-939`); it has no connected-
component split. The initial leaf pass can separate and finish isolated acyclic trees, but two disconnected
cyclic components both remain in the core (`MemoOptimizer.java:646-669`). Component-wise exact solving and
cost/assignment combination is therefore a concrete part of Priority 2, not an already implemented path.

This option has higher implementation risk:

- projected callbacks must expand fixed ordinals in original scope order;
- a complete factor result of `UNKNOWN` must abort the forest shortcut and resume ordinary exact search;
- finite node budgets must charge DP cells consistently and stop before publishing an incomplete optimum;
- multiple connected forest components must combine exact costs and global lexicographic reconstruction;
- removed boundary-tree assignments must be reconstructed exactly once.

Required tests need exhaustive random cyclic graphs with small cutsets, high-arity factors reduced to
0/1/2 live variables, UNKNOWN callbacks, disconnected components, equal-cost global ties, and budgets that
expire during DP. This should follow measured evidence that Priority 1 is insufficient rather than being
combined into the first change.

## Rejected or deferred ideas

- **Whole-state memoization:** deterministic branching makes identical full domain states less common than
  identical local factor states; the current evidence only proves local support/bound reuse. Large global
  snapshots have no demonstrated twofold benefit.
- **Arbitrary branch heuristics or approximate pruning:** value/owner scores without admissible proof can
  change work unpredictably and cannot certify the target. Priority 1's value score is an exact component
  of every completion.
- **Independent stronger minima for overlapping core terms:** separately minimizing overlapping factors
  repeats the same lost-correlation problem. Combining them requires a proven bucket/mini-bucket bound or
  exact variable elimination and is broader than the boundary-message repair.
- **Equivalent-variable quotienting:** predicates and cost evaluators are opaque callbacks. Structural
  similarity alone does not prove semantic symmetry or preserve lexicographic reconstruction.

## Recommendation

Implement Priority 1 alone, lock it against exhaustive parity, and record node count plus optimizer time.
It is the smallest exact change capable of shrinking both the incumbent-discovery prefix and the bound-pruned
tree. If it does not reduce nodes enough for the ten-second target, implement Priority 2 as the structural
completion step; further cache and allocation work cannot by itself supply the required greater-than-twofold
optimizer reduction.

## Implemented Priority 1 result

`ReducedSearch` now constructs the exact immutable `ownerMessageCosts` table described above. It folds each
live core owner's native cost together with every eliminated boundary message at the same rule ordinal.
`coreLowerBound` takes the minimum of that correlated table over the owner's current domain and no longer
adds independently minimized boundary messages. Null boundary entries are still removed through the normal
reversible-domain path before enumeration, and UNKNOWN boundary trees still prevent entry into reduced
search. `completeCore` retains its original cost assembly and full-assignment lexicographic comparison.

Value traversal now uses a per-owner `int[]` sorted once by the immutable combined cost and then rule
ordinal; each node only filters that order by current-domain membership. Owner selection, propagation,
node charging, finite-budget status, cost arithmetic, and final tie reconstruction are unchanged.

The focused conflict fixture records 23 nodes on the frozen pre-change engine and 15 nodes after the change.
This proves that the correlated bound removes work in the constructed lost-correlation case; it is not a
workload performance claim. The latest isolated validation passed 34 tests with assertions enabled:
`MemoOptimizerBoundaryBoundTest`, `MemoOptimizerDomainCacheTest`, `MemoOptimizerTest`, and
`MemoExhaustiveParityTest`. Evidence is in `boundary-before-junit-02.log`,
`boundary-node-red-junit.log`, and `boundary-after-junit-05.log`. No LogReg or GLM workload was run during
this implementation.
