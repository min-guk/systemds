# FedPlanner 리팩토링 전후 성능 조사와 추가 pruning·재계산 제거 계획

- 작성일: 2026-09-29. **상태: 조사·계획 완료, 이 문서의 새 최적화는 미구현.**
- 조사 코드: `refactor/w1357-paper-aligned-20260928`, `5a3ef7d759bf30ee2d848f7123fd963c4d5bbd57`.
- 현재 측정 엔진: JAR `100628da2bb3a489a43247d7d887d24aca81f41faa18ba98394e072a246d4dd2`.
- 행렬 관측 시점: **2026-09-29 04:46:09 UTC / 06:46:09 Europe/Berlin**. 진행 중인 run06의 고정 스냅샷이며, 최종 집계가 아니다.
- 이번 작업은 읽기 전용 코드·이력·로그 조사와 계획 문서 작성이다. 실행 중인 엔진·harness를 변경하거나 추가 성능 실험을 시작하지 않았다.
- **v2 개정:** 여섯 질문에 대한 [pruning·증분 전파·DP 감사](FEDPLANNER_PRUNING_SIX_QUESTIONS_REVIEW_2026-09-29_KO.md)를 반영했다. earliest-safe 적용표, 독립 complete-space 검증, 부분 증분성, DP 표현 변경의 정책 영향에 관한 아래 교정이 v1보다 우선한다. v1은 evidence root의 `plan-v1-before-six-question-audit.md`에 보존했다.

## 1. 결론

**예전에 60초 안에 훨씬 빨리 끝난 기록은 실제로 있다.** 9월 10일 기록은 2,016회 중 2,015회 성공했고, 기록된 compilation은 1.186–14.195초였다. 다만 당시에는 native compile-only, Global/V6/V8, 별도 frozen source overlay를 사용했다. 현재 Docker·물리 후보 표현·4개 planner와 동일 조건은 아니다. 따라서 기억을 단순한 계측 착오로 치부해서도 안 되고, 그 숫자로 리팩토링의 배율을 계산해서도 안 된다. [H1]

**현재 문제는 적어도 두 개다.**

1. **공통 search-space 구성 비용:** logreg에서 약 14초, GLM에서 약 25.6초가 드는 반면 FedFirst/AggLocal 자체 adapter planning은 각각 약 0.6초/2.4초다. 두 heuristic의 선택 정책보다 공통 후보 구성 경로를 먼저 가볍게 해야 한다. [E1]
2. **DP의 분석 이후 비용:** DP-local logreg 16개와 GLM 16개는 모두 `analysis_end` 이후 `planner_begin`에 도달했으나 `planner_end` 전에 60초 timeout됐다. 전체-root 전처리/seed repair/solver 중 어디가 현재 가장 큰지는 추가 분해가 필요하다. 과거 동일 계열 JFR와 현재 코드상으로는 **고차 input-authority factor를 dense하게 펼치는 비용, 전체-root freeze, seed 준비**가 우선 조사 대상이다. [E1, H4, S5–S7]

**권장 순서:** 현재 실패의 정확한 phase 및 SliceLine W1 합법성·earliest-safe 조건 확인 → DP의 illegal authority product와 cell당 할당을 별도 변경으로 개선 → 저위험 same-snapshot/index/profile 재사용(R3a) → factor 구조/pre-freeze support(R2) → dependency가 검증된 cross-revision/SCC 개선(R3b) → 측정된 후처리 중복 제거 → 새 엔진으로 전체 896개 검증.

단순히 후보 개수를 줄이는 것만 목표로 삼지 않는다. **합법적 후보는 유지하되, 같은 사실을 다시 계산하지 않고 불법 조합을 만들기 전에 차단하며, 조합을 필요 이상으로 평탄화하지 않는 것**이 핵심이다.

## 2. 요구사항과 변경하지 않을 계약

1. 4개 planner는 **동일한 합법 후보 공간**을 공유한다. FedFirst/AggLocal만 후보 생성을 생략하거나 축소하지 않는다.
2. coordinator workload의 timeout은 compile/runtime/diagnostic 모두 **60초 고정**이다. Docker 준비·정리는 별도 기록하되, JVM 시작 및 전체 compile이 포함되는 기존 watchdog을 줄여 계측하지 않는다. timeout을 성공 또는 60초의 완주 시간으로 기록하지 않는다. [S10]
3. runtime fallback, 임의 top-K/beam, 후보 수 cap으로 정상 후보 제거, 편의상 opcode 차단, privacy 완화, cap 상향을 하지 않는다. TRead/TWrite는 `<CP,LOUT>` 또는 `<FED,FOUT>`만, recompile의 `<CP,FOUT>` 금지는 유지한다. [AGENTS.md]
4. DP-local을 최적의 global solver로 다시 만드는 작업이 아니며 새 heuristic 점수/임의 탐색 제한을 도입하지 않는다. FedFirst/AggLocal의 선택 정책은 유지한다. **동작 보존형 cache 최적화**와 **합법 공간 보존형 domain/factor 구조 변경**은 별도 검증한다. 후자는 DP-local의 seed·merge 방문 순서와 시간제한 내 최종 선택을 바꿀 수 있으며, 그 차이를 허용·감사하는 경계를 7.1에 명시한다. 합법 공간/authority/정밀 비용과 cap·timer 계약은 두 유형 모두 필수다.
5. 실험은 `scripts/fedplanner/run_LAN_docker.sh` 경로만 사용한다. 과거 native 결과는 **이력 증거**로만 취급한다. PUBLIC-only 테스트는 기존 지침대로 제외하되 PRIVATE/PRIVATE_AGGREGATE 합법·불법 양쪽을 검증한다. [AGENTS.md]
6. run06의 엔진/harness를 측정 도중 바꾸거나 서로 다른 JAR 결과를 같은 성공 행렬로 합치지 않는다. 구현 단계에서는 기존 driver의 종료 또는 명시적으로 관리된 정지 경계를 먼저 확정하고, 새 JAR·manifest·결과 root를 만든다. **이번 계획 작성에서는 기존 실행을 정지하지 않았다.**

## 3. 이전 commit에서 실제로 무엇이 달라졌나

### 3.1 시간순 비교

| 구간 / commit | 확인한 변화와 기록 | 해석 / 비교 한계 |
|---|---|---|
| 9/10 `28eb8072eeffc9810956e95bb7f633532d6d14d2` + frozen V6/V8 overlay | 14 workload × 4 network × W1/3/5/7 × 3 method × 3 rep = 2,016회, 2,015 pass/1 timeout. compilation 1.186–14.195초, planner 0.235–6.058초. [H1] | 실제 빠른 과거 기준선이다. **commit checkout만으로 당시 엔진이 복원되지는 않는다.** native JVM/8GB/8 cores, Docker·worker 실행 없음, network는 모델 값이다. 현재 성능 배율의 분모로 사용하지 않는다. |
| 9/14 `ffb7be5bd8` → 9/16 `d8fbd30b54` | B0/B1 physical plan-space 비교 이력. 이전 표현의 decode가 `INCOMPLETE/LEGACY_REPRESENTATION_LIMIT`인 부분이 있다. [H2] | 후보 표현 확대와 완전성 수정이 섞였다. 이전에 빠른 것이 같은 합법 공간을 다뤘다는 증명은 아니다. |
| 9/22 `a20175c8b2` 기록 | W4/LAN logreg DP 97.991116초, 그중 analysis 59.501222초, model 6.962106초, optimizer 27.193590초. [H3] | **9/28 파일 분리 이전에도** 60초를 넘는 실행이 있었다. 해당 성공 기록을 모두 60초 이내 성공으로 읽으면 안 된다. |
| 9/25 `6cf1aba6a4` | factor/total-cell 기본 제한 10M/50M을 Integer.MAX_VALUE/Long.MAX_VALUE 쪽으로 변경한 이력. [H5] | 기계적 리팩토링과 별개인 **작업량/실패 경계 변경**이다. 옛 cap으로 빨리 실패하게 만드는 것은 해결이 아니다. |
| C0 `cfab6c8258` ↔ 9/28 최종 검증 `8ed2df57a6` | 같은 correctness prerequisite를 적용한 paired source/JAR에서 physical fingerprint와 semantic work count를 비교했다. W1 logreg OFF 28.004893→30.975320초, ON 32.716453→29.201786초. [H6] | 단일 측정, search-space-only 비교다. OFF 약 10.6% 증가 신호는 있지만 ON은 반대다. 현재 폭증 전체가 파일 분리 때문이라는 근거는 아니다. |
| 최근 privacy pruning `a9b1ca711e` 전후 run05→run06 | 공통 성공 58쌍의 per-cell 비율 중앙값: compile 0.998, search-space 1.009, adapter 0.999. [E2] | 최근 pruning으로 폭넓은 시간 개선이 확인되지 않았다. logreg/GLM timeout 해소 증거도 아니다. 단일·비교차 실행이라 통계적 동등성 증명은 아니다. |

### 3.2 “리팩토링 commit”을 정확히 나누기

실제 production 변경은 다음과 같다. `8ed2df57a6`는 주로 검증 문서 endpoint이지, DP solver를 바꾼 commit이 아니다. [H6, H5]

| commit | 역할 | 비교 방식 |
|---|---|---|
| `a5e4996cf6` | private 용어 정리 | rename 전후 의미/호출 순서 동일 확인 |
| `14287414d2` | facts / candidate generator / relation closure / support / diagnostics 분리 | 주요 기계적 extraction 경계 |
| `a8bfa88413` | C0 correctness 수정의 translated import | **의미 변경을 따로 비교**: loop seed memo와 executable ordinary FOUT→local 입력 복구 |
| `151d17b7eb` | complete closure update와 physical authority commit 경계 집중 | update/invalidation 순서 비교 |
| `d6ac46ea9e` | committed revision 안에서 node index 재사용 | 이미 들어간 최적화; 다시 신규 과제로 잡지 않음 |

위 production 변경들은 DP solver를 직접 수정하지 않았다. C0는 별도 가지이므로 `git bisect`만 기계적으로 적용하지 않는다. **동일 correctness를 가진 monolithic C0와 refactored source를 짝지은 비교**, 이후 candidate 표현 확대·cap 변경·privacy 변경을 각각 분리하는 방식이 필요하다. [H5–H6]

## 4. 현재 관측: search-space, DP 내부, 진짜 후처리를 구분

### 4.1 고정 스냅샷

run06의 896 compile 조합 중 **542 pass / 44 fail / 310 pending**, runtime은 0건이다. 실패는 다음처럼 분류된다. [E1]

- DP-local logreg 16 + GLM 16: **60초 timeout 32건**. 전부 shared analysis 이후 planner 내부.
- DP-local SliceLine ADULT/COVTYPE × worker=1 × 4 network: **`EXACT_VE_NO_FEASIBLE_ASSIGNMENT` 8건**. 성능 문제가 아니라 별도 correctness 조사 대상.
- network-quality gate invalid 4건: 해당 compiler는 rc=0/성공 receipt를 남겼지만 유효 성능 측정이 아니다. compiler failure와 구분한다. [E1, S10]

timeout의 analysis marker는 logreg 12.509–29.001초, GLM 21.985–37.039초다. **marker는 성공 search-space receipt와 같은 계측 정의가 아니며, 완료하지 않은 compile 시간을 추정해 채우지 않는다.** [E1, S9]

### 4.2 성공한 유효 측정의 중앙값, 초

| planner / workload | n | 전체 compile | search-space | adapter planning | 기록된 post-optimizer 항목 합 |
|---|---:|---:|---:|---:|---:|
| DP-local / l2svm | 15 | 10.923 | 5.602 | 3.735 | 0.254 |
| DP-local / P1_FULL | 16 | 19.076 | 13.820 | 1.458 | 1.695 |
| FedFirst / logreg | 15 | 17.182 | 14.573 | 0.614 | 0.604 |
| AggLocal / logreg | 15 | 16.777 | 14.138 | 0.641 | 0.610 |
| FedFirst / GLM | 16 | 30.586 | 25.544 | 2.347 | 0.796 |
| AggLocal / GLM | 16 | 31.112 | 25.599 | 2.487 | 1.008 |

출처: [E1]. 서로 다른 condition 중앙값을 다시 더해 전체 compile 중앙값과 맞추지 않는다.

계측 해석상 주의점: [S9]

- search-space = `commonPreparationNanos + analysisNanos`.
- adapter planning = setup + model + cost surface + optimizer + selection + other planning.
- 표의 post-optimizer는 selection + conversion + application 등 기록된 후속 항목이다. **selection은 adapter와 겹치므로 표의 열을 모두 더하면 중복 집계된다.** FedFirst/AggLocal은 exact optimizer를 호출하지 않아 이 열 이름이 실제 DP solve 종료를 뜻하지 않는다.
- `compile − search-space − adapter`에는 frontend와 backend가 함께 들어간다. 이를 전부 “DP 이후 시간”이라고 부르지 않는다. 상위 compile/Lops timer도 내부 timer와 중첩될 수 있다.
- 현 timeout은 **search-space 이후 planner 내부**라는 사실까지 확인됐다. “DP solve가 끝난 뒤 emission 때문에 timeout”은 현재 증거로 확인되지 않았다. 다만 P1의 진짜 후처리 약 1.7초처럼 별도 개선할 구간은 있다.

### 4.3 이전 JFR는 무엇을 증명하나

9/28 diag03은 첫 hard-conflict repair 7 physical decision이 70 encoded 변수로 확장되어 약 **6,583억 번의 VE 대입 작업**을 요구했고, analysis 이후 main-thread 표본의 83.375%가 exact solve에 있었다. 이후 이미 존재하던 conditioned compaction을 활성화한 diag04는 70→31 변수, 대입 작업 약 1.797억 회, exact solve 약 6.17초로 줄었다. **compaction은 현재 기본값이므로 다시 적용할 새 해결책이 아니다.** [H4, S7]

diag04에서도 전체-root 준비는 약 42.87초(freeze 약 36.37, support 약 0.113, quotient 약 5.45, rebuild 약 0.934)였다. 따라서 이미 펼친 테이블의 solver loop만 더 빠르게 하는 것보다 **factor scope·전개 시점·root 준비 작업량**을 줄이는 편이 유망하다. 다만 이 수치는 옛 diagnostic JAR이고 당시 긴 timeout을 사용한 역사 기록이다. **현재 32 timeout의 동일 원인을 입증한 최신 프로파일은 아니며, 새 진단은 60초를 넘기지 않는다.** [H4]

## 5. 현재 코드상 병목 후보와 추가 pruning

아래에서 **관측**은 실제 로그/JFR, **정적 확인**은 현재 코드의 작업 구조, **가설**은 아직 현재 JAR에서 비중이 측정되지 않은 부분이다.

### A. DP: 불가능한 조합을 dense factor로 만들고 나서 제거한다 — 최우선

**정적 확인:** `ExactPhysicalModel`의 input-authority factor는 consumer/direct source뿐 아니라 같은 source value version에 연결된 여러 decision을 scope에 넣는다. cell 평가에서 selected-state `IdentityHashMap`과 receipt 목록을 다시 만든다. reducer는 factor를 freeze한 **뒤에** support/quotient를 적용한다. `regionalSeed`와 전체-root preparation은 incremental 단계의 10초 budget보다 먼저 실행된다. [S5–S7]

1. **확실히 privacy-불법인 RELOCATION authority를 product 이전에 제외.** 현재 hard factor가 해당 action의 active relocation을 항상 ∞로 만드는 것과 정확히 같은 조건만 이동한다. 같은 action에 연결된 `DIRECT_FOUT`는 inactive/no-op authority로 합법일 수 있으므로 같이 지우지 않는다. [S5]
2. **durable worker-pool compatibility를 prefix에서 누적.** 완성된 Cartesian product의 leaf에서 reject하는 대신, 더 이어도 compatibility를 회복할 수 없다는 증명이 있는 prefix만 중단한다. UNKNOWN이나 아직 갱신 중인 anchor를 불가능으로 간주하지 않는다. surviving 순서는 유지한다. [S5]
3. **cell당 Map/List 할당 제거.** factor 생성 시 variable position, 선택 state의 receipt handle, source/action incidence를 준비해 primitive/indexed lookup으로 평가한다. 이 단계는 후보 pruning이 아니라 같은 truth table을 덜 비싸게 계산하는 작업이다. [S5]
4. **실제 의존성만 factor scope에 남기기.** 해당 prepared authority의 활성/만족 여부를 바꾸지 않는 축은 제거한다. same-value-version 전체 대신 exact receipt/action incidence로 dependency를 증명한다. 제거 축의 모든 값에서 raw factor 값이 같음을 작은 exhaustive fixture로 검증한다. **logical incidence만 보존해서는 DP-local 정책이 보존되지 않는다.** seed 보존형 경로는 원래 factor ordinal/scope/닫히는 시점/위반 집계 단위/비용 합산을 읽는 policy view와 내부 solver view를 구분한다. encoded root 자체가 바뀌는 경로는 anytime scheduling 차이를 7.1의 구조 변경 gate로 별도 검증한다. [S5,S7,Q1 §6]
5. **dense freeze 이전의 구조적 support propagation.** unary illegal state, binary native/direct support, guarded relocation relation을 분리·색인하고 증명된 zero-support domain만 삭제한다. 필요한 경우 lossless auxiliary/support DAG를 사용한다. 모든 조합에 기존 callback을 호출한 뒤 “미리 pruning했다”고 부르지 않는다. [S6]

**주의:** native-local의 `ABSENT_LOCAL`은 producer가 무조건 CP여야 한다는 뜻이 아니다. 현재 규칙에서 FOUT producer도 합법인 경로를 별도 검증한다. action/anchor/obligation을 지운 채 FType만 같은 상태를 합치면 안 된다. [S5]

### B. 공통 search-space: 변경 없는 owner를 다시 만들고 전체 proof index를 재구축한다

**정적 확인:** 한 owner commit이 proof inventory를 무효화하고 다음 소비자가 전체 facts를 모아 다시 inventory를 만든다. `buildNode` 이후에야 새 node/keys/facts의 동일성을 검사하는 경로가 있다. 바뀌지 않은 입력으로 비싼 생성을 먼저 수행한 뒤 no-op을 알아내는 구조다. [S1]

1. **생성 전 입력 revision 검사:** 첫 단계는 **동일 immutable committed-snapshot identity 안에서만** generation 결과를 재사용한다. revision을 넘는 재사용은 실제 query-time dependency를 기록·역색인한 뒤 허용한다. read-set은 abstract shape/single-partition facts, CFG/function alias와 compiled edge, privacy, source authority/reaching writer, anchor/action, transitive proof inventory를 포함한다. FType이나 일부 직접 입력 revision만으로 cache하지 않는다.
2. **static incidence와 dynamic authority export를 분리:** owner 교체로 영향받은 source/consumer 부분만 갱신한다. 우선 immutable snapshot의 lazy cache 재사용부터 적용하고, cross-revision delta나 전역 mutable index 전환은 위 dependency capture 및 동치 검증 후 별도 단계로 한다.
3. **epoch가 같을 때만 publication/support 결과 재사용:** relocation 발견·binding 교체·expired-clause 제거의 순서는 그대로 둔다. closure pass 수를 임의로 줄이거나 privacy/CFG/function 단계를 합치지 않는다. [S1–S2]

계측: owner build 시도/실제 생성/no-op 수, inventory 생성 수와 스캔 facts 수, dirty incidence 수, epoch별 support index 재구축 수. **현재 전체 중 얼마나 지배적인지는 미측정**이므로 이 카운터로 투자 순서를 정한다.

### C. Oracle profile·구조 key·partition fact의 중복 계산

- **profile:** row와 input-position×FType에서 `inferProfile`을 반복하며, facade가 OpSig/ShapeHint를 재구성한다. 현재 candidate Oracle call 카운터만으로는 profile 호출 비용이 다 보이지 않는다. immutable 준비 객체 + 정확한 key의 분석-scope memo를 추가한다. key는 **prepared OpSig, ShapeHint/shape proof, 순서와 null/ABSENT 구분을 보존한 input domain, analysis/occurrence revision**을 포함한다. hypothetical FType의 profile에는 concrete privacy authority가 없는 경우가 있으므로 privacy 불법으로 임의 제거하지 않는다. [S3]
- **key:** structural arena와 signature cache는 이미 있다. 새 전역 interning 프레임워크를 만들지 말고, 측정상 hot일 때만 기존 handle을 DirectSourceIndex의 nested record hash lookup 등에 재사용한다. hash 대상은 nested rule/input/realization/anchor 구조이며, 전체 proof graph를 매번 hash한다고 과장하지 않는다. equal-but-distinct key, canonical order, duplicate-key last-wins, arena overflow의 정확한 기존 경로를 보존한다. [S4]
- **partition facts:** 현재 complete-round snapshot 기반 전파를 reverse dependency worklist 또는 SCC 안의 동기식 round로 국소화할 수 있다. traversal 순서 독립성과 recursive proof semantics가 필요하므로 profile/index 개선 이후, 실제 hot일 때만 진행한다. 일반 worklist로 무조건 바꾸지는 않는다. [S3]

### C-2. v2: earliest-safe 조건과 부분 증분성의 공백

- **조건표를 R0 산출물에 추가:** [Q1]의 L1–L8/G1–G8마다 pruning 단위, authoritative read-set, 정보 미확정 시 DEFER, 현재/최초 안전 단계, invalidation, counterexample를 고정한다. tuple/emission/action/support/solver value 삭제를 구별한다. 모든 불법 조합을 사전 완전 판정하는 범용 엔진을 새로 만들지는 않는다.
- **ALL_REJECTED 조사:** Closure `1504–1507`의 zero-survivor mask 우회는 아직 남아 있다. certified terminal bottom과 provisional seed/CFG bottom을 먼저 구별하고, 전자만 전체 product 생략을 검증한다. 안전성 증명이 없으면 원래 경로를 유지한다.
- **빈 emission profile 조사:** Generator `391–403`의 excluded row profile 비용은 필요 여부/오류·audit 계약을 확인한 뒤 생략 또는 memo화한다. 가짜 profile/Oracle 결과를 만들지 않는다.
- **증분 전파의 실제 범위:** block 내부 root→input DFS의 후위 초기 생성, physical dirty worklist, direct SCC가 이미 있다. 하지만 physical 호출의 전체 index 준비(`Closure:5017–5048`), CFG 전체 map/edge 준비(`:2872–2905`), direct wave의 전체 boundary closure(`:3107`)는 여전히 남는다. [Q1 §3]
- **R3 분할:** R3a는 same-snapshot/static topology·profile 재사용. R3b는 query-time dependency capture 이후 dynamic source/action export delta, 생성 직전 revision skip, 닫힌 component semijoin이다. action replacement→expired deletion과 SCC seed/backedge를 보존하고, generation base를 이미 pruning한 bound view로 덮어쓰지 않는다.

### D. 정확한 추가 pruning과 “비효율 후보”의 경계

| 축소 대상 | 허용 조건 | 적용 위치 / 주의 |
|---|---|---|
| runtime/정책상 불가능한 candidate 또는 active relocation | 기존 oracle ReasonCode 또는 immutable privacy/global legality의 동일한 증명 | 최초 concrete 입력 authority가 확정된 지점. 임시 loop/function seed 단계에서는 확정하지 않음 |
| 서로 양립할 수 없는 input/source/anchor 조합 | prefix 이후 어떤 suffix도 모순을 회복할 수 없다는 증명 | product 생성 도중; 기존 same-owner exact-binding prune를 재사용·확장 |
| 유효 completion이 없는 support clause/state | **닫힌 epoch**의 정확한 source/action incidence에서 zero support | relation semijoin/worklist. OR 대안과 모든 reaching writer를 보존 |
| 반복되는 동일 authority product/proof | 향후 모든 관찰에서 동등하고 원래 witness로 lossless 복원 가능 | factorized 표현/공유 저장만 우선. source/receipt/anchor 차이는 단순 duplicate가 아님 |
| 비용이 높거나 local/FED 전환이 많아 보이는 합법 후보 | **이 이유만으로 공통 공간에서 삭제 불가** | 비용 모델이 비교. 정책별 순서 변경·top-K로 공통 후보 축소 금지 |

기존 same-owner binding prefix와 deletion-only support worklist는 이미 구현되어 있다. 새로운 일은 이를 또 작성하는 것이 아니라 **다른 정확한 불법 제약을 더 이른 product 단계에 옮기거나 재구축을 없애는 것**이다. [S1–S2, H7]

**v2 용어 교정:** “불법만 제거”는 shared-candidate legality pruning의 계약이다. DP에는 현재도 합법적인 observational-equivalent 값을 묶는 quotient와 비용 기반 선택이 있다. legality rejection / representation merge / policy selection·budget stop을 별도 통계로 기록한다. 모든 합법 상태를 명시적으로 탐색하지 않는 것과 공통 합법 공간에서 삭제하는 것은 다르다. [S6,Q1 §4]

출력 후보가 K개라면 모두 명시적으로 materialize하는 비용은 최소 Ω(K)다. loop/function fixed point도 일반적으로 진짜 single-pass가 아니다. 목표는 **각 revision에서 같은 사실은 한 번 계산하고, 변경된 의존성만 처리하고, 필요 없는 Cartesian product는 만들지 않는 것**이다. 표현 변경으로 이득을 내면 저장 객체 수와 논리적 합법 조합 수를 별도 보고한다.

### E. 실제 DP 종료 이후: 이미 있는 selected-witness 경로 재사용

`ExactPhysicalSelection`은 아직 전체 후보를 살피는 validation 경로를 호출하고, projector/relocation 선택에서 겹치는 탐색을 할 여지가 있다. `CandidateSelections.resolveAndValidateSelected`는 이미 존재한다. 새 validator를 추가하기보다 complete chosen witness를 전달해 기존 selected-only API를 사용할 수 있는지 검증한다. foreign/stale analysis scope, receipt, derived authority 검증을 생략하지 않는다. [S8]

성공 l2svm의 해당 구간 약 0.254초와 P1 약 1.695초를 구분해 우선순위를 둔다. 이것을 현재 32 DP timeout의 확정 원인으로 삼지 않는다. [E1]

## 6. 구현 순서와 완료 기준

기존 조기 privacy 계획의 P0–P3와 혼동하지 않도록 새 단계는 **R0–R6**로 명명한다. 각 단계는 회귀 테스트를 먼저 고정하고 작은 commit으로 진행한다. DP 경로를 우선 정상화한 뒤 FedFirst → AggLocal → DP-global을 확인한다.

| 단계 | 작업 / 대상 | 검증 가능한 완료 기준 | 위험 / 우선순위 |
|---|---|---|---|
| **R0: baseline·진단·correctness** | frozen baseline와 phase/factor 계측, SliceLine W1 red, L/G earliest-safe 조건표, 독립 bounded whole-plan universe와 mutation fixture. [H1,H6,S5–S10,Q1] | 60초 중단 전 마지막 phase/work 보존. 계측 ON/OFF parity. pruning마다 authority/DEFER/단위/certificate와 피한 작업 확인. SliceLine encoding·global model·지역 seed 실패 분류 | 최우선. 원인 가설, 실제 correctness 실패, 검증 blind spot 분리 |
| **R1: illegal-product와 할당을 분리** | A-1/2의 authority/pool pruning은 구조 변경 gate, A-3의 allocation-free 평가는 동작 보존 gate. C-2의 ALL_REJECTED/profile 조사도 분리. [S5,Q1 §1] | old full product의 합법 survivor 집합·순서와 hard truth 보존. inactive DIRECT_FOUT 보존. 할당 제거 경로의 deterministic trace parity. terminal bottom 증명 없이는 zero-mask guard 변경 금지 | 중간 위험. 불법 값 삭제도 partial seed를 바꿀 수 있음을 기록 |
| **R2: 고차 factor 표현 개선** | A-4/5. 원래 policy view를 유지하는 exact-kernel 내부 축약과 encoded-root 변경을 별도 commit으로 구분. [S5–S7,Q1 §6] | 합법 assignment의 양방향 projection, canonical cost, 조건부 optimum/명시된 tie, auxiliary 복원 및 bounds 검증. 동작 보존형만 trace parity 요구. root 변경은 7.1의 구조 변경/품질 gate 적용 | 높은 위험/높은 기대 효과. 같은 objective라는 이유로 fixed-budget 선택 동치를 주장하지 않음 |
| **R3a / R3b: 준비 재사용 / 증분 전파** | a: same-snapshot topology/index/profile reuse. b: captured read-set delta, dynamic export/index, generation-before-no-op, closed-component semijoin. [S1–S4,Q1 §3] | a는 같은 snapshot의 반복 준비 제거. b는 exact revision별 재생성/영향 incidence만 방문; additions/replacements/indirect alias도 invalidation. full-rebuild 대비 legal set·authority·multiplicity 동일 | a는 R2 전에 적용 가능. b는 stale cache/loop seed 위험으로 별도 gate |
| **R4: 잔여 preparation·자료구조** | R0 이후 남은 hot path만: regional repair의 필요한 exact slice 우선 준비, immutable root/index 재사용, cost-edge 인덱싱, cycle-safe materialization memo. support AC worklist/partition SCC/handle lookup은 측정 후. [S3–S7,S11] | global objective 평가·lower/upper bound·원래 cap/10초 budget 시작 경계 유지. 기존 지역 문제와 slice 조건부 문제 동치. callback/error 순서 계약 회귀 통과. 충분한 이득이 없으면 해당 변경 제외 | lazy root는 고위험 후속 과제. R1/R2로 해결되면 생략 |
| **R5: 선택 후 중복 검증** | E. 기존 selected-only API + analysis-revision에 묶인 검증 증거 재사용. [S8] | selected-only/full validator positive·negative parity. stale/foreign receipt/anchor는 계속 reject. conversion/application 시간과 unselected-row visit 수 감소 | 중간 위험. R0에서 후처리 hot인 workload부터 |
| **R6: 통합 검증** | 아래 7절. 새 root, 고정 60초, 같은 Docker/harness·자료·privacy로 paired와 full matrix | 896개 유효 compile 성공, timeout/합법성 실패 0, cleanup 완료. 독립 search-space completeness gate 별도. compile gate 이후 runtime logreg→l2svm→나머지 | 실행 성공을 모든 합법 후보 보존 증명으로 대체하지 않음; 시간 제한 완화 금지 |

### R0의 SliceLine W1 분리 진단

공통 search-space가 비었다고 먼저 결론 내리지 않는다. **동일 조건에서 검증된 FedFirst chosen receipt/witness를 DP의 hard-factor와 cost 평가에 넣는 진단**을 만든다. 이는 DP의 결과로 heuristic 계획을 대신 실행하는 fallback이 아니다.

1. **같은 immutable PlacementAnalysis**에서 FedFirst의 `NormalizedPlannerResult`와 DP model을 구성한다. 선택 state + candidate receipt + relocation choice를 `ExactPhysicalModel.Alternative`에 대응시킨다. canonical decision/state/rule/emission/realization/support identity 및 input-position별 source authority/action까지 비교해 완전한 witness마다 유일한 match를 요구한다. 0개/복수 match는 상세 key와 누락 authority를 남기는 별도 encoding/mapping 진단이며, 임의의 첫 대안을 고르거나 즉시 DP infeasible로 결론 내리지 않는다.
2. 매핑된 assignment의 **hard factor를 먼저 전부 평가**하고, 모두 finite일 때만 canonical cost를 평가한다. 양쪽을 통과하면 DP 전체 문제가 불가능한 것이 아니다. seed/지역 repair block/경계 조건에서 실패한 이유를 찾는다.
3. hard factor에서 실패하면 최초 불일치 factor·input authority·action 활성 상태를 출력하여 shared legality와 DP model 중 잘못된 쪽을 고친다.
4. anchor·worker count·single-partition의 작은 protected regression을 추가한 뒤 8개 원래 조건을 60초 Docker로 재검증한다. [E1,S5–S7]

### R3의 안전한 invalidation 순서

가장 먼저 읽기 전용 immutable revision에서 재사용을 적용한다. `replacement action bind → expired clause 제거`와 complete owner commit의 원래 경계를 유지한다. source addition/deletion뿐 아니라 **authority replacement, indirect dependency, privacy refinement, UNKNOWN→known cardinality**도 invalidation 사유로 검증한다. live Hop의 값만 비교하거나 같은 FType이라는 이유로 재사용하지 않는다. [S1–S4,H6]

## 7. 검증 설계와 최종 합격 조건

### 7.1 변경 전 잠글 의미 기준

기존 테스트를 확장한다. 새 프레임워크나 의존성을 추가하지 않는다.

- 공통 공간: `EarlyPrivacyPruningLegalSpaceParityTest`, `CandidateIncomingSupportCompletenessTest`, `CandidateReceiptAssignmentCompletenessTest`, `RelocationBindingPrefixPruningTest`, `PlacementSupportDeletionWorklistTest`.
- loop/shape/metadata: `StepLmDynamicLoopPlacementTest`, `SinglePartitionFactsTest`, `FunctionReturnSinglePartitionFactsTest`, `LocalBroadcastMatmulCandidateTest`, `PlacementIdentityAnalysisScopeTest`, `PlacementStructuralArenaTest`.
- DP: `ExactPhysicalModelCertificateTest`, `ExactPhysicalSemanticBindingOracleTest`, `ExactPhysicalReducedSolverTest`, `LocalPhysicalOptimizerIncrementalTraceTest`, `RegionalSearchProblemTest`, `IncrementalRegionalOptimizerTest`, cost fingerprint/preflight 회귀.
- 위 클래스 경로는 각각 `src/test/java/org/apache/sysds/hops/fedplanner/placement/` 및 `.../fedCostBased/fedExact/`다. 기존 GLM/cardinality fixture의 알려진 실패는 별도 재현·분류하며, 이번 회귀를 기존 실패로 숨기지 않는다. [H7]

**새 전용 회귀를 먼저 추가한다**(아래는 예정 이름이며 이미 존재/통과한 테스트가 아니다).

- `ExactPhysicalFactorProjectionPolicyParityTest`: **동작 보존 경로**의 original factor ordinal/scope/closure/violation 단위, seed, repair 순서, 선택 비교. 구조 변경 경로는 별도의 exhaustive relation/bound/quality 회귀로 구분한다. 기존 trace schema 테스트를 정책 증명으로 취급하지 않는다.
- `ExactPhysicalWitnessEncodingTest`: 동일 analysis의 complete chosen witness를 DP Alternative에 매핑하고, 0/복수 match와 최초 hard-factor 불일치 진단, hard 통과 후 cost 평가 순서를 검증한다.
- `PlacementGenerationRevisionCacheTest`: 같은 snapshot의 no-op 재사용 및 간접 source/alias/action/shape/privacy 변경 때의 invalidation. 기존 full rebuild를 reference로 최종 complete legal space를 비교한다.
- `EarliestSafePruningStageTest`: [Q1 §5]의 authority-ready/DEFER/zero-survivor/변경 revision에 따른 호출·할당·freeze work 검증.
- `IndependentCompletePlacementSpaceTest`: production survivor domain에서 출발하지 않는 작은 원래 universe의 **모든 placement+receipt+action**을 독립 판정; branch/loop/function/all-writer 및 합법 witness 누락 mutation 포함. 현재 selected-placement receipt oracle의 범위를 이 새 검증으로 보완한다.

**합격 조건:**

1. **공통 legality pruning**은 같은 compiler/Oracle/runtime 계약 아래 old/new의 합법 complete assignment 집합이 양방향 동일해야 한다. 삭제 사유와 authority/closed-epoch 증명을 보존한다. 과거 candidate 생성기의 누락을 정답으로 굳히지 않도록 independent universe·mutation 검증을 함께 한다. 현재 네 golden corpus와 fixed selected-placement receipt oracle은 전역 완전성 증명이 아니다. [Q1 §4]
2. **동작 보존형** cache·할당 개선은 기존 semantic fingerprint, raw objective bits, deterministic seed/repair/선택 assignment/order/receipt를 유지한다.
3. **합법 공간 보존형 구조 변경**은 원래 변수/witness의 양방향 relation 해석, 모든 작은 대응 assignment의 hard truth·canonical cost bits, 조건부 exact optimum·명시된 tie, auxiliary 복원을 검증한다. quotient의 합법 값 병합은 illegal rejection과 분리한다. 원래 complete witness의 대표 선택과 class 해석을 혼동하지 않는다. factor layout SHA나 TIME/TARGET/RESOURCE 결과가 반드시 동일할 필요는 없다. [S6,Q1 §4,§6]
4. cap/validation preflight를 늦추거나 제거하지 않는다. 동일 표현의 미세 최적화는 raw-size/오류·callback 순서도 유지한다. 구조 변경으로 retained shape가 바뀌는 경우 raw logical cardinality와 실제 allocated cells를 함께 보고하고, 원래 cap의 적용 단위를 몰래 바꾸지 않는다.
5. **DP-local 정책의 v2 교정:** logical incidence 보존만으로는 부족하다. factor의 original ordinal/scope/닫힘/위반 개수/정밀 비용 합산을 policy view로 보존해야 기존 seed parity를 주장할 수 있다. encoded root/domain까지 바꾸면 queue와 fixed-budget 최종 선택도 바뀔 수 있음을 명시한다. 구조 변경 gate에서는 deterministic 재현성과 작은 exhaustive 문제의 `lower ≤ optimum ≤ upper`, incumbent 합법성, canonical objective를 검증하고 before/after seed·repair·merge·stop·gap·upper를 보고한다. 기존 완료 control의 incumbent 비용이 나빠지면 해당 변경 통합을 중단하고 원인을 조사한다. 속도가 빨라졌다는 이유로 품질 악화를 묵인하지 않는다. 새 점수/임의 탐색 제한은 도입하지 않으며 FedFirst/AggLocal의 정책은 변경하지 않는다. [S7,Q1 §6]
6. **resource-stop 허용 범위:** cap 수치·단위·검사 위치와 기존 incremental 10초 budget 시작 경계를 바꾸지 않는다. 현 `initialSlots = Σ cells(encoded factor scope)` 산식을 새 encoded scope/domain에 그대로 적용한다. scope/domain 축소로 이 값과 실제 retained slot이 줄어 기존 `RESOURCE_INITIAL`이 사라지는 것은 허용 가능한 성능 효과지만, 이를 동일 stop trace라고 주장하지 않는다. 원래 logical space 크기는 별도 감사 지표로 보존한다. sparse 구현에서도 현 initialSlots 검사를 sparse 객체 개수로 대체하지 않으며 cap 산식 변경은 이번 범위 밖이다. before/after `initialSlots`, 실제 보관 cells/배열/메시지 수, cap, stop reason, lower/upper/gap을 함께 기록한다. budget 시작을 뒤로 미루거나 기존 준비 작업을 timer 밖으로 이동해 얻은 가짜 개선은 금지한다. [S7]
7. 실행 가능한 positive witness뿐 아니라 stale authority, foreign analysis, no anchor, PRIVATE_AGGREGATE aggregation/local boundary, 같은 producer의 상충 binding, inactive DIRECT_FOUT, loop seed 재등장, OR 대안, 모든 writer, singleton/unknown worker와 cyclic materialization negative fixture를 포함한다.

**단계 중단/되돌림 기준:** 합법 complete assignment 누락, invalid privacy/anchor 통과, canonical cost·bound·명시된 tie 위반은 두 유형 모두 중단한다. 동작 보존형의 deterministic policy 불일치도 중단한다. 구조 변경형의 방문 순서 차이는 기록된 허용 범위 안에서만 인정하며, control incumbent 비용 악화나 허용 범위 밖 policy 변경은 조사 전 통합하지 않는다. red fixture를 보존하고 동치를 증명하지 못한 작은 patch만 제외한다. 다른 작업자의 수정이나 이전 유효 결과를 되돌리지 않으며 assertion·cap 완화로 통과시키지 않는다.

### 7.2 성능 검증: 같은 조건끼리만 비교

1. **artifact 동결:** old/new source·JAR·probe·script·input/mtd/privacy·JVM flag·Docker image·CPU/memory·worker/network manifest를 보존한다. 9/10을 재현하려면 commit 외 build-11 overlay와 source manifest의 정확한 연결까지 복원한다. context가 가리키는 manifest 경로는 현재 없고 build 당시 before/after 파일 목록만 남아 있어 **현재 복원 완료 상태가 아니다**. 복원이 불완전하거나 현재 합법 공간과 다르면 “고고학 참고”로 남기고 정식 속도 비교에서 제외한다. 현재 JAR 대 새 최적화 JAR의 동일 조건 검증은 이 역사 복원과 독립적으로 진행한다. [H1,S10]
2. **최소 재현:** DP logreg W1/W3 LAN, GLM W1/W3 LAN, SliceLine ADULT/COVTYPE W1 LAN, l2svm control, 후처리 control P1을 같은 Docker에서 비교한다. 단계마다 원인에 해당하는 최소 fixture부터 실행한다.
3. **반복:** 제어 workload는 baseline/new를 교차 순서로 각각 최소 3회, fresh coordinator JVM·같은 resource 조건으로 실행한다. JFR 진단과 signal-free timing을 분리하며 둘 다 60초다. 3회만으로 통계적 유의성을 주장하지 않는다.
4. **보고:** 전체 compile, search-space, model, cost, root preparation, seed repair, incremental/solve, decode/selection, conversion/application, full runtime-program construction을 분리한다. work counters·peak memory·GC·logical 후보 수·저장 realization 수·support relation 수·factor arity/cells·VE predicted/actual work도 함께 남긴다.
5. **단계 성능 gate:** correctness pass와 목표 work 감소가 필수다. 성공 control의 compile 중앙값이 baseline 대비 5% 넘게 악화하면 그대로 통합하지 않고 동일 조건 재측정·phase 원인을 남긴다. heavy shared search-space 20% 감소는 **탐색 목표이지 보장/완료 주장 아니다**. DP timeout 해소가 우선이다.
6. **전체 행렬:** ML 10개 + P1_FULL/P2_PREP + SliceLine ADULT/COVTYPE = 14 × W1/3/5/7 × LAN/WAN-light/mid/heavy × DP-local/FedFirst/AggLocal/DP-global = **896개**. 최신 동일 엔진으로 유효 compile 성공 896개를 확보한다. network invalid는 성공 분모에서 빼고 같은 60초 조건으로 재실행한다.
7. **runtime gate:** full compile gate가 참인 뒤에만 요청된 순서 **logreg → l2svm → 나머지**로 runtime을 진행한다. numerical reference/audit/cleanup을 확인한다. compile-only pass를 runtime correctness로 부르지 않는다.

계측 변경은 자체 parity test 후 사용한다. CLI 형태는 기존 `run_LAN_docker.sh --campaign` 경로와 `run_matrix_campaign.py`의 고정 60초 제약을 따른다. 이 문서 작성 중에는 위 실험을 새로 실행하지 않았다. [S9–S10]

## 8. 예상 이득, 하지 않을 일, 남는 위험

- **가장 큰 잠재 이득:** dense product/factor의 차원·support를 줄여 **탐색할 조합 자체**를 감소시키는 R1/R2. 이득 배율은 아직 측정 전이며 고차 factor의 합법 결합은 여전히 지수적일 수 있다.
- **네 planner 공통 이득:** R3. rebuild 횟수 × 전체 facts/Oracle 비용을 줄인다. 지향 복잡도는 “전체 snapshot 매번 순회”에서 “변경된 dependency와 실제 신규 결과에 비례”지만, 모든 mutation이 국소적이라는 전역 O(N) 보장은 하지 않는다.
- **확실한 상수 비용 절감 후보:** prepared profile, allocation-free factor callback, selected-witness 검증. kernel 미세 최적화·hash 손보기만으로 큰 product를 해결하려 하지 않는다.
- **삭제하지 않을 것:** 비용이 비싸다는 이유만의 legal candidate, anchor 차이가 있는 같은 FType 후보, 미래 loop/function propagation에서 살아날 provisional state. 충분한 동치 증명 없는 dominance pruning은 이번 계획에 넣지 않는다.
- **이미 구현된 것을 신규 개선으로 주장하지 않을 것:** privacy seed/gate/mask, same-owner binding prefix, support deletion worklist, structural arena/signature cache, direct dirty-cone index, relocation product cache, conditioned local compaction, cost-row cache/streaming fingerprint, primitive exact kernel. [H4,H7,S1–S7]
- **주요 회귀:** stale index로 합법 후보 누락; OR/all-writer support 오해; inactive action 제거; auxiliary/tie 복원 변경; 검증 생략으로 invalid anchor 통과. 각각 R0/7.1의 counterexample와 exhaustive parity로 감지한다.
- **현재 남은 문제:** 32 timeout, SliceLine W1 8 실패, network invalid 4건, 전체 compile 미완료/runtime 미실행. 예전 GLM 단위 fixture 경계는 따로 남아 있으며, 현재 FedFirst/AggLocal의 GLM compile 성공과 구분한다. [E1,H7]

**결정:** 공통 후보를 무작정 줄이거나 옛 제한값을 되돌리는 대신, R0 이후 **불법 product/할당 개선(R1) → same-snapshot 재사용(R3a) → DP factor 구조 축소(R2) → 의존성이 검증된 cross-revision 재사용·전파(R3b)** 순서로 진행한다. 같은 합법 공간을 유지한 채 60초 안에서 완료시키는 것이 완료 기준이다.

### 계획 검증 기록

- **v1 기록:** 독립 read-only critic `/root/performance_recovery_plan_review`의 최초 보완 요구를 반영하고 재검토 OKAY를 받았다. 이후 여섯 질문 감사에서 logical incidence만으로 DP-local 정책 보존이 충분하지 않다는 추가 문제를 발견하여 **v2에서 검증 계약을 교정했다**. 이전 OKAY를 v2의 검증 결과로 재사용하지 않는다.
- **v1 자료 확인:** 33개 source/commit 참조, 스냅샷 집계와 32개 timeout phase 경로, 역사 CSV 2,016행 및 시간 범위를 확인했다. E1 디렉터리 `plan-document-verification.json`은 보존된 **v1 SHA**에 대한 기록이다.
- **v2 문서 확인:** 새 감사의 source 경로 17개, v2 계획·감사 SHA, 두 계획 사본 일치, 공백 검사 및 source/harness 미변경은 E1 디렉터리 `six-question-audit-document-verification.json`에 별도로 기록한다. v1 검증 파일을 v2 artifact로 간주하지 않는다.
- 이는 **계획의 검토 결과**다. 새 코드·속도 개선·전체 compile 성공의 검증 결과가 아니며, 이번 작업에서는 build/test/추가 workload 실행을 하지 않았다.

## 9. 근거 인덱스

`P = src/main/java/org/apache/sysds/hops/fedplanner/placement/`, `D = src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/`로 줄여 표기한다. 코드 행 번호는 조사 HEAD 기준이다.

### 보존된 측정 자료

- **[Q1]** `docs/FEDPLANNER_PRUNING_SIX_QUESTIONS_REVIEW_2026-09-29_KO.md`: local/관계 조건표, 실제 traversal/DP 순서, earliest-stage 공백, 독립 oracle 범위, v2 검증 계약과 코드 참조.
- **[E1]** `/grid/3/cofee-lm-sweep-mchoi-20260914/refactor-performance-plan-20260929/current-matrix-analysis.json`: 시각, JAR, counts/rows/failures, 각 원본 로그·receipt 경로. 같은 디렉터리 `current-phase-medians.csv`.
- **[E2]** 위 디렉터리 `recent-paired-cells.json` 및 E1의 `paired_run05_run06_*`. 공통 58쌍은 l2svm/pca/als/kmeans; logreg/GLM이 아니다. manifest 차이는 source/JAR SHA, 동일 harness/timeout. 단일 순차 실험의 한계를 유지한다.

### 역사

- **[H1]** `/home/mchoi/so007-incremental-regional-evidence-20260910/analysis/full-v6-v8-global-14w-4net-w1357-3rep/{trials.csv,summary.md:3–9}`; `analysis/FULL_VALIDATION_LAUNCH_KO.md:7–12`; `native/full-validation-configs/full-v6-v8-global-14w-4net-w1357-3rep/{protocol-w1.json,context-new-w1.json}`; `validation/build-11/{command.json,source-before.json,source-after.json}`. 핵심 CSV/protocol/context/build receipt 사본은 E1 디렉터리의 `historical-20260910-*`.
  - frozen build-09 V6 JAR SHA: `13229018d17c1538391f07e00a2ab46edb746586ceb9c974c6a7a6f39f390e83`.
  - build-11 V8/Global JAR SHA: `cb25a0e8032c2edde7e5c644e69d4f66267d33c22e58320ae022c20ad18048c1`.
  - context가 참조하는 `validation/build-11-source-manifest.json`은 조사 시점에 **존재하지 않음**. context에 남은 SHA `fe99bcfa3dcb2a5e4c1104891f28d20fbcaa98de4d36407fa5012697d9a7aa86`는 검증된 현재 파일의 SHA가 아니다.
  - 실제 보존된 `build-11/source-before.json`과 `source-after.json`의 byte SHA는 둘 다 `59dacb8c410bb5fde99cf24a000acba5b2ecb340a3be1000b6e201281fa465c6`. build command에 기록된 manifest digest `00eca207…`와는 직렬화/정의 확인 없이 동일하다고 간주하지 않는다. 파일 hash 목록만으로 당시 소스 내용이 복원되는 것도 아니다.
- **[H2]** `docs/G009_PE_AND_LEGACY_PLAN_SPACE_COMPARISON_EXECUTION_REPORT_2026-09-22.md:14–18,43–45`; `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/{B0,B1}`.
- **[H3]** `/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/experiments/results/fed4/mkl-cost/logreg_dataset-P2P2D_coordinator_mkl-cost_g009general9a3_35_logreg_P2P2D_mklcost_20260922_lan_coordinator1.log:5,22–36`.
- **[H4]** `docs/SESSION_ISSUES_2026-09-28.md:598–618,714–801,848–865`; `/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-policy-matrix-20260928-diag03/jfr-analysis/REPORT.md`; `/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-policy-matrix-20260928-diag04/compact-comparison.json`. 과거의 긴 diagnostic timeout은 이력일 뿐이며 새 실험에 적용하지 않는다.
- **[H5]** repository의 `git show 6cf1aba6a4`, `git show --stat` 및 각 patch: `a5e4996cf6`, `14287414d2`, `a8bfa88413`, `151d17b7eb`, `d6ac46ea9e`, `8ed2df57a6`.
- **[H6]** `docs/PAPER_ALIGNED_REFACTOR_EXECUTION_2026-09-28.md:3–18,20–38,103–145` 및 P5 sections; `docs/SESSION_ISSUES_2026-09-28.md:40–90`; frozen paired evidence `/home/mchoi/w1357-diagnostics/paper-refactor-20260928/`.
- **[H7]** `docs/FEDPLANNER_EARLY_PRIVACY_PRUNING_IMPLEMENTATION_2026-09-28_KO.md`; `docs/SESSION_ISSUES_2026-09-28.md:966–1104`. 최신 P0–P3 구현과 알려진 fixture 차이를 기록한다.

### 현재 코드

- **[S1]** `P/PlacementRelationClosure.java:4982–5001,5058–5119,5182–5186` owner commit/inventory/rebuild/equality; `:772–925` closure epochs; `:7138–7173,7193–7219,7308–7350` product cache/realization/prefix.
- **[S2]** `P/PlacementSupportRelations.java:218–255` support index; `P/PlacementRelationClosure.java:835,863,888,899,919` relocation/support call sites.
- **[S3]** `P/PlacementCandidateGenerator.java:146–158,285–295,337–394,666–685`; `src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java:198–205`; `P/SearchSpaceMetrics.java:151–165,356–393`; `P/SinglePartitionFacts.java:382–407,441–467`.
- **[S4]** `P/PlacementIdentity.java:64–144,434–518,1121`; `P/PlacementAnalysis.java:632–647`; `P/PlacementRelationClosure.java:3180–3203,3229–3277,8340–8370`.
- **[S5]** `D/ExactPhysicalModel.java:486–618` authority products; `:930–992` factor scope/preparation; `:1035–1069,1132–1158` active/privacy check and per-cell selection/receipt.
- **[S6]** `D/ExactPhysicalReducedSolver.java:432–504` freeze; `:560–606` support after freeze; `:619–710` quotient/rebuild.
- **[S7]** `D/LocalPhysicalOptimizer.java:67–94`; `D/LocalCategoricalOptimizer.java:599–605,867–914`; `D/SharedRegionalPreparation.java:74–93,165–179`; `D/RegionalSearchProblem.java:31–44`; `D/IncrementalRegionalOptimizer.java:51–58,96–160`.
- **[S8]** `D/ExactPhysicalSelection.java:150–215`; `P/CandidateSelections.java:1007–1052,1621–1698`; `D/ExactPhysicalPlacementProjector.java:47–58`; `P/RelocationSelections.java:1208–1230`.
- **[S9]** `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/MatrixCampaignProbe.java:93–135,192–194,225–241`; `P/CandidateFormationTiming.java:15–46,210–233`.
- **[S10]** `scripts/fedplanner/run_matrix_campaign.py:43–64,474–478,535–555`; `scripts/fedplanner/run_LAN_docker.sh`.
- **[S11]** `D/ExactPhysicalCostModel.java:573–633,1109–1159,1231–1257,1702–1706`; `D/RegionalSearchProblem.java:115–143`; `D/IncrementalRegionalOptimizer.java:339–353`.
