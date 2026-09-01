# Ternary/Nary/Quantile Federated Planner P/L/R 감사 보고서

작성일: 2026-08-31  
대상 작업공간: `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`

## 1. 결론

Ternary, aggregate-ternary, nary, quantile fixture에서 selector가 공개한 32개 강제 상태를
so003--so006에 분할 실행하고, 최초 실패 2개만 수정 스냅샷으로 격리 재실행하였다.
target ID 기준 authoritative union은 다음 조건을 모두 만족한다.

- manifest target: **32**
- authoritative result: **32/32 SUCCESS**
- `constraintSatisfied=true`: **32/32**
- classification: **32/32 `PUBLISHED_LEGAL_EXECUTED`**
- primary 성공: **30**, isolated retry 성공: **2**
- duplicate target ID: **0**
- missing target ID: **0**
- unexpected target ID: **0**
- unresolved target: **0**
- runtime capability receipt: **1,214/1,214 SUCCESS**

따라서 이번 manifest가 표현한 공개 계획 공간에는 실행 불가능한 상태(Spurious)가 남아 있지
않다. 단, 이는 관찰된 runtime 경로에 대한 폐쇄 검증이지 runtime의 모든 가능한 instruction
조합을 완전 열거한 결과는 아니다.

반면 quantile에는 별도의 구조적 planning-space gap이 확인된다. runtime은 federated
`qsort`/`qpick` 파이프라인을 실행하지만 현재 논리 `b(quantile)` candidate는 그 2단계
federated 파이프라인을 하나의 대안 상태로 표현하지 않는다. 이는 단일 HOP 상태를 강제하는
현재 audit/selector 모델만으로 바로 고칠 수 있는 국소 누락이 아니라, compiler-introduced
다단계 lowering을 계획 공간에 표현해야 하는 구조적 Missing이다.

## 2. 병렬화 및 실행 전략

### 2.1 적용한 병렬화

- **서버:** so003, so004, so005, so006만 사용했다. proxy인 so001은 사용하지 않았다.
- **분할:** 32 target을 4개 shard로 나누어 서버당 8개씩 실행했다.
- **JVM 격리:** `TARGETS_PER_JVM=1`로 target 간 planner/runtime 상태 오염을 방지했다.
- **재실행 최소화:** 최초 campaign에서 성공한 30개는 재실행하지 않고 실패한 2개만
  so003과 so006에서 각각 1개씩 재실행했다.

병렬 프로그램 또는 서버를 더 늘려도 source snapshot 생성, manifest 확정, 실패 원인 규명,
patch 적용, authoritative target-ID union은 앞 단계의 결과에 의존하므로 순차 검증이 필요하다.
이번 작업의 긴 구간은 32개 runtime 자체가 아니라 잘못된 ternary-aggregate fusion이 selector가
선택한 relocation 경계를 지웠다는 사실을 찾고 수정하는 과정이었다.

### 2.2 서버별 근거

| 단계 | 서버 | 대상 수 | 결과 |
|---|---:|---:|---:|
| primary | so003 | 8 | 7 success, 1 triage |
| primary | so004 | 8 | 8 success |
| primary | so005 | 8 | 8 success |
| primary | so006 | 8 | 7 success, 1 triage |
| isolated fix2 | so003 | 1 | 1 success |
| isolated fix2 | so006 | 1 | 1 success |

모든 shard의 `SHA256SUMS.txt`를 수집 디렉터리 내부에서 다시 검증했으며 6개 shard 모두
PASS하였다.

## 3. P/L/R 정의와 fixture 범위

- **P(o,i):** occurrence `o`와 입력 signature `i`에 대해 candidate builder가 selector에 공개한
  placement/FType 상태.
- **L(o,i):** privacy와 whole-program consistency를 만족하는 legal 상태.
- **R(o,i):** 실제 HOP -> LOP -> instruction -> runtime 경로가 실행 가능한 상태.
- **Missing:** `(R(o,i) ∩ L(o,i)) - P(o,i)`.
- **Spurious:** `P(o,i) - (R(o,i) ∩ L(o,i))`.

직접 fixture는 ROW/COL federated input을 사용하여 다음 family를 함께 실행한다.

- ternary `ifelse`
- multiply chain과 `ua(+RC)` aggregate
- nary `min`
- ROW/FULL quantile (`qsort` + `qpick` lowering)

fixture와 reference는 각각 다음 파일이다.

- `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedTernaryNaryQuantileLayoutPlanningTest.java`
- `src/test/scripts/functions/privacy/fedplanning/FederatedTernaryNaryQuantileLayoutPlanningTest.dml`
- `src/test/scripts/functions/privacy/fedplanning/FederatedTernaryNaryQuantileLayoutPlanningTestReference.dml`

이번 32개 forced target은 모두 `PUBLIC` privacy이다. 따라서 이 결과를 private-aggregate 전체
privacy domain의 완전성 증명으로 확대 해석하지 않는다. privacy legality 자체는 공통
PlacementAnalysis의 L 감사 결과를 사용한다.

## 4. 상태별 authoritative 결과

| 상태 | target | 결과 |
|---|---:|---:|
| `CP/FOUT/COL` | 4 | 4 success |
| `CP/FOUT/ROW` | 4 | 4 success |
| `CP/LOUT/-` | 12 | 12 success |
| `FED/FOUT/COL` | 4 | 4 success |
| `FED/FOUT/ROW` | 4 | 4 success |
| `FED/LOUT/COL` | 2 | 2 success |
| `FED/LOUT/ROW` | 2 | 2 success |

| opcode | target | 결과 |
|---|---:|---:|
| `b(*)` | 8 | 8 success |
| `b(quantile)` | 2 | 2 success |
| `m(min)` | 6 | 6 success |
| `t(ifelse)` | 12 | 12 success |
| `ua(+RC)` | 4 | 4 success |

## 5. 확인한 결함과 수정

### 5.1 Ternary FED runtime 정렬

`TernaryFEDInstruction`의 mixed-input 정렬에서 local condition과 두 aligned federated value를
함께 처리할 때 사용할 base federation을 명시적으로 보존했다. 또한 worker fragment 안의
nested federated input을 coordinator-local CP operand로 정규화하였다. 이 수정이 없으면 P에는
있지만 실제 실행 입력 정렬에서 실패하는 Spurious 상태가 생길 수 있었다.

### 5.2 Quantile lowering identity

binary/weighted quantile은 하나의 논리 occurrence가 physical `qsort`와 `qpick`으로 낮아진다.
compiler가 생성한 `qsort`에도 원 논리 HOP ID와 `QUANTILE_SORT` auxiliary kind를 부여해 runtime
placement audit가 이를 예외 처리하지 않고 동일 occurrence의 lowering으로 증명하도록 했다.

### 5.3 Ternary aggregate fusion의 planner boundary 삭제

최초 실패 target은 다음 두 개였다.

- `b94dcc46361b6a85`: `b(*) = FED/LOUT/ROW`
- `424a1d0de56cc2f6`: `b(*) = FED/LOUT/COL`

selector는 multiply 결과를 `FED/LOUT`으로 두고 소비 `ua(+RC)`를 `CP/LOUT`으로 선택했다.
그러나 기존 ternary-aggregate rewrite가 multiply와 aggregate를 `tak+*` 하나로 fuse하면서
selector가 비용화하고 선택한 FED -> CP relocation 경계를 삭제했다. 이는 optimizer 선택과
physical lowering의 불일치이며 단순 test 문제나 서버 flake가 아니다.

`AggUnaryOp.isTernaryAggregateRewriteApplicable()`에서 다음 경우 fusion을 금지하도록 수정했다.

1. aggregate consumer input edge에 refed/FOUT/local-materialize 선택이 있는 경우
2. aggregate와 aggregate input의 planner placement가 다른 경우

fix2 로그에서는 ROW/COL 모두 multiply가 `FED/LOUT`, 소비 aggregate가 `CP/LOUT uak+`로 남아
선택한 relocation 경계가 실제 instruction에 보존되었고 두 target 모두 SUCCESS가 되었다.

## 6. Missing/Spurious 판정

attempt-local P/R join 결과는 다음과 같다.

- successful runtime row: 1,214
- explicit planned target을 가진 runtime row: 694
- `EXACT_PLANNED_TARGET_IN_P`: 694
- observed direct confirmed Missing: 0
- selected/runtime input divergence: 0

따라서 관찰된 공개 forced 상태에서는 Spurious가 없고, 관찰된 exact join 범위에서는 직접
Missing도 없다. 다만 comparator도 `coverageComplete=false`로 명시한다. 실행된 상태만으로
runtime R 전체를 완전 열거할 수 없기 때문이다.

### 6.1 Quantile의 구조적 Missing

독립적인 full-class runtime witness는 ROW input에 대해 다음 실제 실행을 기록한다.

- `QuantileSortFEDInstruction`: federated ROW -> federated ROW
- `QuantilePickFEDInstruction`: federated ROW -> local scalar

하지만 현재 논리 `b(quantile)` P는 ROW/FULL input에서 `CP/LOUT/-`만 공개한다. 즉 runtime이
지원하는 federated 2단계 파이프라인을 단일 logical candidate로 selector가 비교할 수 없다.
이를 올바르게 고치려면 `qsort` residency/FType과 최종 `qpick` result residency를 함께 가진
multi-stage candidate 또는 lowering macro-state가 필요하다. 단순히 FED 상태 하나를 추가하면
중간 정렬 결과와 최종 scalar의 서로 다른 residency를 잘못 합치므로 이번 국소 patch에는
포함하지 않았다.

## 7. 재현 가능한 증거

### 7.1 Authoritative union

- `audit-results/tnq-auth2-20260831T195218Z/authoritative-aggregate-v2/SUMMARY.json`
- `audit-results/tnq-auth2-20260831T195218Z/authoritative-aggregate-v2/FINAL_RESULTS.jsonl`
- `audit-results/tnq-auth2-20260831T195218Z/authoritative-aggregate-v2/TARGET_UNION_VALIDATION.json`
- `audit-results/tnq-auth2-20260831T195218Z/authoritative-aggregate-v2/SHA256SUMS.txt`

### 7.2 P/R comparison

- `audit-results/tnq-auth2-20260831T195218Z/attempt-space-comparison-v2/summary.json`
- `audit-results/tnq-auth2-20260831T195218Z/attempt-space-comparison-v2/REPORT.md`
- `audit-results/tnq-auth2-20260831T195218Z/attempt-space-comparison-v2/nonbaseline_evidence.csv`

### 7.3 Source/manifest receipts

- primary manifest SHA-256:
  `0b3dd4c2106c3f66144f2234dd78041ba1ee12b59cbb61a9fcb2c3a038fd798b`
- primary source-manifest SHA-256:
  `b5e083736fae2df4de30ce4088134730e2da00c6dce30b8ea67d860b5ec79432`
- fix2 manifest SHA-256:
  `0f42d8526302b6295efd8bcb7be4294ffe667f4365abb20a26d9c67e9c913b2d`
- fix2 source-manifest SHA-256:
  `1c1ac5cbb5850e9c4346b7c1335aae0d3adb300a3db3d8ecec2c517703c9e45e`

## 8. 검증

- Maven package (`-DskipTests`): PASS
- `FederatedTernaryNaryQuantileLayoutPlanningTest`: PASS
- `FederatedTernarySumMixedTest`: PASS
- `AggregateTernaryFEDInstructionNoFallbackTest`: PASS
- `PlannerRuntimePlacementAuditTest`: PASS
- aggregator/comparator Python unit tests: 4/4 PASS
- Python `py_compile`: PASS
- owned-file `git diff --check`: PASS
- 6개 remote shard checksum: PASS

## 9. 남은 작업

이번 lane의 32개 forced published state는 완료되었다. 남은 연구/구현 항목은 quantile의
2단계 runtime capability를 selector candidate로 표현하는 multi-stage planning abstraction이다.
이는 공개된 상태 하나의 validator를 고치는 문제가 아니라 P 자체를 넓히는 별도 설계 작업이다.
