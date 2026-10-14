# LogReg movement and legality reuse audit

## Conclusion

The smallest sound movement-side improvement is a model-local, completed-only memo for
`visitBindingCostContexts`. Keep it behind the existing `DIRECT`/`IMPOSSIBLE` gate in
`movementGroupCost`; cache immutable evaluation snapshots and reconstruct each callback with the current
selection lookup. This removes repeated binding-DAG projection without changing factor scope, tuple
enumeration, movement arithmetic, activation union, diagnostics, or selected supply reconstruction.

A broader activation/origin projection shared by `inputCompatible` and `movementGroupCost` is sound in
principle, but it is a second step. It touches normal-continuation handling and early `NO` behavior and is
not the minimum change justified by the current profile.

## Fresh profile evidence

The evidence below comes from the fresh JFR
`logreg-bottleneck-before-01/cases/ml_logreg/fed.jfr`, not from the prior optimizer profile. Counts are
inclusive execution-sample counts, so overlapping rows must not be added as elapsed time.

| Inclusive frame | Samples | Share of 2,789 samples |
| --- | ---: | ---: |
| `MemoOptimizer$ReducedSearch.enumerateCore` | 2,599 | 93.2% |
| `MemoReachableContexts.visitResolved` | 1,036 | 37.1% |
| `MemoBindingResolver.enumerate` | 934 | 33.5% |
| `MemoProgramModel.movementGroupCost` | 826 | 29.6% |
| `MemoProgramModel.visitBindingCostContexts` | 616 | 22.1% |
| `MemoShapes.viewAt` | 401 | 14.4% |
| `MemoProgramModel.inputCompatible` | 191 | 6.8% |
| `MemoShapes.firstUnfixedCostGuardForBinding` | 123 | 4.4% |

The supplied runtime counter reports about 97% hit rate for the new shape-proof cache. The JFR agrees
that guard discovery is no longer the main movement cost: the remaining repeated work is reachable
environment enumeration, binding cost-context callback reconstruction, and contextual view creation.

## Dependency audit

`inputCompatible` is registered over consumer plus direct producer/head scope at
`MemoProgramModel.java:194-198`. For each tuple it repeats occurrence activation and binding resolution at
lines 350-379. The resolved semantic environments are independent of native rule ordinals except for:

- `headIsBound`, which changes resolver control flow at `MemoBindingResolver.java:690-700`;
- selected rule/layout values carried in `ResolvedOrigin` payloads.

Within one `MemoProgramModel`, `headIsBound` is structural and constant: the lookup returns
`headsBySlot.containsKey(...)` at `MemoProgramModel.java:1238-1246`. Selected rule/layout payloads do not
choose SELECT, loop phase, cardinality, call frame, or continuation. Legality still depends on the tuple
through `layout(origin, selected)` and the consumer rule's required input partition at lines 344-367.
Therefore environments can eventually be reused, while layout comparison must remain per tuple.

`movementGroupCost` repeats the same activation resolution at lines 650-657. Source and target layouts
are tuple-dependent. The `DIRECT`/`IMPOSSIBLE` decision at lines 658-659 is therefore a mandatory gate.
Only surviving `UPLOAD`/`COLLECT` paths call `visitBindingCostContexts` at lines 660-685. The latter follows
shape/cost guards; the new `MemoShapes` cache accelerates the next-guard proof but does not retain the
completed list of exact contexts, hence the 616 inclusive samples.

The following values remain tuple-dependent and must stay outside the proposed projection memo:

- source layout, including multi-return output slot and loop-head layout;
- consumer target partition;
- movement kind and worker-normalized movement cost;
- retained-origin activation calculation until `definitionActivationWeight(..., selected)` is separately
  proven selection-independent.

Workers are fixed for one model but must not be assumed across models. A cache must be model-local. It
must not cache mutable contextual HOP views. `OriginIdentity` must retain export path and boundary slot;
compiled owner alone is insufficient (`MemoBindingResolver.java:435-462`).

## Recommended minimal implementation

Add a bounded or naturally bounded model-local memo used only by `visitBindingCostContexts`:

```text
BindingCostKey = binding
               + exact syntactic guard assignments and canonical provenance
               + loop boundaries
               + iteration phases
               + cardinalities
               + exact call frame
               + maxDepth
               + all structural headIsBound bits

BindingCostProjection = COMPLETE ordered list of immutable evaluation snapshots
                      | UNRESOLVED terminal result
```

Guard keys cannot use `GuardDecision.equals` alone because canonical aliases can be equal while carrying
different syntactic guard IDs/call frames; phase proof code reads those syntactic fields. Use the same full
snapshot principle as the new `MemoShapes.SemanticContext`.

On a miss, run the existing recursion and collect snapshots in visitor encounter order. Publish only after
the whole recursion completes. On a hit, rebuild `Context` values using cached guards/loops/phases/
cardinalities and the *current* context's `SelectionLookup`, reachability flag, call frame, and max depth.
This keeps downstream source/target layout lookup current. Preserve `UNRESOLVED_COST_CONTEXT: binding N`
exactly; never turn unresolved into an empty/complete projection. Do not deduplicate or reorder snapshots,
because binary64 primitive order and activation event order are observable by exact objective audits.

The call site remains after lines 658-659. Thus a tuple whose movement is `DIRECT` or `IMPOSSIBLE` creates
no projection entry and cannot surface a latent cost-only unresolved condition that the current code never
visits.

Recommended counters:

- `bindingCostProjectionRequests`
- `bindingCostProjectionBuilds`
- `bindingCostProjectionHits`
- `bindingCostProjectedContexts`
- optionally `bindingCostUnresolved` and `movementPostGateRequests`

These count completed behavior, not cache capacity or hard-coded zero paths. The useful success signal is
that builds follow distinct semantic activation contexts while requests follow evaluated movement tuples.

## Second candidate, after the minimal memo

If a second profile still shows `MemoReachableContexts`/`MemoBindingResolver.enumerate`, introduce one lazy
edge-frame activation projection keyed structurally by `(use index, input position, call frame)`. Build it
with a selection-neutral lookup that preserves the model's constant `headIsBound`; store ordered
`ResolvedOrigin`, `OriginIdentity`, and `ResolvedEnvironment` rows plus terminal status. Both
`inputCompatible` and `movementGroupCost` can then apply current source/target layouts to those rows.

Do not resolve all inputs jointly merely to share this cache. The current legality contract is a universal
check per edge, and joint input enumeration can recreate the source Cartesian product removed by the cost
projection design. Edge-local rows are sufficient. Evaluate every cached row before returning `UNKNOWN`;
`NO` must continue to dominate an unresolved row just as the current early stop does. Movement cost must
continue to fail closed on unresolved activation.

## Required regressions

1. Cache enabled/disabled differential for every movement `CostTerm`: identical raw double bits,
   `BigDecimal` objective, selected rules, and reconstructed supplies.
2. Gate laziness: all `DIRECT`/`IMPOSSIBLE` tuples produce zero projection builds; the first upload/collect
   builds once and a repeated equivalent request hits.
3. Full context separation for phase, cardinality, max depth, call frame, syntactic guard frame with shared
   canonical provenance, and mutable `headIsBound`.
4. UNKNOWN preservation: an unbound/missing cost dependency still throws the same binding diagnostic;
   activation unresolved remains `UNKNOWN` in legality and fail closed in selected movement cost.
5. Multi-return output slots and call-return export paths remain distinct even when they share a compiled
   owner.
6. Loop-head source layouts change with the selected head variable without rebuilding or corrupting the
   semantic projection; one-worker `REP` normalization and multi-worker ROW/COL cases stay distinct.
7. Shared retained origin reached by several guarded demands retains the same activation union and
   primitive encounter order. Include an inactive branch whose internal guard must never appear.

## Risk assessment

The binding cost-context memo is high-confidence because it reuses a completed pure projection and leaves
all tuple-dependent movement decisions after the cache boundary. The activation/origin projection is
medium-confidence until differential tests lock early `NO` versus unresolved behavior and normal
continuation ordering. A reduced-layout final-cost cache should wait for proof that
`definitionActivationWeight` has no remaining selection dependency.
