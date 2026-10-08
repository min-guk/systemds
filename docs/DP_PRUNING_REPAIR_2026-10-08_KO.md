# Local/Global pruning 오류 수정 및 실제 planning 측정

## 수정 범위와 독립 판정

이 기록은 앞선 [독립 재검증](DP_PRUNING_VERIFICATION_2026-10-08_KO.md)에서 발견한 두 결함의 후속 수정이다. 이전 실패 기록은 삭제하지 않는다.

- **코드 수정 판정: APPROVE** — 별도 code-reviewer **APPROVE / 발견 이슈 0**, architect 최종 **CLEAR**. 최초 WATCH에 포함됐던 Local 수치 경계 회귀 누락까지 보강한 뒤 재검토했다.
- 이 판정은 **한정된 correctness 수정**에 대한 승인이다. 전체 저장소 green, 임의의 실제 heap 고갈 복구, 실제 workload 속도 향상을 뜻하지 않는다.
- 합법 plan 공간, oracle/runtime 규칙, 기존 공개 `baseline`/`local_only` ablation 의미를 변경하지 않았다. 새로운 의존성, 공개 플래그, runtime fallback도 없다.

### 1. 선택적 SUFFIX 배열 할당 실패 — 해결

**증상/원인:** Local `regional-cost-bounds`, Global `exact-cost-bounds`/`exact-cost-bound-words`는 최적화용 배열인데, 할당 실패가 기존 exact 계산까지 중단했다.

**해결:** 이 배열의 `PlannerResourceGuard.allocateDoubles` 호출에만 `ResourceExhaustedException` 처리를 추가했다. 실패 시 해당 bucket의 bound 최적화를 생략하고 원래 정확한 합산을 수행한다. counters가 있으면 `resourceSkippedCostBuckets`와 준비 시간을 기록한다. Global의 두 번째 low-word 배열 실패도 같은 경계에서 처리한다.

**보존 계약:** 비용 검증, overflow 등 산술 예외, 필수 message/storage 할당 실패는 여전히 전파한다. 광범위한 catch나 런타임 계획 변경은 없다. 테스트는 해당 위치에 대한 결정론적 실패 주입이며, 실제 머신 전체 heap을 고갈시켜 회복을 입증한 실험은 아니다.

### 2. 빈 bucket 계측의 0 나누기 — 해결

**증상/원인:** 어느 factor에도 속하지 않는 합법 변수는 빈 bucket을 만든다. 계측용 `saturatedMultiply`가 0을 분모로 사용했다.

**해결:** 어느 인자든 0이면 즉시 0을 반환한다. isolated-variable 및 전체 factorless 문제에서 counters 사용 여부에 따른 objective/assignment parity를 검사한다.

### 3–4. 영구 실패 회귀와 raw LEGACY↔SUFFIX parity — 보강

- `CertifiedCostPruningFailureTest` **7개**: Local 일반/support, Global 일반/dyadic 두 번째 배열 실패; isolated/factorless bucket; 필수 할당 및 비자원 예외 전파.
- `CertifiedCostPruningWorkTest` **19개**: 기존 작업량 비교와 함께 raw high/low/choice·lower·witness를 LEGACY와 SUFFIX에서 직접 대조. sparse, separator-major, >53-bit carry, subnormal, near-max, 105-bit words, 잘못된 뒤쪽 비용의 validation 보존을 포함한다.
- 추가 Local 경계 **3개**: lower≠exact/residue(70-cell sparse parent merge), nonabsorbing infinity(lower=7/exact=10), 두 모드의 동일 overflow 진단.
- 기존 fixed-seed parity **6개**도 유지한다.
- 수정 전 실패 재현 로그: **8개 중 5개 실패**. 테스트를 최종 정리한 후 오류 경로 전용 suite는 7개다.
- 수정 직후 **29개 PASS**, 추가 Local 경계 반영 후 Work suite **19개 PASS**. 첫 fresh Maven package는 **280개 PASS / BUILD SUCCESS**였다.

### 변경 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CertifiedCostPruningFailureTest.java` (신규)
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CertifiedCostPruningWorkTest.java`

단순화는 예외 처리 경계를 선택적 배열 할당으로 제한하고, 실패 시 기존 정확한 계산 경로를 재사용한 것이다. 새로운 대체 solver나 후보 삭제 규칙을 만들지 않았다.

## 6. 실제 LogReg/GLM planning 측정 방법

- 공식 진입점 **`scripts/fedplanner/run_LAN_docker.sh --function-boundary-compare`**만 사용했다. `run_LAN.sh`는 사용하지 않았다.
- A: pruning 전 동결 JAR `216ea0accc5cc3ae6923bf422089bbab9221ec89a370acde8bc5e4b4c9a07db7`.
- B: 오류 수정 후 동결 JAR `da2b6ee65b8ebe5f74c60bbeda1c2d71f63793c0c7b8c98f525dd2f41f62d195`.
- 기존 공식 비교 runner가 class directory를 받으므로 두 JAR의 **전체 내용을 그대로 추출**했다. A는 baseline 전체 overlay, B는 candidate 전체 classes를 사용한다. 독립 검증 결과 각 디렉터리가 해당 JAR와 byte-identical하고, JAR 간 차이는 `ExactCategoricalSolver*.class` 45개뿐이다. JAR 직접 실행으로 잘못 표기하지 않는다.
- 동일 probe는 저장소의 **변경하지 않은 `MatrixCampaignProbe`**다. 공식 runner의 요구 main 이름에 맞춘 외부 adapter는 config의 planner를 읽고 script/config/planner/compile/receipt/seed를 그대로 전달한다. 성공 판정·runtime-program construction·authority/audit 검사를 완화하지 않는다.
- 실제 `multiLogReg(maxi=30,maxii=5)` / `glm(moi=20,mii=5)` 전체 DML, X **50,000×128 PRIVATE_AGGREGATE**, Y **50,000×1 public**, W1 federation metadata. 데이터 계산은 하지 않는 **production compile-only** 경로이며 전체 runtime program을 구성해야 완료다. search-space-only 또는 축소 toy workload가 아니다.
- Local=`compile_cost_based`, Global=`compile_exact`; 공개 ablation 플래그를 설정하지 않아 B에서 기본 SUFFIX가 선택된다.
- pinned image `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`, 4 CPU quota/16 GiB container/10 GiB JVM heap/ActiveProcessorCount=4, 동일 cost environment·dependency·builtin·seed.
- 각 case의 A→B를 **직렬** 실행. 둘 다 완료할 때만 순서를 번갈아 최대 5쌍 반복하도록 했다. 완료 deadline은 외부 runner 시작 기준 **60초, 약 0.5초 polling**이다. 경계 직후 완료가 poll 사이에서 감지되는 경우에도 60초 초과 row는 성능 성공으로 집계하지 않는다.
- 시간 초과 뒤의 진단/kill/cleanup 시간은 compile/planning 완료 시간이 아니다. 완료 receipt의 `planningFullInitialNanos`, `candidatePlanningNanos` 등을 얻었을 때만 속도 비교에 사용한다.

### 환경과 해석 한계

- Snap Docker가 `/grid` bind를 볼 수 없고 root disk 여유가 적어, 먼저 별도 읽기 진단으로 가시성을 확인한 **자체 `/dev/shm/snap.docker.dp-pruning-16aQsD` stage**를 사용했다. 파일·클래스·dependencies·probe·입력 hash를 보존하고 마지막에 다시 대조한다. 타 세션의 파일/컨테이너에는 손대지 않는다.
- CPU quota는 독점 CPU가 아니다. 같은 호스트에 기존 장기 실행 작업이 있어 작은 속도 차이에 대한 결론은 제한된다. 두 측정 사이에 Maven이나 자체 추가 성능 작업은 겹치지 않게 했다.
- cgroup `memory.peak`는 종료 직전까지 주기적으로 관찰한 값이다. timeout 과정은 완료 workload의 peak memory를 증명하지 않는다.
- 기존 production receipt에 SUFFIX cut 빈도가 없으므로 cut 횟수에 대한 주장은 하지 않는다.
- 이미지에 `jcmd`가 없어 deadline 후 첫 thread-dump 시도는 실패했다. 이 진단 실패를 workload 실패 원인이나 완료로 오인하지 않는다.

## 증거와 재현

Evidence root: `/grid/3/cofee-lm-sweep-mchoi-20260914/dp-pruning-repair-20261008/`

- `PLAN.md`, `before-manifest.json`, `before/`: 수정 계획과 변경 전 상태.
- `evidence/certified-cost-pruning-failure-red.log`, `certified-cost-pruning-regressions-green.log`, `certified-cost-pruning-local-boundary-green.log`: 실패→수정 및 수치 경계 검증.
- `evidence/repair-package.log`: 280개 fresh Maven package.
- `evidence/review-verdict.json`: 두 독립 리뷰의 최종 판정과 한계.
- `candidate/`: 실제 측정한 수정 solver source와 JAR.
- `setup_perf.py`, `run_perf.py`, `evidence/perf-artifact-manifest.json`, `perf-common-inputs-sha256.json`: 동결과 공식 Docker 실행/감시 설정.
- `real-planning/<case>-<A|B>-r1/`: 실제 명령, Docker log, phase marker, watchdog/cleanup, sampled cgroup peak/events.

기존 확장 suite의 baseline 실패와 미완료 사례는 이전 검증 보고서에 남아 있다. 이번 핵심 회귀의 통과를 저장소 전체 green으로 일반화하지 않는다.

## 실제 측정 결과 — 효과 판정 불가

**8/8 시도 모두 60초 watchdog 미완료**였다. 완료 receipt가 없어 compile/planning 완료 시간과 개선율은 산출하지 않는다. 모든 로그에서 `analysis_begin`은 있지만 `analysis_end`/`planner_begin`은 없다. 즉 이 관찰 구간에서는 공통 placement 분석을 끝내지 못해 Local/Global 비용 DP 성능 비교 단계까지 도달하지 못했다.

| 실제 workload | 변경 전 A | 수정 후 B | 관찰 peak A / B (GiB) |
|---|---|---|---|
| logreg_local_w1 | 60초 watchdog | 60초 watchdog | 6.33 / 6.97 |
| glm_local_w1 | 60초 watchdog | 60초 watchdog | 4.91 / 4.74 |
| logreg_global_w1 | 60초 watchdog | 60초 watchdog | 7.07 / 5.63 |
| glm_global_w1 | 60초 watchdog | 60초 watchdog | 5.84 / 4.70 |

- 각 run 120개 cgroup sample; 관찰된 OOM/oom_kill/max 이벤트 0. 메모리 수치는 **미완료 prefix에서 관찰한 peak**이며 완료 workload 간 memory improvement가 아니다.
- supervisor wall 61.29–61.63초에는 deadline 뒤 진단·kill·정리가 포함된다. 이 값을 planning time, 60초 완료, 또는 속도 개선으로 표기하지 않는다.
- 8개 timed container 모두 정확한 own name으로 중단되고 제거됨을 확인했다. A/B가 모두 완료한 case가 없어 반복 비교를 늘리지 않았고 W3/runtime 측정으로 범위를 넓히지 않았다.
- 결론: **버그 수정과 수치 보존은 승인; 실제 LogReg/GLM 최적화 효과는 미확인**. 다음 성능 과제는 DP 이전 공통 분석의 완료 가능성/비용을 해결한 뒤 같은 고정 artifact·입력 조건으로 다시 측정하는 것이다.

### 별도 진단 — 성능 sample에서 제외

동일 공식 Docker 진입점의 candidate LogReg Local을 별도 1회 실행하고 약 30초 뒤 own container에 SIGQUIT를 보내 thread dump를 얻었다. `main`은 `PlacementRelationClosure.bindDirectNativeCandidateRealizationsWithDependenciesMeasured:5125` → `DMLTranslator.prepareCommonSearchSpace:625`의 공통 분석에서 관찰됐다. 이는 phase marker와 일치하지만 단일 stack이지 통계적 CPU profile이나 전체 병목 점유율 증명은 아니다. 해당 진단은 8개 timed row/개선율에 포함하지 않으며, 진단 컨테이너도 제거했다. 원본: evidence root의 `diagnostic-logreg-local/`.

## 최종 검증 및 정리

- 추가 Local 경계 3개를 포함한 **33 suites / 283 tests PASS**, 실패·오류·skip 0, fresh offline Maven **package BUILD SUCCESS** (`evidence/final-package.log`). Java production/test compilation과 `git diff --check`도 통과했다. 별도 lint/static-analysis plugin은 이 POM에 설정돼 있지 않아 추가 analyzer 실행으로 표기하지 않는다.
- 최종 재빌드 JAR의 모든 entry 내용이 측정에 사용한 동결 candidate JAR와 동일함을 확인했다. 새 테스트 추가가 measured production binary를 바꾸지 않았다.
- 별도 측정 verifier가 **유효한 미완료(censored) 증거**로 승인했다. 속도 효과는 여전히 미확인이다 (`evidence/perf-independent-review.md`).
- 252개 common 입력/설정/probe/builtin, 두 JAR 추출 tree, 301개 dependency를 실행 후 다시 hash 대조했다. `perf-stage.tar`로 stage 전체를 grid에 보존하고 archive 내부의 sealed 파일도 검증했다. timed 8개 + diagnostic 1개 own container 부재를 재확인한 뒤 자체 tmpfs stage만 삭제했다.
- 기존 unrelated dirty 6개 파일 hash는 수정 전과 동일하다. 세션 문서는 기존 bytes 뒤에 이번 기록만 추가했다. Commit/push는 하지 않았다.
