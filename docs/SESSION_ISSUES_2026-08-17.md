# Session Issues — 2026-08-17

## Active Docker campaign storage pressure and verified offload

- **상태**: 해결 (캠페인 재개 및 지속 모니터링 중)
- **환경/조건**: immutable Docker campaign
  `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1`
- **적용 원칙/제약**: 실행 중인 cell의 경계에서만 중단하고, active stage/result와 측정 조건은
  변경하지 않는다. 물리 호스트 `run_LAN.sh`는 사용하지 않고 `run_LAN_docker.sh`만 사용한다.
- **의사결정 근거**: 현재 캠페인의 immutable stage/result는 보존하고, 이전 commit의 종료된 캠페인과
  legacy checkout만 checksum 검증 후 원격 보관한다.

### 관측 증상

- 최초 관측 시 219/336 성공, `failed=false`였고, 저장 공간이 계속 감소했다.
- clean resource-floor 경계까지 진행된 뒤 223/336에서 runner가 정상 종료했다.
- 223번까지 WAN-Light 112/112, WAN-Mid 111/112, LAN 0/112이며 `rows.jsonl`도 정확히
  223행이었다.
- root filesystem 여유 공간은 정리 직전 약 5.0GB, 사용률 99%까지 감소했다.
- 별도의 이전 캠페인(`7a286b0` stage/result)이 중단 상태로 남아 있었으며 현재 대상
  commit `54612b9`와 다른 obsolete 실행이었다.

### 원인 분석

StepLM runtime/audit log가 크고 phase bundle과 stage evidence가 서로 다른 inode로 중복 보존된다.
또한 이전 campaign stage/result와 legacy checkout이 동일 root filesystem에 남아 있었다. `/grid/*`
filesystem에는 충분한 용량이 있었지만 사용자 `mchoi`에게 쓰기 권한이 없었다.

### 해결 요약

1. 현재 대상 runner가 223번 cell 경계에서 정상 종료한 것을 확인했다.
2. obsolete `7a286b0` runner tree를 종료하고, active 대상 컨테이너가 없음을 확인했다.
3. `/grid` 대신 SSH batch 접속 가능한 `so006`의 다음 root로 불필요한 대용량 데이터를
   이동했다.
   - 원격 root: `/home/mchoi/g014-storage-offload-20260817T0326CEST`
   - `/home/mchoi/g007-functionfix-stage-20260729-v4` (8,155 files)
   - `/home/mchoi/g014-planning-audit-stage-7a286b0-d712daf-20260815-v7` (5,193 files)
   - `/home/mchoi/g014-full-results-7a286b0-d712daf-20260815-v7` (1,435 files)
   - `/home/mchoi/exdra_run/sigmod2021-exdra-p523` (28,580 files)
4. 각 경로에 대해 `rsync -cni --delete` 결과가 0건이고 regular-file count가 동일한 것을
   확인한 뒤에만 local 원본을 제거했다. 원래 경로에는 `OFFLOADED_TO_SO006.json` 포인터를
   남겼다.
5. free space는 약 5.0GB에서 41GB로 증가했다(99% -> 90%).
6. 동일 stage, output, seed(`2026072701`)로 runner를 재개했다. 완료된 prefix 검증 후 중복 없이
   224번 cell `workers=4|planner=Heuristic|workload=steplm|profile=wan_mid`를 생성하고
   Docker coordinator + worker 4개를 실행했다.

### 수정 파일

- `docs/SESSION_ISSUES_2026-08-17.md`
- `/home/mchoi/g014-storage-offload-20260817-receipt.json`
- 각 offload 원래 경로의 `OFFLOADED_TO_SO006.json`

### 검증

- 중단 시 `progress.json.completed=223`, `rows.jsonl=223`, `failed=false`.
- offload checksum dry-run: 4개 경로 모두 차이 0건.
- local/remote receipt SHA-256 일치:
  `a241ee64d054f17c7534708ecde4bc6bcfd91ebbdf6772adee7b62318e2baa9c`.
- local receipt: `/home/mchoi/g014-storage-offload-20260817-receipt.json`.
- remote receipt:
  `so006:/home/mchoi/g014-storage-offload-20260817T0326CEST/offload-receipt.json`.
- 재개 runner PID: `2738013`; SID/PGID도 `2738013`, parent PID는 1로 안정적으로 분리됨.
- control logs:
  `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/control/resume-from-223-20260817T0338CEST.{stdout,stderr}.log`.
- `2026-08-17T03:43:12+02:00` 기준 cell 224용 Docker coordinator 1개와 worker 4개가
  모두 `Up` 상태이며, coordinator/worker JVM이 runtime-plan audit를 켠 실제 StepLM 실행에
  진입했다. 완료된 prefix는 여전히 정확히 223행이므로 완료 cell 중복은 없다.

### 잔여 이슈

- cell 224와 이후 LAN 112개가 아직 완료되지 않았다. 진행 중에는 progress/row/failure invariant와
  남은 공간을 계속 감시한다.
- local pointer directory는 투명 mount가 아니다. obsolete evidence를 다시 사용할 때는 receipt의
  `remote_path`에서 명시적으로 `rsync` 복원해야 한다.

### 잠재 회귀 위험

- **위험**: 실행 중 추가 대규모 복사/압축으로 runtime 측정이 오염될 수 있음.
  - **감지/차단**: 이후 offload가 필요하면 다시 cell 경계에서 runner를 멈춘다.
- **위험**: 원격 copy 손상 또는 경로 혼동으로 obsolete evidence의 재현성을 잃을 수 있음.
  - **감지/차단**: local/remote receipt hash와 per-tree checksum dry-run을 재검증한 뒤 복원한다.
- **위험**: 남은 113개 cell의 로그 증가로 41GB가 다시 부족해질 수 있음.
  - **감지/차단**: cell 완료 시점마다 `df`, `progress.json`, `rows.jsonl`, failure ledger를 함께 확인한다.

## DP KMeans legality-repair replacement를 applied-plan 중복으로 오판

- **상태**: 진행중 (production 수정과 component 회귀는 통과, 새 immutable Docker stage 검증 대기)
- **환경/조건**: Docker-only LAN, KMeans, workers=3, planner DP(`mkl-cost`), campaign seed
  `2026072701`, 실패 logical cell 228.
- **적용 원칙/제약**: runtime fallback이나 candidate-space 축소 없이 planner의 최종 normalized plan을
  한 번만 transactional emission한다. DP의 국소 최적화 철학은 변경하지 않는다.
- **의사결정 근거**: runtime/oracle 제약이 아니라 pre-emission selection receipt의 소유권 분류 오류이므로,
  final-plan 선택이나 실행 경로가 아니라 DP receipt invariant를 정확한 supersession 모델로 수정한다.

### 재현 절차

실패 Docker artifact:

```text
/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/cells/228-e53caadcbd62
```

component RED/GREEN:

```bash
mvn -q -Dskip.npm -Dskip.gpu.tests -DskipTests=false \
  -Dtest=CampaignBG014DpKMeansSupersededRootReplacementRedTest test
```

### 관측 증상

- campaign은 `227/336` 성공 뒤
  `workers=3|planner=DP|workload=kmeans|profile=lan`에서 `failed=true`로 종료했다.
- harness 표면 분류는 execution-time footer가 없어서 `metric_parser`였지만, 실제 planner 예외는
  다음과 같다.

```text
java.lang.IllegalArgumentException: Applied plan order or identity is duplicated
at ordinal=36 planningHopId=775 additional=true
```

- 원본 coordinator log:
  `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/cells/228-e53caadcbd62/phases/cell-1/cold-docker-e2e/raw_coordinator.log`.
- 동일 3-worker KMeans fixture는 수정 전 planning Hop 767에서 같은 예외를 재현했다. fixture의 Hop ID는
  독립 compile마다 달라질 수 있지만 exact 발생 구조는 동일하다.

### 원인 분석

1. aggregate/explicit-root traversal은 DP의 초기 local preference를 `appliedPlans`에 기록한다.
2. exact legality 검사에서 그 occurrence가 전역 TRead/TWrite/placement 제약을 위반하면 해당 exact
   component를 `supersededPreCompletionKeys`로 명시하고 다시 연다.
3. disconnected completion은 같은 planning Hop에 대해 다른 합법 plan을 최종 component sink로 선택한다.
4. runtime plan은 이 최종 normalized result에서 한 번만 emission되지만, 기존 receipt constructor는
   planning Hop/Hop ID의 모든 재사용을 무조건 double application으로 간주했다.
5. 진단상 중복 plan object는 서로 달랐고(`uniquePlan=true`), 같은 Hop/ID만 재사용됐다. duplicate ID는
   explicit additional-root 9개가 아니라 disconnected completion 110개 중 하나였으며, 해당 occurrence는
   실제 `supersededPreCompletionKeys`에 포함돼 있었다.

따라서 이는 DP 비용 철학, additional-root 방문 판정, Docker/network/runtime 문제가 아니라
**superseded preference와 final replacement를 구분하지 못한 receipt accounting 버그**다.

### 해결 요약

- `AppliedPlanReceipt`의 plan identity와 ordinal은 계속 전역 유일하게 요구한다.
- planning Hop/Hop ID 재사용은 다음 조건을 모두 만족하는 정확히 한 쌍에만 허용한다.
  1. 앞 receipt가 pre-completion prefix에 있다.
  2. 뒤 receipt가 disconnected-completion suffix에 있고 `additionalRoot=true`다.
  3. 두 receipt는 같은 exact carrier/Hop/ID지만 서로 다른 plan object다.
  4. occurrence가 `supersededPreCompletionKeys`에 있다.
  5. 뒤 receipt를 소유하는 `DisconnectedCompletionReceipt`가 정확히 하나이며 sink root가 같은 exact key다.
- partial ID collision, 다른 carrier의 같은 ID, 세 번째 재사용, superseded 증거 없는 중복은 계속
  fail-closed한다.
- 이 과정은 선택된 candidate/placement/cost, final normalized plan, runtime lowering/emission을 변경하지 않는다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpKMeansSupersededRootReplacementRedTest.java`
- `docs/SESSION_ISSUES_2026-08-17.md`

### 검증

- 목표 3-worker LAN KMeans component 회귀: GREEN, failures/errors 0.
- 관련 DP 계약 묶음 GREEN:

```bash
mvn -q -Dskip.npm -Dskip.gpu.tests -DskipTests=false \
  -Dtest=CampaignBDpAggregateProducerContractTest,CampaignBDpRewireOwnerContractTest,CampaignBG014DpKMeansSingleWorkerMixedRefedRedTest test
```

- checkstyle validate: `mvn -q -DskipTests -Dskip.npm -Dskip.gpu.tests -Dcheckstyle.skip=false validate`, RC=0.
- package: `mvn -q -DskipTests -Dskip.npm -Dskip.gpu.tests package`, RC=0.
- 새 회귀는 실제 KMeans fixture에서 중복 Hop/ID 쌍이 존재함을 요구하고, 모든 쌍이 위의 exact
  superseded→replacement 증거를 만족하는지 확인한다.

### 잔여 이슈

- 현재 성공 227행은 이전 immutable binary의 유효한 predecessor evidence다. 수정 binary의 새 immutable
  stage에서 실패 cell 228을 Docker canary로 먼저 재실행해야 한다.
- canary 통과 뒤에도 서로 다른 immutable stage의 row를 같은 binary 결과처럼 직접 합치지 않는다. harness가
  인증하는 predecessor/continuation provenance로 성공 prefix를 검증하고, 아직 실행하지 않은 cell부터 진행한다.

### 잠재 회귀 위험

- **위험**: 일반적인 double traversal을 supersession으로 잘못 허용할 수 있다.
  - **감지/차단**: 동일 prior receipt의 두 번째 replacement, 다른 exact occurrence/ID의 부분 충돌,
    disconnected sink receipt가 0개/2개인 경우를 production invariant가 거부한다.
- **위험**: legality repair가 final normalized state를 덮어쓰지 못할 수 있다.
  - **감지/차단**: 기존 final certificate 및 disconnected normalized-state identity 검증과 Docker
    planner→lowering→runtime audit를 그대로 유지한다.
