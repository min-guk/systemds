# G009 correctness workspace 종합 작업 보고서

작성일: 2026-09-19 (Europe/Berlin)

대상 workspace: `/home/mchoi/systemds-g009-correctness`

브랜치: `audit/g009-completeness`

기준 HEAD: `35d1f49507a9cf3566674e68c6b97ccc1354d079`

종합 판정: **구현·유한 회귀·진단 GLM 완료 / G009 전역 증명 OPEN / 10배 성능 목표 미달 / 비-GLM workload 미검증**

## 1. 보고서 목적과 판정 원칙

이 문서는 현재 correctness workspace에서 진행한 G009 관련 작업을 다음 다섯 축으로 통합한다.

1. possible-plan 완전성·합법성 감사
2. 실제 correctness 결함 수정과 회귀 고정
3. GLM search-space 장시간 실행의 원인 분석
4. 계산량·중복·메모리 최적화와 알고리즘 재설계
5. 실험·성능·workload qualification의 현재 상태

판정은 다음 원칙을 따른다.

- 유한 corpus에서 `missing=0`, `illegal extra=0`인 것과 모든 지원 프로그램에 대한 전역 증명은 구분한다.
- timeout, 사용자 중단, tool signal 종료는 PASS로 계산하지 않는다.
- PUBLIC-only skip은 active pass에 포함하지 않는다.
- 후보 cap, sampling, dominance pruning, privacy 완화, runtime fallback으로 search space를 줄인 결과는
  correctness-preserving 최적화로 인정하지 않는다.
- 성능 수치는 완료된 artifact 원본에 결속한다. 서로 다른 revision의 wall time과 내부 work counter를
  한 행에 혼합하지 않는다.
- host evaluator는 병목 진단값이다. 공식 workload 성능은 동일 Docker 실행 규칙과 반복 측정이 필요하다.

## 2. 작업 배경과 workspace 분리

초기에는 두 세션이 `/home/mchoi/systemds-lm-worker-count-fix`와 같은 `target/`을 공유해 source와 build
산출물의 귀속이 불명확했다. 이를 해결하기 위해 통합·correctness·performance worktree를 분리했다.

| 구분 | 경로/브랜치 | 책임 |
|---|---|---|
| 통합 기준 | `/home/mchoi/systemds-g009-integration` | 검증된 변경의 직렬 통합 |
| correctness lane | 현재 workspace / `audit/g009-completeness` | 독립 oracle, obligation/rule inventory, missing/extra 검증 |
| performance lane | `/home/mchoi/systemds-g009-performance` / `perf/g009-planning` | profiling, 별도 성능 계획, Docker qualification |

상세 분리 원칙은 [G009_WORKSPACE_SPLIT_2026-09-19.md](G009_WORKSPACE_SPLIT_2026-09-19.md)에 기록돼 있다.
이후 현재 workspace에서는 다른 worktree를 reset하거나 덮어쓰지 않고 correctness와 search-space
알고리즘 작업을 계속했다.

## 3. possible-plan correctness 감사

### 3.1 목표

최종 목표는 지원되는 프로그램 `P`에 대해 다음 등식을 보이는 것이다.

```text
Decode(S(P)) = LegalPhysicalPlans(P)
```

즉 production pipeline이 모든 합법 plan을 생성하고, 불법 plan은 생성하지 않아야 한다. 이를 위해
candidate 생성, fixed point, action/anchor, canonicalization, selector, Exact/DP, runtime emission의 각
경계를 독립적으로 검사했다.

### 3.2 완료된 유한 감사 lane

| 감사 lane | 결과 |
|---|---:|
| Independent oracle | 16/16 pass; expected/legal/decoded 각 8 plan |
| Fixed-point composition | 53/53 pass |
| Action/anchor finite oracle | 27/27 pass |
| Policy boundary | 14/14 pass |
| Program relations | 40 discovered, 35 active pass, PUBLIC-only skip 5 |
| RF01–RF24 | 118/118 pass |
| RF25–RF48 | 92/92 pass |
| 최종 post-index integration gate | 134 discovered, 130 active pass, PUBLIC-only skip 4, fail/error 0/0 |

세부 receipt와 artifact는
[PLAN_SPACE_G009_PROGRESS_2026-09-19.md](PLAN_SPACE_G009_PROGRESS_2026-09-19.md)에 정리돼 있다.
이 결과는 실행한 유한 범위의 증거이며 21개 proof obligation과 48개 rule-family row를 전역적으로
닫았다는 뜻은 아니다.

### 3.3 확인하고 수정한 실제 correctness 결함

감사 과정에서 다음 결함을 반례 fixture 또는 compiler/runtime parity로 확인하고 수정했다.

1. aligned `ROW/ROW`, `COL/COL` binary plan이 삭제되던 resident-input 판정 오류
2. dynamic predecessor가 stale durable geometry로 재승격되던 오류
3. ROW REV runtime이 입력 `FederationMap`을 직접 mutation하던 오류
4. runtime은 지원하지만 compiler continuity에서 ROW ROLL 후보가 누락되던 오류
5. RF23/RF24에서 explicit local operand를 missing으로 오인하던 의미 오류
6. PART/OTHER 제한 과정에서 literal OTHER source route가 함께 삭제되던 오류
7. FULL/COL ROLL과 `REV -> ROLL -> EXP` 동적 합성 후보 누락
8. transient replay가 여러 grounded proof 중 첫 대안만 남기던 오류
9. dynamic native authority가 TWrite/TRead transient replay에서 소실되던 오류
10. exact replay가 worker-pool 표현을 lossy geometry로 사용해 decoded cardinality를
    `1,344 -> 525`로 축소하던 오류

마지막 결함 수정 후 bounded factorization은 `X=2, Y=2, U=4, V=4, D=21`, 곱 `1,344`로 복구됐다.
각 결함의 반례와 회귀 근거는 progress 문서의 4절에 연결돼 있다.

### 3.4 branch inventory

candidate에 영향을 줄 수 있는 production branch를 AST 기반 manifest로 관리했다.

- HEAD 기준 data row: `5,473`
- 현재 data row: `5,805`
- added ID: `618`
- removed ID: `286`
- 변경 ID 총 `904`개는
  [G009_SEARCH_SPACE_BRANCH_INVENTORY_REVIEW_2026-09-19.tsv](G009_SEARCH_SPACE_BRANCH_INVENTORY_REVIEW_2026-09-19.tsv)에
  전부 분류했다.

이 inventory는 선언한 파일과 AST kind의 구조적 열거를 보장한다. 각 predicate의 필요충분성 또는
universal compositional proof를 대신하지 않는다.

## 4. GLM 장시간 실행 원인 분석

### 4.1 분석 대상과 결론

장시간 fixture는
`NeutralPlacementGraphUploadRelocationRedTest#rewrittenInlinedOutputRetainsItsCompilerDeclaredTargetAuthority`다.
이 테스트는 selector/Exact/DP를 호출하기 전에 `NeutralPlacementGraphBuilder.buildAnalysis(...)`에서
오래 실행됐다. 따라서 확인된 병목은 DP plan 선택이 아니라 selector 이전의 공통 neutral
`PlacementAnalysis` 구축이다.

중단 전 실행은 약 113분 동안 진행됐지만 사용자 요청으로 멈췄다. 완료·assertion 결과가 없으므로
`INCOMPLETE_NOT_PASS`로 유지한다.

### 4.2 기존 search-space 알고리즘

분석된 계산 구조는 다음과 같다.

```text
input FType Cartesian product 전수 열거
  -> candidate physical realization 확장
  -> candidate × seed × witness별 AND/OR proof graph 생성
  -> reverse-dependency dead-alternative pruning
  -> Tarjan SCC와 seed-grounding fixed point
  -> input별 viable support Cartesian product 전수 열거
  -> semantic/CFG/function/privacy/physical nested fixed point 반복
```

입력 domain 크기를 `d_i`, viable support 수를 `r_i`라 하면 각각 `prod(d_i)`, `prod(r_i)`의 조합이
존재한다. 과거 구현은 query마다 graph, reverse index, SCC를 다시 만들었기 때문에 closure revision 수를
`R`, query 수를 `Q`, query graph의 state/alternative/edge를 `S/A/E`라고 할 때 핵심 비용은 대략
`R * Q * O(S + A + E)`에 output-sensitive product 비용이 더해지는 구조였다.

### 4.3 직접 관측된 병목 증거

- 두 thread dump 모두
  `buildDetachedAnalysis -> closeCfgTransientCandidateDependencies -> bindDirectNativeCandidateRealizations
  -> proveCandidateAlternatives -> buildCandidateProofGraph`에서 RUNNABLE이었다.
- 중단 직전 CPU 약 116%, RSS 약 11.6 GiB였고 swap/OOM 증거는 없었다.
- heap histogram은 input binding 약 47만, support clause 약 36만, reference 약 15만 live instance를 보였다.
- query별 reverse dependency와 SCC 재구축, normalized signature/hash/sort/object allocation이 hot path에 있었다.

따라서 병목 순위는 다음과 같이 판정했다.

1. candidate × seed × witness별 proof graph 재구축
2. nested fixed point에서 continuity 계산 반복
3. input/support Cartesian product 팽창
4. signature, hashing, sorting, distinct와 객체 allocation

상세 분석은
[G009_SEARCH_SPACE_CONSTRUCTION_ANALYSIS_2026-09-19.md](G009_SEARCH_SPACE_CONSTRUCTION_ANALYSIS_2026-09-19.md)에
있다.

## 5. 1차 복잡도·중복·메모리 최적화(P0–P4)

### 5.1 구현 내용

| 단계 | 구현 |
|---|---|
| P0 | analysis-scoped `SearchSpaceMetrics`, phase/query/graph/product/memo/factorization 계측 |
| P1 | relocation assignment와 input tuple의 callback streaming, proof set accumulator |
| P2 | support descriptor 중복 확장 제거, template index, 선형 dead pruning, bounded signature cache |
| P3-A | resolver snapshot 범위의 completed-result LRU와 entry/proof/estimated-byte 예산 |
| P3-B | conservative dirty connected component와 full-recompute shadow |
| P4 | owner identity를 보존하는 support proof/binding/list 공유 |

P4는 selector가 압축 relation을 직접 소비하도록 전체 pipeline을 교체한 것이 아니라 당시 eager 계약
안에서 내부 객체와 list를 공유한 제한 구현이었다.

### 5.2 짧은 feedback loop 결과

`ACTIONS` fixture에서 다음을 직접 계측했다.

- fingerprint: `48f343237ba65b7fbe074cf7b9592378c0477025cd80d6033a0a5faea3a1d0e7`
- proof query/state/alternative/edge: `52 / 128 / 144 / 92`
- duplicate support descriptor reuse: `16`
- relocation leaf: `164`, peak pending completed assignment: `1`
- dirty fact: recompute `24`, reuse `36`
- factorized proof object/list reuse: `28 / 59`
- factorized binding object/list reuse: `33 / 36`

### 5.3 유한 검증

- 26 suites, 161 tests, failure/error `0/0`, skip `8`
- 선언한 protected finite corpus에서 missing plan `0`, illegal extra plan `0`
- package exit `0`
- `git diff --check` 통과

계획, 복잡도 식, cache 경계와 당시 결과는
[G009_SEARCH_SPACE_COMPLEXITY_OPTIMIZATION_PLAN_2026-09-19.md](G009_SEARCH_SPACE_COMPLEXITY_OPTIMIZATION_PLAN_2026-09-19.md)에
있다.

## 6. 10배 목표 알고리즘 재설계(R0–R3)

1차 최적화만으로 GLM feedback loop가 충분히 짧아지지 않아 문자열 중심 identity와 query-local graph
재구축을 구조적으로 바꾸는 후속 재설계를 수행했다.

### 6.1 채택한 구현

#### R0 — 짧은 계측 loop

- `SearchSpaceMetrics` evaluator를 추가했다.
- proof graph와 SCC edge counter를 실제 graph/Tarjan 순회에 합쳐 계측용 추가 full scan을 제거했다.
- 짧은 ACTIONS fingerprint와 최종 GLM fingerprint를 결과 동일성 표지로 사용했다.

#### R1 — 구조 ID 기반 canonicalization

- analysis-local bounded structural handle arena
- hash collision 시 구조 equality 확인
- arena overflow용 음수 resolver-local handle namespace
- legacy ordering과 byte-exact한 segmented/rope lexical comparator
- 불필요한 중간 sort와 signature serialization 제거
- bounded signature cache와 already-canonical list 공유

#### R2 — 공유 topology와 증분 support 계산

- occurrence/witness별 `CandidateTopology`를 root pin·seed provenance overlay와 분리
- clause/fixed structural handle 사전 계산
- completed support solution memo를 외부 seed provenance와 분리하되 결과 생성 시 provenance 재부착
- memo entry가 실제 방문 occurrence footprint를 보유
- 다음 revision에서 footprint fact가 동일하고 invalidation되지 않은 경우에만 재사용
- eviction 또는 zero budget은 결과 축소가 아니라 exact recomputation으로 처리

R2는 부분 구현이다. query-local pruning/SCC를 하나의 전역 delta-SCC solver로 완전히 교체하지 않았다.

#### R3 — lazy receipt와 final sharing

- candidate receipt와 exact legacy rank를 selector가 요청할 때까지 생성하지 않음
- replacement가 없으면 final clause/fact/realization을 재사용
- GLM에서 receipt relation slot은 `99,632`지만 실제 `candidateReceiptsCreated=0`,
  `receiptRankKeyChars=0`

따라서 eager receipt가 현재 주 병목이라는 조건은 성립하지 않았다. selector가 `Choice/Conjunction`
압축 relation을 직접 소비하는 전체 전환은 구현하지 않고 보류했다.

### 6.2 의미 보존 경계

- candidate cap, sampling, dominance pruning, runtime fallback을 추가하지 않았다.
- TRead/TWrite는 기존 `<CP,LOUT>` 또는 `<FED,FOUT>` 계약을 유지한다.
- occurrence, producer authority, seed provenance, action, layout exactness를 hash 하나로 병합하지 않는다.
- function-input validation은 구조 authority와 row shape만 검증하며 final candidate legality 검사를
  대체하지 않는다.

## 7. 최종 correctness·build 검증

채택 구현의 주 검증 artifact는 다음과 같다.

| 검증 | 결과 | Artifact |
|---|---:|---|
| Broad selected suite | 173 tests, failure/error 0/0, skip 4 | `build/g009-redesign/final-broad-20260919T144851+0200/` |
| ACTIONS fingerprint | 동일 | `build/g009-redesign/fused-metrics-actions-20260919T143600+0200/metrics.json` |
| GLM fingerprint | 동일 | `build/g009-redesign/final-glm-revision-support-20260919T145212+0200/metrics.json` |
| Package | exit 0 | `mvn -q -DskipTests package` |
| Branch inventory checker | PASS | 현재 5,805 data rows와 904-row review |
| Patch integrity | current tracked patch = accepted artifact patch | SHA-256 `487b31ef...a4c505` |
| Production patch integrity | current `src/main/java` patch | SHA-256 `9009de2e...957bdc` |
| Diff hygiene | PASS | `git diff --check` |

동일 fingerprint는 관측 대상으로 정의한 analysis 결과가 같다는 강한 회귀 표지지만 독립 전역 oracle
증명 자체는 아니다.

## 8. GLM 성능 결과

### 8.1 채택 최종 run

Artifact:
`build/g009-redesign/final-glm-revision-support-20260919T145212+0200/`

| 지표 | 값 |
|---|---:|
| exit | 0 |
| evaluator elapsed | 837.223 s |
| process wall | 14:07.82 |
| max RSS | 30,550,684 KB |
| node / candidate fact / relocation action | 1,992 / 2,160 / 293 |
| proof query/graph | 154,362 |
| proof state | 5,264,559 |
| proof alternative | 284,280,699 |
| proof dependency edge | 513,715,666 |
| SCC edge scan | 872,529,353 |
| support memo hit/miss | 1,410,457 / 154,362 |
| revision support reuse | 66,326 |

### 8.2 earliest successful redesign run과 비교

동일 fingerprint를 가진 완료 run
`final-glm-structural-validation-20260919T134011+0200`를 시작점으로 비교하면 다음과 같다.

| 지표 | 시작점 | 채택 최종 | 개선 |
|---|---:|---:|---:|
| evaluator elapsed | 989.132 s | 837.223 s | 1.181x, 15.36% 감소 |
| process wall | 16:36.51 | 14:07.82 | 1.175x, 14.92% 감소 |
| max RSS | 32,677,300 KB | 30,550,684 KB | 6.51% 감소 |
| proof query/graph | 1,658,372 | 154,362 | 10.74x 감소 |
| proof alternative | 1,122,570,689 | 284,280,699 | 3.95x 감소 |
| proof dependency edge | 1,930,476,734 | 513,715,666 | 3.76x 감소 |
| SCC edge scan | 2,801,336,412 | 872,529,353 | 3.21x 감소 |

결론적으로 내부 graph/SCC work는 3–11배 줄었지만 전체 wall은 약 1.18배만 개선됐다. 남은 시간은
final realization merge, canonical boundary, provenance-bearing proof/closure 재생성 및 allocation에
상당 부분 남아 있는 것으로 추론된다.

### 8.3 immediate predecessor와 비교

`final-glm-precomputed-handles-20260919T141736+0200`에서 revision support reuse를 추가한 마지막 단계만
비교하면 다음과 같다.

- wall `14:34.83 -> 14:07.82`: 1.032x, 3.09% 감소
- evaluator `867.380 -> 837.223 s`: 3.48% 감소
- RSS `32,569,540 -> 30,550,684 KB`: 6.20% 감소
- proof query `10.14x`, alternative `3.71x`, dependency edge `3.53x`, SCC edge `3.02x` 감소

### 8.4 기존 문서 수치의 기준점 정리

기존 redesign/complexity/session 문서의 성능 표는 wall·evaluator·RSS에는
`structural-validation`을 시작점으로 사용했지만, proof query/alternative/dependency/SCC 감소율에는
`precomputed-handles`를 시작점으로 사용했다. 개별 숫자는 각 artifact에 존재하지만 한 행의 동일
baseline 비교는 아니었다.

이 보고서는 이를 분리했다. 따라서 공식적으로 재사용할 headline은 다음 두 문장이다.

- 전체 redesign 구간: **wall 14.92%, evaluator 15.36%, RSS 6.51% 감소**
- 마지막 revision-support 단계: **wall 3.09% 감소, 내부 proof work 3.02–10.14배 감소**

## 9. 기각하고 revert한 실험

성능이 악화되거나 실제 reuse를 늘리지 못한 실험은 production diff에서 제거하고 artifact만 보존했다.

| 실험 | 결과 | 판정 |
|---|---:|---|
| public completed-result memo의 revision 이전 | wall 15:50.40, RSS 31,670,436 KB; 138,018 entry copy, lookup hit 증가 없음 | 기각·revert |
| occurrence별 structural realization index | wall 17:27.72, RSS 30,464,588 KB; 약 2,219만 membership lookup | 기각·revert |
| handle-key realization index | wall 15:47.38, RSS 30,413,996 KB; 같은 규모 handle lookup | 기각·revert |

실패 artifact는 각각 `final-glm-public-memo-*`, `final-glm-realization-index-*`,
`final-glm-handle-realization-index-*` 아래 남겨 재발 방지 근거로 사용한다.

## 10. workload qualification 현황

| Workload | 현재 상태 | 설명 |
|---|---|---|
| GLM diagnostic evaluator | **완료** | host에서 exit 0, fingerprint 동일, 최종 14:07.82 |
| GLM official Docker 3-run | **미실행** | 동일 `run_LAN_docker.sh`, fresh JVM 교차 반복 없음 |
| 다른 ML training | **UNQUALIFIED** | 이번 작업에서 실행하지 않음 |
| P1 | **UNQUALIFIED** | 실행하지 않음 |
| P2 | **UNQUALIFIED** | 실행하지 않음 |
| SliceLine | **UNQUALIFIED** | 실행하지 않음 |

따라서 현재 수치를 전체 SystemDS workload 성능 개선으로 일반화할 수 없다.

## 11. 현재 workspace 변경 구성

### 11.1 production

- `LogicalBoundaryRealizations.java`
- `NativePlacementContinuity.java`
- `NeutralPlacementGraphBuilder.java`
- `PlacementAnalysis.java`
- `PlacementEmissionState.java`
- `PlacementIdentity.java`
- `PlacementState.java`
- 신규 `SearchSpaceMetrics.java`

핵심 변경은 structural handle/canonical ordering, shared topology, support memo/revision reuse, streaming,
dirty closure, factorization, lazy receipt, 계측이다.

### 11.2 tests와 resource

- `CandidateRealizationCanonicalizationTest.java`
- `NativePlacementContinuityTest.java`
- `NeutralPlacementFixedPointCompositionTest.java`
- `NeutralPlacementGraphUploadRelocationRedTest.java`
- 신규 `NeutralPlacementBindingAssignmentStreamingTest.java`
- 신규 `SearchSpaceMetricsEvaluatorTest.java`
- `candidate-affecting-branches.tsv`

### 11.3 문서와 실행 도구

- [G009_SEARCH_SPACE_CONSTRUCTION_ANALYSIS_2026-09-19.md](G009_SEARCH_SPACE_CONSTRUCTION_ANALYSIS_2026-09-19.md)
- [G009_SEARCH_SPACE_COMPLEXITY_OPTIMIZATION_PLAN_2026-09-19.md](G009_SEARCH_SPACE_COMPLEXITY_OPTIMIZATION_PLAN_2026-09-19.md)
- [G009_SEARCH_SPACE_ALGORITHMIC_REDESIGN_PLAN_2026-09-19.md](G009_SEARCH_SPACE_ALGORITHMIC_REDESIGN_PLAN_2026-09-19.md)
- [G009_SEARCH_SPACE_BRANCH_INVENTORY_REVIEW_2026-09-19.tsv](G009_SEARCH_SPACE_BRANCH_INVENTORY_REVIEW_2026-09-19.tsv)
- [SESSION_ISSUES_2026-09-19.md](SESSION_ISSUES_2026-09-19.md)
- `scripts/fedplanner/run_g009_planning_correctness.sh`

현재 변경은 의도적으로 미커밋 상태다. clean tree나 integration 완료를 주장하지 않는다.

## 12. 완료·미완료 판정

### 완료한 항목

- bounded independent oracle과 selected regression 실행
- 확인된 10개 correctness 결함의 반례 수정 및 회귀 고정
- search-space 알고리즘과 GLM 병목 분석
- P0–P4 제한 최적화 구현
- R0/R1, R2 부분, R3 lazy/final-sharing 구현
- 최종 GLM diagnostic E2E 완료
- accepted patch와 현재 tracked diff의 SHA-256 일치 확인
- branch manifest 재생성 및 904개 변경 ID 분류
- package와 diff hygiene 검증

### 미완료 또는 OPEN인 항목

- 21개 proof obligation과 48개 rule family의 전역 completeness/legality 증명
- termination과 모든 valid schedule의 일반 정리
- full global delta-SCC solver
- selector-native `Choice/Conjunction` 압축 relation
- overall wall 10배 개선
- 동일 Docker 환경의 3회 이상 공식 GLM 성능 비교
- container RSS, allocated bytes, live-heap 반복 검증
- ML training, P1, P2, SliceLine qualification
- integration branch 반영, commit, push

## 13. 최종 결론

이번 workspace 작업은 G009 search-space가 느린 이유를 DP selector가 아니라
**query별 proof graph/prune/SCC 재구축과 nested closure의 반복**으로 좁혔고, 합법 후보를 줄이지 않은 채
구조 handle, shared topology, support memo와 revision reuse를 도입했다. 그 결과 내부 proof work는 기준에
따라 약 3–11배 줄었고 GLM diagnostic은 정상 완료했다.

그러나 사용자가 요구한 order-of-magnitude 수준의 최종 wall 개선은 달성하지 못했다. earliest successful
redesign run 대비 실제 wall 개선은 **1.175x(14.92%)**, RSS 감소는 **6.51%**다. 또한 유한 test의
missing/extra 0과 fingerprint 동일성은 강한 bounded evidence지만 G009의 전역 증명은 아니다.

따라서 현재 최종 상태는 다음과 같이 요약한다.

```text
구현: bounded redesign 완료
유한 correctness: PASS
GLM diagnostic: PASS
전역 correctness/termination proof: OPEN
10x wall target: FAIL/OPEN
official Docker performance: UNQUALIFIED
ML/P1/P2/SliceLine: UNQUALIFIED
```
