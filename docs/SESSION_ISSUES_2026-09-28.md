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

## P2 책임 추출 및 기준 회귀 — 해결

- 증상/원인: 단일 builder가 compiler facts, Oracle tuple, 관계 수렴, 진단을 함께 소유해 변경 경계를 구별하기 어려웠다.
- 해결: 검토된 읽기/쓰기 표에 따라 package-private 소유자를 추출하고 L3의 실제 단계를 나눴다. 순서/identity/공개 API는 유지했다. 테스트의 private reflection 대상과 source guard만 새 소유자에 맞췄다.
- 검증: clean 44클래스 365건은 frozen baseline과 같은 실패 9/오류 3/skip 5. 신규 실패 없음. protected P/E 각각 216 proofs/56 physical, 전후 양방향 및 multiplicity 동일; P audit 원본은 별도 보존하며 raw byte 차이는 별도 분류.
- 잔여 이슈: 외부 C0 최종 수정이 도착했다. 이 checkpoint 뒤 별도 변경으로 반영하고 P3 전에 새 source/JAR를 고정한다. 기존의 stale fixture/manifest 문제는 별도로 진단한다.
- 잠재 회귀: owner별 commit 시점, exception/reuse cleanup, OR-of-AND 및 source reappearance. 기존 의미 회귀 및 frozen 전체 공간 비교로 확인한다.
- 의사결정: runtime/oracle 규칙을 바꾸지 않고 search-space 구성 책임을 분리했다.
