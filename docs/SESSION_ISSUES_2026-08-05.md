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
