# FedPlanner: 제한된 Control Flow와 안전한 Cost Pruning·DP Memoization

작성일: 2026-10-13  
저장소: /home/mchoi/w1357-structural-grounding-20261006  
HEAD: c961a291811d23db104cc63a2b161e7e4b480e57  
범위: 현재 작업 트리 분석과 설계 문서. Production/test 변경·빌드·runtime 실험·commit/push 없음.

**최신 사용자 설명을 적용한다: loop는 n>1번 실행되며, loop에서 갱신한 값은 외부에서 사용된다.** 따라서 zero-iteration 경로는 분석 전제상 제외할 수 있지만, 최종 값의 exit binding과 외부 consumer compatibility는 제외할 수 없다. “exit 미지원”을 정상 종료나 최종 결과 공급의 생략으로 해석하지 않는다.

**확정 Transfer 정책 반영:** Backedge `Next(t)→Head(t+1)`는 native DIRECT만 허용하고 별도 transfer/materialization/cost 선택은 만들지 않는다. Entry/Exit 및 body 내부 일반 HOP 간 이동은 기존 합법 supply를 유지한다. If-Else join도 branch별 ExecType/nativeOutput을 같게 강제하지 않고 guarded transfer를 허용한다. 단일 구현 기준은 [통합 명세 ADR09/20, §6.6 및 §11.7](FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md)이다. 이 문서는 해당 정책으로 갱신한 보조 분석이다.

**Joint Feasibility 개정 반영:** [통합 명세 ADR21, §§5.5–5.7/11.8](FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md)에 따라 exact reaching definition/def-use가 도달하는 consumer·boundary에서 필요한 비분리 predicate만 등록한다. 관련 domain/선택/readiness 변경만 기존 binding 역참조로 통지한다. RD overlap은 독립성 증명이 아니며 공통 owner·guard·loop/call·shared-cost 관계는 유지한다.

표기: **[확인]**은 소스·기존 테스트 assertion·artifact의 사실, **[해석]**은 구조적 결론, **[제안]**은 앞으로 구현할 방법, **[미검증]**은 구현·실측으로 입증하지 않은 사항이다. 기존 source/test 미커밋 변경 6개 파일 +463/-54행은 보존했다. Oracle/Supply 기본 계약은 [이전 통합 검토](FEDPLANNER_ORACLE_SUPPLY_DESIGN_2026-10-13_KO.md), 전체 grounding/pruning 논의는 [Earliest Safe Pruning 문서](FEDPLANNER_EARLIEST_SAFE_PRUNING_2026-10-13_KO.md)를 잇는다.

## 1. 일곱 질문에 대한 답과 단일 권고

| 질문 | 답 | 확신과 한계 |
|---|---|---|
| 새 제한으로 얼마나 단순해지는가? | Zero-iteration 대안, 재귀 call SCC, iteration별 placement 정책 탐색을 제외할 수 있다. Candidate rebuild와 concrete proof 조합은 immutable RuleMemo로 없앤다. | 앞의 세 항목은 지원 범위의 효과, 뒤는 표현 재설계의 효과다. 두 개선을 혼동하지 않는다. |
| CFG/fixed point를 없앨 수 있는가? | 범용 CFG 객체와 explicit reaching-environment closure는 structured binding으로 대체할 수 있다. Entry·backedge·최종 exit와 invariant 검증은 남는다. 자동 shape/privacy invariant 추론에는 국소 fixed point가 남을 수 있다. | 고정 ExecType/nativeOutput만으로 모든 반복을 없앨 수 없다는 결론은 높음. 대체 분석 구현은 미검증. |
| NativeOutput별 cheapest 하나가 안전한가? | 그 출력 상태가 외부에서 관찰하는 모든 legality와 비용 영향을 완전히 요약하고, 내부 전체 비용을 계산했을 때만 안전하다. | 단순 native compute 최소는 일반적으로 불가. Shared producer/copy가 경계를 넘으면 성립하지 않는다. |
| 최소 DP key/value는? | Tree: snapshot에 scope한 `(occurrence, required supply)` → exact cost/backpointer. 일반 region: `(region, complete boundary assignment[, residual domain signature])` → exact/lower-bound 상태와 backpointer. | Guard·shape·privacy 등의 안정 정보는 snapshot에 한 번 둔다. 요청별로 달라지는 정보만 key에 포함한다. |
| 어디까지 bottom-up인가? | 외부 영향이 작은 boundary로 완전히 분리되는 tree 부분과 비재귀 function의 구조적 summary는 bottom-up. Shared/joint/lifetime 관계가 남는 부분은 공동 최적화. | HOP fanout만으로 독립성을 판단하지 않는다. |
| 효율적인 실행 순서는? | Structured facts → Oracle/privacy → 양방향 feasibility → 싼 동치/dominance → tree DP 요약 → lazy boundary memo를 쓰는 exact global search → 독립 emission 검증. | Root에서 성립한 pruning과 search branch 내부 pruning을 분리한다. |
| 무엇을 재사용하는가? | 기존 value/owner/guard/binding, kernel cost, activation/lifetime, boundary 최소값·backpointer·하한의 의미를 재사용한다. | Physical Alternative, source products, dense boundary table의 현재 객체/배열은 새 경로로 옮기지 않는다. |

**단일 권고 [제안]: `structured facts + immutable RuleMemo + active domains + tree DP를 포함한 lazy separator-memoized exact search`.** 독립된 여러 optimizer framework를 새로 만들지 않는다. `solve(region, boundary)`라는 한 연산이 독립 tree에는 bottom-up DP를, 나머지에는 분해 가능한 부분을 cache하는 exact branch-and-bound를 사용한다. Boundary는 기존 HOP edge와 typed Program Facts의 관계를 참조하는 compact ID 배열이다. 모든 boundary assignment를 미리 열거하거나 범용 Constraint Graph로 복사하지 않는다.

기존 Local은 feasible incumbent와 비교 기준으로, Exact/Regional은 정확한 message·bound 의미와 작은 fixture의 reference로 활용한다. 새 알고리즘의 LogReg/GLM 우위는 아직 확인하지 않았다. 기본 5% gap으로 종료하는 기존 Local과 exact optimum을 요구하는 새 실행을 같은 목표라고 비교하지 않는다. [현재 Local 계약][local-contract], [regional 종료 설정][regional-options]

## 2. 새 Loop/Function 전제가 바꾸는 것

### 2.1 n>1이어도 entry와 exit는 남는다

**[확인]** WhileProgramBlock.execute는 body 전에 predicate를 평가하고, 반복 종료 후 exit instructions를 실행한다. ForProgramBlock.execute는 bounds/increment와 iteration 수를 계산하고 반복 수가 0 이하이면 반환한다. 현재 runtime은 zero iteration을 지원한다. [while 실행][while-runtime], [for 실행][for-runtime]

**[제안]** 이번 분석은 사용자가 제공한 n>1 보장을 입력 계약으로 받아들인다. 별도 runtime 증명을 이번 분석의 선행조건으로 요구하지 않는다. 실제 구현에서는 그 계약이 proven bounds, annotation, workload contract 중 어디서 오는지와 위반 처리만 명시해야 한다. 일반 while을 강제로 do-while로 바꾸거나 알려지지 않은 반복 수를 n>1로 추정해서는 안 된다.

~~~text
T > 1
Head(0)      = Entry
Head(t+1)    = Backedge(t) = Body(Head(t))
Exit         = Backedge(T-1)
OuterUse     = supply(Exit, required layout)
~~~

제거 가능한 것은 `Exit = Entry when T=0` 선택지다. `Entry→Head(0)`와 `Backedge(T−1)→OuterUse`는 남는다. n>1은 entry source가 불필요하다는 뜻도, loop가 종료되지 않는다는 뜻도 아니다. T가 정확히 알려지지 않아도 마지막 값의 구조적 binding은 표현할 수 있다.

~~~dml
A=X;
for(i in 1:2) { A=A+1; }
Z=sum(A);
~~~

초기 X와 첫 번째 A를 최종 A로 공급하면 틀린 결과다. 예를 들어 X가 0인 원소 m개라면 Z는 2m이어야 한다. ExecType과 nativeOutput이 전 iteration에서 같아도 세 값은 서로 다르다. Exit copy는 마지막 dynamic value를 읽어야 하며, 같은 static HOP ID의 첫 iteration copy를 재사용할 수 없다.

### 2.2 ExecType/nativeOutput 고정은 부분적인 physical invariant다

**[해석]** 세 번째 조건은 loop의 static physical contract로 해석할 수 있다. 그러나 이 두 필드만 고정해도 input pattern, output shape, privacy, supply recipe, cache lifetime, lowering variant까지 같아지는 것은 아니다.

| 고정되는 것 | 자동으로 고정되지 않는 것 |
|---|---|
| 특정 body occurrence의 CP/FED와 LOCAL/ROW/COL/REP native output | 같은 변수명의 entry/backedge 값, input 위치별 source, branch guard |
| iteration별 placement mode 수 | shape-dependent native capability, ROW/COL ranges와 upload 합법성 |
| 정적 output partition label | privacy/release 조건, 값의 존재 시점, last-iteration copy |
| 실행 mode를 바꾸지 않는 제약 | runtime recompilation, fused kernel ownership, 실제 iteration 비용 |

예를 들어 동일 변수 A의 if-arm별 서로 다른 정의는 별도 occurrence다. “각 occurrence의 pair 고정”과 “모든 arm이 A를 같은 layout으로 공급”은 다른 조건이다. 일반 If-Else join은 branch별 native output에서 consumer requirement로 합법적인 이동을 허용한다. 다만 그 값이 직접 loop backedge로 연결되면, 실제 다음 iteration으로 가는 각 arm의 native output이 head와 DIRECT 호환해야 하며 boundary normalization은 금지한다. Final-only arm에는 가상의 다음 backedge를 요구하지 않는다.

새 planner는 현재 compile epoch의 한 execution owner에 한 semantic rule을 선택한다. 이것은 기존 정적 compiled body에 맞는 초기 계약이다. 사용자 조건만으로 iteration마다 input variant를 동적으로 최적 선택하는 정책까지 배제되지는 않으므로, 그러한 adaptive plan 전체의 최적성을 주장하지 않는다. Runtime recompilation은 기존 의미를 유지하되 snapshot/DP cache를 갱신하고 고정 physical contract를 다시 검증해야 한다. 미래 재컴파일을 포함한 총 실행 비용의 최적화는 이번 유한 snapshot 최적화의 보장 밖이다. [while recompile][while-runtime], [function recompile][recompiler]

### 2.3 가장 작은 grounding·shape·privacy 반례

**Grounding:** 다음 loop에 `(FED,ROW)` body rule이 있다는 사실만으로 entry가 ROW라는 결론은 나오지 않는다.

~~~dml
A=X;
for(i in 1:2) { A=abs(A); }
Z=sum(A);
~~~

X가 LOCAL이면 첫 body에 합법적인 UPLOAD 또는 다른 검증된 공급이 필요하다. 그 공급을 생략하고 “body A는 ROW이고 다음 A도 ROW”라고 인정하면 아직 존재하지 않는 body output으로 자신을 정당화한다. 같은-iteration data cycle은 거절하고, backedge는 오직 이전 iteration에서만 읽는다. 현 Closure도 provisional loop seed와 entry에 근거한 최종 검증을 구분한다. [placement seed][loop-seed], [grounded proof][loop-ground], [loop 회귀][loop-test]

**Shape:** 다음은 새로운 검증 fixture 제안이지 실행 확인된 workload가 아니다.

~~~dml
A=X;
for(i in 1:3) {
    A=A[1:floor(nrow(A)/2),];
    s=sum(A);
}
Z=sum(A);
~~~

W=4, X의 행 수가 16이면 A는 8→4→2가 된다. CP/LOCAL body를 유지해도 후속 ROW upload는 마지막에 rows<W로 불가능하다. FED indexing은 worker subset/noncanonical map이라는 별도 문제가 있다. 고정 pair는 canonical geometry 검증을 대체하지 못한다. [upload 최소 크기][upload-runtime], [indexing map 변경][index-runtime]

**Privacy:** A의 초기 public label만으로 반복 전체를 분석할 수 없다.

~~~dml
A=PublicX;
for(i in 1:2) {
    print(sum(A));
    A=PrivateY;
}
Z=sum(A);
~~~

Fixture에서 PrivateY의 privacy를 실제로 설정해야 한다. 두 번째 iteration과 exit의 legality는 PrivateY에 대한 semantics를 따른다. 모든 값이 같은 partition이어도 달라지지 않는다. Aggregate 결과 공개와 raw input collect도 별개다. [privacy 회귀][privacy-test]

**Correlated carried tuple:**

~~~dml
X=A; Y=B;
for(i in 1:2) {
    if(p[i,1]>0) { X=A; Y=B; }
    else { X=C; Y=D; }
    Z=X+Y;
}
R=Z;
~~~

각 iteration의 입력은 (A,B) 또는 (C,D)다. Layout을 각각 고정해도 (A,D)/(C,B)를 허용할 수 없다. 여러 carried variables는 하나의 guard에 연결된 공동 전이로 표현해야 한다. 또한 loop에서 쓰이는 predicate를 초회 값으로 영구 specialize해서는 안 된다. [joint analysis][joint-analysis], [loop predicate 회귀][loop-predicate-test]

## 3. CFG·fixed point의 최소 대체

### 3.1 현재 구현과 제거 조건

**[확인]** PlacementProgramFacts.analyze는 conservative CFG를 만들고 abstract shape/scalar facts로 CFG를 다시 분석하는 refinement를 반복한다. 수렴하지 않으면 conservative CFG를 사용한다. analyzeCfg 내부에는 reaching-definition 반복이 따로 있다. PlacementJointInputAnalysis.loopFixedPoint는 entry environment에 body exit environments를 합집합하며 반복한다. 즉 이것들은 같은 의미의 반복 하나가 아니다. [facts refinement][facts-analyze], [reaching definitions][cfg], [joint loop][joint-loop]

| 현재 계산 | 최소 대체 | 제거 가능 조건·남는 의미 |
|---|---|---|
| 일반 predecessor/successor CFG 구축 | StatementBlock의 sequence/if/loop/function 구조 직접 순회 | Structured program 경로에서 가능. 실제 제어·값 의존성은 남음 |
| 전체 block의 reaching-def 재전파 | 프로그램 순서의 symbolic value binding + loop Head placeholder | 모든 concrete writer 집합을 즉시 펼칠 필요가 없을 때 가능 |
| explicit joint environment loop closure | 공동 Head tuple과 guarded Backedge 식 | 정확한 branch correlation을 보존하고 귀납 조건을 검증해야 함 |
| CFG↔shape/scalar refinement | 의존하는 guard/shape query만 재평가 | Optional constant-branch precision을 포기하면 더 단순하지만 추가 false rejection 가능 |
| Placement candidate/Proof 재생성 | immutable native rules + active-domain worklist | source-free rule universe와 정확한 boundary predicate를 구현한 뒤 제거 |
| placement provisional seed/discharge 객체 | 선택 Head 계약의 base/step 검사 | entry grounding과 모든 backedge 보존은 유지 |
| shape/privacy invariant 추론 | 주어진 invariant의 induction 검증 또는 작은 SCC worklist | invariant가 자동 추론 대상이면 반복이 남을 수 있음 |
| 재귀 call summary SCC | call DAG 역위상 순서 | 모든 가능한 호출 대상을 알고 direct/mutual recursion을 제외해야 함 |
| feasibility propagation | incident 관계만 방문하는 worklist | Shared/joint domain 삭제의 전파는 여전히 fixed point까지 필요 |

**중요:** “CFG 객체를 만들지 않는다”와 “control flow를 분석하지 않는다”는 다르다. Symbolic head를 도입하는 것은 cyclic reaching definition을 유한한 식으로 표현하는 것이며, 가능한 모든 실행 경로를 이미 풀었다는 뜻이 아니다.

### 3.2 Structured def-use를 한 번 구성한다

**[제안]** 기존 HOP/StatementBlock을 순회하며 `name→current ValueId` map만 갱신한다. ValueId는 definition occurrence와 output slot을 식별하고 loop/call의 동적 epoch는 별도 lifetime 의미로 둔다.

~~~text
sequence: read는 현재 binding 사용; write는 새 ValueId로 map 갱신
if(g):    두 arm을 같은 incoming map에서 분석
          변경된 이름만 Join(g, thenValue, elseValue)
          여러 이름의 Join은 같은 guard g를 공유
loop L:   carried 이름에 Head(L,v) placeholder를 먼저 생성
          body를 Head map에서 한 번 구조적으로 연결
          Head.entry = incoming[v]; Head.backedge = bodyFinal[v]
          외부 binding = Last(L, bodyFinal[v])  // T>1, zero arm 없음
call:     실제 actual/formal/return position으로 summary parameter 연결
~~~

If에 else가 없으면 elseValue는 incoming이다. Branch의 source writer와 guard는 solver가 비용에 따라 선택하는 변수가 아니다. TRead/TWrite의 runtime legality는 단순 alias로 바꾸었다고 없어지지 않는다. 기존 strict boundary lowering을 유지한다. [현재 transient facts][transient-facts], [boundary policy][boundary-policy]

Placeholder는 새 Proof 객체가 아니다. 기존 value-version/facts 배열의 loop header 참조 몇 개면 된다. 단일 순회는 **def-use 구조 구축**에 대한 주장이다. 필요한 shape/privacy와 correlation의 추론이 한 번에 끝난다는 주장은 아니다. 모든 이름 map을 branch마다 복사하지 말고 변경 이름만 공유/병합한다. 최소 저장량은 binding/guard 식 크기에 비례하며, 임의 correlation의 정확한 해석은 여전히 커질 수 있다.

### 3.3 LoopContract의 최소 필드

~~~java
record LoopInfo(
    int statementBlockId,
    int[] carriedValueIds,
    int[] entryValueIds,
    int[] backedgeValueIds,
    int[] liveOutValueIds,
    int invariantFactId,
    int frequencyFactId) {}
~~~

실제 구현은 기존 facts의 배열/view로 충분하다. `statementBlockId`로 body, predicate, enclosing loop, guard와 순서를 읽는다. `bodyRules`는 RuleMemo/SelectedRule에 이미 있으므로 복사하지 않는다. `partitionRequirements`도 선택 boundary 변수에서 읽고 중복 저장하지 않는다. `frequencyFactId`는 비용에 필요하며 n>1이라는 최소 반복 보장과 기대 반복 횟수는 다른 정보다.

Loop에서 정의된 모든 변수가 carried인 것은 아니다. 이전 iteration 값을 body/predicate에서 읽는 변수만 Head 관계가 필요하다. Body에서 새로 만들어 외부에서만 쓰는 값은 Last binding만으로 충분할 수 있다. 기존 live-variable/def-use 정보를 재사용해 불필요한 header 상태를 만들지 않되, branch에서 정의를 생략하는 pass-through는 보존한다.

Entry/backedge의 input position, exact source slot, guard, alias provenance를 기존 binding으로 참조한다. `liveOutValueIds`는 마지막 iteration의 값을 나타내며 구체적인 T개의 ValueId를 만들지 않는다. Shape/privacy invariant와 source availability를 layout 배열만으로 대체하지 않는다.

Binding kind는 `ORDINARY / LOOP_ENTRY / LOOP_BACKEDGE / LOOP_EXIT`이며 동일 static read의 entry/backedge arm을 구분한다. Backedge는 `isDirectBackedge(native writer, head contract, guard/shape/privacy)`를 호출하고 SupplyChoice/recipe/retention/action slot을 만들지 않는다. Producer의 availableOutputs는 다른 일반·Entry·Exit consumer를 위해 유지한다. 전체 input HOP를 BACKEDGE로 표시하거나 producer의 Offers를 전역 삭제하면 첫 iteration과 외부 소비까지 잘못 제한한다.

Head 계약은 loop/slot당 하나이며 기존 carrier rule에서 유도하거나, 유도할 owner가 없으면 최대4 layout boundary domain으로 선택한다. Writer별로 다른 head를 즉석 선택하지 않는다. Identity carrier는 실제 origin을 투영하고 body movement copy를 native output으로 재명명하지 않는다. Entry로 grounding된 Head를 그대로 forward하는 identity backedge는 허용한다. 선택·DP·emission은 이 같은 계약을 참조한다.

### 3.4 반복을 펼치지 않는 검증

~~~text
validateLoop(L, selected/static boundary contract, facts):
    accept the supplied trip-count contract T > 1
    I = proven joint head invariant(shape, privacy, bindings, layout, availability)
    prove Entry(L) establishes I, including chosen entry supplies
    assume I for symbolic Head(L)
    validate predicate; validate body under I AND iteration-enabled
        in statement/data-dependency order
    for every reachable body branch environment:
        validate native rules, exact input supplies, privacy and availability
        if this backedge is taken:
            require native output DIRECT-matches head; no boundary movement
            prove its Backedge reestablishes I
    forbid same-iteration self-support; allow only explicit previous-iteration edge
    under the exit condition, bind live-outs to Last(Backedge)
        and validate outer consumer supplies
    return VERIFIED, DISPROVED, or UNKNOWN
~~~

Entry에서 I가 성립하고 임의의 유효 Head에서 body가 I를 보존하면 반복 수와 무관하게 귀납적으로 안전하다. 두 iteration을 펼쳐 검사하는 것으로는 충분하지 않다. 세 번째에 shape 조건이 깨질 수 있다. Initial/steady-state 의미가 다를 경우 작은 두 단계 summary를 사용할 수 있지만 일반적인 필수 조건은 아니다.

정확한 유한 T에서만 합법적인 계획도 있다. 이를 보존하려면 필요에 따라 counter/phase를 포함한 guarded invariant를 사용해 **실제로 다음 iteration이 실행되는 상태에서만** body 전이를 검사한다. Counter를 무시한 무제한 invariant를 필수로 요구하면 추가 지원 제한이 된다. 그 표현으로 증명하지 못한 계획은 추상화의 한계/UNKNOWN으로 다루며 runtime 불법이라고 선언하지 않는다. n>1 보장은 정확한 T나 shape 불변성을 제공하지 않는다.

I를 아직 모르면 필요한 속성만 추론한다. 유한 lattice의 높이를 H, SCC incidence를 Iₗ이라 하면 단조 transfer의 delta worklist는 대략 O(H·Iₗ·transferCost)로 제한할 수 있다. Unbounded symbolic shape에는 이러한 유한 상한을 주장하지 않는다. Widening/보수적인 invariant가 proof를 얻지 못하면 UNKNOWN을 반환하며 실제 불가능이라고 표시하지 않는다. 과도한 추상화는 합법 계획의 추가 제외가 되므로 completeness의 범위에 기록한다.

Nested loop는 내부 summary를 먼저 만들고 외부 한 iteration의 연산처럼 연결한다. 내부 entry는 현재 외부 iteration의 값이고, 내부 Last output은 외부의 다음 statement/backedge로 간다. Dynamic epoch는 `(outer iteration, inner iteration)`을 구분한다. 내부에도 Tinner>1 보장이 적용되는지 확인한다. Iteration 수의 곱만큼 HOP/Rule을 복제하지 않는다.

내부 exit transfer를 aliases만 통해 외부 backedge에 넘겨 외부 DIRECT 의무를 우회할 수 없다. 각 loop/slot의 route를 검사하되, 내부 exit 이동 후 실제 외부 body 연산이 새 값을 계산했다면 그 새 native output을 외부 backedge source로 삼는다. TRead/TWrite/단순 alias 앞뒤에 삽입한 transfer를 “body 안”이라는 이유만으로 일반 edge로 재분류하지 않는다. 반대로 head의 값을 실제 body kernel에 공급하는 일반 input preparation은 계속 허용한다.

## 4. Function·If·Transient의 단순화

### 4.1 비재귀 call graph

**[확인]** JointInputAnalysis.invoke는 recursive DML call을 거절하고, invocation input에 따른 exit environment를 memoize한다. FunctionProgramBlock/Recompiler에는 실행 문맥에 따른 recompilation 경로가 있다. 재귀 제외가 이러한 context를 없애지는 않는다. [함수 binding/cache][function-invoke], [compiled body 실행][function-runtime], [recompile][recompiler]

**[제안]** Direct/mutual recursion이 없는, 호출 대상이 해소된 call graph는 DAG다. O(functions+calls) 위상 순서로 구조적인 summary를 한 번 만든다. Function 내부 loop는 §3의 summary를 사용한다. Function actual/formal/return position과 shared compiled owner는 계속 보존한다.

Bottom-up 한 번 계산할 수 있는 것은 parameterized 구조·capability summary다. 모든 shape/privacy/입력 공급/빈도에 대해 최적 cost 하나를 한 번 계산한다는 뜻은 아니다. DPMemo는 실제 query signature별로 lazy 계산하고, 동일한 signature만 재사용한다. 동일 변수명이나 같은 template HopId는 cache equivalence가 아니다.

~~~dml
f=function(matrix[double] X) return(matrix[double] Y) { Y=abs(X); }
A=f(P); B=f(Q);
~~~

같은 compiled body rule r을 공유하며 call 간 비용이 가산적인 경우 비용은 `min_r Σcall Ccall(r)`다. r은 모든 활성 call에 공통으로 합법적이어야 하고 Ccall은 guard/frequency를 반영한다. 이를 `Σcall min_r Ccall(r)`로 바꿀 수 없다. 예를 들어 두 body rule의 call별 비용이 (1,100), (100,1)이면 공통 선택의 최소는 101이며 호출별 독립 최소의 합 2는 허용되지 않은 specialization이다. 실제로 clone된 body만 독립 owner로 다룬다. 수치는 추상 반례다.

Call 간 setup/materialization을 공유하면 일반식은 `min_{r,S:AllActiveCallsLegal} [Σcall Ccall(r,S) + CcrossCall(r,S)]`이며, cross-call 비용은 공통 관계를 소유한 region에서 한 번만 낸다. Known-inactive call만 제외하고, reachable 또는 UNKNOWN call의 조건은 유지한다. 호출마다 합법인 r이 따로 존재한다는 것만으로 공통 domain이 비어 있지 않다고 결론내리지 않는다.

따라서 shared body choice를 call별 summary 안에서 제각각 제거하지 않는다. 관련 body 선택을 boundary에 남기거나, 모든 활성 call context를 모아 공통 body를 한 번 공동 최적화한다. 한 함수의 내부 rule 수만큼 boundary가 커질 수 있으므로 compact summary가 항상 가능한 것은 아니다.

### 4.2 Structured If와 정확한 transient version

If는 공유 guard를 참조하는 Join 식으로 표현할 수 있다. Branch별 layout이 달라도 join contract로 공급할 수 있으면 합법이다. 모든 reachable environment에서 같은 정적 계획이 유효해야 하며, guard를 최적화 변수로 삼지 않는다. 확률 0 추정은 unreachable 증거가 아니다.

~~~dml
X=A; Y=abs(X); X=B; Z=abs(X);
~~~

Y는 A의 definition, Z는 B의 definition을 읽는다. Statement block 경계를 넘어도 동일하다. 최소 분석은 프로그램 순서의 definition-use 연결, alias, guard, loop Head/Last, function binding이다. 모든 reaching writer 목록을 source Cartesian product로 확장하지 않아도 이 의미를 보존할 수 있다. 개별 writer 가능성만 검사하면 correlated input constraint를 잃는다.

Joint 검사 범위는 위 exact binding을 읽는 predicate에서 정한다. 같은 guard를 공유해도 고정 native pattern의 합법성이 edge 조건들의 conjunction으로 정확히 분해되면 별도 joint handle은 필요 없다. Guarded binding 자체와 공통 owner 선택은 그대로 둔다. Loop의 공동 invariant는 실제 참조 carried slots에만 연결하고, 함수의 공유 compiled owner는 RD가 다른 calls도 연결한다. Region 분해는 RD-set 분리가 아니라 모든 hard/cost/action incidence를 기준으로 한다.

분기 조건 동치의 완전한 증명, 임의 symbolic reachability까지 선형 분석으로 해결할 수 있다는 주장은 하지 않는다. 기존 conservative 환경을 그대로 쓰면 그에 따른 false rejection 가능성도 남는다. [현재 joint query][joint-query]

## 5. Native Output별 cheapest pruning의 정확한 경계

### 5.1 작은 반례 세 가지

**A. Native compute와 supply cost는 다르다.**

~~~dml
s=sum(X);
~~~

X가 ROW일 때 CP/LOCAL rule의 compute=1, collect=100이고 FED/LOCAL rule의 native 총비용=10이면 1만 보고 CP를 남길 수 없다. 총비용은 101 대 10이다. PrivateAggregation이면 raw collect가 금지되는 경우도 있다. 그 경우는 privacy 단계에서 제거해야 하며 privacy pruning 완료 후의 cost 반례와 구분한다. [privacy legal-space 테스트][privacy-test]

**B. Subtree 전체 최소도 shared producer를 만나면 틀릴 수 있다.**

~~~dml
P=abs(X);
H=sum(P);
Q=max(P);
Z=H+Q;
~~~

다음 수치는 solver fixture용이며 이 DML의 측정 비용이 아니다. Q가 ROW 공급을 요구하는 domain으로 한정했다고 하자. P의 LOCAL native cost=0, ROW native cost=10, upload/collect=각 100이다. H의 두 rule은 모두 LOCAL output이며 input LOCAL이면 cost=1, input ROW이면 cost=4다. Q 자체의 공통 native 비용은 두 경로에서 같아 0으로 두고 비교한다.

H만 최적화하면 P LOCAL + H LOCAL-input의 비용 1을 선택하고 H ROW-input 경로 14를 버린다. 그러나 Q도 있으면 LOCAL P 경로는 upload가 필요해 최소 101이고, ROW P를 공유하면 전체 14다. `DP[H,LOCAL]`의 1이 exact subtree 최소여도 경계 밖 Q가 P를 관찰하므로 global 대체값이 아니다.

**C. 소비자별 movement 최소는 공유 copy의 최소가 아니다.**

LOCAL X에서 `sum(X)`와 `max(X)`를 계산한다고 하자. 각 consumer의 CP 비용=7, FED LOCAL-output 비용=1, 재사용 가능한 ROW upload=8이면 소비자별 최소는 CP 7이다. 공동 계획은 upload를 한 번 공유해 8+1+1=10으로 CP 두 개의 14보다 싸다. 각각의 subtree에서 FED rule을 버리면 optimum을 잃는다. Sharing에는 같은 exact value·creation/cache owner·lifetime이 전제다. [실제 shared action scope][shared-cost], [activation lifetime 회귀][activation-test]

같은 native output·shape·privacy/release·slot·lifetime 조건이면 intrinsic available-output recipe 집합은 같을 수 있다. 차이를 억지로 가정할 필요는 없다. 입력 비용과 공유 관계만으로도 위 반례는 성립한다. 반대로 persistent REP operand를 LOCAL temporary broadcast보다 항상 우월하다고 취급하는 dominance는 실제 runtime 조건에 어긋난다. [REP 입력 조건][broadcast-compat]

### 5.2 가장 강한 정확한 dominance 정의

Partial plan p의 외부 계약을 σ라고 하자. Plan q가 p를 대체해도 되는 조건은 다음이다.

~~~text
모든 허용된 외부 completion e에 대해:
    legal(p,e) ⇒ legal(q,e)
    (totalCost(q,e), canonicalTie(q,e)) ≤lex (totalCost(p,e), canonicalTie(p,e))
    필요한 observable outputs / execution effects / emission 계약 보존
~~~

이는 최대한의 semantic dominance지만 일반적으로 global optimization만큼 비싸다. 모든 e를 열거해 최대 pruning을 하려는 것은 제거하려던 조합 문제를 다시 만든다. 따라서 다음의 싼 충분조건부터 사용한다.

위 tie는 선택된 한 최적 계획의 deterministic reconstruction 계약을 뜻한다. 동률에서 q가 더 나쁜 canonical tie이면 p를 제거하지 않는다. 모든 최적 계획 자체를 열거·보존하는 요구라면 strict cost improvement 또는 동치 class의 복원 가능한 member mapping이 추가로 필요하다.

| 단계 | 실제로 제거할 수 있는 것 | 판정 비용/주의 |
|---|---|---|
| Oracle 직후 | 동일 semantic variant/pattern/조건의 중복 | signature hash+정확 비교, 대체로 O(pattern 길이). Native compute만 작은 다른 variant는 제거하지 않음 |
| Privacy/offer 이후 | 실행·출력 slot·입력 요구·guard/privacy·lowering·공유 관찰이 동일하고 own cost가 항상 크거나 같은 rule | canonical signature별 묶음은 선형에 가깝다. Shape-dependent 비용은 전 범위 부등식 필요 |
| Root feasibility 이후 | 모든 남은 incident predicate에서 동일한 관찰을 갖는 rule의 비용 대표 | 기존 Exact quotient의 의미. 관찰을 완전히 비교하면 비쌀 수 있으므로 small scopes만 검사 |
| Closed tree DP | 같은 완전한 required-supply key의 더 비싼 내부 subplan | 전체 입력 비용·movement 포함 후 scalar argmin. RuleMemo 삭제가 아니라 DPMemo의 대표 선택 |
| 고정 boundary region | 동일 boundary/activation/lifetime 출력 상태의 열등 label | 작은 Pareto frontier의 비교. 비교 불가능한 label 유지 |
| Search branch | 현재 domain/선택에서만 dominance, 또는 certified LB가 incumbent보다 큼 | branch-local trail. Root/universe로 승격하지 않음 |

기존 Local은 complete state key 안에서만 최소 대표를 유지한다고 명시하고, Exact reducer는 남은 모든 incident factor의 관찰이 같은 값만 quotient한다. 테스트의 `CP/LOUT` key가 단순하다는 이유로 실제 모든 rule도 output-only equivalent라고 결론내릴 수 없다. [Local 대표 계약][local-contract], [Exact quotient][quotient], [Local key 테스트][local-prune-test]

Input-requirement subsumption도 비교할 수 있지만 privacy, exact value, 공동 입력 관계와 cost가 모두 단조여야 한다. 예를 들어 같은 lowering/부작용/공유 경계를 가진 두 rule의 허용 입력 관계가 포함되고, 넓은 쪽의 비용이 항상 작으면 좁은 쪽을 제거할 수 있다. 임의 joint relation의 포함 검사는 비싸므로 기본 경로는 identical signature와 작은 mask/predicate의 증명 가능한 포함에 한정한다. 증명하지 못한 후보를 삭제하지 않는다.

### 5.3 최소 pruning key는 continuation boundary다

NativeOutput은 연산의 결과이고, AvailableOutput은 가능한 공급 옵션이다. 둘 다 일반적인 완전한 cost key는 아니다. 필요한 것은 **남은 프로그램이 관찰할 수 있는 차이만 담은 boundary state**다.

단일 consumer, 닫힌 tree, 고정 facts, 공유 없는 additive cost이면 RequiredOutput만으로 충분하다. 여러 consumer가 같은 결과의 여러 copy를 요구하지만 나머지 외부 영향이 없으면 작은 `DemandMask`가 가능하다. 네 partition의 동시 요구 집합은 최대 16개다.

~~~text
SupplyDP(h, D) = min_r [ closedExecutionCost(h,r)
                         + costOfSelectedPrimitiveActionUnion(r,D) ]
~~~

이는 각 q의 최솟값을 독립적으로 더하는 식이 아니다. 하나의 r에서 D 전체를 공급한다. 이 16-state 요약은 동일 값/guard/lifetime/schedule 경계가 고정될 때만 충분하다. Branch별 activation, 여러 동적 epoch, 외부 shared input, cache retention까지 다르면 mask만으로 합칠 수 없다.

빈도에 따라 최적 선택이 바뀌는 reusable summary는 `(setupCost, perIterationCost, exitCost, boundarySignature)` 같은 작은 Pareto frontier를 유지할 수 있다. 예를 들어 A=(100,1), B=(0,5)는 T=2에서는 B=10, T=100에서는 A=200이 유리하다. 고정 T query라면 frontier를 scalarize해 하나만 남기고 T/frequency snapshot에 scope한다. 서로 다른 activation/lifetime signature를 비용 벡터만 보고 dominance 처리하지 않는다.

**영구 rule 삭제**는 모든 해당 context/completion에서 성립하는 proof가 있어야 한다. **DP 대표 삭제**는 그 key에 한정된다. **B&B 삭제**는 현재 branch와 incumbent certificate에 한정된다. Feasibility pruning은 legal set을 보존하지만 cost pruning은 dominated legal plans를 제거하므로 legal set 전체가 같을 필요는 없다. 대신 optimum 및 요구된 tie/외부 의미를 보존해야 한다.

## 6. RuleMemo와 Local DP

### 6.1 두 Memo의 책임

| 저장소 | 내용 | 수명과 변경 |
|---|---|---|
| RuleMemo | occurrence별 native semantic rule, ordered input requirement, output slot, 조건부 offer | 분석 epoch에서 immutable. Concrete source tuple이나 best-cost를 넣지 않음 |
| active domains | RuleMemo ID 중 현재 허용되는 것 | Root pruning 이후 search에서는 trail로 복원 |
| DPMemo | 완전한 subproblem key에 대한 최소 비용/하한과 reconstruction 정보 | Lazy·bounded cache. Domain/facts/objective 변경 시 무효화하거나 새 snapshot 사용 |

Oracle cache와 DPMemo는 다르다. 동일 native pattern은 다른 callsite에 재사용할 수 있어도 비용·privacy·actual value·공유 compiled body 조건은 달라질 수 있다.

### 6.2 DP[h,requiredOutput]가 정확한 조건

다음 조건이 모두 성립하는 부분을 이 문서에서는 tree 부분이라고 부른다.

1. h는 정확한 occurrence/value/compile epoch를 가리킨다.
2. 내부 실행 선택을 외부 consumer나 다른 callsite가 공유하지 않는다. Child들은 서로 다른 exact occurrence이고 그 내부 owner/value 집합도 서로 겹치지 않는다. 반복 input이나 shared descendant는 초기 tree DP 범위에서 제외해 separator로 보낸다.
3. 내부와 외부 사이의 모든 legality/cost 영향이 requested supply q와 고정 context로 결정된다.
4. 내부 비용은 exclusive native work와 exclusive primitive action으로 분리된다. 공유 copy, fusion, lifetime, memory/schedule 제약이 경계를 넘지 않는다.
5. 각 Oracle rule의 input requirements를 고정하면 child 최적화가 서로 독립이다. Joint guard/privacy 관계가 남으면 그것을 boundary에 포함해야 한다.

그래서 HOP parent가 하나라는 조건만으로 충분하지 않다. Transient alias, function body owner, shared upload와 fused kernel도 외부 관계를 만들 수 있다. 현재 비용 계산은 removed kernel·alias·function boundary에 zeroExecution을 적용하고 실제 fused owner에 비용을 모으므로, 단순 HOP별 compute 합은 이 의미를 보존하지 못한다. [native/fused 비용 소유권][compute-owner], [fusion 회귀][fusion-test]

### 6.3 정확한 tree 점화식

고정 context θ 안에서 ordinary payload tree를 먼저 생각하자. Rₕ는 active native rules, pᵢ(r)는 input i의 required partition, O(r,q)는 q를 공급하는 합법 offer다. Cnative는 해당 native owner의 내부 통신까지 포함한 비용이고 Cmove는 외부 공급 비용이다.

~~~text
Dθ(h,q) = min over r∈Rₕ, o∈O(r,q) {
    Cnativeθ(h,r)
    + Σ_i Dθ(child_i, p_i(r))
    + Cmoveθ(h,r,o)
}
~~~

Source leaf는 실제 존재하는 native source state와 합법 movement만 가진다. unavailable이면 ∞, 미확정이면 UNKNOWN이다. Metadata-only input은 payload LOCAL/collect로 바꾸지 않고 기존 access predicate와 실제 metadata availability를 따른다. Scalar/handle과 multi-output owner도 기존 role/slot 규칙을 유지한다.

Pattern이 압축 mask라면 child별 min을 독립 적용해도 되는 경우만 축을 분리한다. Axis equality·적어도 한 FED 입력 같은 상관 조건은 native predicate로 유지하거나 이미 검증된 추상 pattern별로 계산한다. Child source choices의 product를 RuleMemo에 전개하지 않는다.

**Key는 native output이 아니라 요청한 supply q다.** r의 native ROW 결과를 collect해 q=LOCAL로 줄 수도 있으므로 D(h,LOCAL)에는 CP/LOCAL과 FED/LOCAL, FED/ROW+COLLECT 등의 합법 대안이 함께 들어간다. 반대로 internal execution cost E(h,r)는 아직 특정 consumer로 보내는 movement를 포함하지 않는다. 이 둘을 같은 minimum 값으로 cache하지 않는다.

Reconstruction은 `(r, offerId, childKeyIds[])`를 backpointer로 저장하고 선택된 root에서 한 번만 따라간다. 실제 action은 exact value와 creation/lifetime를 확인해 만든다. Selected producer rule이 하나라는 조건은 decode에서도 재검사한다. Cost만 저장하고 이후 가장 싼 native rule을 다시 고르면 다른 합법성/공유 선택을 복원할 수 있다.

### 6.4 Cache context를 최소화하는 방법

Snapshot θ가 program/compile epoch, W, D1 및 control-flow transfer policy version, shape/privacy facts, cost model·frequency version, root active domains를 소유하면 매 key에 이 큰 객체들을 복사할 필요가 없다. 그 snapshot에 속한 DPMemo에서 `(h,q)`만 사용할 수 있다. Backedge DIRECT-only 정책 이전의 supply/cost cache를 새 snapshot에 재사용하지 않는다.

Callsite별 입력 shape나 frequency가 달라지면 동일 query가 아니다. 모든 관련 facts가 같은 경우만 signature를 intern해 재사용한다. Function body의 공통 R을 이미 고정했다면 그 선택도 key/boundary에 포함한다. 단순 호출 횟수만 곱하려면 one-call 비용의 선형성, call 간 materialization 공유 부재, 동일 body plan과 동일 per-call 특성이 필요하다.

Loop summary도 같은 원칙이다. Shape와 activation/lifetime가 invariant이면 한 iteration의 비용을 계산해 T를 곱할 수 있다. Shape가 달라져 비용이 Cₜ이면 `Σₜ Cₜ` 또는 cost model의 올바른 충분통계가 필요하다. `T×C₀`는 일반적으로 틀리다. 불변 physical pair는 불변 비용을 뜻하지 않는다.

## 7. Global/Regional DP의 최소 상태

### 7.1 Region을 잘라도 관계를 잘라 버리지는 않는다

Region U의 내부 변수는 실행 R, 공급 S, 필요한 boundary/layout/schedule 선택이다. Boundary B에는 U와 외부가 함께 관찰하는 선택을 둔다. 기존 edge와 typed facts의 역색인으로 incidence를 얻으며 범용 graph 객체를 새로 만들 필요는 없다.

| 경계를 넘는 관계 | Boundary가 보존할 최소 정보 |
|---|---|
| Shared producer | 한 producer의 semantic R 선택 또는 외부 관찰상 완전히 동등한 class |
| 외부 input/output | exact value/slot binding과 required supply, 선택 recipe가 외부 비용에 영향 주면 그 선택 |
| Shared upload/collect | action identity, 필요한 activation/guard demand, creation/cache owner, retention/lifetime 선택 |
| If/loop | 고정 facts의 guard 관계 참조, carried native/head DIRECT 계약, entry/exit·branch별 공급 선택. Backedge recipe/action/retention 차원은 없음 |
| Function | 공통 compiled body 선택과 actual/formal/return boundary; 독립 clone만 별도 변수 |
| Fused native cost | 하나의 kernel owner와 관련 선택. Kernel scope가 region을 넘으면 분리를 중단하거나 해당 선택을 boundary로 노출 |
| 메모리/스케줄 제약이 목적함수에 있음 | 미래 가능성을 바꾸는 live copies/schedule 상태. 평균 bytes 하나로 치환하지 않음 |

Guard의 runtime truth는 solver가 고르는 boundary 값이 아니다. 관련 guard 식과 shape/privacy/value facts가 고정이면 snapshot/region descriptor에 한 번만 둔다. Guarded supply policy나 activation에 영향을 주는 plan 선택만 key에 들어간다.

Backedge의 transfer 선택이 없어져도 native writer/head rule, version, guard·shape 및 body/entry/exit shared movement가 외부 영향을 가지면 key에 남긴다. 이 제한은 native output별 cheapest 하나만 유지해도 된다는 증명이 아니다. 기존 closed-tree/complete-boundary 조건을 그대로 적용한다.

### 7.2 Lazy min-sum message

Boundary assignment b와 shared-action interface a를 모두 고정한 region의 정확한 message는 다음이다.

~~~text
V(U,b,a) = min over internal decisions x {
    Σ costs owned by U
    + Σ V(childRegion, projected boundary(x,b,a))
    subject to all U constraints and required interface state a
}
~~~

각 relation/cost ID를 전체 scope를 볼 수 있는 하나의 owner region에 배정한다. Region 계층이 있으면 해당 scope의 최소 공통 조상에 배정할 수 있다. Child message에는 그 상위 소유 비용을 넣지 않는다. 예를 들어 copy 비용 `Ccopy × OR(demand₁,demand₂)`는 child별로 나눠 두 번 더하지 않는다. Guarded demand라면 OR는 runtime activation의 합집합이다. 현재 Exact 비용도 source와 여러 demands를 한 scope에서 평가한다. [shared action scope][shared-cost], [activation][activation]

`SharedMaterializationState`는 항상 별도 객체가 필요한 것은 아니다. Crossing supply/demand/schedule 변수의 assignment로 완전히 결정되면 b에 이미 포함된다. 내부 선택에 따라 외부 공유 비용이 달라진다면 demand/activation 결과를 interface state a로 노출하고 **각 a마다 최소 비용**을 유지해야 한다. 내부에서 가장 싼 a 하나를 먼저 골라 외부에 넘기면 최적해를 놓친다.

Static region DP에서 단순한 “이미 비용 지불함” bit로 shared action을 표현하지 않는다. 동일 static producer의 어느 dynamic value인지, 어느 guard에서 생성되며 얼마나 유지되는지까지 같아야 비용을 합칠 수 있다. 실제 동일 cache instance인 action의 비용을 한 소유자에게 모으는 방식이 더 단순하다.

### 7.3 최소 Java 구조

Rule/Offer는 이전 설계의 compact 구조를 재사용한다. 다음은 추가되는 최소 DP 상태를 설명하는 pseudocode다.

~~~java
record DpKey(
    int regionId,
    int boundaryAssignmentId,
    int residualDomainId) {}  // root domain+boundary만으로 결정되면 고정 0

enum SolveStatus { EXACT, LOWER_BOUND_ONLY, INFEASIBLE, INCOMPLETE }

record DpResult(
    SolveStatus status,
    Cost lowerBound,
    Cost bestFeasibleCost,
    int backpointerId) {}

record RegionView(
    int regionId,
    int[] internalOwnerIds,
    int[] boundaryVariableIds,
    int[] ownedRelationIds,
    int[] ownedCostIds) {}   // 기존 HOP/facts 참조; factor table 없음

// 모두 동일 immutable Snapshot 내부에 scope
RuleMemo ruleMemo;
BitSet[] rootActive;
BoundedMap<DpKey, DpResult> dpMemo;
~~~

`boundaryAssignmentId`는 정확한 tuple을 intern한 ID다. Hash만 같다고 동치로 취급하지 않는다. 선택된 event/activation interface a도 여기에 포함한다. Tree는 singleton boundary query `(h,q)`를 더 작은 배열 index로 처리할 수 있다. `Cost`는 현재 exact/보수적 하한 계산의 수치 계약을 재사용한다.

기존 RegionView를 그대로 복제하는 새 class hierarchy는 필요하지 않다. 한 배열 묶음으로 구현하고, 임의 region을 자동 탐색·최적 분할하는 framework는 만들지 않는다. 기본 분해는 검증된 tree 부분, structured loop/function region과 작고 명확한 separator에 한정한다.

### 7.4 Cache의 정확성과 메모리 상한

두 safe 방법 중 기본은 첫 번째다.

1. Region 내부 변수는 그 solve가 배타적으로 소유하며 outer search가 직접 assign/restrict하지 않는다. 영향을 주는 모든 외부 restriction은 boundary에 노출한다. Region query는 frozen root domains와 boundary만으로 지역 domain을 재구성한다. 이 경우 key에 내부 BitSet을 넣을 필요가 없다.
2. 지역 검색이 추가 restriction을 물려받으면 그 restriction을 `residualDomainId`에 포함하거나 결과를 해당 branch 내부에서만 사용한다.

방법 1의 소유권을 보장하지 못하면 방법 2가 필수다. 더 넓은 root domain의 최솟값은 제한된 branch의 lower bound일 수 있지만 exact 최솟값이나 그 branch에 합법적인 backpointer라고 할 수 없다. Region 내부 DFS의 partial 상태는 그 restriction을 key에 넣지 않는 한 entry-query의 exact cache로 재사용하지 않는다.

Incumbent bound 때문에 잘린 탐색의 값을 EXACT로 cache해서는 안 된다. Exact result는 해당 key의 모든 feasible completion을 평가했거나 sound certificate로 닫았을 때만 저장한다. Budget exhaustion/unknown leaf는 LOWER_BOUND_ONLY 또는 INCOMPLETE다. 앞 branch의 높은 비용 cutoff를 뒤 branch의 불가능 증거로 재사용하지 않는다.

Negative cache와 support witness는 revision/undo에 맞춰 무효화하거나 재검증한다. Cache eviction은 재계산만 늘리고 rule 제거·해의 변경을 일으켜서는 안 된다. Best incumbent의 backpointer는 cache entry eviction과 독립적으로 pin하거나 compact decisions를 복사해 복원 가능하게 한다.

Boundary가 커지면 모든 assignment를 table로 만들지 않는다. 요청된 key만 저장하고 bytes/entries 예산을 넘으면 least-useful entry를 버린다. Tuple intern storage와 backpointer도 예산에 포함하며, 사용하지 않는 key를 무제한 축적하지 않는다. Hit rate가 낮거나 signature 생성비가 재계산비보다 크면 그 region의 cache를 생략하고 같은 exact search를 계속한다. Exactness를 낮추는 top-K rule 삭제로 대체하지 않는다.

## 8. Tree DP와 Global Search를 결합하는 한 알고리즘

### 8.1 분리해도 되는 조건

Subproblem 내부의 모든 변수와 비용 항이 외부와 독립이거나 선택한 boundary로 완전히 분리되어야 한다. HOP edges뿐 아니라 shared owner, transient/guard, loop/function contract, shared materialization, fusion/activation/lifetime scope를 확인한다. Incidence가 모두 준비된 뒤만 tree로 표시한다.

처음에는 fanout이 있는 실행 owner와 control-flow/call/shared-action crossing을 경계로 둔 maximal tree 부분만 찾는다. O(N+E+I)의 DFS/역색인으로 충분한 보수적 분해를 만들 수 있다. 최적 separator/tree decomposition 탐색은 하지 않는다. Separator가 커도 correctness 문제는 없지만 DP 이익이 작아져 global search가 더 많은 일을 한다.

조건부 tree DP가 정확한 subproblem 최소를 반환하고 비용 소유권이 겹치지 않으면 global 합성에 exact cost-to-go로 쓸 수 있다. 공유 제약을 일부 버리거나 crossing movement를 0으로 완화한 DP는 lower bound로만 쓴다. Relaxed DP는 원래 feasible 집합의 상위집합과 과대평가하지 않는 비용을 사용해야 한다. 반대로 임의 rule을 빼서 작은 domain에서 계산한 최소는 안전한 하한이 아니다.

### 8.2 실행 순서와 pseudocode

~~~text
plan(program, tripCountContracts, fixedW):
    F = buildStructuredBindingsAndSummaries(program)
    reject recursion; retain exact entry/backedge/last-exit bindings
    distinguish boundary binding arms; backedge support is DIRECT-only
    infer/check only required shape/privacy/availability invariants
    U = buildNativeRuleUniverseOnce(Oracle, F)  // pure native schema cache
    attach conditional privacy/offers; reject only proven impossible options
    register exact-use checks; add joint handles only for nonseparable conditions
    D0 = root active domains
    bidirectionalFeasibilityWorklist(D0, readyScopes)
        use native direct check for backedges; ordinary supply for other bindings
        visit only dirty registered checks via binding/owner reverse references
    intern exact-equivalent rules; apply proven cheap global dominance
    identify independent tree parts / small typed separators
    prepare immutable query snapshot and disjoint cost ownership
    treeDP for required small output states; cache exact summaries
    obtain and independently validate a feasible incumbent if possible
    result = solve(rootRegion, emptyBoundary)
    decode selected native rules, supplies and owned actions once
    independently validate values, all guards, loop induction/exit, privacy,
        lifetime, canonical maps, runtime boundary and emission legality
    return exact optimum only when no unresolved/coverage/resource gap remains

solve(U,b):
    if exact cache hit for this snapshot/key: return result
    localDomains = rootDomains projected under b
        // outer search는 U의 내부 변수를 직접 제한하지 않음
        // 그렇지 않으면 residualDomainId에 그 restriction을 포함
    propagate affected registered checks; reuse unchanged local scopes
        # existential support YES is not a selected-plan universal verdict
        # UNKNOWN is not infeasible; no whole-program joint replay
    if a sound contradiction: cache INFEASIBLE; return
    if U is a closed conditional tree: exact bottom-up DP; cache; return
    if U splits into independent parts under b:
        solve each part; combine with U-owned crossing costs exactly once
        propagate lower/feasible/status separately; an unresolved required child
            prevents EXACT unless another certificate settles the parent result
    else:
        choose unresolved owner/supply/boundary variable lazily
        visit one value at a time; propagate; compute certified lower bound
        use tree messages as exact terms or explicitly relaxed lower bounds
        prune only with sound dominance/bound certificates
        recurse, restore domains and bump restored-domain revisions
    keep per-interface-state minima/backpointers; never emit source-product Rules
    cache EXACT only after all relevant completions are closed soundly
    otherwise cache bounds/status; retain verified incumbent separately
~~~

Ready는 native universe와 관련 활성 context/relations의 준비 완료다. Shared function owner를 callsite마다 재초기화하지 않는다. Worklist는 producer→consumer뿐 아니라 consumer pattern→producer/supply의 support도 갱신한다. Guarded optional use가 비활성인 환경에는 공급을 강요하지 않고, 모든 관련 reachable 환경의 공동 선택을 유지한다.

Root dominance는 전체 context에 대해 증명한 경우만 active root에서 영구 반영한다. Branch cost pruning은 undo 대상이다. 가장 안전한 최초 하한은 독립 owner의 최소 native 비용과 분리된 exact tree 비용을 한 번씩 더하고, 미확정 shared movement는 0으로 완화한 것이다. 같은 native cost를 tree message와 owner 최소에 두 번 더하지 않는다.

### 8.3 Loop/function 비용을 요약할 때

불변 shape/cost와 정당한 retention 아래 while 비용은 다음처럼 나눌 수 있다.

~~~text
Cwhile = Cinitializer + Centry + CretainedInvariantCopies
         + T × (Cbody + CordinaryBodyCopies)
         + (T+1) × Cpredicate
         + CfinalExitSupply
# Backedge transfer/action term is absent; DIRECT legality remains mandatory.
~~~

For의 bounds/increment 평가는 entry에서 수행하므로 위 T+1 predicate 식을 그대로 적용하지 않는다. 현재 occurrence-frequency semantics를 사용한다. Loop invariant operand라도 실제로 loop 밖에서 copy가 생성·retained되지 않으면 한 번 비용으로 줄일 수 없다. 갱신 값의 **일반 body consumer용** movement는 iteration마다 새 값에 대해 발생할 수 있지만 backedge 연결 자체에는 movement를 생성하지 않는다. 마지막 값의 외부 collect는 필요할 때 exit에서 한 번 발생한다. [실제 lifetime 과금][lifetime-cost], [invariant/updated 회귀][activation-test]

Nested loop에서 Tinner가 outer iteration에 따라 다르면 `Σouter Tinner(outer)` 등 정확한 cost-model 통계를 사용한다. 임의로 E[Touter]·E[Tinner]로 바꾸어 독립성을 가정하지 않는다. Branch activation과 function call 횟수도 같은 원칙이다. 모델이 추정치를 사용하면 global optimum은 그 모델의 optimum이지 실제 실행 시간의 optimum이 아니다.

## 9. 기존 코드에서 재사용할 것과 바꿀 것

**[확인]** 기존 BoundaryMessage는 separator assignment별 exact min-sum 값과 backpointer를 보관한다. Merge는 internal axes의 최솟값을 구하며, Regional은 비용/factor 소유권이 겹치지 않는 cover의 lower bound를 합한다. 이 의미는 새 DP와 직접 대응한다. 그러나 현재 merge는 boundary/output cells를 먼저 할당하고 internal Cartesian assignments를 순회한다. Generic lazy factor도 freeze 시 펼쳐진다. [message][boundary-message], [merge][boundary-merge], [regional cover][regional-cover], [lazy freeze][factor-freeze]

| 분류 | 현재 class/function | 변경·재사용 내용 |
|---|---|---|
| 수정 | PlacementProgramFacts.analyze / analyzeCfg / connectSequence | Structured def-use와 loop Head/Last binding query 제공. CFG 전체 재구축·optional refinement를 대체하되 필요한 scalar/shape 정밀도 유지 |
| 통합/수정 | PlacementJointInputAnalysis.executeBlock / loopFixedPoint / invoke / bindArguments | Guarded symbolic relation과 bottom-up call summary. Explicit environment closure는 검증 후 제거; call별 binding memo의 의미 재사용 |
| 유지/수정 | OccurrenceExecutionFrequencyFacts | n>1과 기대 빈도 분리. While/for/branch/call/nested loop별 비용 빈도 유지 |
| 분해 후 제거 | PlacementRelationClosure.closePlacementAndFeasibility / exactLoopPlacementSeed / exactSinglePartitionRealizationProofs | Candidate/proof rebuild는 제거. Entry/step/exit induction, 필요한 shape/privacy SCC, domain worklist로 분리 |
| 수정 | PlacementCandidateGenerator.buildNode / processCombination | Source-free Oracle rule Memo 생성. RuleMemo에 local best-cost를 섞지 않음 |
| 유지/수정 | CandidatePrivacyInputPruning / ExecPlacementPolicy | Stable early rejection, guarded 조건과 최종 선택 검증을 공유 |
| 제거 대상 | CandidateRealization/SupportClause/Proof 및 Anchor 대안 전개 | Java receipt identity를 DP key로 가져오지 않음. Exact value/slot/authority 의미는 facts에 유지 |
| 의미 재사용 | LocalCategoricalOptimizer singleton/block state-key pruning | Complete continuation key 안에서만 representative를 유지. Physical normalizedSignature 자체는 폐기 |
| 의미 재사용 | ExactPhysicalReducedSolver quotient/support reduction | Observational equivalence·support pruning의 조건 유지. Dense frozen factors에 대한 비교 구현을 새 표준으로 가져오지 않음 |
| 의미 재사용/저장 변경 | ExactCategoricalSolver BoundaryMessage / mergeBoundary / VE backpointer | Exact min/argmin/lower 의미만 재사용. 모든 boundary cell 배열 대신 query별 lazy message |
| 유지/수정 | RegionalSearchProblem prepared reduction / evaluation scopes | Immutable snapshot·scope index·검증된 incumbent 재사용. Legacy physical-surface identity 의존 제거 |
| 의미 재사용 | IncrementalRegionalOptimizer cover/lower/upper/stop status | 비중복 cost ownership과 bound certificate 유지. Default 5% 종료를 exact 결과로 표시하지 않음 |
| 분리 재사용 | PlacementCostSemantics.prepareExecutionCost / ExactPhysicalCostModel | Native/fused cost 단위, activation·frequency·lifetime 재사용. Full Alternative 및 ordinary dense cost freeze 제거 |
| 유지/수정 | LocalMaterializationSelections.derive / ExactMaterializationActivation | 선택된 exact-value demands의 action union·guard activation을 소유 region에서 한 번 평가 |
| 유지/수정 | PlacementEmissionTransaction.prevalidate / emitInternal, physical projector | DP backpointer를 native/supply 선택으로 decode. Anchor-free runtime 변경은 이전 문서의 별도 필수 작업 |
| 최소 신규 | DPMemo/RegionView 및 solve | 기존 package의 compact arrays + bounded cache + exact tree/DFS. 범용 graph/solver/logic framework를 추가하지 않음 |

추가 위치: [generator][generator], [closure][closure], [frequency][frequency], [regional 준비][regional-problem], [cost model freeze][cost-freeze], [materialization][local-copy], [emission][emission].

현재 production Local의 state key는 Alternative.normalizedSignature이며 input authority/support/realization 등의 정보를 포함한다. 그 필드를 모두 새 key로 복사하는 것도, output type만 남기는 것도 답이 아니다. Region 밖에서 실제 관찰할 수 있는 의미만 명시적으로 projection해야 한다. [현재 alternative][physical-alternative], [현재 local seed][local-seed]

## 10. 시간·공간 복잡도와 재계산

N=occurrences, R=ΣKₕ, L=전체 rule input 길이, E=exact guarded bindings, I=관계 incidence, F=필요 facts, M=recipe 상한, d=최대 선택 domain, b=region boundary 변수 수, a=별도 binary activation 상태 수, H=유한 invariant lattice 높이로 둔다. Udistinct는 서로 다른 query key 수, Qquery/Qmiss는 전체 query/cache-miss 수, Q는 search 방문 수, B는 cache byte budget이다. Cresident는 실제 상주 cache entry 수다.

### 10.1 단계별 상한

| 단계 | 시간 | 저장 공간·한계 |
|---|---|---|
| Structured def-use 구축 | 보통 O(N+E+I), 실제 shared expression 병합량 포함 | O(F+I). Environment Cartesian product를 만들지 않음 |
| 필요한 invariant 추론 | 유한 SCC당 O(H·Iₗ·transferCost) 수준 | 추상 fact 크기. Exact correlation은 최악 지수적일 수 있음 |
| 비재귀 call graph | O(functions+calls) topology + 각 구조 summary | Context 수는 여전히 클 수 있음. Call DAG라는 이유로 모든 실제 context가 하나가 되지 않음 |
| Oracle/RuleMemo | Unique signature/pattern miss 비용 + O(R+L) | O(R+L+RM+F+I). Bounded arity/M에서 기본 O(NK+EM)을 지향 |
| Feasibility propagation | 삭제에 영향 받은 관계의 실제 support 방문 합 | Active BitSets+witness cache. Joint support 최악 지수성 유지 |
| Exact signature intern | Hash 구성 O(L+관찰 signature 크기)와 충돌 시 비교 | 보통 O(R), 모든 pair 비교 불필요 |
| 제한적 dominance | 그룹당 naive O(k²·predicateCost) | 그룹·arity/관찰 범위를 제한; global 최대 dominance는 지수적일 수 있음 |
| Closed tree DP | Concrete abstract patterns, 고정 θ이면 O(Σᵣ(arity(r)+offerCount(r)))에 4 output 상태 상수 | O(4N+R+L)의 cost/backpointer 참조. 큰 selected subtree 복사 금지 |
| Conditional tree query | O(Qmiss·treeSolveCost + Qquery·keyCost) | O(Cresident·summarySize)≤B. Eviction 후 같은 key를 재계산할 수 있음 |
| Lazy region exact solve | 각 cache miss에서 실제 방문 local assignments + 하위 message queries | Udistinct≤min(Qquery, dᵇ·2ᵃ·Dres)인 단순 경우. Dres는 residual-domain signature 수. Schedule/interface domain도 필요하면 곱함 |
| Residual global search | 최악 전체 domain product, 실제 Q×propagation/bound 비용 | DFS trail+incumbent+B+facts. Table를 없애도 시간이 지수적일 수 있음 |
| VE 비교 기준 | 대표적인 dense 상한 O(V·d^(w+1)) | O(V·dʷ) message/backpointer와 factor 표현; induced width w에 의존 |

Tree bound는 child input partition만으로 비용이 결정되는 §6.2 조건의 강한 결과다. Child의 구체 rule까지 pairwise 비교해야 하면 O(Σedge KpKcM) 수준이 남으며, 그것을 partition-only tree DP라고 부르지 않는다. Compressed native relation의 확인 비용도 별도다.

최대 cache hit 비용은 공짜가 아니다. Raw boundary key를 만들려면 O(b+a) 읽기/비교가 들 수 있다. Interned projection ID를 재사용하면 보통 O(1) lookup에 가까워지지만 ID 구축 비용은 최초 또는 변경 시 부담한다. Collision 검증과 exact tuple 저장을 생략하지 않는다.

### 10.2 지원 범위 축소 전후

| 대상 | 줄어드는 작업 | 남거나 새로 생기는 작업 |
|---|---|---|
| Program Facts | 별도 CFG 객체, 전체 block reaching-def sweep, explicit environment 펼침을 대체할 여지 | Symbolic Join/Head/Last와 필요한 shape/privacy 해석. 이는 새 표현의 효과이며 static pair만의 효과가 아님 |
| Loop | Zero-iteration 공급 대안, iteration별 placement sequence, backedge transfer/retention/action 변수·비용항, candidate regeneration | Entry와 last-exit의 합법 이동, body 일반 이동, native DIRECT/joint induction, lifetime/frequency. Native rule 수 K 자체는 거의 줄지 않을 수도 있음 |
| Function | 재귀 SCC 수렴/무한 context 대응 | 비재귀 context별 actual facts와 공통 body choice. 현재 joint analysis가 이미 recursion을 거절하므로 이 항목의 추가 절감은 작을 수 있음 |
| RuleMemo | Source/anchor/support 조합별 중복 후보 | Abstract native variants, conditional offers와 metadata roles |
| DPMemo | 동일 complete query의 비용 재계산 | Key projection, intern, backpointer, cache miss/eviction 비용 |
| Optimizer | Tree 내부 선택을 작은 exact summary로 제거 | Shared/joint separator search와 activation state. Boundary가 크면 DP cache가 비싸질 수 있음 |
| 전체 | Candidate/proof/physical/dense factor 객체 할당과 반복 평가 감소 가능 | Structured relation 검증, induction, lazy search의 반복 방문. 비율 개선은 실측 전 미확정 |

T개 iteration에서 매번 K개 placement를 자유롭게 고르는 가상 정책 공간 Kᵀ는 stationary contract로 K개 선택으로 줄어든다. **현재 구현이 실제로 Kᵀ를 열거한다는 주장은 아니다.** 현재도 정적 compiled choices가 많으므로 이 식을 기존 성능 절감량으로 쓰면 안 된다.

Tree DP를 4-state로 줄여도 shared source의 수나 separator width가 자동으로 줄지는 않는다. 새 cost cache에 source tuples/SupportClause 전체를 key로 넣으면 메모리 문제를 옮기는 것뿐이다. Rule ID·exact boundary projection·선택 action identity만 사용하고, 캐시하지 않아도 정확한 재계산이 가능한 구조를 유지한다.

## 11. 구현·검증 순서와 acceptance criteria

### 11.1 단계별 도입

1. **Scope 계약과 기준 고정.** n>1/live-out 지원, recursion 제외, static physical pair, W/canonical/D1 정책, compile epoch와 cost objective를 기록한다. D1 등 아직 확정되지 않은 정책은 기존 논의와 동일하게 별도 parameter로 비교한다. 범위를 줄여 생긴 속도 개선과 표현 개선을 분리한다.
2. **Structured bindings를 shadow 계산.** 기존 CFG와 exact def-use/guard/entry/backedge/final-exit를 비교한다. 새 binding이 맞기 전 기존 facts를 제거하지 않는다. Optional refinement 차이는 explicit precision audit로 남긴다.
3. **Immutable RuleMemo와 feasibility.** Oracle/privacy의 동일 native variant coverage를 확인한다. 원래 legal set이 root/domain pruning 뒤 보존되는지 검사한다. 이 단계에서는 cheapest pruning을 넣지 않는다.
4. **Kernel/action 비용 소유권 정리.** 각 selected plan의 compute/내부 통신/외부 movement/빈도/lifetime 비용을 기존 evaluator와 비교한다. Fusion·metadata·multi-output·TR/TW를 포함한다.
5. **닫힌 tree DP부터 구현.** 조건을 만족하는 부분만 교체하고 모든 q의 최소·backpointer를 exhaustive oracle와 비교한다. Tree가 아닌 부분을 잘못 분류하지 않는 negative fixture를 둔다.
6. **Lazy boundary memo와 exact search.** Shared diamond, guarded demand, loop/function boundary를 하나씩 추가한다. Cache disabled와 enabled의 결과가 같아야 한다. Cutoff result를 EXACT로 cache하지 않는지 검사한다.
7. **증명된 dominance만 확대.** Signature quotient→small subsumption→fixed-boundary Pareto 순서로 도입한다. 각각 optimum/tie와 외부 계약 보존을 확인한다.
8. **선택·emission parity 후 기존 계층 제거.** 대체된 scope의 Closure/Proof/Physical Alternative/dense factor 생성이 실제 0인지 counter로 확인하고 참조가 남는 공통 경로는 보존한다.

### 11.2 작은 exhaustive fixture

작은 domain의 모든 R/S/boundary/schedule 선택을 reference checker가 열거한다. 이 열거는 검증 fixture에서만 수행하며 production planner의 구조가 아니다.

| Fixture | 반드시 확인할 결과 |
|---|---|
| n=2/3 loop와 외부 Z | initializer→각 body→마지막 exit 값. 이전 iteration copy 사용 거부 |
| 고정 pair의 loop self-support | entry 공급이 없으면 실패, 합법 entry upload가 있으면 귀납적으로 통과 |
| shape 축소/privacy 변경 | 첫 iteration만 통과하는 계획 거부. UNKNOWN을 infeasible로 둔갑시키지 않음 |
| 두 carried 값과 if | (A,B)/(C,D)만 허용; cross pair 금지. 최종 joint binding도 유지 |
| Nested loop | 각 동적 epoch와 마지막 값 구분; iteration 수의 합/곱 조건에 맞는 비용 |
| Shared producer §5.1B | 독립 H DP의 1을 global 값으로 쓰지 않음. 공동 최적 14 대 제한된 경로 101 |
| Shared upload §5.1C | Joint optimum 10, 소비자별 CP 14. 동일 copy를 두 번 과금하지 않음 |
| Reusable loop summary | (100,1)/(0,5)의 최적 선택이 T=2와 T=100에서 달라짐 |
| Shared function body | 공통 plan 최소 101; 금지된 call별 specialization 2를 채택하지 않음 |
| Same-output legal rules | feasibility 후에도 다른 input/cost 조건을 보존. 완전한 boundary key 안에서만 축약 |
| Cache/undo/resource | Cache off/on·eviction·collision·revision·branch 순서가 optimum에 영향 없음. Interrupted query를 exact로 재사용하지 않음 |
| Native runtime 경계 | REP/temporary broadcast, multi-output/fused owner, metadata, TR/TW, canonical map 및 recompile gate |

검증을 네 개로 나눈다. (a) Feasibility pruning 전후 **legal plan set 동등성**, (b) cost pruning 전후 **optimum/tie와 각 제거의 대체 certificate**, (c) evaluator의 **선택별 비용 동등성**, (d) reconstruction의 **instruction/값/map/lifetime 동등성**이다. 최적 비용 하나가 같다는 이유로 잘못된 feasible plan이나 잘못된 emission을 통과시키지 않는다.

**이번 분석에서 확인한 범위:** 별도 유한 수치 모델로 §5.1B의 네 선택 비용 101/104/111/14, §5.1C의 14/16/16/10, shared function의 101 대 2, T=2/100의 Pareto 선택 역전, full-domain 최솟값 1과 restricted-domain 최솟값 4의 차이를 열거해 확인했다. 이는 산술·알고리즘 반례 검증이며 SystemDS production/test 실행이나 새 planner의 검증이 아니다.

관련 기존 테스트는 boundary message/조건부 최소값, shared lifetime, cost pruning, loop identity, privacy 및 fused owner 테스트다. 기존 테스트가 새 architecture의 completeness를 이미 입증한 것은 아니다. [boundary message 회귀][boundary-test], [conditional argmin 회귀][boundary-prune-test], [cost pruning 회귀][cost-prune-test]

### 11.3 LogReg/GLM 측정

기존 v50 수치는 이전 W1 Docker 실행 기록이며 새 planner의 측정값이 아니다. 같은 source/JAR/data/seed/W/network/CPU/memory/JVM/BLAS 조건에서 run_LAN_docker.sh로 paired runs를 수행한다. Runtime이 실제로 n>1/static contract를 만족하는 scope를 기록하고, W2+ canonical micro-workload도 포함한다. [v50 근거][v50]

최소 ablation은 structured facts만, immutable rules/feasibility 추가, tree DP 추가, lazy regional cache 추가, safe dominance 추가다. 동일 지원 범위와 cost model에서 exact끼리 비교하고, 기존 5%-Local은 별도 anytime/gap 비교로 표시한다.

측정 항목:

- Facts: CFG 구축/분석 sweep 수, SCC/worklist 방문, explicit environments와 symbolic binding 수, invariant proof/UNKNOWN 수.
- Rules/pruning: 생성/active rule 수, 단계별 first reason, authoritative facts 준비 시점과 실제 제거 시점, 같은 query 중복 검사, Oracle/compatibility cache hit·miss.
- DP: region별 boundary 크기, distinct requested keys, hit/miss/eviction, key 구축 시간, saved recomputation, backpointer bytes, exact/bound/incomplete entry 수.
- Solver/cost: nodes·joint support 방문·LB prune·leaf 수, native cost 평가, selected-action union 재계산, 중복 source tuple 방문과 실제 객체 생성 수, VE intermediate cells/width 또는 DFS trail/cache peak.
- 전체: Analysis/Rule/Cost/DP/Search/Emission/total planning time, heap/RSS/cgroup peak, 누적 allocation. Allocation 계측 run과 일반 timing run을 구분한다.

최소 5회 paired 반복에서 중앙값·분산·최악값과 peak를 함께 보고한다. 성능 기준은 구현 전에 고정하며, 지원 계획을 더 제외하거나 최적성 보장을 낮춘 결과를 동등한 성능 개선이라고 표시하지 않는다. Cache hit가 높아도 key 구성과 memory 압력이 총시간을 늘릴 수 있으므로 실제 total과 allocation으로 판정한다.

### 11.4 기존 구조 제거의 gate

- Structured facts가 선언한 scope에서 정확한 source/guard/loop final value를 보존한다.
- Native rule universe와 feasibility가 canonical scope의 대응 legal plan을 누락하지 않는다. UNKNOWN과 정책 제외 이유가 분리된다.
- 모든 cost 축약은 완전한 boundary 의미와 단일 비용 소유권을 갖는다.
- DP cache off/on과 exhaustive reference의 최적 비용·결정·emission이 일치한다.
- Static loop contract를 entry와 모든 step, final exit에서 검증한다. Zero 경로를 제외해도 마지막 output 검증은 남는다.
- Resource/미해결 상태를 exact optimum/infeasible로 잘못 표시하지 않는다.
- 실제 workload에서 total time/peak와 새 계산량을 확인한 뒤 기존 객체 계층을 제거한다.

## 12. 결론과 보장 범위

이번 확정 정책은 backedge만의 supply/scope 선택 차원을 제거한다. 일반 edge Eg, backedge Eb라면 edge 표현 목표는 `O(Eg·M+Eb)`이고 scope 열거 시 `O(Eg·M·S+Eb)`다. Backedge는 writer/head native choices의 직접 지원만 검사하며 guard·shape·privacy의 공동 검증은 남는다. If-Else에는 별도 동일-output 제약을 추가하지 않는다. 최소 반례와 기존 테스트의 assertion별 전환은 [통합 명세 §6.6/§11.7](FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md)을 따른다. 특히 `ExactLoopPhiSupplySharingTest`의 body 공급 비용을 backedge 비용으로 오인하여 삭제하지 않는다.

**새 제한은 loop의 데이터 의존성을 없애지 않고, 정적 boundary summary로 다루기 쉽게 만든다.** n>1 보장으로 zero 경로를 없애고 재귀 call SCC를 제외할 수 있지만, entry·joint induction·마지막 값의 exit binding·shape/privacy·shared lifetime은 남아야 한다.

가장 단순한 정확한 해법은 source-free RuleMemo와 비용 DPMemo를 분리하고, **닫힌 tree는 required supply별 DP, 나머지는 완전한 boundary assignment별 lazy exact message와 global search**로 처리하는 것이다. 별도 일반 Constraint Graph나 사전 full dense materialization을 설계 계약으로 요구하지 않는다. 최악에는 lazy query 수도 전체 boundary 상태 수에 이를 수 있으며, 작은 cache에서는 재계산도 발생한다. Boundary를 작게 만들 수 없는 경우의 지수 복잡도는 남는다.

NativeOutput별 cheapest rule은 일반 규칙이 아니다. 그 상태가 모든 외부 관찰을 요약하는 경우의 DP 대표 선택으로만 정당화된다. 이번 문서의 correctness·복잡도 조건은 설계 의무이며 새 구현과 workload에서 아직 입증하지 않았다.

독립 정적 검토와 재검토에서 loop의 유한 counter/phase invariant, 반복 child의 tree 제외, region 내부 변수 소유권, cross-call 비용, tie-aware dominance와 eviction 재계산을 보완했다. 해당 검토 범위에서 추가 blocker를 찾지 못한 것이며 전체 구현의 correctness 증명을 대신하지 않는다.


[local-contract]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java#L27
[regional-options]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L55
[while-runtime]: ../src/main/java/org/apache/sysds/runtime/controlprogram/WhileProgramBlock.java#L98
[for-runtime]: ../src/main/java/org/apache/sysds/runtime/controlprogram/ForProgramBlock.java#L106
[recompiler]: ../src/main/java/org/apache/sysds/hops/recompile/Recompiler.java#L2379
[loop-seed]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L8896
[loop-ground]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L9717
[loop-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/LoopIdentityBackedgeClosureTest.java#L60
[upload-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDLocalMaterializeUtil.java#L181
[index-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/IndexingFEDInstruction.java#L176
[privacy-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/EarlyPrivacyPruningLegalSpaceParityTest.java#L90
[joint-analysis]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L49
[loop-predicate-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFactsConstantBranchTest.java#L77
[facts-analyze]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java#L78
[cfg]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java#L144
[joint-loop]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L896
[transient-facts]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L4427
[boundary-policy]: ../src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java#L489
[function-invoke]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L946
[function-runtime]: ../src/main/java/org/apache/sysds/runtime/controlprogram/FunctionProgramBlock.java#L104
[joint-query]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L672
[shared-cost]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java#L2245
[activation-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactActivationMaterializationCostTest.java#L60
[broadcast-compat]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java#L319
[quotient]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java#L30
[local-prune-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizerTest.java#L18
[compute-owner]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java#L980
[fusion-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactFusedKernelOwnerProjectionTest.java#L18
[activation]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactMaterializationActivation.java#L81
[lifetime-cost]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java#L2167
[boundary-message]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L1065
[boundary-merge]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L2250
[regional-cover]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L932
[factor-freeze]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L1860
[generator]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L179
[closure]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1049
[frequency]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFacts.java#L70
[regional-problem]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchProblem.java#L20
[cost-freeze]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java#L590
[local-copy]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/LocalMaterializationSelections.java#L354
[emission]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java#L337
[physical-alternative]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L100
[local-seed]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java#L184
[boundary-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalBoundaryMessageTest.java#L16
[boundary-prune-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/BoundaryPruningTest.java#L135
[cost-prune-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CostBasedPruningTest.java#L35
[v50]: FEDPLANNER_BOTTLENECK_MAP.md#L28
