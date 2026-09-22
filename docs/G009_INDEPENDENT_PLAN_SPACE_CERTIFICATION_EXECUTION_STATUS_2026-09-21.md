# Independent plan-space certification: execution status

This is the earlier execution log. The [single final report](G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_FINAL_REPORT_2026-09-21.md) records the later production-source drift and is authoritative for the current verdict.

## Result

**Overall: INCOMPLETE.** The bounded regression infrastructure exists and has
passed its targeted Java tests, but no workload-wide completeness or soundness
certificate has been issued. A three-way equality of full joint plan sets for
every discovered workload has **not** been established. The separate
`docs/G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_PLAN_2026-09-21.md` remains
the acceptance contract; no subset success changes its full PASS criteria.

This session did not modify production planner/runtime behavior. Concurrent
work in the same checkout also changed production planner files; this report
claims only the new verification files below. A future certificate must freeze
the actual full source tree and input metadata at execution time.

## Implemented pieces

| Plan stage | Result | Evidence / limitation |
|---|---|---|
| S0 inventory | Partial | `scripts/fedplanner/build_plan_space_inventory.py` freezes 279 source-qualified discoveries (115 `IN_SCOPE`, 148 `UNSUPPORTED`, 16 `HISTORICAL`) and hashes all 206 external DML files. The four planning contexts expose 224 profile-qualified signatures; all remain snapshot-only, and 59 other in-scope entries lack condition lists. `/home/mchoi/cofee-evaluation/calibration/build_plan_space_cells.py` generates a 447-cell backlog with every discovery and planned profile mapped. All 224 planning profile cells statically validate literal worker order/ranges and sidecar/global shape/privacy against context; the copied input/worker-partition manifests are SHA-matched to each context and their partition ranges cross-checked against DML. Eight planning templates lack a profile case. The separate profile-backed compiler smoke parses and snapshots all 56 worker-qualified cases before builder execution; 24 templates still have 28 calls with unknown input/output name arrays. Actual data bytes/compiler/runtime FederationMaps and exporters remain unattested. |
| S1 rule/transform ledger | Partial | `scripts/fedplanner/build_plan_space_rule_ledger.py` freezes 48 registered rule families, 56 previously audited transformations and hashes the relevant production trees in `src/test/resources/fedplanner/plan-space/rule-ledger.json`. All 48 family runtime tuple parity proofs remain OPEN. A separate ignored-test ledger pins 14 PUBLIC-source test exclusions with reasons and source hashes; those tests provide no certification evidence. |
| S1/S2 independent oracle | Bounded PASS only | New `src/test/java/org/apache/sysds/test/component/federated/placement/oracle/semantic/` core has independent DTOs, mixed-radix full enumeration, source-map anchoring, bounded legality, explicit UNKNOWN, identity and mutation fixtures. Runtime, matrix and state capability slices cover selected positive/negative tuples, not every rule. The separate pre-builder `shadow/PrebuilderSnapshot` adapter captures raw AST/HOP/control/function boundaries before candidate generation and fails on missing frozen FED source metadata. Its certification entry point deliberately returns UNKNOWN for otherwise LEGAL plans because the full physical grammar is not yet established. |
| S3 production capture | Partial | `.../shadow/ProductionPlanSpaceCapture.java` records published AVAILABLE receipts and rejected facts. `ProductionJointPlanSpaceProbe.java` enumerates bounded action-free products. `FullProductionJointPlanExport.java` streams raw placement, receipt and graph-owned relocation choices by ordinal with accepted/rejected/unknown audit before policy. Protected B-21 exposed candidate owners outside the decision graph; their raw receipt choices are preserved and all such rows remain `UNKNOWN`. Graph-published private relocation and derived-FOUT coverage is unproved; this is not the full workload P exporter. |
| S3 existing exhaustive | Partial | `.../fedExact/ExactPhysicalRawSpaceExporter.java` streams original model assignments and hard-factor results without objective pruning. `ExactPhysicalPlanSpaceExporter.java` preserves occurrence, ordered inputs, authority and action provenance in each decoded model alternative. Test-owned `PlanSpaceFixtureArtifactAdapter` writes protected P/E raw ordinals and SHA-bound UNKNOWN receipts. Protected B-21's E model has 6,048 raw assignments; all were exported, but model-domain completeness is unproved and the exporter reports global coverage `UNKNOWN`. |
| S4 runner/cache | Infrastructure only | `/home/mchoi/cofee-evaluation/calibration/plan_space_verify.py` has independently ranged shard adapters, resume/cache, exact streamed set comparison, persisted per-index audit and certificate checks. `scripts/fedplanner/freeze_plan_space_bundle.py` stores 757 available source/input files as 613 deduplicated SHA-256 objects and refuses to overwrite a different frozen index; `--check-current` detects checkout drift. Its synthetic tests establish coordinator behavior, not runtime semantics or workload completeness. A machine-checked full semantic coverage gate is still absent; full status is forced to UNKNOWN. |
| S5 all-workload certification | Run, non-PASS | The generated 447-cell backlog was run through the full-mode coordinator and independently rechecked. Both return `UNKNOWN`: 283 in-scope cells lack complete attestation/adapters, 164 entries remain unsupported or historical, 115 discovery conditions remain unresolved, and rule-tuple coverage is UNKNOWN. No three-way workload comparison has run. |
| S6 continuous gate | Bounded gate | `scripts/fedplanner/run_plan_space_certification.sh tiny` checks inventory/ledger drift, targeted Java tests, Python runner tests. `full` requires a current manifest and artifact root and may not interpret bounded PASS as full PASS. |

The discovery scope and known input/launcher limits are detailed in
`docs/PLAN_SPACE_CERTIFICATION_SCOPE.md`.

## Validation evidence

- `scripts/fedplanner/run_plan_space_certification.sh tiny` passed. The subsequent `full` gate reran the inventory, rule/exclusion ledgers and 447-cell backlog drift checks, 8 repository Python tests (including compressed-artifact and frozen-bundle corruption), 27 Python runner/backlog tests and 14 targeted Maven classes (54 executed cases, 0 failures/errors/skips). This is **bounded** evidence only. An initial integrated run failed the oracle independence guard because the pre-builder adapter test imported planner privacy inside the semantic oracle package; moving the whole adapter to `shadow/` restored the boundary. The latest integrated Maven run captured all 56 profile-backed planning templates.
- The full-mode gate saved a certificate under `/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/5325fa7ba44ade685ed44724cc16b5773ca1468c3b36b5422f51589f50579141/certificate-full.json`; `check-certificate` with the externally supplied discovery SHA-256 independently returned `UNKNOWN` (exit 1). All 279 discovery IDs and 224 planned condition signatures are mapped, while all 115 in-scope discoveries still lack complete condition certification, and 164 entries remain non-scope/unclassified. This is a scope-accounting check, not a workload execution. Previous certificates are stale against the changed manifest and their checks fail as intended.
- `/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/input-bundle-context-pinned/bundle-index.json` freezes 757 currently available source/input files as 613 SHA-256 objects; the index SHA-256 is `083e1a86a93346f7d58943c91e3b795fd05638b6bfa04f970cfa588a7cc5b0fa`. A fresh `--check-current` passed. This is an available-byte snapshot only: it lacks worker data bytes, live compiler/runtime state and a complete P/E/R adapter, and explicitly records `planCoverage=UNKNOWN`.
- `/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/attempt-context-pinned.json` links the latest certificate, bundle index, full-gate log, manifest, discovery and rule ledger by SHA-256; it is an `UNKNOWN` attempt receipt, not a PASS certificate.
- Protected fixture raw export samples and the complete 6,048-row B-21 E-model raw product are under `/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/fixtures/`. The B-21 E-model rows and audit compressed from about 1.08 GB to about 35 MB; `scripts/fedplanner/check_raw_fixture_pack.py` rechecked archive/raw SHA, every ordinal and matching UNKNOWN audit. B-21 P's unpruned raw product is 324,699,527,577,600 combinations; B-22 P's is 37,748,736. These are representation products, **not** feasible-plan counts or a soundness/completeness certificate. Their stored receipts say `UNKNOWN`.
- Python and shell syntax checks and `git diff --check` passed. The shared checkout is changing concurrently, so source/ledger and bundle-current checks must be rerun against any later revision before applying these results.

## Outstanding obligations and exact stop conditions

1. Freeze every workload's actual DML/imports, compiler version, HOP/CFG
   snapshot, dimensions, privacy/release, FederationMap worker/range/FType,
   call/recompile context and config; reconcile all source-qualified discoveries.
2. Complete an independent finite primitive grammar including source maps,
   derived geometry, upload/download, forced-local outputs, shared actions,
   functions/transients and native grounding. Prove each bound or mark it UNKNOWN.
3. Discharge all runtime opcode/shape/FType/partition tuple rules and 56
   transformation preservation obligations with positive and negative evidence.
4. Export *entire* production and existing exhaustive joint plan sets before
   selector policy, preserving support AND/OR, action and authority identity.
5. Connect the three exporters to the runner, run every frozen cell, resolve
   `R\\P` and `P\\R` witnesses and any old exhaustive defects, finish every shard,
   then collect representative official Docker runtime traces.
6. Make the full certificate independently recheckable: manifest/cell inventory,
   per-index audit, canonical physical identity, content-bound adapters,
   cold/warm/resume parity and sufficient storage. Any missing or unknown item
   keeps the overall status non-PASS.
7. Replace the inventory's `conditionStatus=UNRESOLVED` with the required
   shape/privacy/worker/network/recompile condition signatures and require
   exact cell coverage for each. Workload-ID coverage alone is insufficient.

The planning-validation Docker launcher exists at
`/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/experiments/run_LAN_docker.sh`,
but its stated profile covers protected 14-workload, 4-worker LAN planning.
It does not provide a full-corpus verifier. The frozen planning snapshot lacks
its runtime JAR and input values: all four context files point to a runtime
tree and worker input/partition manifests that are absent at their recorded
paths. Exact-hash copies of the manifests were found in `input_templates` and
are now pinned; this does not recover worker data. The stage launcher
data/references directories are empty. The current
checkout has a JAR, but it is not a frozen input-complete
workload cell. The root filesystem had roughly 0.28 GB free at the latest check.
The isolated `/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921`
artifact directory passed a small write preflight and held the bounded/full
UNKNOWN artifacts; filesystem free space was about 1.1 TB. User quota for a much
larger run has not been established. Timeout, harness mismatch, zero-plan
workload and unclassified PUBLIC-only cases must not be counted as PASS.
