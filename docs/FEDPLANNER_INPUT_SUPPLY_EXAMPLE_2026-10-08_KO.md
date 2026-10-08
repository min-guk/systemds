# 전체 worker를 사용하는 FedPlanner의 입력 공급 조합

작성일은 2026-10-08이다. `Z = U + V`를 통해 source, worker pool, partition, relocation, support가 구체적으로 어떤 선택을 나타내는지 설명한다.

**우리의 가정은 FED 연산이 정해진 전체 worker 집합을 사용한다는 것이다.** Worker 부분집합을 선택하는 경우의 수는 여기에 포함하지 않는다. 이 문서의 예시는 전체 worker와 하나의 ROW layout을 고정하고, U와 V를 FED 결과에서 직접 공급하거나 CP 결과를 업로드하는 네 조합을 설명한다.

앞선 버전의 `A={w1,w2}`, `B={w3,w4}`와 여덟 조합 예시는 서로 다른 worker pool을 선택하는 더 일반적인 상황이었다. 우리의 전체-worker 가정에 맞는 설명으로 정정했다. 전체 worker를 사용한다는 가정만으로 모든 FType과 partition이 동일해지는 것은 아니므로, 이 예시에서는 ROW layout도 추가로 고정한다.

아래는 모든 실행·이동 경로가 runtime과 privacy 조건을 만족한다고 가정한 설명용 예시다. 실제 workload에서 측정한 후보 수가 아니며, Z를 FED로 계산하고 결과를 worker에 남기는 가지에 한정한다.

## U와 V를 만드는 두 가지 source

프로그램의 계산이 다음과 같다고 하자.

```text
U = f(X)
V = g(Y)
Z = U + V
```

U와 V를 만드는 연산에는 각각 다음 실행 후보가 있다고 가정한다.

| Source 후보 | 생성 방법 | 결과가 놓이는 곳 |
|---|---|---|
| U₁ | f(X)를 FED로 계산 | 전체 worker의 배치 P |
| U₂ | f(X)를 CP로 계산 | Coordinator |
| V₁ | g(Y)를 FED로 계산 | 전체 worker의 배치 P |
| V₂ | g(Y)를 CP로 계산 | Coordinator |

U₁과 U₂는 같은 값 U를 만드는 서로 다른 실행 후보다. V₁과 V₂도 같은 값 V의 실행 후보다. 둘이 이미 모두 실행되어 있다는 뜻이 아니라, 계획을 고를 때 비교할 수 있는 대안이라는 뜻이다.

Source 선택은 입력 U를 다른 변수 V로 바꾸는 것이 아니다. 프로그램이 요구하는 동일한 입력값을 어떤 물리적 실행 결과에서 공급할지 선택하는 것이다.

## 전체 worker와 하나의 layout을 고정한 배치

U와 V는 같은 크기의 1,000행 행렬이며, 전체 worker 집합은 `{w1,w2,w3,w4}`로 고정한다. 이 예시의 입력과 FED 출력은 아래의 동일한 ROW layout을 사용한다.

| 배치 | Worker pool | Partition |
|---|---|---|
| P | `{w1, w2, w3, w4}` | w1: 0~249행, w2: 250~499행, w3: 500~749행, w4: 750~999행 |

Worker pool은 어느 worker를 사용하는지를, partition은 각 worker가 어느 데이터 구간을 갖는지를 나타낸다. 위 행 구간은 양 끝을 포함하는 설명용 표기다.

이 예에서 worker 집합 선택은 1가지, 목표 layout도 1가지다. `{w1,w2}`만 사용하거나 `{w3,w4}`만 사용하는 후보를 추가하지 않는다. 임의의 분할점을 바꾸는 후보도 추가하지 않는다.

## Z를 전체 worker에서 계산하는 네 조합

Z를 P에서 FED로 계산하려면 U와 V를 모두 P에서 사용할 수 있도록 공급해야 한다.

- U₁은 이미 P에 있으므로 직접 사용할 수 있다.
- U₂를 선택하면 coordinator의 결과를 P로 업로드해야 한다.
- V₁도 P에 있으므로 직접 사용할 수 있다.
- V₂를 선택하면 coordinator의 결과를 P로 업로드해야 한다.

| 후보 | U를 공급하는 방법 | V를 공급하는 방법 |
|---|---|---|
| P-1 | U₁을 P에서 직접 사용 | V₁을 P에서 직접 사용 |
| P-2 | U₁을 P에서 직접 사용 | V₂를 coordinator에서 P로 업로드 |
| P-3 | U₂를 coordinator에서 P로 업로드 | V₁을 P에서 직접 사용 |
| P-4 | U₂를 coordinator에서 P로 업로드 | V₂를 coordinator에서 P로 업로드 |

경우의 수는 `worker 집합 1 × 목표 layout 1 × U 공급 방법 2 × V 공급 방법 2 = 4개`다. 다른 worker pool로의 이동은 이 예시에 없다. Coordinator에서 전체 worker로 업로드하는 경로는 전체 worker 가정과 양립한다.

| 세는 대상 | 개수 |
|---|---:|
| FED 실행에 사용하는 worker 집합 | 전체 집합 하나 |
| 이 예시에서 고정한 목표 layout | P 하나 |
| U의 공급 방법 | 2개 |
| V의 공급 방법 | 2개 |
| Z의 입력 공급 조합 | 4개 |

목표 배치 수와 입력 공급 조합 수는 다르다. 이 네 조합은 설정한 실행 가지의 공급 조합 수이며, 실제 workload나 전체 프로그램의 후보 수를 뜻하지 않는다.

## Support는 각 조합이 성립하는 조건

Support는 위 표의 각 행을 실행할 수 있게 하는 조건 묶음이다. 예를 들어 P-2의 support는 다음과 같다.

```text
U를 만드는 연산은 U₁ 후보를 선택한다
AND V를 만드는 연산은 V₂ 후보를 선택한다
AND V₂를 전체 worker의 P layout으로 업로드하는 action을 사용한다
```

이 조건들이 함께 성립해야 P-2 방식으로 Z를 계산할 수 있다. P-1은 다른 조건 묶음을 갖는다.

```text
U를 만드는 연산은 U₁ 후보를 선택한다
AND V를 만드는 연산은 V₁ 후보를 선택한다
AND U₁과 V₁을 P에서 직접 사용할 수 있다
```

출력 배치는 둘 다 P지만, 필요한 upstream 선택과 업로드가 다르다. Support는 이 차이를 기록한다. **Support를 별도의 선택지로 또 골라서 앞의 4에 곱하지 않는다. 이미 만든 공급 조합을 조건으로 표현하는 것이다.**

동일한 출력 realization에 여러 대안 support clause가 붙을 수 있다. 내부 rule이나 realization identity가 다른 후보까지 항상 하나로 합쳐지는 것은 아니므로, 목표 layout 하나를 내부 객체 하나와 동일시해서는 안 된다.

## 같은 출력 배치라도 비용이 다른 이유

P-1부터 P-4까지는 모두 Z를 P에 남기지만 전체 비용은 다를 수 있다.

- U₁을 FED로 만드는 비용과 U₂를 CP로 만드는 비용이 다르다.
- V₁을 직접 사용하는 경우와 V₂를 coordinator에서 업로드하는 경우의 이동 비용이 다르다.
- 다른 연산도 같은 결과나 업로드된 데이터를 사용한다면 공유·재사용 비용이 달라진다.

따라서 출력이 P라는 정보만으로 네 후보를 동일한 조건이라고 볼 수 없다. 외부의 다른 연산과 공유하는 선택 및 비용 영향까지 같다는 것이 확인되면, 그 조건에서 더 비싼 후보를 제거하는 판단을 할 수 있다.

## 전체 worker 사용과 실제 후보 생성의 관계

확인한 생성 경로는 federated 입력의 주소와 구간에서 anchor를 만들고, 근거가 있는 anchor를 전파한다. Relocation 목표를 모을 때도 입력에서 얻은 anchor를 사용하고 같은 physical worker pool은 합친다. 이 경로가 전체 worker의 임의 부분집합을 열거하는 것은 아니다.

- [PlacementProgramFacts.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java), 487행과 519행: 입력에 지정된 주소와 구간으로 anchor를 만든다.
- [PlacementRelationClosure.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java), 11767행과 11798행: 입력에서 목표 anchor를 수집하고 물리적으로 같은 pool을 합친다.
- 같은 파일 5856행: native 출력 anchor를 만들 때 seed의 각 partition과 worker를 따라 출력 구간을 구성한다.

자료구조와 테스트는 서로 다른 worker pool을 가진 입력도 표현할 수 있도록 일반적으로 작성되어 있다. 이것만으로 우리의 workload에서 worker 부분집합 후보가 실제로 추가 생성됐다고 판단할 수는 없다. 또한 확인한 anchor 구성 경로는 입력 메타데이터를 따르므로, 이 조사만으로 전역의 고정 worker 집합을 모든 경로에서 강제 검증한다고 주장하지 않는다.

전체 worker를 고정해도 ROW/COL/BROADCAST와 연산에 따른 layout 변화는 남을 수 있다. 이 차이는 worker 부분집합 선택과 별개다. 반대로 FType·shape·입력 배치로 layout까지 하나로 결정된다면 별도 layout 선택 배수도 1이다. 남는 비용은 정확한 source 선택, 필요한 전송과 support 관계를 처리하는 비용이다.

## 실제 구현과의 연결

실제 코드는 producer의 같은 `valueVersion`에 속하는 source 후보를 가져오고, 입력별로 직접 사용 가능한 binding과 relocation action을 적용하는 binding을 모은다. 목표 배치와 입력 FType 등의 조건을 검사하고 중복을 제거한 목록이 조합 생성의 축이 된다.

- [PlacementRelationClosure.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java), 10180행부터 10255행: 정확한 source와 직접 공급·relocation binding 목록 구성.
- [PlacementIdentity.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java), 403행과 429행: worker와 데이터 구간을 포함하는 anchor 정의; 641행: 입력 binding 정의.
- [PlacementAnalysis.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java), 1462행과 1510행: support clause와 이를 보유하는 출력 realization.

이 코드 참조는 main `276f958efc` 기준이다. 개별 예시 경로가 특정 실제 workload에서 모두 생성된다는 측정 결과를 의미하지 않는다.

더 복잡한 그래프에서는 위의 입력별 공급 방법 2개가 더 많은 후보로 늘어날 수 있다. 입력이 세 개라면 해당 조건에서 살아남은 세 공급 목록을 곱한다. 이후 공유 producer 선택의 일관성, oracle, privacy 등의 조건이 유효한 조합을 제한한다.

관련 설명은 [입력 공급 조합의 의미와 실제 경우의 수](FEDPLANNER_CANDIDATE_COMBINATIONS_EXPLAINED_2026-10-08_KO.md), 성능 분석은 [Plan space 생성 비용과 pruning의 한계](FEDPLANNER_PLAN_SPACE_PRUNING_COMPLEXITY_2026-10-08_KO.md)에 정리되어 있다.
