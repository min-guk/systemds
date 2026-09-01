# Session Issues — 2026-02-28

This document captures planner/runtime issues encountered and fixed during this session.

---

## Issue 1 — DP executes `colSums(P)` as `fed_uack+` in kmeans WAN_mid (worker=3) (Fixed)

**Status:** Fixed

**Environment / 조건**
- Workload: `scripts/builtin/kmeans.dml` (P2P2D)
- Planner: DP cost-based (`mkl-cost`)
- Profile: `wan_mid`
- Workers: 3

**재현 로그**
- DP (buggy): `experiments/results/fed3/mkl-cost/kmeans_dataset-P2P2D_coordinator_mkl-cost_20260227_230906_3892770_wan_mid.log`
- MinST: `experiments/results/fed3/mkl-min-st-cut/kmeans_dataset-P2P2D_coordinator_mkl-min-st-cut_20260227_231351_3907605_wan_mid.log`

**관측 증상**
- DP heavy hitters에서 `fed_uack+`가 54회 실행되며 큰 오버헤드:
  - `fed_uack+ 17.770s (54)`
- MinST는 동일 연산이 로컬 `uack+`로 실행:
  - `uack+ ~0.093s (54)`
- inst_stats로 hop 매핑 확인:
  - `kmeans.dml:153 (P_denom = colSums(P))`가 DP에서 `ext_opcode=fed_uack+`로 실행됨.

**원인 분석**
- `P_denom = colSums(P)`는 로컬(CP) 소비가 더 유리하지만,
  DP rewire 단계에서 output decision(자식 hop이 FOUT로 결정됨)과 edge의 LOUT 기대가 충돌할 때
  “엄격 호환성 체크”가 LOUT 소비를 비호환 처리하여 FED 실행으로 강제됨.
- 특히, child가 **CP/FOUT**로 실행되면 로컬 materialization이 존재함에도 이를 반영하지 못함.

**해결 요약**
- DP rewire의 child-output 호환성 체크를 완화:
  - child가 “결정=FOUT”이더라도, child의 FOUT 플랜이 **CP 실행**인 경우에는
    로컬 소비(LOUT edge)를 호환으로 간주 (로컬 materialization이 남는다는 런타임 특성 반영).

**수정 파일**
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `isCompatibleWithChildDecisions(...)` 완화 로직 추가

**검증**
- DP 재실행 후 `fed_uack+` 제거 및 `uack+`로 변경, Get 카운트도 MinST와 동일 수준으로 수렴:
  - DP fixed log: `experiments/results/fed3/mkl-cost/kmeans_dataset-P2P2D_coordinator_mkl-cost_dp_uackfix_test_20260228_005858_wan_mid.log`

**잠재 회귀 위험 / 감지**
- CP/FOUT child의 로컬 materialization 가정이 깨지는 경우(런타임 변경 등) LOUT 소비가 잘못될 수 있음.
- 감지: WAN_*에서 `fed_uack+` 같은 “불필요한 FED aggregate” heavy hitter 재등장 여부.

---

## Issue 2 — DP가 PCA worker=1에서 네트워크 프로필과 무관하게 과도하게 느림 (Fixed)

**Status:** Fixed

**환경 / 조건**
- Workload: `scripts/builtin/pca.dml` (P2P2D)
- Planner: DP cost-based (`mkl-cost`) vs MinST (`mkl-min-st-cut`)
- Workers: 1
- Profiles: `lan`, `wan_light`, `wan_mid`
- Input metadata: `experiments/data/P2P2D_features.data.mtd` contains `privacy: private-aggregate`

**관측 증상 (Before)**
- DP(worker=1) PCA가 ~330–350s 수준으로 매우 느림.
  - heavy hitters: `fed_-`, `fed_/`가 각각 ~110s 급
  - Fed I/O: `Fed Put Bytes`가 ~1.6GB로 폭증 (큰 행렬 GET↔PUT ping‑pong)
- MinST(worker=1)는 ~110–120s 수준.

**원인 분석**
이 이슈는 **FULL(FType.FULL) 단일 파티션(=worker=1) 케이스를 oracle/rules/runtime가 일관되게 처리하지 못한 것**이 핵심.

1) **OracleFacade의 ShapeHint가 FULL(single partition) 여부를 전달하지 못함**
- `OracleFacade.buildShapeHint(...)`가 `ShapeHint.fullSinglePartition`을 항상 `Optional.empty()`로 둬서,
  FULL이 “단일 range”인지 “다중 range”인지 구분 불가.
- 결과: FULL이 단일 파티션인 경우에도 일부 규칙(TSMM 등)에서 FULL 경로가 금지되거나, 잘못된 fallback 발생.

2) **InitFED / planner-side ftype 도출이 replicated FULL을 FULL로 오인**
- `InitFEDInstruction`이 `rowPartitioned && colPartitioned`이면 무조건 `FType.FULL`을 설정하여,
  range가 여러 개인 “복제(=broadcast)처럼 보이는” 케이스도 FULL로 분류됨.
- `FederatedPlannerUtils.deriveFedInitFType`도 동일한 오분류가 있어 planner와 runtime의 의미가 어긋남.

3) **Runtime: FULL(single partition) + FULL(single partition) 이진 연산에서 불필요한 대형 브로드캐스트 발생**
- `BinaryMatrixMatrixFEDInstruction`은 입력2가 FED(비‑BROADCAST)인 경우 “alignment 불일치”로 판단되면
  `broadcastSliced(mo1)`를 통해 driver에서 큰 행렬을 GET 후 다시 PUT하는 최악 경로로 떨어질 수 있음.
- 특히 worker=1에서 refed/broadcast된 작은 벡터가 FULL로 표시되면,
  `broadcastSliced`가 FULL map에서는 슬라이스가 아니라 **전체 broadcast**로 동작 → 대형 ping‑pong 유발.

**해결 요약**
DP enumerator 측 후보공간 “완화/우회”가 아니라, **oracle/rules + runtime의 FULL(single partition) 의미를 정정**.

1) **OracleFacade: ShapeHint.fullSinglePartition 채우기**
- 입력 FType 중 `FType.FULL`이 있을 때, 가능한 compile-time 정보로 range 개수를 추론하여:
  - 단일 range면 `fullSinglePartition=true`
  - 다중 range면 `fullSinglePartition=false`
- 추론 소스:
  - `DataOp(FEDERATED)`의 `FED_RANGES` 파라미터(가능할 때)
  - 변수명 기반 fedinit signature/anchor key(가능할 때)
  - 최후 fallback: `maxFedInitWorkers==1`이면 단일 파티션으로 간주

2) **Rulesets.TsmmRule: FULL(single partition) 허용**
- `FType.FULL`은 `fullSinglePartition=true`일 때만 FED 실행 허용 (FED + LOUT).
- 기존 테스트 기대와의 정합을 위해, 필요 시 “강제 FOUT= BROADCAST” 경로(note 포함)도 복원.

3) **InitFEDInstruction / FederatedPlannerUtils.deriveFedInitFType: replicated FULL → BROADCAST**
- `rowPartitioned && colPartitioned`이더라도 range/map size가 2개 이상이면 `FType.BROADCAST`로 설정.
- planner-side `deriveFedInitFType`도 동일하게 정렬하여 oracle/runtime 불일치 제거.

4) **Runtime: FULL(single partition) 안전 처리**
- `FEDLocalMaterializeUtil.normalizeReplicatedMapType`:
  - FULL broadcast로 materialize된 값은 worker 수와 무관하게 `BROADCAST`로 정규화
    (worker=1에서도 FULL로 남기지 않음)
- `BinaryMatrixMatrixFEDInstruction`:
  - `FULL && size==1` 양쪽 입력이 동일 worker pool이면, alignment 체크/방송 없이 **직접 해당 worker에서 실행**
    (driver GET/PUT 방지)

5) **Rulesets.BinaryMMRule(mm) : FULL(single worker) × local mmult 허용**
- PCA에서 `XReduced = X %*% Components`는 `X`가 worker=1에서 `FType.FULL`, `Components`가 local인 조합이 자주 등장.
- 기존 `BinaryMMRule`은 `inputs=[FULL, null]`을 `NOT_FEDERATED_INPUTS`로 처리하여 FED 실행 후보를 제거했고,
  DP가 앞단 elementwise를 FED로 선택하면 mmult에서 CP 강제 + repr change가 섞이면서 혼합 플랜이 악화될 수 있었음.
- 해결: `FULL x local` 또는 `local x FULL`이면 `FED + LOUT`(ReasonCode.OK) 허용.
  - 근거: runtime `AggregateBinaryFEDInstruction`는 local 쪽을 단일 federated worker로 broadcast하여 실행 가능.

6) **DP-side workaround(“worker=1 allowCP_LOUT gate”) 제거**
- `FederatedPlannerDpCostEnumerator`에서 worker=1을 이유로 CP/LOUT을 강제/완화하는 gate는 두지 않음.
  (현재 코드베이스에 해당 gate가 존재하지 않음을 확인)

**수정 파일**
- `src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java`
- `src/main/java/org/apache/sysds/runtime/instructions/fed/InitFEDInstruction.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerUtils.java`
- `src/main/java/org/apache/sysds/runtime/instructions/fed/FEDLocalMaterializeUtil.java`
- `src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixMatrixFEDInstruction.java`
- Tests:
  - `src/test/java/org/apache/sysds/test/functions/fedplanner/rules/TsmmRuleTest.java`
  - `src/test/java/org/apache/sysds/test/functions/fedplanner/rules/BinaryMMTsmmRuleTest.java`
  - `src/test/java/org/apache/sysds/test/functions/fedplanner/rules/RulesetsReorgTest.java`

**검증 (After)**

Before (DP bad, worker=1, lan):
- `experiments/results/fed1/mkl-cost/pca_dataset-P2P2D_dams-so002_mkl-cost_oraclefix_pca_w1_cost_lan.log`
  - `Total execution time: 341.049 sec`
  - `Fed Put Bytes: 1680034208 Bytes`
  - heavy hitters: `fed_- 113s`, `fed_/ 115s`

After (final, oraclefix3):

- DP (`mkl-cost`)
  - lan: `experiments/results/fed1/mkl-cost/pca_dataset-P2P2D_dams-so002_mkl-cost_oraclefix3_pca_w1_cost_lan.log`
    - `Total execution time: 111.759 sec`
    - `Federated I/O (Read, Put, Get): 2/2/5`
    - `Fed Put Bytes: 33904 Bytes` (대형 ping‑pong 제거)
  - wan_light: `experiments/results/fed1/mkl-cost/pca_dataset-P2P2D_dams-so002_mkl-cost_oraclefix3_pca_w1_cost_wan_light.log`
    - `Total execution time: 117.410 sec`, `Fed Put Bytes: 33904`
  - wan_mid: `experiments/results/fed1/mkl-cost/pca_dataset-P2P2D_dams-so002_mkl-cost_oraclefix3_pca_w1_cost_wan_mid.log`
    - `Total execution time: 118.446 sec`, `Fed Put Bytes: 33904`

- MinST (`mkl-min-st-cut`)
  - lan: `experiments/results/fed1/mkl-min-st-cut/pca_dataset-P2P2D_dams-so002_mkl-min-st-cut_oraclefix3_pca_w1_minst_lan.log`
    - `Total execution time: 113.161 sec`
    - `Federated I/O (Read, Put, Get): 2/1/2`
    - `Fed Put Bytes: 16952 Bytes`
  - wan_light: `experiments/results/fed1/mkl-min-st-cut/pca_dataset-P2P2D_dams-so002_mkl-min-st-cut_oraclefix3_pca_w1_minst_wan_light.log`
    - `Total execution time: 113.540 sec`, `Fed Put Bytes: 16952`
  - wan_mid: `experiments/results/fed1/mkl-min-st-cut/pca_dataset-P2P2D_dams-so002_mkl-min-st-cut_oraclefix3_pca_w1_minst_wan_mid.log`
    - `Total execution time: 112.611 sec`, `Federated I/O: 2/0/1`

결론: worker=1에서 DP는 더 이상 FED↔CP ping‑pong을 선택/실행하지 않으며,
Put/Get payload가 MinST와 동급 수준으로 수렴하고 총 실행 시간도 유사해짐.

추가 검증 (docker mini-matrix):
- Run: `pca_w1_fullmmfix_abs_20260228_0531` (workers=1, planners=mkl-cost,mkl-min-st-cut, profiles=lan/wan_light/wan_mid)
  - DP(`mkl-cost`) `Total elapsed time`: lan `113.843s`, wan_light `120.146s`, wan_mid `134.036s`
  - MinST(`mkl-min-st-cut`) `Total elapsed time`: lan `115.137s`, wan_light `120.086s`, wan_mid `127.211s`
  - matrix rule check: `violations=0, warnings=0` (6/6 success)

**잠재 회귀 위험 / 감지**
- FULL(single partition) 케이스에서 FED binary가 더 이상 driver broadcastSliced로 떨어지지 않는지 확인:
  - 감지: worker=1에서 `Fed Put Bytes`가 수백 MB~GB로 다시 튀는지,
    heavy hitters에 `fed_-`, `fed_/`가 수십~수백 초로 재등장하는지 확인.
