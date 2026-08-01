# Session issues — 2026-08-01

## FedAll LogReg transient-read REFED가 downstream 동일명 TWrite 때문에 차단됨

- **상태**: 진행중 — 구조 수정 및 63개 타깃 회귀/package GREEN, 동일 실패 셀 Docker canary 대기
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`
  - 실패 production commit: `705b8dbb62f52bc98ceb4d0fd3a39405f8e581c0`
  - 실패 campaign:
    `/home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v6`
  - 실패 cell:
    `workers=2|planner=FedAll|workload=logreg|profile=lan`
  - cell directory:
    `/home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v6/planners/FedAll/cells/034-f298c274aba8`
  - Docker-only `run_LAN_docker.sh`, `mkl-fout`, private-aggregate, seed `2026072701`,
    attempt `1`, retry 없음
- **재현 절차**:
  - campaign cell의 `response.json`에 기록된 stage-local `run_LAN_docker.sh` argv를 실행한다.
  - source regression:
    `mvn -q -Dtest=org.apache.sysds.hops.fedplanner.fedAll.CampaignBG014FedAllLogRegTransientReadRelocationRedTest test`
  - RED 로그:
    `/tmp/g007-fedall-logreg-transient-relocation-cli-red-20260801.log`.
- **관측 증상**:
  - Docker cell은 wall `81.57284879684448s`, return code `1`로 종료됐다.
  - runtime-program 생성 중
    `fed_refed lowering cannot upload transient read of a FED/FOUT transient write for hop=191 label=Y`
    예외가 발생했다.
  - 실패 시점 누적 유효 결과는 DP `84/84`, FedAll `33/84`, 전체 `117/336`이며,
    oracle failure와 runtime fallback은 성공 row에서 모두 `0`이다.
- **원인 분석**:
  - FedAll은 builtin `multiLogReg` 내부 TRead `Y`(hop 191)를 CP/LOUT로 선택하고,
    FED/FOUT consumer로 보내기 위한 exact REFED relocation을 선택·비용화했다.
  - 이 TRead의 runtime symbol에는 durable federated anchor가 있어 기존 lowering이 명시적
    `PREFETCH(FED→LOUT) → fed_refed(LOUT→FOUT)` 경로를 만들 수 있다.
  - 그러나 `Dag.insertRefedLops()`가 현재 Lop batch의 모든 FED/FOUT transient write 이름을 모은 뒤,
    같은 이름의 TRead를 무조건 거부했다. 실제로 발견된 TWrite `Y`(hop 195)는 hop 191의 producer가
    아니라 그 값을 사용한 뒤 실행되는 downstream write이므로 이름만 같은 false positive였다.
  - candidate-space, 비용 모델, 데이터/seed 또는 runtime fallback 문제가 아니라, 이미 합법적으로
    선택된 relocation을 lowering의 name-only guard가 차단한 문제다.
- **해결 요약**:
  - producer 지배관계나 symbol version을 표현하지 못하는 name-only transient-write guard를 제거했다.
  - selected source가 실제 FED 값인지는 기존 `isFederatedMatrixLop()`가 exec/output placement와
    transient-read durable anchor를 기준으로 판단한다. FED이면 planner가 비용화한 명시적
    `FED→LOUT→FOUT`, local이면 직접 `LOUT→FOUT` lowering을 유지한다.
  - 기존 runtime-recompile StepLM 경로에서 dominating FED/FOUT TWrite가 실제 owner인 경우는
    `FederatedRefedPolicy.hasDominatingPlannedFederatedWrite()`가 중복 REFED 등록을 막는 기존 구조를
    그대로 보존했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/lops/compile/Dag.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedAll/CampaignBG014FedAllLogRegTransientReadRelocationRedTest.java`
  - `docs/SESSION_ISSUES_2026-08-01.md`
- **검증**:
  - synthetic downstream-same-name-TWrite RED→GREEN:
    `/tmp/g007-fedall-logreg-transient-lowering-unit-green-20260801.log`.
  - exact LogReg와 기존 StepLM owner regression `2/2` GREEN:
    `/tmp/g007-fedall-logreg-steplm-targeted-green-20260801.log`.
  - selected DAG REFED tests 12, `FederatedRefedPolicyTest` 47,
    `FederatedDagLocalMaterializeTest` 1, 이전 L2SVM exact regression 1이 모두 GREEN:
    `/tmp/g007-fedall-transient-relocation-broad-green-20260801.log`.
  - 총 63개 distinct 타깃 테스트가 failure/error `0`이다.
  - checkstyle/RAT를 포함한 `mvn -q -DskipTests package` 성공:
    `/tmp/g007-fedall-logreg-transient-package-20260801.log`.
  - package JAR SHA-256:
    `d694e41695c60b5f97f0986e83cf92874727300ec7cac9b9372b6c720c7d910e`.
- **잔여 이슈**:
  - 새 immutable stage에서 동일 실패 cell Docker canary를 통과시켜 compile뿐 아니라 실제 runtime,
    semantic oracle, fallback/restart/teardown을 검증해야 한다.
  - canary가 성공한 뒤 기존 성공 117셀을 제외한 새-binary continuation만 실행한다.
- **잠재 회귀 위험**:
  - 잘못 등록된 REFED가 실제 dominating FED/FOUT TWrite의 TRead에 남는다면 불필요한 download/upload가
    생길 수 있다. 기존 StepLM exact regression과 Docker coordinator instruction scan으로 감지한다.
  - transient-read anchor registry가 stale이면 FED source를 local로 오판할 수 있다. source/target anchor가
    다른 synthetic test와 exact LogReg/StepLM/L2SVM 회귀로 감지한다.
- **의사결정 근거/적용 원칙**:
  - candidate를 닫거나 runtime에서 보정하지 않고, planner가 선택·비용화한 exact relocation을
    anchor-aware lowering으로 그대로 실행한다. TRead/TWrite placement 규칙과 recompile `<CP,FOUT>` 금지는
    완화하지 않았다.

## FedAll L2SVM의 선택된 REFED source가 ternary-aggregate fusion으로 소실됨

- **상태**: 해결 — exact CLI RED→GREEN, 유효한 broad 회귀·package·동일 실패 셀 Docker canary 완료
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`
  - 실패 production commit: `7fac50ddfdfb5159ad97652dbbb3dcd51154eb38`
  - 실패 문서 HEAD: `d0e6446c9564dd9981b8dcc19f33a3e9624bb36f`
  - 실패 JAR SHA-256:
    `b21567c392883a126844ce8bf7b561d6f1de5f5734d89e904e41a72983db8d64`
  - immutable 실패 campaign:
    `/home/mchoi/g007-all-planners-tread-7fac50d-d60da24-20260731-v1`
  - 실패 cell:
    `workers=2|planner=FedAll|workload=l2svm|profile=lan`
  - cell directory:
    `/home/mchoi/g007-all-planners-tread-7fac50d-d60da24-20260731-v1/planners/FedAll/cells/031-1c6d8d0e3ea2`
  - stage:
    `/home/mchoi/g007-fedall-tread-stage-7fac50d-20260731-v1/g007-stage-ae3713c53aee682ae72f93e39e40e59bfdeef32e1820216c83c652b14a2f2456`
  - Docker-only, `mkl-fout`, private-aggregate, 고정 seed `2026072701`, attempt `1`, retry 없음
- **재현 절차**:
  - 실패 campaign의 `response.json`에 기록된 argv로 stage-local
    `harness/sigmod2021-exdra-p523/experiments/run_LAN_docker.sh`를 실행한다.
  - 핵심 인자는 `--phase-mode discovery --replicate 1 --workers 2 --net-profile lan
    --conf mkl-fout --salg l2svm --systemds-root <stage>/systemds
    --reference-manifest <stage>/references/manifest.json --continue-on-failure 0`이다.
  - 원본 coordinator log:
    `/home/mchoi/g007-fedall-tread-stage-7fac50d-20260731-v1/g007-stage-ae3713c53aee682ae72f93e39e40e59bfdeef32e1820216c83c652b14a2f2456/results/fed2/mkl-fout/l2svm_dataset-P2P2D_coordinator_mkl-fout_20260801_054926_194956_lan_coordinator1.log`
  - 소스 회귀:
    `mvn -q -Dtest=org.apache.sysds.hops.fedplanner.fedAll.CampaignBG014FedAllL2SvmRefedSourceLoweringRedTest test`
- **관측 증상**:
  - Docker cell은 wall `80.696204662323s`, return code `1`로 종료됐다.
  - 초기 runtime-program 생성 중 다음 예외가 발생했다.
    `org.apache.sysds.lops.LopsException: fed_refed lowering requires a local lop for hop=452`
  - 진단 시 누락된 logical source는
    `hop=452 scope=10 logical=b(^):Xd state=FED/FOUT lop=null`였고,
    입력은 TRead `Xd`와 literal `2`, 소비자는 multiply hop `453`, 가장 가까운 물리 parent는
    unary aggregate hop `186`의 `TernaryAggregate`였다.
  - 실패 뒤 결과 파일이 없어 semantic oracle도 연쇄 실패했지만, 일차 원인은 compile/lowering 실패다.
- **원인 분석**:
  - FedAll은 logical intermediate `Xd^2`에서 소비자 multiply로의 exact REFED relocation을
    선택하고 비용에 포함했으며 registry에 등록했다.
  - 이후 `AggUnaryOp`의 ternary-aggregate Lop rewrite가
    `Xd^2 → multiply → unary aggregate`를 단일 `TernaryAggregate`로 융합하면서
    hop `452`와 `453`의 물리 Lop을 모두 제거했다.
  - 따라서 planner-selected REFED boundary는 남았지만 그 source가 물리적으로 lowering될 수 없었다.
  - 이는 candidate-space, seed, 데이터셋, runtime fallback 문제가 아니라,
    선택·비용화된 executable placement boundary를 Lop fusion이 지운 구조적 결함이다.
- **해결 요약**:
  - `AggUnaryOp.isTernaryAggregateRewriteApplicable()`가 rewrite로 생략할 aggregate input 또는
    지원되는 nested `MULT`/`POW` intermediate에 REFED/FOUT/local-materialization registry entry가
    있으면 해당 fusion만 비활성화한다.
  - registry boundary가 없으면 기존 ternary-aggregate rewrite는 그대로 활성화된다.
  - planner의 후보군, 선택 결과, 비용 모델과 runtime 실행 의미는 바꾸지 않고,
    planner가 선택한 물리 이동 경계를 그대로 lower할 수 있게 보존한다.
  - 회귀 테스트는 동일 2-worker L2SVM compile 경로를 실행하고 성공 및
    runtime fallback/repair count `0/0`을 요구한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/AggUnaryOp.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedAll/CampaignBG014FedAllL2SvmRefedSourceLoweringRedTest.java`
  - `docs/SESSION_ISSUES_2026-08-01.md`
- **검증**:
  - exact FedAll L2SVM 회귀, 동일 DP 회귀, 기존 ternary aggregate 회귀를 한 JVM에서 순차 실행:
    `/tmp/g007-fedall-l2svm-targeted-20260801.log`.
  - 결과: `1 + 1 + 48 = 50` tests, failure/error `0`.
  - FedAll compile-only total compilation 약 `2.252s`, FedPlanner 약 `0.213s`.
  - DP compile-only total compilation 약 `2.877s`, FedPlanner 약 `0.864s`.
  - 기존 `ABATernaryAggregateTest` 48개가 모두 통과해 registry가 없는 일반 rewrite가 유지됨을 확인했다.
  - 유효한 FedAll/REFED/placement/selector/ternary 회귀 묶음:
    `/tmp/g007-fedall-l2svm-broad-green-20260801.log`, 총 `136/136` GREEN.
  - 진단용 legacy 전체 묶음:
    `/tmp/g007-fedall-l2svm-broad-20260801.log`.
    이 실행에서 `FederatedPlannerFallbackIntegrationTest`의 기존 기준선 실패 14건과
    `FederatedRefedFoutChildPlanningTest`의 권한 없는 수동 plan 주입 실패 1건을 분리했다.
    두 클래스는 각각 새 JVM에서도 같은 실패를 재현했다:
    `/tmp/g007-fallback-integration-isolated-20260801.log`,
    `/tmp/g007-refed-fout-child-isolated-20260801.log`.
    전자는 현재 최상위 규칙이 금지하는 `<CP,FOUT>` 기대 7건과 이미 제거된 내부 reflection/API
    기대를 포함하며, 2026-07-25/30 문서에도 unmodified HEAD 기준선 실패로 기록돼 있다.
    후자는 planner의 authoritative `PlacementAnalysis` 없이 Hop을 직접 강제한 뒤
    `FederatedRefedPolicy.registerFromProgram`을 호출하는 2026-01-04 방식이라 현재 transactional
    authority 계약에서 fail-closed한다. 두 legacy 테스트를 통과시키려고 production 규칙을 완화하지 않았다.
  - checkstyle/RAT 포함 package:
    `mvn -q -DskipTests package`, `/tmp/g007-fedall-l2svm-package-20260801.log`, return code `0`.
  - package JAR SHA-256:
    `19968f40e8b337eaedf6299c9485a8cce1bdd571620fa07bb6578449ae1353d8`.
  - immutable stage:
    `/home/mchoi/g007-fedall-l2svm-ternary-stage-705b8db-20260801-v2/g007-stage-0597ebfb0ffa034b659dfa055b9dd64fd5b1c9f671b7d8c6414257bd81b8ba40`.
    - SystemDS commit `705b8dbb62f52bc98ceb4d0fd3a39405f8e581c0`
    - JAR SHA-256 `19968f40e8b337eaedf6299c9485a8cce1bdd571620fa07bb6578449ae1353d8`
    - harness commit `d60da243b22e3752183c37679013fde1232c9638`
    - descriptor internal SHA-256 `9ed046583b1f2cf5ced5a830fa81d47d7cda2b942f1edb3a1b3536bbbb9f7748`
  - 동일 실패 셀 Docker canary:
    `/home/mchoi/g007-fedall-l2svm-ternary-canary-705b8db-d60da24-20260801-v1`.
    `execution_seconds=150.762147597`, semantic oracle PASS, scan의
    `error/fallback/resource_invalid/timeout=false`, coordinator/worker restart `0/0`,
    teardown zero resources를 확인했다.
  - canary coordinator log에는 `fed_fed_refed`가 `2491`회 기록되어, 수정 뒤 실제 FedAll 계획이
    REFED lowering을 수행하면서도 기존 `lop=null` 예외 없이 끝났음을 확인했다.
- **잔여 이슈**:
  - 구조적 결함과 동일 실패 셀 검증은 해결됐다.
  - 전체 범위 검증은 새 zero-row campaign
    `/home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v3`
    (manifest SHA-256 `9e151d703d7874ef18c17b708d39f73b9dace995e0f4253ca7598137874726be`)
    에서 `DP → FedAll → Heuristic → MinST`, 336셀, 셀당 1회, retry 없음으로 진행 중이다.
    이 campaign이 실패하면 재시작/보충하지 않고 새 root에서만 구조적으로 재검증한다.
- **잠재 회귀 위험**:
  - 선택된 materialization boundary가 있는 sum-product 식에서는 ternary fusion이 비활성화되어
    instruction shape와 성능이 달라진다. 이는 선택된 계획 경계를 실행하기 위한 의도된 변화다.
  - 누락된 다른 fusion pattern이 registry boundary를 지우면 동일 계열의 `lop=null` 오류가
    다른 workload에서 나타날 수 있다.
  - 감지 방법: exact CLI regression, 기존 ternary rewrite 회귀, planner/placement suite,
    Docker canary 및 전체 336-cell error/fallback/restart/teardown scan을 함께 확인한다.
- **의사결정 근거/적용 원칙**:
  - planner가 선택·비용화한 exact placement movement를 보존했다.
  - candidate-space 폐쇄, runtime fallback/repair, TRead/TWrite 제약 완화,
    recompile `<CP,FOUT>` 허용은 하지 않았다.


## CP reference lifecycle의 publisher contract와 현재 harness validator 불일치

- **상태**: 해결
- **환경/조건**:
  - 최초 실패 stage home:
    `/home/mchoi/g007-fedall-l2svm-ternary-stage-705b8db-20260801-v1`
  - 현재 harness commit: `d60da243b22e3752183c37679013fde1232c9638`
  - 재사용할 canonical reference bundle:
    `/home/mchoi/g007-campaign-stage-4426f23-e96b504-20260728/g007-reference-bundles/g007-reference-bundle-fa7e7d8ef9298e7f10c1f0e1f902dbc6d6dc95ca4d89136b6935c3ca65ab4312/references`
- **재현 절차**:
  - 현재 harness의 `tools/stage_campaign.py`를 호출하면서 위 reference를 검증한다.
  - stage builder 파일 자체의 SHA-256은 기존 publisher stage와 동일한
    `f850f1915f4289b2f0c20d414d167967c3433bef601fa74710e9a784de2cc881`이다.
- **관측 증상**:
  - stage 생성은 `ERROR: CP publisher contract identity diverged`로 중단됐다.
  - lifecycle descriptor가 봉인한 `cp_reference_lifecycle.py` SHA-256은
    `efda6ba6985dec664945067f9eb98153c177ca4f86b67326e991b31d500f6c85`이고,
    현재 harness 파일은 `ef1f4888551793975488d186e6be46aa3df04bbb02a4d9b4dff3e6ea1b876acb`였다.
- **원인 분석**:
  - reference bundle은 생성 시점의 publisher 구현과 계약 hash를 descriptor에 포함한다.
  - stage builder는 자신의 `tools` 경로에서 validator를 import하므로, 최신 evaluator migration을 포함한
    현재 validator를 사용하면 과거 publisher identity와 정확히 같지 않아 fail-closed했다.
  - copied stage의 `references` 디렉터리는 canonical content-addressed bundle parent/data sibling 계약도
    만족하지 않으므로 검증 입력으로 사용하면 안 된다.
- **해결 요약**:
  - 검증을 완화하거나 reference를 재생성하지 않았다.
  - descriptor가 요구하는 정확한 publisher validator를 포함한 원 publisher stage의
    byte-identical `stage_campaign.py`를 사용하고, 입력은 canonical content-addressed bundle의
    `references`와 인증된 data sibling을 사용했다.
  - builder가 export한 실제 campaign harness는 계속 현재 clean commit `d60da24`이며,
    SystemDS source/JAR도 현재 `705b8db`/`19968f…`로 독립 봉인됐다.
- **수정 파일**: production source 수정 없음. 실패/성공 stage artifact와 이 문서만 기록.
- **검증**:
  - `validate_published_bundle()`가 bundle id, descriptor, publisher hash, data inventory,
    reference payload inventory, hardlink identity를 모두 통과했다.
  - 새 stage `g007-stage-0597…`의 stage-local `stage_campaign.py validate`가 통과했다.
  - stage 안 `run_LAN_docker.sh`는 executable이고 `run_LAN.sh`는 0개다.
- **잔여 이슈**: 없음. 이후 stage는 성공한 `v2`만 사용한다.
- **잠재 회귀 위험**:
  - 다른 reference bundle에는 다른 publisher contract가 봉인될 수 있다.
  - 감지 방법: 반드시 canonical bundle root를 사용하고 lifecycle validator와 stage descriptor 검증을
    둘 다 통과시킨다. hash 불일치를 migration 없이 무시하지 않는다.
- **의사결정 근거/적용 원칙**: 동일 데이터/reference 재사용은 내용 주소와 publisher 계약을 모두
  인증해야 하며, 검증 우회나 불필요한 CP reference 재실행보다 봉인된 원 계약을 재현한다.

## 비-persistent launcher에서 336-cell campaign child가 첫 응답 전에 종료됨

- **상태**: 해결 — 실패 root 동결, 새 persistent tmux campaign 실행 중
- **환경/조건**:
  - 종료된 root:
    `/home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v2`
  - 후속 root:
    `/home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v3`
  - 동일 stage/JAR/harness/seed, planner order `DP → FedAll → Heuristic → MinST`.
- **재현 절차**:
  - 일반 command-exec 세션에서 `nohup run.sh &`로 child를 분리하고 command 세션을 종료한다.
- **관측 증상**:
  - launch receipt와 DP manifest, 첫 request는 생성됐지만 child process가 사라졌다.
  - 첫 cell runner stdout/stderr는 0 bytes, response/row/metric은 없고 campaign 소유 Docker resource도 0개였다.
- **원인 분석**:
  - campaign 코드 오류나 Docker 실패 로그는 없었다. command-exec 세션 종료와 함께 detached child가
    정리되는 실행 표면이므로, 장시간 작업의 lifetime을 보장하지 못했다.
- **해결 요약**:
  - 종료된 `v2`를 재실행/보충하지 않고 failure/correction receipt와 함께 동결했다.
  - 새 zero-row `v3`를 detached tmux session `g007_336_705b8db_v3`에서 시작했다.
  - v3 manifest는 336셀, attempt 1, retry NONE, Docker-only, 동일 stage identity를 다시 검증한다.
- **수정 파일**: production source 수정 없음. campaign control artifact와 이 문서만 기록.
- **검증**:
  - tmux pane → `run.sh` → `run_selected_discovery.py` → stage-local `run_LAN_docker.sh` process tree가 유지된다.
  - 첫 DP cell의 frozen data 검증 및 Docker coordinator/worker 기동을 확인했다.
- **잔여 이슈**: v3가 완료될 때까지 tmux/process, row cardinality, failure file, Docker teardown을 감시한다.
- **잠재 회귀 위험**:
  - tmux session 또는 host가 종료되면 해당 campaign도 실패한다.
  - 감지 방법: pane/process 존재, progress/rows 증가, cell response hash, residual Docker resource를 함께 확인한다.
- **의사결정 근거/적용 원칙**: 실패 campaign은 immutable하게 보존하고 backfill하지 않으며,
  실제 측정이 시작되지 않은 launcher failure도 새 manifest/root로만 재시도한다.

## tmux campaign이 외부 KeyboardInterrupt로 FedAll 셀 도중 종료됨

- **상태**: 운영 복구 완료 — 중단 root 동결 및 resource 정리 후 새 zero-row campaign 실행 중
- **환경/조건**:
  - 중단된 root:
    `/home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v3`
  - 대체 root:
    `/home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v4`
  - 동일 immutable stage/JAR/harness/seed와 `DP → FedAll → Heuristic → MinST` 순서.
  - Docker-only, 셀당 attempt 1, retry/backfill 없음.
- **재현 절차**:
  - v3 실행 중 command/tool turn을 외부에서 중단한다.
  - `planners/FedAll/launcher.stderr.log`와 현재 셀의 request/response 존재 여부를 확인한다.
- **관측 증상**:
  - DP는 84/84, FedAll은 11/84까지 정상 완료됐다.
  - 다음 셀 `workers=1|planner=FedAll|workload=l2svm|profile=wan_mid`는 request만 생성됐고
    response와 row는 생성되지 않았다.
  - FedAll launcher traceback은 stage-local `run_LAN_docker.sh`를 기다리는
    `subprocess.run(...)`에서 `KeyboardInterrupt`로 끝났다.
  - planner/runtime 실패 response는 없었고, 중단 시점 Docker coordinator/worker가 남아 있었다.
- **원인 분석**:
  - exact cell의 SystemDS/Docker 실패가 아니라 장시간 tmux process가 외부 interrupt 신호를 받은
    실행 lifetime 문제다. 따라서 완료된 95개 row를 새 campaign에 섞거나 현재 셀만 재시도하면
    one-pass campaign 계약을 위반한다.
- **해결 요약**:
  - v3에 additive `CAMPAIGN_FAILED.json`을 기록하고 재개/백필 불가로 동결했다.
  - 중단 셀의 Docker containers/network/volumes를 제거하고 zero-resource teardown을 확인했다.
  - 동일 입력과 binary identity로 완전히 빈 v4를 생성했다. v4 manifest SHA-256은
    `f7bbb0e3c68f97548b06a92509e8414aee353cd4e70ccbbee33596962347c1c8`이다.
  - command session과 lifetime을 분리하기 위해 v4를 user-systemd service
    `g007-336-705b8db-v4.service`로 실행했다. 플래너/런타임/실험 조건은 바꾸지 않았다.
- **수정 파일**: production source 수정 없음. 이 문서와 campaign control/evidence artifact만 추가.
- **검증**:
  - v3 failure descriptor는 DP 84, FedAll 11, 응답 없는 12번째 request와 traceback hash를 봉인한다.
  - v3 teardown 후 해당 project의 Docker resource가 0개다.
  - v4 launch 전 rows/requests/responses가 각각 0이고 `planners/`가 없음을 증명했다.
  - v4는 authenticated stage descriptor, JAR hash, stage-local `run_LAN_docker.sh`, campaign seed를
    다시 검증한 뒤 DP 첫 셀부터 실행을 시작했다.
- **잔여 이슈**: v4 336셀 완료/실패 여부를 10분 간격의 read-only 점검으로 감시한다.
- **잠재 회귀 위험**:
  - user-systemd manager 또는 host가 종료되면 service도 중단될 수 있다.
  - 감지 방법: service state, progress/row 증가, campaign exit-code, failure file, Docker resource를 함께 확인한다.
- **의사결정 근거/적용 원칙**: 외부 interrupt도 기존 결과에 이어 붙이지 않고 새 zero-row root로만
  대체한다. runtime fallback, planner 수정, 후보 축소, TRead/TWrite 완화는 하지 않았다.

## user-systemd launcher가 Docker 보조 그룹을 상속하지 않음

- **상태**: 해결 — 실패 root 동결 후 sg docker launcher 검증 완료
- **환경/조건**:
  - 실패 root: `/home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v4`
  - 대체 root: `/home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v5`
  - 실패 셀: `workers=1|planner=DP|workload=kmeans|profile=lan`
- **재현 절차**:
  - shell에서 Docker 접근이 되는 상태로 plain user-systemd service를 시작해 stage-local
    `run_LAN_docker.sh`를 실행한다.
- **관측 증상**:
  - v4 첫 셀은 51.35초 뒤 `failure_category=resource_invalid`, return code 1로 종료됐다.
  - stderr에는 Docker socket에 대한 `permission denied`가 반복됐고 response의
    `teardown_zero_resources`도 검증 불가로 false였다.
  - 외부 권한이 있는 shell에서 같은 compose project를 열거한 결과 실제 생성된 container/network/volume은 0개였다.
- **원인 분석**:
  - 현재 shell은 `groups=employees,docker`지만 user-systemd manager는 `groups=employees`만 보유했다.
  - 따라서 persistent launcher 선택은 맞았으나 Docker API에 접근할 보조 그룹이 service에 전달되지 않았다.
  - SystemDS planner/runtime은 시작되지 않았으므로 planner 결함이 아니다.
- **해결 요약**:
  - v4를 `CAMPAIGN_FAILED.json`과 함께 동결하고 재개/백필하지 않았다.
  - 새 v5 service는 frozen `service-wrapper.sh`를 `/usr/bin/sg docker -c`로 실행한다.
  - wrapper는 캠페인 전에 service identity와 Docker server version을 기록하고, 접근 실패 시 실험을 시작하지 않는다.
- **수정 파일**: production source 수정 없음. 이 문서와 v4/v5 campaign control artifact만 추가.
- **검증**:
  - 독립 transient service에서 `sg docker` identity가
    `gid=10001(docker) groups=10001(docker),5500(employees)`이고 Docker server `29.6.1` 접근에 성공했다.
  - v5 launch receipt가 같은 service identity/version, zero-row prelaunch proof, wrapper hash,
    campaign manifest `80361b628fc453d78d8e752bb61fab18ad635dc0f267399071409b1bc917a277`을 봉인한다.
  - v5는 동일 stage/JAR/harness/seed/order와 Docker-only runner로 DP 첫 셀부터 시작했다.
- **잔여 이슈**: v5는 이후 사용자 무중복 지시에 따라 중지·동결됐으며 결과에 채택하지 않는다.
- **잠재 회귀 위험**:
  - host의 Docker group/socket 정책이 바뀌면 preflight가 실패한다.
  - 감지 방법: 각 launch receipt에 service identity와 Docker API version을 필수로 남긴다.
- **의사결정 근거/적용 원칙**: launcher 권한 문제를 planner/runtime 변경으로 덮지 않고 실행 경계를 바로잡았다.
  실패 campaign 결과는 재사용하지 않았으며 retry/backfill/fallback도 추가하지 않았다.


## 이미 완료된 campaign 셀을 다시 실행한 v5 중지와 무중복 continuation 전환

- **상태**: 해결 — v5 동결/제외, 정확한 미실행 241셀 v6 실행 중
- **환경/조건**:
  - 채택할 기존 성공 root:
    /home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v3
  - 취소·제외 root:
    /home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v5
  - 무중복 continuation root:
    /home/mchoi/g007-all-planners-ternary-705b8db-d60da24-20260801-v6
  - 동일 SystemDS commit/JAR:
    705b8dbb62f52bc98ceb4d0fd3a39405f8e581c0 /
    19968f40e8b337eaedf6299c9485a8cce1bdd571620fa07bb6578449ae1353d8
  - 동일 harness/data/seed:
    d60da243b22e3752183c37679013fde1232c9638 /
    0a7066c7dbb6964292d60820115b87f9368d3a6171bdc2dfbe1f5d599bf07e5f /
    2026072701
  - Docker-only run_LAN_docker.sh, 셀당 1회, retry 없음.
- **재현 절차**:
  - v5 실행 중 planners/DP/rows.jsonl과 v3의 DP rows를 cell id로 비교한다.
  - v6의 control/prelaunch-no-duplicate-proof.json과 campaign-manifest.json을 검증한다.
  - 실행 service: systemctl --user show g007-remaining-705b8db-v6.service
- **관측 증상**:
  - v5는 v3에서 이미 성공한 DP 셀을 처음부터 다시 실행해 4개 성공 row를 만들었다.
  - 다섯 번째 DP 셀은 사용자 중지로 failure_category=process_exit response만 남았고
    durable row는 생성되지 않았다.
  - 이는 최신 사용자 지시인 “이전에 돌렸던 것은 중복 실행하지 않는다”와 불필요한 실험 시간 최소화에 어긋났다.
- **원인 분석**:
  - 이전 운영 정책이 외부 interrupt마다 새 zero-row 336 campaign을 강제해,
    동일 binary/data/seed로 이미 검증된 성공 셀까지 폐기하고 다시 실행했다.
  - planner/runtime 문제가 아니라 campaign provenance/재개 정책 문제였다.
- **해결 요약**:
  - v5 service를 중지하고 project-owned Docker container/network/volume이 0임을 확인했다.
  - v5에 immutable CAMPAIGN_CANCELLED.json을 추가했다. 성공 DP 4개는 v3와 중복이므로
    최종 evidence에서 명시적으로 제외했다.
  - v3의 response hash, semantic oracle, scan, metric을 다시 검증해 DP 84개와 FedAll 11개,
    총 95개만 authenticated historical completion registry로 봉인했다.
  - canonical 336 universe에서 이 95개를 정확히 뺀 241개만 v6에 동결했다:
    DP 0, FedAll 73, Heuristic 84, MinST 84. 교집합/누락/extra는 모두 0이다.
  - v6 runner는 기존 성공 response가 row append 직전에 남은 경우 실행 없이 row만 복구한다.
    request만 남거나 실패 response가 남은 경우에는 재실행하지 않고 fail-closed한다.
  - 최종 336 결과는 v3+v6 provenance를 셀별로 기록하며, 단일 연속 wall-clock campaign이라고
    표현하지 않는다.
- **수정 파일**:
  - production source 수정 없음.
  - v5: CAMPAIGN_CANCELLED.json
  - v6: campaign-manifest.json, base-completed.json, cells/*.json,
    bin/run_remaining_discovery.py, bin/finalize_composite.py, run.sh,
    bin/service-wrapper.sh, control/launch evidence.
  - 이 문서.
- **검증**:
  - v5 service inactive/dead, g007-sel-* Docker resource 0.
  - v5 성공 row 4개는 모두 v3 DP cell set의 부분집합이고 최종 composite 제외가 봉인됐다.
  - v6 prelaunch proof:
    historical 95, new 241, combined 336, intersection/missing/extra 0.
  - planner별 validate-only:
    FedAll 73, Heuristic 84, MinST 84, historical overlap 0.
  - v6 첫 request는 이미 완료된 DP나 FedAll 1~11이 아니라
    workers=1|planner=FedAll|workload=l2svm|profile=wan_mid 이다.
  - service identity는 gid=10001(docker)이며 Docker server 29.6.1 접근에 성공했다.
- **잔여 이슈**:
  - v6의 241개 신규 셀을 순서대로 완료해야 한다.
  - 완료 뒤 v3+v6 exact 336 unique-cell composite, oracle/fallback/restart/teardown,
    execution-time 정렬을 검증하고 그래프를 갱신해야 한다.
- **잠재 회귀 위험**:
  - 서로 다른 wall-clock session을 합치므로 host load/cache drift가 timing 분산에 영향을 줄 수 있다.
    감지 방법: 동일 stage/JAR/data/seed/network identity를 강제하고 provenance를 유지하며,
    timing은 stitched continuation임을 명시하고 이상치는 planner semantic/plan evidence와 분리해 분석한다.
  - service가 response/row 경계에서 중단될 수 있다.
    감지 방법: 성공 response는 hash 검증 후 실행 없이 복구하고, 그 외 orphan은 재실행하지 않고 실패시킨다.
- **의사결정 근거/적용 원칙**:
  - 최신 사용자 지시를 현재 실험 운영 정책으로 적용하되, 과거 결과를 무검증 backfill하지 않고
    동일 immutable identity의 성공 response만 hash/oracle/scan 검증 후 provenance와 함께 재사용한다.
  - planner/runtime/fallback/candidate-space/TR-TW/recompile 규칙은 변경하지 않았다.
