# FED Planner P/L/R 감사 진행 보고서 (2026-08-31)

> 최신 운영 checkpoint는
> `docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_2101_KO.md`를 참조한다.

## 1. 서버 사용 정책 정정

- `so001`은 proxy 서버이므로 실행 서버에서 영구 제외했다.
- `so001`에서 실행 중이던 process tree를 종료했고, partial output은 다음 경로에 진단용으로 격리했다.

```text
/home/mchoi/fed-space-forced-current-20260830T222317Z/
  proxy-excluded-so001-shard-0-20260831T071100/
```

- 이 산출물은 최종 `P/L/R`, Missing, Spurious, 성공률, 실행시간 집계에 사용하지 않는다.
- 기존 supervisor도 종료했다.
- 수정 전 하네스로 돌고 있던 `so004/so006` shard 역시 중단 후 `HARNESS_OBSOLETE.txt`로 표시했다.
- 허용 범위는 `so002--so006`이다. 현재 캠페인은 `so003/so004/so006`을 사용한다.
  - `so002`: 허용 서버지만 root filesystem 98% 사용 및 별도 SliceLine 실행 때문에 이번 shard에서는 제외했다.
  - `so005`: host key를 명시적으로 등록한 뒤 v3 별도 source snapshot의
    compile/focused-test 검증에만 사용했다. primary campaign에는 추가하지 않았다.

## 2. 수정 전 캠페인을 폐기한 이유

수정 전 forced-state 결과에는 서로 다른 세 가지 하네스 문제가 있었다.

1. **한 Surefire JVM에서 너무 많은 target을 연속 실행**
   - 수백 target 뒤 `TARGET_NOT_REACHED`였던 표본이 isolated JVM에서는 `SUCCESS`가 됐다.
   - worker/port/static optimizer/test configuration 상태가 target 사이에 남을 수 있었다.
2. **재현 시 바뀌는 control-region ordinal**
   - 동일한 DML source occurrence가 discovery에서 `main/5/branch-if/0`, replay에서 `main/2/branch-if/0`로 생성됐다.
   - 기존 replay hash는 이 차이를 다른 occurrence로 보았다.
3. **Parameterized JUnit method의 case identity 부재**
   - discovery context는 `Class#method`만 저장했지만 runner는 모든 `[i]` case를 한꺼번에 실행했다.
   - target을 보지 않은 sibling case의 실패가 target 결과에 섞일 수 있었다.

## 3. 구현한 수정

### 3.1 Replay occurrence identity

Production `CompiledHopKey`는 바꾸지 않았다. 감사 전용 replay hash만 다음처럼 수정했다.

- control path에서 숫자로만 된 statement-block segment를 `*`로 정규화
- function namespace, semantic control token, recompile context 유지
- emitted HOP root identity와 canonical source origin 유지
- loopback worker port만 `<port>`로 정규화
- 정규화 hash가 한 analysis 안에서 둘 이상의 decision domain과 일치하면 forcing은 계속 fail-closed

기존 discovery JSONL의 full length-prefixed occurrence signature를 Python manifest builder가 직접 decode하고 새 Java 규칙과 동일하게 hash하도록 수정했다. 14,024개 candidate row를 재계산했고 fallback은 0개였다.

### 3.2 Parameterized replay

Runner는 method의 leaf description을 하나씩 실행한다. `CONSTRAINT_APPLIED`가 기록된 case에서만 멈추며, target을 보지 못한 앞선 sibling failure는 target failure로 합치지 않는다.

### 3.3 JVM 격리

Campaign runner는 manifest를 deterministic chunk로 분할하고 chunk마다 새 Maven/Surefire JVM을 시작한다.

- primary pass: 10 targets/JVM
- non-success 후속 검증: 1 target/JVM

### 3.4 전역 infeasibility 영수증

강제 candidate가 local published `P`에는 있지만 동일한 whole-program hard constraints 아래 완성 불가능한 경우 Exact solver가 `WHOLE_PROGRAM_INFEASIBLE` event를 기록하도록 했다. 이를 runtime failure나 `TARGET_NOT_REACHED`와 구분한다.

## 4. 검증 결과

로컬 및 `so006/so003`에서 다음을 통과했다.

- Java test compilation
- `PlannerSpaceAuditTest`
- `ExactPhysicalForcedStateAuditTest`
- `FederatedForcedStateAuditRunnerSelectionTest`
- Python `py_compile`
- campaign shell `bash -n`
- `git diff --check`

대표 표본 결과:

| 기존 문제 | 수정 후 결과 |
|---|---|
| 장시간 shared-JVM 뒤 `TARGET_NOT_REACHED` | isolated `SUCCESS`, constraint satisfied |
| L2SVM `TWrite Y` occurrence mismatch | discovery/replay hash 일치; `WHOLE_PROGRAM_INFEASIBLE`로 정확히 분류 |
| Var parameterized method sibling 혼합 | target case만 선택 및 constraint satisfied; 강제 CP plan이 테스트의 `fed_uavar` baseline assertion을 위반하여 `FAILURE_REQUIRES_TRIAGE` 유지 |

마지막 표본은 더 이상 sibling 오염이 아니다. 실제 forced alternative 때문에 workload의 planner-specific assertion이 실패한 것이므로 runtime infeasibility로 만들지 않았다.

## 5. 현재 authoritative 입력

```text
Source root:
  /home/mchoi/fed-space-audit-current-20260830T222317Z
Manifest:
  final-input/forced-state-manifest-normalized-v2.jsonl
Targets:
  5,408
Manifest SHA-256:
  f3f31f509290ed19d90671c2fb0b26e2b450f41e43f86862cf4b82c3fd2df7e1
Staged source receipt SHA-256:
  cca27c6a51a0c6fac1a61e645a5de7a5ce1920309d2cc87234a1e8fdd655c233
```

이전 5,409개에서 한 개 감소한 이유는 P2FFN parameterized 실행의 동일 literal occurrence가 unstable numeric ordinal 제거 후 결정론적으로 합쳐졌기 때문이다. 두 discovery hash는 provenance에 보존한다.

## 6. 현재 실행 상태

```text
Campaign: fed-space-forced-normalized-v2-20260831T053209Z
so003: shard 0/3, PID 3464368, 181 chunks, running
so004: shard 1/3, PID 4081699, 181 chunks, running
so006: shard 2/3, PID 2633716, 181 chunks, running
```

09:54:50 CEST checkpoint:

| Host | 완료 chunk | 생성 결과 | SUCCESS | TARGET_NOT_REACHED | WHOLE_PROGRAM_INFEASIBLE | TRIAGE |
|---|---:|---:|---:|---:|---:|---:|
| `so003` | 165 | 1,646 | 1,377 | 137 | 13 | 119 |
| `so004` | 161 | 1,602 | 1,361 | 130 | 17 | 94 |
| `so006` | 161 | 1,606 | 1,362 | 138 | 19 | 87 |
| **합계** | **487** | **4,854** | **4,100** | **405** | **49** | **300** |

전체 5,408 target 중 4,854개(89.76%)의 primary result가 생성됐다. 현재
표의 non-success는 최종 판정이 아니며, primary 뒤의 1-target/JVM isolated
결과가 최종 분류의 입력이다.

이 checkpoint의 machine-readable status, 세 watcher script, corrected Surefire
결과 및 검증된 checksum은 다음 산출물에 고정했다.

```text
audit-results/fed-space-forced-normalized-v2-20260831T053209Z-progress-20260831T095447+0200/
SHA256SUMS.txt SHA-256:
  136ee7028f37befc22162827a665bdecac9ae7a00a8a86e2fc0b60808e011776
```

현재 provisional TRIAGE 300개는 전부 runtime-capability evidence를 남겼다.
총 584개 instruction outcome이 모두 `SUCCESS`이고 constraint violation은 0이다.
현재 failure 문자열은 forced alternative가 planner-specific heavy-hitter assertion을
바꾼 경우이며, known physical failure 증거는 아니다. authoritative 결과는
1-target/JVM isolated re-entry가 primary를 대체한 뒤 확정한다.

세 supervisor 모두 살아 있고 manifest/source checksum이 세 호스트에서 동일하다.

각 호스트에는 primary 종료 후 모든 non-`SUCCESS` target을 정확한 target ID로
추출하고 1 target/JVM으로 자동 재실행하는 watcher도 연결했다.

```text
Watcher script SHA-256:
  a10948b10e8ffcb844169befe992c3e089e475598f077de1b602734d5aa500c1
so003 watcher PID: 3479872
so004 watcher PID: 4096087
so006 watcher PID: 2650749
```

### 6.1 `TARGET_NOT_REACHED` 조기 분석

초기 snapshot의 121개 `TARGET_NOT_REACHED`는 모두 L2SVM 두 context에
집중됐다.

```text
runL2SVMFunctionPlannerHeuristicPrivacyNone: 110
runL2SVMPlannerDPPrivacyPrivateAggregate:      11
```

서로 다른 opcode/state 네 개를 즉시 1 target/JVM으로 재현한 결과:

```text
Heuristic function target 3개: 3/3 SUCCESS
DP dynamic target 1개:         TARGET_NOT_REACHED 유지
```

따라서 primary의 다수 미도달은 10-target chunk 안에서도 남는 static
worker/optimizer/test 상태 간섭이며, 연결해 둔 isolated watcher가 이를
교정한다.

DP context의 미도달 11개는 모두 새 JVM에서 한 target씩 추가 재실행했다.
11개 모두 `TARGET_NOT_REACHED`가 유지됐지만, JUnit workload는 정상 실행됐고
관측된 FED runtime instruction 33개도 전부 `SUCCESS`였다. 즉 강제 constraint가
적용될 occurrence가 clean replay에서 동일 identity로 재구성되지 않은 것이며,
물리 실행 실패나 privacy 위반 증거가 아니다.

Discovery의 full occurrence와 clean replay candidate를 canonical source/opcode,
ordered input signature로 대조한 결과는 다음과 같다.

| 분류 | target 수 | 의미 |
|---|---:|---|
| `EMITTED_ROOT_ORDINAL_DRIFT` | 3 | 동일 source occurrence가 있으나 `root-N` ordinal만 변경 |
| `EMITTED_INPUT_PATH_DRIFT` | 3 | 동일 source occurrence가 있으나 dynamic rewrite가 root/input path를 변경 |
| `SOURCE_NOT_EMITTED_IN_CLEAN_REPLAY` | 5 | discovery에서만 생성된 compile/recompile occurrence; clean replay에서는 제거 또는 다른 HOP 형태로 변환 |

따라서 v2 replay identity는 production occurrence identity를 보존한다는 점에서는
안전하지만, 감사 재현 키로는 emitted root/input path에 지나치게 민감하다.

이를 production identity와 분리된 감사 전용 secondary semantic key로 보완했다.

1. 기존 structural replay key가 일치하면 항상 그것을 우선한다.
2. structural key가 없을 때만 function/control/recompile context와 canonical source
   origin으로 semantic key를 조회한다.
3. 현재 analysis에서 정확히 한 decision domain과 일치할 때만 강제를 허용한다.
4. 두 개 이상이면 `REPLAY_IDENTITY_AMBIGUOUS`를 기록하고 fail-closed한다.
5. 0개이면 `TARGET_NOT_REACHED`를 유지하고 physical infeasibility로 해석하지 않는다.

동일한 DP 11개 target을 새 구현으로 다시 1 target/JVM 실행한 결과:

| 결과 | target 수 | 해석 |
|---|---:|---|
| `SUCCESS` | 5 | semantic key가 유일한 현재 domain을 찾아 constraint 적용·충족·실행 성공 |
| `REPLAY_IDENTITY_AMBIGUOUS` | 1 | 동일 semantic source에 두 domain이 있어 강제하지 않음 |
| `TARGET_NOT_REACHED` | 5 | clean replay에서 discovery source HOP 자체가 생성되지 않음 |

성공 5개는 모두 forced receipt의 `matchMode=SEMANTIC_UNIQUE`를 남겼고, 전체
runtime capability는 32/32 `SUCCESS`였다. Java가 기록한 semantic hash 2,508개를
Python manifest builder가 occurrence signature에서 재계산한 값과 전부 대조했으며
mismatch는 0개였다. focused Java tests, test compilation, Python `py_compile`,
`git diff --check`도 통과했다.

진행 snapshot과 checksum:

```text
audit-results/fed-space-forced-normalized-v2-20260831T053209Z-progress-20260831T060348Z/

audit-results/fed-space-dp-tnr-isolated-all-20260831T081822Z/

audit-results/fed-space-dp-tnr-v3-final-20260831T084951Z/
```

두 번째 산출물은 11개 isolated campaign 원본, discovery evidence, 원인 분류
JSON, campaign checksum 및 전체 artifact checksum을 포함하며 두 checksum
검증 모두 통과했다.

세 번째 산출물은 semantic fallback 적용 후 11개 재실행 원본, 명시적 ambiguity
receipt, 검증 요약 및 이중 checksum을 포함한다. 현재 원격 primary/watcher는
중단하거나 바꾸지 않았다. v3는 primary와 기존 isolated watcher가 끝난 뒤에도
남는 미도달 target에만 적용하여 불필요한 전체 재실행을 피한다.

전체 authoritative discovery 14,024 candidate row에도 동일한 builder를 적용해
v3 manifest를 미리 생성했다.

```text
Remote:
  so006:/home/mchoi/fed-space-audit-v3-prepared-20260831T085300Z/
Local:
  audit-results/fed-space-v3-manifest-prepared-20260831T085300Z/
Targets: 5,408
Manifest SHA-256:
  ca5f8a5a80af817493f397728ce76ec03cc3d59e5b112e826ef2d884f8a1f37a
Builder SHA-256:
  a73cd00dc2ff491672c202b6a1b9bf00de1013abc1d9777b1f017061a850ed2e
```

v2와 v3의 5,408 target ID는 완전히 동일하고 semantic 필드를 제외한 차이는
0개다. semantic key가 비어 있는 행도 0개다. context별 semantic group 3,742개
중 104개가 두 structural occurrence를 포함하지만, builder가 이를 합치지 않고
runtime의 unique-domain 검사에서 fail-closed하도록 유지했다. 최대 충돌 크기는
2다.

### 6.2 v3 후속 실행 준비 상태

Active v2 source를 수정하지 않고 별도 v3 source snapshot을 만들었다.

```text
Source snapshot (so003/so004/so005/so006):
  /home/mchoi/fed-space-audit-semantic-v3-20260831T090250Z
V3 source receipt SHA-256:
  d35f1c5777b3742e0931e323ea5b30b9e73f88c7fa1df6ae58300adc099d1e1a
V3 manifest SHA-256:
  ca5f8a5a80af817493f397728ce76ec03cc3d59e5b112e826ef2d884f8a1f37a
Semantic watcher SHA-256:
  19c11cde1868a6d92b620ec5b2efb1c2fd17fcf06fea8c0352b275def377c85c
```

`so005`의 깨끗한 staging tree에서 실제 검증한 결과:

```text
test-compile: PASS
PlannerSpaceAuditTest:                         8/8 PASS
ExactPhysicalForcedStateAuditTest:             5/5 PASS
FederatedForcedStateAuditRunnerSelectionTest:  1/1 PASS
```

동일 staging source와 full v3 manifest에서 기존 structural replay로는 미도달했던
DP target 하나를 추가 end-to-end 실행했다. 결과는 `SUCCESS`,
`constraintSatisfied=true`, runtime capability 3/3 `SUCCESS`였고, constraint
receipt는 `matchMode=SEMANTIC_UNIQUE`를 기록했다. Campaign run manifest도
`SOURCE_SHA256SUMS_V3.txt`를 사용했다.

```text
audit-results/fed-space-semantic-v3-smoke-20260831T091100Z/
ARTIFACT_SHA256SUMS.txt SHA-256:
  1d107ba5ad4cb74da177b519e8e5ba65b66f7036b5ec2d5629614f556696b62e
```

처음 원격 검증 명령은 두 테스트에 잘못된 package prefix를 주었고, 원격 shell에
`set -e`가 없어 마지막 테스트 성공이 앞선 Surefire 오류를 가렸다. 이 실행은
검증으로 인정하지 않았다. 올바른 FQCN과 `set -euo pipefail`로 다시 실행한 위
결과만 증거로 사용한다.

새 watcher는 기존 v2 isolated watcher가 끝날 때까지 대기하고, 그 결과에서도
정확히 `TARGET_NOT_REACHED`인 target만 v3 manifest에 1:1로 join한다. semantic
key가 비어 있거나 ID가 중복/누락되면 실행 전에 중단하며, 재실행은 다시
1 target/JVM이다. 구조적 key는 계속 우선하고 semantic key는 unique domain일
때만 허용된다.

```text
so003 semantic watcher PID: 3778801 (waits for 3479872)
so004 semantic watcher PID:  193806 (waits for 4096087)
so006 semantic watcher PID: 2972640 (waits for 2650749)
```

세 watcher와 세 primary, 세 v2 isolated watcher는 모두 살아 있다. v3 output은
v2 isolated가 끝나기 전에는 생성되지 않으므로, 현재 active campaign의 source,
result, scheduling에는 영향을 주지 않는다.

## 7. 남은 작업과 완료 조건

1. 세 primary shard의 expected target ID를 1회씩 모두 수집한다.
2. 모든 non-success target을 1 target/JVM으로 다시 실행한다.
3. v2 isolated 이후에도 남는 target만 v3 semantic key로 재실행하고, unique,
   ambiguous, not-emitted를 분리한다.
4. workload-specific plan assertion, whole-program infeasible, lowering/runtime failure, target-not-exposed, infrastructure failure를 분리한다.
5. `so001`과 `HARNESS_OBSOLETE.txt`가 있는 모든 행을 집계에서 차단한다.
6. Missing/Spurious/infeasible/UNTESTED 표를 재생성한다.
7. checksum, duplicate/missing target, capability receipt, compile 및 focused regression을 모두 통과한 뒤에만 전역 결론을 낸다.


## 8. 13:08 CEST 업데이트: 무 capability 8개 전부 해결

12:20 checkpoint의 `TRIAGE_NO_RUNTIME_CAPABILITY_EVIDENCE` 8개를 모두
root-cause 수정 후 target당 새 JVM으로 재실행했다.

- L2SVM/LM 6개: planner-selected CP/FED 또는 output placement를 가로지른
  TSMM/MM-chain/transpose fusion 차단
- P2FFN 2개: exact local materialization이 federated frame producer를 수용하도록
  lowering 확장
- P2FFN runtime 후속 결함: worker frame schema를 column별로 병합·정규화하여
  coordinator의 보수적 FP64 schema와 실제 BOOLEAN/INT/FP partition schema 조정

최종 결과는 8/8 `SUCCESS`, constraint 8/8 충족, runtime capability 38/38
`SUCCESS`, failed JVM 0, infrastructure `PASS`다.

```text
audit-results/fed-space-eight-lowering-verified-20260831T125835+0200/
SHA256SUMS.txt SHA-256:
  f4936b152ef63a8a37e369b6e2f7d3a222bc79c9761e246f865773345b5dd73f
```

기존 authoritative artifact는 immutable historical evidence로 유지한다. 보충
해석에서만 `PUBLISHED_LEGAL_EXECUTED=4,831`, no-capability triage `0`이다.
전역 `Missing=(R∩L)-P` 판정은 별도 exact-identity 비교가 필요하므로 계속 열린
상태다.

## 9. 13:49 CEST 업데이트: attempt-local exact join과 formal receipt

`so003/so004/so006`에 보존된 primary/isolated/semantic-v3 원본을 attempt별로
재결합했다. 6,547 attempts, 1,076,549 candidate rows, 30,361 successful runtime
rows를 검사했으며 `confirmedMissing=0`이었다. 다만 역사적 receipt는 selected
target, physical state, analysis fingerprint가 없으므로 이 결과를 전역 Missing
부재 증명으로 승격하지 않는다.

과거 36개의 selected/runtime input divergence는 L2SVM ternary loop의 scalar
literal rewrite 이후 audit recorder가 reflection operand를 잘못 정렬한 문제였다.
logical `inputN` field-order fallback을 추가하고 실제 표적을 재실행했다. 17개의
literal-rewritten event를 포함한 ternary 18/18개가 계획과 동일한
`ABSENT_LOCAL,ABSENT_LOCAL,PRESENT:ROW`를 기록했다.

runtime receipt에 다음을 추가했다.

```text
plannerPlanHash
plannerAnalysisFingerprint
auditContext
plannedTargetStates
plannedPhysicalStates
```

새 receipt로 L2SVM 1개와 기존 anomaly 8개를 target당 clean JVM으로 실행했다.
전체 9/9 target이 constraint를 충족하고 성공했으며 runtime 62/62건이 성공했다.
단일 occurrence로 결합되는 56/56건은 모두 `EXACT_PLANNED_TARGET_IN_P`, input
divergence 0, confirmed Missing 0이다. 이 결과도 observed R만 포함하므로 coverage는
의도적으로 incomplete로 남긴다.

상세 보고서:

```text
docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_1349_KO.md
```

## 10. 14:13 CEST 업데이트: runtime conversion frontier

Candidate builder와 독립적으로 direct FED, CP/SP→FED runtime conversion,
federated-input non-conversion을 기록하는 `fed-runtime-conversion-frontier-v1`을
추가했다. 이는 실행 성공 receipt와 분리되며 parser/dispatch evidence로만 사용한다.

L2SVM smoke는 12 frontier row와 24/24 successful capability row를 기록했다.
`FederatedMultiplyTest` 8/8을 compilation off/on 및 CP/SP에서 실행한 결과 frontier
64개, capability 36개, candidate 120개를 얻었다. `RUNTIME_TO_FED` 5개는 모두
compilation-off legacy context에서만 발생했고 compilation-on에서는 direct FED로
lowering됐다. Runtime의 `ba+* ROW,COL` 경로에 대응해 P는
`CP/LOUT`, `FED/LOUT/ROW`, `FED/FOUT/ROW`를 모두 공개했다.

상세 보고서:

```text
docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_1413_KO.md
```
