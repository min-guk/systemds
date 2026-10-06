# R60: 실험 준비 profiling 자동 연결

## 편집 전 계획

사용자 요청: 과거 R59 보정값 대신 실험 환경에서 측정한 profile을 사용하고 자동 연결을 기본값으로 한다. 추가 대규모 validation/실험은 요청하지 않았다.

1. 기존 CPU/memory/codec probe와 raw network 측정을 실제 실험 image/JAR/host/resource 조건으로 실행하는 공통 profile provider를 만든다. 과거 pinned Python runner와 legacy coefficient schema는 재사용하지 않는다.
2. 선택된 환경별로 profile을 저장하고 재사용한다. host/image/JAR/thread/resource/network/probe identity가 달라지면 재측정한다. workload/planner 선택은 profile cache identity에 넣지 않는다.
3. profile이 없으면 실험 준비 중 측정하고, 완료된 profile만 coordinator JVM 시작 전에 주입한다. 실패하면 명시적으로 중단하며 과거 R59/임의 상수로 되돌아가지 않는다.
4. fixed intercept는 추가하지 않는다. raw network와 isolated codec 측정을 분리하고 coordinator의 동시 링크 총량도 측정한다. RTT/2가 directional 실측이 아니라 대칭 latency 가정임을 profile에 명시한다.
5. 현재 matrix runner와 공유 driver/ML10/integrated 진입점을 연결한다. 오프라인 조건 생성은 측정 profile을 명시적으로 읽도록 하여 미측정 계수를 조용히 생산하지 않는다.
6. 기존 raw/frozen 결과와 dirty work, main target을 보존한다. source preimage는 `/grid/3/cofee-lm-sweep-mchoi-20260914/auto-cost-profile-r60-20261005/evidence`에 저장한다.

## 최소 검증 기준

- production 편집 전 새 계약 테스트로 기존 frozen-only 경로의 실패를 확인한다.
- synthetic probe 결과 → 유효 profile → 정확한 환경 재사용, stale/partial/invalid profile 거부, 실패 시 coordinator 미실행을 회귀로 확인한다.
- 실제 원격 호출 없이 command/env/lifecycle 순서를 테스트한다. Java probe 변경이 있으면 기존 R59 JAR로 compile/small local correctness만 확인한다. 전체 Java 회귀와 전체 campaign은 다시 돌리지 않는다.
- profiling 시간과 workload/compile/planning 시간을 분리해 기록한다. 저장소 문서와 최종 보고에 미실행 원격 경로의 검증 한계를 명시한다.

## 보존할 원칙과 위험

- oracle/privacy/TR-TW/feasibility/runtime fallback은 변경하지 않는다. 이 작업은 비용 계수의 공급 경로 변경이다.
- generic/legacy network 설정과 R59 상수 fallback을 새 경로에 만들지 않는다.
- environment identity 누락, GET 총 시간의 wire/codec 이중 계상, worker 동시 링크 미측정, profile 준비 트래픽의 workload 계측 오염, container cleanup 누락을 주요 회귀 위험으로 취급한다.
- 재사용한 codec probe가 표현하지 못하는 이질성/동시성은 측정 근거보다 강하게 주장하지 않는다.

## 상태

**구현 완료. 현재 campaign 경로에서 automatic profiling이 기본값이다.** 원격 profiling 및 workload 실험은 이번 변경 검증에서 실행하지 않았다.

## 외부 evaluation 의존성과 공개 상태

이 경로는 SystemDS 저장소만으로 독립 실행되지 않는다. 현재 스크립트는 sibling checkout인
`/home/mchoi/cofee-evaluation`을 사용하며, 최소한 다음 공개 커밋의 API와 probe source가 필요하다.

- `cofee-evaluation` 최소 커밋: `639496a6ab7f17b76d0ce0c2a24cf1b0eb669407`
  (`Publish current W1357 experiment workflow and two-feature STEP-LM`)
- 2026-10-06 재확인한 공개 기준: `origin/main` =
  `c9514557ba853bd1e5f131ccb463844cf9f37236`
- 필수 provider: `calibration/experiment_profile.py`,
  `calibration/experiment_profile_probe.py`, `calibration/java/BasicRateProbe.java`,
  `calibration/java/ProbeEnvironment.java`, `calibration/java/TransportProbe.java`
- 필수 driver API: `driver/run_multihost_campaign_network_quality_v2.py`의
  `prepare_cost_profile(...)`. 현재 공개 API는 `local_classpath`와
  `expected_native_blas` 인자를 지원한다.

위 파일은 모두 `cofee-evaluation`의 추적 파일이며 현재 `origin/main`과 동일하다. 따라서
R60 기본 campaign이 별도의 미공개 external 변경에 의존하지는 않는다. 단, sibling checkout이
없거나 위 커밋보다 오래됐거나 API가 달라지면 `--campaign`은 profile 준비 단계에서 실패한다.
이는 과거 상수로 조용히 되돌아가지 않는 fail-closed 계약이다. campaign manifest는 external
provider·probe·driver의 SHA-256을 identity에 포함하므로 external revision을 바꿀 때는 새 campaign
root를 사용해야 한다.


## 기본 실행 흐름

```text
실험 환경/빌드 identity 확인
  → 환경별 profile cache 조회
  → cache miss: 같은 image/JAR/resources/network의 전용 probe container에서 측정
  → probe container 정리 + 완전한 profile 원자적 저장
  → 모든 실험 JVM의 환경변수에 측정 계수 주입
  → 실제 worker/coordinator 시작
  → netem-before 및 workload 타이머 시작
```

- 현재 `run_LAN_docker.sh --campaign`이 연결하는 matrix runner, 공유 network-quality driver/ML10, integrated pilot에 적용한다. 별도 활성화 flag가 없는 기본 동작이다.
- workload/planner/cell 이름은 cache key가 아니다. 같은 환경의 workload·planner와 재개 실행은 같은 profile을 공유한다.
- 계수는 최적화 전에 고정한다. 이미 시작한 JVM의 static final 필드에 나중에 덮어쓰는 방식이 아니다.
- `cost-profile.json`, profile SHA, cache hit 여부와 preparation 시간을 attempt에 남긴다. matrix 결과의 `profiling_seconds`는 setup 안에 포함되는 별도 관측값이며 `process_seconds`/compile/planning/runtime과 섞지 않는다.
- 과거 R59 계수 JSON은 기록으로 보존하지만 신규 campaign의 비용 입력이나 dependency로 사용하지 않는다.
- 과거 frozen P5 증거 harness와 명시적 과거 transport-calibration lane은 자동으로 재작성하거나 실행하지 않는다.
- 새 source/profile provider에 대해서는 새 campaign root를 사용한다. 기존 결과 root의 identity 검사를 우회하지 않는다.

## 실패 처리와 운영 경계

- profile이 없다는 이유로 FLOPS/MEM/BW/codec 상수를 대신 주입하지 않는다.
- profiling 실패, 비정상 계수, 손상된 profile 또는 환경 mismatch는 실험 시작 전 오류다. 이는 runtime fallback이 아니라 실험 준비 단계의 필수 입력 검증이다.
- 원격 profiling을 이번 구현 검증에서 실행하지 않는다. 최초 실제 실행에서는 cache miss에 따른 준비 시간이 발생한다.
- main 실행용 target과 원격 배포는 이번 변경 범위가 아니다. 기존의 source/JAR 일치 검사도 유지한다.


## 최종 구현

### 측정 항목과 계수 전달

| 계수 | 측정 방법 | 비용 입력 |
|---|---|---|
| 일반 compute | 기존 BasicRateProbe의 sustained arithmetic | `SYSDS_FED_COST_FLOPS` |
| matmul compute | 실제 MatrixBlock matmul; output write 시간 분리 및 input-read floor 확인 | `SYSDS_FED_COST_AGGBINARY_FLOPS` |
| memory | 논리 read+write bytes를 세는 memory copy | `SYSDS_FED_COST_MEM_BW` |
| 방향별 latency | 각 worker echo RTT 중앙값 중 최댓값 / 2 | `NET_LATENCY_C2W/W2C` |
| worker 경로 bandwidth | 개별 방향 raw socket bulk 전송; 측정 RTT와 연결 준비 시간 분리 | `NET_BW_C2W/W2C` |
| coordinator cap | 선택한 모든 worker와 동시 raw bulk 전송 | `NET_BW_COORD_C2W/W2C` |
| 방향별 codec | 실제 SystemDS encoder+decoder의 socket 없는 처리 시간 | `NET_SERDES_BW_C2W/W2C` |

표의 `NET_*` 키는 모두 `SYSDS_FED_COST_` 접두사를 사용한다. compute는 operations/s, memory·bandwidth·codec은 MiB/s, latency는 초 단위다. 이전 R59 계수나 고정 FLOPS/MEM 값을 덧씌우지 않는다. 별도 fixed 항도 없다.

CPU/memory/matmul/codec은 coordinator와 선택 worker 각각을 측정하며 host별 준비 작업을 병렬화했다. 현재 모델의 공통 계수에는 site별 중앙값 중 보수적인 최솟값을 사용한다. BasicRateProbe는 CI용 quick 크기가 아니라 sustained 크기, warmup 3회·sample 3회를 쓴다. codec은 1/16/64 MiB, warmup 1회·sample 3회다. 측정 표본 수는 준비 프로토콜이지 비용식의 하드코딩 처리량 보정값이 아니다.

### cache와 오프라인 사용

- profile schema: `cofee-experiment-cost-profile/v1`.
- cache는 campaign root의 `cost-profiles/`에 저장한다. identity는 선택 host/IP/port, 실제 image ID, image reference, JAR SHA, classpath, JVM options, thread 수, container resources, native BLAS, network, probe source SHA를 포함한다.
- cache hit에서도 실제 image ID를 가볍게 확인하지만 kernel/codec/network 측정을 반복하지 않는다.
- per-key lock, 원자적 JSON 저장, profile digest를 사용한다. 손상된 cache를 조용히 삭제·대체하거나 과거 상수로 대체하지 않는다.
- 오프라인 condition generator는 `COFEE_COST_PROFILE`에 측정 JSON 파일 또는 `cost-profiles/` 디렉터리를 지정한다. 정확히 일치하는 하나만 선택하고 누락·모호한 선택은 실패한다. 디렉터리는 정렬된 profile 목록의 digest로 고정한다.

### 구현 중 발견한 문제와 해결

1. **실험 이미지에 javac 없음:** sealed Dockerfile은 `openjdk-17-jre-headless`를 사용한다. 컨테이너에서 컴파일하는 초기 구현을 버리고 controller에서 정확한 JAR에 대해 probe 3개를 컴파일한 뒤 class만 전달하도록 수정했다. 이미지나 의존성을 추가하지 않았다.
2. **controller/remote 경로 차이:** matrix의 remote overlay는 controller의 local overlay와 다르다. 명시적 `local_classpath`를 전달하고 local JAR SHA를 검증한다. 이 경로를 shared filesystem이라고 가정하지 않는다.
3. **partial startup cleanup:** 시작 도중 실패해도 해당 고유 ID의 probe container를 각각 정리한다. 다른 cache root의 측정을 지우지 않도록 probe ID에 고유 nonce를 쓴다.
4. **raw bandwidth의 latency 중복:** TCP 연결을 측정 시작 전에 준비하고 bulk 시간에서 측정 RTT를 분리했다. 비양수 잔차를 1ns로 대체하지 않고 실패 처리한다.
5. **동시 전송 실패 누락:** 일부 thread/link의 실패를 capacity 표본으로 받아들이지 않는다. 열린 socket도 실패 경로에서 닫는다.

## 최종 검증 결과

| 범위 | 결과 | 로그 |
|---|---|---|
| main campaign/profile/condition focused | 42 PASS | `final-main-focused.log` |
| provider/driver/integrated focused | 21 PASS | `final-external-focused.log` |
| 기존 Java probe 3개와 R59 JAR API 호환 compile | PASS; 기존 deprecated API 알림 있음 | `probe-javac.log` |
| 변경 Python syntax·whitespace | PASS | `completion-receipt.json` |

총 **63개 focused 테스트**가 통과했다. provider 전체 측정 조립은 synthetic CPU/codec/network 출력으로 확인했으며, cache miss/hit, invalid/corrupt profile, selection mismatch, directory ambiguity, 실패 시 실제 worker/coordinator 미시작, partial-start cleanup, JVM 이전 계수 주입을 포함한다. 이를 실제 원격 측정 성공으로 해석하면 안 된다.

Java 전체 회귀, 원격 profiling, 실제 workload 및 추가 성능 validation은 실행하지 않았다. main target과 원격 JAR 배포도 변경하지 않았고 commit/push는 하지 않았다.

## 변경 파일과 증거

- main 9파일: `network_cost_profile.py`, `derive_sliceline_conditions.py`, `freeze_campaign_conditions.py`, `run_matrix_campaign.py`와 관련 test 5파일.
- 외부 evaluation 9파일: `calibration/experiment_profile.py`, `calibration/experiment_profile_probe.py`, provider test, driver, ML10/integrated caller와 관련 test 3파일.
- 기존 Java probe source는 변경하지 않고 재사용했다. 상세 전후 SHA와 파일 목록은 다음 경로의 `completion-receipt.json`에 기록했다.

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/auto-cost-profile-r60-20261005/evidence/
```

## 잔여 한계와 회귀 감지

- 실제 Docker/multi-host profiling을 이번에 실행하지 않았으므로 최초 실제 실행이 운영 환경 확인이다. 실패 시 실패 사실을 남기며 workload를 시작하지 않는다.
- one-way latency는 독립적인 양방향 timestamp 측정이 아니라 **대칭 경로에서 RTT/2라는 명시적 가정**이다.
- raw bandwidth는 애플리케이션 payload 기준 유효 처리율이다. physical frame 수준의 정확한 NIC 성능이라고 주장하지 않는다.
- codec은 dense FP64의 단일 encode+decode 결합 처리율이며 모든 이질 endpoint 조합·동시 codec 실행을 완전히 모델링하지 않는다. endpoint 배수를 다시 곱하거나 RPC 총 시간으로 codec을 중복 적합하지 않는다.
- 공통 계수 모델에 맞춰 site별 최솟값을 사용하는 것이며 site별 서로 다른 비용식을 새로 만든 것은 아니다. profile을 쓴다고 모든 workload에서 최적의 runtime을 보장하지 않는다.
- 환경 변경은 cache identity 및 profile digest 회귀로, profile 준비 실패는 coordinator 미시작/cleanup 회귀로 감지한다. 기존 GLM fixture 문제 및 planning 20초 목표는 이번 변경의 해결 주장에 포함하지 않는다.
- historical P5와 명시적인 compile-model microbench 가정은 별도 경로로 남긴다. 현재 production campaign default와 혼동하지 않는다.
