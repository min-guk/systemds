# Statistical FED instruction P/L/R 감사 최종 보고서 (2026-08-31)

## 1. 결론

현재 source snapshot에서 statistical workload가 공개한 22개 selector candidate를 각각 독립 JVM에서 강제 실행한 결과는 다음과 같다.

| 항목 | 결과 |
|---|---:|
| discovery target | 22 |
| forced result row | 22 |
| unique target | 22 |
| `constraintSatisfied=true` | 22 |
| runtime 성공 | 22 |
| 중복 / 누락 / 예상 밖 target | 0 / 0 / 0 |
| runtime-capability receipt | 538 success, 0 failure |
| campaign infrastructure | 4/4 shard `PASS` |

따라서 이 witness가 공개한 `b(cm)`, `b(cov)`, `t(ctable)` candidate에 대해서는
`P \cap L \subseteq R`가 실행 증거로 확인되었다. 다만 이 캠페인은 runtime이 가능한 모든
상태를 planner와 독립적으로 열거하지 않는다. 즉 `R - P`를 완전 탐색하지 않았으므로
**`coverageComplete=false`** 이며, 이 결과만으로 `P = R \cap L` 전체를 증명하지는 않는다.

## 2. 감사 대상과 정의

- `P(o,i)`: occurrence `o`와 input signature `i`에 대해 shared candidate builder가 selector에 공개한 상태
- `L(o,i)`: privacy 및 whole-program consistency를 만족하는 상태
- `R(o,i)`: 실제 HOP→LOP→instruction→runtime 경로가 실행한 상태
- 대상 opcode:
  - central moment `b(cm)`: 7 targets
  - covariance `b(cov)`: 6 targets
  - contingency table `t(ctable)`: 9 targets
- 출력 상태 구성:
  - `CP/LOUT/-`: 14
  - `FED/LOUT/ROW`: 5
  - `FED/LOUT/COL`: 2
  - `FED/LOUT/FULL`: 1

Discovery manifest:

- SHA-256: `b5f69895a21f8083a5cc7bb47165141630f191b5e3493c3b9da953bea13f8ecc`
- 로컬 증거: `audit-results/fed-statistical-cp-vector-fix-20260831T211133Z-v2/discovery/`

## 3. 최초 실패 재현과 근본 원인

최초 authoritative 22-target 실행은 infrastructure 관점에서는 완전했지만 runtime 결과는
1 success / 21 failure였다. 21개 실패는 서로 다른 target의 독립 결함이 아니었다. Exact가
whole-program completion 중 비-target `cmCol`을 `CP/LOUT`로 선택하면 COL federation을
coordinator에 수집한 논리 vector가 `1x12`로 materialize되었다. 기존 CP central-moment kernel은
column vector만 받아 다음 오류를 냈다.

```text
Central Moment cannot be computed on [1,12] matrix
```

즉 후보가 privacy-invalid했던 것이 아니라, **합법적인 COL vector의 coordinator materialization을
CP statistical instruction boundary가 canonical `Nx1` vector로 정규화하지 않았던 runtime
orientation defect**였다. FED UDF 경로는 이미 같은 표현을 정규화하고 있었다
(`CentralMomentFEDInstruction.java:143-149`, `CovarianceFEDInstruction.java:596-605`).

## 4. 최소 수정

### 4.1 CP central moment / covariance

- `CentralMomentCPInstruction.java:84-105`
  - data와 optional weight가 `1xN` row-vector이면 `Nx1`로 정규화한 뒤 기존 kernel을 호출한다.
- `CovarianceCPInstruction.java:61-84`
  - 두 data input과 optional weight에 동일한 정규화를 적용한다.
- `CentralMomentCPInstruction.java:113-121`
  - 두 instruction이 공유하는 정규화 helper는 **오직** `rows == 1 && cols > 1`에만 transpose한다.
  - column vector와 일반 matrix는 원 객체를 그대로 사용한다.

기존 `ExecutionContext` release 호출은 변수 이름에 대해 그대로 수행되며, transpose로 만든 임시
`MatrixBlock` 때문에 cache pin/unpin 대상이 바뀌지 않는다.

### 4.2 회귀 검사

`CMCovCPInstructionUtilsTest.java:40-90`은 외부 R package 없이 다음을 검증한다.

1. row vector만 transpose하고 column/non-vector matrix는 건드리지 않음
2. weighted CM/COV의 row-vector 결과가 동일 값의 column-vector 결과와 같음
3. non-vector matrix와 길이가 다른 weight는 기존 dimension validation으로 거부됨

추가로 강제 실행 canary 두 개를 먼저 재실행했다.

- CM, `PRESENT:COL,ABSENT_LOCAL:-`, `CP/LOUT/-`: PASS
- COV, `PRESENT:COL,PRESENT:COL`, `CP/LOUT/-`: PASS

## 5. 선행 blocker 수정

Statistical campaign이 shared analysis와 dynamic recompilation을 통과하도록 다음 결함도 제거되었다.

- `RewriteSplitDagDataDependentOperators.java:425-434`:
  FED source의 address/range/FType metadata subgraph를 artificial transient split에서 보존한다.
- `CtableFEDInstruction.java:193-206`:
  `ctableexpand` ROW input의 runtime 전제와 실패 진단을 명시한다.
- `CentralMomentFEDInstruction.java:143-149`:
  COL shard row-vector를 FED UDF boundary에서 정규화한다.
- `CovarianceFEDInstruction.java:596-605`:
  aligned COL covariance의 양 입력을 정규화한다.
- `PlannerRuntimePlacementAudit.java:200-207,903-942`:
  dynamic recompilation에서 바뀌는 Lop ID를 persistent-read identity로 쓰지 않고 immutable
  Hop/origin/recompile-signature/path/format을 사용한다.

Persistent-read 수정은 동일 immutable descriptor를 가진 fresh recompiled Lop 허용, 다른 source
path 및 ordinary temporary symbol 거부 회귀로 검증했다
(`PlannerRuntimePlacementAuditTest.java:150-235`). 전체
`PlannerRuntimePlacementAuditTest`도 통과했다.

## 6. Authoritative 원격 실행

`so001`은 사용하지 않았다. `so003`–`so006`의 source-local physical `target/`에서
`TARGETS_PER_JVM=1`로 실행해 target 간 JVM state 공유를 제거했다.

공통 source receipt:

- `SOURCE_SHA256SUMS_V2.txt` SHA-256:
  `d51a350164f73fae6a8dcf969f2e4f53312f8a87389afdb2240eec47b14256e8`

| host | shard | targets | target device/inode | result | capability rows |
|---|---:|---:|---|---|---:|
| dams-so003 | 0 | 6 | `2050/19935259` | 6 success | 146 |
| dams-so004 | 1 | 6 | `2050/19161883` | 6 success | 148 |
| dams-so005 | 2 | 5 | `2050/7735292` | 5 success | 123 |
| dams-so006 | 3 | 5 | `2050/26249709` | 5 success | 121 |

각 `RUN_MANIFEST.txt`는 다음을 기록한다.

- `build_target=/home/mchoi/fed-statistical-plr-20260831T204144Z/source/target`
- `type=directory` (symlink 아님)
- source와 동일 device의 source-local target
- `maven_rc=0`, `summary_rc=0`
- `infrastructure_status=PASS`, `classification_status=ALL_SUCCESS`

Exact union은
`audit-results/fed-statistical-cp-vector-fix-20260831T211133Z-v2/EXACT_UNION_SUMMARY.json`에 저장했다.
모든 로컬 수집 증거의 checksum은 같은 디렉터리의 `LOCAL_SHA256SUMS.txt`에 있다.

## 7. 제외한 비권위 실행

로컬 discovery
`audit-results/fed-statistical-discovery-20260831T210233Z-cp-vector-fix/`는 결과 집계에서 제외했다.
이 worktree의 `target`은 source-local physical directory가 아니라 이미 제거된
`/tmp/g007-bb30-fresh-target-20260727`를 가리키는 symlink였다. Surefire가 그 경로의
`surefirebooter-*.jar`를 열지 못해 fork가 시작 전에 종료되었다. 이는 candidate/runtime 결과가
아니라 build-isolation infrastructure failure이므로 P/L/R 증거로 사용할 수 없다.

또한 기존 CP test 4개 class의 32 failure는 SystemDS 실행 failure가 아니라 로컬 R의
`moments` package 부재로 reference 단계가 종료된 것이므로 correctness 결과에서 분리했다.
대신 외부 dependency가 없는 Java self-reference test와 forced CP canary를 authoritative 회귀
증거로 사용했다.

## 8. 남은 한계

- **`coverageComplete=false`**: 이번 forced replay는 discovery가 공개한 `P \cap L` candidate를
  실행했으며 runtime instruction 공간 `R`을 독립적으로 완전 열거하지 않았다.
- 따라서 이번 결과는 공개된 22개 상태의 spurious candidate가 없음을 보이지만, statistical
  instruction 전체에 대한 missing state `(R \cap L) - P` 부재를 완전 증명하지 않는다.
- FULL/BROADCAST 및 weighted statistical 경로의 더 넓은 독립 runtime enumeration은 별도
  exhaustive R witness가 필요하다.

## 9. 증거 경로

- 최종 evidence root:
  `audit-results/fed-statistical-cp-vector-fix-20260831T211133Z-v2/`
- exact union:
  `audit-results/fed-statistical-cp-vector-fix-20260831T211133Z-v2/EXACT_UNION_SUMMARY.json`
- discovery summary:
  `audit-results/fed-statistical-cp-vector-fix-20260831T211133Z-v2/discovery/DISCOVERY_SUMMARY.json`
- per-host campaign summary / physical target receipt:
  `audit-results/fed-statistical-cp-vector-fix-20260831T211133Z-v2/so00{3,4,5,6}-shard-*/`
- checksum:
  `audit-results/fed-statistical-cp-vector-fix-20260831T211133Z-v2/LOCAL_SHA256SUMS.txt`
