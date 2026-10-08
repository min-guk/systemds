# 합법 조합 ID 저장과 제약 우선 탐색 구현 결과

합법 판정이 끝난 전체 조합은 **선택지 사전과 합법 조합 ID**로 보관하고 downstream이 직접 참조하도록 구현했다. 생성 단계에는 **가장 작은 남은 domain부터 선택하는 MRV**와 **선택에 따른 나머지 domain 축소**를 적용했다.

통합 Java 회귀 검사 426개와 Docker DP 사례 4개가 통과했다. 아래 탐색 수치는 통제된 fixture의 작업량이며 대형 workload의 실행 시간 개선 수치는 아니다.

## 1. 합법 조합의 인덱스만 저장

```text
이미 합법 판정된 전체 조합
  → source/action/proof 사전을 고정
  → 합법 조합 ID만 저장
  → model·cost·DP는 ID handle에서 사전 참조
  → 선택한 ID의 정확한 source/action/proof로 receipt 생성
```

새 `IndexedSupportClauses`는 같은 source owner, DIRECT/RELOCATION 혼합, 상관관계의 빈 조합, 서로 다른 proof도 표현한다. 독립 product라는 조건을 요구하지 않는다. 서로 구조가 같아도 authority가 다른 source·action·proof 객체는 사전에서 임의로 합치지 않는다.

ID는 proof/native metadata와 입력 binding의 전체 선택을 나타낸다. 일반적인 경우 `long[]`, 범위를 넘으면 `BigInteger[]`로 보관한다. 합법 조합이 적은데 이론적인 전체 공간이 크다는 이유로 후보를 버리지 않는다. ID는 해당 immutable relation 안에서만 의미를 가진다.

기존 `CandidateRealizationSupportClause` 접근자는 유지하면서 내부적으로 사전 기반 immutable view를 읽게 했다. 따라서 비용 계산이나 joint 경로에서 완전한 입력 목록을 다시 만들 필요가 없다. 최종 receipt는 원래 relation이 발급한 canonical ID handle만 받으며, 같은 내용을 가진 위조 handle은 거부한다.

작은 검증에서는 가능한 2×3 위치 중 **합법인 4개만 저장**했고, 남은 2개를 새로 생성하지 않았다. 명시적 표현과 합법 관계·비용 raw bits·Local/Exact 최적값·최종 receipt가 일치했다. DIRECT/RELOCATION 혼합도 같은 pipeline으로 비교했다.

이 경로의 완전한 tuple 저장 수는 0이다. **경량 ID handle, 비용 factor와 solver 상태는 여전히 필요하다.** 이것을 전체 후보 객체나 DP 상태가 0개라는 뜻으로 해석하면 안 된다. 기존 100×100 독립 product 경로는 그대로 압축 상태를 유지하며 10,000개 ID로 펼치지 않는다.

변경 위치: [IndexedSupportClauses](../src/main/java/org/apache/sysds/hops/fedplanner/placement/IndexedSupportClauses.java), [PlacementAnalysis](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java), [PlacementRelationClosure](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java).

## 2. 입력을 고를 때마다 남은 선택지가 가장 적은 인자부터 탐색

예를 들어 입력 domain이 다음과 같다고 하자.

```text
입력 0: 독립적인 선택 100개
입력 1: source A의 선택 100개
입력 2: source A73 하나만 허용
```

기존 순서는 입력 0을 펼친 뒤 입력 1의 선택이 입력 2와 일치하는지 확인한다. 새 순서는 입력 2를 먼저 선택하고, 입력 1을 A73 하나로 줄인 뒤 입력 0을 펼친다.

| 항목 | 변경 전 | 변경 후 |
|---|---:|---:|
| 부분 조합 방문 | 301 | 103 |
| 최종 합법 조합 | 100 | 100 |

다음 입력은 최초 domain 크기만 보고 한 번 정렬하는 것이 아니라, **현재 선택으로 축소된 domain 크기**를 보고 매번 고른다. 탐색 순서는 달라져도 결과의 입력 위치는 원래 순서로 돌려놓는다.

Source 일관성은 기존의 정확한 제약을 사용한다. 같은 owner의 서로 다른 source를 동시에 선택할 수 없다는 조건으로만 가지를 줄이며, 다른 owner의 worker 배치가 다르다는 이유로 임의 제거하지 않는다. 선택된 owner에 영향을 받는 domain만 찾아 갱신한다.

250개의 무작위 작은 product를 독립적인 brute-force 구현과 비교해 합법 tuple의 누락·추가·중복이 없음을 검사했다. 함수/논리 boundary의 reference product도 같은 source 기반 탐색기를 사용한다.

## 3. 싼 검사부터 적용하고 살아남은 후보만 oracle로 전달

검사에 필요한 정보가 확보된 지점에서 다음 순서로 처리한다.

| 단계 | 적용 내용 |
|---|---|
| 입력 domain | Authoritative privacy가 금지한 ABSENT_LOCAL을 먼저 제거한다. |
| 단일 physical 선택 | FType·value-version·obligation 조건을 pool 조회·정렬 검사보다 먼저 적용한다. |
| 부분 source 선택 | 같은 owner의 source 일관성으로 나머지 domain을 줄인다. |
| 부분 operation 입력 | 해당 rule이 증명하는 FED 불가능 조건을 검사하고 MRV로 다음 인자를 고른다. |
| 살아남은 완전 입력 | 기존 oracle의 caps와 shape evidence를 생성하고 출력 privacy를 적용한다. |
| 전역 후보 선택 | 공유 source·joint·함수 boundary 등 실제 공동 선택에 필요한 조건을 검사한다. |

조건의 평균 비용과 제거율을 학습하는 범용 scheduler를 만든 것은 아니다. 명확히 싼 조건을 먼저 배치하고, 현재 domain 크기로 탐색 순서를 동적으로 정한다. 전체 assignment가 있어야 판단할 수 있는 전역 조건까지 입력 단계에서 끝났다고 가정하지 않는다.

Oracle에는 기본값이 `UNKNOWN`인 부분 판정 API를 추가했다. 현재 `WSLOSS`, `WSIGMOID`, `WCEMM`, `WDIVMM`, `RightIndex`가 자신의 기존 실행 규칙에 근거한 부분 판정을 제공한다. 출력 profile이 비었다는 이유로 FED 불가능이라고 판단하지 않는다.

**FED 불가능이라는 이유만으로 CP까지 버리지는 않는다.** Authoritative privacy의 보호 입력 때문에 FED가 반드시 필요한 경우에만 이 부분 판정으로 전체 가지를 제거한다. CP가 합법이거나 부분 판정이 없는 rule에서는 기존 경로를 유지한다. 미할당 상태는 `ABSENT_LOCAL(null)`과 별도로 표현한다.

WSIGMOID fixture에서는 privacy 필터 이후 45개였던 완전 oracle 입력 대상이 **18개**로 줄었다. 이를 위해 부분 판정은 **11번** 수행했다. MRV가 고른 후보 목록과 판정 결과를 그대로 재사용하고, 해당 부분 규칙의 나머지 조건이 모두 충족되면 그 아래에서는 같은 부분 검사를 생략한다.

부분 조합 전체를 캐시하지 않는다. 따라서 부분 조합 수만큼 큰 캐시를 새로 만들지 않는다. 완전 oracle의 `ShapeHint`는 조회한 사실을 기록하므로, 수치만 같은 hint를 같은 proof로 취급하는 캐시도 두지 않았다. 동일 hint의 누적 조회와 fresh hint의 독립 proof를 회귀 검사했다.

UDF/FunctionCall은 일반 kernel의 부분 검사를 건너뛴다. 다만 현재 FunctionCall rule은 출력 FType과 함수 boundary에 필요한 placeholder 의미를 가지므로 **완전 판정을 무조건 생략하지는 않는다.** 해당 의미와 privacy·함수 경계 제약은 보존했다.

변경 위치: [PlacementCandidateGenerator](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java), [RulesApi](../src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesApi.java), [RulesCore](../src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesCore.java), [Rulesets](../src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java), [OracleFacade](../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java).

## 4. 검증과 남은 범위

- Frozen production 소스 22개, 테스트 소스 19개 컴파일 통과. 이전 변경을 포함한 관련 소스를 함께 컴파일했다.
- 통합 JUnit **426개 통과**, 기존 ignore 3개. 전체 Maven test suite를 실행한 것은 아니다.
- 일반 ID 저장 6개 검사, sparse/mixed 및 기존 factorized pipeline 6개 검사, 새 부분 oracle/MRV 7개 검사와 기존 joint·VALUE_MAP·control-flow 회귀를 포함한다.
- MRV 변경 전 새 회귀는 prefix 기대 103에 실제 301로 실패했고, 변경 후 통과했다.
- 정적 리뷰에서 확인한 null 입력 오류, 잘못된 handle 소유권, 조합 수만큼 커지는 부분 캐시, shape-proof 혼합 가능성을 수정했다.

Docker는 `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e`와 `local` DP planner로 실행했다.

| 사례 | 결과 |
|---|---|
| `joint_branch_upload` | 정상 실행 및 업로드 action 실행 확인 |
| `joint_correlated_aa` | 공유 source의 상관관계를 유지한 정상 실행 |
| `joint_function_calls` | 함수 호출 boundary를 유지한 정상 실행 |
| `joint_independent_private_ab_negative` | 불법 privacy 조합을 계획 단계에서 거부 |

실행 결과는 [Docker 결과](../experiments/legal-combination-ids-20261008/runtime-result.json), [명령](../experiments/legal-combination-ids-20261008/runtime-command.json), [검증 집계](../experiments/legal-combination-ids-20261008/validation.json)에 보관했다. 현재 workspace 소스와 frozen 빌드의 SHA256이 일치함도 확인했다.

합법 ID 저장은 현재 **closure가 확정한 relation의 publication 경계**에 적용된다. Closure 내부에서 임시 clause를 만드는 모든 경로까지 없앤 것은 아니다. 생성 시간 개선은 MRV·forward checking·부분 oracle pruning으로 별도로 적용했다. 또한 일부 rule만 부분 판정을 구현했고 일반 DP의 최악 지수 복잡도는 남는다.

임시 객체 생성 비용, 합법 ID가 증명하는 범위, VALUE_MAP·함수 경계·공유 비용의 차이는 [Closure와 공동 제약 설명](FEDPLANNER_CLOSURE_AND_GLOBAL_CONSTRAINT_LIMITS_2026-10-08_KO.md)에 예시와 함께 정리했다.

관련 증거: [JUnit 로그](../experiments/legal-combination-ids-20261008/tests.log), [실행 명령](../experiments/legal-combination-ids-20261008/tests-command.json), [MRV 변경 전 실패](../experiments/legal-combination-ids-20261008/mrv-red.log), [production SHA256](../experiments/legal-combination-ids-20261008/source-sha256.json), [세션 기록](SESSION_ISSUES_2026-10-08.md).

전체 frozen engine과 runtime 산출물은 `/grid/3/cofee-lm-sweep-mchoi-20260914/legal-combination-ids-20261008/`에 보관한다. Commit/push는 수행하지 않았다.
