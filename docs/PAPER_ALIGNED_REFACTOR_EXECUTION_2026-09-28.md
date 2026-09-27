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
The external correctness prerequisite C0 was subsequently satisfied on final
correctness commit `cfab6c8258` and its exact translated import `a8bfa88413`.
The original three methods, related clean 469-case suite, B-21 192/36 and frozen
physical multisets pass on paired source/JAR snapshots; see `evidence/c0/gate.json`.

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

Status: P0–P4 implementation and independent boundary/update/index reviews are
complete. P5 final clean, physical exports and Docker comparison are in progress.
The checkpoint results below are historical evidence, including repaired failures.

## P2 checkpoint

- Public builder 190 lines; facts, generator, relation closure, support operations and diagnostics now have separate owners. L3 is split into seed, boundary, function, privacy, feasibility and publication phases.
- Independent extraction review found no change to identity creation, call order, six-component convergence or commit/invalidation lifetime. Published immutable objects survive cleanup and builder reuse.
- Clean expanded 44-class suite: baseline and P2 each 365 tests, 9 failures, 3 errors, 5 existing skips. Failure identities match; the stale source-branch inventory observes moved source locations. Evidence: `evidence/{baseline/p2-expanded-reference,p2/expanded-clean-v1}.json`.
- Protected physical exports: before/P2 each 216 proofs / 56 physical plans on P and E; bidirectional canonical physical sets and multiplicity match. All 216 P audit rows are equal as parsed JSON in order; raw serialization differs. Comparator was unchanged.
- Static checking: no configured lint/SAST gate in POM. Explicit default Checkstyle report and baseline/context delta are recorded in `evidence/P2_STATIC_CHECKSTYLE_REPORT.md`; not reported as lint green. Unused imports: zero.
- External C0 finished independently at `cfab6c8258c1525761b33ee8f6a6caa8c55c84b8`. It is imported after this separate P2 checkpoint, with source/JAR and regression evidence refreshed before P3.

## P3/P4 plan and transfer dependency review (before edits)

Independent reader `extract_generator` reviewed the original and extracted callers.
P3 consolidates only already-complete `ClosureUpdate` assignments. Partial updates
keep their original field writes and actions/pending work remain independently owned.
A method-local physical state owner will keep node/index/key/fact commit and proof
inventory invalidation together, in the original order; scheduling follows the commit.
No transfer is reordered. P4 shares the duplicated lazy node identity index inside
one immutable committed inventory. It never shares indexes between revisions.

| Transfer | Reads | Writes / adds or deletes | Invalidation and order |
| --- | --- | --- | --- |
| CFG replay | Current candidate/logical state; all compiler writers | Four update fields; adds/removes rows and logical compatibility | Existing loop-seed and native revision contracts |
| Physical rebuild | Current exact input state and committed proof inventory | Owner node, block lookup, ordered keys/facts; adds/deletes support | Four writes then inventory invalidation, then refined/worklist scheduling |
| Materialization | Fresh generated base and committed source authority | Fields already returned/consumed by each caller | Preserve partial vs full caller update; actions unchanged |
| Privacy/prune/projection | Latest privacy, sources, actions | Explicit nodes/facts only; removes invalid states/clauses | Global transfer retained; localization is not proven |
| Relocation | Current complete relation and actions | Replacement-bound clauses; action state at pass end | Discover/bind replacements before expired-clause removal |
| Publication | Stable state | Equal graph-owned states, canonical factors/logical binding | No candidate generation/recovery |
| Lazy proof indexes | Immutable constructor-time nodes, edges | One identity index and one edge index per inventory | Commit discards inventory; resolver query memo resets remain per query |

Existing regressions cover base identity/source reappearance, OR/AND siblings, all
writers, foreign owners, dirty SCC topology repair, pending work, inventory revision
isolation and diagnostic parity. P4 adds edge-first/resolver-first query-order coverage
only if this is not already covered. No broad CFG/privacy/action fusion is attempted: its
commutativity is not established. The six full equality checks remain the stop condition.

## C0 / P3 checkpoint

C0 is satisfied by `evidence/c0/gate.json`: frozen final correctness source and
integrated source each pass the original 48-class clean suite (469 tests, zero
failures/errors); paired JAR hashes and source manifests are retained. Protected
P/E exports equal the initial baseline in both directions and multiplicity (216/56),
including the original B-21 192/36 assertion.

P3 replaces ten repeated complete updates with `applyUpdate`. Partial writes stay
explicit, including the initial materialization call that intentionally does not
replace logical bindings. `PhysicalCandidateState.commit` writes node, exact block
index, keys and facts, then invalidates inventory, before scheduling consumers.
The combined semantic/stale-test-repair slice ran 102 cases; all P3 semantics passed.
Four failures are in the test maintenance work (real function setup, source-capability
parity and an obsolete privacy source guard), separately tracked and not accepted as
a green test run. No support predicate or transfer order changed.

## P4 checkpoint

Independent review approved the P3 commit and P4 index reuse. The latter removes
one duplicate full node-index traversal and one map allocation when both edge lookup
and the materialization resolver first use the same committed revision (two builds
become one). This is a structural operation count, not a claimed wall-time gain.
The immutable node snapshot, `IdentityHashMap`, lazy edge validation, fresh query
memo and commit-driven inventory expiry remain unchanged.

`edgeAndResolverQueriesAreOrderIndependentAndRevisionScoped` compares both query
orders against cold owners, distinguishes changed worker pools, and rechecks the old
inventory after the new revision. It passes along with existing immutable-constructor,
failed-duplicate-edge, physical generation, source reappearance and dirty-SCC tests.
The current 103-test slice leaves two errors in a separately repaired synthetic
function fixture; all P3/P4 semantics pass. Final completion still requires the final
clean combined suite and identical before/after protected exports.

## Branch inventory rebase

The old reviewed manifest had 5,832 sites while the frozen P0 source already had
7,459. That prior drift is explicitly retained as `pre_existing_manifest_drift`,
not attributed to this refactor. The unchanged AST scanner was run on actual P0,
C0, P1 and each refactor checkpoint. Each removed/added ID is classified in
`evidence/branch-inventory/classified-deltas.tsv`; the human audit and stage counts
are adjacent. P2 has 3,593 byte-identical moved branch snippets on each side; the
remaining edits map to reviewed private renames, extraction and lifecycle phases.
P3 has 13 replacements and P4 has 7 removed/8 added sites for update/commit and
lazy index sharing. This is a structural guard, not universal semantic proof.

The final golden has 7,471 sites. Diagnostics remains in the source coverage after
extraction (146 sites), so no old branch surface is silently omitted. Comparator/
scanner logic is unchanged; only the owner source list and reviewed data change.

## Final source-guard maintenance

The first final 73-suite clean run had 576 cases, three failures, no errors and
ten existing public-only skips. All three failures were stale architecture source
guards. The underlying production shape/analysis/selector files match C0 exactly.
The guards now require all four immutable shape maps, inspect actual map ownership
declarations and every constructor seam, recognize complete Java type tokens, and
check the public structural authority contract instead of compiler traversal scratch.
Positive and adversarial fixtures remain active. Both guard classes pass 11/11
(`evidence/final/architecture-guards-v2.json`); the final combined clean run follows.
