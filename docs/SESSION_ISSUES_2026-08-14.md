# Session Issues — 2026-08-14

## 1. 최종 source 변경을 포함하지 않는 predecessor stage로는 runtime 충실성을 증명할 수 없음

- **상태**: 진행중; 첫 immutable campaign은 4/336 뒤 fail-closed했고, issue 3 수정 authority에서 실패 cell을 통과해 combined 5/336으로 재개
- **적용 원칙/제약**: 실험은 `run_LAN_docker.sh`만 사용한다. source/JAR/harness/data/reference identity를 immutable stage로 고정하며 runtime fallback/repair를 허용하지 않는다. 이전 성공 row라도 source 의미가 달라졌으면 최종 결과에 합치지 않는다.
- **환경/조건**: active source `/home/mchoi/g014-planning-audit-source-20260810-v1`; base HEAD `a922bee6d16d6514192ece17a4ca462c0c1ecd16`; harness `d712daf82d3023f8f136bb8c348cc04521b72335`; campaign seed `2026072701`.
- **재현 절차**: 이전 output `/home/mchoi/g014-full-results-506368f-d712daf-20260814-v2`의 descriptor identity를 최신 source 변경과 비교한다. 함수 formal-output/caller-TRead, graph-only relocation, qualified namespace 및 recursive alias 수정이 predecessor commit/JAR에 없으므로 해당 21개 row는 최신 authority가 아니다.
- **관측 증상**: predecessor는 21/336에서 중단됐고 이후 source-level correctness 수정이 추가됐다. 따라서 당시 성공 row를 그대로 합치면 source/JAR provenance와 runtime 결과가 일치하지 않는다.
- **원인 분석**: campaign row는 stage의 commit/tree/JAR hash에 종속된다. 동일 workload/configuration이라는 사실만으로 변경 후 planner plan/runtime action이 byte-identical하다고 가정할 수 없다.
- **해결 요약**: active candidate의 tracked 및 non-ignored untracked 12,764개 경로를 별도 clone에 overlay하고 전 경로 byte parity를 확인했다. 이를 clean snapshot commit `cd23d21db46dae0227f6f4d948d78b8e394143d0`, tree `ae0ddbad33a7d3c329e24d8d4e3c49beed9c50fd`로 봉인했다. snapshot에서 재빌드한 JAR과 316개 dependency를 symlink 없는 artifact로 만들고, 인증된 data/reference와 함께 stage `ba7a584dc7203909205434a70a1d57e59c662e8f40620d93975ce4ffb1310e3c`를 생성했다. predecessor는 사용하지 않고 336개 logical cell을 fresh 실행한다.
- **수정 파일**: production 코드는 이 단계에서 추가 변경 없음. `docs/FEDPLAN_RUNTIME_FIDELITY_REPORT_2026-08-14.md`, `docs/FED_PLANNER_ARCHITECTURE_AND_FEDALL_COMPLEXITY_REPORT_2026-08-14.md`, 본 문서.
- **검증**:
  - active↔snapshot parity: `/tmp/g014-current-snapshot-parity-v300.json`, path sets 12,764/12,764, mismatch 0, SHA-256 `74790059fa1e43881312f8f7bbc9f7d9cbc4046d523a241ebc5e0bf12883a9d8`.
  - stage publication 뒤 active tree에는 보고서/status 문서만 추가 갱신됐다. `docs/`를 제외한 executable/test authority 8,421개 경로는 snapshot과 mismatch 0이다: `/tmp/g014-poststage-executable-parity-v307.json`, SHA-256 `2d102720cb6f2b5485ee203a9bae25c285345ad2ceec435b64e9e6b0ca72d43a`.
  - snapshot package: `/tmp/g014-snapshot-package-current-v301.log`, RC=0, SHA-256 `952707e80a69151c2b19e4766f0a95100e6158e14c856876cfa405b457cd4e73`.
  - JAR: `f073be1ed7ae33b127699bf179a97db98c163787cd0a4c3af13ff2ffb93d4114`; artifact `/home/mchoi/g014-planning-audit-artifact-f073be1-20260814-v1`에는 일반 파일 JAR 1개와 lib 316개가 있고 symlink는 0이다.
  - stage descriptor: SHA-256 `05c42455971d3ccffc881b42197a1a5cdf2355e4b6dc93669bebe6dc08496b1b`; create와 stage-local replay JSON은 byte-identical(`/tmp/g014-stage-create-current-v302.json`, `/tmp/g014-stage-replay-current-v303.json`). staged JAR hash도 일치한다.
  - campaign: 종료된 PID receipt `/tmp/g014-campaign-current-v304.pid`, log `/tmp/g014-campaign-current-v304.log`, output `/home/mchoi/g014-full-results-cd23d21-d712daf-20260814-v1`. profile-major 순서는 WAN-Light→WAN-Mid→LAN이며 각 workload/profile의 planner/worker 실행 순서는 시간·warmup 순서 효과를 상쇄하는 frozen Williams schedule이다. row 5의 runtime audit mismatch에서 재시도 없이 fail-closed했다.
  - 첫 row `DP/KMeans/workers=1/WAN-Light`는 warm 85.403초, cold 91.368초로 성공했다. semantic oracle=true, fallback=false, runtime scan clean=true, teardown zero=true이며 두 coordinator 모두 planned/lowered physical Hop 140/140, mismatch/missing physical/missing synthetic 0이다. planner emission은 327 decisions, FED 33, FOUT 29이고 cold/warm runtime-plan SHA 및 instruction fingerprint가 동일하다.
  - runtime fix snapshot: commit `087f346d3ee589181e85671a4a91676780dc6274`, tree `a82358a293ca59455e01c9babe815efd760c95dc`, JAR `c7d772bf451cd1ee8b85d27623cc89914381ed97897f6c235524c3f33c3d4048`, stage `c50e9a15c9c871a8b01ddd40d15b347ae0dc61085a51a0f83d0049286d430310`. active↔snapshot 12,765 paths mismatch 0(`/tmp/g014-runtimefix-snapshot-parity-v314.json`), stage create/replay byte-identical(`/tmp/g014-stage-create-runtimefix-v318.json`, `/tmp/g014-stage-replay-runtimefix-v319.json`).
  - continuation output `/home/mchoi/g014-full-results-087f346-d712daf-20260814-v2`는 predecessor의 성공 prefix 4개를 SHA/row evidence로 인증해 재실행하지 않았다. 첫 신규 cell(row 5)은 성공해 combined 5/336이며 warm 37.513초, cold 39.869초, semantic oracle=true, fallback=false, runtime scan clean=true, teardown zero=true다. cold/warm 모두 planned/lowered physical Hop 142/142, missing physical/synthetic 0, mismatch 0이며 normalized runtime-plan SHA가 동일하다.
  - 같은 continuation의 KMeans/workers=2 네 planner block은 combined 8/336까지 완료됐다. warm runtime은 MinST 32.846 < DP 33.845 < Heuristic 36.819 < FedAll 37.513초이며 네 row 모두 cold/warm planned/lowered 142/142, missing/mismatch 0, oracle/scan/teardown PASS, fallback=false다. FedAll과 Heuristic은 marker 0인 정책 조건에 따라 placement/runtime-plan이 동일하고, DP와 MinST는 서로 다른 emission placement와 runtime-plan을 실제로 실행했다.
- **잔여 이슈**: 336/336 성공, 각 row의 planner→lowering→coordinator→worker exact audit mismatch 0, semantic oracle/cold-warm/fallback gate 통과를 확인한다. fail-closed 중단 시 성공 prefix를 중복 실행하지 않고 새 immutable stage의 authenticated predecessor로 실패 cell부터 재개한다.
- **잠재 회귀 위험**: campaign 중 source를 수정하고도 기존 stage 결과를 최신 결과로 오인할 수 있다. row manifest의 `systemds_commit`, `systemds_jar_sha256`, `stage_id`와 active fix provenance를 비교해 감지한다.
- **의사결정 근거**: planning-hash 동일성을 추정하지 않고 exact source/JAR provenance를 우선했다. runtime/플래너 규칙은 완화하지 않았다.

## 2. campaign 사전 process 검사 패턴이 자기 launcher shell을 active runner로 오인함

- **상태**: 해결
- **적용 원칙/제약**: 중복 Docker campaign은 금지하되, 안전 검사 자체의 false positive 때문에 실행을 막지 않는다.
- **환경/조건**: campaign을 시작하는 단일 `bash -lc` 명령 안에서 `pgrep -f '[r]un_one_pass_performance.py'`를 사용한 최초 launch 시도.
- **재현 절차**: shell command text 자체에 runner filename이 포함된 상태에서 위 `pgrep -f`를 실행한다.
- **관측 증상**: 실제 Python campaign은 없었지만 `pgrep`가 현재 launcher shell PID를 반환해 launch가 Docker 시작 전에 중단됐다. output/log/pidfile은 생성되지 않았다.
- **원인 분석**: `pgrep -f`는 프로세스 executable이 아니라 전체 command line을 검색하므로, 검사 문자열을 포함한 현재 shell도 일치했다.
- **해결 요약**: 실제 interpreter command로 시작하는 `^python3 .*run_one_pass_performance\.py`만 검사하도록 anchor를 좁혔다. 이후 detached runner PID `146873`이 PPID 1, SID self로 생존하고 첫 cell의 `run_LAN_docker.sh` child가 시작됐다.
- **수정 파일**: production/harness 변경 없음. 운영 launch command와 본 문서만 갱신.
- **검증**: 최초 시도 후 output/log/pidfile 및 Docker resource가 모두 없음을 확인했다. 수정한 검사 뒤 PID `/tmp/g014-campaign-current-v304.pid`가 생성됐고 실제 Python runner 명령과 stage/output identity가 일치한다.
- **잔여 이슈**: 없음.
- **잠재 회귀 위험**: interpreter path가 `/usr/bin/python3` 또는 wrapper로 바뀌면 anchored 검사가 active process를 놓칠 수 있다. PID file의 `/proc/<pid>/cmdline`, output manifest stage identity, Docker project labels를 함께 검사한다.
- **의사결정 근거**: 실험/runtime 의미는 건드리지 않고 launcher liveness 검사만 실제 프로세스 형태에 맞췄다.

## 3. matrix-scalar FED 명령이 planner의 `LOUT`을 무시하고 항상 federated mapping을 생성함

- **상태**: 해결; RED→GREEN, 관련 runtime 계약/package 및 새 Docker stage row 5 exact audit 완료
- **적용 원칙/제약**: runtime은 planner가 직렬화한 `FED/LOUT`을 그대로 실행해야 하며 fallback 또는 사후 audit 완화는 허용하지 않는다. runtime이 지원하는 후보를 닫기 전에 실제 instruction 구현과 출력 비용/형태를 확인한다.
- **환경/조건**: immutable stage `ba7a584dc7203909205434a70a1d57e59c662e8f40620d93975ce4ffb1310e3c`; WAN-Light; KMeans; FedAll; workers=2; cold phase; campaign row 5.
- **재현 절차**: `/home/mchoi/g014-full-results-cd23d21-d712daf-20260814-v1/cells/005-10e911bd8c36`의 cold coordinator 로그를 확인한다. DML `scripts/builtin/kmeans.dml:213`의 `Y = rowSums(...) + 1`에서 생성된 scalar-binary `+` 명령이 `... ?LOUT`으로 직렬화됐지만 실행 직후 출력 `_mVar380`은 `FED/FOUT/ROW`로 관측된다.
- **관측 증상**: `[PlannerRuntimeAudit] RUNTIME_VALUE_MISMATCH ... hop=668 ... plannedTarget=FED/FOUT/BROADCAST/SHAPE_DEPENDENT|derivedFedFout=true plannedPhysical=FED/LOUT/ROW actual=FED/FOUT/ROW ... instruction="FED?+?...?LOUT"`. campaign은 fail-closed로 4/336 성공 뒤 중단됐고 teardown leak은 0이다.
- **원인 분석**: `BinaryMatrixScalarFEDInstruction.processInstruction`은 파싱된 `_fedOut`을 전혀 검사하지 않고, worker 실행 뒤 무조건 `out.setFedMapping(mo.getFedMapping().copyWithNewID(...))`를 호출한다. 반면 같은 binary 계열의 `BinaryMatrixMatrixFEDInstruction`은 `_fedOut.isForcedLocal()`이면 `GET_VAR`로 결과를 회수하고 ROW/COL 방향에 맞게 결합해 local matrix를 설정한다. 따라서 planner/lowering은 `FED/LOUT/ROW`을 정확히 전달했지만 scalar runtime이 계약을 위반했다.
- **해결 요약**: matrix-scalar에도 동일한 forced-local 회수/결합 semantics를 적용했다. worker 실행 응답을 확인한 뒤 `GET_VAR`와 cleanup을 같은 mapping에 보내고, ROW는 rbind, COL은 cbind, BROADCAST는 동일 결과 중 하나를 취해 `ExecutionContext.setMatrixOutput`으로 local output을 만든다. `FOUT` 경로와 local-input CP fallback 금지는 그대로 유지했다.
- **수정 파일**: `src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixScalarFEDInstruction.java`, `src/test/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixScalarFEDInstructionNoFallbackTest.java`, 본 문서 및 두 2026-08-14 보고서.
- **검증**:
  - RED: `/tmp/g014-binary-scalar-lout-red-v309.log`, 3 tests 중 forced-local test 1 failure. 실제 실패는 `expected null`인데 output에 `Fed Map: ROW`가 남는 것으로 campaign 증상을 최소 재현했다.
  - GREEN: `/tmp/g014-binary-scalar-lout-green-v310.log`, focused 3/3 PASS. `LOUT` 결과 4×3 ROW 결합 값, output mapping 제거, input mapping 불변, GET/cleanup 각 1회와 기존 `FOUT` mapping 보존을 검증했다.
  - 관련 runtime/lowering 계약: `/tmp/g014-binary-scalar-runtime-contract-v311.log`, 46/46 PASS, RC=0 (`BinaryMatrixScalar`, `BinaryMatrixMatrix`, planner runtime audit, absent-local lowering, FOUT fallback 금지).
  - package: `/tmp/g014-active-package-runtimefix-v312.log`, RC=0, SHA-256 `8dde7bdb7c205de3687ddc030fddb3abc8c6cbfb1680aaafac103a58114a4c89`.
  - Docker 재검증: `/home/mchoi/g014-full-results-087f346-d712daf-20260814-v2/cells/005-10e911bd8c36`. `FedAll/KMeans/workers=2/WAN-Light` cold/warm 모두 semantic oracle PASS, fallback=false, runtime scan clean, planned/lowered physical Hop 142/142, missing physical/synthetic 0, mismatch 0, teardown zero다. 동일 normalized runtime-plan SHA `6c478dddef7206e9ed6df3873a2ca4e5748ad4dd7aa8ab0b8edd213bdd2cdd8b`로 cold 39.869초, warm 37.513초를 완료했다.
- **잔여 이슈**: 이 instruction의 재현 cell은 해결됐다. 이후 다른 instruction의 output-contract mismatch가 있으면 campaign exact audit가 재시도 없이 fail-closed하며 별도 원인으로 분석한다.
- **잠재 회귀 위험**: `LOUT` 회수 시 worker output cleanup을 누락하면 원격 변수가 누적되고, 분할 방향을 잘못 결합하면 값/shape가 손상될 수 있다. request 종류/호출 횟수와 결과 shape/value, output mapping 부재를 함께 검사한다.
- **의사결정 근거**: runtime이 이미 `LOUT` 토큰을 파싱하고 같은 binary family가 이를 지원하므로 합법 후보를 제거하지 않고 runtime 계약을 바로잡는다.
