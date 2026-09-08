# Certified / Anytime Regional: implementation and ablation plan

## Scope and fixed baseline

Implement the requested modeled suboptimality certificate on so007, starting from
`ad5b3ba52fa6a59154e99a34d0a76641a01c1500` (activation integration after explicit-binding rollback).
The existing `FederatedPlanLocalCost` / `LocalPhysicalOptimizer` produces the Regional
incumbent. Global is `ExactPhysicalOptimizer` over the same authorized finite model.
Use an isolated checkout and newly built artifact; historical experiment stages are immutable evidence.

The certificate concerns the encoded objective, not observed execution time, an
unencoded plan space, or cost-model error. Preserve candidate domains, privacy,
placement/emission authority, cost semantics and runtime behavior. Add no dependency.
This is scoped implementation with independent algorithm and verification slices.
The user's experiment scope is **planning only**: use compiler/planner traces and
do not launch workload execution or worker JVMs. This also excludes regenerating
CP reference results by running workloads as part of this task.

## Invocation and reproducible controls

Use the existing `COMPILE_COST_BASED` selector (`mkl-cost` in the Docker harness).
The baseline is the default, `-Dsysds.fedplanner.regional.mode=off`.
Set `mode=certify` to compute a certificate without changing the Regional plan,
or `mode=anytime` to enable region improvement. All following keys have prefix
`sysds.fedplanner.regional.` and are Java system properties:

| Key | Default | Meaning |
| --- | --- | --- |
| `width`, `maxWidth` | 2, 8 | Initial and maximum mini-bucket i-bound |
| `rounds` | 4 | Maximum refinement iterations |
| `refineBound` | false in certify; true in anytime | Increase width one per round |
| `regionGrowth`, `maxRegion` | 8, 64 | Added original decisions per round and total region cap |
| `policy` | `DISAGREEMENT` | `DISAGREEMENT`, `STRUCTURAL`, or `RANDOM` |
| `seed` | 20260908 | Deterministic random-policy seed |
| `timeMillis` | 1000 | Refinement scheduling budget; 0 immediately returns initial certificate |
| `factorCells`, `totalCells` | 1000000, 5000000 | Per-factor and cumulative materialized table caps |
| `absoluteGap`, `relativeGap` | 0, 0.01 | Either attained tolerance stops refinement |

Limits cannot exceed Global's existing 10000000/50000000 production ceilings.
Time begins after the feasible Regional seed and original-model reevaluation;
report total planner invocation time separately. Initial seed construction and
canonical cost-surface construction retain their existing resource behavior.
Exact region solves check time at phase boundaries; `timeMillis` is not a hard
wall-clock deadline. For matched operation-budget experiments use a sufficiently
large time budget and identical rounds/widths/region-growth limits.

`mode=certify,refineBound=true` is the bound-only row. Structural, Random and full
Anytime use the same increasing bound/region schedules with their respective
policies. The GuidedFixed row retains the guided region schedule but sets
`refineBound=false`, isolating bound growth. Set both tolerances
to zero to measure the full trajectory. `mkl-exact` is the independent Global row.
Pass properties through `CAMPAIGN_COORDINATOR_JAVA_OPTS` for `run_LAN_docker.sh`.
Enable `-Dsysds.fedplanner.trace=true` / `SYSDS_FED_PLANNER_TRACE=1` to record
`DP-RegionalCertificate` checkpoints and a final certificate tied to the cost and
analysis fingerprints. No runtime execution is needed for these planning traces.

## Algorithm contract

1. Obtain and independently evaluate the existing feasible Regional assignment.
2. Run min-sum mini-bucket elimination over **all** global hard and cost factors,
   using the existing exact activation encoding (including its auxiliaries).
   Splitting a bucket relaxes agreement on its eliminated variable. Never fix
   outside-region decisions in this lower-bound calculation.
3. Publish `L=max(previous L, completed MBE bound)` and retain only feasible
   canonical-cost improvements in `U`. Nonnegative modeled costs permit the initial
   trivial lower bound zero even if certification reaches its resource limit.
4. Grow a cumulative decision region, prioritizing disagreements between relaxed
   mini-bucket choices. Condition decisions outside it on the incumbent; leave
   activation auxiliaries free. Solve that conditional problem exactly under the
   existing production resource ceilings, reevaluate the full original assignment,
   and accept a strict improvement. Previously included decisions can be revisited.
5. Stop at a requested gap, iteration/time budget, resource limit, or exact global
   completion. A completed region containing all original decisions reaches the
   Global endpoint. Incomplete/failed bound work never replaces a valid bound;
   malformed costs, model mismatches and violated invariants remain explicit errors.

The width cap counts the eliminated variable. A native input factor wider than the
chosen cap is retained intact subject to the cell ceiling. No factor is discarded.
Bound addition rounds downward; hard infeasibility is `+infinity`. Relative optimality
is reported only for positive `L`; zero-bound cases retain an absolute certificate.
Width growth uses a best-so-far envelope, **not** a claim of nested partition refinement.
Disagreement scores select regions; they are not additive explanations of `U-L`.
The time budget is checked within MBE and between exact solve phases. The existing
exact solver is not preemptible, so phase time can overrun the scheduling budget.

The generic direct-model entry evaluates the same original factors used by the
solver. The physical entry is a typed boundary accepting `ExactPhysicalModel` and
its owner-bound `PhysicalCostSurface`; it constructs both the original feasibility/
canonical evaluator and the equivalent auxiliary factor set itself. Exact activation
equivalence remains the cost builder's tested contract. No arbitrary evaluator and
unrelated auxiliary encoding can be supplied through the public package entry.
This construction, rather than an additional exact bootstrap solve, establishes
the initial upper bound when a zero budget stops before any lower-bound work.

## Certificate statement and proof obligations

Let `C` be the unchanged canonical encoded cost, `D` its finite privacy/authority
filtered feasible assignments and `C*=min_{a in D} C(a)`. Assume the existing
exact auxiliary encoding is equivalent to the original physical factors, all
factor costs are nonnegative, and the initial Regional assignment belongs to `D`.
Then every published checkpoint satisfies `L_k <= C* <= U_k`, with nondecreasing
`L_k`, nonincreasing `U_k` and therefore nonincreasing absolute gap.

The original-model evaluator establishes each accepted `U_k=C(a_k)` and checks
all hard constraints. Splitting `min_x(sum_j f_j)` into sums of independently
minimized groups can only decrease it; induction over elimination proves every
completed relaxed bound. Conservative downward addition prevents numerical
overstatement of the bound. Taking the maximum of valid lower bounds and retaining
only feasible improvements preserves the inequalities and monotonicity. If an
exact solve frees all original decisions and auxiliaries and completes, it uses
the same model as Global; the canonical-cost parity check permits `L=U=C*`.
Absolute subtraction and positive-L division round upward for conservative gap
reporting. This follows the repository's canonical binary64 objective convention;
it does not certify real execution time or an unmodeled real-valued objective.

There is no finite-budget convergence theorem: a factor cap, initial Regional
failure, deadline or region cap may prevent the endpoint. Region selection is a
heuristic. Its current score is normalized disagreement among deterministic
mini-bucket argmins in a reconstructed relaxed context, including possible tie
ambiguity; it is not the stronger full local merge-loss calculation. The latter
would be a separate future scoring ablation with its own evaluation budget.

## Acceptance and verification before performance claims

- Independent exhaustive small-model oracle: `L <= C* <= U` at every checkpoint.
- Monotone published bounds, globally feasible returned assignment, complete exact endpoint.
- Shared-producer cost-barrier fixture improves only after the coupled decisions enter the region.
- Zero costs/bounds, hard constraints, huge/small binary64 costs, resource exhaustion,
  malformed inputs, disconnected/isolated variables and deterministic repeated runs.
- Lower bound changes cannot alter the incumbent in certify-only mode.
- Production PRIVATE_AGGREGATE compiler fixture: canonical objective, privacy and emission
  receipts still agree; compare against the Global oracle when tractable.
- Fresh compilation/tests on so007; no reused stale classes. Performance experiments
  use only `run_LAN_docker.sh`, paired on the same immutable inputs and Docker conditions.

## Ablation rows

| Row | Bound | Region refinement | Purpose |
| --- | --- | --- | --- |
| Regional | off | existing single pass | incumbent and planning-cost baseline |
| Certify | fixed width | off | certificate cost, with identical initial/final assignment |
| Bound-only | increasing width | off | lower-bound strength without plan improvement |
| Structural | matched increasing width | cumulative structural order | expansion independent of diagnostic |
| Random | matched width schedule | seeded random cumulative order | selection control |
| GuidedFixed | fixed width | matched disagreement-guided regions | isolate bound refinement |
| Anytime | increasing width | disagreement-guided cumulative regions | combined method |
| Global | exact | all decisions | optimum/oracle or explicit resource failure |

Keep the factor model, initial assignment, width schedule, region-size schedule,
resource limits, requested tolerance, seeds and timing boundary matched where the
row does not deliberately vary that component. Record raw and envelope bounds.
Compare policies on equal expansion/operation budgets before wall-clock budgets.

### Hypotheses and analysis rules

- **Certificate overhead:** Certify must preserve Regional's assignment and cost.
  Measure its added planning time and how often it proves a 1% modeled gap before
  Global finishes or reaches its limit. A valid but weak bound is still a result;
  it is not evidence of an efficient certificate.
- **Bound strength:** Bound-only versus Certify isolates width growth at fixed U.
  Report raw bounds as well as the max-L envelope: envelope monotonicity is an
  algorithm invariant, not empirical evidence that each width run is stronger.
- **Region selection:** compare Structural, Random and Anytime at matched schedules.
  Compare actual modeled regret when Global is available, accepted improvements,
  certified gap and work consumed. GuidedFixed versus Anytime isolates the effect
  of stronger bounds on the same guided selection rule. Bound computation can
  change later region choices; retain the bound and incumbent-cost trajectory,
  not just its final endpoint.
- **Exact endpoint:** when all original decisions are free and the solve completes,
  compare canonical objective bits with Global. Equal-cost ties need not select
  the same assignment. Resource failures do not count as endpoint completion.

For the larger campaign, pair runs on the identical frozen input, source artifact,
cost fingerprint and initial Regional assignment. Balance or randomize variant
execution order; separate JVM startup and warmup from planner invocation time.
Use at least five repetitions per paired cell, retaining random-policy seeds.
Report per-workload paired differences and 95% bootstrap intervals; preserve
timeouts/resource failures with their denominators instead of averaging only
successful runs. A one-repetition pilot does not support uncertainty estimates.

Primary figures are L/U trajectories against elapsed planning time and cumulative
solver work, time to a 1% certified gap, and actual regret versus certified gap
where the Global oracle completes. Keep cost-model error and execution runtime
outside these planning claims. If the pilot starts at the Global optimum, it
validates integration but cannot establish a region-selection advantage; include
coupled synthetic barriers and larger feasible workload cells in the campaign.

First run exhaustive synthetic graphs and a small PRIVATE_AGGREGATE compiler pilot.
Then extend Docker planning cells to KMeans, PCA, LM, L2SVM, LogReg, ALS and GLM;
worker counts 1/3/5/7 and existing LAN/WAN settings are the larger campaign, with at
least five paired repetitions and several fixed random seeds. Preserve failures in
the denominator. Prior known LM/KMeans assertion failures and PRIVATE_AGGREGATE
StepLM infeasibility are not silently relabeled as feature regressions or successes.

Per checkpoint: iteration, elapsed/bound/region time, raw/published L, U, absolute
gap, positive-L relative gap, width, region size, split/disagreement counts, factor
cells, improvement and stop reason. Per instance: baseline cost, Global optimum
when available, actual modeled regret, certificate slack and cost fingerprint.
Save commands, source/artifact hashes, environment, raw traces and machine-readable
results. Small pilots establish correctness and feasibility, not paper-scale speedups.

Bound materialized cells and evaluated assignments describe the last completed
MBE computation; sum them only on `BOUND` checkpoints, not again on `REGION` rows.
`boundNanos`/`regionNanos` are phase work, while `elapsedNanos` includes scheduling
and observer overhead. A failed or cancelled bound attempt has `rawLower=NaN`
and preserves the previously published lower bound; it is not a new raw estimate.
Region assignments count the reduced exact solver's elimination assignments,
not the work of input freezing, quotient construction or canonical reevaluation.

## Mathematical references

The min-sum construction and bounded inference are described by
[Dechter and Rish (JACM 2003)](https://ics.uci.edu/~csp/r62.pdf).
Partition comparability and monotone coarsening are treated by
[Rollon, Larrosa and Dechter (IJCAI 2013)](https://www.ijcai.org/Proceedings/13/Papers/102.pdf).
Independent width runs can use incomparable partitions; the published max-bound
envelope is therefore the implementation's monotonicity guarantee.
