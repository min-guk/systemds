# Session issues — 2026-09-29

## 1. 리팩토링 이전 대비 compile 성능 저하: 원인 분리와 회복 계획

- **상태:** 조사·계획 완료, 새 최적화 구현 전. 이전 조기 privacy P0–P3 구현과 이번 계획을 구분한다.
- **요청:** 과거 60초 안에 완료하던 기록과 리팩토링 commit을 확인하고, search-space/DP 이후 비용에 대해 추가 pruning·자료구조·재계산 제거 계획 작성.
- **환경/조건:** `/home/mchoi/w1357-paper-aligned-refactor`, branch `refactor/w1357-paper-aligned-20260928`, 조사 HEAD `5a3ef7d759bf30ee2d848f7123fd963c4d5bbd57`; run06 JAR `100628da2bb3a489a43247d7d887d24aca81f41faa18ba98394e072a246d4dd2`.
- **재현/관측 경로:** `/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-policy-matrix-20260928-run06/`; 기존 driver 명령은 `python3 -u scripts/fedplanner/run_matrix_campaign.py --root <run06> --phase all --keep-going --compile-timeout 60 --runtime-timeout 60`. 이 세션에서는 재실행하지 않고 보존된 receipt/phase marker를 읽었다.
- **관측 증상:** 2026-09-29 04:46:09 UTC 스냅샷은 compile 542 pass / 44 fail / 310 pending, runtime 0. 32 DP-local logreg/GLM timeout은 모두 shared analysis 완료 후 planner 내부에서 발생했다. SliceLine W1 `EXACT_VE_NO_FEASIBLE_ASSIGNMENT` 8건과 network gate invalid 4건은 별도 분류한다.
- **원인 분석:**
  - 9/10 실제 빠른 compile 기록 2,015/2,016 pass, compilation 1.186–14.195초를 확인했다. native compile-only / Global·V6·V8 / overlay engine이므로 현재 Docker 엔진과 직접 배율을 비교할 수 없다.
  - 9/22 logreg DP compile 약 98초 등 **9/28 extraction 이전**의 지연도 있다. candidate 표현·완전성 변경과 cap 변경을 파일 분리와 구분해야 한다.
  - 현재 input-authority factor의 넓은 scope, dense freeze 후 support reduction, cell당 Map/List 생성, seed/root 준비의 incremental budget 밖 실행은 코드에서 확인됐다. 현재 timeout의 함수별 시간 비중은 미측정이다.
  - 과거 diag04는 root preparation 약 42.87초 중 freeze 약 36.37초였다. 당시 프로파일은 현재 JAR의 직접 원인 증명이 아니며, conditioned compaction은 이미 기본값이다.
  - shared owner commit 이후 전체 proof inventory 재구축, 생성 후 no-op 비교, profile inference 반복은 추가 검증할 재계산 경로다. 최신 privacy 전후 공통 성공 58쌍은 median compile 비율 0.998/search-space 1.009로 큰 개선을 입증하지 못했다.
- **해결 방법/변경 요약:** `FEDPLANNER_REFACTOR_PERFORMANCE_RECOVERY_PLAN_2026-09-29_KO.md`에 R0–R6 순서 작성. DP illegal authority/pool prefix pruning과 allocation-free factor → exact dependency/structured support → shared revision reuse → 측정된 후처리 개선 → 전체 896 compile gate 순서다. 이 단계에서는 production 코드를 수정하지 않았다.
- **의사결정 근거:** runtime/oracle/privacy/global legality로 증명된 불법 조합만 조기 제거. 비싼 합법 후보를 임의 삭제하지 않으며 네 planner 공통 공간, TRead/TWrite 및 recompile 제약, runtime fallback 금지, 60초 timeout을 유지한다.
- **수정 파일:**
  - `docs/FEDPLANNER_REFACTOR_PERFORMANCE_RECOVERY_PLAN_2026-09-29_KO.md`
  - `.omx/plans/fedplanner-refactor-performance-recovery-2026-09-29.md` (동일 계획 사본, local OMX artifact)
  - `docs/SESSION_ISSUES_2026-09-29.md`
- **검증 방법/결과:** git 이력·현재 소스·기존 실험 CSV/JSON/JFR 문서를 읽고 phase/실패를 재집계했다. 이번 작업은 문서 검증이며 Java/Python test나 새 Docker 성능 실행은 하지 않았다. 문서 최종 검토 결과는 아래 검증 기록에 남긴다.
- **잔여 버그:** 32 DP timeout, SliceLine W1 8건, network invalid 4건은 이 스냅샷 기준 미해결. 이전 GLM/cardinality 단위 fixture의 알려진 차이도 별도 관리한다. 전체 compile gate가 거짓이어서 runtime을 시작하지 않았다.
- **수정으로 인한 버그 가능성:** 현재는 문서 변경만이므로 실행 동작 변화 없음. 향후 구현에서는 stale revision cache, inactive DIRECT_FOUT 삭제, OR/all-writer 누락, sparse/auxiliary tie 복원 오류가 주요 위험이다. old/new exhaustive complete-assignment·raw-cost·selected-receipt parity 및 negative authority fixture로 감지한다.
- **실행 중 캠페인 보호:** 기존 run06 driver PID 3151697은 2026-09-29 07:03:52 Europe/Berlin 확인 시 실행 중이었다. 중단/엔진교체/추가 부하 실험은 하지 않았다. 향후 구현 측정은 별도 root/JAR를 사용한다.

## 2. 과거 source manifest 참조 경로 소실

- **상태:** 발견, 과거 엔진의 정확한 소스 복원 미완료.
- **문제 정의/증상:** 9/10 `context-new-w1.json`은 `validation/build-11-source-manifest.json`과 SHA `fe99bcfa…`를 가리키지만 현재 그 파일이 없다. 이를 현재 검증된 manifest로 인용하면 복원 가능성을 과장하게 된다.
- **원인:** 왜 해당 경로가 없어졌는지는 확인하지 않았다. context의 기록과 현재 존재 여부를 구분한다.
- **해결 방법:** 계획에서 사라진 경로를 명시하고, 실제 남아 있는 `validation/build-11/{command.json,source-before.json,source-after.json}`을 확인·보존했다. before/after byte SHA는 둘 다 `59dacb8c410bb5fde99cf24a000acba5b2ecb340a3be1000b6e201281fa465c6`다. command의 digest와 직렬화 규칙을 확인하지 않은 채 동일하다고 주장하지 않는다.
- **검증:** `find`/file existence 및 `hashlib.sha256`으로 확인. 이력 자료 사본은 `/grid/3/cofee-lm-sweep-mchoi-20260914/refactor-performance-plan-20260929/historical-20260910-build11-*`에 보존했다.
- **잔여 문제:** hash 목록은 소스 내용 자체가 아니므로 overlay/JAR provenance까지 복원해야 정식 historical A/B가 가능하다. 복원이 안 되면 역사 참고로만 유지한다. 현재 JAR 대비 신규 최적화 비교는 이 복원에 막히지 않는다.
- **잠재 회귀 위험/감지:** 잘못된 옛 소스·privacy·입력으로 “refactor 회귀”를 주장할 위험. source/JAR/input/manifest hash와 합법 공간 parity를 모두 요구한다.
- **의사결정 근거:** 관측 사실과 추정을 분리하고 동일 Docker 조건의 성능 증거만 정식 비교로 채택한다.

## 최종 문서 검증 기록

- **독립 검토:** `/root/performance_recovery_plan_review` read-only critic. 최초 REVISE에서 DP-local logical incidence/repair trace, resource-stop와 budget 경계, SliceLine complete witness 매핑, cache dependency, 일부 근거 경로 보완을 요구했다. 반영 후 최종 **OKAY**. 새 코드에 대한 승인이 아니라 계획 검토다.
- **참조/자료 검증:** source/commit 33개 확인, 스냅샷 542/44/310 및 32 timeout의 `analysis_begin → analysis_end → planner_begin` 경로 확인. 역사 CSV 2,016행, status 2,015 passed/1 failed, compilation 1.185994–14.194674초, planner 0.235059–6.058382초 재계산.
- **문서 일치/변경 경계:** docs 계획과 `.omx/plans` 사본 동일, whitespace 검사 및 `git diff --check` 통과. source/harness 수정 없음. 코드 테스트·새 benchmark는 실행하지 않았다.
- **증거:** `/grid/3/cofee-lm-sweep-mchoi-20260914/refactor-performance-plan-20260929/plan-document-verification.json` 및 `plan-independent-review.md`.
- **다음 실행 단계:** 계획 R0부터 시작한다. 이 문서 작성만으로 미해결 compile/runtime 작업을 완료 처리하지 않는다.

## 3. 여섯 질문 재감사: earliest pruning·증분성·완전성·DP 정책 계약 교정

- **상태:** 현재 코드 감사와 계획 v2 개정. 새 production 최적화는 미구현.
- **문제 정의/요청:** Hop-local/관계 pruning 조건, source 출발 증분 전파 여부, 합법 search-space 보존 및 비교 방법, earliest-safe 적용 여부, DP의 실제 순서와 복잡도 감소를 비판적으로 평가하고 계획 수정.
- **환경/조건:** 위 이슈 1과 같은 HEAD와 frozen run06. native read-only local/graph/DP 감사 세 갈래를 사용했다. 새 테스트·build·workload 실행 및 프로세스 조작은 하지 않았다.
- **관측/원인:**
  1. privacy operand mask와 binding prefix 등은 이미 이르지만, zero-survivor mask 우회(`PlacementRelationClosure:1504–1507`)와 excluded row의 profile 계산(`PlacementCandidateGenerator:391–403`) 등이 남아 있다. 전자는 provisional bottom과 certified terminal bottom을 구분하지 않은 채 제거하면 위험하다.
  2. 같은 block은 root DFS 후위라 producer-first이고 worklist/SCC도 있다. 그러나 physical 호출의 전체 index 준비, CFG pass의 map/edge 준비, direct wave의 전체 boundary closure는 남는다. 프로그램 전체 single-pass/Δ-only라는 설명은 틀리다.
  3. 기존 합법-space golden은 네 corpus이며 receipt independent oracle도 한 selected placement와 현재 row domain에 한정된다. old/new count 또는 최종 plan만 일치한다고 모든 합법 후보 보존이 증명되지는 않는다.
  4. DP는 전역 불법 domain 삭제 외에도 합법 동등 값 quotient와 예산 기반 선택을 한다. 또한 factor scope뿐 아니라 ordinal/count/닫힘/위반 단위가 seed/repair에 영향을 주고, root 표현은 message queue/자원 종료에 영향을 준다. **v1의 logical incidence 보존만으로 정책 동치가 충분하다는 인상과 fixed-budget 선택 동일성 요구를 수정해야 한다.**
- **해결 방법/변경 요약:**
  - 새 감사 보고서에서 L1–L8/G1–G8의 단위·authority·현재/최초 안전 위치를 정리했다.
  - R0에 earliest-safe 조건표와 DEFER/certificate/work counter, production survivor에서 출발하지 않는 독립 small whole-plan universe와 mutation 검증을 추가했다.
  - R1의 illegal-product 삭제와 allocation 제거를 분리하고, ALL_REJECTED/profile 경계는 별도 증명 과제로 지정했다.
  - R3a same-snapshot reuse를 고위험 구조 변경보다 먼저 적용 가능하게 하고, R3b는 기존 SCC/dirty scheduler와 닫힌 component의 semijoin으로 구체화했다.
  - 동작 보존형은 deterministic trace parity; 구조 변경형은 shared legal set·canonical cost·조건부 exact optimum/tie·bound·cap/timer 계약을 검증한다. 후자의 DP-local 방문 순서/시간제한 내 최종 선택 변화는 명시적으로 기록하며, control incumbent 비용 악화는 조사 전 통합하지 않는다. 새 heuristic 점수나 FedFirst/AggLocal 정책 변경은 하지 않는다.
- **의사결정 근거:** 사용자 요구인 **합법 search-space 보존**과 **같은 중간 탐색 경로/선택 유지**는 다른 계약이다. 불법 제거·동등 표현 축약·정책 선택을 구분하고, 더 이른 적용은 authoritative 정보가 준비된 경계에서만 한다.
- **수정 파일:**
  - `docs/FEDPLANNER_PRUNING_SIX_QUESTIONS_REVIEW_2026-09-29_KO.md` (새 감사)
  - `docs/FEDPLANNER_REFACTOR_PERFORMANCE_RECOVERY_PLAN_2026-09-29_KO.md` (v2)
  - `.omx/plans/fedplanner-refactor-performance-recovery-2026-09-29.md` (동일 사본)
  - 이 세션 이슈 문서.
- **검증/증거:** code/test source read-only 조사. v1의 원문 SHA `fe7c139177fc3bed88c705606184e1bf6214b4ed57a7c258244dcedf47db9401`과 내용은 `/grid/3/cofee-lm-sweep-mchoi-20260914/refactor-performance-plan-20260929/plan-v1-before-six-question-audit.md`에 보존했다. v2 독립 검토 및 문서 검사 결과는 아래 후속 기록에 추가한다.
- **잔여 문제:** 실제 새 pruning 구현·독립 exhaustive fixture·60초 병목 세분화는 앞으로 할 일이다. 새로운 source-space 완전성이나 compile/runtime 성공을 주장하지 않는다.
- **수정으로 인한 버그 가능성/감지:** 현재 문서 변경만이므로 실행 동작 변화 없음. 향후 ALL_REJECTED의 premature bottom, under-specified cache, lost OR/writer, factorization으로 바뀐 비용/부정확한 lower bound/품질 악화가 위험이다. 단위별 certificate·독립 whole-space·mutation·bound 및 동일 조건 품질 gate로 감지한다.

### v2 최종 계획 검증

- 독립 critic `/root/performance_recovery_plan_review`는 새 알고리즘/검증 계약을 검토하고 실행 순서 및 v1/v2 artifact 참조 두 불일치를 지적했다. 수정 후 최종 **OKAY / APPROVE**를 받았다.
- 검토된 v2 계획과 `.omx` 사본 SHA: `d97bebf143b49e5c99d1eeb1a871729c6601db23eb8e44e8887d8f1367cdbcbc`. 비교 결과 동일. 새 감사의 source 참조 17개 존재, 문서 공백 및 `git diff --check` 통과, source/harness diff 없음.
- v2 증거는 기존 evidence root의 `six-question-audit-document-verification.json`, `six-question-independent-review.md`. v1 검증 artifact는 덮어쓰지 않았다.
- **검증 범위:** 코드/테스트 소스와 문서의 read-only 검토. 새 테스트 실행·성능 개선·전체 후보 완전성·runtime 완료를 주장하지 않는다.

## 4. 전체 compile-only 30초 목표 구현 시작

- **상태:** 진행 중. 앞선 계획 작성과 구별되는 실제 구현·검증 세션.
- **문제/조건:** 사용자 요청으로 runtime을 제외한 896개 planning 각각의 목표를 30초로 강화했다. 60초 watchdog 및 기존 privacy/anchor/합법 공간/solver budget 계약은 유지한다.
- **원인 관측:** run06 driver 종료 후 마지막 attempt container 잔존. SliceLine W1 실패는 shared-root의 arcConsistency에서 발생하며 지역 repair만의 실패라고 단정할 수 없다.
- **해결 계획/수정 범위:** `FEDPLANNER_PLANNING_30S_EXECUTION_2026-09-29_KO.md`의 테스트 우선 R0/R1/R3a/R2 및 검증 계약. DP authority 준비와 Oracle profile 준비의 독립 파일 작업을 진행한다.
- **재현/증거 경로:** `/grid/3/cofee-lm-sweep-mchoi-20260914/planning-30s-recovery-20260929`.
- **검증:** 진행 중. 완료/성능 개선 주장은 최종 동일 엔진 Docker 결과 및 회귀 통과 이후로 제한한다.
- **잔여 이슈:** DP timeout, SliceLine root infeasibility, 전체 matrix 및 30초 gate 미검증.
- **잠재 회귀/감지:** authority dependency 누락, privacy-illegal pruning의 DIRECT_FOUT 동반 삭제, profile stale reuse. small exhaustive hard truth, receipt identity, bit-exact cost 및 invalidation 회귀로 감지한다.
- **의사결정 근거:** planner가 runtime 지원과 privacy 제약을 정확히 모델링하며 합법 공간을 임의로 닫지 않는 원칙.

## 5. R0/R1 첫 구현 검증: SliceLine 수정, 잔여 dense-root 병목

- **상태:** SliceLine W1 LAN compile 실패 해결; 전체 성능 목표는 미달/진행 중.
- **환경/재현:** frozen candidate-r1 JAR `4bf51e9380c80338040a6aeac9443857116be1441287e3e5825c79c3e0a1d5b5`, evidence root의 `candidate-r1-source/scripts/fedplanner/run_LAN_docker.sh --campaign --root .../candidate-r1-smoke --phase compile --max-cells 1 --planner DP-local --workload <W> --workers <N> --profile lan --compile-timeout 60 --runtime-timeout 60`. runtime 실행 없음.
- **증상/원인:** SliceLine CTABLE의 선택된 native ROW residency가 동일 worker의 durable FULL origin anchor를 이용하는데, materialization FType(ROW) 대신 origin FType(FULL)으로 비교해 불필요한 privacy-illegal relocation으로 판정했다. 동일 immutable analysis의 FedFirst complete witness를 DP hard factors에 주입하여 최초 불일치 factor를 확인했다.
- **해결:** 직접 입력 support receipt가 정확히 일치할 때만 materialization FType과 endpoint를 비교한다. 전역 worker-pool/FType equality는 변경하지 않았다. 별도 endpoint, explicit relocation, foreign analysis/stale receipt는 계속 거부한다.
- **수정 파일:** `NeutralPlacementGraph.java`, `PlacementIdentity.java`, `ExactPhysicalWitnessEncoding*.java`. R1의 `ExactPhysicalModel.java` allocation 제거/privacy-illegal RELOCATION 생성 차단과 독립 변경이다.
- **Docker 결과:** ADULT W1 10.482945초, COVTYPE W1 10.202383초 compile 성공. logreg W1/W3, GLM W1/W3는 모두 60초 timeout. l2svm W1 12.580175초, P1_FULL W1 24.250333초 성공. 이것은 8개 subset이며 전체 896 완료가 아니다.
- **원인 재측정:** candidate-r1 logreg W3 진단은 common 13.2247초, model/cost 5.5515초, shared-root freeze 28.3841초, quotient 5.3589초. root 이후 지역 exact repair에 진입했으나 watchdog 종료. physicalWorkerPoolLayout/주소 정규화/authority truth 반복이 freeze의 주요 샘플이다. baseline W1의 cost fingerprint 병목과 구분한다.
- **검증:** 초기 통합81 tests 통과. broad190 중189 통과, certificate1 오류(`cells=71606548, limit=60000000`)는 frozen baseline JAR에서도 동일 재현되어 기존 실패로 분리했다. cap을 올리지 않았다. Python matrix 계약83 tests 및 30초 evaluator6 tests 통과. 후기 Native/Oracle review 수리는 독립 직접 테스트를 추가했으며 최종 통합 재검증은 진행 중이다.
- **회귀 처리:** NativeLocal 비용 golden은 R1의 불법 product 제거로 구조 SHA가 바뀌었다. 기존 SHA를 바꾸지 않고 legacy full-product reference에 고정하고, 삭제 후 모든 대응 비용표의 raw bits/순서를 별도 비교한다. 제거된 transfer factor는 모든 survivor cell에서 정확히 +0임을 확인한다. 강화된8 tests 통과.
- **잔여 이슈:** control 3회 교차 반복, incumbent objective 품질 gate, SliceLine 다른 network, 모든 896 조합/30초 미검증. l2svm의 첫 baseline/new pair는 11.349589→12.580175초이므로 단일 측정만으로 성능 보존을 주장하지 않는다.
- **잠재 회귀/감지:** Native residency 변환은 정보손실이 있어 역방향 memoization 금지. 이 버그를 검토 중 발견/제거했고 interval/exactness/모든 2단계 변환의 old-value parity를 추가했다. Oracle shape 준비는 첫 query로 지연해 eager 오류 순서 변경을 방지했다. 상세 evidence는 `shared/`, `cost-source-projection/`, `dp-authority/`에 보존한다.
- **의사결정 근거:** 직접 입력의 정확한 runtime 지원 증명만 복구하며 fallback/공통 합법 공간 축소/timeout 변경 없이 개선한다.

## 6. R2 exact-kernel 관측 표현 개선 — 구현/검증 중

- **문제/원인:** 실제 logreg 모형의 hard factors는 약1.99억 dense cells. 많은 receipt/authority 조합이 한 factor 입장에서는 같은 관측 결과인데 전체 product를 펼친 뒤에야 quotient했다.
- **해결 방향:** canonical 변수/후보/정책용 factor는 유지하고 exact kernel에만 deterministic 관측 auxiliary와 작은 truth/cost table을 제공한다. 모든 원래 assignment의 유일한 auxiliary 복원과 같은 hard truth/raw canonical cost가 필수다.
- **수정 범위:** `ExactHardFactorObservationDecomposition.java`, `ExactPhysicalModel.java`, `RegionalSearchProblem.java`, `ExactPhysicalOptimizer.java`; native-local 비용 source의 LOUT/FOUT FType 관측 분해는 `ExactPhysicalCostModel.java`.
- **cap/clock 계약:** 실제 encoded scope의 기존 cap 산식을 유지하며 original logical cells와 encoded cells를 따로 기록한다. numeric cost callback은 aggregate preflight 뒤에만 평가한다. canonical 비용은 frozen compressed price table을 읽으므로 이후 전역 network parameter 변화로 움직이지 않는다. 기존10초 budget 시작 경계는 바꾸지 않는다.
- **검증:** cost projection의 exhaustive unique completion/raw bits/조건부 optimum·tie/first-invalid 순서/preflight/cost snapshot6 tests 통과. hard factor 관계 projection·bound·품질 회귀와 실제 Docker 재측정은 진행 중.
- **잔여/위험:** encoded graph가 달라져 fixed-budget visitation/result가 달라질 수 있다. scope 감소를 동일 trace라고 주장하지 않으며 control incumbent 악화 시 원인 조사한다. observation key 누락으로 authority가 합쳐지면 즉시 중단한다.

### R2 동결 검증과 R3 추가 경로 (30초 미달)
- **R2 엔진:** SHA256 `aebb10483063ac495c78356eb686c2a7d63313532d5f75c2aa7d7f03a2801326`. `candidate-r2-source`의 1,628 production/pom hashes와 측정 전후 JAR가 독립 검증에서 동일했다.
- **Docker 관측:** DP-local/logreg/W3/LAN은 52.039075초, 유효 compile 성공/cleanup 완료/runtime 0. GLM/W3/LAN은 analysis 약23.21초 뒤 planner 중 60초 timeout. 둘 다 30초 목표 미달이다.
- **새 JFR 근거:** logreg R2의 shared-root freeze 10.397초, quotient 2.231초, rebuild 0.383초로 줄었지만 지역 exact solve의 `preciseSum`이 main 3,741 samples 중1,357 top-frame이다. 공통 analysis의 generation-before-no-op은 약2.35%뿐이므로 광범위 read-set cache는 도입하지 않았다(`shared/r3b-generation-readset-diagnosis.md`).
- **R3 구현 방향:** graph가 실제 읽는 state/identity/receipt/binding/pool/derived-action 필드를 관측 class로 분해했다. LOGREG hard cells 198,580,938→31,349,231, 그중 input-authority 93,289,700→22,951,724. canonical 원래 후보/hard factor/ordinal은 유지하며 독립 branch-by-branch review에서 승인했다. 큰 factor는 모든 관측 category product와 각 원래 값의 치환, 작은 factor는 full Cartesian으로 검증한다.
- **비용 합산:** 정확한 누적 low residue와 다음 high/low가 모두0일 때에만 불필요한 double-double 연산을 생략한다. signed zero/overflow/tie/error 순서를 유지하고 공통 tie overflow 처리를 재사용한다. baseline/current arithmetic golden9 tests PASS. hot method bytecode는 최초 추가안366에서320으로 정리했으며 JVM flags 변경은 없다. 속도는 Docker 재측정 전 확정하지 않는다.
- **R2 회귀:** 독립 focused215 tests는 실패/오류0, 기존 PUBLIC-only skip1. broad190 중189 pass, certificate의 baseline-known cap 오류1. **R2의 오류 cell 수72,089,018은 baseline/R1의71,606,548과 다르다**(+482,470). 같은 실패 testcase/cap이지만 byte-identical failure라고 부르지 않으며 이후 assertions 미실행 gap을 유지한다. cap/기대값을 완화하지 않았다.
- **R2 control 품질:** 진단용 P1_FULL의 seed47,350,691.09795345/final42,940,073.84566997, l2svm seed/final861.5023353826347은 baseline과 동일하다. bound/gap도 동일하나 encoded retained/assignment counts는 달라졌다. `candidate-r2-quality-summary.json`에 분리 보존했다. JFR control은 timing acceptance로 사용하지 않는다.
- **남은 작업:** R3 실제 속도/GLM phase 진단, 새로운 동일 엔진의3회 교차 control과896 compile-only≤30초. 이 문서는 구현 진행 기록이지 성공 선언이 아니다.
- **위험/감지:** 새로운 relocation predicate가 observation key에 누락될 위험은 branch별 negative regression/독립 review로 감지한다. exact arithmetic 변경은 raw high/low/objective/secondary golden 및 overflow callback 순서 회귀로 감지한다.

## 7. R3 이후 남은 dense elimination과 거대 signature — 진행 중
- **환경/재현:** `candidate-r3-source`, `candidate-r4-source`의 고정 JAR로 `run_LAN_docker.sh --campaign --phase compile --workers 3 --profile lan --compile-timeout 60 --runtime-timeout 60`; evidence의 각 `candidate-r*-smoke` 및 `candidate-r3-diag-logreg-w3-dp-global`.
- **증상:** R3 DP-local logreg38.106475초/GLM46.650858초, DP-global logreg timeout60. R4 local logreg36.808468초/GLM50.237844초. 모두30초 미달이며 전체896/3회 control은 아직 미실행이다.
- **원인:** DP-global selected order는8,603억여 candidate assignments. root/order 준비가 아니라 exact enumeration 규모가 주요 blocker다. GLM final89,734 alternatives의 signature 총1,574,124,633 UTF-16 chars는 flatten/hash/retention 비용을 만든다.
- **해결 요약:** R4 SCC-only exact read-set reuse와 후보 fact fingerprint fieldwise streaming은 검토 후 동결. R5에서 기존 CanonicalText의 minimal immutable facade를 이용한 lazy Alternative signature, chunked fingerprint, homogeneous lazy regional state key를 구현한다. String API/정렬/동등성/검증 오류 순서를 보존한다. split surrogate도 기존 flattened UTF-8과 같은 digest가 되어야 한다.
- **exact 반복:** 오직 built-in zero tie에서 첫 factor의 ∞ 값만 bitmap으로 생략한다. public callback은 infeasible 값에도 종전대로 호출한다. finite 후보는 원래 ascending order/preciseSum/comparison을 사용하며 cap/timer/domain을 변경하지 않는다. 모든 separator output을 dense하게 순회하는 한계는 남아 있다.
- **수정 파일:** `ExactCategoricalSolver.java`, `ExactPhysicalModel.java`, `ExactPhysicalCostModel.java`, `LocalPhysicalOptimizer.java`, `PlacementAnalysis.java` 및 targeted tests. R4 reuse는 `NativePlacementContinuity.java`.
- **검증:** frozen R4 finite-row 동작6, current finite-row7+arithmetic9 통과. 독립 architect CLEAR. lazy/cost focused 직접 테스트 통과 후 통합 Maven/독립 review 진행 중. 테스트 오류를 cap/정답 완화로 숨기지 않는다.
- **검토 중 수정:** lazy Alternative의 null signature가 earlier invalid decision보다 먼저 실패하는 validation-order 회귀를 reviewer가 발견해 복구했다. normalized signature overload가 package-level uncast null 호출에 ambiguity를 만들 수 있는 source compatibility 점은 별도 확인한다.
- **잔여 이슈:** 새 frozen R5 Docker 측정, 전체896 ≤30초, incumbent/control 반복 및 broad certificate downstream gap. 높은 차수의 sparse message는 ordered-overflow 보존/range 증명이 없는 한 도입하지 않는다.
- **잠재 회귀/감지:** lazy hash/collision/UTF16 surrogate/부분 cache publication은 flattened reference property test로, bitmap axis/word 경계와 first-factor-only 불변식은 dense explicit-zero-callback 차등회귀로 검증한다.
- **의사결정 근거:** 합법 공간을 삭제하지 않는 표현/중복 계산 개선이며 planner fallback·privacy 완화·정확성 교환을 하지 않는다.

### Python 외부 attestation 검증 공백 분리
- 전체 Python476 tests 중1 error/1 skip. `test_audit_current_scope_library_resolution`은 `/home/mchoi/systemds-g009-integration`의 절대 producer root로 봉인된 attestation을 다른 checkout에서 검사해서 실패한다.
- baseline/current/origin 코드·inventory가 byte-identical이고 frozen baseline에서도 현재 input root로 같은 오류가 재현된다. attested origin에서는5 tests 통과했다. source/attestation을 임의 재작성하지 않았다.
- evidence: `r2-regression-verification/python-attestation-classification/VERIFICATION.md`. 현재 변경으로 생긴 회귀로 분류하지 않지만 본 checkout의 full-green을 주장하지 않는다.

## 8. R6 희소 exact message와 메모리 회귀 — 검토 수정 중
- **문제:** R5 logreg/GLM W3 local은38.903318초/41.796855초, global logreg는60초 timeout. 첫-factor bitmap만으로는 dense separator 전체 방문을 없애지 못했다.
- **범위/근거:** 원래 elimination 순서/모든 dense 논리 cap/Statistics를 유지한 채, 지원되는 tuple의 exact natural join과 infinity-default 중간표를 도입한다. public tie callback은 기존 dense 경로이며 원래 모든 lazy 입력 callback을 먼저 실행한다. runtime/후보/privacy 정책은 바꾸지 않는다.
- **산술 안전 조건:** built-in zero tie, F+V≤2^20, 모든 finite frozen 입력 절댓값≤2^400에서만 후속 ∞로 제외되는 조합을 건너뛴다. DD 누적 L1<2^421/내부 계산<2^425 증명이 있으므로 이전 overflow 오류를 숨기지 않는다. 그 밖에는 기존 ordered dense 산술을 유지한다. 상세 독립 증명: `exact-sparse/ARCHITECTURE.md`.
- **검증:** frozen R5 기준 새5 tests PASS; 구현후 매 중간표의 raw high/low/choice까지 explicit-zero dense reference와 비교한다. equality-star가 실제 sparse storage를 사용하고 D tuple만 방문하는지 확인한다. 직접31 tests PASS, 첫 통합 Maven도 통과했으나 메모리 수정 전 결과이므로 최신 재검증이 필요하다.
- **검토 발견1:** 한 개만∞인5백만 unary factor에서 이전R5는-Xmx192m 성공, 최초R6는 row 객체/박싱 index 때문에 OOM. 다수 finite dense support를 별도 join 관계로 만들지 않고 원래 preciseSum에서 계속 검사하도록 수정했다. root minima는 storage 이득이 없어지면 모든 값을 보존한 채 dense array로 전환한다. 재현은 최신에서 성공했으며 시간 수치는 성능 acceptance가 아니라 메모리 회귀 진단이다.
- **검토 발견2:** partial-bound index의 boxed HashMap/Map.copyOf는2백만 support에서 별도 OOM을 만들었다. flat cell ID와 primitive index/linked chain으로 고치는 중이다. **독립 재현 통과 전 R6 동결/성공 선언 금지.**
- **수정 파일:** `ExactCategoricalSolver.java`, 새`ExactFiniteSupportJoin.java`, `ExactSparseMessageParityTest.java`, `ExactFiniteSupportJoinTest.java`.
- **잔여/위험:** sparse join도 최악에는 지수적이며 전체896≤30초를 보장하지 않는다. 새 JAR Docker에서 실제 join visits/output density/heap/GC를 확인한다. 원래 dense cap을 sparse 객체 수로 대체하지 않는다.
- **검증 재현:** `exact-sparse/review-probes/`와`review-memory-probes.txt`에 reviewer의 독립 bounded-heap probe와 R5/current 결과를 보존했다. sparse unit row-map correctness와 대형 near-dense 메모리 gate를 모두 유지한다.

### JFR 깊이 해석 교정
- 이전 depth5 JSON에서 StringLatin1 hash를 ASM linkage로 추정한 것은 잘못이었다. harness는 원래stackdepth128을 기록했으며 `jfr print` 기본 출력 깊이가5였던 문제다.
- depth128 재추출에서 R5 GLM common1,252 samples 중3+ realization group merge가138(11.0%), relocation product caller가94였다. 재귀 support-clause/proof hash와 canonical comparison이 실제 hot path다.
- `shared/r3b-glm-common-phase-diagnosis.md`의 후속 교정과 `shared/candidate-r5-glm-w3-samples-depth128.json`을 우선한다. public signature/authority order를 유지하는 k-way canonical clause merge를 R6 동결 뒤 별도 bounded slice로 검토한다.

### R6 메모리 검토 수정 확인
- near-dense5M/-Xmx192m 및 partial-bound2×1M×2/-Xmx256m의 독립 probe가 모두 성공했다. 두번째 partial index는 dense primitive heads 또는 primitive open-address heads + 하나의 row-chain 배열을 사용하며 HashMap/Map.copyOf를 제거했다.
- 모든 관계의 validation 뒤 빈 support가 있으면 다른 index 준비 전에 종료한다. iterative DFS/odometer로10,000 관계/axis에서 stack overflow도 방지했다. helper11 tests는-Xmx256m에서 통과했다.
- 독립 reviewer 최신 focused17/17, diff check 통과, 두HIGH 해소 후APPROVE. 최신17-class 통합 재검증과 frozenR6 Docker가 다음 gate다. 더 큰 현실 heap/GC나 전체896 성능을 이 probe로 보장하지 않는다.

### R6 최신 통합과 Docker subset
- 메모리 수정 후 최신 통합167 tests 실패/오류/skip0. JAR `6dab4194a6088e36ddb05269b6925c92bbb65072018cedc10066330029ab855b` 동결.
- DP-local logreg W3/LAN full compile24.226584초, 유효 gate/cleanup 통과, runtime0. evidence `candidate-r6-smoke/attempts/compile/01790669182157050929-7da68f38/`.
- 새로운 전체896 성공/각30초/paired3회는 아직 미확보이며 다른 조건은 계속 측정한다.

### R6 잔여 실패 / R7 반복 해시 제거 (진행 중)
- **관측:** 같은 R6 엔진의 DP-local GLM W3/LAN은 full compile46.741396초로 30초 미달이다. DP-global logreg W3/LAN 진단은60초 timeout이며 성공 시간으로 대체하지 않는다. runtime은 실행하지 않았다.
- **원인:** global의 완료된 sparse 단계만33.3819초/434,940,232 실제 assignment 평가다. variable17이13.7673초, variable44가5.3285초이며, 여전히 남은 자유 축과 큰 finite message가 병목이다. sparse join만으로 전역 문제가 해결되었다고 주장하지 않는다.
- **R7 변경:** `PlacementAnalysis`는 이미 정렬된 realization clause runs를 안정 정렬/정확한 중복 비교로 합쳐 반복 deep hash를 피한다. immutable canonical text의 String hash는 `h(a+b)=h(a)*31^len(b)+h(b)`를 Java int wraparound 그대로 합성하고 shared child에서 지연 메모한다. 재귀 대신 명시적 스택을 쓰며 동시 publication/UTF16/zero hash를 검증한다.
- **R7 model/cost:** `ExactPhysicalModel`은 scope membership에 동일 object fast-path를 사용하되 equal-variable의 legacy domain equality fallback을 유지한다. `ExactPhysicalCostModel`의 worker-count memo는 한 cost-surface 호출 안에서만 유효하며 invalid canonical address의 null 원소도 기존처럼 한 번 센다. 비용/network snapshot과 fingerprint 계약은 바꾸지 않는다.
- **검증:** text/canonical merge 직접53 tests PASS, model scope8 tests PASS, cost25 tests PASS. 별도 review 및 최신 통합 Maven 진행 중이다. 독립 bounded whole-plan gate3 tests와 관련17 tests PASS; 실제 production receipt/all-writer/action decode를 test-owned universe와 비교하고 witness 삭제 mutation을 검출한다. 보편적 모든 DML 공간의 증명은 아니다.
- **추가 공백:** R6 broad certificate는 cap60,000,000에72,182,723 cells로 실패한다. baseline71,606,548/R2 72,089,018과 같은 testcase/cap이나 byte-identical failure는 아니다. 이후 assertions는 미실행이다. cap/기대값은 완화하지 않는다.
- **잔여/회귀 위험:** 새로운 frozen R7 Docker 측정, global의 큰 separator, 전체896 및3회 교차 controls가 남는다. hash bit/identity/equal-distinct/null/error-order 차이는 property/negative 회귀로, sparse 표현의 산술·tie·heap 회귀는 독립 dense oracle와 bounded heap probe로 감지한다.
- **의사결정:** shared legality나 privacy를 완화하지 않고 중복 계산/저장 표현을 수정한다. 60초 watchdog, 원래 resource cap과 DP-local10초 budget 시작 경계는 그대로다.
- **독립 review 교정:** production R7 세 변경은APPROVE. 새 `IndependentCompletePlacementSpaceTest`는 expected-side legality filtering/plan key 축약 때문에 over-generation을 숨기는MEDIUM 검증 결함이 발견되었다. 위3/17 PASS를 whole-space gate 완료로 인용하지 않는다. lossless tuple decode와 extra-witness mutation으로 test를 수정/재검토한다.

### R7 확정 / 독립 bounded gate 교정 완료
- **R7 엔진:** `207fb589fb3759c2b4ec2382d7a8bd25b4cf3f3340347ba8258bec73d0cfef0a`, 통합222 tests PASS, evaluator8 PASS. 잘못 적힌 `ExactPhysicalCostGoldenTest` selector는 실제 클래스가 없어 미실행이며222개 합계에는 포함하지 않았다.
- **Docker:** DP-local W3/LAN logreg23.234482초, GLM42.662154초. GLM common24.627263초/adapter15.046378초로 목표 미달. 최신 진단 JFR도 common24.680079초이며 terminal fixed-point 호출은 이미 작아 단순 마지막 결과 memo의 큰 이득을 주장하지 않는다.
- **독립 gate 수정:** 기존 placement-package 가짜 authority 조합 decoder를 폐기하고 `fedExact/IndependentCompletePlacementSpaceTest.java`로 대체했다. 실제47 domains/108 hard factors의64 raw assignments를 모두 평가해8 EMITTED/56 REJECTED/0 UNKNOWN을 강제하며 UNKNOWN은 사유와 ordinal로 즉시 실패한다. 모든 admitted row는 `ExactPhysicalSelection.create`의 실제 receipt/action/all-writer 검증을 통과해야 한다. expected universe는 별도 literal placement/receipt/action 축에서 출발한다.
- **mutation 의미:** admitted row 삭제는 missing witness를 검출한다. 실제 rejected row 주입은 production selection의 transient-support 오류로 fail-closed한다. 후자를 extra-set 검출이라고 과장하지 않는다. 실제 선택의 relocationChoices/emittedActions는 모두 비어 있으며 graph에 action이 존재한다는 이유로 선택했다고 간주하지 않는다. focused9 tests PASS, 독립 최종APPROVE. 이 작은 fixture 이외의 보편적 완전성 증명은 아니다.

### R8 exact value-profile 표현 (검증 중)
- **원인/관측:** diagnostic-only R7 overlay `6145bcb2e320fa08adb0c242db2385ced85c0a8c1c75822605b04ae25d999fbc`로 큰 bucket을 검사했다. 모든 축이 실제 의존하므로 단순 축 삭제/constant-message 최적화는 채택하지 않았다. binary authority link `[1042,2]`와 `[3692,7,2]` 메시지를 없애는 과정에서26,929,448 cells로 반복 확장되는 경로를 확인했다.
- **해결:** `ExactFactorValueClasses`가 각 축의 전체 raw high/low response profile을 hash+정확 비교로 분류한다. `ExactCategoricalSolver`는 모든 bucket factor의 partition을 교차 세분화하고 최소 원래 값 대표로 기존 순서의 `preciseSum`을 수행한다. logical scope/순서/cap/statistics는 유지하며 비용표와 backpointer의 projection은 별도다. sparse implicit infinity는 보수적인 stored-coordinate partition으로 다뤄 dense 확장을 피한다.
- **안전 경계:** 기존 default-zero-tie/range certificate 안에서만 사용한다. explicit callback/큰 값 overflow 경로는 종전대로이며 입력 callback의 전체 materialization/validation이 먼저다. optional primitive memo가 heap headroom에 맞지 않으면 모든 값을 보존하는 identity partition을 사용한다. 평가 실패를 잡거나 cap/search budget을 바꾸는 fallback이 아니다.
- **검토 중 발견/수정:** boxed profile map과 사용하지 않는 stored coordinate마다 inverse 배열을 만드는HIGH heap 회귀를 발견했다. primitive memo 및 representative 기반 head/count/next로 교체했다. identity inverse는 추가 저장이 없다. 모든 합법 값을 유지하는 one-to-many support lifting을 검증한다.
- **검증:** 직접36 tests PASS 후 permanent selective-memory 회귀를 추가했다. selective5M/near-dense5M은-Xmx192m, partial2×1M×2는-Xmx256m에서PASS. 26.9M logical-cell fixture는-Xmx128m에서PASS(RSS46,848KB), 원래 logical 통계도 그대로다. 작은4200-cell fixture는12 stored cells이며 모든 logical raw high/low/choice가 dense reference와 같다. 독립 review는 실행 중인 통합 Maven 성공 조건으로APPROVE.
- **잔여/회귀 위험:** R8 동결 Docker는 아직 미실행이다. class 준비 비용이 새 병목이 되거나 큰 separator에서 class 교차가 다시 늘 수 있다. 전체896/각30초/3회 controls는 여전히 미완료다. 중간 raw bits/choice, callback·overflow·cap 회귀 및 heap probes로 감지한다.

### R8 동결 / GLM 전역 cap 경계 확인 — 목표 미완료
- **최신 엔진:** SHA256 `67a51182dfc8bf7558d9e69937f9701667c59468ebd14c9f13ee66cd06769d1b`, production/POM2,171 hashes 동결. 통합23 classes/248 tests 실패·오류·skip0, 독립 reviewerAPPROVE.
- **signal-free Docker:** DP-global logreg W3/LAN full compile29.791289초, network/cleanup/compile-only audit 통과, runtime0. R7/R6의60초 timeout과 구분하며 이번 한 condition만30초 통과다. DP-local GLM W3/LAN43.809625초(common24.823740/adapter16.179781)는 여전히 미달이다.
- **새 실패:** DP-global GLM W3/LAN은 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 정상 실패했다. 동일R8 진단에서 입력38,403,121 cells/최대입력5,829,850이나 모든4 order portfolio의 최대 중간표가67,764,848,126,976 cells다. 메모리 제한 또는 cap을 높이지 않으며 timeout/실패를 성공으로 집계하지 않는다. 아직 baseline 같은condition을 실행하지 않아 기존/신규 회귀를 단정하지 않는다.
- **원인 조사:** global GLM의 문제는 입력표보다 elimination separator 결합 크기다. 구조·순서의 lossless 개선 가능성과 최초 큰separator를 조사한다. R8 value-profile 저장 압축으로 logical cap을 우회하지 않는다.
- **R9 공통 분석 검토:** cross-invocation direct dirty continuation 초안은 live Hop opcode/direction/dims/input/parameter/function-parent 읽기를 누락해독립architect가BLOCK했다. 정확한 immutable read-set, semantic mismatch 전에 continuity resolver 초기화, provisional 미발행, completedCFG 정상반환 때만snapshot 발행 조건으로 제한 구현한다. 이전 output/proof를 반환하는 cache가 아니다. fresh-full mutation parity와 GLM 실제guardhit/dirtycone 감소가 필수다.
- **R9 비용표 계획:** JFR의 candidate realization signature flattening을 기존 segmented canonical text로 스트리밍한다. fingerprint byte format/list순서/UTF8/raw cost는 유지한다. baseline15 tests PASS 후Unicode/공유clause byte회귀를 추가한다. 성능수치는동결Docker 전 미확정이다.
- **잔여:** 전체896/각30초, 최신engine3회pairedcontrol/품질gate, GLM global cap, GLM common병목. 작은fixture와subset결과로 전체완료를 주장하지 않는다.

### R9 동결 전 안전성 수정 / R8 확장 결과 — 목표 미완료
- **상태:** R9 회귀 검증 완료, 실제 Docker 진단 진행 중. R9 JAR SHA256 `bc9a92ee4e012662b1a60ced2822881b045cfdf161bfa245400337fc96fe7f31`; `candidate-r9-source`에 production/POM 2,171개 파일을 동결했다.
- **R8 추가 관측:** signal-free DP-global logreg W7/LAN은 35.325474초, GLM W1은 DP-local/global 모두 60초 timeout, logreg DP-global W1은 `EXACT_VE_FACTOR_CELL_OVERFLOW`다. R8 subset evaluator는 896개 중 7개 관측/30초 통과 1개/미측정 889개로 전체 실패다. 실패 receipt의 runtime 필드 누락을 runtime 실행 증거로 해석하지 않는다. harness는 compile만 실행했다.
- **R9 변경:** candidate realization fingerprint를 기존 canonical text로 스트리밍하고, 동일 domain의 동일 입력/실행 FType에 대한 unary projection을 호출 범위에서 재사용한다. direct closure는 완료된 frontier의 exact authority와 live-Hop read-set이 같은 경우에만 기존 dirty 전파를 이어간다. 결과/proof cache나 후보 삭제가 아니다.
- **검토 발견 및 수정:** (1) nullable FunctionOp output/parameter metadata를 `List.copyOf`/`Map.copyOf`로 미리 복사하면 기존 경로보다 이른 NPE가 발생했다. null-preserving owned snapshot을 만들고 불완전 metadata에서는 guard를 거절한다. (2) 완료 frontier의 guard를 나중에 생성하면 중간 Hop mutation을 과거 frontier에 잘못 연결할 수 있었다. direct 완료 직후 guard를 stage하고 정상 CFG 반환 때 다시 비교한 뒤에만 publish한다. mismatch는 continuity cache도 폐기한다.
- **검증:** 최종 Maven 29 classes, 371 tests 중 370 PASS/기존 ignore 1, 실패·오류 0. null metadata, dims/input/parent mutation, stage→publish 사이 mutation을 회귀로 추가했다. streaming/unary/continuation 독립 code review 승인. 증거: `r9-integrated-tests-final.log`, `r9-integrated-test-summary.json`, `shared/r9-direct-continuation-PLAN.md`, `cost-preparation/r9-*`.
- **잠재 회귀/감지:** continuation은 guard 비용보다 dirty 재계산 감소가 커야 한다. 진단 전용 `Direct-Continuation` event로 실제 GLM의 hit/miss/dirty owner/준비 시간을 확인하며, 이득 없는 복잡성은 채택하지 않는다. unary 대형 fixture의 -Xmx512m OOM은 frozen R8에서도 cost build 전에 재현했고 -Xmx2048m에서는 관련 28 tests가 통과했다. 새 메모리 회귀를 숨기는 근거로 쓰지 않는다.
- **의사결정 근거:** 비용 산술·fingerprint byte 형식·후보 순서·privacy·cap·DP-local timer는 그대로 유지한다. 전체 30초 성공/3회 paired control은 아직 미확보다.

### 전역 GLM의 추가 표현 가설 반증 — 새 설계 경계
- **환경/재현:** `dp-global-glm/`의 hermetic production-shaped GLM 모델 진단. 이 JVM 실행은 작업량/구조 진단이며 성능 acceptance가 아니다. 실제 성능은 frozen Docker만 인정한다.
- **원인/음성 증거:** 큰 separator의 pairwise 연결은 fill-induced다. 기존 4개 portfolio, creator cutset(2~1,280 branches), duplicate observation coalescing, finest-observation refinement, weighted min-fill 모두 unchanged logical cap을 만족하지 못했다. 실패한 probe를 production에 통합하지 않았다.
- **authority 분해 반증:** `product-output.log`에서 가장 큰 23,956개 alternative는 23,956개 execution/support base를 갖는다. 17,182개 중 17,149개, 12,248개 중 12,226개도 서로 다른 base다. input-authority 축만 나누어서는 지배적인 차원을 줄이지 못한다. 또한 1,843-row domain의 3개 base는 product가 아니며 Cartesian completion하면 24개 없는 alternative를 추가하므로 금지한다.
- **다음 경계:** rule/emission/realization/support clause 중 어떤 구성요소가 base 수를 만드는지 제한된 count probe만 수행한다. 공유가 확인돼도 witness를 보존하는 support-relation encoding과 전체 logical-cap certificate, 산술/rounded-tie 증명이 필요하다. 임의 clause 합치기, cap 상향, sparse 저장으로 logical cap 우회, heuristic fallback은 하지 않는다.
- **잔여/위험:** 현재 조사한 가설 중 전역 GLM을 cap 안에서 해결하는 증명된 방법은 없다. 이것은 현재 설계의 확인된 한계이지 모든 exact 표현의 불가능성 증명은 아니다. 전체 896/각 30초는 미완료다.

### R9 continuation 제외 / R10 immutable 준비 재사용 — 검증 중
- **R9 실측:** 실제 GLM W3 진단에서 continuation은 8회 중 2회 hit, dirty owner는 238/1,187 및 212/1,187이었다. 기능은 작동하지만 순수 이득은 입증되지 않았다. signal-free full R9는 42.586168초(common 25.625555초), 비용 변경만 남기고 closure를 R8로 복원한 동결 ablation은 36.833656초(common 22.272842초)였다. selected-plan audit hash는 동일하다.
- **제외 결정:** 단일 비교를 인과관계/안정적 배율 증명으로 쓰지 않는다. 다만 이득이 불명확한 대규모 continuation 복잡성을 유지할 근거도 없으므로 **R9 continuation만 main에서 제외**했다. `PlacementRelationClosure.java`는 동결 R8와 byte-identical이며 이전 dirty-cone 최적화는 보존한다. 새 전용 guard test는 evidence에 보관하고 사라진 기능의 private seam과 함께 main에서 제거했다. 다른 테스트/assertion은 완화하지 않았다. 계획/백업: `shared/r9-continuation-retention/`.
- **R10 변경1:** 기존 weak identity cache 패턴으로 immutable clause의 sorted/distinct required source 목록만 재사용한다. compiler-thread/analysis-reset 범위이며 clause를 다시 참조하지 않는 source-only value라 weak key를 붙잡지 않는다. 현재 actions/논리 입력/required writer/모든 writer/privacy 검증은 매번 계속한다. `PlacementIdentity.java`, `PlacementAnalysis.java`, `RequiredInputSupportMemoTest.java`.
- **R10 변경2:** `ExactPhysicalModel.java`의 relocation obligation 전수 스캔을 build-local exact index로 대체한다. source value-version/state는 구조 동등성, consumer는 `==`/identity hash, FType/position은 원래 일치를 유지한다. action별 중복 obligation은 한 번만, 서로 다른 action은 모두 원래 순서/identity로 유지한다. canonical action 목록과 obligations만 색인하고 privacy/support/product는 캐시하지 않는다.
- **검토 발견/수정:** nullable candidate input FType에 새 lookup key가 조기 NPE를 만들 수 있었다. 기존 enum 비교처럼 null FType은 정확한 empty match로 처리하고 회귀를 추가했다. source-list 테스트의 live action/logical/writer 변화 검증 누락도 독립 reviewer 요구대로 보완했다.
- **검증:** support cache RED 4→GREEN 6, 관련 142 tests PASS, 독립 APPROVE. index 새 3 tests PASS 및 관련 20 tests PASS; pruned/unpruned full-build의 domain 순서, authority/action identity, 모든 canonical hard-factor raw bits를 legacy scan과 비교한다. index 독립 review와 두 변경/continuation 제외 후 통합 Maven 진행 중이다.
- **잔여/위험:** R10 Docker 속도/heap/guard 검증 전 성능 성공을 주장하지 않는다. 전역 GLM support-clause 관계는 별도 구조적 blocker다. 최신 support probe의 23,956-row domain은 46 realization이지만 23,930 support clause/23,663 binding 목록을 갖는다. 실제 witness를 삭제하거나 단순 product로 채우지 않는다.

### R10 최종 소스/회귀 확인 및 Docker subset — 30초 목표 미달
- **상태:** 부분 개선은 구현·검증했으나 전체 목표는 실패/미완료다. 최신 JAR SHA256 `8b3d13375085af1e07c99e145f031dca9400fe547dd922a0eeb4f1605c52f5e8`. 독립 verifier가 main/frozen의 production/POM 2,171개 파일과 JAR/manifest를 검사해 누락·불일치 0을 확인했다.
- **동일 R10 signal-free Docker 결과 (모두 LAN):** DP-local logreg W3 23.715556초, DP-global logreg W3 28.294900초, DP-local l2svm W3 8.434776초, DP-local P1_FULL W3 16.806118초. 반면 DP-local GLM W3 40.404205초/W1 56.110871초이며, DP-global GLM W3은 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 실패했다. W1 GLM의 기존 60초 timeout은 해소했지만 30초 성공은 아니다.
- **전체 판정:** `candidate-r10-subset-evaluation.json`을 7개 완료 후 재생성했다. expected 896 / observed 7 / within-target 4 / missing 889 / passed false, manifest 오류 0. 서로 다른 R6~R10 결과를 합쳐 성공 행렬로 만들지 않았다. 실패 receipt에서 runtime 증거가 없다는 evaluator 문구를 runtime 실행 증거로 해석하지 않는다.
- **runtime/정리:** 모든 7개 완료 attempt의 cleanup은 resolved=true다(`r10-completed-smoke-cleanup-check.json`). 성공 receipt는 실제 compile-only, workloadExecutionStarted=false, execution/observedRun=0, runtime-program 구성과 lowering audit를 확인한다. compile/runtime watchdog은 모두 60초이며 workload runtime phase는 실행하지 않았다.
- **단계 분해:** GLM W1은 common 29.888139초, model 6.485787초, cost surface 4.915715초, optimizer 11.174872초다. 공통 준비만 이미 30초에 근접하므로 전역 solver cap만 고쳐도 전체 30초를 입증할 수 없다. incremental 10초 budget 시작/정지 경계는 바꾸지 않았다.
- **회귀/빌드:** 30 classes / 370 tests 중 369 PASS, 기존 ignore 1, 실패·오류 0. evaluator Python 8 PASS, py_compile/diff-check PASS, frozen package 성공. quiet Maven 로그에 종료 코드를 쓰지 않은 기록 공백은 root가 실제 tool exit 0을 관측한 세션 ID와 함께 `r10-root-observed-execution-provenance.json`에 사후 기록했으며, 원래 로그 안에 있던 증거라고 주장하지 않는다. 이후 재빌드로 측정 JAR를 바꾸지 않았다.
- **검증 공백:** 3회 교차 paired timing, 전체 896, 다른 network/worker/planner, plan-named 일부 전용 gate는 미완료다. broad certificate의 기존 cap 실패는 downstream assertions가 실행되지 않는 공백으로 남으며 R10 full-green을 주장하지 않는다. full Python의 외부 attestation 절대 경로 문제도 별도다. W1 P1/l2svm 최신 incumbent 진단 결과는 아래에 별도 기록하며 signal-free 30초 행렬에는 합치지 않는다.
- **잠재 회귀/감지:** immutable cache/index의 의미·순서·identity는 targeted legacy/cold-warm/raw-bit 회귀로 확인했지만, 작은 fixture를 모든 프로그램의 completeness 증명으로 확대하지 않는다. 선택 품질과 실제 peak memory/시간은 동일 조건의 후속 gate가 필요하다.

### 지원 조합의 lossless join은 성립하지만 전체 graph cap은 실패 — 설계 경계에서 중단
- **증상/원인:** exact GLM support witness를 단순 저장 중복으로 취급할 수 없다. `(structural header, ordered bindings)`는 fixture의 원래 alternative와 일대일로 대응했고, binary 59,038개 row 및 ternary 21,368개 row의 제안된 projection join은 없는 조합을 추가하지 않았다. 그러나 이것만으로 전체 factor의 의존성과 separator를 줄인다는 보장은 없다.
- **검증 방법:** pre-reduction frozen root를 사용해 원래 callback을 다시 실행하지 않고 10,019개 physical factor axis의 raw-bit response partition과 component functional dependency를 검사했다. 1,096개 domain의 tuple 복원/join을 확인한 뒤, finite row 수가 아닌 전체 logical scope product와 기존 4개 symbolic portfolio/cap 산식을 적용했다. 이 probe는 E-only이며 production/test/Maven/Docker 변경이 아니다.
- **결과:** 원래 input 95,088,055 cells, 최대 intermediate 63,028,652,259,456. 보수적인 59 split은 input 92,012,331 / 최대 intermediate 22,538,226,233,952로 여전히 factor cap 2,147,483,647의 약 10,495배다. input 이득을 기준으로 한 111 split은 input 84,751,259로 줄었지만 최대 intermediate가 177,825,241,868,400으로 더 커졌다. 두 구성 모두 실패이며 production에 통합하지 않았다.
- **증거/한계:** `dp-global-glm/COMPONENT_FACTOR_GRAPH_CERTIFICATE.md`, `component-graph3-output.log`, `component-graph5-output.log`, `FULL_GRAPH_PROBE_DESIGN.md`. 이 결과는 검사한 encoding/선택 정책/기존 portfolio를 반박할 뿐 모든 exact 표현/순서의 불가능성 증명이 아니다. `denseAxisClasses`의 보수적 identity fallback 발생 여부를 별도 계측하지 않았으므로 계산된 partition이 요구한 full mask를 수학적으로 최소라고 주장하지 않는다.
- **중단 근거/다음 범위:** 독립 architect는 이 후보의 production gate를 BLOCK으로 종료했다. 이후에는 common preparation부터 factor construction/exact solve까지 이어지는 **factor-family별 support-witness 표현**을 별도 설계하고 원래 object/ordinal 복원, 정밀 산술/rounded tie, unchanged cap을 다시 증명해야 한다. 임의 후보 삭제, cap 상향, timeout 성공 처리, runtime fallback으로 목표를 맞추지 않는다. 현재 검증된 추가 production 해법은 없다.

### R10 control 품질 확인 및 main 빌드 산출물 반영
- **control 품질:** 같은 W1/LAN/DP-local의 동결 baseline 진단과 R10 진단을 비교했다. l2svm seed/final은 모두 `861.5023353826347`(final bits `4650789070499856465`), P1_FULL seed `47,350,691.09795345` / final `42,940,073.84566997`(final bits `4721032129008627358`)로 일치한다. 두 control의 최신 완료 incumbent 악화는 관측되지 않았다. 증거: `r10-control-objective-parity.json`.
- **범위:** control별 fresh-JVM 진단 1회이며 3회 교차 signal-free timing gate를 대체하지 않는다. JFR 진단 시간은 위 7-cell 성능 행렬에 포함하지 않는다. 두 최신 control 모두 compile-only 성공, execution=0, cleanup resolved=true다.
- **사용 가능한 산출물:** main의 기존 `target/SystemDS.jar`는 오래된 엔진이어서, main source/POM 2,171 hashes가 frozen R10과 일치함을 다시 확인한 후 **이미 검증한 동결 JAR 자체**를 main `target/`에 반영했다. 측정한 frozen source/JAR는 재빌드·수정하지 않았다. 이전 main JAR는 `main-pre-r10-jar-backup/`에 보관했고, 설치 전후 SHA는 `r10-main-artifact-install.json`에 기록했다. main `target/SystemDS.jar`도 SHA `8b3d13375085af1e07c99e145f031dca9400fe547dd922a0eeb4f1605c52f5e8`다.
- **설치 후 확인:** main JAR를 classpath의 첫 항목으로 사용한 memo/index/unary/sparse 23 tests PASS (`r10-installed-jar-junit.log`). 이는 unit/artifact 검증이며 host-native planning 성능 실험이 아니다. 최신 integrated 370개와 겹치므로 합산해서 고유 테스트 수를 부풀리지 않는다.
- **종료 상태:** 이 bounded 구현·검증 branch는 정리 완료했지만 사용자 목표인 모든 896개 planning ≤30초는 **미달/미완료**다. 안전성/성능 gate에 실패한 새로운 solver 표현을 main에 넣지 않고, 확인된 설계 blocker와 재현 자료를 남긴다.
