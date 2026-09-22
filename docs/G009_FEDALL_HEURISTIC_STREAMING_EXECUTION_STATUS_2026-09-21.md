# G009 FedFirst·AggLocal 경량 first-feasible 실행 보고 — 2026-09-21

**판정: 부분 완료(52/56).** [실행 계획](G009_FEDALL_HEURISTIC_STREAMING_SINGLE_PASS_DESIGN_2026-09-21.md)의 로컬 정책 우선 첫 합법 해 선택은 구현했고, 보호된 worker=4 GLM의 네 플래너 전체 초기 planning은 모두 60초 미만이다. 그러나 최종 동일 JAR의 planning-only 56조건 중 StepLM 네 조건이 selector 이전에 실패해 **56/56 완료 기준은 미달**이다. 실패를 성공·timeout·실행 불가 증명으로 바꾸어 세지 않았다.

## 구현·채택 경계

- `CandidateSelections`와 `PolicyCandidateSelectionView`: FedFirst·AggLocal 전용 경로는 로컬 정책 순서의 row를 시도하고 첫 **완전한 candidate/relocation witness**에서 멈춘다. 충돌 시 차선 row·state로 복구하며 공통 후보 관계와 DP exact 경로는 유지한다. prefix support/relocation 수요를 조기 검사하고 materialization·relocation·파생 FOUT의 신규 action을 로컬 순서 힌트에 반영했다.
- `RelocationSelections`: 정책 전용 첫 합법 action 조합을 반환한다. exact 최소비용 API는 변경하지 않았다.
- `PolicyFirstFeasiblePlacementSelector`와 두 adapter: state-only 성공 뒤 row/action 완성이 실패하면 state 탐색으로 복구한다. 선택한 실제 점수와 느슨한 구조적 상한을 구분하며 `POLICY_FEASIBLE`을 전역 최적성 증명으로 표시하지 않는다.
- `NeutralPlacementGraphBuilder`: 기존 ALS/ALSCG latent WDivMM runtime-output 정규화를 post-CFG 물리 재구축 전 경로에 적용했다. 추가로 **privacy가 source row를 제외한 뒤 살아남은 row에서 CFG/direct support를 다시 증명하고 privacy를 재적용**한다. privacy-filtered 관계가 안정될 때까지 반복하며 동일 상태 재방문·비수렴은 예외로 실패한다. 오래된 source identity를 다른 물리 map으로 대체하거나 제외된 권한을 되살리지 않는다. 이 변경으로 이전 최종 JAR에서 실패한 LogReg·L2SVM 각 네 조건이 정상 계획됐다.
- `FederatedPrivacyConstraintResolver`: 존재하지만 손상되거나 읽을 수 없는 로컬 `.mtd`는 다른 파일시스템·worker 응답으로 넘어가지 않고 fail-closed한다. 유효한 mounted relative sidecar는 worker 요청 없이 사용한다.
- **제외한 실험:** 가변 column의 all-row slice를 곧바로 exact ROW 연속성으로 간주하면 작은 단위 테스트는 통과하지만 실제 StepLM에서 CFG 후보 폐쇄가 2상태로 진동해 비수렴한다. privacy 재증명 유무와 무관하게 재현되어 최종 소스에는 채택하지 않았다. 진단용 계측·완화 코드도 모두 제거했다.

## 최종 JAR·실험 경계

- 소스 기준 HEAD `fe000959c48ffa1172399e49124d082fe42d0c6d` + 위 **미커밋 변경**. 최종 `target/SystemDS.jar` SHA-256: **`805095277f03d4cda0a85278be64c09072c118696f908f00e1f03f62afbf3f28`**. 임시 진단 JAR 사용 후 소스·JAR를 복원하고 이 해시를 다시 확인했다.
- 공식 `experiments/run_LAN_docker.sh --planning-validation --planning-only --skip-net-check --workers 4`, 보호된 P2P2D ML10·P1_FULL·P2_PREP·prepared SliceLine ADULT/COVTYPE. Coordinator 16 GiB/8 active processors, 각 worker 8 GiB/8 active processors, timeout 180초. P2 metadata release opt-in은 P2에만 적용했다.
- 최종 실행 스크립트 `/grid/3/cofee-lm-sweep-mchoi-20260914/g009policy-run-fixedpoint-805.sh`, 상태 `/grid/3/cofee-lm-sweep-mchoi-20260914/g009policy-final805b/status.tsv`, 개별 로그와 official JSON receipt는 각 run ID로 연결된다. **56개 고유 조건 중 4개 성공 receipt**(GLM DP-local/FedFirst, LogReg FedFirst, L2SVM FedFirst)는 동일 JAR의 직전 공식 canary에서 재사용했고 나머지 52개를 각 1회 실행했다. 첫 실행 스크립트는 receipt `run_id` suffix 검사가 잘못되어 GLM DP-local 한 행 뒤 중단했으며, 해당 실제 성공 receipt를 검증해 재사용하고 수정된 스크립트로 나머지를 진행했다. 서로 다른 JAR 표본은 섞지 않았다.
- launcher SHA-256 `4849b415df5e45f27c6613c8d78239f9d3a3bbb3b3708a22f6e497636b55f3c9`; `planning_validation.py` SHA-256 `8ea2586ecf95ab542a8b0916147c32b2beaf932b5855307565842b58d30cd681`; reference manifest SHA-256 `e8bb6a11c7ff5cea4cdb57e51e67c14b5abe8eb80fc4ca60f3aae792ad5a040d`.

## 동일 최종 JAR의 56조건 결과

| 대상 | 성공/실행 | 판정 |
|---|---:|---|
| ML10의 `glm,pca,als,alsCG,kmeans,gnmf,gmm,logreg,l2svm` | 36/36 | 네 플래너 모두 성공 |
| ML10의 `steplm` | 0/4 | 네 플래너 모두 공통 builder에서 실패 |
| P1 `P1_FULL`, P2 `P2_PREP` | 8/8 | 네 플래너 모두 성공 |
| SliceLine prepared ADULT 32,561×13, COVTYPE 581,012×54 | 8/8 | 네 플래너 모두 성공 |
| **합계** | **52/56** | **4 미완료** |

52개 성공 조건의 서로 다른 official receipt 전부에서 `success=true`, `runtime_executed=false`, `execution_seconds=0`, `workers=4`, config에 맞는 planner enum을 확인했다. 실패 네 조건은 exit 1이며 정상 planning receipt가 없다. 이전 최종 JAR `4910292…`의 44/56과 비교하면 **LogReg·L2SVM의 8조건이 추가로 성공**했지만, 두 JAR의 시간을 엄격한 AB/BA 성능 비교로 해석하지 않는다.

### GLM 전체 초기 planning

| Planner | 전체 초기 planning (초) | 포함된 CandidateE2E (초) | 그 안의 공통 Analysis (초) | 그 안의 OtherPlanning (초) |
|---|---:|---:|---:|---:|
| DP-local (`mkl-cost`) | **45.144** | 43.154 | 36.361 | 0.002 |
| FedFirst (`mkl-single-pass`) | **45.116** | 43.238 | 37.878 | 4.042 |
| AggLocal (`mkl-heuristic-first`) | **46.479** | 44.633 | 37.146 | 6.194 |
| DP-global (`mkl-exact`) | **44.123** | 42.308 | 35.166 | 0.002 |

모두 정상 전체 초기 planning **60초 미만**이고 runtime은 실행되지 않았다. CandidateE2E는 전체 초기 planning에 포함되므로 합산하지 않는다. 기존 `Selection` timer는 0이고 policy work는 `OtherPlanning`에 포함되므로 정확한 selector 단독 시간을 주장하지 않는다. 이전 `4910292…`의 FedFirst 단회 39.033초보다 이번 단회 45.116초가 **6.083초 느리다**. 추가 privacy 증명의 정확성 이득과 이 비용을 구분하며, 단회 차이를 통계적 회귀율로 표현하지 않는다.

## StepLM 미완료 원인과 제외한 우회

- 네 planner 모두 `scripts/builtin/steplm.dml:132`의 보호된 `TRead X_global`에서 `No privacy-safe physical placement`로 **selector 진입 전** 중단했다. CFG에서 도달 가능한 `TWrite X_global`은 122·160·163행이다. 최종 소스에 계측만 추가한 별도 진단 JAR에서 세 write의 FED/FOUT/ROW state는 형식상 남았으나 해당 candidate 실현은 모두 worker-pool witness 없는 staging `NATIVE_LINEAGE`였다. `sourceFederatedRealizations`가 세 source에 대해 빈 목록을 반환해 모든 정의를 함께 만족하는 FED transient replay가 게시되지 않았다. CP/LOUT는 `PRIVATE_AGGREGATE`가 금지한다.
- 이는 현재 builder가 **증명하지 못한 것**이지, 해당 알고리즘·입력에 privacy-safe 실제 플랜이 절대 없다는 증명은 아니다. `X_orig[,column_best]`의 동적 column slice 연속성 확장 시험은 실제 폐쇄의 진동을 유발해 제외했다. 근거 없는 FED/FOUT 재삽입, privacy 완화, 다른 planner/runtime fallback은 하지 않았다.
- 런타임 근거의 범위: `IndexingFEDInstruction.rightIndexing`은 입력 federation map을 선택 범위로 `filter`하고, `FederationMap.filter`는 남은 partition이 ROW·COL 양쪽 전체를 덮을 때만 FULL/BROADCAST로 재분류한다. 따라서 **모든 행을 유지하는 다중 ROW partition의 열 선택**은 ROW 연속성의 후보 근거가 된다. 그러나 이는 가변 열 경계의 실제 값 검증, 이후 `cbind`의 양 입력 partition 정합성, 세 reaching TWrite의 공동 증명까지 대신하지 않는다. 해당 단위 근거만 추가한 진단 JAR의 실제 StepLM에서는 CFG 폐쇄가 두 fact/node 상태를 번갈아 재방문했다. `changed=[]`인 재생 단계가 있어도 뒤따르는 physical rebuild 후에는 이전 상태로 돌아가므로, 단순히 반복 상한을 늘리거나 한쪽 상태를 임의로 채택할 수 없다.
- 원시 진단 launcher 로그: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009policy-steplm-source-probe.log`, `g009policy-steplm-facts-probe.log`, `g009policy-steplm-dynamiccol-probe.log`, `g009policy-steplm-dyn-noreground-probe.log`; 각 launcher의 failure artifact에 coordinator log가 있다. 이 진단 JAR들은 최종 56행 성능 표본이 아니다.
- 별도 기존 `StepLmPrivateAggregatePlanningContractTest`는 현재 소스와 C0 `fe000959c4`에서 각각 같은 유형의 선행 `lmCG.dml:129` 보호 TRead 실패를 냈다(`/grid/3/cofee-lm-sweep-mchoi-20260914/g009policy-c0-steplm-test.log`). 따라서 그 테스트 실패는 **이번 경량 selector 변경의 신규 회귀라는 증거가 아니지만**, 최종 worker=4 StepLM 실패와 정확히 동일 원인이라는 증거도 아니다.
- 안전한 후속 작업은 세 TWrite의 **실제 runtime ROW pool 보존**을 동적 column indexing·cbind·loop 합류까지 분리한 작은 보호 fixture로 증명하고, CFG 재생의 진동 원인을 제거한 뒤에만 공통 후보 공간에 추가하는 것이다. 단순히 선택기의 선호 순서를 바꿔서는 해결되지 않는다.

## 검증과 남은 위험

- 최종 소스 표적 Maven 실행은 **32개 통과, 5개 public-only fixture skip, 오류 0**: 기존 정책/relocation/oracle/privacy/ALS 26개, `PublicationSupportClosureTest` 2개, `GlmProtectedTransientReplayTest` 2개, `TransientPlacementAlternativesTest` 실행 2개. `mvn -q -DskipTests package` exit 0, `git diff --check` exit 0, 최종 JAR SHA 일치. 전체 test suite 또는 독립 plan-space 전수 인증 통과를 주장하지 않는다.
- L1/L2와 첫 해 조기 전파는 구현했으나 L3의 모든 prefix 제약 증분화는 완료되지 않았다. 첫 해 탐색 최악은 여전히 지수적이다. 동일 경계 RSS와 selector 단독 시간은 official receipt에 없다.
- **계획 완료 조건인 56/56은 남아 있다.** 이번 변경은 미커밋이며, 다른 작업의 독립 plan-space 인증 파일은 건드리거나 이 보고의 성공 범위에 넣지 않았다. 이전 C0 `origin/main` push는 재실행하지 않았다.

### 후속 원인 분석의 정밀화

[StepLM 원인 분석 및 정상화 계획](G009_STEPLM_ROOT_CAUSE_AND_REPAIR_PLAN_2026-09-21.md)에 근거를 분리했다. 위의 “2상태 진동”은 기존 trace의 반복 hash 패턴과 비수렴을 요약한 표현이며, 정확한 두 candidate 관계의 동등성이나 최초로 깨지는 transfer를 증명한 것은 아니다. 그 차이는 작은 단계별 delta 재현으로 확인해야 한다. 이 후속 분석에서 production 코드·JAR·실험 결과는 변경하지 않았다.
