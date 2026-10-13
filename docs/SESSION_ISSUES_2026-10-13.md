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

## V54: synthetic native gate가 숨긴 실제 재사용 경계 (진행 중)

- **문제/원인**: native input product는 axis gate로 압축하지만 root-child footprint 수집은 gate를 제외했다. 그 뒤의 실제 하위 proof를 여러 native query가 공유해도 component summary admission 대상에서 빠졌다.
- **선행 증거**: 같은 lazy relation을 공유하는 두 native root의 공개 API 테스트와 cyclic SCC/negative/hidden-owner 테스트를 먼저 추가했다. 기존 소스는 25 tests 중 2개에서 예상 RED (admission 0, 빈 경계)를 냈다. Fixture setup 오류는 없었다.
- **해결**: synthetic gate만 통과하고 첫 실제 child에서 멈추는 encounter-order 보존 frontier를 추가했다. 실제 child에는 기존 footprint·SCC·root-dependence·negative·예산·revision 검사를 그대로 적용한다. Gate 자체를 캐시하거나 product를 펼치지 않는다. Gate 방문 집합과 stack은 gate를 만날 때만 만든다.
- **수정 파일**: `NativePlacementContinuity.java`, `SearchSpaceMetrics.java`, `NativeHybridUnpinnedTopologyTest.java`, `NativeAxisGateComponentReuseTest.java`.
- **검증**: fresh overlay focused 43 PASS (0.708초); eager/cold/metrics ON proof 순서·owner identity·FULL seed·footprint parity와 두 번째 query의 graph state/edge 감소를 검증했다. Independent actual-diff review CLEAR. 전체 gate 및 동일 Docker 실측은 별도다.
- **계측 의미**: `COMPONENT_ROOT_GATES_TRAVERSED`는 한 frontier 내 unique gate 방문, `COMPONENT_ROOT_GATE_BOUNDARY_DEPENDENCIES`는 뒤에서 발견한 dependency encounter 수다. 중복 및 후속 거부가 포함될 수 있어 admission이나 시간 절감량이 아니다.
- **잔여/회귀 위험**: 발견 경계가 늘면 footprint 비용과 기존 bounded cache churn이 늘 수 있다. 예산을 늘리지 않고 실제 workload에서 확인한다. Hidden owner revision·negative·SCC 및 기존 native parity 테스트로 안전성을 감지한다.
- **의사결정 근거**: oracle/runtime/privacy가 아니라 압축 그래프의 재사용 경계 발견 누락을 수정했다.

### V53 동일 Docker 결과와 다음 경로 선택

- OFF/detailed × LogReg/GLM 모두 watchdog60, fullInitial 완료 기록 없음, 네 container 제거. 20초 목표는 여전히 FAIL.
- OFF LogReg 2,146 samples/direct binding 63.14%/peak 4.870GB, GLM 2,673/canonical 7.56%/joint 8.08%/peak 3.329GB. 불완전한 실행이므로 완료 시간 개선을 주장하지 않는다.
- Summary lookup은 LogReg 3,657,068 중 absent 3,509,381 / reuse 144,014 / fixed-owner reject 3,673. GLM 343,028 중 absent 322,601 / reuse 20,319 / reject 108. Terminal 합계 일치. State/row budget bypass는 0이며, LogReg eviction 20,089건은 별도로 관측했다. Absent가 곧 재사용 가능한 반복이라는 뜻은 아니다.
- Canonical GLM size>=2 요청 746,684 중 tracked repeat 570, overflow 643,336. 이 bounded identity 관측으로 전체 반복률을 단정할 수 없으며 새 sequence cache의 실제 이득을 입증하지 못했다. 우선 gate 뒤 실제 재사용 경계 누락의 재현 증거가 있는 V54를 진행한다.
- 외부 build 중첩은 OFF 21/25 samples, detailed 2/25; 외부 planner 두 개는 전 구간 존재했다. 자체 build/JFR parsing은 timed Docker와 겹치지 않았다.
- 근거: `evidence/v53-{monitor-driver.log,contention-summary.json,planner-breakdown.txt}`, `evidence/candidate-v53-detailed-topology-outcomes.json`, `candidate-v53-{off-jfr,detailed}/summary.json`.

### V54 통합 gate 및 봉인

- **205 actual classes / 1,587 tests / failures 0 / errors 0 / skipped 2**, Maven package BUILD SUCCESS (2026-10-13 06:38:35+02). 전체 저장소 테스트가 아니라 명시된 FedPlanner 회귀 gate다.
- 공개 API 회귀의 두 번째 query는 proof states **10→9**, dependency edges **12→7**, component reuse 5건이었다. 이는 작은 fixture의 작업량 검증이며 LogReg/GLM 속도 개선을 뜻하지 않는다.
- 봉인 JAR SHA-256: `5e2af1624eb56094aaf180396a62958d2944991b4f6134b40eca1268c9e47bf1`; 근거 `evidence/candidate-v54-package.log`, `candidate-v54/seal.json`, `evidence/v54-gate-frontier-review.txt`.
- 후속 source review: 실제 direct binding의 generated root는 native gate보다 먼저 ordinary dependency로 분기한다. V54는 root gate 경계만 복구하므로, 더 깊은 gate 뒤 반복 proof admission 문제까지 해결했다고 주장하지 않는다. 그 경계를 늘릴 때에는 기존 footprint walker의 중복 transitive traversal/owner 복사 비용도 총량 제한해야 한다.
## Native gate/frontier + CP family closure 통합 gate

- **병합**: 로컬 `fc66b0f415`와 origin/main `3639b09a2d`. 양쪽 세션 기록을 보존했다. Conflict는 family-only 변경 감지·frontier 초기화, gate의 전체 retained unit 예산·관측 카운터를 함께 유지해 해결했다.
- **독립 검토**: CLEAR. Family 변경은 no-change shortcut을 막고 commit을 강제한다. 모든 commit은 frontier를 초기화한다. Gate 자체·선택지·의존성의 보관량과 summary가 같은 예산 및 eviction에 묶인다.
- **통합 검증**: 선택된 FedPlanner 회귀 **1,494 PASS / 183.505초**. 실행 중 class/resource 변경 0, source mismatch 0, 검사 대상 class/resource 7,909개. 전체 repository Maven suite와 구분한다. 테스트 resource `OPERATION_FAMILIES.tsv`도 병합본과 같은 SHA로 교체했다.
- **추가 반례**: 별도 검증했던 equal-value/different-identity gate key와 revision 미이월 테스트를 실제 소스에 통합했다.
- **증거**: workspace 밖 `evidence/native-gate-physical-frontier-merged-full-gate-v44/validation.json`. 실제 COFEE 성능은 이 gate 결과와 별도이며, frozen v44 실행 전에는 개선을 주장하지 않는다.

### V54 동일 Docker 실측

- OFF/detailed × LogReg/GLM 네 실행 모두 watchdog60, fullInitial 완료 없음, container 제거 완료. **20초 목표 FAIL**.
- OFF LogReg 2,262 samples/direct binding 61.49%/peak 5.068GB; GLM 2,553/canonical 8.15%/joint 6.38%/peak 3.223GB. 불완전한 서로 다른 진도이므로 완료 속도 개선을 주장하지 않는다.
- 두 workload 모두 `COMPONENT_ROOT_GATES_TRAVERSED=0`, `COMPONENT_ROOT_GATE_BOUNDARY_DEPENDENCIES=0`. V54의 정확성/fixture 작업량 개선은 입증했지만 이번 workload 병목에 대한 적용은 관측되지 않았다.
- 근거: `evidence/v54-{analysis-driver.log,topology-outcomes.log,planner-breakdown.txt,contention-summary.json}` 및 봉인별 `summary.json`.

## V55: optional native gate memo의 실패 격리와 총 작업량 제한 (진행 중)

- **증상/원인**: incoming `fc66b0f415`의 깊은 native gate cache가 root footprint null 정리 이후 gate footprint의 null 결과를 추가했다. 기존 예산 초과가 memo 생략이 아니라 planner NPE가 됐다.
- **재현**: PUBLIC constraint fixture를 새로 만들지 않고 기존 native fixture에서 `continuityAcyclicComponent.maxStates=1` 사용. Generated query는 `footprint.occurrences`, ordinary query는 `footprint.retainedStates`에서 각각 실패한다. Corrected RED **183 tests / failures 2** (`v55-gate-budget-red.log`). 최초 빈 explicit-lineage fixture 오류는 별도 로그로 보존하고 수정했다.
- **구조적 문제**: gate마다 전체 후손 footprint를 다시 만들면 alternating chain에서 합계가 제곱으로 증가한다. 최종 LRU 크기만 제한해서는 중간 순회/객체 생성 비용을 제한하지 못한다.
- **해결 계획**: 기존 gate scan·footprint·후속 summary 생성 작업을 기존 state/row 예산에서 도출한 하나의 optional allowance로 제한한다. 완전한 footprint만 admission하며 예산 소진은 캐시 생략일 뿐 proof/candidate 축소가 아니다. Root frontier 및 privacy/runtime/TR-TW 계약 유지.
- **수정 파일**: `NativePlacementContinuity.java`, `SearchSpaceMetrics.java`, native gate 회귀 테스트. Incoming merge의 constructor 변경에 맞춰 reflection fixture를 조정한다.
- **검증/위험**: 일반/generated query parity, 깊은 chain·duplicate edge·hidden owner·disabled budget, 전체 gate 및 Docker 실측 예정. 새 reuse는 churn과 추가 작업을 일으킬 수 있어 런타임 이득은 별도 판단한다.
- **의사결정 근거**: 후보를 제거하지 않고 optional memo의 실패 경계와 분석 알고리즘의 작업량을 수정한다.

### V55 중간 검증

- Native/physical incoming 통합 focused **199 PASS** (1 skipped 포함). 두 public budget NPE가 모두 해소됐다. 추가 aggregate adversary는 강화 후 재검증한다.
- Aggregate baseline은 새 계측 enum만 제공하고 이전 NPC 구현을 사용해 재실행했다. **3 tests / failures 2**: 각 suffix는 예산에 들어가도 합계 retained states 초과, wide gate 19개가 entry budget 3을 초과. 계측 부재와 달리 실제 작업/보존 한계의 RED다 (`v55-gate-aggregate-behavior-red.log`).
- 독립 source review는 proof 의미/예산 처리의 concrete blocker 없음. `WORK`는 선지불한 논리 credit이므로 element access의 정확한 수나 초 단위가 아니다. 보장 범위는 추가 optional admission의 O(W) 작업량과 staged logical S/A/E이며, scratch RSS가 S 이하라는 뜻도 아니다. Gate `retainedUnits` constructor의 1회 선형 계산은 이 credit 밖이다.
- 검사 중 두 test fixture 컴파일/생성 오류도 수정했으며 별도 실패 로그를 보존했다. Production 우회로 통과시키지 않았다.

## V55: MATRIX→SCALAR의 완전한 dependency authority (진행 중)

- **문제/원인**: `CAST_AS_SCALAR`는 기존 CP/LOUT/NO_RULE 결과가 FType/shape에 무관한데 이를 명시하는 rule/family dependency authority가 없어 residual 후보 평가에 남았다.
- **해결**: MATRIX 입력 1개 / scalar 출력 범위에서 기존 capability와 빈 profile을 그대로 유지하는 rule를 등록했다. Decision/family determinant가 모두 빈 집합임을 선언하고 기존 PUBLIC+unprotected gate 안에서만 CP family를 허용한다. Scalar/frame 입력 및 non-scalar 출력은 family로 확대하지 않는다.
- **수정 파일**: `Rulesets.java`, `RulesCore.java`, `PlacementCandidateGenerator.java`, `CastAsScalarRuleTest.java`, `CastAsScalarCpFamilyParityTest.java`, ML operation manifest.
- **검증**: focused **21 PASS**, 직접 PUBLIC fixture 2개 ignored 포함. PA-derived PUBLIC의 실제 DML `sum -> matrix -> as.scalar`, FType/null/shape/full·reversed domain/singleton oracle, private/masked 및 입력 종류 negative, metrics ON/OFF를 비교했다. Independent review CLEAR.
- **적용 범위의 한계**: ML10 manifest의 castdts **77회(constructed 18 / rewritten 59)**는 문법적 연산 출현이며 실제 압축 publication 77건이 아니다. 이 실제 PA fixture는 singleton exact이고 family 0건이다. LogReg13/GLM29 출현만으로 workload 이득을 주장하지 않는다.
- **잔여 이슈/위험**: 전체 ML 87개 family coverage는 아직 미완료다. Binary/matrix 연산의 FType·shape·alignment·binding dependency를 생략한 일괄 allowlist는 적용하지 않았다. 최종 전체 gate와 Docker 실측은 별도다.
- **의사결정 근거**: 실행 가능 후보/개인정보 규칙은 바꾸지 않고 기존 불변 capability에 압축 표현의 완전한 의존성 증명을 부여했다.

### V55 첫 통합 gate 통과

- 209 actual classes, **1607/0/0/3 (tests/failures/errors/skipped)**, package BUILD SUCCESS (07:06:50+02). 강화 native/physical focused 202 PASS.
- Deep duplicate enqueue, 실제 hidden/reused owner 복사 경계의 예산 소진도 검증했다. 독립 coverage review CLEAR; reused-summary 과대 owner fixture는 defensive 경계 시험임을 구분한다.
- 검증 중 origin/main에 별도 null-footprint correction `5bd1c76369`가 게시됐다. 우리 bounded admission이 같은 원인을 해결하지만 incoming 회귀도 보존해 병합 후 다시 검증한다.
## v44 실제 LogReg 실패와 gate budget 반례 수정

- **실행 결과**: 커밋 `53f458fa69`, JAR SHA-256 `dfd46a34c037654b884af46dd0b42088b03890caa0e32089a12b6017c82d7ef3`의 실제 COFEE LogReg이 `cacheAcyclicRootChildren:3576`의 NullPointerException으로 종료했다. GLM은 실행하지 않았다. 전체 planning은 −1이며 정상 terminal counters가 없으므로 시간·생성량 비교에 사용하지 않는다. Docker cleanup은 완료됐다.
- **누락했던 경계**: footprint 탐색은 보관 한계를 넘는 항목을 null로 기록해 중복 탐색을 막는다. 기존 root-child 경로가 null을 제거한 이후 새 native-gate 경로가 null을 다시 추가했고, admission 단계가 이를 정상 footprint처럼 읽었다. 1,494개 회귀와 probe가 이 추가 경로의 예산 거절 상황을 검증하지 못했다.
- **수정**: gate 탐색이 끝난 뒤 null 거절 기록을 admission 대상에서 제거한다. 그래프·합법 후보·support·proof를 삭제하는 변경이 아니다. 한계를 넘은 gate는 보관하지 않고 기존 정확한 계산을 계속한다.
- **반례 검증**: native gate가 생기는 공개 질의에서 `maxStates=1`로 같은 NPE를 재현했다. 수정 전 157 tests / failure 1, 수정 후 157 PASS / 1.395초. 캐시 없는 resolver와 전체 proofs 및 identity 기반 dependency footprint가 일치하고 oversized gate가 보관되지 않음을 확인했다. 독립 source review CLEAR.
- **실패 증거**: `evidence/cofee-50k128-v44-validation/runtime-failure-binding.json`, SHA-256 `f72f4dedc90902cd44a65453ddd6a575dc1a8e2697a0613359e044a28cd8da35`. 반례 증거는 `evidence/v45-gate-budget-regression/`에 보존한다.
- **다음 검증**: 새 frozen v45의 통합 gate와 실제 실행. v43이 마지막 정상 완료된 비교 기준이며 v44를 성공이나 성능 개선으로 보고하지 않는다. 20초 목표는 계속 미달성이다.

### V55 최신 main 병합 검증

- `5bd1c76369`의 null-footprint correction은 bounded collector가 이미 포함하는 의미이므로 production 함수는 그대로 보존하고 incoming 회귀와 문서는 추가했다.
- Fresh **209 actual classes / 1,608 tests / failures 0 / errors 0 / skipped 3**, package BUILD SUCCESS (07:10:29+02). 근거 `evidence/candidate-v55-package.log`. 전체 저장소 gate가 아니라 지정 FedPlanner 회귀 gate다.
- V55 실측은 이후 동일 자원 Docker로 수행한다. 이 테스트 통과를 20초 목표 달성이나 모든 ML 연산 압축 완료로 간주하지 않는다.
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

## 다음 단계: 압축 관계의 retained union 실패로 인한 재전개

- **문제 정의/관측**: v43 LogReg 최종 seq60의 PROOFS_CONSUMED 6,121,451은 PARTITIONED 3,425,335, MIXED_EXACTNESS_SINGLE_AXIS 1,649,618, RETAINED_UNION 949,972, REQUIRED_INPUTS 92,486, SINGLETON_OUTPUT 3,917, NO_PRODUCT 123으로 정확히 분해된다.
- **가장 큰 확인 경로**: PARTITIONED_RETAINED_PROOFS 2,555,577(전체 소비의 약 41.75%)은 PlacementRelationClosure의 partitioned publication에서 retained union이 실패한 뒤 product를 개별 tuple로 순회하는 루프에 기록된다. 이 루프는 residual 생성에 성공하면 conditional residual을, 실패하면 원래 product를 순회한다. 현재 카운터는 두 경우를 나누지 않는다. 이미 만들어진 정확한 관계를 다시 순회한다는 점이 핵심이다.
- **생성량 구분**: 이는 2,555,577개의 새 Clause 객체가 아니다. 같은 실행의 explicitSupportClauses는 2,322,698, publication memo hit는 3,926,538이며 outcome별 객체 생성 수가 없어 서로 동일시할 수 없다.
- **수정 계획**: ordinary E와 conditional B\E가 동일 owner/header/witness/layout 및 binding identity를 공유하고 제외 영역이 정확히 E인 경우에만 B로 재결합한다. 기존 E와 새 residual의 개별 clause 권한은 donor scope로 유지한다. Identity 불일치, 부분 overlap/extra, multiheader, cap 초과 등은 exact fallback을 유지한다.
- **검증 계획**: 실제 materialization 호출 RED/GREEN, 전수 소형 관계·고정 seed parity, retained/member Clause assertSame, withdrawal/re-add, canonical hash/order 및 Physical receipt parity. 실제 workload에서 이 제한된 경우의 적용 비중과 성능 효과는 아직 측정하지 않았다.
- **원칙**: runtime/privacy/Oracle 규칙과 합법 후보를 변경하지 않고 표현의 재전개만 제거한다. source/action/proof 대표값을 서로 대체하지 않는다.

- **적용 범위 보정**: `exactAuthorityIntersectionAxes`는 ordinary/ordinary만 허용한다. 따라서 실제 residual 생성은 retained rectangle E와 candidate rectangle B의 다축 성장 등에서 가능하다. 원래 candidate가 conditional이면 residual 생성은 실패할 수 있다. 또한 전체 emission의 staging guard와 exact-key residual guard가 다르다. 2,555,577건 전체가 새 재결합의 적용 대상이라고 주장하지 않으며 실제 경로 테스트·후속 workload로 범위를 확인한다.

## 다음 단계: 동일 incumbent의 반복 projection 제거

- **상태**: 독립 worktree에서 50 tests PASS, ROOT에 후속 wave로 통합 중. 실행 중인 frozen v45에는 포함되지 않는다.
- **원인**: neighborhood마다 같은 incumbent의 전체 assignment를 펼친 뒤 원래 decision prefix만 추출했다.
- **변경**: refinement 호출 안에서 incumbent 배열 identity와 한 개 decision projection만 유지한다. neighborhood는 복사본을 수정하며, 엄격한 개선으로 incumbent가 교체되면 즉시 다시 펼친다. 호출 간 cache는 없다.
- **근거/검증**: incumbent는 in-place 변경되지 않고 accept에서 엄격한 개선 시 교체된다. 기존 버전은 안정된 두 neighborhood에서 실제 expandAssignment 호출 2회, 변경 후 1회. 첫 neighborhood에서 개선이 생기는 반례는 projection 2회와 accept 검증 2회를 유지하며 최종 assignment [1,1], objective raw bits 및 checkpoint 요약이 같다.
- **잔여 위험**: 향후 incumbent를 in-place 변경하면 identity 계약이 깨질 수 있다. 명시적 교체 반례와 독립 검토·통합 회귀로 감지한다. 실제 workload 효과는 아직 미측정이다.

## v45 실제 LogReg 성능 회귀 — 전역 gate 발견 제거 진행 중

- **관측 결과**: 동일 COFEE 조건 LogReg 전체 초기 planning **438.335640203초**, v43 388.771218709초 대비 +49.564421494초. CandidateE2E Analysis 343.940421141초, Model 6.904664545초, Cost Surface 6.849345538초, Optimizer 76.903665119초. 별도 SEARCH_SPACE_LIVE Analysis timer는 343.890281686초이며 전체 단계 timer와 혼동하지 않는다. 한 번의 실행이며 반복 paired 측정의 인과 추정은 아니다.
- **정확성**: numeric/audit/selected/objective 비교는 v43과 일치, cleanup 완료. v44의 NPE는 재발하지 않았다.
- **생성량/작업량**: keys 27,442→25,137, facts 217,777→208,886, explicit clauses 2,322,698→2,312,593. PROOFS_CONSUMED 6,121,451과 graphs 103,616은 동일하다. Objects 일부 감소를 전체 작업 감소로 보고하지 않는다.
- **캐시 추가 작업**: COMPONENT_FOOTPRINT_REQUESTS 159,880→1,900,563, RAW_EDGES 92,488,567→1,462,463,009, UNIQUE_EDGES 29,950,314→554,776,174. Summary rows examined 912,863→8,362,556, admissions 608,756→6,940,484, evictions 0→353,271. Graph states 29,771,479→31,849,922, alternatives 191,033,548→203,722,052, dependency edges 276,607,446→285,878,572도 증가했다.
- **코드상 원인**: 전체 graph의 axis gate마다 전이 footprint를 다시 계산했다. 상위 child summary가 재사용 가능하더라도 이 작업을 수행한다. Gate가 남은 cache 공간만 써도, 이후 ordinary admission의 LRU에서 기존 ordinary summary가 간접 축출될 수 있다. 추가 방문량 증가는 코드와 부합하지만 전체 49.56초를 단일 함수의 측정값으로 주장하지 않는다.
- **수정 결정**: `rememberNativeGateFootprints`의 전역 발견 호출과 사용되지 않는 helper를 제거한다. 기존 root-child footprint 경로는 유지한다. 하위 gate의 추가 발견은 bounded incremental 설계가 검증되기 전까지 적용하지 않는다. 보관 한계 상향이나 새로운 후보 pruning으로 문제를 감추지 않는다.
- **검증 계획/위험**: Native continuity 전체 회귀 및 기존 v43과 같은 fixture의 warmed proof/owner/counter parity. 일부 nested-gate 재사용 기회를 잃을 수 있으므로 실제 전체 시간과 메모리를 재확인한다. GLM 및 최종 artifact binding 결과는 이어서 기록한다.

### v45 실제 pair 최종 결과

| Workload | v43 전체 planning (초) | v45 전체 planning (초) | v45 Analysis / Model / Cost / Optimizer (초) | coordinator peak (bytes) | worker peak (bytes) |
|---|---:|---:|---|---:|---:|
| LogReg | 388.771218709 | 438.335640203 | 343.940421141 / 6.904664545 / 6.849345538 / 76.903665119 | 8,789,852,160 | 726,630,400 |
| GLM | 121.376056740 | 117.548185148 | 74.764857679 / 4.433866240 / 8.167961908 / 25.553646316 | 4,883,849,216 | 753,352,704 |

- 두 workload numeric, audit, 원문 selected/section, selection fingerprint, objective raw bits/certificate, Cost Surface fingerprint가 v43과 일치한다. 모든 가능한 assignment의 비용 전수 동등성을 주장하지 않는다. 각 1회 관측이며 LogReg 회귀를 GLM 결과로 상쇄해 성공으로 판정하지 않는다.
- GLM keys 16,157 / facts 125,264 / clauses 1,088,687 / handles 634. 두 원격 호스트 cleanup 완료.
- Comparison SHA-256 `235eb25c1a08bb4eb4ce8e3cb0aeda90e503af6da83add02247e179c439cdeb0`; regression/parity binding `05927e1a3b0b17ead1fe26666e088e2c102e695e0eb5f125b133ec394e0833e0`; evaluator `459496d4631cdbdcf65e71a3dfd0d94a83bd9a1a5a7452de5caa33e8dcbe98d9`. 경로는 `evidence/cofee-50k128-v45-validation/` 및 `evidence/candidate-engine-gate-budget-replay-v45-20261013/`이다.
- 20초 evaluator **FAIL**, workload별 accepted 0/3. OMX에 FAIL checkpoint를 기록하고 evaluator evidence-list를 v45로 갱신했다(이전 목록은 별도 보존).

## Retained subset/complement 재결합 구현 및 독립 검증

- **변경**: ordinary E와 candidate B의 exact residual B\E를 donor scope로 만들고, 제외 영역이 E와 같은 authority일 때 B를 복원한다. 최종 [E,B] 병합은 첫 donor가 정확히 기존 E이며 E⊆B임을 축별 교집합과 cardinality로 확인할 때만 흡수한다. Existing E의 clause와 새 영역의 candidate clause는 각각 원래 객체에서 복원한다.
- **Closure**: 전체 emission hasStaging guard는 유지한다. 이미 존재하던 exact-key residual 경로에서만 재결합 publication을 사용한다. Same-key staging/explicit, foreign owner, 부분 overlap/extra, multiheader, 보관 cap 실패는 기존 scalar fallback이다.
- **실제 생성 경로 검증**: partitioned 및 standalone binder RED에서 각 residual 소비 3회였다. GREEN에서 각각 **3→0**, 논리적 후보 수 6은 유지. Same-key staging/explicit 반례는 기존 scalar4 경로를 유지한다.
- **검증**: 관련 65 PASS, independent review CLEAR. Hash/order, source withdrawal, authority cap 및 Conditional/Durable/Weighted Physical parity 포함. Projection 변경도 독립 검토 CLEAR, 50 tests PASS.
- **증거**: `lanes/native_retained_complement_union/artifacts/native-retained-complement-union-20261013/`, production SHA-256 `d8de79db5930c36f9ec4a76823058b6aa741a97ecb53f850c48e8b29ec33636e`. 실제 workload의 2,555,577회 중 이 subset 적용률은 아직 미측정이다.

## 새 origin/main 통합으로 gate 회귀 수정 방안 갱신

- 원격 `7a44c1874b`에 root의 synthetic gate를 넘어 실제 child component를 먼저 재사용하는 수정, nested gate 발견 작업 전체의 budget, matrix CAST_AS_SCALAR CP family가 추가됐다. 로컬 fallback 검증 중 도착한 별도 변경이다.
- 5660 기반 전역 nested 발견 제거는 단위 검증을 통과했고 별도 artifact로 보존했다. 그러나 새 원격 구현은 발견 알고리즘 자체가 달라졌으므로 같은 rollback을 그대로 적용하지 않는다. Real child 우선 admission과 제한된 추가 탐색을 통합한 뒤 실제 counter를 재검증한다.
- Budget는 query별 graph scan/row/dependency/hidden owner/schedule/copy 작업 합계를 기존 state+alternative cap으로 제한한다. 이는 무제한 O(gates×graph) 반복을 막지만, 전체 query 수를 곱한 시간이 충분히 작다는 보장은 아니다. v46 실제에서 footprint requests/raw edges/admission work와 전체시간을 확인한다. 악화가 남으면 전역발견 제거를 fallback으로 사용한다.
- 원격 변경을 통합하면서 기존 source/action/proof·CP/FED·privacy·staging 규칙과 테스트를 보존하고, 통합 full gate와 동일 실제 COFEE를 다시 수행한다.

### 발행 직전 추가 main 변경 병합

- `5660e734a4`는 gate가 기존 ordinary summary를 축출하지 않도록 하고, 검증 완료된 동일 DP replay의 lift를 생략한다. 두 경로를 독립 source review한 뒤 통합했다. 신규 `IncrementalConditionalReplayTest`를 selected gate에 추가한다.
- 이전 209-class gate는 이 후속 production 변경을 검증한 결과가 아니므로 별도 재검증 로그로 구분한다. 아직 V55 Docker는 시작하지 않았다.

### V55 gate/replay 통합 최종 gate

- **210 actual classes / 1613 tests / failures 0 / errors 0 / skipped 3**, package BUILD SUCCESS (07:16:00+02). Incoming non-evicting gate/replay production과 우리 bounded admission/CAST 경로를 함께 검증했다.
- 근거 `evidence/candidate-v55-package.log`; 병합 전 두 성공 로그도 별도 보존한다.

### Incoming gate 통합 정책 최종 결정

- 두 검토를 비교한 결과, query별 default allowance 163,840은 v45 평균 footprint raw work 약 14,114/query보다 크므로 실제 반복 비용을 없앤다는 근거가 되지 못한다. Work credit와 raw edge는 같은 단위가 아니며 정확한 초 단위 추정도 아니지만, 제한이 존재한다는 사실만으로 해당 회귀를 해결했다고 볼 수 없다.
- **최종 변경**: real-child frontier와 CAST CP family는 유지하고 production의 전역 `rememberNativeGateFootprints` 호출을 제거한다. 새로운 옵션이나 cap 조정은 없다. Incoming의 bounded helper 및 전용 regression test는 이번 통합에서 보존하지만 production에서는 호출되지 않는다. 이 dead helper/test는 후속 정리 대상으로 명시한다.
- 첫 통합 full gate는 이 정책 결정 전 소스로 시작됐으므로, 정확한 PID/부모/build 경로를 검증한 뒤 해당 JUnit만 중단하고 로그를 보존했다. 중단된 gate를 성공으로 보고하지 않는다. 최종 소스의 full gate를 새로 수행한다.
- Public native fixture에서 cold proof/source/owner parity, real child summary reuse의 상태·edge 감소, gate admission work 0을 확인한다. 기존 native/gate-budget 테스트를 삭제하거나 ignore하지 않는다.

## v46 최종 통합에서 발견된 scalar handle 재전개 회귀 (진행 중)

- **증상**: 선택된 FedPlanner 회귀 1,520건 중 2건 실패 / 184.526초. EarlyNativeCoverageOptimizationTest와 NativeMultiSeedPublicationTest의 실제 binder 검증에서 scalar handle 생성 기대 0, 관측 1이다. Main/test 컴파일은 성공했고 class/resource 변경 0, source mismatch 0이다.
- **원인/대응**: retained complement 재결합 통합 경로를 조사한다. 기존 0 기대를 1로 완화하지 않고 불필요한 scalar 복원 경로를 수정한 뒤 관련 반례와 전체 gate를 재실행한다. 실패 엔진은 Docker 검증·성공 발행에 사용하지 않는다.
- **근거**: `evidence/relation-union-projection-full-gate-v46-pre-fix-two-failures/validation.json` 및 `tests.log`에 원본 실패 증거를 복사 보존했다.
- **별도 검증**: global gate 탐색 호출 제거는 independent review CLEAR, author focused 190 PASS, 독립 copied-build 189 PASS/기존 ignore 1. Real child 재사용과 proof/source authority는 유지한다. 이 결과로 다른 두 실패를 대체하지 않는다.
- **잔여 위험/감지**: donor authority가 맞아도 publication 경로가 scalar handle을 만들 수 있으므로 실제 binder 객체 counter 및 권한 동일성 검증을 함께 유지한다. Oracle/runtime/합법 후보 정의는 변경하지 않는다.

### v46 scalar handle 재전개 원인 및 수정

- **원인**: `GroundedNativePreparation.containsExact`가 native support를 scalar prior clause map에서 찾으려고 순회했다. Preparation은 native retained product를 별도 인덱스에만 넣으므로 이 비교는 성공할 수 없지만, false를 반환하기 전에 lazy `get(0)`가 실행되어 handle 1개를 생성했다.
- **수정**: native support는 scalar map 비교 전에 false를 반환한다. 기존 conservative rebuild 경로와 합법성·authority는 동일하고, 결과가 없는 scalar 조회만 제거한다. `PlacementRelationClosure.java`의 private helper로 범위를 제한했다.
- **검증**: 동일 22 tests에서 RED 2 failures → GREEN 22 PASS. Complement/native/hash/rebind/materialization/multiheader 관련 109 PASS. `NativeMixedAxisSplitPublicationTest`도 최종 관계의 local handle 기대를 1에서 0으로 강화했다. 기존 초기 retained donor의 handle identity는 계속 검사한다.
- **증거**: `lanes/native_complement_handle_regression/artifacts/native-complement-handle-regression-20261013/`, production SHA-256 `c63e42ace157c5e55d71ef8358c93722a019233ac7fb6848a96ddfc0aed18ec4`. 최종 통합 gate 및 independent review 진행 중이다.
- **잔여 위험/감지**: 향후 scalar prior map에 native authority를 포함하도록 계약이 바뀌면 guard도 재검토해야 한다. Current map construction과 cold/warm authority·생성량 회귀를 함께 검증한다.

- **Independent review**: scalar handle guard CLEAR. Retained prefix는 이미 identity로 확인되고 native retained clauses는 scalar prior map에 들어가지 않는다. Suffix에 same-key explicit map이 있어도 early false는 이미 계산된 ordered realization을 그대로 재구성하는 경로이며 후보나 coverage 검사를 생략하지 않는다.

## v46 실제 검증 evidence builder의 실패 판정 강화

- **증상/원인**: 기존 실행 비교 helper는 parity 검사값과 무관하게 binding을 passed로 기록할 수 있었다. Extraction이 실패해도 원본 runtime status=passed를 복사하면 누락된 필드에서 실패 증거 기록 전에 예외가 날 수 있었다.
- **수정**: 두 workload의 runtime/numeric/audit, 모든 v43·v45 parity, 실제 frozen JAR/manifest SHA와 receipt의 엔진 binding이 모두 맞아야 성공이다. Extraction failure의 effective status는 failed로 유지하고 원본 상태는 runtimeStatus로 별도 보존한다. 실패 기록을 저장한 뒤 nonzero로 종료한다.
- **검증**: positive, false parity, failed workload, SHA mismatch, empty/missing workload, runtime passed+missing receipt의 synthetic end-to-end 반례 통과. Production optimizer와 workload 조건은 변경하지 않았다.
- **근거/잔여 범위**: `artifacts/build-v46-runtime-evidence.py` SHA-256 `887494e5a5ce23b353140a74da96564a6d4153620693fd6d1e413d58cc627854`, `evidence/cofee-50k128-v46-validation/builder-negative-tests/output.log`. 이 helper 검증은 실제 workload 성공이나 시간 개선의 근거가 아니다.

### v46 guard 범위를 empty scalar map으로 한정

- Conservative native guard는 선택된 회귀 **1,520 PASS / 199.511초**, class/resource 변경 0, source mismatch 0으로 통과했다. 근거는 `evidence/relation-union-projection-full-gate-v46-conservative-guard/`에 보존했다.
- 추가 검토에서 same-key explicit clause가 scalar map을 채운 경우 기존 exact-authority 비교로 emission identity를 재사용할 수 있음을 확인했다. 넓은 guard는 정확성을 해치지는 않지만 이 재사용을 놓친다.
- 최종 guard는 **scalar map이 비어 있고 support가 native인 경우**에만 조기 false를 반환한다. Map이 채워진 경우의 기존 비교·reuse를 유지한다. 앞선 “native 비교가 언제나 불가능” 설명은 이 empty-map 조건으로 한정한다.
- Production SHA-256 `f9cfb288c5d210f129eacf4fb49fc6b0294179a44991f69c43d7c8e5fcad944b`, 관련 109 PASS, independent review CLEAR. 소스가 바뀌었으므로 이전 1,520 PASS를 최종 엔진 검증으로 재사용하지 않고 다시 수행한다.

- **재사용 보존 반례 추가**: 동일 grounded explicit map과 동등한 native suffix의 reuse certificate를 검사했다. 넓은 guard RED 13 tests/1 failure, 최종 empty-map guard GREEN 13 PASS. `NativeSingleAxisProductUnionTest.sameKeyExplicitMapCanCertifyAnEquivalentNativeSuffix`, incremental patch SHA-256 `1b744835b4cd11c665b4c64b1e26c919c46cd9154f0bb7301b6a7493c439adeb`. Independent test review CLEAR.

## 다음 구조 개선: 같은 hole을 유지하는 conditional relation 성장 (진행 중)

- **코드상 단절**: `oneAxisUnion`의 conditional 경로는 같은 base axes를 요구한다. Base가 커지면 conditional union이 실패하고, ordinary만 지원하는 residual 교집합도 실패해 개별 proof 소비로 돌아갈 수 있다.
- **구현 범위**: retained `BL\E`, candidate `BR\E`에서 exact authority와 hole이 같고 `BL⊆BR`, `E⊆BL`인 경우만 처리한다. 최종 relation은 `BR\E`; 기존 donor는 `BL\E`, 새 donor는 `BR\BL`을 맡는다. Source/action/proof와 sparse hole을 그대로 유지하면서 기존 conditional index 표현을 사용한다.
- **검증 계획**: retained 2×2−1=3, candidate 3×3−1=8인 실제 binder 반례에서 추가 5개 tuple 순회를 0으로 줄이는지 확인한다. Logical8, selection 전 handles0, retained/new donor identity, changed-hole/foreign authority/staging/cap negative 및 physical/cost parity를 검사한다.
- **경계/잔여 한계**: 임의의 다른 hole·부분 overlap·동일 header의 여러 region까지 지원하려면 rank/hash/restrict/downstream API 확장이 필요하다. 현재 제한된 경우의 실제 workload 적용 비중은 미측정이다. 이 후속 구현은 검증 중인 frozen v46과 분리한다.

## v46 최종 회귀 gate 및 frozen 실측 준비

- **최종 소스 검증**: selected FedPlanner **1,521 PASS / 181.537초**, 별도 probe **9 PASS / 1.435초**. Class/resource 7,917개, 실행 중 변경 0, source mismatch 0. 전체 repository Maven suite는 아니다.
- **포함 변경**: retained subset/complement의 조합별 전개 제거, incumbent projection 재사용, 전역 gate suffix 탐색 제거, empty scalar-map에서의 lazy native handle 복원 제거. Real-child 재사용, populated scalar-map의 exact reuse, source/action/proof 계약은 유지한다.
- **근거**: `evidence/relation-union-projection-full-gate-v46/validation.json`, `evidence/v46-probe-gate/tests.log`. 실패 2건 및 conservative guard의 이전 성공 gate는 각각 별도 폴더로 보존했다.
- **원격 변경 분리**: 검증 완료 후 origin/main `1b77bf7a01`이 도착했다. 현재 검증 소스의 커밋/JAR을 먼저 고정해 v46 실측을 수행하고, incoming 변경은 이후 통합 빌드에서 검증한다. v46 실측을 incoming 이후 엔진의 검증으로 표시하지 않는다. 20초 목표는 여전히 미달성이다.

## V55 실측: 선택적 gate cache의 사전 footprint 작업 — 진행 중

- **문제/증상**: 네 Docker trial 모두 60초 watchdog, fullInitial 미완료. 추가 gate footprint work가 LR 95,171,210 / GLM 47,732,346 누적. OFF LR footprint sample 243개 중 153개가 추가 gate 수집 경로다.
- **원인**: ordinary root summary admission 이전에 모든 선택적 gate footprint를 구축하고, 실제 잔여 cache capacity 거절은 나중에 한다. 각 query의 work 상한은 안전성만 보장하며 반복 suffix 탐색 비용 자체를 없애지 못한다.
- **해결 방향**: V56에서 root summary를 먼저 확정하고 추가 gate capacity를 먼저 검사한다. Pruning 이전 원본 의존성은 실패/제거 alternative와 hidden owner를 포함해 유지해야 한다. 단순히 pruned graph만 순회하는 변경은 허용하지 않는다.
- **수정 대상**: `NativePlacementContinuity.java`, gate admission 회귀 tests/metrics. 아직 V56 production 변경 없음.
- **검증/근거**: `R/evidence/v55-footprint-witness-callers.json`, `v55-planner-breakdown.txt`, `candidate-v55-detailed-topology-outcomes.json`; V55 선택 gate 210 classes / 1613 tests / 0 failures / 0 errors / 3 skips. 모든 own Docker container 정리 완료.
- **잔여 이슈**: actual gate admission/capacity rejection 전용 계측은 없음. 20초 목표와 전체 ML coverage 미달성. Witness intern/identity fast path는 이미 구현돼 있어 중복 최적화하지 않는다.
- **잠재 회귀/감지**: phase 순서 변경이 negative dependency invalidation, generated-root exclusion, cache policy를 훼손할 수 있다. 회귀 먼저 추가하고 proof/owner identity parity 및 work 절감을 검증한다.
- **의사결정 근거**: candidate/pruning 합법성은 그대로 두고 optional cache 준비 작업 순서만 최적화한다. 외부 build 중첩(OFF 13/25, detailed 14/25) 때문에 partial sample을 완료 시간 개선으로 주장하지 않는다.

### V56 phase 순서 수정 및 회귀 검증 — 통합 gate 진행 중

- **변경**: ordinary root summary admission 뒤에 gate를 하나씩 처리한다. 현재 entries/states/rows 잔여 용량과 동일 key 교체 credit을 footprint보다 먼저 검사한다. 최종 exact non-evicting guard도 유지한다.
- **Negative authority 보존**: DAG pruning이 덮어쓰는 모든 원본 list(일반 descendant 포함)를 query-local journal에 보관한다. Footprint는 원본 list, summary는 viable list를 읽는다. Journal이 불완전하면 참조를 버리고 optional gate pass만 중단한다. Root/cyclic proof 경로는 유지한다.
- **예산**: journal+staging은 기존 S/A를 공유하고 W=S+A work cap 안에서 동작한다. 남은 실제 cache state headroom까지 footprint 순회 ceiling에 반영한다. 물리 heap byte 상한을 주장하지 않는다.
- **RED/GREEN**: full ordinary cache에서도 gate footprint를 구축하는 반례(7 tests 중 의도된 1 failure) 보존. 새 private-boundary 회귀 14 PASS, 기존 public continuity/owner/FULL-seed 회귀 190 PASS. 합산 journal+staging 상한, 부분 journal overflow, dead ordinary+hidden owner, generated root, 교체 credit, 순차 admission, E/S/A 각각의 용량 거절을 검증했다.
- **독립 검토**: production 의미 보존 CLEAR. Preflight `get`은 access-order LRU를 갱신할 수 있으므로 모든 cache 순서 불변을 주장하지 않는다. Synthetic tests의 null source로 실제 donor identity를 입증했다고 주장하지 않는다; 기존 public 회귀를 함께 검증한다.
- **근거**: `R/V56_NATIVE_GATE_PHASE_ORDER_PLAN.md`, `evidence/v56-capacity-order-red.log`, `v56-budget-final-focused.log`, `v56-existing-focused.log`, `v56-production-review.txt`.
- **잔여 검증**: 최신 최종 테스트를 포함한 통합 package, main 병합 확인, 동일 Docker 실측. 20초 달성 주장은 아직 불가하다.

### V56 최종 선택 통합 gate 통과

- 최종 shared-budget/E·S·A saturation oracle까지 포함해 **210 actual classes / 1,621 tests / failures 0 / errors 0 / skipped 3**. BUILD SUCCESS 2026-10-13 07:47:19+02, `R/evidence/candidate-v56-package.log`.
- 초기 gate도 같은 수로 통과했지만 테스트 oracle 강화 이전 결과는 `candidate-v56-before-final-budget-oracle-package.log`로 별도 보존했다.
- Main fetch 결과 추가 incoming 없음. 검증본을 게시·봉인하고 동일 조건 Docker 실측으로 이어간다. 시간 목표와 ML 전체 coverage는 아직 미완료다.

### V56 실측 완료: 용량 순서 수정만으로는 충분하지 않음

- 네 실행 모두 watchdog60/fullInitial receipt 없음. 선택 gate 통과를 20초 목표 달성으로 간주하지 않는다.
- LR gate attempts 303,837 / preflight capacity reject 22,544 / footprint starts 281,287 / actual admissions 161,885 / final capacity reject 0. Gate work **114,059,905**로 V55 95,171,210보다 크다. 단계별 기회와 조기 state-ceiling 거절이 달라져 단순 work 절감은 성립하지 않았다.
- GLM attempts 115,974 / preflight reject 119 / actual admissions 96,427; gate work 42,527,709. 부분 progress 표본이므로 전체 완료 속도 개선을 입증하지 않는다.
- OFF LR samples 2,311, direct binding 60.41%, footprint inclusive 270. OFF GLM samples 2,793, canonical 8.20%, joint 5.59%. 외부 build 중첩 OFF25/25, detailed18/25; 자체 heavy command는 네 timed trial 이후에만 실행했다.
- 근거: `R/evidence/v56-planner-breakdown.txt`, `candidate-v56-detailed-topology-outcomes.json`, `v56-contention-summary.json`. 모든 own container 정리 완료.

## V57 같은 query 내 동일 native gate 중복 생성 — 회귀 재현

- **문제/원인**: gate identity는 proven summary가 cache에 들어간 뒤에만 발견 가능하다. 한 graph에서 같은 native X를 unpinned 및 pinned real state로 방문하면 동일 exact gate recipe를 각각 새 객체로 만든다.
- **재현**: 공개 query fixture `R=X+log(X)`에서 log clause가 published X를 pin한다. 두 입력 축의 gate가 예상 2회 대신 실제 4회 생성된다. Explicit rectangle·zero-budget 경로와 ordered proofs 및 identity owner footprint는 같고 nonempty다. `v57-query-gate-red.log`: 158 tests / 의도된 1 failure.
- **해결 방향**: 동일 query의 완성된 immutable gate만 기존 exact key로 공유한다. Proof나 grounded status를 공유하지 않으며 active gate는 기존 cycle 검사에 전달한다. Index는 graph construction 종료 때 폐기한다.
- **예산/위험**: cumulative work W는 journal/admission과 공유한다. Index의 E/S/A live reservation은 폐기 후에만 반환해 cap1 memo 경로를 굶기지 않는다. Cycle detection, 다른 owner/fact/relation identity, FULL seed, 작은 budget을 회귀 검증한다.
- **검증 계획/의사결정**: `R/V57_QUERY_GATE_INTERNING_PLAN.md`. 실제 local-hit 빈도가 낮으면 이 경로를 더 확대하지 않는다. Candidate/합법성 규칙 변경은 없다. 아직 V57 production 수정 전이다.

### V57 query-local gate interning: 수정 및 focused 검증

- **구현**: 기존 exact key로 동일 query 안의 immutable gate identity만 공유한다. Resident summary lookup이 우선이며, lookup 전에 기존 fixed-owner·source 검증을 유지한다. Active gate는 기존 cycle protocol을 그대로 통과한다.
- **수명/예산**: graph build `finally`에서 index 참조를 해제하고 해당 E/S/A 예약만 반환한다. 공유 cumulative W는 초기화하지 않는다. Capacity miss는 이미 보관한 key의 lookup을 허용하지만 work 소진은 이후 optional 작업도 중단한다.
- **테스트 fixture 정정**: 처음 positive fixture의 resolver memo 인수 `(0,0)`가 E/S/A까지 비활성화했다. 이는 production 오류가 아니므로 `*disabled-fixture-error.log`로 별도 보존했다. Fresh resolver `(32,256)`로 수정한 올바른 RED도 이전 V56에서 158 tests 중 의도된 1 failure(expected2/actual4)를 재현했다. 수정본은 public focused **199 PASS**, private 경계 **8 PASS**다.
- **경계 테스트**: 동일 immutable row를 갖는 shared/unshared cyclic circuit의 실제 dead-row pruning, active gate 재방문, index 폐기, zero E/S/A, 중복 예약, cap1 반환 후 gate admission, combined work 상한과 sticky exhaustion을 검증했다. 잘못된 nested class qualifier compile 실패도 별도 archive 후 수정했다.
- **독립 검토**: production 및 최종 test coverage CLEAR. 새 private key는 null fact/realization/relation을 쓰므로 full donor authority의 독립 증명으로 과장하지 않는다. 기존 public continuity/identity 회귀와 변경되지 않은 exact key/guard를 함께 근거로 삼는다.
- **잔여**: full 통합 gate/실측 전. 새 main의 complement 유지·DP projection reuse와 global gate sweep 비활성화 변경을 검토·통합한다. Extra gate sweep은 후보 생성 규칙이 아닌 optional memo 최적화로, 비활성화는 합법 후보를 제거하지 않는다.
## origin/main 1b77의 gate phase 변경 병합

- Incoming은 pruning의 원본 row journal과 `cacheNativeGateComponents` 호출을 새로 도입했다. 이전 helper 이름의 호출만 제거하면 journal 작업은 남으므로, 두 경로를 함께 제외했다.
- 현재 production은 gate admission budget을 만들지 않고 기존 2인자 DAG pruning과 real-child cache만 실행한다. Incoming helper·metrics·전용 회귀는 보존하되 production에서 실행하지 않는다. 위 V55/V56 기록은 다른 엔진의 이력이며 이번 통합 엔진의 활성 경로가 아니다.
- 별도 독립 검토에서 root-child cache 추출은 의미가 같고, gate journal/budget/새 counters는 비활성 경로 안에 있음을 확인했다. 통합 소스의 새 gate로 재검증한다. Frozen v46 a59c23069b/JAR8b941b3396은 변경하지 않는다.

### 1b77 병합 후 회귀 gate 통과

- 병합 커밋 `942b752441`: selected FedPlanner **1,529 PASS / 179.156초**, class/resource 7,917개, 실행 중 변경 0, source mismatch 0. Frozen v46과 별도 `engine-v46-main-merged-gate`에서 검사했다.
- `evidence/gate-disabled-incoming-full-gate-v46-main/validation.json`에 보존한다. Independent merge review CLEAR: gate budget/journal/global cache 호출은 비활성, 2인자 DAG pruning과 real-child cache는 유지한다.
- v46 actual은 여전히 커밋 `a59c23069b50fe75238f040413817510dfd91f31`, JAR SHA-256 `8b941b339605fa68c0f952b26d075f02edda53f5623122b9dce1332904e18002`, manifest `de8774f94ce826db7b079dafe8cc0466545392f6099aace5afc2022c51dc20ee`이다. 이후 checkout으로 실측 엔진을 교체하지 않는다.

## v46 실제 COFEE pair 완료 — correctness PASS / 20초 목표 FAIL

- **관측**: LogReg 전체 379.142013755초, Analysis287.022919983 / Model6.539413515 / Cost8.395073625 / Optimizer73.384578917. GLM 전체118.344668550초, Analysis74.841736716 / Model4.512632992 / Cost9.389494162 / Optimizer24.997826467. 각1회이며 반복 paired causal claim은 하지 않는다.
- **메모리**: LR coordinator8,482,996,224 / worker748,265,472 bytes, GLM4,977,336,320 /755,060,736 bytes.
- **정확성**: numeric true/audit0; v43·v45 각각과 selected raw/fields/sections, objective bits/certificate, cost surface 및 selection fingerprints 모두 동일. 두 host cleanup 완료. JAR/manifest/receipt binding exact.
- **실제 개선 범위**: LR footprint requests159,880/raw92,488,567/unique29,950,314로 v43 수준을 복원했다. 그러나 PROOFS_CONSUMED6,121,451, PARTITIONED_RETAINED2,555,577은 그대로다. GLM도 proofs688,373/retained113,551 그대로다. v45 대비 clauses는 LR−40/GLM−51, candidate key/fact는 변화0. Ordinary complement union이 실제 주요 전개 루프를 줄였다고 주장하지 않는다.
- **잔여 문제**: Analysis와 DP가 여전히20초를 크게 넘는다. 후속 conditional growth와 key-first duplicate 검사, exact fallback 원인의 저비용 일반 카운터 구분을 진행한다. 후보/권한/비용 모델은 변경하지 않는다.
- **증거**: comparison SHA`1e5e69f48d578ab6dc32fb07b1ee5139b2260bdc0517d7b8db3049061cb87acc`, binding SHA`4006dcad84c727cf356eb2724d41067655cff75718d837992f9366d0087021ef`, evaluator SHA`fb4eaa2a79e0618691a015918260f96321ca210002225f18e9c5bca84e4ea5c3`. Paths `evidence/cofee-50k128-v46-validation/` 및 `evidence/candidate-engine-relation-union-projection-v46-20261013/`.
- **목표 상태**: evaluator FAIL, accepted0/3씩. OMX에 fail checkpoint 기록, canonical evidence-list를 v46으로 갱신하고 이전 목록은 보존했다. Evaluator의 accepted-engine 관련 부가 오류는 qualifying run0건에서 파생되며, 실제 두 receipt가 다른 JAR이라는 뜻은 아니다.

## v47 conditional base growth 구현·검증

- **변경**: 동일 exact source-owner authority와 제외 영역 E를 가진 `BL\E`, `BR\E`에 대해 `BL⊂BR`이면 BR\E를 유지한다. 새 영역 `BR\BL`은 candidate 원본 donor에서, 기존 영역은 이전 donor에서 복원한다. 여러 입력 축의 성장도 지원하며 기존 cap/staging/explicit 경계는 유지한다.
- **반복 갱신 문제**: 첫 성장 뒤 donor는 leaf 목록으로 평탄화돼 있다. 기존 “첫 donor==이전 relation” 조건은 두 번째 성장의 final merge에서 실패해 다시 전개했다. 전체 이전 donor 목록을 **관계 객체 identity와 scope exact authority가 같은 순서의 prefix**로 확인하도록 수정했다. Nested donor를 보관하거나 예산을 확대하지 않았다.
- **실제 binder RED/GREEN**: 기존 구현은 8개 조합을 `SharedCanonicalList`의 8개 member로 materialize했다. 이 fixture의 PARTITIONED_RETAINED counter는0이며, “추가5개”는 논리적 집합 크기 차이지 관측된 순회량이 아니다. 수정 후 2×2−1→3×3−1은 native size8/handles0, 다시4×4−1로 성장하면 size15/handles0이다. 이전8개 clause 객체를 새 ordinal에서도 assertSame으로 보존했다.
- **Canonical 중복 검사**: key가 다른 realization의 record equality가 support부터 비교하여 lazy member를 복원했다. 먼저 key를 확인하고 같은 key에서 기존 record equals를 그대로 적용한다. 더 엄격한 exact-authority helper로 대체하면 기존 structural duplicate 거절 계약이 달라지므로 채택하지 않았다.
- **권한 검토**: wrapper object identity와 source-owner authority를 구분한다. 기존 approved conditional union도 새 wrapper를 기존 축에 재대응시키며 원래 donor clause를 복원한다. 성장 union은 candidate의 BR\E를 그대로 출력하고 scoped donor를 유지한다. 따라서 같은 owner의 동등한 wrapper 재생성은 허용하지만 foreign owner는 거절한다. 초기 mask-identity 검토 우려는 이 기존 계약과 donor 경로를 재확인한 뒤 철회됐고 최종 independent review CLEAR다.
- **검증**: 관련117 PASS, 고정 seed small-domain set/rank/hash/restrict, changed hole·owner/witness/layout·cap·staging·explicit 및 Physical Local/Exact 회귀 포함. 전체 통합 gate는 별도로 수행한다.
- **카운터**: partitioned retained loop를 original product ordinary/conditional × residual/full의4개로 나눈다. 잔여 residual 요청 거절 이유는 staging/conflict, explicit, relation count, 표현 실패로 기록한다. 이 거절 카운터는 standalone을 포함한 모든 residual 요청 횟수이며 partitioned tuple 방문량과 같은 단위가 아니다. 기존 분기와 size만 사용하고 추가 tuple 순회는 없다.
- **증거/한계**: `lanes/native_conditional_base_growth/artifacts/native-conditional-base-growth-final2-20261013/`; production SHA`10b46006729dd0065d58d8663ed76c32712862fce5b3272a251615d53a80614e`, tests SHA`8750b5e29eecefca3fcfad357642d3b2c2621e921ee79d8ed01eb77c47884f26`. 실제 LogReg/GLM 적용량과 시간·메모리 효과는 아직 미측정이다. 임의의 서로 다른 hole/base union을 지원한다고 주장하지 않는다.


### v47 통합 gate: 불필요한 대표 clause 생성에 의존하던 기대값 갱신

- 첫 selected gate는 1,534 tests / 20 failures / 179.286초였다. 컴파일 성공, class/resource 변경 0, source mismatch 0이며 실패 원본은 `evidence/conditional-growth-full-gate-v47-pre-expectation-update/`에 보존했다.
- 20건 모두 key-first 비교 전 constructor가 읽던 native 대표 clause 수를 1로 기대했지만 실제로 0이 된 경우다. 다섯 테스트 클래스에서 unused native relation의 기대를 0으로 강화했다. Eager/lazy 결과·source/dependency identity·withdrawal/restoration·metadata·cache 검증은 유지한다.
- 실제 native pin을 선택하는 테스트는 대표 clause 1개를 읽는 기존 기대를 유지하고, 완전 순회 fallback은 `product.size()` 기대를 유지했다. Test-only patch SHA-256 `8fc1e5904cb2d912745ed165865a04f94dbea1358337d5c0e71f117572686621`, focused 42 PASS, 독립 검토 CLEAR. 최종 소스로 전체 selected gate를 다시 실행한다.


### v47 최종 회귀 gate 및 엔진 고정

- Selected FedPlanner **1,534 PASS / 186.876초**, 별도 probe **9 PASS / 1.529초**. Class/resource 7,917개, 실행 중 변경 0, source mismatch 0이다. 전체 repository Maven suite를 실행한 것은 아니다.
- 검증 대상은 conditional relation의 여러 축·여러 wave 성장, old-first donor 권한, key-first duplicate 비교, 기존 분기 counter다. Source·proof·layout·withdrawal·mixed/native/explicit 반례를 유지했다. Production 및 test-only 기대 강화는 각각 독립 검토 CLEAR다.
- Gate `evidence/conditional-growth-full-gate-v47/validation.json` SHA-256 `d11eb55ff5b36381e4f2591fccfc06c4a2c33226a60df3fe8db46ac80413006c`. Probe는 `evidence/v47-probe-gate/tests.log`이다. 실패 20건은 별도 증거로 보존했다.
- Gate 중 origin/main에 query-local gate interning `8b15621592` 및 merge `3b5040b7b1`이 도착했다. 현재 검증한 v47을 먼저 고정해 실제 pair를 실행하고 incoming은 별도 통합 gate에서 검증한다. 아직 실제 성능 개선을 주장하지 않는다.

### V57 최신 main 병합 경계

- Incoming `9fa1eceb10`은 global synthetic-gate suffix sweep을 production에서 호출하지 않는다. V56 실측에서도 LR 추가 gate work 증가/footprint 비용을 확인했으므로 이 변경을 보존했다. 이는 optional cache 수집 중단이며 proof graph·합법 후보를 제거하지 않는다.
- V57 query-local exact circuit interning은 그대로 적용한다. Production에서는 graph 종료 후 index를 폐기하고 ordinary real-child summary만 처리한다. Journal/gate-admission helper는 upstream의 독립 계약 테스트 용도로 남지만 production 경로에서 실행되지 않는다. 따라서 “새 runtime에서 journal과 index가 같이 실행된다”는 주장은 하지 않는다.
- Complement authority 보존, retained native support 불필요 materialization 제거, incumbent source projection reuse는 별도 독립 source review CLEAR다. 기존 FULL header·donor 순서·owner identity·strict improvement 제약을 유지한다.
- Merge conflict는 NPC의 sweep 호출 제거를 채택하고 local query index를 보존했으며, 양쪽 세션 기록도 보존했다. 더 이상 읽히지 않는 `hasNativeAxisGates` flag만 제거했다. Fresh 선택 통합 gate로 전체 병합본을 검증한다.

### V57 병합본 최종 선택 통합 gate 통과

- Fresh **211 actual classes / 1,634 tests / failures 0 / errors 0 / skipped 3**, package BUILD SUCCESS 2026-10-13 08:12:29+02. `R/evidence/candidate-v57-package.log`.
- Local query-gate interning과 latest-main global sweep 중단/retained complement/DP projection reuse를 함께 검증했다. 전체 repository suite가 아닌 선택 FedPlanner gate다.
- 검증본 merge commit을 게시·봉인한 후 같은 자원으로 네 Docker trial을 실행한다. 20초 및 전체 ML coverage 달성으로 보고하지 않는다.


### v47 고정 이후 query-local gate interning 병합

- Incoming `3b5040b7b1`의 query-local gate interning을 통합한다. Fact·realization·relation 객체 identity와 witness·pivot·axis가 같을 때만 같은 query 안에서 gate를 재사용하며, 갱신된 relation은 identity가 달라 재사용하지 않는다. Global suffix 탐색은 계속 비활성이다.
- Independent review CLEAR. v47 conditional-growth production과 변경 영역이 분리돼 자동 병합됐다. 세션 기록 충돌은 양쪽 증거를 모두 보존해 해결했다. 병합 소스로 별도 selected gate를 수행한다.
- 현재 실제 Docker v47은 고정된 `6713d749c9`/JAR `7641b78263699b074cd73f1d73d6a6d78b09ff5daf9d8e9f95b4b31753402feb`를 실행한다. 이 후속 병합의 성능 측정으로 혼동하지 않는다.


### Query-local interning 병합 gate 통과

- Merge `84ed273792`: selected FedPlanner **1,543 PASS / 208.946초**. Class/resource 7,919개, 실행 중 변경 0, source mismatch 0이다. Native query-gate identity/cap/release 및 v47 growth/권한 회귀를 함께 통과했다.
- 근거 `evidence/query-interning-incoming-full-gate-v47-main/validation.json`. 실제 v47은 기존 frozen engine으로 계속 실행하고, 병합 엔진은 v48로 따로 고정한다.

### V57 실측 판정 및 다음 구조 개선 — 진행 중

- **증상/조건**: published3b5040b7b1/sealed SHA f26af6abe4f0f6115a77f9e17eb6f3db8cf279074fe7aa5f83c0ea20e977c1e1, 동일 Docker 4CPU/16GiB/10GiB, 네 trial 모두 watchdog60/fullInitial 없음.
- **원인 근거**: query-local gate hit LR28,456/1,755,131(1.62%), GLM0/141,184. 같은 query 내 중복은 실재하지만 실제 workload의 주병목은 아니다. Global optional gate sweep work는 두 workload 모두0으로 제거됐다. OFF LR direct binding62.95%, skeleton/graph/materialization 작업이 잔존한다.
- **결정**: query-local index 범위·budget 확대 대신 중간 dependency 표현 복사와 반복 circuit 구성의 구조적 제거를 검토한다. Proof/viability를 owner/witness만으로 합치는 변경은 금지한다.
- **수정 파일/검증**: 이번 항목은 실측 보고만 추가. 최종 V57 gate211classes1634/0/0/3. 상세 counters/stack은 R/evidence/v57-*.json/txt 및 candidate-v57-detailed-topology-outcomes.json.
- **잔여/위험**:20초/전체ML coverage 미달성. OFF15/25,detailed25/25 external build overlap; 자체 heavy commands 겹침 없음. 복합 merge의 효과를 단일 query-index 변경에 귀속하지 않는다. 다음 변경은 exact proof·identity·cycle·budget 회귀 및 fresh 동일Docker로 검증한다.

## V58 native dependency 중간 표현 제거 — focused 검증 완료

- **문제/원인**: native gate의 각 source option마다 option-specific skeleton list/records를 새로 만들고 곧바로 overlay가 proof dependency로 복사했다. Axis owner는 앞에서 fixed-owner 제외가 증명되므로 이 중간 표현은 불필요하다. V57 LR gate alternatives 누적 12,998,420개였다.
- **해결/원칙**: `nativeAxisDependencies`가 기존 affected skeleton의 key/witness/position을 보존하며 선택 source를 최종 dependency에 직접 바인딩한다. Source handle은 row당 한 번 계산한다. Fixed-owner guard, invariant overlay, candidate/grounding/cycle/pruning 규칙은 변경하지 않는다. 새 cache 없음.
- **수정 파일**: `NativePlacementContinuity.java`, `NativeSkeletonMaterializationMemoTest.java`.
- **검증**: 새 helper 없음 RED 13 tests/2 failures 후 native focused **205 PASS**. 실제 legacy two-step와 direct 3slot 결과/상태/owner·source·witness identity/order/duplicate slot parity, handle lookups 3→1, empty0을 검증한다. Independent source/test review CLEAR.
- **범위/잔여 위험**: helper private boundary test 자체는 실제 workload 3slot 빈도나 시간 개선을 증명하지 않는다. 기존 public native/conditional/query cycle suite를 함께 검증했다. 최종 selected gate 및 동일 Docker 실측 대기. 더 큰 반복 native raw circuit 구성은 V59 구조 설계로 분리한다.
- **근거**: `R/evidence/v58-native-fusion-red.log`, `v58-native-focused.log`, `R/V59_NATIVE_CIRCUIT_DESIGN.md`.20초 목표 미완료.

## V58 clause 병합의 duplicate-first 처리 — focused 검증 완료

- **문제/근거**: GLM OFF canonical compare225/2782 inclusive samples 중 support-clause 병합 경로가 크다. Two-list merge는 정확히 같은 head도 rope 비교부터 하고, K-group은 나중에 제거할 중복 clause의 missing key를 먼저 만들었다.
- **해결**: two-list는 기존 cached hash로 빠르게 불일치를 확인하고 full equality로 중복을 판정한 뒤 같은 경우 comparator를 생략한다. Hash만으로 dedup하지 않는다. K-group은 exact first-donor dedup 이후 살아남은 clause만 missing descriptor를 생성한다. Indexed clause의 specialized ordering path, 공급된 key identity, unequal canonical tie fallback, opt-in duplicate diagnostics는 유지한다.
- **수정 파일**: `PlacementAnalysis.java`, `CandidateRealizationCanonicalizationTest.java`, `DuplicateMergeDiagnosticsTest.java`.
- **검증**: 유효 RED62tests/2의도실패 → focused64PASS. Two-head duplicate의 canonical comparisons1→0 및 left donor/diagnostics 유지. K-way discarded duplicate identity는 scoped canonical cache에 생성되지 않고 unique missing-key clause는 생성된다. 초기 singleton sidecar 없음, 계측되지 않는 counter0 fixture 오류는 별도 archive 후 수정했다.
- **의사결정/위험**: 후보·합법성·privacy 규칙 변경 없이 중간 canonical 작업만 제거한다. 모든 unequal 비교에서 비싼 equals를 추가하지 않도록 cached hash guard를 쓴다. 실제 전체 속도 개선은 미측정; selected 통합 gate 진행 중이다.
- **근거**: `R/evidence/v58-merge-red.log`, `v58-merge-focused.log`, `R/V58_INTERMEDIATE_WORK_PLAN.md`.

### V58 최종 선택 통합 gate 통과

- Fresh211classes /1,638tests /failures0 /errors0 /skipped3, package BUILD SUCCESS08:33:38+02. 전체 repo suite가 아닌 selected FedPlanner gate다.
- 근거 `R/evidence/candidate-v58-package.log`. Native205/merge64 focused와 최종 통합 검증에서 의미 회귀 없음. Main fetch 추가 incoming 없음. 독립 최종 검토 이후 게시/봉인/동일Docker로 진행한다.
- 이 통합 테스트 통과는 전체 planning 시간이나20초 달성의 증거가 아니다.

### V58 실측 완료 — 여전히 공통 분석 미완료

- published5483ecbc6f, sealb1504500acbd9588db0bb245e279268475d481070d6999597836d316223f316c. 네 fixedDocker trial 모두watchdog60/no fullInitial/owncontainersremoved.
- OFF LR2154samples/direct62.86%/peak5.735GB; GLM2704samples/canonical9.32%/joint7.03%/peak3.040GB. DetailedLR gatebuild1,956,950/gaterows14,745,571. 외부 build overlap0/25 양phase(기존외부planner2개는존재), 자체heavy도trial이후로분리.
- 근거 R/evidence/v58-analysis-driver.log, v58-planner-breakdown.txt, candidate-v58-detailed-topology-outcomes.json, v58-contention-summary.json. 샘플 비율이나 진행량 증가를 완료 속도 향상으로 해석하지 않는다.

## V59 원본 native circuit 반복 구성 제거 — 진행 중

- **원인**: ordinary rows는 topology 기본 표현을 재사용하지만 native accepted path는 매 query마다 대표 skeleton/axis partition/gate rows를 재구성한다. 이미 증명된 graph 결과를 넓게 공유하는 대신 immutable 원본 표현의 재구성을 제거한다.
- **구현**: 기존 resident CandidateTopology에만 resolver-local exact recipe sidecar를 부착한다. Fact/realization/relation identity+witness가 일치해야 한다. Host coverage는 바꾸지 않고 새로운 native-only entry도 만들지 않는다. Gate는 raw alternatives 그대로이며 DFS/cycle/pruning/현재fixed-root overlay를 우회하지 않는다.
- **예산/수명**: 기존 topology E/rows 안에서 headers·axis/invariant/branch/row/dependency 단위를 청구한다(실제 heap-byte 보장 아님). Optional admission은 기존 topology/overlay를 밀어내지 않으며 normal admission은 circuit을 먼저 반환한다. Host 교체/퇴출 시 정확히 반환하고 revision sidecar는 비운다. Admission은 gate의 미리 계산된 크기만 읽어 suffix graph를 재순회하지 않는다.
- **수정 파일**: NativePlacementContinuity.java, SearchSpaceMetrics.java, NativeHybridUnpinnedTopologyTest.java, NativeSkeletonMaterializationMemoTest.java.
- **검증**: 공개 ordinary warm-host/fixed-C→fixed-R 반례RED27/1expected2actual4, 초기focused206PASS. 두 query 모두 graph가 실행되며 proof/signature/source·owner identity가 explicit/cold와 같다. Lifecycle/예산/고정경계 회귀 추가 진행. Production 독립 검토 CLEAR; 최신 경계테스트 최종 검토 대기.
- **주의**: 첫 lifecycle same-object assertion은 AnalysisScope 미설정으로 정상 reindex를 관측해 실패했다. 실제 stablehandle scope로 fixture를 수정했으며 해당 실패로그는 분리한다. Pure bind test의 nullgatekey는 source authority 증명이 아니라 raw invariant/current pin 바인딩 경계 검증이다.
- **잔여**: 최종 통합 gate/동일Docker payoff 확인. 이미 resident host인 native query의 비율은 미측정이므로 실제 적용률을 새로운 NATIVE_CIRCUIT counters로 판단한다.20초/전체MLcoverage 미완료.

### v47 실제 LogReg 분기 결과: explicit retained authority가 주된 병합 거절

- Analysis 종료 counter: `PARTITIONED_RETAINED_PROOFS=2,555,577`, ordinary full=2,542,629 / ordinary residual=0 / conditional full=12,948 / conditional residual=0. 네 합계 일치. 기존 v46의 proof 소비 6,121,451회가 그대로다.
- Residual requests=60,677 중 explicit 거절=60,461, 표현 불가=216, staging/conflict=0, relation count=0, successes=0. 합계 일치. 요청의 약99.64%가 explicit 경계에서 멈춘다. 이 거절 counter는 standalone 요청도 포함하므로 retained 순회 개수와 직접 같은 단위로 취급하지 않는다.
- 따라서 conditional base-growth 단위 개선이 실제 주요 경로를 줄였다고 보고하지 않는다. 다음 수정은 원래 explicit clause가 새 native domain 안의 exact 동일 member임을 증명할 수 있을 때만 기존 객체를 donor로 보존하며 relation을 발행하는 것이다. 임의 sparse extra/non-native/foreign authority는 기존 exact fallback이다.
- Query-local interning 포함 v48은 별도 frozen 엔진으로 준비했다. 이후 incoming `5483ecbc6f`의 intermediate dependency 및 canonical 중복 작업 제거는 다시 별도 통합한다. Frozen v47/v48으로 해당 incoming 성능까지 검증했다고 주장하지 않는다.


### v47 실제 COFEE pair 완료 — correctness PASS / 20초 FAIL

- LR full376.506956632초 / Analysis283.123881412 / Model7.402964191 / Cost6.390780015 / Optimizer75.889831905. Peak coordinator8,674,181,120 / worker757,227,520 bytes. GLM full122.992459717초 / Analysis78.535269512 / Model4.460003007 / Cost8.529154043 / Optimizer27.544759933. Peak4,874,715,136 /770,875,392 bytes.
- Objects(key/fact/clause/handle): LR25,137/208,886/2,312,553/2,232; GLM16,157/125,264/1,088,634/607. LR retained/proof 순회는 v46과 같다. GLM residual requests20,927 중 explicit20,882+표현불가45, successes0; retained 순회113,551은 모두 ordinary full이다. 두 counter 합계 검증 PASS.
- 두 workload numerictrue/audit0, v46+v43 selected 원문/fields/sections/objective/certificate/cost/selection parity 모두 PASS. 각1회이며 all-assignment 전수 비용 증명은 아니다. 양쪽 cleanup 완료.
- Comparison SHA `9483734cc27570cf2cc0157cc573e7d0f7fbf7b62ebeec99cea537bab2edaa1d`; binding `3bade438ae4dae85074295dc6eb3d13b6083f70364abac8cc4778e72bb8d1005`; evaluator `b4b6c56d87c63d4189c94c65d4144551f6ed681df33f216df98ba04e344a52d7`. Canonical evaluator rc1, OMX FAIL checkpoint 및 evidence-list 갱신. 이전 목록 별도 보존.
- 다음 구현은 exact retained explicit member를 singleton donor로 보존하는 bounded overlay다. 기존64 donor/보관 cap을 유지하고, final mixed merge가 old-first 권한을 검증한 overlay를 그대로 발행하도록 한다. 순수 explicit retained + fresh native의 좁은 경로부터 검증하며, 상관·shared cost·함수 경계 합법성은 변경하지 않는다.


### Intermediate dependency/canonical 작업 제거 통합 검증

- Merge `322c12f907`: selected FedPlanner **1,547 PASS / 190.245초**, class/resource7,919개, 변경0/source mismatch0. Independent review CLEAR. Key-first realization guard를 보존했고 exact duplicate는 기존 full equality와 earliest authority를 유지한다.
- 근거 `evidence/native-dependencies-incoming-full-gate-v48-main/validation.json`. Native axis의 fixed-boundary 배제 전제 및 invariant overlay를 유지한다. v48 frozen 엔진 이후의 변경이며 실제 성능 검증은 별도로 필요하다.

### V59 경계 회귀 동결 및 최신 main 통합

- 최종 동결 focused **211 tests PASS / 1 ignored PUBLIC fixture**, `R/evidence/v59-native-lifecycle-final-focused.log`. Public two-root reuse, no-host, zero-headroom fallback, replacement/revision/eviction exact release, warm fixed-axis rejection, raw invariant/current-root rebinding을 검증했다. Independent source/test review CLEAR.
- Cap1 eviction의 초기 public DFS fixture는 host 자체가 탐색 도중 퇴출돼 의도한 admission을 만들지 못했다. 실제 native circuit admission을 먼저 확인한 뒤 다른 owner의 topology를 넣는 deterministic cache-admission seam으로 수정했다. Public 두-root integration은 별도로 유지한다. 잘못된 fixture의 실패 로그는 archive해 유효 RED와 구분한다.
- 로컬 `23ed1144e1`에 incoming `6a212354b5`를 병합했다. 문서만 충돌했고 양쪽 기록을 보존했다. Key-first canonicalization은 native support를 실제 읽기 전 materialized count를1→0으로 바꾸지만, accepted native query 뒤 representative1 조건은 유지한다.
- 최종 selected211-class package gate를 fresh 실행 중이며, 실측 전에는 이 변경의 workload 효과를 주장하지 않는다.

- 최종 병합 선택 gate **211 classes / 1,649 tests / failures0 / errors0 / skipped3**, BUILD SUCCESS09:03:05+02. 근거 `R/evidence/candidate-v59-package.log`. 전체 repo 테스트가 아니며 실제 성능은 미측정이다.

## V59 원본 회로 공유 실측 완료 — 다음 구조적 경계

- **상태/검증**: `ec6de943a2` 게시 후 fixed Docker4회 모두 watchdog60/no fullInitial. 기존211classes1649test gate PASS와 성능 목표 FAIL을 구분한다.
- **제거한 작업**: LR native circuit756,278/915,034회 재사용, gate row10,185,764회 재생성 생략. GLM21,503/110,870회 재사용,261,282rows 생략. Optional capacity bypass0.
- **남은 원인**: LR OFF 첫 planner frame witness.equals146, pruneDead129, graph build121/2595. Skeleton inclusive의 generic topology190/hybrid85 sample은 여전히 row 재구성이 있음을 보여준다. 단일 sampled profile로 제거 가능 시간은 단정하지 않는다.
- **방향/위험**: V59 resident-host 적용률은 충분하므로 native-only coverage나 추가 cache budget을 도입하지 않는다. Existing ordinary topology의 raw row를 hybrid 경로에서도 재사용하는 것과 static gate schedule 공유를 검토하되 exact authority/first donor/metadata reads/current fixed overlay/DFS를 보존한다.
- **근거/환경**: R/evidence/v59-hot-callers.json 및 topology-outcomes. External build OFF1/25,detailed25/25; own heavy jobs 격리. 명시적 retained donor 때문에 압축 publication이 다시 개별 proof 순회로 돌아가는 실패도 실제 counters로 확인했다(상단 진행 보고서).

## V60 hybrid 경로의 ordinary row 재구성 제거 — 경계 검증 중

- **문제/원인**: native/ordinary 혼합 owner의 unpinned query가 이미 topology에 저장한 ordinary row까지 매번 skeleton→dependency→edge로 다시 만든다. V59 OFF LR hybrid skeleton85/materialization35 caller samples로 실제 경로를 확인했다.
- **해결/규칙**: 기존 eligible owner/witness host에서 positive rowsByHandle bucket만 빌린다. Exact owner identity와 reference equality를 검사하며 missing/empty/inexact bucket은 원래 clause 경로로 간다. 새 host/cache/admission/예산 없음. 모든 inventory·duplicate 검사는 계속 수행하고 default-edge first donor 및 현재 query overlay를 보존한다. Derived/VALUE_MAP metadata 처리는 그대로 둔다. Candidate legality나 runtime 규칙 변경 없음.
- **변경 파일**: NativePlacementContinuity.java, SearchSpaceMetrics.java, NativeHybridUnpinnedTopologyTest.java.
- **검증**: V59 상 public warm ordinary→native→distinct outer 실제 hybrid 경로 RED31tests/1 intended failure; 변경 후 native focused238PASS/1 ignored fixture. Explicit/cold/no-host와 ordered proof·source/owner identity parity 및 실제 graph 실행 확인. 추가 fixed-owner/negative boundary 검토 중.
- **잔여/위험**: positive bucket을 negative proof로 해석하거나 later alias 검사를 건너뛰면 authority가 바뀐다. 해당 shortcut은 하지 않는다. Warm fixed-source overlay 및 metadata/duplicate fallback 회귀와 독립 검토, 최종 selected gate/실측이 필요하다. 근거 R/evidence/v60-hybrid-row-red.log, v60-hybrid-focused.log, R/V60_HYBRID_ROW_REUSE_PLAN.md.

- V60 추가 경계 focused **239 PASS / 1 ignored**, `R/evidence/v60-boundary-focused.log`. 별도 실제 hidden reaching owner를 고정한 cold/warm hybrid raw alternative 비교로 현재 pin reference/owner identity와 effective edge 순서를 검증했다. Positive bucket 및 missing/foreign-owner 거절을 확인했다. Production 독립 검토 CLEAR, 경계 테스트 독립 검토/최종 selected gate 진행 중. Reused-row counter는 나중에 alias로 거절된 시도에서도 발생할 수 있는 raw 작업량이며 accepted-query 절감량이라고 해석하지 않는다.

- V60 최종 selected **211classes/1650tests/0fail/0error/3skip**, BUILD SUCCESS09:29:00+02. 독립 frozen 경계 검토 CLEAR. 현재 source에서 later duplicate rejection은 그대로지만 warmed hybrid duplicate 전용 fixture는 없으며, 기존 pinned duplicate/실제 migrated-host query로 보완한다. 실측 전 효과 미확인.

### V60 동일 Docker 측정 완료 — 20초 실패 유지

- `eb1e8c47db` frozen4회 모두watchdog60/no fullInitial/own container 제거. LR267,389/GLM6,226 raw ordinary rows 재사용은 실제 적용 증거지만 전체 완료 시간은 없다. OFF direct63.48%/21.94%, GLM canonical7.91%/joint7.68%.
- External build OFF/detailed 각15/25, own heavy 격리. 근거 R/evidence/v60-*.log/txt/json. 반복 row 생성 감소만으로 근본 product 재전개 문제를 해결하지 못했다. 다음 acceptance는 earlier explicit clause와 later native product의 first-donor 보존 압축 merge다.

## V61 explicit retained authority의 native product 재전개 — 구현·회귀 검증 중

- **증상/원인**: 기존 explicit clause가 하나라도 있으면 새 native product의 retained union이 실패해 전체 proof를 개별 소비한다. 최종 emission의 `containsExact` 및 3개 이상 input을 key로 나눈 mixed merge도 lazy relation을 다시 펼칠 수 있다.
- **해결 방향**: 이미 존재하는 explicit clause가 새 product의 exact member임을 FULL seed·proof/source owner identity·layout·witness로 증명할 때만 원래 객체를 singleton leaf donor로 보존한다. 기존64 donor/1M retention-unit 예산을 유지하고 일반 native donors 앞에 둔다. 순수 explicit retained→ordinary native product의 좁은 publication 경로부터 적용한다.
- **규칙 근거**: support 표현·publication만 수정한다. runtime/후보/합법성/privacy/DP 비용이나 timeout을 완화하지 않는다. Conditional/multi-header/extra member/충돌/staging/cap 초과는 기존 정확한 경로를 유지한다.
- **변경 파일**: NativeContinuitySupportClauses.java, PlacementAnalysis.java, PlacementRelationClosure.java, SearchSpaceMetrics.java; NativeSingleAxisProductUnionTest.java, NativeMixedAxisSplitPublicationTest.java.
- **RED 검증**: public pair15tests/1 intended representation failure; cardinality 추가16tests/2 intended failures(기존 native member1개를 불필요하게 생성); 실제 standalone/split binder12tests/3 intended failures(expected proof consumption0, actual4). 단위테스트는 host Java 검증이며 workload 성능 실험이 아니다.
- **근거**: R/V61_EXPLICIT_RETAINED_BRIDGE_PLAN.md, R/evidence/v61-explicit-bridge-red.log, v61-cardinality-red.log, v61-publication-red.log.
- **잔여/회귀 위험**: HashMap iteration을 donor 순서로 사용하지 않는다. 같은 explicit prefix를 재병합할 때 donor를 누적하면 cap을 소모하므로 idempotent 처리한다. 제한/철회/재추가, full-coverage emission reuse, seed/owner/cap 거절과 실제 binder의 최종 lazy 표현을 검증한다. 독립 검토·최종 선택 gate·동일Docker payoff는 아직 미완료다.

- V61 독립 frozen production/경계테스트 검토 CLEAR. 초기 focused296PASS/1ignored. Re-add fixture 첫 버전은 두 축이 달라져 의도한 one-axis union이 불가능했으며, 생존 left축을 유지하고 withdrawn right option만 다시 추가하도록 수정했다. 실패 로그는 `v61-boundary-unrepresentable-readd-fixture.log`로 보존했고 생산코드는 변경하지 않았다. 수정 fixture를 포함한 최종 focused/통합 gate를 재검증한다.

- V61 frozen focused **299PASS/1ignored**. 첫 전체 선택 gate1658tests/1failure는 기존 `NativeMixedExactnessPartitionTest`가 “exact explicit member도 반드시 scalar fallback”이라고 요구하던 기대치였다. 해당 fixture는 권한 충돌이 아니므로 lazy/0소비·원본 donor·전체 equality로 수정하고, FULL seed만 다른 실제 binder 반례를 추가해 기존 scalar2/전체3clauses/foreign donor 보존을 확인했다. 독립 추가검토 CLEAR, focused7PASS. 원래 실패로그 `v61-package-old-explicit-fallback-expectation.log` 보존. 생산코드는 변경 없이 전체 gate를 다시 실행한다.

- V61 최종 fresh 선택 gate **211classes/1658tests/0fail/0error/3skip**, BUILD SUCCESS10:10:09+02. 추가 mixed-exactness7PASS 및 independent CLEAR. 변경은 exact 표현·작업 제거이며 실제 workload 효과는 동일Docker로 별도 검증한다.

### V61 실측 결과 및 V62 원인 분리

- `dea5570a98` sealed four fixedDocker 모두watchdog60/no fullInitial/own container 제거. Explicit bridge success LR2304/18870, GLM370/15409: 실제 적용은 확인했으나20초 미달이다.
- Singleton-per-explicit donor count cap 거절 LR10004/GLM5374, authority/표현 거절6562/9665, mixed0. Cap은 검증 이전 count gate이므로 모두 새 dense 표현으로 해결된다고 단정하지 않는다. 두 terminal sum검증 PASS.
- 다음 V62는 dense exact rectangular explicit prefix를 한 donor leaf로 보관하는 구조를 검토한다. 예산 확대는 하지 않는다. 입력목록의 canonical ordinal과 original clause handles 보존, quadratic axis membership scan 회피, sparsehole/foreign authority/cap fallback 및 bounded preflight가 핵심이다. Architect 설계/독립 RED 준비 중, 아직 생산 수정 없음.
- OFFLRdirect65.76%,GLMcanonical8.76%/joint8.04%. Externalbuild0/25 양phase, oldplanners2개25/25. Partial samples/counts를 완료 속도 향상으로 해석하지 않는다.
