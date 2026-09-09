**Mixed privacy: 네 단계 planning 비교 및 Regional 최초 오차 인증 — 5회 반복 보고서**

작성일: 2026-09-09. so007(`dams-so007`)의 native JVM planning-only 실행. 선언한 160회 중 150회 정상 완료, paired oracle 검증 75쌍. 모든 시도를 원본에 보존했다.

계측 검토 중 종료점이 비대칭인 첫 배치를 중단했다. Global만 selection materialization을 추가로 포함한 것이 원인이었다. 이전 배치의 완료 37회와 중단 1회는 원본을 보존하고 아래 정식 160회 통계에서 제외했다. 수정은 Global 관측 marker 위치뿐이며 알고리즘은 동일하다. 이 제외는 관측 시간이나 성능 결과에 따른 선별이 아니다.

X는 PRIVATE_AGGREGATE, Y가 있는 입력은 PUBLIC이다. ML training 10개, P1·P2, 기존 SliceLine 4개 데이터셋으로 총 16입력이다. 각 입력을 5회 반복했으며, 매 반복 Regional을 한 번 실행해 Regional 완료·최초 LB·남은 exact의 세 상태를 관측하고 독립 Global exact를 한 번 실행했다. 따라서 JVM은 160개이고 각 단계의 관측은 최대 5개다. 세 Regional 관측은 한 실행에 속하며 독립 알고리즘 실험 3개로 세지 않는다.

주요 비교값은 **같은 FedPlanner 시작점부터 해당 품질의 계획이 준비될 때까지의 누적 시간**이다. Regional 완료는 canonical 비용 검증 직후, LB는 최초 전역 인증 완료, 남은 exact는 모든 coupling 복원 및 canonical parity 완료, Global은 exact optimizer의 canonical parity 완료 직후다. 두 exact 관측 모두 selection materialization 이전이다. Regional의 지역 DP 시간만을 Global의 전체 planner 시간과 비교하지 않는다.

**전처리 비교의 한계:** 두 경로에서 끈 것은 singleton compaction이다. Global은 exact domain reduction/동등 상태 quotient를 계속 수행하지만, Regional의 compact-off 지역 solve는 해당 reducer를 사용하지 않고 일반 compile한다. Regional에도 region 밖 assignment 치환 등의 준비는 있다. 따라서 이번 표는 현재 두 구현 경로의 실제 planning 비용 비교이며, 전처리를 동일하게 맞춰 지역 분할 방식만 비교한 ablation이 아니다. 성능 차이를 Regional의 분할 방식 하나로 귀속하지 않는다.

5회 모두 최초 LB에서 목표를 인증한 workload는 다음과 같다. 5%: pca, als, glm, gmm-vvi, sliceline-kdd98. 3%: pca, glm, gmm-vvi, sliceline-kdd98. 1%: pca, gmm-vvi.

남은 exact까지의 누적 준비 시간이 Global보다 5회 모두 짧은 workload: P1_FULL. 5회 모두 긴 workload: steplm, gnmf. 반복에 따라 승패가 바뀐 workload: pca, lm, als, kmeans, logreg, l2svm, glm, gmm-vvi, sliceline-adult, sliceline-covtype, sliceline-kdd98, sliceline-uscensus. 따라서 모든 workload에 대한 일괄적인 속도 우위는 주장하지 않는다.

| Workload | Regional 중앙값(초) | + LB 중앙값(초) | + 남은 exact 중앙값(초) | Global exact 중앙값(초) | 네 단계 관측 수 |
| --- | ---: | ---: | ---: | ---: | --- |
| pca | 0.325 | 0.422 | 0.494 | 0.475 | 5/5/5/5 (각 5회 중) |
| lm | 0.587 | 0.701 | 0.904 | 0.852 | 5/5/5/5 (각 5회 중) |
| als | 0.285 | 0.380 | 0.449 | 0.531 | 5/5/5/5 (각 5회 중) |
| kmeans | 0.503 | 0.682 | 0.851 | 0.804 | 5/5/5/5 (각 5회 중) |
| logreg | 1.021 | 1.281 | 1.569 | 1.438 | 5/5/5/5 (각 5회 중) |
| l2svm | 0.809 | 1.026 | 1.223 | 0.780 | 5/5/5/5 (각 5회 중) |
| steplm | 3.068 | 3.386 | 3.783 | 1.403 | 5/5/5/5 (각 5회 중) |
| glm | 6.944 | 7.955 | 10.240 | 9.472 | 5/5/5/5 (각 5회 중) |
| gnmf | 0.599 | 0.683 | 0.745 | 0.399 | 5/5/5/5 (각 5회 중) |
| gmm-vvi | 0.797 | 1.002 | 1.390 | 1.289 | 5/5/5/5 (각 5회 중) |
| P1_FULL | 2.322 | 2.883 | 4.122 | 5.759 | 5/5/5/5 (각 5회 중) |
| P2_PREP | — | — | — | — | 0/0/0/0 (각 5회 중) |
| sliceline-adult | 1.132 | 1.398 | 1.807 | 1.762 | 5/5/5/5 (각 5회 중) |
| sliceline-covtype | 1.105 | 1.323 | 1.741 | 1.700 | 5/5/5/5 (각 5회 중) |
| sliceline-kdd98 | 1.109 | 1.385 | 1.832 | 1.750 | 5/5/5/5 (각 5회 중) |
| sliceline-uscensus | 1.375 | 1.598 | 2.056 | 1.814 | 5/5/5/5 (각 5회 중) |

—는 해당 단계의 검증된 관측이 없다는 뜻이다. 실패/timeout의 이전 trace가 남아도 정상 FINAL과 모델 binding을 확인하지 못했으면 성공한 시간·인증서로 승격하지 않는다. 각 열은 관측된 성공값의 중앙값이며 미관측 건수는 별도로 표시한다. 서로 다른 관측 분모의 시간 우위를 단정하지 않는다.

Regional의 처음 오차 인증은 U를 최초 feasible Regional 계획의 비용, L을 전체 encoded model의 첫 lower bound로 두고 계산했다. L≤C*≤U이므로 실제 modeled regret U−C*는 U−L 이하이며, L>0이면 상대 regret는 보수적으로 계산한 (U−L)/L 이하이다. 아래 “최초 보장”은 이 인증 상한이다. “실제 오차”는 독립 Global C*를 얻은 뒤 계산한 사후 값이다.

| Workload | 최초 인증 상대 오차 상한: 5회 범위 | Regional 실제 상대 오차: oracle 확인 범위 | 첫 LB 인증 수 | 5% 달성 | 3% 달성 | 1% 달성 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| pca | 0.0514% | 0.0095% | 5/5 | 5/5 | 5/5 | 5/5 |
| lm | 84.1977% | 77.9902% | 5/5 | 0/5 | 0/5 | 0/5 |
| als | 3.9820% | 0.0000% | 5/5 | 5/5 | 0/5 | 0/5 |
| kmeans | 5.9983% | 2.2166% | 5/5 | 0/5 | 0/5 | 0/5 |
| logreg | 86.8696% | 62.9833% | 5/5 | 0/5 | 0/5 | 0/5 |
| l2svm | 76.0140% | 0.0000% | 5/5 | 0/5 | 0/5 | 0/5 |
| steplm | 380.2959% | 0.1003% | 5/5 | 0/5 | 0/5 | 0/5 |
| glm | 1.6946% | 0.2935% | 5/5 | 5/5 | 5/5 | 0/5 |
| gnmf | 148.2586% | 0.0000% | 5/5 | 0/5 | 0/5 | 0/5 |
| gmm-vvi | 0.9724% | 0.1687% | 5/5 | 5/5 | 5/5 | 5/5 |
| P1_FULL | 326.1236% | 0.0178% | 5/5 | 0/5 | 0/5 | 0/5 |
| P2_PREP | — | — | 0/5 | 0/5 | 0/5 | 0/5 |
| sliceline-adult | 7.0780% | 0.0000% | 5/5 | 0/5 | 0/5 | 0/5 |
| sliceline-covtype | 7.2222% | 0.0000% | 5/5 | 0/5 | 0/5 | 0/5 |
| sliceline-kdd98 | 1.1752% | 0.0000% | 5/5 | 5/5 | 5/5 | 0/5 |
| sliceline-uscensus | 6.9203% | 0.0000% | 5/5 | 0/5 | 0/5 | 0/5 |

최초 LB만으로 5% 인증: 25/80 시도. 실패·시간 초과·LB 미완료도 전체 80개 분모에 포함한다.
최초 LB만으로 3% 인증: 20/80 시도. 실패·시간 초과·LB 미완료도 전체 80개 분모에 포함한다.
최초 LB만으로 1% 인증: 10/80 시도. 실패·시간 초과·LB 미완료도 전체 80개 분모에 포함한다.

계획 품질과 인증 품질은 실제로 다른 문제였다. l2svm, gnmf, sliceline-adult, sliceline-covtype, sliceline-uscensus에서는 Regional이 5회 모두 Global 최적 비용과 같았는데 첫 인증 상한은 5%보다 컸다. 이 경우 큰 gap의 원인은 약한 LB다. 반면 lm, logreg에서는 Regional의 실제 modeled regret 자체가 5%보다 컸으므로, 그 계획을 유지한 채 LB만 높여 5% 인증을 얻을 수 없다.

이 campaign은 epsilon=0으로 남은 exact까지 관측했다. 최초 LB에서 3%·5%를 충족한 경우는 그 시각에 해당 인증서를 이용할 수 있었다는 뜻이다. 그 목표에서 즉시 반환·계획 방출까지 완료하는 별도 early-stop JVM을 실행한 것은 아니다. 실제 workload 실행시간이나 cost model의 예측 오차를 인증한 결과도 아니다.

| Workload | 초기 U 범위(modeled ms) | 초기 L 범위(modeled ms) | 절대 인증 상한 U−L 범위(modeled ms) |
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

5회 반복의 변동은 아래처럼 함께 보고한다. 평균±표준편차는 성공 관측에 대한 기술통계이며 신뢰구간이 아니다. 반복 5회만으로 통계적 reliability나 일반적인 speedup을 보장하지 않는다. 실행은 새 JVM을 순차로 띄웠고, workload 및 repetition에 따라 Global/Regional 실행 순서를 번갈아 배치했다. 알고리즘 seed는 고정했다. JIT나 호스트 부하가 변동에 기여한 정도를 분리하는 별도 실험은 하지 않았다.

| Workload | 관측 단계 | 평균 ± 표준편차(초) | 최소 ~ 최대(초) | 5개 원본 관측값(초, repetition 순) |
| --- | --- | ---: | ---: | --- |
| pca | Regional | 0.319 ± 0.044 | 0.273 ~ 0.382 | 0.273, 0.325, 0.331, 0.382, 0.283 |
| pca | Regional + LB | 0.423 ± 0.058 | 0.366 ~ 0.515 | 0.366, 0.422, 0.430, 0.515, 0.383 |
| pca | Regional + LB + 남은 exact | 0.500 ± 0.070 | 0.436 ~ 0.613 | 0.436, 0.494, 0.506, 0.613, 0.450 |
| pca | Global exact | 0.458 ± 0.096 | 0.335 ~ 0.586 | 0.586, 0.397, 0.475, 0.497, 0.335 |
| lm | Regional | 0.647 ± 0.133 | 0.525 ~ 0.802 | 0.525, 0.587, 0.542, 0.802, 0.779 |
| lm | Regional + LB | 0.791 ± 0.173 | 0.640 ~ 0.993 | 0.640, 0.701, 0.658, 0.993, 0.966 |
| lm | Regional + LB + 남은 exact | 1.007 ± 0.216 | 0.817 ~ 1.250 | 0.817, 0.904, 0.833, 1.250, 1.231 |
| lm | Global exact | 0.730 ± 0.229 | 0.473 ~ 0.937 | 0.937, 0.899, 0.473, 0.488, 0.852 |
| als | Regional | 0.334 ± 0.077 | 0.272 ~ 0.433 | 0.400, 0.278, 0.285, 0.433, 0.272 |
| als | Regional + LB | 0.440 ± 0.101 | 0.357 ~ 0.563 | 0.538, 0.363, 0.380, 0.563, 0.357 |
| als | Regional + LB + 남은 exact | 0.514 ± 0.118 | 0.418 ~ 0.652 | 0.631, 0.419, 0.449, 0.652, 0.418 |
| als | Global exact | 0.524 ± 0.130 | 0.371 ~ 0.668 | 0.531, 0.633, 0.668, 0.371, 0.418 |
| kmeans | Regional | 0.467 ± 0.108 | 0.347 ~ 0.582 | 0.503, 0.360, 0.347, 0.582, 0.543 |
| kmeans | Regional + LB | 0.663 ± 0.125 | 0.513 ~ 0.798 | 0.682, 0.556, 0.513, 0.798, 0.765 |
| kmeans | Regional + LB + 남은 exact | 0.855 ± 0.126 | 0.659 ~ 0.968 | 0.851, 0.837, 0.659, 0.968, 0.962 |
| kmeans | Global exact | 0.784 ± 0.083 | 0.686 ~ 0.866 | 0.855, 0.804, 0.706, 0.686, 0.866 |
| logreg | Regional | 1.184 ± 0.303 | 0.929 ~ 1.631 | 1.021, 1.364, 1.631, 0.929, 0.973 |
| logreg | Regional + LB | 1.454 ± 0.334 | 1.178 ~ 1.944 | 1.281, 1.654, 1.944, 1.178, 1.212 |
| logreg | Regional + LB + 남은 exact | 1.775 ± 0.397 | 1.469 ~ 2.399 | 1.569, 1.943, 2.399, 1.469, 1.497 |
| logreg | Global exact | 1.680 ± 0.353 | 1.406 ~ 2.084 | 2.084, 2.047, 1.406, 1.425, 1.438 |
| l2svm | Regional | 0.740 ± 0.208 | 0.482 ~ 0.942 | 0.942, 0.809, 0.559, 0.908, 0.482 |
| l2svm | Regional + LB | 0.961 ± 0.265 | 0.649 ~ 1.251 | 1.251, 1.026, 0.721, 1.158, 0.649 |
| l2svm | Regional + LB + 남은 exact | 1.175 ± 0.311 | 0.810 ~ 1.518 | 1.518, 1.223, 0.902, 1.421, 0.810 |
| l2svm | Global exact | 0.798 ± 0.078 | 0.694 ~ 0.889 | 0.780, 0.768, 0.889, 0.694, 0.861 |
| steplm | Regional | 3.187 ± 0.542 | 2.774 ~ 4.130 | 2.774, 4.130, 3.068, 3.071, 2.892 |
| steplm | Regional + LB | 3.514 ± 0.513 | 3.094 ~ 4.389 | 3.094, 4.389, 3.386, 3.494, 3.209 |
| steplm | Regional + LB + 남은 exact | 4.019 ± 0.500 | 3.622 ~ 4.799 | 3.622, 4.799, 3.783, 4.233, 3.657 |
| steplm | Global exact | 1.491 ± 0.150 | 1.365 ~ 1.718 | 1.570, 1.397, 1.403, 1.718, 1.365 |
| glm | Regional | 7.285 ± 0.848 | 6.803 ~ 8.797 | 6.895, 6.988, 8.797, 6.944, 6.803 |
| glm | Regional + LB | 8.554 ± 1.385 | 7.860 ~ 11.031 | 7.955, 7.996, 11.031, 7.930, 7.860 |
| glm | Regional + LB + 남은 exact | 10.897 ± 1.495 | 10.144 ~ 13.570 | 10.220, 10.312, 13.570, 10.240, 10.144 |
| glm | Global exact | 10.000 ± 0.938 | 9.171 ~ 11.378 | 9.418, 9.171, 10.561, 11.378, 9.472 |
| gnmf | Regional | 0.609 ± 0.133 | 0.474 ~ 0.823 | 0.474, 0.526, 0.823, 0.623, 0.599 |
| gnmf | Regional + LB | 0.702 ± 0.163 | 0.548 ~ 0.970 | 0.548, 0.600, 0.970, 0.708, 0.683 |
| gnmf | Regional + LB + 남은 exact | 0.783 ± 0.176 | 0.632 ~ 1.082 | 0.632, 0.683, 1.082, 0.771, 0.745 |
| gnmf | Global exact | 0.406 ± 0.025 | 0.381 ~ 0.443 | 0.381, 0.388, 0.420, 0.399, 0.443 |
| gmm-vvi | Regional | 0.819 ± 0.083 | 0.742 ~ 0.959 | 0.797, 0.781, 0.815, 0.959, 0.742 |
| gmm-vvi | Regional + LB | 1.017 ± 0.097 | 0.918 ~ 1.175 | 1.023, 0.968, 1.002, 1.175, 0.918 |
| gmm-vvi | Regional + LB + 남은 exact | 1.415 ± 0.102 | 1.314 ~ 1.580 | 1.434, 1.358, 1.390, 1.580, 1.314 |
| gmm-vvi | Global exact | 1.368 ± 0.177 | 1.236 ~ 1.668 | 1.260, 1.236, 1.387, 1.668, 1.289 |
| P1_FULL | Regional | 2.251 ± 0.129 | 2.063 ~ 2.372 | 2.063, 2.322, 2.325, 2.174, 2.372 |
| P1_FULL | Regional + LB | 2.819 ± 0.121 | 2.681 ~ 2.938 | 2.681, 2.898, 2.938, 2.696, 2.883 |
| P1_FULL | Regional + LB + 남은 exact | 4.057 ± 0.116 | 3.927 ~ 4.154 | 3.934, 4.147, 4.154, 3.927, 4.122 |
| P1_FULL | Global exact | 5.759 ± 0.180 | 5.548 ~ 6.010 | 5.548, 5.759, 6.010, 5.844, 5.636 |
| P2_PREP | Regional | — ± — | — ~ — | —, —, —, —, — |
| P2_PREP | Regional + LB | — ± — | — ~ — | —, —, —, —, — |
| P2_PREP | Regional + LB + 남은 exact | — ± — | — ~ — | —, —, —, —, — |
| P2_PREP | Global exact | — ± — | — ~ — | —, —, —, —, — |
| sliceline-adult | Regional | 1.114 ± 0.114 | 0.999 ~ 1.268 | 1.132, 1.167, 1.268, 1.006, 0.999 |
| sliceline-adult | Regional + LB | 1.378 ± 0.121 | 1.248 ~ 1.497 | 1.398, 1.497, 1.490, 1.256, 1.248 |
| sliceline-adult | Regional + LB + 남은 exact | 1.808 ± 0.136 | 1.660 ~ 1.955 | 1.807, 1.955, 1.933, 1.684, 1.660 |
| sliceline-adult | Global exact | 1.975 ± 0.426 | 1.556 ~ 2.608 | 2.203, 1.744, 1.762, 2.608, 1.556 |
| sliceline-covtype | Regional | 1.257 ± 0.233 | 1.073 ~ 1.555 | 1.087, 1.555, 1.465, 1.105, 1.073 |
| sliceline-covtype | Regional + LB | 1.499 ± 0.274 | 1.267 ~ 1.828 | 1.323, 1.828, 1.769, 1.310, 1.267 |
| sliceline-covtype | Regional + LB + 남은 exact | 1.939 ± 0.296 | 1.703 ~ 2.288 | 1.741, 2.288, 2.238, 1.727, 1.703 |
| sliceline-covtype | Global exact | 1.752 ± 0.209 | 1.602 ~ 2.110 | 1.742, 2.110, 1.606, 1.602, 1.700 |
| sliceline-kdd98 | Regional | 1.141 ± 0.112 | 1.033 ~ 1.329 | 1.144, 1.329, 1.109, 1.033, 1.092 |
| sliceline-kdd98 | Regional + LB | 1.408 ± 0.125 | 1.285 ~ 1.615 | 1.413, 1.615, 1.345, 1.285, 1.385 |
| sliceline-kdd98 | Regional + LB + 남은 exact | 1.849 ± 0.144 | 1.703 ~ 2.075 | 1.832, 2.075, 1.751, 1.703, 1.882 |
| sliceline-kdd98 | Global exact | 1.959 ± 0.419 | 1.611 ~ 2.596 | 1.611, 1.750, 2.173, 1.666, 2.596 |
| sliceline-uscensus | Regional | 1.298 ± 0.219 | 1.008 ~ 1.566 | 1.150, 1.391, 1.008, 1.375, 1.566 |
| sliceline-uscensus | Regional + LB | 1.571 ± 0.260 | 1.272 ~ 1.962 | 1.409, 1.616, 1.272, 1.598, 1.962 |
| sliceline-uscensus | Regional + LB + 남은 exact | 2.190 ± 0.422 | 1.687 ~ 2.811 | 2.366, 2.056, 1.687, 2.028, 2.811 |
| sliceline-uscensus | Global exact | 1.953 ± 0.287 | 1.732 ~ 2.402 | 2.402, 1.732, 2.075, 1.742, 1.814 |

동일 repetition의 Global과 비교한 paired 비율도 보존했다. 아래 값은 해당 단계의 누적 시간 / Global의 계획 준비 시간의 중앙값이며,1보다 작으면 그 repetition에서 더 일찍 준비됐다. 네 방식 전체의 독립 end-to-end 성능 비율로 해석하지 않는다.

| Workload | Regional / Global | + LB / Global | + 남은 exact / Global | 검증된 paired 반복 수 |
| --- | ---: | ---: | ---: | ---: |
| pca | 0.770 | 1.037 | 1.234 | 5/5 |
| lm | 0.914 | 1.133 | 1.445 | 5/5 |
| als | 0.649 | 0.853 | 0.998 | 5/5 |
| kmeans | 0.588 | 0.798 | 1.041 | 5/5 |
| logreg | 0.666 | 0.827 | 1.031 | 5/5 |
| l2svm | 1.054 | 1.337 | 1.593 | 5/5 |
| steplm | 2.119 | 2.352 | 2.680 | 5/5 |
| glm | 0.732 | 0.845 | 1.085 | 5/5 |
| gnmf | 1.356 | 1.546 | 1.761 | 5/5 |
| gmm-vvi | 0.587 | 0.722 | 1.019 | 5/5 |
| P1_FULL | 0.387 | 0.489 | 0.709 | 5/5 |
| P2_PREP | — | — | — | 0/5 |
| sliceline-adult | 0.642 | 0.802 | 1.067 | 5/5 |
| sliceline-covtype | 0.690 | 0.818 | 1.078 | 5/5 |
| sliceline-kdd98 | 0.620 | 0.771 | 1.022 | 5/5 |
| sliceline-uscensus | 0.789 | 0.917 | 1.164 | 5/5 |

실제로 끝까지 실행한 두 JVM 방법의 기존 전체 FedPlanner 시간은 다음과 같다. 여기에는 계획 선택 뒤 진단 trace·projection·emission·receipt 검증까지 포함된다. 세 Regional 중간 상태는 별도로 emission하지 않았으므로 standalone Regional 및 Regional+LB의 전체 종료 시간은 이번 관측으로 직접 측정하지 않았다.

| Workload | Global 전체 planner 중앙값(초) | Regional + LB + 남은 exact 전체 planner 중앙값(초) | 정상 JVM Global / Hybrid | Hybrid exact 완료 |
| --- | ---: | ---: | ---: | ---: |
| pca | 0.722 | 0.624 | 5/5, 5/5 | 5/5 |
| lm | 1.044 | 1.103 | 5/5, 5/5 | 5/5 |
| als | 0.763 | 0.626 | 5/5, 5/5 | 5/5 |
| kmeans | 1.101 | 1.187 | 5/5, 5/5 | 5/5 |
| logreg | 1.837 | 1.801 | 5/5, 5/5 | 5/5 |
| l2svm | 1.025 | 1.399 | 5/5, 5/5 | 5/5 |
| steplm | 1.766 | 4.018 | 5/5, 5/5 | 5/5 |
| glm | 11.214 | 11.281 | 5/5, 5/5 | 5/5 |
| gnmf | 0.545 | 0.837 | 5/5, 5/5 | 5/5 |
| gmm-vvi | 1.594 | 1.585 | 5/5, 5/5 | 5/5 |
| P1_FULL | 6.660 | 4.736 | 5/5, 5/5 | 5/5 |
| P2_PREP | — | — | 0/5, 0/5 | 0/5 |
| sliceline-adult | 2.261 | 2.074 | 5/5, 5/5 | 5/5 |
| sliceline-covtype | 2.169 | 2.040 | 5/5, 5/5 | 5/5 |
| sliceline-kdd98 | 2.277 | 2.101 | 5/5, 5/5 | 5/5 |
| sliceline-uscensus | 2.260 | 2.332 | 5/5, 5/5 | 5/5 |

정상 종료와 exact 완료는 별개다. RESOURCE_LIMIT/TIME_BUDGET으로 유효한 incumbent·certificate를 반환한 JVM은 정상 planning 종료일 수 있지만 exact 완료로 세지 않는다. 강제 종료나 lowering 오류는 별도 실패로 남긴다.

| Workload | 실패 횟수(10회 중) | 정상 반환했으나 exact 미완료 | 실패/제한 이유 |
| --- | ---: | ---: | --- |
| pca | 0/10 | 0/5 | 없음 |
| lm | 0/10 | 0/5 | 없음 |
| als | 0/10 | 0/5 | 없음 |
| kmeans | 0/10 | 0/5 | 없음 |
| logreg | 0/10 | 0/5 | 없음 |
| l2svm | 0/10 | 0/5 | 없음 |
| steplm | 0/10 | 0/5 | 없음 |
| glm | 0/10 | 0/5 | 없음 |
| gnmf | 0/10 | 0/5 | 없음 |
| gmm-vvi | 0/10 | 0/5 | 없음 |
| P1_FULL | 0/10 | 0/5 | 없음 |
| P2_PREP | 10/10 | 0/5 | fatal runtime marker in coordinator log: DMLRuntimeException |
| sliceline-adult | 0/10 | 0/5 | 없음 |
| sliceline-covtype | 0/10 | 0/5 | 없음 |
| sliceline-kdd98 | 0/10 | 0/5 | 없음 |
| sliceline-uscensus | 0/10 | 0/5 | 없음 |

P2_PREP는 현재 privacy-safe placement 생성 단계에서 `transformencode`의 두 번째 결과 `M`(`PRIVATE_AGGREGATE`)에 legal physical candidate가 없어 두 방법 모두 search 진입 전에 실패했다. 이는 해당 출력의 현재 encoding/placement 지원 문제이며 Regional이나 LB solver가 느려서 난 실패가 아니다. privacy를 PUBLIC으로 바꾸거나 후보 legality를 완화하지 않았다. 이 사실만으로 원래 P2 연산의 안전한 분산 실행이 원리적으로 불가능하다고 결론내리지 않는다.

| Workload | 첫 LB 추가 시간 중앙값(초) | 남은 exact 추가 시간 중앙값(초) | Global보다 빠른 Regional / +LB / +exact 반복 수 |
| --- | ---: | ---: | --- |
| pca | 0.099 | 0.071 | 5/5, 2/5, 1/5 |
| lm | 0.115 | 0.202 | 3/5, 2/5, 1/5 |
| als | 0.095 | 0.069 | 4/5, 3/5, 3/5 |
| kmeans | 0.196 | 0.170 | 5/5, 4/5, 2/5 |
| logreg | 0.260 | 0.290 | 4/5, 4/5, 2/5 |
| l2svm | 0.217 | 0.196 | 2/5, 2/5, 1/5 |
| steplm | 0.318 | 0.447 | 0/5, 0/5, 0/5 |
| glm | 1.057 | 2.310 | 5/5, 4/5, 1/5 |
| gnmf | 0.084 | 0.083 | 0/5, 0/5, 0/5 |
| gmm-vvi | 0.188 | 0.395 | 5/5, 5/5, 1/5 |
| P1_FULL | 0.576 | 1.239 | 5/5, 5/5, 5/5 |
| P2_PREP | — | — | 0/5, 0/5, 0/5 |
| sliceline-adult | 0.250 | 0.428 | 5/5, 5/5, 2/5 |
| sliceline-covtype | 0.236 | 0.436 | 5/5, 4/5, 1/5 |
| sliceline-kdd98 | 0.269 | 0.419 | 5/5, 5/5, 2/5 |
| sliceline-uscensus | 0.259 | 0.441 | 5/5, 4/5, 2/5 |

추가 시간은 같은 JVM의 두 checkpoint 차이를 먼저 구한 뒤 5회 중앙값을 냈다. 별도 열 중앙값끼리 뺀 값이 아니다. 실패는 시간 승리로 세지 않는다.

| Workload | Global peak RSS 중앙값(MiB) | Hybrid peak RSS 중앙값(MiB) |
| --- | ---: | ---: |
| pca | 555.270 | 579.504 |
| lm | 768.273 | 1097.113 |
| als | 655.273 | 619.785 |
| kmeans | 1003.258 | 828.246 |
| logreg | 1220.816 | 1222.441 |
| l2svm | 912.375 | 983.391 |
| steplm | 1223.039 | 1270.031 |
| glm | 1634.773 | 1656.453 |
| gnmf | 542.426 | 822.266 |
| gmm-vvi | 1204.824 | 1215.156 |
| P1_FULL | 1507.543 | 1496.082 |
| P2_PREP | 435.914 | 435.059 |
| sliceline-adult | 1261.184 | 1261.168 |
| sliceline-covtype | 1262.207 | 1257.082 |
| sliceline-kdd98 | 1253.562 | 1254.863 |
| sliceline-uscensus | 1259.141 | 1257.719 |

Peak RSS는 실제 JVM 실행 전체에 대해 OS가 보고한 값이며 Regional 중간 단계별 메모리가 아니다. 실패한 시도도 RSS가 수집됐으면 포함한다. 이 방법은 큰 MBE table을 모두 보관하는 best-bound frontier 정책을 추가한 실험이 아니다.

최초 LB와 Regional 계획 개선을 구분하려면 내부 시간도 필요하다. 아래는 hybrid 정상 관측 평균이다. Regional seed 시간에는 local DP 준비·solve가 포함되고, 최초 LB 시간에는 initial component 준비·solve가 포함되므로 하위 timer를 다시 합산하지 않는다. 이 표의 시간은 위 공통 시작점의 누적 시간과 다른 범위다.

| Workload | Regional seed 평균(초) | root reduction 평균(초) | 첫 LB 평균(초) | 남은 exact 평균(초) | 재사용 assignment 작업 / 남은 exact 작업 범위 |
| --- | ---: | ---: | ---: | ---: | --- |
| pca | 0.117 | 0.032 | 0.061 | 0.069 | 45 / 18897 |
| lm | 0.414 | 0.044 | 0.087 | 0.205 | 56 / 236932 |
| als | 0.114 | 0.022 | 0.070 | 0.063 | 80 / 1995 |
| kmeans | 0.150 | 0.042 | 0.132 | 0.173 | 152 / 27791 |
| logreg | 0.616 | 0.081 | 0.165 | 0.303 | 243 / 300356 |
| l2svm | 0.360 | 0.056 | 0.144 | 0.200 | 110 / 54568 |
| steplm | 2.715 | 0.057 | 0.243 | 0.485 | 218 / 682997 |
| glm | 6.282 | 0.280 | 0.844 | 2.290 | 933 / 7665029 |
| gnmf | 0.417 | 0.028 | 0.053 | 0.074 | 40 / 76780 |
| gmm-vvi | 0.392 | 0.053 | 0.127 | 0.384 | 162 / 664246 |
| P1_FULL | 0.340 | 0.070 | 0.411 | 1.202 | 635 / 93117 |
| P2_PREP | — | — | — | — | — / — |
| sliceline-adult | 0.310 | 0.039 | 0.194 | 0.412 | 229 / 56903 |
| sliceline-covtype | 0.296 | 0.037 | 0.176 | 0.422 | 229 / 56903 |
| sliceline-kdd98 | 0.309 | 0.039 | 0.195 | 0.423 | 229 / 56903 |
| sliceline-uscensus | 0.383 | 0.044 | 0.191 | 0.596 | 229 / 56903 |

아래 작업량은 5회 범위다. `남은 exact`는 Regional이 선택한 바깥 결정을 고정한 solve가 아니다. LB relaxation에서 빠진 replica equality를 모두 복원하고, 영향을 받는 component를 exact로 닫는다. 기존 LB의 영향을 받지 않는 component만 재사용하며, 결과의 전역 exact성을 canonical 비용과 Global oracle로 검증한다.

| Workload | Regional block assignments | 최초 LB assignments | 남은 exact assignments | Global elimination assignments | 재사용 / 초기 LB component 수 |
| --- | ---: | ---: | ---: | ---: | --- |
| pca | 14509 | 3525 | 18897 | 18942 | 41 / 116 |
| lm | 385716 | 7731 | 236932 | 236988 | 48 / 132 |
| als | 8415 | 1211 | 1995 | 2075 | 69 / 132 |
| kmeans | 23996 | 5580 | 27791 | 27943 | 141 / 273 |
| logreg | 686102 | 20517 | 300356 | 300599 | 212 / 527 |
| l2svm | 269973 | 8641 | 54568 | 54678 | 97 / 240 |
| steplm | 2535945 | 12507 | 682997 | 683215 | 191 / 521 |
| glm | 13748593 | 109188 | 7665029 | 7665962 | 872 / 1878 |
| gnmf | 238269 | 3103 | 76780 | 76820 | 34 / 78 |
| gmm-vvi | 349860 | 16840 | 664246 | 664408 | 116 / 497 |
| P1_FULL | 22878 | 16343 | 93117 | 93752 | 558 / 1202 |
| P2_PREP | — | — | — | — | — / — |
| sliceline-adult | 436486 | 6823 | 56903 | 57132 | 210 / 447 |
| sliceline-covtype | 503754 | 6823 | 56903 | 57132 | 210 / 447 |
| sliceline-kdd98 | 503754 | 6823 | 56903 | 57132 | 210 / 447 |
| sliceline-uscensus | 503754 | 6823 | 56903 | 57132 | 210 / 447 |

Regional 자체가 Global보다 느린 경우의 소스상 원인도 확인했다. Regional은 consumer/direct-input 중심의 겹칠 수 있는 block마다 incumbent 경계로 factor를 축약하고 별도 compile/solve를 수행한다. 서로 다른 block이 Global의 한 번짜리 elimination 결과를 공유하지 않는다. compact=false에서 Regional block은 일반 ExactCategoricalSolver.compile 경로를 쓰지만 Global은 singleton 제거를 끈 뒤에도 domain reduction/quotient를 수행한다. 따라서 작은 지역이라는 이유만으로 총 준비·풀이 작업량이 Global 이하가 되지는 않는다.

현재 Regional seed에는 누적 작업량을 Global 예상 작업량과 비교해 중단하는 정책이 없다. search의 region work limit과 seed 이후 scheduling budget은 그러한 전체 비용 비교 장치와 다르다. 위 카운터는 LB 이전 Regional에서 이미 작업량이 더 큰 사례를 직접 보여주지만, 실제 시간 차이를 중복 factor 처리·전처리 차이·JIT 각각의 기여로 분해한 ablation은 아니다.

구체적인 후속 수정안은 세 단계다. 첫째, singleton compaction을 끈 채 Global과 Regional에 동등한 domain reduction/quotient를 적용하고 원래 상태로의 복원 및 canonical parity를 검증한다. 둘째, Global과 후보 지역의 예상 elimination 작업량을 solve 전에 평가하고 Global 준비 결과는 재사용한다. 셋째, 한 지역 또는 누적 지역 작업이 예산을 넘으면 그 지역을 시작하지 않고 Global 경로를 미리 선택하거나 유효한 incumbent를 반환한다. 예를 들어 L2SVM의 현재 큰 지역 하나는 238,257 assignment 작업으로 Global 전체 54,678보다 컸다. 전처리를 통일하면 이 추정값도 달라지므로, 현재 수치를 새 정책의 성능 결과로 사용하지 않는다.

후속 개선 후보는 지역 solve를 시작하기 전에 예상 table 작업량을 평가하고, 누적 작업 예산을 넘는 지역을 건너뛰거나 저렴한 Global 경로를 미리 선택하는 정책이다. 이번 5회 비교 중에는 이 정책을 구현하거나 적용하지 않았다. 사전 작업량 제한은 실시간 wall-clock 우위나 Global과 같은 계획 품질을 자동 보장하지 않는다. LM/LogReg의 높은 실제 regret는 별도로 region 밖 결정을 고정한 국소 최적화의 한계와 관련된다.

Component 개수의 재사용률과 계산량 절감률은 다르다. 작은 component를 많이 재사용해도 큰 결합 component를 다시 풀면 남은 exact 작업량은 Global에 가까울 수 있다. 카운터는 구현이 방문한 assignment 작업 수이며, CPU instruction 수나 wall-clock에 비례한다는 보장은 없다.

실험 조건은 직전 compact-off pilot과 같은 LAN 비용 환경, seed, common solver 한도다. C→W/W→C 각각 5000 Mbit/s(625 MB/s), latency 설정 0.001초, modeled workers1, JVM -Xmx8g -Xms8g -Xmn800m -XX:ActiveProcessorCount=8을 사용했다. Hybrid scheduling budget은 feasible seed 이후 20초, 각 JVM 외부 한도는 60초다. 준비/solve phase를 강제 중단하는 hard deadline 지원을 추가하지 않았다.

용어를 구체적으로 구분하면 domain reduction은 unary/binary factor에서 finite support가 없는 값의 제거, quotient는 남은 domain의 모든 연결 factor에서 관측이 동일한 원래 decision 값들의 대표화다. 이 과정에서 x의 domain이{A,B,C}에서{B}로 줄어도 변수x는 남을 수 있다. Singleton compaction은 그다음 x=B를 factor에 대입하고 변수x와 해당 scope를 제거하는 별도 단계다. 예를 들어 f(x,y)는 f(B,y)가 된다. 두 전처리는 원래 encoded 모델의 최적값 보존을 전제로 한다. Global의 prepare()는reduce→compile, prepareCompacted()는reduce→singleton substitution→compile이며, Regional compact-off 지역 경로는위reduce를호출하지않는다.

두 방법의 singleton compaction은 모두 껐고, Global 및 hybrid global root의 exact domain reduction/quotient는 유지했다. 이는 모든 전처리·domain 축소를 제거한 raw exact 비교라는 뜻은 아니다. Factor cell 한도 10,000,000, total cell 한도 50,000,000이다. Full Regional의 지역 구성과 seedRevisitPasses=0, width-2 LB 및 기존 remaining-equality closure 정책은 바꾸지 않았다. 입력 X/Y privacy를 제외한 shape·DML·비용 환경을 기존 고정 fixture에서 유지했다. Y 없는 PCA/ALS/KMeans/GNMF/GMM/P1은 Y 비해당이며 SliceLine의 Y는 error vector e다.

이번 코드 변경은 공통 trace clock과 단계 marker만 추가했다. 별도 so007 build의 관련 97개 테스트가 모두 통과했다(failure/error/skip 모두 0). Clock reset, complete cleanup, thread isolation 및 기존 exact/Regional/model parity 테스트를 포함한다. 전체 Java suite와 style/license/RAT는 실행하지 않았다. Native campaign 자체에서 timing 단조성·동일 원점·model fingerprint·canonical bits·oracle enclosure를 검증했다.

동일한 원점은 DMLTranslator의 기존 Compile Phase FedPlanner 시작 시각이다. PlacementAnalysis 바인딩 등 그보다 앞선 compilation은 제외하며, rewriteProgram 안의 물리 모델·비용 표면 준비는 포함한다. ThreadLocal clock은 invocation 시작 시 초기화하고 finally에서 제거한다. 새 marker는 관측용이며 계획 선택이나 legality를 바꾸지 않는다. 두 방법 모두 planner trace를 켰고 이 trace 비용도 각 구간에 포함된다. 따라서 이 수치는 순수 solver kernel 시간이나 trace를 끈 production 성능의 추정치가 아니다.

변경 파일은 DMLTranslator.java, FederatedPlannerTrace.java, LocalPhysicalOptimizer.java, FederatedPlanLocalCost.java, FederatedPlanExact.java 및 새 FederatedPlannerTraceTimingTest.java다. 이전 source/runtime/결과는 별도로 보존했고 이번 build는 `/home/mchoi/so007-regional-fourway-observation-v2-20260909`에 있다. 원본 및 source delta는 아래 evidence에 고정했다.

JAR SHA-256: `01bee10f2929273d25e08b9546f19cd26875897b786dc8e78d786f429e66f444`.
Source manifest SHA-256: `8205777142ef50be93186080015e96b493c951de32859da9727d307d303e76c5`.

보고서 절대 경로: [/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_FOURWAY_MIXED_FIVE_RUNS_2026-09-09_KO.md](/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_FOURWAY_MIXED_FIVE_RUNS_2026-09-09_KO.md).
모든 결과·5회 samples·paired 비교: [/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/fourway-results.json](/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/fourway-results.json).
CSV 원본 관측표: [/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/fourway-observations.csv](/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/fourway-observations.csv).
고정 protocol: [/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/native/protocol-mixed-five.json](/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/native/protocol-mixed-five.json).
원본 logs/commands/receipt/observation: [/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/native/runs/fourway-mixed-lan-five-v2](/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/native/runs/fourway-mixed-lan-five-v2).
빌드 및 test 증거: [/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/native-tests.json](/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/native-tests.json).
소스 delta manifest: [/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/source-patch-manifest.json](/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/source-patch-manifest.json).
실험 후 보존 감사: [/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/final-postflight.json](/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/final-postflight.json).
호스트/JDK/부하 snapshot: [/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/host-snapshot.json](/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909/validation/host-snapshot.json).
제외한 첫 배치의 보존 기록: [/home/mchoi/so007-regional-fourway-mixed-evidence-20260909/validation/stopped-timing-qc.json](/home/mchoi/so007-regional-fourway-mixed-evidence-20260909/validation/stopped-timing-qc.json).
