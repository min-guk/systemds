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

- **상태**: 진행중 — 동일 CLI compile RED→GREEN 및 관련 회귀 완료, 새 JAR Docker runtime canary 대기
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`
  - 수정 전 commit: `78a8843bafcfbd4b3aae10919c356c4fd3ce536b`
  - 수정 전 JAR SHA-256:
    `dbf8a474eb6b85fd930e493947a45d18012d05199ca2c8c796a6aa88ee2045cb`
  - 수정 전 immutable stage:
    `/home/mchoi/g007-dp-l2svm-additional-root-stage-20260731-v1/g007-stage-53693458d2faf07bfdf8ebfb55a41dd9230b0990d50edb2095f2110aa00ad168`
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
- **잔여 이슈**:
  - 새 commit/JAR/immutable stage를 만든 뒤 동일 DP/L2SVM/2-worker Docker canary에서
    실제 `PREFETCH → fed_refed` runtime chain과 semantic oracle을 검증해야 한다.
  - canary가 성공해야만 fresh 336-cell campaign을
    `DP → FedAll → Heuristic → MinST` 순서로 각 cell 한 번씩 실행한다.
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
