# COFEE Memo Global/Local 구현·검증 보고서

작성일: 2026-10-15. 기준 작업 공간: `/home/mchoi/cofee-tree-dp-20261014`, branch `feature/memo-tree-dp-certified-20261014`. 출발점은 `origin/main`의 `b8623e3481072940c07ef3962bcd55be526f1d09`이며, 그 위의 기존 독립 Tree DP 구현과 설계 v2를 이어서 작업했다.

후속 cluster 하한·메모리 admission·factor 진단 개선과 최신 반복 실험은 [후속 개선 및 검증 보고서](COFEE_MEMO_DUAL_MODE_IMPROVEMENT_REPORT_2026-10-15_KO.md)에 정리했다. 이 문서는 첫 frozen engine의 구현·측정 기록으로 보존한다.

## 1. 구현 결과와 판단 범위

`MEMO_GLOBAL`과 `MEMO_LOCAL`을 독립적으로 선택할 수 있도록 구현했다. 두 모드는 기존 Memo의 exact tree DP와 class-aware variable elimination(VE)을 공유한다. 전체 문제가 저렴하게 풀리면 바로 exact로 끝내고, 그 경로가 작업량·메모리 조건에 맞지 않으면 원본 후보 공간을 보존하는 인증 탐색으로 전환한다. Local의 근사 성공 조건은 정확히 `LB > 0 && UB <= 1.05 * LB`다. Global은 exact 증명이 필요하다.

기존 `EXACT`, `TREE_DP_GLOBAL`, `TREE_DP_LOCAL` 선택 경로와 `COMPILE_MEMO`의 기본 알고리즘 `EXACT`는 유지했다. 기존 `MemoOptimizer`에는 공유 kernel을 호출하는 API를 추가했으며 기존 `optimize()`의 탐색 정책·임계값·복원 경로는 유지했다. 후보 생성, runtime legality, privacy, 원본 비용 모델을 완화하지 않았다.

이 보고서는 구현과 실제 검증 결과를 설명한다. [설계 v2](COFEE_MEMO_DUAL_MODE_OPTIMIZER_DESIGN_2026-10-15_KO.md)는 구현 전 의사결정 기록이다. 설계의 모든 연구 확장이나 모든 자원 fault 실험이 끝났다는 뜻은 아니다. 미구현 항목과 검증 한계는 아래에 명시한다.

주 비교에서 새 두 모드는 **3 workload × 7회 × 2모드 = 42/42 인증 성공**, 모두 `OPTIMAL`이었다. LogReg의 Global/Local optimizer 중앙값은 EXACT 대비 18.8%/25.5% 낮았고, GLM은 −2.2%/+7.1%, GNMF는 +28.1%/+27.6%였다. GNMF는 설계의 Global 1.10배 개발 기준을 충족하지 못하므로 **기본값을 승격하지 않는다**. 이는 성능 회귀를 포함한 opt-in 구현 결과다.

## 2. Tree DP, table, cluster를 실제로 어떻게 사용했는가

Tree DP와 table DP는 서로 배타적인 알고리즘 이름이 아니다. Tree DP의 boundary-conditioned message도 table로 저장할 수 있다. 성능은 원본 HOP가 나무처럼 보이는지보다 **모든 비용·합법성 factor를 포함한 separator의 크기, 후보 수, 동치 class 수, message 저장·복원 비용**에 좌우된다. “tree면 언제나 가장 빠르다”거나 “table과 성능 차이가 없다”는 결론은 내리지 않는다.

기존 [ML 구조 계측](COFEE_ML_STRUCTURE_AND_SEED_AUDIT_2026-10-15_KO.md)에서는 다음 차이를 확인했다. 이는 이번 반복 성능 측정과 별도로 수행한 같은 출발 모델의 구조 진단이다.

| 구조 지표 | LogReg | GLM | GNMF |
|---|---:|---:|---:|
| raw HOP 입력 graph의 leaf peeling 비율 | 66.5% | 67.6% | 56.1% |
| 전체 cost/check 정규화 후 active 변수 | 80 | 166 | 16 |
| boundary ≤ 1 peeling 변수 | 12 (15.0%) | 21 (12.7%) | 0 (0.0%) |
| 기존 선택 elimination 순서의 최대 separator 변수 수 | 4 | 10 | 3 |
| 최대 class-aware output rows | 441 | 111,132 | 80 |

따라서 tree 영역만 먼저 찾아 모두 처리하는 정책을 강제하지 않았다. 공유 exact kernel이 전체 factor 구조를 정규화하고, tree이면 tree message로, 작은 separator이면 VE로 처리한다. 분기 이후에도 다시 조건부 tree/VE를 시도한다. VE bucket의 separator-conditioned message가 cluster의 exact 요약 역할을 한다. 별도의 HOP tree cluster 객체나 loopy min-sum 반복기를 추가하지 않았다.

현재 증분 재사용은 두 수준이다. 원본 factor의 scoped domain이 그대로이면 그 factor의 하한 cache를 재사용한다. 한 조건부 영역을 exact kernel이 해결하면 그 영역 전체를 탐색 cover에서 닫는다. **서로 다른 분기 사이의 모든 VE 중간 table을 영속적으로 재사용하는 구현은 아니다.** 이 구분은 준비 비용과 메모리 평가에서 중요하다.

## 3. 구현 구성과 두 모드의 실행 경로

| 파일 | 책임 |
|---|---|
| [MemoDualModeOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoDualModeOptimizer.java) | direct dispatch, lazy DFS cover, LB/UB, exact/5% 종료, telemetry |
| [MemoFeasibleSeed.java](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoFeasibleSeed.java) | closed-factor greedy, 조건부 exact repair, 원본 feasible seed 검증 |
| [MemoResourcePolicy.java](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoResourcePolicy.java) | JVM/cgroup headroom과 byte admission, 공유 resident accounting |
| [MemoOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java) | 기존 exact kernel의 additive `optimizeDirect` API, 조건부 domain 처리 |
| [MemoProgramModel.java](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java) | 동일 원본 factor 전달, 최종 check·cost 재평가, 기존 supply reconstruction |
| [FederatedPlanMemo.java](../src/main/java/org/apache/sysds/hops/fedplanner/memo/FederatedPlanMemo.java) | 알고리즘 선택, 옵션, 인증 gate, 기존 HOP·loop 적용 |

공통 흐름은 다음과 같다.

```text
원본 candidate coverage와 domain 확인
  -> direct exact: propagation + normalization + tree/VE
       완료: 원본 전체 check/cost 검증 -> exact 결과
       거절: sound하게 축소된 원본 domain 유지
  -> 독립 factor relaxation으로 root LB
  -> 선택적 greedy/repair seed -> 검증된 UB
  -> lazy DFS: 조건부 kernel / 분기 / bound cache / pruning
       Global: exact cost + canonical tie 증명
       Local: exact cost 또는 positive-LB 5% 증명
       자원·UNKNOWN: 명시적 미인증 결과, 계획 적용 거절
```

Global도 탐색 경로에서는 pruning과 종료 증명을 위해 bounds를 사용한다. 다만 **direct exact 경로에는 seed, 탐색 frame, 반복 relaxation, 중간 gap 검사를 만들지 않는다.** 따라서 “Global은 중간 검사를 빼면 더 빠르지 않나”라는 질문은 전체 경로에 대한 일률적인 선택이 아니라 direct kernel과 탐색 controller를 분리하는 방식으로 반영했다. 인증 탐색의 Global과 Local은 같은 엔진을 사용하며 종료 기준과 동률 계약이 다르다.

direct 경로를 선택하는 `directCells` 기본값은 2,000,000이다. 이는 전체 solver의 종료 예산이 아니라 VE 경로 선택 기준이다. tree는 별도의 구조·메모리 판단을 거친다. 기준을 넘으면 탐색을 계속한다. 기본 모드에 시간 제한이나 실용적인 고정 node 제한은 두지 않았다.

## 4. 원본 문제와 인증의 근거

### 4.1 문제와 UB

각 owner의 원본 rule ordinal을 `x_i ∈ D_i`로 둔다. 원본 목적함수는

`F(x) = Σ_i native_i(x_i) + Σ_f cost_f(x_scope(f))`

이며 모든 원본 `Check`가 `YES`인 완전 assignment만 feasible이다. 함수·branch·loop·공유 producer·movement의 의존성은 `MemoProgramModel`이 만든 원본 factor scope와 비용 ownership을 그대로 따른다. 공유 비용을 HOP 입력 edge마다 복제하지 않는다.

UB에는 완전 assignment의 전체 checks가 `YES`이고 전체 원본 비용을 다시 계산한 경우만 등록한다. solver 안에서 검증한 뒤 `MemoProgramModel.optimizeDualMode`에서도 다시 검사한다. 인증되지 않은 RESOURCE/UNRESOLVED 결과의 incumbent는 진단 자료일 뿐 계획으로 적용하지 않는다.

### 4.2 한 영역의 LB

domain box `R = Π_i D_i^R`에 대해 다음 독립 relaxation을 사용한다.

`L(R) = Σ_i min_{r∈D_i^R} native_i(r) + Σ_f l_f(R)`

작은 factor는 해당 scope의 현재 domain 또는 인증된 cost class 대표를 완전히 열거하거나, `certifiedMinimum()`에 도달하면 최소값을 확정한다. 열거 작업량이 `factorCells`를 넘으면 factor가 제공하는 `certifiedMinimum()`을 사용한다. class는 해당 factor의 비용 동치에만 사용하며 원본 rule 선택이나 외부 factor의 구분을 합치지 않는다. legality 제약을 relaxation에서 생략하는 것은 feasible 집합을 넓히므로 LB를 높이지 않는다.

모든 feasible `x ∈ R`에 대해 각 항의 하한이 해당 비용 이하이므로 `L(R) ≤ F(x)`다. 음수·상수 항도 그대로 더한다. 열거 도중 본 최소값은 하한으로 공개하지 않는다. 정확한 비용 표현과 비교는 기존 모델의 `BigDecimal` 계약을 따른다. ε 비교에 임의 tolerance를 더하지 않는다.

### 4.3 탐색 cover와 전역 LB

분기는 `R`을 한 rule을 고정한 child와 나머지 sibling의 서로 겹치지 않는 합으로 나눈다. child를 만들기 전에 둘의 표현을 확보하고, pending parent가 아직 만들지 않은 모든 sibling을 계속 대표한다. 각 child/sibling bound는 `max(부모 LB, 새 relaxation LB)`로 설정한다. 같은 영역에 대한 두 유효 하한의 max도 유효하다.

전역 LB는 남은 pending 영역, UNKNOWN 영역 요약, incumbent 비용의 **최소값**이다. 이는 전체 대안 중 아직 더 싼 해가 있을 수 있는 부분을 모두 포함한다. 이미 닫은 영역은 infeasible, exact 해결, 또는 incumbent보다 나을 수 없다는 증거가 있다. 따라서 유한한 UB가 존재하는 동안 `LB ≤ OPT ≤ UB`가 유지된다. UB를 아직 찾지 못한 상태의 `null`은 수학적으로 `+∞`/미확보를 뜻한다.

한 check의 모든 인자가 고정됐는데 UNKNOWN이면, 그 check와 무관한 변수 조합을 계속 열거하지 않고 해당 영역의 하한을 unresolved 요약에 보존한다. UNKNOWN을 YES로 바꾸지 않는다. 열린 candidate coverage는 시작 단계에서 UNRESOLVED다.

Global은 `LB=UB`만으로 더 작은 canonical 동률 rule vector가 없다고 가정하지 않는다. 원본 ordinal의 lexicographic floor와 pending 영역을 추가 검사한다. Local의 OPTIMAL은 최적 **비용**의 증명이며 Global과 같은 canonical tie를 보장하는 계약은 아니다.

### 4.4 종료 조건

| 결과 | 필요한 증거 |
|---|---|
| Global `OPTIMAL` | feasible UB, exact 비용 및 canonical tie 종료 |
| Local `OPTIMAL` | feasible UB와 exact 최적 비용 증명 |
| Local `EPSILON_OPTIMAL` | feasible UB, `LB>0`, `UB≤1.05×LB` |
| `INFEASIBLE` | 전체 원본 영역에 feasible assignment가 없다는 증명 |
| `UNRESOLVED` | 열린 coverage 또는 증명을 막는 UNKNOWN |
| `RESOURCE_EXHAUSTED` | byte admission 정책상 필요한 workspace를 허용할 수 없음 또는 명시적 진단 cap; 미인증 성공으로 처리하지 않음 |

양의 LB에서 `UB≤1.05×LB≤1.05×OPT`이므로 독립 optimum 대비 5% 보장도 성립한다. LB가 0 이하이면 상대 gap 조건으로 성공하지 않고 exact 증명이 필요하다. 외부 실험 watchdog은 실패 수집 장치이며 solver의 성공 조건이 아니다.

## 5. Legacy Local 방식의 initial plan 반영

Legacy의 아이디어를 적용하되 “boundary를 보고 각 cluster에서 최적인 선택을 하면 전체 feasible plan이 된다”고 가정하지 않는다. 이번 seed는 현재 선택으로 완전히 닫힌 factor만 계산하는 greedy와 조건부 exact repair로 구성된다.

1. native 비용과 닫힌 cost factor를 평가하며 rule을 선택한다. 닫힌 check가 NO/UNKNOWN인 선택은 seed 후보로 채택하지 않는다.
2. greedy가 dead end에 이르면 관련 factor 연결 block을 만들고 outside assignment를 고정한 조건부 문제를 푼다.
3. 작은 block에서 실패하면 block을 확대한다. 조건부 infeasible을 전역 infeasible로 승격하지 않는다.
4. 공유 kernel 또는 제한된 열거를 사용하되 seed 작업량은 `seedWork` 안에서 관리한다. 완성된 계획의 원본 checks·공유 비용을 전체 재검증한다.
5. 이미 feasible한 greedy seed를 얻으면 즉시 반환한다. 좋은 초기 UB를 만들기 위해 전체 exact 탐색을 무조건 수행하지 않는다.

`seedWork`는 실제 evaluator 호출 수나 실행시간의 상한이 아니다. greedy/열거의 방문 작업을 세고, 공유 kernel 호출 전에는 `변수 수 × domain 크기 곱`의 보수적인 추정 작업량을 차감한다.

seed의 domain은 별도 scratch다. greedy 선택을 proof domain에 고정하지 않는다. seed 작업량 소진·실패 후에도 원본 탐색을 계속한다. repair가 원래의 전체 incoming domain을 exact로 해결했을 때만 전체 증명을 재사용한다. 작은 block의 증명은 해당 조건부 영역에만 적용한다.

## 6. 메모리 정책과 설계 대비 범위

사용자가 요청한 넓은 메모리 활용을 반영해 새 solver의 cache와 resident byte 상한 기본값은 `Long.MAX_VALUE`이며, 실제 JVM heap/cgroup headroom으로 admission한다. 진행·복원 여유분은 heap의 1/8을 기준으로 최소 16 MiB, 최대 256 MiB다. 메모리 한계 직전까지 무조건 채우기보다 다음 할당과 복원이 가능한지를 본다. 유한한 `memoryBytes`를 명시하는 진단 설정에서는 reserve가 0이 된다. admission 거절은 물리적으로 실행 불가능하다는 증명이 아니라 현재 추정치·정책상 허용할 수 없다는 뜻이다.

큰 table이 거절되면 원본 대안을 버리지 않고 DFS 경로로 진행한다. cache와 진단 history는 회수할 수 있고, pending proof state는 삭제하지 않는다. child와 sibling 표현에 대한 예약이 bound cache 삽입에 소비되지 않도록 별도로 유지한다. direct tree/VE 복원은 반복형 stack을 사용한다.

설계의 best-bound frontier→DFS 전환 대신 **처음부터 lazy DFS**를 선택했다. 따라서 별도 priority queue와 frontier-count 제한이 없다. 폭이 큰 frontier를 없애는 장점과, 가장 낮은 bound 영역을 우선 해결하지 못해 Local 인증이 늦어질 수 있는 단점이 있다. node bound 계산은 cache 재사용을 하지만 조건부 kernel 준비 자체는 반복될 수 있다.

byte 추정치는 JVM 실제 점유량의 정확한 측정값이 아니다. 원본 모델, JVM/native 메모리, evaluator의 임시 allocation과 GC 상태도 영향을 준다. controlled kernel allocation 거절은 안전한 전환 대상이지만 임의 JVM OOM이나 OS kill에서 완전한 복구를 보장하지 않는다. 그런 실행은 실험에서 실패로 기록하며 certified 결과로 처리하지 않는다.

## 7. 정확성 및 회귀 검증

clean build로 다음 검증을 수행했다.

```bash
mvn -q clean -Dspotless.check.skip=true -Drat.skip=true \
  '-Dtest=MemoDualMode*Test,MemoFeasibleSeedTest,MemoDirectKernelTest,MemoOptimizer*Test,MemoTreeDp*Test' test
python3 -m unittest discover -s scripts/fedplanner/tests \
  -p test_run_memo_tree_dp_benchmark.py
```

Java **142 tests, 실패·오류·skip 0**, benchmark harness Python **21 tests 통과**다. Java 결과는 [java-test-summary.json](../experiments/memo-dual-mode-20261015/java-test-summary.json)에 보존했고, Python 실행 출력은 [python-test-output.txt](../experiments/memo-dual-mode-20261015/python-test-output.txt)에 저장했다. 전체 프로젝트 test suite·Spotless·RAT를 통과했다고 주장하지 않는다.

| 검증 | 실제 내용 |
|---|---|
| 독립 exact oracle | 작은 Cartesian 전수조사, signed random 30 fixture의 direct/search 경로, canonical tie |
| Local 인증 | random 25 fixture, 정확한 5% 경계와 경계 밖, positive/zero/negative 비용, 모든 공개 bound 점 |
| dependency | diamond·multi-parent·공동 movement·고차 crossing factor, 함수·branch·loop 모델과 reconstruction |
| seed | greedy dead end, 0/작음/큼 seed cap, conditional repair, 원본 domain 보존, full-proof 재사용 |
| 전환·자원 | direct 작업량/메모리 거절 후 exact 탐색, lazy sibling cover, cache 문맥, 깊이 400 복원 |
| unsupported | UNKNOWN, 열린 coverage, infeasible, fixed UNKNOWN과 무관한 `2^50` 조합 미열거 |
| runtime | 작은 federated linear/logistic 학습에서 5개 알고리즘의 결과와 비용 비교 |

독립 correctness lane과 architecture lane이 구현을 검토했다. 검토 중 조건부 restriction 후 이웃 수 갱신 누락, child 예약과 cache 할당 충돌, 이미 resident인 메모리를 headroom에서 중복 차감하는 문제를 수정했다. 최종 clean 테스트와 frozen engine은 수정 이후 버전이다.

독립 source/unit 검증 기준 C01–C21 중 13개는 충족, 8개는 일부 강한 조건이 부분 검증으로 분류됐다. 구체적인 테스트 대응과 남은 검증 공백은 [independent-validation.json](../experiments/memo-dual-mode-20261015/independent-validation.json)에 기록한다. 이 매핑 뒤에 수행한 §9의 작은 heap 실험은 추가 증거다. mid-table allocation fault 주입, 기존 10,000 frontier를 넘기는 별도 fixture, cross-profile 재사용 같은 가장 강한 조건은 여전히 전부 실험하지 않았다. cross-model cache는 현재 인스턴스별로 분리하고, 별도 frontier 전환 자체는 구현하지 않았다. 테스트 통과는 임의 모델·임의 OOM에 대한 완전한 증명을 대신하지 않는다.

## 8. Docker ML 비교 방법

모든 성능 실행은 `run_LAN_docker.sh`를 통해 수행했다. LogReg·GLM·GNMF의 sealed ML 입력, worker 3, 동일 DML bytes, 동일 비용 기본값, 고정 engine을 사용한다. 새 모드는 같은 원본 모델에서 독립 실행한 EXACT 비용과 비교한다. 실제 원본 후보 전체를 열거할 수 있는 작은 문제와 달리, 큰 ML에서는 기존 EXACT가 독립 비교 기준이다.

주 비교는 workload/algorithm 순서를 seed `20261015`로 섞은 **각각 새 JVM 7회**다. 첫 반복에는 기존 독립 Tree 두 모드도 포함했다. 이 두 모드는 진단 node cap 2,000으로 한 번 실행하므로 새 모드의 무제한 기본 실행과 동일 자원 정책의 속도 경쟁으로 해석하면 안 된다. 실패도 모두 보존한다.

container는 4 CPU, 8 GiB, compile JVM heap 3,072 MiB다. image는 `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`로 고정했다. [engine manifest](../experiments/memo-dual-mode-20261015/engine-v1-sha256.json)는 class·test class·라이브러리·source provenance를 보존한다.

`optimization`은 후보·모델 생성 이후 solver와 최종 모델 검증/복원에 해당하는 phase다. `fullCompile`은 더 넓은 컴파일 시간이며 process wall에는 JVM 시작도 포함된다. container memory는 Docker MemUsage sampling의 high-water이고 RSS나 optimizer 전용 live heap이 아니다. solver의 `peakEstimatedBytes`와 구분한다. synthetic의 JVM pool peak 역시 한 프로세스 안에서 앞선 알고리즘의 heap 상태를 포함하므로 독립 알고리즘 메모리 순위로 쓰지 않는다.

### 8.1 주 비교: optimizer 시간

단위는 초이며 괄호는 min–max다. 각 EXACT/MEMO 행은 7회, 기존 Tree 행은 1회다.

| Workload | EXACT | MEMO_GLOBAL | MEMO_LOCAL | Global / EXACT | Local / EXACT |
|---|---:|---:|---:|---:|---:|
| logreg | 1.1183 (0.7270–1.2226) | 0.9079 (0.7653–0.9616) | 0.8332 (0.7767–0.9665) | 0.812× | 0.745× |
| glm | 2.3113 (2.0564–2.6306) | 2.2594 (2.0937–2.4810) | 2.4758 (2.3062–2.8651) | 0.978× | 1.071× |
| gnmf | 0.1768 (0.1587–0.2172) | 0.2265 (0.1831–0.2934) | 0.2255 (0.1755–0.2849) | 1.281× | 1.276× |

새 두 모드의 42회 모두 route=DIRECT, directAttempts=1, seedAttempts=0, nodes=0, relaxationCalls=0, maxPending=0이다. 최종 LB=UB는 같은 반복의 EXACT optimum과 정확히 일치한다. 큰 ML에서 이번 기본 실행은 ε 탐색의 조기 종료 이득을 보여 주는 실험이 아니다. Local도 cheap exact를 바로 선택했다.

| Workload | 최적 비용(표시만 반올림) | direct VE cells | solver peak 추정 MiB |
|---|---:|---:|---:|
| logreg | 4041.331216476 | 14,744 | 2.297 |
| glm | 426.222945173 | 1,360,977 | 25.084 |
| gnmf | 217.047325383 | 3,308 | 1.222 |

LogReg/GLM/GNMF의 원본 X는 50,000×128, private-aggregate, W3 조건이다. 비용은 모델 단위이며 실제 학습 wall time으로 환산하지 않는다.

### 8.2 전체 컴파일과 memory

| Workload | Algorithm | fullCompile 중앙값 s | process wall 중앙값 s | container peak 최대 MiB |
|---|---|---:|---:|---:|
| logreg | EXACT | 4.4219 | 5.6630 | 383.2 |
| logreg | MEMO_GLOBAL | 3.9607 | 5.1794 | 332.4 |
| logreg | MEMO_LOCAL | 4.2865 | 5.6724 | 400.2 |
| glm | EXACT | 9.5743 | 10.9057 | 1109.0 |
| glm | MEMO_GLOBAL | 9.9835 | 11.4999 | 1125.4 |
| glm | MEMO_LOCAL | 10.2271 | 11.4523 | 1026.0 |
| gnmf | EXACT | 1.4931 | 2.7688 | 193.9 |
| gnmf | MEMO_GLOBAL | 1.6624 | 2.9354 | 162.0 |
| gnmf | MEMO_LOCAL | 1.5897 | 2.8504 | 188.9 |

optimizer만 빨라져도 전체 컴파일이 같은 비율로 줄지는 않는다. 예를 들어 GLM Global의 optimizer 중앙값은 약 2.2% 낮지만 fullCompile 중앙값은 9.5743→9.9835초다. 후보 생성·모델 구성 및 독립 JVM 변동이 포함되므로 이 차이를 solver 개선으로 해석하지 않는다. 7회 범위는 제시했지만 신뢰구간이나 유의성 검정은 수행하지 않았다.

### 8.3 기존 독립 Tree 경로: 실패 포함

| Workload | Algorithm | 결과 | optimizer s / 실패 시 process wall s | 최종 gap |
|---|---|---|---:|---:|
| glm | TREE_DP_GLOBAL | RESOURCE_EXHAUSTED | 22.0644 | 82.3704% |
| glm | TREE_DP_LOCAL | RESOURCE_EXHAUSTED | 20.2125 | 82.3704% |
| gnmf | TREE_DP_GLOBAL | OPTIMAL | 0.2098 | 0.0000% |
| gnmf | TREE_DP_LOCAL | EPSILON_OPTIMAL | 0.1692 | 4.6073% |
| logreg | TREE_DP_GLOBAL | RESOURCE_EXHAUSTED | 8.8102 | 108.9183% |
| logreg | TREE_DP_LOCAL | RESOURCE_EXHAUSTED | 7.5654 | 108.9183% |

LogReg·GLM의 기존 Tree 두 모드는 진단 cap에서 RESOURCE_EXHAUSTED로 끝났고, 미인증 계획을 적용하지 않았다. 이 결과는 과거 Tree 구현의 자원 조건을 명시한 재현이며, 더 오래 돌려도 절대 풀 수 없다는 뜻은 아니다. GNMF Tree Local은 certified 5% 조건으로 종료했다.

[전체 원시 summary](../experiments/memo-dual-mode-20261015/comparison-r1/repeat-01/summary.json), [7회 aggregate](../experiments/memo-dual-mode-20261015/comparison-r1/aggregate.json), [실행 argv와 return code](../experiments/memo-dual-mode-20261015/comparison-r1/repetitions.json)를 보존했다. 독립 Decimal 검사에서 기존 Tree 실패 실행을 포함한 **609개 공개 bound 점**의 단조성과 `LB≤OPT≤UB`를 확인했다.

## 9. 탐색·seed·메모리 실험

### 9.1 GNMF: direct VE와 seed의 영향

아래는 각 조건을 한 번 실행한 정책 진단이다. 주 비교의 7회 중앙값과 같은 통계적 의미를 부여하지 않는다. `directCells=0`은 VE를 거절하지만 조건부 tree kernel은 유지한다.

| 설정 | EXACT s | Memo Global s | Memo Local s | Global/Local nodes |
|---|---:|---:|---:|---:|
| directCells=0, seedWork=100000 | 0.2000 | 2.9933 | 2.9385 | 651 / 651 |
| directCells=0, seedWork=0 | 0.1557 | 2.9579 | 3.3114 | 651 / 651 |
| 기본 direct, seedWork=0 | 0.2327 | 0.1832 | 0.2079 | 0 / 0 |

VE를 끄면 새 모드의 optimizer 시간은 약 3초로 늘고 두 모드 모두 651 nodes 후 exact로 끝난다. 이 사례에서 factor-wise LB와 DFS는 5% 조기 종료 이득을 만들지 못했다. seed를 제거해도 nodes와 bound cache hit 수가 같으므로, seed가 이 탐색을 줄였다는 근거도 없다. 기본 direct 경로에서 seedWork=0의 차이는 실행 변동을 포함하며 원래도 seedAttempts=0이다.

seed를 켠 GNMF Local의 분해 계측은 kernel 1.618초, bound 0.435초, seed 0.055초, search 2.748초, 총 optimizer 2.939초다. search에 kernel/bound의 상당 부분이 포함된다. cache hit 617,930회, relaxation 3,477회, 조건부 exact 해결 17회, maxPending 10개, cache 약 1.22 MiB였다. 반복 준비·평가 비용은 cache hit 수만으로 상쇄되지 않는다.

| GNMF Local 강제 탐색 checkpoint | LB | UB |
|---|---:|---:|
| nodes=0 | 130.812119 | 미확보 |
| nodes=0 | 130.812119 | 910.460327 |
| nodes=644 | 189.533432 | 217.047325 |
| nodes=649 | 201.911973 | 217.047325 |
| nodes=651 | 217.047325 | 217.047325 |

GNMF greedy seed 비용은 910.460327로 OPT=217.047325보다 훨씬 높았다. 최종 직전 LB=201.911973에서는 UB가 이미 OPT여도 인증 gap이 약 7.50%이므로 Local이 멈추면 안 된다. 651 nodes에서 exact로 닫힌 뒤 종료했다. 즉 feasible initial plan, 좋은 initial plan, 충분히 강한 lower bound는 서로 다른 성과다.

[direct0 결과](../experiments/memo-dual-mode-20261015/comparison-r1-ablation-direct0-gnmf/summary.json), [direct0+seed0 결과](../experiments/memo-dual-mode-20261015/comparison-r1-ablation-direct0-seed0-gnmf/summary.json), [direct 경로 seed0 control](../experiments/memo-dual-mode-20261015/comparison-r1-ablation-seed0-gnmf/summary.json).

### 9.2 실제 heap 128/256/512 MiB

각 조건은 한 번이며 default solver 설정을 유지했다. 시간은 optimizer 초, OOM 행은 solver phase 완료 전 실패다. 3,072 MiB 비교는 §8에 있다.

| Heap MiB | Workload | EXACT | Memo Global | Memo Local | 새 Global/Local 경로 |
|---:|---|---:|---:|---:|---|
| 128 | glm | OOM | OOM | OOM | 미진입 / 미진입 |
| 128 | gnmf | 0.2113 | 0.2410 | 0.1763 | DIRECT / DIRECT |
| 256 | glm | 2.6247 | 12.2876 | 22.9769 | CERTIFIED_SEARCH / CERTIFIED_SEARCH |
| 256 | gnmf | 0.1780 | 0.1722 | 0.2042 | DIRECT / DIRECT |
| 512 | glm | 2.3638 | 2.7034 | 2.4829 | DIRECT / DIRECT |
| 512 | gnmf | 0.1569 | 0.1860 | 0.2580 | DIRECT / DIRECT |

GLM 128 MiB는 세 알고리즘 모두 공유 후보/shape 준비 단계에서 JVM OOM으로 실패했다. 새 solver만의 탐색 메모리 정책으로 모델 구성 단계의 모든 메모리를 해결했다고 주장하지 않는다. GNMF는 128 MiB에서도 세 모드 모두 exact로 완료했다. 이는 시험한 범위이며 각 workload의 최소 실행 heap을 찾았다는 뜻은 아니다.

GLM 256 MiB에서는 새 Global이 28 nodes 후 exact, Local이 59 nodes 후 EPSILON_OPTIMAL로 끝났다. Local 최종 LB=410.140751, UB=426.222945, 인증 gap=3.921140%다. 독립 EXACT의 OPT도 426.222945이므로 실제 선택 비용의 오차는 0%지만 Local 자체의 증명은 3.921140%까지만 좁혀졌으며 **OPTIMAL로 올려 쓰지 않았다**.

| GLM 256 MiB Local checkpoint | LB | UB |
|---|---:|---:|
| nodes=0 | 354.464650 | 미확보 |
| nodes=0 | 354.464650 | 509.530166 |
| nodes=5 | 397.588602 | 426.222945 |
| nodes=59 | 410.140751 | 426.222945 |

이 작은 heap에서 Local이 Global보다 느렸다는 사실도 유지한다. 두 실행은 동적으로 읽은 실제 headroom에 따라 조건부 kernel admission 경로가 달랐고, 단일 실행의 GC/일정 변동을 통제한 인과 실험은 아니다. “ε 모드이면 항상 더 빠르다”거나 “메모리를 더 사용하면 항상 빨라진다”는 결론은 낼 수 없다. 256 MiB의 EXACT는 직접 완료하므로 현재 새 admission/전환 정책이 이 조건에서 더 느린 대안을 고른 결과다.

[128 MiB](../experiments/memo-dual-mode-20261015/comparison-r1-heap128-glm-gnmf/summary.json), [256 MiB](../experiments/memo-dual-mode-20261015/comparison-r1-heap256-glm-gnmf/summary.json), [512 MiB](../experiments/memo-dual-mode-20261015/comparison-r1-heap512-glm-gnmf/summary.json).

전체 비교·ablation·heap 실행은 **96 compile attempts: 89 성공, 7 실패**다. 실패는 기존 Tree의 진단 cap 4건과 GLM 128 MiB OOM 3건이며 모두 원시 로그를 보존했다. [독립 결과 audit](../experiments/memo-dual-mode-20261015/result-validation.json)은 총 **727개 공개 bound 점**의 단조성과 같은 source의 EXACT optimum 포함 여부, 인증 조건, 성공 계획 비용 일치를 재검사했다. OOM에서 certificate가 생성됐다고 간주하지 않는다.

## 10. Synthetic와 legacy 비교, 실제 학습 실행

warm-up 1회 후 같은 JVM에서 7회 평균을 측정했다. 아래 시간 단위는 ms다. 큰 ML의 cold-process 중앙값과 직접 섞지 않는다.

| Case | EXACT | Tree Global | Tree Local | Memo Global | Memo Local |
|---|---:|---:|---:|---:|---:|
| adversarial-shared-wide | 2.070 | 2.389 | 1.399 | 2.762 | 1.934 |
| tree-32 | 1.920 | 1.341 | 1.204 | 2.020 | 1.765 |
| cycle-16 | 0.866 | 0.323 | 0.310 | 1.159 | 1.102 |
| diamond-8 | 0.369 | 0.304 | 0.282 | 0.653 | 0.664 |
| shared-wide-8 | 0.709 | 0.484 | 0.485 | 0.836 | 0.825 |
| epsilon-boundary-3 | 0.144 | 0.167 | 0.112 | 0.354 | 0.341 |

6개 기본 fixture × 5알고리즘의 30행과 강제 search/seed ablation 12행, 총 42행을 검증했다. 작은 fixture는 독립 전수조사로, tree-32는 기존 Memo EXACT로 optimum을 계산했다. tree-32에서 새 Global은 EXACT보다 약간 느리고, diamond/cycle/shared fixture에서도 새 wrapper가 더 느린 경우가 있다. 작은 문제에서는 준비·검증·메모리 admission 비용이 실제 DP 계산보다 두드러질 수 있다. 이는 가능한 원인 해석이며 단일 원인으로 profiling해 증명한 결과는 아니다.

| 강제 search fixture | Global search ms | Local search ms | Local seed 없음 ms | seed 있음/없음 Local nodes |
|---|---:|---:|---:|---:|
| adversarial-shared-wide | 11.659 | 6.819 | 8.494 | 17 / 19 |
| diamond-8 | 7.573 | 7.937 | 9.593 | 40 / 68 |
| shared-wide-8 | 14.400 | 10.698 | 10.336 | 60 / 61 |
| epsilon-boundary-3 | 1.372 | 0.355 | 0.603 | 0 / 1 |

`epsilon-boundary-3`의 seed 없는 Local은 LB=100, UB=105, 독립 OPT=100에서 정확히 5%를 인증했다. 같은 fixture의 기본 Local은 direct exact로 100을 반환했다. seed가 항상 빠르지는 않다. shared-wide-8에서는 seed가 node를 61→60으로 줄였지만 평균 시간은 10.336→10.698ms로 늘었다. direct를 끈 adversarial fixture에서는 Global이 2.762→11.659ms로 느려졌다.

legacy 비교는 `ExactCategoricalSolver`와 `LocalCategoricalOptimizer`에 **동일 categorical variable/factor 문제**를 전달하는 adapter fixture에서만 수행했다. legacy exact/local, Memo EXACT, 독립 Tree, 새 Global/Local 비용은 모두 1.4375였다. legacy Local은 198 block assignments, 단일 측정 36.274ms다. 이 fixture는 위 표의 같은 이름 adversarial case(OPT=1.1875)와 비용 설정이 다르며 서로 수치를 합치지 않는다. legacy 전체 Regional/Physical production pipeline과 Memo의 실제 ML 목적함수 동치는 증명하지 않았다. 따라서 전체 legacy planner보다 새 모드가 빠르다는 결론은 내리지 않는다.

Docker bridge network에서 작은 federated linear/logistic 학습을 5개 알고리즘으로 실행한 **10/10 runtime 검증**도 통과했다. 수치 결과와 비용을 비교했으며 큰 50,000-row ML의 전체 training 실행은 아니다. 이 runtime 실행은 Java 142개 테스트 외에 frozen engine을 Docker에서 다시 실행한 증거다.

## 11. 선택 옵션과 재현

planner를 `COMPILE_MEMO`로 선택한 상태에서 JVM property로 알고리즘을 지정한다.

```text
-Dsysds.fedplanner.memo.algorithm=MEMO_GLOBAL
-Dsysds.fedplanner.memo.algorithm=MEMO_LOCAL
-Dsysds.fedplanner.memo.trace=true
```

아래 옵션의 접두사는 `sysds.fedplanner.memo.dual.`이다.

| suffix | 기본값 | 의미 |
|---|---:|---|
| directExact | true | 처음 및 조건부 노드에서 exact kernel 사용 |
| directCells | 2,000,000 | VE 선택 작업량 기준; 초과해도 전체 탐색 계속 |
| seedWork | 100,000 | 초기 UB heuristic의 작업 한도; 0이면 생략 |
| factorCells | 256 | factor 하한의 완전 열거 기준; 초과 시 certified floor |
| cacheBytes | Long.MAX_VALUE | evictable bound cache 상한, 실제 headroom admission 적용 |
| memoryBytes | Long.MAX_VALUE | solver byte 추정치에 대한 명시적 상한 |
| diagnosticNodes | Long.MAX_VALUE | 실험용 cap; 도달 시 RESOURCE_EXHAUSTED, 성공 아님 |

trace는 `[MemoDualBound]`의 LB/UB progression과 `[MemoDual]`의 최종 certificate·통계를 출력한다. `searchNanos`는 탐색 내부 kernel/bound 작업을 포함하므로 `kernelNanos`, `boundNanos`와 단순 합산하지 않는다. 기존 synthetic CSV 호환 열 `meanConflictNanos`는 새 모드에서는 searchNanos이며, `meanConflicts`는 별도 conflict 객체 수를 측정하지 않아 0이다. 이 0을 dependency가 없다는 뜻으로 해석하지 않는다.

다음은 작업 공간 root에서 수행하는 주 비교 재현이다. output 경로는 기존 evidence를 덮어쓰지 않도록 새로 지정한다.

```bash
python3 scripts/fedplanner/prepare_memo_tree_dp_engine.py \
  --output /absolute/path/new-engine
python3 experiments/memo-dual-mode-20261015/run_comparison.py \
  --engine /absolute/path/new-engine \
  --stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z \
  --output /absolute/path/new-comparison --repetitions 7
python3 experiments/memo-dual-mode-20261015/summarize_comparison.py \
  /absolute/path/new-comparison
```

engine preparation은 위의 clean Java test/build 후 실행한다. sealed stage의 데이터·metadata가 필요하다. stage와 고정 image를 갖추지 않은 환경에서 이 명령만으로 같은 ML 입력을 복원할 수 있다고 주장하지 않는다. 각 실행의 manifest의 `reproductionCommand`, 각 cell의 `command.json`·`container-run.sh`에 실제 argv·입력 hash·자원 조건을 보존한다.

```bash
scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark \
  --engine experiments/memo-dual-mode-20261015/engine-v1 \
  --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z \
  --output-root /absolute/path/new-search-ablation --workers 3 \
  --workloads gnmf --algorithms EXACT,MEMO_GLOBAL,MEMO_LOCAL \
  --dual-direct-cells 0 --skip-synthetic
```

seed ablation은 여기에 `--dual-seed-work 0`, heap 실험은 별도 실행에서 `--heap-mib 256` 또는 `512`를 지정한다. heap 옵션은 compile JVM에 적용되며 synthetic/runtime JVM은 별도 설정이다.

## 12. 남은 한계와 다음 개선 순서

이번 구현의 의미는 기존 빠른 exact 경로를 공유하면서도 원본 모델의 bounds로 Local의 5%를 인증할 수 있는 선택지를 확보한 데 있다. “HOP tree를 쓰면 ML 최적화가 전반적으로 더 빠르다”는 결론을 뒷받침하지는 않는다.

현재 의도적으로 추가하지 않은 것은 stronger cluster/mini-bucket relaxation, loopy min-sum, best-bound priority queue, 영속적인 조건부 VE message cache다. 어느 것이 가장 빠른지는 전체 인증 시간과 peak memory로 결정해야 한다. 현재 factor-wise LB는 고차 공유 비용과 강한 교차 제약에서 약할 수 있고, DFS의 진행 순서도 Local gap 수렴에 불리할 수 있다.

다음 개선은 (1) direct 경로의 중복 준비·원본 검증 비용 계측, (2) GLM 256 MiB처럼 기존 EXACT는 직접 풀지만 새 admission이 탐색으로 넘기는 경우의 live memory·GC·추정 정확도 분석, (3) 어려운 큰 모델에서 initial UB와 root LB의 차이 분석, (4) 그 차이를 만드는 factor 연결부만 작은 cluster로 묶어 하한 강화, (5) cache 비용을 포함한 인증 총시간 비교 순서가 적절하다. tree-first를 일괄 적용하거나 seed를 더 오래 돌리는 정책은 이번 결과만으로 정당화하지 않는다.

실제 대규모 ML 실험은 **training script의 계획/컴파일**이다. 작은 federated 학습은 실행해 수치 결과를 확인했지만, 큰 세 workload의 전체 학습 시간·수렴·확장성까지 검증한 것은 아니다. legacy와 Memo의 전체 프로그램 목적함수가 동치라는 증명도 없으므로 실제 ML의 비용 우열을 서로 같은 OPT로 비교하지 않는다. 이러한 한계와 측정된 회귀를 이유로 기본값 승격은 별도 판단 사항이다.
