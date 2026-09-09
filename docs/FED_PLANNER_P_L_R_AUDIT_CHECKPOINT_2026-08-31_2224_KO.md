# FED Planner P/L/R 감사 체크포인트 — 2026-08-31 22:24 CEST

## 목표와 판정 경계

현재 작업은 selector가 공개하는 후보 `P(o,i)`, 공통 privacy/physical legality를 통과하는
상태 `L(o,i)`, 실제 lowering 및 FED runtime에서 성공하는 상태 `R(o,i)`를 exact occurrence와
ordered input signature 단위로 대조한다. Forced published-state 실행은 관측한 `P` 상태가
spurious하지 않음을 검증하지만 독립적인 전체 `R` 열거는 아니므로, 모든 결과는
`coverageComplete=false`로 유지한다.

## 병렬 실행 구성

`so001`은 proxy이므로 사용하지 않는다. 현재 다음 네 lane을 서로 겹치지 않게 운영한다.

| lane | 책임 | 실행 위치 |
|---|---|---|
| coordinator | 통합, source/rewrite 계약 검증, authoritative evidence 판정 | `so002` worktree |
| statistical | source metadata, ctable, central moment, covariance | 독립 test/remote worker |
| parameterized | contains/replace/rmempty/rexpand/transformencode | `so005`, `so006` |
| residual runtime | append/reshape/frame-scalar/spoof | `so003`--`so006` 중 독립 snapshot |

각 remote shard는 physical source-local `target/`, 고정 source receipt, `TARGETS_PER_JVM=1`을
사용한다. 과거 동일 target symlink나 dangling target을 사용한 캠페인은 authoritative 결과에서
제외한다. 공유 production file을 동시에 수정하지 않도록 instruction family별 소유권을 분리했다.

## 새로 확정된 결과

### Ternary/Nary/Quantile

- authoritative target union: `32/32 SUCCESS`
- manifest/result unique target: `32/32`
- duplicate/missing/unexpected: `0/0/0`
- constraint satisfied: `32/32`
- runtime capability receipt: `1,214/1,214 SUCCESS`
- 검증: `audit-results/tnq-auth2-20260831T195218Z/authoritative-aggregate-v2/TARGET_UNION_VALIDATION.json`
- 보고서: `docs/FED_PLANNER_TERNARY_NARY_QUANTILE_P_L_R_AUDIT_2026-08-31_KO.md`

이 캠페인은 초기 30개 성공과 결함 수정 후 isolated retry 2개를 exact target ID로 합쳤다.
발견한 실제 결함은 aggregate consumer에 선택된 relocation/materialization 경계를 ternary
aggregate fusion이 지워 버리는 것이었다. `AggUnaryOp`의 fusion applicability가 planner
boundary를 존중하도록 수정한 뒤 실패한 두 target이 모두 성공했다.

### Unary/Shape/Conversion

- authoritative target union: `52/52 SUCCESS`
- duplicate/missing/unexpected: `0/0/0`
- constraint satisfied: `52/52`
- runtime capability receipt: `240/240 SUCCESS`
- auxiliary runtime 관측: `fed_bcumoffk+`, `fed_bcumoffmax`, `fed_write`, `fed_rblk`
- 검증: `audit-results/fed-runtime-unary-shape-conversion-campaign-20260831T220443Z-retry2/VERIFICATION.json`
- 보고서: 같은 디렉터리의 `REPORT_KO.md`

네 개의 독립 physical build tree에서 13개씩 병렬 실행했다. 잘못된 target symlink를 사용한
앞선 두 캠페인은 infrastructure-invalid로 제외했다. 이 범위에서는 production 결함이 나오지
않았고 fixture/DML/config만 추가되었다.

### 누적 authoritative forced-state 범위

기존 확정 `251/251`에 위 `32/32`와 `52/52`를 더한 현재 누적은 `335/335`이다. 이는 완료된
fixture의 published candidate target에 대한 수치이며 전역 `Missing=0` 주장이 아니다.

## 통계 family에서 새로 발견·수정 중인 결함

1. data-dependent DAG split이 shared federated source의 `ranges` metadata를 transient로 잘라
   공통 pre-selector placement/privacy analysis가 exact partition coordinates를 잃었다.
   `RewriteSplitDagDataDependentOperators`에서 FEDERATED `DataOp`의 metadata subgraph를 atomic
   boundary로 보존했고 관련 placement contract test `9/9`가 통과했다.
2. `ctableexpand` parser/runtime이 sequence marker를 matrix variable로 조회했다. expand flag와
   row-federated worker 실행, LOUT row-bind/FOUT range 보존을 구현했고 기존 parameterized runtime
   test `4/4`가 통과했다.
3. 그 다음 canonical run에서 COL-federated logical vector가 row-vector shard로 worker에 전달되어
   central-moment kernel이 실패했다. UDF boundary에서 합법적인 row-vector shard만 column vector로
   정규화한 뒤 `fed_cm` 세 occurrence가 모두 성공했다.
4. 실행은 다음 독립 단계인 covariance에서도 같은 orientation mismatch를 재현했다. covariance
   입력 정렬을 최소 수정하고 canonical statistical fixture를 끝까지 재실행하는 중이다.

## 현재 진행 중인 다음 범위

- `ParameterizedBuiltinFEDInstruction` 및 `MultiReturnParameterizedBuiltinFEDInstruction`
- `AppendFEDInstruction`, `ReshapeFEDInstruction`, `TernaryFrameScalarFEDInstruction`,
  `SpoofFEDInstruction`
- central moment/covariance/ctable 전체 statistical forced-state campaign

완료 조건은 각 lane의 exact target union 검증, runtime receipt, source/manifest hash, checksum,
targeted regression test를 모두 확보하고 통합 inventory를 authoritative root만으로 재생성하는 것이다.

## 22:40 CEST 병렬 격리 보완

append/reshape의 첫 remote campaign(`append-reshape-auth-20260831T203328Z`)은 source snapshot의
`target/`이 과거 `/dev/shm/...` build tree를 가리키는 symlink임을 coordinator preflight가
발견했다. 실행 중이던 이 campaign 전용 process만 종료했고, 생성된 결과 전체를
infrastructure-invalid로 분류하여 authoritative 집계에서 제외했다.

재발 방지를 위해 `scripts/fedplanner/run_forced_state_campaign.sh`에 fail-closed 검사를 추가했다.

- `target/` symlink는 output 생성 전 exit `78`
- physical source-local `target/`이 없으면 exit `66`
- canonical target path가 source root 밖이면 exit `78`
- 성공한 run은 `RUN_MANIFEST.txt`에 canonical path와 device/inode/type receipt 기록

회귀 스크립트 `scripts/fedplanner/test_run_forced_state_campaign_preflight.sh`는 symlink 거부와
physical target 허용을 모두 검증하며 PASS했다. append/reshape retry2는 so003--so006에서 서로
다른 physical source-local target을 새로 compile/test-compile하는 단계부터 다시 시작했다.

Statistical discovery에서는 runtime 계산과 별개로 dynamic recompilation 후 persistent-read
`createvar` descriptor의 Lop ID가 달라져 audit가 exact identity를 오판하는 문제가 추가로
발견되었다. mutable Lop ID 대신 immutable Hop/origin/recompile signature와 exact source
path/format, reserved `pREAD` symbol을 결합하는 fail-closed identity 검증 및 positive/negative
regression을 적용하고 canonical audit run을 재검증 중이다.
