# Partial W3 campaign interruption

The first complete W3 `TREE_DP_GLOBAL` LogReg attempt reached the deterministic
2,000-node expansion guard after 187.449 seconds. It returned
`RESOURCE_EXHAUSTED`, with LB
`2802.1908635434509827651781123055742261929168535061762668192386627197265625000`
and UB
`5675.6597890673082538772606909734975977332993579693720676004886627197265625000`.
It is explicitly uncertified.

The following `TREE_DP_LOCAL` LogReg process was manually stopped before a
certificate or bound trace was emitted. Its status is
`MANUALLY_INTERRUPTED_UNCERTIFIED`; elapsed time is not an optimizer stopping
criterion and no approximation claim is made. The remainder of this campaign
was not started, to avoid repeating the diagnosed high-arity conditional
minimum cost for six more cells.

The captured Global thread dump is embedded in
`compile/logreg-w3-tree_dp_global/coordinator.log`. At 112.9 seconds, the main
thread had consumed 112.0 CPU seconds in
`MemoTreeDpOptimizer.conditionalMinimum -> enumerateMinimum -> evaluateTuple ->
MemoProgramModel.movementGroupCostExpanded`, establishing that root/deferred
movement-factor minimum evaluation dominated before ordinary conflict
resolution completed.

The earlier `results-w3/` directory is a harness setup failure: its synthetic
phase passed, but workload rendering called the sealed renderer module rather
than a stage-bound `Renderer`. The corrected raw campaign is this
`results-w3-r2/` directory.
