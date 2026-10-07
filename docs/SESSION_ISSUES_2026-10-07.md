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
