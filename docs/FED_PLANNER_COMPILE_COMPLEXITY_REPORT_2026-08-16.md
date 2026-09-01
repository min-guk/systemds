# FED planner 컴파일 시간·복잡도 분석 보고서

작성일: 2026-08-16
분석 소스: `/home/mchoi/g014-planning-audit-source-20260810-v1`
Docker 결과: `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1`
분석 기준: `warm-fresh-coordinator-jvm`, latest successful canonical cell
완전 비교 집합: WAN-Light 112셀 = 7 workload × 4 planner × workers 1..4
분석 스냅샷: 총 142/336셀 = WAN-Light 112 + WAN-Mid 30, 실패 없음

## 1. 질문에 대한 짧은 답

1. **“MinST의 최악 시간복잡도가 더 크므로 항상 DP보다 느려야 한다”는 결론은 성립하지 않는다.**
   MinST의 최악 복잡도는 factor graph의 induced width와 domain 곱에 지배되지만, 현재 workload의 실제 separator가 작고 dense array 기반 elimination이 효율적이다. 반대로 현재 DP는 단순 tree DP가 아니라 HOP별 LOUT/FOUT bit row, materialized input 대안, relocation, TRead/TWrite·function·loop coherence, fixed-point closure와 전역 decision-map 처리를 반복한다.
2. WAN-Light에서 direct `FedPlanner` 평균은 DP `7.690s`, MinST `1.174s`다. 이것은 **현재 구현·현재 graph·audit tracing이 켜진 실험 조건의 wall-clock 결과**이지, MinST의 Big-O가 DP보다 작다는 뜻이 아니다.
3. `FedPlanner` 시간은 `LopsBuild` 안에 포함된다. 둘을 더하면 이중 계산이다. `LopsBuild - FedPlanner`는 planner별 약 `1.36–1.40s`로 비슷해, 관측된 planner 간 compile 차이의 대부분은 direct planner timer 안에서 난다.
4. 다만 direct timer에는 planner 계산뿐 아니라 planner trace 생성과 `System.out` 출력도 들어간다. WAN-Light 평균 trace record 수는 DP `49,378`, FedAll `1,854`, Heuristic `1,816`, MinST `1,722`다. 따라서 **현재 수치만으로 순수 알고리즘 계산시간과 audit I/O를 정확히 분리할 수 없다.**
5. compile이 runtime보다 긴 셀은 WAN-Light에서 6개이며, 모두 runtime이 짧은 DP 셀이다. fresh JVM에서 구조적 탐색과 audit 비용을 매번 지불하고, 작은 데이터의 실행은 빠르기 때문에 가능한 결과다.
6. planner 결과는 후속 lowering과 runtime plan을 실제로 바꾼다. 예를 들어 LogReg workers=4에서 runtime FED instruction 수는 FedAll `58`, Heuristic `40`, DP `20`, MinST `12`이고 실행시간은 각각 `45.952s`, `28.380s`, `16.749s`, `12.889s`다. 112개 WAN-Light 셀의 planner/runtime audit mismatch 합은 0이다.

## 2. 측정 계약과 해석 범위

### 2.1 왜 WAN-Light 112셀을 주 분석 대상으로 삼았는가

- WAN-Light는 7 workload × 4 planner × 4 worker가 모두 끝난 완전 factorial 집합이다.
- WAN-Mid는 현재 KMeans 16셀과 PCA 14셀만 끝난 부분 prefix다. 이를 workload 평균에 섞으면 planner/workload 구성비가 달라져 편향된다.
- 모든 결과는 동일 Docker campaign, 동일 dataset/seed, 동일 warm phase의 latest successful canonical cell이다.
- 물리 호스트 `run_LAN.sh` 결과는 사용하지 않았다.

### 2.2 계측 모드의 중요한 제약

이 campaign은 fedplan 전달을 전수 검증하기 위해 planner trace와 runtime-plan audit를 켠 상태다.

- `FederatedPlannerTrace.logGlobal`과 HOP trace는 `System.out.println`을 수행한다.
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerTrace.java:93-146`
- direct `FedPlanner` timer는 `rewriteProgram` 호출 전 시작하고 trace completion, `Planner-Complete`, final-boundary emission verification 뒤 종료한다.
  - `src/main/java/org/apache/sysds/parser/DMLTranslator.java:378-395`
- 따라서 보고서의 `FedPlanner` 값은 다음의 합이다.

`planner 계산 + 객체/문자열 생성 + planner trace 직렬화/출력 + final emission 검증`

이는 현재 논문 실험의 **audit-mode compile latency**로는 정확한 값이다. 하지만 trace-disabled 순수 solver latency와 동일하다고 해석하면 안 된다. 기존 immutable 로그로 두 항을 사후 분리하는 것은 불가능하다.

## 3. 컴파일에서 FedPlanner가 실행되는 정확한 시점

SystemDS의 큰 compile phase는 다음 순서다.

1. Parse
2. HopsBuild
3. HopsRewrite
4. LopsBuild
5. LopsRewrite
6. RuntimeProgram

코드 경계는 `src/main/java/org/apache/sysds/api/DMLScript.java:479-521`이다.

FED planner는 `constructLops`의 시작에서 한 번 호출된다.

- `constructLops` 진입과 planner 호출: `DMLTranslator.java:627-648`
- direct timer: `DMLTranslator.java:378-395`
- 관계: **`FedPlanner ⊂ LopsBuild`**

### 3.1 direct FedPlanner timer 앞, 그러나 LopsBuild 안에 있는 작업

- final-boundary physical normalization
- HOP memory estimate refresh
- planner run-state/registry 초기화
- `FunctionCallGraph` 생성
- immutable `PlacementAnalysis` binding

코드: `DMLTranslator.java:347-377`.

### 3.2 direct FedPlanner timer 안의 작업

- planner별 `rewriteProgram`
- 선택·정규화·projection·emission transaction
- trace completion과 `Planner-Complete` 출력
- final-boundary emission verification

### 3.3 direct timer 뒤, 그러나 LopsBuild 안에 있는 작업

- federated init/rmvar registry 등록
- receipt consumer 전달
- function/program HOP→LOP lowering

따라서 `LopsBuild - FedPlanner`는 순수 HOP→LOP lowering 하나가 아니라 timer 앞뒤 공통 작업을 모두 포함한다.

## 4. 실제 compile phase 분해

WAN-Light 28셀/planner 평균, 단위 초:

| planner | total compile | HopsRewrite | LopsBuild | **FedPlanner** | LopsBuild−FedPlanner | RuntimeProgram | other compile | runtime |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| FedAll | 5.124 | 0.470 | 4.095 | **2.734** | 1.362 | 0.105 | 0.453 | 121.695 |
| Heuristic | 4.185 | 0.474 | 3.139 | **1.748** | 1.392 | 0.105 | 0.465 | 111.440 |
| DP | 10.148 | 0.481 | 9.090 | **7.690** | 1.400 | 0.108 | 0.468 | 37.257 |
| MinST | 3.604 | 0.476 | 2.566 | **1.174** | 1.392 | 0.099 | 0.463 | 31.791 |

Parse와 HopsBuild는 로그 출력 정밀도에서 `0.000s`로 반올림되어 표에서 생략했다. `other compile`은 total compile에서 여섯 named phase를 뺀 잔차이며 하나의 named algorithm으로 해석하지 않는다.

관측:

- 공통 HOP rewrite와 runtime-program 생성은 planner 간 거의 같다.
- `LopsBuild - FedPlanner`도 최대 평균 차이가 약 `0.04s`뿐이다.
- DP와 MinST total compile 차이 `6.544s` 중 direct planner 평균 차이가 `6.516s`다.
- 즉 현재 campaign에서 planner별 compile 차이는 거의 전부 direct timer 안에서 발생한다.

## 5. 네 planner의 실제 계산 구조

### 5.1 공통 전처리

네 planner는 final HOP DAG에서 동일한 `PlacementAnalysis` 권위를 공급받는다. 여기에는 occurrence identity, legal placement state, input/FType constraint, TRead/TWrite와 function/loop 관계, relocation/candidate rule facts가 들어간다. 이 공통 분석 binding은 direct planner timer **앞**에 있으므로 planner별 `FedPlanner` 수치에는 포함되지 않고 `LopsBuild - FedPlanner`에 들어간다.

### 5.2 FedAll: 정책은 단순해도 전체 planner는 단순 1-pass가 아니다

FedAll의 철학은 가능한 합법 HOP을 FED/FOUT으로 선택하는 것이다. HOP별 선호도 자체는 greedy/single-pass로 표현할 수 있다. 그러나 현재 구현은 그 선호도를 그대로 runtime에 던지지 않는다.

1. 공통 graph의 합법 대안과 equality/placement constraint를 유지한다.
2. relocation과 derived-FOUT candidate reachability를 만족하는 total assignment를 찾는다.
3. exact component solver가 Cartesian enumeration 또는 branch-and-bound를 수행한다.
4. canonical relocation/candidate 선택과 exact certificate를 만든다.

코드:

- `ExactPlacementSelector.java:47-104`: exact selector entry
- `ExactPlacementSelector.java:323-345`: exact search와 receipt
- `ExactPlacementSelector.java:392-470`: propagation, MRV, bound, recursive enumeration

그래서 “FedAll 정책”은 선형에 가까워도 “현재 FedAll planner 전체”는 최악에는 decision-group domain product에 지수적이다. LogReg workers=1은 이 차이를 가장 잘 보여준다.

### 5.3 Heuristic

Heuristic은 vector aggregation 뒤 LOUT 등 locality marker로 FedAll 선호를 제한한다. 그러나 marker 적용 뒤에도 total assignment의 합법성, relocation, TRead/TWrite·function/loop coherence와 canonical certificate를 만족해야 한다. 따라서 policy pass만의 복잡도가 전체 compile 시간을 설명하지 않는다.

### 5.4 DP

현재 DP의 per-HOP 핵심은 두 출력 상태를 모두 가진 child 수를 `b_h`라 할 때 `2^b_h` bit row를 열거하는 것이다.

- bit enumeration: `FederatedPlannerDpCostEnumerator.java:1017-1125`
- program rewire와 memo seed/closure/final observed pass: 같은 파일 `:429-523`
- loop-carried TRead/TWrite와 shared function input 때문에 fixed-point closure 수행: `:534-572`

구현 비용의 개략식은 다음과 같다.

`T_DP ≈ Σ_pass Σ_h 2^(b_h) × candidateVariants_h × (cost + oracle + relocation + coherence) + global decision/conflict work`

고전적인 tree DP의 `O(H × small_state)`보다 비싼 이유:

- HOP DAG가 tree가 아니며 multi-parent와 transient/function edge가 있다.
- literal child row 외 materialized/relocated row가 있다.
- 같은 value version의 TRead/TWrite와 function/loop occurrence를 일관되게 닫아야 한다.
- exact frontier를 안정화한 뒤 다시 observed pass를 수행한다.
- 최종 output decision map과 conflict/component closure가 별도로 존재한다.
- audit 모드에서 후보와 결정 trace를 대량 출력한다.

### 5.5 MinST

MinST는 categorical factor graph를 만들고 exact variable elimination을 수행한다. variable `x`를 제거할 때 separator `S_x`와 domain 크기를 `d_i`라 하면 해당 step의 assignment 수는 대략 다음이다.

`d_x × Π_(i in S_x) d_i`

최대 domain을 `d`, induced width를 `w`라고 단순화하면 전형적인 최악 항은 `O(V × d^(w+1))`다. 따라서 width가 커지면 MinST가 급격히 비싸질 수 있다.

현재 구현은 min-fill, minimum separator cells, minimum assignments, min-degree 네 deterministic order를 symbolic하게 평가한 뒤 실제 dense-factor footprint가 가장 작은 exact order를 택한다.

- factor cell/assignment 계측: `MinStExactCategoricalSolver.java:213-280`
- order portfolio: 같은 파일 `:283-326`

현재 workload에서는 실제 width/domain product가 tractable 범위였고, numeric dense loops가 DP의 반복 Java graph/object/trace 작업보다 효율적이었다.

## 6. workload × planner × worker 직접 시간

각 셀의 `FedPlanner` wall-clock을 workers=1/2/3/4 순서로 표시했다. `total compile mean`과 `runtime mean`은 같은 네 worker 평균이다. 단위 초.

| workload | planner | FedPlanner w1/w2/w3/w4 | total compile mean | runtime mean |
|---|---|---:|---:|---:|
| LM | FedAll | 1.116 / 0.784 / 0.702 / 0.731 | 2.612 | 9.503 |
| LM | Heuristic | 0.583 / 0.608 / 0.608 / 0.567 | 2.374 | 27.063 |
| LM | DP | 1.913 / 1.759 / 1.884 / 2.084 | 3.726 | 5.294 |
| LM | MinST | 1.120 / 0.789 / 0.608 / 0.731 | 2.611 | 5.099 |
| PCA | FedAll | 1.024 / 0.875 / 0.995 / 0.837 | 2.527 | 54.802 |
| PCA | Heuristic | 1.022 / 1.075 / 0.849 / 1.028 | 2.599 | 54.397 |
| PCA | DP | 1.154 / 1.601 / 1.168 / 1.331 | 2.895 | 54.075 |
| PCA | MinST | 1.105 / 0.861 / 0.918 / 0.923 | 2.486 | 53.098 |
| KMeans | FedAll | 2.729 / 3.198 / 2.814 / 2.978 | 6.242 | 39.755 |
| KMeans | Heuristic | 3.400 / 3.410 / 3.608 / 4.017 | 7.294 | 47.956 |
| KMeans | DP | 7.567 / 17.937 / 14.655 / 17.977 | 18.063 | 35.433 |
| KMeans | MinST | 1.313 / 1.103 / 1.159 / 1.163 | 4.691 | 32.870 |
| LogReg | FedAll | 34.456 / 2.775 / 2.753 / 2.804 | 13.911 | 59.850 |
| LogReg | Heuristic | 3.250 / 3.213 / 3.122 / 3.692 | 6.536 | 44.372 |
| LogReg | DP | 16.563 / 12.561 / 14.297 / 16.596 | 18.208 | 19.198 |
| LogReg | MinST | 2.379 / 1.994 / 1.967 / 2.112 | 5.446 | 14.711 |
| L2SVM | FedAll | 1.891 / 1.106 / 1.149 / 1.309 | 3.612 | 113.069 |
| L2SVM | Heuristic | 1.029 / 1.041 / 1.110 / 1.137 | 3.325 | 29.023 |
| L2SVM | DP | 5.184 / 4.856 / 5.518 / 5.815 | 7.548 | 5.351 |
| L2SVM | MinST | 1.053 / 1.374 / 1.258 / 1.315 | 3.468 | 4.836 |
| ALS | FedAll | 0.454 / 0.883 / 0.900 / 1.125 | 2.799 | 98.058 |
| ALS | Heuristic | 0.639 / 0.889 / 1.219 / 0.943 | 2.826 | 99.594 |
| ALS | DP | 2.838 / 3.205 / 3.422 / 3.496 | 5.213 | 110.674 |
| ALS | MinST | 0.691 / 0.751 / 0.713 / 0.761 | 2.708 | 81.791 |
| StepLM | FedAll | 1.520 / 1.395 / 1.555 / 1.689 | 4.163 | 476.830 |
| StepLM | Heuristic | 2.021 / 1.603 / 1.670 / 1.579 | 4.339 | 477.676 |
| StepLM | DP | 9.419 / 12.524 / 13.399 / 14.600 | 15.382 | 30.774 |
| StepLM | MinST | 1.193 / 1.137 / 1.137 / 1.247 | 3.820 | 30.129 |

worker 수에 따른 compile time은 단조일 필요가 없다. HOP 수는 거의 그대로지만 worker count가 cost, FType/layout, legal/competitive domain과 branch-and-bound pruning 순서를 바꾼다. 특히 LogReg FedAll workers=1의 `34.456s`는 worker=2..4의 약 `2.8s`와 다른 search geometry를 가진 outlier다.

각 개별 셀의 Parse/HopsBuild/HopsRewrite/LopsBuild/FedPlanner/LopsRewrite/RuntimeProgram/잔차는 `compile_breakdown_latest.csv`에 모두 기록했다.

## 7. 구조량으로 본 DP와 MinST

workers 1..4 평균:

| workload | HOPs | DP sec | DP candidates | DP output entries | DP trace | MinST sec | variables | induced width | factor cells | elimination assignments |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| LM | 114 | 1.910 | 174 | 4,096 | 16,396 | 0.812 | 122 | 4.2 | 39,139 | 77,998 |
| PCA | 92 | 1.313 | 442 | 1,036 | 6,051 | 0.952 | 103 | 5.0 | 182,482 | 259,658 |
| KMeans | 323 | 14.534 | 907 | 4,096 | 42,782 | 1.185 | 333 | 7.0 | 139,199 | 496,639 |
| LogReg | 483 | 15.004 | 3,828 | 4,096 | 87,926 | 2.113 | 495 | 10.5 | 433,499 | 4,422,634 |
| L2SVM | 226 | 5.343 | 1,689 | 4,096 | 68,556 | 1.250 | 236 | 9.0 | 370,983 | 2,424,519 |
| ALS | 189 | 3.240 | 1,338 | 4,096 | 44,895 | 0.729 | 200 | 9.2 | 4,241 | 1,946 |
| StepLM | 434 | 12.485 | 2,431 | 4,096 | 79,039 | 1.178 | 490 | 8.0 | 38,944 | 135,488 |

해석:

- DP의 `4,096` output entry는 많은 workload에서 bounded trace/decision stage가 cap까지 찼다는 관측값이다. 실제 총 내부 연산량이 정확히 4,096이라는 뜻이 아니다.
- LogReg는 DP 후보·trace가 가장 크고, MinST도 elimination assignment가 `4.42M`으로 가장 크다. 두 planner 모두 이 workload가 구조적으로 무겁다고 동의한다.
- ALS는 induced width 평균이 `9.2`여도 materialized factor cell이 `4,241`, elimination assignment가 `1,946`뿐이다. width 하나만으로 wall-clock을 예측할 수 없는 사례다.
- KMeans/StepLM은 DP HOP 순회와 closure/decision trace가 크지만 MinST factor footprint는 작다. 이것이 가장 큰 DP/MinST 역전을 만든다.

7개 workload 평균에 대한 Pearson 상관은 표본이 7개뿐인 설명적 통계다. 인과관계로 해석하지 않는다.

| planner time | 구조 지표 | Pearson r |
|---|---|---:|
| DP | HOP count | 0.934 |
| DP | candidate evaluations | 0.696 |
| DP | trace count | 0.725 |
| MinST | materialized factor cells | 0.813 |
| MinST | elimination assignments | **0.916** |
| FedAll | exact-search prefixes | **0.979** |
| Heuristic | exact-search prefixes | 0.764 |

이 결과는 각 planner에서 wall-clock을 설명하는 실제 scale 변수가 다름을 뒷받침한다.

## 8. FedAll/Heuristic이 예상보다 오래 걸리는 이유

workers 1..4 평균 exact-selector 구조량:

| workload | FedAll sec | calls | sum prefixes | max prefixes | Heuristic sec | calls | sum prefixes | max prefixes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| LM | 0.833 | 62.0 | 500.5 | 439.5 | 0.591 | 1.0 | 0.0 | 0.0 |
| PCA | 0.933 | 49.0 | 104.8 | 56.8 | 0.994 | 49.0 | 104.8 | 56.8 |
| KMeans | 2.930 | 157.8 | 263.8 | 107.0 | 3.609 | 157.8 | 259.2 | 102.5 |
| LogReg | 10.697 | 273.0 | **97,078.5** | **96,806.5** | 3.319 | 273.0 | 382.8 | 110.8 |
| L2SVM | 1.364 | 124.0 | 449.8 | 326.8 | 1.079 | 124.0 | 154.0 | 31.0 |
| ALS | 0.840 | 67.0 | 96.8 | 30.8 | 0.922 | 67.0 | 96.8 | 30.8 |
| StepLM | 1.540 | 230.0 | 497.5 | 268.5 | 1.718 | 230.0 | 364.8 | 135.8 |

LogReg FedAll 평균은 workers=1 outlier의 영향을 크게 받는다. workers=1에서 `34.456s`, workers=2..4에서 `2.75–2.80s`다. 평균 exact prefix가 약 `97k`인 것도 같은 cell 때문이다. 즉 FedAll의 오래 걸림은 “모든 HOP을 FED로 표시하는 policy pass” 때문이 아니라 그 결과가 모든 전역 합법성·candidate reachability를 만족한다는 exact certificate/projection을 구하는 과정에서 발생한다.

## 9. workload별로 왜 오래 걸리는가

### LM — 114 HOPs

- graph가 작고 DP 후보가 174개로 작다.
- direct planner는 DP `1.910s`, MinST `0.812s`다.
- compile보다 runtime 차이가 중요하며, cost-based plan이 heuristic보다 훨씬 적은 runtime FED instruction을 선택한다.

### PCA — 92 HOPs

- 가장 작은 HOP graph다.
- 네 planner direct time이 약 `0.93–1.31s` 범위이며 total compile과 runtime도 planner별로 거의 같다.
- planner 차이가 작은 것이 합리적인 구조다.

### KMeans — 323 HOPs

- 반복문/transient state가 있어 DP의 seed→frontier closure→observed pass가 비싸다.
- DP direct `14.534s`, trace `42,782`; MinST factor cell `139k`, elimination assignment `497k`, direct `1.185s`다.
- DP workers=2..4가 workers=1보다 느린 것은 worker 수 자체의 선형 항이 아니라 competitive state와 closure/search 경로 변화다.

### LogReg — 483 HOPs

- 가장 큰 HOP graph이고 DP 후보 `3,828`, trace `87,926`으로 가장 크다.
- MinST도 가장 어려운 현재 instance로 elimination assignment `4.42M`, direct `2.113s`다.
- FedAll workers=1은 exact branch-and-bound prefix 폭증으로 `34.456s`다. 이 outlier 때문에 FedAll 평균 compile이 Heuristic보다 커진다.

### L2SVM — 226 HOPs

- DP 후보 `1,689`, trace `68,556`, output entry `4,096`이다.
- cost-based runtime 자체는 약 `5s`로 매우 짧아 DP compile `7–8s`가 runtime보다 길다.
- “compile이 비정상적으로 길다”기보다 작은 실행을 fresh JVM audit compile과 1회 비교한 결과다. 그래도 production latency 관점에서는 개선 대상이다.

### ALS — 189 HOPs

- MinST induced width만 보면 9 이상이지만 실제 factor materialization은 매우 작다.
- 따라서 MinST `0.729s`가 빠르다.
- DP는 multi-parent/transient placement와 output closure를 다루며 `3.240s`다.
- runtime 차이는 planner가 upstream `W`를 FOUT으로 유지할지, CP/LOUT으로 materialize할지에 의해 크게 달라진다.

### StepLM — 434 HOPs

- 큰 function/control graph 때문에 DP trace `79,039`, direct `12.485s`다.
- MinST factor cell은 `38,944`, elimination assignment `135,488`로 작아 `1.178s`다.
- runtime은 FedAll/Heuristic 약 `477s`, DP/MinST 약 `30s`로 planner 선택의 후속 효과가 compile 차이보다 훨씬 크다.

## 10. compile이 runtime보다 긴 셀

WAN-Light에서 6개:

| workload | planner | workers | compile | runtime | compile/runtime |
|---|---|---:|---:|---:|---:|
| L2SVM | DP | 1 | 7.323 | 5.511 | 1.33× |
| L2SVM | DP | 2 | 7.065 | 5.570 | 1.27× |
| L2SVM | DP | 3 | 7.754 | 5.232 | 1.48× |
| L2SVM | DP | 4 | 8.049 | 5.089 | 1.58× |
| LogReg | DP | 4 | 19.819 | 16.749 | 1.18× |
| StepLM | DP | 4 | 17.605 | 17.136 | 1.03× |

이 현상의 구성:

1. fresh coordinator JVM이므로 compile cache/amortization이 없다.
2. dataset은 고정되어 cost model 숫자와 runtime work는 작아질 수 있지만 planner는 전체 program structure를 탐색한다.
3. DP는 실행 데이터량보다 HOP/state/coherence 규모에 지배된다.
4. audit trace 출력이 direct timer에 포함된다.
5. L2SVM의 cost-based runtime plan은 매우 빨라 denominator가 작다.

따라서 이 6개는 측정 오류라고 단정할 수 없다. 동시에 interactive/one-shot 실행에서는 실제 사용자 latency이므로 최적화 대상이 맞다.

## 11. planner가 후속 compile/runtime을 바꾸는 구체적 방식

planner 결과는 각 compiled occurrence의 `(ExecType, output placement, FType)`와 relocation/local materialization receipt다.

- `FED/FOUT`: FederationMap을 유지해 상위 FED consumer가 직접 사용한다.
- `FED/LOUT`: worker partial/result를 coordinator로 내린다.
- `CP/LOUT`: coordinator local instruction/value가 된다.
- 상위 FED consumer가 다시 필요하면 planner가 증명한 CP→FOUT 또는 FED→LOUT→FOUT relocation이 명시적으로 삽입된다.

### 11.1 LogReg workers=4 exact 예

| planner | compile | FedPlanner | selected FED | selected FOUT | reloc | local mat | runtime FED inst | runtime |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| FedAll | 6.333 | 2.804 | 80 | 75 | 0 | 0 | 58 | 45.952 |
| Heuristic | 6.808 | 3.692 | 68 | 60 | 1 | 3 | 40 | 28.380 |
| DP | 19.819 | 16.596 | 37 | 31 | 0 | 5 | 20 | 16.749 |
| MinST | 5.471 | 2.112 | 24 | 17 | 0 | 1 | 12 | 12.889 |

FedAll이 “더 많은 FED/FOUT”을 선택하는 것은 철학과 일치한다. 하지만 FED instruction 수가 많다고 항상 빠른 것은 아니다. 불필요한 worker RPC/동기화와 큰 intermediate FOUT 유지가 늘 수 있기 때문이다. MinST는 비용상 필요한 FED boundary만 남겨 더 적은 FED instruction과 더 짧은 runtime을 만든다.

### 11.2 StepLM workers=4 exact 예

| planner | selected FED | selected FOUT | reloc | runtime FED inst | runtime |
|---|---:|---:|---:|---:|---:|
| FedAll | 50 | 46 | 6 | 31 | 461.299 |
| Heuristic | 50 | 45 | 6 | 29 | 462.935 |
| DP | 22 | 21 | 0 | 4 | 17.136 |
| MinST | 12 | 11 | 2 | 6 | 13.812 |

이 workload에서는 compile `1–15s` 차이보다 잘못된 과도한 federation으로 인한 runtime 약 `430s` 차이가 훨씬 크다.

### 11.3 planner 결과가 runtime에 실제 전달되었는가

- WAN-Light 112셀 모두 planning receipt가 존재한다.
- canonical cell 중복은 0이다.
- runtime-plan audit mismatch 합은 0이다.
- 따라서 위 실행 차이는 planner label만 바뀐 것이 아니라 emitted physical plan이 runtime에 전달된 결과다.

단, audit mismatch 0은 “계획대로 실행했다”는 증거이지 “cost model이 실제 시간을 완벽히 예측했다”는 증명은 아니다.

## 12. 현재 계측으로 알 수 없는 것

현재 timer는 direct `FedPlanner` 하나뿐이므로 다음 wall-clock subphase를 정확히 분해할 수 없다.

- common analysis binding
- planner policy/domain 생성
- DP enumeration / frontier closure / output-decision conflict resolution
- MinST factor production / order selection / factor materialization / elimination / projection
- FedAll/Heuristic exact search / candidate projection
- emission transaction
- trace formatting과 stdout I/O
- final emission verification

이번 보고서는 이를 숨기지 않고 다음 두 종류로 분리했다.

- **실측 시간**: existing named timers
- **구조량 proxy**: candidates, trace count, prefix count, factor cells, elimination assignments

정확한 내부 시간 비율이 필요하면 다음 campaign source에 nested monotonic timer를 추가해야 한다. 특히 `trace_compute_ns`와 `trace_io_ns`를 solver time에서 분리하고, tracing on/off paired canary를 동일 Docker 조건에서 수행해야 한다. 현재 실행 중 immutable campaign에 사후 적용하거나 기존 셀을 중복 재실행하지 않는다.

## 13. 산출물과 재현

### 13.1 분석 CSV

- per-cell phase + structural metrics
  `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/compile_breakdown_latest.csv`
- workload/planner phase summary
  `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/compile_breakdown_summary_latest.csv`
- planner structural complexity summary
  `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/planner_complexity_summary_latest.csv`
- compile > runtime cells
  `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1/analysis/compile_exceeds_runtime_latest.csv`

### 13.2 생성 명령

```bash
python3 scripts/federated_campaign/compile_breakdown.py \
  /home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1
```

### 13.3 최신 검증

- parser 성공: 142 latest successful rows
- profile split: `wan_light=112`, `wan_mid=30`
- planning receipt 누락: 0
- duplicate canonical cell: 0
- audit mismatch 합: 0
- `LopsBuild - FedPlanner < 0`: 0
- campaign progress at extraction: `142/336`, `failed=false`

## 14. 최종 판단

현재 관측된 `DP > MinST` compile ordering은 단순 계측 착오가 아니다. 실제 DP 구조량이 크고 MinST factor instance가 tractable하다는 로그 증거가 있다. 그러나 audit trace I/O가 timer 안에 있고 DP가 훨씬 많은 trace를 발생시키므로, **순수 algorithm-only wall-clock도 같은 비율이라고 주장해서는 안 된다.**

논문에서 안전하게 쓸 수 있는 표현은 다음이다.

> MinST는 최악의 경우 induced width에 지수적이지만, 평가 workload의 실제 factor graph는 낮은 effective separator footprint를 보였다. 반면 DP 구현은 control-flow/transient coherence와 materialized placement 대안을 닫기 위한 반복 enumeration을 수행했다. Audit-enabled end-to-end compilation에서는 결과적으로 DP의 planner latency가 MinST보다 컸다.

그리고 compile latency와 runtime quality는 별도 축으로 보고해야 한다. StepLM처럼 `10s`가량 더 컴파일해도 runtime을 수백 초 줄일 수 있고, L2SVM처럼 runtime이 매우 짧으면 compile overhead가 실행시간보다 커질 수 있다.
