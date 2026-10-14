# Candidate14 Docker results

Each pinned Docker/JFR condition ran exactly once. LogReg planning was **3.923526476s** and GLM was **5.756094881s**. Both remain above 3 seconds.

| Metric | LogReg | GLM |
| --- | ---: | ---: |
| Facts | 0.041028635 | 0.041670794 |
| Rules | 1.427772388 | 2.963907867 |
| Model | 0.717339087 | 1.137087199 |
| Optimization | 1.632351286 | 1.503597877 |
| Projection | 0.105035080 | 0.109831144 |
| Total | 3.923526476 | 5.756094881 |
| C13 delta | +7.29% | -12.04% |
| CPU seconds | 22.49 | 28.51 |
| HWM KiB | 477,492 | 695,564 |
| JFR allocation bytes | 1,601,462,672 | 3,494,074,712 |
| GC STW seconds | 0.272792 | 0.246944 |

21/23 gates passed; only both time gates failed. Both optimizers were OPTIMAL with unchanged exact objectives/native universes and clean numeric/privacy/runtime audits. Frozen inputs matched all 12,650 files and JFR loss was zero. C14's single-run LogReg time increased +7.29% while GLM decreased -12.04%; these one-shot differences are recorded without causal attribution.
