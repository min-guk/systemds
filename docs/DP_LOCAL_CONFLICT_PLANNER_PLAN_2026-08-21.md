# DP Local-Conflict Planner Rewrite Plan (2026-08-21)

## 1. Target Result

Replace the production `COMPILE_COST_BASED` path with a standalone cost-based
planner that:

1. consumes the canonical, privacy-filtered `PlacementAnalysis` shared by every
   compiled planner;
2. uses the same authority-bearing physical domains, hard feasibility factors,
   and canonical physical cost factors as Exact;
3. makes one bottom-up local choice per decision from the decision and its
   already-selected inputs;
4. retains exactly one minimum-cost representative per complete local state and
   never applies an arbitrary top-K cap;
5. detects only real hard-factor conflicts after the local pass and repairs each
   connected conflict block by comparing all legal local assignments;
6. re-optimizes shared-producer blocks locally, so a producer consumed by parents
   `a` and `b` is chosen by the cost of `producer + a + b + incident movement`,
   while shared computation and reusable movement factors are charged once;
7. emits only a fully legal, authority-complete placement certificate; and
8. compiles faster than the legacy DP and, on the measured workloads, targets a
   lower planning time than Exact without claiming global optimality.

The stop condition is: targeted regression tests and a clean package build pass,
the compile-only Docker harness completes for DP and Exact on the existing
LOGREG/KMEANS benchmark blocks, and the report separates measured results from
remaining algorithmic risk.

## 2. Non-Negotiable Invariants

### 2.1 Shared feasibility authority

- Privacy acquisition, propagation, and candidate exclusion stay in
  `PlacementAnalysis` construction.
- The local planner does not recreate privacy rules or expand a filtered domain.
- `ExactPhysicalModel` remains the owner of categorical physical alternatives and
  hard factors for candidate authority, transient/function identity, relocation,
  FType, derived-FOUT anchors, and compiled/logical inputs.
- `ExactPhysicalCostModel` remains the owner of computation, transfer,
  materialization, frequency, and reusable-movement costs.

### 2.2 State-minimum pruning

- A local state is the complete future-observable physical alternative: placement
  (`CP/FED`, `LOUT/FOUT`, `FType`, shape dependence) plus the exact candidate,
  input-authority, and relocation identity needed by lowering.
- For a given local boundary and state, only the minimum-cost representative is
  retained.
- Different complete states remain incomparable; there is no top-K truncation.
- During a conflict-block solve, representatives are recomputed under that
  block's fixed boundary. This avoids retaining stale subplans while preserving
  authority distinctions required for legality.

### 2.3 No first-coherent shortcut

- A conflict block is not resolved by the first feasible assignment.
- Every legal assignment within the local block domain is compared under all
  incident canonical factors, with variables outside the block fixed.
- Ties use the existing deterministic categorical order.

### 2.4 No illegal output

- Every selected value must belong to the privacy-filtered domain.
- Every hard factor must evaluate to finite cost before projection.
- Existing candidate/relocation validators and transactional emission remain the
  final authority.
- If bounded local repair cannot prove feasibility, the planner fails closed; it
  must not silently emit or relabel an illegal plan.

## 3. Algorithm

### 3.1 Canonical model construction

Build `ExactPhysicalModel` and `ExactPhysicalCostModel.PhysicalCostSurface` once
from the invocation-owned `PlacementAnalysis`. Index variables, factors, compiled
input edges, transient edges, and function-argument edges once. This removes the
legacy DP's rewire/unroll graph, fixed-point re-enumeration, output-closure scans,
and repeated decision-map reconciliation from the production path.

### 3.2 Bottom-up local pass

Order decisions producer-before-consumer using compiled and logical input edges;
append cyclic/unordered residuals in canonical decision order. For each decision:

1. evaluate all complete local states against factors whose other variables have
   already been selected;
2. prune duplicate representatives by complete state, retaining the minimum;
3. choose the finite minimum state, or the deterministic least-conflicting state
   as a temporary value when a simultaneous repair is required.

This is a local cost-based recurrence, not FedAll/Heuristic policy reuse and not
global variable elimination.

### 3.3 Conflict index and repair

Build factor incidence once. After the local pass, enqueue violated hard factors.
For each connected violated component:

1. start with the variables in the violated factor scopes;
2. include directly coupled hard-factor variables needed to make the block
   satisfiable;
3. hold the remaining assignment fixed;
4. solve all local block assignments exactly under incident hard + cost factors,
   using local variable elimination instead of materializing their Cartesian
   product; and
5. accept only an assignment that reduces the number of violations, breaking ties
   by incident canonical cost.

Expansion is structural, not cardinality-capped. The factorized local solve does
not discard assignments or states; it only changes evaluation order. If a parent
couples multiple shared inputs, their overlapping blocks are merged and optimized
together.

### 3.4 Shared-producer improvement

Pre-index producers with multiple compiled/logical consumers. Optimize each
producer plus its direct consumers as one local block. Merge two blocks only when
the same parent consumes both shared producers; a producer--consumer chain is
kept as ordered local blocks instead of being transitively collapsed into a
near-global solve. Once legality is established, optimize each block once with
its boundary fixed. Because canonical factors are evaluated once per factor,
reusable materialization and transfer are not double-counted per parent.

### 3.5 Final certificate

Evaluate all hard factors and the canonical objective on the completed assignment,
construct the existing authority-complete physical selection, project it with a
`DP-LocalConflict` planner identity, validate candidate/relocation receipts, and
emit transactionally.

## 4. Implementation Slices

1. Add a package-local local categorical optimizer beside the Exact factor model.
2. Add the `FederatedPlanLocalCost` planner entry point and route
   `COMPILE_COST_BASED` to it.
3. Generalize the existing physical projector only enough to accept an explicit
   planner/certificate identity; preserve Exact's current default behavior.
4. Keep the legacy `FederatedPlannerDpFedCostBased` class temporarily for direct
   compatibility tests, but remove it from the production factory path. Do not add
   another compatibility/reconciliation layer to it.
5. Keep legacy memo pruning lossless: one minimum representative per complete
   boundary state and no top-K cap.

## 5. Regression Tests Before/With Implementation

- Local frontier retains one minimum representative per complete state and keeps
  all distinct states beyond eight.
- A shared producer with two parents selects the minimum joint local assignment,
  not the first coherent assignment and not either parent's isolated preference.
- A parent coupling two shared producers is solved as one overlapping block.
- A locally chosen hard-factor conflict is repaired before projection.
- Candidate/FType/privacy-filtered domain membership cannot be bypassed.
- Factory maps `COMPILE_COST_BASED` to the new planner and Exact remains unchanged.
- Existing factory source-ownership and placement emission contracts pass.

## 6. Verification and Experiment Contract

### 6.1 Code verification

Run, in order:

1. focused local-optimizer and factory tests;
2. existing Exact physical model/projection tests affected by shared code;
3. placement/privacy guard tests;
4. Maven compile/package (tests skipped only after targeted tests pass);
5. static source guards.

### 6.2 Compile-only experiment

- Use the existing immutable Docker experiment harness, never direct host
  `run_LAN.sh` execution.
- Build a fresh artifact from the resulting source tree and record commit/tree/JAR
  hashes.
- Disable runtime execution, planner trace, JFR, GC logging, and other measurement
  instrumentation.
- Reuse the established warmup/repetition policy (one warmup, ten measured runs).
- Measure planner time only for `DP` and `Exact` first on LOGREG (WAN-Light, one
  worker) and KMEANS (WAN-Light, two workers); include FedAll/Heuristic only if the
  existing harness requires a complete four-planner block.
- Compare against
  `/home/mchoi/g014-planner-compile-benchmark-af14a22107-20260821-v1/control/compile-summary.json`.

## 7. Risks and Explicit Non-Claims

- Local conflict optimization is not globally optimal on arbitrary cyclic/highly
  coupled factor graphs; Exact remains the global reference within its resource
  limits.
- A conflict block may expand substantially in an adversarial program. The
  implementation records block/assignment statistics so this is visible rather
  than hidden behind a top-K heuristic.
- Faster compilation is a measured target, not an assumption. If the fresh Docker
  result does not beat Exact, profiling will attribute time to shared model/cost
  construction versus local search before further changes.
- Runtime quality requires separate execution experiments; this task's requested
  experiment is compile-only.

## 8. Completion and Verification Record

### 8.1 Implemented design

The production `COMPILE_COST_BASED` path now uses `FederatedPlanLocalCost` rather
than the legacy reconciliation-heavy DP. The implementation:

1. consumes the same `PlacementAnalysis`-owned, privacy-filtered physical domains,
   hard constraints, and canonical cost surface as Exact;
2. visits decisions in producer-before-consumer order and retains exactly one
   minimum-cost representative for each complete `Alternative.signature()` state,
   without an arbitrary top-K limit;
3. repairs violated hard constraints by solving only the incident conflict block;
4. optimizes a shared producer and its direct consumers as a local producer-star,
   merging stars only when the same parent consumes multiple shared producers;
5. solves multi-variable local blocks exactly by local variable elimination while
   fixing the outside boundary; and
6. validates the final hard factors and canonical objective before transactional
   projection/emission under the `DP-LocalConflict` identity.

The measured implementation is commit
`81556286e2d63426cf73d6d6f976718854b2f6f0` (tree
`d13bcf07879d03b4ab3c8a73131a5d805472565b`).

### 8.2 Regression and build evidence

The focused local-optimizer, integration, factory, architecture, Exact physical
model, and projection-authority set passed 25 tests. This includes explicit tests
for minimum-per-state pruning, more than eight distinct states, minimum legal
conflict resolution rather than first-coherent selection, a shared producer with
two consumers, and non-transitive producer-chain grouping. A fresh Maven package
build also passed with the repository's unrelated global checkstyle baseline
disabled. The resulting JAR SHA-256 is
`0b624873771c885c738c2aaec89c78ef0082b6fa17e5e183a05d02c289b44979`.

### 8.3 Compile-only experiment evidence

The immutable Docker stage is
`9264df22e8a3f2bc238843384462e4aea68298b5beaa5f63a41f19e0ee334d90`.
Planner trace, JFR, GC logging, runtime explain, and runtime execution were disabled.
Each planner received one warm-up and ten measured fresh-JVM repetitions inside the
same blocked workload/worker cell. Both cells completed with zero failures and all
88 raw observations report `execution_seconds = 0.0`.

| Workload | FedAll | Heuristic | DP-LocalConflict | Exact |
|---|---:|---:|---:|---:|
| KMEANS, WAN-Light, 2 workers | 0.891608 s | 0.879071 s | 1.088524 s | 1.185302 s |
| LOGREG, WAN-Light, 1 worker | 17.973684 s | 1.596453 s | 1.346531 s | 2.713388 s |

Relative to the previous production DP, the new planner is 68.76% faster on
KMEANS (3.20x) and 79.15% faster on LOGREG (4.80x). It is faster than Exact by
8.16% and 50.37%, respectively. On LOGREG it is also faster than Heuristic by
15.65% and FedAll by 92.51%. On KMEANS it remains 22.09% slower than FedAll and
23.83% slower than Heuristic, so these measurements do not justify a universal
"fastest planner" claim. The compile campaign does not measure runtime plan quality.

Primary machine-readable results and their human-readable report are at:

- `/home/mchoi/g014-local-conflict-compile-81556286e2-20260821-v3/control/compile-summary.json`
- `/home/mchoi/g014-local-conflict-compile-81556286e2-20260821-v3/control/compile-report.md`
