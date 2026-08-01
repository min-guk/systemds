# Session issues — 2026-08-01

## FedAll L2SVM의 선택된 REFED source가 ternary-aggregate fusion으로 소실됨

- **상태**: 진행중 — exact CLI RED→GREEN, 유효한 broad 회귀 및 package 완료, Docker canary 대기
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
  - 새 immutable Docker canary 결과는 완료 즉시 이 항목에 추가한다.
- **잔여 이슈**:
  - 새 commit/JAR로 immutable stage를 만들고 정확히 한 번의
    FedAll/L2SVM/2-worker/LAN Docker canary를 통과해야 한다.
  - canary 성공 뒤 새 zero-row 336-cell campaign을 `DP → FedAll → Heuristic → MinST` 순서로
    각 cell 한 번씩 실행해야 한다. 과거 실패 campaign은 재시작/보충하지 않는다.
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
