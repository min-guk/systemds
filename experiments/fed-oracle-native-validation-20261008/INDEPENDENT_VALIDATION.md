# FED Oracle-Native Independent Validation

## Reference seam

The runtime reference is the frozen pre-change final-authority engine, not a new
production switch. It already contains the earlier CP/support factorization. Full-explicit
generation plus Closure is a separate unit-test reference using independently parsed HOPs. This avoids adding an API whose implementation could accidentally
share the candidate relation-native path. Every baseline run loads its classes, test
classes, and sources from the immutable staged engine. Every candidate run loads the
same paths from the candidate engine. The existing Docker class preflight proves which
bytes were mounted.

The paired harness records a complete SHA-256 tree manifest for both engines before
launching either one. A pair is accepted only when both existing canonical probes pass
and their objective bits, selected physical states, candidate selection signatures,
relocations, local materializations, and shared supply lifetimes agree. The workload
harness independently compares CP and FED model output.

For object-construction counts, the baseline uses a separate counter-only clone. Its
source differs from the immutable explicit baseline in exactly three files and only by
constructor counter increments plus `SEARCH_SPACE_OBJECT_CREATION` logging. The exact
88-line patch, source/class hashes, and a complete clone manifest live next to the staged
engines. Timings use that clone as well, so both sides execute the same opt-in counter
work. The original frozen baseline remains unchanged.

This is stronger than an in-process `disableFactorization` flag for the reference:
the baseline executable cannot call newly introduced relation code. Unit-level exhaustive
comparison disables relation generation before running the original Closure; it does not
only expand the newly published relation. Runtime equivalence uses two independently
frozen class trees.

## Paired protocol

For each workload and pair index, execution order alternates:

1. pair 0: baseline, candidate
2. pair 1: candidate, baseline
3. pair 2: baseline, candidate

Both variants use the existing joint-boundary harness, seed 7, Local planner, canonical
proof, four active processors, four Docker CPUs, an 8 GiB container limit, a 3 GiB
coordinator heap, three 1 GiB worker heaps, the same config writer, and the same pinned
container image. The container HOME and TMPDIR live under the `/evidence` mount so a
full host root filesystem cannot abort the run before the Java preflight. Ratios are
calculated within each pair before taking the median.

The monitor reads cgroup-v2 `memory.peak` through the running container PID whenever the
host exposes it. Docker stats remains a sampled fallback and is labelled accordingly.
`memory.peak` is preferred because it is a kernel high-water mark rather than a sparse
sample. JFR is opt-in per pair with `--jfr-pair`; profiled pairs must be reported
separately from timing pairs because allocation recording adds overhead. The JFR summary
reports sampled represented bytes by allocation event, allocated class, first FedPlanner
stack frame, class/frame pair, and planner phase. JFR allocation samples are estimates,
not exact heap-allocation totals.

Candidate legality is compared independently of storage representation. Explicit audit
rows and factorized input axes are expanded into a canonical inventory of occurrence,
input tuple, published node states, exclusions, capability, proof, and emissions. The
inventory hash and expanded row count must match. Selected receipt comparison remains
strict and includes structured source authority, relocation actions, proof dependencies,
input bindings, worker-pool witnesses, and support clauses. Only the indexed-storage flag
and indexed combination identifier are ignored. Cross-variant FED model CSV values must
match bit-for-bit, with a recorded `1e-12` maximum-absolute-difference fallback. The weighted
protected-row case additionally requires semantic DIRECT input 0 support from exact
`X_PROTECTED` and a non-null selected relocation action for both WSLOSS and WCEMM; the
support may use explicit or indexed storage.

The summary records constructor counters, `candidateOracleCalls`, and canonical model
counts such as `totalAlternativeCount`. Execution-relation region counts describe Oracle execution regions, not the count of
exact objects created. Candidate v2 includes suppressed FED tuples in its logical-tuple
counter; the older baseline/v1 counter omitted those tuples. Compare constructor counts
and legality inventory separately rather than treating region count as generation savings.

## Commands

After the candidate engine is frozen:

```bash
python3 experiments/fed-oracle-native-validation-20261008/run_paired_workloads.py \
  --repo "$PWD" \
  --baseline-engine /grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/evidence/baseline-engine-counter-overlay \
  --candidate-engine /grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/evidence/candidate-engine-final-v2-20261008T2205 \
  --dependencies /home/mchoi/w1357-stage-main276-20261008T1025Z/systemds/target/lib \
  --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/evidence/paired-logreg-glm-final-v2 \
  --stage-root /dev/shm/mchoi-fed-oracle-native-paired \
  --repetitions 3
```

Run allocation profiling as a separate paired campaign, for example with
`--jfr-pair 0`. The generated `fed.jfr` remains inside each run directory.
Use `--reprocess-only` to rebuild `paired-summary.json` from completed immutable runs
after comparator or reporting-only changes, without rerunning Docker.

## StepLM feasibility

The existing harness already defines `ml_steplm` and `ml_steplm_local_matrix`, emits
model and selection CSVs, and validates both outputs. The paired runner accepts either
case through repeated `--workload` flags. StepLM
is not included in the first LogReg/GLM gate because its function recompilation path
adds a distinct runtime boundary and should be reported as a separate coverage tier.
Before promoting it, run one candidate canonical proof and confirm that the candidate
audit contains a published relation; otherwise it only verifies fallback behavior.
