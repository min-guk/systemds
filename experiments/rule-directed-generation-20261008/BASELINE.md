# Baseline result

Status: **PASSED**

| Case | FED compile (s) | Candidate total (s) | Analysis (s) | Candidate audit rows | Pre/published states | Alternatives | Decisions | Objective ms | JFR heap max (MiB) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| joint_correlated_aa | 2.816938 | 2.241071 | 1.811923 | 90 | 159/63 | 277 | 63 | 4.000494324 | 143.72 |
| joint_function_calls | 3.025952 | 2.278168 | 1.837469 | 108 | 178/80 | 270 | 92 | 4.000562668 | 123.21 |

Whole-container sampled memory high-water: **842.00 MiB** across 15 samples; effective mean interval 2.005 s.

Both cases have matching CP/FED output fingerprints, canonical/certificate objectives, and valid canonical plan/cost fingerprints.

Internal oracle-call and product-prefix counters are unavailable because the unchanged runtime harness does not enable live metrics.
