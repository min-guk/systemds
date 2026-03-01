# Session Issues — 2026-03-01

This document captures planner/runtime issues encountered and fixed during this session.

---

## Issue 1 — Federated worker request interleaving causes missing variables + redundant releases in kmeans LAN (Fixed)

**Status:** Fixed

**Environment / 조건**
- Workload: `scripts/builtin/kmeans.dml` (P2P2D)
- Planner: MinST (`mkl-min-st-cut`) (also observed on other planners in older runs)
- Profile: `lan`
- Workers: 4

**재현 로그 (Before)**
- Coordinator:
  - `experiments/results/fed4/mkl-min-st-cut/kmeans_dataset-P2P2D_coordinator_mkl-min-st-cut_dbg_kmeans_w4_minst_20260301_1_lan.log`
- Worker (example):
  - `experiments/results/workers/logs/worker2-8002_dbg_kmeans_w4_minst_20260301_1_lan.out`

**관측 증상**
- Federated worker에서 `Variable '<id>' does not exist in the symbol table.`가 다수 발생하며 실행 실패
  - 예: `CP?ba+*?...` 실행 중 `Variable '12' does not exist ...`
- 일부 케이스에서 추가로 `DMLRuntimeException: Redundant release.`가 발생
  - stack: `ExecutionContext.releaseMatrixInput` → `AggregateBinaryCPInstruction.processNormal` → `CacheableData.release`

**원인 분석**
- Federated worker는 stateful(symbol table 보유)이며, coordinator의 동일 `(PID,TID)` 요청이
  **여러 Netty 채널**을 통해 동시에 도착할 수 있다.
- 이때 서로 다른 요청 batch들의 `PUT_VAR -> EXEC_INST -> GET_VAR -> rmvar` 시퀀스가 **cross-request interleaving**
  되어 out-of-order로 실행되며,
  - 필요한 변수가 `rmvar`로 먼저 제거되거나
  - 실패 cleanup(`releaseAcquiredData`)이 다른 요청의 READ lock을 건드려
  `missing var` 및 `redundant release` 연쇄가 발생한다.

**해결 요약**
- Worker-side에서 **(coordinator PID, TID) 단위로 request batch를 직렬화**하여
  cross-request interleaving을 차단.
- 구현:
  1) `ExecutionContextMap`에 tid별 stable lock 제공: `ExecutionContextMap.getLock(tid)`
  2) `FederatedWorkerHandler.createResponse(...)`에서 전체 batch를
     `synchronized(ecm.getLock(requests[0].getTID()))`로 감싸 직렬 처리

**수정 파일**
- `src/main/java/org/apache/sysds/runtime/controlprogram/federated/ExecutionContextMap.java`
- `src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederatedWorkerHandler.java`

**검증**
- Build: `mvn -q -DskipTests package`
- Runtime repro는 Docker/실환경에서 동일 run-id(kmeans LAN w4)로 재실행하여
  `missing var` / `redundant release` 미발생을 확인한다.

**잠재 회귀 위험 / 감지**
- (PID,TID) 동일 요청에 대해 동시 채널이 열리는 워크로드에서만 나타나는 race였으므로,
  감지: worker 로그에서
  - `Variable '<id>' does not exist in the symbol table`
  - `Redundant release`
  재등장 여부를 모니터링한다.

