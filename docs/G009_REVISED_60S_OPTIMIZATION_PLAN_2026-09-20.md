# G009 개선 계획 개정판 — 반복 재구축과 평탄화 경계부터 제거

작성일: 2026-09-20. 상태: **계획 작성; 이번 요청에서 구현·새 실험 없음**.

기준: HEAD `e29f4fc30d19cc228b013e786d76424cb7954d7e`와 기존 미커밋 P5 작업 트리를 설명한 [현재 알고리즘 해설](G009_CURRENT_ALGORITHM_EXPLAINED_2026-09-20.md).

## 1. 결론: 무엇을 바꾸는 계획인가?

이전의 “closure → topology → 나머지 비용을 각각 줄인다”는 제안은 병목 이름을 구현 계획으로 착각할 위험이 있었다. 다음처럼 바꾼다.

> **이미 계산한 관계를 유지하고 변경을 발생 지점에서 전달한다. 내부 비교를 위해 압축 관계를 다시 펼치지 않는다. DP 소비까지 같은 원칙을 적용하되, 선택 결과·권한·비용을 보존한다.**

우선순위는 다음과 같다.

1. 기존 미커밋 변경의 검증 경계 정리와 최소 production E2E 확인.
2. 확실한 전체 스캔 중복 제거: DP의 owner grouping, closure의 반복 index 준비.
3. 변경되지 않은 relation의 재생성·비교·flat export 제거.
4. physical rebuild가 native proof binding을 지우는 결합 구조 개선.
5. outer epoch까지 안전한 topology 재사용 확대.
6. 실제 병목/예산 판정에 따라 **DP consumer의 압축 표현 소비를 완성**.
7. 최종 고정 후보로 Docker E2E 60초 및 기존 10배 목표를 각각 검증.

2–5는 서로 다른 비용을 제거하며, 6은 일부가 독립적이지만 interface 변경 위험이 크다. 모두가 반드시 큰 속도 향상을 낸다고 가정하지 않는다.

## 2. 이번 정리에서 새로 알았거나 정정한 것

**코드로 확인된 사실**과 **성능 가설**을 구분한다. 소스 경로/메서드/행은 §9의 E1–E9를 참조한다.

| 발견 | 이전 판단의 문제 | 개정 결정 | 근거 |
|---|---|---|---|
| P3는 owner/reader 선택 delta이며 내부 전체 준비·비교가 남음 | pass 수만 줄이면 충분하다고 보기 쉬움 | dirty owner queue보다 먼저/함께 index 수명과 change detection 개선 | E1/E2 |
| physical rebuild가 base emission을 복원하면서 native binding을 잃음 | 반복 grounding을 독립적인 비효율로만 취급 | base capability와 derived support의 갱신 경계를 분리하되 completed transfer 의미 유지 | E1 |
| relation equality/subset의 구조 비교 실패가 전체 export를 호출 | factorization 후 leaf 비용이 사라졌다고 봄 | representation 유지와 정확한 no-change 판정을 최우선 대상으로 추가 | E3 |
| DP model이 모든 supportClauses와 input-authority 조합을 펼침 | P4가 DP 끝까지 완성됐다는 표현은 잘못 | analysis 최적화와 consumer 완성을 분리; 미완료를 명시 | E4 |
| DP에서 각 node마다 전체 facts를 스캔 | 245초 build 진단으로는 보이지 않음 | owner index를 독립적 작은 첫 패치로 배치 | E4 |
| receipt는 lazy지만 group은 eager, rank는 global flat 경계 | decoder만 바꾸면 selected-only가 된다고 보기 쉬움 | owner/epoch/legacy ordering/rank까지 consumer 설계 계약에 포함 | E5 |
| DP는 제한된 incremental regional 경로 | feasible domain만 같으면 선택도 같다고 보기 쉬움 | 동일 설정의 old/new 실제 선택·tie-break parity도 별도 요구 | E6 |
| P5a는 pass 수가 아니라 pass당 비용을 줄임; timer 위치도 변경 | 12.18% 전부를 production 알고리즘 가속으로 주장할 수 없음 | profiler-off Docker 측정으로 production 효과 분리 | E7 |
| JSON 누적 카운터는 export 뒤 채집 | receipt 생성 수를 build 안의 작업으로 오인 | 기존 build 끝/export 끝 snapshot을 구분하는 최소 경계 보정 | E7 |

**아직 모르는 것:** relation comparison fallback의 실제 시간, production DP/model/rank 시간, 최신 hash cache의 순수 효과와 retained memory. 이 값을 추정으로 채워 60초 달성을 약속하지 않는다.

## 3. 목표와 비목표

### 목표

- 사용자 목표는 **production `TcandE2E ≤ 60초`**로 해석한다. build-only 60초로 바꿔 성공을 선언하지 않는다.
- 기존 1/10 목표는 삭제하지 않는다. absolute 60초와 paired 10배 ratio를 **별도 PASS/OPEN**으로 보고한다.
- 고정 입력·정책·환경에서 합법 후보의 관계, 정확한 owner/root/witness, 비용, 선택과 tie-break 계약을 보존한다.
- 구조적 개선과 instrumentation overhead 제거를 분리한다.

### 비목표/금지

- candidate cap, sampling, 첫 proof만 남김, 연산별 편의 가드, privacy 완화, runtime fallback, TR/TW 및 recompile 규칙 변경 없음.
- CPU/heap/worker 증가나 optimizer gap/time/resource 설정 완화로 얻은 시간을 이 개선의 성과로 인정하지 않음.
- 모든 cache를 무조건 키우지 않음. 새 dependency 도입 없음.
- 전체 closure를 한 번에 새 solver로 교체하지 않음. cyclic semantics를 Boolean LFP로 치환하지 않음.
- 이번 계획 작성으로 기존 durable goal/ledger의 진행 상태를 완료 처리하지 않음.

### 수학적으로 필요한 개선

마지막 host 진단의 build는 245.047초이고 closure 125.723초, topology 65.822초, 나머지 약 53.502초다(E7).

```text
Tbuild / 60 = 4.0841
필요한 build 감소율 = 1 - 60/245.047 = 약 75.51%

나머지 53.502초를 고정하면:
closure + topology 허용량 = 60 - 53.502 = 6.498초
두 영역에 필요한 합산 가속 = 191.545 / 6.498 = 약 29.48배
```

따라서 closure/topology만으로 해결한다는 주장은 근거가 약하다. 게다가 production에서는 다음 항이 더 있다.

```text
TcandE2E = Tcommon + Tanalysis + Tmodel + Tcost
         + Toptimizer + Tselection/application/final-handoff
```

실제 exclusive production 계측을 사용하며, host Tbuild를 Tanalysis의 정확한 대체로 간주하지 않는다. 기존 “20초 + 10초 + 20초 + 10초”는 예측도 입증된 예산도 아니므로 고정 실행 예산에서 제외한다. 단계별 허용량은 실제 나머지 시간에서 계산한다.

## 4. 실행 단계와 검증 가능한 완료 조건

새 단계 이름은 **R0–R6**로 한다. 원래 P5 최종 qualification과 비공식 P5a 패치를 혼동하지 않기 위해서다.

### R0. 기존 변경을 구분하고 최소 기준선 확보

**목적:** 새로운 대규모 계측 작업이 아니라, 어떤 소스를 무엇과 비교하는지 고정한다.

- HEAD P4, 완료 진단 P5a, 후속 미측정 hash diff를 구분한다. P5a의 exact source manifest가 없으면 재현됐다고 쓰지 않는다.
- 미커밋 변경은 기능별 diff로 검토하고 focused regression을 통과한 범위만 checkpoint한다. 미검증 hash cache를 baseline의 성공한 변경처럼 취급하지 않는다.
- 기존 `CandidateFormationTiming`, P0 artifact/runner를 먼저 사용한다. build 끝과 export 끝 counters가 구분되도록 evaluator의 기존 snapshot 위치만 보완한다. hot path에 매 호출 타이머를 추가하지 않는다.
- profiler-off, fresh JVM, 동일 Docker 설정의 baseline/candidate pilot을 확보한다. 최종 통계 gate는 아직 실행하지 않는다.
- 기존 `qualify-glm`와 GLM runner/validator/allowlist가 있으므로 harness를 처음부터 개발하지 않는다. 조사된 기존 immutable stage는 P0 `a5df779706`에 묶여 있으므로 **현재 검증된 revision에 맞는 clean source/JAR/stage를 확정**한다. Docker 가용성/실행 성공은 아직 별도 확인 대상이다. 검증 불가 시 공식 E2E는 OPEN으로 남기고 R1–R3의 로컬 정확성 작업을 진행한다(E8).

**수정/검토 범위:** E7/E8, 기존 P5 diff의 `PlacementIdentity`, `NativeProofProduct`, `CandidateSupportRelation`, 관련 테스트.

**완료 기준:** source/JAR/config/heap/JDK/worker/data/privacy와 실제 커맨드가 기록됨; phase 합이 total과 맞음; export counters 분리 회귀 통과; 미측정 변경 목록 명시; Docker 결과 또는 정확한 harness blocker 기록.

**계측 상한 원칙:** 새로운 dashboard/일반 profiler framework/매 패치 full-GLM 재실행을 만들지 않는다. 병목이 불분명한 구조 변경에만 짧은 sampling이나 한 개의 boundary counter를 붙인다. 측정이 어떤 구현 선택을 바꾸는지 설명할 수 없으면 추가하지 않는다.

### R1. 전체 스캔과 index 준비를 없애는 독립 패치

#### R1-A: DP owner grouping

- `ExactPhysicalModel.build`에서 ordered facts를 owner identity별로 한 번 묶어 `alternatives`에 전달한다.
- 기존 facts/emission/realization/support 순서를 보존한다. synthetic boundary/source/action 대안 처리도 변경하지 않는다.
- 목표 스캔 규모: `Ndecision × F → F + Ndecision`의 grouping/lookup. 실제 Alternative 생성량은 이 변경으로 줄지 않는다.

#### R1-B: closure index 수명

- 현재 P5에서 이미 이동한 정적 index는 재구현하지 않는다.
- 남은 가변 native/executable-reference index 및 dependency index를 실제 갱신 owner 단위로 유지할 수 있는지 검토한다.
- index마다 읽는 입력과 유효 epoch를 명시한다. 삭제/교체 시 stale entry를 반드시 제거하며 full snapshot rebuild와 비교한다.
- 우선 한 index만 바꾸고, 새 orchestration/cache framework를 도입하지 않는다.

**범위:** E1/E2/E4, `ExactPhysicalModel`, `NeutralPlacementGraphBuilder`, `CandidateClosureDependencies`, 기존 model/fixed-point 테스트.

**완료 기준:** old/new Alternative 순서·signature·source ownership 동일; FULL/DELTA/SHADOW 동일; 0/1/10/100% dirty fixture에서 index 조회 결과 동일; owner grouping scan은 한 번; 패치 전후 work counter 및 동일 경계 elapsed/RSS 보고.

**채택 기준:** 작업량 감소만으로 speedup을 주장하지 않는다. 작은 유한 회귀와 동일 환경 pilot에서 no-regression 확인; 유의미한 시간 효과가 없는 복잡한 index 유지 변경은 단순화를 우선한다.

### R2. Relation을 재생성하지 않고 변경을 정확하게 전달

**핵심 가설:** 압축 구조를 반복 재생성하여 구조 비교가 실패하면, 고정점 확인이 full decoder를 깨우는 비용을 낼 수 있다(E3). 그 발생 비중은 아직 미측정이다.

1. immutable route/atom을 그대로 재사용하고, 연산이 exact no-op이면 **기존 relation 객체**를 반환한다.
2. union/filter/binding 연산에서 이미 판정한 no-change/positive-change 정보를 기존 revision 처리에 전달한다. 처음에는 full 비교와 SHADOW로 병행한다.
3. equivalence/subset 캐시는 필요하다면 analysis/epoch-local, 명시적 유한 budget으로 둔다. collision은 실제 equality로 확인한다.
4. 표현이 다른 relation 사이의 외연 equality/subset은 기존 exact 경로를 보존한다. hash/handle 차이나 route 개수 차이를 곧바로 의미 차이라고 판정하지 않는다.
5. new relation 객체가 생겼다고 foreign clause/receipt ownership을 이어받지 않는다. 재사용은 owner/epoch 계약 안에서만 허용한다.

**하지 않을 것:** 임의의 correlated OR-of-products를 항상 싸게 canonicalize할 수 있다고 가정하지 않는다. 모든 fallback 삭제를 완료 조건으로 삼지 않는다. `structural mismatch → unequal`이라는 오답 지름길도 금지한다.

**범위:** E3/E5, `CandidateSupportRelation`, `NativeProofProduct`, `CandidateClosureDependencies`, `LogicalBoundaryRealizations`.

**완료 기준:**

- identical/no-op fixture에서 반복 export 0회 및 기존 relation 재사용.
- flat vs factorized, 겹치는 OR, 불완전 grid, repeated input occurrence, deferred proof metadata가 다른 fixture의 exact equality/subset가 reference와 동일.
- 0% semantic change에서 새 dirty owner가 발생하지 않음.
- positive/deletion/replacement 보고와 full classifier가 각 checkpoint에서 동일.
- aggregate fallback count와 boundary 소요 시간으로 원래 가설을 확인/기각. fallback이 미미하면 R2를 더 확장하지 않고 R3로 이동.

### R3. Physical rebuild와 proof authority의 수명 분리

**핵심 변경:** 현재 물리 재구축이 native binding을 초기화해 재증명하는 구조를 좁힌다. 단순히 두 번째 grounding 호출을 삭제하는 작업이 아니다(E1).

#### R3-A: 안전한 보존 fast path

- base oracle 결과와 derived support의 의존 조건을 명시한다: rule input domain, capability/shape/profile, privacy, exact source/witness, relocation/action identity.
- 이 조건과 dependency epoch가 모두 그대로인 owner는 기존 exact realization/support를 유지한다.
- 하나라도 의미 있게 바뀌면 현재 completed physical→grounding→CFG replay 경로로 정확히 재계산한다.

#### R3-B: 변경분 기반 재결합

- R2의 sound change reporting을 이용해 실제 바뀐 owner와 boundary만 큐에 넣는다.
- additions-only이며 안전하다고 증명된 operator/epoch에서만 delta product/union을 적용한다.
- 삭제·privacy·authority 교체·negative footprint·cycle 변경은 별도 exact recomputation 경로를 유지한다.
- publication 안정성은 여전히 완성된 상태에서 확인한다. 모든 original transfer의 결과가 같다는 근거 없이 outer semantic/publication pass를 합치거나 제거하지 않는다.

**범위:** E1/E2/E9, `NeutralPlacementGraphBuilder`, `CandidateClosureDependencies`, `LogicalBoundaryRealizations`, `NativePlacementContinuity`의 revision 연결부.

**완료 기준:**

- no-change rebuild fixture에서 exact authority 보존, 불필요한 두 번째 grounding 0회.
- 변화가 있는 fixture에서 old completed-state와 매 checkpoint의 node/domain/fact/logical relation/action equality.
- pendingPhysical delta 누락 없음; multi-definition loop seeds와 staging 제거 결과 동일.
- ground source 삭제, privacy tightening, same-FType 다른 realization, function return 변경, SCC split/merge에 대한 full reference parity.
- no-change/희소변화에서 실제 rebuild/grounding 작업량 감소; full-change에서도 결과/종료 동작 동일.

**위험:** 가장 큰 의미 변경이다. finite fixture→LM/대표 사례→GLM 순으로 확대한다. 기존 전체 재계산 경로는 transition oracle로 유지하되 production runtime fallback을 추가하지 않는다.

### R4. Topology를 안전한 범위에서 outer epoch 간 공유

R3와 관련되지만 별개의 패치다. P5a는 `nextRevision` 내부 재사용을 늘렸으나 outer/physical pass가 새 resolver를 만드는 경계는 남는다(E1/E9).

- topology skeleton과 query root pin/eligibility/support answer를 구별한다.
- 노드·physical/reaching edges·privacy·local facts·witness가 요구하는 조건이 같을 때만 analysis-local immutable skeleton을 재사용한다.
- resolver-local integer handle을 그대로 이식하지 않는다. 새 resolver로 옮길 때 재인덱싱하거나 기존 안정 identity 계약을 사용한다.
- 죽은/dead branch의 negative footprint도 보존한다. support answer와 public proof의 공유 범위를 topology와 함께 넓히지 않는다.
- 유한 budget을 두고 eviction/zero-budget에서도 동일 결과를 계산한다. cache 부족을 후보 거절로 바꾸지 않는다.

**범위:** E9, `NativePlacementContinuity`, `NeutralPlacementGraphBuilder` resolver 생성 경계, 관련 continuity tests.

**완료 기준:** 동일 skeleton outer 재진입 시 build 재사용; local fact/edge/shape/privacy/witness 변경 시 정확한 재구축; cache-on/off/eviction/root-pin/cycle parity; CPU 시간과 peak retained memory 모두 보고.

**진입/중단 판단:** R3 이후 topology 비용이 이미 작거나 공유 유지비가 이득을 상쇄하면 범위를 확대하지 않는다. hit율만으로 채택하지 않는다.

### R5. DP consumer의 압축 관계 소비를 완성 — 별도 설계 게이트

**현재 미완료인 핵심 구간이다.** 단순히 list를 iterator로 바꾸어도 모든 alternative를 방문하면 계산량은 그대로다(E4/E5/E6).

#### R5-A: 선택·권한·순서의 reference 고정

- legacy flat 모델을 finite reference로 유지한다.
- 새 표현에서 논리적 Alternative의 ordinal/order, source authority, canonical cost, receipt/rank가 무엇인지 명시한다.
- 기존 변수/대안 ordering을 가능한 한 유지한다. 보조 변수를 도입하는 re-encoding은 기본안이 아니라 별도 검토안이다.
- raw product ordinal은 legacy Alternative ordinal이 아니다. decoder의 dedupe, canonical export의 정렬, 모델의 signature dedupe/sort를 거친 **최종 domain 순서**와 cardinality를 동일하게 재현해야 한다.
- 기존 ordinal을 보존하는 lazy 접근 단계를 먼저 검증할 수 있지만, 현재 local optimizer는 state-key 검증과 seed 선택에서 전체 domain을 방문한다. 이 단계의 성과는 allocation/retention 개선일 수 있으며 leaf evaluation 제거로 보고하지 않는다.

#### R5-B: Factor-aware 조회와 최적화 인터페이스

- 기존 relation의 route/choice 정보를 직접 읽는 compatibility·cost 조회를 우선 구현한다.
- shared producer 선택, relocation action 공유, witness 및 boundary dependency를 유지한다. 입력 slot별 독립 최저 비용을 더하는 식으로 correlated 관계를 지우지 않는다.
- 기존 지역 최적화가 요구하는 조건부 평가에 대해 압축 관계를 직접 다룰 수 있는 범위를 작은 fixture에서 증명한다.
- 여전히 flat 전개가 필요한 경로는 숨기지 않고 측정한다. 새 API가 모든 leaf를 순회하는 thin wrapper라면 R5 완료가 아니다.

#### R5-C: Selected-only receipt와 canonical rank

- 선택된 support path만 analysis-owned choice로 decode하고 검증한다.
- `ensureRanks()`와 전역 flat ordering 요구를 함께 다룬다. 단순 lazy receipt 생성만으로 완료 처리하지 않는다.
- canonical rank/order를 전개 없이 정확히 보존할 수 없는 표현은 기존 exact 경로를 사용한다. 그 때문에 예산을 넘으면 목표 미달로 남긴다.
- 외부에서 같은 값의 clause를 만들어 기존 receipt 권한을 대체하지 못하도록 owner/relation/epoch 검증을 유지한다.

**범위:** E3–E6, `ExactPhysicalModel`, `ExactPhysicalCostModel`, `LocalPhysicalOptimizer`와 regional message 접근부, `PlacementAnalysis`, `ExactPhysicalSelection`, `CandidateSelections`.

**필수 게이트:**

1. finite fixture에서 `decode(new) = decode(old)` 양방향 feasible assignment equality.
2. 모든 finite feasible assignment의 hard factors 및 canonical objective raw bits 동일.
3. 유한 fixture에서 Alternative ordinal, seed assignment, hard-conflict block, canonical cost bits, selected witness/relocation/receipt/tie-break 동일. 먼저 시간 제한을 제거한 **테스트 전용** reference로 알고리즘 의미를 대조하고, production 비교는 기존 time/resource 설정을 유지한다. feasible domain과 optimum만 같다는 이유로 실제 선택 parity를 생략하지 않음.
4. 반복되는 동일 source occurrence, shared action cost, correlated grid, dynamic/exact witness, foreign owner를 포함한 회귀 통과.
5. compressible fixture의 production 경로에서 **비선택 leaf의 전체 export와 전체 receipt 생성이 발생하지 않음**. 별도 correctness full export는 허용.
6. noncompressible fixture에서도 후보를 줄이지 않고 같은 결과. 자원/시간 한계는 명시적으로 실패/미완료로 기록.
7. build+model+optimizer+selection을 합친 E2E가 개선됨. 비용을 optimizer/receipt로 이동한 것만으로 성공 처리하지 않음.

**중요한 난점:** regional optimizer는 시간/자원 예산과 선택 순서의 영향을 받는다. 표현 변경이 탐색 경로를 바꿔 다른 feasible 해를 내면 기존 parity 조건을 충족한 것이 아니다. 예산을 줄여 빠르게 끝내거나 좋은 다른 해라고 조용히 채택하지 않는다. 최소 fixture prototype에서 이 계약을 먼저 해결하고, 실패하면 R5 설계를 재검토한다.

특히 같은 10초 예산에서도 빨라진 구현이 더 많은 merge를 수행하면 결과가 달라질 수 있다. 따라서 **동일 결과를 모든 timed 실행에서 보장할 수 있다는 가정은 하지 않는다**. TIME/RESOURCE 종료 사유·gap·탐색량을 함께 보고하고, 기존 선택 parity와 충돌하면 현재 계약에서는 미완료로 남긴다. 예산 종료 시 다른 선택도 허용하는 계약 변경은 별도 명시적 결정 사항이지 이 계획에 포함된 자동 완화가 아니다.

**착수 판단:** R0에서 consumer 비용과 flatten 규모를 확인하고 reference 설계는 일찍 시작한다. production E2E가 이미 목표를 통과하고 flat consumer 비용이 허용 범위라면 큰 R5 구현은 불필요하게 강행하지 않는다. 그렇지 않으면 analysis-only 개선으로 끝내지 않는다.

### R6. 최종 검증과 보고

- 단위 회귀→FULL/DELTA/SHADOW→finite independent oracle→대표 workload→full snapshot→compile/package/branch inventory/static checks 순으로 검증한다.
- PUBLIC skip은 기존 지침대로 별도 집계한다. global Checkstyle의 기존 위반을 수정분 성공/실패와 섞지 않는다. 실행 불가 검사는 명시한다.
- 실제 GLM runtime 결과/허용오차, no fallback, same heap에서 OOM 없음, peak RSS를 검증한다.
- 공식 성능은 기존 `run_LAN_docker.sh` 경로와 동일 환경만 사용한다. source와 JAR를 동결하고 profiler-off/fresh JVM으로 실행한다.
- pilot 최소 3쌍은 drift/correctness 진단용이고 최종 acceptance 표본에서 제외한다. 매 패치마다 최종 campaign을 반복하지 않는다.

**원래 10배 gate 유지:** 기존 계획 §5의 사전등록 18쌍/AB–BA 균형, paired ratio `r_i`, `r_(13) ≤ 0.10` 조건을 그대로 적용한다. 변경이 필요하면 최종 실행 전 별도로 명시하며 유리한 실행만 골라 표본을 줄이지 않는다.

**추가 absolute 60초 gate:** 같은 최종 사전등록 campaign의 유효 new 실행이 모두 60초 이내인지 별도 보고한다. 하나라도 초과하면 absolute gate는 미달이다. 이것은 해당 campaign의 관측된 충족이지 모든 미래 실행의 worst-case 보장은 아니다. timeout/OOM/중단을 버려 좋은 표본만 남기지 않는다.

**Memory gate:** 기존 계획의 동일 heap, container peak RSS median ≤ contemporaneous baseline의 1.05배를 유지한다. allocated bytes와 peak live/retained memory는 별도다.

**완료 보고 네 항목:** 구현/정확성, absolute 60초, paired 10배, 메모리. 하나를 통과했다고 나머지를 PASS로 쓰지 않는다.

## 5. 단계 의존성과 가장 먼저 실행할 묶음

```text
R0 최소 경계 확인 ── R1-A DP owner index ─────────────────┐
                └─ R1-B closure indexes → R2 → R3 ─────┼─ R6
                                              └─ R4 ──┤
R0 consumer 근거 → R5-A parity prototype → R5-B/C ─────┘
```

- **첫 구현 묶음:** 기존 미측정 hash 변경 검증/분리 + DP owner index + R2의 no-op relation 재사용 한 곳.
- **두 번째 묶음:** R3-A의 no-change physical rebuild 보존. 처음부터 모든 epoch를 통합하지 않는다.
- **그다음:** 남은 exclusive 비용과 consumer 실제 시간을 기준으로 R3-B/R4/R5 중 우선순위를 조정한다.
- 원래 P1/P2를 각각 benchmark하기 위해 과거 전 단계를 다시 돌리지 않는다. 현재 이후 변경을 구분할 수 있게만 한다.

각 묶음은 baseline/after의 소스와 정확성 증거가 남는 작은 diff로 유지한다. 여러 패치가 같은 비용을 줄였다고 각각의 speedup을 곱하지 않는다.

## 6. 단순 revert가 아니라 원인을 확인하는 실패 처리

| 결과 | 다음 판단 |
|---|---|
| correctness mismatch | 첫 divergent checkpoint와 owner/relation을 보존; 삭제/교체·root pin·authority 오류인지 분리. 해당 변경은 채택하지 않고 정확한 경계부터 수정 |
| work 감소, elapsed 개선 없음 | index/hash/retention/GC 또는 다른 phase로 이동한 비용을 확인. 한 번의 좁은 원인 검증 후 유지·수정·제외 결정 |
| build 개선, E2E 그대로 | model/optimizer/rank/application 증가 여부 확인; consumer 경계 개선으로 이동 |
| instrumented만 개선 | observer 효과로 기록; production speedup 주장은 하지 않음 |
| memory 증가 | bounded cache/더 짧은 lifetime/기존 객체 공유를 우선; heap 증가로 통과시키지 않음 |
| 구조 변경이 같은 선택을 보존하지 못함 | 기존 parity 계약에서 미완료. feasible domain 보존만으로 몰래 계약을 완화하지 않음 |
| GLM 또는 목표 미달 | 실패/미완료 artifact 보존 및 남은 phase 수치 보고. 완료/10배 PASS로 이름만 바꾸지 않음 |

## 7. 우선순위의 근거와 한계

- R1은 **낮은 의미 위험 + 직접 확인된 중복** 때문에 먼저 한다. 큰 초 단위 효과는 미확정이다(E1/E4).
- R2/R3은 **압축을 깨는 비교와 proof를 지우는 rebuild**를 직접 겨냥한다. 이것이 이전 계획보다 구체화된 핵심이다(E1/E3).
- R4는 전체 graph cache를 다시 크게 만드는 접근이 아니라 **정확한 lifetime 경계 수정**이다(E9).
- R5는 **원래 계획의 빠진 consumer 구간**이다. 가장 어려운 부분은 단순 decoder가 아니라 factor별 비용/권한/선택 순서 보존이다(E4–E6).
- 새로운 60초 도달 예측치는 제시하지 않는다. 현재 증거는 병목 위치와 위험을 알려주지만 남은 중복 비율과 downstream 시간까지 알려주지는 않는다(E7).

## 8. 실행 체크리스트

- [ ] P4/P5a/후속 미측정 diff 및 실제 실행 source/JAR 구분.
- [ ] build/export counters 및 production E2E 경계 확인.
- [ ] R1 작은 패치와 old/new 순서·owner parity.
- [ ] R2 no-op 경로 export 0 및 exact fallback 보존.
- [ ] R3 completed-transfer shadow와 음수/교체 변경 회귀.
- [ ] 필요 시 R4 fresh resolver/cache-off parity 및 memory gate.
- [ ] R5의 실제 선택 parity prototype 통과 후에만 대규모 consumer 구현.
- [ ] 기존 selected suite/finite oracle/snapshot/branch inventory/build/static 검증.
- [ ] 동일-Docker 고정 campaign에서 60초와 10배를 따로 판정.

## 9. 근거 목록

행 번호는 현재 작업 트리 기준; 메서드명을 함께 찾는다. 아래 근거는 현재 동작의 증거이지 proposed optimization의 완료 증거가 아니다.

| ID | 파일/위치 | 확인 내용 |
|---|---|---|
| E1 | [NeutralPlacementGraphBuilder.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java) 2511–2662 `closeCfgTransientCandidateDependenciesMeasured`, 2670–2760 direct context/binder, 4083 `closePostCfgPhysicalCandidateDependencies` | composed transfer, 새 resolver, full index 준비, 물리 재구축 후 재grounding |
| E2 | [CandidateClosureDependencies.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateClosureDependencies.java) 116–245 `revision/classify/structurallyEqual` | owner change classification, directed/conservative 범위, equality/subset |
| E3 | [CandidateSupportRelation.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSupportRelation.java) 107–135 `union/sameSupportAs/containsAllSupportOf`, 278 `exportCanonicalClauses` | 구조 fast path 및 exact flat fallback |
| E4 | [ExactPhysicalModel.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java) 149–176 `build`, 232–270 `alternatives` | node별 전체 facts scan, support/authority product 전개 |
| E5 | [PlacementAnalysis.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java) 1127–1174 receipt ownership, 1227–1275 group/rank | group eager, clause/receipt lazy, canonical global rank export |
| E6 | [LocalPhysicalOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java) 60–139, [IncrementalRegionalOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java) 46–58/120–222/345–360, [LocalCategoricalOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java) 185–188/828–846/1146–1152 | seed/regional search, time/resource/gap, canonical objective, ordinal tie-break, 전체 domain 방문 |
| E7 | [G009PlanningPerformanceEvaluatorTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/placement/G009PlanningPerformanceEvaluatorTest.java) 31–58, [해설 §11](G009_CURRENT_ALGORITHM_EXPLAINED_2026-09-20.md) | build/export/metrics 순서와 보존된 P4/P5a 수치 |
| E8 | [P0 프로토콜](G009_P0_BASELINE_PROTOCOL_2026-09-19.md), [기존 계획 §3/5](G009_CANDIDATE_E2E_TENTH_PLAN_2026-09-19.md), [CandidateFormationTiming.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateFormationTiming.java), [run_g009_p0_baseline.sh](../scripts/fedplanner/run_g009_p0_baseline.sh) 118–135 | 기존 production 경계·실행 증거·통계/memory gate와 qualify-glm |
| E9 | [NativePlacementContinuity.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java) 256–321 `nextRevision`, 706–956 direct evaluation/summary, 1383–1482 topology; [PlacementIdentity.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java) 992–1024 | topology/support/public memo의 상이한 수명, root footprint, 후속 hash cache |

P5a 원자료: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-p5-closure-diagnostic-20260920-r8/metrics.json`.

기존 GLM harness 참조(현재 candidate의 실행 성공을 의미하지 않음): `/home/mchoi/g009-p0-glm-harness-work-20260919/experiments/run_g009_glm_p0.sh`, 같은 디렉터리의 `tools/g009_glm_contract.py`. 기존 stage는 P0 commit에 묶여 있으므로 그대로 최신 benchmark에 사용하지 않는다.
