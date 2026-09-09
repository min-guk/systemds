# FED Planner P/L/R 감사 체크포인트 — 2026-08-31 21:47 CEST

## 1. 현재 목표와 판정 경계

각 compiled occurrence `o`, ordered input signature `i`, placement state `s`에 대해 다음을 분리한다.

- `P(o,i)`: shared candidate builder가 모든 selector에 공개한 상태
- `L(o,i)`: shared privacy 및 whole-program consistency 제약을 통과한 상태
- `R(o,i)`: exact HOP→LOP→instruction lowering을 거쳐 실제 runtime에서 성공한 상태

강제 published-state campaign은 `P ∩ L`의 soundness를 검증한다. 성공한 published target은
`PUBLISHED_LEGAL_EXECUTED`로 분류한다. 이 방식만으로 독립적인 전체 `R`을 열거하지 않으므로,
모든 보고서는 `coverageComplete=false`이며 전역 `Missing=∅`을 주장하지 않는다.

## 2. 병렬 실행 구조

`so001`은 proxy이므로 사용하지 않는다. 현재 작업은 agent lane과 remote process lane을 함께 분리했다.

| lane | host | 병렬 방식 | 상태 |
|---|---|---|---|
| CentralMoment/Covariance/Ctable | `so003` | family별 독립 Maven tree 4개 | fixture 원인 분리 실행 중 |
| Unary/Cumulative/Reshape/Reblock/Cast | `so004` | fresh snapshot, 4 forced shards 예정 | 새 lane 시작 |
| Ternary/Nary/Quantile/AggTernary | `so005` 중심 | 4-host shard + failed-only retry | 1차 결과 분석/수정 중 |
| Matrix kernel/TSMM/MMChain | `so006` | 독립 Maven tree 4개, target/JVM=1 | 완료 |

각 forced campaign은 source/manifest SHA-256, exact target ID 집합, duplicate/missing/unexpected
검사, attempt-local lowering/runtime receipt, self-contained checksum을 남긴다.

## 3. authoritative 완료 범위

| family | target | 결과 |
|---|---:|---:|
| Binary | 34 | 34/34 |
| Indexing | 28 | 28/28 |
| WDivMM LEFT/RIGHT | 14 | 14/14 |
| WDivMM BASIC | 8 | 8/8 |
| WSigmoid/WSLoss | 10 | 10/10 |
| WCeMM/WUMM | 10 | 10/10 |
| AggregateBinary/AggregateUnary | 36 | 36/36 |
| Reorg | 47 | 47/47 |
| Matrix-kernel program structures | 64 | 64/64 |
| **합계** | **251** | **251/251** |

Matrix 결과:

```text
artifact:
  audit-results/fed-runtime-matrix-kernel-campaign-20260831T192211Z-fixed/
classification:
  PUBLISHED_LEGAL_EXECUTED = 64
unresolved:
  0
validation:
  PASS
source receipt:
  bb1a04f6ca2f9bfdf5c8f82bb9e5b3edfb03b0863141d836f2275b05f32b5dd7
core artifact checksum manifest:
  412ad83c14e81b23eff8e9f17c96efffb3dae718daf2de22d5bc59df1a3ab396
```

관련 상세 보고서:

- `docs/FED_PLANNER_MATRIX_KERNEL_P_L_R_AUDIT_2026-08-31_KO.md`
- `docs/FED_PLANNER_REORG_P_L_R_AUDIT_2026-08-31_KO.md`
- `docs/FED_PLANNER_AGGREGATE_P_L_R_AUDIT_2026-08-31_KO.md`
- `docs/FED_PLANNER_PRIVACY_L_AUDIT_2026-08-31_KO.md`

## 4. 새로 확인한 실제 결함

### 4.1 TSMM/MMChain fusion이 selected movement boundary를 제거

공유 transpose가 selected incoming `fed_refed`/materialization의 consumer인데도 기존 fusion
판정은 producer registry entry만 검사했다. 그 결과 transpose가 제거되고 하나의 logical
consumer가 두 physical MMTSJ edge로 투영되어 exact lowering이 모호해졌다.

수정 후 selected consumer input도 fusion boundary로 인정한다. 임의로 첫 physical edge를
고르지 않고, placement boundary를 보존해 기존 fail-closed 규칙을 유지했다. 수정 전 64개 중
30개 실패가 수정 후 동일 manifest에서 64/64 성공으로 바뀌었다.

### 4.2 Ternary FED worker 재귀 dispatch 및 aligned-base 선택

Ternary FED coordinator instruction을 그대로 worker에 FED exec type으로 보내면 worker가 다시
FED dispatch를 시도할 수 있었다. 또한 `ifelse(local-condition, fed-a, fed-b)`처럼 두 번째와
세 번째 입력이 aligned인 경우 cleanup/dispatch base를 첫 입력으로 고정하면 잘못된 mapping을
사용했다.

1차 20-state campaign은 18 success, 2 runtime failure였다. worker fragment를 CP로 실행하고
실제로 aligned pair를 소유한 mapping을 base로 사용하도록 수정한 local failed-only canary는
2/2 성공했다. 이 결과는 remote failed-only retry와 authoritative union이 끝나기 전까지 최종
251개 합계에는 포함하지 않는다.

### 4.3 AggregateTernary는 독립 P occurrence가 아니라 physical fusion일 수 있음

`sum(A*B*C)`의 shared planner graph에는 `tak+*`가 독립 occurrence로 나타나지 않고
`b(*)`와 `ua(+RC/C)`가 나타난다. `AggUnaryOp.constructLops()`는 조건이 맞으면 이를
`TernaryAggregate`로 fuse하고 `PHYSICAL_TERNARY_AGGREGATE_FUSION` lowering tag를 남긴다.

따라서 runtime class가 존재한다는 이유만으로 `tak+*`를 누락된 독립 selector state라고 단정하지
않는다. 현재 lane은 다음 closed contract를 검증 중이다.

1. forced `ua` occurrence가 exact `tak+*` lowering/runtime receipt로 이어지는가
2. 제거되는 multiply/nary intermediate에 selected movement가 있으면 fusion이 금지되는가
3. shared cost가 physical fusion을 과대 또는 과소 계상하지 않는가

## 5. 진행 중 검증

- Ternary/Nary/Quantile: corrected direct manifest 20개, 1차 18 success + 수정 canary 2 success.
  remote retry, exact union, checksum 전에는 authoritative로 승격하지 않는다.
- CentralMoment/Covariance/Ctable: 한 monolithic fixture의 failure를 `cm`, `cov`, row-ctable,
  other-ctable 네 독립 source/Maven tree로 분리해 `so003`에서 동시 실행 중이다.
- Unary/shape/conversion: matrix lane을 완료한 agent를 즉시 `so004`로 재배치했다. specialized
  runtime class가 실제 관측되지 않은 generic HOP 성공은 해당 class의 R 증거로 세지 않는다.
- MMFED/SPARK: 현재 작은 MapMM fixture는 specialized `MMFEDInstruction` 대신 generic
  `AggregateBinaryFEDInstruction`으로 lowering된다. compiler-owned `chkpoint` audit 모델과
  실제 MapmmSPInstruction을 만드는 sparse large-dimension fixture가 필요하므로 별도 gap이다.

## 6. 다음 승격 조건

각 진행 lane은 다음이 모두 충족될 때만 완료로 계산한다.

1. manifest target 집합과 final-result target 집합이 정확히 동일
2. duplicate/missing/unexpected target 0
3. 모든 성공 target에서 constraint satisfied 및 runtime capability receipt 존재
4. selected/runtime ordered input signature divergence 0 또는 원인별 명시적 판정
5. source/manifest/artifact checksum 검증 PASS
6. targeted regression 및 `git diff --check` PASS
7. `coverageComplete=false`와 독립 `R` 미열거 범위를 보고서에 명시
