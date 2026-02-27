# SESSION ISSUES - 2026-02-23

## Cluster Table (Full no-lm sweep: `ralph_full_no_lm_20260223_073650`)

- **SoT (matrix)**:
  - state: `experiments/results/matrix_state/ralph_full_no_lm_20260223_073650.tsv`
  - heartbeat: `experiments/results/matrix_state/ralph_full_no_lm_20260223_073650.heartbeat`
  - result: `success=162, failed=30 (192 total)`
- **Signature counts (failed 30 기준)**:
  - `fed_refed expects local but found federated input` (**8**)
  - `fed_refed requires a federated anchor` (**6**)
  - `LOG_MISSING` (**14**) — coordinator log/inst_stats 부재로 runner/수집/infra 이슈 가능성 큼
  - `TIMEOUT` (**2**) — heuristic l2svm `wan_mid` (w3/w4)

| Cluster | Count | Representative log | Cells (w, planner, workload, profile) |
|---|---:|---|---|
| `REFED_LOCAL_EXPECTED` | 8 | `experiments/results/fed2/mkl-min-st-cut/kmeans_dataset-P2P2D_coordinator_mkl-min-st-cut_ralph_full_no_lm_20260223_073650_20260223_073650_1094753_w2_mkl-min-st-cut_kmeans_lan_lan.log` | w2/w4 × (`mkl-min-st-cut`,kmeans,lan) + w2/w4 × (`mkl-fout`,l2svm,lan/wan_light/wan_mid) |
| `REFED_NEEDS_ANCHOR` | 6 | `experiments/results/fed1/mkl-fout/kmeans_dataset-P2P2D_coordinator_mkl-fout_ralph_full_no_lm_20260223_073650_20260223_073650_1094753_w1_mkl-fout_kmeans_lan_lan.log` | w1 × (`mkl-fout`,kmeans,lan/wan_light/wan_mid) + w1 × (`mkl-heuristic`,kmeans,lan/wan_light/wan_mid) |
| `LOG_MISSING` | 14 | (missing) | w1 (`mkl-min-st-cut`,logreg,*) + w1 (`mkl-cost`,kmeans,lan) + w2 (`mkl-fout`,kmeans,*) + w2 (`mkl-heuristic`,kmeans,*) + w3 (`mkl-fout`,l2svm,*) + w3 (`mkl-min-st-cut`,kmeans,lan) |
| `TIMEOUT` | 2 | `experiments/results/fed3/mkl-heuristic/l2svm_dataset-P2P2D_coordinator_mkl-heuristic_ralph_full_no_lm_20260223_073650_20260223_073650_1094753_w3_mkl-heuristic_l2svm_wan_mid_wan_mid.log` | w3/w4 × (`mkl-heuristic`,l2svm,wan_mid) |

## 이슈 1: worker=1 MinST(logreg)에서 WAN 구간 FED 대안 과선택으로 `wan_mid` 대형 지연
- **상태**: 해결(타깃 검증 완료)

- **환경/조건**:
  - planner: `mkl-min-st-cut`
  - workload/dataset: `logreg / P2P2D`
  - workers: `w1`
  - profiles: `lan, wan_light, wan_mid`
  - systemds root: `tmp/systemds-local`
  - 실행 스크립트: `experiments/run_LAN_docker.sh`

- **재현 절차**:
  1. 빌드
     - `cd tmp/systemds-local && mvn -q -DskipTests package`
  2. 단일 셀 실행
     - `RUN_ID=ralph_cont_w1_minst_logreg_lan_20260223_065649 bash ./experiments/run_LAN_docker.sh --workers 1 --conf mkl-min-st-cut --salg logreg --net-profile lan --net-target workers --continue-on-failure 0 --keep-containers --skip-docker-build --skip-net-check --systemds-root /home/mchoi/reproducibility/sigmod2021-exdra-p523/tmp/systemds-local`
     - `RUN_ID=ralph_cont_w1_minst_logreg_wanlight_20260223_065953 ... --net-profile wan_light ...`
     - `RUN_ID=ralph_cont_w1_minst_logreg_wanmid_20260223_065755 ... --net-profile wan_mid ...`

- **관측 증상**:
  - 기존 baseline(`run_id_filter=p2p2d_uc_20260213_222812_p2p2d_uc_222812`)에서
    - `w1 logreg minst lan=13.714s`
    - `w1 logreg minst wan_light=76.799s`
    - `w1 logreg minst wan_mid=284.628s` (비정상적으로 큼)
  - `wan_mid`에서 FED `ba+* / fed_refed`가 대량 발생.

- **원인 분석**:
  - `shouldEnableFederatedAlternativeFallback(...)`가 내부적으로
    `computeLocalToFedForwardingPenalty(...)`만 사용.
  - 이 penalty는 `fanout<=1`이면 0을 반환하므로(`w1`), WAN latency가 커도 fallback이 허용되어
    worker=1 AggBinary에서 FED 대안이 다시 살아남는 경로가 발생.

- **해결 요약**:
  - fallback gate에 **latency-only network term**을 추가해 `w1`에서도 WAN 민감도를 반영.
  - 기준 상수(`FED_ALT_FALLBACK_MAX_CTRL_PENALTY_MS`)를 30ms로 상향해
    LAN은 허용, 고지연 WAN은 차단되도록 조정.
  - 구현:
    - `forwardingPenalty + FederatedCostModel.computeNetworkCost(0.0)`를 total penalty로 사용.

- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`

- **검증**:
  - 빌드: `mvn -q -DskipTests package` 성공
  - 런타임:
    - `..._lan.log`: `Total execution time: 13.676 sec`
    - `..._wan_light.log`: `Total execution time: 78.363 sec`
    - `..._wan_mid.log`: `Total execution time: 83.366 sec`
  - inst_stats:
    - lan: `FED°fed_refed=335`, `FED°ba+*=335`
    - wan_light: `FED°fed_refed=0`, `FED°ba+*=0`
    - wan_mid: `FED°fed_refed=0`, `FED°ba+*=0`
  - 집중 matrix 재검증(6셀):
    - prefix: `ralph_cont_w1_minst_logreg_l2svm_matrix_20260223_071215`
    - state: `experiments/results/matrix_state/ralph_cont_w1_minst_logreg_l2svm_matrix_20260223_071215.tsv`
    - latest-status: `success=6/6, failed=0`
    - logreg: `lan=14.219s`, `wan_light=76.884s`, `wan_mid=84.507s`
    - l2svm: `lan=62.141s`, `wan_light=66.185s`, `wan_mid=69.283s`
  - 교차 영향 확인(w1/l2svm/minst):
    - `lan=65.457s`, `wan_light=65.794s`, `wan_mid=69.056s`
    - 세 프로파일 모두 runtime 예외 없이 완료, `FED°fed_refed=0`, `FED°ba+*=0`

- **잔여 이슈**:
  - 본 수정은 `w1/logreg/minst` 타깃 검증 중심.
  - 전체 Rule2/Rule3/Rule4 strict 0 달성을 위해서는 다른 workload/planner 조합 추가 조정 필요.

- **잠재 회귀 위험 + 감지**:
  - 위험: wan_light에서 FED 경로를 과도하게 억제하면 일부 케이스에서 절대시간이 소폭 증가할 수 있음.
  - 감지: `fedplanner_matrix_rules_check.py`와 `inst_stats`의 `FED°fed_refed`, `FED°ba+*`, `Total execution time` 동시 모니터링.

- **의사결정 근거(oracle/runtime/planner)**:
  - planner의 fallback gate 비용 판단만 수정했고, oracle 제약/런타임 fallback 완화는 하지 않았다.

---

## 이슈 2: `fed_refed expects a local input but found federated input` (kmeans MinST / l2svm FOUT)
- **상태**: 해결(타깃 Tier0 프로브 통과; Tier2 failed-only resume로 전체 반영 확인 예정)

- **환경/조건**:
  - sweep: `ralph_full_no_lm_20260223_073650` (no-lm 192 cells)
  - signature: `fed_refed expects a local input but found federated input`

- **재현(대표)**:
  - kmeans MinST (w2, lan):
    - log: `experiments/results/fed2/mkl-min-st-cut/kmeans_dataset-P2P2D_coordinator_mkl-min-st-cut_ralph_full_no_lm_20260223_073650_20260223_073650_1094753_w2_mkl-min-st-cut_kmeans_lan_lan.log`
    - error: `fed_refed expects a local input but found federated input: samples_vs_runs_map`
  - l2svm FOUT (w2, lan):
    - log: `experiments/results/fed2/mkl-fout/l2svm_dataset-P2P2D_coordinator_mkl-fout_ralph_full_no_lm_20260223_073650_20260223_073650_1094753_w2_mkl-fout_l2svm_lan_lan.log`
    - error: `fed_refed expects a local input but found federated input: Xd`

- **관측 증상**:
  - runtime에서 `FEDRefedInstruction`이 입력이 이미 federated인 상태에서 실행되어 즉시 예외.
  - 대표 스택:
    - `FEDRefedInstruction.processInstruction(...):94` (`in.isFederated()` check)

- **원인 분석(확정)**:
  - fedplanner/compile 단계에서 삽입된 `fed_refed`가 **“local→federated 변환 전용”**이라는 런타임 가정과 달리,
    runtime(재컴파일/대안 선택)에서 해당 입력이 **이미 federated(FED/FOUT, CP/FOUT materialize 등)** 로 계획/실행되는 경로가 존재.
  - 결과적으로 동일 SB 내에서 “(이미 federated인 var) → fed_refed”가 남아 런타임에서 즉시 abort.

- **해결 요약(원칙 준수)**:
  - 런타임에서 **“이미 federated + refed 대상 레이아웃이 이미 만족”** 하는 경우에 한해 `fed_refed`를 **no-op(reuse)** 로 처리.
    - (A) **dims가 anchor와 동일**(ROW/COL anchor)일 때: input map이 anchor map과 **정확히 정렬(aligned)** 된 경우만 허용.
    - (B) **dims가 anchor와 다를 때**: `fed_refed`가 local input에 대해 만들 “균등 분할 materialize 레이아웃”과 input map이 **동일**한 경우만 허용.
  - 위 조건을 만족하지 않으면 기존처럼 예외를 유지하여 “필요한 refederation(shuffle)”을 조용히 생략하지 않도록 방지.
  - 이는 `fed_fout`이 이미 federated input을 no-op으로 허용하는 정책과 동일한 방향(안전한 재사용)이며,
    **성능/정합성 fallback을 추가하지 않는다**.

- **수정 파일**:
  - `src/main/java/org/apache/sysds/runtime/instructions/fed/FEDRefedInstruction.java`

- **검증(Tier0)**:
  - kmeans MinST (w2, lan) — 기존 실패(sig=`samples_vs_runs_map`)가 사라짐
    - run: `RUN_ID=20260223_224741_2593024_lan`
    - log: `experiments/results/fed2/mkl-min-st-cut/kmeans_dataset-P2P2D_coordinator_mkl-min-st-cut_20260223_224741_2593024_lan.log`
    - 확인: `rg -n "fed_refed expects" <log>` → no match
  - l2svm FOUT (w2, lan) — 기존 실패(sig=`Xd`)가 사라짐
    - run: `RUN_ID=20260223_225019_2602598_lan`
    - log: `experiments/results/fed2/mkl-fout/l2svm_dataset-P2P2D_coordinator_mkl-fout_20260223_225019_2602598_lan.log`
    - 확인: `rg -n "fed_refed expects" <log>` → no match

- **다음 검증 계획**:
  - Tier0 잔여(coverage): w4 및 wan_mid 조합(기존 baseline 8셀 전체)을 동일 커맨드로 재확인
  - Tier2: baseline failed-only resume (`ralph_full_no_lm_20260223_073650.tsv` 기반)로 cluster 감소 확인

---

## 이슈 3: `fed_refed requires a federated anchor` (w1 kmeans, heuristic/fout)
- **상태**: 해결(타깃 Tier0 프로브 통과; Tier2 failed-only resume로 전체 반영 확인 예정)

- **환경/조건**:
  - sweep: `ralph_full_no_lm_20260223_073650`
  - signature: `fed_refed requires a federated anchor`

- **재현(대표)**:
  - log: `experiments/results/fed1/mkl-fout/kmeans_dataset-P2P2D_coordinator_mkl-fout_ralph_full_no_lm_20260223_073650_20260223_073650_1094753_w1_mkl-fout_kmeans_lan_lan.log`
  - error: `fed_refed requires a federated anchor: _mVar332`

- **관측 증상**:
  - runtime에서 `FEDRefedInstruction`이 anchor operand를 matrix variable로 받았지만,
    해당 anchor가 `isFederated()==false`여서 즉시 예외.

- **원인 분석(확정)**:
  - 특정 경로에서 `fed_refed`의 anchor operand가 `_mVar332` 같은 **local intermediate**로 설정되며,
    해당 변수는 런타임에서 `isFederated()==false`이고 `FederationUtils`에도 anchor map/key를 찾을 수 없어
    `FEDRefedInstruction`이 즉시 예외를 던짐.
  - w1에서는 실제 federated worker pool이 **단일**이므로, anchor가 굳이 그 intermediate일 필요는 없고
    동일 worker pool의 다른 federated 입력(예: `X`)의 map을 anchor로 써도 충분.

- **해결 요약(원칙 준수)**:
  - `FEDRefedInstruction`에서 anchor operand가 (1) federated가 아니고 (2) anchor map/key로도 해석 불가한 경우,
    `ExecutionContext`에 존재하는 federated 변수들을 스캔해 **“유일한(worker pool이 하나뿐인)”** federated map을 찾고
    이를 anchorMap으로 사용한다.
  - worker pool이 2개 이상이면(ambiguous) 기존처럼 예외를 유지하여 잘못된 fallback을 방지한다.

- **수정 파일**:
  - `src/main/java/org/apache/sysds/runtime/instructions/fed/FEDRefedInstruction.java`

- **검증(Tier0)**:
  - w1 mkl-fout kmeans lan
    - run: `RUN_ID=20260223_231542_2653836_lan`
    - log: `experiments/results/fed1/mkl-fout/kmeans_dataset-P2P2D_coordinator_mkl-fout_20260223_231542_2653836_lan.log`
    - 확인: `rg -n "fed_refed requires a federated anchor" <log>` → no match
  - w1 mkl-heuristic kmeans lan
    - run: `RUN_ID=20260223_231749_2659992_lan`
    - log: `experiments/results/fed1/mkl-heuristic/kmeans_dataset-P2P2D_coordinator_mkl-heuristic_20260223_231749_2659992_lan.log`
    - 확인: `rg -n "fed_refed requires a federated anchor" <log>` → no match

- **다음 검증 계획**:
  - Tier2 failed-only resume로 baseline 6셀(프로파일 포함) 전부 통과 확인

---

## 이슈 4: `LOG_MISSING` / state=failed(rc=1)인데 coordinator log가 없음
- **상태**: 진행중

- **관측**:
  - 아래 셀들은 state TSV에 `failed rc=1`로 기록됐지만, `experiments/results/**/coordinator_*<RUN_ID>*` 로그가 없음:
    - w1 `mkl-min-st-cut logreg` (lan/wan_light/wan_mid)
    - w2 `mkl-fout kmeans` (lan/wan_light/wan_mid)
    - w2 `mkl-heuristic kmeans` (lan/wan_light/wan_mid)
    - w3 `mkl-fout l2svm` (lan/wan_light/wan_mid)
    - w3 `mkl-min-st-cut kmeans lan`
    - w1 `mkl-cost kmeans lan`

- **가설**:
  - 실제 런타임 예외가 아니라, runner/수집/컨테이너 종료 시점/파일 이동(또는 이름 충돌) 문제일 수 있음.

- **해결 방향**:
  - Tier0 재실행 시에는 `--continue-on-failure 0` + (가능하면) `--keep-containers`로
    즉시 원인(compose/log path/컨테이너 상태)을 확인.

---

## 이슈 5: heuristic l2svm `wan_mid` timeout (rc=124)
- **상태**: 진행중

- **재현(대표)**:
  - log: `experiments/results/fed3/mkl-heuristic/l2svm_dataset-P2P2D_coordinator_mkl-heuristic_ralph_full_no_lm_20260223_073650_20260223_073650_1094753_w3_mkl-heuristic_l2svm_wan_mid_wan_mid.log`
