# Certificate-first Memo Local evidence

Implementation/report: [Korean technical report](../../docs/COFEE_MEMO_LOCAL_CERTIFICATE_FIRST_REPORT_2026-10-15_KO.md).

Final result: 195/195 compile attempts passed; independent audit checked 119 certificates and 4,515 bound points. All 49 new Local compile rows used the certificate-first route without direct exact. The 18-test Docker suite (11 worker-backed methods and 7 compiler/binding methods) and 42 synthetic rows passed. Local is still slower than direct EXACT on the three measured ML sources; see the report for default seed-off and explicit seed-improvement comparisons.

- `evaluation/` is the final engine-v8 experiment matrix. Audit this subtree for final results.
- `pilot/`, `pilot-v5/`, `pilot-v6/`, `pilot-v7/` preserve development runs. The first two contain explicitly interrupted runs; v5/v6 contain model certificates followed by failed physical lowering. These are not successful planning results. Root-level preliminary aggregate/validation files include those failures and are not final evaluation verdicts.
- `engine-v8-sha256.json` identifies the final source and binaries; frozen binary directories are ignored by Git. `provenance-validation.json` also compares the baseline source against commit `5734de2021f2841d82539063a6b450db69b9f7cb`.
- `test-summary.json`, `test-results/`, `correctness-coverage.md`, and `final-review.md` record correctness evidence and its limits. Development test logs may contain failures that were subsequently corrected; the summary identifies the final per-class results.

Build and freeze from a checkout containing the implementation:

```bash
mvn -q clean '-Dtest=*Memo*Test' test
python3 -m unittest discover -s scripts/fedplanner/tests -p test_run_memo_tree_dp_benchmark.py
python3 -m unittest discover -s experiments/memo-local-certificate-first-20261015 -p analyze_local_results_test.py
python3 scripts/fedplanner/prepare_memo_tree_dp_engine.py --output /absolute/path/new-engine
```

Use a new output directory for each suite. The driver calls only `scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark`. It needs the sealed stage and pinned Docker image recorded in each job manifest, and an independently frozen baseline engine for comparisons. Those external inputs are not bundled here; the experiment is replayable with them, not a self-contained dataset distribution.

```bash
python3 experiments/memo-local-certificate-first-20261015/run_evaluation.py \
  --suite comparison --engine /absolute/path/new-engine \
  --baseline-engine /absolute/path/baseline-engine --repetitions 7 \
  --output /absolute/path/new-evaluation/comparison
```

Run `ablation` with 3 repetitions, `runtime` with 1, `memory` with 1, `old-search` with 3, and `seed-off` with 3, with corresponding suite names under the same new evaluation root. `memory` performs both 1,024 and 1,536 MiB heap configurations; the main comparison uses 3,072 MiB. `runtime` also runs the seven-repeat synthetic comparison and small federated execution tests. The `old-search` suite uses the baseline engine with direct exact disabled. `seed-off` measures all three workloads with the production typed-option default (no extra seed improvement), separately from the main comparison's explicit seed-improvement experiment.

```bash
python3 experiments/memo-local-certificate-first-20261015/analyze_local_results.py \
  --root /absolute/path/new-evaluation
python3 experiments/memo-local-certificate-first-20261015/summarize_evaluation.py \
  --root /absolute/path/new-evaluation
```

Inspect `result-validation.json` and `receipt-validation.json`, including `failedCompileRows`; a completed driver invocation alone is not a successful certificate or runtime result. Timing tables are derived evidence, not certificate validators. Large LogReg/GLM/GNMF rows measure full source compilation, while numerical training checks execute small linear/logistic workloads on actual federated workers.
