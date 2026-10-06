# CostBased 플래너의 pruning 현황

이 문서는 변경 전 감사 기록이다. 이후 구현과 측정 결과는
[단계별 구현 보고서](PRUNING_IMPLEMENTATION_2026-10-06.md)에 정리했다.

현재 후보 생성에는 privacy domain mask와 재배치 조합의 prefix 검사가 있으며, 이후에는 지원 관계의 고정점 계산과 solver의 domain 축약이 적용된다. 그러나 모든 불법 조합을 Cartesian 생성 전에 제거하지는 않는다. **일반적인 비용 dominance pruning과 incumbent를 이용한 조합별 가지치기는 현재 incremental CostBased DP에 없다.**

점검 기준은 작업 트리의 `3d0d683c1bca099f004edf9105f5387369b4abea`다. Production Java 코드와 기존 테스트는 변경하지 않았다. 기존 단위 테스트를 실행하고, 별도 진단 프로그램에서 현재 reducer와 DP를 호출했다. 상한·하한 기반 가지치기는 정수 비용의 독립 예제로만 확인했다.

## 1 확인된 실행 경로

| 시점 | 실제 수행하는 축약 | 제한 |
|---|---|---|
| 입력 조합 생성 전 | 인증된 보호 payload의 local 입력 상태를 domain에서 제거 | CFG·함수·루프의 미확정 정보를 최종 불법으로 간주하지 않음 |
| FType 조합 열거 | 남은 Cartesian tuple마다 Oracle 호출 | 일반적인 partial-tuple legality 검사는 없음 |
| Oracle 판정 후 | privacy, runtime, shape, recompile, transient 조건에 맞지 않는 emission을 생성 전에 거름 | tuple 열거와 Oracle 호출 비용은 이미 발생 |
| 재배치 입력 조합 생성 | 같은 decision owner에 모순된 realization을 요구하는 prefix 제거 | 서로 다른 owner의 mapping이 다르다는 이유만으로 제거하지 않음 |
| 관계 closure | 사라진 입력·action·reaching-writer support를 연쇄 제거 | 모든 정의와 배치 근거가 모여야 판단할 수 있는 조건 포함 |
| 물리 후보 구성 | 동일 signature 중복 제거, privacy-illegal 이동 및 support-clause 불일치 입력을 곱집합 전에 제외 | 선택들 사이의 나머지 일관성은 hard factor로 표현 |
| DP 전처리 | unary/binary finite support가 없는 값 제거, 모든 관련 표에서 동등한 물리 후보 통합 | factor를 freeze한 뒤 수행; 일반 dominance나 고차 support propagation은 아님 |
| incremental DP | 같은 경계 조합의 내부 최소비용과 복원 선택 유지 | 경계·내부 조합을 열거하며 incumbent에 따른 조합별 cut은 없음 |

근거:

- [PlacementRelationClosure의 privacy mask](https://github.com/min-guk/systemds/blob/3d0d683c1bca099f004edf9105f5387369b4abea/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1563)
- [FType tuple 생성과 Oracle 호출](https://github.com/min-guk/systemds/blob/3d0d683c1bca099f004edf9105f5387369b4abea/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L167), [Cartesian 재귀](https://github.com/min-guk/systemds/blob/3d0d683c1bca099f004edf9105f5387369b4abea/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L715)
- [재배치 prefix 검사](https://github.com/min-guk/systemds/blob/3d0d683c1bca099f004edf9105f5387369b4abea/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L7550)
- [지원 관계 deletion fixed point](https://github.com/min-guk/systemds/blob/3d0d683c1bca099f004edf9105f5387369b4abea/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementSupportRelations.java#L313)
- [물리 후보의 입력 support 사전 검사](https://github.com/min-guk/systemds/blob/3d0d683c1bca099f004edf9105f5387369b4abea/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L927)
- [freeze 이후 support 및 동등성 축약](https://github.com/min-guk/systemds/blob/3d0d683c1bca099f004edf9105f5387369b4abea/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java#L454)
- [incremental 경계 비용표의 전체 조합 최소화](https://github.com/min-guk/systemds/blob/3d0d683c1bca099f004edf9105f5387369b4abea/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L653)

CostBased의 초기 feasible plan을 만드는 regional block에서는 별도의 exact solver 경로도 사용한다. 그 경로에는 bucket별 동등 profile 압축과 finite-support join이 있다. 따라서 저장소의 모든 DP가 항상 dense 조합을 순회한다고 일반화하면 안 된다. 위 마지막 행은 실제 [incremental 단계의 mergeBoundary 호출](https://github.com/min-guk/systemds/blob/3d0d683c1bca099f004edf9105f5387369b4abea/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L178)을 가리킨다.

## 2 불법 조합을 더 앞에서 제거할 수 있는가

**확인된 사실:** 조기 privacy pruning의 on/off 비교 테스트는 유효 후보 snapshot을 보존하면서 작은 same-block 예제의 Oracle 호출과 tuple leaf를 각각 26회에서 24회로 줄였다. 조합 2개와 emission allocation 8개를 피했다. 이는 해당 단위 예제의 작업 횟수이며 일반 workload의 성능 측정이 아니다.

**확인된 한계:** `maskInputDomains`는 masking 후 살아남는 tuple 수가 0이면 masked empty domain 대신 원래 domain을 반환한다. 따라서 이 경로는 모든 금지 조합을 열거 전에 제거하는 형태가 아니다. [해당 조건](https://github.com/min-guk/systemds/blob/3d0d683c1bca099f004edf9105f5387369b4abea/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1588)

**추가 검증이 필요한 부분:** 이 조건을 지우는 것만으로 안전한 개선이 된다고 단정할 수 없다. 임시 bottom 상태의 회복, 후보 certificate의 생성 개수 검증, CFG·루프 closure와의 관계를 확인해야 한다. 특히 초기 seed에 아직 없는 후보를 영구적으로 불법 처리하면 정상적인 loop plan을 잃을 수 있다.

또한 현재 solver는 원래 factor 크기의 자원 한도를 확인하고 표를 freeze한 다음 support 축약을 한다. 축약하면 작아질 모델도 원래 표가 크면 그 전에 거부될 수 있다. 기존 `compactModelChecksRawCapBeforeFreezingLazyFactor` 테스트가 이 순서를 확인한다. 비용이 완전히 확정되기 전의 조기 legality 검사와, 확정된 표에 대한 solver 축약은 구분해야 한다.

## 3 비용이 더 큰 후보의 제거 범위

현재 reducer의 동등성 조건은 **연결된 모든 factor의 값이 남은 상대 조합 전체에서 정확히 같은가**이다. 단순한 같은 배치 검사도 아니고, 한 후보가 항상 더 비싼지를 확인하는 dominance 검사도 아니다.

현재 production reducer를 직접 호출한 진단 결과:

| 입력 모델 | 전처리 결과 | 의미 |
|---|---|---|
| 단일 변수의 비용 `[7,10]` | 후보 2개 유지 | 명백한 비용 dominance도 이 전처리는 제거하지 않음 |
| 단일 변수의 비용 `[0,0,∞]` | 대표 1개 유지 | 지원 없는 값 제거와 완전 동등 후보 통합은 적용 |
| ternary factor에서 x=0의 모든 조합이 ∞ | x 후보 2개 유지 | 이 전처리는 고차 factor의 support를 직접 전파하지 않음 |

마지막 사례는 이후 DP가 불법 조합을 허용한다는 뜻이 아니다. 불가능성이 표시된 표를 유지한 채 이후 최소화에서 처리한다는 뜻이다.

**가능한 좁은 확장:** 특정 변수의 unary 비용을 제외한 모든 관련 표가 두 후보를 같게 취급하고 unary 비용만 다르다면, 더 비싼 후보를 먼저 제거할 수 있다. 이 조건은 같은 출력 배치만 확인하는 것보다 강하며, 모든 외부 실행 가능성과 공유 비용의 영향을 포함해야 한다. 현재는 이 일반적인 검사 없이 동등성 통합과 이후 DP 최소화를 수행한다.

## 4 현재 최선 비용과 하한을 이용한 조합별 cut

**현재 동작:** 상한은 검증된 incumbent의 비용이고, 하한은 비용 소유권이 겹치지 않는 메시지들의 하한 합이다. 두 값은 진행 상황과 목표 gap 종료에 사용된다. `mergeBoundary`에는 incumbent 인자가 없으며 각 내부 조합을 해당 칸의 현재 최소값과 비교한다.

**미구현된 충분조건:** 병합할 메시지 집합을 I, 후보 조합을 a라고 하면 다음 하한을 사용할 수 있다.

```text
L_outside = I 밖의 active 메시지와 sealed 메시지의 하한 합
L(a)      = L_outside + I 안의 메시지들을 a에 고정한 하한 합

L(a) > U 이면 그 조합은 현재 incumbent보다 좋거나 같은 완성을 만들 수 없음
```

여기서 U는 유효한 전체 플랜의 비용이다. strict `>`는 같은 비용의 선택을 보수적으로 보존한다. `>=`로 동점까지 제외하려면 incumbent 보존과 tie 정책을 별도로 정해야 한다. 밖의 하한은 비용항의 소유권을 기준으로 합산해야 하며, 반올림된 전역 하한에서 안쪽 값을 단순히 빼서 만들면 안 된다.

독립 정수 비용 예제에서 이 조건을 실행했다.

```text
f(x,b) = [[1,8],[4,9]]
g(x,b) = [[2,7],[3,10]]
h(b)   = [1,2]
유효한 초기 플랜 (x=1,b=0)의 비용 U = 8
외부 하한 min h = 1
```

| x | b | 전체 비용 | 조건부 하한 | U=8에 대한 처리 |
|---|---|---:|---:|---|
| 0 | 0 | 4 | 4 | 유지 |
| 0 | 1 | 17 | 16 | 제외 |
| 1 | 0 | 8 | 8 | 유지 |
| 1 | 1 | 21 | 20 | 제외 |

4개 중 2개를 제외한 탐색과 완전 열거의 최적값은 모두 4였다. 같은 f와 g를 현재 production `mergeBoundary`에 주면 4개 조합을 모두 평가하고 b별 비용 `[3,15]`를 만든다. 이 진단은 cut의 수학적 가능성을 확인하며, 실제 플래너에 새 pruning이 적용되었거나 성능이 개선되었다는 결과는 아니다.

Production에 적용하려면 다음 조건을 함께 처리해야 한다.

1. 현재 메시지는 모든 경계 조합의 정확한 조건부 최소값을 약속한다. incumbent로 제외한 상태를 실행 불가능 상태와 같은 ∞로만 표시하면 이 의미가 달라진다.
2. 제외한 조합의 하한을 버리면 전체 lower-bound certificate가 잘못 올라갈 수 있다. incumbent와 복원 경로도 유지해야 한다.
3. incremental 경로는 double-double 누적과 rounded-primary 비교를 사용한다. 별도의 dyadic exact kernel에 대한 검증을 이 경로의 새로운 cut 검증으로 대신할 수 없다.
4. 현재는 전체 Cartesian 작업량과 출력 표 크기를 열거 전에 검사한다. 내부 loop에 cut을 추가하는 것만으로 자원 한도에 걸리는 큰 bucket이나 dense 출력 allocation이 해결되지는 않는다.

## 5 테스트 결과와 잔여 확인 사항

기존 단위 테스트 7개 클래스에서 **66건 중 65건 통과, 실패 1건, 오류와 제외 0건**이었다.

| 클래스 | 실행 | 실패 |
|---|---:|---:|
| ExactPhysicalReducedSolverTest | 27 | 0 |
| IncrementalRegionalOptimizerTest | 13 | 0 |
| CandidatePrivacyInputPruningTest | 6 | 0 |
| EarlyPrivacyPruningLegalSpaceParityTest | 4 | 1 |
| RelocationBindingPrefixPruningTest | 3 | 0 |
| PlacementSupportDeletionWorklistTest | 10 | 0 |
| EarlyPrivacyGenerationWorkTest | 3 | 0 |

실패한 테스트는 `unknownShapeKeepsSafeAggregateAndCenteringAlternatives`의 저장된 snapshot hash 비교다. 이 테스트에서 unknown width, mean의 FED/LOUT/ROW 후보, centering의 FED/FOUT/ROW 후보, 새 컴파일 간 재현성 검사는 먼저 통과했다.

추가 진단에서 같은 unknown-width fixture를 현재 코드의 early pruning on/off로 각각 분석했다. 두 상세 snapshot은 모두 `ff0e870b4afd1d7ebb1708ea4b0c21fa99345dd369d91d1b8f5a65d2e68451b7`로 같았다. 따라서 이번 실패는 이 fixture에서 **현재 early pruning을 켠 것과 끈 것의 차이**를 뜻하지 않는다. 기존 golden `2bded4649153d1e1f4542c78d3e5862c19e3fa6fb7c52a00f96fad499050d3d0`와의 차이가 의도된 후보 확장인지 다른 변화인지는 과거 snapshot과의 비교가 더 필요하다. 기준값은 변경하지 않았다.

## 6 재현 자료

- [기본 6개 클래스 테스트 로그](../.omx/pruning-audit-20261006/regressions.log)
- [추가 early on/off 테스트 로그](../.omx/pruning-audit-20261006/early-on-off-tests.log)
- [현재 reducer와 DP를 호출한 진단 소스](../.omx/pruning-audit-20261006/PruningAuditProbe.java)
- [unknown-width early on/off 진단 소스](../.omx/pruning-audit-20261006/UnknownWidthPruningProbe.java)
- [진단 결과](../.omx/pruning-audit-20261006/probe-results.json)

```sh
mvn -B '-Dtest=ExactPhysicalReducedSolverTest,IncrementalRegionalOptimizerTest,CandidatePrivacyInputPruningTest,EarlyPrivacyPruningLegalSpaceParityTest,RelocationBindingPrefixPruningTest,PlacementSupportDeletionWorklistTest' -DtrimStackTrace=false -DforkCount=1 test
mvn -B '-Dtest=EarlyPrivacyGenerationWorkTest' -DtrimStackTrace=false -DforkCount=1 test
```

진단 소스는 기존 Surefire 보고서의 test classpath로 별도 디렉터리에 컴파일해 호출했다. 실제 federated worker나 성능 workload는 실행하지 않았다.
