# Session issues — 2026-09-28

## 통합된 C0 correctness 세션 기록

`origin/main`의 `cfab6c8258`에서 추가된 세션 문서는 별도 correctness 작업의
기록이다. 내용을 빠뜨리지 않도록 [원문 전체](CORRECTNESS_SESSION_ISSUES_2026-09-28.md)를
그대로 보존했다. 원문의 당시 파일명·검증 경로와 미완료 범위는 역사적 기록이며,
리팩터링 이후 상태와 검증은 이 문서 및 최종 보고서를 따른다.

## Paper-aligned refactor in an independent workspace — initial issue

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
- Initial blocker: external C0 was not verified at startup. The later C0 entry
  records its resolution before P3/P4 proceeded.
- Regression risks: stale proof inventory, lost pending work, owner identity
  changes, candidate-dependent facts moved too early, and expired action removal
  deleting valid OR siblings. Existing semantic regression and multiset exports
  will detect these; assertion values and comparator remain unchanged.
- Verification at this checkpoint: pending. Final evidence is recorded in the
  subsequent entries and the paper-aligned report.

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

## 최종 architecture guard의 오래된 계약 — 해결

- 증상: 첫 최종 clean 73-suite 검사에서 576건 중 guard 세 건 실패. Production 의미 검사는 통과했다. 수정 중에는 package-private fingerprint helper를 하위 test package에서 호출한 compile 오류도 발견했다.
- 원인: shape carrier의 현재/원본 concrete map을 하나로 세던 검사, 정당한 분석 method parameter를 alternate map owner로 오인한 정규식, `equals(Object)`까지 차단한 전체 클래스 token 검사, `CompiledHopKey`를 `Hop`으로 인식한 부분 문자열 검사, builder 전후 traversal scratch를 의미 구조와 혼동한 fingerprint 검사였다. 해당 production 파일은 C0와 동일하다.
- 해결: 네 typed immutable map을 정확히 요구하고 map 선언과 모든 constructor signature/body를 검사한다. typed/erased seam 및 정확한 selector 타입의 양성·음성 fixture를 유지한다. builder parity 후에는 공개 `assertProgramStructureUnchanged()`로 structural authority를 검증한다.
- 파일: `PlacementAnalysisConstructionArchitectureTest`, `PlacementFoundationArchitectureGuardTest`. Production visibility와 계약은 변경하지 않았다. Docker wrapper의 설명도 실제 selector-free common search-space 실행과 일치시켰다.
- 검증: 두 guard 클래스 11/11 통과, 실패/오류/skip 0, source 전후 동일 (`evidence/final/architecture-guards-v2.json`). 전체 clean 재검증 결과는 최종 항목에 기록한다.
- 잔여 위험: source guard는 실제 physical 동등성 검사를 대체하지 않는다. 최종 protected export 및 동일 Docker 측정을 별도로 수행한다.

## 최종 기능 검증 — 완료

- 결과: `cab3ca86b6`의 clean test + jar:jar는 73 suites / 576건, 실패 0·오류 0·기존 public-only skip 10. source/POM 전후 SHA가 동일하다. 별도 protected export 네 메서드는 4/4 통과했다.
- 동등성: 초기 P0와 최종 C0 각각에 대해 최종 P/E의 216 proofs / 56 physical identities가 양방향·multiplicity로 동일하며, P↔E도 같다. 기존 B-21 192/36 assertion을 유지했다. P audit 216행은 파싱한 내용과 순서가 같고 raw serialization은 다르다.
- 검사 정비: 앞선 fixture/source guard 실패는 모두 해결됐다. 대상 suite에 미해결 실패를 남기지 않았다. 범위 밖 60M-cell certificate 문제와 외부 Sun 스타일 findings는 별도 한계로 남는다.
- 산출물: source/JAR hash, clean XML/log, 새 NDJSON, 다섯 비교 receipt가 `evidence/final`에 있다. 최종 JAR SHA `a2417987edcfea6a87691939f1a4bc57f22480372d228f5581522614cce4447a`.

## P5 OFF 시간 증가 — 조사 완료, 원인 확정 한계

- 증상: 동일 Docker·입력·worker·JVM 조건의 새 JVM 한 번씩에서 공통 search-space OFF 시간 28.004892853→30.975320421초, +10.6068%. 계획의 5% 조사 기준을 초과했다.
- 조사: 증가 2.97초는 analysis 구간이다. Source/JAR/input/image/network provenance와 cleanup을 검증했다. ON의 모든 phase 호출 및 candidate/closure/proof 횟수는 같다. OFF GC pause는 양쪽 16회, 누적 623.257→608.088ms로 감소했고 heap 관측 최댓값도 -0.2858%다. 추가 pass/proof 폭증이나 GC pause 증가가 지연을 설명하지 않는다.
- 한계: ON 단일 관측은 32.716452974→29.201786084초로 반대 방향이다. 이를 OFF 대신 사용하지 않는다. OFF에는 세부 CPU/JIT profile이 없어서 정확한 원인은 확정할 수 없다. JIT/host 변동은 가설이며 속도 개선·시간 동등성·통계적 유의성을 주장하지 않는다.
- 처리: 불리한 OFF 표본을 보존하고 좋은 결과를 고르는 재측정은 하지 않았다. runtime/selector 비용으로 생성 비용을 옮기지 않았고 paused 20초 목표를 재개하지 않았다. 이 조사는 구조 리팩터링의 검증 범위이며 별도 성능 최적화를 섞지 않았다.
- 파일/근거: `scripts/fedplanner/run_LAN_docker.sh`, 최종 paper-aligned 보고서, `perf-harness/p5-comparison.json`, `evidence/final/P5_PERFORMANCE_INVESTIGATION.md`. 원본 run receipts와 GC 로그를 보존했다.
- 잔여 위험: 대표 한 조건의 시간 증가 및 실제 peak heap을 직접 측정하지 못한 한계. GC 경계/종료 시점의 관측 high-water를 정확한 peak로 표시하지 않는다.

- 독립 최종 감사 보완: raw metric 100개 중 identity cache hit -49,045(-0.2476%), structural cache hit +1의 차이는 보존된 구성 횟수와 따로 기록했다. 소스/bytecode 감사에서 중복 pass·OFF 진단 생성·cache 수명 오류는 발견하지 못했다. local→field, 소규모 owner 객체, method/class 분리의 실행 차이는 있지만 OFF 증가의 원인으로 입증되지 않았다.

## origin/main 통합 — 검증 완료

- 요청/조건: 최신 `origin/main`의 `cfab6c8258`을 리팩터링 브랜치에 merge한 뒤 원격 main에 일반 push한다.
- 증상/원인: 이전 builder에 들어온 C0 수정과 추출된 소유자, loop 회귀의 호출 클래스명, 양쪽에서 추가한 세션 문서가 충돌했다. C0 production 변경은 별도 exact import와 기존 C0 gate로 이미 반영된 상태다.
- 해결: 190행 공개 builder와 추출된 관계 소유자를 유지한다. Loop 회귀는 원격 파일에서 helper 소유자 두 이름만 치환한 내용과 현재 파일이 정확히 같음을 확인해 assertion을 그대로 보존했다. 원격의 문서 149행은 연결된 `CORRECTNESS_SESSION_ISSUES_2026-09-28.md`에 원문 bytes로 보존했다.
- 수정 파일: merge 이력과 두 세션 문서. Production·test·POM·실험 script는 merge 전 검증한 tree와 동일하게 유지한다. Oracle/runtime/privacy/TR-TW/recompile 계약을 바꾸지 않는다.
- 검증: 독립 reviewer가 incoming builder 8개 hunk의 반영을 확인해 승인했다. 관련 6개 클래스 48/48 통과, 실패/오류/skip 0, source/POM 전후 동일. Source·test·POM·script는 이전 최종 검증 tree와 같고 JAR SHA `a2417987edcfea6a87691939f1a4bc57f22480372d228f5581522614cce4447a`도 유지됐다. Unresolved index와 conflict marker가 없고 diff-check가 통과했다. 증거는 `/home/mchoi/w1357-diagnostics/paper-refactor-20260928/evidence/merge-origin-main-20260928/`에 저장한다. 원격 게시 결과는 같은 디렉터리의 별도 receipt로 기록한다.
- 잔여 위험: 원격 main이 검증 중 갱신되면 일반 push가 거절할 수 있다. 강제 push 없이 다시 통합한다. 기존 OFF 단일 시간 증가와 정적 검사 한계는 최종 보고서 그대로 유지한다.

## FedFirst · AggLocal 현재 구현 알고리즘 문서화

- **상태:** 해결 — 보고서 작성 및 독립 정적 검토 완료.
- **문제 정의/증상:** 사용자가 이 worktree의 두 planner 알고리즘을 설명하는 한국어 파일을 요청했다. MaxFedFout/SinglePass라는 클래스명과 과거 설명만 보면 전역 최적화·엄격한 단일 순회·AggLocal 전체 MOVEMENT_FIRST로 오해할 수 있다.
- **환경/조건:** HEAD fe758c413d4d156cec249248f99a744eaa45edb5. 현재 production 호출과 기존 테스트 assertion을 읽기 전용으로 조사한다.
- **원인 분석:** 실제 구현은 공통 후보 공간에서 first-feasible 상태/row 탐색을 수행하며 backtracking을 포함한다. AggLocal은 기본 FEDERATED_FIRST + 정책 투영이고, 지정된 strict-view 불가능 조건에서만 base graph의 MOVEMENT_FIRST로 정책을 완화한다. PolicyCandidateSelectionView의 materialization-maximal 주석과 현재 호출 경로도 구별해야 한다.
- **해결/변경 요약:** 설정·공통 분석·상태 의미·두 알고리즘의 의사코드·marker/local-prefix/reentry·정책 완화·atomic emission·비교표·복잡도·한계를 코드 행 근거와 함께 보고서에 작성했다.
- **의사결정 근거:** Oracle/runtime/privacy/플래너 정책은 변경하지 않는다. 지침이나 과거 문서가 아닌 현재 공통 후보 생성과 실제 production 호출을 구현 설명의 근거로 삼는다.
- **수정 파일:** docs/FEDFIRST_AGGLOCAL_ALGORITHM_REPORT_2026-09-28.md, 본 세션 문서. Java·테스트·POM·설정 변경 없음.
- **검증 방법/결과:** 보고서의 소스 링크 33개와 행 범위, 근거 ID, 코드 fence를 검사해 통과했다. Java 테스트와 Docker/runtime/성능 실험은 수행하지 않았으며 보고서에도 명시했다. 독립 verifier가 핵심 알고리즘·합법성·예외 분기를 현재 코드와 대조해 APPROVE했다. 최종 문서 검사와 git diff --check를 통과했고 source/test/POM/conf 변경이 없음을 확인했다.
- **잔여 버그/한계:** 코드 버그 수정 작업이 아니며 신규 runtime 버그를 확정하지 않았다. 정책 완화 branch coverage, 전체 workload 성공, 논문 대비 동등성 및 성능 우열은 이번 문서의 검증 범위 밖이다.
- **잠재 회귀 위험/감지:** 실행 코드 변경에 따른 회귀는 없으나 향후 source 변경 시 보고서 행 번호·설명은 낡을 수 있다. 보고서의 기준 HEAD와 실제 checkout을 대조하고 링크/선택 경로를 다시 확인한다.

## FedFirst · AggLocal 품질 검토 및 큰 component 스택 실패 — 발견, 미수정

- **상태:** 검토·재현 완료, 구현 수정은 하지 않음. 최종 판정 REQUEST CHANGES.
- **문제 정의/증상:** 사용자 요청에 따라 현재 두 정책의 품질을 점검했다. 4,096개 decision의 표준 CONJUNCTIVE 연결 합성 그래프에서 공통 selector가 기본 JVM stack으로 StackOverflowError를 발생시켰다.
- **환경/조건:** HEAD fe758c413d4d156cec249248f99a744eaa45edb5, OpenJDK 17.0.20.1, 기본 ThreadStackSize=1024 KiB, -Xmx512m. graph-only selector 경계이며 실제 대규모 DML/Docker 실행 재현은 아니다.
- **재현:** docs/FEDFIRST_AGGLOCAL_REVIEW_2026-09-28.md의 명령과 /home/mchoi/w1357-diagnostics/fedpolicy-review-20260928-n_odjzle/PolicyProductionRelationDepthProbe.java 참조.
- **원인 분석:** PolicyFirstFeasiblePlacementSelector.Solver.choose가 decision group마다 재귀한다(475–520행, 재귀 호출 512행). all-FED 첫 할당이 그래프 제약을 만족하는데도 스택을 소진했다. 기본 512개는 통과했고 동일 4,096개의 -Xss4m 대조는 통과했다. 스택 증가는 진단용이며 수정이나 성공 대체 근거로 채택하지 않는다.
- **해결/변경 요약:** 코드 변경 없이 raw 실패·대조·JUnit 증거와 최소 수정 방향(명시적 DFS frame stack 및 회귀)을 별도 검토 보고서에 기록했다.
- **추가 확인:** AggLocal 전체 정책 완화는 의도된 compiler-time 경로이므로 불법 runtime fallback으로 단정하지 않았다. 다만 positive relaxation/row-backtracking 전용 회귀가 부족하다. FedAll 2-node certificate에서 graphComponentCount=2, boundComponents=1을 재현했으며 서로 다른 연결 지표의 의미 불명확성으로 한정했다. fallback counter 의미와 stale 주석도 기록했다.
- **수정 파일:** docs/FEDFIRST_AGGLOCAL_REVIEW_2026-09-28.md 및 본 세션 문서. source/test/POM/conf는 SHA manifest 전후 동일.
- **검증:** 선택한 JUnit 21건 모두 통과, 실패/오류/skip 0. public 케이스는 기존 지침에 따라 실행 선택에서 제외했다. 독립 code-reviewer는 HIGH 1/MEDIUM 2/LOW 3, REQUEST CHANGES. 별도 architect agent는 thread limit으로 두 번 생성 실패하여 독립 구조 검토 미완료를 명시하고 대체 승인을 하지 않았다.
- **잔여 버그/한계:** 재귀 스택 실패는 미수정. 실제 DML 규모 재현, 전체 suite, clean build, Docker runtime/수치 결과/성능, public fixture 및 정책 완화 true branch 실행은 이번 범위 밖이다.
- **잠재 회귀 위험/감지:** 향후 iterative DFS 변경 시 domain trail·assigned singleton·witness 복원 오류 가능성이 있다. 기존 first-feasible/backtracking 테스트와 4,096+ 연결 그래프 및 row-witness 실패 후 복원 회귀로 보호해야 한다.
- **의사결정 근거:** current policy 계약과 common legality를 분리해 평가했다. upstream과 다름 자체를 버그로 판단하지 않았고, Oracle/runtime/privacy를 바꾸거나 resource cap으로 실패를 덮지 않았다.

## FedFirst · AggLocal upstream 철학 기반 경량화 계획 — 계획 완료, 구현 미착수

- **상태:** v1 계획의 역사적 기록. 아래 **공통 전체 후보 생성 유지 v2**가 현재 유효한 계획이며, 이 항목의 demand-driven 생성/authority 분리 제안은 최신 사용자 지시로 철회됐다. 구현·신규 테스트·성능 실험은 수행하지 않았다.
- **문제 정의/증상:** 사용자는 upstream의 정책 철학 아래에서 FedFirst/AggLocal을 시간 복잡도가 가벼운 single-pass/online에 가까운 방식으로 바꾸기를 요청했다. 전역 정책 최적성은 요구하지 않았다. 기존 first-feasible의 state/row/relocation backtracking과 AggLocal global local-prefix 계산은 이 목표와 다르다.
- **환경/조건:** HEAD fe758c413d4d156cec249248f99a744eaa45edb5. 공식 Apache/SystemDS b82911b0a032e59a01bcba3d669fd457e9e0042c의 FedAll/FedHeuristic/AFederatedPlanner 원문을 다시 확인했다. upstream raw 파일·URL·SHA는 /home/mchoi/w1357-diagnostics/fedpolicy-plan-20260928-q1x3hukf/에 보존했다.
- **원인 분석:** 반복형 DFS는 스택 문제만 고치며 지수적 조합 탐색 가능성은 남긴다. 공통 authority가 planner 진입 전에 입력 tuple 전체·closure·AggLocal 경로 facts를 eager 생성하므로 selector 교체만으로 전체 분석 비용을 줄일 수 없다. 현재 Docker wrapper도 selector/runtime이 아닌 공통 분석 전용이다.
- **해결/변경 요약:** 상태·exact candidate·입력 binding·relocation을 함께 확정하는 producer-first greedy 계획을 작성했다. FedFirst는 실제 입력에 조건부인 FED 우선, AggLocal은 aggregate-vector의 local 출력 선호로 단순화한다. 후손 전체 CP 강제와 global MOVEMENT_FIRST 재시작은 새 정책 경로에서 제거할 대상으로 정했다. authority-owned demand proof와 DP/Exact full view를 분리하되 같은 legality/rule 의미를 유지한다.
- **계획 검토 중 발견·해결:** 1차 reviewer가 future fact에 의존하는 미확정 unit의 보류/재개 규칙 누락을 지적했다. DEFERRED(waitingOn fact/version), 역색인 wake-up, 중복 제거, 유한 fact 전이 상한, no-progress SCC 종료와 A14 회귀 기준을 추가했다. 재검토 READY_WITH_RISKS. architect는 일반 completeness와 경량 greedy의 긴장을 WATCH로 명시했다.
- **수정 파일:** .omx/plans/fedfirst-agglocal-streaming-2026-09-28.md(작업 계획), docs/FEDFIRST_AGGLOCAL_STREAMING_PLAN_2026-09-28.md(공유용 snapshot), 본 세션 문서. Java/test/POM/conf/scripts 변경 없음.
- **검증 방법/결과:** 현재 코드의 비용·authority·emission 경계를 읽기 전용 대조하고 upstream 원문을 재조회했다. Java 소스14개·행 범위30개, upstream 파일3개의 SHA, 내부 링크4개·fence·공백·snapshot 일치, 소스 무변경 및 git diff --check를 확인했다. 결과는 같은 증거 디렉터리의 plan-verification.json에 기록했다. 이 턴에서 JUnit/Docker를 실행한 것으로 보고하지 않는다.
- **잔여 이슈:** 이전 StackOverflowError는 여전히 미수정이다. 새 경계별 transfer rule/lattice 높이 및 선택 증거의 합성, lazy/full 동등성, 실제 복잡도·성능·수치 정확성은 P0–P7 구현/검증 대상으로 남는다. researcher 추가 생성은 thread limit으로 실패해 root가 공식 원문 확인을 수행했다.
- **잠재 회귀 위험/감지:** irreversible greedy가 기존 성공 workload를 막을 수 있다. 이를 infeasible로 위장하거나 expected-failure로 바꿔 통과시키지 않는다. 기존 protected 성공 corpus 감소0, DP/Exact full-domain parity, privacy/TR-TW/anchor/atomic emission, counter 상한과 Docker end-to-end를 기본 전환 게이트로 삼았다.
- **의사결정 근거:** 최적성 포기와 합법성·기존 지원 포기는 별개다. runtime/Oracle 규칙을 완화하지 않고 정책 선택 방식을 단순화한다. 요청받은 계획까지만 작성하며 기존 별도 paused 성능 goal을 재개하지 않는다.

## FedFirst · AggLocal 공통 전체 후보 생성 유지 v2 — 계획 개정, 구현 미착수

- **상태:** 최신 사용자 지시를 반영해 계획 개정. 구현·신규 JUnit·Docker 실행 없음.
- **문제 정의/증상:** 사용자가 FedFirst/AggLocal도 후보 조합을 전부 생성하는 공통 경로를 그대로 공유하라고 명시했다. v1의 heuristic 전용 demand-driven proof/authority 분리 계획은 이 요구와 맞지 않는다.
- **환경/조건:** 동일 HEAD fe758c413d4d156cec249248f99a744eaa45edb5. 기존 전체 후보 생성/closure/PlacementAnalysis 소유 계약과 adapter 호출을 읽기 전용 재확인했다.
- **원인 분석:** 생성 비용까지 줄이려던 이전 제안이 사용자가 원하는 공유 구조보다 변경 범위를 넓혔다. 전체 후보 생성과 생성된 후보 위의 전역 계획 조합 탐색은 별개의 단계다.
- **해결/변경 요약:** 모든 planner의 전체 공통 후보·증거 생성, Oracle, closure, analysis authority를 유지한다. 기존 eager heuristic path facts도 생성은 유지하되 새 AggLocal 선택에 사용하지 않는다. selector의 invocation-local 읽기 전용 인덱스, exact state/row/action 동시 greedy 선택, AggLocal 지역 LOUT 선호, indexed 검증/관측으로 범위를 좁혔다. lazy 생성·full/demand authority 분리·builder 변경 단계는 철회했다.
- **복잡도 계약:** T_total=T_common+T_index+T_select+T_validate_emit로 분리한다. 목표는 생성된 후보/증거 C/S/M에 가까운 선형 선택 overhead이며, 전체 공통 생성까지 HOP 수에 선형 또는 streaming이라고 주장하지 않는다. 전체 생성 비용·메모리는 그대로 계측한다.
- **수정 파일:** .omx/plans/fedfirst-agglocal-streaming-2026-09-28.md, docs/FEDFIRST_AGGLOCAL_STREAMING_PLAN_2026-09-28.md, 본 세션 문서. Java/test/POM/conf/scripts 수정 없음.
- **검증 방법/결과:** v1 원본과 source-before manifest를 /home/mchoi/w1357-diagnostics/fedpolicy-shared-plan-20260928-hdbteu18/에 보존했다. v2 독립 재검토는 READY_WITH_RISKS이며 v1 판정을 재사용하지 않았다. v2 사본/내부 링크/fence/공백, 활성 P0–P5와 lazy 생성 단계 제거, source/test/POM/conf/scripts의 tracked-file SHA 무변이 및 git diff --check를 확인했다. 결과는 같은 디렉터리의 plan-v2-verification.json에 기록했다.
- **잔여 이슈:** 소스의 기존 StackOverflowError는 미수정이다. boundary별 증거 합성·유한 재평가 상한, 실제 selector 비용 감소 및 수치 정확성은 새 P0–P5 구현/검증 대상이다. full 후보 생성이 전체 시간을 지배하는 경우 전체 속도 개선은 작을 수 있다.
- **잠재 회귀 위험/감지:** read-only index가 canonical identity를 바꾸거나 원본 domain을 축소하는 오류, row/action의 숨은 재탐색, greedy 지원 감소를 후보 inventory/fingerprint 불변·Oracle 재호출0·전역 탐색0·기존 성공 corpus 감소0·DP/Exact parity로 검출한다.
- **의사결정 근거:** 후보 생성은 공통으로 보존하고 휴리스틱 선택만 단순화한다. 최적성은 목표가 아니지만 privacy/TR-TW/anchor/runtime 실행 가능성 및 기존 지원을 낮춰 성공시키지 않는다.

## FedFirst/AggLocal v2 구현 중 발견사항 — 해결 (최종 검증 기록 참조)

- **문제/증상**: 새 greedy selector에서 supportClause만 인덱싱하자 legacy unbound unary row가
  실제 FULL 입력에 BROADCAST 실행을 선택했고 최종 validator가 차단했다. 공유 함수 actual은
  LOUT→명시적 재업로드로 합법이지만 기존 FOUT 유지 계약보다 불필요한 이동을 만들었다.
- **원인**: exact input binding이 없는 공통 후보도 존재한다. 또한 AggLocal의 shape-only 우선순위는
  바로 다음 consumer가 PRESENT만 허용하는 경우까지 LOUT을 선호했다.
- **해결**: compiled input edge와 기존 action match/privacy 의미에 따른 residual 지원관계를 추가하고,
  보호 데이터 direct suppression은 exact pool로 조인한다. consumer의 local-input 대안이 없는 경우
  native FOUT 유지 선호가 우선한다. 공통 후보를 제거하거나 오라클/런타임을 완화하지 않는다.
- **수정 파일**: `PolicyGreedyPlacementSelector.java`, 두 placement adapter,
  `CandidateSelections.java`, `RelocationSelections.java`, `PlacementIdentity.java`, 관련 테스트.
- **검증**: `target/fedpolicy-greedy-20260928/greedy-4.log` 3/3 PASS;
  `cutover-1.log`의 PRIVATE_AGGREGATE 4-planner 11/11 PASS. 전체 재검증 진행중.
- **잔여 이슈**: Docker 실행과 독립 재리뷰 대기. 임의 reconvergent CSP에 대한 greedy 완전성은
  보장하지 않으며 실패는 `GreedyConflictException`으로, 전역 불가능 판정이 아닌 것으로 구별한다.
- **잠재 회귀 위험/감지**: pool/CFG 지원누락으로 합법 프로그램을 선택하지 못할 수 있으므로 기존
  보호 데이터 성공 corpus 및 exact 최종 witness 검사를 유지한다. 오래된 materialization-maximal
  결과 동일성 테스트는 새 비최적 정책과 충돌하여 정확한 합법성 검사로 교체했다.
- **판단 근거**: selector의 선택/인덱스 경계만 변경. common full candidates, Oracle, runtime authority,
  TR/TW 및 recompile 규칙은 그대로 유지한다. runtime fallback은 추가하지 않았다.

## FedFirst/AggLocal v2 — exact complete authority와 순환 grounding 보강

- **상태:** 수정·검증 완료. 아래 최종 검증 기록 참조.
- **증상:** 독립 리뷰에서 (1) complete selected validator가 partial `allowUnassigned=true`를 재사용함,
  (2) 구조적으로 동일한 foreign assignment key를 ordinary map lookup으로 수용함,
  (3) canonical cycle 순서를 proof seed로 오해할 수 있음, (4) local function boundary edge 누락을 발견했다.
- **원인:** partial DP recurrence와 complete plan의 증명 범위가 다르고, 값 동등성과 소유 identity도 다르다.
  또한 domain의 모든 대안을 합친 scheduling graph와 최종 선택된 proof graph는 같지 않다.
  logicalBoundaryRealizations의 pool `relations()`는 local-only 값을 포함하지 않는다.
- **해결:** complete path에 total exact key/state identity와 `allowUnassigned=false`를 적용했다. DP partial path는
  그대로 유지했다. scheduling은 반복형 SCC condensation으로, grounding은 선택된 row/support와 실제
  compiler-owned value edge로 따로 검사한다. local boundary도 `sources(target)`로 포함한다.
  anchor layout, 미선택 OR clause, generic 상태 제약을 값 seed로 빌리지 않는다.
- **실패 처리:** 외부 entry 없는 선택 proof SCC는 `UnresolvedBoundaryContractException`이다. 전역 불가능
  증명이 아니며 emission 전 종료한다. runtime fallback·old selector/DP/Exact retry는 추가하지 않았다.
- **수정 파일:** `CandidateSelections.java`, `PolicyGreedyPlacementSelector.java`,
  `PolicyGreedyPlacementSelectorTest.java`, `PolicyGreedyGroundingTest.java`.
- **검증:** `grounding-2.log`에서 seeded/seedless exact cycle 성공/거절과 기존 FourPlanner11/중첩3/greedy10을
  확인했다. OR-clause fixture의 누락된 physical edge는 test authority 생성 단계에서 거절되어 정확한 edge를
  추가했다. `final-targeted-1.log` 124/124 + package PASS. foreign-key/local-boundary 추가 회귀를 포함한
  최종 결과는 구현 보고서와 `final-targeted-2.log`에 기록한다.
- **잔여 한계:** 입력이 frozen eager analysis인 이 구현은 계획의 일반 비동기 DEFERRED/waiter API를 만들지 않는다.
  SCC 의존 순서와 유한 active→deleted 전파로 구체화했다. 임의 CSP의 greedy 완전성은 여전히 보장하지 않는다.
- **잠재 회귀 위험/감지:** native pool 관계만 값의존으로 사용하면 local 경계가 사라질 수 있다. local CP/LOUT
  function-boundary seedless fixture와 보호 loop/function 성공 corpus를 함께 유지한다.
- **의사결정 근거:** 후보/Oracle/runtime의 합법 영역을 바꾸지 않고 selector가 반환할 witness의 증명 경계를 보강했다.

## Docker validation lane — 실험 환경·fixture 실패와 시간 조사

- **상태:** repo-owned 실행 lane 추가 및 최종 빌드 검증 완료. 아래 최종 검증 기록 참조.
- **문제 정의:** 기존 `run_LAN_docker.sh`는 외부 frozen search-space-only harness만 호출하여 실제 selector/runtime
  수치 검증을 할 수 없었다. 외부 harness나 호스트 `run_LAN.sh`를 우회 수정하지 않았다.
- **해결:** wrapper에 명시적 `--greedy-validation` 분기를 추가하고 repo-owned Python runner + Java test probe를
  사용한다. image/JAR/source SHA, Docker argv, heap/stack/CPU, 모든 로그·실패·수치·시간 receipt를 보존한다.
  DMLScript.main의 예외 출력만으로 성공을 판정하지 않고 executeScript 반환값 및 실제 exception을 검사한다.
- **초기 실패/수정:** `run-orrdlsmx`는 scalar sum에 불필요한 `as.scalar` cast를 적용한 fixture 오류였다.
  `run-1ieali98`는 Java vector module flag 누락이었다. cast를 제거하고 POM의 Java17 module/open flag를 사용했다.
  이 실패들을 planner 버그나 성공 표본으로 계산하지 않으며 raw 로그는 삭제하지 않았다.
- **검증:** `run-l7qvtfm6` protected 6/6 수치 PASS. 첫 paired `run-jbjip25b` baseline/current 72/72 PASS,
  1 MiB stack의 512/4096/16384/65536 chain 12회 PASS. 이는 SCC 최종 보강 전 기록이다.
- **성능 조사:** 첫 paired selector+adapter median은 모든 6조건에서 27–43% 감소했지만, FedFirst-loop 전체
  planning median은 11.5% 증가하여 계획의 5% 조사 게이트가 발동했다. 공통 analysis median +208.8 ms,
  sample별 post-analysis median -18.7%를 확인했다. 공통 analysis 소스는 그대로이며, temporal cohort confound와
  실행 변동 가능성을 구분하기 위해 최종 빌드는 repeat별 engine 순서를 사전 고정해 교대한다.
  이전 불리한 결과를 폐기하거나 좋은 표본만 선택하지 않는다. 최종 수치는 구현 보고서에 별도 병기한다.
- **수정 파일:** `scripts/fedplanner/run_LAN_docker.sh`, `validate_greedy_docker.py`, `PolicyGreedyDockerProbe.java`.
- **잔여 한계:** one-worker container loopback이고 multi-host LAN/general workload benchmark가 아니다.
  heap 수치는 per-pool peak들의 합 상한이지 같은 순간의 전체 peak 측정이 아니다.
- **잠재 회귀 위험/감지:** stale JAR·다른 설정·예외 삼킴은 source mtime/SHA 및 planner/success marker로 검출한다.
  수치 equality만으로 privacy/placement를 확인하지 않고 runtime audit도 켠다.
- **의사결정 근거:** runtime/성능 검증은 지정된 Docker 진입점만 사용하며 frozen harness·기존 별도 paused goal은 건드리지 않는다.

## 추가 partitioned protected MM fixture의 공통 분석 거절

- **상태:** 발견한 검증 범위 한계로 기록. 공통 후보/Oracle 수정은 이번 범위 밖.
- **증상/재현:** `target/fedpolicy-greedy-20260928/grounding-1.log`의 새 ROW/COL matrix-vector fixture가
  selector 호출 전에 `No privacy-safe physical placement ... PRIVATE_AGGREGATE_TO_PUBLIC`로 거절되었다.
- **대응:** runtime 지원 여부나 privacy 규칙을 추측하여 완화하지 않았다. orientation 정책은 owned abstract shape를
  입력으로 하는 단위 테스트로 분리하고, protected FULL MM 및 기존 ROW/COL loop end-to-end 계약은 유지했다.
- **수정 파일:** `PolicyGreedyPlacementSelectorTest.java` (신규 fixture 대체; 기존 성공 테스트 삭제/완화 없음).
- **잔여 이슈:** multi-worker ROW/COL aggregate-vector의 신규 protected runtime corpus는 추가 조사 대상이다.
- **잠재 회귀 위험/감지:** shape predicate 단위 테스트를 multi-worker runtime 성공으로 확대 해석하지 않는다.
- **의사결정 근거:** full common candidate 공간 보존과 privacy fail-closed 원칙을 우선했다.

## FedFirst/AggLocal v2 최종 검증 — 완료

- **상태:** 이 작업의 고정 corpus 기준 구현·검증 완료. commit/push 없음.
- **최종 소스 검증:** `final-targeted-2.log` **125/125 PASS**, failures/errors/skips 0, `mvn package` BUILD SUCCESS.
  Common builder/generator/closure/analysis/rules/Oracle/DP/Exact 경로는 HEAD 대비 무변경이다.
  Source/JAR/probe SHA가 최종 Docker manifest와 일치하며 static 검증 receipt도 보존했다.
- **Docker:** `target/fedpolicy-greedy-docker/run-0jdrw4rr/receipt.json` **72/72 수치 PASS**, audit 오류 marker 0,
  고정 1 MiB stack의 512/4096/16384/65536 chain ×3 **12/12 PASS**. 6조건의 baseline/current analysis
  fingerprint가 동일하다. 모든 runtime/성능 실험은 지정된 `run_LAN_docker.sh` 진입점을 사용했다.
- **성능:** warm-up 제외 5회 median으로 선택+adapter **19–44% 감소**, 전체 planning **1–13% 감소**.
  최종 비교에서 +5% 조사 gate 초과 0건. 이전 FedFirst-loop 악화가 최종 교대 protocol에서는 재현되지 않았다.
  이전 raw artifact도 유지하며 공통 analysis 변동과 selector 비용 감소를 구분했다.
- **독립 검토:** code reviewer가 exact assignment key와 local value-boundary grounding 수정에 APPROVE,
  별도 verifier가 구현 보고서/정적 검증/125-test 주장에 APPROVE했다. 이를 모든 workload/architecture의
  완전한 증명으로 확대하지 않는다.
- **잔여 한계/위험:** 임의 CSP 완전성, 전체 compiler 선형성, 모든 runtime workload의 속도 향상은 보장하지 않는다.
  PUBLIC fixture/full suite, multi-worker runtime, planner-anchor→실제 rmvar 종단 및 대규모 고차 CFG 실험은
  이번 범위 밖이다. 기존 hard legality 검증과 typed fail-before-emission을 유지하여 감지한다.
- **정리 파일:** `docs/FEDFIRST_AGGLOCAL_IMPLEMENTATION_2026-09-28.md`의 알고리즘·변경 파일·정확한 수치와
  재현 명령을 현재 구현의 기준 문서로 사용한다. 이전 알고리즘/리뷰 파일에는 수정 전 snapshot 표시를 추가했다.
