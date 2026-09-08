# Compact Global과 Incremental Anytime 구현 보고서

작성일: 2026-09-09 (Europe/Berlin). 실험 식별일: 2026-09-08 UTC. 프로젝트: Cofee / SystemDS. 실행 서버: so007 (`dams-so007`).

이 문서는 V5 구현(commit `db092e3fea85789b28e4e4570f14f9ffc48a0036`)을 기준으로, Global에도 같은 compact+exact를 적용하고 Anytime은 3%·5% 인증에 필요한 전역 coupling을 점진적으로 복원하는 방식을 설명한다. 성능은 별도 [planning 검증 보고서](ANYTIME_INCREMENTAL_RESULTS_KO.md)에 기록한다. 1%에는 compact Global exact를 권장하지만, 코드는 threshold를 보고 자동으로 Global을 호출하지 않는다. 이번 비교에서는 3%·5%를 명시적으로 설정하며, 범용 relative-gap 기본값 1%를 바꾸지는 않았다.

## 1. 변경 범위

Global과 Anytime은 같은 original decision, auxiliary, hard legality, forced-state audit 및 canonical objective를 사용한다. Global을 느린 이전 경로에 남겨 두고 Anytime만 compact하는 비교는 사용하지 않는다.

| 구분 | 최종 구현 |
| --- | --- |
| Global | 전체 모델을 exact-preserving reduction/compaction한 뒤 기존 exact categorical solver로 최적화 |
| Anytime 초기 하한 | 같은 compact 전체 모델의 MBE replica relaxation을 독립 factor-connected component로 나누어 계산 |
| Anytime 초기 계획 | producer-before-consumer 순서의 greedy+hard feasibility repair와 replica projection 중 원래 모델에서 검증한 더 저렴한 계획 |
| LB 강화 | 선택한 encoded variable의 분리된 replica들을 한 번에 일치시키고 영향받은 component만 다시 풂 |
| 재사용 | 영향받지 않은 component의 결과, 이미 평가한 original assignment의 canonical cost |
| 종료 | 보수적으로 계산한 인증 gap이 3% 또는 5% 목표 이내이면 즉시 종료 |
| 제한된 U 개선 | 선택 coupling 주변의 original decision region을 최대 설정 횟수만 풀어 feasible improvement만 수용 |

새 정책 식별자는 `anytime-incremental`이다. 기존 `anytime-target`, legacy Anytime 및 앞선 세 제안 구현은 보존했다. 새 경로의 성능 근거는 새 compact Global과의 비교에서 얻는다.

## 2. Global과 공유하는 compact 입력

`ExactPhysicalReducedSolver.compactModel(...)`은 기존의 정확한 domain reduction, 동등 대표값 처리와 singleton 제거를 수행하되 exact solver를 compile하지 않는다. 반환되는 `CompactModel`은 다음을 함께 보존한다.

- Compact variable와 factor, 상수 비용.
- Compact 위치에서 원래 variable identity로 가는 대응.
- 원래 decision과 auxiliary의 대표값/domain 대응.
- Compact assignment를 원래 전체 encoded assignment로 복원하는 `expandAssignment`.

Global의 `ExactPhysicalOptimizer.optimize`는 이제 `solveCompacted`를 기본으로 호출한다. 해를 원래 decision 공간으로 복원한 뒤 원래 forced-state audit와 canonical cost의 raw binary64 일치를 확인한다.

Anytime은 같은 `compactModel` 결과를 사용하지만, 전체 compact 문제를 미리 exact compile하는 preflight는 실행하지 않는다. 이 차이가 중간 단계의 계산 절약을 가능하게 한다. 원래 변수를 줄이거나 동등 값을 합치는 과정이 원래 modeled optimum을 바꾸어서는 안 된다.

## 3. 전역 LB의 표현과 증분 계산

### 3.1 Replica relaxation

MBE가 같은 encoded variable의 coupling을 분리하는 부분을 별도 replica로 표현한다. Original decision뿐 아니라 auxiliary도 대상이다. 원래 assignment를 모든 replica에 동일하게 복사하면 원래 모든 factor 비용과 hard constraint가 보존된다. 따라서 equality를 일부 제거한 공간은 원래 feasible 공간을 포함한다.

초기 width는 파일럿에서 2로 설정했다. 이 값만으로 전체 메모리나 모든 component exact solve의 비용이 제한된다고 가정하지 않는다. 각 component는 실제 compile 통계와 table 한도로 따로 검사한다.

### 3.2 독립 component의 결과 재사용

Replica factor graph를 factor scope로 연결된 component들로 분할한다. Component 사이에는 factor가 없으므로 relaxation의 최적값은 각 component 최적값의 합이다.

\[
\ell(R)=\sum_{j=1}^{m}\operatorname{opt}(R_j)\le C^*.
\]

`IncrementalReplicaBound`는 각 component의 variables, factors, exact result와 보수적인 하한을 보관한다. 중간 exact elimination table 전체를 노드별로 계속 캐시하는 구조는 사용하지 않는다. 상수 factor는 별도 constant component로, factor가 없는 변수는 isolated component로 유지한다.

Replica equality 연결 상태와 factor graph component 연결 상태는 별개로 관리한다. 같은 factor component 안에 들어 있다는 사실만으로 두 replica가 같은 값을 갖도록 강제된 것은 아니기 때문이다.

### 3.3 변수 단위 consistency 복원

선택한 encoded variable의 현재 equality 연결 집합이 \(G_1,\ldots,G_q\)라면 각 집합의 대표 replica를 star 형태로 연결하는 \(q-1\)개 equality를 한 번에 추가한다.

\[
x^{(G_1)}=x^{(G_2)}=\cdots=x^{(G_q)}.
\]

이 equality들이 닿는 factor component들을 합쳐 한 번 exact solve한다. 다른 component의 결과는 그대로 재사용한다. 이미 같은 component에 있던 replica들의 equality를 복원할 때도 그 component만 다시 푼다.

후보 준비나 풀이가 중단되면 equality 일부만 반영하지 않는다. 완성된 exact trial의 component 결과, equality 집합과 DSU 갱신을 하나의 commit으로 적용한다. Resource 실패 및 solve 직후 cancellation에서도 기존 assignment, LB, component와 equality 상태가 유지되는지 테스트했다.

### 3.4 복원한 replica identity를 실제로 합치기

2차 GLM 실험에서는 같은 값으로 묶은 replica들을 별도 variable와 equality factor로 계속 exact compile에 넣는 비용이 컸다. WAN의 component preparation만 6.97~15.05초였으며, 전체 planner는 12.16~22.15초였다.

3차 구현은 시험할 equality까지 포함한 **별도 DSU 사본**을 만든 뒤, 그 equality class에 속한 replica들을 하나의 변수로 대입한다. 기존 factor들의 scope와 값 조회를 새 변수 위치로 옮기고, 내부에서 생성한 zero/infinity identity equality factor만 제거한다. 원래 cost/hard factor와 상수는 모두 유지한다. 하나의 factor 안에서 같은 대표 변수가 여러 번 나타나면 scope는 한 번만 포함하고 원래 값 조회에는 같은 값을 반복 전달한다.

이 대입은 원래 relaxation의 일부 coupling을 더 풀어 주는 연산이 아니다. 이미 복원하기로 한 equality가 정의한 동일 문제를 더 작은 exact 입력으로 표현하는 연산이다. Compact 결과를 푼 뒤 각 replica의 값을 다시 원래 component 순서로 복원한다. 이후 projection과 다음 후보 생성은 기존 replica identity를 계속 사용한다.

Prepared/solve 통계는 실제로 축소된 exact 입력의 작업량을 기록한다. Component cache에는 원래 replica 공간의 variables/factors와 확장된 result를 보존하므로, 새 equality를 시험할 때 현재까지 복원한 관계만 다시 대입한다. 이 변경에서도 자원/중단 실패는 기존 equality 상태를 바꾸지 않는다.

### 3.5 Component 조회의 증분 관리

V5는 `componentByReplica` 배열로 각 replica가 현재 속한 component를 O(1)에 찾는다. V4는 후보별 representative마다 component 목록과 내부 variable 목록을 검색했다. 초기 component 구성과 성공한 merge에서만 새 index를 O(R)에 구성한다. 여기서 R은 전체 replica 수다. 모든 replica가 정확히 한 component에 속하는지 검사한 뒤 component 목록과 index를 함께 교체한다. 실패·중단된 trial은 live index에 반영하지 않는다.

같은 원변수의 replica roots 중복 검사에는 집합을 사용하되, representative 목록에는 기존 순서의 첫 replica를 추가한다. 이 변경은 후보 순위식과 결정적 동률 순서를 유지하면서 반복 검색 비용을 줄이기 위한 것이다. 각 자료구조의 wall-clock 비중을 별도 profiler로 측정했다는 뜻은 아니다.

## 4. 어느 부분을 선택하는가

모든 후보를 exact로 시험하는 것은 비싸므로 다음 구조를 사용한다.

1. 아직 분리된 replica가 있는 encoded variable을 찾는다.
2. 각 equality group의 cached component assignment에서 가장 자주 선택된 값의 빈도를 구한다. 전체 group 수에서 이 modal frequency를 뺀 `modalMinorityGroups`는 현재 relaxed 해에서 일치하지 않는 group 수의 순서 독립적인 근사치다.
3. 후보가 건드리는 cached component들의 기존 elimination assignment 수를 합쳐 `cachedAssignments`를 만든다.
4. `modalMinorityGroups / max(1,cachedAssignments)`가 큰 후보부터 정렬한다. 동률이면 cached work가 작은 후보, 그다음 original variable 순서를 사용한다.
5. 기본값으로 맨 앞의 변수 하나만 실제 시험한다. 설정상 최대 2개까지 허용하며, 둘 이상 시험한 경우에는 실제 전역 LB gain을 준비·풀이 시간으로 나눈 값이 가장 큰 유효 trial을 채택한다.

\[
\operatorname{score}(a)=
\frac{L_{\mathrm{trial}(a)}-L_{\mathrm{current}}}
{T_{\mathrm{prepare}(a)}+T_{\mathrm{solve}(a)}}.
\]

여기서 분자는 해당 component만의 증가량이 아니라 재사용한 component까지 합친 전역 LB의 증가량이다. 양의 gain이 없으면 시험한 유효 후보 중 가장 저렴한 batch를 적용하여 consistency 복원을 진행한다. Resource cap에 걸린 후보는 equality 상태를 바꾸지 않은 채 원변수와 현재 equality roots의 key로 실행 동안 blocked 상태에 둔다. 다른 merge가 factor component와 예상 작업량을 바꾸어도 같은 key를 자동 재시도하지 않으므로 자원 종료가 보수적일 수 있다. 첫 후보가 blocked여도 다른 후보가 남아 있으면 controller는 종료하지 않고 다음 step에서 남은 후보를 시험한다. 더 이상 후보가 없을 때만 `RESOURCE_LIMIT`으로 종료한다.

이는 **bounded structural shortlist와 제한된 probe 안에서의 선택**이다. 첫 정렬 점수는 실제 LB gain이 아니라 cached relaxed assignment에서 계산한 휴리스틱이다. 모든 가능한 coupling이나 region 중 가장 좋은 것을 찾지 않으며, 가장 빠른 time-to-threshold나 각 step의 양의 gain도 보장하지 않는다. 둘 이상의 trial을 실행하면 측정 시간에 따라 JVM별 선택 경로가 달라질 수 있다. 인증의 유효성은 이 선택 순서에 의존하지 않는다.

V4에서 추가해 V5에서도 유지한 trace는 선택 이유와 실제 비용을 `selectedMinorityGroups`, `selectedReplicaGroups`, `selectedTouchedComponents`, `selectedCachedAssignments`, `selectedPlannedAssignments`, `selectedTrialNanos`로 기록한다. `zeroBoundGainActions`, `projectionCacheHits`, `orderedSeedNanos`도 별도로 기록하므로, 초기 seed·projection 비용과 incremental refinement 비용을 섞지 않고 분석할 수 있다.

## 5. 초기 계획과 제한된 U 개선

### 5.1 초기 계획을 저렴하게 만드는 이유

유효한 LB를 아무리 높여도 현재 feasible plan 자체의 modeled regret가 목표보다 크면 인증을 끝낼 수 없다. 반대로 기존 Regional의 모든 neighborhood 개선을 초기화에서 수행하면 compact Global보다 이미 많은 시간을 쓸 수 있다.

최종 초기화는 두 종류의 후보를 비교한다.

- Replica component 해를 원래 변수로 projection한 first/majority 후보. 원래 hard legality와 canonical evaluator를 통과한 후보만 인정한다.
- 기존 `LocalCategoricalOptimizer`의 producer-before-consumer 순차 선택과 hard conflict repair. 일반 local block과 deferred materialization neighborhood 개선은 초기 단계에서 실행하지 않는다.

유효한 후보 중 modeled cost가 더 작은 것을 선택한다. Optional greedy repair가 resource limit에 걸렸고 이미 검증한 projection이 있으면 projection을 유지한다. Projection이 없거나 model 오류 등 resource 이외의 문제가 발생하면 그 오류를 숨기지 않는다.

초기 compaction/component bound 계산이 resource limit에 걸린 physical 경로는 기존 Regional feasible seed로 돌아가 유효한 \([0,U]\)와 `RESOURCE_LIMIT`을 반환한다. 중단된 계산을 유효 LB로 게시하지 않는다.

### 5.2 Projection의 반복 평가를 캐시

Auxiliary replica의 equality가 바뀌어도 원래 physical decision assignment는 같을 수 있다. 같은 original assignment를 반복해서 canonical 평가하지 않도록 최대 256개의 assignment→cost를 보관한다. Cache에는 큰 MBE table이나 unresolved search coverage가 들어 있지 않다.

새 incumbent를 채택할 때는 `State.accept`에서 원래 canonical cost와 다시 대조한다. Infeasible projection은 계획으로 채택하지 않는다.

### 5.3 선택 coupling 주변의 primal repair

LB 강화 뒤에도 목표가 남으면, 제한된 횟수로 선택 coupling 주변의 original decision들을 개선한다. 선택 variable에서 compact factor graph를 따라 BFS로 region을 구성한다. Auxiliary는 항상 자유롭게 두고 region 밖 원래 decision만 incumbent에 고정한다.

\[
P'=\arg\min C(\gamma_Q,\alpha_{\bar Q}).
\]

이 결과는 원래 feasibility와 canonical objective를 검증한 뒤 \(U\) 개선에만 사용한다. Conditional region의 optimum은 global LB로 게시하지 않는다. Region solve 실패도 전체 모델 infeasibility의 증거가 아니다.

검증 설정에서는 최대 2회, region당 최대 24개 active original decision, 준비 단계에서 평가한 work limit 1,000,000을 적용한다. 이 횟수는 `incumbentRescueAttempts`로 제한되며 실제 시도와 개선은 별도 telemetry에 남는다. 모든 active original decision을 한꺼번에 푸는 region이나 unconstrained root exact shortcut은 이 controller에서 실행하지 않는다. `wholeClosureAttempts`와 `wholeClosureCompleted`는 0으로 남아야 하며, component relaxation 자체의 full restoration 여부는 `fullyRestored`로 따로 확인한다.

## 6. 인증 구간과 종료 계약

Feasible canonical cost만 \(U\)로 받아들이고, 전체 모델의 유효 relaxation 하한만 \(L\)로 게시한다.

\[
L_k\le C^*\le U_k,\qquad
L_{k+1}\ge L_k,\qquad U_{k+1}\le U_k.
\]

Component 결과를 하향 반올림하고 component 합산도 보수적으로 처리한다. 새 LB가 원래 feasible U를 초과하거나 새 feasible U가 기존 L 아래로 내려가는 모순은 오류로 처리한다. 이를 clamp로 숨기지 않는다.

\(L>0\)에서 요청 상대 threshold \(\tau\)를 만족하는 조건은 다음과 같다.

\[
\frac{U-L}{L}\le\tau.
\]

실제 구현은 subtraction과 division의 gap을 상향 반올림하여 종료를 검사한다. \(L=0<U\)이면 relative gap은 무한대이며 임의 epsilon 분모로 인증하지 않는다. \(U=0\)은 비음수 모델의 exact 경계다.

`TARGET_REACHED`는 요청한 인증 gap을 만족했다는 뜻이다. `STEP_LIMIT`, `TIME_BUDGET`, `RESOURCE_LIMIT`은 현재 인증서와 미달 여부를 보존한다. 모든 equality가 복원됐다고 해서 component 합을 canonical cost와 강제로 같은 비트로 만들지 않는다. 첫 목표 checkpoint를 기준으로 결과를 다음처럼 분리한다.

| 분류 | 조건 | 해석 |
| --- | --- | --- |
| Initial-bound partial | `fullyRestored=0`, `steps=0`, `restoredEqualities=0` | 초기 relaxation만으로 목표를 만족 |
| Incremental partial | `fullyRestored=0`, `steps>0`, `restoredEqualities>0` | 실제 consistency 복원 뒤 목표를 만족 |
| Fully restored | `fullyRestored=1` | 목표 시점에 모든 replica coupling이 복원됨 |

Partial 성능 주장은 oracle/model identity가 paired 검증된 행에만 적용한다. 초기 relaxation이 이미 충분했던 행을 incremental 알고리즘 개선 성과로 세지 않는다.

이 controller는 정해진 step/work/time 한도 아래 실행한다. 모든 입력에서 3%·5%에 도달하거나 Global보다 빠르다는 보장은 없다. 이번 구현은 1%를 위한 exact endpoint 정책을 추가하지 않았다.

## 7. 비용 한도와 측정 의미

| 항목 | 최종 V5 본 측정 설정/의미 |
| --- | --- |
| Relative targets | 0.05, 0.03 |
| Width | 2 |
| Refinement actions | 최대 256; V1–V4 측정은 64 |
| Candidate variable probes | 기본 1, 설정상 action당 최대 2 |
| Component/region exact work | Solve당 최대 1,000,000 elimination assignments |
| Factor/total materialized cells | 해당 compile당 1,000,000 / 5,000,000 |
| Primal repair | 최대 2회, 최대 24 active original decisions |
| Projection cost cache | 최대 256 original assignments |
| Scheduling budget | Seed 생성 뒤 20초, soft deadline |
| Outer JVM watchdog | 두 방법 모두 60초 |
| JVM | Fresh sequential JVM, 8 GiB heap, ActiveProcessorCount=8 |

초기 compact reduction, component 계산과 seed 생성은 physical controller의 scheduling clock 이전에 수행된다. 초기 component들의 **누적** exact 작업량에 별도의 전역 work cap이 있는 구현은 아니다. Exact solve 자체도 강제 중단 가능한 구현이 아니므로 phase가 soft budget을 넘을 수 있다. Component cache는 assignment, objective와 solver 통계를 보존하며, 큰 중간 elimination table을 계속 보관하는 구조는 아니다. 그러나 component 수와 각 result 자체의 메모리까지 0이 되는 것은 아니므로 peak RSS를 별도로 측정한다.

따라서 속도 비교에는 초기화와 model 구성·emission을 포함하는 `CompilePhaseFedPlanner` 전체 시간 및 launcher부터 첫 인증 checkpoint까지의 시간을 사용한다. `elapsedMs`만으로 속도를 주장하지 않는다. `boundNanos` 안에는 component preparation/solve 시간이 들어 있으므로 하위 시간들을 다시 더해 이중 계산하지 않는다.

Global은 같은 compact+exact kernel을 사용하되 production exact limits(10,000,000 factor cells / 50,000,000 total cells)를 유지했다. 두 방법이 같은 cell cap을 사용했다고 주장하지 않는다. 사용자 목표인 시간 절약은 작은 Anytime 작업 한도로 얼마나 인증에 도달하는지 평가한다.

V4의 P1은 after-seed 시간이 1초 미만인 상태에서 64-step 상한으로 종료했다. V5 본 측정은 시간·work·cell·primal cap을 유지하고 `maxSteps`와 `rounds`만 256으로 늘린다. 따라서 V4→V5 성능 차이는 component index와 단계 상한을 함께 바꾼 결과이며, index만의 독립 효과로 해석하지 않는다.

### 7.1 실제 설정과 재현

Anytime의 핵심 JVM property는 아래와 같다. 공통 memory/cell/work/seed 설정까지 포함한 완전한 실행 환경은 각 trial의 `command.json`에 보존한다.

```text
-Dsysds.fedplanner.regional.algorithm=anytime-incremental
-Dsysds.fedplanner.regional.mode=anytime
-Dsysds.fedplanner.regional.relativeGap=0.03
-Dsysds.fedplanner.regional.width=2
-Dsysds.fedplanner.regional.probeCandidates=1
-Dsysds.fedplanner.regional.maxSteps=256
-Dsysds.fedplanner.regional.timeMillis=20000
-Dsysds.fedplanner.regional.incumbentRescueAttempts=2
-Dsysds.fedplanner.regional.exactClosureAssignments=0
```

5%는 `relativeGap=0.05`로 설정한다. Global은 동일 runtime의 `config/mkl-exact.xml`, Anytime은 `config/mkl-cost.xml` 및 위 algorithm property를 사용한다. Frozen 설정은 모두 compile-only이며, 실행 argv의 `-exec singlenode`는 worker process를 시작한다는 뜻이 아니다. 네트워크 환경 변수는 cost model의 입력이다.

완료된 campaign을 덮어쓰지 않고 동일 256회 protocol을 재현하는 launcher 형식은 다음과 같다. 이 예시는 이미 실행된 추가 repetition을 뜻하지 않는다.

```bash
incremental_evidence=/home/mchoi/so007-anytime-incremental-evidence-20260908
python3 "$incremental_evidence/native/run_sevenway.py" \
  --phase measured \
  --protocol "$incremental_evidence/native/protocol-main-incremental-v5.json" \
  --context "$incremental_evidence/native/context-incremental-v5.json" \
  --campaign main-incremental-v5-reproduction
```

## 8. 코드와 검증 증거

| 파일 | 역할 |
| --- | --- |
| `ExactPhysicalReducedSolver.java` | Exact compile과 분리한 compact model, original assignment 복원 |
| `ExactPhysicalOptimizer.java` | Global의 compact+exact 기본 경로 |
| `IncrementalReplicaBound.java` | Component 결과 캐시, 변수별 equality batch, 제한된 probe 선택 |
| `IncrementalAnytimeOptimizer.java` | Threshold controller, projection cache, primal BFS, checkpoint |
| `LocalPhysicalOptimizer.java` | 두 feasible seed 후보 비교, resource fallback |
| `RegionalSearchProblem.java` | 원래 model/canonical authority 및 root compact 입력 |
| `RegionalSearchOptimizer.java` | Algorithm/configuration 식별 및 공통 certificate/state 계약 |
| `FederatedPlanLocalCost.java` | CONFIG, checkpoint와 FINAL planning trace |

V5 구현 commit: `db092e3fea85789b28e4e4570f14f9ffc48a0036`.

로컬·so007 native에서 동일하게 생성한 V5 JAR SHA-256: `a9c8afd660d5fdff9a3149a9748646a79a639a36611955465570054b6caa3b1f`.

로컬·so007 native V5 clean package는 각각 22개 focused test class, **191 tests / failures 0 / errors 0 / skipped 0**을 기록한다. Source manifest는 7,506개 파일을 commit `db092e3...`에 결부한다. 별도 harness 최종 재검증은 45 tests / failures 0 / errors 0이다 (`validation/harness-final-tests-v5.json`). 이 수치는 repository 전체 테스트나 workload runtime 검증이 아니며, planning 성능 검증 결과로 확대 해석하지 않는다. Build command에 기록된 방식대로 checkstyle/spotless/license/RAT 단계는 생략했다.

새 backend 테스트는 independent component reuse, 같은 component 안의 equality, 3개 이상 replica의 batch, constants/isolated/auxiliary, zero-gain, resource failure 및 exact trial 직후 cancellation의 atomicity, 큰 비용 스케일에서의 LB를 확인한다. Contraction 회귀는 상수와 비자명한 ternary cost가 있는 문제에서 원래 objective/assignment를 대조하고 실제 compiled work 감소를 확인한다. Controller 테스트는 작은 exhaustive oracle을 모든 checkpoint와 대조하며, 3%·5%를 일부 equality 미복원 상태로 달성하는지와 physical canonical parity를 검증한다.

V5의 추가 회귀는 독립적인 세 conflict 구조에서 취소를 끼운 세 번의 성공한 merge를 실행한다. 선택 variable 순서, oracle bound, component 수 6→5→4→3, 재사용 횟수와 최종 assignment를 확인한다. Constants/isolated 초기화는 별도 기존 테스트가 다루며, 세 번의 merge와 그 두 경계 조건을 하나의 fixture로 합쳐 검증한 것은 아니다.

증거 루트: `/home/mchoi/so007-anytime-incremental-evidence-20260908`.

- `validation/source-manifest-v5.json`
- `validation/local-tests-v5.json`, `validation/native-tests-v5.json`
- `validation/local-build-command-v5.json`, `validation/native-build-command-v5.json`
- `validation/harness-final-tests-v5.json`
- `reviews/incremental-design-review-v2.md`, `reviews/incremental-contraction-review-v3.md`, `reviews/incremental-index-review-v5.md`
- `native/protocol-main-incremental-v5.json`

V1–V4의 각 GLM 16회 pilot과 V4 본 측정 256회는 완료 후 원본 감사를 통과했다. V5도 별도 source/JAR, `context-incremental-v5.json`, `protocol-main-incremental-v5.json`을 고정한 256회 비교를 완료하고 원본 감사를 통과했다. 정상 planning 120쌍의 독립 Global oracle로 인증 구간을 검증했으며, P2의 8쌍은 공통 배치 실패로 분리했다. 버전별 원본과 실패 행을 모두 보존한다.

## 9. 해석과 남은 범위

이 구현은 전역 relaxation의 일부 coupling을 복원하여 얻은 LB를 사용한다. 작은 region을 incumbent 주변에서 exact로 푼 비용을 전역 LB로 대체하지 않는다. 실제 workload 실행시간의 예측 오차나 privacy/placement 모델 밖의 계획 공간을 인증하는 것은 아니다.

1차 GLM 파일럿은 유효한 인증서를 유지했지만 8개 Anytime target 모두 미달이었다. 초기 feasible plan 개선, original projection 재사용 및 변수 단위 consistency batch를 적용한 2차 버전은 8개 target 모두를 일부 coupling 미복원 상태로 달성했지만, WAN에서 Global보다 느렸다. 3차에서는 equality contraction으로 반복 exact compile의 크기를 줄였다. V4는 modal minority/cached work 순서와 기본 1-probe로 버리는 trial을 줄였고, V5는 component 조회 비용과 조기 step-limit 종료를 다뤘다. 이 선택이 16개 workload와 네 network profile에서 실제로 Global보다 빠른지는 [최종 planning 결과](ANYTIME_INCREMENTAL_RESULTS_KO.md)로 판단한다.
