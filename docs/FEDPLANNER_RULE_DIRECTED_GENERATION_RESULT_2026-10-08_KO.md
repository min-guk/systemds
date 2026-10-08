# FedPlanner 규칙 기반 입력 관계 생성과 압축 소비 개선

2026-10-08. 비교 기준은 직전에 구현한 합법 ID 저장·MRV·부분 Oracle 버전이다. 이전 변경을 포함한 전체 Git diff를 이번 작업의 변경량으로 간주하지 않는다.

입력 전체 product를 Oracle에 반복 전달하는 대신, **기존 rule의 결과에 영향을 주는 입력 축만 평가해 실행 가능한 관계를 생성하는 경로**를 구현했다. 동시에 Closure와 DP에서 압축된 source 관계가 불필요하게 펼쳐지는 지점을 개선했다.

모든 연산과 모든 공동 관계를 완전히 symbolic하게 바꾼 것은 아니다. 적용 범위와 남은 전개 지점은 아래에 구분한다.

**분석과 설계 선택**

조합이 늘어나는 지점은 두 층이었다.

- 입력 FType 단계: `PlacementCandidateGenerator.buildNode`는 MRV를 통과한 tuple마다 exact Oracle을 호출하고 rule fact를 만든다. 동일한 Oracle 결과를 갖는 보조 입력들도 곱해진다.
- 물리 source 단계: 동일한 rule 안에서도 어떤 source와 전송을 사용하는지에 따라 support 조합이 생긴다. 독립 product를 저장해도 일부 Closure 비교·병합과 downstream 소비가 이를 다시 전개했다.

별도의 역방향 합법성 규칙을 작성하면 기존 정방향 규칙과 달라질 위험이 있다. 그래서 rule은 **결과를 결정하는 입력 위치**를 선언하고, 관계 생성기는 해당 축에 대해서만 기존 `caps`를 호출한다. 새로운 runtime 합법성 표를 만들지 않았다.

예를 들어 WSIGMOID의 Oracle 결과는 첫 입력 X와 고정된 연산 속성에 의해 결정된다. U와 V의 source·전송 선택 자체를 없애는 것이 아니라, 그 선택마다 같은 Oracle 판정을 반복하지 않는 것이다.

```text
기존: 각 (X, U, V) tuple → caps(X, U, V)

변경: X의 각 상태 → 기존 caps로 판정
      → {X=ROW} × U의 선택지 × V의 선택지
      → {X=COL} × U의 선택지 × V의 선택지
      → CP 결과를 갖는 영역도 별도로 보존
```

실제로 어떤 영역이 FED인지는 기존 rule이 결정한다. 위 ROW/COL 예시는 WSIGMOID의 설명이며 다른 연산에 그대로 적용하지 않는다.

**구현한 범위**

| 변경 | 적용 조건과 동작 |
|---|---|
| Rule 기반 실행 관계 | WSLOSS, WSIGMOID, WCEMM, WDIVMM이 shape에 독립적인 결정 축 `{0}`을 선언한다. 나머지 rule은 기존 경로를 유지한다. |
| 직접 생성 | Generator가 서로 겹치지 않는 입력 영역을 순회한다. 해당 경로에서는 부분 Oracle/MRV를 다시 실행하지 않는다. |
| Privacy 선적용 | 보호 입력이 local인 선택을 먼저 제외한다. 영역이 비면 Oracle도 호출하지 않는다. FED가 필수인 경우에만 CP 영역을 제외한다. |
| Oracle 의미 재사용 | 같은 결정 축의 판정은 해당 relation 안에서 재사용한다. 공개 정방향 API를 전역 캐시하지 않는다. ShapeHint를 조회하는 rule이 잘못 독립성을 선언하면 실패한다. |
| 오류 보존 | RULE_ERROR는 CP 영역 pruning보다 먼저 오류로 전파한다. |
| Closure 비교 | 이미 같은 product임이 확인된 경우 source owner/action을 각 축에서 한 번씩 검증한다. 모든 clause를 생성하지 않는다. |
| Closure 병합 | 한 rectangle이 다른 rectangle에 완전히 포함되면 큰 rectangle을 그대로 유지한다. Proof·source owner·action 식별자가 달라지거나 공동 조건의 빈 조합이 생기는 경우에는 적용하지 않는다. |
| DIRECT 압축 소비 | 축마다 DIRECT 또는 RELOCATION 방식이 고정된 독립 관계를 Cost Model/DP에 전달한다. DIRECT는 모든 선택의 실제 worker/partition 배치와 FType이 같아야 한다. |

DIRECT 비용 계산은 공통으로 전달되는 배치를 사용한다. 최종 source는 대표 후보로 고정하지 않고, DP가 실제로 선택한 source에 맞춰 clause를 복원한다. 직접 입력의 검증에는 support key뿐 아니라 정확한 source reference도 사용한다.

같은 축 안에서 DIRECT와 RELOCATION이 섞이는 경우, 같은 source owner가 여러 축을 연결하는 경우, VALUE_MAP·joint·함수 경계처럼 상관관계가 필요한 경우를 독립 product로 간주하지 않는다. 기존 합법 ID/명시적 관계와 공동 제약을 유지한다. Worker 수·partition·privacy 정책이나 runtime fallback은 변경하지 않았다.

**작업량과 동등성 검증**

| 사례 | 기존 | 변경 후 | 보존한 내용 |
|---|---:|---:|---|
| WSIGMOID: privacy 이후 45개 입력 공간 | MRV 이후 완전 Oracle 18회 + 부분 검사 11회 | 완전 Oracle 5회 + 부분 검사 0회 | 동일한 합법 tuple 18개와 caps |
| Closure: 100×100 관계의 동일성 검증 | clause 10,000개 생성 | clause 0개 생성, 입력별 선택지 200개 비교 | source owner/action 권한과 삭제된 action 거부 |
| 여러 축에서 포함되는 rectangle 병합 | 명시적 clause 병합으로 전개 | 큰 rectangle 재사용 | 정확한 합집합, holes 및 외부 권한 혼입 방지 |
| DIRECT source 16개를 가진 관계 | source별 명시적 소비 | 대표·최종 선택 clause 최대 2개 | 비용 raw bits·최적값·마지막 source의 정확한 receipt |

첫 행은 전체 compile 시간이 아니라 통제된 동일 입력 공간에서의 검사 횟수다. 합법 fact 18개 자체를 없앴다고 해석하면 안 된다. Closure의 10,000개 생성은 새 회귀 테스트가 변경 전 코드에서 실제로 실패하는 것으로 재현했다.

Oracle 관계 검사는 각 matrix 축에서 `null + 모든 FType`을 전수 비교한다. Caps의 실행 방식·출력·사유·notes와 shape proof, 영역의 누락·중복을 확인하고 WDIVMM의 base type 분기도 검사한다. 빈 영역, 잘못된 arity, guard 실패, CP 가능 영역, 0번이 아닌 보호 입력, RULE_ERROR, 잘못된 shape 독립성 선언, 기존 UDF/FRAME RightIndex 경로도 검사한다.

**Docker 측정 범위**

실행은 `run_LAN_docker.sh`와 DP local을 사용한다. 신규 weighted 사례는 WSLOSS/WCEMM의 공개 입력과 별도의 실제 보호 데이터 집계를 함께 포함한다. 공개 입력만 있는 테스트는 아니다. Weighted 연산 자체가 보호 입력을 직접 소비하는 성공 사례라고 주장하지 않는다.

보호 데이터를 weighted 연산에 직접 전달한 최초 두 시도는 **기준 버전부터** `No privacy-safe physical placement`로 실패했다. 해당 입력은 단일 range의 FULL 배치였으며, WSLOSS/WSIGMOID 규칙은 FULL 입력을 허용하지 않는다. 이 결과를 모든 보호 데이터·모든 partition에서 weighted 연산이 불가능하다는 뜻으로 일반화하면 안 된다. Privacy 완화로 우회하지 않았으며 실패 증거도 보관한다.

추가로 X를 두 개의 4×3 **PRIVATE_AGGREGATE ROW shard**로 구성하여 WSLOSS/WCEMM이 직접 소비하는 사례를 실행했다. 기준 버전과 변경 후 모두 통과했고 출력·비용·계획 fingerprint가 일치했다. 이 사례는 버전별 1회 의미 보존 검사이며 아래의 3회 성능 통계와 섞지 않는다. [Protected ROW 검증 결과](../experiments/rule-directed-generation-20261008/protected-row-parity.json).

컴파일 시간, planning/analysis 시간, heap과 컨테이너 메모리의 측정 범위는 [측정 방법](../experiments/rule-directed-generation-20261008/MEASUREMENT_METHOD.md)에 기록한다. Heap 관측값과 컨테이너 메모리는 서로 다른 지표이며, 주기적 샘플을 정확한 순간 최대값이라고 부르지 않는다.

동일 Docker 조건에서 weighted 사례를 버전별 3회 실행했다. 아래는 중앙값이다.

| 지표 | 기존 | 변경 후 | 해석 |
|---|---:|---:|---|
| DML compile 시간 | 2.226초 | 1.889초 | 중앙값 15.1% 감소 |
| Candidate planning 전체 | 1.613초 | 1.221초 | 중앙값 24.3% 감소 |
| Candidate analysis | 0.923초 | 0.926초 | 사실상 동일 |
| JFR의 GC 시점 최대 관측 heap | 78.83 MiB | 66.69 MiB | 중앙값 15.4% 감소 |
| 전체 컨테이너 최대 샘플 메모리 | 754.20 MiB | 783.10 MiB | 중앙값 3.8% 증가 |

**각 지표의 3회 측정 범위가 겹치므로 이 사례만으로 안정적인 전체 성능 개선을 확정하지 않는다.** 특히 candidate generation과 가장 가까운 analysis 시간은 거의 같았다. 결정적으로 확인된 효과는 앞 표의 Oracle 호출·불필요한 materialization 감소이며, 대형 workload의 시간·메모리 개선은 별도 검증이 남는다. 측정 범위와 개별 결과는 [성능 비교 결과](../experiments/rule-directed-generation-20261008/PERFORMANCE_RESULT.md), [기계 판독 결과](../experiments/rule-directed-generation-20261008/weighted-comparison.json)에 보관했다.

검증 결과는 다음과 같다.

- 생산 소스 22개와 테스트 소스 20개를 frozen dependency 집합으로 컴파일했다. 이번 변경 이외의 이전 수정도 함께 포함한다.
- 관련 통합 JUnit **440개 통과**, 기존 ignore 3개. 전체 Maven suite를 실행한 것은 아니다. [명령](../experiments/rule-directed-generation-20261008/tests-command.json), [로그](../experiments/rule-directed-generation-20261008/tests.log).
- Docker harness Python 테스트 **37개 통과**.
- Weighted Docker 전후 총 **6회 통과**. 출력 fingerprint·canonical objective bits·analysis/plan/cost fingerprint가 모두 동일하다. Candidate audit 53개, 대안 58개, 결정 53개도 유지된다.
- 변경 후 Docker에서 correlated source, 함수 호출, 불법 private tuple 거부 **3개 사례 통과**. [회귀 결과](../experiments/rule-directed-generation-20261008/regression-after-summary.json).
- 보호된 ROW 입력의 weighted Docker 사례는 전후 **2회 통과**. 출력·objective·analysis/plan/cost fingerprint와 50개 결정·52개 대안이 유지된다.
- 독립적인 정적 검토에서 높은 우선순위의 정확성 문제를 발견하지 못했다. 실제 통합 검증 결과와 정적 검토를 구분한다.

Source·테스트 hash는 [생산 소스](../experiments/rule-directed-generation-20261008/main-sha256.json), [테스트 소스](../experiments/rule-directed-generation-20261008/tests-sha256.json)에 보관했다.

**복잡도와 남은 한계**

입력 축 수가 `k`, 각 선택지 수가 `dᵢ`, 전체 product가 `P = ∏dᵢ`라고 하자. Rule이 선언한 결정 축 집합을 D라 하면, 해당 경로의 Oracle 평가 수는 전체 P 대신 `Q = ∏(i∈D)dᵢ`에 비례한다. 현재 weighted 규칙의 D는 `{0}`이다. Privacy로 비는 영역과 동일한 mapped 결정값의 재사용은 실제 호출을 더 줄일 수 있다.

그러나 다음 비용은 남는다.

- 허용된 영역 안의 `CandidateRuleKey`, profile, emission fact는 아직 tuple별로 생성한다. 첫 번째 FType 관계가 프로그램 전체를 끝까지 factorized 형태로 통과하는 구조는 아니다.
- 일반적인 공동 조건의 합법성이나 공유 비용은 결정 축 선언만으로 해결되지 않는다. 기존 downstream 관계와 비용 factor가 필요하다.
- 서로 포함되지 않고 여러 축이 다른 rectangle의 합집합은 명시적 경로로 전개될 수 있다. 이번 변경은 임의의 union-of-products 저장소를 도입하지 않았다.
- ShapeHint 의존 rule, RightIndex의 정확한 경계 증거, UDF/function 관계는 기존 경로다. 의미를 보존할 수 있는 별도 계약 없이는 동일 판정으로 묶지 않는다.
- DP가 다루는 공동 제약의 최악 복잡도는 여전히 지수적일 수 있다. 표현 크기와 불필요한 반복을 줄인 것이며 모든 전역 탐색의 복잡도를 선형으로 바꾼 것은 아니다.

**변경 위치와 재현 근거**

- Rule 계약·dispatch·관계: [RulesApi](../src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesApi.java), [RulesCore](../src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesCore.java), [Rulesets](../src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java), [OracleFacade](../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java).
- 생성·압축·계측: [PlacementCandidateGenerator](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java), [PlacementRelationClosure](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java), [FactorizedSupportClauses](../src/main/java/org/apache/sysds/hops/fedplanner/placement/FactorizedSupportClauses.java), [PlacementAnalysis](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java), [SearchSpaceMetrics](../src/main/java/org/apache/sysds/hops/fedplanner/placement/SearchSpaceMetrics.java).
- 비용·선택: [ExactPhysicalModel](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java), [ExactPhysicalCostModel](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java).
- Docker 사례와 검사: [run_joint_boundary_e2e.py](../scripts/fedplanner/run_joint_boundary_e2e.py), [harness 테스트](../scripts/fedplanner/tests/test_run_joint_boundary_e2e.py).
- 명령·hash·테스트 로그: `experiments/rule-directed-generation-20261008/`. 전체 frozen engine/runtime 산출물: `/grid/3/cofee-lm-sweep-mchoi-20260914/rule-directed-generation-20261008/`.

설계 근거는 [구현 계획](FEDPLANNER_RULE_DIRECTED_GENERATION_PLAN_2026-10-08_KO.md), 이전 구현의 범위는 [합법 ID·MRV 결과](FEDPLANNER_LEGAL_IDS_AND_MRV_RESULT_2026-10-08_KO.md)에 기록되어 있다.
