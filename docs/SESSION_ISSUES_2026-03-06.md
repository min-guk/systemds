# SESSION ISSUES (2026-03-06)

## 이슈 1: kmeans/wan_mid/w1 MinST가 과거 best(74s) 대비 현재(99s)로 회귀

- **상태**: 진행중 (원인 축소 완료, 수정 미완)
- **환경/조건**:
  - planner: `mkl-min-st-cut` (MinST)
  - workload/profile/worker: `kmeans / wan_mid / w1`
  - dataset: `P2P2D`
- **재현 절차**:
  ```bash
  RUN_ID=ralph_fix_funcop_unknowncap_only_kmeans_w1_wanmid_20260306_3 \
  SYSDS_FED_PLANNER_TRACE=1 RUN_TIMEOUT_SEC=3000 \
  bash experiments/run_LAN_docker.sh \
    --dataset P2P2D --workers 1 --conf mkl-min-st-cut --alg kmeans --net-profile wan_mid \
    --systemds-root /home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local
  ```
- **관측 증상**:
  - 현재 재현값: `Total execution time=99.374s`
  - 실행 패턴: `Federated I/O=1/110/111`, `Federated Execute=331/0`, HH 상위 `fed_ba+*`, `fed_fed_fout`
  - 과거 best(`minst_kmeans_rerun_20260227_...`): `74.442s`, `Federated Execute=0/1`(UDF 1회 중심)
- **원인 분석**:
  - 과거 best trace에서 `fcall .builtinNS m_kmeans`는 `MinST-Select=CP/LOUT`.
  - 현재 trace에서는 동일 hop이 `MinST-Select=FED/FOUT`로 뒤집힘.
  - 핵심 차이: `MinST-VertexCost`의 `unaryFED`가 과거 `~1,000,180` vs 현재 `~100`.
  - 코드 상 대응 지점: `FederatedPlanMinSTGraph.setVertexCost(...)`에서 과거 단일-worker FED 실행 패널티(대형 상수)가 제거되어 FED/FOUT 경로가 쉽게 선택됨.
  - 결론: 현재 회귀는 **function-call 레벨 FED 비용 과소추정**에 가까우며, 단순 fallback guard 변경으로는 해결되지 않음.
- **해결 요약(시도/결과)**:
  1) MinST fallback gate 재도입 시도 (`shouldEnableFederatedAlternativeFallback`)
     - 결과: 성능/플랜 변화 없음(회귀 유지)
     - 조치: 변경 롤백
  2) `FederatedCostModel`에서 unknown-dim clamp를 FunctionOp 제외로 조정 시도
     - 결과: 성능/플랜 변화 없음(회귀 유지)
     - 조치: 변경 롤백
- **수정 파일(시도 후 롤백됨)**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
- **검증**:
  - run id:
    - `ralph_fix_minst_altgate_kmeans_w1_wanmid_20260306_1_wan_mid`
    - `ralph_fix_costmodel_funcopcap_kmeans_w1_wanmid_20260306_2_wan_mid`
    - `ralph_fix_funcop_unknowncap_only_kmeans_w1_wanmid_20260306_3_wan_mid`
  - 세 run 모두 `~99.3~99.5s`, `Fed Execute=331/0`로 동일 패턴
- **잔여 이슈**:
  - function-call(`fcall`) FED 비용을 shared cost model 관점에서 재정의 필요
  - 단일-worker 특례 하드코딩 없이(DP/MinST 공통) callee 내부 반복 FED 오버헤드를 추정하는 메커니즘 필요
- **잠재 회귀 위험**:
  - function-call FED 비용 추정치 보강 시 lm/pca에도 동시 영향 가능
  - 감지 방법: `kmeans, lm`의 `fcall` 선택(CP/LOUT vs FED/FOUT), `Fed Execute(Inst,UDF)`, HH(`fed_fed_fout`) 동시 모니터링
- **의사결정 근거**:
  - **candidate-space를 닫는 worker=1 ad-hoc 대신, shared cost-model/function-call 비용 추정 보강 방향 유지**

## 이슈 2: PCA power-iteration 대체 구현 적용 후 DP/MinST가 `TRead eigen_vectors`에서 planning 실패

- **상태**: 해결
- **환경/조건**:
  - workload/profile/worker: `pca / lan / w1`
  - planner: `mkl-cost`, `mkl-min-st-cut` (재현), `mkl-fout`, `mkl-heuristic` (정상)
  - dataset: `P2P2D`
- **재현 절차**:
  ```bash
  SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_RUNTIME_TRACE=1 RUN_TIMEOUT_SEC=3000 \
  bash experiments/run_LAN_docker_matrix.sh \
    --workers-list 1 \
    --planner-confs mkl-fout,mkl-heuristic,mkl-cost,mkl-min-st-cut \
    --workloads pca --net-profiles lan \
    --run-id-prefix pca_powiter_fix2_allplanners_w1_20260306 \
    --resume 0 --continue-on-failure 1 \
    -- --systemds-root /home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local
  ```
- **관측 증상**:
  - `mkl-cost`에서 즉시 실패:
    - `No valid federated plan for hop 528 (TRead eigen_vectors) based on transient write placements`
  - `mkl-min-st-cut`도 연쇄 실패(rc=1) 발생
- **원인 분석**:
  1. power-iteration 초안이 `eigen_values/rbind`, `eigen_vectors/cbind` 동적 확장 + 분기형 TWrite를 만들어 transient read placement 결정을 불안정하게 만듦.
  2. 코드 수정 후에도 Docker 실험에서 동일 실패가 반복된 이유는, builtin script가 `scripts/builtin` 원본이 아니라 `target/classes/scripts/builtin`(jar 리소스) 기준으로 로딩되어 stale script가 계속 사용됐기 때문.
- **해결 요약**:
  - `scripts/builtin/pca.dml`에서 power-iteration 구현을 정적 shape 기반으로 변경:
    - `eigen_values = matrix(0, rows=K, cols=1)`
    - `eigen_vectors = matrix(0, rows=D, cols=K)`
    - 반복마다 `left-index` 할당(`eigen_values[k,1]`, `eigen_vectors[1:D,k]`)
    - `rbind/cbind` 제거
  - `mvn -DskipTests package` 재빌드로 `target/classes/scripts/builtin/pca.dml` 및 jar 반영
- **수정 파일**:
  - `scripts/builtin/pca.dml`
- **검증**:
  ```bash
  cd tmp/systemds-local && mvn -DskipTests package

  SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_RUNTIME_TRACE=1 RUN_TIMEOUT_SEC=3000 \
  bash experiments/run_LAN_docker_matrix.sh \
    --workers-list 1 \
    --planner-confs mkl-fout,mkl-heuristic,mkl-cost,mkl-min-st-cut \
    --workloads pca --net-profiles lan \
    --run-id-prefix pca_powiter_fix3_allplanners_w1_20260306 \
    --resume 0 --continue-on-failure 1 \
    -- --systemds-root /home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local
  ```
  - 4/4 run 완료 (planner crash 없음)
  - 모든 planner 로그에서 `m_topk_eigen_power` 실행 확인
- **잔여 이슈**:
  - 기능 안정화는 완료했지만 성능 규칙(Rule2) 기준으로 `pca/lan/w1`에서 `MinST(73.098s) > DP(71.580s)`는 남아 있음.
- **잠재 회귀 위험**:
  - builtin 스크립트 수정 후 재빌드 누락 시 동일한 “로컬 수정 반영 안 됨” 문제 재발 가능.
  - 감지 방법: 실험 전 `target/classes/scripts/builtin/pca.dml` 내용과 run-id 로그의 HH(`m_topk_eigen_power`)를 함께 확인.
- **의사결정 근거**:
  - planner 후보를 임의로 닫지 않고, 스크립트 구현 구조(동적 확장 제거)와 배포 산출물 동기화(재빌드)로 해결.

## 이슈 3: MinST trace가 raw cut만 남겨 repair 이후 최종 선택을 바로 확인하기 어려움

- **상태**: 해결
- **환경/조건**:
  - planner: `mkl-min-st-cut` (MinST)
  - tracing: `SYSDS_FED_PLANNER_TRACE=1`
  - 대상 파일: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
- **재현 절차**:
  ```bash
  cd tmp/systemds-local
  SYSDS_FED_PLANNER_TRACE=1 mvn -DskipTests compile
  ```
- **관측 증상**:
  - 기존 `MinST-Select`는 min-cut raw partition 기준 선택만 출력했다.
  - 이후 `repairTransientReadWriteSelection`, `repairCapsInconsistentSelection`, `repairFederatedInputSelection`가 exec/out을 바꿔도 trace 상에는 최종 선택이 별도 표시되지 않아 raw cut과 repaired plan을 즉시 구분하기 어려웠다.
- **원인 분석**:
  - `getOptimalPlan()`이 repair 후 `setForcedExecType/setFederatedOutput`를 적용하면서도, 로깅은 `sourceSide`만 읽는 `logSelectedDecision(...)` 하나만 호출했다.
  - 결과적으로 trace에는 “선택 전(raw cut)” 상태만 남고, 실제 적용된 exec/out과 final FType 정보가 누락됐다.
- **해결 요약**:
  - 기존 raw-cut `MinST-Select` 로그는 그대로 유지했다.
  - repair 이후 계산된 `execSelection/outSelection` 기준으로 `MinST-FinalSelect`를 추가해 `raw` vs `final`, `repaired 여부`, `derivedFedFout`, caps, `finalFType`, `cpFoutType`를 함께 남기도록 수정했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
  - `docs/SESSION_ISSUES_2026-03-06.md`
- **검증**:
  ```bash
  cd tmp/systemds-local
  mvn -DskipTests compile
  mvn -Dtest=FederatedPlanMinSTHyperedgeTest,FederatedPlanTReadWriteConsistencyTest test
  mvn -Dtest=FederatedPlanTReadWriteConsistencyTest test
  ```
  - compile 성공
  - `FederatedPlanTReadWriteConsistencyTest`: 3 tests passed
  - `FederatedPlanMinSTHyperedgeTest` 동시 실행 시 기존 실패 1건 확인:
    - `testLoopCarryUploadFallbackUsesWriterMemWhenReaderUnknown`
    - 기대값 `28.0`, 실제 `2052.0`
    - 이번 trace-only 변경과 직접 관련된 assertion은 아님
- **잔여 이슈**:
  - trace-specific assertion을 자동화한 전용 테스트는 아직 없다.
- **잠재 회귀 위험**:
  - low risk: planner 선택 로직은 바꾸지 않고 trace만 추가했다.
  - 감지 방법: trace 활성화 후 동일 hop에서 `MinST-Select`와 `MinST-FinalSelect`를 비교해 raw/final 값이 모두 출력되는지 확인.
- **의사결정 근거**:
  - planner candidate-space나 runtime fallback은 건드리지 않고, repair 이후 실제 적용 상태를 관측 가능하게 만드는 로깅 보강만 수행.

## 이슈 4: fully-unknown loop-carried TRead가 writer-side mem 대신 synthetic upload floor를 써서 MinST hyperedge 회귀 발생

- **상태**: 해결
- **환경/조건**:
  - planner: `mkl-min-st-cut` (MinST)
  - 대상 테스트: `FederatedPlanMinSTHyperedgeTest.testLoopCarryUploadFallbackUsesWriterMemWhenReaderUnknown`
  - 대상 파일: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCostEstimator.java`
- **재현 절차**:
  ```bash
  cd tmp/systemds-local
  mvn -Dtest=FederatedPlanMinSTHyperedgeTest test
  ```
- **관측 증상**:
  - loop-carried `TRANSIENTREAD`의 row/col이 모두 unknown이고 reader 쪽 output mem이 0일 때,
    writer의 `TRANSIENTWRITE` mem(3MB)로 복구해야 하는 upload edge가 `256MB floor` 기반으로 계산됐다.
  - 그 결과 assertion이 `expected 28.0, actual 2052.0`으로 실패했다.
- **원인 분석**:
  - `FederatedCostModel.getEffectiveUploadMemEstimate()`는 unknown-dim hop에 대해 shared fallback floor를 올리는 방향으로 동작한다.
  - `addLoopCarryEdgesForHop(...)`는 이 floor를 reader-side concrete estimate처럼 받아들여 writer fallback을 건너뛰었다.
  - 하지만 loop-carry의 `TRANSIENTREAD`/`TRANSIENTWRITE` 쌍에서는 matched writer mem이 실제 runtime payload에 더 가깝다.
- **해결 요약**:
  - `addLoopCarryEdgesForHop(...)`에서
    - reader의 effective output mem이 0 이하이고
    - row/col이 모두 unknown인 fully-unknown `TRead`
    인 경우에는 reader-side synthetic floor를 사용하지 않고,
    matched writer의 effective upload mem으로 fallback 하도록 수정했다.
  - 즉, shared unknown-dim fallback은 유지하되 loop-carry pair에 한해서 writer-side concrete evidence를 우선했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCostEstimator.java`
- **검증**:
  ```bash
  cd tmp/systemds-local
  mvn -Dtest=FederatedPlanMinSTHyperedgeTest,FederatedPlanTReadWriteConsistencyTest,FederatedPlanMinSTRewireTest,FederatedCostModelFallbackTest test
  mvn -DskipTests package
  ```
  - `FederatedPlanMinSTHyperedgeTest`: 10 tests passed
  - `FederatedPlanTReadWriteConsistencyTest`: 3 tests passed
  - `FederatedPlanMinSTRewireTest`: 2 tests passed
  - `FederatedCostModelFallbackTest`: 9 tests passed
  - package 성공
- **잔여 이슈**:
  - runtime planner-trace를 새 코드 기준으로 다시 수집하는 작업은 아직 별도 실행하지 않았다.
- **잠재 회귀 위험**:
  - low-to-medium: fully-unknown loop-carried `TRead`에만 writer fallback을 우선하도록 제한했기 때문에 영향 범위는 작지만,
    loop-carry 매칭이 잘못된 경우 upload 추정이 writer 쪽으로 치우칠 수 있다.
  - 감지 방법: 동일 run에서 `LoopCarry` trace와 matched `TRead/TWrite` pair의 mem/log를 함께 확인.
- **의사결정 근거**:
  - candidate-space closure나 runtime fallback 없이,
    loop-carry pair가 이미 제공하는 concrete writer-side 정보를 활용하는 방향이 shared cost-model 보정 원칙과 가장 잘 맞는다.

## 추가 smoke 검증: isolated checkout 기준 pca/lan/w1 DP vs MinST docker rerun

- **상태**: 완료
- **환경/조건**:
  - systemds root: `tmp/systemds-local/tmp/systemds-local-isolated`
  - workers/profile/workload: `1 / lan / pca`
  - planners: `mkl-min-st-cut`, `mkl-cost`
  - tracing: `SYSDS_FED_PLANNER_TRACE=1`, `SYSDS_FED_RUNTIME_TRACE=1`
- **실행 커맨드**:
  ```bash
  export COMPOSE_PROJECT_NAME=exdra_ralph_smoke_20260306
  export SYSDS_FED_PLANNER_TRACE=1
  export SYSDS_FED_RUNTIME_TRACE=1
  export RUN_TIMEOUT_SEC=2400
  export MATRIX_STATE_FILE=experiments/results/matrix_state/ralph_smoke_minst_align_20260306.tsv
  export MATRIX_HEARTBEAT_FILE=experiments/results/matrix_state/ralph_smoke_minst_align_20260306.heartbeat

  bash experiments/run_LAN_docker_matrix.sh \
    --workers-list 1 \
    --planner-confs mkl-min-st-cut,mkl-cost \
    --workloads pca \
    --net-profiles lan \
    --run-id-prefix ralph_smoke_minst_align_20260306 \
    --resume 0 \
    --continue-on-failure 0 \
    --skip-docker-build \
    -- --systemds-root /home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local/tmp/systemds-local-isolated
  ```
- **결과 요약**:
  - 2/2 run 성공
  - matrix rule check: `violations=0`, `warnings=0`
  - `MinST-FinalSelect` trace가 coordinator log에 실제 출력됨
  - exec time:
    - `mkl-min-st-cut`: `118.091 sec`
    - `mkl-cost`: `117.006 sec`
- **대표 로그 경로**:
  - state: `experiments/results/matrix_state/ralph_smoke_minst_align_20260306.tsv`
  - MinST log:
    `experiments/results/fed1/mkl-min-st-cut/pca_dataset-P2P2D_coordinator_mkl-min-st-cut_ralph_smoke_minst_align_20260306_20260306_114137_1409892_w1_mkl-min-st-cut_pca_lan_lan.log`
  - DP log:
    `experiments/results/fed1/mkl-cost/pca_dataset-P2P2D_coordinator_mkl-cost_ralph_smoke_minst_align_20260306_20260306_114137_1409892_w1_mkl-cost_pca_lan_lan.log`

## Kmeans shared DAG/runtime mismatch: orphan `fed_fout` skip was ineffective because `addNode(fout)` happened first

- **상태**: 해결
- **환경/조건**:
  - planner/runtime compile path shared by DP/MinST
  - workload focus: `kmeans`
  - representative logs:
    - DP: `experiments/results/fed1/mkl-cost/kmeans_dataset-P2P2D_coordinator_mkl-cost_kmlr_funcplaceholder_w1_20260306_20260306_103940_1238845_w1_mkl-cost_kmeans_wan_mid_wan_mid.log`
    - MinST: `experiments/results/fed1/mkl-min-st-cut/kmeans_dataset-P2P2D_coordinator_mkl-min-st-cut_kmlr_funcplaceholder_w1_20260306_20260306_103940_1238845_w1_mkl-min-st-cut_kmeans_wan_mid_wan_mid.log`
- **재현 절차**:
  ```bash
  cd tmp/systemds-local
  rg -n "selected=FED/LOUT|selected=CP/LOUT|fed_fout" \
    ../experiments/results/fed1/mkl-min-st-cut/kmeans_*kmlr_funcplaceholder*wan_mid*.log \
    ../experiments/results/inst_stats/kmeans_*kmlr_funcplaceholder*wan_mid*_run1.csv
  ```
- **관측 증상**:
  - MinST trace에서는 `hop=424`가 `selected=FED/LOUT`, `hop=427/457/458/477`가 `CP/LOUT`로 정리되는데,
    inst-stats에서는 같은 logical path에 `fed_fout`가 반복 실행됐다.
  - source inspection 결과, `Dag.insertFoutMaterializeLops(...)`에서 no-consumer skip 검사보다 먼저
    `addNode(fout)`를 호출하고 있었다.
  - `Dag.getJobs(...)`는 수정 후 `nodes`를 다시 linearize하기 때문에, local list에서 skip해도 orphan lop이 최종 DAG에 남을 수 있었다.
- **원인 분석**:
  - planner-side registry prune은 있었지만, compile-time lop insertion에서 `FederatedFoutMaterialize`가 이미 global `nodes`에 등록된 뒤였다.
  - 또한 keep/skip 판단이 local `consumers` list만 봐서, `FederatedRefed` 우회 rewiring으로 생긴 실제 downstream output은 놓칠 수 있었다.
- **해결 요약**:
  - `addNode(fout)`/`inserted=true`를 실제 keep decision 이후로 이동했다.
  - keep/skip 판단은 `consumers.isEmpty()` 대신 `fout.getOutputs().isEmpty()` 기준으로 바꿨다.
  - `FederatedRefed` 제거 경로에서 `fout`에 직접 연결된 downstream outputs도 `consumers`에 보강해,
    후속 정렬/삽입 로직이 실제 소비자를 반영하도록 했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/lops/compile/Dag.java`
- **검증**:
  ```bash
  cd tmp/systemds-local
  mvn -DskipTests compile
  ```
  - build 성공
  - modified file diagnostics 0 errors
- **잔여 이슈**:
  - 이 shared compile-time fix와 별개로, DP는 centroid loop `TWrite/TRead C/C_new`에서 실제로 FOUT를 선택하는 planner-side bias가 남아 있다.
- **잠재 회귀 위험**:
  - low-to-medium: orphan skip 기준이 `fout.getOutputs()`로 바뀌었기 때문에, 진짜 소비자 연결을 놓치는 경로가 있으면 materialize가 과소삽입될 수 있다.
  - 감지 방법: trace에서 `CP->FOUT insert skip: ... noFedConsumers=true`가 찍힌 hop의 runtime inst-stats에 `fed_fout`가 남는지 재확인.
- **의사결정 근거**:
  - runtime fallback/특례 없이 DAG compile 단계의 실제 삽입 조건을 planner-selected final consumer graph와 일치시키는 수정이다.

## 이슈 5: MinST graph repair 이후에도 exact graph hop의 post-resolve stale state가 살아남아 `pca/lan`에서 `fed_fed_refed` 회귀를 유발

- **상태**: 해결
- **환경/조건**:
  - planner: `mkl-min-st-cut` (MinST)
  - workload/profile/worker: `pca / lan / w1`
  - systemds root:
    - main tree: `tmp/systemds-local`
    - isolated runtime tree: `tmp/systemds-local/tmp/systemds-local-isolated`
- **재현 절차**:
  ```bash
  export COMPOSE_PROJECT_NAME=exdra_ralph_trace2_20260306
  export SYSDS_FED_PLANNER_TRACE=1
  export SYSDS_FED_RUNTIME_TRACE=1
  export RUN_TIMEOUT_SEC=2400

  bash experiments/run_LAN_docker.sh \
    --dataset P2P2D \
    --workers 1 \
    --conf mkl-min-st-cut \
    --alg pca \
    --net-profile lan \
    --systemds-root /home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local/tmp/systemds-local-isolated \
    --run-id ralph_trace2_pca_20260306
  ```
- **관측 증상**:
  - failing trace run:
    - `experiments/results/fed1/mkl-min-st-cut/pca_dataset-P2P2D_coordinator_mkl-min-st-cut_ralph_trace2_pca_20260306_20260306_135802_1672866_w1_mkl-min-st-cut_pca_lan_lan.log`
  - `MinST-FinalSelect`는 graph repair 시점에
    - `hop59 (rix) -> CP/LOUT`
    - `hop200 (TWrite Components) -> CP/LOUT`
    - `hop75 (ba(+*)) -> FED/LOUT`
    를 보여줬지만,
  - 뒤의 `[Optimal Federated Plan]` table은 다시
    - `59 -> CP/FOUT`
    - `75 -> FED/LOUT`
    - `200 -> FED/FOUT`
    로 어긋났다.
  - runtime recompile도 그대로 stale state를 복제했다.
    - `hopID=231 TWrite Components -> FED/FOUT`
    - `hopID=232 rix -> CP/FOUT`
    - `hopID=245 XReduced ba(+*) -> FED/LOUT`
  - 결과:
    - `Total execution time: 177.010 sec.`
    - `fed_fed_refed 57.846` with count `2`
- **원인 분석**:
  - 문제는 raw-cut repair 미적용이 아니라, **graph repair 후 post-resolve 단계에서 exact graph hop에 stale registry/promoted state가 다시 남는 것**이었다.
  - `FederatedRefedPolicy.registerFromProgram(...)` / `registerFromFunction(...)` 이후에도
    - stale CPFOUT/materialize anchor,
    - stale federated `TRANSIENTWRITE` selection,
    - unsatisfied FED/FOUT placement
    이 exact graph hop 객체에 남아 runtime validation / recompile 경로로 전파됐다.
  - 특히 `hop59/75/200` 조합이 그대로 `231/232/245` recompile clone으로 이어져 `fed_fed_refed`를 만들었다.
- **해결 요약**:
  1. `FederatedPlanMinSTGraph`에 `repairSelectionFixpoint(...)`를 추가해 graph-level repair를 안정화했다.
  2. `FederatedRefedPolicy`에
     - `repairResolvedHopSelections(...)`
     - `demoteStaleTransientWriteFederatedSelections(...)`
     - `finalizeRegisteredStatementBlock(...)`
     를 추가해 post-resolve prune/demotion/finalize를 exact-hop 기준으로 반복 적용할 수 있게 했다.
  3. `FederatedPlanMinSTCut`에서
     - `registerFromProgram(...)`
     - `registerFromFunction(...)`
     직후 `repairResolvedHopSelections(collectGraphHops(graph), ...)`를 호출하도록 바꿨다.
  4. `TransTableRewireUtils.resolveTransReadChildren(DataOp, ...)` + `FederatedPlanMinSTRewire`를 통해
     current-scope dominating `TWrite`가 있으면 stale outer mapping 대신 그 write를 우선하게 했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCut.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/TransTableRewireUtils.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**:
  ```bash
  cd tmp/systemds-local
  mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest#testPruneInvalidCpfoutAnchorsRemovesStaleMaterializeForLocalTransientWrite+testDemoteStaleTransientWriteFederatedSelectionWhenNoFedNeedRemains+testDemoteStaleTransientWriteKeepsLiveFedTransientReadConsumer+testMinSTRepairFixpointPropagatesFedInputDemotionToLinkedTransientRead+testResolveTransReadChildrenPrefersDominatingTransientWriteOverStaleOuterMapping,FederatedPlanTReadWriteConsistencyTest,FederatedPlanMinSTRewireTest,FederatedPlanMinSTHyperedgeTest,FederatedCostModelFallbackTest test

  cd tmp/systemds-local/tmp/systemds-local-isolated
  mvn -q -DskipTests package
  ```
  - targeted tests pass
  - isolated package pass
  - fixed trace run:
    - `experiments/results/fed1/mkl-min-st-cut/pca_dataset-P2P2D_coordinator_mkl-min-st-cut_ralph_postresolve_pca_20260306_20260306_142527_1710847_w1_mkl-min-st-cut_pca_lan_lan.log`
    - `[Optimal Federated Plan]`에서
      - `59 -> CP/LOUT`
      - `75 -> CP/LOUT`
      - `200 -> CP/LOUT`
    - `RecompileNewHop 231/232/245` 모두 `CP/LOUT`
    - `Total execution time: 124.866 sec.`
    - corresponding inst-stats에 `fed_fed_refed` 없음
  - no-trace smoke:
    - `pca/lan`: `116.601 sec.`
    - `lm/lan`: `64.614 sec.`
    - `pca/wan_light`: `120.744 sec.`
    - `pca/wan_mid`: `127.079 sec.`
    - `lm/wan_mid`: `70.283 sec.`
    - 위 5개 모두 corresponding inst-stats에 `fed_fed_refed` 없음
- **잔여 이슈**:
  - post-fix 상태를 기준으로 predicted-vs-actual join artifact를 다시 생성하는 작업은 아직 남아 있다.
  - DP shared cost/memory mismatch(`FunctionOp`, unknown-dim mem)는 별도 축으로 계속 정리해야 한다.
- **잠재 회귀 위험**:
  - medium: post-resolve cleanup이 exact graph hop 기준으로 동작하므로, 다른 workload에서 stale registry state가 실제로 필요한 경우 과도 demotion 위험이 있다.
  - 감지 방법:
    - trace에서 `[Optimal Federated Plan]`과 `RecompileNewHop`의 exec/fedOut 불일치 재발 여부
    - inst-stats에서 `fed_fed_refed` 재출현 여부
    - `TRANSIENTWRITE`가 실제 live federated `TRANSIENTREAD` consumer를 가진 경우 `Refed-TWrite-Review keep` 로그 유지 여부
- **의사결정 근거**:
  - candidate-space closure나 runtime fallback 대신,
    **post-resolve exact graph state와 runtime validation/recompile state를 일치시키는 shared repair/prune 경로**를 추가한 수정이다.

## 이슈 6: DP shared `FunctionOp` baseline 과소추정 + unknown-dim upload 재팽창이 workers=1 canary 정렬을 흔듦

- **상태**: 부분 해결
- **환경/조건**:
  - shared planner cost/memory path (`mkl-cost`, `mkl-min-st-cut` 공통 영향)
  - 중점 workload/profile/worker:
    - `pca,lm / lan,wan_light,wan_mid / w1`
  - systemds root:
    - main tree: `tmp/systemds-local`
    - isolated runtime tree: `tmp/systemds-local/tmp/systemds-local-isolated`
- **관측 증상**:
  - `FunctionOp` placeholder(`m_pca`, `m_lmCG`)가 generic baseline에 가깝게 가격이 매겨져 boundary delta에 과민했다.
  - unknown-dim output이 sentinel clamp를 거친 뒤에도 upload path에서 다시 floor로 재팽창할 수 있었다.
  - post-resolve canary에서는 PCA `lan > wan_light` 규칙 위반이 남아 있었다.
- **원인 분석**:
  1. `FederatedCostModel.computeOpCost(...)`가 DML `FunctionOp`에 대해 generic placeholder baseline만 사용해 callee 내부 work를 거의 반영하지 못했다.
  2. `FederatedCostModel.getEffectiveUploadMemEstimate(...)`는 unknown-dim upload floor를 적용할 때 raw-zero fallback과 sentinel-clamped output을 구분하지 않아, 이미 descendant/input evidence로 줄여 둔 payload를 다시 키울 수 있었다.
- **해결 요약**:
  1. `computeOpCost(...)`에 DML `FunctionOp` shared compute floor를 추가했다.
     - effective input/output mem을 먼저 구하고,
     - logical cell count 기반 `estimateDmlFunctionOpComputeFloor(...)`를 적용해 generic placeholder baseline보다 낮아지지 않게 했다.
  2. `getEffectiveUploadMemEstimate(...)`에서 unknown-dim provenance를 분리했다.
     - `rawOutputMemEstimate <= 0.0`인 진짜 raw-zero fallback만 one-axis floor를 유지
     - sentinel-clamped output은 upload path에서 재팽창 금지
  3. 회귀 테스트를 추가했다.
     - sentinel-clamped upload non-reinflate
     - raw-zero one-axis floor 유지
     - DML `FunctionOp` shared floor
     - DP estimator가 shared floor 소비
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedCostModelFallbackTest.java`
- **검증**:
  ```bash
  cd tmp/systemds-local
  mvn -q -Dtest=FederatedCostModelFallbackTest,FederatedPlanCostEnumeratorTest,FederatedPlanMinSTHyperedgeTest,FederatedPlanMinSTRewireTest,FederatedPlanTReadWriteConsistencyTest,FederatedPlannerFallbackIntegrationTest#testPruneInvalidCpfoutAnchorsRemovesStaleMaterializeForLocalTransientWrite+testDemoteStaleTransientWriteFederatedSelectionWhenNoFedNeedRemains+testDemoteStaleTransientWriteKeepsLiveFedTransientReadConsumer+testMinSTRepairFixpointPropagatesFedInputDemotionToLinkedTransientRead+testResolveTransReadChildrenPrefersDominatingTransientWriteOverStaleOuterMapping test
  mvn -q -DskipTests compile

  cd tmp/systemds-local/tmp/systemds-local-isolated
  mvn -q -DskipTests package
  ```
  - fresh targeted tests/build/package pass
  - `FederatedCostModelFallbackTest`: **13/13 pass**
  - `FederatedPlanCostEnumeratorTest`: **22/22 pass**
  - `FederatedPlanMinSTHyperedgeTest`: **10/10 pass**
  - `FederatedPlanMinSTRewireTest`: **2/2 pass**
  - `FederatedPlanTReadWriteConsistencyTest`: **3/3 pass**
  - targeted `FederatedPlannerFallbackIntegrationTest` subset: **5/5 pass**
  - 4-cell smoke `ralph_dpfix_pca_lanwan_20260306`:
    - `pca/lan`: MinST `124.368s`, DP `120.983s`
    - `pca/wan_light`: MinST `121.118s`, DP `118.012s`
    - matrix rule check `violations=0`, `warnings=0`
    - matching inst-stats에 `fed_fed_refed` 없음
  - full canary `ralph_dpfix_canary_w1_20260306`:
    - `12/12 success`
    - matrix rule check `violations=0`, `warnings=0`
    - matching `run1.csv` inst-stats grep에서 `fed_fed_refed` 없음
  - trace pair `ralph_dpfix_trace_pca_lan_20260306`:
    - MinST `119.528s`, DP `118.338s`
    - rule check `violations=0`, `warnings=0`
    - helper join:
      - MinST `oracle_hops=49`, `minst_final_hops=88`, `full_joins=27`
      - DP `oracle_hops=53`, `hopid_hops=53`, `full_joins=18`
- **잔여 이슈**:
  - 이 wave는 shared floor/clamp mismatch를 줄였지만,
    `kmeans/wan_mid/w1`의 function-call FED underpricing까지 완전히 해결한 것은 아니다.
  - fresh targeted DP rerun `ralph_dp5dedup_dp_trace_kmeans_wanmid_20260307`에서
    `TWrite X_samples`의 transient LOUT delta는 크게 낮아졌지만
    (dp4 `~31k-41k` → dp5 `~3.27k-3.45k`),
    최종 runtime / instruction mix는 사실상 동일하게 남았다
    (`211.739s`, `Federated Execute=364`, `fed_fed_fout=57`, `fed_fed_refed=100`).
    즉 현재 active regression은 transient-write dedup만으로는 닫히지 않는다.
  - 같은 dp5 log의 `[Oracle]` lines를 확인하면 key hop `180/181/185/279/282`는
    oracle 차원에서 `CP/LOUT`와 `FED/FOUT`가 모두 보인다.
    따라서 현재 kmeans/wan_mid/w1의 핵심 문제는 oracle이 LOUT를 닫아서 생긴 hard gate라기보다,
    **열려 있는 후보들 사이의 cost-choice mismatch**에 더 가깝다.
  - 그 다음 가설이었던 dp7 (`ralph_dp7decisionaware_dp_trace_kmeans_wanmid_20260307`)는
    `computeParentVariantSwitchDelta(...)`가 sibling-output-compatible parent variant만 보게 만들었지만,
    fresh runtime에서 오히려 **실패 가설**로 판정됐다.
    - runtime: `311.219s`
    - `Federated Execute=314`
    - `fed_fed_refed=100` (count는 유지)
    - `fed_fed_refed time=121.738s` (dp6 `8.521s` 대비 대폭 악화)
    - `Fed Put Bytes=2,603,966,864` (dp6 `84,831,016` 대비 폭증)
    즉 `258`의 local oscillation만 없애는 것으로는 충분하지 않고,
    실제 dominant bad path는 여전히 `181/218` transient chain과 loop-weighted FED parents `995/1031` 쪽에 남아 있다.
  - 따라서 dp7 decision-aware parent-variant patch는 **revert**했고,
    현재 baseline은 dp6까지의 개선만 유지한 상태다.
  - MinST trace는 여전히 `[HopID]` cost/memory line이 부족해 final-stage placement join은 가능하지만 predicted cost/memory join은 partial이다.
  - LM에는 planner 외 runtime/harness overhead 축이 남아 있다.
- **잠재 회귀 위험**:
  - medium:
    - DML `FunctionOp` floor가 과도하면 일부 workload에서 CP bias가 커질 수 있다.
    - unknown-dim upload provenance split이 raw-zero fallback까지 막아버리면 반대로 CP/FOUT가 과소비용화될 수 있다.
  - 감지 방법:
    - `FunctionOp` trace self/total cost가 generic baseline 아래로 다시 내려가는지 확인
    - unknown-dim hop에서 `effectiveOutputMem` 대비 `uploadMem`가 sentinel-clamped case에서 다시 커지는지 확인
    - `181/218/995/1031` trace에서 compatible CP parent variant 부재인지, clone/original id mismatch인지 분리 로깅으로 확인
    - matrix rule check와 `fed_fed_refed` grep을 canary gate로 유지
- **의사결정 근거**:
  - planner-specific hack이나 candidate closure 대신,
    **shared cost/memory model**에서 underpricing/reinflate 원인을 줄이는 DP-first 수정으로 진행했다.

## 이슈 6: runtime refed/fout reuse key는 `anchorMapId`보다 layout semantics를 봐야 하지만, 현재 hot `fed_fout` 반복은 anchor-id mismatch만으로 설명되지 않는다

- **배경**
  - runtime reuse cache는 원래 `inputSig + mutationVersion + rows/cols/nnz + anchorMapId + outType` 축으로 keyed 되어 있었다.
  - planner/oracle 쪽 anchor는 실제로는 object id가 아니라
    **worker pool + ranges + FType** 의미를 담는 semantic layout이다.
  - 그래서 runtime에서도 `anchorMapId` 대신 layout signature를 key로 보는 게 맞다.

- **적용한 수정**
  - `src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationUtils.java`
    - `RefedReuseKey`를 `anchorMapId` 대신 `layoutSig` 중심으로 변경
    - `deriveFedLayoutSignature(...)`
    - `deriveMaterializedLayoutSignature(...)`
    - `buildAnchorMapFromKey(...)`가 row/col/full ranges를 복원하도록 보강
  - `src/main/java/org/apache/sysds/runtime/instructions/fed/FEDRefedInstruction.java`
    - cache key debug에 `layoutSig` 반영
  - `src/main/java/org/apache/sysds/runtime/instructions/fed/FEDFoutInstruction.java`
    - cache key debug에 `inputKey / uid / mut / layoutSig / outType` 반영
  - 새 테스트:
    - `src/test/java/org/apache/sysds/test/component/federated/FederationUtilsRefedReuseLayoutTest.java`

- **검증**
  - main tree:
    - `FederationUtilsRefedReuseLayoutTest`
    - `FEDFoutInstructionBroadcastFallbackTest`
    - `compile`
    - 모두 pass
  - isolated rerun:
    - `ralph_rtlayout2_kmeans_wanmid_20260307`
    - `152.955s`
    - `Federated Execute=114`
    - `fed_fed_refed=0`
    - `fed_fed_fout=57`
    - `Fed Put Bytes=83,947,664`
    - `violations=0`, `warnings=0`
  - 즉 layout-signature patch는 **비회귀 / safe**다.

- **fresh debug evidence**
  - run: `ralph_rtdebug2_kmeans_wanmid_20260307`
  - hot repeated upload:
    - `FED°fed_fout°_mVar321°X°_mVar307°FULL`
    - 총 `54`회
  - 이 54회는 모두:
    - same operand: `_mVar321`
    - same anchor: `X`
    - same layoutSig: `worker1/...;|0,0,50,50000;|FULL`
    - same dims/nnz: `50x50000`, `50000`
    - same mutationVersion: `1`
  - 하지만 동시에:
    - `inputKey`는 매번 다름
      - `temp207_1089`, `temp207_1106`, `temp207_1123`, ...
    - `uid`도 매번 다름
  - 즉 current miss는 **anchorMapId churn 때문이 아니라 input identity churn**이 지배적이다.

- **중요한 해석**
  - 이걸 근거로 곧바로 `inputKey` 대신 operand name만 쓰는 patch를 넣으면 위험하다.
  - `_mVar321`은 hot `415 -> 418 -> 419` chain 안의 local FULL upload operand인데,
    DML/HOP 대응을 보면 오히려 **실제로 바뀌는 값일 가능성이 높다**.
    - planner trace:
      - `HopID 415 b(/)` / `418 r(r')` / `419 ba(+*)`
      - 즉 `_mVar321`은 `t(P)` 쪽 `50x50000` FULL upload와 맞는다.
    - builtin DML:
      - `P = D <= minD` / `P = P / rowSums(P)` / `C_new = (t(P) %*% X) / t(P_denom)` (`scripts/builtin/kmeans.dml:147-155`)
      - `C_old = C; C = C_new` (`scripts/builtin/kmeans.dml:165-166`)
    - 따라서 `P`와 `t(P)`는 while-loop 안에서 centroid `C`에 의해 계속 변하는 값이다.
    - runtime debug에서도 같은 54회 구간에서 downstream `aggBinary ba+* out=_mVar324`의 `nnz`가 `104103 -> 104336`으로 변한다.
  - 즉 현재 hot `_mVar321`에 대해서는
    - “같은 logical value인데 temp/uid만 바뀐다”보다
    - **“같은 operand slot이지만 실제 값이 매 iteration 바뀐다”** 쪽이 더 강하다.
  - 따라서 지금은:
    - **layout-signature fix는 유지**
    - 그러나 **input identity 완화는 보류**
    - `_mVar321` hot chain은 runtime reuse 후보에서 사실상 제외하고,
      planner/cost-choice가 이 repeated upload를 덜 선택하도록 줄이는 쪽을 우선 본다.

- **의미**
  - runtime lane에서 anchor semantics mismatch 하나는 바로잡았지만,
    현재 `kmeans/wan_mid/w1`의 남은 hot upload는 그것만으로 해결되지 않는다.
  - fresh DP trace를 보면 문제 초점도 planner 쪽으로 더 좁혀진다.
    - `hop 418 (r(r'))` 단독 비교는 `bestLOUT=28700.438750`, `bestFOUT=29115.629774`로 **로컬이 약간 더 싸다**.
    - 그런데 부모 `hop 419 (ba(+*))`에서는 `418`을 federated로 유지한 child 조합이 `82657.941330`으로,
      local-child 조합 `87124.225115`보다 싸게 계산된다.
    - 즉 현재 남은 hot upload는 “runtime cache key가 틀렸다”보다
      **planner가 `415 -> 418 -> 419` handoff를 실제 runtime보다 싸게 보고 있다** 쪽이 더 강하다.
  - 따라서 다음 priority는
    1. hot `_mVar321` chain은 planner/cost-choice 쪽으로 되돌려 축소
    2. runtime reuse 확장은 truly loop-invariant한 다른 chain이 있을 때만 제한적으로 검토
    3. 필요하면 `fed_fout`/`fed_refed` miss reason을 runtime debug에 더 세분화
    으로 가야 한다.

- **2026-03-07 self-cost trace audit: remaining hot DP issue is concentrated on `hop 419`, not on `415/418` generic FED self-cost.**
  - run:
    - `ralph_dptrace_selfcost_kmeans_wanmid_20260307_20260307_090044_3138785`
  - planner trace:
    - `hop 418 (r(r'))`
      - `selfModel[base=1.927098, fedCompute=1.927098, fedOverhead=100.113000, singleWorkerPenalty=0.000000]`
      - `bestLOUT=28643.378750`, `bestFOUT=29058.569774`
    - `hop 419 (ba(+*))`
      - `selfModel[base=4889.475566, fedCompute=4889.475566, fedOverhead=100.113000, singleWorkerPenalty=0.000000]`
      - selected candidate still prefers `FED/LOUT`
  - runtime inst-stats for the same run:
    - `hop 415`: `/` `54` calls, `218.651 ms`
    - `hop 418`: `r'` `54` calls, `226.816 ms`
    - `hop 419`:
      - `fed_fed_fout` `54` calls, `30843.011 ms`
      - `fed_ba+*` `54` calls, `32821.972 ms`
  - MinST comparison (`ralph_trace2_kmeans_wanmid_20260307_20260307_023219_2529631`):
    - `hop 418` final `CP/LOUT`
    - `hop 419` final `CP/LOUT`
    - MinST `hop 419` plain `ba+*` totals only `598.006 ms`
  - current interpretation:
    - broad “FED self-cost heuristic for all nearby hops is too cheap” is too coarse
    - the active remaining mismatch is much more specific:
      - **DP keeps `hop 419` on a repeated `fed_fed_fout -> fed_ba+* -> LOUT` path that MinST avoids**
      - the next patch lane should target how DP values that `418(FOUT) -> 419(FED/LOUT)` chain, rather than weakening runtime reuse safety or over-generalizing the compute-cost issue for `415/418`

- **2026-03-07 dp11 -> dp12 update: the decisive DP bug was the FOUT-only federated-input `TRead X` CP localization term, and a narrow amortization patch fixes the hot `kmeans/wan_mid/w1` chain.**
  - `dp11` child-breakdown established:
    - `hop 419 (ba(+*))`
    - child `418` contribution: `fOutToFED=943.058251`
    - child `354 = TRead X` contribution: `cum=16111.785016`, `toCP=37422.525783`, `toFED=32223.575830`
    - so the dominant bias was not 418 upload anymore; it was the **FOUT-only `TRead X` CP download term**
  - architect read-only review agreed:
    - next smallest valid patch is **not** generic FED self-cost tuning
    - it is a narrow fix on the FOUT-only `TRANSIENTREAD -> CP` boundary term
  - implemented patch:
    - `FederatedPlannerDpCostEstimator`
      - added `computeFoutToCpDownloadShareForParent(...)`
      - amortizes `FOUT -> CP` download share across parents only for
        - federated input data ops, or
        - fed-init `TRANSIENTREAD`
      - and only when the child remains `ExecType.FED`
    - `FederatedPlannerDpCostEnumerator`
      - mirrored that logic in output-decision delta computation
    - `FederatedPlannerDpFedCostBased`
      - mirrored that logic in rewrite/conflict reconciliation
    - `FederatedPlannerFallbackIntegrationTest`
      - added regression tests for direct forwarding-share and child-cost-buffer amortization
  - fresh verification:
    - targeted tests + `compile`: pass
    - isolated tree `package`: pass
  - fresh rerun:
    - `ralph_dp12_treadshare_trace_kmeans_wanmid_20260307_20260307_110452_3267113`
    - planner trace now shows:
      - `hop 419 bestLOUT={exec=CP,fType=ROW,cost=62542.753298}`
      - `hop 418 chosen=LOUT`
      - rewrite `419=CP/LOUT`, `418=CP/LOUT`
      - child `354` CP-side term drops to `18711.262891`
    - runtime improves from the prior `~155.6s / FedExec=114 / FedPutBytes=83947664`
      to:
      - `Total execution time = 86.986 sec`
      - `Federated Execute = 2`
      - `Fed Put Bytes = 840152`
      - `fed_fed_fout = 1 call`
      - `fed_ba+* = 1 call`
      - rule check `violations=0`, `warnings=0`
  - interpretation:
    - the remaining DP hot issue was **not** primarily generic FED self-cost underpricing
    - it was a **missing reuse/amortization model for loop-invariant federated-input `TRead` values shared by multiple CP consumers**
    - this patch closes the active `419` chain without runtime fallback, legality relaxation, or candidate-space closure

- **2026-03-08 workers=`1` post-`dp12` expansion summary: the old DP `kmeans` hot chain stays closed, but the residual anomaly set is now smaller and shifted elsewhere.**
  - artifacts:
    - state: `experiments/results/matrix_state/ralph_w1_expand_postdp12_20260307.tsv`
    - summary:
      - `experiments/results/workers/runtime/ralph_w1_expand_postdp12_runtime_summary_20260308.csv`
      - `experiments/results/workers/runtime/ralph_w1_expand_postdp12_runtime_summary_20260308.md`
    - rules:
      - `experiments/results/matrix_rules/matrix_rules_ralph_w1_expand_postdp12_20260307_20260307_110833_3272171.txt`
  - scope:
    - workers=`1`
    - planners=`mkl-min-st-cut,mkl-cost`
    - workloads=`kmeans,logreg,l2svm`
    - profiles=`lan,wan_light,wan_mid`
  - outcome:
    - `18/18` success
    - rule-check:
      - `RULE3_FAIL: lan>wan_light worker=1 conf=mkl-min-st-cut workload=l2svm_P2P2D lan=70.849000 wan_light=66.543000`
      - `RULE3_FAIL: lan>wan_light worker=1 conf=mkl-cost workload=logreg_P2P2D lan=75.742000 wan_light=71.268000`
      - `RULE3_WARN: lan>wan_light worker=1 conf=mkl-cost workload=kmeans_P2P2D lan=78.550000 wan_light=75.943000`
  - important per-workload takeaways:
    - `kmeans`:
      - DP remains in the new low-FED regime on all three profiles:
        - `Federated Execute=2`
        - `fed_fed_refed=0`
        - `fed_fed_fout=1`
        - runtimes `78.550 / 75.943 / 85.435 sec` for `lan / wan_light / wan_mid`
      - MinST also stays stable:
        - `Federated Execute=3`
        - `fed_fed_refed=0`
        - `fed_fed_fout=1`
        - runtimes `75.252 / 75.351 / 85.263 sec`
    - `l2svm`:
      - both planners are fully local in this sweep (`Federated Execute=0`)
      - the remaining anomaly is pure network monotonicity (`lan > wan_light`) under MinST
    - `logreg`:
      - DP stays fully local on all profiles (`Federated Execute=0`)
      - MinST still emits a repeated FED path on every profile:
        - `Federated Execute=61`
        - `fed_fed_refed=30`
        - `fed_ba+*=30`
        - runtimes `70.190 / 76.259 / 101.097 sec`
  - current issue split after this sweep:
    1. the old DP `kmeans/wan_mid/w1` bug is closed
    2. the remaining workers=`1` anomalies are now
       - network-monotonicity failures for `logreg` DP and `l2svm` MinST
       - a weaker `kmeans` DP warning
       - persistent MinST `logreg` FED/refed activity outside the old DP hot path

- **2026-03-08 workers>`1` subset verification is now running in parallel and is the active fresh-evidence branch.**
  - launched prefixes:
    - `ralph_w24_pca_subset_20260308`
    - `ralph_w24_kmeans_subset_20260308`
    - `ralph_w24_lm_subset_20260308`
  - isolation:
    - unique `COMPOSE_PROJECT_NAME`
    - unique `MATRIX_STATE_FILE`
    - unique `MATRIX_HEARTBEAT_FILE`
  - target subset:
    - `pca`: `lan,wan_mid`
    - `kmeans`: `wan_mid`
    - `lm`: `lan,wan_light`
    - planners=`mkl-min-st-cut,mkl-cost`
    - workers=`2,4`
  - immediate purpose:
    - confirm that the `dp12` workers=`1` fix remains stable under higher worker counts
    - determine whether the residual anomalies are measurement/runtime-noise effects or worker-count-sensitive planner/runtime mismatches.

- **2026-03-08 workers>`1` subset results: `lm` and `pca` are clean; the active remaining multi-worker issue is now `kmeans` under MinST on `wan_mid`.**
  - summary artifacts:
    - `experiments/results/workers/runtime/ralph_w24_lm_subset_runtime_summary_20260308.{csv,md}`
    - `experiments/results/workers/runtime/ralph_w24_pca_subset_runtime_summary_20260308.{csv,md}`
    - `experiments/results/workers/runtime/ralph_w24_kmeans_subset_runtime_summary_20260308.{csv,md}`
  - `lm` subset:
    - state `ralph_w24_lm_subset_20260308.tsv`, rules `0/0`, `8/8` success
    - both planners stay in the same low-FED regime:
      - workers=`2`: DP `23.131/23.121`, MinST `21.646/24.376` (`lan/wan_light`)
      - workers=`4`: DP `11.127/11.917`, MinST `8.433/11.607`
      - `Federated Execute=3`, `fed_fed_refed=1`, `fed_ba+*=1`
  - `pca` subset:
    - state `ralph_w24_pca_subset_20260308.tsv`, rules `0/0`, `8/8` success
    - both planners stay on the same small stable FED path:
      - DP `FedExec=6`, MinST `FedExec=7`
      - both `fed_fed_refed=2`, `Fed Put Bytes=33904`
      - runtimes remain ordered and monotone across `workers=2,4`
  - `kmeans` subset:
    - state `ralph_w24_kmeans_subset_20260308.tsv`, `4/4` success
    - rule check warns only on MinST scaling:
      - `RULE4_WARN ... mkl-min-st-cut ... kmeans_P2P2D ... wan_mid w2=251.856000 w4=269.302000 (fedExecInst 599->221, put 166->330, get 221->331, putBytesMat 46673136->112815616)`
    - DP remains healthy:
      - workers=`2`: `44.582 sec`, `FedExec=3`, `Fed Put Bytes=1275120`, `fed_fed_fout=2`, `fed_ba+*=2`
      - workers=`4`: `30.764 sec`, `FedExec=4`, `Fed Put Bytes=1323328`, `fed_fed_fout=2`, `fed_ba+*=2`
    - MinST is now the active bad path:
      - workers=`2`: `251.856 sec`, `FedExec=599`, `fed_fed_refed=54`, `fed_fed_fout=56`, `fed_ba+*=110`, `Fed Put Bytes=46673136`
      - workers=`4`: `269.302 sec`, `FedExec=221`, `fed_fed_fout=110`, `fed_ba+*=110`, `Fed Put Bytes=112815616`
  - current interpretation:
    1. the `dp12` DP-side fix remains valid beyond workers=`1`
    2. the mission has now shifted to a **multi-worker MinST kmeans parity-follow** issue
    3. the best next trace lane is `kmeans / wan_mid / workers=2` with `SYSDS_FED_PLANNER_TRACE=1`

- **2026-03-08 targeted trace pair launched for the active multi-worker MinST issue.**
  - prefix: `ralph_trace_w2_kmeans_wanmid_postdp12_20260308`
  - scope:
    - workers=`2`
    - planners=`mkl-min-st-cut,mkl-cost`
    - workload=`kmeans`
    - profile=`wan_mid`
    - trace enabled
  - goal:
    - determine whether the multi-worker MinST regression is the same logical `kmeans` chain resurfacing under multi-worker layout/edge accounting or a different worker-sensitive MinST repair/edge-cost bug.

- `2026-03-08` multi-worker MinST targeted trace (`ralph_trace_w2b_kmeans_wanmid_20260308`) isolated the active bad path to `kmeans.dml:134` / `hop 358` plus downstream `hop 419` under `workers=2`.
  - MinST:
    - `252.593 sec`
    - `fed_fed_refed=54`, `fed_fed_fout=56`, `fed_ba+*=110`
    - `hop 358 = FED/FOUT`, `hop 419 = FED/LOUT`
  - DP:
    - `44.934 sec`
    - `fed_fed_refed=0`, `fed_fed_fout=2`, `fed_ba+*=2`
    - `hop 358` / `hop 419` stay on plain local `ba+*`

- MinST patch attempt kept:
  - stable fed-init `TRead` local-download share (`hop 354` `c->l` no longer multiplied by loop weight)
  - fresh rerun `ralph_minst358fix_w2trace_20260308` changed `hop 354` trace cost (`2246240 -> 37437`) but left runtime essentially unchanged (`253.088 sec`)
  - keep this patch as a safe parity fix, but it does not close the active issue

- MinST patch attempt reverted:
  - native FOUT result-edge `p->t` charge for all native MinST FOUT selections
  - fresh rerun `ralph_minst358foutfix_w2trace_20260308` worsened the active path (`245.133 sec`, `fed_fed_fout=110`, `fed_ba+*=110`)
  - `hop 358` demoted to `FED/LOUT`, but the runtime still expanded through repeated FED `ba+*` + `fed_fed_fout`
  - revert kept runtime tree aligned with the previous safe baseline

- Current active issue after these branches:
  - multi-worker MinST `kmeans/wan_mid` still has a parity gap around `hop 358/419`
  - neither stable-fed-init local-download sharing nor a one-edge native-FOUT result charge is sufficient
  - next branch should target the deeper MinST worker-sensitive valuation / state-encoding gap for the `X %*% t(C)` chain under `workers>1`

- Additional `2026-03-08` note after reverting the native-FOUT result-edge patch:
  - repeated `fed_fed_fout` in the bad `workers=2` MinST path is not the `hop 358` output materialization itself
  - the inst-strings show the hotspot is the repeated **broadcast child** materialization (`_mVar310`, later `_mVar311`) feeding FED `ba+*`
  - therefore the next branch is not another result-edge tweak; it is the deeper MinST unary / loop-weight / FED-vs-CP valuation gap on the `ba+*` chain (`hop 358`, `hop 419`)

- Likely deeper root after the latest trace pair:
  - DP hot `kmeans` loop goes through the unroll-aware path (`computeWeight=1`, `networkWeight=1`, `multiplicity=1` on the concrete hot hop)
  - MinST still prices the same logical hot hop with `weights(op=60, net=60)`
  - this loop-weight/state-representation gap is now the strongest structural explanation for the remaining MinST multi-worker regression

- **2026-03-08 update: fresh FLOPS probes showed that the strongest active cause is shared `AggBinaryOp` compute-cost miscalibration, not only the MinST loop-weight representation.**
  - environment / condition:
    - cell: `workers=2`, `workload=kmeans`, `profile=wan_mid`, `planner=mkl-min-st-cut`
    - baseline bad run:
      - `ralph_trace_w2b_kmeans_wanmid_20260308`
      - runtime `252.593 sec`
      - `hop 358 = FED/FOUT`, `hop 419 = FED/LOUT`
  - fresh evidence without source changes:
    - `SYSDS_FED_COST_FLOPS=120000000000` (`ralph_minst_flopsprobe_20260308`) → `45.191 sec`, `358/418/419 = CP/LOUT`
    - `SYSDS_FED_COST_FLOPS=32000000000` (`ralph_minst_flopsprobe32_20260308`) → `45.094 sec`, `358/418/419 = CP/LOUT`
  - reading:
    - a moderate throughput increase for BLAS-like `ba+*` already flips MinST back to the good CP path
    - therefore the active bad path is not best explained by a remaining legality/anchor/runtime-contract issue
    - the strongest evidence now points to **shared `AggBinaryOp` compute-cost overpricing on the CP side**

- **상태: 해결 — shared `AggBinaryOp` compute calibration patch closes the active multi-worker MinST `kmeans/wan_mid` regression.**
  - 환경/조건:
    - `workers=2`
    - `planner=mkl-min-st-cut`
    - `workload=kmeans`
    - `profile=wan_mid`
  - 원인 분석:
    - `ComputeCost.getHOPComputeCost(...)`의 `AggBinaryOp` self cost를
      `FederatedCostModel.computeOpCost(...)`가 공통 기본 throughput `2 * 1024^3 FLOPs/s`로 나누면서,
      large `ba+*`의 CP unary cost를 실제 runtime보다 과대평가했다.
    - 이 shared-model 오차가 MinST `hop 358/419`를 FED 쪽으로 기울게 만들었다.
  - 해결 요약:
    - `FederatedCostModel`에 `SYSDS_FED_COST_AGGBINARY_FLOPS`를 추가했다.
    - 기본값은 `32e9 FLOPs/s`.
    - 적용 범위는 `AggBinaryOp`의 shared `computeOpCost(...)`만으로 제한했다.
    - 전역 `FLOPS_PER_SEC`를 올리는 방식은 쓰지 않았다.
  - 수정 파일:
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
    - `tmp/systemds-local/src/test/java/org/apache/sysds/test/component/federated/FederatedCostModelFallbackTest.java`
  - 검증:
    - tests:
      - `mvn -q -Dtest=FederatedCostModelFallbackTest,FederatedPlanMinSTHyperedgeTest,FederatedPlanMinSTRewireTest,FederatedPlanTReadWriteConsistencyTest,FederatedPlanCostEnumeratorTest test` → pass
    - build:
      - `mvn -q -DskipTests compile` → pass
      - isolated tree `mvn -q -DskipTests package` → pass
    - diagnostics:
      - modified files `lsp_diagnostics = 0`
    - fresh rerun:
      - prefix: `ralph_aggbinary_w2_kmeans_wanmid_20260308`
      - runtime `43.943 sec`
      - `hop 358 = CP/LOUT`
      - `hop 418 = CP/LOUT`
      - `hop 419 = CP/LOUT`
      - rule-check `0/0`
  - 잔여 이슈:
    - 이 patch는 active `workers=2` MinST `kmeans/wan_mid` regression을 닫았지만,
      다른 workload/profile/worker-count 조합까지는 아직 fresh subset 재확인이 필요하다.
  - 잠재 회귀 위험:
    - 다른 `AggBinaryOp` heavy workload에서 CP 쪽이 과도하게 싸질 수 있다.
    - 감지 방법:
      - `workers=2,4` subset 재검증에서 `pca/lm/kmeans` runtime ordering과 `fed_fed_fout/fed_ba+*` counts를 다시 비교한다.

- **후속 검증 결과: `workers=2,4` `kmeans/wan_mid` subset에서도 수정 효과가 유지된다.**
  - 재현 절차:
    - prefix: `ralph_w24_kmeans_postaggbinary_20260308`
    - state: `experiments/results/matrix_state/ralph_w24_kmeans_postaggbinary_20260308.tsv`
    - summary:
      - `experiments/results/workers/runtime/ralph_w24_kmeans_postaggbinary_runtime_summary_20260308.csv`
      - `experiments/results/workers/runtime/ralph_w24_kmeans_postaggbinary_runtime_summary_20260308.md`
  - 관측 결과:
    - rule-check `0/0`
    - `workers=2`
      - DP: `40.313 sec`, `fed_fed_refed=0`, `fed_fed_fout=1`, `fed_ba+*=1`
      - MinST: `44.350 sec`, `fed_fed_refed=0`, `fed_fed_fout=2`, `fed_ba+*=2`
    - `workers=4`
      - DP: `31.237 sec`, `fed_fed_refed=0`, `fed_fed_fout=2`, `fed_ba+*=2`
      - MinST: `30.378 sec`, `fed_fed_refed=0`, `fed_fed_fout=2`, `fed_ba+*=2`
  - 의미:
    - 기존 multi-worker MinST bad regime (`251s/269s`, `fed_fed_refed=54`, `fed_ba+*=110`)는 사라졌다.
    - 현재 `kmeans/wan_mid`는 `workers=2,4`에서도 DP/MinST가 같은 low-FED regime으로 수렴한다.

- **후속 검증 결과: `pca` / `lm` multi-worker subset도 회귀 없이 clean하다.**
  - `pca`
    - prefix: `ralph_w24_pca_postaggbinary_20260308`
    - state: `experiments/results/matrix_state/ralph_w24_pca_postaggbinary_20260308.tsv`
    - summary:
      - `experiments/results/workers/runtime/ralph_w24_pca_postaggbinary_runtime_summary_20260308.csv`
      - `experiments/results/workers/runtime/ralph_w24_pca_postaggbinary_runtime_summary_20260308.md`
    - rule-check `0/0`
    - `fed_fed_refed=2`, `fed_fed_fout=0`, `fed_ba+*=0` 유지
  - `lm`
    - prefix: `ralph_w24_lm_postaggbinary_20260308`
    - state: `experiments/results/matrix_state/ralph_w24_lm_postaggbinary_20260308.tsv`
    - summary:
      - `experiments/results/workers/runtime/ralph_w24_lm_postaggbinary_runtime_summary_20260308.csv`
      - `experiments/results/workers/runtime/ralph_w24_lm_postaggbinary_runtime_summary_20260308.md`
    - rule-check `0/0`
    - low-FED regime(`fed_exec=5`, `fed_fed_refed=1`, `fed_ba+*=1`) 유지
  - 의미:
    - shared `AggBinaryOp` calibration이 multi-worker healthy lanes를 깨뜨리지 않았다.
    - 다음 active issue는 다시 `workers=1` residual anomaly set(`logreg`, `l2svm`)으로 좁혀진다.

- **상태: 해결 — `workers=1` `logreg/wan_mid` MinST residual FED/refed path**
  - 환경/조건:
    - `workers=1`
    - `planner=mkl-min-st-cut`
    - `workload=logreg`
    - `profile=wan_mid`
    - residual branch after the shared `AggBinaryOp` multi-worker fix
  - 재현 절차:
    - baseline trace:
      - prefix: `ralph_trace_w1_logreg_wanmid_20260308`
    - decisive rerun:
      - prefix: `ralph_minst_treadamort_logreg_wanmid_20260308`
      - state: `experiments/results/matrix_state/ralph_minst_treadamort_logreg_wanmid_20260308.tsv`
  - 관측 증상:
    - baseline MinST trace runtime was `111.042 sec`
    - repeated FED path remained at the hot pair:
      - `fed_fed_refed=30`
      - `fed_ba+*=30`
    - `hop 594 (TRead X)` stayed `FED/FOUT`
    - `hop 603 (ba(+*))` stayed `FED/LOUT`
  - 원인 분석:
    - MinST did not just suffer from a closed legality gate.
    - The first branch confirmed that opening `CP/LOUT` for fed-init `TRANSIENTREAD` is legal, but still insufficient.
    - The real gap was in `FederatedPlanMinSTGraph.setVertexCost(...)` and `computeTransientReadDownloadEdgeCost(...)`:
      - stable fed-init `TRead` local materialization was charged like `opWeight * download`
      - this overcharged the CP unary path for `hop 594`
      - which kept `hop 603` on the repeated `FED/LOUT` path
    - DP already amortized the same effect via parent-share / forwarding-share logic, so this was a MinST parity gap.
  - 해결 요약:
    - prerequisite legality patch:
      - `FederatedPlanMinSTRewire.applyTransientPlacementRestrictions(...)`
      - keep `CP/LOUT` open for fed-init `TRANSIENTREAD`
      - still keep `CP/FOUT` and `FED/LOUT` closed
    - decisive parity patch:
      - `FederatedPlanMinSTGraph.computeTransientReadLocalMaterializationCost(Vertex)` 추가
      - stable fed-init `TRead`는
        - loop reuse factor `min(1, networkWeight/opWeight)`
        - parent-share factor `1 / numParents`
        를 적용한 amortized local materialization cost를 사용
      - `setVertexCost(Vertex)`와 `computeTransientReadDownloadEdgeCost(Vertex)`를 같은 cost로 정렬
  - 수정 파일:
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
    - `tmp/systemds-local/src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - 검증:
    - tests:
      - `FederatedPlannerFallbackIntegrationTest` → `31/31`
      - `FederatedPlanMinSTRewireTest` → `2/2`
      - `FederatedPlanTReadWriteConsistencyTest` → `3/3`
      - `FederatedPlanMinSTHyperedgeTest` → `11/11`
    - build:
      - `mvn -q -DskipTests compile` → pass
      - isolated tree `mvn -q -DskipTests package` → pass
    - fresh rerun:
      - MinST runtime `79.587 sec`
      - DP runtime `78.978 sec`
      - rule-check `0/0`
      - `hop 594`:
        - `unaryCP=1247.417526`
        - `TR edge c->l download=1247.417526`
        - final `FED/FOUT`, `noLocal=false`
      - `hop 603`:
        - `unaryCP=961.781223`
        - `unaryFED=3965.171223`
        - final `CP/LOUT`
      - fresh MinST inst-stats:
        - `fed_fed_refed=0`
        - `fed_fed_fout=0`
        - `fed_ba+*=0`
  - 잔여 이슈:
    - `logreg`의 other-profile (`lan`, `wan_light`) sweep은 동일 patch 기준으로 fresh propagation 확인이 아직 필요하다.
    - `l2svm`의 `lan > wan_light` monotonicity anomaly는 별도 branch로 남아 있다.
  - 잠재 회귀 위험:
    - stable fed-init `TRead` share를 과도하게 낮추면 다른 MinST branch에서 CP local materialization이 과도하게 유리해질 수 있다.
    - 감지 방법:
      - `workers=1` `logreg` 전 profile sweep과 MinST `hop 594/603` trace, `fed_fed_refed/fed_ba+*` counts를 다시 확인한다.

- **상태: 진행중 — `workers=1` `logreg` monotonicity의 잔여 tiny delta**
  - 환경/조건:
    - `workers=1`
    - planners=`mkl-min-st-cut,mkl-cost`
    - workload=`logreg`
    - profiles=`lan,wan_light,wan_mid`
    - prefix: `ralph_logreg_w1_posttreadamort_20260308`
  - 관측 결과:
    - fresh propagation sweep after the kept MinST parity patch:
      - MinST `lan=69.106 sec`, `wan_light=68.786 sec`, `wan_mid=75.809 sec`
      - DP `lan=69.854 sec`, `wan_light=77.252 sec`, `wan_mid=79.891 sec`
    - rule-check:
      - only one remaining violation:
        - `RULE3_FAIL: lan>wan_light worker=1 conf=mkl-min-st-cut workload=logreg_P2P2D lan=69.106000 wan_light=68.786000`
    - all six inst-stats are now fully local:
      - `fed_fed_refed=0`
      - `fed_fed_fout=0`
      - `fed_ba+*=0`
  - 의미:
    - substantive planner/runtime mismatch is gone
    - old repeated MinST FED/refed path no longer appears on any `logreg` profile
    - remaining `0.320 sec` gap is now much smaller and is closer to harness/runtime noise scale than the prior planner-pathology regime
  - 다음 분기:
    - stronger residual branch moves to `workers=1 l2svm` monotonicity
    - keep `logreg` tiny delta documented, but do not reopen the already-closed MinST FED/refed branch unless fresh traces show a non-local path again

- **상태: 해결 — `workers=1` `l2svm` MinST `lan > wan_light` monotonicity warning은 재현되지 않음**
  - 환경/조건:
    - `workers=1`
    - planners=`mkl-min-st-cut,mkl-cost`
    - workload=`l2svm`
    - profiles=`lan,wan_light`
    - fresh trace prefix: `ralph_trace2_w1_l2svm_lanwanlight_20260308`
  - 재현 절차:
    - `SYSDS_FED_PLANNER_TRACE=1`
    - `bash experiments/run_LAN_docker_matrix.sh --workers-list 1 --planner-confs mkl-min-st-cut,mkl-cost --workloads l2svm --net-profiles lan,wan_light --run-id-prefix ralph_trace2_w1_l2svm_lanwanlight_20260308 --resume 0 --continue-on-failure 0 -- --systemds-root /home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local/tmp/systemds-local-isolated`
  - 관측 결과:
    - rule-check `0/0`
    - MinST runtimes:
      - `lan=67.377 sec`
      - `wan_light=70.079 sec`
    - DP runtimes:
      - `lan=65.404 sec`
      - `wan_light=66.704 sec`
    - four fresh inst-stats are local-only except federated initialization:
      - ext-opcodes = `['fed_fedinit']` only
      - `fed_fed_refed=0`
      - `fed_fed_fout=0`
      - `fed_ba+*=0`
  - 원인 분석:
    - the earlier `lan > wan_light` warning in `ralph_w1_expand_postdp12_20260307` does not reproduce under fresh trace rerun.
    - the fresh logs show no active FED/refed/fout path in the hot region, so there is no surviving planner-pathology branch to fix.
    - the prior warning is therefore more consistent with timing/harness fluctuation than with DP/MinST cost, memory, oracle, or runtime-contract mismatch.
  - 해결 요약:
    - no code patch was required
    - issue was closed by fresh trace revalidation and reclassification as non-reproducible noise
  - 수정 파일:
    - none (code unchanged)
    - documentation only:
      - `docs/REPORT_FEDPLANNER_DP_MINST_RUNTIME_ALIGNMENT_2026-03-06.md`
      - `tmp/systemds-local/docs/SESSION_ISSUES_2026-03-06.md`
  - 검증:
    - `experiments/results/matrix_state/ralph_trace2_w1_l2svm_lanwanlight_20260308.tsv`
    - fresh rule-check `0/0`
    - fresh logs/inst-stats show local-only execution except `fed_fedinit`
  - 잔여 이슈:
    - no active `l2svm` planner issue remains from this branch
  - 잠재 회귀 위험:
    - future runs can still show small network-order noise on fully local workloads
    - 감지 방법:
      - if the warning reappears, first confirm whether fresh inst-stats still show only `fed_fedinit`; reopen planner work only if a non-local FED path reappears
