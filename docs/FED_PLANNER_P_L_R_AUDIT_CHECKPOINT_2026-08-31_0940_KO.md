# FED Planner P/L/R 감사 현재 상황 보고서

**보고 시각:** 2026-08-31 09:40:55 CEST  
**작업 트리:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**실행 정책:** `so001` 영구 제외, primary는 `so003/so004/so006`만 사용

## 1. 현재 결론

전체 5,408개 published placement target 중 primary 결과 4,311개(79.72%)가
생성됐다. 세 primary, 세 structural isolated watcher, 세 semantic watcher는
모두 살아 있다. 현재 non-success는 아직 최종 물리 feasibility 판정이 아니다.
primary 완료 후 모든 non-success를 1 target/JVM으로 다시 실행하고, 거기서도
남는 `TARGET_NOT_REACHED`만 v3 semantic replay로 재검증한다.

| Host | chunk directory | result | SUCCESS | TNR | WPI | TRIAGE |
|---|---:|---:|---:|---:|---:|---:|
| `so003` | 147 | 1,461 | 1,282 | 135 | 13 | 31 |
| `so004` | 143 | 1,422 | 1,273 | 129 | 17 | 3 |
| `so006` | 143 | 1,428 | 1,263 | 137 | 19 | 9 |
| **합계** | **433** | **4,311** | **3,818** | **401** | **49** | **43** |

- `TNR`: `TARGET_NOT_REACHED`
- `WPI`: `WHOLE_PROGRAM_INFEASIBLE`
- primary의 TNR/TRIAGE는 shared-JVM 상태 또는 workload plan assertion의 영향을
  받을 수 있으므로 isolated 결과로 대체하기 전에는 최종 통계에 사용하지 않는다.
- 현재까지 infrastructure/runner target-ID failure는 관찰되지 않았다.

최근 평균 처리율을 단순 외삽하면 primary 종료는 약 10:15 CEST 전후지만, 이는
workload별 실행시간 변화를 무시한 **대략적 추정**이다. 이후 isolated 및 semantic
단계 시간이 추가된다.

## 2. 연결된 실행 단계

### Primary

```text
Campaign:
  /home/mchoi/fed-space-forced-normalized-v2-20260831T053209Z
Manifest SHA-256:
  f3f31f509290ed19d90671c2fb0b26e2b450f41e43f86862cf4b82c3fd2df7e1
Source receipt SHA-256:
  cca27c6a51a0c6fac1a61e645a5de7a5ce1920309d2cc87234a1e8fdd655c233
```

### Structural isolated retry

각 primary가 끝나면 해당 shard의 모든 non-`SUCCESS` target을 authoritative
manifest에 target ID로 1:1 join하고 1 target/JVM으로 실행한다.

```text
so003 watcher PID: 3479872
so004 watcher PID: 4096087
so006 watcher PID: 2650749
```

### Semantic v3 retry

Structural isolated 결과에서도 `TARGET_NOT_REACHED`인 target만 실행한다.
Structural key가 우선이며, semantic key는 현재 analysis에서 정확히 한 decision
domain과 일치할 때만 허용한다. 복수 domain은
`REPLAY_IDENTITY_AMBIGUOUS`로 fail-closed한다.

```text
V3 source snapshot:
  /home/mchoi/fed-space-audit-semantic-v3-20260831T090250Z
V3 source receipt SHA-256:
  d35f1c5777b3742e0931e323ea5b30b9e73f88c7fa1df6ae58300adc099d1e1a
V3 manifest SHA-256:
  ca5f8a5a80af817493f397728ce76ec03cc3d59e5b112e826ef2d884f8a1f37a

so003 semantic watcher PID: 3778801
so004 semantic watcher PID:  193806
so006 semantic watcher PID: 2972640
```

## 3. 이번 checkpoint에서 추가한 최종 집계 경로

다음 도구를 추가했다.

```text
scripts/fedplanner/aggregate_forced_state_results.py
scripts/fedplanner/test_aggregate_forced_state_results.py
scripts/fedplanner/finalize_remote_forced_state_campaign.sh
```

집계기는 target별 authoritative stage를
`semantic > structural isolated > primary` 순서로 선택한다. 선택 전에 다음을
강제한다.

1. primary target set이 5,408개 manifest와 정확히 일치
2. isolated target set이 primary non-success와 정확히 일치
3. semantic target set이 isolated의 persistent TNR과 정확히 일치
4. 모든 campaign의 infrastructure status가 `PASS`
5. duplicate/missing/unexpected target이 0
6. host가 proxy-free allow-list에 포함
7. `HARNESS_OBSOLETE.txt`가 없는 결과만 사용
8. `SUCCESS`는 반드시 `constraintSatisfied=true`

결과는 success, global infeasibility, target-not-emitted, replay ambiguity,
workload/runtime triage를 분리한다. Forced published-state 집계만으로 `Missing`을
추론하지 않는다.

검증 결과:

```text
aggregate precedence/coverage unit tests: 2/2 PASS
remote compact-copy smoke:                PASS
finalizer wait-loop persistence test:     PASS
bash -n / Python py_compile / diff check: PASS
```

## 4. Finalizer 자동 실행

Detached tmux finalizer가 세 semantic watcher 종료를 기다리고 있다.

```text
tmux session:
  fed-plr-finalizer-20260831T073941Z
immutable tool snapshot:
  /home/mchoi/fed-audit-finalizer-tools-20260831T073941Z
tool receipt SHA-256:
  aeb7e076785644f0f7dea30f7e73d429033ef8f95373bc4b64af2130c7f9dac8
planned final output:
  audit-results/fed-space-forced-normalized-v2-20260831T053209Z-authoritative-final-20260831T073941Z/
```

Finalizer는 완료 후 세 호스트에서 summary, run manifest, forced-state result와
retry plan을 수집하고, stage coverage를 검증한 뒤 최종 JSONL/CSV/Markdown 및
artifact checksum을 생성한다.

### 폐기한 finalizer 시도

실패를 숨기지 않고 다음과 같이 별도 receipt로 폐기했다.

| snapshot | 원인 | 최종 결과에 사용 |
|---|---|---|
| `...073540Z` | checksum 파일을 자기 입력에 포함 | 아니오; 실행 전 차단 |
| `...073610Z` | `set -e`가 wait-loop의 정상적인 false에서 종료 | 아니오; 조건문 수정 |
| `...073834Z` | 로컬 exec session 종료가 nohup child 정리 | 아니오; detached tmux로 교체 |
| `...073941Z` | 수정본 | **현재 사용 중** |

## 5. 검증 산출물

현재 authoritative checkpoint:

```text
audit-results/fed-space-forced-normalized-v2-20260831T053209Z-progress-20260831T094052+0200/
SHA256SUMS.txt SHA-256:
  730cf06f72b32e1fe288bd87de657f5cb14f78ee5e7acdfaa0f10f239665ff59
```

이전 v3 구현 및 end-to-end 증거:

```text
audit-results/fed-space-dp-tnr-v3-final-20260831T084951Z/
audit-results/fed-space-semantic-v3-smoke-20260831T091100Z/
```

## 6. 남은 작업

1. primary 5,408/5,408 target completion 및 shard summary 검증
2. 모든 primary non-success의 structural isolated 재실행
3. persistent TNR의 semantic unique/ambiguous/not-emitted 분류
4. detached finalizer의 자동 수집 및 stage-set 검증
5. physical failure와 workload assertion을 evidence로 분리
6. 별도 exact runtime evidence와 결합해 Missing/Spurious/UNTESTED 표 확정
7. 최종 checksum, compile, focused regression 후 보고서 확정

전역 `P/L/R` 또는 planning-space completeness 결론은 위 단계가 모두 끝나기
전에는 확정하지 않는다.
