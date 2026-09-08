# AnytimeIncremental planning 비교

이 문서는 동일한 compact+exact Global과 AnytimeIncremental의 planning-only paired 결과다. 
총 8쌍 중 incremental JVM 완료는 8쌍, 목표 인증 성공은 0쌍이다. oracle과 model identity까지 paired 검증된 행은 8쌍이다. 목표 미달과 실패도 전체 분모에 남겼다.

| 지표 | Global | AnytimeIncremental | 분모 |
| --- | ---: | ---: | ---: |
| 전체 runtime-paired planner 중앙값 (s) | 3.015 | 3.776 | 8 |
| Incremental 성공 행의 launcher TTT 중앙값 (s) | — | — | 0 |

| Campaign | Workload | Profile | 목표 | 결과 | Target class | Planner G/I (s) | Launcher TTT G/I (s) | Objective G/I | 실제 regret | LB 검증 | Components initial→target/final | equalities | fullyRestored | RSS G/I (MiB) |
| --- | --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- | ---: |
| glm-incremental-v1 | glm | lan | 3% | target-miss | — | 2.867/3.675 | 12.395/— | 8947.078/9991.740 | 11.676% | valid | 324→282 | 64 | False | 1631.0/1628.0 |
| glm-incremental-v1 | glm | lan | 5% | target-miss | — | 3.011/3.777 | 13.204/— | 8947.078/9991.740 | 11.676% | valid | 324→283 | 64 | False | 1622.0/1627.5 |
| glm-incremental-v1 | glm | wan_heavy | 3% | target-miss | — | 2.971/3.840 | 13.359/— | 39974.614/57789.614 | 44.566% | valid | 324→272 | 64 | False | 1633.0/1645.7 |
| glm-incremental-v1 | glm | wan_heavy | 5% | target-miss | — | 3.375/4.862 | 13.991/— | 39974.614/57789.614 | 44.566% | valid | 324→272 | 64 | False | 1639.2/1624.1 |
| glm-incremental-v1 | glm | wan_light | 3% | target-miss | — | 2.984/3.588 | 13.800/— | 10552.184/12291.493 | 16.483% | valid | 324→275 | 64 | False | 1614.3/1619.9 |
| glm-incremental-v1 | glm | wan_light | 5% | target-miss | — | 3.751/3.774 | 14.392/— | 10552.184/12291.493 | 16.483% | valid | 324→276 | 64 | False | 1629.3/1610.3 |
| glm-incremental-v1 | glm | wan_mid | 3% | target-miss | — | 3.020/3.823 | 11.998/— | 24744.297/33634.489 | 35.928% | valid | 324→273 | 64 | False | 1624.0/1612.9 |
| glm-incremental-v1 | glm | wan_mid | 5% | target-miss | — | 3.488/3.674 | 13.751/— | 24744.297/33634.489 | 35.928% | valid | 324→273 | 64 | False | 1624.5/1602.1 |

## 해석 계약

`target-partial`은 첫 목표 checkpoint에서 `fullyRestored=0`임을 뜻한다. `initial-bound-partial`은 refinement action과 복원 equality가 모두 0인 초기 하한 성공이다. `incremental-partial`은 둘 다 0보다 큰 실제 점진 강화 성공이다. 기존 `partial_incremental_win` 필드는 이제 실제 `incremental-partial`이면서 검증된 paired Global보다 전체 planner 시간이 짧을 때만 참이다. 이전의 모든 partial 승리 의미는 새 `partial_planner_win`에 보존했다. `target-fully-restored`는 목표 도달 시 relaxation이 이미 완전히 복원된 경우이며 partial 성과로 세지 않는다.

`target_reached`는 method checkpoint의 목표 도달을 뜻한다. Oracle 또는 fingerprint가 없으면 그 행은 `pair_verified=false`이고 certificate·regret·승리 집계에는 포함하지 않는다.

Stage 시간(MBE bound, compact reduction, component preparation/solve, projection, ordered seed, Regional)은 JSON의 `stage_times_at_outcome`에 보존했다. `orderedSeedNanos`가 없는 구현은 0으로 바꾸지 않고 null로 기록한다.

이 결과는 encoded modeled-cost 문제의 인증이다. 실제 workload runtime을 인증하지 않는다.
