# AnytimeTarget 3%/5% planning-only 진단 보고서

- 상태: **수집·계약 검증이 완료된 diagnostic pilot이며 최종 반복 benchmark가 아님**
- Campaign: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-before-fast-v1`
- Phase: `measured`
- 계획/관측/누락 attempt: 32/32/0
- 반복: 1회 진단이면 속도 차이는 기술 통계이며 일반화하지 않는다.

## Threshold별 결과

| 목표 | 방법 | 성공/시도/계획 | 통과 후 미달 | 프로세스 실패 | 누락 | 성공 TTT 중앙값 | Planner 합/중앙값 | 진단·준비 합(통과) | Bound·MBE 합(통과) | Regional 합(통과) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3.0% | AnytimeTarget | 7/8/8 | 1 | 0 | 0 | 4.727 (N=7) | 38.162/3.126 (N=8/8) | 15.893 | 2.612 | 1.623 |
| 3.0% | Global | 8/8/8 | 0 | 0 | 0 | 4.047 (N=8) | 28.071/1.390 (N=8/8) | — | — | — |
| 5.0% | AnytimeTarget | 7/8/8 | 1 | 0 | 0 | 3.559 (N=7) | 37.071/2.544 (N=8/8) | 14.963 | 2.756 | 1.409 |
| 5.0% | Global | 8/8/8 | 0 | 0 | 0 | 3.931 (N=8) | 26.018/1.323 (N=8/8) | — | — | — |

TTT는 JVM 시작부터 AnytimeTarget의 첫 목표 달성 checkpoint 또는 Global exact 결과의 [Physical-CostContributionComplete] 로그 도착까지다. TTT는 성공한 attempt만 사용한다. Planner 합과 중앙값은 관측된 모든 attempt를 사용한다. Phase 합은 receipt를 통과하고 counter가 있는 row에서 계산한다. 진단 시간은 preparation/preflight를 포함하며 Bound 시간은 MBE를 포함한 LB 계산 누적값이다. —는 해당 phase의 계측값이 없음을 뜻하며, 비용이 0이라는 의미가 아니다.

## Global 대비 AnytimeTarget paired timing

| 목표 | Joint 성공/계획 pair | 제외 | Target TTT | Global TTT | TTT 비율 Target/Global | Planner 비율 Target/Global | 제외 이유 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 3.0% | 7/8 | 1 | 4.727 (N=7) | 3.263 (N=7) | 1.303 (N=7) | 1.974 (N=7) | `{"target_miss": 1}` |
| 5.0% | 7/8 | 1 | 3.559 (N=7) | 3.248 (N=7) | 1.096 (N=7) | 1.145 (N=7) | `{"target_miss": 1}` |

비율과 timing은 두 방법이 모두 목표를 달성한 pair에만 계산한다. 제외된 pair는 계획 분모에 남는다.

### Workload·profile별 paired timing

| Workload | Profile | 목표 | Joint 성공/계획 | Target TTT | Global TTT | TTT 비율 Target/Global | 제외 이유 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| glm | lan | 3.0% | 1/1 | 13.009 (N=1) | 20.338 (N=1) | 0.640 (N=1) | `{}` |
| glm | lan | 5.0% | 1/1 | 14.329 (N=1) | 18.473 (N=1) | 0.776 (N=1) | `{}` |
| glm | wan_mid | 3.0% | 0/1 | — | — | — | `{"target_miss": 1}` |
| glm | wan_mid | 5.0% | 0/1 | — | — | — | `{"target_miss": 1}` |
| lm | lan | 3.0% | 1/1 | 4.727 (N=1) | 3.263 (N=1) | 1.449 (N=1) | `{}` |
| lm | lan | 5.0% | 1/1 | 3.559 (N=1) | 3.248 (N=1) | 1.096 (N=1) | `{}` |
| lm | wan_mid | 3.0% | 1/1 | 4.250 (N=1) | 2.908 (N=1) | 1.462 (N=1) | `{}` |
| lm | wan_mid | 5.0% | 1/1 | 3.360 (N=1) | 2.937 (N=1) | 1.144 (N=1) | `{}` |
| pca | lan | 3.0% | 1/1 | 3.015 (N=1) | 3.160 (N=1) | 0.954 (N=1) | `{}` |
| pca | lan | 5.0% | 1/1 | 2.657 (N=1) | 3.034 (N=1) | 0.876 (N=1) | `{}` |
| pca | wan_mid | 3.0% | 1/1 | 2.868 (N=1) | 3.031 (N=1) | 0.946 (N=1) | `{}` |
| pca | wan_mid | 5.0% | 1/1 | 2.566 (N=1) | 2.979 (N=1) | 0.861 (N=1) | `{}` |
| steplm | lan | 3.0% | 1/1 | 9.184 (N=1) | 7.048 (N=1) | 1.303 (N=1) | `{}` |
| steplm | lan | 5.0% | 1/1 | 9.223 (N=1) | 4.614 (N=1) | 1.999 (N=1) | `{}` |
| steplm | wan_mid | 3.0% | 1/1 | 10.312 (N=1) | 4.830 (N=1) | 2.135 (N=1) | `{}` |
| steplm | wan_mid | 5.0% | 1/1 | 9.740 (N=1) | 6.509 (N=1) | 1.496 (N=1) | `{}` |

## Workload·profile·threshold별 결과

| Workload | Profile | 목표 | 방법 | 성공/시도/계획 | 미달 | 실패 | 누락 | 성공 TTT | Planner 중앙값 | 진단·준비 합(통과) | Bound·MBE 합(통과) | Regional 합(통과) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| glm | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 13.009 (N=1) | 4.067 (N=1/1) | 0.000 | 0.493 | 0.000 |
| glm | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 20.338 (N=1) | 10.557 (N=1/1) | — | — | — |
| glm | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 14.329 (N=1) | 3.980 (N=1/1) | 0.000 | 0.500 | 0.000 |
| glm | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 18.473 (N=1) | 9.011 (N=1/1) | — | — | — |
| glm | wan_mid | 3.0% | AnytimeTarget | 0/1/1 | 1 | 0 | 0 | — | 16.144 (N=1/1) | 10.415 | 1.379 | 0.746 |
| glm | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 19.296 (N=1) | 9.473 (N=1/1) | — | — | — |
| glm | wan_mid | 5.0% | AnytimeTarget | 0/1/1 | 1 | 0 | 0 | — | 16.859 (N=1/1) | 10.224 | 1.594 | 0.737 |
| glm | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 18.529 (N=1) | 9.413 (N=1/1) | — | — | — |
| lm | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 4.727 (N=1) | 2.186 (N=1/1) | 0.726 | 0.175 | 0.191 |
| lm | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.263 (N=1) | 1.084 (N=1/1) | — | — | — |
| lm | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 3.559 (N=1) | 1.108 (N=1/1) | 0.238 | 0.092 | 0.007 |
| lm | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.248 (N=1) | 0.967 (N=1/1) | — | — | — |
| lm | wan_mid | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 4.250 (N=1) | 1.559 (N=1/1) | 0.348 | 0.140 | 0.013 |
| lm | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 2.908 (N=1) | 0.648 (N=1/1) | — | — | — |
| lm | wan_mid | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 3.360 (N=1) | 1.061 (N=1/1) | 0.232 | 0.089 | 0.008 |
| lm | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 2.937 (N=1) | 0.670 (N=1/1) | — | — | — |
| pca | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 3.015 (N=1) | 0.761 (N=1/1) | 0.000 | 0.072 | 0.000 |
| pca | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.160 (N=1) | 0.792 (N=1/1) | — | — | — |
| pca | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 2.657 (N=1) | 0.681 (N=1/1) | 0.000 | 0.083 | 0.000 |
| pca | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.034 (N=1) | 0.863 (N=1/1) | — | — | — |
| pca | wan_mid | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 2.868 (N=1) | 0.598 (N=1/1) | 0.109 | 0.044 | 0.000 |
| pca | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.031 (N=1) | 0.817 (N=1/1) | — | — | — |
| pca | wan_mid | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 2.566 (N=1) | 0.666 (N=1/1) | 0.000 | 0.068 | 0.000 |
| pca | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 2.979 (N=1) | 0.787 (N=1/1) | — | — | — |
| steplm | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 9.184 (N=1) | 5.928 (N=1/1) | 2.312 | 0.158 | 0.387 |
| steplm | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 7.048 (N=1) | 3.004 (N=1/1) | — | — | — |
| steplm | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 9.223 (N=1) | 5.997 (N=1/1) | 2.308 | 0.170 | 0.374 |
| steplm | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 4.614 (N=1) | 1.679 (N=1/1) | — | — | — |
| steplm | wan_mid | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 10.312 (N=1) | 6.919 (N=1/1) | 1.982 | 0.152 | 0.287 |
| steplm | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 4.830 (N=1) | 1.696 (N=1/1) | — | — | — |
| steplm | wan_mid | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 9.740 (N=1) | 6.719 (N=1/1) | 1.961 | 0.161 | 0.283 |
| steplm | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 6.509 (N=1) | 2.627 (N=1/1) | — | — | — |

## 검증

- Diagnostic complete: `True`
- Final benchmark: `False`
- Campaign contract valid: `True`
- Coverage valid: `True`
- 배타적 분모 valid: `True`
- 모든 cell paired oracle verified: `True`
- Analysis: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-before-fast-v1/analysis-refinement/sevenway_analysis.json`
- Analysis SHA-256: `21c89f67949cebb3e31caf68e59e0b795ef2d626389469d3c1a9f4bbc34cade8`
