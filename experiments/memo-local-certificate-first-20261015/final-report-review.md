# Final report evidence review

Review date: 2026-10-15
Scope: final, complete evaluation matrix. The earlier `plan-assessment.md` remains a partial-data snapshot and is not used as final-result evidence.

## Code Review Summary

**Files Reviewed:** 16
**Total Issues:** 2

### By Severity

- CRITICAL: 0
- HIGH: 0
- MEDIUM: 1
- LOW: 1

### Evidence verified

- `evaluation/result-validation.json` is valid and reports 38 summaries, 3 independent EXACT baseline keys, 119 audited certificates, 4,515 audited LB/UB points including final bounds, 49 Local route checks, no unaudited certificates, and no errors.
- `evaluation/receipt-validation.json` is valid and matches all 38 requested jobs and all 195 requested compile rows. All 195 compile rows passed.
- `evaluation/report-data.json` contains 195 passed rows in 51 groups: comparison 126, ablation 24, runtime compile 3, memory 12, old-search 12, and seed-off 18. The 49 new Local rows all use `LOCAL_CERTIFICATE_FIRST`; 43 finish `EPSILON_OPTIMAL` and 6 message-off ablation rows finish `OPTIMAL`.
- The main comparison medians, ranges, full-compile times, container-memory medians, final costs, certified gaps, message counters, ablation numbers, seed-off numbers, old-search numbers, and one-run heap table in the report agree with `report-data.json` at the displayed precision.
- The reported LogReg, GLM, and GNMF certificates satisfy `LB <= EXACT OPT <= UB`. Their reported certified gaps and actual cost gaps recompute from the preserved decimals.
- The first-run LB/UB progression table agrees with the raw `MemoLocalAction` records. These intermediate records are in the per-cell coordinator logs; `report-data.json` intentionally retains only selected checkpoints plus the final message.
- The production typed option defaults to `improveSeed=false`. The main comparison actually ran with the strong seed enabled: the frozen `after-01` reproduction command contains `--local-improve-seed true`. The separate seed-off suite exercises the production-default choice and still constructs the mandatory first feasible seed. The report distinguishes these cases correctly.
- The source-fullcompile and runtime evidence are separate. The large 50,000-row LogReg/GLM/GNMF workloads validate planning and lowering, while the numerical runtime smoke uses small linear/logistic training inputs. No full GNMF numerical training run is present, and the report says so.
- Synthetic evidence contains 42 rows and a single equal-factor Regional adapter fixture. The report correctly avoids treating that fixture as a general Regional-versus-Memo ML comparison.
- Regression evidence matches the report: `test-summary.json` contains 117 classes and 775 tests with zero failures/errors/skips. Provenance and frozen-source counts agree with the report.

### Issues

[MEDIUM] The runtime sentence can be read as saying all 18 JUnit tests used real federated workers
File: `docs/COFEE_MEMO_LOCAL_CERTIFICATE_FIRST_REPORT_2026-10-15_KO.md:201`
Issue: The runtime command executes 18 test methods, but only 11 are worker-backed: 9 in `MemoPlannerFederatedRuntimeTest`, 1 in `MemoTreeDpTrainingRuntimeTest`, and 1 in `MemoSharedSupplyRuntimeTest`. The other 7 are `MemoMultiOutputBuiltinTest` compiler/binding tests and do not start federated workers. The current phrase, “실제 federated worker를 띄운 JUnit 18개,” overstates the worker-backed runtime count even though the following limitations about large training and GNMF are accurate.
Impact: A reader could interpret compiler/binding coverage as federated execution coverage, weakening the report's otherwise careful separation of source compilation and numerical runtime.
Fix: State that the Docker runtime suite passed 18 test methods, of which 11 are worker-backed and 7 are compiler/binding tests. Keep the existing statement that the training test performs 10 linear/logistic algorithm comparisons inside one worker-backed test method.

[LOW] The headline comparison with the old direct-exact-disabled Local omits that it was measured only on GNMF
File: `docs/COFEE_MEMO_LOCAL_CERTIFICATE_FIRST_REPORT_2026-10-15_KO.md:63`
Issue: “메시지를 켠 Local은 이전 Local의 direct-exact-disabled 탐색보다 크게 빨랐다” reads as a result across all three ML workloads. The old-search suite contains only six GNMF Local rows, three with seed off and three with seed on. Section 5.4 later gives the correct GNMF-only scope.
Impact: The executive result paragraph generalizes a workload-specific comparison beyond its evidence.
Fix: Prefix the sentence with “GNMF old-search ablation에서는” or otherwise state that this result is limited to GNMF.

### Scope and limitations confirmed

- Main performance results use seven fresh-JVM repetitions per comparison cell. Ablation uses three repetitions per setting; memory cells use one run and do not establish an OOM boundary.
- Container high-water memory is not solver-only RSS, and solver estimated peak is reported separately.
- The 195 rows are source-fullcompile attempts. They do not measure end-to-end large-dataset training throughput.
- The numerical runtime suite covers small two-worker linear/logistic training and targeted function/shared-supply behavior. It does not cover full GNMF training or the 50,000-row ML workloads.
- The Regional comparison is one synthetic equal-factor adapter case and cannot establish real-workload performance equivalence.
- The report does not claim that Local is faster than current EXACT on the three large ML workloads; the data show it is slower in all three.

### Recommendation

**REQUEST CHANGES** for the two scope-wording corrections above. The numerical tables, certificate claims, seed-on/default-seed distinction, compile/runtime distinction, and stated experimental limitations are otherwise supported by the final artifacts. No implementation defect or certificate inconsistency was found in this bounded report review.

## Resolution verification

Both findings were resolved and verified by text inspection:

- The section 5 headline now limits the old direct-exact-disabled comparison to GNMF.
- Section 5.6 now states that the 18-test Docker suite contains 11 worker-backed methods (Planner runtime 9, training 1, shared supply 1) and 7 multi-output compiler/binding methods. It separately describes the 10 numerical comparisons performed inside the training method.
- The README and correctness coverage table use the same 11/7 runtime split.

No new issue was introduced by these wording changes. The updated recommendation is **APPROVE**.
