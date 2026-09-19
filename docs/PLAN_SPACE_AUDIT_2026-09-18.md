# Physical plan-space preservation audit — 2026-09-18

## Result

**Status: OPEN — inventory complete for the named surfaces, global proof incomplete.**

This audit records the current proof surface for preserving every legal physical plan. It does not certify an exhaustive branch inventory, full planner/runtime parity, or a global completeness theorem. The fresh regression evidence is useful bounded evidence, but it does not close the proof obligations listed below.

The machine-readable source of this document is under `build/plan-space-audit-20260918/inventory/`:

- `transformations.json`: 56 named generation, closure, action, representation, and policy transformations.
- `rule-families.json`: all 48 registered rule families and the still-open parity axes.
- `proof-obligations.json`: the unresolved proof obligations and their affected transformations.
- `validation-summary.json`: evidence counts, validation results, and the completion boundary.

## The set being preserved

Fix one compiled program context: occurrence, value version, control-flow position, function/call context, and recompile context. Fix the actual input `FederationMap` metadata: canonical worker endpoints, `FType`, partition ranges, shape facts, and privacy facts. The plan universe is the finite set of symbolic physical plans generated from those facts and supported runtime/materialization actions. It excludes arbitrary new workers, arbitrary runtime traces, and layouts that cannot be derived from the fixed program inputs.

For a program context `P`, let `S(P)` be the shared placement representation and `Decode(S(P))` its decoded physical-plan set. Completion requires both:

- completeness: `LegalPhysicalPlans(P) ⊆ Decode(S(P))`;
- soundness: `Decode(S(P)) ⊆ LegalPhysicalPlans(P)`.

A plan identity includes the placement state (`exec`, output, `FType`, shape dependence), emission execution `FType`, layout kind (`LOCAL`, `DURABLE_MAP`, or `NATIVE_LINEAGE`), the exact selected source realization for each input, relocation/materialization action with anchor, scope and obligation, function and transient relations, and all support/proof identity that affects executability. The core representation is visible in `PlacementState.java:34-45`, `PlacementAnalysis.java:299-337,434-450`, and `PlacementIdentity.java:404-438`.

`Decode` treats input bindings and proof dependencies inside one support clause as AND, and the support clauses of one realization as OR. It then enforces global source/action, transient, and function compatibility and requires native cycles to be grounded. A Cartesian product of per-consumer receipt domains is therefore only a search space; it is not the decoded legal set by itself.

## Transformation inventory

The classifications are descriptive. `mechanical` means the inspected operation preserves cardinality or canonical identity by construction. `partial` means bounded regression or a local invariant exists. `OPEN` means the required global preservation argument is absent.

Paths abbreviated below are relative to `src/main/java/org/apache/sysds/hops/fedplanner/placement/`: `B` = `NeutralPlacementGraphBuilder.java`, `C` = `CandidateSelections.java`, `A` = `PlacementAnalysis.java`, `I` = `PlacementIdentity.java`, `N` = `NativePlacementContinuity.java`, and `R` = `RelocationSelections.java`.

### Universe and candidate generation

| ID | Source | Transformation | Class | Status |
|---|---|---|---|---|
| U01 | B:276-317 | Build occurrence universe and refine CFG/abstract facts, falling back to the conservative CFG on non-convergence | generation | OPEN |
| U02 | B:7575-7633 | Expand function occurrences and bind call/input/output identities | generation | OPEN |
| U03 | B:7707-7723 | Derive canonical input `FType` domains per compiled occurrence | generation | OPEN |
| U04 | B:5020-5037 | Convert an empty input domain to a bottom node instead of fabricating CP/LOUT | filter | partial |
| U05 | B:5045-5065; rules/bridge/OracleFacade:130-141 | Enumerate the input-domain product and obtain oracle capability/shape evidence | generation | OPEN |
| U06 | B:5066-5099; fedCostBased/commons/ExecPlacementPolicy:358-370 | Refine right-index/vector-local output and add native plus forced-LOUT states | generation/filter | OPEN |
| U07 | B:5082-5086,5139-5140,7735-7737 | Enforce recompile CP/FOUT and TRead/TWrite placement boundaries | legality filter | partial |
| U08 | B:5141-5161,5205-5221 | Replace literal federated-source placeholders with their exact source state | canonicalization | partial |
| U09 | B:5998-6045; rules/bridge/OracleFacade:181-188 | Materialize candidate capability, proof, profile, and emission facts | representation | OPEN |
| U10 | B:5224-5241,5853-5856 | Apply exclusions and re-open only UNKNOWN_METADATA candidates with positive proof | legality merge | OPEN |
| U11 | B:923-1023 | Propagate fixed privacy and remove states/emissions forbidden by privacy | legality closure | OPEN |
| U12 | B:1026-1130; fedCostBased/commons/ExecPlacementPolicy:96-98,125-145 | Close protected payload and policy support across compiled inputs | legality closure | OPEN |
| U13 | B:1147-1157 | Require each derived-FOUT emission to retain its native source witness | source closure | partial |

### CFG, native, function, and publication closure

| ID | Source | Transformation | Class | Status |
|---|---|---|---|---|
| C01 | B:2376-2493 | Replay exact CFG transient candidate dependencies | closure | OPEN |
| C02 | B:2530-2707 | Rebind realizations and exact transient alternatives after CFG replay | closure | OPEN |
| C03 | N:187-246,263-323 | Prove candidate alternatives and retain only grounded native proof states | proof closure | OPEN |
| C04 | N:340-417,662-701,840-868 | Generate native proof alternatives and apply opcode/layout preservation rules | proof generation/filter | OPEN |
| C05 | B:2975-3018 | Reconstruct exact transient alternatives from reaching definitions | generation | OPEN |
| C06 | B:3020-3089 | Select and canonicalize transient seed layouts | canonicalization/filter | OPEN |
| C07 | B:3092-3110 | Merge replay alternatives with equal input state | merge | OPEN |
| C08 | B:3394-3548,3614-3715 | Rebuild post-CFG physical descendants and validate exact refinements | closure/filter | OPEN |
| C09 | B:3812-3936 | Close logical function-input candidates | function closure | OPEN |
| C10 | B:3964-4099,4611-4641 | Close function outputs and intersect exact value-boundary alternatives | function closure/filter | OPEN |
| C11 | LogicalBoundaryRealizations:127-169,173-234,246-271 | Encode and enforce function/transient boundary realization compatibility | relation closure | OPEN |
| C12 | B:4933-4955 | Reclassify standalone recompile occurrences | canonicalization | OPEN |
| C13 | B:643-687,692-842 | Iterate semantic and publication fixed points through replay, privacy, actions, and projection | phase composition | OPEN |
| C14 | B:5540-5579 | Bind exact candidate emission realizations to graph-owned authorities | authority binding | OPEN |
| C15 | B:5600-5638 | Bind exact logical-transient source states | authority binding | OPEN |
| C16 | B:839-854,5653-5719 | Canonicalize published graph-owned states and verify required emitted nodes remain executable | publication | OPEN |

### Materialization and relocation actions

| ID | Source | Transformation | Class | Status |
|---|---|---|---|---|
| A01 | B:5101-5125,5828-5846 | Derive CP/FOUT and FED/LOUT→FOUT actions from one exact materialization anchor | action generation | OPEN |
| A02 | B:6208-6248,6484-6623 | Derive relocation demand while skipping unsupported node CP/FOUT paths and requiring exact candidate input | action generation/filter | OPEN |
| A03 | B:6263-6320; QuaternaryWDivMMFEDInstruction:209-243 | Model forced-local WDivMM output and its partition-axis behavior | runtime boundary | partial |
| A04 | B:5271-5313,5780-5810 | Bind exact derived-FOUT action authorities | authority binding | OPEN |
| A05 | B:5317-5344,5722-5768 | Bind derived-FOUT physical realizations and pool identity | realization binding | OPEN |
| A06 | B:5351-5357 | Reuse equal relocation-action objects without changing list cardinality | canonicalization | mechanical |
| A07 | B:5361-5517,5526-5537 | Bind direct/relocation input support to candidate realizations | relation binding | OPEN |
| A08 | B:6671-6723 | Build relocation actions and exact obligations | action generation | OPEN |
| A09 | B:6726-6869; FEDRefedInstruction:81-143 | Check relocation/runtime anchor and worker-pool correspondence | runtime boundary | partial |
| A10 | B:7097-7154,7217-7255,7412-7492 | Resolve action alternatives, priorities, fallback paths, and cached indices | action selection | OPEN |
| A11 | R:237-271 | Form candidate relocation problems from action demands | action indexing | OPEN |
| A12 | R:1452-1515,1543-1598 | Select canonical relocation choices and validate selected actions | action selection/validation | OPEN |
| A13 | R:1615-1641 | Emit physical relocation actions from canonical choices | projection | OPEN |
| A14 | LocalMaterializationSelections:323-386,407-423 | Derive local materialization actions and origin-residency requirements | action generation | OPEN |

### Representation and planner policy

| ID | Source | Transformation | Class | Status |
|---|---|---|---|---|
| R01 | A:65-75,299-375 | Represent keys, clauses, bindings, witnesses, and exact realizations | representation | partial |
| R02 | A:503-529 | Merge equal realization keys by unioning all OR support clauses | lossless merge | partial |
| R03 | I:42-49,805-811 | Cache canonical signatures without changing semantic identity | representation cache | mechanical |
| R04 | A:700-734,736-794 | Store and canonicalize candidate rule/emission/receipt authority | representation | OPEN |
| R05 | C:993-1037,1664-1730,1774-1802 | Filter receipts by reachability, exact bindings, and derived-FOUT authority | legality filter | OPEN |
| R06 | C:1376-1445,1471-1487,1554-1585 | Test global realization compatibility and validate a complete receipt selection | global relation | OPEN |
| R07 | NeutralPlacementGraph:438-486 | Enumerate state assignments satisfying graph constraints | state decoder | partial |
| P01 | B:1326-1367 | Project constraint-supported policy states from the shared graph | policy projection | OPEN |
| P02 | C:1184-1204,1225-1272 | Build policy domains and rank PRESENT-input/materialization objectives | policy quotient | OPEN |
| P03 | C:1253-1268,1326-1352 | Deduplicate policy effects using a key that includes exact receipt identity | policy quotient | partial |
| P04 | C:2190-2229 | Split exact interaction components only across unshared factors | search decomposition | OPEN |
| P05 | C:1491-1545 | Select one materialization-maximal compatible receipt assignment | policy selection | OPEN |
| P06 | adapter/FedAllPlacementAdapter:59-96; adapter/HeuristicPlacementAdapter:57-80; selector/PolicyFirstFeasiblePlacementSelector:189-196 | Apply the policy projection in FedAll and Heuristic selection | planner policy | OPEN |

## Policy-boundary correction

The PRESENT-input objective, effect deduplication, and anchor-aligned tie-break in `CandidateSelections` originated for FedAll, but the projected graph and materialization-maximal selection are also used by the Heuristic adapter/selector path. They are therefore a FedAll/Heuristic policy quotient, not a shared legality transformation and not a representation of every legal plan.

No inspected path establishes that this quotient feeds back into the shared `PlacementAnalysis` universe. The stronger claim that it cannot re-enter DP through any current or future adapter path is still OPEN. Until that call-graph and authority boundary is proved, policy output must never be used as the expected full legal-plan set.

## Rule-family coverage matrix

`RulesCore.java:355-402` registers 48 families. Registration establishes only that the oracle can dispatch to a family. For every row, the required parity axes remain OPEN: opcode, input data types, shape class, input `FType`s, worker/partition condition, native execution, native output, forced-LOUT support, output layout transform, privacy class, recompile context, runtime entry point, expected rule, and positive/negative fixtures.

| # | Registered family | Registry line | Runtime parity | Fixtures |
|---:|---|---:|---|---|
| 1 | UnaryElemwiseRule | 355 | OPEN | unset |
| 2 | UnaryCumulativeRule | 356 | OPEN | unset |
| 3 | ReorgUnaryRule | 357 | OPEN | unset |
| 4 | ReshapeRule | 358 | OPEN | unset |
| 5 | ReblockRule | 359 | OPEN | unset |
| 6 | WeightedSquaredLossRule | 360 | OPEN | unset |
| 7 | WeightedCrossEntropyRule | 361 | OPEN | unset |
| 8 | WeightedSigmoidRule | 362 | OPEN | unset |
| 9 | WeightedUnaryMMRule | 363 | OPEN | unset |
| 10 | WeightedDivMMRule | 364 | OPEN | unset |
| 11 | AggTernaryRule | 365 | OPEN | unset |
| 12 | AggUnaryRule | 366 | OPEN | unset |
| 13 | AppendRule | 367 | OPEN | unset |
| 14 | FrameMapRule | 368 | OPEN | unset |
| 15 | TernaryElemwiseRule | 369 | OPEN | unset |
| 16 | NaryElemwiseRule | 370 | OPEN | unset |
| 17 | BinaryElemwiseRule | 371 | OPEN | unset |
| 18 | MMFedRule | 372 | OPEN | unset |
| 19 | BinaryMMRule | 373 | OPEN | unset |
| 20 | CastRule | 374 | OPEN | unset |
| 21 | UnaryCastToFrameRule | 375 | OPEN | unset |
| 22 | VariableWriteRule | 376 | OPEN | unset |
| 23 | CentralMomentRule | 377 | OPEN | unset |
| 24 | CovarianceRule | 378 | OPEN | unset |
| 25 | CtableRule | 379 | OPEN | unset |
| 26 | CumulativeOffsetRule | 380 | OPEN | unset |
| 27 | ContainsRule | 381 | OPEN | unset |
| 28 | ReplaceRule | 382 | OPEN | unset |
| 29 | RmemptyRule | 383 | OPEN | unset |
| 30 | RexpandRule | 384 | OPEN | unset |
| 31 | LeftIndexRule | 385 | OPEN | unset |
| 32 | MMChainRule | 386 | OPEN | unset |
| 33 | QuantileInterquantileCtableDenyRule | 387 | OPEN | unset |
| 34 | QuantileSortRule | 388 | OPEN | unset |
| 35 | QuantilePickRule | 389 | OPEN | unset |
| 36 | RightIndexRule | 390 | OPEN | unset |
| 37 | SolveRule | 391 | OPEN | unset |
| 38 | TransformEncodeRule | 392 | OPEN | unset |
| 39 | SpoofCellwiseRule | 393 | OPEN | unset |
| 40 | SpoofRowwiseRule | 394 | OPEN | unset |
| 41 | SpoofMultiAggregateRule | 395 | OPEN | unset |
| 42 | SpoofOuterProductRule | 396 | OPEN | unset |
| 43 | TsmmRule | 397 | OPEN | unset |
| 44 | TransientWriteRule | 398 | OPEN | unset |
| 45 | FunctionOutputRule | 399 | OPEN | unset |
| 46 | TransientReadRule | 400 | OPEN | unset |
| 47 | BuiltinMKMeansRule | 401 | OPEN | unset |
| 48 | FunctionCallRule | 402 | OPEN | unset |

Two runtime slices were read directly: `FEDRefedInstruction.java:81-143` for anchor/pool constraints and `QuaternaryWDivMMFEDInstruction.java:209-243` for local aggregation/forced-LOUT behavior. These checks support A09 and A03 only. They do not establish family-wide parity, including for `WeightedDivMMRule`.

## Current test evidence and independence limits

The fresh G003 evidence records a canonical 17-class run with 124 discovered, 114 executed-pass,
0 failures, 0 errors, and 10 PUBLIC-only skips. The separately run
`NativeLineagePlanSpaceCompletenessTest` records 4 discovered, 2 executed-pass, 0 failures, 0 errors,
and 2 PUBLIC-only skips. A fresh combined clean run also recorded 128 discovered, 116 executed-pass,
0 failures, 0 errors, and 12 skips. Source hashes were unchanged and `git diff --check` passed for that run.

These are bounded regressions. In particular, `NativeLineagePlanSpaceCompletenessTest`:

- takes its placement assignment from `FedAllPlacementAdapter` (`:73-77`), so it does not enumerate the full placement-state universe;
- constructs its raw receipt domain from production `AVAILABLE` facts (`:149-160`), so it cannot detect candidates omitted before publication;
- excludes derived-FOUT and logical-transient bindings (`:223-237`);
- restricts its native operation model to `*` (`:263-277`);
- uses production realization methods for native residency and exactness (`:280-293`);
- keeps the two PUBLIC comparison fixtures ignored under repository policy (`:50-53`, `:100-102`).

The test therefore checks a protected, selected-placement, higher-arity native-lineage slice. It is not an independent generator for all legal placements, actions, opcodes, privacy modes, or recompile contexts. The receipt, function-boundary, and LOCAL/DURABLE_MAP transient micro-oracles described in the session record have the same bounded-fixture limitation. Passing them cannot be promoted to a global proof.

## OPEN proof obligations

The machine-readable obligation list contains the exact affected transformation IDs. The highest-level gaps are:

1. Prove that the fixed finite universe includes every supported occurrence, value/control/function/recompile context and every actual-map-derived layout.
2. Prove termination and preservation across the semantic and publication fixed points, including their order and idempotence.
3. Prove global receipt selection against local SCC/native support; no clause may borrow only part of another OR alternative.
4. Cross-check every native opcode and all 48 rule-family axes against runtime behavior.
5. Resolve PART/OTHER and dynamic native seed completeness without inventing relocation support.
6. Prove all-reader/all-writer transient correlation, multiple call contexts/exits, and publication authority preservation.
7. Prove unique-anchor restrictions and multi-anchor/multi-row action generation are complete.
8. Prove action priority, fallback, and cached indexing preserve every legal action combination.
9. Prove the FedAll/Heuristic quotient cannot flow back into DP/shared legality.
10. Build an independent expected-universe generator and compare global decoded sets over all placement assignments, derived-FOUT, logical transient, native lineage, privacy/recompile, and general higher-arity AND/OR cases.
11. Complete an exhaustive control-flow branch inventory; the named inventory in this document is a structured starting set, not proof that every `continue`, `return`, filter, or exceptional path has been classified.
12. Provide a compositional soundness and completeness argument for every transformation above, then have it independently reviewed.

## Completion gate

This work can be marked complete only when all 48 rule rows have runtime parity evidence and positive/negative fixtures, every transformation has a discharged preservation argument, the independent generator reports `missing = 0` and `extra = 0` over the declared supported finite universe, policy non-reentry is proved, and every OPEN obligation is closed or explicitly moved outside the declared support contract with evidence.

Current completion status remains **OPEN / additional audit required**. The inventory is now reviewable and machine-readable; the global preservation claim is not proved.

## G001–G008 evidence refresh

The 2026-09-18 execution produced the following bounded results. They supersede earlier intermediate
counts where noted, but they do not change the global status.

- G001 preserved the starting HEAD, tracked diff, untracked source/test/document inventory, toolchain,
  process state, and source/evidence hashes under `build/plan-space-audit-20260918/baseline/` without
  resetting or stashing the working tree.
- G002 classified 12 PUBLIC-only privacy tests and applied only the repository-required ignores. The
  manifest and active protected coverage are in `privacy-policy/README.md`.
- G003 re-certified the current source. The clean combined run recorded 128 discovered and
  116 executed-pass with 0 failures, 0 errors, and 12 intended PUBLIC skips; the canonical split was
  124 discovered/114 executed-pass/10 skips plus the four-discovered/two-executed NativeLineage
  class with two skips. Evidence is in `fresh-regression/REPORT.md` and
  `G003-placement-recertification-20260918/REPORT.md`.
- G004 inventoried 56 named transformations, 21 open proof obligations, and 48 rule families whose
  universal runtime-parity rows remain OPEN. The inventory is under `inventory/`, with the readable
  transformation list in `G004_TRANSFORMATION_INVENTORY.md`.
- G005 added bounded independent generation and global-relation oracles. During verification it found
  a real aligned-binary completeness counterexample: the native proof required exactly one resident
  input and removed a legal aligned ROW/ROW or COL/COL plan. `NativePlacementContinuity.java:769-779`
  fixed that condition. `oracle-verification-v2/REPORT.md` records the counterexample and
  `oracle-verification-v3/REPORT.md` records the repaired 43-test result. This fixes the reproduced
  defect; it does not prove the theorem for every opcode or layout.
- G005/G008 also verified policy isolation for one protected two-row Exact domain. FedAll and Heuristic
  each selected one policy row without mutating the shared or Exact domain. Evidence is in
  `policy-quotient-isolation/REPORT.md`; architectural non-reentry remains unproved.
- The current protected `CandidateReceiptAssignmentCompletenessTest` passed all five tests. Its protected
  domains are nonempty, contain zero relocation bindings, and preserve independent raw/legal/production
  equality plus enumeration/action-order invariance. This fixture does not supply aligned-binary positive
  coverage; the Global and Native tests supply that separate bounded evidence.
- `ProductionDecodedPlanSpaceCompletenessTest` checks a protected compiled-function relation with
  `recompileOnce` enabled; it does not establish receipt coverage for recompile-context occurrences.
  `IndependentPlanSpaceGenerationCompletenessTest` provides separate bounded compile/recompile-occurrence
  evidence. ProductionDecoded asserts raw physical-domain cardinality 1344, then its declared binding
  projection decodes to eight literal plans with `missing = extra = empty` inside that projection.
  Excluded and out-of-domain receipts remain unclassified.
- The focused sequential suite recorded 72 discovered and 70 executed-pass with 0 failures, 0 errors,
  and two PUBLIC-only skips. The broad sequential suite then recorded 145 discovered and
  132 executed-pass across 22 separate Maven invocations with 0 failures, 0 errors, and
  13 documented PUBLIC-only skips. That broad report predates the final
  ProductionDecoded source and the CandidateReceipt privacy fixture, so it is pre-final evidence for
  both changed tests. Current-source supplemental
  verification recorded 14 discovered and 13 executed-pass with 0 failures/errors and one expected
  PUBLIC skip, with byte-identical
  before/after source hashes for the three oracle classes; it does not include CandidateReceipt. A separate
  current-source CandidateReceipt run passed 5 tests with no failures, errors, or skips. The reports are
  `g008-focused-verification/REPORT.md`, `g008-broad-sequential/REPORT.md`,
  `G005-final-dual-oracle-20260918T231255+0200/REPORT.md`, and
  `candidate-receipt-protected/REPORT.md`.
- The authoritative final current-source gate ran six classes sequentially and recorded 50 discovered,
  48 executed-pass, 0 failures, 0 errors, and two policy-required PUBLIC-only skips; all five protected CandidateReceipt
  tests were active. An initial PolicyQuotientIsolation command used the wrong package FQCN and is
  excluded from the result; the corrected fully qualified invocation passed. Evidence is
  `final-current-source-gate/REPORT.md`.
- G008 classified the 56 transformations as 2 `PROVED`, 20 `BOUNDED`, 34 `CONDITIONAL`, and
  0 current `COUNTEREXAMPLE`. The arguments and machine summary are
  `G008_TRANSFORMATION_PROOFS.md` and `g008/transformation-proof-summary.json`.
- G006 Docker/runtime qualification remains pending and is not evidence for this audit. G007 records
  the reviewed bounded result in this document and the session record.

The fixed aligned-binary counterexample and the still-unproved global equality are different claims.
There is no remaining reproduced counterexample in the audited fixtures, but 21 proof obligations and
all 48 universal rule-family parity rows remain open. The independent oracles cover declared finite
relations, not every placement assignment, action set, pool geometry, call site, privacy mode, or
runtime family. Therefore `Decode(S(P)) = LegalPhysicalPlans(P)` remains unproved.

## Reproducible evidence index

All paths are relative to `build/plan-space-audit-20260918/`.

| Evidence | Result and scope |
|---|---|
| `baseline/README.md` | G001 preserved starting state, hashes, diff, toolchain, and process inventory |
| `privacy-policy/README.md` | G002 PUBLIC-only classification; 12 intended ignores in the 128-test clean run |
| `G003-placement-recertification-20260918/REPORT.md` | 124-test canonical suite plus separate four-test NativeLineage run |
| `fresh-regression/REPORT.md` | Fresh combined 128 discovered, 116 executed-pass, 12 PUBLIC skips |
| `G004_TRANSFORMATION_INVENTORY.md` and `inventory/*.json` | 56 transformations, 21 open obligations, 48 open parity families |
| `oracle-verification-v2/REPORT.md` | Reproduced aligned-binary missing-plan counterexample |
| `aligned-binary-fix/native-placement-continuity-test.log` | Focused regression after the production repair |
| `oracle-verification-v3/REPORT.md` | Repaired bounded oracle suite: 43 discovered, 41 executed-pass, two PUBLIC skips |
| `policy-quotient-isolation/REPORT.md` | Protected two-row shared/Exact domain remains intact across policy selection |
| `g008-focused-verification/REPORT.md` | Sequential 72-test suite; pre-final for the later CandidateReceipt protected fixture |
| `production-decoded-function/maven.log` | Pre-final compiled-function projection run; current source is verified by the 14-test supplement |
| `g008-broad-sequential/REPORT.md` | Pre-final ProductionDecoded and CandidateReceipt privacy sources: 145 discovered, 132 executed-pass, 13 PUBLIC skips, 22 sequential invocations |
| `G005-final-dual-oracle-20260918T231255+0200/REPORT.md` | Current-source three-oracle supplement: 14 discovered, 13 executed-pass, one PUBLIC skip; CandidateReceipt not included |
| `candidate-receipt-protected/REPORT.md` | Current-source protected CandidateReceipt: 5 tests, nonempty independent equality, zero relocation bindings |
| `final-current-source-gate/REPORT.md` | Authoritative current-source gate: 6 classes, 50 discovered, 48 executed-pass, two PUBLIC-only skips; corrected FQCN invocation passed |
| `G008_TRANSFORMATION_PROOFS.md` | Per-transformation soundness/completeness arguments and remaining premises |
| `g008/transformation-proof-summary.json` | Machine verdict: 2 proved, 20 bounded, 34 conditional |

The completion gate remains unchanged: the audit stays **OPEN / additional audit required** until the
21 obligations and 48 family-parity rows are universally discharged, the remaining action/closure and
out-of-domain receipt gaps are resolved, and an independent full declared-universe comparison reports
both `missing = 0` and `extra = 0`.

## 2026-09-19 independent-review counterexamples

The G008 snapshot's `0 current COUNTEREXAMPLE` statement applied only to its audited fixtures.
Subsequent independent review reproduced three additional production defects:

1. a ROW `REV` dynamic native map could be republished by a following map-preserving unary operation
   as a stale exact `DURABLE_MAP` derived from the original source ranges;
2. ROW `REV` mutated the input `FederationMap` while constructing the output map;
3. ROW `ROLL` had runtime support for split and non-split ranges but was suppressed from native
   continuity candidates.

The current working tree fixes these cases by separating exact layout from endpoint residency,
propagating dynamic native-layout authority through DIRECT bindings, copying the REV output map before
range reversal, and representing ROW ROLL as dynamic native layout. The focused production-builder and
runtime gate recorded **37 discovered, 36 executed-pass, 0 failures/errors, and one required PUBLIC-only
skip**. This result resolves the three reproduced cases; it does not establish the global theorem.

An attempted combined broad run under `build/plan-space-audit-20260919/final-dynamic-layout/` is excluded
from acceptance evidence because another G009 compile/source edit overlapped it, source hashes changed,
and the shared target produced class-replacement/fork failures. This was subsequently superseded by the
stable-source regeneration and 74-test final gate recorded in the final follow-up below.

G009 also added a bounded action/anchor selection oracle. It accepted exactly four independently
declared complete choice sets over eight actions and its related 27-test selection gate passed without
skips. The production-builder diagnostic still exceeds its isolated 180-second limit in
`NativePlacementContinuity.candidateProofAlternatives`, so builder action generation and the global
closure claim remain open. The 21 proof obligations, 48 runtime-parity rows, and unfiltered
`missing = 0` / `extra = 0` completion gate remain unchanged.

### 2026-09-19 final dynamic-layout follow-up

Later independent review found and the current tree repaired three additional bounded counterexamples:

1. a dynamic ROW `REV` result could not feed a subsequent ROW `ROLL` directly;
2. runtime-supported single-worker FULL `ROLL` could not retain dynamic authority through `EXP`;
3. runtime-supported COL `ROLL` was suppressed from native continuity.

The current implementation separates endpoint residency from exact partition ranges, permits dynamic
range-recomputing chains only under their input-shape contract, and prevents dynamic FULL authority from
being generalized to exact single-partition consumers. Production builder/FedAll regressions cover
`REV -> ROLL -> EXP`, `FULL ROLL -> EXP`, and `COL ROLL -> EXP`; runtime tests cover ROW, valid
single-worker FULL, and COL map construction.

The final stable combined gate recorded **74 discovered / 70 executed-pass / 4 PUBLIC-only skips /
0 failures / 0 errors**, with identical source manifests before and after. The final branch inventory is
**5,437 sites in 503 methods**, classified as **5,173 CONDITIONAL / 264 BOUNDED / 0 universally proved**.
After the test-only `DynamicNativeLayoutCompositionTest.java` update, a broader 23-class current-source
integration gate recorded **132 discovered / 128 executed-pass / 4 PUBLIC-only skips / 0 failures /
0 errors**. Its Maven exit was `0`; all 23 Surefire XML files independently aggregate to the same totals,
and production/test source hashes plus branch hashes remained identical before and after. Production
source and the 5,437-row inventory did not change. Evidence is under
`build/plan-space-audit-20260919/g009-final-integration-current/`.
One later counterexample showed that transient replay retained only the first of multiple grounded
native-continuity proofs for the same source realization and seed. The replay boundary now maps the
complete, canonically sorted alternative list without adding a cap, suppression predicate, or fallback.
The current manifest was regenerated to **5,438 sites in 504 methods**, classified as
**5,174 CONDITIONAL / 264 BOUNDED / 0 universally proved**. The post-fix 23-class gate recorded
**133 discovered / 129 executed-pass / 4 PUBLIC-only skips / 0 failures / 0 errors**, with Maven exit
`0`, 23 independently aggregated Surefire XML files, and identical production/test source plus branch
hashes before and after. Evidence is under
`build/plan-space-audit-20260919/g009-final-integration-post-multiproof/`.
This closes the reproduced bounded counterexamples and current structural inventory only. The global
`Decode(S(P)) = LegalPhysicalPlans(P)` theorem, GLM builder timeout, 21 obligations, 48 runtime-parity
rows, and unfiltered missing/extra comparison remain OPEN.

### 2026-09-19 pre-push replay repair

Two final bounded regressions were repaired after the 133-test snapshot. Dynamic ROW REV authority now
survives TWrite/TRead replay with a typed endpoint witness and a DIRECT downstream consumer. The second
regression restored the legal receipt rows required by the decoded physical Cartesian cardinality of
1,344 instead of 525. The following post-index section records the corrected root cause: exact replay
had exposed lossy `NativePoolWitness` geometry as exact.

The pre-index inventory at this stage was **5,469 sites / 507 methods / 5,203 CONDITIONAL / 266 BOUNDED**.
The corresponding 23-class gate recorded **134 discovered / 130 executed-pass / 4 PUBLIC-only skips /
0 failures / 0 errors**, with Maven exit `0` and stable source/branch-manifest hashes. Universal G009
claims remain OPEN.

### 2026-09-19 post-index current-source update

The 1,344-to-525 decoder regression came from replay exposing the lossy
`NativePoolWitness.asAnchor()` reconstruction as exact geometry. That reconstruction canonicalized
worker endpoints, dropped path components, and rebuilt the orthogonal extent as one. Exact proofs now
retain the original exact seed geometry; only dynamic proofs carry endpoint-only residency witnesses.
The restored bounded factorization is **X=2, Y=2, U=4, V=4, D=21**, yielding 1,344 assignments.

`bindDirectNativeCandidateRealizations` now builds one immutable FType-to-durable-anchor index per
invocation and reuses the complete indexed seed lists. The immediate-source loop and final
`distinct().sorted()` relation remain unchanged; no cap, sampling, representative selection, fallback,
or proof filtering was added. This is a semantics-preserving bounded optimization. The isolated target
method nevertheless timed out with exit `124` after **180,770 ms** and produced no Surefire XML, so it
has no assertion result and remains an OPEN performance blocker. Evidence is in
`build/plan-space-audit-20260919/g009-upload-performance/REPORT.md`.

The current five-file structural inventory is **5,473 sites / 507 methods / 5,207 CONDITIONAL /
266 BOUNDED**. Current source hashes include `NativePlacementContinuity.java` = `220712b8...` and
`NeutralPlacementGraphBuilder.java` = `4e74a6ca...`. The serialized post-index 23-class gate recorded
**134 discovered / 130 active passes / 4 PUBLIC-only skips / 0 failures / 0 errors**, Maven exit `0`,
and stable source and branch-manifest hashes. PUBLIC skips are excluded from passes. Evidence is in
`build/plan-space-audit-20260919/g009-branch-proof-post-index/` and
`build/plan-space-audit-20260919/g009-final-integration-post-index/`.

The current architecture verdict is **APPROVE_FOR_BOUNDED_SCOPE**. The current code-review verdict is
**REQUEST CHANGES** because the upload fixture still times out, the branch inventory covers only five
core files, and Tarjan traversal remains recursive. Therefore no final approval or G009 completion is
claimed; universal plan preservation remains **OPEN / additional audit required**.
