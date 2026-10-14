# C26 movement-event key projection evidence

Status: isolated correctness validation PASS; not yet official runtime evidence. Base is frozen C24 (C25 was withdrawn after both official totals regressed).

Every movementGroupCost invocation owns a separate LinkedHashMap. Both insertion paths keep the original equality check against MovementGroup.identity and unretained groups insert no events. Therefore that identity is constant within each map. The clean key keeps target and exactly the original doubleToLongBits(unit), and removes now-unused identity parameters in two private helper calls. No map lifetime, candidate, context, callback, activation, union, primitive value, or addition order changes.

The test-only OFF uses the original full key record; ON uses a reduced record. OFF additionally constructs reduced buckets from the actual original event map, checks each original identity against the group, and verifies bucket order, unit bits, multiplicity and every activation object reference. The clean production candidate has no toggle or shadow state.

Literal old/new workload parity passed (12.539s). Checked LogReg2,984maps/1,458buckets/20,585activations; GLM7,055maps/6,749buckets/327,149activations. Full ordered RuleMemo/environments, all native raw costs and additional20,615/48,333primitive raw/exact values, OPTIMAL/exactobjective/fullselected/optimizer statistics/all Shapes statistics/supplies/diagnostics and selected cost agree. Counts are rewritten-fixture structural evidence, not official Docker performance.

Combined old Model+old scope validation OFF versus both new implementations ON passed (11.730s), `movement-key-combined-junit.log`. Final clean Model and Optimizer compiled together and passed57existing movement/residency/loop/cost regressions in11.306s, `movement-key-clean-regression-junit.log`. All earlier evidence and argv files are retained.

No independent movement-specific production test is added because existing business-level movement/regression coverage plus the stronger full differential check exercises this behavior; a reflective key-layout test would merely mirror the implementation. Scope validation adds its separate boundary test.

Remaining: independent final source review, integration/full selected suite/build/freeze/official once each. C24 JFR origin equality/hash counts cover other sites too and do not quantify savings from this change.

## Final source and evidence hashes

```text
629fae1d240d1680708262bfbbab70e49bb6113c1d645f57b85d9b4225cb1b10  movement-key-clean-src/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java
87c9836081a93d1f33e1956cad0c98f73475d109a6327b6f01805670495e5ebb  scope-validation-clean-src/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java
2a5faa13c4ea61a2e8e6bb2367ea8fa97f73a5728962419a95a06f5b0aa4009c  movement-key-parity-junit.log
3b734e1b9eda259b5f633d12c00f545249c276ea3aaff5632d01c1f90b732d3a  movement-key-combined-junit.log
b0dcc13b6766cd370bf2c1b47cacb838f295457c9432e2656ae766caff16eef5  movement-key-clean-regression-junit.log
```
