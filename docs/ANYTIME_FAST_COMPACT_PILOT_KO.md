# AnytimeTarget 3%/5% planning-only 진단 보고서

- 상태: **수집·계약 검증이 완료된 diagnostic pilot이며 최종 반복 benchmark가 아님**
- Campaign: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-compact-v1`
- Phase: `measured`
- 계획/관측/누락 attempt: 32/32/0
- 반복: 1회 진단이면 속도 차이는 기술 통계이며 일반화하지 않는다.

## Threshold별 결과

| 목표 | 방법 | 성공/시도/계획 | 통과 후 미달 | 프로세스 실패 | 누락 | 성공 TTT 중앙값 | Planner 합/중앙값 | 진단·준비 합(통과) | Bound·MBE 합(통과) | Regional 합(통과) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3.0% | AnytimeTarget | 7/8/8 | 1 | 0 | 0 | 4.013 (N=7) | 28.446/2.771 (N=8/8) | 4.809 | 2.547 | 2.932 |
| 3.0% | Global | 8/8/8 | 0 | 0 | 0 | 4.257 (N=8) | 26.033/1.340 (N=8/8) | — | — | — |
| 5.0% | AnytimeTarget | 7/8/8 | 1 | 0 | 0 | 3.393 (N=7) | 28.992/2.540 (N=8/8) | 4.728 | 2.569 | 2.772 |
| 5.0% | Global | 8/8/8 | 0 | 0 | 0 | 4.831 (N=8) | 30.147/1.911 (N=8/8) | — | — | — |

TTT는 JVM 시작부터 AnytimeTarget의 첫 목표 달성 checkpoint 또는 Global exact 결과의 [Physical-CostContributionComplete] 로그 도착까지다. TTT는 성공한 attempt만 사용한다. Planner 합과 중앙값은 관측된 모든 attempt를 사용한다. Phase 합은 receipt를 통과하고 counter가 있는 row에서 계산한다. 진단 시간은 preparation/preflight를 포함하며 Bound 시간은 MBE를 포함한 LB 계산 누적값이다. —는 해당 phase의 계측값이 없음을 뜻하며, 비용이 0이라는 의미가 아니다.

## Global 대비 AnytimeTarget paired timing

| 목표 | Joint 성공/계획 pair | 제외 | Target TTT | Global TTT | TTT 비율 Target/Global | Planner 비율 Target/Global | 제외 이유 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 3.0% | 7/8 | 1 | 4.013 (N=7) | 3.566 (N=7) | 0.999 (N=7) | 1.410 (N=7) | `{"target_miss": 1}` |
| 5.0% | 7/8 | 1 | 3.393 (N=7) | 3.954 (N=7) | 0.998 (N=7) | 1.145 (N=7) | `{"target_miss": 1}` |

비율과 timing은 두 방법이 모두 목표를 달성한 pair에만 계산한다. 제외된 pair는 계획 분모에 남는다.

### Workload·profile별 paired timing

| Workload | Profile | 목표 | Joint 성공/계획 | Target TTT | Global TTT | TTT 비율 Target/Global | 제외 이유 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| glm | lan | 3.0% | 1/1 | 13.609 (N=1) | 20.574 (N=1) | 0.661 (N=1) | `{}` |
| glm | lan | 5.0% | 1/1 | 13.784 (N=1) | 18.951 (N=1) | 0.727 (N=1) | `{}` |
| glm | wan_mid | 3.0% | 0/1 | — | — | — | `{"target_miss": 1}` |
| glm | wan_mid | 5.0% | 0/1 | — | — | — | `{"target_miss": 1}` |
| lm | lan | 3.0% | 1/1 | 3.552 (N=1) | 3.566 (N=1) | 0.996 (N=1) | `{}` |
| lm | lan | 5.0% | 1/1 | 3.393 (N=1) | 3.954 (N=1) | 0.858 (N=1) | `{}` |
| lm | wan_mid | 3.0% | 1/1 | 4.013 (N=1) | 3.421 (N=1) | 1.173 (N=1) | `{}` |
| lm | wan_mid | 5.0% | 1/1 | 3.393 (N=1) | 2.798 (N=1) | 1.213 (N=1) | `{}` |
| pca | lan | 3.0% | 1/1 | 2.553 (N=1) | 3.306 (N=1) | 0.772 (N=1) | `{}` |
| pca | lan | 5.0% | 1/1 | 2.748 (N=1) | 2.752 (N=1) | 0.998 (N=1) | `{}` |
| pca | wan_mid | 3.0% | 1/1 | 3.268 (N=1) | 3.273 (N=1) | 0.999 (N=1) | `{}` |
| pca | wan_mid | 5.0% | 1/1 | 2.697 (N=1) | 2.710 (N=1) | 0.995 (N=1) | `{}` |
| steplm | lan | 3.0% | 1/1 | 8.225 (N=1) | 4.947 (N=1) | 1.663 (N=1) | `{}` |
| steplm | lan | 5.0% | 1/1 | 8.321 (N=1) | 5.708 (N=1) | 1.458 (N=1) | `{}` |
| steplm | wan_mid | 3.0% | 1/1 | 8.500 (N=1) | 5.278 (N=1) | 1.610 (N=1) | `{}` |
| steplm | wan_mid | 5.0% | 1/1 | 9.598 (N=1) | 6.409 (N=1) | 1.498 (N=1) | `{}` |

## Workload·profile·threshold별 결과

| Workload | Profile | 목표 | 방법 | 성공/시도/계획 | 미달 | 실패 | 누락 | 성공 TTT | Planner 중앙값 | 진단·준비 합(통과) | Bound·MBE 합(통과) | Regional 합(통과) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| glm | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 13.609 (N=1) | 4.007 (N=1/1) | 0.000 | 0.471 | 0.000 |
| glm | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 20.574 (N=1) | 9.667 (N=1/1) | — | — | — |
| glm | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 13.784 (N=1) | 3.897 (N=1/1) | 0.000 | 0.498 | 0.000 |
| glm | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 18.951 (N=1) | 9.448 (N=1/1) | — | — | — |
| glm | wan_mid | 3.0% | AnytimeTarget | 0/1/1 | 1 | 0 | 0 | — | 10.321 (N=1/1) | 3.120 | 1.371 | 2.106 |
| glm | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 18.681 (N=1) | 9.275 (N=1/1) | — | — | — |
| glm | wan_mid | 5.0% | AnytimeTarget | 0/1/1 | 1 | 0 | 0 | — | 10.549 (N=1/1) | 3.226 | 1.390 | 2.114 |
| glm | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 22.299 (N=1) | 11.759 (N=1/1) | — | — | — |
| lm | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 3.552 (N=1) | 1.258 (N=1/1) | 0.252 | 0.091 | 0.172 |
| lm | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.566 (N=1) | 0.892 (N=1/1) | — | — | — |
| lm | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 3.393 (N=1) | 1.088 (N=1/1) | 0.109 | 0.087 | 0.006 |
| lm | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.954 (N=1) | 1.173 (N=1/1) | — | — | — |
| lm | wan_mid | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 4.013 (N=1) | 1.534 (N=1/1) | 0.161 | 0.133 | 0.008 |
| lm | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.421 (N=1) | 0.805 (N=1/1) | — | — | — |
| lm | wan_mid | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 3.393 (N=1) | 1.184 (N=1/1) | 0.175 | 0.135 | 0.010 |
| lm | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 2.798 (N=1) | 0.621 (N=1/1) | — | — | — |
| pca | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 2.553 (N=1) | 0.555 (N=1/1) | 0.000 | 0.062 | 0.000 |
| pca | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.306 (N=1) | 0.885 (N=1/1) | — | — | — |
| pca | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 2.748 (N=1) | 0.458 (N=1/1) | 0.000 | 0.043 | 0.000 |
| pca | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 2.752 (N=1) | 0.723 (N=1/1) | — | — | — |
| pca | wan_mid | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 3.268 (N=1) | 0.904 (N=1/1) | 0.095 | 0.076 | 0.000 |
| pca | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 3.273 (N=1) | 0.842 (N=1/1) | — | — | — |
| pca | wan_mid | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 2.697 (N=1) | 0.608 (N=1/1) | 0.000 | 0.072 | 0.000 |
| pca | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 2.710 (N=1) | 0.531 (N=1/1) | — | — | — |
| steplm | lan | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 8.225 (N=1) | 4.456 (N=1/1) | 0.634 | 0.203 | 0.367 |
| steplm | lan | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 4.947 (N=1) | 1.788 (N=1/1) | — | — | — |
| steplm | lan | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 8.321 (N=1) | 4.688 (N=1/1) | 0.657 | 0.199 | 0.366 |
| steplm | lan | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 5.708 (N=1) | 2.649 (N=1/1) | — | — | — |
| steplm | wan_mid | 3.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 8.500 (N=1) | 5.411 (N=1/1) | 0.548 | 0.141 | 0.278 |
| steplm | wan_mid | 3.0% | Global | 1/1/1 | 0 | 0 | 0 | 5.278 (N=1) | 1.880 (N=1/1) | — | — | — |
| steplm | wan_mid | 5.0% | AnytimeTarget | 1/1/1 | 0 | 0 | 0 | 9.598 (N=1) | 6.520 (N=1/1) | 0.562 | 0.145 | 0.276 |
| steplm | wan_mid | 5.0% | Global | 1/1/1 | 0 | 0 | 0 | 6.409 (N=1) | 3.244 (N=1/1) | — | — | — |

## 검증

- Diagnostic complete: `True`
- Final benchmark: `False`
- Campaign contract valid: `True`
- Coverage valid: `True`
- 배타적 분모 valid: `True`
- 모든 cell paired oracle verified: `True`
- Analysis: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-compact-v1/analysis-refinement/sevenway_analysis.json`
- Analysis SHA-256: `b66de262fdb5497bf3b4936df7bcc934d233752858debd74950cd35fa5430288`
