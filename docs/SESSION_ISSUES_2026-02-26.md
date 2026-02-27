# Session issues — 2026-02-26

## 1) DP planner: loop-weighted conflict decisions ignored iter1 clone roots → kmeans++ loop triggered repeated refed/PUT

- **상태**: 해결
- **환경/조건**
  - Planner: DP (`mkl-cost`)
  - Workload: builtin `kmeans.dml` (P2P2D)
  - Network: LAN
  - Workers: 1
- **재현 절차**
  - Run (docker): `experiments/run_LAN_docker.sh --workers 1 --conf mkl-cost --alg kmeans --net-profile lan --dataset P2P2D --systemds-root /home/mchoi/exdra_run/systemds --skip-docker-build`
  - Bad log (before fix): `experiments/results/fed1/mkl-cost/kmeans_dataset-P2P2D_coordinator_mkl-cost_20260226_111532_2629605_lan.log`
- **관측 증상**
  - 루프(50회) 내부에서 큰 중간결과가 `CP/FOUT`로 떨어지며 `fed_refed`가 반복 실행되어 PUT 폭증.
  - Example metric: `Fed Put Bytes (Mat/Frame): 2603072568/0 Bytes` (≈2.6GB).
- **원인 분석**
  - DP는 루프를 `iter0(×1)` + `iter1(×(N-1))`로 언롤링하여 hop clone을 만들고,
    clone별 `multiplicity`로 forwarding cost를 가중한다.
  - 그러나 DP rewrite 단계의 `computeOutputDecisions`/conflict 수집(BFS)이 dummy root에서
    도달 가능한 plan subtree만 순회하여, **iter1 clone root(큰 multiplicity)**의 parent-usage를
    conflict 비용 비교에 포함하지 못했다.
  - 그 결과, placement 결정이 `iter0(×1)` 기준으로 내려지고(=반복 가중치가 1회로만 반영),
    해당 결정이 original hop id 기준으로 모든 clone에 적용되면서 루프에서 반복 refed/PUT가 발생.
- **해결 요약**
  - loop-unrolled iter1 roots를 memoTable에 등록하고, DP rewrite 단계의 conflict 수집/rewriting에
    이 roots를 포함시켜 **반복 가중치가 placement conflict 비용 비교에 반영**되도록 수정.
  - 핵심: “runtime 지원 조합을 닫는 가드”가 아니라, **loop-aware cost 기반**으로 LOUT/FOUT를 선택.
- **수정 파일**
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpMemoTable.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
- **검증**
  - Build: `mvn -DskipTests package`
  - Run log (after fix): `experiments/results/fed1/mkl-cost/kmeans_dataset-P2P2D_coordinator_mkl-cost_20260226_144633_3042534_lan.log`
  - Metric improvement:
    - `Fed Put Bytes (Mat/Frame): 83064968/0 Bytes` (≈83MB, 반복 PUT 제거)
    - wall-clock: `real 78.87` (이전 ~105s 대비 감소; 추가 최적화 여지 있음)
- **잔여 이슈**
  - kmeans++ 루프 내부에서 일부 큰 연산이 여전히 CP로 선택되는 경향이 있어,
    DP vs MinST 성능 격차 원인 분석/추가 개선이 필요할 수 있음.
- **잠재 회귀 위험 / 감지 방법**
  - Risk: 추가 root BFS로 conflict map이 커져 결정이 바뀔 수 있음.
  - Detect: `kmeans`/`lm` 등 loop-heavy workload에서 `Fed Put Bytes` 및 `fed_refed`(inst stats)
    급증 여부를 regression check로 추가.

