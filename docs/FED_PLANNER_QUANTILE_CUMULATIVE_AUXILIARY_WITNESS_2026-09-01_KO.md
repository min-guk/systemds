# Quantile / CumulativeOffset 보조 lowering 증명 보고서 (2026-09-01)

## 결론

이 증명은 **selector가 선택하는 논리 placement 상태(P)**와 compiler/runtime가 그 상태 또는 별도 runtime-planner 경로를 실행하기 위해 만든 **물리 보조 stage**를 구분한다.

* compiled-selector quantile occurrence는 `b(quantile)`의 단일 `CP/LOUT` 상태 하나를 공개한다. 이 한 상태는 lowering 뒤 `qsort`(보조 stage, `QUANTILE_SORT`)와 `qpick`(논리 결과 stage)으로 실행된다. `qsort`와 `qpick`을 selector 상태 두 개로 세지 않는다.
* `CumulativeOffsetBinary -> CumulativeOffsetFEDInstruction(bcumoffk+)`는 legacy/runtime planner의 Spark-to-FED conversion witness이다. 현재 compiled-selector P의 cumulative 후보 행과 동일시하지 않으며 P count에도 더하지 않는다.
* 이 slice는 특정 lowering/runtime witness를 닫을 뿐, 전체 runtime set R을 열거하지 않는다. 따라서 `coverageComplete=false`이며 전역 `Missing=empty`를 주장하지 않는다.

## 변경

1. `UnaryOp.constructCumOffBinary`가 생성한 Spark cumulative offset Lop에 원래 UnaryOp Hop ID와 `CUMULATIVE_OFFSET` auxiliary kind를 부여했다.
2. outer result Lop은 `Hop.setLops`가 occurrence metadata를 stamp하면서 명시적 auxiliary kind를 지우므로, `UnaryOp.constructLops`가 outer `CumulativeOffsetBinary` tag를 복원한다. nested offset Lops는 생성 시 tag를 유지한다.
3. quantile contract test는 `qsort`만 `QUANTILE_SORT` auxiliary이고 `qpick`은 논리 결과임을 확인한다.
4. runtime integration은 proof-class를 분리한다.
   * compiled planner test: qsort/qpick lowering
   * runtime planner test: `fed_bcumoffk+` conversion

## Selector-P exact fixture / manifest

Fixture:

* `src/test/scripts/functions/privacy/fedplanning/FederatedQuantileCumulativeAuxiliaryRuntimeTest.dml`
* FULL federated 12x1 quantile input, logical occurrence `b(quantile)`

Candidate discovery에서 해당 occurrence가 공개한 P는 정확히 하나였다.

* input signature: `PRESENT:FULL,ABSENT_LOCAL:-`
* state: `CP/LOUT/-`
* manifest target: `7118787221d30264`
* exact one-row manifest: `audit-results/fed-quantile-aux-green-20260831T233000Z/manifest-compiled-quantile-cp-lout.jsonl`

이 manifest에는 `qsort`, `qpick`, `bcumoffk+` 행이 없다. 즉 compiler auxiliary를 selector state로 중복 계수하지 않는다. 이 1-target replay는 proof-only이며 기존 global union/count에 합산하지 않는다. 기존 union과 exact target-ID overlap은 없지만, 기존 TNQ quantile CP/LOUT witness와 의미적으로 같은 lowering class의 좁은 재검증이다.

so005 authoritative forced replay 결과:

* campaign: `audit-results/fed-quantile-aux-forced-20260831T234500Z/forced-one/`
* expected/result targets: 1/1
* outcome: `SUCCESS=1`, `constraintSatisfied=1`, missing/unexpected/duplicate=0
* result: target `7118787221d30264`, state `CP/LOUT/-`, `constraintSatisfied=true`
* lowering audit log에는 한 manifest target 아래 `qsort`와 `qpick`이 모두 `CP/LOUT`로 일치한다. 이는 두 instruction이 두 P 행이라는 뜻이 아니라 한 logical quantile target의 physical lowering이다.

## Runtime capability witness (P와 분리)

`audit-results/fed-quantile-aux-green-20260831T233000Z/runtime-capability-3797498.jsonl`의 성공 행:

* `QuantileSortFEDInstruction`, opcode `qsort`, Hop 117, kind `QUANTILE_SORT`, FULL 12x1 -> FULL 12x1
* `QuantilePickFEDInstruction`, opcode `qpick`, 같은 logical quantile recompile signature, FULL input -> local scalar output
* `CumulativeOffsetFEDInstruction`, opcode `bcumoffk+`, Hop 135/origin 88, kind `CUMULATIVE_OFFSET`, ROW 12x1 + local 1x1 offset -> ROW 12x1

`qsort`와 `bcumoffk+`는 `actualInputSignatureMethod=UNAVAILABLE_LOWERING_AUXILIARY_INPUT_BOUNDARY`로 기록된다. 이는 selector input-boundary 후보가 아니라 lowering/runtime capability 증거라는 뜻이다. runtime-planner execution에서는 planned occurrence authority가 없으므로 이 행들을 compiled-selector P와 결합하지 않는다.

## 테스트와 freshness

### RED

`audit-results/fed-quantile-aux-red-20260901T000800Z/`

* so005 clean unit: 4 tests 중 2 fail — missing CumulativeOffset tag와 brittle identity assertion
* so006 clean integration: 2 tests 중 1 fail — 2000-row multi-block fixture가 별도 `ucumack+` worker parsing gap을 노출

`ucumack+`는 이 auxiliary identity slice와 다른 runtime coverage gap이다. 최소 one-block(12-row) cumulative witness로 범위를 좁혔으며 multi-block 지원을 증명했다고 주장하지 않는다.

### GREEN

* unit contract: `audit-results/fed-quantile-aux-green-20260831T224500Z/so005-contract.exit` = 0 (4/4)
* integration: `audit-results/fed-quantile-aux-green-20260831T233000Z/so006-integration.exit` = 0 (2/2)
* capability run: `audit-results/fed-quantile-aux-green-20260831T233000Z/so006-capability.exit` = 0
* candidate discovery: `audit-results/fed-quantile-aux-green-20260831T233000Z/so006-candidate.exit` = 0
* one-target forced replay: `audit-results/fed-quantile-aux-forced-20260831T234500Z/so005-forced-campaign.exit` = 0

Freshness receipts:

* so005 unit PRE target absent + source inode/SHA: `audit-results/fed-quantile-aux-green-20260831T224500Z/so005-PRE_RECEIPT.txt`
* so005 physical target inode + source/class SHA: `audit-results/fed-quantile-aux-green-20260831T224500Z/so005-POST_RECEIPT.txt`
* so006 integration PRE target absent + source inode/SHA: `audit-results/fed-quantile-aux-green-20260831T233000Z/so006-PRE_RECEIPT.txt`
* so006 physical target inode + source/class SHA: `audit-results/fed-quantile-aux-green-20260831T233000Z/so006-POST_RECEIPT.txt`
* so005 forced stage initial target absent: `audit-results/fed-quantile-aux-forced-20260831T234500Z/so005-PRE_RECEIPT.txt`
* campaign-required physical target inode: `audit-results/fed-quantile-aux-forced-20260831T234500Z/so005-CAMPAIGN-PRE-TARGET.txt`
* campaign clean-build physical target + source/class SHA: `audit-results/fed-quantile-aux-forced-20260831T234500Z/so005-POST_RECEIPT.txt`

## 제한과 후속 gap

* `coverageComplete=false`.
* multi-block Spark cumulative의 `ucumack+` worker parsing은 이 slice에서 해결하거나 지원 증명하지 않았다.
* runtime-planner qsort/qpick/CumulativeOffset 행은 positive R witness일 뿐, 모든 input/state 조합에 대한 R 열거가 아니다.
* 따라서 이 결과만으로 전역 `Missing(o,i)=empty` 또는 `Spurious(o,i)=empty`를 주장할 수 없다.
