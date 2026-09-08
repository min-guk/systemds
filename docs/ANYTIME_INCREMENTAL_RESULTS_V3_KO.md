# AnytimeIncremental planning 비교

이 문서는 동일한 compact+exact Global과 AnytimeIncremental의 planning-only paired 결과다. 
총 8쌍 중 incremental JVM 완료는 8쌍, 목표 인증 성공은 8쌍이다. oracle과 model identity까지 paired 검증된 행은 8쌍이다. 목표 미달과 실패도 전체 분모에 남겼다.

| 지표 | Global | AnytimeIncremental | 분모 |
| --- | ---: | ---: | ---: |
| 전체 runtime-paired planner 중앙값 (s) | 2.962 | 9.040 | 8 |
| Incremental 성공 행의 launcher TTT 중앙값 (s) | 12.954 | 19.089 | 8 |

| Campaign | Workload | Profile | 목표 | 결과 | Target class | Planner G/I (s) | Launcher TTT G/I (s) | Objective G/I | 실제 regret | LB 검증 | Components initial→target/final | equalities | fullyRestored | RSS G/I (MiB) |
| --- | --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- | ---: |
| glm-incremental-v3 | glm | lan | 3% | target-partial | initial-bound-partial | 2.889/3.215 | 14.114/14.341 | 8947.078/9138.829 | 2.143% | valid | 324→324 | 0 | False | 1650.0/1594.6 |
| glm-incremental-v3 | glm | lan | 5% | target-partial | initial-bound-partial | 2.878/2.479 | 12.217/11.923 | 8947.078/9138.829 | 2.143% | valid | 324→324 | 0 | False | 1618.2/1623.1 |
| glm-incremental-v3 | glm | wan_heavy | 3% | target-partial | incremental-partial | 3.028/10.098 | 11.997/19.527 | 39974.614/40449.328 | 1.188% | valid | 324→63 | 493 | False | 1612.3/1637.7 |
| glm-incremental-v3 | glm | wan_heavy | 5% | target-partial | incremental-partial | 2.978/9.043 | 12.276/21.637 | 39974.614/41084.062 | 2.775% | valid | 324→69 | 476 | False | 1628.1/1615.9 |
| glm-incremental-v3 | glm | wan_light | 3% | target-partial | incremental-partial | 3.020/11.428 | 13.632/21.015 | 10552.184/10659.727 | 1.019% | valid | 324→69 | 476 | False | 1655.4/1630.1 |
| glm-incremental-v3 | glm | wan_light | 5% | target-partial | incremental-partial | 3.009/9.752 | 14.930/20.847 | 10552.184/10909.727 | 3.388% | valid | 324→81 | 450 | False | 1611.9/1637.9 |
| glm-incremental-v3 | glm | wan_mid | 3% | target-partial | incremental-partial | 2.839/9.038 | 13.647/18.651 | 24744.297/25201.081 | 1.846% | valid | 324→69 | 484 | False | 1603.7/1605.5 |
| glm-incremental-v3 | glm | wan_mid | 5% | target-partial | incremental-partial | 2.945/8.634 | 12.259/18.228 | 24744.297/25326.081 | 2.351% | valid | 324→69 | 476 | False | 1645.4/1636.3 |

## 해석 계약

`target-partial`은 첫 목표 checkpoint에서 `fullyRestored=0`임을 뜻한다. `initial-bound-partial`은 refinement action과 복원 equality가 모두 0인 초기 하한 성공이다. `incremental-partial`은 둘 다 0보다 큰 실제 점진 강화 성공이다. 기존 `partial_incremental_win` 필드는 이제 실제 `incremental-partial`이면서 검증된 paired Global보다 전체 planner 시간이 짧을 때만 참이다. 이전의 모든 partial 승리 의미는 새 `partial_planner_win`에 보존했다. `target-fully-restored`는 목표 도달 시 relaxation이 이미 완전히 복원된 경우이며 partial 성과로 세지 않는다.

`target_reached`는 method checkpoint의 목표 도달을 뜻한다. Oracle 또는 fingerprint가 없으면 그 행은 `pair_verified=false`이고 certificate·regret·승리 집계에는 포함하지 않는다.

Stage 시간(MBE bound, compact reduction, component preparation/solve, projection, ordered seed, Regional)은 JSON의 `stage_times_at_outcome`에 보존했다. `orderedSeedNanos`가 없는 구현은 0으로 바꾸지 않고 null로 기록한다.

이 결과는 encoded modeled-cost 문제의 인증이다. 실제 workload runtime을 인증하지 않는다.
