# LogReg 현재 병목과 정확성을 유지하는 solver 개선

**구현과 검증을 완료했다. 동일 Docker/JFR 조건의 각 1회 실행에서 LogReg optimizer는 34.797→23.714초(31.85% 감소), 전체 Memo planning은 37.330→26.819초(28.16% 감소)였다.** Backtracking 후 동일 후보 집합에 대한 실제 support 탐색을 38,286,830→171,750회로 줄였고, 최적 비용·탐색 노드 수·출력 CSV는 동일했다. 다만 JVM 메모리 HWM은 16.61% 증가했다.

## 범위와 측정 기준

사용자 요청은 현재 LogReg의 실제 병목 확인과 해결이다. 기존 독립 workspace `fedplanner-oracle-cost-20261013`에서 이전 최적화를 보존하고 진행한다. 원본 `fedplanner-memo-20261013`은 수정하지 않는다. 이전 구현과 결과는 [불변 분석 재사용 보고서](FEDPLANNER_OPTIMIZER_REUSE_IMPLEMENTATION_RESULT_2026-10-14_KO.md)에 있다.

이번 baseline은 `experiments/optimizer-reuse-20261014/after-engine`의 동결된 소스와 클래스다. Git HEAD만으로는 이전 미커밋 최적화를 나타낼 수 없다. 공식 Docker wrapper로 LogReg before/after를 **JFR 사용 조건에서 각각 1회** 실행한다. 기존 비계측 29.823초와 이번 JFR 시간을 직접 비교하지 않는다. GLM이나 별도 비계측 workload는 이번에 실행하지 않는다.

## 원인: backtracking으로 같은 상태에 돌아와도 support를 다시 계산

수정 전 optimizer 시간은 34.797404037초, 전체 Memo planning은 37.329601749초였다. JFR optimizer 실행 표본은 2,651개이며 다음 비율은 inclusive이므로 서로 더할 수 없다. 정확한 함수별 타이머가 아닌 CPU 실행 경로 표본이다.

| 경로 | 표본 | optimizer 표본 비율 | 해석 |
|---|---:|---:|---|
| `propagate` | 1,030 | 38.85% | 후보별 support 확인 포함 |
| `support` | 934 | 35.23% | support 키 조회와 실제 DFS 포함 |
| `supportDfs` | 516 | 19.46% | 같은 scoped domain의 반복 탐색 후보 |
| `computeConditionalCostBound` | 888 | 33.50% | key 준비 외 movement callback도 포함 |
| `movementGroupCost` | 826 | 31.16% | shape, binding, legality, 비용 평가를 포함 |
| `visitBindingCostContexts` | 616 | 23.24% | 방문 callback 내부의 비용까지 포함 |

소스에서 확인한 직접 원인은 다음과 같다.

1. `support()`의 기존 cache key는 check id, 고정 owner/value와 각 owner의 **revision 번호**였다. `remove()`와 `undo()` 모두 revision을 증가시킨다. 따라서 backtracking으로 후보 집합이 정확히 원래대로 복구되어도 key는 달라져 이미 계산한 support를 재사용할 수 없다. 실제 support cache miss는 **38,286,830회**였다. 반복 가능한 상태라는 사실은 소스로 확인했고, 실제 재사용량은 변경 후 카운터로 검증한다.
2. Conditional bound는 **11,135,047회 조회 중 11,132,120회 hit(99.974%)**였다. 그런데 hit 여부를 알기 전에 scoped domain의 cardinality를 계산하고 모든 후보 ordinal을 배열로 직렬화했다. 같은 비용 하한을 읽는 데 계속 키 배열을 생성했다. `computeConditionalCostBound`의 33.50% 전부가 이 오버헤드라는 뜻은 아니다. 가장 가까운 Memo frame으로 배타 분류한 conditional-bound CPU 표본은 7.70%, 할당 표본 추정량은 5.666GB(14.52%)다.
3. 분기 owner를 선택할 때 `BitSet.cardinality()`를 반복했다. 해당 leaf 표본 264개 중 155개는 `ReducedSearch.enumerateCore`, 69개는 `branchConnectivity` 아래였다. 두 경로의 224개는 optimizer 표본의 8.45%다. 후보 수는 실제 삭제·복구 시에만 바뀐다.

Optimizer 아래의 JFR 가중 할당 추정량은 39.031GB(36.350GiB)였다. GC stop-the-world pause는 0.407초로 34.8초 지연의 주원인이 아니다. 할당·해시·배열 순회 자체의 CPU 비용을 줄이는 것이 대상이다. 메모리 수치는 누적 할당 추정량이며 live heap이나 RSS와 구분한다.

## 수정 설계와 정확성 계약

### 1. 정확한 scoped-domain identity로 support 재사용

동일 optimizer 안에서 key는 `(check id, fixed owner, fixed value, 해당 check의 모든 owner별 BitSet 내용)`이다. Hash는 검색 가속에만 쓰고, 동등성은 전체 BitSet 내용을 비교한다. 서로 다른 후보 하나도 합치지 않는다. Check scope와 predicate는 normalization 사이에 고정되며, normalization 시 기존 cache clear를 유지한다.

`supportDfs`는 해당 domain, 고정 입력, immutable check와 solver에 고정된 support budget의 함수다. 같은 domain은 같은 cardinality, ordinal 순회와 DFS 순서를 재현한다. 따라서 유한 budget 때문에 나온 `UNKNOWN`도 그대로 재사용할 수 있다. `UNKNOWN`을 `YES`나 `NO`로 바꾸지 않으며 기존 `Truth.NO` 제거 조건을 유지한다. Worklist scheduling에 필요한 revision/stamp는 그대로 둔다.

조회 전용 probe는 `Map.get` 동안 live domain을 잠깐 참조하고, evaluator 진입 전에 참조를 해제한다. Cache miss의 저장 key만 BitSet을 복제한다. Mutable probe를 map에 저장하지 않는다. 기존 solver-local bounded cache 크기를 유지하며, cache capacity 0에서도 결과 의미가 같아야 한다.

### 2. 같은 domain probe를 conditional-bound cache에 재사용

별도 bound map에서 `(term id, 모든 scoped domain 내용)`을 비교한다. Cache hit이면 기존의 정확한 `BigDecimal`을 반환하고 ordinal 배열을 만들지 않는다. Miss일 때만 기존 조합 수/budget 검사와 `minimumTerm`을 수행한다. Wide term은 여전히 certified minimum을 사용하며 부분 열거를 정확한 minimum으로 주장하지 않는다.

Cache lookup을 조합 수 검사보다 먼저 할 수 있는 이유는 해당 key의 모든 domain과 solver budget이 동일하기 때문이다. 저장된 bound는 이와 같은 입력에서 이미 기존 검사를 통과하여 계산된 값이다. Term normalization 시 cache를 비우므로 id 재사용으로 다른 식의 bound가 섞이지 않는다. Support map과 bound map도 분리한다.

### 3. 현재 후보 수를 삭제·복구와 함께 유지

Owner별 현재 domain 크기를 초기화하고, 실제 후보 삭제와 trail 복구 시 각각 감소·증가시킨다. Owner 선택의 비교식, tie 순서, rule ordinal 순서, cost arithmetic은 변경하지 않는다. Domain mutation 경로와 backtracking 회귀로 일치 여부를 확인한다.

### 이번에 보류한 수정

`visitBindingCostContexts`의 inclusive 표본에는 visitor의 shape 생성과 movement 비용 계산이 포함된다. 이를 전부 traversal overhead로 해석해 새로운 context cache를 추가하지 않는다. 현재 full context의 privacy, source identity, worker binding, UNKNOWN/DEFER, diagnostic 및 비용 callback 순서를 유지한다. Eager source Cartesian catalog나 합법성 검사 생략은 도입하지 않는다.

## 검증 및 변경 후 결과

구현 후 다음 검증을 통과하고 엔진을 동결했다.

| 검증 | 결과 |
|---|---|
| 수정 전 frozen baseline의 behavior fixture + solver/exhaustive | 30 tests PASS |
| 새 reuse counter를 참조하는 red 검증 | baseline API에 counter가 없어 compile 실패 확인; 기능 실패와 구분 |
| 수정 후 targeted solver/exhaustive (`-ea`) | 30 tests PASS |
| 독립 리뷰 및 별도 solver 회귀 (`-ea`) | APPROVED, 25 tests PASS |
| Maven offline `test-compile` | PASS, 42.981초 |
| 리뷰 후 cardinality assertion 보강의 최종 javac | PASS |
| 최종 Memo/Oracle/privacy/worker/runtime 통합 회귀 (`-ea`) | 47 classes / **426 tests PASS**, 47.326초 |
| 공식 Docker harness parser/test | **45 tests PASS** |
| diff whitespace 및 Python helper syntax | PASS |

테스트 수는 실행 간 합산하지 않는다. 기존부터 실패 상태로 별도 관리한 `TrainingWorkloadOperationCoverageTest`의 stale training inventory golden 5개는 이번 suite에도 포함하지 않았다. 전체 repository test가 모두 통과했다고 주장하지 않는다. 기존 다른 소스의 unchecked/deprecation 컴파일 경고는 남아 있다.

새 3개 회귀는 cache capacity 0/1/4096, backtracking 후 support/bound 재사용, finite support budget, 같은 hash를 가진 서로 다른 BitSet, 정확한 status/cost/선택 결과, lexicographic tie 및 wide-bound 횟수를 확인한다. 기존 randomized exhaustive fixture와 normalization/UNKNOWN/RESOURCE_EXHAUSTED 회귀도 실행했다. 모든 domain mutation의 cardinality assertion을 `-ea`로 활성화했다.

동결한 `experiments/logreg-bottleneck-20261014/after-engine`은 12,498개 파일이며 `after-engine-sha256.json`에 해시를 기록했다. 이전 frozen source와 비교한 이번 변경은 다음 세 파일뿐이다.

- `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java`: 정확한 support/bound 재사용, 후보 수 유지, 통계.
- `src/main/java/org/apache/sysds/hops/fedplanner/memo/FederatedPlanMemo.java`: 기존 trace에 support cache hit 수 추가.
- `src/test/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizerDomainCacheTest.java`: 신규 회귀 3개.

### 변경 전후 시간·자원

| 지표 | Before | After | 변화 |
|---|---:|---:|---:|
| **Memo optimization** | **34.797404037초** | **23.714206977초** | **−11.083초 / −31.85%** |
| **전체 Memo planning** | **37.329601749초** | **26.819446540초** | **−10.510초 / −28.16%** |
| Facts | 0.055369806초 | 0.057448992초 | +0.002초 |
| Rules | 1.597656967초 | 2.100982921초 | +0.503초 |
| Model | 0.820056605초 | 0.892350329초 | +0.072초 |
| Projection | 0.059114334초 | 0.054457321초 | −0.005초 |
| SystemDS compilation | 38.704098초 | 28.121125초 | −27.34% |
| SystemDS execution | 2.811초 | 3.034초 | +0.223초 |
| Coordinator 관측 구간 CPU | 63.98 CPU초 | 51.61 CPU초 | −19.33% |
| Optimizer JFR 가중 할당 추정 | 39.031GB | 30.120GB | −22.83% |
| JVM GC STW 합계 | 0.406651초 | 0.368887초 | −0.038초 |
| Coordinator RSS 관측 최대 | 652,276KiB | 758,020KiB | +16.21% |
| Coordinator HWM 관측 최대 | 673,780KiB | 785,668KiB | +16.61% |

Planning은 Facts/Rules/Model/Optimization/Projection을 포함하고 compilation의 일부이므로 각 시간을 서로 더해 전체 절감량으로 계산하면 안 된다. 성능 개선은 optimizer/planning에 해당한다. Runtime execution 자체가 빨라졌다고 주장하지 않는다. 변경하지 않은 Rules 시간이 0.5초 증가한 것처럼 단일 측정의 다른 단계 변동도 함께 공개한다.

CPU는 같은 coordinator JVM을 `/proc`으로 관측한 before 42.685초/after 32.189초 구간의 누적 user+system tick 차이다. `CLK_TCK=100`으로 환산했으며 optimizer만의 CPU가 아니다. 할당 추정량은 줄었지만 RSS/HWM은 늘었다. 살아 있는 cache key, JVM heap/JIT/기타 native 메모리의 개별 기여는 이번 계측만으로 분리할 수 없다. 메모리 사용도 감소했다고 표현하지 않는다.

### 반복 계산 제거를 확인한 카운터

| 지표 | Before | After | 의미 |
|---|---:|---:|---|
| `supportQueries` | 38,286,830 | 171,750 | 전후 모두 cache miss 후 DFS 실행, **99.551% 감소** |
| `supportCacheHits` | 미계측 | 38,226,419 | 새 명시적 hit counter; before를 0으로 가정하지 않음 |
| Truth cache hit | 82,050,731 | 286,080 | 반복 support DFS 내부 조회 자체가 줄어듦 |
| Check evaluator 실행 | 60,690 | 60,227 | 결과 재사용으로 463회 감소 |
| Conditional-bound 조회 | 11,135,047 | 11,135,047 | 같은 탐색에서 동일한 bound 요청 |
| Conditional-bound hit | 11,132,120 | 11,132,120 | hit 의미 유지, 준비 비용 감소 |
| Cost evaluator 실행 / cache hit | 4,828 / 3,407 | 4,828 / 3,407 | 정확한 cost evaluator 호출 수 유지 |
| Worklist 방문 | 13,616,123 | 13,616,123 | 합법성 propagation scheduling 유지 |
| 후보 제거 | 21,182,609 | 21,182,609 | 기존 제거 판단 유지 |
| Bound prune / wide relaxation | 2,466,239 / 844 | 2,466,239 / 844 | pruning 및 certified-bound 동작 유지 |

After support 요청 총계는 38,398,169회이며 그중 99.553%가 정확한 내용 일치로 재사용됐다. 따라서 개선은 후보 검사를 임의로 생략하거나 탐색 공간을 줄인 결과가 아니다. 이미 확인한 **같은 입력에 대한 같은 결과**를 기존 bounded cache에서 돌려주는 효과다.

### 결과 동등성

- 양쪽 `OPTIMAL`, search nodes **2,933,891**, native rules **920**, selected supplies **1**.
- Exact objective는 소수 문자열 전체가 동일하다.

```text
1301.1526531142989790221751587629165844428591469750244868919253349304199218750
```

- FED model CSV는 양쪽 332 bytes로 **byte 동일**: SHA-256 `c1afd1422eb05ef396bbc13b48fbd09d5966995de43d9f92147ebb1599d5b848`.
- CP/FED 최대 절대 오차는 양쪽 `1.6653345369377348e-16`이다.
- FED fingerprint, runtime audit 위반 목록, action diagnostics가 동일하다. 위반은 없다.
- Oracle/native construction과 shape reuse 카운터도 동일하다. Oracle 요청 1,057회, cache hit 0회/miss 1,057회, prepared request 8,880회/reuse 7,823회를 유지한다. 이번 수정은 해당 입력 생성 경로를 변경하지 않는다.

### After 프로파일과 남은 비용

After optimizer 표본은 1,696개다. 같은 방법의 JFR에서 support 경로는 **934→333표본**, support DFS는 **516→153표본**, propagation은 **1,030→388표본**으로 줄었다. 새 `cachedDomains`는 229표본(13.50%)이며 정확한 BitSet hash/equality 조회 비용은 여전히 존재한다. 이전 `cached`가 438→1표본이 되었다고 해서 모든 cache 비용이 사라졌다고 해석하면 안 된다. 새 메서드의 비용을 포함해 비교해야 한다.

새 domain probe/hash/equality 메서드를 같은 분류에 포함한 배타 cache/key/domain 표본은 546→244개(−55.3%)이며 가중 할당 추정은 4.375→0.844GB다. 직접 support 분류의 할당 추정은 5.711→0.006GB다. 표본 범주별 추정치이며 전체 프로그램의 정확한 객체 생성 수는 아니다.

남은 가장 큰 비용 경로는 movement/context 평가다. `movementGroupCost`는 711표본(41.92%), `visitBindingCostContexts`는 543표본(32.02%), `coreLowerBound`는 923표본(54.42%)이며 서로 중첩된다. Movement의 절대 표본은 826→711로 줄었지만, support 비용이 더 크게 줄면서 비율은 증가했다. 이 비율만 보고 movement가 악화됐다고 판단하지 않는다.

Conditional-bound inclusive 표본은 888→763이며 여기에는 movement evaluator가 포함된다. 가장 가까운 Memo frame의 conditional-bound 할당 추정은 5.666→5.544GB로 크게 줄지 않았다. 이 범주는 key뿐 아니라 `minimumTerm` 등의 작업도 포함하므로, before의 5.666GB 전체가 key 배열이었다거나 이번에 모두 제거됐다고 주장할 수 없다. 이번 한 번의 묶음 변경은 support 재사용·bound probe·cardinality 유지 각각의 독립 speedup을 분리하는 ablation도 아니다.

추가 개선을 한다면 `MemoProgramModel.movementGroupCost` 안에서 **plan 선택과 무관한 immutable context projection**을 재사용하는 것이 다음 검토 대상이다. 현재 selection/worker layout, privacy, activation 및 exact primitive를 결합하는 계산은 계속 실행해야 한다. DIRECT/IMPOSSIBLE gate 뒤에서 기존 방문 순서대로 처리하고, 성공적으로 완료된 결과만 저장하며, UNKNOWN/DEFER/diagnostic과 exception 순서를 보존해야 한다. 새로운 cache가 추가하는 복잡성과 메모리를 고려해 이번에는 적용하지 않았다.

GLM timeout과 기존 stale training golden은 이번에 해결했다고 주장하지 않는다. LogReg의 확인된 중복 support/키 준비/후보 수 계산은 개선했고, 남은 정확한 탐색과 movement 평가 자체는 계속 필요하다.

## 재현 근거

- 계획: `experiments/logreg-bottleneck-20261014/implementation-plan.md`
- Before 실행 명령: `experiments/logreg-bottleneck-20261014/logreg-bottleneck-before-command.txt`
- Before timer/counter/JFR/CPU/RSS 요약: `experiments/logreg-bottleneck-20261014/logreg-bottleneck-before-summary.json`
- After 실행 명령·요약: `experiments/logreg-bottleneck-20261014/logreg-bottleneck-after-command.txt`, `logreg-bottleneck-after-summary.json`
- 전후 비교: `experiments/logreg-bottleneck-20261014/before-after-comparison.json`
- 회귀 명령·로그: `experiments/logreg-bottleneck-20261014/final-unit-command.txt`, `final-unit.log`, `final-harness.log`
- 최종 독립 리뷰·검증 요약: `experiments/logreg-bottleneck-20261014/independent-review.md`, `verification.json`
- 이번 변경만의 소스 diff/해시: `experiments/logreg-bottleneck-20261014/task-main.diff`, `task-source-changes.json`
- Raw JFR: `/grid/3/cofee-lm-sweep-mchoi-20260914/fedplanner-oracle-cost-docker-20261013/logreg-bottleneck-before-01/cases/ml_logreg/fed.jfr`
- After raw JFR: 같은 output root의 `logreg-bottleneck-after-01/cases/ml_logreg/fed.jfr`
- Source 분석: `experiments/logreg-bottleneck-20261014/solver-audit.md`, `movement-audit.md` — 당시 우선순위 가설이며 최종 범위는 이 문서의 fresh 표본 및 구현 검증을 따른다.

단일 before/after는 이 조건에서 관측한 결과다. 분산이나 일반적인 speedup을 추정하지 않는다. JFR allocation weight와 CPU 표본은 추정값이며 정확한 method timer/할당 byte 계측으로 표현하지 않는다.

두 실행의 Docker image는 `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`이며 4 CPU / 8GiB / worker 3 / network none / case timeout 300초다. 동일한 공식 `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e --profile-jfr`를 사용했다. 실행 명령 파일이 frozen sources/classes/lib 경로까지 포함하므로 재현 시에도 현재 `target`으로 대체하지 않는다. 이번 작업의 실제 workload 실행은 before/after 각 1회뿐이다.

양쪽 harness의 실제 frozen input은 지정한 main/test source 및 class와 모두 diff 0이고 class preflight를 통과했다. After engine 12,498개 파일의 전체 manifest 검증도 missing/extra/mismatch 0이다. 두 JFR 모두 parser PASS/data-loss 0이며 측정 container와 monitor는 종료했다.
