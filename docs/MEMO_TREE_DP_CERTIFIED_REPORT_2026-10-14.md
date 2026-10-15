# Memo Tree DP + Conflict Resolution: certified exact and 5% optimization

Base: `origin/main` **b8623e3481**, isolated worktree `/home/mchoi/cofee-tree-dp-20261014`, branch `feature/memo-tree-dp-certified-20261014`.

**Outcome:** independently selectable GLOBAL/LOCAL implementations are working and verified. GNMF certifies exact and 4.6073%-gap solutions; LogReg/GLM do not certify within the measured resource limits. Small tree-shaped problems benefit, but these real training-program measurements do not establish a planning-time advantage over existing Memo EXACT.

## Implementation and selection

Keep `sysds.federated.planner=COMPILE_MEMO`. Set JVM property:

```text
-Dsysds.fedplanner.memo.algorithm=EXACT           # unchanged default
-Dsysds.fedplanner.memo.algorithm=TREE_DP_GLOBAL  # new exact algorithm
-Dsysds.fedplanner.memo.algorithm=TREE_DP_LOCAL   # new certified 5% algorithm
```

`MemoTreeDpOptimizer` is an independent solver. It does not call `MemoOptimizer.optimize`, change the RuleMemo candidate universe, introduce a different cost model, or remove cross-subtree obligations. `MemoProgramModel.optimizeTreeDp` passes the same owner domains, coverage, native costs, checks and cost factors, then rechecks every constraint and re-evaluates the original cost before the existing supply reconstruction and transactional planner projection. The old solver has only read-only factor accessors and an additional result status; its search implementation is unchanged.

The certificate is exposed as `Solution.treeDpCertificate()`. Trace output (`-Dsysds.fedplanner.memo.trace=true`) includes `[MemoTreeDP]` status/counters and `[MemoTreeDPBound]` progression. `EPSILON_OPTIMAL` is distinct from `OPTIMAL`. Uncertified results are not installed as executable plans.

## State, relaxation and conflicts

A variable is an existing compiled HOP owner or an existing loop-boundary contract. Its state is its **actual rule ordinal**; rules with different legality, placement, cost or context are never merged merely because their output layout matches. Transparent/singleton owners remain fixed. Functions, branches, loops and reaching definitions remain represented by the existing model's factors.

After projecting globally singleton domains and factor-specific cost-constant axes, a spanning forest of unary/binary factors is retained. A bottom-up message stores the best subtree cost and reconstruction choice for **each root ordinal**. Several factors on one forest edge remain distinct terms, each evaluated once. A shared movement with several demands remains one original cost factor.

Factors of higher arity or factors closing a cycle become explicit deferred conflicts. Deferred legality checks are relaxed for the lower bound. Each deferred cost contributes its minimum over the current scoped domains, or its existing certified floor when that enumeration exceeds `factorCells`. Neither a minimum nor a floor duplicates a movement cost. A complete relaxed witness is checked against all original constraints. A violated legality factor, an UNKNOWN obligation, or a non-tight deferred cost identifies a variable to split. The split enumerates every ordinal still available to that variable.

Unchanged subtree messages are cached by oriented subtree identity and every descendant domain. The parent edge is outside the child's message. Changing one variable invalidates only messages whose signatures contain that domain. Cache eviction affects work, never correctness. Separate bounded caches reuse exact cost tuples and conditional minima. Existing certified cost classes identify equal values of a particular factor for every assignment of its other axes; they never merge native costs, legality, or rule identities. Conditional enumeration chooses one currently live representative per class. Cache keys contain the factor identity and every ordered scoped domain/class set; subtree keys retain actual rule domains. The total entry cap is shared across all three caches. Deferred minima are retained on their exact search node, and infeasible/UNKNOWN witnesses skip cost evaluation.

## Certificate proof

Write the original objective as the sum of native unary costs and the original, singly owned cost factors. For any node of the search frontier, a feasible completion satisfies all retained forest constraints. Therefore the forest DP optimum is no greater than the retained cost of that completion. Every deferred cost minimum/floor is no greater than that completion's corresponding term. Summing establishes a valid node lower bound, including when costs are negative.

The frontier initially covers every complete assignment. A conflict split partitions one node into all its possible ordinal values. Infeasible nodes are removed only with a retained constraint proof or a fixed original NO factor. UNKNOWN nodes stay represented by their lower bounds. A node whose validated witness attains its lower bound is solved. The global lower bound is the minimum over unresolved frontier/UNKNOWN nodes and the best validated incumbent. Thus **LB <= OPT <= UB** whenever a feasible incumbent exists; absent an incumbent, UB is conceptually positive infinity and the trace records `null`.

All finite binary64 cost primitives are converted with `new BigDecimal(double)` and summed exactly. GLOBAL reports OPTIMAL only when the bound proves equality (or all remaining regions have been excluded). LOCAL reports EPSILON_OPTIMAL only with **LB > 0 and UB <= 1.05 * LB**, using exact decimal multiplication. Hence UB <= 1.05 * OPT. For zero/negative LB it continues until an exact proof or an explicit failure. There is no time or fixed-iteration success rule.

A deferred cost's `certifiedMinimum` is a proof obligation of the existing CostTerm interface; this solver does not invent a zero floor for arbitrary cost terms. Open candidate coverage returns UNRESOLVED. Finite complete assignments with UNKNOWN constraints cannot supply an incumbent. Explicit search/frontier/depth exhaustion returns RESOURCE_EXHAUSTED and retains the current valid bound where available. The Docker watchdog is an emergency process interruption, counted as failed certification, never as Local success.

## Validation and measurement

Independent tests enumerate small complete domains without calling any optimizer to obtain the reference optimum. They check every reported lower bound and finite upper bound, actual Local regret, exact results, 5%/just-over-5% boundaries, zero/negative/dyadic costs, open coverage, UNKNOWN, infeasibility and resource failure. Parsed Memo-model tests cover shared producers/movements, functions, correlated branches and zero/one/multiple loop iterations. A separate runtime test executes linear and logistic gradient training on two federated workers and compares both weights against independently computed arithmetic for all three algorithm choices.

The benchmark entrypoint is `scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark`. All measured planner comparisons use the same Docker image, CPU/memory limits, model source and cost classes. Synthetic factors can be compared to legacy Regional only after translating the same domains/costs/constraints; actual legacy program optimizer costs are not presumed equivalent to Memo costs. Sealed ML **full compilation** is reported separately from small training **execution**. Estimated cost optimality is not a guarantee about measured training runtime.

### Resource configuration

Tree-specific JVM properties are `sysds.fedplanner.memo.tree.searchNodes`, `.factorCells`, `.cacheEntries` and `.frontierNodes`. Defaults are unlimited search nodes (`Long.MAX_VALUE`), 256 exact factor cells, 4,096 total cache entries and 10,000 frontier nodes. A factor-cell cap selects a sound floor, not candidate pruning or successful termination. Node/frontier caps return explicit RESOURCE_EXHAUSTED. EXACT ignores these Tree-specific settings.

### Initial real-workload finding

The first implementation was inefficient on the sealed W3 LogReg program: existing EXACT completed full compilation in 4.120s, while Tree Global reached the deterministic 2,000-node limit after 187.449s, with LB 2802.19086 and feasible UB 5675.65979 around the independently computed optimum 4041.33122. Its requested certificate was **not** established. A captured thread dump identified repeated shared-movement factor minimum evaluation. A lower factor-cell diagnostic reduced failed attempts to approximately 12–13s but retained a weak bound. These unsuccessful runs remain under `results-w3-r2` and `results-v1-factor64`; the interrupted Local attempt is explicitly marked uncertified. They motivated the final bounded cost caches/class-based minimum evaluation, not a change to the objective or stopping guarantee.

### Reproduce

From the isolated worktree:

```bash
MAVEN_OPTS='-XX:ActiveProcessorCount=4 -Xmx4g' mvn -q \
  -Dtest='MemoTreeDpOptimizerTest,MemoTreeDpModelTest,MemoTreeDpTrainingRuntimeTest,MemoOptimizer*Test,MemoExhaustiveParityTest,MemoControlFlowParityTest,FederatedPlanMemoTest' \
  -Dtest-forkCount=1 -Djacoco.skip=true test
python3 -m unittest discover -s scripts/fedplanner/tests -p test_run_memo_tree_dp_benchmark.py
python3 scripts/fedplanner/prepare_memo_tree_dp_engine.py --output /absolute/new/engine
scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark \
  --engine /absolute/new/engine \
  --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z \
  --output-root /absolute/new/results \
  --workers 3 --search-nodes 2000 --factor-cells 256 --runtime-smoke
```

The stage and its verified renderer/topology must be present; the runner fails on changed input/model artifacts. The final engine manifest is `experiments/memo-tree-dp-20261014/engine-v2-sha256.json` (SHA-256 `0f22679a103c2f671e6732d1ddd9fb72ff103a9c9ee8c1915b95ee870fa71fdf`, 8,842 binary/dependency files and 3,963 source files). Engine directories are intentionally ignored by Git; the preparer recreates them. Each result retains its exact Docker argv, source hash, rendered DML, configuration, stdout, bounds and counters.

### Final measurements

**Correctness:** 132 Java tests passed (zero failures/errors/skips); the Docker runtime suite passed 10 tests, including actual two-worker linear/logistic training under EXACT, GLOBAL and LOCAL. The independently calculated final weights matched in every mode. All 11 harness tests pass and cover exact decimal gap checking, failed certificates, progression intervals and output-path handling.

**Workloads/conditions:** sealed LogReg, GLM and GNMF training sources, each with 50,000×128 feature data and 3-worker partition metadata; private-aggregate feature inputs. Full compilation uses the same pinned image, 4 CPUs, 8GiB container limit, 3GiB Java heap, seed 1011081480, native BLAS disabled and no network. Each ML cell is one fresh-container measurement, so small timing differences are not statistically established. These are complete training-program compilation measurements; the runtime suite separately executes small training fixtures.

| Workload | Algorithm | Certificate | Full compile s | Container wall s | Sampled memory MiB | Final LB / UB |
|---|---|---|---:|---:|---:|---|
| logreg | EXACT | OPTIMAL | 3.624 | 4.899 | 330.3 | 4041.331 / 4041.331 |
| logreg | GLOBAL | RESOURCE_EXHAUSTED | — | 8.469 | 747.2 | 2742.536 / 5729.660 |
| logreg | LOCAL | RESOURCE_EXHAUSTED | — | 8.721 | 932.9 | 2742.536 / 5729.660 |
| glm | EXACT | OPTIMAL | 9.111 | 10.447 | 1008.0 | 426.223 / 426.223 |
| glm | GLOBAL | RESOURCE_EXHAUSTED | — | 20.045 | 1200.1 | 366.189 / 667.821 |
| glm | LOCAL | RESOURCE_EXHAUSTED | — | 25.598 | 1181.7 | 366.189 / 667.821 |
| gnmf | EXACT | OPTIMAL | 1.340 | 2.595 | 162.3 | 217.047 / 217.047 |
| gnmf | GLOBAL | OPTIMAL | 1.585 | 2.886 | 184.9 | 217.047 / 217.047 |
| gnmf | LOCAL | EPSILON_OPTIMAL | 1.513 | 2.805 | 134.5 | 217.047 / 227.047 |

GNMF GLOBAL matches independent EXACT at 217.0473253831. GNMF LOCAL certifies LB 217.0473253831 / UB 227.0473253831, a **4.6073%** gap (and equal measured regret against EXACT); it does not claim OPTIMAL. Its planner/optimizer phases were 0.662s/0.196s versus EXACT 0.623s/0.156s and GLOBAL 0.798s/0.221s. Thus these measurements do **not** show a real-workload planning speedup.

LogReg and GLM GLOBAL/LOCAL reached their deterministic 2,000-node resource cap without the requested certificate. Every retained interval and progression point contains the independently computed EXACT optimum. Their finite incumbents are feasible, but are not installed or labeled approximate successes. Failed runs have no successful full-compile/planner receipt; wall time and internal counters remain available.

| Workload / mode | Relaxation nodes | Conflict splits | DP s | Witness validation s | Bound progression points |
|---|---:|---:|---:|---:|---:|
| logreg / GLOBAL | 1996 | 245 | 2.510 | 1.338 | 126 |
| logreg / LOCAL | 1996 | 245 | 2.209 | 1.371 | 126 |
| glm / GLOBAL | 1985 | 232 | 6.435 | 4.781 | 150 |
| glm / LOCAL | 1985 | 232 | 8.823 | 6.870 | 150 |
| gnmf / GLOBAL | 38 | 6 | 0.143 | 0.012 | 8 |
| gnmf / LOCAL | 38 | 6 | 0.130 | 0.013 | 7 |

`dpNanos` includes retained-tree DP plus deferred minimum computation; `conflictNanos` times original witness validation, including exact cost evaluation for feasible witnesses. They exclude other solver/model overhead. Reuse is visible in the LogReg GLOBAL run: 625,929 subtree hits and 167,672 conditional-minimum hits. The complete bounds, counts, timings and memory samples are in [the machine-readable results](../experiments/memo-tree-dp-20261014/results-v2-w3-r2/summary.json).

**Small same-model synthetic cases:** means of 7 measured repetitions after warm-up, milliseconds:

| Case | EXACT | GLOBAL | LOCAL | Result |
|---|---:|---:|---:|---|
| adversarial-shared-wide | 1.629 | 1.902 | 1.313 | all exact |
| tree-32 | 1.925 | 1.031 | 0.837 | all exact |
| cycle-16 | 1.088 | 0.272 | 0.250 | all exact |
| diamond-8 | 0.444 | 0.243 | 0.242 | all exact |
| shared-wide-8 | 0.630 | 0.377 | 0.383 | all exact |
| epsilon-boundary-3 | 0.164 | 0.124 | 0.068 | LOCAL LB100 / UB105; GLOBAL optimum 100 |

GLOBAL is slower than EXACT on the six-owner adversarial shared-wide fixture (1.902 vs 1.629ms), despite fewer search nodes. On the 32-owner tree it uses one relaxation versus 64 EXACT nodes and is faster in this small benchmark. The 5% boundary example stops LOCAL at one node versus five GLOBAL nodes; exhaustive enumeration independently establishes optimum 100. Fixed execution order and JIT effects limit timing inference.

The equal-factor legacy component comparison returned the same 1.4375 objective for Memo EXACT, Tree GLOBAL, `ExactCategoricalSolver` and `LocalCategoricalOptimizer`. The last component visited 198 block assignments in 38.340ms (cold, separate timing). It does not execute the production legacy Local path `LocalPhysicalOptimizer → IncrementalRegionalOptimizer`, so it does not measure that path's certified 5% performance. Its nonnegative fixture is shifted by 0.25 from the signed synthetic fixture; this is an objective-equivalence check, not a speedup comparison with warmed runs. No equivalent real-workload Regional model has been established, so no real-workload Regional ranking is claimed. The [detailed comparison](COFEE_LEGACY_MEMO_TREE_DP_COMPARISON_2026-10-15_KO.md) distinguishes these paths and their resource-stop contracts.

Memory is the high-water of sampled Docker MemUsage, **not RSS**; it includes charged cache and JVM/model overhead and can miss brief peaks. Synthetic JVM pool peaks retain preceding algorithms' heap state, so they are not isolated per-solver allocation measurements. LogReg/GLM Tree attempts used more sampled container memory than EXACT. Raw per-process counters are preserved for reproduction.

The 2,000-node benchmark cap is an explicit experiment resource setting, not the production default and not a stopping guarantee. The larger-cap LogReg LOCAL diagnostic (`results-v2-logreg-20k`) permits 20,000 search nodes but reaches the 10,000 frontier cap after 15,958 nodes (max retained frontier 9,990). It returns RESOURCE_EXHAUSTED after 21.616s, LB 2765.57448 / UB 5325.65979, a 92.57% gap. DP takes 9.978s and witness validation 6.804s; sampled container usage is 775.3MiB. Increasing this resource budget improves the incumbent and lower bound but still does not establish 5%. An independent post-run audit checked all 1,387 final/progression intervals across seven Tree attempts, including monotonicity and containment of the same-source EXACT optimum; all passed. See [audit evidence](evidence/memo-tree-dp-20261014/independent-bound-audit.json).

## Limitations

This is a forest relaxation of the HOP-owner dependency factor graph, not a guarantee that every HOP DAG can be solved by linear-time tree DP. Wide shared costs and cross-subtree constraints can leave a weak bound and require exponential conflict search. Global singleton projection is supported; the implementation exploits certified cost equivalence only within cost factors; it does not use the old exact solver's truth equivalence, bucket elimination or all of its support propagation optimizations. It can therefore be slower than the existing Memo exact solver. Cache entry counts and frontier domain slots are structural memory counters; container memory measurements include JVM/model/runtime overhead.

The existing Memo candidate coverage, estimated loop/branch profile, shape support and runtime legality boundaries remain unchanged. Unsupported workloads must remain reported as failures. Finite search and frontier limits are resource failure controls, not an approximation guarantee.
