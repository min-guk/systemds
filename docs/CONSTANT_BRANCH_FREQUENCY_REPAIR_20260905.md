# Constant-branch occurrence-frequency repair (2026-09-05)

## Problem definition

**Status:** resolved and targeted verification passed.

`OccurrenceExecutionFrequencyFacts` currently gives both arms of every `if` the
configured expectation (`0.5`). That is conservative for an unknown predicate,
but it overprices a body whose predicate is compile-time provable in the exact
occurrence and function-call context. The frozen GLM call with `dfam=2` and
`link=2` assigns `desired_eta=0`; consequently `if (desired_eta != 0)` and its
`straightenX` Gram work are dead, while the planner currently assigns the body a
positive frequency.

This is an execution-weight defect only. A zero-weight occurrence remains in the
candidate, privacy, legality, graph, selection, and emission domains. No runtime
fallback, opcode/workload discount, or candidate elimination is permitted.

## Constraints and acceptance criteria

- Specialize an arm only when its predicate is exactly provable from
  compiler-owned literal/scalar HOPs and occurrence-scoped reaching definitions.
- A proven true predicate gives the `if` body its parent's weight and the `else`
  body zero; proven false does the converse. Unknown retains `0.5 / 0.5`.
- Branch joins retain the pre-branch value for an arm that does not write a
  variable and retain all possible definitions for an unknown branch.
- Loop-written variables are unknown in the loop body and after the loop; never
  use a first-iteration value to specialize later iterations.
- Function calls use actual/formal bindings for that call context, respect local
  shadowing, keep unknown outputs unknown, and do not merge different callers
  into one literal context.
- Do not infer constants from runtime values or metadata.
- Execution weights are finite and nonnegative. Existing defaults and loop
  factors remain strictly positive.
- Preserve all existing candidate/privacy/legal/emission structure.

## Planned implementation

1. Add focused hermetic placement tests for literal true/false, unknown branches,
   nested joins, loop mutation, multiple function call contexts, and a minimal
   GLM-shaped `desired_eta`/`straightenX` plus live-CG fixture. Confirm the new
   assertions fail against the current `0.5 / 0.5` implementation.
2. Replace the builder's append-only transient-write table with a small
   conservative scalar environment used only by occurrence frequency analysis.
   Evaluate supported scalar boolean/numeric HOPs with cycle/depth guards and
   exact call-context bindings. Treat conflicting definitions, unsupported HOPs,
   loop-carried writes, and function outputs as unknown.
3. Allow zero only in occurrence execution profiles and execution-weight APIs;
   keep loop/default/forwarding preconditions strict where a positive factor is
   semantically required. Parent-owned cost-model acceptance of zero is recorded
   as a downstream integration requirement rather than changed here.
4. Run the single targeted Maven test class, inspect the diff, and document
   evidence, residual limits, and regression risks.

## Decision basis

Planner occurrence-frequency authority is the correct repair boundary: the
runtime already executes the fixed call correctly and oracle/candidate legality
is unrelated. The repair changes no runtime or planner candidate gate.

## Residual issues / downstream integration

- Parent-owned cost consumers that currently require strictly positive execution
  weights must accept finite nonnegative weights and multiply zero normally.
- Parent-owned Exact additive tracing should expose zero-valued selected
  contributions without removing their factors or occurrences.
- A post-repair review found a separate parent-owned integration risk in
  `FederatedCostModel.computeSingleWorkerFedExecPenalty`: clamping execution
  weight with `Math.max(1, execWeight)` can revive a dead function occurrence.
  This frequency repair does not modify that cost-model method.

## Implementation summary

- Branch predicates now use a bounded exact scalar evaluator. Supported numeric
  arithmetic, comparisons, and boolean operators follow runtime functions;
  strings, unsupported HOPs, conflicting definitions, and non-exact wide INT64
  values remain unknown.
- Scalar writes are frozen as typed literals in their definition-time lexical
  environment. Scalar call actual expressions are likewise frozen before entry
  into the callee, preventing later assignments or callee shadowing from changing
  their meaning.
- Unknown branch joins include the unwritten arm's prior definition, with an
  unknown arm absorbing rather than disappearing. Loop-written values (including
  function-call outputs) are unknown both within and after the loop.
- Function call context deduplication includes the caller context ordinal, so
  nested calls reached through distinct callers are not undercounted.
- Zero is accepted only as a finite execution weight. Loop/default factors remain
  strictly positive, and forwarding explicitly short-circuits a zero consumer
  before the legacy helper that interprets zero as a missing estimate.

## Potential regression risks and detection

- **Unsound branch exclusion:** detected by unknown, nested-join, loop-written,
  shadowing, and multiple-caller tests.
- **Accidental graph/candidate pruning:** detected by asserting dead-arm
  occurrences still exist and have profiles whose only change is weight zero.
- **Loss of established estimation:** detected by assertions that unknown arms
  remain `0.5` and live loop/CG work retains its positive established frequency.

## Verification

- RED baseline: the initial focused class failed six branch-frequency assertions
  under the unconditional `0.5 / 0.5` implementation.
- Integrated targeted Maven command:
  `mvn -DskipTests=false -Dtest=OccurrenceExecutionFrequencyFactsConstantBranchTest,ExactZeroFrequencyCostTest,ExactCanonicalCostTraceTest,PolicyFirstFeasiblePlacementSelectorTest test`
  compiled main/test sources; all 17 downstream zero-cost/trace/policy tests
  passed, with one subsequently corrected test-fixture epsilon mismatch.
- Final focused Maven command:
  `mvn -DskipTests=false -Dtest=org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFactsConstantBranchTest test`
  passed all 17 tests (`BUILD SUCCESS`, 44.555 s). This includes the actual
  builtin `glm.dml` proof: retained line-1065 `straightenX` Gram occurrences have
  zero frequency for literal `dfam=2/link=2`, while line-983 CG occurrences stay
  positive.

## Exact resource-profile validation

A paired standalone diagnostic uses the same compiled builtin-GLM fixture with
100 ms control latency against immutable `2d2f7f3` and the repaired classes.
The 1,976 original authority domains and the 2,127 hard-factor scope multiset
are identical; contribution/factor counts and arities are unchanged. Of the
cost-only auxiliary domains, 307 change from size two to one. Exact value-based
quotienting and elimination consequently move the maximum separator from 379,392
cells near `glm.dml:704` to 588,800 near line 738; total materialization changes
from 5,096,682 to 5,185,292 cells. The old 500k snapshot was a numerical-profile
observation, not an authority or decomposition invariant.

The regression therefore retains the unchanged production 10M per-factor / 50M
total limits, the intentional 6M GLM materialization target, canonical objective
raw-bit equality, and selected candidate/relocation projection checks. It logs
bounded resource statistics instead of replacing 500k with a new magic cap.
The diagnostic establishes structural equality and a changed numerical profile;
it does not export private per-variable quotient classes or attribute the new
separator to one isolated factor.

Evidence (outside the source repository):
`/home/mchoi/g014-runtime-4net-w1357-20260901-control/diagnostics/CANONICAL_GLM_FACTOR_PROFILE_DIAGNOSTIC_20260905.md`;
comparison JSON SHA-256
`dab36ec36b1e9d6b79efe516cf55063cee10e3e21036cb70d33e4b973c4c2f18`.

Fresh final integrated regression: 19 classes, 104 tests, zero failures/errors/
skips; all XML reports created after the command start. Receipt:
`INTEGRATED_FREQUENCY_FINAL_REGRESSION_20260905.json` in the same control directory.
These are hermetic correctness tests, not runtime performance measurements.

The independent high-control-latency run (`SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS=100`)
passed all 9 GLM/zero/trace tests; receipt
`ZERO_FREQUENCY_HIGH_CONTROL_FINAL_REGRESSION_20260905.json`.
