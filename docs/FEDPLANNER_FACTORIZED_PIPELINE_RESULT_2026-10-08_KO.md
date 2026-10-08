# FedPlanner factorized relation 직접 소비 확대

## 구현 범위

이번 변경은 **조건이 증명된 scalar CP/LOUT 후보를 Oracle부터 최종 선택까지 family로 전달하는 경로**, **Closure의 factor별 support 처리**, **합법 source 인덱스를 직접 소비하는 sparse DP factor**를 연결한다. 모든 FED 연산과 모든 공동 제약을 처음부터 끝까지 압축한 구현은 아니다. 아래의 남은 전개 지점을 완료 범위와 구분해야 한다.

비교 기준은 이전 rule-directed generation까지 적용된 frozen engine이다. Git HEAD와 비교하지 않았다. 설계와 기준선은 [구현 계획](FEDPLANNER_FACTORIZED_PIPELINE_PLAN_2026-10-08_KO.md), 실행 명령·로그·소스 해시는 [검증 산출물](../experiments/factorized-plan-space-20261008/)에 있다.

## 변경 내용

### 1. 정확한 tuple key와 rule family를 분리

`CpRuleFamily`는 입력 위치별 domain과 공통 capability·profile·proof·CP emission을 보관한다. `CandidateRuleKey`의 의미를 바꾸거나 여러 FType를 하나의 대표 key로 덮지 않는다.

- 현재 적용 조건은 scalar `WSLOSS`/`WCEMM`, 해당 Oracle 영역의 native 실행이 CP/LOUT, 확정된 PUBLIC privacy, 보호 payload 입력 없음, 공통 scalar profile과 shape 조건이다.
- 해당 영역에서는 Generator가 tuple별 key/fact를 만들지 않는다. Closure는 최신 owner별 family snapshot을 유지하고, publication에서 최종 privacy와 graph-owned CP state를 확인한다.
- Cost Model과 Local/Exact는 family header를 소비한다. 이 영역의 실행 비용과 입력 공급 방식은 tuple과 무관하다. CP 입력은 기존 NATIVE_LOCAL 비용 경로를 사용한다.
- 최종 선택에서만 정확한 member key/fact/receipt를 복원한다. 특정 다른 합법 member의 명시적 조회·검증도 지원하며, 복사된 owner/key를 authority로 인정하지 않는다.
- Canonical member 선택은 문자열 길이 prefix 순서까지 기존 receipt 순서를 따르는 길이별 DP를 사용한다. Family를 전개해서 정렬하지 않는다.
- Audit도 family당 header 하나와 입력 축·논리 tuple 수를 기록한다.

실제 compiled weighted fixture에서는 Closure 뒤 해당 family가 **논리 크기 1**이었다. 따라서 이 fixture로 대규모 후보 압축 효과를 주장하지 않는다. 넓은 domain을 주는 실제 `buildNode` 비교 테스트에서 비단일 family와 기존 exact 생성 결과의 동등성을 별도로 확인한다.

그 `buildNode` 테스트의 측정값은 다음과 같다. 아래 숫자는 전체 JVM 객체 수가 아니라 해당 연산의 후보 생성 작업량이다. 전수 동등성 비교를 위해 family를 복원하기 **직전**에 측정했다.

| 항목 | 기존 tuple 생성 | 변경 후 |
|---|---:|---:|
| 논리 입력 tuple | 2,401 | 2,401 |
| exact rule fact | 2,401 | 686 |
| 압축 family header | 0 | 5 |
| family로 유지한 tuple | 0 | 1,715 |
| 해당 family의 복원된 member | 해당 없음 | 0 |
| Oracle 호출 | 6 | 6 |

Oracle 호출 수는 이전 rule-directed generation에서 이미 줄어 있었다. 이번 개선은 그 뒤의 1,715개 tuple별 key/fact 생성을 생략하는 것이다. 기존 exact fact 2,401개와 family를 복원한 결과의 key·capability·shape proof·profile·emission을 전수 비교했다.

### 2. Closure의 product 순회 제거

`PlacementSupportRelations`의 초기 projection과 삭제 고정점은 factor option에 reverse dependency를 만든다. Source의 마지막 살아 있는 realization이 제거되면 영향을 받는 option만 삭제한다. 어떤 축이 비었을 때 해당 product가 불가능해진다. 지원되는 순환은 기존 greatest deletion fixed point 의미를 유지한다.

Relocation source inventory는 소비자가 실제로 사용하는 source reference·pool witness·layout-exact metadata를 직접 색인한다. 동일 metadata의 product를 clause별로 읽고 다시 deduplicate하지 않는다. 공통 proof 필터와 publication의 relocation action 검증도 축을 직접 검사한다.

`100×100` 테스트에서는 논리 clause 10,000개를 유지하면서 초기 projection·삭제·publication 검사에 **clause materialization 0개, dependency option 200개**를 사용한다. 부분 축 삭제, 완전 연쇄 삭제, cycle, action 무효화, native witness와 proof 보존을 explicit 결과와 비교한다.

### 3. 합법 인덱스를 DP에 직접 전달

`ExactPhysicalModel`의 realization-support factor는 source handle → source 선택 인덱스의 역색인을 사용한다. 소비자 선택이 허용하는 handle에서 합법적인 row-major cell만 생성한다. 불법 `consumer × source` pair를 먼저 평가하지 않는다.

합법 cell이 전체의 1/4 미만일 때 sparse 표현을 사용한다. 이 기준은 저장 표현만 결정하며 합법 후보를 제거하지 않는다. 조밀한 관계는 기존 정확한 predicate를 사용한다.

`ExactCategoricalSolver.Factor.finiteSupport`는 이 인덱스를 freezing, elimination, boundary projection, Local/Regional, Reduced solver까지 전달한다. 기존 functional-map 전용 실행 경로는 유지한다. 일반 sparse 경로로 바꾸었을 때 발견된 시간 회귀를 제거했다.

## 복잡도와 측정 해석

입력 축 크기를 `d₁,…,dₙ`, product 크기를 `P=∏dᵢ`, 합법 pair 수를 `K`, 소비자/source domain 크기를 `C,S`라 하면 다음과 같다.

| 경로 | 이전 작업 | 이번 경로 |
|---|---|---|
| 지원되는 CP rule 영역 | P개의 key/fact/profile/emission | 공통 header + O(Σdᵢ) 축, 선택 member만 복원 |
| Product support의 초기 유효성 검사 | O(nP) binding 방문 | O(Σdᵢ) option 방문 |
| Product source pool inventory | P개 clause를 읽고 중복 제거 | uniform metadata 1개 |
| Sparse binary support factor | C×S pair 평가·테이블화 | 역색인 + 합법 K개 생성·정렬 |
| 비분리 비용과 일반 joint 관계 | 기존 정확한 계산 | 여전히 기존 계산 또는 fallback |

Sparse cell의 정렬에는 O(K log K)가 들며 source/requirement 역색인 비용이 별도로 있다. 전체 DP 최적화가 선형이라는 뜻은 아니다. 변수 제거의 중간 관계가 커지면 여전히 큰 비용이 든다.

기존 PRIVATE_AGGREGATE 모델의 구조 검사에서는 변수 55개·hard factor 159개·논리 cell 941,012개가 유지되고, 신규 경로에서 factor 5개가 sparse였다. 따라서 새 API를 테스트에서만 호출한 것이 아니라 production 모델 생성에서도 사용한다.

FunctionalMap의 이전 엔진은 이미 전용 압축 실행을 사용했다. 큰 `retainedCells` 수치는 물리 배열 할당량이 아닌 논리 telemetry였다. 이를 바로잡은 수치를 메모리 감소로 보고하지 않는다. 구현 중 수행한 [host JVM 진단](../experiments/factorized-plan-space-20261008/functional-map-benchmark/PRODUCTION_RESULTS.md)은 회귀 원인 파악용으로만 남긴다. 저장소의 Docker 비교 원칙에 따라 이 문서의 시간·메모리 개선 판단에는 아래 `run_LAN_docker.sh` 결과만 사용한다.

## 검증 결과

### 컴파일과 회귀 검사

- 현재 production 소스 27개와 test 소스 27개를 frozen 기준선 위에 컴파일했다. Workspace와 frozen source SHA256 일치를 확인했다.
- 통합 **JUnit 469개 통과**, 기존 ignore 3개. **Python harness 테스트 37개 통과**, Python syntax와 `git diff --check` 통과.
- CP family: 실제 parse/rewrite된 weighted DAG에서 model·cost surface·Local/Exact 종료까지 member 0개, 최종 selection에서만 복원했다. Explicit counterpart와 비용 raw bits·최적값을 비교했다. PRIVATE_AGGREGATE가 CP family로 빠지지 않는 검사와 함수 내부 weighted 경로도 포함한다.
- Support product: 100×100 관계의 projection·삭제·source inventory·publication에서 clause 0개, dependency option 200개. 부분 축 삭제·연쇄 삭제·cycle·action·proof·native witness를 explicit 방식과 비교했다.
- Sparse DP: 작은 dense 전수 결과와 동등성, sparse hole·tie·boundary·reduced 경로를 검증했다. 500³ domain의 합법 row 3개와 20,000² domain의 합법 row 3개를 전체 hard table 없이 처리했다.
- 기존 DIRECT·RELOCATION·shared source·VALUE_MAP·joint·privacy 회귀도 통합 대상에 포함했다. 이것이 모든 가능한 연산/입력의 동등성을 증명한다는 뜻은 아니다.

관련 로그는 [tests.log](../experiments/factorized-plan-space-20261008/tests.log), 재현 명령은 [verify.py](../experiments/factorized-plan-space-20261008/verify.py)에 있다. 전체 Maven suite는 실행하지 않았다.

별도로 `ExactPhysicalRealizationSupportFactorCacheTest`의 기존 hardcoded SHA assertion은 **수정 전 frozen engine에서도 실패**했다. 동일한 원본 테스트를 양쪽 엔진에서 실행해 같은 불일치를 확인했으며, 이 실패를 해결했다고 주장하지 않는다. 이번 변경의 sparse legality를 검사하는 해당 클래스의 semantic test는 통과했다. [기준선 실패 증거](../experiments/factorized-plan-space-20261008/fingerprint-baseline/run-summary.txt)를 보관했다. 따라서 “저장소의 모든 테스트가 통과한다”는 결과가 아니다.

### Docker 실행 및 최종 선택 동등성

지정된 `run_LAN_docker.sh --joint-boundary-e2e`, planner `local`로 아래 5개를 기준선과 변경 후에 각각 실행했고 **양쪽 모두 5/5 통과**했다.

| 사례 | 실행 결과·최적 비용 | 정확한 receipt·source·action·proof |
|---|---|---|
| Weighted + PRIVATE_AGGREGATE 혼합 | 동일 | 동일 |
| PRIVATE_AGGREGATE ROW weighted | 동일 | 동일 |
| 공동 source `A+A` | 동일 | 동일 |
| 여러 함수 호출 | 동일 | 동일 |
| 함수 private 혼합 음성 사례 | 양쪽 모두 의도한 거부 | 성공 receipt를 만들지 않음 |

성공한 네 사례는 출력 fingerprint, objective raw bits, assignment, candidate receipt, relocation, local materialization, shared lifetime이 모두 같고 runtime fallback·repair도 0이었다.

혼합 weighted 사례의 plan/cost fingerprint 문자열만 달랐다. 53개 occurrence 중 `q(wsloss)` 한 개가 `CAPTURED_RULE`에서 `CP_RULE_FAMILY`로 바뀌어 representation signature에 반영됐다. **53개 exact candidate receipt는 모두 동일**하고, 선택된 tuple도 `[FULL, ABSENT_LOCAL, ABSENT_LOCAL, ABSENT_LOCAL]`로 같았다. 다른 세 성공 사례는 plan/cost fingerprint까지 같았다. [자동 비교 결과](../experiments/factorized-plan-space-20261008/runtime-comparison.json)에 원문 선택 evidence를 함께 저장했다.

Docker는 Local 실행 검증이고, Local/Exact 최적값 동등성은 JUnit에서 확인했다. Exact runtime 전체 workload 검증으로 확대해서 해석하지 않는다.

### Docker 시간·메모리 비교

각 사례 전후 1회 측정이다. JIT·GC·호스트 부하 차이가 있으므로 반복 측정에 의한 성능 개선 확정값이 아니다. 같은 Docker harness/config/worker 조건을 사용했다.

| 사례 | compile 전→후 (초) | analysis 전→후 (초) | heap pool 최고값 합 전→후 (MiB) |
|---|---:|---:|---:|
| Weighted 혼합 | 2.287 → 2.136 | 1.132 → 1.019 | 75.63 → 104.55 |
| 보호 ROW weighted | 1.958 → 1.787 | 0.852 → 0.725 | 91.81 → 76.56 |
| 공동 source | 3.510 → 3.133 | 2.399 → 2.231 | 153.14 → 130.38 |
| 함수 호출 | 3.799 → 2.910 | 2.554 → 1.856 | 149.40 → 126.58 |

Heap 값은 각 pool의 high-water 합이며, 같은 시각의 전체 heap peak가 아니다. 5개 사례 전체 컨테이너의 Docker 최고 관측 메모리는 **879,964,979 → 891,184,742 B**였다. 관측 간격은 실제 약 2초로 짧은 spike를 놓칠 수 있다. 따라서 **전체 메모리 감소는 입증하지 못했다.** 시간은 이번 관측에서 감소했지만, 실 workload의 family 크기가 1이므로 이를 대규모 factorization 효과로 귀속하지 않는다.

실제 candidate audit에서 weighted 혼합은 explicit row 53개가 explicit 52개 + family header 1개로 바뀌었고, model alternative는 58개로 동일했다. 나머지 사례의 후보·alternative 수는 동일하다. 공동 source/함수 사례에서는 변경 후에도 analysis가 compile의 약 71%/64%여서 일반 Closure 관계 처리가 계속 주요 병목으로 남는다.

명령·관측값은 [baseline runtime](../experiments/factorized-plan-space-20261008/baseline-runtime-command.json), [after-v2 runtime](../experiments/factorized-plan-space-20261008/after-v2-runtime-command.json), [runtime comparison](../experiments/factorized-plan-space-20261008/runtime-comparison.json)에 있다.

## 주요 구현 파일

| 역할 | 파일 |
|---|---|
| 압축 rule family와 정확한 member 복원 | [CpRuleFamily.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CpRuleFamily.java) |
| Oracle rectangle 소비 | [PlacementCandidateGenerator.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java) |
| Closure metadata·proof·publication | [PlacementRelationClosure.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java) |
| Factor별 support 고정점 | [PlacementSupportRelations.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementSupportRelations.java) |
| Family 소유권·receipt domain | [PlacementAnalysis.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java), [CandidateSelections.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java) |
| Model family와 합법 source 인덱스 | [ExactPhysicalModel.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java) |
| Sparse factor 실행 | [ExactCategoricalSolver.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java), [ExactPhysicalReducedSolver.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java) |
| 압축 evidence의 audit·fingerprint | [PlannerCandidateSpaceAudit.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerCandidateSpaceAudit.java), [PhysicalSemanticDagFingerprint.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/PhysicalSemanticDagFingerprint.java) |

## 남은 전개 지점과 제한

1. **일반 FED rule family는 아직 tuple별 fact로 전개된다.** 동일 Oracle capability여도 입력 FType, DIRECT/RELOCATION, pool과 전송 비용이 달라지므로 CP의 동치류 통합을 그대로 적용할 수 없다.
2. **일반 IndexedSupportClauses와 혼합·상관 support는 일부 model 경로에서 clause별 Alternative를 만든다.** 이번 sparse pair factor는 이 객체 생성 자체를 모두 제거한 것이 아니다.
3. **VALUE_MAP, joint/function boundary, 공유 비용은 기존 정확한 경로가 남는다.** 입력별 단순 비용 합으로 바꾸지 않았다. 특히 worker별 read bytes 합산 뒤 max를 취하는 비용은 일반적으로 분리되지 않는다.
4. **모든 union-of-products가 지원되는 것은 아니다.** 기존 rectangle 병합과 합법 ID fallback 범위를 유지한다. 일부 diagnostic/canonical signature와 clause-sensitive 소비자는 여전히 전개할 수 있다.
5. **범용 sparse factor의 row-major cell은 int 범위다.** 더 큰 논리 domain은 이번 표현으로 처리하지 못한다. 사용자 지정 tie callback도 일부 기존 전개 경로를 사용한다.
6. **실제 weighted 예제에서 큰 CP family 압축은 관측되지 않았다.** 대형 workload의 전체 컴파일 시간·메모리 개선은 별도 증거가 필요하다.

이 제한들은 일반 FED·공동 제약을 압축하는 것이 원리상 불가능하다는 뜻이 아니다. 일반화하려면 정확한 source/rule identity를 유지하는 조건부 factor와 비분리 비용 factor를 만들어 모델의 각 소비 지점에 연결해야 한다. 현재 fallback이 다시 객체를 전개하는 부분까지 제거된 것은 아니다.

따라서 이번 결과를 “FedPlanner 전체 plan space가 이미 완전히 factorized다”라고 해석하면 안 된다. 지원되는 경로의 직접 소비와 sparse solver 연결은 구현·검증했지만, **요청한 일반 FED·상관 relation의 전 구간 압축 목표는 아직 미완료**다.
