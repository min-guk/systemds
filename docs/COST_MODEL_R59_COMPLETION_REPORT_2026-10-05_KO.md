# R59 비용 모델 통일 및 Legacy Network 제거 완료 보고서

> 후속 변경: R59의 저장된 보정값 자동 적용은 R60에서 제거했다. 현재 기본 동작은 [실험 환경 profiling 자동 연결](COST_AUTOMATIC_EXPERIMENT_PROFILE_2026-10-05_KO.md)이다. 아래 수치와 결과는 R59 완료 시점의 기록이다.

- 작성일: 2026-10-05
- 대상 저장소: `/home/mchoi/w1357-paper-aligned-refactor`
- 연동 저장소: `/home/mchoi/cofee-evaluation`
- 기준: R59 구현 완료 시점의 소스 반영 기록과 이미 확보한 검증 로그
- 상태: **구현·로컬 소스 반영 완료. 추가 validation 종료. 커밋·푸시·실험 배포는 미실행.**

## 1. 요약

이번 작업은 비용 모델의 중복 경로와 예외를 줄이고, 논문에서 설명한 **one-way latency + bandwidth 시간 + codec 시간**을 공통 네트워크 비용으로 사용하는 데 목적이 있다.

R58에서 도입한 세 항 구조를 유지하면서, R59에서는 다음 네 가지를 완료했다.

| 항목 | 이전 문제 | 반영 결과 |
|---|---|---|
| Legacy network | RTT 변환·generic throughput fallback·무효 penalty API·과거 설정 호환이 남음 | 해당 비용 모델/API/설정 호환 경로 삭제 |
| Shared coordinator NIC | worker별 최대 전송량만으로는 coordinator에 모이는 총량 병목을 표현하지 못함 | worker 경로와 coordinator 총량의 병목 시간을 공통 primitive에 반영 |
| 함수 GET 재사용 | 함수 formal과 일반 consumer가 같은 실제 값을 읽어도 GET이 중복 과금됨 | 증명된 source/version/context/lifetime에 한해 기존 activation-OR collector로 통합 |
| 처리량 보정 및 설정 연결 | 과거 affine fit과 미적용 계수, 실제 환경과 profile 연결 문제가 남음 | intercept 없는 오프라인 보정, 개선된 계수만 채택, 선택 host·worker 수·image에 적용 범위 제한 |

**확보한 검증은 Java 498개와 Python focused 46개 통과다.** 별도 frequency suite의 기존 실패 1건은 남아 있다. 마지막 설정 호출부 수정 후 전체 integrated suite를 다시 실행하지는 않았다.

따라서 완료의 의미는 **요청한 구현과 로컬 반영의 완료**이지, 모든 workload의 runtime 개선이나 compile+planning 20초 달성의 입증이 아니다.

## 2. 변경 범위와 유지한 원칙

### 변경한 것

- 비용 수량과 공통 network primitive.
- 함수 경계 occurrence facts 및 retained-copy 생성 횟수 계산.
- 오프라인 calibration 도구, frozen profile, campaign 설정 생성·전달 경로.
- 위 변경을 검증하는 회귀 테스트.

### 변경하지 않은 것

- oracle의 runtime 지원 범위, privacy 제약, 합법적인 후보 공간.
- TRead/TWrite 배치 일치성 및 recompile 제약.
- runtime protocol, 암묵적 fallback, 실제 연산의 반복 횟수.
- 과거 실험 raw/result/receipt 및 기존 실행용 `target` symlink.

**의사결정 원칙:** 불리한 계획을 임의로 금지하는 대신, 계획을 비교하는 비용과 재사용 횟수를 수정했다. network legacy 제거를 이유로 unknown shape의 bounds나 불확실한 alias의 보수적 과금까지 제거하지 않았다.

## 3. 네트워크 비용 통일

### 3.1 최종 산식

한 방향의 실제 전송 stage에 대해 다음 구조를 사용한다.

```text
network_cost_ms = 1000 × (
    one_way_latency_seconds
    + max(largest_worker_MiB / worker_leg_MiB_per_sec,
          total_MiB / coordinator_MiB_per_sec)
    + codec_MiB / codec_MiB_per_sec
)
```

- coordinator cap이 미지정이면 그 cap의 항은 적용하지 않는다.
- codec rate가 비활성화된 설정이면 codec 항을 적용하지 않는다.
- 실제 존재하는 0-byte stage에는 latency가 남는다. 존재하지 않는 stage를 새로 만들지는 않는다.
- request와 response는 각각의 방향을 따르며, payload마다 latency를 중복 부여하지 않는다.
- 별도 fixed/control 가산항과 자유 intercept는 없다.

핵심은 worker 수가 늘어도 coordinator NIC를 통과하는 총량은 사라지지 않는다는 점이다. worker별 전송 시간이 감소하더라도 총량/cap이 더 크면 그것이 wire 시간을 결정한다.

### 3.2 비용 소유권

- operator에 내재된 준비·결과 반환 통신은 operator cost가 소유한다.
- 명시적 GET/PUT/materialization은 explicit movement cost가 소유한다.
- 두 경로는 같은 network primitive를 호출한다.
- 재사용은 단가를 임의로 할인하는 방식이 아니라 **생성 횟수**를 줄이는 방식으로 반영한다.

따라서 공통 함수를 사용한다는 이유로 같은 전송을 operator와 movement에 동시에 더하지 않는다. 기존 embedded preparation의 소유권도 유지한다.

### 3.3 전송량과 삭제 대상

balanced PUT에서 논리 데이터 크기를 `D`, worker 수를 `W`라고 하면 다음과 같다.

| 배치 | largest worker bytes | total bytes |
|---|---:|---:|
| BROADCAST | D | D × W |
| ROW/COL 분할 | D / W | D |
| 유효한 FULL W1 map | D | D |

삭제한 대표 경로는 generic `SYSDS_FED_COST_NET_BW`, `SYSDS_FED_COST_NET_SERDES_BW` fallback, legacy RTT `SYSDS_FED_COST_NET_LATENCY` 변환, retired control/GET 옵션 처리, `computeNetworkCost(double)` 및 무효 penalty API다.

방향별 one-way latency와 throughput을 사용한다. 과거 설정을 신규 frozen 조건으로 자동 승격하는 호환 분기도 제거했다. 기존 실험 자료 자체를 삭제한 것은 아니다.

## 4. 함수 경계 GET 중복 과금 해결

### 문제와 원인

기존 함수 경계 GET은 `callWeight × unit cost`의 별도 경로여서 일반 retained GET과 같은 생성 사건으로 합쳐지지 않았다. 첫 R59 구현에서도 직접 생성된 actual과 TWrite/TRead를 거친 일반 consumer의 source identity가 달라 중복이 남았다.

새 회귀에서 확인한 사례는 다음과 같다.

- 같은 actual을 두 formal에 전달: R58에서 GET 두 번 과금.
- 같은 actual을 함수 formal과 일반 consumer가 사용: 첫 R59 candidate에서 GET 두 번 과금.
- 동일 값과 유효한 retained copy를 공유하는 경우 기대값은 한 번이다.

### 해결

1. caller/callee occurrence ancestry를 immutable fact로 보존했다.
2. source/version/context/availability가 증명된 함수 GET을 기존 `DownloadKey` collector에 넣었다.
3. 실제 runtime `cpvar`가 같은 Data 객체를 전달하는 단일 입력 TWrite/TRead 경로의 provenance를 정규화했다.
4. 선택된 수요를 activation OR로 결합해 한 availability occurrence 안에서는 한 번만 생성 비용을 냈다.
5. 함수 입력을 source별로 index화해 producer마다 모든 함수 입력을 반복 검색하는 비용도 줄였다.

| 상황 | 과금 원칙 |
|---|---|
| 같은 actual의 두 formal 및 일반 consumer | 공유 증명이 있으면 한 creation |
| loop 밖에서 한 번 생성되고 copy가 유지되는 source | 반복 소비와 별개로 한 creation |
| loop 안에서 매번 재생성되는 source | 생성 횟수만큼 과금 |
| 서로 다른 source/version | 별도 과금 |
| fresh/relocated alias 또는 불완전한 provenance | 일반 retained lifetime을 임의로 빌리지 않고 보수적 과금 |

sparse/NNZ 및 unknown-shape 크기 추정 계약은 유지했다. 함수 이름이나 값의 크기가 같다는 이유만으로 합치지 않는다.

## 5. 오프라인 calibration 결과

### 방법

- 새 실험 없이 기존 R56 완료 raw **340개**만 사용했다.
- 사용 범위: LAN W1/W3, WAN-Light W1. 미완료 WAN-Light W3는 제외했다.
- 4/16 MiB로 적합하고 64 MiB로 held-out 평가했다. 0.0625 MiB는 진단용으로 구분했다.
- 공통 latency/wire 시간을 제외한 잔차에 방향별 비음수 payload 계수를 적합했다.
- fixed intercept는 0이다. worker 수별·opcode별 보정 상수는 만들지 않았다.
- production이 알 수 있는 logical/statistical bytes를 사용했다. observed frame bytes는 진단 자료이며 미래 관측값을 비용식에 넣지 않았다.

### 채택 결과

아래 오차는 **동일한 새 wire 모델에서 기존 계수와 후보 계수를 비교한 held-out 중앙 상대오차**다. 이전 전체 모델과의 workload runtime 비교가 아니다.

| 환경 | 방향 | 기존 계수 오차 | 후보 계수 오차 | 최종 codec MiB/s | 판단 |
|---|---|---:|---:|---:|---|
| LAN | W2C | 453.65% | 21.10% | 401.881358 | 후보 채택 |
| WAN-Light | W2C | 339.99% | 24.61% | 218.590544 | 후보 채택 |
| LAN | C2W | 21.46% | 22.76% | 210.000000 | 기존 유지 |
| WAN-Light | C2W | 29.34% | 31.09% | 210.000000 | 기존 유지 |

이는 모델에서 사용하는 **유효 endpoint 처리량 계수**이며 순수 serializer만의 독립 실측 성능으로 해석하지 않는다.

### 적용 방식과 환경 제한

매 실행마다 profiling하지 않는다. frozen profile을 읽어 optimization 전에 계수를 고정한다. 또한 JVM 전역 기본 상수를 모든 환경에 맞는 실측값으로 교체한 것이 아니다.

측정 profile은 다음 조건이 맞는 경우에만 적용한다.

- 실제 선택 coordinator 및 worker host에 archived link 증거가 있음.
- 선택 worker 수가 측정 coverage에 포함됨.
- network tuple과 실제 Docker image가 맞음.

미측정 host가 포함된 ML10 W5, base W5/W7 등은 `configured-not-calibrated`로 표시하고 baseline codec을 유지한다. unknown coordinator RX는 `0/unspecified`로 전달한다. 전체 topology 파일 SHA가 다르다는 이유만으로 같은 선택 host를 거부하던 문제는 제거했다.

## 6. 검증 결과와 확인 범위

아래는 **이미 실행한 검증의 기록**이다. 이 보고서 작성 중 테스트·빌드·실험을 추가 실행하지 않았다.

| 검증 | 결과 | 증거 파일 |
|---|---|---|
| 격리 Maven package | BUILD SUCCESS, 38.937초 | `maven-package.log` |
| 최종 JAR 비용 회귀 | 310 PASS | `final-cost-tests.log` |
| 최종 JAR 인접 회귀 | 188 PASS, 기존 ignore 2개 | `final-adjacent-tests.log` |
| 변경 production 3파일 lint | 진단 0 | `final-javac-lint.log` |
| Python main focused | 42 PASS | `calibration-wiring-reviewfix2.log` |
| 외부 topology/profile focused | 4 PASS | `calibration-external-reviewfix2.log` |
| 마지막 driver/ML10/integrated caller 수정 | syntax compile 성공 | `r59-calibration-verification.json` |
| 별도 frequency suite | 17개 중 16 PASS, 1 FAIL | `final-frequency-tests.log` |

Maven package 시간은 Java 프로젝트 빌드 시간이며, DML compile+planning 시간이 아니다.

### 알려진 실패와 검증 공백

- 남은 실패는 `OccurrenceExecutionFrequencyFactsConstantBranchTest.actualBuiltinGlmDeadStraightenXIsZeroAndCgRemainsPositive`의 `straightenX Gram occurrence must be retained` assertion이다.
- R58 baseline에서도 같은 실패가 확인됐다. 이번 변경으로 새로 발생했다고 볼 근거는 없지만, 실패 자체를 해결한 것은 아니며 상세 원인 확정도 완료 범위가 아니다.
- source identity 정규화에 따른 native-local-anchor receipt golden을 갱신했다. 해당 테스트의 plan structure 및 raw contribution bits digest는 기존 값과 같았다.
- 독립 검토에서 발견한 환경/profile 연결 문제는 수정했다. 최종 설정 수정 후 **broad integrated suite 재실행과 reviewer 재승인**은 사용자 요청에 따라 생략했다.
- fresh REFED 음성 사례 전체를 이번에 새 전용 테스트로 완전히 입증했다고 주장하지 않는다. 보수적 제외 조건과 기존 인접 회귀를 유지했다.

## 7. 남은 한계와 잠재 회귀 위험

| 항목 | 현재 한계/위험 | 확보된 근거 또는 후속 확인점 |
|---|---|---|
| 네트워크 구조 | 동종 worker 경로와 coordinator cap의 star 모델; 임의의 이질 경로 전체는 미모델링 | W1/W3/W7, coordinator/worker cap, balanced mixed PUT 회귀 |
| PUT 분할 | balanced 분할 근사; 임의의 skew까지 정확하다는 보장 없음 | 실제 skew 환경의 정확도는 별도 문제로 남김 |
| copy identity | 잘못 합치면 과소과금, 지나치게 분리하면 과대과금 | 동일 actual의 전체 선택 mask, distinct actual, invariant/recreated source 회귀 |
| 작은 payload | 별도 fixed bookkeeping 항을 없앴으므로 잔차가 남을 수 있음 | small-payload 진단 자료 보존; 숨은 intercept 추가 안 함 |
| calibration 일반화 | 미측정 host·image·worker 수에 대한 정확도 미확인 | 실제 환경 일치 조건, 미일치 시 configured-not-calibrated |
| 설정 통합 | 마지막 caller 수정의 전체 campaign 실행은 미확인 | focused 테스트와 syntax compile까지만 확보 |
| 계획 및 runtime | 비용 변화로 선택 계획이 달라질 수 있으나 실 workload 개선은 미측정 | 새 버전 runtime 실험 미실행; 20초 목표 달성 주장 안 함 |

추가 validation과 실험은 자동 재개하지 않는다. 위 항목은 완료를 부풀리지 않기 위한 한계 기록이며, 사용자에게 즉시 추가 검증을 요구하는 목록이 아니다.

## 8. 수정 파일과 산출물

### 8.1 주요 production 파일

| 파일 | 역할 |
|---|---|
| `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java` | 공통 network 식, 방향별 설정, shared NIC, legacy API 제거 |
| `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java` | 함수·일반 GET collector 통합, source identity 정규화 |
| `src/main/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFacts.java` | 함수 경계 occurrence ancestry 보존 |
| `scripts/fedplanner/calibration/transport_profile.py` | intercept 없는 오프라인 적합 및 채택 |
| `scripts/fedplanner/network_cost_profile.py` | 환경에 따른 frozen profile 연결 |
| `scripts/fedplanner/calibration/profiles/r59-docker-transport-cost-profile.json` | 계수·환경·근거를 고정한 profile |
| `scripts/fedplanner/transport_calibration.py` | 과거 affine fit 경로 교체 |

main에는 production Java 3개, Java test 9개, Python/config/test 13개로 **source/script 총 25파일**을 반영했다. generator·campaign 호출부도 포함하며, 상세 경로와 전후 SHA는 `main-sync-proof.json`에 있다.

외부 저장소에는 다음 **6파일**을 반영했다.

```text
campaign/run_ml10_campaign.py
campaign/run_w1357_integrated.py
driver/run_multihost_campaign_network_quality_v2.py
driver/transport-cost-profile-r59.json
tests/test_network_cost_environment.py
tests/test_w1357_integrated.py
```

### 8.2 반영 상태와 재현 자료

- 기존 dirty work를 보존하고, preimage와 SHA guard로 작업 대상만 복사했다.
- 반영 기준 HEAD: `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592`. 이번 작업의 새 commit hash가 아니다.
- **커밋·origin/main push는 하지 않았다.**
- **기존 실행용 main `target`은 R37 경로 그대로다.** 소스 반영만으로 새 JAR가 실험에 자동 배포되지는 않았다.
- 새 JAR는 격리 경로에서 생성·검증했으며 원격 workload에 배포해 실행하지 않았다.

최종 JAR:

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/cost-completion-r59-20261005/source/target/systemds-3.4.0-SNAPSHOT.jar
SHA256: f2d266d0833bd2489d8e3d2b40e54d0dfa251f1f745d59a20ba1f8baa59adb81
```

증거 디렉터리:

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/cost-completion-r59-20261005/evidence/
```

주요 기록은 `completion-receipt.json`, `main-sync-proof.json`, `external-sync-proof.json`, `build-jar-proof.json`, `r59-calibration-verification.json`이다. 실패 재현과 수정 전후 로그도 같은 디렉터리에 보존했다. 기존 테스트 실행 정의는 `run-suite.sh` 및 `cost-test-classes.txt`, `adjacent-test-classes.txt`에 있다.

관련 문서:

- [구현 전 계획 및 최종 구현 기록](COST_REMAINING_IMPLEMENTATION_2026-10-05_KO.md)
- [세션 문제·해결 기록](SESSION_ISSUES_2026-10-05.md)
- [논문과 코드 정합성 분석 및 수정안](COST_MODEL_PAPER_CODE_ALIGNMENT_AND_REPAIR_PLAN_2026-10-05_KO.md)

## 9. 최종 판정

**요청한 legacy 제거와 R59 비용 모델 구현은 완료하여 로컬 소스에 반영했다.** 공통 비용식, 함수 경계 재사용, 오프라인 보정 및 환경 연결을 정리했고 대표 회귀 근거를 확보했다.

다만 **전체 테스트 무결함, 논문 전체와 구현의 완전한 일치, 모든 workload의 runtime 개선, compile+planning 20초 달성, 원격 배포 또는 origin/main 반영까지 완료한 상태는 아니다.** 알려진 실패와 미검증 범위를 남기고 사용자 요청대로 validation을 종료했다.
