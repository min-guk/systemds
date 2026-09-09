# 입력 privacy별 compact Global / incremental Anytime 최종 검증 보고서

검증 완료 시각: `2026-09-09T00:08:20.971127+00:00`. 서버: `dams-so007` (`130.149.237.17`).

**요청한 두 privacy 조건의 512회 native JVM planning-only 측정과 원본·oracle 검증을 완료했다.** Anytime 인증은 mixed 114/128, public 122/128이다. PUBLIC에서 P2의 계획 생성이 가능해졌지만, 3%·5%에서 Anytime이 Global보다 전반적으로 빠르다는 결과는 나오지 않았다.

## 1. 요청과 실험 범위

Mixed는 X=PRIVATE_AGGREGATE, 해당하는 Y=PUBLIC이다. Public은 X와 Y가 모두 PUBLIC이다. PCA, ALS, KMeans, GNMF, GMM-VVI, P1은 X-only여서 Y=N/A이고, SliceLine의 응답 입력은 오류 벡터 e다. 원본을 보존한 독립 fixture의 privacy 필드만 바꿨다.

| 항목 | 설정 |
| --- | --- |
| 대상 | PCA, LM, ALS, KMeans, LogReg, L2SVM, StepLM, GLM, GNMF, GMM-VVI + P1_FULL/P2_PREP + SliceLine adult/covtype/kdd98/uscensus |
| 조합 | 16개 입력 × 4개 네트워크 × 2개 목표(5%, 3%) × 2개 방법 × 2개 privacy = 512 JVM |
| 반복 / 순서 | Cell당 paired 1회, method 순서 회전, mixed 이후 public 순차 실행 |
| 구현 | 동일 V5 compact+exact Global / incremental Anytime. 새 Java 구현·빌드 없음 |
| Anytime 초기화 | Ordered greedy/hard repair와 검증된 replica projection; width 2 global relaxation |
| 증분 정책 | modal-minority / cached work 후보 순위, action당 최대 1 probe, 관련 component의 consistency 복원·재계산 |
| 계산 제한 | 최대 256 actions, seed 이후 20초 soft budget, 각 JVM 60초 outer watchdog |
| Primal Regional | 최대 2회, active original 결정 24개까지, auxiliary 자유, 예상 작업량 1M gate |
| Exact 자원 | 같은 compact kernel. Anytime factor/total cells 1M/5M, Global production cap 10M/50M; 양쪽 cap이 같다는 비교는 아님 |
| JVM | -Xmx8g -Xms8g -Xmn800m -XX:ActiveProcessorCount=8 |
| 보장 범위 | 같은 encoded finite cost model의 regret. Workload 실제 실행시간·예측오차 인증은 아님 |

| Modeled network | C→W bandwidth (Mbit/s) | W→C bandwidth (Mbit/s) | RTT (ms) |
| --- | ---: | ---: | ---: |
| LAN | 5000 | 5000 | 1 |
| WAN_light | 2500 | 1000 | 10 |
| WAN_mid | 250 | 200 | 100 |
| WAN_heavy | 100 | 100 | 200 |

Docker·worker·workload 실행과 traffic shaping은 수행하지 않았다. 네트워크 수치는 cost model 설정이다. 동일 DML·shape·nnz·seed·cost environment를 유지했다. 새 privacy는 다른 feasible model을 만들 수 있으므로 Global oracle은 각 privacy 안에서 독립적으로 계산했고 온라인 Anytime에 전달하지 않았다.

## 2. 목표별 성공률과 전체 planning 시간

| Privacy | 목표 | 인증/전체 | 목표 미달 | A실패 / G실패 | Global planner 중앙값 (s) | Anytime planner 중앙값 (s) | 시간 분모 | 인증하면서 A가 빠른 조건 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| mixed | 5% | 57/64 | 2 | 5 / 5 | 1.282 | 1.346 | 59 | 26/64 |
| mixed | 3% | 57/64 | 2 | 5 / 5 | 1.324 | 1.463 | 59 | 22/64 |
| public | 5% | 61/64 | 2 | 1 / 1 | 1.411 | 1.878 | 63 | 9/64 |
| public | 3% | 61/64 | 2 | 1 / 1 | 1.480 | 1.960 | 63 | 12/64 |

A=Anytime. 목표 미달과 실패를 모두 64개 분모에 남겼다. Planner 중앙값은 같은 조건의 oracle/model 검증이 완료된 pair를 사용하며 정상 종료 목표 미달도 포함한다. 계획 생성 실패에는 유효한 planner 완료 시간이 없으므로 0초로 채우지 않았다.

| Privacy | 목표 | Launcher→인증 중앙값 G / A (s) | 성공 분모 | 모든 시도 native wall 합계 G / A (s) | 전체 시도 분모 |
| --- | ---: | ---: | ---: | ---: | ---: |
| mixed | 5% | 4.534 / 4.742 | 57 | 375.658 / 429.365 | 64 |
| mixed | 3% | 4.525 / 4.882 | 57 | 372.589 / 445.472 | 64 |
| public | 5% | 4.554 / 5.043 | 61 | 387.635 / 480.179 | 64 |
| public | 3% | 4.757 / 5.204 | 61 | 388.896 / 484.975 | 64 |

Launcher→인증은 JVM 시작부터 checkpoint를 관측한 시간이며 시작·초기화·출력 지연을 포함한다. 이는 성공한 조건만의 중앙값이므로 위 성공률과 함께 읽어야 한다. 미달의 time-to-threshold는 관측되지 않았다. Native wall 합계는 실패를 포함한 각 방법 64회 비용이며 oracle 감사·보고서·실행 사이 준비시간은 제외한다. Anytime 시간에 별도 Global oracle의 계산 시간을 합치지 않았다.

## 3. 실제 incremental 강화의 기여

| Privacy | 목표 | 초기 bound 인증 | 증분 후 인증 | 완전 복원 후 인증 | 증분 후 인증하면서 Global보다 빠른 조건 |
| --- | ---: | ---: | ---: | ---: | ---: |
| mixed | 5% | 16 | 41 | 0 | 13 |
| mixed | 3% | 10 | 47 | 0 | 15 |
| public | 5% | 10 | 51 | 0 | 4 |
| public | 3% | 7 | 54 | 0 | 5 |

초기 bound 성공은 refinement action과 복원 equality가 모두 0이다. 증분 성공은 둘 다 양수이고 첫 목표 시점에 fullyRestored=false인 경우다. 초기화만으로 끝난 성공을 증분 기법의 속도 성과로 세지 않았다. 이번 성공은 전체 consistency를 복원하기 전에 나왔지만, 이것만으로 전체 planner가 Global보다 빠르다는 뜻은 아니다.

## 4. Workload별 비교

각 privacy의 workload당 8 pairs(4개 network × 2개 threshold)다. 중앙값은 목표 미달을 포함한 정상 검증 pair 기준이다.

| Workload | Mixed 인증/8 | Mixed G / A planner (s) | Public 인증/8 | Public G / A planner (s) |
| --- | ---: | ---: | ---: | ---: |
| pca | 8/8 | 0.616 / 0.626 | 8/8 | 0.849 / 1.151 |
| lm | 8/8 | 0.946 / 0.714 | 8/8 | 0.862 / 1.270 |
| als | 8/8 | 0.586 / 0.695 | 8/8 | 0.577 / 0.918 |
| kmeans | 8/8 | 0.900 / 0.964 | 8/8 | 0.813 / 1.356 |
| logreg | 8/8 | 1.918 / 2.447 | 8/8 | 1.483 / 3.163 |
| l2svm | 8/8 | 0.824 / 2.277 | 8/8 | 1.156 / 2.586 |
| steplm | 8/8 | 1.223 / 2.442 | 8/8 | 1.743 / 5.559 |
| glm | 2/8 | 5.511 / 23.836 | 2/8 | 6.264 / 23.205 |
| gnmf | 8/8 | 0.487 / 0.690 | 8/8 | 0.855 / 1.252 |
| gmm-vvi | 8/8 | 1.274 / 1.020 | 8/8 | 1.680 / 3.593 |
| P1_FULL | 8/8 | 3.499 / 4.270 | 8/8 | 3.789 / 3.371 |
| P2_PREP | 0/8 | — / — | 8/8 | 0.529 / 0.745 |
| sliceline-adult | 8/8 | 1.576 / 1.486 | 8/8 | 1.552 / 1.307 |
| sliceline-covtype | 8/8 | 1.580 / 1.444 | 8/8 | 1.647 / 2.313 |
| sliceline-kdd98 | 8/8 | 1.481 / 1.349 | 8/8 | 1.612 / 1.526 |
| sliceline-uscensus | 8/8 | 1.609 / 1.497 | 8/8 | 1.528 / 2.166 |

GLM 시간의 분모는 privacy당 6 pairs다(그중 4개 목표 미달). Mixed P2는 시간 분모가 0이고, 나머지는 각 8 pairs다. 목표별·network별 원자료는 별도 JSON과 상세 표에 남겼다.

**L2SVM은 두 privacy 모두 8/8 인증했지만 16개 pair 모두 Global보다 느렸다.** Mixed planner 중앙값은 0.824/2.277초, public은 1.156/2.586초다. PUBLIC P1과 SliceLine-adult는 이 측정의 중앙값에서 Anytime이 더 짧았으나, 단일 paired repetition이므로 통계적 speedup을 주장하지 않는다.

## 5. GLM: 최적 비용 LB와 실제 계획 regret의 분해

`U−L=(U−C*)+(C*−L)`로 인증 gap을 계획 regret와 LB slack으로 나눴다. 아래는 완료된 독립 Global을 이용한 사후 분석이며 온라인 scheduler의 입력이 아니다. 각 행의 수치는 해당 profile의 3%·5% 미달 trial에서 동일하다.

| Privacy / profile | 초기 U | 최종 U | L | C* | 실제 regret / C* | LB slack / C* |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| mixed / lan | 58481.944 | 58481.944 | 8918.488 | 8943.130 | 553.931% | 0.276% |
| mixed / wan_light | 65219.973 | 65219.973 | 10252.139 | 10260.178 | 535.661% | 0.078% |
| public / lan | 402715.924 | 151497.048 | 8918.488 | 8943.130 | 1594.005% | 0.276% |
| public / wan_light | 441414.734 | 167142.473 | 10236.943 | 10260.178 | 1529.041% | 0.226% |

비용 단위는 modeled ms다. **모든 미달 사례에서 LB는 C*에 가깝지만 U가 지나치게 높다.** Mixed에서는 두 제한된 Regional 시도가 U를 줄이지 못했다. Public은 U가 크게 줄었어도 LAN에서 151,497, WAN_light에서 167,142 modeled ms에 머물렀다. 최종 U를 고정하면 L=C*까지 높여도 3%·5% 인증은 불가능하다. 따라서 이 사례의 다음 개선 대상은 초기 incumbent와 primal region 선택이다. 어떤 결정 조합을 추가로 풀면 해결되는지는 이번 실험에서 증명하지 않았다.

모든 미달은 seed 이후 20초 soft budget의 TIME_BUDGET 종료다. Seed·초기화와 진행 중 phase 때문에 전체 planner 시간은 약 23~25초였다. 이 budget을 hard deadline으로 해석하지 않는다.

## 6. 계획 생성 실패

**P2 mixed:** Global·Anytime × 네 profile × 두 목표, 16 JVM이 `FunOut M:M (privacy=PRIVATE_AGGREGATE)`의 privacy-safe placement를 찾지 못했다. PUBLIC에서는 16 JVM 모두 정상 완료했고 Anytime 8/8 목표를 인증했다. 이는 현재 planner에서 관측한 변화이며 privacy legality를 완화하거나 실패를 우회한 구현 변경은 없다.

**GLM WAN_mid:** 두 privacy에서 Global·Anytime × 두 목표, 총 8 JVM이 동일한 `LopsException -- fed_refed lowering found ambiguous selected consumer hop=568 for local hop=567 matches=2`로 실패했다. Exit code가 0이어도 execution footer가 없어 실패로 보존했다. Optimizer trace가 존재한다는 이유로 완성된 planning 결과로 세지 않았다. Frozen analyzer의 범주 other는 바꾸지 않고 실제 예외와 로그 해시를 사후 진단에 보존했다.

전체 실패는 24 JVM이다. Harness의 runtime_passed/failed 필드는 여기서 planning JVM의 상태를 뜻한다. 실제 workload runtime을 실행했다는 뜻이 아니다. 이 오류들은 이번 비교에서 수정하지 않았다.

## 7. 시간 분해와 메모리

| Privacy | 목표 | Bound controller (s) | 그중 component 준비 / solve (s) | Regional (s) | Refinement actions 중앙값 | Peak RSS 중앙값 G / A (MiB) | 분모 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| mixed | 5% | 0.148 | 0.048 / 0.017 | 0.020 | 10 | 1211.0 / 1216.6 | 59 |
| mixed | 3% | 0.161 | 0.066 / 0.017 | 0.022 | 12 | 1211.2 / 1217.7 | 59 |
| public | 5% | 0.408 | 0.254 / 0.053 | 0.032 | 22 | 1213.8 / 1219.0 | 63 |
| public | 3% | 0.618 | 0.312 / 0.069 | 0.036 | 39 | 1211.6 / 1213.1 | 63 |

정상 검증 pair의 성공 시점 또는 미달 종료 시점까지의 누적 timer 중앙값이다. Bound controller는 초기 bound와 증분 관리 시간을 포함하고 component 준비·solve는 그 하위 timer다. 서로 더하지 않는다. 기존 mbe_bound_seconds는 이 controller의 호환 별칭이므로 순수 MBE 시간으로 해석하지 않는다. Compact reduction, ordered seed, projection 시간과 최대 RSS는 phase JSON에 보존했다. RSS는 전체 JVM의 최고 resident memory이며 planner cache나 frontier 메모리만을 뜻하지 않는다. 이 구현은 component consistency refinement 방식으로, branch-and-bound node 수로 작업량을 설명하지 않는다.

## 8. 검증 결과와 재현성

| 검증 | 결과 |
| --- | --- |
| 전체 trial coverage | 각 privacy 256/256, 누락·추가·중복 0 |
| Raw audit | 두 campaign 모두 valid-complete-all-declared-bytes-rehashed, trial error 0 |
| 외부 파일 | 각 campaign 479/479 bytes hash 일치 |
| Oracle / model | Mixed 118 pairs, public 126 pairs 검증. 모든 검증 checkpoint가 oracle을 포함; L/U 단조성 및 보수적 목표 검사 통과 |
| 인증 / 미달 / 실패 | Anytime 236 / 8 / 12 = 256; 대응 Global 실패 12 |
| 실행 경계 | 정상 JVM 488개 모두 runtime_executed=false, execution_seconds=0, compile-only config 유효, 출력 없음 |
| 원본 보존 | 원본 161개 파일 hash 유지. 각 fixture 비-metadata 148개 동일, 13개 metadata에서 privacy 외 JSON 동일 |
| 변경 metadata | mixed labels 6개, public 13개; 데이터 값 파일 없음 |
| 이번 helper 검증 | Python harness 45개 + report/diagnostic 6개 테스트 통과. Java 변경·재빌드·신규 Java test 없음 |

입력 privacy는 실제 staged metadata와 production local source binding 경로를 대조한 독립 검토로 확인했다. 현재 로그는 literal X/Y source privacy를 출력하지 않으므로 직접 로그 trace로 확인했다는 주장은 하지 않는다. Context의 data_provenance는 과거 원본 설명이고, 실제 조건은 cases·privacy_experiment·staged metadata다.

두 privacy에 모두 검증된 Global oracle이 있는 118개 대응 조건에서 public C*는 모두 mixed C* 이하였다. 이는 관측 방향 검사이며 서로 다른 encoded model이 동일하다는 뜻이나 인증의 근거가 아니다. 두 campaign은 시간적으로 순차 실행했으므로 privacy 변경만의 planner 시간 인과 효과를 분리하지 못한다.

2초 budget의 smoke 16회와 이전 private/private V5는 본 512회에 합치지 않았다. Cell당 반복 1회이고 JIT·host 부하·순서 변동이 있을 수 있으므로 보편적·통계적 speedup은 주장하지 않는다. 정확한 후보 공간·cost model·runtime legality에 대한 기존 계약 밖의 보장은 없다.

## 9. 이번 결과로 정한 후속 개선 우선순위

1. GLM은 초기 feasible plan과 U 개선 region을 먼저 점검한다. 현재 LB는 이미 충분히 강하므로 동일한 U를 둔 채 LB 계산만 더 반복하는 방향은 3%·5% 미달을 해결하지 못한다.
2. 나머지 성공 사례는 초기화·component 준비·projection을 포함한 전체 planner 비용을 줄이는 방향으로 평가한다. 특히 실제 증분 후 인증하면서 Global보다 빠른 횟수는 mixed 28개, public 9개뿐이므로 초기 bound 성공과 증분 자체의 이득을 분리해야 한다.
3. GLM lowering의 consumer 모호성은 별도 correctness 수정 대상으로 남긴다. Privacy/placement 규칙을 우회하여 성공률을 올리는 방식은 사용하지 않는다.

위 항목은 이번 사후 결과를 바탕으로 한 후속 방향이다. 새 최적화 구현·성능 검증 결과로 주장하지 않는다.

## 10. 산출물과 절대 경로

- 최종 보고서: `/home/mchoi/so007-anytime-incremental-20260908/docs/ANYTIME_PRIVACY_COMPARISON_2026-09-09_KO.md`
- 중간 보고서(347/512 시점 보존): `/home/mchoi/so007-anytime-incremental-20260908/docs/ANYTIME_PRIVACY_PROGRESS_2026-09-09_KO.md`
- 전체 비교 JSON: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/privacy-comparison-v1.json`
- 목표·workload별 상세 표: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/privacy-comparison-tables-v1.md`
- 시간·작업량 추가 집계: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/privacy-phase-summary-v1.json`
- 오차 분해·실제 실패 예외·로그 hash: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/privacy-posthoc-diagnostics-v1.json`
- 최종 보존·planning-only 확인: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/final-postflight-v1.json`
- 원본 audit: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/main-mixed-audit.json`, `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/main-public-audit.json`
- Mixed raw/receipt/analysis: `/home/mchoi/so007-anytime-privacy-evidence-20260909/native/runs/privacy-mixed-v1/`
- Public raw/receipt/analysis: `/home/mchoi/so007-anytime-privacy-evidence-20260909/native/runs/privacy-public-v1/`
- 독립 fixture 검토: `/home/mchoi/so007-anytime-privacy-evidence-20260909/reviews/privacy-fixture-review.md`

구현 commit: `db092e3fea85789b28e4e4570f14f9ffc48a0036`. JAR SHA-256: `a9c8afd660d5fdff9a3149a9748646a79a639a36611955465570054b6caa3b1f`. 보고서와 증거는 로컬 workspace와 so007의 같은 절대 경로에 보존한다.
