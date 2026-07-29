# Session issues — 2026-07-29

## so006 소스 동기화와 legacy greedy planner 이식 판단

- **상태**: 해결
- **환경/조건**: 기준 소스 `/tmp/g007-hdfs-preflight-opt-20260728`의 `6d4d852fb423498ac599b3b5fcd80da8f385aeaf`; 원격 `so006:/home/mchoi/systemds`; Docker 실험만 허용하며 `run_LAN.sh` 결과는 사용하지 않는다.
- **재현 절차**:
  - 동기화 전 비교: `git merge-base 6d4d852fb423 3a7acc737cde`; `git rev-list --left-right --count 6d4d852fb423...3a7acc737cde`; `git diff --shortstat 6d4d852fb423..3a7acc737cde`
  - 동기화 후 확인: 로컬과 원격에서 각각 `git rev-parse HEAD`, `git rev-parse HEAD^{tree}`, `git status --short --branch`
- **관측 증상**: so006의 `move`는 공통 조상 `a6248a770c596617898da54a68b8bcbbe318d8ca`에서 갈라진 단일 legacy greedy-planner commit `3a7acc737cde296641255b03555a3c7fd0db7d14`였고, 현재 소스는 반대편에 789 commits가 있었다. snapshot 차이는 276 files, +4,501/-69,383 lines였다.
- **원인 분석**: so006 구현은 FedAll이 HOP를 직접 순회하고 Heuristic이 이를 상속하는 이전 구조다. 현재 소스는 `NeutralPlacementGraphBuilder`가 공통 후보 그래프와 typed heuristic facts를 만들고 `FedAllPlacementAdapter`/`HeuristicPlacementAdapter`가 각각 정책을 선택한 뒤 `PlacementEmissionTransaction`이 exact relocation을 반영한다. 따라서 so006의 7-file patch를 기계적으로 이식하면 현재 공통 oracle 정책을 중복 적용하거나 되돌릴 가능성이 크다.
- **해결 요약**:
  - so006의 이전 HEAD를 `backup/move-before-current-sync-3a7acc-20260729` 브랜치에 보존했다.
  - so006의 checked-out `move`를 현재 기준 commit `6d4d852fb423498ac599b3b5fcd80da8f385aeaf`로 reset했다.
  - production 코드는 legacy patch를 이식하지 않았다. 현재 구현이 이미 더 강한 exact shared-graph 정책을 제공하기 때문이다: FedAll은 `MAX FED -> MAX FOUT -> MIN relocation`, Heuristic은 typed vector-MM demotion 경로만 local로 제한하며 exact re-entry frontier부터 refed를 다시 허용한다.
- **수정 파일**: production 파일 없음. 동기화/검증 기록만 이 문서에 추가했다.
- **검증**:
  - 로컬/so006 HEAD: `6d4d852fb423498ac599b3b5fcd80da8f385aeaf`
  - 로컬/so006 tree: `67a2331915ded001edf21799c83ec68be630fea2`
  - so006 backup ref: `3a7acc737cde296641255b03555a3c7fd0db7d14`
  - 원격 worktree는 tracked-file 변경 없이 clean이다. `move...origin/move [ahead 789, behind 1]` 표시는 의도적으로 원격 저장소의 기존 origin 브랜치를 덮어쓰지 않았기 때문이다.
- **잔여 이슈**: 다음 검증 commit이 생성되면 동일한 commit/tree를 so006에 한 번 더 동기화하고, 그 source/JAR hash로 새 immutable Docker stage를 만들어야 한다.
- **잠재 회귀 위험**: legacy direct-greedy 테스트가 현재 pathwise typed-policy 출력(`PATH_LOCAL`, `REENTRY_FRONTIER`) 대신 삭제된 `NO_REFED` 문자열을 기대한다. 현재 builder가 만든 typed facts를 사용하는 테스트로 감지한다.
- **의사결정 근거**: runtime fallback이나 candidate-space guard를 추가하지 않고, 현재 공통 oracle/placement graph를 단일 권위로 유지했다.

## 구형 heuristic metadata RED 테스트가 현재 pathwise 정책과 불일치

- **상태**: 해결
- **환경/조건**: `CampaignBHeuristicMetadataAdversarialRedTest`, `R4HeuristicMetadataFixtureBridge`; Maven focused component tests.
- **재현 절차**: `mvn -q -DskipTests=false -Dtest='CampaignBFedAllExactAdapterContractTest,CampaignBFedAllInvocationReceiptContractTest,CampaignBHeuristicRealVectorPolicyRedTest,CampaignBHeuristicPathwiseReentryTest,CampaignBHeuristicInvocationReceiptContractTest,CampaignBHeuristicMetadataAdversarialRedTest,PlacementEmissionTransactionRedTest,OracleFacadeTest,FederatedRefedPolicyTest' test`
- **관측 증상**: 90개 중 88개 통과, 2개 실패: `BROADCAST_SAFE_MATRIX_REJECTED`, `MULTI_MARKER_ATTRIBUTION_MISSING`.
- **원인 분석**: fixture bridge가 graph를 교체하면서 `new HeuristicPolicyFacts(List.of())`로 builder가 만든 typed demotion/path facts를 삭제한다. 또한 assertions는 현재의 `PATH_LOCAL`/`REENTRY_FRONTIER` 대신 제거된 legacy `NO_REFED|...|proof=...`를 찾으며, multi-marker fixture는 vector `AggBinaryOp`가 아닌 H09 marker를 선택한다.
- **해결 요약**: production adapter가 facts를 재합성하거나 fallback하도록 바꾸지 않았다. 두 구형 fixture 파일을 제거하고, builder-produced typed facts와 현재 pathwise output을 직접 검증하는 `CampaignBHeuristicMetadataCurrentPolicyTest`로 교체했다. 새 테스트는 known MATRIX/BROADCAST 후보의 admissibility, unknown shape에서 typed demotion 비발명, 두 실제 typed marker의 exact union 및 입력 순서 독립성, fallback/repair/registry mutation 부재를 검증한다.
- **수정 파일**:
  - 삭제: `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBHeuristicMetadataAdversarialRedTest.java`
  - 삭제: `src/test/java/org/apache/sysds/test/component/federated/placement/guard/R4HeuristicMetadataFixtureBridge.java`
  - 추가: `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBHeuristicMetadataCurrentPolicyTest.java`
- **검증**: focused suite 10 classes, 90 tests, 0 failures/errors/skips. 명령: `mvn -q -DskipTests=false -Dtest='CampaignBHeuristicMetadataCurrentPolicyTest,CampaignBFedAllExactAdapterContractTest,CampaignBFedAllInvocationReceiptContractTest,CampaignBHeuristicRealVectorPolicyRedTest,CampaignBHeuristicPathwiseReentryTest,CampaignBHeuristicProvenanceContractTest,CampaignBHeuristicInvocationReceiptContractTest,PlacementEmissionTransactionRedTest,OracleFacadeTest,FederatedRefedPolicyTest' test`.
- **잔여 이슈**: 최종 commit 생성 후 so006 commit/tree 재동기화와 새 immutable Docker stage 생성이 남아 있다.
- **잠재 회귀 위험**: 단순 삭제만 하면 known safe broadcast, unknown safety 비발명, 다중 typed marker union의 회귀 검출력이 사라질 수 있다. 대체 테스트가 이 세 동작을 직접 고정해야 한다.
- **의사결정 근거**: planner의 typed facts를 권위로 유지하며 runtime fallback, legacy repair, 임의 후보 폐쇄를 추가하지 않는다.

## 빌드 검증

- **상태**: 해결
- **환경/조건**: 위 test-only migration이 반영된 현재 worktree.
- **재현 절차**: `mvn -q -DskipTests package`
- **관측 증상**: package 단계가 exit code 0으로 완료됐다. JDK incubator/deprecation 경고와 Python gateway startup smoke 로그만 출력됐다.
- **원인 분석**: 해당 출력은 빌드 실패가 아니며 Maven lifecycle은 정상 종료했다.
- **해결 요약**: 추가 production 수정 없이 현재 소스가 compile/package 가능한 것을 확인했다.
- **수정 파일**: 없음.
- **검증**: Maven exit code 0; `target/SystemDS.jar` 생성 확인은 최종 commit/stage 구성 시 hash와 함께 기록한다.
- **잔여 이슈**: 없음.
- **잠재 회귀 위험**: focused tests는 전체 test suite를 대체하지 않는다. 이번 변경은 test/doc-only이므로 production bytecode 회귀 가능성은 없지만, 향후 production 변경 시 전체 관련 suite를 다시 실행한다.
- **의사결정 근거**: 최소 검증으로 targeted behavior와 package 성공을 각각 독립 확인했다.

## FedAll/Heuristic 함수 본문 계획 미전달로 kmeans가 사실상 local 실행

- **상태**: 해결(전체 84셀 재실험 전 Docker 1셀 canary 완료)
- **환경/조건**: source worktree `/tmp/g007-hdfs-preflight-opt-20260728`; privacy `NONE`; workload `kmeans`; worker 1; network profile `lan`; Docker 전용 `run_LAN_docker.sh`; campaign seed `2026072701`. 수정 JAR SHA-256은 `3ebb083ff21f729eac52e41dad764fd698d29da73809220eb5d47466f3860fd3`이고, immutable final stage는 `/home/mchoi/g007-functionfix-stage-20260729-v4/g007-stage-31df4c1403e7ce98a843f01f8ec4e984c389b408a2353d1c0fcd8dd8910a556e`다.
- **재현 절차**:
  - 구 버전 결과 확인: `/home/mchoi/g007-selected-fedall-heuristic-current-9ed3423-74ee30f-20260729-v2/fedall/cells/001-4ca6f7687f8b/response.json`의 `discovery_bundle/raw_coordinator.log`.
  - 함수 계획 회귀 테스트: `mvn -q -DskipTests=false -Dtest=org.apache.sysds.hops.fedplanner.placement.SharedPlannerFunctionPlanPropagationRedTest test`.
  - 수정본 Docker canary: final stage의 `run_LAN_docker.sh`에 `--phase-mode discovery --replicate 1 --workers 1 --net-profile lan --alg kmeans --conf mkl-fout` 및 Heuristic의 `--conf mkl-heuristic`을 각각 적용했다. lifecycle 결과는 `/home/mchoi/g007-functionfix-docker-canary-20260729/{fedall-kmeans-w1-lan,heuristic-kmeans-w1-lan}/response.json`이다.
- **관측 증상**:
  - 구 FedAll kmeans Docker 셀은 semantic oracle을 통과했지만 `Federated Execute (Inst, UDF): 0/0`, execution `72.535 sec`였다. 같은 구 MinST 셀도 `0/0`, `69.626 sec`여서 서로 다른 planner가 동일한 local instruction signature를 만들었다.
  - 최소 재현에서 selector는 함수 namespace에 FED 상태를 선택했지만 `recompileStates=0`이었고, 실제 함수 본문의 formal transient read에는 `<CP,LOUT>`만 존재해 compiled FED candidate가 0개였다.
  - 함수 본문 후보를 연결한 직후에는 275-node component 중 39개 multi-alternative decision을 FedAll의 전수 열거가 처리하면서 120초 이상 걸렸다. 기존 metadata가 주장한 partial legality pruning이 실제 selector에는 구현돼 있지 않았다.
- **원인 분석**:
  1. 공통 `PlacementEmissionTransaction`이 선택된 함수/recompile 상태를 planner recompile registry에 게시하지 않았다.
  2. `NeutralPlacementGraphBuilder`가 함수 호출의 synthetic input boundary와 컴파일되는 함수 본문의 formal transient read를 연결하지 않았고, formal read가 호출 인자의 durable federation anchor도 물려받지 못했다.
  3. `ExactPlacementSelector`는 완전 assignment에서만 legality를 검사해 큰 함수 component에서 지수 전수 열거를 수행했다. `FedAllPlacementAdapter`도 선택 후 동일 search count를 다시 계산했다.
- **해결 요약**:
  - emission transaction이 선택 상태를 `registerPlannerRecompileState`로 게시하고, 실패 시 recompile/ambiguous registry까지 원자적으로 복구하도록 확장했다.
  - named function의 reaching definition 없는 formal transient read를 `FUNCTION_INPUT` version으로 식별하고, caller argument에서 유일하게 증명되는 durable anchor를 전파했다. synthetic function input boundary와 물리 formal read에는 `SAME_PLACEMENT` 제약을 추가했다. 서로 다른 callsite anchor가 충돌하면 anchor를 발명하지 않고 보수적으로 미전파한다.
  - 16개를 초과하는 multi-alternative component에는 exact branch-and-bound를 적용했다. singleton state 선적용, constraint-degree 순서, FED/FOUT 우선 탐색, FED/FOUT 최대가능수에 대한 안전한 upper bound만 사용한다. relocation/signature tie는 prune하지 않아 기존 exact 목적함수를 유지한다. adapter의 중복 지수 search count는 selector certificate 사용으로 제거했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerUtils.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/FedAllPlacementAdapter.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/ExactPlacementSelector.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransactionRedTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/placement/SharedPlannerFunctionPlanPropagationRedTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/placement/selector/ExactPlacementSelectorBranchAndBoundTest.java`
- **검증**:
  - shared placement/FedAll/Heuristic focused suite 10 classes가 exit 0으로 통과했다.
  - 17-node `SAME_PLACEMENT` branch-and-bound 회귀 테스트는 all-FED/all-FOUT exact maximum, `TIGHT_BOUND_EQUALITY`, explored assignment 1개와 양의 pruned count를 확인했다.
  - `mvn -q -DskipTests package`와 `git diff --check`가 모두 exit 0이다.
  - Maven integration에서 FedAll과 Heuristic kmeans 모두 `Federated Execute: 727/0`으로 전환됐다.
  - Docker FedAll canary: `success=true`, semantic oracle `passed=true`, `teardown_zero_resources=true`, execution `24.625 sec`, elapsed `27.242841 sec`, `Federated Execute: 1279/0`; `fed_ba+*`, `fed_fed_fout`, `fed_fed_refed` 등이 실제 heavy hitter에 나타났다.
  - Docker Heuristic canary: `success=true`, semantic oracle `passed=true`, `teardown_zero_resources=true`, execution `24.781 sec`, elapsed `27.707577 sec`, `Federated Execute: 1279/0`; 동일하게 실제 FED/refed heavy hitter가 나타났다.
  - 종료 후 관련 container/network/volume은 0개다. `run_LAN.sh`는 사용하지 않았다.
- **잔여 이슈**: 이번 값은 각 planner 1회 discovery canary이므로 논문용 성능 결론이나 전 planner 정렬 증거가 아니다. 기존 중단된 40셀은 구 JAR 결과이므로 재사용할 수 없으며, 새 immutable stage로 DP → FedAll → Heuristic → MinST 순서의 84셀/플래너 1회 sweep을 다시 수행해야 한다. 다중 callsite가 서로 다른 durable anchor를 제공하는 함수는 현재 anchor를 발명하지 않으며 별도 모델링이 필요할 수 있다.
- **잠재 회귀 위험**: 함수 boundary를 잘못 연결하면 서로 다른 callsite placement가 강제로 합쳐질 수 있다. `SharedPlannerFunctionPlanPropagationRedTest`의 physical formal-read 후보/registry assertion과, 향후 multi-callsite conflicting-anchor fixture로 감지한다. branch-and-bound에서 unsafe cost pruning을 추가하면 exactness가 깨질 수 있으므로 현재는 FED/FOUT count upper bound 외에는 prune하지 않는다.
- **의사결정 근거**: runtime fallback이나 opcode별 candidate closing guard를 추가하지 않았다. planner가 실제 함수/recompile 계획을 런타임까지 전달하도록 권위 경로를 복구하고, FedAll의 기존 exact 목적함수를 보존한 채 탐색 구현만 수정했다.

## 공통 함수 입력 후보 확장 후 DP formal TRead의 zero-input fact 누락

- **상태**: 해결
- **환경/조건**: 수정 전 stage `g007-stage-31df4c...`; DP, worker 1, kmeans, LAN, `mkl-cost`; Docker `run_LAN_docker.sh` discovery 1회.
- **재현 절차**: `/home/mchoi/g007-functionfix-full336-20260729/dp/cells/001-0d7aac79d30b/response.json`과 해당 `discovery-correctness/raw_coordinator.log` 확인. Maven 재현은 `FederatedKMeansPlanningTest#runKMeansPlannerDPPrivacyPrivateAggregate`다.
- **관측 증상**: 전체 sweep이 첫 DP 셀에서 `CandidateRuleLookupException: Exact candidate rule fact is missing`으로 중단됐다. 상세 진단 결과 kmeans 함수의 formal `TRead X`가 DP에서 `requested=[]`였지만 공통 candidate domain에는 call-boundary용 `[ABSENT_LOCAL]`, `[PRESENT ROW]`만 존재했다.
- **원인 분석**: 함수 입력 전파 수정이 shared planner에 필요한 local/FED 논리 경계 후보만 생성하고, 실제 컴파일된 formal TRead가 물리 입력 0개라는 DP의 기존 exact occurrence shape를 candidate domain에서 제거했다. runtime 오류나 지원 조합 부족이 아니라 공통 전처리 domain의 불완전성이다.
- **해결 요약**: `VersionKind.FUNCTION_INPUT`이면서 물리 입력이 없는 compiled formal TRead에 대해 exact zero-input candidate fact `[]`를 추가했다. call-boundary의 `[local, FED]` 후보와 graph legal states는 그대로 유지한다. 따라서 DP는 기존 zero-input 철학을 사용하고 FedAll/Heuristic은 동일 공통 그래프의 함수 경계 후보를 계속 사용한다. 진단 가능성을 위해 missing fact 예외에 parent/requested/available identity를 포함했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/placement/SharedPlannerFunctionPlanPropagationRedTest.java`
- **검증**:
  - DP kmeans Maven integration 통과.
  - shared function propagation, exact branch-and-bound, emission transaction, DP kmeans focused suite 통과.
  - 새 immutable source commit `346bee4f105b075e027d479e3a5a03dad8cec666`, JAR SHA-256 `782eda7c5f8feb7e755fbde7fba857dd579f40ce114b601ed4612e251b466ace`.
  - 새 final stage `/home/mchoi/g007-functionfix-dpcompat-stage-20260729/g007-stage-b932908c676748b9564e4f8b4c53264c56b0113a3f4cdaf85cc396ffbc1bea96`.
  - Docker DP canary: `success=true`, oracle `passed=true`, teardown `true`, execution `68.366 sec`, planner compile `1.034371 sec`. DP가 선택한 kmeans 계획은 `Federated Execute 0/0`이며 이는 candidate lookup 실패가 아니라 현재 DP 비용 선택 결과다.
- **잔여 이슈**: DP kmeans의 local 선택과 FedAll/Heuristic의 federated 선택이 비용 목적상 타당한지는 전체 동일-stage 결과와 비용/메모리 추정치를 비교해야 한다. 단일 canary만으로 planner 정렬을 확정하지 않는다.
- **잠재 회귀 위험**: formal TRead에서 zero-input fact를 빼면 DP가 즉시 실패하고, 반대로 call-boundary facts를 빼면 shared planner 함수 본문이 다시 local로 축소된다. 회귀 테스트가 동일 formal read에 empty 및 non-empty candidate key가 공존함을 검증한다.
- **의사결정 근거**: runtime fallback이나 candidate guard를 추가하지 않고, 실제 compiled occurrence와 call-boundary라는 서로 다른 두 입력 의미를 candidate domain에 모두 정확히 표현했다.

## 공통 전처리 통일 후 DP/MinST의 기존 선택 철학 보존 여부

- **상태**: 진행중(DP/MinST 소스 수준 parity 및 Maven 검증 완료, 동일 Docker stage 단일 실행 검증 대기)
- **환경/조건**: 기준 worktree `/tmp/g007-hdfs-preflight-opt-20260728`; privacy public 케이스 제외; workload `kmeans`; planner `DP`, `MinST`; 성능 근거는 `run_LAN_docker.sh`만 허용한다. 통일 전 비교 기준은 DP `7370ef0bbb36229876449b279f29e4c59f72dbc3`, MinST `c1bd4de04cc1a07222affe73afe093ecb460be05`이며, frozen manifest 비교 기준은 `3cb636bed6297d10912b9f6f21ba64ac02f923a4`와 pre-unification `5e4253ac87bed98e951054cf586a22a2784779e9`다.
- **재현 절차**:
  - manifest parity: `mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.placement.characterization.FederatedPlannerDpMinSTOfflineLiteralManifestTest test`
  - focused suite: `mvn -q -DskipTests=false -Dtest='org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelectorTest,org.apache.sysds.test.component.federated.placement.guard.CampaignBR10MinStFTypeMembershipAuthorityRedTest,org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014ProgramDynamicAuthorityParityRedTest,org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014CandidateOccurrenceSnapshotRedTest,org.apache.sysds.hops.fedplanner.placement.SharedPlannerFunctionPlanPropagationRedTest,org.apache.sysds.test.component.federated.placement.guard.CampaignBG014DisconnectedComponentCompletionRedTest,org.apache.sysds.test.component.federated.placement.characterization.FederatedPlannerDpMinSTOfflineLiteralManifestTest' test`
  - package: `mvn -q -DskipTests package`; diff hygiene: `git diff --check`
  - local diagnostic only: 동일 kmeans 스크립트를 통일 전 worktree와 현재 worktree에서 각각 한 번 실행해 FED instruction/I/O signature를 비교했다. 이 값은 Docker 성능 근거로 채택하지 않는다.
- **관측 증상**:
  - 공통 함수/placement 전처리 연결 직후 MinST kmeans의 FED 선택이 통일 전 157개에서 3개로 축소됐고, DP에서는 함수/transient dependency 연결과 component scheduling에서 누락·충돌이 발생했다.
  - 수정 후 local diagnostic execution은 DP `1.588 sec`, MinST `1.836 sec`로 MinST가 더 느렸지만, 이 실행은 작은 동일 JVM/host 진단이라 성능 정렬의 증거가 아니다.
  - 선택 signature는 통일 전과 현재가 정확히 같다. DP는 `Federated Execute 4/0`, I/O `2/1/4`, MinST는 `Federated Execute 157/0`, I/O `2/104/105`다. 즉 현재 관측된 local 시간 역전은 “통일 때문에 DP/MinST 선택 철학이 사라진 것”으로 설명되지 않는다.
- **원인 분석**:
  1. MinST의 shared preprocessing regression은 federated-source membership/도달성 정보가 canonical cut 입력으로 전달되지 않아 exact cut이 3개 FED만 남긴 것이 원인이었다.
  2. DP 함수 계획은 logical function input의 source argument, transient plan-carried state, caller→formal 및 TWrite→TRead equality closure를 공통 graph가 완전하게 표현하지 못했다.
  3. disconnected component scheduler가 이미 예약된 node의 owner가 `null`인 경우까지 현재 component 소유로 오판해 false lifecycle collision을 만들었다.
  4. offline capture는 detached analysis 및 process-global materialization/refed registry 잔존 때문에 실제 planner boundary와 다른 manifest를 만들 수 있었다.
- **해결 요약**:
  - MinST exact selector를 source-reachable/inclusion-minimum canonical cut으로 복구하고, shared facts producer가 federated-source/FType membership과 비용 사실을 selector에 손실 없이 전달하도록 수정했다. arbitrary opcode guard나 candidate closing은 추가하지 않았다.
  - DP에는 logical source argument, nullable durable anchor를 가진 plan-carried transient state, 명시적 federated FType, caller/formal 및 transient read/write equality closure를 추가했다. component traversal은 경계 소유권을 보존하며, scheduler는 `scheduled.owner != null && scheduled.owner != component`일 때만 충돌로 처리한다.
  - offline capture는 final-hop canonical analysis에 bind하고 refed/FOUT/local materialization registry를 매 fixture마다 초기화했다.
  - 5개 DP fixture(`C2-DP-05`, `C2-DP-06`, `C2-X-09`, `C2-X-10`, `C2-X-11`)의 selected Exec/Output state hash를 통일 전 기준으로 영구 고정했다. MinST 전체 manifest row는 통일 전과 현재가 동일하다.
- **수정 파일**:
  - 공통 graph/authority: `FederatedPlannerUtils.java`, `NeutralPlacementGraphBuilder.java`, `PlacementAnalysis.java`, `PlacementCostSemantics.java`, `PlacementEmissionTransaction.java`, `PlacementIdentity.java`, `ExecPlacementPolicy.java`
  - DP: `FederatedPlannerDpCostEnumerator.java`, `FederatedPlannerDpFedCostBased.java`, `DpPlacementAdapter.java`
  - MinST: `MinStExactCostFacts.java`, `MinStExactCostFactsProducer.java`, `MinStExactCutSolver.java`, `MinStPolynomialCutSolver.java`, `MinStExactPlacementProjector.java`, `MinStExactSelection.java`, `MinStExactSelector.java`
  - 회귀 검증: `FederatedPlannerDpMinSTOfflineLiteralManifestTest.java`, `LegacyDpOfflineSelectedCapture.java`, `LegacyMinstOfflineSelectedCapture.java`, `SharedPlannerFunctionPlanPropagationRedTest.java` 및 관련 DP/MinST/placement RED tests, frozen manifest resource 2개.
- **검증**:
  - manifest parity test exit 0: `/tmp/g007-debug-20260729/dp-minst-manifest-parity-v2.log`.
  - 7-class focused suite exit 0: `/tmp/g007-debug-20260729/dp-minst-final-focused-v2.log`.
  - package와 `git diff --check` exit 0: `/tmp/g007-debug-20260729/dp-minst-final-package-v2.log`.
  - package JAR SHA-256: `973ee318d7f19ac22b64644371359f73ed28d2bedeb3a49f49848f5e153e6495`.
  - 현재 offline manifest SHA-256은 `c330d8f...`이며 snapshot `3cb636...`에서도 동일했다. 갱신한 resource manifest SHA-256은 `343f5828b092f2264d92609d480aea621afabb6b2200a41351897cf4c4a79f7c`다.
- **잔여 이슈**: 깨끗한 source commit/JAR로 immutable stage를 만든 뒤 Docker에서 DP 1회, 이어서 MinST 1회만 실행한다. MinST가 여전히 느리면 재실행으로 덮지 않고 동일 데이터·seed·stage의 실제 FED instruction, upload/download, heavy hitter와 비용 추정을 비교해 기존 MinST 비용 모델 자체의 오류인지 판정한다.
- **잠재 회귀 위험**: exact selected state parity만으로 runtime 성능 우위를 보장하지는 않는다. 동일 계획이어도 비용 모델이 실제 network/compute 비용을 잘못 평가할 수 있으므로 Docker raw coordinator log의 instruction/transfer signature와 planner objective를 함께 비교해야 한다.
- **의사결정 근거**: 공통 전처리는 후보/제약 사실만 통일하고, DP의 전역 동적계획 최적화와 MinST의 exact cut 목적함수는 독립 selector로 보존한다. runtime fallback이나 임의 후보 폐쇄로 정렬을 강제하지 않는다.
