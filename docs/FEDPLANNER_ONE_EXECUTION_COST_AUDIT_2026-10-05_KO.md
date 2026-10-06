# Fedplanner one-execution cost 코드 조사 보고서

조사일: 2026-10-05. 목적은 논문의 operation cost model을 수정하기 전에 현재 구현이 한 번의 operator 실행을 어떻게 가격화하는지 확인하는 것이다. 새로운 cost model은 제안하지 않는다.

**핵심 결론:** 현재 구현은 HOP의 FLOPs와 메모리 추정치로 local primitive를 만든 뒤, 일반 FED 경로에서는 그 **primitive 전체를 worker 수로 나눈다**. 일부 operator는 나누지 않거나 output 크기로 비용을 제한한다. 여기에 instruction latency/control, 입력 준비, native result 전송 및 coordinator reduction을 조건별로 더한다. 따라서 최종 분산 비용을 단순히 `local cost / W`로 설명할 수 없으며, 반대로 “partitioned FLOPs와 partitioned input bytes만 각각 W로 나눈다”는 설명도 현재 일반 구현과 다르다. 근거: FCM `computeOpCost` L1194, `computeFederatedComputeCost` L364, EPC `fedCostProjection` L1965.

## 조사 범위와 근거 표기

- **코드:** `/home/mchoi/w1357-paper-aligned-refactor`의 현재 working tree. HEAD는 `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592`이며, HEAD 이후 미커밋 변경을 포함했다. 특히 `ExactPhysicalCostModel`, `PlacementCostSemantics`, shape 분석 관련 변경과 untracked `PlacementCostSizeBounds`가 포함된다. 따라서 HEAD만 체크아웃한 결과와 이 보고서가 완전히 같다고 간주하면 안 된다.
- **논문 비교본:** `/home/mchoi/COFEE_SECTION6_REVIEW_20261001/revised/sections/05-cost-model-and-optimization-objective.tex`. 별도의 최신 원고가 지정되지 않아, 발견한 최근 검토본의 Section 5를 기준으로 했다. 다른 원고 버전에 대한 일치 여부는 **unclear**이다.
- **실제 호출 경로:** `FederatedPlanLocalCost`와 `FederatedPlanExact`가 사용하는 공통 physical cost surface. 과거 `previous/FederatedPlanCostEstimator.java`를 현재 estimator로 취급하지 않았다.
- **검증 방식:** 현재 소스의 호출부, 계산 helper, 설정 로딩부, 관련 테스트 코드를 교차 확인했다. 실행 성능 측정이나 테스트 실행 결과를 주장하는 보고서가 아니다. 코드와 논문은 수정하지 않았다.
- 별도 표시가 없는 계산·분기 설명은 **Evidence: 코드 직접 확인, 신뢰도 높음**이다. 논문 문장에 대한 판정은 인용 코드로부터의 **Inference**이고, 확인되지 않은 사항은 **unclear**로 표시한다.

이하의 `FCM L1194` 같은 표기는 다음 파일의 해당 행과 클래스/메서드를 가리킨다. 행 번호는 조사 시점 working tree 기준이다.

| 별칭 | 파일 및 클래스 |
|---|---|
| FCM | [FederatedCostModel.java](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java) — `FederatedCostModel` |
| CC | [ComputeCost.java](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/cost/ComputeCost.java) — `ComputeCost` |
| PCS | [PlacementCostSemantics.java](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java) — `PlacementCostSemantics` |
| EPC | [ExactPhysicalCostModel.java](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java) — `ExactPhysicalCostModel` |
| EMA | [ExactMaterializationActivation.java](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactMaterializationActivation.java) — `ExactMaterializationActivation` |
| HOP | [Hop.java](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/Hop.java) — `Hop` |
| OPT | [OptimizerUtils.java](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/OptimizerUtils.java) — `OptimizerUtils` |
| MB | [MatrixBlock.java](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/matrix/data/MatrixBlock.java) — `MatrixBlock` |
| PAPER | [Section 5](/home/mchoi/COFEE_SECTION6_REVIEW_20261001/revised/sections/05-cost-model-and-optimization-objective.tex) |

## A. Overall cost pipeline

### A.1 실제 호출 순서

```text
selected operator alternative / compiled HOP occurrence
  │  ExecType, FType, ordered input states, output kind, emission facts
  ▼
PlacementAnalysis metadata
  │  HOP rows/cols/nnz + abstract shapes + sparse assignment estimates
  │  + cost-only size bounds + execution frequency
  ▼
PlacementCostSemantics.analysisAwareUnitLocalCost
  │  effective Din/Dout + latent runtime-kernel floor
  ▼
FederatedCostModel.computeOpCostWithFallback → computeOpCost
  │  ComputeCost.getHOPComputeCost → F
  │  throughput selection + WDivMM/function floors
  │  max(compute time, input memory time) + output memory time
  ▼
CP: local cost
FED: scaling/exception → native aggregate/indexing cap
     → fixed dispatch → input preparation → optional function penalty
     → native result download/reduction when required
  ▼
operator unary factors
  + separately constructed native-input / movement / retained-copy factors
  ▼
canonical physical-plan objective
```

`FederatedPlanLocalCost.rewriteProgram`은 physical model을 만들고 공통 cost surface를 생성한 뒤 optimizer를 호출한다. Exact도 같은 surface를 사용한다. 근거: [FederatedPlanLocalCost.java L53](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java:53), [FederatedPlanExact.java L62](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanExact.java:62).

primitive 연결은 EPC `unitLocalCost` L2045 → PCS `analysisAwareUnitLocalCost` L397 → FCM `computeOpCostWithFallback` L1378 → FCM `computeOpCost` L1194 → CC `getHOPComputeCost` L52이다.

### A.2 한 번의 실행과 전체 objective의 구분

이 보고서의 수식은 `executionWeight = 1`로 놓은 계산이다. 일반 CP contribution은 EPC `cpUnaryCost` L2162에서 `executionWeight × unitLocalCost`로 만든다. FED fixed stage, input preparation, result phase에도 실행 weight가 적용된다. 단, 함수 placeholder의 single-worker penalty에는 별도의 반복 횟수 규칙이 있다.

**최종 one-execution cost를 반환하는 단일 함수가 모든 비용을 독점하지 않는다.** EPC `addPhysicalUnaryFactor` L814는 다음을 별도 factor로 만든다.

| 선택 상태 | unary 구성 |
|---|---|
| CP/LOUT | CP execution |
| CP/FOUT | CP execution + result upload |
| native FED/FOUT | FED execution; native LOUT result phase는 붙이지 않음 |
| native FED/LOUT | FED execution + native result download/coordinator phase |
| derived FED/FOUT | FED execution + native local result phase + 후속 result upload |

입력 공급에 필요한 비용 일부는 EPC `addPhysicalNativeLocalInputTransferFactors` L1520, 재사용 가능한 이동은 `addPhysicalCompiledTransferFactors` L889, 함수 경계 이동은 `addPhysicalLogicalFunctionFactors` L1851에서 추가한다. 따라서 `fedUnaryCost` 하나만 보고 operator 주변의 모든 실행 비용을 읽었다고 할 수 없다.

## B. Operator table

### B.1 표기와 분류

아래는 cost estimator가 분기하는 HOP class이다. 이 표에 있다고 해서 모든 opcode 또는 모든 FED layout 조합이 runtime에서 지원된다는 뜻은 아니다. 실행 가능성은 candidate/placement 분석의 별도 책임이다.

```text
N(h) = max(h.getDim1(), 1) * max(h.getDim2(), 1)
Nout = N(operator)
I(h) = 아래 B.3에서 정의하는 effective input-memory bytes
O(h) = 아래 B.3에서 정의하는 effective output-memory bytes
ρg   = generic FLOPs/s
ρmm  = matrix-valued AggBinaryOp FLOPs/s
μ    = 공통 memory bandwidth
```

`N(h)`는 CC `getSize` L258의 코드 그대로다. 일반 `F`에는 원래 HOP의 차원을 사용한다. PCS가 occurrence-aware shape로 bytes를 보정해도 **일반 FLOPs 식의 차원까지 일괄 교체하지는 않는다**. latent WDivMM supplemental floor는 예외이다.

| Operator class | F | Input bytes | Output bytes | Calibration | Additional stages |
|---|---|---|---|---|---|
| `AggBinaryOp`, MatMul/MatVec | `2 × A.cols × Nout`; `A.dimsKnown(true)`이면 `× sparsity(A)` | `I(h)`: 양쪽 입력 및 공통 중복 처리 | `O(h)`; HOP의 matmul output 추정은 `nnz==0` 외에는 dense | matrix output이면 `ρmm`, 공통 `μ` | ROW-left full broadcast, sliced preparation, native result bind 또는 partial-add reduction. CC L222; FCM L636 |
| `AggUnaryOp` | aggregate coefficient × direction multiplier × `Nout` | `I(h)` | `O(h)`; native result phase에는 scalar/row/column 결과 크기를 별도로 산출 | `ρg`, `μ` | native FOUT reduced-output cap; LOUT partial return와 coordinator aggregation. CC L229; FCM L411, L754 |
| `UnaryOp` | `c_unary × Nout` | `I(h)` | `O(h)`; HOP output shape/nnz | `ρg`, `μ` | 일반 FED scaling/dispatch 적용. CC L54 |
| `BinaryOp` | `c_binary × Nout` | `I(h)`; matrix/scalar 입력도 공통 경로 | `O(h)`; opcode별 sparsity 추정 가능 | `ρg`, `μ` | native-local operand upload가 별도 factor로 필요할 수 있음. CC L95; EPC L1672 |
| `TernaryOp` | `c_ternary × Nout` | `I(h)` | `O(h)` | `ρg`, `μ` | PLUS_MULT/MINUS_MULT/IFELSE/MAP는 FED compute `/W` 제외. CC L139; FCM L354 |
| `NaryOp` | MIN/MAX/PLUS는 `input_count × Nout`; 나머지 `Nout` | `I(h)` | `O(h)` | `ρg`, `μ` | `isCellOp()`이면 FED compute `/W` 제외. CC L163; FCM L347 |
| `ParameterizedBuiltinOp` | RMEMPTY는 `max(N(target),Nout)`; 그 외 `Nout` | `I(h)` | `O(h)` | `ρg`, `μ` | RMEMPTY는 작아진 output만으로 F를 계산하지 않음. CC L167 |
| `IndexingOp` | 기본 `Nout` | 기본 `I(h)`; 최종 slice cap에서는 slice bytes | `O(h)`와 indexing bound | `ρg`, `μ` | CP/FED 모두 slice-payload cost로 제한 가능; FED compute `/W` 제외. CC L183; FCM L531, L557 |
| `ReorgOp` | `Nout` | `I(h)` | `O(h)` | `ρg`, `μ` | transpose는 `/W` 제외; FULL transpose의 fixed dispatch 면제. CC L186; FCM L295, L351 |
| `DnnOp` | BIASADD/BIASMULT `2Nout`; 그 외 기본 `Nout`와 warning | `I(h)` | `O(h)` | `ρg`, `μ` | 별도 convolution FLOP 식은 이 switch에 없음. CC L189 |
| `QuaternaryOp` | WSLOSS/WDIVMM/WCEMM `4N(input0)`; WSIGMOID/WUMM `3N(input0)` | `I(h)` | `O(h)`; WSLOSS/WCEMM scalar는 8 bytes | `ρg`, `μ` | WDivMM rank-aware floor, factor broadcast/slicing, partial reduction. CC L200; FCM L1229, L838 |
| DML `FunctionOp` | 기본 `Nout`와 distinct input/output logical-cell floor의 max | `I(h)` | `O(h)` | `ρg`, `μ` | placeholder floor; 조건부 single-worker function-boundary penalty. FCM L1280, L1113 |
| transient `DataOp`, function input/output boundary | 최종 operator self-cost 0 | forwarding metadata | forwarding metadata | 해당 self-cost 없음 | 필요한 물리 이동만 별도 factor. PCS L412; EPC L819 |
| 기타 HOP class / 미구현 opcode | 기본 coefficient 1, 즉 `Nout`; 일부 switch는 warning | `I(h)` | `O(h)` | 통상 `ρg`, `μ` | 정교한 opcode model이 있다는 의미가 아님. CC L53, L249 |

**MatMul과 MatVec는 별도의 calibration class로 구분되지 않는다.** 둘 다 AggBinary 분기를 사용하며, matrix-valued AggBinary 여부로 throughput을 고른다. “elementwise”도 단일 FLOP class가 아니라 Unary/Binary/Ternary/Nary의 여러 분기로 구현된다. 근거: CC `getHOPComputeCost` L52, FCM `getComputeFlopsPerSec` L1269.

### B.2 정확한 arithmetic coefficients와 operator-specific quantity

CC의 `costs`는 기본값 1이며, 마지막에 `Nout`을 곱한다. 미구현 enum을 모두 exception으로 거부하지 않는다.

| 계열 | opcode별 coefficient |
|---|---|
| Unary, CC L54 | ABS/ROUND/CEIL/FLOOR/SIGN, NROW/NCOL, PRINT/ASSERT, 코드에 열거된 CAST, CUMSUM/CUMMIN/CUMMAX/CUMPROD: 1; SPROP/SQRT/CUMSUMPROD: 2; EXP/SIN: 18; SIGMOID: 21; COS: 22; LOG/LOG_NZ: 32; ATAN/TANH: 40; TAN: 42; ASIN/SINH: 93; ACOS/COSH: 103 |
| Binary, CC L95 | MULT/PLUS/MINUS/MIN/MAX/AND/OR/비교/CBIND/RBIND: 1; INTDIV: 6; MODULUS: 8; DIV: 22; LOG/LOG_NZ: 32; POW: 지수가 literal 2이면 1, 아니면 16; MINUS_NZ/MINUS1_MULT: 2; COV: 23 |
| Binary MOMENT, CC L121 | literal type 0/1/2/3/4/5에 각각 1/8/16/31/51/16. nonliteral이면 type 2 |
| Ternary, CC L139 | IFELSE/PLUS_MULT/MINUS_MULT: 2; CTABLE: 3; COV: 23 |
| Ternary MOMENT, CC L145 | literal type 0/1/2/3/4/5에 각각 2/9/17/32/52/17. type은 코드상 input index 1에서 읽으며 nonliteral이면 type 2 |
| AggUnary, CC L229 | SUM: 4; SUM_SQ: 5; MIN/MAX: 1; 다른 opcode는 warning 후 기본 1 |

AggUnary의 방향별 식은 다음과 같다.

```text
Col:    F = c * max(input.rows, 1) * Nout
Row:    F = c * max(input.cols, 1) * Nout
RowCol: F = c * N(input) * Nout
```

정상적인 aggregate output shape에서는 SUM이 입력 cell당 4 FLOPs인 추정이다. `F=Ninput`으로 바꾸면 코드와 다르다. `MEAN` 등에 대해서도 별도의 정교한 coefficient가 있다고 추정하면 안 된다. 근거: CC L229–249.

AggBinary는 좌측 입력 sparsity만 F에 반영한다. 우측 sparsity, 실제 BLAS kernel 선택, transpose/MatVec 전용 throughput을 이 식에서 별도로 반영하지 않는다. `A.cols` 자체에는 `max(...,1)` 보정이 없다. 일반 shape가 미해결이면 이 항이 부정확하거나 음수가 될 수 있고, FCM에서 floor와 memory term으로 후속 보정된다. 근거: CC L222–227; FCM L1196–1205.

WDivMM 추가 연산량은 다음 코드와 같다.

```text
cells = logicalCellCount(weights, effectiveMemory(weights))
vRank = underlying(V).cols if V is transpose with input else V.cols
rank  = positive U.cols
        else positive vRank
        else positive output.cols
        else 1

rankFloor = 4 * rank * cells    if cells>0 and rank>1
            0                  otherwise
F = max(genericF, rankFloor)
```

이 floor는 `nnz(weights)`가 아니라 logical cell 수를 사용한다. latent WDivMM에는 `1000 × 4 × rows × cols × rank / ρg`라는 compute-time floor도 있다. 원래 source HOP가 AggBinary라도 이 supplemental floor는 WDivMM의 generic throughput을 사용한다. 근거: FCM `estimateWdivmmRankAwareComputeFloor` L1229, `computeWdivmmRankAwareComputeTimeFloor` L1248, `estimateLogicalCellCount` L1304; PCS `latentWdivmmComputeTimeFloor` 관련 경로.

DML FunctionOp floor는 distinct input Hop ID별 logical cells와 output logical cells의 합에 1 FLOP/cell을 곱한다. scalar는 1 cell, known shape는 rows×cols, unknown shape는 effective memory/per-cell fallback이다. 이는 함수 본문의 모든 실제 operator FLOPs를 재구성한 값이 아니라 placeholder floor이다. 근거: FCM `estimateDmlFunctionOpComputeFloor` L1280.

### B.3 Input/output bytes, shape, sparsity

**기본 operation bytes는 HOP의 in-memory footprint 추정치이다.** 항상 `8rc` 또는 `8nnz`인 순수 payload가 아니며, network helper에 넘기는 bytes와도 항상 동일하지 않다.

공통 memory-size helper는 다음과 같이 요약된다.

```text
Size(r,c,s):
    nnz = (long)(s*r*c)
    if MatrixBlock.evalSparseFormatInMemory(r,c,nnz):
        return MatrixBlock header
             + sparse-block estimated storage (s=0이면 미할당 storage=0)
    else:
        return MatrixBlock header
             + DenseBlockFactory.estimateSizeDenseInMemory(r,c)
```

근거: OPT `estimateSizeExactSparsity` L843 → MB `estimateSizeInMemory` L2785, `estimateSizeDenseInMemory` L2809, `estimateSizeSparseInMemory` L2835. representation의 object/array overhead도 포함되므로 dense/sparse를 각각 `8rc`/`8nnz`로 치환하지 않았다.

**Input bytes I(h)** — FCM `getEffectiveInputMemEstimate` L1414:

```text
1. raw = hop.getInputMemEstimate()
2. raw>0이고 unknown-memory sentinel로 보이지 않으면 raw 사용
3. 아니면 모든 입력의 effective output-memory bytes를 합산
   동일 input 객체가 반복될 때 >1 MiB인 입력만 중복 제외
4. 위 합이 positive이면 사용
5. unknown output dimensions 등 해당 조건이면 raw를
   configuredUnknownFallback * inputCount 범위로 제한
6. 마지막으로 injected per-cell 기반 Hop input estimate 사용
```

원래 HOP input-size 합산에도 큰 중복 입력을 제외하는 규칙이 있다. scalar/작은 입력 또는 parameter HOP를 estimator가 모두 무시하는 것이 아니다. 근거: HOP `getInputSize` L786; FCM L1425–1447.

**Output bytes O(h)** — FCM `getEffectiveOutputMemEstimate` L1450:

```text
1. semantic sparse-assignment estimate가 있으면 우선
2. multi-return builtin output estimate가 있으면 사용
3. raw Hop output memory estimate에서 출발
4. transient-read의 concrete source / direct FED source 추정으로 교정 가능
5. unknown-shape elementwise면 input-derived upper bound 적용
6. 필요한 경우 indexing bound 또는 per-cell fallback
7. unknown dimensions이면 descendant/input/configured fallback으로 clamp
```

구체적인 unknown-size 처리는 FCM `clampUnknownDimOutputMemEstimate` L1895에 있다. 기본 unknown transfer fallback은 256 MiB, upload clamp ratio는 4, unknown-size sentinel 판별 기준은 약 8 GiB이다. 이 값들은 실제 shape를 측정한 것이 아니라 소스에 명시된 fallback이다.

**Occurrence-aware override** — PCS `analysisAwareInputBytes` L420, `analysisAwareOutputBytes` L449:

```text
unknown-dimensional matrix output:
    semantic sparse estimate
    else occurrence sparse estimate
    else exact abstract (rows,cols):
        known nnz → Size(rows,cols,min(1,nnz/rows/cols))
        unknown nnz → dense Size(rows,cols,1)
    else cost-only dimension upper bounds → dense memory envelope

input:
    compiled producer occurrence를 찾아 unknown matrix input bytes를 위 값으로 교체
    >1 MiB의 같은 producer occurrence는 중복 제외
    실제 교체가 하나라도 있고 total>0일 때만 Din override
```

cost-only bounds는 정확한 shape로 승격되지 않는다. PCS `boundedDenseOutputBytes` L527과 [PlacementCostSizeBounds.java L43](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSizeBounds.java:43)가 이 경계를 명시한다.

operator별 중요한 output-size 차이는 다음과 같다.

| Operator | Output-memory 산출 |
|---|---|
| AggBinary | `nnz==0 ? sparsity 0 : sparsity 1`; matmul 결과를 보수적으로 dense 취급. [AggBinaryOp.computeOutputMemEstimate L288](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/AggBinaryOp.java:288) |
| Unary / AggUnary | output shape와 nnz에서 sparsity를 구해 representation size 산출. GPU 조건에는 dense 분기가 있지만, 현재 보고서의 실행 위치는 CP/FED이다. [UnaryOp L374](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/UnaryOp.java:374), [AggUnaryOp L249](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/AggUnaryOp.java:249) |
| Binary | known nnz 사용. unknown이면 literal sparse-safe rule 또는 `getBinaryOpSparsity(sp1,sp2,op,!outer)`. [BinaryOp.computeOutputMemEstimate L549](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/BinaryOp.java:549) |
| Quaternary | WSLOSS/WCEMM scalar는 8 bytes; WSIGMOID/WDIVMM/WUMM은 shape/nnz의 representation size. [QuaternaryOp.computeOutputMemEstimate L761](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/QuaternaryOp.java:761) |
| Row-argmin assignment 특례 | unknown nnz의 indicator 및 rowSum-normalized assignment, transpose 등에 대해 원래 row당 1개 nonzero를 가정. 동률 실제 개수는 미측정. FCM `getSemanticSparseAssignmentShape` L1539 및 주석 L1519 |

Binary의 일반 worst-case sparsity 예시는 `PLUS/MINUS/... → min(1,s1+s2)`, `MULT/AND → min(s1,s2)`, `DIV → min(1,s1+1-s2)`, `MODULUS/POW/MINUS_NZ/LOG_NZ → s1`, 나머지는 1이다. outer의 non-worst-case 분기는 PLUS/MINUS에 `1-(1-s1)(1-s2)`, MULT에 `s1*s2`를 사용한다. 근거: OPT `getBinaryOpSparsity` L1324. 따라서 sparsity 보정은 operator마다 같지 않다.

**Memory와 wire size의 경계:** semantic sparse assignment의 operation memory는 `estimateSizeExactSparsity`, serialized size는 `MatrixBlock.estimateSizeOnDisk`로 다르게 산출한다. 그러나 모든 transfer가 항상 정확한 serialized size를 사용하는 것은 아니다. FCM `getEffectiveUploadMemEstimate` L1685와 EPC `effectiveOutputBytes` L2052, `effectiveUploadBytes` L2064, `estimatedBytes` L2218에는 in-memory/fallback estimate도 등장한다. “모든 network D는 실제 serialized byte count”라는 보편적 주장은 확인되지 않는다.

HOP에 `computeIntermediateMemEstimate`가 있어도 FCM의 primitive는 intermediate bytes를 독립 항으로 읽지 않는다. 실제 인자는 F, input bytes, output bytes, supplemental floor이다. 근거: FCM L1194–1210.

## C. Local execution

### C.1 Coordinator CP의 실제 식

아래는 새 모델이 아니라 FCM `computeOpCost` L1194의 직접적인 전사이다. bandwidth는 코드처럼 bytes를 `2^20`으로 나눈 뒤 사용하며, 최종 단위는 ms이다.

```text
memoryMs(D) = 0                          if D <= 0
              1000 * (D / 2^20) / μ     otherwise

F = max(ComputeCost.getHOPComputeCost(h), WDivMM_rank_floor(h))
if h is DML FunctionOp:
    F = max(F, DML_function_cell_floor(h))

ρ(h) = ρmm if h is matrix-valued AggBinaryOp else ρg
computeMs = max(1000 * F / ρ(h), max(0, supplementalWDivmmTimeFloor))

primitive = max(computeMs, memoryMs(Din)) + memoryMs(Dout)
```

근거: [FCM.computeOpCost L1194](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java:1194), `getComputeFlopsPerSec` L1269, `computeMemoryAccessCost` L1408.

추가 보정은 다음과 같다.

- primitive가 positive가 아니고 effective bytes가 있으면 `memoryMs(Din)+memoryMs(Dout)`로 fallback한다. bytes도 없으면 0이 될 수 있다. FCM `computeOpCostWithFallback` L1378.
- matrix rightIndex는 `sliceCost=max(1000×sliceCells/ρ, memoryMs(sliceBytes))+memoryMs(sliceBytes)`와 default cost의 min을 사용한다. default가 nonpositive이면 sliceCost를 사용한다. FCM `computeLocalIndexingCostWithFallback` L557, `computeIndexingSlicePayloadCost` L570.
- transient read/write와 runtime rewrite로 제거되는 WDivMM intermediate는 self-cost 0이다. PCS `analysisAwareUnitLocalCost` L397.
- CP output을 FOUT으로 만드는 선택은 이 local kernel 식에 upload를 흡수하지 않고 별도 result-upload factor를 추가한다. EPC `addPhysicalUnaryFactor` L839, `physicalResultUploadCost` L880.

### C.2 Calibration parameter의 존재, 저장, 사용

FCM L119–239에서 다음 값들을 선언하고 class initialization 시 `private static final double`로 캡처한다. key는 모두 `SYSDS_FED_COST_` 접두사를 가진다.

| 값 | Key suffix | 코드 기본값 | 실제 사용 |
|---|---|---|---|
| Generic compute throughput | `FLOPS` | `2×1024^3 = 2,147,483,648` FLOPs/s | matrix AggBinary 이외의 compute, WDivMM time floor |
| Matrix AggBinary throughput | `AGGBINARY_FLOPS` | 명시값이 없으면 `max(ρg, 32×10^9)` | MatMul/MatVec를 포함한 matrix AggBinary |
| Memory bandwidth | `MEM_BW` | `25000` | input/output memory time 공통 |
| Generic network bandwidth | `NET_BW` | `125` | 공통 및 directional fallback |
| C2W / W2C network bandwidth | `NET_BW_C2W`, `NET_BW_W2C` | generic network 값 상속 | 방향별 transfer |
| Generic combined serdes bandwidth | `NET_SERDES_BW` | `0` | 0이면 serdes 항 비활성 |
| C2W / W2C serdes bandwidth | `NET_SERDES_BW_C2W`, `NET_SERDES_BW_W2C` | generic serdes 상속 | upload / 일반 download |
| Native/in-band W2C serdes | `INBAND_RESULT_SERDES_BW_W2C` | W2C serdes 상속 | native result, 작은 reusable GET response |
| Reusable GET fast-path threshold | `REUSABLE_GET_VAR_FAST_MAX_MB` | `4`, 내부에서 `×2^20` bytes | response별 codec-rate 선택 |
| Network latency | `NET_LATENCY` | `0.001` seconds | logical instruction/transfer fixed stage |
| Coordinator/runtime control | `LOCAL_TO_FED_CTRL_MS` | `0` ms | latency와 별도인 fixed control stage |

변수 이름은 MB/s를 쓰지만 실제 byte 변환은 `2^20`이다. 논문에 단위를 기재할 때 decimal MB로 자동 치환하면 소스와 달라진다. serdes는 serialization과 deserialization을 별도 두 계수로 나누지 않고 combined throughput으로 표현한다. 근거: FCM L125의 주석, `computeDirectionalNetworkCost` L2142.

**고정/minimum kernel cost 판정:** 전체 kernel class마다 측정해 저장하는 일반적인 `minimumKernelCost` 파라미터는 확인되지 않는다. 실제 존재하는 것은 다음 세 종류다.

1. WDivMM의 shape/rank-dependent FLOP floor 및 supplemental compute-time floor.
2. DML FunctionOp의 logical-cell-dependent FLOP floor.
3. FED instruction의 latency + control fixed stage.

이들은 동일한 의미의 kernel launch minimum이 아니다. 특히 1, 2는 입력 크기와 rank에 따라 달라진다. 근거: FCM L1194, L1229, L1248, L1280, `computeFixedFederatedInstructionStageCost` L274.

**로딩 우선순위와 저장:** [FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment L42](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/conf/FederatedPlannerConfiguration.java:42)는 nonempty Java system property → environment → default 순이다. 문자열 parsing 실패 시 default를 사용한다. class 초기화 이후 매 operator마다 다시 측정하거나 worker별 capability를 조회하지 않는다. parse 가능한 숫자의 positivity/finite 여부가 acquisition 단계에서 일괄 검증되는 구조도 아니다.

**측정과 외부 profile은 구분해야 한다.** 현재 checkout의 [freeze_campaign_conditions.py의 network_cost L312](/home/mchoi/w1357-paper-aligned-refactor/scripts/fedplanner/freeze_campaign_conditions.py:312)는 profile의 방향별 Mbit/s와 RTT를 변환하고 다음 값을 configuration으로 생성한다.

```text
directional network BW = directional_mbit / 8
generic network BW = harmonic_mean(c2w_mbit,w2c_mbit) / 8
network latency = rtt_ms / 1000
MEM_BW = 25000
FLOPS = 2147483648
SERDES_BW / SERDES_BW_C2W = 210
SERDES_BW_W2C = 14.7
LOCAL_TO_FED_CTRL_MS = 0.35
```

이는 값 생성/고정 코드이며 자체 microbenchmark fitting 코드가 아니다. [run_current_pe_cell.py L359](/home/mchoi/w1357-paper-aligned-refactor/scripts/fedplanner/run_current_pe_cell.py:359)는 frozen `planned["network"]["cost_environment"]`를 로딩하고 Java 실행 환경에 전달한다. 특정 실행 profile을 지정받지 않았으므로 어떤 실험 프로세스에 최종 적용된 숫자인지는 **unclear**이다. 위 default와 script literal을 임의로 하나의 “현재 측정값”으로 합치면 안 된다.

역사적 [SESSION_ISSUES_2026-02-25.md L43](/home/mchoi/w1357-paper-aligned-refactor/docs/SESSION_ISSUES_2026-02-25.md:43)에는 210을 관측된 KMeans transfer hotspot에서 도출했다는 기록과 tc/iperf 관련 언급이 있다. 그러나 그 문서가 참조하는 `experiments` 디렉터리는 이 checkout에 없다. 현재 모든 계수의 재현 가능한 측정/회귀 과정, 특히 14.7의 측정 provenance는 **unclear**이다. [freeze_microbench_conditions.py L27](/home/mchoi/w1357-paper-aligned-refactor/scripts/fedplanner/freeze_microbench_conditions.py:27)는 network profile을 명시적으로 `microbench-unshaped-5gbit-assumed`라고 부른다.

### C.3 Coordinator와 single worker의 차이

| 항목 | Coordinator CP | Single-worker FED, estimator W=1인 경우 |
|---|---|---|
| Compute/memory calibration | 공통 ρg/ρmm/μ | 동일 값; worker 전용 ρ/μ 없음 |
| 기본 F와 memory bytes | 위 local primitive | 먼저 동일 primitive를 계산; `/max(1,W)`는 효과 없음 |
| FED operator-specific adjustment | 없음 | aggregate/indexing cap 등 적용 가능 |
| Dispatch/control | FED stage 없음 | 보통 latency + control 1회 |
| Local operand preparation | local input memory access | 필요 시 full/sliced upload |
| 결과 | local output write | FOUT이면 remote 유지; LOUT이면 payload return 및 경우에 따라 coordinator 처리 |
| 별도 penalty | 없음 | 특정 DML FunctionOp에만 아래 penalty |

근거: FCM `getComputeFlopsPerSec` L1269, `computeFederatedComputeCost` L364; EPC `fedCostProjection` L1965.

single-worker penalty는 FCM `computeSingleWorkerFedExecPenalty` L1113에 있다. `W<=1`, DML FunctionOp, `controlMs>10`일 때만 고려한다. 한 번 실행하고 concrete FED matrix input이 있으면 0이다. 그런 input이 없으면 `max(1, distinct non-scalar boundary input count) × controlMs`를 추가한다. 일반 MatMul, unary, binary마다 별도 single-worker penalty가 있는 것은 아니다.

즉 CP와 single worker는 **calibration parameter만 바뀌는 구조가 아니다**. 오히려 compute/memory parameter는 같고, layout-dependent 계산 보정과 실행/통신 stage가 달라진다. 단, 실제 계산에 전달되는 W가 operator의 물리 worker 수와 항상 같은지는 D.1의 제한이 있다.

## D. Distributed execution

### D.1 코드에서 W가 의미하는 것

EPC `physicalCostSurface` L432는 `workerCount(analysis.graph())`를 구해 unary 실행 비용으로 전달한다. `workerCount` L2209는 **graph의 모든 node, 모든 anchor, 모든 partition에 등장하는 canonical worker address의 합집합 크기**다.

```text
W = count(unique canonical worker addresses over all graph anchors)
```

따라서 일반 operator compute의 W를 “해당 operator가 실제 선택한 participating worker pool의 정확한 크기”라고 단정할 수 없다. source materialization에는 EPC `realizationWorkerCount` L1733, native-local input upload에는 `nativeLocalInputWorkerCount` L1784 등 더 구체적인 pool 계산이 있지만, generic unary compute 경로는 graph-wide W를 받는다.

FULL은 BROADCAST와 동일하지 않다. [FTypes.java L53](/home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/FTypes.java:53)에서 FULL은 unpartitioned/nonreplicated, BROADCAST는 replicated, PART는 overlapping partial이다. 그런데 EPC `broadcastOnlyMatrixInputs` L2031은 null/local과 BROADCAST만 broadcast-only로 인정하고 FULL은 인정하지 않는다. 여러 worker pool이 공존하는 graph의 FULL execution이 자동으로 compute W=1로 교정된다고 설명하면 안 된다.

### D.2 One-execution FED 계산 pseudocode

다음은 EPC `fedCostProjection` L1965와 FCM helper를 executionWeight=1로 요약한 것이다.

```text
base = unitLocalCost(h)

if proven partitioned latent WDivMM runtime input:
    workerCost = base / max(1,W)
else if unscaledFamily(h, selectedInputFTypes):
    workerCost = base
else:
    workerCost = base / max(1,W)

workerCost = nativeAggregateUnaryOutputCap(h, executionFType, workerCost)
workerCost = nativeIndexingSliceCap(h, executionFType, workerCost)

fixed = ordinary FED instruction ? latencyMs + controlMs : 0
mixed = analysisAwareMixedFedLocalCost(...)

fedUnary = workerCost
         + fixed
         + mixed.inputPreparationCost
         + applicableSingleWorkerFunctionPenalty

resultPhase = generic download(outputUploadBytes)
resultPhase = native AggUnary/AggBinary/latent-kernel override if applicable
if mixed.hasCoordinatorPhase():
    resultPhase = mixed.partialResultDownloadCost + mixed.coordinatorLocalCost

native FED/FOUT:  unary contribution = fedUnary
native FED/LOUT:  unary contribution = fedUnary + resultPhase
derived FED/FOUT: execution contribution = fedUnary + resultPhase
                  add separate output upload
```

native-local input 공급에 필요한 추가 factor는 A.2와 E에 설명했다. 이 비용이 모두 fedUnary에 포함되는 것은 아니다.

unscaledFamily는 FCM의 shouldUseUnscaledFederatedComputeCost L342에서 다음 조건으로 판별한다.

- 모든 matrix input이 local/null 또는 BROADCAST인 경우.
- Ternary PLUS_MULT, MINUS_MULT, IFELSE, MAP.
- Nary cell op.
- IndexingOp.
- transpose ReorgOp.

이 분기들도 후속 native output/slice cap을 받을 수 있다. partitioned latent WDivMM의 예외는 PCS의 analysisAwareFederatedComputeCost L665에 있다.

### D.3 무엇을 병렬화한다고 계산하는가

| 질문 | 현재 코드의 답 | 근거 |
|---|---|---|
| FLOPs를 W로 나누는가? | worker별 F를 재계산하지 않는다. 일반 분기는 계산된 base 전체를 W로 나누므로 compute time도 결과적으로 축소된다 | FCM L364 |
| Partitioned input bytes를 W로 나누는가? | 입력별 bytes를 분할하지 않는다. 일반 분기에서는 base 안의 input-memory time 전체가 축소된다 | FCM L1194, L364 |
| Replicated input은 각 worker가 전체 크기를 읽는다고 과금하는가? | 모든 입력이 local/BROADCAST이면 unscaled. partitioned operand와 replicated operand가 혼재할 때 replicated read만 전체 크기로 유지하는 식은 없다 | EPC L2031; FCM L342 |
| Output bytes를 W로 나누는가? | 일반 worker primitive의 output write도 base/W에 포함된다. native result payload에는 별도의 layout/partial 규칙이 적용된다 | FCM L364, L774, L1045 |
| Output은 partitioned인가, replicated인가? | 선택된 FType/emission에 따라 다르다. ROW/COL 결과, FULL/BROADCAST, 각 worker가 full-sized partial을 반환하는 집계를 구분한다 | EPC L847; FCM L487, L737 |
| Worker runtime을 average/max/sum 중 무엇으로 계산하는가? | worker별 runtime을 열거하지 않는다. base/W라는 균등분할 근사이며 명시적인 max/average/sum 연산은 없다 | FCM L364 |
| Network critical path는 max인가? | 주석은 largest worker path라고 설명하지만 계산값은 totalBytes/fanIn이다. 실제 최대 partition bytes를 구하지 않는다 | FCM L1067, L2202 |
| Dispatch/control overhead가 있는가? | 보통 logical request batch 하나에 latency+control을 부과한다. W배 하지 않는다 | FCM L262, L274, L600 |
| Coordinator reduce/assemble 비용이 있는가? | AggUnary, additive MatMul, 해당 WDivMM에는 reduce 비용이 있다. 일반 MatMul의 row-binding에는 독립적인 정량 비용이 없다 | FCM L737, L786, L1091; L479의 주석 |

**중요한 한계:** base/W에는 replicated operand의 memory read와 output write도 포함된다. 따라서 “partition-parallel인 항만 엄밀히 분리해 나눈다”는 설명은 현재 일반 구현과 맞지 않는다. 이는 코드에서 관측된 계산 방식이며, 여기서 수정식을 제안하지 않는다.

### D.4 Native output에 따른 compute cap

matrix AggUnary가 다음 조건에 해당하면 FCM의 computeNativeFederatedAggregateUnaryCost L411은 input scan을 포함한 기본 비용을 reduced-output 비용으로 제한한다.

- FULL/BROADCAST.
- ROW layout이면서 Row 방향 aggregate.
- COL layout이면서 Col 방향 aggregate.

~~~text
reduced = max(1000 * resultCells / ρ(h), memoryMs(resultBytes))
          + memoryMs(resultBytes)
workerCost = min(defaultWorkerCost, reduced)   // reduced>0인 경우
~~~

scalar output에는 이 cap을 적용하지 않는다. 이는 input-size roofline에서 자동으로 도출되는 식이 아니라 코드에 명시된 추가 분기다. native Indexing에도 min(defaultWorkerCost,sliceCost)가 있다. 근거: FCM L411–443, L531–543.

### D.5 Partial result와 coordinator reduction

아래에서 m은 full partial/result 하나의 bytes, n은 그 logical cell 수, b는 W2C bandwidth, σin은 in-band result serdes bandwidth다. serdes가 nonpositive이면 해당 항을 생략한다.

~~~text
inBandReturn(totalBytes, fanIn)
  = 1000 * ((totalBytes/max(1,fanIn))/2^20/b
            + enabled((totalBytes/max(1,fanIn))/2^20/σin))

reduce(m,n,W):
    if W<=1: return memoryMs(m)
    return max(1000*(W-1)*n/ρ(h), memoryMs(W*m)) + memoryMs(m)

coordinatorPhase = partialReturn + reduce
cleanupExtraFixed = 0
~~~

근거: FCM의 computeParallelInBandResultPayloadCost L1067, computeCoordinatorAggregationCost L1091, computeAggregateUnaryCoordinatorAggregationCost L786, computeLocalAggregationCleanupControlCost L1081. coordinator addition도 getComputeFlopsPerSec(hop)를 사용하므로 matrix AggBinary의 partial-add에는 ρmm가 적용된다. 별도로 측정한 reduction throughput은 없다.

**Aggregate unary.** result cell 수는 RowCol/scalar이면 1, Row이면 rows, Col이면 cols이다. 결과 HOP shape를 우선하고 입력 축 또는 bytes로 fallback한다. result bytes는 이 cell 수에 injected per-cell size를 곱한다. 일반 FP64는 8 bytes/cell이다. 근거: FCM의 estimateAggregateUnaryResultMemEstimate L1322, estimateAggregateUnaryResultCellCount L1332.

~~~text
q = 1   if FULL/BROADCAST or layout-axis-preserving ROW/Row, COL/Col
    W   otherwise
partialReturn = inBandReturn(q*m, W)
coordinatorReduce = reduce(m,n,W)
~~~

근거: FCM의 estimateNativeAggregateUnaryPayloadFanIn L501, computeAggregateUnaryPartialResultDownloadCost L774.

**주의:** FULL/BROADCAST에서 q=1인 경우에도 return helper의 fanIn에는 W를 전달한다. W>1이면 critical payload는 m/W가 되며, 일반 FULL download의 fanIn=1 규칙과 다르다. coordinator reduction도 q가 아닌 W를 사용한다. 보고서에서는 이 분기를 물리 worker 수와 일치하도록 임의로 보정하지 않았다.

**MatMul additive partials와 WDivMM local aggregation.** 각 worker가 output-sized full partial을 만든다고 계산하므로 partialReturn은 inBandReturn(W*m,W)이다. wire/codec critical payload는 m이고, coordinator는 W개 partial을 읽으며 (W-1)*n addition을 수행한다고 가격화한다. 근거: FCM의 computePartialAggregationCost L737, computeReplicatedWorkerResultDownloadCost L1039.

MatMul의 additive 분기는 다음 코드 조건이다.

~~~text
(right == ROW && left != FULL && left not in {ROW, PART})
or left == COL
~~~

ROW/PART-left에서 오른쪽이 BROADCAST가 아니면 full-broadcast input-preparation 분기를 먼저 선택한다. WDivMM은 left-variant/ROW 또는 right-variant/COL에서 local aggregation을 계산한다. 근거: FCM의 computeMixedFedLocalCost L636, requiresFederatedAggBinaryAddAggregation L878, requiresFederatedWdivmmLocalAggregation L303.

**Partitioned MatMul result를 bind하는 경로.** native AggBinary LOUT은 일반 layout fanIn으로 in-band payload를 계산한다. 그러나 coordinator binding에는 별도로 정량화한 cost가 없다고 FCM L479–484 주석에 명시되어 있다. 모든 serial assembly를 비용에 반영했다고 설명할 수 없다.

### D.6 Input preparation과 fixed dispatch

FCM의 computeWdivmmInputPreparationCost L838은 다음 비용을 계산한다.

- X가 ROW/PART이면, U가 이미 ROW/PART가 아닌 경우 U를 slice broadcast하고 V를 full broadcast한다.
- X가 COL이면 U를 full broadcast하고, V가 이미 COL이 아닌 경우 V를 slice broadcast한다.
- matrix fourth input은 FULL이 아니면 slice broadcast한다.

FCM의 computeAggBinaryRowLeftInputPreparationCost L728과 computeAggBinarySlicedInputBroadcastCost L940도 선택된 input FType에 따라 full/sliced preparation을 추가한다. 이미 호환되는 remote representation이면 해당 upload를 생략하는 분기가 있다.

이들 in-band preparation은 payload와 serdes 비용만 추가한다. PUT/EXEC/GET/cleanup이 한 logical batch를 공유한다는 모델이므로 각 단계에 latency를 다시 부과하지 않는다. 근거: FCM의 computeSlicedBroadcastInputCost L995, computeFullBroadcastInputCost L1016, computeInBandUploadPayloadCost L2128.

일반 fixed stage는 nonnegative latencyMs와 nonnegative controlMs의 합이며 worker 수에 무관하다. DataOp와 mapping-preserving FULL transpose는 이 fixed stage에서 면제된다. FULL transpose의 면제가 kernel primitive도 무조건 0이라는 뜻은 아니다. 근거: FCM L262, L274, L295, L600; EPC L1983.

## E. Communication ownership

### E.1 Operator, movement, materialization의 경계

아래는 논문의 세 범주에 코드의 factor construction을 대응시킨 것이다. 코드에 모든 factor를 C_op/C_move/C_mat enum으로 일괄 분류한 표가 있는 것은 아니다. helper 이름에 download/materialization이 포함되었다는 이유만으로 소유 범주를 정하지 않고, 호출 위치와 재사용 방식을 확인했다.

| 물리 행위 | 소유 및 과금 위치 | 근거 |
|---|---|---|
| FED dispatch/control | Operator execution의 fixed stage | EPC fedCostProjection L1983; FCM L600 |
| MatMul/WDivMM 내부 full/sliced broadcast | Operator intrinsic input preparation | FCM computeMixedFedLocalCost L636; EPC L1994 |
| Native FED/LOUT aggregate partial GET 및 coordinator reduce | **Operator native result cost**. fedUnaryCost와 분리될 수 있지만 같은 operator의 unary construction에서 부과 | EPC L2002–2016, addPhysicalUnaryFactor L869 |
| 일반 native-local matrix operand를 FED instruction에 싣는 upload | Operator 실행에 내재된 input supply. 별도의 input-transfer factor로 구현될 수 있음 | EPC addPhysicalNativeLocalInputTransferFactors L1520, nativeLocalTargetCost L1672 |
| CP 계산 결과를 FOUT으로 upload | Local kernel primitive 밖의 별도 result-transfer factor. 실제 retained-copy 분류는 선택된 emission/action에 따라 확인해야 함 | EPC L842–844, physicalResultUploadCost L880 |
| Derived FOUT에서 local result를 다시 upload | Native result 형성은 operator cost; 후속 upload는 별도 output materialization factor | EPC L860–877 |
| FOUT producer를 local consumer들이 재사용하도록 GET/prefetch | Retained-copy materialization. consumer demand와 source lifetime에 따라 공유 과금 | EPC addPhysicalCompiledTransferFactors L889, L973–1032; EMA L81 |
| 별도 relocation/refederation | 별도의 이동. retained target을 만드는 compiled-transfer 경로에서는 materialization activation으로 과금 | EPC L945–981; FCM computeRefedNetworkCost L2177 |
| 함수 actual→formal의 별도 download/upload | 함수 경계의 별도 transfer factor; logical call weight 적용 | EPC addPhysicalLogicalFunctionFactors L1851 |
| Initial/native output을 그대로 보관 | 보관만으로 일반적인 retention-time/storage-rent 항을 더하지 않음. 추가 copy를 만드는 이동에 비용 부과 | EPC L814, L889의 factor construction |

사용자가 질문한 **“distributed aggregate partial result를 coordinator로 보내는 비용”은 native aggregate execution의 operator cost에 속한다.** FOUT producer를 다른 consumer를 위해 나중에 수집하는 reusable materialization과 구별된다. native LOUT의 result phase를 일반 input-download movement로 다시 더하면 이 경로의 소유 방식과 맞지 않는다.

이중 과금을 방지하는 구체적인 예로, EPC의 nativeLocalTargetCost L1699는 mixed helper가 input preparation을 이미 가격화하면 native-local upload의 추가 base cost를 0으로 둔다.

**구분의 한계:** 단독 result upload나 함수 경계 transfer에 대해서는 이 비용 파일의 factor 이름만으로 논문의 retained/non-retained 분류를 완전히 결정할 수 없다. 해당 alternative의 emission/action과 실제 copy lifetime까지 필요하다. 이 보고서는 확인된 operator intrinsic stage와 activation을 통한 retained creation은 명확히 구분하되, 나머지 모든 factor를 일괄적으로 C_move 또는 C_mat라고 단정하지 않는다. 이 완전한 범주 매핑은 **unclear**이다.

### E.2 실제 transfer 식

다음 표기는 코드 계산식을 줄여 쓴 것이다.

~~~text
B(D,b) = 1000 * (D/2^20) / b
S(D,σ) = B(D,σ) if σ>0 else 0
K = one latency + control stage in milliseconds
f = 1 for FULL/BROADCAST, otherwise max(1,W)
~~~

**1. 일반 explicit collection** — FCM의 computeDownloadNetworkCost(D,FType,W) L2042 → computeParallelDownloadCost L2093:

~~~text
Cdownload = K + B(D/f,bw2c) + S(D,σw2c)
~~~

network는 균등분할 critical payload, codec은 전체 logical bytes에 대해 과금한다. computeDownloadNetworkCost(D)라는 다른 overload는 fanIn을 받지 않고 directional whole-payload 식을 사용한다. 따라서 모든 download 호출이 같은 f를 사용한다고 간주하면 안 된다.

**2. Native in-band result** — FCM L1067:

~~~text
CnativeReturn = B(D/f,bw2c) + S(D/f,σinband)
~~~

fixed stage는 이미 operator가 소유하므로 추가하지 않는다. aggregate full partial이면 D 자체가 W*m이고, 일반 partitioned final result이면 D는 logical final result이다. AggUnary의 특수한 fanIn 전달은 D.5에 설명했다.

**3. Reusable FOUT→local materialization** — FCM L2065, L2077:

~~~text
criticalBytes = D/f
σselected = σlargeW2C if criticalBytes>threshold and σlargeW2C>0
            σinband otherwise
CretainedGet = K + B(criticalBytes,bw2c) + S(criticalBytes,σselected)
~~~

default threshold는 response당 4 MiB이다. **이 경로는 codec에도 D/f를 사용하므로 일반 collection 식과 다르다.** EPC L973의 실제 compiled-transfer download가 이 helper를 사용한다.

**4. Upload** — FCM L2114, L2128:

~~~text
multiplier = max(1,W) if target FType is FULL or BROADCAST else 1
Dsend = D * multiplier

CstandaloneUpload = K + B(Dsend,bc2w) + S(Dsend,σc2w)
CinbandUpload     =     B(Dsend,bc2w) + S(Dsend,σc2w)
~~~

slice upload의 총 logical payload에는 일반적으로 /W가 없으며, full/broadcast에는 W배가 적용된다. FULL이 실제 single target이면 caller가 전달하는 target worker 수가 중요하다. EPC의 일부 native-input 경로는 정확한 target pool을 구하지만 다른 경로는 graph-wide W를 전달한다. FCM의 computeLocalToFedForwardingPenalty L2165는 현재 항상 0이다.

**5. Refederation** — FCM L2177:

~~~text
Crefed = sourceDownload + targetUpload
~~~

실제 physical factor에서는 source collection을 reusable helper로 가격화하는 경우도 있다. 위 helper가 존재한다고 해서 모든 relocation이 동일한 download overload를 사용한다고 설명하지 않는다. 근거: EPC L973–980, L1918–1935.

이 식들은 positive payload인 일반 경로를 나타낸다. nonpositive bytes의 조기 반환과 invalid directional bandwidth의 fallback은 각 helper에 있다. 특히 computeDirectionalNetworkCost 자체와 이를 호출하는 public wrapper는 zero-byte 처리도 같지 않다. 근거: FCM L2036, L2114, L2142.

### E.3 Materialization의 공유와 frequency

EPC의 addPhysicalCompiledTransferFactors는 producer value/version, direction, layout, physical emission identity 및 creation scope로 demands를 묶는다. 같은 retained copy가 여러 consumer를 지원해도 모든 edge에 독립적으로 full transfer를 더하지 않는다.

EMA의 partition L81은 증명된 lifetime/control-event 관계를 activation class로 나눈다. overlap을 증명할 수 없으면 conservativeUnion L140에서 duplicate/subsumed event를 제외하고 다음 값을 사용한다.

~~~text
activation count = min(source-scope lifetime weight,
                       sum(nonduplicate, nonsubsumed selected event weights))
~~~

그 activation에 one-creation transfer price를 적용한다. 이는 one-execution kernel 식과 구별되는 retained-copy creation 비용이다. 모든 consumer의 execution count를 각각 materialization에 곱하는 식이 아니다.

## F. Paper-vs-code gaps

이 절의 판정은 조사 범위에 명시한 PAPER Section 5에 대한 것이다. 논문은 수정하지 않았다.

### F.1 코드와 일치하는 설명

| Section 5 설명 | 판정과 코드 근거 |
|---|---|
| Arithmetic/input read의 max 뒤에 output write를 더함, L38–49 | 기본 primitive와 일치. FCM computeOpCost L1194 |
| Operator execution과 별도 movement/retained-copy creation 구별, L9–19 | factor의 역할 및 공유 방식과 대응. EPC L814, L889. 다만 factor 이름과 논문의 세 항을 자동으로 1:1 치환하면 안 됨 |
| Distributed aggregate는 raw input 전체가 아닌 partial result를 전송, L56–59 | 해당 native aggregate 경로와 일치. FCM L754, L774; EPC L2002 |
| Native result communication은 operator 소유, L58–59 | nativeFedDownload 또는 derived execution factor로 가격화. EPC L860–877 |
| Dispatch/coordinator stage를 worker compute와 별도로 더함, L53–55 | Fixed stage와 mixed stage가 존재. FCM L274, L636; EPC L1983 |
| 일반 collection 식의 parallel wire와 full-payload codec, L87–104 | computeParallelDownloadCost helper와 일치. FCM L2093. 모든 download/materialization으로 일반화하면 불일치 |
| Boundary forwarding의 operator self-cost 0, L71–74 | transient read/write와 function boundary의 해당 경로와 일치. PCS L412; EPC L819 |
| Retained creation은 source lifetime/activation에 따라 공유, L141–172 | EPC L983 이후와 EMA partition/conservativeUnion에 대응 |
| Balanced partition와 additive stage 가정, L193–197 | base/W, payload/fanIn, stage 합산과 일치. 실측 skew 또는 heterogeneous worker별 max를 계산하지 않음 |

### F.2 코드에는 있지만 Section 5에 구체적으로 빠진 것

| 구현 내용 | 근거와 의미 |
|---|---|
| FLOP coefficient의 opcode별 차이와 generic fallback | SUM=4/input cell, EXP=18/output cell 등. 미구현 opcode도 기본 Nout으로 계산될 수 있음. CC L52 |
| Compute calibration의 실제 두 분류 | generic와 matrix AggBinary. MatVec/elementwise/aggregate별 독립 rate 없음. FCM L1269 |
| F와 bytes의 shape-refinement 경로 차이 | occurrence-aware shape는 bytes를 보정하지만 일반 F는 원래 HOP 차원을 사용. PCS L397, L420; CC L258 |
| MatMul F에는 좌측 sparsity만 반영 | 우측 sparsity와 output의 dense 추정은 별도 문제. CC L222; AggBinaryOp L288 |
| /W 제외 operator와 all-broadcast 조건 | Ternary cell op, Nary cell op, indexing, transpose, local/BROADCAST-only. FCM L342 |
| Aggregate FOUT의 reduced-output cap과 indexing slice cap | 하나의 roofline 식 밖에 min 보정이 존재. FCM L411, L531, L557 |
| WDivMM rank floor, latent-kernel rewrite, 제거되는 intermediate | source HOP와 runtime kernel 사이의 비용 보정. FCM L1229, L1248; PCS L397 |
| Compute W가 graph-wide worker union | 정확한 operator pool 크기라는 설명에 제약. EPC L432, L2209 |
| In-band result와 reusable GET의 parallel codec | 일반 collection과 달리 D/f codec 사용. response당 4 MiB를 기준으로 rate 전환 가능. FCM L1067, L2077 |
| AggUnary의 FULL/BROADCAST payload와 reduction W 사용 특례 | q=1인 payload를 W로 나누며 reduce도 W 사용. FCM L501, L774, L786 |
| Unknown-size fallback과 sparse-assignment heuristic | 실제 측정치와 다른 bounded/fallback 추정 및 memory/wire 추정 차이. FCM L1414, L1450, L1524, L1685; PCS L449 |
| 조건부 single-worker function penalty | DML FunctionOp, control>10ms 등의 조건. FCM L1113 |

### F.3 논문에는 있지만 코드에서 확인되지 않거나 일반적으로 성립하지 않는 것

| Section 5 문장 또는 전제 | 코드 기준 판정 |
|---|---|
| “rates and floors are calibrated per kernel class”, L50 | **과도한 일반화.** 두 compute-rate 분기는 있으나 모든 kernel class별 rate/fixed-minimum table은 없다. floor는 주로 WDivMM/function의 크기 의존 보정이다. FCM L1194, L1269, L1280 |
| “Only partition-parallel work is divided …”, L53–54 | **일반 구현과 불일치.** base/W로 input-memory/output-write까지 함께 나눈다. mixed input의 replicated read를 독립적으로 보존하지 않는다. FCM L364 |
| 선택된 alternative의 participating worker 수로 항상 가격화한다는 해석, L28–31 | **일반 compute 경로에서 보장되지 않음.** graph-wide W를 전달한다. 일부 movement는 실제 anchor pool 사용. EPC L432, L856, L1733, L2209 |
| 모든 serial assembly를 actual execution structure대로 더한다는 해석, L54–55 | **부분적으로만 확인.** partial reduction 식은 있으나 일반 MatMul coordinator binding에는 독립 cost가 없다고 명시. FCM L479–484 |
| collection 식이 retained-copy creation의 해당 이동에도 공통으로 적용된다는 해석, L87–104와 L124–128 | **현재 helper 분기와 다름.** reusable GET은 network와 codec 모두 critical response bytes 사용. EPC L973; FCM L2077 |
| Required shape/size/calibration이 없으면 항상 costing failure, L191–192 | **그대로는 성립하지 않음.** calibration은 default로, unknown shape는 bounded/fallback bytes 및 N=max(dim,1)로 대체할 수 있다. 모든 fallback 후에도 유효 bytes를 못 구한 일부 physical term은 throw한다. EPC L2218–2270; FCM L1378, L1450; configuration loader L42 |
| 모든 throughput이 positive라는 전제, L20–21 | **모델의 전제와 구현 검증을 구분해야 함.** serdes=0은 의도적인 비활성 값이다. parser도 모든 숫자의 positive/finite를 일괄 검증하지 않는다. FCM L154–156; configuration loader L42 |

위 표의 “해석”은 논문 문장에서 읽힐 수 있는 적용 범위를 코드와 대조한 판정이다. 논문이 구체적으로 주장하지 않은 별도의 수식이나 worker별 모델을 새로 가정하지 않았다.

### F.4 Unclear와 검증 범위

- **unclear:** 특정 실험 run의 최종 property/environment와 frozen profile. 보고서는 Java 기본값과 발견한 script의 설정값을 구분했다.
- **unclear:** 모든 calibration 계수의 재현 가능한 측정 원본과 fitting 과정. 현재 Java estimator의 online calibration은 확인되지 않았다.
- **unclear:** 다른 미지정 논문 버전의 Section 5와의 차이.
- **unclear:** 모든 단독 result/function-boundary transfer를 논문의 retained/non-retained 범주에 완전히 대응시키는 분류. emission/action과 copy lifetime까지 함께 확인해야 한다.
- **미모델링으로 확인:** worker별 compute/memory rate, 실측 skew를 반영한 worker-cost 최댓값, mixed input에서 replicated read의 보편적인 별도 보존, 일반 MatMul binding의 독립 정량 항은 이 호출 경로에 없다.
- **검증한 것:** production 호출 경로와 helper 분기, configuration 로딩/전달 코드, 관련 테스트의 assertion.
- **검증하지 않은 것:** 새 benchmark, runtime 실측과 예측 오차, 전체 test suite의 통과 여부.

관련 테스트 코드도 주요 해석을 뒷받침한다. [FederatedCostModelFixedInstructionStageTest.java L23](/home/mchoi/w1357-paper-aligned-refactor/src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModelFixedInstructionStageTest.java:23)는 latency/control 합산을, L36은 native AggBinary LOUT에 추가 RTT가 없음을, L49는 balanced response의 1/W payload를, L58은 full partial return과 coordinator reduction을, L74는 별도 FOUT materialization의 추가 request stage를 검증하도록 작성되어 있다. 이번 조사에서 이 테스트를 실행했다고 주장하지 않는다.
