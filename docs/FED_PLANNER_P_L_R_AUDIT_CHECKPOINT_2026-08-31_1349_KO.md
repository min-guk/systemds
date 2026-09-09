# FED Planner P/L/R 감사 진행 보고서

**보고 시각:** 2026-08-31 13:49 CEST  
**작업 트리:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**브랜치:** `g014/fed-runtime-space-audit-20260830`  
**원격 서버 정책:** `so001`은 proxy이므로 제외; `so003/so004/so006`만 사용

## 1. 요약

이번 구간에서는 기존 5,408-target forced-state 감사의 후보(`P`)와 runtime
receipt를 **attempt-local exact identity**로 다시 결합했다. 그 결과 과거
comparator에는 다음 두 증거 결함이 있음을 확인했다.

1. 서로 다른 JVM/attempt의 occurrence hash를 전역으로 결합할 수 있었다.
2. selector가 선택한 target state와 lowering 뒤 concrete physical state를 같은
   상태로 취급했다.

이를 해결하기 위해 runtime receipt에 plan hash, analysis fingerprint, audit
context, planned target state, planned physical state를 추가하고, 같은 attempt 안의
동일 occurrence/input/analysis/context만 결합하는 comparator를 작성했다.

과거 artifact 전체를 attempt-local로 재검사한 36개의 input divergence는 실제
planner/runtime 불일치가 아니라 `TernaryFEDInstruction`이 loop 첫 실행 후 scalar
operand를 literal로 바꾸면서 audit recorder가 operand 순서를 잘못 재구성한
instrumentation bug였다. logical input-field 순서 fallback을 추가했고, L2SVM 실제
재실행에서 17개의 literal-rewritten iteration을 포함한 18개 ternary event 모두
planned/actual input signature가 일치했다.

현재 증거로 `confirmedMissing=0`이지만, runtime-supported space `R` 전체를
독립적으로 열거한 것은 아니므로 전역 `Missing=(R∩L)-P=0`은 아직 주장하지
않는다.

## 2. 보존된 원격 artifact의 attempt-local 재검사

`so003/so004/so006`에 남아 있던 primary, isolated, semantic-v3 campaign의 원본
candidate/runtime 파일을 같은 attempt 디렉터리 단위로 결합했다.

```text
artifact:
  audit-results/fed-space-attempt-join-v2-20260831T113542Z/

attempts:                 6,547
candidate files:          6,547
candidate rows:       1,076,549
runtime files:            6,437
runtime SUCCESS rows:    30,361
attempts without runtime:   110
```

역사적 receipt의 분류는 다음과 같다.

| classification | rows | 의미 |
|---|---:|---|
| `LEGACY_ACTUAL_STATE_COMPATIBLE_WITH_P` | 28,091 | 실제 physical state와 호환되는 후보가 동일 attempt의 P에 존재 |
| `NO_EXACT_ACTUAL_INPUT_SIGNATURE` | 190 | runtime input signature와 정확히 같은 candidate signature 없음 |
| `NO_EXACT_SINGLE_OCCURRENCE` | 2,044 | instruction을 단일 planner occurrence로 귀속할 수 없음 |
| `SELECTED_RUNTIME_INPUT_DIVERGENCE` | 36 | 계획 입력과 runtime 입력의 순서가 달라 보임 |

`confirmedMissing`은 0이지만, 28,091개의 legacy row에는 새
`plannedTargetStates/plannedPhysicalStates/plannerAnalysisFingerprint`가 없다.
따라서 위 호환성 결과는 hypothesis-level evidence이며 formal Missing 부재 증명은
아니다. artifact checksum은 다음과 같고 내부/aggregate 검증은 통과했다.

```text
ARTIFACT_SHA256SUMS.txt SHA-256:
  21b65d2677590e7405b60f72ec3f46626eefc60274d64d766056f7652a5e3767
```

## 3. 36개 input divergence의 원인과 수정

36개 row는 두 occurrence로 축약되며 모두 L2SVM loop 내부의 ternary `t(+*)`였다.
계획 입력은 다음 순서였다.

```text
input1 = Xw       -> ABSENT_LOCAL
input2 = step_sz  -> ABSENT_LOCAL
input3 = vector   -> PRESENT:ROW
```

첫 실행 후 `TernaryFEDInstruction`은 serialized instruction string의 `step_sz`를
literal scalar로 치환한다. 그러나 instruction 객체의 `input2`와 `input3` field는
그대로 유지된다. 기존 recorder는 field operand의 이름을 변형된 string에서 다시
찾았고, 더 이상 `step_sz`가 없으므로 input2 위치를 `null/MAX_VALUE`로 처리했다.
그 결과 input3가 input2보다 먼저 정렬되어 다음과 같은 가짜 signature를 만들었다.

```text
ABSENT_LOCAL, PRESENT:ROW, ABSENT_LOCAL
```

`PlannerRuntimeCapabilityAudit`가 serialized lookup에 실패할 때 reflection field의
논리 번호(`input1`, `input2`, `input3`, `inputs[i]`)를 사용하도록 수정했다. 이는
runtime instruction이나 planner 결정을 바꾸지 않고 audit ordering만 바로잡는다.

회귀 테스트:

```text
PlannerSpaceAuditTest.rewrittenLiteralKeepsTheOriginalLogicalTernaryInputOrder
PlannerRuntimePlacementAuditTest.runtimeCapabilitySeparatesSelectedTargetFromConcretePhysicalState
```

## 4. Formal runtime receipt 확장

다음 필드를 runtime-capability row에 추가했다.

```text
plannerPlanHash
plannerAnalysisFingerprint
auditContext
plannedTargetStates
plannedPhysicalStates
```

`plannedTargetStates`는 selector가 occurrence별로 선택한
`ExecType/FederatedOutput/FType`이다. `plannedPhysicalStates`는 lowering 뒤 해당
instruction이 실제로 소유한 physical state다. 둘을 분리해야 target이 runtime에서
다른 concrete layout으로 합법적으로 구현되는 경우를 Missing으로 잘못 판정하지
않는다.

예를 들어 최신 8-target 재실행에는 다음 두 event가 있다.

```text
planned target:   FED/FOUT/BROADCAST
planned physical: FED/LOUT/ROW
```

이는 target 선택이 사라진 것이 아니라 broadcast-valued logical target이 ROW
federated instruction으로 lowering된 것이다. 새 comparator는 exact target이 P에
공개됐는지와 physical implementation이 성공했는지를 별도 필드로 검증한다.

## 5. 새 receipt의 end-to-end 검증

### 5.1 L2SVM literal-rewrite 표적

```text
artifact:
  audit-results/fed-space-l2svm-receipt-v2-20260831T114244Z/
join:
  audit-results/fed-space-l2svm-receipt-v2-20260831T114244Z-attempt-join/

target:                         1/1 SUCCESS
constraintSatisfied:            1/1
candidate rows:                 295
runtime rows:                    24 SUCCESS
rows with planned target:        23
EXACT_PLANNED_TARGET_IN_P:        23
confirmedMissing:                 0
selected/runtime divergences:     0
```

핵심 ternary `+*` event는 18개였고, 그중 17개는 serialized scalar가 literal로
치환된 반복 실행이었다. 18개 모두 다음 planned/actual signature를 유지했다.

```text
ABSENT_LOCAL, ABSENT_LOCAL, PRESENT:ROW
```

Checksum file hashes:

```text
campaign: 37e92c739fe068542aa5eaab8cf81c54b45a9fec16bdc02cb0f5ead386566c9a
join:     5bb87fd4cba2fd5dbab473c88222fc2e489271d8d17394a3e975912e1f34b68e
```

### 5.2 기존 8개 lowering/runtime anomaly의 새 receipt 재실행

```text
artifact:
  audit-results/fed-space-eight-receipt-v2-20260831T114604Z/
join:
  audit-results/fed-space-eight-receipt-v2-20260831T114604Z-attempt-join/

targets:                         8/8 SUCCESS
constraintSatisfied:             8/8
candidate rows:                1,423
runtime rows:                    38 SUCCESS
rows with planned target:        33
EXACT_PLANNED_TARGET_IN_P:        33
NO_EXACT_SINGLE_OCCURRENCE:        5
confirmedMissing:                  0
selected/runtime divergences:      0
```

38/38 row에 plan hash, analysis fingerprint, audit context가 있고, 단일 planner
occurrence로 귀속 가능한 33/33 row에는 target/physical state가 모두 있다.

Checksum file hashes:

```text
campaign: 205f794eaa291402ca86559186f0d8bb44adc0af29181f3de47f04d860f23fba
join:     97b3cb1a222c7de85368cd484b8dbe6c9b8f5a06b495e112aa345ed8b99ee101
```

## 6. 변경 파일

```text
src/main/java/org/apache/sysds/hops/fedplanner/placement/
  PlannerCandidateSpaceAudit.java
  PlannerRuntimeCapabilityAudit.java
  PlannerRuntimePlacementAudit.java

src/test/java/org/apache/sysds/hops/fedplanner/placement/
  PlannerRuntimePlacementAuditTest.java
  PlannerSpaceAuditTest.java

scripts/fedplanner/
  compare_forced_attempt_runtime_space.py
  test_compare_forced_attempt_runtime_space.py
```

## 7. 최신 검증

다음 검증을 모두 새로 실행해 통과했다.

```text
mvn -q -Dskip-rat \
  -Dtest=PlannerRuntimePlacementAuditTest,PlannerSpaceAuditTest test  PASS

python3 -m unittest \
  scripts/fedplanner/test_compare_forced_attempt_runtime_space.py    PASS

python3 -m py_compile ...                                            PASS
git diff --check                                                     PASS
mvn -q -DskipTests compile                                           PASS
campaign/join SHA256SUMS internal verification                       PASS
```

## 8. 현재 결론과 남은 완료 조건

확정된 것:

1. 과거 36개 L2SVM input divergence는 planner/runtime bug가 아니라 audit
   instrumentation bug였다.
2. 새 runtime receipt는 target state와 physical state, attempt/analysis/context를
   분리해 기록한다.
3. 최신 L2SVM과 8-target campaign에서 exact planned target이 공개 후보 P에
   존재하며 선택/runtime input divergence는 0이다.
4. privacy legality는 기존 `PlacementAnalysis`의 candidate exclusion 결과와 forced
   constraint receipt를 계속 사용하며, comparator는 PUBLIC direct evidence만
   formal Missing 후보로 승격한다.

아직 열린 것:

1. 현재의 runtime row는 실행 중 관측된 `R`의 일부이지 전체 runtime-supported
   Cartesian space가 아니다.
2. 5,408개 전체 historical replay는 구형 receipt이므로 새 metadata를 소급 생성할
   수 없다.
3. `NO_EXACT_SINGLE_OCCURRENCE` instruction은 planner-owned occurrence와 정확히
   결합할 추가 provenance가 필요하다.
4. 따라서 전역 `Missing=(R∩L)-P`와 `Spurious=P-(R∩L)`의 완결 판정은 계속 보류한다.

다음 구현 단계는 runtime instruction family별로 **지원 가능한 physical state를
독립적으로 생성하는 capability probe**를 candidate builder와 분리해 만들고,
동일 analysis/context에서 새 receipt schema로 실행하는 것이다. observed-success
row만으로 coverage를 완전하다고 표시하지 않는다.
