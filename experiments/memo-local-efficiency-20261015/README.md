# Certificate-first Local: incremental-work reuse

This follow-up starts from published commit `035f9c8eb76fc7cf793ffbde0127690ef131d16e` and its frozen `../memo-local-certificate-first-20261015/engine-v8`. It changes only Local seed indexing, root-bound reuse and immutable controller metadata. Global, default EXACT, original candidates, costs and legality retain their semantics. The new frozen source/binary manifest is `engine-v1-sha256.json`; binary directories are not committed.

Final results: [Korean technical report](../../docs/COFEE_MEMO_LOCAL_EFFICIENCY_REPORT_2026-10-15_KO.md), [integrated audit](evaluation/evaluation-validation.json), [paired timings](evaluation/paired-efficiency.md). All 165 compile cells, 104 certificates and 5,073 recorded bound points pass. Local median optimizer time falls 21.34% for LogReg and 47.80% for GLM, while GNMF is 0.79% slower; the latter is not claimed as an improvement. Docker JUnit 18 methods (11 worker-backed, including 10 small linear/logistic training cases, plus 7 compiler/binding methods) and 42 synthetic rows pass. `pilot/` is separate from the final `evaluation/` directory. Root-level preliminary analysis files describe the pilot and are not final verdicts.

Reproduce the implementation tests from this checkout:

```bash
mvn -q '-Dtest=*Memo*Test' -Dtest-forkCount=4 test
python3 -m unittest discover -s scripts/fedplanner/tests -p test_run_memo_tree_dp_benchmark.py
python3 -m unittest discover -s experiments/memo-local-certificate-first-20261015 -p analyze_local_results_test.py
python3 -m unittest discover -s experiments/memo-local-efficiency-20261015 -p validate_evaluation_test.py
python3 scripts/fedplanner/prepare_memo_tree_dp_engine.py --output /absolute/path/new-engine
```

The expanded suite passed 915 tests in 141 classes. Its first invocation had a stack overflow in a pre-existing deep source projection fixture; the frozen baseline reproduces recursive `MemoBindingResolver.resolve/resolveSelect` overflow. The dedicated baseline probe passes all six methods with `java -Xss4m`. The final Maven rerun passed with four forks and default stack. Its attempted command-line `argLine` override was superseded by the POM/JaCoCo configuration; the evidence does **not** claim that invocation changed the stack. If reproducing the deep stress fixture on a JVM where it overflows, `JAVA_TOOL_OPTIONS=-Xss4m mvn ...` applies the stack to forked JVMs too. This existing resolver limitation remains; no tests were disabled or production defaults changed.

Freeze an independent baseline build from commit `035f9c8eb7` in another worktree. Use a fresh output directory for each suite:

```bash
python3 experiments/memo-local-efficiency-20261015/run_evaluation.py \
  --suite comparison --repetitions 7 --engine /absolute/path/new-engine \
  --baseline-engine /absolute/path/before-engine --output /absolute/path/results/comparison
python3 experiments/memo-local-efficiency-20261015/run_evaluation.py \
  --suite ablation --repetitions 3 --engine /absolute/path/new-engine \
  --output /absolute/path/results/ablation
python3 experiments/memo-local-efficiency-20261015/run_evaluation.py \
  --suite runtime --engine /absolute/path/new-engine --output /absolute/path/results/runtime
python3 experiments/memo-local-efficiency-20261015/run_evaluation.py \
  --suite memory --engine /absolute/path/new-engine --output /absolute/path/results/memory
```

Every experiment calls only `scripts/fedplanner/run_LAN_docker.sh`. The driver requires the sealed stage `/home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z` and the pinned Docker image recorded in each manifest; neither external input is distributed here. Equivalent locations can be configured in the driver. Default runs use three workers, four CPUs, an 8 GiB container limit and a 3,072 MiB coordinator heap; the memory suite also tests 1,024/1,536 MiB. An external watchdog marks failure and never grants epsilon success. Replaying the evidence requires those external inputs; this is not a self-contained dataset package.

Comparison uses the production `improveSeed=false` setting for both versions, unlike the prior report's main strong-seed experiment. The GNMF ablation independently crosses message on/off and extra seed improvement on/off. Runtime and memory suites use only the new engine and do not establish paired speedups. Fixed-size suites record repetitions=1; the earlier pilot receipt retains its historical unused repetitions=7 metadata.

Seven repetitions randomize engine and algorithm/workload order with seed 20261015. Each compile cell uses a cold JVM, but before/after within-job order is not matched. Report paired ratios and ranges as descriptive observations; no statistical significance is implied. Container MemUsage includes charged cache and workers and is not JVM live heap or process RSS.

The independent final validation additionally rejects nonzero harness return codes, incomplete summaries, missing runtime/synthetic results and a missing memory suite. The original reused auditor alone does not enforce every one of these conditions. A successful outer process or timing summary is not sufficient evidence.

```bash
python3 experiments/memo-local-efficiency-20261015/validate_evaluation.py --root /absolute/path/results
python3 experiments/memo-local-efficiency-20261015/summarize_efficiency.py --root /absolute/path/results
```

Large LogReg/GLM/GNMF inputs measure full source compilation, not large-model numerical training. Actual federated numerical tests use small linear/logistic fixtures. Legacy Regional comparison is limited to the equivalent synthetic factor model; whole-program ML equivalence is not assumed.
