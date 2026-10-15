# Wave 1 Local seed correctness review

Review date: 2026-10-15
Baseline: `035f9c8eb7`
Scope: read-only review of `MemoFeasibleSeed.java` and the new `MemoLocalSeedEfficiencyTest.java`. No Maven or benchmark execution was performed; the parent owns the running validation.

## Code Review Summary

**Files Reviewed:** 2
**Total Issues:** 1

### By Severity

- CRITICAL: 0
- HIGH: 0
- MEDIUM: 1
- LOW: 0

### Issues

[MEDIUM] `improveFirst` admits and constructs unused indexes when no search work can run
File: `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoFeasibleSeed.java:190`
Issue: `improveFirst` always uses `estimatedLocalImproveWorkspace(...)` and constructs the seed with `indexLocalFactors=true`. `improveFirstWitness` then returns before enumeration when `workLimit==0` or when every copied scope is empty. Under a memory ceiling that admits the former improvement workspace but not the new index metadata, the same call now returns `RESOURCE_REJECTED` with a null selection instead of returning the already validated incumbent with `WORK_LIMIT`, `NO_SCOPES`, or `NO_IMPROVEMENT`. The new index is unnecessary on these paths; `findFirst` already avoids constructing it when `workLimit==0`.

Impact: This changes Local seed API behavior and discards a valid incumbent solely because of metadata that cannot be used. The Local controller currently calls `improveFirst` only with positive remaining work and non-empty selected scopes, so the defect is a public edge-path compatibility/resource regression rather than a current certificate-soundness failure.

Fix: Determine whether enumeration can run before selecting the workspace estimate and constructor mode. Use the Local index only when `workLimit>0` and at least one supplied scope is non-empty; otherwise use `estimatedImproveWorkspace(...)` and `indexLocalFactors=false`. Preserve later owner validation and the existing reason values. Add a tight-memory regression for zero work and for a list containing only empty scopes, asserting that the valid incumbent and prior reason are retained.

### Verified behavior

- **Closure index:** Greedy and enumeration assign owners in ascending numeric order. A non-empty factor becomes fully assigned exactly when the current owner is the maximum owner in its scope. `checksClosingAt` and `costsClosingAt` therefore select the same factors as the former `contains(scope, owner) && assigned(scope, selected)` predicate.
- **Original order and IDs:** Indexed checks retain their original list index for `blockedChecks` and repair-region expansion. Per-owner closing lists are populated by traversing the original factor list, preserving predicate/evaluator order. Scope projection retains each factor's original owner order.
- **Assignment and backtracking:** The existing depth-first traversal and ascending ordinal order are unchanged. Closing-list lookup does not alter `consume()`, so one work unit is still charged for each attempted ordinal and work caps stop at the same boundary.
- **UNKNOWN and repair:** A closed UNKNOWN check is still recorded in `SearchResult.unknown` and rejected as a seed row; a greedy UNKNOWN remains a blocked original check whose original ID expands the repair region. Full validation still requires `Truth.YES` before any witness is returned.
- **Empty factor scopes:** Empty-scope checks/costs have no closing owner, matching the old greedy/partial-check behavior. They remain in `indexedChecks`/`indexedCosts`, so full truth and full objective evaluation still include them, including the zero-owner model case.
- **Full objective:** Indexed `fullCost` iterates every original cost once and native costs are unchanged. Conditional improvement still revalidates all crossing checks and the complete original cost before returning.
- **Legacy paths:** Public `find(...)` and `improve(...)` construct with indexing disabled, retain their former workspace estimates, direct-kernel behavior, proof production, repair accounting, and search implementation.
- **Metadata admission for active indexed paths:** `findFirst` and active `improveFirst` admit the expanded workspace before constructing cached scopes and per-owner lists. The estimate includes per-owner mutable/immutable closing-list forms and per-factor wrapper/scope storage through checked arithmetic.

### Test coverage reviewed

The new tests cover deterministic greedy choice and work count, work-cap boundaries, UNKNOWN rejection, repair to the first lexical YES witness, conditional crossing-check/full-cost validation, and pre-construction index-memory rejection. Existing Local seed tests cover proof-domain immutability and first-improvement behavior. The missing zero-work/all-empty-scope tight-memory case is the reproducer for the issue above.

### Recommendation

**REQUEST CHANGES.** Fix the unused-index admission path before approval. No other correctness or proof-contract regression was found in the reviewed seed optimization.

## Resolution verification

The pending issue is resolved.

- `improveFirst` now enables the closure index only when `workLimit>0` and at least one supplied scope is non-empty.
- Zero-work and all-empty-scope calls retain the legacy workspace estimate and construct the seed with indexing disabled. They therefore preserve the validated incumbent and the prior `WORK_LIMIT`, `NO_SCOPES`, or `NO_IMPROVEMENT` result instead of requiring unused index memory.
- Scope validation is not bypassed. Null scopes are rejected before admission, and owner-range validation still runs in `copyAndValidateScopes`, including the zero-work path.
- Active enumeration continues to admit the expanded index workspace before construction.
- New tests cover a finite ceiling that admits the legacy no-enumeration workspace but would reject the indexed workspace, for both zero work and an all-empty scope list. A separate test confirms malformed scope owners still throw when work is zero.

No new issue was introduced by the fix. The updated recommendation is **APPROVE**.
