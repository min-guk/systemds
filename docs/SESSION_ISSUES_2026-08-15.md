# Session Issues — 2026-08-15

## DP LogReg LOCAL-input consumer rejected after valid same-placement fusion

- **상태**: 해결 — 코드/회귀 테스트 및 Docker cold/warm canary 검증 완료, 전체 캠페인 재개
- **환경/조건**:
  - planner: `DP` (`compile_cost_based`)
  - privacy: `private-aggregate`
  - profile/workers/workload: `wan_light`, `1`, `logreg`
  - 실행 근거: Docker 전용 one-pass 캠페인
  - 실패 셀: `workers=1|planner=DP|workload=logreg|profile=wan_light`
- **적용 원칙/제약**: planner-selected LOCAL 전송은 exact synthetic lowering으로 증명하고 runtime fallback은 허용하지 않는다. 런타임 조합이나 planner 후보군을 닫지 않고, 잘못된 lowering 감사 규칙만 고친다.
- **의사결정 근거**: planner/oracle/runtime 지원을 변경하지 않았다. `PlannerRuntimePlacementAudit`가 LOCAL 입력 경계와 consumer 자체의 독립 명령 경계를 혼동한 감사 모델 오류를 수정했다.

### 재현 절차

운영 재현 로그:

```text
/home/mchoi/g014-full-results-087f346-d712daf-20260814-v2/
  cells/065-82c324e4aa6b/phases/cell-1/cold-docker-e2e/raw_coordinator.log
```

최소 JUnit 재현:

```bash
mvn -q \
  -DskipUTs=false -DskipTests=false \
  -Dtest=CampaignBG014DpLogRegTransientForwardRedTest#logRegDpKeepsExactTransientForwardAuthority \
  test
```

수정 전 RED 증거: `/tmp/g014-logreg-fusion-red-v2.log` (`RC=1`).

### 관측 증상

Docker cold phase는 실행시간 footer를 출력하기 전에 다음 예외로 종료했다.

```text
[PlannerRuntimeAudit] LOWERING_FUSION_MISMATCH planner=DP hop=1069
plannedPhysical=CP/LOUT ... samePhysical=true requiresOwnInstruction=true
```

따라서 lifecycle descriptor의 `failure_category=metric_parser`는 2차 증상이다. 실제 원인은 runtime metric parsing이 아니라 runtime program lowering 단계의 fail-closed 감사 예외다.

해당 Hop은 `scripts/builtin/multiLogReg.dml:161`의 transpose이고, planner 결과는 다음 경계를 선택했다.

```text
FED/FOUT producer -> planner-selected LOCAL prefetch -> CP/LOUT transpose -> CP/LOUT ba(+*)
```

SystemDS의 정상 Lop lowering은 transpose를 같은 `CP/LOUT` placement의 상위 aggregate-binary 명령에 융합했다.

### 원인 분석

`PlannerRuntimePlacementAudit.prepareRegistration`은 한 `LocalMaterializationActionKey`의 source와 모든 consumer를 모두 `localBoundaries`에 넣었다. 그 결과 LOCAL prefetch의 **입력 간선 consumer**도 `requiresOwnInstruction=true`가 되어, 같은 placement를 보존하는 정상 compiler fusion까지 거부했다.

하지만 exact LOCAL 경계는 이미 별도의 planner-owned synthetic action으로 모델링되어 있다.

1. `Dag.resolveSelectedRefedConsumers`가 logical consumer/input authority를 정확한 physical Lop input에 투영한다.
2. fused consumer는 `resolveFusedSelectedRefedConsumerEdges`가 모호하지 않은 단일 physical edge에만 투영한다.
3. `Dag.insertLocalMaterializeLops`가 그 exact edge를 `UnaryCP(PREFETCH)`로 재배선하며, 실패 시 예외로 닫는다.
4. synthetic instruction에는 `...|stage=LOCAL` planner token이 붙고 `verifySyntheticLowering`이 opcode와 `CP/LOUT` placement를 검증한다.

즉, 독립 명령이 필요한 경계는 LOCAL **synthetic prefetch**이며 consumer transpose 자체가 아니다.

### 해결 요약

- LOCAL action의 source만 기존 materialization boundary로 유지했다.
- LOCAL obligation consumer에는 독립 명령을 강제하는 대신 exact `stage=LOCAL` token 목록을 보존했다.
- consumer가 상위 명령에 융합될 때는 다음 조건을 모두 만족해야만 `FUSED_MATCH`로 인정한다.
  1. consumer 자체가 다른 이유로 `requiresOwnInstruction`가 아니다.
  2. 융합된 상위 명령과 planner physical placement가 동일하다.
  3. consumer의 모든 exact LOCAL input token이 실제 synthetic lowering에서 이미 증명되었다.
- token이 하나라도 없으면 `LOWERING_FUSION_INPUT_BOUNDARY_MISSING`으로 fail-closed한다.
- planner authority generation 교체 시 fused-input token 목록까지 동일해야만 이전 lowering proof를 carry-forward한다.
- DP LogReg 회귀 테스트는 runtime audit를 항상 활성화하고, selected LOCAL action·synthetic `stage=LOCAL` match·same-placement `FUSED_MATCH`를 함께 확인한다.

이 변경은 fusion을 무조건 허용하는 완화가 아니다. planner-selected 데이터 이동 명령의 존재를 별도로 엄격하게 증명한 경우에만 consumer의 정상 physical fusion을 허용한다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAudit.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpLogRegTransientForwardRedTest.java`

### 검증

수정 전:

- `/tmp/g014-logreg-fusion-red-v2.log`: `RC=1`, `LOWERING_FUSION_MISMATCH`, `requiresOwnInstruction=true`

수정 후 targeted:

- `/tmp/g014-logreg-fusion-green-v1.log`: `RC=0`

수정 후 관련 묶음:

```bash
mvn -q \
  -Dtest=PlannerRuntimePlacementAuditTest,\
CampaignBG014AbsentLocalMaterializationLoweringRedTest,\
CampaignBG014DpLogRegTransientForwardRedTest \
  test
```

- `/tmp/g014-local-fusion-broader-v1.log`: `RC=0`

수정본 immutable provenance:

- SystemDS commit: `1a93f8062ebfea4279334a70c6fe61d6b5ed9e3a`
- SystemDS tree: `6701bef098eb6356445e5e80dbfeb9b3747e5a71`
- `SystemDS.jar` SHA-256: `5cf42df2ddf6368b241e133a6fd9528cdf280d313059caf842aad6959a90bba2`
- stage id: `3a01bf031c75f8902fcc585db7a0ac1d27f8fa981e77c4e761bfe78ed05a9e4f`
- Docker-only canary output:
  `/home/mchoi/g014-full-results-1a93f80-d712daf-20260815-v1/cells/065-82c324e4aa6b`

실패 셀 65 Docker canary 결과:

- cell: `workers=1|planner=DP|workload=logreg|profile=wan_light`
- response: `success=true`, `teardown_zero_resources=true`
- cold/warm semantic oracle: 모두 `passed=true`, 비교 오차 `0.0`
- cold/warm scan: `error=false`, `fallback=false`, `timeout=false`
- runtime-plan audit: cold/warm 각각 `mismatches=0`,
  `missingPhysicalHops=0`, `missingSynthetic=0`
- 문제의 transpose hop `1069`: `status=FUSED_MATCH`,
  `inputBoundaries=[0f9be0154410b24b]`
- 해당 exact LOCAL 전송: `status=MATCH stage=LOCAL`,
  `token=0f9be0154410b24b`, `opcode=prefetch`, `actual=CP/LOUT`
- warm primary metric: `22.677 sec`
- campaign progress after canary: authenticated combined prefix `65/336`
- continuation은 셀 66
  `workers=1|planner=FedAll|workload=logreg|profile=wan_light`부터 재개했다.

### 잔여 이슈

- 전체 336-cell 캠페인은 아직 완료되지 않았다. 기존 성공 prefix와 canary를 재실행하지 않고 셀 66부터 계속 수집한다.
- 이후 셀에서 새로운 fail-closed 오류가 발생하면 그 실패 셀을 새 predecessor 경계로 삼아 원인 수정 후 이어가야 한다.

### 잠재 회귀 위험

- **위험**: LOCAL prefetch가 누락됐는데 consumer fusion만 인정될 수 있음.
  - **감지/차단**: consumer별 exact token을 모두 `LOWERED_SYNTHETIC_KEYS`에서 확인하며, 누락 시 `LOWERING_FUSION_INPUT_BOUNDARY_MISSING`으로 종료한다.
- **위험**: authority generation 변경 후 이전 fused proof가 다른 LOCAL edge 집합에 재사용될 수 있음.
  - **감지/차단**: carry-forward 시 `fusedInputBoundaryTokens`의 완전 동일성을 추가로 요구한다.
- **위험**: 여러 LOCAL 입력 중 일부만 lowered된 consumer가 허용될 수 있음.
  - **감지/차단**: token을 deduplicate/sort한 뒤 `allMatch`로 전부 검증한다.

## FedAll LogReg worker=1 exact placement search가 첫 캠페인 셀에서 장시간 정체

- **상태**: 해결 — production-boundary 회귀, 관련 exact-search 테스트, 새 immutable Docker stage의 실제 cell 66 canary를 모두 통과했고 cell 67부터 캠페인을 재개함
- **환경/조건**:
  - planner: `FedAll` (`compile_fed_all`)
  - privacy: `private-aggregate`
  - profile/workers/workload: `wan_light`, `1`, `logreg`
  - 실행 근거: Docker 전용 one-pass 캠페인
  - 실패 셀: `workers=1|planner=FedAll|workload=logreg|profile=wan_light` (combined cell 66)
- **적용 원칙/제약**: runtime 가능 후보, candidate rule, 목적함수와 exact certificate를 유지한다. timeout 회피를 위해 후보군을 닫거나 근사/fallback으로 전환하지 않는다.
- **의사결정 근거**: oracle·runtime·planner policy는 변경하지 않았다. 동일한 exact search space의 열거 순서와 admissible physical-emission lower bound만 수정했다.

### 재현 절차

운영 재현 로그:

```text
/home/mchoi/g014-planning-audit-stage-1a93f80-d712daf-20260815-v1/
  g007-stage-3a01bf031c75f8902fcc585db7a0ac1d27f8fa981e77c4e761bfe78ed05a9e4f/
  results/fed1/mkl-fout/
  logreg_dataset-P2P2D_coordinator_mkl-fout_2581620dedb533e89c25eac5231ede58_wan_light_coordinator1.log
```

동일 final-boundary JUnit 재현:

```bash
mvn -q \
  -Dtest=MinStExactProductionTractabilityCertificateTest#logRegSingleWorkerFedAllFindsAnExactIncumbentWithoutPrefixExplosion \
  test
```

회귀 테스트는 실제 캠페인과 같은 `N=50000`, `D=2100`, single-worker FULL anchor 및
`RewriteFederatedPlannerPhysicalNormalization` → visit reset → memory refresh → final-boundary
binding 순서를 사용한다.

### 관측 증상

수정 전 Docker cold compile은 다음 상태에서 진전 없이 CPU를 계속 사용했다.

```text
Exact-Search-Start decisions=170 groups=72
Exact-Search-Progress prefixes=4194304 explored=65678 pruned=3120410 best=-
```

약 `694.379 sec` 후 운영자가 해당 cold SystemDS Java만 `TERM`으로 종료했다. harness는
정상 종료와 zero-resource teardown을 확인했고, 이전 성공 prefix는 `65/336`으로 보존됐다.

```text
/home/mchoi/g014-full-results-1a93f80-d712daf-20260815-v1/
  cells/066-6a95be49c473/response.json
```

- `success=false`, `return_code=1`
- lifecycle 분류는 footer가 없어서 `metric_parser`였으나, 실제 원인은 runtime/metric이 아니라 planner exact-search tractability다.
- `teardown_zero_resources=true`

### 원인 분석

1. 변수 선택이 dual native/derived-FOUT group을 MRV보다 먼저 선택했다. candidate reachability를
   실제로 결정하는 LogReg group들이 뒤로 밀려 첫 합법 total assignment조차 수백만 prefix 동안
   나오지 않았다.
2. 첫 incumbent를 빠르게 얻은 뒤에도 native FED/FOUT local download와 derived/CP FOUT
   materialization을 서로 독립된 하한으로 계산했다. 둘 중 하나가 반드시 발생하는 mixed row에서는
   각 독립 하한이 모두 0이 되어, 실제 physical optimum을 증명하기 위해 불필요한 total assignment를
   대량으로 score했다.
3. 단일 고정 변수 순서는 feasibility 탐색과 physical-cost 최적화가 요구하는 우선순위가 다르다는 점을
   반영하지 못했다.

스레드 샘플은 병목이 runtime이나 I/O가 아니라 다음 exact path임을 확인했다.

```text
ExactPlacementSelector$Search.candidateAwarePhysicalEmissionLowerBound
ExactPlacementSelector$Search.cannotBeatIncumbent
CandidateSelections.selectMaterializationMaximal
```

### 해결 요약

- 첫 incumbent 전에는 `MRV → constraint degree → dual-emission → canonical` 순서로 열거한다.
  production fixture에서 첫 합법 plan은 74번째 prefix에서 발견된다.
- incumbent 이후에는 `dual-emission → MRV → constraint degree → canonical` 순서로 전환해
  native/derived 물리 비용을 먼저 최적화한다.
- native FED/FOUT local download와 derived/CP FOUT materialization을 producer-owned union으로
  묶었다. 모든 primary-competitive state/row가 둘 중 최소 하나를 지불할 때만 하한 1을 더한다.
- 이 union은 producer별로 분리되고 relocation action class와도 분리되어 additive하며,
  회피 가능한 state 또는 candidate row가 하나라도 있으면 0을 반환하므로 admissible하다.
- exact trace의 progress/complete `best`는 거대한 normalized signature 대신
  `fed/fout/physical` 요약을 출력한다.

변경은 traversal order와 lower bound뿐이다. legal alternatives, constraints, candidate rows,
relocation actions, FedAll lexicographic objective, stable tie-break 및 final exact certificate는 유지된다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/ExactPlacementSelector.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactProductionTractabilityCertificateTest.java`

### 검증

수정 전 production-boundary 회귀:

- `/tmp/g014-fedall-logreg-final-boundary-red-v1.log`: 외부 제한 `75 sec`, `RC=124`
- `/tmp/g014-fedall-logreg-final-boundary-green-v1.log`: MRV-only 수정 후에도 `150 sec`, `RC=124`

수정 후 production-boundary 회귀:

- `/tmp/g014-fedall-logreg-two-phase-order-green-v1.log`: direct JUnit `44.42 sec`, `RC=0`
- `/tmp/g014-fedall-exact-regression-green-v2.log`: Maven/Jacoco 경로 `74.00 sec`, `RC=0`
- 결과는 모든 decision 선택, `finalUpperBound == score`, termination
  `TIGHT_BOUND_EQUALITY`, `fallbackUsed=false`를 확인한다.

관련 exact-search/실제 LogReg compile 회귀:

```bash
mvn -q \
  -Dtest=ExactPlacementSelectorBranchAndBoundTest,CampaignBG014FedAllLogRegTransientReadRelocationRedTest \
  test
```

- `/tmp/g014-fedall-exact-related-tests-green-v1.log`: `RC=0`, `16.05 sec`
- 실제 LogReg compile의 `Compile Phase FedPlanner=2.990121 sec`

패키징 및 불변 실행 provenance:

- source snapshot:
  `/home/mchoi/g014-planning-audit-source-snapshot-20260815-v2`
- SystemDS commit: `21d4d0fe9d6784db3fa6aa8b1a92e4d699596902`
- SystemDS tree: `96e114511e26cc8fbe9a010210d9294f211f7b2d`
- `SystemDS.jar` SHA-256:
  `be9760d36883594130c4a626e70206d0f4e5bfafc4bd196e4426911a8e84eb79`
- stage:
  `/home/mchoi/g014-planning-audit-stage-21d4d0f-d712daf-20260815-v2/g007-stage-dff765dff224d5ba0e11a4b972c5be6f3e7c3478dfe9972c853ece58b37da7e2`
- stage id:
  `dff765dff224d5ba0e11a4b972c5be6f3e7c3478dfe9972c853ece58b37da7e2`
- clean snapshot package: `/tmp/g014-snapshot-21d4d0f-package-v2.log`, `RC=0`
- stage validation: `/tmp/g014-stage-21d4d0f-validate-v2.json`

Docker-only cell 66 canary:

- output:
  `/home/mchoi/g014-full-results-21d4d0f-d712daf-20260815-v2/cells/066-6a95be49c473`
- launcher log: `/tmp/g014-campaign-21d4d0f-canary-v2.log`, `RC=0`
- `success=true`, `teardown_zero_resources=true`, coordinator/worker restart `0`
- cold/warm semantic oracle 모두 통과, objective 상대 오차 `0`, probability NRMSE `0`, masked class 동일
- cold/warm runtime scan: error/fallback/resource-invalid/timeout 모두 `false`
- primary warm metric `97.568 sec`, cold diagnostic metric `101.730 sec`
- full lifecycle `374.121807819 sec`
- 실제 큰 exact subproblem:
  `decisions=170`, `groups=72`, `fixed=68`, `constraints=85`, `relocations=198`
- 첫 incumbent는 prefix `73`, exact complete는 `prefixes=386698`, `explored=10492`,
  `pruned=288152`, 최종 score `fed=104/fout=99/physical=16`
- cold/warm `Compile Phase FedPlanner`: 각각 `39.094331 sec`, `32.745045 sec`
- planning receipt: planner `COMPILE_FED_ALL`, emission planner `FED_ALL`, decisions `486`,
  selected FED `104`, selected FOUT `99`
- coordinator cold/warm runtime-plan audit: 각각 planned hops `474`, planned physical `199`,
  lowered physical `199`, missing physical/synthetic `0`, mismatch `0`
- worker audit: summary block `2`, final worker fragment kinds `160`

canary 이후 동일 continuation output을 재개했다. 러너는 인증된 `rows.jsonl` prefix를 읽어 cell 66을
건너뛰고 다음 셀을 생성했다.

- tmux session: `g014-campaign-21d4d0f-v2`
- launcher: `/tmp/run_g014_campaign_21d4d0f_v3.sh`
- campaign log: `/tmp/g014-campaign-21d4d0f-resume-v3.log`
- 현재 시작 셀:
  `/home/mchoi/g014-full-results-21d4d0f-d712daf-20260815-v2/cells/067-5c8cee138b05`
- request: `workers=1|planner=MinST|workload=logreg|profile=wan_light`,
  `order_index=66`, `conf=mkl-min-st-cut`
- cell 67 결과: `success=true`, warm `16.234 sec`, cold `18.917 sec`, oracle/scan/teardown 통과,
  fallback 없음
- cell 67 MinST receipt: selected FED `24`, selected FOUT `17`; coordinator cold/warm 각각
  planned physical `199`, lowered physical `199`, missing physical/synthetic `0`, mismatch `0`
- FedAll cell 66과 MinST cell 67의 runtime FED fingerprint 및 dispatch 수는 서로 다르다.
  FedAll은 selected FED/FOUT `104/99`, dispatch kinds `141`, MinST는 `24/17`, dispatch kinds `28`이며,
  실제 warm runtime도 `97.568 sec` 대 `16.234 sec`로 달랐다. 즉 이 두 셀에서는 planner 계획이
  runtime에 동일하게 뭉개지지 않고 서로 다른 실행으로 전달되었다.
- cell 67 성공 직후 cell 68
  `workers=1|planner=Heuristic|workload=logreg|profile=wan_light`가 같은 tmux 캠페인에서 자동 시작됐다.
- cell 68도 성공했다: warm `126.970 sec`, cold `132.027 sec`, selected FED/FOUT `81/65`,
  coordinator cold/warm planned/lowered physical `199/199`, missing physical/synthetic `0`, mismatch `0`,
  oracle/scan/teardown 통과 및 fallback 없음.
- 현재 cell 69
  `workers=2|planner=FedAll|workload=logreg|profile=wan_light`가 실행 중이다.
- 장기 실행 상태는 tmux `g014-campaign-watch-21d4d0f-v2`가 60초 간격으로
  `/tmp/g014-campaign-watch-21d4d0f-v1.log`에 combined progress, newest cell 및 Docker 자원을 기록한다.
  fail-closed 실패 또는 캠페인 완료/종료 receipt가 생기면 watchdog도 종료한다.

### 잔여 이슈

- exact-search 정체 자체는 해결 및 실제 Docker canary 검증 완료다.
- 전체 336-cell 캠페인은 아직 완료되지 않았다. 인증된 combined prefix는 현재 최소 `68/336`이며,
  cell 69부터 계속 실행 중이다. 다음 신규 실패가 생기면 성공 prefix를 재실행하지 않고 그 셀만 분석한다.

### 잠재 회귀 위험

- **위험**: mixed native/derived union 하한이 실제 회피 가능한 row를 놓쳐 과대평가할 수 있음.
  - **감지/차단**: 모든 possible state와 해당 state의 모든 reachable row를 검사하고, 하나라도
    materialization을 회피하면 producer contribution을 0으로 둔다. 작은 exact branch-and-bound
    fixture와 production final-boundary certificate를 함께 실행한다.
- **위험**: incumbent 이후 순서 전환이 결과의 canonical tie-break를 바꿀 수 있음.
  - **감지/차단**: 모든 complete assignment의 기존 objective/canonical comparison을 그대로 수행하고,
    certificate가 `TIGHT_BOUND_EQUALITY`인 경우만 반환한다.
- **위험**: trace가 거대한 normalized signature를 출력해 compile 측정과 로그 크기를 왜곡할 수 있음.
  - **감지/차단**: progress/complete trace는 세 정수 score만 출력하며 세부 plan은 기존 별도 audit log로 검증한다.

## FedAll ALS weighted-quaternary 출력 배치가 lowering에서 `NONE`으로 소실

- **상태**: 해결 — 코드/단위 회귀 및 Docker에서 원래 hop 912의 lowering/execution 일치 확인
- **적용 원칙/제약**: planner가 선택한 물리 계획을 runtime에 그대로 전달하며 fallback/암묵적 보정을 하지 않는다.
- **의사결정 근거**: oracle 후보나 runtime 지원 범위를 닫지 않고, planner→LOP→FED instruction 사이의 권한 직렬화 결함을 수정한다.

### 환경/조건

- planner: `FedAll` (`COMPILE_FED_ALL`, `mkl-fout`)
- profile: `wan_light`
- workload/workers: `als`, `2`
- Docker-only campaign output:
  `/home/mchoi/g014-full-results-21d4d0f-d712daf-20260815-v2`
- 실패 셀: `081-70ffb4c81541`

### 재현 절차

캠페인은 인증된 80-cell predecessor/prefix 다음 셀에서 fail-closed로 중단됐다.

```text
workers=2|planner=FedAll|workload=als|profile=wan_light
```

직접 lowering 회귀는 다음으로 재현한다.

```bash
mvn -q -Dskip.license.check=true \
  -Dtest=FederatedPlannerFallbackIntegrationTest#testDirectFederatedQuaternaryLopsParse test
```

### 관측 증상

ALS coordinator log의 실제 예외:

```text
[PlannerRuntimeAudit] LOWERING_MISMATCH planner=FED_ALL hop=912 key=c5aa4b2f3bf548b3
opcode=wdivmm plannedTarget=FED/FOUT/ROW/SHAPE_DEPENDENT|derivedFedFout=false
plannedPhysical=FED/FOUT/ROW actual=FED/NONE
```

- planner는 `wdivmm` hop 912를 합법적인 native `FED/FOUT/ROW`로 선택했다.
- `QuaternaryOp.constructAndSetLopsDataFlowProperties()` 이후 LOP에도 `FOUT`이 존재한다.
- 그러나 `WeightedDivMM.getInstructions()`가 FederatedOutput을 직렬화하지 않고,
  `QuaternaryFEDInstruction.parseInstruction(String)` 및 하위 생성자도 이를 받지 않아 instruction은 `NONE`이 됐다.
- 실패 셀의 `metric_parser` 분류는 정상 footer가 생성되지 못한 2차 증상이며, 실제 원인은 runtime 전 lowering audit이다.

### 원인 분석

직접 FED weighted-quaternary 5종(`wsloss`, `wsigmoid`, `wdivmm`, `wcemm`, `wumm`)은
exec type/operand/type/thread만 instruction string에 넣었다. Binary/Ternary 계열과 달리 마지막
FederatedOutput 필드가 없고 parser/생성자도 해당 권한을 전달하지 않았다. 따라서 planner와 LOP가
올바른 계획을 가지고 있어도 모든 직접 quaternary FED instruction이 `NONE`으로 생성됐다.

hop 912의 RIGHT WDivMM + ROW X는 runtime이 실제로 federated output을 유지하는 지원 조합이다.
그러므로 이 후보를 닫거나 CP로 강등하는 것은 금지 원칙과 runtime capability 모두에 어긋난다.

### 해결 요약

- weighted-quaternary LOP 5종은 **FED instruction에만** 마지막 FederatedOutput 필드를 추가한다.
- `QuaternaryFEDInstruction` parser는 기존 필드 수와 새 `+1` 필드 수를 모두 받아 legacy 문자열과
  새 명시적 planner 문자열을 호환한다.
- base/subclass 생성자에 FederatedOutput 전달 경로를 추가하고, 기존 CP/SP 동적 변환 생성자는
  `NONE` 기본값을 유지한다.
- worker로 전송할 때는 기존 `FederationUtils.callInstruction`이 FED prefix를 감지해 마지막 출력
  플래그를 제거하므로 worker-side CP instruction 문법은 바뀌지 않는다.
- runtime 계산/fallback/candidate-space/cost 모델은 변경하지 않았다.

### 수정 파일

- `src/main/java/org/apache/sysds/lops/WeightedDivMM.java`
- `src/main/java/org/apache/sysds/lops/WeightedSquaredLoss.java`
- `src/main/java/org/apache/sysds/lops/WeightedSigmoid.java`
- `src/main/java/org/apache/sysds/lops/WeightedCrossEntropy.java`
- `src/main/java/org/apache/sysds/lops/WeightedUnaryMM.java`
- `src/main/java/org/apache/sysds/runtime/instructions/fed/QuaternaryFEDInstruction.java`
- `src/main/java/org/apache/sysds/runtime/instructions/fed/QuaternaryWCeMMFEDInstruction.java`
- `src/main/java/org/apache/sysds/runtime/instructions/fed/QuaternaryWDivMMFEDInstruction.java`
- `src/main/java/org/apache/sysds/runtime/instructions/fed/QuaternaryWSLossFEDInstruction.java`
- `src/main/java/org/apache/sysds/runtime/instructions/fed/QuaternaryWSigmoidFEDInstruction.java`
- `src/main/java/org/apache/sysds/runtime/instructions/fed/QuaternaryWUMMFEDInstruction.java`
- `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`

### 검증

- RED: `/tmp/g014-quaternary-fedout-red-v1.log`, `TEST_RC=1`, `expected LOUT but was NONE`
- GREEN: `/tmp/g014-quaternary-fedout-green-v1.log`, `TEST_RC=0`
- GREEN 테스트는 5종 직접 FED LOP 각각에 대해 HOP→LOP output authority와 parser 이후
  `FEDInstruction.getFederatedOutput()`이 정확히 같은지 검증한다.
- immutable source commit: `5c8c4bb63dad03db96d2beb65b71892b297254cd`
- `SystemDS.jar` SHA-256:
  `027e9f44d7dbf46861dbc21dc908aae1b66198b2e870b501d55f532b700891fb`
- Docker stage id:
  `5714e2d3ade14d934716f59f6353fb8e3d2f72058e194b3e3e506d48f83c8610`
- Docker canary에서 원래 실패 hop 912는 `plannedPhysical=FED/FOUT/ROW`,
  `actual=FED/FOUT/ROW`로 lowering과 execution이 모두 `MATCH`했다.
- canary 전체는 뒤이은 별도 동적 재컴파일 권한 결함 때문에 `runtime_scan`으로 fail-closed했다.
  이 두 번째 결함은 아래 별도 이슈로 추적한다.

### 잔여 이슈

- direct weighted-quaternary 출력 권한 소실 자체는 실제 Docker lowering/execution에서 해결됐다.
- 동일 cell 81에서 발견된 동적 WDIVMM exact-origin 이슈를 수정한 새 불변 stage로 전체 canary를
  성공시킨 뒤, 인증된 80개 predecessor를 유지해 cell 82부터 캠페인을 이어야 한다.

### 잠재 회귀 위험

- **위험**: 새 마지막 필드를 worker CP instruction이 operand로 오인할 수 있음.
  - **감지/차단**: FED direct instruction을 worker에 보낼 때 기존 `instructionStringFEDPrepare`가
    FED output flag를 제거하는 경로를 유지하고 실제 ALS Docker canary로 검증한다.
- **위험**: 기존 필드 수의 legacy direct/quaternary dynamic conversion parsing이 깨질 수 있음.
  - **감지/차단**: parser는 `baseFields`와 `baseFields + 1`을 모두 허용하고 CP/SP conversion 생성자는
    기존 `NONE` 생성자를 계속 사용한다.

## ALS 직접 동적 WDIVMM rewrite가 planner-selected owner의 exact origin을 잃음

- **상태**: 해결 — 코드/회귀 테스트 및 Docker의 직접 replacement 경로에서 `REWRITE_MATCH` 확인
- **적용 원칙/제약**: runtime recompilation도 기존 planner 계획을 정확히 보존하며, source-less
  rewrite hop을 유사 signature로 추측하거나 runtime fallback으로 보정하지 않는다.
- **의사결정 근거**: 후보 공간·비용 모델·runtime 연산 지원을 바꾸지 않았다. 동적 rewrite가 만드는
  exact physical replacement에만 명시적인 planner-origin 계약을 전달하고 audit가 닫힌 opcode 치환을
  검증하도록 했다.

### 환경/조건

- planner: `FedAll` (`COMPILE_FED_ALL`, `mkl-fout`)
- profile/workload/workers: `wan_light`, `als`, `2`
- Docker-only canary output:
  `/home/mchoi/g014-full-results-5c8c4bb-d712daf-20260815-v3`
- 실패 셀: `081-70ffb4c81541`
- coordinator log:
  `/home/mchoi/g014-planning-audit-stage-5c8c4bb-d712daf-20260815-v3/g007-stage-5714e2d3ade14d934716f59f6353fb8e3d2f72058e194b3e3e506d48f83c8610/results/fed2/mkl-fout/als_dataset-P2P2D_coordinator_mkl-fout_cf589dfa1b7ae7b0a2cb8f644f4c5a18_wan_light_coordinator1.log`

### 재현 절차

```bash
mvn -q -Dskip.license.check=true \
  -Dtest=PlannerRuntimePlacementAuditTest#dynamicWeightedDivMmReplacementRetainsExactFederatedOwnerAuthority+dynamicWeightedDivMmReplacementCannotBorrowANonFusionOwner,CampaignBG014DerivedFoutRecompileStateRedTest#dynamicWeightedDivMmFusionCarriesExactPlannerOrigin+prePlannerWeightedDivMmFusionDoesNotManufacturePlannerAuthority \
  test
```

수정 전 RED 증거:

- `/tmp/g014-wdivmm-recompile-origin-red-v1.log`: 동적 replacement가 planner origin을 보존하지 않음
- `/tmp/g014-wdivmm-preplanner-authority-red-v1.log`: 무조건적인 origin 상속이 pre-planner rewrite에
  잘못된 planner authority를 제조함

### 관측 증상

첫 결함을 고친 Docker canary에서 원래 hop 912는 정상 실행됐지만, ALS 함수 lines 125–127의
동적 재컴파일 중 다음 fail-closed 오류가 발생했다.

```text
[PlannerRuntimeAudit] LOWERING_UNPLANNED ...
instruction=1060/origin=1060/595/-/wdivmm/FED/FOUT
```

재컴파일 전 planner-selected owner hop 235는 `ba+*`, `FED/FOUT/ROW`였다. 동적 algebraic
rewrite가 이를 source-less `QuaternaryOp WDIVMM` hop 1060으로 바꾸고 placement는 복사했지만,
ultimate planner origin과 rewrite 종류를 전달하지 않아 audit authority를 찾지 못했다.

### 원인 분석

`RewriteAlgebraicSimplificationDynamic.simplifyWeightedDivMM`은 새 WDIVMM에 exec type,
forced exec type, FederatedOutput과 derived bit만 복사했다. runtime audit는 source-less hop의
0:0 signature를 의도적으로 사용하지 않으므로, 새 hop은 원래 owner hop 235와 연결될 수 없었다.

source-less signature matching을 다시 켜면 서로 다른 WDIVMM occurrence가 같은 권한을 빌릴 수 있다.
반대로 모든 rewrite에서 `setPlannerRewriteReplacement`를 호출하면 planner가 실행되기 전 일반
rewrite에도 선택 권한을 제조한다. 따라서 owner가 실제로 `plannerPlacementSelected`인 동적
재컴파일 때만 exact replacement identity를 전달해야 한다.

### 해결 요약

- planner-selected owner를 rewrite할 때만
  `setPlannerRewriteReplacement(owner, "DYNAMIC_WEIGHTED_DIV_MM")`를 호출한다.
- pre-planner rewrite에서는 기존 exec/FederatedOutput 힌트만 복사하고 planner origin,
  replacement kind, selected bit는 만들지 않는다.
- runtime audit의 닫힌 rewrite 계약에 다음만 허용한다.
  - planned owner opcode: aggregate-binary 계열 또는 WDIVMM pattern 7의 `*`
  - actual replacement opcode: 정확히 `wdivmm`
- unrelated `+` owner, 등록되지 않은 치환, source-less signature 추측은 계속 fail-closed한다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/rewrite/RewriteAlgebraicSimplificationDynamic.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAudit.java`
- `src/test/java/org/apache/sysds/hops/recompile/CampaignBG014DerivedFoutRecompileStateRedTest.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAuditTest.java`

### 검증

- targeted GREEN: `/tmp/g014-wdivmm-recompile-origin-green-v2.log`, `RC=0`
- related GREEN: `/tmp/g014-wdivmm-recompile-related-green-v2.log`, `RC=0`
  - `PlannerRuntimePlacementAuditTest`: 40 tests
  - `CampaignBG014DerivedFoutRecompileStateRedTest`: 4 tests
  - `RewriteWeightedDivMMPlannerPlacementTest`: 1 test
  - direct federated quaternary parser: 1 test
- immutable source commit: `68e6c5045383dc5bd5383b231542e5ed3c73e05d`
- `SystemDS.jar` SHA-256:
  `b8d211ae8f90a8ec1702bd82b6e128b0bea0ce5b7956ff79d1182d9bbf2802a9`
- Docker stage id:
  `64a9af10af7433a8b471abd5b09a36009a79b52d5547155c99e8471c43436b1f`
- 새 Docker canary에서 lines 125–127의 planner hop 235 (`ba+*`, `FED/FOUT`)는
  source-less WDIVMM hop 1219로 바뀐 뒤에도
  `status=REWRITE_MATCH`, `kind=DYNAMIC_WEIGHTED_DIV_MM`, `actual=FED/FOUT`로 검증됐다.

### 잔여 이슈

- 직접 replacement 경로의 exact-origin 결함 자체는 Docker에서 해결됐다.
- 같은 canary가 lines 130–132의 transpose-pair WDIVMM에서 별도 authority 손실을 발견했다.
  아래 이슈에서 별도로 추적한다.

### 잠재 회귀 위험

- **위험**: pre-planner rewrite가 잘못 planner-selected로 표시될 수 있음.
  - **감지/차단**: negative regression이 origin 불변, replacement kind null, selected=false를 검증한다.
- **위험**: unrelated owner가 WDIVMM rewrite authority를 빌릴 수 있음.
  - **감지/차단**: 닫힌 opcode 치환 테스트가 unrelated `+` owner를
    `LOWERING_REWRITE_OPCODE_MISMATCH`로 거부한다.
- **위험**: 향후 새 WDIVMM rewrite pattern의 owner opcode가 계약에 빠질 수 있음.
  - **감지/차단**: runtime audit가 `LOWERING_REWRITE_OPCODE_MISMATCH`로 fail-closed하며, 지원을 넓힐
    때는 해당 pattern의 exact owner/replacement 회귀를 먼저 추가한다.

## ALS transpose-pair WDIVMM rewrite가 outer planner authority를 잃음

- **상태**: 해결 — 코드/50개 관련 회귀와 새 immutable stage의 cold/warm Docker canary 모두 통과
- **적용 원칙/제약**: runtime recompile은 planner가 선택한 outer result boundary를 그대로 보존한다.
  source-less signature 추측, runtime fallback, 후보 닫기는 사용하지 않는다.
- **의사결정 근거**: 동적 WDIVMM와 정적 `t(t(X)) -> X`가 합성된 정확한 rewrite에만 outer transpose
  owner를 전달하고, audit는 `r' -> wdivmm` 치환만 닫힌 계약으로 허용한다.

### 환경/조건

- planner: `FedAll` (`COMPILE_FED_ALL`, `mkl-fout`)
- profile/workload/workers: `wan_light`, `als`, `2`
- Docker-only canary output:
  `/home/mchoi/g014-full-results-68e6c50-d712daf-20260815-v5`
- 수정 후 Docker-only canary output:
  `/home/mchoi/g014-full-results-4abacc2-d712daf-20260815-v6`
- 실패 셀: `081-70ffb4c81541`
- coordinator log:
  `/home/mchoi/g014-planning-audit-stage-68e6c50-d712daf-20260815-v5/g007-stage-64a9af10af7433a8b471abd5b09a36009a79b52d5547155c99e8471c43436b1f/results/fed2/mkl-fout/als_dataset-P2P2D_coordinator_mkl-fout_8495784646e5ca314d7a3ebdca86d286_wan_light_coordinator1.log`
- 수정 후 immutable stage:
  `/home/mchoi/g014-planning-audit-stage-4abacc2-d712daf-20260815-v6/g007-stage-3f73cfe4da976bcf673f0df287c920c48b36c4ffaa60273565d23ab963e67db0`
- 수정 후 cold/warm coordinator logs:
  - `.../results/fed2/mkl-fout/als_dataset-P2P2D_coordinator_mkl-fout_dc5813ab963d44faf62870e6ecd4d790_wan_light_coordinator1.log`
  - `.../results/fed2/mkl-fout/als_dataset-P2P2D_coordinator_mkl-fout_dc5813ab963d44faf62870e6ecd4d790_wan_light_coordinator2.log`

### 재현 절차

Docker cell 81 canary:

```bash
python3 <stage>/harness/sigmod2021-exdra-p523/experiments/tools/run_one_pass_performance.py \
  --stage <stage> \
  --output /home/mchoi/g014-full-results-4abacc2-d712daf-20260815-v6 \
  --campaign-seed 2026072701 \
  --predecessor-output /home/mchoi/g014-full-results-68e6c50-d712daf-20260815-v5 \
  --max-new-cells 1
```

회귀 테스트:

```bash
mvn -q -DskipTests=false \
  -Dtest=CampaignBG014DerivedFoutRecompileStateRedTest#dynamicWeightedDivMmTransposePairCarriesOuterPlannerAuthority+prePlannerWeightedDivMmTransposePairDoesNotManufactureAuthority,PlannerRuntimePlacementAuditTest#dynamicWeightedDivMmTransposePairRetainsOuterFederatedLocalAuthority+dynamicWeightedDivMmTransposePairCannotBorrowAMatrixMultiplyOwner \
  test
```

### 관측 증상

ALS `alsCG.dml` lines 130–132의 다음 식에서 발생했다.

```text
HS = t(t(U) %*% (W * (U %*% t(S)))) + reg * S * col_nonzeros
```

Docker의 실제 fail-closed 오류:

```text
[PlannerRuntimeAudit] LOWERING_UNPLANNED ...
instruction=1287/origin=1287/758/-/wdivmm/CP/LOUT
```

- 원래 planner hop 258은 inner `ba+*`, hop 259는 outer `r'`였다.
- 둘 다 logical derived-FOUT이고 물리 lowering은 `FED/LOUT`였다.
- 동적 WDIVMM rewrite는 inner MM을 `t(wdivmm)`로 바꾸고, 이어지는 정적 rewrite가 source의 outer
  transpose와 새 inner transpose를 `t(t(X)) -> X`로 제거했다.
- 살아남은 WDIVMM hop 1287은 어느 owner의 origin/placement도 받지 않아 기본 `CP/LOUT`로 내려갔다.

### 원인 분석

첫 수정은 direct WDIVMM replacement만 권한을 전달하고 transpose-wrapped pattern 1/3/5를 의도적으로
제외했다. 그러나 ALS else branch는 정확히 그 wrapper를 source-level outer transpose와 상쇄한다.
최종 WDIVMM의 shape/value boundary는 inner MM hop 258이 아니라 outer transpose hop 259와 동일하다.
따라서 inner owner를 무조건 복사하거나 0:0 signature를 재사용하면 잘못된 occurrence/shape의 권한을
빌리게 된다. 두 rewrite 단계의 명시적 marker를 연결해 outer owner를 최종 replacement로 전달해야 한다.

### 해결 요약

- 동적 WDIVMM가 direct quaternary뿐 아니라 `transpose(WDIVMM)`을 만들 때도 wrapper에 inner MM의
  `DYNAMIC_WEIGHTED_DIV_MM` marker를 기록한다.
- 정적 double-transpose 제거는 다음 조건이 모두 참일 때만 최종 WDIVMM에 outer owner를 전달한다.
  - outer transpose가 실제 planner-selected 상태
  - inner transpose가 `DYNAMIC_WEIGHTED_DIV_MM` exact replacement marker 보유
  - surviving child가 정확히 `QuaternaryOp WDIVMM`
- 최종 replacement kind는 `DYNAMIC_WEIGHTED_DIV_MM_TRANSPOSE_PAIR`이며, exec/FederatedOutput/
  derived-FOUT/origin/signature는 outer transpose에서 복사한다. derived-FOUT는 물리 instruction에서
  기존 규칙대로 `FED/LOUT`가 된다.
- runtime audit는 이 kind에 대해 planned `r'`, actual `wdivmm`만 허용한다. `ba+*` 등 다른 owner는
  `LOWERING_REWRITE_OPCODE_MISMATCH`로 거부한다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/rewrite/RewriteAlgebraicSimplificationDynamic.java`
- `src/main/java/org/apache/sysds/hops/rewrite/RewriteAlgebraicSimplificationStatic.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAudit.java`
- `src/test/java/org/apache/sysds/hops/recompile/CampaignBG014DerivedFoutRecompileStateRedTest.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAuditTest.java`

### 검증

- Docker RED: output `g014-full-results-68e6c50-d712daf-20260815-v5`, `runtime_scan`,
  `combined_completed=80`, `teardown_zero_resources=true`
- unit RED: `/tmp/g014-wdivmm-transpose-pair-red-v1.log`, `RC=1`
  - missing composed transfer helper
  - unregistered transpose-pair audit kind
- targeted GREEN: `/tmp/g014-wdivmm-transpose-pair-green-v1.log`, `RC=0`, 4 tests
- related GREEN: `/tmp/g014-wdivmm-transpose-pair-related-green-v1.log`, `RC=0`
  - `PlannerRuntimePlacementAuditTest`: 42 tests
  - `CampaignBG014DerivedFoutRecompileStateRedTest`: 6 tests
  - `RewriteWeightedDivMMPlannerPlacementTest`: 1 test
  - direct federated quaternary parser: 1 test
- `git diff --check`: 통과
- immutable source: commit `4abacc220fb5042f617622d33483dd1cc3a9fb90`,
  tree `32a9f0d315b48240f471c46f0b982415f48243dc`
- immutable JAR: SHA-256
  `f96812e645e8d6550307f124cf8bf463577f971bbdd7e00ebc9b7a3efda9f63e`
- stage descriptor 검증: stage id
  `3f73cfe4da976bcf673f0df287c920c48b36c4ffaa60273565d23ab963e67db0`,
  descriptor SHA-256 `1cc9514724642e8f3ff4f0388d87458840197709a7968ada3942f894539c96a2`
- Docker GREEN: `/tmp/g014-campaign-4abacc2-canary-v6.rc` = `0`, output progress
  `combined_completed=81`, `completed=1`, `failed=false`
- cold/warm 모두 다음을 확인했다.
  - outer hop 259: `r' -> wdivmm`, kind `DYNAMIC_WEIGHTED_DIV_MM_TRANSPOSE_PAIR`,
    `plannedPhysical=FED/LOUT/BROADCAST`, `actual=FED/LOUT`, `REWRITE_MATCH`
  - direct hop 912: `plannedPhysical=FED/FOUT/ROW`, `actual=FED/FOUT`, `MATCH`
  - dynamic hop 235: `ba+* -> wdivmm`, `actual=FED/FOUT`, `REWRITE_MATCH`
  - audit summary: `plannedPhysicalHops=68`, `loweredPhysicalHops=68`,
    `missingPhysicalHops=0`, `missingSynthetic=0`, `mismatches=0`
  - `success=true`, cold/warm bundle 존재, coordinator/worker restart `0`,
    `teardown_zero_resources=true`; 종료 후 실행 중 Docker container `0`

### 잔여 이슈

- transpose-pair 권한 손실 자체는 cold/warm Docker에서 해결됐다.
- 전체 캠페인은 `81/336`이며, 성공한 prefix와 cell 81을 재실행하지 않고 동일 continuation output에서
  cell 82부터 진행한다. 새로운 fail-closed 오류가 나오면 해당 실패 output을 다음 predecessor로 보존한다.

### 잠재 회귀 위험

- **위험**: 일반 source `t(t(WDIVMM))`가 planner authority를 잘못 빌릴 수 있음.
  - **감지/차단**: inner transpose의 exact dynamic marker와 outer selected bit를 동시에 요구하며,
    pre-planner negative test가 origin/kind/selected 불변을 검증한다.
- **위험**: inner MM의 placement를 최종 outer-shaped WDIVMM에 잘못 적용할 수 있음.
  - **감지/차단**: 최종 replacement는 outer transpose만 owner로 사용하고 audit kind도 `r'`만 허용한다.
- **위험**: derived-FOUT가 direct FOUT로 바뀌어 runtime output constraint를 위반할 수 있음.
  - **감지/차단**: derived bit를 outer owner에서 함께 복사하고 Docker audit에서 expected/actual
    `FED/LOUT`를 확인한다.

## DP ALS dynamic WDIVMM의 명시적 `LOUT`이 runtime에서 `FOUT`으로 반환됨

### 상태

해결

### 환경/조건

- planner: DP (`mkl-cost`)
- profile: WAN-light
- workload: ALS
- workers: 2
- campaign cell: `workers=2|planner=DP|workload=als|profile=wan_light`
- output: `/home/mchoi/g014-full-results-4abacc2-d712daf-20260815-v6`
- cell: `083-97ff68ea5d54`

### 재현 절차

성공 prefix 82개를 predecessor/continuation으로 보존한 one-pass Docker campaign에서 cell 83을 실행한다.
실패 coordinator log:

```text
/home/mchoi/g014-planning-audit-stage-4abacc2-d712daf-20260815-v6/
g007-stage-3f73cfe4da976bcf673f0df287c920c48b36c4ffaa60273565d23ab963e67db0/
results/fed2/mkl-cost/
als_dataset-P2P2D_coordinator_mkl-cost_77ca26c82448e50390190144657f8885_wan_light_coordinator1.log
```

### 관측 증상

ALS `alsCG.dml` line 125의 원래 hop 235 (`ba+*`)는 DP가 `FED/LOUT/ROW`로 선택했고,
dynamic rewrite/lowering도 해당 권한을 보존해 다음 instruction을 만들었다.

```text
FED?wdivmm?...?MULT_RIGHT?8?LOUT
[PlannerRuntimeAudit][Lowering] status=REWRITE_MATCH ... plannedPhysical=FED/LOUT/ROW actual=FED/LOUT
```

그러나 instruction 실행 직후 runtime 값은 `FED/FOUT/ROW`였고 fail-closed audit가 중단했다.

```text
[PlannerRuntimeAudit] RUNTIME_VALUE_MISMATCH ...
plannedPhysical=FED/LOUT/ROW actual=FED/FOUT/ROW outputVariable=_mVar232
```

cell 결과는 `failure_category=runtime_scan`, `teardown_zero_resources=true`이며 실행 중 Docker
container는 남지 않았다.

### 원인 분석

`QuaternaryWDivMMFEDInstruction.processInstruction`은 parser가 보존한 `_fedOut=LOUT`을 전혀
참조하지 않는다. LEFT+ROW 또는 RIGHT+COL처럼 partial result가 겹치는 경우에만 GET+local sum을
수행하고, 그 외 BASIC/LEFT/RIGHT는 serialized output flag와 무관하게 항상
`setFederatedOutput(...)`을 호출한다. 이번 `MULT_RIGHT`+ROW는 partition-preserving 결과이므로
runtime이 무조건 FOUT을 등록했다.

이는 planner 후보를 닫거나 rewrite를 끌 문제가 아니다. instruction은 이미 정확한 `LOUT`을
직렬화했으므로 runtime이 해당 계약대로 worker 결과를 GET하고 output partition 방향에 맞게 bind해야 한다.

### 해결 요약

- 기존 필수 local aggregation(LEFT+ROW, RIGHT+COL)은 그대로 합산한다.
- 그 외 변형에서 명시적 `LOUT`이면 worker result를 GET/cleanup하고 non-overlapping output을 bind한다.
- 명시적 `FOUT`/`NONE`은 기존 자연스러운 federated output을 유지한다.
- bind 방향은 input 축을 그대로 사용하지 않고 WDIVMM의 결과 축으로 계산한다.
  - BASIC+COL만 cbind한다.
  - LEFT+COL은 transpose된 output ROW 축이므로 rbind한다.
  - RIGHT+ROW는 보존된 output ROW 축이므로 rbind한다.
- 이 동작은 fallback이 아니라 serialized planner output contract의 직접 실행이다.

### 수정 파일

- `src/main/java/org/apache/sysds/runtime/instructions/fed/QuaternaryWDivMMFEDInstruction.java`
- `src/test/java/org/apache/sysds/runtime/instructions/fed/QuaternaryWDivMMFEDInstructionOutputContractTest.java`

### 검증

- Docker RED: cell 83, `combined_completed=82`, `failed=true`, `failure_category=runtime_scan`
- source/log 대조: parser/lowering은 `LOUT`; runtime 구현만 `_fedOut`을 무시함을 확인
- unit RED: `/tmp/g014-wdivmm-output-contract-red-v1.log`, `RC=1`
  - RIGHT+ROW `LOUT`과 LEFT+COL `LOUT` 두 테스트가 실제 FOUT mapping을 관측해 실패했다.
- targeted GREEN: `/tmp/g014-wdivmm-output-contract-green-v2.log`, `RC=0`, 5 tests
  - RIGHT+ROW rbind, LEFT+COL rbind, BASIC+COL cbind, RIGHT+COL aggregate, RIGHT+ROW FOUT 보존
- related GREEN: `/tmp/g014-wdivmm-output-contract-related-green-v1.log`, `RC=0`
  - `RulesetsWeightedQuaternaryTest`
  - `FederatedPlannerFallbackIntegrationTest`
  - `CampaignBG014DerivedFoutRecompileStateRedTest`
  - `PlannerRuntimePlacementAuditTest`
  - WDIVMM output contract test
- package GREEN: `/tmp/g014-wdivmm-output-contract-package-v1.log`, `RC=0`
- immutable source: commit `7a286b0beadede4ae0c6fb99ec95e001874f6750`,
  tree `7da1089eb64ec1571c62d7dbf1c59743fa3785e8`
- immutable JAR SHA-256:
  `898e6153bbade1e32139564436c7a34e7027fc81b87d3f2de7584348757aa153`
- stage: id `6c6b88a515fe93b8ac9f712a389c9adf1de08cdf681f62097fac36e582648366`,
  descriptor SHA-256 `221d4c1fcc006de6195e332c4c16946086d18f8f2bbad7d54d41c38ad9ecfc7d`
- Docker GREEN: `/tmp/g014-campaign-7a286b0-canary-v7.rc` = `0`,
  output `/home/mchoi/g014-full-results-7a286b0-d712daf-20260815-v7`
  - predecessor의 성공 prefix 82개를 보존하고 cell 83만 실행했다.
  - cold/warm 모두 hop 235가 `ba+* -> wdivmm`, `plannedPhysical=FED/LOUT/ROW`,
    instruction suffix `MULT_RIGHT?8?LOUT`, `REWRITE_MATCH`였다.
  - cold/warm audit summary 모두 `plannedPhysicalHops=68`, `loweredPhysicalHops=68`,
    `missingPhysicalHops=0`, `missingSynthetic=0`, `mismatches=0`이었다.
  - `success=true`, `combined_completed=83`, coordinator/worker restart `0`,
    `teardown_zero_resources=true`, 종료 후 실행 중 Docker container `0`을 확인했다.

### 잔여 이슈

- cell 83의 WDIVMM runtime placement 불일치는 해결됐다.
- 전체 캠페인은 `83/336`이며 동일 output에서 cell 84부터 재개한다.
- 성공한 83개 cell은 재실행하지 않는다.

### 잠재 회귀 위험

- **위험**: LEFT 결과의 bind 축을 원래 X의 COL 축으로 사용해 잘못 cbind할 수 있음.
  - **감지/차단**: input partition이 아니라 WDIVMM 결과 partition을 기준으로 bind 축을 결정하는
    회귀 테스트를 둔다(LEFT+COL 결과는 ROW bind).
- **위험**: 겹치는 partial result를 단순 bind해 수치 결과를 변경할 수 있음.
  - **감지/차단**: LEFT+ROW와 RIGHT+COL은 output flag와 무관하게 기존 aggregate 경로를 우선한다.

### 의사결정 근거

런타임은 플래너가 직렬화한 `FED/LOUT`을 그대로 실행해야 하며 fallback·암묵 보정·candidate-space
축소를 허용하지 않는 원칙을 적용한다.

## immutable stage 생성 시 stage-copy reference root를 입력해 lifecycle 검증 실패

### 상태

해결

### 환경/조건

- stage tool: `experiments/tools/stage_campaign.py`
- 새 stage parent: `/home/mchoi/g014-planning-audit-stage-7a286b0-d712daf-20260815-v7`
- 최초 입력 reference: 직전 stage의 `references/` copy

### 재현 절차

직전 immutable stage 아래의 `references/`를 새 `--reference-root`로 전달한다.

### 관측 증상

stage 생성은 immutable tree를 쓰기 전에 다음 오류로 fail-closed 종료했다.

```text
ERROR: published CP manifest diverged from descriptor
```

### 원인 분석

CP lifecycle descriptor는 최초 발행된 content-addressed reference bundle의 `manifest.json` 절대 경로를
인증한다. 직전 stage의 reference tree는 내용 hash는 같지만 경로가 달라 descriptor의
`reference_manifest.path`와 일치하지 않는다.

### 해결 요약

직전 stage copy가 아니라 lifecycle descriptor에 기록된 canonical bundle root를 새 stage의
`--reference-root`로 사용했다. 실패한 stage parent는 아직 비어 있음을 확인한 뒤 제거하고 동일 명칭으로
다시 생성했다.

### 수정 파일

- 소스 수정 없음

### 검증

- canonical reference root의 실제 manifest 경로가 descriptor의 `reference_manifest.path`와 동일함을 확인
- 재실행한 stage id: `6c6b88a515fe93b8ac9f712a389c9adf1de08cdf681f62097fac36e582648366`
- reference tree SHA-256: `e547b60edab5772958475e52f6236922558ac414466e76eab4e442ab1c44e18a`
- stage descriptor checksum 검증 통과

### 잔여 이슈

- 없음. 향후 stage 생성도 canonical published reference bundle을 직접 입력해야 한다.

### 잠재 회귀 위험

- **위험**: content hash만 같다는 이유로 stage-local copy를 다시 입력해 불필요한 생성 실패가 반복될 수 있음.
  - **감지/차단**: lifecycle descriptor의 `reference_manifest.path`에서 canonical root를 읽고 사전 비교한다.

### 의사결정 근거

reference provenance의 절대 경로 인증을 완화하지 않고 기존 immutable publication contract를 그대로 따른다.

## ALS MinST가 transpose된 WDivMM의 실제 출력 축·크기를 잘못 비용화해 worker scaling 정렬이 깨짐

### 상태

진행중 — 비용/shape 모델과 exact MinST 회귀는 수정·통과했으며, 새 immutable Docker stage의 문제 셀 재검증이 남아 있다.

### 환경/조건

- planner: MinST (`mkl-min-st-cut`), 비교 대상 DP/FedAll/Heuristic
- privacy: `private-aggregate`
- workload: `als`
- 관측 범위: Docker campaign의 worker 수별 실행시간
- 적용 원칙/제약: runtime이 지원하는 WDivMM 후보를 닫지 않고 compute/shape/memory/boundary cost를 실제 출력 계약에 맞춘다.

### 재현 절차

```bash
mvn -q -DskipITs \
  -Dtest=CampaignBG014AlsPartitionedComputeCostRedTest test
```

운영 재검증은 현재 소스를 immutable stage로 고정한 뒤 `run_LAN_docker.sh`만 사용한다.

### 관측 증상

ALS의 transpose된 `WDivMM` 쌍에서 worker 수가 늘어도 MinST runtime이 단조 감소하지 않거나 다른 플래너보다
느린 점이 관측됐다. planner trace를 대조하면 transpose consumer가 실제 계산 결과의 owner인데도 원래
WDivMM producer를 물리 owner로 취급하는 경우가 있었고, exact cost facts에는 unknown/phantom output shape가
들어가 partitioned compute와 boundary cost가 왜곡됐다.

### 원인 분석

1. dynamic rewrite가 생성한 WDivMM→transpose 쌍에서 논리 producer와 실제 runtime output owner가 다르지만,
   MinST의 categorical/physical model은 이를 동일 node로 취급했다.
2. transpose된 출력 축을 원래 input partition 축으로 해석해 `ROW/COL` output contract와 유효 output size가
   어긋났다.
3. 원래 producer와 transpose owner 사이에 실제 데이터 이동이 아닌 phantom edge가 남아 동일 계산/경계를
   중복 비용화할 수 있었다.
4. exact shape/memory fact에 unknown sentinel이 남아 일부 legal state의 비용이 0 또는 과소평가될 수 있었다.

### 해결 요약

- WDivMM rewrite pair의 authoritative physical output을 transpose owner에 귀속시켰다.
- output partition 방향과 effective shape/memory를 WDivMM variant의 실제 결과 축에 맞춰 계산한다.
- rewrite pair 내부의 phantom producer edge는 물리 boundary cost에서 제외하되, 실제 외부 입력/출력 경계는 유지한다.
- exact cost fact 생성 시 inferred output characteristics와 effective memory estimate를 사용해 unknown/zero sentinel이
  최적화 입력으로 들어가지 않게 했다.
- 후보 조합을 닫거나 MinST 목적함수를 runtime 결과에 맞춰 사후 보정하지 않았다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/ReorgOp.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactPhysicalModel.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactPlacementProjector.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014AlsPartitionedComputeCostRedTest.java`

### 검증

- `/tmp/g014-targeted-tests-20260815.log`: `CampaignBG014AlsPartitionedComputeCostRedTest` 4개 통과
- 회귀는 transpose owner가 `FED/LOUT/ROW`, weights 입력이 `FED/FOUT/ROW`인 exact MinST 선택과
  nonzero partitioned compute/output memory fact를 검증한다.
- `/tmp/g014-current-compile-20260815.log`: Maven compile `RC=0`
- 아직 새 소스 Docker runtime ordering은 검증하지 않았다.

### 잔여 이슈

- ALS에서 정렬이 깨졌던 정확한 profile/worker 셀과 인접 worker 점을 새 stage에서 재실행해야 한다.
- 단일 실행시간의 작은 역전은 warm-run 변동과 분리해야 하며, 우선 fedplan/runtime audit 일치와 비용 선택을 확인한다.

### 잠재 회귀 위험

- **위험**: rewrite pair를 하나의 physical owner로 합치는 과정에서 실제 외부 boundary까지 제거할 수 있음.
  - **감지/차단**: exact facts 테스트에서 내부 phantom edge 부재와 외부 weights/output boundary 존재를 함께 검증한다.
- **위험**: DP까지 전역 MinST와 같은 계획을 강제할 수 있음.
  - **감지/차단**: DP의 parent-child 지역 최적 철학은 유지하고, 이 회귀는 shared shape/cost facts와 MinST exact 선택만 검증한다.

### 의사결정 근거

candidate-space guard가 아니라 잘못된 compute/shape/memory/boundary 측정을 수정해야 한다는 고정 원칙을 적용했다.

## KMeans worker=1 DP가 coherent local loop preference를 exact join fallback에서 잃어 정렬이 깨짐

### 상태

진행중 — planner 원인 수정과 compile-only/회귀 테스트는 통과했으며 새 Docker runtime 재검증이 남아 있다.

### 환경/조건

- planner: DP (`mkl-cost`)
- privacy/profile/workers/workload: `private-aggregate`, `wan_mid`, `1`, `kmeans`
- 이전 Docker 증거:
  - DP cell 117: `171.170 sec`
  - FedAll cell 118: `148.694 sec`
  - MinST cell 119: `82.452 sec`
  - Heuristic cell 120: `204.810 sec`
- output: `/home/mchoi/g014-full-results-7a286b0-d712daf-20260815-v7`
- 적용 원칙/제약: DP는 parent-child 지역 비용 철학을 유지한다. CFG TWrite/TRead coherence는 전역 합법성 제약이며,
  후보를 닫지 않고 양쪽 complete legal family를 기존 conflict resolver가 비교하게 한다.

### 재현 절차

```bash
mvn -q -DskipITs \
  -Dtest=CampaignBG014DpKMeansSingleWorkerMixedRefedRedTest test
```

trace 재현:

```text
/tmp/g014-dp-kmeans-trace-20260815.log
/tmp/g014-dp-kmeans-trace-fixed-20260815.log
```

### 관측 증상

수정 전 DP는 `X_samples=sample_maps %*% X`를 반복 loop에서 혼합 FED 경로로 사용해 동일 계열의
`fed_*` 실행을 반복했다. MinST는 X_samples를 한 번 materialize한 뒤 loop를 CP/LOUT으로 실행해 훨씬 빨랐다.
DP의 multi-write normalizer 자체는 X_samples와 centroid `C` family를 LOUT으로 선택했지만 최종 선택은 다시
FOUT/LOUT 혼합으로 뒤집혔다.

trace에서 최초 exact failure는 KMeans centroid `C`의 TWrite(line 166)가 `FED/FOUT`, 다음 iteration의
TRead(line 173)가 `CP/LOUT`인 상태였다. 둘은 CFG의
`SAME_PLACEMENT[evidence=cfg-transient-value:ORDINARY]` 제약을 만족할 수 없었다.

### 원인 분석

1. multi-write normalization 이후의 local decision-map refinement는 selected plans의 child 관계만 점수화했다.
2. CFG reaching-definition으로 이미 증명된 TWrite→TRead output mismatch는 그 점수에 포함되지 않았다.
3. 따라서 refinement가 `C` TWrite를 FOUT으로 다시 뒤집었고, locally preferred domain에는 coherent forest가
   하나도 남지 않았다.
4. exact component join은 합법성을 지키기 위해 전체 legality domain을 다시 열었지만, 이 경로는 DP 비용 선호를
   잃은 첫 feasible lexicographic forest를 선택했다. 같은 큰 component에 있던 X_samples family도 반복 hybrid
   plan으로 함께 뒤집혔다.

### 해결 요약

- decision-map 점수에 analysis-owned CFG reaching definitions의 선택된 TWrite/TRead **output mismatch**를
  structural conflict로 포함했다.
- output mismatch는 이후 exec/FType 선택으로 복구할 수 없으므로 exact join보다 앞서 판정 가능하다.
- full `(exec, output, FType)` 합법성은 기존 exact join이 계속 소유한다.
- LOUT/FOUT 어느 후보도 닫지 않는다. 기존 DP local resolver가 두 complete legal family를 비교하고 coherence를
  유지하게 한다.
- preferred-forest 실패 및 transient mismatch trace는 hot loop마다 반복하지 않고 1회 요약으로 제한했다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpKMeansSingleWorkerMixedRefedRedTest.java`

### 검증

- 수정 전 trace: `DP-ComponentPreferredForestInfeasible` 125회(동일 component/member 중복)
- 수정 후 trace: 해당 fallback 0회
- 수정 후 선택:
  - line 70 X_samples producer: `FED/FOUT`
  - line 70 TWrite X_samples: `CP/LOUT`
  - line 109 TRead X_samples: `CP/LOUT`
  - 반복 loop의 line 100/105/109/110: `CP/LOUT` work 존재
  - 모든 selected CFG TWrite/TRead output pair coherent
- `/tmp/g014-targeted-tests-20260815.log`: KMeans 회귀, ALS 4회귀, aggregate-producer 11회귀 통과
- `/tmp/g014-current-compile-20260815.log`: compile `RC=0`
- 아직 새 소스 Docker runtime ordering은 검증하지 않았다.

### 잔여 이슈

- 새 immutable stage에서 WAN-mid worker=1 KMeans를 DP부터 실행하고 runtime-plan audit mismatch 0 및
  반복 loop의 실제 instruction placement를 확인해야 한다.
- 비교 정렬 판단을 위해 같은 stage/seed/data의 FedAll, Heuristic, MinST 셀도 한 번씩 필요하다.

### 잠재 회귀 위험

- **위험**: reaching-definition 관계가 아닌 이름 기반 TRead/TWrite까지 강제로 묶을 수 있음.
  - **감지/차단**: `cfgDefinitionSourcesInCanonicalOrder`가 반환한 analysis-owned source만 비교한다.
- **위험**: output만 같은 불법 exec/FType 조합을 조기에 합법으로 오판할 수 있음.
  - **감지/차단**: helper는 mismatch penalty만 추가하며 최종 legality는 exact component join과 certificate가 검증한다.
- **위험**: conflict scoring hot loop에서 trace I/O가 compile time에 섞일 수 있음.
  - **감지/차단**: conflict trace는 count와 첫 항목만 1회 요약한다.

### 의사결정 근거

TRead/TWrite는 `<CP,LOUT>` 또는 `<FED,FOUT>`의 coherent family여야 한다는 최상위 규칙과, DP의 지역 최적
철학을 동시에 보존했다. runtime fallback이나 arbitrary opcode guard는 추가하지 않았다.

## Disconnected-component completion 회귀 테스트의 ABSENT_LOCAL emission 오류

### 상태

진행중 — 이번 KMeans 변경과 독립인 기존 실패임을 격리 확인했으며 아직 수정하지 않았다.

### 환경/조건

- test: `CampaignBG014DisconnectedComponentCompletionRedTest`
- 증상: 4개 중 2개 error

### 관측 증상

```text
Candidate emission is absent from final graph node ... TRead I ...
inputs=[ABSENT_LOCAL:-] state=CP/LOUT
```

### 원인 분석

새 transient-output conflict 호출을 임시로 비활성화한 동일 test에서도 같은 stack/error가 재현됐다.
따라서 KMeans coherence 수정이 만든 회귀는 아니며, disconnected completion의 기존 candidate-emission
소유권 문제다.

### 해결 요약

- 아직 없음. KMeans/ALS 문제 셀의 Docker 검증과 분리해 후속 분석한다.

### 수정 파일

- 없음

### 검증

- transient-output scoring 활성/비활성 양쪽에서 동일 실패
- KMeans, ALS, aggregate-producer targeted tests는 통과

### 잔여 이슈

- ABSENT_LOCAL candidate emission owner와 final graph node projection을 별도 수정해야 한다.

### 잠재 회귀 위험

- **위험**: 이번 문제와 섞어 transient coherence 수정을 되돌리면 KMeans 버그만 복원되고 원래 오류는 남는다.
  - **감지/차단**: 격리 실행 결과를 기준으로 두 이슈를 별도 회귀로 유지한다.

### 의사결정 근거

새 변경과 기존 실패의 인과를 검증한 뒤에만 회귀로 판정한다는 원칙을 적용했다.

## Heuristic가 named-function/loop CFG에서 demoted vector를 잃고 반복 REFED

- **상태**: 해결 — source-level 회귀와 immutable Docker 8-cell canary GREEN; 새 stage 전체 실험 진행중
- **환경/조건**:
  - planner: `Heuristic` (`compile_fed_heuristic`), 비교 대상 `FedAll`
  - privacy: `private-aggregate`
  - 관측 workload/profile: `l2svm`, `logreg`, `wan_light`, workers `1/2`
  - 실행 근거: 동일 Docker one-pass 결과와 cold/warm coordinator 로그
- **적용 원칙/제약**: Heuristic는 vector-producing FED/LOUT demotion 이후의 exact local path를 유지하고, 실제 FED 재진입이 필요한 최초 경계에서만 planner-owned LOUT→FOUT relocation을 허용한다. runtime fallback/repair, recompile CP→FOUT, 임의 candidate 차단은 허용하지 않는다.
- **의사결정 근거**: runtime이나 oracle capability를 바꾸지 않았다. 공통 placement graph가 이미-local인 동일 논리값의 exact CFG 흐름을 누락한 planner-analysis 버그를 수정했다.

### 재현 절차

기존 Docker 결과:

```text
/home/mchoi/g014-full-results-087f346-d712daf-20260814-v2/
  cells/063-9692f9487fc3   # L2SVM w2 FedAll
  cells/061-e640a2d540c0   # L2SVM w2 Heuristic
/home/mchoi/g014-full-results-21d4d0f-d712daf-20260815-v2/
  cells/066-6a95be49c473   # LogReg w1 FedAll
  cells/068-fd0a9507273d   # LogReg w1 Heuristic
```

최소 회귀:

```bash
mvn -q -Dtest='\
org.apache.sysds.hops.fedplanner.fedHeuristic.CampaignBG014HeuristicL2SvmLoopLocalityRedTest,\
org.apache.sysds.hops.fedplanner.fedHeuristic.CampaignBG014HeuristicLogRegLoopLocalityRedTest,\
org.apache.sysds.hops.fedplanner.fedHeuristic.CampaignBG014HeuristicLogRegFoutAnchorProjectionRedTest,\
org.apache.sysds.hops.fedplanner.fedHeuristic.CampaignBG014HeuristicKMeansRuntimeRecompileRefedAuthorityRedTest,\
org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicPathwiseReentryTest' test
```

### 관측 증상

L2SVM WAN-light worker=2:

- FedAll runtime `127.017 sec`, `fed_fed_fout=122` (`~2.632 sec`)
- Heuristic runtime `150.138 sec`
- Heuristic가 static REFED action 세 개를 선택했고 실제 loop에서 `600 + 600 + 30 = 1230`회의 `fed_fed_refed`로 실행되어 `~26.44 sec`를 소비했다.
- 세 relocation source는 모두 builtin `l2svm.dml`의 line 99에서 이미 FED/LOUT으로 demote된 `Xd`가 line 110/118 TRead로 전달된 동일 논리값이었다.

LogReg WAN-light worker=1:

- FedAll runtime `97.568 sec`
- Heuristic runtime `126.970 sec`
- Heuristic에서 `prefetch=305 / 61.181 sec`, `fed_fed_refed=257 / 4.854 sec`가 발생했다.
- selected REFED에는 `Grad` line 179/235와 `HV` line 209/226 등 named-function nested-loop의 반복 벡터 경계가 포함됐다.
- 수정 전 source-level worker=1 FULL fixture도 `Grad`, `R`, `V` 등에 대한 11개의 loop-local relocation과 line 246의 별도 relocation을 선택했다.

따라서 runtime은 planner plan을 정확히 실행했지만, **전달된 plan 자체가 Heuristic 철학에 어긋났다**. planner→runtime fidelity가 clean하다는 사실은 plan policy의 정확성을 보장하지 않는다.

### 원인 분석

1. `NeutralPlacementGraphBuilder.exactCfgHeuristicPathEdges`가 `supportedPathOccurrence`를 사용했다.
2. 이 함수는 `main`, `compiled`, non-loop 경로만 허용하므로 named function, loop body, whole-body recompile의 정확한 TWrite→TRead edge를 모두 버렸다.
3. L2SVM `Xd`는 reaching definition이 하나이지만 그 edge가 누락되어 line 110/118 TRead가 새로운 ordinary value처럼 보였다.
4. LogReg `Grad`는 더 복잡하다. line 179/235 TRead가 초기 `Grad`와 outer-loop 갱신 `Grad` 두 정의를 갖는 PHI 형태다. 두 정의 모두 demotion-local이지만 기존 코드는 `reachingDefinitions.size()!=1`이면 무조건 edge를 버렸다.
5. Heuristic selector는 끊어진 TRead를 FedAll-style `MAX_FED → MAX_FOUT → MIN_RELOCATIONS` 대상으로 처리해 매 loop iteration의 REFED/prefetch를 계획했다.

과거 `docs/SESSION_ISSUES_2026-08-13.md` issue 11의 “서로 다른 ValueVersionKey이므로 정책 leak가 아니다”라는 결론은 이 증거로 폐기한다. exact key가 다른 것은 CFG versioning의 결과이며, all-predecessor local proof가 있으면 semantic loop value는 local로 합류해야 한다.

### 해결 요약

- unique reaching TWrite→TRead 전달은 `main` 여부가 아니라 node-aware exact-local 조건으로 판정한다.
  - 동일 program fingerprint, lexical variable, function namespace, recompile context
  - source=`TRANSIENT_WRITE`, target=`TRANSIENT_READ`
  - source/target 모두 vector shape
  - `CLONE_RECOMPILE` 제외
- 다중 정의 TRead는 fixed-point로 처리한다.
  1. demotion과 compiled-input/unique-CFG edge만으로 최초 local prefix를 계산한다.
  2. **모든** reaching TWrite가 그 local prefix에 이미 포함된 경우에만 PHI TRead의 CFG edge를 추가한다.
  3. 새 edge로 local prefix를 재계산하고 더 이상 변하지 않을 때까지 반복한다.
- 하나의 local predecessor만으로 PHI 전체를 local로 추정하지 않는다.
- synthetic `LOOP_PHI` node를 실제 transient forward source로 사용하지 않고 compiler-owned `TRANSIENT_WRITE` occurrence만 허용한다.
- `exactHeuristicReentry`의 restrictive `supportedPathOccurrence`는 그대로 유지했다. 따라서 이 수정은 loop/recompile upload를 새로 허용하지 않고, 이미 local인 값을 local로 전달하기만 한다.
- path validation 실패 시 edge와 node kind를 포함하도록 진단 메시지를 강화했다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedHeuristic/CampaignBG014HeuristicL2SvmLoopLocalityRedTest.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedHeuristic/CampaignBG014HeuristicLogRegLoopLocalityRedTest.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedHeuristic/CampaignBG014HeuristicLogRegFoutAnchorProjectionRedTest.java`

### 검증

RED:

- `/tmp/g014-heuristic-l2svm-loop-locality-red.log`: L2SVM `Xd` local TRead line set가 비어 있음.
- `/tmp/g014-heuristic-logreg-loop-locality.log`: LogReg local prefix가 `HV` line 209/226만 포함하고 `Grad` line 179/235를 누락.
- `/tmp/g014-heuristic-logreg-loop-locality-r2.log`: worker=1 FULL에서 `Grad/R/V` loop relocation이 남아 assertion 실패.

GREEN:

- `/tmp/g014-heuristic-local-phi-regression-r2.log`: 위 명령의 11 tests, failures `0`, errors `0`, `RC=0`.
- L2SVM: line 99 `Xd` demotion path가 line 110/118 TRead와 두 exact CFG forward edge를 포함하고 selected `Xd` relocation은 `0`.
- LogReg worker=1 FULL: `Grad/HV` local path가 line 179/209/226/235를 모두 포함하고 `Grad/HV/R/V/S/Snew` relocation은 `0`; 진단 당시 남은 selected relocation은 line 246의 별도 aggregate 경계 하나뿐이었다.
- 기존 pathwise suite도 GREEN이므로 unsupported loop/recompile re-entry는 계속 닫혀 있다.

광범위 guard 확인:

- `/tmp/g014-heuristic-guard-regression.log`: 22 tests 중 20개 통과, 기존 이슈 두 개가 남았다.
  - `Heuristic score differs from its canonical physical-transfer projection`은 이번 변경 전인
    `/tmp/g014-placement-architecture-guards-rerun-v291.log`(2026-08-14)에도 동일 stack으로 존재했다.
  - `Candidate emission is absent ... inputs=[ABSENT_LOCAL:-]`은 위의
    `Disconnected-component completion 회귀 테스트의 ABSENT_LOCAL emission 오류`와 같은 기존 소유권 문제다.
- 따라서 두 실패를 이번 CFG-locality 변경의 회귀로 분류하지 않는다. 다만 전체 guard가 GREEN이라고 주장하지
  않으며, Docker canary와 별도로 잔여 결함으로 유지한다.

immutable build/stage 확인:

- source snapshot: `/home/mchoi/g014-planning-audit-source-snapshot-20260815-heuristic-r1`
  - commit `54612b94b713bd1d13e1bb5ae42bd684923773e6`
  - tree `6816fe5110803c053c87e17798e4a0536db13d48`
  - snapshot receipt `/tmp/g014-heuristic-r1-snapshot-receipt.json`
- package: `/tmp/g014-heuristic-snapshot-package.log`, `RC=0`
  - jar SHA-256 `10fac23423204e41ecd4538e24ccc3300145ec2f59110c2d783768a8de08a24e`
- immutable Docker stage:
  `/home/mchoi/g014-planning-audit-stage-54612b9-d712daf-20260815-r10/g007-stage-a66e804c28332c9f42984e39c4b5e9bfdfaee637289b3dcc879bbd8861a8566c`
  - stage id `a66e804c28332c9f42984e39c4b5e9bfdfaee637289b3dcc879bbd8861a8566c`
  - harness commit `d712daf82d3023f8f136bb8c348cc04521b72335`
  - explicit validation `/tmp/g014-stage-54612b9-r10-validate.json`
- Docker 성능 오염을 막기 위해 진행 중이던 이전-source ALS/KMeans diagnostic과 병렬 실행하지 않는다.
  종료 후 `/tmp/run_g014_heuristic_logreg_l2svm_canary_r1.py`가 8개 셀을 정확히 한 번씩 실행한다.
- `/tmp/audit_g014_heuristic_logreg_l2svm_canary_r1.py`는 runtime audit, targeted loop-local relocation,
  warm `fed_refed` 반복 횟수, FedAll 대비 큰 성능 퇴행을 gate한다. gate가 통과해야만 새 stage의 독립적인
  336-cell 결과 `/home/mchoi/g014-full-results-54612b9-d712daf-20260815-v1`을 시작한다.

Docker runtime canary 결과:

- 결과 root:
  `/home/mchoi/g014-heuristic-logreg-l2svm-canary-54612b9-d712daf-20260815-r1`
- audit: 위 root의 `audit.json`, `passed=true`, errors `[]`, 8/8 cells, retry/중복 없음
- L2SVM workers=2:
  - Heuristic `23.688 sec`, FedAll `127.290 sec`, ratio `0.1861`
  - 과거 Heuristic `150.138 sec`에서 크게 감소
  - `Xd` relocation source `0`, warm `fed_refed=0`, runtime audit mismatch/missing `0`
- L2SVM workers=1:
  - Heuristic `61.626 sec`, FedAll `72.342 sec`, ratio `0.8519`
  - `Xd` relocation source `0`, warm `fed_refed=0`, runtime audit mismatch/missing `0`
- LogReg workers=1:
  - Heuristic `75.761 sec`, FedAll `97.729 sec`, ratio `0.7752`
  - 과거 Heuristic `126.970 sec`에서 감소
  - `Grad/HV/R/V/S/Snew` relocation source `0`; 별도 line 246 aggregate 경계 1개만 남음
  - warm `fed_refed=30`, runtime audit mismatch/missing `0`
- LogReg workers=2:
  - Heuristic `39.912 sec`, FedAll `48.873 sec`, ratio `0.8166`
  - `Grad/HV/R/V/S/Snew` relocation source `0`; 별도 line 246 aggregate 경계 1개만 남음
  - warm `fed_refed=30`, runtime audit mismatch/missing `0`
- 네 비교 모두 Heuristic가 FedAll보다 빨랐으며, 성능 차이는 runtime fallback이 아니라 실제 선택 plan의
  loop-local 유지에 의해 발생했다.
- canary gate 통과 직후 같은 immutable stage/seed/data로 전체 336-cell campaign을 시작했다.
  schedule은 WAN-light 112 → WAN-mid 112 → LAN 112이고, 이전 source 결과를 merge/predecessor로 사용하지 않는다.

### 잔여 이슈

- 새 source hash의 targeted Docker 검증은 완료했다. 전체 336-cell campaign은 아직 진행 중이므로 모든 workload,
  worker, profile의 ordering/scaling 검증은 미완료다.
- 기존 immutable stage의 결과는 이 수정 전 binary이므로 최종 성능 근거에 합치지 않고 diagnostic predecessor로만 보존한다.

### 잠재 회귀 위험

- **위험**: PHI의 일부 predecessor만 local인데 전체 TRead를 local로 오판할 수 있음.
  - **감지/차단**: 모든 reaching definition이 prior fixed-point local prefix에 포함되어야만 edge를 공개한다.
- **위험**: synthetic loop PHI를 runtime TWrite처럼 취급할 수 있음.
  - **감지/차단**: source/target graph kind를 각각 `TRANSIENT_WRITE`/`TRANSIENT_READ`로 제한하고 placement-analysis constructor가 재검증한다.
- **위험**: local propagation이 곧 pathwise upload 권한으로 오용될 수 있음.
  - **감지/차단**: re-entry는 기존 `supportedPathOccurrence`와 exact relocation/candidate proof를 계속 요구하며 `CampaignBHeuristicPathwiseReentryTest`로 잠근다.
- **위험**: planning 개선이 실제 runtime에서 다른 prefetch/REFED 경계로 치환될 수 있음.
  - **감지/차단**: Docker canary에서 selected plan log, synthetic registry 실행 횟수, runtime-plan audit, heavy hitters를 함께 비교한다.
