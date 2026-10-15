# COFEE Tree DP의 독립성을 제한하는 의존성과 공유 Materialization

작성일: 2026-10-15. 기준 workspace: `/home/mchoi/cofee-tree-dp-20261014`.

**여러 consumer가 공유하는 materialization·전송 비용은 단순 HOP Tree DP의 독립성을 제한하는 대표적인 관계다.** 여기에 공유 producer의 선택 일관성, 함수 호출, branch 합류, loop의 상태 전달 관계가 더해진다. 합법성 제약뿐 아니라 공동 비용도 포함해야 올바르게 영역을 나눌 수 있다.

## Tree DP로 독립적으로 풀 수 있는 조건

기본 판단 기준은 **부모의 상태를 고정했을 때 자식 subtree들을 서로 독립적으로 최적화할 수 있는가**이다. 다른 subtree의 선택까지 알아야 비용이나 합법성을 결정할 수 있다면, 부모 상태만 사용하는 단순 Tree DP로는 부족하다.

이때 부모 상태는 연산 종류만을 뜻하지 않는다. 선택한 rule의 실행 방식, 출력 partition, 입력 요구사항 등 외부와의 관계를 표현하는 정보다. 필요한 정보를 더 큰 boundary에 담으면 DP를 적용할 수 있지만, 기억해야 할 상태 조합이 늘어나 table과 복원 정보가 커질 수 있다.

따라서 HOP 입력 graph가 나무처럼 보이는 것과 전체 최적화 문제가 작은 상태의 tree로 분해되는 것은 별도로 판단해야 한다.

## 현재 Memo에서 중요한 연결 관계

| 관계 | subtree 선택이 연결되는 이유 | 주된 성격 |
|---|---|---|
| 공유 producer | A와 B가 같은 P를 사용하면, 각 subtree가 P에 서로 다른 실행 방식·출력 partition을 선택할 수 없다. 하나의 원본 선택을 공유해야 한다. | 공유 선택의 일관성 |
| 공유 materialization·movement | 같은 값을 같은 형태로 전송해 재사용하면 비용을 한 번만 낼 수 있다. A의 추가 비용이 B의 선택에 따라 달라진다. | 공동 비용 |
| Branch 합류 | 합류 이후 consumer가 경로별로 서로 다른 producer에서 값을 받는다. 실행되는 경로의 공급자와 요구 layout이 맞아야 한다. | 공급·합법성 및 문맥별 비용 |
| 함수 호출 | actual argument, 함수 내부, return의 공급 관계가 호출 경계를 넘어 연결된다. 같은 compiled owner의 선택은 관련 호출 문맥에서 일관돼야 한다. | 공유 선택 및 공급 계약 |
| Loop-carried state | iteration 끝의 출력 선택이 다음 iteration의 입력 상태와 연결된다. 현재 Memo에서는 backedge의 layout이 loop head와 같아야 한다. | 반복 경계 계약 |

함수와 branch의 실제 공급자는 [MemoBindingResolver.collectDirectOwners](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoBindingResolver.java:928)가 연결한다. Formal argument는 호출 frame의 actual binding을 따라 해석하며, [inputCompatible](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java:690)는 reachable frame과 activation에서 선택의 합법성을 확인한다. Branch의 양쪽 경로를 무조건 동시에 실행되는 것으로 취급하지 않는다.

Loop의 [registerBoundaryChecks](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java:817)는 head를 entry와 backedge 공급자에 연결한다. Entry에는 합법적인 변환을 허용할 수 있지만 backedge는 layout 일치를 요구한다. Loop body 내부 HOP만 따로 보면 이 관계를 놓칠 수 있다.

함수·branch·loop라는 구문이나 multi-parent라는 사실만으로 항상 순환이 생기는 것은 아니다. 실제 선택 변수에 연결된 모든 비용·합법성 관계와, 사용하려는 boundary를 함께 봐야 한다.

## 공유 Materialization이 비용을 연결하는 예

같은 재사용 범위에서 local 값 P를 ROW 형태로 업로드하는 비용이 10이고, consumer A와 B가 그 업로드 결과를 공유한다고 가정한다. 아래 숫자는 설명용이며 실제 workload 측정값이 아니다.

| A가 ROW 필요 | B가 ROW 필요 | 실제 공유 업로드 비용 |
|---|---|---:|
| 아니오 | 아니오 | 0 |
| 예 | 아니오 | 10 |
| 아니오 | 예 | 10 |
| 예 | 예 | **10** |

A와 B의 subtree에 각각 업로드 비용 10을 붙이면 마지막 경우를 20으로 잘못 계산한다. 반대로 두 subtree 모두 다른 쪽이 비용을 부담한다고 처리하면 비용이 누락된다.

이 경우 공동 비용은 다음과 같다.

```text
movementCost(A, B) = 10 × indicator(A가 ROW 필요 또는 B가 ROW 필요)
```

A가 업로드를 이미 필요로 하면 B가 같은 결과를 사용하는 추가 업로드 비용은 0이다. A가 업로드를 필요로 하지 않으면 B의 선택으로 비용 10이 새로 발생한다. **P의 출력 상태를 고정해도 A와 B의 수요가 비용으로 연결되어 있는 것이다.**

이는 불법 조합을 금지하는 hard constraint와 구별된다. 네 조합이 모두 합법이어도 비용을 독립적으로 더할 수 없으므로 최적 선택은 함께 고려해야 한다.

## 현재 코드의 Materialization 비용 처리

[registerMovementCosts](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java:1044)는 재사용 가능한 동일 origin의 demand들을 movement group으로 묶는다. [movementScope](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java:1087)는 해당 비용에 영향을 주는 producer와 consumer의 선택 변수를 포함한다. 이 group의 비용을 하나의 `CostTerm`으로 등록한다.

[movementGroupCost](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java:1342)는 target과 단위 비용별 activation을 모으고, 재사용 가능한 경우 activation의 합집합에 해당하는 비용을 계산한다. Branch guard, 실행 문맥, definition phase 등을 반영하므로 같은 값이라는 이유만으로 프로그램 전체에서 무조건 한 번만 과금하는 것은 아니다. Retention이 없는 demand는 개별 비용으로 계산한다.

선택한 retained movement는 [supplySchedule](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java:1721)에서 실행 action으로 재구성한다. 현재 설명의 핵심은 선택한 rule과 layout으로 결정되는 공유 movement activation이다. 임의의 materialization 위치·수명·캐시 용량을 모두 별도 선택 변수로 탐색한다는 뜻은 아니다.

## 제약이 있어도 Tree DP로 처리할 수 있는 경우

**제약의 존재 자체보다 제약이 어떤 변수들을 연결하는지가 중요하다.**

| 관계 | Tree DP에 미치는 영향 |
|---|---|
| 한 HOP에만 걸리는 unary constraint | 해당 HOP의 불법 후보를 제거할 수 있다. 새로운 변수 간 연결을 만들지 않는다. |
| 기존 tree edge의 producer–consumer layout 호환성 | 부모 상태를 고정하고 자식의 합법적인 상태를 비교하는 DP로 처리할 수 있다. |
| 공유되지 않는 edge별 movement 비용 | 해당 edge에 비용을 포함하면 된다. |
| 여러 subtree가 함께 사용하는 선택·비용 | 현재 boundary만으로 독립성이 확보되는지 추가로 확인해야 한다. |

현재 Memo의 기본 [privacyCompatible check](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java:377)는 semantic value에 대한 privacy가 producer placement 선택에 의존하지 않는다는 이유로 `scope={owner}`인 unary check로 등록한다. 따라서 privacy를 일반적인 tree 파괴 원인으로 나열하는 것은 부정확하다. 전송 가능성에 대한 privacy 판단은 관련 movement legality에서도 적용되지만, 그 관계 역시 실제 scope를 기준으로 판단해야 한다.

일반적인 입력 합법성도 [registerInputChecks](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java:590)에서 producer–consumer 관계로 표현한다. 이런 binary 관계 자체는 tree DP에 문제가 없다. 다른 관계와 재결합하거나, 여러 실제 공급자·loop head를 함께 보아야 하는 경우 boundary가 커질 수 있다.

## Cluster를 구성하는 기준

빠르게 풀 영역은 **boundary를 고정하면 내부를 독립적으로 정확하게 풀 수 있는 영역**이다. 내부에 constraint가 많아도 boundary가 작으면 좋은 cluster가 될 수 있다.

Cluster 내부 변수를 제거할 때에는 그 변수에 연결된 모든 합법성·비용 factor를 포함해야 한다. Shared movement factor가 cluster 경계를 넘으면 관련 선택을 boundary에 남기거나, 그 공동 비용을 정확하게 처리할 수 있도록 cluster를 조정해야 한다. 단순히 HOP 부모의 출력 partition만 남기는 것으로 충분하다고 가정할 수 없다.

Materialization 수요·activation 정보를 상태로 추가하는 방식도 생각할 수 있다. 다만 그런 요약 상태가 외부에서 구별해야 할 비용·합법성·재사용 문맥을 모두 보존하는지 증명해야 한다. 상태 하나를 추가하면 모든 공유 문제가 사라진다고 볼 수는 없다.

현재 구현의 공유 exact kernel, 조건부 탐색 및 재사용 범위는 [Memo Global Local 구현 보고서](COFEE_MEMO_DUAL_MODE_IMPLEMENTATION_REPORT_2026-10-15_KO.md)에 정리돼 있다. Tree/table과 boundary-conditioned DP의 기본 관계는 [Tree DP와 cluster 개념 정리](COFEE_TREE_DP_CLUSTER_GLOBAL_LOCAL_CONCEPTS_2026-10-15_KO.md)를 참고한다.

## 실제 ML에서 확인된 범위

현재 코드에서 공유 materialization, 호출·합류 공급 관계, loop 계약이 HOP 입력 edge만으로는 충분히 표현되지 않는 의존성이라는 점은 확인할 수 있다. 전체 factor 구조와 raw HOP 구조의 차이는 [ML 구조 계측 보고서](COFEE_ML_STRUCTURE_AND_SEED_AUDIT_2026-10-15_KO.md)에 기록돼 있다.

다만 각 관계가 세 ML workload의 tree 분해 가능성을 얼마나 줄였는지는 원인별로 분리 계측하지 않았다. 따라서 **공유 materialization은 중요한 연결 원인이지만, 가장 큰 원인인지는 아직 판단할 수 없다.** 원인별 기여도를 비교하려면 동일한 변수·domain과 일관된 graph 정의를 유지한 구조 진단이 필요하다. 그런 진단에서 관계를 제외하더라도 실제 solver의 원본 합법성·공동 비용을 제거하는 근거로 사용해서는 안 된다.
