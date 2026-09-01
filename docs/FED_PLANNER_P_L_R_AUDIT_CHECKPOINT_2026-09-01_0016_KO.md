# Federated Planner P/L/R Audit 병렬 진행 체크포인트

- 작성 시각: 2026-09-01 00:17 CEST
- 작업 트리: `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`
- 실행 노드: coordinator `so002`, workers `so003`–`so006` (`so001` 사용 금지)
- 상태: **진행 중**, `coverageComplete=false`

> **2026-09-01 strict-replay 정정:** 이 문서의 `460/4316` 합계는 exact JUnit
> leaf identity를 강제하기 전에 수집한 **historical pre-hardening evidence**이다.
> 해당 수치는 당시 실행의 성공 합계로는 유효하지만, `replayInvocation`과
> `exactReplayLeaf=true`를 요구하는 현재 strict contract의 authoritative count가
> 아니다. 기존 checksummed artifact는 보존하며, strict 재검증 수는 별도 status
> artifact와 후속 체크포인트에서만 보고한다.

## 1. 병렬 실행 구성

현재 네 개의 독립 lane을 동시에 사용한다.

1. root: campaign runner/manifest builder/집계 도구의 로컬 회귀 검증과 체크포인트 통합
2. aggregate-runtime-space agent: parameterized 3개 TNR 원인 수정 및 `so003`–`so005` 교정 재실행
3. quantile-aux-closure agent: qsort/qpick selector auxiliary proof와 CumulativeOffset runtime-planner witness를 `so005`/`so006`에서 RED/GREEN 검증
4. final-parallel-reconcile agent: 기존·신규 캠페인 target-ID overlap, 성공 수, capability 수를 read-only로 독립 재집계

서버별 stage와 Maven `target`은 물리적으로 분리했으며, 로컬 worktree의 symlink `target`은 빌드에 사용하지 않는다.

## 2. 병렬화로 발견·수정한 핵심 결함

53-target parameterized 캠페인의 최초 병렬 실행은 최신 source를 복사했어도 보존된 mtime과 기존 `target/classes` 때문에 Maven incremental compile이 `Hop.java`를 다시 컴파일하지 않았다. 따라서 최신 source와 실행 bytecode가 달랐다. 이 실행은 **INFRA_INVALID**로 폐기했다.

수정된 campaign runner는 다음을 강제한다.

- source-root 단위 `flock`
- campaign 시작 전 정확히 한 번의 `mvn -q -DskipTests clean test-compile`
- clean build 실패 시 target JVM 실행 금지
- source-local physical `target` device/inode 재검증
- `Hop.class`와 forced-runner test class SHA-256 증거 기록

freshness 수정 후 `so003`–`so006`의 clean bytecode SHA가 일치했다.

- `Hop.class`: `dafd924c11d08261276ecbf82fb710ae8d4381938dcb4dd23715ee36e735adca`
- runner test class: `eb16c3c9dcd30e7ce8a5b12e363d9fc2e858649019a68ee3968155b8d38da31a`

## 3. Historical pre-hardening 결과

### 이미 닫힌 범위

- 기존 focused baseline: `395/395` target SUCCESS, runtime capability `4,112/4,112`
- Spoof: `3/3` target SUCCESS, capability `8/8`
- MMFED/SPARK specialized closure: `12/12` target SUCCESS, constraint `12/12`, capability `59/59`

### Parameterized clean campaign

- manifest: 53 unique targets
- 교정 전 결과: `50 SUCCESS + 3 deterministic TARGET_NOT_REACHED`
- isolated-context 교정 후 당시 결과: `53/53 SUCCESS`
- constraint: `53/53`
- 당시 runtime capability: `145/145`

독립 재집계 기준 historical additive total은 다음과 같다.

- unique successful targets: `460`
- unresolved targets: `0`
- successful runtime capabilities: `4,316`
- cross-campaign target-ID overlap: `0`

`GLOBAL_BASELINE_COMBINATION.json`과 top-level checksum은 당시 합계와 일치한다.
그러나 이 checksummed 파일은 immutable historical artifact로 취급하며 strict-authoritative
증거로 재사용하지 않는다. `coverageComplete=false`는 유지한다.

## 4. 3개 TNR의 교정

초기 가설은 joint-forcing prerequisite 누락이었지만, isolated discovery를 비교한 결과 더 직접적인 원인은 **mixed-context discovery JVM에서 predecessor/layout shape proof가 섞이는 현상**으로 좁혀졌다.

- mixed discovery: `rix`와 `REXPAND`가 `COL` predecessor/input boundary로 기록됨
- isolated discovery: 동일 occurrence가 `FULL` boundary로 정상화됨
- 결과적으로 기존 manifest는 selector가 실제 독립 실행에서 도달할 수 없는 오염된 boundary signature를 강제함

`build_forced_state_manifest.py`에 `--require-isolated-runtime-context` guard를 추가했다.

- mixed-context manifest: 의도대로 RED (`rc=1`)
- manifest builder unit tests: `2/2` GREEN
- 교정 manifest: `rix FULL/FOUT`, `rix FULL/LOUT`, `REXPAND PRESENT:FULL`
- `so003`–`so005` target별 clean campaign: `3/3 SUCCESS`
- 세 target 모두 constraint satisfied, runtime capability `8/8 SUCCESS`
- corrected authoritative union: `53/53 SUCCESS`, missing/duplicate/unexpected `0/0/0`
- corrected manifest SHA-256: `22332c023de9e3dfd9e3dc2730412be3f3482233a8ad6690c4de9418e330fb10`

따라서 별도 joint-assignment serialization은 당시 증거상 필요하지 않았다. 기존 세 mixed-context target은 planner/runtime failure가 아니라 infrastructure-invalid discovery로 supersede했으며, 입력 signature를 무시해 억지로 성공 처리하지 않았다. 단, 이 결과 역시 exact-leaf strict contract 도입 전 historical evidence이다.

## 5. Quantile/CumulativeOffset 별도 증명

두 proof class를 섞지 않는다.

1. compiled selector quantile occurrence: qsort auxiliary + qpick owner를 selector P 권위 아래 검증
2. runtime-planner cumulative occurrence: `CumulativeOffsetFEDInstruction` 변환을 Hop-origin witness로 검증하며, selector P의 새 target으로 세지 않음

RED에서 확인한 문제:

- `CUMULATIVE_OFFSET` origin tag 미설정
- 과도하게 큰 fixture가 별도 `ucumack+` worker 오류를 유발

수정:

- `UnaryOp.constructCumOffBinary`에 Hop ID 및 `CUMULATIVE_OFFSET` tag 전달
- brittle 문자열 assertion 완화
- single-block 12-row fixture로 증명 범위 축소

현재 `so005`(unit)와 `so006`(integration)에서 clean GREEN 실행 중이며, source/target inode와 source/class SHA를 함께 수집한다.

## 6. 방금 완료한 root 검증

다음 로컬 비-Maven 검증은 모두 통과했다.

- `test_run_forced_state_campaign_preflight.sh`: PASS
- `test_build_forced_state_manifest.py`: 2/2 PASS
- 모든 `scripts/fedplanner/*.py`: `py_compile` PASS
- 모든 `scripts/fedplanner/*.sh`: `bash -n` PASS
- forced-result aggregator: 3/3 PASS
- P/R comparison: 1/1 PASS
- runtime-space inventory: 1/1 PASS

## 7. 남은 stop condition

1. quantile/CumulativeOffset clean GREEN 증거 회수
2. 전체 보고서 checksum/path 일치 최종 검증
3. 원격 targeted Maven tests와 최종 `git diff --check`

이 항목들을 닫기 전에는 전체 runtime instruction space에 대해 `Missing=0` 또는 audit complete라고 주장하지 않는다.

## 8. 00:36 CEST adversarial review 후속 조치

독립 code review는 corrected union의 산술과 identity를 재검증했지만, audit harness와
multi-block auxiliary 증명에 추가 soundness gap을 발견했다. 따라서 본 체크포인트의
`460/460`은 **pre-hardening historical target union의 실행 성공 수**이며,
현재 strict exact-leaf authoritative count도 전체 R의 완전성 증명도 아니다.

확인된 보완 항목:

1. isolation guard가 `functions/applications` prefix만 세고, unknown/zero context 및 동일
   parameterized method의 서로 다른 leaf를 구분하지 못한다.
2. mixed-context 상태 차이를 isolated discovery로 우회했으나, production JVM의 순차 compilation에서도
   발생 가능한 global/cache/config state leak의 root cause를 아직 제거하지 않았다.
3. campaign lock이 `/tmp` 기반 host-local lock이라 공유 source root를 여러 host가 동시에 사용할 때
   직렬화하지 못한다.
4. source checksum receipt를 선택적으로 기록했을 뿐 build 전 `sha256sum -c` fail-closed 검증이 없었다.
5. CumulativeOffset 증명은 single-block 12-row 경로이며 multi-level helper의 동일 Hop ID가 Dag mapping에서
   충돌하지 않는지 검증하지 않았다. 2000-row fixture에서 `ucumack+` worker parsing gap도 별도로 관찰됐다.

병렬 수정 lane:

- audit-harness lane: exact JUnit leaf/invocation identity, shared-root lock, mandatory source checksum 검증
- state-leak debugger lane: alone vs predecessor-then-COL same-JVM differential RED/GREEN 및 누수 reset
- cumulative lane: rows > blocksize helper identity와 audit off/on numerical equivalence, multi-block parsing gap 분리

추가 원격 회귀검증은 이미 `so003/so004`에서 완료했다.

- Maven 6 classes / 93 tests: `93 PASS`, failure/error/skip `0/0/0`
- shell campaign preflight: `1 PASS`
- Python audit suites: `7 PASS`
- artifact: `audit-results/targeted-regression-20260831T222738Z/`

Quantile/CumulativeOffset의 현재 좁은 GREEN 증거:

- selector quantile proof-only target: `1/1 SUCCESS`, constraint satisfied
- qsort/qpick logical lowering 및 single-block `bcumoffk+` runtime conversion: PASS
- report: `docs/FED_PLANNER_QUANTILE_CUMULATIVE_AUXILIARY_WITNESS_2026-09-01_KO.md`
- proof-only target은 기존 global 460 count에 더하지 않는다.
