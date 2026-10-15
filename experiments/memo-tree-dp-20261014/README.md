# Memo Tree-DP benchmark artifacts

The `results/` child is the default output root for the certified Tree-DP
benchmark. The runner refuses to overwrite an existing result root; pass a
fresh `--output-root` for subsequent campaigns.

Build and freeze a fresh engine first (no existing artifact is overwritten):

```bash
MAVEN_OPTS='-XX:ActiveProcessorCount=4 -Xmx4g' mvn -q -DskipTests test-compile
python3 scripts/fedplanner/prepare_memo_tree_dp_engine.py \
  --output /absolute/path/to/frozen-engine
```

The preparer copies compiled production classes, test classes, and Maven's dependency
jars, and writes a complete source/binary SHA-256 manifest beside the engine. The
runner verifies this manifest, the pinned cost classes, sealed stage, renderer,
and byte-identical DML sources before execution.

Run all benchmark work through the Docker-only repository entrypoint:

```bash
scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark \
  --engine /absolute/path/to/frozen-engine \
  --sealed-stage /absolute/path/to/sealed-stage \
  --output-root /absolute/path/to/fresh-results \
  --workers 3 --search-nodes 2000
```

The default run first executes `MemoTreeDpBenchmark`, a same-model synthetic
comparison that records planning time, peak JVM pool memory, conflict overhead,
and bounds for Memo EXACT, TREE_DP_GLOBAL, and TREE_DP_LOCAL over tree, diamond,
cycle, shared wide-factor, and exact 5%-boundary cases of 3 to 32 owners. Cases
through 16 owners also use independent exhaustive enumeration. It then runs the three-worker
source-fullcompile matrix for LogReg, GLM, and GNMF. Every compile comparison
group uses identical DML bytes, image, CPU/memory limits, cost defaults, and
compile-only configuration. EXACT provides an independently executed optimum;
the summary checks every Tree-DP bound against it. The external watchdog records
a failed, uncertified attempt; it is never an optimizer stopping rule. Use
`--skip-synthetic` only for an engine without `test-classes/`.

The checked-in benchmark defaults are 20,000 search nodes, 10,000 frontier
nodes, 256 factor cells, and 4,096 cache entries. The command above uses a
2,000-node initial campaign cap. Hitting any deterministic cap is reported as
resource exhaustion with the last emitted bounds and fails the cell.

Pass `--runtime-smoke` only when the frozen engine also contains
`test-classes/`. That phase runs `MemoPlannerFederatedRuntimeTest` and
`MemoTreeDpTrainingRuntimeTest` in separate Docker cells with in-container
federated workers and is reported separately from compile-only planning
performance.

Legacy Regional is compared only in the explicit equal-factor synthetic adapter.
Its dyadic nonnegative fixture is shifted by 0.25 from the signed-cost synthetic
fixture and has a separate cold timing; those times are not used for speedup claims. Running the same DML through `COMPILE_EXACT` alone does not establish
mathematical equivalence to the Memo candidate space and cost ownership model.
