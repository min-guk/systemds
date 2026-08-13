# Session Issues — 2026-08-13

## 1. LM workers>=2에서 planner-selected inner direct FOUT이 MMChain fusion으로 소실됨

- **상태**: 해결; source 회귀, 16셀 planning-only, worker=2 cold/warm Docker runtime canary 검증 완료
- **적용 원칙/제약**: planner가 선택하고 비용화한 direct `FED/FOUT` 경계를 Lop fusion이 지우지 않는다. runtime fallback이나 후보 축소를 추가하지 않고, lowering이 선택된 placement를 그대로 보존한다.
- **환경/조건**: source commit `a4f8825130b8d560562028ec70d6170b0e07422d` 기반 predecessor, Docker WAN-Light, P2P2D LM, workers=2..4, FedAll. 중단 campaign은 `/home/mchoi/g014-full-results-a4f8825-4f0b380-20260812-v1`이며 64/336 terminal row에서 중단했다.
- **재현 절차**: predecessor stage에서 `run_LAN_docker.sh --net-profile wan_light --workers 2 --dataset P2P2D --conf mkl-fout --salg lm`을 실행하고 planning trace와 runtime program을 비교한다. planning receipt는 `results/planning/6501fc8735054d26482051410a06c912_wan_light_coordinator1/mkl-fout.json`, raw planning log는 `results/fed2/mkl-fout/lm_dataset-P2P2D_coordinator_mkl-fout_6501fc8735054d26482051410a06c912_wan_light_coordinator1.log`이다.
- **관측 증상**: FedAll trace는 LM line 129의 외부 MM hop 246을 `FED/LOUT/ROW`, 내부 MM hop 245를 direct `FED/FOUT/ROW`로 선택하고 둘 모두 `emittedWork=true`로 기록했다. 하지만 runtime program에는 `FED mmchain X p ... XtXv` 하나만 남아 내부 FOUT이 독립적인 FederationMap으로 생성되지 않았다. 그 결과 workers=2..4에서 FedAll의 emission plan은 DP/MinST와 달랐지만 runtime-plan SHA와 instruction fingerprint는 모두 같은 `mmchain` 계획으로 붕괴했다.
- **원인 분석**: `AggBinaryOp.checkMapMultChain()`은 외부 aggregate-binary의 direct FOUT만 별도로 막았다. 내부 aggregate-binary는 `hasPlannerMaterializationBoundary()`가 REFED/derived-FOUT/local-materialization registry만 확인해 direct FOUT을 경계로 인식하지 않았고, `constructCPLopsMMChain()`이 내부 HOP을 제거했다.
- **해결 요약**: `hasPlannerMaterializationBoundary()`가 planner-selected direct FOUT(`FOUT && !isFederatedOutputDerived()`)도 executable boundary로 취급하게 했다. 이 공통 경계는 MMChain 및 Lop-level transpose fusion이 내부 FOUT을 지우지 못하게 한다. direct FOUT 경계가 없는 LOUT-only MMChain과 derived FOUT의 명시적 materialization 경로는 그대로 유지한다. 이 LM plan에서는 DP/MinST도 inner direct FOUT을 선택하므로 선택 충실성을 위해 명시적 두 MM으로 lowering된다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/AggBinaryOp.java`, `src/test/java/org/apache/sysds/test/component/federated/FederatedDagExactRefedInputProjectionTest.java`.
- **검증**: 새 `selectedDirectFoutInnerXtXvRemainsUnfused` 회귀는 수정 전 `expected NONE but was XtXv`로 실패했다. 수정 후 inner Lop이 명시적 `FED ba+* ... FOUT`이고 outer가 `MapMultChain`이 아님을 검사해 통과했다. 관련 8개 클래스 총 43 tests가 failures/errors/skips 0으로 통과했다.
- **검증 추가**: clean source commit `5ea324bbc59be05f8e930b0381c7bd8d8b6384fb`, JAR SHA-256 `bd9f0dadf04b459dd1d190b6efa15fe6d249fa2206a3c61050ab2f2ca019d94c`의 immutable stage `g007-stage-bebc4325fa072984a1d0282fadd296211d32004a64f6567a2bac339a5e36459f`에서 WAN-Light LM workers=1..4 x 4 planner planning-only를 실행했다. 16/16 receipt가 `success=true`, `runtime_executed=false`, fallback/repair trace 0으로 통과했다. workers=2..4의 runtime-program fingerprint는 DP/MinST `ba+*:3;fedinit:2;r':2`, FedAll `ba+*:3;fed_fout:1;fedinit:2;r':2`, Heuristic `ba+*:3;fed_refed:1;fedinit:2;r':2`로 정책별로 분리됐다. 특히 FedAll workers=2..4에는 각각 명시적 inner `FED ba+* ... FOUT`과 outer `FED ba+* ... LOUT`이 남고 `FED mmchain`은 0개였다. 전체 감사 산출물은 `/home/mchoi/g014-lm-inner-fout-canary-5ea324b-20260813-v1/planning-audit.json`(SHA-256 `e0948a95908402ee907a5c39505b779215bcf9ba0a7868cbb35227cf70e7a347`)이다.
- **Docker runtime 검증**: 같은 stage의 WAN-Light LM workers=2 FedAll을 cold/warm으로 실행했다. cold/warm runtime은 13.014/9.054초, runtime-plan SHA-256은 양쪽 모두 `f08cb4fa1b56044a5cf78646364e3e11e9dccf0b5677d88766c3f47dc37c1695`, 출력 SHA-256은 양쪽 모두 `ae3ca8405f01303ff5a466c2563d86004488513ae04044ad33df53e4252fcbc6`였다. 실제 heavy hitter에 `fed_ba+*` 91회와 `fed_fed_fout` 1회가 기록됐고 `fed_mmchain`은 없었다. 두 phase 모두 semantic oracle 통과(objective relative error `8.716e-15`, prediction NRMSE `1.604e-15`), runtime scan의 error/fallback/resource-invalid/timeout 0, cold/warm plan/output parity를 통과했다. 감사 산출물은 `/home/mchoi/g014-lm-inner-fout-canary-5ea324b-20260813-v1/runtime-audit.json`(SHA-256 `09089e9b0c3fc3357c3993d4e57ca52bdaf3ee053118666540df357e477e3c2c`)이다.
- **잔여 이슈**: 이 수정이 다른 workload의 선택된 direct FOUT fusion에도 적용되므로 predecessor row를 재사용하지 않는 새 336-cell Docker campaign에서 전체 7 workload x 4 planners x 4 workers x 3 profiles를 검증한다.
- **잠재 회귀 위험**: direct FOUT input을 포함하는 다른 Lop fusion도 이전보다 보수적으로 차단되어 성능이 달라질 수 있다. 이는 후보 폐쇄가 아니라 선택된 실행 경계를 보존하는 변화이며, 관련 FOUT/MMChain/rewire 회귀와 전체 Docker campaign의 planning/runtime fingerprint 차이로 감지한다.
- **의사결정 근거**: Oracle/runtime capability는 합법적인 inner `FED/FOUT/ROW`를 이미 제공한다. 문제는 capability가 아니라 lowering의 plan fidelity이므로 Oracle 후보나 heuristic 규칙을 변경하지 않았다.

## 2. 직접 runtime canary에서 runtime explain 계약을 누락함

- **상태**: 해결; 잘못된 호출은 격리하고 올바른 계약으로 재실행 완료
- **적용 원칙/제약**: 성능 근거는 authenticated planning receipt와 cold/warm runtime-plan parity를 만족한 Docker 실행만 채택한다.
- **환경/조건**: 위 immutable stage, WAN-Light LM, workers=2, FedAll, 직접 `run_LAN_docker.sh` canary 첫 시도 run id `20260813_043212_1535196_wan_light`.
- **재현 절차**: `CAMPAIGN_PLAN_EXPLAIN`을 설정하지 않고 non-lifecycle runtime canary를 호출한다.
- **관측 증상**: workload runtime 자체는 끝났지만 cold phase receipt가 `runtime plan explain marker must occur exactly once`로 fail-closed했다.
- **원인 분석**: direct canary 호출에서 cold receipt가 요구하는 `CAMPAIGN_PLAN_EXPLAIN=1`을 누락했다. source/runtime 결함이 아니라 실험 호출 계약 위반이다.
- **해결 요약**: 첫 시도는 `/results/failures/20260813_043212_1535196_wan_light_coordinator1/mkl-fout`에 격리하고 어떤 성능/정확성 근거에도 사용하지 않았다. `CAMPAIGN_PLAN_EXPLAIN=1`을 명시해 run id `20260813_043703_1546934_wan_light`로 재실행했으며 cold/warm 두 phase 모두 성공했다.
- **수정 파일**: source 변경 없음; canary 호출 환경만 수정.
- **검증**: 재실행의 runtime planning receipt는 `success=true`, `runtime_executed=true`이고 cold/warm canonical runtime-plan SHA-256이 동일하다. 상세 결과는 이슈 1의 runtime 검증과 `runtime-audit.json`에 기록했다.
- **잔여 이슈**: 없음. 전체 campaign은 `run_one_pass_performance.py`가 lifecycle request와 explain 계약을 자동 생성하므로 동일 누락이 발생하지 않는다.
- **잠재 회귀 위험**: 임의 direct canary를 다시 실행할 때 환경 변수를 누락할 수 있다. planning receipt가 fail-closed하므로 잘못된 실행은 최종 row로 인증되지 않는다.
- **의사결정 근거**: 검증 계약을 완화하지 않고 호출을 수정했다.

## 3. LM lowering 수정 후 시작한 전체 Docker campaign을 runtime-plan audit 강화로 격리

- **상태**: 중단/격리; 64/336까지 실행했으나 이후 발견한 audit completeness 결함과 source 의미 변경 때문에 현재 성능 근거로 병합하지 않음
- **적용 원칙/제약**: lowering 변경 전 생성된 predecessor row는 새 source의 runtime-plan 근거로 재사용하지 않는다. 모든 성능 row는 동일 immutable stage, 고정 seed/data, Docker cold/warm lifecycle, semantic oracle 및 runtime-plan parity를 통과해야 한다.
- **환경/조건**: source commit `5ea324bbc59be05f8e930b0381c7bd8d8b6384fb`, harness commit `4f0b3805fec68893e8f45aca1efa29b3873a9cd0`, immutable stage `g007-stage-bebc4325fa072984a1d0282fadd296211d32004a64f6567a2bac339a5e36459f`, campaign seed `2026072701`. 결과 경로는 `/home/mchoi/g014-full-results-5ea324b-4f0b380-20260813-v1`이다.
- **재현 절차**: stage의 `harness/sigmod2021-exdra-p523/experiments/tools/run_one_pass_performance.py`를 `--stage <immutable-stage> --output /home/mchoi/g014-full-results-5ea324b-4f0b380-20260813-v1 --campaign-seed 2026072701`로 실행한다. `--predecessor-output`은 사용하지 않는다.
- **관측 증상**: predecessor campaign은 LM inner FOUT 소실 수정 전 source로 64/336까지 실행됐으므로 새 source와 runtime-plan 의미가 다르다. 이를 섞으면 정책별 fingerprint와 실행시간 비교가 오염된다.
- **원인 분석**: source lowering 변화는 planner emission이 같더라도 실제 runtime instruction sequence를 바꿀 수 있으므로 이전 row의 commit/JAR identity만 다른 수준이 아니라 측정 대상 plan 자체가 다르다.
- **해결 요약**: predecessor를 격리하고 336개 모든 configuration을 WAN-Light -> WAN-Mid -> LAN 순서로 새 stage에서 한 번씩 실행하도록 fresh campaign을 시작했다. 이 campaign은 64개 terminal row까지 성공했지만, 이후 planner-selected physical operation이 실제 lowering됐는지를 완전하게 세지 못하는 audit 결함과 function-boundary 의미 수정이 발견되어 즉시 중단했다. `/home/mchoi/g014-full-results-5ea324b-4f0b380-20260813-v1`은 재현/비교용 predecessor로만 보존하며 새 결과와 병합하지 않는다.
- **수정 파일**: source 변경 없음; 이 문서만 갱신. 실행 산출물은 `/home/mchoi/g014-full-results-5ea324b-4f0b380-20260813-v1` 아래에 기록된다.
- **검증**: `rows.jsonl`과 `progress.json` 모두 `completed=64`, `total=336`, 마지막 셀 `workers=2|planner=DP|workload=l2svm|profile=wan_light`를 기록한다. 64개 row 자체는 당시 semantic oracle/runtime scan/cold-warm parity 계약을 통과했지만, 아래 이슈 5의 강화된 audit-v2 계약을 통과한 것은 아니므로 현재 source의 완전한 planner→runtime 충실성 증거로 간주하지 않는다.
- **잔여 이슈**: 강화된 audit-v2와 새 immutable source/harness identity로 DP canary부터 다시 검증한 뒤 FedAll→Heuristic→MinST, 최종적으로 새 336-cell campaign을 수행해야 한다.
- **잠재 회귀 위험**: 후속 cell에서 planner/runtime capability 누락, semantic mismatch, worker scaling 요동 또는 정책 plan collapse가 드러날 수 있다. 각 cell의 fail-closed receipt와 planning/runtime fingerprint를 통해 최초 실패 지점에서 감지하고, 성공 row를 중복 실행하지 않은 채 원인 수정 후 재개한다.
- **의사결정 근거**: 결과 병합 편의보다 동일 코드/동일 stage의 실험 정합성을 우선했다. 물리 LAN 실행이나 runtime fallback은 사용하지 않는다.

## 4. 함수 actual/formal 경계에서 합법적인 FOUT→CP/LOUT materialization을 strict placement equality가 거부함

- **상태**: 해결; 함수 경계, recompile, emission 회귀검증 완료
- **적용 원칙/제약**: 함수 actual이 `FED/FOUT`이고 formal이 `CP/LOUT`인 경우, 정확한 function-call input materialization을 계획하고 비용화하면 합법이다. runtime fallback이나 `<CP,FOUT>` TRead/TWrite 완화는 허용하지 않는다.
- **환경/조건**: DP, StepLM 함수 호출/recompile 경로, planner-selected FOUT actual과 local formal의 경계.
- **재현 절차**: `CampaignBG014DpStepLmFunctionInputCandidateRedTest`, `FunctionBoundaryRuntimeAliasContractTest`, `SharedPlannerFunctionPlanPropagationRedTest`를 실행하고 function actual/formal alias와 emission receipt를 비교한다.
- **관측 증상**: 공통 placement 정규화 후 function actual/formal에 동일 exec/output placement를 강제하여, caller의 `FED/FOUT` 값을 callee의 `CP/LOUT` formal로 전달하는 합법적 경로까지 불일치로 판정했다. 이 때문에 DP가 기존 StepLM 함수 경계에서 사용할 수 있던 local materialization을 잃거나 잘못된 후보 폐쇄가 발생할 수 있었다.
- **원인 분석**: 함수 경계는 단순한 HOP 동일성 경계가 아니라 값 전달과 materialization이 일어나는 명시적 lowering 경계인데, `NeutralPlacementGraph`/`PlacementAnalysis`와 emission 단계가 이를 strict placement equality로 모델링했다.
- **해결 요약**: FOUT actual→CP/LOUT formal은 정확한 FunctionCallCP input materialization action이 선택·등록·emission되는 경우에만 허용하도록 공통 graph/analysis/emission 계약을 수정했다. 비용 기반 DP/MinST는 이 action의 비용을 선택 전에 반영하며, action이 없으면 fail-closed한다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraph.java`, `PlacementAnalysis.java`, `LocalMaterializationSelections.java`, `PlacementEmissionTransaction.java`, `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`, MinST exact-cost facts 관련 코드와 대응 테스트.
- **검증**: 함수 경계/로컬 materialization/emission/MinST legality와 StepLM 관련 집중 회귀가 통과했다. 이어서 9개 클래스 79개 DP/function/emission 테스트를 실행해 failures/errors 0을 확인했다(`/tmp/g014-runtime-audit-dp-regression-bundle-r1.log`).
- **잔여 이슈**: 실제 Docker StepLM canary에서 dynamic recompile을 포함한 모든 plan generation의 lowering/runtime 증명을 audit-v2로 확인해야 한다.
- **잠재 회귀 위험**: 동일한 값이 여러 function formal로 전달될 때 action occurrence identity가 합쳐지거나 중복될 수 있다. exact occurrence key, plan generation, function boundary 테스트 및 audit의 missingPhysicalHops로 감지한다.
- **의사결정 근거**: runtime은 명시적 local materialization을 지원한다. 따라서 합법 후보를 닫지 않고 경계 action과 비용을 planner 모델에 추가했다.

## 5. runtime audit가 planner-selected ordinary physical operation의 미-emission을 성공으로 인증할 수 있음

- **상태**: 해결; Java/harness 회귀와 WAN-Light DP StepLM Docker audit-v2 canary 완료
- **적용 원칙/제약**: planner authority에 포함된 모든 physical operation은 lowering의 확정 증거와 실제 실행의 exact plan-generation 증거를 가져야 한다. `NOT_EMITTED`/`DEFERRED`, stale plan 실행, unplanned runtime instruction은 모두 fail-closed한다.
- **환경/조건**: runtime audit 활성화(`-Dsysds.fedplanner.runtime.audit=true`), StepLM DP function/recompile 경로 및 coordinator/worker Docker 로그.
- **재현 절차**: 강화 전 `CampaignBG014DpStepLmFunctionInputCandidateRedTest`를 runtime audit와 함께 실행하고 lowering observation/summary를 비교한다. 진단 로그는 `/tmp/g014-steplm-audit-diagnostic-r1.log`이다.
- **관측 증상**: 실제 실행된 instruction은 검증했지만 최종 mismatch 수에는 synthetic action 누락만 포함됐다. ordinary selected physical operation이 `NOT_EMITTED` 또는 `DEFERRED`에 머물러도 audit 성공이 가능했다. 또한 `putIfAbsent` 때문에 130개 `NOT_EMITTED`와 2개 `DEFERRED` provisional observation이 나중의 exact match로 대체되지 않아 최종 보고가 stale 상태를 표시했다. 진단한 132개 occurrence는 실제로는 모두 후속 lowering에서 확정 증명됐다.
- **원인 분석**: lowering observation이 provisional/final 상태를 구분하지 않았고, summary가 selected physical operation 전체 집합과 definitive lowering 집합의 차집합을 계산하지 않았다. dynamic complete replan 사이의 authority generation도 instruction에 귀속되지 않아 이전 plan의 instruction을 새 plan에서 실행하는 것을 구분할 수 없었다.
- **해결 요약**: audit authority에 exact stable occurrence index와 plan generation을 추가했다. final summary가 `plannedPhysicalHops`, `loweredPhysicalHops`, `missingPhysicalHops`, `missingSynthetic`, `mismatches`를 완전하게 계산하고, 모든 selected physical occurrence가 definitive proof를 갖지 않으면 실패한다. provisional observation은 후속 exact proof로 대체한다. complete replan 시 exact-identical occurrence만 `AUTHORITY_CARRY_FORWARD_MATCH`로 전달하며, 변경/삭제된 이전 generation instruction은 `RUNTIME_STALE_PLAN` 또는 `RUNTIME_STALE_SYNTHETIC_PLAN`로 거부한다. coordinator/worker parser도 generation과 closed status set을 강제하고 schema를 `g014-planner-runtime-audit-v2`로 올렸다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAudit.java`, `src/test/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAuditTest.java`, `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpStepLmFunctionInputCandidateRedTest.java`, harness `experiments/tools/runtime_plan_audit.py`, `experiments/tests/test_runtime_plan_audit.py`, `experiments/run_LAN_docker.sh`, `experiments/code/distributedExpNew.sh`, `experiments/tools/write_provenance.py`.
- **검증**: Java audit unit 38/38 통과(`/tmp/g014-runtime-placement-audit-completeness-r2.log`). StepLM complete lowering 1/1 통과 및 `missingPhysicalHops=0`, `mismatches=0` 확인(`/tmp/g014-steplm-audit-completeness-r3.log`). 관련 DP/function/emission 79 tests 통과(`/tmp/g014-runtime-audit-dp-regression-bundle-r1.log`). Harness parser-v2 unit 7/7 통과(`/tmp/g014-harness-runtime-audit-v2-r1.log`). 전체 `mvn -q -DskipTests -Dspotless.check.skip=true package`도 성공했고 새 JAR SHA-256은 `626952f0629e67fb4a3ac398272c0339da509c03036222bbc1a174d97fbf070f`이다.
- **추가 Docker 검증**: immutable stage `g007-stage-fdf1aab1b7d1bcf72694b99c38a9639e3b7e250d7c933303abc39fc4ef3d1d33`에서 WAN-Light DP StepLM workers=2를 cold/warm으로 실행했다. 두 coordinator 모두 `plannedPhysicalHops=144`, `loweredPhysicalHops=144`, `missingPhysicalHops=0`, `missingSynthetic=0`, `mismatches=0`이고 execution/dispatch 증거가 존재했다. 두 worker도 각 phase의 exact plan generation 및 fragment execution 증거를 통과했다. cold 27.092초, warm 26.232초이며 semantic exact/scan/resource/teardown 계약이 모두 성공했다.
- **잔여 이슈**: 같은 강화 audit로 DP LM/KMeans를 우선 검증하고, 이후 FedAll→Heuristic→MinST 순서로 확대한다. 첫 mismatch가 나오면 전체 campaign으로 진행하지 않는다.
- **잠재 회귀 위험**: dynamic recompile에서 의미상 동일하지만 occurrence identity가 바뀌는 operation을 carry-forward하지 못하면 false positive가 발생할 수 있다. 반대로 identity 충돌은 stale instruction을 허용할 수 있다. exact key+planner+recompile signature+opcode+placement+action 비교와 Docker generation 로그로 감지한다.
- **의사결정 근거**: runtime fallback을 추가하지 않고 검증기를 완전하게 만들어 planner-selected authority와 실제 lowering/execution 사이의 숨은 차이를 실패로 노출한다.

## 6. Docker runtime-audit 플래그가 고정 Java resource contract를 오염함

- **상태**: 해결; 하네스 회귀 및 WAN-Light DP StepLM Docker resource 증거 완료
- **적용 원칙/제약**: audit는 진단 기능이며 컨테이너의 고정 메모리/CPU 자원 계약(`-Xmx8g -Xms8g -Xmn800m -XX:ActiveProcessorCount=8`)을 바꾸지 않는다. coordinator와 worker의 실제 SystemDS 프로세스에만 audit system property를 주입한다.
- **환경/조건**: 새 audit-v2 stage, WAN-Light, DP StepLM, workers=2, `--runtime-plan-audit`.
- **재현 절차**: audit-v2 하네스로 `run_LAN_docker.sh --net-profile wan_light --workers 2 --dataset P2P2D --conf mkl-cost --salg steplm --runtime-plan-audit`를 실행한다.
- **관측 증상**: workload/worker JVM을 실행하기 전 `container_resource_evidence.py`가 `ValueError: container Java resource contract mismatch for coordinator`로 fail-closed했다. 컨테이너는 즉시 모두 제거됐고 runtime/성능 결과는 생성되지 않았다.
- **원인 분석**: `run_LAN_docker.sh`가 audit property를 `CAMPAIGN_COORDINATOR_JAVA_OPTS`와 `CAMPAIGN_WORKER_JAVA_OPTS`에 append했다. 이 변수들은 diagnostic option 채널이 아니라 Docker inspect로 인증되는 고정 resource contract 자체다. Coordinator는 이미 `distributedExpNew.sh`가 per-invocation으로 property를 주입하므로 중복이었고, worker만 별도의 process-scoped 주입이 필요했다.
- **해결 요약**: launcher의 container Java opts 변형을 제거했다. Coordinator는 기존처럼 `BENCHMARK_RUNTIME_PLAN_AUDIT`를 받아 실행 직전에 property를 추가한다. Worker 시작 exec에도 같은 bool을 전달하고, `startWorker.sh`가 값 검증 후 worker process 환경의 `SYSTEMDS_STANDALONE_OPTS`에만 `-Dsysds.fedplanner.runtime.audit=true`를 한 번 추가한다. Docker `Config.Env`의 resource opts는 정확히 기존 값으로 유지된다.
- **수정 파일**: harness `experiments/run_LAN_docker.sh`, `experiments/code/startWorker.sh`, `experiments/tests/test_g007_harness.py`.
- **검증**: `bash -n` 통과. 새 process-scope 회귀는 base opts가 그대로 보존되고 worker child에서만 audit property가 붙는 것을 실제 fake snapshot runner로 확인했다. 기존 worker snapshot contract와 runtime audit parser를 포함한 집중 9 tests가 통과했고, 전체 `test_g007_harness` 53/53도 통과했다.
- **추가 Docker 검증**: 이슈 5의 최종 StepLM canary에서 coordinator/worker 모두 `-Xmx8g -Xms8g -Xmn800m -XX:ActiveProcessorCount=8`을 정확히 유지했고 CPU set은 서로 겹치지 않았다. audit property는 실제 process에만 전달됐으며 restart/OOM은 없고 컨테이너와 network도 종료 후 0개로 정리됐다.
- **잔여 이슈**: 없음. 이후 canary/전체 campaign도 동일 resource receipt를 필수로 유지한다.
- **잠재 회귀 위험**: worker start 경로가 추가되거나 `startWorker.sh`를 우회하면 audit property가 누락될 수 있다. worker summary의 audit parser fail-closed와 container resource evidence 두 계약을 동시에 검사해 감지한다.
- **의사결정 근거**: resource validator를 완화하거나 diagnostic property를 resource contract로 인정하지 않았다. 진단 옵션의 주입 경계를 실제 JVM process로 이동했다.

## 7. runtime-audit parser가 정상 subtraction opcode `-`를 누락 sentinel로 오판함

- **상태**: 해결; parser 회귀, 보존 로그 재검증, 수정 stage Docker 재실행 완료
- **적용 원칙/제약**: 실제 subtraction opcode를 허용하되 opcode 필드 자체의 누락은 계속 fail-closed한다. planner/runtime placement 규칙이나 candidate space는 변경하지 않는다.
- **환경/조건**: source snapshot `d8e6c965bebffb0fb2153abcfad9023a336b44ba`, harness snapshot `d4231c0b281e2fd8c4f17700b0f53d77ac7a4099`, JAR SHA-256 `626952f0629e67fb4a3ac398272c0339da509c03036222bbc1a174d97fbf070f`, immutable stage `g007-stage-f79d827e9ccae3dacfbf9c7f6d8b48d4b5e9801add83d63ea83cb78b2be3f1f5`, WAN-Light DP StepLM workers=2.
- **재현 절차**: stage에서 `run_LAN_docker.sh --net-profile wan_light --workers 2 --dataset P2P2D --conf mkl-cost --salg steplm --runtime-plan-audit --skip-net-check`를 실행한다. coordinator log는 `results/fed2/mkl-cost/steplm_dataset-P2P2D_coordinator_mkl-cost_g014_audit_dp_steplm_w2_r2_20260813_wan_light_coordinator1.log`이다.
- **관측 증상**: resource evidence와 workload 실행은 완료됐지만 post-run parser가 `planner lowering lacks opcode`로 실패했다. 거부된 행은 `opcode=-`, `instruction="CP?-?..."`, `status=MATCH`, `plannedPhysical=CP/LOUT`, `actual=CP/LOUT`였고 같은 로그에 정상 subtraction lowering이 7개 존재했다.
- **원인 분석**: parser가 모든 필드에 공통으로 `{None, "-"}` 누락 판정을 적용했다. 그러나 `-`는 이 문법에서 누락 sentinel인 동시에 SystemDS의 정상 subtraction physical opcode이므로 opcode 필드에는 이 판정을 적용할 수 없다. Java authority와 instruction exact proof는 이미 subtraction을 동일 opcode로 검증하고 있었다.
- **해결 요약**: ordinary lowering의 opcode는 필드가 아예 없을 때만 누락으로 거부하고, literal `opcode=-`는 정상 subtraction으로 허용했다. hop/key/nodeKind/plannedTarget/plannedPhysical은 기존처럼 `-`도 누락으로 거부한다. subtraction 허용 및 opcode 필드 부재 거부 회귀를 각각 추가했다.
- **수정 파일**: harness `experiments/tools/runtime_plan_audit.py`, `experiments/tests/test_runtime_plan_audit.py`.
- **검증**: parser unit 9/9 통과. 실패했던 실제 coordinator 로그를 수정 parser로 재검증해 schema `g014-planner-runtime-audit-v2`, `plannedPhysicalHops=144`, `loweredPhysicalHops=144`, `missingPhysicalHops=0`, `mismatches=0`, execution kinds 71,477, dispatch kinds 7로 통과했다. worker 2개 로그도 각각 worker fragment kinds 3으로 통과했다. 임시 검증 JSON은 `/tmp/g014_dp_steplm_w2_runtime_audit_fixed_parser.json`, `/tmp/g014_dp_steplm_w2_worker_audit_fixed_parser.json`이다.
- **추가 Docker 검증**: 이슈 5의 최종 StepLM cold/warm coordinator와 worker receipt가 모두 schema `g014-planner-runtime-audit-v2`로 성공했고 정상 subtraction lowering을 포함하면서 mismatch 0을 유지했다.
- **잔여 이슈**: 없음.
- **잠재 회귀 위험**: future log producer가 실제 누락 opcode를 `opcode=-`로 직렬화하면 subtraction과 구별할 수 없다. 현재 producer의 `PlannedHop` 생성자는 nonblank opcode를 강제하고 exact lowering/execution 비교가 선행되므로 그 경로는 닫혀 있다. 향후 wire format 변경 시 explicit escaping/versioning을 추가하고 parser 회귀로 감지한다.
- **의사결정 근거**: 정상 opcode를 candidate/runtime 제약으로 닫지 않고, 모호한 로그 sentinel 해석만 바로잡았다.

## 8. runtime diagnostic scanner가 인증된 audit row의 dormant `Error:` operand를 실제 오류로 오판함

- **상태**: 해결; scanner 회귀, 보존 로그 재검증, 수정 stage Docker 재실행 완료
- **적용 원칙/제약**: planner/runtime audit evidence와 실제 runtime diagnostic을 분리하되, audit mismatch parser 및 실제 예외 탐지는 계속 fail-closed한다. planner나 runtime 동작은 변경하지 않는다.
- **환경/조건**: 이슈 7 수정 stage `g007-stage-f2d525e85247f65eb26dc33c5c37bd7bab2b353b1e4624d1e8de7c0a5be7d666`, WAN-Light DP StepLM workers=2, run id `g014_audit_dp_steplm_w2_r3_20260813_wan_light_coordinator1`.
- **재현 절차**: 이슈 7 수정이 포함된 stage에서 같은 Docker canary를 실행한다. bundle은 `results/phase-bundles/cell-1/cold-docker-e2e`, coordinator audit receipt는 `results/audit/coordinator/g014_audit_dp_steplm_w2_r3_20260813_wan_light_coordinator1/mkl-cost.json`이다.
- **관측 증상**: coordinator audit-v2 생성, workload return code 0, StepLM semantic oracle exact pass, execution time 27.001초까지 성공했지만 `scan.json`만 `error=true`라 phase가 rc=3으로 격리됐다. 실제 error regex의 두 match는 `[PlannerRuntimeAudit][Lowering]`의 dormant `stop` instruction operand와 같은 dormant literal structural row였다. runtime 예외나 audit mismatch marker는 없었다.
- **원인 분석**: 기존 scanner는 `-explain runtime` instruction과 `[PlannerTrace]`만 제외했다. 새 `[PlannerRuntimeAudit]` row도 실행 diagnostic이 아니라 별도 parser가 인증하는 증거인데, quoted instruction/opcode에 미실행 분기의 문자열 `Error: unable to re-order...`가 포함될 수 있다는 점을 모델링하지 않았다.
- **해결 요약**: `[PlannerRuntimeAudit]`로 시작하는 evidence row를 generic runtime error/timeout/fallback regex 입력에서 제외했다. audit contradiction은 `runtime_plan_audit.py`의 closed status/forbidden marker 검증이 독립적으로 거부하고, 실제 실행 오류는 일반 exception/diagnostic 행으로 계속 탐지한다. scanner regression fixture에 dormant audit lowering 두 행을 추가했다.
- **수정 파일**: harness `experiments/tools/phase_bundle.py`, `experiments/tests/test_g007_harness.py`.
- **검증**: focused 10 tests와 전체 runtime-audit+G007 harness 62 tests 모두 통과했다. 보존된 164,813,965-byte raw coordinator log를 수정 scanner로 재평가해 `timeout/error/fallback/resource_invalid` 네 값이 모두 false임을 확인했다. 같은 로그의 audit-v2는 이슈 7에서 별도로 mismatch 0으로 검증됐다.
- **추가 Docker 검증**: 이슈 5의 최종 StepLM cold/warm phase에서 coordinator/worker audit, semantic oracle, runtime scan, resource receipt, teardown이 모두 하나의 성공 phase로 봉인됐다. 네 scanner flag는 두 phase 모두 false이다.
- **잔여 이슈**: 없음.
- **잠재 회귀 위험**: 향후 실제 audit 실패가 오직 audit row로만 기록되고 audit parser 호출이 우회되면 generic scanner가 이를 보지 못한다. `--runtime-plan-audit` 경로가 coordinator/worker parser receipt를 필수로 요구하는 harness 회귀와 Docker canary로 감지한다.
- **의사결정 근거**: dormant plan/evidence 문자열을 실제 runtime failure로 오인하는 측정 경계만 수정했으며 fallback, candidate, placement, TRead/TWrite 규칙은 건드리지 않았다.

## 9. DML FunctionOp의 공개 래퍼 이름을 실제 물리 호출 대상으로 오인함

- **상태**: 해결; 감사 단위/함수 경계/StepLM 회귀 및 수정 stage DP LM Docker 재검증 완료
- **적용 원칙/제약**: DML 함수 호출 instruction은 coordinator `CP/LOUT` control이어야 하며, planner authority가 지정한 실제 `FunctionOp.getFunctionKey()`와 instruction의 namespace/function name이 정확히 일치해야 한다. 출력 변수명이나 공개 래퍼 이름으로 이를 대신하지 않는다.
- **환경/조건**: immutable stage `g007-stage-fdf1aab1b7d1bcf72694b99c38a9639e3b7e250d7c933303abc39fc4ef3d1d33`, WAN-Light, DP, LM, P2P2D, workers=2, run id `g014_audit_dp_lm_w2_r1_20260813`.
- **재현 절차**: `run_LAN_docker.sh --net-profile wan_light --workers 2 --dataset P2P2D --conf mkl-cost --salg lm --runtime-plan-audit --skip-net-check`를 실행한다. 실패 로그는 `results/fed2/mkl-cost/lm_dataset-P2P2D_coordinator_mkl-cost_g014_audit_dp_lm_w2_r1_20260813_wan_light_coordinator1.log`이다.
- **관측 증상**: lowering 감사가 `FUNCTION_CONTROL_TARGET_MISMATCH`로 중단됐다. planner row의 `name=.builtinNS::m_lm`을 실제 instruction 대상 `.builtinNS::m_lmCG`와 비교했지만, 같은 emission row의 FunctionOp/opcode는 실제로 `(fcall .builtinNS m_lmCG)`였다.
- **원인 분석**: `PlannedHop.valueName`은 값 바인딩/공개 래퍼 이름을 표현하는 필드인데 function-call control 검증도 이를 호출 대상처럼 재사용했다. LM builtin wrapper는 Hop display/value name과 실제 구현 함수 key가 합법적으로 다르므로 false mismatch가 발생했다.
- **해결 요약**: immutable planner authority에 별도의 필수 `controlTarget`을 추가했다. `FUNCTION_CALL` node는 정확한 `FunctionOp.getFunctionKey()`를 등록해야 하고 lowering은 `FunctionCallCPInstruction`의 namespace/function name과 이 필드만 비교한다. `valueName` 기반 변수 배치 검증은 그대로 유지한다. stale-plan 동일성 비교와 진단 출력에도 `controlTarget`을 포함해 recompile에서 실제 호출 대상 변경을 숨길 수 없게 했다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAudit.java`, `src/test/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAuditTest.java`.
- **검증**: audit unit 38/38, `FunctionBoundaryRuntimeAliasContractTest` 1/1, `SharedPlannerFunctionPlanPropagationRedTest` 6/6, `CampaignBG014DpStepLmFunctionInputCandidateRedTest` 1/1이 통과했다(`/tmp/g014-runtime-audit-function-regression-r2.log`). 공개 이름 `ns::publicWrapper`와 실제 호출 대상 `ns::fn`이 달라도 exact control target으로 성공하고, 다른 함수 호출은 계속 fail-closed하는 회귀를 추가했다. 전체 package build도 성공했고 새 JAR SHA-256은 `e74859702aaaab5050d567b0eb9998aefcce1ca05e06c205d7e475d4952591d6`이다.
- **추가 Docker 검증**: source commit `88c08266063aaaf48a5df7cef5fbf3d9ad922d49`, JAR SHA-256 `e74859702aaaab5050d567b0eb9998aefcce1ca05e06c205d7e475d4952591d6`, immutable stage `g007-stage-e60d051448dad401302bbb250e12aee7aa6eda535cd457bcfa819d445841a86f`에서 WAN-Light DP LM workers=2를 cold/warm 실행했다. 두 coordinator 모두 `plannedPhysicalHops=47`, `loweredPhysicalHops=47`, `missingPhysicalHops=0`, `missingSynthetic=0`, `mismatches=0`이고, worker 두 개도 각 phase의 누적 summary 2개와 최종 fragment 16종을 exact plan generation으로 통과했다. LM semantic oracle의 objective relative error는 약 `2.69e-15`, prediction NRMSE는 약 `1.64e-15`였으며 scan/resource/teardown도 모두 성공했다.
- **잔여 이슈**: 없음. 동일 audit-v2를 다른 workload/planner로 확대한다.
- **잠재 회귀 위험**: parser/rewrite가 FunctionOp의 실제 구현 이름을 바꾸면서 planner authority를 갱신하지 않으면 정확히 실패한다. `controlTarget` stale-authority 비교와 LM/StepLM Docker canary로 감지한다.
- **의사결정 근거**: planner 후보나 함수 배치를 완화하지 않고, planner가 이미 선택한 물리 호출 대상을 감사 증거에 정확히 분리했다. runtime fallback과 TRead/TWrite 규칙은 변경하지 않았다.

## 10. worker runtime audit parser가 정상 subtraction fragment opcode `-`를 누락으로 오판함

- **상태**: 해결; red/green parser 회귀, 보존된 FedAll 로그 및 새 immutable harness stage의 Heuristic L2SVM Docker 재검증 완료
- **적용 원칙/제약**: worker가 실제 실행한 모든 fragment는 parent planner authority와 exact plan generation으로 연결되어야 한다. 정상 subtraction opcode는 허용하되 opcode 필드 자체가 없는 증거는 계속 fail-closed한다.
- **환경/조건**: source commit `88c08266063aaaf48a5df7cef5fbf3d9ad922d49`, JAR SHA-256 `e74859702aaaab5050d567b0eb9998aefcce1ca05e06c205d7e475d4952591d6`, stage `g007-stage-e60d051448dad401302bbb250e12aee7aa6eda535cd457bcfa819d445841a86f`, Docker WAN-Light FedAll L2SVM P2P2D workers=2, run id `g014_audit_fedall_l2svm_w2_r1_20260813`.
- **재현 절차**: `run_LAN_docker.sh --net-profile wan_light --workers 2 --dataset P2P2D --conf mkl-fout --salg l2svm --runtime-plan-audit --skip-net-check`를 실행한다. worker 로그는 `results/workers/logs/worker{1,2}-800{1,2}_g014_audit_fedall_l2svm_w2_r1_20260813_wan_light.out`이다.
- **관측 증상**: cold/warm workload와 semantic oracle, coordinator audit-v2는 모두 성공했지만 최종 worker parser가 `worker fragment lacks fragmentOpcode`로 셀을 실패시켰다. 거부된 행은 `requestType=EXEC_INST fragmentOpcode=- actual=CP/LOUT`였으며 worker 1 로그에만 같은 정상 subtraction fragment가 세 번 존재했다. coordinator 두 개는 각각 `plannedPhysicalHops=108`, `loweredPhysicalHops=108`, `missingPhysicalHops=0`, `missingSynthetic=0`, `mismatches=0`이었다.
- **원인 분석**: `_validate_worker_fragment_line`이 `plan`, `parentAuditKey`, `actual`과 함께 `fragmentOpcode`에도 공통 `{None, "-"}` 누락 판정을 적용했다. 하지만 `-`는 SystemDS의 정상 subtraction physical opcode이므로 wire value와 누락 sentinel을 구분해야 한다. 이슈 7에서 coordinator lowering opcode는 이미 같은 문제를 수정했지만 worker fragment 경로가 빠져 있었다.
- **해결 요약**: `plan`, `parentAuditKey`, `actual`은 기존처럼 `None`/`-`를 거부하고, `fragmentOpcode`는 필드 자체가 없을 때만 거부한다. literal `fragmentOpcode=-`는 정상 subtraction으로 허용한다. subtraction 허용 회귀와 필드 부재 fail-closed 회귀를 각각 추가했다.
- **수정 파일**: harness `experiments/tools/runtime_plan_audit.py`, `experiments/tests/test_runtime_plan_audit.py`.
- **검증**: 새 두 테스트는 수정 전 subtraction 케이스가 실제로 red였고 수정 후 parser unit 11/11, runtime-audit+G007 harness 64/64가 통과했다(`/tmp/g014-worker-subtraction-parser-tests-r1.log`, `/tmp/g014-worker-subtraction-harness-regression-r1.log`). 보존된 worker 두 로그를 수정 parser로 재검증해 각 `summary_blocks=2`, `final_worker_fragment_kinds=72`로 통과했으며 receipt는 `/tmp/g014-fedall-l2svm-worker-audit-fixed-parser.json`이다. cold/warm runtime은 `127.288/124.018`초, semantic oracle와 네 scan flag도 모두 성공했다.
- **추가 Docker 검증**: harness commit `b199474606f900e62329779ca56132bd4696b578`, immutable stage `g007-stage-79a2ee350750154c0c7b5653cd08c21ac0af53b8e9908b36ddfaccc2d98e4238`에서 WAN-Light Heuristic L2SVM workers=2를 cold/warm 실행했다. 실행시간은 `153.876/150.411`초이고 semantic oracle, scan, resource, teardown이 모두 성공했다. coordinator 두 phase는 `plannedPhysicalHops=108`, `loweredPhysicalHops=108`, physical/synthetic missing 0, mismatch 0이며 worker 두 로그도 각 `summary_blocks=2`, `final_worker_fragment_kinds=72`로 수정 parser를 통과했다.
- **잔여 이슈**: 없음. 같은 parser를 336-cell campaign의 모든 worker 로그에 적용한다.
- **잠재 회귀 위험**: 향후 producer가 실제 누락 opcode를 literal `-`로 직렬화하면 subtraction과 구분할 수 없다. 현재 Java producer가 실제 fragment instruction에서 opcode를 직접 추출하고 worker audit row의 status/parent key/plan/placement를 별도로 검증하므로 그 경로는 닫혀 있다. wire schema 변경 시 explicit escaping/versioning과 worker parser 회귀로 감지한다.
- **의사결정 근거**: planner/runtime 동작이나 후보군을 바꾸지 않고 정상 실행 증거를 잘못 거부하던 parser만 수정했다. runtime fallback, TR/TW, recompile CP/FOUT 규칙은 변경하지 않았다.

## 11. Heuristic L2SVM의 `frontierEdgeCount=0`인데 planner-selected REFED 3개가 실행됨

- **상태**: 해결; 정책 누수가 아님을 exact value-version probe와 Docker planner→lowering→runtime 감사로 검증 완료
- **적용 원칙/제약**: Heuristic은 vector aggregate demotion producer와 그 exact local prefix만 REFED 금지한다. 그 밖의 독립적 ordinary value는 FedAll과 같은 합법 후보군을 유지한다. runtime은 planner-selected synthetic action만 실행하며 임의 upload/fallback을 만들지 않는다.
- **환경/조건**: source commit `88c08266063aaaf48a5df7cef5fbf3d9ad922d49`, JAR SHA-256 `e74859702aaaab5050d567b0eb9998aefcce1ca05e06c205d7e475d4952591d6`, 이슈 10의 새 stage, Docker WAN-Light Heuristic L2SVM P2P2D workers=2.
- **재현 절차**: 이슈 10의 Heuristic canary cold coordinator log에서 `Heuristic-PolicySummary`, `Heuristic-Demotion`, `Emission-RelocationSource`, `PlannerRuntimeAudit Lowering-Synthetic/Execution`을 exact key/action/token으로 대조한다. 별도 일회성 분석-object probe는 Docker와 동일한 50,000x2,100 ROW-sharded L2SVM fixture의 모든 marker/local-prefix value와 selected relocation source value를 identity 비교한다.
- **관측 증상**: policy summary는 `markerCount=4`, `localPrefixCount=17`, `frontierEdgeCount=0`인데 runtime에 `fed_refed` 세 종류가 실제 실행됐다. 표면적으로는 “frontier가 없는데 local-prefix를 다시 FED로 올린 것”처럼 보일 수 있었다.
- **원인 분석**: 세 relocation source는 모두 `TRead Xd`, `versionKind=ORDINARY`, `selected=CP/LOUT`인 별도 value-version이다. Docker log의 source key는 네 demotion producer key와 모두 다르고, analysis-object probe에서는 selected relocation의 모든 source value가 marker set 및 17개 local-prefix value set 밖임을 직접 확인했다. `frontierEdgeCount=0`은 이 demotion 경로들의 재진입이 없다는 뜻이지, 프로그램 전체에서 모든 REFED를 금지한다는 뜻이 아니다.
- **해결 요약**: planner/runtime 코드는 변경하지 않았다. 기존 정책 구현이 의도대로 “LOUT 결정 경로만 no-REFED, 나머지는 FedAll 기반”임을 검증했으며, candidate-space를 추가로 닫지 않았다.
- **수정 파일**: production source 없음. 증거 문서만 갱신했다.
- **검증**: 일회성 source probe는 처음에 Docker의 정적 action 수를 고정해 fixture 차이를 드러낸 뒤, 정책 불변식만 검증하도록 바로잡아 통과했다(`/tmp/g014-heuristic-l2svm-policy-provenance-probe-r3.log`, SHA-256 `edc8d35623ef3b5bdbbf28491b2a7624c1b8e3502b34ec0aa1ae61ac754cb9c0`). probe 파일은 검증 뒤 제거했다. 실제 Docker 증거는 세 source key와 네 marker key가 disjoint이고, 세 action 모두 lowering과 execution에서 `status=MATCH`, physical `FED/FOUT/ROW`임을 확인했다(`/tmp/g014-heuristic-l2svm-runtime-policy-evidence.json`, SHA-256 `7ff774eb5b3bd6eb1c3309fc9dbeaa7531f10f3ca6672f6d8daa7df1118bedc1`). Heuristic runtime fingerprint에는 FedAll에 없는 `fed_refed:3`이 존재해 두 정책이 실제 물리 실행에서도 분리됐다.
- **잔여 이슈**: 336-cell 전수 결과에서 marker/local-prefix source의 선택된 REFED가 하나라도 나타나면 실패하도록 policy auditor를 적용한다. `frontierEdgeCount=0`만으로 전체 REFED 0을 요구해서는 안 된다.
- **잠재 회귀 위험**: CFG/recompile identity 변경이 ordinary TRead를 marker/local-prefix와 잘못 단절시키거나 반대로 합쳐 정책을 새게 할 수 있다. exact `ValueVersionKey` 교집합 검사와 planner/runtime audit의 synthetic action chain으로 감지한다.
- **의사결정 근거**: Heuristic 철학과 일치하는 합법 ordinary upload를 성능상 이유로 닫지 않았다. planner가 선택한 모든 synthetic upload가 exact authority를 갖고 runtime에서 그대로 실행되는지만 강화 검증했다.

## 12. 336-cell one-pass 러너가 runtime-plan audit을 강제하지 않음

- **상태**: 해결; 전체 하네스 183 tests 통과, 새 immutable harness snapshot/stage 생성 전
- **적용 원칙/제약**: 모든 성능 셀의 cold/warm coordinator instruction과 모든 worker fragment는 planner가 선택한 exact plan generation, exec, output placement/FType 및 synthetic action authority에 연결되어야 한다. audit 누락 셀은 semantic 결과가 맞더라도 성능 근거로 인증하지 않는다.
- **환경/조건**: harness commit `b199474606f900e62329779ca56132bd4696b578` 기반 `experiments/tools/run_one_pass_performance.py`, 336-cell Docker lifecycle, WAN-Light → WAN-Mid → LAN.
- **재현 절차**: one-pass 러너의 `command_tail`과 `with_resource_contract()`를 확인한다. 기존 코드는 `CAMPAIGN_PLAN_EXPLAIN=1`과 runtime planning receipt는 강제했지만 `--runtime-plan-audit` 또는 `RUNTIME_PLAN_AUDIT=1`을 전달하지 않았다.
- **관측 증상**: 기존 러너로 전체 campaign을 시작하면 planner trace와 cold/warm runtime-plan SHA는 수집되지만, `results/audit/coordinator/<run>/...json` 및 `results/audit/workers/<run>/...json`의 생성·검증이 필수 계약이 아니었다. 따라서 planner-selected individual Hop/action의 lowering 누락, placement 불일치 또는 worker fragment의 authority 누락을 전체 336셀에서 fail-closed한다고 주장할 수 없었다.
- **원인 분석**: direct Docker canary는 명시적으로 `--runtime-plan-audit`을 사용했지만 one-pass orchestration에 이 플래그와 audit artifact 인증을 연결하지 않았다. planning receipt 검증을 individual runtime action 검증과 동일한 것으로 잘못 간주한 하네스 경계 누락이다.
- **해결 요약**: one-pass 고정 환경에 `RUNTIME_PLAN_AUDIT=1`을 강제하고 Docker runner argv에도 `--runtime-plan-audit`을 명시했다. 각 셀 완료 전에 cold coordinator, warm coordinator, 활성 worker 전부의 `g014-planner-runtime-audit-v2` artifact와 원본 로그 경로/SHA를 phase bundle 및 worker manifest에 exact bind한다. coordinator는 physical/synthetic missing과 mismatch 0, selected physical lowering 총수 일치, 필수 authority/lowering/execution 증거를 요구한다. worker는 cold/warm 두 summary block과 모든 worker log의 exact 인증을 요구한다. 이 증거를 row에 봉인하고 predecessor 재사용 시에도 다시 검증하며, manifest도 audit-v2 필수 계약을 명시한다.
- **수정 파일**: harness `experiments/tools/run_one_pass_performance.py`, `experiments/tests/test_one_pass_performance.py`.
- **검증**: one-pass 집중 테스트 16/16 통과. 새 테스트는 cold/warm coordinator와 모든 worker audit의 exact path/SHA binding을 통과시킨 뒤 coordinator `mismatches=1` artifact를 유효한 checksum으로 다시 봉인해도 fail-closed하는 것을 확인한다. 전체 harness test discovery는 183/183 통과했다(`/tmp/g014-harness-all-regression-20260813-r1.log`). `python3 -m py_compile`도 성공했다.
- **잔여 이슈**: 변경된 harness를 새 immutable snapshot으로 봉인하고 빈 stage에서 336-cell campaign을 시작해야 한다. 기존 `b199474` stage의 canary는 기능 검증 근거로만 보존하며 새 campaign row와 병합하지 않는다.
- **잠재 회귀 위험**: audit instrumentation의 로그 I/O가 측정시간에 영향을 줄 수 있으나 모든 planner/cell에 동일하게 강제되며, 사용자가 요구한 실행 충실성 검증을 위해 의도적으로 포함한다. audit artifact naming 또는 coordinator log naming이 바뀌면 셀이 즉시 실패하므로 하네스 테스트와 첫 Docker 셀에서 감지한다.
- **의사결정 근거**: planner/runtime 동작이나 후보군은 변경하지 않았다. 누락된 검증 경계를 강화해 runtime fallback 또는 암묵적 보정을 숨길 수 없게 했다.

## 13. DP가 동일 Hop ID의 raw/recompile occurrence를 섞어 잘못된 child carrier를 선택함

- **상태**: 해결; occurrence-aware DP 회귀, 전체 DP 패키지 및 fallback 통합 회귀 통과
- **적용 원칙/제약**: DP의 지역 최적화 범위는 유지하되, 한 occurrence의 선택은 반드시 그 occurrence가 소유한 exact child plan/carrier만 참조한다. 누락 plan을 runtime fallback으로 보충하거나 후보를 닫지 않는다.
- **환경/조건**: DP, LogReg/KMeans/ALS 및 loop-carried transient/function input이 존재하는 compiled/recompile graph. 동일 logical Hop family가 여러 concrete carrier와 occurrence key를 갖는 경우.
- **재현 절차**: `CampaignBG014DpLogRegTransientForwardRedTest`와 DP occurrence/rewire 회귀를 실행한다. 수정 전 로그는 `/tmp/g014-dp-logreg-occurrence-aware-r1.log`, 진단 trace는 `/tmp/g014-dp-als-output-carrier-trace-r1.log`이다.
- **관측 증상**: 수정 전 LogReg 두 테스트가 각각 `Missing LOUT federated plan for child hop ...`로 실패했다. DP memo에 exact occurrence plan이 존재해도 `hopID` 기반 `contains/getFedPlanAfterPrune`가 다른 raw/recompile carrier의 arm을 보거나, parent edge에 요청 carrier ID를 기록하면서 실제 선택 plan의 carrier identity와 달라졌다. loop-carried TRead는 단일 비동기 DFS pass의 세대가 섞여 source 정의 순서에 따라서도 frontier가 달라질 수 있었다.
- **원인 분석**: DP memo는 occurrence-aware authority를 추가한 뒤에도 일부 조회/child-edge 기록이 legacy Hop ID family API를 사용했다. 또한 CFG의 여러 reaching TWrite와 shared function formal은 한 pass에서 모두 닫히지 않는 순환/다중 caller recurrence인데, 이를 일반 tree DFS처럼 처리했다.
- **해결 요약**: child plan 조회를 `getFedPlanAfterPruneForOccurrence`/exact occurrence API로 전환하고, parent의 child edge와 carrier list에는 요청 Hop이 아니라 선택된 exact plan의 `getHopRef()`를 기록한다. logical transient/shared function input은 exact semantic frontier가 안정될 때까지 bounded fixed-point closure를 수행하며, 마지막 관측 pass가 frontier를 바꾸거나 seed가 남으면 선택 전에 fail-closed한다. reaching definitions는 동시에 실행되는 n-ary inputs가 아니라 CFG alternatives로 비용화해 순환 subtree 중복 합산을 제거했다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`, `FederatedPlannerDpMemoTable.java`, `FederatedPlannerDpFedCostBased.java` 및 DP occurrence/transient/function 회귀 테스트.
- **검증**: focused occurrence/carrier 회귀가 통과(`/tmp/g014-dp-occurrence-carrier-anchor-r1.log`). DP 테스트 36개 클래스는 순차 실행 119 tests, failures/errors 0, skipped 4(`/tmp/g014-fed-dp-package-sequential-r4.log`)였고 기본 fork 실행도 RC=0(`/tmp/g014-fed-dp-package-default-r1.log`). 전체 fallback 통합은 145/145 통과(`/tmp/g014-fallback-integration-r2.log`).
- **잔여 이슈**: Docker DP canary에서 모든 selected occurrence의 lowering/execution plan generation이 exact audit key와 일치하는지 확인해야 한다. DP와 MinST의 결과 차이 자체는 DP의 부모-자식 지역 recurrence 철학 때문에 합법일 수 있다.
- **잠재 회귀 위험**: fixed-point가 비수축 recurrence에서 수렴하지 않거나 occurrence lookup 누락을 드러낼 수 있다. 64-pass 초과 시 마지막 상태를 사용하지 않고 명시적 예외로 감지하며, Docker audit의 missing/stale/mismatch로 carrier drift를 탐지한다.
- **의사결정 근거**: DP의 최적화 철학을 MinST처럼 바꾸지 않았다. 잘못된 identity 조회와 비용 recurrence만 바로잡았으며 합법 후보 공간은 축소하지 않았다.

## 14. DP의 `cpFoutType` 힌트가 선택되지 않은 CP/FOUT authority처럼 전파됨

- **상태**: 해결; DP 집중/전체 회귀 통과
- **적용 원칙/제약**: FType 힌트는 capability/비용 탐색 정보일 뿐이다. CP/LOUT→CP/FOUT 또는 FED/LOUT→FED/FOUT materialization authority는 exact candidate receipt가 해당 경로를 허용할 때만 존재한다. recompile CP/FOUT 및 TRead/TWrite `<CP,FOUT>`은 계속 금지한다.
- **환경/조건**: DP LOUT candidate 생성 중 `cpLogicalFType`을 계산하지만 exact candidate가 CP/FOUT 또는 FED/FOUT을 허용하지 않는 opcode/region.
- **재현 절차**: DP KMeans/LogReg/StepLM와 `CampaignBG014CpFoutMaterializationAuthorityRedTest`, recompile exclusion 회귀를 실행하고 selected LOUT plan의 `cpFoutType`과 candidate receipt의 `allowCPFOUT/allowFEDFOUT`을 비교한다.
- **관측 증상**: LOUT plan이 단순 shape/FType hint에서 계산된 `cpLogicalFType`을 무조건 보존했다. 후속 relocation/materialization 판정이 이 값을 실제 CP/FOUT 가능성처럼 읽으면, exact candidate가 금지한 upload를 planner authority로 승격할 수 있었다.
- **원인 분석**: legacy `cpFoutType` 필드가 비용 힌트와 선택 가능한 물리 materialization type 두 의미를 함께 가졌고, occurrence-level candidate gate와 연결되지 않았다.
- **해결 요약**: FED/LOUT plan은 `allowFEDFOUT`, CP/LOUT plan은 `allowCPFOUT`이 true일 때만 `cpFoutType`을 유지하고 그 외에는 null로 만든다. 선택된 CP/FOUT/FED/FOUT candidate 자체의 FType은 exact placement state에서 계속 보존한다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java` 및 CP/FOUT/recompile DP 회귀 테스트.
- **검증**: 18개 placement/runtime 회귀 136 tests 전부 통과(`/tmp/g014-runtime-placement-regression-bundle-r2.log`), DP 119 tests 및 fallback 145 tests도 통과했다.
- **잔여 이슈**: Docker canary에서 runtime registry에 생성된 모든 FOUT/REFED action이 exact selected action key를 갖고 hint-only plan에서는 action이 0인지 확인한다.
- **잠재 회귀 위험**: 이전에 잘못된 힌트에 의존하던 합법 경로가 사라질 수 있다. 그런 경우 후보를 다시 열기 위해 힌트를 복구하지 않고 Oracle/candidate receipt의 실제 capability 누락을 수정해야 한다.
- **의사결정 근거**: 후보를 닫은 것이 아니라 authority가 없는 힌트 누수를 제거했다. 실제 runtime-supported materialization은 candidate receipt를 통해 계속 비용 비교된다.

## 15. MinST complete assignment 후 생성된 candidate가 별도 emission 객체를 사용함

- **상태**: 해결; MinST 포함 placement/runtime 회귀 통과
- **적용 원칙/제약**: MinST의 global assignment와 projector/emitter는 같은 graph-owned exact emission identity를 소비해야 한다. 값이 같다는 이유로 별도 객체 authority를 허용하지 않는다.
- **환경/조건**: MinST exact physical selection에서 초기 alternative capture에는 candidate가 없고 complete assignment 이후 `exactCandidateReceipts`가 candidate를 완성하는 경우.
- **재현 절차**: MinST exact physical selection 및 emission identity 테스트를 실행해 `selectedEmissions`와 completed candidate receipt의 `emissionState()` 객체 identity를 비교한다.
- **관측 증상**: preliminary alternative는 value-equivalent synthetic `PlacementEmissionState`를 `selectedEmissions`에 넣고, complete assignment 뒤 생성된 candidate는 graph-owned exact emission 객체를 따로 가졌다. projector가 두 representation을 서로 다른 authority로 관측할 수 있었다.
- **원인 분석**: candidate completion은 assignment 이후 수행되지만 selected emission map은 completion 전 객체로 고정돼 있었다.
- **해결 요약**: completed exact candidate마다 selected state와 prior state를 검증한 뒤 `selectedEmissions`의 값을 candidate receipt가 소유한 exact emission 객체로 승격한다. placement가 조금이라도 다르거나 prior authority가 없으면 fail-closed한다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactPhysicalSelection.java` 및 MinST projection/emission 회귀 테스트.
- **검증**: 18개 placement/runtime 회귀 136/136 및 fallback integration 145/145 통과.
- **잔여 이슈**: MinST Docker canary에서 global assignment receipt, lowering audit, worker fragment가 같은 plan generation/action identity를 갖는지 확인한다.
- **잠재 회귀 위험**: 새 candidate completion 경로가 exact emission을 제공하지 않으면 즉시 `MINST_PHYSICAL_COMPLETED_CANDIDATE_EMISSION_MISMATCH`로 실패한다. 이를 silent repair하지 않는다.
- **의사결정 근거**: MinST 목적함수나 후보 공간을 바꾸지 않고, global solution을 실행 authority로 투영하는 identity 결함만 수정했다.

## 16. LOCAL/FOUT/REFED registry가 planner action identity를 잃어 runtime audit이 값 기반 매칭에 의존함

- **상태**: 해결; registry snapshot, transaction rollback, runtime lowering 회귀 통과
- **적용 원칙/제약**: synthetic materialization은 exact planner action key로만 실행·감사한다. 동일 source/consumer/FType 값을 가진 다른 action을 대신 사용하지 않으며 runtime은 action을 생성하거나 보정하지 않는다.
- **환경/조건**: DP function-input FOUT→LOUT local materialization 및 REFED/FOUT registry emission. 동일 scope/source delta가 값으로는 같지만 action occurrence가 다른 경우.
- **재현 절차**: `CampaignBG014LocalMaterializationAuthorityRedTest#selectedLocalAuthorityEmitsOneExactSortedRegistryEntry`와 `FederatedDagLocalMaterializeTest`를 실행하고 registry snapshot의 planner action key를 selected `LocalMaterializationActionKey.normalizedSignature()`와 비교한다. 수정 전 로그는 `/tmp/g014-local-materialization-alone-r1.log`이다.
- **관측 증상**: registry entry는 consumer inputs/FType/reason만 보존하여 expected/actual snapshot이 서로 다른 `LocalMaterializeSpec` identity로 나타났고, runtime audit이 어느 planner action이 lowering됐는지 exact하게 증명할 수 없었다.
- **원인 분석**: common planner의 action identity가 `PlacementEmissionTransaction`에서 legacy Lop registries로 내려갈 때 유실됐다. copy/snapshot/restore도 action key를 보존할 필드가 없었다.
- **해결 요약**: LOCAL/FOUT/REFED authority와 transaction `RegistryWrite`에 normalized planner action key를 추가하고 register/copy/snapshot/restore/lowering까지 전달했다. emission 전에 canonical sorted action 집합과 exact compiled matrix input edge를 검증하고, transaction 실패 시 runtime-action registry까지 원자적으로 rollback한다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java`, `PlannerRuntimeActionRegistry.java`, `src/main/java/org/apache/sysds/lops/compile/FederatedLocalMaterializeRegistry.java`, `FederatedFoutMaterializeRegistry.java`, `FederatedRefedRegistry.java` 및 대응 테스트.
- **검증**: local materialization focused 회귀의 red failure를 확인한 뒤 수정했으며, 18개 placement/runtime 136 tests, DP 119 tests, fallback 145 tests가 모두 통과했다.
- **잔여 이슈**: Docker runtime audit에서 coordinator/worker별 synthetic action의 lowering/execution key 전수 일치를 확인한다.
- **잠재 회귀 위험**: legacy caller가 null action key로 등록한 entry는 planner-selected exact action으로 인증될 수 없다. audit-enabled campaign에서 즉시 missing/mismatch로 검출하고, legacy 경로를 planner action으로 위장하지 않는다.
- **의사결정 근거**: runtime 기능이나 placement gate를 완화하지 않고 planner가 이미 선택한 synthetic action의 provenance를 끝까지 보존했다.

## 17. Hop `visited` scratch bit가 program-structure guard에 포함되어 테스트/플래너 실행 순서에 따라 false mutation이 발생함

- **상태**: 해결; 순차 및 기본 fork DP 패키지 통과
- **적용 원칙/제약**: 실제 graph/program 구조 변경은 fail-closed하되, compiler traversal의 임시 `visited` bit는 immutable placement authority가 아니다. committed planner emission만 명시적으로 새 구조 authority로 승인한다.
- **환경/조건**: 여러 planner/rewire 테스트를 동일 JVM에서 순차 실행하거나 Hop traversal이 `visited`를 설정한 뒤 동일 `PlacementAnalysis` guard를 재사용하는 경우.
- **재현 절차**: `CampaignBDpRewireOwnerContractTest`를 다른 DP 테스트 뒤에 순차 실행한다. 수정 전 진단은 `/tmp/g014-rewire-contam-diag-r1.log`, `/tmp/g014-rewire-contam-diag-r2.log`이다.
- **관측 증상**: 단독 실행은 통과하지만 패키지 순서에서는 `PLACEMENT_ANALYSIS_PROGRAM_STRUCTURE_CHANGED` 또는 B05 receipt assertion이 실패했다. HOP edge/opcode/placement가 바뀌지 않았는데 이전 traversal이 남긴 `visited` bit만 fingerprint를 바꿨다.
- **원인 분석**: 하나의 fingerprint가 (a) 분석 중 mutation detection과 (b) 장기 program-structure authority 두 목적을 동시에 담당하면서 traversal scratch까지 포함했다.
- **해결 요약**: 분석 전/후 mutation detection용 full fingerprint는 visited를 계속 포함하고, 장기 structure guard는 오직 visited bit만 제외한 `captureProgramAuthority`를 사용한다. `PlacementEmissionTransaction`이 완전히 commit된 후에만 guard의 새 fingerprint를 승인하며, 그 외 구조 변화는 diff와 함께 거부한다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementGraphFingerprint.java`, `NeutralPlacementGraphBuilder.java`, `PlacementAnalysis.java`, `PlacementEmissionTransaction.java` 및 rewire/architecture 회귀 테스트.
- **검증**: 재현 순서 회귀가 통과(`/tmp/g014-three-regressions-r2.log`). DP 패키지는 순차 119 tests와 기본 fork 실행 모두 RC=0이며, 145개 fallback 통합도 통과했다.
- **잔여 이슈**: Docker canary에서 dynamic recompile 후 authorized generation과 stale instruction rejection을 함께 확인한다.
- **잠재 회귀 위험**: visited 외의 실제 구조 필드가 guard에서 빠지면 mutation을 놓칠 수 있다. full/authority fingerprint 행 비교 architecture tests와 stale-plan runtime audit로 감지한다.
- **의사결정 근거**: runtime/플래너 계획을 완화하지 않고 구조 authority와 compiler scratch 상태의 범주 오류를 분리했다.

## 18. B05 회귀 테스트가 실제 dynamic-recompile CP/FOUT 금지 규칙의 반대를 기대함

- **상태**: 해결; fixture 의미와 최상위 recompile 제약에 맞게 테스트 계약 수정
- **적용 원칙/제약**: recompile 경로에서는 `<CP,FOUT>`을 허용하지 않는다. 테스트 통과를 위해 exclusion을 제거하거나 planner gate를 완화하지 않는다.
- **환경/조건**: `CampaignBDpRewireOwnerContractTest`의 B05 loop fixture.
- **재현 절차**: B05 fixture의 occurrence `recompileContext`와 node exclusions를 출력한 뒤 `b05HasNoCloneClaimsAndPreservesTheRealDpReceipt`를 실행한다. 수정 전 관련 실패는 `/tmp/g014-rewire-visited-fix-r1.log`에 남아 있다.
- **관측 증상**: fixture에는 실제 `recompile` context node가 존재하고 올바르게 `RECOMPILE_CP_FOUT`을 게시하지만, 테스트는 graph 전체에 해당 exclusion이 없어야 한다고 기대했다.
- **원인 분석**: “B05에는 clone claim이 없다”는 rewire invariant를 “dynamic recompile exclusion도 없다”로 잘못 확장한 오래된 assertion이었다.
- **해결 요약**: clone 없음/원본 receipt 보존 assertion은 유지하고, dynamic region이 비어 있지 않으며 모든 dynamic node가 `RECOMPILE_CP_FOUT`을 갖는지를 검사하도록 계약을 수정했다. production gate는 완화하지 않았다.
- **수정 파일**: `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBDpRewireOwnerContractTest.java`.
- **검증**: focused rewire 회귀와 DP 119-test 패키지 통과.
- **잔여 이슈**: 없음. Docker recompile workload에서 CP/FOUT action이 생성되지 않는지는 runtime audit로 계속 검증한다.
- **잠재 회귀 위험**: fixture가 동적 region을 잃으면 테스트가 즉시 실패한다. clone claim과 recompile exclusion은 독립 assertion으로 유지한다.
- **의사결정 근거**: 고정 제약을 코드에 맞춘 것이 아니라 테스트를 프로젝트의 명시적 recompile 금지 원칙에 맞췄다.

## 19. `PlacementAnalysis`가 Hop shape와 program fingerprint를 재파생해 두 번째 분석 universe를 소유함

- **상태**: 해결; architecture 17 tests, placement/runtime 136 tests, DP/fallback 전체 회귀 통과
- **적용 원칙/제약**: `NeutralPlacementGraphBuilder`가 shape/occurrence/program 구조의 유일한 분석 소유자다. `PlacementAnalysis`는 frozen fact carrier이며 Hop getter나 graph traversal로 사실을 재파생하지 않는다.
- **환경/조건**: placement architecture guards 및 `PlacementAnalysisConstructionArchitectureTest`, `PlacementFoundationArchitectureGuardTest`, `CampaignBArchitectureGuardTest`.
- **재현 절차**: architecture focused bundle을 실행한다. 수정 전 `/tmp/g014-runtime-placement-regression-bundle-r1.log`에서 ownership closure 2개, construction 2개, API surface 1개가 실패했다.
- **관측 증상**: `PlacementAnalysis`가 compiled input edge의 matrix 여부를 `Hop.getDataType()`으로 다시 읽었고, 내부 `ProgramStructureGuard`가 `PlacementGraphFingerprint`로 program을 직접 순회했다. 즉 builder가 만든 `PlacementShapeFacts`/occurrence universe와 별도의 사실 파생 경로가 생겼다. 일부 architecture scanner는 intentional `buildAnalysis -> buildDetachedAnalysis` delegation 및 public detached validation API를 중복 construction으로 오판했다.
- **원인 분석**: immutable analysis carrier에 convenience validation과 concrete fingerprint 구현을 함께 두어 소유권 경계가 흐려졌다. source scanner도 overloaded constructor와 intentional delegation을 정확히 모델링하지 못했다.
- **해결 요약**: builder에 `OracleFacade.nodeShape` 단일 호출 helper를 두고 builder/analysis의 모든 shape 판정을 `PlacementShapeFacts`로 전달했다. concrete `ProgramStructureGuard`와 fingerprint traversal은 builder로 이동하고, analysis에는 `run/authorizeCommittedEmission`만 제공하는 package-private opaque authority interface를 남겼다. scanner는 모든 overloaded constructor body를 검사하고 intentional detached delegation은 하나의 guarded construction universe로 인정하되 divergent dual construction은 계속 거부한다. public API guard는 `build`, `buildAnalysis`, `buildDetachedAnalysis`, `requireAuthoritativeAnalysis`의 의도된 계약을 명시했다. runtime audit 관측 클래스 예외는 exact FQCN과 `ExecutionContext/getProperty` 두 token에만 한정했다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`, `PlacementAnalysis.java`; architecture tests의 `JavaSourceBoundaryScanner.java`, `PlacementAnalysisConstructionArchitectureTest.java`, `PlacementFoundationArchitectureGuardTest.java`, `CampaignBPlannerOwnershipClosure.java`, `CampaignBArchitectureGuardTest.java`.
- **검증**: architecture focused 17/17 통과(`/tmp/g014-architecture-focused-r3.log`), test compile RC=0(`/tmp/g014-testcompile-shapefacts-r1.log`), 18개 placement/runtime bundle 136/136 통과(`/tmp/g014-runtime-placement-regression-bundle-r2.log`), DP 119 tests 및 fallback 145/145 통과. `NeutralPlacementGraphBuilder`와 `PlacementAnalysis`에는 direct `getDataType/getDim1/getDim2` 사용이 남지 않았고 analysis에는 `PlacementGraphFingerprint` 참조가 없다.
- **잔여 이슈**: 새 immutable JAR/stage의 Docker canary에서 frozen analysis fingerprint, selected plan, lowering/execution receipt가 동일 generation으로 연결되는지 검증한다.
- **잠재 회귀 위험**: 새 shape consumer가 Hop getter를 직접 사용하거나 audit 관측 예외가 넓어지면 두 번째 universe가 재생길 수 있다. architecture scanner와 exact exception negative test가 이를 fail-closed한다.
- **의사결정 근거**: capability/후보/비용 정책은 변경하지 않고, 공통 전처리의 단일 소유권과 실행 감사 정합성을 강화했다.
