# Session issues — 2026-08-03

## 1. 336-cell campaign was started before the four user-raised claims were proven

- **상태**: 새 단일-artifact 336-cell campaign 진행중
- **환경/조건**: commit `6356d7c8ca54031066a23a7076e3faebfc373293`, JAR SHA-256
  `2dd636d96319e54022d9b2e85f522e1c0c4dab61f2b6d39f08f4ab448b0b4ba0`, Docker-only
  one-pass campaign, DP → FedAll → Heuristic → MinST.
- **재현 절차**:
  - campaign output: `/home/mchoi/g007-one-pass-b403404-7a72f59-20260803-v1`
  - runner log: `/tmp/g014_one_pass_b403404_20260803.log`
  - inspect: `cat progress.json && wc -l rows.jsonl`
  - current replacement campaign:
    `/home/mchoi/g007-one-pass-6356d7c-c149283-20260803-v1`
- **관측 증상**: campaign을 28/336에서 중단했다. 완료된 28개 셀은 모두 DP이며,
  FedAll/Heuristic/MinST의 최신 동일-JAR Docker 결과는 0개였다. 따라서 worker=1 FULL의
  7-workload × 4-planner 범위, planner ordering, MinST runtime behavior를 완료로 주장할 수 없다.
- **원인 분석**: 코드/단위 테스트 증거와 full Docker runtime 증거를 같은 완료 수준으로 취급했다.
- **해결 요약**: 28개 결과는 진단 전용으로 보존하고 나머지 캠페인은 중단했다. 아래 비용모델과
  실험 격리를 수정·검증한 뒤 새 immutable stage/JAR/output으로 336셀을 처음부터
  한 번만 실행한다. 이전 output은 새 캠페인에 stitch/resume하지 않는다.
- **수정 파일**: 이 문서. 후속 수정 파일은 아래 이슈에 누적한다.
- **검증**: superseded campaign 중단 시 `progress.json`은 `completed=28`, `failed=false`; 관련 Docker
  resource는 0개였다. 새 stage의 descriptor/current resource contract가 통과했고, replacement campaign의
  manifest는 336 unique cells, attempts-per-cell=1, retry=NONE, 고정 source/JAR/harness/data/reference/seed를
  인증한다. 첫 셀 DP/KMeans/worker=1/LAN은 warm `20.948s`, oracle/scan/no-fallback/plan-stability/worker
  evidence/teardown 검증을 모두 통과했다.
- **잔여 이슈**: replacement campaign은 아직 1/336이므로 335개가 남아 있다.
- **잠재 회귀 위험**: 이전 28개 또는 superseded 결과가 새 그래프에 섞일 수 있다.
  새 그래프 생성기는 단일 manifest/JAR/stage identity와 정확히 336 unique cells를 fail-closed 검증해야 한다.
- **의사결정 근거**: runtime 결과를 planner 정당성의 증거로 사용하기 전에 동일 artifact와 완전한 셀 집합을 요구한다.

## 2. PCA worker scaling fluctuation has two distinct causes

- **상태**: 진행중
- **환경/조건**: DP, workload `pca`, workers 1..4, profiles `lan/wan_light/wan_mid`,
  cold diagnostic + warm fresh coordinator, warm `Total execution time` primary.
- **재현 절차**: campaign rows 4/10/16/22 (LAN), 5/11/17/23 (WAN-light),
  6/12/18/24 (WAN-mid)의 `raw_coordinator.log`와 stage의
  `results/workers/logs/worker*-<run_id>.out`를 비교한다.
- **관측 증상**:
  - LAN warm total: `56.645 → 52.943 → 64.841 → 66.742 s`.
  - WAN-light warm total: `59.749 → 54.022 → 61.238 → 60.565 s`.
  - WAN-mid warm total: `59.501 → 56.088 → 74.582 → 63.507 s`.
  - LAN에서는 worker-side `tsmm`과 coordinator `fed_tsmm`은 worker 증가에 따라 대체로 감소하지만,
    공통 CP `eigen`이 warm 기준 `49.539, 47.550, 60.617, 62.201 s`로 요동해 total을 지배한다.
  - 같은 셀의 cold/warm 사이에도 CP `eigen`이 최대 약 24초 차이 났다
    (worker=4 WAN-light: `71.127` vs `47.055 s`). seed/data 차이로 설명할 수 없는 runtime jitter이다.
  - WAN-mid worker=3은 final CP `ba+*`가 federated `X`를 materialize하며 warm ACQr 약 32.6초,
    worker=4는 CP `tsmm`을 선택해 warm 약 15.0초가 발생한다. 이 부분은 단순 노이즈가 아니라 plan/cost 문제다.
- **원인 분석**:
  1. Docker는 thread count와 `cpus: 8.0`만 고정하고 service별 physical core affinity를 고정하지 않아
     CP eigen 측정이 scheduler/frequency/CFS 영향에 노출된다.
  2. `FederatedCostModel.computeDownloadNetworkCost(mem,fType,n)`는 partitioned download의 total bytes를
     fan-in으로 나눈 뒤 network와 W2C serdes 양쪽에 같은 축소 payload를 사용한다. wire critical path는
     largest shard로 병렬화될 수 있지만 coordinator deserialize/copy는 모든 shard의 total bytes를 처리해야 하므로
     serdes 비용이 workers 배만큼 과소평가된다. 실제 staged JAR bytecode에서도 동일 동작을 확인했다.
- **해결 요약**:
  - planner: download를 `network(total/fanIn) + serdes(total) + extra latency/control`로 분리한다.
    후보는 닫지 않는다.
  - harness: CFS quota를 제거하고 coordinator/worker에 서로 겹치지 않는 4 physical core × 2 SMT
    sibling cpuset을 할당한다. host topology, rendered Compose, 실제 `docker inspect`를 모두 fail-closed
    검증하고 cell response에 resource evidence SHA-256을 결합한다.
  - 분석: total과 planner-sensitive stage(`total - eigen`, boundary ACQr, FED/CP kernel)를 함께 보고하되,
    사용자가 요구한 primary y축은 여전히 raw SystemDS total이다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedCostModelFallbackTest.java`
  - harness `docker/compose.yaml`, `run_LAN_docker.sh`, `tools/cpu_topology.py`,
    `tools/container_resource_evidence.py`, `tools/run_one_pass_performance.py`, `tools/stage_campaign.py`
    및 대응 테스트.
- **검증**:
  - cost model: `FederatedCostModelFallbackTest` 40/40, targeted integration 1/1 성공
    (`/tmp/g014_cost_model_final_20260803.log`). workers=1은 기존 directional formula와 같고,
    workers=2..4는 wire bytes만 fan-in으로 나누며 coordinator serdes는 total bytes를 유지한다.
  - harness: 최종 146/146 tests 성공 (`/tmp/g014_harness_full_c149283_20260803.log`).
  - 실제 Docker resource canary: coordinator `0-3,24-27`, worker1 `4-7,28-31`, 양쪽 모두
    `NanoCpus=0`, `CpuQuota=0`, restart 0 (`/tmp/g014-resource-canary-2208456.json`).
- **잔여 이슈**: 새 JAR의 PCA worker=1..4 Docker 24-cell 관문 실험이 아직 없으므로,
  수정된 비용이 실제 WAN-mid plan을 바로잡는지와 affinity 이후 eigen variance는 미검증이다.
- **잠재 회귀 위험**: serdes full-byte 반영이 CP materialization을 과도하게 비싸게 만들 수 있다.
  FULL/BROADCAST와 serdes-disabled compatibility 수식 테스트 및 실제 ACQr 비교로 감지한다.
- **의사결정 근거**: runtime-supported 후보를 닫지 않고 잘못된 boundary cost와 실험 격리를 수정한다.

## 3. Per-worker instruction CSV can be empty despite real execution

- **상태**: 하네스 원인/수정 검증 완료, 전체 campaign 검증 진행중
- **환경/조건**: multi-worker Docker cells, `startWorker.sh -instStats`, teardown through `stopWorker.sh`.
- **재현 절차**: 예를 들어 PCA worker=4 LAN run id
  `04cf28f1ecd22217c89f5524e22e2d70_lan`의 stage paths를 비교한다.
- **관측 증상**: worker1 CSV는 non-empty지만 worker2..4 CSV는 0 bytes였다. 그러나 각
  `results/workers/logs/workerN-...out`에는 `uacmean`, `tsmm`, `ba+*`와 누적 worker statistics가 있어
  모든 worker가 실제 실행했다.
- **원인 분석**: `tmp`/`results`가 worker container 사이에 공유되는데 기존 `stopWorker.sh`가
  `tmp/worker/*` 전체를 순회했다. 먼저 종료한 container가 다른 worker의 PID metadata까지 소비했고,
  그 JVM들은 Compose teardown에서 강제 종료되어 buffered `InstructionStatistics` CSV를 flush하지 못했다.
- **해결 요약**: 각 container가 `${HOSTNAME}-*` PID만 소유하도록 제한하고, TERM 후 최대 30초 기다리며,
  살아 있으면 SIGKILL로 성공 처리하지 않고 실패한다. 종료 후 raw log와 CSV가 regular/non-empty인지 확인한다.
  별도 worker evidence collector가 exact run-id/host/port/path/runtime receipt 및 CSV 필수 header와 최소 1개
  instruction row를 SHA-256으로 인증한다.
- **수정 파일**: harness `code/stopWorker.sh`, `tools/worker_evidence.py`, `run_LAN_docker.sh`,
  `tools/run_one_pass_performance.py` 및 테스트.
- **검증**:
  - 4-worker 실제 federated sum canary `/home/mchoi/g007-worker4-evidence-canary-success-1785777059`:
    결과 24.0, worker CSV 425/426/425/425 bytes, 모든 worker instruction row 1 (`uak+`).
  - 새 validator 포함 harness 최종 146/146 tests 성공.
- **잔여 이슈**: 336-cell의 모든 active worker에서 instruction row가 남는지는 새 campaign에서 검증한다.
- **잠재 회귀 위험**: stop timeout 증가가 lifecycle만 늘릴 수 있다. primary metric은 SystemDS execution time이므로
  성능 수치에는 포함되지 않지만 전체 campaign 소요시간은 기록한다.
- **의사결정 근거**: fake success나 부분 worker 응답 채택 없이 evidence lifecycle을 수정한다.

## 4. MinST “global optimum” claim is narrower than the user expected

- **상태**: 진행중
- **환경/조건**: production categorical MinST at commit `b403404`.
- **재현 절차**: `FederatedPlanMinSTCut`, `MinStExactPhysicalModel`,
  `MinStExactCostFactsProducer`, `MinStExactPhysicalOptimizer` 및 exact certificate tests를 검토한다.
- **관측 증상**: 현재 MinST는 generated finite categorical domain과 encoded factor objective 안에서는 exact다.
  하지만 모든 runtime-legal physical plan과의 bijection, actual wall-clock global optimum, 그리고 동일 canonical
  objective에서 `MinST <= DP`라는 관계는 아직 증명되지 않았다.
- **원인 분석**: DP는 별도 memo/recurrence/cumulative-share objective를 사용한다. MinST domain/evaluator와 DP의
  final selected physical plan 사이의 lossless adapter 및 bit-exact common evaluation gate가 없다.
- **해결 요약**: DP selected plan을 production rewire 이후 MinST model로 역투영하는 opt-in certificate를
  구현해 보았지만 실제 lifecycle에서 `PLACEMENT_ANALYSIS_PROGRAM_STRUCTURE_CHANGED`로 실패했다. DP rewire가
  프로그램 구조를 바꾼 뒤 MinST domain을 다시 생성하는 방식은 동일 objective/domain 증명이 아니므로 관련
  코드와 테스트는 전부 revert했다. 안전한 후속 설계는 DP enumeration/rewire **전** fully immutable MinST
  encoded objective basis를 캡처하고, DP가 남긴 identity-owned exact receipts만 그 basis로 역투영하는 것이다.
- **수정 파일**: production/test 변경 없음(실험적 certificate diff 전부 revert); 이 문서만 갱신.
- **검증**: 기존 MinST named suite 36 classes, 172 tests, 0 failure/error, 16 intentional legacy skip
  (`/tmp/g014_minst_named_final_20260803.log`). 이는 encoded MinST 자체의 exactness 증거이지 DP와의 공통
  objective 또는 wall-clock optimum 증거가 아니다.
- **잔여 이슈**: 전체 runtime-legal plan-space completeness theorem과 wall-clock optimum은 별개의 미해결 주장이다.
- **잠재 회귀 위험**: adapter가 DP semantics를 재추론하면 선택 plan을 왜곡할 수 있다. selected exact receipts에서만
  변환하고 round-trip identity/fingerprint로 감지한다.
- **의사결정 근거**: solver exactness, domain completeness, modeled-cost optimality, wall-clock optimality를 분리한다.

## 5. Worker=1 FULL and policy descriptions must not be over-generalized

- **상태**: 진행중
- **환경/조건**: four planners, worker=1, seven campaign workloads.
- **관측 증상**:
  - DP KMeans worker=1 Docker plan은 FOUT/REFED를 실제 실행해 core FULL path를 입증했다.
  - 그러나 opcode/shape별 runtime gates(예: WSLOSS/WUMM axis-only, WDIVMM FULL CP/FOUT,
    scalar/특정 aggregate LOUT, TSMM single-partition proof, multireturn function output)가 남아 있으므로
    “worker=1에서 모든 연산이 FULL FED 가능”은 잘못된 주장이다.
  - FedAll은 “가능한 REFED를 전부 방출”하지 않는다. FED/FOUT/PRESENT를 lexicographically 최대화한 뒤
    direct resident FOUT reuse와 shared relocation을 고려해 필요한 REFED만 최소화한다.
  - Heuristic은 단순히 LOUT marker만 제외한 FedAll이 아니다. marker 뒤 pathwise local prefix를 만들고,
    unique exact re-entry proof가 있을 때만 FedAll 공간으로 복귀한다.
- **원인 분석**: policy slogan이 production selector와 runtime caps보다 넓었다.
- **해결 요약**: 위 정확한 policy를 검증 기준으로 사용한다. unsupported 조합을 억지로 FULL/FED로 열지 않고,
  runtime-supported one-worker FULL candidates가 임의로 닫히지 않는지만 검사한다.
- **수정 파일**: 이 문서; 필요시 테스트/실험 validator를 후속 기록.
- **검증**: 새 336 campaign의 worker=1 84개 셀 전체 runtime success와 plan fingerprint/REFED/FOUT receipts.
- **잔여 이슈**: 최신 동일-JAR FedAll/Heuristic/MinST worker=1 Docker 결과가 아직 없다.
- **잠재 회귀 위험**: “planner 차별화”를 위해 합법 후보를 인위적으로 닫을 위험. candidate count와 ReasonCode를 함께 검증한다.
- **의사결정 근거**: runtime caps와 exact placement policy를 따르며 임의 candidate closure를 금지한다.

## 6. New one-pass request and Docker evidence were not fully bound

- **상태**: 해결(하네스), 새 campaign에서 재검증 예정
- **환경/조건**: `tools/run_one_pass_performance.py`, immutable stage replay, one-cell Docker lifecycle.
- **관측 증상**:
  - request가 `dimensions={canonical_cell}`만 기록하지만 response validator는 workers/profile을 읽어 첫 실제
    cell에서 실패할 수 있었다.
  - script를 외부 cwd에서 직접 실행하면 `from tools.worker_evidence` import가 실패할 수 있었다.
  - worker evidence가 run token/profile/results root/port와 정확히 결합되지 않았고, CSV non-empty만으로는
    header-only 파일을 걸러낼 수 없었다.
  - cpuset은 Compose 설정에만 있고 실제 container `HostConfig` 증거가 cell result에 없었다.
  - cpuset을 필수화한 뒤 stage validator가 topology 변수를 만들지 않아 production stage 검증 전에 실패할 수 있었다.
- **원인 분석**: unit fixture가 response schema와 production stage의 환경 해석 경로를 충분히 그대로 재현하지 않았다.
- **해결 요약**: canonical full dimensions를 request에 기록하고, external-cwd import bootstrap을 추가했다.
  worker receipt의 exact path/duration/header+row를 검증한다. stage validator도 동일 host topology contract를
  렌더링하고 cpuset/thread/no-quota를 검증한다. 실제 container inspect manifest를 exact run-id와 response SHA에 결합한다.
- **수정 파일**: harness `tools/run_one_pass_performance.py`, `tools/worker_evidence.py`,
  `tools/container_resource_evidence.py`, `tools/stage_campaign.py`, `run_LAN_docker.sh` 및 대응 테스트.
- **검증**: harness 최종 146/146 tests, rendered Compose resource validation, 실제 2-container Docker inspect canary 성공.
- **잔여 이슈**: 새 immutable stage 생성과 첫 실제 performance cell response validation은 아직 수행 전이다.
- **잠재 회귀 위험**: deterministic run token이 같은 실패 campaign의 mutable evidence와 충돌할 수 있다.
  per-run runtime receipt exact-count와 evidence exclusive publication으로 재사용을 fail-closed 감지한다.
- **의사결정 근거**: 성능 결과가 주장하는 실행환경과 worker 실행을 cell별 인증하며, 결과 없는 실행을 성공 처리하지 않는다.

## 7. Historical stage replay was incorrectly coupled to the current four-worker resource policy

- **상태**: 하네스 해결, 새 production stage 재검증 예정
- **환경/조건**: historical CP/reference producer stage는 coordinator + worker1..8을 고정해 생성됐고,
  현재 performance campaign은 coordinator + worker1..4 및 service별 cpuset/no-CFS-quota 계약을 사용한다.
- **재현 절차**:
  - historical descriptor:
    `/home/mchoi/g007-critical-validation-b403404-7a72f59-20260803-v1/g007-stage-07c9e88eee72b868a71dfec6fa9bffd0c25594420fadbc74e405b608e7253632/stage-descriptor.json`
  - current `tools/stage_campaign.py validate --descriptor <descriptor>`를 실행한다.
  - 실패 수정 전 새 stage 생성 시 `compose stage override diverged from exact descriptor mounts` 또는
    current resource service inventory 오류가 발생했다.
- **관측 증상**: 변경된 verifier가 historical stage의 overlay를 현재 4-worker 목록으로 다시 생성하고,
  generic descriptor replay에도 현재 cpuset/resource 정책을 적용했다. 따라서 producer 당시에 정확했던
  8-worker immutable descriptor가 현재 정책 변경만으로 invalid가 됐다. 실패한 stage 생성 시도는
  fail-closed로 중단됐고 final content-addressed stage는 publication되지 않았다.
- **원인 분석**: (1) immutable artifact 자체의 과거 계약 재현과 (2) 새 성능 캠페인이 반드시 따라야 하는
  현재 resource 계약을 하나의 검증 단계로 취급했다.
- **해결 요약**:
  - overlay와 generic mount replay는 각 stage의 frozen base Compose에서 exact canonical service inventory를
    추출해 사용한다.
  - generic `validate`는 historical descriptor 자체만 검증한다.
  - 현재 resource 정책은 명시적 `--require-current-resource-contract`로 분리하고 one-pass runner가 이를
    항상 요구한다. 새 production stage 생성/재사용도 current contract를 강제한다.
  - non-production `/tmp` stage fixture는 Snap Docker가 접근할 수 없으므로 실제 Compose render를 생략하되,
    production path와 explicit current-contract validation은 생략하지 않는다.
- **수정 파일**:
  - harness `tools/stage_campaign.py`
  - harness `tools/run_one_pass_performance.py`
  - harness `tests/test_cp_stage_lifecycle.py`
  - harness `tests/test_cpu_topology.py`
- **검증**:
  - historical descriptor generic replay 성공; 출력은
    `/tmp/g014_old_stage_generic_replay_20260803.json`에 보존했다.
  - focused stage/manifest/topology tests 49/49 및 추가 topology test 8/8 성공.
  - harness commit `c1492832794c045e9cca65acd7c128e9cf21af79`.
  - 전체 suite 146/146 성공; `/tmp/g014_harness_full_c149283_20260803.log`.
- **잔여 이슈**: 새 4-worker production stage가 current resource flag와 실제 Docker render를 모두 통과해야 한다.
- **잠재 회귀 위험**: service parser가 noncanonical/추가 service를 누락하면 stage overlay가 부분 생성될 수 있다.
  exact `coordinator, worker1..N` 순서와 exact rendered service set 비교로 fail-closed 감지한다.
- **의사결정 근거**: immutable historical identity는 생성 시 계약으로 재현하되, 새 performance result는
  별도의 명시적 현재 자원 계약을 반드시 충족시킨다.

## 8. 새 DP 24-cell 관문은 KMeans 비용 문제를 닫았지만 PCA raw-total 단조성을 증명하지 못했다

- **상태**: 부분 해결 / 전체 campaign 진행중
- **환경/조건**: source commit `8e3d57a7b6713336465161a0c2d38183d6b064f4`, harness commit
  `c1492832794c045e9cca65acd7c128e9cf21af79`, JAR SHA-256
  `ba2ad01628517460f1367cb03c19d32b2b47d2be2fa40e1f0e7042633da93dfc`, Docker-only
  one-pass output `/home/mchoi/g007-one-pass-8e3d57a-c149283-20260803-v2`.
- **재현 절차**:
  - `cat <output>/progress.json` (`completed=24`, `last_cell=workers=4|planner=DP|workload=pca|profile=wan_mid`)
  - 각 row의 `cold_bundle`/`warm_bundle`에 있는 `raw_coordinator.log`, `metric.json`,
    `semantic_oracle.json`, `scan.json` 및 cell-bound resource/worker evidence를 비교한다.
- **관측 증상**:
  - 24/24 모두 attempt 1, semantic oracle 성공, runtime scan clean, fallback false, restart 0,
    teardown zero resources였고 같은 셀의 cold/warm runtime-plan SHA-256은 동일했다.
  - DP KMeans warm total은 worker 1→4에서 LAN `20.703→18.221→15.764→12.613`,
    WAN-light `50.154→33.096→28.430→25.845`, WAN-mid
    `115.839→74.841→71.748→62.615 s`로 모두 단조 감소했다. 이전 W2C cost 과소평가로 보였던
    병적 PCA WAN-mid CP materialization도 사라졌다.
  - DP PCA warm total은 LAN `54.372→57.583→55.245→50.018`, WAN-light
    `54.038→54.355→58.155→50.780`, WAN-mid `57.814→69.189→61.282→53.606 s`로
    여전히 단조적이지 않다.
  - 그러나 PCA의 planner-sensitive `fed_tsmm`은 대체로 worker 수와 함께 감소하고, total 요동은
    동일한 coordinator-local `eigen` 1회가 `44.986–59.966 s`로 변한 것이 지배한다. 동일 worker 수에서는
    profile/cold/warm 사이 output SHA-256과 runtime-plan SHA-256이 정확히 같으므로 seed/data/plan 차이가 아니다.
    builtin PCA는 1050×1050 Commons Math `EigenDecomposition`을 CP에서 수행하며 이 단계는 MinST/DP의
    worker placement 선택 대상이 아니다.
- **원인 분석**:
  1. 수정된 partitioned W2C 비용은 실제 plan 병리를 제거했고 KMeans scaling도 개선했다.
  2. 남은 PCA raw-total fluctuation은 single-run coordinator-local eigen의 주파수/스케줄링 변동이다.
     서비스 cpuset은 겹치지 않지만 host CPU governor는 `schedutil`, boost enabled이고 host task/IRQ는 해당
     코어에서 배제되지 않는다. cpuset은 container 간 격리이지 host-wide exclusive-core 예약이 아니다.
  3. 따라서 이 결과만으로 planner worker-scaling 결함을 주장할 수도, 반대로 모든 fluctuation이 해결됐다고
     주장할 수도 없다. raw total과 planner-sensitive decomposition을 함께 보고해야 한다.
- **해결 요약**:
  - planner 후보를 닫거나 PCA를 다른 workload로 바꾸지 않는다. canonical builtin PCA와 raw SystemDS total을
    유지한다.
  - current stage의 24개 결과를 중복 실행하지 않고 DP 나머지 셀을 이어서 수행한다. 전체 결과에서는 동일
    plan hash/output hash, FED/boundary heavy hitters, CP-only fixed kernels를 함께 분해해 구현 오류와 측정 잡음을
    구분한다.
- **수정 파일**: 이 문서만 추가 수정. 이 관측 때문에 production planner/runtime 코드는 변경하지 않았다.
- **검증**: 12개 PCA cell의 cold/warm 24개 로그를 재파싱했고, 각 cell에서 plan hash 일치 및 각
  worker/profile 내부 output SHA-256 일치를 확인했다. 모든 24개 row의 인증/오라클/teardown 조건도 재확인했다.
- **잔여 이슈**: DP의 나머지 60개와 FedAll/Heuristic/MinST 252개가 아직 없다. worker=1 FULL 전체,
  네 planner ordering, MinST encoded-objective/runtime 관계는 미검증이다.
- **잠재 회귀 위험**: raw total만 보고 CP 고정 병목을 planner regression으로 오판하거나, 반대로 decomposition만
  보고 사용자-visible total 요동을 숨길 수 있다. 최종 그래프는 raw total을 유지하고 별도 표에 CP/FED 분해와
  plan fingerprint를 같이 기록한다.
- **의사결정 근거**: runtime-supported 후보를 인위적으로 닫지 않으며, cost/model 결함과 환경 노이즈를
  동일 현상으로 취급하지 않는다.

## 9. worker=1 FULL append FOUT이 local-backed 혼합 map을 만들어 downstream FED MM이 실패한다

- **상태**: runtime 단위 회귀 및 동일 실패 셀 Docker canary 해결 / 새 336-cell campaign 진행중
- **환경/조건**: 실패 source `8e3d57a7b6713336465161a0c2d38183d6b064f4`, 수정 source
  `6356d7c8ca54031066a23a7076e3faebfc373293`, harness
  `c1492832794c045e9cca65acd7c128e9cf21af79`, Docker-only one-pass DP, worker=1,
  workload `logreg`, profile `lan`, attempt 1.
- **재현 절차**:
  - campaign output: `/home/mchoi/g007-one-pass-8e3d57a-c149283-20260803-v2`
  - failed cell: `cells/031-3fa104ff0d8d`
  - log: `phases/cell-1/cold-docker-e2e/raw_coordinator.log`
  - `rg -n "_mVar12[789]|local-backed" <log>`
- **관측 증상**:
  - 30/336 성공 후 `workers=1|planner=DP|workload=logreg|profile=lan` cold phase에서
    `FED aggregate binary cannot repair local-backed federated input _mVar129`로 fail-fast 중단했다.
  - 계획은 `FED ba+* X ... -> _mVar127 FOUT`, `FED append _mVar127 zeros_N1 -> _mVar128 FOUT`,
    `FED uarmax _mVar128 -> _mVar129 FOUT`, `FED ba+* _mVar129 ones_1K1 -> ... FOUT` 순서다.
  - runtime fallback/partial-response 수용은 없었고 teardown 후 project Docker resource는 0개다.
- **원인 분석**:
  - worker=1의 완전한 federated object는 exact `FType.FULL`이다. 그러나
    `FType.FULL.isType(ROW)`와 `isType(COL)`이 둘 다 true이므로 `AppendFEDInstruction`의 기존
    축 판정은 FULL+local cbind를 axis-misaligned branch로 먼저 분류한다.
  - 그 branch는 local operand를 `FederationUtils.federateLocalData(...)`로 감싸고 원격 FULL map과
    bind해, planner가 요구한 resident FOUT 대신 `FederatedLocalData`가 섞인 map을 만든다.
  - `uarmax`는 그 잘못된 map을 그대로 복사하고, 다음 aggregate-binary의 fail-fast guard가 암묵적
    업로드/repair를 거부하면서 오류가 드러난다. 따라서 aggregate-unary output capability가 아니라
    선행 append의 worker=1 FULL 물리 실행 경로가 원인이다.
- **해결 요약**: 먼저 FULL+local forced-FOUT append가 정확히 한 원격 worker에서 실행되고 FULL map을
  유지해야 한다는 RED runtime regression을 추가했다. 이후 append에 명시적 single-worker FULL 실행
  경로를 추가해 local operand를 그 worker로 계획된 broadcast하고, 결과 map에는 원격 worker entry만
  남긴다. 이는 fallback이 아니라 planner가 이미 선택한 FED/FOUT의 누락된 runtime 지원이다.
- **수정 파일**:
  - `src/test/java/org/apache/sysds/test/component/federated/AppendFoutRuntimeTest.java`
  - `src/main/java/org/apache/sysds/runtime/instructions/fed/AppendFEDInstruction.java`
  - 이 문서.
- **검증**:
  - RED: `AppendFoutRuntimeTest`가 `expected:<1> but was:<2>`로 실패
    (`/tmp/g014_append_full_red_20260803.log`).
  - GREEN: FULL-left/local-right cbind, local-left/FULL-right cbind, FULL-left/local-right rbind 3개와
    인접 `AggregateBinaryFoutRuntimeTest` 9개, no-fallback aggregate-unary 1개가 모두 성공
    (`/tmp/g014_append_adjacent_green2_20260803.log`).
  - package 성공 및 JAR SHA-256
    `2dd636d96319e54022d9b2e85f522e1c0c4dab61f2b6d39f08f4ab448b0b4ba0`
    (`/tmp/g014_source_package_append_full_20260803.log`).
  - 새 immutable stage
    `/home/mchoi/g007-critical-validation-6356d7c-c149283-20260803-v1/g007-stage-934312eac55cd6b32c460c63ccf0f99cce836fa761f0f83ce1e6709c5d3e47cc`
    의 current resource contract 검증 성공.
  - 동일 실패 셀 Docker canary의 cold/warm가 모두 성공했다. warm `9.579s`, semantic oracle 통과,
    runtime scan clean, fallback=false, cold/warm runtime plan hash 동일, container restart 0,
    teardown resource 0이다. 검증 요약은
    `/home/mchoi/g007-canary-6356d7c-dp-logreg-w1-lan-v1/validated-summary.json`, 전체 실행 로그는
    `/tmp/g014_canary_dp_logreg_w1_lan_6356d7c_20260803.log`이다.
  - artifact가 바뀌었으므로 이전 30개는 진단 전용으로 봉인하고, 새 단일 stage만 사용하는 336-cell
    one-pass를 `/home/mchoi/g007-one-pass-6356d7c-c149283-20260803-v1`에서 시작했다.
- **잔여 이슈**: 동일 실패 셀은 해결됐지만 worker=1의 나머지 workload/planner 조합과 전체 336-cell
  검증은 아직 완료되지 않았다. 이 canary만으로 사용자 제기 4항목 전체 해결을 주장하지 않는다.
- **잠재 회귀 위험**: FULL을 일반 ROW/COL처럼 취급하는 다른 append 조합이나 BROADCAST append까지
  잘못 변경할 수 있다. exact map type, worker count, operand locality, FOUT flag를 테스트로 분리하고
  기존 ROW/COL 경로는 건드리지 않는다.
- **의사결정 근거**: planner 후보를 닫거나 runtime에서 local-backed input을 암묵적으로 고치지 않고,
  planner-selected single-worker FULL append를 런타임이 실제 원격 FOUT으로 실행하도록 지원한다.
- **적용 원칙/제약**: runtime fallback 금지, partial-response 채택 금지, 후보 임의 축소 금지,
  TRead/TWrite 및 recompile CP/FOUT 규칙 변경 없음.
