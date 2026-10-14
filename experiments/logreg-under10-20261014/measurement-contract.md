# LogReg under-10 candidate measurement contract

The reference is the already completed `logreg-bottleneck-after-01` JFR run. It is reused without
another workload execution. Its Memo planning time is 26.819446540 s and optimization time is
23.714206977 s.

Each tested candidate receives exactly one official Docker JFR run of `ml_logreg`, using the same
4 CPU, 8 GiB, three-worker dataset and timeout settings. There are no automatic retries. GLM and
host workloads are excluded.

A candidate passes only when all of these conditions hold:

- Memo planning is strictly less than 10.0 seconds and status is `OPTIMAL`.
- Exact objective, output CSV bytes, FED fingerprint, selected action diagnostics, runtime audit,
  Oracle hit/miss trace, native universe/requests, worker count, native-rule count, and supply count
  match the reference.
- JFR parses without data loss.
- The tested engine matches its manifest and the Docker `frozen-inputs` snapshot exactly.

Search node count is recorded but deliberately excluded from equality gates because the stronger
exact bound is intended to reduce it. Candidate timing is a single JFR observation, not a confidence
interval.

The 26.8 s reference has 1,696 optimizer samples. Its dominant inclusive paths are
`ReducedSearch.coreLowerBound` (923), `computeConditionalCostBound` (763), `visitJoint` (758),
`MemoBindingResolver.enumerate` (714), `movementGroupCost` (711), and
`visitBindingCostContexts` (543). To reach the 10 s gate, the candidate must remove at least
16.819446540 s of planning time, a 62.7137% reduction from the reference planning time. Because
non-optimization phases total 3.105239563 s in the reference, optimization must be below roughly
6.895 s unless those phases also improve.
