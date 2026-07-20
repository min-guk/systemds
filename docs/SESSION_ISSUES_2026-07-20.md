# Session Issues — 2026-07-20

## Typed federated cost configuration was owned by the DP cost model

- **Status**: Resolved
- **Environment/conditions**: DP planner cost configuration; JVM system properties and same-name environment variables; component guard tests.
- **Reproduction**: Run `mvn -q -DskipTests=false -Dtest=FederatedPlannerConfigurationContractTest,CampaignBDpExternalConfigurationBoundaryContractTest test`.
- **Observed symptom**: The neutral configuration boundary did not expose `captureDoublePropertyOrEnvironment(String, double)`, while `FederatedCostModel` retained a private parser and all thirteen cost constants called it directly.
- **Root cause**: Typed parsing was implemented inside the planner consumer instead of the planner-neutral external-configuration owner.
- **Resolution**: Added the typed acquisition method to `FederatedPlannerConfiguration` and routed all thirteen cost constants through it. The method defaults only for null/empty acquisition or `NumberFormatException`; otherwise it preserves `Double.parseDouble` behavior, including raw whitespace, NaN, infinities, zero, and negative values. Existing non-empty property-over-environment precedence is unchanged.
- **Modified files**:
  - `src/main/java/org/apache/sysds/conf/FederatedPlannerConfiguration.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
  - `docs/SESSION_ISSUES_2026-07-20.md`
- **Verification**:
  - PASS: focused configuration behavior and structural ownership tests.
  - PASS: `mvn -q -DskipTests compile`.
  - PASS: `git diff --check` for changed content.
  - Known baseline limitation: repository Checkstyle reports pre-existing violations across the selected production files and generated/resources content, so the Maven Checkstyle goal is not a clean project gate.
- **Remaining issues**: None in this scoped change.
- **Potential regression risk**: A future acquisition helper could trim or reject IEEE special values, or invert property/environment precedence. Detect with `FederatedPlannerConfigurationContractTest` raw-bit assertions and `CampaignBDpExternalConfigurationBoundaryContractTest` call-count/ownership assertions.
- **Decision basis**: Configuration-boundary ownership was corrected; planner legality, oracle rules, runtime behavior, candidate space, and fallback policy were not changed.
- **Applicable constraints**: DP-first scope; no runtime fallback; no candidate-space guard; no checkpoint, push, or cluster switch performed by this worker.
