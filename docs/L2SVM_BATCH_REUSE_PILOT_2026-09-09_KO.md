# L2SVM: 묶음 LB 강화와 component 준비 재사용 구현·검증 보고서

작성일: 2026-09-09. 서버: so007(`dams-so007`). 상태: **두 기능 구현 완료, targeted build/tests 및 9회 planning-only 검증 완료**.

## 1. 결과부터

두 기능 모두 구현했으며 인증의 정확성 검증을 통과했다. 이번 혼합 privacy / 3% 관측에서는 **묶음 강화만 적용한 방법이 네 Anytime 구성 중 가장 빨랐다.** 기존 단일 강화의 전체 planning 시간 2.000초가 1.248초로 줄었다. 관측 감소율은 37.6%다.

그러나 동일 실행 집합의 Global은 0.724초였다. **이번 개선으로 L2SVM에서 Global보다 빠른 3%·5% 인증을 달성했다는 결론은 내릴 수 없다.** 두 기능을 함께 적용한 3% 실행도 1.806초로, 묶음만 적용한 실행보다 느렸다. 각 cell은 1회라 시간 순위와 감소율은 pilot 관측이며 통계적 우위가 아니다.

요청한 개선이 겨냥한 문제는 분명히 줄었다. LB 강화 작업은 39회에서 6회로 감소했다. 모든 Anytime 실행에서 초기 계획의 비용은 이미 Global과 같았으며, 이후 계획 비용 U는 바뀌지 않았다. 이번에는 계획을 더 좋게 만드는 대신 **같은 계획의 품질을 더 정밀하게 인증**했다.

## 2. 실험 범위와 비교 조건

- L2SVM / P2P2D, X 50,000×2,100, Y 50,000×1, worker 수를 모델상 1로 설정했다.
- mixed는 X=PRIVATE_AGGREGATE, Y=PUBLIC이며, public은 X/Y 모두 PUBLIC이다.
- LAN 모델값만 사용했다: C2W/W2C 5,000 Mbit/s, cost bandwidth 625 MB/s, latency 0.001초. Docker나 traffic shaping은 실행하지 않았다.
- so007의 새 JVM에서 compile-only로 실행했다. 데이터 값, 실제 workload, worker JVM을 실행하지 않았다. 모든 9개 receipt에서 runtime_executed=false, execution_seconds=0을 확인했다.
- JVM 설정은 Xmx/Xms 8 GiB, Xmn 800 MiB, ActiveProcessorCount=8. SystemDS seed=2026072701, Regional seed=20260908이다.
- 초기 width=2, 최대 batch=8 encoded 변수, 후보 probe=1, factor/total cells=1,000,000/5,000,000, component/region work limit=1,000,000, 최대 강화 256회다. 추가 제한 Regional 개선은 최대 2회다.
- scheduling budget은 초기화 이후 20초이며 phase 중간의 hard deadline은 아니다. 외부 watchdog은 60초이고, 중단된 JVM은 성공 인증으로 보고하지 않는다.
- mixed/3%는 Global과 batch×reuse 2×2의 5회, public/3%는 Global과 통합법 2회, mixed/public의 통합법 5% 각 1회로 **총 9회**다. 추가 workload나 네트워크 실험은 수행하지 않았다.

Global은 같은 새 JAR의 production **compact + exact** 경로다. privacy별 독립 Global을 한 번씩 계산했고, 목표와 무관한 그 최적값을 해당 privacy의 3%·5% 검증에 함께 사용했다. Global 결과는 Anytime의 온라인 입력으로 주지 않았다.

모든 Anytime은 정상적인 local interaction/materialization 처리까지 완료한 같은 full Regional에서 출발한다. 과거 V5의 ordered-only seed와 이번 단일 강화 기준선을 혼동하면 안 된다. 기존 기본값은 유지하고 이번 비교에서 full seed를 명시적으로 켰다.

## 3. 첫 번째 구현: 여러 결정을 함께 복원

기존 구현은 한 encoded 변수의 replica 일치 조건을 복원한 뒤 관련 component를 다시 풀었다. 이번 구현은 그 변수를 시작점으로 **관련 component를 공유하는 변수들을 최대 8개까지 모아 한 번에 복원**한다. 여기서 encoded 변수는 원래 물리 결정뿐 아니라 auxiliary를 포함할 수 있다. `batch=8`은 물리 region 크기가 8이라는 의미가 아니다.

후보 확장은 이미 접촉한 component를 활용하고, 새로 연결해야 하는 component가 적은 후보를 먼저 선택한다. 같은 비용을 갖는 선택도 이후 coupling의 정체를 풀 수 있으므로 단일 disagreement가 0인 후보도 포함할 수 있다. 이 선택은 휴리스틱이며 최대 이득을 찾는 정리는 아니다.

각 encoded 변수 내부의 replica끼리만 equality를 추가한다. 서로 다른 원변수를 같은 값으로 강제하지 않는다. trial용 contraction state를 복사해 touched component를 한 번 풀고, 성공과 중단 여부를 확인한 뒤 모두 반영한다. 영향을 받지 않은 component 결과는 유지한다.

batch가 cell/work 한도를 넘으면 그 batch를 반영하지 않고, 시작 변수 하나를 복원하는 시도를 한 번 한다. 단일 시도도 실패했을 때 해당 단일 후보를 resource-blocked로 기록한다. batch 실패 때문에 실행 가능한 단일 후보를 제거하지 않는다. 기존 lower bound와 원래 문제의 coverage는 유지된다.

묶음 크기 외에도 exact cell/work 한도를 검사한다. 다만 이 한도 판정에는 준비 비용이 들며, 준비가 시작되기 전에 항상 저렴하게 비용을 알 수 있는 것은 아니다. 이번 9회에서는 batch→single fallback이 발생하지 않았고, 해당 경로는 작은 회귀 테스트로 검증했다.

## 4. 두 번째 구현: component 준비 재사용

현재 component의 exact 결과가 보유한 **변수 key의 제거 순서**를 보관한다. 다음 component를 구성할 때 복원한 equality에 따라 key를 현재 representative로 투영하고 중복을 없애 새 순서 후보를 만든다.

그 순서를 현재 변수·domain·factor scope에서 다시 검증하고, separator·유도 scope·table 크기·work를 새로 계산한다. 허용되면 제거 순서를 탐색하는 비용을 줄인다. 순서가 유효하지 않거나 cell/work 한도를 넘으면 기존 일반 compile 경로로 전환한다.

수치 table, 이전 factor evaluator, backpointer, 과거 variable-index mapping을 새 문제에 재사용하지 않는다. 새로운 factor 값은 실제 solve에서 다시 계산한다. 대형 MBE table을 노드마다 추가 보관하는 구조도 아니다. 재사용 helper는 인스턴스별 통계를 보유하며 현재 component 결과의 순서를 활용한다.

한계는 **이전 문제에서 좋은 순서가 합쳐진 문제에서도 좋은 순서라는 보장이 없다는 것**이다. 한도 안이어도 더 많은 연산을 할 수 있고, 한도를 넘으면 재사용 준비와 일반 compile 준비를 모두 지불할 수 있다. 동률인 relaxed assignment가 달라져 이후 후보 선택 경로도 바뀔 수 있으므로 on/off는 순수한 cache microbenchmark가 아니다.

## 5. 혼합 privacy, 3% 목표의 2×2 결과

| 방법 | 전체 planner 초 | 최초 3% 인증 초¹ | LB 강화 횟수 | 최종 인증 gap | Global 대비 시간 |
| --- | --- | --- | --- | --- | --- |
| Global: compact + exact | 0.724438 | — | — | 0.000000% | 1.000배 |
| 기존 단일 강화 | 2.000367 | 1.843827 | 39 | 2.875318% | 2.761배 |
| 단일 강화 + 준비 재사용 | 1.308149 | 1.153877 | 31 | 2.840486% | 1.806배 |
| 묶음 강화 | 1.248478 | 1.062042 | 6 | 0.693330% | 1.723배 |
| 묶음 강화 + 준비 재사용 | 1.805569 | 1.457524 | 6 | 0.693330% | 2.492배 |

¹ 최초 인증 시간은 Regional method 진입부터 조건을 만족한 checkpoint까지다. 모델 준비, Regional seed와 초기 LB를 포함하지만 이후 계획 반영·나머지 compile 처리는 끝나지 않았을 수 있다. **Global과의 주 비교는 전체 planner 열**을 사용한다. JVM 시작·class loading까지 포함한 wall time은 별도 표에 있다.

독립 Global의 modeled optimum과 모든 seed/final U는 다음과 같다.

```text
C* = U = 846.0188882981099 modeled ms
초기 L (mixed) = 480.65433914497424
초기 L (public) = 480.65433914497413
```

초기 인증 gap은 약 76.014%지만 실제 modeled regret는 이번 oracle 비교상 0이다. 따라서 이 L2SVM 결과에서 약했던 것은 Regional 계획의 품질이 아니라 최초 relaxation의 하한이다. privacy별 모든 구성의 초기 U, assignment fingerprint, 초기 L, order fingerprint 및 초기 component/replica 수가 일치했다. privacy 사이의 assignment와 모델 fingerprint는 다르므로 두 문제를 섞어 검증하지 않았다.

## 6. 시간과 작업량에서 확인한 효과

| 방법 | Regional 전체 초 | 초기 compact 초 | 초기 LB 초 | 추가 LB 강화 초 | component 준비 초² | component solve 초² | 추가 Regional 초 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 기존 단일 강화 | 0.255405 | 0.049340 | 0.075507 | 1.038965 | 0.967639 | 0.058994 | 0.017756 |
| 단일 강화 + 준비 재사용 | 0.254955 | 0.048251 | 0.068924 | 0.363674 | 0.083828 | 0.272246 | 0.023557 |
| 묶음 강화 | 0.249674 | 0.054888 | 0.077970 | 0.273278 | 0.260115 | 0.023662 | 0.017236 |
| 묶음 강화 + 준비 재사용 | 0.248327 | 0.093460 | 0.123532 | 0.468176 | 0.418246 | 0.068367 | 0.029532 |

² component 준비와 solve는 초기 LB 및 추가 LB의 내부 시간이다. 다른 열에 다시 더하면 중복 계상된다. 준비에는 현 component contraction과 compile/재사용 전환 비용이 들어간다. 추가 Regional 열은 full seed 이후 수행한 제한된 개선 시도의 solve 시간이며 full Regional 시간과 다르다.

| 방법 | 강화 횟수 | 정확히 ΔL=0인 횟수 | 재사용 성공 / 일반 순서 전환 | component 누적 assignments | 최종 component 수 |
| --- | --- | --- | --- | --- | --- |
| 기존 단일 강화 | 39 | 27 | 0 / 0 | 311,183 | 10 |
| 단일 강화 + 준비 재사용 | 31 | 20 | 24 / 7 | 1,868,572 | 16 |
| 묶음 강화 | 6 | 0 | 0 / 0 | 66,931 | 32 |
| 묶음 강화 + 준비 재사용 | 6 | 0 | 1 / 5 | 116,810 | 32 |

단일 재사용은 component 준비 시간을 0.968초에서 0.084초로 줄였다. 반면 solve 시간은 0.059초에서 0.272초로 늘었고 assignments도 311,183에서 1,868,572로 증가했다. 순서 탐색을 아낀 대신 더 비싼 제거 순서로 계산한 비용과 탐색 경로 변화가 함께 있다.

묶음만 적용하면 준비 0.260초, solve 0.024초, 강화 6회였다. 단일 복원을 여러 번 거쳐야 했던 coupling을 한 번에 묶는 효과가 보였다.

두 기능을 함께 적용한 mixed/3%에서는 **재사용 성공 1회, 일반 compile 전환 5회**였다. 준비 0.418초와 solve 0.068초가 묶음만 적용한 실행보다 모두 길었다. 같은 최종 L과 6단계를 얻어도 반복된 순서 전환과 더 큰 work가 추가됐다. 이 관측은 두 기능을 모두 기본으로 켜야 한다는 근거가 아니다.

`정확히 ΔL=0`은 부동소수점의 엄격한 동등 비교다. 묶음의 2·3단계에서는 약 10⁻¹³ 수준의 증가만 있었다. 따라서 표의 0회를 보고 모든 묶음이 실질적인 개선을 했다고 해석하지 않는다.

### 묶음만 적용한 실제 LB 진행

| 강화 단계 | L | 인증 gap | 선택 encoded 변수 수 | 접촉 component 수 | 이번 exact assignments |
| --- | --- | --- | --- | --- | --- |
| 1 | 486.500441650 | 73.898894% | 8 | 11 | 1,249 |
| 2 | 486.500441650 | 73.898894% | 8 | 16 | 1,412 |
| 3 | 486.500441650 | 73.898894% | 8 | 15 | 7,193 |
| 4 | 753.236612412 | 12.317813% | 8 | 14 | 9,062 |
| 5 | 781.788104740 | 8.215881% | 8 | 18 | 15,751 |
| 6 | 840.193577081 | 0.693330% | 8 | 21 | 24,074 |

큰 증가는 4단계에서 나타났고 6단계에서 3%·5%를 동시에 통과했다. 마지막 gap이 약 0.693%인 것은 한 묶음의 개선이 요청 목표를 넘어선 이번 결과이며, 별도 1% 실험이나 일반적인 1% 성능 보장이 아니다. 모든 기록의 U는 그대로였다.

최대 component footprint는 이 실행에서 원래 replica 인덱스 기준 438개였고, 초기 전체 replica는 534개다. batch 크기가 8이어도 내부 계산 대상 component는 클 수 있다. 다만 최종 `fullyRestored=0`, component 32개가 남았고 `wholeClosureCompleted=0`으로, 원래 전체 문제 exact 종료를 호출해 목표를 맞춘 결과는 아니다.

## 7. 통합법의 3%·5% 결과

| Privacy | 요청 목표 | 전체 planner 초 | 최초 목표 인증 초¹ | 강화 횟수 | 최종 L | 최종 인증 gap | 동일 privacy Global 초 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| mixed | 5% | 1.293252 | 1.123822 | 6 | 840.193577081 | 0.693330% | 0.724438 |
| mixed | 3% | 1.805569 | 1.457524 | 6 | 840.193577081 | 0.693330% | 0.724438 |
| public | 5% | 1.357102 | 1.194104 | 6 | 810.484512219 | 4.384337% | 0.795816 |
| public | 3% | 1.685787 | 1.527694 | 7 | 821.740925477 | 2.954455% | 0.795816 |

요청된 목표는 네 실행 모두 달성했다. public/3% 실행은 같은 trajectory에서 5%를 1.457529초에, 3%를 1.527694초에 처음 인증했다. 이 두 값은 method 진입 기준이며 동일 실행 안의 비교다. 초기화 이후 값으로는 각각 약 0.358899초와 0.429059초다.

mixed 통합법은 3%와 5% 실행 모두 동일한 6단계와 최종 L을 얻었다. 전체 시간 1.806초와 1.293초의 차이를 threshold를 완화한 알고리즘 효과로 해석할 수 없다. 서로 다른 fresh JVM 1회씩에서 관측한 시간 변동이 포함된다. public/5% 실행은 6단계에서 4.3843%로 멈췄고, 3% 실행은 7단계까지 진행했다.

## 8. Global보다 아직 느린 이유와 이번 판단

이번 mixed Global의 compact exact elimination assignments는 47,369다. 묶음의 component 누적 assignments는 초기 relaxation과 반복 계산을 합해 66,931이며, 여기에 full Regional, compaction, 후보 진단·검증·추가 Regional 등의 비용이 붙는다. 이 work 숫자는 실제 CPU instruction 수가 아니지만 전체 exact가 이미 작은 문제라는 점과 반복 인증의 추가 작업을 보여 준다.

혼합 구성들의 full Regional만 약 0.25초였고 최초 LB 약 0.07~0.12초, compact 준비 약 0.05~0.09초가 필요했다. 목표에 도달하기 전에 반드시 지불하는 초기 비용과 추가 강화 비용을 함께 줄여야 Global 0.724초보다 빨라질 수 있다. 이번 결과는 LB 강화 내부의 개선을 확인했으나 그 조건을 충족하지 못했다.

이번 cell에서 다음 개발 기준으로 삼을 관측은 **batch 적용을 먼저 검토하고 준비 재사용은 선택적으로 취급**하는 것이다. 재사용을 일반 기본값으로 바꾸지 않았다. 향후 개선은 합쳐진 component에서 이전 순서가 계속 적절한지 더 싸게 판정하거나, 변경된 separator만 다시 준비하는 구조를 검토할 수 있다. 그 추가 기능은 이번에 구현·실험하지 않았다. 이번 요청의 작은 검증은 9회에서 종료한다.

## 9. 정확성 검증과 수치 계약

하한은 원래 전체 encoded model의 replica relaxation에서 계산한다. 누적 equality 복원은 원래 feasible assignment를 제거하지 않는다. 각 새 component를 exact로 풀고 기존 유효 하한과 보수적인 합을 유지하므로, 기존 모델·수치 계약 아래 각 checkpoint에서 `L ≤ C* ≤ U`, L 비감소, U 비증가를 유지한다. 제한된 Regional의 optimum을 global LB로 쓰지 않는다.

목표는 L>0에서 보수적으로 계산한 `(U−L)/L ≤ ε`로 확인한다. L=0에서 임의 epsilon 분모로 상대 인증을 선언하지 않는다. 여기서 인증은 **encoded modeled cost 대비 최적성**이며 실제 workload 실행시간이나 비용 예측 오차를 인증하지 않는다.

- so007 Maven `clean package`: **117 testcase 중 116개 실행 통과, failures=0, errors=0**, targeted class 10개.
- 기존 선택형 TSV exporter `writesControlledAblationRowsWhenOutputIsConfigured` 1개는 출력 경로 미지정으로 skip됐다. 새 correctness test는 skip되지 않았다.
- 새 테스트는 단일 equality로는 개선되지 않는 두-variable plateau의 batch 해소, batch resource 실패 후 single 성공, 계산 직후 cancellation의 published-state 보존, 순차 재사용과 oracle 일치, domain/scope/수치 변경 시 재검증, work/resource 전환, 네 구성의 동일 full seed를 다뤘다.
- 새 helper 테스트는 [src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ReplicaComponentPreparationTest.java](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/source-patch/src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ReplicaComponentPreparationTest.java), batch 테스트는 [src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalReplicaBoundTest.java](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/source-patch/src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalReplicaBoundTest.java), 물리 통합 검증은 [src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchPhysicalIntegrationTest.java](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/source-patch/src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchPhysicalIntegrationTest.java)에 있다.
- `git diff --check`, Python syntax 및 네 tuning 설정의 CONFIG 검증을 통과했다. 프로젝트 전체 checkstyle/spotless/license/RAT 검사는 실행하지 않았고 전체 Java suite의 통과를 주장하지 않는다.
- 독립 native architect의 bounded read-only 검토는 blocking correctness 결함을 찾지 못했고, 큰 batch와 재사용 순서의 성능 위험을 남겼다.
- 원본 로그/receipt/command/observation hash를 검증했다. 정상 9/9, 인증 7/7, 각 인증의 독립 oracle 포함과 단조성 7/7, 초기 상태 일치를 확인했다. 실패·목표 미달 행은 이번에 없었으며 선별 재실행하지 않았다.

## 10. JVM wall time과 메모리

| Privacy | 목표 | 방법 | JVM wall 초 | peak RSS MiB |
| --- | --- | --- | --- | --- |
| mixed | exact | Global: compact + exact | 3.942978 | 752.82 |
| mixed | 3% | 기존 단일 강화 | 4.959103 | 1174.25 |
| mixed | 3% | 단일 강화 + 준비 재사용 | 4.450865 | 1165.68 |
| mixed | 3% | 묶음 강화 | 4.438361 | 1155.05 |
| mixed | 3% | 묶음 강화 + 준비 재사용 | 4.860575 | 1112.16 |
| public | exact | Global: compact + exact | 3.893962 | 811.10 |
| public | 3% | 묶음 강화 + 준비 재사용 | 4.957812 | 1191.06 |
| mixed | 5% | 묶음 강화 + 준비 재사용 | 4.328511 | 1116.55 |
| public | 5% | 묶음 강화 + 준비 재사용 | 4.547310 | 1169.09 |

peak RSS는 Linux wait4가 관측한 fresh JVM의 최대 resident memory다. class loading, JVM, 전체 compiler를 포함하며 알고리즘의 live heap 또는 재사용 cache 크기만 나타내지 않는다. Xmx 8 GiB와 실제 RSS를 혼동하지 않는다. 이번 재사용이 전체 memory를 줄였다는 인과 주장은 하지 않는다.

## 11. 변경 파일과 사용 옵션

아래 파일 링크는 실제 빌드와 hash가 일치하는 staged source를 가리킨다.

| 파일 | 변경 내용 |
| --- | --- |
| [IncrementalReplicaBound.java](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalReplicaBound.java) | 연결된 batch 구성, group별 equality 복원, atomic commit, 단일 fallback, 이전 제거 순서 투영 |
| [ReplicaComponentPreparation.java](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ReplicaComponentPreparation.java) | 새 파일. 현재 모델을 검증하는 symbolic order 재사용과 일반 compile 전환 |
| [ExactCategoricalSolver.java](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java) | 현재 scope로 지정된 제거 순서를 컴파일하는 package-private 진입점. 기존 Global의 기본 compile 경로 유지 |
| [IncrementalAnytimeOptimizer.java](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalAnytimeOptimizer.java) | tuning 옵션, batch 전체의 primal 후보 연계, 작업량·시간·재사용 통계 |
| [LocalPhysicalOptimizer.java](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java) | 선택형 full Regional 초기화와 별도 seed 시간 계측 |
| [FederatedPlanLocalCost.java](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java) | 실제 batch/reuse/full-seed 옵션을 CONFIG trace에 기록 |

새 옵션은 다음과 같다. 모두 `sysds.fedplanner.regional.` 접두어를 사용한다.

| 옵션 | 기존 기본값 | 이번 묶음+재사용 실험값 | 의미 |
| --- | --- | --- | --- |
| `incrementalBatchVariables` | 1 | 8 | 한 trial에서 복원하는 encoded-variable group 최대 수. 허용 1~32 |
| `incrementalReusePreparation` | false | true | 이전 component의 symbolic 제거 순서 사용 |
| `incrementalFullSeed` | false | true | normal Regional 전체를 완료한 뒤 초기 MBE와 강화 시작 |

묶음만 쓰려면 batch=8, reuse=false, fullSeed=true로 설정한다. 단일 비교는 batch=1이며 나머지 초기화·자원·목표 설정을 맞춘다. 실제 실행의 모든 cost 환경과 JVM 인자는 각 trial의 `command.json`에 보관했다. 단순히 Java 옵션을 넣는 것만으로 compile-only가 되는 것은 아니므로 재현 시 frozen compile-only config와 harness를 함께 사용해야 한다.

## 12. 절대 경로와 재현 근거

- 구현 작업 소스(현재 로컬 작업 트리): `/home/mchoi/so007-anytime-incremental-20260908`.
- **so007의 새 구현 및 검증된 빌드**: `/home/mchoi/so007-anytime-batch-reuse-20260909`.
- 새 JAR: `/home/mchoi/so007-anytime-batch-reuse-20260909/target/SystemDS.jar`.
- JAR SHA-256: `4629b63aafb672cf1ac84e09e0c2314f9679ce0dfab305d4ee5f6340c5afdca9`.
- source manifest SHA-256: `c4cfb5befaff09b5db4fca295dc3241a0971d2b9015d85bcace27f613d8343a9`.
- 결과·설정·원본 evidence: `/home/mchoi/so007-anytime-batch-reuse-evidence-20260909`. runtime을 제외한 보고·원본 로그는 로컬과 so007에 같은 경로로 동기화한다.
- [검증된 구조화 결과](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/validation/pilot-results.json), [빌드·테스트](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/validation/native-tests.json), [독립 코드 검토](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/validation/final-code-review.md), [보존 검증](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/validation/final-postflight.json).
- [9회 고정 실험 계획](/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/validation/pilot-plan.json), `native/runs/*/protocol.json`, `context.json`, `harness_snapshot/`, `trials/*/command.json`, `observation.json`, `receipt.json`, coordinator 로그.
- 원래 Global/Regional certificate pilot 사본 `/home/mchoi/so007-regional-certify-20260909` 및 그 frozen runtime은 보존한다. 이번 source delta 9개 파일은 `source-patch-manifest.json`으로 검증했으며 `implementation.patch`는 로컬 git 기준 기존 변경도 포함한 전체 diff다. 새 변경은 미커밋 상태다.

GLM WAN_mid lowering의 occurrence identity 문제는 이번 L2SVM 개선과 별개이며 수정하거나 재검증하지 않았다. 후보 공간, privacy·placement legality, canonical objective, runtime fallback 규칙은 변경하지 않았다.
