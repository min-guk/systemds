# SESSION ISSUES - 2026-02-17

## 이슈 1: MinST cp-only multi-parent scaling 재도입(patchAO2)에서 대형 성능 회귀
- **상태**: 해결(롤백)
- **환경/조건**:
  - planner: `mkl-min-st-cut`
  - workload/dataset: `sliceline_ADULT`
  - workers: `w2,w3,w4`
  - profiles: `wan_light, wan_mid`
  - systemds root: `tmp/systemds-local`
  - trace: `SYSDS_FED_PLANNER_TRACE=1`, hops=`1522,1523,1525,1541,1542,1543`

- **재현 절차**:
  - build: `mvn -q -DskipTests clean package`
  - run:
    - `RUN_ID=20260217_0612_patchAO2_w2_lightmid ... --workers 2 --net-profiles wan_light,wan_mid --no-net-cache`
    - `RUN_ID=20260217_0615_patchAO2_w3_lightmid ... --workers 3 --net-profiles wan_light,wan_mid --no-net-cache`
    - `RUN_ID=20260217_0620_patchAO2_w4_lightmid ... --workers 4 --net-profiles wan_light,wan_mid --no-net-cache`
    - `RUN_ID=20260217_0628_patchAO2_w4_midfill ... --workers 4 --net-profile wan_mid --skip-net-check`

- **관측 증상**:
  - Rule4 위반 수는 4→2로 감소했지만, 절대 시간 대폭 악화:
    - w2 wan_light: `8.151 -> 18.225`
    - w2 wan_mid: `10.019 -> 30.679`
    - w3 wan_light: `8.327 -> 19.091`
    - w3 wan_mid: `10.188 -> 30.648`
    - w4 wan_light: `9.541 -> 18.056`
    - w4 wan_mid: `10.215 -> 32.590`
  - I/O도 급증: `fed_put`, `fed_get`, `fed_exec_inst` 증가.

- **원인 분석**:
  - `addParentChildNetEdge`에서 cp-only multi-parent scale(`numParents+1`)를 적용하자,
    본 commit 계열(HEAD `7ff2c187...`)에서는 목표 체인뿐 아니라 더 넓은 경로가 FED 쪽으로 이동.
  - 결과적으로 `fed_put/get`와 대용량 업로드가 증가해 전체 실행시간이 크게 악화.

- **해결 요약**:
  - patchAO2 변경 즉시 롤백.
  - Rule4 일부 개선만으로는 수용 불가(대형 성능 회귀)로 판단.

- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java` (patchAO2 적용 후 롤백)

- **검증**:
  - rules: `/tmp/patchAO2_rules.txt` (violations=2)
  - selected: `/tmp/patchAO2_selected.csv`
  - baseline compare: `/tmp/patchAO_selected.csv`
  - rollback 후 `git status`에서 해당 파일 clean 확인

- **잔여 이슈**:
  - Rule3를 유지하면서 Rule4 strict를 동시에 만족하는 안정적 MinST 가설 미확보.

- **잠재 회귀 위험 + 감지**:
  - 위험: cp-only 경계비용 스케일 조정이 전체 FED 경로를 광범위하게 뒤틀어 대형 업로드를 유발할 수 있음.
  - 감지: `fedplanner_matrix_rules_check` + selected CSV의 `fed_io_put/get`, `fed_put_bytes_mat` 동시 비교 필수.

- **의사결정 근거(oracle/runtime/planner)**:
  - planner cost-model(MinST boundary edge)만 조정했고, oracle/runtime 제약 완화는 수행하지 않음.

