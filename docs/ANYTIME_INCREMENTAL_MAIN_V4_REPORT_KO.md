# Compact Global과 Incremental Anytime: 3%·5% planning 검증 보고서

## 1. 구현과 평가 범위

Global은 exact-preserving compact reduction 뒤 exact solve를 수행한다. 새 `AnytimeIncremental`은 같은 compact 입력의 global replica relaxation을 만들고, 선택한 consistency가 영향을 주는 component만 다시 풀어 LB를 강화한다. 영향받지 않은 component 결과는 재사용한다. 모든 결과는 원래 legality와 canonical modeled objective를 기준으로 검증한다.

3%·5% 인증을 목표로 한다. 1%는 compact Global exact 사용을 권장하며 이번 비교에는 포함하지 않았다. Threshold를 보고 Global로 자동 전환하는 코드는 추가하지 않았다.

so007에서 Docker를 실행하지 않고 local JVM의 compile-only 경로를 사용했다. Docker 네트워크 profile의 bandwidth/RTT 값만 cost model에 전달했다. 이 실험은 workload 실행시간이나 네트워크 예측 정확도를 검증하지 않는다.

| 항목 | 설정 |
| --- | --- |
| 구현 commit | `6694b7e2362726cf4f283e40c91683adda3d0b76` |
| JAR SHA-256 | `3309a89a2c885151ede32e711fc9f66d3b0e1864bd150fe3a075d8533dfe8900` |
| 본 검증 | 16 inputs × 4 profiles × 2 thresholds × 2 methods = 256 JVM trials |
| 반복 | cell별 paired 1회; 별도 GLM pilot은 본 검증 분모에 합치지 않음 |
| JVM | fresh sequential JVM, 8 GiB heap, ActiveProcessorCount=8 |
| Privacy / workers | PRIVATE_AGGREGATE / modeled worker 1; worker JVM 실행 없음 |
| Anytime 제한 | width 2, 최대 64 actions, 1 probe/action, primal repair 최대 2회 |
| 작업량 / region | work 1m, active original 최대 24; factor/total cells 1m/5m |
| 시간 | after-seed soft budget 20s, 두 방법 outer process watchdog 60s |
| Global 제한 | production compact exact factor/total caps 10m/50m |

두 방법은 같은 모델과 compact/exact 커널을 사용한다. Resource cap까지 같은 실험은 아니다. Global optimum을 Anytime의 온라인 선택이나 초기 incumbent로 전달하지 않는다.

| Profile | coordinator→worker / worker→coordinator (Mbit/s) | RTT (ms) |
| --- | ---: | ---: |
| LAN | 5000 / 5000 | 1 |
| WAN-light | 2500 / 1000 | 10 |
| WAN-mid | 250 / 200 | 100 |
| WAN-heavy | 100 / 100 | 200 |

입력은 PCA, LM, ALS, KMeans, LogReg, L2SVM, StepLM, GLM, GNMF, GMM-VVI, P1_FULL, P2_PREP, Sliceline의 adult/covtype/kdd98/uscensus 네 입력이다. 기존 frozen input을 사용했으며 새 데이터나 CP reference를 생성하지 않았다.

## 2. 선택 정책과 인증 계약

각 후보는 같은 encoded variable의 분리된 replica group들을 묶는 작업이다. 현재 선택된 optimum들의 최빈값과 다른 group 수를 `modalMinority`로 세고, 영향받는 component에 이미 기록된 elimination assignment 수로 나눈 값을 우선순위로 사용한다.

\[S(x)=\frac{\mathrm{modalMinority}(x)}{\max(1,\sum_{c\in touched(x)}\mathrm{cachedAssignments}(c))}.\]

이 값은 예상 이득/비용을 대신하는 휴리스틱이다. 실제 최대 LB 개선 구간이나 최단 시간을 보장하지 않는다. 최빈값 기준은 replica 순서 의존성을 없애지만 모든 argmin tie를 고려하지는 않는다. 기본으로 후보 하나만 exact 시험하여 탐색 자체의 비용을 제한한다.

복원한 equality class는 exact 입력에서 변수 하나로 치환한다. 원래 cost와 hard factor, 상수는 유지하고 trial 성공 후에만 component 및 equality 상태를 원자적으로 교체한다. Primal projection과 제한된 Regional repair에서 얻은 계획은 원래 모델에서 검증한 뒤 U 개선에만 사용한다. Region 밖 incumbent를 고정한 conditional optimum은 global LB로 게시하지 않는다.

\[L_k\le C^*\le U_k,\quad L_{k+1}\ge L_k,\quad U_{k+1}\le U_k.\]

양수 LB에서 보수적으로 계산한 \((U-L)/L\le\tau\)이면 종료한다. \(L=0<U\)는 상대 인증 성공으로 처리하지 않는다.

큰 MBE 중간 table 전체를 장기간 보관하지 않는다. Component의 factor/변수 정보, exact 결과와 assignment는 유지한다. 따라서 대형 component와 다수 factor의 메모리 비용까지 사라지는 것은 아니다. Root 전체 exact shortcut은 호출하지 않으며, relaxation의 모든 equality가 복원된 경우는 partial 성공에서 제외한다.

## 3. 전체 결과

목표 미달, JVM 실패 및 Global oracle 부재도 전체 분모에 남겼다. `목표 도달`은 checkpoint의 판정이고, `독립 검증 성공`은 paired Global과 모델 identity 및 모든 checkpoint를 대조한 결과다.

| 목표 | 전체 Anytime | 목표 도달 | 독립 검증 성공 | 도달했으나 독립 검증 부재 | 초기 LB / incremental / fully restored | 미달·실패 | partial planner 승리 | 실제 incremental 승리 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5% | 64 | 56 | 56 | 0 | 8 / 48 / 0 | 8 | 14 | 7 |
| 3% | 64 | 56 | 56 | 0 | 5 / 51 / 0 | 8 | 10 | 7 |

승리는 해당 paired Global보다 전체 planner 시간이 짧은 경우다. 초기 LB만으로 끝난 성공을 실제 incremental 승리로 세지 않았다. `fully restored`는 전체 relaxation coupling이 이미 복원된 경우이며 이 역시 partial 승리에서 제외한다.

| 목표 | 전체 planner 중앙값 Global / Anytime (s) | paired 검증 분모 | 성공 행만의 launcher TTT 중앙값 (s) | TTT 분모 |
| --- | ---: | ---: | ---: | ---: |
| 5% | 1.149 / 1.447 | 60 | 4.967 | 56 |
| 3% | 1.175 / 1.429 | 60 | 4.701 | 56 |

전체 planner는 초기 모델 구성·compact·초기 bound·seed·refinement 등을 포함한 `CompilePhaseFedPlanner`다. Launcher TTT는 JVM launch부터 첫 인증 checkpoint까지의 관측 시간이다. After-seed elapsed만 Global 전체 시간과 비교하지 않았다. 성공 행만의 TTT에는 선택 편향이 있으므로 위 성공률 및 아래 모든 cell 결과와 함께 해석해야 한다.

| Workload | 5% 검증 성공 / 4 | 3% 검증 성공 / 4 | 초기 / incremental / fully restored | partial 승리 / 8 | 실제 incremental 승리 / 8 | planner 중앙값 G / I (s) | 검증 쌍 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| pca | 4 | 4 | 4 / 4 / 0 | 2 | 1 | 0.502 / 0.609 | 8 |
| lm | 4 | 4 | 0 / 8 / 0 | 4 | 4 | 0.765 / 0.721 | 8 |
| als | 4 | 4 | 2 / 6 / 0 | 4 | 2 | 0.486 / 0.542 | 8 |
| kmeans | 4 | 4 | 0 / 8 / 0 | 1 | 1 | 0.723 / 0.978 | 8 |
| logreg | 4 | 4 | 0 / 8 / 0 | 0 | 0 | 1.051 / 1.361 | 8 |
| l2svm | 4 | 4 | 0 / 8 / 0 | 1 | 1 | 0.618 / 0.798 | 8 |
| steplm | 4 | 4 | 0 / 8 / 0 | 0 | 0 | 1.133 / 2.275 | 8 |
| glm | 4 | 4 | 2 / 6 / 0 | 2 | 0 | 2.952 / 3.241 | 8 |
| gnmf | 4 | 4 | 0 / 8 / 0 | 1 | 1 | 0.598 / 0.771 | 8 |
| gmm-vvi | 4 | 4 | 5 / 3 / 0 | 6 | 1 | 1.279 / 1.135 | 8 |
| P1_FULL | 0 | 0 | 0 / 0 / 0 | 0 | 0 | 3.862 / 3.797 | 8 |
| P2_PREP | 0 | 0 | 0 / 0 / 0 | 0 | 0 | — / — | 0 |
| sliceline-adult | 4 | 4 | 0 / 8 / 0 | 0 | 0 | 1.423 / 1.745 | 8 |
| sliceline-covtype | 4 | 4 | 0 / 8 / 0 | 2 | 2 | 1.432 / 1.683 | 8 |
| sliceline-kdd98 | 4 | 4 | 0 / 8 / 0 | 0 | 0 | 1.433 / 1.637 | 8 |
| sliceline-uscensus | 4 | 4 | 0 / 8 / 0 | 1 | 1 | 1.493 / 1.744 | 8 |

전체 128 paired cell의 목표, gap, modeled regret, Global/Anytime planning 및 launcher TTT, component/equality 수와 peak RSS는 [상세 결과](ANYTIME_INCREMENTAL_RESULTS_MAIN_V4_KO.md)에 있다. 실패 원문과 종료 상태는 `native/runs/main-incremental-v4/analysis/sevenway_analysis.json` 및 각 trial receipt/log에 보존했다.

## 4. GLM에서 측정하며 바꾼 내용

| 버전 | 핵심 변경 | 목표 성공 / 8 | 실제 incremental partial / 8 | WAN Anytime planner 범위 (s) |
| --- | --- | ---: | ---: | ---: |
| V1 | Replica projection seed + equality 한 개씩 복원 | 0 | 0 | 3.588–4.862 |
| V2 | Ordered greedy seed + 변수별 equality batch + projection 재사용 | 8 | 6 | 12.159–22.146 |
| V3 | 복원 equality를 정확히 변수 치환해 component compile 축소 | 8 | 6 | 8.634–11.428 |
| V4 | Modal minority / cached work 우선순위 + 1 probe | 8 | 6 | 3.131–4.360 |

V1은 유효한 인증 구간을 유지했지만 초기 U가 약하고 복원 진전이 느려 모두 목표 미달이었다. V2는 목표를 달성했지만 WAN의 반복 component preparation이 병목이었다. V3의 exact-preserving contraction은 그 비용을 줄였다. V4는 저렴한 후보를 앞세우고 버리는 trial 계산을 줄여 WAN planning을 다시 낮췄다.

이 표는 측정→개선→재측정 이력이다. 특히 V2와 V4는 여러 변경을 함께 포함하므로 각 구성요소의 독립 기여를 분리한 factorial ablation이나 통계적 speedup 증거는 아니다. 각 버전의 Global도 동일 버전 compact+exact 경로에서 독립 측정했다. 이전에 Global만 uncompacted였던 비교는 이 표에 포함하지 않았다.

## 5. 검증, 한계와 재현 경로

로컬과 so007에서 각각 22개 focused 클래스의 190 tests가 실패·오류·skip 없이 통과했다. 7,506개 source 파일을 build 전후 검증했고 두 host의 JAR SHA-256이 같았다. 별도 Python harness 최종 45 tests도 통과했다. 전체 repository test suite는 아니며 build에서 checkstyle/spotless/license/RAT는 생략했다.

완료된 각 campaign의 선언된 모든 trial, 명령·설정·로그·receipt를 원본에서 재검증하고 frozen runtime/input 자산 해시를 대조했다. JVM 성공만으로 목표 성공을 선언하지 않으며, target miss와 oracle 부재를 숨기지 않는다. 작은 exhaustive oracle 회귀는 LB enclosure, 단조성, hard feasibility, canonical parity, cancellation과 resource 실패 후 상태 보존을 검증한다.

성능 한계는 다음과 같다.

- Cell당 본 측정은 1회다. 공유 서버에서 다른 사용자의 작업도 관측했으며 서버 독점이나 통계적 우위를 주장하지 않는다.
- Modal disagreement와 cached work는 실제 gain·시간의 근사치다. Zero-gain 복원을 여러 번 수행할 수 있다.
- Resource-blocked 후보의 key는 replica roots를 기준으로 보존된다. 다른 merge가 component 비용을 바꿔도 같은 key를 자동 재시도하지 않으므로 자원 종료가 보수적일 수 있다.
- Initial component 계산에 별도 누적 work cap이 없고, 실행 중 exact kernel은 강제 중단하지 못한다. 20초는 after-seed soft budget이고 60초 외부 watchdog 종료는 인증 성공이 아니다.
- 작은 compact model은 Global exact 자체가 저렴하여 Anytime 초기화·진단·projection overhead를 회수하기 어렵다. 3%·5% 허용만으로 언제나 Global보다 빨라진다는 보장은 없다.

| 자료 | 절대 경로 |
| --- | --- |
| 상세 구현 | `/home/mchoi/so007-anytime-incremental-20260908/docs/ANYTIME_INCREMENTAL_IMPLEMENTATION_REPORT_KO.md` |
| 전체 paired 결과 | `/home/mchoi/so007-anytime-incremental-20260908/docs/ANYTIME_INCREMENTAL_RESULTS_MAIN_V4_KO.md` |
| Frozen 본 protocol | `/home/mchoi/so007-anytime-incremental-evidence-20260908/native/protocol-main-incremental-v4.json` |
| 본 raw campaign | `/home/mchoi/so007-anytime-incremental-evidence-20260908/native/runs/main-incremental-v4` |
| 집계 JSON | `/home/mchoi/so007-anytime-incremental-evidence-20260908/validation/incremental-final-aggregate.json` |
| 원본 감사 | `/home/mchoi/so007-anytime-incremental-evidence-20260908/validation/main-incremental-v4-audit.json` |
| Java / Python 검증 | `/home/mchoi/so007-anytime-incremental-evidence-20260908/validation/local-tests-v4.json`, `native-tests-v4.json`, `harness-final-tests-v4.json` |

각 trial의 `command.json`에 JVM argv와 네트워크 cost 환경이 있으므로 동일 입력·설정을 재현할 수 있다. 전체 planning-only 재실행은 frozen protocol/context에 새 campaign 이름을 지정해 `native/run_sevenway.py --phase measured`로 수행한다. 기존 evidence directory는 덮어쓰지 않는다.
