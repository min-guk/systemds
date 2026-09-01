# FED Planner P/L/R 감사 체크포인트 — 2026-08-31 19:12 CEST

## 1. 현재 판정

현재 감사의 대상은 selector 자체가 아니라 모든 selector가 공통으로 소비하는 물리 계획 공간이다.
occurrence `o`, ordered input signature `i`, placement state `s`에 대해 다음을 구분한다.

```text
P(o,i): shared candidate builder가 selector에 공개한 상태
L(o,i): privacy closure와 whole-program consistency를 만족하는 상태
R(o,i): HOP→LOP→FED instruction→worker 실행에 실제 성공할 수 있는 상태

Missing(o,i)  = (R(o,i) ∩ L(o,i)) - P(o,i)
Spurious(o,i) = P(o,i) - (R(o,i) ∩ L(o,i))
```

이번 체크포인트까지의 확정 결과는 다음과 같다.

1. binary 전용 fixture의 34개 published target이 모두 fresh JVM 강제 실행에 성공했다.
2. indexing 전용 fixture의 28개 published target이 모두 fresh JVM 강제 실행에 성공했다.
3. WDivMM LEFT/RIGHT 전용 direct-runtime 범위의 14개 published target이 모두 fresh JVM 강제
   실행에 성공했다.
4. WDivMM의 output residency를 shape equality로 추론하던 공유 rule이 runtime의 variant/layout
   계약과 불일치하는 결함을 발견하고 수정했다.
5. 세 범위 모두 exact occurrence와 실제 input signature가 결합된 관측에서 confirmed Missing은
   0이며, selected/actual input-signature divergence도 0이다.

이는 위 전용 fixture에서 **공개된 상태가 실행 가능함**을 증명한다. 그러나 독립적으로 완전 열거한
`R`이 아니므로 전체 FED instruction 공간에 대해 `Missing=0` 또는 `Spurious=0`이라고 아직
주장하지 않는다.

## 2. 작업 위치와 실행 정책

```text
worktree: /home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830
branch:   g014/fed-runtime-space-audit-20260830
base:     0d769014d18ffb6a915b186c9bc05596710a3e24
host:     dams-so002
```

- `so001`은 proxy 서버이므로 실행 대상에서 제외한다.
- 허용 서버는 `so002`--`so006`이다.
- 이번 WDivMM discovery/campaign은 현재 source snapshot이 있는 `so002`에서 격리 실행했다.
- 본 감사가 소유하지 않은 장기 실행 process는 종료하거나 변경하지 않았다.

## 3. 완료된 family 요약

| family | authoritative discovery | forced campaign | target | 결과 |
|---|---|---|---:|---|
| Binary | `fed-runtime-binary-layout-e2e-v3-audit-fixed-20260831T173537Z` | `fed-runtime-binary-layout-forced-v1-20260831T173845Z` | 34 | 34 `SUCCESS` |
| Indexing | `fed-runtime-indexing-layout-e2e-v2-20260831T162115Z` | `fed-runtime-indexing-layout-forced-v1-20260831T162358Z` | 28 | 28 `SUCCESS` |
| WDivMM LEFT/RIGHT | `fed-runtime-wdivmm-layout-e2e-v2-20260831T165814Z` | `fed-runtime-wdivmm-layout-forced-v1-20260831T170001Z` | 14 | 14 `SUCCESS` |

Binary와 indexing의 상세 결함 및 수정 근거는 각각 이전 체크포인트에 보존되어 있다.

```text
docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_1758_KO.md
docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_1836_KO.md
```

## 4. WDivMM fixture와 관측 범위

추가 파일:

```text
src/test/java/org/apache/sysds/test/functions/federated/fedplanning/
  FederatedWDivMMLayoutPlanningTest.java
src/test/scripts/functions/privacy/fedplanning/
  FederatedWDivMMLayoutPlanningTest.dml
  FederatedWDivMMLayoutPlanningTestReference.dml
```

Fixture는 PUBLIC `12×10` 입력 `X`, local `12×3` 입력 `U`, local `10×3` 입력 `V`를 사용한다.
`X`를 두 worker에 ROW와 COL로 각각 federate하고 다음 여섯 출력을 local reference와 비교한다.

```text
BasicRow, BasicCol,
LeftRow,  LeftCol,
RightRow, RightCol
```

현재 optimizer는 이 작은 fixture의 LEFT/RIGHT 네 식은 direct `wdivmm`으로 fusion하지만 BASIC 두
식은 다른 연산 조합으로 lower한다. 따라서 이번 direct WDivMM runtime 증거는 LEFT/RIGHT 네
occurrence에 대한 것이며, BASIC은 rule-level regression만 포함한다. 이 한계는 숨기지 않고 다음
감사 항목으로 남긴다.

## 5. Runtime ground truth

Ground truth 파일:

```text
src/main/java/org/apache/sysds/runtime/instructions/fed/
  QuaternaryWDivMMFEDInstruction.java
```

Runtime은 `X`가 ROW 또는 COL일 때만 federated WDivMM을 실행한다. 결과 residency는 단순히
입력/출력 shape가 같은지로 결정되지 않고 WDivMM variant와 `X`의 partition axis로 결정된다.

| variant | `X` FType | runtime 동작 | 결과 residency/FType |
|---|---|---|---|
| BASIC | ROW | partition-wise 계산 | `FOUT/ROW` 가능 |
| BASIC | COL | partition-wise 계산 | `FOUT/COL` 가능 |
| LEFT | ROW | worker 결과가 겹치므로 coordinator aggregation | `LOUT` |
| LEFT | COL | COL map을 transpose한 뒤 range resize | `FOUT/ROW` |
| RIGHT | ROW | non-overlapping worker 결과 | `FOUT/ROW` |
| RIGHT | COL | worker 결과가 겹치므로 coordinator aggregation | `LOUT` |

`getFederatedOutput().isForcedLocal()`은 non-overlapping 경우에도 collection을 강제할 수 있다.
반대로 overlapping LEFT/ROW와 RIGHT/COL은 FOUT 요청 여부와 무관하게 local aggregation이
필요하다.

## 6. 발견한 shared candidate-rule 결함

수정 파일:

```text
src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java
src/test/java/org/apache/sysds/hops/fedplanner/rules/RulesetsWeightedQuaternaryTest.java
```

### 6.1 수정 전 문제

기존 `WeightedDivMMRule`은 `axisPreserved(x, shapeHint)`를 사용했다. 이 규칙은 WDivMM의 실제
variant별 aggregation/transpose semantics 대신 output dimension이 `X`와 같은지를 검사했다.
그 결과:

- 합법적인 LEFT/COL `FED/FOUT/ROW`를 output column 수가 달라졌다는 이유로 숨길 수 있었다.
- 우연히 dimension이 같은 LEFT/ROW 또는 RIGHT/COL에 불법 FOUT을 공개할 수 있었다.
- `profile`이 primary input FType을 그대로 output FType으로 전달해 LEFT/COL의 `COL→ROW`
  전환과 overlapping-result local aggregation을 표현하지 못했다.

이는 selector 탐색 알고리즘의 문제가 아니라 FedAll/Heuristic/DP/Exact가 공통 소비하는 `P`의
모델링 결함이다.

### 6.2 수정 내용

- 범용 shape-equality 추론을 WDivMM rule에서 제거했다.
- BASIC은 `ROW→ROW`, `COL→COL` profile을 유지한다.
- LEFT/COL은 `FED/FOUT/ROW`를 공개한다.
- RIGHT/ROW는 `FED/FOUT/ROW`를 공개한다.
- LEFT/ROW와 RIGHT/COL은 `FED/LOUT`만 공개하고 coordinator aggregation 이유를 기록한다.
- FULL은 native FED input으로 취급하지 않되, 기존 worker pool로 materialize하는 `CP/FOUT/FULL`
  대안은 유지한다.

Selector별 예외 처리는 추가하지 않았다. 네 selector 모두 수정된 동일 `PlacementAnalysis` domain을
소비한다.

## 7. Red regression과 authoritative discovery

수정 전에 추가한 unit regression은 다음 두 불일치를 실제로 재현했다.

1. LEFT/COL에서 기대한 `FOUT/ROW` 대신 기존 rule이 FOUT을 숨겼다.
2. LEFT/ROW profile이 비어야 하지만 기존 rule이 ROW output을 광고했다.

수정 후 discovery:

```text
audit-results/fed-runtime-wdivmm-layout-e2e-v2-20260831T165814Z
```

결과:

| 항목 | 결과 |
|---|---:|
| Maven exit | 0 |
| candidate rows | 109 |
| direct WDivMM candidate occurrences | 4 |
| direct WDivMM runtime rows | 4 `SUCCESS` |
| lowering/runtime mismatch | 0 |

직접 관측된 네 runtime branch는 다음과 같다.

| variant/input | planned physical state | runtime output |
|---|---|---|
| LEFT/ROW | `FED/LOUT/ROW` | local matrix |
| LEFT/COL | `FED/FOUT/ROW` | federated ROW |
| RIGHT/ROW | `FED/FOUT/ROW` | federated ROW |
| RIGHT/COL | `FED/LOUT/COL` | local matrix |

수정 전 diagnostic discovery는 보존한다.

```text
audit-results/fed-runtime-wdivmm-layout-e2e-v1-20260831T164801Z
```

수정 전 LEFT/COL은 native `FOUT/ROW`가 누락되어 `FED/LOUT/COL`과 파생
`FOUT/BROADCAST`만 보였다. 선택되지 않은 legal state는 ordinary execution만으로 발견되지 않기
때문에 P-vs-R 독립 감사가 필요한 실제 사례다.

## 8. Forced-state campaign

Manifest:

```text
audit-results/fed-runtime-wdivmm-layout-e2e-v2-20260831T165814Z/
  wdivmm-layout-manifest.jsonl
  wdivmm-runtime-manifest.jsonl
```

Campaign:

```text
audit-results/fed-runtime-wdivmm-layout-forced-v1-20260831T170001Z
```

각 target을 별도 Surefire JVM에서 실행했다(`TARGETS_PER_JVM=1`).

| 항목 | 결과 |
|---|---:|
| expected target | 14 |
| result rows | 14 |
| constraint satisfied | 14 |
| result outcome | 14 `SUCCESS` |
| fresh JVM chunks | 14 |
| failed chunks | 0 |
| candidate rows across attempts | 1,526 |
| runtime-capability rows | 68 `SUCCESS` |
| targets with runtime receipt | 14 |
| infrastructure | `PASS` |
| classification | `ALL_SUCCESS` |

강제된 물리 상태 집합은 다음과 같다.

```text
CP/LOUT/-
CP/FOUT/BROADCAST
FED/LOUT/ROW
FED/LOUT/COL
FED/FOUT/ROW
FED/FOUT/BROADCAST
```

## 9. Attempt-local P/R join

```text
audit-results/fed-runtime-wdivmm-layout-forced-v1-20260831T170001Z/
  attempt-local-comparison/summary.json
  attempt-local-comparison/REPORT.md
```

| 분류 | rows |
|---|---:|
| runtime success | 68 |
| runtime row with planned target | 34 |
| `EXACT_PLANNED_TARGET_IN_P` | 34 |
| `NO_EXACT_ACTUAL_INPUT_SIGNATURE` | 28 |
| `NO_EXACT_SINGLE_OCCURRENCE` | 6 |
| confirmed Missing | 0 |
| selected input divergence | 0 |

마지막 두 분류는 fused/auxiliary runtime row이거나 exact single-occurrence/input signature로
귀속할 수 없는 성공이다. 이들을 Missing으로 오분류하지 않는다. `coverageComplete=false`인 이유도
이 campaign이 관측된 성공 경로이지 독립적인 exhaustive `R` 열거가 아니기 때문이다.

## 10. 회귀 및 정적 검증

동일 source snapshot에서 다음 90 Java tests가 통과했다.

| Test | pass |
|---|---:|
| `RulesetsWeightedQuaternaryTest` | 2 |
| `PlannerRuntimePlacementAuditTest` | 64 |
| `PlannerSpaceAuditTest` | 16 |
| `ExactPhysicalForcedStateAuditTest` | 5 |
| `FederatedForcedStateAuditRunnerSelectionTest` | 1 |
| `FederatedIndexingLayoutPlanningTest` | 1 |
| `FederatedWDivMMLayoutPlanningTest` | 1 |
| 합계 | 90 |

추가 검증:

- Python audit/comparator tests: 5 pass
- `python3 -m py_compile scripts/fedplanner/*.py`: PASS
- `bash -n scripts/fedplanner/*.sh`: PASS
- `git diff --check`: PASS
- source/discovery/campaign/comparison/envelope checksum verification: PASS

## 11. Evidence receipts

```text
source files:                    75
source receipt SHA-256:          05337a3d58742acd6ac85fd8aa1b5a49393200d22432fb9af9ffffff159809fb
discovery checksum SHA-256:       3c55f341d929cad35257dc13009b587e9aecbf6883d3dbc5231bab33a020eb6e
campaign checksum SHA-256:        b0dc1484df5da8bed1951c38b4f98c9407f30c995132beca90269a007ca0a1d6
comparison checksum SHA-256:      a0173480c86923a401ced21c47a94aac528539b79c5bf86b22272120c4caa92f
WDivMM envelope SHA-256:          ff4245f0107a46fae8cbab8cfe6e8aa82aa3d287f2d8dfa87e7481a0f39fa55a
```

## 12. 남은 작업과 즉시 다음 단계

1. direct BASIC WDivMM lowering을 확실히 유도하는 PUBLIC fixture 또는 기존 upstream-style
   BASIC test를 audited discovery로 실행한다.
2. BASIC/ROW와 BASIC/COL의 native `FOUT/{ROW,COL}`, 강제 `LOUT`, CP materialization 상태를
   occurrence/input-signature 단위로 manifest화하고 fresh-JVM 강제 실행한다.
3. 다음 weighted-quaternary family(WSLoss/WSigmoid)의 runtime branch와 shared rule을 같은 방식으로
   대조한다.
4. privacy class별 exclusion은 PUBLIC 성공과 분리해 PRIVATE/PRIVATE_AGGREGATION fixture에서
   `L` 증거로 검증한다.
5. 각 family에서 observed `R`와 published `P`를 대조하되, exhaustive runtime enumeration 전에는
   전역 completeness 주장을 보류한다.

즉시 이어지는 작업은 direct BASIC WDivMM witness를 확보해 이번 WDivMM 범위의 variant 공백을
닫는 것이다.
