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
