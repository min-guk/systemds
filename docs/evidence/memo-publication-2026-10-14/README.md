# Memo publication validation — 2026-10-14

The user requested committing the current FedPlanner work and pushing it to `origin/main`.
Publication base: `4314a345c16c20752421ce6251bbc2f366c85825` (fresh fetch).

## Included scope

- Memo planner, canonical supply/runtime integration, source/test changes accumulated in this workspace, and the GLM explicit failure-path correction.
- Normal-return shape repair and its regression tests.
- Docker validation harness changes and architecture/analysis/session documentation.
- This compact evidence bundle. Historical design-audit snapshots and the migration baseline's capture of an older workspace are preserved on the server, not published as current acceptance evidence.

The 2.8 GB of raw experiment data, frozen engine/dependency copies, generated classes, and local diagnostic snapshots under `experiments/memo-migration-20261013/` remain on the server; they are not Git publication content. The full historical `docs/evidence/memo-cost-projection-design-2026-10-13/`, `docs/evidence/memo-v9a-design-audit-2026-10-13/`, and `docs/FEDPLANNER_MIGRATION_BASELINE_2026-10-13.json` also remain local. References to these paths in historical analysis documents are server evidence references. No original experimental result was edited. `provenance.json` records source paths and SHA-256 hashes of the current results copied into this bundle.

## Validation and limits

- Build: `mvn -DskipTests test-compile`, PASS (`build.log`).
- Fresh publication check: Memo component **266 tests PASS** (`publication-component-tests.log`), Docker harness **41 tests PASS** (`harness-tests.log`).
- Return-shape focused tests: **9 PASS** (`return-shape-tests.log`).
- Tiny exhaustive fixtures: **2,604 assignments**, preserved feasible counts/exact costs (`exhaustive-parity.json`).
- Docker class preflight and model proof: PASS (`docker-result.json`, `docker-model-proof.log`).
- LogReg: OPTIMAL, planning **23.250950165 seconds**, CP/FED maximum model difference **1.6653345369377348e-16**.
- **GLM is not complete:** coverage repaired, but the Docker case timed out after **180 seconds** during optimization (rc124). No GLM optimum/emission/numeric parity claim. The bounded component probe finds a feasible incumbent but returns RESOURCE_EXHAUSTED; it is not a runtime benchmark.
- A preexisting callback-in-loop eager shape convergence gap remains. See `preexisting-loop-shape-convergence.md` and the retained baseline/current diagnostic logs.
- No full SystemDS test-suite, universal speedup, peak-memory improvement, or completed legacy-removal claim.
- Source/harness diff whitespace checks pass. The unfiltered documentation diff reports preexisting Markdown hard-break spaces and whitespace/blank final lines in byte-preserved raw logs; those evidence bytes and intentional Markdown formatting are retained rather than normalized to hide the warning.
- The already-unused `FederatedTypePropagator` deletion is retained: no Java callers remain and compilation passes. Old `SOURCE_SHA256SUMS.txt`, the plan-space rule ledger, and historical specifications still mention the removed path; those receipts are not presented as current certification.
- The legacy rule-ledger generator's `--check` fails before it reaches source hashing: it expects 48 rule families but finds 51 (`known-ledger-generator-gap.log`). The generator, RulesCore registry and ledger are byte-for-byte identical to fetched origin/main, so this registry/count mismatch predates publication. A regeneration attempt fails before writing; the ledger is unchanged. The legacy audit needs a separate review, not an unreviewed threshold change as part of a commit/push request.

`shape-validation.json` is the original pre-publication validation report; its old base HEAD and server paths intentionally remain unchanged. The original Docker snapshot preceded the ninth test-only cache regression; production was identical, and the final nine/266 tests were subsequently run on the host.

Publication does not waive the outstanding GLM/global optimality and legacy deletion gates in the specification. Main is updated by a normal fast-forward push, never a force push.
