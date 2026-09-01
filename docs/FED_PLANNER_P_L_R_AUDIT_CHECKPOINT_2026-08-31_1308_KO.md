# FED Planner P/L/R 감사 현재 상황 보고서

**보고 시각:** 2026-08-31 13:08 CEST  
**작업 트리:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**브랜치:** `g014/fed-runtime-space-audit-20260830`  
**서버 정책:** `so001`은 proxy이므로 영구 제외; 원격 증거는 `so003/so004/so006`만 사용

## 1. 현재 결론

Authoritative 5,408-target forced-state 감사에서 runtime-capability evidence 없이
남았던 8개 target을 모두 원인 규명하고 수정했다. 수정 후 동일 target을 각각 새
Maven/Surefire JVM에서 다시 강제한 최종 캠페인은 다음을 만족한다.

```text
expectedTargets:                  8
resultRows:                       8
constraintSatisfied:              8
SUCCESS:                          8
runtimeCapabilityRows:           38
runtimeCapability SUCCESS:       38
targetsWithRuntimeCapability:      8
failedJvmChunks:                   0
infrastructureStatus:           PASS
classificationStatus:    ALL_SUCCESS
```

따라서 기존 `TRIAGE_NO_RUNTIME_CAPABILITY_EVIDENCE=8`은 planner가 공개한 상태가
본질적으로 실행 불가능해서 생긴 것이 아니라, **compiler/lowering 경계 두 곳과
federated-frame collection runtime 한 곳의 구현 결함** 때문에 발생했다. 해당
8개에는 이제 모두 positive `PUBLISHED_LEGAL_EXECUTED` witness가 있다.

다만 이 결과는 공개된 `P`의 8개 anomaly를 해결한 것이며, 아직 독립 runtime
space와 exact identity로 전체 상태를 대조한 것은 아니다. 따라서 전역적으로
`Missing=(R∩L)-P=0`이라고 주장하지 않는다.

## 2. 해결한 결함: 두 계층

### 2.1 Compiler/lowering 계층

#### A. Planner placement를 가로지르는 TSMM/MM-chain/transpose fusion

기존 HOP rewrite/lowering은 parent와 child가 서로 다른 selector-owned placement를
가져도 materialization marker만 없으면 transpose-self 또는 MM-chain으로 fusion할
수 있었다. 이 경우 exact planner가 선택한 occurrence별 CP/FED와 LOUT/FOUT
authority가 fused physical owner에서 사라졌고, runtime 직전 placement audit가
`LOWERING_FUSION_MISMATCH`로 중단했다.

`AggBinaryOp`에 planner fusion boundary를 추가했다.

- 기존 relocation/materialization boundary는 항상 fusion 차단
- parent/child 모두 planner-selected이면 `ExecType`과 `FederatedOutput`이 같은
  경우에만 fusion 허용
- 같은 `FED/LOUT` placement 내부의 정상 TSMM fusion은 그대로 허용

이 변경으로 L2SVM/LM의 transpose 및 aggregate-binary 6개 target이 실제 FED
instruction까지 lowering되어 모두 실행됐다.

#### B. Federated frame을 거부한 exact local materialization

`Dag.insertLocalMaterializeLops`의 producer 판정이 matrix-only였기 때문에
P2FFN의 `castdtf FED/FOUT/ROW` 뒤 local frame materialization이 concrete
federated producer Lop을 찾지 못했다. cacheable federated producer 판정을
`MATRIX or FRAME`으로 확장하되, REFED/FOUT 전용 matrix 규칙은 matrix-only로
유지했다.

또한 synthetic `PrefetchCPInstruction`이 frame을 동기적으로 collect하고
`ExecutionContext.setFrameOutput`으로 새 local `FrameObject`를 만들도록 했다.
local output에는 이전 `FederationMap`이 남지 않도록 stale metadata도 제거한다.

### 2.2 Federated runtime 계층

Compiler/lowering 수정 후 P2FFN 두 target은 runtime까지 도달했지만, federated
frame collection에서 worker schema와 coordinator schema가 달라 실패했다.

- worker의 `as.frame`은 partition별 실제 값으로 schema를 추론해
  `BOOLEAN/INT/FP`를 생성할 수 있다.
- coordinator의 cast instruction은 output schema를 FP64로 보수적으로 기록했다.
- 기존 `FrameObject.readBlobFromFederated`는 coordinator schema를 그대로 써서
  Boolean 문자열 `true`를 FP64로 읽으려다 `NumberFormatException`을 냈다.

수정된 collection은 모든 worker `FrameBlock`을 받은 뒤 column별 schema를
`ValueType.getHighestCommonTypeSafe`로 병합한다. row/column federation의 range
offset을 반영하고, 각 partition을 병합 schema로 정규화한 다음 값을 복사하며,
최종 `FrameObject` schema도 병합 결과로 갱신한다. 이 수정 후 P2FFN 두 target도
모두 성공했다.

## 3. 수정 파일과 회귀 테스트

주요 production 변경:

```text
src/main/java/org/apache/sysds/hops/AggBinaryOp.java
src/main/java/org/apache/sysds/lops/compile/Dag.java
src/main/java/org/apache/sysds/runtime/instructions/cp/PrefetchCPInstruction.java
src/main/java/org/apache/sysds/runtime/controlprogram/context/ExecutionContext.java
src/main/java/org/apache/sysds/runtime/controlprogram/caching/FrameObject.java
```

회귀 테스트:

```text
FederatedDagExactRefedInputProjectionTest
  - cross CP→FED boundary는 TSMM fusion 차단
  - same FED/LOUT placement는 TSMM fusion 유지

FederatedDagLocalMaterializeTest
  - exact FED/FOUT frame producer를 UnaryCP local materializer로 rewire

PrefetchCPInstructionPlannerMaterializationTest
  - planner frame prefetch가 distinct local FrameObject 생성
  - stale FederationMap 제거

FrameObjectFederatedMaterializationTest
  - BOOLEAN/INT32와 FP64/INT64 row partitions를 FP64/INT64로 병합
  - 실제 값 변환 검증
```

Focused Maven 검증은 총 87개 테스트, failures/errors 0으로 통과했다.

```text
FrameObjectFederatedMaterializationTest:                 1/1 PASS
PrefetchCPInstructionPlannerMaterializationTest:         3/3 PASS
FederatedDagLocalMaterializeTest:                        7/7 PASS
FederatedDagExactRefedInputProjectionTest:              16/16 PASS
PlannerRuntimePlacementAuditTest:                       60/60 PASS
mvn -DskipTests compile:                                      PASS
```

## 4. 최종 8-target 보충 artifact

```text
audit-results/fed-space-eight-lowering-verified-20260831T125835+0200/
SHA256SUMS.txt SHA-256:
  f4936b152ef63a8a37e369b6e2f7d3a222bc79c9761e246f865773345b5dd73f
```

Artifact 내부 `sha256sum -c SHA256SUMS.txt --quiet` 검증은 `PASS`다. 이
캠페인은 target당 1 JVM을 사용했고, 8개 모두 forced constraint가 적용·충족됐고,
38개의 구체 runtime-capability event가 모두 `SUCCESS`였다.

원래 실패 로그와 분류는 다음 immutable triage artifact에 그대로 보존한다.

```text
audit-results/fed-space-authoritative-no-capability-triage-20260831T122017+0200/
SHA256SUMS.txt SHA-256:
  01b584a63521674c6ad8d36bb8d1e487730d669e0055850e52de52e75287535e
```

## 5. Authoritative 집계를 해석하는 방법

기존 authoritative final artifact와 checksum은 재작성하지 않는다.

```text
audit-results/fed-space-forced-normalized-v2-20260831T053209Z-authoritative-final-20260831T074722Z/
```

그 artifact가 기록한 역사적 집계는 계속 다음과 같다.

| classification | immutable count |
|---|---:|
| `PUBLISHED_LEGAL_EXECUTED` | 4,823 |
| `PUBLISHED_NOT_GLOBALLY_FEASIBLE` | 38 |
| `TRIAGE_ASSERTION_AFTER_SUCCESSFUL_RUNTIME` | 327 |
| `TRIAGE_NO_RUNTIME_CAPABILITY_EVIDENCE` | 8 |
| `UNTESTED_TARGET_NOT_EMITTED` | 201 |
| `UNTESTED_REPLAY_IDENTITY_AMBIGUOUS` | 11 |

최신 코드와 보충 artifact를 함께 읽는 **post-fix supplemental view**는 다음과
같다.

| classification | supplemental count | 변화 |
|---|---:|---:|
| `PUBLISHED_LEGAL_EXECUTED` | 4,831 | +8 |
| `TRIAGE_NO_RUNTIME_CAPABILITY_EVIDENCE` | 0 | -8 |

이는 원래 checksum artifact를 바꾼 재집계가 아니라, 원본 8개 target ID에 대한
새 positive witness를 덧붙인 해석이다. 나머지 authoritative classification은
변경하지 않는다.

## 6. 현재 증거 경계와 다음 작업

완료된 것:

1. published-state 5,408개 forced campaign 및 stage reconciliation
2. runtime-capability 없는 8개 원본 로그 보존
3. 8개 root cause의 compiler/lowering/runtime 수정
4. focused regression, compile, target별 clean-JVM 재실행
5. 8/8 positive runtime witness와 checksum 보존

아직 완료되지 않은 것:

1. 독립적인 runtime-supported state `R` 전체를 생성
2. 동일 analysis/replay context의 exact occurrence identity와 ordered input
   signature로 `R`과 `P`를 join
3. privacy와 whole-program consistency를 만족하는 `L`을 동일 context에서 확인
4. `Missing=(R∩L)-P` 및 `Spurious=P-(R∩L)`의 전역 판정

기존 broad comparison은 `confirmedMissing=0`이지만 coverage가 완전하지 않고,
exact candidate join이 없는 runtime witness가 남아 있다. 다음 단계에서는
`so003/so004/so006`의 원래 context-bearing candidate artifact를 먼저 추적한다.
존재하면 로컬로 checksum과 context를 검증한 뒤 exact join을 수행한다. 없으면
unique replay context 단위로 candidate discovery를 재실행해 동일 compilation에서
`P`, `L`, `R` identity를 함께 생성한다. exact identity가 없는 generalized match는
`Missing` 사실로 승격하지 않는다.
