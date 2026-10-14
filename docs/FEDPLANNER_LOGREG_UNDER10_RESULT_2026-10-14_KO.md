# LogReg planning 10초 목표: 구현 및 검증 완료

## 결과

**동일한 ML training runtime Docker workload의 전체 Memo planning을26.819446540초에서6.672535049초로 줄였다(75.12% 감소, 약4.02배). 10초 미만 목표를 달성했다.** Optimizer는23.714206977→4.237294143초(82.13% 감소)이며, 최종 측정은 후보별1회 중 Candidate03의 단일 JFR 관측이다. 학습 실행 시간 자체를6.67초로 보고한 것이 아니다.

| 지표 | 작업 시작 기준 | Candidate01 | Candidate02 | 최종 Candidate03 |
| --- | ---: | ---: | ---: | ---: |
| 전체 Memo planning(s) | 26.819447 | 16.103019 | 15.926900 | **6.672535** |
| Optimizer(s) | 23.714207 | 13.563777 | 13.318015 | **4.237294** |
| Search nodes | 2,933,891 | 2,933,891 | 2,933,891 | **625,916** |
| Coordinator CPU(s) | 51.61 | 39.61 | 38.93 | 30.50 |
| Coordinator HWM(KiB) | 785,668 | 667,616 | 875,108 | 721,656 |
| Optimizer allocation weight(GB) | 30.120 | 10.104 | 8.424 | 4.857 |

최종 compilation은8.057937초이며 planning을 포함한다. 별도 training execution은3.165초다(기준3.034초, Candidate02 2.398초). 학습 실행의 개선은 주장하지 않는다. CPU와 HWM은 전체 coordinator 관측값이며 optimizer만의 수치가 아니다. Candidate02의 HWM 증가는 표에 그대로 남겼다.

모든17개 primary gate 및 forest trace 검증을 통과했다. Exact objective 전체 문자열, FED 출력 CSV bytes, fingerprint, action diagnostics, 정규화한 runtime audit, Oracle/native universe, worker/supply 수가 기준과 같다. Maven 빌드와50개 클래스/440개 회귀 테스트가 통과했다. 상세 기계 판정은 [candidate03-evaluation.json](../experiments/logreg-under10-20261014/candidate03-evaluation.json), [증분 비교](../experiments/logreg-under10-20261014/candidate03-differential.json)에 있다.

## 목표와 기준

이번 목표는 실제 ML training runtime Docker의 `multiLogReg` workload에서 **전체 Memo planning을 10초 미만**으로 줄이는 것이다. Optimizer 시간, 전체 compilation 시간, 학습 execution 시간은 따로 기록한다. 데이터 생성이나 학습 반복 횟수를 줄이는 변경은 하지 않는다.

기준은 직전 완료한 `logreg-bottleneck-after-01`의 JFR 측정이다. 전체 planning 26.819446540초, optimizer 23.714206977초, search nodes 2,933,891이었다. 변경 없는 기준을 재실행하지 않으며 후보 구현별 공식 workload는 한 번만 실행한다. Candidate01은 전체 planning 16.103018585초로 10초 목표에 미달했다. Candidate02도 15.926899887초로 목표에 미달했다. 두 결과를 보존하고 정확한 조건부 forest completion을 Candidate03으로 구현·검증해6.672535049초를 확인했다.

독립 workspace `fedplanner-oracle-cost-20261013`에서 작업하며 원본 workspace는 수정하지 않는다. 이전 미커밋 최적화를 유지하고, 이번 변경의 비교 기준은 Git HEAD가 아니라 `experiments/logreg-bottleneck-20261014/after-engine`의 동결된 소스다.

## 병목과 변경

### 동일 규칙에 조건부인 비용을 함께 계산하는 하한

기존 reduced search는 core owner의 native 비용과 각 제거된 leaf tree의 비용을 각각 최소화했다. 각 최소값이 서로 다른 owner rule을 골라도 합산했으므로 합법적인 lower bound지만 느슨했다.

`MemoOptimizer.ReducedSearch`는 owner/rule별로 native 비용과 해당 rule에 대응하는 모든 정확한 boundary message를 합산한 뒤 현재 domain에서 최소값을 구한다. 비용은 기존 BigDecimal로 정확히 더한다. 한 규칙을 모든 항에 일관되게 적용하므로 하한이 강해지지만 어떤 합법 후보도 임의로 삭제하지 않는다. 완성된 해의 원래 비용 합산 및 전체 assignment의 lexicographic tie 판정은 유지한다. 같은 불변 비용을 이용한 rule 순서를 한 번 정렬해 탐색 노드마다 객체를 만들고 정렬하는 작업도 제거한다.

### 이미 확인된 문맥과 정확한 movement primitive 재사용

`MemoProgramModel`은 movement group 등록 중 이미 수행하는 입력 edge 하나의 activation 방문 결과를 보존한다. COMPLETE 결과만 재사용하고, 미완료·미저장 결과는 기존 visitor 경로로 평가한다. 두 입력의 source를 Cartesian product로 사전 생성하지 않는다.

각 row는 독립 객체이며 syntactic guard/frame 정보를 축약한 equality로 서로 합치지 않는다. 매 plan 선택에서 source/head/output-slot layout 및 consumer target은 다시 읽는다. DIRECT/IMPOSSIBLE gate를 통과한 뒤에만 cost contexts를 평가하며, callback 순서대로 원래 weight, contextual HOP view 및 `MemoCostModel.movementCost`를 계산한다. 완전히 성공한 row만 보존하고, 같은 row/from/target의 정확한 double unit 값을 재사용한다. Shape만으로 비용을 추정하는 새 공식은 없다.

원래의 double 누적 순서와 retained activation union을 유지한다. Privacy legality는 기존 별도 검사에서 유지한다. UNKNOWN/DEFER와 실패한 부분 계산은 완료된 proof로 승격하지 않는다. Activation 및 primitive 보존은 model 수명 안에서 각각 최대 100,000개로 제한하며 한도 초과 시 기존 계산 경로로 처리한다.

### 불변 shape 의존성 준비

`MemoShapes`는 occurrence의 직접 입력에 대한 phase/guard dependency union을 생성 시 한 번 계산한다. 이미 필요한 loop phase가 모두 있는 immutable context는 그대로 반환한다. Shape나 guard identity의 의미를 축약하지 않는다.

## 검증 및 측정 계약

- Docker: 기존 image, 4 CPU / 8 GiB, worker 3, 네트워크 없음, 동일 JFR 설정.
- Workload: 기존 192×8 입력, X PRIVATE_AGGREGATE / Y PUBLIC, `maxi=10`, `maxii=5`.
- 공식 entrypoint: `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e --profile-jfr --planner memo --case ml_logreg`.
- OPTIMAL, exact objective 전체 문자열, FED fingerprint, action diagnostics, supply/worker/native rule 수, CSV bytes 및 runtime audit가 기준과 같아야 한다. Search node 수는 개선 대상으로 동일성을 강제하지 않는다.
- Oracle 요청/캐시와 native universe도 비교한다. JFR data loss 및 동결한 소스/classes와 실제 Docker 입력 hash 일치를 확인한다.
- 이번에는 GLM을 실행하지 않는다. 이전 300초 timeout의 해결을 주장하지 않는다.

## 검증의 한계

기존 stale training inventory golden 5개가 포함된 클래스는 이전과 같이 통합 suite에서 제외한다. 이번 수정으로 해결한 것이 아니다. 별도 cold JVM에서 기존 1,000-depth source fixture가 기본 stack의 한계를 보였으며 baseline 및 수정 후 host 단위 테스트를 `-Xss8m`의 같은 조건으로 검증했다. Docker workload JVM 조건은 바꾸지 않았다.

각 후보 한 번의 JFR 관측으로 판단하므로 반복 측정의 분산이나 일반적인 모든 LogReg 입력의 10초 보장을 주장하지 않는다. JFR CPU 표본은 inclusive 경로끼리 중첩되며 할당 weight는 추정치다. 전체 coordinator CPU/RSS에는 compilation과 execution도 포함된다.

## 재현 자료

구현 계획, solver/movement 설계, 독립 검토, 테스트 명령·로그, 동결 엔진 hash 및 Docker 명령은 `experiments/logreg-under10-20261014/`에 저장한다. 최종 측정과 판정은 이 문서의 결과 및 Candidate03 절에 기록한다.

## Candidate01 통합 검증 결과

Maven offline `test-compile`은 39.540초에 성공했다. Assertions와 `-Xss8m`을 켠 49개 클래스/432개 테스트가 44.433초에 통과했고 Docker harness Python 45개 테스트도 통과했다. 새 회귀는 correlated bound의 정확한 최적해·lexicographic tie·UNKNOWN·finite budget, movement cache on/off 전체 selection의 legality/정확한 비용, DIRECT gate 이전 계산 0회를 확인한다.

독립 solver/Shapes 검토와 movement 검토는 모두 APPROVED다. Movement 중간 failure를 주입하는 전용 테스트, 별도의 IMPOSSIBLE-only fixture, capacity 초과 전용 fixture는 추가하지 않았으며 코드의 publish 위치·fallback 순서와 기존 control-flow/privacy/shape suite로 검토했다. 세부 테스트 공백은 `movement-independent-review.md`에 남겼다.

`candidate01-engine`은 12,509개 파일과 SHA-256 manifest로 동결했다. 기준 frozen source 대비 수정은 production 4개(`MemoOptimizer`, `MemoProgramModel`, `MemoShapes`, `FederatedPlanMemo`)와 새 테스트 2개다. 기존 `MemoProgramFacts` 등 앞선 작업의 변경은 그대로 유지되며 이번 추가 diff에 포함하지 않는다.

## Candidate01: 반복 문맥 비용은 줄었지만 목표에는 미달

| 지표 | 기준 | Candidate01 |
| --- | ---: | ---: |
| 전체 Memo planning | 26.819446540 s | 16.103018585 s |
| Optimization | 23.714206977 s | 13.563777113 s |
| Search nodes | 2,933,891 | 2,933,891 |
| Coordinator 관측 CPU | 51.61 s | 39.61 s |
| Coordinator HWM | 785,668 KiB | 667,616 KiB |
| Optimizer allocation weight | 30.120 GB | 10.104 GB |

정확성·동결hash·JFR gate는 전부 통과했고, 10초 threshold만 실패했다. 전체 planning은 약 39.96% 줄었다. Correlated boundary bound는 이 workload의 node 수를 줄이지 못했다. Movement inclusive CPU 표본은 711→84, cost-context 방문은 543→28로 줄었다. Post-gate 요청192,331회 중 완성본 생성2,589회/재사용189,742회였고 primitive context10,709개, contextual view35,725회를 기록했다. 이 숫자는 실제 요청/생성/재사용 횟수이며 source identity를 합친 개수로 해석하지 않는다.

새 optimizer 표본1,110개 중 domain cache 조회333개(30.0%), scoped hash140개, domain equality89개였다. Leaf의 HashMap tree lookup89개와 BitSet hash98개는 cache hit 자체가 비싸졌다는 근거다. `coreLowerBound`의 nearest-method117개 및 `enumerateCore`164개, connectivity54개도 확인했다. 이 계측에 따라 Candidate02는 full domain identity를 유지한 compact 표현과 cache hash 개선, 동일 값의 bound 산술 생략, 불변 core-owner 목록과 정확한 탐색 상태 갱신을 구현한다. 새 solver나 근사화는 도입하지 않는다.

Candidate01 상세 자료는 `candidate01-report.md`, `candidate01-evaluation.json`, `candidate01-summary.json`, `candidate01-hotspots.json`에 보존했다. 동일 후보의 재실행은 하지 않았다.

## Candidate02: 정확한 domain과 탐색 상태의 저렴한 재사용

`MemoOptimizer`의 작은 domain(폭≤64)은 전체 원소 집합을 long 비트마스크로도 유지한다. 더 큰 domain은 원래 full BitSet을 사용한다. Cache miss에는 immutable snapshot을 저장하고 hit 조회에서는 현재 domain을 동기적으로 빌린다. 해시를 개선했지만 일치 판정은 항상 전체 mask 또는 BitSet equality로 수행한다. 서로 다른 입력을 hash만으로 합치지 않는다.

하한의 기존·새 contribution이 같은 BigDecimal 값과 scale이면 합계에 더했다 빼는 작업을 생략한다. Scale까지 비교하는 이유는 일반 fallback search가 완성된 lower bound를 incumbent 비용으로 반환하기 때문이다. 최종 비용의 숫자와 표현을 보존한다. Actual core owner의 오름차순 목록을 한 번 만들고 재사용하며, 분기 우선순위의 이웃 수는 domain이 2→1/1→2로 바뀔 때 정확히 갱신한다. 원래 heuristic과 tie 순서는 유지한다.

이번 증분 production 변경은 solver 한 파일이다. 폭63/64/65, signed high bit의 제거·되돌리기, cache 크기0/1/4096, 실제 wide-domain hash collision을 검증했고 focused36개 테스트가 assertions 활성화 상태에서 통과했다. 최종 통합 회귀와 단일 Docker 결과는 아래와 같다.

Candidate02 최종 Maven `test-compile`은 40.476초에 성공했다. 전체49개 클래스/434개 테스트가 `-ea -Xss8m` 조건에서47.838초에 통과했고, 독립 검토도 승인됐다. Candidate02는12,509개 파일의 동결 엔진이며 Candidate01 대비 solver1개·회귀test1개만 바뀌었다. Harness는 변경 없고 기존45개 통과 결과를 유지한다.

Candidate02의 공식 Docker 1회 결과는 planning15.926899887초, optimization13.318014527초였다. Candidate01 대비 planning1.09%, optimizer1.81% 개선에 그쳤다. 정확한 objective 전체 문자열, search nodes2,933,891, Oracle/native universe, CSV hash 및 runtime audit는 같았고 동결hash/JFR gate도 통과했다. 10초 threshold만 실패했다. Coordinator CPU38.93초, HWM875,108KiB로 이전 후보보다 HWM이31.08% 증가했다. 할당 weight는8.424GB이며 Candidate01 대비 감소량은약1.680GB다. 이를 resident memory 감소로 해석하지 않는다.

Fresh optimizer 표본1,068개에서 cachedDomains299, coreLowerBound314, propagate376, movement97개가 관측됐다. 이전 HashMap tree lookup·BitSet.hashCode·BitSet.equals leaf 표본은0개가 되었지만 전체 탐색량은 줄지 않았다. 남은 반복 bound/support/invalidation을 줄이려면 작은 연산 최적화에 더해 조합 탐색 구조를 개선해야 한다. 세부 자료는 `candidate02-report.md`, `candidate02-evaluation.json`, `candidate02-differential.json`에 보존한다.

## Candidate03: 조건부 forest의 정확한 완성 (목표 달성)

일부 owner가 singleton으로 확정된 뒤 잔여 factor graph가 forest가 되면 기존 TreeCell을 이용한 동적 계획법으로 남은 최적해를 계산한다. 잔여 고차 factor나 cycle이 있으면 기존 enumeration을 유지한다. 동일 edge의 여러 factor는 각각 한 번 평가하고, 원래 제거한 boundary tree의 비용은 owner message에 포함된 한 번만 더한다. Lexicographic 비교에는 원래 제거한 owner까지 포함한 전체 assignment를 사용한다.

UNKNOWN은 조건부 proof를 완성한 것으로 취급하지 않고 원래 enumeration으로 돌아간다. 각 tail cell은 기존 global node budget에 청구하고 한도가 끝나면 RESOURCE_EXHAUSTED로 남긴다. 최적해를 포기하는 근사나 후보 삭제는 도입하지 않는다. 구현 전 계획은 `candidate03-plan.md`다.

잔여 graph 검사는 이미 모든 factor scope의 clique expansion을 보존한 `branchAdjacent`를 사용한다. Parallel factor는 동일 edge로 묶여 graph cycle로 오인되지 않는다. Arity>2의 잔여 factor는 triangle을 만들므로 forest 검사를 통과하지 못한다. Forest가 확인된 경우에만 evaluator용 constant/unary/edge bucket을 구성한다. 현재 남은 node budget보다 필요한 전체 cell 수가 크면 DP를 시작하지 않고 기존 enumeration을 유지한다.

Frozen Candidate02에 새 semantic fixture5개를 adapter로 먼저 실행해 기존 상태·비용·선택을 고정했다. 별도 구조 fixture는 정확히 같은 OPTIMAL과 선택을 유지하며75→33nodes로 감소했다. 새회귀6개를 포함한 targeted42개가 assertions를 켠 상태에서 통과했다. 정확히 필요한 node 한도에서는 OPTIMAL, 한 개 적으면 RESOURCE_EXHAUSTED임을 확인했다. 독립 source 검토는 APPROVE이며 전체 suite와 실제 workload 측정도 아래와 같이 완료했다.

Candidate03의 최종 offline Maven test-compile은38.931초에 성공했고, 50개 클래스/440개 테스트가 `-ea -Xss8m` 조건에서44.190초에 통과했다. 전체 production/test source는12,512개 파일의 frozen engine과 일치한다. Candidate02 대비 production2개(`MemoOptimizer`, `FederatedPlanMemo` trace)와 새test1개가 변경됐다. 이번 작업 전체 기준은 production4개·test4개이며 누적 diff는 `final-task-main.diff`와 `final-task-tests.diff`다. 이전 harness45개 PASS는 harness 변경이 없어 재사용한다. 근거는 `candidate03-preflight-verification.json`과 각 command/log에 있다.

### 실제 감소한 작업

Forest 적용 검사는8,716회, 적용·완료는 각각3,310회였으며 tail cell570,702개를 global search node에 청구했다. 전체 nodes625,916에는 이 cell도 포함된다. DP 작업을 node 계측에서 숨겨 만든 감소가 아니다. Candidate02 대비 전체 planning58.11%, optimizer68.18%, nodes78.67%가 감소했다.

| 탐색 작업 counter | 기준 | 최종 |
| --- | ---: | ---: |
| Conditional bound queries | 11,135,047 | 207,924 |
| Support cache hits | 38,226,419 | 1,924,098 |
| Support queries(cache miss/DFS) | 171,750 | 68,642 |
| Worklist visits | 13,616,123 | 409,814 |
| Rule removals(undo 포함 반복 탐색에서 누적) | 21,182,609 | 632,691 |
| Cost evaluations | 4,828 | 5,103 |
| Cost cache hits | 3,407 | 2,278,731 |

핵심은 cache hit를 더 만드는 것만이 아니라, 수백만 번의 domain 변경·되돌리기·전파·하한 재계산을 정확한 forest message 계산으로 대체한 것이다. DP의 edge 평가로 cost evaluation/cache hit는 오히려 늘었다. 전체 작업이 줄었다는 근거와 모든 counter가 줄었다는 주장을 구분한다.

완료한 movement primitive 생성은2,589회, 재사용191,116회, primitive contexts10,709개, contextual views35,725회다. Oracle actual requests1,057/hit0/miss1,057, prepared8,880/reused7,823이며 native rule920·supplies1·workers3이 유지된다. Source Cartesian 조합을 새로 materialize하거나 Oracle/privacy 규칙을 수정하지 않았다.

JFR optimizer sample은1,696→198, allocation weight는30.120→4.857GB, GC stop-the-world는0.368887469→0.225019509초였다. 최종 movement inclusive79/198, support63/198, coreLowerBound59/198 표본은 남은 비용이며 경로가 중첩된다. 반복측정이 없으므로 표본 비율을 정확한 하위단계 초 단위 시간으로 환산하지 않는다.

### 결과 동등성과 수정 파일

Exact objective는 `1301.1526531142989790221751587629165844428591469750244868919253349304199218750`으로 동일하다. FED CSV332bytes의 SHA-256는 `c1afd1422eb05ef396bbc13b48fbd09d5966995de43d9f92147ebb1599d5b848`, CP/FED max absolute difference는1.6653345369377348e-16이다. Runtime audit33rows는 정규화 후 동일하며 위반은0건이다. 최종 frozen engine12,512files와 Docker staging의 source/classes가 모두 일치하며 JFR data loss는0건이다.

| 파일 | 이번 작업의 변경 |
| --- | --- |
| `MemoOptimizer.java` | correlated boundary bound, exact domain key 재사용, reversible neighbor counts, conditional forest tail |
| `MemoProgramModel.java` | 완료 activation 및 기존 exact movement primitive의 model-local 재사용 |
| `MemoShapes.java` | 불변 dependency union과 loop-phase normalization no-op |
| `FederatedPlanMemo.java` | 기존 trace property 아래 movement/tail 관측 추가 |
| `MemoOptimizerBoundaryBoundTest.java` | correlated bound·tie·UNKNOWN·budget 회귀 신규 |
| `MemoMovementReuseTest.java` | cache on/off exact legality/cost 및 DIRECT gate 회귀 신규 |
| `MemoOptimizerDomainCacheTest.java` | width63/64/65·high-bit undo·wide hash collision 회귀 보강 |
| `MemoOptimizerForestTailTest.java` | forest·고차scope투영·parallelfactor·lex·UNKNOWN·infeasible·budget 회귀 신규 |

원본 workspace는 수정하지 않았다. 이 목록은 이번 task 시작 시 동결한 `after-engine`과의 diff이며 기존 미커밋 변경까지 이번 작업으로 계산하지 않는다. 후보별 frozen engine·source hash·검증 결과는 그대로 보존했다.

### 재현과 잔여 위험

정확한 Docker 명령은 [candidate03-command.txt](../experiments/logreg-under10-20261014/candidate03-command.txt), 후처리는 [candidate03-postprocess-command.txt](../experiments/logreg-under10-20261014/candidate03-postprocess-command.txt), 전체 회귀 명령은 [candidate03-unit-command.txt](../experiments/logreg-under10-20261014/candidate03-unit-command.txt)에 있다. Immutable raw run은 `/grid/3/cofee-lm-sweep-mchoi-20260914/fedplanner-oracle-cost-docker-20261013/logreg-under10-candidate03`이다. 새 재현을 할 경우 기존 run-id를 덮어쓰지 않는다. 이번 세션에서는 동일 후보를 재실행하지 않았다.

일반 입력의10초 보장, GLM timeout 해결, 기존 training golden5개 해결은 이번 결과에 포함하지 않는다. Movement cache의 failure injection·IMPOSSIBLE-only·capacity 초과 전용 회귀 공백과 host deep fixture의 기본 stack 민감성은 위 검증 한계대로 남아 있다. 향후 factor scope나 domain mutation 경로를 추가하면 residual graph와 reversible count의 정확성을 다시 검증해야 한다. UNKNOWN/cell budget/전체 lex/cost once의 경계를 회귀와 독립 검토로 유지한다.
