# Legacy federated planner removal — 2026-09-10

## Scope and result

Removed obsolete planner implementations, not physical candidates or privacy constraints.
The supported compiled planners are now exactly:

| Paper name | Configuration value | Implementation |
|---|---|---|
| FedFirst | `compile_fed_all_max_fed_fout_single_pass` | `FederatedPlannerFedAllMaxFedFoutSinglePass` |
| AggLocal | `compile_fed_heuristic_single_pass` | `FederatedPlannerFedHeuristicSinglePass` |
| COFEE-Regional | `compile_cost_based` | `FederatedPlanLocalCost` |
| COFEE-Global | `compile_exact` | `FederatedPlanExact` |

`NONE` and `RUNTIME` remain non-compiled sentinels. When federated compilation is explicitly
forced without a compiled planner value, the compiler uses current single-pass AggLocal.
Removed `compile_fed_all` and `compile_fed_heuristic` values fail explicitly; they are not
silently aliased to another algorithm. Callers must use the current names above.

## Deleted / simplified

- Five old `fedCostBased/fedDp` classes: planner, cost enumerator, estimator, memo table,
  and transient-table rewire implementation, together with tests exclusive to them.
- Dead DP-only support: `OracleUtils`, `RewireDagWalker`, `TransTableRewireUtils`,
  DP tree/debug logger methods, and obsolete compiler receipt recognition.
- Policy-score exhaustive `placement/selector/ExactPlacementSelector`, its exclusive
  tests, exhaustive FedAll/Heuristic entry points, and exhaustive adapter branches.
  **This is not the current Global cost optimizer.** Current Global and Regional search
  algorithms and shared cost semantics were not changed.
- Parent policy classes were eliminated; their existing receipt/emission bodies moved
  into the current final SinglePass classes. Both adapters use the concrete first-feasible
  selector; no exhaustive injection/fallback remains.
- The old 1,823-line `DpPlacementAdapter` was removed. Its still-used function-boundary
  projection was retained as `SyntheticBoundaryProjection`. The receipt, both projection
  overloads, and input-state selection method were checked against the pre-change source:
  their bodies are unchanged. Active consumers were renamed, not reimplemented.
- Shared privacy, relocation, cost and CFG tests were migrated to current Regional.
  The reusable hermetic fixture moved out of the deleted DP test package. Tests retain
  all four active planners and include non-replayable CFG reaching-definition consistency.
- The current tracked Java tree already had no MinST/min-s-t-cut/Dinic implementation.
  No such execution path is retained or reintroduced. Historical reports, measured results
  and generated historical API snapshots are preserved as historical evidence. Root
  `SOURCE_SHA256SUMS*.txt` files belong to the earlier frozen experiment input
  (`d89a27f424`); they are not updated or presented as manifests for this cleanup.

## Verification

The cleanup plan preceded production edits and was independently reviewed. Policy and
legacy-DP deletion used separate implementation lanes; the leader reviewed moved bodies
and integration, and the DP lane independently reviewed policy/factory changes.

1. **Behavior lock:** original factory/type/source tests 4/4 PASS; active first-feasible
   selector tests 12/12 PASS. New four-mode/removal tests first failed against the original
   classes (2 expected failures), then passed after removal.
2. **Fresh full main compile:** all **1,605 Java sources** PASS with JDK17/vector and
   `-Xlint:unchecked`. The output directory was new and the classpath contained dependency
   jars only, **not an old SystemDS.jar or old main classes**.
3. **Test compilation:** retained planner/placement/federated-instruction test sources
   and every edited retained test were recompiled against those fresh main classes.
4. **Final regression batch:** **238/238 PASS**, including current Global/Regional,
   single-pass policy, factory, receipt/emission, four-planner PRIVATE_AGGREGATE,
   mixed-privacy relocation, function boundaries, and physical-certificate contracts.
   Factory tests also verify removed classes are absent from the runtime classpath,
   detecting stale incremental-build output rather than merely checking source deletion.
5. **Known baseline failures:** `FederatedRefedPolicyTest` runs 74 tests, with the same
   16 failing method identities before and after this cleanup. No tests were disabled
   to hide them. These existing failures remain unresolved; the whole repository suite
   is **not** advertised as green. Three additional isolated older policy suites also
   reproduce the same 8/14 failures on original and clean candidate classes: 6 privacy
   metadata-resolution failures (MetadataCurrentPolicy/RealVector) and 2 Pathwise
   reentry expectation failures. Paired logs confirm identical failing method identities;
   these are recorded rather than hidden by the passing focused batch.
6. **Static checks:** live production Java has no imports/calls to removed planners;
   current test mentions of deleted class/config names are intentional rejection/absence
   checks. `git diff --check` PASS. No dependencies added.

Evidence in the isolated worktree's ignored `target/`:
`cleanup-final-main-compile.log`, `cleanup-final-main-sources.txt`,
`cleanup-final-test-compile.log`, `cleanup-final-regression.log`,
`cleanup-factory-baseline.log`, `cleanup-factory-red.log`,
`cleanup-classpath-tests.log`, `cleanup-refed-policy-tests.log`, and
`cleanup-baseline-parity.json`, `cleanup-policy-baseline.log`,
`cleanup-policy-candidate.log`, and `cleanup-policy-baseline-parity.json`.

## Limits / deployment

This is source cleanup and targeted regression validation, not a new runtime benchmark
or full Maven distribution build. Old configuration names and old planner Java APIs are
intentionally removed. Clean build output before packaging to avoid bundling deleted
classes; the classpath-absence regression enforces this requirement.

The frozen experiment JAR/stages, measurement artifacts and calibration profile were not
replaced or relabelled. Publishing this source does not deploy it into an active experiment.
Privacy, candidate feasibility, native runtime support and cost-model estimates were not
relaxed to make deletion tests pass.
