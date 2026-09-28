# W1357 FedFirst/AggLocal 구현 및 전체 실험 진행상황

> **후속 정책 변경 — 2026-09-28 15:55 UTC:** 사용자 요청으로 compile과 runtime의
> coordinator 실행 timeout을 **60초로 고정**했다. 아래 15:17 스냅샷은 과거 기록이다.
> run04는 22통과 후 정책 전환을 위해 중단했고, 중단된 1건은 planner 오류나 자연 timeout이 아니다.
> 해당 건의 4개 컨테이너와 8개 host lease 해제를 검증했다.
> 새 60초 기준은 별도 `run05` root에서 다시 측정하며 기존 장기 timeout 성공을 합산하지 않는다.
> 전체 compile survey는 실패/timeout도 기록하면서 계속하고, runtime은 새 기준 896조건 통과 전까지 차단한다.
> 60초는 workload 종료신호 시점이다. 기존 강제 종료 유예 30초와 setup/증거 수집/정리 시간은 별도다.

- **스냅샷:** 2026-09-28 15:17:23 UTC / 17:17:23 Europe/Berlin
- **저장소:** `/home/mchoi/w1357-paper-aligned-refactor`
- **상태:** 진행 중. 보고서 작성 때문에 실행을 중단하지 않았다.
- **주의:** 아래 수치는 이 시점에 완료 receipt가 저장된 결과만 센다. 이후 진행은 `run04/summary.json`과 비교 CSV에서 확인한다.

## 1. 요약

| 요청 | 현재 상태 |
|---|---|
| FedFirst/AggLocal 구현 커밋·푸시 | 완료. 후속 성능/정확성 수정도 origin에 푸시됨 |
| 전체 896조건 compile/planning | **4 통과 / 0 실패 / 892 미완료** (0.45% 통과) |
| 장애 진단·수정·재검증 | W3 bootstrap 폭증과 durable-anchor emission 문제를 수정하고 W3/LAN production compile 통과 |
| 4개 플래너 시간 비교 | 아직 불가능. 현재 DP-local부터 실행 중이며 나머지 플래너의 본행렬 결과는 없음 |
| runtime | **0건 실행**. 동일 엔진의 896조건 compile gate가 아직 닫혀 있음 |

현재 엔진의 미해결 compile 실패는 아직 없지만, 미완료 조건이 많으므로 “전체 문제가 없다”는 결론은 내릴 수 없다.

## 2. 확정된 실험 범위와 실행 순서

- **ML training 10개:** logreg, l2svm, pca, als, kmeans, lm, steplm, glm, gnmf, gmm.
- **추가:** P1_FULL, P2_PREP, SliceLine ADULT·COVTYPE.
- SliceLine 2개는 사용자가 명시적으로 확정했다. KDD98·USCENSUS는 포함하지 않는다.
- **Worker:** 1 / 3 / 5 / 7.
- **Network:** LAN / WAN-light / WAN-mid / WAN-heavy.
- **Planner:** DP-local / FedFirst / AggLocal / DP-global(Exact).
- **총합:** 14 workloads × 4 worker 수 × 4 network × 4 planners = **896조건/단계**.

모든 workload 실행은 `scripts/fedplanner/run_LAN_docker.sh --campaign`을 사용한다.
compile은 **DP-local → FedFirst → AggLocal → Exact** 순서로 진행한다.
모든 compile 조건이 같은 엔진으로 통과한 뒤에만 runtime을 **logreg → l2svm → 나머지** 순서로 실행한다.
현재 실행 프로세스는 실패하면 정지하도록 되어 있으며, 원인을 수정한 경우 엔진을 새로 동결해 재검증한다.

## 3. 현재 동결된 엔진과 커밋

- 브랜치: `refactor/w1357-paper-aligned-20260928`
- 푸시 대상: **`origin` = `git@github.com:min-guk/systemds.git`**. Apache upstream에 푸시하지 않았다.
- 본행렬 엔진 코드 기준 커밋: **`ca7d8eb67fcffe4e576c4a1cd935dd24e4969d12`**.
- JAR SHA-256: `0b4cfe5dbc41c502f70fa65774f77c6b53eb6d6064e82f78a928e392c3f7152b`.
- 후속 보고서/기록 커밋은 엔진 변경이 아니다. 현재 행렬의 manifest/source/JAR는 그대로 유지한다.

주요 변경 커밋:

| 커밋 | 내용 |
|---|---|
| `4fe5932f43`, `5abe7ee758` | 비백트래킹 policy selector 및 FedFirst/AggLocal 연결 |
| `1fc8f00aaa`, `5205946c76` | Docker 검증과 구현 문서 |
| `ff012671e0`, `30dcd08ff6`, `6ddeeda9f7` | realization/authority 불변 계산 재사용, 비용 반복 계산·fingerprint 할당 감소 |
| `5763bb7893` | 896조건 compile 및 gated runtime harness |
| `b143c83567` | exact solver 수치 의미를 유지하는 할당/indexing 최적화 |
| `8468991128`, `71d2b2f9c8` | 일반 측정과 분리된 JFR/실제 VE 작업량/compact 진단 |
| `bc2f4ef9cc` | local 조건부 exact reduction·singleton compaction 기본 활성화 |
| `ca7d8eb67f` | graph-owned durable metadata를 live Hop 없이도 REFED에 전달 |

FedFirst/AggLocal의 공유 후보 조합 생성은 유지한다. “선택부가 가볍다”와 “후보 생성까지 입력 크기에 선형이다”는 다른 주장이다. 전체 성능 우위는 이번 4-planner 행렬 결과로 판단해야 한다.

## 4. 현재 엔진의 완료 측정값

단위: 초. **아래는 같은 run04 엔진의 일반 compile-only 실행만 포함한다.**

| Planner | Workload | Workers | Network | 결과 | 전체 compile | Shared search-space | Planning/selection adapter |
|---|---|---:|---|---|---:|---:|---:|
| DP-local | logreg | 3 | lan | passed | 77.969562 | 12.047577 | 62.934620 |
| DP-local | logreg | 1 | lan | passed | 206.913269 | 27.676215 | 177.424754 |
| DP-local | logreg | 1 | wan-light | passed | 211.483065 | 27.672141 | 181.834855 |
| DP-local | logreg | 1 | wan-mid | passed | 215.223092 | 26.708203 | 186.525290 |

- `compile`: RuntimeProgram construction까지의 production 전체 compile 시간.
- `shared search-space`: 공통 preparation + analysis.
- `planning/selection adapter`: planner setup/model/cost-surface/optimizer/selection/other-planning 합.
- application, conversion, final verification 등은 별도 항목이므로 마지막 두 열의 합이 전체 compile과 같지는 않다.
- 각 조건은 fresh workers/JVM의 **1회 표본**, warm-up 0회다. 아직 통계적 유의성이나 플래너별 우열을 주장하지 않는다.
- 이 스냅샷의 Docker 24GiB / JVM 16GiB / 8 cores, compile timeout은 900초였다. 이후 timeout만 위 정책에 따라 60초로 변경했다. setup만 병렬화하고 실제 측정은 한 번에 한 조건만 실행한다.
- 실패/timeout은 성공이나 infeasibility 증명으로 처리하지 않는다. 진단 실행의 timing은 일반 비교 CSV에서 제외한다.

### W3/LAN 회귀 성공의 확인 범위

기존 900초 timeout 조건이 새 엔진에서 **77.969562초**에 compile 통과했다.
shared search-space 12.047576852초, planning/selection adapter 62.934620264초다.

- 실제 compile-only 설정 및 RuntimeProgram 생성 확인.
- workload 실행 시작/완료 false, execution/run/Spark 및 runtime audit 실행 카운터 0.
- physical Hops planned/lowered **204/204**, missing/mismatch 0.
- synthetic 4건 lowering 일치.
- 해당 실행의 exact container cleanup 및 8개 host stage lease 해제 확인, OOM 0.

이 결과는 **해당 조건의 compile 문제 해결**을 증명한다. runtime 성공이나 전체 896조건 성공을 증명하지 않는다. 이전 timeout은 검열된 값이므로 정확한 speedup 분모로 사용하지 않는다.

## 5. 발견한 문제, 수정과 남은 위험

### 5.1 DP 초기 exact repair의 조합 폭증

- **증상:** logreg/W3/LAN이 900초에 timeout. OOM은 관측되지 않았다.
- **원인:** 첫 7개 physical decisions의 repair가 auxiliary 포함 70변수/240factor가 되고, 선택된 VE order가 **658,322,977,640회** 대입 평가를 요구했다.
- 다른 기존 세 order는 최대 factor 10,273,615,672 cells로 기존 production per-factor 한도를 넘었다. 단순 order 교체나 cap 상향은 하지 않았다.
- **수정:** 이미 존재하는 조건부 exact support/equivalence reduction + singleton substitution 경로를 local 기본값으로 사용한다.
- **진단 증거:** 동일 JAR/입력의 첫 block이 31 compiled variables와 **179,697,439회** 평가로 감소하고 bootstrap이 완료됐다. 약3,663.5배는 **symbolic 작업량 비율**이며 wall-time speedup이 아니다.
- **보존:** 공유 후보 생성, feasible 의미, canonical costs, privacy, authority, cheapest-repair 및 resource cap.
- **위험:** equal-cost 선택이 바뀌면 이후 local 선택/최종 DP 비용도 달라질 수 있다. 전역 optimality나 모든 assignment의 동일성을 보장하는 변경이 아니다.
- 기존 incremental soft budget은 bootstrap 이후 적용되며 hard 전체 planning 시간 제한은 아니다. 진단의 `RESOURCE_INITIAL`/gap∞를 목표 gap 달성으로 세지 않았다.

### 5.2 계획 선택 후 durable-anchor emission 실패

- **증상:** compact 진단에서 seed는 완료됐지만 `Relocation has no durable analysis-owned anchor`로 compile 실패.
- **계약 문제:** 검증된 graph-owned relocation action의 durable metadata가 있어도 emitter가 동일 anchor record를 가진 live Hop을 필수로 요구했다. DAG는 이미 concrete key와 `anchorHopId=-1`을 지원한다.
- **수정:** canonical action key를 먼저 직렬화하고 exact-record Hop이 있으면 기존 deterministic hint를 유지한다. 없으면 **그 action key와 -1 hint**를 전달한다.
- **금지한 우회:** 임의 anchor/동일 endpoint alias로 바꾸기, 후보·privacy·TR/TW 규칙 완화, runtime fallback.
- **검증:** no-live-hint actual DAG lowering, 잘못된 key 거절, exact consumer binding, rollback, live/key conflict 검사 유지. 이후 W3/LAN 일반 compile도 성공했다.
- **한계:** 원래 진단은 details=false여서 해당 W3 action의 구체적인 alias subtype은 보존되지 않았다. 계약 수정과 E2E 해결은 확인했지만 subtype을 추측해 확정하지 않는다.

### 5.3 반복 계산과 객체 할당

realization/authority proof 재검증, native-local 비용의 target-only 불변 계산, fingerprint 문자열 생성, solver의 중간 PreciseCost/indexing 반복을 줄였다. 기존 raw-bit/선택/오류 순서 goldens와 authority 회귀를 유지했다. 이 최적화만으로 W3 timeout이 해결되지는 않았으며, 조건부 문제 크기 감소가 추가로 필요했다.

## 6. 검증 현황과 범위 한계

- 최종 결합 Java: **31개 클래스 237/237 통과**, failure/error/skip 0.
- Python matrix 관련: **77/77 통과**.
- emission 전용 16개 및 기존 live/key conflict 1개 통과.
- baseline → intended RED → GREEN 증거, 독립 actual-diff review 승인, package exit0, bash syntax/diff-check 통과.
- GLOBAL Exact 설정/비용/cap은 이번 local 기본값 및 emission 수정에서 바꾸지 않았다.

**전체 저장소의 모든 테스트가 통과했다는 주장은 아니다.** 과거 별도 certificate fixture의 60M cell cap, GLM 테스트 JVM heap, 이전 엔진에서도 재현된 L2SVM/ForcedState expectation, 다른 worktree의 frozen producer 경로를 가리키는 Python provenance fixture gap은 이 green 집합 밖에 있다. shared auxiliary 직접 fixture coverage에도 한계가 있고 exact reducer 회귀 및 실제 canonical bootstrap이 이를 부분 보완한다.

## 7. 원본 증거와 다음 단계

### 현재 행렬

`/grid/3/cofee-lm-sweep-mchoi-20260914/w1357-policy-matrix-20260928-run04`

- `manifest.json`: 동일 엔진/source/입력/stage/network 계약.
- `summary.json`: 계속 갱신되는 전체 진행 건수와 gate.
- `compile-comparison.csv`, `runtime-comparison.csv`: 896행 비교표, 미완료/실패 포함.
- `attempts/compile/<attempt>/`: command/result/receipt/로그/netem/health/cleanup.
- `regression-verification.json`: W3/LAN 성공의 독립 확인.
- 이 보고서의 고정 스냅샷: `progress-snapshot-20260928T151723Z.json`.

추가 증거:
- `w1357-policy-matrix-20260928-diag03/jfr-analysis/REPORT.md`
- `w1357-policy-matrix-20260928-diag04/compact-comparison.json`
- `conditioned-reduction-parity-evidence`, `regional-compact-adoption-evidence`
- `emission-durable-metadata-evidence/root-combined-counts.json`
- 상세 문제 기록: [SESSION_ISSUES_2026-09-28.md](SESSION_ISSUES_2026-09-28.md)

위 증거 디렉터리들은 모두 `/grid/3/cofee-lm-sweep-mchoi-20260914/` 아래에 있다. 이전 엔진의 run01/run02/run03 및 diagnostic 통과/실패를 현재 성공 건수에 합산하지 않는다.

### 계속 수행할 일

1. 후속 사용자 정책에 따라 새 run05의 동일 frozen engine 및 60초 timeout으로 compile 조건을 측정한다. run04의 장기 timeout 결과는 별도로 보존한다.
2. 실패하면 원본·cleanup·자원 상태를 확인하고 원인 수정/회귀/E2E를 거친다. 엔진 변경 시 새 root로 분리한다.
3. 4개 플래너를 같은 조건끼리 비교해 전체 compile/shared search-space/planning 결과를 정리한다.
4. **896조건 compile gate 통과 후에만** logreg → l2svm → 나머지 runtime 및 수치 결과 비교를 실행한다.

스냅샷 당시 active attempt: `01790608542853124342-445b7f84`.

## 8. 60초 정책 적용 후 실제 확인 (16:04:17 UTC)

- 정책 커밋 `551fe1cfee` 푸시 완료. 엔진 JAR는 그대로이고 새 run05에서 측정 중이다.
- Python matrix 회귀 **83/83**, syntax/diff 검사 및 독립 review 통과.
- DP/logreg/W1의 LAN·WAN-light가 설정된60초 한도에서 timeout(rc124)되었다.
  프로세스 종료 회수까지61.692초·62.091초였고 두 건 모두 container 정리 확인/OOM0이다.
- 당시 새 기준 **0통과 / 2 timeout 실패 / 894대기**, runtime0. 실패를 성공 시간으로 채우지 않는다.
- 원본은 `w1357-policy-matrix-20260928-run05/timeout60-verification.json`;
  이후 진행 건수는 같은 root의 `summary.json`/비교 CSV를 따른다.

## 후속: 조기 privacy pruning 새 엔진 검증 (2026-09-28 18:24 UTC)

P0–P3 구현을 `a9b1ca711e`로 커밋·푸시했고 JAR `100628da…`로 run06을 시작했다.
384개 Java 회귀 통과/기존 GLM 오류1/기존 skip10, 마지막 비용·계획8개/Python83개 통과,
package 및 독립 scoped review 통과. 전체 green은 아니다.

첫 Docker DP-local/l2svm/W1/LAN compile11.133612초 통과. search-space6.777722초,
selection-adapter3.153528초다. 기존 엔진과 계획 hash가 같지만 search-space 시간 개선은 아직
입증하지 못했다. 새 matrix1성공/895대기, runtime0. timeout60초와896 compile gate 유지.

상세: [조기 pruning 구현·검증 보고서](FEDPLANNER_EARLY_PRIVACY_PRUNING_IMPLEMENTATION_2026-09-28_KO.md).
