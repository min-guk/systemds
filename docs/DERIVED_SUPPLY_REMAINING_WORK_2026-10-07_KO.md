# Derived supply sharing: 잔여 구현·검증 결과

작성일: 2026-10-07. 기준 commit: `ada24ffd4be4d17240f32f5bd1c2b5a98e1a0c8a`.

전용 workspace: `/home/mchoi/w1357-derived-supply-sharing-20261006`.
다른 workspace와 기존 실행 결과를 보존하며 [이전 보고서](DERIVED_SUPPLY_AUTOMATIC_E2E_RESULTS_2026-10-07_KO.md)의 잔여 항목을 처리했다. 이번 작업은 이 worktree의 미커밋 변경이다.

요청한 flat 공유, PHI 비용, 실제 worker spill 측정, 기존 reflection fixture 수정은 완료했다. 자동 DML 8건과 메모리 실험 6건은 모두 통과했다. 확대 Java 회귀는 **220건 중 218 PASS, 1 ERROR, 1 SKIP**이다. ERROR는 수정 전 버전에서도 확인된 대형 L2SVM Exact solver의 크기 제한이며, 이번에 해결하지 않았다.

## 1. 해결한 문제

**단일 loop의 공유 후보가 후속 연산에서 거부되던 문제.**

`QB=UB+S`, `QC=UB*S`, `QM=QB/QC`에서 QB와 QC가 같은 S→UB 이동을 선택하는 후보는 존재했다. 하지만 QM의 출력 증명은 QB/QC의 예전 입력 공급 경로까지 동일해야 한다고 요구했다. 전체 assignment를 강제해 조사한 결과, 위반은 QM←QB와 QM←QC의 `realization-support` 두 개였고, 호환되는 QM 후보는 0개였다.

이제 **같은 compiled 연산 발생점이 정확히 같은 `DURABLE_MAP` 출력을 만드는 경우**에는 입력 공급 경로가 달라도 그 출력으로 후속 연산을 지원할 수 있다. 출력 state, anchor, worker/range/layout은 정확히 같아야 한다. 다른 source 발생점, 다른 map, `VALUE_MAP`/`NATIVE_LINEAGE`/`SOURCE_LINEAGE`에는 이 규칙을 적용하지 않는다.

이 비교 기준을 hard factor와 선택 후 receipt 검증에서 함께 사용한다. 선택된 연산 자신의 실행 증명, DIRECT input binding, TW/TR·함수 경계, relocation/privacy 검사는 기존의 정확한 비교를 유지한다. 이동 후보를 추가하거나 runtime fallback을 넣지 않았다.

**여러 origin을 갖는 loop PHI의 반복 횟수 과금.**

다음 형태에서 안쪽 사용 횟수와 새 값의 생성 횟수를 구분했다.

```text
C = initial
for i in 1:N:
    S = C
    for j in 1:K:
        use(S)
    C = update(C)
```

S는 안쪽 loop에서 N×K번 읽지만, 소비되는 값은 바깥 snapshot별 N개다. 기존 비용은 entry/backedge origin이 둘이라는 이유로 REFED 생성 횟수를 N×K로 보수적으로 계산했다. 새 증명은 실제 transparent TR/TW 경로의 loop carrier를 추적해 outer snapshot의 N회를 사용한다. FOUT의 local staging GET과 REFED upload 모두 같은 값 수명을 따른다.

증명은 entry+하나의 backedge, 동일한 singleton carrier, 동일한 호출 context와 activation, 바깥 loop가 안쪽 loop의 prefix인 경우로 제한한다. 의미 있는 연산·값 갱신, branch guard, 함수 return 경계, 모호한 provenance는 기존 보수적 계산을 유지한다. 선택된 source가 relocation/derived output으로 새 객체를 만드는 경우도 epoch cap에서 제외한다. 후보별 mask와 GET creation scope를 구분하므로 선택하지 않은 REFED 후보의 존재만으로 다른 GET 비용이 줄어들지 않는다.

또한 upstream `825acfca9d36db8e45d847124602de0f32096d1e`의 native grounding 보존 수정을 반영해 중첩 PHI 분석의 비수렴을 해소했다. 임시 전체 재계산 fallback은 남기지 않았다.

**표현과 비용 소유권.**

기존 `a_v = native execution/input/output`, `b_e = explicit supply/movement` 구조를 유지한다. `transient`/`retained` 후보 차원을 다시 추가하지 않았다. 공유와 필요한 유지 수명은 선택된 공급 관계에서 계산한다. 위 수정은 출력 지원 증명의 비교 기준과 materialization 생성 횟수 증명에 한정된다.

## 2. 실제 DML 자동 선택 결과

`run_LAN_docker.sh`를 통해 파싱 → 자동 최적화 → emission → 실제 worker 실행을 검증했다. CP 기준 수치, 선택 state, canonical objective bits, cost surface, shared lifetime, 실제 GET/PUT를 함께 검사했다. 아래 8건 모두 통과했고 fallback/repair는 0이다.

| DML case | Planner | 실제 공급 실행 | 생성 | hit | 해당 공급의 GET / Matrix PUT |
|---|---|---:|---:|---:|---:|
| invariant FOUT staging | Local / Global 각각 | 3 | 1 | 2 | 1 / 1 |
| outer updated, inner reuse | Local / Global 각각 | 9 | 3 | 6 | 3 / 3 |
| loop PHI snapshot, inner reuse | Local / Global 각각 | 9 | 3 | 6 | 3 / 3 |
| flat loop, QB/QC 공동 소비 | Local | 3 | 3 | 0 | 3 / 3 |
| flat loop, QB/QC 공동 소비 | Global | 3 | 3 | 0 | 0 / 3 |

Flat의 두 consumer는 iteration 안에서 하나의 이동 결과를 사용한다. 따라서 consumer별로 6번 업로드하지 않고 3번만 업로드한다. 값이 iteration마다 바뀌므로 iteration 간 retained copy나 hit는 필요하지 않다. Local은 FOUT staging, Global은 CP/LOUT→FOUT 공급을 자동 선택했다.

PHI도 최종 자동 계획에서는 FOUT staging을 선택했다. 따라서 비용 단위 검사뿐 아니라 실제 DML에서 **3개 값 × 각 3번 사용 = GET 3회·upload 3회**를 확인했다. 이전 upload-only 중간 구현의 LOUT→A/B 두 그룹 결과는 최종 결과와 구분한다.

Flat canonical 비용은 Local `70.576311 → 61.572832 ms`, Global `69.576311 → 60.572832 ms`로 감소했다. 이는 모델의 예상 실행 비용이다. Global은 강제 공동 relocation의 최적 complete assignment와 동일하다. Local은 다른 연산들의 배치 선택 때문에 1 ms 차이가 남는다. Local의 근방 탐색을 Global 최적성으로 오인하지 않으며, 두 planner 모두 실제 공동 공급 선택·합법성·canonical recost를 검증한다.

## 3. 후보·factor 크기

동일한 flat private 입력 fixture에서 비교했다.

| 항목 | 수정 전 | 수정 후 |
|---|---:|---:|
| Node decision | 59 | 59 |
| Physical alternative | 333 | 333 |
| Canonical hard factor | 149 | 149 |
| Canonical cost contribution | 262 | 262 |
| Encoded solver factor | 335 | 335 |
| Solver variable 전체 | 171 | 175 |
| 그중 auxiliary variable | 112 | 116 |

후보 수를 늘리지 않고, 잘못 배제되던 입력 경로 조합을 보존했다. Auxiliary 4개 증가는 수정된 support 관계의 내부 encoding에 따른 것이다. 새 retained 선택 차원이 아니다. 독립적인 factor-cell oracle이 같은 owner/map의 다른 경로를 허용하는 셀과 그 외 정확한 관계를 검사하며, 기존 B-11 hard-factor golden hash는 유지된다.

## 4. 메모리 압박과 실제 재업로드

동일한 216 MiB 공급 fixture를 각 heap에서 3회씩 실행했다. 72 MiB source 3개는 같은 target placement를 사용하지만 별도 source이므로 별도 copy 3개를 유지해야 한다. 모든 run에서 공급 9회, 생성 3회, hit 6회, GET 3회, Matrix PUT 3회였다. 서로 다른 source의 canonical ID가 겹치지 않는 것도 검사했다.

| Worker heap | 실행 시간 3회(초) | 중앙값 | Target FS write / restore | Source 파일 읽기 counter | Source GC 횟수 |
|---|---|---:|---|---|---|
| 1 GiB | 9.842 / 9.545 / 9.966 | 9.842초 | 각 7 / 0 | 각 4 | 39 / 38 / 44 |
| 256 MiB | 15.676 / 13.662 / 12.184 | 13.662초 | 6/4, 6/6, 6/4 | 13 / 8 / 6 | 808 / 376 / 364 |

두 heap의 선택된 native/supply plan, receipt, lifetime은 직접 비교해 동일함을 확인했다. Canonical 비용 bits도 모두 같았으며 비용은 `3634.783943 ms`였다. 각 run 내부의 certificate/canonical 일치 검사는 통과했다. Run 간 전체 plan/cost-surface fingerprint는 서로 달라, 비교 근거는 선택 내용의 직접 비교와 별도 semantic digest(`a0551ff0…`)로 남겼다. Digest의 필드와 직렬화 규칙은 기계 판독 요약에 명시했다.

**관측된 추가 부담은 재업로드가 아니었다.** Target worker는 파일로 spill한 block을 복원하면서 remote ID를 유지했고, 예정된 공급의 추가 PUT은 없었다. Source worker에서는 입력 파일 재읽기(`hdfsHits` counter)와 GC가 증가했다. Worker 집계 FS counter이므로 특정 canonical 파일의 복원 횟수로 단정하지 않는다.

정적 모델은 이번 범위의 네트워크 생성 횟수를 맞췄지만 worker-local 디스크 I/O·GC 시간을 별도 가격으로 예측하지 않는다. 약 38.8%의 중앙값 차이는 이 fixture의 3회 관측값이며 일반적인 비용 보정 계수로 사용하지 않는다.

실험 조건과 적용 범위는 다음과 같다.

- 동일 pinned Docker image, CPU 4개, container memory 8 GiB, coordinator heap 3 GiB, 동일 입력과 DML. Worker heap만 비교했다. 기본 JVM soft-reference 정책을 사용했다.
- 기본 production worker의 caching은 비활성이다. 실제 spill은 test-only launcher가 기존 STATIC cache API를 초기화하는 `--worker-cache static` profile에서 검증했다. Production 기본 정책은 변경하지 않았다.
- Worker별 temp/scratch 경로를 분리했다. Docker hostname 조회 실패 시 JVM UUID가 같아지는 환경에서도 cache 파일이 충돌하지 않는다.
- 종료 후 logical canonical count/bytes는 0이고 각 run의 세 canonical에 대해 WORKER_RESET/CLEAR 응답을 확인했다. SOURCE_REMOVAL의 비동기 cleanup 제출 성공과 CLEAR 응답 확인을 구분한다.
- 활성 worker cache의 기존 `cleanupEnabled(false)` 정책 때문에 CLEAR 후 backing 파일은 남는다. 256 MiB profile에서는 3개·226,492,443 bytes였다. 이 실험은 production cache 활성화와 파일 정리까지 완료했다는 의미가 아니다.

## 5. 회귀와 검증 도구

| 검증 | 결과 |
|---|---|
| Java 관련 26개 클래스 | 220건: 218 PASS, 1 ERROR, 1 SKIP |
| Python runner 회귀 | 42/42 PASS |
| 자동 DML, Local/Global | 8/8 PASS |
| 실제 spill 비교, heap별 3회 | 6/6 PASS |
| Shell 문법·Python compile·diff check | PASS |

Java 집계는 최종 코드의 25개 클래스 결과와 마지막 certificate 테스트 수정 후 새로 실행한 1개 클래스 결과를 합쳤다. 기존 PUBLIC-only fixture 1건의 skip을 유지했으며, 이번 실패를 숨기기 위한 새 skip은 추가하지 않았다.

기존 reflection 오류 7건은 현행 `AlternativeHeader(NativeCandidate, supplies, bindingShapes)` 계약에 맞게 fixture를 이식했고, source ordinal·wildcard·atomic publication 검사를 유지했다. 추가로 발견한 함수 receipt fixture의 오래된 8개 proof-path 기대값은 canonical formal map의 실제 두 물리 계획과 정확한 DIRECT binding을 기준으로 고쳤다. 두 종류의 기존 실패는 수정 전 동결 버전에서 별도로 재현했다.

KMEANS의 후보 보존 검사도 수정 전 실패를 재현했다. 해당 derived output은 존재하지만 필수 ROW 입력의 producer가 CP/LOUT만 가능하고 relocation과 DIRECT 지원이 없어 실행 불가능했다. 테스트는 후보가 누락되었을 때 neutral graph의 source 상태·compiled edge·relocation·support clause로 이 불가능 조건을 독립적으로 증명하도록 수정했다. Physical candidate builder를 자기 검증 oracle로 사용하지 않으며, 실행 가능한 후보의 보존 검사는 유지한다. 별도 검토에서도 실현 가능한 후보를 면제하는 경로는 발견하지 못했다.

이 오래된 assertion을 고치자 같은 campaign의 **L2SVM Exact solver 크기 제한**이 드러났다. 동일하게 수정한 테스트를 수정 전 `automatic-sharing-final-r7` 동결 main/dependency에 올려 비교했다.

| 버전 | 동일 certificate 클래스 결과 | L2SVM에서 중단된 이유 |
|---|---|---|
| 이번 잔여 작업 전 동결 버전 | 8건 중 7 PASS, 1 ERROR | optimizer의 `EXACT_VE_FACTOR_CELL_OVERFLOW` |
| 현재 버전 | 8건 중 7 PASS, 1 ERROR | 입력 factor `19,322,130 > 10,000,000` cells |

따라서 대형 campaign이 완료되지 않는 문제는 이번 작업 전에도 존재했다. 다만 같은 에러 지점이라고 주장하지 않는다. 현재 모델은 동일 L2SVM에서 더 이른 명시적 한계에 도달한다. 작은 Docker 검증 DML은 모두 완료했지만 이 대형 fixture의 끝까지 실행과 그 뒤 workload는 검증되지 않았다. Production/test 한도를 올리거나 후보를 제거하지 않았으며, solver factorization 개선은 남은 별도 문제다.

PHI 비용 회귀는 N=3, K=3에서 direct LOUT/FOUT의 upload 및 FOUT staging collection이 9회에서 3회로 줄고, inner semantic update는 9회를 유지함을 확인했다. Branch/function 경계와 복수 carrier에 대해서는 이 새 증명을 적용하지 않는다.

선택된 producer가 새 물리 객체를 만드는 경우는 후보별 mask로 epoch cap을 막는다. 다만 같은 consumer occurrence/layout에서 direct와 fresh-owner가 모두 합법적인 fixture는 이번에 확보하지 못했으므로, 이 특정 조합에 대한 직접 음성 테스트까지 완료했다고 주장하지 않는다. 값 갱신·서로 다른 source·기존 function/lifecycle 회귀는 실행했다.

## 6. 주요 수정 위치와 증거

| 파일·경로 | 역할 |
|---|---|
| `ExactPhysicalModel`, `CandidateSelections`, `PlacementIdentity` | 동일 durable output의 required support identity를 factor와 최종 검증에서 공유 |
| `ExactPhysicalCostModel` | 실제 loop carrier provenance, snapshot epoch, source별 GET/upload activation mask |
| `PlacementRelationClosure` | upstream native grounding 재적용 및 고정점 검사 |
| `AutomaticSupplySharingDockerProbe` | complete assignment 진단, 자동 선택과 runtime identity 연결, worker cache 관측 |
| `run_supply_sharing_e2e.py` | PHI·flat fixture, 실제 이동/버전/lifecycle gate, 분리된 worker cache profile |
| `ExactDurableOutputSupportTest`, `ExactLoopPhiSupplySharingTest`, factor-cell/기존 fixture 회귀 | 새 지원 관계와 과금·버전 분리 검증 |

최종 기계 판독 요약: [remaining-validation.json](experiments/automatic-supply-sharing-20261007/remaining-validation.json).

원시 증거 root:

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/automatic-supply-sharing-20261006/
```

- 자동 DML: `remaining-automatic-final-r2`
- Flat complete assignment와 runtime: `remaining-flat-final-r7`
- 메모리: `remaining-memory-216m-{1g,256m}-final-r{1,2,3}`
- 수정 전 flat: `remaining-flat-compatible-qm-r4`, `remaining-flat-upstream-r3`
- 기준 버전 테스트 재현: `remaining-regression-baseline-{certificate,decoded}`
- 동일 수정 테스트의 solver 한계 비교: `remaining-regression-certificate-proof`
- 최종 Java XML·로그, Python 로그, 수정 source hash·diff, 요약 생성 스크립트: `remaining-regression-final`

각 run은 입력, DML/config, source/class/dependency inventory와 hash, class preflight, 실행 명령, coordinator/worker 로그, probe 및 판정 JSON을 보존한다. 최종 8개 run 디렉터리의 production source inventory는 현재 workspace와 전부 일치한다. Cache 경로 충돌이 있었던 초기 r2·r3와 시작 전 bind 오류 r1은 최종 메모리 근거에서 제외했다. 144 MiB에서 restore가 없었던 canary도 spill 성공으로 집계하지 않았다.

재실행 예시는 다음과 같다. Run ID를 생략하면 새 경로를 생성한다.

```bash
bash scripts/fedplanner/run_LAN_docker.sh --supply-sharing-e2e
bash scripts/fedplanner/run_LAN_docker.sh --supply-sharing-e2e \
  --kind flat --rows 16 --inner 16 --width 4096 --copies 1
bash scripts/fedplanner/run_LAN_docker.sh --supply-sharing-e2e \
  --kind invariant --planner global --rows 8 --inner 1024 --width 9216 \
  --copies 3 --worker-heap 256m --worker-cache static \
  --worker-buffer-percent 15 --require-worker-spill
```
