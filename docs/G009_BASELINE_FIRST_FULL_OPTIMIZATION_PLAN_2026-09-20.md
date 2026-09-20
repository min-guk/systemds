# G009 기준선 우선 전체 최적화 실행 계획

- 작성일: 2026-09-20
- 상태: **실행 전 계획**
- 범위: 계획에 열거된 모든 최적화 항목의 구현 또는 실행 가능한 prototype, 정확성 검증, 동일 workload 성능 판정
- 이번 문서에서 하지 않는 일: production 코드 수정, benchmark 실행, commit, push

## 1. 정정된 목표와 종료 조건

이 계획은 이전의 잘못된 종료 판단을 반복하지 않는다.

- 공식 비교에서 고정 기준선 B0의 `CandidateE2E` 중앙값은 `36.632751초`, 현재 후보 C0의 중앙값은 `43.153788초`였다. C0/B0 ratio of medians는 `1.178011`이므로 C0는 약 `17.8%` 느리다.
- B0부터 이미 60초 미만이었다. 따라서 **후보가 60초 안에 끝났다는 사실만으로는 최적화 성공도, 계획 완료도 아니다.**
- `R3`, `R4`, `R5`를 “60초를 이미 통과했다”는 이유로 `NO-ENTRY` 처리한 이전 결정은 이번 실행의 완료 기준으로 인정하지 않는다.
- 구현을 시도하지 않은 항목, 실행 가능한 prototype이 없는 항목, 정확성 또는 성능 검증이 막힌 항목은 **미완료**다. 문서상의 설명이나 위험 판단만으로 완료로 바꾸지 않는다.
- 구현된 항목과 최종 production에 채택된 항목은 별도로 집계한다. 모든 항목을 다루되, B0보다 빨라진다는 증거가 있는 변경만 최종 production 후보에 포함한다.

최종 종료 조건은 다음을 모두 만족하는 것이다.

1. 이 문서의 P1–P5 및 R1–R6 항목이 모두 `ACCEPTED`, `REJECTED_WITH_EVIDENCE`, `BLOCKED_INCOMPLETE` 중 하나로 판정된다.
2. `REJECTED_WITH_EVIDENCE`에는 실제 구현/prototype, 정확성 결과, 동일 workload 측정이 있다. 위험 설명만 있는 거절은 허용하지 않는다.
3. `BLOCKED_INCOMPLETE`가 하나라도 있으면 “전체 계획 완료”라고 쓰지 않는다.
4. 최종 production 집합은 정확성 계약을 만족하고 B0보다 빠르며 memory gate를 통과한다.
5. 최종 동결 후보를 새로운 holdout campaign으로 B0 및 마지막 채택 parent와 비교한다.
6. `Tplanning_full_initial`, 기존 `CandidateE2E`, 실제 GLM runtime을 서로 다른 지표로 보고한다.
7. 60초 gate와 역사적 10배 gate는 별도 판정하며, 60초 통과만으로 실행을 중단하지 않는다.

## 2. 기준선과 알려진 증거

### 2.1 동결할 세 revision

| 이름 | commit | tree | 현재 알려진 의미 |
|---|---|---|---|
| B0 | `3bdbb8042f1771dc4553c4246982d61e3f9037f1` | `059ccec789f11446d1ed1ad541377463c58d2843` | 이전 공식 18-pair control. commit 제목은 `Harden R6 baseline qualification runner` |
| C0 | `49a313125f76c270f8bc63165326b9a844568737` | `9f6779330a591beb55564931178c0b05050ff9b3` | P5/R1/R2 일부를 유지한 현재 후보. B0보다 느린 것으로 관측됨 |
| F | 실행 후 동결 | 실행 후 동결 | B0보다 빨라진 변경만 포함한 최종 후보 |

B0와 C0는 현재 Git ancestry에서 단순한 직계 전후 관계로 가정할 수 없다. 실행 전에 다음을 수행한다.

1. M0 검증에서 B0의 production/test Java는 parent `a5df7797062c0680adbc985577d97741f3949a3a`와 같고, B0의 변경은 qualification runner와 Python tests에 한정됨을 확인했다. 이 identity를 최종 manifest에서 다시 고정한다.
2. `a5df779...`보다 앞서 이미 채택된 최적화가 있으므로 B0는 pristine/unoptimized source가 아니다. B0에 포함된 P1–P5 계열 변경을 inventory한다.
3. B0는 “공식 strict control”로 사용하되 “최초의 무최적화 구현”이라고 부르지 않는다.
4. 실제 무최적화 원본을 재구성할 수 있다면 별도 `Bpristine`으로 보조 비교하되 primary gate는 사전 동결한 B0로 유지한다.
5. B0, C0, 각 실험 후보는 별도 clean worktree, 별도 target/JAR, 별도 stage를 사용한다. main을 force reset하거나 한 worktree의 산출물을 다른 revision에 재사용하지 않는다.

### 2.2 716.592초의 정확한 의미

`716.591801515초`는 P1/P2 이전 host의 `SearchSpaceMetricsEvaluatorTest`가 기록한 **diagnostic evaluator elapsed**다. 해당 timer는 `compileBuiltinGlmFixture` 호출 전부터 시작하여 directory 생성, GLM fixture compile, `buildAnalysis`, 결과 assertion까지 포함한 뒤 끝난다. 그러므로 이것은 `buildAnalysis`-only 시간도, 전체 production planning도, 전체 GLM job도 아니다. 공식 Docker `CandidateE2E`나 새 `Tplanning_full_initial`의 기준선으로 사용할 수 없다.

원본 artifact:

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-acyclic-fastpath-glm-pair-r1-20260919/current/metrics.json
```

기존 host diagnostic도 경계가 다르다.

| record | metric | 값 | 경계 주의 |
|---|---|---:|---|
| pre-P1/P2 unified diagnostic | `SearchSpaceMetricsEvaluatorTest` elapsed | `716.592초` | fixture compile·directory 생성·`buildAnalysis`·assertion 포함 |
| P3 | build / canonical export / process wall | `450.977초` / `365.350초` / `13:43.49` | 별도 timer 세 개 |
| P4 | build / canonical export / process wall | `279.018초` / `368.579초` / `11:15.14` | 별도 timer 세 개 |
| P5a | build / canonical export / process wall | `245.047초` / `358.011초` / `10:10.66` | 별도 timer 세 개 |

이 계열은 다음 절차로 별도 재구성한다.

- 원본 input, GLM script, config, privacy, worker topology, JDK, heap/GC, host/container, source/JAR hash, evaluator와 timer 경계를 manifest로 매핑한다.
- 과거 기록의 commit prefix `00e8...`와 source-diff hash만으로는 당시 working tree 전체를 재구성할 수 없다. 그것만 일치한다고 동일 historical source를 복원했다고 판정하지 않는다.
- 동일 조건 재생성이 불가능하면 `HISTORICAL_NONCOMPARABLE`로 표시하고 716.592초를 speedup 분모로 사용하지 않는다.
- 재생성할 수 있으면 같은 경계의 동시점 pair로만 비교한다. `build`, `export`, `process wall`을 합치거나 서로 대신 사용하지 않는다.
- 역사 진단은 병목 설명용이며 최종 production 채택 gate를 대체하지 않는다.

## 3. 시간 측정 계약

### 3.1 primary: 전체 GLM 초기 planning wall time

새 primary 지표를 `Tplanning_full_initial`로 정의한다. 구현 전에 실제 production 호출 경로를 추적하여 시작·종료 지점을 테스트로 고정한다.

```text
start: 원본 GLM 프로그램의 최초 compilation/planning episode 진입 직전
end:   그 초기 compilation episode의 모든 planner invocation, 선택 적용,
       final verification, registration, receipt handoff 및 runtime-program 생성이 완료된 직후
```

포함 범위:

- 원본 GLM script의 parse/compile 및 초기 HOP rewrite 중 planning에 선행하는 작업
- final physical normalization과 memory refresh
- placement analysis
- physical model/cost surface/optimizer/selection/application
- final boundary verification, registry 갱신, receipt handoff
- 초기 compilation episode 안에서 실제 발생한 **모든** planner invocation

제외·별도 범위:

- canonical snapshot export와 profiler output
- GLM 학습/추론 runtime
- runtime 중 dynamic recompilation. 이를 측정하면 각 episode를 `Tplanning_dynamic[i]`로 별도 기록하고 초기 planning에 몰래 합치거나 생략하지 않는다.

검증 조건:

- baseline과 candidate가 같은 원본 script의 같은 control/function path를 통과해야 한다.
- early planning stop, 함수 생략, control path 축소, invocation 누락은 speedup이 아니라 correctness failure다.
- invocation 수와 각 receipt를 모두 기록한다. 한 번만 빠른 invocation을 골라 전체 planning으로 보고하지 않는다.
- 현재 공식 workload 계약이 정확히 한 번의 exact-phase invocation을 요구한다면 그 조건도 유지한다. 실제 workload가 여러 invocation을 수행하는 것으로 확인되면 새 full metric은 모두 포함하고 기존 단일-call 계약을 조용히 바꾸지 않는다.

### 3.2 secondary: 기존 CandidateE2E

`CandidateFormationTiming`과 `DMLTranslator.runFederatedPlannerAtFinalHopBoundary`가 제공하는 `CandidateE2E` v1은 유지한다.

- 시작: final physical normalization 이전의 common preparation 시작
- 종료: final verification, registration 및 planner receipt handoff 이후
- exact phase attribution의 exclusive phase 합은 total과 같아야 한다.

`CandidateE2E`는 `Tplanning_full_initial`의 하위 구간 또는 invocation별 지표다. 새 full metric으로 이름을 바꾸거나 기존 결과와 혼합하지 않는다. 최종 18-pair의 같은 프로세스 실행에서 두 지표를 함께 얻되 서로 별도 gate로 보고한다.

### 3.3 별도 지표

- `Tbuild`: diagnostic `buildAnalysis` 경계
- `Texport`: canonical snapshot serialization
- `Tjob`: 실제 GLM job 시작부터 학습 종료까지
- `Tplanning_dynamic[]`: runtime recompilation별 planning episode
- allocation, GC, CPU sampling: wall time을 설명하는 보조 지표이며 wall time과 더하지 않는다.

독립 실행에서 얻은 `CandidateE2E`, runtime, export 시간을 합쳐 가상의 E2E를 만들지 않는다.

## 4. 수학적 채택 기준

metric `m`을 `Tplanning_full_initial` 또는 `CandidateE2E`, 비교 대상 `Y`를 B0 또는 마지막 채택 parent P라 하고

```text
r^m_(X/Y,i) = T^m_X,i / T^m_Y,i
M^m_(X/Y) = median(T^m_X) / median(T^m_Y)
```

로 정의한다.

### 4.1 최종 faster-than-baseline gate

최종 18-pair holdout에서 ratio를 정렬한 `r_(13)`에 대해 다음을 모두 요구한다. 이 gate는 새 broad metric 하나에만 적용하지 않고 **기존 고정 경계와 새 전체 경계 모두**에 적용한다.

```text
Tplanning_full_initial: r_(13) < 1.00 AND M <= 0.98
CandidateE2E:           r_(13) < 1.00 AND M <= 0.98
```

- `r_(13) < 1`은 one-sided median confidence bound가 baseline보다 빠른 방향인지 확인한다.
- `M <= 0.98`은 중앙값 기준 최소 2% 개선을 요구하는 **제안된 공학적 noise margin**이다. 현재 관측에서 보편적으로 입증된 noise floor도, 미래 실행에 대한 보장도 아니다.
- F/B0와 F/P 모두 같은 두 metric gate를 통과해야 한다. 단, F와 P가 byte-identical source/tree/JAR인 경우에만 F/P는 identity로 표시하고 `N/A`로 판정한다.
- 새 `Tplanning_full_initial`이 빨라도 기존 `CandidateE2E`가 회귀하면 최종 후보를 채택하지 않는다.
- 2% 미만 개선은 correctness가 맞아도 `INCONCLUSIVE`다. 이는 terminal success가 아니며 추가 사전 고정 측정으로 해소하거나 `REJECTED_WITH_EVIDENCE`로 제외한다.
- 기존 10배 gate `r_(13) <= 0.10`은 **CandidateE2E에만** 적용하는 별도 역사 목표로 계속 보고한다.
- candidate의 모든 `CandidateE2E <= 60초`도 별도 absolute gate다. 이것은 faster-than-baseline gate를 대체하지 않는다.

### 4.2 bundle pilot

사소한 patch마다 18-pair campaign을 돌리지 않는다. 각 논리 bundle마다 profiler-off fresh JVM 3쌍 pilot을 수행한다.

- primary pilot pair는 X 대 B0다.
- X가 이전 채택 parent P에 이어지는 증분 bundle이면 같은 block에서 P도 실행하여 X/B0와 X/P를 모두 계산한다.
- 순서는 작은 balanced block으로 교차하고 raw run을 전부 보존한다.
- pilot의 provisional acceptance는 correctness PASS, 3쌍 모두 정상 완료, 두 planning metric 각각의 median X/B0 `<=0.98`, 각 metric에서 적어도 2/3 pair의 X가 B0보다 빠름, memory ratio `<=1.05`다.
- P가 B0와 다르면 두 planning metric의 X/P에도 같은 `median <=0.98` 및 `2/3 이상 승리`를 요구한다. 이를 통과하지 못한 독립 증분 patch는 parent에 추가하지 않는다.
- pilot은 후보 선택용이며 최종 통계적 acceptance가 아니다. 최종 holdout 실패를 pilot 성공으로 덮지 않는다.
- pilot이 `INCONCLUSIVE`이면 채택하지 않는다. 더 큰 사전 고정 follow-up으로 해소하거나, 재측정 후에도 해소되지 않으면 evidence를 보존하고 거절한다.

독립 patch는 단독으로 이득을 보여야 한다. 의미상 서로 의존하여 단독 조합이 안전하지 않은 변경은 사전에 하나의 bundle로 묶고 bundle 전체 이득을 요구한다. 유용하지 않은 독립 변경을 큰 bundle 안에 숨기지 않는다.

### 4.3 누적과 leave-one-out

- 채택 parent에서 다음 bundle을 추가할 때 X/P와 X/B0를 함께 보고, 위 provisional gate를 둘 다 통과해야 증분 채택한다.
- 여러 변경이 같은 비용을 줄이면 speedup 배수를 곱하지 않는다.
- 최종 후보에서 안전하게 제거 가능한 독립 bundle은 leave-one-out으로 기여를 확인한다.
- dependency를 깨는 feature-flag 조합, production에 존재하지 않는 혼합 구현, 불완전한 authority 경계는 leave-one-out 대상으로 만들지 않는다.
- 의미상 의존하는 bundle은 하나의 단위로 평가한다. 정확하지 않거나 production에 존재하지 않는 unsafe ablation을 억지로 만들지 않는다.
- 최종 production 집합은 B0보다 빠르다는 통합 증거가 있어야 한다. 개별 이득의 합이 양수라는 추정으로 대체하지 않는다.

## 5. 모든 항목의 공통 실행 상태기계

모든 P/R 항목은 다음 순서를 거친다.

```text
INVENTORY
  -> IMPLEMENTED_OR_PROTOTYPED
  -> EXACT_CORRECTNESS_PASS
  -> 3-PAIR_PILOT
  -> ACCEPTED | REJECTED_WITH_EVIDENCE | BLOCKED_INCOMPLETE
```

각 항목의 최소 증거:

1. 수정한 source와 algorithm boundary
2. full reference 또는 independent finite oracle와의 정확성 비교
3. candidate/fact/support/assignment/cost/order/authority/selection fingerprint
4. B0 및 필요한 경우 last accepted parent와 동일 workload 3-pair 결과
5. process RSS, cgroup peak, allocation/GC 보조 결과
6. 채택·수정·거절 이유와 실패 artifact

prototype이 정확성에 실패하면 첫 divergent checkpoint를 보존하고 수정 반복한다. 정확하지만 느리면 원인을 한 번 좁혀 개선한 뒤 재측정한다. 그래도 gate를 통과하지 못하면 `REJECTED_WITH_EVIDENCE`로 제외한다. 구현 자체가 불가능하거나 환경이 막히면 `BLOCKED_INCOMPLETE`이며 전체 계획은 완료가 아니다.

## 6. 기존 P1–P5 계획 전수 inventory와 재평가

기준 문서는 `docs/G009_SEARCH_SPACE_COMPLEXITY_OPTIMIZATION_PLAN_2026-09-19.md` §4와 `docs/G009_CANDIDATE_E2E_TENTH_PLAN_2026-09-19.md`의 P1–P5다. “과거에 구현됨”이라는 기록만으로 채택하지 않고 B0 대비 다시 평가한다.

### P1. 중간 product 제거와 증명된 조기 거절

서로 독립적으로 추적할 항목:

1. relocation assignment streaming
2. input tuple streaming
3. raw proof sink/canonical accumulator
4. `LegalCompletions(prefix)=∅`가 독립 oracle로 증명되는 조기 거절

각 항목은 leaf/tuple 방문 순서와 수, final legal set, seed/witness/authority, mutable buffer alias, exception 동작을 old reference와 비교한다. 조기 거절은 streaming과 별도 patch/bundle로 측정하며 unknown/staging/ungrounded를 영구 불법으로 취급하지 않는다.

과거 구현이 B0에 이미 있으면 source inventory와 counter로 존재를 확인하고, B0에 내재된 이득으로 분류한다. C0에만 있으면 B0 및 parent against pilot을 수행한다. 이득이 없으면 final F에 추가하지 않는다.

### P2. 동일 의미 생성·정규화 중복 제거

모든 하위 항목을 각각 다룬다.

1. 완전한 root/seed/witness/pin/action identity가 같은 product descriptor의 단일 확장
2. analysis-scoped immutable binding/clause/identity structural sharing
3. hot path 구조 key/analysis-local ID와 canonical lexical ordering 경계 분리
4. dead-alternative alive flag/live count와 마지막 1회 compaction

hash collision은 equality로 확인하고 static/global interner는 금지한다. owner membership과 receipt authority는 구조 equality로 대체하지 않는다. 중복 factor 1/2/4/8 및 dead fan-out에서 work 감소를 확인하되 wall 개선이 없으면 채택하지 않는다.

### P3-A. 안전한 exact-context query 재사용

full context에 analysis identity, semantic revision, root occurrence, ordered inputs, pinned realization, external seed, exact witness, template mode, fixed/pinned environment를 포함한다. completed result만 publish한다. empty/failure도 현재 revision에만 유효하다. entry/estimated bytes/proof 수 budget 0·small·eviction에서 결과가 같아야 한다.

P5 topology/hash reuse 및 R4와 중복되는 부분을 inventory하여 같은 최적화를 두 번 계산하지 않는다. full-context hit의 절약 시간이 lookup/size/GC 유지비보다 큰지 pilot으로 판정한다.

### P3-B. 영향 cone worklist

positive dependency뿐 아니라 absence/failure/rejection의 negative dependency도 기록한다. add/remove/replace, privacy, reaching definition, exactness, SCC split/merge를 처리한다. 불확실한 dirty cone은 exact recomputation으로 넓히되 후보나 정책을 바꾸지 않는다.

0/1/10/100% dirty fixture에서 full recompute와 각 checkpoint를 비교한다. 의미상 R2 change reporting 및 R3-B delta recombination과 결합해야 안전하면 하나의 dependency bundle로 구현·평가한다.

### P4. factorized relation과 consumer 연결

analysis 내부 객체 공유만이 아니라 Choice/Conjunction 관계, correlated alternatives, owner/occurrence/authority/action/edge position/multiplicity를 보존한 exact representation을 prototype한다. builder 직후 다시 전부 flatten하면 완료가 아니다.

R5-A/B/C와 같은 consumer boundary를 다루므로 하나의 설계 bundle로 묶는다. flat reference adapter는 correctness 전용으로 유지하고 production path에서 비선택 leaf 전체 export가 발생하는지 측정한다.

### P5. 통합·qualification

P5는 단일 최적화 patch가 아니라 integration 및 acceptance 단계다. 다음을 모두 수행한다.

- 선택된 bundle의 누적 정확성 및 성능 재검증
- source/JAR/stage/command manifest 동결
- compile/package/static/diff/branch inventory
- final 18-pair holdout
- B0, last accepted parent, F 비교
- actual GLM runtime 의미 검증
- 60초, faster-than-B0, 역사적 10배, memory를 별도 판정

## 7. 개정 R1–R6 계획의 전수 실행

기준 문서는 `docs/G009_REVISED_60S_OPTIMIZATION_PLAN_2026-09-20.md` §4다.

### R1-A. DP owner grouping

`ExactPhysicalModel.build`의 ordered facts를 owner identity별로 한 번 묶고 Alternative 순서, signature, source ownership, synthetic boundary/source/action을 보존한다. C0에 존재하는 구현을 B0 diff로 분리하여 재평가한다.

완료 증거:

- old/new Alternative ordinal과 전체 signature 동일
- per-node whole-facts scan 제거 counter
- B0 및 parent 3-pair full planning pilot
- 독립 patch라면 단독 benefit. 단독 이득이 없으면 다른 bundle에 이유 없이 포함하지 않음

### R1-B. closure index 수명

정적 index, 가변 native/executable-reference index, dependency index를 inventory한다. 각 index에 read set, owner, epoch, invalidation, deletion/replacement stale removal을 명시한다. 한 index씩 prototype하고 full snapshot rebuild와 0/1/10/100% dirty 결과를 비교한다.

index 유지비와 retained memory가 절약한 scan보다 크면 evidence와 함께 거절한다. 새 범용 cache framework는 만들지 않는다.

### R2. Relation no-op과 정확한 change reporting

다섯 항목을 모두 구현/prototype하고 각각 판정한다.

1. immutable route/atom 재사용과 exact no-op에서 기존 relation 객체 반환
2. union/filter/binding이 이미 계산한 no-change/positive-change를 revision classifier에 전달하고 초기에는 SHADOW full comparison 유지
3. 필요한 equivalence/subset cache를 analysis/epoch-local 유한 budget으로 제한하고 collision을 exact equality로 확인
4. flat/factorized 등 표현이 다른 relation의 외연 equality/subset exact fallback 보존
5. relation 객체 생성과 foreign clause/receipt ownership을 분리하고 owner/relation/epoch authority 검증 유지

C0의 existing R2 identity reuse를 B0 및 last accepted parent와 재측정한다. identical/no-op export 0, 0% semantic change dirty owner 0, positive/deletion/replacement full classifier parity, correlated/incomplete grid/repeated occurrence/deferred metadata fixture를 요구한다.

### R3-A. physical rebuild의 안전한 authority 보존

base oracle과 derived support의 dependency를 rule input domain, capability/shape/profile, privacy, source/witness, relocation/action identity까지 고정한다. 모두 같은 owner만 exact realization/support를 보존한다. 변화가 있으면 completed physical→grounding→CFG replay exact 경로로 재계산한다.

no-change에서 불필요한 second grounding 0과 authority parity를 확인한다. 위험하다는 이유만으로 생략하지 않고 최소 실행 prototype으로 dependency key의 충분성을 반례 검증한다.

### R3-B. 변경분 기반 재결합

R2/P3-B의 sound change report를 사용하여 dirty owner/boundary만 처리한다. additions-only가 증명된 epoch에서 delta product/union을 적용한다. deletion, privacy, authority replacement, negative footprint, cycle 변화는 영향 영역 exact recomputation을 수행한다.

ground source 삭제, privacy tightening, same-FType/different realization, function return, multi-definition seed, SCC split/merge, staging 제거를 old completed state와 checkpoint별 비교한다. time/resource budget 때문에 선택이 달라지면 speedup이 아니라 failure다.

### R4. outer epoch topology 공유

topology skeleton과 root pin/eligibility/support/public proof를 분리한다. node/physical/reaching edge/privacy/local fact/witness read set이 동일할 때만 immutable skeleton을 analysis-local로 재사용한다. resolver-local handle은 재인덱싱하거나 안정 identity로 검증한다.

cache on/off, zero budget, eviction, dead negative footprint, root pin, cycle, local fact/edge/shape/privacy/witness 변화 parity를 확인한다. hit율이 아니라 절약 wall과 retained memory로 채택한다.

### R5-A. selection·authority·ordering reference

legacy flat 모델을 finite reference로 유지하고 다음을 exact contract로 고정한다.

- logical Alternative ordinal/order와 cardinality
- source/owner/epoch authority
- hard factor와 canonical objective raw bits
- receipt/rank 및 canonical lexical ordering
- seed assignment, hard-conflict block, selected witness/relocation, tie-break

raw product ordinal을 legacy Alternative ordinal로 간주하지 않는다. 시간 제한을 제거한 test-only reference와 production time/resource 설정의 두 비교를 분리한다.

### R5-B. factor-aware cost/optimization interface

route/choice에서 compatibility와 cost를 직접 조회하는 prototype을 만든다. shared producer selection, relocation action 공유 비용, witness, boundary dependency, correlated alternatives를 separator state에 유지한다. 모든 leaf를 순회하는 thin wrapper는 완료로 인정하지 않는다.

작은 finite universe에서 old/new feasible assignment 양방향 equality와 모든 assignment의 hard factor/objective bits를 비교한다. 압축되지 않는 입력도 후보를 줄이지 않아야 한다.

### R5-C. selected-only receipt와 canonical rank

선택된 support path만 owner-bound receipt로 materialize하고 검증하는 production prototype을 만든다. `ensureRanks()`와 global flat ordering 요구를 함께 해결한다. 비선택 leaf full export/receipt 생성이 production path에서 0인지 확인한다.

canonical rank를 전개 없이 보존하지 못하면 exact path를 사용하고 해당 비용을 측정한다. 시간 예산을 줄이거나 다른 선택을 허용하여 통과시키지 않는다. 이 때문에 성능 gate를 못 넘으면 `REJECTED_WITH_EVIDENCE` 또는 `BLOCKED_INCOMPLETE`다.

### R6. 최종 검증과 보고

R6는 60초 확인으로 끝나지 않는다.

1. targeted regression
2. FULL/DELTA/SHADOW
3. finite independent oracle
4. representative workload 및 full canonical snapshot
5. compile/package/static/diff/branch inventory
6. actual GLM runtime output/semantic tolerance/audit
7. source/JAR/stage 동결
8. preregistered 18-pair holdout
9. B0와 last accepted parent에 대한 최종 비교 및 가능한 leave-one-out
10. 실패 campaign을 포함한 결과 공개

## 8. 정확성 불변식과 금지 사항

다음 불변식은 성능 때문에 완화하지 않는다.

- finite feasible assignment의 양방향 equality
- candidate/fact/realization/support 관계의 missing 0, illegal extra 0
- canonical cost IEEE raw bits 동일
- Alternative, clause, binding, fact, receipt의 안정 순서와 ordinal 동일
- occurrence, owner, relation, epoch authority 동일; foreign receipt 거부
- exact source, witness, relocation/action identity 동일
- seed, selection, tie-break, selected receipt와 rank 동일
- FULL/DELTA/SHADOW와 completed-state checkpoint 동일
- privacy, TR/TW, recompile, publication barrier, final boundary verification 동일
- timeout/resource/gap 종료 사유와 실제 선택 동일

특히 같은 time budget에서 빨라진 구현이 더 많은 탐색을 수행하여 다른 결과를 선택하면 기존 계약에서는 **실패**다. budget/gap을 완화하거나 “더 좋은 결과”라는 이유로 받아들이지 않는다.

금지 사항:

- candidate cap, sampling, 첫 proof만 유지
- privacy 완화, TR/TW 변경, recompile 규칙 변경
- runtime fallback 또는 실패 시 다른 planner로 전환
- CPU/heap/worker 증가로 알고리즘 speedup 대체
- source identity가 다른 JAR/target 공유
- 빠른 invocation/run만 cherry-pick
- timeout/OOM/failure를 표본에서 조용히 제외

memory gate는 같은 heap에서 process RSS 및 cgroup peak ratio of medians `<=1.05`다. allocation, live heap, retained heap은 별도로 보고한다.

## 9. 실행 순서와 bundle

```text
M0  B0/C0 provenance + Tplanning_full_initial boundary lock
M1  P1 inventory/prototypes + R1-A/R1-B
M2  P2 inventory + existing P5/R2 exact no-op re-evaluation
M3  P3-B + R2 change reporting + R3-A/B dependency bundle
M4  P3-A + R4 topology lifecycle bundle
M5  P4 + R5-A/B/C factorized consumer bundle
M6  integrated accepted set + leave-one-out
M7  frozen holdout R6
```

- M1의 독립 항목은 각각 단독 이득을 요구한다.
- M3와 M5는 semantics dependency 때문에 묶을 수 있지만 각 내부 항목의 implementation coverage와 correctness 결과는 따로 기록한다.
- 앞 bundle이 느려서 거절되어도 뒤 항목을 자동 생략하지 않는다. 뒤 prototype이 필요한 interface를 자체 branch/worktree에서 구현하여 평가한다.
- dependency 때문에 parent patch가 필요하지만 parent 자체가 느리다면 combined bundle이 B0보다 빨라야 하며, parent는 독립 채택으로 세지 않는다.
- C0의 P5/R1/R2는 출발점이 아니라 재평가 대상이다. B0보다 느린 C0 전체를 parent로 고정하지 않는다.

## 10. 측정 campaign

### 10.1 pilot

- 논리 bundle당 3쌍
- profiler off, fresh JVM, sequential execution
- 동일 Docker image, CPU quota/affinity, JDK, heap/GC, worker/data/privacy/config
- pair/block마다 B0와 candidate source/JAR/manifest 검증
- drift, thermal/load contamination을 사전 규칙으로만 무효화하고 pair 전체를 보존
- pilot은 최종 18-pair 표본에 재사용하지 않음

### 10.2 최종 holdout

최종 F를 고른 뒤 새로운 stage와 미사용 run id로 B0/F와 P/F를 각각 사전 등록한다. P는 F 직전의 마지막 채택 parent다.

- 비교별 18 pair, 총 36개 실행
- 비교별 AB 9, BA 9
- fresh JVM, profiler off, sequential, fixed resources
- exact command, source/JAR/config/data hash
- early stop 없음
- 실행 중 표본 수, gate, metric boundary 변경 없음
- 모든 failure/OOM/timeout 포함

같은 run에서 `Tplanning_full_initial`, 모든 invocation의 `CandidateE2E`, phase partition, process/cgroup memory, correctness signature를 수집한다. B0/F와 P/F는 모두 동일한 18-pair·AB/BA 9/9·fresh holdout 규칙을 사용하며, 두 planning metric 모두 §4.1 gate를 통과해야 한다. 한 비교의 유리한 표본을 다른 비교에 대체하지 않는다. F와 P의 source tree와 JAR가 동일할 때만 P/F를 identity `N/A`로 생략할 수 있으며 그 identity를 manifest로 증명한다.

최종 holdout이 실패하면:

1. 실패 campaign과 preregistration을 그대로 보존·보고한다.
2. 원인을 수정한 새 후보는 새 version/stage/run id를 사용한다.
3. 새 campaign을 다시 사전 등록한다.
4. 이전 실패를 삭제하거나 새 성공 표본과 섞지 않는다.

## 11. 최종 판정표 template

### 11.1 역사적 참고값 — 새 final ratio에 재사용 금지

| metric | historical B0 | historical C0 `49a313...` | historical C0/B0 | 용도 |
|---|---:|---:|---:|---|
| `CandidateE2E` median | `36.632751초` | `43.153788초` | `1.178011` | 기존 회귀 확인만 |
| `CandidateE2E` max | `42.125914초` | `45.866749초` | — | 기존 60초 관측만 |
| CandidateE2E 10x `r_(13)` | — | — | `1.181232` | 기존 10배 FAIL 기록만 |

위 값은 과거 campaign 결과다. 새 F의 `F/B0`, `F/P`, 60초 또는 10배 판정의 분모·표본으로 재사용하지 않는다. `Tplanning_full_initial`의 역사적 B0/C0 값은 측정되지 않았으므로 `36.633/43.154초`를 그 metric에 복사하지 않는다.

### 11.2 fresh contemporaneous holdout template

| metric | fresh B0 | fresh parent P | fresh final F | F/B0 | F/P | gate |
|---|---:|---:|---:|---:|---:|---|
| `Tplanning_full_initial` min | TBD | TBD/N/A | TBD | 정보 | 정보/N/A | 정보 |
| `Tplanning_full_initial` median | TBD | TBD/N/A | TBD | TBD | TBD/N/A | 두 ratio `<=0.98` |
| full-planning paired `r_(13)` | contemporaneous pairs | contemporaneous pairs/N/A | — | TBD | TBD/N/A | 두 ratio `<1.00` |
| `CandidateE2E` median | TBD | TBD/N/A | TBD | TBD | TBD/N/A | 두 ratio `<=0.98` |
| CandidateE2E paired `r_(13)` | contemporaneous pairs | contemporaneous pairs/N/A | — | TBD | TBD/N/A | 두 ratio `<1.00` |
| CandidateE2E max | TBD | TBD/N/A | TBD | — | — | 모든 F `<=60초` |
| CandidateE2E 10x `r_(13)` | contemporaneous pairs | — | — | TBD | — | `<=0.10` 별도 |
| process RSS median | TBD | TBD/N/A | TBD | TBD | TBD/N/A | ratio `<=1.05` |
| cgroup peak median | TBD | TBD/N/A | TBD | TBD | TBD/N/A | ratio `<=1.05` |
| actual GLM `Tjob` cold/warm | 별도 | 별도/N/A | TBD | 비교 가능 시만 | 비교 가능 시만 | semantic gate |

F=P identity인 경우에만 P 열과 F/P를 `N/A`로 쓰며 source tree/JAR equality evidence를 첨부한다.

### 11.3 implementation coverage와 production selection

| item | implemented/prototyped | exact correctness | B0 pilot | parent pilot | production decision | evidence |
|---|---|---|---|---|---|---|
| P1-1..4 | TBD | TBD | TBD | TBD | TBD | artifact |
| P2-1..4 | TBD | TBD | TBD | TBD | TBD | artifact |
| P3-A/B | TBD | TBD | TBD | TBD | TBD | artifact |
| P4 | TBD | TBD | TBD | TBD | TBD | artifact |
| R1-A/B | TBD | TBD | TBD | TBD | TBD | artifact |
| R2-1..5 | TBD | TBD | TBD | TBD | TBD | artifact |
| R3-A/B | TBD | TBD | TBD | TBD | TBD | artifact |
| R4 | TBD | TBD | TBD | TBD | TBD | artifact |
| R5-A/B/C | TBD | TBD | TBD | TBD | TBD | artifact |
| R6 | TBD | TBD | 18-pair | final parent | TBD | final report |

## 12. 실패 처리와 위험

| 위험/결과 | 처리 |
|---|---|
| correctness mismatch | 첫 divergent owner/relation/checkpoint와 full reference 저장; 수정 전 채택 금지 |
| work 감소, wall 개선 없음 | hash/index/allocation/GC/retention 또는 다른 phase 이동을 한 번 좁혀 수정; 재실패 시 거절 |
| build만 개선 | `Tplanning_full_initial`의 model/optimizer/rank/application 증가 확인; full planning이 안 빨라지면 거절 |
| 3-pair pilot만 개선 | provisional 상태; final holdout 전 production 성공 주장 금지 |
| pilot `INCONCLUSIVE` | 채택하지 않음; 사전 고정 follow-up으로 해소하거나 `REJECTED_WITH_EVIDENCE`로 종료 |
| holdout 실패 | failure 보존, 새 version과 새 campaign; optional stopping 금지 |
| memory `>1.05` | lifetime/budget/retention 수정; heap 증가로 통과 금지 |
| time budget 아래 selection mismatch | correctness failure; budget 또는 parity 계약 완화 금지 |
| B0 provenance 불명 | official control로만 표현; pristine baseline 주장 금지 |
| 716 workload 재구성 실패 | `HISTORICAL_NONCOMPARABLE`; 현재 metric의 분모로 사용 금지 |
| 한 항목 구현/실행 차단 | `BLOCKED_INCOMPLETE`; 전체 완료 주장 금지 |

주요 기술 위험은 stale dependency, negative footprint 누락, SCC split/merge 오류, correlated relation 소실, owner/epoch authority 위조, canonical ordering 변화, cache retained memory, 비용을 downstream으로 이동하는 가짜 개선이다.

## 13. 산출물과 보고 원칙

각 bundle은 다음을 immutable artifact로 남긴다.

- baseline/parent/candidate commit, tree, source diff, JAR hash
- image/JDK/heap/GC/CPU/worker/data/privacy/config hash
- exact command와 AB/BA/block order
- correctness oracle diff와 selection/cost/order/authority fingerprints
- raw timing TSV/JSON, phase partition, RSS/cgroup, exit status
- failure/OOM/timeout log
- 채택/거절 판정

최종 보고서는 다음 문장을 서로 바꾸어 쓰지 않는다.

- “항목을 구현/prototype하고 검증했다.”
- “항목을 production에 채택했다.”
- “B0보다 빨랐다.”
- “60초 이내였다.”
- “10배를 달성했다.”
- “전체 GLM runtime이 빨라졌다.”

각 주장은 해당 metric과 gate의 증거가 있을 때만 쓴다.

## 14. 실행 체크리스트

- [ ] B0/C0 ancestry, diff, source/JAR/harness provenance 확인
- [ ] B0를 pristine/unoptimized라고 부를 수 있는지 판정
- [ ] 원본 GLM full initial planning의 start/end와 invocation count 고정
- [ ] 716.592초 역사 workload의 input/config/hash 재구성 또는 non-comparable 판정
- [ ] P1 네 항목 구현/prototype·정확성·pilot·판정
- [ ] P2 네 항목 구현/prototype·정확성·pilot·판정
- [ ] P3-A/B 구현/prototype·정확성·pilot·판정
- [ ] P4와 R5 consumer bundle 구현/prototype·정확성·pilot·판정
- [ ] 기존 P5/R1/R2 변경을 B0 대비 재평가
- [ ] R1-A/B, R2 다섯 항목, R3-A/B, R4, R5-A/B/C 전수 판정
- [ ] 모든 accepted bundle의 누적/leave-one-out 검증
- [ ] fixed heap memory ratio `<=1.05`
- [ ] final F와 stage 동결
- [ ] B0/F 및 P/F 각각 fresh 18-pair, AB/BA 9/9, no early stop holdout; F=P identity만 P/F `N/A`
- [ ] full planning과 CandidateE2E 모두에서 B0/F 및 P/F `r_(13)<1`, median ratio `<=0.98` 판정
- [ ] 모든 final CandidateE2E `<=60초` 별도 판정
- [ ] CandidateE2E historical 10x `r_(13)<=0.10` 별도 판정
- [ ] actual GLM runtime 및 dynamic recompile 별도 보고
- [ ] implementation coverage와 accepted production set 분리
- [ ] `BLOCKED_INCOMPLETE`가 있으면 전체 완료로 표시하지 않음

## 15. 로컬 근거

- `docs/G009_CURRENT_ALGORITHM_EXPLAINED_2026-09-20.md` §5–§13: fixed point, proof, factorization, DP consumer, diagnostic/production timing 경계
- `docs/G009_SEARCH_SPACE_COMPLEXITY_OPTIMIZATION_PLAN_2026-09-19.md` §4–§8: 기존 P1–P4 구현 항목과 검증 계약
- `docs/G009_CANDIDATE_E2E_TENTH_PLAN_2026-09-19.md` §3–§7: CandidateE2E, P1–P5, 18-pair order-statistic gate
- `docs/G009_REVISED_60S_OPTIMIZATION_PLAN_2026-09-20.md` §4–§6: R1–R6 설계와 실패 처리
- `docs/G009_REVISED_60S_OPTIMIZATION_FINAL_REPORT_2026-09-20.md` §1–§6: B0/C0 공식 결과, 미구현 NO-ENTRY, rollback, runtime 분리
- `docs/G009_UNIFIED_WORKSPACE_REPORT_2026-09-19.md` §7–§9: 716.592초 host diagnostic과 과거 실패 실험
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateFormationTiming.java`: 기존 CandidateE2E exclusive timing contract
- `src/main/java/org/apache/sysds/parser/DMLTranslator.java`: final-hop planning invocation과 receipt handoff 경계
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`: closure/physical/CFG 반복 경계
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateClosureDependencies.java`: revision/change classification
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSupportRelation.java`: relation union/equality/subset/export
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java`: topology, proof, memo, revision lifecycle
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java`: DP owner scan과 alternative 전개
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java`: seed, budget, selection order

이 계획은 모든 설계를 production에 무조건 누적하겠다는 뜻이 아니다. **모든 설계를 실제로 구현 또는 실행 가능한 prototype으로 평가하고, 정확하면서 B0보다 빨라진 것만 production에 채택한다**는 뜻이다. 증거가 없는 생략도, 60초만 보고 멈추는 종료도 허용하지 않는다.
