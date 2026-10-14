# FedPlanner Optimizer 불변 분석 재사용 구현 및 검증

작성일: 2026-10-14. 상태: 구현·회귀·독립 검토·전후 각 1회 Docker 측정 완료. GLM timeout은 미해결.

**LogReg planning은 39.65→32.08초로 19.09% 단축됐다. Exact objective와 실행 결과는 동일하다. GLM은 전후 모두 300초 timeout이며 완료 문제는 해결되지 않았다.**

## 기준과 범위

[실측 분석·개선 설계](FEDPLANNER_OPTIMIZER_PROFILE_AND_IMPROVEMENT_DESIGN_2026-10-14_KO.md)의 후속 구현이다. 독립 workspace `/home/mchoi/fedplanner-oracle-cost-20261013`, branch `implement/oracle-cost-projection`에서 수행한다. 변경 전 기준은 `25662ee7b485af0880a11c9b219620e155cfbee7`이다. 원본 workspace는 수정하지 않는다.

고정된 분석의 반복 조회와 cache hit의 객체 생성을 줄인다. Feasibility, privacy, UNKNOWN/DEFER, worker binding, source identity, binary64 primitive 및 exact BigDecimal 합, 최적해 보장은 유지한다. 후보 조합을 임의로 제거하거나 source Cartesian product를 미리 생성하지 않는다.

## 구현

| 설계 항목 | 구현 위치와 범위 | 보존하는 의미 |
|---|---|---|
| A. Guard/phase 조회 | `MemoProgramFacts` 생성 완료 후 loop/guard index, exact syntactic frame의 provenance·decision 재사용 | 첫 등록 fact, null, alias·inversion, 서로 다른 syntactic frame |
| B. 완료한 shape proof 재사용 | `MemoShapes`의 binding guard query와 repeat-head proof, instance별 bounded cache | full context, 완료한 결과만 저장, eviction 시 원래 계산 |
| C. 고정 context 준비의 공통 분석 재사용 | Facts의 immutable occurrence context와 Shapes의 공통 분석 경로에서 처리 | movement DIRECT/IMPOSSIBLE gate, 선택 의존 legality·privacy·비용 합산 유지 |
| D. Solver key 할당 | `MemoOptimizer`의 truth/cost/support/bound cache에 동기 lookup probe | 전체 id+tuple equality, partial -1, revision/domain, immutable 저장 key |
| E. Normalization 전략 | 이번 변경에서 보류 | 기존 folding, branch ordering, finite-budget 동작 |

`MemoProgramModel`에 별도의 movement cache 계층을 추가하지 않는다. 기존 gate 뒤에서 호출되는 공통 Facts/Shapes 분석을 재사용하므로 비용 계산을 생성자 시점으로 앞당기지 않는다. Plan 선택에 따라 바뀌는 origin, selected layout, privacy, activation union, movement identity 및 비용 계산은 기존 경로에서 수행한다. 원본 selected-cost audit도 유지한다.

Solver probe는 `Map.get` 동안에만 입력 배열을 참조하고 `finally`에서 참조를 지운다. Predicate/evaluator 호출 이전에 reset하며, 결과를 저장하는 miss에서만 immutable key와 배열 복사본을 만든다. Cache capacity, eviction, normalization invalidation은 바꾸지 않는다.

### 재사용 입력과 정확성 경계

Shape proof key는 binding 또는 선택된 loop head와 함께 다음을 보존한다.

- guard의 syntactic id·call frame, canonical value binding·value frame, truth value
- loop boundary, iteration phase, ZERO/ONE/MANY cardinality, 현재 call frame과 maxDepth
- 프로그램에 존재하는 carried slot 각각의 외부 `headIsBound` 값

`GuardDecision.equals()`는 canonical value가 같으면 서로 다른 syntactic frame도 같다고 본다. 새 proof key는 해당 객체를 그대로 map equality에 맡기지 않고 전체 필드를 기록한다. Mutable `SelectionLookup`의 객체 identity도 사용하지 않는다. Shape 경로가 읽지 않는 selected rule/layout payload는 key에서 제외하고, traversal을 바꾸는 head binding은 snapshot한다. Facts/Shapes/Sources instance를 넘어 결과를 공유하지 않으므로 다른 worker pool이나 분석 세대가 섞이지 않는다.

두 shape cache의 한도는 각각 4,096개 완료 결과이며 LRU eviction 후에는 기존 계산을 다시 수행한다. 반복 증명은 선택된 head가 정해진 뒤의 완료된 계산만 저장한다. Active recursion path를 binding 하나로 합치거나 in-progress 결과를 성공으로 게시하지 않는다. 완료된 false와 UNRESOLVED도 원래 의미대로 유지한다. Top-level cache hit의 traversal 진단 수치는 실제 작업량 0으로 기록하고 별도 `[MemoShapeReuse]` request/hit 수치로 재사용을 관찰한다.

Guard phase index는 실제 fact를 loop별 정렬된 primitive guard-id 배열로 저장하고 binary search한다. 저장량은 등록 fact 수에 비례하며 loop×guard 빈 조합을 할당하지 않는다. `putIfAbsent`로 기존 첫 fact 의미를 유지하고 조회에서 boxing/stream을 제거한다. Occurrence context는 처음 필요할 때 계산한 immutable 목록만 재사용한다.

## 동일 Docker 조건의 변경 전후 측정

사용자의 각 1회 제한을 지킨다. Before/after 각각 LogReg와 GLM을 공식 `scripts/fedplanner/run_LAN_docker.sh`로 실행한다. JFR은 사용하지 않는다. 이전 JFR 결과는 병목 선정 근거이며 이번 시간과 직접 혼합하지 않는다.

동일 Docker image `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`, CPU 4, memory 8 GiB, worker 3, network none, case timeout 300초를 사용한다. 원본 명령, frozen input 검증과 결과는 `experiments/optimizer-reuse-20261014/`에 둔다.

| 지표 | 변경 전 LogReg | 변경 후 LogReg | 변화 |
|---|---:|---:|---:|
| Facts | 0.056115449초 | 0.058541804초 | +0.002426355초 |
| Rules | 1.937784423초 | 1.507092549초 | −22.23% |
| Model | 0.754423339초 | 0.613457449초 | −18.69% |
| Optimization | 36.851172142초 | 29.823222436초 | −19.07% |
| Projection | 0.045925907초 | 0.074656098초 | +0.028730191초 |
| 전체 planning | 39.645421260초 | 32.076970336초 | −19.09% |
| SystemDS compilation | 40.732312초 | 33.223130초 | −18.44% |
| Runtime execution | 2.603초 | 2.577초 | −1.00% |
| Coordinator CPU 관측 | 62.87 CPU초 | 52.95 CPU초 | −15.78% |
| Coordinator sampled max RSS | 693,584 KiB | 725,480 KiB | +4.60% |
| Coordinator peak HWM | 710,988 KiB | 725,480 KiB | +2.04% |

CPU는 `/proc` 첫/마지막 표본 구간의 process 합계이며 optimizer 전용 CPU나 단일 wall time이 아니다. Peak HWM은 live heap과 다르다. 전체 planning은 기존 phase timer의 합이며 SystemDS compilation과 별도 지표다. Runtime의 0.026초 차이를 학습 실행 속도 개선으로 일반화하지 않는다.

| GLM 지표 | 변경 전 | 변경 후 |
|---|---:|---:|
| 결과 | 300초 timeout, rc124 | 300초 timeout, rc124 |
| Coordinator CPU 관측 | 325.84 CPU초 | 323.65 CPU초 |
| Coordinator sampled max RSS | 715,416 KiB | 782,976 KiB |
| Coordinator peak HWM | 715,800 KiB | 783,360 KiB |
| 최적해·출력·완료 planning 시간 | 없음 | 없음 |

GLM은 두 실행 모두 모델 생성 이후 optimizer에서 종료됐다. Native generation, cost projection 구성 및 Oracle 요청 counter는 같았다. 요청은 prepared 11,699 / reuse 9,449 / 실제 Oracle 요청 2,250이다. 종료 시점까지 optimum과 수치 출력이 없어 **GLM의 완료 성능·결과 동등성은 검증하지 못했다**. `[MemoShapeReuse]`도 optimizer가 반환한 뒤 출력하므로 GLM에는 없다. 이를 hit 0이나 분석 미실행으로 해석하지 않는다. 같은 300초 제한과 비슷한 CPU 시간만으로 처리량 개선 여부나 잔여 탐색량을 알 수 없다.

Peak HWM은 LogReg 2.04%, GLM 9.44% 증가했다. 시간 감소와 함께 기록해야 할 memory tradeoff이며, 단일 표본으로 증가량 전체를 새 cache의 retained heap이라고 단정하지 않는다.

LogReg는 전후 모두 OPTIMAL, native rules 920, nodes 2,933,891, supplies 1이며 exact objective는 완전히 같다.

```text
1301.1526531142989790221751587629165844428591469750244868919253349304199218750
```

8×2 출력의 CP/FED 최대 절대 차이는 전후 모두 `1.6653345369377348e-16`이다. FED 출력 CSV도 byte 단위로 같고 SHA-256은 `c1afd1422eb05ef396bbc13b48fbd09d5966995de43d9f92147ebb1599d5b848`이다. Oracle prepared 8,880 / result reuse 7,823 / 실제 요청 1,057 / cache hit 0 / miss 1,057도 같다.

Native generation, cost projection, cost evaluations 4,828, cost cache hits 3,407, selected supply/definition action diagnostics가 동일했다. Runtime capability audit 13행과 conversion-frontier audit 20행은 process PID만 제외하고 비교해 같으며 runtime violation은 없다. Check cache hit는 82,050,731→82,050,743(+12), support query는 38,286,830→38,286,836(+6)으로 소폭 달랐다. 따라서 모든 내부 counter가 동일하다고 주장하지 않는다. 이 미세한 차이의 원인을 이번 단일 실행으로 분리하지 않았으며, objective·선택된 실행 진단·출력 동등성과 구분한다.

## 성능 결과의 해석

LogReg 전체 planning은 **7.568450924초, 19.09% 감소**했고 Optimizer는 **7.027949706초, 19.07% 감소**했다. Model 시간도 감소했으므로 단순히 Optimizer 작업을 모델 생성 시점으로 옮긴 결과는 아니다. 다만 A/B/D 각각의 기여를 분리하는 ablation은 실행하지 않았으며 한 번의 통합 변경 효과로 해석한다.

완료된 LogReg에서 `[MemoShapeReuse]`로 관찰한 재사용은 다음과 같다. optimizer 반환 시점까지 같은 Shapes instance에 누적된 수치이며 optimizer만의 별도 counter가 아니다. 실제 계산 수는 request−hit로, eviction 후 재계산도 포함하고 distinct input 수를 뜻하지 않는다.

| 분석 | 요청 | cache hit | 실제 계산 | hit 비율 |
|---|---:|---:|---:|---:|
| Binding guard query | 767,951 | 744,987 | 22,964 | 97.01% |
| Repeat-head proof | 66,164 | 64,956 | 1,208 | 98.17% |

MemoProgramModel의 movement context descriptor나 movement unit primitive를 별도의 cache로 저장하는 단계까지는 확장하지 않았다. Facts/Shapes 공통 경로의 재사용을 먼저 적용했고, source·activation·origin·retention에 의존하는 방문과 실제 cost factor 평가는 그대로 남아 있다. 따라서 “모든 중복 순회가 제거됐다”거나 “모든 남은 작업이 plan 선택에만 의존한다”고 주장하지 않는다. 낮은 비중으로 측정된 normalization 전략도 유지했다.

이번 비계측 실행에는 allocation sampling이 없으므로 새로운 allocated bytes 감소량을 수치로 주장하지 않는다. Lookup hit에서 key/clone을 생성하지 않는다는 소스·회귀 근거, 완료된 proof reuse counter와 wall/CPU/memory 관측을 구분한다. 이전 JFR 24.60→51.60초 또는 역사적인 비계측 25.20→39.08초와 이번 before/after를 혼합해 speedup을 계산하지 않는다.

## 검증 및 재현

- Production 수정 전 solver 회귀 22개 PASS. Full tuple hash collision, partial sentinel, cache 0/1/4096을 포함한다.
- Facts reference regression 3개 및 기존 shape/cost context regression 15개로 변경 전 동작을 고정했다.
- 최종 Maven `test-compile` PASS, 37.678초. Clean build에서 확인된 `Arrays` import 누락은 수정했다. 초기 실패 로그도 보존한다.
- 최종 통합 회귀 **46 classes / 423 tests PASS**, 48.832초. Memo solver, native rules, privacy, loop cost, exhaustive parity, resource-budget 및 새 회귀를 포함한다.
- Docker 평가 harness **45 tests PASS**. `git diff --check` PASS.
- Facts, solver, Shapes의 독립 review에서 material correctness must-fix는 발견되지 않았다. [독립 검토](../experiments/optimizer-reuse-20261014/independent-review.md)에 근거와 테스트 범위 한계를 기록했다.
- Frozen after engine의 source/main class/test class를 workspace와 비교해 불일치 0개를 확인했다. Manifest는 `after-engine-sha256.json`, 소스 변경은 `after-production.diff` 및 동결 source에 보존한다.
- 중간 Facts 35-test PASS는 최종 sparse index 이전 결과다. 최종 sparse 구현의 검증 근거는 통합 423-test 결과이며, 중간 로그와 합산하거나 대체하지 않는다.
- 기존 `TrainingWorkloadOperationCoverageTest`의 stale inventory golden 불일치는 이번 수정 범위 밖이다. 해당 class를 포함한 전체 repository tests가 모두 통과했다고 주장하지 않는다.

### 재현 가능한 명령과 근거

```bash
cd /home/mchoi/fedplanner-oracle-cost-20261013
mvn -o -B -DskipTests -Djacoco.skip=true clean test-compile
python3 experiments/optimizer-reuse-20261014/run-unit-suite.py reproduced
python3 -m unittest discover -s scripts/fedplanner/tests -p test_run_joint_boundary_e2e.py
```

성능 실행의 전체 명령은 [변경 전](../experiments/optimizer-reuse-20261014/optimizer-reuse-before-command.txt)과 [변경 후](../experiments/optimizer-reuse-20261014/optimizer-reuse-after-command.txt)에 저장한다. 소스·클래스·dependency 경로를 frozen engine으로 지정하고 공식 Docker wrapper를 사용한다. 재실행할 때는 새로운 run id와 결과 경로가 필요하다. 이번 작업에서는 각 조건을 추가 반복하지 않는다.

- [통합 JUnit 명령](../experiments/optimizer-reuse-20261014/final-unit-command.txt), [423-test 로그](../experiments/optimizer-reuse-20261014/final-unit.log)
- [최종 build 로그](../experiments/optimizer-reuse-20261014/final-compile.log), [harness 로그](../experiments/optimizer-reuse-20261014/final-harness.log)
- [Frozen engine hash manifest](../experiments/optimizer-reuse-20261014/after-engine-sha256.json), [검증 요약](../experiments/optimizer-reuse-20261014/verification.json)
- [변경 전 측정 요약](../experiments/optimizer-reuse-20261014/optimizer-reuse-before-summary.json), [변경 후 측정 요약](../experiments/optimizer-reuse-20261014/optimizer-reuse-after-summary.json), [전후 비교 JSON](../experiments/optimizer-reuse-20261014/optimizer-reuse-comparison.json)

## 잔여 한계와 다음 우선순위

이번 구현은 같은 분석의 반복 비용을 줄였고 LogReg의 exact search 탐색 공간은 동일하다. **GLM 300초 timeout은 미해결**이다. 제한 시간을 늘리거나 추가 실행하지 않았다. 전후 workload는 각각 LogReg 1회, GLM 1회이며 분산·신뢰구간이나 일반적인 speedup을 주장하지 않는다.

다음 성능 단계는 변경 후 GLM의 새로운 CPU profile로 남은 `movementGroupCost`/`inputCompatible` 방문과 solver 자체 비용을 다시 분리하는 것이다. 이전 JFR의 hotspot 비율을 이번 구현 이후에도 그대로 적용하지 않는다. 그 결과에 따라 기존 DIRECT/IMPOSSIBLE gate 뒤의 lazy context descriptor 또는 movement primitive 재사용이 필요한지 결정해야 한다. 후보 pruning, UNKNOWN 축약, 비용 근사, 원본 selected-cost audit 생략으로 timeout을 해결하지 않는다.

새 cache는 기존 frozen model과 동기 실행 수명에 한정된다. 사후 HOP graph mutation이나 Shapes의 병렬 공유는 지원 범위를 넓히지 않았다. 독립 리뷰의 낮은 우선순위 테스트 공백은 duplicate phase fact fixture, 동시 조회, support/bound별 전용 hash-collision fixture, false repeat-proof 전용 hit fixture다. 현재 전체 tuple equality와 완료한 결과만 저장하는 소스 계약은 확인했고, 관련 regression 및 exhaustive parity는 통과했다.
