# FED Planner P/L/R 감사 현재 상황 보고서

**보고 시각:** 2026-08-31 14:13 CEST  
**작업 트리:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**브랜치:** `g014/fed-runtime-space-audit-20260830`  
**원격 정책:** `so001` 제외, 보존 원격 증거는 `so003/so004/so006`만 사용

## 1. 현재 상태

5,408개 selector-visible forced-state 캠페인의 기존 무-capability 8개는 모두
compiler/lowering/runtime 결함을 수정하고 8/8 재실행에 성공했다. 이후 P와 runtime
receipt를 같은 attempt/JVM 안에서만 결합하도록 comparator를 교체했고, runtime
receipt에 다음 exact provenance를 추가했다.

```text
plannerPlanHash
plannerAnalysisFingerprint
auditContext
plannedTargetStates
plannedPhysicalStates
```

과거 전체 artifact의 36개 selected/runtime input divergence는 L2SVM ternary loop의
literal rewrite 뒤 audit operand 정렬이 깨진 instrumentation bug로 확인했다. 논리
`inputN` field 순서 fallback을 추가했으며 실제 L2SVM 재실행에서 literal-rewritten
17개를 포함한 ternary 18/18건의 planned/actual input signature가 일치했다.

상세한 이전 단계의 원인·artifact·checksum은 다음 보고서에 있다.

```text
docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_1349_KO.md
```

## 2. 이번 연속 작업: candidate builder와 독립적인 runtime dispatch 관측

Observed-success receipt만으로는 runtime-supported space `R`의 누락 상태를 찾기
어렵다. 이를 보완하기 위해 physical execution 이전의 runtime conversion frontier를
별도 schema로 기록하도록 구현했다.

```text
schema: fed-runtime-conversion-frontier-v1

frontierKind:
  DIRECT_FED
  RUNTIME_TO_FED
  FEDERATED_INPUT_NOT_CONVERTED
```

이 receipt는 다음을 기록한다.

- 원본 CP/SP/FED instruction class와 opcode
- 원본 runtime input residency/FType
- 변환 결과 instruction class와 requested federated output
- plan hash, analysis fingerprint, selected target, lowered physical state
- audit context

중요하게, conversion-frontier row는 **실행 성공 증거가 아니다**. 실제 `R` member는
기존 `fed-runtime-capability-v1`의 `SUCCESS` row만 인정한다. 새 row는 runtime
parser/dispatch가 어떤 state를 FED instruction으로 받아들이는지 candidate builder와
독립적으로 관찰하기 위한 전단계다.

## 3. L2SVM frontier smoke

```text
artifact:
  audit-results/fed-space-l2svm-frontier-v1-20260831T120224Z/

target:                     1/1 SUCCESS
runtime capability:         24/24 SUCCESS
conversion-frontier rows:   12
  DIRECT_FED:                7
  FEDERATED_INPUT_NOT_CONVERTED: 5
  RUNTIME_TO_FED:            0
```

비변환 5건은 function-call wrapper, planner prefetch, `rmvar`로서 계산 kernel의 FED
후보 누락을 뜻하지 않는다. 이들은 function boundary, local collection, variable
lifecycle instruction이므로 후속 comparator에서도 자동 Missing으로 분류하지 않고
review 항목으로 유지한다.

Artifact checksum-file SHA-256:

```text
48e25407e182fddd65d430ffbb995995b70c19321dea2c575683d74fc1b99d1a
```

## 4. FederatedMultiply: runtime conversion과 planner P의 교차 확인

`FederatedMultiplyTest`는 동일 DML을 federated compilation off/on 및 CP/SP에서
실행하므로 runtime fallback과 planner lowering을 함께 보기 적합하다. candidate와
capability/frontier recorder를 모두 켜고 8개 parameterized test를 실행했다.

```text
artifact:
  audit-results/fed-runtime-frontier-multiply-with-p-20260831T121104Z/

tests:                      8/8 PASS
candidate rows:             120
runtime capability rows:     36
conversion-frontier rows:    64
```

Context별 결과:

| context | DIRECT_FED | RUNTIME_TO_FED | FED input, not converted |
|---|---:|---:|---:|
| CP, compilation off | 4 | 2 | 4 |
| CP, compilation on | 8 | 0 | 8 |
| SP, compilation off | 7 | 3 | 6 |
| SP, compilation on | 12 | 0 | 10 |

Runtime conversion이 관측된 state:

```text
AggregateBinaryCP ba+*
  inputs: ROW, COL
  -> AggregateBinaryFEDInstruction

ReblockSP rblk
  input: ROW or COL
  -> ReblockFEDInstruction / FOUT

MapmmSP mapmm
  inputs: ROW, COL
  -> MMFEDInstruction
```

이 5건은 모두 federated compilation을 끈 legacy runtime-fallback context에서만
나왔다. federated compilation을 켠 동일 workload에서는 `RUNTIME_TO_FED=0`이고
직접 FED instruction으로 lowering됐다. 특히 `ba(+*)`의 `ROW,COL` input에 대해
candidate P는 두 parameter set의 CP/SP context 모두 다음 세 state를 공개했다.

```text
CP/LOUT/-
FED/LOUT/ROW
FED/FOUT/ROW
```

따라서 이 표적에서 runtime이 지원하는 aggregate-binary FED 경로가 planner P에서
누락됐다는 증거는 없다. 오히려 새 frontier가 legacy dynamic conversion과 COFEE
direct planning의 차이를 정확히 분리해 기록함을 검증했다.

Artifact checksum-file SHA-256:

```text
f0633492f087bbbb966d58a9a026c976a444e85af4fb86f9449c40379c168210
```

## 5. 구현 변경

추가 수정:

```text
src/main/java/org/apache/sysds/hops/fedplanner/placement/
  PlannerRuntimeCapabilityAudit.java

src/main/java/org/apache/sysds/runtime/controlprogram/
  ProgramBlock.java

src/test/java/org/apache/sysds/hops/fedplanner/placement/
  PlannerSpaceAuditTest.java

scripts/fedplanner/
  compare_forced_attempt_runtime_space.py
  test_compare_forced_attempt_runtime_space.py
```

Attempt-local comparator summary에도 frontier file/row/kind 집계를 추가했다. 다만
frontier만으로 Missing을 확정하지 않고, 정확한 P/L identity와 successful runtime
capability receipt가 모두 있을 때만 formal candidate로 승격한다.

## 6. 최신 검증

```text
PlannerRuntimePlacementAuditTest + PlannerSpaceAuditTest  PASS
Python attempt-local comparator unit test                 PASS
Python py_compile                                         PASS
git diff --check                                          PASS
mvn -q -DskipTests compile                                PASS
FederatedMultiplyTest                                     8/8 PASS
artifact internal SHA256 verification                     PASS
```

## 7. 현재 결론과 다음 단계

현재까지 확정된 결론:

1. 8개 historical no-capability target은 최신 코드에서 모두 합법적으로 실행된다.
2. 과거 36개 input divergence는 audit bug였고 수정 후 실제 반복 실행에서 0이다.
3. 새 receipt는 selected target과 concrete physical state를 혼동하지 않는다.
4. runtime dynamic conversion과 planner direct FED lowering을 독립적으로 관측할 수
   있다.
5. FederatedMultiply의 runtime-supported `ba+* ROW,COL` 경로는 planner P에도 있다.

아직 전역적으로 주장하지 않는 것:

```text
Missing=(R∩L)-P=0
Spurious=P-(R∩L)=0
```

다음 단계는 39개 `*FEDInstruction.java` 파일을 concrete execution family와
abstract/helper class로 분리한 뒤, 실제 campaign에서 관측된 opcode/input/FType별
frontier와 successful capability coverage를 생성하는 것이다. 우선순위는
`RUNTIME_TO_FED`가 존재하거나 runtime source에서 FType별 분기가 명시된 family다.
각 family는 parser/dispatch receipt만으로 통과시키지 않고 작은 실제 federated
input으로 실행한 positive/negative probe를 함께 요구한다.
