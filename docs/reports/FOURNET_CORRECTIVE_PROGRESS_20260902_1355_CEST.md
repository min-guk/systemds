# Four-Network COFEE Corrective Experiment Progress Report

- 최종 갱신: 2026-09-02 17:10 CEST
- 소스 저장소: `/home/mchoi/g014-fournet-fix-systemds-20260901`
- 브랜치: `cofee-fournet-w1357-20260901`
- 수정 소스 커밋: `9aefc6473957495846d45395dae03251fd9ea153`
- 커밋 메시지: `[SYSTEMDS-COFEE] Repair iterative materialization planning`
- 허용 노드: `so002`–`so009`; proxy인 `so001`은 사용하지 않음
- 현재 상태: 최종 커밋 전용 immutable stage로 832-cell 전체 runtime 캠페인 실행 중
- 중단 조건: failed cell, provenance 불일치, semantic parity 실패, 또는 사용자가 중단을 지시한 경우

## 1. Executive summary

ALS의 비정상적인 장시간 실행, LM WAN-Heavy의 반복 FOUT→CP materialization 비용 과대계상, PCA WAN-Mid/WAN-Heavy DP의 local-materialization basin 문제를 수정했다. 변경은 하나의 소스 커밋으로 고정했고, 60개 targeted regression, 전체 package build, selective planning/runtime 재검증을 통과했다.

최종 full campaign은 과거 결과 root를 재사용하지 않는다. 따라서 서로 다른 JAR로 얻은 셀이 섞이지 않으며, `9aefc647…` 소스와 SHA-256이 고정된 JAR만 사용하는 새 root에서 ML → P1 → P2 → SliceLine 순으로 실행된다.

| 항목 | 상태 | 핵심 증거 |
|---|---|---|
| ALS LAN 장시간 실행 | 수정·selective validation 완료 | corrected Exact/DP runtime 89.735/87.592 s; 기존 약 3,100 s 결과는 구 JAR artifact |
| LM WAN-Heavy ordering | 비용 모델 수정·검증 완료 | DP w7 objective `12270.611265`; reusable local materialization 1회 유지 |
| PCA WAN-Mid/Heavy DP | conflict traversal/batching 수정·검증 완료 | 8개 planning cell 모두 개선; WAN-Heavy w7 runtime 58.213 s |
| Privacy feasibility | 회귀 검증 완료 | DP가 shared privacy-filtered placement domain 밖으로 나가지 않음을 targeted tests로 확인 |
| 최종 전체 캠페인 | 실행 중 | 832 cells, 첫 셀 complete, failed cell 없음 |

## 2. 최종 소스 변경

커밋 `9aefc6473957495846d45395dae03251fd9ea153`은 9개 파일에서 406 insertions, 134 deletions를 포함한다.

### 2.1 Shared/Exact physical cost fixes

- 함수 내부에서 재사용되는 FOUT→CP download를 매 iteration의 전체 network transfer로 계산하지 않고 reusable materialization activation으로 계산한다.
- function-call weight는 occurrence authority를 합성해 계산한다.
- logical function source에서 downstream CP로 이어지는 중복 download factor를 제거한다.
- ALS WDivMM 입력은 실제 reusable source residency에 맞춰 비용을 계산한다.

### 2.2 Loop-frequency facts

- compiler transient-write predicate를 분석 전에 unwrap한다.
- exact scalar binding을 predicate analysis에 overlay한다.
- convergence loop의 hard cap에는 `min(cap, max(10, sqrt(cap)))` 형태의 추정치를 적용한다.

### 2.3 Local-conflict DP fixes

- deferred materialization-sensitive block과 ordinary block을 함께 batch해 maximal block이 포함된 작은 block을 solve 전에 retire하도록 한다.
- conflict traversal은 선택된 물리 경로인 FED/FOUT과, FED alternative가 남아 있는 연속 CP/LOUT decision만 따라간다.
- CP-only operator에서 traversal을 중단한다.
- compiled DML function call-boundary edge는 제외하고 logical function-input edge를 사용한다.
- arbitrary whole-program traversal을 제거해 local-conflict optimizer의 설계 범위를 보존한다.

## 3. 검증 결과

### 3.1 Build and tests

- `mvn -q -DskipTests package`: PASS
- `git diff --check`: PASS
- targeted regression: **60 tests, 0 failures, 0 errors, 0 skipped**
- production debug marker: 없음
- repository-wide Checkstyle은 SystemDS/generated source 전반의 기존 368,888건 baseline violation 때문에 실패했다. 따라서 이를 변경분 실패로 해석하지 않았으며, 변경분 검증은 package, targeted tests, diff check로 수행했다.

주요 regression 범위:

- LM WAN-Heavy reusable materialization
- PCA WAN-Heavy local-conflict escape
- ALS WDivMM reusable residency
- Exact physical certificate/cost integration
- local categorical batching
- DP/shared privacy placement and movement legality
- transient-table/rewire behavior

### 3.2 Final immutable stage

- stage: `/home/mchoi/cofee-w1357-stage-20260902-9aefc64`
- source commit: `9aefc6473957495846d45395dae03251fd9ea153`
- JAR SHA-256: `1b65364f85c0e8fef7eb7792442c6322546e863bad1a1fe99380133fdcf05b69`
- `STAGE_CONTENT.sha256` SHA-256: `e0b2b0025baa6fef1a374991c99bfbe720ebbd574ca6494d95710d3088c100d7`
- provenance SHA-256: `d73a88db18a54bf4a19eb8d1821701b3ff16c8029bbbe10bc076665c9182c4fe`
- deployment: `so002`–`so009` 전 노드에서 hash 검증 완료
- replication logs: `/home/mchoi/g014-runtime-4net-w1357-20260901-control/final-9aefc64-stage-replication/`

## 4. Selective planning evidence

### 4.1 PCA DP

최종 후보 stage에서 PCA DP는 이전 LM-fix baseline의 local-materialization basin을 벗어났다.

| Profile | Workers | 이전 objective | 수정 objective | 수정 selected FED/FOUT | Local materialization |
|---|---:|---:|---:|---:|---:|
| WAN-Mid | 1 | 107902.23 | 23788.84 | 21 / 19 | 2 |
| WAN-Mid | 3 | 50155.51 | 12564.43 | 19 / 14 | 0 |
| WAN-Mid | 5 | 38494.65 | 10866.20 | 15 / 9 | 1 |
| WAN-Mid | 7 | 33483.59 | 9892.42 | 15 / 9 | 1 |
| WAN-Heavy | 1 | 140045.69 | 26494.44 | 21 / 19 | 2 |
| WAN-Heavy | 3 | 60936.66 | 15089.93 | 19 / 14 | 0 |
| WAN-Heavy | 5 | 45114.85 | 12847.93 | 19 / 14 | 0 |
| WAN-Heavy | 7 | 38334.08 | 11907.28 | 19 / 14 | 0 |

Artifacts:

- `/home/mchoi/g014-fournet-selective-planning-pca-wan_mid-dp-w1357-20260902-pcadpfix`
- `/home/mchoi/g014-fournet-selective-planning-pca-wan_heavy-dp-w1357-20260902-pcadpfix`

### 4.2 LM preservation

LM WAN-Heavy w7 DP는 수정 전후 동일한 물리 placement를 유지한다.

- objective: `12270.611265255675`
- placement fingerprint: `195157b49f352fe673052c29cbe8bc25ada8bfd645ec874058c7002ca37d0bef`
- selected FED/FOUT: `9 / 8`
- relocation: `0`
- reusable local materialization: `1`

최종 committed-stage 재검증 root:
`/home/mchoi/g014-fournet-final-planning-lm-wan_heavy-w7-dp-20260902-9aefc64`

PCA WAN-Heavy w7도 최종 committed stage에서 candidate stage와 동일한 objective, placement fingerprint, selected states를 재현했다:
`/home/mchoi/g014-fournet-final-planning-pca-wan_heavy-w7-dp-20260902-9aefc64`.

## 5. Selective runtime evidence

### 5.1 PCA WAN-Mid DP

| Workers | Execution | Planner | FED instructions |
|---:|---:|---:|---:|
| 1 | 50.382 s | 1.287262 s | 19 |
| 3 | 54.650 s | 0.841863 s | 16 |
| 5 | 59.275 s | 0.661303 s | 17 |
| 7 | 69.155 s | 0.578095 s | 17 |

Root:
`/home/mchoi/g014-fournet-selective-runtime-pca-wan_mid-dp-w1357-20260902-pcadpfix`

### 5.2 PCA WAN-Heavy DP

| Workers | Execution | Planner | FED instructions |
|---:|---:|---:|---:|
| 1 | 64.914 s | 1.312279 s | 19 |
| 3 | 60.887 s | 0.660686 s | 16 |
| 5 | 59.959 s | 0.673370 s | 16 |
| 7 | 58.213 s | 0.695054 s | 16 |

Root:
`/home/mchoi/g014-fournet-selective-runtime-pca-wan_heavy-dp-w1357-20260902-pcadpfix`

WAN-Heavy w7은 잘못된 이전 plan의 66.067 s, FED execution/UDF 0 상태에서 58.213 s, FED instructions 16으로 복구됐다. 두 network profile의 각 worker count는 동일한 output semantic SHA를 가져 network emulation이 결과 의미를 바꾸지 않았음을 확인했다.

## 6. 최종 832-cell 캠페인

### 6.1 Frozen definition

- output root: `/home/mchoi/g014-fournet-w1357-runtime-20260902-9aefc64-v1`
- archive root: `/home/mchoi/cofee-w1357-evidence-archive-20260902-9aefc64`
- campaign log: `/home/mchoi/g014-runtime-4net-w1357-20260901-control/full-runtime-campaign-9aefc64.log`
- heartbeat: `/home/mchoi/g014-runtime-4net-w1357-20260901-control/full-runtime-campaign-9aefc64-heartbeat.log`
- launcher tmux session: `cofee-9aefc64-full`
- watcher tmux session: `cofee-9aefc64-watch`
- execution order: ML → P1 → P2 → SliceLine
- profiles: LAN, WAN-Light, WAN-Mid, WAN-Heavy
- workers: 1, 3, 5, 7
- planners: FedAll, Heuristic, Exact, DP
- total: 832 cells
- Docker image: `cofee-experiment:content-0861f4ff197c868f42abf6478b66505f650325c267caa8be2e82b4b6317c7ff0`
- per-cell timeout: 172,800 s

`campaign.json`이 고정한 JAR SHA는 최종 stage의 SHA와 동일하고, archive host는 proxy가 아닌 `so007`이다.

### 6.2 Launch validation at 2026-09-02 17:10 CEST

- progress: `1 / 832`
- failed cell: `null`
- completed first cell: `ml|pca|lan|w1|FedAll`
- first-cell wall time: `75.333363 s`
- SystemDS execution time: `56.424 s`
- FedPlanner time: `0.485860 s`
- FED instructions/UDF: `34 / 0`
- semantic SHA-256: `5f24c9136d4b088d38c1289f4470d3a0acce650f40e5ca24ee20d1288feb6ac6`
- remote evidence: durable archive complete on `so007`
- active next cell: `ml|pca|lan|w1|Heuristic`

이 첫 셀은 return code 0, fatal-error 0, semantic evidence 생성, remote evidence archive를 모두 충족했다. 따라서 stage, image, topology, network setup, privacy-aware execution, artifact collection을 포함한 end-to-end launch path가 정상임을 확인했다.

## 7. 운영 원칙과 다음 단계

1. 새 root에는 최종 JAR 이외의 결과를 혼합하지 않는다.
2. watcher가 `failed_cell`을 감지하면 attention JSON을 생성한다.
3. failure 발생 시 해당 셀 artifact와 remote container logs를 분석하고, 원인을 수정한 뒤 새 immutable stage/root를 만든다.
4. 정상 실행 중에는 동일 캠페인을 병렬 중복 실행하지 않는다.
5. 완료 후 semantic parity, planner/runtime ordering, planning time, FED instruction statistics를 전수 집계하고 그래프를 갱신한다.

현재는 두 번째 셀이 실행 중이며, 캠페인은 자동으로 다음 셀을 계속 수행한다.
