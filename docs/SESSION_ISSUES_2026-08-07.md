# Session Issues — 2026-08-07

## WAN-light LogReg DP worker=4에서 required-output closure가 지역 최적 결정을 전역 재최적화함

- **상태**: 진행중 — 원인 수정과 hermetic 회귀는 통과했으며 새 immutable Docker canary 및 WAN-mid → LAN 실행이 남아 있음
- **환경/조건**:
  - planner: DP (`mkl-cost`)
  - workload/profile/workers: LogReg / WAN-light / 4 workers
  - frozen dataset shape: features `50000x2100`, labels `50000x1`, privacy `private-aggregate`
  - cost profile: memory `25000 MB/s`, network `125 MB/s`, worker-to-coordinator serdes `14.7 MB/s`, latency `20 ms`, seed `2026072701`
  - 기존 Docker stage: `12f701496f030ca74fd0f6d7d883e48fdec2bb021f1b745cf27b15c74c411956`, source commit `9b46d342c7273e98a52b4fc454c01247efdbc41f`
- **재현 절차**:
  - hermetic compile regression:
    `mvn -q -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014DpLogRegWanLightWorker4CostRedTest test`
  - RED 로그: `/tmp/g014-logreg-w4-red-notrace-1786089653.log`
  - focused trace: `/tmp/g014-logreg-w4-focused-trace-1786089778.log`
  - 기존 Docker raw log:
    `/home/mchoi/g014-full-results-9b46d34-26882dd-20260806-v1/cells/044-f0416edecc5c/phases/cell-1/warm-fresh-coordinator-jvm/raw_coordinator.log`
- **관측 증상**:
  - 기존 WAN-light warm execution은 worker `2/3/4`에서 각각 `18.804/16.916/24.681 sec`였고, worker=4만 역으로 느려졌다.
  - worker=4 plan은 `multiLogReg.dml:197-198`의 반복 Hessian-vector 경로를
    `FED ba+* -> FED * -> FED n* -> FED - -> FED r' -> FED ba+*`로 유지해 FED execute가 약 `2072`회였다.
    worker=2/3은 첫 `ba+*` 뒤의 elementwise/Nary/subtract를 CP로 materialize해 FED execute가 약 `835`회였다.
  - 초기 DP conflict resolver는 worker=4에서도 HOP `421 b(*)`를 `CP/LOUT`으로 선택했다. 원시 memo 비용도
    `CP/LOUT=622.988784 < FED/FOUT=729.916935`였다.
  - 그 뒤 두 번째 `refineRequiredOutputClosureDecisions` pass가 이미
    `missing=0, incompatible=0, conflicts=[]`인 map에서 전역 score가 작다는 이유만으로 HOP
    `420/422/426/1198`의 LOUT 결정을 FOUT closure로 차례로 바꿨고, 다음 iteration에서 HOP 421도 FOUT으로 전파됐다.
- **원인 분석**:
  - DP의 원래 국소 선택기(`resolveOneHopConflict`)와 후보 비용은 정상 동작했다.
  - required-output closure의 두 번째 alternative loop가 실행 불가능한 exact forest를 고치는 역할을 넘어,
    구조가 동일하고 이미 실행 가능한 decision map도 전체 forest 비용으로 hill-climb했다.
  - 이 후처리는 DP의 계약인 “현재 HOP과 직접 자식 관계에 대한 국소 비용 결정”을 우회해 사실상 별도의 전역 optimizer로 동작했다.
    worker=4에서만 closure score의 작은 임계값 차이가 발생해 반복 FED chain을 선택했다.
- **해결 요약**:
  - candidate domain, opcode 지원, runtime cap 및 비용 항은 변경하지 않았다.
  - alternative required-output closure는 `missing/incompatible` 구조를 개선하거나, 현재 closure에 실제 exact-selection conflict가 남아 있을 때만 비용/tie-break를 사용할 수 있게 했다.
  - incumbent closure가 이미 실행 가능하면 후처리가 비용만으로 DP의 국소 결정을 뒤집지 않는다. 구조 충돌을 해결하는 기존 StepLM/locked-child 경로는 그대로 유지한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpLogRegWanLightWorker4CostRedTest.java`
  - `docs/SESSION_ISSUES_2026-08-07.md`
- **검증**:
  - 수정 전 worker=4 회귀: `Tests run: 1, Failures: 1`; HOP 421이 기대 `CP` 대신 `FED/FOUT`으로 선택됨.
  - 수정 후 동일 worker=4 회귀: 성공, `/tmp/g014-logreg-w4-local-closure-green-1786090466.log`; FedPlanner `48.344155 sec`.
  - worker=2 비교 회귀: 성공, `/tmp/g014-logreg-w2-local-closure-green-1786090585.log`; FedPlanner `51.894320 sec`.
  - required-output same-output/cycle/locked-child 회귀와 StepLM full decision-map closure: 모두 성공,
    `/tmp/g014-dp-required-closure-regressions-1786090670.log`; StepLM FedPlanner `91.157743 sec`.
- **잔여 이슈**:
  - 새 source commit/JAR/immutable stage에서 WAN-light LogReg DP worker=4 Docker cold/warm canary로 실제 plan과 실행시간 개선을 검증해야 한다.
  - canary 성공 후 이전 stage 결과와 섞지 않고, 새 identity로 WAN-mid 전체를 먼저 실행한 뒤 LAN 전체를 실행해야 한다.
- **잠재 회귀 위험**:
  - 동일 missing/incompatible count 안에서 exact conflict를 실제로 해소해야 하는 closure가 지나치게 보수적으로 남을 수 있다.
    `currentClosureUnresolved` 조건, locked-child 회귀, StepLM full closure 및 최종 executable-map fail-closed 검사로 감지한다.
  - hermetic compile 선택이 wall-clock 개선을 보장하지는 않는다. 새 Docker canary의 runtime plan fingerprint, FED execute/reorg 횟수, semantic oracle 및 warm execution으로 감지한다.
- **의사결정 근거**: DP의 기존 국소 conflict resolver를 authority로 유지하고, required-output 후처리를 런타임 실행 가능성 복구 역할로 제한했다. MinST식 전역 탐색이나 runtime fallback을 DP에 추가하지 않았다.
- **적용 원칙/제약**: 후보 임의 축소 금지, 비용 후보 보존, runtime fallback 금지, TRead/TWrite `<CP,LOUT>`/`<FED,FOUT>` 유지, recompile `<CP,FOUT>` 금지, Docker-only 성능 검증.

## 기존 9b46d34 Docker campaign의 수정 중단 지점 보존

- **상태**: 진행중 — old identity는 중단·보존했고 새 identity campaign으로 교체 예정
- **환경/조건**: 결과 root `/home/mchoi/g014-full-results-9b46d34-26882dd-20260806-v1`, driver PID `1965606`, profile order WAN-light → WAN-mid → LAN.
- **재현 절차**: `ps -o pid,ppid,stat,etime,cmd -p 1965601,1965602,1965606,1965607`; cell 094 response는 `cells/094-7474c7a7bba3/response.json`에서 확인한다.
- **관측 증상**: source/JAR 수정 판단 직전에 driver를 `SIGSTOP`해 새 cell 시작을 막았다. 실행 중이던 WAN-mid KMeans MinST worker=2 cell 094는 Docker phase를 마치고 response를 썼지만, 정지된 driver가 아직 validate/terminal receipt를 기록하지 못했다.
- **원인 분석**: immutable stage는 code/JAR identity를 바꿀 수 없으므로 수정된 planner 실험을 기존 manifest에 이어 붙이면 provenance가 깨진다.
- **해결 요약**: old driver를 재개하지 않고 old response/raw bundle을 격리 보존한다. 수정 후 fresh stage/manifest/output에서 WAN-mid → LAN을 실행한다.
- **수정 파일**: production source 변경 없음; process/state 및 외부 evidence만 보존.
- **검증**: PID 1965606 상태 `T`; old campaign에 수정 JAR가 주입되지 않았고 새 cell도 시작되지 않았다.
- **잔여 이슈**: 새 Docker canary와 continuation launcher가 인증된 뒤 old stopped process를 안전하게 종료하고 preservation receipt를 남긴다.
- **잠재 회귀 위험**: old driver를 실수로 `SIGCONT`하면 old JAR cell이 추가 실행될 수 있다. 새 campaign 시작 전에 PID 상태와 manifest identity를 다시 검사하고 old process를 종료해 감지/차단한다.
- **의사결정 근거**: 서로 다른 source/JAR 결과를 한 campaign으로 합치지 않는 immutable provenance 원칙을 우선했다.
- **적용 원칙/제약**: Docker-only, exactly-once, stale result stitching 금지, profile order 유지.
