# Session Issues 2026-02-27

## Issue 1: DP planner under-estimates FED per-op overhead in unrolled loops → kmeans WAN에서 DP가 Heuristic보다 느림

- **상태**: 해결(코드 수정) / 런타임 성능 재검증 필요
- **환경/조건**
  - dataset: `P2P2D`
  - workers: `4`
  - profiles: `wan_light`, `wan_mid`
  - planners: `mkl-cost(DP)` vs `mkl-heuristic(Heuristic)` / `mkl-fout(FedAll)`
  - 로그(대표):
    - DP(느림, wan_light): `experiments/results/fed4/mkl-cost/kmeans_dataset-P2P2D_coordinator_mkl-cost_kmeans_wan_dp_minst_fix_20260227_103051_2509085_w4_mkl-cost_kmeans_wan_light_wan_light.log`
    - Heuristic(빠름, wan_light): `experiments/results/fed4/mkl-heuristic/kmeans_dataset-P2P2D_coordinator_mkl-heuristic_matrix_public_20260226t1325_20260226_142054_2878338_w4_mkl-heuristic_kmeans_wan_light_wan_light.log`
    - DP(느림, wan_mid): `experiments/results/fed4/mkl-cost/kmeans_dataset-P2P2D_coordinator_mkl-cost_localsys_kmeans_20260227_050640_20260227_060640_1700645_w4_mkl-cost_kmeans_wan_mid_wan_mid.log`
    - Heuristic(빠름, wan_mid): `experiments/results/fed4/mkl-heuristic/kmeans_dataset-P2P2D_coordinator_mkl-heuristic_matrix_public_20260226t1325_20260226_142054_2878338_w4_mkl-heuristic_kmeans_wan_mid_wan_mid.log`

- **재현 절차**
  - 기존 로그 기반 확인:
    - `python3 experiments/scripts/fedplanner_matrix_rules_check.py --dataset P2P2D --results-dir experiments/results --workers-list 4 --planner-confs mkl-min-st-cut,mkl-cost,mkl-heuristic,mkl-fout --workloads kmeans --profiles wan_light,wan_mid`

- **관측 증상**
  - `kmeans`에서 `DP > Heuristic/FedAll` 위반:
    - wan_light: `dp=98.503s`, `heu=89.530s`, `fedall=89.218s`
    - wan_mid: `dp=349.277s`, `heu=312.562s`, `fedall=311.789s`
  - DP 로그 heavy hitter에서 반복 루프(약 54 iter)마다 많은 federated elementwise op가 수행됨:
    - 예: `fed_<=`, `fed_+`, `fed_*`, `fed_r'`, `fed_uark+`, `fed_uak+`, `fed_fed_refed` 등이 각각 `count≈54`로 반복
  - Heuristic은 같은 반복에서 `+`, `<=`, `r'` 등을 주로 로컬(CP)에서 처리하고 `fed_fed_fout`(업로드/재배치) 호출이 증가하지만,
    WAN에서는 “다수의 FED 호출(고지연)”보다 “상대적으로 큰 전송 + 로컬 연산”이 더 유리하여 더 빠르게 관측됨.

- **원인 분석**
  - DP 비용모델에서 FED 실행의 **per-op coordination overhead**(`fedOverhead`)가 루프 반복 횟수로 스케일되지 않아,
    루프 내부의 fine-grained FED 실행이 과소평가됨.
  - PlannerTrace 근거(예: `hop=1098 (b(<=))`):
    - `self[CP=113.698810,FED=294.162810]` 형태로 FED self cost가 “(루프 반복에 비례하는)” dispatch/coordination 비용을 반영하지 못함.
    - 결과적으로 DP는 루프 내부 연산을 FED로 길게 유지하는 계획을 선택하고, 실제 실행에서는 WAN latency가 누적되어 느려짐.

- **해결 요약**
  - DP의 `fedOverhead`를 `hopCommon`의 루프-unroll `multiplicity`를 포함하는 hop-local weight로 스케일하도록 수정.
    - 기존: `hopNetworkWeight * computeNetworkCost(0) * numWorkers`
    - 변경: `hopPlacementWeight(=computeWeight*multiplicity) * computeNetworkCost(0) * numWorkers`
  - 의도: 루프 내부 FED 호출 비용(특히 WAN latency)을 DP가 제대로 페널티로 반영하여,
    필요 시 Heuristic/MinST처럼 로컬 경계(materialize) 전략을 선택하도록 유도.

- **수정 파일**
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`

- **검증**
  - 빌드: `mvn -q -DskipTests compile` 성공.

- **잔여 이슈**
  - 실제 성능 개선 여부는 matrix sweep 재실행으로 확인 필요(특히 `worker=4, kmeans, wan_light/wan_mid`에서 Rule2 위반 해소 여부).

- **잠재 회귀 위험**
  - 루프-heavy 워크로드에서 FED 계획이 과도하게 억제되어 LAN/저지연 환경에서 성능이 떨어질 가능성.
  - 감지 방법:
    - `experiments/scripts/fedplanner_matrix_rules_check.py`로 Rule2/4 재검사
    - `kmeans` 외 워크로드(l2svm/logreg/pca/lm)에서 DP 성능 변화 확인

- **의사결정 근거**
  - candidate-space를 임의로 닫는 가드 추가가 아니라, **비용 모델(오버헤드 스케일링) 수정**으로 DP 선택을 바로잡는 방향(AGENTS 원칙 준수).

