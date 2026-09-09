# 입력 privacy별 Global·incremental Anytime — 중간 진행 보고서

기준 시각: **2026-09-09 01:37:12 CEST** (`2026-09-08T23:37:12.992949+00:00`). 이 파일은 해당 시점의 고정 snapshot이며 계산은 계속 진행 중이다.

**전체 347/512회 완료. 혼합 조건 256회는 원본·oracle 검증까지 완료됐고, 모두 PUBLIC 조건은 91/256회 실행됐다.** 실험은 so007의 native JVM에서 순차 실행한다.

## 1. 실행 범위와 현재 상태

| 항목 | 설정 |
| --- | --- |
| 비교 | 같은 V5 JAR의 compact+exact Global / incremental Anytime |
| Privacy | mixed: X PRIVATE_AGGREGATE, applicable Y PUBLIC / public: 두 입력 PUBLIC |
| 대상 | 기존 ML 10개 + P1/P2 + SliceLine 4개 = 16개 입력 |
| 환경 / 목표 | modeled network 4종 × 5%·3% |
| 반복 | 각 조건에서 Global·Anytime 각 1회; privacy당 128 pairs, 256 JVM 실행 |
| 예산 | Anytime seed 이후 20초 soft budget; 두 방법 모두 60초 outer JVM watchdog |
| 수행 범위 | planning-only; Docker·worker·workload 실행 없음. 네트워크 bandwidth·RTT는 비용 모델에만 반영 |
| 변경 | 독립 fixture의 privacy metadata만 변경. Java, JAR, DML, shape, nnz, seed, 원본 입력 유지 |
| 비해당 입력 | PCA/ALS/KMeans/GNMF/GMM-VVI/P1은 X-only, Y=N/A. SliceLine의 응답 입력은 e |

## 2. 완료된 혼합 조건: X PRIVATE_AGGREGATE, Y PUBLIC

인증 성공은 같은 조건의 Global 최적값, model fingerprint, checkpoint의 L≤C*≤U 및 단조성 검증을 통과한 결과만 센다. 목표 미달과 계획 생성 실패도 전체 분모에 포함했다.

| 목표 | 검증된 인증/전체 | 목표 미달 | Anytime 실패 | Global 실패 | 초기 LB 인증 / 증분 후 인증 | Anytime planner 승리 | Planner 중앙값 Global / Anytime (초) | 시간 분모 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5% | 57/64 | 2 | 5 | 5 | 16 / 41 | 26 | 1.282 / 1.346 | 59 |
| 3% | 57/64 | 2 | 5 | 5 | 10 / 47 | 22 | 1.324 / 1.463 | 59 |

전체 114/128개 인증이다. 26개는 초기 bound에서, 88개는 실제 incremental refinement 이후 도달했다. 완전한 consistency 복원에 의존한 인증은 0개다. Global보다 전체 planner 시간이 짧으면서 인증까지 성공한 조건은 48/128개다.

**혼합 조건에서는 3%·5% 인증이 대부분 가능했지만, 이번 한 번의 측정으로 Global보다 전반적으로 빠르다는 결론은 나오지 않았다.** Planner 중앙값은 oracle 검증된 59 pairs/목표를 사용하고, 실패 행을 시간 0으로 넣지 않았다.

| 목표 | 성공한 pair의 launcher→인증 중앙값 Global / Anytime (초) | 성공 분모 | Peak RSS 중앙값 Global / Anytime (MiB) | RSS 분모 |
| --- | ---: | ---: | ---: | ---: |
| 5% | 4.534 / 4.742 | 57 | 1211.0 / 1216.6 | 59 |
| 3% | 4.525 / 4.882 | 57 | 1211.2 / 1217.7 | 59 |

Launcher→인증 시간은 JVM 시작·초기화와 출력 관측 지연을 포함하는 성공 행의 관측값이다. 실패/미달에 대한 time-to-threshold는 알 수 없으며 위 표에서 제외한 수를 성공률 표에 남겼다. RSS는 전체 JVM의 최고 resident memory이며 planner 내부 cache 크기만을 뜻하지 않는다.

## 3. 혼합 조건 workload별 결과

각 행은 4개 네트워크 × 2개 목표, 총 8 pairs다. 시간 중앙값에는 정상 완료했지만 목표에 미달한 pair도 포함한다.

| Workload | 인증/8 | 미달 / A실패 / G실패 | Global / Anytime planner 중앙값 (초) | 시간 분모 | A planner 승리 |
| --- | ---: | ---: | ---: | ---: | ---: |
| pca | 8/8 | 0 / 0 / 0 | 0.616 / 0.626 | 8 | 3 |
| lm | 8/8 | 0 / 0 / 0 | 0.946 / 0.714 | 8 | 6 |
| als | 8/8 | 0 / 0 / 0 | 0.586 / 0.695 | 8 | 3 |
| kmeans | 8/8 | 0 / 0 / 0 | 0.900 / 0.964 | 8 | 3 |
| logreg | 8/8 | 0 / 0 / 0 | 1.918 / 2.447 | 8 | 3 |
| l2svm | 8/8 | 0 / 0 / 0 | 0.824 / 2.277 | 8 | 0 |
| steplm | 8/8 | 0 / 0 / 0 | 1.223 / 2.442 | 8 | 0 |
| glm | 2/8 | 4 / 2 / 2 | 5.511 / 23.836 | 6 | 0 |
| gnmf | 8/8 | 0 / 0 / 0 | 0.487 / 0.690 | 8 | 1 |
| gmm-vvi | 8/8 | 0 / 0 / 0 | 1.274 / 1.020 | 8 | 6 |
| P1_FULL | 8/8 | 0 / 0 / 0 | 3.499 / 4.270 | 8 | 1 |
| P2_PREP | 0/8 | 0 / 8 / 8 | — / — | 0 | 0 |
| sliceline-adult | 8/8 | 0 / 0 / 0 | 1.576 / 1.486 | 8 | 5 |
| sliceline-covtype | 8/8 | 0 / 0 / 0 | 1.580 / 1.444 | 8 | 6 |
| sliceline-kdd98 | 8/8 | 0 / 0 / 0 | 1.481 / 1.349 | 8 | 6 |
| sliceline-uscensus | 8/8 | 0 / 0 / 0 | 1.609 / 1.497 | 8 | 5 |

## 4. 확인된 미달·실패 원인

**GLM, LAN/WAN_light: LB보다 incumbent U가 문제다.** 두 네트워크의 3%·5% 총 4개 Anytime 행이 20초 soft budget 종료로 미달했다. LAN 5%의 경우 C*=8,943.130, L=8,918.488, U=58,481.944 modeled ms다. LB는 최적값보다 약 0.276% 낮을 뿐이지만, 실제 계획 regret는 약 553.931%다. 두 번의 제한된 Regional repair가 모두 U를 줄이지 못했다. U를 고정하면 L을 C*까지 올려도 3%·5%에 도달할 수 없다. 이는 독립 Global을 확인한 뒤의 사후 분석이며 온라인 알고리즘이 C*를 사용한 것은 아니다.

**GLM, WAN_mid: 공통 계획 변환 오류.** Global·Anytime과 두 목표 모두 `LopsException -- fed_refed lowering found ambiguous selected consumer hop=568 for local hop=567 matches=2`로 계획 생성을 끝내지 못했다. JVM exit code가 0이더라도 execution footer가 없어 실패로 판정됐다. 실패 4 JVM을 인증에 포함하지 않았다. Frozen analyzer의 범주 `other`는 그대로 두고 실제 예외를 별도 기록했다.

**P2_PREP: 혼합 조건의 privacy-safe placement 오류.** 네 네트워크와 두 목표에서 Global·Anytime 모두 파생 `FunOut M:M (privacy=PRIVATE_AGGREGATE)`에 합법적인 placement를 찾지 못했다. 총 16 JVM 실패다. 이것은 현재 planner/encoding에서의 실패이며 가능한 모든 구현의 불가능성 증명은 아니다. 작은 PUBLIC smoke에서는 P2가 통과했지만 전체 PUBLIC 본실험 판정은 아직 진행 중이다.

**L2SVM:** 혼합 조건의 8개 목표는 모두 인증했다. 전체 planner 중앙값은 Global 0.824초, Anytime 2.277초로 이 사례에서는 Anytime이 느렸다. PUBLIC 본실험과 대조할 예정이다.

실험 도중 알고리즘이나 legality 규칙을 바꿔 실패 행을 대체하지 않았다. 개선 구현은 이 고정 비교의 결과와 분리해야 한다.

## 5. 모두 PUBLIC: 진행 중인 수치

기준 시각까지 **91/256 JVM**이 완료됐다. Global 46회와 Anytime 45회가 launcher 검사를 통과했다. Anytime checkpoint가 보고한 목표 달성은 45회다. **최종 raw/oracle audit 전 잠정 수치**이며 mixed의 검증 완료 성공률과 합산하지 않는다.

현재 workload/profile: `l2svm` / `wan_mid`. 완료 workload는 5/16개다.

## 6. 검증 근거와 계산 계속 실행

- Mixed raw audit: `valid-complete-all-declared-bytes-rehashed`; 256/256 attempts, 479/479 external assets hash 일치, trial error 0.
- Mixed oracle/model 검사: 118 pairs 검증, 실패한 10 pairs는 oracle 없음. 인증 성공 114개와 정상 종료 미달 4개를 구분했다.
- Fixture 독립 검증: 161개 원본 파일 유지; 148개 비-metadata 파일 동일; mixed는 label 6개, public은 metadata 13개에서 privacy만 변경. 실제 X/Y source privacy는 staged metadata와 코드의 local binding 경로로 확인했으며 로그에 X/Y를 직접 출력한 것은 아니다.
- 이번 Python harness 45개 테스트와 report/diagnostic helper 6개 테스트 통과. Java는 재빌드·재시험하지 않았다. 이전 Java 검증을 이번 신규 실행 결과로 세지 않는다.
- 2초 smoke 16회는 사전 진단용으로 본실험 512회에서 제외한다. 기존 private/private V5 실험도 새 반복으로 합치지 않는다.
- 계산 supervisor PID `3239256`는 `running`이며 PUBLIC 측정을 계속 실행한다. 끝나면 자동 분석과 raw audit를 수행한다. 이 보고서 작성 때문에 계산을 중단하거나 재시작하지 않았다.
- Cell당 한 번 측정이므로 통계적 speedup이나 privacy 변경만의 인과 효과를 확정하지 않는다. 인증 대상은 encoded cost model이며 실제 workload 실행시간이 아니다.

## 7. 절대 경로

- 이 보고서: `/home/mchoi/so007-anytime-incremental-20260908/docs/ANYTIME_PRIVACY_PROGRESS_2026-09-09_KO.md`
- 고정 진행 snapshot: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/progress-report-snapshot-v1.json`
- Mixed 분석: `/home/mchoi/so007-anytime-privacy-evidence-20260909/native/runs/privacy-mixed-v1/analysis/sevenway_analysis.json`
- Mixed raw audit: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/main-mixed-audit.json`
- 미달·실패 사후 진단: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/mixed-posthoc-diagnostics.json`
- Fixture 검증: `/home/mchoi/so007-anytime-privacy-evidence-20260909/reviews/privacy-fixture-review.md`
- 진행 상태: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/supervisor.json`
- PUBLIC 진행 행: `/home/mchoi/so007-anytime-privacy-evidence-20260909/native/runs/privacy-public-v1/rows.jsonl`

보고서는 로컬 workspace와 so007의 동일 절대 경로에 저장한다. 최종 보고서는 PUBLIC 256회와 두 campaign 검증이 끝난 뒤 별도 파일로 작성한다.
