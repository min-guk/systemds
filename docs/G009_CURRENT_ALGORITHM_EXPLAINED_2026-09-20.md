# G009 현재 후보 형성 알고리즘 해설 — 무엇을 계산하고, 왜 반복하며, 어디서 비용이 발생하는가

작성일: 2026-09-20. 대상: `systemds-g009-integration`.

## 0. 읽기 전에: 이 문서가 설명하는 버전과 증거

이 문서는 **60초 달성 제안서가 아니라 현재 구현의 설명서**다. 독자가 자료구조·반복·계산 경계를 보고 문제를 판단할 수 있도록 작성했다.

- 커밋 기준: `e29f4fc30d19cc228b013e786d76424cb7954d7e` — P4까지 반영된 HEAD.
- 코드 설명: 위 HEAD에 **현재 미커밋 P5 작업 트리**를 더한 소스 기준. 소스 행 번호도 이 작업 트리 기준이다.
- 마지막 완료 진단: P5a `g009-p5-closure-diagnostic-20260920-r8`, build 245.047초.
- **현재 소스와 마지막 진단 바이너리는 동일하다고 가정하지 않는다.** 특히 `PlacementIdentity`의 analysis-local hash cache 등 후속 변경은 245초 실행 이후 변경으로, 그 성능 수치에 포함시키지 않는다.
- 이 문서 작성에서는 production/test 코드를 변경하거나 새 성능 실험을 실행하지 않았다.

표시 규칙:

| 표시 | 의미 |
|---|---|
| **[코드]** | 현재 구현에서 직접 확인한 동작 |
| **[측정]** | 보존된 실행 artifact에서 읽은 값. 해당 입력·실행 경계에만 유효 |
| **[추론]** | 코드와 측정에서 도출한 가설. 원인별 소요 시간이나 개선폭까지 입증한 것은 아님 |
| **[미확정]** | 현재 증거만으로 판정할 수 없음 |

아래 의사코드는 제어 흐름을 설명하기 위한 **비실행용 요약**이다. 예외 처리와 연산별 특수 규칙을 모두 복사한 구현이 아니다. 소스 찾아보기는 §15에 있다.

### 권장 읽기 순서

1. §1–3: 우리가 만들고 있는 결과와 표현 단위.
2. §4–6: 입력 조합과 여러 고정점이 왜 필요한지.
3. §7–9: native proof, factorization, cache의 실제 의미.
4. §10–11: DP 소비와 성능 측정 경계.
5. §12–14: 어디가 문제일 수 있고 무엇은 아직 모르는지.

## 1. 우리 알고리즘은 무엇을 푸는가?

한 문장으로 말하면:

> **컴파일된 각 연산 occurrence에 대해, 어떤 입력의 어떤 물리 배치를 사용하면 어떤 실행·출력 배치가 가능한지를 증명까지 포함해 닫고, 그 관계를 비용 기반 플래너에 넘긴다.**

여기서 후보는 단순히 `CP 또는 FED`라는 두 개의 선택지가 아니다.

예를 들어 같은 `FED/FOUT/ROW`도 다음이 다르면 서로 다른 후보 근거가 될 수 있다.

- 입력이 어느 statement/function/loop occurrence에서 온 값인지.
- 같은 변수 이름 중 어느 write/version이 그 read에 도달하는지.
- 어느 worker 집합과 range를 가진 실제 FederationMap을 사용하는지.
- native 입력을 그대로 쓰는지, local materialization 또는 relocation을 하는지.
- 선택된 입력 realization이 출력의 partition/layout을 정당화하는지.
- privacy·runtime capability·shape·recompile 제약을 만족하는지.

따라서 **coarse 배치 종류가 같음 ≠ 실제 실행 권한과 근거가 같음**이다.

### 만들지 않는 것 / 허용하지 않는 것

- 분석 단계에서는 가장 싼 후보 하나만 남기지 않는다. 비용 선택은 downstream 단계다.
- 빠르게 만들기 위한 candidate cap, 임의 sampling, 첫 proof만 유지하는 축소를 사용하지 않는다.
- 기존 anchor 없이 임의의 FED 배치를 만들어내지 않는다.
- TRead/TWrite는 `<CP,LOUT>` 또는 `<FED,FOUT>` 계약을 따른다.
- recompile 경로의 `<CP,FOUT>`을 허용하지 않는다.
- planner가 못 증명한 계획을 runtime fallback으로 실행시키지 않는다.

이것들은 성능 최적화와 별개로 보존해야 할 의미 계약이다. 근거: [저장소 지침](../AGENTS.md).

## 2. 전체 파이프라인 지도

**[코드]** 큰 경계는 다음과 같다. 자세한 production 타이머 범위는 §11에서 분리한다.

```text
컴파일된 DMLProgram / HOP 그래프
    │
    ├─ 공통 물리 준비·메타데이터 처리
    │
    ▼
NeutralPlacementGraphBuilder.buildDetachedAnalysis
    ├─ occurrence / CFG / abstract shape / value version 구축
    ├─ 각 입력 FType 조합에 대한 oracle 후보 생성
    ├─ CFG transient + direct native + physical 후보 closure
    ├─ 함수 입력·출력 경계 closure
    ├─ privacy 전파 및 후보 관계 정리
    ├─ proof / layout / relocation semantic closure
    ├─ executable publication closure
    └─ canonical graph + factorized support + authority 소유 객체
    │
    ▼
PlacementAnalysis
    ├─ legal node states / rule facts / realizations / support relations
    ├─ physical·logical edges / constraints / actions
    └─ 필요할 때 발급하는 candidate selection receipts
    │
    ▼
ExactPhysicalModel + ExactPhysicalCostModel
    ├─ 실제 선택 변수와 대안 생성
    ├─ hard compatibility factors
    └─ compute / transfer / materialization 등의 비용 관계
    │
    ▼
LocalPhysicalOptimizer (DP 설정의 현재 production 경로)
    ├─ local/regional seed
    └─ incremental regional optimization
    │
    ▼
ExactPhysicalSelection → receipt/authority 검증 → 계획 적용
    │
    ▼
최종 경계 검증·handoff → runtime 실행
```

**중요:** `ExactPhysicalModel`이라는 이름을 사용한다고 DP 경로가 전역 exact solver 하나를 무조건 호출한다는 뜻은 아니다. 실제 호출은 §10과 같다.

## 3. 핵심 자료구조: 후보의 층위를 구분해야 한다

**[코드]** `PlacementIdentity`, `PlacementAnalysis`, `NeutralPlacementGraph`가 다음 층위를 나눈다.

| 단위 | 무엇을 식별하는가 | 왜 필요한가 |
|---|---|---|
| `CompiledHopKey` | 프로그램·함수 namespace·경로·control region·recompile context 등을 포함한 연산 occurrence | 같은 Hop/변수처럼 보여도 다른 실행 문맥을 섞지 않음 |
| `ValueVersionKey` | lexical variable의 정의/version과 CFG provenance | TWrite→TRead, branch, loop의 값 출처를 구분 |
| `PlacementState` | exec, local/federated output, FType, shape-dependent 여부 | 가장 거친 실행/출력 선택 |
| `DurableAnchorKey` | placement identity, FType, worker/range 등의 물리 근거 | `ROW`가 같아도 다른 물리 map을 같은 것으로 보지 않음 |
| `CandidateRuleKey` | parent occurrence + **입력 위치 순서가 있는** 입력 상태 조합 | `A×B`와 `B×A`, local/FED 입력 조합을 구분 |
| `CandidateRuleFact` | 해당 조합의 capability, shape proof, profile, status, 허용 emission | oracle 결과와 거절 원인을 보존 |
| `CandidateEmissionFact` | 실제 output state, execution FType, derived action, realizations | FED 계산의 local 출력 후 upload 같은 경로도 구별 |
| `CandidateEmissionRealization` | 같은 emission을 가능하게 하는 구체적 layout/lineage | 배치 종류만으로 잃는 물리 identity를 유지 |
| `CandidateRealizationInputBinding` | 입력 위치와 정확한 source realization, binding kind | 실제 producer 선택과 consumer proof를 연결 |
| `CandidateSupportRelation` | realization을 정당화하는 여러 support clause의 압축된 관계 | OR 대안과 AND 입력 결합을 보존 |
| `CandidateSelectionReceipt` | 분석이 소유·검증하는 구체적 후보 선택 증표 | 나중에 다른 분석/epoch/임의 객체로 바꿔치기하지 못하게 함 |

### local과 불가능은 다르다

입력 상태 `ABSENT_LOCAL`은 **그 입력이 federated 입력이 아니라 local이라는 뜻**이지, 데이터가 없다는 뜻이 아니다. 내부 FType 조합에서는 `null`로 표현되기도 한다.

반대로 `inputDomain = ∅`는 **알려진 불가능(bottom)**이다. `buildNode`는 빈 domain을 local 입력으로 바꿔 CP 후보를 만들어내지 않는다.

### 작은 예: 이름이 같은 x도 다르다

```text
x₀ = fedRead(...)
while (...) {
    x₁ = transform(x₀ 또는 이전 iteration의 x₁)
}
y = consume(x)
```

이는 개념 예제이지 이 문서에서 실행한 DML fixture가 아니다. read의 근거는 단순 문자열 `x`가 아니라 **어떤 정의가 도달하는가**다. loop backedge와 초기 fed source를 함께 고려해야 하므로 직선 DAG 한 번의 순회만으로 모든 관계를 결정할 수 없다.

## 4. 첫 후보는 어떻게 생성되는가?

### 4.1 CFG와 shape를 먼저 정리한다

**[코드]** builder는 ordered HOP occurrences를 수집하고 conservative CFG를 만든다. abstract shape/scalar inference와 CFG refinement를 반복해 branch에 대한 정밀도를 높인다.

- 처음 loop의 concrete 크기를 모든 iteration에서 참인 크기로 취급하지 않는다.
- exact하게 증명된 차원만 확정하고 나머지는 unknown으로 유지한다.
- 함수 경계에 의해 source가 아직 모호하면 부분 정보를 `FULL` cardinality 증명으로 올리지 않는다.
- 선택적 CFG precision refinement가 수렴 한도에 도달하면 conservative all-branches CFG로 돌아간다. 이것은 runtime fallback과 다른 **분석 정밀도 복구**다.

이후 occurrence key, value version, durable anchor, input domains를 만들어 `buildNode`에 전달한다.

### 4.2 각 연산의 입력 배치 조합을 열거한다

연산 v에 입력 domain `D₁,…,Dₖ`가 있으면 첫 조합 수는 다음과 같다.

```text
Q_v = |D₁ × ... × Dₖ| = ∏ᵢ |Dᵢ|
```

`forEachInputCombination`은 recursive streaming으로 각 조합을 방문한다. 전체 조합 list를 한꺼번에 보관하지 않는다고 **조합 수 자체가 줄어드는 것은 아니다**.

각 조합마다:

```text
key ← (occurrence, ordered input states)
evidence ← oracle.decideWithEvidence(op, input types, exact shape hint)
apply runtime / shape / transient / recompile restrictions
derive legal native/local emissions
if exact existing anchor permits materialization:
    attach explicit derived-FOUT action and its authority
store CandidateRuleFact(key, evidence, emissions, status)
```

oracle의 capability와 producer-output profile은 서로 다른 근거다. “연산이 FED 지원”만으로 모든 output layout이 허용되지 않는다.

초기 emission에 있는 generic native lineage는 **staging용**일 수 있다. 아직 “현재 upstream 후보로 실제 실행 가능”하다는 완성된 증명이 아니다. 다음 closure가 이것을 채워야 한다.

근거: S1 `buildDetachedAnalysisScoped`, `buildNode`, `forEachInputCombination`; S3 `CandidateRuleKey`, `CandidateRuleFact`.

## 5. 왜 고정점이 여러 겹인가?

### 5.1 서로 물고 있는 관계

후보 생성은 한 번의 `oracle(op)` 호출로 끝나지 않는다.

```text
source realization이 생김
    → TRead가 해당 source를 사용할 수 있음
    → TRead를 입력으로 쓰는 physical 연산 후보가 바뀜
    → 출력의 native proof/layout이 바뀜
    → TWrite / 함수 출력 realization이 바뀜
    → 다시 downstream TRead의 정확한 source binding이 바뀜
```

privacy나 executable projection은 이미 있던 realization을 없앨 수도 있다. relocation binding은 realization identity를 교체할 수 있다. 따라서 전체 과정은 단순한 “집합에 원소만 계속 추가”하는 알고리즘이 아니다.

### 5.2 builder의 바깥 반복

**[코드]** 주요 phase는 순서대로 구성되며, 각 phase가 내부 closure를 호출한다. 아래 표가 모든 phase를 하나의 5중 곱 loop로 표현한다는 뜻은 아니다.

| phase | 무엇을 맞추는가 | 안정성 비교/후속 작업 |
|---|---|---|
| CFG refinement | reaching definitions와 abstract scalar/shape | refined CFG equality |
| 함수 boundary closure | actual/formal input, formal exit/caller output 관계 | node/domain/fact/logical input equality, 변경 consumer 재구축 |
| Semantic closure | exact proof·layout·derived action·relocation·privacy | node/domain/fact/logical relation/action equality |
| Publication closure | staging 제거 후에도 실행 가능한 후보와 exact relation이 유지되는지 | 동일한 completed-state 비교 |

publication은 단순 직렬화가 아니다. 정확히 증명되지 않은 staging realization을 제거하고, 남은 executable states로 node를 투영한다. 그 투영이 입력 domain을 바꾸면 physical 후보를 다시 만들고, 그 후보를 다시 grounding한다.

### 5.3 내부의 composed CFG/direct/physical closure

**[코드]** 핵심은 `closeCfgTransientCandidateDependenciesMeasured`다.

```text
current ← exact emission binding을 붙인 facts/nodes/logical relation
directTemplates ← 진입 시점의 template facts

repeat:
    passStart ← current
    build nodes/physical-edge/reaching-source indexes and resolver

    repeat direct closure:
        selected owner의 native realizations를 계산
        logical function boundaries를 닫음
        if facts structurally/extentionally unchanged: break
        revision ← 변경 종류 + 영향 범위 계산
        current.facts ← 새 facts
        resolver ← nextRevision(facts, invalidationOwners)

    replay eligible CFG readers
    pendingPhysical ← 이전에 남은 변경 ∪ 이번 reader 변경
    if pendingPhysical empty: return current replay result

    rebuild physical consumers of pending changes
    rebuild resolver for physical state
    repeat direct grounding again
    replay exact CFG source/reader relations again

    if completed state == passStart: return completed state
    current ← completed state

if convergence guard exhausted: throw, not partial success
```

**왜 physical rebuild 직후 멈추면 안 되는가?**

physical rebuild는 oracle-owned base emissions를 복원하면서 기존 candidate-specific native binding을 잃을 수 있다. 이 중간 상태에서 안정성을 비교하면 “proof를 붙였다가 다시 지운 상태”에 수렴한 것처럼 오판할 수 있다. 그래서 **rebuild → grounding → exact relation replay를 하나의 transfer로 완료한 후** 비교한다.

이 반복의 존재는 코드로 확인된다. 그 반복을 전부 제거해도 된다는 결론은 아니다. 제거하려면 base capability와 derived authority를 분리해도 같은 결과가 나오는지 입증해야 한다.

### 5.4 수렴 보장에 대한 한계

현재 코드에는 occurrence/domain 크기 등에 따른 pass guard와 비수렴 예외가 있다. “고정점이므로 모든 가능한 프로그램에서 종료가 수학적으로 증명됐다”는 상태가 아니다. guard가 있다는 사실도 후보를 잘라 성공시키는 성능 cap과 다르다.

근거: S1 366–400, 585–672, 725–936, 2490–2662.

## 6. P3의 delta는 정확히 무엇을 줄이는가?

### 6.1 변경 종류 분류

**[코드]** `CandidateClosureDependencies.classify`는 old/new facts를 비교한다.

| 종류 | 대표 조건 |
|---|---|
| `NONE` | 구조/외연상 동일 |
| `DOMAIN_CHANGE` | rule key domain 변화 |
| `AUTHORITY_CHANGE` | status/capability/shape proof/profile/failure 변화 |
| `TOPOLOGY_CHANGE` | support가 참조하는 source occurrence 집합 변화 |
| `EXACT_REPLACEMENT` | 기존 emission/realization support가 새 관계의 부분집합이 아님 |
| `POSITIVE_RELATION` | 위 위험 변화 없이 기존 support를 보존하며 증가 |

분류 자체도 공짜가 아니다. facts grouping, support source projection, subset/equality 검사를 수행한다.

### 6.2 두 영향 범위가 다르다

dependency graph에는 physical input, reaching source, support source, boundary constraint가 들어간다. 같은 value-version alias는 그룹으로 관리한다.

```text
C = changed owners의 무방향 연결 closure (+ aliases)
D = changed owners의 방향성 downstream closure (+ aliases)

if positive-only AND alias 조건 안전 AND D acyclic AND authority 안전:
    directOwners = D
else:
    directOwners = C

invalidationOwners = C
```

즉 **적게 다시 계산해도 cache 무효화는 더 보수적**일 수 있다. alias group이 복수 구성원을 가지거나 함수/민감 authority가 걸리면 방향성 fast path가 제한된다.

### 6.3 이것은 아직 tuple-level semi-naive가 아니다

`bindDirectNativeCandidateRealizations`는 dirty가 아닌 owner의 fact를 재사용한다. 그러나:

1. 매 호출에서 현재 facts 전체로 native/executable reference index를 구성한다.
2. dirty owner는 기존 relation에 새 tuple만 추가하는 대신 해당 fact의 realization을 다시 계산할 수 있다.
3. `LogicalBoundaryRealizations.close`와 전체 fact 안정성 비교가 뒤따른다.
4. revision마다 dependency index를 새로 만드는 경로가 있다.

P5 작업 트리에서는 nodes/anchors/edges/templates의 **정적 index를 direct inner loop 바깥으로 이동**했다. 하지만 위 가변 index 및 바깥 CFG/physical pass 준비까지 모두 없어진 것은 아니다.

수학적 semi-naive의 예는 다음과 같다.

```text
Δ(A×B) = (ΔA×B_old) ∪ (A_old×ΔB) ∪ (ΔA×ΔB)
```

**현재 전체 closure가 이 식으로 구현되어 있다는 뜻은 아니다.** 삭제/교체/negative dependency가 있는 구간에는 이 additions-only 식을 그대로 적용할 수 없다.

### 6.4 CFG reader 선택도 제한 조건이 있다

- 초기 pass는 full replay다.
- positive epoch가 아니거나 loop seed가 설치되어 있으면 selective 경로를 쓰지 못할 수 있다.
- 영향받는 reader의 reaching definition이 여러 개면 전체 replay로 돌아가는 조건이 있다.
- `FULL`, `DELTA`, `SHADOW` 모드가 있으며 SHADOW는 delta 결과와 full 결과 및 seed 집합을 비교한다.
- 이 full replay는 planner 내부의 정확한 재계산이지 runtime fallback이 아니다.

근거: S2 39–242, 245–316; S1 2670–2760, 3256–3490.

## 7. Native proof: “이 배치가 정말 이어지는가?”

### 7.1 증명 질문과 결과

**[코드]** `NativePlacementContinuity`는 다음을 증명한다.

> 이 정확한 candidate realization이 주어진 federated anchor의 worker pool/partition 조건에 근거하며, 필요한 입력들도 그 조건을 만족하는가?

결과는 boolean만이 아니라 **root의 어느 immediate input binding 조합이 증명을 성립시키는가**를 나타내는 product다. 매 root에 하위 전체 proof tree를 복사하는 표현과 구별해야 한다.

### 7.2 Witness: 같은 worker 집합만으로 충분하지 않다

`NativePoolWitness`는 개념적으로 `(FType, canonical endpoints, partition-axis intervals, exact 여부)`다.

- exact witness는 worker endpoints뿐 아니라 필요한 partition interval도 비교한다.
- dynamic witness는 interval 동일성 대신 해당 native 경로의 dynamic-layout 근거를 사용한다.
- partition을 다시 계산하는 연산인지 등에 따라 exact/dynamic 경로의 적용 조건이 다르다. 단순히 범위 검사를 꺼서 통과시키는 것이 아니다.
- PART/OTHER, partition 정보 부재, 부적합한 FULL map 등은 durable pool witness로 올릴 수 없다. literal source lineage 자체와 재배치 anchor 권한은 구분한다.

### 7.3 Root-independent topology와 query-specific pin

topology cache의 핵심 key는 `(occurrence identity, witness)`다. topology는 해당 occurrence의 현재 local facts에서 만든 **로컬 증명 규칙 목록**이지, query 전체의 증명 결과가 아니다.

```text
Topology
  eligible / nodeDirectGround / fallbackRequired
  rows[] / rowsByRealizationHandle

Row
  exact candidate reference
  dependency groups[]
  directGround / requiresPinned
```

topology 구축 시 AVAILABLE 여부, FED/FOUT와 FType, operation의 witness 보존, broadcast 가능성, source의 물리 근거 등을 검사한다. derived upload/FOUT action을 native continuity 자체의 근거와 혼동하지 않는다.

query evaluation/overlay는 여기에 **root realization pin**을 적용한다. dependency가 다시 query root occurrence를 가리키면 반드시 요청한 root realization을 사용한다. 그렇지 않으면 한 root의 증명을 다른 root의 대안으로 대신 성립시키는 오류가 생긴다.

### 7.4 DAG 직접 평가

현재 GLM 진단에서는 proof graph가 acyclic이다. direct path는 state별 완료 결과를 memoize하고 dependency를 평가한다.

```text
evaluate(state):
    if completed[state] exists: return it
    if safe shared descendant summary exists: return it
    if state is active on current path: signal cyclic/unsupported direct path
    for each eligible local row:
        for each dependency group:
            evaluate its allowed states (OR)
        combine dependency groups (AND)
        record row viability and groundedness
    combine row alternatives (OR)
    memoize completed state

if direct evaluation completes without cycle/unsupported structure:
    publish tentative safe summaries
    form root's immediate binding product
else:
    use exact legacy proof-graph path
```

논리를 간략화하면, row r의 dependency group g에는 선택 가능한 state들이 있다.

```text
V(r) = AND_g OR_{s in g} V(s)
G(r) = (directGround(r) OR dependencies(r) nonempty)
       AND_g OR_{s in g} G(s)
V(state) = OR_r V(r)
G(state) = OR_r G(r)
```

이는 row 경로의 요약이며 synthetic direct-anchor 처리는 별도로 존재한다. `directGround`가 있는 row도 명시적인 dependency가 있으면 그 dependency의 grounding 검사를 생략하지 않는다.

재사용 descendant summary는 footprint에 **현재 요청 root가 없을 때** 사용한다. root pin의 영향을 받을 수 있는 결과를 무조건 공유하지 않는다. cycle 발견 전에 계산한 sibling summary도 전체 direct evaluation이 cycle/unsupported 없이 완료된 후에만 게시한다. 이것은 root가 grounded=true여야 한다는 뜻은 아니다. 정상 평가 결과가 grounded=false인 경우도 재사용 가능한 결과다.

### 7.5 Cycle과 지원하지 않는 factorization의 exact 경로

direct 경로가 cycle 또는 지원하지 않는 구조를 만나면 정확한 legacy graph 경로를 사용한다. 예를 들어 같은 occurrence가 여러 입력 위치에 반복되는데 서로 다른/multi-option pin을 요구하면 slot 독립성을 가정할 수 없다.

legacy 경로는 selected candidate graph를 만들고 dead alternative를 제거한 뒤 DAG이면 해당 경로를, cyclic이면 SCC grounding/refinement를 수행한다.

1. SCC를 구한다.
2. component 밖 dependency는 이미 grounded여야 하는 등 eligible alternative를 고른다.
3. eligible edge로 SCC를 다시 나눈다.
4. component의 모든 state가 지원되며 실제 direct anchor 또는 외부 grounded 근거가 존재하면 grounding한다.
5. 변화가 없을 때까지 반복한다.

근거 없는 자기 순환만으로는 후보가 성립하지 않는다. 반대로 실제 ground가 있는 loop를 cycle이라는 이유만으로 없애지도 않는다. 이 의미론을 일반 Boolean least fixed point로 바꾸면 결과가 달라질 수 있다.

**복잡도 주의:** DAG state/edge 순회 자체는 방문 graph 크기에 선형일 수 있지만, topology 생성·hash·footprint union·정렬·product가 추가된다. SCC도 refinement를 반복하므로 전체가 Tarjan 한 번의 O(N+E)라고 설명할 수 없다.

근거: S5 `proveCandidateProduct` 380–408, query key 456–477, direct 평가 706–956, cyclic grounding 999–1254, factorized topology 1383–1482, 반복 occurrence pin 검사 1786–1822, `NativePoolWitness` 2823–2890.

## 8. Factorization: 무엇을 압축하고 무엇을 유지하는가?

### 8.1 한 realization의 support는 OR-of-AND 관계다

예를 들어 입력 0에서 `a₁ 또는 a₂`, 입력 1에서 `b₁ 또는 b₂`를 독립적으로 쓸 수 있다면:

```text
R = {a₁,a₂} × {b₁,b₂}
  = {(a₁,b₁), (a₁,b₂), (a₂,b₁), (a₂,b₂)}
```

flat 표현은 4개 clause를 만들지만 product route는 두 choice slot에 2개씩 저장할 수 있다. k개의 입력에 각각 d개 선택이 있으면 **한 complete product의 저장량**은 대략 `O(kd)`, 펼친 결과 수는 `d^k`다.

실제 route에는 binding choice뿐 아니라 fixed proof/binding atoms, native worker-pool witness, exact-layout annotation, deferred proof 정보가 붙는다. 출력 clause를 만들 때 이 문맥이 보존되어야 한다.

### 8.2 correlation을 잃으면 불법 후보를 만든다

```text
허용: {(a₁,b₁), (a₂,b₂)}
금지된 압축: {a₁,a₂} × {b₁,b₂}
```

뒤 표현은 `(a₁,b₂)`, `(a₂,b₁)`를 새로 만든다. 따라서 현재 구현은 **동일 metadata 아래 complete Cartesian grid임을 확인한 경우에만** singleton route를 묶는다. incomplete/correlated OR는 별도 route로 남긴다.

이는 모든 관계가 작게 압축된다는 뜻이 아니다. 상관관계가 강하거나 서로 다른 metadata가 많으면 route 수가 크게 남을 수 있다.

### 8.3 어떤 연산이 flat leaf 없이 가능한가?

**[코드]** route union, binding/source projection, 일부 filter와 existential selection은 factorized graph에서 직접 수행한다.

예를 들어 “binding 중 하나 이상이 relocation인 clause만 선택”할 때 각 leaf를 일일이 검사하는 대신, **처음 predicate에 일치하는 slot**에 그 leaf의 소유권을 배정하는 disjoint 분해를 사용한다. 중복이나 correlated tuple 추가 없이 조건을 걸기 위한 방식이다.

`NativeProofProduct`는 native proof의 product를 유지하고, `CandidateSupportRelation`은 최종 realization support의 OR 관계를 유지한다. 두 구조의 역할은 같지 않다.

### 8.4 어디서 다시 펼쳐지는가? — 중요한 현재 한계

**[코드]** “P4 이후 모든 것이 lazy”는 사실이 아니다.

| 경계 | 현재 동작 |
|---|---|
| `sameSupportAs` | route 구조 비교가 실패하면 양쪽 canonical clauses를 **전체 export**하여 외연 equality 비교 |
| `containsAllSupportOf` | 구조 subset fast path가 실패하면 **전체 export** 후 포함 검사 |
| `exactCardinality()` | decoder를 순회해 고유 leaf 수 계산. 상수 시간 size getter가 아님 |
| `exportCanonicalClauses` | decode → extensional dedupe → global sort → export cache |
| `CandidateEmissionRealization.supportClauses()` | compatibility flat-view 경계 |
| `ExactPhysicalModel.alternatives` | `supportClauses()`를 순회하여 선택 alternatives 생성 |
| 전체 snapshot writer | canonical receipt를 모두 요청하고 문자열로 출력 |

`rawCardinality`는 각 route의 Cartesian 크기를 합친 값이다. OR route가 겹칠 수 있으므로 **중복 제거된 고유 clause 수와 동일하다고 가정하면 안 된다**.

**[추론]** factorized 구조의 불일치가 잦으면 equality/subset 검사가 예기치 않은 전개 비용을 만들 수 있다. **[미확정]** 그 경로가 현재 245초 중 몇 초를 차지하는지는 별도 시간 계측으로 확정되지 않았다.

근거: S4 48–165, 261–294; S3 `CandidateEmissionRealization`; S6 232–270. 관련 회귀: `CandidateSupportRelationTest.correlatedPairsNeverBecomeCrossedProducts`, `NativeProofProductTest.twoDimensionalCorrelatedOrNeverFormsCrossRouteLeaves`.

## 9. Cache와 identity: 재사용률만으로는 판단할 수 없다

### 9.1 Cache마다 저장하는 값과 무효화 경계가 다르다

**[코드]** 다음은 의미를 구분하기 위한 표다. proof cache 하나로 묶으면 잘못된 설명이 된다.

| cache/구조 | 저장 값 | key/안전 조건 | revision 간 |
|---|---|---|---|
| Factorized topology | local rule rows 및 dependency 구조 | occurrence identity + witness; local facts 동일성 | 현재 P5에서는 local facts 동일하면 conservative invalidation 안에서도 재사용 가능 |
| Flat topology | legacy dependency skeleton | occurrence identity + witness | 보수적 invalidation 경계 유지; row/index 내부 resolver-local handle은 이전 시 reindex |
| Completed support memo | seed-independent support templates | exact root handle 또는 안전한 rule/emission template key + witness | dependency footprint가 invalidation/변경과 겹치지 않을 때 이전 |
| Direct descendant summary | DAG substate의 결과와 footprint | root pin 영향 없음, budget 내 | revision 간 이전하지 않음 |
| Public candidate proof memo | 외부 seed를 붙인 최종 proof list | exact source + external seed + root owner | 새 revision으로 이전하지 않음 |
| Signature/structural handle | 정규화 identity 계산 재사용 | analysis/resolver scope와 기존 budget | 전역 무제한 authority가 아님 |
| 후속 identity hash cache | immutable 객체의 기존 Java structural hash | analysis-local identity map, collision은 equals로 확인 | 분석 종료 시 제거; 최신 변경은 미측정 |

completed support key의 root handle을 rule/emission으로 줄일 수 있는 경우는 제한된다. fallback이나 root pin에 따라 답이 달라지면 exact key를 유지해야 한다. template 결과를 실제 seed/root에 적용할 때는 외부 근거를 다시 부착한다.

**주석과 코드의 차이:** `nextRevision` Javadoc의 completed query results를 전달하지 않는다는 설명을 모든 cache에 적용하면 틀린다. 실제 코드는 안전 조건을 만족하는 `completedSupportMemo`를 전달한다. public flat proof/direct summary와 구분해야 한다.

### 9.2 P5a에서 줄인 것은 무엇인가?

- BROADCAST 가능한 alias를 fact마다 전체 nodes에서 검색하는 대신 value-version membership을 사전 계산.
- revision에서 동일 local facts에 대한 구조 비교를 occurrence 단위로 재사용.
- factorized topology는 실제 local facts가 같으면 더 넓게 재사용.
- direct inner loop에서 불변 nodes/anchor/input/template index 재구축 제거.
- topology cache hit를 상세 phase timer 바깥으로 이동.

마지막 항은 **계측된 실행의 observer 비용 감소**다. 계측이 꺼진 production 실행에서도 같은 크기의 개선이 발생한다고 주장하면 안 된다.

### 9.3 여전히 비용이 남는 이유

cache hit에도 hash, key equality, lookup, footprint 확인, 결과 재부착 비용이 들 수 있다. 캐시를 유지하기 위한 객체 retention은 메모리와 GC에 영향을 줄 수 있다.

P5 후속 `ACTIVE_IDENTITY_HASHES`는 분석 종료 시 해제되지만 현재 diff에는 별도 entry cap이 보이지 않는다. **[추론]** 구조 hash CPU 감소와 분석 중 retained memory 증가의 교환 가능성이 있다. **[미확정]** 마지막 245초/14,725,452 KiB 결과는 이 변경의 개선폭이나 memory 안전성을 입증하지 않는다.

또한 resolver는 composed closure의 바깥 pass와 physical rebuild 뒤 새로 생성되는 경로가 있다. `nextRevision`으로 재사용하는 내부 revision과 이 새 resolver 경계는 같지 않다.

근거: S5 생성자 205–211, `nextRevision` 256–321, memo keys 2113–2227, direct summary 927–956; S8 analysis scope/hash 992–1024.

## 10. DP는 이 결과를 어떻게 소비하는가?

### 10.1 Canonical analysis 소유권

**[코드]** production은 `DMLProgram.bindPlacementAnalysisAtFinalHopBoundary`에서 analysis를 canonical 객체로 바인딩한다. 뒤의 planner는 임의로 다시 만든 동등한 analysis가 아니라 이 객체의 authority를 사용한다. 구조가 바뀌었는지도 확인한다.

builder의 detached analysis는 입력 HOP graph와 federated registry를 변경하지 않았는지 fingerprint/sentinel로 검사한다. 후보 분석과 실제 계획 적용은 다른 단계다.

### 10.2 물리 모델은 무엇을 선택하는가?

`ExactPhysicalModel.build`는 synthetic function boundary를 포함한 decision node별로 categorical variable을 만든다. 변수의 domain 원소는 coarse FType가 아니라 **실제 물리 Alternative**다.

```text
for each decision node:
    for each matching AVAILABLE rule:
        for each emission:
            for each realization:
                for each support clause:       // 현재 flat 경계
                    for each input-authority combination:
                        create Alternative
    add relevant synthetic/source/action alternatives
```

여러 후보를 아무렇게나 조합하지 못하도록 hard factors를 둔다.

- neutral graph constraints.
- strict TRead/TWrite compatibility.
- logical function boundary consistency.
- realization support dependency.
- derived-FOUT anchor authority.
- input authority 및 연산별 latent runtime input 제약.

이를 수학적으로 쓰면 node v의 선택 변수를 `x_v ∈ Alternatives(v)`라 할 때 다음과 같은 factor-model 최적화다.

```text
minimize    Σ_f cost_f(x_scope(f))
subject to  모든 hard factor h에서 compatible_h(x_scope(h)) = true
```

설명을 위해 incompatible assignment의 비용을 무한대로 쓰는 것과 동등하게 생각할 수 있다. 실제 구현의 feasibility 검사와 숫자 표현을 전부 이 한 식으로 치환했다는 뜻은 아니다. 비용 factor는 단일 node에만 걸리지 않을 수 있다.

예를 들어 producer에는 같은 ROW라도 layout `a₁`, `a₂` 두 realization이 있고 consumer의 특정 support가 `a₁`만 참조하면, producer=`a₂`와 그 consumer support를 함께 선택하는 assignment는 불가능하다. **각 node의 가장 싼 대안을 독립적으로 고르는 방식으로는 해결되지 않는다.**

이 단계는 analysis에서 압축해 보관했던 support를 다시 펼칠 수 있다. **현재 P4는 factorization을 DP consumer 끝까지 관통시킨 완성 구현이 아니다.**

### 10.3 비용과 실제 DP 탐색

`ExactPhysicalCostModel`은 execution frequency, worker count, sparse estimate 등을 이용해 execution/transfer/materialization 비용 관계를 만든다. cost surface는 analysis owner와 변수 identity에 결합된다.

현재 DP 경로는 `FederatedPlanLocalCost → LocalPhysicalOptimizer`다.

```text
model, canonical cost surface 구축
seed ← producer-before-consumer local 선택 + hard-conflict block repair
messages ← original factors를 겹치지 않게 소유하는 regional boundary messages

repeat within configured stopping/resource rules:
    pivot 주변 message들을 합침
    내부 변수를 제거하고 boundary 조건부 최솟값 관계 유지
    lower bound 갱신
    incumbent boundary에 맞추어 candidate decode
    hard-factor feasibility와 canonical cost 일치 검증
    엄격히 더 싼 feasible candidate이면 incumbent 교체

selected assignment 반환
```

local seed 호출은 neighborhood/revisit 개선을 사용하지 않는 설정이며, 그 뒤 incremental regional 단계가 실행된다. 그러므로 단순한 tree-DP 한 번 또는 모든 가능한 전역 조합 완전 탐색으로 설명하면 틀린다.

**[코드]** `IncrementalRegionalOptimizer.Options`의 기본값은 target relative gap 5%, merge assignment budget 1,000,000, retained-slot budget 8,000,000, 시간 10초, early stop 활성이다. 실제 실행은 설정에 따라 달라질 수 있다. EXACT/TARGET_REACHED/TIME/RESOURCE 같은 종료 사유를 구분한다.

이 10초 설정은 해당 incremental optimization 단계의 예산이다. 선행 analysis/model/cost/seed 생성 및 후행 검증을 모두 포함한 planner E2E 10초 보장이 아니다.

이것은 **downstream 최적화 탐색 예산**이지 G009 후보 형성에 첫 백만 후보만 남기는 cap을 도입했다는 뜻이 아니다. 다음을 구분해야 한다.

- 각 regional message의 exact conditional 계산.
- encoded model의 hard feasibility 보존.
- 전체 최적해를 증명했는지, gap/time/resource에서 멈췄는지.
- encoded model의 범위를 넘어 모든 runtime 가능 계획까지 완전한지.

동일 비용이면 기존 incumbent를 유지하는 경로가 있으므로 global exact solver와 항상 같은 tie winner를 고른다고 단정할 수 없다.

### 10.4 Receipt와 rank가 만드는 추가 경계

`PlacementAnalysis.CandidateReceiptDomain`의 receipt group은 analysis 생성 시 구성한다. **clause export·receipt 객체 생성·rank 초기화가 lazy**다. 다만:

- `clauses()`는 realization의 flat support export를 부른다.
- clause ownership은 값 equality만이 아니라 객체 identity로 검사한다.
- `ensureRanks()`는 최초 rank 초기화 시 모든 group의 flat clauses를 요청한다. 아직 export되지 않은 relation은 이때 전체 export된다.
- 선택된 receipt는 identity map으로 재사용한다.

따라서 선택한 path만 decode하는 변경은 decoder만 고치는 일이 아니다. **rank/tie-break와 compiler-owned clause/receipt 권한까지 같이 보존해야 한다.**

### 10.5 선택 이후

`ExactPhysicalSelection.create`는 selected index/Alternative와 graph-owned state identity를 확인하고, 필요한 canonical receipt를 만든 뒤 `CandidateSelections.resolveAndValidate`를 호출한다. 이어 projector/adapter/application이 적용한다.

선택 이후 빠진 realization을 임의로 다른 것으로 대체하는 구조가 아니다. final boundary verify, runtime 등록, receipt handoff까지 끝나야 production 후보 E2E 경계가 종료된다.

근거: S6/S7; [DMLTranslator.java](../src/main/java/org/apache/sysds/parser/DMLTranslator.java) 367–479, [DMLProgram.java](../src/main/java/org/apache/sysds/parser/DMLProgram.java) 59–88, [FederatedPlanLocalCost.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java) 45–78, [IncrementalRegionalOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java) 46–58/120–222/345–360, [ExactPhysicalCostModel.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java) 295–335, [ExactPhysicalSelection.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalSelection.java) 90–169; S3 1127–1174/1254–1275/3137–3167.

## 11. 245초는 정확히 어느 구간인가?

### 11.1 진단 evaluator의 경계

**[코드]** `G009PlanningPerformanceEvaluatorTest`는 다음 순서다.

```text
compileWorkload()                       // build timer 밖
start build timer
analysis = builder.buildAnalysis(program)
stop build timer

start export timer
write complete graph/rule/emission/receipt snapshot
stop export timer

compare snapshot bytes with expected
```

따라서 build 시간에는 downstream production DP model·optimization·selection 전체가 포함되지 않는다. snapshot export에도 별도 비용이 있다.

**중요한 metrics 함정:** evaluator는 snapshot을 쓴 뒤 `metrics.snapshot()`을 저장한다. 따라서 JSON의 `candidateReceiptsCreated = 99,632` 같은 카운터는 **export 중 증가분까지 포함할 수 있다**. 이것을 “245초 build 안에서 receipt 99,632개를 다 만들었다”라고 해석하면 안 된다. `exportNanos`와 build phase partition을 따로 봐야 한다.

### 11.2 완료된 진단 수치

**[측정]** 아래는 동일 종류의 GLM host 진단 비교이며 **공식 동일-Docker paired 성능 인증이 아니다**. 저장소 계약상 공식 성능 채택/목표 통과 근거를 대체하지 않는다.

| revision/stage | Build | Canonical export | Process wall | Peak RSS |
|---|---:|---:|---:|---:|
| P3 `c0dc78e819` | 450.977초 | 365.350초 | 13:43.49 | 35,859,276 KiB |
| P4 `e29f4fc30d` | 279.018초 | 368.579초 | 11:15.14 | 14,030,516 KiB |
| P5a r8 | 245.047초 | 358.011초 | 10:10.66 | 14,725,452 KiB |

P3/P4/P5a snapshot의 보존 SHA-256:

```text
78bb2d116061fc7d8aad97059c56cba3665367215bb3fe3a1679a613a077ed09
```

snapshot 일치는 이 fixture의 관측된 출력 보존 근거다. 전역 completeness/termination 증명이나 모든 runtime 결과 정확성의 대체가 아니다.

초기 716.592초는 **새 P1/P2 구현 이전 unified acyclic-fast-path 진단**이다. P1과 P2의 독립 full-GLM 수치는 동결되지 않았으므로 716.592→450.977초를 P3 단독 효과라고 부를 수 없다.

### 11.3 P5a build의 exclusive 시간

| phase | 시간 | 해석 |
|---|---:|---|
| `CLOSURE_REPLAY` | 125.723초 | nested proof 단계 시간을 뺀 replay/준비/관계 처리 등 |
| `PROOF_TOPOLOGY` | 65.822초 | P5a에서는 topology expansion miss/build를 중심으로 계측 |
| `ANALYSIS` exclusive | 36.898초 | 분석 안에서 다른 named phase로 배분되지 않은 작업. 단일 함수가 아님 |
| Clause merge/canonicalization | 5.752초 | 해당 계측 구간. 모든 곳의 정렬 비용 전체와 동일하지 않음 |
| Proof grounding | 4.066초 | DAG/grounding 관련 exclusive 구간 |
| Support product relation materialization | 3.164초 | 해당 product 단계 |
| Receipt/rank consumer preparation | 2.387초 | build-side 준비. DP 전체 소비 비용이 아님 |
| Overlay + public proof + observer 등 | 약 1.208초 | 나머지 named phase |

outer elapsed와 phase inclusive 사이에는 약 0.027초 차이가 있다. inclusive/exclusive를 섞어 더하면 중복 계산이 된다. 예를 들어 `CLOSURE_REPLAY` inclusive 약 199.983초에 topology 시간을 다시 더해서는 안 된다.

P4→P5a에서 topology cache-hit timer의 위치도 바뀌었다. **총 build 시간은 비교하되, topology phase 감소분 전부를 알고리즘 개선으로 단정하지 않는다.**

### 11.4 작업량: 무엇이 줄고 무엇이 그대로인가?

| 항목 | P4 | P5a | 해석 |
|---|---:|---:|---|
| Topology builds | 67,720 | 28,024 | 실제 확장 감소 |
| Topology hits | 3,149,021 | 3,188,717 | hit에도 lookup/비교 비용이 있음 |
| Topology rows built | 3,837,174 | 2,562,927 | 생성량 감소 |
| Topology revision entries reused | 10 | 39,706 | local-fact 기반 재사용 증가 |
| Direct closure passes | 273 | 273 | P5a는 주로 pass당 비용 감소 |
| Direct full / stable passes | 77 / 77 | 77 / 77 | 두 카운터의 집합이 동일하다는 뜻은 아님 |
| Selective CFG passes | 18 | 18 | 처리 스케줄은 큰 변화 없음 |
| CFG full passes / 그중 unsafe fallback | 59 / 30 | 59 / 30 | fallback 30은 full 59와 별도로 더할 값이 아님 |
| CFG reader recompute / reuse | 11,396 / 3,465 | 11,396 / 3,465 | P5a가 reader 수를 줄인 것은 아님 |

P5a의 전체 fixed-point phase 횟수는 CFG refinement 3, function 6, semantic 5, publication 5이며 direct pass 273은 별도 누적 카운터다. 이를 `3×6×5×5×273`으로 곱하는 것은 잘못이다.

### 11.5 진짜 production E2E

목표 `TcandE2E`는 공통 preparation 시작부터 analysis, DP model/cost 준비, optimization, selection/application, 최종 검증·handoff까지의 고정 wall 경계다. runtime 학습이나 진단 snapshot serialization은 별도다.

**[미확정]** 여기 제시한 host 진단만으로 이 production E2E가 몇 초인지, build 60초가 E2E 60초를 만족하는지 알 수 없다. `716.592 / 10 = 71.659초`도 기존 build 진단의 산술 참고선일 뿐 공식 E2E 합격선이 아니다.

근거: S9; [E2E 계약 문서](G009_CANDIDATE_E2E_TENTH_PLAN_2026-09-19.md) §3.

## 12. 계산량을 수식으로 보면

이 절의 식은 **비용 구조 모형**이다. count를 실제 초로 변환하는 측정식이나 보편적인 tight bound가 아니다.

기호:

- N: occurrence/node 수, E: dependency edge 수.
- F: 현재 rule fact 수, B: stored binding/proof atom 수.
- R: 실제 수행한 closure revision/pass들의 집합.
- Q_r: revision r에서 처리하는 proof query들.
- S_q, A_q, E_q: query가 방문/생성하는 state, alternative, dependency 수.
- L: 펼쳤을 때의 support leaf 수, K: canonical 비교 횟수.

### 12.1 후보 입력 조합

```text
W_input ≈ Σ_v ∏_i |D_vi| × oracle/profile 처리비
```

streaming은 peak 임시 list를 줄여도 이 곱을 없애지 않는다. 단, 현 GLM에서 이것이 제일 큰 병목이라는 측정 근거는 없다.

### 12.2 반복 closure

```text
W_closure ≈ Σ_{r∈R} [
    static/dynamic index 준비
  + dirty owner의 proof/relation 계산
  + boundary closure
  + old/new equality·subset·revision 분류
  + CFG replay와 physical rebuild
]
```

dirty owner 수가 작아도 index 준비나 equality가 전체 F/B를 순회하면 해당 항은 작아지지 않는다. “재계산 fact 70% 감소”가 전체 시간 70% 감소를 의미하지 않는 이유다.

### 12.3 proof query

```text
W_proof ≈ Σ_r Σ_{q∈Q_r} (|S_q| + |A_q| + |E_q| + product/filter 작업)
```

topology 공유는 반복 생성 항을 줄인다. query context와 eligibility가 다르면 query별 평가가 남는다. DAG이라는 사실은 SCC refinement를 피하게 해주지만 graph 순회와 product 계산까지 0으로 만들지는 않는다.

### 12.4 factorization과 flat boundary

route j의 slot 크기가 `n_j1,…,n_jk`이면:

```text
stored atoms ≈ Σ_j Σ_i n_ji + fixed atoms/metadata
raw leaves    = Σ_j ∏_i n_ji
```

factor graph에서 끝나는 연산과 full decoder를 호출하는 연산의 규모가 다르다. canonical export는 raw leaf 순회·중복 제거·고유 leaf 정렬·구조 비교 비용을 부담한다. 긴 proof identity를 비교하면 비교 한 번도 상수가 아닐 수 있다.

### 12.5 DP model 준비의 별도 항

현재 `ExactPhysicalModel.alternatives`는 각 decision node마다 전체 ordered facts를 읽고 parent identity로 거른다. 따라서 이 grouping 단계만으로도 `O(N_decision × F)` 스캔이 생긴다. 그 뒤 support clause와 input-authority 조합 전개가 추가된다.

**[코드]** 이 스캔이 존재한다. **[미확정]** 현재 production GLM E2E에서 차지하는 초/비중은 이 build-only 진단에 없다.

## 13. 문제 판단표: 확인된 사실과 가설을 분리

| 우선 검토점 | 확인된 사실 | 가능한 문제 [추론] | 아직 모르는 것 |
|---|---|---|---|
| Composed closure 반복 | physical rebuild 후 grounding/replay 재실행, 여러 outer phase에서 호출 | base fact와 derived authority가 섞여 재구축 비용 증폭 | 동일 의미를 유지하며 없앨 수 있는 반복의 수 |
| Delta granularity | owner-level skip, 가변 index와 전체 안정성 비교 잔존 | dirty가 작아도 pass당 고정비가 큼 | 준비/비교 각각의 exclusive 시간 |
| Relation comparison | 구조 비교 실패 시 flat export | 압축을 되돌리는 equality/subset 검사가 비용 유발 | 실제 fallback 횟수와 총 시간 |
| Topology lifecycle | P5a local-fact 재사용 개선, outer rebuild의 새 resolver 존재 | epoch 경계에서 여전히 동일 topology 생성 | 추가로 안전하게 공유 가능한 범위 |
| DP consumer boundary | full supportClauses 및 authority products 전개 | build의 지연 계산이 downstream에서 청구됨 | model/optimization/selection 각각의 E2E 비용 |
| DP owner grouping | node마다 전체 facts scan | 표현 변경 없이도 제거 가능한 반복 scan | 실제 비중; 60초 목표에 미치는 영향 |
| Hash/signature | 구조 객체 반복 hash, 후속 identity hash cache 추가 | CPU 감소와 retention 증가의 교환 | 최신 변경의 성능·memory 효과 |

**현재 가장 강한 결론:** closure/topology가 마지막 build 진단의 약 78.2%를 차지한다. 그러나 그 안의 특정 loop를 원인으로 지목하고 “그것만 바꾸면 60초”라고 말할 근거는 아직 없다.

### 앞선 설명에서 더 엄밀히 고쳐야 할 표현

1. **“P4가 DP까지 factorized 상태를 유지한다”는 완료 주장은 부정확하다.** 현재 analysis 내부에는 factorized 경로가 있지만 DP model은 `supportClauses()`를 소비한다.
2. **“P3는 delta 계산이다”는 부분적으로만 맞다.** owner/reader 선택은 delta이나 모든 product/union/index가 tuple-delta는 아니다.
3. **“P1은 안전하니 효과도 있다”는 논리적으로 다른 주장이다.** 제거한 작업은 확인되지만 단독 elapsed 기여는 미측정이다.
4. **P5a는 비공식 구현 slice 명칭이다.** 원래 계획의 P5는 최종 통합·E2E qualification 단계이며, P5a 진단 완료가 그 전체 완료를 뜻하지 않는다.

## 14. 독자가 판단할 때의 핵심 질문

1. **우리가 보존해야 할 것은 relation의 외연인가, 현재 객체 구조/정렬 형식까지인가?** 공개 receipt identity 계약과 내부 동등성 비교를 나눠 볼 필요가 있다.
2. **base oracle fact와 증명된 realization을 같은 rebuild 경로에서 관리해야 하는가?** 지금은 물리 재구축이 proof binding을 지우고 재증명을 요구한다.
3. **변화 감지는 “전체를 비교해 알아내는가”, “변경을 만들 때 기록하는가”?** 현재는 상당 부분 전자다. 후자로 바꾸려면 모든 삭제·교체·권한 변화를 놓치지 않아야 한다.
4. **topology, support solution, public proof의 dependency 범위가 정말 같은가?** 같다고 가정해 일괄 무효화하면 과도한 재계산, 다르다고 성급히 공유하면 stale proof가 생긴다.
5. **DP가 모든 flat alternative를 요구하는 현재 경계를 유지할 것인가?** 유지한다면 output-sensitive 비용이 남는다. 바꾼다면 비용·compatibility·tie-break·receipt 선택까지 함께 설계해야 한다.
6. **같은 coarse FType 아래 어떤 정보가 달라질 수 있는가?** owner, exact layout, source lineage, privacy, realization identity 변화는 모두 의미 있는 변화다.
7. **60초는 build인가 production E2E인가?** 후자라면 build 외 비용을 빼고 평가하면 안 된다.

### 이미 있는 정확성 보호 장치와 검증의 한계

- FULL/DELTA/SHADOW의 결과 비교.
- cyclic/grounded/dead dependency 및 revision cache 회귀.
- correlated product와 complete-grid coalescing 회귀.
- publication/physical composition, multi-definition loop seed 회귀.
- full canonical snapshot 비교.

이들은 중요하지만 유한한 입력에서의 검증이다. 이번 문서는 새 test suite를 실행하지 않았으며 기존 테스트 존재/코드와 보존된 진단을 대조했다. 전역 증명이나 최신 미커밋 변경의 전체 qualification을 새로 수행했다고 주장하지 않는다.

## 15. 소스 및 artifact 찾아보기

행 번호는 문서 작성 당시 작업 트리 기준이며 이후 변경될 수 있다. **메서드명을 함께 검색**하는 것이 안전하다.

| ID | 소스 | 주요 위치 |
|---|---|---|
| S1 | [NeutralPlacementGraphBuilder.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java) | `buildDetachedAnalysisScoped` 350, function 585, semantic 725, publication 778, composed closure 2511, direct binder 2720, CFG replay 3256, physical closure 4083, `buildNode` 5721, input enumeration 8486 |
| S2 | [CandidateClosureDependencies.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateClosureDependencies.java) | ChangeKind 32, `revision` 116, classify 152, structural equality 212, dependency index 245 |
| S3 | [PlacementAnalysis.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java) | CandidateInputState 445, CandidateRuleKey 465, support clause 665, realization 710, emission 864, rule fact 976, receipt domain 1113 |
| S4 | [CandidateSupportRelation.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSupportRelation.java) | owner/epoch/routes 48, union 107, equality/subset 123–135, selectAnyBinding 142, export 278 |
| S5 | [NativePlacementContinuity.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java), [NativeProofProduct.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativeProofProduct.java) | native query, topology, DAG/SCC, product, revision cache. 상세는 §7/9 |
| S6 | [ExactPhysicalModel.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java) | `build` 149, `alternatives` 232, per-node whole-facts scan 241 |
| S7 | [LocalPhysicalOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java) | `optimize` 60, `regionalSeed` 114 |
| S8 | [PlacementIdentity.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java) | occurrence 232, value version 264, anchor 326, binding 483, receipt 807, analysis hash cache 992–1024 |
| S9 | [G009PlanningPerformanceEvaluatorTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/placement/G009PlanningPerformanceEvaluatorTest.java) | timer/export/metrics 31–58, byte comparison 67–80, snapshot 129–142 |

관련 회귀 테스트(이번 문서 작성에서 새로 실행한 테스트 목록이 아님):

- [CandidateClosureDependenciesTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/placement/CandidateClosureDependenciesTest.java)
- [NeutralPlacementFixedPointCompositionTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementFixedPointCompositionTest.java)
- [NativePlacementContinuityTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuityTest.java)
- [CandidateSupportRelationTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/placement/CandidateSupportRelationTest.java)
- [NativeProofProductTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeProofProductTest.java)

측정 artifact 경로(로컬 파일이며 Git에 포함되지 않음):

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/g009-p3-final-diagnostic-20260920/
/grid/3/cofee-lm-sweep-mchoi-20260914/g009-p4-grid-diagnostic-20260920-r7/
/grid/3/cofee-lm-sweep-mchoi-20260914/g009-p5-closure-diagnostic-20260920-r8/
```

각 디렉터리의 `metrics.json`, `stderr-time.log`, snapshot/sha256 및 timing 파일을 참조한다. 기존 보고서: [P3](G009_P3_FINAL_DIAGNOSTIC_2026-09-20.md), [P4](G009_P4_FACTORIZED_RELATION_DIAGNOSTIC_2026-09-20.md).
