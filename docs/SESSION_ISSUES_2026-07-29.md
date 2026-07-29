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
