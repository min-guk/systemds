# Session issues — 2026-09-23

## 전체 workload의 현재 P/E 동등성 인증

- **상태:** 실행 중. 전체 EQUAL 인증은 아직 성립하지 않는다.
- **문제:** planning 224조건과 registry에서 전개한 388개 후보를 포함한 전체 조건에서 P와 E의 모든 물리 plan을 정확히 비교해야 한다. planning 외 조건은 기존 placeholder만으로는 컴파일 입력이 확정되지 않았고, 큰 조건의 raw 공간은 직접 열거할 수 없다.
- **분모 증거:** planning 224조건은 원래 동결된 입력을 유지한다. 추가 후보 388개 중 base/ML10 296개, generated microbench 28개, KDD98/USCENSUS SliceLine 64개를 `FROZEN_COHORT_DERIVED_ARGV_612`에 결합했다. 612개 입력 준비·0개 미해결, 생성기 재생성 검사가 통과했다. 초기 derived-v2는 296개 compiler argv 누락으로, v3는 P2 campaign 16개 필수 JVM 옵션 누락으로 최종 실행 근거에서 제외했다. 현재 v4 catalog SHA는 `7d55c9f469077038f6d1b5a3a6a4ef5887bc55089712c2d74c1738b4812376ac`다. SliceLine 64개는 source-backed 파생 compile-model 조건이므로 historical `FULL_CURRENT` 관측치로 승격하지 않는다.
- **구현·검증:** compact physical dictionary/reference와 독립 verifier, 저장 E factor의 정확한 accepted count, 전체 campaign을 누락 없이 다루고 미완료를 실패로 보고하는 matrix runner를 추가했다. 동결·모델 수집·artifact 재검사를 묶는 한 명령 게이트와 source/class snapshot 도구도 추가했다. 물리 비교는 검증된 P/E 모델 matrix를 재사용하며 두 사본의 해시를 재검사한다. Python 단위 테스트 115개가 통과했다. Pca 한 조건의 P compact proof 377,856개와 E compact proof 479,232개는 각각 물리 600개, 차집합 0이고, 기존 전체 JSON과 compact를 P/E 9/9 shard에서 byte 수준으로 비교했다. 이 Pca 결과는 이전 source binding의 캡처 집합 비교이며 P acceptance 전체 인증은 아니다. planning LM w3/LAN의 물리 60개 일치 결과도 뒤늦게 확인한 loop-seed 결함이 있는 v3 빌드의 진단 자료로만 보존한다.
- **P acceptance 위험:** 그래프 구조만으로 Java의 모든 acceptance를 증명하지 못한다. candidate support/입력/relocation의 원시 DTO와 Python 재구성기가 실제 동결 P1 artifact의 candidate receipt 2,118개·realization reference 1,669개 및 relocation 관련 원시 사실을 검증했고 원시 source owner 변조도 거부했다. assignment별 feasible variant·worker pool을 포함한 opaque predicate가 남아 있어 runner는 equality를 `CAPTURED_EQUAL` 이하로 제한한다. 새 증거가 적용된 모델과 과거 artifact도 분리한다.
- **P1/P2 위험:** P1의 E capture에서 함수 출력 call ordinal과 CFG 경로 정규화를 수정해 대표 조건을 캡처했다. GMM/GLM의 인라인 함수 입력 provenance를 compiler-owned 사실로 옮겼으며, 최적화로 사라진 입력과 원본 누락을 구분하는 최종 nullable 패치는 실제 GLM/GMM 재검증 중이다. P2 campaign의 JVM 옵션 누락은 원본 protocol에서 condition hash까지 전달하도록 고쳤고, 교체 셀 w3/wan_heavy P/E 캡처와 저장 artifact 재검사가 모두 통과했다. 저장 E factor 관계는 P1/P2에서 재생되지만, native 모델에 검증된 compositional 물리 projection과 physicalLogicalInputs가 없어 canonical 물리 plan 집합을 재구성할 수 없다. 좌표 표 이름/크기만으로 `COMPLETE`가 되던 잠재 경로를 차단했다.
- **다음 검증:** v2 빌드·v3 catalog의 진단 matrix를 P 264개·E 284개 영수증에서, v3 빌드·v4 catalog의 진단 matrix를 P 64개·E 64개 영수증에서 중단했다. 후자는 loop-seed 재시도 누락 때문이며 proof-state revision·완료 replay memo로 수정하고 실제 L2SVM canary와 집중 회귀를 통과했다. 통합 `current-pe-final-build-v4`를 동결하고 612개 P/E 모델 matrix를 병렬 진단 중이다. 초기 GLM P 캡처가 raw DTO nested 서명 구성에서 Java heap OOM으로 `NO_CAPTURE`되어 수정 중이며, 이를 합법 plan 부재로 해석하지 않는다. P acceptance의 남은 assignment 의미론, E opaque hard factor, 거대 셀의 구조화된 양측 물리 projection을 해결해야 전체 gate를 열 수 있다.
- **역사·runtime 범위:** 과거 B0/B1 비교와 독립 runtime R 인증은 이번 P/E equality와 별도 미완료로 유지한다.

## W1357 PCA·LM: 실험 worker privacy와 planner 집계 규칙 불일치

- **상태:** 원인 분석·해결 계획 작성. 구현 미착수, 이번 분석에서 테스트/실험 실행 없음.
- **환경/조건:** W1357 X=50,000×128 PRIVATE_AGGREGATE, LM Y=PUBLIC, W1/LAN. 기존 실패는 실험 branch `4a5e99eae4` 계열이고, 최신 계획 진단은 origin/main `8d07f02`를 병합한 `40d6d6f3`이다. 순수 origin/main의 runtime 실패라고 부르지 않는다.
- **재현 증거:** `/home/mchoi/cofee-evaluation/runs/w1357-bounded-20260923/bounded-pilot-0eab57fed628/coordinator.stderr:20`의 `uacmean X → _mVar9`, `.../bounded-pilot-f3f378d28d33/coordinator.stderr:20`의 `replace _mVar9 → _mVar10`, `.../bounded-pilot-24fe3afa6966/coordinator.stderr:20,55`의 `prefetch _mVar10`/PRIVATE 반환 거부. LM은 `/home/mchoi/cofee-evaluation/docs/evidence/W1357_PREVIOUS_LM_PRIVACY_DENIAL_2026-09-23.txt`의 `FED ba+* ... X ... LOUT` 미지원 거부. 기존 증거를 읽었으며 새 재현 실행은 하지 않았다.
- **원인:** `FederatedPlannerUtils.derivePrivacyConstraint`는 AggUnary/AggBinary 결과를 aggregate-to-public으로 취급하지만, 실험 branch에 추가된 `FederatedWorkerPrivacy`는 `uacmean`을 비공개 PRIVATE로 만들고 AggregateBinaryCPInstruction을 지원하지 않는다. 허용 명령 중 공개 전환은 `uak+`만 지원한다. PCA 첫 전송 대상은 소스/로그상 `scale.dml`의 정제된 열 평균으로 연결되므로, 단순히 prefetch가 있다는 이유로 원본 X 유출 계획이라고 규정하지 않는다. LM은 `lmDS.dml:93`의 XᵀX와 대응한다.
- **해결 방법(계획):** 원본 반환 거부는 유지하고 실제 PCA/LM 연산의 실행·집계 결과 공개 계약을 함께 보완한다. Positive/negative 회귀 → worker 규칙 보완 → planner/worker 일치 검사 → 새 stage의 제한된 Docker 확인 순서다. 임의 opcode 일괄 공개, runtime fallback, workload 변경은 하지 않는다.
- **수정 파일:** 이번에는 이 이슈 문서만 갱신. 전체 계획은 `/home/mchoi/cofee-evaluation/.omx/plans/COFEE_W1357_PRIVACY_CONTRACT_REPAIR_PLAN_2026-09-23.md`; 평가 저장소의 기존 재-planning 보고서에는 해석 정정 추가. Production 코드 변경 없음.
- **검증 방법/결과:** 코드·commit 이력·기존 로그를 대조. 새 build/test/runtime 결과 없음. 계획에서 W1 aggregate positive와 raw/alias/slice negative, 작은 전체 DML 경로, 불균등 W3 집계 검증을 정의했다.
- **잔여 이슈:** 실제 후속 연산 지원, multiworker 부분 통계의 공개 범위, 수치 reference 비교, 전체 campaign launcher/배포는 미완료다. W1의 전역 집계 지원 누락 수정은 multiworker 공개 범위의 미확정 때문에 보류할 필요가 없다.
- **잠재 회귀 위험:** 집계 지원을 원본 공개 우회로 확대하거나 합법적 후보까지 제거할 위험. Paired positive/negative 및 planner/runtime contract 검사로 감지한다. 이 분석은 모든 DML 보안 인증을 요구하지 않는다.
- **의사결정 근거:** 정책 완화가 아니라 **실험 runtime 지원과 planner 집계 의미의 정합성 복구**가 우선이다. prefetch 개수 감소나 plan hash 변경은 성공 조건이 아니다.

### 후속 사용자 결정: 독립 worker 검사는 test-only로 분리

- **상태:** 수정 계획 갱신, production 코드 미변경. 사용자가 worker 검증 계층을 테스트 용도로만 남기고 production·실험에서 제외하는 방향을 제시했다.
- **적용 경계:** 신뢰하는 coordinator/planner가 privacy를 판단하고 worker는 선택한 계획을 실행한다. Coordinator의 기존 privacy/placement 제약은 유지하며, 임의 직접 GET_VAR의 worker 거부는 실험 자격 조건이 아니다. 악의적 coordinator를 방어하는 배포라고 주장하지 않는다.
- **해결 방향 변경:** 위의 worker 집계 지원 확대 구현안은 진행하지 않는다. Prototype 및 label 상태를 test-only fixture로 옮기고 handler/cache/local-data/Data/List 연결을 production에서 제거하는 계획으로 대체한다. 일반 타입/차원·입력·오류 검사와 planner/runtime audit는 보존한다.
- **주의할 연결:** `BoundedWorkerWarmup.java:76–94`가 prototype label에 의존하므로 함께 분리해야 한다. 예열 source/path/type/크기·입력 미변경·값 미반환은 유지하며 privacy metadata 검증은 기존 stage/coordinator 쪽 증거로 기록한다.
- **계획 문서:** `/home/mchoi/cofee-evaluation/.omx/plans/COFEE_W1357_WORKER_PRIVACY_TEST_ONLY_PLAN_2026-09-23.md`.
- **검증/위험:** 향후 main/JAR/classpath에서 prototype 부재, coordinator privacy 회귀, 예열과 PCA/LM 실제 경로를 확인한다. 테스트 fixture가 production의 공개 판단 정답으로 재사용되지 않도록 구분한다. 이번에는 코드 검색·계획/이슈 문서 갱신만 했고 build/test/runtime은 실행하지 않았다.

### test-only 분리 구현 및 로컬 검증

- **상태:** production 소스와 테스트 분리 구현 완료, 새 stage 및 실제 W1/LAN 재진단은 진행 전이다. 위 두 절의 “미구현”은 작성 당시의 기록이다.
- **환경/조건:** SystemDS 진단 branch `codex/w1357-main-replan-8d07f02`, X=`PRIVATE_AGGREGATE`, 신뢰하는 coordinator/planner. 임의 직접 worker API 접근 방어는 범위 밖이다.
- **재현 절차/관측:** 기존 PCA `uacmean → replace → prefetch`와 LM `ba+*` 거부 근거는 위 절의 로그를 사용한다. 이번 로컬 회귀에서는 동일한 worker prototype 경계가 제거된 handler의 작은 PCA/LM 연산을 직접 실행한다.
- **원인/해결:** coordinator가 계획한 합법 집계 경로를 별도 worker allowlist가 거부했다. `FederatedWorkerPrivacy`와 `WorkerPrivacyLevel`을 production에서 제거하고 기존 정책 의미는 `src/test`의 독립 fixture 상태로 옮겼다. READ/PUT/GET/EXEC/UDF의 prototype 호출만 삭제했고 일반 명령·타입 오류, planner audit, read cache, 결측 변수 복구, bounded warmup의 ID/path/크기/3회 제한은 유지했다.
- **수정 파일:** `FederatedWorkerHandler.java`, `FederatedWorkerPrivacy.java`(삭제), `FederatedReadCache.java`, `FederatedLocalData.java`, `BoundedWorkerWarmup.java`, `Data.java`, `ListObject.java`, `BoundedWorkerWarmupTest.java`, `FederatedWorkerPrivacyTest.java`, 새 `FederatedWorkerPrivacyTestFixture.java`.
- **검증:** `mvn -q -DskipTests compile` 성공. `mvn -q -Dtest=FederatedWorkerPrivacyTest,BoundedWorkerWarmupTest,PrivacyMovementCertificationTest,MixedPrivacyRelocationContractTest,PrivacyDerivedMaterializationClosureTest test` 성공(27 테스트, 5 skip, 실패/오류 0). LM 회귀 추가 후 `mvn -q -Dtest=FederatedWorkerPrivacyTest test` 성공(9 테스트, 실패/오류 0). 최종 clean package 및 JAR/classpath 부재 검사는 별도 후속이다.
- **잔여 이슈:** 실제 PCA/LM 전체 DML 결과·참조값 검증 전이다. 새 JAR·seal·protocol과 Docker worker 재배포 전에는 이전 stage로 성공을 주장하지 않는다.
- **잠재 회귀 위험:** 직접 `GET_VAR`는 이제 worker가 차단하지 않는다. coordinator/planner를 신뢰하지 않는 배포로 오용하면 보호 원본이 노출될 수 있으므로 해당 신뢰 모델은 실험 receipt/protocol에 명시하고 봉인된 production classpath를 검사한다. 완전한 planner privacy 보증은 로컬 작은 회귀만으로 확립되지 않는다.
- **의사결정 근거:** 금지된 runtime fallback이나 planner 후보 축소가 아니라, 사용자 승인에 따라 중복 worker 독립 판단을 production에서 제거하고 coordinator 책임으로 되돌린 것이다.
