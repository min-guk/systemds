# 인증이 없는 Regional이 Global보다 오래 걸린 측정의 해석

작성일: 2026-09-09. 기존 8회 compact ablation의 소스·로그를 다시 확인한 설명이다. 새 Java 변경, 빌드 또는 실험은 수행하지 않았다.

## 확인한 사실

PUBLIC L2SVM에서 일반 Regional의 전체 planner는 1.321210초, singleton compact를 끈 Global은 1.000791초였다. 일반 Regional은 정상적인 지역 개선까지 실행했고, 인증/MBE는 실행하지 않았다.

| 일반 Regional 내부 구간 | 시간(초) |
| --- | ---: |
| 지역 DP 준비 | 0.091184351 |
| 지역 DP solve | 0.463994471 |
| 그 밖의 Regional seed 작업 | 0.064870695 |
| Regional seed 전체 | 0.620049517 |
| seed 밖의 planner 작업 | 0.701160483 |
| 전체 planner | 1.321210000 |

세부 세 구간의 합이 Regional seed 전체이며, seed 전체와 seed 밖의 작업을 더하면 전체 planner다. 중첩된 행을 모두 더하지 않는다. seed 밖의 값은 같은 JVM 전체 시간에서 계측된 seed 시간을 뺀 잔여 시간이다. 모델·비용 surface 구축, canonical 검증, 계획 선택/반영, trace 등 여러 처리가 들어가지만 각 항목의 시간은 별도로 측정되지 않았다.

따라서 1.321초를 순수 local DP 시간으로 읽으면 안 된다. 또한 0.701초는 숨겨진 인증 시간이 아니다.

## 인증이 없어도 더 오래 걸릴 수 있는 이유

Global과 Regional은 계획 생성 절차가 다르다. Global은 전역 factor 모델을 reduction/quotient한 다음 한 elimination plan으로 푼다. compact=false는 singleton 치환만 생략하며 이 전처리와 exact DP를 유지한다. 모든 전체 계획 조합을 일일이 열거하는 실행이 아니다.

Regional은 ordered 초기 선택 후 여러 interaction/materialization block을 조건부로 준비하고 푼다. 작은 문제라도 block별 준비와 풀이가 누적될 수 있으며, 겹치는 부분을 서로 다른 boundary 조건에서 다시 처리할 수 있다. PUBLIC 로그의 localBlocks는 4, factorizedCompilations/solves는 각각 3, factorwiseSkips는 1이다. 추가 revisitPasses와 localRevisits는 0이므로 optional 재방문 정책을 켠 결과는 아니다.

| PUBLIC 작업 지표 | Regional | Global compact off |
| --- | ---: | ---: |
| 기록된 assignment 작업 카운터 | blockAssignments=566030 | eliminationAssignments=97272 |

Regional 다변수 block의 searchAssignments는 내부 ExactCategoricalSolver의 eliminationAssignments에서 온다. 이를 block별로 누적한다. singleton 직접 열거는 completeAssignments를 더하므로 모든 경우에 완전히 동일한 카운터는 아니다. Global의 값은 전체 elimination plan의 각 단계에서 separator cells × 제거 변수 domain 크기를 합한 값이다. 이 지표는 Regional의 지역 풀이도 가벼운 작업으로 단정할 수 없다는 근거지만, 두 숫자의 비율을 CPU 연산량이나 시간 비율로 해석하지 않는다. 중간 table 생성, factor 평가 단가, 준비 비용도 다르다.

## 남아 있는 측정 불확실성

같은 PUBLIC 모델에서 묶음 Anytime이 먼저 수행한 full Regional도 동일한 localBlocks/solves와 blockAssignments=566030을 기록했지만 seed 시간은 0.397808469초였다. 일반 Regional의 0.620049517초와 차이가 크다. 서로 다른 fresh JVM이며 JIT, 스케줄링, trace 등의 영향을 따로 분리하지 않았다. 따라서 0.320419초의 전체 시간 차이를 block 중복 계산 하나의 인과 효과로 확정할 수 없다.

기존 표는 해당 JVM에서 실제 관측한 시간을 정확히 기록한다. 다만 이 한 번의 관측으로 Regional이 일반적으로 Global보다 느리다는 결론을 내리지 않는다. 현재 비교에는 Global의 whole-model reduction/quotient와 Regional의 별도 local 처리 경로 차이도 남아 있다.

후속 최적화의 근거는 인증만이 아니라 Regional baseline의 block 준비·풀이·전처리 비용도 살펴봐야 한다는 점이다. 어떤 개선이 실제 시간을 줄이는지는 별도 검증 대상이다.

## 재현 근거

- [기존 결과와 원본 로그 경로](/home/mchoi/so007-global-compact-ablation-evidence-20260909/validation/ablation-results.json)
- [기존 compact ablation 보고서](/home/mchoi/so007-anytime-incremental-20260908/docs/GLOBAL_COMPACT_ABLATION_2026-09-09_KO.md)
- [전체 planner 계측 범위](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/parser/DMLTranslator.java:400)
- [모델 준비와 계획 반영 경로](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java:47)
- [Regional seed와 인증의 별도 경로](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java:203)
- [지역 solver 통계 변환](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java:504)
- [block별 통계 누적](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java:1064)

기존 raw log, 결과 JSON 및 frozen 소스는 변경하지 않았다.

## HOP별 처리와 지역별 DP의 구분

후속 질문에 따라 초기 선택의 의미를 명확히 한다. 현재 `LocalCategoricalOptimizer.optimize`는 먼저 producer-before-consumer 순서로 각 원래 결정을 방문해 `selectLocalState`를 호출한다. 이 함수는 현재까지 assignment가 정해진 factor의 legality/비용을 비교한 뒤 상태 하나를 선택한다. 여러 HOP의 상태별 최적 subplan을 저장해 부모가 재사용하는 bottom-up DP 테이블 계산이라고 설명해서는 안 된다.

그다음 HOP과 직접 입력, shared producer-consumer 묶음, materialization 경계를 기반으로 만든 여러 결정의 집합을 joint solve한다. 이 집합을 코드에서는 block, 앞선 설명에서는 region이라고 불렀다. 밖의 선택을 고정하고 안의 선택들을 함께 비교한다. 지역 내부 exact variable elimination도 변수를 차례로 처리하지만, `factorizedSolves=3`은 이 내부 변수 처리 횟수가 아니라 지역 solver 호출 세 번을 의미한다. PUBLIC의 `maxBlockVariables=78`도 region이 단일 HOP만 뜻하지 않음을 보여 준다.

따라서 현재 측정한 baseline은 **HOP 순서의 초기 상태 선택 + 여러 HOP을 묶은 지역 exact 개선**이다. 이를 단순히 HOP별 bottom-up DP라고 부르면 구현을 잘못 전달한다. 논문이나 이전 버전의 Regional과 동일한 알고리즘인지 판단하려면 해당 정의와의 별도 대응 확인이 필요하며, 이번 로그 확인만으로 동등성을 주장하지 않는다.

근거: [초기 순회와 지역 개선](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java:548), [초기 상태 선택](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java:671), [지역 구성](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java:294).

## 최초 LB 한 번과 3%까지 반복 강화한 Anytime의 구분

최근 compact ablation의 Anytime 1.245995/1.428013초는 3% 달성까지 강화한 시간이며 최초 LB만 한 번 계산한 시간이 아니다. 최초 LB 한 번은 이전 RegionalCertify pilot에서 따로 측정했다. 그 pilot의 Global은 compact on이고 JAR도 이전 버전이므로 최신 compact-off Global 시간과 섞어 비교하지 않는다.

| 이전 L2SVM pilot | 혼합 privacy | 모두 PUBLIC |
| --- | ---: | ---: |
| Regional + 최초 LB 전체 planner 초 | 1.082844 | 1.304845 |
| 같은 pilot Global compact on 초 | 1.211039 | 0.989488 |
| 같은 Regional 실행 내부의 인증 추가 전체 초 | 0.135770259 | 0.192251569 |
| 그중 순수 MBE 초 | 0.054915833 | 0.087860629 |

Regional seed에는 whole-model compact가 없지만 이 인증 경로에는 LB용 compact가 있다. 추가 인증 전체에는 그 준비와 검증도 포함한다. 최초 LB 후 상대 오차 상한은 두 조건 모두 76.0139916%로 3%·5% 목표에는 부족했다. 혼합에서는 총시간이 Global보다 짧았고 PUBLIC에서는 더 길었다. Cell당 한 번의 관측이며 “LB 한 번이 Global만큼 비싸다” 또는 “Regional+LB는 언제나 Global보다 느리다”는 결과가 아니다. Regional과 LB 모두 compact를 끈 구성과 Global off를 같은 JAR로 묶어 측정한 결과는 이 두 pilot에 없다.

검증 근거: [최초 LB 1회 paired 결과](/home/mchoi/so007-regional-certify-evidence-20260909/validation/pilot-results.json). 새 실험은 수행하지 않았다.
