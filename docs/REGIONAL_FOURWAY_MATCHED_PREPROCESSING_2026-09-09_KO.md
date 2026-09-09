# Regional–Global matched preprocessing: planning-only 3회 반복 보고서

| 문서 정보 | 값 |
| --- | --- |
| 작성일 | 2026-09-09 |
| 서버 / 실행 | so007 (`dams-so007`) / native JVM planning-only |
| 완료 상태 | 보고 대상 96회 중 96회 보존, 정상 완료 90회, 검증된 paired 비교 45/48 |
| 비교 | Regional → 최초 LB → 남은 exact의 중첩 관측과 독립 Global exact |
| 네트워크 / privacy | LAN, X=PRIVATE_AGGREGATE, 적용 가능한 Y=PUBLIC |
| Compaction | Regional·LB·Global singleton compaction 모두 false |

## 1. 질문과 실험 계약

이 실험은 Regional의 다변수 local block과 Global이 모두 `ExactPhysicalReducedSolver.prepare`의 exact domain support/quotient reduction을 사용하도록 맞춘 뒤 계획 준비 시간을 비교한다. Regional은 매 block의 **현재 바깥 assignment로 조건화한 factor**를 줄이고, Global은 **전체 encoded factor graph**를 줄인다. 따라서 reducer 종류와 singleton-compaction 설정은 같지만 입력 문제가 같다는 뜻은 아니다. Regional과 Global이 무조건 같은 graph를 전처리한다고 해석하면 잘못이다.

각 workload·repetition에서 Regional 계열 JVM 하나와 Global JVM 하나를 실행했다. Regional 계열 한 번에서 Regional 계획 준비, 최초 global LB, 모든 빠진 replica equality를 복원한 remaining exact의 세 시각을 순서대로 관측했다. 세 시각은 독립 실행 세 개가 아니다. 보고 범위는 16개 입력 × 두 JVM 방법 × 3회로 총 96회다.

고정 protocol은 원래 5회를 목표로 했지만 사용자가 3회만 보고하도록 변경했다. 따라서 repetition 1–3만 집계하며 4회차 도중 실행을 중단했다. 집계 범위 뒤에 이미 완료된 trial은 12개, 중단 시점의 trial은 1개이며 원본을 보존했다. 이 제외는 측정값이나 성능 결과를 보고 선택한 것이 아니라 사용자 지시에 따른 repetition 범위 cutoff다.

공통 시계는 `DMLTranslator`의 Compile Phase FedPlanner 시작점에서 출발하며 네 단계 모두 selection materialization 전 canonical assignment가 준비된 순간에 끝난다. 전체 planner 시간은 로그의 `Compile Phase FedPlanner` 통계를 별도로 기록한 값이다. 외부 실험 harness의 receipt 검증 시간까지 포함한다고 해석하지 않는다. 캠페인은 epsilon=0으로 exact closure까지 실행했고, 5%·3%·1%는 최초 LB checkpoint에서 관측한 품질 판정이다. 별도 early-stop JVM의 standalone time-to-threshold 측정은 아니다.

실행은 native JVM, LAN 비용 설정, modeled worker 1개이며 Docker, federated worker, workload 본 실행과 reference 실행을 사용하지 않았다. ML 10개, P1/P2, SliceLine 4개를 포함한다. 3회 반복은 변동을 보여 주는 기술통계이고 통계적 신뢰성이나 일반적 speedup을 보장하지 않는다.

## 2. 네 단계의 공통 시계 결과

| Workload | Regional 중앙값(초) | + 최초 LB 중앙값(초) | + 남은 exact 중앙값(초) | Global exact 중앙값(초) | 관측 수 |
| --- | ---: | ---: | ---: | ---: | --- |
| pca | 0.376 | 0.513 | 0.616 | 0.441 | 3/3/3/3 (각 3회 중) |
| lm | 0.764 | 0.923 | 1.225 | 0.559 | 3/3/3/3 (각 3회 중) |
| als | 0.281 | 0.375 | 0.448 | 0.568 | 3/3/3/3 (각 3회 중) |
| kmeans | 0.472 | 0.609 | 0.762 | 0.955 | 3/3/3/3 (각 3회 중) |
| logreg | 1.070 | 1.381 | 1.661 | 1.370 | 3/3/3/3 (각 3회 중) |
| l2svm | 0.703 | 0.870 | 1.108 | 0.651 | 3/3/3/3 (각 3회 중) |
| steplm | 2.718 | 2.983 | 3.419 | 1.439 | 3/3/3/3 (각 3회 중) |
| glm | 5.065 | 6.090 | 8.433 | 10.299 | 3/3/3/3 (각 3회 중) |
| gnmf | 0.709 | 0.799 | 0.898 | 0.437 | 3/3/3/3 (각 3회 중) |
| gmm-vvi | 0.790 | 0.971 | 1.369 | 1.263 | 3/3/3/3 (각 3회 중) |
| P1_FULL | 2.316 | 2.821 | 4.080 | 5.664 | 3/3/3/3 (각 3회 중) |
| P2_PREP | — | — | — | — | 0/0/0/0 (각 3회 중) |
| sliceline-adult | 1.124 | 1.358 | 1.785 | 1.743 | 3/3/3/3 (각 3회 중) |
| sliceline-covtype | 1.209 | 1.409 | 1.863 | 1.631 | 3/3/3/3 (각 3회 중) |
| sliceline-kdd98 | 1.168 | 1.374 | 1.809 | 1.662 | 3/3/3/3 (각 3회 중) |
| sliceline-uscensus | 1.174 | 1.391 | 1.833 | 1.797 | 3/3/3/3 (각 3회 중) |

`—`는 검증된 관측이 없음을 뜻한다. 실패·timeout·resource limit은 전체 시도에서 유지하며, 정상 FINAL과 모델 binding이 없는 partial trace를 성공 시간이나 인증서로 승격하지 않았다.

## 3. 3개 원본 표본과 기술통계

| Workload | 단계 | 평균 ± 표준편차(초) | 최소 ~ 최대(초) | repetition 1,2,3 원본(초) |
| --- | --- | ---: | ---: | --- |
| pca | Regional | 0.375 ± 0.007 | 0.367 ~ 0.382 | 0.376, 0.382, 0.367 |
| pca | Regional + 최초 LB | 0.507 ± 0.012 | 0.494 ~ 0.515 | 0.513, 0.515, 0.494 |
| pca | Regional + LB + 남은 exact | 0.610 ± 0.016 | 0.592 ~ 0.622 | 0.622, 0.616, 0.592 |
| pca | Global exact | 0.458 ± 0.048 | 0.421 ~ 0.512 | 0.512, 0.441, 0.421 |
| lm | Regional | 0.743 ± 0.179 | 0.554 ~ 0.911 | 0.911, 0.764, 0.554 |
| lm | Regional + 최초 LB | 0.891 ± 0.223 | 0.654 ~ 1.097 | 1.097, 0.923, 0.654 |
| lm | Regional + LB + 남은 exact | 1.163 ± 0.305 | 0.831 ~ 1.433 | 1.433, 1.225, 0.831 |
| lm | Global exact | 0.597 ± 0.109 | 0.512 ~ 0.720 | 0.559, 0.512, 0.720 |
| als | Regional | 0.285 ± 0.007 | 0.280 ~ 0.293 | 0.280, 0.281, 0.293 |
| als | Regional + 최초 LB | 0.372 ± 0.008 | 0.362 ~ 0.378 | 0.362, 0.375, 0.378 |
| als | Regional + LB + 남은 exact | 0.443 ± 0.011 | 0.431 ~ 0.450 | 0.431, 0.450, 0.448 |
| als | Global exact | 0.510 ± 0.110 | 0.383 ~ 0.580 | 0.580, 0.383, 0.568 |
| kmeans | Regional | 0.456 ± 0.060 | 0.390 ~ 0.507 | 0.507, 0.390, 0.472 |
| kmeans | Regional + 최초 LB | 0.599 ± 0.057 | 0.537 ~ 0.650 | 0.650, 0.537, 0.609 |
| kmeans | Regional + LB + 남은 exact | 0.764 ± 0.056 | 0.709 ~ 0.820 | 0.820, 0.709, 0.762 |
| kmeans | Global exact | 0.981 ± 0.287 | 0.708 ~ 1.280 | 1.280, 0.955, 0.708 |
| logreg | Regional | 1.104 ± 0.063 | 1.066 ~ 1.177 | 1.066, 1.177, 1.070 |
| logreg | Regional + 최초 LB | 1.379 ± 0.041 | 1.336 ~ 1.418 | 1.381, 1.418, 1.336 |
| logreg | Regional + LB + 남은 exact | 1.658 ± 0.026 | 1.631 ~ 1.683 | 1.661, 1.683, 1.631 |
| logreg | Global exact | 1.442 ± 0.137 | 1.356 ~ 1.601 | 1.370, 1.356, 1.601 |
| l2svm | Regional | 0.699 ± 0.011 | 0.687 ~ 0.707 | 0.687, 0.703, 0.707 |
| l2svm | Regional + 최초 LB | 0.881 ± 0.038 | 0.849 ~ 0.922 | 0.870, 0.922, 0.849 |
| l2svm | Regional + LB + 남은 exact | 1.076 ± 0.065 | 1.001 ~ 1.120 | 1.108, 1.120, 1.001 |
| l2svm | Global exact | 0.679 ± 0.069 | 0.628 ~ 0.757 | 0.757, 0.628, 0.651 |
| steplm | Regional | 2.748 ± 0.079 | 2.688 ~ 2.837 | 2.837, 2.688, 2.718 |
| steplm | Regional + 최초 LB | 3.025 ± 0.082 | 2.974 ~ 3.119 | 3.119, 2.974, 2.983 |
| steplm | Regional + LB + 남은 exact | 3.424 ± 0.083 | 3.343 ~ 3.509 | 3.509, 3.343, 3.419 |
| steplm | Global exact | 1.530 ± 0.179 | 1.415 ~ 1.737 | 1.415, 1.439, 1.737 |
| glm | Regional | 5.071 ± 0.032 | 5.042 ~ 5.105 | 5.105, 5.042, 5.065 |
| glm | Regional + 최초 LB | 6.074 ± 0.031 | 6.039 ~ 6.094 | 6.094, 6.090, 6.039 |
| glm | Regional + LB + 남은 exact | 8.429 ± 0.074 | 8.353 ~ 8.501 | 8.433, 8.501, 8.353 |
| glm | Global exact | 10.108 ± 0.867 | 9.162 ~ 10.864 | 10.299, 9.162, 10.864 |
| gnmf | Regional | 0.635 ± 0.202 | 0.406 ~ 0.791 | 0.406, 0.709, 0.791 |
| gnmf | Regional + 최초 LB | 0.730 ± 0.237 | 0.467 ~ 0.925 | 0.467, 0.799, 0.925 |
| gnmf | Regional + LB + 남은 exact | 0.828 ± 0.265 | 0.535 ~ 1.051 | 0.535, 0.898, 1.051 |
| gnmf | Global exact | 0.430 ± 0.104 | 0.323 ~ 0.531 | 0.323, 0.437, 0.531 |
| gmm-vvi | Regional | 0.838 ± 0.107 | 0.763 ~ 0.961 | 0.763, 0.790, 0.961 |
| gmm-vvi | Regional + 최초 LB | 1.041 ± 0.132 | 0.959 ~ 1.193 | 0.959, 0.971, 1.193 |
| gmm-vvi | Regional + LB + 남은 exact | 1.520 ± 0.268 | 1.362 ~ 1.829 | 1.369, 1.362, 1.829 |
| gmm-vvi | Global exact | 1.341 ± 0.151 | 1.245 ~ 1.515 | 1.245, 1.263, 1.515 |
| P1_FULL | Regional | 2.580 ± 0.499 | 2.268 ~ 3.156 | 2.268, 2.316, 3.156 |
| P1_FULL | Regional + 최초 LB | 3.094 ± 0.489 | 2.804 ~ 3.659 | 2.804, 2.821, 3.659 |
| P1_FULL | Regional + LB + 남은 exact | 4.354 ± 0.493 | 4.058 ~ 4.923 | 4.058, 4.080, 4.923 |
| P1_FULL | Global exact | 5.664 ± 0.057 | 5.607 ~ 5.721 | 5.664, 5.607, 5.721 |
| P2_PREP | Regional | — ± — | — ~ — | —, —, — |
| P2_PREP | Regional + 최초 LB | — ± — | — ~ — | —, —, — |
| P2_PREP | Regional + LB + 남은 exact | — ± — | — ~ — | —, —, — |
| P2_PREP | Global exact | — ± — | — ~ — | —, —, — |
| sliceline-adult | Regional | 1.275 ± 0.342 | 1.035 ~ 1.667 | 1.124, 1.035, 1.667 |
| sliceline-adult | Regional + 최초 LB | 1.547 ± 0.372 | 1.307 ~ 1.975 | 1.358, 1.307, 1.975 |
| sliceline-adult | Regional + LB + 남은 exact | 2.011 ± 0.441 | 1.729 ~ 2.520 | 1.785, 1.729, 2.520 |
| sliceline-adult | Global exact | 1.774 ± 0.149 | 1.643 ~ 1.935 | 1.743, 1.935, 1.643 |
| sliceline-covtype | Regional | 1.163 ± 0.101 | 1.046 ~ 1.232 | 1.209, 1.046, 1.232 |
| sliceline-covtype | Regional + 최초 LB | 1.366 ± 0.103 | 1.248 ~ 1.441 | 1.409, 1.248, 1.441 |
| sliceline-covtype | Regional + LB + 남은 exact | 1.815 ± 0.087 | 1.714 ~ 1.867 | 1.863, 1.714, 1.867 |
| sliceline-covtype | Global exact | 1.897 ± 0.483 | 1.606 ~ 2.455 | 1.606, 1.631, 2.455 |
| sliceline-kdd98 | Regional | 1.150 ± 0.093 | 1.049 ~ 1.232 | 1.049, 1.232, 1.168 |
| sliceline-kdd98 | Regional + 최초 LB | 1.382 ± 0.122 | 1.264 ~ 1.508 | 1.264, 1.508, 1.374 |
| sliceline-kdd98 | Regional + LB + 남은 exact | 1.823 ± 0.124 | 1.706 ~ 1.954 | 1.706, 1.954, 1.809 |
| sliceline-kdd98 | Global exact | 1.707 ± 0.089 | 1.651 ~ 1.810 | 1.651, 1.810, 1.662 |
| sliceline-uscensus | Regional | 1.282 ± 0.328 | 1.023 ~ 1.650 | 1.174, 1.023, 1.650 |
| sliceline-uscensus | Regional + 최초 LB | 1.513 ± 0.357 | 1.233 ~ 1.915 | 1.391, 1.233, 1.915 |
| sliceline-uscensus | Regional + LB + 남은 exact | 1.955 ± 0.364 | 1.668 ~ 2.364 | 1.833, 1.668, 2.364 |
| sliceline-uscensus | Global exact | 1.815 ± 0.250 | 1.575 ~ 2.073 | 1.797, 2.073, 1.575 |

평균과 표준편차는 성공 관측만의 기술통계다. 관측 수는 앞 표와 CSV에 남아 있으므로 결측이 있는 행의 분모를 3로 오인하지 않아야 한다.

## 4. 최초 인증 gap과 실제 Regional regret

Regional 계획의 canonical 비용을 U, 같은 encoded model의 첫 유효 lower bound를 L, 독립 Global 최적 비용을 C*라 두면 `L ≤ C* ≤ U`다. `(U−L)/L`은 L>0일 때 modeled relative regret의 인증 상한이고, `(U−C*)/C*`는 Global 결과를 얻은 뒤 계산한 실제 modeled regret다.

| Workload | 최초 인증 상대 gap 범위 | 실제 Regional regret 범위 | 인증 관측 | 5% | 3% | 1% |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| pca | 0.0514% | 0.0095% | 3/3 | 3/3 | 3/3 | 3/3 |
| lm | 84.1977% | 77.9902% | 3/3 | 0/3 | 0/3 | 0/3 |
| als | 3.9820% | 0.0000% | 3/3 | 3/3 | 0/3 | 0/3 |
| kmeans | 5.9983% | 2.2166% | 3/3 | 0/3 | 0/3 | 0/3 |
| logreg | 86.8696% | 62.9833% | 3/3 | 0/3 | 0/3 | 0/3 |
| l2svm | 76.0140% | 0.0000% | 3/3 | 0/3 | 0/3 | 0/3 |
| steplm | 380.2959% | 0.1003% | 3/3 | 0/3 | 0/3 | 0/3 |
| glm | 1.6946% | 0.2935% | 3/3 | 3/3 | 3/3 | 0/3 |
| gnmf | 148.2586% | 0.0000% | 3/3 | 0/3 | 0/3 | 0/3 |
| gmm-vvi | 0.9724% | 0.1687% | 3/3 | 3/3 | 3/3 | 3/3 |
| P1_FULL | 326.1236% | 0.0178% | 3/3 | 0/3 | 0/3 | 0/3 |
| P2_PREP | — | — | 0/3 | 0/3 | 0/3 | 0/3 |
| sliceline-adult | 7.0780% | 0.0000% | 3/3 | 0/3 | 0/3 | 0/3 |
| sliceline-covtype | 7.2222% | 0.0000% | 3/3 | 0/3 | 0/3 | 0/3 |
| sliceline-kdd98 | 1.1752% | 0.0000% | 3/3 | 3/3 | 3/3 | 0/3 |
| sliceline-uscensus | 6.9203% | 0.0000% | 3/3 | 0/3 | 0/3 | 0/3 |

- 최초 LB에서 5% 인증: **15/48**. 실패·LB 미완료도 48개 분모에 남겼다.
- 최초 LB에서 3% 인증: **12/48**. 실패·LB 미완료도 48개 분모에 남겼다.
- 최초 LB에서 1% 인증: **6/48**. 실패·LB 미완료도 48개 분모에 남겼다.

| Workload | 초기 U 범위(modeled ms) | 초기 L 범위(modeled ms) | U−L 범위(modeled ms) |
| --- | ---: | ---: | ---: |
| pca | 18744.205220 | 18734.569486 | 9.635734 |
| lm | 3032.789468 | 1646.486166 | 1386.303303 |
| als | 399082.209722 | 383799.177734 | 15283.031988 |
| kmeans | 29701.732754 | 28020.954359 | 1680.778395 |
| logreg | 7183.235694 | 3843.983555 | 3339.252138 |
| l2svm | 846.018888 | 480.654339 | 365.364549 |
| steplm | 1063535533.255107 | 221433387.926704 | 842102145.328404 |
| glm | 8969.380144 | 8819.915803 | 149.464341 |
| gnmf | 4651.797110 | 1873.771019 | 2778.026091 |
| gmm-vvi | 184229.010250 | 182454.868687 | 1774.141563 |
| P1_FULL | 27359721.216683 | 6420606.412974 | 20939114.803709 |
| P2_PREP | — | — | — |
| sliceline-adult | 124831.322569 | 116579.836504 | 8251.486065 |
| sliceline-covtype | 254321.172101 | 237190.707484 | 17130.464617 |
| sliceline-kdd98 | 1444977.736270 | 1428194.294675 | 16783.441595 |
| sliceline-uscensus | 309473.249990 | 289442.888636 | 20030.361354 |

큰 인증 gap은 Regional 계획이 나쁘거나 LB가 느슨한 두 경우를 모두 포함할 수 있다. 실제 regret와 certificate slack을 함께 봐야 원인을 구분할 수 있다. 인증 대상은 encoded cost model이며 workload wall-clock이나 cost-model 예측 오차가 아니다.

## 5. 동일 repetition의 Global 대비

| Workload | Regional / Global | + LB / Global | + 남은 exact / Global | 검증 paired 수 |
| --- | ---: | ---: | ---: | ---: |
| pca | 0.866 | 1.169 | 1.397 | 3/3 |
| lm | 1.491 | 1.803 | 2.392 | 3/3 |
| als | 0.516 | 0.666 | 0.790 | 3/3 |
| kmeans | 0.408 | 0.562 | 0.742 | 3/3 |
| logreg | 0.778 | 1.008 | 1.212 | 3/3 |
| l2svm | 1.086 | 1.304 | 1.536 | 3/3 |
| steplm | 1.867 | 2.066 | 2.322 | 3/3 |
| glm | 0.496 | 0.592 | 0.819 | 3/3 |
| gnmf | 1.489 | 1.742 | 1.981 | 3/3 |
| gmm-vvi | 0.625 | 0.770 | 1.099 | 3/3 |
| P1_FULL | 0.413 | 0.503 | 0.728 | 3/3 |
| P2_PREP | — | — | — | 0/3 |
| sliceline-adult | 0.645 | 0.779 | 1.024 | 3/3 |
| sliceline-covtype | 0.642 | 0.766 | 1.051 | 3/3 |
| sliceline-kdd98 | 0.681 | 0.827 | 1.080 | 3/3 |
| sliceline-uscensus | 0.653 | 0.774 | 1.020 | 3/3 |

비율은 같은 repetition의 누적 checkpoint 시간 / Global exact 준비 시간이다. 1보다 작으면 그 repetition에서 더 먼저 준비됐다는 뜻이다. Regional 세 단계는 중첩 관측이므로 네 개의 독립 end-to-end planner를 비교한 값이 아니다.

## 6. matched 캠페인과 이전 v2의 역사적 비교

이전 v2에서는 Global compact-off가 exact reduction/quotient를 사용했지만 Regional compact-off block은 raw compile을 사용했다. 이번 build는 Global 알고리즘을 그대로 두고 Regional block도 같은 reducer API를 거치게 해 그 전처리 비대칭을 수정했다. 다만 두 캠페인은 동시에 무작위 배정한 실험이 아니므로 아래 변화가 reducer 수정의 인과 효과라는 증거는 아니다. JIT, 호스트 부하와 순차 실행 시점이 다를 수 있다.

| Workload | Regional v2(초) | Regional matched(초) | matched/v2 | Global v2(초) | Global matched(초) | matched/v2 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| pca | 0.325 | 0.376 | 1.155 | 0.475 | 0.441 | 0.928 |
| lm | 0.542 | 0.764 | 1.409 | 0.899 | 0.559 | 0.621 |
| als | 0.285 | 0.281 | 0.988 | 0.633 | 0.568 | 0.896 |
| kmeans | 0.360 | 0.472 | 1.312 | 0.804 | 0.955 | 1.187 |
| logreg | 1.364 | 1.070 | 0.785 | 2.047 | 1.370 | 0.669 |
| l2svm | 0.809 | 0.703 | 0.869 | 0.780 | 0.651 | 0.835 |
| steplm | 3.068 | 2.718 | 0.886 | 1.403 | 1.439 | 1.026 |
| glm | 6.988 | 5.065 | 0.725 | 9.418 | 10.299 | 1.094 |
| gnmf | 0.526 | 0.709 | 1.349 | 0.388 | 0.437 | 1.127 |
| gmm-vvi | 0.797 | 0.790 | 0.991 | 1.260 | 1.263 | 1.003 |
| P1_FULL | 2.322 | 2.316 | 0.997 | 5.759 | 5.664 | 0.983 |
| P2_PREP | — | — | — | — | — | — |
| sliceline-adult | 1.167 | 1.124 | 0.963 | 1.762 | 1.743 | 0.989 |
| sliceline-covtype | 1.465 | 1.209 | 0.825 | 1.742 | 1.631 | 0.936 |
| sliceline-kdd98 | 1.144 | 1.168 | 1.022 | 1.750 | 1.662 | 0.950 |
| sliceline-uscensus | 1.150 | 1.174 | 1.021 | 2.075 | 1.797 | 0.866 |

각 `matched/v2` 값은 **새 캠페인의 3회 중앙값 / 이전 v2 repetition 1–3의 중앙값**이다. 서로 다른 캠페인의 같은 repetition끼리 비율을 낸 뒤 그 중앙값을 구한 값이 아니다.

이전 v1의 37개 완료·1개 중단 timing-QC 배치는 Global marker 경계 문제를 찾아 이전 v2를 설계할 때 사용한 역사적 자료다. 이번 matched 캠페인에서 결과를 보고 제외한 run이 아니며, 새 96회 통계의 일부도 아니다.

독립 비교 validator는 두 배치에서 같은 trial key가 모두 정상 완료한 경우의 모델 fingerprint와 exact objective를 검사했다. 공통 정상 trial key는 90개이고, 그중 exact objective 일치는 90개다. Regional 초기 plan/LB 비교는 45개이며, 초기 plan bits 전부 동일=True, 최초 LB 전부 동일=True였다. 이 identity 검사는 시간 차이의 인과성을 증명하지 않는다.

| Workload | Regional work v2 → matched | Regional 준비(초) v2 → matched | Regional solve(초) v2 → matched | Global work v2 → matched |
| --- | --- | --- | --- | --- |
| pca | 14509 → 1717 | 0.019 → 0.080 | 0.059 → 0.011 | 18942 → 18942 |
| lm | 385716 → 61972 | 0.026 → 0.354 | 0.299 → 0.070 | 236988 → 236988 |
| als | 8415 → 1470 | 0.036 → 0.075 | 0.031 → 0.009 | 2075 → 2075 |
| kmeans | 23996 → 4131 | 0.032 → 0.090 | 0.049 → 0.010 | 27943 → 27943 |
| logreg | 686102 → 157227 | 0.099 → 0.482 | 0.435 → 0.069 | 300599 → 300599 |
| l2svm | 269973 → 43226 | 0.068 → 0.238 | 0.239 → 0.051 | 54678 → 54678 |
| steplm | 2535945 → 45505 | 0.079 → 2.214 | 2.318 → 0.046 | 683215 → 683215 |
| glm | 13748593 → 1612747 | 0.112 → 3.591 | 5.719 → 0.339 | 7665962 → 7665962 |
| gnmf | 238269 → 15376 | 0.034 → 0.379 | 0.280 → 0.029 | 76820 → 76820 |
| gmm-vvi | 349860 → 41284 | 0.071 → 0.298 | 0.257 → 0.040 | 664408 → 664408 |
| P1_FULL | 22878 → 5831 | 0.121 → 0.205 | 0.082 → 0.025 | 93752 → 93752 |
| P2_PREP | — → — | — → — | — → — | — → — |
| sliceline-adult | 436486 → 49922 | 0.095 → 0.210 | 0.172 → 0.029 | 57132 → 57132 |
| sliceline-covtype | 503754 → 52722 | 0.094 → 0.248 | 0.155 → 0.027 | 57132 → 57132 |
| sliceline-kdd98 | 503754 → 52722 | 0.099 → 0.229 | 0.146 → 0.025 | 57132 → 57132 |
| sliceline-uscensus | 503754 → 52722 | 0.106 → 0.256 | 0.177 → 0.027 | 57132 → 57132 |

## 7. 내부 준비·solve 시간과 작업량

| Workload | seed 평균 | local DP 평균 | Regional block 준비 평균 | block solve 평균 | root reduction 평균 | 첫 LB 평균 | 남은 exact 평균 | Global 준비 평균 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| pca | 0.138 | 0.125 | 0.081 | 0.011 | 0.029 | 0.089 | 0.093 | 0.201 |
| lm | 0.446 | 0.432 | 0.337 | 0.067 | 0.035 | 0.098 | 0.261 | 0.217 |
| als | 0.111 | 0.101 | 0.074 | 0.009 | 0.011 | 0.065 | 0.062 | 0.253 |
| kmeans | 0.149 | 0.131 | 0.092 | 0.010 | 0.026 | 0.099 | 0.151 | 0.578 |
| logreg | 0.640 | 0.620 | 0.488 | 0.070 | 0.051 | 0.202 | 0.263 | 0.863 |
| l2svm | 0.337 | 0.321 | 0.238 | 0.049 | 0.037 | 0.125 | 0.181 | 0.374 |
| steplm | 2.310 | 2.294 | 2.210 | 0.045 | 0.026 | 0.231 | 0.383 | 0.933 |
| glm | 4.094 | 4.012 | 3.594 | 0.336 | 0.172 | 0.667 | 2.303 | 8.159 |
| gnmf | 0.425 | 0.414 | 0.350 | 0.028 | 0.018 | 0.064 | 0.090 | 0.167 |
| gmm-vvi | 0.414 | 0.398 | 0.317 | 0.044 | 0.039 | 0.140 | 0.463 | 0.764 |
| P1_FULL | 0.370 | 0.295 | 0.203 | 0.025 | 0.039 | 0.394 | 1.222 | 3.565 |
| P2_PREP | — | — | — | — | — | — | — | — |
| sliceline-adult | 0.376 | 0.341 | 0.263 | 0.035 | 0.027 | 0.209 | 0.446 | 0.918 |
| sliceline-covtype | 0.334 | 0.305 | 0.245 | 0.027 | 0.021 | 0.152 | 0.431 | 1.035 |
| sliceline-kdd98 | 0.314 | 0.288 | 0.235 | 0.025 | 0.021 | 0.184 | 0.424 | 0.813 |
| sliceline-uscensus | 0.344 | 0.315 | 0.256 | 0.027 | 0.022 | 0.180 | 0.425 | 0.921 |

위 값은 각 trace가 정의한 내부 범위다. seed는 local DP 준비·solve를 포함하고 첫 LB는 component 준비·solve를 포함하므로 하위 timer를 다시 더하면 중복된다. 공통 시계의 네 checkpoint와도 범위가 다르다. Global은 preparation timer만 별도로 계측돼 있으며 순수 solve timer는 없어 임의로 차감해 만들지 않았다.

| Workload | Regional block assignments | 첫 LB assignments | 남은 exact assignments | Global assignments | 재사용/해결 component | 최대 재해결 원변수 |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| pca | 1717 | 3525 | 18897 | 18942 | 41 / 2 | 121 |
| lm | 61972 | 7731 | 236932 | 236988 | 48 / 3 | 153 |
| als | 1470 | 1211 | 1995 | 2075 | 69 / 6 | 142 |
| kmeans | 4131 | 5580 | 27791 | 27943 | 141 / 5 | 248 |
| logreg | 157227 | 20517 | 300356 | 300599 | 212 / 15 | 504 |
| l2svm | 43226 | 8641 | 54568 | 54678 | 97 / 8 | 273 |
| steplm | 45505 | 12507 | 682997 | 683215 | 191 / 14 | 520 |
| glm | 1612747 | 109188 | 7665029 | 7665962 | 872 / 57 | 1298 |
| gnmf | 15376 | 3103 | 76780 | 76820 | 34 / 2 | 87 |
| gmm-vvi | 41284 | 16840 | 664246 | 664408 | 116 / 6 | 573 |
| P1_FULL | 5831 | 16343 | 93117 | 93752 | 558 / 34 | 1111 |
| P2_PREP | — | — | — | — | — / — | — |
| sliceline-adult | 49922 | 6823 | 56903 | 57132 | 210 / 8 | 593 |
| sliceline-covtype | 52722 | 6823 | 56903 | 57132 | 210 / 8 | 593 |
| sliceline-kdd98 | 52722 | 6823 | 56903 | 57132 | 210 / 8 | 593 |
| sliceline-uscensus | 52722 | 6823 | 56903 | 57132 | 210 / 8 | 593 |

assignment 카운터는 구현이 방문한 작업량이며 CPU instruction이나 wall-clock에 비례한다고 가정할 수 없다. Remaining exact는 빠진 equality가 닿는 current component를 모두 복원해 다시 풀고, 영향받지 않은 component만 재사용한다.

## 8. 전체 planner 시간과 peak RSS

| Workload | Global 전체 planner 중앙값(초) | Hybrid 전체 planner 중앙값(초) | Global peak RSS 중앙값(MiB) | Hybrid peak RSS 중앙값(MiB) |
| --- | ---: | ---: | ---: | ---: |
| pca | 0.634 | 0.823 | 550.652 | 566.617 |
| lm | 0.842 | 1.385 | 765.430 | 906.402 |
| als | 0.831 | 0.600 | 652.695 | 621.598 |
| kmeans | 1.251 | 0.969 | 993.078 | 839.000 |
| logreg | 1.844 | 1.929 | 1215.957 | 1216.715 |
| l2svm | 0.880 | 1.313 | 904.672 | 879.082 |
| steplm | 1.840 | 3.644 | 1210.871 | 1358.445 |
| glm | 12.146 | 9.340 | 1636.586 | 1735.422 |
| gnmf | 0.606 | 1.000 | 559.164 | 667.371 |
| gmm-vvi | 1.551 | 1.579 | 1213.277 | 1235.875 |
| P1_FULL | 6.692 | 4.661 | 1515.539 | 1517.105 |
| P2_PREP | — | — | 446.320 | 441.441 |
| sliceline-adult | 2.253 | 2.051 | 1260.035 | 1254.605 |
| sliceline-covtype | 2.110 | 2.137 | 1242.027 | 1269.414 |
| sliceline-kdd98 | 2.127 | 2.085 | 1260.750 | 1252.906 |
| sliceline-uscensus | 2.307 | 2.098 | 1248.086 | 1277.754 |

Planner 시간 중앙값은 정상 완료 trial만 포함한다. Peak RSS는 값이 수집됐다면 실패 trial도 포함하는 fresh JVM 전체의 Linux `wait4` high-water mark다. Regional 중간 단계별 heap이 아니며 JVM/class loading도 포함한다.

## 9. 실패와 보존 정책

| Workload | 실패(두 방법 합계 6회 중) | Hybrid 정상 반환 중 exact 미완료 | 관측 이유 |
| --- | ---: | ---: | --- |
| pca | 0/6 | 0/3 | 없음 |
| lm | 0/6 | 0/3 | 없음 |
| als | 0/6 | 0/3 | 없음 |
| kmeans | 0/6 | 0/3 | 없음 |
| logreg | 0/6 | 0/3 | 없음 |
| l2svm | 0/6 | 0/3 | 없음 |
| steplm | 0/6 | 0/3 | 없음 |
| glm | 0/6 | 0/3 | 없음 |
| gnmf | 0/6 | 0/3 | 없음 |
| gmm-vvi | 0/6 | 0/3 | 없음 |
| P1_FULL | 0/6 | 0/3 | 없음 |
| P2_PREP | 6/6 | 0/3 | fatal runtime marker in coordinator log: DMLRuntimeException |
| sliceline-adult | 0/6 | 0/3 | 없음 |
| sliceline-covtype | 0/6 | 0/3 | 없음 |
| sliceline-kdd98 | 0/6 | 0/3 | 없음 |
| sliceline-uscensus | 0/6 | 0/3 | 없음 |

P2_PREP의 6회는 모두 search 진입 전 privacy-safe placement 생성에서 실패했다. `transformencode`의 두 번째 결과 M(PRIVATE_AGGREGATE)에 legal physical candidate가 없다는 동일한 관측이며 Regional/LB/exact solver의 속도 실패가 아니다. Privacy나 legality를 완화하지 않았다.

## 10. 정확성 및 재현성 점검

- 검증된 paired 실행: **45/48**. 각 pair의 analysis/cost fingerprint가 같고 모든 checkpoint가 Global oracle을 포함했다.
- remaining exact까지 완료하고 Global과 canonical objective bits가 일치한 pair: **45/45**.
- build test: **98개**, failure=0, error=0, skip=0. 테스트 수는 증거 JSON에서 읽었으며 보고서 코드에 고정하지 않았다.
- Postflight: 입력 161개와 runtime asset 318개를 frozen hash로 재검증했고, output 부재와 실행 중 pilot 부재를 확인했다.
- JAR SHA-256: `58a8e5ddaa912c080bf45c1e34e52fda00424887bc124559b3d37f95d38cae76`. Source manifest SHA-256: `4c88825f6473173b0416d103be1b2418de101858856c0b77f59eaed87ad64665`.

Validator는 Regional block trace가 `exactReduction=true`, `requestedCompact=false`, `compact=false`인지 확인하고 Global preparation도 같은 reduction 및 compact-off 계약인지 확인했다. 이 수정은 전처리 경로를 맞추며 region 구성, legality, canonical objective, LB, remaining-exact 정책, 단계 timing endpoint를 바꾸지 않는다. 새 build의 Global 알고리즘도 이전과 같다.

## 11. 해석 한계

- Regional과 Global은 reducer 구현과 singleton 설정을 공유하지만 각각 conditional block과 full encoded graph를 입력으로 받는다.
- 최초 5%·3%·1% 판정은 epsilon=0 실행의 중간 관측이다. 실제 threshold 반환 overhead를 포함한 standalone time-to-threshold가 아니다.
- 이전 v2와 새 matched batch는 역사적 비교이며 무작위 교차 실험에 의한 인과 추정이 아니다.
- n=3의 평균·표준편차·중앙값은 이 호스트에서 관측한 변동을 기술할 뿐 신뢰구간이나 다른 workload에 대한 보장이 아니다.
- 모든 인증은 동일 encoded modeled cost에 대한 것이며 실제 workload 실행시간의 오차를 인증하지 않는다.

## 12. 원본 증거와 소스

실제 build source 위치는 `/home/mchoi/so007-regional-fourway-matched-preprocessing-20260909`로 기록한다. 아래 코드 링크는 source manifest hash로 검증한 immutable evidence snapshot을 사용하므로 보고서를 읽는 로컬 환경에서도 직접 열 수 있다.

- 최종 보고서: [/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_FOURWAY_MATCHED_PREPROCESSING_2026-09-09_KO.md](/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_FOURWAY_MATCHED_PREPROCESSING_2026-09-09_KO.md)
- Evidence 사본: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/reports/REGIONAL_FOURWAY_MATCHED_PREPROCESSING_2026-09-09_KO.md](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/reports/REGIONAL_FOURWAY_MATCHED_PREPROCESSING_2026-09-09_KO.md)
- 96회 검증 결과: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/fourway-results.json](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/fourway-results.json)
- 모든 trial 관측 CSV: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/fourway-observations.csv](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/fourway-observations.csv)
- 고정 protocol: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/native/protocol-mixed-five.json](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/native/protocol-mixed-five.json)
- raw run 디렉터리: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/native/runs/fourway-mixed-lan-five-matched-v1](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/native/runs/fourway-mixed-lan-five-matched-v1)
- Global raw log 예시: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/native/runs/fourway-mixed-lan-five-matched-v1/trials/fourway-mixed-lan-five-matched-v1_0000_pca_lan_Global/results/fed1/mkl-exact/pca_mkl-exact_fourway-mixed-lan-five-matched-v1_0000_pca_lan_Global.log](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/native/runs/fourway-mixed-lan-five-matched-v1/trials/fourway-mixed-lan-five-matched-v1_0000_pca_lan_Global/results/fed1/mkl-exact/pca_mkl-exact_fourway-mixed-lan-five-matched-v1_0000_pca_lan_Global.log)
- Hybrid raw log 예시: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/native/runs/fourway-mixed-lan-five-matched-v1/trials/fourway-mixed-lan-five-matched-v1_0001_pca_lan_RegionalRemainingExact/results/fed1/mkl-cost/pca_mkl-cost_fourway-mixed-lan-five-matched-v1_0001_pca_lan_RegionalRemainingExact.log](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/native/runs/fourway-mixed-lan-five-matched-v1/trials/fourway-mixed-lan-five-matched-v1_0001_pca_lan_RegionalRemainingExact/results/fed1/mkl-cost/pca_mkl-cost_fourway-mixed-lan-five-matched-v1_0001_pca_lan_RegionalRemainingExact.log)
- build/test 증거: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/native-tests.json](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/native-tests.json)
- postflight: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/final-postflight.json](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/final-postflight.json)
- 이전 v2 독립 비교 결과: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/baseline-comparison.json](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/baseline-comparison.json)
- 이전 v2 repetition 1–3 결과: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/baseline-three-results.json](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/baseline-three-results.json)
- source manifest: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/source-manifest.json](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/source-manifest.json)
- source delta manifest: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/source-patch-manifest.json](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/source-patch-manifest.json)
- 검증한 implementation patch: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/implementation.patch](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/implementation.patch)
- 이전 v2 전체 원본 결과: [/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/fourway-results.json](/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/fourway-results.json)
- 3회 cutoff receipt: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/stopped-after-three.json](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/validation/stopped-after-three.json)
- 역사적 v1 timing-QC: [/home/mchoi/so007-regional-fourway-mixed-evidence-20260909/validation/stopped-timing-qc.json](/home/mchoi/so007-regional-fourway-mixed-evidence-20260909/validation/stopped-timing-qc.json)
- Regional 구현 snapshot: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/source-reference/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/source-reference/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java)
- Global reducer snapshot: [/home/mchoi/so007-regional-fourway-matched-evidence-20260909/source-reference/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java](/home/mchoi/so007-regional-fourway-matched-evidence-20260909/source-reference/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java)
