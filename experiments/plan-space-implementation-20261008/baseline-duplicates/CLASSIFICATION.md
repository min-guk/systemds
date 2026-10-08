# Baseline duplicate-merge origin classification

The run used commit `276f958efc91477e3d0e8a2a492929455dd77227` plus diagnostics-only source changes. It used the repository Docker dispatcher, `PRIVATE_AGGREGATE`, the aggregated plan-space fixture, and a 2,048-event trace bound.

## Preserved published relation

- rules: 29
- realizations: 48
- final support clauses: 184
- legacy cumulative duplicate merge clauses: 925
- diagnostics-classified duplicate merge clauses: 925
- retained merge events: 307
- dropped events: 0

## Exclusive classification

- identical immutable support-list replay: 0
- shared exact clause-object replay: 37
- equal but separately allocated clauses: 888
  - exact product-batch duplicate: 0
  - same route and same closure revision: 695
  - different route in the same closure revision: 79
  - equal support replayed across closure revisions: 114
  - unresolved provenance: 0

The 888 equal-distinct clauses are fully covered by 38 retained origin transitions. No origin entry overflow or transition-key overflow occurred.

The largest origin transition is 609 clauses generated again within semantic revision 0's `relocation-pre-cfg` route. Other notable work includes 45 within initial `privacy-relations`, 16 from `cfg-grounding` into `relocation-pre-cfg`, and repeated exact support carried across initialization or later semantic revisions.

## Interpretation limits

`sameRouteSameRevision` proves the same explicit closure route and revision, but its scope is broader than one Cartesian product invocation. It is therefore generation/rebuild duplication, not proof that every such clause arose in one product call. `sameBatch` is reserved for an explicit product-level batch scope; this baseline did not install that finer hook and consequently reports zero rather than estimating it.

`unchangedRevisionReplay` means structurally equal support clauses were allocated under different recorded closure revisions. It does not by itself prove that every upstream dependency object was identical; exact clause equality proves that the published support authority was unchanged.

The 37 shared-object cases prove identity reuse only. They are kept separate from closure replay because object identity alone does not identify the closure revision that caused the merge.

## Reproduction

```text
scripts/fedplanner/run_LAN_docker.sh --plan-space-example \
  --script scripts/fedplanner/examples/plan_space_aggregated.dml \
  --output-dir /home/mchoi/plan-space-implementation-20261008/baseline-origin-duplicates-routes \
  --engine-target /home/mchoi/plan-space-implementation-20261008/baseline-origin-engine-routes \
  --privacy PRIVATE_AGGREGATE --timeout-seconds 120 --merge-diagnostics 2048
```

Exact Docker command, dependency hashes, probe hash, and input hash are stored in `provenance.json`. The complete counters, 307 event traces, and 38 origin transitions are stored in `trace.json`.
