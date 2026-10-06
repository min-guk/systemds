# MMChain cost reachability audit

## Result

The original audit established that an unselected `t(X) %*% (X %*% v)` HOP can lower to
`MMChainFEDInstruction`, but it did not establish a feasible planner assignment. The production
assignment checks split the result into two cases:

- A local CP fragment has a reachable fused plan. Exact selected the outer multiply, transpose,
  and inner multiply as `CP/LOUT`; emission preserved `ChainType.XtXv`, and lowering produced one
  `CP°mmchain` instruction.
- The checked federated fragment has no fused selected plan. A forced whole-program-feasible
  `FED/LOUT/ROW` outer selection required `FED/FOUT/COL` for the transpose and `FED/FOUT/ROW` for
  the inner multiply. Those direct-FOUT boundaries produced `ChainType.NONE` and an explicit
  `FED°ba+* ... LOUT` instruction.

The cost correction therefore applies only to assignment-invariant local CP/LOUT chains with no
federated source. It does not infer fusion from the source pattern alone.

## Cost ownership correction

Before the correction, the reachable local `XtXv` plan retained three source-kernel costs:

```text
outer     0.0000164794921875 ms, removed=false
transpose 0.0000308334827423 ms, removed=false
inner     0.0000164794921875 ms, removed=false
```

After the correction, the fused owner prices the runtime MMChain kernel from its actual fused
compute nodes and runtime inputs, while its exclusive source intermediates are removed:

```text
outer     0.0000268554687500 ms, removed=false
transpose 0 ms, removed=true
inner     0 ms, removed=true
```

The runtime input byte estimate counts `X` twice because the fused kernel reads it for both `Xv`
and `t(X)(Xv)`. It omits the erased transpose and inner-result materializations.

If an intermediate has another consumer, that intermediate and the fused ancestors required by
the additional consumer retain their independent execution costs. This prevents the owner fusion
from removing work that still executes elsewhere in the DAG.

## Evidence

The standalone probes and logs are outside the repository:

- `/home/mchoi/cost-followup-20261006/mmchain/assignment-probe-local-exact.log`
- `/home/mchoi/cost-followup-20261006/mmchain/assignment-probe-local-patched.log`
- `/home/mchoi/cost-followup-20261006/mmchain/assignment-probe-forced-exact.log`
- `/home/mchoi/cost-followup-20261006/mmchain/MMChainForcedExactProbe.java`

The regression test solves and emits `XtXv`, `XtwXv`, and matrix-`XtXvy` assignments, then checks
the resulting Lop and instruction. Negative controls cover scalar subtraction, disabled sum-product
rewrites, shared inner/wrapper kernels, and the forced feasible federated plan.

## Limitation

This is not a universal proof that no federated MMChain can ever be selected. It proves the
examined canonical `XtXv` fixture and protects all currently visible FED/direct-FOUT/materialized
paths by excluding every chain with a federated source from cost transfer. A future planner feature
that introduces an MMChain-specific physical alternative consuming `X` and the vector directly
must add assignment-dependent ownership for that new alternative rather than broadening this
source-pattern check.
