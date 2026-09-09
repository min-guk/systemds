# Session Issues — 2026-08-29

## P2 worker=1 loses the runtime-supported FULL element-wise continuation

- **Status**: complete
- **Applied principle**: the shared candidate domain must expose every runtime-supported placement before FedAll, Heuristic, Exact, or DP selects a plan; this change repairs shared rule/candidate construction instead of adding a selector-specific fallback.
- **Environment/condition**: P2 preprocessing, `PRIVATE_AGGREGATE`, one federated worker, all four planners, LAN/WAN-Light/WAN-Mid.
- **Observed symptom**: one-worker P2 plans collect the large transform-encoded matrix `X0` at the coordinator. The resulting `CP prefetch X0` takes approximately 56–58 seconds in LAN and dominates the 75–79 second runtime. With one worker the input is classified as `FType.FULL`; with multiple workers it is normally `ROW`.
- **Root cause**: two shared-rule defects hid the runtime-supported plan. First, `BinaryElemwiseRule.profile()` did not emit FULL. Second, `caps()` tested ROW/COL alignment and vector topology before its FULL case. P2 deliberately leaves the transform-encoded width unknown, so those irrelevant probes recorded missing required shape facts; `NeutralPlacementGraphBuilder` consequently excluded the otherwise executable FED/FOUT/FULL alternative before selector search.
- **Resolution**: retain FULL in the producer profile and decide an exact FULL-compatible input assignment before ROW/COL shape probes. A FULL operand is compatible only with another FULL operand, a coordinator-local operand, or a replicated BROADCAST operand. FULL remains distinct from ROW/COL and is not used as an axis wildcard.
- **Modified files**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java`
  - `src/test/java/org/apache/sysds/test/functions/fedplanner/rules/BinaryElemwiseFullProfileTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacadeTest.java`
- **Verification**: focused profile/oracle tests pass, including unknown encoded width and negative ROW+FULL coverage. Runtime-plan audits on the immutable `18cad0fa...` JAR passed for all four planners with zero privacy/placement mismatches. The closure trace exposes FED/FOUT/FULL for the P2 clipping chain, and FedAll/Heuristic select it; Exact/DP legally retain the collected alternative. All 12 timing-only P2 worker=1 cells (four planners by three network profiles) completed with authenticated receipts and identical workload output.
- **Observed consequence**: exposing FULL restores the intended search space but does not make every FULL-heavy plan fast. FedAll/Heuristic maximize remote continuation and execute 61 FED instructions, including repeated movement/materialization of large FULL intermediates; their timing-only runtimes are 562.591/564.835 s on LAN and 620.786/620.381 s on WAN-Mid. Exact/DP account for these transitions and retain the lower-movement plan with four FED instructions, running in 76.122/77.556 s on LAN and 86.657/88.098 s on WAN-Mid. This is a selector-policy difference over one shared legal domain, not a runtime mismatch.
- **Artifacts**:
  - runtime audit: `/home/mchoi/g014-p2-pipeline-privateagg-27201f202a-20260827-v1/validation-p2-w1-full-caps-v29-18cad0fa/audit-summary.json`
  - timing campaign: `/home/mchoi/g014-p2-pipeline-privateagg-27201f202a-20260827-v1/runtime-p2-w1-full-caps-campaign-v29-18cad0fa`
  - merged 96-cell view: `/home/mchoi/g014-p2-pipeline-privateagg-27201f202a-20260827-v1/runtime-p1full-p2prep-final-v29-p2w1-full-caps-18cad0fa`
- **Potential regression risk**: an over-broad FULL profile could admit incompatible partition combinations. Detection is guarded by negative ROW+FULL coverage and by runtime-plan audit; exact input combinations remain checked by `caps()`.
- **Decision basis**: modify the shared rule profile because runtime and capability support already exist; do not patch selector policy or runtime fallback behavior.
