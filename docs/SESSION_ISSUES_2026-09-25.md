# Session issues — 2026-09-25

## Logreg/FedFirst FULL nary input rejected because runtime anchor address stayed unresolved

- **상태**: 소스 수정 및 집중 회귀 테스트 완료. 전체 W1357 재실행은 수행하지 않음.
- **적용 원칙/제약**: planner가 승인한 placement를 runtime fallback 없이 그대로 실행한다. 동일한 worker/range를 다른 placement로 오인하게 만든 producer 경계를 수정하며, runtime의 정렬 검사를 완화하거나 후보를 닫지 않는다.
- **의사결정 근거**: runtime anchor 생성 로직을 수정했다. `FederationMap.isAligned`나 `FederatedData.equalAddress`를 문자열 비교로 완화하지 않았고, planner의 DNS-free canonical parsing도 유지했다.

### 환경/조건

- SystemDS 기준 commit: `07726cd7fb2e50e8801d46d775454b2c418dadf4`
- 진단 조건: `ml/logreg`, `FedFirst`, W1, LAN, PRIVATE_AGGREGATE
- 실패 명령: `FED n* _mVar80 _mVar74 _Var81 scalar FOUT`
- 별도 진단 artifact: `/home/mchoi/w1357-diagnostics/ordinal18-nary-20260925-3d69064b4815`
- 원본 896조건 캠페인의 재시도로 계산하지 않는 진단 1회이며, 이후 추가 workload replay는 수행하지 않았다.

### 재현 절차

실제 workload 재현 결과는 위 artifact의 다음 파일에 보존돼 있다.

- `coordinator.stderr`
- `probe-receipt.json`
- `root-cause.json`
- `plan-identity-comparison.json`

소스 수준 최소 재현은 다음 회귀 테스트다.

```bash
mvn -q \
  -Dtest=org.apache.sysds.runtime.instructions.fed.BuiltinNaryFEDInstructionBroadcastTest#runtimeAnchorLiteralIpAlignsWithNativeFedInitForNary \
  test
```

수정 전에는 다음 차이로 실패했다.

```text
expected:</130.149.237.12:8001>
but was:<130.149.237.12/<unresolved>:8001>
```

### 관측 증상

진단 오류는 값 데이터를 포함하지 않고 두 placement의 메타데이터를 기록했다.

```text
base={type=FULL,dims=50000x1,ranges=[[0, 0] - [50000, 1]],workers=[/130.149.237.12:8001]}
input=_mVar74 {type=FULL,dims=50000x1,ranges=[[0, 0] - [50000, 1]],workers=[130.149.237.12/<unresolved>:8001]}
```

FType, 크기, 2차원 range, IP와 port는 모두 동일했다. 주소 객체의 resolved 상태만 달랐다.

### 원인 분석

1. commit `a6281207cf520171af47f5f8755a34cbd28ecf37`은 planner의 canonical worker identity가 DNS에 의존하지 않도록 공용 `parseAddress` 결과를 `InetSocketAddress.createUnresolved`로 변경했다.
2. `FederationUtils.buildAnchorMapFromKey`도 같은 parser를 사용하면서 unresolved 주소를 runtime `FederationMap`에 그대로 넣었다.
3. 네 호출 경로인 `FEDRefedInstruction` 두 곳과 `FEDFoutInstruction` 두 곳이 이 runtime anchor map을 사용한다.
4. native `fedinit`은 `InetAddress.getByName(host)`로 만든 resolved 주소를 사용한다.
5. nary 정렬은 `FederationMap.isAligned` 경로에서 range를 맞춘 뒤 `FederatedData.equalAddress`의 `InetSocketAddress.equals`를 호출한다. FULL map은 호환 FType 판정 때문에 ROW 정렬 분기가 먼저 선택될 수 있으므로, 특정 `AlignType.FULL` 분기가 원인이라고 가정하지 않는다. Java에서 동일 host:port라도 resolved 객체와 unresolved 객체는 같지 않다.

따라서 실제 placement 불일치가 아니라 runtime anchor producer가 잘못된 주소 표현을 내보낸 것이 원인이다.

### 해결 요약

`buildAnchorMapFromKey`에서 lexical parsing이 끝난 직후에만 다음 변환을 수행한다.

```java
isa = new InetSocketAddress(isa.getHostString(), isa.getPort());
```

이 경계는 runtime용 map을 만드는 위치다. literal IP와 runtime에서 해석 가능한 hostname은 native `fedinit`과 같은 resolved 표현이 되며, 해석할 수 없는 hostname은 기존 `InetSocketAddress` 동작대로 unresolved 상태를 유지한다. 따라서 새로운 failure timing이나 fallback은 추가하지 않는다.

planner가 호출하는 `canonicalFederatedWorkerAddress(String)`의 parser는 계속 unresolved lexical identity를 반환하므로 `.invalid` hostname에 대한 DNS-free 동작은 바뀌지 않는다.

### 수정 파일

- `src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationUtils.java`
  - runtime anchor map 생성 시에만 endpoint 표현을 native `fedinit`과 맞춤.
- `src/main/java/org/apache/sysds/runtime/instructions/fed/BuiltinNaryFEDInstruction.java`
  - 기존 진단 변경을 보존: 정렬 실패 시 값 없이 FType, 크기, range, worker 주소를 기록.
- `src/test/java/org/apache/sysds/runtime/instructions/fed/BuiltinNaryFEDInstructionBroadcastTest.java`
  - resolved native map과 runtime anchor map의 matrix/matrix/nonliteral-scalar FULL nary dispatch 회귀.
  - nonliteral scalar가 worker instruction의 literal scalar로 교체되었는지 검증.
  - 출력 FULL geometry 검증.
  - 다른 IP, port, range는 계속 실패하는 음성 회귀.
- `src/test/java/org/apache/sysds/test/component/federated/FederationUtilsRefedReuseLayoutTest.java`
  - runtime anchor hostname이 해석되지 않을 때 기존처럼 unresolved endpoint를 유지하는 회귀.

### 검증

Red baseline:

- 위 단일 회귀 테스트: **1 failure**
- 실패 이유: native resolved 주소와 rebuilt unresolved 주소가 다름.

Green 검증:

```bash
mvn -q -Dtest=org.apache.sysds.runtime.instructions.fed.BuiltinNaryFEDInstructionBroadcastTest test
```

- **6 tests, 0 failures, 0 errors**

```bash
mvn -q \
  -Dtest=org.apache.sysds.runtime.controlprogram.federated.FederationUtilsCanonicalWorkerAddressTest,org.apache.sysds.test.component.federated.FederationUtilsRefedReuseLayoutTest,org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransactionRedTest \
  test
```

- canonical no-DNS: **1 test**
- refed layout/anchor round trip 및 unknown-host 동작: **7 tests**
- placement emission/runtime anchor 계약: **14 tests**
- 합계 **22 tests, 0 failures, 0 errors**

두 명령 전체 합계는 **28 tests, 0 failures, 0 errors**다.

### 잔여 이슈

- 수정된 class를 새 stage에 넣은 실제 logreg/FedFirst E2E는 아직 실행하지 않았다. 사용자 지침에 따라 진단 이후 추가 workload replay를 금지했기 때문이다.
- 이번 수정은 runtime 정렬 false negative만 해결한다. 879.475초 planning 문제는 별도 성능 작업이다.
- 진단 phase marker 기준 planning은 공통 analysis 793.889초(약 90.27%), selector 84.577초(약 9.62%), 기타 약 1.009초였다.

### 잠재 회귀 위험

- **위험**: runtime anchor 생성 시 hostname resolution 결과가 native `fedinit`과 다르거나 runtime DNS가 일시적으로 실패할 수 있다.
  - **감지 방법**: resolved literal-IP equality 회귀와 기존 `.invalid` canonical no-DNS 회귀를 함께 유지한다.
- **위험**: 주소 정규화가 실제로 다른 worker를 같다고 취급할 수 있다.
  - **감지 방법**: 다른 IP와 port가 계속 nary 정렬에서 실패하는 음성 테스트를 유지한다.
- **위험**: 주소가 같아도 partition geometry가 다른 입력이 허용될 수 있다.
  - **감지 방법**: 다른 FULL range가 계속 실패하는 음성 테스트를 유지한다.
- **위험**: 진단 메시지에 값 데이터가 유출될 수 있다.
  - **감지 방법**: 메시지는 placement metadata만 구성하며 회귀 테스트는 matrix 값이 아닌 type/dims/ranges/workers만 확인한다.

## Exact variable-elimination production factor cap 제거 — 해결

### 상태

해결. 소스와 focused regression suite까지 통합했으며 production stage나 workload는 실행하지 않았다.

### 환경/조건

- 통합 기준 commit: `0857dcd7a7`
- 대상: Exact physical optimizer의 production variable-elimination limits
- 관측 workload factor 크기: `7,248 x 2,339 = 16,953,072` cells
- 검증 로그: `/home/mchoi/w1357-diagnostics/logreg-repair-integration-20260925`

### 재현 절차

기존 production limit에서 위 factor structure를 `ExactCategoricalSolver.analyze`에 전달하면 실제 factor 값 평가 전 다음 오류로 거부됐다.

```text
EXACT_VE_FACTOR_LIMIT_EXCEEDED
cells=16953072
limit=10000000
```

### 관측 증상

Exact planner가 표현 가능한 16,953,072-cell factor를 임의의 10,000,000-cell production cap 때문에 평가 전에 중단했다. 이 cap은 Java 자료구조로 표현할 수 없는 크기를 막는 overflow guard와 별개였다.

### 원인 분석

`ExactPhysicalOptimizer.PRODUCTION_LIMITS`가 maximum factor cells를 `10_000_000L`, maximum materialized cells를 `50_000_000L`로 고정했다. 따라서 solver의 기존 산술 overflow 검사에 도달하기 전에 workload-specific 크기 제한이 실행을 차단했다.

### 해결 요약

production limits를 `Integer.MAX_VALUE` factor cells와 `Long.MAX_VALUE` materialized cells로 올려 임의의 workload cap을 제거했다. solver 내부의 int-addressability 및 곱셈 overflow 검사는 그대로 유지한다. 따라서 `46,341 x 46,341`처럼 `Integer.MAX_VALUE`를 넘는 factor는 값을 평가하기 전에 계속 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 거부된다.

### 수정 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalOptimizer.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolverTest.java`

### 검증

다음 단일 targeted Maven suite를 실행했다.

```bash
mvn -q -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolverTest,org.apache.sysds.runtime.instructions.fed.BuiltinNaryFEDInstructionBroadcastTest,org.apache.sysds.runtime.controlprogram.federated.FederationUtilsCanonicalWorkerAddressTest,org.apache.sysds.test.component.federated.FederationUtilsRefedReuseLayoutTest,org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransactionRedTest test
```

- Exact solver: **20 tests**
- 기존 runtime-anchor/nary 회귀: **28 tests**
- 합계: **48 tests, 0 failures, 0 errors, 0 skipped**, Maven exit 0
- 명령, 실행 시간, 전체 로그와 5개 Surefire XML은 `/home/mchoi/w1357-diagnostics/logreg-repair-integration-20260925`에 보관했다.

### 의사결정 근거

runtime이나 planner candidate-space를 우회하지 않고, Exact solver의 임의 production 크기 정책만 제거했다. 표현 불가능한 factor를 차단하는 solver의 실제 안전성 제약은 보존했다.

### 잔여 이슈

- 이번 단계에서는 production package/stage를 빌드하거나 배포하지 않았다.
- 실제 Exact workload의 메모리·시간 비용은 이 단위 테스트가 보증하지 않는다.
- 수치 정확성 검증이나 workload replay를 수행하지 않았다.

### 잠재 회귀 위험

- **위험**: 이전 cap보다 큰 표현 가능한 factor가 이제 더 많은 CPU와 메모리를 소비할 수 있다.
  - **감지 방법**: 실제 campaign에서는 기존 process/container resource limit와 phase timing을 기록하고, 개별 실패는 campaign 정책에 따라 기록한다.
- **위험**: cap 제거 과정에서 overflow 보호까지 약화될 수 있다.
  - **감지 방법**: `productionLimitsStillRejectUnrepresentableFactorBeforeEvaluation`이 `EXACT_VE_FACTOR_CELL_OVERFLOW`와 평가 횟수 0을 고정한다.

## W1357 search-space 재계산 증폭 — 60초 목표 미달

- 재현: `/home/mchoi/w1357-diagnostics/search-space-60s-revised-20260925/runs/on-04/`의 실제 logreg W1/LAN search-space-only receipt. 채택 가능한 metrics ON 시간은 459.285986484초이며 60초가 아니다.
- 원인: CFG/함수/물리/출판 closure가 중첩되어 물리 base 재구성이 이미 증명된 derived binding을 버리고 다시 direct proof를 요청한다. 53 closure replay, 852 direct pass, 240,459 proof query가 남았다. 별도 결함으로 `CompiledInputEdgeFact`가 재생성되지만 값 동등성이 없어 같은 edge를 제거+추가로 오인해 가짜 dirty를 만들었다.
- 이번 수정: production privacy 필터를 유지하며 test용 evidence 생성만 기본 NONE으로 분리했다. direct dirty cone이 이전·새 사실·의존관계를 추적하고, 재생성 edge는 endpoint identity+position으로 비교한다. 완료된 outer direct frontier를 재사용하고 singleton SCC 재분해를 생략한다. 채택 JAR SHA-256은 `a00c93688c9ea8366de61a90f7db64c1f1ad7115096372603c00ec5036544f34`이다.
- 검증: 최종 영향 테스트 110건, 실패·오류 0, 기존 skip 1; Maven package 및 `git diff --check` 통과. on-04 정상 search-space-only receipt·cleanup. 전체 suite·수치 정답·896조건은 검증하지 않았다.
- 철회된 시도: 단순 물리 base 보존은 on-05에서 406.179885782초로 줄었지만 relation slot이 31,029→31,604로 바뀌어 의미 보존 미확인이다. 소스는 on-04 상태로 되돌리고 on-05 JAR/receipt를 진단 증거로 보존했다.
- 잔여 위험/다음 단계: base oracle 사실과 derived support/action/privacy를 분리하고, 삭제·새 source 등장까지 포함하는 완전한 영향 관계로 성분별 재계산해야 한다. 단순 캐시·SCC 미세 최적화나 증명 생략만으로 60초 달성을 주장하지 않는다. 상세 결과는 `/home/mchoi/cofee-evaluation/docs/COFEE_W1357_SEARCH_SPACE_60S_REVISED_EXECUTION_REPORT_2026-09-25.md`에 있다.

## W1357 구조 단순화 1차 패치 리뷰 — 철회

- **환경/조건**: 기존 on-04 JAR에서 출발하여 계획 `/home/mchoi/cofee-evaluation/.omx/plans/COFEE_W1357_SEARCH_SPACE_60S_STRUCTURAL_SIMPLIFICATION_PLAN_2026-09-25.md`의 S0/S1/S2를 순차 검토. 같은 DML bytes의 search-space-only ON 진단 `.../runs/struct-on-01/`을 완료했다. 학습/selector/896조건은 실행하지 않았다.
- **관측 증상**: 단위 회귀 114건(오류·실패 0, 기존 skip 1)과 package는 통과했지만, 독립 리뷰에서 새 물리 캐시의 latent-WDivMM 간접 문맥 누락을 발견했다. physical cache는 같은 `closePostCfgPhysicalCandidateDependencies` 호출 내부의 동일 완료 row만 재사용한다. 새 acyclic `exactRelation`도 실제 cache hit의 proof 계산에는 소비되지 않고 footprint 보관에만 사용된다.
- **원인**: 물리 cache key는 즉시 입력 domain/anchor와 current row를 포함하지만 `closeLatentWdivmmRuntimeOutputContracts`가 읽는 transitive weights-node FED/FOUT legality를 포함하지 않는다. 그 상태가 변해도 cache hit하면 normalization을 건너뛸 수 있다. 또한 이 cache는 base와 derived binding 소유권을 분리하지 않아 기존 재증명 증폭을 제거하지 못한다. acyclic DAG 저장은 기존 grounded-boundary hit 경로를 대체하지 못한다.
- **해결/변경 요약**: 새 물리 cache와 사용되지 않는 `exactRelation` 사본/관련 테스트를 철회했다. 물리 phase 자체가 on-04에서 0.926초이므로 키 확장보다 제거가 합리적이다. 기존 acyclic grounded-boundary 경로와 on-04 정책은 유지한다. 실제 병목을 줄이려면 outer closure 사이의 완료 direct/proof 결과 재사용 또는 base/derived 결과 소유권 분리가 필요하다.
- **수정 파일**: 1차 시도의 `NeutralPlacementGraphBuilder.java` 물리 row cache와 `PublicationSupportClosureTest.java` 테스트는 철회했다. `NativePlacementContinuity.java`의 새 exact DAG 사본 및 세 테스트도 제거했다. 다른 선행 수정은 보존했다.
- **검증**: 철회 전 12개 클래스 통합 targeted Maven 114건, 실패·오류 0, 기존 skip 1; package 성공. 코드 리뷰는 S1 correctness HIGH 1건, 목표 미구현 HIGH 1건, S2 불필요한 사본/비용 MEDIUM 1건, 테스트 부족 MEDIUM 1건으로 **REQUEST CHANGES**였다. `struct-on-01`은 정상 종료 및 cleanup/lease 해제를 확인했지만 Tspace **530.954250564초**로 on-04의 459.285986484초보다 느렸다. 1회 측정 차이를 패치의 순수 효과로 단정하지 않는다. proof query 240,459회와 relation slot 31,029개는 on-04와 동일했다. 철회 후 영향 회귀 63건, 실패·오류 0, 기존 skip 1 및 package 통과.
- **잔여 이슈**: 새 완료 direct frontier의 별도 ON 실측 중. 최종 새 JVM OFF 3회와 V4는 아직 미실행. `struct-on-01` 로컬 overlay JAR은 Maven이 hard link를 덮어써 한 번 변했으나 so007의 원본 SHA-256 `306494b7cf60aaba43755a6a990582f39c498bf14c573557ceaea6de4a4d5a99`에서 복원하고 target inode를 분리해 원래 provenance를 회복했다.
- **잠재 회귀 위험과 감지**: cache를 그대로 두면 latent-WDivMM에서 stale LOUT/FOUT correction이 남을 수 있다(간접 weights legality 변경 반례 필요). proof 관계 저장만 늘리면 메모리/시간이 악화될 수 있다(ON phase·allocation 비교 및 cache hit 소비 확인). **규칙 근거**: runtime 지원 후보를 줄이거나 privacy/재배치 판단을 우회하지 않으며, 테스트 전용 search space 진단도 미검증 후보를 성공으로 세지 않는다.

## W1357 바깥 closure 간 direct frontier 재사용 — 효과 미달로 철회

- **재현**: 기존 `initialPostPhysicalDirectDirty`의 before/after 비교를 바깥 `closeCfgTransientCandidateDependencies` 호출 사이에도 적용했다. 분석 단위 완료 frontier를 보관하고 정확한 CFG/origins/constraints 문맥만 재사용했다. `struct-frontier-on-01` 단일 metrics ON, 원본 DML hash와 search-only 조건을 유지했다.
- **관측**: Tspace 466.794469197초, full direct pass 115→98회, proof query 240,459→236,357회, relation slot 31,029개. status/planningStatus/spaceReady 정상, selector·runtime·workload 호출 0, cleanup·lease 해제 정상. 1회 차이로 엄밀한 성능 회귀를 단정하지는 않지만 **60초에는 명백히 미달**하며 핵심 반복 수요 감소는 약 1.7%뿐이다.
- **원인**: 호출 간 같은 완료 상태 일부만 생략했을 뿐, base/derived 사실의 이중 소유와 14개 호출 지점의 중첩된 semantic/publication/action closure는 그대로다. 이미 비슷한 문맥에서 partial memo를 더 늘려도 이 크기의 격차를 메울 근거가 없다.
- **해결**: 이 변경을 소스에서 철회했다. 기존 on-04 의미 경로를 보존하고 별도 run JAR/receipt는 진단 증거로 남겼다. 철회 후 `NativePlacementContinuityTest`, `NeutralPlacementFixedPointCompositionTest`, `DirectedDirectClosureDirtyConeTest`, `PublicationSupportClosureTest` 합계 66건, 실패·오류 0, 기존 skip 1; `git diff --check` 통과.
- **남은 위험/검증**: stage-owned base/derived 결과와 SCC별 transfer, 안정 후 publication 한 번을 구현하지 못했다. metrics OFF 새 JVM 3회 ≤60초 및 V4 플래너 소비 확인은 아직 시작하지 않았다. 상세 보고서: `/home/mchoi/cofee-evaluation/docs/COFEE_W1357_SEARCH_SPACE_60S_STRUCTURAL_EXECUTION_REPORT_2026-09-25.md`.

## W1357 S1 stage ownership — flat candidate row 설계 제약

- **증상/원인**: 현재 `CandidateReplay`는 하나의 `List<CandidateRuleFact>`에 oracle base와 direct/CFG/privacy/relocation/action 결과를 섞는다. direct는 relocation clause를 복사하고 relocation은 direct-only FED/LOUT clause도 만든다. CFG는 TRead 행 전체, privacy는 상태와 emission 목록, latent-WDivMM은 transitive legality에 따라 계약 전체를 변경한다. binding kind나 단일 clause owner는 충분한 stage provenance가 아니다.
- **검토한 해결과 기각 근거**: 물리 base side-map만 추가해 동일 descriptor에서 현재 bound 행을 보존하면 source/action 삭제 후 stale binding 위험(on-05)을 반복한다. 매번 base로 초기화하는 안전한 방법은 기존 재증명 비용을 유지한다. 공개 clause에 owner 필드를 넣으면 signature/canonical merge/receipt identity가 바뀌거나 비의미적 owner가 복사·factorization 과정에서 소실된다.
- **결과**: Builder·PlacementAnalysis로 범위를 넓혀 검토했지만, revision-capable stage transition API와 row/node/status/logical/action 전체의 계층 소유권이 없이는 작은 안전 patch가 불가능하다고 판정했다. 추측성 코드는 추가하지 않았다. 이는 문제 해결 완료나 production 우회를 뜻하지 않는다.
- **다음 작업/검증**: dependency component scheduler를 먼저 독립적으로 고정하고, cutover 시 source/action 삭제, absent→present, privacy 제외/복귀, 두 callsite, loop seed, latent-WDivMM 간접 legality의 단계별 반례를 포함한다. 전체 row layer/transfer를 실제 production 경로에 통합하기 전에는 60초 달성을 주장하지 않는다.

### S2-A 순수 dependency schedule 진행

`PlacementDependencyComponents.java`를 새로 추가해 semantic producer→consumer SCC와 별도 alias invalidation을 분리했다. 최초 리뷰에서 전체 transitive downstream을 미리 enqueue하는 O(N²) 위험과 지연 alias 누락이 발견되어, `initialDirtyComponents`와 **실제 export 변경 후** `exportChangeFrontier`(alias closure + immediate semantic successors)로 계약을 수정했다. adjacency 중복 제거도 owner별 identity set으로 바꿨다. chain/diamond/join/loop/self-loop/callsite/alias/512-chain 반례 11건 통과. 이 타입은 아직 production Builder에 연결되지 않아 search-space 시간 개선을 주장하지 않는다.

## W1357 실행 관계와 proof 이력의 혼합 — 철학 변경 계획

- **상태:** 원인 조사·계획 작성 완료, 수정 미구현. 이번 조사에서 코드 변경·새 테스트·새 성능 실행은 하지 않았다.
- **환경/재현 근거:** 실제 logreg W1/LAN, on-04 search-space-only. `/home/mchoi/w1357-diagnostics/search-space-60s-revised-20260925/runs/on-04/probe-receipt.json`에서 Tspace 459.285986484초, 최종 relation slot 31,029개, 누적 proof alternative 289,863,369개, dependency edge 401,000,706개, closure replay 53회/direct pass 852회를 확인했다. 누적 작업량과 최종 공간 크기는 다르며, 그 차이 전체가 불필요한 후보라고 단정하지 않는다.
- **문제 정의:** 같은 실행 관계의 여러 증명 경로를 후보별 graph로 반복 전개하고, 나중에 support를 합친다. 실행 불가능한 일부 조합도 product 생성 시 제외하지 않는다. 물리 cache·frontier 일부 재사용보다 경우의 수의 정의를 바꿀 필요가 있다.
- **코드로 확인한 원인:**
  1. `NativePlacementContinuity.java:803–818`의 input-position Cartesian product에는 동일 producer occurrence의 exact reference 일관성 join이 없다. 같은 X의 선택 A/B를 두 입력에서 사용하면 AB/BA가 생길 수 있지만, `ExactPhysicalModel.java:771–790`의 단일 selectedSource 제약은 이를 허용하지 않는다.
  2. `NativePlacementContinuity.java:1303–1307`은 한 clause의 동일 owner에 대한 다른 support reference를 map에 마지막 값으로 덮어쓴다. 모순 clause를 앞에서 거부하도록 바꿀 대상이다. 실제 logreg에서 이 모순이 얼마나 발생했는지는 미측정이다.
  3. `NativePlacementContinuity.java:1199–1249`는 realization의 clause마다 topology row를 만든다. private grounding에 쓰이는 exact reference·ground·완전한 dependency skeleton이 같은 경우까지 proof 설명 이력 때문에 반복 전개할 필요는 없다. 서로 다른 public source reference나 AND 의무는 합치면 안 된다.
  4. `NeutralPlacementGraphBuilder.java:3620–3673,3707–3729`는 전체 호환 seed를 순회하며 seed signature를 native lineage/public proof에 포함한다. 내부 support memo는 이미 seed-free이므로 새 cache를 발명하는 문제가 아니다. 내부 계산 공유와 public seed/root 권한을 분리해야 한다.
- **해결 계획:** 동일-occurrence consistency join → private semantic hyperedge 사전 중복 제거 → revision별 공유 obligation graph와 root-active view → 실제 export 변경에 따른 요청 전파. source/action/callsite/geometry/exactness는 유지한다. 전체 candidate row의 stage ownership 개편을 첫 선행 조건으로 두지 않는다.
- **의사결정 근거:** runtime이 지원하는 대안을 임의로 줄이지 않는다. 충돌 join의 제외는 이미 존재하는 전역 선택 합법성 제약을 앞에서 적용하는 것이고, private proof-history 통합은 외부 합법 실행 대안을 지우지 않는 표현 변경이다. PRIVATE_AGGREGATE·runtime fallback 금지·TRead/TWrite/recompile 제약을 완화하지 않는다.
- **문서 수정 파일:** `/home/mchoi/cofee-evaluation/.omx/plans/COFEE_W1357_SEARCH_SPACE_60S_EXECUTION_RELATION_PLAN_2026-09-25.md`, 직전 구조 계획·검증 계획·구조 실행 보고서의 최신 계획 링크, 이 이슈 문서. 엔진 Java 파일은 이번 조사에서 수정하지 않았다.
- **검증 방법/현재 결과:** source·receipt read-only 조사와 독립 코드 검토. 검토에서 (a) seed가 같아 보이더라도 public root/proof 권한은 유지할 것, (b) root pin에 따라 SCC가 바뀌므로 root 영향 component를 다시 계산할 것, (c) 내부 solve가 재사용돼도 provenance-only 변경은 게시하고 마지막 grounding authority가 사라지면 재계산할 것을 반영했다. public seed별 선택 identity 전체 통합은 이번 내부 관계 공유 단계의 요구가 아니다. 문서 확인은 알고리즘 회귀 통과나 60초 성능 증거가 아니다.
- **예정 회귀:** AA/BB만 허용하는 동일-owner product, 같은 reference의 여러 입력 위치 허용, 다른 alias/callsite 미병합, AND/OR 상관관계, pure self-cycle/grounded loop, root-pinned good/bad sibling, source/action 삭제와 absent→present, exact/dynamic range 구분. 수정 묶음별 작은 테스트와 ON 1회, 최종 OFF 새 JVM 3회 각각 ≤60초 및 기존 V4 연결만 수행한다. 수치 정답/896조건 검증은 추가하지 않는다.
- **잔여 이슈:** 충돌 조합과 history-only duplicate의 실제 workload 비중은 아직 없다. R1/R2만으로 60초 달성을 예측할 수 없으며, 75.519초의 closure exclusive 비용도 남을 수 있다. 기준선·미달 상태는 그대로다.
- **잠재 회귀 위험/감지:** pool이 같다는 이유로 다른 producer 권한을 합치거나, query pin을 root 첫 row에만 적용하거나, OR-of-AND를 입력별 product로 평탄화하면 불가능한 조합/잘못된 grounding이 생긴다. 위 작은 반례와 public 소비자 테스트로 확인한다. graph/index/public 변환을 selector로 넘겨 시간을 숨기는 위험은 기존 Tspace 경계 및 V4로 확인한다.

## W1357 실행 관계 계획 구현 — R1/R2 완료, R3/R4 부분 구현, 60초 미달

- **상태/환경:** 기준 계획 `/home/mchoi/cofee-evaluation/.omx/plans/COFEE_W1357_SEARCH_SPACE_60S_EXECUTION_RELATION_PLAN_2026-09-25.md`. 실제 logreg W1/LAN의 search-space-only 진단. 최종 JAR SHA-256 `1787854e444fb1b97441f2e24c362fa054842b63863fa2aa235af933f09c4418`. 896조건, selector, runtime workload, 수치 reference 검증은 실행하지 않았다.
- **증상/원인:** R1/R2는 내부 proof alternative 누적을 289,863,369→55,044,203으로 줄이고 public relation slot 31,029를 보존했지만 ON Tspace는 459.286→417.071초였다. 후속 SCC 밖 cyclic child summary와 memo·semantic revision 재사용 후에도 final ON 415.751초, **fresh JVM metrics OFF 406.735초**다. 목표 60초의 약 6.78배이며 OFF 합격 첫 시도가 실패했다. 852 direct pass와 53 closure replay는 그대로이고, 의미 projection 후에도 proof query 230,359회다. Builder의 반복 transfer 수요 자체가 남았다.
- **변경 요약:** 동일 occurrence의 상충 exact 선택 product와 clause pin을 조기 거부(R1). proof 이력만 다른 private hyperedge를 topology·root overlay에서 합치고 선언된 invalid ref의 staging fallback을 차단(R2). private row/selected proof에서 쓰이지 않는 clause field를 제거. compiled input/reaching의 보수적 occurrence SCC를 만들어 root SCC 밖 cyclic child를 grounded summary로 재사용하고, summary를 온전한 footprint 검사 후 revision으로 이전(R3 일부). `proofDependencies`만 제외하고 status/key/emission/exact realization/binding owner identity/action/native witness/layout을 포함하는 projection으로 topology/support/public memo를 revision 재사용(R4 일부). 설명만 달라진 public candidate fact는 다음 revision의 publication에서 보존된다. Public memo footprint 메모리도 bounded 예산에 포함했다.
- **수정 파일:** `NativePlacementContinuity.java`, `SearchSpaceMetrics.java`, `NativePlacementContinuityTest.java`, `NeutralPlacementFixedPointCompositionTest.java`. 진단 `initialize_run.py`, `template/prepare_overlay.py`, `test_initialize_run.py`, `README.md`도 독립 overlay inode·Docker-bind 경로 계약을 수정했다. 원래 있던 다른 dirty worktree 변경은 되돌리지 않았다.
- **검증:** 최종 영향 Java 8 suite 105 cases, failures/errors 0, 기존 skip 1; Maven package 0, `git diff --check` 0. 독립 코드 리뷰는 최종 의미 projection에 HIGH 문제 없음. ON `relation-r12-on04` 417.070854217초, `relation-r34-on05` 411.550707756초, `relation-r34-semantic-on06` 415.750729691초; 최종 OFF `relation-r34-semantic-off01` 406.735203534초. 모두 정상 search-only receipt·cleanup·lease 해제, selector/runtime/workload count 0. 각 ON은 1회라 수 초 차이를 순수 패치 효과로 단정하지 않는다. 증거와 전체 상태는 `/home/mchoi/cofee-evaluation/docs/COFEE_W1357_EXECUTION_RELATION_IMPLEMENTATION_REPORT_2026-09-25.md`에 있다.
- **미완료/위험:** R3의 공유 obligation graph가 기존 per-root ancestor graph build를 대체하지 못했고, R4의 실제 support-export delta 기반 component worklist는 Builder에 연결되지 않았다. 단순 one-hop dirty propagation은 중간 fact가 동일해도 아래 source grounding이 바뀌어 downstream proof가 바뀌는 경우 stale support를 만들 수 있으므로 적용하지 않았다. OFF 첫 시도 실패로 같은 버전의 OFF 2·3회와 V4를 성공 검증으로 반복하지 않았다. **60초 달성·전체 계획 완료를 주장하지 않는다.**
- **데이터/자원 보존:** so002 Snap Docker는 `/grid/3` bind를 거부하므로 실제 진단은 home에 유지했다. 기존 generated Maven `target/lib`와 완료된 ON overlay JAR 3개는 SHA/bytes 확인 후 `/grid/3/cofee-lm-sweep-mchoi-20260914/` 아래에 보존 이동하고 원위치 symlink를 둔 상태다. 기존 결과/데이터는 삭제하지 않았다. preflight-only 실패 run과 Docker bind/resource-gate 실패 run도 각각 증거로 보존했다.

## 원격 main 병합 기록 (동일 날짜 별도 작업)

# 2026-09-25 세션 이슈

아래 수치와 진행 상태는 이슈를 발견한 당시의 시점 기록이다. 현재 판정과 완료 증거는 `G009_PE_EXECUTION_FIRST_REPORT_2026-09-25.md` 및 저장된 612행 상태표를 따른다.

## P/E 전체 비교보다 검증 컴포넌트 개발이 선행한 실행 순서

- **상태**: 재정렬 계획의 실행 단계 진행 중. 612행 상태표 생성, 중단됐던 Pca16 실행 재개, 별도 pilot 재검사 진행.
- **문제 정의/증상**: 사용자는 실제 workload 검증이 끝나지 않고 검증 도구만 늘어나는 점을 지적했다. 저장 결과 612개 중 551개가 미완료로 남아 있었다.
- **원인 분석**: `physical-results/*/receipt.json`을 직접 집계하니 551개 모두 기본 `E_RAW_BUDGET` 사전 제한이었다. 이 중 37개는 완료한 Pca pilot의 raw 상한 이하인데, 실제 실행 우선순위와 전체 독립 acceptance/출처 감사/overlay 구축이 섞여 있었다. raw 상한 통과가 실행 가능성을 보장하는 것은 아니다.
- **해결 방법**: `.omx/plans/g009-pe-execution-first-reset-20260925.md`에 612행 상태표 → 기존 경로의 실제 비교 → 제한된 raw/독립 legality 회귀 → P2 전체 양방향 비교 → 전체 확대 순서를 기록했다. 새 overlay·범용 checker 개발은 당장 선행 조건에서 제외했다.
- **수정 파일**: 위 계획 문서와 이 세션 문서 생성, 기존 현황 보고서의 사전 종료 사유·CLI 예시·최신 계획 연결 정정.
- **검증 근거**: baseline receipt 직접 집계 61 `CAPTURED_EQUAL`, 551 `E_RAW_BUDGET`; 그 551개 중 raw ≤ 2,057,529,600은 37개, 초과 514개. Pca12 저장 재검사 12/12 `CAPTURED_EQUAL`. 이후 Pca16 run directory에서 완료 certificate/profile 8쌍을 찾았다. 현재 프로세스가 없음을 확인한 뒤 `run_current_pe_medium_pca.py run`을 같은 worklist와 artifact root에 `--resume` 경로로 재개했다. `current-pe-execution-first-20260925/current-pe-progress.tsv`의 612행은 73개 기존 재검사 완료, 15개 추가 certificate 재검사 대기, 524개 미완료를 구분한다. 15개 중 8개는 재개한 Pca16이고 나머지 7개는 별도 artifact-only 검사 중이다. 우선 raw 범위에서 완료 증거가 없는 셀은 Pca 8개와 microbench 2개다. 동결 v12 빌드의 기존 raw/P-E 대응 Java 테스트 38개, 물리 identity·cell verifier Python 테스트 17개가 모두 통과했다. 수는 서로 겹치는 셀을 제외한 실행 표의 시점별 상태다.
- **잔여 이슈**: P 전체 acceptance·P1/P2 physical image, applicability 149행, 미완료 overlay의 검토 지적은 해결되지 않았다. 단순화 계획을 전체 인증 완료로 해석하지 않는다.
- **잠재 회귀 위험/감지**: captured 비교를 full certification으로 승격하거나 작은 oracle을 모든 workload 증명으로 일반화할 위험. 기존 strict gate·claim 명칭·차집합/coverage 검사를 유지하고, 분모별 완료 상태를 따로 명시한다.
- **의사결정 근거**: planner/oracle/runtime 규칙을 완화하지 않고 실행 순서와 보고 단위를 수정한다. 아직 어떤 production 후보도 제거하거나 추가하지 않았다.

### 실행 중 추가로 확인한 경계

- 기존 Pca16의 완료된 8셀은 재개 실행기에서 다시 `CAPTURED_EQUAL`로 끝났다. 새 4셀도 각각 물리 plan 600:600, 양방향 차집합 0, 실행기의 별도 cell verifier 통과로 `CAPTURED_EQUAL`이 됐다. 나머지 4셀은 같은 worklist에서 실행 중이며, 최종 summary 및 aggregate `verify` 전에는 16/16 완료로 세지 않는다.
- 별도 pilot의 terminal certificate 7개를 기존 cell verifier로 별도 프로세스에서 재생해 7/7 `PASS`, 각 양방향 차집합 0을 확인했다. `current-pe-execution-first-20260925/pilot-replay-results.json`을 보존했다. medium 실행기 로그·certificate·profile을 대조한 per-cell 12개까지 반영해 612행 상태표는 92 `CAPTURED_EQUAL`, 520 `INCOMPLETE`다. medium 전체 aggregate 검증은 진행 중이다.
- 우선 raw 범위에 남은 microbench `cell_capture_5723bab37fbbc1a33864`를 state budget 512·E raw budget 727,833,600·7,200초 제한으로 기존 P shard 32개/E receipt에서 재개했다. 약 70분 후 기존 E factor verifier의 component 원시 열거 상한 2,000,000에서 실패했다(`microbench-reuse-k2-resume.log`). 저장 certificate는 terminal PASS가 아니고 P/E 차집합은 계산되지 않았다. 독립 factor count 경로의 `run`/`verify`는 raw 727,833,600, accepted 49,152, UNKNOWN 0, max bag 180을 재현했다(`microbench-reuse-k2-e-factor-count.json`, SHA-256 `786aba25ec6d9b1261c226dce729a836334d738620c21d710c9799d9cda05a94`). producer 출력 49,152개 ordinal을 독립 factor 관계와 연결하는 좁은 fallback을 격리 worktree에서 시험한다. 다른 `cell_capture_98da15c5d573a51c1aaf`는 E receipt가 없어 medium 작업의 JVM 상한을 고려해 대기한다.
- P2 `cell_00d1aa1ca27bce14d826`는 동결된 조건·JVM 옵션·network 환경에서 P native placement state `[0,1)`만 120초 제한 실행했다. exit 124, CPU 139.01초, 최대 RSS 1,052,860 KiB, 최종 receipt/물리 row 없음. 로그는 `current-pe-execution-first-20260925/p2-one-state/profile.log`다. 10바이트 임시 gzip 두 개가 생성되어 스트림 단계까지 도달했으나 그 파일만으로 emit 수는 알 수 없다. 이어서 같은 state 0을 짧게 재실행해 `p2-state0-stack/stack-{1,2,3}.txt`를 채집했고 세 표본 모두 `ClosedPlanRelationEnumerator.selectCandidates` 145프레임 안의 호환성·boundary 검사에 있었다. 이는 P/E 불일치나 plan 불가능의 증거가 아니라 현행 후보 열거의 비용 위치다. 후보·relocation을 포함한 P raw product는 66자리이므로 전체 예산을 단순 상향하지 않는다. 다음 한 가지 비용 감소 가설은 기존 `CandidateSelections.realizationsCanStillBeCompatible`를 P 후보 prefix 가지치기에 적용하는 것이다. 이 최적화만으로 P2 전체 양방향 비교가 끝나지는 않는다.
- 소형 fixture B-01/B-14에서 raw 전수·P 압축 열거·E 물리 집합이 같은지 기존 대응 테스트에 연결했다. 이 테스트와 독립 유한 legality 사례를 포함한 JUnit 30건이 통과했다. derived-FOUT 회귀 테스트는 hermetic privacy 등록과 정확한 realization/support clause 선택으로 현재 모델 계약에 맞췄다. derived-FOUT 테스트 자체는 생산 predicate를 재사용하므로 독립 합법성 인증은 아니다.

### 재현과 잠재 위험

- 상태표: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-execution-first-20260925/current-pe-progress.tsv`. campaign 612개 ID와 동일한 집합·유일성을 재검사했다. catalog 전체는 835행이다. 기존 612개 receipt와 명시한 pilot root의 terminal certificate/profile만 결합한 시점 기록이며 verifier의 대체물이 아니다.
- Pca16 재개: 같은 증거 루트의 `current-pe-v12-medium-pca16/run-resume-20260925.log`와 `worklist.json`. 실행 명령은 `.omx/plans/g009-pe-execution-first-reset-20260925.md`의 예산(4 cell, 2 shard, 최대 8 JVM)을 따른다. 최종 `summary.json`과 별도 `verify`가 나오기 전에는 16셀 완료로 보고하지 않는다.
- 추가 pilot 재검사: `current-pe-execution-first-20260925/pilot-replay.log`, 같은 디렉터리의 `pilot-replay-results.json`에 최종 7/7 `PASS`로 저장했다.
- **위험**: 살아 있는 runner와 별도 직접 실행이 같은 cell/artifact root를 쓰면 race·중복이 생긴다. Pca16 완료 전에는 그 8개 미완료 셀을 따로 실행하지 않는다. 현재 집계 코드는 새로운 overlay 파일을 읽거나 수정하지 않는다.

## 직전 현황 보고서의 재검사 CLI 예시 불일치

- **상태**: 예시 정정 완료. 해당 보고서의 무거운 재검사는 이번 작업에서 실행하지 않음.
- **증상/원인**: `G009_CURRENT_PE_WORK_STATUS_REPORT_2026-09-25.md`의 `--physical-result-dir`는 실제 parser에 없으며 `--artifact-root`, `--result-dir`, 동결 binding 재현 옵션이 필요하다.
- **해결 방법**: 장기 실행 보고서의 예시·실제 parser·저장 summary binding에 맞춰 경로와 병렬/예산 옵션을 정정했다. 새 계획에는 실제 지원되는 cell-level `verify` 명령을 수록했다.
- **수정 파일**: `G009_CURRENT_PE_WORK_STATUS_REPORT_2026-09-25.md`, 계획 및 이슈 기록. 기존 runner 변경 없음.
- **검증**: `verify_current_pe_matrix_parallel.py:354`, `run_current_pe_cell.py:865`의 CLI 인자 정의 직접 확인.
- **잔여 이슈/잠재 회귀 위험**: 과거 복사본의 명령은 여전히 잘못될 수 있다. 정정된 문서를 사용하고 후속 실행 전 옵션과 저장 binding을 대조한다.
- **의사결정 근거**: 저장 artifact의 정확성 문제가 아니라 문서의 명령 예시 오류다.

## 실제 셀 실행으로 확인한 추가 이슈와 종료 상태

- 작은 E raw 후보 37셀은 기존 전수·compact 비교 경로로 모두 실행·저장 재검사를 마쳤다. 전체 campaign은 98/612 `CAPTURED_EQUAL`, 514개 `E_RAW_BUDGET` 미실행이다. 완료 셀의 P-only/E-only는 모두 0이다. 현재 값과 run별 근거는 `G009_PE_EXECUTION_FIRST_REPORT_2026-09-25.md`에 있다.
- `reuse-k2`의 E factor verifier가 200만 component 열거 상한에서 멈췄다. 독립 exact count와 모든 compact ordinal의 factor 적합성·유일성·SHA를 대조하는 좁은 fallback을 추가했다. 실제 셀 전체·offline 검증이 모두 PASS, 물리 plan 8,400:8,400이다. 작은 relation의 기존 전수 경로를 유지했고 관련 Python 테스트 26개가 통과했다.
- `update-k2`의 기본 E 생산은 Java heap 4GiB에서 JSON 직렬화 OOM이 났다. 전체 stderr로 위치를 확인하고 동결 소스 변경 없이 기존 성분 열거 옵션과 24GiB heap으로 E receipt를 완료했다. 이후 셀 전체·offline 검증이 모두 PASS, 물리 plan 8,400:8,400이다.
- Pca16은 실행 summary와 별도 `verify` summary 모두 16/16 `CAPTURED_EQUAL`, 각 물리 plan 600:600이다. verifier 변경 전 certificate 96개는 Git 커밋 `c03c55246a4924b94d38b05ba970773764af07c9`의 코드 SHA에, 두 microbench는 새 코드 SHA에 묶인다. 변경 전 커밋의 임시 체크아웃으로 baseline 실제 offline PASS를 재현했다.
- **남은 차단점**: P2 한 셀의 66자리 P raw 관계와 약 109억 E accepted 관계를 현재 열거·물리 행 방식으로 전체 양방향 비교할 수 없다. prefix 병목은 stack으로 확인했지만 전체 압축 관계 비교 경로는 없다. 따라서 514셀, applicability 149행, 독립 P acceptance/feasibility와 strict gate는 미완료다. 부분 count나 단일 state 실행을 완료로 승격하지 않는다.
