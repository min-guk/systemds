# FED Planner P/L/R 감사 현재 상황 보고서

**보고 시각:** 2026-08-31 16:57 CEST  
**작업 트리:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**브랜치:** `g014/fed-runtime-space-audit-20260830`  
**실행 정책:** `so001` 제외. 이번 증분 검증은 로컬 `dams-so002`에서 수행했다.

## 1. 이번 체크포인트의 결론

ROW/COL `append`와 `reshape`에서 selector가 공개한 22개 candidate state를 target당 새
JVM으로 전부 강제 실행했다. 최초 실행은 append의 네 `FED/LOUT` state가 실제로
`FED/FOUT` value를 남기는 P/R 계약 위반을 발견했다. Runtime을 수정한 뒤 동일한 22개
state를 clean rerun한 결과는 다음과 같다.

```text
expected/result targets:       22 / 22
forced constraint satisfied:   22 / 22
target outcomes:               22 SUCCESS, 0 FAILURE
runtime capability receipts:   104 SUCCESS, 0 FAILURE
failed JVM chunks:             0
infrastructure status:         PASS
classification status:         ALL_SUCCESS
```

따라서 이 fixture가 관측한 P의 append/reshape state는 모두 privacy-legal forced plan으로
lowering되고 실제 runtime에서 계획된 output residency를 만족했다. 다만 이 결과는 전체
runtime space `R`의 완전 열거가 아니므로 전역 `Missing=Spurious=∅`를 의미하지 않는다.

## 2. 판정 기준

```text
P(o,i): selector에 공개된 candidate state
L(o,i): privacy 및 whole-program consistency를 통과한 legal state
R(o,i): 실제 HOP→LOP→instruction lowering 및 runtime 실행에 성공한 state

Missing(o,i)  = (R(o,i) ∩ L(o,i)) - P(o,i)
Spurious(o,i) = P(o,i) - (R(o,i) ∩ L(o,i))
```

Formal join은 같은 replay attempt의 exact occurrence, exact ordered input signature,
privacy legality, forced-state constraint 만족, successful runtime receipt가 모두 있을 때만
수행한다.

## 3. 새 layout-state fixture와 P 공간

추가한 fixture:

```text
src/test/java/org/apache/sysds/test/functions/federated/fedplanning/
  FederatedLayoutStatePlanningTest.java
src/test/scripts/functions/privacy/fedplanning/
  FederatedLayoutStatePlanningTest.dml
  FederatedLayoutStatePlanningTestReference.dml
```

Public federated ROW/COL 입력을 대상으로 다음 연산과 local reference의 수치 결과를
비교한다.

```text
rbind(ROW, ROW)               expected ROW
cbind(ROW, ROW)               expected ROW
rbind(COL, COL)               expected COL
cbind(COL, COL)               expected COL
matrix(ROW, ..., byrow=TRUE)   expected ROW
matrix(COL, ..., byrow=FALSE)  expected COL
```

Discovery artifact:

```text
audit-results/fed-runtime-layout-state-e2e-v1-20260831T141348Z
layout-manifest.jsonl SHA-256:
  76aa37a183098bf6e1747da34efed965d6939c5c7506d2e399b1f6322ebfcea8
```

6개 occurrence가 공개한 22개 target의 구성:

| State | target 수 |
|---|---:|
| CP/FOUT/BROADCAST | 6 |
| CP/LOUT/- | 6 |
| FED/FOUT/ROW | 3 |
| FED/FOUT/COL | 3 |
| FED/LOUT/ROW | 2 |
| FED/LOUT/COL | 2 |

## 4. 발견한 실제 P/R 계약 위반

최초 diagnostic campaign:

```text
audit-results/fed-runtime-layout-state-forced-v1-20260831T141722Z
```

초기 결과는 18 SUCCESS, 4 RUNTIME_FAILURE였다. 실패는 정확히 다음 네 state였다.

| operation | input | planned | 최초 observed output |
|---|---|---|---|
| cbind | ROW,ROW | FED/LOUT/ROW | FED/FOUT/ROW |
| rbind | ROW,ROW | FED/LOUT/ROW | FED/FOUT/ROW |
| rbind | COL,COL | FED/LOUT/COL | FED/FOUT/COL |
| cbind | COL,COL | FED/LOUT/COL | FED/FOUT/COL |

네 경우 모두 다음 조건은 이미 만족했다.

1. target state가 P에 존재했다.
2. privacy는 PUBLIC이었고 L/forced constraint를 통과했다.
3. instruction string에 `LOUT`가 직렬화됐다.
4. runtime ordered input signature가 planner signature와 일치했다.
5. JVM 및 worker infrastructure는 정상 동작했다.

그런데 `AppendFEDInstruction`은 `_fedOut=LOUT`를 output residency contract로 이행하지
않고 항상 federated output map을 등록했다. 따라서 문제는 selector 선택이나 privacy
filter가 아니라 runtime capability가 공개된 P state를 구현하지 않은 것이었다.

## 5. Runtime 수정

수정 파일:

```text
src/main/java/org/apache/sysds/runtime/instructions/fed/AppendFEDInstruction.java
src/test/java/org/apache/sysds/test/component/federated/AppendFoutRuntimeTest.java
```

선택한 수정은 P에서 네 state를 제거하는 것이 아니라, 이미 planner와 instruction format이
명시한 LOUT contract를 runtime에서 완성하는 것이다.

### 5.1 worker-executed append

Aligned append처럼 worker가 실제 append 결과를 만든 경로는 다음 순서로 처리한다.

```text
FED append execution
→ exact output FederationMap 생성
→ output ID GET_VAR
→ ROW/COL range에 따라 bind, FULL/BROADCAST는 한 complete result 선택
→ worker temporary cleanup
→ ExecutionContext에 local MatrixBlock 설정
```

`ExecutionContext.setMatrixOutput`은 기존 federation map을 제거하므로 instruction 종료 후
관측되는 value residency는 실제 `LOUT`다.

### 5.2 map-only append와 aliasing

ROW+rbind 및 COL+cbind는 본래 worker computation 대신 두 federation map을 range-shift와
bind로 결합한다. `rbind(X,X)`처럼 동일 worker variable이 두 output range에 나타날 수
있다. 이 aliased output map에 `GET_VAR → cleanup`을 range마다 실행하면 첫 range의
cleanup이 같은 worker의 두 번째 GET보다 먼저 실행되어 `Variable ... does not exist`가
발생한다.

따라서 LOUT map-only 경로는 output alias map을 만들지 않는다.

```text
각 distinct input map을 cleanup 없이 한 번 수집
→ coordinator에서 local append
→ local output 설정
```

입력 variable은 그대로 유지되며 정상 lifecycle cleanup의 대상이다. Output 크기는 두
입력 크기의 합이므로 이 경로의 전송량은 계획된 collection과 일치한다.

### 5.3 회귀 테스트

Component test는 다음 네 경우를 독립적으로 검증한다.

```text
ROW+cbind LOUT
ROW+rbind LOUT
COL+rbind LOUT
COL+cbind LOUT
```

각 테스트는 output federation map이 제거됐는지뿐 아니라 shape와 partition별 값 순서도
검증한다.

## 6. 수정 후 authoritative campaign

```text
audit-results/
  fed-runtime-layout-state-forced-v2-append-lout-fixed-20260831T144506Z/
```

| 항목 | 결과 |
|---|---:|
| expected / result targets | 22 / 22 |
| constraint satisfied | 22 / 22 |
| target SUCCESS / FAILURE | 22 / 0 |
| runtime capability SUCCESS / FAILURE | 104 / 0 |
| failed JVM chunks | 0 |
| classification | ALL_SUCCESS |

수정 대상 네 target의 runtime receipt는 모두 다음을 확인한다.

```text
instructionClass=AppendFEDInstruction
actualInputSignatureMethod=INSTRUCTION_OPERAND_ORDER
plannedPhysicalState=FED/LOUT/<ROW|COL>
output.federated=false
output.fType=null
outcome=SUCCESS
```

Checksum-file SHA-256:

```text
campaign SHA256SUMS.txt:
  6057364f3e984c8a5b02a0149d545815574930dc3612a28d08db57d15fbbf538
attempt-local comparison checksum file:
  35ab08ede726451f8e891d0529d4dce462f48dd9790b5b255648a8c4d7f6f001
```

## 7. Attempt-local P/R join

```text
audit-results/fed-runtime-layout-state-forced-v2-append-lout-fixed-20260831T144506Z/
  attempt-local-comparison/
```

| 분류 | 건수 |
|---|---:|
| EXACT_PLANNED_TARGET_IN_P | 54 |
| NO_EXACT_ACTUAL_INPUT_SIGNATURE | 44 |
| NO_EXACT_SINGLE_OCCURRENCE | 6 |
| unique selected/runtime input divergences | 0 |
| confirmed Missing | 0 |

`NO_EXACT_ACTUAL_INPUT_SIGNATURE`는 원래 HOP tuple과 다른 lowering auxiliary boundary를
거짓으로 join하지 않은 것이며, `NO_EXACT_SINGLE_OCCURRENCE`는 여러 occurrence가 하나의
physical instruction에 합쳐진 경우다. 둘 다 candidate omission 판정이 아니다.

`coverageComplete=false`는 의도된 보수적 판정이다. 104개 success receipt는 관측된 R
witness이지 전체 R의 독립적 완전 열거가 아니다.

## 8. 최신 curated inventory

이전 clean v7 evidence roots에 위 authoritative v2 campaign만 추가했다. 실패가 포함된 v1
diagnostic campaign과 임시 focused retest는 근거 집계에서 제외했다.

```text
audit-results/
  fed-runtime-space-inventory-v8-layout-lout-fixed-clean-20260831T145413Z/
```

| 항목 | 결과 |
|---|---:|
| FED source classes | 41 |
| concrete / abstract | 36 / 5 |
| positive-witnessed concrete families | 36 / 36 |
| runtime capability SUCCESS / FAILURE | 30,800 / 0 |
| successful runtime signature groups | 174 |
| exact actual-input rows | 28,247 |
| selected-target rows | 170 |
| candidate rows / groups | 4,152 / 474 |
| conversion-frontier rows | 746 |

Inventory checksum-file SHA-256:

```text
58c9186dc98a6fa1058e5020c360bc7b95523e38bf28cc4ea2ab7242b7fd2b77
```

## 9. 검증 결과

```text
mvn -q -DskipTests compile                                    PASS
mvn -q -DskipTests test-compile                               PASS
AppendFoutRuntimeTest                                         PASS
FederatedLayoutStatePlanningTest                              PASS
PlannerSpaceAuditTest                                         PASS
PlannerRuntimePlacementAuditTest                              PASS
22-target forced campaign                                     22/22 PASS
attempt-local comparator                                      PASS
Python aggregator/comparator/inventory unit tests              PASS
Python py_compile                                              PASS
git diff --check                                               PASS
```

Repo-wide `mvn checkstyle:check`는 이 branch의 변경과 무관한 legacy/generated source까지
기본 Checkstyle 규칙으로 검사하여 367,128개의 기존 위반을 보고하므로 clean gate로 사용할
수 없었다. 변경 코드는 compile, targeted tests, end-to-end forced campaign, whitespace
검증으로 확인했다.

## 10. 남은 범위와 다음 단계

이번 단계에서 append/reshape의 관측된 P state는 정렬됐다. 전체 P/L/R 감사의 남은 핵심은
다음과 같다.

1. binary matrix/matrix 및 matrix/vector의 ROW/COL/BROADCAST ordered input 조합
2. indexing의 layout-preserving/changing 및 scalar result 경로
3. weighted-div/quaternary variant별 output residency
4. fused instruction에서 원래 occurrence input을 독립 runtime boundary로 복원할 수 있는지
   여부
5. 위 probe마다 planner-on P, privacy legality L, forced successful R을 같은 attempt에서
   수집

현재 체크포인트는 append의 확인된 P/R contract bug를 수정·검증한 완료 지점이지만,
runtime space 전체의 exhaustive completeness 감사는 계속 진행 중이다.
