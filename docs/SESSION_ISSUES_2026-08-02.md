# Session issues — 2026-08-02

## Heuristic LM policy projection retained a direct-source placement removed from its selector graph

- **상태**: 해결 — 구조 수정·소스 회귀·package·동일 실패 셀 Docker canary 완료,
  no-duplicate Heuristic→MinST continuation 실행중
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`
  - 실패 binary commit/JAR:
    `056caca33fb4466df0770fd9944e9fa6b433d9ae` /
    `6cddc0e300b432ad07ac653bfa282ac9d8d332cef1cd722bd78dd94f43de83cc`
  - 실패 campaign:
    `/home/mchoi/g007-all-planners-als-runtime-refed-056caca-d60da24-20260801-v1`
  - 실패 cell:
    `workers=2|planner=Heuristic|workload=lm|profile=lan`
  - cell directory:
    `/home/mchoi/g007-all-planners-als-runtime-refed-056caca-d60da24-20260801-v1/planners/Heuristic/cells/028-0f6cd87200ab`
  - Docker-only `run_LAN_docker.sh`, `mkl-heuristic`, private-aggregate, seed `2026072701`,
    attempt `1`, retry 없음.
- **재현 절차**:
  - 위 cell의 `response.json`에 봉인된 stage-local `run_LAN_docker.sh` argv를 확인한다.
  - production-shape LM source regression:
    `mvn -q -Dtest=org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicPathwiseReentryTest#lmLocalPrefixProjectsDirectRelocationSourcesIntoTheFilteredGraph test`.
  - 실패 전 회귀에서는 `HeuristicPlacementAdapter.select(...)`가
    `NeutralPlacementGraph` 생성 중 동일 예외를 발생시킨다.
- **관측 증상**:
  - coordinator는 Hop/Lop 실행 전에 다음 planner exception으로 종료됐다:
    `java.lang.IllegalArgumentException: Relocation direct-source placement is absent from its source node`.
  - stack은 `NeutralPlacementGraph.validateReferences` → `HeuristicPlacementAdapter.select` →
    `FederatedPlannerFedHeuristic.select` 순서였다.
  - harness의 compact metric이 생성되지 않아 외부 response는 `failure_category=semantic_oracle`로
    기록됐지만, 실제 원인은 semantic mismatch가 아니라 위 compile-time planner exception이다.
  - return code `1`, fallback/resource-invalid/timeout scan은 false, teardown은 zero resources였다.
  - 실패 전 채택 가능한 exact 성공은 historical `121` + 신규 `74` = `195/336`이다:
    FedAll 신규 `47/47`, Heuristic 신규 `27/84`, MinST 신규 `0/84`.
    실패 셀은 성공 row에 포함되지 않았다.
  - 실패 response SHA-256:
    `eaece4f6fffd22cfbda30127ac390710773211c581b4a77e8497f55c063160b2`.
- **원인 분석**:
  1. common neutral graph의 relocation action은 source가 exact FED/FOUT 상태로 선택되면 업로드가
     필요 없다는 `directSourcePlacements` shortcut을 가진다.
  2. Heuristic의 `PATHWISE_REENTRY_POLICY_V2`는 demotion/local-prefix node에서 FOUT 후보를 제거해
     해당 prefix를 FED/LOUT으로 유지한다.
  3. adapter는 filtered node 목록을 만들면서 relocation action은 base graph 객체를 그대로 재사용했다.
     production-shape LM에서는 direct-source shortcut이 가리키는 FOUT 상태가 filtered source 후보
     집합에서 사라져 immutable graph reference invariant를 위반했다.
  4. 이 shortcut을 그대로 인정하면 존재하지 않는 선택 상태로 relocation을 생략하게 되고,
     relocation 자체를 삭제하면 실제 local→federated boundary와 비용을 숨기게 된다.
- **해결 요약**:
  - 먼저 production LM shape(`1000000 x 1050`, label `1000000 x 1`)의 builtin expansion으로 동일
    예외를 재현하는 회귀를 추가했다.
  - `HeuristicPlacementAdapter`가 filtered nodes를 만든 뒤 relocation action을 policy graph에 투영한다.
    action key와 exact obligations는 보존하고, `directSourcePlacements`만 동일 source value version의
    filtered legal-state 합집합에 실제로 남아 있는 상태로 제한한다.
  - 따라서 source가 local-prefix FED/LOUT으로 고정되면 존재하지 않는 FOUT bypass만 제거되고,
    필요한 relocation은 selector가 계속 선택·비용화·emit할 수 있다.
  - candidate-space 폐쇄, runtime fallback/repair, TRead/TWrite 완화, recompile `<CP,FOUT>` 허용은
    추가하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/HeuristicPlacementAdapter.java`
  - `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBHeuristicPathwiseReentryTest.java`
  - `docs/SESSION_ISSUES_2026-08-02.md`
- **검증**:
  - exact LM regression은 수정 전 동일 `IllegalArgumentException` RED, 수정 후 `1/1` GREEN:
    `/tmp/g007-heuristic-lm-direct-source-red-20260802.log`,
    `/tmp/g007-heuristic-lm-direct-source-green-20260802.log`.
  - exact LM + 기존 metadata policy + invocation receipt + real-vector policy 묶음 `10/10` GREEN:
    `/tmp/g007-heuristic-lm-projection-green-suites-20260802.log`.
  - Heuristic 전체 suite 비교:
    수정 전 baseline은 `28` tests에서 기존 assertion failure `5` + direct-source exception `2`였고,
    수정 후 `29` tests(새 LM 포함)에서 동일 assertion failure `5`만 남고 exception은 `0`이다.
    baseline: `/tmp/g007-heuristic-baseline-70c-20260802.log`,
    수정본: `/tmp/g007-heuristic-lm-policy-suites-20260802.log`.
  - neutral/upload/selector 묶음은 `59` tests 중 `58` GREEN이고, 남은
    `NeutralPlacementGraphUploadRelocationRedTest` assertion `1`은 수정 전 baseline에서도 동일하다:
    `/tmp/g007-heuristic-lm-neutral-selector-suites-20260802.log`,
    `/tmp/g007-upload-relocation-baseline-70c-20260802.log`.
  - checkstyle/RAT 포함 `mvn -q -DskipTests package` 성공:
    `/tmp/g007-heuristic-lm-projection-package-20260802-rerun.log`.
  - 수정 JAR SHA-256:
    `d4b5308325f6f885b294e30e9645f973e34b5bbaf8a885f249f01c0e111832c5`.
  - immutable Docker stage 검증 성공:
    `/home/mchoi/g007-heuristic-lm-relocation-stage-61a907f-20260802-v3/g007-stage-acb0f61eb4be3aabfd68f617ad6631c683a24a72c4950fa89dbf7026688c4ce0`.
    binary commit은 `61a907f07ff727c1438e07199f443db7f1e16032`, JAR은 위 SHA-256이며,
    harness commit `d60da243b22e3752183c37679013fde1232c9638`, data tree
    `0a7066c7dbb6964292d60820115b87f9368d3a6171bdc2dfbe1f5d599bf07e5f`, reference tree
    `edc847fd4f53efb04d0468c221311a9f590debd20fd8703c6cd9b980e30afe85`로 이전 실험과 동일하다.
    stage 전체에서 executable `run_LAN_docker.sh`는 정확히 1개, `run_LAN.sh`는 0개다.
  - 동일 실패 셀 Docker canary attempt `1` 성공:
    `/home/mchoi/g007-heuristic-lm-relocation-canary-61a907f-d60da24-20260802-v1`.
    response SHA-256은 `65e9b9dcc9283056ca74cc8090e624ecfb1149d4127033572298db06ca8130cb`,
    execution `112.441972074s`, full lifecycle `135.602050556s`, semantic oracle 통과,
    scan의 error/fallback/resource-invalid/timeout 전부 false, coordinator/worker restart `0/0`,
    teardown 이후 해당 Compose container/network `0/0`이다.
  - 이전 실패 campaign은 성공 `195/336`과 실패 경계를
    `CAMPAIGN_FAILED.json`으로 봉인했다. 실패 셀은 성공 집합에 포함하지 않았고 같은 binary로
    재시도하지 않았다.
  - canary를 포함한 exact 성공 `196`셀과 남은 Heuristic `56` + MinST `84`의 교집합이 0이고
    합집합이 canonical `336`셀임을 validate-only로 확인했다. continuation root는
    `/home/mchoi/g007-all-planners-heuristic-lm-relocation-61a907f-d60da24-20260802-v1`,
    campaign manifest hash는 `7bf182d39751c6dce0468bd1195643497134da0a26cdc80d13e8461162f0e052`다.
    `g007-remaining-61a907f-v1.service`와 300초 monitor
    `g007-monitor-61a907f-v1.service`를 기동했고, 첫 request는 이미 성공한 LAN 셀 다음의
    `workers=2|planner=Heuristic|workload=lm|profile=wan_light`다.
- **잔여 이슈**:
  - 현재 continuation의 남은 Heuristic `56` + MinST `84` = `140`셀을 각 attempt `1`, retry 없음으로
    끝까지 실행한다. 성공 row는 재실행하지 않고, 새 실패가 발생하면 해당 campaign을 다시 봉인한 뒤
    구조 원인을 수정한다.
  - 전 셀 성공 뒤 exact `336` unique-cell composite, oracle/fallback/restart/teardown,
    execution-time 정렬과 그래프를 검증한다.
- **잠재 회귀 위험**:
  - 동일 value version에 clone/CFG owner가 여러 개일 때 합집합 projection이 한 owner의 direct state를
    다른 owner에도 허용하는 것으로 오해될 수 있다. 감지 방법: graph의 기존 invariant와 동일하게
    source value version 전체의 legal-state 합집합만 사용하며, action key/consumer/obligation identity는
    변경하지 않는 회귀를 유지한다.
  - impossible shortcut 제거로 이전에 가려졌던 relocation이 추가 선택될 수 있다. 이는 source가 실제
    LOUT이고 consumer가 해당 required placement를 선택할 때 필요한 boundary이다. 감지 방법:
    selected relocation마다 exact obligation과 durable anchor가 존재하는지 emission receipt와 Docker
    instruction scan으로 검증한다.
  - 기존 Heuristic assertion failure `5`와 upload-relocation assertion `1`은 이번 변경 전에도 존재한다.
    감지 방법: baseline 로그와 수정본 로그를 함께 보존하고, 이번 변경으로 failure/error 수가 증가하면
    stage를 만들지 않는다.
- **의사결정 근거/적용 원칙**:
  - Heuristic의 정책 view가 node 후보만 투영하고 relocation shortcut은 base graph 그대로 둔 compiler
    경계 불일치를 수정했다. 존재하지 않는 상태를 인정하거나 runtime에서 보정하지 않고, planner가
    실제 선택 가능한 상태와 exact relocation 비용을 일치시켰다.

## Immutable stage 생성 입력의 provenance 경로 오류

- **상태**: 해결
- **환경/조건**:
  - stage builder: authenticated harness commit `d60da243b22e3752183c37679013fde1232c9638`의
    `tools/stage_campaign.py`
  - 실패 기록:
    `/home/mchoi/g007-heuristic-lm-relocation-stage-61a907f-20260802-v1/stage-create.stderr.log`,
    `/home/mchoi/g007-heuristic-lm-relocation-stage-61a907f-20260802-v2/stage-create.stderr.log`
  - Docker create 및 workload cell 실행 전 단계.
- **재현 절차**:
  - v1: 이전 immutable stage 내부에 복사된 `references/`를 `--reference-root`로 전달한다.
  - v2: source worktree의 symlink `target/SystemDS.jar`를 `--jar`로 전달한다.
- **관측 증상**:
  - v1은 `ERROR: CP publisher contract identity diverged`로 종료했다.
  - v2는 `ERROR: JAR path contains symlink component: .../target`로 종료했다.
  - 두 경우 모두 stage descriptor, Docker resource, cell request/response가 생성되지 않았다.
- **원인 분석**:
  - reference lifecycle replay는 publisher bundle ID까지 인증하므로 stage 안의 복사본 parent 이름을
    원본 publisher bundle로 사용할 수 없다.
  - source repo의 `target`는 canonical build target을 가리키는 symlink이고, stage builder는 lexical
    path component의 symlink를 fail-closed로 거부한다.
- **해결 요약**:
  - publisher lifecycle descriptor가 기록한 원본 reference bundle
    `g007-reference-bundle-fa7e7d8e.../references`를 사용했다.
  - 동일 JAR/lib bytes의 canonical 실경로 `/tmp/g007-bb30-fresh-target-20260727`를 사용했다.
  - 데이터, reference, JAR을 다시 만들거나 내용을 바꾸지 않았다.
- **수정 파일**: 소스 수정 없음. stage 생성 argv만 canonical provenance 경로로 바로잡았다.
- **검증**:
  - v3 stage descriptor 전체 검증, hardlink identity, Compose binding, read-only mode 검사가 성공했다.
  - systemds commit/JAR/harness/data/reference hash가 기대값과 정확히 일치한다.
  - v1/v2 root에는 실패 로그만 있으며 실험 결과로 채택하지 않는다.
- **잔여 이슈**: 없음.
- **잠재 회귀 위험**:
  - stage 복사본을 다시 publisher source로 전달하거나 symlink target을 argv에 넣으면 동일 gate에서
    중단된다. 감지 방법: builder 성공 전 cell 실행을 금지하고 `stage-validation.json`을 필수 확인한다.
- **의사결정 근거/적용 원칙**:
  - provenance gate를 완화하지 않고 정확한 원본/canonical 경로를 제공했다. 런타임·플래너 정책에는
    변화가 없다.
