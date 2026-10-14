# Candidate05 one-shot measurement

Both official frozen-engine JFR runs completed once without retries. All semantic, exact-objective, output, audit, construction/native-universe, frozen-input, and JFR-integrity gates passed. Both <=3 second gates failed.

| Workload | Planning | Rules | Model | Optimization | Projection | C04 delta |
|---|---:|---:|---:|---:|---:|---:|
| LogReg | 6.029726604s | 1.747147034s | 0.715002047s | 3.402542805s | 0.110390736s | -1.9502% |
| GLM | 15.831163478s | 6.861084711s | 2.034918229s | 6.763692807s | 0.111757520s | -19.7899% |

LogReg remained OPTIMAL at the unchanged exact cost `1301.1526531142989790221751587629165844428591469750244868919253349304199218750`; CP/FED max absolute output difference was `1.6653345369377348e-16`. GLM remained OPTIMAL at exact cost `163.445631891948489909499669515659725158029935698777990182861685752868652343750000`; CP/FED max absolute difference was `9.020562075079397e-17`. Both audits were clean.

C04 to C05 GLM optimization fell 10.723301464s to 6.763692807s (-36.93%), while Rules rose 4.29% to 6.861084711s and Model fell 9.95% to 2.034918229s. Total sampled allocation fell 15.111 GB to 11.008 GB (-27.15%), observed whole-JVM CPU fell 58.61s to 48.54s, JFR STW pause fell 0.939s to 0.846s, and observed HWM rose 1,375,668 KiB to 1,458,724 KiB. These are one-shot profiled measurements on a shared host.

The exact-positive binary support path recorded 9,543,637 hits over 4,093 binary rows for GLM and 1,403,221 hits over 2,095 rows for LogReg. GLM support queries fell from 1,684,831 to 114,117; support cache hits changed from 4,594,438 to 3,488,014; support witness hits rose from 19,632,157 to 22,309,295. Search nodes stayed 885,887.

Residual GLM optimizer inclusive profile: `propagate` 120/434 samples (27.65%), core lower bound 108/434 (24.88%), movement group cost 105/434 (24.19%), conditional bound chain 89/434 (20.51%), support 77/434 (17.74%), and cached domains 52/434 (11.98%). Inclusive stacks overlap. Exclusive nearest buckets were cache/key/domain 85/434 (19.59%), solver-other 79/434 (18.20%), reduced-search control 71/434 (16.36%), optimizer-other 58/434 (13.36%), and shape/guard cost 44/434 (10.14%).

Evidence integrity is recorded in `candidate05-verification.json`; the exact C04 comparison is in `candidate05-comparison.json`.
