# Further incremental Regional refinement

The successful v6 implementation and build-09 runtime remain immutable references. This is a bounded follow-on implementation and comparison, independent of the already-completed performance goal.

Changes to evaluate:
1. Maintain active boundary/internal decision counts at message ownership updates, avoiding repeated full active-scope traversal at every checkpoint. Preserve checkpoint fields and events.
2. Cache exact/lower message minima and avoid repeated immutable table scans without changing arithmetic, ties, backtrace, scope or resource admission.
3. If measurements justify it, reduce formatting overhead while retaining every checkpoint and round-trip numeric information.

Before editing, lock behavior with the existing prefix oracle tests and focused tests comparing incremental boundary counts with a reference scan. Parent owns the controller, integration, harness and build; core agent owns the boundary message class and its tests. No concurrent build or measured JVM work.

Evaluation command: python3 native/evaluate_refinement.py validation/latest-refinement-evaluation.json

The new measured matrix contains Global (new JAR), preserved v6 (frozen build-09 JAR) and the refinement (new JAR), three fresh JVM repetitions on L2SVM, StepLM and P1, worker5 WAN-mid, X PRIVATE_AGGREGATE/Y PUBLIC. All versions use identical inputs, cost-model environment, JVM options, CPU8-15 affinity, common preparation and fast-order settings. Global source must remain identical. Version contexts and runtime hashes are recorded per trial; all failed observations are retained. No Docker, worker JVM or workload execution.

Acceptance requires: valid complete27-trial audit, passing stable targeted build, all18 incremental observations certify <=5%, matching paired objective and certificate trajectory between v6 and refinement, unchanged Global/common source, each refinement planning median <=.90 Global median, no refinement workload median regression over5% versus v6, and geometric mean of three refinement/v6 planning median ratios <1. A passing result receives a separate unchanged-code confirmation. Do not pool campaigns or discard slower results. Failure or inconclusive wall-clock improvement is reported honestly and does not replace the preserved v6 reference.

The metric is whole planning time, including preparation, tracing and emission. Internal phase timing and elimination of repeated scans explain effects but do not substitute for whole planning improvement.
