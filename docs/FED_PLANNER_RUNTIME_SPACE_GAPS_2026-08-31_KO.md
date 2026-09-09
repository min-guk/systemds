# FED Planner Runtime-Space 잔여 감사 범위 — 2026-08-31

## 1. 목적과 판정 경계

이 문서는 현재 source tree의 FED runtime instruction과 완료된 forced-state campaign을 대조하여,
아직 `P(o,i)`와 관측 가능한 `R(o,i)`를 occurrence/input-signature 단위로 강제 검증하지 못한
연산 family를 정리한다.

```text
P(o,i): shared candidate builder가 selector에 공개한 상태
L(o,i): shared privacy/whole-program legality를 통과한 상태
R(o,i): 실제 lowering 및 runtime 실행에 성공한 상태
```

Runtime class가 존재하거나 한 fixture에서 성공했다는 사실만으로 `P = R`이라고 보지 않는다.
각 published target을 exact occurrence와 ordered input signature로 다시 강제하고, lowering·runtime
receipt와 결합해야 해당 범위를 닫은 것으로 간주한다.

## 2. 완료 범위

| family | forced targets | 결과 |
|---|---:|---:|
| Binary | 34 | 34/34 |
| Indexing | 28 | 28/28 |
| WDivMM LEFT/RIGHT | 14 | 14/14 |
| WDivMM BASIC | 8 | 8/8 |
| WSigmoid/WSLoss direct ROW/COL | 10 | 10/10 |
| **합계** | **94** | **94/94** |

Weighted-quaternary 10개는 다음 artifact에서 확인한다.

```text
discovery:  audit-results/fed-runtime-weighted-quaternary-layout-e2e-v1-20260831T180004Z/
campaign:   audit-results/fed-runtime-weighted-quaternary-layout-forced-v1-20260831T180534Z/
validation: audit-results/fed-runtime-weighted-quaternary-validation-20260831T181306Z/
```

## 3. Source/runtime inventory의 의미

기존 input-aware inventory는 다음을 포함한다.

```text
audit-results/fed-runtime-space-inventory-v5-input-aware-20260831T132003Z/
  REPORT.md
  candidate_opcode_coverage.csv
  runtime_frontier_coverage.csv
```

- source class: 41개
- concrete runtime family: 36개
- positive capability witness가 있는 concrete family: 36개
- candidate-P grouping: 291개
- runtime conversion-frontier row: 460개

이는 runtime instrumentation의 도달 가능성을 보여 주지만 exhaustive `P` 대 `R` 증명은 아니다.
특히 입력 FType, output residency, synthetic materialization, privacy label이 달라지면 같은 opcode도
서로 다른 물리 상태다.

## 4. 잔여 우선순위

### P0. WCeMM/WUMM

- weighted-quaternary family의 남은 직접 kernel이다.
- ROW/COL primary input, scalar aggregation(WCeMM), federated matrix output(WUMM), local execution 및
  synthetic FOUT 후보를 분리해서 검증해야 한다.
- WUMM runtime은 ROW/COL 이외 mapping을 거부한다. WCeMM도 runtime branch와 shared rule을 직접
  대조해야 한다.

### P1. AggregateBinary와 AggregateUnary

- `ba(+*)`, `uak+` 등의 ROW/COL/BROADCAST/FULL 조합을 검증한다.
- `PRIVATE_AGGREGATION`에서 aggregate-to-public이 허용되는 경우와 raw collection이 금지되는 경우를
  같은 fixture에서 분리해야 한다.
- 비용·selector가 아니라 shared `L(o,i)`의 exclusion이 먼저 맞는지 확인한다.

### P1. Reorg

- transpose(`r'`)와 `rdiag`의 layout transition 및 redistribution을 검증한다.
- 계획 target의 FType과 runtime output mapping이 실제로 일치하는지 강제한다.

### P2. Ternary/Nary/quantile 계열

- `t(+*)`, nary multiply/list, qsort/qpick, covariance, ctable, `contains`, `rmempty`를 family별로
  분리한다.
- parameter role과 scalar/frame 입력 때문에 opcode만으로 합치지 않고 ordered input signature를
  유지한다.

### P2. TSMM/MMChain 및 잔여 matrix kernels

- native FED output, coordinator aggregation, broadcast/redistribution 선택을 별도 target으로 만든다.

## 5. 병렬 실행 배치

`so001`은 proxy이므로 영구 제외한다. 2026-08-31 20:18 CEST preflight에서 `so003`--`so006`은
SSH, filesystem, memory 측면에서 사용 가능했다.

```text
audit-results/remote-parallel-preflight-20260831T181839Z/
```

권장 분할은 다음과 같다.

| server | 독립 campaign |
|---|---|
| `so002` | fixture 개발 및 WCeMM/WUMM canary |
| `so003` | AggregateBinary/AggregateUnary |
| `so004` | Reorg/redistribution |
| `so005` | Ternary/Nary/quantile |
| `so006` | privacy-L 및 잔여 matrix kernels |

각 서버는 동일 source receipt를 검증한 뒤 서로 겹치지 않는 manifest shard만 실행한다. target마다
fresh JVM을 사용하고, 결과를 합칠 때 source hash, manifest hash, target ID, runtime receipt를 모두
검증한다. 다른 실험 process는 종료하지 않는다.

## 6. 중단 조건

다음 중 하나가 충족되기 전에는 전역 `Missing=0` 또는 `Spurious=0`을 주장하지 않는다.

1. 해당 family의 모든 published target이 success/failure로 분류됨.
2. runtime-supported-but-unpublished 상태를 독립적으로 생성할 수 있는 negative-space probe가 완료됨.
3. privacy별 `L` exclusion이 candidate publication 전에 동일하게 적용됨이 입증됨.

