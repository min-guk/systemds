# SESSION ISSUES — 2026-03-16

## 이슈 1 — DP contextual FType propagation이 CP-local vector의 downstream용 `cpFoutType` 힌트를 버려 `FULL×ROW` 재구성을 막음

- **상태**: 해결
- **환경/조건**: DP planner (`mkl-cost`), `logreg`, worker=1, `wan_mid`, `hop 568 (b(+)) -> hop 603 (ba(+*))`
- **의사결정 근거**: opcode별 후보군을 닫는 가드가 아니라, 이미 도입된 DP `cpFoutType` 경로가 output-decision/context propagation 단계에서도 살아남도록 상태 전파를 바로잡는 수정이다.

### 재현 절차
1. DP code path에서 `buildContextuallyFeasibleDecisionFTypeMap(...)` 를 따라간다.
2. `CP/LOUT` selected plan이 `cpFoutType=ROW` 를 가져도, 기존 구현이 `FederatedRefedPolicy.canSatisfyFederatedInputs(...)` 를 동일하게 요구하는지 본다.
3. producer 자신은 FED-input feasibility가 없지만 downstream upload hint는 유효한 non-transient local hop(예: `568`)이 contextual map에서 탈락하는지 확인한다.

### 관측 증상
- DP는 `cpFoutType` plumbing이 이미 일부 들어가 있었지만, contextual propagation 단계에서
  local selected plan도 `selected FED/FOUT` 와 같은 방식으로 self FED-input feasibility를 요구했다.
- 그 결과 **producer 자체는 local이지만 downstream에서 `ROW` upload hint로 써야 하는 hop** 이 contextual map에서 빠질 수 있었다.
- 이는 `568` 같이 non-transient local vector가 downstream `603` 에서 `FULL×ROW` 로 재해석될 기회를 줄였다.

### 원인 분석
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java:1122-1147`
  - 기존 로직은 `resolveContextualPlanningFType(...)` 로 local plan의 `cpFoutType` 을 복구한 뒤에도,
    최종 삽입 전에 `FederatedRefedPolicy.canSatisfyFederatedInputs(hopRef, contextualFTypeMap)` 를 동일하게 요구했다.
- 그러나 local selected plan의 `cpFoutType` 는 **producer가 이미 FED로 실행 가능하다는 뜻이 아니라**,
  **consumer가 anchor를 갖고 있을 때 upload/refed에 사용할 downstream-safe layout** 이라는 의미다.
- 따라서 local plan에 self FED-input feasibility를 요구하면 의미가 뒤섞여 유효한 힌트가 제거된다.

### 해결 요약
- `selected FED/FOUT` 경로와 `local-output hint` 경로를 분리했다.
- `FED/FOUT` selected plan은 기존처럼 self FED-input feasibility를 만족해야만 contextual map에 들어간다.
- 반면 `CP/LOUT` 또는 `FED/LOUT` selected plan은, non-transient이고 `resolveContextualPlanningFType(...)` 가
  concrete non-`BROADCAST` 타입을 돌려주면 **consumer-side legality check에 맡기고** contextual map에 넣는다.

### 수정 파일
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
- `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`

### 검증
- Diagnostics:
  - `lsp_diagnostics` on
    - `FederatedPlannerDpFedCostBased.java`
    - `FederatedPlannerDpMemoTable.java`
    - `FederatedPlannerDpCostEnumerator.java`
    - `FederatedPlannerDpCostEstimator.java`
    - `FederatedPlannerFallbackIntegrationTest.java`
  - 결과: **all 0 errors**
- Tests:
  - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest test`
  - 결과: `Tests run: 40, Failures: 0, Errors: 0, Skipped: 0`
  - 새 regression test:
    - `testDpContextualFTypeMapKeepsLocalCpFoutHintForNonTransientPlan`
- Build:
  - `mvn -q -DskipTests compile`
  - 결과: exit code 0
- Workload trace 재검증:
  - 실행 커맨드:
    - `RUN_ID=ralph_20260316_dp_cpftype_trace_logreg_w1_wanmid SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_PLANNER_TRACE_HOPS=555,558,568,594,603 SYSDS_FED_PLANNER_TRACE_MAX_EDGES=12 EXPERIMENT_TIMEOUT_SEC=1800 bash ./run_LAN_localproc.sh --workers 1 --conf mkl-cost --dataset P2P2D --net-profile wan_mid --salg logreg`
  - 실행 위치:
    - `/home/mchoi/reproducibility/sigmod2021-exdra-p523/experiments`
  - 로그:
    - `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_dp_cpftype_trace_logreg_w1_wanmid.log`
  - 핵심 관측:
    - `hop 568`:
      - `DP-Selected ... bestLOUT={exec=CP,fType=ROW,...}`
      - `DP-OutputDecision hop=568 ... chosen=LOUT`
      - 즉 **568은 local로 남지만 `ROW` 힌트는 보존**된다.
    - `hop 603`:
      - `[Oracle] ... exec=FED, placement=LOUT, reason=FOUT_NOT_SUPPORTED_BY_RUNTIME, inputs=[FULL, ROW]`
      - `DP-Candidate ... allow[cpl=true,cpf=false,fedl=true,fedf=false] reasonFedInputs=true`
      - `DP-Selected ... bestLOUT={exec=FED,fType=BROADCAST,cost=-24029.429925}`
      - 즉 **603은 더 이상 CP/LOUT-only에 갇히지 않고 `FULL×ROW` 기반 FED/LOUT 후보를 열고 실제로 선택**한다.
  - 성능 지표:
    - `Total execution time: 65.315 sec.`
    - `Federated I/O (Read, Put, Get): 2/61/64.`
    - `Federated Execute (Inst, UDF): 188/0.`
  - 기준 trace 대비 변화:
    - 기존 `2026-03-08` trace에서는
      - `hop 568`: `bestLOUT={exec=CP,fType=BROADCAST,...}`
      - `hop 603`: `reason=NOT_FEDERATED_INPUTS` 중심으로 `CP/LOUT`에 머물렀다.
      - `Total execution time: 81.360 sec.`
      - `Federated I/O (Read, Put, Get): 2/0/2.`
      - `Federated Execute (Inst, UDF): 0/0.`
    - 수정 후 trace에서는
      - `hop 568`: `ROW` 힌트 보존
      - `hop 603`: `[FULL, ROW]` 기반 `FED/LOUT` 후보/선택 복구
      - `Total execution time: 65.315 sec.`
      - `Federated I/O (Read, Put, Get): 2/61/64.`
      - `Federated Execute (Inst, UDF): 188/0.`

### 잔여 이슈
- 이번 이슈의 핵심 증상(`568 -> 603` 경로에서 `FULL×ROW` 재구성 실패)은 workload-level에서 재현/해결 확인을 마쳤다.
- 다만 다른 워크로드/다른 hop에서도 동일한 contextual `cpFoutType` 경로가 충분한지는 추가 matrix 검증이 필요하다.

### 잠재 회귀 위험
- local plan hint를 과도하게 전파하면, producer가 실제 federated source인 것처럼 오인될 수 있다.
- 감지 방법: trace에서 local selected hop이 contextual map에는 들어가더라도, 최종 FED legality는 **consumer-side anchor/materialization check** 로만 성립하는지 확인한다.

---

## 이슈 2 — Post-patch DP 검증에서 logreg는 회복됐지만 pca는 여전히 강한 residual을 보임

- **상태**: 진행중
- **환경/조건**: DP planner (`mkl-cost`), dataset=`P2P2D`, workloads=`pca,logreg`, workers=`1,2`, profiles=`lan,wan_light`
- **의사결정 근거**: 코드 추가 수정 없이 현재 DP 상태를 localproc matrix로 재검증했다. 새 가드/후보 closure 없이 현행 planner 결과를 측정했다.

### 재현 절차
- 실행 위치:
  - `/home/mchoi/reproducibility/sigmod2021-exdra-p523/experiments`
- 실행 커맨드:
  - worker 1:
    - `SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local RUN_ID_PREFIX=ralph_20260316_dp_verify_w1_pcalog_lan_wlight PORT_BASE_START=9100 bash ./run_LAN_localproc_matrix.sh --workers-list 1 --planner-confs mkl-cost --workloads pca,logreg --net-profiles lan,wan_light --dataset P2P2D --continue-on-failure 0 --run-timeout-sec 2400 --run-timeout-kill-after 120`
  - worker 2:
    - `SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local RUN_ID_PREFIX=ralph_20260316_dp_verify_w2_pcalog_lan_wlight PORT_BASE_START=9300 bash ./run_LAN_localproc_matrix.sh --workers-list 2 --planner-confs mkl-cost --workloads pca,logreg --net-profiles lan,wan_light --dataset P2P2D --continue-on-failure 0 --run-timeout-sec 2400 --run-timeout-kill-after 120`
- 상태 파일:
  - `experiments/results/matrix_state/ralph_20260316_dp_verify_w1_pcalog_lan_wlight.tsv`
  - `experiments/results/matrix_state/ralph_20260316_dp_verify_w2_pcalog_lan_wlight.tsv`

### 관측 증상
- 8개 케이스 모두 **실행 자체는 성공**했고 fatal marker도 없었다.
- 그러나 성능은 workload별로 갈렸다.

현재 결과:

| workload | w | profile | current DP sec | Fed I/O | Fed Exec |
|---|---:|---|---:|---|---|
| logreg | 1 | lan | 73.742 | 2/335/338 | 1010/0 |
| logreg | 1 | wan_light | 70.590 | 2/61/64 | 188/0 |
| logreg | 2 | lan | 10.848 | 4/2025/1418 | 2970/0 |
| logreg | 2 | wan_light | 10.614 | 4/1965/1238 | 2520/0 |
| pca | 1 | lan | 246.754 | 1/2/5 | 16/0 |
| pca | 1 | wan_light | 233.994 | 1/2/5 | 16/0 |
| pca | 2 | lan | 75.424 | 2/2/6 | 11/0 |
| pca | 2 | wan_light | 78.745 | 2/2/6 | 11/0 |

과거 기준 CSV(`experiments/plots/out/ralph_fullsweep_postalign_plus_pcaorigfix_20260309_data.csv`)와 비교:

| workload | w | profile | reference DP sec | current DP sec | 해석 |
|---|---:|---|---:|---:|---|
| logreg | 1 | lan | 72.705 | 73.742 | 거의 동일 |
| logreg | 1 | wan_light | 76.166 | 70.590 | 개선 |
| logreg | 2 | lan | 28.923 | 10.848 | 크게 개선 |
| logreg | 2 | wan_light | 49.485 | 10.614 | 크게 개선 |
| pca | 1 | lan | 68.085 | 246.754 | 심한 회귀 |
| pca | 1 | wan_light | 60.509 | 233.994 | 심한 회귀 |
| pca | 2 | lan | 57.689 | 75.424 | 악화 |
| pca | 2 | wan_light | 63.941 | 78.745 | 악화 |

### 원인 분석
- 방금 수정한 `cpFoutType` contextual propagation 보정은 **logreg 계열의 `568 -> 603` 복구에는 유효**했다.
- 반면 `pca`는 여전히 federated activity가 매우 낮다.
  - current `pca/w1`: `Fed Execute 16`, `Fed I/O 1/2/5`
  - reference `pca/w1`: `Fed Execute 14`, `Fed I/O 1/3/6`
- 즉 `pca` residual은 이번 수정의 직접 타깃이었던 **local vector `ROW` 힌트 소실 문제와는 다른 경로**일 가능성이 높다.
- 현재 evidence 기준으로는, `pca`는 여전히 **대부분 local chain**에 가까운 계획을 타고 있으며, logreg에서 회복된 경로와는 별개의 residual이 남아 있다.

#### 추가 trace 확인 (`pca / w1 / lan`)
- 실행:
  - `RUN_ID=ralph_20260316_pca_w1_lan_trace_hot SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_PLANNER_TRACE_HOPS=18,220,224,225,74,75 SYSDS_FED_PLANNER_TRACE_MAX_EDGES=16 EXPERIMENT_TIMEOUT_SEC=2400 bash ./run_LAN_localproc.sh --workers 1 --conf mkl-cost --dataset P2P2D --net-profile lan --alg pca`
- 로그:
  - `experiments/results/fed1/mkl-cost/pca_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_pca_w1_lan_trace_hot.log`
- 핵심 관측:
  - `hop 220`:
    - `bestFOUT={exec=FED,fType=FULL,cost=848.147050}` 로 FED/FOUT 후보는 살아 있다.
    - output-decision도 일부 iteration에서 `chosen=FOUT`.
  - `hop 224`:
    - `bestFOUT={exec=FED,fType=FULL,cost=1866.081742}` 로 여전히 FOUT 후보가 살아 있다.
    - 그러나 iteration마다 `LOUT/FOUT`가 흔들린다.
  - **decisive collapse는 `hop 225 -> hop 74 -> hop 75`** 다.
    - `hop 225 (TWrite X)`:
      - `bestLOUT={... cost=652.415096}`
      - `bestFOUT={... cost=4923.087648}`
      - `DP-TransientDecision ... fOutAdditional=6811.901104 chosen=LOUT`
    - `hop 74 (TRead X)`:
      - `fOutAdditional=5883.653961 chosen=LOUT`
    - `hop 75 (ba(+*))`:
      - `fOutAdditional=1424.847619 chosen=LOUT`
- 비교 기준(old trace):
  - `experiments/results/fed1/mkl-cost/pca_dataset-P2P2D_coordinator_mkl-cost_ralph_b6trace_pca_dp_w1_lan_20260311_20260311_061342_2648972_w1_mkl-cost_pca_lan_lan.log`
  - old:
    - `hop 225 bestFOUT=329.705363`
    - `DP-TransientDecision ... fOutAdditional=664.481358 chosen=LOUT`
    - `hop 74 fOutAdditional=664.481358`
    - `hop 75 fOutAdditional=103.581000`
  - current:
    - `hop 225 bestFOUT=4923.087648`
    - `DP-TransientDecision ... fOutAdditional=6811.901104 chosen=LOUT`
    - `hop 74 fOutAdditional=5883.653961`
    - `hop 75 fOutAdditional=1424.847619`

#### 현재 가장 강한 가설
- 현재 `resolveTransientWriteConflict(...)`는 `currentOut != targetOut`이면
  `computeTransientWriteProducerDelta(...)`를 **항상 더한다**.
  - 위치:
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java:1478-1506`
- 그런데 old trace에는 `DP-TransientInputDelta ... mode=skip_producer_delta_already_priced_by_treads`가 남아 있었다.
- 즉 현재는 `225`에서 **TRead-side delta + producer-side delta가 함께 반영**되며,
  이것이 `225 -> 74 -> 75` local bias를 크게 키운 것으로 보인다.
- 이는 trace 기반의 **강한 가설**이며, 다음 단계에서 코드/trace를 더 맞춰 확인해야 한다.

### 해결 요약
- 이번 단계에서는 추가 코드 수정 없이 **문제 분리를 완료**했다.
- 결론:
  - **logreg**: 이번 patch의 효과가 실제 matrix에서도 확인됨
  - **pca**: 이번 patch로 해결되지 않음. 별도 residual로 유지

### 수정 파일
- 없음 (검증만 수행)

### 검증
- 모든 8개 case success
- matrix state:
  - `experiments/results/matrix_state/ralph_20260316_dp_verify_w1_pcalog_lan_wlight.tsv`
  - `experiments/results/matrix_state/ralph_20260316_dp_verify_w2_pcalog_lan_wlight.tsv`
- 대표 로그:
  - `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_dp_verify_w1_pcalog_lan_wlight_20260316_075214_4103540_w1_mkl-cost_logreg_wan_light.log`
  - `experiments/results/fed1/mkl-cost/pca_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_dp_verify_w1_pcalog_lan_wlight_20260316_075214_4103540_w1_mkl-cost_pca_lan.log`
  - `experiments/results/fed2/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_dp_verify_w2_pcalog_lan_wlight_20260316_075214_4103544_w2_mkl-cost_logreg_lan.log`
  - `experiments/results/fed2/mkl-cost/pca_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_dp_verify_w2_pcalog_lan_wlight_20260316_075214_4103544_w2_mkl-cost_pca_wan_light.log`

### 잔여 이슈
- `pca` residual의 planner path를 trace-level로 다시 확인해야 한다.
- 우선순위는 `pca / w1 / lan`이다. 현재 가장 크게 벌어진다.

### 잠재 회귀 위험
- logreg에 맞춘 수정이 pca path에는 아무 효과가 없을 수 있다.
- 감지 방법: `pca`에서 `Fed Execute`, `Fed I/O`, 특정 hot-hop trace(`18,220,224,225,74,75`)를 함께 비교한다.


- **상태: 해결 — DP rewrite가 parent FED edge와 child local plan을 섞어 runtime/local-chain을 만들던 불일치**
  - **환경/조건**
    - planner: `mkl-cost (DP)`
    - workload: `logreg`, `pca`
    - dataset: `P2P2D`
    - workers: `1`, `2`
    - profiles: `lan`, `wan_light`
  - **재현 절차**
    - trace repro:
      - `RUN_ID_PREFIX=ralph_20260316_trace2_logreg_w2_lan_current SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_PLANNER_TRACE_HOPS=271,357,389,390 SYSDS_FED_PLANNER_TRACE_MAX_EDGES=16 PORT_BASE_START=9300 bash ./run_LAN_localproc_matrix.sh --workers-list 2 --planner-confs mkl-cost --workloads logreg --net-profiles lan --dataset P2P2D --continue-on-failure 0 --run-timeout-sec 2400 --run-timeout-kill-after 120`
    - failing log:
      - `experiments/results/fed2/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_trace2_logreg_w2_lan_current_20260316_092226_34033_w2_mkl-cost_logreg_lan.log`
  - **관측 증상**
    - runtime fatal:
      - `FED indexing requires federated input but found local at runtime`
      - failing op: `FED rightIndex P`
    - trace상 selected/output-decision은 `271/357/389/390 = FOUT`이었지만,
      rewrite에서는
      - `389 (rix)`는 child edge를 `(357,FOUT)`로 유지한 채
      - `357 (TRead P)`는 `desiredOut=LOUT`, `effectiveExec=CP`, `effectiveOut=LOUT`
      로 내려갔다.
    - 즉 **parent FED edge와 child rewritten plan이 서로 다른 output**을 사용했다.
  - **원인 분석**
    - `rewriteHop(...)`가 현재 hop은 `selectCompatiblePlanVariant(...)`로 고르면서도,
      child recursion에서는 `outputDecisions`를 우선해 `memoTable.getFedPlanAfterPrune(childHopID, childDesiredOut)`를 직접 집었다.
    - 그래서 global decision map이 parent edge와 일시적으로 어긋난 경우,
      **상위 parent가 선택한 FED edge는 유지되는데 child는 local plan으로 rewrite**될 수 있었다.
    - 이전에 `pca` residual을 `producer delta` 중복 과금으로 해석했던 것은 **trace 상 symptom 설명으로는 유효했지만 root cause로는 불충분**했다.
      실제 blocker는 `225 -> 74 -> 75` family에서도 같은 rewrite 불일치였다.
  - **의사결정 근거**
    - runtime limitation이 아니라 **DP rewrite/state-consistency bug**이므로,
      candidate closure나 oracle 가드가 아니라 rewrite plan selection을 수정했다.
  - **해결 요약**
    - `rewriteHop(...)`에 `selectRewritePlanVariant(...)`를 추가했다.
    - rewrite에서는
      1. child/현재 hop의 **inherited edge output**과 호환되는 variant를 먼저 찾고,
      2. 그 다음 `outputDecisions`를 보며,
      3. 그래도 안 되면 inherited raw plan을 우선 fallback 하도록 바꿨다.
    - 목표는 **global decision map이 완전히 일치하지 않아도, rewrite된 executable forest 자체는 parent-child output 일관성을 유지**하게 만드는 것이다.
  - **수정 파일**
    - `tmp/systemds-local/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
    - `tmp/systemds-local/src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - **검증**
    - unit:
      - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest#testDpRewriteKeepsTransientChainConsistentWithFedParentEdge,FederatedPlannerFallbackIntegrationTest#testDpContextualFTypeMapKeepsLocalCpFoutHintForNonTransientPlan test`
      - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest test`
      - `mvn -q -DskipTests package`
    - logreg trace fixed:
      - run:
        - `RUN_ID_PREFIX=ralph_20260316_trace3_logreg_w2_lan_rewritefix ...`
      - log:
        - `experiments/results/fed2/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_trace3_logreg_w2_lan_rewritefix_20260316_093251_51496_w2_mkl-cost_logreg_lan.log`
      - result:
        - no fatal error
        - `Total execution time: 10.921 sec`
        - `Federated Execute: 3120/0`
        - rewrite consistency restored:
          - `390 = FED/FOUT`
          - `389 = FED/FOUT`
          - `357 = FED/FOUT`
          - `271 = FED/FOUT`
    - full matrix rerun:
      - `experiments/results/matrix_state/ralph_20260316_postrewrite_w1_pcalog_lan_wlight.tsv`
      - `experiments/results/matrix_state/ralph_20260316_postrewrite_w2_pcalog_lan_wlight.tsv`
      - results:
        - `logreg / w1 / lan = 75.054 sec`
        - `logreg / w1 / wan_light = 70.776 sec`
        - `logreg / w2 / lan = 10.507 sec`
        - `logreg / w2 / wan_light = 10.831 sec`
        - `pca / w1 / lan = 52.836 sec`
        - `pca / w1 / wan_light = 64.266 sec`
        - `pca / w2 / lan = 51.706 sec`
        - `pca / w2 / wan_light = 57.770 sec`
    - pca hot trace after fix:
      - `experiments/results/fed1/mkl-cost/pca_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_pca_w1_lan_trace_postrewrite.log`
      - `Total execution time: 57.336 sec`
      - `Federated Execute: 26/0`
      - output-decision은 여전히 `74/75/225 = LOUT`였지만,
        rewrite는 inherited FED edge를 따라
        - `75 = FED/LOUT`
        - `74 = FED/FOUT`
        - `225 = FED/FOUT`
        - `224 = FED/FOUT`
        - `220 = FED/FOUT`
        로 연결되며 collapse를 막았다.
  - **잔여 이슈**
    - `225` transient family의 producer-delta 과금이 실제로 여전히 과한지는 별도 calibration 이슈로 남아 있을 수 있다.
    - 다만 **current blocker는 더 이상 그것이 아니고**, runtime/perf를 크게 망가뜨리던 rewrite inconsistency는 닫혔다.
  - **잠재 회귀 위험**
    - inherited edge 우선 rewrite가 일부 case에서 global output-decision을 덜 엄격하게 따를 수 있다.
    - 감지 방법:
      - trace에서 `DP-OutputDecision-Chosen`과 `DP-Rewrite-Plan`이 다른 경우,
      - parent-child edge output이 일관적인지(`childEdges` vs child `effectiveOut`)를 우선 확인한다.

#### 2026-03-16 최종 정리: broad decision-repair는 제거

- 한때 `repairIncompatibleOutputDecisions(...)`로 global decision map 자체를 고치려 했지만,
  이것이 `logreg / w2 / wan_light`에서 `271(TWrite P)`, `357(TRead P)`까지
  `FOUT -> LOUT`으로 강등시켜
  - `Total execution time: 27~29 sec`
  - `Federated Execute: 604/0`
  의 회귀를 만들었다.
- trace에서 확인된 직접 원인:
  - `[PlannerTrace][DP-DecisionRepair] hop=271 ... repairedTo=LOUT`
  - `[PlannerTrace][DP-DecisionRepair] hop=357 ... repairedTo=LOUT`
- 따라서 최종 해법은:
  1. **rewrite 단계의 inherited-edge-aware plan selection 유지**
  2. **global broad repair 제거**
- 제거 후 current-workspace 재검증:
  - `logreg / w2 / lan`
    - `11.635 sec`
    - `Federated Execute: 3120/0`
    - runtime crash 없음
  - `logreg / w2 / wan_light`
    - `11.693 sec`
    - `Federated Execute: 2820/0`
  - `pca / w1 / lan`
    - `52.422 sec`
    - `Federated Execute: 26/0`
- 최종 matrix:
  - `experiments/results/matrix_state/ralph_20260316_dp_verify_final_norepair.tsv`
  - `logreg / w1 / lan = 69.185 sec`
  - `logreg / w1 / wan_light = 65.371 sec`
  - `logreg / w2 / lan = 11.117 sec`
  - `logreg / w2 / wan_light = 11.631 sec`
  - `pca / w1 / lan = 52.453 sec`
  - `pca / w1 / wan_light = 53.577 sec`
  - `pca / w2 / lan = 52.796 sec`
  - `pca / w2 / wan_light = 52.753 sec`

#### 2026-03-16 wan_mid closing sweep

- 추가 검증 파일:
  - `experiments/results/matrix_state/ralph_20260316_dp_verify_wanmid_norepair.tsv`
- 범위:
  - DP (`mkl-cost`) / `{logreg, pca}` / `{w1, w2}` / `wan_mid`
- 결과:
  - `logreg / w1 / wan_mid = 67.160 sec`
  - `logreg / w2 / wan_mid = 30.519 sec`
  - `pca / w1 / wan_mid = 56.454 sec`
  - `pca / w2 / wan_mid = 51.893 sec`
- reference (`ralph_fullsweep_postalign_plus_pcaorigfix_20260309_data.csv`) 대비:
  - `logreg / w1 / wan_mid`: `88.204 -> 67.160`
  - `logreg / w2 / wan_mid`: `70.145 -> 30.519`
  - `pca / w1 / wan_mid`: `66.507 -> 56.454`
  - `pca / w2 / wan_mid`: `62.415 -> 51.893`
- 현재 evidence 기준 closing statement:
  - **DP / `{logreg,pca}` / `{w1,w2}` / `{lan,wan_light,wan_mid}` 범위의 major blocker는 닫혔다.**
  - 아직 닫지 않은 범위는 다른 workload(`l2svm`, `lm`, `kmeans`, `als`, ...)에 대한 최종 matrix이다.

#### 2026-03-16 공통 수정: PRIVATE_AGGREGATE에서 concrete FType 기반 CP->FOUT 개방

- **상태**: 부분 해결 / 후속 DP residual 진행중
- **환경/조건**
  - 플래너: DP, MinST
  - privacy: `PRIVATE_AGGREGATE`
  - workload: `logreg`
  - 핵심 hop family: `604 -> 607 -> 611 -> 613`
- **관측 증상**
  - `logreg / w1 / lan`에서 오라클은 `604 (b(cbind))`의 `CP/FOUT/ROW`를 허용하지만,
    DP는 `CP/FOUT`를 충분히 열지 못했고 MinST는 별도 repair/reopen에 의존했다.
  - 관련 trace:
    - DP: `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_logreg_w1_lan_dp_trace_604fam.log`
    - MinST: `experiments/results/fed1/mkl-min-st-cut/logreg_dataset-P2P2D_dams-so002_mkl-min-st-cut_ralph_20260316_logreg_w1_lan_minst_trace_604fam.log`
- **원인 분석**
  - 공통 `ExecPlacementPolicy`가 `PRIVATE_AGGREGATE`의 `CP/FOUT`를 너무 좁게 열고 있었다.
  - 기존 로직은 사실상 `caps.reason()==UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY`일 때만 `CP/FOUT`를 열어,
    `NO_FED_INPUT`이지만 concrete `FType`과 안전한 materialization path가 있는 bridge hop(`604`)를 충분히 모델링하지 못했다.
  - MinST는 이 부족한 공통 policy를 planner-local reopen으로 보완하고 있었고,
    DP는 공통 policy를 더 직접적으로 따라 candidate를 닫는 편이었다.
- **해결 요약**
  - 공통 `ExecPlacementPolicy`에서 `PRIVATE_AGGREGATE`도
    **concrete `FType`(ROW/COL/FULL)** 가 있으면 `CP/FOUT` competitor를 열도록 수정했다.
  - 반대로 `FType`이 `null`/`PART`/`OTHER`이면 열지 않도록 제한했다.
  - MinST의 planner-local `PRIVATE_AGGREGATE -> CP/FOUT` reopen은 제거해,
    **DP/MinST가 같은 shared policy**를 타도록 맞췄다.
  - 결정 근거: **오라클 reason-code 기반 완화가 아니라, concrete FType/materialization 가능성 기반 공통 policy 수정**
- **수정 파일**
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**
  - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest test` → pass
  - 추가 테스트:
    - `testPrivateAggregateDecisionRequiresConcreteFTypeForCpfout`
    - `testMinSTBuildExecPlacementCapsUsesSharedPrivateAggregateCpfoutPolicy`
  - 타겟 실험(최신 build):
    - `experiments/results/matrix_state/ralph_20260316_common_cpftype_privateagg_lan.tsv`
    - `mkl-cost / logreg / w1 / lan = 66.919 sec`, `Federated Execute = 604/0`
    - `mkl-cost / logreg / w2 / lan = 10.884 sec`, `Federated Execute = 3120/0`
- **잔여 이슈**
  - 공통 policy 수정 후에도 `logreg / w1 / lan / DP`는 여전히 느리다.
  - 즉 이번 수정은 **공통 gating asymmetry**는 해소했지만,
    **DP의 output-decision / selected FOUT chain propagation residual**은 남아 있다.
- **잠재 회귀 위험**
  - concrete FType가 잘못 추정되는 hop에서 `CP/FOUT` 후보가 새로 열릴 수 있다.
  - 감지 방법:
    - `FederatedPlannerFallbackIntegrationTest`
    - `logreg / w1 / lan` trace에서 `604/607/611/613`의 `allow[cpf=...]`와 `DP-OutputDecision-Chosen`을 함께 확인

#### 2026-03-16 DP residual 분리: `772 -> {775,780}` parent-variant delta의 TRead cumulative 재과금 축소 후, 남은 원인이 upstream family propagation으로 좁혀짐

- **상태**: 진행중
- **환경/조건**
  - 플래너: DP (`mkl-cost`)
  - workload: `logreg / w1 / lan`
  - 핵심 hop family: `612 (TWrite P_new)`, `772 (TRead P_new)`, `775 (TWrite P)`, `780 (rix)`
  - 실행 커맨드:
    - `SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local RUN_ID=ralph_20260316_trsharefix_logreg_w1_lan_dp SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_PLANNER_TRACE_HOPS=603,604,607,611,612,613,772,775,780,809 SYSDS_FED_PLANNER_TRACE_MAX_EDGES=32 EXPERIMENT_TIMEOUT_SEC=1800 bash /home/mchoi/reproducibility/sigmod2021-exdra-p523/experiments/run_LAN_localproc.sh --workers 1 --conf mkl-cost --dataset P2P2D --net-profile lan --port-base 10020 --salg logreg`
- **의사결정 근거**
  - runtime-supported combination을 닫지 않고, output-decision scoring decomposition에서 `TRANSIENTREAD` child cumulative를 parent variant delta가 다시 먹는 부분만 줄이는 planner-cost 수정이다.

- **관측 증상**
  - 기존 trace에서는 `612`의 local transient decision이
    - `DP-TransientDecision ... fOutAdditional=477.198270 chosen=LOUT`
  - 그 큰 값의 대부분이 `772 (TRead P_new)`를 child로 둔 downstream parent들에서 나왔다.
    - 이전 trace:
      - `parentHop=1477` delta `228.111132`
      - `parentHop=1485` delta `228.111132`
      - `parentHop=775` delta `7.865901`
      - `parentHop=780` delta `7.865901`
  - 관련 로그:
    - 이전: `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_trace775_focus772_775_logreg_w1_lan_dp.log`
    - 수정 후: `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_trsharefix_logreg_w1_lan_dp.log`

- **원인 분석**
  - `FederatedPlannerDpFedCostBased.computeParentVariantSwitchDelta(...)`는 compatible parent variant를 찾으면
    `cand.getCumulativeCost() - parentPlan.getCumulativeCost()`를 그대로 delta로 사용했다.
  - 그런데 `772 (TRead P_new)`의 parent variant cumulative에는 child `TRead` plan의 cumulative share가 이미 들어 있다.
    - `775` candidate:
      - current `8.220903`
      - alt `16.086804`
      - raw delta `7.865901`
    - 여기서 direct child `772` share delta가 `5.243800`이고, 이 값이 producer-side change를 parent에서 다시 먹는 중복분이었다.
  - 수정 후 trace에서 이 분해가 직접 보인다:
    - `parentHop=775 ... delta=2.622101 rawDelta=7.865901 childShareAdj=5.243800`
    - `parentHop=1477 ... delta=76.040941 rawDelta=228.111132 childShareAdj=152.070191`
  - 즉 downstream parent들의 큰 delta는 “parent-local boundary”보다 `TRANSIENTREAD` child cumulative 변화의 재과금이 주성분이었다.

- **해결 요약**
  - `FederatedPlannerDpFedCostBased.computeParentVariantSwitchDelta(...)`에서
    switched child가 `TRANSIENTREAD`일 때 direct child cumulative-share delta를 계산해 parent variant delta에서 뺐다.
  - 결과:
    - `612`의 transient decision 추가비용이 `477.198270 -> 162.570288`로 감소했다.
    - `775` additional-root contribution도 사실상 0으로 수렴해 family score에서 downstream duplicate가 크게 줄었다.

- **수정 파일**
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`

- **검증**
  - `mvn -q -DskipTests compile` → pass
  - `mvn -q -DskipTests package` → pass
  - 수정 후 trace 핵심:
    - `DP-TransientDecision`:
      - `hop=612 ... fOutAdditional=162.570288 chosen=LOUT`
    - `DP-ParentVariantCandidate`:
      - `hop=772 parentHop=775 ... delta=2.622101 rawDelta=7.865901 childShareAdj=5.243800`
      - `hop=772 parentHop=1477 ... delta=76.040941 rawDelta=228.111132 childShareAdj=152.070191`
    - `DP-DecisionMap-FamilyScore`:
      - `chosenTotal=104114.139192`
      - `altTotal=104418.279574`
      - 남은 차이 대부분이 `rootOrig=612`의 raw FOUT delta로 축소됨

- **잔여 이슈**
  - downstream TRead/TWrite re-charge는 줄었지만, `612` family는 여전히 `chosen=LOUT`이다.
  - 현재 남은 직접 원인은 `collectTransientFamilyDecisionHopIDs(...)`가 여전히 `{612,772}`만 family로 보고,
    upstream producer chain(`611/607/604/...`)을 함께 flip 후보로 올리지 못하는 점이다.
  - 즉 residual은 이제 **downstream duplicate charging**이 아니라 **upstream FOUT-chain family propagation** 쪽으로 좁혀졌다.

- **잠재 회귀 위험**
  - 일반 non-transient child까지 cumulative-share delta를 빼면 실제 parent-local cost가 과소평가될 수 있다.
  - 감지 방법:
    - trace에서 `childShareAdj`가 `TRANSIENTREAD`가 아닌 hop에선 항상 0인지 확인
    - `logreg / w1 / lan`에서 `612/772/775/780` deltas가 줄되 unrelated hop deltas는 그대로인지 확인

#### 2026-03-16 DP residual 분리: upstream family propagation을 억지로 키워도 MinST-style mixed chain을 그대로 표현하지 못해 `LOUT` 고정이 유지됨

- **상태**: 진행중
- **환경/조건**
  - 플래너: DP (`mkl-cost`)
  - workload: `logreg / w1 / lan`
  - 핵심 hop chain:
    - DP residual: `604 -> 607 -> 611 -> 612`
    - MinST reference mixed chain: `603 LOUT`, `604 FOUT`, `605 LOUT`, `606 LOUT`, `607 FOUT`, `608 FOUT`, `610 LOUT`, `611 FOUT`, `612 FOUT`
  - 실행 커맨드:
    - `SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local RUN_ID=ralph_20260316_familyclosure*_logreg_w1_lan_dp SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_PLANNER_TRACE_HOPS=603,604,605,606,607,608,610,611,612,613,614 SYSDS_FED_PLANNER_TRACE_MAX_EDGES=24 EXPERIMENT_TIMEOUT_SEC=1800 bash /home/mchoi/reproducibility/sigmod2021-exdra-p523/experiments/run_LAN_localproc.sh --workers 1 --conf mkl-cost --dataset P2P2D --net-profile lan --port-base 9990 --salg logreg`
- **의사결정 근거**
  - runtime-supported combination을 닫지 않고, DP output-decision/family scoring이 실제로 어떤 closure를 표현 가능한지 trace 기반으로 분리했다.

- **관측 증상**
  - baseline (`twritepass`) 이후에도 DP는 계속
    - `DP-TransientDecision hop=612 ... fOutAdditional=7.866304 chosen=LOUT`
    - `DP-OutputDecision-Chosen hop=604/607/611/612 = LOUT`
    - `Federated Execute ~= 596~603`
    - `Total execution time ~= 66 sec`
  - upstream propagation exploratory trace에서는 family가 커져도 결과가 좋아지지 않았다.
    - `familyclosure`:
      - `family=[612, 772, 611]`
      - `altTotal=104263.519593`
    - `familyclosure2`:
      - `family=[612, 772, 611, 608, 607, 604, 603, ...]`
      - `altTotal=104274.055463`
    - `familyclosure3`:
      - mixed-output closure를 시도했지만
      - `family=[612, 772, 611, 608, 607, 604, 603, ..., 610, 609]`
      - `changed=34/40`
      - `altTotal=104492.573124`
  - 즉 family를 upstream으로 키워도 `LOUT` 선택이 뒤집히지 않았다.

- **원인 분석**
  - 이번 단계에서 확인된 핵심은 **“family가 안 퍼져서”만이 문제가 아니라는 점**이다.
  - MinST trace를 보면 실제 좋은 repair chain은 uniform `FOUT`이 아니라 mixed output이다.
    - reference trace:
      - `603 = FED/LOUT`
      - `604 = FED/FOUT`
      - `605 = FED/LOUT`
      - `606 = CP/LOUT`
      - `607 = FED/FOUT`
      - `608 = FED/FOUT`
      - `610 = CP/LOUT`
      - `611 = FED/FOUT`
      - `612 = FED/FOUT`
      - `613 = FED/FOUT`
  - 반면 현재 DP transient-family refine는 근본적으로
    - “family hop들을 같은 output으로 뒤집는 score probe”
    - 또는 그에 가까운 closure heuristic
    를 사용한다.
  - 그래서 `604/607/611/612`만 `FOUT`로 올리고
    `603/605/606/610`은 `LOUT`으로 유지하는 MinST-style repair를 자연스럽게 표현하지 못한다.
  - exploratory closure를 크게 키우면
    - `603`, `605`, `606`, `610` 같이 MinST가 일부러 `LOUT`으로 유지하는 hop들까지 함께 흔들리거나
    - deeper local subtree(`568`, scalar/update chain 등)를 과도하게 끌어와
    alt score가 오히려 더 나빠졌다.

- **해결 요약**
  - 이번 단계에서는 exploratory family-closure heuristic을 두 번 시도했지만 성능 개선이 없었고,
    mixed-output closure 시도는 alt score를 더 악화시켰다.
  - 따라서 exploratory helper는 **세션 종료 전 소스에서 되돌리고**, 워크트리는 더 단순한 closure patch 상태로 유지했다.
  - 결론적으로 남은 직접 원인은:
    - `upstream family IDs` 부족 그 자체라기보다
    - **DP family scoring이 MinST repair의 mixed-output / same-output variant shift를 직접 표현하지 못하는 구조적 한계**
    로 좁혀진다.

- **수정 파일**
  - exploratory 시도 후 revert:
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`

- **검증**
  - compile/package:
    - `mvn -q -DskipTests compile` → pass
    - `mvn -q -DskipTests package` → pass
  - 대표 로그:
    - `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_familyclosure_logreg_w1_lan_dp.log`
    - `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_familyclosure2_logreg_w1_lan_dp.log`
    - `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_familyclosure3_logreg_w1_lan_dp.log`
  - reference MinST trace:
    - `experiments/results/fed1/mkl-min-st-cut/logreg_dataset-P2P2D_dams-so002_mkl-min-st-cut_ralph_20260316_logreg_w1_lan_minst_trace_604fam.log`
  - 핵심 결과:
    - `familyclosure3` final runtime:
      - `Total execution time: 66.067 sec`
      - `Federated Execute: 596/0`
    - 즉 exploratory propagation은 runtime gain으로 이어지지 않았다.

- **잔여 이슈**
  - 다음 수정은 uniform family flip이 아니라,
    - per-hop required output map
    - same-output variant shift
    - mixed-output family score
    를 명시적으로 표현하는 방향이어야 한다.
  - 특히 `604 FOUT`를 위해 `603 LOUT/FED`,
    `607 FOUT`를 위해 `606 LOUT/CP`,
    `611 FOUT`를 위해 `610 LOUT/CP`
    를 유지하는 cost probe가 필요하다.

- **잠재 회귀 위험**
  - family propagation을 무리하게 키우면 deep local subtree까지 decision probe에 끌려 들어와
    alt score를 왜곡하거나 planner 시간이 증가할 수 있다.
  - 감지 방법:
    - `DP-TransientFamilyRefine`에서 family size가 과도하게 커질 때
      `altTotal`이 오히려 상승하는지 확인
    - MinST reference chain과 DP probe chain의 output pattern(`LOUT/FOUT`)을 hop별로 직접 비교

#### 2026-03-16 DP seeded mixed-output rerun: 604-family residual은 local cost delta로 축소됐고, 전체 gap의 주범은 `multiLogReg` builtin 내부 `contains` 경로로 이동

- **상태**: 진행중
- **환경/조건**
  - 플래너: DP (`mkl-cost`)
  - workload: `logreg / w1 / lan`
  - 실행 커맨드:
    - `SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local RUN_ID=ralph_20260316_seededmixed_logreg_w1_lan_dp SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_PLANNER_TRACE_HOPS=603,604,605,606,607,608,610,611,612,613,614 SYSDS_FED_PLANNER_TRACE_MAX_EDGES=24 EXPERIMENT_TIMEOUT_SEC=1800 bash /home/mchoi/reproducibility/sigmod2021-exdra-p523/experiments/run_LAN_localproc.sh --workers 1 --conf mkl-cost --dataset P2P2D --net-profile lan --port-base 9990 --salg logreg`

- **의사결정 근거**
  - runtime/oracle/shared policy를 건드리지 않고, planner decision-map만 최소 확장했다.
  - transient family refine에서
    - transient write/read pair를 alternative output으로 lock
    - 기존 fixed-point output-decision loop를 재실행
    - 그 mixed-output rerun 결과를 기존 family/bundle probe와 같이 score 비교
    하는 seeded simulation을 추가했다.

- **관측 증상**
  - 새 DP trace:
    - `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_seededmixed_logreg_w1_lan_dp.log`
  - 성능은 분명히 좋아졌다.
    - 이전 DP (`trace775_packaged`):
      - `Total execution time: 65.711 sec`
      - `Federated I/O: 2/198/202`
      - `Federated Execute: 604/0`
    - seeded mixed DP:
      - `Total execution time: 60.774 sec`
      - `Federated I/O: 2/32/34`
      - `Federated Execute: 101/0`
  - 하지만 traced `604/607/611/612/613` family는 여전히
    - `DP-OutputDecision-Chosen ... chosen=LOUT`
    - `DP-TransientFamilySeed ... apply=false`
    로 남는다.

- **원인 분석**
  - 이번 trace에서 `612` family alt penalty는 더 이상 duplicate scoring이나 missing family propagation이 아니다.
  - `DP-DecisionMap-AltScore` / `FamilyRoot`를 보면 alt와 chosen의 차이는 사실상 root `612` 하나로 설명된다.
    - chosen root:
      - `rootOrig=612`, `exec=CP`, `out=LOUT`, `cost=451.775039`
    - alt root:
      - `rootOrig=612`, `exec=FED`, `out=FOUT`, `cost=603.856921`
    - delta:
      - `152.081882`
  - 이 delta는 `5.244203`의 FOUT boundary penalty가 loop context에서 반복된 값과 일치한다.
  - 따라서 traced `612` residual은 이제
    - “selected FOUT chain을 못 봐서”
    - “downstream TRead/TWrite subtree를 중복 과금해서”
    가 아니라,
    - **현재 cost model 기준으로 `611 -> 612` FOUT leg 자체가 loop 안에서 비싸다고 계산되는 local output delta**
    로 좁혀졌다.

- **중요한 정정**
  - MinST reference trace를 다시 확인한 결과,
    traced `603-613` hop의 `MinST-Select`는 전부 `selected=CP/LOUT`이다.
  - 즉 이전에 가정했던
    - “MinST가 최종적으로 이 family를 mixed FOUT chain으로 고른다”
    는 해석은 현재 trace 증거와 맞지 않는다.
  - MinST는 `CpFout-Repair` trace를 찍지만, 최종 `Select` 단계에서 이 family 자체를 FOUT로 끝내지는 않는다.

- **새로운 주범**
  - 전체 runtime gap은 현재 604-family residual보다 `multiLogReg` builtin 내부 planning 쪽이 훨씬 크다.
  - heavy hitters 비교:
    - DP:
      - `m_multiLogReg 60.234`
      - `contains 55.365`
    - MinST:
      - `m_multiLogReg 10.329`
      - `fed_contains 1.576`
  - 두 planner 모두 top-level `m_multiLogReg` call (`hop=962`)은 `FED/FOUT`로 들어가지만 내부 graph가 다르다.
    - DP:
      - `hop=962` child list에 `TWrite betas (hop=886)`가 추가됨
      - `Fed X (hop=916)`가 child 없는 leaf
    - MinST:
      - `hop=962` child list에 `886`이 없음
      - `Fed X (hop=916)`가 stamp-slice children `(893, 914, 915)`를 유지
      - runtime heavy hitter가 `fed_contains`
  - builtin script `scripts/builtin/multiLogReg.dml` 상의 핵심 line:
    - `hasNaNs = contains(target=X, pattern=NaN);`
  - 따라서 남은 큰 planner/runtime 차이는
    - **DP에서 `contains(target=X, pattern=NaN)`가 plain CP `contains`로 떨어지고**
    - **MinST에서는 `fed_contains`로 유지되는 경로**
    로 보인다.

- **수정 파일**
  - planner patch:
    - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - 문서 업데이트:
    - `docs/SESSION_ISSUES_2026-03-16.md`

- **검증**
  - `mvn -q -DskipTests compile` → pass
  - `mvn -q -DskipTests package` → pass
  - targeted planner tests:
    - `org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpTransientFamilyDecisionMembersExcludeConsumerParents+testDpContextualTransientBundleFeasibilitySkipsRightIndexSliceWithoutConcreteSource+testDpRewriteKeepsTransientChainConsistentWithFedParentEdge`
    - → pass

- **잔여 이슈**
  - 604-family output-decision residual은 남아 있지만, 현재 evidence상 이게 MinST 대비 전체 성능 차이의 주범은 아니다.
  - 다음 debug target은
    - `m_multiLogReg` 내부 `contains`
    - `Fed X` stamp-slice preservation
    - `hop=962` child set 차이 (`886` 포함 여부)
    를 planner/builtin path에서 분리하는 것이다.
  - 추가 확인:
    - `logreg / w1 / lan / DP` 재실험 (`RUN_ID=ralph_20260316_rewriteseedfix_logreg_w1_lan_dp`)에서
      `DP-OutputDecision-Chosen`는 마지막 iter=3에
      - `hop=46 -> FOUT`
      - `hop=49 -> LOUT`
      - `hop=50 -> FOUT`
      를 찍지만,
      바로 이어지는 `DP-TransientFamilyRefine` / `DP-DecisionMap-FamilyScore`는
      `hop=50 chosen=LOUT alt=FOUT`
      로 family decision을 다시 `LOUT`로 normalize한다.
    - 같은 run에서 rewrite는
      - `hop=50 desiredOut=LOUT`
      - `hop=49 desiredOut=FOUT`
      - `hop=46 desiredOut=LOUT`
      로 남았고, `contains` heavy hitter도 계속 plain `contains`였다.
    - 따라서 이번 시점의 직접 원인은 rewrite seed/priority가 아니라
      **`refineTransientFamilyDecisions`가 `X` family를 최종적으로 `LOUT`로 되돌리는 scoring / normalization 단계**다.
    - rewrite-root-seed / `desiredOut`-first patch는 적용 후에도 trace/runtime이 변하지 않아 즉시 되돌렸다.
  - 추가로 같은 family를 iteration당 한 번만 refine하도록 dedup patch를 넣어도
      `hop=50/49/46` rewrite, `contains` heavy hitter, `Federated I/O 2/32/34`, `Federated Execute 101/0`
      가 그대로였고, patch는 되돌렸다.
    - bundle-wide trace (`RUN_ID=ralph_20260316_familybundletrace_logreg_w1_lan_dp`)로
      `hop=50 (TWrite X)` family와 `hop=1101 (TWrite Grad)` family가 overlap한다는 점은 확인됐다.
      - `hop=50` bundle/family 쪽에는 `1101/277/287`까지 포함된다.
      - 반대로 `hop=1101` family refine는 `200/50/49/46/916`까지 포함한다.
    - 하지만 그 overlap 자체를 겨냥한 두 개의 최소 patch도 효과가 없었다.
      - seed rerun 결과를 local family로만 merge하는 patch
      - overlap family를 같은 pass에서 skip하는 patch
      둘 다 `logreg / w1 / lan / DP` trace 구조와
      `hop=50 desiredOut=LOUT`, plain `contains`, `Federated Execute 101/0`, `I/O 2/32/34`
      를 바꾸지 못해 되돌렸다.
    - 따라서 현재 시점의 더 정확한 판단은:
      - overlap family 현상은 **증상/맥락**으로는 분명하지만
      - 이번 residual의 직접 변이점으로 확정되지는 않았다.
      - 다음 디버깅 포인트는 `refineTransientFamilyDecisions` 바깥의 더 늦은 decision-map scoring,
        혹은 builtin `multiLogReg` 내부 `contains` / `Fed X` stamp-slice 구조 차이를
        다시 상단 priority로 올려 보는 것이다.

- **잠재 회귀 위험**
  - seeded mixed-output rerun은 transient family scoring에만 국한되므로 legality/runtime 조합을 닫지는 않지만,
    fixed-point rerun이 다른 workload의 decision-map을 바꿀 수 있다.
  - 감지 방법:
    - transient-family planner tests 유지
    - `DP-TransientFamilySeed`가 apply되는 workload에서 runtime/`Federated Execute` 변화를 비교

---

## 이슈 3 — DP가 scalar parent(`contains`) 아래의 `TRANSIENTREAD` child share를 과공제해 `hop=1 (TRead X)`를 local로 뒤집음

- **상태**: 부분 해결, runtime 검증은 환경 block
- **환경/조건**: DP planner (`mkl-cost`), `logreg / w1 / lan`, builtin `multiLogReg`, `contains(target=X, pattern=NaN)`
- **핵심 결론**:
  - 함수 인자/파라미터 매핑 mismatch는 아니었다.
  - `hop=1 (TRead X)`는 후보 생성 시점에는 정상적으로 `FED/FOUT`였다.
  - 직접 원인은 `resolveOneHopConflict(...)`가 `computeParentVariantSwitchDelta(...)` 경로에서
    scalar parent `hop=30 (CONTAINS)`에 대해서도 `TRANSIENTREAD` child share를 빼면서,
    `FOUT -> LOUT` 전환을 거의 공짜처럼 보이게 만든 것이다.

### 재현 절차
- planner-only trace 실행:
  - 위치: `/home/mchoi/reproducibility/sigmod2021-exdra-p523/experiments`
  - 커맨드:
    - `SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local RUN_ID=ralph_20260316_scalartrsfix_planneronly_logreg_w1_lan_dp_r2 SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_PLANNER_TRACE_HOPS=1,30,31,46,49,50,916,962 SYSDS_FED_PLANNER_TRACE_MAX_EDGES=12 WORKER_READY_RETRIES=1 WORKER_READY_DELAY=1 WORKER_READY_STRICT=0 EXPERIMENT_TIMEOUT_SEC=1800 bash ./run_LAN_localproc.sh --workers 1 --conf mkl-cost --dataset P2P2D --net-profile lan --port-base 9990 --salg logreg`
  - 로그:
    - `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_scalartrsfix_planneronly_logreg_w1_lan_dp_r2.log`

### 관측 증상
- **patch 전** (`ralph_20260316_hop1trace_logreg_w1_lan_dp`):
  - `hop=1` initial 후보는 `bestFOUT={exec=FED,fType=FULL,cost=0.000000}`
  - 그런데 iter0 output-decision에서
    - `deltaToLOUT=-1.000806`
    - `chosen=LOUT`
  - final rewrite도
    - `hop=1 desiredOut=LOUT effectiveExec=CP effectiveOut=LOUT`
    - `hop=30 desiredOut=LOUT effectiveExec=CP effectiveOut=LOUT`
  - 결과적으로 `contains`가 plain CP 경로로 굳었다.

- **patch 후** (`ralph_20260316_scalartrsfix_planneronly_logreg_w1_lan_dp_r2`):
  - `hop=1` iter0 output-decision:
    - `deltaToLOUT=4455.566003`
    - `chosen=FOUT`
  - final rewrite:
    - `hop=1 desiredOut=FOUT effectiveExec=FED effectiveOut=FOUT`
    - `hop=30 desiredOut=LOUT effectiveExec=FED effectiveOut=LOUT childEdges=[(1,FOUT), (29,LOUT)]`
  - `X` family도 함께 federated로 유지:
    - `hop=46/49/50` final rewrite가 전부 `FED/FOUT`

### 원인 분석
- anchor/planned-FED source는 정상이다.
  - `contains` 후보 생성 시 `FedInputCheck`는 `inputHop=1`에 대해 `plannedFedBefore=true plannedAnchor!=null`을 이미 보고 있다.
- 문제는 DP output-decision cost 보정이다.
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java:2110-2178`
    - `resolveOneHopConflict(...)`가 child output LOUT/FOUT를 고를 때 parent-edge delta만 더해 비교한다.
  - `.../FederatedPlannerDpFedCostBased.java:2247-2366`
    - `computeParentVariantSwitchDelta(...)`가 parent variant 전환 비용을 계산한다.
  - `.../FederatedPlannerDpFedCostBased.java:2369-2408`
    - 기존 `computeTransientReadChildShareAdjustment(...)`는 parent 종류와 무관하게 `TRANSIENTREAD` child share를 공제했다.
- `contains`는 scalar output parent라 child local materialization cost가 별도 shareable subtree로 재사용되지 않는데,
  이 share를 빼버리면 `FED/LOUT contains + FOUT child` 대비 `CP/LOUT contains + LOUT child`가
  `4455.566003 - 4456.566809 ~= -1.000806`처럼 왜곡된다.

### 해결 요약
- 수정 파일:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
- 수정 방향:
  - `computeTransientReadChildShareAdjustment(...)`에 parent plan을 넘기고,
    **parent output이 scalar/non-matrix인 경우에는 TR child share adjustment를 적용하지 않도록 제한**했다.
- 왜 안전한가:
  - runtime이 지원하는 조합을 닫지 않는다.
  - oracle/shared policy/rewrite legality를 건드리지 않는다.
  - output-decision의 cost reconciliation 한 군데만 조정한다.
  - scalar parent는 child matrix materialization을 별도 matrix subtree로 재사용하지 않으므로, share 공제를 하지 않는 편이 더 일관된다.

### 검증
- Build:
  - `mvn -q -DskipTests compile`
  - 결과: pass
- Planner trace:
  - `hop=1`이 iter0부터 `chosen=FOUT`으로 유지되는 것 확인
  - final rewrite에서 `hop=30`이 `FED/LOUT` + `child (1,FOUT)`로 바뀌는 것 확인
- Runtime:
  - 이 환경에서는 local federated socket 생성이 막혀 `fedinit` 단계에서
    `java.net.SocketException: Operation not permitted`가 발생했다.
  - 따라서 실제 wall-clock / heavy hitter(`fed_contains`) 재실행 검증은 **환경 block**이다.
- Tests:
  - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest test`
  - 결과: 현재 worktree에서 `testDpTransientFedParentForwardingChargesRefedShare` 실패
  - 참고: 이 테스트는 `computeParentChildForwardingCostShare(...)` 경로를 직접 검증하며,
    이번 수정 helper와는 다른 영역이라 **unrelated residual일 가능성이 크다**. 다만 현재 시점에는 green suite를 주장할 수 없다.

### 잔여 이슈
- planner 관점에서는 `contains(X)` localize 원인은 분리되었고, rewrite도 MinST 쪽 구조와 맞춰졌다.
- 하지만 실제 runtime 성능 회복(`fed_contains` heavy hitter, wall-clock 단축)은
  현 실행 환경의 local socket 제한 때문에 아직 직접 확인하지 못했다.

---

## 이슈 N — logreg / w1 / lan DP residual은 generic one-hop conflict scoring이 clone edge와 same-output compatible variant cost를 빠뜨리던 문제였다

- **상태**: 해결
- **환경/조건**: DP planner (`mkl-cost`), `logreg`, `workers=1`, `net=lan`, dataset=`P2P2D`
- **의사결정 근거**: runtime/oracle/shared policy를 바꾸지 않고, planner의 generic conflict scoring이 실제 rewrite-compatible plan cost를 보도록 바로잡는 수정이다. runtime이 지원하는 조합을 닫는 가드는 추가하지 않았다.

### 재현 절차
- clean confirmation run:
  - 위치: `/home/mchoi/reproducibility/sigmod2021-exdra-p523/experiments`
  - 커맨드:
    - `SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local RUN_ID=ralph_20260316_cleanconfirm_logreg_w1_lan_dp EXPERIMENT_TIMEOUT_SEC=1800 bash ./run_LAN_localproc.sh --workers 1 --conf mkl-cost --dataset P2P2D --net-profile lan --port-base 10020 --salg logreg`
- trace repros used during isolation:
  - clone-only residual:
    - `SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local RUN_ID=ralph_20260316_clonefilter_pkg_logreg_w1_lan_dp SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_PLANNER_TRACE_HOPS=197,198,417,420,422,1227,1228 SYSDS_FED_PLANNER_TRACE_MAX_EDGES=16 EXPERIMENT_TIMEOUT_SEC=1800 bash ./run_LAN_localproc.sh --workers 1 --conf mkl-cost --dataset P2P2D --net-profile lan --port-base 10020 --salg logreg`
  - line 108 residual:
    - `SYSTEMDS_ROOT=/home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local RUN_ID=ralph_20260316_line108compat_logreg_w1_lan_dp SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_PLANNER_TRACE_HOPS=30,1109,200,277,420,422 SYSDS_FED_PLANNER_TRACE_MAX_EDGES=16 EXPERIMENT_TIMEOUT_SEC=1800 bash ./run_LAN_localproc.sh --workers 1 --conf mkl-cost --dataset P2P2D --net-profile lan --port-base 10020 --salg logreg`

### 관측 증상
- clone-filter patch만 적용된 중간 상태에서는:
  - `Total execution time: 63.588 sec`
  - `Federated I/O: 2/306/310`
  - `Federated Execute: 923/0`
  - line 197/198은 이미 `fed_ba+*`로 내려갔지만, line 108 `rowSums (X ^ 2)`는 still plain `uarsqk+` CP였다.
- line 108 trace에서 `hop=1109`는 candidate 단계에서 이미 좋은 FED/FOUT variant가 있었는데도,
  final rewrite가 `effectiveExec=CP effectiveOut=FOUT childEdges=[(160,LOUT)]`로 굳었다.
- 즉 planner가 generic one-hop conflict를 풀 때
  - virtual clone parent edge를 executable member와 같은 decision 대상으로 세고 있었고
  - same-output(`FOUT`) 안에서 child decisions 때문에 비싸지는 compatible variant cost를 보지 못했다.

### 원인 분석
#### 1. clone + executable hop merged conflict
- `collectConflictsSingleBFS(...)`는 conflict entry를 original hop id 기준으로 모은다.
- 그래서 executable hop과 virtual clone이 하나의 entry로 합쳐질 수 있다.
- 기존 `resolveOneHopConflict(...)`는 merged entry의 `memberHopIDs` 전체를 그대로 cost-edge 계산에 사용해서,
  executable hop과 무관한 clone parent edge까지 delta에 포함했다.
- logreg trace에서는 이 경로가 `hop=422`를 local 쪽으로 끌었다.

#### 2. same-output compatible variant shift omission
- `resolveOneHopConflict(...)`는 기존에 parent-edge switch delta만 더했다.
- 하지만 `hop=1109` 같은 경우에는 output 자체는 여전히 `FOUT`이어도,
  현재 child decisions 아래에서는 cheap `FED/FOUT`가 아니라 expensive `CP/FOUT` compatible variant를 rewrite가 선택하게 된다.
- 기존 scoring은 이 “same-output variant shift” 비용을 보지 못해서, planner는 FOUT가 싼 것처럼 판단하고 rewrite만 비싼 plan을 타는 mismatch가 생겼다.

### 해결 요약
- 수정 파일:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- 변경 요약:
  - generic one-hop conflict resolve가 `selectDecisionMembers(...)`를 재사용해 executable/original member가 있으면 virtual clone member cost edge를 건너뛰게 했다.
  - output-decision fixpoint에서 tentative decision map을 만들어 generic one-hop / transient conflict resolve에 넘기게 했다.
  - `computeCompatiblePlanSelectionDelta(...)`를 추가해, 현재 tentative child decisions 아래에서 rewrite가 실제로 고를 strict-compatible variant share 차이를 `lOutAdditionalCost` / `fOutAdditionalCost`에 반영했다.
- 왜 안전한가:
  - runtime legality, oracle caps, shared placement policy는 변경하지 않는다.
  - planner candidate space를 닫지 않는다.
  - 이미 memo에 있는 합법 variant들 사이에서 “실제로 rewrite가 쓰는 cost”를 generic conflict score에 맞춰주는 수정이다.

### 검증
- Build:
  - `mvn -q -DskipTests package`
  - 결과: pass
- Focused tests:
  - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest,FederatedPlannerDpRewireTransTableTest test`
  - 결과: pass
  - 새 regression:
    - `testDpResolveOneHopConflictSkipsVirtualCloneCostEdgesWhenExecutableMemberExists`
    - `testDpResolveOneHopConflictAccountsForSameOutputCompatibleVariantShift`
- Runtime evidence:
  - clean confirmation log:
    - `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_cleanconfirm_logreg_w1_lan_dp.log`
  - clean confirmation inst stats:
    - `experiments/results/inst_stats/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_cleanconfirm_logreg_w1_lan_dp_run1.csv`
  - clean confirmation summary:
    - `Total execution time: 9.043 sec`
    - `Federated I/O: 2/335/339`
    - `Federated Execute: 1016/0`
  - baseline after clone-only fix:
    - `experiments/results/fed1/mkl-cost/logreg_dataset-P2P2D_dams-so002_mkl-cost_ralph_20260316_clonefilter_pkg_logreg_w1_lan_dp.log`
    - `63.588 sec`, `2/306/310`, `923/0`
  - comparison against MinST reference:
    - `experiments/results/fed1/mkl-min-st-cut/logreg_dataset-P2P2D_dams-so002_mkl-min-st-cut_ralph_20260316_logreg_w1_lan_minst_trace_604fam.log`
    - `10.981 sec`, `2/486/489`, `1749/0`
- Hot line confirmation from inst stats:
  - clean DP line 108 is `fed_uarsqk+` and no longer plain CP `uarsqk+`
  - clean DP line 197/198 remain federated (`fed_ba+*`)

### 잔여 이슈
- 현재 logreg / w1 / lan / DP는 이 residual 기준으로는 더 이상 MinST보다 느리지 않다.
- 다음 우선순위는 같은 planner change가 다른 workloads/profile에서 어떤 영향이 있는지 matrix 수준으로 다시 확인하는 것이다.

### 잠재 회귀 위험
- generic one-hop resolve가 tentative decisions를 더 많이 보게 되었으므로, clone/compatible-variant가 많은 loop body에서 decision flip이 달라질 수 있다.
- 감지 방법:
  - planner trace에서 `DP-OutputDecision-Entry`, `DP-OutputDecision-Edge`, `DP-Rewrite-Plan`을 함께 보고
  - clean inst stats에서 line-level opcode가 trace와 일관되게 내려가는지 확인한다.
