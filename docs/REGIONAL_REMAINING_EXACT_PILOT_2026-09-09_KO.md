**Regional + LB + 남은 coupling exact 대 Global exact — compact off 비교 보고서**

작성일: 2026-09-09. 대상: Cofee / SystemDS, so007(`dams-so007`). 상태: 구현, 표적 테스트, 8회 planning-only 실험 및 원본 검증 완료.

이번 소규모 L2SVM 비교에서 새 방식은 전역 최적값을 정확하게 인증했다. 그러나 Global보다 일관되게 빠르지는 않았다. 혼합 privacy에서는 평균 차이가 작고 반복별 승패가 바뀌었으며, 두 입력이 PUBLIC이면 두 반복 모두 Global이 빨랐다. 초기 Regional 계획은 이미 최적이었지만, 이를 증명할 때 다시 푼 exact 작업량은 Global의 약 99.8~99.9%였다.

| Privacy | Global 전체 planning 평균(초) | Regional + LB + 남은 exact 평균(초) | 관측 |
| --- | ---: | ---: | --- |
| X PRIVATE_AGGREGATE / Y PUBLIC | 1.462060 | 1.420169 | 평균 2.87% 짧음, 반복별 승패 교차 |
| X PUBLIC / Y PUBLIC | 1.183244 | 1.741944 | 평균 47.22% 김, 두 반복 모두 느림 |

각 조건은 새 JVM 2회뿐이다. 위 비율은 이번 관측 평균의 차이이며, 통계적으로 입증된 speedup이나 모든 workload에 대한 결과로 해석하지 않는다. 실패·미달 행을 제외해 평균을 개선한 것은 아니다. 이번에는 8/8 시도가 정상 완료됐고 두 방법 모두 4/4 exact endpoint에 도달했다.

사용자가 요청한 “남은 것만 exact”는 다음처럼 구현했다. Regional에서 이미 선택한 계획을 전역 optimum의 증거로 고정할 수는 없다. 따라서 재사용 대상은 첫 global LB를 계산하면서 얻은 독립 component의 exact 결과다.

1. 기존 full Regional을 완료하고 원래 모델에서 feasible한 계획과 canonical 비용 U를 얻는다. 지역 비용 개선을 실제로 수행하며, 추가 재방문은 0회다.
2. 원래 decision과 auxiliary를 모두 포함하는 global encoded model에서 width-2 replica relaxation의 LB를 한 번 계산한다. 지역 밖을 Regional assignment로 고정하지 않는다.
3. 초기 인증이 요청한 threshold를 만족하면 반환한다. 부족하면 아직 분리된 동일 변수의 replica 사이 equality를 모두 복원한다.
4. equality 복원으로 연결되는 현재 component들을 묶어, 영향을 받는 각 묶음을 한 번씩 exact로 푼다. 영향을 받지 않는 component의 비용·assignment는 재사용한다.
5. 모든 equality가 복원됐는지 확인하고, 복원한 assignment의 encoded 비용과 canonical 비용이 raw double bit까지 일치하는지 검증한 뒤 L=U exact 종료를 게시한다.

예를 들어 LB에서 A와 B가 서로 다른 producer state를 가정했다면, 둘을 같은 state로 맞추는 조건을 되살리고 A·B를 함께 다시 푼다. 이 결합과 무관한 C의 이전 exact 결과는 유지한다. A·B의 예전 최솟값만으로 결합한 문제의 최솟값을 결정할 수는 없으므로, 영향을 받은 묶음 내부의 수치 DP table은 다시 계산한다. 이 구현은 Regional의 지역 DP table을 이어 쓰는 기능이 아니다.

중단은 component 묶음별로 처리한다. 완료된 묶음의 강화는 유지하고 미완료 묶음은 이전 유효 LB로 남긴다. 전체 coupling 복원이 끝나지 않았으면 exact 완료로 표시하지 않는다. 매 묶음이 작다는 보장이나 hard wall-clock deadline은 없다. 반복적인 LB 강화, 추가 primal region 확장, 별도의 전체 Global fallback 호출은 이번 경로에 없다.

| 실험 계약 | 고정 값 |
| --- | --- |
| 범위 | L2SVM / P2P2D, LAN, 두 privacy, 두 방법, 각2회 = 8 trials |
| 실행 방식 | so007 native JVM, planning-only; Docker·worker JVM·workload 실행 없음 |
| 입력 shape | X 50000×2100, Y 50000×1; 데이터 실행 없이 고정 metadata 사용 |
| modeled network | C→W 및 W→C 각각 5000 Mbit/s = 625 MB/s, latency 설정 0.001초 |
| JVM | -Xmx8g -Xms8g -Xmn800m -XX:ActiveProcessorCount=8 |
| seed | Regional 20260908, SystemDS 2026072701; modeled workers=1 |
| Global | compact=false, 기존 exact domain reduction/quotient 유지 |
| Regional / LB / 남은 exact | singleton compact=false, global root의 exact domain reduction/quotient 유지 |
| 공통 자원 한도 | factor cells 10,000,000; total cells 50,000,000 |
| 인증 목표 | epsilon=0으로 동일 exact endpoint 비교; 첫 LB의 5%·3% 여부 별도 기록 |
| 시간 한도 | hybrid는 feasible seed 이후 scheduling budget 20초, 전체 JVM 외부 한도 60초; Global도 외부 한도 60초 |
| 순서 | privacy별 1·2회 방법 순서를 반대로 실행; 모든 JVM 순차 실행 |

여기서 compact off는 선택지가 하나인 변수를 모델에서 제거하는 singleton compaction을 끈다는 뜻이다. 기존 Global compact=false에 남아 있는 정확한 domain reduction/동등 상태 병합까지 제거한 raw 모델 비교는 아니다. 두 global 경로는 encoded 변수 전체를 유지했다: 혼합 407개, PUBLIC 422개. Regional local compile도 변수 제거 없이 실행했다. Replica equality를 복원하는 contraction은 같은 encoded 변수의 복제본을 다시 하나로 연결하는 연산이다.

두 방법은 같은 JAR·입력·비용 설정을 사용했고 각 paired trial의 analysis/cost fingerprint가 일치했다. Global은 Regional을 seed로 실행하지 않았다. 소스의 과거 Docker-only/public-ignore 지침은 이번 대화의 명시적인 native JVM 및 두 privacy 조건 요청으로 대체했다. Privacy·placement legality, candidate-space, runtime 규칙은 수정하지 않았다.

| Privacy | 반복 | Global planning(초) | Regional + LB + 남은 exact planning(초) |
| --- | ---: | ---: | ---: |
| X PRIVATE_AGGREGATE / Y PUBLIC | 1 | 1.651147 | 1.412068 |
| X PRIVATE_AGGREGATE / Y PUBLIC | 2 | 1.272974 | 1.428270 |
| X PUBLIC / Y PUBLIC | 1 | 1.086361 | 1.492115 |
| X PUBLIC / Y PUBLIC | 2 | 1.280128 | 1.991772 |

전체 planning은 기존 planner timer의 값이다. JVM 시작 등을 포함한 native wall time이나 modeled plan cost와 구분한다. 아래 시간은 각 privacy의 hybrid 2회 평균이다. 하위 timer는 상위 timer에 이미 포함되므로 모든 행을 합산하면 안 된다.

| 시간 항목(초) | 혼합 | PUBLIC | 포함 관계 |
| --- | ---: | ---: | --- |
| full Regional | 0.356392 | 0.625746 | 지역 선택·준비·풀이 포함 |
| Regional 내부 DP 준비 | 0.061576 | 0.092066 | full Regional의 일부 |
| Regional 내부 DP solve | 0.244996 | 0.476145 | full Regional의 일부 |
| global root reduction | 0.063096 | 0.064012 | 초기 LB 앞단, singleton 제거 없음 |
| 초기 LB 계산 | 0.143476 | 0.136026 | replica 구성 및 초기 component 준비·solve 포함 |
| 남은 exact 전체 | 0.197409 | 0.220200 | 결합 구성·준비·solve 포함 |
| 남은 exact 내부 준비 | 0.170191 | 0.175700 | 남은 exact 전체의 일부 |
| 남은 exact 내부 solve | 0.018611 | 0.034897 | 남은 exact 전체의 일부 |
| root reduction + 첫 LB | 0.206572 | 0.200038 | 위 두 행의 합; 인증 전체 overhead까지 포함한 값은 아님 |
| 전체 planner | 1.420169 | 1.741944 | 모델 형성·검증 등 다른 planner 작업도 포함 |

이번 측정에서 “Regional의 local DP만”은 solve 기준 혼합 약0.245초, PUBLIC 약0.476초였다. DP 준비까지 합치면 약0.307초와 0.568초이고, full Regional 전체는 약0.356초와 0.626초다. 최초 LB는 reduction을 포함해 약0.207초와 0.200초가 추가됐다. 남은 exact는 약0.197초와 0.220초였다. 이 부분 시간과 다른 JVM에서 측정한 Global 전체 시간을 직접 비교해 알고리즘 우위를 판단하지 않는다.

| 인증 값 | 혼합 | PUBLIC |
| --- | ---: | ---: |
| 초기 Regional U (modeled ms) | 846.0188882981099 | 846.0188882981099 |
| 첫 global L (modeled ms) | 480.6543391449701 | 480.6543391449701 |
| 첫 상대 인증 gap | 76.013991635% | 76.013991635% |
| 첫 LB로 5% / 3% 인증 | 모두 미달 | 모두 미달 |
| 독립 Global C* 및 최종 L=U (modeled ms) | 846.0188882981099 | 846.0188882981099 |
| 최종 상대 인증 gap | 0% | 0% |

Regional 계획의 실제 modeled regret는 독립 Global을 확인한 뒤 보면 0이었다. 처음 76.014%라는 값은 계획이 그만큼 나쁘다는 뜻이 아니라, 약한 LB가 허용하는 regret의 상한이다. 이번에는 U를 낮추지 않고 L만 높여 인증을 닫았다. 이 결과는 입력 두 조건에서의 사후 확인이며, 다른 workload의 Regional이 항상 최적이라는 주장이 아니다.

이번 구현은 초기 LB 뒤에 남은 묶음을 모두 exact로 닫는다. 묶음 사이에 3%·5%를 검사해 더 일찍 멈추는 정책은 이번 비교에 넣지 않았다. 따라서 3%·5%는 최종 exact 시점에는 충족됐지만, 그 목표를 달성할 수 있는 가장 빠른 중간 시각은 이 실험으로 측정하지 않았다. 기존 anytime의 목표별 실험과 같은 결과로 합치지 않는다.

| 작업량 / 재사용 | 혼합 | PUBLIC |
| --- | ---: | ---: |
| 초기 LB component 수 | 240 | 254 |
| 결합 복원이 필요한 encoded 변수 | 80 | 80 |
| 영향받은 초기 component 수 | 143 | 157 |
| exact로 다시 푼 연결 묶음 수 | 8 | 8 |
| 재사용한 초기 component 수 | 97 | 97 |
| 재사용 component의 encoded 변수 수 | 110 | 110 |
| 가장 큰 재풀이 묶음의 encoded 변수 수 | 273 | 288 |
| 재사용 component의 elimination assignment 작업 | 110 | 110 |
| 첫 LB의 elimination assignment 작업 | 8,641 | 12,959 |
| 남은 exact의 elimination assignment 작업 | 54,568 | 97,162 |
| 첫 LB + 남은 exact의 assignment 작업 | 63,209 | 110,121 |
| Global exact의 elimination assignment 작업 | 54,678 | 97,272 |
| 남은 exact / Global assignment 작업 | 99.799% | 99.887% |
| 첫 LB + 남은 exact / Global assignment 작업 | 115.602% | 113.209% |

Component 97개를 재사용했지만, 그 component들의 작업량은 합계110이었다. 비싼 결합은 다시 푸는 쪽에 남았다. 따라서 component 개수만 보면 커 보이는 재사용이 실제 elimination 작업을 약0.1~0.2%만 줄였다. 초기 LB 작업까지 합치면 Global보다 혼합15.6%, PUBLIC13.2% 많았고, 여기에 Regional의 별도 지역 최적화가 추가된다. 이 비율은 공통 solver의 작업 카운터 비교이며 wall-clock 시간이 같은 비율로 바뀐다는 뜻은 아니다.

| JVM peak RSS 평균(MiB) | Global | Regional + LB + 남은 exact |
| --- | ---: | ---: |
| X PRIVATE_AGGREGATE / Y PUBLIC | 920.0 | 994.2 |
| X PUBLIC / Y PUBLIC | 953.4 | 1165.2 |

Peak RSS는 planner 객체만의 크기가 아니라 해당 JVM 전체 프로세스의 최대 resident memory다. 초기 LB 및 component 결과를 보관하므로 작은 width만으로 전체 메모리가 작다고 주장하지 않는다.

변경은 기존 exact reduction, replica bound, canonical evaluator와 통계 경로를 재사용했다. 새로운 외부 의존성은 추가하지 않았다. 기본 Regional이나 Global의 기본 compact 설정을 바꾸지 않았으며 새 알고리즘은 `sysds.fedplanner.regional.algorithm=remaining-exact`로 선택한다.

| 수정 파일 | 역할 |
| --- | --- |
| [RemainingExactOptimizer.java](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RemainingExactOptimizer.java) | full Regional 이후 첫 global LB와 남은 coupling의 exact 완료를 연결; 비용 bit parity 및 종료 계약 |
| [IncrementalReplicaBound.java](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalReplicaBound.java) | closeRemaining, exactObjective; 영향받은 연결 묶음의 한 번 풀이와 untouched 결과 재사용 |
| [ExactPhysicalReducedSolver.java](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java) | reducedModel; singleton 변수 유지 및 원래 assignment 복원 |
| [RegionalSearchOptimizer.java](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchOptimizer.java) | remaining-exact 옵션·분기, 미시도/미완료 통계의 0 초기화 |
| [FederatedPlanLocalCost.java](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java) | remainingExactCompletion CONFIG trace |
| [IncrementalReplicaClosureTest.java](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/source-patch/src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalReplicaClosureTest.java) | 5개 테스트: 분리 묶음·tie·이전 refinement·중단 후 재개·부분 resource 실패 |
| [RemainingExactOptimizerTest.java](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/source-patch/src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RemainingExactOptimizerTest.java) | 4개 테스트: reduction 계약·exact parity·초기 목표 종료·resource 미완료 |
| [RegionalCompactTest.java](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/source-patch/src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalCompactTest.java) | 이전 compact 구현의 추가 regression test 포함 |

so007의 격리 빌드에서 Maven clean package 및 8개 관련 test class, 92개 테스트가 모두 통과했다. 새 closure/optimizer 테스트뿐 아니라 기존 exact solver, Regional local optimizer, physical integration의 algorithm 전체 순회도 포함한다. 최초 빌드는 새 통계 키가 미시도/미완료 때 빠져 2개 테스트에서 NPE가 발생했다. 키를 0으로 초기화한 뒤 같은 92개를 재실행해 failure/error/skip 모두 0을 확인했다. 테스트를 제거하거나 기대치를 약하게 바꾸지 않았다. 실패한 첫 로그·소스도 attempt-1에 보존했다.

전체 Java test suite 및 Checkstyle/Spotless/license/RAT는 이번 범위에서 실행하지 않았다. git diff --check와 Python helper AST 검사는 통과했다. 독립 architect가 모델 coverage·부분 commit·수치 parity 계약을 검토했고, 성능 개선은 별도 증거가 필요하다고 판단했다.

8개 원본 실행은 protocol/JAR/입력/command/log/receipt hash, compile-only 설정, runtime execution 시간0, output 부재를 확인했다. 4개 paired 비교 모두 동일 model fingerprint 및 최적 비용이 일치했다. 모든 hybrid checkpoint에서 L≤C*≤U, L 비감소, U 비증가와 보수적인 gap 계산을 확인했다. 4개 hybrid 모두 initial bound1회, 추가 region0회, Global fallback0회, 남은 closure1회 및 모든 equality 복원 후 exact 종료였다.

실험 이후 새 source 7,512개, 이전 source 7,509개, 이전 runtime 318개 hash를 확인했다. 두 privacy별 입력161개/runtime318개가 유지됐으며 output과 활성 pilot 프로세스는 없었다. 이번 추가 실험은 선언한 8회에서 종료했다.

| 산출물 | 절대 경로 |
| --- | --- |
| 현재 보고서 | [/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_REMAINING_EXACT_PILOT_2026-09-09_KO.md](/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_REMAINING_EXACT_PILOT_2026-09-09_KO.md) |
| 이전 compact 실험 보고서 | [/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_COMPACT_FIRST_LB_PILOT_2026-09-09_KO.md](/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_COMPACT_FIRST_LB_PILOT_2026-09-09_KO.md) |
| 소스·JAR 격리 빌드(so007) | [/home/mchoi/so007-regional-remaining-exact-20260909](/home/mchoi/so007-regional-remaining-exact-20260909) |
| 원본 비교 및 모든 trial | [/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/pilot-results.json](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/pilot-results.json) |
| 고정 실행 계획 | [/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/pilot-plan.json](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/pilot-plan.json) |
| 실제 빌드 커맨드 | [/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/native-build-command.json](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/native-build-command.json) |
| 92개 test 결과 | [/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/native-tests.json](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/native-tests.json) |
| 실험 후 보존 감사 | [/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/final-postflight.json](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/final-postflight.json) |
| 소스 delta manifest | [/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/source-patch-manifest.json](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/source-patch-manifest.json) |
| 최초 실패 빌드 보존 | [/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/attempt-1](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/validation/attempt-1) |
| 원본 command·coordinator log·receipt | [/home/mchoi/so007-regional-remaining-exact-evidence-20260909/native/runs](/home/mchoi/so007-regional-remaining-exact-evidence-20260909/native/runs) |

JAR SHA-256: `2f42f568e33ff45efba4f210b8f77534a174a7e8f0b6795a6524a817452be364`.
Source manifest SHA-256: `a178e79e2694d7cd9fe9bf5b21681a0b82e9d33be845817fb55124b515cefef6`.

이번 결과가 지지하는 판단은 제한적이다. “Regional 후 LB를 계산하고 필요한 결합만 exact로 닫기”는 전역 인증을 유지하며 구현 가능하다. 다만 이 L2SVM에서는 무거운 결합이 대부분 남아 있어 계산 재사용만으로 큰 이득을 얻지 못했다. 첫 LB만으로 3%·5%를 만족하는 workload에서는 exact 단계를 생략할 수 있지만, 이번 두 조건에서는 첫 LB가 그 정도로 강하지 않았다.
