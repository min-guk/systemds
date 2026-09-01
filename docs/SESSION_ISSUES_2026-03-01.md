# Session Issues — 2026-03-01

This document captures planner/runtime issues encountered and fixed during this session.

---

## Issue 1 — Federated worker cross-channel request interleaving causes missing vars + `Redundant release` (kmeans/logreg) (Fixed)

**Status:** Fixed

**환경 / 조건**
- Dataset: `P2P2D`
- Profile: `lan`
- Planner(s): observed with MinST (`mkl-min-st-cut`) (likely affects other planners/workloads too)
- Failures are worker-side and manifest as coordinator runtime errors.
- 대표 재현:
  - `kmeans` with 4 federated workers
  - `logreg` with 4 federated workers

**재현 로그 (대표)**
- Coordinator:
  - `experiments/results/fed4/mkl-min-st-cut/kmeans_dataset-P2P2D_coordinator_mkl-min-st-cut_dbg_kmeans_w4_minst_20260301_1_lan.log`
- Worker:
  - `experiments/results/workers/logs/worker2-8002_dbg_kmeans_w4_minst_20260301_1_lan.out`

**관측 증상**
- Federated worker에서 다음 시그니처가 반복적으로 발생:
  - `Variable 'XXX' does not exist in the symbol table.`
  - `DMLRuntimeException: Redundant release.` (예: `AggregateBinaryCPInstruction`에서 `ExecutionContext.releaseMatrixInput` 호출 중)
- 로그 상에서 `rmvar`가 많이 실행되며(heavy hitters), 동일 PID/TID에서 여러 요청 배치가 근접 시간에 연속으로 실패.

**원인 분석**
- Federated worker는 **stateful server**이며 coordinator는 독립 Netty 채널(연결)을 통해 여러 RPC를 **동시에(in-flight)** 보낼 수 있다.
  - 특히 변수 정리(`execCleanup` → worker-side `rmvar`)는 성능을 위해 **wait 없이 비동기**로 전송될 수 있다.
- Worker는 동일 coordinator `(host,PID)`에 대한 요청 배치들(`PUT_VAR -> EXEC_INST -> GET_VAR -> rmvar` 등)이
  **순서대로, interleaving 없이** 처리된다는 가정을 사실상 가지고 있으나,
  기존 구현은 채널 단위로만 직렬화되어 **서로 다른 채널의 배치가 interleave**될 수 있었다.
- 또한 federated worker의 `exec()` 실패 경로에서 `ec.getVariables().releaseAcquiredData()`가 실행되는데,
  요청 배치가 interleave되면 다른 in-flight 요청이 acquire한 객체까지 release하여
  이후 정상 release 시점에 `Redundant release`가 발생할 수 있다.
- 결과적으로:
  - 배치들이 뒤섞이며 아직 생성되지 않았거나 이미 정리된 변수 ID를 참조 → `Variable 'X' does not exist`
  - cleanup/release 타이밍이 꼬이며 `CacheableData.release`가 중복 호출 → `Redundant release`

**해결 요약**
- Worker에서 coordinator `(host,PID)`별로 요청 배치를 직렬화.
  - `FederatedWorkerHandler`에서 각 `FederatedRequest[]` 배치 처리 전체를
    `synchronized(ecm) { ... }`로 감싸
    **동일 (host,PID)의 배치들이 서로 interleave되지 않도록 보장**.
  - (이전 시도) per-(PID,TID) 직렬화만으로는 `logreg/lan`(4 workers)에서 여전히
    `AggregateUnaryCPInstruction` 경로의 `Redundant release`가 남아, 최종적으로 per-(host,PID)로 강화.

**수정 파일**
- `src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederatedWorkerHandler.java`

**패치 커밋**
- `8ac4cc03f9` — initial attempt: serialize batches per (PID,TID) (helps kmeans but logreg w4 still flaky)
- `ca075fe948` — final: serialize batches per (host,PID) via `synchronized(ecm)` (fixes logreg w4 `Redundant release`)

**검증**
- Build:
  - `mvn -q -DskipTests compile` (PASS)
  - `mvn -q -DskipTests package` (PASS)
  - `mvn -q -Dtest=FederatedPlanCostEnumeratorTest test` (PASS)
- Targeted localproc repro (P2P2D / lan / mkl-min-st-cut):
  - kmeans, workers=4:
    - `RUN_ID=lockfix2_w4_minst_kmeans_lan_20260301_1 bash experiments/run_LAN_localproc.sh --workers 4 --conf mkl-min-st-cut --dataset P2P2D --net-profile lan --alg kmeans` (PASS)
  - logreg, workers=1:
    - `RUN_ID=lockfix_w1_minst_logreg_lan_20260301_1 ... --salg logreg` (PASS)
  - logreg, workers=4:
    - `RUN_ID=lockfix2_w4_minst_logreg_lan_20260301_1 ... --salg logreg` (PASS)

**잠재 회귀 위험 / 감지**
- per-(host,PID) 직렬화로 인해 coordinator 단위로 worker-side 동시성이 줄어들 수 있음(특히 remote parfor).
  - 감지: parfor workload에서 federated worker 병목(큐잉)으로 실행 시간이 급증하는지 확인.
- CLEAR(ECM 제거)와 동시 요청이 섞이는 edge-case는 여전히 주의 필요(일반적으로 CLEAR는 종료 시점에만 발생).
