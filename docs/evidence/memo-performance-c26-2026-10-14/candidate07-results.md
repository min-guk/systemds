# Candidate 07 one-shot Docker/JFR results

Both authorized workloads ran exactly once from the 12,584-file frozen engine. Both harness cases passed,
reported `OPTIMAL`, matched their exact objective references, matched CP output numerically, selected the
same native universe and supply count, and reported no privacy or runtime-audit violation. Both JFRs are
loss-free and both frozen-input comparisons are exact. The performance goal did not pass because neither
Memo planning total is at most 3 seconds.

| Workload | Planning | Facts | Rules | Model | Optimization | Projection | Goal |
|---|---:|---:|---:|---:|---:|---:|---|
| LogReg | 4.758684308s | 0.059180711s | 2.475849643s | 0.738357529s | 1.419082160s | 0.066214265s | FAIL |
| GLM | 11.047010574s | 0.059524964s | 5.657429277s | 2.106660781s | 3.132354578s | 0.091040974s | FAIL |

Against the one-shot Candidate 06 measurements, LogReg planning fell 10.18% and GLM planning fell 21.07%.
Against the primary published/baseline measurements, the reductions are 38.76% and 92.75%, respectively.
These are single JFR comparisons and are not repeat-run confidence intervals.

The exact LogReg objective remains
`1301.1526531142989790221751587629165844428591469750244868919253349304199218750`;
the exact GLM objective remains
`163.445631891948489909499669515659725158029935698777990182861685752868652343750000`.
LogReg FED output is byte-equal to its reference. GLM's baseline has no certified FED CSV, so the fixed
policy uses candidate CP/FED numeric equality; maximum absolute differences are `1.6653345369377348e-16`
for LogReg and `9.020562075079397e-17` for GLM.

Candidate 06 to 07 structural changes:

- LogReg nodes remain 37,499. Optimization falls 39.84%, while Rules rises 31.17%; total planning still
  falls by 0.539197427s.
- GLM nodes fall 685,615 -> 213,240 (-68.90%); optimization falls 44.40%, Rules falls 7.85%, and total
  planning falls by 2.949127137s.
- Native generation, cost projection, native rule/federated-owner/supply counts, exact objectives, action
  diagnostics, and runtime audit remain equal. `MemoNativeRequests.contextualViewBuilds` is a new telemetry
  field and is permitted by the evaluator's reference-subset construction gate.

| Workload | Sampled allocation | C06 delta | STW GC | Whole-JVM CPU observed | Max HWM |
|---|---:|---:|---:|---:|---:|
| LogReg | 2,874,787,168 B | -6.56% | 0.295571s | 24.77s | 950,776 KiB |
| GLM | 7,970,513,816 B | -20.25% | 0.745903s | 43.00s | 1,596,320 KiB |

The evaluator fails only the two <=3-second gates. Evidence is in `candidate07-evaluation.json`, incremental
and primary comparisons in `candidate07-comparison.json`, and run/frozen-input verification in
`candidate07-verification.json`.
