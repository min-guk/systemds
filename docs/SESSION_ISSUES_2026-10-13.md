# FedPlanner 세션 이슈 — 2026-10-13

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
