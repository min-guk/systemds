# Candidate03 measurement contract

Candidate03 receives one official Docker JFR run of LogReg only after its frozen engine is declared
ready. The primary reference remains `logreg-bottleneck-after-01`: planning 26.819446540 s and
optimization 23.714206977 s. Candidate02 is the incremental profile reference: planning
15.926899887 s, optimization 13.318014527 s, and 2,933,891 nodes.

The primary evaluator still requires planning strictly below 10 seconds, `OPTIMAL`, exact objective,
byte-identical output, FED fingerprint, action diagnostics, normalized runtime audit, Oracle trace,
native universe and requests, worker count, native-rule and supply counts, loss-free JFR, and exact
frozen inputs.

Search-node equality is not a gate for Candidate03. Its exact conditional-forest completion is
intended to change the search shape and reduce nodes. Node count and all search counters are recorded
against Candidate02, while semantic equality remains enforced by the primary gates. Candidate02
timing, CPU, memory, allocations, GC and hotspot samples are retained as the incremental comparison.

The run must contain exactly one line matching `[MemoForestTail] attempts=<long>
applicable=<long> completions=<long> cells=<long>`. All counters must be nonnegative,
`attempts >= applicable >= completions`, and all four counters must be positive to prove that this
workload exercised and completed the conditional-forest path. Trace validity and feature exercise
are differential evaluation gates.
