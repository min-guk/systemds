# FED Planner P/L/R 감사 체크포인트 — 2026-08-31 22:49 CEST

## 병렬화 상태

현재 작업은 단일 순차 실행이 아니다. `so001`은 proxy이므로 제외하고, root coordinator와
세 전문 lane이 `so002`--`so006`에서 동시에 다음 범위를 처리한다.

| lane | 담당 | 실행 방식 |
|---|---|---|
| root | authoritative 집계, 격리 검증, 잔여 inventory | `so002` |
| statistical | central moment, covariance, ctable | `so003`--`so006`, 4-way shard |
| parameterized | contains, replace, removeEmpty, rexpand, transformencode | `so005`/`so006` |
| residual runtime | append, reshape, frame-scalar, spoof | `so003`--`so006` |

병렬 campaign은 각 서버에서 서로 다른 **physical source-local `target/`**을 사용하고,
`TARGETS_PER_JVM=1`로 target을 격리한다. source hash, manifest hash, target-directory
device/inode/type, artifact checksum을 receipt로 남긴다. 과거 source snapshot이 공유
`/dev/shm` target symlink를 가리킨 invalid campaign을 발견했기 때문에, campaign runner는
이제 symlink, missing target, source 밖 target을 실행 전에 fail-closed한다.

## 새로 확정한 authoritative 범위

Append/Reshape retry campaign은 네 서버에서 독립 build 후 완료됐다.

- expected/result unique targets: `31/31`
- authoritative rows: `31`
- outcome: `31/31 SUCCESS`
- duplicate/missing/unexpected: `0/0/0`
- constraint satisfied: `31/31`
- runtime-capability receipts: `335/335 SUCCESS`
- validation:
  `audit-results/append-reshape-auth2-20260831T203727Z/authoritative-aggregate/TARGET_UNION_VALIDATION.json`

따라서 이전 확정 `335/335`에 이 범위를 더한 provisional 누적은 `366/366`이다. 최종
통합 inventory에 반영하기 전에도 이 수치는 exact target union 검증을 통과했다. 다만 이는
published candidate의 forced execution 범위이므로 `coverageComplete=false`이며 전역
`Missing=(R\cap L)-P=\varnothing`을 의미하지 않는다.

## 병렬화로 발견한 실제 결함

### Parameterized / transformencode

전용 6-method fixture가 ROW/COL/FULL, CP/SPARK 및 `PRIVATE_AGGREGATION` source에서 `6/6`
통과했다. 이 과정에서 dead metadata output을 가진 FED transformencode instruction은 6-part
형식인데 parser가 7-part로 검사하여 마지막 `false` flag를 primary output으로 오인하는 production
defect를 찾았다. `MultiReturnParameterizedBuiltinFEDInstruction`의 optional metadata-output 판정을
SP 형식과 동일한 6-part 조건으로 수정했다. COL 결과의 초기 수치 불일치는 runtime 결함이 아니라
scalar 합계를 `matrix(...)`로 재생성한 test artifact였고, 두 worker의 실제 합 `80+180=260`을
확인한 뒤 scalar tolerance 비교로 교정했다. 임시 debug logging은 제거됐다.

### Statistical

공통 source metadata 보존, ctableexpand, COL-vector central moment/covariance orientation 및 dynamic
recompile persistent-read identity 결함은 focused regression에서 수정·통과했다. 그러나 첫 22-target
4-way forced campaign은 exact target union 자체는 완전하지만 결과가 `SUCCESS 1`,
`FAILURE_REQUIRES_TRIAGE 21`이다. 모든 target은 constraint에 도달했고 runtime capability receipt
`538/538`은 성공했으므로 infrastructure 실패가 아니라, target 하나를 강제할 때 같은 whole-program
fixture의 다른 통계 연산이 `1x12` COL logical vector를 CP central-moment kernel에 전달하는
inter-operation plan/fixture 결합 문제로 분류 중이다. 실패 결과를 성공으로 세지 않고 opcode와
forced state별 isolated replay로 분리하여 runtime defect, planning-space spurious state, fixture
interference 중 어느 것인지 판정한 뒤 failed-only 재실행한다.

## 현재 stop condition

1. parameterized discovery manifest와 forced replay의 exact union 검증,
2. statistical 21건을 원인별 분리하고 failed-only retry 성공 또는 명시적 infeasible 판정,
3. frame-scalar/spoof 잔여 범위의 독립 campaign,
4. authoritative root만 사용한 통합 inventory와 checksum,
5. focused Maven tests, Python audit tests, shell preflight regression, `git diff --check`

가 모두 끝날 때까지 작업을 계속한다.

## 23:04 CEST 병렬 실행 업데이트

### Frame-scalar

`TernaryFrameScalarFEDInstruction`의 `_map`은 실제로 remote frame을 생성하지만 CP/SP→FED
conversion이 `FederatedOutput.NONE`을 기록하던 lowering defect를 `FOUT`으로 교정했다. 네 서버의
독립 target에서 ROW/COL의 CP/LOUT 및 FED/FOUT 4개 target을 각각 실행했다.

- exact union: `4/4 SUCCESS`
- duplicate/missing/unexpected: `0/0/0`
- constraint satisfied: `4/4`
- runtime capability: `20/20 SUCCESS`
- report: `docs/FED_PLANNER_FRAME_SCALAR_P_L_R_AUDIT_2026-08-31_KO.md`

이 결과까지 포함한 provisional 누적 published forced success는 `370/370`이다.

### Parameterized primary

최신 source receipt `7a5dcc...be2af`와 manifest SHA `21d14a...ba34`를 사용한 47-target
PRIVATE_AGGREGATION campaign을 so005/so006에 24/23으로 나눠 실행 중이다. 이전 remote snapshot의
stale scalar assertion 결과는 authoritative 결과에서 제외한다. Identity transformencode canary와
별도로 dummycode의 ROW/COL encoded-column/range 불일치를 negative evidence로 유지하고 runtime
output range 및 metadata merge를 계속 추적한다.

### Spoof structural gap

actual-only codegen fixture에서 최종 HOP planner가 끝난 뒤 runtime codegen이 `spoofCellTMP6` HOP과
instruction을 생성하여 `LOWERING_UNPLANNED`이 발생했다. 즉 default runtime codegen 경로에서는
Spoof occurrence가 shared candidate set `P`에 존재하지 않지만 runtime에는 존재하는 실제 구조적
coverage gap이다. 단순히 fixture에서 제외하지 않고, codegen rewrite 직후 shared analysis/selector의
certified re-entry 또는 phase ordering 교정을 구현·검증 중이다.

### Statistical R 확장

COL vector를 collect한 CP central-moment/covariance 상태를 제외하는 대신, legal `1xN` vector를 CP
kernel boundary에서 canonical `Nx1`로 정규화하여 실제 실행 공간 `R`을 확장했다. 해당
CM-COL/COV-COL CP/LOUT forced canary는 `2/2 PASS`했다. 외부 R `moments` package 부재로 실패한
기존 reference tests는 infrastructure-invalid로 분리하고, self-contained 회귀와 새 22-target
campaign으로 재검증한다.

## 23:32 CEST 병렬 검증 업데이트

### Statistical authoritative 완료

`so003`--`so006`의 서로 다른 physical source-local `target/`에서 22-target manifest를
4-way shard로 다시 실행했다. 이전 `1/22` 결과의 원인은 planner 후보 자체가 아니라, COL
federated vector를 coordinator로 수집했을 때의 `1xN` 표현을 CP central-moment/covariance가
`Nx1`만 허용하던 physical-feasibility 결함이었다. CP kernel 경계에서 합법적인 row vector만
canonical column vector로 정규화한 뒤 다음 exact union을 얻었다.

- expected/result/unique: `22/22/22`
- outcome: `22/22 SUCCESS`
- duplicate/missing/unexpected: `0/0/0`
- constraint satisfied: `22/22`
- runtime-capability receipts: `538/538 SUCCESS`
- source receipt SHA-256: `d51a350164f73fae6a8dcf969f2e4f53312f8a87389afdb2240eec47b14256e8`
- evidence: `audit-results/fed-statistical-cp-vector-fix-20260831T211133Z-v2/`

로컬 symlink target에서 Surefire가 시작 전에 실패한 discovery와 외부 R `moments` package가
없어 종료된 reference run은 authoritative 결과에서 제외했다. 상세 보고서는
`docs/FED_PLANNER_STATISTICAL_P_L_R_AUDIT_PROGRESS_2026-08-31_KO.md`이다.

### Transformencode의 잘못된 Spark checkpoint 제거

Full/Spark transformencode에서 같은 Hop에 다음 물리 chain이 생겼다.

`Federated(INVALID/FOUT) -> ReBlock(FED/FOUT) -> Checkpoint(SPARK/NONE)`

Spark rewrite가 reblock 대상에 남긴 checkpoint request를 compiled planner가 FED/FOUT으로
배치한 뒤에도 `Hop.constructAndSetCheckpointLopIfRequired`가 무조건 적용한 것이 원인이다.
`CheckpointSPInstruction`도 federated object를 실제로 persist하지 않고 alias만 만들기 때문에,
이를 transparent wrapper로 사후 수용하지 않고 **final Lop이 forced FOUT이면 Spark checkpoint를
생성하지 않도록** 수정했다. `FED/LOUT`까지 넓게 억제하지 않고 FederationMap-backed FOUT만
판별해 local result의 합법적인 checkpoint 가능성은 보존했다.

회귀는 실제 `INVALID/FOUT -> FED/FOUT ReBlock` chain과 ordinary `SPARK/NONE -> Checkpoint`
control을 함께 고정한다. `so004` physical target에서 unit test 5/5와 과거 실패 target
`0e63...`, `29e6...`, `7c39...`를 각각 별도 JVM으로 재실행했다.

- retry exact union: `3/3 SUCCESS`
- duplicate/missing/unexpected: `0/0/0`
- constraint satisfied: `3/3`
- runtime-capability receipts: `8/8 SUCCESS`
- physical target: device `2050`, inode `19296529`, symlink 아님
- source receipt SHA-256: `aebf3d1f8233f271d5bc602df45cc243bb87a88d2853a39f50696cac8de20eed`
- evidence: `audit-results/fed-checkpoint-transformencode-fix-20260831T232000Z/`

Parameterized 47-target campaign은 이 retry로 기존 3개 failure가 해소되어 현재 `44/47`
published-state success다. 남은 세 건은 `REXPAND CP/LOUT` 하나와 `rmempty -> rix`로 rewrite된
COL 두 건의 semantic target-not-reached이며, 성공으로 세지 않고 occurrence/rewrite authority를
추적 중이다.

### 추가 병렬 lane

- WCeMM/WUMM은 `10/10 SUCCESS`, spurious `0`, observed confirmed-missing `0`으로 완료했다.
  단, 독립적인 전체 `R` 열거가 아니므로 `coverageComplete=false`이다.
- Spoof는 post-planner runtime codegen으로 P에 없던 HOP이 생기던 phase-ordering gap, FED parser
  누락, FOUT/LOUT 직렬화 누락을 순차적으로 드러냈다. 현재 worker fragment의 폐쇄형 opcode
  compatibility를 수정·검증 중이며 authoritative success로는 아직 집계하지 않는다.
- transformencode dummycode COL에서는 worker별 dummy expansion offset을 반영하지 않아 output
  ranges가 겹치던 runtime 결함을 고쳤다. ROW recode-map ordering의 간헐적 불일치는 별도 negative
  evidence로 유지하며 원인을 추적한다.

현재 root + 세 전문 agent가 병렬로 동작하며, 원격 campaign은 `so001`을 제외한 `so003`--`so006`에
분산한다. 모든 숫자는 source hash, manifest hash, physical target device/inode, checksum을 갖춘
campaign만 authoritative로 취급한다. 여전히 독립적 전체 `R` 열거는 아니므로 전역
`Missing=(R\cap L)-P=\varnothing`은 주장하지 않으며 `coverageComplete=false`를 유지한다.

## 23:47 CEST 병렬화 및 build-freshness 교정

### Spoof exact-source campaign 완료

`so003`--`so005`의 서로 다른 physical target에서 3개 Spoof target을 3-way로 재실행했다.
첫 실행의 `1 SUCCESS / 2 TRIAGE`는 forced CP 상태에도 federated heavy-hitter를 요구한 fixture
assertion이었으며, exact forced-state audit 동안에만 해당 assertion을 비활성화한 동일-source
재실행으로 target별로 대체했다.

- expected/result/unique: `3/3/3`
- outcome 및 constraint: `3/3 SUCCESS`, `3/3 satisfied`
- duplicate/missing/unexpected: `0/0/0`
- runtime-capability receipts: `8/8 SUCCESS`
- exact union: `audit-results/spoof-auth-20260831T213123Z/EXACT_UNION_VALIDATION.json`
- report: `docs/FED_PLANNER_SPOOF_P_L_R_AUDIT_2026-08-31_KO.md`

이를 포함한 completed focused campaign의 target-ID exact union은 `395/395 SUCCESS`, runtime
capability receipt는 `4,112/4,112 SUCCESS`다. 캠페인 간 target-ID overlap은 0이다. 다만 과거
11개 campaign은 source receipt는 있으나 현재 형식의 device/inode receipt가 없어, 모든 과거
campaign의 physical-target identity까지 소급해 증명했다고 주장하지 않는다.

### 53-target parameterized 실행의 stale-bytecode 적발

53-target 확장 campaign은 source receipt SHA
`3588ec8144a5d83f381f7299b3d04bdd9d42133809bb29183331df8876571398`를 사용했지만,
원격 stage에 기존 `target/classes`를 남긴 채 보존된 source mtime으로 복사했다. 그 결과 Maven의
incremental compile이 변경된 `Hop.java`를 건너뛰었다. 실제 증거는 다음과 같다.

1. stage의 `Hop.java`에는 FederationMap-backed FOUT checkpoint suppression이 존재한다.
2. 같은 stage의 `target/classes/.../Hop.class`를 `javap -c`로 확인하면 해당 분기가 없고 과거의
   unconditional Spark checkpoint bytecode가 남아 있다.
3. 따라서 과거에 해결한 동일 target `0e63...`, `29e6...`, `7c39...`가 다시 같은 checkpoint
   오류를 냈다.

이 campaign은 **source/bytecode 불일치로 infrastructure-invalid**이며 authoritative 결과에
더하지 않는다. source hash만 맞으면 된다는 기존 검증도 충분하지 않으므로, runner에 campaign당
한 번의 clean compile 및 build-freshness receipt를 fail-closed로 추가 중이다. 새 physical stage는
`target`을 완전히 제거한 뒤 build하고, source receipt와 실제 bytecode 확인을 통과한 경우에만
`so003`--`so006` 4-way forced replay를 수행한다.

### 남은 독립 lane

- parameterized/transformencode 53-target clean 4-way replay 및 3개 semantic TNR authority 판정,
- specialized MMFED/SPARK 12개 상태용 sparse/large MapMM fixture와 checkpoint auxiliary 계약,
- qsort/qpick/CumulativeOffset의 specialized runtime witness 범위 정량화,
- campaign runner의 stale-bytecode 재발 방지 회귀

를 병렬로 진행한다. 이 추가 coverage 역시 전체 runtime `R`의 독립적 완전 열거는 아니므로
`coverageComplete=false`를 유지한다.
