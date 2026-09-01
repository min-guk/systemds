# Federated Planner P/L/R Audit Checkpoint — 2026-08-31 20:42 CEST

## 1. 이번 체크포인트의 판정

현재 작업은 더 이상 한 프로세스에서 모든 opcode를 직렬로 조사하지 않는다. `so001`은 proxy이므로 제외하고, 독립 fixture와 forced-state campaign을 `so002`--`so006`에 family별로 분할하는 방식으로 전환했다. 동일 결과 파일에 여러 프로세스가 append하지 않고, 서버별 immutable 결과와 SHA-256 receipt를 마지막에 병합한다.

현재까지 직접 강제 실행으로 검증한 상태는 기존 84개에 WSigmoid/WSLoss 10개를 더한 **94/94 성공**이다. WCeMM/WUMM은 direct discovery까지 성공했고, 10개 published target의 원격 forced replay를 병렬 실행 중이다.

## 2. 완료된 핵심 결과

### 2.1 WSigmoid/WSLoss

* discovery: `audit-results/fed-runtime-weighted-quaternary-layout-e2e-v1-20260831T180004Z`
* forced campaign: `audit-results/fed-runtime-weighted-quaternary-layout-forced-v1-20260831T180534Z`
* validation: `audit-results/fed-runtime-weighted-quaternary-validation-20260831T181306Z`
* direct targets: 10
* forced results: 10/10 `SUCCESS`
* direct state validation: 10/10 `PASS`
* confirmed `Missing`: 0
* selected runtime input-signature divergence: 0

이 과정에서 WSigmoid가 runtime에서 ROW/COL만 처리하는데 shared rule이 FULL/PART를 FED/FOUT으로 공개하던 `Spurious` bug를 발견했다. `Rulesets`에서 FULL/PART를 CP/LOUT으로 제한했고 focused red/green 및 전체 회귀를 통과했다.

### 2.2 공통 privacy legality (`L`)

상세 보고서: `docs/FED_PLANNER_PRIVACY_L_AUDIT_2026-08-31_KO.md`

확인된 경로는 다음과 같다.

1. selector 이전의 canonical `PlacementAnalysis`가 source privacy를 취득한다.
2. occurrence/value-version 관계를 따라 fixed-point 전파한다.
3. privacy에 금지된 candidate를 shared domain에서 제외한다.
4. FedAll/Heuristic/DP/Exact가 이 동일한 privacy-filtered domain을 소비한다.

감사 결과는 179 rows (`PUBLIC` 121, `PRIVATE_AGGREGATE` 54, `PRIVATE` 4)이며, strict `PRIVATE` 3개 행에서 CP/FOUT, CP/LOUT, FED/LOUT 제거가 관측되었다. privacy 회귀는 12/12 통과했다. 현재 확인된 legality bug는 없다.

남은 결함은 **관측성**이다. privacy가 모든 candidate를 제거하면 fail-closed 예외가 audit record보다 먼저 발생하여 실패 occurrence가 JSON에 남지 않는다. selector 의미를 바꾸지 않고 예외 직전에 failure-side record를 남기는 최소 수정과 회귀 검증을 별도 agent가 수행 중이다.

### 2.3 WCeMM/WUMM direct discovery

* fixture smoke: `audit-results/fed-runtime-wcemm-wumm-layout-smoke-20260831T183009Z`
* discovery: `audit-results/fed-runtime-wcemm-wumm-layout-e2e-v1-20260831T183116Z`
* candidate occurrences: 4
* runtime occurrences: 4
* runtime outcomes: 4/4 `SUCCESS`

직접 관측한 `P`는 다음과 같다.

| Operation/input | Published states |
|---|---|
| WCeMM ROW | CP/LOUT, FED/LOUT/ROW |
| WCeMM COL | CP/LOUT, FED/LOUT/COL |
| WUMM ROW | CP/LOUT, CP/FOUT/ROW, FED/FOUT/ROW |
| WUMM COL | CP/LOUT, CP/FOUT/COL, FED/FOUT/COL |

native discovery에서 WCeMM 결과는 local scalar이고 WUMM 결과는 입력 orientation을 보존한 federated matrix였다.

## 3. 병렬 실행 상태

2026-08-31 20:37 CEST live check:

| Host | SSH | Root free | 역할/상태 |
|---|---:|---:|---|
| `so003` | pass | 234 GiB | WCeMM/WUMM shard 및 후속 aggregate family |
| `so004` | pass | 269 GiB | WCeMM/WUMM shard 및 후속 reorg family |
| `so005` | pass | 297 GiB | WCeMM/WUMM shard 및 후속 ternary/nary family |
| `so006` | pass | 281 GiB | WCeMM/WUMM shard 및 privacy/failure-side audit |

원격 live-check artifact: `audit-results/remote-parallel-livecheck-20260831T183659Z`

첫 WCeMM/WUMM 병렬 attempt에서는 `so003`--`so005`가 Maven의 generated `target/maven-shared-archive-resources/META-INF/NOTICE` 부재로 build 전에 실패했다. 이는 runtime infeasibility가 아니라 staging/build infrastructure failure이다. `so006`은 결과 2개를 생성했으나 하나는 `TARGET_NOT_REACHED`여서 아직 capability 판정에 사용할 수 없다. 다음 attempt는 각 host에서 깨끗한 `target`으로 build prerequisite를 먼저 완료하고, 새 output directory 및 target-per-JVM=1 isolated replay를 사용한다.

## 4. 동시에 진행 중인 독립 작업

1. **WCeMM/WUMM agent**: 원격 clean-build 재시도, 10-target forced-state 병합, Missing/Spurious 판정.
2. **Privacy observability agent**: empty privacy domain의 failure-side audit record 구현 및 회귀.
3. **Aggregate family agent**: AggregateBinary/AggregateUnary fixture, P/L/R manifest, `so003` forced campaign.
4. **Root coordinator**: 원격 상태 감시, immutable artifact 수집, checksum/target-set 검증, checkpoint 통합.

## 5. 왜 이 방식이 더 빠르고 안전한가

* fixture 설계와 source 판독은 opcode family별로 독립적이므로 agent 병렬화가 가능하다.
* forced replay는 target별 독립 JVM으로 실행할 수 있으므로 서버 shard 병렬화가 가능하다.
* coordinator 한 곳에서 여러 family를 동시에 돌리면 worker port, Maven `target`, audit append 파일이 충돌할 수 있으므로, **source snapshot + 서버별 output**으로 격리한다.
* 결과는 target ID 집합, source/manifest hash, campaign summary, runtime-capability receipt가 모두 맞을 때만 병합한다.

## 6. 다음 stop condition

다음 체크포인트는 아래 조건을 만족한 뒤 작성한다.

1. WCeMM/WUMM 10개 target의 infrastructure-complete forced result 확보.
2. privacy empty-domain failure record가 legality/selector 결과를 바꾸지 않음을 회귀로 확인.
3. AggregateBinary/AggregateUnary의 direct P와 forced R 비교 완료.
4. 각 artifact SHA-256 검증 및 `git diff --check` 통과.

