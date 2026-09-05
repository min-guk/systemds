# Origin-bound aggregate-only release: repair and remaining runtime support gap

## Repair contract

PRIVATE and unreleased PRIVATE_AGGREGATE values cannot be downloaded to the coordinator, computed there and uploaded, or moved through a coordinator-mediated REFED. An approved aggregate can release its result, not its original input. DML call placeholders and FederationMap dimension reads are not payload acquisitions. Matrix function formals, return values, transient aliases and CFG joins remain data-bearing.

Implement result/node/candidate-input filtering in the shared PlacementAnalysis construction. Implement conditional movement feasibility in the shared movement model and all selector paths; reject an unsafe option rather than deleting a whole action that might certify same-pool direct use. At final emission, independently validate before registry/prefetch mutations. No runtime fallback or DML/public relabeling.

## Locked regressions and compatibility correction

- Raw PA elementwise/transient/native collection regression: 4 cases, 3 fail before repair; strict PRIVATE counterpart already passes.
- Public aggregate output with protected input: CP aggregation was erroneously available; RED captured before input-gate edit. nrow metadata counterpart already passed.
- Existing tests expecting PA CP/LOUT, CP/FOUT, raw scalar right-index FED/LOUT are semantically incorrect and are changed to assert exclusion. Concrete FType/anchor does not authorize data release.
- Existing branch/function propagation fixture must retain nonempty FED/FOUT domains on every protected data-bearing occurrence, excluding only the actual DML FUNCTION_CALL placeholder.
- Unknown-width aggregate/centering coverage is retained using a raw federated matrix with unknown compiled width; it is no longer coupled to an unsupported recode metadata release.

## P2 transformencode is an explicit unresolved support boundary

The selected dummycode spec implicitly builds a recode dictionary. The current runtime sends each worker's encoder to the coordinator; `ColumnEncoderRecode.getMetaData` serializes original distinct keys, and the metadata output is a local frame. A distinct-value dictionary can expose raw values and has no output-specific aggregate declassification authority in the current privacy lattice. Calling the frame "metadata" is not a proof that it is non-sensitive.

Source evidence:
- `runtime/transform/encode/EncoderFactory.java`: implicit recode for dummycode without hash/bin.
- `runtime/transform/encode/ColumnEncoderRecode.java#getMetaData`: source keys plus codes.
- `runtime/instructions/fed/MultiReturnParameterizedBuiltinFEDInstruction.java`: CreateFrameEncoder responses, coordinator encoder merge, local metadata output.
- Historical SystemDS `d522183249bd792fffa7e00b833213eb172f1fbf:src/main/java/org/apache/sysds/runtime/privacy/PrivacyConstraint.java`: aggregate-only release; no recode dictionary/public-domain exemption.

The [ExDRa paper, §4.4](https://mboehm7.github.io/resources/sigmod2021c_exdra.pdf) describes distinct-item exchange for recoding and its disclosure tradeoff. Its feature-hashing alternative is not the same benchmark and will not be silently substituted.

**Current disposition:** the PA recode/dummycode pipeline fails closed in common analysis. Do not restore local metadata availability, relabel raw data/public, or run this old plan to preserve a graph. A future safe support change needs an explicit transform-spec-specific protocol and release authority, including internal responses, not just output-M labeling. This remains a gap for P1/P2/SliceLine cells that use such a transform.

## Verification and remaining risk

Parent and independent agents are testing the complete four-selector paths. Partial tests passing are not a campaign completion or privacy certification claim. Conditional active-vs-direct movement must be checked before Exact factors and single-pass policy commits; DP must skip only narrowly typed infeasible arms, not swallow invariant failures. Authenticated old/new physical plan comparison and actual Docker parity are still required before runtime results are promoted.
