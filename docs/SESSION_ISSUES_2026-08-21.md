# Session Issues — 2026-08-21

## 1. DP reconciliation still allocates complete decision-map snapshots

- **상태**: 진행중
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
  통과했다. lazy inline-storage 변경 후 동일한 21개 테스트도 다시 통과했다. 새 commit의
  package build와 Docker compile-only 재측정은 진행중이다.
- **잔여 이슈**: immutable snapshot 재사용 후에도 score traversal 자체가 우세하면,
  affected decision component별 incremental score invalidation이 다음 단계다.
- **잠재 회귀 위험**: cached simulation result를 호출자가 수정하면 cache가 오염될 수 있다.
  `DecisionMapView`를 read-only value로 반환하고 모든 수정 지점이 candidate copy 또는
  `DecisionMapTransaction`을 소유하는지 targeted tests로 감지한다.
- **의사결정 근거**: 후보/제약/비용을 바꾸지 않고 동일 immutable planner state의 표현과
  cache ownership만 공유하는 behavior-preserving 최적화다.
