# G009 unified workspace 종합 작업 보고서

- 작성일: 2026-09-19 CEST
- 대상 workspace: `/home/mchoi/systemds-g009-unified`
- 브랜치: `integration/g009-unified-20260919`
- 현재 HEAD: `71c598b398d3eee56a00e9103f3cdfe782c3a6da`
- 비교 시작점: `451acabf038906c4219ede8dab7eecffcabc7eb2`
- 현재 로컬 `origin/main`: `35d1f49507a9cf3566674e68c6b97ccc1354d079`
- 판정 시각의 HEAD 관계: 로컬 `origin/main`보다 10커밋 앞섬

## 1. 현재 결론

이 workspace에서는 correctness lane에서 검증한 G009 재설계를 통합한 뒤, 동일한 후보·support·proof·plan을 유지하면서 planning 계산량을 줄이는 실험을 수행했다.

현재 채택된 성능 변경은 **acyclic proof graph 전용 pruning·grounding fast path** 한 건이다. 이 변경은 contemporaneous GLM 비교에서 evaluator 시간을 `794.699s`에서 `716.592s`로 줄여 **9.83% 개선**했으며, 결과 fingerprint와 node/fact/action 수를 유지했다. LM 12쌍에서도 중앙값이 `10,304.5ms`에서 `9,733ms`로 **5.55% 개선**됐다.

최종 목표인 `buildAnalysis <= 180,000ms` 3회 연속은 달성하지 못했다. 현재 검증된 GLM 최선은 `716.592s`이므로 목표보다 `536.592s` 길다. Docker 3회 반복, 전역 legality/completeness/termination 증명, 21개 proof obligation과 48개 rule-family row의 전체 closure도 OPEN이다.

마지막 bounded proof overlay cache 실험은 미커밋 상태다. 후보 snapshot은 유지했지만 완료된 6쌍에서 중앙값이 `9,587ms`에서 `10,573ms`로 **10.28% 악화**됐다. 12쌍 검증은 사용자의 중단 요청으로 종료됐고 GLM으로 확대하지 않았다. 따라서 이 실험은 채택된 개선으로 간주하지 않는다.

## 2. 범위와 근거 구분

이 보고서는 다음 세 범위를 구분한다.

1. **상속한 correctness 작업**: `/home/mchoi/systemds-g009-correctness`에서 완료된 P0–P4 및 R0–R3 일부를 `6392a7ebe7`에서 통합한 내용이다. 해당 작업의 수치와 판단은 통합 당시 보존한 artifact와 문서를 따른다.
2. **unified workspace에서 수행한 작업**: 통합 기준점 이후 timeout 없는 snapshot evaluator, 구조별 LM paired screen, DAG GLM 비교, observer 계측과 후속 cache 실험이다.
3. **현재 미커밋 실험**: bounded proof overlay cache 구현과 부분 벤치마크다. 채택 코드나 완료 gate로 취급하지 않는다.

주요 근거 문서는 다음과 같다.

- [세션 이슈 기록](SESSION_ISSUES_2026-09-19.md)
- [G009 모든 possible plan 보존 감사 진행 현황](PLAN_SPACE_G009_PROGRESS_2026-09-19.md)
- [search-space 시간복잡도·중복·메모리 개선 계획](G009_SEARCH_SPACE_COMPLEXITY_OPTIMIZATION_PLAN_2026-09-19.md)
- [10배 개선 알고리즘 재설계 계획](G009_SEARCH_SPACE_ALGORITHMIC_REDESIGN_PLAN_2026-09-19.md)
- [search-space 생성 알고리즘과 GLM 병목 분석](G009_SEARCH_SPACE_CONSTRUCTION_ANALYSIS_2026-09-19.md)
- [공통 기준 및 workspace 분리](G009_WORKSPACE_SPLIT_2026-09-19.md)

과거 문서의 manifest `5,473`행은 당시 source snapshot에 대한 기록이다. 채택된 DAG fast path의 현재 tracked manifest는 `5,833`행이며 두 수치를 혼용하지 않는다.

## 3. workspace 생성과 작업 분리

초기에는 두 세션이 `/home/mchoi/systemds-lm-worker-count-fix`와 공용 `target/`을 함께 사용하고 있었다. 당시 HEAD와 원격 main은 `451acabf03`이었다. 서로 다른 소스의 merge conflict보다 shared working tree 및 build output 경합이 문제였다.

이를 해결하기 위해 다음 구조로 분리했다.

| 역할 | workspace | branch | 책임 |
|---|---|---|---|
| 통합 기준 | `/home/mchoi/systemds-g009-integration` | `integration/g009-baseline-20260919` | 검증된 변경의 직렬 통합 |
| 완전성·정확성 | `/home/mchoi/systemds-g009-correctness` | `audit/g009-completeness` | 독립 oracle, proof obligation, 반례 |
| 성능 | `/home/mchoi/systemds-g009-performance` | `perf/g009-planning` | profiling, 중복 계산·메모리 개선 |
| 최종 통합·후속 실험 | `/home/mchoi/systemds-g009-unified` | `integration/g009-unified-20260919` | accepted correctness와 성능 변경 결합 |

build 산출물, `.omx*`, 임시 POM은 커밋 범위에서 제외했다. 합법 후보 cap, sampling, privacy 완화, runtime fallback으로 timeout을 숨기는 접근도 제외했다.

## 4. 커밋 이력

`451acabf03..71c598b398`에는 11개 커밋이 있다. 기각 실험은 runtime code를 커밋한 뒤 revert한 방식이 아니다. 실험 변경을 작업 트리에서 되돌린 후 결과 문서만 커밋했다.

| 커밋 | 시각 | 분류 | 내용 |
|---|---:|---|---|
| `35d1f49507` | 02:36 | 채택 runtime | FType별 durable native placement seed index, workspace 분리, manifest 갱신 |
| `6392a7ebe7` | 17:37 | 채택 runtime 통합 | correctness workspace의 accepted 재설계와 metrics/test/script/docs 통합 |
| `3f991635fe` | 17:42 | 검증 인프라 | timeout 없는 G009 snapshot evaluator 추가 |
| `f6039d789a` | 17:52 | 문서 | direct broadcast source index NO-GO |
| `b38e0d2f34` | 18:00 | 문서 | no-op dead-pruning fast path NO-GO |
| `00e8b96ff6` | 18:09 | 문서 | singleton SCC fast path NO-GO |
| `024dafb89c` | 19:08 | 채택 runtime | acyclic proof graph pruning·grounding fast path |
| `ee5b7e5987` | 19:28 | 문서 | topology dependency 공유 NO-GO |
| `77512b252f` | 19:42 | 문서 | query-local proof state interning NO-GO |
| `48600adc68` | 20:34 | 문서 | 초기-empty alternative 조기 거절 NO-GO |
| `71c598b398` | 20:45 | 문서 | proof graph·overlay 반복 상한 observer 결과 |

## 5. 상속한 correctness 및 search-space 재설계

### 5.1 P0–P4 제한 구현

상속한 첫 구현은 다음 계산·표현 개선을 포함한다.

- relocation/input product streaming
- 동일 descriptor 중복 생성 억제
- 선형 dead-alternative pruning
- first-match template index
- 64Mi-char bounded signature cache
- entry/proof/estimated-byte budget을 가진 resolver-local completed-result memo
- conservative dirty cone 기반 증분 재계산
- owner-safe support substructure factorization

이 단계의 검증 기록은 161 tests, failure/error `0/0`, finite oracle missing/illegal-extra `0/0`, package exit `0`이다. 한편 원래 production builder 진단은 약 113분 뒤 중단됐고 `INCOMPLETE_NOT_PASS`로 분류했다. 완료되지 않은 실행은 성능 합격 근거로 사용하지 않았다.

### 5.2 R0–R3 후속 재설계

후속 재설계에서 채택한 항목은 다음과 같다.

- bounded structural handle 및 legacy-exact rope ordering
- shared candidate topology와 query overlay 분리
- dependency handle 사전 계산
- 계측 scan fusion
- provenance-neutral support solution memo
- occurrence footprint 기반 revision invalidation 및 reuse
- lazy owner-bound receipt/rank
- final factorization object reuse

구현 범위는 R0/R1, R2 일부, R3의 lazy receipt/final sharing까지다. full global delta-SCC와 selector-native `Choice/Conjunction` relation은 구현하지 않았다.

이 단계의 broad gate는 173 tests, failure/error `0/0`, skip `4`였다. 최종 GLM evaluator는 `837.223s`, wall `14:07.82`, max RSS `30,550,684KiB`였다. proof query는 최초 정상 완료 대비 10.14배, alternative는 3.71배, dependency edge는 3.53배, SCC edge scan은 3.02배 감소했지만 공식 10배 시간 목표는 달성하지 못했다.

상속 단계에서 다음 실험도 기각하고 되돌렸다.

| 실험 | 결과 | 판정 |
|---|---:|---|
| public-result memo revision 이식 | 138,018 entries를 복사했으나 lookup hit 증가 없이 wall `15:50.40`, RSS `31,670,436KiB` | NO-GO |
| realization structural index | 약 2,219만 membership lookup, wall `17:27.72` | NO-GO |
| handle-key realization index | 동일 규모 lookup, wall `15:47.38` | NO-GO |

### 5.3 correctness 통합 기준점

`6392a7ebe7`에서는 correctness workspace의 accepted production patch를 공통 base `35d1f49507` 위에 복원했다. accepted artifact의 tracked production diff SHA-256과 통합 전 production diff가 일치했다.

초기 통합 gate는 96 tests, 91 active pass, 5 skip, failure/error `0/0`이었다. LM snapshot은 `10,924ms`, max RSS `1,354,132KiB`, SHA-256 `9000bff430f7fde79b901e5af6414eb4e537c1806af73056234f05830d63551a`였다. 단일 LM run은 최종 성능 판단에 사용하지 않고 이후 paired fresh-JVM 비교 기준을 마련하는 데만 사용했다.

## 6. timeout 없는 검증 체계

`3f991635fe`에서 [G009PlanningPerformanceEvaluatorTest](../src/test/java/org/apache/sysds/hops/fedplanner/placement/G009PlanningPerformanceEvaluatorTest.java)를 추가했다. 이 evaluator는 planning timeout을 강제하지 않고 다음 출력을 snapshot으로 직렬화해 expected 결과와 byte 단위로 비교한다.

- candidate fact 및 realization
- support/proof
- relocation과 input leaf
- receipt와 최종 plan 관련 출력

build 시간과 planning 시간을 분리해 측정했고, control/current는 같은 workload, heap/config, expected snapshot을 사용했다. 짧은 LM paired screen을 먼저 실행하고 의미 있는 반복 개선이 확인된 변경만 GLM으로 확대했다.

## 7. unified workspace 성능 실험

### 7.1 전체 결과

| 실험 | LM control 중앙값 | LM current 중앙값 | current 승리 | RSS 변화 | 판정 |
|---|---:|---:|---:|---:|---|
| direct broadcast source index | 11,657.5ms | 11,419ms | 1/4 | -2.92% | NO-GO, revert |
| no-op dead pruning | 11,283ms | 11,177.5ms | 2/4 | +2.50% | NO-GO, revert |
| singleton SCC grounding | 10,490.5ms | 10,095ms | 2/6 | +0.99% | NO-GO, revert |
| **acyclic pruning·grounding** | **10,304.5ms** | **9,733ms** | **7/12** | **-0.21%** | **채택** |
| topology dependency 공유 | 10,273.5ms | 10,239.5ms | 4/12 | +2.49% | NO-GO, revert |
| query-local state interning | 10,538ms | 10,394.5ms | 5/12 | +0.24% | NO-GO, revert |
| 초기-empty alternative 거절 | 9,760.5ms | 10,440ms | 6/12 | -2.54% | NO-GO, revert |
| bounded overlay cache | 9,587ms | 10,573ms | 3/6 | -1.23% | 중단, 미커밋, 개선 미확인 |

모든 완결 실험은 LM snapshot 동등성을 확인했다. 구조적 객체·edge 감소만으로 채택하지 않았고, paired wall time과 RSS가 반복해서 지지하는지를 함께 확인했다.

### 7.2 direct broadcast source index

목표는 broadcast input마다 모든 node를 다시 스캔하는 작업을 resolver 생성 시의 identity index로 치환하는 것이었다. value-version/output/snapshot/missing-edge 경계 회귀와 two-source/local-mix/LM snapshot은 통과했다.

LM 중앙값은 2.05% 좋아졌지만 current 승리는 1/4뿐이었다. 반복성이 부족해 GLM으로 확대하지 않고 revert했다.

### 7.3 no-op dead-pruning fast path

dead state와 graph 밖 dependency가 없는 경우 reverse-dependency index, viable list 복사와 compaction을 생략하려 했다. LM 중앙값 개선은 0.94%였고 승리는 2/4, RSS는 2.50% 악화됐다. 선행 scan 비용을 상쇄하지 못해 revert했다.

### 7.4 singleton SCC grounding fast path

크기 1 SCC에 대해 eligible map과 Tarjan refinement를 다시 만드는 대신 직접 grounded 조건을 검사했다. self-loop, direct ground와 ungrounded AND dependency, dependency 없는 direct ground 반례를 검증했다.

LM 중앙값은 3.77% 개선됐지만 current 승리는 2/6, RSS는 0.99% 악화됐다. 재현성이 부족해 revert했다.

### 7.5 채택: acyclic pruning·grounding fast path

query-local graph traversal에서 equality 기반 active set으로 cycle을 감지하고 dependency-first completion order를 기록한다. cycle이 하나라도 있으면 기존 dead-pruning cascade와 SCC fixed point를 그대로 사용한다. DAG이면 다음 작업을 생략한다.

- reverse-dependency index 구축
- dead queue 처리
- 반복 identity removal
- 두 차례 SCC 수집과 SCC grounding refinement

graph key와 dead state key를 유지해 occurrence revision invalidation footprint를 보존했다. 생존 alternative의 identity, 중복, 순서를 유지하고 missing dependency는 기존처럼 fail-closed로 처리한다. dead sibling만 변경된 revision에서 proof 값은 같아도 graph가 다시 생성되는 회귀를 추가했다.

검증 결과는 다음과 같다.

- ACTIONS: proof graph 52개 전부 acyclic, snapshot byte 동일
- LM: graph 13,854개 중 acyclic 13,371개, cyclic 483개
- LM: DAG 경로에서 alternative 351,893개 제거
- LM 12쌍: `10,304.5ms -> 9,733ms`, 5.55% 개선, current 7/12 승
- LM RSS: `1,341,330 -> 1,338,568KiB`, 사실상 동일
- GLM: proof graph 154,362개 전부 acyclic
- GLM: control SCC edge scan 872,529,353회, current 0회
- GLM fingerprint: 양쪽 `98b41db8...7109`
- GLM node/fact/action: 양쪽 `1,992/2,160/293`
- GLM evaluator: `794.699s -> 716.592s`, 78.107초 및 9.83% 개선
- GLM wall: `13:21.06 -> 12:02.81`, 9.77% 개선
- GLM RSS: `30,471,044 -> 30,468,196KiB`, 사실상 동일
- 보호 suite: 98 discovered / 95 active pass / 3 intended PUBLIC skip / 0 failures/errors
- branch manifest: 5,833행, baseline 대비 addition/removal 935개 전부 review TSV에서 분류
- 독립 review: severity 전 등급 지적 0건, APPROVE

historical `837.223s` 대비 14.4% 개선은 참고값이다. 채택 판정에는 같은 시점·조건의 `794.699s -> 716.592s` pair를 사용했다.

### 7.6 topology dependency 목록 공유

root pin의 영향을 받지 않는 topology row의 immutable dependency 목록과 nested state를 resolver-local cache에서 공유했다. LM dependency/state 생성은 `1,750,172 -> 252,820`으로 줄어 85.55%를 재사용했다.

그럼에도 LM 중앙값 개선은 0.33%, current 승리는 4/12였고 RSS는 2.49% 악화됐다. lookup 및 retained cache 비용이 allocation 감소를 상쇄해 revert했다.

### 7.7 query-local proof state interning

query마다 `(occurrence identity, realization handle, full witness)`가 같은 dependency state를 canonicalize했다. LM 요청 1,750,172개 중 신규 state는 194,872개였고 88.87%를 재사용했다.

LM 중앙값 개선은 1.36%, current 승리는 5/12였으며 RSS는 0.24% 악화됐다. shared-topology 통합 구조에서는 반복 가능한 성능 개선이 아니어서 revert했다.

### 7.8 초기-empty dependency alternative 조기 거절

LM의 DAG 제거 alternative 중 97.01%가 처음부터 alternative가 없는 dependency를 포함한다는 observer 결과를 바탕으로, owner alternative와 dependency wrapper 생성을 조기에 생략했다. 모든 dependency는 기존 DFS 순서로 방문해 graph key와 revision footprint를 유지했다.

구조 수치는 크게 감소했다.

| workload | alternative | dependency edge |
|---|---:|---:|
| LM | `1,185,743 -> 655,763` (-44.70%) | `1,750,172 -> 852,324` (-51.30%) |
| GLM | `284,280,699 -> 242,868,791` (-14.57%) | `513,715,666 -> 436,271,206` (-15.08%) |

실제 성능은 개선되지 않았다.

- LM: `9,760.5ms -> 10,440ms`, 6.96% 악화, current 6/12 승
- LM RSS: 2.54% 감소
- GLM evaluator: `726.065s -> 729.171s`, 3.107초 및 0.43% 악화
- GLM RSS: 사실상 동일
- GLM fingerprint와 node/fact/action 및 최종 support/proof/leaf 수: 동일

최초 GLM bootstrap attempt는 CLI `argLine`이 POM의 3GiB 값에 덮여 OOM이 발생했으므로 비교에서 제외했다. 실제 fork args를 확인한 8/32/1GiB 임시 POM 실행만 위 비교에 사용했다. 수천만 객체 생성을 없애도 traversal, topology lookup과 downstream 결과 크기가 유지되어 wall time 감소로 이어지지 않았다.

## 8. proof graph·overlay 반복 observer

초기-empty 실험 뒤 병목 위치를 다시 판단하기 위해 observer-only 계측을 수행했다. observer는 support memo miss 뒤 실제로 계산한 graph만 집계했다. signature에는 occurrence identity, pinned structural handle, full witness, template-root, ordered alternative, ordered dependency와 input position을 포함했다.

LM 결과는 다음과 같다.

| 관측 단위 | 전체 | unique | repeated | 반복 비율 |
|---|---:|---:|---:|---:|
| state overlay | 208,726 | 1,174 | 207,552 | 99.44% |
| whole proof graph | 13,854 | 1,129 | 12,725 | 91.85% |
| occurrence footprint | 13,854 | 154 | 13,700 | 98.89% |

반복 overlay가 차지한 alternative는 97.31%, dependency edge는 96.83%였다. 반복 graph가 차지한 alternative는 73.37%, edge는 72.56%였다. 기존 local topology cache도 build/hit `6,814/260,254`였으므로 동일 query overlay materialization이 반복되고 있음을 확인했다.

이 수치는 bounded double-hash observer의 재사용 상한이다. hash equality만으로 semantic equivalence나 안전한 whole-graph 공유를 증명하지 않는다. observer source와 metrics 변경은 결과 및 patch를 보존한 뒤 revert했다.

## 9. 현재 미커밋 bounded proof overlay cache

### 9.1 구현 상태

observer 결과를 바탕으로 whole-graph cache보다 작은 resolver-local overlay cache를 실험했다. 현재 수정 파일은 다음 세 개다.

- [NativePlacementContinuity.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java): bounded access-order cache, overlay key, immutable dependency list
- [SearchSpaceMetrics.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/SearchSpaceMetrics.java): hit/miss/eviction/bypass 및 retained-size 계측
- [NativePlacementContinuityTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuityTest.java): memo를 끈 반복 query에서 immutable overlay 재사용 검증

현재 diff는 200 insertions, 7 deletions다. cache 기본 budget은 entries 4,096, alternatives 131,072, dependency edges 262,144다. key에는 다음 정보가 들어간다.

- state occurrence identity
- pinned structural handle
- full witness
- template-root 여부
- root-sensitive overlay인 경우 query-fixed root occurrence와 handle

fixed context가 단일 root가 아니면 cache를 우회한다. cache는 resolver-local이고 revision 간 이전하지 않는다. cached alternatives와 dependency list는 immutable copy로 저장한다.

### 9.2 동일성 및 구조 계측

작은 snapshot은 다음 SHA-256을 생성했고 accepted 결과와 일치했다.

| workload | snapshot SHA-256 | 단일 planning 관측 |
|---|---|---:|
| two-source | `4053fcd5f281d7ca37c856363bfd4d917f37700326b70fa6135e896cd07ce5c0` | 771ms |
| local-mix | `79851b54863f3e4d54e6e1f65d34be51d84aa1a443c99147f23d1cf439898db5` | 703ms |
| LM | `9000bff430f7fde79b901e5af6414eb4e537c1806af73056234f05830d63551a` | 9,838ms |

LM metrics evaluator 결과:

- analysis fingerprint: `7fdcb7a8cbae57fc399f026e9a01d4558d9ee2582dfd15ca46cc14466a173b22`
- graph/state/alternative/edge: `13,854 / 208,726 / 1,185,743 / 1,750,172`, accepted 구조와 동일
- overlay cache hit/miss: `178,005 / 30,721`, hit rate 약 85.28%
- eviction/bypass: `0 / 0`
- 종료 시 retained entries/alternatives/edges: `308 / 1,915 / 2,728`

### 9.3 중단된 paired screen

계획한 12쌍 중 6쌍만 완결됐다. 7번째 control은 완료됐지만 current가 중단되어 pair 통계에서 제외했다.

| pair | control | current | current-control |
|---:|---:|---:|---:|
| 1 | 8,697ms | 8,158ms | -539ms |
| 2 | 11,910ms | 11,583ms | -327ms |
| 3 | 9,497ms | 10,717ms | +1,220ms |
| 4 | 9,299ms | 10,413ms | +1,114ms |
| 5 | 10,847ms | 10,429ms | -418ms |
| 6 | 9,677ms | 11,837ms | +2,160ms |

부분 결과는 다음과 같다.

- control 중앙값: `9,587ms`
- current 중앙값: `10,573ms`
- 중앙값 차이: `+986ms`, `+10.2848%`
- current 승리: 3/6
- RSS 중앙값: `1,402,148 -> 1,384,842KiB`, 1.2342% 감소
- 완료된 control/current snapshot 12개: 모두 LM accepted SHA-256과 동일
- pair 7 current: `planning-ms.txt`와 `snapshot.sha256` 미생성
- `summary.txt`: 전체 run 미완료로 생성되지 않음

중단 시점의 production diff SHA-256은 시작 전과 동일해 벤치마크 도중 source가 바뀌지 않았다. 결과는 정확성 snapshot을 지지하지만 wall-time 개선을 지지하지 않는다. 독립 최종 review, fresh branch manifest, 12-class 전체 gate, GLM paired 비교는 수행하지 않았다. 현재 상태는 **미채택·미완료 실험**이다.

## 10. 검증 수치 정리

| 단계 | 결과 | 의미 |
|---|---|---|
| seed index 통합 gate | 134 discovered / 130 pass / 4 PUBLIC skip / 0 failure | 공통 baseline bounded regression |
| 상속 P0–P4 | 161 tests / 0 failure·error / finite missing·illegal-extra 0 | 제한 corpus correctness |
| 상속 재설계 broad gate | 173 tests / 4 skip / 0 failure·error | accepted correctness source |
| unified 초기 통합 | 96 tests / 5 skip / 0 failure·error | patch 복원 및 기준점 확인 |
| DAG fast path 보호 suite | 98 discovered / 95 pass / 3 PUBLIC skip / 0 failure·error | 현재 커밋된 성능 변경 |
| early-empty 실험 | continuity 39 tests 중 38 pass/1 skip, inventory pass | 구현 correctness, 이후 성능 NO-GO로 revert |
| overlay cache | focused test와 three-workload snapshot pass, 6-pair 부분 screen | 미커밋, 전체 gate 미실행 |

PUBLIC privacy 테스트는 저장소 정책상 skip으로 분리했다. skip은 pass나 privacy 전체 cross-product의 positive evidence로 계산하지 않는다.

## 11. 모든 possible plan 보존 상태

이번 작업은 다음 bounded 증거를 확보했다.

- 동일 fixture에서 candidate/support/proof/receipt snapshot byte equality
- exact 및 dynamic witness 대표 경로
- transient replay multiplicity와 exact geometry 보존
- root pin, template fallback, ordered dependency/input position 회귀
- cyclic fallback 및 acyclic completion-order 회귀
- occurrence footprint 기반 revision invalidation 회귀
- branch inventory와 변경 branch-ID 분류
- finite decoder oracle에서 missing/illegal-extra 0

다음 이유로 전역 보존 증명은 아직 완료되지 않았다.

- PO-01, PO-02/09/21, PO-05/06/07/08, PO-10~13, PO-17/18/20 등 21개 obligation의 일부가 OPEN/PARTIAL
- RF01–RF48의 worker count, range/layout, privacy, recompile, compiler-runtime binding 축 전체 미충족
- 모든 supported opcode/layout을 독립 생성하는 generation-independent oracle 부재
- 모든 valid schedule에 대한 monotonicity·termination 정리 부재
- arbitrary caller/output 및 multiple semantic exit의 전체 function-boundary 증거 부재
- builder가 만든 모든 authority/action과 runtime 실행의 전수 correspondence 미증명
- PUBLIC-only privacy 행은 정책상 실행하지 않아 전체 privacy cross-product를 닫을 수 없음

따라서 현재 durable 판정은 계속 **OPEN / additional audit required**다.

## 12. 성능 목표 상태

| 목표 | 현재 근거 | 상태 |
|---|---|---|
| timeout 없이 동일 후보 생성 | LM 및 GLM evaluator 정상 완료, fingerprint/snapshot 동일 | bounded PASS |
| GLM planning 180초 이하 | 검증된 최선 `716.592s` | FAIL/OPEN |
| 180초 이하 3회 연속 | 해당 실행 없음 | OPEN |
| 동일 Docker fresh JVM 3회 | 미실행 | OPEN |
| build 시간과 planning 시간 분리 | evaluator에서 분리 | PASS |
| allocation/live heap 및 container RSS 반복 측정 | 일부 RSS만 확보 | OPEN |
| ML training/P1/P2/SliceLine qualification | 이번 workspace에서 미실행 | UNQUALIFIED |

## 13. artifact 위치

### 13.1 채택 DAG fast path

- LM paired: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-acyclic-fastpath-screen-r1-20260919/`
- GLM pair: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-acyclic-fastpath-glm-pair-r1-20260919/`
- local metrics: `build/g009-unified/acyclic-fastpath-metrics-20260919/`

### 13.2 기각 실험

- broadcast index: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-broadcast-index-screen-r1-20260919/`
- dead pruning: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-dead-prune-screen-r1-20260919/`
- singleton SCC: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-singleton-scc-screen-r1-20260919/`
- dependency sharing: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-dependency-share-screen-r1-20260919/`
- state interning: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-state-intern-screen-r1-20260919/`
- early-empty LM: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-early-empty-screen-r1-20260919/`
- early-empty GLM: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-early-empty-glm-pair-r1-20260919/`

### 13.3 observer와 마지막 cache 실험

- reuse observer: `build/g009-unified/proof-graph-reuse-observer-lm/`
- overlay cache metrics: `build/g009-unified/overlay-cache-lm/`
- overlay cache snapshots: `build/g009-unified/overlay-cache-snapshots/`
- overlay cache partial paired screen: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-overlay-cache-screen-r1-20260919/`

`build/`와 `/grid/3/...`는 검증 artifact 저장소이며 Git tracked source가 아니다.

## 14. 현재 working tree와 인계 조건

현재 HEAD의 커밋된 runtime 기준은 correctness 재설계와 DAG fast path다. working tree에는 bounded overlay cache 실험의 세 파일만 수정돼 있다. 보고서 작성으로 이 문서가 추가됐다.

마지막 cache 실험은 부분 결과가 악화됐으므로 merge 대상이 아니다. 후속 작업자가 이어서 판단하려면 다음 중 하나를 명시적으로 선택해야 한다.

1. 실험 patch와 artifact를 별도로 보존하고 세 source/test 파일을 HEAD로 복구한다.
2. 새로운 구조 가설이 있는 경우 현재 cache를 기준 변경으로 사용하지 않고, clean `71c598b398`에서 새 실험을 시작한다.

후속 성능 작업의 우선 조건은 합법 후보를 줄이지 않는 것, 모든 snapshot/fingerprint를 유지하는 것, 작은 paired gate에서 반복 개선을 먼저 보이는 것이다. 현재 데이터는 allocation 수 감소만으로 planning 시간이 줄어들지 않음을 반복해서 보여준다. 다음 구조 변경은 traversal/topology lookup/downstream product 자체의 비용을 줄이는지 별도 계측해야 한다.

## 15. 최종 판정

- correctness 재설계 통합: **채택·커밋 완료**
- timeout 없는 snapshot evaluator: **채택·커밋 완료**
- acyclic pruning·grounding fast path: **채택·커밋 완료**
- 나머지 unified 성능 실험: **NO-GO 및 revert**, 결과 문서 보존
- proof graph reuse observer: **계측 완료**, production source revert
- bounded proof overlay cache: **미커밋·미채택**, 6-pair 부분 결과에서 성능 악화
- 모든 possible plan 전역 보존 증명: **OPEN / additional audit required**
- GLM `<=180s` 3회 연속 및 Docker qualification: **OPEN**

이 보고서는 현재 workspace의 코드 상태, 커밋 이력, 검증 artifact와 부분 실행 결과를 함께 반영한다. 완료되지 않은 실행이나 bounded fixture 결과를 전역 PASS로 확대하지 않는다.
