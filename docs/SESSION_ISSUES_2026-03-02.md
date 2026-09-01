# Session Issues — 2026-03-02

This document captures planner/runtime issues encountered and fixed during this session.

---

## Issue 1 — DP(Cost-based) compile-time spike at hop=16 + silent under-enumeration at hop≥32 (child-signature enumeration overflow) (Fixed via DML + overflow fix)

**Status:** Fixed

**환경 / 조건**
- Scenario: federated *compile-only* experiment measuring **FedPlanner time** vs hop count
- Planner: DP (config: `experiments/code/conf/mkl-cost.xml` → `sysds.federated.planner=compile_cost_based`)
- DML generator pattern: repeated scalar accumulation
  - `acc = acc + as.scalar(Pi[1,1])` repeated `hop_target` times

**재현 절차 (대표)**
- Workers up (example ports 18001/18002)
- Hop=16:
  - `RUN_ID=verify_dp16_fixB SYSTEMDS_ROOT=tmp/systemds-local LOG4JPROP=experiments/code/conf/log4j-off.properties experiments/code/systemds_snapshot_exec.sh -f experiments/results/hop_scaling_artifacts/20260302_115630_2949249/rendered/mkl-cost/no_conflict_h16_r3.dml -config experiments/code/conf/mkl-cost.xml -stats 100`
- Hop=32:
  - `RUN_ID=verify_dp32_fixB SYSTEMDS_ROOT=tmp/systemds-local LOG4JPROP=experiments/code/conf/log4j-off.properties experiments/code/systemds_snapshot_exec.sh -f experiments/results/hop_scaling_artifacts/20260302_102620_2662873/rendered/mkl-cost/no_conflict_h32_r1.dml -config experiments/code/conf/mkl-cost.xml -stats 100`

**관측 증상**
- Hop=16에서 **FedPlanner 시간이 비정상적으로 튐** (수 초 단위 spike).
- Hop=32 이상에서는 **오히려 지나치게 빨라지거나**, 결과가 불안정해질 수 있음.

**원인 분석**
- HOP rewrite가 `acc = acc + as.scalar(...)`의 덧셈 체인을 **N-ary plus(NaryOp)** 로 fold함.
  - 결과적으로 특정 hop에서 `m(plus)`가 **k개의 입력(=hop_target)** 을 갖는 high fan-in 노드가 생성됨.
- DP cost enumerator는 “양쪽 출력(LOUT/FOUT) 모두 가능한 자식”들에 대해
  **child-output signature를 전수조사(2^k)** 하도록 구현되어 있었음.
  - Hop=16 → 2^16=65536 signature 열거 → compile spike.
- 추가로 기존 코드는 `int enumerationLimit = 1 << numBothOutInputs` 를 사용하여,
  Java의 int shift 규칙(shift count가 0~31로 마스킹) 때문에:
  - numBothOutInputs=32 → `1<<32 == 1` → **signature를 1개만 평가하는 overflow/버그**가 발생.
  - 이로 인해 hop≥32에서 DP가 **검색 공간을 사실상 1개로 축소**(잘못된 “빠름”)될 수 있음.

**해결 요약**
- (권장 경로) DP 자체의 후보공간을 heuristic으로 닫지 않고, **실험 DML에서 high fan-in Nary plus가 생기지 않도록** 변경:
  - `acc = acc + as.scalar(Pi[1,1])` 누적 체인을 제거하고,
  - 마지막에만 `acc = as.scalar(P{hop_target}[1,1])`로 scalar sink를 만들고 `write(acc, ...)` 수행
  - 이렇게 하면 “2-1-2 체인”(P=L+R 후 P를 두 matmul이 소비) 자체는 유지되면서,
    scalar 누적 덧셈이 Nary plus로 fold되어 생기던 `numBothOutInputs=hop_target` 상황이 사라짐
- DP 내부의 조용한 overflow(under-enum) 방지를 위해, signature enumeration 한계값 타입을 `int`→`long`으로 교체:
  - `final long enumerationLimit = 1L << numBothOutInputs;`
  - `for (long i=0; i<enumerationLimit; i++)` + `(1L<<j)` 마스크 사용

**수정 파일**
- (SystemDS) `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
  - `enumerationLimit`/loop/mask를 `long` 기반으로 변경
- (Experiment) `experiments/scripts/fedplanner_hop_scaling_gen.py`
  - `no_conflict` DML에서 acc 누적 제거 + 마지막 scalar sink로 대체

**검증**
- DP compile-only smoke:
  - `experiments/scripts/fedplanner_hop_scaling_run.py --dml-dir ... --confs mkl-cost --repeats 1 --suppress-planner-noise ...`
  - hop=16: `compile_fedplanner_ms ≈ 549`
  - hop=32: `compile_fedplanner_ms ≈ 624`

**잠재 회귀 위험 / 감지**
- (원복 상태 유지 시) hop≥32 실험 결과가 “빠르게 끝나는 것” 자체가 overflow under-enumeration의 신호일 수 있음.
  - 감지: `-explain hops`로 NaryOp 입력 수 확인 + DP trace에서 signature count 확인.

---

## Issue 2 — PCA/LM에서 DP·MinST가 FED per-op overhead를 과대평가해 FED 경로를 비정상적으로 불리하게 선택 (Fixed, 공통 cost-model 보정)

**Status:** Fixed

**환경 / 조건**
- Workload: `pca`, `lm` (특히 `worker=1`, `wan_light/wan_mid`)
- Planner: `mkl-cost`(DP), `mkl-min-st-cut`(MinST), 비교군 `mkl-heuristic`, `mkl-fout`
- 증상 재현 시 로그에서 DP trace에 `FedInputCheck`와 Oracle FED 허용이 동시에 보이는데도 최종 선택이 CP로 기우는 구간 다수 관측

**재현 절차 (대표)**
- Trace on:
  - `export SYSDS_FED_PLANNER_TRACE=1`
  - `export SYSDS_FED_PLANNER_TRACE_MAX_EDGES=24`
  - `export SYSDS_FED_PLANNER_TRACE_INCLUDE_ORACLE=1`
- PCA 1-worker 4-planner 3-profile 실행:
  - `bash experiments/run_LAN_localproc_matrix.sh --workers-list 1 --planner-confs mkl-min-st-cut,mkl-cost,mkl-heuristic,mkl-fout --workloads pca --net-profiles lan,wan_light,wan_mid --dataset P2P2D --run-id-prefix p1_pca_w1_all4_trace_r1_20260304 --resume 1 --continue-on-failure 1 --run-timeout-sec 3000`

**관측 증상**
- DP/MinST가 Heuristic/FedAll 대비 상대적으로 불리한 실행시간을 반복 관측.
- Trace 상 일부 hop에서 Oracle이 `exec=FED, placement=FOUT, reason=OK`를 반환해도 DP가 CP 후보를 빈번히 선택.

**원인 분석**
- DP FED self-overhead가 `computeNetworkCost(0) * numWorkers` 기반으로 계산되어,
  - 경계 upload/download 비용(이미 latency+payload 포함)과 결합 시 FED per-op latency를 사실상 중복 반영.
- MinST는 별도 helper(`computeFedCoordinationCost`)를 쓰지만, helper 자체가 latency 항을 포함해 동일한 과대평가 방향을 가질 수 있었음.
- 결과적으로 "작은 FED op가 많은 루프"에서 FED를 체계적으로 과벌점.

**해결 요약**
- 공정성 원칙에 맞게 **DP/MinST 공통 helper를 동일 의미로 보정**:
  - `FederatedCostModel.computeFedCoordinationCost(numWorkers)`를 **control-plane only**(RPC/Netty 제어 오버헤드)로 정의.
  - latency/payload는 경계 전송(upload/download) 항에서만 반영되도록 분리.
- DP는 FED overhead 계산을 공통 helper 호출로 통일하고, 외부 `* numWorkers` 중복 곱 제거.
- workload/worker/profile 특례 가드 추가 없음 (ad-hoc 금지 준수).

**수정 파일**
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
  - `computeFedCoordinationCost(...)`를 control-only 모델로 변경
  - 주석에 latency/payload 중복반영 방지 의도 명시
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
  - FED overhead 계산을 `computeFedCoordinationCost(numWorkers)` 사용으로 변경

**검증**
- 정적/컴파일:
  - `lsp_diagnostics` (modified files): 0 error
  - `mvn -DskipTests package`: BUILD SUCCESS
- 대표 실행(quick check):
  - `RUN_ID=coordctrl_postjar_pca_w1_cost_wanmid_20260304_1 ... --conf mkl-cost --alg pca --net-profile wan_mid --workers 1`
  - `RUN_ID=coordctrl_postjar_pca_w1_heur_wanmid_20260304_1 ... --conf mkl-heuristic --alg pca --net-profile wan_mid --workers 1`
  - 결과: DP가 Heuristic 대비 근접(갭 축소), 두 실행 모두 정상 완료.

**잔여 이슈**
- PCA/LM의 run-to-run 분산 자체는 여전히 존재(특히 localproc 환경). 단일 run 판정보다 반복 run의 median 기반 검증 필요.

**잠재 회귀 위험 + 감지**
- `SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS`가 0일 때 per-op FED overhead가 너무 작아질 수 있음.
- 감지: WAN profile에서 DP/MinST가 과도하게 FED 편향되는지 `DP-Candidate` trace와 rule check로 점검.

**의사결정 근거**
- planner gate/후보공간 축소가 아니라 **공통 cost-model 수식 보정**으로 해결 (DP/MinST 공정성 유지).
