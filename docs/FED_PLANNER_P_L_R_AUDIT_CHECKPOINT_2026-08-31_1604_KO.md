# FED Planner P/L/R 감사 현재 상황 보고서

**보고 시각:** 2026-08-31 16:04 CEST  
**작업 트리:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**브랜치:** `g014/fed-runtime-space-audit-20260830`  
**실행 정책:** `so001` 제외. 이번 증분 검증은 로컬 `dams-so002`에서 수행했다.

## 1. 목표와 현재 판정 경계

감사 대상은 occurrence `o`, ordered input signature `i`, placement state `s`별
다음 세 공간이다.

```text
P(o,i): selector에 공개된 candidate state
L(o,i): privacy와 whole-program consistency를 통과한 legal state
R(o,i): 실제 lowering과 FED instruction 실행에 성공한 state

Missing(o,i)  = (R(o,i) ∩ L(o,i)) - P(o,i)
Spurious(o,i) = P(o,i) - (R(o,i) ∩ L(o,i))
```

Formal Missing/Spurious 판정에는 같은 attempt의 exact occurrence, exact ordered input,
privacy legality, successful runtime receipt를 모두 요구한다. 아래 결과는 관측된 state에
대한 강한 증거지만, 아직 전체 `R`의 완전 열거를 증명하지 않으므로 전역
`Missing=Spurious=∅`를 주장하지 않는다.

## 2. 이번 단계에서 해결한 감사 오류

### 2.1 named parameter의 논리 입력 순서

`ParameterizedBuiltinOp`의 HOP input 순서와 physical instruction 내부 `HashMap` 순서는
같다고 가정할 수 없다. 따라서 runtime map iteration 순서를 HOP signature와 직접
비교하면 `target`, `margin`, `select`의 identity가 뒤섞인다.

수정 후에는 planner가 occurrence별 parameter role을 보존하고, runtime operand를
`target`, `margin`, `empty.return` 등의 role로 재정렬한다. 정확한 매칭 receipt는
`actualInputSignatureMethod=PARAMETER_ROLE_ORDER`를 기록한다.

### 2.2 lowering helper와 원래 HOP 입력 경계의 분리

`fedinit` 뒤의 `rblk`는 원래 `DataOp` candidate와 별개의 physical data-flow boundary다.
원래 HOP은 세 입력을 갖지만 `ReblockFEDInstruction`은 이미 생성된 federated value 한
개를 입력으로 받는다. 이는 누락 operand나 runtime/planner divergence가 아니다.

`PlannerRuntimeCapabilityAudit`는 이제 다음 metadata를 receipt에 보존한다.

```text
plannerSyntheticActionKey
plannerLoweringAuxiliaryKind
plannerRewriteReplacementKind
```

`plannerLoweringAuxiliaryKind`가 설정된 physical helper는 성공한 `R` witness로는
계속 보존하되, 원래 HOP tuple과 거짓으로 exact join하지 않고 다음 상태로 분류한다.

```text
UNAVAILABLE_LOWERING_AUXILIARY_INPUT_BOUNDARY
```

구현 위치:

- `PlannerRuntimeCapabilityAudit.java:84-130`
- `PlannerRuntimeCapabilityAudit.java:327-359`
- `Hop.java:574-597`
- 회귀 테스트: `PlannerSpaceAuditTest.java:268-279`

Rewrite replacement는 배제하지 않았다. replacement가 원래 occurrence의 primary physical
instruction인 경우에는 exact input mapping이 가능하기 때문이다.

## 3. ROW/COL `rmempty`의 같은-attempt P/R 검증

새 planner-enabled fixture는 public federated metadata로 다음 두 경우를 실행한다.

```text
ROW input + removeEmpty(..., margin="rows")
COL input + removeEmpty(..., margin="cols")
```

각 input signature에서 P는 다음 두 상태를 공개한다.

```text
CP/LOUT/-
FED/FOUT/<input FType>
```

네 상태를 target당 새 JVM에서 강제한 최신 authoritative artifact:

```text
audit-results/
  fed-runtime-parameter-role-forced-v4-auxiliary-aware-20260831T140051Z/
```

결과:

| 항목 | 결과 |
|---|---:|
| expected / result targets | 4 / 4 |
| constraint satisfied | 4 / 4 |
| target outcome | 4 SUCCESS |
| runtime capability | 10 SUCCESS, 0 FAILURE |
| failed JVM chunks | 0 |
| selected/runtime input divergence | 0 |
| confirmed Missing | 0 |

Runtime receipt의 10개 instruction은 `fedinit` 4개, `rblk` 4개, `rmempty` 2개다.
`rblk` 네 건은 모두 `PHYSICAL_REBLOCK` 및
`UNAVAILABLE_LOWERING_AUXILIARY_INPUT_BOUNDARY`로 분류됐다. `rmempty` 두 건은
`PARAMETER_ROLE_ORDER`로 ROW/COL ordered signature가 정확히 일치했다.

Attempt-local comparator의 `NO_EXACT_ACTUAL_INPUT_SIGNATURE=4`는 이 네 auxiliary
boundary이며 candidate omission 판정이 아니다.

Checksum-file SHA-256:

```text
campaign SHA256SUMS.txt:
  a258f2ab96a4615bfdad48b278ffb37ff5f94dac14d13f204caf74f6963f70c6
post-comparison checksum file:
  0b71a885c01348b03050203b5cbc958f5365c091f4021b499e6446916b3ee842
```

## 4. 최신 clean runtime-space inventory

과거 실패·폐기·proxy artifact를 섞지 않고, 이전 v6와 같은 curated evidence roots에서
구형 parameterized evidence만 최신 v3/v4 evidence로 교체했다.

```text
audit-results/
  fed-runtime-space-inventory-v7-auxiliary-aware-clean-20260831T140323Z/
```

| 항목 | 결과 |
|---|---:|
| FED instruction source classes | 41 |
| concrete / abstract | 36 / 5 |
| positive-witnessed concrete families | 36 / 36 |
| runtime capability SUCCESS / FAILURE | 30,696 / 0 |
| successful runtime signature groups | 169 |
| exact actual-input rows | 28,193 |
| selected-target rows | 72 |
| candidate rows / groups | 2,150 / 343 |
| conversion-frontier rows | 530 |
| generic operand-cardinality unavailable | **0** |
| explicit lowering-auxiliary boundary | 6 |

Actual input method breakdown:

```text
INSTRUCTION_OPERAND_ORDER                       28,379
PARAMETER_ROLE_ORDER                                 4
UNAVAILABLE_FOR_FUSED_INSTRUCTION                2,289
UNAVAILABLE_NO_PLANNED_OCCURRENCE                   18
UNAVAILABLE_LOWERING_AUXILIARY_INPUT_BOUNDARY        6
```

Inventory checksum-file SHA-256:

```text
22b8cd223d73dddb3cb2131b00f5a00fcd4a62571009bed6c3acf52c1af8b471
```

검증 중 `audit-results` 전체를 무차별 입력으로 사용한 임시 inventory는 historical
failure와 `so001` artifact까지 포함하므로
`*-NON_AUTHORITATIVE_BROAD_HISTORY`로 이름을 바꾸고 근거 집계에서 제외했다.

## 5. 통과한 검증

```text
mvn -q -DskipTests compile                                      PASS
PlannerSpaceAuditTest                                          PASS
PlannerRuntimePlacementAuditTest                               PASS
FederatedParameterizedBuiltinPlanningTest ROW/COL              PASS
forced ROW/COL rmempty campaign (1 target/JVM)                 4/4 PASS
attempt-local comparator                                       PASS
Python inventory/comparator unit tests                         PASS
Python py_compile                                              PASS
git diff --check                                               PASS
```

## 6. 현재 결론

1. Map-encoded parameter operand identity는 이제 HOP role 순서로 검증된다.
2. `rblk`의 3-vs-1 cardinality는 planner bug가 아니라 HOP 아래에 삽입된 lowering-helper
   boundary였으며, 감사기가 이를 별도 분류한다.
3. ROW/COL `rmempty`에 대해 P의 CP/FED state 네 개는 모두 privacy-legal forced plan으로
   실행됐고, 해당 관측 범위에서 Missing과 input divergence는 0이다.
4. 36개 concrete FED family 모두 최소 한 positive runtime witness를 갖는다.
5. 그러나 family-level positive coverage는 state-space completeness가 아니다. 다음 단계는
   opcode × ordered input FType × output residency별 독립 runtime-supported state를 더
   열거하고, 같은 attempt의 P/L과 결합하는 것이다.

## 7. 다음 실행 단계

우선순위는 FType과 output residency 분기가 큰 family다.

1. binary matrix/matrix 및 matrix/vector의 ROW/COL/BROADCAST 조합
2. `rbind`/`cbind`, `reshape`, indexing의 layout-preserving/changing 경로
3. weighted-div 및 quaternary 변형
4. 각 probe에서 planner-on candidate P, forced legal state, successful R receipt를 동일
   attempt에 수집
5. unavailable fused boundary를 fusion contract로 분해할 수 있는지 별도 검증

각 source의 FType reference는 지원 증거가 아니라 probe 우선순위 힌트로만 사용한다.
