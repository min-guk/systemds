# FedFirst / AggLocal v2 구현·검증 보고서

- 기준 HEAD: `fe758c413d4d156cec249248f99a744eaa45edb5`.
- 최종 상태: 구현 및 아래 명시된 범위의 검증 완료.
- 구현 범위: [v2 계획](FEDFIRST_AGGLOCAL_STREAMING_PLAN_2026-09-28.md)의 **공통 전체 후보 생성 유지 + 선택기 경량화**.
- 기존 알고리즘/검토 문서는 위 HEAD의 **수정 전** 기록이다. 현재 구현은 이 문서를 기준으로 읽는다.
- 커밋·push는 하지 않았다. Oracle, 공통 builder/closure, analysis authority, DP/Exact 알고리즘은 수정하지 않았다.

## 1. 무엇을 바꿨는가

두 기본 adapter가 `PolicyGreedyPlacementSelector`를 사용한다. 기존 설정 enum 이름은 유지한다.

| 항목 | 수정 전 | 수정 후 |
|---|---|---|
| 공통 후보 생성 | 전체 입력 tuple·candidate·realization·이동 후보 생성 | **그대로 공유** |
| 상태/row 선택 | 전역 first-feasible/backtracking | 입력 의존 순서의 단일 provisional commit + 단조 지원 전파 |
| FedFirst | FED/FOUT 선호 조합을 전역 탐색 | 실제 resident 입력으로 가능한 native FED 우선 |
| AggLocal | local-prefix hard projection, 실패 시 전체 MOVEMENT_FIRST 재시작 | 현재 AggBinary vector 출력의 LOUT 지역 선호 |
| 이동 선택 | 별도 조합 탐색 | consumer별 공통 physical pool 교집합에서 deterministic 선택 |
| 실패 | 탐색기/정책 relaxation 경로 | typed greedy/boundary 실패, 자동 재탐색 없음 |
| 완료 조건 | 기존 common legality/authority 검증 | 기존 검증 + 선택된 proof SCC의 외부 값 entry 검사 |

새 selector는 `PolicyFirstFeasiblePlacementSelector`, DP, Exact를 실패 대체 경로로 호출하지 않는다.
구현 비교/명시적 호출을 위한 기존 selector 자체는 남겼다. **비최적 휴리스틱이며 임의 CSP의 완전성은 보장하지 않는다.**

## 2. 실제 알고리즘

### 2.1 공통 선택 흐름

1. 이미 완성된 `PlacementAnalysis`에서 node-owned 상태와 exact candidate/realization/support clause를 가져온다.
   각각을 하나의 선택 row로 다룬다. rule/emission/realization/clause를 구조적으로 비슷한 새 객체로 위조하지 않는다.
2. state, realization reference, 실제 worker pool, 입력 binding, transient 및 function boundary의 지원 관계를
   invocation-local로 인덱싱한다. `Group.live`가 0이 될 때 그 그룹을 필요로 하는 row만 삭제한다.
   같은 realization의 OR clause 하나가 삭제되어도 다른 clause가 살아 있으면 reference는 유지된다.
3. 의존 그래프를 **반복형 SCC condensation**으로 producer-first 정렬한다. DAG는 입력부터 처리하고,
   cyclic unit 내부에서는 canonical 순서로 provisional하게 선택한다. 이 순서는 proof의 seed가 아니다.
4. 각 occurrence의 살아 있는 row를 정책 순위로 한 번 비교하고 하나만 commit한다. 나머지를 삭제하여
   지원 관계를 전파한다. 이전 결정을 복원하거나 전체 조합을 다시 탐색하지 않는다.
5. explicit input binding에 지정된 action은 그 row와 함께 고정된다. legacy unbound 이동 수요는 완성된
   assignment에서 한 번 수집하고, consumer별로 exact physical worker-pool 교집합을 구해 마무리한다.
   여기에도 relocation DFS나 Cartesian 조합 탐색은 없다.
6. 전체 node-owned assignment, 선택된 exact row, 입력 slot/source, action 활성화, pool/anchor 및 모든
   reaching writer를 기존 common validator로 검증한다. complete 검증은 `allowUnassigned=false`이다.
7. **선택된 proof graph를 별도로 구성**한다. 선택하지 않은 OR clause/단순 상태 제약을 외부 seed로
   빌리지 않는다. cyclic proof SCC에는 실제로 선택된 외부 값 entry가 필요하다. anchor geometry만으로
   순환 값을 초기화했다고 인정하지 않는다. seedless SCC는 typed boundary 오류로 종료한다.
8. 검증된 결과만 기존 atomic emission 경로에 넘긴다. HOP/registry 변이는 선택 도중 하지 않는다.

### 2.2 FedFirst

- 실제 resident FOUT 입력을 사용하는 native FED를 먼저 선호한다.
- 그 안에서는 native FOUT을 우선하되 runtime이 FED/LOUT만 지원하면 LOUT을 그대로 선택한다.
- 입력이 전부 local이면 CP/LOUT이 speculative upload보다 우선한다.
- hard constraint 때문에 필요한 anchored materialization은 정확한 기존 증거가 있으면 허용한다.
- 동순위는 입력 이동 수와 canonical row 순서로 결정한다. 미래 FED 개수/총 통신량을 최적화하지 않는다.

### 2.3 AggLocal

FedFirst에 아래 **현재 결과의 local-output 선호**만 더한다.

- AggBinary의 owned abstract 결과 shape가 column vector이고 실제 execution FType이 ROW.
- 결과가 row vector이고 execution FType이 COL.
- 우리 FULL one-worker 확장에서는 결과가 vector.

임의 aggregate를 CP로 바꾸지 않는다. 연산은 FED이고 출력만 LOUT일 수 있다. 후손 전체를 CP로 묶지 않으므로
다른 resident FED 입력이 있으면 FED에 재진입한다. 단, 즉시 소비자가 local input 대안을 전혀 갖지 않으면
불필요한 gather/re-upload를 피하도록 native FOUT 유지 선호가 우선한다. 공통 domain을 줄이는 규칙은 아니다.

### 2.4 경계·실패 계약

- `GreedyConflictException`: 현재 commit과 호환되는 row/pool을 찾지 못했다. 전역 infeasibility 증명이 아니다.
- `UnresolvedBoundaryContractException`: 선택된 cyclic proof에 외부 값 entry가 없다. emission 전에 실패한다.
- same-value/placement constraint cycle 자체는 proof cycle로 오인하지 않는다.
- 일반 reconvergent synthetic CSP에는 기존 탐색기가 찾지만 greedy가 못 찾는 해가 있다. 이를 회귀 테스트로
  명시하되, 기존에 성공하던 보호 DML을 expected-failure로 바꾸지는 않았다.

## 3. 복잡도와 계획 대비 구체화

**전체 compiler가 single-pass 또는 HOP 수에 선형이라고 주장하지 않는다.** 전체 후보 조합 생성 비용은 그대로다.

- 선택 row는 policy comparison 최대 1회, 삭제 최대 1회이다.
- 지원 group의 `live`는 감소만 하며, 0 전이는 1회이다. 역색인 watcher만 깨운다.
- scheduling SCC와 selected-proof SCC는 명시적 stack/queue로 처리하며 Java 재귀를 사용하지 않는다.
- canonical 정렬, signature/hash, 공통 후보 생성, 최종 validator/adapter 비용은 별도로 남는다.
- `Run.metrics`의 `candidateChecks/supportIncidences/deletedRows/decisionCommits/factEvents`는
  **kernel 작업 지표**다. 모든 shared validator scan이나 생성 시간의 대용 지표가 아니다.
- selected-only 검증과 consumer action index로 unselected candidate 재열거 및 반복 전체 action scan을
  줄였다. 남은 physical-edge/parametric common validator 비용까지 엄밀한 incidence-linear로 증명한 것은 아니다.
- certificate는 `POLICY_FEASIBLE`, explored plan 1/pruned plan 0, optimality false이다.
  decision/row 카운터를 explored plan 수로 위장하지 않는다. FedAll component metadata의 연결 정의도 통일했다.

계획의 일반적인 versioned `DEFERRED` API 대신, **입력이 이미 frozen eager analysis라는 조건**을 활용하여
SCC 의존 순서와 두 값(active/deleted)의 단조 worklist로 구체화했다. 비동기 fact 등록·대기 서비스나 SCC의 모든
인터페이스 조합표는 추가하지 않았다. 실제 selected SCC의 grounding을 별도 검사하므로 canonical 순서를
값의 초기 증거로 취급하지 않는다. 아직 생성되지 않은 future analysis fact를 참으로 가정하는 경로도 없다.

## 4. 변경 파일

- `placement/selector/PolicyGreedyPlacementSelector.java`: 공통 greedy core, 지역 정책, 지원 인덱스, SCC 검증.
- `placement/adapter/FedAllPlacementAdapter.java`, `HeuristicPlacementAdapter.java`: 기본 연결, projection/restart 삭제,
  truthful certificate. AggLocal의 `selectorGraph()`는 `analysis.graph()`와 **동일 객체**다.
- `fedHeuristic/FederatedPlannerFedHeuristicSinglePass.java`: 정책/trace 설명 정비.
- `placement/CandidateSelections.java`: complete selected-only authority 검증 및 action index.
  DP partial 검증은 기존 허용 범위를 유지한다.
- `placement/PlacementIdentity.java`: 기존 physical-pool 동등성에 대응하는 hash key.
- `placement/RelocationSelections.java`: 공통 audit-demand 조회의 재사용 가능한 분리.
- 새 테스트: `PolicyGreedyPlacementSelectorTest`, `PolicyGreedyGroundingTest`, Docker-only `PolicyGreedyDockerProbe`.
- 기존 heuristic 테스트는 obsolete materialization-maximal/강제-prefix **정책 assertion만** 변경했다.
  protected FOUT, privacy, slot/anchor 및 exact receipt 검증은 유지했다.
- `scripts/fedplanner/run_LAN_docker.sh`, `validate_greedy_docker.py`: frozen 외부 harness를 수정하지 않고
  `--greedy-validation` lane을 추가했다. 원래 wrapper 동작은 유지한다.

위 Java 경로의 공통 prefix는 `src/main/java/org/apache/sysds/hops/fedplanner/`, 테스트 prefix는
`src/test/java/org/apache/sysds/hops/fedplanner/`이다.

## 5. 검증 증거

### JUnit / build

- 수정 전 fresh baseline **21/21 PASS**: `target/fedpolicy-greedy-20260928/baseline.log`.
- 초기 전환 41/41 및 별도 derived authority 12/12 PASS 기록을 보존했다.
- 최종 확장 corpus: **125/125 PASS**, failures/errors/skips 0:
  `target/fedpolicy-greedy-20260928/final-targeted-2.log` (`mvn package` BUILD SUCCESS).
- 포함 범위: 보호 데이터 four-planner 11건, greedy 정책/4,096-depth/순서/동시 호출/비소유 receipt,
  직접 seeded/seedless exact cycle, local function-boundary cycle 및 OR clause 생존, pool/range 동등성, 독립 candidate/action plan-space oracle,
  anchor cleanup/clone/recompile, authority 변조 및 atomic rollback.
- PUBLIC privacy fixture는 실행 목록에서 제외했다. 테스트 성공을 위해 PUBLIC fixture의 privacy/Ignore를 바꾸지 않았다.
- `git diff --check`, Bash syntax, Python byte-compile을 검사했고 모두 통과했다. POM에 활성화된 별도 checkstyle/spotbugs/pmd
  gate는 없어 그러한 검사까지 통과했다고 주장하지 않는다.

### Docker 실행 원칙

모든 runtime/성능 측정은 저장소의 `scripts/fedplanner/run_LAN_docker.sh --greedy-validation`을 사용했다.
호스트 `run_LAN.sh` 또는 호스트 Java runtime benchmark는 사용하지 않았다.

- image: `sha256:3806cc615239d7e62a92fd27cc861f9d9c8b0bc41762fcb873270e163746a3da`.
- 2 CPU, 4 GiB container, 외부 network 없음, worker/coordinator는 container loopback 통신.
- Java 17, coordinator/scaling heap 1536 MiB, worker heap 768 MiB, stack 1 MiB.
- PRIVATE_AGGREGATE 4×3 source. nested MM=1926, elementwise sum=90, growing-loop sum=234.
- baseline은 `git archive HEAD`의 별도 build. source/JAR/image/명령/원시 로그를 manifest로 보존한다.
- 한 조건당 제외 warm-up 1회 + measured fresh JVM 5회. 실패/불리한 표본을 삭제하지 않는다.
- runtime audit를 켜고 DMLScript의 실제 성공 반환값·예외·수치 결과를 모두 검사한다.
- 이것은 **one-worker correctness/계획 microbenchmark**이며 multi-host LAN 성능 주장이나 전체 workload matrix가 아니다.

### 첫 paired 결과 및 조사

`target/fedpolicy-greedy-docker/run-jbjip25b/receipt.json`: baseline/current **72/72 수치 PASS**,
512/4,096/16,384/65,536 chain ×3의 **12회 stack/scaling PASS**. 이 결과는 최종 SCC 보강 전 빌드의 기록이다.

- 6개 조건의 selector+adapter(`otherPlanningNanos`) median은 약 **27–43% 감소**했다. pure kernel 시간은 아니다.
- FedFirst-loop의 전체 planning median은 **11.5% 증가**, compilation median은 **7.1% 증가**했다. 이를 숨기지 않는다.
- 해당 case의 공통 analysis median은 1793.2→2002.0 ms로 늘었지만, sample별 total−analysis의 median은
  326.3→265.4 ms로 줄었다. 공통 생성/closure 코드는 변경하지 않았다.
- 관측은 analysis 변동과 일치하나 단일 cohort만으로 원인을 단정하지 않았다. 첫 protocol이 baseline 전체 후
  current 전체를 실행한 temporal confound도 있었다. 최종 빌드는 SCC 보강 후 재검증하며, 사전 고정한
  repeat별 교대 engine 순서와 fresh worker로 비교한다. 이전 불리한 결과를 대체·폐기하지 않는다.

### 최종 빌드 결과 — PASS

`target/fedpolicy-greedy-docker/run-0jdrw4rr/receipt.json`: **72/72 수치 PASS**, runtime audit 오류 marker 0, **12/12 scaling PASS**.
기존·새 구현의 6조건 analysis fingerprint가 동일하고, 실행 시 manifest의 production source/JAR/probe SHA가
최종 파일과 일치함을 재확인했다. `verification-docker.json`에 별도 검증 결과를 저장했다.

아래는 warm-up을 제외한 5개 표본의 median(ms)이다. `선택+adapter`는 common final validation 일부까지
포함하는 `otherPlanningNanos`이며 pure kernel 측정이 아니다. 전체는 `CandidateE2EReceipt.totalNanos`다.

| 정책 / workload | 공통 analysis 기존→새 | 선택+adapter 기존→새 | 선택 구간 변화 | 전체 planning 기존→새 | 전체 변화 |
|---|---:|---:|---:|---:|---:|
| FedFirst / nested | 1469.3→1443.3 | 260.5→152.5 | -41.5% | 1954.6→1794.7 | -8.2% |
| FedFirst / elementwise | 1022.5→997.5 | 182.5→116.9 | -36.0% | 1366.7→1289.0 | -5.7% |
| FedFirst / loop | 1983.8→1840.5 | 187.7→126.5 | -32.6% | 2342.1→2160.3 | -7.8% |
| AggLocal / nested | 1400.0→1339.2 | 246.3→138.1 | -43.9% | 1897.1→1657.2 | -12.6% |
| AggLocal / elementwise | 846.9→874.3 | 181.8→146.7 | -19.3% | 1239.4→1158.3 | -6.5% |
| AggLocal / loop | 1688.8→1753.1 | 202.1→122.5 | -39.4% | 2069.0→2044.2 | -1.2% |

**이 고정 corpus에서는 선택+adapter 19–44%, 전체 planning 1–13% 감소**했다. 최종 비교의 전체
planning median +5% 초과 조건은 0개다. 첫 cohort의 FedFirst-loop 증가가 최종 교대 비교에서는 재현되지
않았고, 공통 analysis 변동과 시간순 confound를 분리해 기록했다. 원인을 확정적인 단일 요인으로 단정하거나
공통 생성 자체를 최적화했다고 해석하지 않는다. 두 cohort의 raw 결과를 모두 유지한다.

고정 `-Xss1m`의 합성 selector chain에서는 아래 결과를 얻었다. 공통 분석/후보 생성은 이 graph seam 측정에
포함되지 않는다. 시간은 3회 median이며 counter가 구조적 작업량의 주 증거다.

| 노드 N | candidateChecks | supportIncidences | decisionCommits | 시간(ms) |
|---:|---:|---:|---:|---:|
| 512 | 1,024 | 3,066 | 512 | 30.4 |
| 4,096 | 8,192 | 24,570 | 4,096 | 162.0 |
| 16,384 | 32,768 | 98,298 | 16,384 | 676.0 |
| 65,536 | 131,072 | 393,210 | 65,536 | 3038.4 |

`candidateChecks=2N`, `supportIncidences=6(N−1)`, `decisionCommits=N`, `deletedRows=N`을 확인했다.
모든 규모에서 stack overflow가 없었다. 각 JVM의 heap-pool peak 합 상한은 baseline 85.3–100.1 MiB,
current 82.1–101.5 MiB였다. 이는 동시에 사용한 전체 heap peak나 통계적인 메모리 개선 증명이 아니다.

재현 진입점:

```bash
scripts/fedplanner/run_LAN_docker.sh --greedy-validation \
  --image sha256:3806cc615239d7e62a92fd27cc861f9d9c8b0bc41762fcb873270e163746a3da \
  --baseline-root "$(cat target/fedpolicy-greedy-20260928/baseline-root.txt)"
```

검증 결론은 **현재 고정한 보호 corpus/authority/stack/수치/시간 gate의 통과**다. 계획 A1–A14를 모든
가능한 arity·CFG·배포 환경에서 완전히 증명했다는 뜻은 아니다. 아래 남은 범위를 그대로 공개한다.

## 6. 남은 한계

- 반환된 witness의 legality와 임의 그래프에서 해를 찾는 completeness는 다르다. 후자는 보장하지 않는다.
- 전체 후보 생성 비용은 줄이지 않았다. 생성이 지배적인 프로그램의 전체 planning 개선은 작을 수 있다.
- ROW/COL vector 조건은 orientation 단위 테스트로 확인했다. 추가한 partitioned protected MM fixture는
  선택기 이전 common analysis에서 `No privacy-safe physical placement`로 거절되어, Oracle/공통 domain을
  이번 작업에서 완화하지 않았다. FULL protected MM end-to-end와 기존 ROW/COL loop 계약은 별도로 통과했다.
- planner-generated anchor와 실제 `rmvar`를 연결한 종단 테스트, multi-worker runtime, 모든 고차 arity/큰 CFG,
  전체 repository suite는 이번 검증 범위 밖이다. existing anchor lifecycle/authority 단위 테스트와 구별한다.
- 즉, “문제가 전혀 없다”, “모든 프로그램에 선형/완전”, “모든 workload가 빨라진다”는 결론이 아니다.
