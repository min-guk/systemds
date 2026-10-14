# Candidate12 Docker results

Each pinned Docker/JFR condition ran exactly once. LogReg planning was **3.751252388s** and GLM was **8.850169084s**; both remain above 3 seconds.

| Metric | LogReg | GLM |
| --- | ---: | ---: |
| Facts | 0.042075705 | 0.084287965 |
| Rules | 1.439945444 | 4.558514573 |
| Model | 0.707058536 | 1.851988468 |
| Optimization | 1.436372265 | 2.241847592 |
| Projection | 0.125800438 | 0.113530486 |
| Total | 3.751252388 | 8.850169084 |
| C11 delta | -2.84% | 30.59% |
| Coordinator CPU seconds | 23.29 | 36.68 |
| HWM KiB | 491,056 | 832,156 |
| JFR allocation bytes | 1,695,633,952 | 3,928,808,568 |
| GC STW seconds | 0.171650 | 0.283554 |

21/23 gates passed; only the two time gates failed. Both optimizers were OPTIMAL with unchanged exact objective/native universe and clean output/privacy/runtime audits. Frozen inputs matched all 12,642 manifest files and both JFR profiles had zero data-loss events. This is one observation per condition, so the GLM regression is reported as observed rather than attributed to one change.
