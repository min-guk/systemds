# FedFirst·AggLocal streaming single-pass 구현 및 56조건 planning 검증 계획

- 날짜: 2026-09-21
- 상태: **2026-09-21 사용자 요청으로 실행 순서·검증 범위를 수정한 계획. 이번 턴은 계획만 작성하며 커밋·푸시·production 구현·실험은 수행하지 않는다.**
- 실행 순서: 현재 소스 snapshot을 커밋하고 `origin/main`에 정상 push → streaming 구현 → protected worker=4의 ML10 + P1/P2 + SliceLine 2개 × 4 planner 검증.
- 사용자 표기: **FedFirst = 기존 FedAll policy**, **AggLocal = 기존 Heuristic demotion policy**. 클래스/enum을 일괄 rename하지 않고 실제 config→factory 매핑을 검증한다.
- 목표: 두 policy selector가 배치를 고른 뒤 전역 candidate 조합을 다시 DFS하지 않도록 한다. DP의 비용 최적화 경로는 유지한다.
- 보존: 공통 합법 candidate와 결합 관계, privacy/runtime legality, 실제 support·publication authority, deterministic receipt, runtime fallback 금지.

## 1. 이번 진단으로 확인한 사실

최종 JAR `815e75ae…`의 GLM Heuristic 진단에서 JVM elapsed 45.80초·79.57초의 두 main stack은 모두 `CandidateSelections.Search.solve:2184` → `ComponentSearch.solve:2407,2435` → `realizationsCanStillBeCompatible:1413` → `requiredInputSupport:727–728`였다. **component의 첫 feasible 조합 probe 단계**다. 최적성 증명만 중단하는 것으로 이 병목을 해결할 수 없다. 90초 진단은 timeout이며 성공 성능 수치가 아니다.

원시 로그: `/home/mchoi/g009-glm-optimization-20260921/heurprobe-results/fed4/mkl-heuristic-first/glm_dataset-P2P2D_coordinator_mkl-heuristic-first_g009-glm-heurprobe-20260921_lan_coordinator1.log`; SHA-256 `22cae3729fd0ac97a2e827b18ca2652879b26e0cd3ee8caa14a4c2267f98947b`.

현재 구현 근거(경로 prefix `src/main/java/org/apache/sysds/`):

| 사실 | 소스 |
|---|---|
| 두 adapter가 같은 first-feasible selector 사용 | `hops/fedplanner/placement/adapter/FedAllPlacementAdapter.java:57–71`, `HeuristicPlacementAdapter.java:55–80` |
| placement 선택 후 별도 candidate 선택 호출 | `hops/fedplanner/placement/selector/PolicyFirstFeasiblePlacementSelector.java:111–123,190–191`, `PolicyCandidateSelectionView.java:52–58` |
| 뒤의 candidate search는 별도 component DFS | `hops/fedplanner/placement/CandidateSelections.java:1128–1131,2176–2201,2431–2442` |
| 매 분기 선택된 support·transient·function 관계 재검사 | `hops/fedplanner/placement/CandidateSelections.java:1403–1423,2406–2411` |
| support getter가 호출마다 distinct/sort/list 생성 | `hops/fedplanner/placement/PlacementAnalysis.java:726–728` |
| heuristic에 demotion/local-prefix/reentry 정책 존재 | `hops/fedplanner/placement/adapter/HeuristicPlacementAdapter.java:199–293` |
| 현재 certificate는 upper-bound와 incumbent를 결합 | `hops/fedplanner/placement/selector/PlacementCertificate.java:12–20,42–43` |

FedAll의 확인된 과거 GLM 실패는 8GB/옛 JAR에서 공통 analysis의 proof signature 정렬 중 OOM이었다. 최종 JAR FedAll은 미실행이며, 위 selector 병목이 실제 재현됐다고 주장하지 않는다.

## 2. 먼저 바꿔야 할 정책 계약

**제안:** streaming은 immutable 공통 관계를 iterator/index로 읽는다는 뜻이고, single-pass는 selector가 준비된 결정 단위를 한 번 commit하며 이미 commit한 결정을 되돌리지 않는다는 뜻으로 정의한다. 공통 analysis/인덱스 전처리까지 한 번의 선형 순회라고 주장하지 않는다.

- FedAll: 현재 선택과 함께 합법적으로 실현 가능한 FED/FOUT 대안을 우선하는 결정적 greedy 정책. 전체 FED 수·PRESENT 수·이동 수의 전역 optimum을 증명하지 않는다.
- Heuristic: 기존 demotion/local-prefix/reentry 정책을 적용한 뒤 같은 constructive engine을 사용한다. 금지된 재업로드를 싸다는 이유로 부활시키지 않는다.
- 기존 candidate-row 최적화·canonical optimum 대신 명시적인 local preference와 stable original identity tie를 사용한다. **선택 결과 변경을 포함하는 정책 변경**이지 결과 동일성 보장 최적화가 아니다. 이전 exact tie를 보존할 경우 그 증명 비용도 남을 수 있다.
- success certificate는 실제 선택된 완전한 plan의 합법성만 보증한다. global upper-bound equality나 exhausted search를 꾸며 넣지 않는다. 기존 DP certificate와 의미를 분리하거나 policy-result 경계를 명시한다.

### 2.1 제안하는 결정적 local preference

공동 witness까지 존재하는 대안에 대해 다음 tuple의 오름차순으로 선택한다. 이것은 **새 정책의 명시적 제안값**이며 기존 전역 optimum과 같다고 주장하지 않는다.

1. state rank: `FED/FOUT=0, FED/LOUT=1, CP/FOUT=2, CP/LOUT=3`. legality에서 금지한 상태는 rank와 무관하게 대상이 아니다.
2. PRESENT 입력 수: FED에서는 많은 순서, CP에서는 적은 순서.
3. exact anchor-aligned 입력 수: 많은 순서. geometry·worker·authority 동등성의 실제 판정만 사용한다.
4. 이번 선택이 새로 요구하는 물리 action 수: 적은 순서. 이미 committed된 동일 physical action identity는 중복 계산하지 않는다. 미래 consumer의 예상 공유 이득이나 전체 최적 이동 수는 계산하지 않는다.
5. 동률: 기존 canonical identity 순서의 `(occurrence, rule, emission, realization/support clause, ordered action keys)` tuple. 메모리 주소·allocation 순서·HashMap iteration은 사용하지 않는다.

AggLocal은 먼저 기존 marker/local-prefix/reentry 제약을 적용한 뒤 같은 tuple을 쓴다. 예: local-prefix의 CP/LOUT는 FedFirst가 선호할 FED/FOUT보다 낮아서가 아니라 AggLocal 정책상 강제된 선택이다. 두 FED/FOUT witness가 같은 PRESENT/정렬 특성을 갖고 하나는 기존 action을 재사용하고 다른 하나는 새 action을 요구하면 재사용 쪽을 선택한다. 동률 테스트는 정확한 원본 ID를 고정해 예상 winner를 검증한다.

## 3. 일반 그래프에서 불가능한 보장

임의의 결합 제약에 대해 **무조건 한번 확정·무백트래킹·가능한 plan이 있으면 반드시 성공**을 모두 보장할 수는 없다. 첫 producer에서 A/B 중 A를 골랐는데 뒤의 여러 consumer의 공동 제약이 B만 허용할 수 있다. DAG diamond·공유 action만 있어도 발생하므로 SCC 처리만으로 충분하지 않다. 각 consumer별 witness 존재는 동일한 공유 producer choice를 쓰는 공동 witness 존재와 다르다.

따라서 strict single-pass의 적용 범위를 다음처럼 구분한다.

1. **인증된 구성 가능 영역:** committed predecessor witness를 고정해도 후속 완성 가능성이 보존됨을 증명한 규칙/관계에 한해 즉시 commit.
2. **결합 경계:** fan-out/reconvergence, 동일 상태/alias, 함수 다중 caller, TR/TW, loop backedge, shared action/authority를 정확한 관계로 보존한다. producer 방향 그래프의 SCC뿐 아니라 이 제약들을 잇는 factor graph의 공유 경계를 사용한다. 검증된 joint boundary summary가 있을 때만 단위째 commit.
3. summary가 없으면 unresolved를 유지하고 명시적인 `POLICY_CONSTRUCTION_STUCK` 또는 unsupported result를 반환한다. 이를 `NO_LEGAL_PLAN`으로 바꾸지 않는다. 실패 result는 성공 receipt를 만들거나 runtime으로 넘어가지 않는다.

공동 summary를 만드는 과정이 조합 탐색을 요구하면 그 비용을 숨기지 않는다. boundary 변수 수 w와 변수당 대안 수 d일 때 단순한 exact summary도 최대 d^w 규모가 될 수 있다. 자동 DP fallback이나 전체 DFS를 이름만 전처리로 옮기지 않는다. 모든 입력의 feasible completeness가 필요하다면 strict single-pass가 아닌 별도 탐색 계약이 필요하다.

## 4. 제안 실행 경로

```text
immutable full candidate/support relation + legality indexes
  → selector-local policy view
  → ready decision units (data + support + boundary + action dependencies)
  → candidates compatible with committed witness, as a stable iterator
  → select and commit full candidate + support + actions atomically
  → notify dependent units / update affected constraint counters
  → one final legality validation
  → existing authorized plan application
```

### 4.1 PlacementState만 먼저 고르지 않는다

결정은 최소한 `(state, candidate rule, emission, support clause, source witness, actions/owners)`를 함께 갖는다. 모든 필수 producer support가 실제 committed witness와 일치해야 한다. 미선택 producer에 대한 단순 존재성으로 receipt를 확정하지 않는다. SCC 내부는 검증된 joint witness를 원자적으로 commit하고, 미grounded cycle을 자체 증명으로 사용하지 않는다.

### 4.2 현재 선택 조건으로만 읽는다

기존 candidate relation에 대한 exact selected-source index를 사용한다. iterator는 모든 합법 행에 대한 접근 능력을 유지하며, selector가 현재 선택과 불일치하는 행을 건너뛰는 것은 공통 공간 삭제가 아니다. 모든 joint product를 먼저 list로 만들지 않는다. 그러나 압축 relation이 결합 제약을 풀거나 producer 생성 단계에서 다시 전개되면 end-to-end streaming 완료가 아니다.

### 4.3 검사도 변경분만 반영한다

선택된 receipt map, cached required support, source→dependent constraints, action refcount, owner/geometry/authority dependency를 selector lifetime 내 유지한다. 선택 하나마다 전체 receipt/transient/function 관계를 다시 훑는 대신 영향받는 constraint만 검사한다. 최종 검증은 기존 계약을 유지하며 **재선택/최적화 없이** 한번 수행한다.

결정 수명주기는 `UNRESOLVED → PROVISIONAL → COMMITTED`다. PROVISIONAL은 성공한 결정/receipt가 아니며 해당 경계가 아직 열려 있다는 뜻이다. COMMITTED 이전에 모든 cross-boundary relation에 대해 source/owner identity, geometry, authority revision, 실제 support 및 남은 경계의 공동 continuation witness가 있어야 한다. 개별 source 존재성만으로는 충분하지 않다. 공유 action은 동일 exact identity로 소유권과 refcount를 유지하고 사용 consumer가 닫힐 때까지 유지하며, 단위 transaction이 완성되기 전 실제 plan에 publish하지 않는다.

미해결 continuation이 남아 있으면 최종 verifier 호출 전에 `POLICY_CONSTRUCTION_STUCK`으로 종료한다. 모든 단위를 certified COMMITTED로 표시했는데 기존 full verifier가 충돌을 발견하면 그때는 planner invariant bug다. 두 상태 모두 성공 receipt·runtime fallback·뒤늦은 암묵적 relocation을 허용하지 않는다.

### 4.4 DP와 분리하되 중복 엔진은 피한다

두 policy adapter가 공유하는 constructive selector 경로를 만든다. `CandidateSelections`의 exact engine은 아직 다른 호출자가 있으면 보존하되 두 adapter의 call graph에서는 제거한다. proof validator, action identity, canonical receipts, application transaction은 기존 구현을 재사용한다. DP의 shared analysis 공간과 비용 모델은 변경하지 않는다.

## 5. 수정된 실행 순서와 수용 기준

| 단계 | 작업 | 완료 게이트 |
|---|---|---|
| P0 | 현재 소스 snapshot commit·origin/main push | 8절의 source commit C0와 remote main SHA가 일치. 이 확인 전 신규 streaming 구현을 시작하지 않음 |
| P1 | workload·privacy·planner·측정 경계 고정 | 9절의 ML10·P1/P2·SliceLine ADULT/COVTYPE × 4 planner = 56개 cell manifest. 모든 cell의 script·input·privacy·config·자원 고정 |
| P2 | 공식 harness 지원 복구 | 14 workload 모두 `run_LAN_docker.sh`의 명시적인 planning-only 경로에서 script/metadata/config/receipt preflight 통과. 미지원 표기만 남긴 채 최종 검증 완료로 처리하지 않음 |
| P3 | 계약·regression oracle 및 적용 가능 경계 확인 | protected diamond·alias·TR/TW·action 공유 반례; local tuple 예상 winner; 14 script의 공유 factor/함수/loop 경계를 분류하고 아래 P3 gate 통과. 기존 exact tie 기대와 새 policy 기대를 명시적으로 분리 |
| P4 | 원자적 witness 선택의 streaming engine 구현 | state→candidate 전역 재탐색 경로 제거; committed unit rollback=0; exact/global DFS 호출=0; 미선택 source 존재성만으로 commit 금지 |
| P5 | iterator·선택조건 인덱스·증분 검증 | 전체 candidate product list를 selector에서 새로 생성하지 않음; cached support 사용; 영향받는 관계만 갱신; 기존 full validator와 결과 일치 |
| P6 | fan-out·함수·TR/TW·loop·shared authority 처리 및 두 adapter 통합 | 공동 witness가 증명된 단위만 확정. 테스트에서 성공과 구성 미완료를 구분; demotion/local-prefix/reentry 유지; DP 코드/목적함수/공통 공간 유지 |
| P7 | certificate·코드 리뷰·빌드·GLM canary | 성공은 완전한 policy-feasible receipt로만 표시. 표적 테스트·빌드·diff/static checks. GLM 각 planner 1회 정상 planning 목표; 막히면 작은 반례로 복귀 |
| P8 | 최종 JAR 고정 후 56조건 validation | 10절을 따라 동일 최종 JAR, workload마다 4 planner, 조건당 1회. 동일 JAR/manifest의 P7 실행은 재사용. 실패·timeout·정책 구성 미완료는 PASS 아님 |
| P9 | 결과·소스·한계 보고 및 구현 commit 분리 | C0/C1 소스, JAR/config/input/privacy SHA, 56행 status·timing·memory·receipt와 미완료 항목 제출. 초기 snapshot과 신규 구현 diff를 분리 |

P4–P6 변경 대상은 `PolicyFirstFeasiblePlacementSelector`, `PolicyCandidateSelectionView`, `CandidateSelections`, 두 policy adapter와 기존 certificate/application 경계다(1절 소스표). 별도 중복 planner framework를 만들지 않는다. selector에서 참조되지 않게 된 exact 경로도 DP/다른 호출자 사용 여부를 확인하기 전 삭제하지 않는다.

**P3 적용 가능성 gate:** 14 script의 공통 compiled factor 경계(공유 입력/합류·함수·TR/TW·SCC·action owner)를 inventory하고 frontier 폭과 대안 곱의 상한을 기록한다. 별도 56회 benchmark나 전면 계측 없이 기존 relation/counters와 작은 재현을 사용한다. summary는 기존 정확 relation의 선택조건 projection/공동 join 및 재사용 가능한 witness handle로 구성하며 상관관계를 보존한다. 단순 독립 Cartesian product로 풀지 않는다.

초기 지원 범위는 기존 relation row/edge를 공유하는 선형 크기의 summary 또는 정확성이 입증된 연산별 constructive rule이다. **기존 입력 relation 규모를 넘는 새로운 Cartesian row 전개가 필요하면 이를 숨겨진 전처리 DFS로 만들지 않고 P3/P6 설계 미완료로 남긴다.** 180초 planning 자원 한도와 전체 JVM 한도를 유지하며, resource guard는 미지원/구성 미완료를 반환할 뿐 candidate를 cap/prune하지 않는다. 해당 GLM 경계에 대해 더 작은 정확한 separator·새 constructive witness 규칙을 도출하고 작은 oracle로 검증한 뒤 진행한다. 이것이 불가능하면 strict single-pass와 56/56 목표의 충돌을 구체적 반례로 보고하며, 지원되지 않는 cell을 PASS로 바꾸지 않는다.

개발은 작은 regression → 논리 묶음 구현 → 필요한 GLM 단회로 진행한다. 완성 전 56조건 전체 matrix를 매번 돌리지 않는다. 최종 matrix에서 새 버그가 확인되면 해당 protected 반례를 먼저 고치고 JAR를 다시 고정한다. **최종 결과표의 56개 성공을 서로 다른 source/JAR에서 모아 하나의 검증처럼 표시하지 않는다.**

성능 비교는 **정책 변경 비교**임을 명시한다. 기존 timeout을 성공 baseline으로 환산하지 않는다. 기존 DP-local/global 45.779/44.644초는 C0 이전 작업 트리의 성공 참고값이며 신규 최종 JAR의 검증을 대체하지 않는다. 성공한 동일 조건 baseline이 없는 planner는 절대 시간만 보고한다.

## 6. 복잡도와 한계

실제로 iterator가 검사한 행 수를 R, 그 행들에서 확인한 support/constraint 참조 수를 S, 준비한 인덱스 크기를 I라 하면, 인증된 one-pass selector의 목표는 `T_select = O(I + R + S)`다. 이 식은 후보 인덱스 조회·지역 preference 순서가 준비되어 있고 참조당 갱신이 bounded한 경우에만 적용된다. 정렬 비용, boundary summary 생성 비용, 공통 analysis 비용은 별도로 포함해야 한다. R 자체가 작은 값이라는 보장은 없다.

메모리 목표는 선택된 witness + dependency indexes + 미해결 frontier다. frontier가 작다는 보장도 없고 기존 eager analysis 메모리까지 없어지는 것은 아니다. Strict single-pass의 미지원/구성 실패를 60초 내 성공으로 세지 않는다.

## 7. 남는 위험

- local greedy 선택은 이전 전역 candidate policy보다 이동 수가 많거나 다른 winner를 낼 수 있다. 정확한 정책 변경으로 문서화하고 작은 oracle의 legality와 policy trace를 검증한다.
- GLM의 실제 결합 관계가 인증 가능한 streaming 범위인지 아직 확인되지 않았다. 성공을 미리 보장하지 않는다.
- proof/action identity를 단순 placement나 비용 동일성으로 합치지 않는다. 여러 selector의 목적이 다르므로 공통 후보 관계를 축소하지 않는다.
- 현재 재사용 가능한 full validator는 유지한다. 거기서 검출된 충돌을 runtime fallback/implicit relocation으로 숨기지 않는다.

## 8. 실행 첫 단계: 현재 소스 snapshot을 origin/main에 보존

계획 작성 시 확인한 상태:

- repo: `/home/mchoi/systemds-g009-integration`
- 현재 branch: `integration/g009-baseline-20260919`
- HEAD와 실제 원격 `origin/main`: `a6281207cf520171af47f5f8755a34cbd28ecf37` (이번 턴 `git ls-remote` 확인).
- origin: `git@github.com:min-guk/systemds.git`.
- 현재 tracked 수정 18파일 외에 신규 source/test/docs가 있다. 이전 GLM 정확성·공간 보존 변경을 포함하며 전부 이미 검증 완료된 release라고 간주하지 않는다.

실행 시 절차:

1. branch·remote tip·현재 diff를 다시 확인하고 현재 작업 파일의 포함 목록을 고정한다. source/test/docs와 재현 manifest는 포함하되 데이터·JAR·Docker 결과·인증정보·`.omx` runtime state는 commit하지 않는다. `git add .`로 다른 작업을 무분별하게 포함하지 않는다.
2. `git diff --check`, 대상 파일/secret 검사, 현 snapshot의 테스트 결과·known failure를 기록한다. 기존 `OracleFacadeTest`의 별도 실패, 미구현 G4/G6, 기존 benchmark 조건 차이를 숨기지 않는다. snapshot을 보존하기 위해 불필요한 전면 정리를 먼저 하지 않는다.
3. streaming 변경 전 현재 source를 별도 commit **C0**으로 만들고 source tree SHA를 기록한다. C0는 재현 가능한 시작 snapshot이지 모든 workload가 성공하는 `B_correct`라고 이름 바꾸지 않는다.
4. 일반 fast-forward push `git push origin HEAD:refs/heads/main`을 수행하고 `git ls-remote origin refs/heads/main`이 C0인지 확인한다. 강제 push·원격 history 삭제는 하지 않는다. 원격이 움직였으면 타 변경을 보존하며 재통합하고 conflict를 해결하기 전 push하지 않는다.
5. C0 확인 후에만 P1 이후 작업을 시작한다. 구현은 별도 **C1** 및 작은 논리 commit으로 구분한다. 이번 요청의 최초 push와 최종 구현 검증을 한 작업으로 뭉뚱그리지 않는다. 최종 구현의 추가 remote push는 별도 명시적 실행 범위로 기록한다.

## 9. 검증 대상: 14 workload × 4 planner

### 9.1 네 planner의 실제 매핑

| 사용자 이름 | config | 현 enum |
|---|---|---|
| FedFirst | `mkl-single-pass` | `COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS` |
| Heuristic / AggLocal | `mkl-heuristic-first` | `COMPILE_FED_HEURISTIC_SINGLE_PASS` |
| DP-local | `mkl-cost` | `COMPILE_COST_BASED` |
| DP-global | `mkl-exact` | `COMPILE_EXACT` |

근거: 외부 harness `experiments/code/conf/`의 위 XML 각 4행 및 repo `src/main/java/org/apache/sysds/hops/ipa/FederatedPlannerFactory.java:40–47`, `FTypes.java:24–30`; AggLocal alias는 `docs/SESSION_ISSUES_2026-09-15.md:79`의 policy view 설명과 일치한다. **legacy `mkl-fout`/`mkl-heuristic`를 대신 사용하지 않는다.** 이름만 맞추지 말고 실제 factory class·새 selector route를 invocation receipt에서 확인한다.

### 9.2 workload 목록

ML10 근거: `/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/metadata/ml10-harness.log:5–14`. 기존 ML10은 아래 목록이며 **LM을 임의 추가하거나 ALSCG/GNMF/GMM을 빠뜨리지 않는다.**

| ID | workload | 입력/스크립트 기준 | planner 수 |
|---|---|---|---:|
| W01 | PCA | 기존 P2P2D `pca` | 4 |
| W02 | ALS | 기존 P2P2D `als` | 4 |
| W03 | ALSCG | 기존 P2P2D `alsCG` | 4 |
| W04 | KMeans | 기존 P2P2D `kmeans` | 4 |
| W05 | GNMF | 기존 P2P2D `gnmf` | 4 |
| W06 | GMM | 기존 P2P2D `gmm` | 4 |
| W07 | LogReg | 기존 P2P2D `logreg` | 4 |
| W08 | L2SVM | 기존 P2P2D `l2svm` | 4 |
| W09 | StepLM | 기존 P2P2D `steplm` | 4 |
| W10 | GLM | 최종 성공한 P2P2D label 전처리·인자 그대로 | 4 |
| W11 | P1 | `P1_FULL`, 기존 corrected scalar aggregate sink | 4 |
| W12 | P2 | `P2_PREP`, 기존 protected preprocessing 및 명시적 metadata release 계약 | 4 |
| W13 | SliceLine-ADULT | 정식 prep-backed X=32,561×13, prepared error vector | 4 |
| W14 | SliceLine-COVTYPE | 정식 prep-backed X=581,012×54, prepared error vector | 4 |
| 합계 | **14 workload** | **worker=4, privacy 적용, planning-only** | **56** |

사용자의 “작은 거 2개”에 따라 정식 SliceLine 네 데이터셋 중 **전처리된 X의 원소 수(rows×cols)**가 작은 ADULT·COVTYPE을 선택한다. 실제 planning 시간이 짧다는 사전 보장이 아니라 재현 가능한 크기 기준이다. frozen metadata `/home/mchoi/cofee-w1357-extra-ml-stage-20260905-79de4be/data/fed_<DATASET>_features_1.json.mtd`의 rows/cols 기준: ADULT 423,293개, COVTYPE 31,374,648개, KDD98 44,748,228개(95,412×469), USCENSUS 167,163,380개(2,458,285×68). 행 수만으로 고르면 다른 두 개가 되므로 선택 기준을 manifest에 남긴다.

**397×5 SLICELINE fixture와 별도 32,561×14 ADULT 진단 입력으로 대체하지 않는다.** prep-backed ADULT는 `experiments/code/workloads/sliceline/dataprep_adult.dml:12–13`에서 label과 첫 feature를 제거한 13열이다. 14열 stage의 파일을 같은 이름으로 섞지 않고 dataset provenance별 isolated data root와 SHA를 고정한다.

### 9.3 입력·privacy 계약

- ML10: 기존 P2P2D X=50,000×2,100, supervised Y=50,000×1. 사용하는 모든 X/Y 입력의 기존 `private-aggregate` metadata를 유지한다. 알고리즘별 파라미터·seed·입력 전처리는 기존 유효 script를 고정하며 성능을 위해 반복 수·차원을 줄이지 않는다.
- P1: X=100,000×68, `P1_FULL`의 corrected aggregate sink. P2: X=100,000×1,001 및 Y=100,000×1. 기존 protected metadata와 script의 입력 타입/전처리 계약을 보존한다.
- SliceLine: 원본 prep-backed X와 그 데이터에 대응하는 prepared error vector를 고정하고 worker4 metadata·descriptor의 보호 표시를 확인한다. 단순 label을 error vector로 바꾸거나 privacy가 없는 error 입력으로 몰래 대체하지 않는다.
- worker4에 대해 partition 합계·range coverage·중복/누락·column 수·실제 storage metadata·coordinator descriptor의 privacy를 일치시킨다. descriptor만 보호하거나 반대로 remote metadata만 보호한 상태로 검증하지 않는다.
- P2의 `allowPublicRecodeMetadata=true`는 기존 inline release policy와 함께 해당 metadata 용도로만 적용한다(`tmp/p2/gen_P2_PREP_P2_4.dml:8–16,29–35`). raw protected input을 PUBLIC으로 바꾸지 않고 GLM 등 다른 workload에 opt-in을 전역 적용하지 않는다.
- 기존 prepared data의 worker4 partition이 없으면 동일 prepared 데이터에서 정확한 partition과 metadata를 생성/검증한다. 전체 `dataprep_*`에는 ML 학습이 포함될 수 있으므로 planning-only 검증 명목으로 그 학습을 실행하지 않는다. 데이터 준비와 측정 구간은 별도 기록한다.
- PUBLIC-only fixture를 대신 돌려 성공 처리하지 않는다. repo 지침으로 ignore하는 PUBLIC-only 단위 테스트 수는 보고하고, protected fixture와 명시적인 제한적 metadata release는 구분한다.

## 10. 공식 harness와 단회 검증 프로토콜

### 10.1 실행 경로 복구는 필수 구현 항목

외부 harness root: `/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921`. `experiments/run_LAN_docker.sh:1578–1602`의 기존 frozen 목록은 ALSCG/GNMF/GMM/SliceLine을 포함하지 않으며 GLM은 별도 diagnostic 예외다. P1/P2는 목록뿐 아니라 generator·validator·receipt 경로까지 일치해야 한다. 이전 `Unknown --alg/--salg P1_FULL` 실패를 반복한 후 “미지원이므로 완료”라고 하지 않는다.

1. 14 workload를 지원하는 **명시적 별도 planning-validation profile**을 기존 launcher에 연결한다. 기존 frozen campaign 목록의 의미를 몰래 바꾸지 않는다.
2. registry 하나에서 workload id, CLI route, DML generator/template, 입력 shape/privacy, 허용 planner, receipt validator를 연결한다. 존재하지 않는 `_fed.dml` 이름만 allowlist에 추가하는 수정은 불충분하다.
3. dry-run/preflight 테스트로 56개 고유 cell 조합·중복 없음·모든 template 존재·입력 접근 경로·정확한 planner enum을 확인한다. 실제 worker 준비 방식은 기존 planning-only 계약을 유지하고 학습/실행 instruction은 실행하지 않는다.
4. 최종 benchmark는 **오직 이 `run_LAN_docker.sh` 경로**로 수행한다. 직접 Java/Docker/`run_LAN.sh` 실행을 성공 receipt로 대체하지 않는다.
5. harness는 SystemDS repo 밖에 있으므로 소스/patch SHA를 별도로 보존한다. 변경된 launcher·generator·validator가 어느 revision인지 최종 artifact manifest에 포함한다. 사용자 승인 없이 다른 remote repository에 push하지 않는다.

### 10.2 실행 조건

- worker=4, LAN, 같은 coordinator/worker 설정과 CPU allocation, 같은 workload 내 네 planner의 input/privacy/seed/공통 compiler 설정 동일.
- coordinator `CAMPAIGN_COORDINATOR_JAVA_OPTS`로 기존 16GiB/8 active processors 설정을 실제 container에서 확인한다. host 변수만 설정하고 Compose가 기본 8GiB를 사용하는 오류를 반복하지 않는다. Planner 선택에 필요한 XML 차이는 명시한다.
- planning-only: `runtime_executed=false`, `execution_seconds=0`, 완전한 선택·최종 검증·LOP/runtime-program 생성 완료 후 종료. **ML training workload의 planning을 검증하는 것이지 ML training runtime을 수행하는 것이 아니다.**
- 작은 regression과 빌드는 직렬 실행한다. 최종 성능 cell도 직렬로 실행해 Docker·메모리 경합을 피한다. 코드/테스트의 서로 독립된 구현·리뷰는 bounded native subagent로 나눌 수 있으나 같은 파일 동시 편집과 Maven 동시 실행은 하지 않는다.
- 조건당 최종 JAR로 **1회**. 반복 pilot/ABBA는 하지 않는다. 공통 timeout 기본 180초를 manifest에 고정하고 timeout은 미완료로 기록한다. 실패 후 수정·새 JAR로 재검증한 것은 같은 조건의 독립 성능 반복 표본으로 평균내지 않는다.
- GLM canary에서 DP-local → FedFirst → AggLocal → DP-global 순으로 확인하고, 같은 최종 JAR/manifest이면 최종 56행에 재사용한다. 나머지도 workload별 같은 순서로 검증한다. GLM의 정상 전체 초기 planning <60초 목표는 유지하고 다른 workload에 미합의 60초 완료 조건을 임의 부과하지 않는다.
- 현재 home filesystem 여유가 약 0.6GB뿐이다. 실행 P1에서 빌드·로그 예상 용량과 실제 사용 가능한 filesystem을 점검한다. 타 작업 데이터/소스/로그를 지우지 않고 이번 작업 소유의 재생성 가능한 임시 산출물만 안전하게 정리하거나 확인된 다른 저장소를 사용한다. 공간 부족을 알고도 대형 빌드/데이터 복제를 시작하지 않는다.

### 10.3 측정과 결과 판정

각 cell에 source commit/tree/JAR SHA, harness SHA, script/input/metadata/privacy/config SHA, run id, 자원 조건, `T_initial`, CandidateE2E exclusive phases, selector 관련 counters, coordinator peak RSS와 측정 방법, receipt/hash, runtime flag, error/timeout을 기록한다. 기존 최소 timer와 외부 프로세스 RSS 측정을 우선하며 새로운 대규모 계측 framework를 만들지 않는다.

상태는 최소 `PASS`, `TIMEOUT`, `PLANNING_ERROR`, `POLICY_CONSTRUCTION_STUCK`, `PROVEN_INFEASIBLE`, `HARNESS_ERROR`, `NOT_RUN`으로 구분한다. 작은 negative fixture의 의도된 infeasible은 테스트 PASS일 수 있지만, **최종 workload 검증에서 stuck/timeout/infeasible을 정상 planning PASS로 계산하지 않는다.**

`T_initial`과 CandidateE2E는 포함 관계이며 합산하지 않는다. parse/rewrite·공통 analysis·selector·validation/application/lowering·command wall을 구분한다. 기존 실패 시간/OOM/서로 다른 privacy나 입력으로 가속률을 만들지 않는다. 성공한 동일 방법 baseline 없는 경우 memory +5%·속도 개선률은 미판정으로 남긴다.

## 11. 최종 완료 기준과 보고

1. **보존 완료:** C0가 `origin/main`에 push되었고 remote SHA 확인.
2. **구현 완료:** FedFirst/AggLocal에서 state-only 선결정 뒤 전역 candidate DFS가 사라짐. 실제 witness 원자적 commit, no-backtracking 계약, 정확한 failure 분류, local policy certificate를 테스트로 입증. 공통 공간/DP 목적함수 불변.
3. **정확성 완료:** protected 작은 독립 oracle, DAG diamond, 함수/loop/TR-TW/alias, shared relocation 및 authority 반례를 통과. 성공 receipt의 full validator 통과, runtime fallback 없음. 필요한 코드 수정 없이 assertion만 지워 기존 실패를 숨기지 않음.
4. **검증 완료:** 동일 최종 JAR의 **56/56 정상 planning receipt**, 실제 runtime 미실행. `NOT_RUN`·harness 미지원·policy stuck이 남으면 전체 완료 아님. 일반 그래프의 이론적 한계는 숨기지 않되 대상 workload 실패를 형식상 허용해 성공률을 올리지 않는다.
5. **성능 보고:** GLM 네 planner의 전체 초기 planning 및 selector 시간을 비교하고 <60초 달성 여부를 행별 판정. 나머지 52조건도 timing/RSS를 보고하되 단일 관측의 통계적 우열을 주장하지 않음.
6. **산출물:** 수정 설계, C0→C1 파일별 변경 요약, source/JAR/harness/input manifest, 56행 CSV/JSON, 원시 logs/receipts, 시험 결과, 구현·채택·제외·미완료·잔여 위험을 담은 실행 보고서와 세션 이슈 기록.

**이번 턴의 완료 조건은 이 수정 계획의 작성·경로 검증까지다. 위 commit/push·구현·실험 단계는 아직 실행하지 않았다.**
