# Candidate-space failure transparency and paired planning

## Scope and invariants

The observed planner convergence is not itself proof of missing legal plans. The
confirmed defect is that unexpected rule/profile exceptions can be converted into
empty profiles, CP defaults, or omitted candidates. Fix that failure transparency
without changing privacy, capability rules, policy ranks, costs, runtime, or DML.
Do not reintroduce explicit binding variables or loosen feasibility constraints.

Base: `30333401460f6e4fb3b2eba91aa16906c577c804`.
Use the isolated `g014-candidate-failfast-systemds-20260908` worktree. The active
campaign's source, staged JAR, comparison metadata, runtime processes and servers
remain unchanged. Local diagnostics use CPUs outside workers' 0-7 allocation,
bounded memory and separate output paths; timings are not performance results.

## Test-first implementation sequence

1. Add synthetic injectable rule and PRIVATE_AGGREGATE/synthetic builder tests:
   throwing profiles/capabilities must fail with original cause and useful input
   context; JVM Errors must escape; ordinary unsupported/empty results remain
   ordinary unsupported/empty results. Run against the original implementation
   and preserve expected assertion failures before editing production code.
2. Replace only unexpected-exception fallback paths in RulesCore and
   NeutralPlacementGraphBuilder. Keep standard no-rule and capability exclusions.
3. Run focused regressions, privacy/placement coverage, compile and static diff
   checks. Review changes independently. Do not weaken existing test assertions.
4. Build an isolated JAR. Replay saved Docker planning-only commands with network
   disabled, worker control disabled, unchanged DML/data/config and isolated
   outputs. Start with StepLM WAN-Mid/w3 for all four selectors, then a differing
   workload control and the relevant LM case if resources permit.
5. Compare complete selected states and candidate evidence with the original
   archives. A new fail-fast exception is a defect witness requiring its cause
   to be fixed, not a reason to restore the fallback. An unchanged plan proves
   only that this replay did not expose that failure; it does not establish
   completeness relative to the entire federated runtime instruction set.

## Candidate completeness follow-up

For hot occurrences, separate oracle capability, privacy exclusions, global
consistency and selector-specific policy projection. Require a concrete runtime-
supported and privacy-safe alternative before declaring omission. Preserve raw
failure/candidate evidence and identify compiled-but-unexecuted functions.

StepLM's authenticated WAN-Mid/w3 output is a zero 2,100-coefficient model and its
runtime performs only 2,100 initial lmDS fits. No claim about iterative selection
or lmCG performance can be made from that cell. Do not alter this dataset to
manufacture planner separation; a representative nonempty-model fixture would
be a separately identified experiment.

## Evidence and risks

Artifacts: `/home/mchoi/g014-candidate-space-audit-20260908`.
Risk: previously hidden errors can now reject compilation. This is intentional
fail-fast behavior, not proof that the operation lacks physical alternatives.
Residual risk: silent non-exception rule incompleteness and runtime recompile
behavior are not solved by error propagation. Do not claim full-suite success,
full instruction coverage, or runtime improvement from planning-only replay.

## Evidence-driven extension after first replay

The initial diagnostic wrapper's exit code is not sufficient: Java caught and
printed application errors while returning zero. Require a complete physical
emission trace, candidate audit, compilation completion and zero runtime, with no
application-error marker. Preserve the original receipts and write a separate
validated comparison; never relabel exit status as compilation success.

The first repaired binary exposed two pre-existing causes:

1. `LeftIndexRule.profile` probes immutable `List.of(...)` with `contains(null)`.
   Test the exact observed six-input profile before replacing only that probe
   with null-safe iteration. Keep all supported local/FULL cases.
2. FED opcode canonicalization predicts a Spark-specific left-index method,
   initializes Spark configuration and can fail on the container hostname. Both
   opcode aliases use the same FED rule and runtime. Add classification/parity
   tests first; canonicalize the semantic HOP to LEFT_INDEX and delete the
   duplicated Spark prediction in the facade/logger. Do not change Spark lowering
   or runtime aliases. A paired explicit-hostname replay separately controls the
   environment, but is not the production repair.

These repairs address the revealed causes without restoring any silent fallback.
Replay the repaired binary against the same original network-isolated commands;
compare full candidate authority, not just CP/FED labels. A restored CP candidate
record can change authority without changing emitted instructions; report this
separately rather than implying a runtime improvement.
