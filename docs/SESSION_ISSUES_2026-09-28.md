# Session issues — 2026-09-28

## Paper-aligned refactor in an independent workspace — in progress

- Problem: the 10k-line search-space builder combines compiler facts, candidate
  generation, relation convergence, observation and publication in one owner.
- Resolution: follow the approved P0–P5 plan in the independent
  `refactor/w1357-paper-aligned-20260928` worktree; preserve the original workspace.
- Baseline: imported correctness work is frozen separately in `aaa574ebb5`;
  its upstream session record is archived with the diagnostic evidence.
- Decision basis: preserve Oracle/runtime/authority contracts and physical plan
  sets while giving paper concepts explicit code responsibility boundaries.
- Files: see `PAPER_ALIGNED_REFACTOR_EXECUTION_2026-09-28.md` for the cleanup plan,
  state ownership table, evidence locations, reviewers and verification ledger.
- Remaining bugs: external C0 correctness prerequisite is not yet verified.
  P3/P4 must not proceed without its source-matched evidence.
- Regression risks: stale proof inventory, lost pending work, owner identity
  changes, candidate-dependent facts moved too early, and expired action removal
  deleting valid OR siblings. Existing semantic regression and multiset exports
  will detect these; assertion values and comparator remain unchanged.
- Verification: in progress. No success claim until fresh results are recorded.
