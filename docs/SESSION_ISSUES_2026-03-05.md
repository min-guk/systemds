# SESSION ISSUES (2026-03-05)

## 이슈 1: MinST가 PCA에서 TRANSIENTREAD(Components)의 FED/FOUT 후보를 과도하게 차단하여 CP `ba+*`로 붕괴

- **상태**: 해결
- **환경/조건**:
  - planner: `mkl-min-st-cut` (MinST)
  - workload/profile: `pca`, `lan/wan_light/wan_mid`
  - dataset: `P2P2D`
  - 실험 축: workers `1..4`, planners `mkl-fout,mkl-cost,mkl-min-st-cut`
- **재현 절차**:
  1) (수정 전) 36셀 실행
     ```bash
     SYSDS_FED_PLANNER_TRACE=1 RUN_TIMEOUT_SEC=3000 \
     bash experiments/run_LAN_docker_matrix_until_complete.sh \
       --workers-list 1,2,3,4 \
       --planner-confs mkl-fout,mkl-cost,mkl-min-st-cut \
       --workloads pca --net-profiles lan,wan_light,wan_mid \
       --dataset P2P2D \
       --run-id-prefix pca_powereig4_fedall_dp_minst_20260305_0310 \
       -- --systemds-root /home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local
     ```
  2) 대표 로그 확인(수정 전, MinST w1 lan):
     - `experiments/results/fed1/mkl-min-st-cut/pca_dataset-P2P2D_coordinator_mkl-min-st-cut_pca_powereig4_fedall_dp_minst_20260305_0310_..._w1_mkl-min-st-cut_pca_lan_lan.log`
- **관측 증상**:
  - 수정 전 MinST 실행시간(대표):
    - `lan/w1=65.811s`, `wan_light/w1=67.800s`, `wan_mid/w1=71.847s`
  - trace에서 핵심 경로:
    - `hop=55 (TRead Components)` caps가 `[CP_LOUT=true, CP_FOUT=false, FED_LOUT=false, FED_FOUT=false]`
    - `hop=57 (ba(+*))`에서 `hasUnmaterializableLocal=true`로 FED 입력 불충족 판정
    - 결과적으로 recompile에서 `ba(+*)`가 CP로 강제되어 대규모 CP 연산 발생
- **원인 분석**:
  - `FederatedPlanMinSTRewire`의 TRANSIENTREAD 처리에서
    `hasConcreteTransientReadSource=false`일 때 FED 후보를 일괄 차단.
  - 이 로직이 **mapped TRANSIENTWRITE가 이미 FED/FOUT 경로를 보유한 경우**까지 막아,
    DP가 정상적으로 선택하는 FED 경로(derived/refed 가능)를 MinST만 배제함.
- **해결 요약**:
  - TRANSIENTREAD에 대해 concrete source가 없더라도,
    **mapped source로부터 FED/FOUT 경로가 이미 확인된 경우는 FED/FOUT 유지**하도록 수정.
  - 즉, 무조건 차단 → 조건부 차단으로 변경.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
- **검증**:
  1) 빌드:
     ```bash
     cd tmp/systemds-local && mvn -q -DskipTests package
     ```
  2) 스모크(3셀, w1/lan):
     - run id: `pca_powereig4_patchcheck_w1_lan_20260305_0350`
     - 결과: `mkl-fout=8.261s`, `mkl-cost=7.278s`, `mkl-min-st-cut=6.698s`
  3) 전체 36셀 재실행:
     - run id: `pca_powereig4_fedall_dp_minst_fix1_20260305_0400`
     - heartbeat: `completed_success`
     - state counts: `success=36, failed=0, start=0`
     - MinST 성능 개선(수정 전 → 수정 후):
       - `lan/w1: 65.811 -> 6.409`
       - `wan_light/w1: 67.800 -> 7.615`
       - `wan_mid/w1: 71.847 -> 10.500`
       - workers 1~4 전 구간에서 대폭 개선(대부분 7~60초 단축)
- **잔여 이슈**:
  - PCA 3-planner Rule2(간이) 기준(`MinST <= DP <= FedAll`)에서 소수 셀(3개)이 경미하게 남음:
    - `lan/w2`, `lan/w3`, `wan_light/w3` (MinST가 DP보다 약간 느림)
  - 다만 기존 대회귀(수십 초) 성격은 해소됨.
- **잠재 회귀 위험**:
  - concrete source가 없는 TR에 FED/FOUT 여지를 남겼으므로,
    일부 DAG에서 FED 후보군이 증가할 수 있음.
  - 감지 방법: planner trace에서 `hasUnmaterializableLocal` 증가 여부 및
    runtime fatal signature(`requires federated input but found local`) 재발 여부 모니터링.
- **의사결정 근거**:
  - **MinST rewire의 과도한 후보 차단 버그 수정(공통 합법 후보 복원, ad-hoc workload 분기 없음)**


## 이슈 2: MinST fallback strict화 시도는 logreg/lan(w1)에서 대회귀를 유발해 롤백

- **상태**: 롤백(폐기)
- **환경/조건**:
  - planner: `mkl-min-st-cut` (MinST)
  - workload/profile: `logreg/lan`, workers=1
- **재현 절차**:
  1) strict fallback 버전으로 빌드 후 실행
     - run id: `srcfix_logreg_lan_w1_notrace2_20260305_20260305_235725_180195`
  2) 롤백 후 동일 조건 재실행
     - run id: `srcfix_logreg_lan_w1_finalcheck_20260305_20260306_000605_208604`
- **관측 증상**:
  - strict fallback 버전:
    - MinST `Total elapsed=72.254s`, `Fed Execute=92`, `fed_refed=30`
  - 롤백 후:
    - MinST `Total elapsed=11.373s`, `Fed Execute=1007`, `fed_refed=335`
  - 동일 조건에서 strict fallback이 MinST를 CP 위주로 붕괴시켜 대회귀를 만들었음.
- **원인 분석**:
  - `canSatisfyFederatedInputsFromFTypes(...)` 불충족 시 fallback을 과도하게 닫으면,
    logreg 반복 구간의 FED 후보가 사라져 비용 기반 비교 이전에 CP 경로로 수렴함.
- **해결 요약**:
  - strict fallback 변경은 폐기하고, 기존 fallback 동작(인덱싱 제외 + FED 대안 존재 시 유지)으로 복원.
  - 현재 파일에는 기능 변화 없이 시그니처 정리(미사용 인자 제거)만 유지.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
- **검증**:
  - 빌드 성공:
    ```bash
    cd tmp/systemds-local && mvn -DskipTests package
    ```
  - 타깃 스모크:
    - `srcfix_logreg_lan_w1_finalcheck_20260305_20260306_000605_208604` (2/2 success)
      - MinST elapsed `11.373s`, DP elapsed `9.137s`
    - `srcfix_kmeans_lan_w1_smoke_20260305_20260306_000842_223313` (2/2 success)
      - MinST elapsed `22.739s`, DP elapsed `23.268s`
  - 2셀 스모크 성공(violations=0, warnings=0) 및 회귀 해소 확인.
- **잔여 이슈**:
  - MinST vs DP 성능 간격(특히 WAN/kmeans, l2svm w1)은 별도 원인(비용모델/오라클)로 계속 추적 필요.
- **잠재 회귀 위험**:
  - fallback을 유지하므로 특정 DAG에서 refed가 다시 과다해질 수 있음.
  - 감지 방법: trace에서 `Fed Execute`/`fed_refed` 급증과 실행시간 동반 상승을 같이 모니터링.
- **의사결정 근거**:
  - **대회귀를 만든 단일 수정은 즉시 롤백하고, 근거 있는 공통(cost/oracle) 수정으로 전환**

## 이슈 3: DP/MinST recompile 구간 CP->FOUT 허용 불일치 (공통 합법성 제약 위반)

- **상태**: 진행중
- **환경/조건**:
  - planner: `mkl-cost`(DP) vs `mkl-min-st-cut`(MinST)
  - 대상: loop/function recompile 경로
- **관측 증상**:
  - MinST는 `isRecompileRegion(hop)`에서 `allowCP_FOUT=false`를 강제.
  - DP는 `DISALLOW_CPFOUT_ON_RECOMPILE=false`로 동일 제약을 적용하지 않아, planner 간 후보 공간 비대칭이 발생.
- **원인 분석**:
  - 공통 합법성 제약(재컴파일 구간 CP->FOUT 금지)이 DP에서 비활성화되어
    DP/MinST fairness와 규칙 일관성이 깨짐.
- **해결 요약**:
  - DP의 `DISALLOW_CPFOUT_ON_RECOMPILE`를 `true`로 변경하여
    MinST와 동일한 전역 합법성 제약을 적용.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
- **검증**:
  - 빌드 성공:
    ```bash
    cd tmp/systemds-local && mvn -DskipTests package
    ```
- **잔여 이슈**:
  - 성능 영향(특히 `logreg -> kmeans -> pca`)은 타깃 셀 trace로 확인 필요.
- **잠재 회귀 위험**:
  - 일부 케이스에서 CP->FOUT 기반 우회 경로가 줄어 CP/LOUT 또는 FED/LOUT 선택이 늘어날 수 있음.
  - 감지 방법: trace에서 recompile 구간의 `allowCP_FOUT` 및 `fed_refed`/upload 경계 비용 변화 비교.
- **의사결정 근거**:
  - **planner별 특례가 아니라 문서화된 전역 합법성 제약을 공통 적용**


## 이슈 4: MinST PCA에서 OPTIONAL edge의 native FED/FOUT 힌트를 과도하게 null 처리해 `tsmm`가 CP로 붕괴

- **상태**: 부분 해결(추적 중)
- **환경/조건**:
  - planner: `mkl-min-st-cut` (MinST), 비교군 `mkl-cost` (DP)
  - workload/profile: `pca/lan`, workers=1
  - dataset: `P2P2D`
- **재현 절차**:
  1) (수정 전 대표) `srcfix_pca_lan_w1_smoke_20260305_20260306_001128_236599`
  2) (수정 후 대표) `srcfix7_smoke_lan_w1_20260306_20260306_005035_321063`
  3) 재확인: `srcfix7_final_pca_lan_w1_20260306_20260306_011046_366796`
- **관측 증상**:
  - 수정 전 MinST pca/lan/w1:
    - `Total execution time=119.715s`
    - heavy hitter: `tsmm 60.979s` (CP), `eigen 53.515s`
  - 수정 후 MinST pca/lan/w1:
    - `63.401s` (run: `srcfix7_smoke...`)
    - `65.263s` (run: `srcfix7_final_pca...`)
    - heavy hitter: `fed_tsmm` 복원, `tsmm` CP 붕괴 해소
- **원인 분석**:
  - `FederatedPlanMinSTRewire`에서 local-capable OPTIONAL 입력에 대해
    `oracleInputFType=null`로 내리는 조건이 너무 강해,
    child가 이미 **native FED/FOUT** 경로를 보유해도 Oracle hint가 사라짐.
  - 그 결과 MinST가 FED TSMM 후보를 충분히 유지하지 못하고 CP TSMM 쪽으로 수렴.
- **해결 요약**:
  - non-vector parent에서 child가 native FED/FOUT인 경우,
    OPTIONAL edge라도 FED hint를 보존(`preserveNativeFedHint`)하도록 수정.
  - 입력 hint nulling / infer 조건에 동일 플래그를 반영해 후보 공간을 복원.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
- **검증**:
  - 빌드:
    ```bash
    cd tmp/systemds-local && mvn -DskipTests package
    ```
  - 6셀 스모크(성공, rules violation=0):
    - `srcfix7_smoke_lan_w1_20260306_20260306_005035_321063`
    - pca: MinST `63.401s`, DP `59.458s`
  - pca 2셀 재검증(성공, rules violation=0):
    - `srcfix7_final_pca_lan_w1_20260306_20260306_011046_366796`
    - pca: MinST `65.263s`, DP `64.817s`
  - 참고: 중간 실험 `srcfix7b_smoke...`는 MinST pca가 일시적으로 `133.093s`로 흔들렸으나,
    동일 코드 재빌드/재실행에서 `~65s`로 재현 회복됨(런 변동/환경 간섭 가능성).
- **잔여 이슈**:
  - `kmeans/logreg (lan,w1)`은 기존 대비 변동폭이 커서(특히 DP) 추가 안정화/반복 측정 필요.
  - WAN(light/mid)에서 동일 효과가 유지되는지 추가 확인 필요.
- **잠재 회귀 위험**:
  - OPTIONAL edge에서 FED hint를 더 오래 유지하므로,
    일부 DAG에서 FED 후보/경계비용 탐색량이 늘 수 있음.
  - 감지 방법: `hasUnmaterializableLocal`, `fed_refed`, `fed_tsmm/tsmm` 비율을 trace+HH로 모니터링.
- **의사결정 근거**:
  - **planner별 ad-hoc 가드가 아닌, 공통 합법 후보(Oracle hint) 복원으로 해결**


## 운영 이슈: 실험 중 디스크 full(`No space left on device`)로 로그reg 셀 실패

- **상태**: 해결(운영 복구)
- **관측 증상**:
  - run `srcfix7_minst_nativehint_lan_w1_20260306...` 중 logreg에서
    `org.apache.hadoop.fs.FSError: java.io.IOException: No space left on device`
    로 rc=86/1 실패.
- **원인 분석**:
  - `/` 파티션 100% 사용 (`/dev/sda2 413G used 392G, avail 292K`)
  - `experiments/tmp/systemds_snapshots` 누적으로 약 15G 점유.
- **해결 요약**:
  - snapshot 정리:
    ```bash
    rm -rf experiments/tmp/systemds_snapshots/*
    ```
  - 복구 후 `/` 여유 약 15G 확보, 동일 조건 logreg 재실행 성공.
- **검증**:
  - rerun: `srcfix7_logreg_retry_lan_w1_20260306_20260306_004821_313484`
  - 결과: 2/2 success, rule violations=0.
