# v8 trace formatting refinement

This is item3 of the already frozen v7 refinement plan. Preserve v6/build09 and v7/build10 runtimes. The v7 cohort passed27-row correctness and complete trajectory parity but failed the performance acceptance on L2SVM.

Replace only incremental trace String.format allocations with locale-independent round-trip numeric rendering. Retain all checkpoint events and fields, diagnostics, timing boundaries, U/L values, resource counters, emission work, canonical validation, common preprocessing and Global code paths. No sampling or omitted logs, no separate exact restart.

Before build, validate the numeric/event schema against old formatting, including finite rounding edges, Infinity and zero, and run the targeted234-test set plus any new format test. Build11 and source manifest must be stable. Then use the unchanged run_refinement.py and evaluate_refinement.py for a new27-row cohort. The original Global10% perworkload gate, v6 regression guard, trajectory equality, and separate unchanged-code confirmation requirements remain unchanged. No pooling with old cohorts.
