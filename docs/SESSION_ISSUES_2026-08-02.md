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

- **상태**: 해결 — planner 구조 수정·양/음성 소스 회귀·package·동일 실패 셀 Docker canary 완료.
  후속 unfinished-only continuation은 별도 StepLM planner 오류에서 봉인했으며 아래 이슈로 이어진다.
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
  - 이 수정의 Docker canary는 완료됐다. 후속 continuation은 신규 성공 `8`셀 뒤
    `workers=1|planner=MinST|workload=steplm|profile=lan`에서 다른 planner 오류로 중단·봉인했으며,
    새 binary에서 그 셀을 canary로 검증한 뒤 성공 `270`셀을 제외한 `66`셀만 이어간다.
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

## MinST StepLM local formal 입력에서 복수 exact FOUT layout을 임의의 단일 FType으로 축약함

- **상태**: 진행중 — planner 구조 수정, exact CLI RED/GREEN, 인접 회귀, package 및 동일 실패 셀
  Docker canary 검증 완료; 중복 없는 잔여 MinST `65`셀 실행 중
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`
  - 실패 binary commit/JAR: `5126afca83ca6bfa972755fa22f2ba5e8ebeab50` /
    `0865b63ffc59969e3fe2d8ed394f4ddbafa77d44dd7fd05c1f2a005564116531`
  - 실패 campaign:
    `/home/mchoi/g007-all-planners-minst-l2svm-worker-pool-5126afc-d60da24-20260802-v1`
  - 수정 commit/JAR: `e36339d3b7cfb01afce099bb32127e033504db29` /
    `3eca30ec8cd1a4ab7193a107c3a242b4e9ce56fd0ab5554c723eebdbfe455b19`
  - 수정 stage:
    `/home/mchoi/g007-minst-steplm-layout-stage-e36339d-20260802-v1/g007-stage-8aeaee8a7175bfc81535f41d19209f9dc70d1d1978b6642534badcf256bca66c`
  - continuation campaign:
    `/home/mchoi/g007-all-planners-minst-steplm-layout-e36339d-d60da24-20260802-v1`
  - 실패 cell:
    `workers=1|planner=MinST|workload=steplm|profile=lan`, attempt `1`, retry 없음
  - Docker-only `run_LAN_docker.sh`, private-aggregate, seed/data `2026072701`.
- **재현 절차**:
  - 실패 cell의 `response.json`과 `raw_coordinator.log`를 확인한다. runtime instruction 실행 전
    MinST exact-cost fact 생성에서 실패한다.
  - exact CLI RED:
    `mvn -q -Dcheckstyle.skip=true -Drat.skip=true -Dtest=CampaignBG014MinStStepLmFunctionSourceLayoutRedTest test`.
    회귀는 Docker와 동일한 50,000×2,100 feature, 50,000×1 label metadata, builtin `steplm`,
    `compile_min_st_cut`, compile-only, seed `2026072701`을 사용한다.
- **관측 증상**:
  - planner exception:
    `MINST_LOGICAL_FUNCTION_SOURCE_LAYOUT_AMBIGUOUS|...scripts/builtin/steplm.dml:98:44:...IndexingOp:rix:X_orig|types=[BROADCAST, FULL]|formal=null`.
  - 실패 response SHA-256:
    `182ef6be0ed3d76333f065ec2a909418506d4bbd5ed24225c1751686bd3470dc`.
  - continuation은 기존 exact 성공 `262`셀에 신규 MinST 성공 `8`셀을 더한 `270/336`에서 봉인했다.
    실패 셀은 성공 집합에 포함하지 않았고 같은 binary로 재시도하지 않았다.
    `CAMPAIGN_FAILED.json` SHA-256은
    `91520770242085fe083f740d6baacf7c4a3fa028443af1e1b3ad65927eda37d7`, stop 후 validation SHA-256은
    `b671c0bee2683eeacf60aad1e63f3a41d47346361bc31ff2a0ffa1a623304691`이며 remaining은 `66`이다.
- **원인 분석**:
  1. `MinStExactCostFactsProducer.addLogicalFunctionInputEdges`는 caller source가 FOUT이고 function formal이
     CP일 때 exact membership의 FType을 수집했다.
  2. StepLM의 `X_orig` source에는 합법한 exact `BROADCAST`와 `FULL` membership이 함께 존재한다.
  3. 코드는 formal의 단일 FType을 유도하지 못하면 위 ambiguity 예외를 던졌지만, 그렇게 유도한
     `sourceType`은 이후 edge cost나 상태 선택에 전혀 사용하지 않았다.
  4. 이 edge는 local formal TRead가 coordinator에 전체 논리 행렬을 materialize하는 download이다.
     따라서 source가 BROADCAST인지 partitioned FULL인지와 무관하게 layout이 소거되며 둘 다 동일한
     full-payload download 비용을 낸다. 비용 계산도 이미
     `getEffectiveTransientReadSourceMemEstimate(formalHop, sourceHop)`의 전체 bytes만 사용했다.
- **해결 요약**:
  - CP formal의 source에 exact FOUT membership이 하나 이상 있다는 증명은 유지했다.
  - 이후 사용되지 않는 단일 `sourceType` 추론과 ambiguity 예외만 제거했다.
  - local materialization이 federated layout을 소거하고 BROADCAST/FULL 모두 같은 전체 payload 비용을
    낸다는 이유를 코드 주석으로 명시했다.
  - 사용되지 않게 된 `workers` parameter를 해당 helper에서 제거했다.
  - runtime fallback/repair, candidate skip/continue guard, TRead/TWrite 완화, recompile `<CP,FOUT>` 허용,
    비용 우회는 추가하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014MinStStepLmFunctionSourceLayoutRedTest.java`
  - `docs/experiments/minst-continuation-2026-08-02-interim-270/*`
  - `docs/SESSION_ISSUES_2026-08-02.md`
- **검증**:
  - exact Docker-shape CLI RED:
    `/tmp/g007-minst-steplm-function-source-layout-cli-red-5126afc-20260802.log`, SHA-256
    `4f7f7848f33d7e117069a651e59497c9dbe7ce37c2fd0c21fde2be2f78137325`.
  - 구조 수정 후 동일 CLI GREEN:
    `/tmp/g007-minst-steplm-function-source-layout-green-20260802.log`, SHA-256
    `05cf6a0ec88498fe1772295ff797101b8a91dec6bbec9844ad034b18e0af126d`.
  - 최종 focused suite는 총 `30` test 중 `29` pass, 기존 조건부 `1` skip, failure/error `0`:
    `/tmp/g007-minst-steplm-focused-green-final-20260802.log`, SHA-256
    `cc63423c7fcb47256f00562ac681788439f7fad18c9d9a9151da82d4ed32f760`.
  - 처음 포함한 `CampaignBR7MinStExactPlacementProjectorTest`의 `ACTUAL-ROOT` capabilityReason assertion은
    수정본과 clean `e6e862b2fa` 임시 worktree에서 모두 같은 194줄로 실패했다. baseline 로그는
    `/tmp/g007-br7-baseline-e6e862.log`, SHA-256
    `1906a4b701773d5d7168d235e35b8342250e172f6fa755f7dbffa1edf8495be6`이므로 이번 수정의 신규 회귀가 아니다.
  - checkstyle/RAT/compile을 포함한 `mvn -q -DskipTests package` 성공:
    `/tmp/g007-minst-steplm-package-final-20260802.log`, SHA-256
    `b4c9ec1a8f7639957299c90faa849423e30efbc0ae54141fad7d2fab2a3c40fe`.
  - 중간 그래프/인증 CSV는
    `docs/experiments/minst-continuation-2026-08-02-interim-270/`에 생성했다. unique `270`, overlap `0`,
    DP/FedAll/Heuristic 각 `84`, MinST `18`, four-planner matched `18`이다. 현재 stitched binary 결과에서
    exact 정렬은 `0/18`, 5% tolerance 정렬은 `1/18`이지만 homogeneous final run이 아니므로 진단용이다.
  - real-target fresh build JAR SHA-256은
    `3eca30ec8cd1a4ab7193a107c3a242b4e9ce56fd0ab5554c723eebdbfe455b19`이며 package 로그
    `/tmp/g007-minst-steplm-package-real-target-e36339d-20260802.log` SHA-256은
    `a85888f9359b97deed468feba3ee0aae06adb78973fa9e184b0a64a7583bf333`이다.
  - 새 immutable stage는 executable `run_LAN_docker.sh`가 정확히 `1`개이고 `run_LAN.sh`는 `0`개다.
    stage descriptor file SHA-256은
    `63d3c129655e42a027fe37c2d0546f40989c778700e9ae7750c7dda54c296fd1`이다.
  - 동일 실패 셀 `workers=1|planner=MinST|workload=steplm|profile=lan`의 fresh Docker canary는
    attempt `1`, retry 없음으로 성공했다. execution `132.666106535s`, full lifecycle `153.313938249s`,
    semantic oracle pass, fallback 없음, restart `0/0`, teardown resource `0/0`이다. response SHA-256은
    `8febe37018e046d870d649a41e7558bf445cbd416838934adfa8e6f5cbe9315d`이다.
  - canary 승격 후 사전 검증은 historical `271`, planned `65`, overlap `0`, union `336`이다.
    validate-only receipt SHA-256은
    `00c3a0ff9d0a34a4e65db23e4fce72fb1af43f03851499bae331753e052538e7`이다.
  - continuation 첫 요청은
    `workers=1|planner=MinST|workload=steplm|profile=wan_light`, attempt `1`로 확인했다.
    launch receipt SHA-256은
    `576ce115947dd0c058df0587633f11116bbb666af4da9bf78edd466d4a779f9e`이다.
  - 최초 user-systemd 실행은 보조 `docker` 그룹이 상속되지 않아 셀 요청 전 종료됐다. request/response/
    cell attempt가 모두 `0`임을 봉인한 뒤, 기존 캠페인과 동일한 `sg docker` 경계로 실행했다. 이는 실험
    재시도가 아니며 증거는 continuation의 `control/infrastructure-preflight-failure-1/`에 있다.
- **잔여 이슈**:
  - StepLM 수정 binary는 canary와 continuation의 추가 `2`셀까지 성공했으나, 다음 KMeans LAN 셀에서
    별도 exact upload-authority 문제가 드러나 campaign을 `273/336`에서 동결했다. 아래 KMeans 이슈에서
    새 binary/canary/remaining-only continuation으로 이어간다.
  - 전체 성공 후 semantic/fallback/restart/teardown 감사와 최종 execution-time 정렬/그래프를 다시 만든다.
- **잠재 회귀 위험**:
  - CP formal이 전체 payload가 아니라 FType별 부분 payload를 읽는 새 runtime semantics를 얻으면 현재
    layout-independent 비용이 부정확해질 수 있다. 감지 방법: function input runtime instruction과
    transfer bytes를 비교하고, 그런 지원이 추가될 때 edge cost에 명시적 FType별 모델을 도입한다.
  - exact FOUT membership 자체가 비어 있는 source까지 허용하면 planner proof가 약화된다. 감지 방법:
    `!sourceFoutTypes.isEmpty()` 조건을 유지하고 exact CLI/authority 회귀에서 proof 부재를 계속 거부한다.
  - 기존 BR7 failure를 이번 변경의 회귀로 오인할 수 있다. 감지 방법: clean commit baseline의 동일 method/
    line failure hash와 수정본 failure 집합을 함께 비교한다.
- **의사결정 근거/적용 원칙**:
  - 합법 후보를 닫거나 runtime에서 복구하지 않고, local materialization이 실제로 소거하는 FType을 비용
    projection에서도 소거했다. 사용되지 않는 단일-layout 가정을 제거해 planner의 exact 상태 표현을
    runtime semantics와 맞춘 것이다.

## MinST KMeans의 파생 FED/FOUT worker-pool 계보가 upload reuse에서 소실됨

- **상태**: 해결 — planner 수정·focused/package·fresh Docker canary 및 후속 `8`셀 성공, 별도 L2SVM 이슈에서 continuation 동결
- **환경/조건**:
  - planner `MinST` (`compile_min_st_cut`), workload `kmeans`, workers `2`, profile `lan`.
  - Docker-only `run_LAN_docker.sh`, private-aggregate frozen P2P2D, seed/data `2026072701`.
  - 실패 campaign:
    `/home/mchoi/g007-all-planners-minst-steplm-layout-e36339d-d60da24-20260802-v1`.
- **재현 절차**:
  - Docker 실패 cell:
    `workers=2|planner=MinST|workload=kmeans|profile=lan`, attempt `1`, retry 없음.
  - 로컬 exact RED:
    `mvn -q -Dcheckstyle.skip=true -Drat.skip=true \
    -Dtest=CampaignBG014MinStKMeansGroupedUploadAuthorityRedTest test`.
  - 회귀는 Docker와 동일한 50,000x2,100, `k=50`, `runs=1`, `max_iter=60`,
    builtin `kmeans`, compile-only, LAN cost profile을 테스트 내부에서 고정한다.
- **관측 증상**:
  - planner exception:
    `MINST_EXACT_OBLIGATION_AUTHORITY_MISSING|direction=UPLOAD|...kmeans.dml:210:29:...b(+):D|consumer=...kmeans.dml:211:14:...ua(minR)|input=0|selectedProducer=FED/FOUT/ROW/...|selectedConsumer=FED/FOUT/ROW/...|available=[]`.
  - Docker response SHA-256:
    `5830bfc14fab96955746d93843a2a341a3668708e73fecd9457ef5df8aca682c`.
  - coordinator log SHA-256:
    `308ab07e38c2c0af424e202da9bfd70dda2d272d6af6d89b27ccb72f75ce1115`.
  - 실패 campaign은 base `271` + 신규 성공 `2` = `273`에서 봉인했고 remaining은 `63`이다.
    `CAMPAIGN_FAILED.json` SHA-256은
    `aeb05154e802086a7c44b2327797f3dfb31c5a75aee74b42935d2502f4ff0b1c`, stop 후 validation
    SHA-256은 `8d45acd41a1452642d84562593cfae8023c71f7db5c75cca7026e59e0e24f424`이다.
- **원인 분석**:
  1. KMeans의 `D = -2*(X %*% t(C)) + ...`는 exact captured-rule chain을 통해 원본 `X`의 ROW
     FederationMap에서 계산되어 `FED/FOUT/ROW`를 만든다.
  2. `D`는 `D <= rowMins(D)`의 input 0과 `rowMins(D)`의 input 0에서 함께 사용된다. 두 연산 모두
     같은 파생 worker pool을 그대로 사용할 수 있다.
  3. 기존 `exactUploadPriceTarget`은 producer Hop에 직접 붙은 `Node.anchors()`만 보았다. 파생 `D`에는
     직접 durable anchor가 없으므로, 이미 exact membership input authority가 증명한 worker-pool 계보를
     잃었다.
  4. 같은 upload group의 endpoint 중 binary sibling anchor가 있는 endpoint와 unary처럼 anchor 제약이
     없는 endpoint가 섞이면, `exactUploadAnchorCompatibility`는 빈 요구사항을 wildcard가 아닌 conflict로
     처리했다.
  5. 그 결과 이미 선택된 `D=FED/FOUT/ROW`를 재사용하지 않고 SINK upload를 가격화했으며, 실제 relocation
     action이 필요 없는 unary endpoint에서 transfer authority가 비어 selector가 fail-closed했다.
- **해결 요약**:
  - `MembershipRepresentative`의 exact input-authority DAG를 재귀적으로 따라가
    `DURABLE_ANCHOR`/`RELOCATION_SOURCE`까지 도달하는 단일 anchor 계보를 복원한다.
  - 단일 exact anchor로 수렴할 때만 해당 FOUT membership의 worker-pool reuse를 인정한다. 서로 다른
    anchor가 둘 이상이거나 순환/권한 공백이 있으면 기존처럼 unproven으로 남겨 같은-FType 추정은 하지 않는다.
  - group endpoint의 빈 anchor 요구는 constrained sibling이 선택한 동일 materialization을 받을 수 있는
    wildcard로 모델링한다. 모든 endpoint가 unconstrained이면 기존 `UNCONSTRAINED` 동작을 유지한다.
  - KMeans `D` group은 `PRODUCER_FED_FOUT`으로 정확히 가격화되고, 선택된 `FED/FOUT`에서는 중복 upload
    receipt를 만들지 않는다.
  - runtime fallback/repair, candidate skip/continue guard, TRead/TWrite 완화, recompile `<CP,FOUT>` 허용은
    추가하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014MinStKMeansGroupedUploadAuthorityRedTest.java`
  - `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBR10MinStFTypeMembershipAuthorityRedTest.java`
  - `docs/experiments/minst-continuation-2026-08-02-interim-274/*`
  - `docs/SESSION_ISSUES_2026-08-02.md`
- **검증**:
  - Docker-equivalent LAN cost RED:
    `/tmp/g007-minst-kmeans-lan-cost-red-20260802.log`, SHA-256
    `bc75f6ec69227cbf06291855d00b80f7851e67ff89b8149a5945d0333e0f98c5`.
  - self-contained exact CLI + structural GREEN:
    `/tmp/g007-minst-kmeans-derived-anchor-green-self-contained-20260802.log`, SHA-256
    `e7b681e6e7c311945390f0edc0e2f6f49be2564e9397473dd96a0fd662745424`.
  - KMeans/StepLM/heavy-MM/BR10/forward/selector/PCA focused GREEN:
    `/tmp/g007-minst-kmeans-derived-anchor-focused-green-final-20260802.log`, SHA-256
    `ee406b613c7c36002cdb537f4a53c69c8066d0d1a6760fad5785fef1e802ccc8`.
  - anchor/download/fingerprint fixture 6건은 수정 전 commit `93d91d6`에서도 동일하게 실패했다.
    baseline 로그 `/tmp/g007-baseline-93d91d6-anchor-audit.log`, SHA-256
    `81fda1fd4f507fa5ccc0e08db51f914c0c760143ecf365e3458e5c74d0d775f3`이므로 이번 변경의 신규 회귀가 아니다.
  - 수정은 commit `e4f6bad51de3212ac4641e97df33cf0a63a6f2fd`로 봉인했다. clean detached worktree에서
    real target package를 다시 만들고 별도 no-symlink artifact snapshot으로 옮긴 JAR SHA-256은
    `2f01c8511e4ac51d4d9475c367bbb3c0b766fd7b0d83ba8baf9ccbcb7613ff31`이다. package 로그 SHA-256은
    `a54bbe20a5c9377da6f1f24337bea0ce20d29e18ee7abb6443f4315cbc9c7c03`이다.
  - immutable stage
    `/home/mchoi/g007-minst-kmeans-derived-anchor-stage-e4f6bad-20260802-v1/g007-stage-9c6ac9593edd9ab30074778b7d990750ff2f6e8a925fb6a284bb80689c28854f`
    검증을 통과했다. data/reference tree는 각각
    `0a7066c7dbb6964292d60820115b87f9368d3a6171bdc2dfbe1f5d599bf07e5f` /
    `edc847fd4f53efb04d0468c221311a9f590debd20fd8703c6cd9b980e30afe85`, executable
    `run_LAN_docker.sh`는 정확히 `1`, `run_LAN.sh`는 `0`이다.
  - 동일 실패 셀 `workers=2|planner=MinST|workload=kmeans|profile=lan`을 fresh Docker canary attempt `1`,
    retry 없음으로 실행해 성공했다. execution `121.080435112s`, full lifecycle `144.39978494s`, semantic
    oracle pass, scan error/fallback/resource-invalid/timeout 전부 false, restart `0/0`, teardown resource
    `0/0`이다. response SHA-256은
    `0616c3220170a6222e0275cc2dab38149c08742e4c44d1e76c52c6ad6c28375f`이다.
  - 새 continuation root는
    `/home/mchoi/g007-all-planners-minst-kmeans-derived-anchor-e4f6bad-d60da24-20260802-v1`이다.
    독립 사전 감사에서 historical success `274`(DP/FedAll/Heuristic `84`씩, MinST `22`), remaining
    MinST `62`, overlap `0`, union `336`을 확인했고 274개 모두 attempt `1`, oracle pass, fallback 없음,
    restart `0/0`, teardown 성공으로 재검증했다. 감사 receipt SHA-256은
    `343bd853ddb5c56ef77dab9966f71e3703d214baf7b98280947e4394f7e66ecf`이다.
  - continuation은 첫 요청 `workers=2|planner=MinST|workload=kmeans|profile=wan_light`부터 KMeans WAN 2셀,
    PCA 3셀, LM 3셀을 모두 attempt `1`로 성공했다. 그 다음 L2SVM LAN 셀에서 별도 planner ambiguity가
    드러나 base `274` + 신규 `8` = `282/336`에서 즉시 동결했다. KMeans 수정 binary에서 KMeans 관련
    후속 실패는 없었다.
  - 실행 직전 274셀 그래프/인증 CSV는
    `docs/experiments/minst-continuation-2026-08-02-interim-274/`에 생성했다. four-planner matched `22`에서
    exact 정렬 `0/22`, 5% tolerance 정렬 `1/22`, median ratio(DP=1)는 FedAll `1.184`, Heuristic
    `1.182`, MinST `1.659`다. 여러 봉인 binary를 잇는 중간 진단값이므로 final 성능 근거가 아니다.
- **잔여 이슈**:
  - KMeans 자체의 잔여 문제는 관측되지 않았다. campaign 전체의 다음 중단 원인은 아래 L2SVM 이슈로 분리한다.
  - 전체 `336` 성공 후 semantic/fallback/restart/teardown 감사와 최종 execution-time 정렬/그래프를 만든다.
- **잠재 회귀 위험**:
  - 여러 FOUT input이 동일 FType이지만 서로 다른 worker/range anchor를 가질 때 하나로 오인하면 잘못된 reuse가
    생길 수 있다. 감지 방법: identity-distinct anchor가 정확히 하나일 때만 reuse하고 cross-anchor fixture와
    Docker semantic oracle을 유지한다.
  - 새 logical input 형태가 membership authority에는 present로 남지만 physical/logical edge proof가 없으면
    reuse가 보수적으로 거부될 수 있다. 감지 방법: `resolvedPositions`가 모든 present input을 덮지 못하면
    empty proof로 fail-closed하고 해당 경계를 별도 exact fact로 추가한다.
- **의사결정 근거/적용 원칙**:
  - runtime이 지원하는 후보를 닫지 않고, 기존 exact membership authority가 이미 가진 placement 메타데이터를
    upload 비용/재사용 모델까지 보존했다. 즉 비용 모델과 상태 표현을 고쳤으며 runtime fallback은 추가하지 않았다.

## MinST L2SVM의 동일 FED/LOUT membership에 여러 exact execution emission이 존재함

- **상태**: 해결 — compile ambiguity 구조 수정·exact/focused/package 검증 완료; fresh Docker canary에서 별도 runtime-plan 문제가 드러나 아래 이슈로 분리
- **환경/조건**:
  - planner `MinST` (`compile_min_st_cut`), workload `l2svm`, workers `2`, profile `lan`.
  - Docker-only `run_LAN_docker.sh`, private-aggregate frozen P2P2D, seed/data `2026072701`.
  - 실패 campaign:
    `/home/mchoi/g007-all-planners-minst-kmeans-derived-anchor-e4f6bad-d60da24-20260802-v1`.
  - 실패 cell:
    `workers=2|planner=MinST|workload=l2svm|profile=lan`, attempt `1`, retry 없음.
- **재현 절차**:
  - Docker 실패 로그:
    `planners/MinST/cells/031-dfeab00ca29d/phases/cell-1/discovery-correctness/raw_coordinator.log`.
  - Docker-shape exact CLI RED:
    `mvn -q -Dcheckstyle.skip=true -Drat.skip=true \
    -Dtest=CampaignBG014MinStL2SvmInternalEmissionCostRedTest test`.
  - 회귀는 Docker와 같은 50,000x2,100 feature, 50,000x1 label의 두 ROW worker, builtin `l2svm`,
    `maxIterations=30`, LAN cost profile, compile-only, seed `2026072701`을 사용한다.
- **관측 증상**:
  - planner exception:
    `MINST_EXACT_MEMBERSHIP_RULE_EMISSION_AMBIGUOUS|...l2svm.dml:120:18:...AggBinaryOp:ba(+*):out|membership=FED/LOUT|inputs=[PRESENT COL, PRESENT ROW]`.
  - 동일 exact input rule이 다음 세 runtime-supported emission을 게시했다.
    `FED/LOUT/COL/SHAPE_INDEPENDENT(exec COL)`,
    `FED/LOUT/COL/SHAPE_DEPENDENT(exec COL)`,
    `FED/LOUT/ROW/SHAPE_DEPENDENT(exec ROW)`.
  - Docker response/raw coordinator SHA-256은 각각
    `18da941de2e01aa02bd3a40e0902c3f80afe94047196c9b319fdf2a5fad57aca` /
    `2130888bff893023a00dd32ea34a3b58f020ea5ceed478c699efa80af2e1c1f5`이다.
  - 실패 campaign은 historical `274` + 신규 성공 `8` = `282/336`에서 봉인했고 remaining은 `54`다.
    같은 binary/cell은 재시도하지 않았다. `CAMPAIGN_FAILED.json` SHA-256은
    `277ff96c06139b932de2916bf54e5757739fc4fe522919200d6400c7a61d3a1b`, stop 후 validate-only
    receipt SHA-256은 `0a772d9017c35b4343ca90439188a4d9907868ec6b56b28076a9913704e64bf7`이다.
    service/container/network는 모두 종료돼 residual Docker resource가 `0`이다.
- **원인 분석**:
  1. neutral builder는 같은 `[COL, ROW]` 입력에서 runtime이 지원하는 COL/ROW aggregate-binary 실행 arm을
     모두 exact emission으로 보존한다. 이는 후보 중복이나 oracle 오류가 아니다.
  2. DP는 각 `CandidateEmissionFact`를 독립적으로 비용 계산해 같은 외부 placement 안에서도 비교한다.
  3. MinST의 현재 cut은 hop마다 exec bit와 output bit만 가지므로, `FED/LOUT` 안의 execution FType과
     shape-proof 차이를 별도 cut label로 표현하지 않는다.
  4. 기존 `capturedRuleRepresentative`는 이 coarse membership에 exact emission이 반드시 하나라고 가정해,
     합법한 세 arm을 비용 비교하기 전에 ambiguity로 중단했다.
  5. 이 cell의 세 arm은 exact input authority와 모든 cut boundary obligation이 동일하고 결과가 LOUT에서
     coordinator-local 값으로 materialize돼 내부 FType이 downstream에서 소거된다. 공유 FED cost model로
     계산한 `FED unary + local result` 비용도 세 arm 모두 `6.049673132863985`로 동일했다.
- **해결 요약**:
  - neutral graph와 DP의 세 후보는 그대로 유지한다. MinST에서만 외부에 동일한 `FED/LOUT` membership을
    나타내는 내부 arm을 완전 비용으로 비교해 dominated arm을 projection한다.
  - 안전 조건을 `FED/LOUT`, non-derived, exact execution FType 존재, 모든 matrix input이 이미 PRESENT인
    candidate row로 제한했다. FOUT 또는 local matrix input relocation이 섞인 경우는 비용 공유/배치가 달라질
    수 있으므로 임의 선택하지 않고 expanded cut state가 필요하다는 ambiguity를 계속 발생시킨다.
  - `addDecisionEdges`의 기존 FED unary/result-download 계산을 `fedCostProjection`으로 추출하고 내부 arm 비교도
    정확히 같은 함수로 계산한다. unit occurrence weight를 사용해 비교하며 모든 항이 동일한 양의 occurrence
    weight에 선형이므로 순위가 보존된다.
  - 비용 동률은 더 적은 shape 전제를 요구하는 exact arm을 우선하고, 그 뒤 canonical emission signature로
    결정한다. L2SVM target은 `COL/SHAPE_INDEPENDENT`를 선택했다.
  - runtime fallback/repair, opcode/candidate skip guard, TRead/TWrite 완화, recompile `<CP,FOUT>` 허용은
    추가하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014MinStL2SvmInternalEmissionCostRedTest.java`
  - `docs/experiments/minst-continuation-2026-08-02-interim-282/*`
  - `docs/SESSION_ISSUES_2026-08-02.md`
- **검증**:
  - exact Docker-shape CLI RED:
    `/tmp/g007-minst-l2svm-internal-emission-cli-red-e4f6bad-20260802.log`, SHA-256
    `d064e25f95d52b0341dc357df26a6f07d81d4eaf141a343ee0181b4338723453`.
  - 동일 CLI GREEN:
    `/tmp/g007-minst-l2svm-internal-emission-cli-green-20260802.log`, SHA-256
    `3dc7865cdcaaa9b712d45c9a788f7595b822fceca3e7bfb060010b0a0cc50af8`.
  - trace에서 세 exact arm 비용과 canonical 선택을 확인했다:
    `/tmp/g007-minst-l2svm-internal-emission-trace2-20260802.log`, SHA-256
    `5a7dd95cd9a12cf60bc5fb0ec7645a0f8790216e7ffb058df9583164f60d9066`.
  - L2SVM/KMeans/StepLM/heavy-MM/BR10/forward/selector/PCA focused suite `37/37` pass:
    `/tmp/g007-minst-l2svm-internal-emission-focused-green-final-20260802.log`, SHA-256
    `4a723bdf4a1fe7e440ce8e092d54241d71137cfd2560f09c4514a6455ba88586`.
  - 별도 `MinStDownloadAuthorityAmbiguityRedTest` 2건은 수정 전 `e4f6bad`에서도 동일하게 실패했다.
    baseline 로그 `/tmp/g007-baseline-e4f6bad-download-authority-20260802.log`, SHA-256
    `5c8b54d01562b9fba2db3972e8c4d027f0b54a5687d16875f8cd23a218c5567c`이므로 신규 회귀가 아니다.
  - checkstyle/RAT/compile을 포함한 `mvn -q -DskipTests package` 성공:
    `/tmp/g007-minst-l2svm-internal-emission-package-20260802.log`, SHA-256
    `34a7ef59ff6b4ea2fe03b908d4cbc2d8f120afa3609fbccd73166635f62bded9`.
  - 인증된 중간 결과/그래프는 `docs/experiments/minst-continuation-2026-08-02-interim-282/`에 고정했다.
    unique `282`, overlap `0`, DP/FedAll/Heuristic 각 `84`, MinST `30`, four-planner matched `30`이다.
    exact 정렬은 `0/30`, 5% tolerance 정렬은 `4/30`, median ratio(DP=1)는 FedAll `1.417`, Heuristic
    `1.395`, MinST `1.509`이며 여러 binary를 잇는 불완전 진단값이므로 final 성능 근거가 아니다.
- **잔여 이슈**:
  - compile ambiguity는 해소됐다. fresh Docker canary는 compile을 통과했지만 아래의 loop-invariant transpose
    반복 재물질화 문제로 실패했으므로, 이 binary/cell은 봉인하고 재시도하지 않는다.
- **잠재 회귀 위험**:
  - local input relocation이 포함된 arm을 같은 방식으로 축약하면 공유 upload 비용을 잘못 국소 최적화할 수 있다.
    감지 방법: 모든 matrix input의 exact PRESENT 검사를 유지하고, 해당 형태는 expanded cut 모델 없이 통과시키지 않는다.
  - FOUT arm을 축약하면 downstream worker/range placement가 달라질 수 있다. 감지 방법: reduction을 FED/LOUT로만
    제한하고 non-local membership ambiguity 회귀를 유지한다.
  - cost helper 추출이 기존 MinST edge 가격을 바꾸면 기존 계획이 변할 수 있다. 감지 방법: BR10의 exact edge
    contribution assertions와 focused suite, Docker semantic oracle을 계속 실행한다.
- **의사결정 근거/적용 원칙**:
  - runtime-supported 후보를 닫지 않고 neutral/DP 후보군을 보존했다. MinST cut에서 downstream에 관측되지 않는
    내부 실행 arm만 동일 공유 비용 모델로 부분 최소화했으며, 전역 배치 비용이 섞이는 경우는 fail-closed한다.

## MinST L2SVM loop-invariant transpose를 매 반복마다 재물질화함

- **상태**: 해결 — production 구조 수정·focused/package·fresh immutable Docker canary 검증 완료; unfinished-only continuation 진행중
- **환경/조건**:
  - 실패 binary source commit `e79250e304d93975f705806a1985b52336011398`, clean packaged JAR SHA-256
    `a8a21713b968eda8dfb44c85a25e6aa9272e1fdacbfd51a0660cafc1518ed0a1`.
  - planner `MinST`, workload `l2svm`, workers `2`, profile `lan`, seed/data `2026072701`.
  - Docker-only 실패 stage:
    `/home/mchoi/g007-minst-l2svm-internal-emission-stage-e79250e-20260802-v1/g007-stage-d275e27e99b6e21f7b091d9ffde09556aff962811f1e7d4d1dec2dd7e2ae260d`.
  - one-attempt 실패 canary root:
    `/home/mchoi/g007-minst-l2svm-internal-emission-canary-e79250e-d60da24-20260802-v1`.
  - 수정 source commit `e18d326b3f091fdf3433e5347001f580077e6d79`, clean JAR SHA-256
    `ed315ef65656861a6d8e2b8b2ccd83895120e3ac0f9091beb7ff17ccc6ab123f`.
  - 수정 immutable stage:
    `/home/mchoi/g007-minst-native-local-stage-e18d326-20260802-v1/g007-stage-43b9a97018e920b103c0e9b5f884208186b71ea6de1711ca81b98043df3433c8`.
- **재현 절차**:
  - exact cell `workers=2|planner=MinST|workload=l2svm|profile=lan`을 immutable stage의
    `run_LAN_docker.sh`로 attempt `1`만 실행했다. retry policy는 `NONE`이며 기존 binary/cell은
    `CANARY_FAILED.json`으로 봉인해 다시 실행하지 않는다.
  - compile/lowering 재현:
    `mvn -q -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.CampaignBG014MinStL2SvmInternalEmissionCostRedTest test`.
- **관측 증상**:
  - 실패 Docker canary는 compile을 통과했지만 SystemDS return code `3`, semantic output 부재였다.
    wrapper `2445.585992582s`, raw execution `2351.291s`였다.
  - MinST heavy hitter는 `fed_ba+*` `2310.671s`/61회, `fed_fed_refed` `33.241s`/60회,
    `fed_r'` `4.557s`/31회였고 PUT은 186회/`25,213,431,104` bytes였다.
  - 동일 Docker 조건 DP는 `67.745744316s`, `fed_r'` 1회였다.
  - compile-only 회귀에서도 MinST runtime explain에 `FED r' X.MATRIX`가 2회였고 loop 내부에 1회 남았다.
    선택된 relocation은 hop 229 transpose와 hop 230 local vector를 hop 231 aggregate binary의
    `FED/LOUT/ROW` 입력으로 각각 재배치했다.
- **원인 분석**:
  1. neutral candidate domain에는 hop 231에 대해 exact native row
     `[COL, ABSENT_LOCAL] -> FED/LOUT/COL`이 존재한다. 이는 relocation이 아니라 runtime이 직접 지원하는
     mixed federated/local 실행 후보다.
  2. MinST는 동일한 `<FED,LOUT>` bit membership을 만드는 여러 exact input row를 한 대표로 축약했다.
     기존 `strongestCoverage` 선택은 PRESENT authority가 많은 ROW 후보를 고정해, 실제 cut이 선택한 자식
     `hop229=FED/FOUT/COL`, `hop230=CP/LOUT`과 정확히 일치하는 native row를 버렸다.
  3. `ABSENT_LOCAL`도 무조건 relocation 입력으로 해석해 upload group/receipt를 만들었고, 그 결과 hop 229에
     planner materialization boundary가 등록됐다. `AggBinaryOp.usesLeftTransposeRewrite`는 계획된 movement를
     지우지 않기 위해 이 boundary가 있으면 transpose fusion을 금지하므로 loop마다 `r'`가 재실행됐다.
  4. 따라서 이전의 “cross-occurrence durable placement reuse 누락” 진단은 직접 원인이 아니었다. 핵심은
     **선택된 자식 상태와 무관한 exact-row 대표 고정 + native local 입력을 relocation으로 오분류**한 것이다.
- **해결 요약**:
  - canonical baseline cut은 그대로 보존하고, 선택된 자식 placement와 exact하게 일치하는 native
    `FED/LOUT` candidate row를 immutable `RepresentativePreference` variant로 별도 재유도/재가격한다.
  - variant는 facts에 포함되어 validation/projector/diagnostics에서 동일하게 재생된다. 선호 row의 PRESENT
    입력은 정확한 producer FOUT layout/exec membership을, ABSENT 입력은 producer LOUT을 hard legality로
    고정한다. 이로써 row와 cut 상태가 분리되는 것을 막는다.
  - exact non-derived `FED/LOUT`의 `ABSENT_LOCAL`은 `computeMixedFedLocalCost`에 `null` input type으로 전달해
    runtime의 mixed-local preparation cost를 사용하며, planner upload group/relocation receipt를 만들지 않는다.
  - baseline과 variant의 완전한 min-cut objective를 비교해 더 싼 결과만 선택한다. 비용 동률이면 obligation이
    더 적은 계획을 선택한다. 임의 예외를 삼키거나 runtime fallback/repair를 사용하지 않는다.
  - 현재 2-bit cut으로 안전하게 표현 가능한 “consumer의 모든 FED membership이 LOUT인 경우”에만 이 variant를
    생성한다. FED/LOUT와 FED/FOUT이 섞인 membership은 기존 baseline을 유지하며 향후 expanded-state 대상이다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCut.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFacts.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactPlacementProjector.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStDiagnosticsProducer.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014MinStL2SvmInternalEmissionCostRedTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpL2SvmRefedSourceLoweringRedTest.java`
  - Unsafe-fixture field initialization: `MinStExactSelectorTest`, `MinStExactAnchorRelocationIdentityRedTest`,
    `MinStDownloadAuthorityAmbiguityRedTest`.
- **검증**:
  - MinST L2SVM runtime explain 회귀 GREEN: `FED r' X.MATRIX` 전체 1회, outer loop 0회, loop-local
    transpose source의 selected relocation 0개.
  - DP 대조 회귀도 `FED r' X.MATRIX` 1회로 GREEN.
  - L2SVM/KMeans/StepLM/PCA/heavy-MM/BR10/forward/selector focused suite `37/37` pass:
    `/tmp/g007-minst-native-local-focused-37-final-20260802.log`, SHA-256
    `b558c5b98ef9c267400fdf5edaa40993d38a70d9e8824ae6e9fa6fac001e5807`.
  - checkstyle/RAT/compile 포함 `mvn -q -DskipTests package` 성공:
    `/tmp/g007-minst-native-local-package-final-20260802.log`, SHA-256
    `fe6151829c6404e4fae6410a7576c1e1b3f8e9b0d2264ef5063f1431e6e0ffa5`.
  - canonical supervised lane(`--salg l2svm`) fresh Docker canary root:
    `/home/mchoi/g007-minst-native-local-canary-e18d326-d60da24-20260802-v2`.
    attempt `1`, retry 없음으로 성공했으며 discovery execution `29.532813713s`, SystemDS total execution
    `5.857s`, full lifecycle `56.813738703s`였다. semantic oracle pass, scan error/fallback/resource-invalid/timeout
    전부 false, coordinator/worker restart `0/0`, teardown container/network `0/0`이다.
  - canary의 heavy hitter는 `fed_r'` 정확히 `1`회, `fed_ba+*` `61`회, `fed_fed_refed` `60`회였다.
    response SHA-256은 `93957a489ab3ce5b613e809d0f9933cfe556f6ccaf1fb4deb80f59dabd4a9a9c`,
    raw coordinator SHA-256은 `b878c48d86608514f35b0ad9ebc43364c2f163fd63153e89e3345d5a22da1785`다.
  - canary를 historical success에 정확히 한 번 편입한 continuation root는
    `/home/mchoi/g007-all-planners-minst-native-local-e18d326-d60da24-20260802-v1`이다. 독립 사전 감사에서
    historical `283`(DP/FedAll/Heuristic 각 `84`, MinST `31`), remaining MinST `53`, overlap `0`, union
    `336`을 확인했고 283개 모두 attempt `1`, oracle pass, fallback 없음, restart `0/0`, teardown 성공으로
    재검증했다. 같은 seed `2026072701`, 셀당 한 번, retry `NONE`, stage-local `run_LAN_docker.sh`만 사용한다.
- **잔여 이슈**:
  - fresh canary 성공으로 historical은 `282`에서 `283`으로 증가했고, remaining은 `54`가 아니라 `53`이다.
    현재 unfinished-only Docker continuation을 실행 중이며, 새 실패가 생기면 같은 binary/cell을 재시도하지 않고
    campaign을 봉인한 뒤 planner/runtime 원인을 분리한다.
  - 전체 `336` 성공 후 semantic/fallback/restart/teardown 감사를 다시 수행하고 최종 그래프 및
    `MinST <= DP <= Heuristic, FedAll` 정렬을 검증한다.
  - FED/LOUT와 FED/FOUT이 동일 consumer에 함께 존재하면서 input-row mode가 다른 경우는 2-bit graph의
    conditional upload 표현 한계가 있으므로 expanded exact-state cut이 장기 해결책이다.
- **잠재 회귀 위험**:
  - variant가 producer FType을 output bit만으로 고정하면 CP/FOUT과 FED/FOUT의 layout 차이를 놓칠 수 있다.
    감지/방지: matching layout이 한 exec membership에만 있으면 compute bit도 hard constraint로 고정한다.
  - native-local과 relocation-backed ABSENT를 혼동하면 BR10 KMeans/derived-FOUT upload가 사라질 수 있다.
    감지/방지: non-derived `FED/LOUT` exact emission만 native-local로 분류하고 BR10/focused suite를 유지한다.
  - 여러 preference가 같은 producer에 상충하는 LOUT/FOUT을 요구할 수 있다. 감지/방지: derivation에서
    producer별 required output conflict를 fail-fast한다.
- **의사결정 근거/적용 원칙**:
  - runtime-supported 후보를 닫지 않고 baseline과 native-row variant를 모두 같은 MinST 비용 모델로 비교했다.
    runtime fallback 금지, TRead/TWrite `<CP,LOUT>/<FED,FOUT>` 제한, recompile `<CP,FOUT>` 금지를 유지했다.

## MinST L2SVM Docker canary가 supervised lane 대신 일반 algorithm lane으로 잘못 호출됨

- **상태**: 해결 — malformed attempt 제외, canonical `--salg` canary를 별도 root에서 1회 실행해 성공
- **환경/조건**:
  - 잘못된 canary root:
    `/home/mchoi/g007-minst-native-local-canary-e18d326-d60da24-20260802-v1`.
  - 올바른 canary root:
    `/home/mchoi/g007-minst-native-local-canary-e18d326-d60da24-20260802-v2`.
  - 동일 immutable stage/commit/JAR/seed를 사용했으며 planner는 MinST, workers `2`, profile `lan`이다.
- **재현 절차**:
  - 잘못된 호출은 `run_LAN_docker.sh ... --alg l2svm`이었다.
  - canonical 호출은 `run_LAN_docker.sh ... --salg l2svm`이다.
- **관측 증상**:
  - workload process 자체는 return code `0`, SystemDS execution `5.809s`, runtime scan clean이었지만,
    일반 algorithm phase oracle은 `fed_P2P2D_2.res`를 기대했고 실제 supervised workload는
    `l2svm-P2P2D.res`를 생성해 semantic oracle 단계에서 실패했다.
  - 실제 출력 SHA-256은 올바른 canary와 같은
    `18328c5d122f75439f654399ceb61660709e90eb919a794b0c587ca7a07615ce`였다.
- **원인 분석**:
  - `l2svm`, `lm`, `logreg`, `steplm`은 harness의 supervised/scalar lane으로 `--salg`를 사용해야 한다.
    첫 수동 canary manifest만 이를 `--alg`로 잘못 분류했다. planner/runtime 실패가 아니다.
- **해결 요약**:
  - malformed root에 `INVALID_CANARY_INVOCATION.json`을 기록하고 status를
    `excluded-not-a-canonical-cell-attempt`로 고정했다. 이 결과는 336-cell 완료 집합 및 성능 그래프에 포함하지 않는다.
  - retry로 덮지 않고 새 root/새 manifest를 만들었으며 canonical `--salg` canary를 정확히 한 번 실행했다.
  - continuation runner의 `SCALAR_WORKLOADS={lm,l2svm,logreg,steplm}` 분기와 남은 53개 cell argv를 사전 검증했다.
- **수정 파일**:
  - production source 수정 없음.
  - 실험 artifact: 두 canary root의 manifest/verdict 및 `INVALID_CANARY_INVOCATION.json`.
- **검증**:
  - canonical canary semantic oracle NRMSE `5.558823931286871e-16`, objective relative error `0`,
    response SHA-256 `93957a489ab3ce5b613e809d0f9933cfe556f6ccaf1fb4deb80f59dabd4a9a9c`.
  - continuation preflight에서 malformed root occurrence `0`, canonical canary occurrence `1`을 검증했다.
- **잔여 이슈**:
  - 없음. 남은 supervised workload 셀은 공통 runner의 `SCALAR_WORKLOADS` 분기를 사용한다.
- **잠재 회귀 위험**:
  - 신규 supervised workload가 목록에 추가되지만 분기에 누락되면 같은 oracle-path 오분류가 재발할 수 있다.
    감지 방법: workload→CLI lane 매핑을 manifest 사전 감사에 포함하고 실제 expected output basename을 확인한다.
- **의사결정 근거/적용 원칙**:
  - planner/runtime를 변경하지 않고 잘못된 실험 호출만 제외·정정했다. malformed 결과를 가짜 성공으로 승격하지 않았다.

## systemd user 서비스에서 Docker 보조 그룹이 상속되지 않아 continuation이 셀 실행 전에 종료됨

- **상태**: 해결 — pre-cell infrastructure failure로 분류하고 `sg docker` launcher로 정상 시작
- **환경/조건**:
  - campaign root:
    `/home/mchoi/g007-all-planners-minst-native-local-e18d326-d60da24-20260802-v1`.
  - 최초 unit `g007-minst-e18d326-v1.service`, 수정 unit `g007-minst-e18d326-v2.service`.
- **재현 절차**:
  - `systemd-run --user ... <root>/bin/service-wrapper.sh`로 직접 실행하면 service 내 `id`에 기본 그룹만 남고
    `docker version`이 `/var/run/docker.sock` permission denied로 종료된다.
- **관측 증상**:
  - `run.sh` 진입 전 campaign exit code `1`; cell request `0`, response `0`, rows `0`, Docker resource `0`이었다.
  - 증거는 `control/launch-v1-no-docker-group/INFRASTRUCTURE_FAILURE.json`과 보존된 stderr에 있다.
- **원인 분석**:
  - 현재 로그인 shell은 `docker` 보조 그룹을 가지지만 이미 실행 중인 systemd user manager가 해당 보조 그룹을
    service process에 전달하지 않았다. full filesystem 권한이나 Codex 승인 문제와 무관한 OS group context 문제다.
- **해결 요약**:
  - cell plan/stage/binary/seed를 변경하지 않고 launcher만
    `/usr/bin/sg docker -c <root>/bin/service-wrapper.sh`로 실행했다. monitor도 같은 그룹 context로 실행한다.
  - 최초 종료는 cell attempt가 하나도 생성되지 않았으므로 실험 retry로 세지 않는다.
- **수정 파일**:
  - production source 수정 없음.
  - campaign control receipts와 launch artifact만 추가.
- **검증**:
  - 수정 unit의 service identity는 gid `docker`, Docker server `29.6.1`이다.
  - `run_remaining_discovery.py`와 첫 canonical request
    `workers=2|planner=MinST|workload=l2svm|profile=wan_light` 실행을 확인했다.
- **잔여 이슈**:
  - campaign 종료까지 5분 monitor snapshot에서 unit 상태, rows/failures, Docker resource를 확인한다.
- **잠재 회귀 위험**:
  - 향후 systemd user manager가 재시작되거나 group 정책이 바뀌면 직접 launcher도 동작할 수 있지만,
    일관성을 위해 Docker campaign service는 `sg docker` 경로를 유지하고 시작 직후 `docker version`을 fail-fast한다.
- **의사결정 근거/적용 원칙**:
  - 실험 셀을 재실행하거나 결과를 합성하지 않고, 셀 실행 전 인프라 권한 context만 바로잡았다.

## 중간 성능 그래프가 실행환경×워크로드 3×7 비교 형식을 따르지 않음

- **상태**: 폐기/진단용 — 3행×7열 형식은 맞지만 서로 다른 binary와 Docker lifecycle metric을 섞어 성능 근거로 사용할 수 없음
- **환경/조건**:
  - Docker-only stitched snapshot `294/336`: DP/FedAll/Heuristic 각 `84`, MinST `42`.
  - campaign root:
    `/home/mchoi/g007-all-planners-minst-native-local-e18d326-d60da24-20260802-v1`.
- **재현 절차**:
  - 기존 `docs/experiments/minst-continuation-2026-08-02-interim-284/` 그래프를 열면 profile별 파일과
    ratio boxplot으로 분리되어 있어 실행환경 행과 workload 열을 한 화면에서 비교할 수 없다.
- **관측 증상**:
  - 사용자가 요구한 행=`LAN/WAN-light/WAN-mid`, 열=`7 workloads`, x=`workers`, y=`execution time` 구성이 아니었다.
- **원인 분석**:
  - 기존 plotter가 profile마다 `4×2` 별도 PNG를 생성하도록 고정되어 있었고 통합 facet 계약이 없었다.
- **해결 요약**:
  - 실행 중인 campaign의 성공 prefix 11개를 원자적으로 복사해 283개 historical registry와 결합했다.
  - 모든 response descriptor/hash, attempt `1`, semantic oracle, fallback/timeout/error scan, restart `0/0`, clean
    teardown 및 execution metric을 다시 검증한 뒤 `3×7` 단일 PNG를 생성했다.
  - 같은 workload 열의 세 실행환경은 동일한 y축 범위를 사용한다. 미실행 MinST 지점은 0으로 채우지 않는다.
- **수정 파일**:
  - `docs/experiments/minst-continuation-2026-08-02-interim-294/build_interim_dataset.py`
  - `docs/experiments/minst-continuation-2026-08-02-interim-294/plot_runtime_grid.R`
  - `docs/experiments/minst-continuation-2026-08-02-interim-294/runtime_grid_3x7_interim_294.png`
- **검증**:
  - 출력은 `4200×1900` RGB PNG이며 SHA-256은
    `c7cd1c0782545b05e1d178d2add10ea63490e65b70ccfff60e42772c528073a6`이다.
  - 인증 CSV는 294개 unique canonical cell이고 SHA-256은
    `d295b37a8313ef93709b3a467643994ad83c8c513314fff802cd9669fad6cf0d`이다.
- **잔여 이슈**:
  - 이 파일은 캠페인 진행 중 동결한 진단 스냅샷일 뿐이며 성능 비교에서 제외한다. 동일 immutable binary와
    `systemds_total_execution_time`으로 새 336개가 성공한 뒤 같은 3×7 형식으로 최종 그래프를 재생성한다.
- **잠재 회귀 위험**:
  - 실행 중인 rows 파일을 직접 읽으면 그래프 데이터와 제목 cardinality가 달라질 수 있다. 감지/방지: 먼저
    immutable JSONL snapshot을 만들고 그 snapshot row 수와 CSV unique 수를 검증한다.
- **의사결정 근거/적용 원칙**:
  - 측정값이나 planner 결과는 변경하지 않고 인증된 Docker 결과의 시각화 구조만 바로잡았다.

## worker=1 FULL 입력의 CP/FOUT reorg가 planner 후처리에서 잘못 제거됨

- **상태**: 코드/회귀 해결, 전체 실험 미검증 — planner/runtime capability parity 및 one-range FULL transpose 실행 회귀 통과; fresh Docker 336-cell 검증 대기
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`, 기준 HEAD `b73e51d0e4`.
  - 대상: 모든 planner가 공유하는 `FederatedRefedPolicy`와 `ReorgFEDInstruction`; 특히 worker `1`의
    single-range `FType.FULL` 입력.
  - privacy public 케이스는 실험/회귀 대상에서 제외하며 Docker 실험은 `run_LAN_docker.sh`만 사용한다.
- **재현 절차**:
  - planner 회귀:
    `mvn -q -DskipITs -Dcheckstyle.skip -Dspotbugs.skip -Dtest=FederatedRefedPolicyTest#testSingleWorkerFullTransposeIsNotDemotedFromPlannedFout,FederatedRefedPolicyReorgCapabilityTest test`.
  - runtime 회귀:
    `mvn -q -DskipITs -Dcheckstyle.skip -Dspotbugs.skip -Dtest=ReorgFEDInstructionFullTest test`.
- **관측 증상**:
  - rules/runtime는 transpose/rev/roll/diag에서 FULL을 정상 layout으로 취급했지만,
    `FederatedRefedPolicy.registerFromHops(...)`의 CP/FOUT reorg 후처리만 `inputFType == FULL`을
    `unsupportedFedInputForReorgFout`으로 분류했다.
  - 따라서 worker=1에서 이미 합법하게 선택된 FOUT 계획이 lowering 직전에 LOUT으로 demote될 수 있었고,
    후속 FED consumer가 다시 업로드하거나 더 보수적인 계획으로 수렴할 수 있었다.
- **원인 분석**:
  - `FType.isType(ROW/COL)`의 alias semantics 때문에 runtime은 FULL을 암묵적으로 허용했지만 planner 정책은
    과거의 “ROW/COL only” 설명을 기준으로 FULL을 명시적으로 닫았다. 같은 capability를 서로 다른 방식으로
    표현한 것이 drift의 원인이다.
- **해결 요약**:
  - planner reorg input capability를 ROW/COL/FULL/BROADCAST로 명시하고 PART/OTHER만 거부한다.
  - runtime gate도 alias 판정 대신 같은 exact FType 집합을 직접 검사하게 해 parity를 코드상 명시했다.
  - single-range FULL `FederationMap`을 실제로 transpose해 출력이 `FULL`, range `1`, shape `3x2`로 유지되는
    runtime 회귀와, 선택된 CP/FOUT transpose가 후처리에서 demote되지 않고 planner-owned lowering receipt를
    남기는 회귀를 추가했다.
  - 이는 worker-count 특례가 아니라 실제 runtime capability 복구이며 runtime-supported candidate를 닫지 않는다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java`
  - `src/main/java/org/apache/sysds/runtime/instructions/fed/ReorgFEDInstruction.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicyReorgCapabilityTest.java`
  - `src/test/java/org/apache/sysds/runtime/instructions/fed/ReorgFEDInstructionFullTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedRefedPolicyTest.java`
  - `src/test/java/org/apache/sysds/test/functions/fedplanner/rules/RulesetsReorgTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/rules/RulesetsUnaryTest.java`
- **검증**:
  - FULL/runtime/rules 및 MinST exactness를 묶은 focused suite `52/52` PASS. 로그
    `/tmp/g007-worker1-full-minst-exact-focused-20260802.log`, SHA-256
    `03ae01d4811ae1510c13c52ecc611cf8b5c6da33d7344a01e1119b6530a51b2a`.
  - one-range FULL runtime test는 실제 `ReorgFEDInstruction.processInstruction(...)`을 실행하며 단순 문자열/정적
    검사가 아니다.
- **잔여 이슈**:
  - 새 immutable JAR로 KMeans worker=1 Docker canary를 planner 순서 `DP → FedAll → Heuristic → MinST`로
    각각 한 번 실행해 실제 instruction fingerprint와 execution time을 확인한다.
- **잠재 회귀 위험**:
  - 다중 range를 FULL로 잘못 분류하면 복제/partition 의미가 섞일 수 있다. 감지: one-range FULL 회귀와 기존
    FULL/BROADCAST 분류 회귀를 함께 유지하고, Docker descriptor의 worker/range 수를 검사한다.
- **의사결정 근거/적용 원칙**:
  - runtime이 이미 지원하는 exact FULL 조합을 planner가 닫던 drift를 제거했다. fallback, TRead/TWrite 완화,
    recompile `<CP,FOUT>` 허용 또는 worker-count heuristic은 추가하지 않았다.

## MinST 전역 최적성의 범위와 shadow authority 검증이 분리되어 있지 않았음

- **상태**: 인코딩된 목적함수 검증 완료, 전체 plan-space/실측 최적성 미검증 — 독립 exhaustive cut oracle과 production selector 일치 포함 focused `52/52` PASS
- **환경/조건**:
  - MinST exact two-bit state: compute `CP/FED`, output `LOUT/FOUT`, neutral graph의 legal states와 directed
    upload/download/compute cost edges.
  - 주요 회귀: `MinStExactCutSolverTest`, `MinStExactSelectorTest`, `MinStExactTwoDecisionOracleTest`,
    `CampaignBR5MinStExactSelectorShadowRedTest`, `CampaignBR10MinStFTypeMembershipAuthorityRedTest`,
    KMeans grouped upload와 L2SVM native-local cost 회귀.
- **재현 절차**:
  - 위 7개 MinST test class를 한 Maven invocation에서 실행한다. BR5 fixture는 production selector를 호출하기 전에
    최대 1,000,000개의 모든 legal source partition을 독립 열거해 objective bits를 계산한다.
- **관측 증상**:
  - 최초 fresh run에서 BR5의 authority-only 보조 검사가 한 endpoint에 존재하는
    `FED/LOUT/ROW`와 `FED/FOUT/ROW` upload authority를 선택 상태 없이 하나로 축약해
    `R5_MINST_AUTHORITY_EXPECTED_PLACEMENT_AMBIGUOUS`로 실패했다.
  - production selector는 이미 cut이 고른 exact consumer state로 두 authority를 구분하고 있었으므로 solver
    objective 오류가 아니라 stale shadow fixture 오류였다.
- **원인 분석**:
  - helper가 과거의 “endpoint당 required placement 하나” 가정을 사용했다. exact-state 모델에서는 같은 endpoint가
    서로 다른 합법 consumer membership마다 별도 relocation authority를 갖는 것이 정상이다.
- **해결 요약**:
  - shadow comparison은 source partition에서 선택된 producer/consumer state를 독립 복원한 뒤 그 state와 정확히
    일치하는 transfer authority 하나를 요구한다.
  - 별도 authority coverage 검사는 모든 published authority의 required placement가 해당 decision의 legal state이며,
    relocation action/obligation identity가 neutral graph와 정확히 일치하는지 검사한다.
  - production selector/cost graph는 변경하지 않았다. MinST의 보장 범위는 **인코딩된 legal-state 비용 목적함수의
    전역 최소 cut**이다. 실제 wall-clock은 비용 모델의 정확도와 시스템 noise에 의존하므로 “항상 실측 최단”을
    수학적으로 보장하지 않는다.
- **수정 파일**:
  - `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBR5MinStExactSelectorShadowRedTest.java`
- **검증**:
  - focused suite `52/52` PASS. BR5 actual-root fixture에서 독립 exhaustive objective bits, source partition,
    selected states, obligation receipts가 production selector와 byte/identity 수준으로 일치했다.
- **잔여 이슈**:
  - 현재 exact-row variant search는 mixed `FED/LOUT`+`FED/FOUT` membership을 2-bit cut으로 정직하게 표현할 수
    없는 경우 baseline에 남긴다. 따라서 **전체 physical legal plan space의 전역 최적성은 아직 증명되지 않았다**.
  - variant 조합이 `4096`을 넘으면 fail-closed한다. 실제 7개 workload가 이 경계를 밟지 않는지 Docker 로그로 확인한다.
  - Docker에서 MinST가 DP보다 느린 셀은 곧바로 selector 오류로 단정하지 않고, 동일 binary/seed/data 조건에서
    selected fingerprint와 modeled objective를 먼저 비교한 뒤 cost estimate 오차를 분석한다.
- **잠재 회귀 위험**:
  - representative preference variant가 향후 2-bit로 표현 불가능한 conditional input row를 포함하면 각 variant는
    exact여도 전체 physical-state 공간을 포괄하지 못할 수 있다. 감지: baseline을 항상 보존하고 exhaustive small
    fixture, variant objective/obligation 비교 및 fail-closed conflict 검사를 유지한다.
- **의사결정 근거/적용 원칙**:
  - global optimal의 수학적 대상을 명확히 하고 독립 oracle을 복구했다. 실제 성능 정렬을 만들기 위한 candidate
    closure나 workload 예외는 추가하지 않았다.

## Docker discovery metric이 SystemDS 실행시간 대신 lifecycle wall-clock을 기록함

- **상태**: 측정 계약 해결, worker fluctuation 미검증 — 모든 phase의 성능 metric을 `systemds_total_execution_time`으로 통일; 새 homogeneous 336-cell 실행 대기
- **환경/조건**:
  - harness: `/home/mchoi/g007-harness-exdra-only-20260729-v1/sigmod2021-exdra-p523`, 기준 HEAD `d60da243`.
  - 폐기 campaign: `/home/mchoi/g007-all-planners-minst-native-local-e18d326-d60da24-20260802-v1`, 완료 `298/336`.
- **재현 절차**:
  - 과거 discovery bundle의 `metric.json`과 같은 cell의 `raw_coordinator.log` 내 strict
    `Total execution time: ... sec.` 및 response descriptor의 `full_lifecycle_seconds`를 비교한다.
  - harness 회귀: `python3 -m unittest discover -s tests -p 'test_*.py'`.
- **관측 증상**:
  - discovery phase는 `(time.time_ns() - started_ns)`를 metric으로 저장해 Docker 생성, network shaping, oracle,
    teardown 시간을 포함했고 warm phase만 SystemDS raw execution time을 사용했다.
  - KMeans worker=1 raw SystemDS 시간은 DP/MinST가 LAN `18.900/18.728s`, WAN-light `30.847/30.926s`,
    WAN-mid `83.957/85.399s`로 유사했으나 lifecycle metric 기반 그래프에서는 planner/worker 순서와 무관한
    외부 overhead가 크게 섞여 “DP/MinST가 비정상적으로 느리고 worker 수에 따라 요동”하는 것처럼 보였다.
  - 과거 298개는 15개 source commit/JAR에서 이어 붙인 결과이므로 metric 수정만으로도 최종 비교에 사용할 수 없다.
- **원인 분석**:
  - correctness discovery의 end-to-end duration과 algorithm execution time을 같은 `seconds` 필드에 넣은 측정 계약
    오류가 주원인이다. worker 증가에 따른 runtime은 통신/직렬화/plan-switch threshold 때문에 단조 감소가
    보장되지 않지만, lifecycle noise를 y축에 넣으면 이 현상을 훨씬 크게 왜곡한다.
- **해결 요약**:
  - `phase_bundle.py`가 discovery/cold/warm 모두 raw log의 정확히 한 개 `Total execution time`을 파싱해
    `systemds_total_execution_time`으로 기록한다.
  - setup→teardown 전체 시간은 기존 response descriptor의 `full_lifecycle_seconds`에만 남겨 성능 y축과 분리한다.
  - 과거 root에 `SUPERSEDED_FOR_PERFORMANCE.json`을 기록하고 `superseded-do-not-resume-or-plot`으로 고정했다.
    새 결과는 동일 source commit/JAR/harness/data/seed의 immutable stage에서 336개를 각각 한 번만 실행한다.
- **수정 파일**:
  - harness `experiments/tools/phase_bundle.py`
  - harness `experiments/tests/test_g007_harness.py`
  - harness `experiments/tests/test_campaign_lifecycle.py`
  - harness `experiments/docs/SEED_AND_DATA_FREEZE.md`
  - 폐기 campaign `SUPERSEDED_FOR_PERFORMANCE.json`
- **검증**:
  - discovery fixture가 시작/종료 ns와 무관하게 raw `1.25 sec.`를 그대로 저장하는 회귀 PASS.
  - harness 전체 `121/121` PASS; lifecycle suite `28/28`, main harness suite `51/51` PASS.
  - 폐기 marker SHA-256 `56c0f566fc2ce987bcfa77972afde40de27ef76d6bf636e53f6bf1fe5c529a67`.
- **잔여 이슈**:
  - 새 source/harness commit으로 immutable stage를 만들고 KMeans worker=1 canary를 planner 순서대로 실행한 뒤,
    exact 336-cell discovery를 한 번만 수행한다. 완료 전에는 정렬 결론이나 최종 3×7 그래프를 발표하지 않는다.
- **잠재 회귀 위험**:
  - raw log에 timing line이 0개/2개 이상이면 잘못된 값을 고를 수 있다. 감지: phase pass 조건은 strict timing line
    cardinality `1`과 positive finite seconds를 요구하고 실패 bundle을 보존한다.
  - 실제 runtime은 worker 증가에 따라 약간 비단조일 수 있다. 감지: 동일 plan fingerprint 반복성, restart `0/0`,
    resource/timeout/fallback scan과 network profile receipt를 별도로 검증하며 곡선을 인위적으로 정렬하지 않는다.
- **의사결정 근거/적용 원칙**:
  - planner 후보/비용을 성능 그래프에 맞추지 않고 측정 경계를 바로잡았다. data/seed 고정과 Docker-only,
    no-retry/no-stitching 원칙을 강화했다.

## FED reorg가 planner 오류를 CP 실행·missing-variable 재초기화로 은폐함

- **상태**: 코드/회귀 해결, Docker 미검증 — runtime CP fallback과 missing-variable 재시도 삭제; fresh canary 대기
- **환경/조건**:
  - 소스: `/home/mchoi/g007-dp-minst-function-boundary-source-20260730-v1`, 기준 HEAD `20c82a8ccc`.
  - 대상: `ReorgFEDInstruction`의 local input 처리와 transpose missing-variable 재시도.
- **재현 절차**:
  - `mvn -q -DskipITs -Dcheckstyle.skip -Dspotbugs.skip -Dtest=ReorgFEDInstructionFullTest test`.
- **관측 증상**:
  - FED reorg가 local `MatrixObject`를 받으면 예외 대신 CP instruction으로 변환해 실행한다.
  - FOUT이면 실행 컨텍스트에서 임의의 unique worker-pool anchor를 찾아 결과를 FULL로 업로드한다.
  - transpose가 worker의 missing-variable 오류를 받으면 local value를 같은 ID로 재업로드하거나 source path를
    다시 읽고 한 차례 재시도한다.
- **원인 분석**:
  - 과거 MinST upload/download obligation을 실행 가능하게 만들면서 planner legality 결함을 runtime repair로
    흡수한 코드가 남았다. 그 결과 성공 로그가 실제로 합법한 planner plan을 증명하지 못한다.
- **해결 요약**:
  - local input이 CP로 실행되지 않고 fail-fast해야 한다는 behavioral 회귀를 추가했다.
  - CP 변환 fallback과 missing-variable reinit/retry를 삭제했고, Docker 실패가 나면 planner의 placement 또는
    lifetime/rewire 모델에서 원인을 수정한다.
- **수정 파일**:
  - `src/test/java/org/apache/sysds/runtime/instructions/fed/ReorgFEDInstructionFullTest.java`
  - `src/main/java/org/apache/sysds/runtime/instructions/fed/ReorgFEDInstruction.java`
- **검증**:
  - `FederatedPlannerFallbackIntegrationTest`를 포함한 critical gate `289/289` PASS:
    `/tmp/g007-critical-source-gate-r4-20260802.log`, SHA-256
    `b98e72556a9292a7ddc320efef6838835ce2f9d3b2c7d7c5259d59ad751e7d42`.
  - 수정/신규 테스트 전체 gate `194` 실행, `192` PASS, PUBLIC privacy `2` ignored, failure/error `0`:
    `/tmp/g007-modified-tests-gate-r1-20260802.log`, SHA-256
    `f4074c92a9fb676394abc27ebb92ecda5cefd199f8bca3b9ec1cb52db967897e`.
- **잔여 이슈**:
  - reorg 이외 FED instruction의 이름뿐인 fallback이 명시적 planned relocation인지 암묵 repair인지 분류해야 한다.
  - fail-fast 복구 뒤 KMeans/PCA worker 1–4에서 드러나는 planner 불법 경로를 수정해야 한다.
- **잠재 회귀 위험**:
  - 기존 실험이 fallback에 의존했다면 새 Docker canary가 실패한다. 이는 runtime 우회로 되돌리지 않고 planner
    candidate/rewire/lifetime 오류로 추적한다.
- **의사결정 근거/적용 원칙**:
  - runtime fallback 금지와 “planner가 실행 가능성을 판정하고 runtime은 plan을 그대로 실행” 원칙을 적용한다.

## DP conflict refinement가 절대 Hop ID와 HashMap 순서에 따라 다른 plan을 선택함

- **상태**: 코드/회귀 해결, worker scaling 실험 미검증
- **환경/조건**:
  - DP multi-write/transient-variable fixture의 동일 DAG. 첫 Hop ID가 `0`이면 FOUT, 더미 Hop 13개 뒤 `13`이면
    LOUT을 선택했고 전체 test class 실행에서는 `3453`부터 시작해 같은 실패가 재현됐다.
- **재현 절차**:
  - 수정 전 offset-13 회귀:
    `mvn -q -DskipITs -Dcheckstyle.skip -Dspotbugs.skip -Dtest=FederatedPlannerFallbackIntegrationTest#testDpMultiWriteTransientVariableUsesOneExecutableRepresentation test`.
  - RED 로그: `/tmp/g007-dp-multiwrite-offset13-red-r1-20260802.log`.
- **관측 증상**:
  - 구조/비용/입력 placement가 같은데 전역 Hop ID offset만 바꾸면 DP의 accepted refinement와 최종 output placement가
    달라졌다. 이는 seed/data/cache가 아니라 planner 내부 iteration-order 비결정성이었다.
- **원인 분석**:
  - `conflictCheckMap`은 `HashMap`인데, refinement 단계가 `entrySet()` 순서로 앞 후보를 적용한 뒤 그 변경 상태를
    다음 후보 평가의 입력으로 사용했다. Long hash bucket wrap에 따라 producer/consumer 처리 순서가 달라졌다.
- **해결 요약**:
  - conflict normalization/refinement/parent-graph 순회를 모두 ascending Hop ID의 producer-first canonical order로
    통일했다. 후보를 닫거나 비용을 바꾸지 않고 동일 상태 전이를 동일 순서로 수행한다.
  - 회귀 fixture가 13개 dummy LiteralOp을 먼저 할당해 과거 bucket wrap을 고정적으로 재현하도록 했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**:
  - offset-13 GREEN: `/tmp/g007-dp-multiwrite-offset13-green-r1-20260802.log`, SHA-256
    `edc8d35623ef3b5bdbbf28491b2a7624c1b8e3502b34ec0aa1ae61ac754cb9c0`.
  - full critical gate `289/289` PASS 및 DP owner/estimator gate `51` PASS + PUBLIC `2` ignored:
    `/tmp/g007-dp-owner-estimator-gate-r2-20260802.log`, SHA-256
    `8c7a4dab4e08b145b1b937992ede411cc117c2ace10a1657387632e9f9d51d0c`.
- **잔여 이슈**:
  - 이 수정은 planner plan의 run-order 결정성을 보장하지만 worker 수에 따른 실제 runtime 곡선의 단조성은 보장하지
    않는다. 동일 immutable Docker pass에서 fingerprint 변화와 execution time을 분리해 판정해야 한다.
- **잠재 회귀 위험**:
  - producer-first가 아닌 별도 dependency가 refinement에 숨어 있으면 정렬만으로 semantic ordering을 표현하지 못할 수
    있다. 감지: 전역 Hop ID offset/전체 class order 회귀와 336-cell instruction fingerprint를 함께 유지한다.
- **의사결정 근거/적용 원칙**:
  - supported candidate를 닫지 않고 planner state transition의 비결정성만 제거했다.

## DP exact estimator가 동일 occurrence의 복수 physical carrier를 하나로 축약함

- **상태**: 코드/회귀 해결, ALS Docker 미검증
- **환경/조건**:
  - 함수/loop unrolling으로 하나의 semantic occurrence가 여러 executable Hop carrier를 갖는 ALS DP fixture.
- **재현 절차**:
  - ALS carrier receipt 회귀 실행. GREEN 로그:
    `/tmp/g007-als-carrier-receipt-fix-r6-20260802.log`.
- **관측 증상**:
  - exact estimator가 semantic occurrence의 원래 Hop ID로 pruned plan을 조회하고, memo arm API는 physical carrier가
    둘이면 ambiguous로 실패했다. 그 결과 합법한 unrolled clone plan이 누락되거나 null estimator request가 발생했다.
- **원인 분석**:
  - semantic occurrence와 executable carrier를 1:1로 가정했다. 실제 function/loop lowering에서는 occurrence 1개에
    carrier N개가 가능하다.
- **해결 요약**:
  - enumerator는 현재 실행 carrier Hop ID로 exact plan을 요청한다.
  - occurrence 조회는 등록된 모든 carrier의 LOUT/FOUT arm을 canonical Hop-ID 순으로 반환하고, 동일 output의 선택은
    cumulative cost 최소/동률 Hop ID 최소로 결정한다.
  - enclosing memo authority가 있는 unrolled clone carrier를 합법 completion receipt로 인정한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpMemoTable.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
- **검증**:
  - ALS focused GREEN과 critical `289/289`, DP owner/estimator gate failure/error `0`.
- **잔여 이슈**:
  - ALS worker 1–4 Docker 셀에서 carrier selection과 warm/cold fingerprint가 동일한지 확인한다.
- **잠재 회귀 위험**:
  - 여러 carrier가 서로 다른 실행 의미를 갖는데 semantic occurrence가 잘못 합쳐진 경우 cheapest 선택이 부정확할 수
    있다. 감지: owner-bound occurrence identity, carrier Hop identity, exact placement state를 receipt에서 동시에 검사한다.
- **의사결정 근거/적용 원칙**:
  - plan-space 축소 없이 실제 executable carrier 공간을 memo/estimator에 복원했다.

## 단일 336-cell Docker pass의 중복·측정·provenance 계약 부재

- **상태**: 하네스 구현/단위 검증 완료, 본 실험 미실행
- **환경/조건**:
  - `3 profiles × 7 workloads × 4 workers × 4 planners = 336` private-aggregate cells.
  - 각 logical cell은 cold/setup 1회와 fresh coordinator JVM warm 1회이며, warm SystemDS execution time만 primary다.
- **관측 증상**:
  - 과거 결과는 여러 source JAR과 lifecycle/discovery metric이 섞였고, 중단 후 수동 재개 시 이미 실행한 셀을 다시
    돌릴 위험이 있었다.
- **해결 요약**:
  - manifest의 exact ordered prefix만 resume 가능하고 logical cell마다 attempt `1`, retry `NONE`을 강제하는 runner를
    추가했다. planner 순서는 `DP → FedAll → Heuristic → MinST`, 각 planner는 KMeans/PCA 24-cell canary가 먼저다.
  - `run_LAN_docker.sh`만 허용하고 물리 `run_LAN.sh`가 stage에 존재하면 fail-closed한다.
  - cold/warm instruction fingerprint 동일성, seed/data/JAR/harness hashes, fresh no-cache network receipt, semantic oracle,
    fallback/error/timeout/resource scan, restart `0/0`, clean teardown를 cell row에 인증한다.
- **수정 파일**:
  - harness `experiments/tools/run_one_pass_performance.py`
  - harness `experiments/tests/test_one_pass_performance.py`
  - harness `experiments/code/distributedExpNew.sh`
  - harness `experiments/tests/test_g007_harness.py`
- **검증**:
  - one-pass runner `6/6` PASS, main harness `51/51` PASS, Python compile/bash syntax/git diff check PASS.
- **잔여 이슈**:
  - clean source/harness commits로 immutable stage를 만들고 첫 24개 DP KMeans/PCA canary부터 한 번씩만 실행한다.
- **잠재 회귀 위험**:
  - 실패한 logical cell은 같은 output root에서 재시도할 수 없다. 원인을 수정하면 새 immutable commit/stage/output으로
    전체 campaign을 다시 정의해야 하므로, canary 24개에서 fail-fast 검증을 먼저 수행한다.
- **의사결정 근거/적용 원칙**:
  - 데이터/seed 고정만으로는 부족한 JAR, network, metric phase, container lifecycle, plan fingerprint를 함께 고정했다.

## 기본 실행에서 rmvar 진단 출력이 런타임 측정을 오염함

- **상태**: 코드/회귀 해결, Docker 측정 재검증 대기
- **환경/조건**:
  - 모든 planner/workload. 특히 loop가 많은 workload에서 `rmvar X/Y`가 반복 실행되는 경우.
  - 명시적 planner trace/debug property는 꺼진 기본 실행.
- **재현 절차**:
  - `mvn -q -DskipITs -Dcheckstyle.skip -Dspotbugs.skip \
    -Dtest=VariableCPInstructionFederatedCleanupTest test`
  - 과거 raw 예시:
    `/home/mchoi/g007-selected-minst-6d4d852-74ee30f-20260729-v2/cells/042-f13fc97c5734/phases/cell-1/discovery-correctness/raw_coordinator.log`.
- **관측 증상**:
  - `VariableCPInstruction`, `DMLTranslator`, `Dag`가 trace flag와 무관하게
    `[DEBUG] rmvar X/Y ...`를 stdout에 대량 출력했다.
  - 같은 파일에는 loop workload에서 수천~수만 회의 rmvar 실행이 있어, terminal/file I/O가 SystemDS 실행 구간에
    직접 포함되고 planner/worker별 실행 횟수 차이가 성능 차이로 오인될 수 있었다.
- **원인 분석**:
  - 과거 lifetime/rewire 진단을 위해 넣은 임시 `System.out`가 production 기본 경로에 남았다.
  - CP→FOUT 진단 일부도 기존 `LOG_LOP_MAPPING` gate 밖에서 출력되고 있었다.
- **해결 요약**:
  - X/Y 전용 unconditional rmvar/exit-instruction 출력을 삭제했다.
  - CP→FOUT anchor/skip 진단은 기존 explicit `LOG_LOP_MAPPING` property 아래로 이동했다.
  - rmvar 기본 실행이 `[DEBUG]`를 쓰지 않는 stdout capture 회귀를 추가했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/runtime/instructions/cp/VariableCPInstruction.java`
  - `src/main/java/org/apache/sysds/parser/DMLTranslator.java`
  - `src/main/java/org/apache/sysds/lops/compile/Dag.java`
  - `src/test/java/org/apache/sysds/test/component/federated/VariableCPInstructionFederatedCleanupTest.java`
- **검증**:
  - RED: `/tmp/g007-rmvar-debug-regression-red-20260802.log` — 신규 3개 중 1개 실패.
  - GREEN: `/tmp/g007-rmvar-debug-regression-green-20260802.log` — 3/3 PASS.
  - `git diff --check` PASS.
- **잔여 이슈**:
  - 새 JAR의 Docker canary에서 raw log에 unconditional `[DEBUG]`가 없고, warm execution time이 이전의 출력 I/O를
    포함하지 않는지 확인한다.
- **잠재 회귀 위험**:
  - 실제 오류 진단 정보가 줄 수 있다. 감지 방법: 필요한 상세 정보는 이미 존재하는 explicit trace/debug property를
    켠 진단 실행에서만 수집하고, primary 성능 실행에서는 항상 꺼진 상태를 receipt로 고정한다.
- **의사결정 근거/적용 원칙**:
  - planner 후보나 runtime semantics를 바꾸지 않고 측정 경로의 비의도적 I/O만 제거했다.

## worker 수 증가 시 Docker JVM/BLAS CPU 과구독이 발생함

- **상태**: 하네스/정적·단위 검증 해결, 실제 fluctuation 제거 여부는 Docker canary 대기
- **환경/조건**:
  - 48 logical CPU host, worker `1..4`, coordinator 1개 + worker JVM N개.
  - 기존 manifest는 각 JVM에 `systemds/OMP/BLAS threads=48`을 부여했고 Compose CPU quota가 없었다.
- **재현 절차**:
  - 기존 `docker compose config`와 host `nproc/lscpu`를 비교한다.
  - worker=4에서는 5 JVM × 48 thread까지 runnable해 host 48 CPU 대비 최대 240-way oversubscription이 가능했다.
- **관측 증상**:
  - worker 수가 늘수록 같은 coordinator CP work와 worker native work가 서로 더 심하게 경쟁한다.
  - 따라서 worker scaling 곡선의 요동이 planner plan 변화인지 CPU scheduler contention인지 분리되지 않았다.
- **원인 분석**:
  - 데이터/seed/network만 고정하고 per-service CPU/thread resource contract는 고정하지 않았다.
- **해결 요약**:
  - coordinator와 각 worker를 동일하게 `8.0 CPU`, SystemDS `ActiveProcessorCount=8`,
    OMP/MKL/OpenBLAS `8 threads`, dynamic thread adjustment off로 고정했다.
  - 최대 동시 서비스 5개에 필요한 host affinity CPU를 40으로 계산해 미달 host에서는 fail-closed한다.
  - one-pass runner가 inherited host tuning 값을 campaign contract로 덮어쓰도록 했다.
- **수정 파일**:
  - harness `experiments/docker/compose.yaml`
  - harness `experiments/parameters.sh`
  - harness `experiments/config/campaign_manifest_policy.json`
  - harness `experiments/tools/run_one_pass_performance.py`
  - harness `experiments/tests/test_one_pass_performance.py`
  - harness `experiments/tests/test_g007_harness.py`
- **검증**:
  - RED: `/tmp/g007-resource-isolation-regression-red-20260802.log`.
  - focused GREEN: `/tmp/g007-resource-isolation-regression-green-20260802.log`.
  - 실제 `docker compose config --format json`에서 coordinator/worker1..8 모두 `cpus=8`, OMP/MKL/OpenBLAS `8`,
    JVM `-XX:ActiveProcessorCount=8` 확인:
    `/tmp/g007-compose-resource-config.json`.
  - `parameters.sh` 중복 옵션 probe PASS: `/tmp/g007-parameters-resource-probe.txt`.
- **잔여 이슈**:
  - DP KMeans/PCA worker 1–4 canary로 runtime plan 변화와 execution-time scaling을 분리해 판정한다.
  - 실제 알고리즘은 통신/작은 partition/GC 때문에 완전 단조일 필요는 없으므로, 요동을 인위적으로 보정하지 않는다.
- **잠재 회귀 위험**:
  - 8-thread contract는 이전 48-thread 절대 시간과 비교할 수 없다. 감지 방법: 새 336셀은 동일 새 commit/stage에서만
    비교하고 과거 stitched graph와 섞지 않는다.
- **의사결정 근거/적용 원칙**:
  - planner 후보를 닫지 않고 동일 자원 조건을 만들어 측정 confounder를 제거했다.

## heavy-hitter 개수만으로는 동일한 planner plan을 증명하지 못함

- **상태**: 하네스/회귀 해결, 실제 Docker runtime-explain receipt 대기
- **환경/조건**:
  - one-pass cold/warm phase와 4 planner 비교.
- **재현 절차**:
  - 기존 `parse_instructions`는 `fed_opcode:executed_count`만 fingerprint로 저장한다.
  - 서로 다른 operand, placement, FType, control-block 위치를 가진 plan도 opcode count가 같으면 동일하다고 판정된다.
- **관측 증상**:
  - “4개 planner가 다르게 작동하는가”, “DP/MinST cold/warm compile이 동일한가”를 기존 row만으로 증명할 수 없었다.
- **원인 분석**:
  - runtime plan 자체가 아니라 실행 후 heavy-hitter aggregate를 plan identity로 사용했다.
- **해결 요약**:
  - typed Docker lifecycle의 모든 SystemDS 실행에 `-explain runtime`을 추가한다.
  - explain block 전체에서 phase directory와 scratch process/thread identity만 정규화하고 SHA-256을 계산한다.
  - cold/warm의 heavy-hitter fingerprint와 exact normalized runtime-plan SHA가 모두 동일해야 cell을 accept한다.
  - row에는 runtime-plan SHA와 compiled FED opcode fingerprint를 별도로 저장한다. Warm primary metric은 SystemDS가
    보고한 execution time이며 explain 출력은 execution timer 시작 전 compile 단계에 발생한다.
- **수정 파일**:
  - harness `experiments/code/distributedExpNew.sh`
  - harness `experiments/run_LAN_docker.sh`
  - harness `experiments/tools/run_one_pass_performance.py`
  - harness `experiments/tests/test_one_pass_performance.py`
  - harness `experiments/tests/test_g007_harness.py`
- **검증**:
  - RED: `/tmp/g007-runtime-plan-fingerprint-red-20260802.log`.
  - GREEN: `/tmp/g007-runtime-plan-fingerprint-green-r2-20260802.log` — 9/9 PASS.
  - same heavy-hitter/different runtime plan fixture가 fail-closed하고, cold/warm phase path 및 scratch PID만 다른 plan은
    동일 SHA로 정규화되는 것을 검증했다.
  - 실제 local `-explain runtime` 출력 parser probe SHA:
    `6ef2a829b4451d00c74d83a506fb0b90da4a74f919cf1d4aa21352affec46f3f`.
- **잔여 이슈**:
  - 새 Docker canary에서 phase-specific output path 외의 비결정적 토큰이 남는지 확인한다. 남는다면 의미 없는 정확한
    run-local identity만 좁게 정규화하고 opcode/operand/placement/FType은 절대 지우지 않는다.
- **잠재 회귀 위험**:
  - runtime explain 형식 변경 시 parser가 실패한다. 감지 방법: marker cardinality 1, `PROGRAM`, `MAIN PROGRAM`,
    blank terminator를 모두 요구하고 raw bundle을 보존한다.
- **의사결정 근거/적용 원칙**:
  - plan을 추측하거나 runtime에서 보정하지 않고 compiler가 실제 배출한 전체 runtime program을 증거로 사용한다.
