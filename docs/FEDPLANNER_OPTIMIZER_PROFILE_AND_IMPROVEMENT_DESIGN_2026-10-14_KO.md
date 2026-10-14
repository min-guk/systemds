# FedPlanner Memo Optimizer 실측 병목과 정확성 보존 개선 설계

- 분석일: 2026-10-14, 독립 workspace `fedplanner-oracle-cost-20261013`
- 현재 코드: `25662ee7b485af0880a11c9b219620e155cfbee7`
- 과거 baseline: `abb5a4822ab78cfd962073bba794da23fdcd6be6`
- 범위: **production 소스·테스트·실행 harness·컴파일된 엔진을 수정하지 않은 분석**. 이번 문서의 개선안은 아직 구현하지 않았다.
- 정책: baseline LogReg 1회, 현재 LogReg 1회, 현재 GLM 1회. 성능 반복 실행이나 평균 계산은 하지 않았다.

## 1. 확인된 결론

**LogReg 회귀의 가장 강한 근거는 movement cost evaluator 내부의 재분석이다. GLM timeout도 같은 경로에 더해 input legality의 contextual shape 분석이 대부분을 차지한다.** Solver가 필요할 때 비용을 묻는 구조는 유지할 수 있다. 문제는 질문마다 고정된 guard/phase/shape 증명까지 다시 수행한다는 점이다.

| 우선순위 | 병목 | 직접 관측 | 판정 |
|---|---|---|---|
| 1 | movement cost 내부 binding/shape/반복 루프 증명 | 현재 LogReg optimizer 표본의 51.59%, GLM 68.77%가 `movementGroupCost` 아래 | 가장 큰 공통 개선 대상. 단순 lookup 개선부터 시작하고, 증명된 불변 결과를 재사용한다. |
| 2 | guard/phase lookup과 key 준비 | `guardValue`가 현재 LogReg 8.95%, GLM 34.58%; GLM `PhaseContextKey` 생성 경로 22.32% | 프로그램 구조가 고정되어도 stream 검색·frame/key 생성·guard 관련성 검사를 반복한다. 1의 내부 비용 및 legality 비용과 겹친다. |
| 3 | context별 input legality의 shape 분석 | 현재 LogReg 5.67%, GLM 28.74% | legality 자체를 생략하지 않고, 그 입력을 만드는 activation/shape 증명을 재사용한다. GLM에서는 필수 개선 대상이다. |
| 4 | solver의 domain 순회와 cache hit 경로 할당 | LogReg truth hit 약 8,205만, support miss 약 3,829만, conditional-bound query 약 1,114만 | baseline부터 존재하는 큰 공통 비용. 현재 회귀 전부를 이 경로 탓으로 돌릴 수는 없다. |
| 낮음 | unary cost 정규화/BigDecimal 합산 | 정규화 관측 구간: LogReg 0.116초, GLM 1.110초 | 불필요한 평가가 있으나 이번 GLM 300초 timeout의 주원인은 아니다. |
| 낮음 | GC 정지·privacy predicate 자체·entry cost | GLM 전체 JFR STW GC 1.679초; privacy 0.049% 실행 표본 | 이번 자료로 우선 최적화할 근거가 약하다. |

위 비율은 **`MemoProgramModel.optimize`를 포함한 JFR 실행 stack의 inclusive 비율**이다. 호출 횟수나 독립적인 wall time 비율이 아니다. 부모·자식 경로 비율을 합하면 중복 계산된다. 예를 들어 movement 68.77%와 guardValue 34.58%를 더해서 총비용이라고 해석하면 안 된다.

분석 근거는 [실행·환경 요약](../experiments/optimizer-profile-20261014/optimizer-profile-summary.json), [JFR 비교 집계](../experiments/optimizer-profile-20261014/profile-comparison.json), [독립 소스 분석](../experiments/optimizer-profile-20261014/source-analysis.md), [정확성 검토](../experiments/optimizer-profile-20261014/correctness-review.md)에 보존했다.

## 2. 무엇을 어떻게 측정했는가

### 2.1 고정 환경과 코드 provenance

모든 workload는 `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e --profile-jfr`로 실행했다. 호스트에서 workload를 실행하지 않았다.

| 항목 | 조건 |
|---|---|
| Docker image | `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434` |
| 자원 | 4 CPU, 8 GiB container limit, network none |
| JVM | coordinator `-Xmx3g -XX:ActiveProcessorCount=4`; worker 각 `-Xmx1g` |
| Worker pool | 고정된 3 workers, ports 13000/13001/13002 |
| Workload | 기존 harness LogReg/GLM fixture, seed 7, Memo planner, runtime conversion 금지 |
| Timeout | case 300초, wrapper 1,800초 |
| JFR | FED coordinator만, `settings=profile`, stack depth 256, disk, dumponexit, duration 299초 |
| 사전 gate | frozen class preflight 및 `FederatedPlanMemoTest` model proof |

Baseline은 기존 `experiments/oracle-cost-projection-20261013/baseline-engine`, 현재는 `experiments/oracle-request-dedup-20261014/after-engine`을 동결하여 사용했다. 실행본 main/test sources 및 main classes가 해당 snapshot과 같음을 확인했다. 시작 시 [production hash 목록](../experiments/optimizer-profile-20261014/production-before.sha256.json)을 기록하고 종료 시 비교했다. 추적 중인 src/scripts 9,061개 파일의 hash 변경은 0개였다.

JFR 후처리용 Java/Python 프로그램은 `experiments/optimizer-profile-20261014/`에만 작성했다. 이 프로그램은 기록 파일을 읽으며 측정 JVM에 주입되지 않는다.

### 2.2 시간의 정확도와 자료의 한계

`FederatedPlanMemo.planWithLease()`의 기존 `System.nanoTime()` trace가 facts/rules/model/optimization의 **정확한 전체 경과 시간**을 제공한다. `optimization`에는 다음이 함께 들어간다.

```text
MemoProgramModel.optimize
  new MemoOptimizer(...)             생성자
  MemoOptimizer.optimize
    initial propagate
    normalizeAfterPropagation
    fixed UNKNOWN 확인
    tryTreeSolve / tryLeafRegionSolve
      ReducedSearch.enumerateCore    이번 실제 탐색 경로
    필요할 경우 global search
  evaluateCost(selected)             원본 비용으로 독립 재검증
  reconstructSupplies(selected)
```

기존 phase timer는 메서드 자체만 감싼 것이 아니므로 phase 사이 trace 출력도 포함한다. 하위 단계에는 기존 entry/exit timer가 없다. Production을 수정하지 않았으므로 하위 시간은 JFR에서 해당 단계의 **첫 실행 표본~마지막 실행 표본 사이 관측 구간**이다. 메서드의 정확한 전체 시간 또는 CPU 시간으로 둔갑시키지 않는다. 0~1개 표본만 있는 단계의 시간을 0초로 주장하지 않는다. sampling gap, JIT, GC, safepoint 등이 있어 단계 시간을 합해 전체 trace와 같게 맞출 수 없다.

Allocation은 `ObjectAllocationSample.weight` 합계로 추정했다. 객체 생성의 누적량이며 살아 있는 heap/RSS가 아니다. 작은 단계별 allocation attribution은 표본 간격 때문에 오차가 커질 수 있다. JFR 데이터 손실 이벤트는 없었다.

JFR CPU load는 JVM 전체를 포함하며 main thread 이외 JIT/GC도 들어간다. 관측된 host hardware threads 48 기준으로 core 상당량을 계산했다. 짧은 단계의 1초 CPU 표본은 인접 단계가 섞일 수 있으므로 정밀 비교에 쓰지 않는다. GLM은 별도 `/proc` CPU tick으로 장시간 구간을 교차 확인했다.

LogReg `/proc` 수집은 초기 selector가 `timeout` wrapper를 가리켜 유효한 JVM RSS/CPU 자료를 얻지 못했다. 이 값은 전부 제외했고 재실행하지 않았다. 따라서 LogReg에는 coordinator JFR heap/CPU와 container 메모리만 사용하며, coordinator RSS를 측정했다고 주장하지 않는다. GLM은 수정된 Java PID selector로 유효한 표본을 얻었다.

### 2.3 과거 시간과 이번 profile 시간을 분리

| 실행 | Rules | Model | Optimization | 결과 |
|---|---:|---:|---:|---|
| 과거 baseline, JFR 없음 | 0.281초 | 1.281초 | **25.200초** | OPTIMAL |
| 과거 projection 구현 `2aff96c87f`, JFR 없음 | 1.889초 | 0.958초 | **39.076초** | OPTIMAL |
| 이번 baseline, JFR 사용 | 0.318초 | 1.475초 | **24.600초** | OPTIMAL |
| 이번 현재 `25662ee7b4`, JFR 사용 | 2.207초 | 0.977초 | **51.601초** | OPTIMAL |
| 이번 현재 GLM, JFR 사용 | 6.337초 | 3.906초 | 완료 trace 없음 | **case 300초 timeout / rc124** |

과거 25.20→39.08초는 +13.876초, +55.1%라는 관측이다. 이번 24.60→51.60초를 그 회귀율의 재현값 또는 profiler overhead의 정확한 추정치로 사용하지 않는다. profile overhead, JIT/실행 변동, 후속 코드가 모두 개입할 수 있다. 현재 코드의 native request dedup은 과거 `2aff96c87f` 이후 추가된 변경이다.

그러나 LogReg에서 solver 소스는 baseline과 같고, 아래 탐색 규모도 동일하다. 새 profile이 movement subtree에 증가분을 집중시킨다는 사실과 실제 소스 변경이 같은 위치라는 사실은 **반복 movement/shape 분석을 회귀의 주된 경로로 판단할 강한 근거**다. 과거 비계측 13.876초의 각 함수별 귀속까지 정확히 측정한 것은 아니다.

| LogReg 검증 항목 | baseline / current |
|---|---|
| Status | OPTIMAL / OPTIMAL |
| Native rules | 920 / 920 |
| Search nodes | 2,933,891 / 2,933,891 |
| Worklist visits | 13,616,123 / 13,616,123 |
| Removed rules | 21,182,609 / 21,182,609 |
| Cost evaluations / hits | 4,828 / 3,407, 양쪽 동일 |
| Conditional-bound queries / hits | 11,135,047 / 11,132,120, 양쪽 동일 |
| Check evaluations | 60,690 / 60,690 |
| Support queries | 38,286,830 / 38,286,836 |
| Exact objective | 아래 값으로 동일 |

```text
1301.1526531142989790221751587629165844428591469750244868919253349304199218750
```

과거 baseline GLM은 transpose owner 974의 `ROW/COL CANONICAL_EFFECT_NOT_AUDITED` coverage 때문에 `UNRESOLVED`로 조기 종료했다. 당시 짧은 optimize 시간은 성공한 GLM 계산 시간이 아니다. 현재 GLM timeout과 baseline 사이의 성능 회귀율은 계산할 수 없다. 이번에는 해당 baseline GLM 실패를 반복 실행하지 않았다.

LogReg 양쪽의 harness numeric/runtime audit가 통과했다. GLM은 최적화와 FED 출력이 완료되지 않았으므로 parity·선택 plan·최적해를 검증했다고 표시하지 않는다.

## 3. model.optimize 단계별 관측

### 3.1 시간과 CPU 표본

괄호는 optimizer 실행 표본 중 비율이다. 전체 optimizer 표본 수는 baseline LogReg 1,906, 현재 LogReg 3,900, 현재 GLM 18,231이다.

| 단계 | baseline LogReg 관측 구간 | 현재 LogReg 관측 구간 | 현재 GLM 관측 구간 |
|---|---:|---:|---:|
| Optimizer 생성자 | 1표본, 시간 미분해 | 1표본, 시간 미분해 | 1표본, 시간 미분해 |
| 초기 propagation | 0.553초 / 13표본 | 0.735초 / 24표본 | 3.421초 / 197표본 |
| Normalization | 0.075초 / 3표본 | 0.116초 / 3표본 | 1.110초 / 67표본 |
| Leaf-region 준비, core 재귀 제외 | 0.122초 / 8표본 | 1.220초 / 85표본 | 0.070초 / 6표본 |
| **ReducedSearch core 탐색** | **23.213초 / 1,876 (98.43%)** | **49.302초 / 3,785 (97.05%)** | **281.751초 / 17,957 (98.50%)** |
| 선택 비용 원본 audit | 1실행 표본 | 실행 표본 없음, allocation 표본 및 OPTIMAL 후속 완료 | 도달하지 못함 |
| Supply reconstruction | 0.020초 / 3표본 | 0.020초 / 2표본 | 도달하지 못함 |
| 기존 trace 전체 optimization | **24.600초** | **51.601초** | 미완료 |

`tryTreeSolve()`와 fallback `search()`에는 별도 실행 표본이 없었다. 소스 순서와 `ReducedSearch` stack으로 leaf-region 경로가 실제 작업을 담당함을 확인했다. 표본이 없는 fast-path 확인 비용을 절대 0이라고 하지는 않는다.

ReducedSearch 관측 구간 JVM 평균 CPU는 baseline LogReg 약 **1.24 core**, 현재 LogReg **1.13 core**, 현재 GLM **1.04 core**였다. GLM Java PID의 별도 245.096초 구간에서 user+system CPU 249.33초, 즉 **1.017 core**를 확인했다. 주된 solver 작업은 main thread에서 지속적으로 CPU를 사용했다. 4 core quota가 있다고 탐색 자체가 4 core에 병렬화된 것은 아니다.

### 3.2 단계별 할당과 메모리

| 단계 | baseline LogReg 추정 할당 | 현재 LogReg 추정 할당 | 현재 GLM 추정 할당 |
|---|---:|---:|---:|
| 생성자 | 0.013 GiB | 0.114 GiB | 0.015 GiB |
| 초기 propagation | 0.668 GiB | 0.510 GiB | 4.804 GiB |
| Normalization | 0.170 GiB | 0.241 GiB | 1.483 GiB |
| Leaf-region 준비 | 0.177 GiB | 1.902 GiB | 0.093 GiB |
| ReducedSearch core | 33.157 GiB | 65.081 GiB | 444.683 GiB |
| **전체 optimize** | **34.265 GiB** | **67.911 GiB** | **451.223 GiB** |

이는 누적 allocation의 sampling 추정이며 메모리를 위 용량만큼 점유했다는 뜻이 아니다.

| 전체 recording의 메모리/GC 관측 | baseline LogReg | 현재 LogReg | 현재 GLM |
|---|---:|---:|---:|
| GC heap used 관측 최대 | 269.12 MiB | 369.82 MiB | 467.35 MiB |
| GC 직후 heap used 관측 최대 | 49.42 MiB | 49.83 MiB | 83.35 MiB |
| GC 횟수 | 179 | 242 | 1,255 |
| 총 STW pause | 0.367초 | 0.447초 | 1.679초 |
| 가장 긴 STW pause | 12.04ms | 11.50ms | 13.11ms |
| 유효 coordinator RSS/HWM 최대 | 미측정 | 미측정 | 988,276 KiB ≈ 965.1 MiB |

현재 실행 container 메모리 관측 최대는 약 1.357 GiB였다. 이 값에는 workers 등이 포함되므로 GLM coordinator heap과 혼동하면 안 된다. 관측 구간은 8 GiB 제한에 접근하지 않았고, GLM 종료는 OOM이 아닌 timeout rc124다. 일시 객체의 생성·hash·복사 CPU 비용은 크지만 **GC 정지 시간이 timeout을 설명하지는 않는다**.

## 4. 실제 반복 작업은 어디에서 발생하는가

### 4.1 Movement evaluator가 고정된 분석을 다시 실행한다

구현 위치: [MemoProgramModel.java](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java), `movementGroupCost` 643행, `visitBindingCostContexts` 1041행. 새 guard 탐색은 [MemoShapes.java](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoShapes.java) 243행부터다.

```text
ReducedSearch.coreLowerBound
  computeConditionalCostBound -> minimumTerm -> evaluateCost(cache miss)
    movementGroupCost
      edge × frame
        visitActive / resolveOriginIdentity
          selected from/target 결정
          DIRECT/IMPOSSIBLE이면 현재대로 종료
          visitBindingCostContexts
            firstUnfixedCostGuardForBinding
              binding DAG 순회
              provesActiveRepeatHeadCostStats
                provesRepeatHeadCostStats
                  FIRST/MIDDLE contextual shape 다시 평가
          shapes.viewAt / movementCost / activationUnion
```

`firstUnfixedCostGuardForBinding`은 매 요청마다 새 `HashMap`, active set, accumulator를 만든다. 재귀에서 한 guard를 고정한 뒤 다시 전체 필요한 binding 경로를 방문한다. 루프 순환을 만나면 반복 비용 통계가 같다는 증명을 다시 수행한다. 이 작업은 solver의 `costEvaluations=4,828` 같은 outer counter에 내부 횟수로 드러나지 않는다.

| inclusive optimizer 실행 표본 | baseline LogReg | 현재 LogReg | 현재 GLM |
|---|---:|---:|---:|
| movementGroupCost | 301 / 15.79% | 2,012 / 51.59% | 12,538 / 68.77% |
| visitBindingCostContexts | 없음 | 1,778 / 45.59% | 11,990 / 65.77% |
| firstUnfixedCostGuardForBinding | 없음 | 1,081 / 27.72% | 9,384 / 51.47% |
| provesRepeatHeadCostStats | 없음 | 784 / 20.10% | 8,776 / 48.14% |
| phaseProvesGuard | 19 / 1.00% | 366 / 9.38% | 6,440 / 35.32% |

Movement 하위 추정 allocation은 baseline LogReg **8.558 GiB → 현재 41.910 GiB**였다. 반면 movement/input/privacy 등을 제외한 solver 및 기타 경로는 **20.454 → 20.643 GiB**로 비슷했다. 전체 allocation 증가의 대부분이 movement subtree에 모인다. 단일 표본의 가중 추정이지만 CPU stack 변화와 같은 방향이다.

Source-only projection과 정확한 shape 증명 자체가 불필요한 것은 아니다. 같은 의미 입력에 대한 증명을 rule assignment마다 되풀이하는 것이 개선 대상이다. 과거의 부정확한 비용 계산으로 되돌리는 방법은 제외한다.

### 4.2 불변 guard/phase 조회가 중첩 순회로 증폭된다

[MemoProgramFacts.java](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramFacts.java) 299~345행:

- `guardPhases(loop)`는 매번 전체 `guardPhases`를 stream/filter한다.
- `guardValue(loop,guard,phase)`도 전체 목록을 stream/filter/findFirst/map한다.
- `guardValueProvenance(guard,frame)`는 결과 cache가 있어도 `GuardFrame`과 frame 표현을 만들고 map을 조회한다.
- `guardDecision`은 같은 입력이어도 매번 새 `GuardDecision`을 만든다.

`MemoShapes.phaseProvesGuard` 793행은 **전체 guard × frame prefix**를 순회한다. 그 안에서 위 `guardValue`와 canonical decision 조회를 호출한다. `withPhase`는 이것을 현재 guard set의 각 원소에 반복한다. 구조가 고정된 조회를 중첩해서 재계산하는 모양이다.

GLM optimizer 표본의 `guardValue`는 **6,305 / 34.58%**, `guardDecision`은 **4,388 / 24.07%**다. Stream sink 생성이 많이 관측되는 이유가 이 소스 경로와 일치한다. Cache가 있다는 사실만으로 cheap hit가 되는 것은 아니다. `PhaseContextKey` 생성 경로 자체가 GLM **22.32%**에 나타났으므로, 비싼 key를 추가하는 것만으로 해결됐다고 판단하면 안 된다.

### 4.3 Input legality도 같은 contextual shape 작업을 반복한다

`MemoProgramModel.inputCompatible`는 현재 LogReg 221 / **5.67%**, GLM 5,240 / **28.74%**다. GLM 해당 subtree의 추정 allocation은 **135.927 GiB**다. `visitActive`/`MemoReachableContexts`의 activation·caller prerequisite·loop phase 열거와 `MemoShapes.bindingShape`/`resolvedBindingShape`가 포함된다.

이는 모든 legality가 invariant라는 뜻이 아니다. 선택된 producer/consumer layout, loop head, worker binding, movement 가능성은 실제 assignment에 의존한다. 불변인 reachable context 구조와 shape 증명을 준비하고, 각 tuple에는 동적인 layout/정책 판단을 그대로 수행해야 한다.

Privacy는 이미 `PrivacyDemand`를 이용한 재사용이 있다. 이번 profile의 `privacyCompatible` 자체는 GLM 9표본에 불과하다. 보안을 위해 필요한 검사를 생략하거나 privacy 전체를 추가 cache 계층으로 감싸는 것이 우선 과제는 아니다.

### 4.4 Solver cache hit도 공짜가 아니다

[MemoOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java):

- `propagate` 429행: revision stamp `long[]` 생성, domain 순회.
- `support` 454행: revision을 펼친 `int[]`, `TupleKey`; miss마다 partial tuple DFS.
- `evaluate` 497행: hit에도 `TupleKey` 생성, 그 생성자에서 `int[]` clone.
- `computeConditionalCostBound` 1048행: domain cardinality와 실제 ordinal을 key 배열로 직렬화한 뒤 다시 clone.
- `TupleKey` 1204행: hash 계산과 배열 복제.

LogReg의 conditional-bound hit rate는 **99.97%**다. 그래도 key 생성과 domain 열거는 매 query에 발생한다. Truth cache hit 약 8,205만 번에도 동일한 문제가 있다. Baseline ReducedSearch leaf 표본의 상위에는 `BitSet.cardinality`, `HashMap$TreeNode.getTreeNode`, `Arrays.hashCode`, `BitSet.set` 등이 있었다.

현재 GLM에서는 이 경로보다 실제 predicate/cost callback의 shape 계산이 훨씬 크다. `TupleKey.<init>`는 GLM 0.154% 실행 표본이다. 따라서 먼저 key 자료구조를 대대적으로 교체하는 것만으로 GLM timeout을 해결할 것으로 기대하지 않는다.

### 4.5 Cost factor 정규화 가설은 이번 자료에서 우선순위를 낮춘다

`foldAndGroupCosts` 310행은 singleton scope를 줄인 뒤 같은 scope의 factor를 묶고, unary group을 **원래 domain 전체 ordinal**에 평가해 native cost에 합한다. 초기 propagation에서 제거된 ordinal까지 평가하는 반복은 존재한다. Group evaluator의 clone, primitive별 `BigDecimal(double)` 변환도 존재한다.

하지만 GLM 정규화의 실행 표본은 약 1.11초 구간에 걸쳐 관측됐고, 그 이후 ReducedSearch의 표본이 281.75초에 걸쳐 이어졌다. 따라서 “35만 unary term/정규화 전개 때문에 이번에도 300초를 소모한다”는 설명은 현재 버전의 주원인으로 채택할 수 없다. 과거 v9a 진단과 현재 source-only projection 결과를 분리해야 한다. 이번 GLM model trace는 projected contexts/vectors 9,517개, primitive values 49,892개를 기록했다. 이 수치를 과거 unary term 개수와 같은 항목으로 간주해서는 안 된다.

`CostTerm.evaluateExact`의 큰 inclusive 비율 역시 BigDecimal 자체의 비용을 뜻하지 않는다. 그 아래의 movement evaluator가 대부분을 차지한다.

## 5. 가장 단순한 개선 순서

큰 새 planner 계층, 모든 tuple의 eager table, 새로운 dependency를 만들기보다 기존 클래스 안의 immutable index와 model-local 재사용부터 적용한다.

### A. 고정된 guard/phase 정보를 한 번 인덱싱 — 첫 구현 권장

**위치:** `MemoProgramFacts`의 분석 결과가 freeze되는 시점 및 `guardPhases`, `guardValue`, `guardDecision`.

1. `(loopId, guardId) -> GuardPhase`와 `loopId -> GuardPhase 목록`을 한 번 만든다. 조회 시 phase의 기존 Boolean 값을 직접 읽는다.
2. 기존 `findFirst`의 순서 의미를 보존한다. 중복 key가 있을 수 있으면 첫 항목을 유지하거나 실제 불변식을 검증한다. `null`은 “증명 없음”으로 그대로 둔다.
3. 이미 도달한 `(guardId, exact callFrame)`의 provenance와 canonical `GuardDecision`을 함께 재사용한다. Frame 전체 Cartesian catalog는 만들지 않는다.
4. `phaseProvesGuard`는 해당 loop에서 그 phase에 증명된 guard 목록만 순회한다. “목록에 없으면 현재대로 false”인 조회 최적화이며 후보 plan pruning이 아니다.

**예상 효과:** GLM 34.58%, LogReg 8.95%에서 관측된 `guardValue`의 선형 검색/stream 할당을 직접 줄인다. 이는 타깃 비중이며 같은 비율의 speedup을 보장하지 않는다. 표본 비율이 시간 비율을 대표하고 다른 비용이 변하지 않는다는 단순 모형에서, 이 subtree를 전부 제거하는 이상적인 단독 절감 한계는 관측 작업 기준 약 34.6%, 8.9%다. GLM은 미완료 실행의 비율이므로 최종 완료 시간의 상한이나 speedup 보장이 아니다. 다른 개선과 비율을 더하지 않는다.

**정확성 위험:** caller/callee alias, Boolean inversion, SELECT/LOOP_HEAD/LAST의 provenance 경계를 guard id 하나로 합치는 오류. Facts 생성 도중 아직 바뀌는 목록을 일찍 고정하는 오류.

**검증:** 기존 목록 scan reference와 모든 등록 loop/guard/phase 조회를 비교한다. Unknown/null, 중첩 loop, 다중 call frame, 같은 Boolean alias와 반전, 서로 다른 value version을 포함한다. Profile에서 stream/GuardFrame/GuardDecision 할당과 `guardValue` 표본이 실제 감소하는지 확인한다.

### B. 반복 shape 증명을 완전한 의미 입력으로 재사용

**위치:** `MemoShapes.provesRepeatHeadCostStats`, `firstUnfixedCostGuardForBinding`, `PhaseContextKey` 주변.

- 첫 요청은 현재 알고리즘으로 증명하고, 완료한 proof 결과만 같은 `MemoShapes`/model 수명에서 재사용한다.
- key에는 대상 loop head/slot 또는 binding, exact call frame, guard **값과 provenance**, loop boundary/iteration phase/cardinality 및 shape 분석 세대가 포함된다.
- source/rule selection에 독립적이라는 것을 호출 경로로 증명한 결과만 selection을 key에서 뺀다. 증명되지 않은 의존성은 남긴다. shape만 같다는 이유로 input identity를 합치지 않는다.
- `provesActiveRepeatHeadCostStats` 전체는 active recursion path에서 “가장 가까운 head”를 고르는 작업까지 포함한다. 이 호출을 binding id만으로 memoize하지 않는다. head를 정한 이후의 완료된 pure proof를 재사용하는 편이 단순하다.
- 기존 관련성·phase projection 의미를 공유한다. 최적화별로 비슷한 key writer를 복제하지 않는다. 그러나 기존 key 생성 자체가 비싸므로 guard dependency와 canonical frame/decision metadata는 미리 준비한다.
- 캐시 크기는 model-local로 제한한다. eviction 시 원래 계산을 다시 수행한다. 캐시 포화를 UNKNOWN/NO나 0원으로 처리하지 않는다. Mutable HOP view를 공유하는 대신 immutable shape/proof 결과를 저장한다.

**예상 효과:** 현재 GLM `provesRepeatHeadCostStats` 48.14%, LogReg 20.10%에 걸친 반복을 줄일 수 있다. **실제 동일 key 반복률은 아직 계측하지 않았다.** 따라서 예상 hit rate나 몇 배 빨라질지는 확정하지 않는다. 먼저 request/unique/hit/key-generation time을 측정해야 한다.

**정확성 위험:** in-progress cyclic proof를 성공으로 cache, FIRST/MIDDLE/LAST와 ZERO/ONE/MANY 혼합, 다른 frame·nested head 혼합, UNKNOWN 재사용 정책 오류.

**검증:** old/new proof를 같은 full context에 differential 비교한다. phase마다 shape/nnz가 변하는 backedge, invariant outer loop 안의 changing inner loop, unresolved cycle, 반복 guard alias를 포함한다. Cache on/off가 비용 double bits, feasibility/status, diagnostics를 바꾸지 않아야 한다.

### C. Movement/input legality는 구조와 선택 의존 부분만 분리

**위치:** `MemoProgramModel.registerMovementCosts`, `movementGroupCost`, `inputCompatible`; `MemoReachableContexts`의 phase/caller metadata.

간단한 책임 분리는 다음과 같다.

| 한 번 준비하거나 같은 context에서 재사용 | 선택 tuple마다 계속 수행 |
|---|---|
| binding/occurrence/frame dependency, canonical guard metadata | source/consumer rule 및 loop-head layout 읽기 |
| phase/cardinality별 reachable activation 구조 | DIRECT/IMPOSSIBLE 판정 및 실제 movement legality |
| 동일 의미 입력의 shape/proof 결과 | privacy demand와 legality/UNKNOWN 판정 |
| frozen shape·worker 수에 대한 movement unit primitive | 활성 origin의 정확한 identity/retention/target별 비용 소유권 |
| 가능한 경우 원래 순서의 immutable context descriptor | retained activation union, 현재 의미의 비용 합산 |

**핵심은 gate 순서를 보존하는 lazy 준비다.** 현재 `movementGroupCost`는 from/target을 본 뒤 DIRECT/IMPOSSIBLE이면 costly projection을 호출하지 않는다. 모든 factor를 생성자에서 eager하게 준비하면 원래 호출하지 않던 분기의 `UNRESOLVED_COST_CONTEXT`가 먼저 발생할 수 있고, 불필요한 shape 분석도 늘어난다. 첫 구현은 **현재 gate 뒤에서 필요해진 exact input의 projection만** 계산·저장하는 방법을 권장한다.

또한 모든 source 조합을 펼친 뒤 제거하는 catalog를 만들면 안 된다. 기존 resolver/Oracle가 허용한 prefix와 reachable continuation만 따라가고, UNKNOWN 의무는 그대로 보존한다. 재사용 구조는 기존 binding/guard 결정 구조를 공유하며 전체 plan assignment나 source Cartesian product를 미리 생성하지 않는다.

Movement identity와 primitive unit bits를 유지한다. 현재 factor 내부의 `double` 곱셈·덧셈 순서 및 retained activation union도 유지해야 한다. Native primitive들은 각 `double`을 먼저 정확한 `BigDecimal`로 바꿔 합한다. 이를 모두 double로 미리 합하거나 primitive 경계를 임의로 변경하면 exact cost semantics가 달라진다.

**예상 효과:** solver가 다른 layout tuple을 평가할 때에도 고정 binding-DAG/shape 분석을 재사용한다. 현재 GLM 68.77% movement와 28.74% input legality의 공통 분석 비용을 겨냥한다. Eager하게 Model 시간으로 이동시켜 Optimization 숫자만 줄이는 것을 성공으로 보지 않고 **전체 planning 시간과 peak memory를 함께 비교**한다.

**정확성 위험:** 같은 source shape의 서로 다른 origin/materialization을 합쳐 shared cost를 누락, worker pool/범위나 multi-output slot 혼합, activation/normal continuation 손실, 호출하지 않을 분기의 failure를 앞당김.

**검증:** 현재 original evaluator와 새 evaluator의 전체/partial assignment differential 비교, original selected-cost audit 유지, activation union과 demand multiplicity 비교, worker pool W 변경·frame/slot/phase 변형 fixture. 이미 생성된 tiny exhaustive fixture의 모든 plan을 기준으로 status/objective/tie-break를 비교한다.

### D. Solver의 cache hit 경로에서 key/배열 할당 감소

**위치:** `MemoOptimizer.evaluate`, `support`, `computeConditionalCostBound`, `TupleKey`, `stamp`.

1. 먼저 lookup 시 기존 tuple을 읽고 **miss로 저장할 때만 immutable copy**를 만드는 작은 probe 경로를 검토한다. 저장된 key가 mutable scratch를 참조하면 안 된다. nested lookup/reentrancy도 고려한다.
2. 작은 scope는 `-1` partial sentinel까지 포함한 충돌 없는 ordinal 인덱스를 계산하고 lazy table을 사용할 수 있다. 단 명시적인 크기 한도를 두며 큰 scope는 기존 map으로 남긴다. 초기화할 때 모든 tuple의 predicate를 실행하지 않는다.
3. Domain cardinality와 반복되는 scope/index metadata를 유지해 매번 `BitSet.cardinality`를 다시 세는 비용을 줄일 수 있다. remove뿐 아니라 undo에서도 정확히 갱신한다.
4. Support cache를 revision 대신 domain content로 재사용하는 변경은 별도 단계다. 같은 domain 내용과 query, budget/predicate identity가 같음을 확인해야 한다. fingerprint만으로 동일시하지 않는다. Conditional bound는 이미 domain 내용 key이므로 먼저 그 직렬화·clone 비용을 줄인다.

**예상 효과:** LogReg의 수천만 cache hit와 약 20.6 GiB solver/기타 allocation 중 key 관련 부분을 줄인다. GLM 주병목 해결의 대체안은 아니다.

**정확성 위험:** partial `-1` 누락, hash collision을 동일 입력으로 오인, backtracking 후 stale support, normalization 후 바뀐 factor id에 이전 cache 사용, cache capacity 0 계약 변경.

**검증:** cache 0/기존/개선 간 exhaustive parity, nonzero singleton projection, remove/undo 반복, UNKNOWN 및 finite-budget 상태 비교. Hash가 같고 tuple이 다른 충돌 fixture를 포함한다. 동일 결과와 함께 allocated int/long 배열 및 lookup path CPU 감소를 확인한다.

### E. Cost normalization은 나중에 작게 정리

**위치:** `MemoOptimizer.foldAndGroupCosts`, `MemoProgramModel`의 native primitive 등록.

초기 propagation에서 영구 제거된 ordinal을 unary folding에서 다시 평가하는 것은 줄일 수 있다. 기존 exact primitive conversion 결과를 한 번 보관하는 방안도 검토할 수 있다. 그러나 모든 unary를 lazy factor로 남기는 변경은 branch ordering과 finite-budget 동작을 바꿀 수 있다. 이번 측정의 작은 비중을 고려하면 이 변경을 먼저 할 이유가 없다.

원본 `MemoProgramModel.evaluateCost`는 grouped/folded 표현과 독립된 audit로 유지한다. 검증 비용을 줄인다는 이유로 제거하거나 optimizer가 만든 같은 합계만 비교해서는 안 된다.

## 6. 정확성 보존 조건

| 계약 | 유지할 조건 |
|---|---|
| Feasibility/coverage | 열린 native universe를 OPTIMAL로 승격하지 않음. 합법 rule을 편의상 제거하지 않음. |
| UNKNOWN/DEFER | No proof, budget exhaustion, unresolved context를 NO 또는 비용 0으로 바꾸지 않음. Privacy DEFER도 그대로 유지. |
| Context identity | call frame, guard provenance/값, loop phase/cardinality, normal continuation, output slot 구분. |
| Worker binding | frozen pool identity/placement/range와 selected layout 의존성 유지. 다른 W에 cache 공유 금지. |
| Exact costs | 원래 binary64 primitive와 factor 내부 연산 순서, primitive별 exact BigDecimal 합 보존. |
| Shared movement | exact origin/materialization identity와 activation union을 보존. 단순 shape dedup 금지. |
| 최적해 | certified lower bound를 악화된 근사 비용으로 대체하지 않음. UNKNOWN 있는 incumbent를 optimum으로 반환하지 않음. |
| Cache | model/invocation 수명, normalization 후 전체 무효화, mutable state 분리, full identity equality. |
| Runtime | fallback 추가 금지, selected supply reconstruction fail-closed, DIRECT backedge 계약 유지. |

A의 invariant lookup 개선을 먼저 적용하고, B/C는 기존 evaluator와 비교할 수 있는 작은 단위로 적용하는 순서가 위험과 복잡성을 줄인다. Cache를 추가했는데 key 만드는 시간이 더 커지는 경우도 평가에서 실패로 처리한다.

## 7. 재현 및 향후 개선 전후 평가

### 7.1 이번 profile 재현

정확한 전체 명령은 다음 두 파일에 저장했다. 재실행 시 evidence 충돌을 피하도록 run id를 새로 지정한다. 이번 작업에서는 이미 각 조건 1회 완료했으며 다시 실행하지 않았다.

- [baseline 명령](../experiments/optimizer-profile-20261014/optimizer-profile-baseline-command.txt)
- [현재 명령](../experiments/optimizer-profile-20261014/optimizer-profile-current-command.txt)

원본 결과 경로:

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/fedplanner-oracle-cost-docker-20261013/
  optimizer-profile-baseline-01/
    cases/ml_logreg/fed.log
    cases/ml_logreg/fed.jfr
    result.json
    manifest.json
  optimizer-profile-current-01/
    cases/ml_logreg/fed.log
    cases/ml_logreg/fed.jfr
    cases/ml_glm/fed.log
    cases/ml_glm/fed.jfr       # 최종299초 recording
    cases/ml_glm/live.jfr      # 중간97초, 최종 집계에 중복 합산하지 않음
    result.json
    manifest.json
```

분석 재생은 workload 재실행이 필요 없다.

```bash
cd /home/mchoi/fedplanner-oracle-cost-20261013
javac experiments/optimizer-profile-20261014/JfrExtract.java
# 각 fed.jfr에 대해 실행. <name>은 baseline-logreg/current-logreg/current-glm.
java -cp experiments/optimizer-profile-20261014 JfrExtract \
  <fed.jfr 경로> experiments/optimizer-profile-20261014/<name>-events.jsonl
python3 experiments/optimizer-profile-20261014/analyze_jfr.py \
  experiments/optimizer-profile-20261014/<name>-events.jsonl \
  experiments/optimizer-profile-20261014/<name>-profile.json
# 위 세 recording의 후처리가 끝난 뒤:
python3 experiments/optimizer-profile-20261014/summarize_metrics.py
```

`JfrExtract`는 JDK 내장 JFR consumer만 사용한다. `analyze_jfr.py`는 이벤트를 streaming 집계하고, `summarize_metrics.py`는 동일 phase/method 분모로 비교한다. Raw `.jfr` SHA와 parser 결과는 실행 요약에 있다.

### 7.2 개선 구현 시 평가 조건

이번에 실제 구현 전후 speedup을 측정한 것은 아니다. 다음 구현에서는 현재 commit을 before로 고정하고 변경 한 묶음마다 after와 비교한다.

1. **정확성 gate:** `MemoOptimizerTest`, `MemoExhaustiveParityTest`, `MemoCostContextProjectionTest`, `MemoLoopCostContextProjectionTest`, `MemoRepeatedLoopCostTest`, privacy/control-flow/worker/supply 관련 기존 회귀와 신규 differential fixture를 실행한다. 현재 알려진 training operation inventory golden drift는 별도 기존 실패로 기록한다.
2. **시간 비교:** 동일 Docker 이미지·fixture·W·CPU·heap·timeout에서 before/after 각 1회. 사용자 요청 범위에서 반복 평균은 만들지 않는다. JFR 없는 실제 시간 비교와 JFR 사용 원인 분석은 서로 다른 종류의 증거로 보고한다. 이 둘을 같은 표본으로 합치지 않는다.
3. **모든 단계를 함께 비교:** facts/rules/model/optimization/projection/전체 planning, compilation/runtime, coordinator CPU, heap/RSS, GC, allocation. Model 사전계산 때문에 전체가 느려지면 실패다.
4. **반복 제거 증거:** guard lookup 수/stream scan 수, proof request/unique/hit, key 준비 시간, binding DAG visit, movement factor unique tuple, context descriptor 수와 크기를 함께 기록한다. 기존 outer cost/check counter만으로 내부 비용 감소를 추정하지 않는다.
5. **결과 동등성:** status, exact objective, selected rule/supply signature, coverage/diagnostics, UNKNOWN/DEFER, numeric parity와 runtime audit를 비교한다. Pure lookup 변경은 같은 search counters가 기대된다. Branch ordering을 바꾸는 변경은 nodes가 달라도 exact optimum·tie-break가 같아야 한다. Finite-budget incumbent 시점은 별도 비교한다.
6. **GLM 완료 gate:** timeout이면 `>300초, 미완료`로 남긴다. 시간이 지난 부분의 표본 비율로 완료 시간을 외삽하거나 “최적해가 같을 것”이라고 표시하지 않는다. OPTIMAL 및 original selected-cost audit/FED output parity까지 완료되어야 성능 개선 완료로 인정한다.

더 정확한 하위 wall/CPU 시간이 필요하면 향후 승인된 구현 단계에서 constructor/initial propagation/normalization/tree/leaf-core/audit/reconstruction 경계에 소수의 timing event를 넣을 수 있다. predicate를 추가로 호출하지 않고, per-evaluation 로그를 쌓지 않으며, 계측 off에서 결과·counter가 같음을 검증해야 한다. 이번에는 그 production 계측도 추가하지 않았다.

## 8. 해결된 질문과 남은 한계

- **확인:** 현재 GLM은 비용 정규화에 갇힌 것이 아니라 ReducedSearch에서 대부분의 시간을 보낸다. 그 내부의 movement와 input legality callback이 shape/phase/guard 분석을 반복한다.
- **확인:** LogReg은 탐색 규모가 같아도 movement evaluator의 단위 작업과 객체 생성이 커졌다. ProgramFacts의 불변 lookup을 인덱싱하는 작은 변경과 exact-context proof 재사용이 우선이다.
- **확인:** Heap 부족이나 긴 GC 정지가 이번 timeout의 주원인이라는 증거는 없다. 높은 누적 allocation은 주로 CPU·객체 생성 비용으로 관측된다.
- **미확정:** GLM의 최종 탐색 노드 수, 남은 탐색량, 완료 시간, 최적해 및 FED 출력은 아직 없다. Callback을 빠르게 해도 combinatorial search가 다음 병목이 될 수 있다.
- **미확정:** Proof cache의 실제 unique key 수/hit rate와 순개선 폭. 후보 key를 안전하게 설계한 뒤 구현·측정해야 한다.
- **한계:** 과거 비계측 LogReg 39.08초의 함수별 정확한 시간 분해, LogReg coordinator RSS, 하위 메서드의 정확한 entry/exit 시간은 이번 자료로 제공할 수 없다. 이 공백을 숨기거나 표본 span을 정확한 timer 값으로 표시하지 않았다.

검증: Docker 사전 model proof는 baseline/current 각각 7 tests PASS, LogReg 두 case PASS였다. JFR phase/category 표본 수와 allocation 합계가 전체와 일치했고, 데이터 손실 이벤트가 없으며, Python 후처리 구문 검사 및 문서 링크/공백 검사를 통과했다. Production 무변경이므로 전체 회귀 suite를 새로 실행했다고 주장하지 않는다. [검증 기록](../experiments/optimizer-profile-20261014/verification.json)을 함께 보존했다.

이번 산출물은 병목 규명과 개선 설계다. Production 최적화 구현, GLM 완료, 성능 목표 달성은 다음 검증 작업으로 남는다.
