# Session Issues — 2026-08-21

## 1. DP reconciliation still allocates complete decision-map snapshots

- **상태**: 해결됨
- **환경/조건**: `COMPILE_COST_BASED` (DP), authenticated `wan_light`
  compile-only Docker campaign, LOGREG worker 1 and KMEANS worker 2. Timing runs disable
  PlannerTrace, JFR, GC logging, runtime explain, and runtime execution; profiling uses a
  separate fresh JVM.
- **재현 절차**:
  ```bash
  python3 experiments/tools/run_compile_only_planner_benchmark_blocked.py \
    --stage-descriptor <stage>/stage-descriptor.json --profile wan_light \
    --workers 1 --workloads logreg --planners DP,Exact --repetitions 6
  ```
- **관측 증상**: commit `8024c38efd`의 change-aware reconciliation은 LOGREG DP를
  6.882초에서 6.680초로 2.9% 줄였지만 KMEANS DP는 3.526초에서 3.551초로 측정
  변동 범위 안에 머물렀다. immutable snapshot을 재사용한 `831b4903a8`은 KMEANS DP
  평균을 3.380초로 4.8% 줄였으나 LOGREG DP 중앙값만 6.558초로 1.7% 줄었고,
  LOGREG sampled planner allocation도 2.408 GB에서 2.364 GB로 1.8% 감소하는 데
  그쳤다. `Long` 377 MB와 `collectObservedMemberOutputs` 89.6 MB가 다음 명확한
  allocation 병목으로 남았다. 1차 primitive 변경 `b48ccea3d1`은 LOGREG DP
  중앙값을 6.558초에서 6.325초로 줄이고 `Long`을 307.5 MB까지 낮췄지만,
  eager 배열 네 개를 가진 `ObservedMemberOutputs`가 생성자 41.5 MB를 추가 할당해
  collect 경로 총량을 줄이지 못했고 KMEANS DP 평균도 3.566초로 개선되지 않았다.
- **원인 분석**: dirty-stage scheduling은 불필요한 후속 closure 호출을 줄이지만,
  실행되는 refinement마다 입력 decision map을 다시 복사한다. 또한 simulation cache의
  key가 base/lock `HashMap` 두 개를 복제하고 cache hit도 결과 `HashMap`을 다시 복제한다.
  동일한 immutable decision snapshot을 score/conflict/simulation cache가 공유하지 못해
  whole-forest traversal 감소가 allocation 감소로 이어지지 않았다.
- **해결 계획**:
  1. mutating transaction이 없는 refinement는 input map을 그대로 사용하고 실제 candidate
     변경 시에만 copy-on-write한다.
  2. simulation key/value를 immutable primitive `DecisionMapView`로 저장하고 cache hit에서
     복제하지 않는다.
  3. score key가 동일 compatible-plan cache를 가진 `DecisionMapView`를 재사용하게 해
     같은 snapshot의 재직렬화를 제거한다.
  4. 기존 targeted legality/closure tests와 package build를 통과한 뒤, 새 immutable stage에서
     동일 compile-only timing을 재실행한다.
  5. immutable `DecisionMapView`의 hot lookup은 primitive `long` 경로로 우회하고,
     conflict-family observed outputs는 insertion order와 conflict-to-null semantics를
     보존하는 primitive indexed sequence로 저장해 boxed key/map-node 반복 생성을 없앤다.
  6. observed-output common case는 두 member를 객체 내부에 저장하고 세 번째 member에서만
     배열로 승격한다. 큰 family의 점근적 조회 성능은 열두 member부터 lazy primitive hash
     index를 생성해 유지한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `docs/SESSION_ISSUES_2026-08-21.md`
- **검증**: primitive lookup/observed-output 변경 후 boundary/exact-component/function/
  transient/closure targeted test 15개와 parent-variant/output-decision integration test 6개를
  통과했다. lazy inline-storage 변경 후 동일한 21개 테스트도 다시 통과했다. commit
  `0f7f942dd7`의 package build와 authenticated Docker compile-only 10회 측정도 모두
  성공했다. LOGREG DP는 평균 6.340초(중앙값 6.316초), KMEANS DP는 평균
  3.570초(중앙값 3.587초)였다. JFR에서 `Long` sampled allocation은 `831b4903a8`의
  377 MB에서 308 MB로 줄었고, eager observed-output 경로 약 91.8 MB는 lazy inline
  representation에서 33.5 MB로 줄었다.
- **잔여 이슈**: immutable snapshot 재사용 후에도 score traversal 자체가 우세하면,
  affected decision component별 incremental score invalidation이 다음 단계다.
- **잠재 회귀 위험**: cached simulation result를 호출자가 수정하면 cache가 오염될 수 있다.
  `DecisionMapView`를 read-only value로 반환하고 모든 수정 지점이 candidate copy 또는
  `DecisionMapTransaction`을 소유하는지 targeted tests로 감지한다.
- **의사결정 근거**: 후보/제약/비용을 바꾸지 않고 동일 immutable planner state의 표현과
  cache ownership만 공유하는 behavior-preserving 최적화다.

## 2. DP score rebuilt the same selected forest for conflict and feasibility

- **상태**: 해결됨
- **환경/조건**: commit `0f7f942dd7`, authenticated `wan_light` compile-only LOGREG
  worker 1 JFR. Timing campaign과 profiling JVM은 분리했으며 timing에는 JFR, GC log,
  PlannerTrace, runtime execution을 사용하지 않았다.
- **관측 증상**: 하나의 immutable output-decision map을 score할 때
  `countIncompatibleDecisionMapPlans`와 `collectConflictsSingleBFS`가 같은 root forest에서
  같은 strict-compatible/fallback plan을 각각 선택하고 별도 BFS를 수행했다. `0f7f942dd7`
  JFR의 sampled allocation은 전자 67.1 MB, 후자 27.2 MB였고, 두 메서드는 각각 planner
  CPU hot path에도 나타났다.
- **원인 분석**: conflict-map cache는 selected conflict usages만 저장했고, 같은 traversal에서
  결정 가능한 incompatible required-output state, compiled-occurrence exact-state 불일치,
  transient reaching-definition output 불일치를 저장하지 않았다. 따라서 score cache hit 전
  최초 snapshot 생성 시 선택 forest를 만든 뒤, score가 structural feasibility를 위해 같은
  forest를 다시 만들었다.
- **해결**:
  1. `collectConflictForestSnapshot`이 required `(carrier Hop, output)` state를 한 번 선택하면서
     conflict usage, incompatible state 수, occurrence-family 불일치, transient-output 불일치를
     함께 수집한다.
  2. `ConflictForestSnapshot`이 위 immutable facts와 unrefreshed/feasible conflict map, score를
     같은 decision-map key 아래 공유한다.
  3. 별도 `countIncompatibleDecisionMapPlans` BFS를 삭제했다. 기존 root/additional-root seed,
     strict-compatible 선택 순서, fallback evidence, exact occurrence identity, analysis-owned
     transient relation은 유지했다.
  4. 이 변경은 memo frontier, boundary signature, feasibility constraint, cost term 또는 후보
     pruning을 바꾸지 않는다.
- **기각한 대안**: commit `3c41f69670`에서 conflict-state cache의 boxed key를 기존 primitive
  state table로 바꿨다. JFR상 boxed `ConflictForestStateKey` 16.8 MB는 제거됐지만 planner
  sampled allocation은 2.298 GB에서 2.295 GB로만 줄었고, KMEANS DP 2회 pooled 20개
  측정은 boxed 통합판 3.438초보다 primitive판 3.492초가 1.6% 느렸다. 이 workload에서는
  heap/GC가 병목이 아니고 primitive table의 추가 lookup 비용이 더 컸으므로 commit
  `af14a22107`에서 이 실험만 되돌렸다. 최종 tree와 JAR은 성능이 더 좋은 `84f012c117`
  tree와 byte-identical하다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `docs/SESSION_ISSUES_2026-08-21.md`
- **회귀 검증**: boundary frontier, exact component, function boundary, clone family,
  transient write/read, LOGREG forwarding, STEP-LM closure 등 15개 targeted test와
  multi-write/parent-sharing/required-output integration test 6개가 통과했다. 최종 commit
  `af14a22107`은 검증된 `84f012c117`과 같은 Git tree
  `95e2f5d7beebc872071f43989a35c99f5c89448d`를 가지며, fresh compile/package가 성공했고
  JAR SHA-256은 `15856c389ea26bccc6fe1b7474fd7bea41bdd10939b1ad7a424a85edf2da992d`다.
- **compile-only 재실험**: 최종 commit에 직접 묶인 immutable stage
  `eaaa538d43b3d7eb5e1181c763fc1c7cd2b7b1a392ba28a8a3950ea952369a39`에서 planner별
  1회 warm-up + 10회 측정을 수행했고 두 workload 모두 0 failures였다.
  - LOGREG, worker 1: DP 6.233초 평균/6.206초 중앙값, Exact 2.700초/2.580초.
    `0f7f942dd7` 대비 DP 평균 1.68%, 중앙값 1.75% 감소.
  - KMEANS, worker 2: DP 3.501초 평균/3.480초 중앙값, Exact 1.169초/1.145초.
    `0f7f942dd7` 대비 DP 평균 1.95%, 중앙값 2.99% 감소.
- **잔여 이슈**: 불필요한 두 번째 forest traversal은 제거됐지만, low-width LOGREG/KMEANS에서
  Exact는 여전히 DP보다 각각 약 2.31배, 3.00배 빠르다. 최신 JFR에서 남은 비용은 필요한
  conflict usage 구성, logical transient relation augmentation, root contribution, parent-variant
  sharing/materialization delta 계산에 분산돼 있다. 다음 최적화는 후보를 임의로 줄이는 대신
  immutable conflict facts와 sharing-cost decomposition을 component 단위로 증분 갱신해야 한다.
- **의사결정 근거**: 실측 compile latency를 우선해 allocation-only primitive 변형은 기각하고,
  후보 완전성과 score semantics를 보존하면서 실제로 end-to-end planner time을 줄인 단일
  forest analysis만 유지했다.
