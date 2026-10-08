# Rule-directed generation baseline measurement

## Fixed execution surface

- Runtime entrypoint: `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e`
- Planner: `local` (`DP-LocalConflict`)
- Docker image: recorded by the generated runtime `manifest.json`
- Container limits: 4 CPUs, 8 GiB; coordinator JVM heap: 3 GiB
- Network: disabled except container loopback
- Engine inputs: frozen main/test classes, sources, and dependencies from
  `/grid/3/cofee-lm-sweep-mchoi-20260914/legal-combination-ids-20261008/engine`
- Cases: `joint_correlated_aa`, `joint_function_calls`
- Canonical proof and JFR are enabled for exact objective, plan/cost hashes, and
  coordinator heap observations. Comparisons must use the same flags.

The exact argv is stored in
`verification/measurement-canonical/command.json`. The runtime manifest stores
the image digest, artifact inventory digests, fixture hashes, generated script
hashes, and preflight class hashes.

## Metrics

1. **DML compilation time** is `fedStatistics.compilationSeconds` from
   `result.json`. This includes candidate planning and later compiler phases.
2. **Planning time** is the `CandidateE2EReceipt` in each FED log. Compare
   `totalNanos`, then `analysisNanos`; model, cost, optimizer, and selection are
   also retained separately.
3. **Candidate work** comes from the `fedplanner-candidate-space-v1` audit:
   audit rows, unique hop IDs, available rules, pre-privacy states, published
   states, and published exclusions.
4. **Exact solution identity** comes from canonical proof: analysis fingerprint,
   normalized-plan fingerprint, physical semantic DAG fingerprint, objective
   bits/milliseconds, decision and factor counts, and CP/FED output fingerprint.
5. **Memory** has three scopes:
   - canonical-proof `coordinatorMemory.sumOfHeapPoolPeakUsedBytes`: a JVM pool
     high-water sum; it is not a simultaneous heap snapshot;
   - JFR `GCHeapSummary`: maximum observed coordinator heap at GC event boundaries;
   - sampled Docker `MemUsage`: whole one-container cgroup including coordinator,
     workers, and harness processes. `docker stats --no-stream` makes the effective
     cadence slower than the requested 0.2-second post-sample sleep, so this is a
     lower-bound sample, not an exact peak.

`summarize_baseline.py` normalizes these sources into `baseline-result.json`.

## Known measurement gap

The unchanged Docker runner does not set `sysds.fedplanner.liveMetrics=true`.
Therefore the runtime baseline does not contain internal oracle call counts,
relocation-product prefix/leaves, memo hits, or MRV partial-evaluation counters.
The candidate audit counts above are stable external work proxies. An exact
oracle-work comparison requires a runner option that passes this JVM property;
adding that option belongs in the main runner implementation, outside this
read-only baseline lane.

## Reproduction

Run the command array in `verification/measurement-canonical/command.json`
through `monitor_docker_run.py` with a fresh `--run-id` and stage directory.
Then run:

```text
summarize_baseline.py \
  --run <runtime>/<run-id> \
  --monitor-summary <measurement>/monitor-summary.json \
  --output <verification>/baseline-result.json
```

Compare only runs with identical image, engine inventory, fixture hashes,
planner, cases, canonical-proof setting, JFR setting, CPU/memory limits, and JVM
heap limit.
