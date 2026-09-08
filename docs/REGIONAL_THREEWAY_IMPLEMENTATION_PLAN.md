# Three Regional algorithms: implementation and planning comparison

Status: implementation in progress; no new experimental result is claimed here.
Date: 2026-09-08. User authorization: implement all three supplied algorithms and
evaluate which is effective at planning only. Source baseline: `afe805d889`.

## Scope and lane

Direct execution of the supplied designs, with native role-specific bounded
implementation/review lanes. Preserve existing `mode=off` and existing Anytime.
Do not activate a new workflow/goal solely because this is sustained work.
The earlier source, JAR, frozen stages, reports and raw pilot remain preserved.

New source: `/home/mchoi/so007-regional-threeway-20260908`.
New evidence: `/home/mchoi/so007-regional-threeway-evidence-20260908`.
Build and Docker planning experiments run on so007. Only the authorized
`run_LAN_docker.sh --planning-only` path is used for workload measurements.
No workload execution, worker JVM or new CP reference generation.

## Algorithm identities and invariant boundary

1. `THRESHOLD`: one cumulative original-decision region, adaptive predicted
   reduction of `H=U-(1+tau)L` per measured time, periodic mandatory original
   coverage, explicit persistent MBE replica model with restored equalities.
   Preserve each restored equality across later refinement. Solve the relaxed
   problem exactly within existing resource limits; keep prior valid L if a
   refinement cannot complete. Record replica/equality counts and actual P/L
   action selections. Width sweeps alone are not algorithm 1.
2. `TARGET_GAP`: best-bound frontier including deferred nodes, limited MBE
   strengthening, up to two candidate original variables with all-value probing,
   parent-bound improvement from every covered candidate partition, conditional
   Regional, then complete atomic branching on the selected variable. Reuse
   evaluated children and per-node feasible plans. This is algorithm 2.
3. `REUSE`: same certified frontier contract, one selected variable per node,
   one all-value conditional MBE pass, immediate parent LB update, at most one
   conditional Regional attempt, then reuse exactly those children to branch.
   No multi-variable strong probing or discretionary width refinement. This
   intentionally simpler schedule is algorithm 3, not a renamed algorithm 2.

All methods start with the same Regional seed and global MBE configuration.
Use the same physical cost surface, original hard factors, exact auxiliary
encoding and canonical evaluator. Original decisions precede auxiliaries.
Every accepted plan must pass original hard feasibility and raw binary64
canonical/solver parity. No privacy/candidate/placement/runtime gate changes.

Branch condition fixes only specified originals; preserve all constant factors
and leave every other original and all auxiliaries free for conditional MBE.
Conditional region additionally fixes its outside boundary, so its infeasibility
or optimum cannot certify the entire node. Branch fixed decisions that disagree
with the reference must be inside the region; keeping all branch-fixed originals
inside is the shared implementation convention.

Global lower bound for a frontier is `min(U,min node.lb)` including deferred
nodes, combined with prior valid global bounds via max. Never silently clamp a
node bound above a verified feasible cost for that node. True prune requires
`node.lb >= U`; target-sufficient nodes stay represented. An empty frontier can
imply exactness only after complete partitions and proved closures.

Incomplete probing inherits the parent bound for every uncomputed value. Keep
the parent until all children are ready, or initialize every child before
refinement. Each processed active node has bounded optional attempts followed
by branching/closure or an explicit resource exit. Terminal originals require
exact auxiliary evaluation. Exact solve preflight uses factor/auxiliary table
work, not merely original variable count. Existing exact solves are not hard
preemptible; report soft-budget overruns and end-to-end planning time.

## Shared code contract

The leader owns `RegionalSearchProblem.java`, `RegionalSearchOptimizer.java`,
physical integration/config/trace and integration tests. Algorithm lanes own
their optimizer and its tests, and coordinate any shared API changes upward.

`RegionalSearchProblem` will expose original-first encoded variables/factors,
decision count, canonical evaluation, conditional global MBE, conditioned exact
region/whole solve, resource preflight and deterministic incidence-based region
growth. Conditions are `int[decisionCount]` with -1 for unassigned values.
Returned assignments contain original decisions only; auxiliary freedom is
internal. Infeasible regional results are explicit, not malformed assignments.

`RegionalSearchOptimizer` will own the shared options, run budget, validated
incumbent/bounds, checkpoint/result records and algorithm dispatch. New config
uses `sysds.fedplanner.regional.algorithm=legacy|threshold|target-gap|reuse`
with legacy as default and the existing mode/width/limits settings retained.
Additional finite work/frontier/probe/coverage limits are explicit and traced.

## Validation before measurements

- Independent enumeration on small nonnegative finite models: every checkpoint
  encloses C*, monotone L/U, original feasibility, threshold termination.
- Replica diagonal objective equivalence, initial relaxation/MBE correspondence,
  persistent equality refinement (including auxiliaries), exact endpoint and
  failure/cancellation preserving old bounds.
- Frontier coverage, deferred-node inclusion, exact empty-frontier closure,
  partial probing, parent max-over-partitions, inherited child bounds, constant
  factors and original/auxiliary conditioning correctness.
- Conditional Regional infeasible while full node feasible; branch/reference
  mismatch, per-node seed reuse and canonical equality.
- Zero costs/budgets, NaN/malformed models, resource caps, incorrect node bounds,
  cached-probe reuse and deterministic order/counters. Distinguish algorithms
  with tests showing multi-candidate versus single-candidate work.
- Existing targeted 97-test regressions plus new tests and physical compiler
  integration; package JAR, verify source/JAR hashes and fresh Docker smoke.

## Planned comparison

Keep a written protocol before measured trials. Include Regional, existing
Anytime, algorithms 1/2/3 and independent Global. Primary initial workload is
the validated KMeans/P2P2D PRIVATE_AGGREGATE worker-1 planning cell; attempt PCA
as a second feasible cell through the same launcher. Record unsupported/failing
cells without treating them as valid comparisons or relaxing legality.

Use common thresholds 5%, 2%, 1%, multiple refinement budgets and at least five
paired repetitions with balanced order, common seed, same model/stage/JAR and
initial objective. Warm-up/smoke is separate from measured trials. Cap total
work and memory uniformly; record method-specific optional-work limits. Global
oracle time is separate. Select budgets from a documented smoke, not after
observing a winner; preserve every attempted row and any censored threshold.

Primary metrics: certificate success, time-to-threshold including seed/model
construction as available, final modeled gap vs Global, certificate gap, total
planner time, diagnostic/bound/region work, cache reuse, frontier size, resource
stops and overruns. Compare successful paired trials and censored/failure counts;
do not infer a universal winner from one workload or successful rows only.

## Delivery

Source/tests, executable planning harness and commands, model/JAR/data identity,
raw logs/receipts, machine-readable comparisons and a Korean result report.
Explain observed ranking and its limits, including negative results. Stop only
after all three implementations are tested and the authorized planning
comparison and report are complete, or a concrete unrecoverable blocker is
documented after alternatives have been exhausted.
