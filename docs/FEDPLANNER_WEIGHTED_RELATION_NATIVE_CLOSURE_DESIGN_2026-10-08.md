# Weighted FED/LOUT relation-native closure design

This is the initial design and its target contracts, retained for design history. The implemented scope, measured costs and remaining tuple expansion are reported in [the final result](FEDPLANNER_ORACLE_NATIVE_RESULT_2026-10-08_KO.md).

## Scope and invariant

This lane covers scalar `WSLOSS` and `WCEMM` candidates whose Oracle result is
`FED/LOUT`. A relation member is publishable only when it owns the same exact
source references, relocation action identities, proof objects, and LOCAL
realization support as the corresponding explicit row after the full placement
closure. An empty default LOCAL clause must not replace required DIRECT/RELOCATION
authority. Legacy no-action LOCAL clauses remain legal where the explicit path admits them.

## Where the explicit row gains authority today

`FED/LOUT` uses a default LOCAL realization. It therefore does not gain input
support in `bindDirectNativeCandidateRealizations`; that transfer specializes
native lineage and FED/FOUT output maps.

The decisive transfer is:

1. `relocations(...)` derives obligations for the exact consumer placement.
2. `bindRelocationCandidateRealizationsMeasured(...)` builds the consumer's
   target pools and an `ExactRelocationSourceInventory` from closed producer
   facts.
3. For every present matrix input, it selects exact source realization options.
   A pool-compatible source becomes a `DIRECT` binding. A source admitted by an
   exact relocation obligation becomes a `RELOCATION` binding carrying that
   action object.
4. `relocationBindingProduct(...)` creates LOCAL support. DIRECT-only clauses
   have no relocation proof. RELOCATION clauses retain the exact action-derived
   proof.
5. Privacy closure, support deletion, and executable projection remove clauses
   and states whose source or action authority disappears.

The selected weighted Docker receipts therefore legitimately contain an empty
proof list and a nonempty `DIRECT` input-0 binding to the durable federated
source. Dropping that binding changes both hard factors and the receipt.

## Exact-fact consumers that need a relation-native path

| Closure transfer | Weighted relation requirement |
| --- | --- |
| Function input/output closure | Keep explicit fallback for boundary-owned weighted outputs until relation boundary aliases exist. |
| Physical regeneration | Regenerate the unclosed Oracle region header, not exact tuple facts. |
| Direct/VALUE_MAP closure | Scalar LOUT does not need native-output grounding; source inventory revisions still invalidate its conditional support. |
| Relocation derivation | Must read region axes and emit the same obligations currently derived from exact rows. |
| Relocation binding | Must build per-state, per-input exact source/action choices and closure-owned support. This is the main adapter. |
| CFG replay/transient binding | Keep explicit fallback when a transient compatibility edge names an exact weighted rule member. |
| Privacy closure | Filter region input states and emissions before support publication. PRIVATE/PRIVATE_AGGREGATE cannot retain an absent protected payload. |
| Support pruning | Delete conditional choices by exact source/action identity and delete a state only when every choice is gone. |
| Executable projection | A FED/LOUT state is executable iff at least one closed conditional member has one live support choice. |
| Delta invalidation | Source/action deletion must dirty the owning region even when its coarse placement state is unchanged. |
| Final action verification | Every RELOCATION binding must name a currently published graph action; DIRECT bindings must name a live exact source. |

## Proposed adapter

The Oracle relation remains unclosed and contains only axes plus the exact
capability/profile/shape-proof/emission template. Closure adds conditional
support grouped by `(region, emission, target pool)`:

- each input position maps a `CandidateInputState` to exact binding options;
- `ABSENT_LOCAL` maps to a deliberate no-binding option;
- PRESENT states retain exact `CandidateRealizationReference` objects;
- mixed DIRECT/RELOCATION choices are split by action identity and proof set;
- repeated source owners carry a shared-owner equality constraint;
- the closed relation exposes `emissionsFor(inputs)` and materializes an exact
  member only when final selection asks for it.

The existing source-option loop in
`bindRelocationCandidateRealizationsMeasured` and
`relocationBindingProduct(owner, emission, choices, ...)` can be extracted.
The latter already consumes bindings rather than a full `CandidateRuleFact`.
The missing work is relation-aware relocation obligations, live-choice pruning,
and delta invalidation. Tuple suppression must happen only after those three
consumers accept the closed relation.

## Complexity target

For four weighted inputs, closure work should scale with the sum of exact
binding choices per state and action group, rather than the Cartesian product
of all input FType states. Legal combination IDs remain a fallback for
correlated choices that cannot be represented as independent axes. No product
is materialized to establish source liveness or proof equality.

## Verification contract

An independent test must build the same parsed program twice: once with the
relation-native adapter disabled before generation, and once enabled. It must
compare every legal tuple and final selected receipt, including:

- rule inputs and emission state;
- realization key and normalized semantic support contents; numeric combination IDs may differ across representations;
- proof contents across analyses, plus owned proof/source/action identity within each analysis;
- DIRECT/RELOCATION binding kind, position, exact source rule/realization, and
  action identity;
- graph relocation actions, selected assignment, and raw objective bits; hard-factor counts are reported separately and need not be equal across representations.

The explicit baseline must pass through the full ordinary closure. Expanding a
relation after closure is not an independent reference.
