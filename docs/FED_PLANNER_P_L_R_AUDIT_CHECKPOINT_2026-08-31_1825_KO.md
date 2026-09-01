# FED Planner P/L/R 감사 체크포인트 — 2026-08-31 18:25 CEST

## 1. 결론 요약

현재 작업은 selector가 아닌 **공유 `PlacementAnalysis`가 공개하는 후보 공간 `P`**, privacy 및
whole-program 제약을 통과한 합법 공간 `L`, 실제 HOP→LOP→FED instruction→worker 실행에
성공한 관측 공간 `R_observed`를 occurrence와 ordered input signature 단위로 대조하는 단계다.

이번 체크포인트까지 확인된 결과는 다음과 같다.

1. binary 전용 fixture가 공개한 34개 target은 모두 강제 실행에 성공했다.
2. indexing 전용 fixture를 추가해 ROW/COL right-index와 matrix/scalar left-index를 정상 실행했다.
3. left-index worker fragment가 coordinator의 `FED` exec type을 그대로 전달하던 실제 runtime
   lowering 결함을 수정했다.
4. 한 partition 안에 완전히 포함되는 right-index 결과는 runtime에서 `FULL`이 되지만 공통 후보
   생성기가 입력 `ROW/COL`을 그대로 공개하던 실제 planning-space 모델 결함을 발견했다.
5. selector별 보정이나 audit 완화 대신, exact literal bounds와 fed-init partition anchor를 사용해
   runtime `FederationMap.filter` semantics를 공유 `PlacementAnalysis`에서 재현하도록 수정했다.
6. 수정 후 audited discovery는 mismatch 0건으로 통과했고, federated indexing occurrence가 공개한
   28개 상태에 대한 target당 fresh-JVM forced campaign을 실행 중이다.

현재 증거는 **전용 fixture에서 공개된 상태에 대한 관측적 완전성**을 보인다. Runtime이 지원할 수
있는 모든 미관측 상태를 독립적으로 열거한 것이 아니므로 전역적인 `Missing=0` 주장은 아직 하지
않는다.

## 2. 작업 위치와 실행 정책

```text
worktree: /home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830
branch:   g014/fed-runtime-space-audit-20260830
base:     0d769014d18ffb6a915b186c9bc05596710a3e24
host:     dams-so002
```

- `so001`은 proxy이므로 감사/실험 서버에서 제외한다.
- 허용 원격 서버는 `so002`--`so006`이다.
- 이번 indexing discovery와 forced campaign은 현재 worktree가 있는 `so002`에서 격리 실행한다.
- 별도로 오래 실행 중인 `FederatedMultiplyPlanningTest10.dml` process tree는 본 작업 소유가
  아니므로 종료하거나 변경하지 않았다.

## 3. 이미 완료된 binary 감사

### 3.1 Discovery

```text
audit-results/fed-runtime-binary-layout-e2e-v3-audit-fixed-20260831T173537Z
```

### 3.2 Forced campaign

```text
audit-results/fed-runtime-binary-layout-forced-v1-20260831T173845Z
```

결과:

| 항목 | 결과 |
|---|---:|
| expected target | 34 |
| result row | 34 |
| forced constraint satisfied | 34 |
| successful target | 34 |
| fresh JVM chunk | 34 |
| runtime-capability SUCCESS row | 296 |
| campaign classification | `ALL_SUCCESS` |
| confirmed Missing | 0 |
| input-signature divergence | 0 |

Binary evidence envelope SHA-256:

```text
a10a652ab7459bfd8a332ef0d810a07db07dab2063452ca3fb16f6d4e5d9138d
```

상세 근거는 다음 이전 체크포인트에 고정되어 있다.

```text
docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_1758_KO.md
```

## 4. Indexing 감사 fixture

추가 파일:

```text
src/test/java/org/apache/sysds/test/functions/federated/fedplanning/
  FederatedIndexingLayoutPlanningTest.java
src/test/scripts/functions/privacy/fedplanning/
  FederatedIndexingLayoutPlanningTest.dml
  FederatedIndexingLayoutPlanningTestReference.dml
```

PUBLIC 12×12 matrix를 두 worker에 ROW 및 COL로 각각 분할하고 다음 경로를 포함했다.

- multi-partition ROW right-index
- single-partition ROW right-index
- multi-partition COL right-index
- single-partition COL right-index
- ROW/COL matrix left-index
- ROW/COL scalar left-index

Local reference와 8개 출력을 수치 비교한다.

## 5. 발견 및 수정한 실제 결함

### 5.1 Left-index worker fragment exec type

파일:

```text
src/main/java/org/apache/sysds/runtime/instructions/fed/IndexingFEDInstruction.java
```

문제:

- coordinator의 `IndexingFEDInstruction.leftIndexing`은 worker로 보낼 instruction을 만들 때
  `callInstruction(..., null)`을 사용했다.
- 따라서 원본 coordinator instruction의 `FED` exec type이 worker fragment에도 남았다.
- worker는 local fragment를 다시 `IndexingFEDInstruction`으로 파싱했고, local input에 대해
  `FED indexing requires federated input`으로 실패했다.

수정:

- right-index와 동일하게 원본 exec type을 읽는다.
- 원본이 `FED`이면 worker fragment용 `CP`로 변환한다.
- matrix 및 scalar left-index 양쪽 `callInstruction`에 변환된 exec type을 전달한다.

이는 audit 전용 우회가 아니라 coordinator→worker physical fragment lowering의 실제 수정이다.

### 5.2 Single-partition right-index의 output FType

최초 audited discovery:

```text
audit-results/fed-runtime-indexing-layout-e2e-v1-20260831T160730Z
```

실패 예:

```text
planned: FED/FOUT/ROW
actual:  FED/FOUT/FULL
operation: RRowOne = XR[2:5,]
```

원인:

- 기존 `RightIndexRule`은 native FOUT layout을 입력 FType으로 전달했다.
- 실제 runtime은 `FederationMap.filter(ixrange)`로 overlap partition만 남긴다.
- 남은 map이 row/column 양쪽 전체 결과 범위를 덮고 entry가 하나이면 FType을 `FULL`로 바꾼다.
- 따라서 한 ROW 또는 COL partition 내부의 slice는 물리적으로 `FULL`이다.

수정 파일:

```text
src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java
```

공유 분석이 다음 exact evidence가 모두 있을 때만 runtime filtering을 재현한다.

1. HOP이 right `IndexingOp`이다.
2. oracle native state가 `FED/FOUT`이다.
3. input signature의 첫 FType과 exact durable fed-init anchor FType이 동일하다.
4. row/column lower·upper가 모두 literal이다.
5. anchor partition이 유효한 2D half-open range다.

그 외에는 기존 oracle 결과를 유지하여 추측 후보를 만들지 않는다. Exact evidence가 있는 경우에는
다음을 하나의 일관된 candidate fact로 공개한다.

- corrected native FOUT FType
- corrected producer profile
- corrected legal/emission state
- `SHAPE_DEPENDENT` 표시
- literal bounds, exact anchor, filtered partition count, runtime output FType proof

이 처리는 FedAll/Heuristic/DP/Exact가 모두 소비하는 selector-neutral 후보 생성 단계에 있다.

## 6. 수정 후 discovery 검증

현재 authoritative indexing discovery:

```text
audit-results/fed-runtime-indexing-layout-e2e-v2-20260831T162115Z
```

결과:

| 검증 항목 | 결과 |
|---|---:|
| Maven exit | 0 |
| JUnit | 1 test, 0 failure, 0 error |
| candidate row | 136 |
| runtime-capability row | 12 `SUCCESS` |
| runtime/lowering mismatch | 0 |

Right-index exact proof:

| bounds | filtered partitions | shared P native FType | runtime |
|---|---:|---|---|
| `1:12,3:10` | 2 | ROW | ROW |
| `2:5,1:12` | 1 | FULL | FULL |
| `3:10,1:12` | 2 | COL | COL |
| `1:12,2:5` | 1 | FULL | FULL |

정상 audit-off regression도 통과했다.

```text
FederatedIndexingLayoutPlanningTest: 1 test, 0 failures, 0 errors
```

## 7. 현재 실행 중인 forced campaign

Discovery의 전체 152 target에는 reference script의 literal/read/write/순수 CP occurrence가 포함되어
있다. 이번 instruction-family 검증에서는 다음 fail-closed 조건으로 실제 federated indexing target만
선별했다.

```text
hopClass ∈ {IndexingOp, LeftIndexingOp}
ordered input signature starts with PRESENT:<FType>
```

선별 결과:

```text
28 targets
right-index: CP/LOUT, CP/FOUT, FED/FOUT, FED/LOUT
left-index:  CP/LOUT, CP/FOUT, FED/FOUT
layouts: ROW, COL, FULL, BROADCAST where physically applicable
```

Manifest:

```text
audit-results/fed-runtime-indexing-layout-e2e-v2-20260831T162115Z/
  indexing-runtime-manifest.jsonl
```

실행 중인 campaign:

```text
audit-results/fed-runtime-indexing-layout-forced-v1-20260831T162358Z
```

격리 정책:

```text
TARGETS_PER_JVM=1
28 targets / 28 fresh Maven-Surefire JVM chunks
```

보고서 작성 시점 상태:

```text
completed chunks: 4 / 28
passing chunks:   4 / 4 completed
```

이 값은 중간 상태이며 최종 판정이 아니다.

## 8. 검증 명령 결과

현재까지 새 수정에 대해 다음을 통과했다.

- `mvn ... compile`: PASS
- `FederatedIndexingLayoutPlanningTest` audit-off: PASS
- 동일 test candidate/runtime/lowering audit-on: PASS
- `git diff --check`: PASS

Forced campaign 종료 후 다음을 추가로 수행한다.

1. 28/28 result 및 constraint-satisfied 확인
2. 모든 target의 runtime-capability receipt 존재 확인
3. attempt-local exact occurrence/input-signature join
4. `Missing`, failed target, input divergence 집계
5. Java audit regression + Python comparator tests
6. source/evidence SHA-256 receipt 생성 및 검증

## 9. 남은 위험과 주장 범위

- 현재 `R`은 실행으로 관측된 `R_observed`다. Runtime capability의 완전한 독립 Cartesian
  enumeration은 아니다.
- 28개 중 아직 실행되지 않은 target은 성공으로 간주하지 않는다.
- Forced campaign에서 failure가 나오면 candidate를 임의 삭제하거나 audit equivalence를 넓히지
  않고, lowering/runtime/공유 feasibility 결함을 분리해 수정한 뒤 해당 target부터 재실행한다.
- 이번 fixture는 PUBLIC privacy다. Privacy별 candidate exclusion은 기존 shared privacy closure
  evidence 및 별도 privacy tests로 검증하며, indexing runtime capability와 혼합해 과도한 결론을
  내리지 않는다.

## 10. 즉시 이어지는 작업

1. indexing 28-target fresh-JVM campaign 완료
2. non-success target의 exact occurrence별 root-cause 분석 및 수정
3. attempt-local comparator와 checksum envelope 고정
4. indexing 결과를 최신 progress/status 문서에 반영
5. 다음 미감사 family(weighted-div/quaternary 및 fused boundary)로 확장
