# 실제 DML로 확인한 후보와 support 생성

**같은 전체 worker 두 개를 사용하는 작은 DML을 실제 common plan-space 생성 코드로 실행했다. `C = U * V` 하나에 realization 19개, support clause 151개가 남았다. 그중 한 realization에는 입력별 공급 선택 5개씩의 모든 조합인 `5 × 5 = 25`개 support가 들어 있었다.**

이 수치는 설명을 위해 가정한 값이 아니다. 아래 스크립트를 파싱하고 HOP rewrite를 거친 뒤 `NeutralPlacementGraphBuilder.buildAnalysis()`가 반환한 결과를 읽은 값이다. 두 번째 예제에서는 다음 연산이 앞 연산의 support 전체를 복제하지 않고 realization 참조를 받는 것도 확인했다.

| 실행 | 최종 rule fact | 최종 realization | 최종 support clause | common analysis 시간 |
|---|---:|---:|---:|---:|
| 집계 후 곱셈 예제 | 29 | 48 | 184 | 1,207.281 ms |
| 집계 전 곱셈 예제 | 23 | 25 | 32 | 858.722 ms |

전체 개수에는 주소 문자열·range 목록·상수·출력 연산도 포함된다. 시간은 각 최종 실행의 단일 측정값이며 파싱·HOP rewrite·JSON 출력을 제외한다. 진단 계측이 켜져 있으므로 성능 비교용 benchmark로 해석하지 않는다.

## 1. 실제로 실행한 범위

- 기준 production 코드: `276f958efc91477e3d0e8a2a492929455dd77227`.
- 실행 경로: `run_LAN_docker.sh --plan-space-example` → Docker → 실제 Java parser/HOP rewrite → production common analysis.
- 모든 FED 입력의 worker endpoint: `{localhost:5234, localhost:5235}`. 두 worker가 전체 worker다.
- A와 B는 모두 `8 × 2`, 같은 ROW 분할을 사용한다.
- source privacy: `PRIVATE_AGGREGATE`. 기존 테스트용 metadata setter로 제공한다. PUBLIC fixture는 실행하지 않았다.

**이번 실행은 실제 후보 생성 코드의 진단 실행이다. worker 프로세스에서 행렬을 계산하거나 DP/Global로 최종 계획을 선택하는 실행까지 수행한 것은 아니다.** 네트워크를 끈 Docker에서 worker metadata 조회만 기존 테스트 hook으로 대체했다. 후보 생성·pruning·closure 알고리즘은 production 구현을 그대로 호출했다.

production JAR의 SHA256은 다음과 같다.

```text
2ece4ec7ce5e6866ad10563c454bdc7cd0191ab17268c12a62433f42b5e0eb61
```

현재 main의 [빌드 검증 기록](../experiments/plan-space-example-20261008/baseline-build-validation.json)과 실제 사용한 staged JAR의 바이트 일치를 확인했다. 각 실행의 provenance에는 JAR·진단 소스·DML·의존성의 hash와 Docker 명령을 저장했다.

## 2. 집계 후 곱셈: 먼저 네 가지 입력 상태 조합을 만든다

실행한 [plan_space_aggregated.dml](../scripts/fedplanner/examples/plan_space_aggregated.dml):

```dml
# PRIVATE_AGGREGATE sources; column sums can leave the source workers.
A = federated(addresses=list("localhost:5234/A1", "localhost:5235/A2"),
    ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));
B = federated(addresses=list("localhost:5234/B1", "localhost:5235/B2"),
    ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));
U = colSums(A);
V = colSums(B);
C = U * V;
D = abs(C);
print(sum(D));
```

`*`는 원소별 곱셈이다. A/B의 파일 경로가 다르지만 사용하는 worker는 같다.

| 데이터 | worker 1: localhost:5234 | worker 2: localhost:5235 |
|---|---|---|
| A | A1: 행 `[0,4)`, 열 `[0,2)` | A2: 행 `[4,8)`, 열 `[0,2)` |
| B | B1: 행 `[0,4)`, 열 `[0,2)` | B2: 행 `[4,8)`, 열 `[0,2)` |

`U`와 `V`는 각각 `1 × 2` 집계 결과다. 이 실행에서는 각각 다음 두 realization이 만들어졌다.

| 결과 | 실제 ID | 실행 및 결과 위치 | 의미 |
|---|---|---|---|
| U의 local 결과 `U_L` | S15 | `FED / LOUT`, 실행 FType=ROW | worker에서 집계하고 결과를 coordinator에 둔다. |
| U의 federated 결과 `U_F` | S16 | `FED / FOUT / BROADCAST` | 집계 결과를 두 worker에 복제하는 derived output materialization을 포함한다. |
| V의 local 결과 `V_L` | S21 | `FED / LOUT`, 실행 FType=ROW | worker에서 집계하고 결과를 coordinator에 둔다. |
| V의 federated 결과 `V_F` | S22 | `FED / FOUT / BROADCAST` | 집계 결과를 두 worker에 복제하는 derived output materialization을 포함한다. |

여기서 local은 **출력이 coordinator에 있다**는 뜻이다. 원본 private 입력을 CP로 집계했다는 뜻이 아니다. S16/S22의 실제 집계 실행 FType은 ROW이고, materialization 후 결과 FType이 BROADCAST다.

`C`가 입력을 받는 상태는 다음 네 조합으로 나뉜다. `ABSENT_LOCAL`은 local 공급을 뜻하며 입력값 자체가 없다는 뜻이 아니다.

| 실제 rule ID | U의 입력 상태 | V의 입력 상태 | 최종 realization | 그 안의 support 합계 |
|---|---|---|---:|---:|
| R21 | local | local | 1 | 1 |
| R22 | local | BROADCAST | 6 | 23 |
| R23 | BROADCAST | local | 6 | 23 |
| R24 | BROADCAST | BROADCAST | 6 | 104 |
| 합계 | | | **19** | **151** |

즉, `2 × 2 = 4`는 입력 상태에 대한 rule 조합 수다. 각 rule 아래에서 실행 방식·출력 materialization·정확한 anchor와 입력 공급 방법이 구체화된다.

## 3. support 25개는 정확히 어떤 조합인가

R24에서 출력 `FED / FOUT / BROADCAST`이고 A에서 유래한 anchor를 사용하는 **S37 하나**를 보자. 실제 출력에 다음 값이 들어 있다.

```json
{
  "id": "S37",
  "rule": "R24",
  "state": "FED/FOUT/BROADCAST/SHAPE_INDEPENDENT",
  "layout": "DURABLE_MAP",
  "derivedOutputMaterialization": false,
  "supportCount": 25,
  "uniqueBindingsPerInput": {"0": 5, "1": 5}
}
```

아래에서 anchor A/B는 서로 다른 worker 집합이 아니다. A/B 입력에서 각각 유래한 map을 가리키는 내부 anchor다. 두 anchor 모두 같은 두 endpoint를 포함한다.

U를 공급하는 실제 선택은 다섯 가지다.

| 설명용 이름 | source | 공급 방식 | 실제 action |
|---|---|---|---|
| U1 | U_F = S16 | 이미 federated인 결과를 직접 사용 | 없음 |
| U2 | U_L = S15 | anchor A를 기준으로 BROADCAST materialization | A5 |
| U3 | U_L = S15 | anchor B를 기준으로 BROADCAST materialization | A6 |
| U4 | U_F = S16 | anchor A를 기준으로 다시 relocation | A5 |
| U5 | U_F = S16 | anchor B를 기준으로 다시 relocation | A6 |

V도 다섯 가지다.

| 설명용 이름 | source | 공급 방식 | 실제 action |
|---|---|---|---|
| V1 | V_F = S22 | 이미 federated인 결과를 직접 사용 | 없음 |
| V2 | V_L = S21 | anchor A를 기준으로 BROADCAST materialization | A1 |
| V3 | V_L = S21 | anchor B를 기준으로 BROADCAST materialization | A2 |
| V4 | V_F = S22 | anchor A를 기준으로 다시 relocation | A1 |
| V5 | V_F = S22 | anchor B를 기준으로 다시 relocation | A2 |

**source는 U/V당 두 개인데, source와 action을 묶은 입력 공급 binding은 다섯 개다.** 예를 들어 S16을 그대로 받는 것과 S16에 A5를 적용해 받는 것은 다른 binding이다.

이 다섯 가지씩을 하나씩 골라 묶은 결과가 다음 25개다. 셀의 `C1`~`C25`는 S37 안의 실제 clause ID다.

| U 공급 선택 ＼ V 공급 선택 | V1 | V2 | V3 | V4 | V5 |
|---|---|---|---|---|---|
| U1 | C1 | C22 | C24 | C23 | C25 |
| U2 | C10 | C2 | C6 | C3 | C7 |
| U3 | C20 | C12 | C16 | C13 | C17 |
| U4 | C11 | C4 | C8 | C5 | C9 |
| U5 | C21 | C14 | C18 | C15 | C19 |

실제 clause C2의 binding 부분은 다음과 같다.

```json
[
  {"input": 0, "source": "S15", "kind": "RELOCATION", "action": "A5"},
  {"input": 1, "source": "S21", "kind": "RELOCATION", "action": "A1"}
]
```

의미는 “U는 S15를 만들어 A5로 공급하고, **동시에** V는 S21을 만들어 A1로 공급해야 S37의 이 경로가 성립한다”이다. 한 clause 안의 조건들은 AND다. S37의 25개 clause는 서로 다른 대안인 OR다. 각 clause에는 binding뿐 아니라 해당 경로의 proof 참조도 들어 있다.

검증에서는 입력별 marginal 개수를 곱하는 데서 멈추지 않았다. 실제 25개 clause를 `(U binding, V binding)` 쌍으로 읽어, **중복 없이 5×5의 모든 쌍과 정확히 일치**함을 검사했다. [검증 결과](../experiments/plan-space-example-20261008/validation.json)에 저장했다.

S37만으로 C 전체를 설명한 것은 아니다. 같은 R24 아래에도 다른 출력 경로들이 남는다.

| 실제 ID | 출력 경로 | support 수 |
|---|---|---:|
| S36 | CP / LOUT | 1 |
| S37 | FED / FOUT / BROADCAST, A 유래 anchor | 25 |
| S38 | FED / FOUT / BROADCAST, B 유래 anchor | 25 |
| S39 | FED / LOUT | 26 |
| S40 | CP 계산 후 FOUT materialization | 1 |
| S41 | FED 결과의 derived FOUT materialization | 26 |
| 합계 | | **104** |

S37과 S41은 겉으로 보이는 결과 FType이 같아도 `derivedOutputMaterialization`과 실행 경로가 다르다. 출력 상태의 문자열만으로 동일 후보라고 판단하면 이 차이를 잃는다.

## 4. 전체 worker가 같은데 왜 선택이 더 생기는가

**이번 실행에서는 worker 부분집합 선택 때문에 25개가 된 것이 아니다.** 최종 realization의 durable anchor와 입력 binding에서 참조한 모든 relocation anchor가 동일한 두 endpoint를 사용하는지 검사했다.

다음 세 가지 구현상의 구분이 선택을 남긴다.

1. **물리 worker pool의 호환성과 exact anchor identity는 다르다.** A와 B의 map에는 각각 `/A1`, `/A2`와 `/B1`, `/B2`가 들어 있다. 동일 endpoint에서 실행할 수 있어도 내부 exact anchor와 action key는 같지 않을 수 있다.
2. **직접 사용 가능 여부와 relocation 제거 조건은 다른 비교를 쓴다.** 직접 사용 경로가 있더라도 source layout과 relocation의 기준 anchor가 exact하게 같지 않으면 relocation 경로도 남을 수 있다.
3. **중복 제거는 source/action/proof의 정확한 동일성을 본다.** “결과가 같은 worker에 있으니 더 싼 경로 하나만 남긴다”는 비용 dominance를 이 단계에서 수행한 결과가 아니다.

두 번째 항목은 이번 예제에서 특히 구체적이다. S16/S22의 결과 layout은 **BROADCAST 1×2**다. 반면 A1/A2/A5/A6 action의 `durableAnchor` 필드에는 기준으로 삼은 원본 **ROW 8×2** map이 들어 있다. action의 materialization FType은 BROADCAST다. 기준 anchor의 ROW 구간을 최종 결과 구간으로 읽으면 안 된다.

`PlacementRelationClosure`는 다음 조건으로 relocation binding을 추가한다.

```java
return pool == null || !PlacementIdentity.samePhysicalLayout(
    pool, action.key().durableAnchor());
```

`samePhysicalLayout`은 FType과 exact partition 목록을 비교한다.

```java
return left.fType() == right.fType()
    && left.partitions().equals(right.partitions());
```

따라서 같은 endpoint라도 `BROADCAST 1×2`와 기준 `ROW 8×2`는 이 비교에서 다르다. 이미 FOUT인 S16/S22의 relocation 선택까지 남는 이유를 실제 코드와 trace에서 확인할 수 있다. 코드 위치는 [relocation binding 구성](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L10180), [layout 동일성 비교](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java#L675)다.

이 관측은 **같은 worker를 쓰는데도 표현과 전송 경로 때문에 조합이 늘어난다**는 사용자의 의심을 뒷받침한다. 다만 어떤 action을 지워도 전역 비용·공유·anchor의 의미가 보존되는지는 별도의 증명이 필요하다. 이번 실행에서는 비용을 비교하거나 후보를 제거하는 변경을 하지 않았다.

## 5. 다음 연산이 앞의 support들을 전부 곱해서 가져가는가

이 부분은 [plan_space_private.dml](../scripts/fedplanner/examples/plan_space_private.dml)로 확인했다. 동일한 A/B 선언 뒤에 다음을 실행한다.

```dml
U = A * 2;
V = B * 3;
C = U * V;
D = abs(C);
print(sum(D));
```

production HOP rewrite가 U/V/C의 곱셈을 하나의 `m(mult)` HOP로 합쳤다. 실제 HOP 입력은 A, B, 3, 2다. 따라서 DML 세 줄을 독립적인 세 단계로 세지 않았다. 아래 C는 이 합쳐진 곱셈 결과를 뜻한다.

| 연산 | realization | support 수 | 그 support가 참조하는 앞의 결과 |
|---|---|---:|---|
| C | S20 | 2 | A의 S14, B의 S18 |
| C | S21 | 1 | A의 S14, B의 S18 |
| D = abs(C) | S22 | 3 | S20 직접 / S21 직접 / S21+relocation |
| D = abs(C) | S23 | 2 | S20 직접 / S21 직접 |

S22의 실제 입력 조건은 다음 세 가지다.

```text
S22.C1: input 0 = DIRECT(S20)
S22.C2: input 0 = DIRECT(S21)
S22.C3: input 0 = RELOCATION(S21, A2)
```

S20 안에 support가 두 개 있어도 S22.C1은 **S20을 한 번 참조**한다. `S20.C1`과 `S20.C2`를 각각 복사해서 두 입력 후보로 만들지 않는다. 이 trace에서도 D가 사용하는 서로 다른 source 참조는 S20/S21 두 개임을 검사했다.

코드의 `CandidateRealizationReference` 역시 `rule + realization key`를 저장한다. 이전 support clause 전체를 담는 객체가 아니다. [참조 자료구조](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java#L572)를 보면 이 차이를 확인할 수 있다.

따라서 실제 설명은 다음과 같다.

- 여러 입력이 만나는 연산에서는 **각 입력의 공급 binding 목록을 곱하는 전개**가 있다. 앞의 5×5가 실제 사례다.
- 그 결과는 realization별 support 목록으로 합쳐진다.
- 다음 연산은 앞의 realization을 참조한다. 앞 support 수가 매번 그대로 다음 입력 후보 수가 되는 구조는 아니다.
- 그래도 source inventory 작성, proof 확인, clause 병합에서 이전 support를 순회할 수 있고, 다른 source/action이 남으면 다음 연산에서도 새 조합이 만들어진다.

집계 후 곱셈 예제에서도 C의 support는 151개였지만 바로 다음 `abs(C)`의 최종 support는 3개였다. 수가 단계마다 반드시 곱셈으로 증가하는 것은 아니다. 이때 빈 binding을 가진 clause가 있다고 해서 입력 의존성이 없다고 읽으면 안 된다. rule의 입력 상태, HOP edge, proof 의존성은 별도로 존재한다.

## 6. 실제 코드에서 조합을 만드는 지점과 작업량

[입력 FType 조합 생성](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L729)은 각 입력 domain을 재귀적으로 순회한다. 집계 예제의 C에서는 local/BROADCAST 두 상태씩이 네 rule로 이어졌다.

그 다음 [exact 공급 binding 구성](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L10180)에서 source별 DIRECT/RELOCATION 선택을 모으고 exact 중복을 제거한다. [binding product 생성](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L10421)은 실제 assignment들을 순회해 realization/support를 만들고, [realization 병합](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L1737)이 같은 realization key 아래의 clause들을 합친다.

입력 공급 선택 수가 `b₁, …, bₖ`인 한 product 전개의 가능한 leaf 수는 `∏ bᵢ`다. 호환성 pruning이 없다면 assignment를 만들고 검사하는 비용은 적어도 leaf 수에 비례하며, 입력 목록 처리까지 단순하게 세면 `O(k × ∏ bᵢ)` 규모다. 이것은 **한 product 전개의 비용**이고 전체 분석의 고정된 복잡도식은 아니다. 실제 전체 비용에는 여러 rule·emission·anchor, proof 탐색, 병합 및 closure 반복이 추가된다.

집계 예제에서 읽은 누적 counter는 다음과 같다.

| counter | 실제 값 | 범위 |
|---|---:|---|
| 최종 support clause 합계 | 184 | 전체 스크립트의 최종 realization들을 한 번 합산 |
| `inputLeaves` | 44 | 계측 구간에서 방문한 입력 상태 조합 leaf의 누적 |
| `supportLeaves` | 16 | native support product의 계측 leaf 누적 |
| `relocationLeaves` | 214 | relocation binding product의 계측 leaf 누적 |
| `realizationMergeUniqueClauses` | 2,076 | 각 병합 호출에서 고유 clause로 처리된 누적 건수 |
| `realizationMergeDuplicateClauses` | 925 | 각 병합 호출에서 중복 clause로 처리된 누적 건수 |

`supportLeaves=16`만 보고 support가 16개라고 할 수 없다. relocation 쪽에서도 clause가 생성된다. 또한 병합 누적 2,076건은 서로 다른 최종 support 2,076개라는 뜻이 아니다. closure 갱신 중 같은 clause가 다시 처리될 수 있다.

이 작은 예제는 약 1초 안에 분석됐다. 큰 workload의 지연 시간을 이것만으로 설명하거나, 실제로 특정 realization에 support 10만 개가 있다고 주장하지 않는다. **이번에 직접 확인한 값은 C 전체 151개, S37 하나 25개다.**

## 7. 다시 실행하는 방법

추가한 소스는 [Java 진단 프로그램](../scripts/fedplanner/PlanSpaceExample.java), [Docker runner](../scripts/fedplanner/run_plan_space_example.py), 두 [DML 예제 디렉터리](../scripts/fedplanner/examples)다. Java 프로그램은 별도 후보 생성 알고리즘을 구현하지 않고 다음 production 호출을 관찰한다.

```java
translator.constructHops(program);
translator.rewriteHopsDAG(program);
// 기존 테스트 hook으로 federated source privacy metadata를 등록한다.
PlacementAnalysis analysis = builder.buildAnalysis(program);
// analysis.candidateRuleFacts()의 emissions → realizations → supportClauses를 읽는다.
```

현재 환경에서는 다음 명령으로 두 예제를 다시 실행할 수 있다. 기존 결과 덮어쓰기를 방지하기 위해 output 디렉터리는 새 경로여야 한다.

```bash
cd /home/mchoi/w1357-structural-grounding-20261006
example_root=$(mktemp -d /home/mchoi/fed-plan-example.XXXXXX)

scripts/fedplanner/run_LAN_docker.sh --plan-space-example \
  --script scripts/fedplanner/examples/plan_space_aggregated.dml \
  --engine-target /home/mchoi/w1357-stage-main276-20261008T1025Z/systemds/target \
  --output-dir "$example_root/aggregated"

scripts/fedplanner/run_LAN_docker.sh --plan-space-example \
  --script scripts/fedplanner/examples/plan_space_private.dml \
  --engine-target /home/mchoi/w1357-stage-main276-20261008T1025Z/systemds/target \
  --output-dir "$example_root/private"
```

기존 `SystemDS.jar`와 `lib/`, 호스트의 `javac`, runner에 고정된 Docker image가 필요하다. 진단 클래스만 컴파일하며 production JAR을 수정하지 않는다. 현재 진단 프로그램은 직선형 DML만 지원한다. 함수·루프 전체 workload용 일반 trace 도구는 아니다.

집계 예제의 실제 출력은 다음과 같다. H1~H7의 출력 순서는 분석 occurrence 순서이며 실행 순서를 뜻하지 않는다.

```text
hop  line  name          op        rules  realizations  supports
H1   10    parsertemp29  ua(+RC)   2      3             3
H2   9     D             u(abs)   2      3             3
H3   8     C             b(*)     4      19            151
H4   6     U             ua(+C)   1      2             4
H5   7     V             ua(+C)   1      2             4
H6   2     A             Fed A    1      1             1
H7   4     B             Fed B    1      1             1
```

각 output 디렉터리의 `trace.json`에서 `realizations[].supports[].bindings`를 보면 실제 입력 선택을 전부 읽을 수 있다. R/S/C/A/P ID는 이 진단 출력 안에서 쓰는 짧은 식별자다. 다른 스크립트나 코드 버전에서 같은 숫자를 보장하지 않는다.

## 8. 보존한 실행 증거와 검증

- 집계 예제: [전체 trace](../experiments/plan-space-example-20261008/aggregated/trace.json), [provenance](../experiments/plan-space-example-20261008/aggregated/provenance.json), [실행 로그](../experiments/plan-space-example-20261008/aggregated/diagnostic.log).
- 집계 전 예제: [전체 trace](../experiments/plan-space-example-20261008/private/trace.json), [provenance](../experiments/plan-space-example-20261008/private/provenance.json), [실행 로그](../experiments/plan-space-example-20261008/private/diagnostic.log).
- [구조 검증 결과](../experiments/plan-space-example-20261008/validation.json): 두 실행 성공, source/proof/action 참조 연결, support 개수 일치, 동일 전체 worker pool, S37의 완전한 5×5 조합, 다음 연산의 S20/S21 참조를 확인했다.

JSON의 `inputBindingRelocations`는 최종 support가 입력 binding에서 참조한 action 목록이다. 분석 전체 action inventory나 output materialization action 전체 목록은 아니다. `proofs`에는 서로 다른 proof를 구별할 수 있도록 exact signature도 보존했다. 빈 binding clause를 제거하거나 proof가 다른 clause들을 임의로 합산·축약하지 않았다.

oracle·runtime·planner의 합법성 규칙과 pruning 정책 변경은 없다. 이번 추가 사항은 재현용 DML, production 분석을 호출하는 진단 프로그램, Docker 실행 경로와 결과 문서다.
