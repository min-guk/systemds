# FedPlanner 통합 검토: Oracle-driven Memo, Supply 소유권, Privacy와 최적화

**Joint Feasibility 후속 기준:** [통합 명세 ADR21, §§5.5–5.7/11.8](FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md)을 따른다. Oracle의 source-free rule·input-position 조건과 ProgramFacts의 exact binding을 연결해, edge로 분해되지 않는 관련 consumer/boundary 조건만 joint 검사한다. 같은 domain/선택 상태의 반복 검사와 source tuple 구체화를 피하며, 공통 compiled owner·conditional privacy·guarded supply·global shared cost는 유지한다.

**정책 이력 안내:** 이 문서는 앞선 분석 기록이다. 최신 사용자 확정 정책은 [통합 구현 명세 ADR09/20 및 §6.6](FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md)을 따른다. Loop Backedge는 native DIRECT-only이며 이 문서의 일반 backedge transfer/normalization 후보·비용 설명은 새 설계에 적용하지 않는다. Entry/최종 Exit·일반 body edge의 합법 movement 및 If-Else guarded join transfer는 유지한다. 현재 코드의 기존 동작을 기록한 내용과 새 정책을 구분한다.

작성일: 2026-10-13  
대상: /home/mchoi/w1357-structural-grounding-20261006  
기준 HEAD: c961a291811d23db104cc63a2b161e7e4b480e57 및 기존 미커밋 변경  
범위: 분석·설계 문서만 작성. Production/test 수정, 빌드, 테스트·workload 실행, commit/push 없음.

이 문서는 [단순 Memo 설계](FEDPLANNER_SIMPLE_MEMO_REDESIGN_2026-10-13_KO.md)와 [Earliest Safe Pruning 검증](FEDPLANNER_EARLIEST_SAFE_PRUNING_2026-10-13_KO.md)을 이번 여섯 질문에 맞춰 통합한다. **[확인]**은 현재 소스/기존 artifact, **[해석]**은 그 구조적 의미, **[제안]**은 앞으로 구현할 설계, **[미검증]**은 입증하지 않은 지원·성능이다. 아래의 제안은 구현 완료나 실제 workload 개선 결과가 아니다.

후속 [Control Flow·DP 설계](FEDPLANNER_CONTROL_FLOW_DP_DESIGN_2026-10-13_KO.md)는 사용자 추가 전제인 **n>1 loop와 갱신 값의 외부 사용, 재귀 제외, 고정 physical pair**를 적용한다. Zero-iteration 대안은 제외하되 최종 exit binding은 유지하며, exact search 안에 closed-tree DP와 lazy boundary memoization을 결합한다. 이 문서의 일반 loop 설명과 후속 문서의 제한된 지원 범위를 구분한다.

**분석 기준 [확인]:** HEAD는 위 커밋과 같다. 이번 검토 시작 시 기존 production/test 수정은 6개 파일 +463/-54행이며, NativeContinuitySupportClauses의 sparse authority, PlacementAnalysis/PlacementRelationClosure의 병합·retention, 관련 테스트 세 개에 해당한다. 이 변경을 보존한 작업 트리를 읽었다. Oracle/Generator/optimizer의 아래 참조에는 이 미커밋 source diff가 없으며, Closure 관련 행은 작업 트리 기준이다. 기존 변경의 파일별 내용은 선행 문서 §2.1에 있다. 새 문서의 제안을 HEAD나 현재 구현의 동작으로 혼동하지 않는다.

## 1. 질문별 답과 최종 권고

| 질문 | 명확한 답 | 권고 |
|---|---|---|
| Oracle 집합 반환과 early pruning | 타당하다. 현재 단일 OpCaps를 그대로 목록 하나로 감싸면 CP/forced-local 대안을 놓친다. | Operator별 native variant 집합을 반환하고 source-free pattern을 생성한다. Runtime dispatch의 첫 matching rule이라는 의미는 유지한다. |
| Native/available output 소유권 | Native는 execution owner의 rule, available은 producer output slot의 **조건부 공급 recipe**다. 실제 use의 합법성은 edge에 속한다. | Producer에 intrinsic offer를 한 번 저장하고 edge에서는 선택된 rule과 exact binding을 lazy하게 검사하는 혼합 방식. |
| Privacy 적용 시점 | Oracle 직후 확정 불가능 rule/recipe를 제거하는 것이 적절하다. 그러나 label propagation 완료가 모든 rule legality 완료는 아니다. | Exact value/input role에 대한 stable 조건은 앞에서 검사하고 guarded/선택 의존 조건은 ID로 보존한다. |
| Shared/control flow | 기존 facts를 직접 활용할 수 있다. Partition pair만으로는 불충분하다. | 단일 execution owner 변수, guarded def-use, loop entry/induction, actual/formal/return, output-slot coupling을 작은 predicate로 유지한다. |
| Noncanonical native path | 실제 지원 연산이라도 새 계약 밖일 수 있다. CP 대안도 privacy/runtime이 허용할 때만 가능하다. | Native capability와 canonical policy 판정을 분리하고, 기본 경로는 canonical source와 검증된 native effects만 수용한다. |
| Solver/전체 효율 | 생성 공간은 줄일 수 있지만 global search의 지수 복잡도는 남는다. B&B가 LogReg/GLM에서 더 빠르다는 증거는 없다. | 작은 direct B&B를 **연구용 exact prototype/reference**로 먼저 검증한다. Production optimizer 교체는 같은 domain·목표의 실측 후 결정한다. |

**권장 아키텍처 하나 [제안]:** 기존 HOP DAG와 Program Facts + immutable native-rule Memo + producer별 intrinsic offers + edge의 lazy compatibility + dependency worklist + 직접 선택/공유 비용 평가. 새 구조를 처음 검증할 optimizer는 설명하기 쉬운 exact B&B로 둔다. 이것은 production에서 기존 Local/Exact보다 빠르다는 권고가 아니다. 기존 optimizer는 baseline·reference로 유지하고, 새 기본 경로 채택은 §9의 실험을 통과한 뒤 판단한다. Solver들을 감싸는 새 범용 framework는 만들지 않는다.

## 2. Oracle 기반 후보 생성과 Early Pruning

### 2.1 현재 API는 native 대안의 완전한 집합이 아니다

**[확인]** FedOracle.caps와 PreparedDecision.decideWithEvidence는 한 추상 input tuple에 대해 하나의 OpCaps를 반환한다. RulesCore.OracleEngine.prepare는 opcode 규칙 또는 category의 첫 matching rule을 선택한다. PlacementCandidateGenerator.processCombination은 그 결과를 받은 뒤 CP, native FED, forced-local FED, derived upload 대안을 추가한다. [Oracle 계약][oracle-contract], [dispatch][oracle-dispatch], [facade][oracle-facade], [후속 대안 생성][generator-options]

따라서 현재의 다음 두 의미를 분리해야 한다.

| 현재 표시 | 새 표현 |
|---|---|
| FED caps이지만 CP emission도 허용 | 별도 CP native rule. 보통 payload 입력은 LOCAL이고 필요한 collect는 edge에 명시 |
| Native FOUT에 forced-local FED 대안 추가 | 실제로 다른 runtime mode이면 LOCAL native variant. 동등성이 입증된 단순 collect만 output recipe로 분리 |
| CP/FED LOUT에서 derived FOUT 추가 | LOCAL native output + 명시 UPLOAD recipe. Native FED output으로 위장하지 않음 |
| FED 불가 reason을 담은 CP fallback caps | 해당 FED 조합의 미지원이지, FED payload를 CP가 이동 없이 읽을 수 있다는 증거가 아님 |
| Scalar FOUT normalize | Scalar는 LOCAL이어도 실행 자체는 FED일 수 있음 |

**[제안]** Operator dispatch는 유지하고, 선택된 operator adapter가 모든 검증된 execution variant를 반환하도록 한다. Registry의 모든 rule을 무조건 union하면 서로 다른 precedence/shape fallback을 섞을 수 있다. Set API의 구현은 callback/iterator로 하여 큰 결과 목록을 먼저 만들 필요가 없다.

~~~java
void forEachNativeCase(
    OpSignature signature, ShapeFacts shape, FixedWorkerPool workers,
    NativeCaseConsumer sink); // pattern, exec, outputSlots, variant, nativeCondition
~~~

현재 DecisionDependencies와 prepareExecutionRelation의 determinant enumeration을 재사용한다. 다만 기존 relation의 “한 decision evidence”를 native variant 집합으로 바꿔야 한다. FTypeProfile은 가능한 output의 projection일 뿐 input-output correlation 전체가 아니므로 이를 rule 집합 대신 사용하지 않는다. [determinants][oracle-dependencies], [execution relation][oracle-relation]

### 2.2 Partition만으로 입력 의미를 표현하면 생기는 오류

**[확인]** 비-matrix/frame 입력은 기존 Oracle에서 null로 mapping되고, metadata-only access도 따로 식별된다. 특히 nrow/ncol/length는 matrix payload 대신 FederationMap metadata를 읽을 수 있다. [type mapping][oracle-mapping], [metadata access][access-kind]

~~~dml
n = nrow(X);  # X의 payload가 private이어도 metadata 접근과 raw collect는 다르다.
~~~

**[제안]** 네 partition 상태는 유지하되 입력 requirement에 기존 access role을 연결한다. Metadata 요구를 단순 requiredPartition=LOCAL로 적어 COLLECT를 삽입하지 않는다. Metadata input은 지원되는 layout mask와 isMetadataAvailable 조건으로 검사한다. Mask는 {LOCAL,ROW,COL,REP}의 부분집합이지 다섯 번째 layout이 아니다. Scalar/handle도 기존 datatype/role을 따른다.

LOCAL operand를 FED kernel이 임시 broadcast하는 rule은 그 input을 LOCAL로 요구한다. REP input은 이미 모든 worker에 전체 데이터가 있는 별도 입력 상태다. Rulesets.broadcastMatrixCompatibleWithPartition은 persistent BROADCAST matrix가 LOCAL처럼 sliced되지 않음을 명시한다. 예를 들어 W>1의 ROW elementwise 실행에 동일 shape의 full REP matrix를 넣으면 full-vs-partition dimensions가 맞지 않을 수 있다. Row vector 또는 검증된 single partition 예외와 구분한다. [REP operand 조건][broadcast-compat]

### 2.3 Oracle rule에서 producer 공급을 찾아간다

**[제안]** 생성 순서는 “producer candidate product → Oracle” 대신 다음과 같다.

~~~text
operator native pattern/variant
  → 확정된 shape·privacy gate
  → producer별 required-layout offer index 조회
  → same-owner/guard/binding을 유지한 factorized edge relation
  → 필요할 때만 joint support 탐색
~~~

Producer의 rule/output slot별 available offer는 §3에서 정의한다. 각 slot에 대해 mayOfferRules[partition]을 small BitSet으로 만들 수 있다. UNKNOWN condition도 가능한 것으로 포함한다. 이 mask는 **존재 가능성의 상계**일 뿐, 현재 use에 합법적이라는 증거가 아니다.

Consumer rule r의 input position i가 p를 요구하면 다음 intersection을 검사한다.

~~~text
matchingProducerRules = active[producerOwner] ∩ mayOfferRules[sourceSlot,p]
~~~

Empty이고 해당 binding이 필수이며 producer universe가 closed이면 r 또는 해당 supply option을 제거할 수 있다. Nonempty이면 정확한 recipe/condition/guard/availability를 검사한다. 일반 소비자 fanout마다 같은 offer list를 복사하지 않는다. 조건부 mask는 occurrence/privacy snapshot에 scope하며 같은 compiled owner의 다른 invocation 조건을 무조건 합치지 않는다.

### 2.4 최소 lazy join 알고리즘

아래는 **로컬 관계의 support query**다. Global assignment 목록을 생성하는 알고리즘이 아니다.

~~~text
support(consumerRule r, relation c, fixedAssignments, active, facts):
    if a required domain is not coverage-ready: return UNKNOWN
    bindings = facts.symbolicBindings(c) // exact sources, positions, guards; 선택 변수 아님
    views = required offer/movement views for r and bindings

    for each mandatory binding/view:
        if metadata-only:
            if metadata access provably impossible: return NO_SUPPORT
        else if closed may-offer intersection is empty:
            return NO_SUPPORT

    // 이 cheap semijoin은 필요조건이다. 모든 입력의 nonempty가 충분조건은 아님.
    vars = distinct unresolved choice owners and required supply selectors in c
    search smallest remaining matching domain first:
        choose one value lazily, without tuple/list allocation
        unify repeated owner IDs, including repeated input positions
        check newly decidable privacy, guard, native and boundary predicates
        if proven false: backtrack
        if undecided due to budget/unknown: retain UNKNOWN evidence
        if every scope variable assigned:
            require one shared assignment legal in every active environment of c
            if proven: return SUPPORTED with a bounded witness
    return NO_SUPPORT only if every completion was soundly rejected
           else UNKNOWN
~~~

동일한 producer가 input position 0과 1에 나타나더라도 서로 다른 R을 선택하지 않는다. Guarded supplies는 분기마다 달라도 되지만, 동일한 compiled execution owner는 모든 환경에서 동일한 R을 사용한다. Optimizer가 branch guard/source writer 자체를 더 저렴한 쪽으로 선택하지 않는다.

**불충분한 marginal 검사:** shared producer의 각 consumer에 서로 다른 witness가 있거나, branch X/Y의 writer가 개별적으로 존재하거나, 각 loop backedge가 서로 다른 head choice로 통과하거나, function call마다 서로 다른 shared body rule이 통과하거나, native/output privacy가 단독으로 통과하거나, 동일한 copy의 lifetime이 각 use에서는 개별적으로 통과한다는 검사만으로는 공동 합법성을 증명할 수 없다.

압축 표현은 `rule r + ordered input requirements + facts binding 참조 + edge predicates`면 충분하다. 필요할 때 위 join을 방문하더라도 새로운 Memo rule로 전개하지 않는다. 독립적인 pattern mask는 공유할 수 있지만, at-least-one-FED·axis equality·multi-output coupling 등의 빈틈을 단순 product로 채우지 않는다.

**한계:** "최종적으로 global legal한 tuple만 한 번도 실패하지 않고 생성"하려면 global feasibility를 사전에 풀어야 한다. 이 제안은 이미 알려진 불가능 prefix를 만들지 않고 남은 상관관계를 lazy하게 검사한다. 모든 국소 prefix가 global feasible하다고 주장하지 않는다.

### 2.5 Enumeration 순서와 completeness

1. Pure native/type/확정 shape/privacy 판정은 필요한 정보가 확정된 시점에 실행한다.
2. Source ingress를 검증하고, acyclic producer의 closed domain과 offer index를 일찍 준비한다.
3. Oracle의 operator-specific pattern을 순서대로 생성한다. 불필요한 scalar/metadata axes를 4상태 payload product로 만들지 않는다.
4. 확정된 input partition에 support가 없음을 root에서 증명하면 해당 pattern region을 생성하지 않은 채 제외한다. UNKNOWN이나 아직 생성하지 않은 domain을 empty로 취급하지 않는다.
5. Owner Memo를 freeze할 때마다 ready가 된 edge/joint 관계를 worklist로 넘긴다. Loop는 source proof를 기다리며 rule 생성을 미루지 않고 native universe를 먼저 준비해 entry/induction을 검사한다.
6. Search가 시작된 뒤에는 universe를 고정한다. Branch-local 삭제는 BitSet/trail에만 반영하고 Oracle의 영구 후보를 지우지 않는다.

Earliest는 "authoritative 정보와 sound checker를 사용할 수 있는 최초 시점"이며, 의미론적으로 가장 이른 삭제를 다항 시간에 알아내는 만능 판정기가 아니다. Current MRV의 FED-only prefix 거부를 PUBLIC/CP domain으로 무조건 확장하지 않는다. [현재 early gate][generator-prefix], [partial UNKNOWN][oracle-partial]

같은 native partition에서 가장 저렴한 rule 하나만 남기지 않는다. Dominance 삭제에는 대체 rule이 모든 관련 context에서 입력 요구·공급 가능성·privacy·실행 owner·공유/lifetime·emission 계약을 보존하고, 모든 downstream completion의 비용을 증가시키지 않는다는 증명이 필요하다. 초기 구현은 완전히 같은 의미의 중복 rule intern 정도만 수행하는 편이 단순하다. 나머지 cost pruning은 검증된 incumbent와 안전한 하한을 사용하는 global search에서 처리한다.

**복잡도:** raw source 후보 수를 dᵢ라고 할 때 product Πdᵢ의 생성·저장을 피한다. 대신 native pattern 수 K, 각 rule의 pattern 길이, offer intersection, 실제로 방문한 support query에 비례한다. 일반 arity a의 native patterns는 최대 4ᵃ이고 joint support도 여전히 지수적일 수 있다. 압축이 이를 보편적으로 해소하지는 않는다.

## 3. Native Output / Available Output / Edge의 책임

### 3.1 Producer 측 precompute와 edge 측 lazy의 결합

| 컴포넌트 | 소유하는 정보/작업 | 소유하지 않는 정보 |
|---|---|---|
| Oracle/native rule | 실제 input role/pattern, exec, output slot의 native layout, native lowering variant/조건 | source realization, placement anchor, consumer의 공급 선택 |
| 공통 Movement Policy | DIRECT/UPLOAD/COLLECT, D1, single-worker 정규화, canonical shape 제약의 pure recipe template | producer의 값·복사본·수명·실행 빈도 |
| Producer Memo | rule/slot별 intrinsic offers와 privacy 조건, may-offer index | 특정 use 시점에 copy가 존재한다는 단정 |
| HOP edge + Facts binding | 정확한 producer value/slot, consumer position, guard/context, 요구 role/layout | producer rule의 복사본, 전체 source tuple 목록 |
| Optimizer | R/S/schedule의 공동 선택, 공급 합법성, 공유 activation/cost | native capability의 재정의 |
| Selected-plan decoder/emitter | 실제 primitive actions, operand/slot, creation/release, runtime instruction | 선택되지 않은 fallback, 암묵적인 layout 수정 |

**[제안]** Producer intrinsic offer는 "이 rule로 얻은 이 logical output을 조건이 충족되면 이 recipe로 공급할 수 있다"는 가능성이다. Consumer와 무관한 native→target 및 privacy/release/shape 조건까지 precompute한다. Edge에서는 use site, guard, alias, temporal availability, actual/formal, runtime boundary gate를 추가로 판정한다.

Producer에서 모두 확정하면 edge-specific availability를 과대평가하기 쉽다. 모두 edge에서 다시 계산하면 fanout×rule×recipe만큼 privacy/shape 판정을 반복한다. Intrinsic offer를 한 번 공유하고 edge delta만 lazy하게 평가하는 방식이 가장 작다. M이 상수라면 producer offer 저장은 O(RM), edge descriptor는 O(Eᵦ)다. Edge×producerRule×consumerRule table은 기본적으로 저장하지 않는다.

### 3.2 단순 partition 집합으로는 충분하지 않다

동일한 `{LOCAL,ROW}`라도 다음 두 경우는 다르다.

~~~text
A: native LOCAL → DIRECT LOCAL / UPLOAD ROW
B: native ROW   → DIRECT ROW / COLLECT LOCAL
~~~

A의 LOCAL copy는 이미 base value지만 B에는 collect action이 필요하다. 현재 LocalMaterializationSelections.derive도 derived FED/FOUT의 base LOCAL이 있으면 불필요한 collect를 생략한다. [local-copy 도출][local-copy]

따라서 Offer에는 최소한 target, recipe, condition이 필요하다. 동일한 target에 의미가 서로 다른 recipe가 여러 개 있으면 모두 남긴다. Available에는 다음을 포함하지 않는다.

- 선택 전에 실제 복사본이 만들어졌다는 의미.
- Native 내부의 temporary broadcast를 다른 consumer가 재사용할 수 있다는 의미.
- Producer의 다른 rule에서 가능한 layout을 현재 선택한 rule에서도 사용할 수 있다는 의미.
- 다른 output slot이나 loop/call의 값을 동일한 copy로 사용할 수 있다는 의미.

### 3.3 최소 Java 자료구조

다음은 pseudocode이며 컴파일된 구현이 아니다. 실제 저장에는 dense IDs, flat arrays, shared schema를 사용할 수 있고 record마다 대량의 객체를 생성할 필요는 없다.

~~~java
enum Partition { LOCAL, ROW, COL, REP }

record Offer(byte target, short recipeId, int conditionId) {}

record Rule(
    int semanticRuleId,
    ExecType exec,                    // CP / FED
    byte[] requiredMasks,             // 기본은 singleton. role은 기존 Facts에서 조회
    byte[] nativeOutputBySlot,        // ordinary는 길이 1, multi-output만 여러 개
    int loweringVariant,
    int executionConditionId,         // native + privacy, 필요하면 별도 ID로 분리
    Offer[][] intrinsicOffersBySlot) {}

record MemoValue(int privacyFactsId, Rule[] rules) {}

record SupplyChoice(
    short recipeId, int creationSiteId, int cacheOwnerId, int retentionPolicyId) {}

MemoValue[] memoByOccurrence;         // occurrence → compiled owner는 Program Facts에 있음
BitSet[] activeRuleIdsByOwner;        // 동일한 실행을 consumer별로 복제하지 않음
int[] selectedRuleByOwner;
SupplyChoice[] selectedSupplyByBinding; // exact source/slot/guard는 Facts에 한 번만 저장
~~~

네 layout의 mask가 여러 tuple을 반드시 독립적인 product로 허용하는 것은 아니다. Conditional native pattern의 상관관계는 executionConditionId에서 기존 predicate를 참조한다. Input access role이 native variant에 의존하면 Facts의 role query에 variant를 전달한다.

CP의 metadata-only input은 필요한 metadata layout을 허용하고, payload용 Offer/COLLECT query를 우회해 isMetadataAvailable을 사용한다. 새로운 다섯 번째 partition이나 METADATA payload copy는 만들지 않는다. DIRECT recipe를 사용하는 경우에도 metadata access이지 완전한 LOCAL MatrixObject의 공급은 아니다.

Oracle cache에는 pure native tuple/schema가 들어가며, privacy가 붙은 Rule 전체가 들어가는 것은 아니다. Rule의 condition과 offers는 occurrence의 privacy/value facts에 scope한다. 조건 ID는 기존 facts/predicate에 대한 참조이며 새로운 일반 논리 언어를 도입하지 않는다.

HopId 단독 대신 기존 occurrence/compiled owner/value-version 정보를 compact ID로 intern한다. Memo는 occurrence별 조건을 참조하지만 선택 변수는 **동일 compile epoch의 execution owner별 하나**다. 같은 template를 호출하는 두 context는 actual binding·privacy·shape·빈도를 공유하지 않는다. 같은 owner의 semanticRuleId는 공통이며 모든 활성 context에서 합법적이어야 한다. 실제 clone 또는 새 recompile epoch만 독립 owner로 둔다. 정적 value version과 output slot에 loop iteration/call invocation의 동적 수명 구분을 더하되, 반복을 Memo에 unroll하지 않는다. [기존 occurrence identity][identity], [value version][value-id]

SupplyChoice의 recipeId는 선택된 producer rule/output slot과 함께 recipe를 유일하게 정해야 한다. Native mode·primitive 순서·실제 lowering이 다르면 같은 ID로 합치지 않는다. 동일 recipe의 여러 조건을 공유한다면 그 조건의 정확한 disjunction을 보존한다. short는 설명용 compact 형식이며 실제 ID 개수의 상한을 보장하지 못하면 int를 사용한다.

### 3.4 Shared movement/materialization

**[확인]** LocalMaterializationSelections.derive는 선택된 consumer demand를 producer별로 모은다. ExactMaterializationActivation은 guard의 공통 부분/배타성 등을 사용해 activation을 계산한다. 기존 action key에는 source value와 scope가 포함된다. [demand grouping][local-copy], [activation][activation], [action identity][action-identity]

**[제안]** SupplyChoice는 edge/binding별 요구이며 materialization의 실체와는 별개다. 선택 후 동일한 exact value/slot, dynamic value epoch, target, primitive step, creation/cache owner 및 양립 가능한 retention을 grouping한다. 실제로 동일한 cache instance를 공유할 수 있을 때만 한 번 과금한다.

- Branch별 공급은 guard 조건부다. Hoist는 값의 존재·privacy·실제 lowering이 성립할 때만 허용한다.
- 동일한 static HOP이라도 loop iteration의 결과는 서로 다른 값이다. Loop invariant copy를 재사용하려면 runtime lifetime도 필요하다.
- 동일한 formal 이름이라도 call별 actual/result는 서로 다르다. Compiled code의 공유가 compute 결과의 공유를 뜻하지는 않는다.
- D1 on에서는 COLLECT와 UPLOAD를 별도 action으로 두고, 중간 LOCAL을 여러 target이 공유할 수 있는지 검사한다.
- 여러 합법적 schedule에 따라 비용/feasibility가 달라지면 작은 schedule selector를 S에 넣는다. 고정 policy라면 그 제한을 명시한다.

Native 내부 통신 비용은 kernel cost가, 외부 공급 action은 movement cost가 한 번만 소유하게 한다. PlacementCostSemantics의 wdivmm/ctable/fused preparation 등을 단순 partition-pair 비용으로 바꿀 수는 없다. [native cost][native-cost]

## 4. Privacy 적용 시점과 조건부 pruning

### 4.1 Oracle 직후 할 수 있는 것과 할 수 없는 것

**[확인]** PrivacyFact는 occurrence/valueVersion에 연결된다. ExecPlacementPolicy는 PRIVATE/아직 해제되지 않은 PRIVATE_AGGREGATE의 origin residency를 검사하고, CandidatePrivacyInputPruning은 output label뿐 아니라 정확한 protected input position과 access kind를 검증한다. [privacy facts][privacy-facts], [policy][privacy-policy], [input mask][privacy-mask]

| 판정 대상 | Oracle 이후 확정되면 삭제 가능 | 조건이 남는 경우 |
|---|---|---|
| Rule 실행 | Native variant가 항상 금지된 payload access/release를 수행 | input binding, shape, release, fine-grained 조건을 execution predicate로 남김 |
| Output recipe | Protected output의 COLLECT처럼 해당 primitive 이동이 불법 | rule/slot/환경에 따라 달라지는 조건부 Offer로 남김 |
| 특정 input combination | fixed consumer rule과 binding에서 어떤 producer 선택으로도 legal input을 공급할 수 없음 | 해당 relation에서만 support를 제거. Producer 전체를 금지하지 않음 |
| Shared static rule | 실행이 필요한 환경 중 하나에서라도 합법적인 completion이 전혀 없다고 증명됨 | 각 환경에 서로 다른 witness만 있으면 공통 R의 존재 여부는 미확정 |
| Metadata/function handle | Payload와 다른 기존 access semantics를 따름 | Handle이 LOCAL이어도 formal payload의 이동을 허용한 것은 아님 |

**양화사:** 올바른 정적 계획은 "공통 R과 guarded S가 존재하고, 그것이 모든 관련 환경에서 합법적"이어야 한다. 환경마다 서로 다른 R을 선택할 수 있다는 뜻이 아니다. Known-inactive context는 제외하지만 unknown branch나 추정 확률 0은 검사 대상에서 제외하지 않는다.

Rule의 conditional input combinations 중 하나가 불법이더라도 rule 전체를 삭제하지 않는다. 반면 rule 실행이 필요한 어떤 환경에서 합법적인 completion이 **하나도** 없다고 증명할 수 있으면 해당 shared rule은 사용할 수 없다. 보수적으로 추가된 환경 때문에 거부되는 경우에는 실제 runtime의 불가능성이 아니라 선언한 추상 환경 모델의 제한으로 기록한다.

### 4.2 Privacy propagation을 먼저 완료할 수 있는 범위

Source label, 일반 semantic transfer, CFG/branch/loop의 privacy facts는 가능한 범위에서 Memo보다 먼저 닫는다. 순환에는 필요한 SCC fixed point를 수행한다. 그래도 다음 query는 선택/edge에 남는다.

~~~text
actual guarded input/privacy bindings
native variant의 payload access와 aggregation release
producer option의 각 primitive movement
function formal/return과 재컴파일 경계
fine-grained 조건, metadata access, selected lifetime/availability
~~~

모든 source의 privacy를 가장 엄격한 label 하나로 join하면 안전하지만 합법적인 plan을 불필요하게 제외할 수 있다. 현재 privacy 추상 의미를 지키고 필요한 guarded fact를 남긴다. 단순한 4-state planner라는 이유로 fine-grained privacy를 삭제해도 되는 것은 아니다.

최소 반례는 PrivateAggregation X에 대한 sum(X)다. 합법적인 FED aggregate의 LOCAL 결과와 X를 raw COLLECT한 뒤 수행하는 CP sum은 다르다. Native 결과가 PUBLIC이어도 input collect를 허용할 근거가 되지는 않는다. [privacy legal-space 회귀][privacy-parity]

### 4.3 Cache와 검증

Stable snapshot에 privacy semantics/config, W/movement policy, compile epoch를 고정하고 query key에 semantic variant, input position/output slot, access role, exact privacy fact/binding ID, release/shape 조건, recipe ID를 포함한다. 조건부 결과는 boolean으로 축약하지 않고 condition ID로 cache한다. 시간적 availability와 domain support는 별도 cache로 두고 use context/domain revision/backtrack을 포함한다.

Early pruning 검증은 tiny exhaustive fixture에서 **합법적인 assignment 집합이 불변**임을 확인한다. Same optimum만으로는 충분하지 않다. Conditional privacy를 PUBLIC으로 완화하는 오류와 일부 private 환경을 이유로 모든 rule을 삭제하는 과도한 pruning을 모두 포함한다. Function handle, metadata, unknown shape, loop/branch의 기존 parity 테스트를 기준으로 삼는다.

**복잡도:** 공유 privacy predicate 수를 P, miss 시 평가 비용을 Tprivacy라고 하면 기본적인 고비용 계산은 O(P×Tprivacy)다. Edge별로 필요한 context query가 추가된다. Label만 사용하는 cache는 작지만 잘못되며, exact-context의 무제한 cache는 커질 수 있으므로 byte/entry budget을 둔다. Eviction은 재계산만 늘리고 후보를 제거하지 않는다.

## 5. Multi-parent와 Control Flow의 최소 constraint

### 5.1 필수 정보와 새로운 제한의 구분

| 구조 | Memo 이전의 Program Facts | 사전 propagation | Global까지 남기는 선택/제약 | 새로운 baseline 제한과 복잡도 |
|---|---|---|---|---|
| Multi-parent | HOP input position, 동일 execution owner, value/slot, use sites | 각 edge의 R/S support를 양방향으로 삭제 | 하나의 R[A], 모든 use에서 양립하는 S, 공유 copy/schedule/cost | 재계산/clone을 도입하지 않는 방침은 새로운 제한. E개 edge에서도 joint 해는 지수적일 수 있음 |
| Transient | exact reaching defs, write/read 시점, value version, alias, guard | 잘못된 writer/future source, 공급 불가능한 boundary state 삭제 | 각 mandatory writer 환경의 공급, alias movement/lifetime | 단순 변수명 map은 불가. Facts 고정점과 guard relation F가 필요 |
| If-Else | structured guard, correlated definition tuple, missing-else pass-through, reachability | known-dead arm 제외, branch exit→join 계약의 무-support 삭제 | 모든 도달 환경에 공통인 compiled R, guarded S, activation cost | 고정 join partition은 언어의 필수가 아닌 baseline 제한. 독립 branch 환경 수는 최악의 경우 지수적 |
| Loop | entry/carried/backedge/exit, zero iteration, shape/privacy invariants, dynamic epoch | prospective domain에 entry/backedge 제약, SCC 국소 worklist | grounded entry + 모든 body path의 induction, 내부 cycle 금지, 반복 copy 비용 | 동일 epoch에서 body 고정, iteration 재배치를 새로 도입하지 않음. 반복을 unroll하지 않고 contract로 검사 |
| Function | actual/formal/return position, callsite, same compiled owner, clone/epoch, 빈도 | active call contexts의 shared semantic R support를 축소 | 호출별 S/shape/privacy/cost, owner 공통 R, return 공급, recompile invalidation | 검증되지 않은 callsite cloning은 추가하지 않음. 재귀는 현재 exact frequency 지원 범위 밖 |

"사전 propagation 완료"는 joint legality가 모두 증명되었다는 뜻이 아니다. Local relation의 support가 남아 있어도 상호 모순이 있을 수 있으므로 최종 계획을 동일한 의미론과 독립 checker로 검증한다. 별도의 일반 Constraint Graph로 복사하지 않고 HOP edges와 typed facts에 대한 역색인을 만들면 된다.

### 5.2 최소 DML과 허용/금지 예

~~~dml
# Multi-parent
A=abs(X); B=A+U; C=A+V; Z=sum(B)+sum(C);
~~~

U/V 측 Oracle가 요구하는 input pattern에 따라 A의 단일 native rule에서 필요한 공급을 선택한다. A를 ROW와 COL에서 각각 실행했다고 가정해 합산해서는 안 된다. 추상 fixture에서 B는 ROW만, C는 COL만 요구하고 A의 LOCAL 계산 비용이 3, ROW/COL 계산 비용이 4, 각 upload 비용이 2이며 D1이 off라면, tree별 최적은 서로 모순되고 공동 해는 LOCAL+두 upload의 7이 된다. 이 수치와 requirement는 solver 반례이며 이 DML의 실측 비용이 아니다. [공유 cost 회귀][sharing-test]

~~~dml
# Transient
X=A; Y=abs(X); X=B; Z=abs(X);
~~~

Y는 이전 definition을 읽고 Z는 새 definition을 읽는다. 이름이 같다는 이유로 동일한 supply cache를 사용하지 않는다. Conditional writer는 facts의 guard로 resolve하며 optimizer의 source-choice로 만들지 않는다. 현재 LogicalTransientInputFact와 strict/logical boundary factor의 의미를 재사용한다. [transient facts][transient-facts], [transient factor][transient-factor]

~~~dml
# If-Else
if(p){X=A;Y=B;}else{X=C;Y=D;}
Z=X+Y;
~~~

(A,B)/(C,D)만 허용하고 (A,D)/(C,B)는 금지한다. `X=ite(g,A,C)`, `Y=ite(g,B,D)`처럼 동일한 guard 참조로 유지할 수 있다. Join 이후 layout 고정을 선택하면 두 branch exit가 해당 contract를 공급해야 한다. Branch 내부 native layout은 동일하지 않아도 된다. 현재 JointInputAnalysis는 structured correlation을 보존하지만 서로 다른 if의 predicate 식이 동등한지까지 증명하지는 않는다. [joint facts][joint-facts], [branch normalization][branch-normalize]

~~~dml
# Loop
A=X;
while(p){ A=abs(A); }
Z=sum(A);
~~~

Entry X에서 head로의 합법적인 공급과, 가정한 head에서 각 backedge로의 보존을 요구한다. Loop body의 미래 결과를 entry source로 삼지 않는다. 초기에 backedge proof가 아직 없다는 이유로 잠재 rule을 제거하지 않는다. 0회 실행 시에는 X가 exit를 공급한다. 비용은 entry 1회 + E[N]×body/새 값의 movement + E[N+1]×predicate + exit이며, 실제로 retained되는 loop invariant copy만 반복 간에 공유할 수 있다. [loop provisional][loop-seed], [grounded 고정점][loop-ground], [loop 회귀][loop-test]

~~~dml
# Function
f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}
A=f(P); B=f(Q);
~~~

동일한 compiled FunctionProgramBlock에는 동일한 body R을 사용하고, P/Q actual, return slot, privacy, execution cost는 호출별로 구분한다. 실제로 clone/specialize된 body나 새로운 recompile epoch만 별도 owner로 둘 수 있다. Known-inactive call을 body domain의 교집합에 포함하지 않는다. 재컴파일 시에는 새 snapshot에서 기존 boundary/upload 제약을 검증한다. [runtime call][function-call], [body 실행][function-block], [재compile][recompiler], [경계 구성][function-boundary]

### 5.3 작은 joint 관계로 충분한가

필요한 공동 상태는 R[owner], S[exact input binding], 필요한 join/loop/formal boundary partition P, 비용에 영향을 주는 schedule selector다. P를 read/alias rule에서 도출할 수 있으면 독립 변수를 만들지 않는다. Multi-output은 owner R에서 slot layout을 도출하며 carrier R은 만들지 않는다.

Branch environment를 모두 열거하는 대신 기존 region/guard를 공유하고 관련 definition만 projection한다. Loop는 entry/backedge tuple과 induction을, function은 call binding과 shared body mapping을 사용한다. 임의의 correlation을 항상 O(N+E)로 표현하고 해결할 수 있다고 주장하지 않는다. 현재 JointInputAnalysis는 explicit environments도 보관하므로 이를 그대로 재사용하는 초기 단계에서는 F의 팽창이 남는다. 향후 delta/persistent guard 표현으로 바꾸려면 동일한 correlation/provenance를 검증한다. [현재 joint loop][joint-loop]

### 5.4 공동 비용과 안전한 하한

**[제안]** 목적함수는 실행 context별 native 비용의 빈도 가중 합과 실제로 활성화되는 materialization action 비용의 합이다. 공유 compiled body는 call별 계산 비용을 각각 내며, 동일 dynamic value의 동일 copy만 한 번 낸다. Branch 비용은 predicate 비용과 각 arm의 조건부 기대 비용을 합한다. 공유 action의 activation은 use guard들의 합집합이므로 use별 확률을 단순 합산하거나 서로 독립이라고 가정하지 않는다. Loop 비용은 §5.2처럼 entry/body/predicate/exit를 구분한다. 이는 추정 빈도를 사용하는 cost model의 최적화이며 실제 wall time의 수학적 최적화라는 뜻은 아니다.

최초 B&B 하한은 잔여 owner별 최소 native 비용처럼 **비음수이고 중복되지 않는 항**만 더한다. 아직 공유 여부를 모르는 movement는 0으로 완화하고, 피할 수 없는 action임을 증명한 경우에만 한 번 추가한다. Shared copy를 consumer마다 독립 과금한 합은 과대 하한이 될 수 있다. 부동소수점 반올림으로 하한이 실제 model cost를 넘지 않도록 기존 exact cost 표현을 재사용하거나 보수적으로 계산한다. 동일 최적 비용의 tie 선택도 보존하려면 단순 비용 비교에서는 LB > incumbent일 때만 자르고, 같을 때는 tie dominance가 별도로 증명되어야 한다. [기존 비용 표현][dyadic]

## 6. Canonical Native Rule을 충족하지 않는 Operator와 대응

### 6.1 실제 불일치와 최소 대응

아래의 "제외"는 operator 전체가 아니라 특정 native path의 제외를 뜻한다. Source의 native capability 판정과 planner의 canonical scope 판정은 별도 reason으로 둔다.

| Operator/path | 현재 확인된 동작 | 초기 대응과 실제 변경 위치 | Correctness/비용/복잡도 |
|---|---|---|---|
| Right indexing X[1:10,] | FederationMap.filter로 worker subset이 되는 경우 | Exact effect가 all-W/canonical이면 채택. 나머지는 native scope에서 제외하고 합법적이면 명시적 CP rule로 전환 | subset 결과를 ROW로 다시 명명하지 않음. CP에는 원래 X collect의 privacy/cost가 필요.[index][rt-index] |
| rbind/cbind의 partition 방향 append | bind로 map/ranges를 연결하며 동일 worker에 여러 range도 가능 | Canonical output과 일치할 때만 native 채택. 일반 대응에는 runtime 변경이나 normalization이 필요 | 모든 worker가 동일하다는 사실만으로 canonical은 아님.[append][rt-append] |
| rmempty | worker별 실제 잔여 행/열 수에 따라 range 변경 | Canonicality를 증명할 수 있는 variant만 허용. 알 수 없으면 unresolved/scope 제한으로 기록 | 40/10 결과를 canonical 25/25로 취급할 수 없음. operator 전체 금지는 과도함.[rmempty][rt-rmempty] |
| roll | shifted range와 wrap-around split | range effect 검증. 일반 FED output은 baseline 밖일 가능성 | 이름이 ROW여도 DIRECT canonical은 아님.[roll][rt-roll] |
| FULL source W>1 | single full object는 all-W replica가 아님 | REP로 rename 불가. Ingress 거부, 향후 명시적 import만 허용 | Protected data의 collect normalization이 불가능할 수 있음.[FTypes][ftypes] |
| LOCAL upload rows<W/cols<W | 현재 allocator는 empty partition을 지원하지 않아 거부 | 대응하는 ROW/COL recipe 제거. REP는 별도 검증 | all-W를 지키기 위해 worker subset으로 바꾸지 않음.[ranges][rt-ranges] |
| REP operand + ROW/COL kernel | Full REP가 LOCAL처럼 sliced된다고 보장할 수 없음 | 기존 operand shape guard 유지 | REP가 가장 강한 공급 상태라고 할 수 없음.[broadcast][broadcast-compat] |
| Unary abs 등의 BROADCAST input | 현재 UnaryElemwiseRule에는 해당 input을 CP로 판정하는 경로가 있음 | REP 통합만으로 새 FED rule을 열지 않고 runtime/Oracle parity 확인 | Oracle의 현재 제한과 runtime 미검증을 구분.[unary rule][unary-oracle] |
| Aggregate/MM의 forced output | Native 내부에 partial 집계나 temporary broadcast가 있으며 persistent REP 결과를 직접 만드는 MM 경로도 있음 | 검증된 persistent REP native variant 유지. LOCAL native 결과의 REP 공급은 명시 UPLOAD로 표현 | 임시 broadcast를 REP offer로 간주하거나 raw collect와 aggregate release를 혼동하지 않음.[MM][rt-mm], [aggregate][rt-aggregate] |
| transformencode | FED primary matrix + LOCAL metadata | Owner R과 slot layout을 결합. Spec별 canonical effect 확인 | Carrier별 compute/exec를 선택하지 않음.[multi-output][rt-multi] |
| Transpose t(X) | Operator 자체가 axis/ranges를 변경 | Native output이 canonical이면 ROW↔COL 허용 | 동일 값의 movement RESHARD가 아니라 새 값의 native semantics.[transpose][rt-transpose] |

### 6.2 제외/CP/runtime 변경/normalization 비교

| 선택 | 유지되는 것과 바뀌는 것 |
|---|---|
| Canonical adapter에서 path 제외 | 최소 구현. 다만 기존 legal workload의 범위가 좁아짐. Runtime unsupported와 구분해 진단 |
| 명시적 CP alternative | Privacy상 raw input을 LOCAL로 만들 수 있고 CP kernel이 지원할 때만 유효. Collect/compute/upload를 최적화 전에 과금. Runtime fallback이 아님 |
| Native runtime 수정 | Privacy상 collect할 수 없는 사례를 지원할 여지가 있음. 다만 all-W/canonical을 충족하는 실제 처리와 추가 통신을 operator별로 검증해야 하므로 이번 단순화 초기 범위를 벗어남 |
| 명시적 normalization | noncanonical 중간값의 표현·collect/import·재upload·lifetime과 privacy를 추가로 처리해야 함. 네 state 뒤에서 암묵적으로 실행하지 않음 |

초기 권고는 "canonical source + 검증된 native path + 합법적인 명시적 CP 대안"이다. Noncanonical external source는 입구에서 거부하고, 필요성이 입증된 경우에만 planner 밖의 명시적 import 계약을 별도로 검토한다. W 밖/subset에서의 import 자체도 fixed-W 실행 범위와 충돌할 수 있다. Private source라는 이유로 자동 collect를 통해 바로잡을 수는 없다.

### 6.3 D1: ROW→LOCAL→COL을 허용해야 하는가

**[제안] 초기 연구 구현에서는 off를 권고하지만 확정된 요구사항은 아니다.** 단순성을 우선하는 support restriction이며 canonical 의미가 필연적으로 금지하는 것은 아니다. 필요한 workload의 공통-domain 평가에서는 on/off 양쪽을 서로 다른 계획 공간으로 취급한다.

사용자가 미결정으로 둔 핵심은 ROW↔COL 우회다. 아래 초기안은 구현을 단일 primitive recipe로 제한하기 위해 REP를 포함한 **동일 값의 FED→LOCAL→FED 복합 공급 전체를 제외하는 더 강한 제안**이다. 이 추가 제한을 canonical 계약의 필연적 결과로 취급하지 않는다. 향후 ROW↔COL만 금지하고 REP 관련 우회를 허용한다면 두 정책을 분리하고, 중간 REP/alias를 경유해 금지된 axis 변환을 우회하지 않도록 원래 값과 layout provenance를 추적해야 한다.

| D1 | 최소 제약과 비용 |
|---|---|
| off | 동일한 logical value의 FED p→collect→transparent aliases→upload q를 금지. LOCAL alias에서 origin을 잊지 않음. Native compute가 새 값을 만드는 chain은 별개 |
| on | Privacy상 합법적인 COLLECT와 UPLOAD의 명시적인 두 step으로 제한하고, 두 runtime lowering, 원래 값/중간 LOCAL의 존재와 lifetime, canonical target을 검증. 공통 LOCAL collect와 동일 target upload는 exact value·creation site·cache owner·lifetime이 맞을 때 각각 dedup |

D1 on은 ROW↔COL 직접 RESHARD를 도입하는 것이 아니다. p→LOCAL→p의 불필요한 round-trip은 보통 DIRECT로 대체할 수 있지만, lifetime/ownership을 바꾸는 의미가 없음을 확인한 뒤 dedup한다. Policy에서 모든 schedule을 임의로 동일시하지 않는다.

Native transpose 등은 논리값과 shape를 바꾸는 operator이며 movement와는 별개다. Rule의 native output을 단순히 input layout과 같도록 고정하면 합법적인 native 변환을 제외하게 된다.

### 6.4 Anchor 없는 runtime 재사용을 위한 최소 변경

**[확인]** FEDFoutInstruction.processInstruction은 현재 anchor/registry를 해석하고, FEDLocalMaterializeUtil.materializeLocalToAnchor/plannedMaterializationRanges는 anchor map을 사용한다. 새 planner가 W를 갖는 것만으로는 이 API 의존성이 사라지지 않는다. [upload instruction][rt-upload], [materializer][rt-materializer]

**[제안]** 기존 upload 처리에서 다음 두 부분을 분리한다.

~~~text
canonicalRanges(orderedW, shape, partition)   // pure geometry, source 불필요
materializeLocal(value, orderedW, ranges, targetType) // 기존 PUT/data 처리 재사용
~~~

Emitter는 snapshot의 단일 고정 W descriptor ID와 target layout을 전달한다. 가상의 data-bearing anchor MatrixObject를 만들어 기존 API를 속이지 않는다. 기존 FED instruction의 LOUT/FOUT 표기와 runtime FType은 lowering detail로 남겨도 되며 Memo state로 되돌릴 필요는 없다. W1→REP는 runtime variant와의 호환성을 확인한 adapter로 처리한다.

이는 **upload 경계 수정**이며 모든 native operator를 자동으로 canonical화하는 방법은 아니다. Native opcode별 map effect는 계속 검증한다. Boundary recompile의 CP→FOUT 금지와 TRead/TWrite의 실제 lowering 제약을 enum 변경만으로 해제하지 않는다. [boundary policy][boundary-policy]

## 7. Immutable Memo, 양방향 propagation, search

### 7.1 최소 state와 역색인

~~~text
immutable: Memo, pure native schema cache, closed/guarded Program Facts
mutable: active BitSets, domainReady, revision, queue, undo trail
indexes: owner→uses, consumer→input positions,
         value→guarded reads, bodyOwner→call contexts,
         nativeOwner→output slots, variable→관련 joint/loop/boundary facts
~~~

DomainReady는 객체 생성 완료가 아니라 coverage 완료를 나타낸다. Unresolved capability나 아직 생성하지 않은 domain은 빈 domain이 아니다. Shared owner에서는 native variant 열거뿐 아니라 모든 관련 활성 context의 조건 등록도 완료되어야 한다. Irreversible pattern 미생성은 root에서 증명된 제외에만 적용한다.

### 7.2 통합 pseudocode

~~~text
buildAndSolve(program):
    begin immutable analysis epoch
    F = closeFactsWithEarlyAuthoritativeChecks(program)
    validateCanonicalIngress(F)
    create structural reverse indexes once
    create supply/boundary domains from policy and F

    for u in distinct compiled execution owners, dependency-friendly order:
        // context별 pure native schema를 재사용하고 공통 semantic variant ID로 묶음
        for each native semantic case and its active context conditions:
            if guaranteedNativeOrPrivacyRejection(case,F): record; continue
            if rootClosedInputViewsProveNoSupport(case,rootDomains,ready,F):
                record; continue
            attach occurrence-specific privacy/offer views to this semantic case
            append one semantic rule; no source product, no physical alternative
        freeze occurrence Memo views for u; initialize active[u] exactly once
        ready[u] = nativeCoverageComplete(u) && allActiveContextsRegistered(u)
        enqueue ready incident relations
        if propagate() proves contradiction: return INFEASIBLE_FOR_DECLARED_SCOPE

    enqueue all remaining mandatory relations
    if propagate() proves contradiction: return INFEASIBLE_FOR_DECLARED_SCOPE
    exactSearch(R,S,boundaries,scheduleSelectors)
    return only verified optimum/infeasible, or explicit incomplete with incumbent

propagate():
    while queue has c:
        if c is proven inactive: continue
        if scope not ready: subscribe to ready event; continue
        if same scope revisions already fully checked: continue
        for each variable/value in c:       // producer/consumer/S 양쪽을 포함
            status = support(c, fixed variable=value, active, F)
            if status == NO_SUPPORT:
                trail.delete(value); bump revision
                if required active variable is empty: return model contradiction
                enqueue all incident relations
            // UNKNOWN은 유지. Revision 변경 중의 이전 결과를 최신 완료로 보지 않음.

search(partial):
    propagate affected relations
    if proven contradiction: return
    if verified incumbent and certified lowerBound > incumbentCost: return
    if complete:
        decode unique canonical schedule from selected choices
        independently check all active environments, loop entry/induction,
            exact values/slots, privacy, lifetime and emission gates
        if legal: evaluate cost and update canonical incumbent
        if unresolved: mark outstanding validation/coverage gap
        return
    choose smallest relevant matching domain; assign lazily; recurse
    undo; bump revisions of restored domains
    invalidate/revalidate affected cached checks before sibling search
~~~

Initial/current unknown, resource limit, unresolved leaf가 남으면 OPTIMAL/INFEASIBLE을 반환하지 않는다. 선택 후보가 있으면 FEASIBLE_BUT_OPTIMALITY_UNPROVEN을, 없으면 VALIDATION/RESOURCE_INCOMPLETE를 반환한다. 미해결 부분이 sound bound 등으로 불필요하다고 증명된 경우에만 완료로 처리할 수 있다.

### 7.3 Cache 대상과 재검사 대상

Pure native signature/pattern cache는 shape/config/runtime revision에 scope하고 privacy와 source를 섞지 않는다. Pure layout/role compatibility cache와 exact edge availability는 분리한다. Support witness는 relation/value/guard/context와 domain revision을 검사하고, backtrack 이후 negative cache를 그대로 사용하지 않는다.

Producer 후보 삭제는 모든 use로, consumer pattern 삭제는 각 mandatory producer/S로 전파한다. Same compiled body의 call contexts와 shared materialization/joint 관계도 incident index에 포함한다. 일반 HOP parent list만으로는 충분하지 않다. No-support는 필요한 relation의 모든 completion이 없다는 증명이며, 가장 저렴한 producer가 부적합했다는 뜻이 아니다.

Facts의 reaching definitions·shape/privacy·loop/call summary fixed point는 남는다. Candidate rebuild나 SupportClause/Proof route 재생성과는 별개다. 새로운 shape/program/W/compile epoch는 새 snapshot이며, 미래의 recompile까지 하나의 Memo를 계속 재사용하지 않는다.

## 8. 복잡도와 기존 Optimizer 재사용

### 8.1 단계별 시간·공간

N=occurrences, R=ΣKᵢ, L=ΣKᵢ×pattern 길이, Eᵦ=guarded bindings, M=recipe 상한, F=Program Facts, I=역색인 incidence, Q=search nodes, B=bounded cache다. Bounded arity·constant M·단순 edge라면 기본 O(NK+EM)을 목표로 할 수 있지만 전체 planner의 상한은 아니다.

| 단계 | 새 설계의 시간 | 저장량/재계산 감소 | 남는 최악 조건 |
|---|---|---|---|
| Program Facts | 필요한 block/SCC의 transfer 방문 합계 | F와 dependency index. Stable facts는 재생성하지 않음 | Correlated 환경/호출 context는 커질 수 있음 |
| Oracle | Unique semantic miss×native 평가+pattern 생성 | O(R+L), pure cache B | 4^arity나 runtime variant 수. Cache eviction 시 재평가 |
| Privacy/offer | Unique predicate/recipe 조건 평가 | O(RM)+조건 참조 | Fine-grained/guard 의존 predicate 비용 |
| Required-layout matching | BitSet intersection, 실제 matching rule/recipe scan | 네 개의 rule mask/slot 공유. 일반 edge에 full KpKc 표를 만들지 않음 | mask 자체는 conditional feasibility의 충분조건이 아님 |
| Binary propagation | naive 1회 O(KpKcM), 실제 재방문 합계 | active domains+I+bounded witness | 동일한 pair를 재검사할 수 있음 |
| Joint support | 방문한 local assignments×guard/loop/privacy 평가 | stream/backtracking state, 작은 관계만 저장 | 높은 arity relation과 guard 검증은 지수적 |
| Cost/schedule | Native 단가 lookup, selected demand grouping, activation union | 선택한 action 수와 bounded cache | 많은 leaf에서 schedule 평가를 반복할 가능성 |
| Exact B&B | Q×propagation/bound/checker | DFS trail+current/best plan+B | Q가 전체 domain product에 가까워질 수 있음 |
| Selected emission | 선택한 rule/supply/actions 및 worker 명령 수에 비례 | 선택 결과만 저장 | Source privacy/runtime boundary의 독립 검증이 필요 |

Concrete source tuples, SupportClause, Proof route, 선택되지 않은 Physical Alternative와 dense cost table은 **새 경로의 표준 표현에서** 제외할 수 있다. 다만 guarded source identity, joint support, owner selection, activation/lifetime은 없앨 수 없다. 동일한 product를 lazy하게 여러 번 순회하는 구현은 시간이 solver로 이동할 뿐이므로 방문/중복/할당을 반드시 계수한다.

### 8.2 B&B / Exact / Local 비교

**[확인]** COMPILE_COST_BASED는 FederatedPlanLocalCost로 들어가며 Local/Regional의 default는 relativeGap=0.05, earlyStop=true다. TARGET_REACHED, EXACT, RESOURCE를 구분한다. 별도의 COMPILE_EXACT는 global variable elimination을 사용한다. [factory][factory], [local entry][local-entry], [gap/stop][local-gap], [exact entry][exact-entry]

| 방식 | 유리할 수 있는 조건 [해석] | 불리한 점/재사용 시 주의 |
|---|---|---|
| Direct lazy B&B | 강한 feasibility 전파, 강한 cheap 하한, 적은 leaf, 큰 VE width | Shared movement를 0으로 두는 하한은 약함. 유사한 prefix에서 joint/cost를 재계산할 수 있음. 공급/schedule 변수도 증가 |
| Existing Exact VE | Low induced width, compact finite/conditional support, 공통 부분 문제의 message 재사용 | Dense한 고-width factor/backpointer가 팽창. Generic lazy는 freeze 과정에서 모든 tuple이 평가됨 |
| Existing Local/Regional | Feasible seed에서의 국소 개선, regional boundary lower bound, 지정 gap까지의 anytime 계획 | Default 5%는 global optimum이 아님. Frozen reduced root나 boundary message와의 결합이 강함 |

VE의 대표적인 dense 상한은 시간 O(Vd^(w+1)), message/backpointer 공간 O(Vd^w)다. B&B는 이 table을 피하기 쉽지만 시간은 마찬가지로 지수적일 수 있다. 어느 쪽의 지배 요인이 LogReg/GLM에서 더 큰지는 현재 artifact로 알 수 없다.

**[확인]** ExactCategoricalSolver의 generic Factor.lazy는 freezeValidatedFactor/freezeGenericFactor에서 전개된다. ExactPhysicalCostModel도 optimizer 이전에 ordinary factors를 freeze한다. Memo predicate를 Factor.lazy로 감싸는 것만으로는 non-materialization 설계가 되지 않는다. [solver freeze][solver-freeze], [cost freeze][cost-freeze]

기존 finiteSupport/conditionalSupport/functionalMap과 support-revision의 의미는 재사용할 수 있다. 그러나 Local block solver/RegionalSearchProblem/selection은 physical model·frozen surface identity에 결합되어 있다. Direct Memo의 drop-in backend가 아니다. LocalCategoricalOptimizer의 block-local DFS도 global guard/lifetime B&B를 대신하지 못한다. [factor API][factor-api], [support revision][support-revision], [regional input][regional-input], [local block][local-block]

### 8.3 v50에서 판단할 수 있는 것

**[확인]** Frozen v50의 LogReg는 full planning 363.643초/Analysis 275.768초/optimizer 71.336초였고, GLM은 125.151초/77.512초/27.836초였다. W1 Docker에서 각각 한 번 관측한 결과이며 기존 성능 gate는 실패한다. Optimizer 내부의 width/cell/node/LB 강도는 이 기록만으로 비교할 수 없다. [v50 evidence][v50-map]

Proof overlay/topology/consumption의 큰 allocation은 줄일 대상이지만 필요한 관계 평가까지 사라진다고 할 수는 없다. Source-product를 만들지 않아 줄어드는 allocation, support query로 이동하는 CPU, 늘어나는 공급 변수/DFS 방문을 구분한다. v50의 5%-Local과 새 B&B exact의 wall time만 비교해 우열을 단정하지 않는다.

**[권고의 범위]** 이전의 "B&B를 초기 기본 방식으로 삼는다"는 권고는 연구자가 읽을 수 있는 reference 구현에 한정한다. LogReg/GLM production에 최적이라는 결론은 아니다. 새 Memo의 설계 가치와 solver의 실측 우열을 나누어 평가한다.

## 9. 실제로 변경할 함수와 최소 검증 계획

### 9.1 유지/변경/통합/삭제

| 분류 | 현재 class/function | 필요한 변경 |
|---|---|---|
| 유지/변경 | PlacementProgramFacts.analyze/analyzeCfg, PlacementJointInputAnalysis | Identity/def-use/guard/loop/call 재사용. Typed queries, ready/reverse-use index. 이후 필요하면 delta 환경 분석 |
| 변경 | RulesApi.FedOracle.caps / OpCaps, RulesCore.OracleEngine.prepare | Selected operator가 native variant 집합을 반환하는 계약으로 변경. Dispatch precedence 유지 |
| 변경 | OracleFacade.PreparedDecision.decideWithEvidence / prepareExecutionRelation | Pure case schema와 shape 증거를 여러 variant로 확장. Metadata/scalar/REP mapping 검증 |
| 통합 | PlacementCandidateGenerator.buildNode / processCombination / enumerateInputCombinations | Oracle-driven Memo builder로 축소. CP/forced-native variant는 Oracle adapter로, derived output은 Offer로 이동 |
| 분리 재사용 | ExecPlacementPolicy.decide/requiresOriginResidency, CandidatePrivacyInputPruning | Source/role 권한을 작은 조건 query로 분리. 선행 pruning과 최종 selected checker에서 공유 |
| 유지/변경 | PlacementProgramFacts.isMultiReturnBuiltinOutputCarrier, FunctionOp.optFindExecType | Owner/slot 정보 재사용. Carrier movement upload를 owner FED 실행으로 오인하지 않는 lowering으로 변경 |
| 분해 후 삭제 | PlacementRelationClosure.closePlacementAndFeasibility / pruneSupportWithWork | Facts 고정점과 active-domain 전파로 분리. Stable epoch의 후보 재생성을 새 경로에서 제거 |
| 제거 대상 | Source Owner/Anchor 후보, CandidateRealization/InputBinding의 후보 복제, SupportClause/Proof/NativeContinuity carriers | 기존 source identity/authority 의미는 facts/selected checker로 이동. Object 계층과 product 생성 제거 |
| 대체 | ExactPhysicalModel.alternatives / sortAndDeduplicateAlternatives | Native rule/edge 관계를 직접 사용. 모든 physical candidate를 생성한 뒤 dedup하지 않음 |
| 분리 재사용 | PlacementCostSemantics.prepareExecutionCost, ExactPhysicalCostModel | Native 단가와 내부 통신, activation 재사용. Ordinary factor freeze/Alternative 의존성을 새 경로에서 제거 |
| 변경 | LocalMaterializationSelections.derive, ExactMaterializationActivation.partition | Selected exact-value demands에서 primitive action/schedule 도출. Anchor 의존 key 축소 |
| Reference 유지 | ExactCategoricalSolver / ExactPhysicalReducedSolver / LocalPhysicalOptimizer | 공통 domain의 baseline, tiny exact oracle. 현재 frozen API를 lazy direct consumer로 오인하지 않음 |
| 변경 | FEDFoutInstruction.processInstruction, FEDLocalMaterializeUtil.plannedMaterializationRanges/materializeLocalToAnchor | 고정 W/canonical descriptor 입력을 추가하고 기존 upload 데이터 처리를 분리해 재사용 |
| 변경/유지 | ExactPhysicalPlacementProjector.project, PlacementEmissionTransaction.prevalidate/emitInternal | Native 선택과 exact supply의 작은 decoder로 변경. Transactional validation과 fallback 금지는 유지 |
| 최소 신규 | Rule/Offer/Supply 선택, BitSet worklist, direct exact search | 기존 package 내부의 작은 구현. 범용 Constraint Graph/Proof framework를 새로 만들지 않음 |

주요 위치: [Facts][facts], [joint][joint-facts], [Oracle][oracle-contract], [generator][generator-options], [privacy][privacy-policy], [Closure][closure], [physical model][physical-alternatives], [native cost][native-cost], [local copy][local-copy], [upload][rt-upload], [projector][projector], [emission][emission].

기존 class가 다른 planner에서도 사용되면 new lane만 대체하고 common class를 먼저 삭제하지 않는다. 기존 receipt의 Java object identity는 새 설계의 본질적인 요구사항이 아니지만 foreign/stale/withdrawn source 선택을 거부하는 의미는 유지한다.

### 9.2 최소 3단계 실험

**A. Tiny semantics와 pruning**

기존 Oracle 생성 공간을 semantic native variants로 사상하고 동일한 scope의 새 생성 결과와 비교한다. 모든 tiny assignment의 합법 집합·비용·canonical tie를 비교한다. 필수 fixture는 CP/native/forced-local coverage, metadata, LOCAL temporary broadcast 대 REP, multi-output, shared diamond, branch correlation, loop entry/모든 backedge/zero iteration, function call/epoch, transient overwrite, privacy release, D1 alias, canonical ingress/output이다.

Feasibility 삭제에서는 각 이벤트 이후 모든 legal assignment가 남는지 확인한다. Cost pruning에서는 verified incumbent, sound lower bound, 최적값/tie 유지를 별도로 검증한다. Native unknown, support budget, 아직 준비되지 않은 domain, backtrack 이후 negative cache도 포함한다. [기존 early parity][privacy-parity], [cost pruning 반례][cost-test], [grounding tests][grounding-test]

**B. Solver-only와 pre-freeze 비교**

1. 동일한 frozen PhysicalModel/CostSurface를 한 번 만들고 Local default 5%, Local exact-target, Exact VE, B&B exact를 비교한다. Prep/solve/종료 status/gap을 분리하고 exact끼리는 동일한 objective/tie/합법적 선택을 요구한다. 이는 solver 차이에 대한 실험이므로 table 회피 효과는 측정할 수 없다.
2. 다음으로 ExactPhysicalCostModel의 ordinary freeze 직전에서 동일한 semantic evaluator/descriptor를 사용해 freeze→기존 solver와 direct B&B를 비교한다. Model+Cost+Optimizer 총시간과 peak를 측정한다. [pre-freeze seam][cost-freeze]
3. 새 Memo에는 기존 Alternative identity가 없으므로 기존 selection에 억지로 맞추지 않고 semantic projection과 작은 decoder를 검증한다. Legacy same-domain 실험에서만 기존 selection/projector를 재사용할 수 있다.

**C. Workload 비교**

Frozen v50과 현재 dirty tree의 기준 engine을 분리하고, 동일한 canonical 지원 범위/D1/shape/privacy/비용 모델로 제한한 LogReg/GLM을 동일한 Docker 조건에서 측정한다. run_LAN_docker.sh를 사용하며 source/JAR/data/seed/W/network/CPU/memory/JVM/BLAS/trace 설정을 고정한다. 최소 5회의 paired 반복에서 cold/warm cache를 구분하고 W2+의 canonical micro-workload도 추가한다.

최소 counter는 native generated/active rules, first-cause 제외, Oracle request/miss/실행/intern, offer/pair/joint query, worklist 방문/deletion, source tuples/SupportClause/Alternative의 실제 생성, solver nodes/LB/leaf/schedule, encoded/logic factor cells, Analysis/Cost/Search/fullInitial, 누적 allocation/heap/RSS/cgroup peak다. Rule 수 감소만으로 성공이라고 판단하지 않는다.

제거 이벤트에는 semantic rule/pattern-region ID, 단계, reason, 필요한 facts/domain이 준비된 event ID, 실제 제거 event ID를 기록한다. Earliest lag는 동일한 sound checker를 준비 이벤트부터 replay하여 측정하며, 구현하지 않은 global impossibility oracle의 시점을 주장하지 않는다. 중복 검사도 rule뿐 아니라 query signature별로 세어 cache miss와 revision에 따른 정당한 재검사를 구분한다. Lazy source tuple 방문 수와 실제 tuple 객체 할당 수를 따로 측정해야 CPU가 solver로 옮겨간 사실을 확인할 수 있다.

Factor는 논리적 domain product, 실제 저장 cell/bytes, VE의 최대 separator/중간 factor, B&B의 최대 trail/cache와 반복 subproblem 방문을 각각 기록한다. JFR 등의 allocation 계측은 sampling/recording 설정을 고정하고 계측 run과 일반 timing run을 분리한다. 측정 부하를 새 설계의 회귀나 개선으로 해석하지 않는다. 수치 기준은 구현 전에 동결하고, 어느 한 workload의 평균 개선만으로 tail time·peak·지원 범위의 회귀를 통과시키지 않는다.

### 9.3 보장을 주장하기 위한 조건

| 주장 | 필요한 검증 |
|---|---|
| Soundness | 모든 active 환경, exact value/slot, joint/loop/privacy 조건에서 수락한 계획이 합법적 |
| Completeness | 명시한 canonical+D1+static/schedule+guard 추상 범위의 기존 legal plan에 새 assignment가 대응. 미검증 variant를 숨겨서 삭제하지 않음 |
| Cost preservation | Native owner 1회, 내부 통신과 외부 movement의 비중복, guard/loop/call 빈도, shared copy/lifetime을 각 tiny plan에서 비교 |
| Emission correctness | 선택한 native variant/operand/binding/recipe/schedule이 실제 instruction/map/value와 일치. 기존 Anchor/Proof 없이도 독립적으로 validate |
| Global optimality | 대응하는 유한 계획 집합을 완전 탐색하거나 sound bound로 모두 닫고 unresolved/resource gap이 없음. Default Local 5%는 별도 계약 |
| 성능 개선 | 동일한 support/solver 목표에서 전체 planning과 peak를 반복 측정. 기존 표현 삭제에 따른 새 joint/search/cache 비용 포함 |

Canonical 전체와 static join/body, 고정 schedule, D1 off, 보수적인 guard 등을 추가한 baseline은 동일하지 않다. 이들은 SystemDS의 필수 semantics가 아니라 제안상의 support restriction이다. 제한으로 인한 고속화를 표현 개선과 혼동하지 않는다.

## 10. 최종 판단과 이번 근거

Oracle-driven enumeration은 타당하며 producer 측 intrinsic offers와 edge 측 정확한 조건 평가를 결합하면 source product와 fanout별 중복 objects를 처음부터 피할 수 있다. Native/metadata/temporary broadcast/multi-output의 의미를 올바르게 분리하는 것이 전제다.

가장 중요한 점은 **rule별 factorized input 관계를 유지하고 matching에 성공한 source 조합마다 Rule을 복제하지 않는 것**이다. Global correlation과 shared cost는 필요할 때 동일한 owner 변수와 facts를 사용해 평가한다. 이때 발생하는 지수 시간을 숨기지 않고 측정한다.

이 문서의 실험은 모두 향후 계획이다. 이번 확인은 현재 source, 테스트 assertion, 기존 v50 evidence의 검토와 문서 검증만 포함한다. 새 Memo의 runtime/cost parity와 LogReg/GLM에서의 B&B 우위는 검증되지 않았다.

독립 정적 검토에서 shared owner의 단일 초기화와 전체 context 등록, undo 후 revision/cache 갱신, 실제 persistent REP native 경로, 복합 이동의 collect/upload 공유 비용을 보완했다. 재검토 범위에서 추가 blocker를 찾지 못했으며, 이는 새 구현의 soundness/completeness를 증명하거나 실제 workload를 실행한 결과가 아니다.


[oracle-contract]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesApi.java#L407
[oracle-dispatch]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesCore.java#L333
[oracle-facade]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L180
[generator-options]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L231
[oracle-dependencies]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesApi.java#L465
[oracle-relation]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L445
[oracle-mapping]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L1290
[access-kind]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L6231
[broadcast-compat]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java#L319
[generator-prefix]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L1350
[oracle-partial]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L309
[local-copy]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/LocalMaterializationSelections.java#L354
[identity]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java#L320
[value-id]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java#L352
[activation]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactMaterializationActivation.java#L81
[action-identity]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java#L948
[native-cost]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java#L214
[privacy-facts]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementPrivacyFacts.java#L37
[privacy-policy]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java#L267
[privacy-mask]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidatePrivacyInputPruning.java#L94
[privacy-parity]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/EarlyPrivacyPruningLegalSpaceParityTest.java#L90
[sharing-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactDerivedSupplySharingTest.java#L87
[transient-facts]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L4427
[transient-factor]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L1920
[joint-facts]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L49
[branch-normalize]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/BranchPlacementNormalization.java#L47
[loop-seed]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L8896
[loop-ground]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L9717
[loop-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/LoopIdentityBackedgeClosureTest.java#L60
[function-call]: ../src/main/java/org/apache/sysds/runtime/instructions/cp/FunctionCallCPInstruction.java#L127
[function-block]: ../src/main/java/org/apache/sysds/runtime/controlprogram/FunctionProgramBlock.java#L104
[recompiler]: ../src/main/java/org/apache/sysds/hops/recompile/Recompiler.java#L2379
[function-boundary]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L10827
[joint-loop]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L896
[dyadic]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactDyadicCosts.java#L189
[rt-index]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/IndexingFEDInstruction.java#L176
[rt-append]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/AppendFEDInstruction.java#L166
[rt-rmempty]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/ParameterizedBuiltinFEDInstruction.java#L598
[rt-roll]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/ReorgFEDInstruction.java#L317
[ftypes]: ../src/main/java/org/apache/sysds/hops/fedplanner/FTypes.java#L53
[rt-ranges]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDLocalMaterializeUtil.java#L125
[unary-oracle]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java#L1565
[rt-mm]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateBinaryFEDInstruction.java#L289
[rt-aggregate]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateUnaryFEDInstruction.java#L153
[rt-multi]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/MultiReturnParameterizedBuiltinFEDInstruction.java#L83
[rt-transpose]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/ReorgFEDInstruction.java#L201
[rt-upload]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDFoutInstruction.java#L95
[rt-materializer]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDLocalMaterializeUtil.java#L181
[boundary-policy]: ../src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java#L489
[factory]: ../src/main/java/org/apache/sysds/hops/ipa/FederatedPlannerFactory.java#L45
[local-entry]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java#L43
[local-gap]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L55
[exact-entry]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanExact.java#L59
[solver-freeze]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L1860
[cost-freeze]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java#L590
[factor-api]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L170
[support-revision]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java#L1022
[regional-input]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchProblem.java#L32
[local-block]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java#L369
[v50-map]: FEDPLANNER_BOTTLENECK_MAP.md#L28
[facts]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java#L144
[closure]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1049
[physical-alternatives]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L1050
[projector]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalPlacementProjector.java#L36
[emission]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java#L337
[cost-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CostBasedPruningTest.java#L35
[grounding-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/StructuralGroundingPlanSpaceTest.java#L52
