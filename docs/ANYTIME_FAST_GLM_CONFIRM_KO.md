# AnytimeTarget 3%/5% planning-only 진단 보고서

- 상태: **수집·계약 검증이 완료된 diagnostic pilot이며 최종 반복 benchmark가 아님**
- Campaign: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/glm-confirm-v1`
- Phase: `measured`
- 계획/관측/누락 attempt: 32/32/0
- 반복: 1회 진단이면 속도 차이는 기술 통계이며 일반화하지 않는다.

## Threshold별 결과

| 목표 | 방법 | 성공/시도/계획 | 통과 후 미달 | 프로세스 실패 | 누락 | 성공 TTT 중앙값 | Planner 합/중앙값 | 진단·준비 합(통과) | Bound·MBE 합(통과) | Regional 합(통과) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3.0% | AnytimeTarget | 8/8/8 | 0 | 0 | 0 | 14.119 (N=8) | 36.983/4.515 (N=8/8) | 2.074 | 4.254 | 0.000 |
| 3.0% | Global | 8/8/8 | 0 | 0 | 0 | 18.944 (N=8) | 75.598/9.405 (N=8/8) | — | — | — |
| 5.0% | AnytimeTarget | 8/8/8 | 0 | 0 | 0 | 14.525 (N=8) | 36.827/4.556 (N=8/8) | 2.307 | 4.238 | 0.000 |
| 5.0% | Global | 8/8/8 | 0 | 0 | 0 | 19.860 (N=8) | 77.727/9.349 (N=8/8) | — | — | — |

TTT는 JVM 시작부터 AnytimeTarget의 첫 목표 달성 checkpoint 또는 Global exact 결과의 [Physical-CostContributionComplete] 로그 도착까지다. TTT는 성공한 attempt만 사용한다. Planner 합과 중앙값은 관측된 모든 attempt를 사용한다. Phase 합은 receipt를 통과하고 counter가 있는 row에서 계산한다. 진단 시간은 preparation/preflight를 포함하며 Bound 시간은 MBE를 포함한 LB 계산 누적값이다. —는 해당 phase의 계측값이 없음을 뜻하며, 비용이 0이라는 의미가 아니다.

## Global 대비 AnytimeTarget paired timing

| 목표 | Joint 성공/계획 pair | 제외 | Target TTT | Global TTT | TTT 비율 Target/Global | Planner 비율 Target/Global | 제외 이유 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 3.0% | 8/8 | 0 | 14.119 (N=8) | 18.944 (N=8) | 0.741 (N=8) | 0.479 (N=8) | `{}` |
| 5.0% | 8/8 | 0 | 14.525 (N=8) | 19.860 (N=8) | 0.716 (N=8) | 0.469 (N=8) | `{}` |

비율과 timing은 두 방법이 모두 목표를 달성한 pair에만 계산한다. 제외된 pair는 계획 분모에 남는다.

### Workload·profile별 paired timing

| Workload | Profile | 목표 | Joint 성공/계획 | Target TTT | Global TTT | TTT 비율 Target/Global | 제외 이유 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| glm | lan | 3.0% | 2/2 | 13.613 (N=2) | 18.956 (N=2) | 0.719 (N=2) | `{}` |
| glm | lan | 5.0% | 2/2 | 13.679 (N=2) | 19.235 (N=2) | 0.711 (N=2) | `{}` |
| glm | wan_heavy | 3.0% | 2/2 | 14.435 (N=2) | 18.944 (N=2) | 0.763 (N=2) | `{}` |
| glm | wan_heavy | 5.0% | 2/2 | 14.613 (N=2) | 19.795 (N=2) | 0.739 (N=2) | `{}` |
| glm | wan_light | 3.0% | 2/2 | 16.497 (N=2) | 18.982 (N=2) | 0.868 (N=2) | `{}` |
| glm | wan_light | 5.0% | 2/2 | 14.526 (N=2) | 20.481 (N=2) | 0.709 (N=2) | `{}` |
| glm | wan_mid | 3.0% | 2/2 | 13.998 (N=2) | 19.081 (N=2) | 0.734 (N=2) | `{}` |
| glm | wan_mid | 5.0% | 2/2 | 14.558 (N=2) | 19.710 (N=2) | 0.741 (N=2) | `{}` |

## Workload·profile·threshold별 결과

| Workload | Profile | 목표 | 방법 | 성공/시도/계획 | 미달 | 실패 | 누락 | 성공 TTT | Planner 중앙값 | 진단·준비 합(통과) | Bound·MBE 합(통과) | Regional 합(통과) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| glm | lan | 3.0% | AnytimeTarget | 2/2/2 | 0 | 0 | 0 | 13.613 (N=2) | 4.140 (N=2/2) | 0.000 | 0.994 | 0.000 |
| glm | lan | 3.0% | Global | 2/2/2 | 0 | 0 | 0 | 18.956 (N=2) | 9.280 (N=2/2) | — | — | — |
| glm | lan | 5.0% | AnytimeTarget | 2/2/2 | 0 | 0 | 0 | 13.679 (N=2) | 4.062 (N=2/2) | 0.000 | 0.981 | 0.000 |
| glm | lan | 5.0% | Global | 2/2/2 | 0 | 0 | 0 | 19.235 (N=2) | 8.991 (N=2/2) | — | — | — |
| glm | wan_heavy | 3.0% | AnytimeTarget | 2/2/2 | 0 | 0 | 0 | 14.435 (N=2) | 4.597 (N=2/2) | 0.668 | 0.989 | 0.000 |
| glm | wan_heavy | 3.0% | Global | 2/2/2 | 0 | 0 | 0 | 18.944 (N=2) | 9.597 (N=2/2) | — | — | — |
| glm | wan_heavy | 5.0% | AnytimeTarget | 2/2/2 | 0 | 0 | 0 | 14.613 (N=2) | 5.156 (N=2/2) | 0.916 | 1.275 | 0.000 |
| glm | wan_heavy | 5.0% | Global | 2/2/2 | 0 | 0 | 0 | 19.795 (N=2) | 9.955 (N=2/2) | — | — | — |
| glm | wan_light | 3.0% | AnytimeTarget | 2/2/2 | 0 | 0 | 0 | 16.497 (N=2) | 5.188 (N=2/2) | 0.709 | 1.256 | 0.000 |
| glm | wan_light | 3.0% | Global | 2/2/2 | 0 | 0 | 0 | 18.982 (N=2) | 9.308 (N=2/2) | — | — | — |
| glm | wan_light | 5.0% | AnytimeTarget | 2/2/2 | 0 | 0 | 0 | 14.526 (N=2) | 4.639 (N=2/2) | 0.691 | 0.963 | 0.000 |
| glm | wan_light | 5.0% | Global | 2/2/2 | 0 | 0 | 0 | 20.481 (N=2) | 9.675 (N=2/2) | — | — | — |
| glm | wan_mid | 3.0% | AnytimeTarget | 2/2/2 | 0 | 0 | 0 | 13.998 (N=2) | 4.566 (N=2/2) | 0.697 | 1.015 | 0.000 |
| glm | wan_mid | 3.0% | Global | 2/2/2 | 0 | 0 | 0 | 19.081 (N=2) | 9.615 (N=2/2) | — | — | — |
| glm | wan_mid | 5.0% | AnytimeTarget | 2/2/2 | 0 | 0 | 0 | 14.558 (N=2) | 4.556 (N=2/2) | 0.700 | 1.018 | 0.000 |
| glm | wan_mid | 5.0% | Global | 2/2/2 | 0 | 0 | 0 | 19.710 (N=2) | 10.242 (N=2/2) | — | — | — |

## 검증

- Diagnostic complete: `True`
- Final benchmark: `False`
- Campaign contract valid: `True`
- Coverage valid: `True`
- 배타적 분모 valid: `True`
- 모든 cell paired oracle verified: `True`
- Analysis: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/glm-confirm-v1/analysis-refinement/sevenway_analysis.json`
- Analysis SHA-256: `16f9018616ef64f351ec9ffdbf647029d35813edb1762d47e020aa58f5cc9388`
