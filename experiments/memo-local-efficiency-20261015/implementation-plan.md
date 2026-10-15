# Certificate-first Local efficiency follow-up

Baseline: commit035f9c8eb76fc7cf793ffbde0127690ef131d16e, already pushed to origin/main. Frozen before-engine: ../memo-local-certificate-first-20261015/engine-v8. User authorizes autonomous improvement, correctness/performance validation and publication of verified work.

Contracts: preserve original candidates/costs/legality; no root exact call in Local; check the global5% certificate after each completed refinement; preserve UNKNOWN/unexplored cover; no successful time/iteration stopping. Existing Global/exact solvers and their seed paths remain unchanged. Benchmarks only through run_LAN_docker.sh. Parent exclusively owns Maven and Docker experiment launch.

First wave (behavior-preserving):
1. Local first-feasible/first-improvement seed APIs pre-index factors by assignment closing owner, avoiding repeated full scope scans/clones. Old seed APIs retain their path. Regression fixtures must compare identical deterministic witnesses, work limits, UNKNOWN/repair behavior and full original validation.
2. Message kernel caches exact root-factor minima and maintains root LB by exact subtract-consumed/add-message updates after atomic publication. Conditional queries retain their complete relaxed scan. Signed/UNKNOWN/constant/failure tests compare every step with independent enumeration or slow bounds. Deterministic active-factor removal avoids repeated list products.
3. Controller reuses fixed metadata estimates and invokes the dedicated root-bound query while at construction root. It still tests the certificate immediately after every message and original-validates every new UB.

Evaluate targeted tests first, then full Memo regression, frozen-engine Docker pilot. Retain only measured improvements and sound proofs. If promising, evaluate seven cold-JVM paired repetitions for LogReg/GLM/GNMF, default seed-off plus explicit stronger-seed ablations, actual small federated runtime, synthetic exact/oracle comparisons and heap checks. Use source-equivalent EXACT as independent optimum reference and audit all certificates/progression.

Second wave only if measured bottlenecks justify it: admitted per-factor evaluator reuse within message buckets, invariant/no-op owner work elimination, or reuse of reconstruction. Preserve complete ownership/backpointers and atomic memory admission; do not skip required certification checks. A gap-aware ordering heuristic needs its own correctness/seed-independent tests and ablation before adoption.

Stop after a verified, reproducible improvement is published and results/remaining regressions documented. If no safe measured win exists, keep published baseline and record rejected experiments. No claim of globally minimum work or arbitrary OOM recovery.
