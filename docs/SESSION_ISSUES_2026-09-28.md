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

## Four-planner 전체 compile/runtime 캠페인 준비 — 진행중

- **요청/상태:** 후속 사용자 요청으로 구현을 5개 논리 커밋으로 나누어 정상 push 완료.
  `origin/refactor/w1357-paper-aligned-20260928`와 local HEAD 모두
  `5205946c768cc48627a227ab1f6cd07ef75b6024`. 이후 전체 896 compile 조건과,
  전부 성공한 뒤 logreg → l2svm → 나머지 runtime 순서를 별도 캠페인으로 준비했다.
- **증상/원인:** 기존 P5 wrapper는 search-space-only여서 selector/LOP/runtime-program
  구성을 검증하지 않는다. 외부 구형 campaign의 run 분기는 선택 필터를 runtime batch에
  전달하지 않으므로 logreg-first를 보장하지 않는다. 전체 compile이라고 부를 수 없었다.
- **해결/수정 파일:** `run_LAN_docker.sh --campaign`, `run_matrix_campaign.py`,
  `MatrixCampaignProbe.java` 추가. production `executeScript` + `-stats` +
  compile_only XML로 runtime 직전까지 컴파일한다. 각 시도는 새 JVM/worker/container,
  immutable JAR/probe와 append-only 결과를 사용하며 전역 compile gate 이전 runtime 금지.
  frozen P5 harness와 별도 paused 성능 goal은 변경하지 않는다.
- **환경 검증:** 8개 host 모두 동일 stage seal의 2,995개 파일 SHA 재검증 성공.
  host별 Docker image ID는 달랐으나 normalized content SHA는 전부 동일했다.
  coordinator/worker 모두 최신 candidate overlay SHA 검증. root disk 2.4 GiB 문제는
  기존 user-owned `/grid/3/cofee-lm-sweep-mchoi-20260914` evidence 공간으로 회피했고
  사용자 파일을 삭제하지 않았다.
- **검토 중 수정:** probe/log timing 대조, 실제 runtime timer/실행 job 수와 audit 요약을 통한
  compile-only 무실행 검증, search-space commonPreparation/analysis 분리,
  runtime 실패 시 exit code 및 reference identity 고정 필요성을 확인했다.
- **잔여 이슈:** 첫 DP/logreg/W1/LAN compile pilot 진행중. 142초 thread dump에서
  공통 analysis 이후 DP exact-factor dense materialization CPU 작업을 관측하여 원인 조사중.
  진단 개입이 있으므로 이 pilot은 정상 성능 표본으로 사용하지 않는다.
- **잠재 회귀 위험/감지:** 예외를 출력만 하는 DMLScript.main의 가짜 성공, stale JAR,
  다른 seed/network/planner, compile-only 오인, 일부 조건만 통과한 runtime 진입을
  receipt·SHA·896개 gate·실제 Docker/netem 증거로 차단한다.
- **의사결정 근거:** 실험 경계를 바로잡으며 Oracle/privacy/runtime 영역은 완화하지 않는다.

## 기존 runtime 수치 comparator/reference 불일치 — 진행중

- **증상/원인:** 외부 `run_trusted_comparison`이 보내는 구형 request에는 최신 comparator가
  요구하는 host-aware `reference_source`가 없다. 최신 pinned cpref5 전체 상태는 FAILED이며
  13 workload reference만 있고 P2는 미완료다.
- **해결 방향/파일:** repo-owned `matrix_runtime_compare.py`에서 현재 request schema와
  manifest/generation-plan 두 SHA를 검증하도록 연결한다. comparator, 기준값,
  correctness 정책을 임의로 바꾸지 않는다. P2 없는 기준값을 READY로 승격하지 않는다.
- **검증:** so007의 cpref5 result SHA
  `76963e5a6c0cdb0b60b2f42fc4c8bac0fa523cb0fc687f4fde82cb9ed2f89416`, generation-plan SHA
  `06e15c6b331deab04840dcd3dc1247cf394b5499daf5e9c60d1ba06fa95de641` 읽기 전용 재확인.
- **원인 확정:** cpref5 P2는 compile 0.801126초 후 runtime cache eviction의 912,440,831-byte 파일을
  256 MiB `/tmp` tmpfs에 쓰다가 `No space left on device`로 실패했다. rc=0이지만
  `dmlscript_fatal_marker=true`였다. 엔진 미지원으로 단정하거나 성공 처리하지 않는다.
- **수정 방향:** 기존 `generate_w1357_references.py`의 P2 selective repair는 attempt-owned
  host scratch `/scratch`와 5 GiB 가용량 gate를 이미 제공한다. 전체 compile gate가
  성공한 뒤 P2 runtime 순서에서 이 lane을 wrapper 내부로 호출하고 새 P2 결과 두 개를
  검증·pin한다. 원본 cpref5 FAILED/13개 reference는 덮어쓰지 않는다.
- **잔여 이슈/위험:** P2 scratch 수정 후 reference 재생성은 아직 미실행이다.
  candidate actual output JAR SHA와 sealed reference/decoder JAR SHA를 혼동하면
  거짓 provenance 또는 정당한 비교 거절이 생긴다. 두 identity와 raw output SHA를 분리 기록한다.
- **의사결정 근거:** 검증 실패를 runtime 성공으로 덮지 않고, 비교기 계약을 충족한 수치 결과만 성공으로 인정한다.


## DP/logreg 첫 전체 compile의 900초 timeout — 수정 검증중

- **조건/증거:** `w1357-policy-matrix-20260928-pilot01`, DP-local/logreg/W1/LAN,
  최신 push된 frozen engine, 16 GiB JVM/24 GiB Docker. 900초 제한에서 rc=124,
  process 901.711541초. compile 성공 0/실패 1/미시작 895, runtime 0/896이다.
- **원인 관측:** 여러 SIGQUIT 표본이 `LocalPhysicalOptimizer → reducedRoot →
  ExactPhysicalReducedSolver.reduce → ExactCategoricalSolver.materializeInputs`의
  realization-support factor callback에 머물렀다. 매 factor cell마다 동일 clause의
  requiredInputSupport distinct/sort/정규화와 canonical receipt/reference 구성이 반복된다.
  deadlock/worker network 대기가 아니라 DP model factor 동결 단계의 CPU 계산이다.
- **해결 진행:** `ExactPhysicalModel.addRealizationSupportFactors`에서 immutable support와
  canonical source reference를 model-local로 계산하고 equality를 동일한 reference handle
  비교로 재사용한다. domains/scopes/truth tables/candidate 집합/상한은 바꾸지 않는다.
  변경 전에 old evaluator의 전체 fixture factor truth를 고정하고 after와 대조한다.
- **검증/정리:** 실패 raw logs/thread dumps와 result.json 보존. ownership-checked Docker
  cleanup resolved=true, 8개 stage lease release=true, coordinator/worker 컨테이너 없음 확인.
  진단 개입이 있으므로 timing survey의 정상 표본으로 사용하지 않는다. timeout을 실제
  compile 시간이나 전역 infeasibility로 기록하지 않는다.
- **잔여 이슈:** cache 수정 이후 동일 timeout/동일 조건 재검증 대기. 전체 행렬 및 runtime
  성공으로 확대 보고하지 않는다. PUBLIC-only regression은 적용하지 않고 protected fixture로 검증한다.
- **잠재 회귀/감지:** canonical 소유자 검증 생략, 두 required reference의 AND를 OR로 바꾸는
  오류, model 간 cache 누수는 exhaustive truth parity와 기존 exact/DP authority 테스트로 검출한다.
- **의사결정 근거:** 불변 계산만 재사용한다. 후보 pruning, cap 증가, 다른 planner/runtime fallback은 금지한다.

## DP/logreg 두 번째 compile 파일럿의 rc=137 — 진단 신호 오류로 원인 확정

- **상태/증상:** realization-support 불변 계산 재사용 이후 `pilot02`의 동일 DP/logreg/W1/LAN
  production compile이 237.811432초에 rc=137로 종료했다. 900초 timeout과 다른 실패이며
  원인은 아래 진단 신호 오류로 확정했다. 원본 로그/receipt(null)/cleanup/netem을 보존했다.
- **첫 수정 검증:** protected B11 fixture의 전체 hard-factor truth SHA는 수정 전후 모두
  `8985c0e88b787efda0f0b62ae357f6374ee555e1a836f466c81e84e91e2faea9`.
  `dp-realization-cache-final-evidence`의 6개 Surefire XML은 총 40 tests, failures/errors/skips 0이다.
  별도 확장 실행에서 기존 certificate 60M-cell cap 오류와 GLM default-heap OOM은 남아 있다.
  GLM OOM은 수정 전 코드에서도 재현되어 이번 수정으로 해결했다고 주장하지 않는다.
- **추가 관측:** JVM 약206초 thread dump는 realization-support가 아닌
  `ExactPhysicalModel.inputSatisfied → directFoutSatisfied`에서 전체 relocation action/obligation
  검색을 factor cell마다 반복함을 보였다. 이 파일럿도 SIGQUIT 개입이 있어 성능 표본이 아니다.
- **원인 확정:** 진단자가 `MatrixCampaignProbe` 문자열을 가진 첫 PID를 Java로 오인하여 GNU
  `timeout` wrapper(PID45)에 QUIT를 보냈다. timeout이 Java에 QUIT를 전달해 유효한 thread dump는
  생성됐지만 `--kill-after=30s`도 발동하여 31.366540초 후 rc137로 종료했다. Docker event에서
  진단 exec 10:50:15.466193Z → target exec_die 10:50:46.832733Z를 확인했다. 원본 event 증거는
  `pilot02/diagnostics/rc137-diagnostic-interference.json`에 보존했다. OOM event는 없었고
  cleanup kill은 이후 10:50:50.419295Z였다. kernel journal은 권한상 읽지 못했으며 근거로 사용하지 않는다.
- **수정 방향:** 향후 측정은 진단 신호 없이 재실행한다. 필요시 `comm=java`와 `/proc/PID/comm`,
  cmdline을 모두 검증해야 한다. rc137을 엔진/메모리 문제로 돌리거나 자원 한도를 올리지 않는다.
  관측한 반복 계산의 matching-action/canonical receipt hoist는 별도 성능 수정이며 assignment별
  활성화/authority 검증과 factor truth를 유지한다.
  새 runner는 cleanup 전에 exact container ID의 State, memory limit, OOM events, cgroup counter를 수집한다.
- **수정 파일:** `ExactPhysicalModel.java`, protected factor regression, `run_matrix_campaign.py`.
- **정리/잔여:** cleanup resolved=true, stage leases released=true. compile 0/896, runtime 0/896;
  전역 compile gate는 계속 닫혀 있다. 이 파일럿의 완료/성능 결과는 무효이며 후속 최적화 효과는 재검증한다.
- **잠재 회귀/감지:** 사전 인덱싱이 다른 candidate/action authority를 섞는 위험은 exact truth parity와
  ownership 회귀로 검출한다. child JVM만 OOM-kill된 경우 PID1 State만 보면 놓치므로 OOM event도 수집한다.
- **의사결정 근거:** 후보/제약/한도를 바꾸지 않고 반복 불변 연산과 실패 관측만 수정한다.

## Untimed Docker setup의 안전한 병렬화 — 단위 검증 완료

- **증상/원인:** worker 수에 따라 독립 host의 생성/tc 설정 SSH가 직렬 누적되어 전체 896-cell
  캠페인 준비 시간이 커진다. 이 시간은 compile/planning timer 밖이다.
- **해결/파일:** `matrix_lifecycle.py` 및 runner 연결. worker 생성 전부 완료 → coordinator 생성 →
  tc 설정 전부 완료 → tc 검증 전부 완료 → readiness 순서의 barrier를 유지한다. 각 stage는 최대8개
  독립 작업만 병렬화하며, 실패해도 이미 실행한 모든 future가 끝난 뒤 cleanup에 진입한다.
  한 번에 timed workload는 여전히 하나이며 cell마다 새 worker/container/JVM을 유지한다.
- **검증:** canonical 7-worker plan 형태 7→1→8→8→1 확인, helper mock 5/5 PASS,
  matrix-pattern Python 회귀 및 cleanup/health 통합 회귀 66/66 PASS. Docker 실측은
  다음 frozen epoch에서 검증한다. `matrix-harness-lifecycle-health-final.log`에 원본 결과를 보존했다.
- **잔여 위험/감지:** stage barrier 누락이나 실패 후 늦은 container 생성은 mock ordering/drain 회귀와
  실제 exact-ID cleanup receipt로 검출한다. helper SHA도 campaign identity에 포함하여 revision을 섞지 않는다.
- **의사결정 근거:** 측정 대상/자원/입력/네트워크/새 프로세스 조건은 유지하고 준비 시간만 단축한다.

## DP exact input-authority factor의 불변 준비 인덱싱 — 보호 fixture 회귀 통과

- **증상/근거:** 진단용 `pilot02` JVM dump에서 `ExactCategoricalSolver.materializeInputs`의
  factor cell 평가가 `ExactPhysicalModel.inputSatisfied → directFoutSatisfied`에 머물렀다.
  기존 evaluator는 동일 consumer alternative에 대해 authority 검색, 전체 relocation-action 필터,
  obligation 일치, canonical candidate receipt 파생을 factor cell마다 반복했다.
- **수정:** `ExactPhysicalModel.java`의 model build 범위에서 source value-version별 action을 인덱싱하고,
  consumer-alternative별 일치 authority·DIRECT_FOUT action·exact-action identity·RELOCATION obligation을
  한 번 준비했다. canonical receipt는 domain/alternative identity별로 한 번 검증하여
  factor scope와 value 순서를 그대로 재사용한다. cache는 model-local이며 candidate/action을 제거하지 않는다.
- **의미 보존:** assignment별 `isRelocationActive`, exact receipt collection, relocation privacy,
  source output/FType 검사는 factor cell 안에 남겼다. exact action은 구조 동등이 아닌 기존 `==`
  identity 조건을 유지했고, authority의 source-decision·input-position·placement/FType 필터도 변경하지 않았다.
  factor scope/순서/비용, 전체 후보 집합, solver limit은 동일하다.
- **truth 검증:** PUBLIC이 아닌 B-11 `PRIVATE_AGGREGATE` fixture의 전체 hard-factor
  scope/order/cell truth SHA-256은 수정 전 golden과 동일한
  `8985c0e88b787efda0f0b62ae357f6374ee555e1a836f466c81e84e91e2faea9`이다.
  이 fixture는 relocation action, DIRECT_FOUT authority, realization support가 모두 0보다 큼을 검사한다.
- **회귀 검증:** focused protected test PASS. realization support/reduced solver/regional problem/owner lookup/
  policy quotient/anchor relocation identity를 포함한 7개 exact 클래스 46 tests, failures/errors/skips 0.
  증거는 `/grid/3/cofee-lm-sweep-mchoi-20260914/dp-input-authority-index-final-evidence`에 보존했다.
- **잔여/중단조건:** scope assignment map과 selected-receipt list는 assignment에 의존하므로
  cell별 구성을 유지했다. 이 수정의 end-to-end compile 속도는 아직 증명하지 않았다.
  `pilot02` rc137은 timeout wrapper에 잘못 보낸 QUIT으로 유발된 무효 실행이므로 성능 근거가 아니다.
  후속 검증은 진단 신호 없이 동일 900초 제한으로 실행한다.

## 수정 후 첫 signal-free DP production compile — 단일 조건 통과

- **환경/증거:** `w1357-policy-matrix-20260928-run01`, JAR
  `2d9dd14bf7787db48f5f0f0469b8f0e299405ea86c76487ab6934c052b0e1d57`,
  DP/logreg/W1/LAN. `attempts/compile/01790593628154595809-58500dc9/result.json`.
- **결과:** compile 810.656620초, shared search-space 26.888162초,
  selection/adapter 781.971015초. runtime timer/실행 job 0, physical lowering198/198,
  missing/mismatch0, cleanup resolved=true, OOM/health 수집 오류 없음.
- **검증:** 두 준비 단계 수정의 Java46/46, harness66/66, package 성공 및 독립 review 승인 후 실행했다.
  진단 신호/JFR은 사용하지 않았다. read-only ps 관측 한 번의 원문은 별도 보존했다.
- **잔여 이슈:** 단일 조건만 통과했으며 DP optimizer718.349780초/cost surface58.052110초는
  여전히 큰 비용이다. 896개 행렬 전체 통과나 일반적 성능 향상을 주장하지 않는다.
  후속 조건은 동일 frozen engine으로 순차 실행하며 실패 시 중지·진단한다.
- **의사결정 근거:** 기존 cap·후보·privacy·자원·timeout을 그대로 둔 전체 production 컴파일 성공만 채택한다.

## signal-free DP/WAN-mid의 compile timeout — 후속 프로파일링 준비

- **상태/환경:** run01 DP/logreg/W1/LAN810.656620초와 WAN-light792.211542초는 통과했으나,
  세 번째 WAN-mid가 900초 제한에서 rc124로 종료했다. 진단 신호가 없는 자연 timeout이다.
- **관측/범위:** 공통 analysis 종료 및 planner 진입 marker 후 완료 receipt는 없었다. 첫 성공 사례의
  optimizer718.349780초만으로 실패 사례의 정확한 hotspot을 단정하지 않는다.
- **대응:** 명시적인 compile-only JFR 진단 옵션을 추가해 별도 immutable root에서 프로파일을 수집한다.
  startup recording + 정상 timeout의 JVM 종료 시 dump를 사용하고 live PID에 신호를 보내지 않는다.
  진단 표본은 정상 timing CSV/전역 compile gate에서 제외한다. 300초 진단 한도는 관측용이며
  본행렬의 고정 900초 제한, JVM/컨테이너 자원, 후보·Oracle·solver cap은 유지한다.
- **수정 파일:** `run_matrix_campaign.py`, diagnostic 회귀 테스트. 엔진 후속 수정은 실제 profile 확인 후 결정한다.
- **잔여:** compile2통과/1실패/893미시작, runtime0/896. 전체 compile gate가 닫혔고 자동 실행도 중지됐다.
- **잠재 회귀/감지:** 진단 옵션을 일반 캠페인 identity에 섞거나 diagnostic success로 runtime gate를 여는
  오류는 identity/compile_gate/CLI 회귀로 차단한다. 이전 성공·실패 원본은 삭제하지 않는다.
- **의사결정 근거:** 실패를 임의 timeout 증가나 다른 플래너 fallback으로 덮지 않고 재현 가능한 profile로 진단한다.

## JFR 진단 및 확장 Python 검증의 외부 binding 경계

- **진단 결과:** `w1357-policy-matrix-20260928-diag01`에서 DP/logreg/W1/WAN-mid의
  300초 한도 compile-only JFR을 정상 수집했다. rc124/process301.443892초는 관측 종료이며
  본행렬 성공/실패 추가 표본으로 섞지 않는다. `compile.jfr` 8,964,227 bytes,
  SHA `9cdab9bb2336c0cdcbd664e4302b1a2cbcbd1b0f28e8eb5276378e4123ee17ca`.
  cleanup/lease 해제 성공, collection 오류0. startup JFR이므로 PID 신호 오류 위험을 제거했다.
- **도구 회귀:** matrix-pattern Python73/73 PASS. identity/measurement와 개별 result의
  diagnostic marker 모두 전역 runtime gate를 닫고 일반 timing CSV는 진단 시간을 비운다.
- **확장 검증 한계:** 전체 `scripts/fedplanner/tests` 발견 실행은457개 중 setup error1, skip1이었다.
  실패는 `test_audit_current_scope_library_resolution`이 외부 frozen receipt의 producer 절대경로를
  검증하는 지점이다. receipt는 `/home/mchoi/systemds-g009-integration/...`에 묶여 있고 현재
  worktree는 `/home/mchoi/w1357-paper-aligned-refactor/...`이다. producer SHA는 양쪽 모두
  `8fd304246f526552851a9fd3f8d91160dcd45af3ddfa33c42a29689d92fffe98`이며 초기 push의 파일과도 같다.
  `matrix-unrelated-library-binding.json`에 별도 증거를 보존했다.
- **대응/잔여:** 외부 frozen receipt를 현재 경로로 위조하거나 binding 검사를 완화하지 않는다.
  해당 독립 audit의 fixture portability는 본 캠페인 범위 밖 검증 gap으로 남긴다. 본행렬의
  stage/image/engine/receipt 검증은 별도 contract와73개 회귀로 유지한다.
- **의사결정 근거:** 다른 worktree의 provenance를 현재 worktree 성공 증거로 승격하지 않는다.

## JFR로 확인한 validated receipt worker-pool proof 재검증 제거 — 의미 회귀 통과

- **측정 근거:** `diag01` JFR의 analysis 이후 main-thread 12,162 execution sample 중
  `inputSatisfied` 6,536(53.741%), `isRelocationActive` 4,664(38.349%),
  `CandidateEmissionRealization.provenWorkerPool` 2,748(22.595%)이었다. 이 비율은
  wall time이 아닌 execution sample 비율이며, 수정 후 성능 성공을 의미하지 않는다.
- **원인:** immutable `CandidateSelectionReceipt` 생성자는 emission→realization과
  realization→support-clause 소유권을 identity로 이미 검증하지만, `provenWorkerPool()`이
  cell별로 같은 support-clause list를 다시 linear scan했다. graph는 같은 receipt의
  `provenWorkerPool()`을 한 분기에서 두 번 호출했다.
- **수정:** receipt의 pool/residency query가 기존 package-local `*ForOwnedClause`를 사용하도록
  하고, helper 계약에 생성자가 검증한 immutable receipt를 명시했다.
  `NeutralPlacementGraph.isRelocationActive`는 proven pool을 한 번만 읽고 receipt-owned native residency
  accessor를 사용한다. record component/shape, public clause-taking API, global cache는 변경하지 않았다.
- **의미 보존 검증:** durable multi-clause, exact native witness, dynamic native witness,
  witness 없는 native realization에서 receipt fast path가 기존 guarded query와 동일한 exact object/null을
  반환함을 검사했다. 구조적으로 같지만 identity가 다른 foreign clause는
  receipt 생성과 두 public guarded query에서 계속 거절된다.
- **회귀:** 수정 전 authority+protected truth baseline PASS. 수정 후 focused authority PASS,
  기존 exact 46 tests + authority/policy-greedy 26 tests = 72 tests,
  failures/errors/skips 0. B-11 `PRIVATE_AGGREGATE` hard-factor truth golden은
  `8985c0e88b787efda0f0b62ae357f6374ee555e1a836f466c81e84e91e2faea9`를 그대로 검증했다.
- **증거/잔여:** `/grid/3/cofee-lm-sweep-mchoi-20260914/validated-receipt-fastpath-evidence`.
  후보·action·factor scope/order/cost/limit은 바꾸지 않았다. OFF 실측 전에는
  complexity 감소와 semantics 보존만 주장하고 compile 시간 개선은 주장하지 않는다.

## JFR cost-factor 반복 계산과 fingerprint 문자열 할당 제거 — 보호 baseline 동일

- **실제 실패와 진단 표본 구분:** 사용자 행렬의 실제 실패는 `run01` DP/logreg/W1/WAN-mid가
  고정 900초에서 자연 timeout된 건이다. `diag01`의 300초 rc124는 이 실패를 분석하기 위한
  의도적인 compile-only JFR 관측 종료이며 새 성능 표본이나 추가 행렬 실패로 세지 않는다.
- **측정 근거:** analysis 이후 main-thread 12,162 execution sample 중 native-local physical-cost
  factor가 2,601(21.386%), fingerprint enumeration이 1,141(9.382%)이었다. sampled allocation
  weight는 각각 71,433,289,048 bytes(20.993%)와 72,877,836,520 bytes(21.418%)이다.
  이 값은 JFR sample/weight이며 wall time이나 retained bytes가 아니다.
- **원인 A와 수정:** native-local binary factor는 모든 producer×consumer cell을 유지하지만,
  consumer alternative와 고정 edge에만 의존하는 authority/FType/bounded elementwise/mixed-cost
  계산을 producer alternative마다 반복했다. structural/materialized-cell preflight 뒤
  `freezeValidatedFactor`가 처음 target cell을 평가할 때 immutable target row
  `(applicable, baseCost, sourceBytes)`를 construction-local 배열에 한 번 준비한다. 이후 cell은
  배열 조회와 기존 source-FOUT download, 기존 `weight * cost` 검증만 수행한다. 계산을 factor
  생성 시 eager 실행하지 않으므로 기존 aggregate budget gate가 ordinary evaluator보다 먼저 실패한다.
  전체 P×T cell, source별 FOUT 비용, factor scope/order, 후보와 solver limit은 그대로다.
- **원인 B와 수정:** fingerprint 대상은 이미 frozen dense factor인데도 각 cell을 재귀 decode하고
  `Factor.cost`로 다시 indexing한 뒤 unsigned-hex `String`과 UTF-8 `byte[]`를 만들었다.
  기존 dense row-major 배열을 `denseCostAt(cell)`로 한 번 순회하고, writer-local 16-byte buffer로
  동일한 variable-width lowercase unsigned hex와 comma를 SHA-256에 직접 공급한다. ±0, infinity,
  NaN payload를 포함한 raw 64-bit 값을 skip/coalesce하지 않는다.
- **수정 전 고정값/의미 회귀:** `PRIVATE_AGGREGATE` native-local fixture의 full contribution
  fingerprint `84eb7160f4b4605efb174f37d7705cf4d9fe7828db41b9b4a1b9b5b903b89b0e`,
  domain/alternative/factor-scope/order SHA
  `6eabb96f81f1c831a67ee82f76775d1f8ea8e64dd7b7cd0776a09f7cb674c4d3`,
  모든 contribution cell raw-double-bit SHA
  `f949de6c7f44f5bc770ab2e3ec0966f3f94a0c6d2be74e7e898c92b02444909d`를 old code에서
  먼저 고정했고 A/B 뒤 모두 동일하다. fingerprint stream은 edge 값과 seed1011081480의
  random 100,000개 raw long, 2×3 multidimensional row-major factor를 legacy
  `Long.toUnsignedString(bits,16)+','` digest와 직접 비교한다.
- **검증:** A checkpoint native+preflight 10/10 PASS, B checkpoint 12/12 PASS. 최종 소유 범위
  native/preflight/streaming/trace/sparse/zero-frequency 6개 클래스 23 tests,
  failures/errors/skips 0. diff-check도 통과했다. 원본 로그/XML/diff/source SHA는
  `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-factor-fixes-evidence`에 보존했다.
- **별도 기존/동시 범위 실패:** 확장 10-class 실행은 certificate의 기존
  `71606548 > 60000000` materialized-cell gate와 동시 Placement 변경 범위의 L2SVM relocation,
  ForcedState fixture assertion 때문에 3건 실패했다. 이 실행을 green 증거로 사용하지 않고 cap을
  올리거나 본 cost patch에서 Placement 파일을 수정하지 않는다. 해당 두 assertion은 Placement owner가
  재검증한다.
- **잔여/중단조건:** 이 변경은 반복 불변 연산과 per-cell 문자열 할당을 제거했지만 실제 compile
  단축이나 900초 timeout 해결은 아직 주장하지 않는다. root가 모든 동시 변경을 freeze/package한 뒤
  동일 조건의 signal-free production compile/JFR로만 성능 효과를 판정한다.

### Cost-factor 검증 범위 정정 및 frozen 결합 회귀

- 위의 "동시 Placement 변경 범위" 표현은 부정확하다. receipt fast path 3개 production 파일은
  cost-factor Maven 실행 전에 이미 freeze되어 있었고, 실행 중 production source 변경은 없었다.
  확장 실행의 3개 실패 XML/log/test class는
  `cost-factor-fixes-evidence/exploratory-failures`에 보존했으며 old frozen JAR 비교 결과와 별도로 판정한다.
- receipt-fastpath의 기존 10개 클래스72 tests와 cost-factor 소유 6개 클래스23 tests를 같은 frozen
  source에서 새로 결합 실행했다. 총95 tests, failures/errors/skips 0이다. 원본 log/count/XML/hash는
  `cost-factor-fixes-evidence/final-combined-95*`에 보존했다. known certificate cap test와 위 두 fixture
  assertion은 이 green 집합에 포함해 성공으로 위장하지 않았다.

### 확장 fixture 두 assertion의 old frozen JAR 재현

- `CampaignBG014ExactL2SvmInternalEmissionCostRedTest`의 Xd relocation assertion(line112)과
  `ExactPhysicalForcedStateAuditTest`의 coherent-completion assertion(line56)은 run01 immutable old JAR
  `2d9dd14bf7787db48f5f0f0469b8f0e299405ea86c76487ab6934c052b0e1d57`를 사용한 격리 JUnitCore에서도
  각각 동일하게 재현됐다(L2SVM 1 test/1 failure, ForcedState 5 tests/1 failure).
  따라서 이 둘은 receipt fast path나 이번 cost-factor A/B가 만든 회귀가 아니며, 앞 절의
  "동시 변경 오염" 가능성은 폐기한다. 증거는
  `/grid/3/cofee-lm-sweep-mchoi-20260914/matrix-cost-expanded-baseline-check`에 보존했다.

- **추가 baseline 확정:** 최초 캠페인 `pilot01`의 수정 전 JAR
  `4e43606437ff05860434a5c266b9116eee4a517058979ebc066d3849cb63474e`로도 동일한 두 assertion을
  재현했다(L2SVM1건 중1실패 line112, ForcedState5건 중1실패 line56). 이는 receipt/cost 수정뿐 아니라
  이번 캠페인의 첫 factor 캐시 수정 이전에도 존재한 검증 gap이다. 격리된 JUnitCore command/log는
  `matrix-cost-expanded-baseline-check/initial-engine/`에 보존했고 당시 test class/dependency를 동결했다.
  이 기존 실패를 이번 변경의 통과로 바꾸거나 테스트를 삭제하지 않았다.

## JFR 기반 수정의 통합 재검증 — run02 시작

- **검증:** exact/authority/policy72 + cost23의 Java95/95 (16 classes), Python matrix73/73,
  package exit0, 독립 receipt/cost diff review 승인. 전체 과거 suite의 위 별도 실패는 남아 있다.
- **실행:** `w1357-policy-matrix-20260928-run02` 새 immutable root. 기존 실패 조건
  DP/logreg/W1/WAN-mid를 신호/JFR 없이 900초 제한으로 먼저 재검증한다. 성공하면 동일 root/engine에서
  나머지 compile을 이어서 실행하고 전역896 조건 성공 후에만 ordered runtime으로 넘어간다.
  이전 run01의 성공2건을 새 엔진 성공으로 합산하지 않는다.
- **보존 원칙:** 후보/domain/factor scope·order/비용 raw bits/기여 fingerprint/ownership/privacy/cap/
  자원·입력·네트워크를 유지한다. 성능 효과와 전체 성공 여부는 새 production 결과로만 판단한다.

## run02 WAN-mid 단일 회귀 조건 통과

- **결과:** 새 JAR `2c1d3f0205d3f88d0a8b67fc919ff05d7f2d6b841d8a0bc083e35b05d566893e`의
  DP/logreg/W1/WAN-mid compile209.403378초, search-space30.813578초,
  selection/adapter176.812636초. 동일900초 한도·입력·자원·netem이며 신호/JFR 없는 실행이다.
- **증거:** run02 `attempts/compile/01790598811446200270-e4a06fdb/result.json`; audit/lowering,
  runtime0, exact cleanup 모두 통과했다. 이전900초 timeout을 정확한 speedup 분모로 사용하지 않는다.
- **상태:** 해당 조건의 timeout은 재검증에서 해소됐으며 full compile schedule을 같은 root/engine으로
  계속 실행한다. 아직 전체896조건·runtime 성공이 아니다. 원래 엔진의 성공2건은 합산하지 않는다.

## run02 multi-worker DP timeout — 원인 진단 중

- **상태/환경:** 동일 frozen JAR `2c1d3f0205d3f88d0a8b67fc919ff05d7f2d6b841d8a0bc083e35b05d566893e`의
  DP/logreg/W1은 LAN249.018635초, WAN-light227.803576초, WAN-mid209.403378초,
  WAN-heavy231.739408초에 모두 compile 통과했다. 다음 DP/logreg/W3/LAN은 고정900초에서
  자연 timeout(rc124/process901.924841초)했다. runtime은 시작하지 않았다.
- **관측:** `run02/attempts/compile/01790599872711787532-607edb35`에 raw 결과/로그/health가 있다.
  analysis 약12.835초 후 planner에 진입했지만 완료 receipt는 없다. 네 컨테이너의 cgroup
  oom/oom_kill은 모두0, Docker OOM event도0이며 coordinator peak13,469,945,856 bytes는
  24GiB 제한 이내다. 이 증거는 OOM이 아니라 timeout임을 지지하지만 아직 세부 hotspot은 설명하지 않는다.
- **대응:** `diag02/attempts/compile/01790600967453756312-60f6a893`에서 같은 W3/LAN 조건에
  startup JFR만 추가하여300초 관측했다. rc124/process301.997346초는 의도한 진단 종료이며
  일반 matrix timing/성공 표본에 넣지 않는다. JFR16,127,331 bytes, SHA-256
  `41f1ade7af3b6df3c1a8211aaabf3a0ae8c33032e334f950e136a0db08cfe6c9`를 독립 확인했다.
  두 실행 모두 exact container ID/name 부재를 확인했고 cleanup 및8개 host stage lease 해제가 성공했다.
- **원인 분석/변경 계획:** W1 profile을 W3 원인으로 간주하지 않고 이 새 JFR을 full-stack으로
  offline 분석한다. 이 단계에서는 엔진·후보·비용·privacy·cap·자원·timeout을 변경하지 않았다.
- **잔여 이슈:** 현재 엔진의 full matrix는 compile4/896 통과,1실패,891미시작이며 runtime0/896이다.
  전체 compile gate는 닫혀 있다. 기존 엔진의 통과 사례를 합산하지 않는다.
- **잠재 회귀/감지:** 향후 최적화는 baseline truth/cost fingerprint와 직접 parity 회귀를 먼저 고정하고,
  같은900초·signal-free Docker 재검증으로 판단한다. timing 실패를 후보 축소나 제한 증가로 덮지 않는다.
- **의사결정 근거:** 실행 순서 DP→FedFirst→AggLocal→Exact 및 full compile gate를 유지한다.

### W3 JFR 원인 확인 및 semantics-preserving 수정 계획

- **새 증거:** `diag02/jfr-analysis/REPORT.md`, `offline-analysis.txt`, `w3-detail-analysis.txt`.
  analysis 이후 main-thread25,904 표본 중 dense exact solve22,286(86.033%)이다.
  `preciseSum` exclusive12,210(47.136%)/inclusive18,385(70.974%),
  `DenseFactor.value`6,173(23.830%), `PreciseCost.rounded`2,674(10.323%)이며 inclusive 비율은 겹친다.
  cost surface0.950%로, 앞선 W1 cost-factor 병목을 반복해서 원인으로 삼지 않는다.
- **호출/시간 경로:** DP의 initial regional seed → local hard-conflict repair → shared reduced block
  preparation → dense exact solve다. 관측 상대시간 약50.9초 이후부터284.7초까지 같은 solve 경로에
  표본이 집중되고 incremental optimizer 표본은 없다. 표본만으로 정확한 함수 호출 횟수는 단정하지 않는다.
- **할당/GC:** PreciseCost allocation sample weight 약1.030TB(89.65%)이며 이는 retained bytes나
  실제 heap 크기가 아니다. GC pause 합계 약1.058초로, CPU 계산/할당 hot loop를 먼저 수정한다.
- **계획:** 변경 전 raw high/low/tie, objective bits, 선택 assignment/통계/오류/callback 순서를
  회귀로 고정한다. (A) factor마다 만드는 임시 PreciseCost를 primitive 누적값으로 대체,
  (B) 같은 separator cell의 factor base index를 한 번 계산하고 variable stride를 재사용,
  (C) 이미 비교에 성공한 best의 rounded 값을 재사용하는 순서로 각각 검증한다.
  double-double 연산 순서·검사 순서·infinity early return·strict tie order·전체 cell·기존 cap은 유지한다.
- **판정 경계:** 독립 design review는 위 불변조건 하 A/B/C를 승인했다. 아직 production 수정 또는
  W3 성공을 주장하지 않는다. bounded 계획은 `solver-kernel-fixes-evidence/PLAN.md`에 먼저 기록했다.

### Solver kernel A0/A/B/C 구현 및 단계별 회귀

- **변경 전 고정:** solver source SHA
  `609f9456a5e8d19e8d2f99c78806346acb88539268f4a1fecad07fb6881667cc`에서 새8개+기존27개,
  총35 tests를 먼저 통과했다. 64개 heterogeneous 모델의 raw objective/assignment/statistics와
  지정 elimination-order 결과 SHA는
  `15479d2899d00720e0e59dca1b695167458c1f631b04a4acb2f532252e448513`,
  오류/원인 순서 SHA는 `48ffeda9ce34f97e03aa8a8c74c44fa9f1a1924108ff41793720316fa1b5150e`다.
  raw double-double high/low/tie는 test-local legacy 구현과500개 random sum 및 edge case로 직접 비교한다.
- **구현:** `ExactCategoricalSolver.java` 한 production 파일에서 (A0) immutable `plusTie(0)`는
  자신을 반환하고, (A) `preciseSum`은 동일 순서의 primitive high/low/tie를 누적하여 factor마다
  중간 record를 만들지 않는다. 후보당 최종 immutable record는 유지한다. (B) eliminated-variable
  stride를 bucket당 한 번, 나머지 separator base index를 separator cell당 한 번 계산한다.
  (C) 이전 best가 이미 성공한 rounded 값을 재사용한다. generic/indexed 경로는 하나의 합산 body를 공유한다.
- **보존:** 전체 factor/candidate cell, scope/order, elimination plan, numeric expression/check 순서,
  first-infinity의 해당 factor low/tie, overflow 오류 선후관계, candidate별 tie callback 순서/횟수,
  rounded primary→tie cost→첫 후보 우선순위를 유지한다. 새로운 배열은 solve-local이며 persistent
  compiled problem, immutable boundary message, input factor에는 mutable cache를 추가하지 않는다.
- **검증:** A0+A, B, C 각 단계마다35/35 PASS, goldens 동일. source/diff/XML/command는
  `solver-kernel-fixes-evidence`의 after-a/b/c에 보존했다. 독립 actual-diff review는 findings0/APPROVE.
  Python matrix73/73, bash syntax, py_compile, diff-check PASS. 확대 Java 결합 회귀는 다음 단계다.
- **잠재 회귀/감지:** primitive 합산과 boundary용 `PreciseCost.plus`가 향후 서로 달라지는 위험은
  test-local legacy raw-bit parity와 heterogeneous end-to-end golden으로 감지한다. stride 오류는
  reversed/mixed/singleton scopes와 지정 elimination order를 포함한 결과/callback parity로 감지한다.
- **잔여:** 아직 새 엔진으로 W3 timeout이 해소됐다는 성능 증거는 없다. 동일900초·자원·입력의
  signal-free Docker 재검증과 full896 compile gate가 여전히 필요하다.

- **확대 결합 검증 완료:** 기존 protected95개에 solver arithmetic/solver/order/local/shared/
  boundary-message 회귀를 결합한22개 클래스166 tests가 failures/errors/skips0으로 통과했다.
  기존 certificate cap, GLM heap, L2SVM expectation/ForcedState fixture 등의 과거 별도 gap을
  이 통과 집합에 넣어 green으로 가장하지 않았다. `combined-command.txt`, `combined-counts.json`,
  `combined-surefire/`에 원본을 보존했다. 신규 test의 unused import 한 줄만 제거한 후8개를 재실행한다.
- **최종 빌드:** import-only 정리 뒤 arithmetic8/8 PASS, `mvn -q -Dmaven.test.skip=true package`
  exit0. 최종 source/test/JAR SHA는 `solver-kernel-fixes-evidence/SOURCE_FREEZE.json`에 동결했다.

## run03 W3 재검증 timeout — solver 최적화만으로 미해결

- **상태/환경:** pushed commit `b143c83567e583e0ddd93dbb1ca73ce86b1bcc85`, JAR
  `13a2a65c523370d0f8008f38d5f6b0875af3dbf1ae520282cd933a78270d365c`의
  DP/logreg/W3/LAN을 새 immutable root에서 같은900초 제한으로 실행했다.
- **증상/증거:** `run03/attempts/compile/01790602766846701414-d97bc149`의
  rc124/process902.363041초, 완료 receipt 없음. analysis 종료 후 planner에 진입했다.
  cleanup resolved=true,8개 stage lease 해제 성공. full schedule은 첫 회귀 조건 실패로 시작하지 않았다.
- **해석:**166개 수치/선택/authority 결합 회귀와 소스 수준 중복 연산 제거는 입증됐지만,
  W3 production timeout 해결은 입증되지 않았다. 이전과 새 실행이 모두900초에서 검열됐으므로
  속도 향상이 없었다거나 특정 speedup이 있었다는 결론은 내리지 않는다.
- **다음 분석:** 새 engine profile과 dense solver의 실제 symbolic elimination work/statistics를
  확보해 전체 열거량과 남은 비용을 분리한다. 이전 profile만으로 추가 미세 최적화를 반복하지 않는다.
  신규 instrumentation이 필요하면 diagnostic-only identity 아래서만 수행하며 본행렬 조건은 유지한다.
- **잔여:** run03 compile0/896 통과,1실패,895미시작; runtime0/896. run02의4통과는 다른 엔진의
  결과로 보존하며 합산하지 않는다. compile gate는 계속 false다.
- **잠재 회귀/감지:** 다음 수정이 elimination order/수치 grouping/선택 tie/authority에 영향을 주면
  단순 cache 최적화로 간주하지 않고 별도 설계·parity 검증을 요구한다. 임의 후보 축소나 cap 증가는 하지 않는다.

### Timeout 관측 gap 보완 및 architecture 검토

- **확인된 구조:** DP는 `regionalSeed`의 cheapest hard-conflict repair를 먼저 끝낸 뒤 incremental
  optimizer의10초 soft budget을 시작한다. seed의 exact VE는 현재 materialization-only limit과
  memory-first4-order portfolio를 사용하며, assignment 열거량은 통계만 계산하고 hard cap으로 제한하지 않는다.
  따라서 작은 결과 table과 매우 많은 연산량이 공존할 수 있다. 이것은 코드 구조 확인이며 run03의
  정량 원인으로 확정한 것은 아니다. 실패 실행 health도 OOM event/cgroup kill0이었다.
- **기존 관측 부족:** 선택된 실제 VE 통계는 이미 compile 시 존재하지만 주로 완료 후 출력됐다.
  fast-order trace의0은 default-off의 placeholder이며 실제 선택된 열거량이 아니다.
- **진단 instrumentation:** `SharedRegionalPreparation`의 첫 성공적으로 준비한 block에 대해
  `Exact-PreSolveWork`를 solve 전에 출력한다. 기존 `blocks==0`과 기존 trace flag를 재사용하며
  root/block/input 변수·factor 수, domain 벡터, 실제 선택 order/assignment/cell 통계와 cap을 기록한다.
  compact 분기의 post-reduction 입력과 compiled 상태를 혼동하지 않도록 input* 필드를 구분한다.
  `ExactCategoricalSolver`는4개의 이미 계산한 portfolio metrics와 선택 priority를 단일
  `Exact-OrderPortfolio` receipt에 함께 출력한다. 입력 table cell 수는 intermediate metrics와 별도다.
  후보/order 재계산, lazy cost 평가, comparator/limit/policy 변경은 없다.
- **격리:** wrapper의 `--diagnostic-jfr`에만 coordinator trace=true/details=false를 추가하며
  flag 목록을 manifest identity/measurement에 동결한다. worker와 일반 compile/runtime에는 추가하지 않는다.
  새 Python contract를 먼저 RED로 확인한 뒤 matrix74/74 PASS. Java trace contract 역시
  pre-edit7/7 → first-block instrumentation9/9, portfolio-header 미구현 RED를 별도로 보존했다.
- **독립 architecture 의견:** first-feasible seed는 기존 cheapest-repair 계약과 테스트를 바꾸는
  정책 변경이므로 임시 우회로 사용하지 않는다. 실제 작업량 확인 후 conditioned exact reduction
  (root reduction과 다름)을 우선 검토한다. 기존 compact path는 post-conditioning reduction과
  singleton substitution 둘 다 수행한다. 과거 default-off는 compatibility/ablation이며 이득이 항상
  있었던 것은 아니다. order/compaction 변경은 equal-cost coupled tie의 선택을 바꿀 수 있으므로
  objective 동치와 assignment 동치를 혼동하지 않는다. global Exact 정책은 따로 검증해야 한다.
- **잔여/회귀 위험:** 새 JFR+pre-solve evidence 전까지 어떤 알고리즘 변경도 선택하지 않았다.
  trace-disabled numeric golden, trace-on/off/출력시점/계산통계/원래후보4개를 검증한 뒤 별도diag03를 실행한다.

- **Portfolio assertion 정정:** 새 trace 테스트의 첫 구현은 이 tiny fixture에서 MIN_SEPARATOR가
  선택될 것으로 잘못 예상했다. 실제 기존 MIN_FILL도 secondary neighbor-cell 기준으로 같은 order를
  만들며 네 metrics가 동일해 기존 priority0(MIN_FILL)가 이긴다. comparator 소스와 trace로 이를
  확인해 신규 진단 assertion만 바로잡았고 실패 원본은 `portfolio-first-attempt.log`에 보존했다.
  엔진 선택 정책을 테스트에 맞춰 바꾸지 않았다.
- **진단 변경 결합 검증:** numeric/cost/authority/policy/trace22개 클래스168/168 PASS,
  Python matrix74/74 PASS, 독립 diagnostic diff review APPROVE. trace test child의 환경변수 상속
  취약성은 system property true/false를 명시해 제거하고, trace 환경변수가1인 실행으로 추가 검증한다.
- **최종 진단 빌드:** `SYSDS_FED_PLANNER_TRACE=1` 환경에서도 explicit-off child를 포함한
  SharedRegionalPreparation9/9 PASS, package exit0. `solver-work-diagnostic-evidence/SOURCE_FREEZE.json`에
  새 source/JAR/runner/test hash를 기록했다. diag03는 새 immutable root에서300초 관측하며
  normal matrix timing 또는 compile gate에는 포함하지 않는다.

## diag03 실제 VE 작업량 확인 — 조건부 exact reduction 비교 진행 중

- **환경/결과:** commit `8468991128ceba10a1e87304f95722d4e856a139`, JAR
  `0ab951c1b4820e1cd13ea6d4e802538aeb41f9cabe90befde7c935e84094c037`의
  DP/logreg/W3/LAN diagnostic-only 실행이300초 관측 후 자연 timeout됐다
  (rc124, process302.019078초). runtime은 실행하지 않았다.
- **증거:** `/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-policy-matrix-20260928-diag03/`,
  attempt `01790605025518566636-3be1a882`. JFR9,556,651 bytes의 SHA는
  `96d0172a870a7ee171d6b13ee44f22645e9e36230271770c1469950ede2d51ea`다.
  네 컨테이너 oom/oom_kill/event0, coordinator peak14,175,797,248 bytes로24GiB 이내다.
  exact ID/name cleanup 및8개 stage lease 해제는 모두 성공했다.
- **정량 원인:** 첫 hard-conflict repair의7개 physical decisions가70개 encoded 변수/240개
  factor로 확장된다. 선택된 MIN_SEPARATOR_CELLS order는658,322,977,640개의 대입 평가를
  요구하며, 한 단계가657,511,403,008개(약99.88%)다. 결과 table의 최대 크기는202,998,272 cells다.
  나머지 기존 세 order의 최대 table은10,273,615,672 cells로, 실제 production per-factor
  한도2,147,483,647을 넘는다. 따라서 기존 portfolio 순서를 단순 교체하는 것은 허용 가능한 대안이 아니다.
  root489 decisions/887 variables/4,125 factors, 해당 block 입력49,793,890 cells 등의 원본
  trace-line SHA와 파싱 값은 `symbolic-work-summary.json`에 보존했다.
- **대응 계획:** 같은 JAR·입력·W3/LAN·24/16GiB·300초 조건에서 이미 존재하는
  `sysds.fedplanner.regional.compact=true`를 별도 diagnostic-only root로 비교한다.
  이 경로는 **조건을 고정한 뒤 exact reduction을 다시 수행하고 singleton을 대입**한다.
  root reduction만으로 conditioned domain의 추가 축소를 대신할 수는 없다.
  CLI를 diagnostic JFR/compile/single-cell에만 허용하고 실제 coordinator JVM 옵션,
  manifest identity, measurement에 variant를 묶는다. 일반 timing CSV/runtime gate에는 넣지 않는다.
- **의사결정 근거:** 독립 architecture 검토는 위 bounded ablation만 승인했다.
  cheapest-repair를 first-feasible로 바꾸거나 후보를 임의로 제거하거나 cap을 늘리지 않는다.
  global Exact 및 production compact 기본값은 이 단계에서 바꾸지 않는다.
- **잔여/회귀 위험:** compact 경로는 equal-cost tie의 선택을 바꿀 수 있어 objective 동치와
  assignment 동치를 혼동하면 안 된다. 성능 개선이나 전체 compile 성공은 아직 증명되지 않았다.
  비교 후 조건부 support/quotient, boundary 변화, singleton constants, auxiliary 복원,
  coupled tie 회귀와 signal-free900초 실행으로 production 채택 여부를 판단한다.

- **진단 harness 검증:** 기존 helper에 compact argument가 없어 실패하는 RED를 보존한 후
  `--diagnostic-compact`를 구현했다. coordinator-only option/CLI 제한/identity·measurement·result
  binding을 검증했고 독립 diff review는 findings0/APPROVE다. compact flag만 남은 manifest도
  gate를 닫는 회귀와 CSV label을 추가한 최종 Python matrix77/77, bash syntax 및 diff-check가 통과했다.
  증거는 `diagnostic-compact-harness-evidence`에 보존한다. 새 production JAR를 만들지 않았고
  위 baseline JAR SHA를 그대로 사용한다.

- **diag03 JFR 교차 확인:** 경계 이후 main-thread25,846 표본 중 exact solve21,549(83.375%)이며
  상대61.008초부터 기록 종료284.689초까지 bootstrap solve에 머문다. incremental optimizer 표본은0이다.
  전체 execution 표본의99.996%가 main thread이고 GC pause 합은624.890ms다.
  primitive kernel의 main sampled allocation weight는531.858GB, PreciseCost는415.320GB이며
  이 중 infinity early-return record가402.696GB다. 이는 표본 가중치이지 실제 heap/retained bytes가 아니다.
  이전 진단보다 allocation 표본은 낮아졌지만 두 결과 모두 검열되어 wall-time speedup은 주장하지 않는다.
  `diag03/jfr-analysis/REPORT.md` SHA
  `fa9544124660a8049e73ae4d34e7b72cb450658f582572f5ea94615f9884393a`, analyzer 재실행 일치 PASS.
  방대한 대입 열거 자체를 줄이는 exact preprocessing 비교가 잔여 allocation 미세 최적화보다 우선이다.

## diag04 조건부 reduction 효과 확인 및 후속 emission 실패 — 진행 중

- **환경/재현:** 같은 JAR `0ab951c1…c037`, DP/logreg/W3/LAN,24/16GiB,300초,
  `run_LAN_docker.sh --campaign --diagnostic-jfr --diagnostic-compact --phase compile --max-cells 1`.
  root `w1357-policy-matrix-20260928-diag04`, attempt `01790605933585279631-5e135360`.
  baseline과 source/probe/stage/helpers/limits, 첫 원본 block/변수 key/domain/factor를 동일하게 검증했다.
- **효과:** 첫 block이70→31 compiled variables, 입력 table49,793,890→1,070,611 cells,
  VE 대입658,322,977,640→179,697,439로 줄었다(작업량 약3,663.5분의1; wall-time speedup 아님).
  첫 조건부 전처리는0.354822초, 두 번째0.038034초였다. seed49.684107초 중 exact solve6.170912초로
  완료됐고 planner56.545382초에 canonical bootstrap 검증을 통과했다.
- **인증 한계:** incremental 단계는 `RESOURCE_INITIAL`에서 lower0/upper1682.6752882754172/gap∞로
  종료됐다. 기존8M retained-slot cap을 그대로 지켰고, 이를 목표 gap 달성 또는 최적성 증명으로 부르지 않는다.
- **새 실패:** PLAN_CONVERSION 이후 `PlacementEmissionTransaction.resolveAnchor:1149`에서
  `Relocation has no durable analysis-owned anchor`로 rc1, process74.673136초에 종료됐다.
  따라서 이 실행은 **compile 실패**이며 full896 gate에 들어가지 않는다. anchor 요구를 완화하지 않고
  selected realization→analysis anchor→emission projection 연결을 추적한다.
- **검증/보존:** `diag04/compact-comparison.json`에 trace SHA/통계/동일 입력 검증/health를 보존했다.
  JFR SHA `f3e3739a2f315ef4eaf8afa07c9a818dd5aa06ba063967234337522e4b75c76f`.
  coordinator peak7,149,244,416 bytes, OOM0, exact cleanup 및8개 lease 해제 모두 확인했다.
- **수정 계획/근거:** 기존 local compact 경로를 기본 활성화하는 최소 변경을 독립 architect가
  구현·검증 대상으로 승인했다. 임계치에 따른 새 선택 정책은 도입하지 않는다. global Exact 설정,
  후보·비용·cheapest repair·cap은 바꾸지 않는다. 새7개 shared 회귀와 기존54개, 총61/61이 통과했고
  조건을 고정해야만 quotient/singleton이 활성화되는 fixture에서 실제 제거 작업 감소까지 확인했다.
- **잔여/회귀 위험:** equal-cost 선택이 이후 DP 경로/최종 비용을 바꿀 수 있다. 원래 domain으로의
  복원·feasibility·conditional optimum을 검증하며 plan identity parity는 주장하지 않는다.
  shared auxiliary 직접 fixture gap은 남고 reducer auxiliary/hash-collision 회귀가 이를 부분 보완한다.
  anchor 문제와 기본값 변경을 각각 회귀 검증한 뒤 새 JAR/root의 signal-free900초 실행이 필요하다.

### Local compact 기본값 활성화 — 단위 회귀 통과, E2E 대기

- `LocalCategoricalOptimizer.configuredCompaction`의 unset 기본값만 false→true로 변경했다.
  explicit false/true(대소문자 무관), invalid option 실패와 property 복원을 유지한다.
  기존25개 baseline을 통과한 뒤 default-on assertion RED1/6을 기록했고 수정 후 shared/reducer/local/
  incremental8개 클래스86/86이 통과했다. 새 activation fixture 포함 독립 diff review는 APPROVE다.
- `ExactPhysicalOptimizer`, reducer, elimination-order policy의 source SHA는 변경 전과 동일하다.
  global Exact의 별도 설정, 전체 후보·비용·authority·cheapest repair·cap을 유지했다.
  compact일 때 기존 legacy `regional.fastBlockOrder`는 적용되지 않는 호환성 주의사항을 문서화했다.
- 증거: `regional-compact-adoption-evidence/{PLAN.md,baseline-xml,default-on-red.xml,green-counts.json}`.
  전체 frozen 결합 회귀와 실제 compile/runtime 성공은 아직 이 통과 주장에 포함하지 않는다.

### Emission anchor 권한과 live Hop hint의 계약 불일치 — 수정 계획

- **확인된 사실/한계:** 실패는 선택된 action에 exact-record-equal `Node.anchors()` occurrence가
  없음을 증명한다. details=false 진단이라 해당 action 상세는 출력되지 않아, W3의 구체적인 alias
  종류나 same-physical-pool occurrence 존재는 아직 증명하지 못했다. 이를 특정 alias 원인으로 단정하지 않는다.
- **소스 계약:** emission의 `exactRelocations`는 program/analysis/plan hash, candidate realization,
  graph-owned action, source/active obligation/privacy를 검증하고 canonical graph action을 반환한다.
  그런데 `resolveAnchor`가 추가로 live Hop occurrence의 record equality를 필수로 요구한다.
  이미 DAG lowering은 구체적인 durable key를 우선 사용하며 `anchorHopId=-1`을 정식으로 지원한다.
- **안전한 수정 방향:** REFED에 한해 검증된 action 자신의 runtime anchor key를 먼저 직렬화/검증한다.
  기존 exact-record Hop hint가 있으면 deterministic하게 유지하고, 없으면 **명시적인 -1 hint와
  검증된 action metadata**를 전달한다. action key/signature/FType/source/consumer obligation은
  변경하지 않는다. 권한 또는 key 직렬화 실패를 catch하여 -1로 바꾸지 않는다.
  derived-FOUT의 별도 owner 계약과 DAG의 live/key conflict 검사는 그대로 둔다.
- **왜 pool alias를 대신 쓰지 않는가:** `samePhysicalWorkerPool`은 full geometry equality가 아니다.
  특히 FULL/BROADCAST의 같은 endpoint pool이라도 key가 달라 live/key 충돌을 만들 수 있다.
  임의 alias anchor를 선택하거나 action key를 교체하지 않고 이미 지원되는 metadata 경로를 사용한다.
- **원칙/검증 계획:** 이것은 runtime fallback이나 가짜 anchor가 아니라 검증된 계획의 metadata emission이다.
  exact hint 유지, owned-action/no-live-hint lowering, foreign/inactive/altered obligation의 비변경 실패,
  malformed key, FULL/BROADCAST geometry, registry roundtrip/rollback/live-key conflict를 회귀로 고정한다.
  독립 architecture는 bounded 설계만 승인했으며 W3 해결 여부는 이후 실제 compile로 판정한다.

### REFED durable metadata emission 구현 및 결합 회귀

- `PlacementEmissionTransaction`의 REFED 경로가 canonical action key를 먼저 직렬화한 뒤
  private `exactAnchorHopHint`에서 기존 exact-record occurrence의 deterministic Hop ID 또는 -1을
  반환한다. consumer/source/action signature/materialization FType는 그대로이고 alias 검색은 없다.
  기존 `exactRelocations` 권한·privacy·obligation 검증, derived-FOUT owner 계약, DAG live/key 충돌
  검사 및 rollback은 변경하지 않았다.
- 새 회귀는 canonical graph action을 유지하면서 exact node-anchor 중복만 제거하고,
  같은 worker의 다른 geometry FULL decoy를 둔다. 실제 `Dag.getJobs`까지 실행해 -1 hint,
  action key, FType/signature/exact consumer bindings와 concrete `fed_refed`를 확인한다.
  기존 exact hint의 선택 순서도 검증한다. intended RED는 기존 missing-anchor 예외로 실패했다.
- malformed/unsupported metadata 검사는 constructor/직렬화 경계의 거절을 검증한다.
  이 검사 자체가 `emit` 전체 atomicity를 증명하는 것은 아니며, 기존 foreign/stale 및 injected-failure
  transaction 회귀와 production prevalidation 순서가 별도로 이를 검증한다.
- **검증:** emission16/16, 기존 live/key conflict1/1 통과; 독립 실제 diff review APPROVE.
  local compact 변경까지 같은 source에서 결합한31개 클래스237/237(실패/오류/skip0),
  Python matrix77/77, bash syntax/diff-check가 통과했다. 원본/XML/command/count는
  `emission-durable-metadata-evidence`에 보존했다. 최초 fixture normalization/structure-guard 오류도
  intended engine RED와 구분해 보존했다.
- **잔여:** 과거 별도 certificate cap/GLM heap/fixture expectation/외부 provenance 테스트 gap은
  이 green 집합에 포함하지 않았다. W3 실제 compile 성공과 full896/runtime 성공은 아직 미확인이다.
  새 package 후 새 `run04`에서 JFR/trace 없이 고정900초 W3/LAN을 먼저 검증한다.

## run04 W3/LAN production compile 회귀 통과 — 전체 행렬 실행 중

- **빌드/실행:** commit `ca7d8eb67fcffe4e576c4a1cd935dd24e4969d12`와 package exit0,
  JAR SHA `0b4cfe5dbc41c502f70fa65774f77c6b53eb6d6064e82f78a928e392c3f7152b`를 동결했다.
  새 `w1357-policy-matrix-20260928-run04`의 DP/logreg/W3/LAN을 원래900초·자원·입력·network로
  JFR/상세 trace/진단 옵션 없이 실행했다. attempt `01790607659896617038-a16591d3`.
- **결과:** compile77.969562초, shared search-space12.047577초,
  selection/adapter62.934620초, full-initial-planning77.967181초로 통과했다.
  이전900초 timeout 대비 이 조건이 해결됐음을 증명하지만 검열된 이전 시간을 정확한 speedup 분모로 쓰지 않는다.
- **검증:** RuntimeProgram 생성, 실제 compile-only flag, workload start/complete=false,
  execution/run/Spark/runtime-instruction/federated-dispatch/worker-fragment0을 확인했다.
  planned/lowered physical Hops204/204, missing/mismatch0, synthetic4건 모두 lowering 일치다.
  정확한 container ID/name cleanup과 첫 실행의8개 lease 해제를 확인했다. OOM0이다.
  `run04/regression-verification.json`에 결과 SHA와 health/audit를 보존했다.
- **진행 상태:** 동일 engine/root로 `--phase all`을 이어서 실행한다. 이 시점의 성공은1/896뿐이며,
  다른 엔진·diagnostic의 성공을 합산하지 않는다. full896 compile gate 이전 runtime은 계속 차단한다.
- **범위 확정:** 사용자가 지연 도착한 질의 응답에서 SliceLine을 기존 기본 ADULT·COVTYPE로
  명시했다. 이미 실행 중인896조건과 일치하므로 matrix/manifest/engine을 바꾸거나 재시작하지 않는다.

## 사용자 요청: compile/runtime timeout 60초 고정 — 구현·로컬 회귀 완료

- **환경/증상:** 사용자가 기존 timeout이 너무 길다고 지적하고 60초 고정을 명시했다.
  기존 harness 기본값은 compile900초/runtime3600초였고 CLI로 임의 값을 허용했다.
  timeout이 manifest identity에 없어서 같은 root에 다른 제한의 결과가 들어갈 수 있었다.
- **원칙/결정:** workload 제한만 변경한다. 엔진·공유 후보·비용·privacy·oracle·resource cap은
  그대로 유지하며 시간초과를 성공이나 infeasibility로 해석하지 않는다.
- **기존 실행의 안전한 종료:** run04 Python driver PID2390380의 정확한 argv와 timed
  `subprocess.run` 자식 argv를 확인하고 **driver에만 SIGINT**를 보냈다.
  JVM/GNU timeout/process group에 직접 신호를 보내지 않았다. 기존 finally가 exact-owned
  4개 container ID/name 부재와 8개 stage lease 해제를 증명했고 driver는 rc130으로 종료했다.
  run04는22 passed/1 interrupted-as-failed/873 pending이며 runtime0이다.
  중단 attempt `01790610644952265544-d3b665b1`은 정책 전환이지 새 엔진 실패나 자연 timeout이 아니다.
  기존 result를 재작성하지 않고 `run04/timeout60-transition.json`에 원인·증거를 별도 보존했다.
- **변경 요약:** compile/runtime CLI 기본값과 허용값을 모두60으로 고정했다. helper 직접 호출도
 60초를 사용한다. identity/measurement에 단계별60초를 동결하고 result/CSV에 명시한다.
  gate는60초 provenance가 없는 성공도 거절하므로 기존 장기 timeout 실행을 승계할 수 없다.
  진단 CSV에서는 timing만 비우고 timeout 정책은 보존한다.
- **시간 의미:** coordinator의 compile-only 또는 compile+runtime JVM 전체에60초 후 TERM을
  보낸다. 기존 kill-after30초는 종료 유예이고 SSH/setup/로그 수집/cleanup도 별도다.
  따라서 컨테이너 한 조건의 전체 lifecycle wall time까지60초라는 뜻은 아니다.
  종료 유예 중 끝난 작업도 GNU timeout 실패를 성공으로 바꾸지 않는다.
- **수정 파일:** `scripts/fedplanner/run_matrix_campaign.py`,
  `scripts/fedplanner/tests/test_run_matrix_campaign.py`,
  `scripts/fedplanner/tests/test_matrix_jfr_diagnostic.py`, campaign/progress 문서.
- **검증:** 변경 전77개 baseline PASS, 새 정책의 intended RED 보존, 수정 후83/83 matrix
  회귀 PASS. CLI 양쪽 기본/명시60, 다른 값 거절, frozen identity/resume mismatch,
  gate의 missing/900/3600 provenance 거절, 실제 mock command 양쪽60·rc124 실패·cleanup,
  CSV 실패 timing 공란과 진단 정책 보존을 확인했다. bash syntax/py_compile/diff-check PASS.
  엔진 JAR SHA `0b4cfe5dbc41c502f70fa65774f77c6b53eb6d6064e82f78a928e392c3f7152b`는 불변이다.
- **재개/잔여:** 새 `w1357-policy-matrix-20260928-run05`에서 `run_LAN_docker.sh --campaign`
  `--phase all --keep-going`으로60초 compile survey를 재개한다. 실패도 보존하여 다른 조건의
  측정을 계속하지만 full896 compile 성공 이전 runtime은 차단한다. 독립 review 및 실제60초
  natural timeout/cleanup 확인은 이어서 기록한다.
- **잠재 회귀 위험:** 기존 성공 중60초보다 긴 조건은 새 기준에서 timeout이 될 수 있다.
  timeout을 수치 시간60초의 성공 표본으로 넣지 않고 성공 timing 공란/실패로 감지한다.
  유예/정리 시간을 compile 시간으로 오인하지 않도록 command/receipt/process 기록을 구분한다.
- **증거 경로:** `/grid/3/cofee-lm-sweep-mchoi-20260914/matrix-timeout60-evidence/`
  (`PLAN.md`, `baseline.log`, `red.log`, `green.log`) 및 run04 transition/cleanup/lease-release.
- **독립 review 후 보강:** reviewer가 identity만 검사하면 measurement-only timeout 변경을
  resume에서 놓치는 provenance 불일치를 발견했다. 실행/게이트의60초 강제에는 영향이 없었지만,
  측정 metadata도 동결한다는 계약에 맞게 resume 시 명시적으로 일치 검사를 추가했다.
  JSON deep copy 후 identity/measurement 각각의 missing/900 변조를 독립 검사한다.
  measurement-only2건 intended RED를 보존했고 최종83/83 및 syntax/diff 검사가 통과했다.
- **최종 독립 review:** measurement-only guard와 회귀 보강까지 실제 diff 재검토 APPROVE(추가 findings0). 실행 전 최종83/83 결과는 root가 확인했다.

### run05 실제60초 timeout 및 정리 검증 — 전체 compile survey 계속

- **동결/재개:** `551fe1cfeed53e49c4d5559c5fe2b0b6314938bb`를 origin에 푸시하고
  clean tree에서 새 run05를 시작했다. identity/measurement의 compile/runtime60초,
  기존 JAR 불변,896조건, runtime 미시작을 확인했다.
- **실제 관측(2026-09-28 16:04:17 UTC):** DP/logreg/W1의 LAN 및 WAN-light가 각60초
  설정으로 자연 timeout(rc124) 처리됐다. 종료/SSH 회수까지의 process wall time은
  61.691963초와62.090587초다. compile/search-space/planning 성공 시간은 채우지 않았다.
- **안전/게이트:** 두 건 모두 exact-owned2개 container ID/name 부재와 OOM0을 검증했다.
  CSV896행에 timeout60/failed/성공 timing 공란을 확인했다. snapshot은0통과/2실패/894대기,
  compile gate=false/runtime0이며 다음 WAN-mid 조건으로 진행한다. 실행 중 stage lease는
  정상적으로 유지되며 이전 run04의 lease release 증거와 혼동하지 않는다.
- **원인/한계:** 이 timeout은 새60초 한도에서의 미완료이며 infeasibility가 아니다.
  같은 엔진의 과거 W1/LAN206.913초·WAN-light211.483초 완료 기록이 있으므로 한도 단축과
  일치하지만 그 과거 성공을 새 결과로 승계하지 않는다. 전체 실패 원인 분석과 성능 개선은
  후속 과제이며 아직4개 플래너 전체 결과나 runtime 성공을 주장하지 않는다.
- **증거:** `run05/timeout60-verification.json`에 두 result SHA, command60, raw process time,
  cleanup/OOM/CSV/gate 검증과 고정 snapshot을 보존했다. 회귀 suite83/83 및 독립 review와 별개로
  실제 Docker timeout 작동을 확인한 것이다. 현재 session27440의 survey는 계속 실행 중이다.

## 조기 privacy pruning 및 공통 후보 축소 계획 — 분석/계획 완료, 미구현

- **요청/증상:** 사용자가 큰 search space를 줄이기 위해 privacy를 가능한 가장 초기에 적용하고
  추가 pruning 계획을 요청했다. 이번 작업 범위는 계획이며 active run05의 엔진/harness는 변경하지 않는다.
- **원인/현재 사실:** privacy는 마지막 선택 때만 검사하는 것이 아니다. 최초 input Cartesian
  생성 이후 boundary/function/privacy를 닫고 physical/replay/export에서도 재적용한다.
  따라서 첫 tuple/Oracle/emission 확장에서는 나중에 privacy로 탈락할 대안을 먼저 만드는 비용이 남는다.
  최종 합법 후보 공간과 중간 생성 작업량을 구분하며, 이 사실만으로 현재 DP timeout의 지배 원인이라고
  단정하거나 모든60초 실패가 해소될 것이라고 약속하지 않는다.
- **발견한 구현 경계:** (1) output privacy와 protected input payload 접근은 다른 제약,
  (2) occurrence/value-version/CFG/function 정보가 초기에는 미완성,
  (3) PRIVACY_EXCLUDED 존재를 privacyAlreadyClosed로 쓰는 stage coupling,
  (4) excluded row도 실제 capability/profile이 필요한 domain/lookup 계약,
  (5) raw Cartesian completeness와 pre/published audit가 미생성 tuple을 놓칠 수 있음.
- **계획/원칙:** 기존 판정기를 재사용해 확정된 금지만 먼저 거절하고 UNKNOWN은 보존한다.
  P0 회귀/계측·명시적 stage authority → P1 불법 emission의 support/realization 생성 억제 →
  P2 certified consumer mask·prefix rejection과 원래 unmasked-domain coverage →
  P3 exact binding semijoin·post-rebind deletion worklist 순서다.
  DP/Exact factor-freeze 전 hard support/표현 개선은 별도 후순위이며, 기존 conditional compaction을
  새 기능으로 재구현하지 않는다. top-K/cap 상향/정책별 공통 후보 삭제/runtime fallback은 제외한다.
- **수정 파일:** 계획 문서 `docs/FEDPLANNER_EARLY_PRIVACY_PRUNING_PLAN_2026-09-28_KO.md`와 본 기록.
  동일 계획을 `.omx/plans/fedplanner-early-privacy-pruning-2026-09-28.md`에 저장했다.
- **검증:** 독립 read-only explore2개가 privacy 파이프라인·현재 pruning·domain/audit 의존성을 조사했고,
  architect가 실제 계획 문서를 검토하여 APPROVE했다. 권고에 따라 가설적 profile의 정확한
  consumer/source/revision authority와 동일 unmasked domain revision의 coverage 기준을 명시했다.
  이는 소스 기반 설계 검토다. 이번 계획에 대해 새 Java/Python 테스트 또는 성능 workload를 돌리지 않았다.
- **잔여/회귀 위험:** early emission suppression과 진짜 tuple omission은 별도 구현 게이트가 필요하다.
  temporary bottom/loop widening, OR 대안과 모든 reaching writer, no-op/실제 relocation, same-row
  derived source를 잃지 않아야 한다. 작은 전체 합법 계획 집합 parity·인증 없는 누락 rejection·
  canonical survivor 순서 및 실제 생성 counter로 검출한다. 구현/성능 개선은 아직 미검증이다.
- **진행 중 실험:** 2026-09-28 16:59:37UTC run05는 compile30 passed/16 failed/850 pending,
  runtime0이었다. 이 수치는 기존60초 baseline이고 새 pruning 결과가 아니다. 측정은 그대로 계속한다.

## 조기 privacy pruning 구현 — 진행 중 (baseline 동결/회귀 고정 완료)

- **요청/상태:** 계획대로 구현·검증 요청을 받아 P0–P3를 구현 중이다. 앞 절의 ‘미구현/측정 계속’은
  계획 시점 기록이며, 현재 baseline run05는 아래와 같이 안전하게 종료했다.
- **baseline 보존:** driver PID/argv 소유권을 확인하고 timed child가 종료·정리되도록 Python driver에만
  SIGINT를 보냈다. 2026-09-28 17:26:55 UTC exact-owned container 6개 부재와 host lease 8개 해제를 확인.
  최종 59 passed / 16 자연 60초 timeout / 1 사용자 지시 전환 중단 / 820 pending, runtime0이다.
  전환 중단은 planner 자체 실패와 구분한다. 증거: run05/privacy-pruning-transition.json.
- **재현/증거 루트:** `/grid/3/cofee-lm-sweep-mchoi-20260914/early-privacy-pruning-evidence`.
  baseline11개 Java suite 통과 후 새 protected corpus4개에서 AVAILABLE rule/emission/realization/
  OR support/binding/action/노드 합법 상태 전체의 canonical SHA를 고정했다. 동일 script를 다시
  compile하여 process-global Hop ID를 배제했는지도 확인했다. 기존 raw cost/완전 계획 검증도 후속 유지한다.
- **확인된 원인/수정:**
  1. `PRIVACY_EXCLUDED` row 유무가 privacy 완료 단계를 대신하던 코드를 명시적인 완료 상태로 분리.
  2. CFG value version·function boundary·compiled input edge가 완성되는 첫 지점에서 privacy **값 분석만**
     분리해 실행한다. 전체 privacy closure의 temporary-bottom 실패 검사는 당기지 않는다.
     초기 PUBLIC seed는 authority로 재사용하지 않으며, worker metadata는 build 내 캐시로 중복 취득하지 않는다.
  3. 실제 Oracle capability/profile을 보존하는 emission suppression을 후속 support 확장 앞으로 이동.
     generator는 같은 ExecPlacementPolicy kernel을 사용하고 derived FOUT의 같은-row source도 요구한다.
  4. certified protected-payload operand의 local tuple을 product 전에 제외하는 좁은 불변 증거를 추가 중.
     미생성 tuple에 가짜 capability를 만들지 않으며 original domain revision/count/typed lookup/audit를 검증한다.
  5. relocation binding product는 같은 owner의 상충하는 exact source만 prefix에서 거절한다.
     서로 다른 owner의 geometry를 임의로 같게 요구하지 않는다.
  6. action rebind 후 삭제 전용 snapshot에서 reverse support index/worklist로 연쇄 삭제한다.
     OR sibling과 모든 reaching writer, duplicate-reference live count, canonical survivor 순서를 유지한다.
- **검증(중간):** P1/P3 첫 단계47개 통과, 이어 earliest structural authority 적용 후49개 통과.
  old-engine golden4개 불변. Worklist는 기존 full-pass 고정점과 32 seed ×24 row 무작위 graph 및
  cascade/cycle/action/all-writer/duplicate reference differential7개 통과. Prefix3개 통과.
  추가 P2 coverage/실제 Oracle·tuple 감소 테스트는 현재 실행/확장 중이며 아직 완료로 주장하지 않는다.
- **남은 작업/회귀 위험:** P2 domain completeness/foreign·stale evidence와 generation metric 감소,
  광범위 privacy/CFG/plan-space/cost 회귀, 독립 diff review, package 및 새 Docker root 검증이 남았다.
  최종 합법 plan 공간을 줄이지 않고 중간 불법 작업을 제거하는 변경이다. 60초 timeout 해결은 미확정.
  기존 성공 결과를 새 엔진 결과로 합산하지 않으며 896 compile 통과 전 runtime은 시작하지 않는다.
- **규칙 근거:** 기존 privacy policy와 exact selected-source/global legality만 앞당긴다.
  top-K/임의 cap/플래너 선호 기반 후보 삭제/TR·TW 완화/runtime fallback은 도입하지 않았다.

### 확장 회귀에서 발견한 비용 receipt/loop seed 차이 — 원인 분리 중

- **상태/증상:** focused48개 통과 후61개 class395개 테스트로 넓히자 failure2/error2/skipped10을 발견했다.
  `ExactNativeLocalAnchorFanoutCostTest`의 contribution fingerprint가 달라지고,
  `FedFirstRemoteInputPreferenceTest`의 StepLm에서 loop TRead X_global이 모든 reaching writer를
  유지하지 못했다. 최종 합법 공간·비용 보존을 입증하기 전에는 구현 완료로 취급하지 않는다.
- **baseline 분리:** 기존 JAR SHA `0b4cfe5dbc41c502f70fa65774f77c6b53eb6d6064e82f78a928e392c3f7152b`로
  실패4개 class12개 테스트를 다시 실행했다. 비용 fingerprint와 StepLm은 baseline에서 통과했으므로
  새 회귀다. 반면 `ProductionDecodedPlanSpaceCompletenessTest`의1344대208과
  `GlmPrivateAggregatePlanningContractTest`의 post-CFG non-monotone 오류는 baseline에서도 재현됐다.
  기존 실패를 새 pruning의 회귀와 혼동하지 않고 별도 추적한다.
- **원인/해결 계획:** 비용 receipt가 진단상 excluded row 변화에 결합되는지와 실제 factor 구조/raw
  double bits가 바뀌었는지를 분리한다. StepLm은 초기 후보 suppression과 loop seed의 temporary-bottom
  계약을 조사한다. 기대값을 단순 갱신하거나 후보를 임의로 삭제하는 방식으로 통과시키지 않는다.
- **검증/증거:** evidence root의 `regression.log`, `regression-xml/`, `baseline-four-failures.log`,
  `baseline-junit-classpath.txt` 및 `current-junit-classpath.txt`. 최신 certificate6개 테스트는 통과했다.
- **잔여/회귀 위험:** 두 새 회귀의 수정과 동일 광범위 회귀 재실행 전 package/commit/새 Docker 실행은
  보류한다. timeout60초, runtime896 compile gate, TR/TW와 no-fallback 계약은 유지한다.

### 확장 회귀 원인 확인 및 seed-scope 교정

- **StepLm 해결:** privacy 값 관계가 확정되는 것과 loop/function의 물리 후보·reaching writer가 닫히는
  것은 다르다. 전역 privacy projection의 존재만으로 pre-closure 후보를 차단한 것이 원인이었다.
  실제 privacy closure 전에는 complete same-block seed에만 emission suppression/input mask를
  허용하며, 그 이후에 전역 authority를 사용한다. 두 seed map의 fact는 structural boundary에서
  canonical identity로 다시 연결해 이후 replay의 certificate가 stale 객체를 갖지 않도록 했다.
- **검증:** 기존 엔진/early-off/seed-scope-only overlay의 StepLm 통과, 원래 변경본의 실패를 확인했다.
  최종 소스에서 StepLm과 privacy5개 class32개 테스트가 모두 통과했고 tuple/Oracle26→24 감소는 유지된다.
  증거 `steplm-seedscope-probe/`, `seed-scope-repair.log`, `seed-scope-repair-xml/`.
- **비용 receipt 원인 확정:** `cost-probe/`에서 old JAR와 현재 class의 code source를 각각 확인하고
  세 hash와 전체 candidate signature를 비교했다. analysis fingerprint, factor structure SHA
  `6eabb96f81f1c831a67ee82f76775d1f8ea8e64dd7b7cd0776a09f7cb674c4d3`, raw double-bits SHA
  `f949de6c7f44f5bc770ab2e3ec0966f3f94a0c6d2be74e7e898c92b02444909d`가 모두 동일하다.
  유일한 fact 차이는 sum 입력 ABSENT_LOCAL의 기존 PRIVACY_EXCLUDED row 한 개가 certified mask로
  미생성되는 것이다. 모든 row에 결합된 optimization receipt fingerprint는 의도적으로 달라져야 하며,
  실제 비용/합법 대안 변경은 아니다. 비용 raw bits·구조 기준값은 변경하지 않는다.
- **기존 completeness 테스트 진단:**1344는 독립적인 합법8개 계획 수가 아니라 과거 proof-clause
  multiplicity(X2,Y2,U4,V4,D21)의 Cartesian 크기였다. 기존 엔진도 현재 canonical support
  X2,Y2,U2,V2,D13=208을 생성한다. 해당 숫자 assertion만 제거한 isolated overlay에서 나머지
  모든 raw receipt/assignment 분류, 독립 literal8계획 equality, source/geometry 각각 제거 시
  정확히4계획 누락 mutation이 통과했다. 따라서1344를208로 바꿔 고정하지 않고 숫자는 진단으로
  내리며 실제 완전성·mutation 검증은 그대로 유지한다. 증거 `decoded-support-probe/`.
- **잔여 이슈:** 기존 GLM 오류는 별도 원인 분석 중이며 아직 해결/전체 green으로 주장하지 않는다.
  최신 matrix harness83/83 회귀도 통과했으며 workload timeout 정책은 변경하지 않았다.

### P0–P3 최종 코드 검토·package 및 남은 GLM 경계

- **최종 검증:**61개 class395개에서384 통과/1 GLM 오류/10 기존 skip. 새 StepLm 회귀와 비용 receipt
  차이는 해소됐고, 마지막 fixture-local certificate 보강 후 비용·전체계획2개 class8개도 통과했다.
  matrix Python83개, `bash -n`, `py_compile`, `git diff --check` 통과. 독립 reviewer는 seed-scope,
  certificate/receipt, 의미적 completeness 변경을 검토했으며 마지막 scoped diff APPROVE/findings0이다.
- **package:** `mvn -q -DskipTests package` 성공(exit0). 이는 전체 테스트 green이라는 의미가 아니다.
  새 JAR SHA `100628da2bb3a489a43247d7d887d24aca81f41faa18ba98394e072a246d4dd2`.
  기존 baseline JAR는 evidence root의 `baseline-SystemDS.jar`로 보존했다.
- **GLM 미해결:** baseline에도 실패했던 worker1 private-aggregate GLM은 이제 binomial cbind
  `glm.dml:916`의 privacy-safe 후보 부재에서 중단한다. read-only 진단은 shared 함수 formal의
  endpoint/cardinality join에서 하나의 UNKNOWN call argument가 FULL single-partition 증명을
  없애는 경계를 추적 중이다. 전역 worker1을 exact 후보의 single-partition 증명으로 대신하거나,
  CP 수집을 허용하지 않는다. 해당 테스트를 ignore/삭제하지 않았으며 별도 남은 correctness gap이다.
- **후속:** 구현 보고서는 `FEDPLANNER_EARLY_PRIVACY_PRUNING_IMPLEMENTATION_2026-09-28_KO.md`.
  새 엔진 Docker compile smoke와896 compile survey를 새 root에서 실행하되 성공으로 섞지 않는다.
  전체 compile gate가 거짓인 동안 runtime은0으로 유지한다.

### 새 P0–P3 엔진 Docker smoke 확인

- **상태:** pushed `a9b1ca711e`, JAR100628da…를 clean tree에서 run06으로 동결.
  DP-local/l2svm/W1/LAN compile11.133612초, search-space6.777721795초,
  selection-adapter3.153528349초로60초 안에 통과. runtime-program 생성/audit mismatch0,
  workload runtime0, cleanup 및8개 lease 해제를 확인했다.
- **비교 한계:** old run05 동일조건11.722002/6.494225690/3.973990369초이며 selected plan hash는 같다.
  각1회 sample이므로 유의한 속도 개선을 주장하지 않는다. 특히 search-space wall time은 증가했으므로
  tuple/Oracle counter 감소와 실측 시간 개선을 구분한다.
- **잔여:** 새 engine896조건 중1성공/895대기, runtime gate=false. 기존 GLM은 UNKNOWN actual의
  compiled-input/cardinality edge 추적까지 완료됐지만 아직 패치하지 않았다. source/harness는
  측정 중 동결하고 나머지 compile survey를 계속한다.
- **증거:** run06/summary.json, compile-comparison.csv; evidence/run06-smoke-verification.json,
  glm-baseline-diagnosis/. 상세내용은 P0–P3 구현 보고서 참조.
