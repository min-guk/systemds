# FedPlanner 추가 검증: 표현 완전성, Earliest Safe Pruning, 계산량

**Joint Feasibility 후속 기준:** [통합 명세 ADR21, §§5.5–5.7/11.8](FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md)이 이 문서의 포괄적인 joint/worklist 설명을 구체화한다. Exact RD/def-use로 필요한 consumer·boundary를 찾고, 비분리 조건만 joint 검사하며 관련 domain/선택/readiness 변경에만 재평가한다. Guard/owner/loop/call/shared-cost 관계는 보존하고, RD 집합의 교집합만으로 생략하지 않는다. 기존 소스 동작의 이력은 그대로 둔다.

**정책 이력 안내:** 이 문서는 앞선 분석 기록이다. 최신 사용자 확정 정책은 [통합 구현 명세 ADR09/20 및 §6.6](FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md)을 따른다. Loop Backedge는 native DIRECT-only이며 이 문서의 일반 backedge transfer/normalization 후보·비용 설명은 새 설계에 적용하지 않는다. Entry/최종 Exit·일반 body edge의 합법 movement 및 If-Else guarded join transfer는 유지한다. 현재 코드의 기존 동작을 기록한 내용과 새 정책을 구분한다.

작성일: 2026-10-13  
기준: HEAD c961a291811d23db104cc63a2b161e7e4b480e57 및 보존된 기존 미커밋 변경  
범위: 소스·기존 v50 artifact 읽기, 분석용 유한 모델 열거, 설계 문서 작성. Production/test 수정·빌드·DML/runtime 실행·commit/push 없음.

이 문서는 [기존 단순 Memo 설계](FEDPLANNER_SIMPLE_MEMO_REDESIGN_2026-10-13_KO.md)의 추가 검증이며, 아래 completeness 조건과 multi-output 보완을 우선 적용한다. **[확인]**은 소스/기존 artifact의 관측, **[추론]**은 그 해석, **[제안]**은 미구현 설계, **[미검증]**은 아직 입증되지 않은 사항이다. 테스트 assertion을 읽었다는 사실은 해당 테스트를 이번에 실행했다는 뜻이 아니다.

## 1. 결론: 해결 가능한 문제와 아직 해결됐다고 할 수 없는 문제

| 판단 | 결론과 근거 |
|---|---|
| canonical 범위의 모든 기존 legal plan을 표현하는가? | **아직 아니다.** Static join/body/schedule 정책과 보수적 guard 분석은 canonical 이외의 제한이다. 실제 native multi-output coupling도 기존 단일-output 설명에 추가해야 한다. |
| correctness를 보존할 수 있는가? | 구조적으로 가능하지만 표현 completeness, pruning soundness, selected-plan/emission 검증이 필요하다. 객체 이름을 제거하는 것만으로 입증되지 않는다. |
| Anchor/Proof/Closure를 제거할 수 있는가? | 배치 선택·proof route 객체·후보 재생성은 제거 가능하다. 정확한 값, source availability, loop 귀납, privacy, lowering legality는 facts와 predicate로 남는다. |
| 큰 계산이 실제로 사라지는가? | Source-product/SupportClause/overlay graph 구축은 새 경로에서 만들지 않을 수 있다. Joint feasibility·grounding·sharing 비용 계산은 남거나 search로 이동한다. |
| earliest pruning만 강화하면 v50 병목이 해결되는가? | 근거가 없다. 합법 조합도 반복 전개되며 v50 LogReg optimizer만 71.336초다. 더 이른 제거와 **합법 후보의 비전개 표현**을 함께 검증해야 한다. |
| 최종 권고 | 기존의 작은 Memo·Program Facts·직접 exact search를 유지하되, activation-aware pruning, multi-output owner binding, closed-facts 경계와 typed dependency worklist를 보완한다. 새 범용 Constraint Graph는 필요하지 않다. |

현재 구현 전체에 알려진 correctness 버그가 있다는 주장은 하지 않는다. 아래 반례 대부분은 **단순화 과정에서 생길 오류**를 드러낸다. 현재 코드는 관련 invariant를 이미 보존하는 검사와 테스트를 가진다. v50의 선택 결과 동등성과 전체 legal/cost surface의 동등성도 서로 다른 주장이다.

## 2. 표현 완전성의 실제 범위

### 2.1 비교할 계획 집합을 먼저 고정한다

~~~text
Lruntime   = 실제 runtime/lowering가 지원하는 계획
Lcanonical = Lruntime 중 fixed W, canonical layout, 네 partition 계약을 지키는 계획
Lbaseline  = Lcanonical 중 선택한 D1·static boundary/body·schedule 정책과
             보수적 환경 모델·검증된 native variants를 만족하는 계획
~~~

**[추론]** Lbaseline ⊆ Lcanonical이지만 둘이 같다는 증거는 없다. D1은 아직 확정되지 않았으므로 D1 off/on의 집합을 별도로 비교해야 한다. Policy 제외를 runtime 불가능이나 correctness pruning으로 기록하면 안 된다. Unknown canonical effect 때문에 구현 지원을 보류한 경우도 PROVEN_IMPOSSIBLE과 다르다.

필요한 보존 의무는 다음과 같다.

~~~text
Representation:
  ∀ p ∈ Lbaseline, ∃ a: decode(a) ≃ p and modeledCost(a) = modeledCost(p)

Sound emission:
  ∀ accepted assignment a: decode(a) ∈ Lbaseline

Feasibility pruning:
  solutions(domainsBefore) = solutions(domainsAfter)

Cost search pruning:
  best cost/tie under the declared objective is preserved
  // 모든 합법 계획을 유지한다는 뜻은 아님
~~~

Oracle variant coverage, exact value binding, 공유 execution owner, guard, recipe, activation/lifetime 및 schedule 선택이 대응되어야 한다. 완성 R/S에 비용상 다른 hidden schedule이 남으면 B&B가 모든 R/S를 방문해도 optimum을 보장하지 못한다. 기존 설계의 canonical schedule 계약을 그대로 적용한다.

현재 단계에서 위 의무는 **acceptance criterion**이다. 구현되지 않은 새 모델의 completeness가 이미 증명됐다는 결론은 철회한다. 기존 문서의 독립 검토는 당시 검토한 사례에서 모순을 찾지 못했다는 의미이며 전체 프로그램의 완전성 증명이 아니다.

### 2.2 실제 누락: 하나의 native 실행에 여러 output carrier

~~~dml
[Y, M] = transformencode(F, spec);
print(sum(Y));
# M은 이후 transformapply 등의 metadata input으로 사용할 수 있다.
~~~

**[확인]** MultiReturnParameterizedBuiltinFEDInstruction은 primary matrix를 federated로 만들고 metadata frame을 coordinator에 둔다. 생성자와 processInstruction에서 두 output을 구분한다. Oracle의 FunctionOutputRule도 output position 0과 1을 다르게 처리하며, FunctionOp.optFindExecType는 output carrier의 FOUT 선택을 owner 실행 타입에 연결한다. 이는 두 독립 transformencode 실행이 아니다. [FED multi-output][multi-runtime], [출력 생성][multi-process], [Oracle 출력 규칙][multi-oracle], [실행 owner][multi-owner]

**[추론]** 각 carrier에 독립 CP/FED rule을 선택하고 단독 compute 비용을 붙이는 구조는 부족하다. FED primary + CP/LOCAL metadata라는 기존 표시를 “한 FED 실행 + 한 CP 실행”으로 읽으면 중복 실행·중복 비용 또는 모순된 variant가 생긴다.

**[제안] 최소 보완:** 기존 ProgramFacts.isMultiReturnBuiltinOutputCarrier와 owner/output position을 재사용한다.

~~~text
R[nativeOwner] = one actual execution variant
carrier(o, j).nativeOutput = nativeOutputs(R[o])[j]
carrier(o, j).valueId = exact output value at position j

computeCost = one charge per native owner execution context
movementCost = per output value's selected/shared materialization
~~~

Ordinary rule은 단일 nativeOutput을 유지한다. Multi-output owner에만 작은 output-layout 배열을 한 번 보관하고 carrier Memo는 그 projection으로 둔다. Carrier는 독립 실행 변수를 만들지 않는다. 새 범용 multi-output graph가 필요한 것은 아니다. CP QR/LU/eigen/SVD도 출력별 LOCAL/movement와 owner 1회 실행을 분리해야 한다. 해당 연산의 FED 지원을 새로 가정하지 않는다. [carrier facts][multi-facts], [CP multi-return][multi-cp]

**Canonical 주의:** transformencode native 지원이 곧 모든 spec의 canonical 지원은 아니다. ROW dummycode의 row ranges 보존과 omit/unknown spec은 구분해야 한다. Canonical input에서 output rows/ranges가 보존된 검증된 variant만 이 반례의 admitted fixture로 사용한다. NativePlacementContinuityTransformEncodeTest도 이러한 경계를 구분한다. [관련 회귀][multi-test]

### 2.3 canonical 조건만으로 해결되지 않는 최소 반례

아래 layout 도메인은 필요 시 **분석용 가정**이다. DML 예를 제시했다고 특정 operand/privacy 조합의 runtime 지원을 검증한 것은 아니다.

| 사례 / 최소 DML | 잘못된 단순화 | 필요한 조건과 실제 근거 |
|---|---|---|
| A=abs(X); B=f(A); C=g(A); | B에 ROW witness, C에 COL witness가 있다는 이유로 A가 둘을 동시에 실행한다고 간주 | 같은 A owner의 R 하나를 고정하고 모든 활성 use를 검사. f/g의 layout-only 요구는 finite-domain 예시. [공동 factor][joint-factor] |
| if(p){X=A;}else{X=B;} Z=abs(X); | 각 branch에서 독립 R을 고른 뒤 Z의 한 정적 계획으로 취급 | Guarded S는 다를 수 있지만 같은 compiled Z의 R은 공통. [joint 분석][joint-analysis] |
| A=X; while(p){A=abs(A);} Z=sum(A); | 아직 backedge witness가 없다고 ROW 삭제; 또는 cycle만으로 grounding 승인 | Entry base + 모든 backedge induction + zero iteration. [loop seed][loop-seed], [identity loop 회귀][loop-test] |
| if(FALSE){A=f(P);} B=f(Q); | 도달 불가능 f(P)의 빈 domain을 공통 body 교집합에 포함 | Proven-inactive context 제외. Unknown predicate와 0 확률 추정은 제외 근거가 아님. [CFG 상수 분기][cfg-constant], [빈도 회귀][frequency-test] |
| X=A; Y=abs(X); X=B; Z=abs(X); | 같은 X라는 이름으로 writer/전송 cache 통합 | 정확한 read 시점의 definition/value version, 미래 writer 금지. [transient 검사][transient-test] |
| s=sum(X); X는 PrivateAggregation | PUBLIC s 때문에 X collect+CP를 허용하거나, private X 때문에 합법 LOCAL aggregate 결과도 삭제 | 입력 payload 이동과 native aggregate release를 구분. [privacy parity][privacy-test] |
| n=nrow(X); X는 private | LOCAL access를 모두 raw collect로 처리 | Metadata-only operand와 payload role 구분. [access kind][access-kind] |
| A=abs(X); B=f(A); C=g(A); | 두 consumer의 같은 collect를 두 번 과금하거나 실제 없는 lifetime 공유 | Exact value + creation/cache owner + guard demand + retention. [공유 비용][shared-cost] |

반복 guard의 precision에도 한계가 있다.

~~~dml
# p는 두 if 사이에서 바뀌지 않는 동일한 boolean 값이다.
if (p) { X=A; } else { X=B; }
if (p) { Y=C; } else { Y=D; }
Z=X+Y;
~~~

실제 공동 정의는 (A,C), (B,D)다. 현재 joint 분석은 structured branch 내부 correlation을 보존하지만 predicate expression에 path-insensitive이므로 (A,D), (B,C)를 추가할 수 있다. 추가 조합만 incompatible한 layout 모델이면 실제로 합법인 계획도 보수적으로 거절한다. **이는 기존 분석의 soundness 문제라는 주장이 아니라 runtime 경로 대비 completeness 한계다.** 같은 변수명 p라는 이유만으로 다른 iteration/call의 guard까지 합쳐서도 안 된다. [joint 분석 계약][joint-analysis], [branch 실행][joint-execute]

## 3. Pruning의 양화사와 UNKNOWN

### 3.1 한 정적 선택이 모든 관련 실행을 만족해야 한다

~~~text
∃ shared R, statically representable guarded S, schedule:
    ∀ environment e represented by sound facts:
        Active(o,e) ⇒ Legal(R, S.resolve(e), e)
~~~

다음은 충분하지 않다.

~~~text
∀ e: ∃ R_e, S_e: Legal(R_e,S_e,e)
~~~

예를 들어 shared R={rROW,rCOL}, e1에서는 rROW만, e2에서는 rCOL만 허용되면 각 환경에는 witness가 있지만 공통 선택은 없다. 동일한 오류가 shared producer의 consumer별 witness, function call별 body rule, correlated loop tuple에 나타난다.

**[제안] support 검사 결과는 세 가지로 구분한다.**

- SUPPORTED: 해당 relation의 활성 환경 전체에 대해 일관된 scope assignment witness를 확인했다. 다른 relation과의 global 합법성까지 증명한 것은 아니다.
- NO_SUPPORT: 현재 closed facts와 active domain 안에서 해당 value를 포함한 relation의 합법 completion이 없음을 증명했다.
- UNKNOWN: 예산 소진, 불완전 shape/facts, 약한 독립환경 검사 등으로 결정하지 못했다. Domain에 남긴다.

각 환경에서 witness를 따로 찾는 검사는 relaxation이다. **어느 mandatory 환경에서조차 witness가 없다면 제거 근거가 될 수 있지만, 모든 환경에 개별 witness가 있다고 SUPPORTED/global feasible로 승격해서는 안 된다.** 서로 다른 관계의 witness도 독립적으로 존재할 뿐 서로 양립한다는 뜻은 아니다.

### 3.2 Reachability와 applicability를 먼저 정의한다

새 active-domain은 **실행 가능성이 남은 occurrence/context**만 대상으로 한다. Known-unreachable branch의 empty Memo는 전체 불가능을 뜻하지 않는다. 같은 shared compiled body의 call들이 mutually exclusive여도 둘 다 reachable이면 하나의 R이 둘을 모두 만족해야 한다. 예상 빈도 0은 reachability 증명이 아니다.

현재 ProgramFacts CFG는 exact predicate로 일부 arm을 제외하지만 JointInputAnalysis는 두 arm을 보수적으로 유지한다. Facts 인터페이스에서 authoritative reachability와 conservative binding을 연결하는 규칙이 필요하다. 서로 다른 pass의 precision을 묵시적으로 혼합하지 않는다. [CFG][cfg-constant], [joint 실행][joint-execute]

실제로 reachable임이 확인된 환경의 NO_SUPPORT는 runtime 모델의 제거 근거다. 보수적 over-approximation에만 들어온 환경의 위반은 **선언한 abstract 환경 모델 안의 불가능**이며 실제 runtime 계획의 불가능까지 증명하지는 않는다. 이런 제거에는 ABSTRACT_ENVIRONMENT_SCOPE를 구분해 기록한다.

### 3.3 Unknown을 빨리 없애는 것과 안전한 제거는 다르다

| UNKNOWN의 원인 | 처리 |
|---|---|
| partial input pattern | Native query 계속 또는 conditional region 유지. FED 불가능만 증명했다면 CP 대안은 지우지 않음 |
| unknown shape/canonical effect | 증명된 conditional capability는 조건을 남김. 지원 variant 자체를 모르면 unresolved capability로 보고하며 새 variant를 발명하지 않음 |
| 미완성 reaching definitions/loop summary | closed-world no-support로 사용하지 않음. Facts가 닫힌 뒤 검사 |
| support 탐색 budget 소진 | UNKNOWN 유지. Timeout/OOM을 INFEASIBLE로 바꾸지 않음 |
| 최종 선택에서도 조건 미해결 | 검증된 existing recompile/guard 경계가 처리하지 못하면 그 계획을 emission하지 않음. 전체 프로그램 infeasible로 오기하지 않음 |
| 비용/실행 횟수 불확실 | 선언한 추정 cost model 사용. 추정치를 privacy/feasibility 증명으로 사용하지 않음 |

OracleFacade.EarlyFedFeasibility는 현재도 completion budget 소진 또는 unresolved evidence에서 UNKNOWN을 반환한다. 이 계약을 보존한다. [partial Oracle][oracle-partial]

## 4. Earliest Safe Pruning: 단계별 설계

**원칙 [제안]:** 필요한 정보가 authoritative해지고 sound한 검사로 불가능함을 증명할 수 있으면 즉시 제거한다. 아래 1–6은 **증명 종류**이지 반드시 직렬 실행해야 하는 stage 순서가 아니다. 이미 알려진 source/privacy 제약은 Oracle 호출이나 rule 할당보다 먼저 적용할 수 있다.

### 4.1 단계별 정보·조건·근거·비용

기호: R=전체 native rules, Eᵦ=guarded binding 수, M=recipe 수, K=owner 최대 domain, I=predicate incidence, a=relation arity. 비용은 제안 알고리즘의 상한/실제 방문량이며 측정 결과가 아니다.

| 단계 | 필요한 확정 정보 | 가장 이른 제거 조건 / 단위 | Soundness 근거 | 시간·공간 |
|---|---|---|---|---|
| 1. Native/shape | opcode·type·실제 operand role·shape/parameter·W, runtime variant coverage | 특정 native variant 불가능. Prefix 전체가 불가능하면 suffix 미생성. FED-only 거절로 CP까지 삭제 금지 | 모든 completion에서 native execution 불가능하다는 Oracle 증거 | 일반 abstract patterns 4^a 가능; determinant/prefix 검사와 cache miss 비용. Stored rules O(R+pattern 길이) |
| 2. Privacy/movement | 정확한 input position/value/privacy, native internal access/release, recipe | Execution 자체 불법이면 rule; COLLECT만 불법이면 option만; 조건부면 조건 유지 | 실제 입력과 모든 활성 환경에 대한 release/access 조건 | O(RM × 조건 검사), fine-grained facts/조건 크기 별도 |
| 3. Source/canonical | leaf 실제 map/shape/worker identity, 초기 정의 시점, canonical allocator/REP 계약 | 비canonical ingress 정책 제외; 존재 불가능한 leaf supply/temporal binding 제거 | 입력 inventory 완결성과 exact source/version. 일반 연산의 미래 결과와 초기 leaf를 구별 | 실제 source partition 수에 비례한 검증; epoch 내 한 번. Dependency 방문 추가 |
| 4. Compatibility | closed source-independent rule universe, active domains, ordered edge/guard binding | 고정한 value를 지지하는 해당 edge tuple이 전혀 없음 | 합법 global plan은 각 edge relation을 만족하므로 local NO_SUPPORT는 필요조건 위반 | 한 revise의 naive binary O(Kp Kc M); queue 반복량 별도. Full K² table 불필요 |
| 5. Joint/grounding | shared R, branch tuple, entry/backedge, actual/formal/return, output owner, privacy joint 조건 | 동일 고정 value를 포함하는 joint completion 부재; 확정 entry 불가능; owner공통rule 부재 | relation의 모든 활성 환경과 공동 입력에 대한 sound projection. 순환의 미증명은 부재 아님 | 일반 support search는 O(K^a × 환경 검증)까지 가능. 약한 검사 후 UNKNOWN 가능; 작은 relation만 explicit |
| 6. Global cost | feasible incumbent, complete finite decision space, 비음수 항과 certified bound | lower bound가 incumbent보다 큼; 또는 증명된 pointwise dominance | 아직 탐색하지 않은 모든 completion의 비용 하한 | Q search nodes × propagation/bound/leaf 검증. 비용 prune는 feasibility domain 삭제와 별도 |

Execution이 side effect/output 없이 폐기 가능하다는 이유는 별도의 HOP/liveness 의미다. 모든 output option이 없다는 사실만으로 side-effecting native rule을 지워도 되는지는 해당 opcode 의미에 따라야 한다. 초기 범위는 기존 planner가 다루는 value-producing operators로 명시한다.

### 4.2 현재 early pruning 중 재사용할 것

**[확인]** PlacementCandidateGenerator.enumerateInputCombinations는 보호 payload의 LOCAL 축을 Oracle 호출·key/fact 생성 전에 차단한다. MRV 경로는 partial Oracle가 INFEASIBLE인 prefix를 차단하지만 protected payload가 있는 FED-required subspace로 제한한다. CandidatePrivacyInputPruning.validate는 exact source/privacy authority, operand access kind, domain coverage를 다시 검사한다. [prefix privacy][generator-prefix], [MRV][generator-mrv], [mask authority][privacy-mask]

따라서 “현재 모든 pruning이 후보 생성 뒤에만 일어난다”는 진단은 틀리다. 새 구조에서는 이 early gate를 순수 native/조건부 Memo 생성과 연결하고, 필요 없는 source/fact 객체가 생기기 전에 적용한다. 현재 buildNode의 allowsEmission은 일부 key 생성 이후 호출되므로 **증거가 이미 확정된** emission rejection은 더 앞당길 여지가 있다. [생성/emission gate][generator-build]

**[확인]** Closure.constraintSupportedPolicyStates는 기존 incident relation을 따라 양방향 state support를 검사한다. Exact reduction도 unary→binary pruning 후 전체 factor freeze와 arc consistency를 수행한다. 새 구조에서 재사용할 것은 이 필요조건과 support 원리이며, 기존 Physical Alternative/frozen table을 먼저 만드는 순서는 재사용하지 않는다. [Closure worklist][closure-policy], [Exact 준비 순서][reduction-order], [Exact support][reduction-support]

### 4.3 너무 일찍 제거하면 틀리는 세 지점

1. **Native “FED 불가”와 pattern 전체 불가:** PUBLIC LOCAL 입력에는 CP가 가능할 수 있다. FED-only 탐색 공간에서만 해당 prefix 전체를 버린다.
2. **Source가 아직 없음과 앞으로도 없음:** canonical W descriptor가 존재해도 데이터는 없을 수 있고, 반대로 loop body의 native rule은 entry 단계에 결과가 아직 없어도 합법적이다. Body/backedge의 현재 witness 부재로 잠정 domain을 삭제하지 않는다.
3. **모든 입력이 각각 공급 가능함과 공동 공급 가능함:** 식의 input position, 동일 source owner, guarded tuple을 고정해야 한다. (A,B)/(C,D)를 (A,D)로 바꾸어 support를 찾지 않는다.

Closure의 transfer 중간 상태에서 source support pruning을 하지 않는 주석과 단계 경계는 이 문제를 방지한다. 새 no-rebuild 설계에서는 그 경계를 **closed Program Facts + prospective loop contract**로 바꾼다. [closure midpoint][closure-midpoint], [support fixed point][support-fixedpoint]

## 5. 최소 자료구조와 no-rebuild 알고리즘

### 5.1 저장할 것

기존 Memo/Rule/SupplyChoice는 재사용한다. 새 framework 없이 다음 배열·역색인을 추가한다.

~~~java
// 분석 snapshot 동안 불변: template, shape, native conditions, guarded bindings
NativeRule[][] rulesByOwner;
ProgramFacts facts;

// Search 상태만 가변. ID는 compact int, native rule 객체는 복제하지 않음.
BitSet[] active;                     // owner R / binding S / 필요한 boundary selector
boolean[] domainReady;              // 미생성 domain을 empty로 해석하지 않음
long[] domainRevision;              // 복원 때도 증가시켜 stale cache와 분리
int[][] incidentRelations;          // 기존 HOP/facts 관계의 typed row ID 역색인
boolean[] queued;
IntDeque worklist;
UndoTrail trail;                    // 제거 bit와 선택된 activation delta

// 작은 bounded caches. 전 domain product를 저장하지 않는다.
NativeSemanticCache nativeCache;
PairCompatibilityCache pairCache;
SupportWitnessCache witnesses;
~~~

위 이름은 역할을 설명하는 pseudocode다. 기존 컬렉션/primitive utility를 써도 된다. 별도 범용 graph node/edge, proof-route 객체, 관계마다 후보별 replica를 만들지 않는다. Typed relation ID는 INPUT_EDGE, BRANCH_BINDING, LOOP_CONTRACT, FUNCTION_OWNER, MULTI_OUTPUT_OWNER 등 기존 facts의 row를 참조한다.

| 역색인 | 용도 |
|---|---|
| producer owner → use edges | producer domain 변경 시 모든 consumer/S를 재검사 |
| consumer owner → ordered input edges | consumer pattern 제거 시 producer/S의 지원 여부 역방향 재검사 |
| value definition → guarded read bindings | transient/branch/function의 exact source 변경 범위 조회 |
| compiled owner → active invocation contexts | 함수 body 공통 R의 모든 관련 shape/privacy 검사 |
| native owner → output carriers | multi-output의 실행·출력·비용 coupling 보존 |
| loop → entry/carried/backedge/exit 관계 | provisional feasibility와 entry/induction 검사를 같은 SCC에 한정 |
| variable → joint/privacy/schedule predicates | pairwise 이외 제약의 영향 범위를 누락하지 않음 |

Index 공간은 I이다. 모든 predicate scope가 작고 일반 edge 위주면 선형이지만 call/guard 관계가 커지면 추가 비용이다. Last witness를 모든 edge×rule에 무제한 저장하면 O(EK)가 추가되므로 cache는 bounded로 두거나 필요한 지점에만 유지한다.

### 5.2 Facts의 닫힘과 immutable universe

**[제안]** Facts 분석과 candidate 탐색을 분리한다.

독립 native/type/확정 shape/source gate는 전체 facts fixed point가 끝날 때까지 기다릴 필요가 없다. 필요한 필드가 authoritative해지는 event에서 실행하고 결과를 native cache 또는 작은 rejection-region 기록으로 넘긴다. 반면 미완성 reaching-definition의 부재를 근거로 하는 support pruning은 기다려야 한다. 이 둘을 같은 global stage barrier로 묶으면 불필요한 pruning 지연이 생긴다.

~~~text
F = analyzeProgramToClosedSnapshot(program)
    // def-use/guard/shape/privacy/call facts: 필요한 SCC worklist 수행
    // 아직 발견하지 못한 writer를 '없다'는 증거로 쓰지 않는다.
    // 독립 필드가 확정되는 즉시 native/source/privacy early gate를 실행·cache

validateIngress(F)  // canonical source, exact input identity; early source rejection
create structural dependency indexes once from HOP/F
domainReady[:] = false
attach guarded bindings and owner/output-position projections
initialize closed supply/boundary domains from F/policy; mark those domains ready

for each potentially-active actual execution owner o:
    for abstract pattern region generated lazily:
        if cheapAuthoritativePrivacyOrShapeGate proves whole region impossible:
            countSkippedPatternRegion(reason); continue
        decision = nativeCache.query(pureSemanticKey(o, pattern, F))
        for supported conditional native variant r in decision:
            if executionImpossible(r, F): recordRuleRejection(); continue
            options = removeOnlyProvenIllegalRecipes(r, F)
            keep r with residual conditions and options
        if capability itself remains unresolved:
            record UNRESOLVED; do not fabricate a native rule
    freeze known Memo[o]; initialize active[o]
    domainReady[o] = nativeCoverageComplete(o)
    enqueue every incident relation whose required domains are now ready
    propagateReadyRelations()

// Unresolved coverage는 false domainReady와 전역 incomplete 상태로 남긴다.
propagate(allActiveVariables)  // 최초 모든 필수 relation을 검사
search with the same predicates/caches/domains
~~~

Oracle 열거 중에도 이미 closed인 producer/supply domain으로 특정 input partition의 support가 전혀 없음을 증명하면 그 pattern region은 할당 전에 건너뛸 수 있다. 미완성 domain 또는 prospective loop support에서는 이 검사가 UNKNOWN이어야 한다. 아직 다른 owner를 만들고 있다는 이유로 이미 검사 가능한 edge를 마지막 global Memo freeze까지 미루지 않는다. Ready event도 revision event로 queue에 전달하며, 뒤늦게 준비된 consumer가 producer를 줄이면 기존 producer BitSet만 갱신한다.

이 영구 미생성은 **search 이전 root-level 확정 pruning**에만 적용한다. Search branch의 임시 domain 축소를 근거로 native pattern을 영구 생략하지 않는다. Search 중에는 고정 universe의 active bit만 trail로 제거·복원한다.

단일-owner Memo를 freeze한 뒤에도 필요한 다른 domain이 미생성이면 relation은 pending이다. **Ready는 객체 할당 완료가 아니라 해당 domain coverage의 완결**을 뜻한다. Unresolved native variant가 남으면 알려진 rule만으로 “support 없음”을 증명할 수 없고 전역 validation-incomplete를 남긴다. Pending을 false로 취급하지 않는다. Loop SCC의 native universe를 source-free하게 모두 준비한 뒤 관련 cyclic predicates를 활성화할 수 있으므로 candidate 재생성은 필요 없다. 이러한 증분 생성은 전체 domain을 다시 복사하는 병렬 optimizer를 추가하는 것이 아니라 하나의 worklist를 생성 단계부터 재사용하는 것이다.

Native cache의 source-free universe는 snapshot 안에서 불변이다. Privacy/availability로 줄어든 active domain을 다시 Oracle 입력 후보로 보내 native universe를 재생성하지 않는다. 형상·프로그램·privacy·W·compile epoch가 바뀌면 새 snapshot이다. **No rebuild는 미래 recompilation에서도 같은 universe를 영구 사용한다는 뜻이 아니다.**

Facts 분석 도중에도 확정된 단조 사실로 local gate를 적용할 수 있지만, growing reaching-definition 집합의 “아직 없음”은 확정 사실이 아니다. 현재 captureSeedPrivacy가 ordinary same-block만 조기에 다루고 transient/function/carrier를 구분하는 이유도 여기에 있다. [seed privacy][seed-privacy]

### 5.3 양방향 incremental propagation

~~~text
propagate(changed):
    enqueueDeduplicated(incidentRelations(changed))
    while queue not empty:
        c = pop()
        if c is proven inactive: continue
        if required domains/fact fields of c are not ready:
            mark pending on their ready events; continue
        revisions = revisionsOfScope(c)
        if lastCompletedCheck(c) == revisions: continue

        for v in activeVariablesOf(c):             // producer, consumer, S 모두 포함
            for a in active[v] needing recheck:
                w = cachedWitness(c,v,a)
                if w still valid in all required active environments/domains:
                    continue
                status = support(c, v=a, active, F)
                if status == NO_SUPPORT:
                    trail.remove(v,a)
                    domainRevision[v]++
                    recordPrune(c,v,a,reason,epoch,revisions)
                    if active[v].empty and v is required by an active context:
                        return INFEASIBLE_IN_DECLARED_MODEL
                    enqueueDeduplicated(incidentRelations[v])
                else if status == SUPPORTED:
                    keep bounded witness
                else:
                    keep value; record UNKNOWN
        record lastCompletedCheck only for the revisions actually checked
    return CONSISTENT_SO_FAR
~~~

scope 안의 domain이 검사 도중 바뀌면 변경 전 revision에서의 결과를 최신 완료 결과로 기록하지 않는다. 같은 relation을 재queue하거나 내부적으로 안정화해야 한다. UNKNOWN에 재시도가 유용하려면 정보/예산 상태가 바뀌어야 하며 같은 상태를 무한 반복하지 않는다.

Producer→consumer에서는 producer rule 삭제로 해당 input 공급을 잃은 consumer pattern/S를 제거한다. Consumer→producer에서는 남은 consumer pattern 중 어느 것도 받을 수 없는 producer rule/S를 제거한다. 양쪽 모두 **도달 가능한 활성 환경에서 해당 exact binding이 필수**라는 조건 아래 적용한다. Inactive consumer나 사용되지 않는 optional binding으로 producer를 삭제하지 않는다. Source는 optimizer가 임의 선택하는 writer가 아니라 facts의 exact binding이다. Shared producer는 같은 domain 객체 하나를 모든 use가 참조한다.

예: R[A]={row,col}, B는 row만, C는 col만 받는 고정 domain이면 B edge가 col을, C edge가 row를 제거해 A가 empty가 된다. 반면 일반 cycle의 joint 모순은 binary propagation 후에도 남을 수 있으므로 CONSISTENT_SO_FAR는 feasible 판정이 아니다.

### 5.4 Loop와 function의 필수 반복

~~~text
Loop feasibility domain starts prospectively from supported native contracts.
Entry constraints restrict it using sources that exist before the loop.
Backedge constraints are checked under the assumed head contract.
Final selection must prove:
    every entry supplies the joint head tuple
    every body path preserves the contract on every backedge
    all zero-iteration/ordinary exits are legal
    no same-iteration cyclic supply manufactures a value
~~~

현재 provisional/greatest placement fixed point와 grounded/least fixed point를 하나의 “source reachability”로 합치지 않는다. 새 모델의 native domain은 source-independent여서 body rule이 source proof 도착을 기다려 생성될 필요가 없다. Entry+induction을 facts의 loop 관계에서 검사한다. [provisional seed][loop-seed], [seed discharge][loop-discharge]

Reaching definitions, joint guard 환경, shape/privacy, nonrecursive function summary는 실제 fixed point가 필요할 수 있다. 변경된 block/SCC/callee summary의 dependents만 방문하고 candidate 객체는 재생성하지 않는다. Recursion은 현재 exact frequency 분석의 제한대로 baseline 지원 밖이다. 이 제외를 “recursion runtime이 없다”는 주장으로 바꾸지 않는다. [joint loop][joint-loop], [recursion 제한][recursion]

### 5.5 비용 탐색과 cache의 경계

~~~text
hasUnresolvedPotentialCompletion = unresolvedCapabilityInPotentiallyActiveScope

search():
    if propagate(lastDecision) proves contradiction: return
    if feasibleIncumbent exists and certifiedLB(active) > incumbentCost: return
    if all choices complete:
        plan = canonicalSelectedSchedule(R,S,F)  // 숨은 일정 선택 없음
        if independentLegalityCheck(plan, all active environments, loops, lowering):
            updateIncumbent(evaluateCost(plan))
        else if unresolved:
            hasUnresolvedPotentialCompletion = true
            report validation gap, not a feasible incumbent
        return
    v = chooseUnresolvedVariable()
    for a in active[v]:
        checkpoint = trail.mark()
        assign(v,a); search()
        undo(checkpoint)
        bumpRestoredDomainRevisions()

finish():
    if resourceLimitHit: return RESOURCE_INCOMPLETE with optional incumbent
    if hasUnresolvedPotentialCompletion:
        return FEASIBLE_BUT_OPTIMALITY_UNPROVEN if incumbent exists
               else VALIDATION_INCOMPLETE
    return OPTIMAL_IN_DECLARED_MODEL if incumbent exists
           else INFEASIBLE_IN_DECLARED_MODEL
~~~

미해결 leaf 또는 native capability를 건너뛰고 나머지 탐색이 끝났다고 exact optimum을 반환하지 않는다. 위 flag는 보수적으로 유지한다. 미해결 부분 전체가 certified bound 또는 확정된 별도 제약으로 최적해와 무관함을 증명한 경우에만 그 부분을 완료 처리할 수 있다. Incumbent가 없는 미해결 상태도 INFEASIBLE이 아니다.

Lower bound는 execution owner의 **각 실행 context**에서 남은 R의 native 비용 최솟값을 가중 합산할 수 있다. Multi-output carrier는 compute를 추가하지 않는다. 아직 sharing/activation이 달라질 수 있는 movement는 0으로 두는 보수적 bound가 안전하다. 이미 선택한 consumer별 이동 합을 bound로 쓰면 나중에 가능한 공유를 놓쳐 optimum을 자를 수 있다. 비음수 비용·빈도, downward/certified 산술, 검증된 incumbent가 필요하다. 동률 선택 계약을 지키려면 단순히 ≥로 자르지 않는다. [수치 원리][dyadic]

Cost pruning은 search branch를 닫는 것이지 그 rule이 runtime 불가능하다는 판정이 아니다. Feasibility BitSet과 cost-pruned search state의 원인을 분리한다. 새로운 incumbent 때문에 전체 Memo에서 rule을 영구 삭제하지 않는다.

| cache/index | key 및 유효 범위 | 피할 오류 |
|---|---|---|
| native capability | 순수 opcode/type/shape/parameter/pattern, 고정 W/config/runtime revision | 결과 interning을 Oracle 호출 회피로 보고; privacy 결과를 공통 native cache에 넣기 |
| pure pair compatibility | rule IDs, recipe, position/role, 필요한 shape/조건 revision | availability/source identity가 필요한 결과를 partition pair만으로 재사용 |
| support witness | relation ID, variable/value, guard/context, 관련 active domain revision | 환경마다 다른 shared R witness를 하나로 합치기 |
| no-support | 동일 closed snapshot과 검사된 domain 또는 그 검증된 축소 | backtracking으로 확장한 domain에 부정 cache 재사용 |
| native/movement unit cost | 정확한 shape/sparsity/W/variant/cost policy | call frequency와 guard union을 단가에 숨겨 여러 번 과금 |
| activation/schedule delta | 선택된 exact values, creation/cache/retention, guarded demand | 동일 HOP ID만 보고 다른 iteration/call의 값을 공유 |

정적 stage와 optimizer가 같은 pure predicates를 사용한다. Final checker는 cached truth만 신뢰하지 않고 선택된 값/slot/실제 action과 lowering 계약을 재검증한다. 이것은 의도적인 독립 검증이며 제거 대상 중복으로 보지 않는다.

## 6. Soundness와 completeness가 유지되는 조건

**[추론]** 다음 조건에서 domain pruning은 합법 assignment를 보존한다.

1. Universe가 선언한 지원 범위의 모든 native variant/recipe/schedule 선택을 포함한다.
2. Facts가 실제 실행을 누락하지 않고 activation, value version, input/output position을 보존한다. Conservative 추가 환경으로 잃는 completeness는 따로 명시한다.
3. Relation은 실제 legality의 필요조건이다. NO_SUPPORT는 해당 value를 포함하는 모든 local completion이 불가능함을 뜻한다. UNKNOWN은 삭제하지 않는다.
4. Local completion의 환경·shared owner 값은 relation 내부에서 일관된다. 약한 relaxation에서의 성공을 global 합법성으로 쓰지 않는다.
5. 변경된 domain의 모든 dependency가 queue에 도달한다. Cache가 snapshot/backtrack과 일치한다.
6. Loop의 provisional hypothesis를 인정한 검사를 하되 leaf에서는 entry와 induction을 모두 검증한다.
7. 완성 선택의 조건·source grounding·multi-output owner·schedule·privacy·emission legality를 별도로 검증한다.

현재 ExactPhysicalPlacementProjector.project와 PlacementEmissionTransaction.prevalidate/emitInternal은 선택의 lowering와 transactional 검증 경계다. 새 구조도 이 경계를 유지하며 proof/clause 객체 대신 선택된 owner/value/slot/recipe/schedule의 의미를 검증한다. 불일치 계획을 runtime fallback으로 고치지 않는다. [projector][projector], [emission prevalidation][emission]

증명은 간단하다. 삭제된 value를 쓰는 global legal assignment가 있다고 가정하면, 그 assignment의 relation scope projection은 NO_SUPPORT를 반박하는 witness여야 한다. 정확한 NO_SUPPORT와 모순이다. 이 논리는 삭제마다 적용되므로 fixed point까지 보존된다. **잘못된 relation, 미완성 facts, quantifier 교환, 누락 variant**는 이 증명의 전제를 깨뜨린다.

Propagation이 충분히 강해야만 sound한 것은 아니다. 약한 pruning도 마지막 exact search/checker가 완전하면 올바르다. 반대로 더 이른 삭제 수가 많아도 합법 assignment를 잃으면 실패다. Cost dominance는 compatible completion 보존과 전체 비용의 점별 비악화를 추가로 증명해야 하며 같은 partition의 cheapest만 남기는 규칙은 충분하지 않다. [실제 비용 반례][cost-test]

## 7. 기존 v50 병목: 제거·이동·신규 계산

### 7.1 실제 측정 기준

**[확인]** v50은 c961a29181의 frozen engine이며 현재 미커밋 sparse 작업을 포함한 새 runtime 측정이 아니다. COFEE 50,000×128, W1, Docker LAN, DP-local, cgroup 24g/JVM 16g/cpuset 0–7 조건이다. LogReg→GLM 각각 한 번의 runtime attempt는 완료했지만 역사적 “각각 3회 20초” 최종 gate는 실패했다. 이번 요청을 수행하며 새 workload를 실행하지 않았다. [v50 launch][v50-launch], [v50 비교][v50-comparison]

| v50 최종 측정 | LogReg | GLM |
|---|---:|---:|
| Analysis / Closure | 275.767753421 s | 77.511758239 s |
| Model | 6.176832464 s | 5.283612656 s |
| Cost | 6.627712211 s | 9.741299703 s |
| Optimizer | 71.336489037 s | 27.836008081 s |
| Full initial planning | 363.642834428 s | 125.151026729 s |
| Coordinator cgroup peak | 9,100,935,168 bytes | 4,798,939,136 bytes |
| Worker cgroup peak | 715,948,032 bytes | 755,593,216 bytes |

Full initial과 CandidateE2E total은 측정 경계가 다르며 위 phase 합과 완전히 같지 않다. 누적 allocation도 cgroup peak와 다르다. 현재 v50의 solver variable/factor/cell/width별 상세 계측과 클래스별 retained heap/GC breakdown은 확인할 수 없다. [v50 병목 근거 정리][v50-map]

| Terminal v50 작업 counter | LogReg | GLM |
|---|---:|---:|
| CandidateRuleKey 생성 | 25,137 | 16,157 |
| CandidateRuleFact 생성 | 208,896 | 125,270 |
| Explicit SupportClause 생성 | 2,313,241 | 1,087,972 |
| Proof graph/query | 103,616 | 33,029 |
| Graph rows | 16,638,542 | 6,563,050 |
| Proofs consumed | 6,042,753 | 662,839 |
| Required-input checks | 5,234,940 | 866,955 |
| Binding candidates examined | 7,066,618 | 1,201,621 |
| Native fact visits/recomputed | 59,608 | 29,920 |
| Emission visits / rebuilt / reused | 160,260 / 55,847 / 104,413 | 93,149 / 15,683 / 77,466 |

이 값들은 terminal snapshot이다. 누적 live snapshot들을 합산하지 않는다. Key/Fact 생성 수는 새 Memo의 unique native rule 수가 아니며 graph rows/proof consumption도 객체 수와 동일하지 않다. 현재 artifact만으로 “몇 개 native rule을 더 일찍 제거하면 이 작업이 얼마 감소한다”를 계산할 수 없다. [GLM terminal counters][v50-glm-counters], [LogReg terminal counters][v50-lr-counters]

LogReg exclusive phase는 proof overlay 68.840초/누적 allocation 44.852GB, topology 49.738초/36.525GB, dependency pruning 27.669초/15.492GB, direct proof consumption 28.279초/19.344GB다. 여기서 GB는 artifact byte 값을 반올림한 십진 단위다. 각 phase의 callback이 수행하는 필수 의미 검사가 새 predicate에도 남으므로 **이 초를 통째로 빼서 속도 개선을 예측하지 않는다.** [phase allocation][v50-map-phases]

v50 raw-circuit reuse는 LogReg 4,251,588회, GLM 21,235회였지만 v49 대비 proof 소비 등 주요 counter는 그대로였다. LogReg overlay allocation은 줄었지만 시간이 거의 같았고 full planning은 더 길었다. 단일 run이므로 변화의 인과를 단정하지 않는다. Cache hit 증가와 전체 문제 해결은 같은 지표가 아니다. [reuse 비교][v50-map-reuse]

### 7.2 새 모델의 계산 귀속

| 현재 작업 | 새 구조에서 실제로 줄일/없앨 대상 | 남거나 이동하는 의미 / 새 비용 |
|---|---|---|
| Concrete source axes → candidate/fact/realization | 동일 abstract pattern의 source별 객체 생성·복제 | exact definition/use binding은 facts에 남음. Guarded support query 비용 발생 |
| NativeContinuity proof graph/overlay/topology/skeleton | query별 graph/proof route 구축과 그 위의 반복 pruning | relation predicate의 support 탐색, loop induction, source availability로 이동 |
| Closure candidate 재생성 | stable epoch의 rule 재생성, 재intern, 재publication | Facts SCC fixed point와 active-domain worklist는 남음 |
| input/source product 전개 | Oracle 앞의 concrete realization Cartesian product, 전체 support routes | abstract 4^arity pattern, 복잡한 joint relation의 search는 남음 |
| Physical Alternative 생성 후 dedup | 미선택 physical 후보 전체 materialization | 완료된 선택의 action schedule만 생성; 많은 leaf가 나오면 이 비용도 반복될 수 있음 |
| Generic lazy factor freeze | 기본 direct search에서는 전 domain table 생성 제거 | compatibility 평가/지원 witness/DFS 방문으로 시간 이동. 반복 query 가능 |
| Cost factors·shared movement | 후보별 중복 descriptor/table | 단가 lookup, demand union, activation/lifetime, 정확한 owner 비용은 필수 |
| 기존 DP/Local | 기존 domain/factor 구축 의존 해제 | direct exact search가 더 느릴 수도 있음. v50은 DP-local이므로 exact-vs-heuristic 비교를 혼동하지 않음 |
| Anchor pool/range 탐색 | fixed W/canonical로 선택 공간 제거 | source validation과 native canonical preservation 검사는 신규 필수 |
| 반복 정적 query | semantic cache와 revision 기반 skip | cache key/hash·메모리·eviction·dependency index 유지 비용 |

현재 enumerateInputCombinations와 PhysicalModel.alternatives는 일부 일반/잔여 경로에서 전개가 남는다. SupportRelations 및 NativePlacementContinuity가 수행하는 exact binding 의미는 없앨 수 없다. “기존 객체가 0개”는 재표현의 증거일 뿐 동등한 조합을 새 solver에서 더 많이 계산하지 않았다는 증거가 아니다. [생성기][generator-prefix], [physical alternatives][physical-alternatives], [support pruning][support-fixedpoint]

## 8. 시간·공간 복잡도와 earliest 측정

### 8.1 목표 공간과 실제 비용

기본 Memo/input relation의 O(NK+EM)은 bounded arity와 상수 recipe의 목표다. R은 전체 native rule 수이므로 R≤NK이며, Eᵦ는 guard/context를 구분한 binding 총수다. 아래 M은 rule의 availableOutputs 또는 binding의 recipe 수 상한이다. Rule 저장량 R과 recipe 저장량 RM은 서로 다른 항이다. 실제로는 pattern 길이 L, Program Facts F, reverse incidence I, bounded cache B, live search trail T가 추가된다. Multi-output layout vector는 전체 output slot 수에 비례한다. 가변 scope/retention domain도 별도다.

~~~text
Base storage ≈ O(N + R + L + R*M + Eᵦ*M + F + I + B + T)
~~~

Binary edge revision 한 번은 naive하게 Kp×Kc×M 검사를 한다. Last-support cache로 재검사를 줄일 수 있으나 역색인과 cache 저장 비용이 생긴다. 한 단조 propagation 구간에서는 value당 한 번 삭제되지만 sibling search branch에서는 다시 삭제될 수 있다. 전체 방문량은 검색 Q와 relation support 비용에 달린다.

Joint relation은 a개 변수/복잡한 guard 환경에서 지수적일 수 있다. Lazy predicate는 저장하지 않는 것이지 계산하지 않는 것이 아니다. B&B의 최악 시간은 domain product 규모다. Existing VE의 dense intermediate는 width w/domain d에서 대략 O(V d^(w+1)) 시간, O(V d^w) message/backpointer 공간이며 generic lazy factor가 freeze에서 table로 바뀔 수 있다. [freeze][solver-freeze], [elimination][solver-eliminate]

### 8.2 “가장 이른 시점”의 측정 가능한 정의

**[추론]** 후보가 어느 global completion에도 속하지 않는지 판정하는 일 자체가 exact feasibility 문제일 수 있다. 모든 후보의 의미론적 최초 제거 시점을 싸게 알려 주는 별도 oracle는 없다. “earliest”를 계측 가능한 수준으로 정의해야 한다.

| 시점 | 의미 |
|---|---|
| t_info_ready | 해당 증명 규칙에 필요한 facts/domain revision이 authoritative해진 시점 |
| t_proven | 구현한 sound checker가 그 revision에서 실제로 불가능을 증명한 시점 |
| t_removed | domain bit/recipe/pattern region을 실제 제거한 시점 |
| earliest_replay | 작은 fixture의 revision event를 재생해 같은 checker가 처음 NO_SUPPORT를 낼 수 있었던 시점 |

t_removed−t_proven은 제거 전달 지연이고, t_proven−t_info_ready에는 queue 대기와 검사 비용이 섞인다. earliest_replay는 **정한 checker 집합 기준**이며 가능한 모든 정리 증명의 earliest를 뜻하지 않는다. UNKNOWN은 최초 제거 가능 시점이 미확정이라는 결과로 남긴다. 큰 workload에서 이 counterfactual replay를 매 후보마다 실행하면 또 하나의 비싼 proof 분석이 되므로 작은 exhaustive fixture와 sampling에 한정한다.

Cost-bound로 닫힌 node에는 feasible rule 삭제 시점을 붙이지 않는다. Shared body/multi-output의 원인이 같은 제거를 여러 carrier의 새 native rule 제거로 중복 계산하지 않는다.

### 8.3 필수 counters와 관측 비용

| 지표 | 기록 방법 |
|---|---|
| native/active rules | abstract patterns tried/skipped, 실제 generated native variants, unique retained rules, active owner-domain values를 분리 |
| 단계별 제거와 이유 | primary cause를 한 번 기록. 옵션만 제거/owner rule 제거/지원정책 제외/UNKNOWN/cost-pruned node 구분 |
| earliest 지연 | epoch·event sequence·reason·dependency revision과 위 세 시점. 상세 로그는 작은 fixture 또는 sampled IDs만 |
| 중복 검사 | (checker,semantic query,revision)의 actual evaluation vs cache hit vs repeat; domain/환경 변화로 필요한 재검사는 별도 |
| Oracle/cache | request, miss, actual decide, interning hit, eviction/recompute. Interning은 miss 회피가 아님 |
| compatibility/worklist | pair/joint evaluation, witness scan, enqueue/dedup/pop, domain deletion, SCC round, reverse propagation 방향 |
| materialized combinations | native abstract pattern, concrete input tuple, source tuple, joint environment, SupportClause, Physical Alternative 각각 |
| solver/cost | search nodes, support UNKNOWN, bound eval/prune, feasible leaves, schedule eval, factor logical product/encoded cells/peak intermediate |
| 시간 | analysis/facts/native/privacy/propagation/cost/search/emission exclusive wall/CPU, fullInitial. Nested inclusive 합산 금지 |
| 메모리 | current-thread allocated bytes, object count/class sample, retained/peak heap, coordinator RSS, container cgroup peak를 구분 |

기본 실행은 aggregate counters와 bounded sampled reason log만 쓴다. 모든 candidate의 모든 provenance를 영구 저장하는 새 PruneProofGraph는 만들지 않는다. Audit reason은 해당 fact/relation ID와 epoch/revision으로 재검사할 수 있어야 한다. 기존 SearchSpaceMetrics/CandidateFormationTiming을 재사용하되 감소한 class count와 전체 allocation을 함께 관찰한다. [metrics][metrics], [timing][timing]

## 9. 작은 exhaustive correctness fixture

### 9.1 비교 알고리즘

~~~text
reference = enumerate every finite R/S/P/schedule assignment in tiny fixture
legalBefore = {a | independentCompleteChecker(a, facts)}

for each feasibility-domain deletion event:
    legalContext = legalBefore restricted by the current search decisions
    assert every a in legalContext remains representable in current domains
    assert deleted value has no legal completion in legalContext

legalAfter = enumerate assignments after feasibility propagation only
assert canonicalSemanticPlans(legalBefore) == canonicalSemanticPlans(legalAfter)
assert perPlanCostBefore == perPlanCostAfter
assert searchOptimum == bruteForceOptimum
assert canonicalTieWinner(search) == canonicalTieWinner(bruteForce)

for every partial assignment with at least one legal completion:
    assert certifiedLB(partial) <= minimum complete cost below partial

for each cost-bound search-node closure:
    assert incumbent was a completely verified legal plan
    assert certifiedLB(node) > incumbentCost  // declared tie contract도 보존
    assert certifiedLB(node) <= bruteForceMinimumBelow(node)
    // 이 subtree에는 합법이지만 비최적인 계획이 있어도 정상이다.
~~~

Representation가 다르면 old/new assignment 번호나 Java identity 대신 실행 owner/value slot/guard/supply/schedule의 semantic projection으로 비교한다. 여러 lowering이 의미·비용 모두 같을 때만 quotient해 비교한다. **동일 optimum 하나만 비교하면 비최적 legal plan을 잘못 지운 오류를 놓친다.**

### 9.2 필수 fixture 목록

| Fixture | 전수 비교할 반례 / 기대 |
|---|---|
| shared diamond | A rule 하나, B/C 다른 공급, 동일 collect 1회; A의 소비자별 모순 choice 금지 |
| guarded pair | (A,B)/(C,D)만 활성; cross pair 금지; 한 환경만 합법이면 전체 shared rule reject |
| inactive branch | proven FALSE call의 empty domain이 전체 infeasible을 만들지 않음; unknown p는 검사 유지 |
| repeated predicate | 동일 p value의 실제 2환경과 conservative 4환경을 별도 reference로 비교. Completeness 손실을 숨기지 않음 |
| loop | 합법 entry+identity backedge 유지; absent entry, incompatible backedge, future source, 0회, 두 carried 값 correlation |
| function | 실제 shared owner R 교집합, 호출별 shape/privacy/비용, return slot, stale recompile epoch; clone만 독립 |
| transient | overwrite 전후 exact writer, alias 경유 D1 우회, future writer 금지 |
| privacy | PrivateAggregation sum release vs raw collect; private nrow metadata; unknown shape/조건 유지 |
| multi-output | transformencode owner 1회, primary/metadata 이종 output, carrier slot 혼동 금지, output별 movement |
| canonical boundary | noncanonical ingress와 native output을 policy 제외로 구분; W1 REP lowering; rows<W upload 불가 |
| backtrack/cache | branch1의 no-support를 undo 후 branch2에 재사용하지 않음; same revision duplicate evaluation suppression |
| joint-only conflict | X=Y, Y=Z, X≠Z의 2값 모델: 모든 binary edge에 support가 있어도 complete solution은 0 |
| shared cost/dominance | 단독 4/6, 연동 100/1의 legal totals 104/7. Cheapest 단독 rule 삭제 금지 |
| schedule | 동일 native/recipe에서 creation/cache/retention에 따라 합법성·비용이 다르면 selector가 이를 모두 표현 |

기존 참고 테스트는 EarlyPrivacyPruningLegalSpaceParityTest, CandidateSelectionPruningOracleTest, StructuralGroundingPlanSpaceTest, LoopIdentityBackedgeClosureTest, ExactConditionalNativeProductPhysicalParityTest다. Undefined loop value DML은 현재 frontend가 이미 거절하므로 이를 새 optimizer가 해결한 runtime bug로 보고하지 않는다. [early parity][privacy-test], [bounded exhaustive][bounded-test], [grounding 회귀][grounding-test], [physical parity][physical-test]

### 9.3 이번에 수행한 분석용 유한 모델 열거

Production/test 파일을 만들거나 실행하지 않고 작은 Python 유한 집합 열거로 다음 수학적 반례만 확인했다.

| 검사 | 관측 |
|---|---|
| 환경별 ∃R 대 전체 공통 R | 각 환경에는 witness 있음, 공통 rule 수 0 |
| branch pairs | 합법 2쌍, 금지 cross pair (A,D)/(C,B) 2쌍 |
| coupled costs | legal assignments 2개, costs 104와 7; 단독 싼 rule이 global best 아님 |
| 하한 | feasible completion이 있는 partial assignment 7개에서 독립 최소 비용 합 ≤ 실제 최소 완료 비용 |
| binary support의 한계 | equality/equality/inequality cycle에서 active values 6개가 남지만 complete solutions 0 |

이는 **새 planner 구현, 실제 DML의 feasibility, Oracle, privacy, emission의 검증이 아니다.** 위 알고리즘의 양화사·하한·arc-consistency 한계를 확인한 분석 보조 결과다.

## 10. 실제 workload 검증 계획과 제거 전 acceptance criteria

### 10.1 비교를 분리한다

1. **Frozen v50 재현:** v50 source/JAR/설정/입력으로 동일 Docker fullInitial과 counters를 반복 측정한다. 현재 dirty tree 실행을 v50으로 부르지 않는다.
2. **현 작업 트리 기준선:** 기존 sparse 변경을 포함한 별도 frozen engine을 만든 뒤 같은 조건을 측정한다. v50과 이 baseline을 섞지 않는다.
3. **공통 계획 공간 비교:** 기존 engine의 semantic plans를 새 canonical/D1/static/schedule 정책으로 투영하고 새 모델과 비교한다. 지원 범위가 줄어서 빨라진 효과와 representation 효과를 분리한다.
4. **최적화 알고리즘 비교:** v50 DP-local과 새 exact B&B의 planning time만 비교해 representation 승리라고 하지 않는다. Tiny exact reference 및 동일 목표/gap의 비교를 별도 제공하고 성능 표에 완료 status·optimality gap·resource/validation-incomplete를 필수 열로 기록한다.
5. **선택/emission 실행:** 같은 비용 fixture의 전체 surface 검증과 실제 workload의 numeric/placement/privacy/materialization audit을 모두 수행한다. 선택 plan 하나가 같다는 사실로 전체 completeness를 대체하지 않는다.

모든 성능 실행은 저장소 지침대로 run_LAN_docker.sh와 동일 Docker 조건에서 한다. 소스/JAR, data/seed, W/order, CPU/network/memory, Xmx/Xms, BLAS, logging/profiling 설정을 고정한다. W1 LogReg/GLM 외 W2+ canonical micro-workload도 필요하다. W1만으로 ROW/COL mapping·replica·worker subset 문제를 검증할 수 없다.

Cold snapshot과 warm native cache를 분리하고 각 variant/workload를 최소 5 paired 반복해 raw 값·median·분산을 보관한다. 계측 on/off의 overhead도 분리한다. 역사적 20초 목표를 달성했다고 주장하려면 해당 기존 gate까지 통과해야 하며 이번 설계에 대한 관측치는 아직 없다.

### 10.2 Ablation: 무엇이 줄었는지 검증

| 비교 단계 | 고정할 것 | 달라질 것 / 측정 |
|---|---|---|
| shadow Memo | 기존 선택/emission | source multiplicity와 무관한 rule 수, 실제 native cache miss, 작은 Memo allocation |
| early gate | 동일 admitted semantic universe | skipped region, 실제 객체 allocation, earliest latency; illegal 삭제 집합 parity |
| incremental propagation | 같은 domains/constraints/선택 순서 | 전체 rescan 대비 pair/joint 평가·queue 방문·revision hit와 time |
| source/physical 비전개 | 같은 semantic plan/cost space | source tuples/SupportClause/Alternative가 새 경로에서 0인지; 새 joint query/search 반복량 |
| cost/search | 같은 checker·단가·policy | direct B&B node/bound/schedule 비용, 기존 reference intermediate와 memory |
| full integration | canonical 공동 지원 범위 | 숫자·privacy·emission audit, total planning, 전체 allocation·peak |

일부 ablation switch는 현재 없다. 위 표는 향후 구현·검증 순서이며 이번에 수행한 실험이 아니다. Profiler allocation/GC 자료가 없으면 “GC 때문에 시간이 늘었다”는 인과 설명을 하지 않는다.

### 10.3 삭제를 허용하는 순차 gate

| Gate | 반드시 통과할 조건 |
|---|---|
| G0 지원 계약 | canonical/D1/static/schedule/guard approximation별 포함·제외·미검증 명세. 기존 legal plan의 제외 이유를 재현 가능하게 기록 |
| G1 표현 completeness | Tiny reference에서 모든 supported old semantic plan의 새 assignment가 존재하고 cost 동일. Multi-output owner/slot 포함 |
| G2 pruning soundness | Feasibility stage/deletion마다 legal assignment 집합 동일. UNKNOWN·inactive·budget exhaustion·backtrack·loop provisional 반례 통과. Cost-node closure는 G4에서 별도 검증 |
| G3 fixed point/no rebuild | full-recompute reference와 domains/facts 동일. Stable epoch의 Oracle native universe 재생성/후보 rebuild 0. Shape/compile 변경은 명시 새 snapshot |
| G4 비용/최적성 | every tiny complete plan cost parity, every partial bound sound, cost-node closure의 incumbent/tie 조건, shared execution/transfer와 lifetime 일치. Exact 완료와 resource/validation-incomplete 구분 |
| G5 grounding/emission | 정확 source/version/slot/guard, entry/모든 backedge/zero iteration, actual/formal, canonical map, privacy, 실제 action/lifetime 검증. Runtime fallback 없음 |
| G6 실제 runtime | canonical 공통 범위 LogReg/GLM 및 W2+ fixture의 숫자/audit/선택 계약 통과. 기존 baseline FAIL을 새 PASS로 이름만 바꾸지 않음 |
| G7 성능 주장 | 반복 paired runs에서 full planning 및 §8.3의 heap/RSS/cgroup peak·누적 allocation을 구분해 보고. 줄어든 old 객체 외 새 predicate/solver/schedule 비용까지 포함 |

Anchor/Proof/SupportClause/Closure 경로는 적어도 G0–G6 통과 전 기본 경로에서 제거하지 않는다. G7은 “성능 문제가 해결됐다”는 주장에 필수다. 새 구조가 더 읽기 쉽더라도 planning time 또는 peak가 악화하면 그 결과를 그대로 보고하고 병목별로 후속 판단한다.

## 11. 최종 권고

**단순 Memo 설계는 유지하되 correctness와 성능 문제의 해결을 이미 달성했다고 보지 않는다.** 가장 큰 이점은 source/physical/support 조합을 만들지 않는 데 있다. Earliest safe pruning은 이 이점을 보완하지만, 합법 조합의 표현 비용과 shared/joint exact search 비용을 대신 해결하지 않는다.

최소 보완은 네 가지다: (1) 활성 환경과 공통 선택의 양화사를 명시한 pruning, (2) multi-output owner에 종속된 carrier projection, (3) closed facts 이후의 양방향 dependency worklist와 revision-safe cache, (4) representation·legal-set·cost·grounding·emission을 분리한 acceptance gate. 이 정도의 관계는 기존 HOP DAG와 Program Facts에 직접 연결할 수 있다. 새 범용 Constraint Graph나 새로운 Proof 객체 계층을 추가할 필요는 없다.

## 12. 이번 작업의 증거와 한계

- 기준 HEAD와 기존 미커밋 변경을 확인하고 보존했다.
- v50 raw artifact는 기존 실행 기록이다. 새 planner 또는 현재 dirty tree의 runtime 측정이 아니다.
- 분석용 유한 모델 열거는 §9.3의 수학적 반례만 확인했다. 저장소 production/test 수정·테스트 실행·benchmark·commit/push는 하지 않았다.
- 두 독립 정적 검토에서 양화사/loop/cache 및 v50 수치/acceptance 기준을 확인하고, 미해결 leaf의 종료 상태와 feasibility/cost pruning 분리를 보완했다. 재검토에서 해당 구간의 추가 blocker를 찾지 못했으며 구현 증명을 대신하지 않는다.
- 추가 문서의 참조 51개와 기존 보고서의 참조 91개의 경로·행, Markdown 구조를 검사했다. src tracked 8,032개 hash와 HEAD가 동일하고 신규 untracked source가 없음을 확인했다. 최종 재확인 결과는 세션 기록에 남긴다.


[access-kind]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L6231
[bounded-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelectionPruningOracleTest.java#L31
[cfg-constant]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java#L301
[closure-midpoint]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1038
[closure-policy]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L2984
[cost-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CostBasedPruningTest.java#L35
[dyadic]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactDyadicCosts.java#L189
[frequency-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFactsConstantBranchTest.java#L22
[generator-build]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L245
[generator-mrv]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L1123
[generator-prefix]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L1350
[grounding-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/StructuralGroundingPlanSpaceTest.java#L52
[joint-analysis]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L49
[joint-execute]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L872
[joint-factor]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L1942
[joint-loop]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L896
[loop-discharge]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L4527
[loop-seed]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L8896
[loop-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/LoopIdentityBackedgeClosureTest.java#L60
[metrics]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/SearchSpaceMetrics.java#L817
[multi-cp]: ../src/main/java/org/apache/sysds/runtime/instructions/cp/MultiReturnBuiltinCPInstruction.java#L71
[multi-facts]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java#L555
[multi-oracle]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java#L5264
[multi-owner]: ../src/main/java/org/apache/sysds/hops/FunctionOp.java#L553
[multi-process]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/MultiReturnParameterizedBuiltinFEDInstruction.java#L224
[multi-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/MultiReturnParameterizedBuiltinFEDInstruction.java#L83
[multi-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuityTransformEncodeTest.java#L21
[oracle-partial]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L309
[physical-alternatives]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L1050
[physical-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactConditionalNativeProductPhysicalParityTest.java#L136
[privacy-mask]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidatePrivacyInputPruning.java#L94
[privacy-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/EarlyPrivacyPruningLegalSpaceParityTest.java#L90
[recursion]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFacts.java#L1020
[reduction-order]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java#L554
[reduction-support]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java#L1006
[seed-privacy]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L2559
[shared-cost]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java#L1590
[solver-eliminate]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L3165
[solver-freeze]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L1860
[support-fixedpoint]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementSupportRelations.java#L349
[timing]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateFormationTiming.java#L142
[transient-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactTransientRealizationTest.java#L29
[v50-comparison]: ../../evidence/cofee-50k128-v50-validation/v43-v49-v50-comparison.json#L1
[v50-glm-counters]: ../../evidence/cofee-50k128-v50-validation/v43-v49-v50-comparison.json#L1274
[v50-launch]: ../../evidence/cofee-50k128-v50-validation/prepared-launch-plan.json#L83
[v50-lr-counters]: ../../evidence/cofee-50k128-v50-validation/v43-v49-v50-comparison.json#L2922
[v50-map]: FEDPLANNER_BOTTLENECK_MAP.md#L28
[v50-map-phases]: FEDPLANNER_BOTTLENECK_MAP.md#L49
[v50-map-reuse]: FEDPLANNER_BOTTLENECK_MAP.md#L112
[projector]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalPlacementProjector.java#L36
[emission]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java#L337
