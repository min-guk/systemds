# Weighted protected workload plan

## Harness inventory finding

`run_joint_boundary_e2e.py` currently supports ordinary L2SVM and joint
control-flow cases plus optional ML training cases (`ml_logreg`, `ml_l2svm`,
`ml_lm`, `ml_steplm`, and explicit-gradient variants). It has no direct
WSLOSS, WSIGMOID, WCEMM, or WDIVMM case.

Historical candidate audits and FED logs for `ml_logreg`, `ml_l2svm`, and
`ml_steplm_local_matrix` contain no weighted opcode or weighted FED heavy
hitter. These cases are therefore unsuitable for measuring the weighted rule
generation change.

## Proposed non-default case

Add one non-default `weighted_quaternary_with_private_aggregate` case to the existing
one-container harness:

- weighted operand `X_WEIGHTED`: public 8x3 federated fixture, with a matching
  CP reference;
- separate input `X`: existing 8x3 `private-aggregate` federated fixture,
  consumed by a real scalar aggregate in the same planned program;
- local public factor matrices `U` (8x2) and `V` (3x2), with fixed positive
  values generated as fixture CSV files;
- expressions adapted from the existing privacy/fedplanning regression
  fixtures:
  - `sum((X - U %*% t(V)) ^ 2)` for WSLOSS;
  - `sum(X * log(U %*% t(V)))` for WCEMM;

Initial Docker probes fed a single-range protected `X` directly to WSIGMOID and
then to WSLOSS/WCEMM. That physical layout is FULL, which the weighted rules
reject, and closure consequently found no privacy-safe placement.
The retained positive fixture keeps WSLOSS and WCEMM on the public federated
operand and preserves a separate nonpublic aggregate path. It exercises
weighted generation and privacy closure together without claiming that the
weighted kernels accept protected operands.

A separate bounded parity probe uses two private-aggregate ROW shards and feeds
them directly to WSLOSS and WCEMM. This protected ROW case succeeds on baseline
and optimized engines with identical semantic fingerprints and weighted
runtime/audit evidence. Its one run per engine is excluded from timing claims.

The case prints stable scalar fingerprints for every output, requires both a
runtime weighted heavy hitter and an AVAILABLE weighted candidate-audit row,
and supports canonical proof. It must
remain non-default so existing harness coverage and runtime do not change.

## Repeated comparison protocol

Run the baseline frozen engine and optimized frozen engine three times each,
alternating baseline and optimized runs when practical. Every run uses:

- `run_LAN_docker.sh --joint-boundary-e2e` only;
- planner `local`;
- the same Docker image, 4 CPU and 8 GiB container limits, 3 GiB JVM heap;
- `--canonical-proof --profile-jfr`;
- one weighted case per fresh run ID and stage root;
- the existing monitor and summary scripts for compile/planning time, candidate
  audit, exact objective/fingerprints, JFR heap, and sampled container memory.

Report all three observations plus median and range. Correctness gates are
matching CP/FED fingerprints, weighted opcode evidence, matching canonical and
certificate objective bits, and stable analysis/plan/cost fingerprints within
each engine. Internal oracle counters should come from the dedicated unit
counter test; runtime live metrics are not required.
