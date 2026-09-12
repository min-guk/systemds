# Incremental Regional (v8)

DP-Local builds a feasible seed with producer-before-consumer DP and exact hard-conflict repair. It then uses the shared encoded factor model to compute boundary messages. A message supplies a lower bound and conditional assignments; merging a complete variable bucket restores coupling and reuses its inputs. The planner improves the incumbent and certificate using these same messages. It does not restart Global.

Each original factor has exactly one owner. Every published checkpoint preserves `L <= C* <= U`, a nondecreasing lower bound and a nonincreasing feasible upper bound. The relative certificate uses conservative rounding of `(U-L)/L`; `L=0<U` cannot certify a relative target. Exact closure has `L=U`. These guarantees concern the encoded cost model, not observed workload runtime.

DP-Global solves the same physical model by exact variable elimination. Both planners use the shared cost encoding and elimination-order policy. FedFirst and AggLocal retain their first-feasible policy selectors. All four use `PlacementPlanApplication` for normalization and program application.

## Configuration

DP-Local always uses the current incremental implementation. The previous Certified/MBE, cost-shift, region-dual, replica-refinement and separate remaining-exact algorithms have been removed. Their implementations and experiments remain in Git history at `8fa253ccfa`.

Options have prefix `sysds.fedplanner.regional.incremental.`:

| Suffix | Default | Meaning |
| --- | --- | --- |
| `relativeGap` | `0.05` | Requested modeled relative certificate |
| `assignments` | `1000000` | Maximum assignments per merge |
| `retainedSlots` | `8000000` | Retained numerical-slot cap; not a JVM RSS limit |
| `timeMillis` | `10000` | Soft scheduling budget after bootstrap; zero disables this limit |
| `scoredCandidates` | `16` | Maximum candidates considered by merge scoring |
| `earlyStop` | `true` | Stop once the relative target is certified |

The former `incremental.enabled=true` setting remains accepted; it is no longer required. Explicit `false` or an invalid value is rejected. The old runner markers `regional.mode=off` and `regional.algorithm=legacy` are accepted only as neutral markers; they select no alternate implementation. Other values and an explicit `regional.initialBound` are rejected. Use the incremental options above to control the current planner.

The seed uses shared root preparation and bounded conditioned-table reuse. A resource limit or unsupported intermediate seed boundary can require the existing exact hard-repair preparation; this preserves feasibility and does not create a second planner. Global and Local keep the common `sysds.fedplanner.exact.fastOrder` and `fastOrderAssignments` settings.

## Timing and limits

`Compile Phase FedPlanner Decision` measures planner-owned model construction, search, certificate refinement and validated selection. Common preparation, post-selection diagnostics, conversion, application and finalization are reported separately. In-search trace work remains in Decision. The existing FedPlanner total is retained; historical totals cannot be relabelled as Decision measurements.

A resource/time stop returns the current feasible plan and valid certificate, which can exceed the requested gap. Limits are not a hard wall-clock timeout. The algorithm has no universal speed advantage over Global. Compare identical inputs, models, settings and all observations, including target misses.

Current checks cover exhaustive small-model agreement, conservative certificates, full factor ownership, interrupted refinement, canonical objective parity, root-table reuse, selected-plan authority and timing phase sums. Publication verification is recorded in [the session notes](SESSION_ISSUES_2026-09-12.md).
