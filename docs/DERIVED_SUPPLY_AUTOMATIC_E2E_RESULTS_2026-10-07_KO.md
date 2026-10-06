# Derived supply sharing 자동 DML 실행·메모리 검증 결과

작성일: 2026-10-07, Europe/Berlin. 실험 디렉터리와 UTC 로그의 날짜는 2026-10-06이다.

이 문서는 첫 검증 단계의 기록이다. 이후 flat 공유·PHI 비용·활성 cache spill 실험과 fixture 수정을 진행했으며, 최종 상태는 [잔여 작업 결과 보고서](DERIVED_SUPPLY_REMAINING_WORK_2026-10-07_KO.md)에 있다. 자동 DML 8건·메모리 6건은 통과했고, 확대 Java 회귀에서 기준 버전에도 있는 대형 Exact solver 한계 1건을 명시했다.

후속 검토 정정: `SOURCE_REMOVAL` 등의 `cleanupSuccess=true`는 비동기 `rmvar` 요청 제출 성공이며 개별 worker 응답 확인은 아니다. `WORKER_RESET`의 true만 모든 CLEAR 응답 확인이다. 따라서 아래 updated의 앞선 두 copy는 정리 dispatch 성공, 마지막 copy는 CLEAR 응답 확인으로 구분한다. 당시 `confirmedRemoteCleanupCount`가 이를 합산한 명칭은 후속 runner에서 수정했다. [잔여 작업 보고서](DERIVED_SUPPLY_REMAINING_WORK_2026-10-07_KO.md)에 새 검증을 기록한다.

**자동 DML 실행과 메모리 측정 경로를 구현했고, 최종 Docker 6개 사례가 통과했다.** Local과 Global 모두 invariant FOUT의 staged REFED를 한 번 생성했다. 서로 다른 값 3개를 만드는 중첩 loop에서는 값마다 한 번씩, 총 3번 생성했다. 72 MiB의 planned copy를 보관하는 경우에도 worker heap 1 GiB와 256 MiB에서 추가 업로드 없이 재사용했다.

이 첫 단계에서는 모델의 candidate·비용·합법성 규칙을 변경하지 않았다. 변경은 기본 OFF인 runtime 관측, 자동 DML 검증 도구, 회귀 검사다. 단일 loop에서 공유 후보가 선택되지 않는 별도 사례의 원인과 기존 테스트 오류는 아래에 당시 남은 항목으로 구분한다.

## 1. 기준과 작업 범위

| 항목 | 값 |
|---|---|
| 기준 commit | `ada24ffd4be4d17240f32f5bd1c2b5a98e1a0c8a` |
| 전용 workspace | `/home/mchoi/w1357-derived-supply-sharing-20261006` |
| 기준 문서 | [자동 DML 검증과 메모리 확인 계획](DERIVED_SUPPLY_E2E_AND_MEMORY_VALIDATION_2026-10-06_KO.md) |
| 실행 경로 | `scripts/fedplanner/run_LAN_docker.sh --supply-sharing-e2e` |
| Docker | 고정 image, CPU 4개, container memory 8 GiB, network none |
| Worker | 같은 격리 container 안의 별도 JVM 2개, loopback 통신 |
| Coordinator heap | 3 GiB |

기존 workspace와 다른 실행 중인 실험은 수정하지 않았다. 각 실행에서 source·class·dependency·입력을 새 디렉터리에 복사하고 SHA를 기록했으며, case마다 새 worker를 시작했다. 이 단계의 최종 세 실행 main/test Java source는 당시 worktree와 모두 일치하고 class preflight도 통과했다. 이번 추가 변경은 아직 commit/push하지 않았다.

## 2. 실제로 검증한 자동 계획

최종 사례는 후보 번호나 sharing group을 주입하지 않는다. 실제 DML의 파싱, HOP 구성, production optimizer 선택, lowering, loop 실행을 거친다.

Invariant 사례의 핵심은 다음과 같다. `LA`와 `LB`는 각각 worker A/B의 보호된 입력이고, `S`는 A의 public FOUT 값이다.

```dml
S = federated(...A...);
for (i in 1:3) {
    LA = LA + i;
    LB = LB + i;
    QA = LA %*% S;
    QB = LB %*% S;
    # 두 결과의 합계와 제곱합을 누적
}
```

`S`는 변하지 않지만 실제 연산 입력 `LA/LB`는 변한다. 따라서 계산 전체가 loop 밖으로 이동한 결과를 공유 성공으로 세지 않는다. `S`의 B 공급에서 FOUT → local staging → REFED가 자동 선택되고, 세 번의 공급 중 첫 번째만 source GET과 target PUT을 수행했다.

갱신 사례는 새 값의 생성과 그 값의 반복 사용을 분리했다.

```dml
B = federated(...A...);
for (i in 1:3) {
    S = B + i;                 # 서로 다른 실제 값 S₁, S₂, S₃
    for (j in 1:3) {
        LA = LA + j;
        LB = LB + j;
        QA = LA %*% S;
        QB = LB %*% S;
        # 두 결과의 합계와 제곱합을 누적
    }
}
```

이 사례의 성공 조건은 **9회 공급, 3회 생성, 6회 hit**이다. 같은 version의 안쪽 세 사용은 공유하고, 다음 바깥 iteration의 새 값에는 다른 canonical copy를 만든다. 값이 바뀌어도 한 copy를 쓰는 오류와, 같은 값의 staging이라는 이유만으로 매번 새 copy를 만드는 오류를 함께 검사한다.

이는 단일 loop에서 사용마다 새 값을 만드는 `SINGLE_USE` 공급을 자동 선택한 사례와는 다르다. 그 경로의 N회 비용·생성 회귀는 기존 production cost/runtime 검사와 이번 unit 검사로 유지했다. 이번 자동 E2E는 새 값마다 반복 사용이 있는 사례를 고정했다.

### 최종 결과

기본 입력에서 `S`는 `16 × 8192`, dense payload 1 MiB다. 아래 GET/PUT은 해당 selected supply에 귀속되는 요청만 센 값이다.

| 사례 | Planner | REFED 실행 | 서로 다른 source version | 생성 | Hit | GET / PUT | 결과 |
|---|---|---:|---:|---:|---:|---:|---|
| Invariant | Local | 3 | 1 | 1 | 2 | 1 / 1 | PASS |
| Invariant | Global | 3 | 1 | 1 | 2 | 1 / 1 | PASS |
| Updated, 바깥 3 × 안쪽 3 | Local | 9 | 3 | 3 | 6 | 3 / 3 | PASS |
| Updated, 바깥 3 × 안쪽 3 | Global | 9 | 3 | 3 | 6 | 3 / 3 | PASS |

네 사례 모두 CP reference가 출력한 합계·제곱합·shape marker와 일치했다. 수치 비교의 상대·절대 허용 오차는 `1e-9`다. Runtime fallback/repair는 0이며 계획 밖 변환을 허용하는 옵션은 사용하지 않았다.

검증은 다음 연결을 요구한다.

1. 선택된 relocation의 source/version, target state, FType, sharing lifetime을 읽는다.
2. Production cost surface를 다시 구성해 선택 certificate의 objective bits, cost-surface fingerprint, selected states, derived lifetime을 비교한다.
3. Synthetic action digest → 실제 instruction audit key → 해당 instruction의 GET/MatrixBlock PUT을 연결한다. 입력 준비·최종 scalar 수집·observer 요청은 제외한다.
4. Supply event와 lifecycle event를 source UID/version/group/layout으로 연결한다. 같은 version에서 canonical ID가 유지되고, 다른 version에는 다른 canonical ID가 사용되는지 확인한다.
5. Creation, retained, hit, alias, retirement, cleanup의 개수·순서를 검사한다. Event 누락·logging 실패·group 소실은 실패로 처리한다.

| 사례 | 선택 비용 = canonical recost | 측정 compile 시간, Local / Global | 측정 DML 실행 시간, Local / Global |
|---|---:|---:|---:|
| Invariant | 46.427803 ms | 7.126 / 5.704 s | 1.915 / 2.158 s |
| Updated | 142.383532 ms | 14.591 / 6.035 s | 2.376 / 2.705 s |

이번 네 선택의 비용은 Local/Global 간에도 같았지만, 모든 입력에서 두 optimizer가 같은 계획을 선택한다는 뜻은 아니다. 총 계획 비용은 연산 수와 그래프 구조가 다르므로 invariant/updated의 비율을 이동 비용의 배수로 해석하지 않는다. 이동 비용의 1×/N× 소유권은 `ExactCompiledSupplySharingTest` 등 production surface 회귀와 함께 확인했다. Canonical 일치는 실제 wall time 예측 정확도를 보장하지 않는다.

## 3. 메모리 압박과 copy 정리

같은 invariant DML에서 `S0/S1/S2`를 각각 `1024 × 3072`, 24 MiB로 만들었다. 동시에 보관한 canonical copy는 3개이고, header를 포함한 추정 bytes는 **75,497,928 bytes, 약 72 MiB**다. 일반 REFED cache의 기본 64 MiB 예산보다 크다.

두 profile은 worker heap만 바꿨으며, Local planner의 canonical 비용은 모두 `1246.653634 ms`였다.

| 항목 | Worker heap 1 GiB | Worker heap 256 MiB |
|---|---:|---:|
| Canonical peak / 종료 시 개수 | 3 / 0 | 3 / 0 |
| 공급 3개 합산 생성 / hit | 3 / 6 | 3 / 6 |
| 공급 3개 합산 GET / PUT | 3 / 3 | 3 / 3 |
| Coordinator RSS 관측 peak | 778.34 MiB | 786.38 MiB |
| Source worker A RSS 관측 peak | 405.35 MiB | 430.90 MiB |
| Target worker B RSS 관측 peak | 278.20 MiB | 271.00 MiB |
| A의 GC 횟수 / 누적 시간 | 26 / 74 ms | 39 / 93 ms |
| B의 GC 횟수 / 누적 시간 | 14 / 48 ms | 14 / 49 ms |
| DML 실행 시간 | 3.997 s | 4.015 s |
| Worker FS spill write / restore hit | 0 / 0 | 0 / 0 |
| 원격 canonical 정리 확인 | 3개 모두 성공 | 3개 모두 성공 |

**이 범위에서는 heap을 줄여도 추가 업로드가 발생하지 않았다.** A의 GC 횟수는 늘었지만 실행 시간 차이는 약 0.5%였고, profile별 한 번의 최종 측정으로 유의한 성능 차이를 주장할 수는 없다. Source A에는 원본과 연산 중간 값도 있으므로 A의 메모리를 target copy의 순수 유지 비용으로 해석하지 않는다.

Worker의 `cachingActive=false`를 확인했다. 따라서 이 실행은 worker spill/restore 비용의 검증이 아니다. 기존 설명처럼 “planned copy가 일반 LRU로 퇴출되어 재업로드된다”는 현상도 관측하지 않았다. 현재 planned copy는 그 LRU 대상에서 제외된다.

RSS는 100 ms 간격의 관측 peak이며 JVM heap 한도와 다르다. Coordinator 측정에는 파싱·계획 및 실행 후 canonical recost도 포함된다. Worker GC는 새 worker JVM의 시작부터 실행 후 관측까지의 누적 값이다. Observer는 별도 JVM에서 scalar 통계만 읽고 MatrixBlock을 취득하지 않는다.

### 수명과 정리

- Invariant의 copy는 마지막까지 source가 살아 있어 worker CLEAR 때 정리됐다.
- Updated 사례의 앞선 두 copy는 `SOURCE_REMOVAL`, 마지막 copy는 `WORKER_RESET`으로 정리됐다. 서로 다른 값 3개를 만들었지만 동시에 유지한 canonical copy는 최대 1개였다.
- Updated Local에서 마지막 alias 발행부터 retirement까지는 version별 약 26–72 ms, Global은 약 27–71 ms였다.
- 72 MiB profile의 세 copy에서는 같은 간격이 약 66–106 ms였다.

이 간격은 **마지막 공급 alias 발행 → 정리**의 proxy다. Alias를 받은 consumer의 실행 시간도 포함하므로 “마지막 연산이 끝난 뒤 불필요하게 보관한 시간”으로 단정하지 않는다. 정확한 last-consumer 종료 계측이나 조기 해제 정책은 추가하지 않았다.

정리 증거도 보강했다. Coordinator의 cache entry가 0이 되는 것만으로 성공이라 판단하지 않는다. 기존 worker CLEAR가 모든 worker의 성공 응답을 받은 경우에만 cleanup 성공을 기록한다. 실패·확인 불가 상태는 각각 `false`·`null`로 구분한다. 최종 여섯 사례는 해당 canonical들의 원격 정리 성공까지 확인됐다.

## 4. 구현 변경

| 파일 | 변경 내용 |
|---|---|
| [RefedReuseAudit.java](../src/main/java/org/apache/sysds/runtime/controlprogram/federated/RefedReuseAudit.java) | 기본 OFF 관측. PLANNED/LEGACY/SINGLE_USE별 생성·hit·alias·residency 및 사유·시각 기록. 객체 참조 없이 scalar/digest만 저장한다. |
| [FederationUtils.java](../src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationUtils.java) | 기존 materialization 및 retire/cleanup 지점에 관측을 연결한다. |
| [FederatedData.java](../src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederatedData.java) | 기존 CLEAR의 성공 응답 결과를 audit에 전달한다. |
| [FEDRefedInstruction.java](../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDRefedInstruction.java) | 선택된 supply action과 실제 materializer 실행 여부를 연결한다. |
| `CacheableData`, `ExecutionContext`, `MatrixObjectFuture` | mutation, mapping 변경, source 제거·clear 사유를 구분한다. |
| [AutomaticSupplySharingDockerProbe.java](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/AutomaticSupplySharingDockerProbe.java) | 실제 DML, 선택 receipt, production recost, runtime audit를 연결하고 별도 worker 관측 모드를 제공한다. |
| [run_supply_sharing_e2e.py](../scripts/fedplanner/run_supply_sharing_e2e.py) / `run_LAN_docker.sh` | 격리·freeze·기준 결과·실행·메모리 sampling·엄격한 성공 조건·증거 보존을 자동화한다. |

관측 property는 `sysds.fed.refed.reuse.audit=true`이며 process 동안 고정해야 한다. 기본값은 OFF다. Event buffer는 기본 4096개로 제한되고 초과/기록 실패는 검증에서 거부한다. Source나 FederationMap을 audit buffer가 붙잡지 않으며, 살아 있는 canonical을 추적 중일 때 reset도 거부한다.

`a_v`, `b_e`, derived group 생성, solver factorization, canonical 비용 공식, privacy/TW/TR/function boundary 규칙은 그대로다. Candidate/factor 탐색 차원을 추가하지 않았고 메모리 예산에 따른 eviction이나 retention 선택도 새로 만들지 않았다.

## 5. 회귀 검사와 남은 범위

| 검사 | 결과 |
|---|---|
| 새 audit 및 관련 Java 회귀 14개 클래스 | 75건 실행: 68 PASS, 7 ERROR, skip 0 |
| 위 7건의 기준 commit 재현 | 격리 baseline class overlay에서도 같은 7건·같은 예외·같은 위치로 실패 |
| Python runner 회귀 | 35/35 PASS: 신규 supply 검사 15건 + 기존 E2E 검사 20건 |
| 최종 자동 DML | 4/4 PASS |
| 최종 메모리 profile | 2/2 PASS |
| Shell 문법 / Python compile / diff whitespace | PASS |

Java 7건은 `ExactSharedSourceOrdinalReuseTest` 5건과 `ExactSharedSourceOrdinalLifecycleTest` 2건이다. 기존 테스트가 `AlternativeHeader`를 14개 인자로 reflection 생성하지만 기준 commit의 생성자는 3개 인자다. 이번에 바꾸지 않은 test/encoding에서 발생하며, 기준 production 파일을 별도 overlay로 컴파일해 동일 오류를 새로 재현했다. 이 기록을 제외한 “전체 Java 테스트 무실패”로 보고하지 않는다.

다음 세 항목은 이번 결과로 해결됐다고 주장하지 않는다.

1. **단일 loop의 r5 선택 원인.** 같은 S를 두 B 연산에 주는 flat fixture는 legal relocation 후보를 갖지만 Local/Global 모두 relocation을 선택하지 않았다. Shape·anchor·candidate 부재는 확인된 원인이 아니다. 두 consumer를 함께 바꾸는 완전한 assignment의 제약·contribution 차이를 진단해야 하며, 후보 존재만으로 더 싼 합법 계획이 제거됐다고 결론 내릴 수 없다.
2. **여러 origin을 갖는 loop-carried PHI의 더 정밀한 lifetime 증명.** 이번 updated E2E는 바깥 iteration에서 생성한 단일 origin을 사용한다. 초기 정의와 backedge 정의가 함께 오는 PHI의 더 세밀한 재사용 증명까지 검증한 결과로 일반화하지 않는다.
3. **더 큰 working set과 spill/restore 비용.** 이번 최대 planned 보관량은 약72 MiB이고 최소 worker heap은256 MiB다. Spill cache 활성 환경, 극단적인 heap 부족, 반복 측정에 의한 비용 보정은 별도 범위다.

현재 결과에서 새로운 retention candidate나 복잡한 메모리 탐색 차원을 추가할 근거는 얻지 못했다. 우선 기존 구조와 새 회귀를 유지하고, r5의 구체적인 선택 원인을 다음 모델 진단으로 다루는 편이 적절하다.

## 6. 재현 명령과 증거

Worktree에서 build한 main/test classes와 `target/lib`가 필요하다. Run ID는 매번 새 값을 사용한다.

```bash
# Local/Global, invariant/updated 자동 선택·실행
scripts/fedplanner/run_LAN_docker.sh --supply-sharing-e2e \
  --run-id automatic-sharing-new-run

# 동일 72 MiB 보관량에서 worker heap 비교
scripts/fedplanner/run_LAN_docker.sh --supply-sharing-e2e \
  --run-id memory-72m-new-1g --kind invariant --planner local \
  --rows 8 --inner 1024 --width 3072 --copies 3 --worker-heap 1g
scripts/fedplanner/run_LAN_docker.sh --supply-sharing-e2e \
  --run-id memory-72m-new-256m --kind invariant --planner local \
  --rows 8 --inner 1024 --width 3072 --copies 3 --worker-heap 256m
```

기계 판독 요약: [validation.json](experiments/automatic-supply-sharing-20261007/validation.json).

증거 root:

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/automatic-supply-sharing-20261006/
  automatic-sharing-final-r7/
  automatic-sharing-memory-72m-1g-final/
  automatic-sharing-memory-72m-256m-final/
  regression-final/
  regression-baseline/evidence/
```

각 run에는 원본 DML/config/input, 명령, source/class SHA, `probe.json`, `result.json`, coordinator/worker 로그, worker 통계, GC 로그, `memory.jsonl`이 있다. 이전 canary의 coverage 실패·privacy 오류·관측 도구 종료 오류도 해당 디렉터리에 보존했다. 실패한 과거 run을 최종 성공으로 덮어쓰지 않았다.
