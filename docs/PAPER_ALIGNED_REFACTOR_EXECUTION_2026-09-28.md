# Paper-aligned search-space refactor — execution record

## Scope and frozen baseline

Implement the approved plan at
`/home/mchoi/cofee-evaluation/.omx/plans/COFEE_W1357_PAPER_ALIGNED_SEARCH_SPACE_REFACTOR_PLAN_2026-09-28.md`.
The independent workspace is `/home/mchoi/w1357-paper-aligned-refactor`, branch
`refactor/w1357-paper-aligned-20260928`. Original source is untouched.

Baseline commit `aaa574ebb5` records the original `9172a08e28` plus the exact
in-progress correctness patch imported from `/home/mchoi/w1357-logreg-nary-fix`.
That import is a separate review unit, not part of the refactor.
The immutable baseline checkout, per-file source manifest, imported patch, and
approved plan are under `/home/mchoi/w1357-diagnostics/paper-refactor-20260928`.
The external correctness prerequisite C0 remains unproven until the original
three methods, related regressions, original B-21 192/36 assertions and frozen
physical multisets pass on a source/JAR-matched snapshot.

## Cleanup plan written before production edits

1. P0: run the existing semantic regression gates on the frozen checkout;
   preserve failures, source hashes, XML, JAR hash and fresh B-01/B-21 exports.
   Reuse the existing canonical physical comparator without changing it.
2. P1: rename private builder terminology and reflection references mechanically.
   Preserve predicates, call order, public records, identities and representation.
   Compile and repeat semantic gates and before/after physical multiset comparison.
3. P2: independently review the caller/state contract below before extraction.
   Extract L1 facts, L2 local generation, L3 relation ownership, observation and
   publication in order-preserving steps. Give each semantic state one writer.
   Split actual responsibilities; moving the whole builder is insufficient.
4. P3/P4: only after C0, consolidate complete owner updates and reuse the existing
   dirty SCC schedule. Preserve all six stability components and replacement ->
   binding -> expired-clause removal. Do not change unproven transfer order.
5. P5: publish the actual concept/function mapping, a worked example, diagram,
   algorithm, clean regression/static evidence and representative identical-Docker
   comparison through `run_LAN_docker.sh`. Do not resume the paused performance goal.

No dependencies, cost-model changes, new runtime features, candidate caps,
privacy relaxation, runtime fallback or weakened assertions are permitted.

## State and actual caller contract for independent review

| State | Actual callers / responsibility | Writer and boundary |
| --- | --- | --- |
| Compiler facts | `analyzeCfg`, preliminary abstract shape refinement, occurrence identity and original shape capture | Facts preparation; expose snapshots after preparation |
| Single-partition facts | Initial compiler construction in L1; `closeOccurrences` and `changedOccurrenceOrdinals` depend on current nodes/relations | Refined cardinality belongs to L3 at its original call site |
| Candidate state | `buildNode`, `closeCfgTransientCandidateDependencies`, function input/output closure, physical/materialization/privacy closure | L3 owns nodes/rule keys/rule facts/transient bindings/relocations; generator returns fresh base |
| Work | closure return `changedOrdinals`, final composition `pendingPhysical` | L3 consumes and merges without duplicating SCC queues; pending work is the sixth stability component |
| Candidate-dependent structure | `expandFunctionBoundaryContexts`, `closeCfgDurableAnchors`, original value-version closing | L3 at original call sites; never precompute as immutable L1 facts |
| Generation bases | `replayBases`, `retainCompleteDerivedBase`, `recordCandidateBase` | Per analysis, independently of currently supported realizations |
| Source proof inventory | physical owner commit updates nodes/index/keys/facts, then invalidates `MaterializationProofInventory` | Keep commit and invalidation together at the existing source revision boundary |
| Direct native revision | `closeDirectComponents`, `DirectSupportIndex.update`, component schedule repair, source index and continuity revision | Preserve topology rebuild and outstanding work before advancing revision |
| Effective privacy | `closePrivacyDomains`, `staticPrivacyProjection`, post-privacy replay | Derived from current relations inside closure |
| Loop seed ledger | entry revision, completed transfer and replay | Closure-owned; preserve imported correctness semantics without adding speculative exit memo |
| Diagnostics | recurrence tracker, export deltas, metrics and optional privacy evidence | Read semantic state only; never become canonical execution state |
| Publication | graph-owned equal-state canonicalization, support factorization, transient source-state binding, authority validation and analysis creation | Consume stable closed state; no candidate generation or recovery; derived/relocation action binding stays L3 |

## Fallback inventory and classification

- CFG refinement falls back to conservative all-branches facts on nonconvergence:
  grounded fail-safe preserving reachable paths; retain the existing boundary.
- Optional observer/metrics/privacy evidence paths: observation only, preserve
  enabled/disabled semantic equality with existing tests.
- Source-domain empty/bottom vs local, provisional loop seeds and retained bases:
  semantic contracts, not interchangeable fallback defaults; preserve primary and
  negative tests and do not broaden these branches.
- Imported correctness patch and any failures: separate evidence, not silently
  repaired or hidden inside structural edits. C0 controls P3/P4.

## Review and verification ledger

Author: root implementation lane. Independent boundary reviewer:
`p2_boundary_review` (read-only). Baseline evidence owner: `p0_baseline`.
Independent P2 review approved this table after correcting the publication/action
boundary and explicitly separating initial vs relation-refined single-partition
facts (both corrections applied before extraction).

Status: baseline verification and boundary review in progress; no implementation
stage is claimed complete yet.

## P2 checkpoint

- Public builder 192 lines; facts, generator, relation closure, support operations and diagnostics now have separate owners. L3 is split into seed, boundary, function, privacy, feasibility and publication phases.
- Independent extraction review found no change to identity creation, call order, six-component convergence or commit/invalidation lifetime. Published immutable objects survive cleanup and builder reuse.
- Clean expanded 44-class suite: baseline and P2 each 365 tests, 9 failures, 3 errors, 5 existing skips. Failure identities match; the stale source-branch inventory observes moved source locations. Evidence: `evidence/{baseline/p2-expanded-reference,p2/expanded-clean-v1}.json`.
- Protected physical exports: before/P2 each 216 proofs / 56 physical plans on P and E; bidirectional canonical physical sets and multiplicity match. The P audit is retained separately; raw bytes differ and require semantic classification. Comparator was unchanged.
- Static checking: no configured lint/SAST gate in POM. Explicit default Checkstyle report and baseline/context delta are recorded in `evidence/P2_STATIC_CHECKSTYLE_REPORT.md`; not reported as lint green. Unused imports: zero.
- External C0 finished independently at `cfab6c8258c1525761b33ee8f6a6caa8c55c84b8`. It is imported after this separate P2 checkpoint, with source/JAR and regression evidence refreshed before P3.
