# Candidate13 Docker results

Each pinned Docker/JFR condition ran exactly once. LogReg planning was **3.657053118s** and GLM was **6.543782842s**. Both remain above 3 seconds.

| Metric | LogReg | GLM |
| --- | ---: | ---: |
| Facts | 0.057144431 | 0.047252267 |
| Rules | 1.711980919 | 2.862495619 |
| Model | 0.812238528 | 1.432279403 |
| Optimization | 0.996431126 | 2.110666630 |
| Projection | 0.079258114 | 0.091088923 |
| Total | 3.657053118 | 6.543782842 |
| C12 delta | -2.51% | -26.06% |
| C11 delta | -5.28% | -3.44% |
| CPU seconds | 22.24 | 29.84 |
| HWM KiB | 556,148 | 848,452 |
| JFR allocation bytes | 1,656,322,488 | 3,732,045,336 |
| GC STW seconds | 0.211885 | 0.286063 |

21/23 gates passed; only both time gates failed. Both optimizers were OPTIMAL with unchanged exact objective/native universe and clean numeric/privacy/runtime audits. Frozen inputs matched all 12,645 files and JFR loss was zero. GLM search traversal counters differ from C12 while the selected exact result remains equal; this is recorded without attributing one-shot elapsed differences to a single cause.
