# FED Planner P/L/R 감사 현재 상황 보고서

**보고 시각:** 2026-08-31 17:58 CEST  
**작업 트리:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**브랜치:** `g014/fed-runtime-space-audit-20260830`  
**감사 기준 HEAD:** `0d769014d18ffb6a915b186c9bc05596710a3e24`  
**실행 호스트:** `dams-so002` (`so001`은 proxy이므로 제외)

## 1. 현재 결론

이번 증분에서는 binary matrix 연산에 대해 selector가 공개한 34개 placement state를
**target당 하나의 새 Maven/Surefire JVM**에서 강제 실행했다. 최종 결과는 다음과 같다.

```text
expected/result targets:       34 / 34
forced constraint satisfied:   34 / 34
target outcomes:               34 SUCCESS, 0 FAILURE
runtime capability receipts:   296 SUCCESS, 0 FAILURE
failed JVM chunks:             0
infrastructure status:         PASS
classification status:         ALL_SUCCESS
```

동일 replay attempt 안에서 candidate `P`와 실제 runtime receipt `R`을 exact occurrence 및
ordered input signature로 join한 결과, 관측된 binary witness의 `confirmedMissing`은 0이고
selector가 고른 input signature와 runtime input signature의 divergence도 0이다.

이 결과의 범위는 명확히 제한한다. 전용 PUBLIC fixture가 공개한 **34개 P target을 모두
검증**했지만, runtime이 이론상 실행할 수 있는 전체 공간 `R`을 독립적으로 완전 열거한
것은 아니다. 따라서 전역적으로 `Missing=Spurious=∅`라고 주장하지 않는다.

## 2. 전체 감사 진행 상태

현재까지 확정된 주요 결과는 다음과 같다.

1. Candidate/legality/runtime evidence를 분리하는 P/L/R 감사 기반을 구현했다.
2. forced-state replay를 exact occurrence, ordered input signature, privacy legality,
   target constraint로 fail-closed하게 만들었다.
3. target 사이의 JVM 상태 오염을 피하기 위해 target당 fresh JVM 실행을 지원한다.
4. append/reshape 전용 22-state campaign에서 발견한 append `FED/LOUT` runtime 계약 위반을
   수정했고, clean rerun에서 22/22 target과 104/104 runtime receipt가 성공했다.
5. curated inventory v8 기준 concrete FED source family 36/36에 positive runtime witness가
   있으며, 포함된 clean evidence는 30,800 SUCCESS / 0 FAILURE다.
6. 이번 binary campaign은 34/34 target, 296/296 runtime receipt 성공으로 별도 확정했다.

최신 clean inventory는 다음 위치에 보존돼 있다.

```text
audit-results/fed-runtime-space-inventory-v8-layout-lout-fixed-clean-20260831T145413Z
```

단, family-level positive witness coverage와 `(o,i,s)`별 complete-space proof는 서로 다른
주장이다. 후자는 instruction family별로 계속 확장 중이다.

## 3. 판정 모델

컴파일된 occurrence `o`, ordered input-residency signature `i`, placement state `s`에 대해:

```text
P(o,i): shared candidate builder가 selector에 공개한 state
L(o,i): shared privacy/whole-program constraint를 만족하는 legal state
R(o,i): HOP→LOP→instruction lowering 후 runtime에서 실제 성공한 state

Missing(o,i)  = (R(o,i) ∩ L(o,i)) - P(o,i)
Spurious(o,i) = P(o,i) - (R(o,i) ∩ L(o,i))
```

이번 강제 캠페인의 모든 target은 `privacy=PUBLIC`이며 whole-program forced constraint를
통과했다. 실행 성공만으로 임의의 runtime row를 P와 합치지 않고 다음 조건이 모두 맞을
때만 exact join한다.

- 같은 replay attempt
- exact occurrence identity
- exact ordered input signature
- 같은 planned target state
- privacy/whole-program legality
- successful post-execution residency receipt

## 4. Binary 전용 fixture와 공개된 P 공간

추가한 fixture는 다음과 같다.

```text
src/test/java/org/apache/sysds/test/functions/federated/fedplanning/
  FederatedBinaryLayoutPlanningTest.java
src/test/scripts/functions/privacy/fedplanning/
  FederatedBinaryLayoutPlanningTest.dml
  FederatedBinaryLayoutPlanningTestReference.dml
```

12×12 PUBLIC matrix를 ROW/COL partition으로 각각 federate하고, local full matrix와 row/column
vector를 조합해 다음 구조를 검증한다.

```text
RR  = ROW + ROW
CC  = COL + COL
RL  = ROW + local-full
LR  = local-full - ROW
CL  = COL + local-full
LC  = local-full - COL
RRV = ROW + local-row-vector
CCV = COL + local-column-vector
RC  = ROW + COL
CR  = COL - ROW
```

수치 결과는 local reference와 비교하고, 실제 FED `+`/`-` heavy hitter도 확인한다. 반대로
local vector를 왼쪽에 두는 비대칭 broadcast는 DML/HOP validation에서
`Invalid binary broadcasting from left`로 거부되므로 runtime-capability target으로 넣지
않았다. 이는 planner omission이 아니라 언어/HOP legality boundary다.

Discovery artifact:

```text
audit-results/fed-runtime-binary-layout-e2e-v3-audit-fixed-20260831T173537Z
```

34개 target의 state 분포:

| Planned state | 수 |
|---|---:|
| CP/FOUT/COL | 4 |
| CP/FOUT/ROW | 4 |
| CP/LOUT/- | 10 |
| FED/FOUT/COL | 4 |
| FED/FOUT/ROW | 4 |
| FED/LOUT/COL | 4 |
| FED/LOUT/ROW | 4 |

Opcode는 `b(+)` 25개, `b(-)` 9개다. Ordered input signature는 다음과 같이 구성된다.

| Ordered input signature | 수 |
|---|---:|
| ABSENT_LOCAL, COL | 4 |
| ABSENT_LOCAL, ROW | 4 |
| COL, ABSENT_LOCAL | 8 |
| COL, COL | 4 |
| COL, ROW | 1 |
| ROW, ABSENT_LOCAL | 8 |
| ROW, COL | 1 |
| ROW, ROW | 4 |

## 5. Discovery 중 발견하고 수정한 감사 계층 결함

최종 binary physical execution은 성공했지만, discovery 과정에서 두 개의 **audit-only
false mismatch**를 먼저 발견했다. 이를 숨기거나 candidate를 삭제하지 않고 exact authority
규칙을 보강했다.

### 5.1 Local persistent read의 lazy `createvar`

초기 audit은 local persistent read `PRead`가 physical lowering에서 lazy `createvar`
descriptor가 되는 것을 lifecycle auxiliary로 분류했다. 그 결과 PRead 자체의 lowering
receipt를 잃고, 상위 FED binary instruction에 융합됐다고 잘못 판정했다.

수정 파일:

```text
src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAudit.java
src/test/java/org/apache/sysds/hops/fedplanner/placement/PlannerRuntimePlacementAuditTest.java
```

허용 범위는 일반 `createvar` 전체가 아니다. 다음을 모두 만족하는 exact PRead descriptor만
`PERSISTENT_READ_DESCRIPTOR_MATCH`로 인정한다.

1. 실제 HOP이 `DataOp(PERSISTENTREAD)`이다.
2. planner opcode가 `PRead`다.
3. selected physical state가 `CP/LOUT`이며 FType이 없다.
4. instruction이 `VariableCPInstruction.CreateVariable`이다.
5. Hop/Lop provenance가 정확히 같다.
6. 생성 symbol이 SystemDS PRead 예약 prefix를 사용한다.
7. 실제 physical placement도 `CP/LOUT`이다.

또한 `createvar`의 실제 output symbol에서 MatrixObject를 찾아 post-execution local residency까지
검증한다. 다른 lifecycle `createvar/rmvar` 및 FED-result descriptor는 계속 auxiliary로 남는다.

### 5.2 Algebraic rewrite `*` → `*2`

Local reference의 `X+X`가 static algebraic rewrite에서 `X*2`로 바뀌면서, planned `*`와
실제 specialized opcode `*2`를 audit이 불일치로 처리했다. `Opcodes.MULT2("*2")` 및
rewrite/lowering 경로에 근거해 **planned `*`에 대해서만 actual `*2`를 허용하는 단방향
closed alias**를 추가했다. 임의 opcode canonicalization은 도입하지 않았다.

두 수정 모두 독립 회귀 테스트로 고정했다. 실패한 diagnostic v1/v2 artifact는 원인 분석용일
뿐 authoritative evidence 집계에는 사용하지 않는다.

## 6. Authoritative binary campaign

Discovery:

```text
audit-results/fed-runtime-binary-layout-e2e-v3-audit-fixed-20260831T173537Z
```

Forced campaign:

```text
audit-results/fed-runtime-binary-layout-forced-v1-20260831T173845Z
```

실행 정책:

```text
targets:          34
TARGETS_PER_JVM:  1
fresh JVM chunks: 34
```

최종 `CAMPAIGN_SUMMARY.json`:

| 항목 | 결과 |
|---|---:|
| expected / result target | 34 / 34 |
| forced constraint satisfied | 34 / 34 |
| target SUCCESS / FAILURE | 34 / 0 |
| runtime capability SUCCESS / FAILURE | 296 / 0 |
| target with runtime receipt | 34 |
| failed JVM chunks | 0 |
| missing / unexpected / duplicate target | 0 / 0 / 0 |
| classification | ALL_SUCCESS |

## 7. Attempt-local P/R 비교

결과 위치:

```text
audit-results/fed-runtime-binary-layout-forced-v1-20260831T173845Z/
  attempt-local-comparison/
```

| 항목 | 결과 |
|---|---:|
| attempts | 35 |
| candidate files / rows | 34 / 4,522 |
| runtime files / rows | 34 / 296 |
| runtime SUCCESS | 296 |
| runtime rows with planned target | 152 |
| EXACT_PLANNED_TARGET_IN_P | 152 |
| NO_EXACT_ACTUAL_INPUT_SIGNATURE | 136 |
| NO_EXACT_SINGLE_OCCURRENCE | 8 |
| selected/runtime input divergence | 0 |
| confirmed Missing | 0 |
| coverageComplete | false |

`NO_EXACT_ACTUAL_INPUT_SIGNATURE`와 `NO_EXACT_SINGLE_OCCURRENCE`는 exact join 증거가 부족한
runtime auxiliary/fused row를 보수적으로 미분류한 것이며 Missing 판정이 아니다.
`attemptsWithoutCandidate=1`은 34개 target attempt 외 campaign aggregate/root 탐색이 하나
포함된 것으로, target result 34개와 candidate file 34개에는 누락이 없다.

Runtime conversion frontier는 다음과 같다.

```text
DIRECT_FED:                    296
FEDERATED_INPUT_NOT_CONVERTED: 298
```

## 8. 재현성 및 검증 증거

캠페인 종료 직후, 이후 소스 수정 전에 modified/untracked `src/` 및 `scripts/` 66개 파일의
SHA-256 receipt를 고정했다. 같은 receipt를 discovery와 forced campaign 양쪽에 복사해
동일 source state임을 확인했다.

핵심 checksum-file SHA-256:

```text
campaign SHA256SUMS.txt:
  29b778ba4544c62f0780f523db65166147e498a22692fbdd7bfa6be66992ed5d
attempt-local comparison checksum file:
  e02eed30f8c022eab89c6d35a69cf7f6591ad0d1aad37dcfab08354e731e55a3
source worktree receipt file:
  3c6671fca653d852e529ff6c4065f5a70dcc1d8d895981cbd95127558b55d046
discovery checksum file:
  ffa6a64014b3f26c5d84c7366298b3301bf48acf0bde7f74588b646a4547d061
binary evidence envelope:
  a10a652ab7459bfd8a332ef0d810a07db07dab2063452ca3fb16f6d4e5d9138d
```

Evidence envelope:

```text
audit-results/fed-runtime-binary-layout-forced-v1-20260831T173845Z/
  BINARY_EVIDENCE_ENVELOPE_SHA256SUMS.txt
  VALIDATION_SUMMARY.txt
```

검증 결과:

```text
raw campaign checksum:                         PASS
attempt-local comparator checksum:             PASS
discovery checksum:                            PASS
campaign/discovery source receipts (66 files): PASS
PlannerRuntimePlacementAuditTest:              64/64 PASS
FederatedBinaryLayoutPlanningTest:              1/1 PASS
Python audit utility tests:                     5/5 PASS
python py_compile:                             PASS
git diff --check:                             PASS
```

Repository-wide checkstyle은 이 증분의 판정 수단으로 사용하지 않았다. 현재 작업 트리와
기준 코드에 누적된 대규모 legacy/generated violation 때문에 신호가 아니며, 변경 파일의
동작은 위 targeted regression과 정적 검증으로 판정했다.

## 9. 남은 범위와 다음 작업

다음 우선순위는 indexing instruction family다. 현재 source inspection으로 확인한 검증
가설은 다음과 같지만, 아직 결론으로 쓰지 않는다.

1. Right-index rule은 입력 FType을 보존하는 FED/FOUT state를 공개한다.
2. Runtime right-index는 forced-local output을 수집하는 경로를 가진다.
3. 한 partition 안으로 축소되는 slice는 runtime에서 FULL residency가 될 수 있어 planned
   FType 보존과 실제 FType 사이의 경계 검증이 필요하다.
4. Left-index rule도 FED/FOUT 계열 state를 공개하지만 runtime left-index가 requested
   output residency를 실제로 이행하는지는 전용 forced test로 확인해야 한다.

따라서 다음 실행은 PUBLIC ROW/COL right-index 및 left-index의 compact fixture를 만들고,
정상 discovery → manifest 생성 → target당 fresh JVM forced campaign → attempt-local P/R join
순서로 진행한다. mismatch가 나오면 candidate를 임의 삭제하지 않고 P, lowering, runtime
중 어느 계약이 틀렸는지 먼저 증명한다.

## 10. 운영 상태

- `so001`은 계속 제외한다.
- 이번 binary campaign은 `dams-so002`에서 완료했다.
- 현재 보이는 장기 Java 프로세스는 본 감사 캠페인이 아니며, 다른 진행 중 작업을 중단하지
  않는 원칙에 따라 건드리지 않았다.
- 이 작업 트리는 감사 인프라와 runtime 수정이 아직 commit되지 않은 dirty state다. 본
  보고서는 현재 증거 상태를 고정한 checkpoint이며 임의 commit은 수행하지 않았다.
