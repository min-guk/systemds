# FedPlanner 단순 Memo 재설계: 현재 코드 비교와 변경 계획

**정책 이력 안내:** 이 문서는 앞선 분석 기록이다. 최신 사용자 확정 정책은 [통합 구현 명세 ADR09/20 및 §6.6](FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md)을 따른다. Loop Backedge는 native DIRECT-only이며 이 문서의 일반 backedge transfer/normalization 후보·비용 설명은 새 설계에 적용하지 않는다. Entry/최종 Exit·일반 body edge의 합법 movement 및 If-Else guarded join transfer는 유지한다. 현재 코드의 기존 동작을 기록한 내용과 새 정책을 구분한다.

작성일: 2026-10-13  
갱신: 후속 요청의 시간·공간 복잡도, 재계산 제거, 측정 및 단일 권장 아키텍처 분석 반영  
요청 저장소: /home/mchoi/w1357-structural-grounding-20261006  
기준 HEAD: c961a291811d23db104cc63a2b161e7e4b480e57  
범위: 현재 working tree의 소스·테스트·문서를 읽은 분석과 설계. Production/test 코드 수정, 빌드, 테스트 실행, 성능 실험은 수행하지 않았다.

후속 [Earliest Safe Pruning 검증](FEDPLANNER_EARLIEST_SAFE_PRUNING_2026-10-13_KO.md)은 이 문서의 completeness 범위를 더 엄격히 한정한다. Canonical 전체가 아니라 명시한 baseline 범위의 표현 보존도 아직 검증 의무다. Multi-output 실행 owner/carrier coupling, 활성 context별 rule 조건, UNKNOWN과 closed-facts 경계를 아래 보완과 함께 적용한다.

최신 [Oracle·Supply 통합 검토](FEDPLANNER_ORACLE_SUPPLY_DESIGN_2026-10-13_KO.md)는 Oracle 집합 API가 보존해야 하는 CP/forced-local 대안, metadata 입력과 temporary broadcast, producer intrinsic offer와 edge 조건의 책임을 구체화한다. 이 문서의 B&B 권고는 연구용 exact prototype/reference 선택이며, LogReg/GLM production optimizer로서 기존 Local/Exact보다 우수하다는 결론은 아니다. Production 교체는 동일한 지원 범위·정확도 목표에서 검증한 뒤 결정한다.

## 1. 결론과 판단의 범위

**별도의 범용 Constraint Graph 없이 구현할 수 있다.** HOP DAG, 작은 occurrence Memo, 기존 Program Facts, 선택된 계획의 검증 함수로 필요한 의미를 표현할 수 있다. 다만 **HOP별 독립 최솟값 선택이나 pairwise partition 검사만으로는 부족하다.** 공유 producer의 단일 선택, 실제로 함께 도달하는 값, loop의 entry와 귀납적 backedge 조건, 함수의 공유 compiled body, materialization의 활성화와 lifetime을 공동으로 검사해야 한다.

고정 worker pool W와 canonical partitioning은 Anchor의 **배치 대안 탐색**을 제거할 좋은 전제다. 이것이 값의 identity, 존재 시점, privacy, 실제 runtime layout의 검증까지 없애지는 않는다. 특히 현재 runtime에는 이 전제를 만족하지 않는 native 연산이 있으므로, 새 구조는 현 구현의 단순한 자료형 치환이 아니다.

표기:

- **[확인]** 현재 소스 또는 테스트 assertion에서 직접 확인했다. 테스트의 실행 성공을 뜻하지 않는다.
- **[추론]** 코드에서 도출한 판단이다.
- **[제안]** 앞으로 구현할 설계다. 실행 동등성이나 성능은 아직 검증하지 않았다.
- **[미검증]** 현재 분석으로 결론을 내릴 수 없는 사항이다.

| 우선순위 | 판단 | 확신 / 근거 |
|---|---|---|
| 1 | Memo + HOP DAG + Program Facts로 구조를 단순화할 수 있다. | 구조적 가능성 높음. 현 Physical Model도 이미 occurrence별 단일 변수와 공동 hard/cost factor를 사용한다. 전체 대체 구현은 미검증. [모델 생성][model-build] |
| 2 | Oracle보다 Oracle 이후의 source/anchor/realization/support/emission 결합이 주요 단순화 대상이다. | 높음. Oracle은 이미 HOP·추상 FType 입력·shape를 받는다. [Oracle API][oracle-api], [생성기][generator] |
| 3 | 고정 W/canonical 계약은 현재 runtime이 자동 보장하지 않는다. | 높음. slicing의 worker 축소, append의 map 결합, rmempty/roll의 range 변경이 구체적 반례다. 10절. |
| 4 | 현재 Closure 전체를 유지할 필요는 없지만 의미가 다른 fixed point를 구분해 옮겨야 한다. | 높음. loop placement의 provisional/greatest fixed point와 grounded single-partition의 least fixed point가 별개다. 7.3절. |
| 5 | Exact solver와 비용 계산의 수학적 핵심은 재사용 가능하다. Physical Alternative 구축부는 크게 바뀐다. | 높음. Local의 기본 5% gap 종료는 정확한 전역 최솟값 보장과 다르다. [Local][local-opt], [종료 설정][regional-options] |

권장 초기 설계는 **고정된 compiled rule 선택 + 경계별 고정 partition 계약 + 명시적인 movement 선택**이다. Branch별 runtime rule dispatch, iteration별 placement 전환, 호출별 function cloning을 동시에 도입하지 않는다. 이는 새 추상 계층을 줄이기 위한 지원 범위 제안이며, 현 runtime의 모든 가능한 계획을 보존한다는 주장은 아니다.

**최종적으로 권고하는 한 가지 구조는 immutable occurrence Memo + 기존 Program Facts의 symbolic binding + dependency worklist + lazy predicate 기반 exact branch-and-bound다.** 작은 domain을 직접 탐색해 source/physical product와 거대 factor table을 생성하지 않는다. 기존 Exact solver는 초기 semantic/cost reference로 재사용하고, 다른 solver를 동시에 새 기본 경로로 도입하지 않는다. 실제 탐색 속도 우월성은 미검증이다. 이 선택은 연구자가 설명할 수 있는 구현과 planning memory를 우선하며, 최악의 지수 탐색은 숨기지 않는다. 상세 비교와 복잡도는 11.1–11.10절에 있다.

기본 저장 공간 O(NK+EM)은 bounded arity·상수 크기 movement recipe 등의 조건에서 목표로 삼을 수 있다. 전체 공간에는 control-flow/guard/binding facts, 패턴 길이, cache, 탐색 상태가 추가된다. 임의의 joint relation이나 exact optimization까지 이 상한으로 처리한다고 주장하지 않는다.

## 2. 분석 기준과 미커밋 변경

### 2.1 저장소 상태

**[확인]** HEAD는 요청한 커밋과 같고 branch는 refactor/structural-grounding-20261006이다. 요청 경로는 물리적으로 /grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/workspace-preserved를 가리킨다. 아래 참조는 요청 경로의 저장소를 기준으로 한다.

최초 문서 작성 시작 시 tracked 수정은 8개 파일, +514/-54행이었다. **후속 요청 재확인 시에는 8개 파일, +528/-54행**이며 이전 분석의 세션 기록 14행이 추가된 차이다. Production/test 미커밋 수정은 두 시점 모두 아래 6개 파일 +463/-54로 같고, 모두 HEAD 이후의 기존 작업이다. Oracle/candidate generator/solver의 후속 분석 대상에는 이 미커밋 diff가 없다.

| 기존 수정 파일 | 기존 diff | 이번 분석에서의 취급 |
|---|---:|---|
| placement/NativeContinuitySupportClauses.java | +128/-37 | sparse ordinal별 기존 clause authority 보존의 의미를 분석 |
| placement/PlacementAnalysis.java | +4/-2 | explicit/native authority 병합 조건 분석 |
| placement/PlacementRelationClosure.java | +9/-4 | mixed publication 및 retention budget 분석 |
| fedExact/ExactConditionalNativeProductPhysicalParityTest.java | +147/-2 | 모든 factor cell·비용·선택 의미 비교 assertion 분석 |
| placement/NativeContinuitySupportClausesTest.java | +131/-9 | sparse hole, withdrawal/re-add, budget 반례 분석 |
| placement/NativeMixedAxisSplitPublicationTest.java | +44 | explicit clause의 동일 identity 보존 assertion 분석 |
| docs/FEDPLANNER_ALGORITHM_AND_BOTTLENECKS_2026-10-13_KO.md | +14 | 기존 문서, 덮어쓰지 않음 |
| docs/SESSION_ISSUES_2026-10-13.md | 최초 +37, 후속 시작 +51 | 기존 기록 보존 후 이번 분석 항목만 추가 |

Production/test 표의 상대 경로는 src/main/java/org/apache/sysds/hops/fedplanner 또는 src/test/java/org/apache/sysds/hops/fedplanner 아래다. 최초 시작 시 untracked 파일은 94개, 이 문서가 추가된 후속 시작 시에는 95개이며 기존 설계 문서와 experiments 산출물을 포함한다. 해당 파일의 존재는 이번 분석에서 그 실험을 실행하거나 결과를 검증했다는 뜻이 아니다.

HEAD 대비 source/test patch SHA-256은 7c4657652de1de5f59d769fd98347d67cd61a8afccc90640dd2b1ace760041b1로 재확인했다. HEAD는 explicit member별 donor를 보관하던 반면 현재 작업 트리는 sparse ordinal 배열+원래 clause 참조를 한 donor로 묶는다. 예를 들어 64 explicit member+fallback이 donor 2개로 유지되는 assertion이 있다. 이는 bounded fixture의 표현 차이이며 end-to-end 시간·peak memory 개선의 증거는 아니다. [현재 sparse publication 테스트][sparse-publication]

**[확인]** 미커밋 변경은 객체 계층 삭제가 아니라 sparse authority의 표현 최적화다. 동일 output이라도 허용된 정확한 ordinal과 source binding만 재사용하고 sparse hole을 다음 donor로 넘긴다. 철회 후 재추가된 member에 예전 authority를 부활시키지 않는 테스트도 있다. 이 변경을 “이미 source Cartesian product가 없어졌다”는 증거로 사용하지 않는다. [sparse 구현][sparse], [철회 테스트][sparse-test], [physical parity][parity-test]

### 2.2 기존 문서와 새 요청의 차이

기존 [비교 문서](FEDPLANNER_MEMO_ARCHITECTURE_COMPARISON.md)와 [재검토 문서](FEDPLANNER_MEMO_ARCHITECTURE_REVIEW_2026-10-13_KO.md)는 보존한다. 특히 비교 문서의 이전 fixed-worker 부록에 있는 “최대 세 상태”, single-worker FULL, BROADCAST 제외, RESHARD 비용은 이번 계약의 결론으로 재사용하면 안 된다.

이번 문서는 **LOCAL/ROW/COL/REP, 모든 W 사용, canonical mapping, RESHARD 금지, 별도 범용 Constraint Graph 미도입**을 기준으로 다시 판단한다. 기존 저장소 지침의 anchor 기반 upload는 현재 계약을 설명한다. 사용자의 최신 요청에 따른 anchor 제거는 향후 변경 제안으로 다루며 현재 runtime을 이미 변경한 것으로 쓰지 않는다.

## 3. 단계별 현재 구조와 제안 구조

**[확인]** 현재 final-HOP 경로는 DMLTranslator.constructLops → runFederatedPlannerAtFinalHopBoundary → prepareCommonSearchSpace다. 마지막 함수는 준비 helper다. 함수 specialization과 physical/branch normalization, 메모리 추정 후 PlacementAnalysis를 생성하고 선택/emission으로 넘어간다. [호출 진입점][planner-entry], [준비 helper][entry]

| 단계 | 현재 코드와 실제 역할 | 제안 구조 | 유지할 의미 |
|---|---|---|---|
| 프로그램 해석 | PlacementProgramFacts.analyze, PlacementJointInputAnalysis | 기존 facts에 guarded definition·loop·call 관계를 직접 노출 | occurrence, 정확한 값, input position, control flow |
| 후보 준비 | NeutralPlacementGraphBuilder.buildAnalysis와 PlacementRelationClosure | facts/shape/privacy 분석 후 occurrence별 Memo 작성 | source metadata, shape/type, privacy transfer |
| native capability | OracleFacade.PreparedDecision, Rulesets | 추상 input pattern → native rule 목록 | 실제 runtime의 opcode/shape/output 조건 |
| 후보 생성 | PlacementCandidateGenerator.buildNode가 anchor, input owner, emission, privacy 등을 결합 | source 조합 없이 native rule + 조건부 output options | native 실행과 이동을 구분 |
| privacy | 생성기 pruning, Closure privacy, 이후 movement 검사 | 독립 privacy pass가 불가능 rule/option 제거, 조건부 합법성은 남김 | 보호 입력의 이동과 결과 공개를 따로 검사 |
| 연결/grounding | graph constraints, realization binding, support/proof, Closure 재생성 | HOP edge predicate + facts의 branch/loop/call 관계 + selected-plan availability | 공동 선택, 시간 순서, rooted value |
| 비용 | Physical Alternative → ExactPhysicalCostModel | 선택된 native rule·movement·activation에 직접 비용 부여 | 내부 통신, sharing, 실행 횟수, 실제 lifetime |
| 최적화 | Local/Exact가 공통 physical domain/factor를 소비 | 작은 Memo domain을 직접 탐색; 필요할 때만 작은 factor | 전체 합법성과 하나의 공동 목적함수 |
| emission | projector → normalized result → transactional emission/registries | 선택 rule·edge supply만 lowering하고 독립 재검증 | solver 선택과 runtime action의 일치 |

**이미 일치하는 부분:** 추상 FType Oracle, occurrence/value-version identity, path-correlated input 분석, 하나의 physical decision variable, shared materialization 비용, source availability 검증, transactional emission이다. 이 의미는 재사용한다. **아직 일치하지 않는 부분:** 고정 W/canonical runtime 보장, 네 partition 상태, source-free candidate 생성, Anchor/Proof 없는 lowering, 작은 Memo를 직접 쓰는 optimizer다.

## 4. 먼저 확정해야 할 실행 계약

### 4.1 Partition과 canonical layout

**[제안]** W는 endpoint 순서까지 고정된 불변 descriptor 하나다. shape s와 partition p에 대해 layout(W,s,p)는 하나다. snapshot/compile epoch 안에서 W가 바뀌면 안 된다. 같은 W라는 사실만으로 입력값이 존재한다고 판단하지 않는다.

| 상태 | 의미 | 필요한 검사 |
|---|---|---|
| LOCAL | coordinator에서 해당 논리 값을 읽을 수 있음 | CP payload 접근의 privacy 및 실제 materialization |
| ROW | 모든 W에 canonical row ranges로 분할된 값 | 전체 범위, 중복/빈 partition, worker 순서 |
| COL | 모든 W에 canonical column ranges로 분할된 값 | ROW와 동일한 종류의 검증 |
| REP | 모든 W에 **동일한 완전한 논리 값**이 존재함 | 부분합/중간 결과를 replica로 오인하지 않음 |

W가 하나이면 FED 상태는 REP로 정규화한다. CP/FED execution은 여전히 별도이므로 LOCAL과 REP를 합치지 않는다. Scalar 결과는 보통 LOCAL이며, FED aggregate가 모든 W에서 계산하고 LOCAL scalar를 반환하는 것은 계약 위반이 아니다.

한 worker라는 이유만으로 현재 ROW/COL metadata를 FULL로 바꿔도 모든 opcode가 동일하게 동작한다고 가정하지 않는다. Normalization adapter는 값 전체의 범위·물리 객체를 확인하고, nativeVariant별 기존 runtime dispatch와 수치 결과가 같음을 검증해야 한다. 원래 FType spelling이 필요하면 lowering detail로만 보관하고 planner의 다섯 번째 partition 상태로 만들지 않는다.

**[확인]** 현재 FULL은 replication NONE, BROADCAST는 replication FULL이다. W>1에서 한 worker에만 있는 FULL을 REP로 바꿀 수 없다. 제안 REP의 runtime lowering은 W=1이면 검증된 FULL, W>1이면 BROADCAST가 기본이다. 이는 lowering adapter의 책임이며 planner state에 LOUT/FOUT을 재도입할 이유는 없다. [FTypes][ftypes], [replicated map 검사][materialize-rep]

Replica의 동일 값 조건은 신뢰할 수 있는 source/runtime의 replication 계약과 생성 provenance로 보장해야 한다. Planner가 private payload를 수집해 byte equality를 검사한다는 뜻이 아니다. 외부 source metadata만으로 이 계약을 확인할 수 없다면 REP를 추측하지 않는다.

Canonical 함수는 범용 추상 계층 대신 단일 helper로 둔다. 균등 분할을 택한다면 현재 allocator의 quotient/remainder 방식을 기준으로 명문화할 수 있다. 하지만 native output이 그 함수와 일치하는지는 operator별로 검사해야 한다. 서로 다른 shape의 ROW 입력끼리도 operator가 요구하는 정렬 관계는 별도다.

### 4.2 Movement와 아직 열린 우회 결정

**[제안]** 아래는 source native output에 대한 공급 가능성이지 eager materialization 목록이 아니다.

여러 availableOutputs가 개별적으로 가능하다는 사실만으로 모두 동시에 존재한다고 결론내리지 않는다. 여러 consumer가 함께 요구하면 selected supply schedule이 원본을 필요한 마지막 use까지 보존하고, 각 복사본의 생성·사용·해제를 실제로 구현할 수 있어야 한다. Destructive collect/cleanup이나 lifetime 충돌이 있으면 해당 공동 선택을 거절한다.

| native → required | 기본 옵션 | 추가 조건 |
|---|---|---|
| LOCAL → LOCAL | DIRECT | 값이 그 시점에 존재 |
| ROW → ROW, COL → COL, REP → REP | DIRECT | 같은 정확한 값, canonical layout |
| LOCAL → ROW/COL/REP | UPLOAD | shape, privacy, canonical allocator, worker 접근이 지원됨 |
| ROW/COL/REP → LOCAL | COLLECT | release/privacy, 실제 collect semantics와 비용 |
| 서로 다른 FED partition | 기본적으로 없음 | 직접 RESHARD, REP local slicing shortcut도 가정하지 않음 |

**미확정 결정 D1: COLLECT+UPLOAD 우회를 허용할 것인가?**

| 선택 | 결과 | 장단점 / 필요 구현 |
|---|---|---|
| D1=금지 — 초기 구현 권장 | ROW→LOCAL→COL 등의 동일 값 우회를 공급 옵션에서 제외 | 이해하기 쉽고 중간 단계가 적음. 일부 기존 refed 계획은 지원 범위에서 제외 |
| D1=허용 | 길이 2 recipe로 COLLECT→UPLOAD를 명시 | 공개 가능한 값의 계획 범위 증가. 중간 LOCAL activation·sharing·peak memory·두 이동 비용을 모델링 |

이는 아직 사용자가 확정한 정책이 아니다. 구현 시 별도 결정 기록으로 확정해야 한다. 권고를 확정된 요구사항으로 해석하지 않는다. 같은 결정은 COL→LOCAL→ROW, ROW/COL→LOCAL→REP, REP→LOCAL→ROW/COL에도 일관되게 적용해야 한다.

우회 금지라면 alias/TRead/branch join에서 LOCAL이 되었다는 이유로 값의 native origin을 잊고 다시 upload하여 금지를 우회하면 안 된다. **투명 alias를 따라 공급 경로를 합성해 D1을 검사한다.** 반면 실제 CP 연산이 새로운 값을 만든 뒤 upload하는 정상 operator chain은 동일 값의 재배치와 구분한다. 금지를 피하려고 optimizer가 임의 identity 연산을 삽입하지 않는다.

W>1의 COL→ROW transpose는 **native operator 변환**일 수 있으므로 movement RESHARD 금지와 다르다. native semantics 및 transpose 후 canonical mapping이 확인되는 경우만 허용한다.

### 4.3 임시 operand와 지속 output

예를 들어 FED MM이 LOCAL 오른쪽 피연산자를 내부적으로 broadcast하고 cleanup할 수 있다. 이 rule의 required input은 LOCAL이며 그 broadcast는 **native 실행 recipe의 통신**이다. producer에 지속 REP가 생겼다고 기록하지 않는다.

지속 REP는 명시적 UPLOAD나 실제 persistent native output으로 만들어져 다른 consumer에서도 살아 있어야 한다. 임시 broadcast를 재사용하려면 runtime의 retention/emission 지원을 별도로 증명해야 한다. 현재의 cleanup 경로만 보고 공유 가능한 materialization으로 승격할 수 없다. [MM runtime][mm-runtime], [append runtime][append-runtime]

## 5. 최소 자료구조와 Oracle/Privacy API

### 5.1 Memo key와 input pattern

**[제안]** 설명상 Memo[HopId]를 유지할 수 있지만 실제 저장은 현재 CompiledHopKey를 재사용하는 편이 안전하다. 이 키는 function/callsite/recompile/control-region/emitted-instance를 구분한다. ValueVersionKey는 값의 정의를 나타낸다. 두 identity를 하나의 변수명이나 HOP 번호로 합치지 않는다. [키][identity], [값 버전][value-id]

HOP input edge는 정확한 input position을 갖는다. Branch/loop/formal read의 inputHopId는 경계 read를 가리키고, 실제 writer는 Program Facts에서 guard/activation에 따라 해석한다. 모든 writer를 Memo rule에 복제하지 않는다.

아래 Java는 구현 지시를 위한 최소 형태이며 컴파일한 코드가 아니다. 기존 type은 재사용하고 선택되지 않은 source binding/proof 객체를 추가하지 않는다.

~~~java
enum Partition { LOCAL, ROW, COL, REP }
enum Movement { DIRECT, UPLOAD, COLLECT }

record InputRequirement(int position, Partition requiredPartition) {}
record OutputOption(
    Partition output, List<Movement> steps, int conditionId) {}

record FeasibleRule(
    int ruleId,                                // 안정적인 semantic ID, 목록 ordinal 아님
    int nativeVariant,                         // opcode/검증된 lowering mode
    List<InputRequirement> inputPattern,
    ExecType execType,                         // CP 또는 FED만
    Partition nativeOutput,
    List<OutputOption> availableOutputs,
    int executionPrivacyConditionId) {}

record MemoValue(
    PlacementPrivacyFacts privacyConstraints,
    List<FeasibleRule> feasibleRules) {}

record InputEdgeId(CompiledHopKey consumer, int inputPosition) {}

record SupplyChoice(
    OutputOption recipe,
    int creationSiteId, int cacheOwnerId, int retentionPolicyId) {}

record SupplySelection(
    Map<Integer, SupplyChoice> optionByBinding) {} // facts-owned binding ID → selected supply

Map<CompiledHopKey, MemoValue> memo;            // snapshot 내부 occurrence
Map<CompiledHopKey, Integer> selectedRule;      // compiled choice owner → semantic ruleId
Map<InputEdgeId, SupplySelection> selectedSupply;
~~~

conditionId는 기존 privacy/facts의 조건을 참조하는 작은 ID다. 새 범용 predicate 언어를 만들자는 뜻이 아니다. 항상 참이면 공통 sentinel을 사용한다. nativeVariant도 규칙이 단 하나의 lowering을 결정하면 생략할 수 있다. 동일 CP/FED+pattern에서 내부 통신/출력이 다른 runtime mode를 구분해야 할 때만 유지한다. ruleId는 operator variant, ordered input pattern, exec, native output의 semantic key를 intern한 ID다. 서로 다른 Memo 목록의 같은 ordinal을 같은 rule로 취급하지 않는다.

각 binding ID는 Program Facts의 **(guard/entry/backedge/call context, 정확한 source occurrence, ValueVersionKey와 return slot, use site)** 한 행을 참조한다. 일반 HOP edge는 무조건 binding 하나이고, 경계는 facts-owned conditional binding을 갖는다. Source 자체와 guard는 선택 변수가 아니다. Optimizer는 각 binding에 필요한 OutputOption만 선택한다. Complete plan에서는 모든 reachable environment에 정확히 하나의 binding case가 resolve되어야 하며, 선택 option이 실제 producer R의 availableOutputs에 속하고 privacy/availability를 만족해야 한다.

Search 변수는 S[edge,binding]으로 두고 완료 후 SupplySelection의 작은 map으로 묶는다. 서로 다른 guard의 recipe 조합을 미리 큰 domain으로 만들지 않는다. 예를 들어 then은 A ROW의 DIRECT, else는 C LOCAL의 UPLOAD ROW를 같은 join edge에 담을 수 있다. InputRequirement.position은 facts/HOP의 inputHopId를 정확히 조회하므로 요청의 (inputHopId,partition)을 중복 저장하지 않은 표현이다.

SupplyChoice의 schedule ID들은 §6.2의 schedule 완전성 계약을 위한 **선택 결과**다. 고정 정책에서 유일하면 생략하거나 공통 sentinel을 쓴다. 실제로 대안이 있으면 작은 selector를 lazy하게 탐색하며 recipe×scope×consumer 조합의 SupplyChoice 객체를 미리 만들지 않는다. 중간 COLLECT의 공유 여부도 선택된 primitive action의 실제 creation/cache key로 결정한다.

Program Facts의 compiledChoiceOwner(occurrence)는 **같은 emitted body HOP·같은 compile epoch·같은 specialization**을 공유하는 canonical CompiledHopKey를 반환한다. R[o]는 selectedRule[compiledChoiceOwner(o)]의 shorthand다. Callsite-specific Memo의 조건은 각각 검사하되 그 owner가 사용할 수 있는 semantic ruleId의 교집합에서 선택한다. 이 검사는 Reachable(context,environment) ⇒ RuleLegal의 형태다. Proven-inactive context는 교집합에서 제외하며, unknown guard나 추정 빈도 0은 제외 근거가 아니다. 새 recompile epoch나 실제 clone은 다른 owner다.

**후속 보완: multi-output owner.** FED transformencode는 하나의 실행에서 federated matrix와 LOCAL metadata frame을 만든다. 기존 Program Facts의 owner/output-position 관계를 재사용하여 native owner에 R 하나와 output-layout 배열을 두고, 각 output carrier Memo는 그 projection으로 둔다. Carrier별 독립 실행 선택·compute 과금은 하지 않는다. Ordinary rule의 단일 nativeOutput은 유지하되 multi-output을 그대로 단일 출력 연산으로 취급하면 표현이 불완전하다. 구체 소스와 canonical 지원 조건은 [후속 검증 §2.2](FEDPLANNER_EARLIEST_SAFE_PRUNING_2026-10-13_KO.md#22-실제-누락-하나의-native-실행에-여러-output-carrier)를 참고한다.

~~~java
record MaterializationKey(
    ValueVersionKey origin,
    int activationScope,          // invocation + loop value epoch + compile snapshot
    CompiledHopKey creationSite,  // 실제 생성 위치; dominance 검사
    int cacheOwnerId,             // runtime 보존/해제의 소유 scope
    Partition target,
    Movement step) {}             // 한 primitive action만 나타냄

// Guard와 source binding은 ProgramFacts가 소유한다.
// CompiledHopKey만으로 반복 실행마다 생성되는 서로 다른 MatrixObject를 합치지 않는다.
~~~

OutputOption.steps는 DIRECT/UPLOAD/COLLECT의 singleton 또는 D1 허용 시 [COLLECT,UPLOAD]다. 마지막 경우 중간 LOCAL collect와 target upload를 **두 MaterializationKey/action**으로 분리하고 upload가 그 LOCAL action에 의존한다. 다른 target으로 가는 recipe들이 같은 중간 collect를 공유할 수 있다. Composite enum 하나를 하나의 통합 비용으로 계산하지 않는다.

Physical materialization key와 demand event는 다르다. 각 demand는 기존 activation facts의 **guard literals, invocation/iteration context, consumer site, 예상 횟수**를 참조한다. Retention end는 selected emission schedule의 마지막 legal use로 결정한다. 서로 다른 branch에 생성된 두 복사본을 guard 확률만 보고 같은 cache object로 합치지 않는다. 실제 공통 creation site를 선택하고 dominance·availability·privacy·retention을 충족했을 때만 물리적으로 공유한다.

선택 시 실제 materialization 횟수와 emission scope를 결정한다. 단순한 Map<HopId,Part> 전역 cache는 이 key를 대체하지 못한다. 역으로 구현에 MaterializationKey record가 꼭 필요한 것은 아니며 기존 creation-scope/registry/activation 표현을 줄여 재사용할 수 있다.

### 5.2 Program Facts에 남길 최소 정보

| 정보 | 최소 표현 | 현재 재사용 지점 |
|---|---|---|
| occurrence/compiled mutation owner | occurrence → Hop, compile epoch, compiledChoiceOwner | CompiledHopKey, HopOccurrenceProjection |
| 입력과 값 | consumer+position → HOP input; 경계의 definition/version binding | compiled input edges, ValueVersionKey |
| alias와 새 값 | aliasParent(value,guard) 또는 실제 DERIVED operator 경계 | transient/function/placement alias와 value-origin facts |
| branch | 같은 guard ID를 공유하는 guarded definitions/structured region | CfgAnalysis + PlacementJointInputAnalysis |
| loop | entry definitions, carried reads, backedge definitions, exit/zero-iteration, nested scope | CFG version kind와 loop facts |
| function | callsite, actual/formal position, return position, shared compiled-body owner | logical function facts, call contexts |
| source availability | source metadata, 정의 위치, dominating scope, canonical layout 검증 결과 | source facts/region identity |
| shape/privacy | 정확한 값별 facts와 conditional transfer, metadata-only operand 표시 | shape/privacy facts, coordinator input access |
| 비용 횟수 | branch guard와 확률, loop 횟수 추정, call invocation context | OccurrenceExecutionFrequencyFacts |
| materialization 수명 | creation site, cache owner, guard별 demand, 마지막 use | 기존 creation-scope/activation/registry 의미 재사용 |

isCompatible은 aliasParent를 guard별로 따라 실제 value origin까지 공급 경로를 normalize한다. D1=금지이면 FED p→COLLECT→투명 aliases→UPLOAD q인 동일 값의 FED 간 이동을 거절한다. 실제 계산 DERIVED 경계에서만 새 value origin을 시작한다. 이 query가 없으면 no-detour 정책은 LOCAL transient를 통해 우회될 수 있다.

**[확인]** CfgAnalysis의 writer set만으로는 branch correlation이 부족하지만 현재 전체 구현이 correlation을 잃는 것은 아니다. PlacementJointInputAnalysis가 path-correlated environments와 JointTuple을 만들고 JointValueMapRelations 및 ExactPhysicalModel.addJointFactors가 공동 합법성을 검사한다. 이 정보를 Program Facts의 작은 인터페이스로 **옮기거나 노출**하면 된다. [CFG][cfg], [joint 분석][joint], [joint factor][joint-factor]

### 5.3 Oracle API 변경

**[확인]** OracleFacade.decide/PreparedDecision은 이미 HOP, List<FType>, ShapeHint를 받는다. OpCaps의 exec/placement/foutFType 표현과 PreparedDecision.prepareExecutionRelation, Rulesets의 capability/shape 조건을 재사용할 수 있다. Source/anchor 탐색을 Oracle 내부의 본질로 오해할 필요가 없다. [API][oracle-api], [OpCaps][opcaps], [relation API][oracle-relation]

**[제안]**

~~~java
List<NativeRule> nativeRules(
    Hop template, NodeShape outputShape, List<NodeShape> inputShapes,
    List<Partition> inputPattern, FixedWorkerPool fixedW,
    Consumer<NativeRejection> rejectedOutcomes);
~~~

NativeRule은 exec, nativeOutput, 필요한 nativeVariant와 capability reason을 가진 작은 반환값이며 위 FeasibleRule 생성에 바로 사용한다. FixedWorkerPool은 전역 descriptor 하나로 충분하다. Runtime effect 판정은 source lineage를 읽지 않고 canonical input geometry와 operator parameter로 수행한다.

실제 native semantics와 새 planner scope를 구분하기 위해 rejectedOutcomes에 **(operator/variant, abstract pattern, 실제 runtime output effect, 제외 이유)**를 기록한다. Runtime effect가 subset/noncanonical/data-dependent이면 먼저 그 사실을 판정하고, canonical policy adapter가 PLANNER_CANONICAL_SCOPE 제외를 기록한다. Runtime opcode/shape 자체가 불가능한 RUNTIME_UNSUPPORTED와 다른 이유다. NativeRejection은 audit용 작은 반환 기록이며 Memo state나 탐색 변수에 새 partition을 추가하지 않는다. 같은 구분을 반환 결과의 admitted/rejected 목록으로 구현해도 된다.

변경 순서:

1. 기존 opcode signature/shape/capability 구현을 감싼 adapter를 만든다.
2. operator가 관찰하는 matrix/frame input partition pattern만 열거한다. Scalar/metadata-only input은 해당 역할에 따라 LOCAL로 두고 payload COLLECT와 혼동하지 않는다.
3. CP는 payload 입력을 LOCAL로 공급받는 rule로 표현한다. FED native는 검증된 pattern과 output만 반환한다.
4. Oracle가 보고한 실제 output effect가 canonical contract를 만족하지 않으면 policy adapter가 그 **native 경로**를 PLANNER_CANONICAL_SCOPE로 제외하고 audit에 남긴다. 임의의 수선 이동을 Oracle 안에 숨기지 않는다.
5. CandidateGenerator.buildNode에서 anchor/input-owner/source-realization/derived-output 생성 인자를 제거하고 Memo builder로 축소한다.
6. 가능한 출력 movement는 Oracle 이후에 계산한다. Existing OpCaps가 CP/FED 중 한 preferred 결과만 표현하는 경로는 합법적인 두 경쟁 rule을 모두 반환하도록 분리한다. 이 작업은 runtime이 없는 FED rule을 추가하는 것이 아니다.

Scalar/metadata parameter, fused kernel의 실제 operand, shape와 canonical range alignment는 계속 검사한다. 단순히 FType enum을 네 값으로 치환하는 작업이 아니다.

### 5.4 Privacy Filter

**[확인]** 현재 ExecPlacementPolicy.requiresOriginResidency는 PRIVATE와 미해제 PRIVATE_AGGREGATE를 보호하고, CandidatePrivacyInputPruning은 output privacy 외에 protected input position을 검사한다. derivePrivacyConstraint는 입력 facts가 없으면 PUBLIC을 발명하지 않는다. [policy][privacy-policy], [입력 pruning][privacy-input], [transfer][privacy-transfer]

**[제안]**

~~~text
for occurrence in facts:
    rules = []
    for nativeRule in oracle.abstractRules(occurrence, canonicalShapes, W):
        executionCondition = privacy.executionCondition(nativeRule, facts.bindings(occurrence))
        if provablyImpossible(executionCondition): continue
        options = []
        for recipe in movementPolicy.recipesFrom(nativeRule.nativeOutput):
            condition = executionCondition AND
                        privacy.outputAndMovementCondition(nativeRule, recipe)
            if not provablyImpossible(condition):
                options.add(recipe with condition)
        if options is not empty:
            rules.add(nativeRule + executionCondition + options)
    Memo[occurrence] = immutable(privacy facts, rules)
~~~

여기서 “불가능”은 **그 rule이 사용할 수 있는 어떤 관련 입력 환경에서도 합법일 수 없음**이다. 입력 선택에 따라 참/거짓이 달라지면 조건을 남기고 optimizer가 실제 선택과 모든 reachable 환경에 대해 검증한다. 조건을 무조건 참으로 만들거나 비공개 branch를 버리지 않는다.

예: PRIVATE_AGGREGATE X에 대한 합법적인 FED sum의 LOCAL 결과와 raw X를 COLLECT한 뒤 CP sum하는 것은 다르다. 전자는 aggregation release 조건을 통과할 수 있지만 후자는 원본 공개가 먼저 발생한다. Output PUBLIC만 보고 CP와 모든 movement를 허용하면 안 된다.

Privacy propagation은 source/branch/loop facts의 제한된 데이터흐름 분석으로 남는다. 반복이 필요하더라도 모든 physical candidate를 재생성할 이유는 없다. Fine-grained privacy까지 네 partition/몇 개 privacy enum으로 환원해도 된다는 근거는 없다. 기존 metadata/worker privacy 계약을 보존하며 parity가 입증되지 않은 경우 해당 입력 조건을 지원 완료로 선언하지 않는다.

## 6. Optimizer: Memo를 직접 사용하면서 전역 선택 유지

### 6.1 필요한 최소 공동 상태

**[제안]** 정적 선택 변수는 다음과 같다.

~~~text
R[o] = selected semantic ruleId at compiledChoiceOwner(o)
S[e,b] = selected SupplyChoice (recipe + 필요한 schedule selector) for edge e, binding b
P[j] = chosen boundary partition, only where a join/loop/formal needs a contract
A[m] = materialization activation; derived from selected supplies and guards
~~~

P는 가능한 경우 기존 read/alias의 rule output에서 유도하여 독립 변수를 없앤다. A도 독립 선택이 필요 없으면 OR demand의 계산 결과다. 같은 compile epoch의 동일 compiled function body를 공유하는 invocation들은 R을 공유하며, 서로 다른 값과 S는 분리한다. 재컴파일 시 새로운 epoch/owner를 사용한다.

최소 검사 인터페이스:

~~~text
isCompatible(producerRule, supply, consumerRule, inputPosition, facts, environment)
isAvailable(valueVersion, supply, useSite, facts, activationScope)
isPrivacyLegal(rule, supplies, facts, environment)
isBoundaryLoweringLegal(transientOrRecompileSite, rule, supplies, facts)
evaluateCost(selectedRules, selectedSupplies, materializationSchedule, facts)
validateAllReachableEnvironments(selectedPlan, facts)
~~~

단순 pairwise 검사 외에 같은 guard를 공유하는 공동 입력, loop carried tuple, function compiled owner, shared movement activation에는 작은 joint predicate가 필요하다. **관계의 의미는 남기되 이를 별도 범용 graph로 복제하지 않는다.**

### 6.2 가장 설명하기 쉬운 정확 탐색

첫 구현은 finite domain branch-and-bound가 가장 투명하다. HOP DAG와 facts에서 관련된 변수만 조회한다. 전체 assignment를 미리 생성하지 않는다.

~~~text
solve(Memo, facts):
    validateFixedPoolAndSourceContracts(facts)
    domains = semantic rule IDs per choice owner,
              supply recipe/schedule selectors per (edge, fact binding), boundary partitions
    pruneOnlyProvablyUnsupportedValues(domains) // unknown is not false
    best = none
    search(emptyAssignment)
    return OPTIMAL/INFEASIBLE only after complete coverage and validation
           otherwise INCOMPLETE with optional verified best

search(partial):
    propagate affected HOP edges and branch/loop/function predicates
    if any definitely-false predicate: return
    if certifiedLowerBound(partial) > bestCost: return
    if complete(partial):
        schedule = constructSelectedSupplySchedule(partial, facts)
        require canonicalLayoutAndPrivacy(partial)
        require allBoundaryLoweringsLegal(partial, facts)
        require validateAllReachableEnvironments(partial, facts)
        require validateLoopEntriesAndInduction(partial, facts)
        require allSuppliesAvailable(schedule)
        cost = evaluateCost(partial, schedule, facts)
        update best with deterministic tie rule
        return
    v = smallest unresolved relevant domain
    for value in domain(v):
        assign v=value; search(partial); undo
~~~

Branch guards는 탐색 변수가 아니다. 모든 관련 guard 환경에서 검사하며, guard별 이동은 해당 실행 경로의 프로그램 위치에 emission한다. Loop의 무한 반복을 펼치지 않고 entry+induction 검사를 사용한다. 알려지지 않은 runtime predicate 관계는 양쪽을 포함하는 안전한 over-approximation으로 처리하며 “정확하게 불가능한 경로만 제거한다”는 한계를 명시한다.

검사 결과 UNKNOWN인 leaf는 불합법으로 확정한 leaf와 구분한다. Unresolved native coverage나 아직 배제하지 못한 미해결 leaf가 남으면 탐색이 끝나도 optimum/infeasible을 확정하지 않는다. 상세 종료 상태는 [후속 검증의 search 계약](FEDPLANNER_EARLIEST_SAFE_PRUNING_2026-10-13_KO.md#55-비용-탐색과-cache의-경계)을 따른다.

이 방식은 유한한 모델·정확한 제약·완전 탐색 종료와 아래 schedule 계약을 전제로 **모델 목적함수의 전역 최적해**를 준다. 일반 DAG와 shared movement에서 최악의 지수 복잡도는 남는다. 작은 Memo가 다항시간 최적화를 보장하지 않는다.

constructSelectedSupplySchedule은 고정된 emission 순서와 scheduling policy 아래 **완료된 선택마다 유일한 canonical schedule**을 만드는 순수 함수여야 한다. 그 선택에 대한 임의의 첫 schedule만 만들어서는 안 된다. Creation/cache/retention/cleanup/grouping의 다른 합법 선택이 비용 또는 feasibility를 바꾼다면, 정책상 고정해 최적성 범위를 좁힌 사실을 명시하거나 해당 구별을 S의 유한 선택에 포함해야 한다. 권고는 필요한 구별만 S에 추가하고 같은 search로 탐색하는 것이다. 모든 지원 계획이 R/S/필요 P의 어떤 assignment로 표현되어야 하며, 고정 policy 안에서도 schedule 대안을 숨겨 놓고 optimum을 주장하지 않는다. 같은 materialization key의 demand들이 선택한 retention 조건은 공동으로 만족시켜야 하며 충돌하면 해당 선택을 거절한다. Leaf의 require 실패는 해당 선택을 거절하고 탐색을 계속한다는 뜻이다.

기존 ExactCategoricalSolver의 variable elimination은 작은 문제의 reference로 재사용한다. 새 기본 경로에는 직접 predicate search 하나를 권고한다. 단순히 lazy factor를 기존 solver에 넘기면 준비 단계의 freeze와 separator 생성에서 조합 table이 다시 생길 수 있으므로, 이를 조합 생성 회피의 대안으로 간주하지 않는다. 이후 실제 측정으로 필요성이 입증된 작은 tree/separator의 exact DP만 별도 최적화 후보로 남긴다. [solver API][solver], [lazy freeze][factor-freeze]

### 6.3 비용과 sharing

~~~text
C(plan) =
    Σ execution context x n(x) *
      [compute(R[compiledChoiceOwner(x)], shape(x))
       + nativeInternalCommunication(R[compiledChoiceOwner(x)], shape(x))]
  + Σ materialization expectedActivations(m) * movementUnitCost(m)
  + explicit boundary/cleanup costs that are not already owned above
~~~

실행 context x는 실제 native execution owner의 callsite와 분석된 loop/branch context를 구분한다. Multi-output carrier는 새 실행 context/compute 항이 아니다. 반복의 횟수는 n(x)에 반영하며 body를 iteration 수만큼 펼치지 않는다. Compiled body의 R을 공유하더라도 호출별 shape와 실행 비용은 별도다. 동일 dynamic value, 동일 destination, 동일 recipe, 호환되는 activation/lifetime의 materialization만 공유한다. Input edge마다 cost를 더하지 않고 해당 materialization을 요구하는 demand의 **합집합**을 과금한다.

- 두 직선 consumer의 같은 collect는 실제로 retained되고 생성 위치가 두 use를 지배하며 lifetime이 유지되면 1회다.
- mutually exclusive branch의 이동은 branch probability로 가중한 실제 activation이다.
- 같은 static HOP여도 다른 loop iteration에서 만들어진 값은 서로 다르다.
- 같은 formal 이름이어도 다른 function actual은 다르다.
- loop 밖의 불변 값은 실제 runtime에서 보존되고 lifetime이 허용될 때만 반복 간 공유한다.
- D1 우회에서 두 output이 중간 LOCAL을 공유하면 COLLECT는 1회, 각 target UPLOAD는 각각 과금한다.

Peak memory/lifetime은 emission schedule과 맞아야 한다. Runtime이 cache/hoisting을 지원하지 않으면 비용만 공유한 것으로 계산하지 않는다. 여러 legal creation scope, cache owner, retention/cleanup 또는 grouping 선택이 있으면 이를 S의 작은 선택지에 포함하거나 정책으로 고정하고 그 범위 안에서만 최적성을 주장한다. 선택이 끝난 뒤 서로 다른 비용의 schedule 중 첫 것을 임의로 고르는 구현은 exact하지 않다. 고정 순서에서 같은 materialization의 마지막 use까지 보존하는 결정적 규칙을 쓰더라도 privacy/alias/cleanup과 실제 runtime lifetime이 이를 지원하는지 검증한다.

한 iteration 안의 then/else는 mutually exclusive여도 서로 다른 iteration에서는 두 arm 모두 실행될 수 있다. Guard event의 loop epoch를 유지하여 반복을 통틀어 한 번의 배타적 사건으로 합치지 않는다. 불변 값의 지연 materialization을 여러 iteration에 걸쳐 공유한다면 최초 activation 횟수 모델이 추가로 필요하다. 현재 ExactMaterializationActivation의 검증된 union/보수적 처리를 재사용하고, 확률·overlap 정보가 부족하면 명시한 보수적 비용 모델로 제한한다.

**[확인]** 현 ExactPhysicalCostModel은 source/value creation context, loop epoch, guard activation, alias와 fresh output을 구분한다. ExactMaterializationActivation은 event overlap이 검증되지 않으면 unresolved/conservative union 경로도 가진다. 따라서 “Exact”는 실제 runtime 비용을 정확히 예언한다는 뜻이 아니다. [공유 비용][shared-cost], [activation][activation]

### 6.4 기존 Cost Model·Local·Exact 재사용 정도

| 구성요소 | 재사용 | 변경/주의 |
|---|---|---|
| ComputeCost, FederatedCostModel의 FLOPs/bytes/network/latency helper | 높음 | REP는 W>1에서 BROADCAST 비용으로 매핑. FULL 비용을 쓰면 과소계산 |
| PlacementCostSemantics.PreparedExecutionCost | 높음 | occurrence shape와 canonical input layout을 직접 공급. fused/native stage 보존 |
| OccurrenceExecutionFrequencyFacts | 높음 | invocation/branch/loop weight는 추정치. 합법성의 branch 선택권이 아님 |
| ExactMaterializationActivation | 조건부 높음 | creation/lifetime binding을 새 selected supply에 연결. 불확실 overlap 처리 계약 유지 |
| ExactCategoricalSolver | reference로 높음 | 작은 fixture의 전역 최소 비교에 재사용. 일반 lazy factor의 freeze는 product를 생성하므로 새 기본 backend로 그대로 연결하지 않음 |
| ExactPhysicalOptimizer | 일부 | numeric/검증 로직 재사용 가능. PhysicalModel/receipt 의존 입출력은 교체 |
| LocalCategoricalOptimizer·IncrementalRegionalOptimizer | 선택적 | 작은 Memo에서도 heuristic/upper bound 용도로 가능. 정확 최솟값의 대체물 아님 |
| ExactPhysicalModel.Alternative, projection/encoding 준비부 | 낮음 | 실행+source+clause+authority가 합쳐져 있어 새 구조와 목적이 다름 |
| ExactPhysicalCostModel의 거대 factor 구성 | 부분 재사용 | 단가와 activation 원리는 유지하고 Alternative/product 결합부를 제거 |

현재 DP factory는 FederatedPlanLocalCost를 선택하고, 그 경로는 LocalPhysicalOptimizer에서 regional optimization을 수행한다. 기본 relativeGap=0.05, earlyStop=true다. 정확한 최솟값이 요구되면 Exact 경로 또는 lower=upper가 증명된 완전 탐색이 필요하다. 비교는 동일 canonical/privacy/movement 지원 범위와 동일 비용 모델에서 해야 한다. [DP factory][factory], [Local pipeline][local-entry], [설정][regional-options]

재사용할 단가 함수의 직접 위치는 PlacementCostSemantics.PreparedExecutionCost.localCost/federatedCost, FederatedCostModel.computeReusableMaterializationDownloadCost/computeUploadNetworkCost다. REP collect도 현재 runtime이 replica별 응답을 어떻게 요청하는지에 맞춰 가격을 유지하고, 한 replica만 읽는 새 최적화를 가정하지 않는다. [실행 단가][compute-cost], [collect 단가][collect-cost], [upload 단가][upload-cost]

## 7. 다섯 사례: 허용·금지, 제약, 비용, 현재 코드

아래 DML은 의미 설명용 최소 예제다. A/B/C/D/P/Q/X/Y의 source metadata, shape와 privacy는 별도 fixture로 제공한다고 가정한다. 새 계약에서의 native rule 가능성은 Oracle가 실제 지원하는 경우에 한한다. 새로운 FED operator 지원을 예제로 가정하지 않는다.

### 7.1 Multi-parent / Shared producer

~~~dml
A = abs(X);
B = A + U;
C = A + V;
D = B + C;
~~~

U/V의 배치·privacy 등에 의해 특정 FED rule이 각각 A의 ROW/COL 공급을 요구하는 상황을 고려한다. 위 DML 자체가 반드시 그 두 rule을 강제한다는 뜻은 아니다.

~~~text
R[A] = 하나의 native rule
Compatible(R[A], S[A→B,0], R[B])
Compatible(R[A], S[A→C,0], R[C])
~~~

| 선택 | 허용 여부 / 이유 |
|---|---|
| A native LOCAL, B에 UPLOAD ROW, C에 UPLOAD COL | 각 upload/privacy가 지원되면 허용. A는 한 번 실행 |
| A native ROW, B에 DIRECT ROW, C에 COLLECT LOCAL을 요구하는 CP rule | collect가 합법이고 두 use의 원본/복사본 lifetime을 유지할 수 있으면 허용 |
| A native ROW, B에 DIRECT ROW, C에 COL | D1 금지면 불가. 허용이면 COLLECT+UPLOAD를 명시 |
| A native REP, 두 consumer에 REP DIRECT | 두 native consumer rule이 REP 입력을 지원하면 허용 |
| B는 A의 CP 실행, C는 같은 A의 FED 실행을 동시에 전제 | 금지. A 재계산/복제는 이번 계약에 없음 |
| 두 LOCAL consumer에 같은 COLLECT를 선택하고 비용을 두 번 부과 | 합법 계획일 수 있으나 비용 모델 오류 |

**Tree DP 최소 비용 반례 [제안 fixture]:** A의 LOCAL 실행비 3, ROW 실행비 4, COL 실행비 4, LOCAL→ROW/COL 각각 2, B는 ROW, C는 COL, D1 금지라 하자. B 단독 tree는 ROW를, C 단독 tree는 COL을 각각 선택한다. 이를 합치면 A에 상충된 native 실행을 부여한다. 공동 해는 A LOCAL 한 번 + 두 upload로 비용 7이다. 수치는 runtime 측정이 아니라 solver correctness fixture다.

공유 materialization 반례: A ROW, B/C 모두 LOCAL 필요, collect 단가 4라면 총 collect 비용은 4다. 독립 subtree가 8로 계산하면 최적 선택을 바꿀 수 있다.

**최소 공동 상태:** R[A], 두 consumer의 R, 각 S, 선택된 materialization의 activation/lifetime. 부분문제 경계를 넘는 A의 선택과 이미 활성화된 공유 공급을 버리면 tree DP가 실패한다. 경계 DP를 쓰려면 이 separator state가 필요하다. Memo rule 자체는 consumer/source 조합별로 복제하지 않는다.

**현재 대응:** ExactPhysicalModel.build의 occurrence별 DecisionDomain, addCorrelatedSupportFactors의 “one owner cannot select two different exact sources”, shared-cost grouping, ExactDerivedSupplySharingTest의 version별 공유 구별이다. [단일 선택][model-build], [상관 support][correlated], [공유 테스트][sharing-test]

### 7.2 If-Else / Branch

~~~dml
if (p) { X = A; Y = B; }
else   { X = C; Y = D; }
Z = X + Y;
~~~

**[제안]**

~~~text
def(X) = ite(guard_p, A, C)
def(Y) = ite(guard_p, B, D)
Reachable environments = { p:(A,B), !p:(C,D) }

∀ reachable environment g:
    resolve each exact definition using g
    check all operand supplies against the one selected rule for Z
    check joint native/privacy conditions
~~~

Guard ID를 공유하므로 (A,D), (C,B)를 만들지 않는다. 사용자 제시 BranchEnvironment는 **guard가 definition tuple 전체를 지배하고, call/loop activation context까지 정확하면 충분한 인터페이스**다. 각 변수별 writer set을 별도로 만든 뒤 곱하면 충분하지 않다.

초기 권장 정책은 **join 이후 각 값에 하나의 partition 계약**이다. X의 계약 px와 Y의 py는 서로 같을 필요는 없지만 Z의 한 inputPattern이 둘을 지원해야 한다. Then/else 내부 native output은 달라도 각 branch exit에서 해당 계약에 맞는 공급을 만든다. Missing else는 기존 값의 pass-through다. 현재 BranchPlacementNormalization이 branch exit의 _PLACEMENT alias 위치를 이미 만든다. [branch normalization][branch-normalize]

| 선택 | 판정 |
|---|---|
| Then (A ROW, B ROW), else (C ROW, D ROW), Z rule (ROW,ROW) | native/shape/privacy가 맞으면 허용 |
| Then ROW pair, else COL pair, 각 branch에서 LOCAL로 collect, Z CP | collect가 모두 합법이면 허용 |
| Then ROW pair, else COL pair, 고정 Z ROW rule인데 else 공급 없음 | 금지 |
| p의 비용이 낮으니 else의 illegal rule을 무시 | 금지. guard는 optimizer 선택 변수가 아님 |
| (A,D) 한 쌍이 가능하다는 이유로 Z를 합법 처리 | 금지. 실제 경로와 무관한 조합 |

Branch별 partition을 join 이후까지 보존하고 Z의 rule을 guard에 따라 바꾸는 대안은 가능하나, runtime dispatch/실제 compiled clone을 요구한다. 이번 단순 baseline에 포함하지 않는다. 이 제한으로 현재 더 유연한 VALUE_MAP 계획 일부가 사라질 가능성을 별도 비교해야 한다.

**비용:** C(predicate)+Pr(p)C(then)+(1-Pr(p))C(else)+C(Z). Branch exit movement도 해당 branch에만 과금한다. 같은 value의 공통 movement를 밖으로 hoist하려면 값의 존재·privacy·lifetime을 만족하고 실제로 emission해야 한다.

**Cartesian product 없는 표현:** structured if 노드나 공유 ite DAG를 그대로 참조한다. 검사 대상 consumer가 읽는 정의만 projection하고 guard conjunction을 따라 필요한 경우 분기한다. 새 solver가 모든 프로그램 path tuple을 사전 생성할 필요는 없다. 다만 독립 branch가 많거나 operator privacy가 여러 입력을 함께 관찰하면 최악의 조합 복잡도는 남는다.

**현재 대응:** CfgAnalysis writer sets; PlacementJointInputAnalysis.executeBlock/tuplesForReads; JointValueMapRelations; ExactPhysicalModel.addJointFactors가 각 joint row의 합법성을 검사한다. 현재 joint 분석은 predicate 식의 모든 논리 상관을 증명하는 symbolic executor가 아니며 finite static definition 분석이다. [CFG][cfg], [joint query][joint-query], [branch 실행][joint-branch], [joint factor][joint-factor]

### 7.3 Loop

~~~dml
A = X;
while (condition) {
    A = A + Y;
}
Z = sum(A);
~~~

값을 A.entry, A.head(t), A.next(t), A.exit로 구분한다. A.head(0)=X, A.head(t+1)=A.next(t)이며 0회 반복의 A.exit=X도 포함한다. Static definition ID는 같더라도 dynamic value epoch는 다르다.

**[제안]** loop-carried partition pA와 compiled body의 R을 고정한다. 한 반복 안의 native partition과 head partition은 다를 수 있지만 backedge 공급이 pA를 만족해야 한다. iteration마다 비용에 따라 R을 바꾸는 정책은 도입하지 않는다. 명시적 recompilation이 일어나면 새 epoch에서 boundary를 재검증한다.

~~~text
Base:
  ∀ reachable entry environment:
    entry values exist before the loop
    selected entry supplies provide the complete head tuple

Step:
  assume the head tuple is available at start of one iteration
  validate the body in execution order under every reachable body branch
  every backedge supplies the complete next-head tuple

Exit:
  zero-iteration and post-iteration values satisfy the exit consumer contract
~~~

| 선택 | 판정 |
|---|---|
| X LOCAL, 합법적인 entry UPLOAD ROW, body ROW→ROW, Z의 합법 aggregate | 허용 |
| X LOCAL, entry upload 불가, body가 스스로 ROW를 만든다는 이유만으로 head ROW | 금지 |
| 첫 iteration에 body 끝에서 생성될 LOCAL copy를 entry 공급으로 사용 | 금지 |
| 한 body branch의 backedge만 compatible | 금지. 모든 reachable backedge 필요 |
| 매 반복 A가 새 값인데 A upload를 최초 1회만 과금 | 비용/availability 오류 |
| Y가 loop-invariant이고 entry에 존재하여 1회 upload 후 보존 | privacy·실제 lifetime/emission이 허용하면 가능 |

**현재 Closure의 정확한 의미 [확인]:**

1. captureLoopEntryAnchors는 entry 전에 존재하는 pool만 허용한다.
2. exactLoopPlacementSeed는 비재귀 entry definitions에서 **greatest placement fixed point**의 provisional seed를 구성한다.
3. provisional context는 backedge를 제외해 시작하고, composed transfer가 안정화된 뒤 seed를 제거하여 모든 definition을 포함한 closure를 다시 수행한다. “seeded fixed point 자체는 executable proof가 아니다.”
4. exactSinglePartitionRealizationProofs는 별도로 **least fixed point**에서 grounded/cardinality support를 전파한다.

따라서 현재 Closure 전체를 단순한 하나의 least fixed point라고 설명하면 틀리다. [entry anchors][loop-entry], [provisional context][loop-provisional], [seed discharge][loop-discharge], [placement seed][loop-seed], [grounded fixed point][loop-ground]

**최소 대체 로직:** 새로운 rule universe는 source-independent이므로 candidate 재생성을 없앤다. 선택된 loop plan마다 entry base case와 한 번의 body induction을 검사한다. Within-iteration supply graph는 HOP order와 movement order를 따라야 하며, 허용하는 유일한 시간 순환은 명시적 previous-iteration binding이다. Facts/shape/privacy에 순환이 남으면 해당 loop SCC에 한정해 유한 domain worklist를 사용한다.

~~~text
validateLoop(loop, selected):
    require all entry bindings legal and available outside loop
    headContract = selected boundary partitions + shape/privacy invariants
    assume headContract only after entry check succeeds
    validate body DAG/structured branches with concrete selected rules
    require every carried output preserves headContract
    require no same-iteration supply cycle
    require legal zero-iteration and ordinary exits
~~~

Entry에서 도달 가능한 SCC라는 것만으로 충분하지 않다. **모든 entry와 backedge 및 공동 carried tuple의 귀납 조건**이 필요하다. Loop 안에서 X/Y를 함께 갱신하면 branch와 마찬가지로 tuple correlation을 보존한다. 존재하는 하나의 seed로 다른 incompatible backedge까지 정당화하지 않는다.

**비용:** initializer/entry movement 1회 + E[N]×(body와 바뀐 값의 movement) + E[N+1]×predicate + exit. N을 모르면 현재 추정치를 명시하고 최적성은 그 비용 모델에 대해서만 주장한다. Shape가 iteration마다 변하는 경우 canonical layout과 rule validity를 shape invariant 또는 검증된 recompilation boundary로 보장해야 한다. 불명확한 shape를 고정으로 가정하지 않는다.

고정 W descriptor는 값이 없는 시점에도 존재할 수 있으므로 기존의 “body에서만 처음 등장한 anchor라 entry upload 불가” 제약 일부는 사라진다. 그러나 **entry의 실제 X와 privacy/allocator support는 여전히 필요하다.** 배치 metadata의 존재와 데이터의 존재를 구분하는 것이 Anchor 제거의 핵심이다.

### 7.4 Function Call

~~~dml
f = function(matrix[double] X) return (matrix[double] Y) {
    Y = abs(X);
}
A = f(P);
B = f(Q);
~~~

**[확인]** FunctionCallCPInstruction은 namespace/name으로 FunctionProgramBlock을 찾고 call-local actual/formal 값을 바인딩해 같은 body를 실행한다. PlacementRelationClosure도 callsite boundary는 분리하지만 formal body node에 SAME_PLACEMENT를 연결한다. FunctionProgramBlock/Recompiler는 조건에 따라 함수 실행마다 재컴파일할 수 있다. “함수마다 영구히 단 한 번 컴파일한다”는 설명도 부정확하다. [호출 runtime][function-call], [function 실행][function-block], [재컴파일][function-recompile], [boundary][function-boundary]

| 설계 | 장점 | 필요한 조건 / 단점 |
|---|---|---|
| A. Memo[(HopId,CallContext)]와 호출별 body 선택 | actual shape/privacy/layout에 특화 가능 | 독립 compiled body clone/dispatch 또는 검증된 재컴파일 plan 설치 필요. Key 분리만으로 runtime code가 분리되지 않음 |
| B. reusable body rules + callsite binding | 현재 shared FunctionProgramBlock과 맞고 구조가 작음 | 모든 callsite를 만족하는 shared body contract가 필요. 호출별 movement 비용은 남음 |

**권장: B를 기본으로 하되 compile epoch와 shape/privacy validity를 구분한다.** Immutable Oracle rule template만 공유할 수 있고, occurrence-specific Memo facts까지 무조건 공유하면 안 된다. 같은 emitted body를 가리키는 선택에는 equality constraint를 둔다. 이미 inlining/specialization으로 실제 HOP/body가 분리된 경우에는 독립 선택이 가능하다.

~~~text
R[body.abs] = shared compiled choice
Actual(call1, formal0)=P; Actual(call2, formal0)=Q
Return(call1, slot0)=A; Return(call2, slot0)=B

∀ reachable callsite:
    actual supply satisfies shared formal contract
    body plan is valid for that invocation's shape/privacy
    return supplies satisfy caller's selected input requirements
~~~

| 선택 | 판정 |
|---|---|
| P/Q 모두 shared ROW formal로 합법 공급, abs ROW, 두 return 별도 값 | 허용 |
| call1은 P, call2는 Q인데 같은 formal명 때문에 materialization 공유 | 금지 |
| P는 ROW, Q는 COL, body는 ROW, Q를 바꿀 합법 공급 없음 | 금지 또는 다른 common body 선택 |
| 동일 FunctionProgramBlock에 call1 FED body, call2 CP body를 동시에 기입 | 금지. 실제 specialization 없이는 실현 불가 |
| 원래 P/Q가 같은 불변 runtime object이고 lifetime도 동일 | actual supply sharing은 검증 시 가능. 함수 실행 결과를 자동 공유하지 않음 |

**비용:** 각 callsite frequency×body/native 비용 + actual/return movement. Compiled code 공유는 compute 결과 공유가 아니다. Shape별 compute 비용이 다르면 동일 body rule이라도 호출별 단가를 계산한다. Recursion은 SystemDS runtime의 지원 여부와 분리한다. 현 Exact frequency 분석은 recursive function context를 거절하므로 초기 exact redesign도 재귀 최적화 지원을 명시적으로 제외하는 편이 일관된다. [재귀 제한][recursion]

재컴파일은 compile epoch 갱신, 실제 입력 metadata/privacy 확인, 기존 selected supply/registry 무효화, 선택과 emission 재검증을 하나의 경계로 취급한다. HOP clone의 원래 ID를 그대로 cache authority로 쓰지 않는다. 현재 recompile에서 CP→FOUT을 제한하는 계약은 새 state에서 사라지는 이름과 별개로 lowering 제약으로 남는다. 이를 지원하려면 별도 runtime 변경·검증이 필요하다.

재컴파일을 포함한 전 실행의 최적성을 원한다면 future shape/context와 호출 빈도까지 모델이 포괄해야 한다. 초기 구현의 Exact 보장은 **고정된 분석 snapshot과 명시한 context/비용 모델의 범위**다. 미지의 미래 입력에 대해 전역 최적이라고 확대하지 않는다.

### 7.5 Transient Read/Write

~~~dml
X = A;
if (p) { X = B; }
Y = abs(X);
X = C;
Z = abs(X);
~~~

**[제안]** 첫 write를 X.v0, branch write를 X.v1, 마지막을 X.v2로 구분한다. Y의 read는 ite(p,X.v1,X.v0), Z의 read는 X.v2다. 같은 변수명이나 가장 최근에 발견한 HOP로 resolve하지 않는다.

| 선택 | 판정 |
|---|---|
| Y는 true에서 B, false에서 A; Z는 C | 허용, 각 공급의 partition/privacy 검사 필요 |
| Y가 싼 A 또는 B만 골라 모든 경로에서 사용 | 금지 |
| Y가 미래 writer C를 사용 | 금지: non-dominating/future value |
| 함수 local X와 caller X를 변수명으로 동일시 | 금지 |
| alias가 같은 underlying value임이 증명돼 collect를 공유 | lifetime과 activation이 같으면 허용 |

TRead/TWrite는 새 값을 계산하는 일반 CP/FED kernel이 아니라 binding/alias boundary다. Payload materialization은 명시적 edge action이다. 새 planner state에서 LOUT/FOUT은 제거해도 현 emission의 transient legality를 무시하지 않는다. Boundary alias의 native state와 선택된 movement를 분리하면 기존 CP,LOUT / FED,FOUT 계약을 유지하며 설명할 수 있다.

isBoundaryLoweringLegal은 transient의 emitted 상태를 **(CP,LOCAL) 또는 (FED,ROW|COL|REP)**로 제한한다. LOCAL writer의 federated 공급이 필요하면 CP TWrite의 output option을 FED인 것처럼 선언하지 않는다. 검증된 pre-boundary materialization을 선택하고 그 실제 FED 값을 FED TWrite가 alias하도록 lowering할 수 있을 때만 허용한다. 그런 lowering이 없는 위치에서는 해당 선택을 제외한다. Recompile의 기존 CP→FOUT 금지도 이 gate에 보존하며, 이름이 바뀌었다는 이유로 LOCAL→FED promotion을 허용하지 않는다. [현재 transient/refed 계약][boundary-policy]

여러 reaching writer가 있으면 **guard별 모든 실제 writer**가 선택한 read 계약을 공급해야 한다. Branch/loop/function을 지나는 경우 앞 절의 동일 facts를 재사용한다. Runtime에 없는 writer를 optimizer가 선택하는 별도 source-choice variable은 만들지 않는다.

**비용:** read/write 자체의 alias 비용과 materialization 비용을 구분한다. 같은 underlying object의 read는 공유할 수 있지만 X.v0/v1/v2의 전송을 한 번으로 합치지 않는다. Loop에서는 previous-iteration binding과 현재 write가 다른 epoch임을 반영한다.

**현재 대응:** PlacementProgramFacts.analyzeCfg/valueVersion, LogicalTransientInputFact, PlacementAnalysis의 reaching-definition support 검사, ExactPhysicalModel.addStrictTransientFactors/addLogicalBoundaryFactors, ExactTransientRealizationTest다. [CFG][cfg], [transient fact][transient-fact], [transient support][transient-support], [transient factor][transient-factor], [transient 테스트][transient-test]

### 7.6 코드에서 도출한 invariant와 단순화 정책의 구분

이 절의 제약은 DML 의미와 현 runtime/테스트에서 도출한 것이다. 앞 절의 fixed join/body 선택을 미리 정해진 언어 semantics로 취급하지 않는다.

| 대상 | 반드시 보존하는 의미 [확인·추론] | 초기 설계가 택한 정책 [제안] | 보관/처리와 시간·공간 tradeoff |
|---|---|---|---|
| Multi-parent | 실제 한 producer 실행의 선택과 값은 모든 use에서 일관됨 | producer 재실행/복제는 도입하지 않음 | Memo에 R 하나, HOP edge별 S. Source 조합 복제 대신 관련 R들을 함께 검사 |
| Transient | read 시점에 도달한 정확한 writer/value를 읽음 | transient를 값 생성 kernel 대신 binding으로 취급 | Facts의 def/use+alias, optimizer의 guard별 supply, emitter의 boundary gate. 이름별 global map은 불가 |
| Branch | 같은 branch에서 발생하는 definitions의 공동 관계와 모든 도달 경로의 합법성 | join 뒤 non-dispatched rule의 고정 입력 계약 | Facts의 shared guard, 필요 joint predicate. 고정 join은 DML 필수조건이 아니라 지원 범위 제한 |
| Loop | entry 존재, 모든 backedge의 보존, zero iteration, 이전/현재 반복의 값 분리 | 같은 compile epoch의 body rule 고정 | Facts의 SCC/entry/backedge, selected-plan induction. 동적 shape를 고정 수치라고 가정하지 않음 |
| Function | actual/formal/return이 호출별 정확하고 실제 emitted body와 선택이 일치 | 같은 epoch의 비특화 body를 공유; 새 specialization은 도입하지 않음 | 공통 R와 호출별 binding/cost. 재컴파일/실제 clone은 별도 owner |

**[확인]** JointInputReachingDefinitionsTest는 동일 slice의 consumer를 한 CFG pass로 묶으면서 tuple을 유지하고, loop entry/backedge와 finite static call identity를 검사한다. FunctionBoundaryRuntimeAliasContractTest는 input/output의 실제 alias와 명시적 transfer 경계를 구분한다. StepLmDynamicLoopPlacementTest는 두 backedge와 dynamic columns에서도 executable ROW read를 보존한다. 따라서 “body rule 고정”과 “shape를 compile 시점 숫자로 고정”을 혼동하면 안 된다. [joint 회귀][joint-regression], [function alias 회귀][function-alias-test], [dynamic loop 회귀][dynamic-loop-test]

**[확인]** 현재 joint 분석은 same structured branch의 상관관계를 보존하지만 predicate expression에는 path-insensitive다. 서로 다른 두 if(p)를 일반적으로 논리적으로 연계하지 않으며 loop 조건을 정확히 풀어 iteration path를 결정하지도 않는다. tuplesForReads는 같은 statement block의 read들을 대상으로 하고 invocation 결과 cache는 actual provenance를 포함한다. [joint 분석][joint], [joint query][joint-query], [joint invocation][joint-invoke]

**[제안]** 새 설계도 처음에는 이 보수적인 CFG 의미를 유지한다. 한 branch의 guard ID를 모든 값에 공유하되, 변수명이 p라는 이유만으로 다른 시점·호출·iteration의 predicate를 같다고 합치지 않는다. Symbolic guard는 제어 흐름의 상관관계를 표현하는 것이며, 새 SMT/BDD/predicate theorem prover를 도입하자는 뜻이 아니다. 최적성은 이 보수적인 도달 환경과 문서화한 static contract 내에서 평가한다.

## 8. Constraint 표현의 최종 결정

**[제안]** 새 범용 ConstraintGraph 클래스는 만들지 않는다. 다음 세 종류를 HOP DAG/Program Facts에서 직접 읽는다.

1. **Edge-local predicate:** producer R, consumer R, input position, 공급 S, 실제 정의와 movement 경로.
2. **기존 구조 관계:** shared producer R, structured branch bindings, loop-carried entry/backedge, function actual/formal/return, compile owner equality.
3. **필요한 작은 joint 계산:** 여러 operand의 privacy/native 조건, correlated definitions, materialization demand 합집합과 lifetime.

이 의미는 수학적으로 constraint/factor 관계이지만 별도의 범용 graph, 노드 inventory, graph 복제, candidate별 edge 객체를 요구하지 않는다. 문제가 큰 경우 solver 내부의 factor/separator 자료구조는 사용할 수 있다. “별도 Constraint Graph 없음”을 “다변수 관계 없음”으로 해석하지 않는다.

필요한 최소 추가 정보는 현재 CfgAnalysis set에 없는 **guard와 정의의 상관**, **entry/backedge 분류**, **compiled function body 공유 관계**, **materialization activation/lifetime**이다. 상당 부분은 현재 다른 클래스에 이미 있으므로 Program Facts의 조회 함수로 모으는 것이 신규 객체 계층보다 낫다.

알고리즘의 정확성은 다음 세 수준을 구분한다.

- **Soundness:** 채택한 계획이 모든 관련 runtime 실행에서 합법적이다.
- **Completeness:** 문서화한 canonical/privacy/movement/static-boundary 계약 안의 합법 계획을 빠뜨리지 않는다.
- **Optimality:** 위 유한 계획 집합과 고정된 비용 모델에 대해 최소값을 찾는다.

Constant/predicate reasoning의 보수성, 고정 join partition, 고정 function body, no-detour 등으로 기존 runtime 전체 대비 completeness는 줄 수 있다. 그 제한을 runtime 미지원이라고 잘못 설명하지 않는다.

## 9. 제거·통합·유지·신규 구현 표

아래는 새 planner lane의 목표다. 공통 클래스가 FedAll/Heuristic 등에도 쓰이면 새 lane 검증 전에 전역 삭제하지 않는다.

| 분류 | 현재 클래스/함수 | 변경 제안 | 남겨야 할 invariant |
|---|---|---|---|
| 유지/축소 | PlacementProgramFacts.analyze | immutable snapshot과 기존 facts 재사용 | 정확한 occurrence, 값 버전, control flow |
| 유지/노출 | PlacementJointInputAnalysis, JointValueMapRelations | branch/call/loop joint 정보의 facts query로 통합 | 동시에 도달 가능한 정의 |
| 유지 | PlacementIdentity.CompiledHopKey, ValueVersionKey | 안정화 뒤 필요하면 ID 저장 간소화 | compile epoch와 정확한 값 |
| 유지 | PlacementShapeFacts, PlacementAbstractShapeAnalysis | canonical shape contract 입력으로 사용 | shape 불명확성을 확정값으로 오인하지 않음 |
| 유지/분리 | PlacementPrivacyFacts, derivePrivacyConstraint | Privacy Filter + facts transfer로 이동 | 보호 입력/결과 release/조건부 privacy |
| 수정 | OracleFacade.PreparedDecision, RulesApi.OpCaps, Rulesets | NativeRule 목록 API와 native lowering mode | 실제 runtime capability |
| 통합 | PlacementCandidateGenerator.buildNode | 작은 Memo builder | 추상 pattern, native output, 조건부 options |
| 통합 | CandidateRuleKey/Fact/Relation, CpRuleFamily | FeasibleRule와 필요한 compact input pattern | 동일 partition이어도 서로 다른 실행 조건 |
| 제거 가능 | CandidateRealization/Reference, InputBinding의 후보별 복제 | selected edge와 facts binding만 유지 | 정확한 source와 operand position |
| 제거 가능 | Source Owner 중복 descriptor | HOP/facts occurrence ID 참조 | producer 단일 선택과 snapshot scope |
| 제거 가능 | DurableAnchorKey, anchor 선택/continuity 탐색 | 하나의 W descriptor + canonical helper | 실제 layout 검증과 데이터 availability |
| 제거 가능 | PlacementProofKey, SupportClause, NativeContinuitySupportClauses, Indexed/Factorized carrier | selected-plan 직접 검사; 조건은 facts/predicate에 유지 | sparse hole 금지, joint correlation, grounding |
| 분해 후 제거 | PlacementRelationClosure의 전역 후보 재생성 | facts의 제한된 dataflow + selected loop induction | entry와 모든 backedge, source 존재, privacy |
| 제거 가능 | NativePlacementContinuity topology/circuit/proof cache | immutable facts 및 필요한 직접 predicate cache | semantic 동등성과 stale-state 거절 |
| 대체 | NeutralPlacementGraph의 중복 HOP/constraint inventory | 원래 HOP DAG와 boundary facts 직접 참조 | synthetic function/boundary가 갖던 의미 |
| 대체 | ExactPhysicalModel.Alternative와 source-product 전개 | R/S/boundary의 작은 domain | 하나의 선택과 공동 합법성 |
| 일부 유지 | ExactPhysicalCostModel, PlacementCostSemantics, FederatedCostModel | 단가/activation 재사용, old authority 결합 제거 | 공유 비용과 native 내부 통신 |
| 일부 유지 | ExactCategoricalSolver, numeric summation/certification | 작은 공통-domain reference; 수치 utility 재사용 | exact/incomplete 구분, 수치 계약. 새 기본 탐색은 직접 B&B |
| 선택적 유지 | Local/IncrementalRegional optimizer | heuristic 또는 upper bound로 사용 | 전역 최적성으로 과장하지 않음 |
| 수정/유지 | ExactPhysicalPlacementProjector, PlacementEmissionTransaction | selected rule/supply lowering으로 교체, transaction 보존 | 정확한 operand/action, 재검증, 실패 원자성 |
| 수정 | FEDFoutInstruction, FEDLocalMaterializeUtil, materialize registries | 향후 fixed W/canonical descriptor 수용 | 실제 upload/range/retention 계약 |
| 신규 최소 | Partition enum, FeasibleRule/MemoValue, InputEdgeId, selected-plan checker | 기존 package 안의 작은 클래스/함수 | 새 범용 framework 도입 불필요 |

**삭제 순서 주의:** 현재 receipt는 support-clause 객체 identity와 order를 소비한다. 최종 선택을 의미 기반 R/S receipt로 옮기기 전에 proof/clause만 지우면 emission 검증이 깨진다. Identity 객체 자체는 필수가 아니지만 foreign/stale/withdrawn 선택을 거절하는 의미는 필수다. 미커밋 sparse 테스트를 새 representation의 semantic parity 테스트로 이전한다. [기존 authority 병합][authority-merge], [receipt][receipt], [parity 테스트][parity-test]

Emission의 prevalidate/emitInternal은 공급 검증에 실패한 계획을 고치는 단계가 아니다. 새 구조도 검증 실패를 명시하고, 선택되지 않은 fallback이나 누락 movement를 runtime에 맡기지 않는다. [emission 경계][emission]

## 10. 실제 runtime과 새 계약의 차이

아래는 **현재 소스의 정적 동작**이다. 해당 operator 전체를 지원 불가로 선언하는 표가 아니라, 특정 native 경로를 새 계약에 넣을 수 있는지 판정하는 표다.

| 연산/경계와 최소 예 | 현재 동작 [확인] | 새 계약에서의 처리 [제안] |
|---|---|---|
| Right indexing: X가 두 worker ROW, Y=X[1:10,] | rightIndexing이 map.filter(ixrange)로 일부 worker만 남길 수 있음 | all-W/canonical인 특별한 경우만 native 채택. 그 외 LOCAL 경로 또는 명시적 지원 범위 제외 |
| Partition 방향 append: Y=rbind(X,X) | FederationMap.bind로 기존 map을 이어 붙임. 같은 worker가 여러 range를 가질 수 있음 | 같은 output shape의 canonical ROW와 다르면 native ROW로 기록 금지 |
| rmempty(X,margin="rows") | worker별 실제 남은 row 수로 output range 재설정 | shape=50이어도 40/10 분할이 canonical 25/25와 다름. Canonicality를 증명할 수 없는 FED output rule은 baseline에서 제외; 검증된 LOCAL 경로만 별도 유지 |
| roll | range 이동과 wrap-around split | partition enum 불변만으로 DIRECT/canonical 승인 금지 |
| FULL source, W>1 | FULL은 완전한 비복제 객체이며 1-worker materialization 제약 | REP로 단순 rename 금지. W 전체 replica가 검증된 BROADCAST만 REP |
| LOCAL→ROW, rows < worker 수; LOCAL→COL, cols < worker 수 | allocator가 예외 발생. 빈 partition 지원을 가정하지 않음 | 해당 upload option 제거. 가능하면 REP 대안은 별도 검사 |
| FEDFout upload | anchor/registry를 해석하고 matching-shape anchor ranges를 보존 | W/canonical helper가 실제 runtime 입력이 되도록 경계 변경 필요 |
| LOCAL-left / ROW-right MM | sliced temporary broadcast 후 coordinator aggregation 경로 존재 | native LOCAL + native 내부 통신. 지속 REP 출력으로 오인 금지 |
| 축이 어긋난 aggregate unary | forced FOUT 경로는 partial GET→local aggregate→full broadcast PUT 가능; Oracle caps는 FED/LOUT를 보고 | 향후 단계 7의 lowering 지원·검증 후 native LOCAL + 명시 UPLOAD REP로 표현. 현재 anchor-free 경로가 있다는 뜻은 아님 |
| t(X) | native transpose는 output axis/ranges를 변환 | canonical range equality가 증명되는 경우만 ROW↔COL native 연산 허용; 실패 시 그 rule 제외. RESHARD와 구별 |
| scalar aggregate / metadata-only nrow,ncol 등 | payload 이동과 metadata 접근은 다름 | matrix REP나 원본 collect를 강제하지 않음 |
| TRead/TWrite 및 recompile upload | 기존 placement/rewriter/registry 제약 존재 | enum 제거만으로 지원 확장하지 않고 selected edge emission으로 검증 |

직접 근거: [indexing][index-runtime], [append][append-runtime], [rmempty][rmempty-runtime], [roll][roll-runtime], [materialization ranges][materialize-ranges], [upload][fout-runtime], [MM][mm-runtime], [aggregate unary runtime][aggregate-runtime], [aggregate Oracle][aggregate-oracle].

**중요한 범위 변화:** canonical source가 아닌 외부 federated 입력을 받아 놓고 canonical이라고 선언할 수 없다. 최초 source validation에서 worker 집합, endpoint 순서, ranges, replica 의미를 확인해야 한다. 거절하거나, 별도로 허용·구현된 import normalization 경계를 사용한다. Protected source를 canonical화하기 위해 COLLECT할 수 있다고 가정하지 않는다.

불일치 native output 뒤에 몰래 collect/reupload를 붙이는 방법도 현재 기본 계약으로 자동 허용되지 않는다. 그것은 비canonical 중간 값을 다룰 runtime action과 privacy/cost를 추가하는 별도 변경이다. 이번 설계는 그런 지원을 새로 가정하지 않는다.

### 10.1 비canonical source 처리와 의도적인 지원 범위 축소

**[제안] 초기 권고는 ingress에서 검사하고 명시적으로 거절하는 것이다.** W와 endpoint 순서·전체 range·replica 내용 계약을 만족한 source만 네 상태 중 하나로 Memo에 넣는다. Metadata label만 ROW/FULL이라고 해서 통과시키지 않는다. Source validation 실패와 runtime operator 미지원은 다른 reason으로 기록한다.

| 처리안 | 타당성 / 지원 범위 | 판단 |
|---|---|---|
| canonical source만 수용 | subset/pool 불일치, skewed ranges, W>1의 single FULL source를 제외 | 가장 작은 정확한 baseline. 기존 runtime에서 합법이던 입력 일부는 명시적으로 제외됨 |
| 별도 import normalization | 공개 가능한 source를 지원된 collect/upload로 canonical artifact로 바꾼 뒤 진입 | 향후 옵션. 실제 allocator, privacy, 비용, 데이터/metadata 검증 필요. 새 Memo의 암묵적 기능이 아님 |
| source의 실제 ranges를 네 상태 뒤에 계속 숨김 | 같은 shape/type에 여러 mapping이 남음 | canonical 전제를 깨고 Anchor 탐색을 되살리므로 비권고 |

원래 source가 W 밖의 worker나 subset에 있으면 import collect 자체가 “모든 FED는 W 전체” 계약과 충돌할 수 있다. 이를 허용하려면 명시적인 **planner 진입 전 import 범위**로 별도 정의해야 한다. Protected raw data는 collect를 통한 canonical화가 불가능할 수 있다. 이 문서는 그런 import가 이미 지원된다고 가정하지 않는다.

제외되는 기존 계획은 크게 네 종류다: (a) source/native output의 subset·다른 pool·비canonical range, (b) PART/OTHER 또는 W>1 single FULL을 persistent 상태로 사용하는 계획, (c) D1 금지 시 동일 값의 collect/reupload 재배치, (d) 초기 static join/body 정책보다 더 유연한 branch/iteration/call specialization. (a)(b)는 새 네 상태 계약, (c)는 아직 미확정 정책의 결과, (d)는 권고 baseline의 구현 범위이며 각각 구분해 migration report에 남긴다.

## 11. Enumeration과 pruning

**[제안]** 열거 단위는 Oracle가 지원하는 추상 input partition pattern, CP/FED mode, native output, 허용 movement option이다. Arity k의 행렬 입력에는 최악 4^k pattern이 있을 수 있지만 scalar/metadata 역할은 제외하고 operator-specific pattern generator나 기존 compact relation을 사용한다. k가 크면 compact pattern을 유지해도 된다.

열거하지 않는 것:

- worker subset/pool/range 조합
- anchor 또는 proof route
- 각 producer의 구체 realization Cartesian product
- source binding별 동일 Memo rule 복제
- 전체 HOP assignment의 사전 생성

실제 search 중 필요한 작은 공동 선택 조합은 검사해야 한다. “조합 사전 생성 금지”가 “global search 없이 정확 최적화 가능”이라는 뜻은 아니다.

Pruning 규칙:

1. Native 미지원/shape 불가능/확정 privacy 위반은 Memo 전에 제거한다.
2. 동일 producer 변수와 facts를 고려해 어떤 compatible extension도 없는 domain value는 제거할 수 있다.
3. Unknown 조건은 false로 처리하지 않는다. 모든 branch의 legality와 loop grounding을 단순 arc consistency가 대신하지 않는다.
4. **같은 nativeOutput/available partition에서 cheapest rule 하나만 남기지 않는다.** Required input, native communication, privacy guard, function body sharing, consumer movement/lifetime이 다를 수 있다.
5. Dominance는 모든 관련 boundary observation과 compatible extension이 같거나 포함되고 전체 비용이 더 나쁘지 않음을 증명할 때만 적용한다. 처음에는 완전히 같은 semantic rule 중복만 제거하는 편이 단순하다.
6. 메모리/시간 한계는 탐색 미완료 상태다. 자원 한계 때문에 legal candidate를 조용히 버리거나 완료된 Exact로 보고하지 않는다.

### 11.1 복잡도 표기와 O(NK+EM)의 정확한 범위

이 절의 복잡도는 **[추론/제안]**이며 실행 측정값이 아니다.

| 기호 | 의미 |
|---|---|
| N, R, K | occurrence 수, 전체 native rule 수 R=ΣKᵢ, occurrence당 최대 rule 수 K |
| E, aᵢ, L | 일반 ordered input edge 수, occurrence i의 arity, rule pattern 전체 길이 L=ΣKᵢaᵢ |
| M, Eᵦ | rule의 output 또는 binding당 최대 movement recipe 수, guard/value context를 구분한 실제 input binding 수 |
| F, I | 공유 표현을 포함한 Program Facts 크기, 변수→영향받는 edge/경계 predicate incidence 수 |
| U, H, S | 서로 다른 native semantic query 수, 분석이 방문하는 joint environment 수, 분석 slice 수 |
| Bcache | query cache 저장 상한. Entry 크기가 가변적이면 bytes 기준으로 관리 |
| V, dᵥ, Q | solver 변수 수, 각 domain 크기, 실제 방문한 search node 수 |
| w, d | elimination ordering의 induced width, solver 최대 domain 크기 |

Memo의 정직한 저장량은 **O(N+R+L+RM)**이다. M이 현재 계약처럼 상수이고 arity가 bounded이거나 rule들이 input schema를 공유하면 O(NK)가 된다. 긴 n-ary pattern을 rule마다 복사하면 L은 O(KE)이며 O(NK)라고 쓰면 안 된다. Input relation은 일반 DAG에서 O(EM)이지만 branch/call binding을 별도로 저장하면 **O(EᵦM+F)**다. Eᵦ를 원래 E로 슬쩍 바꾸지 않는다.

따라서 목표 O(NK+EM)은 **기본 native Memo + 일반 edge의 supply 표현**에 대한 목표다. 전체 planner에는 F, L의 비상수 부분, incidence I, 선택된 materialization, bounded cache와 solver trail이 추가된다. Scope/retention을 선택 변수로 확장하면 그 domain 저장량도 추가되며 이를 상수 M에 숨기지 않는다. Symbolic guard의 공유로 Eᵦ를 줄일 수 있지만 임의 프로그램의 path relation을 항상 선형 크기로 나타낼 수 있다는 보장은 없다. W descriptor는 한 번 저장하고 canonical ranges는 필요 시 계산한다. Source의 실제 map을 검사하는 비용은 실제 source partition 수에 비례하며, W=상수라는 이유로 검증 자체를 생략하지 않는다.

### 11.2 현재 생성·반복 비용과 새 단계별 비용

**[확인]** 생성기는 일부 determinant relation과 CP/FED family를 압축하지만 일반 execution region에서는 구체 input tuple을 순회한다. enumerateInputCombinations의 순회 stack은 arity 크기라도 leaf list와 callback이 보관하는 rule/fact 전체가 arity 공간인 것은 아니다. Physical Model은 일부 compact product를 유지하면서도 잔여 support/input-authority 조합을 Alternative로 만든 뒤 sortAndDeduplicateAlternatives를 수행한다. [생성기 열거][generator-product], [physical 전개][physical-alternatives]

| 단계 | 현재 확인된 작업 / 병목 | 제안 시간 | 제안 저장량 / 한계 |
|---|---|---|---|
| CFG/reaching definitions | analyzeCfg가 block별 집합을 전체 pass로 반복 | dependency worklist의 실제 transfer/union 횟수 합 | bitset이면 대략 O(CFG blocks × definition 수 / word bits), sparse이면 실제 사실 수. F에 포함 |
| 공동 input 환경 | slice별 structured 실행, loop header 전체 재실행, 환경/tuple 보관 | 실제 방문 환경 H와 각 transfer 비용의 합; delta 처리로 재방문 감소 | explicit 방식은 H에 비례하고 H는 지수적일 수 있음 |
| native Oracle | 준비 cache + 제한적 determinant relation + 일반 tuple 열거 | U개 unique query의 실행 비용 + lookup; eviction이면 재실행 추가. 행렬 arity a의 일반 상한은 4ᵃ patterns | O(R+L+Bcache), Bcache는 명시한 cache byte/entry 상한. source realization 수를 곱하지 않음 |
| privacy | 후보/closure 단계에 분산된 propagation·pruning | privacy lattice worklist + rule/recipe별 조건 계산 | 기존 fine-grained facts 크기와 조건식 크기 추가 |
| edge compatibility | physical bindings/support/factor 구축 | 이진 edge 전수 평가 시 O(Σedge Kproducer Kconsumer M); lazy이면 실제 query 수 | 모든 K² table을 저장하지 않음. support witness/소형 bounded cache만 |
| domain propagation | Closure에서 후보 재생성과 proof 재계산 결합 | 실제 revise 방문 × predicate 비용. 한 단조 propagation 구간의 삭제는 최대 전체 domain 값 수 | immutable Memo + active bitset + I + undo trail. Backtrack 후 다른 branch에서는 삭제가 다시 발생할 수 있음 |
| cost 준비 | Alternative/transfer descriptors와 cost factor | rule unit cost O(R), 선택된 supply grouping은 hash 기대 선형 또는 정렬 O(T log T) | T는 선택된 primitive materialization/demand 수. guard union 비용 별도 |
| 정확 탐색 | reduced categorical factors + elimination 또는 Local | B&B: O(Q × (propagation+bound+완료 검증 비용)); 최악 Q는 domain product 규모 | tables 없이 domain/trail, 현재·best plan, 제한된 cache; F/guard 처리는 별도 |
| reconstruction/emission | projector/receipt/transaction | 선택 rule·binding·action과 실제 emitted worker instruction 수에 비례 | 선택된 계획만. 원래 모든 후보의 physical clone 불필요 |

각 단계가 별도의 선형 시간이라는 주장은 하지 않는다. 예를 들어 compatibility domain이 줄 때 witness를 다시 찾으면 같은 pair를 재검사할 수 있고, 모든 경로 privacy를 판정하는 데 공동 환경 방문이 필요할 수 있다. Dependency worklist는 **불필요한 전체 재계산을 줄이는 방법**이며 다항시간을 보장하지 않는다.

### 11.3 Oracle cache: 결과 interning과 호출 회피를 구별

**[확인]** ExactRuleDecision.decide는 Oracle 결정을 먼저 실행한 후 결과를 intern한다. 이것은 같은 OpCaps 객체의 중복을 줄이지만 Oracle 호출을 피하는 cache는 아니다. PreparedProfile.inferWithEvidence는 input domains/shape snapshot을 key로 실제 추론 결과를 재사용한다. Prepared relation은 determinant product가 4,096보다 크면 다른 경로로 돌아가며, early FED feasibility도 제한된 probing 후 UNKNOWN을 반환할 수 있다. “Oracle는 이미 완전히 cache된다” 또는 “4,096이 합법 rule 수 제한이다”라고 해석하면 안 된다. [결정 interning][oracle-intern], [profile cache][oracle-profile], [determinant relation][oracle-determinant]

**[제안]** native cache는 source-free adapter의 입구에 둔다. 한 분석 snapshot의 runtime semantic revision, 설정, W/canonical 정책 revision은 cache 소유자가 고정한다. Entry key에는 opcode/signature, 실제 관찰하는 type/shape/literal/parameter, abstract ordered partition pattern 또는 검증된 determinant만 넣는다. Unknown dimension은 알려진 dimension과 구분한다. Shape가 guard별로 다르면 각 shape case를 검사하거나 모든 case에 안전한 rule만 남긴다.

| cache | 재사용 단위 | 무효화 / 주의 |
|---|---|---|
| Oracle native result | 동일 순수 semantic key → admitted native outcomes + scope rejection | shape/type/parameter/config/runtime/canonical revision 변경. source나 privacy를 읽는 기존 API는 먼저 분리해야 함 |
| immutable native rule schema | 같은 semantic rule과 ordered input schema | occurrence별 privacy/guard 조건은 별도. 목록 ordinal을 공통 rule ID로 쓰지 않음 |
| privacy 조건 | 정확한 value-version facts와 native variant/recipe | facts revision 또는 compile epoch 변경. privacy-filtered 결과를 native cache에 넣지 않음 |
| pair compatibility | semantic rule IDs, recipe, edge role, 고정 facts revision | domain과 무관한 결과만 영속 cache. domain support 실패는 현재 search revision에 종속 |
| native/movement 단가 | 정확한 shape/sparsity/type, runtime variant, W, cost configuration | activation 빈도·guard union·lifetime은 단가 key에 숨기지 않고 선택 시 계산 |
| guard/availability query | 기존 facts의 node IDs와 use/context | alias/value/compile epoch 변경. runtime data predicate의 truth를 비용으로 고정하지 않음 |

고정 크기 또는 byte budget의 cache는 eviction 시 재계산만 일으켜야 하며 합법성에 영향을 주면 안 된다. 부정 결과도 UNKNOWN과 IMPOSSIBLE을 분리한다. 기존 PreparedProfile cache는 snapshot domain 조합을 보관하므로 새 immutable pattern cache와 동일한 공간 상한으로 가정하지 않는다. OracleFacade.exploreAll에는 eager Cartesian 경로가 있지만 현재 main/test에서 정의 외 호출을 찾지 못했으므로 실제 hot path라고 단정하지 않는다.

### 11.4 애초에 만들지 않을 객체와 symbolic input relation

**[제안]** Java record 예시는 이해를 위한 인터페이스다. 실제 저장은 occurrence/rule/edge/binding을 dense int ID로 intern하고, offsets+flat arrays 또는 작은 immutable list를 사용한다. 이름·scope 문자열을 매 rule에 복사하지 않는다. W/ranges는 한 descriptor와 shape 참조로 공유한다. Memo는 freeze한 뒤 수정하지 않고 optimizer가 별도 active-domain bitset을 갖는다.

- Abstract pattern만 만들고 exact source는 edge의 facts-owned binding에서 해석한다. 같은 pattern을 source별 CandidateRuleFact로 복제하지 않는다.
- availableOutputs는 상수 크기 recipe/condition 참조다. 공급 가능성을 실제 복사본 객체로 materialize하지 않는다.
- Proof route, SupportClause, CandidateRealization, Physical Alternative를 새로운 이름의 동형 객체로 옮기지 않는다. 완료된 선택에서만 primitive action과 semantic receipt를 만든다.
- Guarded definitions는 기존 branch node/region을 공유하는 구조로 유지한다. 예를 들어 X=ite(g,A,C), Y=ite(g,B,D)는 동일 g를 참조하므로 (A,D)를 만들지 않는다. 이것을 다시 네 tuple로 확장할 필요가 없다.
- Oracle pattern mask도 독립성이 입증될 때만 사용한다. “적어도 한 입력은 FED”, 동일 axis, 특정 operand 간 shape 관계는 mask들의 단순 product로 바꾸지 않고 기존 operator predicate로 유지한다.

새 BDD/SMT/범용 relation DSL을 도입할 필요는 없다. 기존 structured branch/loop/call facts와 필요한 작은 joint relation을 그대로 조회한다. 임의의 복잡한 guard 동치나 data predicate의 논리적 불가능성까지 새 optimizer가 증명하지 않는다.

**현재와의 차이 [확인/제안]:** PlacementJointInputAnalysis는 이미 correlation을 보존하지만 일반적인 compact guard DAG만 저장하는 구현은 아니다. 같은 slice consumer들을 batch하고 함수 입력 환경을 cache하며, 16,384 environment 제한에서 실패한다. Loop에서는 누적 header 환경을 반복 처리한다. 이 분석을 재사용하는 첫 단계의 F는 이미 클 수 있다. 이후 facts 생산자에서 shared guarded binding과 새 환경 delta를 직접 만들도록 바꿔야 “조합을 만들지 않음”이 분석 단계에도 적용된다. 기존 explicit 환경을 먼저 모두 만들고 압축하는 것으로 목표를 달성했다고 보고하면 안 된다. [joint query/batching][joint-query], [joint loop][joint-loop], [환경 제한][joint-limit]

서로 독립적인 g개 branch의 결과를 모두 관찰하는 consumer가 있다면 구별되는 환경은 2ᵍ일 수 있다. Symbolic 표현은 저장을 줄일 수 있어도 해당 operator/constraint의 진리표 자체가 복잡하면 검증 시간이 지수적일 수 있다. 기존 분석처럼 predicate 식의 의미를 모르면 두 arm을 유지하되, 같은 **동적 guard occurrence**에 속한 definitions의 correlation은 보존한다.

### 11.5 후보 재생성 없는 worklist와 필요한 fixed point

**[확인]** ProgramFacts.analyzeCfg는 reaching-definition 집합을 반복 갱신하고, JointInputAnalysis.loopFixedPoint는 header 환경이 안정될 때까지 본문을 실행한다. Closure에는 privacy worklist뿐 아니라 placement/feasibility pass, function boundary closure, CFG replay와 후보 재생성, 별도 loop proof fixed point가 있다. 반복이라는 이유만으로 모두 같은 목적이거나 불필요한 것은 아니다. [CFG][cfg], [joint loop][joint-loop], [privacy worklist][privacy-worklist], [placement closure][closure-pass]

**[제안]** 두 시기를 분리한다.

1. **Facts 구축:** definition/guard/shape/privacy/alias의 단조 데이터흐름 분석. 변경된 block·SCC·callee summary의 dependents만 queue에 넣는다. Loop/call summary의 재방문은 인정한다. Recursion은 초기 지원 제외다.
2. **Memo freeze 이후:** native domain universe는 고정한다. 현재 선택과 support가 불가능해진 값만 active domain에서 지운다. 이 변화로 Oracle/CandidateGenerator를 다시 호출하지 않는다.

~~~text
propagate(changedVariables, domains, trail):
    queue = affectedInputEdgesAndBoundaryRelations(changedVariables, facts)
    while queue not empty:
        relation = queue.popDeduplicated()
        for variable in relation.relevantVariables:
            for value needingRecheck(variable, relation):
                support = findCompatibleExtensionLazily(relation, variable, value, domains)
                if support == PROVEN_ABSENT:
                    trail.remove(variable, value)
                    if domains[variable].empty and requiredByActiveContext(variable):
                        return INFEASIBLE_IN_DECLARED_MODEL
                    queue.addAll(affectedRelations(variable))
                // UNKNOWN은 유지. Joint legality는 완료 선택에서도 검사한다.
    return CONSISTENT_SO_FAR
~~~

이진 edge의 마지막 support witness는 작은 cache로 남길 수 있다. Witness가 domain에서 제거된 경우에만 재탐색한다. 임의 다변수 relation의 exact support search가 비싸면 sound한 약한 pruning을 하고 complete check로 넘긴다. Arc consistency가 global feasibility를 증명하는 것은 아니다.

PROVEN_ABSENT는 closed facts와 prospective loop hypothesis를 허용한 모든 해당 relation completion의 부재다. 환경별로 서로 다른 shared R witness를 찾은 결과는 약한 유지 근거일 뿐 공통 support 증거가 아니다. Same compiled R과 guarded S를 먼저 고정하고 모든 활성 환경의 legality를 요구한다. 상세한 양방향 queue와 단계별 제거 조건은 [후속 검증 §3–5](FEDPLANNER_EARLIEST_SAFE_PRUNING_2026-10-13_KO.md)를 따른다.

Search backtrack 때 domain과 activation 변경을 trail로 복구한다. 특정 domain revision에서의 “support 없음”을 다른 branch에 재사용하면 legal plan을 삭제할 수 있다. Pure pair incompatibility만 revision과 무관하고, domain-sensitive witness/negative cache는 revision 검증 또는 undo가 필요하다.

Loop placement는 단순히 bottom에서 source reachability만 전파해서 해결하지 않는다. Loop invariant의 **가능 domain**은 넓게 시작해 entry 및 모든 backedge 제약으로 줄일 수 있다. 최종적으로 entry의 실제 base case와 selected body의 inductive preservation을 따로 증명한다. 한 seed가 SCC에 닿는다는 검사만으로 cycle의 모든 값이 존재하는 것은 아니다. 이 작은 loop contract 검사가 현재 provisional greatest fixed point와 grounded least fixed point의 서로 다른 의미를 대체한다. 후보 객체를 매 round 다시 만들 필요는 없다.

Delta 환경 분석에서는 definition map뿐 아니라 read-source provenance와 loop epoch도 key에 포함해야 한다. 같은 definition 집합이라는 이유로 필요한 read observation을 생략하면 correlation이 사라진다. 반복이 유한한 추상 domain 위에서 수렴하도록 하고, facts/resource 제한 초과를 임의 Cartesian approximation이나 합법 판정으로 바꾸지 않는다.

### 11.6 안전한 pruning과 HOP별 DP가 정확한 조건

Rule r을 s로 대체해 지우려면 적어도 다음을 증명해야 한다.

~~~text
every legal completion using r
    maps to a legal completion using s
with the same externally observed value/guard/lifetime behavior
and totalCost(mapped completion) <= totalCost(original completion)
~~~

여기서 completion은 producer 선택, 모든 consumer supply, 공유 body, materialization 및 activation을 포함한다. Native output partition이 같거나 r의 단독 compute가 비싸다는 사실로는 충분하지 않다. 초기 구현에서는 동일 semantic effect/조건/입력 요구/비용의 중복 rule만 intern하는 것이 가장 설명하기 쉽다.

**[확인]** CostBasedPruningTest에는 단독 비용 4,6과 연결된 shared 비용 100,1이 등장한다. 독립 cheapest는 4를 남기지만 합법적인 공동 비용은 104 대 7이므로 6을 버리면 최적해가 사라진다. ExactPhysicalReducedSolver의 quotient도 같은 partition이 아니라 모든 incident factor observation의 동일성을 사용한다. 새 optimizer에 그 비용 큰 비교 절차 전체를 이식할 필요는 없지만, 기존 reduction이 보장하던 구분을 cheapest pruning으로 약화해서는 안 된다. [pruning 반례][pruning-test], [exact quotient][quotient]

HOP별 tree DP는 다음 조건에서 정확하다: producer가 한 consumer에만 사용되거나 subtree들이 완전히 독립적이고, 비용이 subtree+edge로 분리되며, branch/loop/function/shared materialization의 공동 제약이 없고, DP state가 상위에서 관찰하는 모든 정보를 포함한다. Partition만으로 충분한 단순 tree에서는 다음과 같이 source product 없이 계산할 수 있다.

~~~text
DP[o, requiredOutput] = min over native rules r and legal final recipe q:
    nativeCost(o,r) + finalMovementCost(o,q)
    + sum over input positions i:
        DP[input(i), r.requiredPartition(i)]
~~~

이때 input pattern의 ordered requirement가 고정되어 있고 child 간 비용/합법성 coupling이 없어야 한다. 동일 child를 두 번 소비하거나 같은 movement를 공유하면 위 합이 중복 과금하거나 서로 모순된 child rule을 고른다. DAG에서는 경계에 걸친 **공유 선택과 아직 활성화가 끝나지 않은 materialization**을 joint state에 넣어야 한다. 이는 subtree당 layout 하나의 DP가 아니라 separator DP이며 그 state 수는 커질 수 있다. branch correlations와 공유 compiled function owner도 separator에 포함된다.

### 11.7 권장 optimizer 하나: lazy predicate exact branch-and-bound

**[제안]** §6.2의 탐색을 새 기본 optimizer로 권고한다. Fixed boundary contracts와 고정된 합법 creation-scope 정책 아래 R, S, 필요한 P만 탐색한다. 같은 owner R은 한 변수이고 guard는 facts다. Sharing은 선택 supply에서 유도하므로 모든 consumer subset을 domain에 미리 생성하지 않는다.

1. Domain이 작고 많은 edge와 연결된 미결정 변수를 먼저 고른다. 선택 순서는 성능만 바꾸고 합법해를 버리지 않는다.
2. Incident edge와 관련된 branch/loop/function predicate만 갱신한다. Compact int incidence list는 새 범용 Constraint Graph가 아니라 기존 facts의 역색인이다.
3. 초기 feasible incumbent은 같은 checker로 검증된 완전 계획만 허용한다. Existing Local의 결과를 이용하려면 새 scope/semantic rule에 정확히 대응되어야 한다.
4. Complete assignment에서 selected primitive actions를 생성하고 availability·privacy·boundary lowering·loop induction을 독립 검증한다. 비용은 그 action schedule을 기준으로 계산한다.
5. 탐색 완료 시 모델 내 optimum, 자원 중단 시 incumbent와 미완료 상태를 반환한다. Infeasible은 전체 search가 끝나거나 sound한 모순 증명이 있을 때만 반환한다.

가장 단순한 sound lower bound는 **각 실행 context x에서 shared owner의 남은 R domain에 대한 native cost 최솟값의 가중 합 + 이미 확정된 비중복 비용**이다. 미결정 shared movement 비용은 0으로 둔다. 모든 비용과 빈도 가중치가 비음수라는 계약이 필요하다. 같은 compiled body R을 공유해도 실제 invocation별 실행 비용은 각각 shape와 빈도에 따라 계산한다. Bound에서 context별 최소를 따로 취하는 것은 공통 R equality를 완화하므로 안전한 하한이다. 완성 계획의 비용은 반드시 실제 공유 R을 사용한다. Owner마다 한 번만 과금하면 함수 실행 비용을 과소 계산한다.

“이미 확정된 비용”은 후속 assignment가 sharing/activation/creation site를 바꿔 낮출 수 없는 항만 뜻한다. 이를 증명하기 어렵다면 bound에서는 movement 전체를 0으로 두는 편이 안전하다. 현재 consumer별 이동 비용 합이나 현재 임시 grouping 비용을 bound에 더하면 나중에 공유할 수 있는 최적해를 잘라낼 수 있다. Guard별 movement 비용도 미지의 overlap을 가정해 더하지 않는다.

IEEE double의 반올림 오차로 진짜 optimum을 잘라내지 않도록 certified lower-bound 산술 또는 검증된 downward bound를 사용한다. 임의 epsilon은 증명이 아니다. 현재 ExactDyadicCosts의 비음수 dyadic certificate 원리를 재사용하거나, 검증 전에는 비용 bound pruning을 끄고 합법성 pruning만 수행한다. 결과의 optimality는 추정 runtime이 아니라 **정의된 cost model**에 대한 것이다. [수치 certificate][dyadic-cost]

초기 baseline은 runtime이 이미 지원하는 creation/cache scope와 emission 순서를 고정한다. 이 범위 안에서도 여러 비용상 구별되는 retention/grouping이 가능하면 §6.2의 계약대로 S의 선택에 노출한다. 필요한 항목은 edge recipe의 작은 scope/retention selector이며 전체 consumer subset을 사전 생성하지 않는다. 다른 scope로의 hoisting까지 최적화하려면 실제 lowering 가능한 유한 scope 선택지를 추가해야 하며, 그렇지 않으면 그 계획들은 최적성 범위 밖이라고 명시한다. 정해진 R/S에서 비용이 최소라는 주장과 가능한 모든 scheduling/cache 정책까지 최적이라는 주장을 구분한다.

DFS는 전체 assignment 목록을 저장하지 않는다. 하나의 경로에서 각 domain 삭제를 한 번 trail에 기록하고 복원하면 기본 search 공간은 O(Σdᵥ+I+선택 action 수)에 bounded cache를 더한 수준으로 관리할 수 있다. 다만 facts/guard 계산이 쓰는 별도 메모리는 남는다. No-good 전체 저장, 무제한 partial-assignment memoization은 baseline에 넣지 않는다. 최악 시간은 Πdᵥ 규모의 탐색에 predicate 계산을 곱한 지수적 값이다.

### 11.8 기존 Exact/Local 재사용과 induced-width 한계

**[확인]** ExactCategoricalSolver의 generic lazy Factor는 freezeGenericFactor에서 domain tuple을 순회하며 double table로 바뀔 수 있다. 일부 conditional/finite support/hard relation은 별도 sparse 표현을 유지하지만, “callback이므로 끝까지 lazy”는 성립하지 않는다. Elimination에서는 separator domain product, sparse join 크기, backpointer가 메모리를 결정한다. 기존 Statistics에는 inducedWidth, maxFactorCells, materializedCells와 elimination assignment 수가 있다. PlannerResourceGuard의 allocation 검사는 새로운 configurable planning budget과 동일하지 않다. [freeze][factor-freeze], [elimination][solver-eliminate], [solver 통계][solver-statistics], [allocation guard][resource-guard]

균일 domain d와 최대 width w인 일반 dense elimination은 대략 시간 O(V d^(w+1)), 모든 message/backpointer 보관 시 공간 O(V d^w)를 요구한다. 한 bucket의 순간 message도 d^w 규모다. 실제 비용은 비균일 separator의 domain product와 초기 factor 크기로 계산해야 하며 높은 arity의 초기 factor는 이미 클 수 있다. Sparse/conditional factor는 특정 문제에서 줄여 주지만 일반적인 width 문제를 없애지는 않는다.

따라서 새 기본 optimizer에 기존 Physical Model→generic lazy factors→freeze를 연결하면 애초에 피하려던 조합 materialization을 재도입할 수 있다. 기존 Exact는 **작은 공통-domain fixture의 reference와 수치/비용 utility**로 재사용한다. Local은 incumbent 탐색 원리를 재사용할 수 있지만 독립적인 최적성 근거가 아니다. 둘의 Physical Alternative builder를 새 Memo 기본 경로에 유지하지 않는다.

이후 실제 병목이 확인된 경우에만, shared owner/guard/materialization 의존이 끊긴 component 또는 작은 separator에서 exact DP를 적용한다. Component 분리는 HOP edge만 끊어 보고 판단하지 않는다. 이 선택적 최적화의 table budget을 넘으면 직접 탐색으로 돌아갈 수 있으며, domain을 잘라서 budget을 맞추지 않는다. Baseline은 §11.7의 하나의 direct optimizer다.

### 11.9 측정 설계: 생성량·실제 작업·메모리를 구분

**[확인]** SearchSpaceMetrics는 stage별 wall/CPU/current-thread allocated bytes, Oracle/relation/profile counters, logical/explicit support 수를 이미 제공한다. Factorized support 테스트는 100×100 logical members를 200 options와 2 groups로 보관하고 materialized clause 0을 확인한다. 이는 특정 representation fixture의 증거이며 peak memory나 전체 candidate generation 제거의 증거는 아니다. [metrics 구현][metrics], [cache counters][metrics-oracle], [압축 fixture][factorized-storage]

| 지표 | 권장 정의와 위치 |
|---|---|
| Oracle calls/cache | native adapter 요청·lookup hit·실제 Oracle 실행·불가능/UNKNOWN 결과 각각. 결과 interning hit는 별도 |
| Rule 수 | 시도한 abstract patterns, admitted native rules, privacy 탈락, 최종 unique rules, occurrence 참조 수 |
| materialized combination | 실제 생성한 exact input tuple / joint environment / support clause / physical alternative / solver table cell을 서로 다른 counter로 기록 |
| compatibility | query, pure-cache hit, 실제 pair evaluation, joint predicate evaluation, support witness 재탐색, pruning 이유 |
| worklist | enqueue 시도/중복 제거/실제 pop, facts transfer, domain deletion, loop SCC round; candidate rebuild는 새 lane에서 0 |
| cost/solver | native unit lookup, demand 수/공유 action 수, guard union 계산, factor callback 평가, search node·bound prune·incumbent·완료 상태 |
| intermediate 크기 | 실제 encoded cells와 logical domain product 분리; max separator product/width, 실제 dense/sparse 할당, cache live entries/bytes |
| 시간 | source/facts/native/privacy/Memo/propagation/cost/search/emission의 exclusive wall time와 전체 시간. Inclusive nested 합을 total로 더하지 않음 |
| memory | planner thread 누적 allocation, retained heap, JVM heap peak, coordinator RSS high-water, whole-container peak를 각각 표시 |

Timer와 counter 자체의 비용도 측정 조건에 포함한다. PlacementAnalysis timing, CandidateFormationTiming, PlannerPipelineTiming을 재사용할 수 있다. FederatedPlannerTrace의 trace-print 시간 차감은 formatting/validation까지 모두 제거한 순수 optimizer 시간이라는 뜻이 아니다. Local/Exact의 서로 다른 timing scope도 같은 표에서 그대로 비교하지 않는다. [candidate timing][candidate-timing], [pipeline timing][pipeline-timing], [trace timing][trace-timing]

향후 benchmark는 같은 source/JAR hash, Docker image, worker 수·데이터·network, CPU/memory 한도, JVM Xmx/Xms/processor 설정으로 수행한다. Cold snapshot과 warm same-snapshot native cache를 분리하고 반복별 phase raw time, median/분산, 최종 scope/선택/비용을 보관한다. Oracle cache warming 때문에 달라진 native 호출 수와 실제 실행 계획 변화를 구분한다.

Peak는 fresh process/container에서 측정한다. Existing Docker collector를 참고해 cgroup memory.peak/current/events와 coordinator VmHWM/RSS/Swap을 수집하고 worker memory는 별도로 기록한다. Phase별 heap은 MemoryPoolMXBean peak reset 또는 JFR 등 명시적 계측으로 추적할 수 있다. 짧은 RSS sampling은 보조 관측이며 실제 peak를 놓칠 수 있다. “종료 heap−시작 heap”, 누적 allocated bytes, cgroup peak를 동일한 planner peak라고 보고하면 안 된다. [기존 Docker memory 수집][memory-collector]

아직 새 구조를 구현하지 않았으므로 속도·peak memory 개선율이나 목표 달성치를 제시할 근거는 없다. 우선 증명할 구조적 지표는 source realization 수가 늘어도 native rule 수가 늘지 않는 것, stable facts에서 Oracle 재실행/candidate rebuild가 없는 것, final selection 전 새 Physical Alternative가 생성되지 않는 것이다.

### 11.10 효율성 변경의 semantic/cost 검증 기준

**[제안]** 기존 테스트의 객체 identity assertion은 해당 구현의 회귀 검증에 유지하되, 새 표현 간 비교는 정확한 값/조건/선택/비용으로 한다. 동일 Java object를 만들지 않는 것이 목표인 변경에 과거 receipt identity를 새 correctness 정의로 강제하지 않는다.

| 변경 | 최소 검증 |
|---|---|
| 순수 native cache | source identity만 바뀌면 hit, shape/parameter/W/config/epoch 변경 시 적절한 분리. 같은 native result에 서로 다른 privacy filter 결과를 검증 |
| factorized pattern | 허용 pattern 전수 비교, equality/at-least-one-FED의 hole 보존. source realization 수만 늘린 fixture에서 native rule 수 불변 |
| symbolic facts | branch correlated pair, missing else, warm/cold read provenance, loop entry/모든 backedge, function actual/return slot. 새 explicit environment 생성량도 측정 |
| incremental worklist | full recompute reference와 domain/조건 동일, 관련 없는 edge 방문 억제, backtrack 후 negative support cache 복원, Oracle/candidate rebuild count 0 |
| direct exact search | tiny random domain의 brute-force reference와 feasible assignments/최저 비용 비교. 각 선택 rule/공급과 final checker 결과 일치 |
| cost/bound | shared collect, D1 중간 collect, branch demand union, loop dynamic value, shared body 호출 빈도, FP 경계. 모든 partial bound ≤ 그 subtree의 실제 최소 complete cost |
| canonical schedule | 같은 R/S에서 숨은 비용상 다른 schedule이 없는지 검사. Creation/cache/retention/grouping 대안은 선택에 노출하거나 정책 제외로 기록하고 작은 전체 lowering reference와 비교 |
| memory 회피 | high-arity/높은 source multiplicity/high-width fixture에서 rule/tuple/table별 실제 생성 수와 peak를 분리. 큰 검색은 resource-incomplete로 끝나도 exact 완료로 보고하지 않음 |
| emission | 선택 공급이 실제 instruction/값 버전/worker/range/생성·해제 scope와 동일. 빠진 action을 runtime fallback이 보정하지 않음 |

기존 JointInputReachingDefinitionsTest는 같은 slice batching과 loop/call provenance를 검증한다. ExactConditionalNativeProductPhysicalParityTest는 작은 fixture의 ordered domain/factor cost parity에 유용하다. ExactFactorFreezeOrderTest는 generic callback이 tuple 전체를 방문할 수 있음을 보여 준다. 이 assertion들을 읽은 사실과 이번 작업에서 실행했다는 주장은 구분한다. [joint 회귀][joint-regression], [physical parity][parity-test], [freeze 회귀][freeze-test]

## 12. 구현·검증 순서

이번 작업에서는 아래 코드를 구현하거나 테스트를 추가하지 않았다. 향후 실행할 리팩터링 계획이다. 매 단계의 종료 조건을 만족한 뒤 다음 표현을 제거한다.

| 단계 | 구현 범위 | 검증과 종료 조건 |
|---|---|---|
| 0. 계약 고정 | W 순서, canonical 함수, REP lowering, D1 우회, join/function/loop policy 명문화 | 지원/제외 사례 목록과 reason이 확정됨. 현재 runtime 지원과 새 정책 제한을 구분 |
| 1. 현 동작 기준선 | 기존 미커밋 sparse 변경 포함 snapshot/semantic fixtures 보존 | 어떤 구현과 비교하는지 고정. source·candidate·factor·selected/emitted 결과를 분리해 기록 |
| 2. Oracle adapter | 현 Oracle를 감싼 source-free nativeRules, canonicality 판정, 순수 결과 cache | admitted/rejected effect가 runtime fixture와 일치. Native cache miss와 결과 interning을 구분 |
| 3. Privacy Filter + Memo 병행 | 기존 pipeline 옆에서 immutable Memo와 compact IDs 생성 | protected input/release/조건부 환경 비교. source multiplicity 증가에도 native rule 수 불변 |
| 4. Facts 경계 정리 | 기존 joint/CFG/call/loop 조회와 dependency worklist; 이후 symbolic producer로 전환 | correlation/version/zero iteration/call 반례 통과. full-recompute와 같고 불필요한 candidate rebuild 0 |
| 5. 작은 정확 optimizer | R/S/boundary의 직접 B&B와 independent legality checker | 작은 exhaustive reference와 feasible set·최저 비용 동일. backtrack/하한의 soundness 별도 검증 |
| 6. Cost adapter | native stage, movement grouping, lifetime/frequency 연결 | 모든 작은 assignment의 cost contribution 비교. compute/GET/PUT 중복·누락 없음 |
| 7. Selected-plan emission | 의미 기반 selection→기존 runtime lowering, fixed W allocator 연결 | 선택한 operand/version/actions와 runtime audit 일치. 비canonical output을 감추는 보정 없음 |
| 8. Full control-flow 검증 | shared function body, branch exit, loop induction, transient, recompile | 아래 반례 matrix와 privacy/lifetime 재검증 통과 |
| 9. 중복 계층 제거 | 새 lane에서 Anchor/Proof/SupportClause/Closure candidate rebuild/Physical expansion 제거 | old/new 공통 지원 범위에서 legality·비용·숫자 결과 동등. 제외 범위 별도 승인된 계약에 명시 |
| 10. 성능 평가 | §11.9 계측 후 입증된 병목에만 작은 separator/순서 최적화 | correctness 뒤 측정. 저장소 규칙대로 run_LAN_docker.sh, 동일 Docker 조건; 미완료 탐색과 완료 optimum 구분 |

단계 3까지는 선택·emission을 바꾸지 않는 read-only 비교 경로를 만들 수 있다. 단계 7의 runtime 변경 전에는 “anchor-free LOCAL→FED upload를 이미 지원한다”고 주장하지 않는다. 새 canonical 범위로 제한한 old/new 비교와 현재 전체 candidate-space parity를 혼동하지 않는다.

### 12.1 최소 검증 matrix

| 사례 | 반드시 잡아야 할 반례 | 현재 참고 테스트/근거 |
|---|---|---|
| shared A→B,C | 서로 다른 A native 선택; collect 이중 과금; 동명 다른 value 공유 | ExactDerivedSupplySharingTest, ExactPhysicalModel correlated factor |
| branch | (A,D) 가짜 조합; 한 branch만 합법; missing else; then DIRECT/else UPLOAD를 같은 edge에 연결 | PlacementJointInputEnvironmentTest, BranchExitPlacementNormalizationTest |
| loop | 자기 지지 cycle; entry 없는 FED; incompatible backedge; 0회; 반복 값 cache 재사용 | LoopIdentityBackedgeClosureTest, PhysicalSinglePartitionProofFixedPointTest |
| function | P/Q 바뀜; return slot 바뀜; shared body에 상충 plan; stale epoch | FunctionBoundaryOccurrenceIdentityTest, FunctionBoundaryRuntimeAliasContractTest |
| transient | 미래 writer 사용; 덮어쓰기 전후 value 합침; wrong input position | ExactTransientRealizationTest, JointInputReachingDefinitionsTest |
| privacy | raw collect 후 CP aggregate; PRIVATE output LOCAL; metadata 접근 과도 차단 | CandidatePrivacyInputPruningTest, SharedPrivacyMovementLegalityAuditTest |
| native runtime | slicing worker subset; rbind duplicate range; rmempty skew; roll split | 해당 runtime method + 새 canonical fixture 필요 |
| REP | W=1 정규화, W>1 FULL 오분류, replica 중복 aggregate/collect 비용 | FEDLocalMaterializeUtil, ExactNativeLocalAnchorFanoutCostTest |
| movement | D1 off에서 alias 경유 우회; D1 on에서 중간 LOCAL 이중 과금 | 새 정책-specific fixture 필요 |
| authority 삭제 | sparse hole, withdrawal/re-add, input binding 교체, stale selection | 이번 미커밋 sparse/parity 테스트 |

검증 수준은 분리한다.

1. Pattern/guard/solver 단위 검증: 작은 domain에서 exhaustive reference.
2. Parsed DML 분석: 실제 HOP, branch, call, transient 변환 이후 facts 확인.
3. Emission/runtime 통합: 실제 instruction, registry, materialization lifetime, 숫자 결과와 privacy.
4. 이후 필요한 lint/typecheck/build/static checks 및 동일 Docker workload 비교.

소스 assertion을 읽은 것, 과거 로그에 PASS가 있는 것, 이번 소스에서 테스트를 실행한 것은 서로 다른 증거다. 이번 문서에서는 앞의 첫 종류만 새로운 근거로 사용했다.

### 12.2 비교 기준

- 의미 동등성: 정확한 값·입력 위치·rule·공급·guard·native result·privacy·materialization lifetime.
- 비용 동등성: 동일 fixture의 각 contribution과 total. 단가/정책을 바꾸면 같은 optimal assignment를 무조건 기대하지 않는다.
- 최적성: 소규모 exhaustive min과 동일; 큰 문제는 Exact 완료 증거 또는 명시적 gap.
- 표현 계약: 과거 clause Java identity/fingerprint는 구조 변경 시 달라질 수 있다. 이를 수치·계획 의미 동등성의 대체 지표로 쓰지 않는다.
- 성능: Memo rule 수, physical 전개 수, analysis/model/cost/search 시간과 peak memory를 따로 측정.

## 13. 위험, 미검증 가정, 최종 설계 결정

| 위험/미검증 사항 | 처리 원칙 |
|---|---|
| 고정 W/canonical이 source 또는 native output에서 깨짐 | ingress와 native rule boundary에서 검사. contract 밖임을 명시 |
| REP를 FULL의 이름 변경으로 처리 | 모든 W의 완전한 동일 값 검증, runtime lowering/가격 구분 |
| partition만 같으면 input value가 맞다고 판단 | occurrence + version + input position을 facts에서 resolve |
| branch probability 0 추정으로 경로 삭제 | 상수/도달불가능 증거 없이는 legality 검사 유지 |
| loop seed를 실행 가능한 source로 오인 | entry base case + 모든 backedge induction + ordered supply 검사 |
| function call context별 R이 실제 compiled body와 불일치 | shared emitted owner equality 또는 실제 specialization |
| alias/loop/function cache의 과도한 공유 | dynamic creation scope와 lifetime이 맞을 때만 reuse |
| privacy를 output enum 하나로 요약 | 정확한 입력 privacy와 native 내부 데이터 이동까지 검사 |
| native 통신을 movement와 중복 계산 | 각 request/result stage의 비용 소유자를 명시 |
| 새 Memo가 작으니 search도 항상 작을 것이라고 기대 | shared DAG의 separator/branch joint 복잡도는 별도 측정 |
| support/proof를 먼저 삭제 | selected-plan checker/emission 전환과 반례 검증을 먼저 완료 |
| 기존 지원 계획이 canonical/static-boundary 정책으로 사라짐 | correctness 버그와 의도적 지원 범위 축소를 구분해 기록 |

**최종 권고 [제안]:**

Memo는 occurrence별 native feasible rules와 조건부 supply options만 보관한다. **Immutable Memo + 기존 facts의 guarded binding + dependency worklist + lazy predicate exact branch-and-bound**를 하나의 기본 경로로 권고한다. Optimizer는 HOP edge와 Program Facts를 직접 읽고 같은 compile epoch의 한 producer/compiled body owner에 한 semantic rule을 선택하며, edge의 guarded binding별 supply와 공유 materialization을 공동 최적화한다. Branch는 공동 guard, loop는 entry+귀납 조건, function은 epoch별 shared compiled plan+callsite binding, transient는 정확한 value version으로 처리한다.

초기 지원 범위는 canonical source만, 정적 boundary/body 계약, 검증된 creation/cache scope로 제한한다. D1 우회는 아직 사용자 확정 사항이 아니며 초기 금지를 권고한다. 이 범위 안에서도 **표현 completeness, native variant/출력 coverage, pruning soundness, schedule 및 emission 대응이 검증되어야** global optimum을 보존한다고 주장할 수 있다. 보수적 guard 모델과 추가 policy 때문에 canonical 전체와 같지는 않다. 범위 밖의 기존 legal plan은 제외 이유를 기록한다. 기존 Exact는 작은 reference로 사용하며 다른 범용 Constraint Graph/solver 계층을 새 기본 경로에 추가하지 않는다.

Anchor/Proof/SupportClause라는 객체 계층과 전역 후보 재생성은 제거 대상이다. 그 안에 들어 있던 **정확한 값·실제 존재 시점·privacy·공동 선택·runtime 실행 가능성**은 작은 facts와 선택 검증 함수로 이동한다. 성능 개선 여부는 이 설계의 correctness와 지원 범위를 먼저 확인한 뒤 측정한다.

## 14. 이번 작업의 검증 기록

- HEAD와 기존 tracked/untracked 변경을 확인했다.
- src 아래 8,032개 tracked 파일의 SHA-256 snapshot을 최초 작성 및 후속 보완 후 비교했다. 변경·삭제 0개이며 신규 untracked source 파일도 0개다. HEAD 및 기존 source/test patch SHA-256도 유지됐다.
- 최초 문서의 소스 참조 62개에 후속 29개를 추가하여 총 91개 소스/스크립트 참조의 경로·행 위치, Markdown 참조, code fence, whitespace를 검사했다. git diff --check도 통과했다.
- 독립 읽기 전용 검토를 반영하여 guard별 supply, compiled owner/semantic rule ID, 복합 movement의 중간 action, alias provenance, activation/lifetime, canonical 제외 audit, transient lowering gate를 보완했다. 당시 검토한 사례에서 추가 모순을 찾지 못한 것이며 전체 표현 completeness를 증명한 것은 아니다. 후속 검증에서는 multi-output owner와 activation 조건을 추가 보완했다.
- 후속 독립 검토에서 complete R/S 이후 arbitrary schedule 선택이 optimum을 놓칠 수 있음을 확인하고, canonical schedule과 필요한 schedule selector의 완전성 계약을 추가했다. 실행 context별 함수 비용, 한 propagation 구간의 domain 삭제 상한, bounded cache/eviction, Exact reference 역할도 정밀화했다. 해당 계약의 재검토는 실제 구현·실행 동등성 검증을 대신하지 않는다.
- 테스트 실행, 빌드, benchmark, commit/push는 수행하지 않았다. 새 설계의 실행 동등성·성능은 미검증이다.

[model-build]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L646
[oracle-api]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L125
[generator]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L179
[local-opt]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java#L65
[regional-options]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L55
[sparse]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativeContinuitySupportClauses.java#L104
[sparse-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeContinuitySupportClausesTest.java#L78
[parity-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactConditionalNativeProductPhysicalParityTest.java#L136
[entry]: ../src/main/java/org/apache/sysds/parser/DMLTranslator.java#L625
[ftypes]: ../src/main/java/org/apache/sysds/hops/fedplanner/FTypes.java#L53
[materialize-rep]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDLocalMaterializeUtil.java#L41
[mm-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateBinaryFEDInstruction.java#L313
[append-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/AppendFEDInstruction.java#L166
[identity]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java#L320
[value-id]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java#L352
[cfg]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java#L144
[joint]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L49
[joint-factor]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L1942
[opcaps]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesApi.java#L289
[oracle-relation]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L434
[privacy-policy]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java#L267
[privacy-input]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidatePrivacyInputPruning.java#L94
[privacy-transfer]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerUtils.java#L1526
[solver]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L1499
[shared-cost]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java#L1590
[activation]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactMaterializationActivation.java#L81
[factory]: ../src/main/java/org/apache/sysds/hops/ipa/FederatedPlannerFactory.java#L45
[local-entry]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java#L43
[correlated]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L966
[sharing-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactDerivedSupplySharingTest.java#L87
[branch-normalize]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/BranchPlacementNormalization.java#L47
[joint-query]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L672
[joint-branch]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L872
[loop-entry]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L731
[loop-provisional]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L4370
[loop-discharge]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L4527
[loop-seed]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L8896
[loop-ground]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L9717
[function-call]: ../src/main/java/org/apache/sysds/runtime/instructions/cp/FunctionCallCPInstruction.java#L127
[function-block]: ../src/main/java/org/apache/sysds/runtime/controlprogram/FunctionProgramBlock.java#L104
[function-recompile]: ../src/main/java/org/apache/sysds/hops/recompile/Recompiler.java#L2379
[function-boundary]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L10827
[recursion]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFacts.java#L1020
[transient-fact]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L4427
[transient-support]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L5516
[transient-factor]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L1920
[transient-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactTransientRealizationTest.java#L29
[authority-merge]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L2847
[receipt]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L3451
[index-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/IndexingFEDInstruction.java#L176
[rmempty-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/ParameterizedBuiltinFEDInstruction.java#L598
[roll-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/ReorgFEDInstruction.java#L317
[materialize-ranges]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDLocalMaterializeUtil.java#L125
[fout-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDFoutInstruction.java#L95
[aggregate-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateUnaryFEDInstruction.java#L153
[aggregate-oracle]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java#L2952

[compute-cost]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java#L282
[collect-cost]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java#L2158
[upload-cost]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java#L2238
[emission]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java#L152

[planner-entry]: ../src/main/java/org/apache/sysds/parser/DMLTranslator.java#L918
[boundary-policy]: ../src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java#L489

[sparse-publication]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeMixedAxisSplitPublicationTest.java#L772
[factor-freeze]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L1860
[joint-regression]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L38
[function-alias-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/FunctionBoundaryRuntimeAliasContractTest.java#L20
[dynamic-loop-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/StepLmDynamicLoopPlacementTest.java#L37
[joint-invoke]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L946
[generator-product]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L1252
[physical-alternatives]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L1050
[oracle-intern]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L211
[oracle-profile]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L720
[oracle-determinant]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L445
[joint-loop]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L896
[joint-limit]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L1186
[privacy-worklist]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L2305
[closure-pass]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1049
[pruning-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CostBasedPruningTest.java#L35
[quotient]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java#L30
[dyadic-cost]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactDyadicCosts.java#L189
[solver-eliminate]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L3165
[solver-statistics]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L989
[resource-guard]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/PlannerResourceGuard.java#L21
[metrics]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/SearchSpaceMetrics.java#L31
[metrics-oracle]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/SearchSpaceMetrics.java#L462
[factorized-storage]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/CorrelatedFactorizedSupportStorageTest.java#L50
[candidate-timing]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateFormationTiming.java#L142
[pipeline-timing]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerPipelineTiming.java#L16
[trace-timing]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerTrace.java#L144
[memory-collector]: ../scripts/fedplanner/run_supply_sharing_e2e.py#L150
[freeze-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactFactorFreezeOrderTest.java#L65
