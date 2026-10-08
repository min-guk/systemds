# Local/Global 인증 비용 pruning 구현 기록

> **후속 오류 수정 완료.** 독립 재검증에서 재현한 선택적 bound 할당 실패와 빈 bucket 계측을 수정하고 영구 회귀를 보강했다. 수정은 독립 code-reviewer APPROVE / architect CLEAR. 실제 LogReg/GLM 8회는 공통 분석에서 60초 watchdog 미완료로, 속도 개선은 미확인이다. 최신 근거는 [수정 및 실제 측정 보고서](DP_PRUNING_REPAIR_2026-10-08_KO.md)를 따른다.

## 문제와 구현 범위

- 기준: origin/main `e468797556` + 기존 미커밋 작업을 보존한 checkout.
- 승인된 계획: `/home/mchoi/.omx/plans/dp-pruning-parity-2026-10-08.md`.
- P1은 최신 upstream의 rule-directed/MRV/option-level support 구현을 재사용한다. 기존 합법성 정의나 runtime 동작은 변경하지 않는다.
- P2/P3은 `ExactCategoricalSolver`의 같은 boundary cell에 대한 인증된 부분합 및 suffix 하한 pruning만 추가한다. 전체 incumbent를 이용한 boundary 상태 삭제는 제외한다.

## 변경 전 behavior lock와 정리 계획

- 변경 전 23개 기존 suite: 181 tests 중 3 failures / 4 errors. `ReferenceProductPrefixPruningTest`의 MRV 순서/사전 index read 기대 2건, `CandidatePrivacyInputPruningTest`의 current-domain certificate 존재 가정 5건. 테스트를 비활성화하거나 통과로 보고하지 않는다.
- 신규 `CertifiedCostPruningParityTest`: 6 tests, 230 fixed-seed 작은 모델의 objective/논리 message/lower/witness 비교. 변경 전 standalone JUnit 및 Maven package로 검증한다.
- 정리 대상: 여러 합산 경로의 중복 cutoff 판단. 작은 private suffix-bound holder만 재사용하고 기존 DD/정수 word 합산 및 비교 규칙은 유지한다.
- 순서: 기준선 고정 → 같은 상태의 cutoff 공통화 → 누락 경로 적용 → suffix 하한 → parity/work regression → 독립 리뷰 → Docker 검증.
- 최적화 미적용 분류: 음수/negative zero/residue/overflow 범위/tie callback 때문에 증명이 안 되는 경우 기존 정확한 계산을 유지하는 것은 계약 보존 경로다. 오류 은폐·runtime fallback·임의 후보 삭제는 도입하지 않는다.
- 독립 architect 검토: 조건부 승인. Local stored/logical coordinate 인증을 일치시키고, strict `>` cutoff, Global callback 비적용, sparse accumulator 양쪽 저장 형태, known-zero token의 원래 factor 위치를 보존할 것.

## 증명 경계

- Global 일반 경로는 입력 materialization/validation 이후 전체 factor table의 공통 비음수 lattice와 53-bit 합 상한을 인증한다. 각 elimination의 factor 소유권이 겹치지 않으므로 이 상한이 중간 message에도 적용된다.
- Global dyadic 경로는 기존 입력 변환의 최대합 인증을 사용하며 double-double과 word 산술을 혼합하지 않는다.
- Local은 기존 exact/lower 값 동일성 및 합 인증을 유지한다. exact 비용만으로 별도 lower minimum을 버리지 않는다.
- 새 cutoff는 `partial + certified unread minimum > same-cell best`이며, 동률의 original-coordinate canonical witness는 기존 코드가 결정한다.
- 새 cutoff의 dominated 결과는 미계산 후보를 나타내는 별도 반환값이며, 불가능한 상태를 뜻하는 infinity로 저장하지 않는다.
- legacy `BASELINE` / `LOCAL_ONLY` 옵션 의미는 유지한다. 새 최적화는 production 기본 실행 및 package-private 테스트 경로에서 검증한다.

## 적용 행렬

| 경로 | 이번 변경 | 검증 |
|---|---|---|
| Local 일반 merge | 기존 인증 prefix 유지 + cached child minimum의 suffix 하한 | prefix-only 4 reads → suffix 3 reads, exact/lower/witness 동일 |
| Local typed-hard join | 같은 output cell의 best를 사용한 suffix cutoff | support dispatch 기준 이하 fixture에서 감소 확인 |
| Local support/quotient | 누락됐던 인증 prefix/suffix cutoff 추가 | 원래 논리 좌표의 모든 row/lower/witness 보존 |
| Global dense | 같은 separator cell의 인증 prefix/suffix cutoff | 모든 elimination message 및 최종 assignment 비교 |
| Global sparse join | sparse/dense accumulator best 조회 + known-zero 원래 factor 위치 사용 | sparse→dense 전환 80개 row, hard join, dyadic carry 회귀 |
| Global dyadic separator-major | 인증된 두 word의 suffix 합과 strict 비교 | quotient lift 및 53-bit를 넘는 carry 보존 |

Local의 compact sparse numeric row 인증에서 stored index와 logical index를 섞던 부분도 수정했다. 이 검사는 `values`, `lowValues`, `lowerValues`의 같은 저장 위치를 비교해야 한다.

## 생성 단계 합법성: upstream 재사용

fetch/merge 후 P1을 다시 조사했다. `PlacementCandidateGenerator`의 빈 domain 차단, 보호 입력의 CP/ABSENT_LOCAL 사전 차단, rule-directed/MRV product 생성과 `PlacementSupportRelations`의 option-index/factorized survivor restriction이 이미 앞단에 있다. 이번 변경은 이를 중복 구현하지 않았다.

- 생성 시 증명된 불법성은 생성 단계에서 차단한다.
- 후속 discovery에서 support가 생길 수 있는 proposal은 publication fixed point까지 보존한다.
- 각 후보는 합법이어도 함께 선택할 때 충돌하는 경우는 relational support join을 유지한다.
- 비용/제약 반응이 같은 **합법 상태**의 quotient는 불법 후보 제거가 아니므로 costed preparation/DP에 남긴다.

`EarlyPrivacyPruningLegalSpaceParityTest`, `CandidateInputBottomDomainTest`, `RelocationBindingPrefixPruningTest`, `CandidateSelectionPruningOracleTest`, `PlacementSupportDeletionWorklistTest` 등을 회귀군에 포함했다. 아래 기존 7건은 별도 실패로 보존하며, P1 전체가 green이라고 보고하지 않는다.

## 검증

- 변경 전 신규 behavior-lock: 6 tests PASS, 230 fixed-seed 작은 모델. 변경 전 Maven package도 성공했다.
- 변경 후 targeted/placement/regional/numeric 회귀군: **270 tests, failures 0, errors 0, skipped 0**, offline Maven package **BUILD SUCCESS**.
- Docker 측정 후 동일 32 suites / 270 tests를 다시 실행해 PASS를 확인했다 (`evidence/final-regression.log`, 15.148 s). Docker candidate의 solver 관련 49개 class는 최종 compiled class와 byte-identical하다.
- 위 270개에는 신규 19개 테스트가 포함된다. 음수, 넓은 lattice, residue, infinity, strict tie, callback 관측, sparse storage 전환, 큰 dyadic carry를 검사한다.
- 독립 architect 검토의 조건을 반영했고, 최종 별도 code-reviewer는 **APPROVE / 발견 이슈 0개**를 보고했다.
- Java compile/testCompile로 타입을 검사했으며, 별도 Java checkstyle/PMD/SpotBugs 설정은 없다. 정적 변경 검사는 `git diff --check`와 독립 diff review로 수행했다.

### 기존 실패와 범위 제한

변경 전 181 tests에서 발생한 7건을 변경 후 해당 2개 suite의 8 tests로 재실행했고 **동일한 7개 test method가 실패**했다. 무시/비활성화하거나 신규 회귀를 숨기지 않았다.

1. `ReferenceProductPrefixPruningTest`: MRV 도입 후 과거의 방문 순서/사전 index read 기대와 충돌하는 2건.
2. `CandidatePrivacyInputPruningTest`: 이미 앞단에서 제한된 current domain에서도 새 compact pruning certificate가 존재한다고 가정하는 5건. audit provenance와 fixture 계약의 후속 정리가 필요하며, 오래된 certificate를 재사용해 통과시키지 않았다.

계측은 기존 `BoundaryMergeCounters`에 suffix-only cuts, 인증/미인증 bucket 수, bound scan cells, 준비 시간을 추가했다. Global 새 계측은 package-private test seam으로 제공하며 production 기본 실행에 trace 비용을 강제하지 않는다. 준비 시간 counter는 suffix 배열 준비/최솟값 scan을 측정하고 전체 인증 비용은 포함하지 않으므로, 총비용은 별도 Docker compilation 측정으로 확인한다.

### 결정론적 작업량 비교

같은 테스트 fixture의 assertion은 그대로 두고, 별도 evidence 경로에 복사한 테스트의 비교 helper에 counter 출력만 추가했다. 저장소 테스트나 production 코드는 계측 때문에 수정하지 않았다. 이 별도 실행도 19 tests PASS다. 아래는 **prefix-only 대비 suffix**이며 wall-time 개선율이 아니다.

| fixture | child reads: prefix → suffix | suffix-only cuts | bound minimum scan cells |
|---|---:|---:|---:|
| Local 일반 | 4 → 3 | 1 | 0 (cached minimum) |
| Local typed-hard join | 18 → 15 | 3 | 0 |
| Local support/quotient | 6 → 5 | 1 | 0 |
| Local sparse stored/lower | 4 → 3 | 1 | 0 |
| Global dense | 4 → 3 | 1 | 4 |
| Global sparse hard join | 8 → 7 | 1 | 88 |
| Global dyadic separator-major | 5 → 4 | 1 | 34 |
| Global sparse→dense accumulator | 640 → 560 | 80 | 644 |
| Global dyadic >53-bit carry: dense / sparse | 4 → 3 / 8 → 7 | 1 / 1 | 4 / 88 |

Global은 작은 fixture에서 scan 수가 아낀 합산 read보다 많을 수 있다. 따라서 이 표만으로 더 빠르다고 주장하지 않는다. 추가 bound 저장은 bucket factor 수에 선형인 scalar 배열 1개 또는 dyadic 배열 2개이며 Cartesian bound table은 없다. 준비 시간의 단일 실행 raw 수치와 재현용 source는 `evidence/deterministic-work-metrics.log`, `evidence/work-probe-src/`에 보존했다.

## 변경 파일 및 증거

- Production: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java`.
- Tests: 같은 패키지의 `CertifiedCostPruningParityTest.java`, `CertifiedCostPruningWorkTest.java`.
- 문서: 이 보고서와 `docs/SESSION_ISSUES_2026-10-08.md`의 추가 기록.
- 이전부터 있던 continuity/benchmark scripts/진행 문서/테스트는 원본을 보존한다. 새 의존성, 공개 옵션, runtime fallback, commit/push는 추가하지 않는다.
- 증거 root: `/grid/3/cofee-lm-sweep-mchoi-20260914/dp-pruning-parity-20261008/`.
  - `baseline/manifest.json`, `baseline/existing-local-files/`: 기존 변경 보존 증거.
  - `evidence/baseline-package.log`, `evidence/preexisting-failures-candidate.log`: 기존 실패 비교.
  - `evidence/baseline-behavior-package.log`, `evidence/candidate-package.log`: behavior lock 및 270개 검증.
  - `baseline/SystemDS.jar`, `candidate/SystemDS.jar`: 비교용 동결 바이너리.

## 아직 적용하지 않은 pruning

- 증명되지 않은 일반 부동소수점/음수/residue, Local exact/lower 불일치, Global tie callback에서는 새 비용 cutoff를 적용하지 않는다.
- whole-plan incumbent를 이용해 다른 boundary cell을 삭제하지 않는다. exact message와 truncated certificate의 구분 없이 infinity를 넣으면 API 계약이 깨진다.
- assignment별 더 강한 조건부 하한이나 새로운 Cartesian 하한 table은 도입하지 않는다. 현재 child별 최소값만 사용한다.
- DP 합산 read 감소를 plan 후보 수나 보관 message 크기의 감소라고 해석하지 않는다. 모든 가능한 pruning을 끝냈다는 의미도 아니다.

## Docker 동일 조건 검증 및 성능

- 공식 진입점 `scripts/fedplanner/run_LAN_docker.sh --cost-runtime-validation`만 사용했다. pinned image `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`, Java 17, CPU 4, RAM 8 GiB, worker 2, network none, 고정 seed/cost profile/input/probe를 두 바이너리에 동일 적용했다.
- 최초 `/grid` bind mount는 snap Docker에서 보이지 않아 `/evidence/run.sh` 실행 전 실패했다. baseline 자체의 실패로 해석하지 않고, 자체 `/home/mchoi/dp-pruning-runtime-601i0hzp` 임시 stage에 byte-identical jar/probe/dependencies를 놓고 재실행했다. 원래 harness의 판정 정책은 바꾸지 않았다.
- 5쌍 교차 실행 후 aggregate compile median이 **+5.19%**여서 승인 계획의 재측정 gate를 적용했다. 같은 조건으로 **추가 5쌍 전체 workload**를 실행했으며 aggregate는 **+1.07%**, 재측정의 모든 workload는 +5% 이내였다. 지속적인 5% 초과 회귀는 재현되지 않았다.
- 2회 측정군을 합쳐 baseline/candidate 각각 10 runs × 6 workloads = **120/120** PASS. numeric output, objective certificate의 원래 bits/assignment, selected relocations/materializations가 전부 동일하다. runtime fallback/repair는 전부 0이다. 각 run의 환경/입력 hashes도 동일성 검사했다.
- 아래 시간은 suffix 준비와 인증을 포함하는 실제 compilation 시간이며 modeled objective가 아니다.

### 최초 5쌍

| workload | baseline compile ms median [min–max] | candidate compile ms median [min–max] | change |
|---|---:|---:|---:|
| aggregate | 2019.7 [1748.0–2201.3] | 2124.6 [1909.4–2235.7] | +5.19% |
| shape | 2298.0 [2072.3–2753.6] | 2091.0 [2015.0–2452.2] | -9.01% |
| linear | 2535.8 [2169.8–2839.1] | 2375.5 [1931.1–2635.8] | -6.32% |
| control | 3414.1 [3186.2–3774.4] | 3291.7 [2926.7–3473.4] | -3.58% |
| branch_true | 3174.1 [2792.7–3390.3] | 3311.5 [3145.5–3448.1] | +4.33% |
| branch_false | 3154.8 [2832.2–3650.8] | 2927.4 [2854.1–3633.3] | -7.21% |

### 독립 재측정 5쌍

| workload | baseline compile ms median [min–max] | candidate compile ms median [min–max] | change |
|---|---:|---:|---:|
| aggregate | 2011.3 [1819.6–2216.7] | 2032.9 [1601.2–2199.0] | +1.07% |
| shape | 2488.4 [2249.5–2905.3] | 2259.9 [2133.3–2637.3] | -9.18% |
| linear | 2514.9 [2231.7–2708.2] | 2565.4 [2155.2–2895.6] | +2.01% |
| control | 3406.8 [3193.0–3942.9] | 3281.5 [3077.6–4034.7] | -3.68% |
| branch_true | 2918.3 [2891.8–3070.7] | 2823.7 [2783.2–3233.9] | -3.24% |
| branch_false | 3110.5 [2743.9–3387.3] | 2925.5 [2840.1–3126.0] | -5.95% |

6개 compilation 시간 합의 run별 median은 최초 측정 -3.30%, 재측정 -3.97%였다. 표본 범위가 겹치므로 이를 일반적인 속도 개선 보장으로 해석하지 않는다. 최초 aggregate의 phase trace도 별도 보존했다 (`evidence/aggregate-phase-diagnosis.json`).

**범위 제한:** 이 Docker harness는 DP-LocalConflict의 작은 실제 workload 검증이다. Global solver는 단위/모든 elimination-message/conditional exact-path 회귀로 검증했지만, 대규모 standalone DP-Global 및 LogReg 전체 runtime·peak memory 성능은 이번에 측정하지 않았다. Local/Global 모든 workload에서 더 빠르다고 주장하지 않는다.

**재현 증거:** `docker-pairs/`, `docker-recheck/` 각각의 run manifest/receipt/log와 `summary.json`, `summary.md`; 실행/요약 스크립트는 `evidence/run_pairs.py`, `run_recheck.py`, `summarize_pairs.py`, `summarize_recheck.py`. 모든 임시 run 결과는 SHA256 일치를 확인한 뒤 grid 증거 경로로 옮겼다.

최종 확인에서 기존 비문서 6개 파일의 SHA256 및 세션 문서의 원본 prefix가 보존됐다 (`evidence/final-preservation.json`). 임시 stage를 참조하는 live container가 없음을 확인하고 자체 stage만 제거했다 (`evidence/stage-cleanup.json`).

- baseline jar SHA256: `216ea0accc5cc3ae6923bf422089bbab9221ec89a370acde8bc5e4b4c9a07db7`
- candidate jar SHA256: `6a0fcf2f35f45f18188388cc7ca80ba09ae341af6fc3a3ae63578b144fe9aa77`
