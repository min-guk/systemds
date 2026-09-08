# AnytimeTarget 3%/5% planning-only 진단 보고서

- 상태: **수집·계약 검증이 완료된 diagnostic pilot이며 최종 반복 benchmark가 아님**
- Campaign: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-whole1m-v1`
- Phase: `measured`
- 계획/관측/누락 attempt: 32/32/0
- 반복: 1회 진단이면 속도 차이는 기술 통계이며 일반화하지 않는다.

## Threshold별 결과

| 목표 | 방법 | 성공/시도/계획 | 통과 후 미달 | 프로세스 실패 | 누락 | 성공 TTT 중앙값 | Planner 합/중앙값 | 진단·준비 합(통과) | Bound·MBE 합(통과) | Regional 합(통과) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3.0% | AnytimeTarget | 8/8/8 | 0 | 0 | 0 | 5.892 (N=8) | 22.012/2.503 (N=8/8) | 0.838 | 1.498 | 0.000 |
| 3.0% | Global | 8/8/8 | 0 | 0 | 0 | 4.146 (N=8) | 28.559/1.393 (N=8/8) | — | — | — |
| 5.0% | AnytimeTarget | 8/8/8 | 0 | 0 | 0 | 5.213 (N=8) | 20.215/2.358 (N=8/8) | 0.809 | 1.482 | 0.000 |
| 5.0% | Global | 8/8/8 | 0 | 0 | 0 | 4.058 (N=8) | 28.982/1.344 (N=8/8) | — | — | — |

TTT는 JVM 시작부터 AnytimeTarget의 첫 목표 달성 checkpoint 또는 Global exact 결과의 [Physical-CostContributionComplete] 로그 도착까지다. TTT는 성공한 attempt만 사용한다. Planner 합과 중앙값은 관측된 모든 attempt를 사용한다. Phase 합은 receipt를 통과하고 counter가 있는 row에서 계산한다. 진단 시간은 preparation/preflight를 포함하며 Bound 시간은 MBE를 포함한 LB 계산 누적값이다. —는 해당 phase의 계측값이 없음을 뜻하며, 비용이 0이라는 의미가 아니다.

## Global 대비 AnytimeTarget paired timing

| 목표 | Joint 성공/계획 pair | 제외 | Target TTT | Global TTT | TTT 비율 Target/Global | Planner 비율 Target/Global | 제외 이유 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 3.0% | 8/8 | 0 | 5.892 (N=8) | 4.146 (N=8) | 1.036 (N=8) | 1.022 (N=8) | `{}` |
| 5.0% | 8/8 | 0 | 5.213 (N=8) | 4.058 (N=8) | 1.057 (N=8) | 1.139 (N=8) | `{}` |

비율과 timing은 두 방법이 모두 목표를 달성한 pair에만 계산한다. 제외된 pair는 계획 분모에 남는다.

### Workload·profile별 paired timing

| Workload | Profile | 목표 | Joint 성공/계획 | Target TTT | Global TTT | TTT 비율 Target/Global | 제외 이유 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| glm | lan | 3.0% | 1/1 | 14.589 (N=1) | 22.434 (N=1) | 0.650 (N=1) | `{}` |
| glm | lan | 5.0% | 1/1 | 13.927 (N=1) | 19.524 (N=1) | 0.713 (N=1) | `{}` |
| glm | wan_mid | 3.0% | 1/1 | 14.006 (N=1) | 19.163 (N=1) | 0.731 (N=1) | `{}` |
| glm | wan_mid | 5.0% | 1/1 | 14.682 (N=1) | 23.238 (N=1) | 0.632 (N=1) | `{}` |
| lm | lan | 3.0% | 1/1 | 3.111 (N=1) | 3.208 (N=1) | 0.970 (N=1) | `{}` |
| lm | lan | 5.0% | 1/1 | 3.674 (N=1) | 3.089 (N=1) | 1.189 (N=1) | `{}` |
| lm | wan_mid | 3.0% | 1/1 | 4.287 (N=1) | 3.549 (N=1) | 1.208 (N=1) | `{}` |
| lm | wan_mid | 5.0% | 1/1 | 3.535 (N=1) | 2.694 (N=1) | 1.312 (N=1) | `{}` |
| pca | lan | 3.0% | 1/1 | 2.868 (N=1) | 2.601 (N=1) | 1.103 (N=1) | `{}` |
| pca | lan | 5.0% | 1/1 | 2.721 (N=1) | 2.929 (N=1) | 0.929 (N=1) | `{}` |
| pca | wan_mid | 3.0% | 1/1 | 2.885 (N=1) | 2.985 (N=1) | 0.966 (N=1) | `{}` |
| pca | wan_mid | 5.0% | 1/1 | 2.534 (N=1) | 2.702 (N=1) | 0.938 (N=1) | `{}` |
| steplm | lan | 3.0% | 1/1 | 7.498 (N=1) | 4.743 (N=1) | 1.581 (N=1) | `{}` |
| steplm | lan | 5.0% | 1/1 | 6.752 (N=1) | 5.026 (N=1) | 1.344 (N=1) | `{}` |
| steplm | wan_mid | 3.0% | 1/1 | 9.378 (N=1) | 4.959 (N=1) | 1.891 (N=1) | `{}` |
| steplm | wan_mid | 5.0% | 1/1 | 7.515 (N=1) | 6.389 (N=1) | 1.176 (N=1) | `{}` |

## Workload·profile·threshold별 결과

| Workload | Profile | 목표 | 방법 | 성공/시도/계획 | 미달 | 실패 | 누락 | 성공 TTT | Planner 중앙값 | 진단·준비 합(통과) | Bound·MBE 합(통과) | Regional 합(통과) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| glm | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 14.589 (N=1) | 3.947 (N=1/1) | 0.000 | 0.486 | 0.000 |
| glm | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 22.434 (N=1) | 11.916 (N=1/1) | — | — | — |
| glm | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 13.927 (N=1) | 3.945 (N=1/1) | 0.000 | 0.505 | 0.000 |
| glm | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 19.524 (N=1) | 9.341 (N=1/1) | — | — | — |
| glm | wan_mid | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 14.006 (N=1) | 4.611 (N=1/1) | 0.324 | 0.492 | 0.000 |
| glm | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 19.163 (N=1) | 9.560 (N=1/1) | — | — | — |
| glm | wan_mid | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 14.682 (N=1) | 5.009 (N=1/1) | 0.341 | 0.476 | 0.000 |
| glm | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 23.238 (N=1) | 12.226 (N=1/1) | — | — | — |
| lm | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 3.111 (N=1) | 0.935 (N=1/1) | 0.086 | 0.052 | 0.000 |
| lm | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.208 (N=1) | 0.955 (N=1/1) | — | — | — |
| lm | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 3.674 (N=1) | 1.248 (N=1/1) | 0.108 | 0.072 | 0.000 |
| lm | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.089 (N=1) | 0.699 (N=1/1) | — | — | — |
| lm | wan_mid | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 4.287 (N=1) | 1.422 (N=1/1) | 0.104 | 0.071 | 0.000 |
| lm | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.549 (N=1) | 1.048 (N=1/1) | — | — | — |
| lm | wan_mid | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 3.535 (N=1) | 0.957 (N=1/1) | 0.090 | 0.055 | 0.000 |
| lm | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 2.694 (N=1) | 0.647 (N=1/1) | — | — | — |
| pca | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 2.868 (N=1) | 0.761 (N=1/1) | 0.000 | 0.071 | 0.000 |
| pca | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 2.601 (N=1) | 0.714 (N=1/1) | — | — | — |
| pca | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 2.721 (N=1) | 0.462 (N=1/1) | 0.000 | 0.053 | 0.000 |
| pca | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 2.929 (N=1) | 0.850 (N=1/1) | — | — | — |
| pca | wan_mid | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 2.885 (N=1) | 0.564 (N=1/1) | 0.054 | 0.045 | 0.000 |
| pca | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 2.985 (N=1) | 0.756 (N=1/1) | — | — | — |
| pca | wan_mid | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 2.534 (N=1) | 0.585 (N=1/1) | 0.000 | 0.067 | 0.000 |
| pca | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 2.702 (N=1) | 0.733 (N=1/1) | — | — | — |
| steplm | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 7.498 (N=1) | 3.584 (N=1/1) | 0.142 | 0.110 | 0.000 |
| steplm | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 4.743 (N=1) | 1.738 (N=1/1) | — | — | — |
| steplm | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 6.752 (N=1) | 3.468 (N=1/1) | 0.139 | 0.109 | 0.000 |
| steplm | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 5.026 (N=1) | 1.838 (N=1/1) | — | — | — |
| steplm | wan_mid | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 9.378 (N=1) | 6.188 (N=1/1) | 0.128 | 0.171 | 0.000 |
| steplm | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 4.959 (N=1) | 1.871 (N=1/1) | — | — | — |
| steplm | wan_mid | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 7.515 (N=1) | 4.540 (N=1/1) | 0.132 | 0.146 | 0.000 |
| steplm | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 6.389 (N=1) | 2.648 (N=1/1) | — | — | — |

## 검증

- Diagnostic complete: `True`
- Final benchmark: `False`
- Campaign contract valid: `True`
- Coverage valid: `True`
- 배타적 분모 valid: `True`
- 모든 cell paired oracle verified: `True`
- Analysis: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-whole1m-v1/analysis-refinement/sevenway_analysis.json`
- Analysis SHA-256: `06167a0fb07cf71041dc1cfb20ebe58e311e09bb19a85bd8d1a0ce7d81c1febf`
