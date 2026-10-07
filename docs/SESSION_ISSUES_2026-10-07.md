# 세션 이슈 — 2026-10-07

## L2SVM 최신 main 통합 및 runtime 비용 fingerprint — 해결·검증 완료

- **통합 기준**: L2SVM 수정 `7945e2aab1`과 origin/main `241b9c491a`를 `7f35faa05d`로 병합했다. 기존 후보·privacy·TW/TR·함수 경계와 10m/60m solver 한도는 유지한다.
- **통합 회귀**: Java 35클래스253건 중252 PASS/1 ERROR/skip0, Python56/56 PASS, Maven package PASS. 대형 PUBLIC L2SVM production optimizer가 기본3GB JVM에서 canonical objective bits4652134937446297763, 실제 최대9,028,800/누적19,349,167 cells로 통과했다. 테스트 중 Java source 변경0이다.
- **별도 기존 오류**: campaign은 L2SVM 다음 PUBLIC LOGREG에서423,588,286-cell separator 한도 오류를 낸다. `241b9c491a`의 `ExactPhysicalModel.java`만 현재 dependencies 위에 올린 별도 baseline overlay도 동일 LogReg fixture에서436,840,500-cell raw input 한도 오류를 낸다. 전체 clean baseline 빌드의 증거는 아니지만 기존 helper에서도 실패함을 확인했다. input과 separator 크기는 서로 다른 단계이므로 숫자 차이를 성능 변화로 해석하지 않는다. campaign의 뒤 ALS/StepLM은 도달하지 않았다.
- **새 runtime 증상**: `run_LAN_docker.sh --joint-boundary-e2e --planner local --canonical-proof --case l2svm_true_01`에서 실제 학습 완료 후 probe가 `costSurfaceMatches=false`로 실패했다. Objective bits4620708460999506657, selected states, sharing lifetime은 재구성과 일치한다. 전체 surface fingerprint 비교는 완화하지 않고 원인을 조사한다.
- **원인 확정**: 같은 immutable analysis에서 runtime 없이 surface를3번 만들면 fingerprint와1,218 contributions/247 transfer keys/21 groups가 같다. 실제 Docker의 단계별 해시에서 analysis/candidates/domains/hard는 같고 factor0~974도 동일하지만, l2svm75의 placement Y→TW Y compiled-transfer부터 수치가 달라진다. 이 단계의 factor 수는1,330→1,328, 전체1,360→1,358이다. `estimatedBytes()`가 runtime/recompile 후 변경된 `Hop.getOutputMemEstimate()`를 다시 읽고, bytes가 group key에도 포함되므로 비용과 그룹 수가 변한다. 단순 해시 순서 문제가 아니다.
- **최소 수정 계획**: 분석 생성 시 occurrence별 memory/payload 추정 입력을 immutable fact로 고정하고 exact transfer helpers가 해당 값을 사용한다. 기존 initial estimate·unknown-shape/multi-return fallback·후보·비용 ownership을 보존한다. 먼저 live Hop 추정치를 변경했을 때 surface가 바뀌는 회귀를 만들고, 수정 후 fingerprint/factor count/canonical objective 불변 및 다른 초기 추정치의 구분을 확인한다. Runtime evidence는 같은 strict proof로 다시 실행한다.
- **수정 범위/위험**: 현재 진단은 비용 표의 변경과 fingerprint 순서 불안정을 구분하는 것이다. 선택한 한 계획의 objective 일치만으로 모든 후보의 비용 surface가 같다고 주장하면 안 된다. Frozen 실행과 실패 원본을 보존하고 원인별 회귀 및 동일 Docker 재실행으로 감지한다.
- **최종 수정**: `f0f919f023`에서 analysis별 immutable memory/shape/nnz·multi-return 및 function source/read fallback을 고정했다. 기존 numeric precedence, nullable synthetic key fallback, foreign key/pair 거부를 유지한다. `PlacementAnalysis.java`, `ExactPhysicalCostModel.java`, `ExactPhysicalCostSurfaceEstimateSnapshotTest.java`, Docker class preflight가 수정 파일이다. 후보/합법성 authority는 바꾸지 않는다.
- **회귀**: frozen baseline의 새2건이 모두 실패하고 수정 후 통과했다. 서로 다른 초기 estimate는 같은 structural fingerprint 아래에서도 다른 transfer cost를 만들고, 동일 analysis의 이후 transfer estimate 갱신은 surface를 바꾸지 않는다. 독립 검토 blocker0. 최종 중앙19클래스84/84·Python56/56·package PASS, Java source변경0이다. 대형 L2SVM 후보59,429, 비용 bits4652134937446297763, 실제 저장9,028,800/19,349,167도 그대로다.
- **최종 Docker**: `l2svm-local-final-r3`, `l2svm-global-final-r3`의 true/false/일반 학습6건 및 `sharing-final-r3`의8건이 모두 PASS다. 각 selected plan의 objective·전체cost fingerprint·states·lifetimes가 재구성과 같고 fallback/repair 및 audit 위반0이다. 일반 학습8계수 CP최대오차8.4134e-17. Invariant는 GET/creation/PUT 각1회, updated·PHI·flat은 서로 다른3개 버전에 각3회다. Local/Global 결과가 같고 snapshot 수정 전 초기 objective도 유지한다.
- **빌드 증거**: 3개 final run의 main/test source(1,652/1,949파일), main/test class/resource(4,369/2,851파일), dependencies316파일 전수 SHA가 현재 빌드와 같다. 실제 source변경0 검사는 Java3,590개다. 단일 Docker 기능 실행의 compile 시간은 latency A/B로 해석하지 않는다.
- **잔여/위험**: PUBLIC LogReg의 별도 capacity 실패는 미해결이다. 이번 수정이 모든 workload의 메모리 제한 내 완료 또는 임의의 operator-cost mutation 불변을 보장하지 않는다. 분석 간 estimate 오염은 immutable identity map과 다른 초기 추정치 회귀로, synthetic/function 경계 누락은 기존 함수·multi-return 관련 회귀로 감지한다.
- **증거/보고서**: [L2SVM 보고서](L2SVM_EXACT_CAPACITY_2026-10-07_KO.md), [검증 JSON](experiments/l2svm-exact-capacity-20261007/validation.json), `/grid/3/cofee-lm-sweep-mchoi-20260914/l2svm-exact-capacity-20261007/`. 아래 항목은 이전 조사 단계의 기록으로 보존한다.

## L2SVM canonical 진단의 encoding 우회 — 초기 조사 기록, 위 최종 결과로 대체

- **게시 완료**: main HEAD `8ae75aff00cf790d1afa3820b3b7ce9a2492d04d`의 원격 일치를 확인했다. 이후 `fix/l2svm-exact-factor-20261007`에서 작업한다.
- **증상/원인**: fresh certificate8건 중7 PASS/1 ERROR. `ExactPhysicalModel.analyze()`가 기존 hard observation encoding을 사용하지 않아 realization-support/input-authority의9613×2010=19,322,130-cell 원본 factor를 입력 한도에서 거부했다. Production optimizer와 다른 경로다.
- **해결 설계**: `analyze/solveLegalityOnly`에 기존 encoded hard factors 및 exact reduction/compaction을 사용한다. 원래 decisions로 결과를 투영한 뒤 canonical hard factors를 재검사한다. Canonical factor API·후보·비용·privacy·boundary·한도는 유지한다.
- **현재 검증**: immutable 게시 JAR의 encoded reduced legality는 기존10m/60m에서 통과하고 canonical hard=0이다. 241 decisions/59,429 alternatives는 유지된다. 그러나 비용 surface를 포함한 실제 optimizer는 별도로 `EXACT_VE_FACTOR_CELL_OVERFLOW`를 발생시킨다(`prepare:1580` separator, `optimize:87` 일반 compacted 경로). Cost encoding 및 elimination order의 추가 문제를 조사하며 첫 오류 해결만으로 전체 성공을 주장하지 않는다.
- **위험/감지**: auxiliary assignment를 physical selection으로 잘못 반환하거나 분해 동치 오류를 놓치는 위험. 작은 raw/encoded oracle, 반환 길이, canonical 재검사 및 실제 optimizer recost로 확인한다.
- **추가 원인/진행**: shared-source에서 canonical required-output support보다 강한 exact rule 일치를 요구하고, 출력 reference가 header만의 함수라고 가정했다. 공통 support identity에 맞추고 output별 header를 분할했다. Factor4.9m까지 압축해도 separator15.26b로 실패한다. 조건부 output relation overlay는 factor를 늘려 되돌렸고, weighted-fill·불필요 축 제거·GAC의 단독 효과도 부족해 채택하지 않았다. 현재 native/supply header 및 반복 predicate 중복을 조사한다. 후보·canonical cost·제한은 유지한다.
- **새 검증**: Java14클래스77/77, Python56/56 PASS. Docker 실제 builtin L2SVM Local·Global 모두 자동 선택/실행/CP계수/canonical proof PASS. 8계수 최대오차8.4134e-17, objective21.385960545366007ms, fallback/repair0. PRIVATE_AGGREGATE192×8 fixture의 성공이며 별도 대형 PUBLIC metadata production capacity는 여전히 미해결이다. 증거: `l2svm-exact-capacity-20261007/{correctness-r1,ml-l2svm-local-canonical-r1,ml-l2svm-global-exact-r1}`.
- **대형 capacity 후속 설계**: 기존 sparse kernel의 projection 이전에 dense logical separator를 int로 계산하는 사전 검사가 실제 저장량을 과대 요구한다. Overlay에서 projection-first 실행을 사용하자 대형 optimizer의 canonical hard/objective 검증이 통과했다(objective1014.5096925175934ms). Dense 예측 최대15.258b/누적34.434b와 달리 실제 최대9,028,800/누적19,349,167 cells다. 초기 trace의 finite-entry 수를 저장 배열 길이로 혼동한3,905,024/8,347,373 집계는 dense 전환을 반영해 바로잡았다. 따라서 새 solver나 header 분해 대신 인증된 dyadic 경로에 실제 저장량 budget을 적용한다. Map 삽입·배열 할당 전에10m/60m를 검사하고, 한도를 넘는 dense 전환은 금지한다. 일반 solver 계약·후보·canonical cost는 유지한다. Production 코드와 부정 회귀는 구현·검증 중이다.
- **Production 첫 검증 완료**: 기본3GB JVM에서 Java12클래스91/91 PASS, Python56/56 PASS. 최종 구현의 L2SVM 실제 최대9,028,800/누적19,349,167 cells와 canonical objective bits4652134937446297763을 확인했다. Overlay의 저장량과 혼용하지 않는다. 원래 후보59,429개를 유지했다. 인증·scope/assignment parity·한도 초과·dense 전환 거부 및 join 회귀를 포함한다. Java source변경0. 최신 main과 통합한 검증은 다음 단계다.
- **기록**: [L2SVM 보고서](L2SVM_EXACT_CAPACITY_2026-10-07_KO.md). 아래 대형 solver 미해결 기록은 이전 검증 시점의 결과로 보존한다.

## Derived supply main 게시 후 L2SVM 해결 — 초기 게시 기록, 위 최종 결과로 대체

- **요청/순서**: 검증한 sharing 변경을 main에 먼저 commit/push한 뒤 대형 L2SVM Exact 한계를 해결한다. 원본 변경 commit `3fe367b213`, 새 통합 worktree `/home/mchoi/w1357-derived-supply-main-20261007`.
- **병합**: main `73d1eb…`와 병합할 때 세션 문서 두 파일만 충돌했다. 양쪽 모든 줄을 보존해 해결했다. Java 소스는 자동 병합됐고 최초 17클래스91건, Python49건, flat Local/Global Docker2건이 통과했다. 이후 main `fbfd4d790f`의 StepLM closure도 포함해 최종 검증한다.
- **의미상 충돌**: Heuristic/FedAll required-output 인덱스의 exact rule 비교와 공통 모델의 같은 owner/durable-map 지원 규칙이 다르다. 해당 support 비교만 일치시키고 selected receipt·DIRECT·boundary 제약은 유지하는 수정과 회귀를 진행한다. Cost-based Local은 이 selector를 호출하지 않는다.
- **최종 게시 전 검증**: StepLM 및 selector 호환 수정까지 포함해 Java23클래스134/134, Python52/52, Maven package, 자동 DML8/8, Heuristic worker6/6이 통과했다. Required-output 비교 외 exact receipt/physical/logical 경계 유지 여부도 별도 검토했다. 두 final 자동 run의 frozen production inventory가 현재 source와 같고, Heuristic JAR의 지원 클래스도 현재 compiled class와 같다.
- **잔여/다음 순서**: 일반 fast-forward push를 확인한 뒤 L2SVM을 재현해 exact factor 표현을 수정한다. 이전 대형 campaign 오류를 이미 해결한 것으로 보고하지 않는다.
- **보고서/증거**: [게시 기록](DERIVED_SUPPLY_PUBLICATION_2026-10-07_KO.md), `/grid/3/cofee-lm-sweep-mchoi-20260914/derived-supply-main-20261007/`.
- **회귀 위험**: 다른 값·layout의 지원 혼동과 upstream closure 손실. Output identity 음성 검사, alias/partition/loop closure 회귀, 실제 자동 선택·runtime identity 검사로 감지한다.

## Row 분해 제외와 joint alias 의존 축소 — 완료

- **요청/기준**: 사용자가 row 분해를 롤백하고 보고서의 다음 단계 진행을 요청했다. `129a2ad268`과 fetch한 `origin/main`이 동일하고 작업 트리는 깨끗했다. row 분해는 production에 없고 별도 복사본의 계측뿐이므로 되돌릴 실행 코드는 없다. 음성 계측 기록은 재현 근거로 보존한다.
- **문제**: 실제 multiLogReg의 `S*Grad` joint truth factor는 25,006,592셀이다. 단순 row 분해는 전체 표현을 25,029,616→75,142,200셀로 늘리며 최대 factor를 줄이지 않는다. 원인은 Grad loop-back alias 경로의 선택 의존성이다.
- **사전 계획/판단 근거**: 기존 realization-support hard factor가 parent→alias와 alias→source의 정확한 reference 일치를 이미 강제한다. 모든 reachable pool query에서 내부 alias의 모든 owned clause가 동일 retained owner의 단일 reference로 전달되고 exact pool override가 없음을 인증할 수 있으면, 해당 alias 선택 축 하나를 joint predicate에서만 생략한다. 선택된 retained source의 실제 reference로 같은 query를 재구성하며, 기존 active-query cycle 검사와 invariant-pool shortcut을 그대로 유지한다. 원래 decision domain과 다른 hard/cost factor는 유지한다. row 분해·runtime fallback·privacy 완화·자의적 후보 cap은 추가하지 않는다.
- **동치성 범위/회귀 계획**: 개별 joint predicate의 모든 불법 tuple 값까지 같다는 계약이 아니다. 기존 support 제약 S를 포함한 `S+H_old == S+H_projected`를 원래 작은 완전 assignment 공간에서 확인한다. support-invalid에서 H가 달라지는 tuple도 검사해 전제를 검증한다. canonical model 전체 비용/합법 집합, exact 최적해·동률·조건부 fixed→free 복원, 부분 proof와 encoding projection을 유지한다. 기존 unprojected evaluator를 테스트 oracle로 보존한다.
- **구현 경계**: 우선 consumer/reader가 아닌 TR/TW/placement alias 하나만 인증·생략한다. 모든 query가 인증되지 않으면 기존 표현을 유지한다. 이는 정당한 동치 표현 선택이며 합법 후보 제거가 아니다. Grounding 변경과 model 통합을 분리하고 root만 Maven/shared target을 사용한다.
- **검증 계획**: 회귀 RED→GREEN, 관련 solver/physical tests와 package, source/class SHA, `git diff --check` 후 동일 Docker image/input/privacy에서 multiLogReg/l2svm/lmCG 학습을 실행한다. 전체 계수·audit·최종 modeled 비용 및 실제 최대 factor 셀 수를 게시 baseline과 비교한다.
- **잔여 이슈/잠재 회귀**: support 제약을 제외한 단독 evaluator에 projection을 사용하면 불법 계획을 허용할 수 있다. source reference가 여러 owner로 갈라지는 경우, exact-pool override, missing receipt, 미할당 source, cycle, reader activation guard를 잘못 처리하면 동치성이 깨진다. 위 반례와 전체 hard-cost parity로 감지한다.

## Support 조건을 이용한 단일 alias projection — 완료

- **변경 파일**: `JointValueMapRelations.java`는 reachable pool query마다 alias의 모든 support clause가 동일 retained owner의 단일 reference로 전달되는지 인증한다. `ExactPhysicalModel.java`는 인증된 alias 축 하나를 joint scope에서 제거한다. `JointAliasProjectionTest.java`는 기존 evaluator를 oracle로 사용한다.
- **증명 경계**: support 제약 `S`가 incoming reference와 선택된 alias realization의 일치, 그리고 alias의 outgoing reference와 선택된 target realization의 일치를 강제한다. 따라서 `S`가 참이면 생략한 alias의 선택을 따라간 결과와 실제 선택된 target을 직접 따라간 결과가 같다. 원래 `PoolQuery(reference,supplier,origin)` cycle token을 유지한다. `S`가 거짓인 tuple에서는 단독 `H`의 값이 달라도 전체 hard cost는 여전히 infinity다. 이 조건부 동치성을 standalone predicate 동치로 사용하면 안 된다.
- **인증하지 않는 경우**: reader/consumer 자체, transient read/write/placement 외 연산, exact-pool override, source reference 여러 개, clause마다 서로 다른 target owner, scope 밖 target, 자기 자신 target, 두 번째 동시 projection. 이 경우 기존 표현을 그대로 유지하며 어떤 후보도 삭제하지 않는다.
- **현재 회귀 증거**: 작은 loop fixture에서 joint 1,872→936셀. 원래 1,872개 tuple 전부에 대해 `S+H` raw-bit parity를 확인했다. 별도 reference-support oracle의 312개 유효 tuple에서도 `H`가 동일하다. support-invalid 112개 tuple에서 standalone `H`가 달라지는 것을 확인해 전제가 실제로 필요함을 검증했다. projected partial-truth certificate 936개와 모든 completion을 대조했다. reader-owned loop fixture의 160개 canonical factor는 전체 raw truth가 같다.
- **검증 한계**: 위 작은 fixture에서 cycle-entry guard를 제거한 isolated mutant는 감지되지 않았다. 따라서 cycle guard mutation 검출을 증거로 주장하지 않는다. 기존 branch/loop/function 부분 판정 및 물리 계획 14건은 통과했고, 원래 cycle token 보존은 별도 코드 검토로 확인했다. exact solver·조건부 복원·decomposition 회귀 및 실제 Docker 학습은 아래 최종 검증에서 통과했다.
- **독립 검토 반영**: support proof가 다른 제약에 가려지지 않도록 requiredInputSupport 직접 oracle를 추가했다. missing decision domain은 projection 후보 정렬 전에 기존 IllegalArgumentException으로 검출한다.

## 실제 ML 학습 및 최신 main 통합 — 학습 검증 완료

- **기준 통합**: 첫 확인 당시 `129a2ad268`이었으며, 이후 fetch에서 `0eb1ae4410`의 loop/function privacy golden 테스트·문서 변경을 발견해 fast-forward 반영했다. 들어온 production 변경은 없다. 해당 `EarlyPrivacyPruningLegalSpaceParityTest` 4건도 최종 회귀에 포함했다.
- **실행**: `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e --run-id joint-alias-candidate-01 --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/boundary-seed-ml-20261006 --case ml_logreg --case ml_l2svm --case ml_lm --timeout-seconds 2400 --case-timeout-seconds 300`. main은 새로 컴파일·동결한 source/classes를 사용했다. Docker의 기존 physical proof 10건은 게시 baseline의 동결 test source/classes를 명시적으로 사용했고, 새 regression은 Maven에서 별도 검증한다. 정확한 argv는 `target/joint-alias-evidence/docker-command.json`에 있다.
- **학습 결과**: 3/3 PASS. CP/FED 전체 계수 16/8/8개 일치, audit mismatch·missing 및 runtime conversion 위반 0. input/fixture/dependency/image 해시 모두 `legal-prefix-main-publication-r2`와 일치한다. 세 workload의 최종 modeled upper도 각각 122.26631334184357 / 21.385960545366007 / 21.2628479582568로 기존과 같다.
- **LogReg 병목 변화**: `S*Grad`의 else `_PLACEMENT Grad` domain38 축을 인증했고 truth category17 축이 사라졌다. truth 25,006,592→1,470,976셀, partialCalls 2,547,709→150,189, freeze 16.246→2.369초, 최종 planner checkpoint 37.115→15.764초, 전체 compilation 89.101→74.622초다. runtime 학습은 3.847→4.843초다. 공유 호스트 단일 비교이며 모든 구간이 빨라졌다고 주장하지 않는다.
- **큰 분석 fixture와 실제 workload 차이**: `ExactInputAuthorityOptimizationTest.logregAnalysis()`는 numclasses2, 50,000×2,100, maxi30이고 실제 검증은 numclasses3, 192×8, maxi10이다. 전자에서는 line235 Grad alias가 projection 후보로 나타나지 않고 X write만 줄었다. 실제 학습에서는 목적한 Grad 축이 줄었으므로 인증 조건을 추가로 완화하지 않았다.
- **잔여 병목/제약**: 공통 analysis 55.180초, dense truth 1,470,976셀은 남는다. 한 joint당 내부 alias 하나만 인증한다. generic operator product나 모든 dense 저장을 제거한 것이 아니다. 전역 최적해 증명이 아니라 기존과 같은 gap 1.43%에서 TARGET_REACHED다.

## 빌드 provenance — 추가 테스트 문구 변경 감지 및 재검증

- **증상/원인**: 363-test Maven 실행이 시작된 뒤 test agent가 신규 회귀에 명시적 assertion 하나를 추가했다. root의 source snapshot 검사가 해당 test 파일의 SHA 변경을 감지했다. production 변경은 없었다.
- **조치**: 변경 사실을 숨기거나 snapshot을 덮어쓰지 않았다. 최초 build/source snapshot과 상태를 보존하고, 최종 source를 다시 동결해 `JointAliasProjectionTest` 3건과 package를 다시 실행했다. 최초 363건은 failure/error/skip 0이며, 최종 dedicated 결과·SHA 일치는 별도 final-test/final-build 기록으로 확인한다.
- **재발 방지/검증 근거**: root만 Maven/shared target을 소유한다. source freeze 이후 agent 수정 금지를 재확인했다. Docker 동결 main source/classes와 최종 build도 hash 대조한다.

## 최종 결과 — 완료

- **Java**: 최신 main 통합 후 49개 클래스 363건 PASS, failure/error/skip 0. 테스트 문구 변경 이후 신규 회귀 3건을 최종 source로 다시 컴파일·실행해 PASS했고 package도 성공했다. 최종 source snapshot 변경 0, Docker 동결 main source 1,651개·class 파일 4,357개가 최종 build와 해시 일치한다.
- **독립 검토**: 세 변경 Java 파일에 대해 blocking finding 0. 원래 support 제약과 cycle token 보존, partial evaluator, exact/regional parity를 확인했다.
- **정적 확인**: Java 전체 재컴파일(타입 검사 및 기존 unchecked lint 포함), `git diff --check` 통과. full checkstyle/RAT는 기존 targeted package 명령과 같이 skip했고 별도 전체 정적 분석 실행으로 주장하지 않는다.
- **재현 artifact**: `target/joint-alias-evidence/{regression.log,final-test.log,final-build-source-sha.json,final-build-status.json,docker-command.json}`. Docker raw evidence는 `/grid/3/cofee-lm-sweep-mchoi-20260914/boundary-seed-ml-20261006/joint-alias-candidate-01/`. 커밋되는 [검증 JSON](experiments/joint-alias-20261007/validation.json)에 hash·비교 수치·범위를 보존한다.

## StepLM·LogReg·GLM 공통 병목 진단 — LogReg로 범위 변경

- **새 요청**: 사용자가 StepLM, LogReg, GLM 세 workload가 모두 느리므로 공통 원인을 확인하고 통합 수정하도록 요청했다. 일곱 가지 최적화를 가정만으로 한꺼번에 적용하지 않고, 동일 baseline에서 측정한 원인별 변경을 비교해 채택한다.
- **기준**: `57933f328c` 및 새 fetch의 origin/main 동일. 이전 LogReg의 analysis55.180초는 aggregate 측정이며 내부 memoization hotspot을 입증하지 않는다. StepLM/GLM의 과거 compile-only·다른 builtin 확장 결과를 이번 actual-training baseline으로 혼용하지 않는다.
- **실행 계획**: 기존 Docker-only joint-boundary harness에 현재 builtin StepLM/GLM을 opt-in 추가한다. 동일 192×8 X, PRIVATE_AGGREGATE, 3 ROW workers, public local labels, 고정 image/resources에서 실제 학습과 CP 전체 결과 비교를 수행한다. 진단용 FED coordinator JFR을 별도 opt-in으로 보존하고 analysis/model/cost/optimizer/runtime phase와 hot stacks를 나눈다. baseline source/classes를 동결한 뒤 공통 hotspot을 겨냥한 작은 수정과 회귀를 적용한다. 효과 없는 ablation은 production에서 제외한다.
- **채택 조건**: 합법 선택과 support/cost 계약을 보존하고 작은 전수 oracle·음성 회귀·fixed/free 복원을 통과해야 한다. 실제 학습의 모든 계수와 StepLM 선택 결과가 CP와 맞고 audit/runtime conversion 위반이 없어야 한다. 같은 입력·image·privacy·리소스의 반복 A/B에서 시간 및 작업량 이득을 확인하며, anytime upper/lower/gap을 함께 비교한다. 단순 후보 수 감소만으로 채택하지 않는다.
- **구현 경계/소유권**: harness와 Python tests만 별도 agent가 수정한다. root만 production Java와 Maven/shared target을 관리한다. 소스 동결 이후 수정 금지를 유지한다. runtime fallback·privacy 완화·임의 후보 cap·builtin 알고리즘 대체는 도입하지 않는다.
- **예상 위험**: loop/branch/function 문맥이나 analysis snapshot 경계가 다른 증명을 캐시하면 합법성이 달라질 수 있다. 조건부 결과는 경계와 free-set을 포함하거나 매번 재구축한다. 압축 표현/순서 변경이 local DP 탐색 경로를 바꿀 수 있으므로 비용 품질과 전체 컴파일 시간을 따로 확인한다.

### 측정 기반 수정 계획 A/B

- **A 관측**: 실제 LogReg FED JFR 2,873개 execution samples 중 common analysis 2,024개, single-partition proof 경로 901개다. 약 44.5%는 해당 분석 내부 표본 비율이며 wall time 비율로 단정하지 않는다.
- **A 사전 회귀/설계**: 기존 synchronous 전체 inventory scan을 독립 oracle로 한 250개 무작위 그래프, 미접지 cycle, 긴 cycle, 늦은 multi-partition backedge, 다중 source conjunction, missing/동등 reference 회귀 9건이 baseline에서 통과했다. invocation 안의 불변 inventory에서 세 possibility bit의 단조 최소 고정점을 reverse-dependency worklist로 계산한다. 초기 모든 노드를 enqueue하고 self-edge와 전체 bit 변경을 보존하며 commit 간 cache 재사용은 하지 않는다. 최종 enum 우선순위도 유지한다.
- **B 관측/계획**: GLM CP common analysis가 수 분째 종료되지 않아 실행 중인 소유 JVM에 60초 JFR을 부착했다. 2,333개 execution samples에서 Environment.stableKey 862개, Definition.stableKey 301개, Environment 생성자 204개가 application leaf다. 실행 도중 붙인 profile이므로 깨끗한 시간 비교 자료로 쓰지 않는다. 불변 환경의 같은 정의 재기록/중복 복사/키 계산을 줄이는 정확한 변경을 검토한다. provenance·call context·값/읽기 map equality와 정렬 계약을 유지한다.
- **별도 correctness 문제**: baseline StepLM은 analysis 18.241초, model 1.447초 이후 EXACT_VE_NO_FEASIBLE_ASSIGNMENT로 실패한다. CP는 성공했다. 이를 성능 timeout과 혼용하지 않고 support/factor 원인을 별도 진단한다.

### 범위 변경 — LogReg 집중

- 사용자가 StepLM/GLM은 다른 담당자에게 맡기고 LogReg에 집중하도록 변경했다. 소유 mount를 확인한 GLM 두 컨테이너만 중지했다. 이 중단은 workload 실패나 timeout 수치로 집계하지 않는다. StepLM 진단 agent도 추가 작업과 소유 실행을 종료했다.
- GLM 환경 표현 최적화 B 및 회귀는 `target/three-workload-evidence/handoff-glm-environment.patch`에 보존하고 현재 production/test 변경에서 제외했다. baseline 회귀 4건 및 후보+joint reaching-definition 회귀 13건은 통과했으나 GLM 성능 채택 검증은 미완료다.
- StepLM 인계: alias projection을 비활성화해도 동일 실패다. binary arc consistency가 canonical input-authority factor 1123(m_lm)·1143(m_lmCG)의 유일 셀이 infinity여서 domain을 비운다. `ExactPhysicalModel.inputAuthorityProducts`에서 relocation authority가 먼저 생겨 sole-input action-free DIRECT_FOUT을 누락한다. 진단용 수정은 solver를 통과시키지만 canonical relocation completion에서 다시 실패하므로 그대로 채택하면 안 된다. 다음 담당자는 `ExactPhysicalSelection`의 demand completion과 direct authority 의미를 일치시켜야 한다. raw run은 `three-workload-ablation-20261007/diag-steplm-{unprojected,reduction-trace,single-fed-input}-57933f-*`, diagnostic overlays도 같은 root다.
- LogReg A 첫 진단 실행은 전체 계수 16개와 audit를 통과했고, 최종 upper/lower/gap 및 assignments 17,336,646·merges 4,566이 baseline과 같다. analysis 53.872→31.044초, compilation72.507→44.005초지만 진단 실행 간 호스트 부하 차이가 있으므로 이 비율을 최종 효과로 단정하지 않는다. JFR 없는 순차 교차 실행으로 채택 효과를 다시 측정한다.

## Heuristic legality main publication — 통합 검증 완료

- **문제/상태**: 이전 Heuristic legality 구현 `93170f9dfb`를 최신 `origin/main` `57933f328c`에 통합했다. 새 worktree는 `/home/mchoi/heuristic-main-publication-20261007`이며 기존 workspace는 수정하지 않는다.
- **원인/해결**: main의 support-certified joint alias projection/partial truth와 Heuristic 공통 row predicate가 동일 factor에서 만났다. alias projection과 기존 support factors를 유지하고 completed cost만 공유 predicate를 사용한다. 물리 입력 위치가 하나 이하인 경우 completed cost와 partial truth 모두 정렬 의무가 없도록 맞춘다. 두 경로가 다르면 partial truth가 합법 leaf를 금지하는 잘못된 가지치기가 가능하다.
- **수정 파일**: `ExactPhysicalModel.java`, `JointPartialTruthTest.java`. 기존 `JointValueMapRelations.java` 및 `PlacementRelationClosure.java`의 양쪽 독립 변경도 보존했다. Oct 6 이슈 문서의 양쪽 기록을 모두 보존했다.
- **검증**: loop matrix-scalar 소형 fixture에서 joint consumer와 단일 physical FED 입력의 존재를 확인하고 모든 partial certificate/완성 tuple 및 frozen table parity를 검사했다. local operand 시도는 joint factor가 없어 실패한 fixture로 기록 후 제거했다. alias projection, support-prefix, closure, greedy, common legality 등 19개 클래스 100/100 및 최종 Maven package가 통과했다. Docker Heuristic 6/6, 소형 ALS CP/FedAll 200개 값 오차0, 소형 builtin LogReg/L2SVM/lmCG 3/3도 통과했다. runtime audit/conversion 위반0. 최종 frozen 소스/클래스 및 JAR 일치를 확인했다. 상세는 `HEURISTIC_MAIN_PUBLICATION_2026-10-07.md` 및 `experiments/heuristic-main-publication-20261007/validation.json`에 기록했다.
- **원칙**: runtime fallback/repair, privacy/TR-TW/geometry 완화 없이 planner의 실행 합법성 규칙을 일치시킨다. factor scope/auxiliary encoding은 확대하지 않는다. 대형 모델은 실행하지 않는다.
- **잔여 이슈**: StepLM closure 비수렴은 20×5 compile-only 기존 테스트에서 **이번 통합본에서도 229.971초 후 재현**했다. `f=626/632`, `changed=[281,423]`, iteration1244의 기존 signature와 일치하며 selector/optimizer 이전 common closure에서 실패한다. 이는 통과한 100개 회귀와 별도인 미해결1건이다. ALS/StepLM Exact overflow는 여전히 `0146f043e0`의 과거 근거이며 최신 재현으로 취급하지 않는다. Heuristic L2SVM full fixture 중단은 별도 대형 metadata 검증 공백이다. 실제 소형 L2SVM 학습 성공과 모순되지 않는다. WDIVMM 음성 fixture 및 read/source 실제 factor 경로는 검증 공백이다.
- **잠재 회귀 위험/감지**: support 제약이 필요한 alias projection을 독립 predicate 동치로 오해하면 legal space를 바꿀 수 있다. 기존 exhaustive support-conjunction parity/alias/partial-truth 검사를 유지하고, 소형 ML 전체 계수 CP/FED 비교 및 runtime audit로 통합 실행을 확인한다.

## StepLM CFG closure 비수렴 — 보고된 주기2 오류 수정, 실제 CSV 학습 미완료

- **환경/증상**: origin/main `93706bbaa9`의 20×5 StepLM compile-only 재현에서 `f=626/632`, changed ordinals281/423의 주기2 진동. 이전 publication에서 229.971초 후 비수렴 예외를 확인했다. 새 worktree `/home/mchoi/steplm-cfg-closure-20261007`에서 조사하며 기존 workspace는 읽기만 한다.
- **근거/원인**: bounded diagnostic overlay가 `R281 → W282 → R414 → placement415 → W416 → R281` identity 경로에서 snapshot CFG replay와 physical 재생성의 한 단계 지연을 보여 준다. R281과 R414가 CP/FULL의 반대 상태로 교대한다. cbind398은 FULL을 유지하고 single-partition 값도 양쪽 pass가 같아 최초 cardinality 가설은 기각했다. 임시 loop-entry seed가 한 composed pass 후 해제되어 깊은 경로가 안정되기 전에 all-definition 교집합이 적용된다.
- **수정 계획**: 설치 이력과 현재 active seed를 분리한다. entry source가 고정된 seeded composed transfer를 안정될 때까지 반복한 뒤 seed를 해제한다. 그 다음 all-definition transfer가 안정되고 기존 ledger가 모든 reaching writer를 확인해야만 publication한다. pass 상한 증가, 진동 중간 상태 반환, CP 강제 축소, privacy/TR-TW 완화 없이 기존 greatest placement fixed-point 계약을 구현한다.
- **검증 계획/잠재 위험**: 원래 소형 재현, 깊은 identity backedge 및 비호환 backedge, 기존 loop/transient/function·privacy 회귀, incremental/full-recompute parity를 확인한다. 실제 20×5 StepLM B 전체 계수와 S 선택 순서를 Docker CP/DP로 비교한다. 검증 전 해결 완료로 취급하지 않는다.
- **원본 진단**: `target/steplm-evidence/diagnostic-{stages,sources,cardinality,generators}.log`. 진단 overlay만7pass에서 의도적으로 중단했으며 production 반복 한도는 변경하지 않는다.
- **구현/검토 완료**: `PlacementRelationClosure.java`에 설치 이력과 별도인 active seed를 유지하고 seeded 안정화 후 반드시 unseeded all-definition 안정화를 거치게 했다. provisional resolver를 all-definition cache와 분리하고 최초 seed의 geometry 정책을 보존한다. 독립 검토에서 final replay의 late-install 누락을 발견해 신규 seed 설치는 첫 replay만 허용하도록 보완했다. 보완 후 추가 차단 사항은 없다. 오라클/런타임 지원 규칙이 아니라 proof 전달 순서를 수정했다.
- **최종 회귀**: 새 `LoopIdentityBackedgeClosureTest`의 동일 builtin20×5 canonical 회귀는 baseline264.837초 CFG 비수렴→후보30.419초 PASS다. 새4건 전체 PASS, 관련12개 클래스52 PASS/기존 ignore5/failure·error0, Maven package 성공, Python28/28 및 py_compile/diff check 통과. 모든 writer의 exact FULL 관계, incompatible backedge 거부, protected incremental/full parity를 확인했다. Java source는 최종 build 중 변경되지 않았고 동결 Docker source/classes/dependencies도 최종 build와 해시가 일치한다.
- **후단 미해결1 — factor overflow**: 원래 `FederatedPlannerFallbackIntegrationTest.testDpPlansSteplmWithSameNamedFormalTransientBinding` 전체 compile은 최종 후보에서38.065초 후 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 실패한다. CFG 이후 cost model preflight의 `ExactCategoricalSolver.checkedCells` 경로다. 이 테스트를 통과로 집계하지 않는다.
- **후단 미해결2 — 실제 CSV common analysis 지연**: 원래와 같이 X/Y 모두 FED인20×5 full-rank CSV의 baseline/candidate Docker는 모두360초 timeout(rc124), FED planner checkpoint0, 모델 출력 없음이다. CP 학습은 각각0.613/0.673초에 완료했고 S는[3,1,5]다. 입력/fixture/runner/image/dependency hash는 동일하다. 후보136/222/353초 스택은 memo relocation normalization, LoopSeedRevision deep equality, outer relocation binding 재생성에서 진행 중이다. 관찰된 경로는 고친 내부 CFG 주기2와 다르며 별도 outer-pass 구조 처리 병목이다. baseline의 동일 원인이나 실제 학습 speedup을 주장하지 않는다. audit0건은 runtime 검증 미실시다.
- **별도 관측 — local Y 입력**: 첫 baseline Docker fixture는 X FED/Y local이었다. lmCG line129에서 `Final publication has an ungrounded relocation realization: actionPresent=false|sourceLive=true`를 관측했다. 원본과 입력 경계가 달라 최종 A/B는 X/Y 모두 FED로 맞추었으며, 최초 실패와 동결 근거는 보존한다. 후보에서 이 local-Y 오류를 재검증하지 않았으므로 후보에도 남는다고 단정하지 않는다.
- **검증 범위 오류**: 최초 Maven에서 `LoopSeedReplayWideningTest` 전체를 선택해 50,000×2,100 metadata-only 분석1건도 실행했다. 실제 대형 학습은 없었으나 대형 제외 요청을 벗어난 선택 오류다. 최종 실행은 해당 l2svm 메서드를 제외한 소형5개만 명시했다.
- **환경/보존**: 공유 root filesystem 부족을 확인하고 이번 worktree가 만든 target/lib만 grid로 이동·symlink하여 확보했다. 기존 workspace나 다른 파일은 지우지 않았다. 상세 보고서 `docs/STEPLM_CFG_CLOSURE_2026-10-07.md`, 요약 `docs/experiments/steplm-cfg-closure-20261007/validation.json`, 원본 Docker `/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-closure-20261007/`에 결과를 보존한다.
- **main 게시 통합 검증**: 수정 커밋 `30e822dacb` 작성 후 최신 origin/main `73d1eb024f`의 partition-proof worklist/JFR 기능을 병합했다. Java는 자동 병합되고 문서·Python 충돌은 양쪽 동작/기록을 유지했다. 독립 검토에서 의미상 충돌 없음. 통합 Java13클래스61 PASS/기존 skip5, Python30 PASS, package4분12초 성공, source3,578개 변경0이다. canonical StepLM은26.904초 PASS. 대형 metadata는 제외했다. 게시 검증 JSON은 `docs/experiments/steplm-cfg-closure-20261007/publication-validation.json`이다. 원래 Docker 측정은 통합 전 근거이며 실제 CSV 학습은 재실행하지 않았다.
### LogReg worklist — 최신 main 통합

- 작업 도중 fetch한 origin/main이 `93706bbaa9`로 전진했다. heuristic legality와 grounded relocation publication 변경을 모두 보존했다. `PlacementRelationClosure`는 서로 다른 위치라 자동 병합했고, 세션 문서의 append 충돌은 양쪽 기록을 모두 유지했다.
- 통합 전 candidate는 52개 Java 클래스 382건, Python unittest 27건 및 package를 통과했다. Java build 중 source 변경 0이다. 이 결과를 최신 통합본 검증으로 혼용하지 않으며 통합 후 다시 검사한다.
- 최종 actual-training A/B는 동일 최신 main을 양쪽에 넣고 `exactSinglePartitionRealizationProofs` 계산 방식만 바꾼다. 이전 57933f 실행은 진단 근거로만 남긴다.

### LogReg worklist 최종 채택 — 검증 완료

- 최신 main93706bbaa9 기준 ABBA 순차 actual multiLogReg 네 실행 PASS. 평균 analysis33.844530→28.999022초(14.32% 감소), compilation45.315991→38.767572초(14.45% 감소). 입력·fixture·image·runner hash 동일. 공유 호스트 각군2회이므로 모든 원자료와 한계를 보고서에 공개했다.
- CP/FED 전체16계수 최대오차2.22e-16, audit/conversion 위반0. analysis fingerprint 및 시간 제외1224개 DP체크포인트 전체가 같고 최종upper122.26631334184357/lower120.54269578813249/gap1.429881%도 같다. 후보 제거·품질 저하 없이 불변 inventory 안의 증명 전파 중복을 줄였다.
- 최신 통합 Java55클래스401건, Python27건, package PASS. baseline/candidate 독립 fixed-point9건씩 PASS. build 중 source변경0, candidate frozen main source1651개/class-resource4361개가 Maven 결과와 hash 일치. production 및 harness 최종 독립 검토 blocker0.
- JFR evidence 검사에서 nonempty 손상 파일이 통과할 수 있다는 리뷰를 반영해 bounded jfr summary parser 성공도 필수로 했다. 실제 기록 성공과 잘린11바이트 파일 거부 smoke를 확인했다. 비프로파일 학습 경로에는 parser 호출을 추가하지 않는다.
- 상세: [LogReg 보고서](LOGREG_PARTITION_WORKLIST_2026-10-07.md), [검증 JSON](experiments/logreg-partition-worklist-20261007/validation.json). StepLM/GLM 추가 변경은 인계 패치로 보존했으며 현재 production에 포함하지 않았다.

## LogReg 후속 — 전체 컴파일 수십 초 해소를 위한 구조 수정 (진행 중)

- **새 증상/목표**: 사용자는 다른 workload의 planning이1초대인데 LogReg가 여전히40초인 점을 지적했다. 기존 채택은 부분 개선이며 충분한 해결이 아니다. 이전동일run의 lmCG/L2SVM checkpoint1.005/1.847초와 비교하면 최신LogReg checkpoint7.716초도 느리다. 전체컴파일38.768초 중commonanalysis28.999초(74.8%)가 우선병목이다. 타이밍구간을혼동하지않는다.
- **근거/가설**: 기존worklist는 한fixedpoint내fullsweep만줄였다. PhysicalCandidateState가 owner하나commit할때마다 singlePartitionProofs를폐기해 전체realization/역의존graph를새로만든다. WorkerPoolAnchorResolver는이미owner-delta색인을쓰므로이를새개선으로주장하지않으며, 전체mapcopy·querymemo초기화비용은별도로측정한다.
- **수정전계획**: 먼저actualLogReg진단overlay에서기존SearchSpaceMetrics를켜analysisphase/작업량을기록한다(계측시간을성능근거로사용하지않음). single-partition은owner별동등reference마지막slot의미를보존하는revision-localindex를만들고, old/new dependency그래프의변경cone을bottom으로되돌려재계산한다. missingref의reverseedge도유지해나중source추가를전파한다. 물리closureinstance밖cache는없다.
- **검증/위험**: source삭제로cycleground가사라지는경우, reference동일support변경, multipart/unknown뒤늦은추가, missingref추가·제거, 다중commit, owner간동등reference충돌을freshfullrecompute와비교한다. 기존250개독립synchronousoracle도유지한다. 모든legal선택·cost·학습16계수·runtimeaudit를보존하고계측없는Docker반복으로채택한다. 삭제시이전truebit를그대로재사용하는cache는금지한다.

- **CFG 추가 수정 전 근거/계획**: 새 실제 LogReg 진단에서 CFG_REPLAY exclusive 10.253초, 누적 할당 18.406GB를 확인했다. JFR CFG CPU 178샘플 중 candidateRealization 전체 검색 69개, compatibility 문자열 생성 44개다. replay 내부 자연 TreeSet을 기존 canonicalComparator로 바꿔 동일 정렬 키를 재사용하고, 기존 facts와 해당 reader replacementFacts의 정확한 reference 합집합 membership으로 edge 생존을 판정한다. AVAILABLE/executable 필터를 추가하지 않으며 같은 owner의 기존 후보도 유지한다. 두 개선은 각각 동결하여 실제 학습 ablation으로 평가한다.
- **선행 회귀 공백**: 기존 PhysicalGenerationEnvelopeTest 7건 중 4건은 baseline과 새 proof index 양쪽에서 동일 실패했다(3건 origins=null, 1건 null reflection receiver). 기존401건의 구성원이 아니며 이번 변경의 회귀 성공으로 집계하지 않는다. 기존 proof/refinement와 신규 incremental 회귀17건은 통과했다.
- **진단 실행 환경**: metrics-01은 stage 도중 exit120, 원인은 미확정이다. metrics-02는 /grid stage가 Docker daemon에서 보이지 않아 container-run.sh missing으로 실행 전 실패했다. home stage를 사용한 metrics-03은 CP/FED 전체 학습 PASS. 실패 두 건을 성능 자료로 사용하지 않는다.

- **Ablation 중간 판단**: sort-only 및 CFG index 단독 실행은 각각39.49/38.54초로 기준39.79초 대비 아직 효과가 작다. 기존 canonicalComparator가 transient compatibility/proof에 대해서는 긴 normalizedSignature 전체를 literal로 감싸므로, 다른 지원 타입처럼 source/reader/proof 하위 구조를 공유하는 segmented ordering으로 확장해 별도 평가한다. 기존 UTF-16 정렬과 구분자/길이/중복 제거 의미를 완전히 유지하며 서로 다른 anchor/native exactness/Unicode 입력을 legacy 문자열 oracle와 대조한다. 기존 결합 후보와 구분해 측정한다.

- **비용 계산 추가 수정**: JFR에서 최종 planner 9.183초 중 비용 표면 2.931초, optimizer 3.996초를 확인했다. 동일 Alternative의 worker count를 새 visiting set으로 반복 계산하는 경로를 개선한다. PhysicalWorkerCounts 호출 범위에 완료된 root exact 결과만 identity memo로 저장하고, recursive 내부 결과와 caller별 fallback은 저장하지 않는다. 기존 durable anchor early return도 유지한다. 순환 A↔B, fallback 3/7, 공유 support, 동일/동등-but-distinct root와 호출 간 격리를 회귀로 검증했다. 실제 비용/탐색 parity와 성능 채택은 별도 최종 실행에서 확인한다.
- **큰 factor 해석 정정**: metrics-03의 최대 1,470,976 logical-cell factor는 partial proof 9회·leaf 0회·약3.95ms로 전체 zero를 인증했다. 현재 wall-time 병목으로 지목하지 않는다. shared preparation 0.382초, seed boundary 40회가 약2.370초다. assignments 총수와 실제 시간 병목을 구분한다.


### LogReg 후속 최종 검증 — 개선 채택, 1초 목표 미달

- **통합**: origin/main의 StepLM closure 수정 fbfd4d790f를 보존해 af761a6fc1로 통합했다. activeLoopSeeds와 mandatory all-definition 검증이 유지됨을 독립 리뷰했다. 다른 담당자의 StepLM/GLM worktree는 수정하지 않았다.
- **결과**: 같은 최신 기준에서 baseline→no-cost→final→final→baseline 실제 multiLogReg5회 PASS. 평균 analysis49.175→38.120초(22.48% 감소), compilation61.986→51.153초(17.48% 감소). 전체 planner11.418→11.621초로 개선을 확인하지 못했다. 이전40초대와 코드/호스트 조건이 달라 직접 비교하지 않는다. 1초 목표는 미달이다.
- **메모리 근거**: 이전73d1eb 기준 별도 계측에서 전체 analysis 누적 할당27.794→13.650GB, CFG18.406→5.111GB다. 후보 계측은 Maven과 겹쳐 시간을 채택 근거로 사용하지 않았다. peak heap 감소로 해석하지 않는다.
- **정확성**: 최신 Java63클래스477건 중472PASS/기존ignore5/실패0, package 및 Python30PASS. 실제16계수·shape8×2·audit/conversion gate와 시간 제외1,224개 DP checkpoints가 모든 군에서 같다. source1,651개/class-resource4,363개가 build와 같고 build 중 source 변경0이다.
- **채택/위험**: 세production 파일에서 owner revision의 proof 철회, 정확한 reference membership, segmented canonical text, root exact worker-count 재사용을 반영했다. 합법 후보나 비용 공식을 줄이지 않는다. 삭제/순환/같은reference support변경, UTF-16 구분자/동률, fallback·context 혼합 위험은 differential 회귀로 확인했다. 공통분석 재구성과 seed 조건부 compile은 여전히 크며 전체 성능 문제 해결로 주장하지 않는다.
- **상세**: [후속 보고서](LOGREG_REPLAY_COST_ABLATION_2026-10-07.md), [검증 JSON](experiments/logreg-replay-cost-20261007/validation.json). 기존 PhysicalGenerationEnvelopeTest4개 baseline fixture 실패는 별도 미해결로 남는다.
## Derived supply sharing 잔여 항목 — 구현·실험 완료, 확대 Exact 한계는 별도 잔여

- **요청/환경**: 사용자가 이전 결과 보고서의 남은 항목 모두 진행을 요청했다. 전용 worktree `/home/mchoi/w1357-derived-supply-sharing-20261006`, 기준 `ada24ffd4be4d17240f32f5bd1c2b5a98e1a0c8a`에서 기존 변경을 보존했다. 결과는 [완료 보고서](DERIVED_SUPPLY_REMAINING_WORK_2026-10-07_KO.md)와 [remaining-validation.json](experiments/automatic-supply-sharing-20261007/remaining-validation.json)에 있다. 미커밋 상태다.
- **Flat 증상/원인**: QB/QC의 공동 S→UB 이동 후보는 있었지만 QM의 required realization support가 child의 입력 공급 경로까지 동일해야 한다고 요구해 합법적인 complete assignment를 거부했다. 강제 assignment에서 두 hard 위반과 호환 parent 후보 0개를 확인했다.
- **Flat 해결/수정 파일**: `PlacementIdentity`, `CandidateSelections`, `ExactPhysicalModel`에서 같은 compiled owner가 정확히 같은 DURABLE_MAP을 만드는 required support에 한해 input-route 차이를 허용한다. Candidate 자신의 receipt와 DIRECT binding·boundary 비교는 그대로다. 후보333/hard149/encoded-factor335는 유지되고 auxiliary는112→116이다. 출력 map·owner 차이 음성 검사와 독립 factor-cell oracle, 기존 golden hash를 통과했다.
- **PHI 증상/해결**: outer snapshot N개를 inner loop에서 K번 사용하는 entry/backedge 두 origin을 N×K번 과금했다. `ExactPhysicalCostModel`이 transparent ancestry의 singleton loop carrier와 entry/backedge·context·profile을 증명한 경우에만 direct owner의 생성 횟수를 N으로 계산한다. 후보별 source mask와 GET creation scope를 사용한다. `PlacementRelationClosure`에는 upstream `825acfca…`의 grounding 보존 수정을 반영했고 임시 전체 재계산 fallback은 제거했다.
- **PHI 검증/제한**: `ExactLoopPhiSupplySharingTest` 2/2 PASS. N=K=3일 때 direct LOUT/FOUT upload·FOUT staging collection은9→3, inner semantic update는9 유지. 자동 PHI DML도 Local/Global 모두9 supplies/3 creations/6 hits/3 GET/3 PUT이다. Branch guard·함수 return·복수 carrier는 증명 범위 밖이다. 같은 occurrence/layout의 합법적인 fresh-owner 대안 직접 음성 fixture는 확보하지 못했다.
- **메모리 검증 도구**: Test-only worker launcher에서 기존 STATIC cache를 명시적으로 초기화한다. Production 기본 cache 비활성 정책은 유지한다. Worker별 temp/scratch 분리, 실제 cache 활성화·budget·target FS write/restore·supply identity를 gate로 검사한다. 초기 경로 충돌과 bind 실패 run은 성공 근거에서 제외했다.
- **메모리 결과**: 동일216 MiB 공급/선택계획으로1 GiB와256 MiB 각3회 실행. 전부9 supplies/3 creations/6 hits/3 GET/3 PUT. 256 MiB target의 FS restore4/6/4회에도 추가 업로드 없음. 실행 중앙값9.842→13.662초; source 파일 읽기와GC가 증가했다. Canonical cost bits는 같고 비용3634.783943ms이다. 3회 관측값으로 일반 보정계수를 만들지 않는다.
- **정리·잔여**: 각 run의3개 WORKER_RESET CLEAR 응답을 확인했고 logical canonical count/bytes는0이다. SOURCE_REMOVAL은 비동기 정리 제출 성공과 응답 확인을 구분한다. 기존 `cleanupEnabled(false)` 때문에 활성 cache backing 파일은 남는다. Worker-local disk I/O·GC·파일 재읽기는 정적 비용 모델의 별도 항목이 아니다. 전체 plan/surface fingerprint는 run별로 다르지만 선택된 native/supply/receipt/lifetime 내용과 별도 semantic digest는 같다.
- **회귀 결과**: Java220건=218 PASS/1 ERROR/1 SKIP; Python42/42; 자동 DML8/8; 메모리6/6; shell/Python compile/diff check PASS. Source ordinal reflection7건을 현행3인자 계약에 이식해 검증을 유지했다. 함수 fixture의 오래된8개 proof-path 기대값은 canonical formal map의2개 물리 계획과 정확한 DIRECT binding으로 바로잡았다. 상세한 certificate ERROR 귀속은 다음 항목에 기록한다.
- **재현/증거**: `run_LAN_docker.sh --supply-sharing-e2e` 사용. 원본 root `/grid/3/cofee-lm-sweep-mchoi-20260914/automatic-supply-sharing-20261006/`; 최종 run은 `remaining-automatic-final-r2`, `remaining-flat-final-r7`, `remaining-memory-216m-{1g,256m}-final-r{1,2,3}`. Production source inventory와 현재 파일의 hash가 모두 같다.
- **규칙/회귀 위험**: runtime fallback, retained 선택 차원, privacy 또는 TW/TR·함수 경계 완화는 추가하지 않았다. 다른 source/map의 잘못된 지원, source 갱신 후 재사용, 다른 identity의 runtime 실행을 각각 negative unit·lifecycle·정확한 identity join으로 감지한다.

## Certificate campaign의 잘못된 후보 기대와 대형 Exact 한계 — fixture 해결, solver 제한 미해결

- **증상/원인**: `ExactPhysicalModelCertificateTest`가 KMEANS의 coarse AVAILABLE derived emission을 무조건 executable candidate로 기대했다. 해당 입력은 필수 ROW FOUT이지만 compiled producer가 CP/LOUT만 가능하고 relocation과 DIRECT 지원이 없다. 수정 전 동결 코드에서도 같은 assertion이 실패했다.
- **해결/의사결정 근거**: 테스트에서 candidate가 없을 때 neutral graph·compiled edge·source legal state·relocation·support clause로 명시적인 impossible-input 조건을 독립 증명한다. Production builder를 oracle로 사용하지 않는다. 애매한 복수 producer나 FOUT/relocation/DIRECT 가능성이 하나라도 있으면 면제하지 않는다. 별도 읽기 전용 리뷰에서 soundness blocker를 발견하지 못했다.
- **새로 드러난 제한**: 앞선 잘못된 assertion을 통과하자 L2SVM에서 Exact factor가 커진다. 동일 수정 테스트를 이전 `automatic-sharing-final-r7` main/dependency에 올리면 optimizer의 `EXACT_VE_FACTOR_CELL_OVERFLOW`; 현재 코드는 model analyze의 `EXACT_VE_FACTOR_LIMIT_EXCEEDED|cells=19322130|limit=10000000|input`이다. 양쪽8건 중7 PASS/1 ERROR. 대형 campaign 실패는 이번 작업 전에도 존재하지만 도달하는 한계는 달라졌다.
- **검증/증거**: 최종 중앙 Maven `-Dtest=ExactPhysicalModelCertificateTest test`에서도 동일 ERROR를 확인했다. 로그/XML은 `remaining-regression-final`, 동일 수정 테스트 baseline 비교는 `remaining-regression-certificate-proof`에 명령·source hash·trace와 함께 보존했다.
- **잔여/위험**: solver/test limits와 legal candidates는 그대로 유지했다. 해당 L2SVM 끝까지와 뒤 workload는 이 campaign에서 검증되지 않았다. 한도만 높이는 scratch 진단도 더 큰 cell/materialization 한계에 도달했으며 production 해결책으로 채택하지 않았다. Solver factorization 개선과 전체 대형 campaign 완주는 별도 남은 문제다. 테스트 전체 무실패로 보고하지 않는다.

## Derived supply sharing 자동 DML·메모리 검증 — 완료

- **환경/요청**: 사용자가 [추가 검증 보고서](DERIVED_SUPPLY_E2E_AND_MEMORY_VALIDATION_2026-10-06_KO.md)대로 실행을 요청했다. 전용 worktree는 `/home/mchoi/w1357-derived-supply-sharing-20261006`, 기준은 `ada24ffd4be4d17240f32f5bd1c2b5a98e1a0c8a`다. 10월 6일 시작한 작업의 완료 기록이다.
- **문제 정의**: 기존 proof는 특정 공급 후보/직접 구성 instruction의 1×/N× 동작을 검사했다. 파싱부터 optimizer 자동 선택·실제 DML loop까지의 연결과 planned copy의 메모리 관측이 필요했다.
- **해결/수정 파일**: 기본 OFF `RefedReuseAudit`, `FederationUtils`/`FEDRefedInstruction` lifecycle 연결, source 제거·mutation 이유 전달, `FederatedData`의 기존 CLEAR 성공 결과 전달, test-only `AutomaticSupplySharingDockerProbe`, `run_supply_sharing_e2e.py`, `run_LAN_docker.sh` dispatch 및 Java/Python 회귀를 추가했다. Buffer는 scalar/digest만 보유하며 원본 객체를 retain하지 않는다. Candidate·비용·privacy·boundary·cache 유지 정책은 바꾸지 않았다.
- **최종 검증**: `automatic-sharing-final-r7`의 Local/Global invariant 2건은 공급3/생성1/GET1/PUT1, updated 2건은 바깥에서 만든 새 값3개 × 안쪽사용3회로 공급9/생성3/hit6/GET3/PUT3이다. CP 출력 일치, canonical 비용 bits·surface·선택 state·lifetime 일치, fallback/repair0. Updated canonical peak는1이다. 후속 검토에서 정리 의미를 명확히 했다: 이전 두 copy의 SOURCE_REMOVAL은 비동기 cleanup 제출 성공이고, 마지막 WORKER_RESET은 CLEAR 응답 성공이다.
- **메모리 검증**: 24 MiB copy3개를 유지하는 `automatic-sharing-memory-72m-1g-final`과 `automatic-sharing-memory-72m-256m-final` 모두 PASS. 공급별 GET/PUT1회 유지, 종료 후 canonical0, 세 copy의 원격 정리 성공. Worker caching은 비활성이고 FS spill/restore0이다. 최종 실행3.997/4.015초, source worker GC26/39회였다. Profile당 한 번의 최종 측정으로 유의한 성능 차이나 spill 비용 정확도를 주장하지 않는다.
- **재현/증거**: 결과와 명령은 [최종 보고서](DERIVED_SUPPLY_AUTOMATIC_E2E_RESULTS_2026-10-07_KO.md), 수치는 [validation.json](experiments/automatic-supply-sharing-20261007/validation.json)에 있다. 원본 root는 `/grid/3/cofee-lm-sweep-mchoi-20260914/automatic-supply-sharing-20261006/`이다. 기존 workspace/실험은 수정하지 않았고 이번 변경은 commit/push하지 않았다.
- **검증 도구 문제/해결**: 같은 DML coordinator의 추가 observer UDF가 runtime audit를 위반하던 문제는 별도 observer JVM으로 분리했다. 성공·실패 후 남던 Netty client를 `clearWorkGroup`으로 정리했으며 audit를 완화하거나 observer에서 worker CLEAR를 보내지 않았다. 과거 실패 run과 수정 이력은 보존했다.
- **잠재 회귀 위험/감지**: 계측으로 인한 객체 보관·예외 변경, source/version 오귀속, audit 손실, 원격 정리 미확인을 각각 unit·strict lifecycle/dispatch gate·CLEAR 응답 검사로 감지한다. 감사 property는 process 동안 고정한다.

## 확대 회귀의 기존 reflection fixture 오류 — 초기 기록, 위 후속 작업에서 해결

- **증상**: 관련14개 Java 클래스75건 중68 PASS,7 ERROR,skip0. `ExactSharedSourceOrdinalReuseTest`5건과 `ExactSharedSourceOrdinalLifecycleTest`2건이 `NoSuchElementException`으로 실패했다.
- **원인**: 두 기존 테스트가 `AlternativeHeader` 생성자를14개 인자로 찾지만 기준 commit의 생성자는3개 인자다. 실패 위치는 각각 `construct:256`과 `construct:131`이다. 해당 test/encoding source는 이번에 바꾸지 않았다.
- **검증/대응**: 수정한 기존 production6개 파일의 HEAD source를 격리 class overlay로 컴파일해 두 클래스를 새로 실행했다. 같은7건·예외·위치가 재현됐다. 증거는 위 root의 `regression-baseline/evidence/`에 있다. 원래 실패와 나머지68건의 통과 XML을 보존하고 전체 무실패로 보고하지 않는다. Python은 신규15건+기존20건=35/35 PASS, shell 문법·Python compile·diff check PASS다.
- **잔여/위험**: 기존 reflection fixture를 현 encoding 계약으로 이식하는 작업이 남는다. 이번 runtime 관측 수정의 회귀로 잘못 분류하거나 오류를 숨기는 것을 baseline 재현 기록으로 방지한다.

## Flat loop의 미선택 공급과 lifetime 범위 — 초기 진단 기록, 위 후속 작업으로 대체

- **관측**: 초기 작은 matmul source는 native 요청에 실어 보내는 공급이 선택돼 공유 coverage를 충족하지 못했다. 이후 `updated-r5`의 protected elementwise 두 consumer에는 동일 S→B relocation 후보가 존재하지만 Local/Global 모두 선택하지 않았다. 해당 DML 자체의 수치 결과와 canonical recost는 통과했다.
- **확인/미확인 경계**: r5의 shape·source version·FULL target·anchor·physical identity는 후보 생성 단계에서 일치한다. 이것만으로 두 후보를 함께 선택한 전체 assignment가 합법이고 더 저렴하다는 증명은 되지 않는다. 숨은 결합 제약 또는 contribution 차이에 대한 진단은 미완료다. 정상 비용 선택이나 solver 버그 어느 쪽으로도 단정하지 않는다.
- **검증 fixture 결정**: 최종 updated는 `S=B+i`로 바깥 iteration마다 새 값을 만들고 안쪽 loop에서 반복 사용한다. 단일 origin의 creation profile3과 consumer profile9를 production 코드가 추적할 수 있어, 같은 version의 공유와 서로 다른 version의 분리를 자동 선택부터 검증한다. 후보·privacy·비용을 강제하지 않는다. 직접 갱신 PHI의 여러 origin을 정밀하게 묶는 lifetime 증명은 이 결과의 범위 밖이다.
- **기타 실패 보존**: 중간 elementwise fixture에서 보호된 Nary plus 및 nested divide의 privacy-safe placement 부재가 발생했다. Oracle 완화 없이 지원되는 matmul fixture로 검증했으며 실패 source/log는 보존했다.
- **후속/회귀 위험**: r5 두 consumer의 대체 assignment를 완전한 제약 검사와 contribution별 비용 차이로 조사한다. 이번 중첩 loop의3회 생성을 단일 loop SINGLE_USE 자동 선택 증거로 혼동하지 않는다. 72 MiB보다 큰 working set 및 활성 spill cache의 비용 측정도 별도 범위다.


### LogReg 게시 전 동시 main 업데이트 — 재통합 검증 완료

- 푸시 직전에 origin/main이 shared-supply/loop-cost 수정8ae75aff00으로 전진해 non-fast-forward 거절이 발생했다. e605a032f8로 통합했고 세션 문서의 append 충돌은 양쪽 내용을 보존했다. production 비용 파일은 자동 병합됐으며 origin8ae와의 차이는 root memo뿐임을 재검토했다.
- upstream의 materialization lifetime/activation 변경은 worker-count의 순수 root 계산과 독립적이다. 같은 analysis 안의 memo 범위, visiting 의존 재귀, fallback, durable early return을 보존했다. 신규 upstream 회귀까지 포함한67개 Java 클래스493건 중488PASS/기존ignore5/실패0 및 package, Python52건 PASS. build 중 source 변경0.
- fbfd4d의 반복 측정은 해당 revision 결과로 그대로 보존한다. 게시본은8ae75aff00 기준 baseline/candidate 실제LogReg 한 번씩 추가해 양쪽 PASS, CP/FED16개 계수 최대 오차2.22e-16, audit/conversion 위반0을 확인했다. analysis fingerprint와 시간 제외 DP checkpoint1,224개, upper/lower/gap이 같다. 단일 pair를 반복 성능 추정으로 바꾸지 않는다.
- 추가 pair의 공통 분석46.414→39.677초, 전체 planner14.449→11.701초, 컴파일62.255→52.850초다. 이전 반복 측정에서 전체 planner 개선은 확인하지 못했으며 1초 목표도 미달이다. 합법 후보·정책·비용 공식 변경 없이 반복 계산을 재사용한다는 채택 근거를 유지한다.
- main source1,652개/class-resource4,374개가 Maven 산출물과 같고 전체 Java3,584개가 build freeze와 일치한다. 기준과 후보의 production 차이는 PlacementRelationClosure, PlacementAnalysis, ExactPhysicalCostModel 세 파일뿐이다. [상세 결과](LOGREG_REPLAY_COST_ABLATION_2026-10-07.md), [통합 검증 JSON/명령](experiments/logreg-replay-cost-20261007/publication-validation.json).
- 잔여 이슈는 common closure의 replay 재구성/증명 문맥 재사용과 seed boundary 반복 compile이다. invalidation 누락·정렬 변화·문맥 의존 memo가 잠재 회귀 위험이며 새 proof/CFG/cost 회귀와 실제 학습의 checkpoint·audit 동치 비교로 감지한다. runtime fallback과 후보 cap은 추가하지 않았다.


## StepLM joint 배치 정렬의 작은 scope encoding — 해결

- **문제/기준**: 최신 origin/main `2faff2a2a5`에서 새 worktree `steplm-joint-compact-20261007`을 만들었다. 기존 workspace는 변경하지 않는다. 앞선 검증된 조기 생략 변경을 이어 받았다. 두 입력의 정렬을 증명하는 재귀 공급 결정을21축 factor로 모아 cost preflight에서 overflow한다.
- **수정 계획**: 기존 canonical joint predicate와 공급 참조·origin·cycle 의미를 회귀 oracle로 보존한다. 공급별 배치 결과를 작은 범위 제약으로 인코딩하고, 최종 joint row 정렬은 결과만 비교하도록 분리한다. 후보/금액/상한/런타임 규칙은 변경하지 않는다.
- **검증 계획**: 수정 전 작은 회귀로 canonical legal space를 고정하고, compact encoding의 보조 변수를 존재 소거한 결과를 비교한다. 상관/독립 입력·alias loop·function call·relocation·invalid cycle 및 canonical 비용을 확인한다. 원래20×5 StepLM compile과 Docker run_LAN_docker.sh 소형 실제 실행을 검증한다. 대형 및 CSV 학습 성능은 제외한다.
- **위험/잔여**: 관찰 범주만 합치면 full realization-reference 일치와 cyclic self-support를 놓칠 수 있다. 결정적인 배치 도출 또는 유한한 cycle-safe proof와 회귀로 검증한다. 아직 해결 주장 없음.

- **구현/동치**: 공급별 demanded pool 보조 변수, 조건부 source-reference edge 및 순환 component의 rank, 실제 row별 정렬을 최대3축 factor로 분해했다. 기존 canonical predicate·후보·비용·privacy·runtime 규칙을 유지한다. Projected target의 scope 밖 dependency는 기존 missing-receipt와 같은 INVALID로 처리하며, 관찰 star 전용 quotient는 새 회로에 적용하지 않는다.
- **최종 코드 검증**: 관련15클래스62건, shared-source28건 및 원래20×5 전체 compile1건까지 총91건 PASS. Small space 전수 existential parity 및 canonical monetary raw bits/선택 parity를 확인했다. 실제 SCC loop의 rank 제약을 제거하면 거짓 순환 증명이 허용되는 mutation regression도 통과했다. Architectural review CLEAR, isolated javac-Xlint·diff check·package PASS.
- **계측**: 기존21축/1.36×10²⁴셀 preflight overflow에서 전체 encoded hard 최대scope3, 최대45,325셀·합625,066셀로 바뀌었다. 남은 joint 자체는203aux/352factor/46,832셀이고 rank20개다. 전체 graph symbolic order induced width는21→16이다. 최초 DP 계획 선택105.710초, canonical objective37019.63574473899ms. 기본3GB JVM의 최종 전체 compile 회귀는142.320초에 통과했다. Host 단일 측정이므로 training speedup으로 해석하지 않는다. Docker 실제 학습 검증은 진행중이다.
- **검증 과정 이슈**: 초기 SCC fixture는 invariant shortcut으로 cycle이 없어 assertion이 실패했고 실제 cycle을 만드는 초기화 loop로 교체했다. 잘못된 package skip property 때문에 실행된 Python StartupTest에서 log-count4/5 불일치1건이 있었으나 baseline7건/최종 단독7건은 모두 통과했다. 관련 코드는 수정하지 않았고 모든 실패 로그를 보존한다.
- **상세 보고서**: `docs/STEPLM_JOINT_COMPACT_ENCODING_2026-10-07.md`; 원시근거 `target/joint-compact-evidence/`. 아직 commit/push하지 않았다.

## StepLM 실제 Docker 입력의 CFG snapshot 반복 비교 — 해결

- **문제/환경**: 위 compact joint 코드의 실제 Docker `ml_steplm`(20×5, 원격 FED X/Y, 모델과 selection 출력)에서 joint encoding 전 `analysis_begin`에 오래 머문다. 원래 local_matrix/B-only compile 회귀와 다른 입력이다. 첫 실행은 결국 `Executable realization/action composition did not converge`로 실패했다. 이 실패를 joint 수정 성공 또는 단순 timeout으로 분류하지 않는다.
- **관측/원인 범위**: 소유 container의 coordinator에서 수집한 스택은 `completedLoopSeedTransferMeasured`의 `LoopSeedRevision.equals`가 동일한 immutable proof snapshot 쌍을 여러 reader에 대해 반복 비교하며 deep realization/support List equality로 내려가는 것을 보여준다. 이는 반복 비교 비용의 근거이며, 별도 publication 비수렴의 원인을 증명한 것은 아니다.
- **해결 계획/구현**: `LoopSeedProofSnapshot` 안에 상대 snapshot identity와 정확한 구조 비교 결과를 한 항목으로 memo한다. 원래 revision key와 완전한 List equality/hash를 보존한다. 일반 mutable List 비교는 memo하지 않는다. self/null/동일 hash의 다른 내용/상대 교체/일반 List mutation 및 반복 호출 횟수 회귀를 추가했다.
- **수정 파일**: `placement/PlacementRelationClosure.java`, `placement/LoopSeedSnapshotEqualityMemoTest.java`.
- **검증**: 수정 전 회귀4건 중 반복 비교3건 실패, 수정 후 isolated javac/JUnit4건 통과. 중앙 Maven 및 실제 Docker 재검증 예정. 첫 run 원본과 스택은 `target/joint-compact-evidence/`, Docker 결과는 `/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-joint-compact-validation-20261007/`에 보존한다.
- **잔여/위험**: 이 memo는 semantic convergence를 바꾸지 않으므로 publication 비수렴을 해결했다고 주장하지 않는다. snapshot 내부 값의 불변성 및 강한 참조 한 항목의 보관이 전제다. 구조적으로 다른 proof의 잘못된 cache hit는 hash collision/교체 회귀로 검사한다. 후보, privacy, TW/TR, pass 한도 및 runtime 정책은 바꾸지 않는다.
- **범위 재확인/분리 진단**: 이전 기록의 별도 CSV common-analysis 지연이며 사용자가 뒤로 미룬 경로다. 추가 closure 수정으로 확대하지 않는다. `local_matrix` 입력에 Docker의 selection 출력·모든 fingerprint roots를 그대로 붙인 compile-only 진단은27.256초/pass4에 수렴했다. 동일 owner의 intra-pass relocation support8→6→8 재생성은 pass 경계에서 같아 비수렴 근거가 아니다. 원격 입력의 host compile-only는 privacy metadata 조회를 시도해 즉시 중단했고 runtime 실행 근거로 사용하지 않는다. 실제 원래 source 의미의 검증을 위해 파일 입력 없이 동일 full-rank20×5 행렬을 생성하는 opt-in Docker case를 추가한다.
- **Maven/선택 범위 오류**: 중앙 memo 인접 회귀7클래스28건은 통과했지만 `LoopSeedReplayWideningTest` 전체 선택에 기존50,000×2,100 metadata-only1건이 섞였다. 대형 학습을 실행하지 않았으나 사용자의 대형 제외 범위에는 맞지 않는 선택 오류이며 숨기지 않는다. 최종 재실행은 해당 클래스의 소형5개 메서드만 명시했고, compact/조기 생략9건 및 원래 전체 compile1건과 함께15/15 PASS다. Java source3,588개는 최종 재검증 중 변경0이다.
- **최종 scope 보완**: 반복 reader의 canonical scope2에 새3축 회로를 쓰면 범위가 커질 수 있어, 최종 scope≤3에는 기존 observation encoding을 보존한다. 실제 square-matrix `A=X/Y; C=A%*%A` 회귀가 VALUE_MAP과 복수 물리 입력의 존재를 먼저 확인하고 factor/auxiliary/scope/descriptor/비용 bits/선택 동치를 검증한다. 독립 최종 리뷰 CLEAR, 통합18클래스92/92 PASS, failures/errors/skips0. 원래20×5 전체 compile162.057초 PASS이며 Java 및 harness source3,590개 변경0이다.
- **Docker fixture 보완**: 파일 입력 없는 full-rank20×5를 추가했으며 기존 CSV case는 그대로 둔다. 처음 literal 생성의 `c(...)`는 DML에서 정의되지 않아 root의 실제 Parser/HOP 진단이 실패했다. 기존 지원 문법인 `matrix("space-separated values",rows=...,cols=...,byrow=TRUE)`로 수정한 뒤 CP/FED Parser/HOP 두 경로와 Python31/31을 통과했다. 검증 fixture 오류 로그도 보존한다.

## Full-rank StepLM outer publication의 주기2 — 해결

- **새 증거/분류 수정**: 최종 Docker02의 파일 입력 없는20×5 `local_matrix` StepLM도 joint encoding 전에 `Executable realization/action composition did not converge`로 실패했다. 따라서 앞서 remote CSV에서 관측한 병목을 CSV에 한정된 것으로 분류할 수 없다. 이 입력과 상수1 compile 회귀의 차이를 구분한다. DMLScript는 오류를 출력하고 rc0으로 종료했으나 harness의 모델·selection·trace 검증이 실패를 정확히 감지했다. coordinator 강제 종료를 시도하기 전에 이미 자연 종료했으며 종료 신호는 보내지 않았다.
- **원인 범위/진단**: 같은 literal DML의 compile-only 진단은 pass6부터 full-context 주기2를 확인했다. nodes/domain/logical/actions/pending은 안정적이고 candidate facts의2개 owner만 달라진다. `lmCG.dml:129` 안쪽 `parsertemp258 ba(+*)`와 바깥 `q ba(+*)`의 FULL/BROADCAST direct/derived 지원이 번갈아 생성·삭제된다. 최초 차이는 `cfg-grounded`, 반대 경로 제거는 `source-prune`다. 반복 로그를 모으는 대신 주기 증명 후 bounded probe를 중단했다.
- **대응/원칙**: exact support reference의 최초 손실과 재생성을 추적한다. 반복 한도 증가, 주기 중간 상태 채택, 합법 후보 삭제, 무조건 union/intersection으로 수렴 판정을 우회하지 않는다. 아직 production closure 수정은 하지 않았다.
- **다른 실제 검증**: Docker02에서 joint6/6과 작은 builtin LogReg/L2SVM/lmCG3/3은 PASS. 전체 계수16/8/8개의 최대 절대 오차는2.22e-16/8.41e-17/1.22e-15이고 runtime audit 위반0이다. StepLM 실패를 포함한10건 전체는 FAILED로 보존한다.
- **근거/잔여/위험**: `target/joint-compact-evidence/publication-probes/literal-order/`, `docker-steplm-local-matrix-threads-01.txt`, 원본 Docker `steplm-joint-compact-validation-20261007/steplm-joint-compact-02/`. 실제 값 StepLM 학습은 아직 미완료다. 향후 수정은 expired support를 되살리거나 full/broadcast의 독립 합법 대안을 잃지 않는지 회귀로 검증해야 한다.
- **정확한 순서 결함/수정 계획**: `q`의 DIRECT 및 RELOCATION clause는 안쪽 matmul의 exact DURABLE_MAP을 참조한다. source-prune가 그 참조를 삭제한 뒤 final-action-bind가 생산자의 action-backed VALUE_MAP을 복원한다. 기존 binder는 전체 owner를 교체하면서 교체 전 source options를 읽어 소비자가 한 pass 늦게 본다. 삭제 전에 current action authority의 생산자 binding을 완료하고, changed receipt를 기존 native/direct CFG closure가 소비하도록 하는 가장 작은 수정 경계를 검증한다. Action binder만 반복해서는 DIRECT-only FOUT을 생성하지 못하므로 충분하지 않다.
- **회귀 계획**: 새 `StepLmPublicationReceiptClosureTest`에 같은 full-rank literal20×5를 사용한다. 단순 종료뿐 아니라 q의 두 DIRECT 경로 보존을 확인한다. `PublicationSupportClosureTest`의 expired source/action, 전체 reaching writer, sibling-support 보존 및 memo/fixed-point 인접 회귀를 함께 검사한다. HEAD/no-memo isolated class는 컴파일했지만12초 진단은 첫 pass를 끝내지 못했으므로, HEAD에서도 주기2를 실제 관측했다고 주장하지 않는다.
- **최종 수정 경계**: 삭제 전에 전체 CFG를 다시 호출하는 초안은 채택하지 않았다. 기존 CFG composed fixed point의 두 direct closure 뒤에 current action binding을 포함해, 생산자 receipt 변화가 같은 고정점의 native/CFG 전파에 소비되도록 했다. `lastDirectFacts`는 action binding 직전, direct closure가 실제 처리한 revision을 저장한다. 추가 변경을 이미 처리한 것으로 기록하여 dirty work를 누락하는 초안 오류를 독립 리뷰에서 발견해 수정했다.
- **불필요한 작업 제거**: relocation binder는 compiled-input dependency의 SCC를 생산자 순서로 처리하며 cyclic SCC 내부는 동시 commit한다. Facts와 source options는 owner identity로 index하고 변경된 owner의 lazy options만 무효화한다. 전체 fact list·inventory를 SCC마다 다시 순회하지 않는다. 결과 fact 순서와 `exact.isEmpty()` 동작을 보존하고 기존 source/action pruning이 실제 철회를 처리한다.
- **고립 검증/리뷰**: 실제 Docker와 같은 데이터·selection 출력·네 fingerprint root의20×5 StepLM 분석이28.272초에 통과했다. 회귀는 정확한 `.builtinNS::m_lmCG`의 q owner를 하나로 식별하며 FULL/BROADCAST DIRECT source reference가 현재 생산자의 DURABLE_MAP에 존재함을 확인한다. 기존 publication·memo·fixed-point·snapshot32건 PASS, 별도 scheduling2건 PASS. Acyclic 한 번의 binding은 반복 동시 closure와 같고 fact 순서에 독립적이다. SCC 내부 새 receipt는 다음 transfer 전에는 소비하지 않으며 실제 고정점까지 검사한다. 수정 전 classes에서 acyclic 회귀가 실패해 민감성을 확인했다. 독립 최종 리뷰 CLEAR; 중앙 Maven·실제 Docker는 진행중이다.
- **회귀 fixture 보정**: scheduling 초안이 연속 두 이동의 목적지를 같게 두어 두 번째가 DIRECT-only가 됐다. action binder는 relocation이 포함된 clause만 생성하므로 실패는 테스트 전제 오류였다. 서로 다른 목적지로 실제 두 이동을 만들고 RELOCATION binding을 확인하도록 고쳤으며 production 규칙은 완화하지 않았다.

- **최종 중앙 검증**: Java25클래스132/132 PASS(실패·오류·skip0), Python31/31 PASS, Maven package 및 diff check PASS. 원래20×5 전체 compile142.804초 PASS, 실제 값/전체 출력 roots의 분석 회귀30.585초 PASS다. 소스3,603개는 회귀·빌드 중 변경0이고 최종 class/resource4,374개는 Docker 전후 변경0이다. 원본은 `target/joint-compact-evidence/publication-final-{common,core}/`, `publication-final-build.json`에 있다.
- **최종 실제 학습**: `run_LAN_docker.sh --joint-boundary-e2e`의 `steplm-joint-compact-03`은 joint6건+소형ML4건=10/10 PASS다. StepLM20×5의5개 계수 최대 차이0, 선택 순서 `[3,1,5]` 동일, runtime audit/conversion 위반0이다. 컴파일120.694863초, 실행0.903초. LogReg/L2SVM/lmCG도 전체16/8/8개 계수 일치 및 audit 통과. 원본 결과 `/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-joint-compact-validation-20261007/steplm-joint-compact-03/result.json`. 소유 staging은 자동 정리됐으며 기존 workspace는 수정하지 않았다.
- **남은 성능/범위**: StepLM DP 탐색97.003초와 `RESOURCE` 종료 시 upper37040.115993804146ms/lower19529.714826506737ms의 gap은 남는다. 합법 incumbent을 실행한 결과이며 전역 최적성을 주장하지 않는다. Overflow/주기2는 해결했지만 빠른 전체 계획 탐색까지 해결한 것은 아니다. 단일 실제 실행으로 성공 baseline 대비 속도 개선율을 만들지 않는다. 원격CSV 최종 재실행과 대형/전체Global Exact 최적화는 제외했다. 새 제한·fallback·후보 삭제를 추가하지 않았다.
- **최종 보고서/상태**: `docs/STEPLM_JOINT_COMPACT_ENCODING_2026-10-07.md`, `docs/experiments/steplm-joint-compact-20261007/validation.json`. 전용 worktree에 미커밋 상태로 보존하며 이번 수정은 push하지 않았다.

## StepLM·ALS overflow 및 큰 LogReg W1 첫 재검증 — 완료, 새 main 후속 검증 진행 중

- **요청/기준**: 사용자가 이전 잔여 항목 1·3의 최신 main 동일 조건 검증을 요청했다. 새로 fetch한 `origin/main`은 `93706bbaa9`이며 별도 worktree `/home/mchoi/w1357-main-revalidation-20261007`에 고정한다. 기존 worktree·실행·artifact는 수정하지 않는다.
- **범위/계획**: StepLM CFG closure의 정확한 기존 메서드, ALS/StepLM의 canonical cost-factor overflow 메서드, PRIVATE_AGGREGATE X/Y의 n=50,000·d=2,100·W1 LogReg compile/lowering을 확인한다. 같은 커밋·입력·소스에서 이미 실행한 결과는 provenance를 검증해 재사용한다. 해당 조건의 완료 확인을 192×8 소형 학습 성공으로 대체하지 않는다.
- **검증/한계**: 새 main/test package와 source SHA를 고정한다. 비용 테스트는 각각 별도 JVM으로 실행하고, 실제 workload는 기존 `run_LAN_docker.sh`와 image/config를 재사용한다. 시간 상한에 도달하면 소유한 실행만 종료하고 현재 단계·stack·결과를 남긴다. 이번 단계의 목적은 실패/성공/시간 상한의 정확한 재분류이며 테스트 제외·기대값 완화·runtime fallback은 추가하지 않는다. 원본 명령·로그는 `/home/mchoi/main-revalidation-20261007/`에 보존한다.
- **소형 StepLM 재현 재사용**: 바로 위 최신 Heuristic 통합의 같은 메서드가 229.971초 후 CFG transient candidate closure 비수렴으로 실패했다. 기록된 source manifest·log·thread dump·runner·JAR SHA를 확인하고 manifest 4,119개 파일을 이 worktree와 전수 대조해 모두 일치했다. 같은 source의 새 재현을 중복 실행하지 않는다. 로그의 281/423은 HOP ID가 아닌 compiled occurrence 인덱스이고 fact 수 626↔632가 반복된다. 정확한 변수명/교대 후보 내용은 기존 로그만으로 확정할 수 없다.
- **큰 ALS/StepLM 새 재현**: 새 Maven package 성공(59.99초, Java/builtin/POM 3,771파일 SHA 불변) 후 기존 두 메서드를 별도 Java 17·8GiB JVM에서 기대값 변경 없이 실행했다. ALS `singleWorkerAlsPricesOneReusableCpRuntimeWeightMaterialization`은 13.114초, StepLM `costBasedSelectorsDoNotCollectTheFullFeatureMatrix`는 20.556초 후 각각 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 실패했다(JUnit 시간; JVM 전체 wall 15.00/22.00초). 공통 stack은 `physicalCostSurface` → `freezeOrdinaryFactorsAfterPreflight` → `validateInputStructure` → `checkedCells`이며 optimizer 선택 이전이다. ALS의 단일 assignment cost 검증에도 도달하지 않는다. StepLM은 첫 `compile_cost_based`에서 실패하므로 다음 `compile_exact` 성공 여부는 이 실행으로 확인하지 않았다.
- **overflow의 구체적 범위**: 외부 source/class 복사본에 scope 크기 출력만 추가한 진단에서도 동일한 실패를 확인했다. 문제는 보조 cost factor가 아니라 이미 observation 변수로 표현한 joint **hard** truth factor다. ALS의 `alsCG.dml:135`는 667,285,920,000셀, StepLM의 `lmCG.dml:135`는 8,380,255,403,520셀, `lmCG.dml:129`는 16,942,865,164,620,595,200셀의 raw product를 갖는다. preflight가 support reduction 전에 Java 배열 크기를 요구해 거부한다. 이 수치는 실제 할당량이 아니며, 이미 해결한 repair 중 canonical fallback overflow 또는 LogReg의 한 alias 축 제거와 구분한다. 단순 guard 삭제만으로 모든 후속 factor가 처리된다는 결론은 내리지 않는다.

- **큰 LogReg W1 첫 결과**: `93706bbaa9`의 n=50,000·d=2,100·W1·PRIVATE_AGGREGATE X/Y 동일 compile/lowering은 1,200초 제한 안에 완료되지 않았다. 공통 분석은 808.046초에 완료했고 이후 joint factor 모델 구성에서 `Grounding.supportOwners`→`aliasesOrigin`을 실행 중이었다(912초/1,182초 snapshot). 예외/OOM/privacy 실패 또는 최종 플랜 출력은 없었다. 1,203.584초에 소유한 container만 종료·제거했고 raw 결과·SHA·입력 parity는 `logreg-w1/verdict.json`에 있다. 시간 상한은 불법/불가능 판정이 아니다.
- **실행 중 main 변경**: 원격이 `73d1eb024f`로 전진했다. 새 production 변경은 `exactSinglePartitionRealizationProofs`의 synchronous sweep을 dependent worklist로 바꾸므로 공통 분석 시간에 영향을 줄 수 있다. 이 첫 실행을 새 main의 증거로 전용하지 않고 `/home/mchoi/w1357-main-revalidation-r2-20261007`에서 package 후 동일 메서드·W1을 다시 검증한다. 첫 결과는 그대로 보존한다.

## 73d1eb024f 잔여 항목 재검증 — 완료, 이후 StepLM 수정은 별도 검증

- **기준/검증**: `73d1eb024f6f70a7d869f363d895d72313bcbff6`, 별도 worktree에서 Maven package 성공(63.562초), tracked main/test/builtin/POM manifest 3,782개 SHA 불변. 기존 ALS/대형 StepLM/소형 StepLM 메서드를 각각 별도 Java 17 JVM에서 실행한다. W1은 동일 fixture/image/cost/privacy/1,200초 제한이며 경로만 새 artifact root로 바꾼다. 정확한 명령·receipt는 [검증 JSON](experiments/main-revalidation-20261007/validation.json)에 기록한다.
- **새 main ALS/대형 StepLM**: 기존 메서드는 모두 다시 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 실패했다. JUnit ALS 14.147초, StepLM 22.708초(전체 JVM 15.004/24.003초)이며 첫 실행과 같은 cost-surface preflight stack이다. 새 worklist 변경 후에도 이 두 실패는 남는다. 성능 A/B 결과로 해석하지 않는다.
- **새 main 소형 StepLM**: 기존 메서드를 실제로 다시 실행해 JUnit 244.102초(전체 JVM 245.508초)에 같은 CFG closure 비수렴을 재현했다. 첫 오류 메시지의 전체 0~1,244 iteration trace를 이전 동일 메서드와 비교한 결과 완전히 같다. `626↔632` fact와 occurrence `[281,423]` 반복이 남아 있다. 최신 결과는 이전 재현 재사용과 구분해 저장한다.
- **환경 실패 및 복구**: 최신 W1의 첫 시도는 약 5분 이후 host root filesystem 여유가 0이 되면서 runner의 `OSError: [Errno 28] No space left on device`로 중단됐다. planner 실패로 집계하지 않는다. 소유한 container만 종료·제거하고 무효 receipt를 별도로 보존했다. 완료된 Java 3건과 source/build는 불변이다. 첫 라운드의 재생성 가능한 target 7,509개 파일만 grid로 복사해 전수 SHA 일치를 확인한 뒤 원래 경로를 symlink로 보존했고 약 432MiB를 확보했다. 공유 /tmp·다른 worktree·Docker 자산은 삭제하지 않았다. 여유 758GiB의 grid artifact root에서 같은 최신 source/build·정규화 입력·Docker·privacy·비용 설정·1,200초 조건으로 W1만 재실행한다. 경로 변경/공유 호스트 때문에 이 재시도도 latency A/B로 해석하지 않는다.
- **Docker 경로 제약/유효 재시도**: snap Docker는 grid bind mount를 읽지 못해 direct-grid 시도는 즉시 `ClassNotFound`로 끝났다. 이를 별도의 환경 무효 실행으로 기록했다. 시스템 snap 권한은 변경하지 않았다. 최종 재시도는 기존 `/home` 구조(`/home/mchoi/main-revalidation-r3-20261007/logreg-w1`)에서 시작하고 grid에 주기적으로 evidence만 복사한다. 시작 시 root 여유 3,947,737,088 bytes였다. 첫 라운드 archived target은 host symlink로 접근할 수 있으나 그 라운드를 Docker에서 다시 실행하려면 Docker가 읽을 수 있는 위치에 target을 복원해야 한다. 최신 target은 원래 위치에 보존돼 있다.
- **73d1 W1 최종 결과**: 유효 재시도는 공통 분석 712.442초에 완료했으나 1,200초 제한 안에 compile/lowering을 끝내지 못했다. 900초와 1,181초 모두 `Grounding.supportOwners/aliasesOrigin`→`ExactPhysicalModel.addJointFactors`에서 joint 모델을 구성 중이며 solver/lowering에는 도달하지 않았다. 예외·OOM·overflow·privacy 실패는 없고 출력도 없다. watchdog 1,204.105초 후 소유 container 제거, root 여유 약 2.194GB를 확인했다. 서로 다른 source/공유 환경의 첫 라운드와 비교해 성능 개선을 주장하지 않는다.
- **두 번째 main 변경**: 실행 중 `fbfd4d790f`가 게시됐다. 기존 loop seed를 한 pass 후 해제하던 문제를 수정했고 main 통합 후 소형 canonical **common closure** 회귀가 26.904초에 통과했다. 따라서 73d1의 소형 CFG 비수렴을 새 main에도 남은 것으로 보고하지 않는다. 전체 DP compile의 후단 overflow와 큰 W1은 구분해 새 코드로 확인하며, published fresh package/source 검증 근거는 가능한 한 재사용한다.

## fbfd4d790f 최신 main 재검증 — 진단 전환으로 종료

- **기준/빌드 재사용**: 실행 중 main에 들어온 StepLM active seed 수정까지 포함한 `fbfd4d790feccc84997c3ac749eb8742b9e03a59`를 별도 worktree에 고정했다. 게시 worktree의 frozen source 3,578개가 Git blob과 일치하고 main class 3,790개가 게시 JAR와 일치함을 독립 검토했다. 재컴파일 기록과 package/JAR SHA도 일치한다. main/test/resource 7,178개를 새 worktree로 복사해 전후 SHA를 비교했고 dependency 301개는 기존 Docker-visible R2 파일과 byte 일치하므로 재사용했다. 새 Maven 빌드는 중복하지 않는다.
- **새 기존 메서드 결과**: 기대값 변경 없이 기존 3개 full compile/cost 메서드를 다시 실행했다. ALS 24.390초, 대형 StepLM 37.924초, 소형 StepLM 55.313초(JUnit)에 모두 cost-surface preflight의 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 실패했다. 소형은 이전 CFG 비수렴을 통과해 후단 오류로 이동했다. 따라서 **소형 CFG 비수렴은 해결됐지만 원래 전체 compile 테스트는 통과하지 않았다**. 기존 게시본의 26.904초 PASS는 common-closure 전용 회귀로 구분한다.
- **큰 W1**: 사용자의 원인 진단 전환에 따라 694.811초에 소유 실행을 중단·정리했다. 상태는 `STOPPED_FOR_DIAGNOSIS`이며 timeout/planner failure로 집계하지 않는다. 마지막 685.22초 stack은 common analysis의 direct-native/CFG closure이고, 이번 실행은 후단 alias 탐색까지 도달하지 않았다. artifact는 `/home/mchoi/main-revalidation-r4-20261007/logreg-w1/verdict.json`(SHA256 `7d0ffaf87b84d1775cb93f5f717b00928bd2b9b748c4f172a8607259b879b2a8`)이다. 정확히 소유한 container와 runner/JVM 종료를 확인했다. 앞선 93706/73d1의 timeout을 새 main 결과로 전용하지 않는다.

## Joint alias 합류 경로 반복 탐색 — 수정 및 집중 회귀 완료

- **문제/범위**: 사용자가 `Grounding.supportOwners → aliasesOrigin`의 반복 탐색에 집중하도록 지정했다. 앞선 W1 두 실행의 모델 구성 stack과 현재 코드에서 원인을 확인했다. `active.remove(reference)`가 재귀 경로에서 빠질 때 방문 이력을 지워 합류한 같은 하위 그래프를 경로마다 다시 펼친다. overflow, common CFG closure, 대형 W1 재실행은 이번 수정 범위에 포함하지 않는다.
- **수정 전 계획**: 작은 diamond/cycle/계산 경계 회귀를 먼저 추가하고 baseline의 중복 방문을 계수한다. 이후 query별 단조 visited와 반복 DFS를 적용하고, 같은 Grounding 안에서 완료된 root `(reference, origin)` 결과만 재사용한다. origin의 identity와 reference의 equals 계약, `sources` 우선순위, alias hop 및 null-hop 의미를 유지한다. 새 의존성·후보 삭제·heuristic pruning은 추가하지 않는다.
- **검증 계획**: diamond의 realization 조회 횟수, cycle의 exit 유무/질의 순서, 서로 다른 origin/realization cache 분리, 독립 transitive-closure oracle의 전수 쌍, 기존 joint alias projection/partial truth/selection legality 회귀를 검사한다. 변경 클래스만 별도 overlay에 컴파일해 이전 frozen build와 증거를 보존한다.
- **수정 파일**: `JointValueMapRelations.java`, `JointAliasReachabilityTest.java`, 본 문서.
- **적용 결과**: 재귀 DFS를 `ArrayDeque` 기반 DFS로 바꾸고 visited를 query 종료까지 유지한다. 각 reachable realization의 support는 query당 최대 한 번 펼치므로 그래프 탐색 작업량은 O(V+E)다(reference 비교/조회 비용 제외). Grounding별 origin identity map 안에서 reference equality로 완료된 boolean만 캐시하고, 같은 analysis의 projected Grounding에는 내부 map을 복사한다. 기존 DFS의 support 순서도 보존한다.
- **검증 결과**: production 수정 전에 추가한 diamond 회귀가 baseline에서 `expected 22, actual 3071`로 실패했다. 같은 22개 reachable realization에서 수정 후 조회22회로 통과했다. source fallback의 positive/negative 동일 query 재호출은 추가 조회0회다. cycle+exit의 양쪽 질의 순서, 닫힌 cycle, 계산/alias 구분, origin identity/realization 분리, 5,000-edge chain, 40개 무작위 그래프의 모든 쌍 2,560개를 독립 transitive closure와 비교했다. 새8건과 기존 `JointValueMapRelationsTest`, `JointValueMapSelectionLegalityTest`, `JointAliasProjectionTest`, `JointPartialTruthTest` 총22건이 26.598초에 통과했다. 기존 projection 회귀는1,872개 원본 joint assignment, exact4시나리오 및 regional4블록도 검사한다.
- **빌드/검토**: Java17 `javac --add-modules jdk.incubator.vector -Xlint:unchecked`로 변경 production/test를 별도 class overlay에 컴파일했다. 경고는 incubator module 알림뿐이고 `git diff --check` 통과, 지정 두 파일의 독립 read-only 검토에서 blocker0이다. 이전 frozen class/JAR는 변경하지 않았다. 원본 baseline/final 로그와 재현 명령·SHA는 `docs/experiments/alias-reachability-20261007/validation.json`에 기록했다.
- **잠재 회귀 위험**: cycle 중간의 실패를 영구 캐시하면 실제 도달 가능한 경로를 제거한다. 완료된 root 결과만 저장하고 cycle+exit 회귀로 감지한다. 캐시는 Grounding 수명 안에서만 유지하며 selected assignment에 의존하지 않는다.
- **잔여 이슈/한계**: 최신 W1 전체 완료 및 wall latency 개선은 미검증이다. 작은 그래프의 중복 탐색량 감소를 workload 전체 시간 단축으로 해석하지 않는다.


### Alias 수정 실제 Docker 검증 — 완료, 대형 W1 효과 미확인

- **요청/고정 비교군**: 사용자가 실험·검증을 요청했다. `fbfd4d790f`의 동일 source/class/dependency를 기준으로 `JointValueMapRelations`만 baseline/candidate로 교체한다. 같은 Java17 명령으로 해당 파일을 각각 다시 컴파일했고 변경 class family가 이 파일의11개뿐임을 전수 SHA로 확인했다. 다른 main 변경은 추적하지 않는다.
- **작은 실제 학습**: 기존 `run_LAN_docker.sh --joint-boundary-e2e --case ml_logreg`을 그대로 사용해 baseline→candidate→candidate→baseline 순으로 실행한다. 각 군은 동일192×8 PRIVATE_AGGREGATE X, public local labels,3 ROW workers, numclasses3, maxi10/maxii5, pinned Docker4CPU8GiB 조건이다. 전체16계수의 CP/FED 비교, runtime audit, conversion 금지, DP checkpoint/비용 및 분석 fingerprint 동등성을 확인한다. phase별 시간은 공유 호스트의 원수치와 함께 보고한다.
- **탐색량 진단**: 별도 복사본에만 query/search/expansion counter와 shutdown marker를 추가해 같은 작은 LogReg를 각 군 한 번 실행한다. production 및 순수 시간 비교군에는 counter를 넣지 않는다. query는 sources의 alias fallback root 질의, search는 cache miss로 실행한 root 탐색, expansion은 alias hop 분류를 통과한 realization adjacency 조회다. diagnostic 시간으로 성능을 주장하지 않는다.
- **대형 W1**: 같은 n50,000·d2,100·PRIVATE_AGGREGATE X/Y·worker1·4CPU16GiB·10GiB heap·비용 설정으로 candidate compile/lowering을1회 실행한다. 이전 조건처럼1,200초 상한과 단계/stack evidence를 둔다. 새 실패를 확인하면 반복하지 않는다. 이전 main의 timeout 및 latest baseline의 수동 중단은 새 candidate와의 정확한 latency A/B로 사용하지 않는다.
- **소유 artifact**: `/home/mchoi/alias-reachability-validation-20261007/{small,diagnostic,w1}`; 작은 학습 완료 artifact는 `/grid/3/cofee-lm-sweep-mchoi-20260914/alias-reachability-20261007`로 보존한다. 새 전체 Maven build/worktree 없이 class overlay를 사용하며 unrelated container/파일을 정리하지 않는다.

- **소형 최종 결과**: 순수 A/B/B/A 4회 및 별도 계측 2회 모두 실제 학습, CP/FED 전체 16계수 비교, audit를 통과했다. PID를 제외한 candidate-space, 524개 lowering 실행·저장 선택, 시간 필드를 제외한 DP 체크포인트 1,224개가 6회 모두 같다. alias 질의 1,506개는 같고 실제 root 탐색은 1,506→14회, adjacency 조회는 6,822→54회(99.21% 감소)다. 평균 컴파일은 62.631→64.572초로 latency 개선은 미확인이다. 공유 호스트·각 군 2회의 한계가 있다. 원자료 6회를 독립 재집계해 records/means/parity 일치와 blocker 0을 확인했다.
- **해시 한계**: 동일 variant 반복에서도 costFingerprint가 다르고, 이를 objective certificate에 포함하는 planHash도 달라진다. 수치 비용, DP 체크포인트, 실행 배치, 계수의 일치와 분리해 기록한다. 최초 hash 입력 차이는 분리하지 못했고 전체 cost surface의 byte 동일성을 주장하지 않는다. 별도 해시 수정은 이번 범위에서 하지 않는다.
- **보고서**: `docs/ALIAS_REACHABILITY_VALIDATION_2026-10-07.md` 및 `docs/experiments/alias-reachability-20261007/workload-validation.json`. W1은 20분 제한에서 alias 이전 공통 분석이 끝나지 않아 `TIME_LIMIT_BEFORE_ALIAS`로 종료했다.

- **W1 최종 결과**: 유효 후보 실행 1회는 1,200초 상한에 도달했고, 정리까지 1,204.876초가 걸렸다. analysis_end/planner_begin 없이 공통 분석의 `mergeCanonicalClauseRuns → mergeRealizations → bindDirectNativeCandidateRealizationsMeasured`에 머물렀다. 예외·OOM·overflow는 없었고 plan/cost/output도 없으므로 대형 W1의 alias 효과는 미검증이다. owned container/runner/JVM 종료를 확인했다. 상세 receipt는 `docs/experiments/alias-reachability-20261007/w1-verdict.json`에 보존했다.

### Alias 게시 전 main 통합 — 검증 완료

- **통합**: `origin/main`의 `2faff2a2a53ed1f608f2e1f6b2a13ca427d71f52`에 rebase했다. Alias production 파일은 upstream 변경이 없었고, 이 문서의 append 충돌은 양쪽 내용을 보존했다. 후보/비용/정책 변경 없이 반복 탐색을 줄이는 수정 범위를 유지했다.
- **검증**: Maven `test-compile` 성공 후 새 main/test class로 alias reachability, value-map relations, selection legality, alias projection, partial truth 5개 클래스 22건이 12.056초에 통과했다. 명령과 로그 SHA는 `docs/experiments/alias-reachability-20261007/publication-validation.json`에 기록했다.
- **근거 보존**: 기존 Docker workload 결과의 기준은 `fbfd4d790f`로 유지한다. 편의용 실험 snapshot과 Maven class의 hardlink를 분리하기 위해 실제 측정의 독립 `frozen-inputs`에서 8개 source/class tree를 복원하고 파일별 SHA를 확인했다. 실제 Docker 원자료는 변하지 않았다.
- **잔여/위험**: 대형 W1 완료와 전체 latency 개선은 여전히 미확인이다. 최신 main에서 workload를 다시 측정했다고 주장하지 않는다. cycle 및 cache 문맥 위험은 위 집중 회귀로 확인했다.

## 동일 실행의 costFingerprint·planHash 불일치 — 수정·검증 완료

- **문제/기준**: `241b9c491a`에서 사용자가 해시 불일치 해결을 요청했다. 기존 alias Docker 반복 4회의 analysis/placement/candidate fingerprint, 선택 배치, 수치 비용 및 학습 결과는 같지만 costFingerprint와 planHash는 다르다. 기존 실험 자료를 보존한다.
- **초기 근거**: `ExactPhysicalCostModel.addJointPhysicalExecutionFactors`가 `IdentityHashMap.values()`의 순서로 비용 factor를 추가한다. cost fingerprint는 factor ordinal과 scope를 포함하므로 같은 factor 집합도 객체 identity의 순회 순서에 영향을 받는다. 아직 이것이 실제 workload 차이의 전부라는 결론은 내리지 않는다.
- **수정 전 계획**: 작은 joint fixture에서 비용 factor 순서와 해시 차이를 먼저 재현한다. 이미 정해진 model domain 순서로 factor를 생성하고 identity lookup 계약은 유지한다. 해시 입력을 삭제하거나 비용값을 반올림하지 않는다. factor별 raw cost bits와 합법 후보를 보존하는 회귀, 독립 JVM 반복 및 기존 Docker LogReg 학습으로 cost/plan hash 일치를 검증한다.
- **검증/위험**: 생성 순서를 바꾸면 factor ordinal도 바뀌므로 solver tie-break와 부동소수점 합산에 영향이 있을 수 있다. 수치 factor 표·선택·목적값·학습 결과를 함께 검사한다. 새 heuristic pruning·runtime fallback은 추가하지 않는다.
- **확정 원인**: 작은 fixture에서 동일 비용표의 raw-bit multiset을 유지하면서 순서와 cost/plan hash가 달라졌다. 실제 현재 baseline Docker 2회의 전체 누적 hash prefix는 ordinal2105까지 같고, 2106에서 `gs` 합계와 다른 행렬곱의 factor가 처음 엇갈린다. joint10개의 kind/scope/raw-bit 행은 ordinal을 빼면 모두 같다. 해시 payload를 약화시키지 않고 생성 순서를 바로잡는 것이 적용 근거다.
- **해결/수정 파일**: `ExactPhysicalCostModel.addJointPhysicalExecutionFactors`에 기존 ordered model domain list를 전달해 그 순서로 순회한다. `IdentityHashMap`은 identity lookup으로 유지한다. 새 `ExactPhysicalCostFingerprintDeterminismTest`는 4개 joint owner의 순서, 재구성한 analysis의 모든 비용표·assignment·objective bits·plan hash를 확인한다. 진단 checkpoint는 외부 복사본에만 존재한다.
- **집중 검증**: 최종 새 회귀 2건은 baseline에서 2 FAIL, 후보는 기존 스트리밍·authority mutation·보호된 golden·joint/alias/partial truth를 포함한 7클래스32건 PASS다. Maven test-compile 및 diff check 성공. 독립 JVM 각3회에서 baseline cost/plan hash는2종, candidate는1종이며 6회 모두 전체 비용표·assignment·objective bits 동일하다. 별도 읽기 전용 code/evidence 검토 blocker0.
- **실제 학습 검증**: `run_LAN_docker.sh --joint-boundary-e2e --case ml_logreg`, 192×8 PRIVATE_AGGREGATE X/public local labels/3 ROW workers/4CPU8GiB/pinned image의 baseline/candidate 각2회 모두 PASS다. candidate 반복의 costFingerprint·planHash가 각각 정확히 일치한다. 네 실행의 candidate space·524개 배치·시간 제외1,224개 DP checkpoint·목적값122.26631334184357·16개 학습 계수가 같고, CP/FED 최대 오차2.22e-16, audit/conversion 위반0이다.
- **근거/잔여**: `docs/experiments/cost-fingerprint-20261007/validation.json`에 명령·로그 SHA·최초 차이 및 원자료 경로를 기록했다. 기존 비결정적 hash와 새 canonical hash의 값은 달라질 수 있다. latency 개선 주장은 하지 않으며, W1 공통 분석과 별도 factor-size 오류는 여전히 범위 밖이다. 후보 제거·비용 변경·runtime fallback은 없다.
- **게시 통합 검증**: 최신 main `344f88360919e3eb109c4e4bc121cf949be6a757`의 L2SVM·비용 추정 snapshot 변경에 rebase했고 충돌은 없었다. origin/main 대비 production 차이는 기존 joint factor 순서 수정 7줄뿐이다. fresh Maven test-compile 성공, 기존 32건과 upstream snapshot 2건 총34건 PASS(14.086초), 독립 JVM 2회의 전체 비용표/순서/assignment/objective/plan snapshot 동일. 기존 Docker 결과의 기준241b9c는 유지하며 별도 `publication-validation.json`에 통합 명령·SHA를 기록했다.

## StepLM joint 수정 게시와 전체 플래닝20초 목표 — 통합 검증 중

- **요청/기준**: 검증된 joint/CFG 수정부터 commit·origin/main push한 다음 전체 플래닝을20초 이내로 줄이라는 요청이다. 원본 수정은 `879383b42c`로 커밋했다. Fetch된 main이6개 commit 전진한 `56d2ac628e`이므로 그 변경을 먼저 통합한다.
- **통합 범위/원칙**: upstream alias reachability·bounded sparse storage·runtime 전 transfer 추정 snapshot·deterministic cost/plan fingerprint를 보존한다. Session 문서는 양쪽 이력을 합치고, Docker harness는 upstream case/계측과 기존 full-rank StepLM literal case를 함께 유지한다. Production 세 파일은 자동 병합됐으며 별도 의미 검토와 소형 회귀를 수행한다.
- **성능 평가 예정**: 동일 image·4CPU/8GB·coordinator3GB·실제 StepLM20×5로 전체 컴파일 시간을20초 이하로 검사한다. 공통 분석·비용 모델 구성·DP 선택을 포함하며 runtime 학습 시간과 분리한다. 합법 후보·privacy·source/version/lifetime·canonical 비용과 CP/FED 학습 결과를 유지한다. 대형 학습과 CSV 별도 오류는 제외한다.
- **회귀 위험/검증**: 일반 compact 회로를 observation-star로 잘못 취급하는 quotient, descriptor 및 alias 경로 충돌, sparse table 저장 규칙 변경에 따른 선택/비용 차이를 관련 회귀와 실제 Docker로 검사한다. 통합 검증과 게시가 끝나기 전에는 새 성능 구현을 시작하지 않는다.


## LogReg common 잔여 병목 조사 — 분석 완료, 추가 최적화 미구현

- **문제**: 게시본의 공통 분석39.677초가 여전히 길어 현재 병목을 새로 확인했다.
- **환경/검증**: 기준2faff2a2a5, 실제 multiLogReg PRIVATE_AGGREGATE/ROW3, run_LAN_docker.sh 사용. production source는 그대로 두고 DMLTranslator 진단 사본에서 기존 metrics/JFR을 켰다.
- **측정**: common46.332초/current-thread CPU44.455초/누적 할당14.571GB. exclusive CFG11.309초, closure 자체7.577초, physical7.135초, direct6.197초로 합계69.54%. 계측 실행을 이전 비계측39.677초와 직접 비교하지 않는다.
- **원인**: fixed-point revision에서 exact reader replay·joint Environment·canonical 표현을 재구성하고 physical resolver delta map/query-state 및 direct dependency/merge/equality 처리를 반복한다. 이전에 제거한 전체 proof index 재구축이나 거대 compatibility 문자열을 현재 원인으로 오인하지 않는다.
- **해석 주의**: stable direct wave1676/1869는 계산 후 변경없음이지 사전 skip 가능 비율이 아니다. exactContextRepeated0도 memo miss 뒤 revision별 관측이라 전체 중복 부재를 뜻하지 않는다.
- **검증 결과**: 실제 학습/계수 PASS, max error2.22e-16, audit/conversion0. 게시본과 시간 제외1224 checkpoints·fingerprint 동치.
- **수정/잔여**: production 변경0. 이번 수정 파일은 이 세션 기록과 [common 분석 보고서](LOGREG_COMMON_BOTTLENECK_2026-10-07.md)뿐이다. replay/result 재사용과 delta index 비용 축소의 실제 절감량은 후속 ablation이 필요하다.
- **위험/감지**: 향후 cache가 node/support/reaching/loop-seed/privacy revision 변화를 놓치면 합법 공간·증명이 달라진다. 완전한 read-context key와 fixed-point/학습 checkpoint parity로 검증해야 한다. 분석 단계에서 privacy/oracle/runtime 규칙은 변경하지 않았다.
- **증거**: `/grid/3/cofee-lm-sweep-mchoi-20260914/logreg-common-profile-20261007`의 command/diagnostic patch/phase-metrics/JFR/validation.


### L2SVM common과 LogReg 비교 — 확인 완료

- **문제**: L2SVM common이1초대인데 LogReg만40초인지 확인 요청. 이전 인용 로그의1.847초는planner checkpoint이고 common은10.527초였다.
- **검증**: 현재2faff의 동일 동결 Docker 실행에서 실제 ml_l2svm/ml_logreg 순차 계측. common8.497/34.769초, 학습1.973/2.147초. 양쪽 CP/FED 계수 일치·audit/conversion0. LogReg checkpoint1224개는 직전 진단과 동치.
- **원인 근거**: compiled occurrences252/524, fixed-point11/13에 비해 누적 proof alternatives83,107/658,249, proof edges74,481/738,448이다. LogReg의 inner trust-boundary S/R/V 분기와 outer B/P/Grad update/retain 분기가 loop backedge와 결합하며 지원 관계를 늘린다는 코드 경로와 부합한다.
- **해석/잔여**: 약4.1배 common 차이는1회 pair 관측이며 모든 환경의 보장값이 아니다. 누적 proof 수를 최종 고유 후보 수로 오인하지 않는다. 특정 분기의 인과 기여율은 별도 ablation이 필요하고 production 수정은 없다.
- **기록**: [common 보고서의 L2SVM 비교](LOGREG_COMMON_BOTTLENECK_2026-10-07.md), 원자료 root의paired-command/paired-comparison/paired-validation.json.

## LogReg common 10초대 최적화 — 구현·반복 검증 진행 중

- **요청/완료 조건**: 실제 multiLogReg의 비계측 common analysis와 CommonPreparation을 모두20초 미만으로 낮춘다. 세 번의 최종 실행, 기존1224개 시간 제외 planner checkpoint와 analysis fingerprint 동치, CP/FED 계수 일치 및 runtime audit/conversion 위반0을 확인한다.
- **환경/재현**: 기준2faff2a2a5, 입력192×8/3classes/ROW3/PRIVATE_AGGREGATE, maxi10/maxii5, Docker4CPU/8GiB. `/grid/3/cofee-lm-sweep-mchoi-20260914/logreg-common-opt-20261007/PLAN.md`, `freeze.py`, `evaluate.py`, 각 `*-command.json`에 입력·실행을 고정했다. 계측/JFR은 별도 진단 사본에만 적용한다.
- **원인/변경**: CFG replay의 고정 입력을 owner/reference별로 인덱싱한다. reader 결과는 실제로 읽은 source/node/shape/privacy/seed context와 reference 결과(negative 포함), 현재 native resolver에 다시 물은 출력 witness/precision이 일치할 때 재사용한다. loop seed 설치와 prior compatibility의 live-reference 검사는 매번 수행한다. 물리 후보 closure는 method-local owned inventory를 delta 갱신하고 normalization은 현재 owner를 기존 전역 index에 overlay한다. direct wave는 완전한 변경 owner 집합에 속한 슬롯만 비교하고 안정된 boundary session의 빈 변경 집합은 재탐색하지 않는다. Environment key와 native query의 불변 후보 snapshot도 재사용한다.
- **초기 ablation**: 비계측 기준common25.329초, Environment/query snapshot만26.251초, 최초 CFG memo/owned resolver 통합23.359초. 세 실행 모두 학습·정적 fingerprint·시간 제외1224 checkpoints 동치다. 작은 두 변경만으로 wall-time 개선을 주장하지 않는다. 원래 계측 진단의 CFG exclusive8.472초 대비 통합 진단2.834초, memo hit9220/context miss956/evidence miss106을 관측했다. 공유 호스트 단일 진단 수치를 최종 비계측 개선율로 사용하지 않는다.
- **검증 중간 결과**: native root identity 충돌, native empty↔nonempty, support/owner identity 변경, privacy/seed context, resolver withdrawal/restore, cold-vs-overlay authority, direct net cancellation 등을 별도 회귀로 확인했다. LogicalBoundary의 빈 delta 테스트는 수정 전 실패·수정 후 통과했다. 전체 Maven 및 최종 반복 실행은 아래 후속 기록으로 확정한다.
- **기존 테스트 fixture 문제**: PhysicalGenerationEnvelopeTest의4개 기존 실패는 instance 메서드에 null receiver 및 closure origins/shapes 미설정 때문이었다. 실제 authority를 주입하도록 fixture를 수정해8/8 통과했다. MaterializationProofInventoryTest의 같은 reflection harness 문제도 수정했다. production legality를 완화한 것이 아니다.
- **결정 원칙/위험**: oracle/privacy/TR-TW/비용식/합법 후보/solver 정책 변경 없음. cache는 빌드 범위에 한정하고 clearBuildState에서 제거한다. mutable owned inventory는 method-local 전용이며 immutable snapshot API와 상호 혼용을 거부한다. 누락된 read-set이나 identity 변화는 잘못된 재사용 위험이므로 명시적 무효화 테스트와 cold differential/실제 planner parity를 통과해야 채택한다.

- **반복 측정의 목표 미달 확인**: direct owner delta/physical overlay까지 반영한 screen은 common19.754초였지만, 동일 source/class의 Maven 최종 사본은22.242초였다. 단일 성공을 목표 달성으로 채택하지 않는다. Environment/query snapshot을 뺀 ablation은23.036초, grounded support 부분집합 merge 생략과 no-derived-action identity 보존을 더한 다음 screen은21.402초였다. 모든 실행에서 CP/FED 계수·fingerprint·시간 제외1224 checkpoints 동치와 audit/conversion0을 확인했다. 추가 최적화와 최종3회 검증을 계속한다.
- **중앙 검증 중간 확정**: 최초 결합본의 Maven77클래스674건 중668PASS/기존ignore6/실패0, package BUILD SUCCESS. source hash는 빌드 전후 같았다. 이후 direct 부분집합/no-derived-action 변경은 격리51건 PASS이고 중앙 재검증 전이다. 이 둘을 같은 최종 검증으로 혼동하지 않는다.
- **추가 리뷰 경계**: recompute-native는 기존 grounded clause를 먼저 union하므로 동등 clause의 old-first authority 의미를 유지해야 한다. 반면 일반 non-recompute 경로에서 단순 emission.equals만으로 이전 객체를 돌려주면 구조적으로 같은 foreign graph owner를 감출 수 있다. 해당 일반 재사용 gate를 제거하고 owner identity 음성 회귀를 추가한다. 순수 publication memo는 현재 proof 조회·completeness·exactness 검사를 끝낸 결과 객체에만 적용하며 legality 조회를 건너뛰지 않는다.
- **후속 구현/검증**: 일반 equals 재사용을 제거했고, subset 조회는 구조적 clause map 조회 뒤 proof/source/relocation consumer owner identity를 확인한다. foreign input/proof owner 음성 회귀를 추가했다. 순수 native publication은 proof와 owner의 객체 identity, emission, 출력 anchor/lineage, 현재 input exactness가 같은 경우에만 재사용한다. 현재 native query와 completeness 검사는 계속 실행한다. SCC 일정은 owner identity/순서, 방향 있는 semantic edge 집합, 방향 없는 alias 집합이 같을 때만 재사용한다. primitive ordinal pair로 캐시 키를 만들며 추가/삭제/self-edge/cycle/foreign identity를 cold constructor와 대조했다. 캐시 크기 제한은 저장한 재사용 결과만 퇴거시키며 후보 수를 제한하지 않는다. 결합 격리121건 PASS.
- **최신 main 통합**: fetch한 origin/main241b9c491a의 alias reachability 최적화를 fast-forward로 통합했다. 두 세션 문서의 append 내용을 모두 보존했다. 이후 동결 source는 이 upstream 변경을 포함하고, 별도 baseline-main 동결본도 준비했다. 앞선2faff 기준 수치를 최신 main의 단독 효과로 바꾸어 해석하지 않는다.
- **두 번째 최종 후보의 목표 미달/추가 계측**: 중앙80클래스702건 중696PASS/기존ignore6/실패0 및 package, source3588개 SHA 불변을 확인했으나 첫 비계측 common20.389초/CommonPreparation20.409초였다. 최종3회 gate는 실패로 보존하고 후속 실행을 중단했다. 동일 후보의 별도 diagnostic02는 publication hit22,646/miss112,121/저장32,768이며 value가 같은 emission wrapper 관련 miss27,080회를 보였다. 저장 한도 이후 새 항목을 전혀 받지 않는 정책이 최근 fixed-point 결과 재사용을 방해한다. SCC hit91/miss3, CFG hit9,303/miss979도 확인했다.
- **추가 해결**: publication key는 emission의 값으로 비교하되 hit 결과를 기존 `rebindRealization`으로 현재 emission-state wrapper에 다시 결합한다. Immutable support clause와 proof/input authority는 그대로 공유한다. 저장소는 동일32,768 상한의 access-order LRU로 바꿔 오래된 항목을 퇴거시키고 최근 결과를 저장한다. Current-wrapper/공유-clause/foreign-owner/cold parity와 실제 access-order 퇴거·build clear 회귀를 통과했다. 별도 검토 APPROVE이며 workload 채택은 후속 최종 동결본에서 확정한다.

- **세 번째 최종 후보의 목표 미달**: LRU/current-state rebind 및 CFG first-equal ordinal index를 결합한 최종 v3은 중앙 80클래스704건(698PASS/기존ignore6/실패0), package 및 source SHA 불변을 통과했지만 common20.911초/CommonPreparation20.927초였다. 모델/합법성/fingerprint/1224 checkpoints는 동등하나 <20초 목표는 실패로 기록하고 최종 반복 gate를 중단했다. 앞선18.104초 screen을 최종 성능으로 보고하지 않는다.
- **후속 병목/수정 계획**: diagnostic02의 native 구조/SCC 생성과 publication 문자열 비용을 조사했다. WorkerPoolAnchorResolver의 legacy proves 경로는 SCC를 소비하지 않는데 NativePlacementContinuity 생성 시 전체 SCC를 계산했다. 구조 read-set/검증은 즉시 보존하고 실제 SCC 소비 때만 lazy 생성한다. 또한 publication에서 shape 기반 digest를 먼저 만들고 PlacementAnalysis 생성자가 이를 graph/projection/privacy 기반 digest로 즉시 다시 계산했다. canonical 생성 경로를 명시적으로 분리해 중복 계산을 제거하고,64자리 lowercase hex 치환을 동일 의미의 선형 스캔으로 바꾼다. 기존 supplied fingerprint 생성자의 null/blank 거부와 fixture fingerprint 계약은 보존한다. 회귀/실제 fingerprint·checkpoint 비교와 동일 Docker 재측정 후 채택한다.

- **지연 SCC/중복 fingerprint screen**: common19.952초/CommonPreparation19.971초 및 모델·fingerprint·1224 checkpoint 동치였다. 20초 경계에 너무 가까워 최종 반복 gate를 시작하지 않고 추가 비용을 줄였다. Diagnostic03 publication 준비는1.111초(이전 diagnostic02 1.582초), native StructuralContext 포함 CPU sample은3개/총663개로 감소했다. 계측의 다른 phase 변동을 전체 개선율로 해석하지 않는다.
- **추가 불필요 작업 제거**: WorkerPool resolver의 native fallback 객체 전체를 최초 필요 시 생성한다. Origins/privacy는 identity snapshot을 유지하고, 최초 생성 시 현재 owner facts/node authority를 읽는다. 생성 전 revision은 기존 인덱스만 갱신하고 생성 후에는 기존 next/fresh 경로를 유지한다. 조회 전용 normalized-reference 인덱스를 TreeMap/TreeSet에서 HashMap/HashSet으로 전환하되 충돌 bucket의 명시적 ordinal 정렬과 마지막 authority 선택을 보존한다. SHA-256의32회 String.format을 기존 저장소에서 사용하는 HexFormat으로 교체하고 중복 digest helper를 삭제했다.
- **집중 검증**: lazy native SCC 관련102건, 전체 WorkerPool lazy/cycle/function-return/owner inventory42건, fingerprint6건(정규식 oracle 무작위1000입력, SHA known vectors, constructor 호환성) 모두 PASS. SCC/native/fingerprint/index 변경의 독립 source 검토 CLEAR, diff-check PASS. 이 결과는 이후 중앙 Maven/비계측 반복 측정과 구분한다.

- **추가 screen 및 두 번째 upstream 통합**: WorkerPool 전체 지연 생성/문자열 인덱스/HexFormat 결합 screen은 common18.519초/CommonPreparation18.538초였다. 전체16계수 최대 오차2.22e-16, analysis fingerprint 및 시간 제외1224 checkpoints가 기존 기준과 일치했다. 이어 fetch한 origin/main56d2ac628e(L2SVM sparse solver/compile-time cost snapshot/cost fingerprint 변경)를 fast-forward 통합했다. PlacementAnalysis는 자동 병합 후 양쪽 변경을 확인했고 session 문서는 기존 내용에 두 세션 append를 모두 보존했다. 백업 stash 및 base/ours/upstream/merged 문서는 artifact integration-56d에 보관했다. 최종 비교군은56d main으로 다시 만들고, upstream이 바꾼 비용/checkpoint와 이번 common-only 변경의 동등성을 구분한다.

- **최종 v4 중앙 검증**: origin/main56d 통합 상태에서 Maven86클래스731건 중725PASS/기존ignore6/실패·오류0, package BUILD SUCCESS(173.411초). Java source SHA가 빌드 전후 불변임을 확인하고 main/test source/classes를 final-v4로 동결했다. 최신 upstream cost snapshot/determinism/sparse-budget/legality 및 Docker probe argument 회귀도 포함한다. 원본 명령·요약·source manifest는 final-v4-regression-command.json, final-v4-regression-summary.json, final-v4-build-status.json 및 final-v4/manifest.json이다.

- **최종 완료/성능**: 동일 final-v4 artifact의 실제 LogReg3회 common16.714/18.749/17.716초, CommonPreparation16.735/18.765/17.736초로 모두20초 미만이다. 최신56d 대조군common23.186초 대비 최종 평균17.726초(-23.55%). 전체 컴파일25.224–28.084초, 학습 실행3.249–3.619초다. 모든 실행에서16계수·fingerprint·1224 non-time checkpoints가 일치하고 audit/conversion 위반0이다. L2SVM 모델·4 checkpoints도PASS이며 common6.852→7.047초이므로 해당 workload의 속도 개선은 주장하지 않는다.
- **Ablation/조건 검증**: publication/direct-SCC 캐시를 제거한 단일 ablation은common18.647초, 정확성PASS. 최종3회 변동과 겹치므로 기능별 확정 효과로 확대 해석하지 않는다. Docker image4CPU8GiB/network/설정/fixture/34개 input/runner·dispatch hash와 테스트·dependency digest 동등성을 자동 검사했다. 세 최종 run의 main/test class/source digest가 같고 현재3595개 Java source가 동결 빌드와 일치한다. 대조군source는HEAD전수일치/수정classfamily재컴파일overlay이며 별도 cleanMaven빌드는 아니다. 최신대조군과2faff의non-time checkpoint도같다.
- **완료 근거/잔여 범위**: docs/LOGREG_COMMON_OPTIMIZATION_2026-10-07_KO.md 및 docs/experiments/logreg-common-20261007/validation.json에 최종 수치·실패한 중간 screen·재현 명령·증거SHA를 기록했다. 이번192×8 actualbuiltinfixture에서의 목표는 완료다. 큰W1/임의데이터 크기의20초미만은 미검증이고 일반 보장하지 않는다. 합법후보/privacy/TR-TW/oracle/비용정책/runtimefallback은 변경하지 않았다. 잠재cache회귀는 기존cold-differential/identity·negative lookup/ownerwithdrawal 회귀와 actualplannerparity로 감지한다.


## LogReg 전체 컴파일 10초 목표 — 진행 중

- **문제/기준**: 사용자 추가 요청으로 d26bb59610의 전체 컴파일25.224–28.084초를10초까지 줄이는 작업을 시작한다. 사용자가 common + planner + lowering을 합친 전체 컴파일이라고 확정했다. 학습 실행 시간은 별도로 보고한다. 이전 common20초 미만 달성과 구분한다.
- **환경/재현**: 동일192×8 multiLogReg fixture,3classes/maxi10/maxii5,PRIVATE_AGGREGATE X/local labels,ROW3, pinned Docker4CPU8GiB, run_LAN_docker.sh만 사용한다. 동결 기준과 평가기는 /grid/3/cofee-lm-sweep-mchoi-20260914/logreg-common-round2-20261007에 보관한다.
- **최신 관측**: 별도 JFR/metrics 진단 common21.297초·planner9.592초·전체32.214초. 계측 시간을 생산 성능으로 사용하지 않는다. common은 direct/CFG 반복과 불변키 처리, planner는 elimination ordering의 fillEdges/neighborCells 계산이 남는다.
- **수정 전 계획**: 변경 없는 direct wave의 전체 facts 복사를 제거하고 고정 입력 준비를 재사용한다. exact solver의 그래프 순서 계산에서 중복을 줄인다. 불변 identity/hash/최종 publication 계산도 조사한다. candidate space·proof authority·privacy/TR-TW·비용·순서와 tie-break는 보존한다. 집중 회귀와 실제학습 parity 후 비계측 반복/A-B/ablation을 수행한다.
- **검증/잔여/위험**: 현재는 원인 재계측까지 완료,10초 목표는 미달이며 구현/최종 검증 전이다. 잘못된 dirty 범위 축소·boundary 최초 실행 생략·structural identity 변경·tie-break 변경을 집중 회귀와1224 checkpoints/fingerprint/모델 동치로 감지한다. 규칙 완화나 후보 임의 삭제로 시간을 맞추지 않는다.

- **첫 screen/대조군**: d26기준 첫 결합 screen01은 common18.283초·전체27.285초, 동시기 baseline01은 common19.948초·전체28.756초. 양쪽16계수/fingerprint/1224 non-time checkpoints 동치 및 audit/conversion0. 단일비교여서 반복개선 확정이나10초 달성으로 채택하지 않는다.
- **추가 구현**: 제거 순서 지표는 후보 비교 간뿐 아니라 단계 간 재사용하고 제거된 노드 이웃 및 새 fill edge의 공통 이웃만 정확히 무효화한다. 400개 무작위 그래프×4정렬을 기존 comparator와 대조했다(포화 product 포함). immutable identity list의 내용 hash를 재사용하며 record API/객체 identity를 유지한다. BROADCAST alias 판정은 structural value별 인덱스를 identity owner로 투영하고 node authority revision에서 재생성한다.
- **관측 비용**: candidate JSONL은 같은 byte를 stream으로 기록하고, receipt ordering descriptor·cursor를 재사용한다. DEBUG가 꺼진 Oracle 로그는 메시지 포맷 준비를 생략한다. audit 자체나 출력항목을 끄지 않는다.
- **집중 회귀**: exact5클래스95PASS, broadcast82PASS(기존ignore1), direct/boundary39PASS, hash/raw-bit/메모리9PASS, publication/identity/audit10클래스105PASS. 독립 hash oracle·collection 방어복사/수정거부·JSONL 동일byte 검증 포함. 별도 실행한 기존 PlacementIdentityKnownEqualityContractTest의pca fixture1건은 변경 전d26에서도 같은PLACEMENT_FUNCTION_ROOT_UNPROVEN으로 실패함을 보존했다(이번 변경의 성공 테스트로 세지 않는다). 추가 direct 입력준비/지연 single-partition proof 작업 및 중앙검증은 진행 중이다.

- **후속 screen/구현**: screen02 common16.801초·전체24.474초, screen04 common17.214초·전체24.018초로 모두10초 미달이다. 모델16계수·fingerprint·시간 제외1,224 checkpoints 동치 및 audit/conversion0은 유지했다. 최종 반복 수치가 아닌 단일 screen으로 보존한다. Direct 입력 준비·불변 fact snapshot·VALUE_MAP reference/realization lookup 인덱스와 초기 없는 single-partition proof의 지연 생성으로 불필요 반복을 줄였다.
- **boundary 계산**: exact boundary 내부 assignment의 mixed-radix 열거 순서는 유지하면서 child scope의 cell index를 증분 갱신한다. 검토에서 지적된 messages×axes scratch는 실제 scope 교집합에 비례한 sparse 배열로 바꾸고 singleton-only 시 준비를 생략했다. 기존 무작위 union oracle·precision/tie/pruning 및2,048 singleton message 회귀 등122건 PASS. LogicalBoundary는 한 close 호출 안에서 Session을 공유하고 동일 VALUE_MAP carrier topology를 재사용한다. 독립 cold 알고리즘은 테스트 파일에만 두며 withdrawal/restore/동적 layout/topology 회귀를 실제 cold 경로와 대조한다.
- **문자열 캐시 수명 오류**: common 종료가 active 캐시는 지웠지만64M character 예산 사용량은 남겨 다음 planner weak 캐시가 저장하지 못할 수 있었다. 작은 예산 소진 후 종료 회귀는 수정 전 retained4096≠0으로 실패했다. 실제 active scope가 종료될 때 문자열 weak 맵과 카운터도 함께 해제하며, 반복 종료는 이후 planner 캐시를 유지한다. 관련59건 PASS. 별도로 같은 문자열을 가리키는 identity alias가 예산에 계산되지 않아 무한히 쌓이던 front cache를65,536항목으로 제한하고 정확한 structural fallback을 유지했다. 후보 수 제한이 아닌 캐시 보유량 제한이다. 70,000개 동등 alias 포화 회귀 수정 전 실패/수정 후60건 PASS.
- **추가 검증/위험**: canonical native layout은 별도 immutable wrapper key로 동일 bounded serialization cache를 사용한다. 이미 canonical인 partition은 재생성하지 않으며 새 DurableAnchorKey의 정렬/중복 거부는 유지한다. native layout/boundary20건 및 CFG/native-signature/composition25건 PASS. Identity front cache 포화는 후반 structural hash 비용을 늘릴 수 있으므로 실제 screen/ablation으로 채택 여부를 검증한다. 전체 Maven/동결 최종 반복은 아직 진행 전이며10초 달성을 주장하지 않는다.

- **최종 중앙/조건 검증**: Maven97클래스821건(815PASS/기존ignore6/실패·오류0), package 성공202.156초, 빌드 전후 Java SHA 불변. 이후 weak-cache 테스트를 analysis scope로 감싸 GC 의존성을 없애고 문자열 validation 종류 변경의 정렬/중복 거부를 보강했다. 해당2클래스7건과 package 재검증 성공43.897초, main source 불변. 최종 main/test source/classes를 동결했고 모든 run의 입력34개·fixture/config·runner·dependency·test artifact·Docker4CPU8GiB를 자동 대조했다. 최종 inventory 재hash 및 현재3598개 Java source 동결 일치, baseline main source Git d26 전수 일치.
- **최종 성능/목표 상태**: 기준/수정본을 번갈아 각3회 실행했다. 전체 컴파일 기준27.200/31.780/27.652초, 수정24.566/24.195/20.397초. 평균28.877→23.053초(-20.17%), 중앙값27.652→24.195초(-12.50%). common 평균19.382→16.331초이며 수정 범위13.968–17.794초다. **전체10초 목표는 미달**이며 모든 수정본에서 이를 명시적으로 false로 기록했다. 모델16계수 maxdiff2.22e-16, fingerprint/1,224 checkpoints 동치, audit/conversion0. L2SVM 양쪽1회 모델/4checkpoints/audit smoke PASS. 학습 실행 평균3.083→3.299초이며 학습 가속을 주장하지 않는다.
- **Ablation/채택 해석**: exact 두 파일의 개선을 제거하면 전체23.583초/common16.080초, 나머지 common/identity/publication/관측 개선을 제거하면29.494초/common18.403초였다. 모두 정확성 동치다. 단일 ablation과 실행 변동으로 exact 변경의 독립 wall-time 개선율 또는 각 미세 변경의 기여율은 확정하지 않는다. cold oracle·정밀도·메모리 회귀, 소스 검토로 확인한 중복 계산 제거, 결합본의 반복 감소를 근거로 반영한다. 모듈별 합산 개선율을 만들어내지 않는다.
- **잔여/결정**: target 미달을 실패 은폐 없이 기록했다. common만 평균16.331초이며 전체의70.84%다. 전체 CFG 재사용은 loop seed/privacy/mutable Hop/현재·음성 proof readset을 완전히 검증하는 설계가 먼저 필요하다. seed placement ID 공유는 이미 있으므로 중복 캐시를 추가하지 않았다. 후보 임의 삭제·새 runtime fallback·fixture 축소는 없다. 새 기능 오류는 확인되지 않았으나 큰W1/다른 workload 성능은 미검증이다. 상세는 LOGREG_TOTAL_COMPILE_OPTIMIZATION_2026-10-07_KO.md, 통합 결과는 experiments/logreg-total-compile-20261007/validation.json의 TARGET_NOT_MET 상태로 보존한다.
- **StepLM 게시 전 두 번째 main 통합**: 첫 통합은 `5eaa080449`로 보존했다. 공유 origin/main이 `d26bb59610`으로 다시 전진하여 해당 고정 hash의 LogReg common-analysis memo/SCC 최적화도 통합했다. Production은 자동 병합됐고 session 문서의 양쪽 추가 이력을 보존했다. 테스트 기준은 이제 mutable remote ref가 아닌 `d26bb59610` 통합 소스다. 새로운 CFG replay memo와 action receipt invalidation의 결합을 추가 검증한다.

- **StepLM 통합 게시 검증 완료**: d26bb59610 통합 코드의 common88건+joint/cost/sparse121건=Java209/209 PASS(실패·오류·skip0), Python35/35 및 package/diff check PASS. `steplm-joint-publication-04` 실제 Docker10/10 PASS, StepLM5계수·선택순서 `[3,1,5]`는 CP와 정확히 같고 전체 audit/conversion 위반0이다. 소스3,614개·class/resource4,390개가 테스트·빌드 기준과 같다. 독립 병합 리뷰 CLEAR. 상세는 `docs/experiments/steplm-joint-compact-20261007/publication-validation.json`; 이후20초 목표의 baseline으로 이 게시본을 사용한다.


## StepLM 전체 planning 20초 목표 — 진행중

- **문제/기준**: 사용자의 게시 요청에 따라 joint compact/receipt closure 수정과 최신 main 통합본 `9668cb432f`를 origin/main에 게시했다. 별도 worktree `steplm-planning-20s-20261007`에서 성능 개선을 시작한다. 실제 full-rank 20×5 `ml_steplm_local_matrix` Docker 게시 검증은 전체 compile 104.571702초, 공통 분석14.264420초, DP elapsed88.610979초다. 학습0.731초와 구분한다.
- **검증 계약**: 동일 Docker image/4CPU/8GiB/coordinator3GiB/network none의 fresh JVM 3회에서 compile 각각≤20초; 기존 official `run_LAN_docker.sh`만 실행한다. 전체5계수와 선택[3,1,5], runtime audit 및 class/source overlay, canonical objective≤37040.115993804146을 검사한다. evaluator는 `scripts/fedplanner/evaluate_steplm_planning.py`이며 `.omx/goals/performance/steplm-planning-20s/`에 계약/증거를 남긴다.
- **수정 전 계획**: (1) Docker JFR로 phase별 CPU 비용을 확인한다. (2) 공통 분석에서 소비되지 않는 중간 continuity 재계산을 제거하되 action/value-map 변경 후 정확한 현재 receipt context를 사용한다. (3) 조건부 exact solve의 기존 동치 support/quotient reduction과 동일 root의 준비 cache를 재사용한다. (4) DP boundary merge는 양쪽 numeric cost와 certified lower bound가 모두 무한대인 child cell만 불가능하다고 증명해 sparse support를 순회한다. canonical child 합산 순서·동률 backtrace·독립 lower bound와 resource guard를 유지한다.
- **행동 보존**: 수정 전 differential regression을 먼저 작성한다. 같은 source/version/lifetime/physical support, hard legality, cost raw bits, lower bounds, backtrace를 Cartesian baseline과 비교한다. 비용 개선 없는 기존66조건부 시도 및9.35억 Cartesian merge 조합의 중복 작업을 줄이는 것이 목표이며 후보 삭제/임의 탐색 제한/거짓 수렴/runtime fallback을 도입하지 않는다.
- **잔여/회귀 위험**: 아직20초 미달이다. sparse 순회 순서의 tie 변화, lower bound를 잃는 잘못된 infinity pruning, 현재 receipt가 아닌 캐시 재사용, 조건부 auxiliary closure 누락이 위험이다. 집중 회귀와 실제 동일 Docker 반복 검증으로 감지한다. 대형 ML과 별도 CSV 입력 문제는 제외한다.

- **첫 Docker screen**: 101개 Java 회귀 통과 뒤 실제 compile59.079543초(기준104.571702초), common16.978430초, planner40.239282초였다. cost37040.115993804146/lower19529.714826506737, 모든5계수/선택[3,1,5]/audit가 동일했다. 20초 목표는 FAIL로 보존한다. 첫 sparse join은 논리적935,076,025조합/원래 출력 저장 크기를 바꾸지 않고 실제 입력 읽기만 줄였다.
- **후속 계측과 수정**: profile02의 sparse merge callback이1,508/3,810 CPU samples로 남아 정확한 high/low/lower 응답 profile이 같은 축값을 한 번 계산하도록 확장했다. 원래 dense 출력/table/resource 계약은 유지하고, 실제 출력 좌표로 witness를 복원한다. 제거 순서 comparator는 기존 네 정책/동률 기준을 그대로 두고 점수를 캐시하며, 제거된 정점의 이웃과 새 fill edge의 공통 이웃만 무효화한다. 독립 boolean graph/BigInteger oracle로 전체 순서와 separator를 비교한다. 실제 OOM 처리와 max-heap 정책은 그대로 두고, 성공적인 작은 배열 할당에서 사용하지 않던 total/free snapshot 조회만 제거했다.
- **증거 구분**: 첫 quotient regression은 기존클래스에서 동치 profile 중복 작업 assertion이 실패했다. 새 점수-cache test API가 기존클래스에 없어서 생긴 compile 실패는 버그 재현으로 주장하지 않는다. 점수 최적화는 독립 oracle parity 및 계산 횟수로 검증한다. 후속 성능은 새 Docker screen 전이므로 아직 채택 효과 미확정이다.
- **두 번째 screen**: `steplm-20s-screen-03`은 compile35.078982초/common14.934572초/planner18.147644초/DP12.821352초, Java198건 PASS였다. 비용·하한·학습결과는 기준과 같지만 전체20초 gate는 실패다. profile04에서 quotient 출력을 다시 dense로 확장한 후 profile/minimum을 재검사하는 비용이 남아 확인됐다.
- **압축 저장과 조건부 결과 재사용**: 원래 logical domain/scope 및 사전 resource limit은 유지하고, merge 전 child들의 raw high/low/lower 응답 동치류로 quotient 숫자·backpointer를 저장한다. 다음 merge, marginal, singleton alias와 backtrace는 실제 원래 좌표를 복원한다. 새 factor가 기존 동치류를 나누는 경우도 baseline과 비교한다. optimizer 하나의 동일 block·동일 외부 assignment에서 성공한 exact 결과만 재사용하며 lift와 full canonical acceptance는 매번 수행한다. Java203건 PASS, 독립 저장 의미 리뷰 CLEAR.
- **세 번째 screen**: `steplm-20s-screen-05`는 compile26.082991초/common15.454963초/planner8.722863초/DP4.434218초다. 비용37040.115993804146,5계수와 선택[3,1,5], audit는 동일하다. 실제 retained storage 감소로 기존 resource 정책 안에서 추가 merge가 가능해 4,899merge/12,482,523,649 logical assignments, 하한23216.114867705463으로 개선됐다. 과거와 모든 search checkpoint가 같다는 주장은 하지 않는다. 조건부 actual solve는54회이며 반복12건은 성공 결과를 재사용한다. 전체20초 목표는 아직 실패다.
- **공통 분석 후속 계획**: 전체 CFG의 성공 출력만으로 다음 호출의 no-op을 가정하는 cache는 새 invocation의 loop seed 재설치 때문에 부적절해 제외했다. exact-identity 기회도 진단14회 중0회다. relocation binder는43,584개 재생성 row 중42,353개가 구조적으로 같았으므로, 완전한 재검사 뒤 source parent/action의 현재 authority까지 같은 row만 기존 객체를 유지하고 실제 변경 owner만 inventory를 무효화하는 수정·회귀를 진행한다. 진단 JVM 수치는 Docker 성능 개선율로 사용하지 않는다.
- **공통 분석 중간 측정**: relocation row identity 보존 뒤 screen06은26.748982초로 뚜렷한 시간 개선을 확인하지 못했다. 완전한 direct dirty 집합이 비어 있을 때 인덱스/SCC 준비를 생략한 screen07은25.627615초/planner9.099918초/DP4.414254초다. Java93건 및 실제5계수·선택·비용·audit는 통과했지만20초 gate는 실패다. 소스/receipt/native context 갱신과 이후 VALUE_MAP·action·privacy 검사는 그대로 수행한다.
- **후속 proof/함수 경계 최적화**: generated native proof가 실제로 탐색한 pruning 전 의존 경로에 root 재진입이 없을 때만 root의 publication-history 변경에 따른 재계산을 생략한다. 보수적인 occurrence SCC에 속한다는 사실만으로 모든 조회를 무효화하지 않는다. 함수 경계는 기존 incremental Session을 재사용하되 VALUE_MAP carrier 생성·소멸 시 topology 전체를 다시 만들고, 마지막 구조적 동치 결과도 현재 source parent identity를 보존한다. 독립 cold oracle와 생성·소멸/foreign-owner 회귀 및 중앙 검증 후 Docker 효과를 판단한다.

- **성능 중간 게시 요청**: screen23 전체 compile22.990939초/planner6.348077초/DP3.524877초, 기준104.571702초에서 개선됐지만20초는 미달성이다. 첫 동결3회23.978670/22.038707/23.449315초는 FAIL로 보존한다. 전체578PASS+1기존ignore 검증 후 후속step17은183/183PASS, 마지막 conditional quotient/singleton witness는집중90/90PASS다. sparse boundary quotient, 정확한 elimination score cache, proof read-set, canonical DAG cache generation/prefix sharing, binder action index, 조건부 비개선 witness 검증을 반영했다. 합법후보·cost rawbits·현source/version/receipt authority는 유지한다. 상세 보고서와 원자료/SHA는 `docs/STEPLM_PLANNING_20S_2026-10-07_KO.md`, `docs/experiments/steplm-planning-20s-20261007/`에 보존한다. 최신origin/main 통합 및 추가 최적화를 계속한다.

## 최신 main ALS 확인 — 워크스페이스 정리 및 기존 실패 회귀 PASS

- **요청/기준**: workspace를 정리하고 origin/main 기반으로 ALS를 확인한다. fetch로 확인한 `9668cb432f051d0313c5668719b9adadb0c60314`에 `verify/als-main-20261007`을 고정했다. 실행 중 remote ref가 전진해도 이번 결과의 기준을 바꾸지 않는다.
- **정리**: 이전 tracked 변경은 모두 커밋·푸시 완료 상태였다. 현재 worktree의 target7,259개 file/symlink 항목을 grid의 `als-main-verification-20261007/previous-target-56d2ac628e`로 복사하고 전수 hash/link 일치를 확인한 뒤 기존 target을 정리했다. 이전 alias/cost 증거 경로는 symlink로 보존하고 dependency만 재사용한다. 다른 worktree·외부 실험 자료는 유지했다. 새 main/test classes는 fresh Maven으로 생성한다.
- **검증 대상/조건**: 기존 `CampaignBG014AlsPartitionedComputeCostRedTest.singleWorkerAlsPricesOneReusableCpRuntimeWeightMaterialization` 그대로 실행한다. n50,000·d2,100·rank10·maxi2·PRIVATE_AGGREGATE X·W1 FULL·WAN-light 조건이며 Java17/8GiB/4CPU의 metadata-only compile·비용·Exact/Local DP 선택 테스트다. 실제 데이터 학습이나 lowering 검증과 구분한다.
- **판정 계획**: 이전 실패는 비용 surface preflight의 joint hard-factor overflow였다. 현 main의 compact joint 표현을 포함해 같은 메서드의 통과 여부와 최초 실패 단계를 확인한다. 후보/기대값/한도를 바꾸지 않고, 새 실패가 나오면 동일 실행을 반복하기 전에 stack과 원인을 분리한다. raw command/log/build/source SHA는 `/home/mchoi/als-main-verification-20261007/`에 저장한다.
- **잔여/위험**: 실행 완료 전 ALS 해결을 주장하지 않는다. 원래 메서드는 Exact를 먼저 호출하므로 거기서 막히면 뒤 Local DP도 실패했다고 단정하지 않는다. shared host wall time은 성능 A/B로 사용하지 않는다.
- **최종 결과**: fresh Maven test-compile 성공, Java/builtin/POM 3,808개 파일 SHA가 빌드·테스트 전후 그대로다. 위 원래 메서드는 **1건 실행/실패0/ignore0**, JUnit74.910초(전체 process76.009초)에 PASS했다. 메서드 본문이 이전 실패 기준 `fbfd4d790f`와 동일함을 직접 비교했다. production·기대값·solver 한도는 수정하지 않았다.
- **확인된 범위**: 이전 joint hard-factor preflight overflow가 해소되어 전체 cost surface 구성, 재사용 FULL W2C 전송 비용의 단일 contribution 및 중복 과금 방지 검사, Exact 최적화·selection 검증, Local DP 최적화·selection 검증, 두 planner의 cost fingerprint 일치 및 기존 owner 선택 assertion까지 완료했다. 37.57초 thread dump도 Exact 완료 후 Local DP merge 단계에 도달했음을 보여준다.
- **남는 경계**: 해당 metadata-only ALS 테스트의 기존 overflow는 현재 main에서 해결된 것으로 갱신한다. 실제 분산 학습, CP/FED 수치 비교, plan projection/lowering은 이 테스트가 수행하지 않으므로 검증 완료로 보고하지 않는다. 이전 실패 시간과 새 성공 시간을 속도 개선율로 비교하지 않는다. 검증 기록만 변경했으며 새 구현으로 인한 회귀 위험은 없다.
- **근거**: `docs/experiments/als-main-verification-20261007/validation.json`에 source/build SHA, 정리 receipt, 원래 실행 명령과 이전 실패 대비, 새 stdout/stderr SHA를 저장했다. 원자료는 `/home/mchoi/als-main-verification-20261007/`에 보존한다.
- **플래닝 시간 추가 확인**: 기존 74.910초는 fixture·assertion·Exact·Local DP를 합친 JUnit 시간이라 한 planner의 시간으로 사용할 수 없다. 같은 조건의 외부 테스트 사본에 `System.nanoTime()` 구간 계측만 추가해 fresh JVM 1회 실행했다. 원래 Exact→Local 순서와 모든 assertion을 유지했으며 계측을 되돌리면 원본이 정확히 복원된다. production class overlay는 없고 원본 source 3,808개 SHA도 그대로다.
- **계측 결과**: fixture/parse/rewrite 1.109초, 공통 분석 9.426초, 물리 모델 0.617초, 비용 surface 1.134초, Exact solver 2.996초/selection 검증 0.094초, Local DP solver 53.428초/selection 검증 0.029초다. 공통+물리+비용+각 solver+selection의 구간 합은 **DP 64.634초**, Exact 14.267초다. 새 계측 테스트는 1/1 PASS(실패·ignore 0), JUnit 68.856초다. 따라서 이 실행에서 가장 긴 플래너 구간은 Local DP solver였다.
- **시간 해석/잔여**: 위 합은 동일 회귀 안의 구간 합이며 각각 독립 cold DP/Exact 전체 컴파일 시간이 아니다. Local은 Exact 다음 실행이므로 JIT/cache 순서의 영향이 가능하다. metadata 기반 단일 shared-host 관측이고, plan projection/LOP 생성/학습을 제외한다. Docker 성능 비교나 개선율을 주장하지 않는다. `timing-validation.json`, `timing.stdout.log`, `timing-only.patch`에 구간값·계측 변경·검증 명령을 보존했다. 수정은 기록뿐이며 oracle/비용/후보/solver 로직을 바꾸지 않았다.


## LogReg 최적화의 최신 main 통합 — 검증 완료

- **문제/환경**: LogReg 최적화 `5f9ede00f1`을 게시하기 전 fetch에서 origin/main이 `9409f503d8`로 전진했음을 확인했다. StepLM joint proof/relocation support 수정과 `PlacementRelationClosure` 및 세션 기록이 겹쳤다.
- **해결/수정 파일**: upstream의 component 순서에 따른 relocation binding 및 revision 갱신을 보존하고, 각 replacement에 기존 `retainUnchangedPrivacyFact`를 적용해 동일 객체 authority의 재사용을 결합했다. 세션 문서의 두 append는 모두 보존했다. oracle/privacy/TR-TW/후보 허용 규칙은 추가로 수정하지 않았다.
- **검증 계획/상태**: 통합 전 3,598개 Java source가 검증된 동결본과 일치함을 확인하고 LogReg 변경을 별도 커밋했다. 통합 후 exact/joint/direct/relocation/identity 회귀와 Maven package, Docker 실제 LogReg 학습의 모델·audit 및 계획 동치를 재검증한다.
- **잔여/위험**: 이전의 평균 컴파일 23.053초는 통합 전 동결본의 측정이다. 최신 main 결합본에 그대로 적용하지 않는다. SCC 갱신 순서와 identity 재사용의 상호작용은 새 upstream 회귀와 실제 학습으로 감지한다. 전체 10초 목표는 계속 미달이다.

- **통합 검증 완료**: Java 102클래스 838건 중832PASS/기존ignore6/실패·오류0, Maven package 성공208.732초, 빌드 전후3,604개 Java source SHA 불변. Python harness35건 PASS. 독립 병합 검토에서도 correctness blocker가 없었다.
- **실제 학습/기준 차이 확인**: `logreg-r2-main9409-01`에서 harness/container/model-proof 성공, CP/FED16계수 maxdiff2.22e-16, audit/conversion 위반0, analysis fingerprint 일치. d26의1,224 checkpoints와 비교하는 기존 evaluator는 upstream의 joint factor 변경으로 false/exit1을 반환했으며 그 결과를 보존했다. main 게시 검증 `steplm-joint-publication-04`의 main source1,645개가 Git9409와 전수 일치함을 확인하고 다시 비교해 시간 제외1,228 checkpoints 전부 동일함을 증명했다. 입력 fixture/config/image/dependency도 일치한다. 이는 실패를 무시한 것이 아니라 upstream 기준의 의미 변경을 분리한 검증이다.
- **최종 관측/잔여**: 통합 smoke1회 common15.885초, 전체 컴파일22.554984초, 학습3.638초. 반복 성능 개선율로 사용하지 않으며 전체10초 목표는 미달이다. 원자료는 `logreg-common-round2-20261007/integration-9409`, 게시 근거는 `docs/experiments/logreg-total-compile-20261007/publication-validation.json`이다. 새 회귀는 확인되지 않았으며 대형W1 및 타 workload 성능은 이번 게시 검증 범위 밖이다.

## STEP-LM evaluation / engine origin-main 동기화 — 진행 중

- **문제 정의**: cofee-evaluation main c951455에는 maxi10/max_features2 호출, STEP-LM의 CG 직접 호출, lmCG의 Xt 재사용이 있지만 SystemDS main260ec54에는 builtin 변경이 없어 main만 빌드하면 동일 실험이 되지 않았다. 평가 snapshot은 자동 설치되지 않는다. 이전 compatibility worktree는 max_features만 옮겨 CG 호출/재사용이 빠졌다.
- **경계/계획**: 실험 설정은 evaluation caller가 소유하고 실행 builtin은 SystemDS에도 구현되어야 한다. 신규 main 기반 worktree에서 평가 저장소의2개 builtin, Python 설명, 관련2개 테스트를 그대로 동기화한다. max_features 기본값0/전체 후보/output shape를 유지한다. LogReg·shared encoding·Local DP 등 다른 미커밋 작업은 포함하지 않는다.
- **추가 재발 방지**: matrix 초기화의 기존 source/JAR freshness 검사에는 builtin DML이 빠져 있었다. 평가 정본↔engine source↔실제 JAR의2개 builtin bytes를 검사하고 불일치 시 명확히 실패시킨다. 실행 중/frozen stage를 자동 덮어쓰거나 runtime fallback하지 않는다.
- **검증 계획**: 기존 main builtin에 평가 regression10개를 먼저 실행해 RED를 확인하고 새 main 빌드로 동일 회귀·JAR resource 및 offline preflight를 확인한다. 원자료는 `/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-origin-sync-20261007/`에 저장한다. 예전RED는 고정된 기존 compiler classpath에서 현재 main builtin을 읽는 parser/numerical 검사로서 최신 main 전체 빌드 성능을 의미하지 않는다.
- **잠재 회귀/잔여**: STEP-LM은 명시적으로 CG를 사용하므로 이전 main의 DS 자동선택과 수치/학습시간이 다를 수 있다. 기존 요청의 CG 실험조건을 복원하는 변경이며 전워크로드 runtime 개선은 주장하지 않는다. 기존 root resume은 새 source identity와 섞지 않아야 한다. full runtime matrix는 이번 동기화에서 실행하지 않는다.
- **최종 검증**: 기존 main builtin에서 regression10개 중8개가 max_features/CG dispatch/loop transpose assertion으로 실패한 RED를 보존했다. 수정 후 새 main260ec54 기반 Maven package BUILD SUCCESS, 실제10/10 PASS(실패·오류·skip0)다. SystemDS/evaluation runner 회귀는 각각32/32 PASS, Python 문법/diff check PASS다.
- **실제 JAR 검증**: 방금 빌드한 JAR의 `scripts/builtin/steplm.dml`, `lmCG.dml`가 source/evaluation 정본과 정확히 같다. 이전 main의 실제 JAR는 새 preflight가 stale builtin bytes로 거부했다. 수정한7개 source/test/runner 파일은 두 저장소에서 byte-for-byte 일치한다.
- **동기화 부수 차이**: 평가 runner에만 남아 있던 기존 cleanup 들여쓰기 및 profiling CSV 열 누락을 현재 SystemDS main의 버전으로 맞췄다. 새 cleanup 정책을 임의로 만든 것이 아니라 이미 main에 있는 파일을 그대로 동기화했으며 양쪽 전체32개 offline 회귀가 통과했다.
- **게시 범위**: 기존 evaluation의 maxi10/max_features2 설정은 그대로이고 별도 planner Java 수정/LogReg 수정/실험 재시작은 포함하지 않는다. 이번 결과는 source/JAR/호출 그래프/수치·예산 회귀 검증이며50K×128 전체 플래닝이나 학습 runtime 완주를 뜻하지 않는다. 기록은 `steplm-origin-sync-20261007/validation.json`, `build-test.log`, `guard-validation/`에 보존한다.

- **StepLM 성능 중간 main 통합 게시 검증**:09588f7f4d와origin0b6dc23522186bf8d67bfbcfb1bb82c905abf4e2를 통합했다. 압축DP 내부좌표 delta/rollover, direct read-set+eligibility/no-op wave, native snapshot/handle 권한을 함께 검증했다. 중복realization/broadcast 인덱스는 하나로 유지하며 테스트reflection2건을 조정했다. Java656PASS+기존ignore1/Python78PASS/packagePASS/독립reviewCLEAR; actualDocker11/11PASS, StepLM전체compile17.911837초,5계수/선택[3,1,5]정확일치/audit0이다. 다만upstream은lm→lmCG로 workload를 바꿔 비용65701.88346157457을 과거37040.115993804146과 직접비교하지 않는다. 3회20초검증은진행중이다. 상세 `docs/experiments/steplm-planning-20s-20261007/publication-validation.json`.


## StepLM 중간 게시 후 시간 변동과 workload 분리 — 진행중

- **문제/조건**: f298378925를 origin/main에 게시했으나 원래 lm workload의 fresh JVM 3회가 20.870906/19.681566/19.244135초여서 20초 목표는 실패다. 전체 compile을 측정하며 Docker/리소스/입력은 유지한다.
- **원인/해결**: 새 upstream의 lmCG builtin은 별도 workload다. 원래 lm 비교를 보존하고 origin0b6의 검증된 Java/classes와 정확한 새 builtin으로 실제 CG baseline을 실행했다(91.989792초, 비용65701.88346157457, 정확성 PASS). 평가기는 builtin 전체 fingerprint와 각 실행의 class inventory 재hash를 검증하며 비용 상한을 실제 baseline에서만 가져온다.
- **추가 수정**: CandidateRuleKey의 동일 ordered structural 비교에서 iterator를 제거하고 DP boundary RHS 임시 PreciseCost 객체 및 audit hex formatter를 제거했다. 후보/authority/연산순서/비용·tie 규칙은 변경하지 않았다.
- **수정 파일**: PlacementAnalysis.java, ExactCategoricalSolver.java, PlannerRuntimePlacementAudit.java, 대응 회귀 및 scripts/fedplanner/evaluate_steplm_planning.py.
- **검증**: 중앙 Java105/105 PASS, Python evaluator16/16 PASS, package/독립review CLEAR. run_LAN_docker.sh의 legacy-screen28은20.989561초로 정확성만 PASS. 증거는 target/planning-evidence/step18-* 및 steplm-planning-20s-validation-20261007 아래에 보존한다.
- **잔여/위험**: 양 workload 각각 fresh JVM3회≤20초 조건은 아직 미달이다. raw double/signedzero/overflow, record equality/hash충돌, 잘못된 UTF-16/locale 회귀로 미세 변경의 의미 차이를 감지한다. 큰 모델/CSV는 제외하고 resource 정책·합법 후보·runtime 규칙을 완화하지 않는다.

- **후속 검증/변경**: support count/fill의 동일 좌표 decode를 O(scope rank) cursor로 바꾸고 기존 strict-majority 기준이 확정되면 count만 종료한다. factor/후보를 제거하는 조건이 아니다. entry 수가 알려진 identity map9곳은 정확한 크기로 초기화한다. source/key/value authority·정렬·산술·tie·resource cap은 유지한다. 독립review CLEAR, isolated51PASS, 중앙171건 중170PASS/기존ignore1/실패·오류0다. package 이후 원래lm/현재lmCG 각각 동결fresh JVM3회를 진행한다.

- **최신 동결 실패**: step19 원래lm3회22.836013/21.851988/18.783427초, 현재CG3회21.698392/19.212769/19.255013초로 둘 다 시간 조건만 FAIL. 비용·모델·선택·artifact/builtin/resource 검증은 모두 PASS다.
- **signature front 포화**: 작은 StepLM compile-only telemetry에서65536 identity front가6,668,400회 포화 상태였고1,714,667 admission이 거절됐다. private identity front만 다음 admission에서 교체하여 structural fallback1773560→255426회(-85.6%), rotation3회, canonical miss6643회 동일을 확인했다. structural String cache/64M character 예산/weak cache/authority arena는 유지한다. PlacementIdentity.java와 AnalysisScope 회귀를 수정했다. 양쪽 compile-only 테스트 및 신규 scope6회귀 PASS/독립review CLEAR; 중앙/Docker 검증은 진행중이다. 이전 hotalias 재조회 비용은 실제 반복측정으로 확인한다.

- **후속 중간 게시 근거**: `4736b23e01`의 중앙 step20은116PASS/기존ignore1/실패·오류0, package PASS다. 동결 Docker 원래lm3회20.507247/21.926934/21.471499초, 현재CG3회19.225814/21.122758/18.991695초로 시간 목표만 FAIL이다. 6회 모두 비용·전체5계수·선택·audit·artifact/source 검증 PASS. 실행시간 변동과 모든 실패를 보고서·JSON에 보존하며 중간 개선분을 게시한다. 목표는 계속 진행중이고 resource/후보/비용 규칙 완화는 없다.
