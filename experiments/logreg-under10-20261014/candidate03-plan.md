# Candidate03: exact conditional forest completion

Candidate02's sole Docker run planned in 15.926899887 s (optimizer13.318014527 s), with2,933,891 nodes and all correctness gates passing. Its hash/tree-bin/BitSet leaf work was reduced, but a nearly unchanged number of support/bound/domain operations remains. Fresh JFR:1,068 optimizer samples, cachedDomains299, coreLowerBound314, propagate376, movement97. This candidate must reduce remaining combinatorial search, not merely make another cache.

## Implementation boundary

Inside ReducedSearch.enumerateCore, after propagation, assignment refresh and existing lower-bound prune, inspect retained factors over currently non-singleton owners. Project all fixed ordinals from their original scopes. A remaining factor of arity>2 or an actual cycle makes the shortcut inapplicable. Parallel factors share a graph edge. If the residual graph is a forest, solve that conditional forest exactly with the existing TreeCell representation and evaluator caches. The ordinary exact search remains the fallback.

Charge each original factor exactly once: zero-residual factors and fixed owner messages become the conditional constant; unary factors belong to the one remaining owner; binary factors to its edge. OwnerMessageCosts already include native cost and previously eliminated boundary trees. Add original base once. Use existing evaluate/evaluateCost with original scope order and fixed values; do not weaken predicates or cost primitives.

For each forest cell, enforce the global node budget before charging the cell. UNKNOWN must discard the incomplete tail proof and resume original enumeration; never convert UNKNOWN to infeasible. Fully proven NO may close the branch. Do not publish partial solutions as optimal after exhaustion.

Global lexicographic ties must include previously eliminated leaf owners. Start with an outer clone of existing cells and build new core TreeCells whose reconstruction children include dynamic forest children plus each attached original BoundaryTree root/rule. The old boundary message cost is already in ownerMessageCosts and must not be added twice. This lets existing compareTreeChoice examine interleaved original owner IDs correctly. Reconstruct fixed-core boundary trees as well, then compare the complete assignment with the original incumbent logic. Preserve exact BigDecimal result representation.

Use a cheap eligibility pass before allocating buckets/DP storage. No second general solver, no source Cartesian precomputation, no candidate deletion or approximate minimum. Native/Oracle/privacy/worker/movement semantics stay unchanged.

## Behavior lock and evidence

Add exhaustive fixtures for cycle-cutset to forest, original higher-arity scope projected to an edge, parallel factors, negative/dyadic costs, exact global ties with interleaved old leaf owners, known infeasibility, UNKNOWN fallback, finite global node limits, cache0/1/4096. Verify original solver on the small fixtures before edits, then prove node reduction and exact optimum afterward. Add counters distinguishing eligibility attempts, applicable/completed forests and charged cells; do not claim speedup from counters alone.

Independent review, full regression and source/class freeze are mandatory before one official Docker/JFR LogReg candidate03 run. Candidate01/02 evidence stays immutable. Reuse the initial26.819446540s baseline. Search nodes may legitimately change in this structural candidate. Report failure if planning is not strictly below10s; no rerun of the same candidate and no GLM/host workload benchmark.
