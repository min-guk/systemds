# 실제 ML의 Tree 구조, Global의 직접 Exact 경로, Legacy Local 초기 계획 감사

작성일: 2026-10-15. 기준 workspace: `/home/mchoi/cofee-tree-dp-20261014`, `origin/main`의 `b8623e3481072940c07ef3962bcd55be526f1d09` 기반 변경분.

이 문서는 세 질문에 대한 코드 조사와 **새 구조 계측 결과**다. LogReg·GLM·GNMF의 기존 봉인 입력과 Docker 조건에서 진단용 engine을 별도로 만들어 EXACT compile을 재실행했다. 원본 생산 source와 frozen `engine-v2`는 변경하지 않았다. 진단은 ML 학습 프로그램의 planning graph를 관찰하며, 대규모 학습 실행 시간이나 solver 성능 ablation을 새로 측정한 것은 아니다.

## 1. 실제 ML에 Tree가 얼마나 있는가

### 1.1 기존 로그만으로는 알 수 없었던 정보

기존 로그에는 elimination 변수·cell 수와 Tree relaxation의 retained/deferred factor 수가 있었다. 그러나 connected component, tree 영역, separator 최대 크기는 없었다. retained factor 수는 constant/unary 항도 포함하므로 tree 비율로 바꿔 읽을 수 없다.

이번에는 다음을 별도 계측했다.

- HOP input graph: 분석된 occurrence들의 HOP ID를 중복 제거하고 입력 edge만 연결했다.
- Memo constraint/cost graph: 실제 선택지가 둘 이상인 변수를 대상으로, 각 Check·CostFactor scope의 변수들을 모두 연결한 primal graph를 만들었다.
- 정규화 전후 connected component, tree component, 반복 leaf 제거량과 잔여 2-core.
- 실제 exact elimination 순서의 separator 변수 수와 class-aware 출력 table 최대 rows.

여기서 leaf 제거는 차수가 0 또는 1인 변수를 반복 제거하는 연산이다. 독립 tree component와 core에 boundary 하나로 붙은 tree 부분을 포함한다. **모든 가능한 cluster 분해를 세는 알고리즘은 아니다.**

### 1.2 원래 HOP 입력 연결에는 tree 부분이 많다

| HOP input graph 지표 | LogReg | GLM | GNMF |
|---|---:|---:|---:|
| 중복 제거 HOP 수 | 474 | 973 | 82 |
| 전체 연결 성분 수 | 45 | 137 | 9 |
| 전체가 tree인 연결 성분 수 | 32 | 113 | 6 |
| 그 tree 성분에 포함된 HOP 수 | 164 | 459 | 27 |
| 반복 leaf 제거 HOP 수 | 315 | 658 | 46 |
| 반복 leaf 제거 비율 | 66.5% | 67.6% | 56.1% |
| 서로 다른 consumer가 둘 이상인 HOP | 70 | 145 | 17 |

이 그래프는 HOP input edge만 본다. statement 사이의 read/write binding, 함수·loop 경계, shared movement 등 추가 factor를 표현하지 않는다. 같은 HOP의 여러 occurrence도 합쳤으며, 상수·선택지가 없는 연산도 포함한다. 따라서 **이 비율을 Memo 최적화에서 독립적으로 풀 수 있는 비율로 해석하면 안 된다.** 세 사례 모두 input HOP가 분석 occurrence 밖에 있는 경우는 0이었다.

### 1.3 실제 선택 그래프에서는 단순 tree 축약 비중이 작다

다음 표는 원본 cost/check scope를 포함하고 propagation·정확 정규화를 수행한 뒤의 primal graph다.

| Memo 선택 그래프 지표 | LogReg | GLM | GNMF |
|---|---:|---:|---:|
| 전체 모델 변수 슬롯 | 489 | 1,001 | 85 |
| propagation 전 선택지 2개 이상 변수 | 86 | 171 | 18 |
| propagation·정규화 후 선택지 2개 이상 변수 | 80 | 166 | 16 |
| 정규화 후 연결 성분 크기 | 76, 2, 2 | 162, 2, 1, 1 | 16 |
| 독립 tree 성분에 포함된 변수 수 | 4 | 4 | 0 |
| boundary ≤ 1의 반복 leaf 제거 변수 수 | 12 | 21 | 0 |
| 반복 leaf 제거 비율 | 15.0% | 12.7% | 0.0% |
| 남는 2-core 변수 수 | 68 | 145 | 16 |
| 정규화 후 primal edge 수 | 131 | 362 | 23 |

전체 모델 슬롯에는 singleton 등이 포함되므로 `489→80` 전체를 propagation의 효과로 표현하지 않는다. 실제 초기 multi-valued 변수는 86개다. HOP 수와 모델 변수 수도 동일한 단위가 아니다.

이 측정에서 LogReg는 독립 tree 4변수와 core에 붙은 tree 8변수, GLM은 독립 tree 4변수와 붙은 tree 17변수가 단순 leaf 축약 대상이다. GNMF는 이 정의의 leaf 부분이 없다.

**고차 factor의 incidence tree와 primal tree는 다르다.** 하나의 ternary factor만 있어도 incidence graph는 tree일 수 있지만 primal graph에는 triangle이 생긴다. 따라서 위 표는 “boundary 하나로 확실하게 제거되는 단순 tree 비중”이며, 나머지는 어떤 tree/cluster DP로도 풀 수 없다는 뜻이 아니다. factor scope가 추가 분리 가능성을 노출하지 않으면 이 구조 판정은 보수적일 수도 있다.

### 1.4 이 세 사례에서는 작은 separator와 class 압축이 더 중요한 단서다

| 실제 EXACT elimination 지표 | LogReg | GLM | GNMF |
|---|---:|---:|---:|
| 제거 변수 수 | 80 | 166 | 16 |
| 선택된 순서의 최대 separator 변수 수 | 4 | 10 | 3 |
| 최대 class-aware 출력 table rows | 441 | 111,132 | 80 |
| 전체 출력 rows 합계 | 2,378 | 229,309 | 348 |
| 전체 elimination cells | 14,744 | 1,360,977 | 3,308 |
| separator 0 / 1인 bucket 수 | 3 / 15 | 4 / 26 | 1 / 1 |
| 완료 결과 | OPTIMAL | OPTIMAL | OPTIMAL |

이 값들은 **선택한 elimination 순서**의 관측값이지 최소 treewidth의 증명은 아니다. 최대 출력 rows는 class-aware message의 크기이며 모든 factor의 동시 저장량이나 peak heap은 아니다. separator 0/1 bucket에는 제거가 진행된 뒤 작아진 영역도 포함되므로 처음부터 존재한 tree 개수와 다르다.

세 진단 실행의 최적 비용은 frozen baseline과 정확히 일치했다. 기존 baseline의 optimization 시간은 LogReg 약 0.696 s, GLM 약 2.237 s, GNMF 약 0.156 s였다. 진단용 graph 구축·로그가 시간을 바꿀 수 있으므로 **이번 재실행 시간으로 속도 향상이나 회귀를 주장하지 않는다.**

현재 근거에 따른 판단은 다음과 같다.

- 단순 tree 처리는 유용한 fast path일 수 있지만, 이 세 workload의 모든 성능 개선을 설명하는 중심 가설로 삼을 근거는 부족하다.
- GNMF는 위 leaf 영역이 0이어도 최대 80-row message로 exact elimination이 완료됐다. 작은 separator와 비용·합법성 class를 활용하는 일반적인 구조 처리가 중요하다.
- 변수 개수 비율은 시간 절감률이 아니다. tree 우선 처리의 이득을 확정하려면 해당 fast path를 켜고 끈 같은-model ablation이 필요하다.
- 이 결과는 세 봉인 W3 조건에 한정한다. 모든 ML·worker 수·입력 shape의 분포로 일반화하지 않는다.

근거: [구조 요약 JSON](../experiments/memo-tree-dp-20261014/structure-audit-20261015/structure-summary.json), [진단 실행 결과](../experiments/memo-tree-dp-20261014/structure-audit-20261015/results/summary.json), [기존 성능 baseline](../experiments/memo-tree-dp-20261014/results-v2-w3-r2/summary.json).

## 2. Conflict가 없는 영역부터 푸는 것이 항상 좋은가

먼저 “conflict가 없다”는 말의 의미를 구분해야 한다.

| 의미 | 먼저 축약할 수 있는가? |
|---|---|
| 모든 원본 cost/check factor 기준으로 다른 성분과 독립 | 정확히 풀어 상수 비용과 복원 정보로 요약 가능 |
| boundary를 조건으로 독립이며 작은 exact message를 만들 수 있음 | boundary 대안을 보존하면서 안전하게 축약 가능 |
| 현재 후보 계획에서 위반이 관측되지 않음 | 독립성의 증거가 아님; 미래 선택에서 결합이 드러날 수 있음 |

안전한 축약도 항상 가장 빠른 순서는 아니다. 큰 boundary table을 먼저 만들면, 나중의 core propagation이 제거할 상태까지 계산할 수 있다. 어려운 영역을 먼저 처리해 전체 infeasibility를 발견하면 나머지 계산을 생략할 수도 있다. Local에서는 이미 충분히 좋은 영역보다 전역 gap을 제한하는 영역을 먼저 처리하는 편이 유리할 수 있다.

따라서 **저렴한 propagation과 작은 exact 축약은 먼저 수행하되, 비싼 지역 table까지 “충돌이 없으므로 무조건 먼저” 완성하지 않는다.** 관측한 domain/class 수와 separator 비용으로 선택한다. 이 순서의 성능 우위는 아직 ablation으로 확인한 결과가 아니라 설계 권고다.

## 3. Global은 LB/UB 검사 없이 Exact DP를 끝내는 편이 빠를 수 있다

**그럴 수 있으며, 현재 세 ML의 Memo EXACT가 실제로 그 경로를 사용한다.** 앞선 개념 설명에서 Global도 LB/UB를 검사한다고 한 부분은 bound 기반 탐색 경로에 대한 설명으로 한정해야 한다.

| 경로 | 중간 bound의 역할 |
|---|---|
| 직접 exact tree/variable elimination | separator별 정확 최소값을 계산하고 끝에서 해를 복원한다. 별도의 feasible seed 탐색, 반복 relaxation, priority frontier, 중간 gap 검사는 필수가 아니다. |
| B&B·잔여 탐색 | 하한과 feasible incumbent로 불필요한 대안을 배제한다. bound의 계산 비용보다 절약하는 탐색량이 클 때 유리하다. |
| certified Local | 중간 종료하려면 유효 LB/UB가 필요하다. 다만 exact DP가 더 싸면 그 경로로 바로 exact를 반환할 수 있다. |

현 [MemoOptimizer의 elimination solve](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java:514)는 전역 `lowerBound()`나 gap 검사를 반복하지 않고, 완료 후 incumbent를 설정한다. 세 baseline 로그 모두 `nodes == eliminationCells`, `boundPrunes=0`, `conditionalBoundQueries=0`이었다. 단, propagation으로 domain을 줄이고 불법 table 조합을 거르는 처리는 있으므로 “모든 pruning이 없다”는 뜻은 아니다.

또한 **gap 비교 비용과 bound 생성 비용은 다르다.** [Tree 인증 검사](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoTreeDpOptimizer.java:671)의 소수 비교를 제거해도 [forest relaxation과 deferred minimum 계산](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoTreeDpOptimizer.java:326)은 남는다. 별도 ablation 없이 기존 Tree의 지연 원인을 단순 비교 연산 탓으로 돌릴 수 없다.

통합 엔진의 공통화 대상은 후보·합법성·비용 모델과 구조 kernel이다. **모든 모드에 동일한 anytime 실행 루프를 강제하는 것이 아니다.** Global은 직접 exact 경로가 유리하면 그 경로를 선택하고, 검색이 필요할 때 유용한 bound를 사용한다.

## 4. Legacy Local의 initial plan은 실제로 어떻게 만들어지는가

현재 생산 경로는 다음과 같다.

```text
producer-before-consumer 순서의 조건부 greedy 선택
    → 전체 hard constraint 위반 탐지
    → conflict block의 조건부 exact repair, 필요하면 block 확장
    → 전체 hard factor와 원본 cost 검증
    → reduced/encoded model의 auxiliary 값 완성
    → 재검증 후 incremental Regional DP의 초기 UB로 사용
```

### 4.1 Greedy 단계는 이미 값이 정해진 주변만 본다

[LocalPhysicalOptimizer.regionalSeed](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java:184)는 producer-before-consumer 순서로 `LocalCategoricalOptimizer`를 호출한다. 현재 변수에 incident한 factor 중 **다른 모든 변수가 이미 선택된 factor만** 이번 후보 비교에 포함한다. 미래 미선택 변수가 있는 factor는 이 시점의 평가에서 빠진다. [selectLocalState와 closedIncidentFactors](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java:886).

따라서 “정해진 boundary를 보고 국소적으로 선택한다”는 이해는 이 단계에 대해서 맞다. 그러나 모든 미래 boundary 상태별 최적값을 계산하는 exact Tree DP가 아니며, 이 선택만으로 전체 합법성이 보장되지 않는다.

### 4.2 이후 전역 conflict를 수선하고 검증한다

[repairHardConflicts](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java:959)는 위반 factor의 연결 영역과 인접 hard-factor response 영역을 block으로 모은다. 외부 선택을 고정하고 내부를 exact로 풀며, 필요한 auxiliary closure나 boundary 문제 또는 해 없음에 따라 block을 확장한다. 이미 한 greedy 선택도 바뀔 수 있다.

수선 뒤에는 전체 hard factor와 전체 비용을 검증하고, 원본 canonical cost와도 대조한다. [전체 검증](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java:758), [canonical 비용 확인](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java:81).

`IncrementalRegionalSeed`는 최초 원본 선택을 만드는 알고리즘 이름이 아니다. 이미 얻은 원본 선택을 고정하고 reduced/encoded model의 auxiliary를 완성하는 lifting 단계다. [seed lift](/home/mchoi/cofee-tree-dp-20261014/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalSeed.java:95).

이 방식도 초기 plan 생성이 항상 싸다는 보장은 없다. 결합이 강하면 repair block이 커질 수 있다.

## 5. Memo에서도 이 initial-plan 접근을 사용할 수 있는가

**동일한 아이디어를 UB 후보 생성 경로로 적용할 수 있다.** legacy의 encoded model 코드를 그대로 이식하기보다 Memo의 원본 변수·factor와 exact kernel 위에 구현하는 방향이다.

1. 원본 domain propagation과 저렴한 exact region 축약을 수행한다.
2. 이미 선택된 boundary와 닫힌 factor를 보며 greedy 후보를 만든다. boundary별 exact message가 있으면 후보 평가에 활용한다.
3. 전체 Check의 NO/UNKNOWN을 점검한다. 수선 가능한 위반은 주변 block을 조건부 exact로 풀고, 필요하면 외부 선택을 다시 열거나 block을 확장한다.
4. 모든 원본 Check가 YES이고, native와 shared movement를 포함한 전체 비용을 계산한 결과만 UB로 등록한다.
5. 이 UB를 독립적으로 유지한 certified LB와 결합해 Local의 5% 인증에 사용한다.

외부를 고정한 block에 해가 없다는 사실은 전체 문제의 infeasibility 증명이 아니다. 또한 seed를 만드는 동안의 임시 greedy 고정은 전역 proof search의 후보를 삭제하는 근거로 쓰지 않는다. UB 탐색과 전역 LB/후속 탐색의 domain을 분리해야 exact/ε 보장을 유지할 수 있다.

UNKNOWN을 YES처럼 수선 완료로 간주하지 않는다. 좋은 초기 UB를 얻어도 LB가 약하면 5% 인증은 오래 걸릴 수 있다. seed 생성 비용이 direct exact 해결보다 크면 Local도 direct exact를 선택하는 편이 낫다.

## 6. 계측 재현과 검증 범위

진단용 source와 engine 준비는 [prepare_engine.py](../experiments/memo-tree-dp-20261014/structure-audit-20261015/prepare_engine.py), 구조 계산은 [MemoStructureProbe.java](../experiments/memo-tree-dp-20261014/structure-audit-20261015/MemoStructureProbe.java)에 있다. 준비 스크립트는 원본 두 Java 파일이 baseline hash와 일치하는지 확인하고 별도 복사본에 관측 코드를 추가한다. 기존 engine 경로를 덮어쓰지 않는다.

첫 실행용 명령은 다음과 같다. 출력 경로가 이미 있으면 스크립트는 덮어쓰지 않고 종료한다.

```bash
python3 experiments/memo-tree-dp-20261014/structure-audit-20261015/prepare_engine.py
scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark \
  --engine /home/mchoi/cofee-tree-dp-20261014/experiments/memo-tree-dp-20261014/structure-audit-20261015/engine \
  --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z \
  --output-root /home/mchoi/cofee-tree-dp-20261014/experiments/memo-tree-dp-20261014/structure-audit-20261015/results \
  --workloads logreg,glm,gnmf --workers 3 --algorithms EXACT --skip-synthetic
```

동일 image digest, CPU·heap·container 자원, input hash, cost profile은 [실행 manifest](../experiments/memo-tree-dp-20261014/structure-audit-20261015/results/manifest.json)에 기록됐다. 이번 결과는 세 사례 모두 OPTIMAL이며 baseline 비용과 정확히 일치했다. 생산 source 3,963개의 hash도 baseline과 일치했다. 독립 코드 검토에서 probe는 domain/factor를 변경하거나 evaluator를 호출하지 않는 것으로 확인됐다.

아직 측정하지 않은 것은 tree-first ablation, bound 계산을 켜고 끈 같은 검색의 비교, Memo greedy/repair seed의 시간·품질이다. 이 문서의 권고를 해당 성능 개선이 이미 입증된 것으로 해석하지 않는다.
