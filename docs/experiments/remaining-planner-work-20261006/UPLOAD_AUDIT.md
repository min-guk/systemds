# Repeated stable-input upload audit

The final isolated compile probe uses a `20,000,000 x 128` row-federated
`PRIVATE_AGGREGATE` matrix `X` and a public coordinator-local `128 x 1` matrix
`Y`.  It calls the same function twice with the same immutable `Y`; the function
contains a normalized if/empty-else boundary and computes `sum(X %*% Y)`.

The latest target accepts both calls after the same-actual occurrence fix.  Both
branch carriers select `CP/LOUT`.  The selected plan reports `REL=[]` and
`MAT=[]`.  Therefore this fixture does not select an explicit REFED action and
cannot support a runtime upload-cache claim.  Any RHS transfer needed by the
federated matrix multiplication is intrinsic instruction traffic, represented by
the selected aggregate-binary alternative rather than a planner relocation.

Evidence:

- Source: `StableCarrierAggregateProbe.java`, SHA-256
  `ffe92d908f8f5ddd0301add2a904d4a2f479ede592f4817ceb938333c6cab398`.
- Compiled probe: `stable-upload-private-repeat-latest-classes/StableCarrierAggregateProbe.class`,
  SHA-256 `74f13f637e576fe9dfb4be69275af3a926d73ccdba2a61c935e491d2c87f8c9d`.
- Output: `stable-upload-private-repeat-latest.log`.
- Planner jar at audit time: SHA-256
  `1d8955a9949e6e14515f1e295851180037a222eda09e772f3eb8d66942ee591a`.

Earlier all-public and elementwise variants are unsuitable evidence: the former
allows an all-CP plan, while the latter is not privacy-safe for a
private-aggregate input.
