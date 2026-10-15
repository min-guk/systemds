# Wave 1 benchmark driver review

Review target: `run_evaluation.py` and its compatibility with the frozen v8 baseline, the existing certificate/action auditor, and `summarize_evaluation.py`. This was a source-only review; no Maven or Docker command was run. Python AST parsing of the driver passed.

## Verdict

**APPROVE, subject to the completed matrix passing the final validator and provenance check.** The experiment matrix and seed configuration are suitable. The driver propagates failed jobs, the validation wrapper fails closed on execution and matrix completeness, and the paired summarizer now refuses incomplete or mismatched comparisons. Parent-owned final provenance validation will separately prove one frozen v8 before-engine, one v1 after-engine, distinct engine artifacts, and the recorded source hashes.

## Findings

### RESOLVED — failed Docker/runtime work is propagated and independently checked

The first review found that the driver recorded each subprocess return code but returned zero whenever a `summary.json` existed. The reused receipt auditor also recorded `returncode` without rejecting it and did not require `summary.complete == true`.

This matters most for the `runtime` suite: the benchmark harness deliberately sets `summary.complete` false when the synthetic comparison or a runtime row fails. Such a failure can therefore leave a readable summary while the outer driver and existing auditor both appear successful.

The driver now returns nonzero when any job does (`run_evaluation.py:60`), and `validate_evaluation.py:86-123` independently requires zero job/compile return codes and `summary.complete is true`. Runtime rows are also required and checked. This resolves the original silent-success path.

### RESOLVED — empty or incomplete runtime/synthetic evidence is rejected

`validate_evaluation.py:166-208` now requires nonempty runtime rows, positive integer runtime test counts, the exact 42 current synthetic case/algorithm rows, and nonempty legacy Regional evidence. Tests reject zero test counts, missing synthetic coverage, and empty Regional evidence. The expected 42-pair set matches the current harness schema: six cases across five core algorithms plus three search/no-seed algorithms on four eligible cases.

### RESOLVED — the memory matrix and heap identities are required

The reused auditor requires only `comparison`, `ablation`, and `runtime`, but the new wrapper adds `memory`, enforces two jobs and 12 rows, and requires unique 1,024 and 1,536 MiB receipt commands (`validate_evaluation.py:149-164`).

### RESOLVED — seed and message ablation labels are audited

`run_evaluation.py:33-35` correctly adds `--local-improve-seed false` to every job without an explicit setting. Consequently, comparison, runtime, memory, and pilot use the production `Options.defaults().improveSeed == false`; ablation preserves its explicit on/off variants. The existing auditor does not verify these receipt arguments.

The wrapper requires seed-off for comparison/runtime/memory/pilot, matches each ablation seed suffix to its command, and also matches `messages-on`/`messages-off` names to exactly one `--local-messages` value (`validate_evaluation.py:60-68,98-112`). Negative tests cover both kinds of label mismatch.

### RESOLVED — fixed-count suite repetition metadata is normalized

The driver now normalizes pilot, runtime, and memory to one before writing receipts (`run_evaluation.py:28-29`). The already completed pilot retains its historical `repetitions: 7` receipt; it is outside the final evaluation root and should be described as one pilot job. Runtime and memory launched later by the active command chain load the corrected driver.

### LOW — paired engines do not share an identical within-job order

The driver randomizes the before/after engine order and independently randomizes algorithm and workload order for each engine (`run_evaluation.py:69-79`). This removes a fixed ordering bias, but it is not a matched-order pair and can retain host drift or thermal noise. Seven cold-JVM repetitions reduce this risk. Timing claims should use paired workload/algorithm observations and retain min/max or dispersion rather than rely on a single median alone.

### RESOLVED — the paired summarizer requires validated, complete pairs

`summarize_efficiency.py:23-40` now runs the fail-closed validator first, rejects duplicate keys, and requires all 126 unique cells: seven repeats × two versions × three workloads × three algorithms. Its explicit `require` checks remain active under `python -O`, require a nonempty 64-hex source hash, and compare non-null final solver results, bounds, and routes (`summarize_efficiency.py:48-61`).

### RESOLVED — work-parity wording matches the checked counters

The two unavailable, previously vacuous direct-solve fields were removed. The summarizer now requires equality for six present Local structural counters and describes them precisely as selected structural counters. The independent certificate auditor verifies exact cost vectors and every reported LB/UB interval, including before/after exact parity.

## Compatibility and interpretation

- Frozen v8 and the new engine receive the same explicit seed-off option in the comparison. This is a fair production-default comparison. It is not directly comparable to the prior report's strong-seed main medians without stating the mode difference.
- The GNMF ablation is the only new strong-seed evidence. It does not support strong-seed performance claims for LogReg or GLM.
- Runtime and memory run only the new engine. They establish functional/runtime and constrained-heap evidence, not paired before/after speedups.
- `-Dsysds.fedplanner.memo.trace=true` is always set by the benchmark harness, so action and summary records remain available even when `--dual-diagnostics` is false. The existing action auditor can therefore enforce bound-before-seed ordering on comparison and memory runs.
- The existing summarizer's directory and run-name rules are compatible when the final root has `ROOT/<suite>/<run>/summary.json`. Keep pilot outside that final root; otherwise its nine compile rows will be included in report tables. A validator invoked directly on the pilot suite needs to normalize the shallower `ROOT/pilot/new-pilot/summary.json` layout.

The intended final matrix, excluding pilot, is 29 jobs and 165 compile rows:

| suite | jobs | compile rows |
|---|---:|---:|
| comparison, 7 repeats | 14 | 126 |
| GNMF ablation, 3 repeats | 12 | 24 |
| runtime | 1 | 3 |
| memory | 2 | 12 |
| **total** | **29** | **165** |

This corresponds to 104 dual certificates and 40 new-engine `MEMO_LOCAL` compile rows. Those counts are useful completeness checks, but certificate validity must continue to come from the independent bound auditor rather than from timing tables.

## Review verification

- `validate_evaluation_test.py`: 8 tests passed.
- The public validation composition was invoked read-only on the completed pilot artifacts: valid, one job, nine compile rows, six dual certificates, and zero errors.
- The driver parsed successfully with Python's AST parser.
- No Maven or Docker command was started by this review. The final approval remains conditional on the in-progress Docker matrix itself passing the wrapper and the parent-owned engine/source provenance check.
