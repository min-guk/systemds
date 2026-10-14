# 실제 ML LogReg·GLM planning timeout 근본 원인 — 2026-10-14

## 1. 결론

**실제 workload의 timeout은 Oracle/source-only cost projection이 아니라, exact optimizer의 cyclic-core 탐색과 그 탐색 안에서 반복되는 forest-tail 동적 계획법(DP)에 집중돼 있다.** primitive 비용·합법성 결과를 캐시해도, subtree message/비용 합산 결과를 재사용하지 않아 잔여 하위 구조를 호출별로 다시 계산한다.

C26 production 소스는 변경하지 않았다. 기존 56조건 평가와 별도로, 사용자가 요청한 원인 진단을 위해 **실제 LogReg W3와 GLM W3를 각각 한 번**, 같은 Docker/engine/DML/config/자원에서 실행하고 live thread dump 및 60초 JFR을 수집했다. 이 진단 시간은 공식 성능 재측정이나 개선 결과가 아니다. 두 진단도 coordinator 300초에서 종료됐다.

## 2. 실측

| 지표 | LogReg W3 | GLM W3 |
|---|---:|---:|
| main execution samples | 4925 | 5099 |
| `enumerateCore` 포함 samples | 4925/4925 | 5099/5099 |
| `tryForestTail` 포함 samples | 4804/4925 (97.54%) | 4741/5099 (92.98%) |
| main 임시 객체 할당량 (counter delta) | 130.969 GB / 59.973s | 112.691 GB / 59.979s |
| main 할당률 | 2.184 GB/s | 1.879 GB/s |
| GC 회수 / pause 합 | 504 / 0.262s | 280 / 0.196s |
| GC 후 sampled heap 최댓값 | 47.22 MiB | 95.40 MiB |
| JFR DataLoss | 0 | 0 |


- 각 비율은 **프로파일 구간의 메인 스레드 execution sample 비율**이며, 정확한 메서드별 경과 시간이나 전체 300초의 비율이 아니다. inclusive frame 비율끼리는 겹치므로 합산하면 안 된다.
- LogReg thread dump: 163.53초 시점 main CPU 162.498초, 263.46초 시점 CPU 261.708초. GLM: 30.14초 시점 CPU 29.596초, 162.62초 시점 CPU 161.342초. 모두 RUNNABLE이다. 네트워크 대기나 락 대기가 아니라 한 메인 스레드의 CPU 작업이다.
- 메모리는 LogReg 후기 RSS 약 643MiB, GLM 중기 약 886MiB이며 container 제한 8GiB/heap 3GiB보다 작다. GLM container stats는 약 875MiB와 CPU 100.92%를 보였다. GC pause가 작다고 allocation이 무료인 것은 아니다. 객체 생성과 정확 산술의 CPU 비용이 남는다.
- 할당량은 `jdk.ThreadAllocationStatistics` 시작/끝 차이로 계산했다. attach 직후 첫 `ObjectAllocationSample.weight`는 기록 전 누적 할당을 포함하므로 합계에 사용하지 않았다. 예를 들어 LogReg 첫 weight 364,026,944,952 bytes를 60초 할당으로 세면 잘못된 결론이다. class별 weight는 초기 sample을 제외해도 추정치일 뿐이다.
- 최종 search counters는 `optimize()` 반환 뒤에 출력돼 timeout 시 없다. 따라서 실제 방문 노드 수, 완료된 forest DP 횟수, incumbent 발견 시간, bound-prune 비율은 이번 비침습 진단으로 확정하지 않았다.

## 3. 어느 단계인가

관측 경로는 두 workload 모두 다음과 같다.

```text
MemoProgramModel.optimize:435
  MemoOptimizer.optimize:300
    tryLeafRegionSolve:997
      ReducedSearch.run:1191
        enumerateCore:1208 / 재귀 :1231
          tryForestTail:1269–1414
            cached truth/cost 조회 + 정확 BigDecimal 합산/비교
```

`MemoProgramModel.java:435–440`에서 selected-cost audit와 supply reconstruction은 optimizer가 반환한 후 실행된다. live stack은 반환 전 `ReducedSearch`에 있으므로 이 두 후처리를 timeout 원인으로 지목할 수 없다. 초기 constructor/propagation/normalization이 완료되어 탐색에 진입했다는 것도 확인했다.

이번 진단의 Facts+Rules+Model은 LogReg **1.590초**, GLM **3.169초**다. 생성량은 각각 publishedRules **595/1289**, primitiveValues **38198/49861**이고, Oracle 요청은 **155/454**다. 300초 대부분을 이 앞단에 귀속시키는 설명은 맞지 않는다.

## 4. 소스 구조상 근본 문제

### 4.1 leaf 제거 후 남은 core는 여전히 조합 탐색

`MemoOptimizer.java:883–910`은 degree≤1 leaf를 exact tree message로 제거한다. 그러나 scope arity>2인 factor의 owner는 core에 pinned되고(`1003–1008`), 나머지 cyclic core는 owner 값을 하나씩 고정하는 재귀 탐색을 한다(`1196–1233`). source 조합을 저장하지 않는 RuleMemo 설계가 **전역 rule 선택 조합 자체를 제거하는 것은 아니다.**

관측된 thread dump에는 LogReg `enumerateCore` frame **17개**(재귀 호출 16개), GLM **51개**(재귀 호출 50개)가 있었다. 이는 해당 경로에서 여러 owner 선택이 중첩됐다는 증거다. 총 core owner 수나 방문 조합 수는 아니며, `2^16/2^50`개의 조합을 실제 방문했다는 주장이 아니다.

### 4.2 forest가 된 잔여 문제를 매번 새로 푼다

`enumerateCore:1208`은 bound prune을 통과한 각 상태에서 `tryForestTail()`을 시도한다. 이 함수는:

1. active owner, parent, traversal order를 다시 생성한다(`1269–1294`).
2. 모든 core check/term을 constant/unary/edge로 다시 분류한다(`1301–1309`).
3. child edge와 children 배열을 만든다(`1310–1325`).
4. 새 `TreeCell` 배열을 만들고 각 owner rule 및 parent-rule×child-rule을 다시 순회한다(`1342–1397`).
5. 정확 비용을 합산·비교하고 최적 child rule을 복원한다.

이 `tailCells`는 호출 지역 결과이며, 다음 core assignment에서 재사용하는 component-message cache가 없다. 따라서 외부 선택이 일부만 달라져도 전체 잔여 forest를 다시 계산한다. **탐색 상태 수 × forest DP 비용**이 곱해지는 구조가 주된 확장성 문제다. 다만 완전히 동일한 component/separator/domain key가 얼마나 재발하는지는 미측정이며, message cache의 실제 hit율이나 시간 절감은 아직 검증되지 않았다.

### 4.3 cache hit여도 반복 DP 비용은 사라지지 않는다

`evaluate:715–724`와 `evaluateCost:863–873`에는 tuple cache가 있다. 하지만 조회 전 truth/cost class tuple을 구성하고, hash/equality를 수행한다. hit 이후에도 `tryForestTail:1361,1381–1390`의 비용 합산·최소값 비교는 반복된다.

비용은 `new BigDecimal(double)`로 binary64의 정확한 값을 보존한다(`1758–1772`). 합산·비교에서 `BigDecimal/BigInteger/int[]` 등 임시 객체가 반복 생성된다. 문제는 **정확 비용 semantics가 잘못됐다는 것이 아니라, 동일 하위 DP 계산을 너무 많이 반복한다는 것**이다. double/반올림으로 바꿔 정확성을 희생하는 해결은 허용되지 않는다.

### 4.4 B&B가 없는 것은 아니다

`1205–1207`에 incumbent 기반 prune이 있고, `1427–1445`는 owner-message minima 및 factor minima를 합친 lower bound를 사용한다. `1546–1563`에는 conditional minima 및 넓은 factor의 certified minimum relaxation도 있다.

이 bound가 실제 full workload에서 충분히 강하지 않을 가능성은 있지만 **prune 비율을 실측하지 않았으므로 원인을 그것 하나로 단정하지 않는다.** 확정된 것은 prune 후에도 반복 forest 계산에 CPU가 집중된다는 점이다. 내부 default searchNodes는 `Long.MAX_VALUE`(`107–112`)여서 exact search가 끝나기 전에 외부 300초 제한이 먼저 종료한다. timeout은 INFEASIBLE 증명이 아니다.

## 5. 다른 원인과의 구분

| 가설 | 이번 증거에 따른 판정 |
|---|---|
| Oracle 요청/캐시 canonical key가 주병목 | 앞단 Rules가 수 초 내 완료. 현재 timeout의 직접 원인 아님 |
| source 조합을 비용 primitive로 전개하는 앞단 폭발 | Model 완료 및 primitive 카운터 확보. 현재 주병목 아님 |
| `movementGroupCost`, `visitBindingCostContexts`, context legality callback 반복 | LogReg에서는 해당 callback frame이 없고 GLM에서는 movementGroupCost 6/5099(0.12%), visitBindingCostContexts 3/5099(0.06%) samples만 관측. tuple cache 조회는 훨씬 빈번. 전체 실행 비용이 0이라는 의미는 아님 |
| selected-cost audit / supply reconstruction | optimizer 반환 전이므로 현재 정체 위치 아님 |
| heap 부족/OOM/GC stop-the-world | 낮은 retained heap 및 짧은 GC pause. 현재 timeout을 설명하지 못함 |
| training 실행/worker RPC | compile-only planning이며 training을 실행하지 않음 |
| exact global search + forest DP 재계산 | live stack, JFR, 소스가 모두 지지. 높은 확신 |

## 6. 왜 이전 10초 이내 결과와 달랐나

이전 C26 결과 LogReg **3.028초**, GLM **5.072초**는 192×8, W3 tiny E2E였다. 실제 평가 입력은 50000×128, **Y도 federated PUBLIC**이며 전처리·reg·iteration/tolerance 옵션도 다르다. 같은 production 코드라도 동일 planning 문제가 아니다. 앞선 작은 입력의 성능을 full training workload 성능으로 일반화한 것은 검증 범위 오류였다.

full의 publishedRules/primitiveValues가 tiny보다 압도적으로 큰 것도 아니다(LogReg 595/38198 vs tiny 772/66132; GLM 1289/49861 vs tiny 1282/49892). **단순 rule 수보다 잔여 factor graph의 연결 구조, domain, 비용 및 pruning이 중요하다.** 큰 N 자체, Y federated placement, regularization, loop-frequency 중 어느 입력 차이가 가장 결정적인지는 ablation 없이 특정하지 않는다. max iteration은 계획의 빈도/비용 입력이지 이번 진단에서 training loop를 실제 돌렸다는 뜻이 아니다.

## 7. 정확성을 보존하는 개선 방향

이번에는 구현하지 않았다. 개선 우선순위는 다음과 같다.

| 우선순위 | 구현 위치 / 방향 | 기대 효과 | 정확성 위험과 검증 |
|---|---|---|---|
| 1 | `ReducedSearch.tryForestTail`: component message 재사용. component identity + **완전한 separator assignment와 관련 live-domain identity**를 key로 사용 | outer prefix가 달라도 같은 잔여 하위 문제의 DP 재계산 제거. 구조적 반복이 제거될 때 가장 큰 효과 기대 | whole-prefix 또는 shape-only key 금지. 변경된 고정 factor/도메인·UNKNOWN·tie-break를 빠뜨리면 잘못 합쳐짐. cache OFF/ON exact optimum/selected rules/원비용 audit 동등성 검사 |
| 2 | normalization 이후 factor incidence/배치 사전 계산. 탐색에서는 변경 owner에 의존하는 component만 갱신 | global 순회·TailFactor·배열 재생성 감소 | 활성 도메인/고정 선택은 동적임. 모델 불변 정보와 선택 의존 정보를 분리하고 원 predicate 경로와 비교 |
| 3 | normalized unary/binary factor-local truth/exact-cost table. live class tuple 직접 조회 | 반복 hash/equality/임시 tuple 구성 감소 | 모든 결정 입력을 반영한 기존 정확한 class identity만 사용. UNKNOWN도 그대로 보관. 넓은 factor를 무제한 Cartesian 사전 전개하지 않음 |
| 4 | 1–3 이후에도 core 탐색이 크면 independent component 분해 및 separator 기반 exact elimination/tree decomposition | 불필요한 component 간 조합 곱 제거. 가능하면 복잡도를 core 전체 조합 대신 separator 폭에 맞춤 | 공유 movement/retention/privacy factor로 연결된 component를 임의로 분리하지 않음. 모든 check/cost scope 반영 및 lexicographic 최적해 동등성 확인 |
| 5 | 반복 제거 이후 exact dyadic 비용 표현/합산 개선 검토 | 남은 BigDecimal/BigInteger 산술·할당 감소 | double 반올림 또는 `BigDecimal.valueOf`로 semantics 바꾸기 금지. binary64 term의 정확 합과 비교를 전 선택/경계값에서 검증 |

**작은 객체 최적화나 Oracle dedup만으로 full workload를 10초로 만들 수 있다고 주장할 근거는 없다.** 다음 구현의 첫 목표는 forest DP 호출/셀 계산 횟수 감소이며, 노드 수·incumbent·prune·separator 폭 counters를 별도 관측해 개선 효과를 확인해야 한다. 일반적인 exact 문제의 최악 시간 보장은 유지할 수 없으므로 3초/10초는 반드시 봉인된 실제 workload에서 검증해야 한다.

## 8. 재현·검증

- Frozen C26 engine: `/home/mchoi/fedplanner-under3-20261014/experiments/memo-under3-20261014/candidate26-engine`.
- Sealed stage: `/home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z`.
- pinned Docker image `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`, 4 CPU/8GiB, Java heap 3GiB/ActiveProcessorCount4, nativeBLAS none, codegen false, C26 default costs, compile-only.
- runner command 및 live attach 절차: [재현 명령](evidence/full-workload-timeout-2026-10-14/reproduce.md).
- 측정 JSON: [LogReg](evidence/full-workload-timeout-2026-10-14/logreg-profile.json), [GLM](evidence/full-workload-timeout-2026-10-14/glm-profile.json). 카운터·로그·thread dump·DML/config는 같은 evidence 디렉터리에 보존했다.
- 원 JFR 및 전체 exporter output: `experiments/full-workload-timeout-20261014/`의 provenance에서 경로/해시를 확인한다. JFR은 live attach라 초기 단계 전체를 담지 않는다.
- `src/main`/`src/test`/하네스 production 변경 없음. 분석/문서만 추가했으므로 알고리즘 테스트를 새로 통과했다고 주장하지 않는다. 이전 회귀 결과와 현재 profiling을 혼동하지 않는다.
- W1/5/7 timeout은 이전 56조건 평가의 증거다. 이번 세부 profiler는 W3 두 건만 수집했으므로 W1/5/7의 내부 분포를 동일하다고 단정하지 않는다.

후속 비교는 동일 봉인 입력/비용/worker/resource로 baseline과 candidate를 각각 한 번 실행하고, optimizer 완료·OPTIMAL·정확 비용 audit·full initial/compile 시간·UNKNOWN/DEFER/privacy/worker binding·선택 결과 및 회귀 테스트를 함께 확인해야 한다. profiler 시간과 비계측 공식 시간은 별도 표로 유지한다.
