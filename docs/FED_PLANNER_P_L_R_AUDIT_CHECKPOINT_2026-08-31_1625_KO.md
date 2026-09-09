# FED Planner P/L/R 감사 현재 상황 보고서

**보고 시각:** 2026-08-31 16:25 CEST  
**작업 트리:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**브랜치:** `g014/fed-runtime-space-audit-20260830`  
**실행 정책:** `so001` 제외. 현재 증분 검증은 로컬 `dams-so002`에서 수행한다.

## 1. 목표와 판정 원칙

occurrence `o`, ordered input signature `i`, placement state `s`별로 다음 공간을
독립적으로 수집하고 같은 실행 attempt 안에서 결합한다.

```text
P(o,i): selector에 공개된 candidate state
L(o,i): privacy 및 whole-program consistency를 통과한 legal state
R(o,i): 실제 HOP→LOP→instruction lowering 및 runtime 실행에 성공한 state

Missing(o,i)  = (R(o,i) ∩ L(o,i)) - P(o,i)
Spurious(o,i) = P(o,i) - (R(o,i) ∩ L(o,i))
```

Formal Missing/Spurious 판정은 exact occurrence, ordered input identity, privacy legality,
강제 상태 제약 충족, successful runtime receipt가 모두 동일 attempt에서 확인된 경우에만
내린다. FED instruction family의 단순 positive witness는 전체 상태 공간 완전성 증명이
아니다.

## 2. 직전 완료 항목

상세 근거는
`docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_1604_KO.md`에 있다.

1. named-parameter builtin의 runtime operand를 HOP의 logical parameter role 순서로
   복원했다.
2. `fedinit` 아래 `rblk`처럼 원래 HOP과 입력 경계가 다른 lowering helper를
   `UNAVAILABLE_LOWERING_AUXILIARY_INPUT_BOUNDARY`로 분리했다.
3. ROW/COL `rmempty`의 공개 상태 4개를 target당 새 JVM에서 강제했고 4/4가
   privacy-legal plan 및 runtime execution에 성공했다.
4. curated inventory v7에서 concrete FED instruction 36/36 family가 positive witness를
   가지며, 30,696 runtime success와 0 failure를 확인했다.

주요 artifact:

```text
audit-results/fed-runtime-parameter-role-forced-v4-auxiliary-aware-20260831T140051Z
audit-results/fed-runtime-space-inventory-v7-auxiliary-aware-clean-20260831T140323Z
```

## 3. 새 ROW/COL append/reshape probe

새 통합 fixture:

```text
src/test/java/org/apache/sysds/test/functions/federated/fedplanning/
  FederatedLayoutStatePlanningTest.java
src/test/scripts/functions/privacy/fedplanning/
  FederatedLayoutStatePlanningTest.dml
  FederatedLayoutStatePlanningTestReference.dml
```

이 fixture는 public federated ROW/COL 입력에 대해 다음 연산을 local reference와 수치
비교한다.

```text
rbind(ROW, ROW)               expected ROW
cbind(ROW, ROW)               expected ROW
rbind(COL, COL)               expected COL
cbind(COL, COL)               expected COL
matrix(ROW, ..., byrow=TRUE)   expected ROW
matrix(COL, ..., byrow=FALSE)  expected COL
```

일반 FedAll 실행은 통과했으며 heavy hitter는 `fed_append=4`, `fed_rshape=2`였다.
Planner-enabled discovery artifact는 6개 selected operation 모두 exact ordered input
signature와 expected FType으로 실행됐음을 보였다.

```text
audit-results/fed-runtime-layout-state-e2e-v1-20260831T141348Z
checksum-file SHA-256:
  da6eda3b14a5be119d80d6870f6fab3a2a773b13888e3bd818597221ae17650a
```

## 4. 22개 공개 후보의 강제 실행 결과

Discovery P에서 append/reshape 6개 occurrence의 공개 후보를 전부 manifest로 만들었다.

| 상태 종류 | target 수 |
|---|---:|
| CP/FOUT/BROADCAST | 6 |
| CP/LOUT/- | 6 |
| FED/FOUT/ROW | 3 |
| FED/FOUT/COL | 3 |
| FED/LOUT/ROW | 2 |
| FED/LOUT/COL | 2 |
| **합계** | **22** |

Target당 새 JVM으로 실행한 artifact:

```text
audit-results/fed-runtime-layout-state-forced-v1-20260831T141722Z
```

최종 캠페인 집계:

| 항목 | 결과 |
|---|---:|
| expected / result targets | 22 / 22 |
| forced constraint satisfied | 22 / 22 |
| SUCCESS | 18 |
| RUNTIME_FAILURE | 4 |
| runtime capability SUCCESS / FAILURE | 100 / 4 |
| failed JVM chunks | 0 |
| infrastructure status | PASS |
| classification status | NEEDS_TRIAGE |

실패 네 건은 모두 append가 공개한 `FED/LOUT` 상태다.

| target | operation | input | planned | observed output |
|---|---|---|---|---|
| `fd0026a58885132a` | cbind | ROW,ROW | FED/LOUT/ROW | FED/FOUT/ROW |
| `53a08ea5b81064c3` | rbind | ROW,ROW | FED/LOUT/ROW | FED/FOUT/ROW |
| `41897e41ddf1edd1` | rbind | COL,COL | FED/LOUT/COL | FED/FOUT/COL |
| `1b2e510c96d0e3f4` | cbind | COL,COL | FED/LOUT/COL | FED/FOUT/COL |

네 instruction 모두 직렬화된 output flag는 `LOUT`였고 ordered input signature도
정확히 일치했다. 하지만 `AppendFEDInstruction` 실행 후 output value는 coordinator-local
matrix가 아니라 federated mapping을 유지했다. 감사기는 이를
`RUNTIME_VALUE_MISMATCH`로 차단했다.

따라서 현재 증거가 지시하는 문제는 selector가 잘못된 target을 강제하지 못한 것이
아니라, **candidate builder가 append의 FED/LOUT를 P에 공개하지만 runtime append가 그
residency contract를 구현하지 않는 P/R 불일치**다. Privacy는 네 경우 모두 PUBLIC이고
강제 legality/constraint도 통과했으므로 privacy rejection이나 harness 실패가 아니다.

## 5. 현재 판정

1. append의 네 `FED/LOUT` 후보는 현 구현 그대로라면 observed `R`에 포함되지 않는다.
2. 동일 attempt에서 P와 L은 확인됐고 lowering도 LOUT flag를 전달했으나 runtime value가
   FOUT이므로, 이 네 건은 **잠정 Spurious 후보**다.
3. 최종 Spurious 판정 전, runtime이 LOUT contract를 지원하도록 고치는 것이 의도된
   semantics인지 append 구현과 다른 FED instruction의 forced-local 경로를 대조한다.
4. 지원이 타당하고 안전하면 runtime에 local collection을 구현하고 네 target을 재실행한다.
   지원하지 않을 설계라면 shared candidate generation에서 해당 state를 제거한다.
5. 수정 후 같은 target ID를 재실행하여 runtime receipt와 수치 결과가 모두 성공해야만
   문제 해결로 판정한다.

## 6. 지금부터 이어갈 작업

1. `AppendFEDInstruction`과 forced-local을 지원하는 FED instruction을 비교하여 LOUT
   contract의 정확한 구현 위치를 확정한다.
2. 가장 작은 수정과 회귀 테스트를 추가한다.
3. compile 및 append/reshape 통합 테스트를 실행한다.
4. 실패한 네 target만 새 JVM으로 재실행한다.
5. 필요하면 22개 전체 캠페인을 clean rerun하고 attempt-local comparator를 생성한다.
6. curated runtime-space inventory와 이 보고서를 최종 증거로 갱신한다.

현재 작업은 완료 상태가 아니라 **실패 원인이 exact append FED/LOUT output-residency
contract로 격리된 진행 중 상태**다.
