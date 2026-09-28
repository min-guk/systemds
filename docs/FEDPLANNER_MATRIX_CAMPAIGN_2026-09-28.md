# Four-planner compile/runtime campaign — 2026-09-28

## Request and fixed scope

The implementation was committed and normally pushed to
`origin/refactor/w1357-paper-aligned-20260928` at
`5205946c768cc48627a227ab1f6cd07ef75b6024`; local and remote HEAD matched.

The canonical matrix is ML10 (`pca`, `als`, `kmeans`, `lm`, `logreg`, `l2svm`,
`steplm`, `glm`, `gnmf`, `gmm`), `P1_FULL`, `P2_PREP`, and SliceLine
`ADULT`/`COVTYPE`, crossed with workers 1/3/5/7, LAN/WAN-light/mid/heavy,
and DP/FedFirst/AggLocal/Exact: **896 conditions**. SliceLine's other two
datasets are not part of the existing canonical 14-workload input set.

## Execution/verification plan

1. Add an explicit repo-owned `run_LAN_docker.sh --campaign` lane without
   modifying the frozen P5 search-space-only harness or its paused goal.
2. Freeze engine/probe/dependency/image/input identities. Reuse sealed input
   files read-only, latest engine on both coordinator and workers, new Docker
   containers and coordinator JVM per attempt, and append-only attempt logs.
3. Run production `DMLScript.executeScript`, `-stats`, and
   `sysds.benchmark.compile_only=true`: parsing through runtime-program
   construction, **without executing that program**. A process exit alone is
   not success: require successful receipt, intended planner, full compile
   timing, and candidate timing receipt. Infrastructure/timeout failures stay
   failures, not infeasibility or zero-time observations.
4. Diagnose and repair DP first, then FedFirst, AggLocal, Exact. Preserve
   failed attempts, add regressions for engine fixes, freeze each new engine
   separately, and rerun before declaring its matrix successful.
5. Runtime is globally gated by all 896 compile conditions passing under the
   same engine. Run logreg's conditions first, then l2svm, then the rest.
   Preserve raw outputs and numerical validation evidence separately from
   runtime timing. No runtime fallback or silent privacy relaxation.

## Measurement contract

- Initial coverage pass: one fresh JVM/cell, no discarded warmup or best-run
  selection. This is a coverage/timing survey, not a statistically powered
  speedup claim. Record order and each attempt; failures have no successful
  timing sample. Later repetitions must be identified explicitly.
- Total compile is SystemDS's production compilation timer. Candidate E2E
  phases separately expose common search-space analysis, planner work,
  conversion/application/verification. Do not double-count nested compiler
  phases or call a candidate-only duration total compile. CSV also preserves
  `full_initial_planning_seconds` through production runtime-program construction;
  `selection_adapter_seconds` is the narrower planner-specific selection work.
- Detailed search-space counters require a separate metrics-on diagnostic;
  production compile remains metrics-off. Report `commonPreparationNanos`
  and `analysisNanos` separately and their sum as shared search-space time.
  The finer `analysisNanos` alone excludes final-HOP/common preparation.
- Use identical modeled network inputs and verified container netem profiles.
  Compile timings may include compiler metadata RPCs, not workload runtime.
- ML fixtures protect X but supervised labels Y are PUBLIC; this is not an
  all-PUBLIC workload or an all-private dataset. P2 retains the existing
  explicit metadata-only release, not row-level privacy release.
- Keep existing CPU/memory limits (8 cores, 24 GiB container, 16 GiB heap),
  finite disclosed timeouts, and fresh-worker lifecycle per cell. Do not
  increase engine candidate caps or prune supported combinations to pass.

## Evidence and resource isolation

Local evidence belongs under the already user-owned
`/grid/3/cofee-lm-sweep-mchoi-20260914/` (root filesystem had only 2.4 GiB free).
Remote coordinator has 178 GiB available; stage is
`/home/mchoi/w1357-stage-privacy-test-only-e21cac2-07726cd` on all eight hosts.
No Docker workloads were running at the initial inspection. Normalized image
content must match despite host-specific Docker IDs. Stage leases, the shared
physical lane lock, exact ownership-checked cleanup and per-attempt remote
directories prevent interfering with other work.

Results are pending; preparation/preflight is not a successful matrix run.

The first DP/logreg/W1/LAN pilot is diagnostic-only (a SIGQUIT thread snapshot
was requested after 142 seconds), not a clean performance sample. Its immutable
evidence is in `w1357-policy-matrix-20260928-pilot01` under the local root above.
At that snapshot, shared analysis had already finished: the main thread was in
DP's `ExactCategoricalSolver.materializeInputs` called through
`ExactPhysicalReducedSolver.reduce` and `LocalPhysicalOptimizer.optimize`.
This observation is not yet a completed timing result or proof of failure.

## Pilot outcomes (not successful timing samples)

- `pilot01`: DP/logreg/W1/LAN, rc124 at the fixed 900-second deadline;
  exact cleanup and stage-lease release succeeded.
- `pilot02`: after invariant realization-support preparation, the same cell
  exited rc137 after 237.811432 seconds. This was caused by the diagnostic
  SIGQUIT mistakenly sent to GNU timeout rather than its Java child: the
  wrapper forwarded QUIT, then killed Java after its 30-second grace period.
  Docker event timestamps confirm the chain; no OOM event was observed. The
  input-authority stack is diagnostic evidence only, not a completed timing
  or proof of an engine failure. The next frozen pilot must run signal-free.
- The protected full hard-factor truth digest stayed identical before/after
  the first optimization; 40 focused engine tests passed. Separate larger
  certificate/GLM resource failures remain disclosed in the session issues.
- Current coverage is zero successful compile cells and no runtime cells.
  Both pilots had diagnostic SIGQUIT samples and are excluded from timing
  comparisons. No resource cap or legal candidate was removed to pass them.

Independent lifecycle setup commands are now staged in parallel, strictly
outside the measured interval. Fresh containers/workers and one timed JVM at
a time remain unchanged. Pre-cleanup health evidence includes exact-ID OOM
events so that an exec-child kill is not confused with the idle coordinator
container's state. These changes require a new immutable campaign root.

## First signal-free production compilation

`w1357-policy-matrix-20260928-run01` uses candidate JAR
`2d9dd14bf7787db48f5f0f0469b8f0e299405ea86c76487ab6934c052b0e1d57`.
Both invariant-preparation fixes passed 46 exact-planner regressions; the
campaign/lifecycle/health/comparator regressions passed 66 tests, and package
completed successfully. Independent source and lifecycle reviews approved.

The first DP/logreg/W1/LAN condition completed successfully:

| Quantity | Seconds |
| --- | ---: |
| Production compilation | 810.656620 |
| Full initial planning through runtime-program construction | 810.654682 |
| Shared preparation + analysis | 26.888162 |
| Planner selection/adapter phases | 781.971015 |
| All candidate phases after shared analysis | 782.575079 |

The receipt proves zero workload runtime/executed Spark instructions,
198/198 physical HOPs lowered, no missing lowering or audit mismatch, and
resolved exact cleanup. This is **one successful condition, not a complete
matrix or proof of general scalability**. In particular optimizer work is
718.349780 seconds and cost-surface construction 58.052110 seconds; DP
remains expensive. The runner continues sequentially and retains the global
896-cell gate before any runtime. One read-only `ps` observation was recorded;
no signals, profiler, or diagnostic JVM instrumentation were used in run01.

## WAN-mid timeout diagnosis and successful rerun

Run01 completed LAN (810.656620 s) and WAN-light (792.211542 s), then stopped
at WAN-mid's natural 900-second timeout. Cleanup and lease release succeeded;
there were no OOM events. Its full compile gate remained false.

A separate 300-second startup-JFR diagnostic (`diag01`) identified repeated
receipt proof ownership scans, repeated target-only native-local cost work,
and per-cell fingerprint allocation. Diagnostic results cannot enter ordinary
timing CSVs or satisfy the runtime gate. The repairs preserve constructor and
public-API ownership checks, full candidates/factor cells, raw cost bits,
contribution fingerprints, limits and runtime rules:

- validated immutable receipt uses the existing owned-clause proof accessor;
- target-only cost preparation is memoized **after** structural preflight;
- dense fingerprint cells are streamed in the same row-major order with the
  same unsigned-hex bytes, without per-cell strings/byte arrays.

Fresh integration: Java 95/95, Python matrix 73/73, package success, and
independent receipt/cost reviews approved. Separate historical certificate,
L2SVM-plan expectation and forced-state fixture failures remain documented;
the latter two also reproduce on the original pilot01 engine. This is not a
claim that every repository test passes.

`run02` uses JAR
`2c1d3f0205d3f88d0a8b67fc919ff05d7f2d6b841d8a0bc083e35b05d566893e`.
The same DP/logreg/W1/WAN-mid condition, signal-free and with the same
900-second limit, **passed in 209.403378 s** (shared search-space 30.813578 s;
selection/adapter 176.812636 s). Audit/runtime-zero/cleanup checks passed.
The timeout is a censored baseline, not an exact measured speedup denominator.

This successful cell is retained in run02; the complete schedule then resumes
in the same root/engine, skipping that already-passed cell. Old run01 successes
are not counted toward the new engine's 896-condition gate. Full matrix and
runtime are still incomplete.

## Multi-worker status and next measured bottleneck

Run02 completed all four DP/logreg/W1 network cells (compile209–249s), then
stopped on DP/logreg/W3/LAN at the unchanged900s timeout. Current coverage is
**4/896 compile passed,1failed,891pending; runtime0/896**. There were no OOM
cgroup/Docker events; exact cleanup and stage lease release succeeded.

Separate `diag02` startup JFR (300s observation, not a normal result) showed a
new bottleneck: dense exact solve in DP's initial local hard-conflict repair,
not physical cost-factor construction. Of25,904 post-analysis main-thread
samples,86.033% were in dense solve. `preciseSum`/`DenseFactor.value`/rounded
comparison dominate, with extensive temporary `PreciseCost` allocation.
The full-stack offline report is under `diag02/jfr-analysis/`.

A bounded solver-kernel plan first locks the legacy raw arithmetic, selected
assignment, statistics and exceptions, then removes repeated allocation/index
work without changing cells, factor order, candidate order, caps or rounding.
Each source/JAR revision starts a new immutable matrix root. The previous
engine's four successes will not open the new engine's runtime gate.

The solver-kernel repair now passes the unchanged pre-edit arithmetic/result
fingerprints,35 focused tests after each of three stages, and166 combined
Java tests across22 classes. Matrix Python tests remain73/73. Independent
review approved the actual diff. These are correctness/integration results,
not yet evidence that the W3 production timeout is resolved. Run03 will first
retry W3/LAN signal-free under the unchanged900s limit, then continue the full
schedule only if that cell passes; runtime stays behind the full896-cell gate.

Run03's W3/LAN regression still hit900s (rc124/process902.363041s), so its
same-engine coverage is0 passed,1failed,895pending; runtime remains0.
The correctness-preserving kernel patch alone did not establish timeout
resolution. New diagnostic-only receipts now expose the existing four-order
portfolio metrics and selected first-block plan **before** execution. They do
not alter the comparator/order/costs. Java168/168 and Python74/74 passed,
with9 additional trace-environment tests and package/review success.
`diag03` combines these receipts with startup JFR; it is excluded from all
normal timing/gating. Full compile and ordered runtime are still incomplete.
