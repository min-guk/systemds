# FED Planner P/L/R 감사 현재 상황 보고서

**보고 시각:** 2026-08-31 12:20 CEST  
**작업 트리:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**브랜치:** `g014/fed-runtime-space-audit-20260830`  
**서버 정책:** `so001`은 proxy이므로 영구 제외; 본 캠페인은 `so003/so004/so006`만 사용

## 1. 현재 결론

5,408개 published-state target에 대한 primary, isolated, semantic forced-state
campaign과 authoritative finalizer가 모두 끝났다. 모든 stage에서 expected target
집합, 실제 result 집합, duplicate, failed JVM, source/manifest checksum을 검증했고
최종 `validationStatus`는 `PASS`다.

이 완료는 **`P`에 공개된 상태의 강제 실행 감사가 끝났다**는 뜻이다. 별도의
runtime-space evidence를 exact `(occurrence,input signature,state)`로 join하는
`Missing=(R∩L)-P` 판정은 이 집계에서 의도적으로 수행하지 않았다. 따라서 아직
전역 planning-space completeness를 주장하지 않는다.

## 2. Stage별 완료 결과

### 2.1 Primary

| Host | Targets | SUCCESS | TNR | WPI | TRIAGE | Infra |
|---|---:|---:|---:|---:|---:|---|
| `so003` | 1,803 | 1,518 | 148 | 14 | 123 | PASS |
| `so004` | 1,803 | 1,535 | 139 | 17 | 112 | PASS |
| `so006` | 1,802 | 1,531 | 145 | 20 | 106 | PASS |
| **합계** | **5,408** | **4,584** | **432** | **51** | **341** | **PASS** |

- runtime-capability rows: 25,146/25,146 `SUCCESS`
- failed JVM, missing, duplicate, unexpected target: 모두 0
- primary non-success 824개와 isolated manifest target ID 집합: 정확히 동일

### 2.2 Isolated re-entry

| Host | Targets | SUCCESS | TNR | WPI | TRIAGE | Infra |
|---|---:|---:|---:|---:|---:|---|
| `so003` | 285 | 72 | 85 | 14 | 114 | PASS |
| `so004` | 268 | 60 | 95 | 13 | 100 | PASS |
| `so006` | 271 | 66 | 99 | 11 | 95 | PASS |
| **합계** | **824** | **198** | **279** | **38** | **309** | **PASS** |

- 각 target은 새 Maven/Surefire JVM에서 1개씩 실행
- runtime-capability rows: 3,539/3,539 `SUCCESS`
- constraint 적용·충족: 507개 = `SUCCESS` 198 + `TRIAGE` 309
- persistent TNR 279개와 semantic manifest target ID 집합: 정확히 동일

### 2.3 Semantic exact re-entry

| Host | Targets | SUCCESS | TNR | AMBIGUOUS | TRIAGE | Infra |
|---|---:|---:|---:|---:|---:|---|
| `so003` | 85 | 8 | 67 | 3 | 7 | PASS |
| `so004` | 95 | 19 | 60 | 5 | 11 | PASS |
| `so006` | 99 | 14 | 74 | 3 | 8 | PASS |
| **합계** | **279** | **41** | **201** | **11** | **26** | **PASS** |

- runtime-capability rows: 1,676/1,676 `SUCCESS`
- semantic unique match로 constraint 적용·충족: 67개 = `SUCCESS` 41 + `TRIAGE` 26
- 11개 복수 semantic match는 `REPLAY_IDENTITY_AMBIGUOUS`로 fail-closed
- 201개는 clean replay에서 target occurrence가 다시 emit되지 않아
  `TARGET_NOT_REACHED`를 유지한다. 이를 physical failure로 해석하지 않는다.

## 3. Authoritative 최종 집계

Finalizer precedence는 `semantic > isolated > primary`다.

| 최종 outcome | 수 |
|---|---:|
| `SUCCESS` | 4,823 |
| `FAILURE_REQUIRES_TRIAGE` | 335 |
| `WHOLE_PROGRAM_INFEASIBLE` | 38 |
| `TARGET_NOT_REACHED` | 201 |
| `REPLAY_IDENTITY_AMBIGUOUS` | 11 |
| **합계** | **5,408** |

| 최종 classification | 수 | 증거 해석 |
|---|---:|---|
| `PUBLISHED_LEGAL_EXECUTED` | 4,823 | 강제된 published state가 lowering과 runtime을 통과 |
| `PUBLISHED_NOT_GLOBALLY_FEASIBLE` | 38 | local `P`에는 있으나 동일 whole-program hard constraints 아래 완성 불가 |
| `TRIAGE_ASSERTION_AFTER_SUCCESSFUL_RUNTIME` | 327 | FED runtime은 성공했으나 workload의 planner-specific assertion 실패 |
| `TRIAGE_NO_RUNTIME_CAPABILITY_EVIDENCE` | 8 | runtime 이전 lowering에서 중단; 아래에서 재분류 중 |
| `UNTESTED_TARGET_NOT_EMITTED` | 201 | clean replay에서 target occurrence 미생성 |
| `UNTESTED_REPLAY_IDENTITY_AMBIGUOUS` | 11 | semantic identity 복수 일치로 강제하지 않음 |

Authoritative runtime-capability row는 총 22,543개이며 모두 `SUCCESS`다. 현재
집계에는 알려진 FED worker runtime capability failure가 0개다. 다만 이는 모든
`R` 상태가 성공했다는 뜻이 아니라, 실제 runtime까지 도달해 기록된 capability
row 중 실패가 없다는 뜻이다.

## 4. 무 capability 8개 사례의 원격 로그 보존 및 1차 원인 분류

Authoritative compact artifact에는 Maven stack trace가 포함되지 않아, 원격
isolated campaign 디렉터리에서 해당 8개 chunk의 manifest, result, Maven log,
return code를 로컬 artifact로 회수했다.

| 원인 | 수 | 현재 판정 |
|---|---:|---|
| `LOWERING_FUSION_MISMATCH` | 6 | 선택된 physical authority와 fused runtime owner가 일치하지 않아 runtime 전 audit가 차단 |
| `LOCAL_MATERIALIZATION_NO_FEDERATED_PRODUCER_LOP` | 2 | P2FFN `u(castdtf)`의 `FED/FOUT/ROW` 선택 뒤 exact local materialization이 concrete federated producer Lop을 찾지 못함 |

여섯 fusion mismatch는 L2SVM/LM function/heuristic context의 transpose와
aggregate-binary alternative에서 재현된다. 두 P2FFN 사례는 서로 다른
train/test cast-to-frame occurrence에서 동일한 lowering 예외를 재현한다.

이 8개는 **worker runtime failure가 아니라 pre-runtime physical lowering
failure**다. 그러나 여섯 fusion mismatch는 강제 target 자체가 아니라 동일한
whole-program assignment의 다른 fused owner에서 감지됐을 수 있으므로 아직 해당
target을 `Spurious`로 확정하지 않는다. P2FFN 두 개도 candidate가 실제로
불가능한 것인지, Lop producer-resolution 구현 결함인지 코드 분석 후 판정한다.

보존 artifact:

```text
audit-results/fed-space-authoritative-no-capability-triage-20260831T122017+0200/
SHA256SUMS.txt SHA-256:
  01b584a63521674c6ad8d36bb8d1e487730d669e0055850e52de52e75287535e
```

Artifact 내부 checksum 검증은 `PASS`다.

## 5. 최종 산출물과 검증

Authoritative final artifact:

```text
audit-results/fed-space-forced-normalized-v2-20260831T053209Z-authoritative-final-20260831T074722Z/
```

Finalizer 상태:

```text
manifestTargets: 5408
unresolvedTargets: 547
validationStatus: PASS
```

검증 결과:

- `aggregate/SHA256SUMS.txt`: PASS
- root `ARTIFACT_SHA256SUMS.txt`: PASS
- primary/isolated/semantic 세 stage의 missing/duplicate/unexpected: 0/0/0
- 세 stage 모든 shard `infrastructureStatus=PASS`
- `so001` 산출물 미사용

## 6. 증거 경계

1. 4,823개는 published state의 positive execution witness다.
2. 38개는 exact whole-program constraints가 증명한 global infeasibility다.
3. 327개는 runtime success 뒤 test assertion failure이므로 physical failure가 아니다.
4. 201개와 11개는 강제 실행되지 않았으므로 `UNTESTED`다.
5. 8개는 lowering failure지만 root cause 수정/판정 전에는 자동으로 `Spurious`가 아니다.
6. 본 forced-`P` 집계는 `Missing`을 계산하지 않았다.

따라서 현재 확정 가능한 주장은 “공개된 5,408 target의 강제 감사와 stage
reconciliation이 완결됐고, 4,823개의 성공 witness, 38개의 global-infeasible
state, 8개의 pre-runtime lowering anomaly를 분리했다”까지다.

## 7. 계속 진행 중인 작업

1. `PlannerRuntimePlacementAudit.verifyLowering`의 fused-owner identity와
   `requiresOwnInstruction` 판정을 추적해 여섯 fusion mismatch의 원인을 규명한다.
2. `Dag.insertLocalMaterializeLops`에서 `FED/FOUT` Unary cast-to-frame producer가
   concrete federated Lop으로 인식되지 않는 이유를 규명한다.
3. 결함이면 최소 수정과 회귀 테스트 후 위 8개 target만 새 JVM으로 재실행한다.
4. 수정 후 authoritative classification을 보충 artifact로 갱신한다.
5. 별도 runtime-space evidence를 exact identity로 join해 `Missing=(R∩L)-P`를
   판정한다.

이 단계가 끝나기 전에는 전역적으로 `Missing=0` 또는 `Spurious=0`이라고 쓰지 않는다.
