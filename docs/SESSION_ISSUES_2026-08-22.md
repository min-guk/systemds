# Session Issues — 2026-08-22

## 1. First-feasible policy selector chooses loop-amplified BROADCAST layouts in LOGREG

- **상태**: 해결 (`a738893dc7`, targeted tests + 7-workload compile + LOGREG authenticated runtime 검증 완료)
- **환경/조건**: regression commit `c792138e7b`, fixed commit `a738893dc7`, authenticated Docker WAN-light, 2 workers, LOGREG `multiLogReg(maxi=30, maxii=5)`, FedAll/Heuristic exact-policy selector와 first-feasible selector 비교.
- **재현 절차**:
  ```bash
  # 기존 완료 artifact
  cat /home/mchoi/g014-policy-first-feasible-20260822-v1/analysis/final/runtime_summary.csv
  cat /home/mchoi/g014-policy-first-feasible-20260822-v1/analysis/final/plan_differences.json
  ```
- **관측 증상**: first-feasible가 HOP 614를 `FED/LOUT/BROADCAST`, HOP 785를 derived `FED/FOUT/BROADCAST`로 선택한다. exact-policy는 각각 ROW를 선택한다. 두 static relocation site가 outer loop에서 각각 30회 실행되어 FedAll/Heuristic 모두 `fed_refed`가 60회 증가하며 runtime이 각각 7.53%, 11.54% 증가했다. HOP 785는 추가 derived FOUT도 30회 실행한다.
- **원인 분석**: selector의 local `MovementHint`는 incident action identity의 정적 개수만 비교하며 occurrence execution frequency를 보지 않는다. incomplete local hint가 alternatives를 구분하지 못하면 canonical `PlacementState` 문자열에서 `BROADCAST`가 `ROW`보다 먼저 정렬된다. Exact-policy가 ROW를 선택한 이유는 frequency가 아니라 complete assignment의 physical movement 수를 비교하기 때문이다.
- **해결 계획**:
  1. 공통 `PlacementAnalysis`에 immutable occurrence execution-frequency fact를 생성한다.
  2. first-feasible가 relocation 및 derived-FOUT action을 decision group별로 pre-index한다.
  3. 동일 policy rank에서 incident movement와 source preparation을 execution frequency로 가중하고, 여전히 동률이면 durable anchor affinity와 canonical order를 사용한다. layout 이름만으로 ROW/COL을 BROADCAST보다 우선하지 않는다.
  4. global enumeration은 추가하지 않고 first-feasible termination과 legality/candidate-reachability semantics를 유지한다.
  5. targeted tests, 7-workload compile-only campaign, LOGREG runtime campaign으로 검증한다.
- **수정 파일**:
  - `OccurrenceExecutionFrequencyFacts.java`: compiler-owned occurrence path, loop/branch/function context에서 공통 execution/forwarding frequency fact를 한 번 생성한다. local selector용 보수적 API와 Exact용 fail-closed API를 분리했다.
  - `PlacementAnalysis.java`: selector 실행 전에 위 immutable fact를 소유·공유한다.
  - `ExactPhysicalCostModel.java`: 자체 control-flow profile 재구성을 삭제하고 공통 fact의 strict API를 소비한다.
  - `PolicyFirstFeasiblePlacementSelector.java`: relocation/derived-FOUT action을 equality group별로 사전 인덱싱하고, 동일 policy rank 후보를 frequency-weighted incident movement, direct-source preparation, anchor affinity 순으로 정렬한다. legal domain과 first-feasible 종료 조건은 변경하지 않는다.
  - `PolicyFirstFeasiblePlacementSelectorTest.java`: hot-loop relocation과 derived BROADCAST preparation 회귀를 고정한다.
- **검증**:
  - `PolicyFirstFeasiblePlacementSelectorTest` 8개 통과.
  - `CampaignBG014ExactKMeansWanRepeatedUploadRedTest`, `ExactPhysicalModelCertificateTest` 통과.
  - `mvn -q -DskipTests package` 및 `git diff --check` 통과.
  - `SharedPlannerFunctionPlanPropagationRedTest`의 host 실행은 `localhost:8001/8002` worker 부재로 privacy fail-closed가 발생했다. privacy를 우회하지 않고 Docker campaign에서 검증한다.
- **Docker 검증 결과**:
  - immutable stage `84c5912...f08dd`, JAR `3935dba8...4316`, SystemDS `a738893d...b609`.
  - 7 workloads × 4 planners × 11 repetitions: 308/308 observations, 7/7 blocks, failure 0. logging/trace/runtime execution은 compile 측정에서 모두 비활성화했다.
  - first-feasible selector geometric-mean speedup: FedAll 2.604×, Heuristic 2.202×. end-to-end compile speedup: 1.223×, 1.169×.
  - LOGREG exact/first는 두 family 모두 492개 decision 전부 일치했다. FedAll은 `fed_refed=0`, relocation 0, derived FOUT 7; Heuristic은 `fed_refed=30`, relocation 1, derived FOUT 0으로 exact와 동일했다.
  - runtime: FedAll exact 48.871s / first 49.144s, Heuristic exact 40.285s / first 39.855s. oracle pass, fallback 없음, family별 runtime instruction fingerprint 동일.
  - 동일 seed 반복은 immutable resource evidence 덮어쓰기 금지로 거부되고, 다른 seed는 authenticated CP reference seed binding으로 거부되었다. 기존 evidence를 삭제하지 않았으므로 runtime 시간은 n=1이며 plan/instruction/movement identity가 주 증거다.
- **잔여 이슈**: data-dependent while frequency는 compile-time estimate이므로 runtime actual count와 다를 수 있다. Exact-policy와 cost-based Exact를 용어상 구분해야 한다. one-pass runtime contract 때문에 동일 authenticated reference에서 분산 추정용 반복은 확보하지 못했다.
- **잠재 회귀 위험**: 무조건적 `ROW/COL > BROADCAST`는 scalar/vector 및 mixed ROW/COL workload를 악화시킬 수 있다. native BROADCAST와 mixed-axis derived FOUT regression tests로 감지한다.
- **의사결정 근거**: legal domain을 닫지 않고 동일 policy class 내부의 cost-based local ordering만 개선한다. runtime-supported 후보를 제거하지 않으며 fallback을 추가하지 않는다.
