# Regional + lower bound + remaining exact

This publication retains two planner paths: Global exact, and Regional with a single global certificate followed, when needed, by exact completion. Earlier iterative Certified/Anytime and A/B/C search controllers are preserved in archive commit `6b2f32143d`, rather than remaining executable alternatives. Historical reports describe their original snapshots.

## Algorithm and guarantee

Regional first produces a feasible plan with canonical modeled cost U. An initial global mini-bucket/replica relaxation supplies L with L <= C* <= U. The relaxed problem includes the entire encoded model; it does not fix decisions outside the Regional seed.

If the conservatively rounded relative certificate `(U-L)/L` is at most 5%, return the Regional plan. Otherwise, restore the remaining replica equalities and solve the affected component groups exactly, reusing components already solved. A completed exact closure returns L = U = C*. L=0<U never satisfies a finite relative target. A resource or time limit returns the valid current interval and an explicit stop reason; targetReached is computed from the returned interval. The certificate concerns this encoded cost model, not runtime prediction error.

“Remaining” refers to unresolved global coupling in the relaxation. Regional decisions are not permanently fixed for the exact completion. No unresolved component is discarded during interruption.

## Planner selection

Set the XML configuration property `sysds.federated.planner` to `COMPILE_EXACT` for Global, or `COMPILE_COST_BASED` for Regional. Regional certification is selected with these JVM properties (placed before the Java class or `-jar` argument):

```text
-Dsysds.fedplanner.regional.mode=remaining-exact
-Dsysds.fedplanner.regional.algorithm=remaining-exact
-Dsysds.fedplanner.regional.relativeGap=0.05
-Dsysds.fedplanner.regional.absoluteGap=0
-Dsysds.fedplanner.regional.sharedPreparation=true
-Dsysds.fedplanner.regional.compact=false
-Dsysds.fedplanner.exact.compact=false
```

The older `mode=anytime` spelling is also retained for compatibility with the measured configuration; it selects no iterative Anytime algorithm. `mode=off` keeps a plain Regional diagnostic baseline. Superseded algorithm selectors are rejected.

For the published three-workload pilot, the additional resource settings were:

```text
-Dsysds.fedplanner.regional.width=2
-Dsysds.fedplanner.regional.factorCells=10000000
-Dsysds.fedplanner.regional.totalCells=50000000
-Dsysds.fedplanner.regional.timeMillis=20000
-Dsysds.fedplanner.regional.exactClosureAssignments=9223372036854775807
```

These are reproduction settings, not promises of a hard deadline. The scheduling clock begins after the Regional seed, and an exact solve already in progress is not forcibly interrupted. Both planner paths retain the same privacy/placement legality and canonical cost authority. With compact=false, exact support reduction and equivalent-value quotienting still occur; singleton-variable substitution is disabled.

## Shared preparation

The Regional backend and initial bound reuse one reduced encoded root. Immutable dense tables avoid redundant generation and copying, while a bounded per-factor cache reuses conditioned tables. Phase traces distinguish freeze, support, quotient, rebuild and compile costs. The diagnostic switch `sharedPreparation=false` allows a comparison against the original Regional preparation path.

## Evidence and history

The [shared-preparation report](REGIONAL_SHARED_PREPARATION_5PCT_REPORT_2026-09-09_KO.md) records the preceding frozen build: 109 targeted tests and 27 native planning-only trials. It found substantial StepLM improvement, an L2SVM regression and GLM certification without exact completion. Those measurements do not establish a universal speedup. Fresh publication checks are recorded in [the session log](SESSION_ISSUES_2026-09-09.md).

To inspect or recover earlier versions without replacing the final branch:

```bash
git show 6b2f32143d:src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalAnytimeOptimizer.java
git worktree add --detach /tmp/systemds-regional-experiments 6b2f32143d
```

The replica bound and mini-bucket implementation remain because the final algorithm uses them to construct the initial certificate and complete its unresolved coupling. They are shared solver infrastructure, rather than the removed Anytime scheduling policies.
