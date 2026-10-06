# R58: one-way 세 항 network 비용 구현

- **상태: 구현·검증·원본 작업공간 반영 완료 (2026-10-05).** commit/push 및 runtime 실험은 수행하지 않았다.
- 최종 검증: packaged JAR 기준 **Java 487 PASS**, 기존 ignore 2개 유지, **Python 33 PASS**. 실제 campaign import/config 연결도 원격 실행 없이 확인했다.
- 이 문서는 아래 편집 전 계획과 최종 결과를 함께 보존한다. R57의 나머지 최적화까지 완료했다는 의미는 아니다.

## 구현 전 계획 / 범위

- 사용자 최신 결정: 공통 network 식은 one-way latency + wire 시간 + codec 시간. 독립 fixed/control/RTT 가산은 제거하고 다른 항에 숨기지 않는다.
- 이번 구현은 이 계약, GET/PUT·intrinsic 공유, 방향별 설정과 migration, 실제 campaign 입력 wiring에 한정한다. R57의 별도 과제인 shared-NIC profile 확장, 함수 경계 lifetime 통합, 새 보정계수 적합은 포함하지 않는다. 기존 payload geometry/codec rate와 copy activation은 보존한다.
- 주 코드: `FederatedCostModel`, `ExactPhysicalCostModel`; 설정을 공급하는 Python campaign 경계와 해당 회귀. actual runtime RPC/feasibility/oracle/privacy/TR-TW/recompile은 수정하지 않는다.
- 기존 main target은 R37 symlink이므로 빌드하지 않는다. 격리 경로: `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-oneway-network-r58-20261005/source`. main 전체 baseline SHA 및 변경 overlay 검증은 같은 root의 `evidence/`에 저장한다.

## 순서 / 보존 계약

1. 변경 전 테스트를 추가해 asymmetric 3ms+7ms, standalone/in-band, control 무효화, worker 수 독립, 0-byte/absent stage, legacy RTT migration의 RED를 기록한다.
2. core stage 산식은 한 곳으로 통일한다. stage payload의 algebraic contribution과 stage 존재/latency를 구분해 기존 factor 구조를 유지한다. 별도 GET/PUT는 요청·응답을 조합하고 이미 있는 batch의 payload는 latency를 중복하지 않는다.
3. 기존 coordination/fixed-stage API와 호출을 삭제한다. directional latency는 초 단위 `SYSDS_FED_COST_NET_LATENCY_C2W/W2C`를 사용한다. 기존 `NET_LATENCY`는 입력 경계에서 대칭 RTT→one-way 변환하고 경고하며, 두 방향 명시 시 legacy를 무시한다. control 키는 무효화 경고만 한다.
4. frozen 과거 결과/manifest를 수정하지 않고 앞으로 생성하는 설정에서 one-way 값을 전달하고 decimal Mbit/s→MiB/s 변환을 맞춘다. 신규 네트워크 실측이나 workload campaign은 실행하지 않는다.
5. focused→기존 비용/인접 회귀, lint/static scan, 격리 package 및 packaged JAR 검증을 수행한다. 독립 read-only 검토 뒤 baseline guard로 owned 변경만 main에 반영한다. commit/push 없음.

## 정리 계획 / fallback 분류

- 삭제: 독립 control 상수·계산·사용처와 fixed-stage helper; 잘못된 이름/주석은 새 one-way 의미로 정리한다.
- 유지: legacy RTT 설정은 기록된 기존 의미를 보존하는 설정 경계의 좁은 compatibility 변환이다. core의 두 번째 가격 모델이 아니다. 잘못된 신규 latency 입력은 조용히 0으로 바꾸지 않고 오류로 드러낸다.
- 유지: 기존 unknown shape/codec 비활성/geometry 근사는 이 latency 변경과 무관하므로 새로운 fallback이나 opcode 예외를 만들지 않는다.
- 회귀 fixture는 compiler-only 합성/public 비용 비교가 필요하다. `PRINCIPLE_REBUTTAL_2026-10-05_COST_UNIFICATION_KO.md`의 동일한 이유를 이 범위에 적용한다. 실제 private 데이터/원격 실행을 사용하지 않는다.

## 검증 / 잔여 위험

- 최종 상태: 아래 명시한 최종 소스/JAR로 새 검증을 완료했다. 이전 R56 PASS를 재사용한 결과가 아니다.
- byte-independent runtime bookkeeping을 독립적으로 가격화하지 않으므로 small-payload 추정 오차가 남을 수 있다. 기존 0.35ms/intercept를 새 항에 이식하지 않는다.
- source version/copy lifetime과 actual stage count를 바꾸지 않는다. 비용 재순위가 있을 수 있으며 compile 20초/전체 runtime 개선을 주장하지 않는다.
- writer는 root, 독립 reviewer는 읽기 전용 별도 lane으로 분리한다.

### 독립 검토에서 발견한 stage 누락 (해결)

- 기존 FULL transpose의 network 면제는 runtime 근거가 없다. `ReorgFEDInstruction.processInstruction`의 transpose 분기는 FULL에서도 `PUT_VAR + EXEC`를 전송하며 LOUT의 GET도 같은 batch에 포함한다.
- FType만으로 remote stage를 없애던 helper/예외를 삭제하고, 실행되는 transpose에는 요청·응답 latency를 한 번씩 부과한다. 실제 fusion으로 제거되는 kernel의 기존 `removedKernel` 처리는 유지한다.
- 먼저 해당 회귀의 기대값을 실제 RPC에 맞춰 실패를 기록하고 수정한다. runtime/후보 합법성은 변경하지 않는다.
- 수정 전 candidate에서 `expected 10ms, actual 0ms` 실패를 확인했다. 예외/helper/FType 인자를 삭제한 뒤 focused 55개와 최종 전체 487개 회귀가 통과했다. 독립 reviewer 재검토에서도 두 finding(stage 누락, 낡은 control 주석)이 해결되어 APPROVE를 받았다.

## 최종 비용 계약

방향별 한 stage의 공통 식은 다음과 같다.

```text
stage_ms = 1000 * (one_way_latency_seconds
                  + wire_MiB / network_MiB_per_second
                  + codec_MiB / codec_MiB_per_second)
```

- 별도 control/fixed/RTT 가산은 없다. 제거한 0.35ms 등의 상수를 latency/codec/compute에 옮기지 않았다.
- `computeOneWayNetworkCost`가 공통 arithmetic을 소유한다. byte 수와 site/worker geometry, codec 처리량은 기존 모델을 유지한다. codec 처리량 0은 기존과 같이 해당 항 비활성 의미다.
- 독립 GET은 C2W 요청과 W2C 데이터 응답, 독립 PUT은 C2W 데이터 요청과 W2C 응답으로 구성된다. 실제 요청·응답이면 payload가 0이어도 해당 방향 latency는 남는다.
- FED operator의 intrinsic 요청·응답 latency는 실행 횟수로 가중한다. 같은 batch의 preparation/result payload factor는 공통 primitive의 **latency 0인 비용 기여분**이며, 별도 stage를 생성하는 것이 아니다. 따라서 payload마다 latency를 재과금하지 않는다.
- explicit materialization은 별도 요청·응답을 소유하고, 기존 source/version/lifetime activation이 생성 횟수를 결정한다. operator 실행 횟수와 retained-copy 생성 횟수는 합치지 않았다.
- parallel worker 수를 latency에 다시 곱하지 않는다. FULL transpose도 실제 원격 instruction이면 과금하며, 진짜 compiler-elided kernel은 기존 prepared-execution 경계에서 제외한다.

## 설정과 migration

| 설정 | 의미 / 처리 |
|---|---|
| `SYSDS_FED_COST_NET_LATENCY_C2W` | 초 단위 coordinator→worker one-way latency |
| `SYSDS_FED_COST_NET_LATENCY_W2C` | 초 단위 worker→coordinator one-way latency |
| 방향별 기본값 | 각각 `0.0005`초. 기본 요청·응답 합은 기존처럼 1ms |
| legacy `SYSDS_FED_COST_NET_LATENCY` | 경고 후, 명시되지 않은 방향만 기존 대칭 RTT의 절반으로 변환. core에는 RTT가 들어가지 않음 |
| `SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS` | 값과 무관하게 무시하고 retired diagnostic 출력 |
| invalid latency | 음수/NaN/무한대/숫자가 아닌 명시값은 오류. 조용한 0/default 대체 없음 |

명시된 방향별 값이 legacy보다 우선하며 같은 키에서는 property가 environment보다 우선한다. 임의의 비대칭 네트워크를 RTT 하나로 추론하지 않는다. 현재 대칭 Docker profile의 생성기는 `rtt_ms / 2000`을 각 방향에 전달한다.

네트워크 대역폭 설정은 다음처럼 단위를 맞췄다.

```text
MiB/s = Mbit/s * 1_000_000 / (8 * 1024 * 1024)
1000 Mbit/s -> 119.209290 MiB/s
```

기존 codec 계수 C2W=210, W2C=14.7은 재측정하거나 바꾸지 않았다. 매 실행 profiling도 추가하지 않았다. 과거 frozen planning snapshot을 읽을 때만 private legacy validator를 사용하고, 새 설정에는 one-way 키만 출력한다. 과거 manifest/result를 새 값으로 고쳐 쓰지 않았다.

## 수정 파일과 정리

### 본 저장소

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`: 공통 one-way primitive, 방향별 입력, control/coordination/fixed helper 및 근거 없는 FULL transpose 면제 삭제.
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java`: 삭제된 별도 coordination/항상 0 penalty 호출과 불필요한 전달 인자를 제거. 공통 instruction stage 사용.
- `scripts/fedplanner/{derive_sliceline_conditions,freeze_campaign_conditions,freeze_microbench_conditions}.py`와 대응 테스트 3개: 향후 생성 설정의 one-way latency 및 MiB/s 단위 통일.
- `OneWayNetworkCostTest`, `FederatedNetworkCostUnificationTest`, `FederatedCostModelFallbackTest`: asymmetric latency, migration, stage 존재/빈 payload, frequency 회귀.
- `FederatedCostModelFixedInstructionStageTest` → `FederatedCostModelInstructionNetworkStageTest`: 삭제한 fixed/control API 대신 request/response/in-band 계약 검증.

### 실제 campaign 의존 저장소 `/home/mchoi/cofee-evaluation`

- `driver/run_multihost_campaign_network_quality_v2.py`: 실제 `cell_environment`에 새 설정 전달.
- `campaign/run_ml10_campaign.py`: 변경한 driver의 SHA pin만 갱신. SHA mismatch 차단은 유지한다.
- `tests/test_network_cost_environment.py`: 출력 단위·실제 cell wiring·adapter pin 회귀 추가.

**새 모델 실험에는 새로운 campaign root가 필요하다.** 기존 root는 driver/JAR 등 identity를 고정하므로 그 receipt를 덮거나 guard를 우회해 재사용하지 않는다. 본 저장소만 커밋해서는 외부 driver 변경이 함께 포함되지 않는다는 점도 유의한다.

## 검증과 재현 산출물

격리 root:

`/grid/3/cofee-lm-sweep-mchoi-20260914/cost-oneway-network-r58-20261005`

| 검증 | 결과 / evidence 파일 |
|---|---|
| 최초 계약 RED | 기존 R56에 12개 중 11개 실패: `evidence/oneway-red.log` |
| FULL transpose 추가 RED→GREEN | 1개 실패 → focused 55 PASS: `evidence/review-full-transpose-{red,green}.log` |
| 변경 production 2개 lint | `javac -proc:none -Xlint:all,-path`, 진단 0: `evidence/production-javac.log` |
| 전체 production package | `mvn -DskipTests -Dmaven.test.skip=true package`, BUILD SUCCESS: `evidence/package-final.log` |
| 최종 JAR 비용 suite | 299 PASS: `evidence/final-cost-tests.log` |
| 최종 JAR 인접 회귀 | 188 PASS, 기존 ignore 2개: `evidence/final-adjacent-tests.log` |
| main Python | 22 PASS: `evidence/final-main-python-tests.log` |
| 외부 driver/adapter Python | 11 PASS: `evidence/final-external-python-tests.log` |
| 실제 dependency import + config smoke | LAN/WAN-Light PASS, 원격 호출 없음: `evidence/active-campaign-wiring-smoke.log` |

재현 명령은 격리 source에서 `../evidence/run-suite.sh final cost`와 `../evidence/run-suite.sh final adjacent`이다. main의 `target`에서는 빌드하지 않는다.

- 최종 JAR: `source/target/systemds-3.4.0-SNAPSHOT.jar`
- SHA-256: `70f180294a891cc88cb35a552069b3c3ee86346398cd294d99c6ea687c3d2eb6`
- 실제 Maven class 44개와 JAR 내부 class 일치, production/test/builtin 3,698파일이 main과 일치함을 `evidence/final-build-proof.json`, `final-build-inputs.json`, `final-sync-proof.json`에 기록했다.
- main 반영은 baseline SHA guard 후 owned 12파일 복사 + 이전 이름 테스트 1개 삭제로 한정했다. 무관 baseline 13,275파일, HEAD, R37 target symlink를 보존했다. 외부 3파일의 별도 proof는 `evidence/external-sync-proof.json`이다. pre-image는 `patch-base/`, `patch-base-external/`에 보존했다.
- 중간 검증에서 새 sourcepath로 광범위 재컴파일을 시도한 direct javac가 incubator module 누락으로 실패했다. production 변경 없이 두 대상 파일을 기존 dependency JAR에 대해 lint하는 명령으로 수정했다. 정식 Maven 전체 compile와 최종 JAR 회귀는 별도로 통과했다.
- 독립 writer/reviewer 분리를 유지했고, whitespace/syntax/static retired API 참조 검사도 수행했다. 새 dependency나 runtime fallback은 없다.

## 아직 주장하지 않는 것

Shared-NIC aggregate wire/max profile 확장, 함수 경계의 copy lifetime union, site별 새 calibration은 미구현/미측정으로 남는다. 작은 메시지의 bookkeeping 잔차도 있을 수 있다. 이번 PASS는 식·stage 소유권·설정 연결의 회귀 검증이지 전체 workload runtime 개선율이나 compile+planning 20초 달성 증거가 아니다. 새 실험을 자동 재개하지 않았다.
