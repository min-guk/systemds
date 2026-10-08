# 합법 조합의 인덱스로 plan space를 표현하기

**구현 상태 갱신:** 합법 조합 ID 저장과 downstream 조회, 생성 단계의 MRV/부분 oracle pruning을 구현하고 검증했다. 현재 적용 범위와 수치는 [구현 결과](FEDPLANNER_LEGAL_IDS_AND_MRV_RESULT_2026-10-08_KO.md)를 따른다. 아래의 미구현 표기는 제안 당시의 상태이며, 제약식 기반의 더 일반적인 factorization과 합법 ID 저장은 별도 범위다.

## 사용자 제안의 정확한 의미: 합법 판정을 받은 전체 조합 ID만 저장

**합법인 전체 조합의 인덱스만 기억하면 된다.** 입력별 인덱스를 따로 추출해서 다시 조합하자는 뜻이 아니다. 아래에서 설명하는 일반적인 제약식 표현보다 먼저 이 제안을 명확히 한다.

```text
전체 조합 ID: 0, 1, 2, ..., 9999
이미 합법 판정을 받은 조합 ID: {3, 17, 25}

저장: legalCombinationIds = {3, 17, 25}
Downstream: 이 세 조합의 비용을 비교하고 하나를 선택
실행 계획: 선택한 ID에 해당하는 정확한 입력·전송 경로를 복원
```

불법 조합의 boolean도, 각 합법 조합의 무거운 clause 객체도 모두 저장할 필요는 없다. 고정된 입력별 선택지 사전과 조합 번호 규칙이 있으면 ID에서 원래 선택을 복원할 수 있다. 예를 들어 B 선택지가 100개이면 `a = id / 100`의 정수 몫, `b = id % 100`으로 복원한다. 합법 ID 목록의 저장 개수는 전체 조합 수가 아니라 합법 조합 수다.

**이미 판정한 것과 동일한 입력·연산·배치·privacy 문맥에서는 downstream이 같은 합법성을 oracle로 다시 검사할 필요가 없다.** 최초 판정 결과를 재사용하고 비용 계산과 선택을 진행하면 된다. 사전과 ID 대응은 해당 분석 안에서 안정적으로 유지하고, 실행 복원에 필요한 proof는 사전 또는 별도 참조로 보존한다.

이 목록이 증명하는 범위는 최초 검사한 범위다. 목록에 포함되지 않은 다른 연산의 조건이나 전역 공동 조건까지 검증이 끝났다고 간주하지 않는다. 분석 중 source나 정책 문맥이 바뀌면 영향을 받은 결과를 갱신한다. 이는 동일 조건의 합법 조합을 매번 재검사해야 한다는 뜻이 아니다.

이 방식으로 반복 판정과 clause 저장 비용을 줄일 수 있다. 합법 ID를 처음 찾아내는 비용과 합법 조합 자체가 많은 경우의 저장량은 별도로 남는다. 아래의 factorized 표현은 그 추가 압축을 위한 선택지다. **기본 방향은 ‘한 번 판정한 합법 조합 ID를 보관하고 재사용’하는 것이다.** 현재 문서는 설계 정정이며, 일반적인 합법 ID relation을 production에 구현한 상태는 아니다.

## 앞선 설명: 추가적인 표현 압축과 현재 구현의 범위

**가능하다. 앞서 제시한 표는 ‘압축이 불가능한 유형’이 아니라 ‘현재 구현에서 아직 압축하지 않은 유형’이다.** 같은 source owner, DIRECT/RELOCATION 혼합, 공동 배치, VALUE_MAP, 분리되지 않는 비용도 인덱스 변수와 제약으로 표현할 수 있다. 독립적인 relocation에 한정한 것은 이번 구현의 범위이며, 일반적인 표현 방법의 한계가 아니다.

추가 압축을 검토할 때에는 두 가지를 구분해야 한다. **전체 조합마다 boolean을 저장하면 clause 객체의 메모리는 줄지만 조합 수 자체는 그대로다. 입력별 인덱스와 공유 제약을 저장하면 전체 조합을 먼저 만들 필요가 없다.** 아래의 전체 bitmap과 제약식 비교는 추가 설계 설명이며, 사용자가 제안한 합법 ID만의 저장과 구별한다.

이 문서는 현재 소스와 비교한 설계 설명이다. 아래의 일반화는 아직 구현하지 않았다.

## 1. 조합의 번호를 안다는 것은 무엇인가

입력 A에 선택지 100개, B에 선택지 100개가 있다고 하자. 모든 FED 실행이 같은 전체 worker를 사용한다는 가정에서도 source, 전송 방식, ROW/COL/BROADCAST와 정확한 partition의 차이로 선택지는 생길 수 있다. 이 예시는 worker 부분집합을 추가로 열거하지 않는다.

```text
A 선택지 사전: A[0], A[1], ..., A[99]
B 선택지 사전: B[0], B[1], ..., B[99]

조합: (a, b)
조합 번호: a * 100 + b
합법성: valid(a, b, context)
비용: cost(a, b, context)
```

`(17, 23)`의 번호는 1723이다. 번호를 계산하기 위해 앞의 1723개 조합을 생성할 필요는 없다. 일반적으로도 입력별 선택지 수를 알면 곱셈·나머지 연산으로 조합과 번호를 변환할 수 있다.

선택지 사전에는 정확한 source/support key, 필요한 입력 binding, 전송 action, 목적 layout, proof 참조를 보존한다. 인덱스는 이 정보를 짧게 참조하는 수단이다. 같은 ROW라는 이유만으로 서로 다른 값이나 worker별 구간을 같은 선택지로 합치지는 않는다. 최종 선택 후에는 해당 인덱스에서 원래 authority를 가진 실행 경로를 복원한다.

## 2. 모든 조합의 boolean을 저장하는 경우

```text
validBits[1723] = true
validBits[1724] = false
...
```

BitSet처럼 1개 조합당 1bit를 쓰면 100×100은 10,000bit, 즉 payload 1,250byte다. Clause 객체 10,000개보다 훨씬 작게 저장할 수 있다. 이 수치는 사전·proof·객체 관리 비용을 제외하며 Java `boolean[]`이 자동으로 1bit라는 뜻은 아니다.

하지만 입력 5개에 각각 선택지가 100개라면 다음과 같다.

```text
조합 수: 100^5 = 10,000,000,000
전체 bitmap payload: 1,250,000,000byte = 1.25GB
```

모든 bit를 oracle로 채우면 여전히 100억 번의 조합 검사가 필요하다. 인덱스만 도입한다고 생성 시간이 없어지는 것은 아니다. 입력별 선택지 수를 nᵢ라 할 때 전체 bitmap은 Θ(∏nᵢ) bit이고, 각 조합을 한 번씩 검사하면 Θ(∏nᵢ)회 평가다. 한 번의 평가 비용은 별도로 든다.

## 3. 전체 bitmap 대신 제약을 저장하는 경우

예를 들어 A와 B가 같은 producer의 정확히 같은 source 선택을 참조해야 한다고 하자. 양쪽이 동일한 순서의 source 사전을 사용한다는 조건에서는 다음과 같다.

```text
valid(a, b) = (a == b)
```

100×100개의 clause나 bit 대신 선택지 사전과 등식 하나로 100개의 합법 조합을 나타낸다. 더 직접적으로는 공통 source 변수 `s`를 한 번만 두고 두 입력이 이를 참조할 수 있다.

```text
s ∈ {0, ..., 99}
A의 source = source[s]
B의 source = source[s]
```

실제 입력마다 사전 순서가 다를 수 있으므로 로컬 번호의 단순 등식이 아니라 canonical source/support key로 연결해야 한다. 또한 같은 owner라는 사실만으로 모든 binding이 같다는 뜻은 아니다. 공통 producer 선택은 공유하되 입력마다 허용되는 source와 전송을 별도로 검사한다.

일반적인 표현은 다음과 같다.

```text
선택 변수: x₁, x₂, ..., xₖ 및 필요한 공유 변수

valid(x, context)
  = source 일관성
  AND 전송 가능성
  AND partition 정렬
  AND privacy
  AND branch/value-version 일관성
  AND runtime oracle 제약
```

각 제약은 자신에게 필요한 변수만 읽는다. 작은 허용 집합·bitmap, 등식, 조건부 규칙, 필요한 시점에 평가하는 함수 등을 사용할 수 있다. 후보를 만들 때부터 이 형태를 유지해야 한다. Clause를 전부 만든 뒤 이 형태로 바꾸면 생성 비용은 이미 지불한 뒤다.

## 4. 앞의 표에 있는 경우를 어떻게 표현하나

| 유형 | 인덱스·제약 표현 | 반드시 보존할 의미 |
|---|---|---|
| 독립적인 입력별 relocation | 각 입력의 선택 변수와 허용 집합 | 정확한 source와 action |
| 같은 source owner를 공유 | 공통 producer 변수와 입력별 binding 호환성 | 하나의 producer 결정과 서로 모순되는 source를 동시에 선택하지 않음 |
| DIRECT와 RELOCATION 혼합 | 전송 종류가 포함된 선택지 또는 별도 전송 변수 | DIRECT에 필요한 실제 배치, relocation의 source·목적 배치·privacy |
| 입력 사이의 공동 배치 | 관련 입력을 함께 검사하는 compatibility factor | worker별 partition 정렬, 실제 연산의 공동 조건 |
| VALUE_MAP 및 joint boundary | 값 버전·도달 정의·분기 문맥과 boundary 연결 제약 | 서로 다른 분기/값의 상관관계와 정확한 proof |
| 비용이 입력별로 분리되지 않음 | 관련 선택들을 함께 읽는 비용 factor | 공유 비용의 중복 청구 방지와 조합별 실제 비용 |

따라서 이 유형들은 모두 일반화한 표현의 대상이다. 어려운 점은 번호 부여 자체보다 **기존 clause가 담고 있던 의미를 정확한 제약으로 옮기는 작업**이다.

공동 제약을 항상 두 입력씩의 boolean 표로 나눌 수 있는 것은 아니다. 예를 들어 세 bit의 합이 짝수인 조합만 합법이라면 `000, 011, 101, 110`이 허용된다. 이 관계를 입력 두 개씩만 보면 모든 쌍이 등장한다. 두 입력 표만 검사하면 불법인 `001`도 통과한다. 이런 경우에는 세 입력 제약을 유지하거나 같은 의미를 보존하는 보조 변수를 사용해야 한다.

VALUE_MAP의 분기도 비용이 싼 쪽을 optimizer가 임의로 고르는 변수가 아니다. 실제로 도달 가능한 각 문맥에서 값의 연결과 계획이 합법이어야 한다.

## 5. Boolean만으로 충분한 부분과 추가 정보

**완전한 조합의 가능/불가능 판정에는 boolean이면 충분하다.** 하지만 다음 두 정보도 필요하다.

첫째, 가능하다는 사실만으로 비용을 알 수 없다. 합법성 factor와 비용 factor를 분리한다.

```text
hard(x) = valid(x) ? 0 : +∞
totalCost(x) = compute(x) + transfers(x) + sharedCosts(x)
```

예를 들어 두 소비자가 같은 값과 같은 전송 결과를 공유할 수 있다면, 공유 upload의 활성 여부를 두 소비자의 요구와 연결하고 비용은 한 번만 부과한다. 이는 비용을 각 입력에 독립적으로 나눌 수 없는 경우도 표현할 수 있음을 보여준다. 다만 실제로 서로 다른 값/전송이면 공유하면 안 된다.

둘째, **일부 입력만 선택한 상태**에서는 아직 모른다는 상태가 필요하다.

```text
불가능이 증명됨 → 해당 가지 제거
합법성이 확정됨 → 해당 제약은 통과
아직 판단 불가 → 나머지 선택을 진행하거나 추가 검사
```

캐시에 없는 조합이나 미결정 입력을 `false`로 취급하면 합법 후보를 잃는다. Privacy와 oracle도 필요한 입력·출력 정보가 갖춰진 제약부터 평가한다. 부분 조합을 제거하려면 어떤 나머지 선택으로도 합법이 될 수 없다는 근거가 있어야 한다.

## 6. 현재 코드에서 이미 있는 기반과 남은 변경

현재 solver에는 상당 부분의 기반이 있다.

- [ExactCategoricalSolver](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java)의 `Factor.lazy(...)`는 변수 인덱스에 대한 평가 함수를 받는다. `PartialHardCostFunction`도 있다.
- [ExactPhysicalModel](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java)의 `addRealizationSupportFactors`는 허용되는 producer 인덱스에 0, 불법이면 +∞를 반환하는 factor를 만든다. `addJointFactors`는 공동 조건을 다룬다.
- [ExactHardFactorObservationDecomposition](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactHardFactorObservationDecomposition.java)은 hard factor가 구분하는 관측값별로 범주를 묶고 보조 변수와 truth factor를 만드는 경로다.
- [ExactPhysicalCostModel](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java)은 비용 factor와 공유 공급 비용을 별도로 다룬다.

현재 제한은 [PlacementAnalysis](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java)의 `independentSupportProduct()`와 모델의 compact alternative 적용 조건에 있다. 상관 support 등을 입력별 변수로 전달하는 경로가 일반화되지 않아 명시적 clause에 의존한다. Solver에 lazy factor가 있다는 것만으로 후보 생성 단계가 자동 압축되는 것은 아니다.

확장할 때에는 다음 순서가 적절하다.

1. 입력 선택 사전, 공유 변수, 합법성 factor, 선택 결과 복원을 하나의 relation 계약으로 연결한다.
2. 공통 source owner와 DIRECT/RELOCATION 혼합을 이 relation으로 전달한다.
3. Joint/VALUE_MAP과 결합 비용을 정확한 문맥·공유 제약으로 옮긴다.
4. Authority 검증, fingerprint, cost model, DP, 최종 receipt가 전체 clause를 순회하지 않게 한다.

작은 예제에서는 기존 명시적 표현과 모든 조합의 합법성·비용·복원 경로가 같은지 비교하고, 큰 축에서는 product 전체를 생성하지 않는지 계측해야 한다. 이 문서 작성에서 production 코드를 변경하거나 위 확장의 완료를 주장하지 않는다.

## 7. 기대할 수 있는 복잡도 개선

입력별 사전과 소수의 작은 제약으로 나타낼 수 있다면 표현 크기는 대략 `O(Σnᵢ + 제약 표현 크기)`가 된다. 공유 source 등식 같은 경우에는 product보다 크게 작아진다.

그러나 임의의 oracle이 모든 조합에 아무 규칙 없이 true/false를 반환한다면 전체 결과를 항상 작은 크기로 압축할 수는 없다. Lazy 평가도 조회 횟수가 많아지면 모든 조합을 검사할 수 있다. DP의 중간 표 또한 서로 연결된 제약의 범위에 따라 커진다.

따라서 목표는 **상관관계가 있다는 이유로 전체 조합을 먼저 전개하는 것을 없애고, 실제 제약의 구조만큼만 저장·계산하는 것**이다. 모든 문제의 최악 시간 복잡도를 없앤다는 주장은 아니다.
