# 세션 이슈 — 2026-10-07

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
