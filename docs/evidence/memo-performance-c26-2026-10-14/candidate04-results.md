# Candidate04 one-shot measurement

Both official frozen-engine JFR runs completed once without retries. All semantic, objective, output, audit, frozen-input, and JFR-integrity gates passed. The <=3 second performance gates failed.

| Workload | Planning | Rules | Model | Optimization | Projection | C03 delta |
|---|---:|---:|---:|---:|---:|---:|
| LogReg | 6.149657852s | 2.209057012s | 0.982948760s | 2.843966503s | 0.063812890s | -7.5050% |
| GLM | 19.737127325s | 6.579082723s | 2.259770072s | 10.723301464s | 0.097675124s | -47.0711% |

LogReg remained OPTIMAL at exact cost `1301.1526531142989790221751587629165844428591469750244868919253349304199218750`; CP/FED max absolute output difference was `1.6653345369377348e-16`. GLM remained OPTIMAL at exact cost `163.445631891948489909499669515659725158029935698777990182861685752868652343750000`; CP/FED max absolute difference was `9.020562075079397e-17`. Both runtime audits were clean.

C03 to C04 GLM sampled allocation fell 34.740 GB to 15.111 GB (-56.50%), observed whole-JVM CPU fell 81.88s to 58.61s, HWM fell 1,598,896 KiB to 1,375,668 KiB, and JFR STW pause fell 1.767s to 0.939s. These are separate one-shot profiled runs on a shared host, so the deltas are directional.

The new guard relevance cache saw 9,718,830 GLM requests, 9,716,286 hits, and 2,544 index entries. GLM phase-shape requests fell 653,342 to 626,530 and hits fell 200,813 to 174,001 while the completed cache stayed at 449,409 entries. Search nodes remained 885,887.

Residual GLM optimizer profile: `propagate` 271/744 inclusive samples (36.4%), `support` 217/744 (29.2%), movement group cost 104/744 (14.0%), reduced-search core lower bound 100/744 (13.4%), and binding `visitJoint` 100/744 (13.4%). Nearest exclusive buckets were cache/key/domain 154/744 (20.7%), solver-other 111/744 (14.9%), reduced-search control 111/744 (14.9%), optimizer-other 108/744 (14.5%), and shape/guard cost 60/744 (8.1%). Inclusive stacks overlap.

Evidence integrity is recorded in `candidate04-verification.json`; the exact C03 comparison is in `candidate04-comparison.json`.
