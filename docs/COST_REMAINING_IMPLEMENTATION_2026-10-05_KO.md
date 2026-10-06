# R59: legacy network 제거 및 남은 비용 모델 구현

## 요청과 완료 기준 (편집 전 계획)

사용자 요청은 R58에서 남긴 항목 전체의 구현이다. R57의 검증 가능한 범위를 실행한다.

1. **Legacy 삭제**: RTT→one-way 변환, retired control/GET 경고·구 옵션, generic network throughput fallback, 0-return network penalty API, 과거 frozen schema를 현재 설정으로 받아주는 compatibility 분기를 제거한다. 현재 방향별 latency/throughput/resource profile만 사용한다. 과거 raw/result/receipt는 삭제하거나 수정하지 않는다.
2. **공유 NIC**: 동일 one-way primitive의 wire 항을 `max(largest worker bytes / worker-leg rate, total bytes / coordinator rate)`로 통일한다. GET/PUT/intrinsic/explicit가 같은 산식을 사용한다. PUT broadcast 총량 multiplier는 한 번만, FULL/replica/partial/skew 수량은 실제 의미를 유지한다. stage latency는 기존 R58 계약을 유지한다.
3. **함수 경계 재사용**: 실제 runtime이 공유하는 MatrixObject와 정확한 source/version/context/lifetime이 증명된 함수 GET을 일반 GET과 동일 collector/activation OR에 넣는다. Builder의 call-context ancestry를 immutable fact로 보존한다. 매 호출 갱신되는 source, 새 REFED alias, 함수 출력, 선택되지 않은 수요는 무조건 합치지 않는다. PUT은 실제 emission 수명을 유지한다.
4. **오프라인 보정**: 기존 R56 완료 raw340개만 사용한다. 4/16MiB train, 64MiB held-out, 0.0625MiB diagnostic 분리를 유지한다. 공통 wire/latency를 뺀 잔차에 방향별 비음수 payload 계수만 적합한다. free intercept/worker별·opcode별 계수를 만들지 않는다. archived preflight의 coordinator/worker `ens5f0/speed=1000`과 directional egress shaping을 각각 반영한다. 검증된 profile만 적용하며 근거 없는 환경 일반화·새 실측을 하지 않는다.
5. **통합 검증**: public/compiler-only 회귀는 기존 `PRINCIPLE_REBUTTAL_2026-10-05_COST_UNIFICATION_KO.md` 근거를 유지한다. 새 RED→GREEN, 기존 비용/alias/context 인접 회귀, Python offline/profile/wiring, lint/build/독립 검토를 수행한다. 모델 정확도와 workload runtime 개선은 구분한다.

## 보존/정리 분류

- 범위는 비용 모델·frequency facts·configuration/calibration 경계다. legality/oracle/privacy/TR-TW/recompile와 runtime protocol을 바꾸거나 feasible 후보를 닫지 않는다.
- legacy 설정 호환은 R58까지 의도된 compatibility였지만 최신 사용자 요청으로 제거한다. old artifact acceptance로 새 설정을 몰래 생성하지 않는다.
- unknown shape의 명시된 bounds/fallback, 불확실한 alias에 대한 독립 과금은 비용/정체성 근거를 잃지 않기 위한 별개 계약이다. network legacy 삭제에 끼워 제거하지 않는다.
- main의 R37 target symlink와 기존 dirty work는 보존한다. `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-completion-r59-20261005/source`에서 구현/빌드한 뒤 baseline SHA guard로 owned 변경만 반영한다.
- root는 network/core/config 통합, 별도 native executor는 함수 collector/frequency 및 offline calibration을 분담한다. 독립 read-only reviewer로 최종 수정자를 분리한다. 새 dependency, commit/push, 원격 workload 재실행은 없다.

## 회귀 위험과 검출

- payload별 latency 중복·0-byte stage 누락: 기존 R58 asymmetric/empty/in-band 회귀 유지.
- worker bandwidth를 coordinator RX에 잘못 재사용: 방향별 독립 cap 및 W1/W3/W7 총량·skew 테스트.
- 함수 context를 동일 이름/weight로 잘못 묶음: nested calls, distinct actual, updated actual, branch 및 relocation 음성 회귀.
- 새 profile이 cache에 섞임: rate는 JVM/optimization 전 고정, 수량과 unit price는 기존 factor/cache identity에 유지한다.
- 계수 적합으로 fixed overhead 은닉: intercept=0, signed residual/held-out 비교·source/environment provenance를 남긴다.

## 상태

**구현·원본 소스 반영 완료. 사용자 요청에 따라 추가 validation은 종료했다.** 최초 baseline13,290파일과 기존 삭제 상태를 보존했으며, 격리 빌드 후 SHA guard로 변경 파일만 반영했다.


## R59 최종 구현

### 1. Legacy 삭제와 공통 network primitive

- generic bandwidth/codec fallback, legacy RTT 변환, retired control/GET 옵션 처리, 0-return penalty API, 과거 frozen network 설정을 신규 설정으로 받아들이는 compatibility 분기를 삭제했다.
- network stage는 **one-way latency + wire time + codec time**만 사용한다. 별도 fixed/control 가산 및 affine intercept는 없다.
- wire time은 `max(largest worker bytes / worker-leg rate, total bytes / coordinator rate)`다. coordinator cap이 unspecified이면 해당 항을 적용하지 않는다.
- GET/PUT, operator intrinsic, explicit movement가 같은 primitive를 사용한다. 실제 request/response stage는 남기며 payload마다 latency를 중복 추가하지 않는다.
- BROADCAST는 total=`D×W`, ROW/COL은 total=`D`, largest=`D/W`, FULL의 유효 W1 map은 total=largest=`D`다.
- 방향별 rate 단위는 MiB/s이며, coordinator C2W/W2C cap을 별도로 받는다. 명시적인 잘못된 throughput은 오류다. W2C coordinator 기본값0은 **미지정**이지 실측 무한대가 아니다.

### 2. 함수 경계와 일반 GET의 재사용 통일

- immutable caller/callee occurrence ancestry와 source/version/context/availability가 증명되는 함수 GET을 기존 `DownloadKey` activation-OR collector에 통합했다.
- 동일 actual→두 formal뿐 아니라 동일 actual의 일반 consumer도 한 creation을 공유한다. metadata TWrite/TRead의 runtime `cpvar`가 같은 Data 객체를 가리키는 경로만 정규화했다.
- loop 밖 invariant는1회, loop 안에서 다시 생성되는 source는 생성 횟수만큼 과금한다. distinct source/version 및 증명되지 않은 fresh/relocated alias는 독립 과금을 유지한다.
- 함수 GET bytes의 sparse/NNZ/unknown-shape 계약을 유지했다. 함수 입력을 source별 index로 찾아 producer마다 전체 input을 반복 검색하지 않는다.
- 새 교차 consumer 회귀는 R58의 duplicate-formal 오류와 첫 R59 candidate의 formal+ordinary 오류를 각각 재현했고 수정 후 통과했다.

### 3. 오프라인 calibration과 실제 설정 연결

- 기존 R56 완료 raw340개만 사용했다. 새 원격 측정이나 실행마다 profiling하지 않는다.
- 4/16MiB train, 64MiB held-out으로 방향별 payload 계수를 적합했다. production이 아는 logical/statistical bytes를 사용하며 observed frame bytes는 진단 자료다.
- held-out 개선이 확인된 W2C만 채택했다. LAN codec401.881358MiB/s, WAN-Light218.590544MiB/s다. C2W는 새 후보가 더 나빠 기존210MiB/s를 유지했다.
- W2C held-out 중앙 상대오차는 같은 wire 모델의 기존 계수 대비 LAN453.65%→21.10%, WAN-Light340.00%→24.61%다. **전송 비용 추정오차이며 전체 workload runtime 개선율이 아니다.**
- frozen profile은 실제 선택 coordinator/worker host의 archived link 증거, 측정 worker 수, network tuple, 실제 Docker image가 맞을 때만 적용한다.
- 실제 ML10 W5 및 base W5/W7 등 미측정 host/worker 수에는 `configured-not-calibrated`를 명시하고 baseline codec을 유지한다. unknown coordinator RX는0/unspecified다. topology 전체 파일 SHA 차이만으로 유효한 선택 host를 거부하지 않는다.
- driver·ML10·integrated campaign caller에 selection/image를 전달하고 profile/driver SHA pin을 갱신했다. 과거 frozen 결과·receipt·raw는 변경하지 않았다.

## 완료 검증과 중단 범위

| 항목 | 확보된 결과 |
|---|---|
| 격리 Maven package | BUILD SUCCESS, 38.937초 — planning 시간이 아님 |
| 최종 JAR 비용 회귀 | 310 PASS |
| 최종 JAR 인접 회귀 | 188 PASS, 기존 ignore2 |
| 변경 production3파일 javac lint | 진단0 |
| Python main focused | 42 PASS |
| 외부 topology/profile focused | 4 PASS |
| 마지막 driver/ML10/integrated caller 수정 | syntax compile 성공 |
| 별도 frequency fixture suite | 16/17 PASS; 아래 기존 실패1개 |

- 별도 `OccurrenceExecutionFrequencyFactsConstantBranchTest.actualBuiltinGlmDeadStraightenXIsZeroAndCgRemainsPositive`의 Gram occurrence assertion은 R58 baseline과 R59에서 동일하게 실패했다. 이번 변경으로 해결했다고 주장하지 않는다.
- native-local-anchor golden receipt는 canonical source/activation descriptor 변경에 맞췄다. plan structure 및 raw contribution bits digest는 기존 값과 같았다.
- independent reviewer의 설정 연결 문제를 수정했다. balanced PUT의 mixed-stage max 문제 제기는 동일 worker/rate 조건에서 성립하지 않아 철회되었다. **최종 설정 수정 후 broad integrated suite 재실행 및 reviewer 재승인은 사용자 validation 중단 요청에 따라 하지 않았다.**
- 추가 workload runtime 실험, 원격 calibration, 전체 저장소 테스트, commit/push는 하지 않았다. 20초 planning 또는 모든 workload 성능 개선을 주장하지 않는다.

## 반영 파일과 증거

- Java production: `FederatedCostModel.java`, `ExactPhysicalCostModel.java`, `OccurrenceExecutionFrequencyFacts.java`.
- Java regression9파일 및 Python calibration/profile/generator/campaign/test13파일: main source/script 총25파일을 반영했다.
- 외부 `/home/mchoi/cofee-evaluation`: driver, frozen transport profile, ML10/integrated caller와 관련 test, 총6파일을 반영했다.
- 전체 변경 파일명과 전후 SHA는 아래 evidence의 `main-sync-proof.json`, `external-sync-proof.json`에 있다. 기존 dirty work와 HEAD, main의 R37 target symlink는 보존했다. preimage도 보존했다.
- 최종 JAR: `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-completion-r59-20261005/source/target/systemds-3.4.0-SNAPSHOT.jar`.
- JAR SHA256: `f2d266d0833bd2489d8e3d2b40e54d0dfa251f1f745d59a20ba1f8baa59adb81`.
- 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-completion-r59-20261005/evidence/`의 `final-cost-tests.log`, `final-adjacent-tests.log`, `final-frequency-tests.log`, `build-jar-proof.json`, `r59-calibration-verification.json`, `completion-receipt.json`.

## 남는 모델 한계

공유 NIC 식은 homogeneous worker-leg와 coordinator cap을 쓰는 star 모델이며 PUT은 balanced 분할을 가정한다. 임의의 skew/이질 네트워크 resource graph 전체를 모델링하지 않는다. 불명확한 source/copy lifetime은 보수적 별도 과금이며 small-payload bookkeeping은 독립 fixed 항 없이 잔차로 남는다. 계수의 미측정 환경 일반화와 비용 변화에 따른 실제 계획/runtime 개선은 이번 완료 범위가 아니다. 이를 확인하기 위한 추가 validation은 자동 재개하지 않는다.
