# Session Issues — 2026-07-21

## MinST carrier accepted receipts from a foreign analysis owner

- **Status**: Resolved (Phase A carrier boundary)
- **Environment/conditions**: Campaign B/G012 MinST placement adapter; non-public exact-owner fixtures; Maven with dependency copy skipped (`-Dmdep.skip=true`).
- **Reproduction**: `mvn -q -Dmdep.skip=true -Dtest=MinStLayer1OwnerCarrierContractTest test`
- **Observed symptom**: `MinStPlacementInput.create(...)` accepted a `ProducerReceipt` with a foreign analysis fingerprint and accepted a value-equal copied `CompiledHopKey`; the new four-test Layer1 suite failed because no exception was thrown.
- **Cause analysis**: the carrier constructor checked only occurrence count. Later validation used `CompiledHopKey.equals`, so a copied descriptor could impersonate the canonical key owned by `PlacementAnalysis`.
- **Resolution summary**: validate producer fingerprint ownership and exact per-occurrence key/Hop identity when constructing the carrier, and repeat the owner check before stale-state validation. This rejects forged receipts without narrowing any planner candidate or changing runtime behavior.
- **Modified files**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/MinStPlacementInput.java`
  - `src/test/java/org/apache/sysds/test/component/federated/placement/core/MinStLayer1OwnerCarrierContractTest.java`
- **Verification**:
  - Layer1 carrier contract: 4/4 passed.
  - Focused exact-owner fixture methods in `CampaignBR4CostSelfTest`: passed.
  - `mvn -q -Dmdep.skip=true -DskipTests compile`: passed.
  - `git diff --check`: passed.
- **Remaining issues**: Phase B root cutover remains blocked because neutral `PlacementAnalysis` does not yet own the exact MinST edge-weight objective/source partition produced by the legacy graph min-cut. No alternative selector may substitute different semantics.
- **Potential regression risk**: any producer that reconstructs rather than preserves the canonical occurrence keys will now fail fast. Detect with the Layer1 carrier contract and exact-owner fixture tests.
- **Decision basis / applied constraint**: planner carrier ownership was repaired; no oracle relaxation, runtime fallback, candidate-space guard, or legacy graph cutover was introduced.
