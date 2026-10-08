# FedPlanner 입력 공급 조합의 의미와 실제 경우의 수

작성일은 2026-10-08이다. 구현과 테스트 설명은 main 커밋 `276f958efc`를 기준으로 하며, 실제 workload 수치는 별도의 과거 `engine-merged-v78` GLM 진단에서 가져왔다. 과거 GLM 수치를 현재 main의 LogReg 후보 수로 해석하지 않는다.

**Plan space에서 조합하는 것은 같은 계산 결과를 어떤 upstream 실행 결과에서 가져와, 어떤 이동을 거쳐, 어디서 계산할 것인가이다.** Source, worker pool, partition, relocation, support를 다섯 개의 독립 축으로 무조건 곱하는 구조는 아니다. 입력마다 가능한 구체적인 공급 방법을 모은 뒤, 입력들의 공급 방법을 조합한다.

생성 비용과 global pruning의 전체 흐름은 [Plan space 생성 비용과 pruning의 한계](FEDPLANNER_PLAN_SPACE_PRUNING_COMPLEXITY_2026-10-08_KO.md)를 참고한다.

우리의 정책은 FED 연산에 정해진 전체 worker 집합을 사용한다는 것이다. 아래의 서로 다른 worker pool과 다중 anchor 테스트는 일반적인 자료구조·테스트의 범위를 설명하며, 우리 workload에서 worker 부분집합을 선택한다는 뜻이 아니다. 이 가정에 맞춘 [Z = U + V의 전체 worker와 네 공급 조합 예시](FEDPLANNER_INPUT_SUPPLY_EXAMPLE_2026-10-08_KO.md)를 참고한다.

## 각 용어의 의미

`z = u + v`를 예로 들면 다음과 같다.

| 항목 | 실제 의미 | 예시 |
|---|---|---|
| Source | 해당 입력값을 공급하는 정확한 producer와 그 실행 결과 형태 | 같은 u라도 CP에서 만들어 coordinator에 있는 결과, FED에서 만들어 worker에 남은 결과 |
| Worker pool | 결과가 존재하거나 연산에 사용할 worker 배치 | worker 집합 `{w1, w2}`에 있는 결과와 `{w3, w4}`에 있는 결과 |
| Partition | 각 worker가 행렬의 어느 구간을 갖는지 | w1은 행 0~499, w2는 행 500~999를 보유 |
| Relocation | source를 consumer가 사용할 배치로 공급하는 명시적인 이동 | coordinator의 u를 기존 federated 배치에 맞춰 업로드 |
| Support | 특정 출력 후보가 실행 가능하려면 동시에 성립해야 하는 입력 선택과 근거 | 입력 0은 U1을 직접 사용하고, 입력 1은 V2를 이동해서 사용한다는 조건 |

Source를 선택한다는 것은 `u` 대신 다른 변수 `v`를 넣는다는 뜻이 아니다. 프로그램이 요구하는 같은 값의 여러 실행·공급 대안을 선택하는 것이다. 실제 코드도 producer의 `valueVersion`이 맞는 선택지만 가져온다. [PlacementRelationClosure.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java), 10180행.

Worker pool과 partition은 연결되어 있다. 동일한 `{w1, w2}`라도 1,000행을 `500/500`으로 나눈 배치와 `300/700`으로 나눈 배치는 정확한 layout이 다르다. 내부의 일부 pool 호환성 검사는 worker뿐 아니라 ROW/COL 분할 축의 구간도 비교한다. 따라서 내부의 worker-pool identity를 항상 단순한 worker 집합과 동일시해서는 안 된다. [PlacementIdentity.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java), 403행, 429행, 685행.

이 구분이 가능한 모든 worker 부분집합이나 모든 partition 분할점을 탐색한다는 뜻은 아니다. 입력과 연산에서 근거가 확보된 배치와 anchor를 사용한다. Anchor가 없으면 임의의 배치를 만들어 relocation 후보를 생성하지 않는다. 위의 500/500과 300/700은 layout 차이를 설명하기 위한 예시다. [PlacementRelationClosure.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java), 11767행과 11795행.

## 실제로 곱하는 선택지

특정 consumer 실행 형태와 목표 배치를 정하면, 입력별로 공급 방법 목록을 만든다. 다음은 네 공급 방법이 모두 해당 조건에서 가능하다고 가정한 설명용 예시다.

```text
입력 u의 공급 방법
  U1: worker에 있는 u의 결과를 직접 사용
  U2: coordinator에 있는 u의 결과를 목표 배치로 업로드

입력 v의 공급 방법
  V1: worker에 있는 v의 결과를 직접 사용
  V2: coordinator에 있는 v의 결과를 목표 배치로 업로드
```

| 조합 | u 공급 | v 공급 |
|---|---|---|
| 1 | U1 직접 사용 | V1 직접 사용 |
| 2 | U1 직접 사용 | V2 업로드 |
| 3 | U2 업로드 | V1 직접 사용 |
| 4 | U2 업로드 | V2 업로드 |

출력은 모두 같은 worker 배치의 `FED/FOUT`이어도 입력 공급 조합은 `2 × 2 = 4`개다. 각 조합의 이동 비용과 upstream 선택 조건은 다를 수 있다. 실제 프로그램에서는 oracle, privacy, anchor 및 공유 의존성 제약에 따라 일부 방법이 처음부터 없거나 조합이 거부될 수 있다.

코드는 각 입력에 대해 직접 사용할 수 있는 source와 relocation action을 적용할 수 있는 source를 모으고, 중복을 제거한 binding 목록을 만든다. 이 입력별 목록이 product의 각 축이 된다. [PlacementRelationClosure.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java), 10180행부터 10255행.

필터링과 중복 제거 이전의 기본 조합 수는 다음과 같이 생각할 수 있다.

```text
N_raw = Σ_(consumer 형태·목표 배치)
          ∏_(입력 i) 해당 조건에서 가능한 공급 방법 수_i
```

이후 공유 producer 선택의 모순과 다른 합법성 조건을 검사하고, 필요한 경계에서 중복을 합친다. 이 수식은 개별 조합 생성 작업의 원시 크기를 설명하며, 전체 프로그램의 최종 고유 계획 수를 뜻하지 않는다.

## Support는 공급 조건의 묶음

Support는 위 조합에 별도로 곱하는 자유 선택지가 아니다. 예를 들어 조합 2의 support는 다음 조건이다.

```text
입력 0이 U1을 선택함
AND 입력 1이 V2를 선택함
AND V2의 relocation과 해당 실행을 뒷받침하는 근거가 성립함
```

하나의 support clause 내부 조건은 AND로 결합된다. 같은 출력 realization에 여러 대안 clause가 있으면 clause 사이는 OR에 해당한다.

```text
하나의 출력 realization
  ├─ support 경로 A: U1 직접 사용 AND V1 직접 사용
  └─ support 경로 B: U1 직접 사용 AND V2 업로드
```

실제 표현도 `CandidateEmissionRealization`에 `supportClauses` 목록을 붙이는 형태다. 따라서 출력 realization 수가 작아도 그 아래의 support 목록은 클 수 있다. 다만 coarse rule이나 realization identity가 다르면 출력 모양이 비슷해도 항상 하나로 합쳐지는 것은 아니다. [PlacementAnalysis.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java), 1462행과 1510행.

## 실제 테스트에 정의된 경우의 수

Relocation 테스트에는 다음 조건이 있다.

- 입력값 두 개: `S0`, `S1`.
- Consumer의 배치 형태 두 개: `ROW`, `COL`.
- 사용할 목표 anchor 두 개: `A`, `B`.

이 fixture의 개별 relocation action은 다음과 같이 8개다.

```text
ROW: S0→A, S0→B, S1→A, S1→B
COL: S0→A, S0→B, S1→A, S1→B
```

여기서 S0와 S1은 둘 다 필요한 입력이다. 둘 중 하나를 대신 선택하는 관계가 아니다. `배치 형태 2 × 입력값 2 × 목표 anchor 2 = action 8개`는 가능한 개별 공급 action의 개수다.

`ROW`를 고정하고 두 입력의 목적지를 조합하면 다음과 같다.

| S0 목적지 | S1 목적지 | 이 fixture의 결과 |
|---|---|---|
| A | A | 가능 |
| A | B | 불가능 |
| B | A | 불가능 |
| B | B | 가능 |

이 fixture에서는 두 입력이 동일한 목표 anchor를 사용해야 한다. 따라서 ROW는 원시 조합 4개 중 2개, COL도 원시 조합 4개 중 2개가 유효하다. **개별 action은 8개지만, 유효한 consumer 공급 계획은 총 4개다.** 이 조건을 모든 종류의 FED 연산에 대한 일반 규칙으로 확대해서는 안 된다. [RelocationActionPlanSpaceCompletenessTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/placement/RelocationActionPlanSpaceCompletenessTest.java), 170행과 179행.

공유 입력도 경우의 수를 제한한다. `f(u, v, u)`에서 u의 후보가 U1/U2이고 v의 후보가 V1/V2라면 단순 곱은 `2³ = 8`이다. 그러나 앞의 u와 뒤의 u가 같은 물리 producer라면 둘을 서로 다르게 선택할 수 없어 다음 4개만 남는다.

```text
(U1, V1, U1)
(U1, V2, U1)
(U2, V1, U2)
(U2, V2, U2)
```

현재 prefix pruning 테스트가 정확히 이 경우를 검사한다. 이는 전체 연산의 모든 실행 가능성을 검증한 개수가 아니라, 해당 product에서 동일 producer의 선택 일관성 조건을 적용한 결과다. [RelocationBindingPrefixPruningTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/placement/RelocationBindingPrefixPruningTest.java), 32행.

## 실제 GLM 진단의 누적 작업량

과거 `engine-merged-v78` GLM W1 진단에서 가장 큰 누적 조합 작업량을 보인 owner는 hop 3653, `m(mult)` 연산이었다. GLM 초기화 코드의 다음 입력 3개짜리 곱셈 부분에 대응한다.

```text
y_corr * (1 - is_zero_y_corr) * (1 - is_one_y_corr)
```

[glm.dml](../scripts/builtin/glm.dml), 584행. 원래 문장에는 이 곱셈 결과에 다른 항을 더하는 계산도 포함된다.

| 지표 | 수치 | 정확한 의미 |
|---|---:|---|
| 조합 생성 요청 | 1,748회 | Closure 등의 진행 중 같은 owner에 대한 반복 요청 |
| 캐시 재사용 | 1,634회 | 이전 product를 재사용한 요청 |
| 실제 product 계산 | 114회 | 캐시로 처리하지 못한 요청 |
| 원시 Cartesian 크기의 누적합 | 22,777,756 | 캐시 hit 요청까지 포함해 각 요청의 선택지 수를 곱한 값의 합 |
| 실제 방문한 완성 조합 | 1,511,303 | 실제 계산에서 enumerate callback에 도달한 leaf의 누적 수 |
| 생성한 durable support clause | 1,505,606 | Durable realization을 생성한 누적 횟수이며 최종 고유 계획 수가 아님 |

`1,634 + 114 = 1,748`이므로 요청 대부분은 캐시로 처리됐다. 그럼에도 실제 계산 경로가 약 151만 개의 leaf를 방문했다. 또한 worker가 하나인 W1 진단이므로 이 작업량을 worker 선택 가짓수만으로 설명할 수 없다. 같은 값을 공급하는 realization·relocation·support 대안과 반복 처리량을 함께 봐야 한다.

이 owner에서 product 반환 시 센 realization 수의 누적합은 1,748이고, 그 안의 support clause 참조 수의 누적합은 22,693,264였다. **출력 realization 개수와 그 아래의 support 수는 규모가 크게 다를 수 있다.** 두 반환 counter는 캐시 재반환도 다시 집계하므로 최종 고유 realization이나 고유 clause 수로 해석하지 않는다.

근거:

- [v78 진단 집계](/grid/3/cofee-lm-sweep-mchoi-20260914/boundary-correctness-20261006/merged-v78-diagnostic-summary.json), 55행: W1 최대 누적 owner의 counter.
- [W1 원본 로그](/home/mchoi/fedplanner-boundary-correctness-20261006/glm-merged-v78-w1/results/B/glm_w1.log), 6행과 65859행: 최대 owner 진단 및 연산의 코드 위치.
- [v78 계측 구현](/home/mchoi/fedplanner-boundary-correctness-20261006/engine-merged-v78/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java), 10555행, 10570행, 10865행, 10884행: 요청·raw product·방문 leaf·생성 clause의 집계 위치.

이 자료는 과거 GLM의 진단용 실행이며 오늘 main의 LogReg 후보 수가 아니다. 요청마다 조합 대상 입력 목록이 달라질 수 있고, 요청별 axis 길이 배열이나 최대 single-product 수는 이 집계에 없다. 따라서 실측값을 특정한 `a × b × c`로 정확히 역산할 수 없다. 앞선 복잡도 설명의 `30⁴ = 810,000` 역시 설명용 계산이며 실측 후보 수가 아니다.

## 서로 구분해서 세어야 하는 수

| 지표 | 답하는 질문 |
|---|---|
| 실행 형태 수 | CP/FED, LOUT/FOUT 등의 큰 실행 선택지가 몇 개인가 |
| 정확한 출력 realization 수 | 실제 배치와 lineage를 구분한 출력 후보가 몇 개인가 |
| Support 조합 수 | 그 출력 후보를 실행 가능하게 만드는 입력 공급 조건 묶음이 몇 개인가 |
| 누적 생성 작업량 | Closure 갱신과 재조회까지 포함해 조합을 얼마나 만들고 확인했는가 |
| 최종 전체 계획 수 | 모든 연산과 공유 의존성의 조건을 함께 만족하는 완성된 선택이 몇 개인가 |

출력 배치가 몇 개 없더라도 그 배치를 만드는 공급 조합은 많을 수 있다. 현재 구현은 입력별 공급 목록의 조합을 구체적으로 전개하는 구간이 있으므로, 성능을 판단할 때 출력 후보 수만이 아니라 support 수와 실제 생성·재사용 작업량을 함께 확인해야 한다.
