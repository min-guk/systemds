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

- **상태**: 진행중 — 구조적 수정 및 회귀 테스트 GREEN, package/Docker canary 대기
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
- **잔여 이슈**:
  - checkstyle/RAT 포함 package build 후 새 immutable stage를 만들어야 한다.
  - 정확한 DP/ALS/2-worker/LAN Docker canary에서 REFED descriptor가 `ROW`이고
    runtime이 성공하는지 확인해야 한다.
  - canary 성공 후 기존 실패 campaign row를 재사용하지 않고 새 336-cell campaign을
    `DP → FedAll → Heuristic → MinST` 순서로 각 셀 한 번씩 실행한다.
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
