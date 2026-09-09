# AggregateBinary / AggregateUnary P-L-R 감사 보고서

작성 시각: 2026-08-31 20:59 CEST  
대상 worktree: `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
기준 commit: `0d769014d18ffb6a915b186c9bc05596710a3e24`

## 1. 결론

AggregateBinary `ba(+*)`와 AggregateUnary `ua(+R)`, `ua(+C)`, `ua(+RC)`의 직접 ROW/COL witness에 대해 다음을 확인했다.

- selector에 공개된 직접 상태 `P`: **36개**
- privacy/whole-program constraint를 만족하며 강제 선택된 상태: **36/36**
- 실제 HOP→LOP→instruction→runtime 실행 성공 상태: **36/36**
- 공개되었으나 실행 불가능한 상태(Spurious): **0개**
- 이번 실행에서 관찰된 runtime 상태 중 selector에 없는 상태(Observed Missing): **0개**
- target 누락/중복/예상 밖 결과: 모두 **0개**

따라서 이 fixture가 노출한 AggregateBinary/Unary 상태에는 family-specific rule/feasibility 버그가 입증되지 않았다. `Rulesets.java`나 runtime 구현은 수정하지 않았다.

단, runtime capability recorder는 강제 replay 중 실제로 성공한 instruction을 기록하는 positive witness이다. 따라서 `Missing=0`은 **이번 fixture가 관찰한 실행 경로에 대한 결과**이며 전체 runtime 상태의 수학적 완전 열거를 의미하지 않는다.

## 2. 감사 범위와 fixture

추가한 파일은 다음 여섯 개뿐이다.

- `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedAggregateBinaryLayoutPlanningTest.java`
- `src/test/scripts/functions/privacy/fedplanning/FederatedAggregateBinaryLayoutPlanningTest.dml`
- `src/test/scripts/functions/privacy/fedplanning/FederatedAggregateBinaryLayoutPlanningTestReference.dml`
- `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedAggregateUnaryLayoutPlanningTest.java`
- `src/test/scripts/functions/privacy/fedplanning/FederatedAggregateUnaryLayoutPlanningTest.dml`
- `src/test/scripts/functions/privacy/fedplanning/FederatedAggregateUnaryLayoutPlanningTestReference.dml`

### AggregateBinary

하나의 planner invocation에서 다음 입력/연산을 구성했다.

1. `ROW × local matrix`: `XR %*% B`
2. `COL × local vector`: `XC %*% V`
3. `local row vector × ROW`: `Left %*% XR`

이는 runtime의 ROW-left, COL-left, ROW-right 분기를 직접 실행한다. Runtime은 두 입력이 모두 local인 FED instruction을 거부하고(`AggregateBinaryFEDInstruction.java:116-123`), ROW/COL 및 operand ordering에 따라 broadcast, sliced broadcast, local aggregation, 또는 federated residency를 수행한다(`AggregateBinaryFEDInstruction.java:125-345`). Shared rule은 ROW-left의 FOUT 가능성과 COL/ROW mismatch의 local consolidation을 구분한다(`Rulesets.java:2931-2965`).

### AggregateUnary

ROW 및 COL federated matrix 각각에 대해 다음을 구성했다.

1. partition axis와 같은 방향의 aggregate
2. partition axis를 가로지르는 aggregate
3. scalar full aggregate

Runtime은 aligned axis FOUT에서 입력 mapping을 보존한다. Crossing aggregate를 강제로 federated output으로 유지할 때는 partial result를 coordinator에서 합친 뒤 `BROADCAST`로 다시 배치한다(`AggregateUnaryFEDInstruction.java:153-199`). Full aggregate는 scalar이므로 FOUT이 될 수 없다(`AggregateUnaryFEDInstruction.java:153-156`). Shared rule도 aligned axis, crossing axis, scalar full aggregate를 각각 FOUT, LOUT, LOUT로 분류한다(`Rulesets.java:2480-2506`).

## 3. P: selector-visible domain

Discovery artifact:

`audit-results/fed-runtime-aggregate-layout-e2e-v1-20260831T204737Z`

직접 manifest:

`audit-results/fed-runtime-aggregate-layout-e2e-v1-20260831T204737Z/aggregate-layout-manifest-direct.jsonl`

Manifest SHA-256:

`d195ed161e2b26c906db9e5904ea13e47a37e54a971b014da45453270d687eac`

Opcode별 상태 수:

| Opcode | P 상태 수 |
|---|---:|
| `ba(+*)` | 15 |
| `ua(+R)` | 8 |
| `ua(+C)` | 8 |
| `ua(+RC)` | 5 |
| 합계 | 36 |

Physical state별 수:

| State | 수 |
|---|---:|
| `CP/LOUT/-` | 15 |
| `CP/FOUT/BROADCAST` | 7 |
| `FED/FOUT/BROADCAST` | 4 |
| `FED/FOUT/ROW` | 2 |
| `FED/FOUT/COL` | 1 |
| `FED/LOUT/ROW` | 4 |
| `FED/LOUT/COL` | 3 |

`CP/FOUT/BROADCAST`는 CP computation 뒤 reusable federated materialization을 수행하는 synthetic physical state이며, FED kernel 자체의 output flag와 혼동하지 않았다.

## 4. L: legality

Fixture 입력 metadata는 모두 `PUBLIC`이다. 그러므로 이 범위에서 privacy는 candidate를 추가로 제거하지 않는다. 대신 강제 replay가 다음 legality를 함께 검증했다.

- exact occurrence identity와 ordered input signature 일치
- selector가 공개한 state만 강제 선택
- whole-program output/consumer consistency 충족
- HOP→LOP lowering authority 일치
- synthetic CP/FOUT materialization의 명시적 lowering

각 target의 `constraintSatisfied=true`가 이 조건을 통과했음을 나타낸다. Private/PrivateAggregation movement legality 자체는 별도 shared privacy-L 감사의 범위이며 이 보고서에서 중복 주장하지 않는다.

## 5. R: forced runtime execution

### 병렬 실행 구성

- host: `so003` (`so001`은 사용하지 않음)
- 동일 source snapshot을 네 개의 고유 staging directory로 복제
- 각 staging은 독립 Maven `target/` 사용
- disjoint shard: `0/4`, `1/4`, `2/4`, `3/4`
- 각 shard: 9 targets
- `TARGETS_PER_JVM=1`
- 총 36개의 isolated JVM replay

Campaign artifact:

`audit-results/fed-runtime-aggregate-campaign-20260831T205051Z`

네 shard 모두 다음을 만족했다.

- `expectedTargets=9`
- `resultRows=9`
- `constraintSatisfied=9`
- `failedJvmChunks=0`
- `classificationStatus=ALL_SUCCESS`

병합 결과:

- `manifestTargets=36`
- `PUBLISHED_LEGAL_EXECUTED=36`
- `SUCCESS=36`
- `unresolvedTargets=0`
- `validationStatus=PASS`
- runtime capability success rows: 208

핵심 검증 파일:

- `audit-results/fed-runtime-aggregate-campaign-20260831T205051Z/aggregate/SUMMARY.json`
- `audit-results/fed-runtime-aggregate-campaign-20260831T205051Z/aggregate/FINAL_RESULTS.jsonl`
- `audit-results/fed-runtime-aggregate-campaign-20260831T205051Z/AGGREGATE_P_L_R_VALIDATION.json`
- `audit-results/fed-runtime-aggregate-campaign-20260831T205051Z/CORE_SHA256SUMS.txt`

독립 검증 receipt:

`audit-results/parallel-campaign-independent-verification-20260831T185536Z`

독립 검증도 expected/actual 36/36, 성공 36, missing/unexpected/duplicate 0, manifest hash `d195...`, source manifest hash `6189...`를 확인했다.

## 6. Missing / Spurious 판정

### Spurious

이번 P의 36개 상태가 모두 동일 target constraint를 유지한 채 실제 runtime까지 성공했다. 따라서 이 fixture 범위에서:

`P - (R ∩ L) = ∅`

즉 Spurious는 0이다.

### Missing

Attempt-local comparator 결과:

`audit-results/fed-runtime-aggregate-campaign-20260831T205051Z/attempt-local-comparison-v1/summary.json`

- runtime success rows: 208
- exact planned target in P: 113
- unique selected/runtime input divergence: 0
- confirmed missing: 0

나머지 capability row는 auxiliary instruction 또는 exact single occurrence/input signature로 역결합할 수 없는 row이며 Missing으로 분류하지 않았다. 이 audit은 independent exhaustive R generator가 아니므로 전체 runtime space에 대한 `Missing=0` 완전성 주장은 유보한다.

## 7. 회귀 검증

Validation artifact:

`audit-results/fed-runtime-aggregate-validation-20260831T205614Z`

- Java focused tests: **7 tests, 0 failures, 0 errors, 0 skipped**
  - `RulesetsGuardTest`: 5
  - `FederatedAggregateBinaryLayoutPlanningTest`: 1
  - `FederatedAggregateUnaryLayoutPlanningTest`: 1
- Python aggregator/comparator tests: PASS
- Python bytecode compilation: PASS
- `git diff --check`: PASS

## 8. 남은 gap

1. FULL/PART/BROADCAST를 입력으로 직접 구성한 AggregateBinary/Unary exhaustive fixture는 아직 없다.
2. AggregateUnary의 `var`, `min/max`, row-index aggregate는 동일 direction abstraction을 공유하지만 이번 forced manifest는 sum opcode에 한정했다.
3. AggregateBinary의 co-located `COL_T`, single-worker FULL, two-federated-input alignment topology는 별도 fixture가 필요하다.
4. 완전한 Missing 판정을 위해서는 candidate builder와 독립적인 runtime-state generator가 필요하다.

이번 작업의 종료 조건은 direct ROW/COL aggregate domain에서 P의 모든 상태를 강제 실행하고, target-set/hash/constraint/lowering/runtime을 교차 검증하는 것이었다. 이 조건은 충족되었다.
