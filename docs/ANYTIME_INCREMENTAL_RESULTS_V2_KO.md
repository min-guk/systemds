# AnytimeIncremental planning 비교

이 문서는 동일한 compact+exact Global과 AnytimeIncremental의 planning-only paired 결과다. 
총 8쌍 중 incremental JVM 완료는 8쌍, 목표 인증 성공은 8쌍이다. oracle과 model identity까지 paired 검증된 행은 8쌍이다. 목표 미달과 실패도 전체 분모에 남겼다.

| 지표 | Global | AnytimeIncremental | 분모 |
| --- | ---: | ---: | ---: |
| 전체 runtime-paired planner 중앙값 (s) | 2.904 | 15.289 | 8 |
| Incremental 성공 행의 launcher TTT 중앙값 (s) | 13.390 | 25.116 | 8 |

| Campaign | Workload | Profile | 목표 | 결과 | Target class | Planner G/I (s) | Launcher TTT G/I (s) | Objective G/I | 실제 regret | LB 검증 | Components initial→target/final | equalities | fullyRestored | RSS G/I (MiB) |
| --- | --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- | ---: |
| glm-incremental-v2 | glm | lan | 3% | target-partial | initial-bound-partial | 3.020/2.425 | 13.505/13.271 | 8947.078/9138.829 | 2.143% | valid | 324→324 | 0 | False | 1622.2/1620.3 |
| glm-incremental-v2 | glm | lan | 5% | target-partial | initial-bound-partial | 3.066/2.321 | 13.276/13.027 | 8947.078/9138.829 | 2.143% | valid | 324→324 | 0 | False | 1621.8/1601.1 |
| glm-incremental-v2 | glm | wan_heavy | 3% | target-partial | incremental-partial | 2.787/21.706 | 13.054/31.283 | 39974.614/40399.328 | 1.062% | valid | 324→47 | 539 | False | 1606.7/1649.0 |
| glm-incremental-v2 | glm | wan_heavy | 5% | target-partial | incremental-partial | 2.907/15.147 | 12.623/24.515 | 39974.614/40449.328 | 1.188% | valid | 324→69 | 478 | False | 1618.0/1626.0 |
| glm-incremental-v2 | glm | wan_light | 3% | target-partial | incremental-partial | 3.313/15.431 | 13.892/26.423 | 10552.184/10659.727 | 1.019% | valid | 324→69 | 462 | False | 1620.5/1619.2 |
| glm-incremental-v2 | glm | wan_light | 5% | target-partial | incremental-partial | 2.902/12.159 | 14.408/21.977 | 10552.184/10909.727 | 3.388% | valid | 324→91 | 424 | False | 1604.2/1624.2 |
| glm-incremental-v2 | glm | wan_mid | 3% | target-partial | incremental-partial | 2.891/22.146 | 14.205/32.135 | 24744.297/24989.244 | 0.990% | valid | 324→56 | 506 | False | 1623.9/1633.9 |
| glm-incremental-v2 | glm | wan_mid | 5% | target-partial | incremental-partial | 2.850/15.550 | 12.707/25.718 | 24744.297/25326.081 | 2.351% | valid | 324→75 | 461 | False | 1633.2/1618.9 |

## 해석 계약

`target-partial`은 첫 목표 checkpoint에서 `fullyRestored=0`임을 뜻한다. `initial-bound-partial`은 refinement action과 복원 equality가 모두 0인 초기 하한 성공이다. `incremental-partial`은 둘 다 0보다 큰 실제 점진 강화 성공이다. 기존 `partial_incremental_win` 필드는 이제 실제 `incremental-partial`이면서 검증된 paired Global보다 전체 planner 시간이 짧을 때만 참이다. 이전의 모든 partial 승리 의미는 새 `partial_planner_win`에 보존했다. `target-fully-restored`는 목표 도달 시 relaxation이 이미 완전히 복원된 경우이며 partial 성과로 세지 않는다.

`target_reached`는 method checkpoint의 목표 도달을 뜻한다. Oracle 또는 fingerprint가 없으면 그 행은 `pair_verified=false`이고 certificate·regret·승리 집계에는 포함하지 않는다.

Stage 시간(MBE bound, compact reduction, component preparation/solve, projection, ordered seed, Regional)은 JSON의 `stage_times_at_outcome`에 보존했다. `orderedSeedNanos`가 없는 구현은 0으로 바꾸지 않고 null로 기록한다.

이 결과는 encoded modeled-cost 문제의 인증이다. 실제 workload runtime을 인증하지 않는다.
