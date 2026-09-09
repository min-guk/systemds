# FED Planner P/L/R 감사 현재 상황 보고서

**보고 시각:** 2026-08-31 10:51:15 CEST  
**작업 트리:** `/home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830`  
**브랜치:** `g014/fed-runtime-space-audit-20260830`  
**서버 정책:** `so001` 영구 제외; primary/retry는 `so003/so004/so006`만 사용

## 1. 현재 결론

Primary forced-state campaign은 5,408개 target을 모두 완료했다. 세 shard 모두
`infrastructureStatus=PASS`이며 failed JVM chunk, duplicate, missing,
unexpected target이 모두 0이다. Primary의 non-`SUCCESS` 824개는
target ID 기준으로 isolated manifest와 정확히 일치하고, 현재 1 target/JVM
재실행 중이다.

Forced published-state campaign은 `P`에 공개된 상태를 실행 증거로
분류하는 단계다. 이 결과만으로 `Missing=(R∩L)-P`를 추론하지 않는다.
또한 primary non-success는 isolated와 semantic 결과가 존재하면 반드시 후속
stage 결과로 대체한다.

## 2. Primary 완료 결과

| Host | Targets | SUCCESS | TNR | WPI | TRIAGE | Infra |
|---|---:|---:|---:|---:|---:|---|
| `so003` | 1,803 | 1,518 | 148 | 14 | 123 | PASS |
| `so004` | 1,803 | 1,535 | 139 | 17 | 112 | PASS |
| `so006` | 1,802 | 1,531 | 145 | 20 | 106 | PASS |
| **합계** | **5,408** | **4,584** | **432** | **51** | **341** | **PASS** |

- failed JVM chunks: 0
- duplicate/missing/unexpected target: 0/0/0
- runtime-capability rows: 25,146
- runtime-capability outcomes: 25,146/25,146 `SUCCESS`
- constraint가 실제 적용·충족된 target: 4,925
  - `SUCCESS` 4,584 + workload assertion `TRIAGE` 341

Primary `TRIAGE`는 현재까지 known physical failure가 아니다. 강제된
alternative가 workload의 planner-specific heavy-hitter assertion을 바꿨지만,
해당 실행에서 관찰한 FED instruction은 성공했다. 최종 분류에서는 isolated
runtime-capability evidence를 다시 사용한다.

## 3. Isolated re-entry handoff

Primary non-success 집합과 isolated manifest를 target ID로 대조했다.

| Host | Primary non-success | Isolated manifest | Missing | Unexpected | Duplicate |
|---|---:|---:|---:|---:|---:|
| `so003` | 285 | 285 | 0 | 0 | 0 |
| `so004` | 268 | 268 | 0 | 0 | 0 |
| `so006` | 271 | 271 | 0 | 0 | 0 |
| **합계** | **824** | **824** | **0** | **0** | **0** |

각 manifest row는 별도 Maven/Surefire JVM에서 실행된다. 10:51:15 CEST 진행률:

| Host | 완료 | SUCCESS | TNR | WPI | TRIAGE |
|---|---:|---:|---:|---:|---:|
| `so003` | 91/285 | 39 | 47 | 5 | 0 |
| `so004` | 77/268 | 33 | 30 | 11 | 3 |
| `so006` | 72/271 | 35 | 35 | 0 | 2 |
| **합계** | **240/824 (29.13%)** | **107** | **112** | **16** | **5** |

현재 결과는 진행 중 snapshot이다. Isolated summary가 생성되기 전에는 outcome
cardinality를 최종값으로 사용하지 않는다.

10:41의 capability 교차 검사에서 persistent TNR은 constraint 미적용 상태였지만
해당 workload에서 관찰된 FED instruction outcome은 모두 `SUCCESS`였다. WPI는
실행 전 whole-program hard constraint에서 차단됐다. 현재까지 isolated runtime
capability `FAILURE` 증거는 없다.

## 4. Semantic retry와 finalizer

Structural isolated 결과에서 끝까지 `TARGET_NOT_REACHED`인 target만
v3 semantic manifest에 exact target ID로 join한다. Structural replay key가
우선이며 semantic key는 현재 analysis에서 정확히 하나의 decision domain과
일치할 때만 허용한다. 복수 일치는 `REPLAY_IDENTITY_AMBIGUOUS`로
fail-closed한다.

Watcher 상태:

- `so003` isolated PID 3479872 / semantic PID 3778801: alive
- `so004` isolated PID 4096087 / semantic PID 193806: alive
- `so006` isolated PID 2650749 / semantic PID 2972640: alive

Capability-aware detached finalizer도 alive 상태다.

```text
tmux session:
  fed-plr-finalizer-20260831T074722Z
tool snapshot:
  /home/mchoi/fed-audit-finalizer-tools-20260831T074722Z
tool receipt SHA-256:
  66e5c3615caa8ee188399a13837f6322fc05c1d92fe5f62aab8c39f0ef2912d5
planned final output:
  audit-results/fed-space-forced-normalized-v2-20260831T053209Z-authoritative-final-20260831T074722Z/
```

Finalizer의 stage precedence는
`semantic > structural isolated > primary`다. Runtime capability row를
보존하고 assertion-after-successful-runtime과 실제 runtime-capability failure를
구분한다.

## 5. 검증 산출물

Primary 완료 checkpoint:

```text
audit-results/fed-space-forced-normalized-v2-20260831T053209Z-primary-complete-20260831T103226+0200/
SHA256SUMS.txt SHA-256:
  b209c8affa1eecd0c7f6229c7476cc5dd73501b91b7793240cacabb8a0e5af18
```

검증 상태:

- primary 5,408/5,408 및 세 shard infra PASS
- isolated handoff 824/824 exact set equality
- aggregation unit tests 3/3 PASS
- 실제 semantic smoke 기반 aggregation checksum PASS
- shell `bash -n`, Python `py_compile`, `git diff --check` PASS
- `so001` 산출물은 최종 집계에서 제외

## 6. 남은 작업

1. isolated 824/824 완료 및 세 summary의 infrastructure PASS 검증
2. persistent TNR만 semantic v3로 재실행
3. finalizer의 stage-set equality와 checksum 검증
4. physical failure, workload assertion, replay ambiguity, not-emitted를 분리
5. 별도 runtime-space evidence와 결합해 P/L/R, Missing, Spurious, UNTESTED 표 확정
6. 최종 보고서와 재현 가능한 artifact 경로 확정

위 단계가 끝나기 전에는 전역 planning-space completeness를 주장하지 않는다.
