# Wave 1 kernel correctness review

Review date: 2026-10-15
Baseline: `035f9c8eb7`
Scope: read-only review of the `MemoLocalMessages` incremental root-bound/active-set change and the `MemoLocalOptimizer` cached-size/initial-bound integration. Concurrent `MemoFeasibleSeed` work is outside this review.

## Code Review Summary

**Files Reviewed:** 4
**Total Issues:** 0

### By Severity

- CRITICAL: 0
- HIGH: 0
- MEDIUM: 0
- LOW: 0

### Correctness findings

- **Incremental root bound:** Before publication, the code starts from the cached sum of every active factor's root minimum, subtracts each consumed factor minimum, and adds the completed message minimum. Candidate inputs are exactly the active factors incident to the eliminated owner. Factor scopes are unique by the `Check`/`CostTerm` construction contract, so an input is neither omitted nor subtracted twice. The replacement is therefore algebraically identical to recomputing `lowerBound(root)` over the post-publication active factors.
- **Signed costs:** The update uses exact `BigDecimal` addition and subtraction and makes no monotonicity or non-negativity assumption. Negative native values, certified floors, and message values retain the previous semantics. The controller still raises `rootLower` with `max`; it does not use a cached bound to weaken or lower an already published bound.
- **Null and UNKNOWN:** A null root minimum still means that the relaxed active factor set has no compatible row. Once a completed message has no root-compatible row, later elimination cannot restore feasibility, so retaining a cached null is sound. `Truth.UNKNOWN` remains allowed with zero hard-factor contribution, while only `Truth.NO` forbids a row. No UNKNOWN path is converted to YES for incumbent validation or removed from the proof cover.
- **Conditional domains:** The cache is used only when every residual `BitSet` equals the immutable construction root. Any strict residual restriction takes the original full factor/message scan. Restrictions on already eliminated owners continue to be ignored by the message factor, preserving the documented weaker relaxation rather than producing a conditioned optimum.
- **Atomic publication:** Both memory admissions, candidate preparation, complete message solving, and computation of the replacement root bound happen before `active`, `incident`, `messages`, `eliminated`, retained-byte counters, or the cached root sum are published. A rejected admission or handled allocation failure therefore leaves the old active cover and root bound unchanged. Exceptions are not converted into a successful result.
- **Active-set replacement:** `LinkedHashSet` preserves the prior deterministic insertion order while changing identity-based active-factor removal from a linear list operation to constant-time set removal. `Factor` has identity equality, and every constructed factor/message instance is unique, so no legal duplicate is collapsed.
- **Memory admission:** Cached domain/setup estimates are computed once from immutable sizes and scopes and retain the prior formulas. The added per-factor cache fields and `LinkedHashSet` entry overhead remain inside the existing conservative per-variable/per-factor setup allowances; dynamic message tables retain their separate conservative per-cell and metadata admission. The controller still counts setup plus retained message bytes in resident memory.
- **Controller semantics:** The message loop now calls `initialBound()`, whose result is numerically identical to the former `lowerBound(root)`. Message selection, cell cap, candidate domains, witness reconstruction/validation, fallback to complete-cover search, positive-LB epsilon test, exact equality test, resource outcomes, and stopping order are unchanged.
- **Counters:** `rootBoundQueries` and `conditionalBoundQueries` distinguish cache-eligible root requests from conditional scans. Existing total `boundQueries` continues to increment once per public bound request.

### Regression evidence inspected

- New focused tests cover repeated root-bound reuse after each publication, negative costs with UNKNOWN rows, strict conditional-domain queries, and rejection before publication.
- Existing tests cover signed random models against Cartesian enumeration, exact signed completion, UNKNOWN lower-bound retention, eliminated-owner conditional relaxation, no-row rejection, memory-rejected publication, and proof-cover preservation after failed child admission.

Tests and benchmarks were not run in this review, as requested. This conclusion is based on the complete source diff and the relevant test coverage.

### Recommendation

**APPROVE.** No blocker or correctness regression was found in the reviewed wave-1 kernel/controller changes.
