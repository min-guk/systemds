# ALS DP 선택 및 FED planner 컴파일 시간·복잡도 분석 보고서

작성일: 2026-08-16
대상 소스: `/home/mchoi/g014-planning-audit-source-20260810-v1`
Docker 결과 스냅샷: `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1`의 성공 warm row 142개

컴파일 시간·복잡도의 전체 workload/planner/worker 표와 audit-mode 한계는 별도 상세 보고서
`docs/FED_PLANNER_COMPILE_COMPLEXITY_REPORT_2026-08-16.md`에 정리했다.

## 1. 요약 결론

1. ALS에서 DP가 CP 계획을 택한 현상을 전부 “DP가 국소 최적화이기 때문”이라고 설명하면 불완전하다.
   - **실제 버그**: DP가 선택된 `CP/LOUT` 자식을 정확한 graph-owned relocation으로 `FOUT`화해 FED 부모가 소비할 수 있는 합법 후보를 열거하지 않았다.
   - 이 누락을 수정해 DP memo에 `FED/LOUT/ROW` ALS arm이 실제로 남도록 했다.
2. 후보 누락을 고친 뒤에도 DP가 최종적으로 CP를 택하는 부분은 이번 ALS occurrence에서는 **개별 HOP 비용 오류도, multi-parent `/2` 산술 오류도 아니다**.
   - owner transpose와 내부 `b(*)`의 occurrence-exclusive 비용은 둘 다 FED가 더 싸다.
   - CP/FED 대안은 동일한 exact `TRead W` plan 객체와 동일한 `TWrite W / 2` 누적비용을 상속한다.
   - 차이를 뒤집는 항목은 선택된 `W=CP/LOUT`에서 FED 소비자로 넘어갈 때 필요한 정확한 `CP→FOUT/ROW` 업로드 1회(`51,216.953`)다.
3. MinST는 같은 전역 assignment 안에서 upstream `W` 자체를 `FED/FOUT/ROW`로 선택한다. DP는 현재 parent-child recurrence가 이미 선택한 upstream 상태를 downstream 이익 때문에 공동 재선택하지 못한다. 이 부분은 **DP의 국소 상태 표현 한계**다.
4. 따라서 이번 ALS 판정은 다음처럼 구분해야 한다.
   - 합법 FED arm 자체가 없었던 것: 버그, 수정 완료.
   - 합법 arm은 있지만 upstream `W` 상태까지 전역 공동 최적화하지 못한 것: 허용된 DP 철학/상태공간 한계.
5. 현재 구현에서 MinST의 직접 planner 시간이 DP보다 짧은 것은 모순이 아니다. 두 알고리즘 모두 현재 구현에서 지수적 요소를 가지며, 실제 시간은 DP의 반복 후보·relocation·coherence 작업과 MinST factor graph의 **실제 induced width/domain 크기**에 좌우된다.
6. WAN-Light 112개 전체 row에서 직접 FedPlanner 평균은 DP `7.690s`, MinST `1.174s`다. `LopsBuild - FedPlanner`는 네 planner 모두 약 `1.36–1.40s`로 비슷해, 현재 compile 차이는 대부분 direct planner 구간에서 발생한다.

## 2. ALS DP 선택의 네 가지 분해

### 2.1 공통 HOP self-cost가 잘못됐는가?

현재 DP와 MinST는 occurrence-local compute의 공통 권위로 `PlacementCostSemantics`를 사용한다.

- DP local: `FederatedPlannerDpCostEstimator.ExactEstimator.computeHopCost` → `PlacementCostSemantics.analysisAwareUnitLocalCost`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java:212-217`
- DP FED: `ExactEstimator.computeFederatedHopCost` → `analysisAwareFederatedComputeCost`
  - 같은 파일 `:220-226`
- MinST local: `MinStExactCostFactsProducer.unitLocalCost`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java:2565-2569`
- MinST FED: `fedCostProjection`
  - 같은 파일 `:2139-2172`

WAN-Light ALS, workers=4의 exact DP plan tree에서 얻은 수치는 다음과 같다.

| occurrence | CP cumulative | CP embedded child | CP exclusive | FED cumulative | FED embedded child | FED exclusive | self-cost 판정 |
|---|---:|---:|---:|---:|---:|---:|---|
| line 130 transpose owner | 40,065.608 | 30,286.689 | **9,778.919** | 85,178.942 | 82,573.253 | **2,605.689** | FED가 더 쌈 |
| line 130 `b(*)` | 30,284.762 | 29,364.327 | **920.435** | 80,952.319 | 80,622.210 | **330.109** | FED가 더 쌈 |

즉 이 occurrence에서 “HOP compute를 0이나 지나치게 작게/크게 재서 DP가 CP를 골랐다”는 가설은 기각된다. 개별 HOP self-cost만 보면 오히려 FED를 선호한다.

### 2.2 multi-parent 누적비용을 단순히 나눈 것이 원인인가?

DP의 일반 누적비용 share는 `totalCost / numParents`다.

- `FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java:846-854`

이번 ALS exact occurrence에서는 다음을 회귀 테스트로 고정했다.

- CP/FED `b(*)` 대안은 **동일한 exact `TRead W` FedPlan 객체**를 상속한다.
- 그 `TRead W`의 직접 parent 수는 1이다.
- 정의 `TWrite W`의 parent 수는 2다.
- `TRead W cumulative = TWrite W cumulative / 2`
  - `56,122.328723 / 2 = 28,061.1643615`
- 이 값은 CP/FED 대안 양쪽에 동일하게 들어간다.

따라서 `/2`가 이 두 arm의 순서를 뒤집을 수 없다. 같은 항이 양쪽에서 상쇄되기 때문이다.

다만 **일반론으로 uniform `/numParents`가 항상 정확하다는 뜻은 아니다**. 부모별 dynamic multiplicity가 다른 DAG에서는 compute 누적비용의 균등 분담이 근사일 수 있다. 네트워크 경계비용은 parent demand와 producer weight가 있을 때 단순 `/N`이 아니라 demand-weighted 공식을 사용한다(`FederatedPlannerDpCostEstimator.java:1254-1303`). 이 잠재 일반 문제는 별도 workload에서 검증해야 하지만, 현재 ALS inversion의 원인은 아니다.

### 2.3 네트워크 비용을 잘못 나누거나 누락했는가?

수정 후 FED `b(*)` arm에는 exact graph-owned relocation receipt가 남는다.

- source value version: lexical variable `W`
- target: `FED/FOUT/ROW`
- physical child boundary: `51,216.952609`
- direct relocation-action cost 합: `51,216.952609`

두 값이 bit-level 산술 경로상 동일함을 테스트가 검증한다. local `b(*)` boundary는 0이다.
또한 이 exact relocation action의 `compatibleConsumers`는 1개다. 따라서 이 arm에서
“여러 parent가 공유할 업로드를 DP가 각각 전액 과금했다”는 설명도 해당하지 않는다.

| 항목 | 값 |
|---|---:|
| CP `b(*)` cumulative | 30,284.761958 |
| FED `b(*)` cumulative | 80,952.319150 |
| 두 arm의 누적비용 차이 | 50,667.557192 |
| exact `W` CP→FOUT/ROW 경계비용 | **51,216.952609** |

한 번의 실제 업로드가 local compute 절감보다 크기 때문에 DP의 local recurrence는 CP를 택한다. 이 비용은 임의 penalty가 아니라 relocation selector가 선택한 exact action의 receipt다.

### 2.4 그렇다면 MinST는 왜 FED를 고르는가?

회귀 테스트에서 같은 immutable `CompiledHopKey`의 `TRead W`에 대해 다음이 확인됐다.

- DP 최종 상태: `CP/LOUT`
- MinST 최종 상태: `FED/FOUT/ROW`

즉 MinST는 “이미 CP인 W를 다시 업로드하는 동일 arm을 더 싸게 계산”한 것이 아니라, 전역 assignment에서 upstream W placement 자체를 바꾼다. 따라서 현재 ALS 차이의 핵심은 다음이다.

- DP: 선택된 child recurrence를 기반으로 parent를 평가하며, downstream 이익 때문에 upstream exact value state를 공동 재선택하지 못한다.
- MinST: W producer/read와 downstream WDivMM owner를 같은 factor assignment에서 공동 선택한다.

MinST는 동일 physical transfer demand를 value version, endpoints, direction, FType, boundary, emission identity로 묶고 active demand의 최대 가격을 한 번만 부과하는 price-once factor도 갖는다.

- `MinStExactCostFactsProducer.addPhysicalCompiledTransferFactors`: `:376-520`
- `activePrice = max(...)`: `:508-514`

그러나 이번 ALS 차이를 오직 “MinST가 transfer reuse를 잘해서”라고 설명하는 것은 부정확하다. 직접적인 차이는 upstream `W` 상태의 전역 재선택이다.

## 3. 발견한 실제 DP 후보공간 버그와 수정

### 3.1 기존 문제

TRead/TWrite 최상위 규칙 때문에 line 130의 exact `TRead W`는 선택된 `TWrite W=CP/LOUT`과 일치해야 했다. 이 자체는 올바르다. 하지만 이후 FED parent를 평가할 때 다음의 합법 경로가 후보에서 사라졌다.

`W CP/LOUT → planner-owned exact CP→FOUT/ROW relocation → FED b(*) → FED/LOUT owner`

기존 raw oracle 분류는 사실상 native `FED/FOUT` child만 PRESENT federated input으로 보았고, 선택된 graph-owned materialized FOUT 또는 LOUT에서 exact relocation으로 도달 가능한 PRESENT row를 별도 후보로 만들지 않았다.

### 3.2 수정

- `PlacementAnalysis.CandidateRuleFacts`에 parent identity별 canonical row index 추가.
- `DpPlacementAdapter.normalizeCandidateInputAlternatives` 추가.
  - literal selected-child row는 그대로 유지한다.
  - immutable candidate-rule domain에 이미 존재하는 row만 추가한다.
  - 바뀐 physical input마다 target FED emission에 대한 exact graph-owned relocation obligation이 있을 때만 추가한다.
  - anchor를 발명하거나 TRead/TWrite 규칙을 완화하지 않는다.
- DP enumerator가 literal row와 materialized row를 각각 평가하도록 확장.
- CP emission은 literal row에서만 생성하고, materialized alternative는 exact relocation을 동반한 FED emission에만 사용한다.
- 같은 closure에서 동일 occurrence/value에 상충하는 두 state가 선택되면 그 **특정 local arm만 전역 합법성 실패로 제외**하고 다른 arm의 열거는 계속한다. 이는 runtime-supported opcode를 닫는 guard가 아니라 동일 immutable occurrence에 두 placement를 동시에 부여할 수 없다는 명시적 전역 합법성 제약이다.

주요 파일:

- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/DpPlacementAdapter.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014AlsPartitionedComputeCostRedTest.java`

## 4. DP와 MinST의 시간복잡도: 왜 실측 순서가 뒤집히는가

### 4.1 “MinST의 Big-O가 더 크다”와 “항상 더 느리다”는 같은 명제가 아니다

현재 DP도 단순한 선형 tree DP가 아니다.

- 각 HOP에서 LOUT/FOUT 가능 input의 bit 조합: 대략 `2^b_h`
- 각 bit row에 materialized candidate row와 emission 대안
- exact relocation action 선택과 closure 검증
- TRead/TWrite, function, loop/recompile occurrence coherence
- component join/conflict resolution 및 fixed-point completion

따라서 개략적인 구현 비용은 `Σ_h 2^b_h × candidateVariants_h × relocation/coherence work_h` 형태이며, 큰 workload에서는 같은 구조를 여러 단계에서 다시 방문한다.

MinST exact solver는 categorical variable elimination을 사용한다. 최악 비용은 HOP 수 자체보다 elimination separator의 domain 곱, 즉 induced width에 지배된다.

- factor/separator cell 계산: `MinStExactCategoricalSolver.java:213-280`
- min-fill, minimum separator cells, minimum assignments, min-degree의 deterministic portfolio: `:283-326`

최악의 큰 induced width에서는 MinST가 급격히 비싸질 수 있다. 그러나 현재 7개 workload의 exact factor graph는 production limit 안에서 separator/domain이 작았고, DP의 반복 구조 작업보다 실제 materialized factor가 작았다. 그래서 실측 MinST가 더 빨랐다.

### 4.2 WAN-Light direct FedPlanner 실측

112개 완성 row(7 workload × 4 planner × workers 1..4)의 평균:

| planner | total compile | HopsRewrite | LopsBuild | **FedPlanner** | LopsBuild−FedPlanner | RuntimeProgram | other compile | runtime |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| DP | 10.148 | 0.481 | 9.090 | **7.690** | 1.400 | 0.108 | 0.468 | 37.257 |
| FedAll | 5.124 | 0.470 | 4.095 | **2.734** | 1.362 | 0.105 | 0.453 | 121.695 |
| Heuristic | 4.185 | 0.474 | 3.139 | **1.748** | 1.392 | 0.105 | 0.465 | 111.440 |
| MinST | 3.604 | 0.476 | 2.566 | **1.174** | 1.392 | 0.099 | 0.463 | 31.791 |

단위는 초다. `FedPlanner`는 `LopsBuild` 안에 포함되므로 둘을 더하면 안 된다.

### 4.3 workload별 구조 크기와 compile 시간

네 planner와 workers 1..4 평균:

| workload | observed HOPs | total compile | FedPlanner | runtime |
|---|---:|---:|---:|---:|
| LM | 114 | 2.831 | 1.037 | 11.740 |
| PCA | 92 | 2.627 | 1.048 | 54.093 |
| KMeans | 323 | 9.073 | 5.564 | 39.003 |
| LogReg | 483 | 11.025 | 7.783 | 34.533 |
| L2SVM | 226 | 4.488 | 2.259 | 38.069 |
| ALS | 189 | 3.387 | 1.433 | 97.529 |
| StepLM | 434 | 6.926 | 4.230 | 253.852 |

HOP 수가 compile 비용의 중요한 설명변수지만 충분조건은 아니다. KMeans는 LogReg보다 HOP 수가 적어도 DP candidate/loop/coherence 구조 때문에 planner 비용이 크며, ALS는 HOP 수가 중간이어도 exact factor/DP frontier가 상대적으로 작다.

### 4.4 DP/MinST direct planner 비율

| workload | DP | MinST | DP / MinST |
|---|---:|---:|---:|
| LM | 1.910 | 0.812 | 2.35× |
| PCA | 1.313 | 0.952 | 1.38× |
| KMeans | 14.534 | 1.185 | 12.27× |
| LogReg | 15.004 | 2.113 | 7.10× |
| L2SVM | 5.343 | 1.250 | 4.27× |
| ALS | 3.240 | 0.729 | 4.45× |
| StepLM | 12.485 | 1.178 | 10.59× |

현재 결과는 “MinST의 이론적 최악 복잡도가 작다”는 뜻이 아니다. 이 구현과 이 workload factor 구조에서 DP의 반복 local enumeration 상수가 더 크다는 뜻이다.

## 5. 컴파일 단계별 계측 경계

`DMLScript`가 기록하는 큰 단계:

1. Parse
2. HopsBuild
3. HopsRewrite
4. LopsBuild
5. LopsRewrite
6. RuntimeProgram

코드: `src/main/java/org/apache/sysds/api/DMLScript.java:479-521`.

FED planner는 `constructLops`의 시작에서 호출되므로 `FedPlanner ⊂ LopsBuild`다.

- 호출: `DMLTranslator.java:627-648`
- direct timer 시작: `DMLTranslator.java:378`
- direct timer 종료: `:395`

`FedPlanner` timer에 포함되는 것:

- planner `rewriteProgram`
- planner trace completion
- final-boundary emission verification

포함되지 않지만 `LopsBuild`에는 포함되는 것:

- physical normalization
- final memory estimate refresh
- FunctionCallGraph 생성
- immutable PlacementAnalysis binding
- planner 이후 registry 등록
- 실제 HOP→LOP lowering

따라서 `LopsBuild - FedPlanner`는 “순수 lowering만”이 아니라 위 전후 작업의 합이다. 현재 네 planner 평균이 거의 같다는 사실은 planner 이후/주변 작업보다 direct planner가 compile 차이의 주된 원인임을 보여주지만, 각 세부 항목을 개별적으로 분리한 timer는 아직 없다.

`other_compile`은 total compile에서 여섯 큰 phase를 뺀 잔차다. 정확한 named phase로 해석하면 안 된다.

## 6. compile이 runtime보다 긴 실제 셀

WAN-Light 완성 row 중 다음 6개에서 compile > runtime이다.

| workload | planner | workers | compile | runtime | compile/runtime |
|---|---|---:|---:|---:|---:|
| L2SVM | DP | 1 | 7.323 | 5.511 | 1.33 |
| L2SVM | DP | 2 | 7.065 | 5.570 | 1.27 |
| L2SVM | DP | 3 | 7.754 | 5.232 | 1.48 |
| L2SVM | DP | 4 | 8.049 | 5.089 | 1.58 |
| LogReg | DP | 4 | 19.819 | 16.749 | 1.18 |
| StepLM | DP | 4 | 17.605 | 17.136 | 1.03 |

작은 dataset의 단일 실행시간과 구조적 compile 탐색을 비교하므로 가능한 결과다. 반복 실행/더 큰 데이터에서는 compile이 amortize될 수 있지만, 현재 실험 계약은 한 번의 fresh coordinator JVM warm phase를 비교하므로 이 overhead를 숨기지 않는다.

## 7. planner 선택이 후속 실행을 바꾸는 방식

planner 결과는 단지 라벨이 아니라 exact placement/emission/relocation receipt다.

- upstream value를 `FOUT`으로 유지하면 downstream FED instruction이 FederationMap을 직접 소비한다.
- `FED/LOUT`이면 worker partial을 coordinator로 모으고, 상위 FED consumer가 필요하면 명시적 LOUT→FOUT relocation이 추가된다.
- `CP/LOUT`이면 local instruction과 local value가 남고, FED 재진입 시 planner-owned upload가 필요하다.
- MinST의 ALS 선택은 `W`를 FED/FOUT으로 유지해 downstream WDivMM FED 경로를 연다.
- DP의 ALS local 선택은 이미 CP인 W에서 51,216.953 업로드를 새로 지불하는 arm을 피한다.

runtime은 이 receipt를 fallback/암묵 보정 없이 실행해야 하며, Docker campaign의 runtime-plan audit `mismatches=0`만 결과로 채택한다.

## 8. 산출물과 재현

### 8.1 per-cell 및 집계 CSV

- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/compile_breakdown_latest.csv`
- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/compile_breakdown_summary_latest.csv`
- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/compile_exceeds_runtime_latest.csv`
- `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/planner_complexity_summary_latest.csv`

생성 스크립트:

```bash
python3 scripts/federated_campaign/compile_breakdown.py \
  /home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1
```

스크립트는 대형 runtime-audit log를 통째로 메모리에 읽지 않고 mmap prefix search를 사용한다.

### 8.2 ALS exact plan tree

- `/tmp/g014-als-dp-plan-tree-20260816.log`

### 8.3 테스트

- `CampaignBG014AlsPartitionedComputeCostRedTest`: 5/5 통과
- `CampaignBG014DpLogRegTransientForwardRedTest`: 2/2 통과
- `CampaignBG014DpKMeansSingleWorkerMixedRefedRedTest`: 1/1 통과
- `CampaignBG014AbsentLocalMaterializationLoweringRedTest`: 1/1 통과
- `MinStExactPhysicalPlanSpaceOracleTest`: 9/9 통과
- `CampaignBHeuristicProvenanceContractTest`: 6/6 통과
- `CampaignBG014PlacementCandidateRuleFactsSliceATest`: 3/3 통과

## 9. 현재 한계와 다음 검증

1. 위 ALS 수정은 현재 dirty source에서 단위/통합 회귀로 검증했다. 진행 중인 336-cell campaign은 이전 immutable stage이므로 새 ALS 수정의 Docker 증거와 섞지 않는다.
2. 진행 중 campaign은 중단/중복 실행하지 않고 WAN-Light → WAN-Mid → LAN 순서로 계속 둔다.
3. 새 source의 Docker canary는 현재 campaign과 자원 충돌 없이 새 clean snapshot/JAR/immutable stage를 만든 뒤 ALS WAN-Light DP부터 수행해야 한다.
4. MinST “global optimum”은 **인코딩된 exact physical objective의 전역 최적**이다. 비용 모델이 실제 runtime을 완벽히 예측한다는 증명은 별도이며, runtime ordering과 plan/runtime audit으로 계속 검증해야 한다.
5. DP의 equal compute-share가 다른 unequal-demand multi-parent DAG에서 왜곡을 만들 가능성은 남아 있다. 이번 ALS에서는 원인이 아님을 증명했지만, 향후에는 parent별 multiplicity가 다른 synthetic regression으로 따로 검증한다.
6. direct `FedPlanner` timer에는 audit trace 생성/출력도 포함된다. 따라서 순수 solver 시간과 audit I/O를 분리하려면 다음 immutable source에 nested timer와 동일 Docker tracing on/off canary가 필요하다.
