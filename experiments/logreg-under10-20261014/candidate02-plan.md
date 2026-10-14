# Candidate02: measured solver hot paths

Candidate01 is preserved after exactly one Docker run. Its planning is 16.103018585 s, optimizer 13.563777113 s, nodes 2,933,891. All correctness, input-hash and JFR gates pass; only planning <10 s fails. A distinct candidate02 will receive exactly one run after fresh regression and freeze; candidate01 will not be repeated.

Fresh JFR has 1,110 optimizer execution samples. Domain cache lookup contains 333 samples (30%); scopedDomainHash 140 and exact equality 89. HashMap tree lookup is a leaf in 89 of these samples, evidence of costly collision handling rather than productive search. Reduced enumerateCore accounts for 164 nearest-method samples and coreLowerBound for 117; branchConnectivity has 54. Movement fell from 711 to 84 inclusive samples and its cost-context visitor from 543 to 28. Optimize the observed solver overhead first, without introducing another solver.

## Changes to implement after behavior lock

1. Keep exact domains. For immutable domain width <=64, maintain the full bit mask on existing remove/undo paths; wider domains keep full BitSet identity. Store immutable snapshots on cache misses and use the existing synchronous lookup probe on hits. Hash all input coordinates with a non-linear mixer, but always check exact equality; a hash is never a proof of equality. Preserve cache capacities, worklist revisions, support budgets and normalization invalidation.
2. Skip incremental BigDecimal lower-bound add/subtract when the contribution has the same BigDecimal value and scale. Keep all factor evaluation, invalidation and final objective assembly unchanged; this eliminates only an algebraic no-op.
3. Keep the owner-choice heuristic identical. Reuse a stable ascending list of actual core owners instead of scanning all removed owners. Consider maintaining exact live-neighbor counts at the domain cardinality 2->1 and 1->2 transitions if the existing adjacency is symmetric; rebuild it wherever adjacency is rebuilt. Verify remove/undo invariants with assertions and cache-off parity.
4. Lock behavior for compact mask boundaries 63/64/65, wide domains, nontrivial high ordinals, backtracking, hash collision and finite support/node budgets. Run the existing exhaustive/negative/tie suite and full integration suite after review.

No new forest/component solver, no approximate cost, no candidate deletion, no privacy/Oracle change, no new dependencies. Candidate02 is scoped to MemoOptimizer and corresponding regression tests. The measured result, including any miss of the target, will be retained.

## Final implementation choices

- Compact masks are per owner, not an all-small solver mode. Width 64 is the full signed long bit pattern; widths >64 remain full immutable BitSet snapshots. Wide hashes use the existing BitSet hash; full equality remains authoritative even for an actual hash collision.
- Bound no-op detection uses reference identity or BigDecimal.equals, which preserves both numerical value and scale. This matters because the general fallback solver publishes its complete lower bound as the incumbent. Stored contribution references are still refreshed.
- Active branch-neighbor counts are rebuilt with adjacency and updated exactly on cardinality 2->1 / 1->2. Assertions compare them with the original scan at queries and affected mutations.
- No alternate exact subsolver was added. Candidate02 should retain candidate01 search node count and selection order.
- Behavior lock passed before changes; final focused suite has 36 tests with assertions, including 63/64/65 widths, high-bit undo, cache 0/1/4096 and a real wide-domain hash collision. Integrated and workload validation follow separately.
