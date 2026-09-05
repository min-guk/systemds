<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to you under the Apache License, Version 2.0.
-->

# GLM Exact cost-factor repair (2026-09-05)

## Failure and root cause

The authenticated GLM planning cell `ml|glm|lan|w1|Exact` failed before the
optimizer ran. The archived coordinator log reports
`OutOfMemoryError: Requested array size exceeds VM limit` in
`ExactPhysicalCostModel.appendPhysicalFactorValues`, reached from
`physicalCostSurface`. At the failing revision, the fingerprint builder used
one `StringBuilder` and recursively enumerated every Cartesian factor cell.
This violated the solver's documented contract that dense-factor budgets are
checked before lazy factor evaluation.

The cost model contains two intentional high-order reusable-materialization
factors:

* compiled transfer groups charge the maximum active demand price; and
* latent WDivMM runtime inputs charge the exact control-flow event union.

Those are compact functions, not intrinsically dense tables. Expanding either
function merely to hash it can exceed the JVM's maximum array length. If the
same monolithic factor is passed to variable elimination, it can also create a
consumer clique beyond the 10,000,000-cell production limit.

## Repair contract

The repair keeps the original physical model and canonical objective intact.

1. `PhysicalCostSurface.variables`, `contributions`, `factors()`, and
   `evaluateCanonical` still expose only the original authority-bearing
   decisions and original cost functions. Local-conflict DP and trace
   contribution evaluation continue to use that surface.
2. Exact receives a separate augmented solver view. Original variables are an
   identity-equal prefix. Auxiliary variables are internal and never reach the
   physical selection, candidate receipts, relocation receipts, forced-state
   audit, or projector.
3. A reusable max-demand factor is represented exactly by:
   * a deterministic source semantic projection;
   * one deterministic semantic projection for each distinct consumer;
   * zero/infinity link factors from every original alternative to its class;
   * a deterministic prefix-maximum chain; and
   * one terminal factor carrying the original binary64 price.
4. Source and consumer projection classes collapse only observations made by
   this factor. Original alternatives are never collapsed. In particular,
   upload activity observes the exact relocation/emission authority, not only
   placement state.
5. A latent WDivMM group whose demands all share one control-flow event uses
   binary source/read compatibility constraints, deterministic read/owner
   activation variables, and the same prefix-maximum representation. General
   event unions remain the original exact evaluator. If such a general factor
   is too large, the existing solver preflight rejects it by a named exact
   factor/materialization limit; it is not approximated or silently pruned.
6. Ordinary bounded factor tables are hashed directly into SHA-256 without an
   intermediate aggregate string. Structured factors hash a versioned semantic
   descriptor containing original scopes, activity maps, every numeric value as
   raw binary64 bits, projection maps, auxiliary domains, and solver-factor
   scopes. The WDivMM descriptor includes the computed unit-cost table, so a
   network/serialization calibration change changes the receipt.
7. After solving, Exact trims the assignment to the original identity prefix,
   retains the augmented solver statistics, runs forced-state verification on
   the trimmed result, and independently compares the solver objective bits to
   `PhysicalCostSurface.evaluateCanonical`.

For every original assignment, link constraints and prefix transitions admit
exactly one auxiliary extension. Therefore minimization over auxiliaries is
the original function, not a relaxation. Extra zero-valued constraint factors
do not alter the elementary non-zero cost terms.

## Exact solve-time quotient

The compact structured factors remove the first failure, but the unmodified
dense elimination order still induces a 592,764,480-cell separator across the
high-cardinality original authority domains around `glm.dml` lines 735--739.
The final repair therefore applies two semantics-preserving reductions after
the hard constraints, forced-state constraint, and all augmented cost factors
have been assembled:

1. Unary and binary arc consistency removes a value only when it has no finite
   support in an applicable input factor. This is a necessary feasibility
   condition, so it cannot remove a feasible global assignment. Higher-order
   factors are deliberately not used for unsupported local pruning.
2. For each original physical decision, the reducer partitions its remaining
   values by the complete observation vector in every incident frozen factor,
   over all active assignments of the other scoped variables. Raw binary64
   bits, factor identity, and the optional secondary tie cost are part of this
   vector. A streaming hash creates candidate buckets, but exact raw-vector
   comparison is required before two values enter the same class.

Only original decision variables are quotiented. Exact-only auxiliary
variables may lose values through sound arc consistency, but are otherwise
left as singleton classes. Every reduced factor table is rebuilt from the
frozen original table using the lowest-index member of each class. The normal
dense variable-elimination solver then runs under the unchanged production
limits. Its class assignment is expanded back to original/auxiliary indices;
the optimizer removes the auxiliary suffix, executes the existing forced-state
audit, and reevaluates the complete original canonical surface by raw bits.

The input contract is fail-closed. At physical-surface construction, the model
first assembles the complete hard-plus-augmented-cost solver input and validates
all scopes, domains, dense values, individual factor cells, and the aggregate
input-materialization budget before evaluating any ordinary cost factor.
Ordinary factors are then frozen once into immutable dense tables; the same
factor objects feed the fingerprint, canonical contribution surface, and Exact
solver. Structured high-arity factors avoid their original Cartesian table:
their versioned descriptor contains the complete immutable activity/projection
maps and every numeric price as raw bits, while the bounded auxiliary factors
encode those same captured arrays. The optional forced-state audit factor is
created later and is revalidated together with every solver input before its
lazy evaluator runs. At solve time all remaining lazy structured/hard factors
are materialized exactly once for that solve, so a dynamic forced evaluator is
fresh on the next solve but cannot change during reduction.

The production entry point uses the same `PRODUCTION_LIMITS` at surface
construction and at optimization, so the aggregate input gate precedes
ordinary-factor fingerprint evaluation in the deployed path. A package-local
test seam can supply stricter limits to surface construction in order to prove
that ordering. Callers that deliberately construct a surface with production
limits and later invoke the optimizer with a tighter, custom limit still get a
fail-closed solve-time rejection, but the later limit cannot retroactively
prevent ordinary-factor evaluation that already occurred while constructing
the surface.

## Correctness argument

Arc consistency retains every globally feasible assignment. For an original
variable, two values share a quotient class only if substituting one for the
other leaves every incident factor's raw value unchanged for every active
context (and leaves its secondary tie cost unchanged). Replacing any discarded
value by its class representative therefore preserves every factor and the
total objective. Conversely, every reduced assignment expands to a valid
original assignment with exactly the represented factor values. Thus the
minimum reduced objective equals the minimum original objective; this is not a
top-K restriction, a placement-only merge, or a relaxation of hard/privacy
constraints.

Equal-cost plans can still select a different authority-valid representative
when the specified tie cost is equal. That is an actual solver tie rather than
an objective or candidate-space change. The optimizer's independent canonical
raw-bit reevaluation remains the final semantic certificate.

## Tests

`ExactMaxDemandFactorDecompositionTest` exhaustively fixes every original
assignment in a bounded factor and compares canonical and augmented objectives
by raw bits. It covers repeated demand variables, source/consumer overlap,
distinct original alternatives mapping to the same semantic class, descriptor
numeric sensitivity, and a Cartesian surface that overflows as a monolith but
passes bounded-width analysis after decomposition.

`ExactPhysicalReducedSolverTest` adds deterministic random unary/binary/ternary
comparisons against the unreduced exact solver and separately covers an empty
AC-3 domain, forced value, observation equivalence, an observation difference
that must prevent merging, secondary tie-cost separation, input-budget
preflight before lazy evaluation, and once-per-solve dynamic materialization.

`ExactPhysicalCostFingerprintPreflightTest` exercises the actual
`physicalCostSurface` production implementation through a package-local limit
and observation seam, proving that its aggregate preflight failure invokes zero
ordinary evaluators. It also verifies the lower-level aggregate-budget boundary
and that a successfully frozen ordinary table is reused by the solver even if
the original evaluator's external state subsequently changes.

`ExactGlmCostSurfaceScalabilityTest` compiles the actual built-in GLM body
without executing DML. It retains more than 300 original decisions, constructs
the augmented exact surface, solves under the unchanged 10,000,000-cell /
50,000,000-materialized-cell production limits, checks canonical objective-bit
equality, and projects the original candidate and relocation receipts. This
test is GREEN. After packaging an immutable stage, an authenticated Docker
planning-only canary remains required before promotion or any runtime campaign;
this unit fixture proves compiler and optimizer completion, not
container/deployment provenance.
