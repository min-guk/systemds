# SESSION ISSUES (2026-02-12)

## 이슈 1: `logreg` (`w3`, `mkl-heuristic/mkl-fout`)에서 `FED?tak+*`가 런타임 미지원 조합으로 실패

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `mkl-heuristic`, `mkl-fout`
  - Workload/Profile: `logreg`, `lan/wan_light/wan_mid`, `workers=3`
  - 실패 로그 예시:
    - `/home/mchoi/reproducibility/sigmod2021-exdra-p523/experiments/results/fed3/mkl-heuristic/logreg_P2P_coordinator_mkl-heuristic_full240b_20260211_121323_20260211_121323_80988_w3_mkl-heuristic_logreg_lan_lan.log`
- **재현 절차**:
  1. `run_LAN_docker_matrix.sh` 상태 파일에서 실패 셀(`w3 mkl-heuristic/mkl-fout logreg *`)을 추린다.
  2. coordinator 로그에서 `FED?tak+*` 및 stack trace를 확인한다.
- **관측 증상**:
  - `Federated AggregateTernary not supported ...` 예외가 발생하며 실행 실패.
  - `FED?tak+*?Y?...?_mVar120...` 경로에서 Y와 보조 입력의 FED map 정렬이 맞지 않는 케이스가 발생.
- **원인 분석**:
  - vector-vector 경로에서 `resolveVectorVectorRequirement`가 "한쪽만 concrete FED source"인 경우에도 비-FED 입력을 REQUIRED로 강제.
  - 결과적으로 불필요한 `CP->FOUT`이 삽입되어 `tak+*`에 dual-fed(비정렬 가능) 조합이 유입.
- **해결 요약**:
  - `FederatedRefedPolicy.resolveVectorVectorRequirement`에 full-aggregate 소비(`sum(RowCol)`) 특수 규칙 추가.
  - concrete FED source가 1개인 경우, 나머지 벡터 입력을 OPTIONAL로 유지해 불필요한 CP->FOUT 강제를 차단.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
- **검증**:
  - `mvn -q -DskipTests compile`
  - `mvn -q -Dtest=FederatedRefedPolicyTest test`
  - 실패 셀 재실험 완료:
    - `fix7_20260212_0930_1_3_mkl-heuristic_logreg_lan_lan` success
    - `fix7_20260212_0930_2_3_mkl-heuristic_logreg_wan_light_wan_light` success
    - `fix7_20260212_0930_3_3_mkl-heuristic_logreg_wan_mid_wan_mid` success
    - `fix7_20260212_1035_4_3_mkl-fout_logreg_lan_lan` success
    - `fix7_20260212_1035_5_3_mkl-fout_logreg_wan_light_wan_light` success
    - `fix7_20260212_1035_6_3_mkl-fout_logreg_wan_mid_wan_mid` success
- **잔여 이슈**:
  - 없음(해당 실패 재발 미관측).
- **잠재 회귀 위험**:
  - dot-like vector 경로에서 OPTIONAL 판정 확대로 FED 후보 공간이 달라질 수 있음.
  - 감지 방법: `logreg/lm/l2svm`의 `CP->FOUT insert` 및 `fed_refed/fed_fout` 카운트 비교.
- **의사결정 근거(oracle/런타임/플래너)**:
  - runtime fallback 없이, 플래너 입력 요구 판정을 런타임 실제 지원 조합에 맞춰 조정.

## 이슈 2: `l2svm` (`w4`, `mkl-cost`, `lan`)에서 CP->FOUT BROADCAST 강제로 worker 차원 불일치 발생

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `mkl-cost`
  - Workload/Profile: `l2svm`, `lan`, `workers=4`
  - 실패 로그 예시:
    - `/home/mchoi/reproducibility/sigmod2021-exdra-p523/experiments/results/fed4/mkl-cost/l2svm_P2P_coordinator_mkl-cost_full240b_20260211_121323_20260211_121323_80988_w4_mkl-cost_l2svm_lan_lan.log`
- **재현 절차**:
  1. `w4/mkl-cost/l2svm/lan` 케이스 실행.
  2. worker 로그에서 `CP?1-*` 차원 불일치 및 `Failed to getVariable` 연쇄 오류 확인.
- **관측 증상**:
  - `Block sizes are not matched for binary cell operations: 25000x1 vs 100000x1`
  - 이후 `Variable does not exist` / `Failed to getVariable`로 실패 종료.
- **원인 분석**:
  - CP->FOUT 등록 시 `plannedFType==BROADCAST`를 무조건 우선해 `fed_fout(BROADCAST)`를 강제.
  - concrete ROW/COL anchor가 있어도 aligned upload/refed 경로를 배제해 worker-local 연산에서 shape mismatch 유발.
- **해결 요약**:
  - `FederatedRefedPolicy.registerCpfoutWithSelection`에서 `planned BROADCAST` 강제 분기 보정.
  - concrete axis anchor가 있고 axis mismatch가 없는 경우 BROADCAST 강제를 해제하고 aligned 경로를 허용.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedRefedPolicyTest.java`
- **검증**:
  - `mvn -q -DskipTests package`
  - `mvn -q -Dtest=FederatedRefedPolicyTest test`
  - 실패 셀 재실험 완료:
    - `fix7_20260212_1035_7_4_mkl-cost_l2svm_lan_lan` success
- **잔여 이슈**:
  - 없음(해당 실패 재발 미관측).
- **잠재 회귀 위험**:
  - 기존에 BROADCAST 고정으로 비용이 맞던 일부 케이스에서 경로 선택 변화 가능.
  - 감지 방법: `fed_fout(BROADCAST)` 비율 및 worker-side shape mismatch 로그 모니터링.
- **의사결정 근거(oracle/런타임/플래너)**:
  - runtime 우회 없이 planner/register 단계에서 CP->FOUT shape 정책을 정렬.

## 이슈 3: `kmeans` WAN에서 worker 증가 시 시간 역전 (`w↑`, `time↑`)의 구조적 원인

- **상태**: 분석완료 (수정안 탐색 중)
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `mkl-cost`, `mkl-min-st-cut`
  - Workload/Profile: `kmeans`, `wan_light/wan_mid`, `workers=1..4`
- **핵심 관측**:
  - `mkl-cost`, `wan_mid`:
    - `w1: 75.828s, I/O=2/0/2`
    - `w2: 269.038s, I/O=4/183/244`
    - `w3: 329.725s, I/O=6/184/305`
    - `w4: 386.278s, I/O=8/185/366`
  - `fed_ba+*`/`fed_fed_fout` 호출 수는 거의 고정(121/61)인데 worker 수 증가에 따라 fan-out 통신이 누적 증가.
- **trace 기반 원인**:
  - `trace_20260212_kmeans_w2_dp_wan_mid_wan_mid`에서 `hop=180(ba+*)` DP 후보 비용:
    - `CP_LOUT=330740.778`, `FED_LOUT=148312.507`
  - `w1`에서는 `X` 입력이 `FULL`이라 `hop=180`이 CP로 유지되지만,
    `w2+`에서는 `ROW`가 열리며 FED 경로가 비용상 우세로 선택됨(레짐 전환).
- **폐기한 가설**:
  - FED per-op overhead를 cost model에 추가하는 실험을 수행했으나 역효과:
    - `overheadfix_20260212_kmeans_w2_dp_wan_mid_wan_mid`
    - `269.038s -> 356.611s`, `Fed Put Bytes 25,677,208 -> 889,689,688`
  - 해당 코드는 즉시 롤백(현재 working tree 잔존 변경 없음).
- **다음 액션**:
  - conf-specific 예외 없이, planner 후보공간/경계 materialization 비용에서
    `w1(FULL)->w2+(ROW)` 레짐 전환이 과도해지는 지점을 trace 기반으로 추가 분해.
  - Rule4는 `lan=strict`, `wan_light/wan_mid=warn-only` 운영을 유지.
