# Session Issues — 2026-07-31

## DP 2-worker LM의 선택된 논리 consumer가 MapMultChain에 융합되어 REFED lowering이 실패함

- **상태**: 해결 — 정확한 RED→GREEN, 관련 단위/package, 새 immutable Docker 단일 셀 검증 완료
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`
  - 수정 전 production commit: `f3bdd2ea18148312b28ec4a25a7d825a00df43db`
  - 수정 전 문서 HEAD: `80a278f148b78c0b9d5a75c0a89933a3eb4788bb`
  - 수정 production commit: `6ba2176bc562737268feb4948f2435b9f357ad5e`
  - 수정 JAR SHA-256: `32c8452181d8400e8a64cb3945d567b3a6dd528c17a00a5bf3230ac43d1d3a60`
  - 수정 final stage: `/home/mchoi/g007-dp-lm-fused-refed-stage-20260731-v2/g007-stage-6c81232d8ea85f1c6835602f862d4e8d3a79c3d7f1e98137f78de77f4c3f0eab`
  - 실패 stage: `/home/mchoi/g007-dp-runtime-placement-lock-stage-20260730-v1/g007-stage-4c838968a51801a734bf3ca923a524ad3cf38de09e6b30ca358a5ee9a858ffc4`
  - 실패 matrix root: `/home/mchoi/g007-all-planners-runtime-placement-lock-f3bdd2e-d60da24-20260731-v2`
  - 플래너/워크로드: DP / LM / `P2P2D` / 2 workers / LAN / private-aggregate
  - 고정 seed: `2026072701`
  - 전체 실행은 stage-local `run_LAN_docker.sh`만 사용했고, 해당 cell은 attempt 1에서 fail-closed함
- **재현 절차**:
  - Docker 실패 cell:
    - `/home/mchoi/g007-all-planners-runtime-placement-lock-f3bdd2e-d60da24-20260731-v2/planners/DP/cells/028-8afe53358302`
    - raw coordinator: `phases/cell-1/discovery-correctness/raw_coordinator.log`
  - 정확한 2-worker LM 단위 RED:
    - `mvn -q -Dcheckstyle.skip -Drat.skip=true -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014DpLmRegistrySlotRedTest#lmDpTwoWorkersLowersEverySelectedRefedConsumer test`
    - 로그: `/tmp/g007-dp-lm-two-worker-refed-consumer-red-20260731.log`
  - Hop/Lop ownership 진단:
    - 로그: `/tmp/g007-dp-lm-two-worker-refed-consumer-diagnostic-20260731.log`
- **관측 증상**:
  - Docker와 단위 RED 모두 동일하게
    `fed_refed lowering could not resolve selected consumer hop=245 for local hop=265`
    로 실패했다.
  - planner registry는 statement-block scope `67`에 local Hop `265`, exact selected consumer Hop `245`, anchor Hop `165`를 기록했다.
  - Hop `245`는 `AggBinaryOp ba(+*)`이고 local Hop `265`를 직접 입력으로 가진 정확한 논리 consumer였다.
  - 그러나 `AggBinaryOp` Hop `245`의 Lop은 `null`이었다. 상위 Hop `246`의 `MapMultChain` Lop이 이 부분 그래프를 융합했으며, 실제 Lop edge는 `265(Data) → 246(MapMultChain)`이었다.
  - 따라서 planner의 논리 edge 선택은 정확했지만, lowering이 consumer Hop ID와 materialized Lop Hop ID가 항상 동일하다고 가정해 실제 물리 edge를 찾지 못했다.
- **원인 분석**:
  - placement planner는 최종 Hop 경계에서 exact compiled consumer occurrence를 선택하고 registry에 논리 Hop ID를 기록한다.
  - Lop construction은 그 이후 실행되며 `AggBinaryOp`의 map-multiply-chain 최적화가 중간 Hop을 별도 Lop으로 만들지 않고 상위 `MapMultChain` Lop 안에 융합한다.
  - 기존 `Dag.resolveSelectedRefedConsumers(...)`는 같은 Hop ID를 가지면서 local Lop을 직접 입력으로 갖는 Lop만 허용했다.
  - 즉 후보 정책, 비용 모델, worker 수, seed 또는 runtime 지원 문제가 아니라, planner-selected logical consumer와 Lop fusion의 physical owner 사이에 exact lowering mapping이 없었던 구조적 결함이다.
- **해결 요약**:
  - 기존 direct Lop consumer가 존재하면 종전의 exact Hop-ID + direct-edge 규칙을 그대로 사용한다.
  - direct Lop이 없을 때만 현재 `StatementBlock`의 Hop DAG에서 등록된 consumer Hop ID와 local Hop/Lop identity가 모두 일치하는 정확한 논리 edge를 찾는다.
  - 그 논리 consumer의 parent 경로를 따라가되, 각 경로에서 처음 materialized된 Lop만 physical fusion owner 후보로 인정한다.
  - physical owner가 local Lop을 논리 edge와 동일한 multiplicity로 직접 소비하고 현재 Lop DAG에 정확히 하나 존재할 때만 rewiring한다.
  - 논리 consumer가 모호하거나, physical owner가 0개/복수이거나, 서로 다른 논리 consumer가 한 physical edge로 겹치거나, edge multiplicity가 다르면 계속 fail-closed한다.
  - 이는 임의의 다른 consumer를 선택하는 fallback이 아니라 planner-selected logical edge의 정확한 fusion owner를 lowering 단계에서 증명하는 구조적 mapping이다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/lops/compile/Dag.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpLmRegistrySlotRedTest.java`
  - `docs/SESSION_ISSUES_2026-07-31.md`
- **검증**:
  - 정확한 2-worker LM RED: 1 error, 위 동일 `hop=245/local=265` 메시지.
  - 수정 후 정확한 단일 GREEN:
    - `/tmp/g007-dp-lm-two-worker-refed-consumer-green-attempt1-20260731.log`
    - Maven return code `0`.
  - 1-worker + 2-worker LM 전체 class GREEN:
    - `/tmp/g007-dp-lm-registry-slot-suite-green-20260731.log`
    - Maven return code `0`.
  - 기존 `testDagRegistryRefed*` fail-closed/authority/multiplicity suite GREEN:
    - `/tmp/g007-refed-lowering-contract-suite-green-20260731.log`
    - Maven return code `0`.
  - PCA, StepLM, LM, placement transaction, runtime placement 관련 묶음 GREEN:
    - `/tmp/g007-fused-refed-lowering-regression-suite-20260731.log`
    - Maven return code `0`.
  - checkstyle/RAT 포함 package GREEN:
    - `/tmp/g007-fused-refed-lowering-package-20260731.log`
    - Maven return code `0`.
  - 정확한 DP/LM/2-worker/LAN Docker canary GREEN:
    - root: `/home/mchoi/g007-dp-lm-fused-refed-canary-20260731-v1`
    - attempt `1`, execution `110.72060044s`, full lifecycle `133.822947944s`
    - semantic oracle `passed=true`
    - runtime scan `error=false`, `fallback=false`, `resource_invalid=false`, `timeout=false`
    - coordinator/worker restart `0/0`, `teardown_zero_resources=true`
    - response SHA-256 `6c51d74ab70d8a03df36b48969824b16a672764c304172e379d3b5e8fe84f213`
- **잔여 이슈**:
  - 이 구조 이슈 자체의 잔여 수정은 없다.
  - 이전 v2의 27개 성공 row를 재사용하지 않는 fresh campaign
    `/home/mchoi/g007-all-planners-fused-refed-6ba2176-d60da24-20260731-v3`
    이 `DP → FedAll → Heuristic → MinST` 순서로 336 cells를 각 한 번씩 실행 중이다.
- **잠재 회귀 위험**:
  - 새로운 Lop fusion이 하나의 논리 edge를 여러 physical owner로 분기하거나 multiplicity를 바꾸면 exact mapping이 fail-closed할 수 있다.
  - 감지 방법: 신규 2-worker LM 회귀, 기존 unresolved/ambiguous/multiplicity `fed_refed` 회귀, 전체 Docker matrix에서 첫 구조 실패를 함께 확인한다.
  - 같은 종류의 fusion이 `FederatedLocalMaterializeRegistry` 경로에 나타날 가능성은 별도 관측 대상이다. 증거 없이 해당 경로를 넓히지 않으며 실제 실패 시 같은 exact-owner 원칙으로 수정한다.
- **의사결정 근거/적용 원칙**:
  - planner가 선택한 logical consumer identity와 Lop optimizer가 만든 physical owner identity를 명시적으로 연결했다.
  - 후보군 폐쇄, 비용 우회, runtime fallback/repair, TRead/TWrite `<CP,FOUT>` 완화, recompile `<CP,FOUT>` 허용은 하지 않았다.

## DP 2-worker L2SVM의 REFED source transpose가 Lop fusion으로 사라지고 FED/FOUT cross-anchor relocation이 단일 upload로 lowering됨

- **상태**: 해결 — 동일 CLI compile RED→GREEN, 관련 회귀/package 및 새 JAR Docker runtime canary 완료
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`
  - 수정 전 commit: `78a8843bafcfbd4b3aae10919c356c4fd3ce536b`
  - 수정 전 JAR SHA-256:
    `dbf8a474eb6b85fd930e493947a45d18012d05199ca2c8c796a6aa88ee2045cb`
  - 수정 전 immutable stage:
    `/home/mchoi/g007-dp-l2svm-additional-root-stage-20260731-v1/g007-stage-53693458d2faf07bfdf8ebfb55a41dd9230b0990d50edb2095f2110aa00ad168`
  - 수정 production commit: `0a7252ddb14e8eee423481453a5959d010ff014c`
  - 수정 production tree: `2b79a874b9f2295f7e39a049f5f11d913a7b3de7`
  - 수정 JAR SHA-256:
    `76bab583c4fb13e250ed5b2214e02660fc523a6543ba08723b5dc8c1126b0265`
  - 수정 immutable stage:
    `/home/mchoi/g007-dp-l2svm-two-leg-stage-20260731-v1/g007-stage-d6baba1f843e55bef07466663f8d4fdf9f694da7546d073ffc1dc388a84cb603`
  - 플래너/워크로드: DP / L2SVM / `P2P2D` / 2 workers / LAN / private-aggregate
  - 고정 seed: `2026072701`
  - Docker와 동일한 cost 환경:
    `MEM_BW=25000`, `NET_BW=1250`, `SERDES_C2W=210`,
    `SERDES_W2C=14.7`, `LATENCY=0.001`, `FLOPS=2147483648`
- **재현 절차**:
  - 실패 Docker canary:
    `/home/mchoi/g007-dp-l2svm-additional-root-canary-20260731-v1`
  - raw coordinator:
    `/home/mchoi/g007-dp-l2svm-additional-root-canary-20260731-v1/phases/cell-1/discovery-correctness/raw_coordinator.log`
  - 동일 `DMLScript.executeScript` compile-only RED:
    `mvn -q -Dtest=CampaignBG014DpL2SvmRefedSourceLoweringRedTest test`
  - RED 로그:
    `/tmp/g007-dp-l2svm-refed-cli-red-20260731.log`
- **관측 증상**:
  - Docker 및 동일 CLI compile 경로가
    `fed_refed lowering requires a local lop for hop=122`
    로 실패했다.
  - scope `55`의 Hop `122`는 L2SVM line 91
    `g_old = t(X) %*% Y`에서 `t(X)`를 나타내는 `ReorgOp r(r')`였다.
  - planner가 선택한 source state는 `<FED,FOUT,BROADCAST>`이고, target worker pool로의 relocation이 등록되어 있었다.
  - 그러나 `AggBinaryOp.constructCPLopsMMWithLeftTransposeRewrite`가
    `t(X) %*% Y`를 `t(t(Y) %*% X)`로 융합해 Hop `122`의 Lop을 제거했다.
  - transpose 경계를 보존하면 Hop `122`의 Lop은 존재하지만 이미 `<FED,FOUT>`이므로,
    기존 `Dag.insertRefedLops`는 local input만 받는 단일 `FederatedRefed`로 내릴 수 없다고 거부했다.
- **원인 분석**:
  - 첫 번째 결함은 planner가 비용에 포함하고 registry에 기록한 materialization/relocation 경계를
    Lop-level transpose fusion이 일반 대수 최적화 경계처럼 제거한 것이다.
  - 두 번째 결함은 cross-anchor relocation의 source가 이미 federated일 때 필요한
    명시적 `FED→LOUT→FOUT` 두 단계 lowering이 없었던 것이다.
  - planner의 후보 선택, seed, 데이터셋, runtime fallback 문제가 아니라,
    선택된 executable placement boundary 보존과 그 경계의 물리 lowering이 불완전했던 구조적 결함이다.
- **해결 요약**:
  - `AggBinaryOp`은 input Hop에 REFED/FOUT/local materialization registry entry가 있으면
    해당 transpose를 compressed-linalg 또는 left-transpose rewrite로 융합하지 않는다.
  - `Dag.insertRefedLops`는 selected REFED source Lop이 이미 federated이면
    명시적 `UnaryCP(PREFETCH, LOUT)`를 먼저 삽입하고, 그 local materialization을
    `FederatedRefed(FOUT)`의 입력으로 사용한다.
  - exact selected consumer만 새 FOUT을 소비하도록 rewiring하며,
    source→LOUT→target FOUT→consumer 순서를 Lop DAG와 linear Lop list 양쪽에 보존한다.
  - 이 chain은 planner가 사전에 선택하고 비용화한 cross-anchor relocation의 lowering이며
    runtime이 실패 후 개입하는 fallback/repair가 아니다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/AggBinaryOp.java`
  - `src/main/java/org/apache/sysds/lops/compile/Dag.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpL2SvmRefedSourceLoweringRedTest.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - `docs/SESSION_ISSUES_2026-07-31.md`
- **검증**:
  - 동일 CLI compile RED:
    `/tmp/g007-dp-l2svm-refed-cli-red-20260731.log`,
    `fed_refed lowering requires a local lop for hop=122`.
  - 수정 후 동일 CLI compile GREEN:
    `/tmp/g007-dp-l2svm-refed-cli-green-final-20260731.log`,
    1 test, failure/error 0, total compile `2.787055s`, execution `0.000s`.
  - 명시적 two-leg lowering 단위 회귀:
    `/tmp/g007-refed-two-leg-unit-20260731.log`, return code 0.
  - 기존 left-transpose rewrite 회귀:
    `/tmp/g007-left-transpose-existing-green-20260731.log`, return code 0.
  - 기존 REFED fail-closed/authority/multiplicity 및 local-materialize 회귀:
    `/tmp/g007-dag-refed-regressions-20260731.log`,
    12 tests, failure/error 0.
  - DP LM/StepLM 및 placement transaction 회귀:
    `/tmp/g007-dp-boundary-regressions-20260731.log`,
    14 tests, failure/error 0.
  - checkstyle/RAT 포함 package:
    `/tmp/g007-l2svm-two-leg-package-postcommit-20260731.log`,
    Maven return code 0.
  - 정확한 DP/L2SVM/2-worker/LAN Docker canary:
    - root: `/home/mchoi/g007-dp-l2svm-two-leg-canary-20260731-v1`
    - attempt `1`, execution `140.8391145s`, full lifecycle `164.155430956s`
    - semantic oracle `passed=true`
    - runtime scan `error=false`, `fallback=false`, `resource_invalid=false`, `timeout=false`
    - coordinator/worker restart `0/0`, `teardown_zero_resources=true`
    - response SHA-256 `fce20ad0e071b626e70851ee1e940c16cfed619aa79d4a9eefabc74530b774ad`
  - fresh 336-cell matrix의 동일 DP/L2SVM/2-worker/LAN cell도 attempt 1 GREEN:
    - campaign cell:
      `/home/mchoi/g007-all-planners-two-leg-refed-0a7252d-d60da24-20260731-v5/planners/DP/cells/031-7413e302c937`
    - execution `67.590017805s`, full lifecycle `94.863345881s`
    - semantic oracle `passed=true`
    - runtime scan 4종 false, restart `0/0`, teardown zero-resource
    - response SHA-256 `658b517ffb0e31fd992633dc2a8bdd842996fd313dbc629cdb87a697eeb23085`
- **잔여 이슈**:
  - 구조 이슈 자체의 잔여 수정은 없다.
  - fresh 336-cell campaign
    `/home/mchoi/g007-all-planners-two-leg-refed-0a7252d-d60da24-20260731-v5`
    이 `DP → FedAll → Heuristic → MinST` 순서로 각 cell 한 번씩 실행 중이다.
  - 전 셀 성공 후 planner별 실행시간 그래프와
    `MinST <= DP <= Heuristic, FedAll` 정렬을 관측·분석해야 한다.
- **잠재 회귀 위험**:
  - 선택된 materialization boundary가 있는 transpose에서는 기존 fusion 최적화가 비활성화되므로
    해당 계획의 instruction shape와 성능이 달라진다. 이는 계획의 데이터 이동을 보존하기 위한 의도된 변화다.
  - `PREFETCH`의 FED→LOUT runtime support 또는 scheduling dependency가 불완전하면 Docker에서
    channel/runtime 오류로 드러날 수 있다.
  - 감지 방법: exact CLI regression, two-leg Lop topology test, 기존 transpose test,
    DP Docker runtime canary와 coordinator/worker error/fallback scan을 함께 확인한다.
- **의사결정 근거/적용 원칙**:
  - 선택된 placement boundary와 사전 비용화된 `FED→LOUT→FOUT` 경로를 그대로 물리화했다.
  - candidate-space 폐쇄, runtime fallback, 임의 소비자 rewiring, TRead/TWrite 제약 완화,
    recompile `<CP,FOUT>` 허용은 하지 않았다.

## Fresh 336-cell Docker campaign의 5 GiB resource-floor headroom 부족

- **상태**: 해결 — active source/stage/evidence를 보존하고 재생성 가능한 과거 build output만 정리
- **환경/조건**:
  - active campaign:
    `/home/mchoi/g007-all-planners-two-leg-refed-0a7252d-d60da24-20260731-v5`
  - stage:
    `/home/mchoi/g007-dp-l2svm-two-leg-stage-20260731-v1/g007-stage-d6baba1f843e55bef07466663f8d4fdf9f694da7546d073ffc1dc388a84cb603`
  - Docker-only, planner order `DP → FedAll → Heuristic → MinST`, attempt 1, retry 없음
- **재현 절차**:
  - `os.statvfs('/home/mchoi')`로 available bytes와 `5*1024**3` floor를 비교한다.
  - cleanup receipt:
    `/home/mchoi/g007-resource-floor-cleanup-20260731-v2.txt`
- **관측 증상**:
  - accepted row 7개 시점에 floor 위 headroom은 `44,863,488` bytes뿐이었다.
  - 당시 campaign file 증가율의 336행 예상치는 약 `107,317,824` bytes여서,
    코드/semantic 오류가 없어도 후속 셀이 resource gate에서 중단될 상태였다.
- **원인 분석**:
  - active campaign이 아니라 과거 임시 verifier clone들의 재생성 가능한 Maven `target/`이
    local filesystem을 점유했다.
- **해결 요약**:
  - 실행 중 프로세스 참조가 없음을 확인한 2026-07-25 임시 verifier clone 3개의
    `target/`만 제거했다.
  - repository source/git metadata, active source/JAR/stage, campaign rows/bundles는 보존했다.
- **수정 파일**:
  - production source 수정 없음
  - `/home/mchoi/g007-resource-floor-cleanup-20260731-v2.txt`
  - 이 문서
- **검증**:
  - 확보 공간 `1,054,429,184` bytes.
  - 정리 후 floor 위 headroom `1,099,124,736` bytes.
  - receipt SHA-256:
    `26f637a02df39283051783a2cd929c7b23adfe2b95ac90389558d1cd2d671d15`.
  - 캠페인은 DP accepted row 8개, failure 0으로 계속 진행했다.
  - staged JAR SHA-256
    `76bab583c4fb13e250ed5b2214e02660fc523a6543ba08723b5dc8c1126b0265`
    및 campaign control checksums를 재검증했다.
- **잔여 이슈**:
  - 336셀 동안 headroom을 계속 관측하되 resource floor 자체는 완화하지 않는다.
- **잠재 회귀 위험**:
  - 다른 동시 작업이 1 GiB 이상을 소비하면 다시 floor에 접근할 수 있다.
  - planner checkpoint마다 exact available bytes와 failure rows를 함께 확인한다.
- **의사결정 근거/적용 원칙**:
  - runtime/planner/oracle를 완화하지 않고 재생성 가능한 build output만 제거해
    Docker-only fail-closed 실험 계약을 유지했다.

## DP 2-worker LogReg의 호환 가능한 REFED relocation들이 동일 registry slot에서 충돌

- **상태**: 진행중 — exact CLI RED→GREEN 및 관련 회귀 완료, package/Docker canary/새 336-cell 대기
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`
  - 실패 production commit: `0a7252ddb14e8eee423481453a5959d010ff014c`
  - 실패 JAR SHA-256:
    `76bab583c4fb13e250ed5b2214e02660fc523a6543ba08723b5dc8c1126b0265`
  - 실패 campaign:
    `/home/mchoi/g007-all-planners-two-leg-refed-0a7252d-d60da24-20260731-v5`
  - 플래너/워크로드: DP / LogReg / `P2P2D` / 2 workers / LAN / private-aggregate
  - 고정 seed: `2026072701`
  - Docker와 동일한 cost 환경:
    `MEM_BW=25000`, `NET_BW=1250`, `SERDES_C2W=210`,
    `SERDES_W2C=14.7`, `LATENCY=0.001`, `FLOPS=2147483648`
- **재현 절차**:
  - 실패 cell:
    `/home/mchoi/g007-all-planners-two-leg-refed-0a7252d-d60da24-20260731-v5/planners/DP/cells/034-3584d655a836`
  - 동일 2-worker metadata, 8 GiB coordinator memory, planner config, seed 및 cost 환경을 사용하는
    `DMLScript.executeScript` compile-only 회귀:
    `mvn -q -Dtest=CampaignBG014DpLogRegTransientForwardRedTest#logRegDpTwoWorkersCoalescesCompatibleRelocationRegistryAuthority test`
  - RED 로그:
    `/tmp/g007-dp-logreg-two-worker-registry-cli-red-20260731.log`
  - authority 진단 로그:
    `/tmp/g007-dp-logreg-two-worker-registry-diagnostic-20260731.log`
- **관측 증상**:
  - fresh 336-cell single pass는 DP row 34에서
    `PlacementEmissionException: Multiple relocations target one registry slot`
    로 fail-closed 중단했다. 이전 33개 성공 row는 새 캠페인에 재사용하지 않는다.
  - 충돌한 두 write는 정확히 같은
    `RegistrySlot[kind=REFED, scopeId=48, hopId=419]`,
    `anchorHopId=1`, durable ROW `anchorKey`를 가졌다.
  - 차이는 exact selected consumer가 각각 Hop `421`, `1156`이라는 점뿐이었다.
  - slot 충돌을 해소한 첫 실행에서는 receipt 검증이 raw memo closure의 TWrite를
    실제 적용 traversal로 오인해
    `Disconnected completion overlaps a pre-completion receipt category`
    로 추가 실패했다.
- **원인 분석**:
  - planner는 두 consumer에 필요한 relocation을 각각 선택하고 비용화했으며 두 authority는 충돌하지 않았다.
  - runtime registry 표현은 source slot 하나와 exact consumer ID 목록 하나이므로,
    emission transaction이 같은 durable authority의 consumer별 action을 prevalidation에서
    하나의 write로 합쳐야 했다. 기존 코드는 모든 중복 slot을 무조건 거부했다.
  - DP receipt는 적용 시 output-decision variant/visited 경계에 따라 실제로 순회한 key 집합이 아니라
    raw pruned memo child edge를 재귀한 closure로 category를 판정했다.
    따라서 raw closure에는 있지만 실제 root traversal에서 적용되지 않은 LogReg function-body
    TWrite가 disconnected completion과 잘못 충돌했다.
- **해결 요약**:
  - `FederatedRefedRegistry`의 기존 conflict/consumer-union 규칙을
    pure non-mutating `mergeCompatibleAuthority`로 공개했다.
  - placement emission은 stable slot map을 만들고, 중복 REFED slot만 위 규칙으로 사전 병합한다.
    durable anchor가 다르면 여전히 mutation 전에 fail-closed하며,
    FOUT/LOCAL 중복 slot은 기존처럼 거부한다.
  - 두 consumer ID는 정렬·중복 제거된 단일 REFED write에 보존된다.
  - DP invocation receipt는 root/additional-root 단계가 실제 capture한
    canonical `appliedTraversalKeys`를 기록하고, deferred/disconnected category의
    disjointness와 normalized authority를 이 exact 집합으로 검증한다.
  - stale contract test의 고정 receipt 개수는 제거하고,
    exact applied/deferred/disconnected partition의 불변성·완전성 검증으로 교체했다.
    baseline commit에서도 해당 고정 개수는 이미 실패함을 별도 detached worktree로 확인했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/lops/compile/FederatedRefedRegistry.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpLogRegTransientForwardRedTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBDpAggregateProducerContractTest.java`
  - `docs/SESSION_ISSUES_2026-07-31.md`
- **검증**:
  - exact Docker-equivalent 2-worker LogReg compile GREEN:
    `/tmp/g007-dp-logreg-two-worker-registry-cli-green-v2-20260731.log`,
    Maven return code 0, compile `4.735853s`, execution `0.000s`.
  - full LogReg regression class under exact Docker cost environment:
    `/tmp/g007-dp-logreg-full-class-exact-cost-green-20260731.log`,
    Maven return code 0.
  - placement transaction, same-Hop occurrence, registry merge 및 applied traversal receipt:
    `/tmp/g007-dp-registry-core-regressions-default-cost-v2-20260731.log`,
    69 tests, failure/error 0.
  - DP LM/L2SVM/PCA 및 derived-authority 회귀:
    `/tmp/g007-dp-lm-l2svm-pca-emission-regressions-20260731.log`,
    Maven return code 0.
  - REFED fail-closed/authority/rematerialization/duplicate-consumer integration 회귀:
    `/tmp/g007-refed-fallback-integration-regressions-20260731.log`,
    Maven return code 0.
  - 수정 전 commit의 stale fixed-count contract도 별도 worktree에서 동일하게 실패:
    `/tmp/g007-baseline-aggregate-receipt-test.log`.
- **잔여 이슈**:
  - checkstyle/RAT 포함 package, 새 immutable stage, 정확한 DP/LogReg/2-worker/LAN
    Docker runtime canary를 완료해야 한다.
  - canary 성공 후 기존 33개 row를 버리고, 새 root에서
    `DP → FedAll → Heuristic → MinST` 336 cells를 각 한 번씩 실행해야 한다.
- **잠재 회귀 위험**:
  - 동일 source slot의 action들이 다른 durable anchor를 가질 때 잘못 합쳐지면 authority가 손상될 수 있다.
    감지 방법: registry conflict 회귀와 transaction prevalidation/rollback 테스트를 유지한다.
  - raw memo closure와 actual traversal이 다른 경우 receipt category 수가 달라질 수 있다.
    감지 방법: 고정 개수가 아니라 exact category disjointness, analysis identity,
    normalized coverage 및 canonical ordering을 검증한다.
- **의사결정 근거/적용 원칙**:
  - 선택된 합법 relocation을 닫지 않고, planner가 비용화한 두 exact consumer obligation을
    runtime registry의 단일 source + consumer-set 표현으로 정확히 투영했다.
  - runtime fallback/repair, candidate-space 축소, TRead/TWrite `<CP,FOUT>` 완화,
    recompile `<CP,FOUT>` 허용은 하지 않았다.

## DP ALS runtime recompile이 ROW REFED 앵커 descriptor를 FULL로 변질

- **상태**: 해결 — 구조적 수정/회귀/package/정확한 Docker canary GREEN, 새 336-cell campaign 실행중
- **환경/조건**:
  - 소스 기준 commit: `e1cdba1858ad0e8ff8bcf86ddc1ea25e057cb70f`
  - 실패 campaign:
    `/home/mchoi/g007-all-planners-refed-coalesced-e1cdba1-d60da24-20260731-v1`
  - 실패 cell:
    `planners/DP/cells/037-968a89ebaac9`
  - 플래너/워크로드: DP / ALS / `P2P2D` / 2 workers / LAN / private-aggregate
  - frozen data/seed: 기존 campaign manifest와 동일, ALS seed `1389632218`
  - 실행 경로: `run_LAN_docker.sh`만 사용
- **재현 절차**:
  - 기존 실패 cell과 동일한 rendered DML, metadata, config 및 seed로 Docker 진단 실행:
    `/home/mchoi/g007-dp-als-two-worker-debug-20260731-v2`
  - runtime symbol/FederationMap 추적을 추가한 동일 Docker 진단 실행:
    `/home/mchoi/g007-dp-als-two-worker-debug-20260731-v3`
  - 최소 RED 회귀:
    `mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.functions.federated.fedplanning.FederatedRefedPolicyTest#testRuntimeRecompileDerivedFedSiblingKeepsConcreteRowAnchorType test`
- **관측 증상**:
  - 최초 compile의 hop `998` REFED는
    `worker1:8001;worker2:8002;|0,25000;25000,50000;|ROW`였다.
  - runtime recompile에서는 같은 worker/range signature가 `|FULL`로 바뀌었다.
  - `FEDRefedInstruction`은 descriptor를 그대로 실행해 로컬 `50000x10` 항을 두 worker에
    전체 복제했고, worker-local `25000x10` 항과의 elementwise `+`에서
    `LibMatrixBincell: block sizes not matched`가 발생했다.
  - runtime symbol `W`의 실제 `FederationMap`은 `ROW`였으므로 runtime 지원 부족이나
    planner candidate 자체의 불법성이 아니었다.
- **원인 분석**:
  - runtime recompile은 planner의 ExecType/FOUT 결정을 복원한 뒤 lowering registry를 재구성한다.
  - 로컬 정규화 항 hop `998`의 FED parent sibling은 파생 FED 연산이었다.
    `buildAnchorKey`가 이 sibling에서 upstream fed-init signature를 찾았지만,
    descriptor 타입에는 concrete source `X`의 `ROW`가 아니라 파생 output hop의
    알려지지 않은 FType을 사용했다.
  - 알려지지 않은 타입은 `buildAnchorKeyFromSignature`에서 `FULL`로 보수 변환되어,
    실제 ROW FederationMap과 모순되는 descriptor가 만들어졌다.
- **해결 요약**:
  - 파생 hop에서 fed-init signature가 발견되면 이미 존재하는 runtime-federated input을
    탐색해 그 concrete source anchor key를 우선 사용한다.
  - typed concrete source가 없을 때만 기존 conservative fallback을 유지한다.
  - 따라서 planner의 선택이나 후보군을 바꾸지 않고, lowering descriptor가 실제
    FederationMap의 worker/range/FType authority를 보존한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedRefedPolicyTest.java`
  - `docs/SESSION_ISSUES_2026-07-31.md`
- **검증**:
  - 새 회귀는 수정 전 정확히 `expected ROW but was FULL`로 RED였다.
  - 수정 후 `FederatedRefedPolicyTest` 전체 GREEN.
  - 다음 관련 회귀 묶음 GREEN:
    `RecompileStatusFederatedPlacementTest`,
    `CampaignBG014DpPcaRefedLoweringRedTest`,
    `CampaignBG014DpL2SvmRefedSourceLoweringRedTest`,
    `CampaignBG014DpLogRegTransientForwardRedTest`,
    `FederationUtilsRefedReuseLayoutTest`.
  - `mvn -q -DskipTests package` return code 0.
  - 수정 commit: `1b204449bed1ee2e54458ca525ef3a7bdd2b244d`.
  - staged JAR SHA-256:
    `7fc2e32896402e5f21491e8b20cd445ae50783a18be741478f5fa9371244383e`.
  - 새 immutable stage:
    `/home/mchoi/g007-dp-als-refed-stage-1b20444-20260731-v1/g007-stage-70ba58221cecb1f50d45c9cdadeb6be406d6fdb487b8fbdf0d915a00bb00d286`.
  - 정확한 DP/ALS/2-worker/LAN Docker canary:
    `/home/mchoi/g007-dp-als-refed-canary-1b20444-d60da24-20260731-v1`.
    `execution_seconds=152.057206001`, semantic oracle GREEN
    (`objective_relative_error=1.499618323978053e-16`,
    `rowspace_projector_relative_error=0.0`), runtime scan의
    `error/fallback/resource_invalid/timeout=false`, coordinator/worker restart 0,
    teardown zero resources.
  - canary raw statistics에서 `fed_fed_refed=10`을 확인했다. production canary는
    compile mapping 진단 플래그 없이 실행했으므로 descriptor의 `ROW` 직접 검증은 위 최소
    회귀가 담당하고, 실제 Docker 성공/semantic equality가 잘못된 `FULL` lowering이 더는
    실행되지 않음을 검증한다.
  - 새 336-cell campaign의 과거 실패와 정확히 같은 37번째 cell
    `workers=2|planner=DP|workload=als|profile=lan`도 `execution_seconds=79.878206907`로
    GREEN. semantic oracle의 objective relative error는
    `1.499618323978053e-16`, rowspace projector relative error는 `0.0`이며,
    scan 4종 false, restart 0/0, teardown zero이다. 이어지는 WAN-light cell도
    `91.716257418`초로 통과해 campaign이 과거 실패 경계를 넘어 진행 중이다.
  - canary `152.057206001`초와 campaign LAN `79.878206907`초 차이는 동일 stage에서
    canary가 cache/network 준비를 선행한 warm-primary 실행 순서의 관측값이다. 전체 matrix
    완료 전에는 이 차이를 planner 성능 정렬의 근거로 사용하지 않는다.
- **잔여 이슈**:
  - 이 campaign은 이후 DP/PCA/4-worker/LAN 67번째 cell의 exact-occurrence 충돌로
    fail-closed했으며, 성공 row를 재사용하지 않고 아래 `b5e0c65` 새 campaign으로
    supersede했다.
  - 전체 성공 후 workload별 planner differentiation 및 허용 오차를 둔
    `MinST <= DP <= Heuristic, FedAll` 실행시간 정렬을 검증해야 한다.
- **잠재 회귀 위험**:
  - 여러 concrete source가 다른 worker pool/placement를 가지는 파생 hop에서 임의 anchor가
    선택되면 안 된다. 감지 방법: 기존 anchor compatibility/fail-closed 테스트와 새 runtime
    recompile descriptor 테스트를 함께 유지한다.
  - concrete source 탐색 비용이 큰 DAG에서 compile time에 영향을 줄 수 있다.
    감지 방법: 336-cell 결과의 compile time과 package 회귀를 이전 campaign과 비교한다.
- **의사결정 근거/적용 원칙**:
  - planner가 선택한 합법 ROW relocation을 닫지 않고 실제 FederationMap authority를
    lowering에 정확히 전달했다. runtime fallback, 부분 응답 채택, TRead/TWrite 규칙 완화,
    recompile CP/FOUT 허용, opcode candidate guard 추가는 하지 않았다.

## immutable stage 입력 경로와 hardlink mode가 provenance 검증을 중단

- **상태**: 해결
- **환경/조건**:
  - staging tool:
    `sigmod2021-exdra-p523/experiments/tools/stage_campaign.py`
  - 대상 SystemDS commit/JAR: `1b204449...` /
    `7fc2e32896402e5f21491e8b20cd445ae50783a18be741478f5fa9371244383e`
  - 기존 frozen data/reference 및 harness commit `d60da243...` 재사용
- **재현 절차**:
  - 이전 stage의 복사된 `references/`를 `--reference-root`로 사용하면
    `CP publisher contract identity diverged`.
  - 원본 content-addressed reference bundle을 사용하고 symlink인 source `target` 아래 JAR를
    직접 지정하면 `JAR path contains symlink component`.
  - regular artifact snapshot의 dependency hardlink를 `0444`로 chmod한 직후 원본 canonical
    reference stage validation은 `staged systemds tree diverged`.
- **관측 증상**:
  - evaluator migration은 reference bundle ID를 parent directory 이름으로 인증하므로,
    stage 안에 복사된 reference 경로는 원본 bundle ID를 보존하지 못했다.
  - staging은 경로 구성요소의 symlink를 fail-closed로 거부했다.
  - dependency snapshot은 원본과 같은 inode를 hardlink했으므로 snapshot 쪽 chmod가 링크 수
    46인 공유 inode 전체의 mode를 `0644 → 0444`로 바꿨다. 콘텐츠 SHA-256은 동일했지만
    lib/stage tree identity에는 mode가 포함되어 descriptor 검증이 중단됐다.
- **원인 분석**:
  - 첫 두 중단은 provenance/path safety 계약을 만족하지 않는 입력 경로 선택이었다.
  - 세 번째 중단은 hardlink가 콘텐츠뿐 아니라 inode metadata도 공유한다는 점을 무시한
    artifact 준비 명령 때문이었다.
- **해결 요약**:
  - 원본 bundle
    `g007-reference-bundle-fa7e7d8e.../references`를 사용해 승인된 migration identity를
    보존했다.
  - 현재 JAR와 316개 dependency를 symlink가 없는 regular artifact root에 고정했다.
  - descriptor의 `lib_tree_sha256`를 대상으로 mode를 가상 재계산해 전 파일 `0644`가 정확한
    원래 계약임을 증명한 뒤 공유 inode mode를 복원했다.
  - 원본 canonical reference stage와 published reference bundle을 재검증한 후 새 stage를
    생성했으며, 새 stage descriptor validation과 Docker canary가 모두 통과했다.
- **수정 파일**:
  - 소스 코드 수정 없음.
  - 실험 artifact:
    `/home/mchoi/g007-systemds-artifact-1b20444-20260731-v1`
  - 본 세션 이슈 문서만 갱신.
- **검증**:
  - 복원 후 lib tree SHA-256:
    `86c7af015f48e3a6035c907b1d4cb3396a505db2125fe612b8f86e2ebf00979d`.
  - canonical reference-free stage `3034c6ba...` validate GREEN.
  - published reference bundle validate GREEN.
  - 새 stage `70ba5822...` 생성/validate GREEN 및 위 DP ALS Docker canary GREEN.
- **잔여 이슈**:
  - campaign 진행 중 artifact/stage dependency inode에 chmod를 다시 적용하지 않는다.
- **잠재 회귀 위험**:
  - hardlink source나 어느 staged link에서든 mode/content를 변경하면 같은 inode를 공유하는
    여러 immutable descriptor가 동시에 깨질 수 있다. 감지 방법: 새 campaign/stage 전
    `stage_campaign.py validate`와 `lib_tree_sha256`를 확인한다.
- **의사결정 근거/적용 원칙**:
  - provenance·symlink·tree identity 검증을 완화하지 않고 정확한 content-addressed source와
    원래 inode mode를 복원했다. runtime/planner 동작이나 candidate space는 변경하지 않았다.

## DP PCA 4-worker에서 하나의 exact occurrence가 CP/LOUT와 FED/FOUT로 이중 선택

- **상태**: 해결 — 구조 수정/회귀/package/정확한 Docker canary GREEN, 새 336-cell campaign 실행중
- **환경/조건**:
  - 실패 소스 commit/JAR: `1b204449bed1ee2e54458ca525ef3a7bdd2b244d` /
    `7fc2e32896402e5f21491e8b20cd445ae50783a18be741478f5fa9371244383e`
  - 실패 campaign:
    `/home/mchoi/g007-all-planners-refed-anchor-1b20444-d60da24-20260731-v1`
  - 실패 cell:
    `planners/DP/cells/067-2208e5356221`
  - 플래너/워크로드: DP / PCA / 4 row-partition workers / LAN / private-aggregate
  - 고정 데이터/seed: campaign manifest의 PCA dataset 및 seed를 그대로 사용
  - 실행 경로: stage-local `run_LAN_docker.sh`만 사용, exact cell 1회
  - 수정 소스 commit/JAR: `b5e0c6534b31b495d35167e357fc61b3861e8821` /
    `c30681bbe2f5168a6ecb33491b032c5b5aa451340741e7573d9a7163ad2450aa`
- **재현 절차**:
  - campaign manifest 순서상 67번째 cell을 실행하면 compile 단계에서 중단한다.
  - 최소 Docker-equivalent RED 회귀:
    `mvn -q -Dskip.license.check=true -Dskip.spotless.check=true `
    `-Dtest=CampaignBG014DpPcaRefedLoweringRedTest#`
    `pcaDpFourRowPartitionsKeepOneExactSelectionPerOccurrence test`
  - RED 로그:
    `/tmp/g007-dp-pca-4worker-selection-red-20260731.log`.
- **관측 증상**:
  - `.builtinNS::m_pca`의 centered `X` hop `b(-)` 한 occurrence가 한 부모 경로에서는
    `CP/LOUT`, 다른 부모 경로에서는 `FED/FOUT/ROW`로 선택됐다.
  - rewrite는 한 emitted occurrence에 두 exact state를 적용할 수 없으므로
    `DP occurrence has disagreeing exact selections`로 정확히 fail-closed했다.
  - 기존 decision-map score는 output decision이 없는 hop에 대해 각 incoming edge의 output을
    독립적으로 상속했다. 따라서 두 raw `(hopID, output)` 경로는 각각 합법으로 계산됐지만,
    합친 forest는 실행 불가능했다.
  - 동일 fixture가 기본 비용 상수에서는 통과하고 Docker LAN 비용 상수에서만 재현되어,
    rand/seed/dataset/cache 문제가 아니라 비용 선택 뒤 구조 closure 누락임을 확인했다.
- **원인 분석**:
  - DP memo에는 LOUT/FOUT 두 합법 후보가 모두 존재했고, runtime capability나 oracle이 후보를
    잘못 닫은 문제는 아니었다.
  - `countIncompatibleDecisionMapPlans`가 required-output 좌표의 존재만 검사하고,
    서로 다른 root/parent traversal이 같은 `CompiledHopKey`에 어떤 exact
    `PlacementState`를 선택했는지는 합치지 않았다.
  - output map에 해당 original hop 결정이 누락되면 rewrite까지 내려간 뒤에야 identity 충돌을
    발견했으며, refinement는 이를 비용 비교 대상으로 다시 올릴 정보가 없었다.
- **해결 요약**:
  - decision-map 구조 점수 traversal에서 analysis-owned `CompiledHopKey`별 exact
    `SelectedDpState`를 identity 기준으로 합친다. 같은 occurrence의 exec/output/FType 또는
    derived bit가 다르면 구조적 incompatibility로 계산하고 original hop ID를 receipt로 남긴다.
  - 새 exact-occurrence refinement는 충돌 hop마다 `<LOUT,FOUT>` 두 output을 모두 명시적으로
    적용해 기존 complete-forest 구조 점수와 전체 비용으로 평가한다.
  - 구조 incompatibility를 실제로 줄이는 후보만 채택하고, 여러 합법 후보가 동일하게 구조를
    닫으면 기존 total-cost 비교로 선택한다. 각 채택은 incompatibility를 엄격히 줄이므로
    반복은 유한하며, 해결 후보가 없으면 기존 최종 executable guard가 fail-closed한다.
  - analysis가 없는 legacy synthetic memo unit fixture에는 occurrence identity가 존재하지 않으므로
    기존 좌표 기반 검증을 유지한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/`
    `FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/`
    `CampaignBG014DpPcaRefedLoweringRedTest.java`
  - `docs/SESSION_ISSUES_2026-07-31.md`
- **검증**:
  - 새 회귀는 수정 전 Docker와 동일한 centered-X exact-selection 예외로 RED였다.
  - 수정 후 exact 회귀 및 클래스 전체 GREEN:
    `/tmp/g007-dp-pca-4worker-selection-green-attempt3-20260731.log`,
    `/tmp/g007-dp-pca-regression-class-20260731.log`.
  - trace에서 충돌 hop `270`을 명시적 `LOUT`으로 닫아
    `incompatibleBefore=1`, `incompatibleAfter=0`임을 확인:
    `/tmp/g007-dp-pca-4worker-selection-trace-green-20260731.log`.
  - PCA/StepLM/LM/L2SVM/LogReg/disconnected/refed-policy 최종 인접 회귀 묶음 GREEN:
    `/tmp/g007-dp-exact-selection-final-regressions-20260731.log`.
  - checkstyle/RAT를 포함한 최종 package return code 0:
    `/tmp/g007-dp-exact-selection-final-package-20260731.log`.
  - 수정 commit: `b5e0c6534b31b495d35167e357fc61b3861e8821`.
  - 새 immutable stage:
    `/home/mchoi/g007-dp-pca-exact-stage-b5e0c65-20260731-v1/`
    `g007-stage-9b2137baa293710725b0d230eac246249c7d7c41507eb06950a323b7d65e934f`.
    stage descriptor file SHA-256는
    `69cf712d6cedc9458bd67dc75d8a9355cbd85f59f7472aa7357a756d6bfdbe3f`,
    internal descriptor SHA-256는
    `27933aa4e1215f849004873ba7cba41756ce168f18ded178ec91c5b9389bb96e`이다.
  - 정확한 DP/PCA/4-worker/LAN Docker canary:
    `/home/mchoi/g007-dp-pca-exact-canary-b5e0c65-d60da24-20260731-v1`.
    `execution_seconds=221.391120826`, `full_lifecycle_seconds=249.237654198`,
    projector relative error `0.0`, reconstruction-loss relative error
    `2.859380401866628e-16`, runtime scan 4종 false, restart 0/0, teardown zero로 GREEN이다.
    raw statistics에서 `fed_fed_refed=2`도 확인했다.
  - 과거 실패 campaign의 성공 row는 재사용하지 않았다. 새 zero-row campaign
    `/home/mchoi/g007-all-planners-exact-closure-b5e0c65-d60da24-20260731-v1`을
    2026-07-31T16:36:43Z에 시작했다. master manifest SHA-256는
    `cf6839ca8681390e2d0db2cd35eac6017f0b40bb13895e3a3f1df8fda2a4c3a4`,
    seed는 `2026072701`, 순서는 `DP → FedAll → Heuristic → MinST`, 각 exact cell은
    1회이고 retry/backfill은 없다. PID `2331044`에서 stage-local
    `run_LAN_docker.sh`만 사용해 실행 중이다.
  - 첫 campaign cell `workers=1|planner=DP|workload=kmeans|profile=lan`은 attempt 1에서
    `execution_seconds=92.004711617`, `full_lifecycle_seconds=112.57700064`로 GREEN이다.
    semantic oracle은 ARI `1.0`, SSE relative error `0.0`, full ID domain valid이며,
    scan 4종 false, restart 0/0, teardown zero이다. 이어서 두 번째 WAN-light cell이
    자동 실행되기 시작해 planner-major 순서도 실제 프로세스에서 확인했다.
- **잔여 이슈**:
  - 새 campaign의 336개 cell이 모두 runtime/semantic oracle을 통과하는지 감시한다.
    실패하면 해당 campaign은 immutable하게 보존하고 retry/backfill 없이 구조 원인을 분석한다.
  - 전체 성공 후 모든 workload에서 네 planner의 실제 plan 차이와 허용 오차를 둔
    `MinST <= DP <= Heuristic, FedAll` 실행시간 정렬을 검증한다.
- **잠재 회귀 위험**:
  - 여러 exact conflict를 차례로 닫을 때 앞선 선택이 뒤 conflict의 비용/합법성을 바꿀 수 있다.
    감지 방법: 매 단계 complete-forest score를 다시 계산하고 incompatibility가 엄격히 감소할
    때만 채택하며, 최종 guard와 multi-root/function/loop 회귀를 유지한다.
  - 동일 output이지만 서로 다른 exec/FType exact state가 충돌하는 경우 output-only map으로
    해결되지 않을 수 있다. 감지 방법: exact-state identity score는 이 경우도 계속 fail-closed하며,
    향후 발생 시 state 표현 확장으로 해결하고 candidate guard나 runtime fallback은 추가하지 않는다.
- **의사결정 근거/적용 원칙**:
  - 합법 후보를 닫지 않고 기존 DP의 비용 기반 철학으로 두 placement를 비교해 하나의 executable
    occurrence state를 명시했다. runtime fallback/repair, TRead/TWrite 규칙 완화,
    recompile `<CP,FOUT>` 허용, opcode별 skip/continue 가드는 추가하지 않았다.

## FedAll StepLM의 exact selector가 독립 placement 성분을 전역 Cartesian으로 열거

- **상태**: 진행중 — 첫 admissible-bound 수정의 실제 Docker 실패를 봉인했고, 성분별 exact
  구조 수정의 RED→GREEN 회귀/package까지 완료; 새 Docker canary 대기
- **환경/조건**:
  - 최초 실패 소스 commit/JAR: `b5e0c6534b31b495d35167e357fc61b3861e8821` /
    `c30681bbe2f5168a6ecb33491b032c5b5aa451340741e7573d9a7163ad2450aa`
  - 최초 실패 campaign:
    `/home/mchoi/g007-all-planners-exact-closure-b5e0c65-d60da24-20260731-v1`
  - 최초 실패 cell: `planners/FedAll/cells/019-1bdf672e0374`
  - 첫 bound 수정 commit/JAR: `5a804b0a539a95116d04540a2c3a6d94cf358c9b` /
    `7b4462c4fda72b5d359cf59fbba5c16eaf7ecd37e5d3efe07597c2acc1573260`
  - 첫 수정 canary:
    `/home/mchoi/g007-fedall-steplm-tie-canary-5a804b0-d60da24-20260731-v1`
  - 플래너/워크로드: FedAll / StepLM / 1 worker / LAN / private-aggregate / `mkl-fout`
  - 실행 경로: immutable stage의 `run_LAN_docker.sh`; 각 stage에서 exact cell attempt 1만 실행
- **재현 절차**:
  - 최초 campaign은 DP 84/84를 통과한 뒤 FedAll 18개 cell을 통과했고, 19번째 StepLM LAN
    cell에서 coordinator가 output/statistics 없이 20분 이상 CPU를 소비했다.
  - 최초 thread dump:
    `/tmp/g007-fedall-steplm-jstack-20260731T211444Z.txt`
    (SHA-256 `41a5678d0e0bb9035a44b46525e81c3b2c6bc1b90de381be1e315d2047c48d54`).
  - relocation/signature lower bound를 추가한 `5a804b0` stage로 동일 exact canary를 새 attempt 1로
    실행했지만 같은 compile 단계에서 다시 진행하지 못했다.
  - 두 번째 thread dump:
    `/tmp/g007-fedall-steplm-jstack-5a804b0-20260731T214216Z.txt`
    (SHA-256 `eba42e38caf3a8f3b486cfe0ca0314802e31624df06271a5416bba032a7aa8bf`).
  - 구조 결함의 bounded RED:
    `mvn -q -Dtest=org.apache.sysds.hops.fedplanner.placement.selector.`
    `ExactPlacementSelectorBranchAndBoundTest test`.
- **관측 증상**:
  - 두 dump 모두 main thread가 `ExactPlacementSelector.canStillBeLegal`과
    `ExactPlacementSelector$Search.enumerate`의 20단계 이상 재귀에 머물렀다. 상위 호출은
    `FedAllPlacementAdapter.select → FederatedPlannerFedAll.select`였고 runtime FED instruction은
    시작되지 않았다.
  - 최초 attempt는 1,435.666초, 첫 수정 canary는 520.678초 뒤 SIGTERM으로 실패를 봉인했다.
    두 response의 `semantic_oracle`은 output 부재의 후속 분류이고 최초 원인은 compile-time
    exact search 폭발이다. 첫 수정 canary는 `teardown_zero_resources=true`이다.
  - 첫 bound 수정은 17개 독립 FED/FOUT·relocation 동률 synthetic case를 2^17 전수 열거하지
    않게 만들었지만 실제 StepLM에는 충분하지 않았다.
  - 새 18-decision RED는 9개의 서로 독립적인 2-node CONJUNCTIVE 성분으로 구성되며, 기존 구현은
    각 성분의 합법 선택을 전역 곱으로 결합해 `exploredCount < 100` 계약을 실패했다.
- **원인 분석**:
  - 기존 branch-and-bound의 FED/FOUT upper bound는 아직 배치되지 않은 각 node가 서로 독립적으로
    최선 state를 취할 수 있다고 계산한다. SAME_PLACEMENT/SAME_FTYPE/CONJUNCTIVE 제약으로 그 상한이
    도달 불가능한 경우에도 전역 incumbent와 같아질 때까지 큰 subtree를 제거하지 못한다.
  - relocation/signature lower bound는 완전히 동률인 synthetic 공간에는 유효했지만, 여러 작은
    제약/relocation 성분의 조합에서는 각 성분의 합법 optimum을 전역 Cartesian으로 다시 열거했다.
  - FedAll objective나 candidate universe가 아니라 exact solver의 문제 분해 부재가 실제 원인이다.
- **해결 요약**:
  - candidate universe, legality, score 순서
    `(FED 수 → FOUT 수 → distinct relocation 최소 → normalized signature 최소)`는 그대로 유지했다.
  - 16개 초과 multi-alternative production graph는 실제 state 의존성으로 연결 성분을 만든다.
    selector가 합법성에 사용하는 SAME_PLACEMENT/SAME_FTYPE/CONJUNCTIVE edge와, 각 relocation의 모든
    decision source 및 decision obligation consumer를 같은 성분으로 연결한다.
  - 각 성분을 기존 branch-and-bound로 **정확히** 풀고 assignment를 병합한다. FED/FOUT 수와 서로
    겹치지 않는 relocation action 수는 성분별 가산이고, stable signature는 정렬된 성분 assignment의
    deterministic merge이므로 성분별 lexicographic optimum의 병합이 전역 optimum이다.
  - action participant가 둘 이상의 성분에 남으면 fail-closed하며, 병합 뒤 전체 graph legality와
    전체 relocation/score를 다시 계산한다. 따라서 잘못된 분해를 성공으로 인정하지 않는다.
  - 16개 이하 fixture는 종전 complete Cartesian enumeration을 유지해 독립 exhaustive oracle의
    assignment와 certificate counter를 그대로 비교한다.
  - 첫 수정의 admissible relocation lower bound와 optimistic signature lower bound도 성분 내부에서
    유지한다. 후보 skip/continue나 runtime 보정은 추가하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/ExactPlacementSelector.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/placement/selector/`
    `ExactPlacementSelectorBranchAndBoundTest.java`
  - 첫 수정에서 함께 변경:
    `src/test/java/org/apache/sysds/test/component/federated/placement/selector/`
    `ExactPlacementSelectorContractTest.java`
  - `docs/SESSION_ISSUES_2026-07-31.md`
- **검증**:
  - 첫 수정의 2개 회귀는 수정 전 각각 131,072개 search와 2^17 relocation tie 확장으로 RED였고,
    수정 후 정확한 assignment/relocation을 유지하면서 100개 미만 탐색으로 GREEN이었다.
  - 새 독립-성분 회귀는 성분 분해 전 `exploredCount < 100`에서 RED, 구조 수정 후 9 FED/FOUT,
    각 pair의 forbid-pair 합법성 및 stable CP-left/FED-right tie를 모두 확인하며 GREEN이다.
  - 현재 branch-and-bound class 4/4와 독립 selector oracle/FedAll exact adapter를 포함한 15 tests
    GREEN:
    `ExactPlacementSelectorBranchAndBoundTest`, `ExactPlacementSelectorContractTest`,
    `CampaignBFedAllExactAdapterContractTest`.
  - `git diff --check` GREEN, `mvn -q -DskipTests package` return code 0; 성분 수정 working-tree
    JAR SHA-256 `8b01a2310081030047a8b457993a7eb1c67d6b259064a5dc3b3364cadddd0dae`.
- **잔여 이슈**:
  - 성분 수정 commit과 새 immutable stage를 만든 뒤, 과거 실패와 정확히 같은
    FedAll/StepLM/1-worker/LAN Docker canary를 새 stage의 attempt 1로 실행해
    compile/runtime/semantic oracle을 확인한다.
  - canary 성공 후 zero-row 336-cell campaign을 새 경로에서 DP → FedAll → Heuristic → MinST로
    다시 시작한다. 모든 과거 DP/FedAll 성공 row는 backfill하지 않는다.
- **잠재 회귀 위험**:
  - relocation activation에 관여하는 source/consumer를 분해에서 누락하면 relocation 수를 성분별로
    잘못 최소화할 수 있다. 감지 방법: 모든 동일 value-version decision source와 obligation consumer를
    연결하고 action owner 단일성 assert, 병합 뒤 전체 `isRelocationActive` score 재계산을 유지한다.
  - 성분별 stable signature 최소화가 전역 tie를 바꿀 위험은 assignment key 정렬/병합 규칙이 바뀔
    때 생긴다. 감지 방법: production-size stable tie 회귀, insertion-order 반복 계약, 소형 독립
    exhaustive oracle 계약을 유지한다.
  - 실제 StepLM의 active dependency가 하나의 큰 성분이라면 이번 분해만으로 충분하지 않을 수 있다.
    감지 방법: 새 immutable stage의 동일 exact Docker canary 한 번으로 compile 종료 여부를 확인하고,
    실패 시 해당 attempt를 재사용하지 않고 새 thread dump를 근거로 같은 exact solver를 추가 개선한다.
- **의사결정 근거/적용 원칙**:
  - runtime capability나 candidate space를 축소하지 않고 exact objective의 독립성을 증명할 수 있는
    성분만 분해했다. runtime fallback/repair, TRead/TWrite 완화, recompile CP/FOUT 허용,
    opcode별 skip/continue는 추가하지 않았다.
