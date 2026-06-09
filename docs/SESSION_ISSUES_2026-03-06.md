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

## 2026-03-14 checkpoint — narrowed 2% wave, rejected parent-compatibility probe

- scope stays fixed to:
  - `pca / DP`
  - `logreg`
  - `l2svm / MinST` collateral
- reverted probe:
  - `FederatedPlannerDpFedCostBased.java`
    - temporary CP/FOUT child-compatibility relaxation in
      `isCompatibleWithChildDecisions(...)` /
      `computeParentVariantSwitchDelta(...)`
  - `FederatedPlannerFallbackIntegrationTest.java`
    - temporary switch-delta CP/FOUT compatibility test
- fresh verify after revert:
  - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest test` pass
  - `mvn -q -DskipTests compile` pass
  - isolated `mvn -q -DskipTests package` pass

### Fresh evidence summary

- `pca / lan / w1 / DP`
  - parent-compatibility probe did **not** change the decisive rewrite:
    - `hop 74 (TRead X) -> LOUT`
    - `hop 75 (ba(+*)) -> LOUT`
    - `hop 90 (TWrite XReduced) -> LOUT`
    - `hop 225 (TWrite X) -> LOUT`
  - even when `hop 75` / `hop 90` got `bestDelta=0` in the temporary probe,
    the planner still chose `LOUT`
- `logreg / lan / w2`
  - clarified that Rule2 checker reads coordinator-log `Total execution time`,
    not `runner_phase_* systemds_exec`
  - for `ralph_stage2c_parentcompat_logreg_w2_all4_20260314` the coordinator
    times were:
    - `DP = 11.891s`
    - `MinST = 11.485s`
    - `Heuristic = 12.059s`
    - `FedAll = 12.098s`
  - so checker `violations=0` was correct for that run
- `l2svm / lan / w3 / MinST`
  - fresh targeted trace stayed close to baseline (`+0.66%`)
  - keep as collateral guard, not as first patch lead

### Current strongest hypothesis

The active DP issue is no longer the discarded CP/FOUT child-compatibility
relaxation.

Current strongest shared hypothesis:

> `computeParentVariantSwitchDelta(...)` overcharges one-child output flips
> because it may choose a parent variant that changes additional sibling edges,
> letting unrelated sibling/output drift leak into the delta.

Concrete signals:

- `pca`
  - `hop 74 (TRead X)` parent `75`: `bestDelta=5903.764607`
  - `hop 75 (ba(+*))`: `fOutAdditional=1426.209619`
  - `hop 90 (TWrite XReduced)` parent `175`: no compatible desired-child
    variant in the retained parent set, then child-forwarding fallback
    `delta=713.104810`
- `logreg`
  - `hop 595 (TRead Y)`: compatible variants exist but still cost
    `128.881926` / `10.999673`
  - `hop 611 (b(/))`: compatible variant cost from parent `1390`
    still `678.995616`

### Next patch surface

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `computeParentVariantSwitchDelta(...)`
  - candidate scoring/selection should preserve current sibling-edge outputs
    where possible for a one-child switch-delta
- keep collateral verification on:
  - `logreg`
  - `l2svm / MinST`

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


- **상태: 해결 — upstream 원본 PCA에서 DP/MinST가 `Components/XReduced` 경로를 local로 잘못 잡던 잔여 parity gap**
  - 환경/조건:
    - workload/profile/worker: `pca / lan / w1`
    - original upstream builtin `pca.dml` SHA256:
      - `628188bb3174853826cd8bbb45ed98e9a5c564a30cfdef303d2b39d5af8f3d65`
  - 원인 1 (DP):
    - `TransTableRewireUtils.mapFunctionOutputs(...)`가 DML-backed `MULTIRETURN_BUILTIN`에서 function body output roots 대신 caller-visible outputs만 따라가 `XReduced`/`Components` 경로를 충분히 rewire하지 못했다.
    - 결과적으로 rewrite/recompile state에서 `hop 245 (XReduced)` FED 계획이 누락되어 DP가 `192.775 sec`까지 악화됐다.
  - 수정 1 (DP):
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/TransTableRewireUtils.java`
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpRewireTransTable.java`
    - `src/main/java/org/apache/sysds/hops/recompile/Recompiler.java`
    - targeted rerun `ralph_pca_dp18_mtroorder2_20260309` 결과:
      - `mkl-cost / pca / lan / w1 = 60.001 sec`
      - `hop 75/245` FED rewrite+restore 복구
      - heavy hitter `fed_tsmm 1.875`
  - 원인 2 (MinST parity):
    - `FederatedPlanMinSTRewire`가 optional-input promotion으로 열린 FED alternatives를 caps만 병합하고, 그 alternatives를 가능하게 한 promoted FED `FType` (`FULL`)는 보존하지 않았다.
    - `hop 224`는 base oracle이 준 `COL`을 유지했고, downstream `hop 25/26`가 `CP/LOUT`로 내려가 local `tsmm 58.456`이 발생했다.
  - 수정 2 (MinST parity-follow):
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
    - alternative FED caps가 base decision에 없던 FED feasibility를 새로 열어줄 때, 그 alternative의 promoted FED `FType`도 함께 보존하도록 수정
    - focused regression test 추가:
      - `FederatedPlannerFallbackIntegrationTest#testMinSTFederatedAlternativePromotesFullFTypeForValidFedPath`
  - 검증:
    - targeted tests:
      - `FederatedPlannerFallbackIntegrationTest#testMinSTFederatedAlternativePromotesFullFTypeForValidFedPath`
      - `FederatedPlanMinSTRewireTest`
      - `FederatedPlanMinSTHyperedgeTest`
    - `mvn -q -DskipTests compile`
    - isolated `mvn -q -Dmaven.test.skip=true package`
    - fresh rerun `ralph_pca_minst20_altftype_20260309`:
      - `mkl-min-st-cut / pca / lan / w1 = 56.623 sec`
      - `hop 224 finalFType=FULL`
      - `hop 25 final=FED/FOUT`
      - `hop 26 final=FED/LOUT`
      - `fed_tsmm 1.987`
      - rule-check `0 violation / 0 warning`
  - 잔여 이슈:
    - PCA original 전체 12-case MinST propagation sweep은 별도 run(`ralph_pca_minst20_full_20260309`)으로 진행 중이며, targeted `w1/lan` root cause는 닫혔다.
  - 잠재 회귀 위험:
    - promoted FED `FType` 보존은 FED alternative가 base decision에 없던 경우에만 적용해 scope를 제한했다.
    - 감지 방법:
      - if future MinST regressions appear, inspect `MinST-Rewire` for `oracleFType != finalFType` and confirm that the promoted `finalFType` corresponds to a FED-satisfiable alternative rather than a planner-only override.


- **상태: 분석중(원인 분리 완료) — latest-success PCA 그래프에서 DP/MinST가 ALL/Heuristic보다 항상 우세하지 않게 보이는 현상**
  - 환경/조건:
    - source graph: `experiments/plots/out/ralph_latest_success_matrix_20260309.png`
    - cell selection: planner/worker/workload/profile별 **latest successful run**
    - diagnostic breakdown:
      - `experiments/results/workers/runtime/ralph_pca_latest_breakdown_20260309.csv`
      - `experiments/results/workers/runtime/ralph_pca_latest_breakdown_20260309.md`
  - 관측 증상:
    - PCA 일부 셀에서 DP/MinST가 ALL/Heuristic보다 total runtime이 항상 더 좋게 보이지 않는다.
    - 그러나 key FED phases의 top HH 비중은 작고, total runtime의 대부분을 local `eigen()`가 차지한다.
  - 원인 분석:
    - 현재 PCA latest-success run들에서는 `eigen(C)`가 total runtime의 약 **80–91%**를 차지한다.
    - 예: `pca/lan/w1`
      - `ALL`: `60.102 sec`, `eigen=51.948 sec`, `Components=FED/FOUT`, `XReduced=FED/LOUT`
      - `DP`: `68.085 sec`, `eigen=60.752 sec`, `Components=FED/FOUT`, `XReduced=FED/LOUT`
    - 즉 이 셀의 DP vs ALL gap 대부분은 planner path 차이라기보다 local `eigen()` phase wall-clock 차이로 설명된다.
    - `fed_tsmm`, `fed_uacmean`, `fed_ba+*`는 이미 상대적으로 작아서 planner-sensitive gain이 total wall-clock에서 희석된다.
  - 해결/의사결정:
    - 이 현상은 즉시 새로운 planner hack으로 처리하지 않는다.
    - 먼저 PCA를 pure planner benchmark가 아니라 **planner + local eigensolver + runtime jitter**가 섞인 workload로 해석한다.
    - planner 품질 판정 시에는 total time만이 아니라 `Components/XReduced` placement와 `eigen`/FED phase 분해를 함께 본다.
  - 수정 파일:
    - `experiments/plots/fed2_planner_dp_docker_matrix_line.py` (latest-cell-success selection mode)
    - `experiments/scripts/fedplanner_pca_latest_breakdown.py`
  - 검증:
    - `experiments/results/workers/runtime/ralph_pca_latest_breakdown_20260309.csv`
    - `experiments/results/workers/runtime/ralph_pca_latest_breakdown_20260309.md`
  - 잔여 이슈:
    - PCA에서 “DP/MinST가 항상 ALL/Heuristic보다 좋아야 한다”는 요구를 검증하려면, total runtime뿐 아니라 local `eigen()` 분리/반복 실행 median 기준이 추가로 필요하다.
  - 잠재 회귀 위험:
    - latest-success per-cell graph는 서로 다른 run family를 섞을 수 있으므로, 과해석하면 planner pathology처럼 보이는 noise를 만들 수 있다.
    - 감지 방법:
      - faster/slower cell이 보이면 먼저 해당 셀의 `eigen share`, `Components/XReduced` placement, run family를 함께 확인한다.

- **상태: 해결 — 원본 PCA benchmark는 유지하되, 별도 planner-friendly PCA experiment variant를 추가**
  - 환경/조건:
    - request: 원본 builtin `pca.dml`은 보존하고, 같은 Components 결과를 목표로 하는 planner-friendly 실험용 DML variant 추가
    - scope: `experiments/code/exp/*`, `experiments/code/distributedExpNew.sh`, `experiments/code/localExp.sh`
  - 의사결정 근거:
    - upstream benchmark 정의를 바꾸지 않기 위해 `tmp/systemds-local/scripts/builtin/pca.dml`은 수정하지 않았다.
    - 대신 experiment template 레벨에서 opt-in variant만 추가했다.
  - 해결 요약:
    - `experiments/code/exp/pca_planner_friendly.dml`
    - `experiments/code/exp/pca_planner_friendly_fed.dml`
    - 를 추가해 upstream PCA의 Components 계산 수식을 inline으로 전개했다.
    - 실험 harness가 실제로 write하는 값은 `Mout/Components`뿐이므로, variant는 Components path만 계산하고 `XReduced`는 생략했다.
    - selection은 `PCA_DML_VARIANT=planner_friendly`로 opt-in 하도록 `distributedExpNew.sh`/`localExp.sh`에 최소 지원을 추가했다.
  - 수정 파일:
    - `experiments/code/exp/pca_planner_friendly.dml`
    - `experiments/code/exp/pca_planner_friendly_fed.dml`
    - `experiments/code/distributedExpNew.sh`
    - `experiments/code/localExp.sh`
  - 검증:
    - `bash -n experiments/code/distributedExpNew.sh`
    - `bash -n experiments/code/localExp.sh`
    - small local equivalence check on synthetic input:
      - original vs variant raw Components는 eigenvector sign ambiguity로 그대로는 차이가 남을 수 있음
      - non-degenerate synthetic input에서 sign-aligned diff:
        - `sign_aligned_max_abs_diff = 8.881718e-04`
        - `sign_aligned_fro_diff = 1.626028e-03`
  - 잔여 이슈:
    - 이 variant는 원본 benchmark 대체물이 아니라 별도 experiment variant다.
    - `XReduced`를 생략하므로 wall-clock은 원본 PCA benchmark와 직접 비교하면 안 된다.
  - 잠재 회귀 위험:
    - 사용자가 `PCA_DML_VARIANT=planner_friendly`를 켠 상태를 원본 PCA와 혼동할 수 있다.
    - 감지 방법: run metadata/log에 `PCA_DML_VARIANT`를 함께 남기거나, graph caption에 variant 여부를 명시한다.

- **상태: 해결 — DP/MinST 차이를 더 잘 드러내는 별도 `planner_stress` PCA experiment variant 추가**
  - 환경/조건:
    - original upstream PCA benchmark는 그대로 유지
    - goal: canonical benchmark를 바꾸지 않고, Components output은 유지하면서 planner-sensitive downstream work를 더 크게 드러내는 별도 variant 추가
  - 의사결정 근거:
    - upstream `pca.dml`을 특정 planner에 유리하게 바꾸는 대신, experiment template 레벨에서 명시적인 별도 variant를 추가한다.
  - 해결 요약:
    - `experiments/code/exp/pca_planner_stress.dml`
    - `experiments/code/exp/pca_planner_stress_fed.dml`
    - 를 추가했다.
    - Components 계산 자체는 upstream PCA와 동일하게 유지하고, 이후
      - `XReduced = X %*% Components`
      - `Loading = t(X) %*% XReduced`
      - `ReducedGram = t(XReduced) %*% XReduced`
      - `ReducedEnergy = colSums(XReduced^2)`
      를 추가해 small-output→large-FED-consumer 구조를 더 오래 유지하게 했다.
    - 최종 write는 여전히 `Components`만 한다.
    - harness selector에 `PCA_DML_VARIANT=planner_stress` 지원을 추가했다.
  - 수정 파일:
    - `experiments/code/exp/pca_planner_stress.dml`
    - `experiments/code/exp/pca_planner_stress_fed.dml`
    - `experiments/code/distributedExpNew.sh`
    - `experiments/code/localExp.sh`
  - 검증:
    - `bash -n experiments/code/distributedExpNew.sh`
    - `bash -n experiments/code/localExp.sh`
    - synthetic local equivalence vs original PCA Components (sign ambiguity 감안):
      - `sign_aligned_max_abs_diff = 8.881718e-04`
      - `sign_aligned_fro_diff = 1.626028e-03`
  - 잔여 이슈:
    - 이 variant는 canonical PCA benchmark가 아니라 planner-stress benchmark다.
    - `Components`는 유지하지만 extra downstream work를 추가하므로 wall-clock 비교는 원본 PCA와 분리해서 해석해야 한다.
  - 잠재 회귀 위험:
    - canonical/original PCA와 planner-stress PCA를 혼동하면 결과 해석이 왜곡된다.
    - 감지 방법: run prefix, graph caption, 문서에서 `planner_stress`를 명시한다.

- **상태: 해결 — `eigen()` bottleneck 편향을 줄인 별도 `balanced_stress` PCA-like variant 추가**
  - 환경/조건:
    - request: upstream/original PCA를 바꾸지 않으면서, 예전 `planner_stress`처럼 “계속 FED가 유리한” 편향도 줄이고, 원본 PCA의 `eigen(C)` local bottleneck에 너무 갇히지도 않는 별도 benchmark variant가 필요했다.
  - 의사결정 근거:
    - canonical PCA를 고치면 benchmark 정의가 흔들리므로 experiment template 레벨에서만 opt-in variant를 추가한다.
    - 구현은 downstream 1-step heuristic이 아니라, DML 자체를 `block subspace iteration + local QR + small Rayleigh-Ritz eigen` 구조로 바꿔 mixed placement를 자연스럽게 만들도록 한다.
  - 해결 요약:
    - `experiments/code/exp/pca_balanced_planner_stress.dml`
    - `experiments/code/exp/pca_balanced_planner_stress_fed.dml`
    - 를 추가했다.
    - 반복 단계마다
      - `Y = X %*% Q`
      - `Z = t(X) %*% Y / (N - 1)`
      - `qr(Z)` 기반의 local dense orthogonalization
      를 수행하고,
      마지막에는 small `B = t(Q) %*% Z`에서 Rayleigh-Ritz `eigen(B)`를 풀어 `Components`를 만든다.
    - 이후
      - `XReduced = X %*% Components`
      - `Loading = t(X) %*% XReduced`
      - `ReducedGram = t(XReduced) %*% XReduced`
      - `ReducedEnergy = colSums(XReduced^2)`
      - `SmallCoupling = t(Components) %*% Loading`
      를 추가해 large-FED / small-local mixed workload를 유지한다.
    - harness selector에 `PCA_DML_VARIANT=balanced_stress`와 alias `planner_balanced`를 추가했다.
  - 수정 파일:
    - `experiments/code/exp/pca_balanced_planner_stress.dml`
    - `experiments/code/exp/pca_balanced_planner_stress_fed.dml`
    - `experiments/code/distributedExpNew.sh`
    - `experiments/code/localExp.sh`
    - `experiments/run_LAN_docker.sh`
  - 검증:
    - `bash -n experiments/code/distributedExpNew.sh`
    - `bash -n experiments/code/localExp.sh`
    - local synthetic run:
      - original shape `24x10`
      - balanced shape `24x10`
      - `subspace_proj_fro_diff = 1.737307e-01`
      - `balanced_orthogonality_err = 6.632799e-15`
    - docker smoke follow-up:
      - `PCA_DML_VARIANT=balanced_stress`가 이전에는 coordinator container까지 전달되지 않았는데, `run_LAN_docker.sh`에서 env 전달을 추가해 해결했다.
      - 이후 생성된 federated script `experiments/tmp/gen_pca_P2P2D_1.dml`에서 `subspaceIters`/balanced body가 실제로 렌더링되는 것은 확인했다.
      - 다만 첫 `wan_light / w1 / ALL` smoke는 기존 worker connection race로 `fedinit` 단계에서 실패했다 (`Connection refused`), so balanced variant의 federated runtime 수치는 아직 확보하지 못했다.
  - 잔여 이슈:
    - balanced variant 역시 canonical/original PCA가 아니다.
    - 원본 PCA와 benchmark 의미가 다르므로 graph caption/run prefix에 variant 명시가 필요하다.
  - 잠재 회귀 위험:
    - `planner_stress`와 `balanced_stress`를 혼동하면 “왜 FedAll이 너무 잘하냐 / 왜 eigen bottleneck이 약하냐” 해석이 섞일 수 있다.
    - 감지 방법: `PCA_DML_VARIANT`와 run prefix를 반드시 함께 기록한다.

- **상태: 미해결 residual — ALS에서 DP는 ALL/Heuristic과 같은 `wdivmm` regime에 머물고, MinST만 더 좋은 helper-vector layout을 유지**
  - 환경/조건:
    - dataset=`P2P2D`, profile=`wan_light`
    - `w1`: `ralph_newfed_p2p2d_w1_20260309`
    - `w2,w3,w4`: `ralph_newfed_allnew_w234_fix4_20260309`
  - 관측:
    - `ALS` runtime:
      - ALL `[150.826, 65.359, 50.581, 42.159]`
      - Heuristic `[149.550, 69.881, 50.544, 45.858]`
      - DP `[146.176, 66.173, 62.716, 42.656]`
      - MinST `[90.291, 49.069, 50.106, 42.200]`
    - `w2` clean traces (`ralph_als_steplm_trace_w2_fix6`) show:
      - DP/ALL/Heuristic `wdivmm` heavy hitter remains `~61–65s`
      - MinST `wdivmm` is `~24.988s`
  - 원인 해석:
    - DP가 완전히 잘못된 것이 아니라, helper-vector/refed state에서 `ROW` 축을 충분히 살리지 못해 MinST보다 덜 유리한 `wdivmm` regime에 머문다.
  - 시도한 수정:
    - `preferVectorAxisForRefedCandidate(...)` (DP vector-axis fallback preference)
  - fresh 결과:
    - `ralph_als_w2_fix8_20260309`
    - DP `64.781s`, MinST `50.006s`
    - 실질 개선 없음
  - 현재 결론:
    - 이 patch는 실패/무효에 가깝다.
    - ALS는 여전히 helper-vector layout/state quality residual이 남아 있다.

- **상태: 미해결 residual — steplm DP transient-read FED/FOUT inheritance patch는 runtime 회귀를 일으켜 revert**
  - 환경/조건:
    - dataset=`P2P2D`, profile=`wan_light`
    - clean reference run: `ralph_newfed_allnew_w234_fix4_20260309`
  - clean reference 관측:
    - `steplm` runtime:
      - ALL `[69.322, 24.529, 18.228, 12.812]`
      - Heuristic `[66.065, 26.008, 16.639, 13.053]`
      - DP `[70.500, 28.543, 21.950, 15.962]`
      - MinST `[10.161, 7.589, 8.099, 8.862]`
    - `w2` DP HH:
      - `m_steplm 28.029s`
      - `rightIndex 21.375s`
      - local `tsmm` regime
    - `w2` MinST HH:
      - `m_steplm 7.050s`
      - `fed_rightIndex 123.507`
      - `tsmm 140.214`
      - FED-friendly regime
  - 시도한 수정:
    - DP `TRANSIENTREAD`가 planner-mapped non-`TWrite` rightIndex source에서도 FED/FOUT concreteness를 상속하도록 변경
  - fresh 결과:
    - `ralph_steplm_w2_fix7d_20260309`
    - DP `28.543s -> 103.659s`
    - HH가 `linear_regression_t96`, `m_lm_t96`, `tsmm`, `fed_rightIndex`로 폭증
  - 조치:
    - 해당 patch와 regression test를 revert
  - 현재 결론:
    - steplm의 DP residual은 단순 transient-read FED/FOUT concreteness 누락 문제가 아니다.
    - 다음 원인 축은 `rightIndex` regime selection / FED-local decomposition 차이를 clean trace 기준으로 좁혀야 한다.


- **상태: steplm current-code residual 해소로 판단**
  - failed branch인 planner-mapped transient-read FED/FOUT inheritance patch를 revert한 뒤 current code state를 다시 좁게 재검증했다.
  - fresh run:
    - `ralph_als_steplm_notrace_fix10_20260310`
    - `ralph_steplm_minst_w2_retry_20260310`
  - `P2P2D / wan_light / w2` 결과:
    - DP `7.369s`
    - MinST `7.943s`
  - 현재 heavy hitter regime은 DP/MinST 모두 `fed_rightIndex + tsmm` 축으로 수렴한다.
  - 따라서 steplm은 current code state 기준으로는 open DP residual이 아니라 **이전 stale run이 남긴 잔차**로 정리한다.

- **상태: ALS major DP residual 완화 -- transient mask `W` local-acquire double count fix 반영**
  - 원인:
    - `FederatedPlannerDpCostEnumerator.enumerateTransientReadDataOp(...)`가 `TRead W`의 CP/LOUT 후보에 대해, 이미 matching `TWrite W` LOUT source cumulative cost에 포함된 local-acquire/download를 synthetic `loutAcquireCost`로 다시 더하고 있었다.
    - 결과적으로 DP가 `W` local transient path를 과도하게 비싸게 보았다.
  - 수정:
    - matching transient-write LOUT source plan이 존재하면 synthetic `loutAcquireCost`를 skip.
  - 테스트:
    - `testDpTransientReadLocalSourceSkipsSyntheticLoutAcquireCost`
  - fresh evidence:
    - `ralph_als_fix11_20260310`
    - DP `~65.3s -> 50.348s`
    - MinST `45.727s`
    - `TRead W`가 DP에서도 `CP/LOUT`로 재컴파일됨
    - 양 planner 모두 `Federated Execute = 0/0`
  - 현재 결론:
    - ALS의 gross DP pathology는 해소됨.
    - 남은 차이는 `wdivmm` 중심의 secondary local-runtime residual이며, 더 이상 큰 placement mismatch는 아님.


- **상태: ALS current-code residual은 repeat 재검증 기준으로 runtime/noise 수준으로 축소**
  - repeat run:
    - `ralph_als_fix11_repeat1_20260310`
    - `ralph_als_fix11_repeat2_20260310`
    - `ralph_als_fix11_repeat3_20260310`
  - totals:
    - `DP`: `51.782s`, `48.000s`, `48.102s`
    - `MinST`: `49.143s`, `50.728s`, `46.877s`
  - mean/std:
    - `DP`: mean `49.295s`, std `1.759s`
    - `MinST`: mean `48.916s`, std `1.580s`
  - `m_alsCG` / `wdivmm` heavy hitter도 서로 겹치는 범위로 움직였다.
  - 결론:
    - fix11 이후 ALS는 더 이상 구조적인 DP placement bug로 유지하지 않고, **동일 local `wdivmm` regime 안의 residual runtime/noise**로 정리한다.
    - ALS의 planner 추적은 종료한다.


- **상태: 최종 정리 — 원래 미션 범위(`pca,lm,kmeans,logreg,l2svm,als,steplm`)는 current code state 기준 완료**
  - 기준 문서:
    - `docs/REPORT_FEDPLANNER_DP_MINST_RUNTIME_ALIGNMENT_2026-03-06.md`
      - `2026-03-10 final current-code checkpoint — root-cause map and completion gate`
  - helper / join 상태:
    - hop-level join CSV는 이미 `limitations`로 가능한 것과 부족한 것을 구분하고 있었음
    - summary md는 이 구분이 약했기 때문에 `experiments/scripts/fedplanner_pred_actual_join.py`를 보강해
      - `## Currently possible`
      - `## Still missing`
      섹션을 추가함
    - current-code representative summary:
      - `experiments/results/pred_actual/ralph_pca_currentcode_pair_pred_actual_join_summary_20260310.md`
  - current-code completion 판정:
    - workers=`1` 확장 workload 묶음(`pca,lm,kmeans,logreg,l2svm`)은 2026-03-06~03-08 fresh artifacts로 닫힘
    - late residual이던 `als`, `steplm`은 2026-03-10 current-code rerun으로 닫힘
    - workers>`1` 핵심 subset(`pca,lm,kmeans`)은 2026-03-08 subset/post-patch rerun으로 닫힘
    - `fed_fed_refed` 재발 branch는 현재 미션 범위에서 active하지 않음
  - fresh verification (2026-03-10):
    - `cd tmp/systemds-local && mvn -q -Dtest=FederatedCostModelFallbackTest,FederatedPlanCostEnumeratorTest,FederatedPlanMinSTHyperedgeTest,FederatedPlanMinSTRewireTest,FederatedPlanTReadWriteConsistencyTest,FederatedPlannerFallbackIntegrationTest test`
    - `cd tmp/systemds-local && mvn -q -DskipTests compile`
    - `cd tmp/systemds-local/tmp/systemds-local-isolated && mvn -q -DskipTests package`
    - checked diagnostics: project-level `0` errors / `0` warnings
  - 남은 이슈:
    - optional experiment variant(`balanced_stress` PCA-like)에는 기존 `fedinit` worker connection race가 남아 있으나, canonical mission workloads blocker는 아님
  - 잠재 회귀 위험:
    - multi-return builtin output/state propagation, promoted FED `FType`, stable fed-init amortization, transient local-acquire skip는 모두 shared/stateful logic이므로 향후 신규 workload에서 다시 drift할 수 있음
    - 감지 방법:
      - 먼저 report의 final root-cause map 기준으로 같은 family(`multi-return builtin`, `stable fed-init TRead`, `AggBinary/Quaternary cost`, `local-acquire double count`) 여부를 분류한 뒤 reopen


- **상태: 해결 — DP PCA overwritten-`X` chain에서 same-output compatible-variant cost가 `hop 74/75`에 전파되지 않던 stale rewrite**
  - **환경/조건**
    - planner: `mkl-cost (DP)`
    - workload/profile/workers: original upstream `pca / lan / w1`
    - active branch: B12 raw FED-input boundary 복구 이후
  - **재현 절차**
    - stale baseline:
      - `SYSDS_FED_PLANNER_TRACE=1 ... bash experiments/run_LAN_docker_matrix.sh --workers-list 1 --planner-confs mkl-cost --workloads pca --net-profiles lan --run-id-prefix ralph_b12_pca_dp_w1_lan_fedinput_boundary_20260311 --resume 0 --continue-on-failure 0 -- --systemds-root /home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local/tmp/systemds-local-isolated`
    - fixed rerun:
      - `SYSDS_FED_PLANNER_TRACE=1 ... bash experiments/run_LAN_docker_matrix.sh --workers-list 1 --planner-confs mkl-cost --workloads pca --net-profiles lan --run-id-prefix ralph_b13_pca_dp_w1_lan_compatcost_20260311 --resume 0 --continue-on-failure 0 -- --systemds-root /home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local/tmp/systemds-local-isolated`
  - **관측 증상**
    - B12 trace에서는 `hop 225 (TWrite X)`가 이미 expensive compatible `LOUT` variant(`effectiveCost=1081.917856`, child `(224,FOUT)`)로 재가격화됐는데도,
      - `hop 74` rewrite는 stale `effectiveCost=417.436498`
      - `hop 75` rewrite는 stale `effectiveCost=16328.556625`
      - recompile `hop 245`는 local `CP/LOUT`
      - total runtime `223.448726 sec`
    - 즉 deeper child decision(`224:FOUT -> 225:LOUT expensive`)이 같은 output type(`LOUT`)을 유지한 채 상위 `hop 74/75` cost에 반영되지 않았다.
  - **원인 분석**
    - DP rewrite/conflict layer가 `LOUT↔FOUT` output switch는 다시 계산했지만, 같은 output type 안에서 child-compatible variant가 바뀔 때의 cost shift는 raw cumulative cost로 남겨뒀다.
    - 특히 `FederatedPlannerDpFedCostBased.computeParentVariantSwitchDelta(...)` / `computeSwitchEdgeCostDelta(...)`가 current-vs-candidate 비교에 raw `getCumulativeCost()`를 사용해, `hop 225`의 deeper compatible-cost shift가 `hop 74/75` parent-variant delta와 rewrite effective cost에 전파되지 않았다.
    - **의사결정 근거**: oracle/runtime 제약 문제가 아니라 DP planner 내부의 cost propagation/state consistency 문제이므로, oracle 완화나 workload-specific heuristic이 아니라 DP effective-cost propagation을 수정했다.
  - **해결 요약**
    - `FederatedPlannerDpFedCostBased`에서
      - `selectCompatiblePlanVariant(...)`를 “first compatible”이 아니라 “minimum recursive effective-cost compatible variant” 선택으로 변경
      - `computeCompatiblePlanEffectiveCost(...)`를 추가해 same-output child variant shift를 재귀적으로 재가격화
      - `computeParentVariantSwitchDelta(...)` / `computeSwitchEdgeCostDelta(...)`에 `outputDecisions`를 전달하고, child override된 current/candidate effective cost 기준으로 delta를 계산
      - `rewriteHop(...)` trace/rewrite도 raw cumulative cost 대신 recursive effective cost를 사용
    - regression test:
      - `testDpParentVariantSwitchDeltaUsesRecursiveEffectiveCurrentCost`
  - **수정 파일**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
    - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - **검증**
    - `cd tmp/systemds-local && mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest test`
    - `cd tmp/systemds-local && mvn -q -Dtest=FederatedCostModelFallbackTest,FederatedPlanCostEnumeratorTest,FederatedPlanMinSTHyperedgeTest,FederatedPlanMinSTRewireTest,FederatedPlanTReadWriteConsistencyTest,FederatedPlannerFallbackIntegrationTest test`
    - `cd tmp/systemds-local && mvn -q -DskipTests compile`
    - `cd tmp/systemds-local/tmp/systemds-local-isolated && mvn -q -DskipTests package`
    - fresh trace rerun `ralph_b13_pca_dp_w1_lan_compatcost_20260311`:
      - `hop 74` rewrite `effectiveCost=1081.917856`
      - `hop 75` rewrite `effectiveCost=16993.037983`
      - recompile `hop 245` restored to `FED/FOUT`
      - total runtime `113.521223 sec`
      - matrix rule check `violations=0`, `warnings=0`
    - non-trace collateral triplet `ralph_b13_pca_dp_w1_triplet_20260311`:
      - `lan`: `112.200898 sec`
      - `wan_light`: `112.663496 sec`
      - `wan_mid`: `121.912331 sec`
      - matrix rule check `3/3 success`, `violations=0`, `warnings=0`
  - **잔여 이슈**
    - 이 root cause 자체는 닫혔다. immediate collateral `pca / {lan,wan_light,wan_mid} / w1`도 clean pass했고, 다음 후속은 broader grouped runtime-fix queue로 복귀하는 것이다.
  - **잠재 회귀 위험**
    - compatible-variant search가 sibling/clone drift까지 과도하게 따라가면 unrelated branch cost가 흔들릴 수 있다.
    - 감지 방법:
      - `steplm`/`als`처럼 loop-carried same-output variants가 많은 workload에서 `computeParentVariantSwitchDelta` trace의 `currentEffective` vs `bestEffective`가 비정상적으로 발산하는지 확인한다.


- **상태: 해결 — DP `logreg`에서 transient-write family output-decision이 standalone `TRead`에 의해 같은 iteration 안에서 덮어써지던 fixed-point inconsistency**
  - **환경/조건**
    - planner: `mkl-cost (DP)`
    - workload/profile/workers: `logreg / lan / w1`
    - 전제: docker harness가 `SYSDS_FED_COST_NET_SERDES_BW*`를 coordinator에 전달하도록 이미 수정된 뒤의 authoritative trace 기준
  - **재현 절차**
    - corrected-serdes baseline:
      - `SYSDS_FED_PLANNER_TRACE=1 ... --run-id-prefix ralph_b2o3_serdesenv_trace_logreg_lan_w1_20260311`
    - fixed rerun:
      - `SYSDS_FED_PLANNER_TRACE=1 ... --run-id-prefix ralph_b2p_transientfamily_trace_logreg_lan_w1_20260311`
  - **관측 증상**
    - baseline trace에서 output-decision iter2에
      - `hop 50 (TWrite X)`는 family-level cost 비교 후 `FOUT`를 선택하고 linked `TRead`들에 전파했지만,
      - 같은 iteration 후반에 standalone `hop 160 (TRead X)`가 다시 처리되면서 `LOUT`를 선택했다.
    - 그 결과 rewrite 최종 상태가
      - `hop 50 = FOUT`
      - `hop 160 = LOUT`
      - `hop 1109 = LOUT`
      로 갈라졌고, traced runtime은 `67.174 sec`, `uarsqk+ = 60.811 sec`였다.
  - **원인 분석**
    - `FederatedPlannerDpFedCostBased.computeOutputDecisions(...)`가 transient-write family decision을 linked `TRead`들에 먼저 써 넣은 뒤에도 같은 `conflictCheckMap` iteration을 계속했다.
    - 그래서 이미 family choice가 정해진 linked `TRead`가 standalone entry로 다시 들어오면 `nextDecisions`를 덮어쓰는 planner consistency bug가 있었다.
    - **의사결정 근거**: 이는 oracle/rule/runtime limitation이 아니라 DP fixed-point / state propagation bug이므로, heuristic이 아니라 family-consistent output-decision evaluation으로 수정했다.
  - **해결 요약**
    - `FederatedPlannerDpFedCostBased.computeOutputDecisions(...)`를 two-pass로 변경했다.
      1. `TRANSIENTWRITE` families를 먼저 resolve해서 linked `TRead`까지 같은 output으로 고정
      2. 나머지 entries를 처리하되, 이미 transient-write family가 선택한 linked `TRead` standalone entry는 skip
    - trace에는 `source=transient_write_family`를 남기도록 했다.
    - regression test:
      - `testDpOutputDecisionsKeepTransientWriteFamilyConsistentWhenStandaloneTReadPrefersOtherOut`
  - **수정 파일**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
    - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - **검증**
    - targeted `FederatedPlannerFallbackIntegrationTest` pass
    - federated planner suite pass
    - `mvn -q -DskipTests compile` pass
    - isolated `mvn -q -DskipTests package` pass
    - fresh traced rerun `ralph_b2p_transientfamily_trace_logreg_lan_w1_20260311`:
      - total runtime `61.239 sec`
      - `uarsqk+ = 54.925 sec`
      - rule check `violations=0`, `warnings=0`
      - final rewrite는 family-consistent:
        - `hop 50 = LOUT`
        - `hop 160 = LOUT`
        - `hop 1109 = LOUT`
    - pre-fix corrected-serdes trace 대비:
      - total `67.174 -> 61.239 sec`
      - `uarsqk+ 60.811 -> 54.925 sec`
  - **잔여 이슈**
    - transient family inconsistency root cause는 닫혔다.
    - 다만 `logreg/l2svm`의 전체 cheap-baseline gap이 모두 닫힌 것은 아니므로, 다음은 current code 기준 collateral `all4` rerun과 residual root-cause 분리를 계속해야 한다.
  - **잠재 회귀 위험**
    - linked `TRead`를 family-level choice로 고정하는 과정이 unrelated standalone transient-read choice를 과하게 억누르면 다른 function-body fixed-point에 영향이 갈 수 있다.
    - 감지 방법:
      - trace에서 `source=transient_write_family`가 붙은 linked `TRead`들이 실제 family member인지와, unrelated standalone `TRead`가 skip되지 않았는지를 같이 확인한다.


- **상태: 실패 후 revert — DP `AggBinary` aggregate-to-public stable-download skip 제거 브랜치**
  - **가설**
    - `logreg/l2svm` cheap-baseline miss가 CP-local `ba(+*)` parent에서 stable `FED/FOUT -> CP` local materialization을 과소계상해서 생긴다고 보고, stable `AggBinary` parent의 legacy skip을 제거했다.
  - **시도한 수정**
    - `FederatedPlannerDpCostEstimator.shouldSkipAggregateToPublicFoutDownload(...)`
      - `AggBinaryOp || QuaternaryOp` skip을 `QuaternaryOp` only로 좁힘
    - regression test도 stable `AggBinary` parent에서 positive FOUT->CP share를 요구하도록 변경
  - **fresh evidence**
    - traced rerun `ralph_b2q_noskipaggbinary_trace_logreg_l2svm_lan_w1_20260311`
    - `logreg / lan / w1 / DP`
      - current-code all4: `68.902450 sec`
      - failed branch: `70.952869 sec`
  - **판정**
    - compile/test/package는 pass했지만 active blocker runtime이 악화됐다.
    - shared mismatch를 너무 거칠게 보정한 실패 브랜치로 기록하고 즉시 revert했다.
  - **다음 축**
    - 여전히 root cause는 legality가 아니라 dynamic-function body aggregate chain의 shared cost/boundary misranking이다.
    - 다음은 stable `AggBinary` 전체에 일괄 charge를 거는 대신, loop/body chain에서 반복 local materialization / cumulative effective-cost가 어디서 과소계상되는지 더 좁게 본다.


- **상태: 진행 중 — `logreg / lan / w1 / DP` current blocker는 `X` transient family output-decision global-cost inflation**
  - **fresh trace**
    - `ralph_b2seedcost_trace_logreg_lan_w1_20260311`
    - total runtime `63.460 sec`
    - heavy hitter `uarsqk+ = 57.056 sec`
  - **핵심 fresh evidence**
    - `hop 1 (TRead X)` 단독 decision-map 비교는 여전히 `FOUT`가 더 싸다:
      - `LOUT totalEffective=120563.994894`
      - `FOUT totalEffective=118290.076791`
    - 그런데 family root `hop 50 (TWrite X)`는 propagated readers `[160,200,417,594,770]`와 함께 여전히 `LOUT`를 고른다:
      - iter0 `FOUT totalEffective=124429.484684`
      - iter1 `FOUT totalEffective=1846069.175065`
      - iter2 `FOUT totalEffective=1849287.855282`
  - **현재 해석**
    - active blocker는 legality 부족이 아니다.
    - current DP가 shared `X` family의 `FOUT` choice를 decision-map effective-cost 합산에서 과대처벌하고 있다.
    - runtime hotspot도 뒤쪽 `ba(+*)`보다 앞단 `uarsqk+`가 지배적이므로, 다음 수정 축은 `TWrite X` family output-decision cost aggregation / overlapping-root overcount 쪽이다.
  - **실패 가설 (즉시 폐기)**
    - sibling `TRANSIENTREAD` local-acquire amortization을 넣어보는 가설을 코드로 시도했지만, active blocker code path와 맞지 않아 landing 전에 revert했다.
    - 따라서 다음 branch는 `standalone TRead acquire`가 아니라 `shared TWrite/TRead family decision-map` 쪽으로 유지한다.


- **상태: 해결 — explicit producer-edge `TRANSIENTREAD` aggregate-to-public skip bug를 shared DP fix로 닫음**
  - **root cause**
    - DP aggregate-to-public skip이 explicit federated-source producer edge를 가진 `TRANSIENTREAD`까지 stable fed-init reusable input처럼 취급했다.
    - 그 결과 CP-local aggregate-to-public parent가 `FOUT -> CP` local materialization share를 `0`으로 보고 local `ba(+*)` chain을 과소계상했다.
  - **수정**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java`
      - non-memo legacy path는 유지
      - memoTable-aware path에서만 explicit producer-edge `TRANSIENTREAD`는 stable aggregate-to-public skip 대상에서 제외
    - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
      - federated-source transient-read aggregate-to-public regression 추가
  - **fresh verify**
    - targeted fallback integration regression: pass
    - federated planner component suite: pass
    - `mvn -q -DskipTests compile`: pass
    - isolated `mvn -q -DskipTests package`: pass
  - **fresh runtime evidence**
    - traced rerun `ralph_b2aggsrc_trace_l2svm_lan_w1_20260311`
    - `l2svm / lan / w1 / DP`
      - `64.329303 sec -> 11.081697 sec`
    - hot region이 local `ba(+*)`에서 `fed_ba+*` 중심으로 바뀌었다.
    - trace에서 `hop 465 (ba(+*))` child `toCP`가 `0`에서 `130691.618353`으로 복구되어 FED candidate가 실제 runtime 방향과 맞게 우세해졌다.
  - **collateral guard**
    - `ralph_b2aggsrc_guard_all4_logreg_l2svm_lan_w1_20260311`
    - rule file `experiments/results/matrix_rules/matrix_rules_ralph_b2aggsrc_guard_all4_logreg_l2svm_lan_w1_20260311_20260311_214545_457944.txt`
    - `violations=2`, remaining은 **MinST only**
  - **의미**
    - 이 guard set 기준으로 B2 shared-DP branch는 닫혔다.
    - 현재 simplified `1%` rule에서 `DP`는 `logreg / lan / w1`, `l2svm / lan / w1` 모두 baseline violation이 아니다.
  - **다음**
    - active residual은 B2가 아니라 **B3 MinST parity-follow**로 넘어간다.


- **상태: 실패 후 revert — shared `ExecPlacementPolicy`의 broad `PRIVATE_AGGREGATE` CP/FOUT 개방 브랜치**
  - **가설**
    - 남은 `logreg / lan / w1 / MinST` residual이 shared policy에서 private-aggregate `CP/FOUT` parity candidate를 너무 좁게 닫아서 생긴다고 봤다.
  - **시도한 수정**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java`
      - topology-mismatch 외에도 matrix `PRIVATE_AGGREGATE` hop에 대해 `CP/FOUT`를 더 넓게 열어보는 shared patch를 넣었다.
    - 대응 regression expectation도 `FederatedPlannerFallbackIntegrationTest.java`에 반영했다.
  - **fresh evidence**
    - targeted trace `ralph_b3t_privagg_cpfout_trace_logreg_minst_vs_fout_lan_w1_20260312`
      - MinST runtime은 `~15.72 sec`로 residual이 닫히지 않았다.
    - collateral guard `ralph_b3t_guard_all4_logreg_l2svm_lan_w1_20260312`
      - `DP / logreg / lan / w1`가 `~75.39 sec`로 크게 악화됐다.
      - heavy hitter도 bad DP regime(`m_multiLogReg`, `ba(+*)`)로 되돌아갔다.
  - **판정**
    - shared policy를 넓게 건드린 overreach였다.
    - MinST residual은 못 닫고 DP를 다시 깨서 즉시 revert했다.


- **상태: 실패 후 revert — MinST `repairRequiredLocalInputSelection(...)` parent `CP/FOUT` preference**
  - **가설**
    - `b3u` 이후 남은 `logreg / lan / w1 / MinST` residual은 required-local repair가 parent를 `FED`로 올리기 전에 `CP/FOUT`를 먼저 택하도록 하면 닫힐 수 있다고 봤다.
  - **시도한 수정**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
      - `repairRequiredLocalInputSelection(...)`에서
        - child가 no-local `FED/FOUT`
        - parent가 `allowCP_FOUT`
        - `canGenerateCpfoutCandidateFromFTypes(...)` 가능
      - 이면 parent를 `CP/FOUT`로 먼저 promote하는 branch를 추가했다.
    - 임시 regression test도 `FederatedPlannerFallbackIntegrationTest.java`에 추가했다.
  - **fresh verify**
    - focused tests / planner suite / compile / isolated package는 pass였다.
  - **fresh runtime evidence**
    - targeted trace `ralph_b3v_repaircpfout_trace_logreg_minst_vs_fout_lan_w1_20260312`
    - `logreg / lan / w1 / MinST`
      - `b3u: 15.95 sec`
      - `b3v: 73.68 sec`
    - bad churn이 다시 폭증했다.
      - `fed_ba+* = 2.994 sec x198`
      - `fed_fed_refed = 0.535 sec x198`
    - 그리고 기존 repair chain도 사라졌다.
      - `214 (TWrite Y)` repaired `FED/FOUT` 소실
      - `595 (TRead Y)` repaired `FED/FOUT` 소실
      - `613 (b(*))` repaired `FED/FOUT` 소실
      - final `604/607/608/611/613`가 다시 `CP/LOUT`
  - **판정**
    - locally reasonable해 보였지만 fixed-point 전체를 불안정하게 만들어 bad local chain을 다시 열었다.
    - 즉시 revert했고, 다음 branch는 repair preference가 아니라
      - `604 -> 607 -> 608 -> 611 -> 613`
      chain의 **parity valuation / anchor-feasibility accounting** 쪽으로 유지한다.


- **상태: 실패 후 수정 유지 — MinST `CP/FOUT` parity-follow가 runtime registration hole을 드러냄**
  - **가설**
    - 남은 `logreg / lan / w1 / MinST` residual은 selected `FOUT` consumer chain을 따라 `CP/FOUT`를 backward-propagate하면 닫힐 수 있다고 봤다.
  - **시도한 수정 (`b3x`)**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
      - `repairSelectionFixpoint(...)` 안에 `repairCpfoutPropagationSelection(...)` 계열 `CP/FOUT` parity pass를 추가했다.
  - **fresh runtime evidence**
    - targeted trace:
      - `ralph_b3x_cpfoutchain_trace_logreg_minst_vs_fout_lan_w1_20260312`
    - failure:
      - `MinST plan requires CP->FOUT for hop 222 (rix) but no refed/materialize entry was registered.`
  - **원인**
    - graph repair는 `CP/FOUT`를 선택했지만, `FederatedPlanMinSTCut`는 MinST가 선택한 `CP/FOUT` hop을 runtime/consistency validation 전에 materialize candidate로 등록하지 않았다.
  - **판정**
    - 이 branch는 잘못된 shortcut이 아니라 실제 **runtime correctness hole**을 드러냈다.
    - 따라서 parity 방향은 유지하고, MinST-side `CP/FOUT` registration을 추가하는 쪽으로 진행했다.


- **상태: 실패 후 수정 유지 — MinST `CP/FOUT` registration만 추가하면 `requiredLocal` oscillation이 남음**
  - **시도한 수정 (`b3y`)**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCut.java`
      - `registerMinstCpfoutSelections(...)` 추가
      - `rewriteProgram(...)`, `rewriteFunctionDynamic(...)`에서
        `FederatedRefedPolicy.repairResolvedHopSelections(...)` 직후 호출
  - **fresh runtime evidence**
    - targeted trace:
      - `ralph_b3y_cpfoutreg_trace_logreg_minst_vs_fout_lan_w1_20260312`
    - 기존 registration error는 사라졌지만 새 runtime failure가 발생했다:
      - `.builtinNS::m_multiLogReg`
      - `FED?rexpand ... NullPointerException ... fedMap is null`
  - **trace diagnosis**
    - `hop 1045 (REXPAND)`가
      - parity pass에서는 `CP/FOUT`
      - `requiredLocal` repair에서는 다시 `FED/FOUT`
      로 되돌아가며 fixed-point oscillation이 생겼다.
    - 같은 패턴이 `265`, `603` 등 주변 chain에도 남아 있었다.
  - **판정**
    - MinST-side `CP/FOUT` registration 자체는 필요하고 유지한다.
    - 남은 문제는 registration이 아니라 `requiredLocal` repair와 new parity-follow branch의 상호작용이었다.


- **상태: 해결 — stabilized MinST `CP/FOUT` parity-follow가 `workers=1 / lan / {logreg,l2svm}` guarded residual을 닫음**
  - **최종 유지 patch (`b3z`)**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
      - kept:
        - `repairCpfoutPropagationSelection(...)`
        - `shouldSkipCpfoutPropagationRepair(...)`
        - `hasSelectedFoutConsumerOpportunity(...)`
        - `getAdjustedCpFoutType(...)`
      - 그리고 `repairRequiredLocalInputSelection(...)`를 좁혀
        - selected `FOUT` consumer chain을 먹이는 `CP/FOUT` support node는
        - 즉시 다시 `FED`로 re-promote하지 않도록 안정화했다.
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCut.java`
      - `registerMinstCpfoutSelections(...)` 유지
    - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
      - `CP/FOUT` parity promotion/demotion, preservation, registration regression tests 추가
  - **fresh verify**
    - focused fallback integration subset pass
    - `FederatedPlanMinSTHyperedgeTest,FederatedPlanMinSTRewireTest,FederatedPlannerFallbackIntegrationTest` pass
    - `mvn -q -DskipTests compile` pass
    - isolated `mvn -q -DskipTests package` pass
  - **fresh trace**
    - `ralph_b3z_stablecpfout_trace_logreg_minst_vs_fout_lan_w1_20260312`
    - runtimes:
      - `MinST / logreg / lan / w1 = 14.68 sec`
      - `FedAll / logreg / lan / w1 = 15.94 sec`
    - rule check:
      - `violations=0`, `warnings=0`
    - decisive final-selects:
      - `1045 (REXPAND) -> CP/FOUT`
      - `222 (rix) -> CP/FOUT`
      - `265/267 -> CP/FOUT`
      - `286 -> FED/FOUT`
    - `b3y`에서 보이던 unstable `rexpand` runtime crash는 재발하지 않았다.
  - **fresh collateral guard**
    - `ralph_b3z_stablecpfout_guard_all4_logreg_l2svm_lan_w1_20260312`
    - all 8 cells success
    - fresh simplified rule:
      - `violations=0`
      - `warnings=0`
    - runtime summary:
      - `DP / logreg / lan / w1 = 9.844 sec`
      - `MinST / logreg / lan / w1 = 9.809 sec`
      - `Heuristic / logreg / lan / w1 = 10.034 sec`
      - `FedAll / logreg / lan / w1 = 10.868 sec`
      - `DP / l2svm / lan / w1 = 4.568 sec`
      - `MinST / l2svm / lan / w1 = 6.371 sec`
      - `Heuristic / l2svm / lan / w1 = 15.919 sec`
      - `FedAll / l2svm / lan / w1 = 15.428 sec`
  - **의미**
    - `workers=1 / lan / {logreg,l2svm}` residual은 current code 기준으로 닫혔다.
    - 이건 workload heuristic이 아니라
      - selected `FOUT` consumer chain
      - MinST-side `CP/FOUT` legality registration
      - required-local repair stabilization
      을 맞춘 **MinST parity-follow correctness + valuation fix**다.
  - **다음**
    - B3 active target는 다시 multi-worker MinST residual, 특히 `kmeans / lan`으로 이동한다.


- **상태: 진행 중 — `kmeans / lan / w2 / MinST` legality는 복구됐지만 parity gap은 남음**
  - **유지한 patch**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
      - selected `FED/FOUT` consumer에 대해
        heavy looped producer의 weighted upload가 federated unary보다 크면
        `CP/FOUT` back-propagation을 skip하도록 cost gate 추가
    - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
      - local `TRANSIENTREAD`가 여전히 planned `FED` parent를 먹이면
        prune 단계에서 materialize registration을 지우지 않도록 보정
    - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
      - heavy-looped parity skip regression 수정
      - local `TRANSIENTREAD` prune regression 추가
  - **fresh verify**
    - focused fallback subset pass
    - `FederatedPlanMinSTHyperedgeTest,FederatedPlanMinSTRewireTest,FederatedPlannerFallbackIntegrationTest` pass
    - `mvn -q -DskipTests compile` pass
    - isolated `mvn -q -DskipTests package` pass
  - **fresh runtime evidence**
    - 1차 trace:
      - `ralph_b3k2_costgate_trace_kmeans_minst_vs_fout_lan_w2_20260312`
      - `263/286`의 direct selected-`FED` consumer parity는 막혔지만
      - `X_samples` transient-read materialize가 prune되어
        `FED ba+* requires at least one federated input but both are local` runtime error 발생
    - 2차 trace:
      - `ralph_b3k3_costgate_prunefix_trace_kmeans_minst_vs_fout_lan_w2_20260312`
      - legality 회복:
        - `186/223 (X_samples)` 유지
        - `263/286` 유지
      - 하지만 runtime은 아직 큼:
        - `MinST / kmeans / lan / w2 = 110.828754 sec`
        - `FedAll / kmeans / lan / w2 = 18.118129 sec`
      - heavy hitter:
        - `fed_fed_fout = 52.829 sec (100x)`
        - `fed_fed_refed = 0.141 sec (50x)`
      - decisive final-select:
        - `185`, `363`, `424`는 아직 `CP/FOUT`
        - `362/364`, `423/425/426`도 계속 `CP/FOUT`
  - **현재 해석**
    - direct selected-`FED` consumer에 대한 over-propagation은 일부 잡혔다.
    - 남은 주범은
      - heavy looped producer가
      - immediate parent가 아니라
      - `TRANSIENTWRITE -> TRANSIENTREAD`를 포함한 **transitive selected-`FOUT` chain**
      때문에 계속 `CP/FOUT`로 끌려가는 문제다.
  - **다음**
    - `kmeans / lan`에서는
      - immediate consumer만 보는 parity rule이 아니라
      - future/transitive `FOUT` consumer chain을 보는 refinement로 넘어간다.

- **상태: 진행 중 — `363` required-local oscillation은 닫혔고, active residue는 `X_samples` family로 이동**
  - **유지한 patch**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
      - `CP/FOUT` parity gate가 현재 child 하나만 보지 않고
        parent의 **모든 active required-local child upload cost**를 같이 본다.
    - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
      - `testMinSTRepairFixpointAggregatesRequiredLocalChildUploadsAcrossSelectedFoutChain` 추가
  - **fresh verify**
    - focused fallback subset pass
    - `FederatedPlanMinSTHyperedgeTest,FederatedPlanMinSTRewireTest,FederatedPlannerFallbackIntegrationTest` pass
    - `mvn -q -DskipTests compile` pass
    - isolated `mvn -q -DskipTests package` pass
  - **fresh runtime evidence**
    - run:
      - `ralph_b3k6_reqchildmax_trace_kmeans_minst_vs_fout_lan_w2_20260312`
    - result:
      - `MinST / kmeans / lan / w2 = 95.349064 sec`
      - `FedAll / kmeans / lan / w2 = 18.456174 sec`
      - 2-run pair rule check: `violations=0`, `warnings=0`
    - 닫힌 부분:
      - `hop 363`이 더 이상 `FED/LOUT`로 떨어지지 않고 `FED/FOUT`로 유지
      - `fed_fed_fout` count도 `214 -> 160`으로 감소
    - 그런데 총 runtime은 거의 안 줄었다.
      - 새 dominant residue는 downstream `363/424`가 아니라
        upstream `X_samples` family:
        - `185 -> 186 -> 223 -> 263 -> 287 -> 288`
      - current MinST runtime state:
        - `223 = FED/FOUT`
        - `263 = FED/LOUT`
        - `287 = FED/FOUT`
        - `288 = FED/LOUT`
      - paired `mkl-fout` oracle baseline:
        - `223 = CP/LOUT`
        - `263 = CP/FOUT`
        - `287 = CP/FOUT`
        - `288 = CP/FOUT`
  - **현재 해석**
    - 이번 patch는 맞는 fix다. cheap sibling child 때문에 `363`가 잘못 `FED/LOUT`로 repair되던 oscillation은 닫혔다.
    - 하지만 핵심 parity gap은 이제
      - `363/424` chain이 아니라
      - **self-anchored `TRANSIENTWRITE -> TRANSIENTREAD` `X_samples` family**다.
    - 즉 다음 분기는 `MinSTGraph`가 아니라
      - `FederatedRefedPolicy.demoteStaleTransientWriteFederatedSelections(...)`
      - `hasLiveFederatedTransientReadConsumer(...)`
      쪽으로 이동한다.

- **상태: 유지 — `already-FOUT` raw selection을 `CP/FOUT`로 다시 쓰지 않도록 해서 `kmeans / lan / w1` MinST를 닫음**
  - **수정**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
      - `repairCpfoutPropagationSelection(...)`에서 현재 selection이 이미 `FOUT`이면 CP/FOUT propagation을 건너뛰도록 수정
      - 의미:
        - selected `FOUT` consumer chain을 만족시키기 위한 propagation은
          upstream에 `FOUT` producer가 *없을 때만* 필요하다
        - raw choice가 이미 `FED/FOUT`이면 legality/ranking상 valid한 `FOUT` producer가 있는 상태이므로
          이를 `CP/FOUT`로 rewrite하면 runtime만 악화시킨다
    - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
      - already-satisfied `FED/FOUT` producer가 `CP/FOUT`로 demote되지 않는 regression으로 갱신
  - **fresh verify**
    - focused fallback subset pass
    - `FederatedPlanMinSTHyperedgeTest,FederatedPlanMinSTRewireTest,FederatedPlannerFallbackIntegrationTest` pass
    - `mvn -q -DskipTests compile` pass
    - isolated `mvn -q -DskipTests package` pass
  - **fresh runtime evidence**
    - trace:
      - `ralph_b3k9_skipfedfout_trace_kmeans_minst_vs_fout_lan_w1_20260312`
      - `MinST / kmeans / lan / w1 = 27.669691 sec`
      - `FedAll / kmeans / lan / w1 = 35.255176 sec`
      - pair rule check: `violations=0`, `warnings=0`
    - trace proof:
      - old bad line `MinST-CpFout-Repair ... switch 185 to CP/FOUT ...`는 `hop 185`에서 사라짐
      - fresh final:
        - `hop 185 (ba(+*)) raw=FED/FOUT ... final=FED/FOUT repaired=false`
  - **collateral guards**
    - `ralph_b3k9_guard_kmeans_lan_w1234_20260312`
      - `8/8 success`
      - `violations=0`, `warnings=0`
      - `w1..w4` 모두 `MinST <= FedAll`
    - `ralph_b3k9_guard_logreg_l2svm_lan_w1_20260312`
      - `4/4 success`
      - `violations=0`, `warnings=0`
  - **해석**
    - B3 guarded residual은 current code 기준으로 닫혔다.
    - 이 fix는 workload heuristic이 아니라
      - MinST repair parity correction
      - raw selected `FOUT` chain preservation
      이다.
  - **다음**
    - fresh full sweep:
      - `ralph_postb3_fullsweep_allw_allplanners_20260312`
    - 이 sweep 이후 simplified audit(`lm` exempt, `pca` DP-only)로 final live residual set을 다시 산출한다.

- **상태: revert — DP decision-seed same-output compatible-child marginal repricing branch는 유지하지 않음**
  - **시도한 변경**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
    - 목적:
      - decision-seed union cost에서 parent marginal cost가 stale primary child variant를 빼고
      - recursive child seed에서 repriced compatible child를 다시 더해서
      - deeper same-output compatible variant delta를 이중계상하는지 확인
  - **좋았던 결과**
    - `ralph_b1seedmarg_trace_pca_lan_w1_dp_vs_fout_20260312`
    - `pca / lan / w1 / DP = 69.910725 sec`
    - overwritten-`X` chain이 크게 좋아졌다:
      - `224 = FED/FOUT`
      - `225 (TWrite X) = FED/FOUT`
      - `74 (TRead X) = FED/FOUT`
    - `hop 75` decision-map global cost도 마지막에는 `LOUT == FOUT` tie까지 내려왔다.
  - **막힌 이유**
    - same branch가
      - `ralph_b1seedmarg_trace_logreg_lan_w2_dp_vs_fout_20260312`
      에서 `logreg / lan / w2 / DP` legality mismatch를 다시 열었다.
    - fatal:
      - `FED indexing requires federated input but found local at runtime`
    - bad state:
      - `214 (TWrite Y) = FED/FOUT`
      - `595 (TRead Y) = FED/FOUT`
      - 하지만 다른 live `Y` branch는
        - `201 (TRead Y) = CP/LOUT`
        - `1079 (REXPAND) = CP/LOUT`
      로 남아 `FED rightIndex`가 local `Y`를 집게 됐다.
  - **follow-up refinement**
    - function-boundary-only gate도 바로 시도했지만
    - 그건 `logreg` regression은 피했어도
    - `pca`가 다시 old local `224 -> 225 -> 74`로 돌아가서 neutral이었다.
  - **현재 코드 상태**
    - 위 두 branch는 둘 다 revert
    - safe baseline으로 복귀
    - fresh verify:
      - targeted fallback tests pass
      - `mvn -q -DskipTests compile` pass
      - isolated `mvn -q -DskipTests package` pass
  - **현재 해석**
    - PCA 개선 신호 자체는 진짜였지만,
    - 이번 patch 형태는 너무 broad해서 `logreg` `Y` family legality/state alignment를 깨뜨렸다.
    - 따라서 다음 분기는
      - PCA overwritten-`X` / function-boundary / root-seed 쪽을 더 좁게
      - `logreg / lan / w2` guard를 깨지 않도록
      다시 나눠서 봐야 한다.

- **상태: revert — DP nonconverged family-merge branch는 유지하지 않음**
  - **시도한 변경**
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
    - nonconverged output-decision에서 latest bounded map 위에 `bestVisited` transient-write family를 greedy merge
  - **fresh negative evidence**
    - `ralph_familymerge_trace_pca_lan_w1_dp_vs_fout_20260312`
      - `pca / lan / w1 / DP = 143.902831 sec`
      - `FedAll = 64.647597 sec`
    - `ralph_familymerge_trace_kmeans_lan_w2_dp_vs_fout_20260312`
      - `kmeans / lan / w2 / DP = 32.369172 sec`
      - `FedAll = 22.983491 sec`
    - `ralph_familymerge_guard_logreg_lan_w2_all4_20260312`
      - legality는 유지됐지만
      - `DP = 23.351867 sec`
      - `Heuristic = 16.483833 sec`
      - `FedAll = 16.736729 sec`
      로 residual만 남겼다.
  - **조치**
    - family-merge refinement는 revert
    - 현재 코드는 다시
      - nonconverged면 latest bounded map 사용
      - `bestVisited` transient-write family 재병합은 하지 않음
  - **fresh revert verify**
    - targeted fallback tests pass
    - `mvn -q -DskipTests compile` pass
    - isolated `mvn -q -DskipTests package` pass
  - **fresh current-code reconfirm**
    - `ralph_nonconvlatest_reconfirm_pca_lan_w1_dp_vs_fout_20260312`
      - `DP = 76.210433 sec`
      - `FedAll = 76.096634 sec`
    - `ralph_nonconvlatest_reconfirm_kmeans_lan_w2_dp_vs_fout_20260312`
      - `DP = 30.686732 sec`
      - `FedAll = 22.278078 sec`
    - `ralph_nonconvlatest_guard_logreg_lan_w2_all4_20260312`
      - all 4 success
      - old legality crash는 재발하지 않음
      - 하지만 `DP = 23.153929 sec`로 여전히 baseline보다 큼
  - **현재 해석**
    - bestVisited family merge는 current code 기준 shared improvement가 아니었다.
    - 다음 분기는 output-decision family merge가 아니라
      - output/download memory accounting
      - worker fan-in cost semantics
      쪽 shared model로 pivot한다.

- **상태: revert — mixed-conflict global-cost reeval branch도 유지하지 않음**
  - **유지한 shared fix**
    - `FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(...)`
    - multi-return builtin source-aware fallback은
      - transient read가 아직 unresolved일 때만 source mem을 쓰고
      - concrete reader mem이 생기면 reader estimate를 유지하도록 좁혔다.
  - **시도한 follow-up**
    - `FederatedPlannerDpFedCostBased.java`
    - second-pass output-decision에서
      - seen-only conflict뿐 아니라
      - transient-neighborhood / compatible-variant mixed conflict도
      global decision-map cost로 고르도록 넓혔다.
  - **fresh targeted evidence**
    - `ralph_b6globalmixed_trace_pca_lan_w1_dp_vs_fout_20260312`
      - `pca / lan / w1 / DP = 122.890865 sec`
      - `FedAll = 64.513168 sec`
      - `hop 209 (REPLACE)`는 `iter0`만 `FOUT`, `iter1/2`는 다시 `LOUT`
      - `RecompileRestoreHop hopID=245`도 계속 `CP/LOUT`
    - `ralph_b6globalmixed_guard_logreg_lan_w2_all4_20260312`
      - `mkl-cost / logreg / lan / w2`가 다시 crash
      - fatal:
        - `FED indexing requires federated input but found local at runtime`
        - failing op: `FED rightIndex` on runtime-local `P`
  - **조치**
    - mixed-conflict global-cost broadening은 revert
    - 현재 코드는 guarded source-aware mem fix만 유지
    - transient-neighborhood / compatible-variant mixed conflict에는 다시 broad global-cost override를 쓰지 않음
  - **현재 해석**
    - source-aware mem fix 자체는 맞다.
    - 하지만 output-decision broadening은
      - PCA를 닫지 못했고
      - `logreg / lan / w2` legality mismatch를 재개방했다.
    - 다음 분기는 넓은 second-pass override가 아니라
      - 더 좁은 shared model / boundary / materialization pricing
      쪽으로 유지한다.

- **상태: revert — grouped-runtime follow-up 3개 모두 폐기**
  - **B9 transient-write-family computed-download amortization**
    - 시도:
      - computed `FED/FOUT -> CP` materialization을 downstream local sibling `TRead` family까지 amortize
    - fresh negative evidence:
      - `ralph_b9_twfam_trace_pca_dp_vs_fout_lan_w1_20260312`
        - `DP = 140.271094 sec`
        - `FedAll = 68.780257 sec`
      - `ralph_b9_twfam_guard_logreg_lan_w2_all4_20260312`
        - `DP = 24.118936 sec`
        - `MinST = 16.999313 sec`
        - `Heuristic = 16.659034 sec`
        - `FedAll = 16.574358 sec`
    - 조치:
      - `FederatedPlannerDpCostEstimator.java`
      - `FederatedPlannerFallbackIntegrationTest.java`
      - 둘 다 revert

  - **B10 control-plane-only refed**
    - 시도:
      - `FederatedCostModel.computeRefedNetworkCost(...)`를 payload upload 대신 control-plane only로 변경
    - fresh negative evidence:
      - `ralph_b10_refedctrl_trace_kmeans_dp_vs_fout_lan_w2_20260312`
        - first completed cell `DP = 33.031727 sec`
        - safe baseline reconfirm `30.686732 sec`보다 악화
      - `ralph_b10_refedctrl_guard_logreg_lan_w2_all4_20260312`
        - first completed cell `DP = 25.755652 sec`
        - safe baseline reconfirm `23.153929 sec`보다 악화
    - 조치:
      - `FederatedCostModel.java`
      - `FederatedCostModelFallbackTest.java`
      - 둘 다 revert

  - **B11 hidden FunctionOp matrix roots as additional roots only**
    - 시도:
      - hidden/unescaped function-body matrix outputs를 caller-visible `FunctionOp` output list에서 빼고 additional roots로만 유지
    - fresh negative evidence:
      - `ralph_b11_hiddenroots_guard_logreg_lan_w2_all4_20260312`
      - `mkl-cost / logreg / lan / w2` legality crash 재개:
        - `FED indexing requires federated input but found local at runtime`
        - failing op: `FED rightIndex` inside `.builtinNS::m_multiLogReg`
    - 조치:
      - `FederatedPlannerDpRewireTransTable.java`
      - `FederatedPlannerFallbackIntegrationTest.java`
      - 둘 다 revert

  - **현재 live 해석**
    - safe baseline만 source of truth
    - live residual:
      - `pca` DP open
      - `kmeans` DP open
      - `logreg / lan / w2` legality는 safe baseline에서만 닫혀 있고 simplified-rule overrun은 여전히 open
    - 다음 패치는 위 3개보다 더 좁아야 한다.

- **상태: revert — B12 metadata-only `producer FED/FOUT -> TRANSIENTWRITE` refed boundary**
  - 시도:
    - global refed model은 유지하고,
    - `producer -> TWrite` 경계에서만 payload-sized refed upload를 metadata/control-plane cost로 낮춤
  - fresh evidence:
    - `ralph_b12_twmeta_trace_kmeans_lan_w2_20260312`
      - `DP = 18.695 sec`
      - `FedAll = 16.760 sec`
      - safe baseline `30.686732 sec` 대비 큰 개선
    - `ralph_b12_twmeta_guard_logreg_lan_w2_dp3_20260312`
      - legality crash 미재발
      - `DP = 12.649 sec`
      - `Heuristic = 11.464 sec`
      - `FedAll = 10.947 sec`
    - `ralph_b12_twmeta_guard_pca_lan_w1_pair_20260312`
      - `DP = 137.935 sec`
      - `FedAll = 62.510 sec`
      - PCA DP가 심하게 local path로 무너짐
  - 조치:
    - `FederatedPlannerDpCostEstimator.java`
    - `FederatedPlannerFallbackIntegrationTest.java`
    - 모두 revert
  - 결론:
    - `kmeans`가 `producer -> TWrite` refed overcharge에 민감하다는 진단 자체는 맞다.
    - 하지만 이 경계 전체를 metadata-only로 내리는 건 아직 too broad하다.
    - 다음은 다시 stage-diff 기반으로
      - `PCA`: caller-visible vs hidden function-output seed accounting
      - `kmeans`: `X_samples` family 쪽 더 좁은 pricing / fan-in 분리
      로 진행한다.

- **상태: revert — B13 caller-visible vs hidden function-output seed split**
  - 시도:
    - caller-visible `FunctionOp` root seed와 hidden function outputs를 cost-accounting에서 분리해서
    - PCA `FunctionOp` root pollution을 줄인다.
  - fresh evidence:
    - `ralph_b13_funoutseed_trace_pca_lan_w1_pair_20260312`
      - `mkl-cost / pca / lan / w1 = 137.423 sec`
      - trace에서 의도한 변화는 맞게 관측:
        - `rootHop=175 (fcall .builtinNS m_pca)` seed cost가 `~1665.961847`까지 낮아짐
      - 그러나 실제 dominant blocker는 `rootHop=90 (TWrite XReduced)`로 이동했고
        - `hop 90 = LOUT`
        - `hop 75 = LOUT`
        로 남아서 PCA DP runtime이 크게 악화
      - paired `mkl-fout` trace leg는 hang으로 중단했지만,
        patch가 DP 전용 seed-accounting 변경이고 DP runtime이 safe baseline보다 훨씬 나빠졌기 때문에 revert 판단에는 영향 없음
    - `ralph_b13_funoutseed_guard_logreg_lan_w2_dp3_20260312`
      - legality crash 미재발
      - `DP = 12.055 sec`
      - `Heuristic = 12.110 sec`
      - `FedAll = 11.100 sec`
    - `ralph_b13_funoutseed_guard_kmeans_lan_w2_pair_20260312`
      - immediate collateral regression 없음
      - `DP = 18.113 sec`
      - `FedAll = 16.967 sec`
  - 조치:
    - `FederatedPlannerDpRewireTransTable.java`
    - `FederatedPlannerDpMemoTable.java`
    - `FederatedPlannerDpCostEnumerator.java`
    - `FederatedPlannerDpFedCostBased.java`
    - 전부 revert
  - 결론:
    - B13은 채택하지 않는다.
    - 다만 진단은 유효하다:
      - PCA current blocker는 broad `FunctionOp` root pollution보다
      - `hop 90 / XReduced` hidden-output root pricing 쪽이 더 직접적이다.
    - 다음은
      - `PCA`: `XReduced` root/output pricing
      - `kmeans`: `X_samples` `TWrite/TRead` family drift + fan-in pricing
      쪽으로 진행한다.

- **상태: revert — B14 dead caller-visible function-output boundary suppression**
  - 시도:
    - caller-visible dead outputs가 synthetic function-boundary seed/forwarding 비용을 더 이상 내지 않도록 해서
    - PCA `FunctionOp` 바깥 boundary inflation만 걷어낸다.
  - fresh evidence:
    - 1차:
      - `ralph_b14_deadfunout_trace_pca_lan_w1_pair_20260313`
      - `mkl-cost / pca / lan / w1 = 136.121294 sec`
    - refine:
      - `ralph_b14c_deadfunout_noboundary2_trace_pca_lan_w1_pair_20260313`
      - `mkl-cost / pca / lan / w1 = 112.131916 sec`
      - pair summary는 `2/2 success`, `0 violation`, `0 warning`
      - 하지만 final rewrite state는 여전히 bad local chain:
        - `hop 90 (TWrite XReduced) = CP/LOUT`
        - `hop 225 (TWrite X) = CP/LOUT`
        - `hop 74 (TRead X) = CP/LOUT`
        - `hop 75 (ba(+*)) = CP/LOUT`
    - collateral:
      - `ralph_b14_deadfunout_guard_logreg_lan_w2_dp3_20260313`
      - `ralph_b14_deadfunout_guard_kmeans_lan_w2_pair_20260313`
      - 둘 다 새 legality/runtime 회귀 없음
  - 조치:
    - dead caller-visible output suppression branch는 revert
  - 결론:
    - caller-visible dead-output boundary는 PCA current blocker가 아니었다.
    - live blocker는 function 내부:
      - `hop 90 / XReduced`
      - `225 -> 74 -> 75`
      쪽에 남는다.
    - 따라서 다음 PCA branch는 dead-output boundary가 아니라
      - function-internal `XReduced` root/output pricing
      - 또는 `225 -> 74 -> 75` seed/variant propagation
      으로 이동한다.

- **상태 정정: synchronized current code 기준 PCA DP는 closed**
  - fresh evidence:
    - `ralph_b15sync_baseline_trace_pca_w1_pair_20260313`
    - `DP = 58.969786 sec`
    - `FedAll = 58.665977 sec`
  - 해석:
    - 이전 PCA reopen 신호는 stale isolated runtime tree confound였다.
    - 현재 live DP residual set에서 `PCA`는 제거한다.

- **상태: revert — B16d clone additional-root seed normalization**
  - 의도:
    - planning-only virtual clone additional roots가 decision seed costing에서
      inflated clone cumulative cost를 밀어넣는다고 보고,
      original executable root identity로 normalize
  - fresh verify:
    - focused regression pass
    - compile pass
    - synchronized isolated package pass
  - fresh evidence:
    - tried:
      - `ralph_b16d_trace_kmeans_w2_pair_20260313`
      - `kmeans / lan / w2 / DP = 19.356 sec`
      - `FedAll = 17.443 sec`
    - revert-confirm:
      - `ralph_b16d_revertconfirm_kmeans_w2_pair_20260313`
      - `kmeans / lan / w2 / DP = 18.558 sec`
      - `FedAll = 17.407 sec`
    - trace상 decisive signal은 변하지 않았다:
      - hidden root `1041` normalize 여부와 무관하게
      - visible roots
        - `770 / PWrite Y_n`
        - `763 / fcall .builtinNS m_kmeans`
      가 `186 / 190 / 649 / 764` family decision-map seed cost를 지배한다.
  - 조치:
    - branch revert
  - 현재 live blocker 재정의:
    - `kmeans / lan / w2 / DP`
    - `logreg / lan / w2 / DP` small residual / collateral only
  - 다음 분기:
    - hidden clone-root normalization 재시도 금지
    - `PWrite Y_n` / `m_kmeans` visible-root seed aggregation
    - transient family materialization / fan-in reuse
    쪽 shared branch로 이동

- **상태: revert — B18 hidden clone-root marginalization probe**
  - 의도:
    - virtual additional root가 explicit desired output 없이 decision-map global binding을 받을 때
      clone-local cheapest seed를 강제로 잃는지 확인
    - probe 방식:
      - virtual additional root + explicit desired output 없음
      - `selectDecisionSeedPlan(...)`에서 global output-decision map을 무시하고 local cheapest seed 사용
  - touched file (reverted):
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - fresh verify:
    - focused fallback tests pass
    - compile pass
    - synchronized isolated package pass
  - fresh evidence:
    - `kmeans` pair:
      - `ralph_b18_clonehidden_probe_kmeans_pair_20260313`
      - `DP = 19.263 sec`
      - `FedAll = 17.349 sec`
      - safe baseline(`ralph_b16d_revertconfirm_kmeans_w2_pair_20260313`)의
        `DP = 18.558 sec`, `FedAll = 17.407 sec`보다 악화
    - `logreg` all4:
      - `ralph_b18_clonehidden_probe_logreg_w2_all4_20260313`
      - `DP = 12.713 sec`
      - `MinST = 12.272 sec`
      - `Heuristic = 12.277 sec`
      - `FedAll = 12.181 sec`
      - legality crash 없음
      - simplified 1% rule residual 1건만 남음
        - `DP 12.713 > 12.30281`
  - 해석:
    - hidden additional-root binding이 `logreg` small residual에는 실제로 일부 관여한다.
    - 하지만 virtual additional root 전체에 대해 global decision pressure를 제거하면 `kmeans`가 악화된다.
  - 조치:
    - branch revert
  - 다음 분기:
    - hidden clone-root 전체 marginalization 재시도 금지
    - visible-path decision binding과 hidden executed clone-root costing을 분리하는 더 좁은 branch로 이동

- **상태: revert — B20 hidden clone-member transient-family propagation**
  - 의도:
    - merged `TRANSIENTREAD` conflict entry에서 visible member와 hidden clone member가
      동일한 unique `TRANSIENTWRITE` origin을 읽는 경우,
      exact clone member hop id까지 transient family에 넣어서 additional-root seed costing이
      visible path와 같은 family decision을 보게 만들기
  - touched files (reverted):
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
    - `tmp/systemds-local/src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - fresh verify after revert:
    - focused fallback tests pass
    - compile pass
    - synchronized isolated package pass
    - diagnostics `0`
    - `git diff --check` pass
  - fresh evidence:
    - traced `kmeans` pair:
      - `ralph_b20_hiddenclone_trace_kmeans_w2_dp_vs_heur_20260313_20260313_114133_1061035`
      - `DP = 19.310 sec`
      - `Heuristic = 16.569 sec`
      - kept `b19` 기준 (`DP 17.586 sec`, `FedAll 16.986 sec`)보다 악화
      - trace상 patch 자체는 실제로 적용됨:
        - `hop 186 / TWrite X_samples`에서 `linkedTReads=[1040, 223]`
      - 하지만 decisive drift는 그대로 남음:
        - `hop 223 / TRead X_samples`: `iter0 FOUT`, `iter1+ LOUT`
        - `hop 214 / TRead X_samples_sq_norms`: `iter0 FOUT`, `iter1+ LOUT`
    - collateral `logreg` all4:
      - `ralph_b20_hiddenclone_guard_logreg_w2_all4_20260313_20260313_114133_1061036`
      - `DP = 12.453 sec`
      - `MinST = 12.391 sec`
      - `Heuristic = 11.068 sec`
      - `FedAll = 11.671 sec`
      - legality crash는 재발하지 않았지만
      - simplified 1% rule에서는 `DP`, `MinST` 둘 다 다시 fail
  - 해석:
    - hidden clone member hop id를 family에 포함시키는 것만으로는 `kmeans` DP residual이 닫히지 않는다.
    - 오히려 `kmeans`와 `logreg` 모두 performance baseline을 악화시켰다.
  - 조치:
    - branch revert
  - 다음 분기:
    - `b19`를 accepted baseline으로 유지
    - hidden clone member 전체 전파 재시도 금지
    - 다음 fix는 shared pricing/state 축으로만 이동:
      - visible-root seed aggregation
      - transient family materialization
      - worker fan-in / local sink accounting

- **상태: revert — B21 CP-parent / CP-FOUT synthetic-download zero-share**
  - 의도:
    - base DP estimator는 `CP parent <- CP/FOUT child`를 zero extra download로 다루는데,
      output-decision / seed-union helper `computeParentChildForwardingCostShare(...)`는 같은 경우에
      synthetic `FOUT -> CP` share를 계속 더하고 있어서 `kmeans` local-sink root를 과대처벌한다고 가정
  - touched files (reverted):
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
    - `tmp/systemds-local/src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - fresh evidence before revert:
    - `ralph_b21_cpfoutcp_guard_logreg_w2_all4_20260313_20260313_143855_1287403`
      - `DP = 22.148 sec`
      - `MinST = 18.827 sec`
      - `Heuristic = 16.337 sec`
      - `FedAll = 16.550 sec`
      - simplified **5%** rule에서도 `DP`, `MinST` 모두 fail
    - `ralph_b21_cpfoutcp_guard_kmeans_w234_all4_20260313_20260313_143855_1287408`
      - `w2 / DP = 29.349 sec`
      - `w3 / DP = 27.775 sec`
      - `w4 / DP = 28.604 sec`
      - kept `b19` baseline보다 명확히 악화
  - read-only audit:
    - helper zero-share 자체는 local consistency fix일 수 있지만,
    - 동일 계열 synthetic pressure가 `FederatedPlannerDpCostEnumerator` exact-hop delta에도 남아 있고,
    - 실제 decisive penalty는 여전히 root-seed union / decision-map effective-cost 누적에서 나온다.
  - 조치:
    - branch revert
  - 다음 분기:
    - `DP FedCostBased` 단일 helper를 또 완화하지 않는다.
    - 다음 fix는
      - local-sink root seed aggregation
      - additional-root marginal accumulation
      - exact-hop enumerator parity
      축을 함께 보는 shared branch로 이동

- **상태: keep — B22 exact-hop / output-decision CP-FOUT parity**
  - 의도:
    - base child-cost estimator와 later rescoring 사이의 parity mismatch를 닫기
    - 즉 `CP parent <- CP/FOUT child` synthetic download를
      - `DP FedCostBased` helper
      - `DP CostEnumerator` exact-hop helper
      둘 다 base estimator와 동일하게 처리
  - touched files:
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
    - `tmp/systemds-local/src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - fresh verify:
    - focused fallback tests pass
    - compile pass
    - synchronized isolated package pass
    - diagnostics `0`
  - fresh evidence:
    - `ralph_b22_exactparity_guard_logreg_w2_all4_20260313_20260313_145338_1336045`
      - `DP = 11.960 sec`
      - `MinST = 12.876 sec`
      - `Heuristic = 12.083 sec`
      - `FedAll = 12.800 sec`
      - 5% rule 기준 `DP` pass, `MinST`만 residual
    - `ralph_b22_exactparity_guard_kmeans_w234_all4_20260313_20260313_145338_1336050`
      - `w2`: `DP = 16.855 sec`, `MinST = 18.079 sec`, `Heuristic = 18.159 sec`, `FedAll = 16.685 sec`
      - `w3`: `DP = 15.763 sec`, `MinST = 16.236 sec`, `Heuristic = 16.171 sec`, `FedAll = 15.483 sec`
      - `w4`: `DP = 14.911 sec`, `MinST = 16.277 sec`, `Heuristic = 15.329 sec`, `FedAll = 15.279 sec`
      - 5% rule 기준 `DP w2,w3,w4` 전부 pass
      - 남은 것은 `MinST w2`, `MinST w4`
  - 해석:
    - `kmeans DP` blocker는 broad root-seed redesign이 아니라
      exact-hop / output-decision parity mismatch였다.
  - 다음 분기:
    - **DP branch 종료**
    - 다음 active branch는 `MinST parity-follow`
      - `kmeans / lan / w2,w4`
      - small `logreg / lan / w2 / MinST`

- **상태: keep — B24b MinST raw `FED/LOUT` promotion on selected FOUT chain**
  - touched files:
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
    - `tmp/systemds-local/src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - fresh trace:
    - `ralph_b24b_fixtrace_logreg_minst_vs_fout_w4_20260313`
    - `logreg / lan / w4 / MinST = 16.165240 sec`
    - `FedAll = 17.066237 sec`
    - `hop 420 (ba(+*))` final이 `FED/FOUT`로 유지되면서 old local `ba(+*)` hotspot이 닫혔다.
  - fresh collateral:
    - `ralph_b24b_guard_logreg_kmeans_w24_all4_20260313_20260313_194145_1835614`
    - `16/16 success`
    - sole remaining violation:
      - `logreg / lan / w4 / DP = 12.013`
      - baseline `FedAll = 11.319`
      - `12.013 > 11.319 * 1.05 = 11.88495`

- **상태: no new patch — `logreg / lan / w4 / DP` residual은 fresh rerun에서 비지속**
  - fresh pair trace:
    - `ralph_b25_trace_logreg_dp_vs_fout_w4_20260313_20260313_202249_1948458`
    - `DP = 12.018 sec`
    - `FedAll = 11.644 sec`
    - pair baseline 기준으로는 이미 약 `+3.2%` 차이만 남았다.
  - fresh all4 recheck:
    - `ralph_b25_recheck_logreg_w4_all4_20260313_20260313_202904_1966552`
    - `4/4 success`
    - `violations=0`, `warnings=0`
    - coordinator-log runtimes:
      - `MinST = 10.206 sec`
      - `DP = 11.655 sec`
      - `Heuristic = 11.537 sec`
      - `FedAll = 11.380 sec`
    - 5% rule:
      - `11.655 <= 11.380 * 1.05 = 11.949`
  - 해석:
    - `b22 + b24b` current code 기준 targeted residual set은 이제 `0`
    - `logreg / lan / w4 / DP`는 persistent planner mismatch로 고정되지 않았고,
      synchronized fresh all4 rerun에서는 blocker가 아니었다.
  - 다음 단계:
    - current kept code로 synchronized **336-case fresh full sweep** 재개

- **상태: revert — 2% targeted wave의 DP child-variant switch-delta probe**
  - 의도:
    - `computeSwitchEdgeCostDelta(...)`에서
      global-primary child variant 대신
      parent-context cheapest desired-output child variant를 사용해
      `pca`와 `logreg`의 output-decision delta를 함께 줄여보려 했다.
  - touched file:
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - fresh evidence:
    - `ralph_stage2_postfix3_w1_pca_logreg_all4_trace_20260314`
      - `pca / lan / w1 / DP` rewrite unchanged:
        - `hop 90 / 225 / 74 / 75` 계속 `LOUT`
    - `ralph_stage2_postfix3_logreg_w2_all4_20260314`
      - `DP = 12.062`
      - `MinST = 12.169`
      - baseline `FedAll = 10.912`
      - `2%` rule에서 둘 다 fail
    - `ralph_stage2_postfix3_l2svm_w3_all4_20260314`
      - run을 중단하기 전 이미 collateral 악화 관측:
        - `DP = 12.203608`
        - `MinST = 20.197661`
        - `Heuristic = 18.774417`
  - 결론:
    - branch는 reject
    - current live hypothesis는
      **child-variant primary 선택 문제**보다
      **parent-variant delta / hidden-output-root coupling** 쪽이 더 강하다.

- **상태: revert — aggregate-to-public stable-producer alias skip probe**
  - touched files:
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java`
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
    - `tmp/systemds-local/src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - 의도:
    - stable federated producer alias로 이어지는 `FED/FOUT TRANSIENTREAD`를
      `AggBinary/Quaternary` aggregate-to-public parent에서 다시 download-skip으로 취급해
      `pca / DP`의 `hop 74 -> 75` delta를 줄이려 했다.
  - fresh evidence:
    - `ralph_2pct_aggpca_pca_w1_pair_20260314`
      - `DP = 123.347020 sec`
      - `FedAll = 57.934210 sec`
      - PCA는 개선됐지만 여전히 크게 open
    - `ralph_2pct_aggpca_logreg_w1_pair_20260314`
      - `DP = 76.942041 sec`
      - `FedAll = 15.437424 sec`
      - previously closed `logreg / DP`가 다시 크게 reopen
    - `ralph_2pct_aggpca_l2svm_w3_pair_20260314`
      - `MinST = 20.374152 sec`
      - `Heuristic = 19.701126 sec`
      - l2svm collateral은 사실상 unchanged
  - 결론:
    - branch reject
    - main tree / isolated tree 둘 다 safe baseline으로 복구
    - 다음 PCA branch는 `AggBinary` broad skip이 아니라 더 좁은 visible-path / parent-variant delta 축으로 돌아가야 함

- **상태: same-env 2% source-of-truth 재정렬**
  - old `2026-03-09` PCA “good” trace는 live 2% wave의 1차 비교축에서 내렸다.
  - 이유:
    - current rerun env에는 `SYSDS_FED_COST_NET_SERDES_BW=210`이 포함되고,
    - old trace의 `hop 74/75` boundary 숫자와 direct comparison을 하면 apples-to-apples가 아니다.
  - fresh source-of-truth runs:
    - `ralph_2pct_treadcumfix_trace_pca_logreg_w1_20260314`
      - `pca / DP = 257.780638`
      - `pca / FedAll = 62.557871`
      - `logreg / DP = 76.354730`
      - `logreg / FedAll = 17.906953`
    - `ralph_2pct_treadcumfix_l2svm_w3_20260314`
      - `l2svm / MinST = 19.873112`
      - `l2svm / Heuristic = 20.217359`
  - fresh stage-diff:
    - `pca / DP`
      - `hop 220`은 already `FED/FOUT`
      - `hop 224` selected `FED/FOUT` but rewrite/output-decision에서 `CP/LOUT`로 무너진다
      - decisive collapse는 `225 -> 74 -> 75`와 hidden root `90`
    - `logreg / DP`
      - `hop 50 (TWrite X)` selected `bestFOUT=41.149950 << bestLOUT=2293.772714`
      - 그런데 output-decision이 `LOUT`를 고른다
      - runtime도 local `ba+*`가 다시 지배한다
    - `l2svm / MinST`
      - current same-env collateral은 closed
  - corrected interpretation:
    - live blocker는 generic unknown-dim sizing이 아니다
    - 현재 strongest shared hypothesis는
      **output-decision parent-delta / transient-family marginalization이 visible `TWrite/TRead` family에서 `LOUT`를 과선호한다**는 것이다
  - 다음 분기:
    - `FederatedPlannerDpFedCostBased.computeSwitchEdgeCostDelta(...)`
    - `computeParentVariantSwitchDelta(...)`
    - transient-family exact marginalization
    로만 계속 좁힌다.

- **상태: reject — single-reader transient-write parent-local probe**
  - touched files:
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
    - `tmp/systemds-local/src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - branch idea:
    - zero the transient-write whole-parent delta only for **single-reader** `TRANSIENTWRITE -> TRANSIENTREAD`
      chains, while preserving normal parent-variant pressure for multi-reader alias families
  - fresh evidence:
    - `ralph_2pct_twrite1tr_trace_pca_logreg_w1_20260314`
      - `pca / DP = 265.561998`
      - `pca / FedAll = 80.807989`
    - `ralph_2pct_twrite1tr_quick_logreg_w1_20260314`
      - `logreg / DP = 70.727243`
      - `logreg / FedAll = 11.007210`
    - `ralph_2pct_twrite1tr_guard_l2svm_w3_20260314`
      - `violations=0`, `warnings=0`
  - interpretation:
    - `hop 224` local symptom은 일부 줄었지만
    - live blocker인 `225 -> 74 -> 75 -> 90` collapse는 그대로 남았다
    - `logreg / DP`도 severe reopen 상태라 채택 불가
    - stronger next hypothesis:
      - 현재 `computeOutputDecisions(...)`의 transient-write path가
        `resolveTransientWriteConflict(...)` local edge delta만 보고 있고,
        **root/global-aware marginalization을 못 하고 있다**
  - action:
    - branch revert 완료
    - 다음 branch는 local edge delta tweak가 아니라
      **transient-write output decision의 root/global marginalization**으로 이동

- **상태: reject — min(parentVariantDelta, child-forwarding delta) probe**
  - touched files:
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
    - `tmp/systemds-local/src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - branch idea:
    - `computeSwitchEdgeCostDelta(...)`에서 `parentVariantDelta`를 무조건 우선하지 않고
      `min(parentVariantDelta, childForwardingDelta)`를 선택
    - 기대:
      - `pca / DP`, `logreg / DP` output-decision에서 parent-variant 과대계상을 줄이기
  - fresh evidence:
    - `ralph_2pct_minparent_trace_pca_logreg_w1_20260314`
      - `pca / DP = 284.440202`
      - trace 핵심:
        - `224 -> chosen LOUT`
        - `225 -> chosen LOUT`
        - `74 -> chosen LOUT`
        - `75 -> chosen LOUT`
        - `90 -> desiredOut LOUT`
      - `hop 74`는 `parentVariantDelta=1445.903576`까지 줄었지만
      - `hop 224`가 `childForwardingDelta=-4319.887764`로 더 강하게 localize되면서
        collapse point만 이동
    - `ralph_2pct_minparent_quick_logreg_w1_20260314`
      - `logreg / DP = 82.937091`
      - `logreg / FedAll = 14.139458`
      - severe reopen
    - `ralph_2pct_minparent_guard_l2svm_w3_20260314`
      - `l2svm / MinST = 17.293892`
      - `l2svm / Heuristic = 18.115811`
      - `violations=0`, `warnings=0`
  - interpretation:
    - generic `min(...)` 정책은 safe shared fix가 아니었다
    - 문제는 `parentVariantDelta` general preference가 아니라
      **transient-write family 전체를 local edge delta만으로 재조정하는 구조**
    - 다음 분기는 다시
      - `resolveTransientWriteConflict(...)`
      - `computeTransientWriteProducerDelta(...)`
      - root/global-aware marginalization
      으로 이동

- **상태: reject — near-tie bundle promotion (`bundleapply`, `closedbundle`, `closedbundle2`)**
  - case:
    - `logreg / lan / w2 / DP`
  - touched file:
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - fresh evidence:
    - `ralph_2pct_bundleapply_trace_logreg_w2_dp_vs_heur_20260315`
      - fatal:
        - `FED indexing requires federated input but found local at runtime`
        - failing op: `FED rightIndex P`
      - promoted bad chain:
        - `271 (TWrite P) = FED/FOUT`
        - `389 (rix) = FED/FOUT`
        - `390 (TWrite P_1K) = FED/FOUT`
    - `ralph_2pct_closedbundle_trace_logreg_w2_dp_vs_heur_20260315`
      - no crash but regression:
        - `DP = 20.380618`
        - `Heuristic = 18.178433`
      - over-restricted closure disabled the helpful family-only near-tie keep
    - `ralph_2pct_closedbundle2_trace_logreg_w2_dp_vs_heur_20260315`
      - fatal again:
        - `FED indexing requires federated input but found local at runtime`
        - same promoted bad chain around `389/390`
  - decisive comparison:
    - heuristic trace for the same case keeps the `P` slice local:
      - `357 (TRead P)` has `reason=NO_FED_INPUT`
      - `389 (rix)` logs `FedInputCheck return=false (hasUnmaterializableLocal=true)`
      - `390 (TWrite P_1K) = CP/LOUT`
  - interpretation:
    - raw memo-table `canChooseFOUT` is too weak for wider near-tie promotions
    - bundle promotion needs **contextual FedInputCheck feasibility**, not just plan existence
  - action:
    - revert bundle-promotion variants
    - restore family-only near-tie baseline
    - strongest next branch:
      - gate any wider transient-family promotion with
        `FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(...)`
