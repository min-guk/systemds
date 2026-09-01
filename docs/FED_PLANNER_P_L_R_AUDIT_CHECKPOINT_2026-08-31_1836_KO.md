# FED Planner P/L/R 감사 체크포인트 — 2026-08-31 18:36 CEST

## 1. 현재 판정

공유 candidate builder가 selector에 공개하는 `P`, shared privacy/whole-program constraints를 통과한
`L`, 실제 lowering 및 runtime 실행에 성공한 `R_observed`를 exact occurrence와 ordered input
signature 단위로 대조하고 있다.

이번 체크포인트에서 **indexing instruction family의 전용 fixture 검증을 완료**했다.

- binary fixture: 34/34 forced target `SUCCESS`
- indexing fixture: 28/28 forced target `SUCCESS`
- indexing fresh JVM: 28/28, failed chunk 0
- indexing runtime-capability receipt: 132/132 `SUCCESS`
- exact planned target in published `P`: 68 rows
- selected runtime input divergence: 0
- confirmed direct/public `Missing`: 0
- audit/runtime/lowering mismatch: 0

단, 현재 `R`은 실행으로 확인한 `R_observed`다. Runtime capability의 독립적이고 완전한 Cartesian
enumeration이 아니므로 전체 SystemDS에 대한 전역 `Missing=0` 결론은 아직 내리지 않는다.

## 2. 작업 위치와 서버 정책

```text
worktree: /home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830
branch:   g014/fed-runtime-space-audit-20260830
base:     0d769014d18ffb6a915b186c9bc05596710a3e24
host:     dams-so002
```

- `so001`은 proxy이므로 사용하지 않는다.
- 허용 서버는 `so002`--`so006`이다.
- 이번 indexing 단계는 `so002`의 위 격리 worktree에서 실행했다.
- 본 작업 소유가 아닌 장기 실행 `FederatedMultiplyPlanningTest10.dml` process는 건드리지 않았다.

## 3. 기존 binary 단계

Authoritative discovery:

```text
audit-results/fed-runtime-binary-layout-e2e-v3-audit-fixed-20260831T173537Z
```

Authoritative campaign:

```text
audit-results/fed-runtime-binary-layout-forced-v1-20260831T173845Z
```

| 항목 | 결과 |
|---|---:|
| expected/result/constraint-satisfied | 34/34/34 |
| result outcome | 34 `SUCCESS` |
| fresh JVM chunks | 34 |
| runtime-capability rows | 296 `SUCCESS` |
| classification | `ALL_SUCCESS` |
| confirmed Missing | 0 |
| input divergence | 0 |

Binary evidence envelope SHA-256:

```text
a10a652ab7459bfd8a332ef0d810a07db07dab2063452ca3fb16f6d4e5d9138d
```

상세 보고서:

```text
docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_1758_KO.md
```

## 4. Indexing fixture와 관측 범위

추가 파일:

```text
src/test/java/org/apache/sysds/test/functions/federated/fedplanning/
  FederatedIndexingLayoutPlanningTest.java
src/test/scripts/functions/privacy/fedplanning/
  FederatedIndexingLayoutPlanningTest.dml
  FederatedIndexingLayoutPlanningTestReference.dml
```

PUBLIC 12×12 matrix를 ROW/COL로 각각 두 worker에 분할하고 다음을 실행한다.

1. 두 partition을 가로지르는 ROW right-index
2. 한 partition 안의 ROW right-index
3. 두 partition을 가로지르는 COL right-index
4. 한 partition 안의 COL right-index
5. ROW/COL matrix left-index
6. ROW/COL scalar left-index
7. local reference와 8개 output의 수치 비교

## 5. 발견한 결함과 수정

### 5.1 Worker-side left-index fragment lowering

파일:

```text
src/main/java/org/apache/sysds/runtime/instructions/fed/IndexingFEDInstruction.java
```

기존 `leftIndexing`은 coordinator의 `FED` instruction string을 `callInstruction(..., null)`로
worker에 전달했다. Worker는 local fragment를 다시 FED indexing으로 파싱했고 local input에 대해
다음 오류를 냈다.

```text
FED indexing requires federated input but found local at runtime
```

수정은 right-index와 동일한 lowering contract를 적용한다.

- 원본 exec type 획득
- `FED`이면 worker fragment에서 `CP`로 변환
- matrix/scalar left-index 양쪽 `callInstruction`에 전달

이는 audit 우회가 아니라 실제 coordinator→worker physical fragment 수정이다.

### 5.2 Single-partition right-index FType

최초 diagnostic discovery:

```text
audit-results/fed-runtime-indexing-layout-e2e-v1-20260831T160730Z
```

관측된 불일치:

```text
RRowOne = XR[2:5,]
planned: FED/FOUT/ROW
actual:  FED/FOUT/FULL
```

기존 shared rule은 right-index output에 input FType을 그대로 전달했다. 실제 runtime의
`FederationMap.filter`는 overlap partition만 남긴 후, 결과를 한 entry가 row/column 양쪽 전체
범위를 덮으면 `FULL`로 분류한다. 같은 현상은 COL single-partition slice에도 적용된다.

수정 파일:

```text
src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java
```

공유 builder는 다음 exact evidence가 모두 존재할 때만 runtime filter semantics를 재현한다.

- right `IndexingOp`
- oracle native `FED/FOUT`
- ordered input FType과 exact durable fed-init anchor FType 일치
- 네 index bound가 모두 literal
- 유효한 2D half-open anchor partitions

결과는 selector별 보정이 아니라 하나의 공통 candidate fact에 반영된다.

- native FOUT FType
- producer profile
- legal/emission states
- `SHAPE_DEPENDENT`
- literal bounds, anchor, filtered partition count, output FType proof

따라서 FedAll/Heuristic/DP/Exact가 동일한 정제된 `P`와 privacy-filtered `L`을 소비한다.
Exact evidence가 없으면 추측하지 않고 기존 oracle 결과를 유지한다.

## 6. 수정 후 audited discovery

Authoritative discovery:

```text
audit-results/fed-runtime-indexing-layout-e2e-v2-20260831T162115Z
```

| 항목 | 결과 |
|---|---:|
| Maven exit | 0 |
| JUnit | 1 pass |
| candidate rows | 136 |
| runtime-capability rows | 12 `SUCCESS` |
| runtime/lowering mismatch | 0 |

Exact right-index proof:

| literal bounds | filtered partitions | shared P | actual runtime |
|---|---:|---|---|
| `1:12,3:10` | 2 | ROW | ROW |
| `2:5,1:12` | 1 | FULL | FULL |
| `3:10,1:12` | 2 | COL | COL |
| `1:12,2:5` | 1 | FULL | FULL |

## 7. Forced-state campaign

전체 discovery manifest의 literal/read/write/reference-script target을 제외하고, 실제 federated
indexing occurrence만 다음 조건으로 선별했다.

```text
hopClass in {IndexingOp, LeftIndexingOp}
ordered input signature starts with PRESENT:<FType>
```

Manifest:

```text
audit-results/fed-runtime-indexing-layout-e2e-v2-20260831T162115Z/
  indexing-runtime-manifest.jsonl
```

Campaign:

```text
audit-results/fed-runtime-indexing-layout-forced-v1-20260831T162358Z
```

| 항목 | 결과 |
|---|---:|
| expected target | 28 |
| result rows | 28 |
| constraint satisfied | 28 |
| result outcome | 28 `SUCCESS` |
| fresh JVM chunks | 28 |
| failed chunks | 0 |
| candidate rows across attempts | 3,808 |
| runtime-capability rows | 132 `SUCCESS` |
| targets with runtime receipt | 28 |
| infrastructure | `PASS` |
| classification | `ALL_SUCCESS` |

강제한 상태는 다음을 포함한다.

```text
CP/LOUT
CP/FOUT/{ROW,COL,BROADCAST}
FED/FOUT/{ROW,COL,FULL}
FED/LOUT/{ROW,COL,FULL}
```

Left-index에는 shared builder가 공개하지 않는 `FED/LOUT`을 인위적으로 추가하지 않았다.
Right-index의 forced local collection은 ROW/COL뿐 아니라 새로 모델링한 FULL에서도 성공했다.

## 8. Attempt-local P/R join

```text
audit-results/fed-runtime-indexing-layout-forced-v1-20260831T162358Z/
  attempt-local-comparison/summary.json
  attempt-local-comparison/REPORT.md
```

| 분류 | rows |
|---|---:|
| runtime success | 132 |
| runtime row with planned target | 68 |
| `EXACT_PLANNED_TARGET_IN_P` | 68 |
| `NO_EXACT_ACTUAL_INPUT_SIGNATURE` | 56 |
| `NO_EXACT_SINGLE_OCCURRENCE` | 8 |
| confirmed Missing | 0 |
| selected input divergence | 0 |

마지막 두 분류는 fused/auxiliary 또는 exact single-occurrence/input signature로 귀속할 수 없는
성공 runtime row다. 이들을 Missing으로 오분류하지 않는다. Comparator는 JVM attempt를 가로질러
candidate와 runtime evidence를 섞지 않는다.

## 9. 회귀 및 정적 검증

동일 source snapshot에서 다음 Java 87 tests가 모두 통과했다.

| Test | pass |
|---|---:|
| `PlannerRuntimePlacementAuditTest` | 64 |
| `PlannerSpaceAuditTest` | 16 |
| `ExactPhysicalForcedStateAuditTest` | 5 |
| `FederatedForcedStateAuditRunnerSelectionTest` | 1 |
| `FederatedIndexingLayoutPlanningTest` | 1 |
| 합계 | 87 |

추가 검증:

- Python audit/comparator unit tests: 5 pass
- `python3 -m py_compile scripts/fedplanner/*.py`: PASS
- `bash -n scripts/fedplanner/*.sh`: PASS
- `git diff --check`: PASS
- campaign/discovery/source/comparison/envelope checksums: PASS

## 10. Evidence receipts

```text
source files:                  70
source receipt SHA-256:        9d55bbb7a54a8b490618483f34a44d8185a4cd2f1db034c554d43515db14110f
campaign checksum SHA-256:     09fa9cdd39bfeca1b504e6f6f692a6b2703244270f5faf0d9dafba1478778c2a
discovery checksum SHA-256:    b80bd15cbc4d8814410d112cd87e16c54e9db7711e7b944af4b80e68fa01a2ba
indexing envelope SHA-256:     491cfbac0b02e68158275e892c537b37a114129aecfa9253d87438d691ddc91b
```

Envelope:

```text
audit-results/fed-runtime-indexing-layout-forced-v1-20260831T162358Z/
  INDEXING_EVIDENCE_ENVELOPE_SHA256SUMS.txt
```

## 11. 주장 범위와 다음 작업

이번 단계로 전용 PUBLIC fixture에서 shared `P/L`이 공개한 28개 indexing target은 모두 실제
lowering/runtime에서 실행 가능함을 확인했다. 또한 runtime이 선택 plan과 다른 input signature를
사용한 증거는 없다.

아직 남은 범위:

1. `R_observed` 밖의 독립 runtime capability enumeration
2. privacy class별 indexing exclusion/aggregation 경로의 전용 강제 fixture
3. weighted-div/quaternary variant의 output residency
4. fused instruction이 여러 원래 occurrence를 합칠 때의 boundary attribution
5. 위 family별 발견 결함을 selector가 아니라 shared analysis/runtime contract에서 수정

다음 실행 단계는 weighted-div/quaternary의 rule variants와 runtime instruction branches를 먼저
정적 매핑한 뒤, 최소 전용 DML fixture와 audited discovery를 추가하는 것이다.
