# Local DP 초기 seed와 경계 조합 최적화

Local DP의 초기 seed는 cluster별 경계 조건부 최적해를 활용하여 개선할 수 있다. 충돌하는 공유 경계의 조합을 평가하고 각 cluster 내부 계획을 복원하면, 모든 외부 경계 조합에 대한 새 병합 테이블을 저장하지 않고도 더 저렴한 실행 가능 계획을 선택할 수 있다.

기준 커밋의 초기 seed는 결정적인 순차 greedy 선택과 조건부 exact conflict repair로 생성된다. 일반적인 비용 개선 block pass는 seed 생성 호출에서 비활성화되어 있다. 후속 구현은 이 실행 가능한 seed를 첫 persistent merge 전에 조건부 exact 최적화로 개선한다.

## 분석 기준과 범위

아래 최초 분석·ML 표는 `0146f043e0`의 역사적 기록이다. 후속 사용자 요청으로 최신 `origin/main`을 fetch하여 `ada24ffd4b`에 통합한 재검증은 문서 마지막 절에 구분한다. 그 뒤 진행한 후보 확장 개선은 [별도 문서](LEGAL_CANDIDATE_EXPANSION_2026-10-06_KO.md)를 따른다.

- 기준: 2026년 10월 6일 분석 시점의 로컬 `origin/main`.
- 커밋: `0146f043e07ca445d9084257759aa78fe14ddf55`.
- 소스 위치: `/home/mchoi/w1357-structural-grounding-20261006`.
- 대상: `COMPILE_COST_BASED`가 사용하는 `FederatedPlanLocalCost`의 seed 및 incremental regional 최적화 경로.
- 상태: 기준 커밋 분석 후 초기 경계 조합 개선을 구현했다. 아래 구현 절과 검증 결과를 함께 참조한다.

기존 동작 분석은 위 커밋을 기준으로 하며, 후속 구현은 같은 소스에 적용했다. 다른 worktree의 후속 비용 수정이나 merge 진행 상태는 분석에 포함하지 않는다. Planner 매핑은 [FederatedPlannerFactory.java](../src/main/java/org/apache/sysds/hops/ipa/FederatedPlannerFactory.java#L34)에서 확인할 수 있다.

## 적용한 구현

`IncrementalRegionalOptimizer`는 `INITIAL_BOUND` 뒤, 첫 joint boundary merge 전에 초기 조건부 개선을 수행한다. 기존 seed가 이미 설정된 gap 목표를 만족하거나 active cluster가 없으면 추가 탐색을 생략한다. 초기 개선 후 `SEED_BOUNDARY` checkpoint를 기록한다. [초기 개선 진입](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L183), [구현](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L286)

각 active cluster의 현재 조건부 비용과 최소 비용 차이가 양수이면 해당 owner의 원래 decision 영역을 검토한다. 기존 두 단계 original 인접 영역과 auxiliary closure를 사용하며, 큰 영역과 직접 owner 영역을 비용 개선 가능성 순으로 비교한다. Singleton source와 auxiliary만 가진 비용 owner도 closure를 통해 mutable consumer를 찾는다.

계산은 `SharedRegionalPreparation`을 재사용한다. 외부 original decision을 incumbent 값으로 고정하고, 포함된 auxiliary의 모든 관련 factor를 유지한 조건부 exact 문제를 푼다. 전체 외부 경계 조합용 persistent message를 새로 만들거나 기존 cluster cover를 변경하지 않는다. **조건부 풀이의 임시 factor와 VE 테이블은 사용한다.** 경계 메시지의 반올림된 값을 새 factor로 변환하는 경로는 추가하지 않았다.

후보는 encoded auxiliary를 다시 완성한 뒤 reduced factor, 원래 encoded factor, canonical 비용의 일치를 검증한다. 전체 canonical 비용이 엄격히 감소할 때만 채택하며, 조건부 문제의 수치 동률 때문에 전체 비용이 같거나 더 비싼 후보는 기존 incumbent를 유지한다. Canonical 불일치와 lower-bound 위반은 계속 오류로 처리한다. Persistent merge의 기존 비용 비증가 검사는 유지한다.

초기 pass는 한 번 수행한다. 각 neighborhood 처리 전 gap 목표를 확인하고, 자원 한도에 맞지 않는 조건부 풀이는 기존 실행 가능한 계획을 유지한다. 초기 후보 목록은 이후 resource fallback에서 재사용하지 않고 현재 incumbent를 기준으로 다시 수집한다. 초기 조건부 작업 시간은 `dpNanos`에 포함하고 전체 후보 검증 시간은 `validationNanos`로 구분한다.

현재 구현은 이미 계산한 메시지 행만 읽는 streaming 전용 탐색과는 다르다. Auxiliary 연결과 비용 정밀도를 보존하는 기존 조건부 exact 풀이를 사용한다. Correlated 영역이 크면 이 단계 자체의 planning 비용이 커질 수 있으며, 전체 planning 시간 단축은 보장하지 않는다.

## 기준 커밋의 seed 생성 방식

`LocalPhysicalOptimizer.regionalSeed()`는 producer-before-consumer 순서를 만들고 `LocalCategoricalOptimizer.optimize()`를 호출한다. 이 호출은 일반 local block과 deferred block을 빈 목록으로 전달하며, `revisitPasses`도 0으로 설정한다. Seed 생성 후에 incremental optimizer가 실행된다. [호출과 설정](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java#L183), [incremental 진입](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java#L94)

| 단계 | 현재 동작 | 최적화 범위 |
| --- | --- | --- |
| 초기 선택 | Producer부터 순차적으로 후보를 비교한다 | 현재까지 값이 모두 정해진 incident factor |
| Hard conflict repair | 위반 영역과 인접 response region을 조건부 exact하게 푼다 | 외부 선택을 고정한 block |
| 일반 비용 개선 | Seed 호출에서 block 목록이 비어 있고 재방문 횟수가 0이다 | 별도 일반 개선 pass 없음 |
| Incremental 최적화 | Factor별 cluster를 만들고 경계 메시지를 점진적으로 병합한다 | Seed 이후 확장되는 영역 |

초기 선택은 random이 아니다. 후보 비교 순서는 **hard constraint 위반 수, 현재 닫힌 factor의 비용, domain value index**다. 여기서 닫힌 factor란 scope의 모든 변수값이 정해진 factor다. 아직 선택되지 않은 변수와 연결된 factor는 해당 시점의 평가에서 빠지므로, 앞선 선택이 후속 비용을 충분히 반영하지 못할 수 있다. [순차 선택](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java#L669), [hard factor 평가](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java#L857), [비용 평가](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java#L1094), [비교 기준](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java#L1162)

Hard conflict가 발생하면 위반 factor의 연결 영역과 즉시 인접한 hard-factor response region을 포함해 푼다. 고정된 외부 선택 때문에 해가 없으면 영역을 확장한다. 이 과정은 처음 발견한 실행 가능 조합을 그대로 채택하는 방식이 아니라, 해당 조건부 문제의 비용을 최적화하는 방식이다. 다만 외부 선택이 고정되어 있으므로 전체 계획의 전역 최적성을 뜻하지는 않는다. [Conflict repair](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java#L873), [block exact solve](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java#L970)

기존 `conflictBlockChoosesTheCheapestLegalAssignmentNotTheFirstCoherentOne` 테스트는 처음 가능한 비용 10의 조합 대신 비용 2의 조합을 고르는 동작을 검증하도록 작성되어 있다. 따라서 현재 conflict 처리까지 전부 greedy라고 해석하면 부정확하다. [기존 회귀 테스트](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizerTest.java#L48)

## Cluster마다 필요한 최적해

Cluster마다 보존할 정보는 경계 조건별 최적 비용과 그때의 내부 계획이다. Cluster C의 내부 선택을 i, 경계 선택을 b라고 하면 다음과 같이 표현할 수 있다.

```text
M_C(b) = min_i cost_C(i, b)
```

`cost_C`에는 cluster가 소유하는 factor들의 비용과 hard constraint가 포함된다. 불가능한 조합은 무한대 비용으로 표현한다. 내부 변수는 다른 cluster와 직접 공유되지 않고, 공유되는 선택은 경계에 남아 있어야 한다.

독립 최적 계획 하나만 남기면 타협에 필요한 후보를 잃을 수 있다. 다음은 이를 설명하는 가상 비용 예다.

| 공유 경계 값 | Cluster A 최소 비용 | Cluster B 최소 비용 | 합계 |
| --- | ---: | ---: | ---: |
| x=0 | **0** | 9 | 9 |
| x=1 | 9 | **0** | 9 |
| x=2 | 2 | 2 | **4** |

A의 독립 최적은 `x=0`, B의 독립 최적은 `x=1`이지만, 전체 비용은 `x=2`에서 가장 작다. 따라서 두 cluster가 처음 선택한 값 외에도 경계 조건별 대안을 평가할 수 있어야 한다. 경계가 여러 변수로 이루어졌다면 변수별 독립 최솟값만으로는 변수 사이의 제약과 비용 상관관계를 보존할 수 없다.

## 기존 경계 메시지 병합과의 차이

현재 incremental optimizer는 reduced root의 factor 하나마다 초기 cluster를 만든다. 다른 cluster가 사용하지 않는 private variable을 먼저 최소화할 수 있으며, `BoundaryMessage`는 경계별 비용과 내부 선택 복원 정보를 보관한다. 초기 cluster는 임의의 큰 연산 그룹과 동일한 개념이 아니다. [초기 cluster 구성](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L150), [private variable 처리](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L563), [메시지 구조](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L188)

현재 merge는 공유 pivot에 연결된 메시지들을 모아 pivot을 최소화하고, 남은 외부 경계의 모든 조합에 대해 비용과 복원 정보를 저장한다. 개념적으로는 다음과 같다.

```text
M_new(b) = min_x sum_C M_C(x, b의 해당 부분)
```

이미 제거한 cluster 내부 변수 전체를 다시 열거하거나, 원래 모든 변수의 거대한 joint table을 그대로 저장하는 구조는 아니다. 다만 남은 외부 경계 b가 커지면 새 메시지의 행 수와 계산량이 커진다. 구현은 output boundary 조합을 순회하면서 각 조합의 최적 내부 선택을 계산하고 저장한다. [Pivot에 연결된 전체 bucket 구성](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L636), [출력 테이블 크기와 계산](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L736)

Seed 하나를 개선하려는 목적이라면, 모든 외부 조건에 대한 `M_new`를 만들 필요는 없다. 현재 유지할 외부 조건을 고정하고 바꿀 경계 조합의 최선값과 선택만 구하는 방법을 사용할 수 있다. 이 부분이 제안하는 변경이다.

## 초기 경계 조합 개선의 원리

실행 가능한 seed와 cluster 메시지를 준비한 뒤, 본격적인 병합 전에 경계 조합 개선을 수행한다. 아래는 조건부 최적화의 개념적 절차이며, 실제 구현은 위 절의 owned-factor neighborhood와 공용 solver를 사용한다.

1. 함께 변경할 공유 경계 변수 집합 Q를 고른다. Q 이외의 경계는 현재 seed 값으로 고정한다.
2. Q의 변수에 연결된 모든 cluster를 수집한다. 관련 hard constraint, 공유 비용 및 auxiliary 관계를 빠뜨리지 않는다.
3. Q의 공동 상태 조합을 평가한다. 각 cluster의 해당 경계 조건부 최소 비용을 합산하여 가장 저렴한 실행 가능 조합을 찾는다.
4. 선택한 경계에 맞춰 각 cluster 내부 최적 계획을 복원한다. 전체 외부 조건용 병합 테이블을 새로 유지할 필요는 없다.
5. 전체 hard constraint와 canonical 비용을 검증하고, 더 저렴한 계획을 incumbent로 채택한다.

현재 seed를 s, Q에 연결된 cluster 집합을 A(Q)라고 하면 비교 대상은 다음과 같다. Q의 영향을 받지 않는 cluster 비용은 모든 비교에서 동일하다.

```text
q* = argmin_q sum_{C in A(Q)} M_C(q와 s의 고정 경계값을 결합한 조건)
```

기존의 실행 가능한 경계값도 탐색에 포함하고 관련 factor를 빠짐없이 반영한다면, 조건부 최적화에는 기존 계획보다 비싸지 않은 해가 존재한다. 실제 채택은 기존 canonical 비용 검증과 수치 계약을 유지해야 한다. 현재 optimizer에도 전체 모델의 비용 일치와 incumbent 비용 비증가를 검사하는 코드가 있다. [후보 검증과 채택](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L706)

개선 대상은 hard conflict뿐 아니라 **실행은 가능하지만 비싼 경계 선택**도 포함해야 한다. Hard conflict만 처리하면 현재 seed 생성과 마찬가지로 비용 개선 기회를 놓칠 수 있다. 또한 여러 경계를 함께 바꿔야 좋아지는 경우에는 한 변수씩만 탐색하는 방식으로 충분하지 않다.

Cluster별 독립 최적해에서 바로 최초 seed를 구성하는 확장도 생각할 수 있다. 그러나 그 조합은 아직 전역적으로 실행 가능하지 않을 수 있으므로, 경계 조정으로 해를 찾지 못하면 영역을 넓히는 절차가 필요하다. 위 절차의 비증가 및 실행 가능성 설명은 이미 실행 가능한 seed를 개선하는 경우에 적용된다.

## 기존 구현에서 활용할 수 있는 부분

`BoundaryMessage`에는 특정 전체 assignment에 대응하는 경계 비용 조회와 내부 계획 복원 기능이 있다. 초기 경계 탐색은 이 정보를 활용할 수 있다. 다만 현재의 변수별 min-marginal 조회만 독립적으로 합치는 것으로 여러 경계 변수의 공동 제약을 대신할 수는 없다. [비용 조회와 복원](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L316)

기준 커밋에는 persistent boundary message를 만들 자원이 부족해진 뒤 conditional exact neighborhood로 incumbent를 개선하는 경로가 있었다. 후속 구현은 이 풀이를 초기 seed 단계와 자원 제약 이후 단계에서 함께 사용한다. 두 단계 모두 새 persistent message를 게시하지 않고 인증된 lower bound를 유지한다. [공용 조건부 개선](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L482)

`SharedRegionalPreparation`은 block 밖 원래 decision을 고정하면서, 포함된 auxiliary를 제약하는 factor를 함께 유지한다. 이런 관계 보존 방식은 경계 개선에서도 필요하다. 기존 conditional solver를 활용하는 것과 이미 만들어진 cluster 메시지만 조회하는 것은 서로 다른 구현 선택이며, 전자는 내부 풀이 과정에서 중간 테이블을 사용할 수 있다. 따라서 persistent merge를 생략한다는 설명을 모든 테이블 계산과 메모리 사용이 없어진다는 뜻으로 해석해서는 안 된다. [조건부 문제 구성](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/SharedRegionalPreparation.java#L175)

## 보장 범위와 검증 과제

이 개선은 더 좋은 seed와 더 낮은 upper bound U를 만드는 데 적합하다. 작은 영역을 반복해서 최적화하는 것만으로 전역 최적성을 보장하지는 않는다. Lower bound L을 높이려면 별도의 유효한 인증이 필요하므로, seed 개선 결과만으로 L이나 exact 완료 상태를 변경하면 안 된다.

Cluster별 factor 소유권도 보존해야 한다. 같은 factor의 비용을 여러 cluster에서 중복 합산하거나, 경계에 걸친 hard factor를 누락하면 전체 계획의 비용과 실행 가능성이 잘못 평가된다. 현재의 cover 검사는 각 원래 factor가 정확히 한 번 소유되는지 확인한다. [Factor 소유권 검사](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L744)

새 persistent message의 저장량을 줄일 수 있어도, 선택한 경계 변수들의 domain 곱이 크면 조합 탐색 자체는 비싸질 수 있다. Seed 품질과 총 planning 시간의 개선 여부는 구현 후 측정해야 한다. 후보 공간, privacy, runtime legality 또는 비용 모델을 임의로 축소하는 변경은 이 제안에 포함되지 않는다.

구현 검증에서는 다음을 구분한다.

- 기존 greedy seed가 이미 합법적인 경우에도 더 싼 경계 조합을 찾는지.
- 각 cluster의 독립 최적값에 없는 타협값과 여러 변수의 동시 변경을 찾는지.
- 경계 hard constraint와 auxiliary 관계, factor별 단일 비용 소유권을 보존하는지.
- 개선 후 canonical 비용이 증가하지 않고, lower bound와 종료 인증을 잘못 변경하지 않는지.
- 동일 Docker 조건의 `run_LAN_docker.sh` 실험에서 seed 비용, 최종 비용, seed 생성 시간 및 총 planning 시간을 어떻게 바꾸는지.

## 구현 검증 결과

17개 클래스의 **176 tests가 실패 0, 오류 0, 제외 0으로 통과**했고 JAR 빌드가 성공했다. 신규 `IncrementalBoundarySeedTest`의 11개 사례와 기존 exact 수치 동률, 무작위 global optimum 대조, pruning, shared source 및 physical planner 연동 검사를 포함한다. [신규 회귀 테스트](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalBoundarySeedTest.java)

| 신규 검증 사례 | 초기 비용 | 초기 개선 후 비용 | 확인한 내용 |
| --- | ---: | ---: | --- |
| 세 상태 경계의 타협 | 9 | 4 | 어느 cluster의 독립 최적값도 아닌 공동 최적값 선택 |
| 세 original의 equality chain | 15 | 0 | 여러 original을 동시에 변경 |
| Auxiliary만으로 연결된 비용 owner | 10 | 3 | Singleton source 뒤의 mutable consumer 추적 |
| 네 original chain의 외부 고정 | 10 | 10 | 초기 영역 밖 선택을 고정하며 이후 exact merge는 비용 0에 도달 |
| 초기 조건부 풀이의 자원 거절 | 9 | 9 | 기존 실행 가능한 seed와 canonical 비용 보존 |

초기 pass 전후에 lower bound, factor cover의 cluster 수, persistent merge 수, retained slots 및 internal decision 수가 유지되는지도 검사한다. Auxiliary 탐색 전의 잘못된 empty-original 검사를 별도 테스트용 빌드에 복원하면, 11건 중 auxiliary-only 회귀만 기대값 3 대신 10으로 실패했다. Production 파일과 최종 빌드는 이 변형을 포함하지 않는다.

`run_LAN_docker.sh --joint-boundary-e2e`로 고정한 최종 classes에서 모델 검증 **10건**, 실제 worker의 **loop와 function 2건**이 통과했다. 두 사례 모두 CP/FED 수치 fingerprint가 일치하고 audit error 및 runtime conversion 위반이 0이었다. 변경한 production/test 파일 4개의 source와 class SHA가 Docker의 frozen inputs와 일치함을 확인했다.

재현 명령, 클래스별 결과, source/class/JAR SHA 및 Docker 결과 경로는 [검증 기록](experiments/boundary-seed-20261006/validation.json)에 저장했다. 구현 과정과 수치 동률 수정은 [세션 기록](SESSION_ISSUES_2026-10-06.md#초기-경계-조합-개선-구현과-검증)에 기록했다.

단위 fixture의 비용 감소는 실제 workload의 실행시간 단축을 뜻하지 않는다. 아래 ML 후속 검증에서도 workload에 따라 초기 조건부 풀이의 시간 부담이 달랐다.

## ML training 후속 검증

**실제 학습 5개 케이스에서 CP/FED 모델 계수 전체가 일치했다. 초기 seed 비용은 감소했지만, 변경 전과 최종 계획 비용은 같았고 planning 시간은 workload에 따라 개선 또는 악화됐다.**

비교 기준은 구현 당시 `origin/main`인 `0146f043e07ca445d9084257759aa78fe14ddf55`다. 검증 시작 시 `origin/main`이 가리킨 `79c26b40cebae479a191f9e406586b5b74193d17`의 후속 변경은 포함하지 않았다. 이전 optimizer와 `RegionalSearchProblem`만 기준 커밋에서 별도 컴파일했고, 나머지 production class/resource·dependency·Java test class는 수정본과 동일한 byte를 사용했다.

192×8의 결정적 합성 데이터를 세 worker에 64행씩 ROW 분할했다. X는 PRIVATE_AGGREGATE, label은 로컬 public이다. CP는 같은 값의 로컬 복사본을 기준으로 실행한다. 고정 Docker 이미지, 4 CPU·8 GiB 컨테이너·3 GiB coordinator heap, 기본 비용 상수, trace/audit 및 `-noFedRuntimeConversion`을 양쪽에 동일하게 적용했다. 비용 모델의 ms와 아래 실측 planner 초는 서로 다른 지표다.

| 실제 학습 workload | 초기 seed 비용 → 경계 개선 후 (modeled ms) | 변경 전 planner (s) | 수정본 planner (s) | CP/FED 계수 최대 절대 오차 |
| --- | ---: | ---: | ---: | ---: |
| 내장 `l2svm` | 71.8142 → 21.3860 | 6.2793 | 2.2162 | 8.42e-17 |
| 내장 `lmCG` | 71.6911 → 21.2628 | 1.0201 | 0.8882 | 1.23e-15 |
| Logistic GD, 20회 | 263.3357 → 41.5633 | 1.4879 | 2.8769 | 5.21e-18 |
| L2 squared-hinge GD, 20회 | 122.3724 → 41.5159 | 3.1636 | 4.6069 | 8.03e-18 |
| Least-squares GD, 20회 | 202.9607 → 41.5058 | 1.7486 | 3.0939 | 1.12e-16 |

Planner 시간은 `planner_begin`부터 `planner_end`까지의 `rewriteProgram` 호출 구간이다. 공통 placement analysis가 포함되는 전체 compilation 시간과 구별한다. **각 workload당 1회 대조 관측**이며, trace/audit가 켜져 있고 공유 호스트의 다른 컨테이너도 존재했다. 통계적인 속도 향상이나 LAN throughput을 입증하는 측정은 아니다.

다섯 케이스 모두 수정 전후 최종 modeled 비용이 표의 경계 개선 후 비용과 같았다. 내장 L2SVM과 lmCG는 초기 경계 개선만으로 gap 목표에 도달했다. 각각 조건부 풀이 5회/개선 2회, 1회/개선 1회였으며 초기 pass의 추가 시간은 약 0.690초와 0.180초다. GD 세 사례는 경계 개선 후에도 lower-bound 인증을 위한 merge를 진행했다. 초기 조건부 풀이가 각각 약 1.47초, 2.38초, 1.51초를 사용해, 같은 최종 계획 비용에 도달하는 데 시간이 더 걸렸다.

모든 초기 경계 pass에서 lower bound·cluster 수·merge 수·assignment 수·persistent retained slots가 유지됐다. 즉, 기록된 seed 개선은 새 persistent boundary merge table을 만드는 단계가 아니다. 임시 조건부 VE 테이블은 여전히 사용한다. 완료한 케이스의 최종 상태는 `TARGET_REACHED`이며 전역 최적 증명은 아니다.

내장 두 학습에서는 각각 FED 행렬곱 17회가 실제 실행됐다. 모든 모델의 8개 계수를 전부 비교했고, runtime audit의 missing/mismatch와 runtime conversion 위반은 0이었다. GD 세 학습의 손실도 CP/FED에서 일치하며 다음처럼 감소했다.

- Logistic cross entropy: `0.69314718 → 0.69222662`
- L2 squared hinge: `0.5 → 0.49852572`
- Regularized least squares: `2.48303701 → 0.05808227`

내장 `multiLogReg`는 별도 제한으로 남는다. CP 학습은 완료했으나 FED에서는 **수정본과 baseline 모두 300초 timeout(exit 124)**이 발생했다. 공통 `reducedRoot`의 hard factor materialization이 오래 걸렸으며, 양쪽에서 `JointValueMapRelations.Grounding.rows` → `ExactCategoricalSolver.freezeInputs` 경로의 실행 중인 스레드를 확인했다. 새 incremental 초기 경계 단계에 진입한 checkpoint는 없었다. 따라서 이 실패는 양쪽 공통 단계에서 재현된 병목으로 남긴다. 전체 ML suite를 통과했다고 표현하지 않으며, 간단한 Logistic GD 통과를 내장 `multiLogReg` 통과로 대체하지 않는다.

실험은 모두 `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e`로 실행했다. 추가한 여섯 `ml_*` 케이스는 `--case`로 명시할 때만 실행되며 기존 기본 목록을 확장하지 않는다. 하네스 테스트 25건, Python compile 및 diff 공백 검사는 통과했다. ML 검증 중 production 코드를 추가 변경하지 않았고, 기존 176-test 검증 당시 source SHA와 일치함을 확인했다.

재현 시 다음 케이스를 선택하고 새 `--run-id`를 지정한다. Baseline에는 `--classes target/boundary-seed-evidence/ml-baseline/classes --main-sources target/boundary-seed-evidence/ml-baseline/sources`를 함께 전달한다. 이 디렉터리의 `provenance.json`에 기준 source와 재컴파일 명령이 기록돼 있다.

```bash
scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e \
  --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/boundary-seed-ml-20261006 \
  --case ml_l2svm --case ml_lm --case ml_logreg_gd \
  --case ml_l2svm_gd --case ml_lm_gd --case ml_logreg \
  --timeout-seconds 3600 --case-timeout-seconds 300
```

Raw evidence의 run 이름은 `ml-candidate-01`, `ml-gd-candidate-01`, `ml-baseline-01`이다. 각 manifest, frozen source/class, 입력/스크립트 hash, 전체 로그와 모델 CSV를 보존했다. [ML 검증 집계](experiments/boundary-seed-20261006/ml-validation.json)에 결과·시간·checkpoint·한계와 원문 경로를 정리했다. 정식 staged ML campaign은 `/home/mchoi/cofee-evaluation` 외부 모듈 부재로 실행하지 못했으며, 이번 소규모 합성 학습 결과를 대규모 데이터 검증으로 일반화하지 않는다.

## 최신 origin/main 통합 후 실제 builtin 재검증

`git fetch origin main`으로 `ada24ffd4be4d17240f32f5bd1c2b5a98e1a0c8a`를 확인하고 작업 branch를 fast-forward했다. 기존 변경을 보존했으며 세션 문서의 독립 append만 합쳤다. 새 production에서 Java 24개 클래스 240건, Python 25건이 통과했다. 최신 main의 optimizer와 boundary-seed 구현을 같은 이미지·입력·나머지 class byte로 비교했다.

| builtin | 최신 main planner (s) | boundary-seed planner (s) | 결과 |
|---|---:|---:|---|
| `l2svm` | 11.111 | 2.737 | CP/FED 전체 8계수 일치 |
| `lmCG` | 1.586 | 1.085 | CP/FED 전체 8계수 일치 |
| `multiLogReg` | 300초 제한 | 300초 제한 | 양쪽 FED timeout, seed 진입 전 |

완료된 두 workload의 최종 modeled 비용은 양쪽 동일하고, boundary-seed 단계에서 초기 비용이 약 70.2%/70.3% 줄었다. 새 persistent merge 없이 gap 목표에 도달했다. 전체 compilation과 구분한 planner interval이며 단일 공유 호스트 실행으로 통계적 속도 개선을 주장하지 않는다.

logreg의 candidate thread dump는 공통 root factor 준비 중 `Grounding.rows`에서 관측됐다. 최신 main이 root 준비를 초기 선택 전으로 이동했으므로 이전 커밋과 seed 전후 시간을 직접 비교하면 안 된다. 두 suite의 전체 상태는 FAILED이며 logreg 성공으로 보고하지 않는다. [최신 main 검증 집계](experiments/boundary-seed-20261006/latest-main-ml-validation.json)에 manifest, 수치, hash 대조 및 원문 경로를 기록했다.

그 뒤 사용자 요청에 따라 [합법성 기반 후보 확장과 partial hard proof](LEGAL_CANDIDATE_EXPANSION_2026-10-06_KO.md)를 추가한 `legal-prefix-candidate-01`에서는 세 builtin이 모두 통과했다. logreg는 planner 44.728초, 전체 compilation 89.965초, 학습 3.869초이며 CP/FED 16개 계수 전체가 일치했다. 이 후속 결과를 앞선 seed-only 대조의 성공으로 소급하지 않는다. Java 329건과 Docker 3건의 [별도 검증 기록](experiments/boundary-seed-20261006/legal-prefix-ml-validation.json)을 따른다.
