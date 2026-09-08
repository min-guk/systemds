# AnytimeIncremental planning 비교

이 문서는 동일한 compact+exact Global과 AnytimeIncremental의 planning-only paired 결과다. 
총 8쌍 중 incremental JVM 완료는 8쌍, 목표 인증 성공은 8쌍이다. oracle과 model identity까지 paired 검증된 행은 8쌍이다. 목표 미달과 실패도 전체 분모에 남겼다.

| 지표 | Global | AnytimeIncremental | 분모 |
| --- | ---: | ---: | ---: |
| 전체 runtime-paired planner 중앙값 (s) | 3.017 | 3.178 | 8 |
| Incremental 성공 행의 launcher TTT 중앙값 (s) | 12.497 | 13.552 | 8 |

| Campaign | Workload | Profile | 목표 | 결과 | Target class | Planner G/I (s) | Launcher TTT G/I (s) | Objective G/I | 실제 regret | LB 검증 | Components initial→target/final | equalities | fullyRestored | RSS G/I (MiB) |
| --- | --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- | ---: |
| glm-incremental-v4 | glm | lan | 3% | target-partial | initial-bound-partial | 2.926/2.384 | 12.254/12.451 | 8947.078/9138.829 | 2.143% | valid | 324→324 | 0 | False | 1635.7/1591.0 |
| glm-incremental-v4 | glm | lan | 5% | target-partial | initial-bound-partial | 3.029/2.373 | 12.740/14.077 | 8947.078/9138.829 | 2.143% | valid | 324→324 | 0 | False | 1631.0/1610.7 |
| glm-incremental-v4 | glm | wan_heavy | 3% | target-partial | incremental-partial | 3.098/3.860 | 12.013/13.678 | 39974.614/40421.635 | 1.118% | valid | 324→47 | 533 | False | 1624.9/1652.6 |
| glm-incremental-v4 | glm | wan_heavy | 5% | target-partial | incremental-partial | 3.005/3.151 | 13.522/13.178 | 39974.614/40421.635 | 1.118% | valid | 324→98 | 368 | False | 1622.9/1620.8 |
| glm-incremental-v4 | glm | wan_light | 3% | target-partial | incremental-partial | 2.921/4.360 | 13.438/14.463 | 10552.184/10578.797 | 0.252% | valid | 324→98 | 368 | False | 1626.0/1629.8 |
| glm-incremental-v4 | glm | wan_light | 5% | target-partial | incremental-partial | 2.949/3.205 | 12.087/12.650 | 10552.184/10578.797 | 0.252% | valid | 324→98 | 368 | False | 1625.2/1616.7 |
| glm-incremental-v4 | glm | wan_mid | 3% | target-partial | incremental-partial | 3.730/3.131 | 13.910/13.426 | 24744.297/24961.375 | 0.877% | valid | 324→93 | 411 | False | 1628.0/1615.7 |
| glm-incremental-v4 | glm | wan_mid | 5% | target-partial | incremental-partial | 3.045/3.301 | 12.167/13.980 | 24744.297/24961.375 | 0.877% | valid | 324→98 | 368 | False | 1636.2/1637.6 |

## 해석 계약

`target-partial`은 첫 목표 checkpoint에서 `fullyRestored=0`임을 뜻한다. `initial-bound-partial`은 refinement action과 복원 equality가 모두 0인 초기 하한 성공이다. `incremental-partial`은 둘 다 0보다 큰 실제 점진 강화 성공이다. 기존 `partial_incremental_win` 필드는 이제 실제 `incremental-partial`이면서 검증된 paired Global보다 전체 planner 시간이 짧을 때만 참이다. 이전의 모든 partial 승리 의미는 새 `partial_planner_win`에 보존했다. `target-fully-restored`는 목표 도달 시 relaxation이 이미 완전히 복원된 경우이며 partial 성과로 세지 않는다.

`target_reached`는 method checkpoint의 목표 도달을 뜻한다. Oracle 또는 fingerprint가 없으면 그 행은 `pair_verified=false`이고 certificate·regret·승리 집계에는 포함하지 않는다.

Stage 시간(MBE bound, compact reduction, component preparation/solve, projection, ordered seed, Regional)은 JSON의 `stage_times_at_outcome`에 보존했다. `orderedSeedNanos`가 없는 구현은 0으로 바꾸지 않고 null로 기록한다.

이 결과는 encoded modeled-cost 문제의 인증이다. 실제 workload runtime을 인증하지 않는다.
