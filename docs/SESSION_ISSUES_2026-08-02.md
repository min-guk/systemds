# Session issues — 2026-08-02

## MinST LM 1-worker에서 worker-pool closure가 derived FED/FOUT을 누락

- **상태**: 해결 — 구조 수정·RED/GREEN 회귀·package·동일 실패 셀 Docker canary 완료,
  exact no-duplicate MinST continuation 준비
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`
  - 실패 binary commit/JAR:
    `005c16e54341734ee6cfffffcdf5912d7020bd97` /
    `54a82575b5611b469048999cfeaa61f912f81306a045f88544e9a548a39da5a6`
  - 실패 campaign:
    `/home/mchoi/g007-all-planners-minst-pca-upload-state-005c16e-d60da24-20260802-v1`
  - 실패 cell: `workers=1|planner=MinST|workload=lm|profile=lan`, Docker-only
    `run_LAN_docker.sh`, private-aggregate, seed `2026072701`, attempt `1`, retry 없음.
- **재현 절차**:
  - 위 campaign의 실패 response에 봉인된 stage-local `run_LAN_docker.sh` argv를 실행한다.
  - 소스 회귀:
    `mvn -q -DskipTests=false`
    `-Dtest=CampaignBR10MinStFTypeMembershipAuthorityRedTest#lmCgDerivedWorkerPoolClosesFedLoutToDerivedFedFout test`.
  - 정확한 fixture는 1-worker federated `50000 x 2100` X와 `50000 x 1` Y에
    `lm(X=X,y=Y,verbose=FALSE,tol=1e-9)`를 컴파일하고 builtin `lmCG.dml`의 `AggBinaryOp ba(+*) q`
    decision을 검사한다.
- **관측 증상**:
  - planner는 실행 전 `MINST_EXACT_SELECTED_STATE_NOT_LEGAL`로 fail-closed했다.
  - 선택 상태는 builtin `lmCG.dml`의 `q`에 대한 `exec=FED|output=FOUT`이었다.
  - 정확한 1-worker production shape에서 수정 전 legal membership은
    `CP/FOUT/BROADCAST`, `CP/LOUT`, `FED/LOUT/FULL`뿐이었다. 두 MinST component bit는 각각
    노출되어 Dinic이 `FED/FOUT` 조합을 선택할 수 있었지만 그 조합을 대표하는 exact candidate는 없었다.
  - 실패 response SHA-256은
    `489a9832d52776c4e535fd2f9fa8fa2113936d3819ec30632476a3f5fcf6dc0e`, raw coordinator
    SHA-256은 `85c05f0a1bd29096665eac8b85235586ffaba457d640333714940a18e6eb45eb`다.
  - campaign은 신규 PCA 2셀 성공 뒤 이 LM 실패 1회에서 봉인했다. `CAMPAIGN_FAILED.json` SHA-256은
    `df8023ff1b8b08c7eed54595fd4e3956ad092e3a54740010aec4697cd802d293`, teardown은 zero resources이며
    같은 binary로 재시도하지 않는다.
- **원인 분석**:
  1. 최초 candidate pass에서는 해당 `q`가 직접 FederationMap anchor를 소유하지 않아 direct FOUT
     후보를 만들지 못한다.
  2. `closeDerivedWorkerPoolMaterializationCandidates`는 exact predecessor chain에서 하나의 durable
     worker pool을 재귀적으로 증명한 뒤 local computation + upload인 `CP/FOUT`만 뒤늦게 추가했다.
  3. 같은 exact worker pool에서 native `FED/LOUT/FULL`로 계산한 뒤 `LOUT→FOUT`으로 재배치하는
     합법 경로는 추가하지 않았다. 따라서 legal membership이 MinST의 compute/output 두 bit에 대해
     닫혀 있지 않았고, cut은 표현 가능하지만 candidate authority가 없는 corner를 선택했다.
  4. selector의 fail-closed 검사는 정상적으로 이 불일치를 차단했다. selector 검사를 완화하거나
     runtime fallback을 넣는 것은 원인이 아니라 증상을 숨기므로 적용하지 않았다.
- **해결 요약**:
  - 기존 closure gate를 유지해, direct `CP/FOUT`이 없고 recompile/transient global legality를 통과하며
    모든 exact candidate가 동일한 하나의 durable anchor로 수렴하는 노드만 다룬다.
  - 각 exact candidate에서 native `FED/LOUT` emission이 정확히 하나이고 execution FType이 증명된
    경우, 기존 `CP/FOUT`과 함께 동일 materialization FType의 `FED/FOUT` candidate를 추가한다.
  - 새 `FED/FOUT` emission은 `derivedFedFout=true`와 원래 native `FED/LOUT`의 `executionFType`을 보존해
    `FED→LOUT→FOUT` 경로와 비용을 planner가 명시적으로 모델링한다.
  - native `FED/LOUT` authority가 없거나 여러 개로 모호하면 새 상태를 발명하지 않고 기존처럼
    fail-closed한다. candidate를 닫는 opcode 가드, runtime fallback/repair, TRead/TWrite 완화,
    recompile `<CP,FOUT>` 허용은 추가하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
  - `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBR10MinStFTypeMembershipAuthorityRedTest.java`
  - `docs/SESSION_ISSUES_2026-08-02.md`
- **검증**:
  - clean baseline `005c16e`의 격리된 실제 target build에서 새 production-shape 회귀가 정확히 RED:
    `/tmp/g007-minst-lm-derived-fout-red-005c16e-20260802.log`, SHA-256
    `34f7a11536a12c64793fe1d96c8895098923c14989090eb25a7e2757530a74c9`.
  - 수정 후 exact LM 회귀와 전체 `CampaignBR10MinStFTypeMembershipAuthorityRedTest`가 GREEN이다.
  - exact solver/selector, placement emission, PCA authority를 포함한 집중 묶음이 GREEN:
    `/tmp/g007-minst-lm-derived-fout-focused-green-20260802.log`, SHA-256
    `57a3966d0d23c2cc6f0852f670eddc5dd24093e60ad1f47f7a22ba28c712f4f2`.
  - broader impacted suite의 남은 세 실패는 clean baseline에서도 동일함을 별도 확인했다:
    `/tmp/g007-minst-lm-baseline-known-failures-005c16e-20260802.log`, SHA-256
    `508b2e1e6dc3840fbd12e7452b9cb8b594db8e0334cc31835bd4d14abf8a886c`.
    하나는 기존 upload-relocation assertion이고 두 개는 의도적 BG014 RED/mutation fixture다.
  - checkstyle/RAT 포함 `mvn -q -DskipTests package` 성공:
    `/tmp/g007-minst-lm-derived-fout-package-20260802.log`, SHA-256
    `1c79f730db8d289be1a2c4a11a1942890b424d6d407df7232062bf491af40c4e`.
  - 수정 commit/JAR은
    `823bc4bd3d7a0849610fd05b89b0ca4e6141b7c0` /
    `21edc924705f2ce81fd7d91a2ce945d2ffa339c540fa5aa3d30d8c14fb80d544`로 봉인했다.
    immutable stage는
    `/home/mchoi/g007-minst-lm-derived-fout-stage-823bc4b-20260802-v1/g007-stage-c9a1d7ac6078c4617ef82fc872f99e160b6e7d11e3e27a4abd0d1d9a47ea56e3`다.
    harness/data/reference/lib hash는 직전 실험과 동일하고, 실행 가능한 `run_LAN_docker.sh`는 1개,
    `run_LAN.sh`는 0개다. stage descriptor file SHA-256은
    `6ca7819e19f9ab05a546047a3d32e7e648832fdf94e2dd6b0c2c8a64a9567ce7`이다.
  - 동일 실패 LM 셀 Docker canary는 새 root에서 attempt `1`, retry 없음으로 성공했다:
    `/home/mchoi/g007-minst-lm-derived-fout-canary-823bc4b-d60da24-20260802-v1`.
    response SHA-256은 `89860fc19874cf1d11f3f21d87bdb075db2a174cd6ae27eb9cfc8dd93ae7d13e`,
    execution은 `129.321465679s`, full lifecycle은 `149.862389625s`다.
    semantic oracle 통과, scan의 error/fallback/resource-invalid/timeout 전부 false,
    coordinator/worker restart `0/0`, teardown 후 해당 Compose container/network `0/0`이다.
- **잔여 이슈**:
  - 이전 exact 성공 258셀과 새 canary를 합쳐 중복 0의 259셀 registry를 만들고,
    남은 MinST 77셀만 새 continuation root에서 실행한다. 성공 셀은 재실행하지 않는다.
  - 새 구조 실패가 발생하면 해당 campaign을 다시 봉인하고 동일 binary 재시도 없이 RED→GREEN으로
    수정한다. 전 셀 성공 후 exact 336-cell 및 execution-time 정렬/그래프를 감사한다.
- **잠재 회귀 위험**:
  - 한 candidate에 서로 다른 native `FED/LOUT` FType이 동시에 합법이면 어느 execution FType을 derived
    FOUT에 부여할지 모호하다. 감지 방법: `nativeFedLout.size() == 1`일 때만 추가하고 나머지는
    membership validation에서 fail-closed한다.
  - 미래에 compute/output 두 bit가 또 다른 합법 corner를 누락하면 cut이 exact membership 밖을 선택할
    수 있다. 감지 방법: selector의 `MINST_EXACT_SELECTED_STATE_NOT_LEGAL` 검사를 유지하고,
    production-shape membership 회귀를 추가한다. selector 검사를 완화하지 않는다.
  - closure 범위를 넓히면 direct FOUT을 이미 가진 노드의 후보/비용이 바뀔 수 있다. 감지 방법: 기존
    `CP/FOUT`-absent gate와 recompile/transient gate를 유지하고 집중 selector/PCA 회귀를 함께 실행한다.
- **의사결정 근거/적용 원칙**:
  - runtime이 지원하는 `FED→LOUT→FOUT` 경로를 planner의 exact candidate/비용 상태로 명시했다.
    합법 후보를 닫거나 runtime에서 보정하지 않았고, 선택 전에 실행 가능성과 anchor authority를
    증명한다는 최상위 원칙을 적용했다.

## MinST PCA의 derived FED/FOUT을 upload authority로 재사용하지 못함

- **상태**: 해결 — 구조 수정·회귀·package·동일 실패 셀 Docker canary 완료,
  이후 no-duplicate MinST continuation에서 PCA WAN 2셀 추가 성공
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`
  - 실패 binary commit: `0cbd0a913a846c52fe76487bd1cdc2872d5f07af`
  - 실패 stage:
    `/home/mchoi/g007-minst-pca-authority-stage-0cbd0a9-20260802-v2/g007-stage-8533e472199951ff3fe7fed25a4ecd7b8a2dfa03b5eb7c9a6ccac21ecd21ee69`
  - 실패 canary:
    `/home/mchoi/g007-minst-pca-authority-canary-0cbd0a9-d60da24-20260802-v1`
  - cell: `workers=1|planner=MinST|workload=pca|profile=lan`, Docker-only
    `run_LAN_docker.sh`, private-aggregate, seed `2026072701`, attempt `1`, retry 없음.
- **재현 절차**:
  - 위 canary의 `request.json`에 봉인된 stage-local `run_LAN_docker.sh` argv를 실행한다.
  - 소스 회귀:
    `mvn -q -DskipRat -Dcheckstyle.skip -Dspotless.check.skip=true`
    `-Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStPcaAuthorityClosureAndTWriteMetadataTest test`.
  - 정확한 harness shape는 1-worker federated `50000 x 2100` 입력에
    `[Xout,Mout]=pca(X=X,K=10); write(Mout,...)`이다.
- **관측 증상**:
  - Docker planner가 실행 전에
    `MINST_EXACT_OBLIGATION_AUTHORITY_MISSING`으로 fail-closed했다.
  - 실패 edge는 builtin PCA의 `AggUnaryOp:ua(meanC):Centering` →
    `ParameterizedBuiltinOp:REPLACE:Centering`, input `0`이다.
  - 선택 상태는 producer `FED/FOUT/FULL`, consumer `FED/FOUT/FULL`인데도
    upload authority `available=[]`로 판정됐다.
  - 실패 response SHA-256은
    `b209ca2933b76c466d7bed3560376944f9e47e4691b6326377446236537f986c`,
    raw coordinator SHA-256은
    `728a7e47e238bca1c00b8ac1a9331a2372c07af69ba69d616b3de081503941c`다.
  - campaign은 실패 1회에서 봉인했고 같은 binary로 재시도하지 않았다. teardown 뒤 해당 canary의
    Docker container/network는 0/0이었다.
- **원인 분석**:
  1. producer의 exact membership은
     `CP/FOUT/BROADCAST`, `CP/LOUT`, `FED/FOUT/FULL`이었다.
  2. 기존 upload OR 가격 edge는 producer에 exact durable anchor가 있을 때만 placement bit로 연결하고,
     그 외에는 sink로 연결했다.
  3. 이 모델은 derived `FED/FOUT/FULL`과 `CP/FOUT/BROADCAST`를 placement bit 하나로 구분할 수 없다.
     따라서 실제 FED 연산이 만든 정확한 FULL FOUT도 항상 새 upload가 필요한 것으로 비용화되었고,
     selector/projector가 존재하지 않는 relocation authority를 요구했다.
  4. 단순히 anchor gate를 완화하면 `CP/FOUT/BROADCAST`까지 동일한 FULL FOUT으로 오인하므로,
     문제는 후보 gate가 아니라 cut state 표현 부족이었다.
- **해결 요약**:
  - 각 upload group이 exact reusable producer membership의 불리언 predicate를 derivation 시점에 분류한다:
    `SINK`, producer compute, producer placement, 또는 exact `FED && FOUT`.
  - exact `FED && FOUT`은 별도 conjunction node와 두 hard implication edge로 그래프에 인코딩한다.
    따라서 upload 비용은 오직 exact reusable membership이 선택된 경우에만 제거된다.
  - selector와 projector는 더 이상 placement bit + 사후 anchor 추론을 사용하지 않고, group에 봉인된
    exact price target과 선택된 source partition으로 동일한 receipt 의미를 계산한다.
  - endpoint에 별도 exact anchor 요구가 없더라도 captured-rule/input-authority가 증명한 derived FOUT은
    재사용할 수 있다. 반대로 distinct anchor 요구가 있으면 기존처럼 incompatible로 fail-closed한다.
  - 새 conjunction graph에서 JGraphT `PushRelabelMFImpl`이 KMeans production-shape 테스트에서
    2분 이상 CPU를 소모하며 진행하지 않아, 동일 directed min-cut 목적함수와 residual extrema 규칙을
    유지하는 `DinicMFImpl`로 polynomial solver 구현만 교체했다. exhaustive parity 회귀로 목적값과
    inclusion-minimum/maximum cut 동등성을 고정했다.
  - candidate-space를 닫는 opcode 가드, runtime fallback/repair, TRead/TWrite 완화,
    recompile `<CP,FOUT>` 허용은 추가하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFacts.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactSelector.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactPlacementProjector.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStDiagnosticsProducer.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStPolynomialCutSolver.java`
  - 관련 exact facts/selector/PCA 회귀 테스트.
- **검증**:
  - 새 harness-shape 회귀는 수정 전 동일 authority-missing RED를 재현했다:
    `/tmp/g007-minst-pca-red-detail-20260802.log`, SHA-256
    `eb2b69d800d75b10a6100bf07889ec6f0a34e6beefbf2562bc4bdac5e27cf73e`.
  - PCA + exact facts 회귀 GREEN:
    `/tmp/g007-minst-pca-green-2-20260802.log`,
    `/tmp/g007-minst-pca-facts-green-2-20260802.log`.
  - 이전에 정지했던 KMeans production-shape 단일 테스트는 Dinic에서 `38.20s`, exit `0`:
    `/tmp/g007-minst-dinic-kmeans-20260802.log`, SHA-256
    `7b0ceda068b5acc08bb43f4aa2965b8b8bf0444db0b9e65df88382069f104a36`.
  - exact solver/selector/PCA/facts/diagnostics 묶음 GREEN:
    `/tmp/g007-minst-pca-final-focused-20260802.log`, SHA-256
    `57a3966d0d23c2cc6f0852f670eddc5dd24093e60ad1f47f7a22ba28c712f4f2`.
  - exhaustive와 Dinic의 unique/tie/conjunction 목적값 및 extrema parity GREEN:
    `/tmp/g007-minst-dinic-parity-20260802.log`.
  - broad selector 집합은 더 이상 정지하지 않고 `26.92s`에 종료했다. 현재 실패는 수정 전부터 알려진
    stale assertion/unsafe fixture 집합이며 PCA·exact facts·selector에는 신규 error가 없다:
    `/tmp/g007-minst-dinic-broad-20260802.log`. 기존 기준은
    `/tmp/g007-minst-exact-broad-green-20260802.log`의 71 tests / 6 known failures / 0 errors다.
  - checkstyle/RAT 포함 `mvn -q -DskipTests package` 성공:
    `/tmp/g007-minst-pca-final-package-20260802.log`, SHA-256
    `f388f7242112852a1d90145fac752b3c315876f2fb1263156a89197ee4db0004`.
- **잔여 이슈**:
  - PCA fix commit `005c16e54341734ee6cfffffcdf5912d7020bd97`의 동일 실패 셀 Docker canary는
    attempt `1`, retry 없음으로 성공했다. canary root는
    `/home/mchoi/g007-minst-pca-upload-state-canary-005c16e-d60da24-20260802-v1`, response SHA-256은
    `c09490109bedcc27da419908486d1ff580735d2e5f356ad55cb060292d652565`, execution은
    `194.197030914s`, full lifecycle은 `214.821493895s`다. semantic oracle/scan/restart/teardown을 모두 통과했다.
  - 이후 no-duplicate continuation에서 동일 binary의 1-worker PCA `wan_light`와 `wan_mid`도 각각
    `195.060929483s`, `202.334250429s`로 성공했다. PCA 관련 잔여 실패는 없다.
  - 전체 campaign의 잔여 작업은 뒤이어 발견된 MinST LM 구조 실패를 해결한 뒤 exact 336 unique-cell,
    semantic/fallback/restart/teardown, execution-time 정렬과 그래프를 감사하는 것이다.
- **잠재 회귀 위험**:
  - 동일 compute/placement membership 안에 서로 다른 FType 재사용 가능성이 섞이면 현재 2-bit cut으로
    표현할 수 없다. 감지 방법: `MINST_UPLOAD_REUSE_PREDICATE_NOT_CUT_REPRESENTABLE`로 fail-closed하고,
    해당 경우 state 표현을 확장하지 후보를 닫지 않는다.
  - auxiliary/conjunction node가 많아져 max-flow 성능 또는 tie partition fingerprint가 달라질 수 있다.
    감지 방법: exhaustive parity 회귀, production KMeans 시간 제한, exact selected state/receipt 비교를
    유지한다. solver 이름 외에 목적함수·tie 규칙은 변경하지 않는다.
  - endpoint anchor가 비어 있는 derived FOUT 재사용은 반드시 captured rule 또는 relocation/durable
    membership authority가 먼저 증명되어야 한다. 감지 방법: membership representative 검증과 PCA
    production-shape 회귀를 유지한다.
- **의사결정 근거/적용 원칙**:
  - runtime이 실행 가능한 exact derived FED/FOUT을 planner 비용 그래프가 상태별로 표현하도록 수정했다.
    runtime fallback을 추가하거나 합법 후보를 닫지 않았고, planning 단계에서 비용과 authority를 함께
    증명한다는 최상위 원칙을 적용했다.

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

## MinST L2SVM에서 서로 다른 FULL 값 앵커의 동일 worker-pool 권한을 잃음

- **상태**: 해결 — planner 구조 수정·양/음성 소스 회귀·package·동일 실패 셀 Docker canary 완료,
  exact unfinished-only MinST continuation 실행중
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`
  - 수정 기준 commit: `c3d42ec58b19e1847505b69a65692c2d8181e02b`
  - 실패 binary commit: `823bc4bd3d7a0849610fd05b89b0ca4e6141b7c0`
  - 수정 binary commit/JAR:
    `5126afca83ca6bfa972755fa22f2ba5e8ebeab50` /
    `0865b63ffc59969e3fe2d8ed394f4ddbafa77d44dd7fd05c1f2a005564116531`
  - 실패 campaign:
    `/home/mchoi/g007-all-planners-minst-lm-derived-fout-823bc4b-d60da24-20260802-v1`
  - 실패 cell: `workers=1|planner=MinST|workload=l2svm|profile=lan`
  - cell directory:
    `/home/mchoi/g007-all-planners-minst-lm-derived-fout-823bc4b-d60da24-20260802-v1/planners/MinST/cells/010-6036d8b2cfe3`
  - Docker-only `run_LAN_docker.sh`, private-aggregate, seed/data `2026072701`, attempt `1`, retry 없음.
- **재현 절차**:
  - 위 cell의 `response.json`과 `raw_coordinator.log`를 확인한다. coordinator는 workload 실행 전
    MinST exact-facts 생성 단계에서 종료한다.
  - production-shape source RED:
    `mvn -q -DskipRat -Dcheckstyle.skip -Dspotless.check.skip=true -Dtest=CampaignBR10MinStFTypeMembershipAuthorityRedTest#l2svmStateDependentInputMaterializationRemainsExactlyCostable test`.
  - fixture는 X=`localhost:1234/X1` FULL, Y=`localhost:1234/Y1` FULL로 두 값의 exact range/placement
    identity는 다르지만 실제 worker endpoint는 동일하게 구성한다.
- **관측 증상**:
  - planner exception:
    `MINST_CONSUMER_LAYOUT_UNPROVEN|ambiguous-exact-membership-representatives`.
  - 실패 지점은 builtin L2SVM의 `g_old` (`AggBinaryOp ba(+*)`) input `1`이며, 동일 compute membership의
    대표 후보가 다음처럼 서로 다른 input materialization type을 보고했다.
    - derived `FED/FOUT/BROADCAST`: `[PRESENT FULL, ABSENT_LOCAL]`, relocation=`BROADCAST`
    - native `FED/LOUT/FULL`: `[PRESENT FULL, PRESENT FULL]`, input layout=`FULL`
  - 실패 response SHA-256:
    `af27979df9ce987ad7ce4e73def3b38181ff09ffede37909cda01321c7d6b107`.
  - campaign은 historical `259` + 신규 LM `2` = exact 성공 `261/336`에서 봉인했다. 실패 셀은 성공
    집합에 포함하지 않았고 재시도하지 않았다. `CAMPAIGN_FAILED.json` SHA-256은
    `b3a24407af0f73e8b909f5095b18943570d268524e2d9fd587f80ec32732d302`, stop 후 validation은
    `c72238c1c9823e3aa065018cfa6d07726628edd5efb3958f4e745486b143a092`이며 remaining은 `75`다.
- **원인 분석**:
  1. `WorkerPoolAnchorResolver.resolveCandidateInputs`는 모든 PRESENT input의 `DurableAnchorKey`를 exact
     equality로 교집합했다.
  2. `g_old` input0 X는 direct/derived 경로로 `fed-init:X/FULL`을 찾았지만, input1 Y는 이전 branch의
     TWrite에서 현재 TRead로 전달된 `LogicalTransientInputFact`였다. 이 fact의 inline anchor는 null이고,
     sourceWrite 경로를 따라가야 `fed-init:Y/FULL`을 찾을 수 있다.
  3. X/Y는 placement id와 matrix range가 달라 exact value anchor로는 당연히 다르지만,
     `FederationUtils.canonicalFederatedWorkerAddress` 기준으로 둘 다 `localhost:1234`의 동일 단일 worker
     pool이다. exact value identity와 worker-pool identity를 동일시한 교집합이 합법한 PRESENT 후보를
     누락했다.
  4. 업로드 후 PRESENT 후보를 못 찾자 sparse-domain pre-materialization 경로가 Y의 geometry만 보고
     `BROADCAST`를 선택했다. 그 결과 native FULL 입력 후보와 derived FOUT 후보의 MinST membership
     representative가 충돌했다.
- **해결 요약**:
  - 기존 exact anchor 교집합을 우선 유지한다. exact 결과가 비어 있을 때만 제한된
    `resolveSameFullWorkerPoolCandidateInputs` 증명을 수행한다.
  - 이 증명은 다음 조건을 모두 요구한다.
    1. PRESENT matrix input이 두 개 이상이고 모두 `FULL`이다.
    2. 적어도 한 input은 exact `LogicalTransientInputFact`의 targetRead→sourceWrite 경로를 실제로
       사용해야 한다.
    3. 각 input에서 exact durable anchor가 하나만 나와야 한다.
    4. 서로 다른 value anchor가 존재해야 하며, 모든 canonical worker endpoint 목록이 동일해야 한다.
  - 위 조건이 증명되면 candidate input 순서의 첫 exact anchor를 deterministic worker-pool 대표로
    사용한다. closure와 post-materialization 후보 해석이 같은 authority를 사용하므로 `[FULL,FULL]`
    후보에도 기존 derived FOUT이 유지되고 input1 relocation은 `FULL`로 비용화된다.
  - 다른 endpoint(`localhost:1235/Y1`)에서는 FULL worker-pool authority를 만들지 않는 음성 회귀를
    추가했다.
  - runtime fallback/repair, candidate-space skip/continue guard, TRead/TWrite 완화, recompile
    `<CP,FOUT>` 허용은 추가하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
  - `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBR10MinStFTypeMembershipAuthorityRedTest.java`
  - `docs/SESSION_ISSUES_2026-08-02.md`
- **검증**:
  - 수정 전 MinST exact-facts RED:
    `/tmp/g007-minst-l2svm-state-dependent-red-c3d42ec-20260802.log`, SHA-256
    `900ff9f00e231d508bde57d560fb9e02f02c94254affd78c86b8249c9a685be5`.
  - 더 좁은 relocation layout RED:
    `/tmp/g007-minst-l2svm-derived-input-layout-red-c3d42ec-20260802.log`, SHA-256
    `f513f05b452ae4c676d6cea01296659e5748f7b5f0ace19d87763886574ba965`.
  - 단일 양성 L2SVM 회귀 GREEN:
    `/tmp/g007-minst-l2svm-worker-pool-narrow-green-wip-20260802.log`, SHA-256
    `e9fce3994870afe90aee93d0f357fe6bd356cbb86a04c5b4ade8c09f216a28fb`.
  - 동일 endpoint 양성 + 다른 endpoint 음성 회귀를 포함한 BR10 전체 GREEN:
    `/tmp/g007-minst-l2svm-br10-positive-negative-green-wip-20260802.log`, SHA-256
    `bc4bff63fd498f553e7c4748dc57f0c40c43e0e8d5d8e2d6640081e12b85f88b`.
  - MinST PCA/selector/heavy-MM/forward-membership/CFG identity 집중 suite GREEN:
    `/tmp/g007-minst-l2svm-focused-green-wip2-20260802.log`, SHA-256
    `38590d2362e0523cd62a9f20e7f98c80fd844577dfd853e3ea8e22dc2f26c268`.
  - 원래 선택한 10-class/67-test suite는 수정본에서 `62` GREEN + 기존 failure `5`였다:
    `/tmp/g007-minst-l2svm-focused-baseline-parity-wip-20260802.log`, SHA-256
    `b1811c9e6cfb957cd2c6c829a5847ee796a4b0b49a141fdcc30e69f4994d7e78`.
    clean `c3d42ec58b` 임시 worktree에서도 동일 5개 method가 전부 실패했다:
    `/tmp/g007-c3d42ec-baseline-five-20260802.log`, SHA-256
    `33ac59e64dcd113ddb5469a59eb4fb2ab64e3ba77d97fca1cdda4af5350efc38`.
    따라서 이번 수정이 추가한 회귀 failure/error는 0이다.
  - checkstyle/RAT/compile을 포함한 `mvn -q -DskipTests package` 성공:
    `/tmp/g007-minst-l2svm-package-wip-20260802.log`, SHA-256
    `93c5a1f2231900312aff12ae9a0427294532bc79605199267c0a82887c831e70`.
  - 최종 정리 후 BR10 전체와 package를 다시 실행해 모두 성공했다:
    `/tmp/g007-minst-l2svm-br10-final-green-20260802.log` SHA-256
    `019d56727ca8abf8157221b9daaf58ad767d97d32f5dc8913e2fe6b562f861f9`,
    `/tmp/g007-minst-l2svm-package-final-20260802.log` SHA-256
    `d2e5b05f9989805d3b86bcbc5094a7aaaafed71ea2b5232f42e98719701bfab6`.
  - 수정본은 commit `5126afca83ca6bfa972755fa22f2ba5e8ebeab50`으로 봉인했고, real target에서
    생성한 JAR SHA-256은
    `0865b63ffc59969e3fe2d8ed394f4ddbafa77d44dd7fd05c1f2a005564116531`이다.
  - 새 immutable stage 검증 성공:
    `/home/mchoi/g007-minst-l2svm-worker-pool-stage-5126afc-20260802-v1/g007-stage-dc67db84ab37461fa33200541bde7e48625bf1d83662ab23b562a1484f34cee8`.
    harness commit은 `d60da243b22e3752183c37679013fde1232c9638`, data/reference tree는 각각
    `0a7066c7dbb6964292d60820115b87f9368d3a6171bdc2dfbe1f5d599bf07e5f` /
    `edc847fd4f53efb04d0468c221311a9f590debd20fd8703c6cd9b980e30afe85`로 기존 실험과
    동일하다. stage 전체에서 executable `run_LAN_docker.sh`는 정확히 `1`, `run_LAN.sh`는 `0`이다.
  - 동일 실패 셀 Docker canary를 새 root에서 attempt `1`, retry 없음으로 실행해 성공했다:
    `/home/mchoi/g007-minst-l2svm-worker-pool-canary-5126afc-d60da24-20260802-v1`.
    response SHA-256은 `88878ed4409e3e80b4037232900664a6d766f6deb51120a93f61a4a65b78e452`,
    execution은 `71.020771038s`, full lifecycle은 `91.536015399s`다. semantic oracle 통과,
    scan의 error/fallback/resource-invalid/timeout 전부 false, coordinator/worker restart `0/0`,
    teardown 후 해당 Compose container/network `0/0`을 독립 확인했다.
  - 기존 exact 성공 `261`셀과 위 canary를 합친 성공 registry `262`셀, 남은 MinST `74`셀의
    overlap `0`, union `336`을 validate-only로 확인했다. 새 continuation root는
    `/home/mchoi/g007-all-planners-minst-l2svm-worker-pool-5126afc-d60da24-20260802-v1`,
    campaign manifest hash는
    `aef97f5532ced194b095d71dfe7889324e385e3bd2fa2c21d1548df3e228382a`다.
  - continuation은 user-systemd `g007-minst-5126afc-v1.service`를 `sg docker` 경계로 실행했고,
    `g007-monitor-5126afc-v1.service`가 120초 주기로 상태를 기록한다. 첫 unfinished 셀
    `workers=1|planner=MinST|workload=l2svm|profile=wan_light`은 attempt `1`에서
    `74.701532117s`로 성공했으며, semantic/fallback/restart/teardown 계약을 통과했다.
- **잔여 이슈**:
  - 위 fresh continuation에서 이미 성공한 `262`셀은 재실행하지 않고 남은 MinST `74`셀만 각
    attempt `1`, retry 없음으로 실행한다. 첫 셀은
    `workers=1|planner=MinST|workload=l2svm|profile=wan_light`이다.
  - 전 `336` unique cell 성공 뒤 semantic/fallback/restart/teardown, execution-time 정렬 및 그래프를
    최종 감사한다.
- **잠재 회귀 위험**:
  - 같은 host:port라도 분산 ROW/COL partition 또는 여러 worker 순서가 다른 값을 합치면 안 된다.
    감지 방법: 이 증명은 `FULL/FULL`에만 제한하고 canonical endpoint 목록 exact equality를 요구한다.
  - logical transient source가 여러 exact anchor를 내면 임의 선택하면 안 된다. 감지 방법:
    input별 anchor cardinality가 정확히 1이 아니면 기존 empty/exact 결과를 유지해 fail-closed한다.
  - 다른 endpoint를 같은 pool로 잘못 합치면 FULL relocation이 나타날 수 있다. 감지 방법:
    `l2svmDifferentFullWorkersDoNotInventSharedPoolAuthority` 음성 회귀를 유지한다.
  - clean HEAD에서 이미 실패하는 5개 assertion은 별도 기존 부채다. 감지 방법: baseline과 수정본의
    exact failure method 집합을 비교하고 신규 failure/error가 생기면 stage를 만들지 않는다.
- **의사결정 근거/적용 원칙**:
  - runtime이 실제로 공유하는 worker endpoint와 exact value/range identity를 planner state에서 분리해
    모델링했다. 합법 후보를 닫거나 runtime에서 보정하지 않고, planner가 업로드 전 정확한 transient
    provenance와 worker-pool identity를 증명해 비용에 반영한다는 최상위 원칙을 적용했다.
