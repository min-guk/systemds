# 2026-10-13 세션 이슈

## FedPlanner 단계별 동작과 병목 설명 문서화 — 작성 완료

- **증상/요청**: 사용자가 조합 전개, 중복 처리, 여러 HOP에 걸친 의존성 확인 중 무엇이 실제 병목인지 이해할 수 있도록 설명 문서를 요청했다.
- **코드/측정 기준**: 코드 `33384c0054becbfefba6a4e51a5de7aefe745988`; 성능은 v41 COFEE 50K×128 W1 정상 실행. 최신 v43 실측과 혼동하지 않는다.
- **원인 분석**: Oracle execution region의 candidate별 재열거, 지원 family의 제한, native proof DFS와 gate 재사용 제외, Closure worklist의 후보 재생성, Physical Model의 입력·clause 재전개가 코드에 존재한다. 정상 지표에서 많은 누적 proof 소비·객체 생성과 PlacementAnalysis 시간이 확인된다.
- **해결/변경 요약**: 단계별 설명, 예시, 코드 링크, 복잡도의 조건, 실측표, 확인 사실과 미확인 범위를 별도 보고서로 작성했다. 알고리즘·oracle·runtime 동작을 수정하지 않았다.
- **수정 파일**: `docs/FEDPLANNER_ALGORITHM_AND_BOTTLENECKS_2026-10-13_KO.md`, 이 세션 기록.
- **검증 방법/결과**: 기준 커밋과 비교 artifact SHA-256, 두 workload 표의 수치를 대조했다. Repository 상대 링크 23개와 해당 줄 번호의 존재, Markdown fence 짝을 확인했다. 첫 공백 검사에서 Markdown 줄바꿈용 trailing space 2건을 발견해 빈 줄로 바꿨다. 문서만 추가하므로 실행 테스트를 새로 수행하지 않았다. 문서 검증은 성능 목표 달성 증거가 아니다.
- **잔여 이슈**: 최초 문서 작성 당시 v43 측정은 미완료였다. 아래 후속 측정으로 갱신했다. Native gate 개선, end-to-end relation 유지와 20초 목표는 별도로 검증해야 한다. 모든 방문을 불필요한 중복으로 분류할 수는 없다.
- **잠재 회귀 위험/감지**: 코드 동작 회귀는 없으며, 누적 작업량을 고유 합법 후보 수로 오해하거나 예시 복잡도를 실제 알고리즘의 보편적 복잡도로 오해할 수 있다. 문서에 각 수치와 예시의 범위를 명시했다.
- **의사결정 근거**: source/action/proof 및 공유 비용 의미를 유지해야 한다는 기존 계약을 바탕으로 설명했다. 합법성 완화나 후보 임의 삭제를 해결책으로 제시하지 않았다.

## v43 실제 COFEE 측정 — 정확성 통과, 성능 목표 실패

- **기준**: 커밋 `33384c0054`, frozen JAR SHA-256 `9862117114b2746f579c5b504f441a3a46e8370f14907662cf47a80573e29eb8`. 기존 Docker harness와 COFEE 50K×128 W1 조건 유지.
- **결과**: 전체 초기 플래닝 LogReg 388.771218709초, GLM 121.376056740초. v41 대비 각각 +21.305282667초, −2.734416632초. 각 1회 관측이며 인과관계·일관된 개선을 주장하지 않는다.
- **동등성**: numeric comparator PASS, audit mismatch 0, 원문 선택·section·objective raw bits·certificate·Cost Surface fingerprint 일치. 모든 assignment의 비용 전수 동등성은 미입증이다.
- **작업량**: LogReg proof 소비·graph 수·rule fact 생성 불변. Skeleton 재사용 약 371만 회와 clause 객체 1,536개 감소만으로 전체 작업량 문제를 해결하지 못했다.
- **후속 조치**: 기존 성능 evaluator에 FAIL checkpoint 기록. 변경 없는 physical consumer 재생성과 root에 독립적인 native gate의 반복 생성·방문을 줄이는 변경을 별도로 구현·검증 중이다.
- **증거**: `evidence/cofee-50k128-v43-validation/v41-v43-comparison.json`, SHA-256 `e8070a6df3e19ed0fd5f018d4141ff8c307a47d4ab6ecf1c1b3a7c07e430003a`.

## Native gate 공유와 physical no-change frontier — 병합 전 검증 통과

- **원인**: 질의 root가 달라도 동일 relation의 입력 gate와 의존성을 다시 만들었다. Physical closure는 동일 snapshot의 변하지 않은 consumer를 여러 dirty input edge에서 반복 재생성했다.
- **변경**: 정확한 fact/realization/relation identity 및 witness/pivot/axis가 같은 gate를 기존 bounded component summary와 함께 보관·퇴거한다. 전체 source 및 숨은 metadata footprint가 현재 root에 의존하면 summary를 재사용하지 않는다. Revision 간 gate 이동은 금지한다. Physical frontier는 호출 내부에서만 유지하며 모든 authority commit에서 초기화한다.
- **실제 생성량 검증**: 두 dirty input의 동일 consumer에서 keys 4→2, facts 28→14, clauses 20→10. 폭 256 gate에서는 새 코드의 cold 질의 대비 warm 질의가 alternative 512→256, 의존성 방문 1,028→516이었다. 이 gate 비교는 이전 코드의 warm 질의와 비교한 결과가 아니며, 아래 직접 비교에서 cap=1 회귀가 발견됐다. 논리적 후보는 동일하다. 이 작은 fixture의 수치를 COFEE 성능으로 일반화하지 않는다.
- **반례**: 중간 source 변경 후 frontier 초기화를 제거한 복사 빌드는 FULL/ROW가 되어야 할 consumer에 FULL/FULL을 남겨 실패했다. 초기화가 있는 버전은 전체 nodes/keys/facts/source/proof 비교를 통과했다.
- **검증**: 선택된 FedPlanner 회귀 1,453 PASS / 186.537초. 실행 중 class/resource 변경 0, source mismatch 0. 별도 gate 반례 복사 빌드 156 PASS / 기존 ignore 1: 값은 같지만 identity가 다른 fact/realization/relation의 gate 차용 금지, revision 후 gate 미이월 및 cold 결과 일치. Metrics on/off parity 및 독립 source review CLEAR.
- **미완료**: 병합 전 결과다. 새 origin/main의 CP family closure 수정과 통합한 뒤 재검증하고 실제 COFEE를 실행한다. 전체 candidate/Physical Model/DP가 완전히 relation-native가 됐다는 주장은 하지 않는다.

## Origin/main에서 병합한 CP family closure 기록

## V53: exact row / CP family 표현 전환의 closure 계약 (진행 중)

- **문제/재현**: `WeightedRelationNativeClosureParityTest.dynamicRemoveEmptySourceMatchesExplicitFullClosure`에서 NROW의 두 exact row가 같은 후보를 보유한 CP family로 바뀌자 `Post-CFG physical candidate closure is not monotone`가 발생했다. 기존 199-class gate는 1,559 tests / error 1로 실패했다.
- **근본 원인**: physical closure의 변경 감지·실행 가능한 이전 권한 판정·단조성 증명이 명시적 key/fact만 보았다. Exact→family뿐 아니라 family-only 변경도 검증과 후속 전파에서 빠질 수 있다.
- **계획/결정 근거**: oracle/privacy/runtime 규칙이 아니라 후보 표현 경계의 검증 계약을 수정한다. 재생성 전후 owner-local family snapshot을 보존하고 전체 header와 ordered axes로 변경을 비교한다. 기존 predecessor-domain 증명에 family 축을 포함하며, 증명된 입력 위치만 잘라낸 이전 family의 생존 직사각형을 replacement family와 exact row가 빠짐없이 덮는지 검사한다.
- **비열거 증명**: 현재 publication은 family/family 및 family/exact 겹침을 금지한다. 이 계약에 맞춰 겹침 없는 교집합의 BigInteger cardinality 합으로 coverage를 증명한다. Cartesian member 생성, `requireExact`, 후보 삭제, guard 제거, 압축 비활성화는 사용하지 않는다. Owner 객체 identity와 capability/shape/profile/emission 전체를 비교하고 normalized signature로 대체하지 않는다.
- **수정 예정 파일**: `CandidateFamilyRefinement.java`, `CandidateDomainRefinement.java`, `PlacementCandidateGenerator.java`, `PlacementRelationClosure.java` 및 대응 회귀 테스트.
- **검증 계획**: 기존 오류 재현 → exact/family 양방향·축 축소·singleton·누락 interior tuple·다른 owner/header·대규모 product 무전개 테스트 → 통합 gate → 독립 검토 → 동일 Docker OFF/detailed LogReg/GLM 재측정.
- **잔여 이슈**: CP family 외의 compressed relation 전체에 대한 일반 계약은 이번 수정의 완료 범위가 아니다. 전체 ML 연산 coverage와 20초 목표도 미완료다.
- **회귀 위험/감지**: 증명되지 않은 입력 domain으로 축소하거나 두 rectangle의 겹침을 중복 계산하면 후보 손실을 놓칠 수 있다. 반례 단위 테스트와 기존 full-closure oracle parity로 감지한다. Family-only 변경은 재생성 후 downstream worklist에 전파돼야 한다.
- **근거 경로**: `/grid/3/cofee-lm-sweep-mchoi-20260914/placement-bottleneck-drive-20261008/evidence/`.

### 중간 검증과 fixture 정정

- 재개 직후 원본 재현 테스트는 **1 test / error 1**로 같은 monotonicity 오류를 다시 냈다 (`v53-resume-closure-red.log`).
- Symbolic helper 12개와 기존 domain refinement 6개는 **18 PASS**. 200개 fixed-seed 소형 집합 oracle, 40축의 BigInteger product, owner/header 반례, 겹침/누락, singleton·두 입력 순차 축소, 재조정 과정의 old key 부활 금지를 검증했다.
- Closure 수정 후 기존 weighted 비교가 assertion을 냈다. 실제 차이는 공통 fact의 내용이 아니라 **NROW exact row 2개 vs 동일 family 표현**이었다. 양쪽의 전체 논리 inventory를 펼쳐 비교하도록 oracle를 바로잡았으며 weighted 11개가 모두 통과했다. 이를 production 의미 변경으로 해결하지 않았다.
- 새 PRIVATE_AGGREGATE `rmempty -> nrow/ncol` fixture는 privacy상 singleton exact domain이므로 family를 기대한 최초 가정이 틀렸다. 이 fixture는 양 모드의 singleton·전체 header·metadata-only privacy·legal state parity로 정정했다. 오류 로그 `v53-closure-complete-focused.log`를 보존했다.
- Family-only 변경의 commit/requeue 및 retained-derived shortcut 방지는 독립 source review **CLEAR**. 다만 scalar CP family는 placement-data descendant worklist의 일반 producer가 아니므로 runtime worklist observer 검증을 완료했다고 주장하지 않는다. 기존 final inventory overlap validation도 유지한다.
- `origin/main`의 `33384c0054`를 fetch했고 incoming ordinary-root reuse/missing-pin 변경을 독립 검토했다. 이후 통합 검증 후 병합·재검증한다. Incoming 이후 support memo eviction counter는 completed-support와 shared-root eviction을 함께 세므로 해석을 구분해야 한다.

### V53 로컬 통합 gate 통과

- 201개 actual class, **1,573 tests / failures 0 / errors 0 / skipped 2**, Maven package BUILD SUCCESS (2026-10-13 06:21:57+02).
- PA dynamic dimension control 2개도 포함하여 통과했다. 기존 오류와 representation-biased oracle를 각각 구분해 고쳤다.
- 아직 Docker 성능을 측정하지 않았으므로 이 결과는 정답/계약 회귀 검증이지 20초 목표 달성 근거가 아니다.
- 원격 변경 병합 후 같은 gate에 incoming 회귀 테스트를 더해 재검증한다.

### V53 병합 gate 통과

- 구현 `58b6342d99`, incoming `33384c0054` 병합 `8cff64591b`. 문서 append 충돌은 양쪽 보존, NPC 독립 함수 변경은 자동 병합.
- Fresh **204 actual classes / 1,585 tests / failures 0 / errors 0 / skipped 2**, package BUILD SUCCESS (06:25:42+02). Incoming missing-pin/ordinary-root 회귀도 포함한다.
- 근거 `evidence/candidate-v53-package.log`; 독립 계약 검토와 source hash `evidence/v53-family-contract-review.txt`.
- OMX state checkpoint는 session scope unusable 오류로 실패했다. 권한을 우회하지 않았고 외부 `PROGRESS.json`과 정상 동작하는 working notepad에 상태를 보존한다.

## Native gate/frontier + CP family closure 통합 gate

- **병합**: 로컬 `fc66b0f415`와 origin/main `3639b09a2d`. 양쪽 세션 기록을 보존했다. Conflict는 family-only 변경 감지·frontier 초기화, gate의 전체 retained unit 예산·관측 카운터를 함께 유지해 해결했다.
- **독립 검토**: CLEAR. Family 변경은 no-change shortcut을 막고 commit을 강제한다. 모든 commit은 frontier를 초기화한다. Gate 자체·선택지·의존성의 보관량과 summary가 같은 예산 및 eviction에 묶인다.
- **통합 검증**: 선택된 FedPlanner 회귀 **1,494 PASS / 183.505초**. 실행 중 class/resource 변경 0, source mismatch 0, 검사 대상 class/resource 7,909개. 전체 repository Maven suite와 구분한다. 테스트 resource `OPERATION_FAMILIES.tsv`도 병합본과 같은 SHA로 교체했다.
- **추가 반례**: 별도 검증했던 equal-value/different-identity gate key와 revision 미이월 테스트를 실제 소스에 통합했다.
- **증거**: workspace 밖 `evidence/native-gate-physical-frontier-merged-full-gate-v44/validation.json`. 실제 COFEE 성능은 이 gate 결과와 별도이며, frozen v44 실행 전에는 개선을 주장하지 않는다.

## v44 실제 LogReg 실패와 gate budget 반례 수정

- **실행 결과**: 커밋 `53f458fa69`, JAR SHA-256 `dfd46a34c037654b884af46dd0b42088b03890caa0e32089a12b6017c82d7ef3`의 실제 COFEE LogReg이 `cacheAcyclicRootChildren:3576`의 NullPointerException으로 종료했다. GLM은 실행하지 않았다. 전체 planning은 −1이며 정상 terminal counters가 없으므로 시간·생성량 비교에 사용하지 않는다. Docker cleanup은 완료됐다.
- **누락했던 경계**: footprint 탐색은 보관 한계를 넘는 항목을 null로 기록해 중복 탐색을 막는다. 기존 root-child 경로가 null을 제거한 이후 새 native-gate 경로가 null을 다시 추가했고, admission 단계가 이를 정상 footprint처럼 읽었다. 1,494개 회귀와 probe가 이 추가 경로의 예산 거절 상황을 검증하지 못했다.
- **수정**: gate 탐색이 끝난 뒤 null 거절 기록을 admission 대상에서 제거한다. 그래프·합법 후보·support·proof를 삭제하는 변경이 아니다. 한계를 넘은 gate는 보관하지 않고 기존 정확한 계산을 계속한다.
- **반례 검증**: native gate가 생기는 공개 질의에서 `maxStates=1`로 같은 NPE를 재현했다. 수정 전 157 tests / failure 1, 수정 후 157 PASS / 1.395초. 캐시 없는 resolver와 전체 proofs 및 identity 기반 dependency footprint가 일치하고 oversized gate가 보관되지 않음을 확인했다. 독립 source review CLEAR.
- **실패 증거**: `evidence/cofee-50k128-v44-validation/runtime-failure-binding.json`, SHA-256 `f72f4dedc90902cd44a65453ddd6a575dc1a8e2697a0613359e044a28cd8da35`. 반례 증거는 `evidence/v45-gate-budget-regression/`에 보존한다.
- **다음 검증**: 새 frozen v45의 통합 gate와 실제 실행. v43이 마지막 정상 완료된 비교 기준이며 v44를 성공이나 성능 개선으로 보고하지 않는다. 20초 목표는 계속 미달성이다.

## Gate admission의 기존 warm 경로 비교 — cap=1 회귀 발견

- **검증 보완**: 동일 generated-history fixture를 v43과 수정된 v45 복사 빌드에서 각각 두 번 실행했다. 새 코드 내부의 cold/warm 차이만으로 기존 구현 대비 효과를 판단하지 않았다.
- **결과**: 기본 cap에서 두 버전의 두 번째 질의는 graphs 1 / states 3 / alternatives 3 / edges 2 / raw footprint edges 0으로 같았다. `maxEntries=1`에서는 v43이 같은 1/3/3/2/0, v45가 1/8/10/8/4로 악화했다. Per-JVM lineage ID를 정규화한 proofs와 dependency-owner footprint는 일치했다.
- **원인**: 새 gate admission이 동일 예산의 기존 root-child summary를 축출했다. 더 작은 부분을 재사용하려다 이미 가능했던 더 큰 부분의 재사용을 잃었다.
- **수정 방향**: gate는 기존 summary를 축출하지 않고 남은 공간만 사용한다. 기존 ordinary summary의 eviction 계약은 유지한다. 값만 같은 서로 다른 authority를 합치거나 보관 한계를 늘리는 방식으로 해결하지 않는다.
- **증거**: `lanes/gate_baseline_probe/artifacts/old-v43-vs-v45-20261013/comparison.json`. 이 상태는 성능 회귀로 판정했으며 실제 v45 실행 전에 수정·재검증한다.

## DP의 검증 완료된 동일 선택 재계산 제거 — 독립 검증 통과

- **원인**: Conditional replay가 동일 원본 decision 선택을 복원한 뒤에도 전체 support 전파와 보조 변수 복원을 다시 실행했다.
- **정확성 근거**: cache entry는 canonical 비개선 증명 또는 성공한 lift·accept 뒤에만 만들어진다. 원본 decision이 같고 root·cost가 불변이면 canonical objective도 같다. `upper`는 감소하기만 하므로 이미 확인한 선택은 이후 엄격한 개선이 될 수 없다. 동률에서 기존 auxiliary assignment를 유지하는 계약도 동일하다.
- **변경**: 검증된 replay에서 lift와 accept를 생략한다. 다른 block·외부 decision, cache miss, 자원 실패 등은 기존 경로를 따른다. 추가 assignment 배열이나 테스트 전용 production counter를 저장하지 않는다.
- **실제 작업 검증**: 기존 `Prepared.lift`에 spy를 연결한 동일 fixture에서 변경 전 실제 호출 2회, 변경 후 1회. 전체 assignment, 별도로 복원한 assignment, objective raw bits, conditionalAttempts, 4개 시간 필드를 제외한 전체 checkpoint가 동일하다. 관련 48 tests PASS, 독립 proof review CLEAR.
- **한계**: 실제 COFEE에서 이 replay가 얼마나 발생하는지는 아직 확인하지 않았다. 이 단위 테스트로 전체 optimizer 시간을 추정하지 않는다.
- **증거**: `lanes/regional_replay_lift/artifacts/regional-replay-lift-final2-20261013/`. Production patch SHA-256 `ae94dbdc037924f1070093d82277a2c2e8613c5b8781b15665c2777065ceccde`.

### Gate admission 회귀 수정 및 이전 버전 직접 비교

- **상태**: 수정·독립 검증 통과. Gate admission은 기존 entry를 제거하기 전에 공유 entries/states/alternatives의 잔여 예산을 O(1)로 확인한다. 자기 entry 갱신이면 그 사용량만 차감한다. Gate는 기존 summary를 축출하지 않으며 일반 summary의 LRU 정책은 유지한다.
- **반례**: 수정 전 production을 복원한 RED가 gate의 ordinary summary 축출로 실패했다. 수정 후 cap=1의 두 번째 질의는 v43과 동일한 graphs/states/alternatives/edges/rawFootprint = 1/3/3/2/0이다. 기본 cap에서도 두 버전이 같다.
- **효과가 있는 경계**: 같은 fixture에서 maxEntries=1, maxStates=5로 전체 child는 크고 일부 gate는 들어가는 경우, v43의 1/10/12/12/2가 수정본 1/8/10/8/2로 줄었다. Proof와 owner footprint 의미는 같다. 실제 workload 효과를 이 수치로 추정하지 않는다.
- **검증**: NativePlacementContinuityTest 157 PASS / 기존 skip 1. 독립 검토 CLEAR. 비교 증거 `lanes/gate_baseline_probe/artifacts/opportunistic-gate-old-v43-comparison-20261013/comparison.json`, SHA-256 prefix `8b6de613`.
- **수정 파일**: NativePlacementContinuity.java, NativePlacementContinuityTest.java.
- **잔여 위험**: 기존 캐시를 밀어내지 않는 것은 증명했지만 gate 발견·보관 자체의 비용이 이득보다 클 수 있다. 전체 통합 gate 후 실제 COFEE LogReg·GLM을 v43과 비교한다.

## v45 통합 회귀 gate

- **검증 범위**: gate budget null 경계 수정, opportunistic gate admission, 검증 완료 DP replay의 중복 lift 제거를 CP family closure·physical frontier 변경과 함께 검증했다.
- **결과**: 선택된 FedPlanner 회귀 **1,500 PASS / 215.979초**. 검사 중 class/resource 변경 0, source mismatch 0, class/resource 7,909개. 전체 repository Maven suite는 아니다.
- **증거**: `evidence/gate-budget-replay-full-gate-v45/validation.json`. 이전 v44 실제 NPE 실패 증거는 보존했다. 이 결과는 실제 compilation 시간이나 20초 목표 달성의 증거가 아니다.
- **별도 probe gate**: 9 PASS / 2.942초 (`evidence/v45-probe-gate/tests.log`). Frozen engine 생성 및 실제 Docker 검증으로 이어간다.
