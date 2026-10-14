# Candidate15 Docker results

Each pinned Docker/JFR condition ran exactly once. LogReg planning was **3.521011574s** and GLM was **7.039366906s**. Both remain above 3 seconds.

| Metric | LogReg | GLM |
| --- | ---: | ---: |
| Facts | 0.052538586 | 0.074202202 |
| Rules | 1.734399609 | 3.390392459 |
| Model | 0.619207627 | 1.650163606 |
| Optimization | 1.041199662 | 1.812977919 |
| Projection | 0.073666090 | 0.111630720 |
| Total | 3.521011574 | 7.039366906 |
| C14 delta | -10.26% | +22.29% |
| CPU seconds | 21.45 | 33.20 |
| HWM KiB | 475,136 | 746,816 |
| JFR allocation bytes | 1,625,395,128 | 3,375,466,896 |
| GC STW seconds | 0.243912 | 0.354638 |

21/23 gates passed; only both time gates failed. Both optimizers were OPTIMAL with unchanged exact objectives/native universes and clean numeric/privacy/runtime audits. Frozen inputs matched all 12,657 files and JFR loss was zero. C15's single-run deltas versus C14 were -10.26% for LogReg and +22.29% for GLM; no causal attribution is made from one run.
