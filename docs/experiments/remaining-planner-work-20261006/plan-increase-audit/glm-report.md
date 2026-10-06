# GLM W1 same-model plan audit

Confirmed search-quality miss: selected **46212.521643700398 ms**, complete feasible witness **21977.116964792665 ms** under the identical current pre-emission physical model and cost surface. Improvement: **24235.404678907733 ms (52.44%)**. All **3,458** hard factors pass. Cost contribution deltas sum to the objective delta within 1e-8 ms.

The witness restores historical coarse states at seven line983 occurrences, of which four differ from the selected plan. Every other physical decision is fixed to the selected assignment. This is a complete feasible assignment, not a single-root forced state or post-lowering recost. It is the first cheaper historical-coarse realization found; global or conditional optimality is not claimed. Historical objective 21,355.270631 ms is a different model/graph result and is not used as the same-model witness.

At `scripts/builtin/glm.dml:983`, `temp_CG = t(X) %*% (w * (X %*% ssX_p_CG))`, the witness changes inner matmul FED/FOUT/FULL to FED/LOUT/FULL, elementwise multiplication and transpose to CP/LOUT, and outer matmul FED/LOUT/BROADCAST to FED/LOUT/FULL. Storage/read boundary rules remain unchanged.

The production trace reports `RESOURCE_INITIAL`, merges=0, improvements=0. Incremental regional DP preflights the sum of dense cells of all reduced factors against 8,000,000 slots and returns the seed before constructing boundary leaves. Existing domain reduction does not make this complete initial dense cover fit. Private-variable projection happens after the admission check. A fix should change initial representation/reduction or supply a bounded improvement route; merely removing the memory check would make the actual dense allocation unsafe.

Artifacts: `results/B/glm_w1.log`, adjacent `.command.json`, `receipt.json`, isolated `src/.../LocalPhysicalOptimizer.java`. Executed through `run_LAN_docker.sh --function-boundary-compare` on the pinned Docker image. An earlier unrestricted seven-domain enumeration found cheaper leaves but was stopped to avoid unnecessary enumeration; its source/log are preserved in `exhaustive-partial.java` and `exhaustive-partial-results/`. Only the completed bounded audit above is final evidence.

Production sources, shared target classes and jar remain unchanged. These are modeled objectives, not measured runtime speedups.
