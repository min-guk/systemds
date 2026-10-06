# MMChain complete-plan feasibility audit

## Current conclusion

No duplicate MMChain network or CPU charge is confirmed for a complete production
DP-LocalConflict plan.  The earlier `NativeTransferAuditProbe` result is not such a
plan: it creates coordinator-local transient inputs and forces only the outer matrix
multiply to `FED/LOUT` before Lop construction.

## Production compilation path

`DMLTranslator.prepareCommonSearchSpace` runs
`RewriteFederatedPlannerPhysicalNormalization` before placement analysis is bound.
For the runtime witness `A` with shape 8x2, the outer expression
`t(A) %*% (A %*% q)` satisfies the normalizer predicate
`2*8 > 8*1 + 2*1`.  The immutable placement analysis therefore receives
`t(t(A %*% q) %*% A)`, not an MMChain candidate.  The weighted form has the same
8x1 right-hand result geometry and follows the same normalization.

If geometry makes that normalization inapplicable, fusion still survives lowering
only when the selected outer multiply, transpose, and inner multiply do not carry a
direct-FOUT or explicit movement boundary and the adjacent selected execution/output
states match.  No complete anchored DP selection satisfying those conditions has
been observed.

## Runtime verdict

The latest pinned-image, two-worker Docker `linear` workload completed with the
independently derived numeric result `3588.0`.  It uses a weighted public Gram
matrix to keep that operation observable and retains the private-aggregate
MMChain-shaped expression.  Its heavy hitters contain `fed_ba+*=1`, `fed_r'=1`,
`fed_uak+=1`, and `fed_uark+=1`; they do not contain `fed_mmchain`.  Federated
statistics report Read/Put/Get `4/1/4`, Execute Inst/UDF `10/0`, and coordinator
network read/written `8642/15706` bytes.  Runtime fallback and repair counts are
both zero.  The evidence is captured in `run-bxvhfa_0/linear.result.json` and
`run-bxvhfa_0/linear.log`.

This confirms that the actual selected complete DP plan takes the normalized,
unfused path.  It does not establish any duplicate MMChain network or CPU charge,
so the earlier forced-root probe is not a basis for a cost-model change.
