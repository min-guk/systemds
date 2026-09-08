# Certified / Anytime Regional validation — 2026-09-08

## Artifact and scope

- Source: `/home/mchoi/so007-certified-regional-20260908` locally and on so007.
- Branch: `feature/certified-regional-20260908`, based on
  `ad5b3ba52fa6a59154e99a34d0a76641a01c1500`.
- Implementation commit: `6a2cf13a22`; the corresponding patch is retained as
  `validation/implementation.patch` in the evidence directory.
- Evidence: `/home/mchoi/so007-certified-regional-evidence-20260908`.
- Built on `dams-so007`, OpenJDK 17.0.20, Maven 3.9.7.
- JAR: `target/SystemDS.jar`, SHA-256
  `3ce3aa9ec8a95b6407afdc0bdf1f60b397ba414981e51bcd65fd5ed079f4d9b0`.
- All 7,488 files selected from `src/main`, `src/test` and `pom.xml` by the
  provenance script match between local and so007. The sorted source-manifest hash is
  `0045c4996688283964db06daa9d848d4796a6316280cccc617996b7ab0e941d3`.
  `validation/provenance.json` records individual file hashes and test results.

This validates a certificate for the existing nonnegative encoded cost model.
It does not establish runtime optimality, paper-scale speedups, or a hard wall-clock
deadline. Exact solve phases retain the existing non-preemptible behavior.
All experiments in this task are restricted to planning, as explicitly requested.

## Fresh build and regression evidence

On so007, run from the source directory:

```sh
/home/hadoop/apache-maven-3.9.7/bin/mvn -B \
  -Dtest-forkCount=1 -Dtest-threadCount=1 \
  -Dsysds.regional.ablation.output=/home/mchoi/so007-certified-regional-evidence-20260908/validation/physical-ablation.tsv \
  -Dtest=CertifiedRegionalOptimizerTest,MiniBucketLowerBoundTest,CertifiedRegionalPhysicalIntegrationTest,ExactCategoricalSolverTest,LocalCategoricalOptimizerTest,ExactPhysicalReducedSolverTest,ExactActivationIndependentOracleTest,ExactActivationClassFactorDecompositionTest,ExactMaterializationActivationTest,ExactActivationMaterializationCostTest,ExactCompiledMaterializationScopeTest,OccurrenceActivationContextTest,FederatedPlanLocalCostPrivacyConstraintTest \
  package
```

**BUILD SUCCESS: 97 tests, 0 failures, 0 errors, 0 skipped.** This includes fresh
main and test compilation. `git diff --check` passes. The Maven log and 13 JUnit XML
reports are retained under `validation/`; the full repository suite was not run.

Coverage includes 150 independently enumerated mini-bucket models across four
widths and 40 independently enumerated anytime models across three region policies.
Each published certificate is checked against the independent optimum, with
monotonicity, hard feasibility, exact completion, cancellation, invalid costs,
resource limits, disconnected variables and floating-point boundary cases.

A shared-producer synthetic barrier starts at U=8. One- and two-decision exact
regions retain U=8; the complete three-decision region improves it to U=2 and closes
the certificate at L=U=2. This checks the ability to cross a joint-decision barrier.

## Controlled physical compiler ablation

The PRIVATE_AGGREGATE fixture contains 41 original decisions and exercises the
exact activation auxiliary encoding. All eight rows use the same model and seed.
Structural, Random and Anytime use width 1→3 and cumulative regions 14→28→41;
GuidedFixed uses the same region sizes at width 1. Both tolerances are zero.

| Variant | Final L | Final U | Completed bound / region passes |
| --- | ---: | ---: | ---: |
| Regional | 0 | 2.5008440979570152 | 0 / 0 |
| Certify | 2.500844097956998 | 2.5008440979570152 | 1 / 0 |
| Bound-only | 2.5008440979570135 | 2.5008440979570152 | 3 / 0 |
| Structural | 2.5008440979570152 | 2.5008440979570152 | 3 / 3 |
| Random | 2.5008440979570152 | 2.5008440979570152 | 3 / 3 |
| GuidedFixed | 2.5008440979570152 | 2.5008440979570152 | 1 / 3 |
| Anytime | 2.5008440979570152 | 2.5008440979570152 | 3 / 3 |
| Global | 2.5008440979570152 | 2.5008440979570152 | independent exact solve |

The initial Regional assignment is already globally optimal in this fixture.
Certify and Bound-only preserve it exactly. The expanding variants reach the exact
endpoint. These observations validate integration and bound tightening; they do
not establish a region-selection advantage. The tiny residual in bound-only rows
is conservative floating-point arithmetic. Raw checkpoints and their summary are
`validation/physical-ablation.tsv` and `validation/physical-ablation-summary.json`.

The property-driven compiler test also validates the applied physical emission
receipt, canonical plan hash, complete decision coverage and privacy exclusions.

## Docker planning pilot

The isolated Docker pilot uses the same JAR and `run_LAN_docker.sh --planning-only`:
KMeans, PRIVATE_AGGREGATE P2P2D, 50,000 rows × 2,100 features, one worker placement,
LAN, campaign seed 2026072701 and one replication per variant. The encoded model
has 327 original decisions and 1,406 physical cost contributions. Traces include
canonical objective bits, the applied emission receipt and the full certificate
history. Compile-only receipts require zero execution time and no workload output.
Worker containers run `sleep infinity`; no worker JVM is started.

The new planning-stage schema is deliberately separate from the ordinary runtime
campaign schema. The byte-exact archived dataset records `max_workers=4`, and is
accepted only for the bound one-worker planning invocation. Its 1,031 input files,
sidecar, privacy metadata, seed streams, worker-1 federated descriptors and reference
payload are validated. The original publisher filesystem lifecycle cannot be
reconstructed from the archive; `publisher_lifecycle_verified=false` is included
in the content-addressed identity. Ordinary publisher/five-worker gates are
unchanged and reject this planning-only descriptor. No references were regenerated.

The final stage is
`g007-planning-stage-ce823de74bc0387ff2f493ada32f8d4465f3b36fcdb42156e5efa0b6b100d07a`,
with harness commit `0d2ebd0`. Its SystemDS Git snapshot `d66871f` is a clean
snapshot of the implementation source and built artifacts, not a replacement for
the implementation commit above. The JAR hash is identical. Compose v5.3.1 is
selected through an experiment-local Docker configuration; the image ID is
`sha256:26eaea7a28a130f2c4c2fd4492b74d0e32d4a1df4f1d3e4f0b28c15b59eca8cc`.

The harness passes **42 tests** and the collector **9 tests**, with shell syntax
and Python compilation checks. Initial smoke failures (missing Compose, then a
receipt CLI import and failure-branch error) remain recorded as failures. After
those fixes, the Regional smoke passed with planning time 0.686579 seconds and
zero execution time. That smoke is separate from the eight measured pilot rows.

The eight-row pilot uses width 2→4, three rounds, eight additional decisions per
region, a 64-decision region cap, a five-second soft refinement budget and zero
gap tolerances. GuidedFixed holds width 2; Certify performs one width-2 bound.
The bound table limits are 1,000,000 per factor and 5,000,000 total cells.
All reported planner times exclude Docker startup, stage hashing and receipt
verification. A single sequential pilot cannot establish a speedup or confidence
interval; the paired multi-seed campaign remains in the experimental plan.

Commands, metadata, validated receipts, raw traces, row results and the independent
stage/receipt review are retained under `docker/` in the evidence directory.
`harness-source-final.tar.gz` and `harness-patches/planning-stage.patch` preserve
the experimental harness. `snapshots/` contains byte-checked evidence copies;
`snapshot-files.json` maps them to the original so007 paths retained in receipts.

**Eight of eight rows passed.** The final machine-readable comparison is
`docker/runs/20260908t0351ablation-kmeans/comparison.json`. All ten comparison
checks pass: complete successful rows, matching model/analysis/stage, identical
Regional seed objectives, unchanged Certify/BoundOnly emission plans, compile-only
execution and `L <= C* <= U` at every checkpoint against the independent Global row.

| Variant | Planner seconds | Final L (modeled ms) | Final U (modeled ms) | Certified relative gap |
| --- | ---: | ---: | ---: | ---: |
| Regional | 0.683282 | — | 29701.732756 | — |
| Certify | 0.676193 | 28020.954361 | 29701.732756 | 5.998291% |
| BoundOnly | 0.743041 | 28020.954361 | 29701.732756 | 5.998291% |
| Structural | 1.049665 | 28020.954361 | 29701.732756 | 5.998291% |
| Random | 0.978961 | 28020.954361 | 29701.732756 | 5.998291% |
| GuidedFixed | 0.903047 | 28020.954361 | 29701.732756 | 5.998291% |
| Anytime | 0.912077 | 28020.954361 | 29701.732756 | 5.998291% |
| Global | 0.989851 | 29057.638458 | 29057.638458 | 0% (exact oracle) |

Unlike the small compiler fixture, KMeans has an actual Regional modeled regret
of **2.216609%** relative to Global. The certificate's **5.998291%** upper bound is
valid but looser. Width growth from 2 to 4 changes L only at floating-point scale.
All expanding policies complete three exact region passes, reaching 24 original
decisions, and stop at the iteration limit without improving U. This pilot does
not reach a 1% certificate and gives no evidence of a region-selection advantage.
The observed timing differences are single-run measurements, including the
slightly lower Certify time than Regional; they cannot establish zero certification
overhead or a speedup. Workload execution remains zero in every row.

See [the ablation plan](CERTIFIED_REGIONAL_ABLATION_PLAN.md) for the eight variants,
matched-budget controls, hypotheses, metrics and subsequent campaign design.
