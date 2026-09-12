# Shared exact elimination-order policy

The common policy is configured with these JVM properties:

- `sysds.fedplanner.exact.fastOrder`: `true` tries `MIN_SEPARATOR_CELLS` first; `false` uses the existing four-order portfolio. The default is `false` for compatibility.
- `sysds.fedplanner.exact.fastOrderAssignments`: positive symbolic elimination-assignment limit. The default is `1000000` when the common fast order is enabled.

Global and Local use the same `ExactEliminationOrderPolicy.Configuration`. The policy runs after exact support reduction and quotient construction. For compact mode it runs after singleton substitution, so the estimate describes the model actually compiled.

The fast order is accepted only when all of these hold:

1. its symbolic elimination-assignment estimate is finite and no larger than the configured limit;
2. its maximum factor cells, including input factors, fit the existing maximum-factor limit; and
3. its input plus materialized elimination cells fit the existing total-cell limit.

If a valid fast candidate misses a bound, the unchanged deterministic portfolio selects the exact plan. Input validation happens before order selection. Invalid variables, factors, costs, limits, or property values fail closed and never become portfolio fallback. Both choices use the same exact solver and canonical tie-cost handling.

Every successful order selection emits `Exact-OrderSelection` with `caller`,
`fastOrderSource`, `fastOrderConfigured`, `fastOrderAssignmentsLimit`,
`fastOrderAccepted`, `fastOrderFallback`, `fastOrderEstimatedAssignments`, and
`compileNanos`. This includes Global, Local seed blocks, Regional remaining-exact
compilation, and incremental replica-bound exact compilation. A successfully reused
preferred replica-component order does not select a new order and therefore does not
emit this event; its ordinary fallback does. Global also emits `Exact-Preparation`.
Local emits singular fields in `DP-RegionalBlockPreparation`; shared Local preparation
emits effective configuration plus accepted/fallback counts in
`DP-RegionalSharedPreparation`, including a zero-block run.

For the matched comparison, bind `fastOrder=false` for the portfolio control and bind `fastOrder=true` with `fastOrderAssignments=1000000` for both Global and Dual500. Authenticate these properties in the native context, protocol, command receipt, and trace collector.
