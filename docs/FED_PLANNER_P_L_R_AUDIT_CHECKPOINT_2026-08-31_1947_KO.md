# FED Planner P/L/R 감사 체크포인트 — 2026-08-31 19:47 CEST

## 1. 현재 결론

이번 감사는 selector의 상대 성능이 아니라 모든 selector가 공통으로 받는 물리 계획 공간이 실제
runtime 능력과 일치하는지를 확인한다. occurrence `o`, ordered input signature `i`, placement state
`s`에 대해 다음 세 집합을 구분한다.

```text
P(o,i): shared candidate builder가 selector에 공개한 상태
L(o,i): privacy closure와 whole-program consistency를 만족하는 상태
R(o,i): HOP→LOP→instruction lowering 후 실제 runtime에서 성공한 상태

Missing(o,i)  = (R(o,i) ∩ L(o,i)) - P(o,i)
Spurious(o,i) = P(o,i) - (R(o,i) ∩ L(o,i))
```

현재까지의 확정 결과는 다음과 같다.

1. Binary 34개, Indexing 28개, WDivMM LEFT/RIGHT 14개, WDivMM BASIC 8개 published
   target을 fresh JVM에서 강제했고 총 84/84가 성공했다.
2. WDivMM LEFT/RIGHT의 output residency를 shape equality로 결정하던 shared rule 결함을
   variant/FType 기반 runtime 계약에 맞게 수정했다.
3. WDivMM BASIC runtime은 이미 `FED/LOUT`을 지원하지만 shared candidate policy가 이를 공개하지
   않는 실제 planning-space 누락을 발견했다.
4. `ExecPlacementPolicy`에 WDivMM만을 대상으로 한 forced-local capability를 추가했다. WSigmoid
   등 다른 quaternary kernel로 능력을 과도하게 확장하지 않았다.
5. 수정 후 BASIC ROW/COL occurrence는 각각 네 상태를 공개하며, 8개 상태를 모두 강제 실행했다.
6. attempt-local comparator 결과는 수정 후 관측 범위에서 `confirmedMissing=0`, selected/actual
   input-signature divergence `0`이다.
7. Java 93개 및 Python 5개 회귀시험, Python compile, shell syntax, `git diff --check`가 모두
   통과했다.

단, 현재 `R`은 runtime 전체를 독립적으로 완전 열거한 집합이 아니라 fixture에서 관측하고 강제한
성공 상태의 집합이다. 따라서 전체 FED instruction universe에 대해 `Missing=0` 또는
`Spurious=0`이라고 아직 주장하지 않는다.

## 2. 작업 위치와 실행 정책

```text
worktree: /home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830
branch:   g014/fed-runtime-space-audit-20260830
base:     0d769014d18ffb6a915b186c9bc05596710a3e24
host:     dams-so002
```

- `so001`은 proxy이므로 사용하지 않는다.
- 허용 범위는 `so002`--`so006`이다.
- 이번 BASIC WDivMM discovery와 forced campaign은 현재 source snapshot이 있는 `so002`에서
  실행했다.
- 이 감사가 소유하지 않은 장기 실행 process는 종료하거나 변경하지 않았다.

## 3. 완료된 family 현황

| family | authoritative discovery | forced campaign | 결과 |
|---|---|---|---:|
| Binary | `fed-runtime-binary-layout-e2e-v3-audit-fixed-20260831T173537Z` | `fed-runtime-binary-layout-forced-v1-20260831T173845Z` | 34/34 |
| Indexing | `fed-runtime-indexing-layout-e2e-v2-20260831T162115Z` | `fed-runtime-indexing-layout-forced-v1-20260831T162358Z` | 28/28 |
| WDivMM LEFT/RIGHT | `fed-runtime-wdivmm-layout-e2e-v2-20260831T165814Z` | `fed-runtime-wdivmm-layout-forced-v1-20260831T170001Z` | 14/14 |
| WDivMM BASIC | `fed-runtime-wdivmm-basic-layout-e2e-v2-20260831T193216Z` | `fed-runtime-wdivmm-basic-layout-forced-v2-20260831T193329Z` | 8/8 |

합계는 84/84 published target success이다. 이 수는 family별 fixture와 해당 occurrence의 공개 상태를
강제한 결과이며 runtime 전체 상태 수를 뜻하지 않는다.

## 4. BASIC WDivMM direct-runtime fixture

추가 파일:

```text
src/test/java/org/apache/sysds/test/functions/federated/fedplanning/
  FederatedWDivMMBasicLayoutPlanningTest.java
src/test/scripts/functions/privacy/fedplanning/
  FederatedWDivMMBasicLayoutPlanningTest.dml
  FederatedWDivMMBasicLayoutPlanningTestReference.dml
```

Fixture는 PUBLIC 행렬을 사용한다.

```text
X: 12 × 1002
U: 12 × 3
V: 1002 × 3
block size: 1000
federation: two-worker ROW and two-worker COL
```

열 수를 block size보다 크게 만든 이유는 optimizer의 BASIC WDivMM fusion 조건을 만족시켜 단순한
연산 조합이 아니라 direct `QuaternaryWDivMMFEDInstruction`을 생성하기 위해서다. ROW/COL 결과는
동일 입력의 local reference와 비교한다. 기본 실행에서 `fed_wdivmm` 두 건이 관측되었고 결과 비교도
통과했다.

## 5. 발견한 shared planning-space 누락

수정 전 authoritative 후보 관측은 다음과 같았다.

| input | 수정 전 `P(o,i)` |
|---|---|
| ROW | `CP/LOUT`, `FED/FOUT/ROW`, `CP/FOUT/ROW` |
| COL | `CP/LOUT`, `FED/FOUT/COL`, `CP/FOUT/COL` |

그러나 runtime 구현은 WDivMM의 non-overlapping BASIC 결과에 대해 serialized placement가
forced-local이면 worker 결과를 coordinator에 bind하여 local matrix를 만든다.

```text
QuaternaryWDivMMFEDInstruction.java:120--122
  requiresLocalMaterialization = requiresLocalAggregation
      || getFederatedOutput().isForcedLocal()

QuaternaryWDivMMFEDInstruction.java:210--215
  forced-local BASIC result를 FederationUtils.bind(...)로 local output에 기록
```

공통 builder도 native `FED/FOUT` 후보에서 `FED/LOUT` 파생 후보를 만들 수 있었지만 다음 gate를
통과한 HOP에만 이를 공개했다.

```text
NeutralPlacementGraphBuilder.java:3502--3508
  ExecPlacementPolicy.supportsForcedLocalFederatedOutput(hop)
```

해당 policy는 Indexing, transpose, Binary, AggBinary만 포함하고 WDivMM을 누락하고 있었다. 즉
runtime kernel이 구현하고 PUBLIC privacy에서 합법적인 `FED/LOUT/{ROW,COL}` 두 상태를 selector
앞의 shared space가 숨겼다. 이는 selector 철학의 문제가 아니라 모든 selector가 공유한 candidate
construction 결함이다.

수정 전 source의 `P`와 변경되지 않은 runtime 계약, 수정 후 exact forced-runtime witness를 결합하면
이 두 상태는 source-grounded missing-state defect이다. 다만 동일 snapshot의 comparator가 강제할
수 없던 상태였으므로 보고서에서는 이 cross-snapshot 인과 증거와 수정 후 same-snapshot comparator
결과를 구분한다.

## 6. 구현한 수정

### 6.1 Forced-local capability 공개

변경 파일:

```text
src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/
  ExecPlacementPolicy.java
```

`supportsForcedLocalFederatedOutput`에 다음의 좁은 capability를 추가했다.

```java
hop instanceof QuaternaryOp
    && ((QuaternaryOp) hop).getOp() == Types.OpOp4.WDIVMM
```

모든 quaternary 연산을 허용하지 않고 runtime contract가 직접 확인된 WDivMM만 허용한다.

### 6.2 Red/green regression

추가 파일:

```text
src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/
  ExecPlacementPolicyForcedLocalTest.java
```

테스트는 수정 전 WDivMM assertion에서 실패했다. 수정 후에는 다음을 동시에 보장한다.

- WDivMM: forced-local capability `true`
- WSigmoid: capability `false`

기존 `FederatedPlannerFallbackIntegrationTest`의 WDivMM local-aggregation expectation도 이전
shape-equality 결함 수정 후의 `ReasonCode.OK` 계약에 맞게 갱신했다.

## 7. 수정 후 공개 후보 공간

수정 후 direct BASIC occurrence의 `P(o,i)`는 다음과 같다.

| input | 수정 후 `P(o,i)` |
|---|---|
| ROW | `CP/LOUT`, `FED/FOUT/ROW`, `FED/LOUT/ROW`, `CP/FOUT/ROW` |
| COL | `CP/LOUT`, `FED/FOUT/COL`, `FED/LOUT/COL`, `CP/FOUT/COL` |

즉 임의 Cartesian product를 추가한 것이 아니라 native layout별로 runtime이 실제 지원하는
forced-local residency 하나씩만 추가했다. COL 입력에 ROW 출력 같은 근거 없는 cross-layout
후보는 공개하지 않는다.

Discovery 산출물:

```text
audit-results/fed-runtime-wdivmm-basic-layout-e2e-v2-20260831T193216Z/
  discovery/candidate-space-2284342.jsonl
  discovery/runtime-capability-2284342.jsonl
  discovery/runtime-conversion-frontier-2284342.jsonl
  basic-layout-manifest-all.jsonl
  basic-wdivmm-runtime-manifest.jsonl
  DISCOVERY_SHA256SUMS.txt
```

## 8. Forced-state campaign 결과

```text
audit-results/fed-runtime-wdivmm-basic-layout-forced-v2-20260831T193329Z/
```

| 항목 | 결과 |
|---|---:|
| expected target | 8 |
| result row | 8 |
| constraint satisfied | 8 |
| fresh JVM chunk | 8 |
| failed JVM chunk | 0 |
| candidate rows | 608 |
| runtime capability rows | 38 `SUCCESS` |
| targets with runtime receipt | 8 |
| infrastructure | `PASS` |
| classification | `ALL_SUCCESS` |

강제한 상태는 다음 여덟 개다.

```text
ROW: CP/LOUT, CP/FOUT/ROW, FED/FOUT/ROW, FED/LOUT/ROW
COL: CP/LOUT, CP/FOUT/COL, FED/FOUT/COL, FED/LOUT/COL
```

CP 상태는 forced-state constraint와 end-to-end result check로 검증했다. FED 상태는 WDivMM runtime
receipt도 직접 확인했다.

| forced state | actual input | runtime output | result residency |
|---|---|---|---|
| `FED/FOUT/ROW` | ROW | `FOUT` | federated ROW |
| `FED/LOUT/ROW` | ROW | `LOUT` | local |
| `FED/FOUT/COL` | COL | `FOUT` | federated COL |
| `FED/LOUT/COL` | COL | `LOUT` | local |

행 단위 증거:

```text
audit-results/fed-runtime-wdivmm-basic-layout-forced-v2-20260831T193329Z/
  WDIVMM_BASIC_RUNTIME_STATE_VALIDATION.json
```

## 9. Attempt-local P/R join

```text
audit-results/fed-runtime-wdivmm-basic-layout-forced-v2-20260831T193329Z/
  attempt-local-comparison/summary.json
  attempt-local-comparison/REPORT.md
```

| 항목 | 결과 |
|---|---:|
| successful runtime rows | 38 |
| runtime rows with planned target | 20 |
| `EXACT_PLANNED_TARGET_IN_P` | 20 |
| `NO_EXACT_ACTUAL_INPUT_SIGNATURE` | 16 |
| `NO_EXACT_SINGLE_OCCURRENCE` | 2 |
| confirmed Missing | 0 |
| selected input divergence | 0 |
| coverage complete | false |

`NO_EXACT_*` 행은 init/reblock 같은 auxiliary instruction 또는 한 planner occurrence에 정확히 귀속할
수 없는 runtime 행이다. 성공했다는 이유만으로 Missing으로 오분류하지 않는다.

## 10. 회귀 및 정적 검증

검증 산출물:

```text
audit-results/fed-runtime-wdivmm-basic-layout-validation-20260831T174110Z/
```

| Java test class | pass |
|---|---:|
| `RulesetsWeightedQuaternaryTest` | 2 |
| `ExecPlacementPolicyForcedLocalTest` | 1 |
| `PlannerRuntimePlacementAuditTest` | 64 |
| `PlannerSpaceAuditTest` | 16 |
| `ExactPhysicalForcedStateAuditTest` | 5 |
| `FederatedForcedStateAuditRunnerSelectionTest` | 1 |
| `FederatedIndexingLayoutPlanningTest` | 1 |
| `FederatedWDivMMLayoutPlanningTest` | 1 |
| `FederatedWDivMMBasicLayoutPlanningTest` | 1 |
| `FederatedPlannerFallbackIntegrationTest` targeted method | 1 |
| **합계** | **93** |

검증 결과:

- Java: 93 tests, 0 failures, 0 errors, 0 skipped
- Python audit/comparator: 5 tests, 0 failures
- `python3 -m py_compile scripts/fedplanner/*.py`: PASS
- `bash -n scripts/fedplanner/*.sh`: PASS
- `git diff --check`: PASS
- campaign runner 원본 `SHA256SUMS.txt`: `sha256sum -c` PASS

## 11. Evidence receipts

```text
source files bound:              85
source receipt SHA-256:          942f7402b8a320ca5998babb8ae8a85210f4cb9945d229cd325f0d233becb6a6
discovery checksum SHA-256:       8f10032174c41a7a292b7dbaa606b4f627a02fb2363ee810d6877afc126682f2
campaign checksum SHA-256:        3204dc90efcc9b9d2ed4a693346f99b588da2eeb3d5c3312a74b325b08e4304e
comparison checksum SHA-256:      fd626b04350246ffd8a00350a226ceb8b86cebabbc7dc39f5119f9742c536bfa
validation checksum SHA-256:      1e9673f60304bb3775bd4e2fb84b024dbf4c13ea8e04dffb7be11dc8b6318fba
BASIC evidence envelope SHA-256:  fc8b5168dd857f01645d293dcd17fcb3ff659ea86e90144c177185fdcb495ec4
```

Source receipt는 현재 수정·추가된 audit source와 WDivMM HOP/LOP/runtime ground-truth 파일을 함께
고정한다. base commit에 있는 나머지 파일은 commit hash로 식별한다.

## 12. 현재 한계

1. 현재 comparator는 관측된 successful FED instruction을 `R` witness로 사용하며 runtime의 모든
   입력 조합과 상태를 생성하는 독립 exhaustive enumerator는 아니다.
2. PUBLIC fixture는 physical executability를 검증하지만 PRIVATE/PRIVATE_AGGREGATION exclusion을
   대신하지 않는다. privacy별 `L` 검증은 별도 fixture로 수행해야 한다.
3. WDivMM에 대해 BASIC/LEFT/RIGHT의 핵심 layout/residency branch는 닫았지만 WSLoss/WSigmoid 등
   다른 weighted-quaternary kernel은 아직 같은 수준의 direct-runtime coverage가 없다.
4. published target 성공은 해당 `P` 상태가 실행 가능하다는 강한 증거지만, 공개되지 않은 모든
   runtime 상태가 없다는 전역 completeness 증명은 아니다.

## 13. 이어지는 작업

다음 순서로 계속 진행한다.

1. WSLoss와 WSigmoid의 HOP rule, LOP lowering, runtime instruction branch를 정적으로 대조한다.
2. runtime이 지원하지만 candidate policy가 숨기는 output/layout 상태가 있는지 좁은 direct fixture로
   확인한다.
3. 후보가 확인되면 discovery → exact manifest → fresh-JVM forced campaign → attempt-local comparator
   순서로 동일하게 검증한다.
4. weighted-quaternary family 후에는 PRIVATE와 PRIVATE_AGGREGATION 입력을 사용해 공통 privacy
   closure가 불법 CP/FOUT 또는 data movement 상태를 selector 전에 제거하는지 `L` 관점에서
   검증한다.
5. 각 단계에서 source/discovery/campaign/validation checksum을 새로 고정하며, exhaustive 근거가
   생기기 전까지 전역 `Missing=0` 주장은 보류한다.

즉시 다음 작업은 WSLoss/WSigmoid runtime 계약과 shared candidate rule의 차이를 매핑하는 것이다.
