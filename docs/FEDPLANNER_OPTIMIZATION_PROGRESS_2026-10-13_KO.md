# FedPlanner 최적화 진행 현황 보고서

## V59 native 원본 회로 공유 — 경계 검증 진행 중

- V58은 `5483ecbc6f`로 게시했다. 외부 build 중첩은 OFF/detailed 모두0/25였지만 네 실행 모두 watchdog60, 완료 시간 증거 없음. OFF LogReg direct binding62.86%, GLM canonical9.32%/joint7.03%로 근본 반복 작업이 남아 있다.
- LogReg detailed native gate alternatives는14,745,571회 구성됐다. V59는 **이미 존재하는 ordinary/complete topology에만 원본 회로를 부착**한다. 새로운 부분 coverage를 만들거나 더 큰 예산을 추가하지 않는다.
- 동일 snapshot의 exact fact·realization·relation identity와 witness를 유지하며, raw gates만 공유한다. Fixed-axis 거절, invariant overlay, 현재 root reference, graph DFS/cycle/pruning은 query별로 유지한다. Revision에는 이 payload를 옮기지 않는다.
- 공개 두-root 반례 RED27/1(expected gate total2/actual4) → native focused206PASS. 같은 query 결과 재사용이 아니라 두 번째 graph도 실제로 구축하면서 추가 gate만0개다. 이후 replacement/eviction/revision/budget/fixed-boundary 검증을 추가 중이다.
- **V59 미게시·성능 미측정**. Raw 회로 공유를 proof/viability 공유나20초 달성으로 보고하지 않는다.

## V58 중간 작업 제거 — 선택 통합 검증 통과

- **211 classes / 1,638 tests / failures 0 / errors 0 / skipped 3**, package BUILD SUCCESS 2026-10-13 08:33:38+02.
- Native gate option skeleton을 만든 뒤 dependency로 다시 복사하던 경로를 직접 바인딩으로 교체했다. Source·owner·witness·순서·query 고정 경계를 보존하며 fixture의 source handle 조회는 3→1이다. Native focused205 PASS, 독립 review CLEAR.
- Two-list merge는 exact duplicate의 canonical 비교를 생략한다. K-way merge는 exact dedup 후 살아남은 clause의 missing key만 만든다. First donor와 indexed ordering/tie fallback/diagnostics 유지, focused64 PASS.
- 새 cache나 예산 확대 없이 중간 객체·문자열 작업만 제거했다. 동일 Docker 실측 네 번도 모두 watchdog60/fullInitial receipt 없음으로 **20초 목표 미달성**이다.
- 다음 구조 설계는 동일 snapshot의 native 원본 회로를 query마다 다시 만들지 않는 것이다. Raw 회로 공유와 query별 viability 증명을 구분하며, 아직 구현 완료로 보고하지 않는다.

## V57 실측 완료 — 작은 cache 확대만으로 해결되지 않음

- 게시 commit **`3b5040b7b1`**, 봉인 JAR SHA-256 `f26af6abe4f0f6115a77f9e17eb6f3db8cf279074fe7aa5f83c0ea20e977c1e1`.
- 동일 Docker 네 trial 모두 watchdog60, fullInitial receipt 없음. 모든 own container 제거 확인. **20초 목표 미달성**.
- Query-local gate reuse는 LogReg **28,456 / 1,755,131 lookups (1.62%)**, GLM **0 / 141,184**다. 이 index를 더 확장하는 것은 우선순위에서 제외한다.
- Global optional gate sweep 제거는 실제로 적용됐다: gate admission work/footprint/admission counter 모두 0. OFF LogReg footprint samples는 90/2,340이며 전부 root-boundary 작업이다.
- 남은 LogReg OFF direct binding은 1,473/2,340 samples(62.95%). `candidateDependencySkeletons`, graph 구축, materialization 및 실제 dead-row pruning이 여전히 크다. GLM OFF canonical 8.09%, joint 6.90%다.
- 다음 방향은 캐시 용량 확대가 아니라 **변하지 않는 dependency/circuit의 반복 구성과 중간 표현 복사를 없애는 것**이다. 실제 authority·owner·witness 및 query overlay 경계는 유지한다.
- 외부 build 중첩 OFF 15/25, detailed 25/25. 자체 heavy commands는 timed trials 이후 실행했다. 부분 counters/sample 비율은 완료 planning 시간 개선의 증거가 아니다.
- 근거: `R/evidence/v57-planner-breakdown.txt`, `candidate-v57-detailed-topology-outcomes.json`, `v57-footprint-callers.json`, `v57-contention-summary.json`, `v57-monitor-driver.log`.

## V57 최신 main 병합 검증 완료

- **211 actual classes / 1,634 tests / failures 0 / errors 0 / skipped 3**, BUILD SUCCESS 2026-10-13 08:12:29+02. 근거 `R/evidence/candidate-v57-package.log`.
- 동일 query에서 pinned/unpinned native 입력이 같은 gate를 중복 생성하는 문제를 수정했다. Public diamond fixture에서 **gate build 4→2**, ordered proof·identity footprint와 zero-budget 결과가 동일하다. Local positive fixture와 cycle/예산 경계는 각각 199/8 focused PASS다.
- 최신 main의 global gate-suffix sweep 비활성화, native complement 보존, 불필요 support materialization 제거, DP incumbent projection 재사용을 함께 검증했다. Global sweep은 optional cache 수집이므로 끄더라도 합법 후보/proof graph를 제거하지 않는다. Query-local gate identity 공유는 계속 적용한다.
- **V56 실측은 네 번 모두 watchdog60**이었다. LR extra-gate work는 95.2M→114.1M으로 늘어 처리 순서 수정만으로 충분하지 않았다. OFF LR footprint sample 270/2311 중 추가 sweep 193개였다. 이 결과를 근거로 global sweep 중단을 보존했다.
- V57 동일 자원 네 실측도 모두 watchdog60이며 fullInitial 완료 receipt는 없다. **20초 목표와 전체 ML 연산 압축 coverage는 아직 미완료**다.


## V56 검증 및 실측 기록 — 목표 미달성

캐시에 들어갈 수 없는 gate의 footprint를 먼저 구축하던 순서를 수정했다. Root summary를 먼저 보관하고, gate는 settled capacity를 먼저 검사한다. Pruning으로 사라진 원본 의존성은 bounded journal로 보존하며 불완전하면 optional cache만 포기한다.

- 최종 선택 통합 gate: **210 classes / 1,621 tests / failures 0 / errors 0 / skipped 3**, BUILD SUCCESS (2026-10-13 07:47:19+02).
- 회귀: gate phase/budget 14 PASS, 기존 native continuity/identity 190 PASS. 독립 production 검토 CLEAR; shared journal+staging budget assertion을 추가해 다시 전체 선택 gate를 통과했다.
- 근거: `R/evidence/candidate-v56-package.log`, `v56-capacity-order-red.log`, `v56-budget-final-focused.log`, `v56-production-review.txt`.
- 이는 불필요한 순회를 제거한 검증이며 **전체 planning 성능 개선이나 20초 달성의 증거는 아니다**. 동일 Docker 4회 실측은 완료했으며 모두 watchdog60이었다(상단 V57 결정 근거 참조).

## V55 실측 완료 및 V56 우선순위

- 게시 commit `7a44c1874b`, 선택 gate **210 classes / 1,613 tests / failures 0 / errors 0 / skipped 3**. JAR SHA-256 `51172e693f4a9ebac9ed695a240e6ec84b5e98ed2e27a6fc656cfe98cebe1a3e`.
- 동일 Docker 4 CPU / 16 GiB / JVM 10 GiB / JFR 55초 / watchdog 60초에서 LogReg·GLM × OFF·detailed **네 실행 모두 watchdog 종료**. fullInitial 완료 receipt 없음. **20초 목표 미달성**.
- 추가 gate footprint 탐색의 누적 work: LogReg **95,171,210**, GLM **47,732,346**. 누적 staging은 실제 캐시 보관량/성공 admission과 다르다.
- OFF JFR의 footprint inclusive sample은 LogReg 243/2,256개(그중 추가 gate 153개), GLM 89/2,822개(추가 gate 65개). 캐시 재사용 확대가 추가 의존성 탐색 비용을 만들고 있다.
- 현재 실제 용량 거절은 footprint 생성 뒤에 판정한다. V56은 ordinary root summary admission 이후 **용량 판정을 먼저 하고** 추가 gate를 처리하는 구조를 검토한다. Dead alternative에만 있던 의존성과 hidden owner까지 원본 footprint에 보존해야 한다.
- 외부 build 중첩: OFF 13/25, detailed 14/25 samples. 자체 build/분석은 timed trial과 겹치지 않았다. 완료 시간 개선/악화는 아직 입증하지 못했다.
- 근거: `R/evidence/v55-planner-breakdown.txt`, `candidate-v55-detailed-topology-outcomes.json`, `v55-footprint-witness-callers.json`, `v55-contention-summary.json`.
- 전체 ML 연산 rule-directed/압축 coverage도 미완료. CAST_AS_SCALAR의 syntactic 77회와 실제 family publication 수를 혼동하지 않는다.


작성일: 2026년 10월 13일

최신 빌드 및 실측 근거: 2026년 10월 13일

## 재개 후 추가 검증 — 2026년 10월 13일

V53의 exact row ↔ CP family closure 오류를 수정했다. 후보를 제거하거나 guard를 완화하지 않고, 전체 header·owner identity와 입력 축의 비열거 coverage 증명을 추가했다. Family-only 변경 감지 및 후속 전파도 보완했다.

- 로컬 gate: 201개 클래스, 1,573 tests, 실패 0, 오류 0, 건너뜀 2.
- 최신 main 병합 후 gate: **204개 클래스, 1,585 tests, 실패 0, 오류 0, 건너뜀 2**, package BUILD SUCCESS (06:25:42+02).
- 구현 커밋 `58b6342d99`, 병합 검증본 `8cff64591b`. 독립 검토 CLEAR.
- V53/V54의 새 Docker 실측도 모두 watchdog60에 도달했다. **20초 목표와 전체 ML 연산 coverage는 여전히 미완료다.**
- 상세 수정·회귀 및 검증 한계: `docs/SESSION_ISSUES_2026-10-13.md`.

### V54 실측과 V55 근본 원인 보완

| 항목 | 확인 결과 |
|---|---|
| V54 회귀 gate | 205 classes / 1,587 tests / 실패·오류 0 / skip 2 |
| V54 작은 proof fixture | 두 번째 query states 10→9, edges 12→7; proof·owner·FULL seed 동일 |
| V54 실제 root gate 적용 | LogReg/GLM 모두 0회 — 이 경로만으로 workload 개선 근거 없음 |
| V54 고정 Docker 4회 | 모두 watchdog60, fullInitial 완료 없음 |
| V55 incoming gate memo 오류 | 예산 초과 시 일반/generated query NPE 재현 후 수정 |
| V55 중복 footprint 순회 | optional admission의 총 작업·중간 보존량 제한; 원래 proof/candidate 불변 |
| V55 CAST_AS_SCALAR | MATRIX→SCALAR 불변 capability에 dependency authority 추가; focused 21 PASS |

V55 native/physical 강화 focused는 **202 PASS**, 최신 main 병합 통합 gate는 **210 classes / 1,613 tests / 실패·오류 0 / skip 3**이다. V55 동일 Docker 실측은 완료했으며 네 실행 모두 watchdog 종료했다(상단 최신 결과 참조). `origin/main`의 exact gate reuse/physical no-op 변경을 함께 검증하고 있다. Gate key의 정확한 owner·fact·relation identity, fixed-owner 제외 및 revision 격리는 유지한다.

CAST_AS_SCALAR의 ML10 출현 77회는 압축 적용 실적이 아니라 frozen DML의 문법적 출현 수다. 실제 PA-derived PUBLIC fixture는 singleton exact라 family 0건이다. 모든 연산에 빈 dependency를 붙이는 식의 일반화는 합법성 증명을 깨뜨리므로 적용하지 않았다.

V54 OFF 표본에서 LogReg direct binding 61.49%, GLM canonical 8.15%/joint analysis 6.38%였다. 완료된 실행이 아니므로 표본 비율 변화는 속도 향상으로 해석하지 않는다. 외부 build 중첩은 OFF 0/25, detailed 1/25 samples; 기존 외부 planner 두 개는 계속 존재했다. 자체 build/JFR parsing은 timed 실행과 분리했다.

아래 본문은 10월 9일 실측 및 재개 전 상태를 보존한 스냅샷이다. V53 미커밋·통합 오류 미해결·보조 에이전트 제한에 관한 당시 상태는 위 추가 검증으로 대체된다.

## 핵심 요약 — 재개 전 스냅샷

**LogReg와 GLM의 전체 초기 planning을 각각 20초 이내로 완료하는 목표는 아직 달성하지 못했다.** 최신 고정 Docker 실험 네 건 모두 60초 watchdog에 도달했고, 전체 초기 planning 완료 기록을 얻지 못했다.

검증을 마치고 `origin/main`에 마지막으로 반영한 이번 작업의 커밋은 **`23f199291f`**다. 이 버전은 선택한 195개 테스트 클래스에서 총 1,536개 테스트, 실패 0, 오류 0, 건너뜀 1을 기록했다.

이후 NROW/NCOL 후보 압축과 추가 병목 계측을 구현했지만, 통합 테스트에서 **명시적 후보와 압축 family 사이의 전환을 처리하지 못하는 closure 검증 오류 1건**을 발견했다. 이 후속 변경은 아직 커밋하거나 푸시하지 않았다.

| 항목 | 현재 상태 |
|---|---|
| 검증된 개선의 원격 반영 | 완료, 마지막 반영 확인 커밋 `23f199291f` |
| 반복 skeleton materialization 재사용 | 구현 및 회귀 검증 완료, 실제 workload에서 재사용 관측 |
| NROW/NCOL rule-directed 후보 압축 | 구현 및 개별 동등성 검증 완료, 통합 오류 수정 필요 |
| 공통 proof와 canonical 비교의 추가 병목 계측 | 구현 및 focused 테스트 통과, 후속 버전 실측 전 |
| 모든 ML training 연산의 rule-directed 및 압축 경로 | 미완료 |
| Local/Global DP와 SUFFIX의 최종 성능 평가 | 공통 분석 완료 시간을 확보하지 못해 미완료 |
| LogReg와 GLM 전체 초기 planning 각각 20초 이내 | 미달성 |

## 목표와 유지한 제약

최적화 대상은 후보 생성, support 구성, 의존성 및 합법성 검사, 공통 Placement 분석, 이후 Local/Global DP다. 단순히 DP 테이블을 더 자르는 대신, 현재 실제 시간이 집중되는 공통 분석의 반복 작업을 우선 줄이고 있다.

다음 조건은 바꾸지 않았다.

- 합법적인 후보 및 support 집합, privacy 조건, 정확한 source와 owner identity를 보존한다.
- Canonical UTF-16 직렬화와 정렬, 첫 donor 및 동률 선택 의미를 보존한다.
- TRead/TWrite 배치 제약과 recompile 제약을 완화하지 않는다.
- Runtime fallback으로 실패를 숨기거나, 성능을 위해 지원되는 후보를 임의로 제거하지 않는다.
- 성능 실험은 `scripts/fedplanner/run_LAN_docker.sh`만 사용한다.
- Docker 조건은 4 CPU, 메모리 16 GiB, JVM heap 10 GiB, JFR 55초, watchdog 60초로 고정한다.
- 불완전한 실행의 sample 비율이나 counter를 전체 planning 시간 단축으로 보고하지 않는다.

## 원격 반영을 마친 주요 변경

### 불필요한 native support 전개 감소

최근 게시본에는 다음 개선이 포함된다.

- 명시적 native inventory를 인덱싱해 반복 membership 탐색을 줄였다.
- Factorized relocation support에서 전체 유지 또는 전체 제거를 축 단위로 증명할 수 있는 경우에만 개별 clause 순회를 생략한다. 혼합된 경우는 기존 정확한 검사를 유지한다.
- Ordinary pin 조회가 같은 owner의 native Cartesian product 전체를 펼치지 않도록 ordinary topology coverage를 재사용한다.
- Ordinary coverage는 기존 topology 예산을 공유한다. 전체 topology가 필요한 소비자는 complete coverage로 전환하며, partial coverage를 잘못 사용하지 않는다.

Ordinary topology 재사용은 실제 계측에서 확인됐지만, 전체 planning 완료 시간 개선은 아직 입증하지 못했다.

### 동일 resolver 내 skeleton materialization 재사용

기존에는 dependency skeleton template이 이미 있어도 매번 dependency record와 immutable list를 다시 만들고 candidate handle을 조회했다.

게시된 V52는 다음 조건이 모두 같은 경우에만 완성된 결과를 재사용한다.

1. 같은 resolver 인스턴스
2. 같은 template 객체
3. 같은 immutable support-list 객체

캐시는 resolver별 최대 4,096개 entry와 16,384개 dependency slot으로 제한한다. 실패 결과는 저장하지 않으며, revision으로 bound handle을 복사하지 않는다. Fixed-boundary overlay는 query마다 별도로 적용한다.

이 상한은 entry와 dependency 참조 수의 상한이지, 참조가 붙잡는 전체 객체 그래프의 정확한 heap-byte 상한은 아니다.

| 검증 항목 | 결과 |
|---|---|
| 변경 전 회귀 재현 | 핵심 5개 중 재사용 관련 4개 예상 실패 |
| Focused 검증 | 33개 통과 |
| 병합 후 선택 통합 검증 | 195개 클래스, 총 1,536개, 실패 0, 오류 0, 건너뜀 1 |
| 독립 코드 검토 | 차단 문제 없음 |
| 원격 반영 | `23f199291f`, 2026년 10월 9일 반영 확인 |

위 결과는 선택한 FedPlanner 테스트 범위이며, 전체 저장소 테스트 성공을 뜻하지 않는다.

## 최신 실제 성능 측정

게시된 V52를 봉인한 뒤 같은 Docker 조건에서 LogReg와 GLM을 각각 metrics OFF 및 detailed 모드로 실행했다.

| 측정 항목 | LogReg | GLM |
|---|---:|---:|
| OFF 실행 | 60초 watchdog, planning 완료 기록 없음 | 60초 watchdog, planning 완료 기록 없음 |
| Detailed 실행 | 60초 watchdog, planning 완료 기록 없음 | 60초 watchdog, planning 완료 기록 없음 |
| OFF JFR sample 수 | 2,268 | 2,790 |
| OFF direct binding 관련 sample 비율 | 63.49% | 23.12% |
| OFF canonical 비교 관련 sample 비율 | 2.78% | 10.00% |
| OFF joint input analysis 관련 sample 비율 | 0.04% | 8.03% |
| OFF 관측 peak memory | 4.968 GB | 3.316 GB |

Sample 분류는 서로 겹칠 수 있으므로 합산해서 단계별 시간 비중으로 해석하지 않는다. Peak 값의 GB 표기는 분석 스크립트 출력 기준이다.

### Skeleton 재사용의 실제 관측

아래 값은 detailed 실행의 마지막 부분 snapshot이다.

| Counter | LogReg | GLM |
|---|---:|---:|
| Materialization 요청 | 3,548,542 | 1,324,200 |
| 기존 결과 재사용 | 998,060 | 119,319 |
| 실제 dependency record 생성 | 4,486,831 | 2,482,903 |
| Cache admission | 400,070 | 562,615 |
| Cache budget bypass | 2,150,412 | 642,266 |

**재사용 경로가 실제 workload에 적용된 것은 확인했다. 그러나 이것만으로 전체 속도 향상을 주장할 수는 없다.** 높은 bypass 수 역시 캐시 확대의 근거로 바로 사용하지 않는다. Admission에는 replacement가 포함될 수 있으며, 위 값은 고유 객체 수나 최종 처리량이 아니다.

### 측정 해석의 제한

- 네 실행 모두 전체 planning 완료 시간을 얻지 못했으므로 20초 목표는 실패다.
- OFF 구간은 25개 monitor sample 중 6개에서 외부 build와 겹쳤다. Detailed 구간은 외부 build 중첩이 없었다.
- 별도로 오래 실행 중인 외부 planner 두 개는 두 구간 모두 관측됐다.
- 자체 Maven 및 JFR 분석 작업은 timed Docker 실행과 겹치지 않게 했다.
- 네 실험 container는 종료 후 제거됐다.

따라서 현재 실험은 병목 위치와 반복 작업을 진단하는 근거이며, 통제된 전체 완료 시간 비교로 취급하지 않는다.

## 구현했지만 아직 게시하지 않은 변경

### 공통 proof 재사용과 canonical 비교 계측

기존 데이터를 다시 확인한 결과, support memo eviction은 LogReg와 GLM 모두 0이었다. 별도의 다른 memo eviction 수치를 보고 support cache를 늘리는 방향은 배제했다.

반면 이전 LogReg 부분 실행에서는 24,520개 proof graph에 걸쳐 약 582만 state와 4,494만 dependency edge가 생성됐다. 동일 query의 단순 cache miss보다는 서로 다른 query context 사이의 반복 graph 작업을 구분할 필요가 있다.

이를 위해 다음 진단을 추가했다.

- 기존 acyclic summary lookup의 부재, fixed owner 충돌, 실제 재사용을 구분한다.
- 기존 admission 경로의 비활성 budget, state/row 제한, eviction, admission을 구분한다.
- Canonical 비교에서는 크기 2 이상 proof-list의 정확히 같은 객체가 반복되는지 관찰한다.
- Canonical 관찰은 metrics ON에서만 동작하며, active scope의 generation별 최대 4,096개 identity를 추적한다. Overflow는 별도 기록한다.

**새로운 canonical 비교 최적화 자체를 넣은 것은 아니다.** 먼저 실제 재사용 가능성을 측정하려는 변경이며, 기존 flat 직렬화와 comparator의 의미는 유지한다.

### NROW와 NCOL 후보 생성 확대

FederationMap metadata만 읽는 scalar dimension 연산에 rule-directed CP family를 추가했다.

- 역사적인 `CP/LOUT/NO_RULE` capability와 빈 scalar profile을 유지한다.
- 여러 input 상태가 있는 domain은 정확한 input 축을 보존한 lazy family로 표현한다.
- 단일 input 상태만 있는 domain은 기존 exact row를 유지한다.
- 기존 `PUBLIC` output 및 보호된 payload 입력 없음 조건을 유지한다.
- 실제 `PRIVATE_AGGREGATE` 입력에서 기존 privacy inference가 metadata 결과를 `PUBLIC`으로 도출하는 경로를 활성 테스트로 검증했다.
- `PRIVATE` 또는 protected-payload 조건은 기존 exact residual 경로에 남긴다.

검증에는 모든 FType과 absent 입력, domain 역순, 알려진/알려지지 않은 shape, metrics ON/OFF, family 확장과 독립 singleton-domain 생성 결과의 비교가 포함된다. 직접 PUBLIC privacy fixture 한 건은 저장소 지침에 따라 ignore 상태다.

고정 ML10 inventory 기준으로 이 두 연산은 **87개 연산 family 중 2개**, 총 **218회 발생**한다. NROW 108회, NCOL 110회이며 constructed 202회와 rewritten 16회를 합한 수치다.

이 inventory는 10개 DML의 특정 크기 및 rewrite 조건을 고정한 것이다. 성능 실험의 입력 크기와 다르며, 외부 harness를 포함한 모든 ML workload와 모든 privacy 및 고차 입력 조합의 완전한 coverage를 뜻하지 않는다.

## 현재 미해결 통합 오류

### 증상과 재현

후속 V53의 선택 통합 gate 결과는 다음과 같다.

| 항목 | 결과 |
|---|---|
| Actual 테스트 클래스 | 199개 |
| 총 테스트 | 1,559개 |
| Assertion failure | 0 |
| Error | 1 |
| 건너뜀 | 2 |
| 빌드 결과 | BUILD FAILURE |

오류를 재현한 기존 테스트는 다음과 같다.

`WeightedRelationNativeClosureParityTest.dynamicRemoveEmptySourceMatchesExplicitFullClosure`

오류 메시지의 핵심은 `Post-CFG physical candidate closure is not monotone`이다. NROW의 기존 `ABSENT_LOCAL` 및 `PRESENT/ROW` exact key가 제거된 것으로 표시되지만, legal placement state의 제거는 없다.

### 확인된 원인

1. Physical closure가 input domain을 반영해 후보를 다시 생성한다.
2. 새 NROW 경로는 두 exact row를 CP family로 표현하고, 명시적 replacement fact 목록에는 넣지 않는다.
3. Family 자체는 generator의 최신 owner별 저장소에 남아 최종 publication 대상으로 보존된다.
4. 그러나 해당 monotonicity 검증은 명시적 replacement key/fact만 확인한다.
5. 따라서 **같은 후보가 다른 표현으로 보존됐는데도 후보가 사라진 것으로 오인한다.**

### 수정 방향과 남은 설계 결정

우선 검토하는 최소 수정은 누락된 exact row가 replacement family에 실제로 포함되고, 다음 권한 정보가 모두 정확히 같은지 검증하는 것이다.

- Parent owner의 객체 identity
- Ordered input 상태
- Capability, shape proof, profile
- Emission, evaluation status, failure 상태

이를 증명할 때 family 전체를 펼치거나 `requireExact`로 불필요하게 member를 생성하지 않는 방향이다. `normalizedSignature`만으로 권한 동등성을 판단하면 일부 header 필드가 누락되므로 사용하지 않는다.

Family에서 더 작은 family 또는 singleton exact row로 바뀌는 일반적인 refinement까지 함께 고칠지는 별도 경계 테스트와 검토가 필요하다. 이 부분은 아직 구현 또는 승인 완료 상태가 아니다. 검증 guard를 단순 제거하거나 family를 비활성화해서 오류를 숨기지 않는다.

### 게시 상태

후속 변경의 focused 검증은 29개 통과와 명시적 PUBLIC fixture 1개 ignore였으나, **전체 선택 통합 gate가 실패했으므로 게시 가능한 상태가 아니다.** 현재 V53 소스는 미커밋 상태이며, 마지막으로 검증하고 반영한 버전은 V52다.

## 다음 작업 순서

1. Exact row와 family 사이의 표현 전환을 정확하게 검증하도록 closure 계약을 수정한다.
2. 기존 동적 `rmempty` 재현 테스트와 header 불일치, foreign owner, 포함되지 않은 input, 불필요한 member 생성 여부를 검증한다.
3. Family narrowing과 singleton 전환의 지원 범위를 명확히 하고 해당 회귀 테스트를 추가한다.
4. Focused 테스트와 선택 통합 gate를 다시 실행하고 독립 검토를 완료한다.
5. 통과한 변경만 최신 `origin/main`과 병합하여 재검증 후 커밋 및 푸시한다.
6. 같은 Docker 조건에서 LogReg와 GLM을 다시 측정하고 새 counter로 다음 병목을 결정한다.
7. 공통 분석의 전체 완료 시간을 확보한 뒤 Local/Global DP와 SUFFIX cut 효과를 평가한다.
8. 남은 ML 연산 family의 rule-directed 및 압축 경로를 정확한 oracle parity와 함께 확대한다.

현재 일부 보조 에이전트가 사용량 제한으로 종료되어, 마지막 설계 검토는 완료되지 않았다. 이미 확보한 테스트와 진단 결과는 저장돼 있으며, 이를 수정 승인으로 간주하지 않는다.

## 근거와 재현 경로

저장소:

`/grid/3/cofee-lm-sweep-mchoi-20260914/factored-support-20261008/source`

아래에서 `R`은 다음 실험 artifact 경로를 뜻한다.

`/grid/3/cofee-lm-sweep-mchoi-20260914/placement-bottleneck-drive-20261008`

| 근거 | 경로 |
|---|---|
| 게시본 통합 빌드 | `R/evidence/candidate-v52-package.log` |
| 게시본 봉인 정보 | `R/candidate-v52/seal.json` |
| Docker 실행 결과 | `R/candidate-v52-off-jfr/summary.json`, `R/candidate-v52-detailed/summary.json` |
| JFR 병목 분석 | `R/evidence/v52-planner-breakdown.txt` |
| 최신 부분 counter | `R/evidence/candidate-v52-detailed-topology-outcomes.json` |
| 외부 작업 중첩 | `R/evidence/v52-contention-summary.json` |
| 후속 focused 검증 | `R/evidence/v53-final-focused.log` |
| 후속 통합 오류 | `R/evidence/candidate-v53-first-integration-failure.log` |
| 실패 테스트 상세 | `R/evidence/v53-failed-surefire/` |
| 선택 테스트 목록 | `R/evidence/v53-test-selector.txt` |
| 세션별 상세 문제 기록 | `docs/SESSION_ISSUES_2026-10-09.md` |

게시본 JAR SHA-256:

`ed7970855e6a619498f8da074da1f6f1c1f6aedd12df79b12fd4d336ef54af0c`

**최종 판단: 검증된 중간 개선은 반영했지만, 후속 통합 오류 수정과 전체 planning 20초 달성, 전체 ML 연산 coverage는 여전히 남아 있다.**
