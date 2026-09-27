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
- 검증: clean 44클래스 365건은 frozen baseline과 같은 실패 9/오류 3/skip 5. 신규 실패 없음. protected P/E 각각 216 proofs/56 physical, 전후 양방향 및 multiplicity 동일; P audit 216행의 파싱된 JSON 내용/순서는 동일하며 serialization만 다름.
- 잔여 이슈: 외부 C0 최종 수정이 도착했다. 이 checkpoint 뒤 별도 변경으로 반영하고 P3 전에 새 source/JAR를 고정한다. 기존의 stale fixture/manifest 문제는 별도로 진단한다.
- 잠재 회귀: owner별 commit 시점, exception/reuse cleanup, OR-of-AND 및 source reappearance. 기존 의미 회귀 및 frozen 전체 공간 비교로 확인한다.
- 의사결정: runtime/oracle 규칙을 바꾸지 않고 search-space 구성 책임을 분리했다.

## 외부 C0 최종 수정 수신 — 검증 완료

- 원본 correctness commit: `cfab6c8258c1525761b33ee8f6a6caa8c55c84b8`. 원본 engine은 수정하지 않았다.
- 변경: 별도 eager input-edge 순회를 제거하고 local-input proof와 materialization이 현재 commit된 inventory의 lazy edge index를 공유한다. 이 변경은 외부 correctness diff 47 additions/41 deletions와 동일하며, P1 이름/추출된 owner qualifier만 치환했다.
- 검증: frozen source checkout의 clean 48클래스 469건 실패/오류 0 (기존 public skip 6 + 외부 metadata 조건부 skip 2). 새 worktree의 같은 clean 48클래스 469건 실패/오류 0, 기존 skip 6. 원래 세 C0 메서드는 양쪽 모두 실행/통과했다. 각 source 전후 manifest가 동일하고 대응 JAR SHA는 evidence/c0에 저장했다.
- 경계: C0 수정 자체는 구조 리팩터링에 섞지 않고 별도 commit으로 관리한다. P3/P4는 이 기준을 사용한다. `ExactPhysicalModelCertificateTest`의 60M-cell limit 오류는 외부 expanded suite에서 원래 기준에도 재현된 별도 문제이며 cap을 올리지 않는다.
- 위험: commit 이후 오래된 inventory 재사용; 기존 `PhysicalGenerationEnvelopeTest` 및 immutable inventory/cold-owner 등가 테스트로 보호한다.

## 기존 확장 테스트 계약 정비 — 해결

- 원인: RuleFacts 검사가 물리 Hop arity만 읽어 CFG의 논리 입력을 놓쳤고, Oracle 이외의 literal source/CFG replay capability를 fresh Oracle와 무조건 비교했다. Resolver fixture는 함수 호출만 만들고 정의/출력 writer가 없었으며, builder 실행 전 traversal scratch 상태를 resolver 비변이 기준으로 잡았다. Privacy source guard는 삭제된 DP 목록 index와 옛 wrapper 위치를 참조했다. S2 graph-only fixture는 CFG writer Hop 대신 LiteralOp를 만들어 exact authority 검증에 걸렸다. 모두 P0에서도 재현됐다.
- 수정: compiler CFG의 definition owner와 durable anchor에서 독립 기대값을 얻어 typed evidence를 검사하고, 실제 함수 정의/출력 writer를 포함했다. Resolver 검사 기준은 분석 후로 이동하고 프로그램 구조 검사는 structural authority fingerprint를 쓴다. 현재 privacy 소유자/소비자 경계를 확인하며 S2는 exact compiled projection의 순서만 바꾼다.
- 수정 파일: `CampaignBG014PlacementCandidateRuleFactsSliceATest`, `CampaignBG014PlacementCandidateResolverSliceATest`, `CampaignBG011PrivacyResolverOwnerContractTest`, `PlacementAnalysisS2ContractTest`. Production은 바꾸지 않았다.
- 검증: 네 클래스 27/27 통과, 실패/오류/skip 0, source 전후 동일 (`evidence/p4/stale-contracts-four-v6.json`). 중간 fixture 구성 오류와 수정 과정의 raw XML은 별도 보존했다.
- 위험: source guard가 의미 회귀를 대체할 수 없다. frozen physical multiset 및 기존 writer/foreign-owner/OR-of-AND 의미 검사를 계속 적용한다.
