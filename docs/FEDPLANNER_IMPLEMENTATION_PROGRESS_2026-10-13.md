# FedPlanner Memo implementation work log

Base: origin/main `4314a345c16c20752421ce6251bbc2f366c85825` fetched 2026-10-13.
Branch: `codex/fedplanner-memo-20261013`.
Workspace: `/home/mchoi/fedplanner-memo-20261013`.
Authoritative specification: FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md, including ADR20 and ADR21.

## Authorized scope and deletion gate

User explicitly requested full implementation, legacy removal, tests, GLM and LogReg execution in a fresh origin/main workspace. Previous documentation-only restrictions do not apply to this new task. Existing source workspace and its dirty files remain untouched. At initialization no commit/push was requested; on 2026-10-14 the user explicitly requested committing this work and pushing it to origin/main. Runtime fallback is forbidden.

Latest publication validation: Memo component 266 tests and Docker harness 41 tests PASS. LogReg is OPTIMAL with numeric parity; GLM native coverage is repaired but global optimization still exceeds the 180-second Docker case limit. Publication does not mark GLM or the full legacy-deletion gate complete. See `docs/evidence/memo-publication-2026-10-14/README.md` and `docs/SESSION_ISSUES_2026-10-14.md`. Historical `experiments/` and design-audit snapshot links below refer to server-local evidence, not a promise that large artifacts are included in Git.

Implement in this order; do not mark a stage complete without evidence:

- [ ] M0: native/canonical and lowering catalog; record unsupported/unknown rather than invented native paths.
- [ ] M1: structured definition/use/guard/loop/call facts with compact identities; parity fixtures.
- [ ] M2: source-free immutable native RuleMemo, privacy and primitive movement policy.
- [ ] M3: reaching-definition-driven conditions, domains, reversible incremental propagation.
- [ ] M4: canonical upload and replica-aware collect; selected movement scope/lifetime.
- [ ] M5: single-owned native/shared transfer costs, closed-tree DP.
- [ ] M6: exact global search with complete shared boundary state and certified lower bounds.
- [ ] M7: selection validation, emission/recompilation, semantic/cost/numeric parity.
- [ ] M8: Docker GLM/LogReg runs; remove obsolete legacy production and adapt semantic tests after parity.

Legacy deletion is the final implementation step, not a prerequisite for producing a working replacement. A successful standalone component test does not authorize claiming end-to-end planner completion. Preserve old path during differential validation, then remove only the components fully replaced. Blocking runtime/coverage gaps must be reported explicitly.

## Current lanes

Parent: core Memo/rules/solver/integration and build coordination.
`memo_facts`: new MemoProgramFacts and focused tests.
`rep_runtime`: replica-aware Matrix/Frame collect and focused tests.
`native_catalog`: read-only Oracle/cost/emission/harness interface mapping.

## Validation

Compile and targeted semantic tests first. Legacy/new differential tests where support overlaps. Workloads only through scripts/fedplanner/run_LAN_docker.sh, with engine/config/data/worker identity recorded. Numeric/instruction/transfer evidence is required before legacy removal. No performance gain is assumed.

## Implementation checkpoint — 2026-10-13, compiler integration in progress

- Implemented compact structured `MemoProgramFacts`, lazy guard-correlated binding resolution, derived privacy, immutable native rules, canonical source validation, shape views, primitive movement policy, and an incremental exact solver. Rule choices remain shared by compiled owner; occurrence bindings remain distinct.
- Solver has reversible domains, dependency-indexed checks, bounded support/cache work, exact dyadic cost summation and closed-forest DP. The standalone oracle comparison covers 80 small exhaustive assignments with caches enabled/disabled. General residual search remains exponential; no planning speedup is claimed.
- Implemented anchor-free canonical upload, replica-aware Matrix/Frame collect, exact edge lowering, guarded supply, alias-safe DIRECT, and runtime loop contracts. Loop entry movement runs once; backedges validate direct compatibility and never repair layouts; final variables survive for normal exit supply.
- `supply-audit-loop.log`: 87 tests passed. Separate final loop optimization: 5 tests passed. `program-model-tests.log`: 12 tests passed before the latest compiler integration. These are component evidence, not whole-planner/GLM/LogReg acceptance.
- `COMPILE_MEMO`, transactional HOP projection, exact selected-plan registry, lowering and recompilation integration are now being connected and tested. Legacy paths remain available for differential checks.
- Remaining material gates include actual FED compiler execution, complete native workload coverage, control-flow/recompile parity, selected supply retention beyond one statement block, workload runs, and only then legacy removal. Existing same-statement sharing must not be presented as full cross-iteration/call materialization support.

## Integration checkpoint — loop phase and shared origin work

- Native/shape/definition analysis now distinguishes FIRST/MIDDLE/LAST and entry versus repeated shape; source identity has explicit function export chains. Latest Facts/Resolver isolated suite: 38/38; shape suite: 11/11.
- Exact solver conditional term lower bounds: 12/12 tests, including exhaustive cache/budget/backtracking parity. Wide terms remain certified relaxations; no speedup estimate is claimed.
- Runtime selected-action candidate dispatch: 26/26 store/lowering tests. Full compiler/workload integration remains open.
- Real Docker diagnostic02 CP LogReg and GLM produced nonzero models; Memo runs failed during shape/cost construction. Logs and frozen engine remain retained. Neither workload gate nor legacy deletion is complete.
- Current work: finish phase-aware workload native coverage and cost construction; connect source-origin sharing cost to emitted actions across statement/function/loop scopes; fresh central suite and Docker rerun.

- Integration-v3: full main and test compilation succeeded (43.335s). Central component and W2 runtime suites are running against that frozen compiled checkpoint. This is compilation evidence, not a workload or deletion gate.
- Phase correctness audit found/fixed: static-for MIDDLE cannot be inferred from iteration 2 when it contains several iterations; nested outer-loop guard proofs must remain visible inside inner loops. Facts suite now 22/22.
- Shared movement objective uses an explicit frozen guard-correlation estimate; see SESSION_ISSUES for exact modeled union versus unverified runtime probabilities.

## Integration checkpoint v4 — verified, workload gate pending

- `integration-v4-compile.log`: full production/test compile PASS (46.447s).
- `integration-v4-unit.log`: 179/179 PASS; Facts/Resolver, Privacy, Shapes, Rules, costs, solver, Model/control flow, compiler, function termination, ZERO/ONE/MANY loop runtime, supplies and canonical pool.
- `integration-v4-w2.log`: 5/5 actual two-worker runtime tests PASS, including same-layout branch payload identity and repeated function returns reused in A/B/A order.
- User approval supersedes T>1-only scope: ZERO/ONE paths are supported, including exact initial/final exits; taken backedges remain DIRECT-only. Unknown count cost estimates do not eliminate ZERO/ONE feasibility.
- After v4, joint contextual native input cost and safe privacy factoring are under isolated verification. LogReg catalog is complete, and GLM guard-30 FIRST shape is now derived from exact reaching definitions. Neither full ML workload has yet completed with the Memo planner.
- Current blocker: repeated conditional privacy evaluation at loop/control-flow boundaries. Source-domain scopes and per-slot loop invariants are being tightened; no fallback or candidate restriction is used to hide it. Legacy deletion remains pending actual workload parity.


## Integration v5 and current verification boundary

- Frozen v5: main/test compilation passed; component suite 187/187, actual W2 runtime 5/5, reused Oracle/cost/worker-privacy semantics 99/99 passed. These logs remain historical checkpoint evidence.
- Subsequent review rejected a generic lazy loop-privacy summary path because it could re-enter the same induction under another assumption map. It has been removed; recursion is guarded by loop and invocation identity. Therefore v5 alone does not validate the current tree.
- Positive-only `MemoResidency` now proves released local residency using a small dependency worklist. Unknown/protected facts defer to guarded privacy evaluation; no negative pruning follows from an absent positive proof. The native RuleMemo is never rebuilt.
- Cost bounds now update only terms incident to changed domains, use exact sum deltas, and fold proven singleton scopes once after initial propagation. The LogReg 100k-node diagnostic retains an incumbent but still returns RESOURCE_EXHAUSTED; it is not emitted or called optimal.
- Remaining immediate gates: GLM contextual shape closure, invocation-qualified privacy summaries, fresh central checkpoint, exact workload optimum and Docker numeric/emission parity. Legacy removal is still gated on those results and semantic test migration.


## Resume checkpoint — v6 verified, v7 compiling

- v6 central production/test compile PASS (41.818s); Memo component suite 217/217 PASS. W2 + reused privacy suite had one stale legacy source-scanner assertion; corrected scanner separately passes 18/18. Actual W2 suite 7/7 passed at that checkpoint.
- Root added actual W2 dynamic T=0/1/2/3 final-value tests plus selected entry UPLOAD skipped for ZERO and performed once for ONE/MANY. Taken backedge transfers remain zero.
- Privacy contracts are now qualified by exact call frame; ancestor loop contracts remain visible in nested callees but siblings cannot reuse one another's contracts. Context replacement preserves the qualified map.
- Original ungrouped selected-cost audit is now checked before emission. `MemoProgramModel.evaluateCost` splits native execution and movement and sums original binary64 primitives exactly. Solver grouping must preserve this objective.
- New W2 shared-supply fixture ties together one modeled UPLOAD cost, one selected retained action, two actual function demands (one creation + one reuse), exact numeric result, and zero live leases after exit. It exposed mutable compiler HOP memory annotations leaking into later cost views; all occurrence clones now use the frozen MemoShapes snapshot. Focused shared/shape 13/13 and model/optimizer/store 54/54 pass.
- Prior runtime lane added full transactional publication-failure rollback, guarded noncommutative input-position1 upload, inline DataGen actual marker ordering, and two-return function slot/guard origins. Latest isolated combined evidence: memo-two-return-candidates-final-isolated.log 46/46 PASS. Independent review and central v7 remain pending.
- **LogReg blocker:** 10m-node exact search still RESOURCE_EXHAUSTED, incumbent 2608.245790229737; never emitted as optimal. Identical-scope folding reduces work but the lower-bound gap (~1314) remains.
- **GLM blocker:** shape and bounded cost-pattern clone crashes fixed; 180s profile finds nativeCostTerms enumerating input/source/call-completion/guard products, retaining >352k unary terms. This violates the intended no-product cost construction and must be factorized before a workload completion claim.
- No successful Memo Docker ML run yet. No legacy core deletion. Original source workspace unchanged. New workers on resume: glm_native_cost (native cost projection), solver_gap (exact search), emission_review (read-only latest runtime audit); parent owns integration/Docker/deletion gates.

## Resume checkpoint — v7 verified, v8 changes in progress

- Fresh v7 full production/test compile PASS (40.922s); `integration-v7-unit.log` 222/222, `integration-v7-runtime-privacy.log` 28/28 (actual W2/runtime + ingress privacy), `integration-v7-reused-semantics.log` 256/256, `integration-v7-docker-harness.log` 39/39. These counts belong to the frozen v7 target, not uncompiled subsequent edits.
- Extracted 14 runtime privacy acquisition tests from the legacy CampaignBG011 suite into planner-neutral `FederatedPrivacyConstraintResolverTest`; direct typed API replaces reflection/source-presence assumptions. Isolated 14/14 PASS (`privacy-runtime-extracted-tests.log`). Old tests remain until the deletion gate.
- Independent emission review requires four fixes: prevalidate all lowering changes before call-return metadata mutation; validate selected callee export markers before caller outputs/lineage/cleanup; reject nonsingleton legacy return-definition accessor; limit marker ordering rewiring to the exact current Lop DAG. Implementation and fresh tests are ongoing.
- Exact solver lane isolated a safe search-order improvement, not a new pruning rule: prioritize current distinct check/cost co-owner connectivity. LogReg diagnostic completed OPTIMAL at 1294.245790229737... in 130,061 nodes, model 1.636s / model+optimization+reconstruction 6.460s. This host fixture result is not Docker planning-time or numeric parity evidence. Central integration remains pending.
- GLM still has no completed model/workload evidence: native execution cost construction expands irrelevant source/guard/completion combinations. Work targets projected cost contexts, retaining exact shape and termination conditions without source products.
- Pending: integrated regression suites, actual Docker LogReg/GLM numeric/emission verification, full semantic test transfer, then legacy deletion. Original source workspace and experiments remain preserved.

## Docker LogReg milestone — v9a PASSED (not whole migration completion)

- v8 central main/test compile PASS37.779s; core245/245 and W2/shared-runtime10/10 PASS. Frozen v8 had a real post-selection lowering failure in LogReg, not a solver timeout.
- Minimal sum(product) regression reproduced the missing selected multiply. Memo now prevents unmodeled late fusion from erasing independently selected kernels, using committed selection authority; unselected and legacy paths keep existing fusion. Expanded compiler tests12/12 PASS.
- Residual emission preflight and duplicate-output-name cleanup review findings fixed; isolated34/34 +9/9 W2. Frozen `docker-engine-v9a` is v8 + explicitly recorded compiler/emission deltas, compiled from its own frozen sources. Delta suite53/53 PASS; ongoing GLM/builtin WIP excluded.
- Docker `memo-v9a-logreg-diagnostic04/result.json`: **PASSED**. W=3; nativeRule count920; exact search2,934,107nodes. Planner phases total20.665881174s; full compilation21.571258s; runtime3.071s. CP reference compilation1.174157s/runtime0.665s is numeric reference only, not an old-planner performance comparison.
- Result model8x2,16entries all match CP; max absolute difference1.6653345369377348e-16. `selectedOptimal`, `nativeFedExecution`, `loweringVerified` alltrue; no runtime conversion/audit violations.
- This is one small fixed Docker LogReg correctness workload, not a general scaling benchmark. GLM completion, native multi-output capability/binding/cost coverage, loop guard cost-contract audit, and final clean-build legacy deletion gates remain.

## Continued implementation — native-cost source products removed; not complete

- GLM native model construction now completes in ~2.96s in a **host model-only diagnostic**, with 994 owners/2,098 rules and complete native coverage. This is not total planning performance. Independent projection review is pending.
- The next GLM diagnostic reaches optimizer initial feasibility propagation but exposes repeated conditional privacy evaluation. No GLM optimum/runtime-success claim yet; privacy work is being localized without weakening legality.
- Native multi-output slot support and supported CP bias kernels integrated in source; isolated 109/109 component and 2/2 numeric builtin runtime tests pass. Runtime QR's actual Householder H/R contract is used.
- Added repeated-loop guard activation regression; current declared cost estimator intentionally differs from legacy's cross-iteration conservative activation. Numeric equality or legacy selected-plan equality is not an optimality proof.
- Independent emission review resolved atomicity findings but identified owner lifecycle retention/stale authority. Owner-scoped weak lifetime/invalidation is in progress with new tests.
- Fresh Docker harness tests 39/39 and diff whitespace checks pass. Central source/test compile, final actual GLM/LogReg Docker runs, and gated legacy deletion remain outstanding. No commit/push; original workspace preserved.

## Central v10 checkpoint — source coherence restored

- Full production/test compilation PASS39.384s; core **283/283 PASS**. Source snapshot includes multi-output/bias support, guard value provenance and privacy cache projection, frozen shape/NNZ cost contexts, exact owner lifecycle leases and rollback.
- Independent lifecycle follow-up APPROVE: stale recompile epochs, unrelated-owner rollback clobber and orphan strong supply retention fixed. Shape/cost proof review and actual W2 integration remain in flight.
- Docker harness now parses complete Memo phase timings separately from compilation/runtime; **41/41 Python tests PASS**. Existing v9a evidence is preserved and not rewritten.
- GLM still has no completed planning/runtime result. Its current bottleneck is repeated exact privacy/source/completion evaluation during initial feasibility propagation. Privacy payload and source availability must remain separate obligations rather than re-expanding unrelated source products.
- No legacy core deletion or commit/push. Overall implementation task remains active; this checkpoint is not completion.

- v10 additional gates: actual W2 planner/shared-supply **10/10 PASS**, reused native Oracle/physical-cost tests **205/205 PASS**, Docker harness **41/41 PASS**. These belong to the v10 build, before the next privacy/shape refinements.
- Subsequent exact-search diagnostic found 215 fixed UNKNOWN obligations in GLM; root prevents expansion of unrelated assignments and returns UNRESOLVED early. Actual analysis gaps remain under repair; not a successful GLM plan.
- R1 independent dead-code batch removed the caller-free `FederatedTypePropagator` class. All active legacy planner/closure/exact layers remain until runtime/parity gates. Fresh clean-output compilation is still required for the deletion gate.

## Current continuation — v11 preparation, not yet an integrated checkpoint

- Nested-loop entry costing now includes an outer head-contract source, with private contextual output/binding shape views. Isolated adjacent suite **47/47 PASS**; independent source review pending.
- Transformencode's authorized public metadata is modeled by exact output slot using the existing deployment opt-in/spec validator. Default protected metadata remains rejected. The actual two-worker heterogeneous-output fixture is still exposing lowering gaps; no completed FED transformencode result is claimed yet.
- GLM work is focused on the first unresolved privacy chain, not unrelated global search: actual/formal binding resolves correctly, but a loop-carried released value loses its positive residency proof. Missing proof is still UNKNOWN, not public or infeasible.
- Fresh Docker harness **41/41 PASS** (`integration-v11-harness.log`). Original workspace source-diff SHA-256 remains `7c4657652de1de5f59d769fd98347d67cd61a8afccc90640dd2b1ace760041b1`.
- Next coherent checkpoint requires all writer lanes to freeze, clean main/test outputs, complete regression suites, and a source-matched Docker engine. Historical v9a LogReg results must not be labeled as current-source results.

## User-requested GLM diagnosis/plan checkpoint — production frozen

- Confirmed: investigated actual/formal def-use chain resolves completely; failure was in privacy summary convergence rather than a missing native transfer handler. A minimal may-privacy fixed point reduced GLM fixed UNKNOWN checks from **215 to 1**; isolated residency **11/11 PASS**. No component-wide grounding/proof layer remains.
- The last UNKNOWN is a separate `m_glm` return/normal-continuation guard correlation issue at main `PWrite beta`. Exact cause inside completion facts versus query projection is not yet established. GLM still returns UNRESOLVED; no optimum/runtime result is claimed.
- Resolution/verification plan saved to `.omx/plans/glm-privacy-fixed-point-and-activation-2026-10-13.md`. User asked to define the problem and plan the fix, so production edits and further build/workload runs are frozen at this point.
- Other completed isolated evidence: actual transformencode W2 **4/4**, builtin/privacy/lowering **27/27**, slot/privacy-cache isolation **2/2**. Entry-cost phase repair **2/2** plus adjacent **46/46**; its dedicated same-shape/different-NNZ guarded fixture remains OPEN. These are not yet a clean central v11 checkpoint.
