# Session issues — 2026-08-05

## 1. ALS MinST worker=1의 합법적인 idle-worker 증거를 harness가 실패로 오판함

- **상태**: 해결 — harness 수정 및 실제 보존 artifact 재검증 통과
- **환경/조건**:
  - campaign: `/home/mchoi/g014-one-pass-results-96858a1-cac3730-20260804-v2`
  - cell: `WAN-light/ALS/MinST/worker=1` (`cells/089-ba137724f34d`)
  - source/JAR/stage: `96858a1...` / `7d812e31...` / `268670c...`
- **재현 절차**:
  - `cells/089-ba137724f34d/runner.stderr.log`에서
    `ValueError: instruction CSV has no instruction rows`를 확인한다.
  - 실제 CSV는 frozen stage의
    `results/workers/stats/worker1-8001_d5d5597bf418b9e6d7b51267b0ca21a0_wan_light.csv`다.
- **관측 증상**:
  - cold `125.995 s`, warm `125.654 s` 실행과 semantic oracle/runtime scan/fallback/teardown은 성공했다.
  - coordinator/CP-dominant 계획이라 worker instruction row가 합법적으로 0개였으나 harness가 이를 실패 처리했고,
    campaign은 88/336에서 중단됐다.
- **원인 분석**:
  - planner/runtime 결함이 아니라 evidence collector가 schema-valid header-only statistics stream과 손상된 빈
    evidence를 구분하지 못했다.
- **해결 요약**:
  - header/path/hash/runtime receipt 인증은 유지하고 row 0개를 명시적 idle-worker evidence로 표현했다.
  - 0-byte/malformed CSV와 nonempty row의 빈 opcode는 계속 거부한다.
- **수정 파일**:
  - harness `experiments/tools/worker_evidence.py`
  - harness `experiments/tests/test_worker_evidence.py`
  - harness/source의 `docs/SESSION_ISSUES_2026-08-05.md`
- **검증**:
  - harness focused 4/4, 전체 147/147 통과, `py_compile` 통과, basedpyright 0 errors.
  - 실제 보존 run을 수정된 collector/validator로 읽어 row 0/opcodes `[]` 및 payload SHA-256
    `90f61d738eb2d48038fea7fc359e6e1c7b06207f88eef6c8a7eec0f8ea0d947f`로 인증했다.
- **잔여 이슈**:
  - v2는 재개/재시도/결과 stitching하지 않는다. issue 13의 MinST LogReg objective 이상을 해결하고 새 immutable
    stage에서 ALS idle-worker canary와 LogReg canary를 거친 뒤 fresh 336을 시작한다.
- **잠재 회귀 위험**:
  - malformed file 허용 위험은 nonempty/schema/opcode tests로 감지하며 file SHA와 receipt binding은 유지된다.
- **의사결정 근거**: planner 후보나 runtime을 바꾸지 않고 실제 합법 실행을 evidence layer가 표현하게 했다.
- **적용 원칙/제약**: runtime fallback 금지, Docker-only, exactly-once, 실패 campaign stitching 금지.

## 2. MinST LogReg가 worker 수 증가 시 동일 FED 연산의 고정 RTT/control 비용을 worker마다 중복 계산함

- **상태**: 진행중 — 공유 비용 모델 및 MinST exact input fact 수정, source 회귀 검증 통과; Docker runtime canary 대기
- **환경/조건**:
  - 보존 campaign: `/home/mchoi/g014-one-pass-results-96858a1-cac3730-20260804-v2`
  - profile/workload: `WAN-light/LogReg`, workers `1..4`, planners `DP/MinST`
  - source 기준: `96858a1...`에서 관측, 현재 working tree에서 수정
- **재현 절차**:
  - 보존 LogReg planner log에서 반복 feature matrix-multiply 소비자의 placement를 비교한다.
  - 기존 MinST는 worker=1에서 `FED/LOUT`, worker=2..4에서 `CP/LOUT`을 선택했다.
  - `CampaignBG014MinStLogRegParallelDispatchCostRedTest`로 동일 campaign-shaped CLI compile을 workers 1..4에 실행한다.
- **관측 증상**:
  - worker가 늘수록 FED 후보에 약 `26.7s -> 38.7s -> 50.7s`, `37.5s -> 62.2s -> 87.0s`처럼
    worker-linear 고정비가 추가되어 MinST가 반복 LogReg core를 coordinator CP로 내렸다.
  - 보존 실행시간에서도 MinST가 DP보다 느렸으며, 이는 MinST solver의 global optimization 실패가 아니라 입력된
    objective의 물리 비용 왜곡이었다.
- **원인 분석**:
  - `FederationMap.execute`는 모든 worker request future를 먼저 제출하므로 한 논리 FED instruction의 고정 RTT/control은
    worker 수가 아니라 한 병렬 dispatch stage다.
  - `AggregateBinaryFEDInstruction.aggregateLocally`도 compute/GET_VAR/cleanup을 같은 request batch에 넣는다.
  - 기존 공유 비용 모델은 FED unary, sliced/full input preparation, partial result GET, cleanup, forwarding에서 고정 stage를
    worker 수만큼 또는 같은 instruction 안에서 중복 청구했다.
- **해결 요약**:
  - 모든 non-Data FED instruction에 한 번의 latency fallback을 두되 worker 수로 곱하지 않는다. 명시적 control calibration이
    있으면 기존 coordination term이 그 한 stage를 소유한다.
  - FED instruction request에 포함되는 input upload/result download/cleanup은 payload·serdes만 계산한다.
  - standalone parallel download도 고정 stage를 한 번만 계산한다.
  - base CP→FOUT upload가 한 dispatch를 이미 소유하므로 별도 local-to-FED forwarding fixed penalty를 0으로 만들었다.
  - 후보를 닫거나 runtime fallback을 추가하지 않았고 DP/MinST가 공유하는 물리 비용만 수정했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014MinStLogRegParallelDispatchCostRedTest.java`
  - DP/MinST cost/parity 관련 회귀 테스트들
- **검증**:
  - RED 단계에서 heavy AggBinary FED unary, forwarding, parallel download가 각각 `0 vs 7ms`, `3ms vs 0`,
    worker별 fixed-stage 차이로 실패하는 것을 확인했다.
  - 수정 후 `FederatedCostModelFallbackTest` 41/41, `FederatedPlanMinSTHyperedgeTest` 34 pass + 3 skip,
    LogReg campaign-shaped workers 1..4 1/1, MinST physical oracle 9/9, physical model certificate 8/8 통과.
  - DP upload projection/selector/NaN owner proof는 총 12 tests 중 9 pass + 3 skip, occurrence demand 9/9,
    BR10 membership authority 7 pass + 9 skip, 관련 integration 5/5 통과.
  - 수정 후 LogReg 반복 feature-matmul 소비자는 worker=1 `FED/LOUT/FULL`, worker=2..4 `FED/LOUT/ROW`을 유지한다.
- **잔여 이슈**:
  - 새 immutable stage에서 WAN-light Docker LogReg DP/MinST workers 1..4 runtime canary와 KMeans/L2SVM 영향 canary가 필요하다.
  - `MinStExactProductionTractabilityCertificateTest#sevenCampaignWorkloadsPublishExactPhysicalTractabilityCertificate`의
    MinST 부분은 진행되었으나, 내부 baseline DP가 기존 `collectConflictsSingleBFS`/multi-write normalization에서 4분 이상
    고비용 상태가 되어 해당 전체 메서드는 중단했다. 소형 exact parity 두 메서드는 통과했다.
- **잠재 회귀 위험**:
  - 실제 runtime이 별도 RPC batch를 만드는 연산까지 in-band로 오분류하면 비용을 과소평가할 수 있다. Docker canary의
    opcode/plan/runtime과 worker scaling으로 감지하고, 별도 batch가 증명되면 그 경계에만 한 fixed stage를 복원한다.
  - 공유 모델 변경이 DP 선택에도 영향을 줄 수 있으므로 DP canary를 MinST와 같은 입력·seed·Docker profile로 비교한다.
- **의사결정 근거**: runtime futures/batch 구조와 exact factor decomposition으로 증명된 비용 모델 오류를 수정했다.
- **적용 원칙/제약**: 후보군 임의 축소 금지, 비용/메모리 측정 우선 수정, runtime fallback 금지, Docker-only.

## 3. MinST StepLM runtime recompile에서 여러 logical consumer가 하나의 physical REFED edge로 융합됨

- **상태**: 진행중 — 정확한 RED→GREEN 및 관련 소스 회귀 통과; 동일 실패 셀 Docker canary 대기
- **환경/조건**:
  - 실패 campaign: `/home/mchoi/g014-one-pass-results-f9a307b-a32b188-20260805-v1`
  - 실패 cell: `WAN-light/StepLM/MinST/worker=3`, canonical order index `97`(98번째 요청)
  - 실패 cell directory: `cells/098-637a87a8cad4`
  - source/JAR stage: commit `f9a307b87a6db57d5694945a166d0ca3c7fb271f`,
    `/home/mchoi/g014-fixed-canary-f9a307b-a32b188-20260805-v1/g007-stage-5576aae75bf5dddc1470a68130dcabbdcb4f7380970732350fe23ea1accdf0a9`
  - Docker-only, private-aggregate, seed `2026072701`, attempt `1`, retry 없음
- **재현 절차**:
  - 보존 실패 로그:
    `/home/mchoi/g014-fixed-canary-f9a307b-a32b188-20260805-v1/g007-stage-5576aae75bf5dddc1470a68130dcabbdcb4f7380970732350fe23ea1accdf0a9/results/fed3/mkl-min-st-cut/steplm_dataset-P2P2D_coordinator_mkl-min-st-cut_76f1005e7a13bb8fa39a0bc2ee325591_wan_light_coordinator1.log`
  - 소스 RED:
    `mvn -q -Dcheckstyle.skip -Drat.skip=true -Dtest=org.apache.sysds.test.component.federated.FederatedDagExactRefedInputProjectionTest test`
  - RED 로그: `/tmp/g014-minst-steplm-shared-physical-refed-red-20260805.log`
- **관측 증상**:
  - 이전 scanner false-positive를 수정한 새 campaign에서 97번째 Heuristic/StepLM 셀은 cold/warm semantic,
    scan, restart, teardown을 모두 통과했다.
  - 다음 MinST 셀은 함수 본문 runtime recompile 중 실제
    `LopsException: fed_refed lowering mapped multiple selected logical consumers to one physical consumer hop=1539 for local hop=1541`
    로 중단됐다. FED 실행은 `fedinit` 외에는 시작되지 않았다.
  - campaign은 성공 row `97/336`, failure `1`로 동결됐으며 실패 셀은 결과 row로 채택하지 않는다.
- **원인 분석**:
  - planner는 동일 source와 동일 anchor/FType 권한에 속하는 여러 exact logical consumer edge를 선택했다.
  - StepLM 함수 recompile의 Lop fusion은 그 logical consumer들을 하나의 동일 physical consumer/input edge로 합쳤다.
  - 기존 `Dag.resolveSelectedRefedConsumers`는 physical consumer identity가 한 번이라도 중복되면 입력 위치가 완전히
    동일한지와 관계없이 실패시켰다. 따라서 하나의 physical edge를 한 번 rewiring하면 모든 선택된 logical use를
    충족하는 합법적인 many-to-one projection도 거부했다.
- **해결 요약**:
  - 한 authority 안에서 logical consumer별 projection을 physical consumer identity와 exact input-position 목록으로 기록한다.
  - 여러 logical consumer가 같은 physical consumer의 **동일한 exact input-position 목록**으로 융합된 경우에만 하나의
    physical edge로 canonicalize하고 한 번 rewiring한다.
  - 같은 physical consumer라도 input-position projection이 다르면 계속 fail-closed한다.
  - 서로 다른 anchor/FType authority가 같은 physical input을 소유하려는 경우는 기존
    `validateDistinctRefedInputOwnership`가 lowering mutation 전에 계속 거부한다.
  - 후보군, 비용 모델, planner 선택, runtime 실행/fallback은 변경하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/lops/compile/Dag.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedDagExactRefedInputProjectionTest.java`
  - `docs/SESSION_ISSUES_2026-08-05.md`
- **검증**:
  - 새 회귀는 수정 전 실제 예외와 같은 `mapped multiple selected logical consumers to one physical consumer`로 RED였다.
  - 수정 후 exact projection test `4/4` GREEN:
    `/tmp/g014-minst-steplm-shared-physical-refed-green-20260805.log`.
  - Dag projection, DP fused registry slot, placement transaction, MinST StepLM Docker-shape compile,
    FedAll StepLM runtime recompile, REFED policy 묶음 GREEN:
    `/tmp/g014-minst-steplm-refed-lowering-bundle-20260805.log`, 총 `86/86` GREEN.
    FedAll runtime regression은 함수 recompile `1`, statement-block recompile `8`, `fed_fed_refed=2`를 실제 실행했다.
  - checkstyle/RAT 포함 package GREEN:
    `/tmp/g014-minst-steplm-refed-lowering-package-20260805.log`, return code `0`.
  - package JAR SHA-256: `5423f2ec774d58a95c2a4fd02691fd07503a895d198466a21d04a79e6b694aa7`.
- **잔여 이슈**:
  - 새 immutable JAR/stage 생성이 필요하다.
  - 정확히 실패했던 `WAN-light/StepLM/MinST/worker=3`를 새 stage의 Docker canary로 먼저 통과시켜야 한다.
  - 사용자 지시에 따라 기존 성공 97셀은 재실행하지 않고, canary 성공 후 미실행 셀만 별도 continuation manifest에서
    수행한다. 최종 분석은 cell별 source/JAR provenance를 유지한다.
- **잠재 회귀 위험**:
  - 실제로 서로 다른 physical input occurrence를 잘못 동일시하면 선택되지 않은 edge를 rewiring할 수 있다.
  - 감지 방법: 동일 exact input-position만 deduplicate하는 새 회귀, duplicate-source subset fail-closed 회귀,
    cross-authority physical-input ownership 검증 및 Docker instruction/oracle scan을 함께 실행한다.
- **의사결정 근거**: planner-selected logical authorities를 변경하지 않고 Lop fusion의 증명 가능한 identical physical
  edge만 canonicalize했다.
- **적용 원칙/제약**: runtime fallback 금지, planner-owned exact edge authority, 후보군 임의 축소 금지,
  Docker-only, TRead/TWrite 및 recompile placement 제약 유지.
