# CostBased pruning 단계별 구현과 검증

> 이 문서는 단계별 실험 구현의 기록이다. 후속 ablation과 사용자 결정으로 dominance·추가
> n-ary support·global 구현은 삭제했고 local prefix pruning만 유지한다.
> 현재 결정과 근거는 [최종 비교 보고서](PRUNING_FACTORIAL_2026-10-06.md)를 참조한다.

기준 커밋: `3d0d683c1bca099f004edf9105f5387369b4abea`.
기존 감사 결과는 `PRUNING_AUDIT_2026-10-06.md`에 보존한다.

## 구현 범위

1. CostBased root 전처리에서 외부 관찰이 같은 후보의 내부 비용을 비교한다.
   공유 선택, 이동 비용, def-read 및 loop 제약을 담은 다변수 factor의 관찰은
   모두 유지한다. 더 비싼 초기 seed도 싼 대표로 매핑하고 실제 모델로 재검증한다.
2. 모든 arity의 factor에서 finite support가 없는 값을 DP 전에 제거한다.
   현재 unary/binary 전파와 같은 합법성 기준을 적용하고 고정점까지 전파한다.
3. 경계별 정확한 최소값과 lower-bound certificate를 보존하는 조합 가지치기를
   적용한다. 전역 상한 때문에 정상 경계를 단순히 infeasible로 표시하지 않는다.

각 단계는 회귀 테스트를 먼저 추가하고, 완전 열거와 비교해 최적값·유효성·복원을
확인한다. 후보 수, factor cell 수, 실제 평가 횟수로 감소 여부를 기록한다.
이 작업 횟수는 workload 실행시간이나 Docker 성능 측정으로 표현하지 않는다.
런타임 legality와 중립 후보 생성 규칙은 비용 때문에 축소하지 않는다.

## 결과

**세 단계를 구현하고 검증했다. 실제 DML 예제에서는 전처리의 추가 축약은 없었지만,
DP 내부의 child 비용표 조회가 identity loop에서 82.4%, 회귀 loop에서 65.3% 감소했다.**
두 loop의 최적 비용과 선택의 유효성은 유지됐다. 이는 동일한 병합의 전체 열거가
요구하는 조회 수와 새 kernel에서 실제 수행한 조회 수의 비교다. 실행시간 개선율은 아니다.

| 단계 | 변경 | 독립 작은 모델의 관측 |
|---|---|---|
| 비용 dominance | 외부 factor 관찰이 같은 후보 중 unary 총비용이 가장 싼 대표 선택 | 후보 6→3, factor cell 42→21 |
| 조기 합법성 전파 | 3개 이상 변수를 가진 factor에도 finite support 고정점 적용 | ternary cell 8→4, 2×3×4 모델 cell 24→8 |
| 병합 조합 가지치기 | 불가능한 prefix 중단, 인증된 수치 범위에서 경계별 최선비용보다 나은 결과를 만들 수 없는 prefix 중단 | child 조회 15→7; 별도 infeasible 예제 12→6 |

### 1. 비용 dominance의 경계와 수치 조건

`ExactPhysicalReducedSolver.reducedModel`은 CostBased root에서 다음 조건을 확인한다.

```text
동일한 외부 관찰 = 모든 non-unary factor에서
                  남아 있는 외부 값 조합별 raw 비용/불가능성 관찰이 동일
                  + 동일한 tie cost

대표 = 그 class에서 incident unary 비용의 정확한 binary64 실수 합이 최소인 후보
```

따라서 공유 자식, 이동 비용, FType·mapping 호환성, def-read 및 loop 제약이
외부 factor에서 구분되는 후보는 합치지 않는다. 값이 비싸다는 이유로 중립적인
runtime 후보 생성 규칙을 바꾸지는 않는다. 모든 유효한 원래 선택에 대해 같은 외부
호환성을 가진 더 비싸지 않은 대표가 존재한다. 원래의 비싼 플랜 자체를 모두 남기는
기능은 아니다.

`sourceToReducedValue`가 더 비싼 seed도 대표로 매핑한다. 이후 기존의 frozen /
original / canonical objective 검사를 수행하고 실제 대표를 복원한다. 정확한 unary
합이 동률이면 첫 대표를 유지한다. 전체 비용이 반올림 때문에 동률인 경우에는 이전과
다른, 정확한 실수 비용이 더 작은 플랜을 선택할 수 있다.

**수치 인증은 필수다.** 여섯 unary factor만으로도 정확한 실수 합은 A<B인데 기존
Neumaier 및 DD 비용은 A>B가 되는 반례를 발견했다. 이를 회귀 테스트로 보존했다.
기존 `ExactDyadicCosts.certifyOrderedMaxima`를 재사용하여 다음을 요구한다.

- frozen encoded factor 전체의 canonical 합산 인증.
- physical 모델에서는 원래 surface의 별도 canonical 합산 인증도 필요.
- 인증 실패 시 기존 value-preserving quotient를 유지.

정확한 unary 합은 `BigDecimal(double)`로 비교한다. factor 순서와 비용을 재합산하거나
바꾸지 않고 원래 비용표에서 대표 행만 선택한다. Exact 전용 `prepare`와
`prepareCompacted`는 기존 value-preserving quotient 계약을 유지한다.

### 2. 모든 arity에 대한 support 전파

기존 unary/binary 처리에 더해 n-ary factor의 유한한 tuple을 순회한다. 현재 active
domain 안의 tuple에서 등장하는 값만 지원된다고 표시하고, 지원되지 않은 값을
제거한 뒤 기존 고정점 반복을 계속한다. 전체 플랜에서 유효한 선택은 제거할 수 없다.

이 검사는 factor freeze 이후, DP 이전이다. raw factor 크기 preflight를 앞당겨
통과시키거나 모든 전역 모순을 support 전파만으로 해결하는 기능은 아니다.
무작위 모델 40개에서는 모든 유효한 원래 assignment와 비용이 그대로 복원됨을 확인했다.
순서가 다른 scope와 2×3×4 크기의 domain도 검사했다.

### 3. 병합 내부의 조기 종료

`mergeBoundary`는 입력 순서를 유지하면서 다음 두 경우에 suffix 계산을 생략한다.

1. 현재 비용과 하한이 모두 `+∞`: 남은 항을 더해도 둘 다 `+∞`다.
2. 현재 경계의 finite best가 있고 부분합이 best 이상: 이 조건은 모든 입력의
   residue가 0, lower가 high와 같고, 비음수 비용의 공통 binary lattice에서 최대
   합이 53 bits 안에 들어가는 경우에만 사용한다. 이때 모든 합산이 정확하고
   나머지 비용은 비음수이므로 값·하한·첫 동률 선택을 모두 보존한다.

두 번째 조건은 정수뿐 아니라 인증 가능한 소수 비용에도 적용된다. 잘린 부분합을
output cell에 기록하지 않는다. 일반 DD residue나 넓은 lattice는 기존 열거를 유지한다.
경계별 최소값과 decode를 전수 열거와 비교하는 무작위 모델 40개도 통과했다.

전역 incumbent `U`를 이용해 경계 자체를 제거하는 기능은 적용하지 않았다. 현재
메시지가 모든 경계의 정확한 조건부 최소값과 복원을 제공하므로, 그런 cut에는 별도의
제외 상태 및 lower-bound certificate 표현이 필요하다. 이번 변경은 **같은 경계의 best**를
사용한다. assignment 및 메모리 cap은 이전과 동일하게 열거 전에 검사한다.

## 실제 DML 모델 비교

기존 hermetic DML fixture 방식을 재사용했다. `X`는 8×2, ROW worker 2개,
`PrivateAggregation`이며 rand 입력은 public이다. parse→HOP 구성→placement analysis→
physical cost surface→CostBased incremental DP를 수행했다. `p=p` identity backedge를
보존하도록 fixture에서 HOP rewrite 단계는 호출하지 않았다. worker나 학습 workload는
실행하지 않았다. 기본 비용 설정이며, 이전 대형 WAN-Mid 실험 수치로 해석하지 않는다.

| DML | 기존 전처리 후 후보 값 합 / cell | 새 전처리 후 후보 값 합 / cell | 전체 열거 child 조회 | 실제 child 조회 | 감소 |
|---|---:|---:|---:|---:|---:|
| `B=X+1; print(sum(B)); print(sum(B*B));` | 25 / 134 | 25 / 134 | 0 | 0 | 추가 병합 없음 |
| `p=rand(...); for(i in 1:3){p=p;}` | 100 / 2,052 | 100 / 2,052 | 54,524 | 9,588 | 82.4% |
| `e=X%*%p-y; g=t(X)%*%e/8; p=p-0.1*g;` 반복 | 926 / 57,786 | 926 / 57,786 | 8,852,780 | 3,069,965 | 65.3% |

candidate 값 합은 변수별 domain 크기의 합이며 전체 플랜 개수가 아니다. 기존
전처리는 기준 커밋의 reducer 소스를 별도 이름으로 컴파일하여 비교했다. n-ary
support만 적용한 중간 결과도 같았다. 즉, **이 세 모델에서는 비용 dominance와
n-ary support의 추가 축약 효과가 없었다.**

child 조회는 main merge와 private projection에서만 센다. 초기 leaf 생성과 인증용
표 순회는 포함하지 않는다. 이론적 Cartesian assignments와 retained slots는 바꾸지
않았으며, 따라서 dense 표 메모리나 허용되는 bucket 크기가 줄었다는 결과도 아니다.

identity loop에서 infeasible prefix 3,380개와 cost prefix 21개, 회귀 loop에서 각각
1,068,074개와 59,935개를 조기에 끝냈다. 세 모델의 새 DP는 모두 `EXACT`로 종료했다.
canonical objective는 각각 `4.001651387810708`, `1.0008634477853775`,
`12.003681933283806`으로 비교 경로와 같았다. `ExactPhysicalSelection.create`로
복원된 실제 선택의 합법성도 검증했다.

## 테스트 및 재현

- 변경 전 새 dominance 회귀 테스트: 6건 중 미구현 기능 4건 실패, 보호 조건 2건 통과.
- 1단계 후 관련 61건 모두 통과.
- n-ary support 구현 전 회귀 테스트: 11건 중 새 축약 기대 2건 실패.
- 2·3단계 초기 검증: 83건 모두 통과.
- 최종 범위 확장 검증: **146건 중 145건 통과, 기존 golden 불일치 1건, 오류/제외 0건**.
- production/test 컴파일, `git diff --check`, 독립 correctness review 통과.

기존 실패는 `EarlyPrivacyPruningLegalSpaceParityTest.unknownShapeKeepsSafeAggregateAndCenteringAlternatives`다.
observed hash는 변경 전과 같은 `ff0e870b4afd1d7ebb1708ea4b0c21fa99345dd369d91d1b8f5a65d2e68451b7`이다.
기준 hash를 갱신하거나 테스트를 제외하지 않았다. 과거 golden과의 차이는 여전히
별도 조사 사항이므로 전체 suite가 통과했다고 주장하지 않는다.

```sh
mvn -B '-Dtest=CostBasedPruningTest,BoundaryPruningTest,ExactPhysicalReducedSolverTest,IncrementalBoundaryMessageTest,IncrementalRegionalSeedTest,IncrementalRegionalOptimizerTest,ExactDyadicOrderedResidualTest,ExactDyadicOrderedResidualBoundaryTest,RegionalSearchProblemTest,SharedRegionalPreparationTest,SharedRegionalCompactionParityTest,ExactPhysicalDyadicCertificateTest,FederatedPlanLocalCostPrivacyConstraintTest,LoopEntryCompletePlacementSpaceTest,LoopEntryMaterializationTest,LoopSeedReplayWideningTest,EarlyPrivacyGenerationWorkTest,CandidatePrivacyInputPruningTest,EarlyPrivacyPruningLegalSpaceParityTest' -DtrimStackTrace=false -DforkCount=1 test
python3 .omx/pruning-implementation-20261006/run-probes.py
```

- [최종 테스트 로그](../.omx/pruning-implementation-20261006/regressions.log)
- [DML 비교 진단 소스](../.omx/pruning-implementation-20261006/PhysicalPruningProbe.java)
- [DML 측정 로그](../.omx/pruning-implementation-20261006/physical-probe.log)
- [검증 receipt](../.omx/pruning-implementation-20261006/validation.json)

## 남아 있는 범위

- 기존 검사 66건 중 golden snapshot 불일치 1건은 이번 변경 이전부터 존재한다.
- zero-survivor privacy mask의 단순 guard 삭제는 closure 회복과 축소 증명에
  영향을 줄 수 있어 이번 단계의 안전성 근거로 삼지 않는다.
- 전처리와 merge의 인증·support 순회 비용까지 포함한 wall-clock 성능은 측정하지 않았다.
  실제 실행시간 비교가 필요하면 저장소 지침대로 동일 Docker 조건을 사용해야 한다.
- 모든 DML의 최적성을 새로 증명하거나 모든 가능한 dominance를 구현한 것은 아니다.
  지원 제거와 외부 관찰별 최소 대표라는 충분조건 및 해당 regression 범위를 검증했다.
