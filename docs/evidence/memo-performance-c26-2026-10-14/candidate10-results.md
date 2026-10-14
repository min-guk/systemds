# Candidate 10 results

Both total-planning time gates still fail. Unchanged official Docker workloads each ran once with the frozen candidate10 engine. No repeated-run or warmup claim is made.

| Workload | Total s | Facts s | Rules s | Model s | Optimization s | Projection s |
|---|---:|---:|---:|---:|---:|---:|
| logreg | 4.239108735 | 0.054410028 | 2.230555635 | 0.688045971 | 1.195370007 | 0.070727094 |
| glm | 7.962475052 | 0.052817556 | 3.431948353 | 1.968972878 | 2.352034219 | 0.156702046 |

The evaluator has 21 passing semantic/runtime/freeze gates and two failing <=3-second gates. Both optimizers remain OPTIMAL, with unchanged exact objective, native universe and construction semantics. LogReg output bytes match the published reference. Both CP/FED comparisons and runtime/privacy audits pass. GLM baseline had runtime lowering failure; candidate numeric validation is independent and no nonexistent baseline CSV equality is claimed.

Compared with C09, total LogReg decreases 13.23% and GLM increases 4.38%. Single-run differences cannot establish per-change causal timing effects. Sampled allocation falls to 1.847/4.101 GB (LogReg/GLM); process CPU is 23.71/36.49 seconds and peak RSS is 588,460/1,055,256 KiB. JFR reports no loss. The 12,622-file frozen engine matches both executions; owned containers were stopped.

Movement category reuse reduces contextual view builds to 19,744/17,023. Residual Rules and shape-context preparation are still large; direct private shape-key encoding and certified Rules-only geometry are separate experiments. Full native-cost leaf streams and binary64 primitive semantics remain unchanged.

Reproduce using `candidate10-logreg-command.txt`, `candidate10-glm-command.txt`, and `candidate10-evaluator-command.txt`. Exact evidence is in the corresponding summary, phase-hotspots, profile, proc-summary, freeze-verification and evaluation JSON files; artifact hashes are in `candidate10-artifact-sha256.txt`.
