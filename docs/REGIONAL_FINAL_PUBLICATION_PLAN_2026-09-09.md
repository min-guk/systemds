# Regional / Global final publication

The user requested preserving earlier experiments as commits, while excluding superseded algorithm implementations from the final main tree. Archive commit: `6b2f32143d`.

## Cleanup plan

1. Preserve the current experimental snapshot and its reports in Git history before editing (done).
2. Keep the Global exact planner and the Regional seed followed by one global LB, a conservative 5% certificate check, and exact completion of all remaining replica equalities when needed. Retain a plain Regional baseline switch for diagnostics.
3. Remove superseded Certified/Anytime iteration controllers, adaptive Threshold, branching Target-gap, reuse search, and their dispatch/configuration paths and dedicated tests. Keep the shared factor/replica infrastructure required by the final algorithm.
4. Reuse existing regression tests for exact objective parity, certificate containment, interruption coverage, shared preparation and timing; adapt physical integration tests to retained paths and reject retired selectors explicitly. Do not change feasibility, privacy, placements or runtime behavior.
5. Integrate the selected remote main without discarding unrelated changes; run fresh targeted tests and package validation, inspect the final diff, then push normally. No force push.

## Existing behavior evidence before cleanup

The frozen shared-preparation build passed 109 tests in 11 classes. Its audited native planning-only pilot completed 27 trials across L2SVM, StepLM and GLM. L2SVM and StepLM used remaining exact completion; GLM returned with a 1.694623% initial certificate. Reports remain historical evidence, not claims about an untested post-cleanup build.

## Publication destination

The user confirmed publication to the existing main of GitHub min-guk/systemds, using the local github remote. The remote named origin points to so003 and is not the publication destination. Push normally without changing remote URLs or rewriting history.

## Completion evidence

Archive preserved; current GitHub main merged at 1caf7a1a30 without source conflicts. Superseded controllers removed, retained tests migrated. Fresh clean package: 145 tests in 17 classes, all passed. Four native planning-only JVMs on so007 completed; validated logs prove exact closure for L2SVM and first-bound return for GLM. Original collection failures for retired counters are preserved and corrected in a separate smoke-audit.json. See REGIONAL_FINAL_PUBLICATION_REPORT_2026-09-09_KO.md. Destination is confirmed; verify the remote main SHA after pushing.
