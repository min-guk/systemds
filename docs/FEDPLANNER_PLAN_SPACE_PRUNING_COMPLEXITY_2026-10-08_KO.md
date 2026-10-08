# FedPlanner plan space 생성 비용과 pruning의 한계

기준일은 2026-10-08이며, 분석 기준 코드는 main 커밋 `276f958efc`다. 소스 근거의 줄 번호는 이 커밋을 기준으로 한다. 측정 근거는 같은 main을 사용한 LogReg 및 L2SVM 컴파일 기록과 LogReg W1의 JFR 진단이다.

Source·worker pool·partition·relocation·support의 구체적인 의미와 경우의 수는 [입력 공급 조합 설명](FEDPLANNER_CANDIDATE_COMBINATIONS_EXPLAINED_2026-10-08_KO.md)에 정리했다.

**전역적으로 동일한 조건에서 비용만 더 비싼 후보는 제거할 수 있다. 현재 global의 사전 축약에는 이 dominance pruning을 추가할 여지가 있다.** 다만 현재 긴 대기 시간은 비용 비교에 앞서 정확한 입력·배치·의존성 조합을 생성하고, 그 조합의 합법성을 반복 확인하는 공통 분석에서도 발생한다. Global의 비용 pruning만 강화해서 이 앞단 비용까지 해결된다고 볼 수는 없다.

아래에서 구현과 테스트가 직접 보여주는 내용은 코드 사실로, 반복문에서 유도한 복잡도와 개선 방향은 분석으로 구분한다. CPU sample 비율은 관측된 실행 구간의 통계이며 전체 컴파일 시간 비중이나 예상 개선율이 아니다.

## Plan space를 만드는 과정

공통 분석은 각 연산에 `CP/FED`, `LOUT/FOUT` 몇 개를 붙이는 작업보다 훨씬 많은 상태를 다룬다. 주요 흐름은 다음과 같다.

```text
프로그램·함수·제어흐름 분석
  → 입력 FType 조합 열거와 oracle 검사
  → 정확한 source 및 placement 조합 생성
  → native 실행과 재배치의 근거 및 의존성 구성
  → 함수·루프·변수 경계를 통한 후보 전파
  → privacy 및 지원되지 않는 후보 pruning
  → 관련 상태가 안정될 때까지 반복
  → planner의 물리 모델과 비용표 생성
  → 비용 기반 최적화
```

이는 주요 의존 순서를 요약한 흐름이다. 실제 구현에는 초기 privacy 필터링, 중첩 closure, 증분 갱신과 캐시 재사용이 함께 있다. 모든 단계를 매번 처음부터 다시 실행한다는 뜻은 아니다.

같은 `FED/FOUT` 후보라도 어떤 입력 realization을 선택했는지, 어느 worker와 partition layout을 사용하는지, 어떤 relocation이 필요한지, 어떤 지원 관계가 성립하는지에 따라 구분된다. 따라서 실행 형태가 몇 가지뿐이라는 사실로 실제 plan space가 작다고 판단할 수 없다.

입력별 FType 선택은 Cartesian product로 열거한다. 그 뒤 정확한 source realization과 support binding의 조합도 별도로 전개한다. Relocation 경로 역시 입력 binding 조합을 생성한다. 프로그램 전체의 완성 계획을 처음부터 하나씩 나열하는 방식이라고 단순화해서는 안 되지만, **국소적인 조합 전개부터 이미 곱셈 형태로 증가한다.**

코드 근거:

- [NeutralPlacementGraphBuilder.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java), 174행: 프로그램 사실 분석 후 relation closure를 수행한다.
- [PlacementCandidateGenerator.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java), 180행과 729행: 입력 조합을 재귀적으로 열거하고 조합별 oracle 처리를 수행한다.
- [NativePlacementContinuity.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java), 2176행과 2530행: 정확한 입력 support 선택지를 만들고 조합을 전개한다.
- [PlacementRelationClosure.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java), 10421행과 10621행: relocation binding product를 생성한다.

## Pruning을 해도 생성 비용이 남는 이유

**후보를 생성하지 않는 것과 생성한 다음 제거하는 것은 비용이 다르다.** 생성 이후 pruning은 후속 탐색을 줄이지만, 이미 수행한 객체 생성·binding 복사·서명 계산·oracle 검사 비용을 되돌리지는 못한다.

현재 prefix pruning은 동일한 물리 연산을 여러 입력에서 참조하면서 서로 다른 realization을 선택하는 모순을 조합 완성 전에 제거한다. 반면 서로 다른 연산에서 온 독립적인 입력들이 모두 합법적이면 이 조건에 걸리지 않아 선택지가 그대로 곱해진다. 이는 pruning 누락으로 단정할 문제가 아니라, 해당 pruning이 제거할 수 있는 조건의 범위다. [NativePlacementContinuity.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java), 2530행.

일부 support pruning은 함수·변수 경계와 relocation의 연관된 갱신을 끝낸 뒤 실행한다. 아직 support가 도착하지 않은 중간 상태만 보고 후보를 삭제하면, 이후 합법적으로 연결될 후보까지 잃을 수 있기 때문이다. 이 때문에 후보 생성·전파·제거가 고정점 반복에 들어 있다. 고정점은 후보와 의존성 상태가 더 이상 바뀌지 않는 상태를 뜻한다. [PlacementRelationClosure.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java), 1000행, 1072행, 1132행.

Pruning 자체에도 비용이 든다. Native support 제거는 이미 dense state ID, reverse dependency index, dead-state queue를 사용한다. 따라서 단순히 전체 반복 검사를 queue로 바꾸면 해결된다는 진단은 맞지 않는다. 상태의 동등성 검사와 인덱스 구축 비용도 봐야 한다. [NativePlacementContinuity.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java), 2343행.

Proof 정렬에는 seed, output pool, partition 정보와 immediate binding이 포함된 서명을 비교한다. 공유 구조를 재사용하는 최적화가 있지만, 공유되지 않는 긴 문자열 구간은 여전히 비교해야 한다. [NativePlacementContinuity.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java), 1875행과 3640행; [PlacementAnalysis.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java), 314행.

## Global의 사전 축약과 DP table 계산

Global의 비용 기반 처리는 공통 분석 뒤에 물리 모델과 비용표를 생성하고 optimizer를 호출한다. 따라서 optimizer가 수행하는 pruning으로 앞선 공통 분석과 모델 구성 비용을 소급해서 절약할 수는 없다. [FederatedPlanExact.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanExact.java), 59행.

| 단계 | 현재 수행하는 축약 | 구분해야 할 한계 |
|---|---|---|
| 최적화 전 support pruning | unary·binary 관계에서 불가능하거나 지원되지 않는 값을 제거 | 모든 종류의 전역 dominance를 검사하는 것은 아님 |
| 최적화 전 동치 축약 | 연결된 factor들의 관측 비용 패턴과 tie 조건이 같은 값을 병합 | 항상 더 비싼 값의 제거까지 수행하지 않음 |
| DP 변수 제거 | 같은 경계 상태에 대해 최소 비용과 해당 선택을 저장 | 앞에서 후보와 비용표를 준비한 비용은 이미 발생함 |

여기서 factor는 하나 이상의 선택 변수에 비용이나 합법성 제약을 부여하는 관계다. 동치 축약은 연결된 factor에서 다른 변수의 활성 선택을 바꿔가며 관측값이 같은지 검사한다. 비용 비교에는 raw-bit equality를 사용한다. 즉, 이 단계의 조건은 비용의 `동일함`이며 `작거나 같음`에 기반한 일반적인 dominance가 아니다. [ExactPhysicalReducedSolver.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java), 533행, 1186행, 1247행.

반면 DP table 계산은 남아 있는 경계 변수들의 선택이 같을 때 제거할 변수의 여러 값 중 최솟값을 남긴다. 따라서 global이 최소 비용 선택을 전혀 하지 않는다는 설명도 정확하지 않다. [ExactCategoricalSolver.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java), 2265행.

## 동일한 조건에서 더 비싼 후보를 버릴 수 있는가

가능하다. 현재 사전 축약에서 이를 하지 않는 가장 직접적인 근거는 다음 테스트다.

```text
다른 연결 조건이 없는 unary 비용: [10, 7, 7, ∞]
현재 사전 축약 결과:             [10, 7]
```

무한 비용은 제거하고 중복된 7은 합치지만, 더 비싼 10은 남긴다. 이 예에서는 10을 제거해도 최적해를 보존할 수 있다. 따라서 안전한 dominance 여지가 전혀 없어서 못 한다는 설명은 맞지 않으며, 현재 구현의 축약 범위가 제한적인 것이다. [CostBasedPruningTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CostBasedPruningTest.java), 19행.

다만 `exec/output`이 같다는 것만으로 조건이 같다고 보면 안 된다. 같은 테스트 파일의 36행에는 다음 반례가 있다.

| 후보 | 자체 비용 | 호환되는 공유 입력 비용 | 전체 비용 |
|---|---:|---:|---:|
| A | 4 | 100 | 104 |
| B | 6 | 1 | 7 |

자체 비용만 비교하면 A가 싸지만, 호환되는 공유 입력까지 포함하면 B가 최적이다. B를 먼저 제거하면 최적해를 잃는다.

안전한 dominance의 충분조건은 두 후보의 모든 외부 선택에 대한 호환성과 비local 비용 영향이 같고, 이미 확정되는 local 비용은 한쪽이 더 작거나 같은 것이다. 더 일반적으로는 제거할 후보로 완성 가능한 모든 계획을 다른 후보로 대체해도 합법성이 유지되고 전체 목적값이 나빠지지 않음을 보여야 한다. 공유 전송·재사용·변수 경계 조건도 여기에 포함된다. 동일한 계획 선택까지 보존해야 한다면 비용 동률 처리와 수치 비교 규약도 유지해야 한다.

이는 코드에 추가할 수 있는 최적화 방향에 대한 분석이다. 현재 equality 기반 축약이 이러한 inequality 증명을 수행한다는 뜻은 아니다.

## 코드 루프에서 유도한 시간 복잡도

복잡도는 Hop 개수 하나만으로 설명하기 어렵다. 입력별 정확한 후보 수, 조합의 폭, closure 갱신 횟수와 global의 중간 경계 크기가 함께 영향을 준다.

| 작업 | 변수 정의 | 복잡도 또는 작업량 |
|---|---|---|
| 입력 조합 전개 | 입력 수 k, 입력 i의 후보 수 r_i | 모든 조합이 호환되면 tuple 복사를 포함해 O(k × ∏ r_i) |
| Proof 정렬 | proof 수 M, 비교 서명 길이 상한 L | 최악 O(L × M log M) |
| Dead-state 전파 | 상태 수 S, alternative 수 A, 의존 edge 수 E | 인덱스가 준비된 뒤 queue 처리는 O(S + A + E) 규모. 구조 비교·인덱스 구축 비용은 별도 |
| Dense factor 표 구성 | factor f의 변수 집합 S_f, 변수 v의 domain 크기 d_v | 표 cell 수의 합은 Σ_f ∏_{v∈S_f} d_v. cell별 평가 비용은 별도 |
| Global DP 변수 제거 | 제거 변수 v, 남는 경계 변수 집합 B_v | d_v × ∏_{u∈B_v} d_u개의 조합에 대한 연결 factor 평가 |

예를 들어 입력 4개에 정확한 realization이 각각 30개이고 모두 호환된다고 가정하면, 한 product에서만 `30^4 = 810,000`개의 조합이 나온다. 이는 실제 측정한 후보 수가 아니라 곱셈 증가를 설명하기 위한 예시다. 대부분 합법적이면 legality pruning으로 줄지 않는다.

공통 분석의 시간을 개략적으로 표현하면 다음과 같다.

```text
T_build ≈ T_program_and_joint_analysis
        + Σ_(실제로 처리한 closure 갱신)
          [입력 tuple 열거 및 oracle 비용
           + 정확한 support·relocation product 전개 비용
           + proof 비교·인덱스·pruning·projection 비용]
```

증분 처리와 캐시가 실제 갱신 횟수를 줄일 수 있지만, 살아남는 조합의 수 자체가 크면 곱셈 비용은 남는다. 입력 수 k가 고정되어 있다면 r에 대한 다항식일 수 있으므로 무조건 모든 차원에서 지수 시간이라고 부르는 것도 부정확하다. 입력별 후보 수가 d로 같을 때 `d^k`이며, k가 커지는 방향으로 지수적으로 증가한다.

Global DP에서는 domain 크기 상한을 d, 변수 제거 과정의 최대 경계 변수 수를 w라고 할 때 전형적인 시간 규모가 `O(n × d^(w+1))`이다. 이는 bucket의 factor 개수와 평가 비용을 별도로 둔 표현이며, 한 경계 message의 cell 수는 `O(d^w)`다. 전체 보유 표와 backpointer의 메모리는 이보다 클 수 있다. w는 원래 연산의 입력 수와 다르며, 공유 의존성으로 인해 변수 제거 도중 커질 수 있다.

Sparse·functional 표현과 support pruning은 실제로 방문하거나 저장하는 조합 수를 줄인다. 다만 모든 경우에 최악의 Cartesian 증가를 없애는 보장은 아니다.

## 실제 진단에서 확인된 병목

같은 main의 LogReg W1 진단은 main-thread CPU sample 3,941개를 기록했다.

| 관측 항목 | Sample 수 | 해석 |
|---|---:|---|
| Native proof comparator inclusive | 768 / 3,941, 약 19.5% | proof 서명 비교가 큰 CPU 소비 경로 |
| NativePoolWitness.equals top | 422 / 3,941, 약 10.7% | worker와 layout 관련 동등성 검사 비용 |
| IdentityHashMap.clear top | 290 / 3,941, 약 7.4% | map 초기화 비용. 보고서상 pruning 내부 귀속은 289 samples |

Inclusive sample은 하위 호출을 포함하고 top sample은 최상단 실행 위치를 집계하므로, 서로 다른 지표를 단순 합산하지 않는다. 이 값은 종료 전 샘플링 구간의 CPU 관측이며 완료된 컴파일의 단계별 wall time이나 예상 절감률이 아니다.

오늘 main의 LogReg 및 L2SVM LAN W1/W3 control은 공통 분석 중 60초 성능 watchdog에서 종료됐다. 성공한 전체 컴파일 시간이 60초라는 뜻은 아니며, global DP의 큰 중간 table이 이 timeout을 일으켰다고 확정할 수 없다. 일부 수정 후보도 같은 제한에서 종료됐지만 이 문서의 위 수치는 원본 main의 진단이다.

측정 근거:

- [2026-10-08 main 진행 보고서](/home/mchoi/w1357-paper-aligned-refactor/docs/FEDPLANNER_ORIGIN_MAIN_PROGRESS_REPORT_2026-10-08_KO.md), 93행과 116행: control 결과와 JFR 해석.
- [원본 main JFR hotspot 집계](/grid/3/cofee-lm-sweep-mchoi-20260914/main-20s-20261008T1025Z/campaign-baseline-diagnostic/attempts/compile/01791456640674944604-295655a1/hotspots.json).
- [원본 main LogReg W1 실행 결과](/grid/3/cofee-lm-sweep-mchoi-20260914/main-20s-20261008T1025Z/campaign-baseline/attempts/compile/01791455268874577533-282adbbe/result.json): return code 124, watchdog 60초, 성공 receipt 없음.

## 개선 판단과 남은 확인 사항

| 우선순위 | 판단 | 확신과 범위 |
|---|---|---|
| 1 | 공통 분석의 proof 비교와 상태 인덱스 구축 비용을 줄일 필요가 있다 | 높음. 현재 main의 CPU 진단에서 직접 관측 |
| 2 | 동일한 support product를 반복해서 전개·비교하는 작업을 줄이는 방향이 중요하다 | 조합 전개 구조는 확실. 현재 실행 전체에서의 정확한 시간 비중은 미확정 |
| 3 | Global 사전 축약에 안전한 dominance를 추가할 여지가 있다 | 높음. unary 테스트가 직접 보여줌. 현재 timeout의 주원인으로는 입증되지 않음 |

공통 분석에서는 기존 합법 후보와 의존성 의미를 보존하면서 반복 전개와 비교를 줄이는 방향이 필요하다. 비용 모델 이후에는 동일한 외부 조건을 가진 후보를 정확하게 묶고 비용상 열등한 후보를 제거할 수 있다. 두 개선은 적용되는 단계가 다르다.

후속 검증에서는 공통 분석, 물리 모델 및 비용표 생성, optimizer 시간을 분리하고 입력 tuple 수, support·relocation product의 prefix와 leaf 수, closure 갱신 수를 함께 확인해야 한다. 이 지표를 통해 후보 수 자체의 증가와 같은 후보에 대한 반복 처리 비용을 구분할 수 있다.

이 분석으로 추가 dominance의 정확한 절감량, 전체 compile 단축률, 다른 workload의 병목 비중까지 확정할 수는 없다. 최적화를 구현한다면 합법 후보의 의미, 공유 비용 계산, 최적 목적값과 요구되는 동률 선택 규약을 보존하는 검증이 필요하다.
