# Reorg (`r'`, `rdiag`) FED Planner P/L/R 감사 및 runtime 정합성 수정

**작성일:** 2026-08-31  
**대상:** transpose와 `rdiag`의 ROW/COL/FULL/BROADCAST 입력, FED/CP 및 LOUT/FOUT 상태  
**원격 실행:** `dams-so004` (so001 미사용)  
**최종 authoritative artifact:** `audit-results/fed-runtime-reorg-layout-forced-authoritative-20260831T191727Z`

## 1. 감사 기준과 범위

각 occurrence와 실제 입력 layout signature에 대해 다음 집합을 구분했다.

- **P:** privacy filtering 뒤 selector에 공개된 physical candidate state
- **L:** privacy 및 whole-program consistency를 만족하는 state
- **R:** HOP→LOP→instruction→runtime을 끝까지 성공한 state

fixture의 입력 metadata는 모두 `PUBLIC`이므로 privacy가 P의 state를 추가 제거하지 않는다. forced-state runner는 P의 각 direct state를 실제 선택하고, whole-program constraint 충족 여부와 runtime 실행 결과를 같은 attempt에서 기록했다. 따라서 최종 47개 target은 모두 `PUBLISHED_LEGAL_EXECUTED`, 즉 이 fixture에서 관찰된 `P ⊆ L ∩ R`이다.

단, 이 감사는 runtime이 이론적으로 지원할 수 있는 모든 비공개 state를 독립 Cartesian product로 생성하지 않는다. 따라서 **P 밖의 R을 완전 열거했다는 주장이나 전역적인 `Missing=∅` 주장은 하지 않는다.** attempt-local comparator도 `coverageComplete=false`를 명시한다.

## 2. 전용 fixture

추가한 `FederatedReorgLayoutPlanningTest`는 다음 concrete mapping을 실제 federated workers에 구성한다.

- ROW: 서로 다른 row range
- COL: 서로 다른 column range
- FULL: worker 하나의 전체 range
- BROADCAST: 서로 다른 worker에 동일한 전체 range 두 개

검증 연산은 다음과 같다.

| 연산 | ROW | COL | FULL | BROADCAST |
|---|---:|---:|---:|---:|
| transpose | 지원 | 지원 | 지원 | 지원 |
| `rdiag` matrix→vector | 지원 | 지원, 결과 ROW | 지원 | 지원 |
| `rdiag` vector→matrix | 지원 | 해당 없음 | 지원 | 지원 |

비어 있지 않은 `n×1` vector는 column 축으로 둘 이상의 서로 겹치지 않는 일반 COL partition을 만들 수 없으므로 COL vector→matrix fixture는 정의하지 않았다. 이는 후보 누락이 아니라 입력 representation의 기하학적 제약이다.

fixture는 11개 결과를 local reference와 수치 비교하며 기본 FOUT 실행에서 `fed_r'` 4회와 `fed_rdiag` 7회를 관찰한다.

## 3. 발견한 구현 불일치와 수정

### 3.1 BROADCAST `rdiag`의 range identity 손실

기존 `rdiagV2M`/`rdiagM2V`는 worker 응답 shape를 `HashMap<FederatedRange,...>`에 저장했다. BROADCAST는 값이 같은 range 객체를 worker별로 가지므로 equality 기반 map에서 entry가 합쳐졌고, 이후 range mutation 뒤 lookup도 불안정했다.

수정:

- worker별 concrete range object identity를 유지하도록 `IdentityHashMap` 사용
- BROADCAST의 동일 range 두 개를 각각 보존

### 3.2 COL matrix→vector의 잘못된 output mapping

기존 `updateFedRanges`는 입력 COL range의 column offset을 결과 vector에도 재사용했다. 하지만 각 worker의 local diagonal 결과는 column vector이며, global 결과는 worker 결과를 **row 방향으로 이어 붙인 ROW mapping**이어야 한다.

수정:

- ROW/COL axis input의 local result를 누적 row offset으로 배치
- COL `rdiag` output FType을 ROW로 변경
- FULL은 FULL, BROADCAST는 동일 full range와 BROADCAST를 유지
- shared `ReorgUnaryRule.profile/caps`도 COL `rdiag`→ROW로 정렬

### 3.3 `rdiag` LOUT가 instruction에 전달되지 않음

`Transform.getInstructions`는 transpose/sort/reshape에는 federated output flag를 직렬화했지만 DIAG에는 하지 않았다. 따라서 planner가 LOUT를 선택해도 runtime instruction이 이를 받지 못했다.

수정:

- FED DIAG instruction 끝에 `_fedOutput.name()` 직렬화
- `ExecPlacementPolicy.supportsForcedLocalFederatedOutput`에 DIAG 추가

### 3.4 `optionalForceLocal`은 residency를 local로 바꾸지 않음

첫 47-target 원격 campaign에서 40개는 성공했지만 `rdiag` FED/LOUT 7개가 모두 실패했다. instruction 문자열은 분명 `...°LOUT`였으나, 기존 경로는 FOUT mapping을 출력 MatrixObject에 먼저 붙인 뒤 `acquireReadAndRelease()`와 cleanup만 수행했다. MatrixObject의 federated mapping은 남아 있었으므로 post-instruction runtime audit가 정확히 `actual=FED/FOUT/{ROW,FULL,BROADCAST}`를 관찰했다. 이는 audit 오분류가 아니라 LOUT 구현의 residency semantic bug였다.

수정:

- LOUT이면 worker result ID를 `GET_VAR`하고 즉시 cleanup
- ROW/FULL은 실제 partition 결과를 bind
- BROADCAST는 중복 복제를 bind하지 않고 첫 replica를 사용
- `ExecutionContext.setMatrixOutput`으로 local MatrixBlock을 등록하고 federated mapping을 출력에 부착하지 않음

수정 뒤 로컬 BROADCAST LOUT canary와 원격 실패 7개 재실행이 모두 성공했다. 재실행에는 matrix→vector뿐 아니라 `DVRow`, `DVFull`, `DVBroadcast` vector→matrix target도 포함한다.

## 4. P/L/R 결과

### 4.1 Discovery

- 전체 candidate rows: 175
- direct reorg target: 47
  - transpose: 17
  - `rdiag`: 30
- direct state에는 CP/FOUT, CP/LOUT, FED/FOUT, FED/LOUT가 포함된다.
- concrete FED output은 다음 규칙과 일치했다.
  - transpose: ROW→COL, COL→ROW, FULL→FULL, BROADCAST→BROADCAST
  - `rdiag`: ROW→ROW, COL matrix→ROW vector, FULL→FULL, BROADCAST→BROADCAST

Discovery artifact:

`audit-results/fed-runtime-reorg-layout-discovery-20260831T210131Z`

### 4.2 Forced execution

초기 so004 4-way shard:

- expected/result targets: 47/47
- constraint satisfied: 47
- SUCCESS: 40
- `rdiag` FED/LOUT residency failure: 7
- failed JVM chunks: 0
- infrastructure: PASS

수정 후 실패 target만 4-way 재실행:

- expected/result targets: 7/7
- constraint satisfied: 7
- SUCCESS: 7
- runtime capability SUCCESS rows: 105
- failed JVM chunks: 0
- infrastructure: PASS

Authoritative aggregation:

- final SUCCESS: **47/47**
- `PUBLISHED_LEGAL_EXECUTED`: **47/47**
- authoritative runtime capability SUCCESS: **724**
- unresolved targets: **0**
- validation status: **PASS**

실패 이력을 포함한 primary artifact는 보존했고, aggregator가 수정 전 40개 성공과 수정 후 7개 isolated retry를 target ID별 authoritative result로 결합했다.

### 4.3 Missing/Spurious 판정

- 관찰된 published target 중 runtime 불가능한 최종 state: **0**
- 수정 후 7개 attempt-local P/R join의 confirmed Missing witness: **0**
- selected runtime input divergence: **0**
- 전역 R coverage: **불완전** (`coverageComplete=false`)

즉 이번 fixture와 공개된 47개 direct state에 대해서는 Spurious가 남지 않았다. 다만 P 밖의 모든 가능한 R을 독립 생성하지 않았으므로 전역 Missing 부재를 과장하지 않는다.

## 5. 검증

### 로컬 회귀

| Test class | tests | 결과 |
|---|---:|---|
| `FederatedReorgLayoutPlanningTest` | 1 | PASS |
| `RulesetsReorgTest` | 3 | PASS |
| `ReorgFEDInstructionFullTest` | 4 | PASS |
| `FederatedRefedPolicyReorgCapabilityTest` | 1 | PASS |
| `FederatedRdiagTest` | 8 | PASS |

총 **17 tests**, failures/errors/skips = 0/0/0.

추가 forced canary:

- BROADCAST `rdiag` FED/LOUT: 1/1 SUCCESS
- runtime capability: 15/15 SUCCESS
- artifact pointer: `audit-results/.latest-reorg-layout-local-lout-canary`

### 원격 receipt와 checksum

- primary: `audit-results/fed-runtime-reorg-layout-forced-so004-20260831T191145Z`
- failed-only retry: `audit-results/fed-runtime-reorg-layout-forced-so004-retry-20260831T191727Z`
- authoritative: `audit-results/fed-runtime-reorg-layout-forced-authoritative-20260831T191727Z`
- source receipt: 각 campaign `RUN_MANIFEST.txt`의 `sourceManifestSha256`
- artifact별 `SHA256SUMS.txt` 및 authoritative aggregate checksum 생성

so004 source snapshot은 campaign 시작 전 remote에서 다시 계산하고 검증했으며, so001은 사용하지 않았다.

## 6. 수정 파일

- `src/main/java/org/apache/sysds/runtime/instructions/fed/ReorgFEDInstruction.java`
- `src/main/java/org/apache/sysds/lops/Transform.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java` (reorg rule 부분)
- `src/test/java/org/apache/sysds/test/functions/fedplanner/rules/RulesetsReorgTest.java`
- `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedReorgLayoutPlanningTest.java`
- `src/test/scripts/functions/privacy/fedplanning/FederatedReorgLayoutPlanningTest.dml`
- `src/test/scripts/functions/privacy/fedplanning/FederatedReorgLayoutPlanningTestReference.dml`

## 7. 최종 판정

transpose와 `rdiag`에 대해 selector 공개 state, shared rule, LOP serialization, runtime output mapping/residency가 동일한 의미를 갖도록 정렬했다. 특히 BROADCAST identity, COL `rdiag`의 ROW 결과, DIAG LOUT 직렬화 및 실제 local materialization을 고쳤다. 공개된 47개 direct reorg state는 모두 privacy/whole-program constraint를 만족한 채 실제 runtime에서 성공했으며, 미해결 target은 없다.
