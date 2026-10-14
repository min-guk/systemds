# FedPlanner 통합 Architecture & Algorithm Specification

> **구현 중 정책 갱신 — ADR22, 사용자 승인:** 2026-10-13 사용자가 “0~1회 경로도 정확하게 지원”을 선택했다. 아래 이전 T>1 제한/zero-arm 제외/unsupported 기대는 이 결정으로 대체된다. 정상 종료·최종 값 사용, 정적 HOP rule, DIRECT-only **taken** backedge, body 내부 일반 transfer 허용은 그대로다. 구현·검증 상태는 `FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md`가 기록하며, 이 정책 결정 자체는 검증 완료를 뜻하지 않는다.


작성일: 2026-10-13. 기준 저장소: `/home/mchoi/w1357-structural-grounding-20261006`.
기준 HEAD: `c961a291811d23db104cc63a2b161e7e4b480e57`; 분석 대상은 이 HEAD와 현재 미커밋 작업 트리다.

**Control Flow 정책 개정:** 사용자 확정 정책에 따라 Loop의 `Next(t)→Head(t+1)`는 **DIRECT-only**, Entry/최종 Exit 및 body 내부 일반 HOP edge는 합법 movement를 허용한다. If-Else join은 branch별 native output/exec가 달라도 기존 guarded supply를 사용한다. 이 개정은 이전 문서의 backedge normalization/transfer 후보·비용 설명에 우선한다. 상세 계약과 전환 근거는 §6.4, §6.6에 있다.

**Joint Feasibility 개정:** exact definition/use/position의 binding을 출발점으로, 실제 비분리 조건을 관찰하는 consumer 또는 loop/call 경계에만 검사 handle을 둔다. 입력별로 분리 가능한 조건은 기존 edge 검사로 처리하고, 관련 domain/readiness/선택 변경만 역색인으로 전파한다. RD 집합의 교집합이나 개수로 독립성을 추정하지 않는다. ADR21과 §5.3–5.7이 선행 문서의 포괄적인 joint-check 설명에 우선하며, 기존 Control Flow·Movement 지원 범위는 변경하지 않는다.

이 문서는 아래 조사 문서들의 결정을 통합한 **새 Memo planner의 단일 구현 기준**이다. 기존 문서는 이력·근거로 남긴다. 구현이나 실행 검증을 완료했다는 보고서가 아니다. 최초 통합 작업은 이 문서만 추가했고, 후속 Control Flow 및 Joint Feasibility 개정은 관련 설계 문서와 세션 기록만 갱신했다. Production/test·실험 결과는 보존했으며 build/test/benchmark/commit/push를 수행하지 않았다.

표기: **[사실]**은 현재 소스 또는 명시된 과거 실행 기록, **[해석]**은 그 사실에서 도출한 의미, **[설계]**는 이 명세의 규범, **[미검증]**은 추가 증거가 필요한 주장이다. 결정 상태는 `DECIDED`(정책/API 확정), `VALIDATED`(명시한 범위의 증거로 확인), `OPEN`(비필수 후속 문제), `BLOCKER`(해당 단계의 완료를 막는 문제)다. `DECIDED`가 실행 검증을 뜻하지 않는다.

## 1. Executive Decision Summary

**권고 아키텍처는 하나다: structured ProgramFacts + source-free immutable RuleMemo + active domains + reaching-definition-driven supply/joint checks + closed-tree DP + separator를 메모하는 exact global search + transactional emission.** 별도 범용 Constraint Graph, Anchor 후보 탐색, source Cartesian product, SupportClause/Proof 객체, 반복 candidate rebuild를 새 구조에 옮기지 않는다.

1. Oracle은 추상 입력 패턴별 **모든 native execution variants**를 반환한다. 단순히 현재 단일 `OpCaps` 반환값을 List로 감싸면 불완전하다. Generator의 CP/forced-local 보충, 다중 출력 owner, runtime dispatch도 옮긴다. [Oracle API][oracle-api], [현재 보충 경로][generator-combinations]
2. RuleMemo에는 capabilities와 조건만 저장한다. 프로그램의 정확한 정의·guard·호출·loop 관계는 ProgramFacts에 한 번 저장한다. 비용과 최적 결정은 별도 DPMemo에 저장한다.
3. 하나의 실제 compiled owner에는 하나의 native rule 선택이 있다. 호출별 occurrence/value/binding은 분리하되, 같은 compiled HOP을 호출별로 모순되게 설정하지 않는다.
4. Available output은 producer가 제공할 수 있는 **recipe+조건**이다. 실제 edge의 공급 선택·생성 위치·재사용 수명은 optimizer가 정하고 emitter가 그대로 구현한다. availableOutputs를 모두 미리 실행하지 않는다.
5. 초기 새 planner는 **D1(동일 값의 FED→LOCAL→FED 복합 이동)을 OFF**, 비정규 source를 명시 거절한다. 이는 사용자 기본 전제에서 자동으로 따라오는 사실이 아니라 이 문서가 선택한 추가 지원 제한이다. CP 연산이 새 논리 값을 만든 뒤 upload하는 것은 허용한다.
6. Loop는 **T>1 보장**, 정상 종료와 최종 값의 외부 사용을 유지한다. Backedge는 native value를 DIRECT로 공급해야 하며 transfer/materialization 선택변수와 그 비용항을 만들지 않는다. Entry/Exit와 일반 body edge의 합법 transfer, entry grounding, backedge 귀납, 최종 value binding은 유지한다.
7. 초기 구현은 한 compile epoch에서 native variant와 abstract input pattern까지 정적이다. 사용자가 확정한 ExecType/nativeOutput 고정보다 강한 제한이다. Shape/privacy의 변화는 증명 가능한 invariant로 처리하고, 실제 recompile은 새 epoch이다.
8. NativeOutput별 cheapest 하나는 일반 pruning이 아니다. 완전한 continuation equivalence 또는 닫힌 tree의 정확한 DP state 안에서만 축약한다. Global search는 비용 모델의 최적성을 증명했을 때만 `OPTIMAL`이다. 기존 Local의 5% 종료는 별도 비교 모드다.
9. Anchor-free upload, REP collect, 선택된 materialization scope/lifetime lowering은 **명시적인 구현 변경**이다. 현재 runtime이 이미 그 계약을 충족한다고 가정하지 않는다.
10. Joint feasibility는 전역 재분석 단계가 아니다. ProgramFacts의 정확한 binding을 소비하는 edge/consumer/boundary 조건으로 분해하고 필요한 위치에서만 검사한다. 서로 다른 검사들의 witness를 별개의 실행 선택으로 채택하지 않으며, 전체 최적화는 여전히 공통 owner/supply 선택 하나를 사용한다.

**Readiness 판정:** 자료구조·정책·전체 알고리즘·migration 순서는 확정했다. M0 catalog 조사와 M1 shadow scaffolding은 착수할 수 있다. 다만 native coverage/canonical output과 REP collect·scope-retained emission의 검증 공백 때문에 **전체 지원 범위의 Implementation-Ready 또는 기존 planner 제거 가능 상태라고 선언하지 않는다**. §13의 단계별 blocker를 먼저 닫아야 한다.

## 2. 자료 조사와 의사결정 통합

### 2.1 코드·작업 트리 기준

물리 경로는 `/grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/workspace-preserved`다. 최초 통합 조사 시작 시 tracked diff는 8개 파일, +578/-54이고 그중 production/test 6개는 +463/-54였다. 다음 dirty 코드는 HEAD와 구분해 읽었고 정책 개정에서도 그대로 보존했다.

|파일|현재 작업 트리의 의미|
|---|---|
|`placement/NativeContinuitySupportClauses.java`|조건부 product의 sparse ordinal authority 및 보존된 기존 clause 접근|
|`placement/PlacementAnalysis.java`|native product/projection과 authority 연계|
|`placement/PlacementRelationClosure.java`|확장·publication 경로에 위 표현 통합|
|`placement/NativeContinuitySupportClausesTest.java`|sparse holes, withdrawal/re-add, 보존된 clause 접근 회귀|
|`placement/NativeMixedAxisSplitPublicationTest.java`|혼합 axis publication 의미·identity 회귀|
|`fedExact/ExactConditionalNativeProductPhysicalParityTest.java`|physical domain/factor/선택 의미 비교|

이 변경은 **source product 표현 개선**이며 새 immutable RuleMemo 설계의 구현이 아니다. Post-v50 dirty 변경의 성능을 frozen v50 결과에 귀속하지 않는다. 기존 dirty 문서 두 개 및 모든 untracked 분석·실험 파일도 보존한다.

### 2.2 문서 inventory

아래 `docs/`는 현 저장소 기준이다. 날짜는 본문을 우선하고, 없으면 파일명/디렉터리 날짜임을 표시했다. 기준 코드가 없으면 추정하지 않았다. 30개 주요 문서의 관련 주장·전제·결과를 표적 검토했으며 모든 과거 실험 원자료를 재실행하거나 모든 문장을 전수 검증한 것은 아니다.

|ID / 경로|작성 시점 / 명시 기준|통합 판단|
|---|---|---|
|D01 [`REPORT_KO_ORIGINAL_2026-09-24.md`](/home/mchoi/fedplanner-analysis-2026-09-24/REPORT_KO_ORIGINAL_2026-09-24.md)|09-24 / `07726cd7fb2e50e8801d46d775454b2c418dadf4`|역사적 pipeline; 현 API 근거로 사용하지 않음|
|D02 [`REPORT_KO.md`](/home/mchoi/fedplanner-analysis-2026-09-24/REPORT_KO.md)|09-24, 09-25 보강 / 같은 HEAD|Local bound/gap 의미 참고|
|D03 [`CANDIDATE_GENERATION_DEEP_DIVE_KO.md`](/home/mchoi/fedplanner-analysis-2026-09-24/CANDIDATE_GENERATION_DEEP_DIVE_KO.md)|09-25 / `07726cd7`|source/proof product 생성의 역사적 설명; 전체 runtime 검증 아님|
|D04 [`LM_INTEGRATED_RUNNING_EXAMPLE_PROPOSAL_KO_2026-09-28.md`](/home/mchoi/fedplanner-analysis-2026-09-24/LM_INTEGRATED_RUNNING_EXAMPLE_PROPOSAL_KO_2026-09-28.md)|09-28 / 단일 base 없음, `ab595999…` 비교|폐기된 prediction-count privacy 예제는 재사용하지 않음|
|D05 [`example-chapter.md`](/home/mchoi/fedplanner-readonly-analysis-20261013/example-chapter.md)|디렉터리 10-13 / frozen v50, HEAD 미기재|planner 목적함수 설명 자료; worker 실행시간 아님|
|D06 [CURRENT_CROSS_BLOCK_PLANNING_CONSTRAINTS](CURRENT_CROSS_BLOCK_PLANNING_CONSTRAINTS_2026-10-06_KO.md)|10-06 / `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592`+dirty|당시 all-writer 제약의 현황; 필수 runtime 법칙으로 승격하지 않음|
|D07 [CROSS_BLOCK_CONSTRAINT_DESIGN_RECOMMENDATION](CROSS_BLOCK_CONSTRAINT_DESIGN_RECOMMENDATION_2026-10-06_KO.md)|10-06 / `3d0d683c1bca099f004edf9105f5387369b4abea`|한 계획이 실제 공동 환경 모두에서 합법이어야 한다는 의미 채택|
|D08 [CORRELATED_EXECUTIONS_PLAN_COMPLETENESS](CORRELATED_EXECUTIONS_PLAN_COMPLETENESS_2026-10-06_KO.md)|10-06 / `0b51cc3ef8`+dirty|공통 join layout 강제가 합법 runtime 계획을 잃는 반례 채택|
|D09 [STRUCTURAL_GROUNDING](STRUCTURAL_GROUNDING_2026-10-06.md)|파일명 10-06 / `58145e73667e0a4f43589e9d37199fc90d293ee8`|추가 reachability search 제거와 entry/binding 제거를 구분|
|D10 [LOOP_ENTRY_IMPLEMENTATION](LOOP_ENTRY_IMPLEMENTATION_2026-10-06.md)|파일명 10-06 / `88e733d303fb453158ae67a2157deaeb80961f6a`|initializer 1회·body 반복 의미 채택; data anchor 대체|
|D11 [ORACLE_NATIVE_API_PLAN](FEDPLANNER_ORACLE_NATIVE_API_PLAN_2026-10-08_KO.md)|10-08 / `e468797556`+129 dirty/new|relation-native 이전안; exact source authority 객체는 새 API에 불필요|
|D12 [ORACLE_NATIVE_RESULT](FEDPLANNER_ORACLE_NATIVE_RESULT_2026-10-08_KO.md)|10-08 / `e468797556e6789100736355ca2463941302221f`+snapshot|49 families 조사, 일부 scalar paths 구현; 전체 완료 아님|
|D13 [WEIGHTED_RELATION_NATIVE_CLOSURE_DESIGN](FEDPLANNER_WEIGHTED_RELATION_NATIVE_CLOSURE_DESIGN_2026-10-08.md)|10-08 / base 미기재|초기안; 후속 result로 위임. 객체 보존 요구를 의미와 분리|
|D14 [RULE_DIRECTED_GENERATION_RESULT](FEDPLANNER_RULE_DIRECTED_GENERATION_RESULT_2026-10-08_KO.md)|10-08 / 직전 frozen engine, hash 미기재|determinant pruning과 source product 제거는 별개|
|D15 [FACTORIZED_PIPELINE_RESULT](FEDPLANNER_FACTORIZED_PIPELINE_RESULT_2026-10-08_KO.md)|10-08 / 직전 frozen engine|제한된 scalar PUBLIC/sparse 경로; 전체 symbolic 완료 아님|
|D16 [GENERAL_RELATIONS_PLAN](FEDPLANNER_GENERAL_RELATIONS_PLAN_2026-10-08_KO.md)|10-08 / `e468797556`|joint holes·owner·비분리 비용·tie 의미 채택|
|D17 [GENERAL_RELATIONS_RESULT](FEDPLANNER_GENERAL_RELATIONS_RESULT_2026-10-08_KO.md)|10-08 / `e468797556`|VALUE_MAP/function clause-sensitive 잔여 경로 인정|
|D18 [PRINCIPLE_REBUTTAL_FACTORIZED_EQUIVALENCE](PRINCIPLE_REBUTTAL_FACTORIZED_EQUIVALENCE_2026-10-08_KO.md)|10-08 / base 미기재|PUBLIC tiny parity/negative fixtures 유효; 보호 ML 성능과 구분|
|D19 [DP_PRUNING_PARITY](DP_PRUNING_PARITY_2026-10-08_KO.md)|10-08 / `e468797556`+dirty|boundary 내부 certified pruning; 후속 repair 우선|
|D20 [BOTTLENECK_EXPLANATION](FEDPLANNER_BOTTLENECK_EXPLANATION_2026-10-09_KO.md)|10-09 / 단일 HEAD 없음|early pruning 후 source/support 재전개가 남는 근거|
|D21 [ALGORITHM_AND_BOTTLENECKS](FEDPLANNER_ALGORITHM_AND_BOTTLENECKS_2026-10-13_KO.md)|10-13 / 1–11절 `33384c0054becbfefba6a4e51a5de7aefe745988`, 후속 별도|누적 v43–v48 이력; 날짜만으로 단일 snapshot 취급 금지|
|D22 [PROGRESS_REPORT_0945](FEDPLANNER_PROGRESS_REPORT_2026-10-13_0945_KO.md)|10-13 09:45 CEST / `c961a291…`, frozen v50|현재 v50 성능 근거; dirty 구현 결과와 분리|
|D23 [OPTIMIZATION_PROGRESS](FEDPLANNER_OPTIMIZATION_PROGRESS_2026-10-13_KO.md)|10-13 / V58 `5483ecbc6f`, V57 `3b5040b7b1` 등|다른 engine/resource; V59 성능 미측정|
|D24 [ARCHITECTURE_GUIDE](FEDPLANNER_ARCHITECTURE_GUIDE.md)|본문 날짜 없음 / `c961a291…`+dirty|현 구조와 Local 5% 계약 설명|
|D25 [DATA_STRUCTURES](FEDPLANNER_DATA_STRUCTURES.md)|본문 날짜 없음 / `c961a291…`+dirty|AND/OR, proof dependency, identity와 의미 구분|
|D26 [MEMO_ARCHITECTURE_COMPARISON](FEDPLANNER_MEMO_ARCHITECTURE_COMPARISON.md)|본문 base/date 미기재, v50 연결|옛 3상태 안은 REP 포함 4상태로 대체|
|D27 [SIMPLE_MEMO_REDESIGN](FEDPLANNER_SIMPLE_MEMO_REDESIGN_2026-10-13_KO.md)|10-13 / `c961a291…`+dirty|초기 통합안; loop/DP/ownership 후속 수정을 반영|
|D28 [EARLIEST_SAFE_PRUNING](FEDPLANNER_EARLIEST_SAFE_PRUNING_2026-10-13_KO.md)|10-13 / 같은 기준|UNKNOWN, domain readiness, root/branch pruning 구분 채택|
|D29 [ORACLE_SUPPLY_DESIGN](FEDPLANNER_ORACLE_SUPPLY_DESIGN_2026-10-13_KO.md)|10-13 / 같은 기준|owner/slot, input role, conditional offer 채택|
|D30 [CONTROL_FLOW_DP_DESIGN](FEDPLANNER_CONTROL_FLOW_DP_DESIGN_2026-10-13_KO.md)|10-13 / 같은 기준|T>1/live-out, compile epoch, complete boundary cache 채택|

추가로 [LOCAL_GLOBAL_DP_REPORT](FEDPLANNER_LOCAL_GLOBAL_DP_REPORT_2026-10-06_KO.md)(10-06, `e1fdfe4446180ef9d6fbd93fd0c4f5ab899d7440`), [RESOURCE_POLICY](FEDPLANNER_RESOURCE_POLICY_2026-10-06.md), [MEMO_ARCHITECTURE_REVIEW](FEDPLANNER_MEMO_ARCHITECTURE_REVIEW_2026-10-13_KO.md), [SOURCE_CALL_FLOW_GUIDE](FEDPLANNER_SOURCE_CALL_FLOW_GUIDE_2026-10-13_KO.md), [BOTTLENECK_MAP](FEDPLANNER_BOTTLENECK_MAP.md)를 교차 확인했다. Resource policy의 과거 회귀 통과도 현 dirty snapshot 검증으로 승격하지 않는다.

### 2.3 충돌 해결 규칙

|상충 주장|해결|상태|
|---|---|---|
|Fixed W이면 range alignment도 자동|W의 순서와 canonical geometry가 별도 필요; 실제 map 검사 유지|VALIDATED: 현재 allocator가 같은 shape anchor ranges를 그대로 보존함 [allocator][canonical-ranges]|
|3 partition states면 후보도 최대 3개|REP 포함 4 layout; input patterns/variant/output slots 때문에 K는 4가 아님|DECIDED|
|Structural grounding 제거 = 모든 grounding 제거|정확한 def-use·entry·시간 선행은 유지; placement proof route만 제거|DECIDED, D09/D10|
|Loop exit 미지원 = 외부 최종 값 미지원|사용자 답변 T>1 + live-out 우선; 정상 exit와 최종 backedge 값 필수|DECIDED|
|각 callsite를 독립 최적화|같은 compiled owner는 공유 선택; 실제 clone/epoch만 별도|VALIDATED: emission 충돌 검사와 runtime body 공유|
|같은 output cheapest면 충분|닫힌 DP state/검증된 dominance 안에서만 가능|DECIDED; §7 반례|
|기존 object identity/fingerprint가 의미의 정의|정확한 version/epoch 의미 유지, 표현 hash의 동일성은 별도 진단|DECIDED; D22의 fingerprint FAIL은 그대로 보존|
|5% Local = 정확한 global optimum|Local은 해당 모델의 certified gap, exact는 탐색 증명 필요|VALIDATED [regional-options]|
|REP collect를 replica 한 개 GET으로 비용 계산|현재는 W개 GET. 첫 구현도 W개 요청 계약, replica-aware bind 필요|VALIDATED 정적 경로; 실행 결과 미검증|

## 3. Assumptions, Scope, ADR

### 3.1 보장할 계획 공간

`Lold`는 과거 객체 목록 자체가 아니라 기존 runtime으로 실행 가능한 의미적 계획 집합이다. `Lcanonical`은 사용자 확정 W/partition/privacy/no-direct-reshard 및 확정된 T>1 계약을 만족하는 부분이다. 이번 사용자 정책을 반영한 `Lcontrol`은 그중 loop backedge가 DIRECT-only인 계획이다. If-Else에는 별도의 same-output/no-join-transfer 제한을 추가하지 않는다. `Lspec`은 `Lcontrol`에 다음 **추가 설계 제한**을 적용한다: D1 OFF, 확인된 canonical native variants, 한 epoch의 정적 native variant/input pattern, 증명 가능한 loop invariant, emitter가 지원하는 명시 supply scopes, 비재귀·sequential 실행과 ADR19 설정. Full completeness 주장은 **Lspec에 한정**한다. `Lold ∩ Lcanonical` 전체와 같다고 주장하지 않는다. 과거 backedge normalization 계획의 제외는 runtime 불가능이 아니라 명시적인 새 사용자 정책이다.

추가 제한으로 제외되는 합법 계획을 `INFEASIBLE`이라 부르지 않는다. `UNSUPPORTED_SCOPE(reason)`로 기록한다. 증명/Oracle coverage가 부족하면 `UNRESOLVED(reason)`다. 모든 조합을 정확히 검사해 없음이 증명된 경우만 `INFEASIBLE`이다. Resource 중단은 `RESOURCE_EXHAUSTED(incumbent, certifiedLB)`이며 최적 완료가 아니다.

### 3.2 Architecture Decision Records

|ADR|대안과 trade-off|단일 결정 / 영향 / 근거|
|---|---|---|
|01 W/geometry|기존 anchor의 ranges 보존 vs 유일 canonical allocator|**DECIDED:** session-owned ordered W descriptor; q=floor(length/W), remainder를 앞 worker부터 1개씩 배분. ROW는 rows≥W, COL은 cols≥W; 빈 slice 금지. W1 FED는 REP. 현재 allocator의 non-preserve 분기와 같은 산식 [canonical-ranges]|
|02 ingress|자동 normalize vs 명시 변환 vs reject|**DECIDED:** source가 W/type/range/shape를 정확히 만족해야 함. 불일치면 scope 거절. 비정규 PRIVATE 데이터를 몰래 collect하거나 worker 일부만 사용하지 않음. 외부에서 이미 정규화한 입력은 허용|
|03 D1|모든 primitive의 transitive closure vs 한 hop recipe|**DECIDED:** 동일 value에 DIRECT 또는 단일 UPLOAD/COLLECT만. FED→LOCAL→FED(같은 layout/REP 포함) OFF. alias/TRead/function return으로 origin을 지워 우회 금지. 장래 ON은 별도 계약·비용·emission 추가|
|04 REP|FULL/BROADCAST 이름 치환 vs runtime adapter|**DECIDED:** planner는 REP; W1 runtime FULL, W>1 BROADCAST. 각 opcode dispatch를 검증. temporary sliced/broadcast operand는 persistent REP output이 아님|
|05 unknown|모르면 불가능으로 prune vs 보류|**DECIDED:** 삼값 YES/NO/UNKNOWN. 완성된 domain에서 NO 증명만 삭제; UNKNOWN은 보존하되 최종 성공 leaf에는 남길 수 없음|
|06 Oracle completeness|현재 OpCaps wrapping vs native catalog 이식|**DECIDED:** operator family의 dispatch 우선순위·forced-output·slot layout·input preparation을 포함한 source-free variant 집합. coverage 상태는 별도; missing variant를 privacy 실패로 위장하지 않음|
|07 loop|일반 CFG/placement closure vs structured contract|**DECIDED:** loop head의 Entry/Backedge/Exit binder + joint invariant. Backedge는 ADR20의 DIRECT 검사, Entry/Exit는 기존 supply. T>1이므로 zero arm 제거; body variant는 epoch 내 고정. shape/privacy facts는 필요한 SCC에서만 계산|
|08 function|callsite 전부 복제 vs 공유 compiled owner|**DECIDED:** 기본 공유 body 선택+call별 bindings. 실제 compiler clone/새 epoch만 choice 분리. recursion은 typed scope 거절|
|09 branch|공통 native output 강제 vs guarded supply|**DECIDED, 사용자 확정:** then/else producer는 서로 다른 ExecType/nativeOutput을 선택할 수 있다. Join consumer의 선택 rule/input requirement에 각 실제 branch output을 DIRECT/합법 movement로 공급한다. 동일 branch output 강제나 join transfer 금지 없음; guard·privacy·reaching definition 및 실행 branch 비용 보존|
|10 materialization|opportunistic runtime cache vs selected schedule|**DECIDED:** optimizer가 retention region을 선택, 첫 실제 demand에서 생성, 같은 version의 이후 demand가 공유. 동적 key/expiry 명시. 기존 planned `sharing=single`에 없는 cross-iteration/call 재사용은 구현·검증 전 활성화하지 않음|
|11 exact search|dense VE 기본 vs lazy B&B+지역 memo|**DECIDED:** 한 `solve(region,boundary)` 알고리즘; 닫힌 tree는 특수 DP, 나머지 lazy exact B&B. 기존 Local seed/Exact numerical utilities 재사용. 기존 VE는 작은 reference/비교 backend로 보존|
|12 resource|top-K/시간 도달을 성공으로 처리 vs 증명 상태 분리|**DECIDED:** cache는 eviction만, domain 축소 없음. 사전 임의 search cap 없음. 외부 취소/실제 resource 실패는 incomplete; OPTIMAL로 반환 금지 [RESOURCE_POLICY](FEDPLANNER_RESOURCE_POLICY_2026-10-06.md)|
|13 cost|벽시계 exact 예측 vs 선언한 estimator의 exact 최적화|**DECIDED:** 기존 primitive/fused/activation 의미를 이식한 동결 모델의 최적값을 구함. conservative activation 추정도 모델 함수로 명시. 실제 runtime 최적 보장 아님|
|14 identity|Java reference equality vs stable compact IDs|**DECIDED:** snapshot-local IDs+definition/slot/compiled owner/epoch. 같은 문자열·HopId·변수명만으로 cache/selection 권위 인정 금지|
|15 emission|선택 후 movement 재발견 vs 그대로 lowering|**DECIDED:** selected supply의 모든 edge/action/scope를 사전 검증하고 atomic emit. runtime의 암묵적 layout 교정/대체 실행 없음|
|16 REP collect|현 bind 재사용 vs 하나의 replica만 GET vs replica-aware all-W collect|**DECIDED:** 모든 W GET 성공 요구, full shape 검증, deterministic replica 하나의 값/NNZ를 publish. NNZ를 replica 간 더하지 않음. 현재 경로 보완 필요; 실패를 무시하는 fallback 금지|
|17 multi-output|slot별 execution 독립 vs owner variant|**DECIDED:** owner의 실행은 하나, slot별 native layout은 vector. 독립 output upload는 별도 action이며 owner FED 설정으로 오인하지 않도록 lowering 수정. 구현 전 미지원 variants는 scope 명시|
|18 memo lifetime|프로그램 전체 무기한 cache vs snapshot cache|**DECIDED:** Rule universe/조건 cache는 compile epoch 단위. recompile·facts/W/privacy/cost profile 변경은 새 snapshot; 검증된 순수 Oracle signature cache만 epoch 밖 재사용|
|19 runtime execution settings|임의 optimizer shortcut과 조합 vs 명시 scope|**DECIDED:** 초기 새 경로는 sequential structured loops, function lineage-output reuse OFF를 요구한다. ParFor/동시 action 생성/함수 결과 reuse는 별도 검증 전 scope 밖. 설정을 몰래 바꾸지 않고 진입 때 검사한다. 함수 조기 reuse는 [FunctionCallCPInstruction][function-call-runtime]에 존재|
|20 backedge transfer|비용으로 반복 normalization 배제 vs 후보 자체 미생성|**DECIDED, 사용자 확정:** `Next(t)→Head(t+1)`는 native output/헤더 input의 DIRECT 호환만 허용. LOCAL→LOCAL(CP carrier), FED p→FED p만 가능하며 canonical ranges/shape/privacy도 검사. Backedge 전용 recipe/action/retention/cost 변수 없음. Entry/Exit 및 일반 body movement는 유지|
|21 joint feasibility|전역 공동 환경/ancestor closure vs RD overlap만 검사 vs 관련 binding의 조건별 검사|**DECIDED:** exact def-use로 scope를 찾고 predicate의 비분리성으로 joint 필요성을 결정. 기존 binding/owner/loop/call 관계의 역참조와 dirty queue 사용. RD overlap만으로 생략 금지, 새 source/Proof graph 없음. 등록·coverage·선택 검증은 §5.5–5.7; 미구현으로 실행 성능은 미검증|

D1 OFF·정적 input pattern·sequential/lineage 설정은 추가 설계 제한이며 사용자 확정 전제로 기록하지 않는다. 비재귀 함수는 사용자가 요청한 scope 분석 조건을 ADR08에서 채택한 것이다. 이 명세는 구현 결정을 위임받아 이 선택을 채택한다. 향후 지원 확대는 별도 버전의 Lspec과 parity gate를 요구한다.

### 3.3 핵심 가설과 검증 상태

|가설|최소 조건 / 반례 / 결론|
|---|---|
|Anchor 탐색을 없앨 수 있다|W+geometry descriptor가 독립 존재해야 함. 현 fed_fout은 data anchor 없으면 실패. API seam은 확인됨; runtime 변경 전 사용 불가|
|Native rule universe를 한 번 생성할 수 있다|shape/privacy의 의미 snapshot과 exhaustive variant coverage가 고정되어야 함. loop중 shape가 새 dispatch를 요구하면 새 epoch 또는 unresolved. source support 성장과 native capability를 분리하면 rebuild 불필요|
|generic graph 없이 정확하다|HOP edges+guarded bindings+loop/call/owner 관계가 모든 joint 의존을 표현해야 함. 작은 incidence index는 필요; pairwise layout 검사만으로는 부족|
|loop를 한 번 읽으면 충분하다|placement 후보 closure는 불필요해질 수 있음. `A=A+Y`에 entry가 없으면 같은 FED/ROW라도 self-support. Shape/privacy 귀납은 별도|
|4 layouts면 memo/solver 선형이다|arity a의 패턴은 최악 4^a, boundary의 폭도 지수. 4는 layout domain 크기일 뿐 전체 계산량 보장이 아님|
|shared materialization은 ID만 같으면 공유 가능|동적 version·guard·retention scope가 같아야 함. 같은 static ID를 loop 전후 값에 재사용하면 잘못된 값|
|lazy search면 성능 개선|source object 생성은 제거되지만 탐색 node/지원 witness 방문은 지수 가능. v50 optimizer 비용이 별도로 큼. 실제 wall time 개선은 미검증|

## 4. 최종 Data / Program / Execution Model

### 4.1 단계와 정보 owner

|단계|입력 → 출력|유일 owner / invariant|기존 대응|
|---|---|---|---|
|Program analysis|HOP/structured SB/compiler metadata → frozen facts|정의·guard·source·shape/privacy·loop/call bindings, compiled choice ownership. 실행 선택 없음|`PlacementProgramFacts.analyze`, joint/frequency 분석|
|Oracle|operator+abstract pattern+shape/literals/roles+runtime catalog → native variants|native exec/output/input preparation만. producer realization/proof 없음|`FedOracle.caps`, `OracleFacade`, Rulesets|
|Privacy/movement filter|variants+facts → conditional legal rules/offers|무조건 불가능 제거; 선택 의존 predicate 유지|`PlacementPrivacyFacts`, `ExecPlacementPolicy`|
|RuleMemo|native variants/condition IDs → immutable primitive arrays|rule의 input pattern/native slot layouts/offer templates. 값 복사 없음|CandidateRule/Fact 대신|
|Feasibility|facts+RuleMemo+active BitSets → smaller domains|exact def-use에 걸린 edge 및 필요한 비분리 consumer/boundary support. Dirty 관계의 NO 증명만 삭제|Closure 후보 재생성 대신|
|Cost/optimization|active domains+frozen cost model → selected rules/supplies/scopes|한 compute owner/한 materialization activation 비용, complete boundary memo|PhysicalModel/Local/Exact 경로 단순화|
|Validation/emission|selected plan+동일 snapshot → instructions|실제 availability, privacy, canonicality, exact edge rewire, rollback|Projector/EmissionTransaction/Dag|

### 4.2 최소 Java API 스케치

이름은 제안 API다. 아래 records를 전부 heap 객체로 만드는 것이 아니다. 구현은 occurrence/rule/edge/condition ID를 dense int로 intern하고 CSR offset+primitive arrays를 사용한다. 기존 `Hop`/`StatementBlock`/facts 객체를 참조하며 복제하지 않는다.

```java
enum Partition { LOCAL, ROW, COL, REP }
enum Move { DIRECT, UPLOAD, COLLECT }
enum Verdict { YES, NO, UNKNOWN }
enum SolveStatus { OPTIMAL, INFEASIBLE, UNSUPPORTED_SCOPE,
                   UNRESOLVED, RESOURCE_EXHAUSTED }

record SnapshotId(long analysisEpoch, long compileEpoch) {}
record Occurrence(int hopRef, int callContext, int definitionId,
                  int compiledOwner, int outputSlot) {}
// Scalar/metadata role is separate from matrix payload layout.
record InputEdge(int consumer, int inputPosition, int bindingExpr,
                 int inputRole) {}
// One static read can have ENTRY and BACKEDGE binding arms.
// Kind belongs to the resolved boundary relation, not the whole HOP/input edge.
enum BindingKind { ORDINARY, LOOP_ENTRY, LOOP_BACKEDGE, LOOP_EXIT }
record NativeRule(int owner, int variantId, ExecType exec,
                  int patternOffset, int patternLength,
                  int outputLayoutsOffset, int outputCount,
                  int legalityCondition, int offerOffset, int offerCount,
                  int costKernelId, int loweringVariantId) {}
record Offer(Partition target, Move recipe, int conditionId) {}
record RuleMemo(NativeRule[] rules, int[] ownerOffsets,
                byte[] requiredLayouts, Offer[] offers) {}
record Domains(BitSet[] activeByOwner, byte[] activeHeadLayouts, long[] revisions) {}
// A head domain is at most four layouts. If an existing carrier owner determines
// it, use that owner's domain/view instead of allocating a duplicate domain.
record SupplyChoice(int edge, int guardedArm, int recipeId,
                    int originValueId, int retentionRegionId) {}
// No SupplyChoice/actionSlot is allocated for a LOOP_BACKEDGE binding.
// Its DIRECT route is derived from ProgramFacts + selected endpoint rules.
// Selected arrays only; not a second PhysicalAlternative universe.
record Plan(SnapshotId snapshot, int[] selectedRuleByOwner,
            int[] headContractByCarriedSlot,
            SupplyChoice[] selectedSupply, int[] actionSlots) {}

NativeResult nativeRules(OperatorSignature op, AbstractInputPattern pattern);
FilterResult filter(NativeRule r, ProgramFacts f); // unconditional NO or condition
Verdict isCompatible(InputEdge e, int producerRule, int consumerRule,
                     int recipe, BindingContext ctx);
Verdict isAvailable(int originValue, int useSite, int retentionRegion,
                    BindingContext ctx);
Verdict isDirectBackedge(int loopId, int carriedSlot, int writerRule,
                        int headContract, BindingContext ctx);
Cost evaluateCost(PlanOrPartialAssignment x, FrozenCostModel model);
SolveResult solve(int regionId, BoundaryKey key, SearchContext ctx);
ValidationResult validateAndEmit(Plan p, ProgramFacts f);
```

`ProgramFacts`는 기존 container를 축소해 다음 tables를 소유한다: occurrence→compiled owner/output slot, 정확한 definition/alias origin, ordered input positions, structured statement region tree, guard DAG, binding expression DAG, source availability, loop/call boundary records, shape/privacy facts, execution frequency 및 cost ownership. `InputEdge.bindingExpr`는 `Def(id)`, `Select(guard,then,else)`, `LoopHead(entry,backedge)`, `CallArg/Return`를 참조한다. 서로 다른 read가 동일 guard ID를 사용하므로 branch correlation이 유지된다. Source tuple 리스트를 저장하지 않는다.

`LoopHead`가 가진 entry/backedge 및 `Last`의 exit 관계에서 `BindingKind`와 loop/slot ID를 유도한다. 각 static input 전체를 BACKEDGE로 표시하지 않는다. 동일 read의 첫 iteration entry에는 movement가 가능하기 때문이다. `HeadContract`는 기존 carrier/선택 rule가 요구하는 stored layout/exec를 참조하는 boundary state이며 새 실행 kernel이나 별도 transfer 선택이 아니다. Head 이후 실제 body operator input은 기존 일반 supply edge다.

Head layout은 `(loopId, carriedSlot)`당 하나를 선택하고 모든 taken-backedge 환경이 **동일한 계약**을 검사한다. 기존 TRead/carrier owner 선택이 이를 유일하게 정하면 `headContractByCarriedSlot`은 해당 선택의 view다. Symbolic head라서 유도할 owner가 없을 때만 최대4 layout domain과 선택 ID를 둔다. Head exec는 LOCAL이면 CP, FED layout이면 FED로 유도한다. Writer마다 즉석에서 맞는 head를 골라 DIRECT를 통과시키거나 guard-parametric head를 새로 가정하지 않는다. 이는 backedge transfer 변수가 아닌 기존 loop boundary의 static state다.

`Memo[HopId]`는 사람이 보는 기본 인덱스일 뿐이다. 실제 lookup은 `snapshot + occurrenceId → compiledOwner의 rule view`다. 동일 HOP template의 여러 호출이 view를 공유해도 call별 shape/privacy/binding 검사는 분리된다. 공유 owner의 domain은 모든 reachable call context의 native variant 의미가 대조된 뒤 한 번 publish한다. 먼저 발견한 call의 subset을 완성 domain으로 오인하지 않는다.

### 4.3 output와 edge ownership

Oracle은 `nativeOutput[slot]`, input role/required layout, native internal preparation/release를 정한다. Privacy Filter는 rule 조건과 output release/movement 조건을 만든다. 공통 MovementPolicy는 native→target의 유한 recipe template을 정한다. Producer Memo는 조건부 Offer만 저장한다. Edge는 정확한 origin/position/guard에서 어느 Offer를 사용할지 정한다. Optimizer가 여러 edge를 같은 materialization action에 연결한다.

```text
DIRECT: LOCAL→LOCAL, ROW→ROW, COL→COL, REP→REP (actual canonical map match)
UPLOAD: native LOCAL payload→ROW/COL/REP (shape/privacy/runtime gate)
COLLECT: native ROW/COL/REP payload→LOCAL (release/privacy/runtime gate)
```

Metadata access는 payload COLLECT가 아니다. FULL/BROADCAST 또는 REP를 ROW의 상위 상태로 취급하지 않는다. 동일 value의 collect 결과를 새로운 LOCAL native producer로 바꿔 D1을 허용하지 않는다. Native CP operator가 값을 계산했다면 새 definition이므로 결과 upload는 가능하다. AvailableOutputs는 coexistence 증명이 아니므로 각각 합법이어도 동시 lifetime/side-effect 제약을 최종 선택에서 검사한다.

이 목록은 일반 supply의 최대 허용 집합이다. **LOOP_BACKEDGE에서는 producer의 availableOutputs를 탐색하지 않고 nativeOutput만 읽는다.** 같은 producer라도 외부 consumer, Entry/Exit, 일반 body consumer에는 기존 Offer가 남는다. 전역 Memo에서 그 producer의 UPLOAD/COLLECT Offer를 삭제하면 허용된 다른 사용까지 잃으므로 금지한다.

## 5. Native Enumeration과 Earliest Safe Pruning

### 5.1 Oracle-driven 생성

현재 `processCombination`은 구체적 입력 조합으로 Oracle을 호출한 후 CP/forced-local/derived-upload를 보충한다. MRV/prefix pruning이 있지만 source 조합을 생성하지 않는 구조는 아니다. [generator-combinations], [generator-prefix]

```text
buildRuleMemo(facts, runtimeCatalog, movementPolicy):
  require facts.snapshot frozen; owner contexts enumerated structurally
  for each distinct OperatorSignature(op, roles, shape facts, literals, W,
                                      oracleVersion, loweringVersion):
    for pattern in oracle.abstractPatterns(signature):
      # determinant/prefix capability NO는 여기서 중단. source tuples 없음.
      variants = nativeCache.getOrCompute(signature, pattern)
      for variant in variants:
        verify native output slots, dispatch, canonical map effect
        if capability is NO: record reason; continue
        if coverage/shape is UNKNOWN: retain condition; mark unresolved coverage
        result = privacy.filter(variant, contextsOfCompiledOwner)
        if result proves unconditional NO: record reason; continue
        offers = movementTemplates(variant.outputs, result.conditions, D1_OFF)
        intern source-free rule and offers; reference it from occurrence views
  publish each owner's immutable known universe + active BitSet once
  ready[owner] = nativeCoverageComplete && allActiveContextsRegistered
  enqueue publish/ready events; propagate incident closed relations immediately
  retain outstanding coverage obligations; do not infer NO from incomplete domain
```

`OperatorSignature`는 source identity를 포함하지 않는다. 하지만 datatype, metadata/payload role, required shape/literal selector, W cardinality/geometry version, native forced-output variant와 runtime policy version은 포함한다. Shape가 다른데 input partitions만 같다고 Oracle cache를 재사용하지 않는다. 현재 `RulesCore.OracleEngine.prepare`의 first-match dispatch를 유지한다. 모든 Ruleset의 결과를 무조건 union하면 runtime이 선택하지 않는 대안을 발명한다. [oracle-dispatch]

완전집합 검증 기준은 **old Oracle+generator가 보충한 native variants+runtime paths**다. derived upload는 새 native rule로 복제하지 않고 Offer가 된다. CP와 FED→LOCAL은 서로 다른 native execution이다. Scalar right-index처럼 FED 실행/native LOCAL인 경우를 보존한다. Multi-output은 owner variant 하나가 slot layouts를 동시에 결정한다.

CP payload kernel의 required pattern은 LOCAL이다. 현재 Oracle가 FED 입력 패턴에서 CP fallback caps를 반환했다고 해서 새 rule가 그 FED payload를 직접 읽을 수 있다고 해석하지 않는다. 합법 COLLECT를 edge에서 선택한 뒤 CP rule에 LOCAL을 공급해야 한다. Metadata/handle-only access는 role로 구분한다. FED native LOCAL variant 내부의 reduction/GET은 kernel semantics/cost에 포함되므로 같은 결과를 다시 COLLECT하지 않는다.

모든 owner가 완성될 때까지 propagation을 전역 barrier로 지연하지 않는다. 게시된 known universe에는 후보를 추가하지 않는다. Coverage가 미해결이면 알려진 합법 후보로 incumbent를 찾을 수는 있으나 완전성/최적성은 미증명이다. Missing capability를 나중에 확보하면 새 snapshot을 생성한다. 미준비 owner에 의존한 relation은 pending이고 ready event에 재검사한다. 독립된 sound contradiction이 전체 프로그램을 배제하는 경우를 제외하고 outstanding coverage는 OPTIMAL/INFEASIBLE 판정을 막는다.

### 5.2 단계별 가장 이른 안전한 제거

|시점|필요 정보 / NO 증명|soundness 조건|비용|
|---|---|---|---|
|1 native/shape|opcode dispatch, datatype/role, abstract pattern, known shape가 불가능|unknown shape를 false로 치환 금지; variant coverage가 완성되어야 empty 의미 확정|Oracle 패턴 수 P, 호출당 Tnative; cache O(P)|
|2 privacy/movement|rule 자체 금지 또는 release/recipe 금지|선택·guard 의존 조건은 조건으로 보존. PRIVATE_AGGREGATE 결과 공개가 raw input collect 허용을 뜻하지 않음|condition intern O(R+offers), 조건 평가 비용 별도|
|3 ingress/availability|고정 source가 noncanonical/다른 W, 필요한 정의가 해당 env에서 없음|아직 미분석 source를 없음으로 판정 금지. 부적합 scope와 프로그램 내 infeasible 구분|source maps O(total partitions), facts relation traversal|
|4 edge propagation|특정 rule의 필수 input edge에 compatible live support가 전혀 없음|consumer별 marginal support만 검사하면 pruning은 sound하나 complete 아님; shared/joint 문제 남김|단순 pairwise 최악 ΣE KpKcM, witness 재검사 비용 포함|
|4a backedge DIRECT|carried boundary의 native output과 head 계약이 직접 호환하지 않음|동일 exec label만으로 통과 금지. Entry/Exit/일반 body edge는 이 제한 대상이 아님. Guard가 미확정이면 NO 증명 조건 유지|backedge당 O(Kwriter·Khead·directPredicate); movement/scope 순회 없음|
|5 관련 joint relation|exact binding이 도달하는 consumer/boundary의 비분리 조건에서 공동 support 없음|§5.5의 scoped relation에 한정한 NO 증명. 독립 edge 검사를 joint로 재포장하지 않음; UNKNOWN/중단은 삭제 사유 아님|실제 조건 scope w에 최악 d^w; 어려운 부분은 global search까지 보존|
|6 cost|완전한 동등성 dominance, 고정 boundary의 최솟값, certified LB가 incumbent보다 나쁨|feasibility domain과 branch-local cut 분리. tie가 개선 가능하면 같은 비용만으로 prune 금지|signature O(R) 기대, boundary/search는 지수 가능|

Early pruning이란 **증명에 필요한 facts가 준비된 첫 단계**에서 검사한다는 뜻이다. Global infeasibility를 Oracle에서 알 수 있다고 요구하지 않는다. 사전 joint 검사를 무제한 전수 탐색해 optimizer를 두 번 실행하지 않는다: cheap predicates/known witnesses를 사용하고 어려운 support는 UNKNOWN으로 search까지 넘긴다.

### 5.3 양방향 incremental worklist

기본 index는 기존 exact `definition→uses(input position 포함)`, binding-expression child→parent/use, compiled owner→occurrence views 및 condition의 직접 선택변수→check 역참조다. 모든 consumer×전이적 source 집합이나 guard별 전체 프로그램 관계를 복제하지 않는다. §5.5에서 분류한 check만 등록하고, §5.6의 같은 queue/cache를 Memo 생성과 global search가 공유한다. Witness는 재검사 힌트이며 별도의 Proof 객체가 아니다.

```text
propagate(domains, initialEvents, trail):
  queue = dirty registered checks reached through exact use/binding/owner references
  while queue not empty:
    check = pop(queue)
    start = localStamp(check)  # only complete declared dependencies, no global revision
    if start == lastCheckedStamp(check): continue
    if required domains/context coverage not ready: mark pending; continue
    for (variable, value) affected and still active: # native owner or head boundary
      v = support(check, variable=value, domains, facts) # YES / NO / UNKNOWN
      # scoped necessary condition: ∃ common local choices, ∀ projected reachable env
      if v == NO with closed-domain evidence:
        trail.record(variable,value); domains.remove(variable,value); bump revision
        notify exact incoming AND outgoing uses/conditions, including this check
        invalidate dependent witness/conditional-bound entries
    if localStamp(check) == start: remember start
    else: enqueue(check)  # 검사 중 삭제 전에 본 값도 축소 domain에서 재검사
    if closed required rule/head domain empty: return INFEASIBLE
  return STABLE  # arc consistency is not a full plan proof
```

Producer 삭제는 consumers의 support를, consumer 삭제는 producer가 필요한 관계를 만족할 가능성을 재검사한다. Conditional/dead consumer는 unconditionally required edge로 검사하지 않는다. Joint check의 `NO`는 그 check가 읽는 변수에 대해 모든 관련 reachable env에 합법인 **하나의 common assignment**가 없다는 증명이다. `YES`는 해당 필요조건의 support일 뿐 다른 check와 witness를 결합한 전역 계획의 증명이 아니다. `∀env∃assignment(env)`로 통과시키지 않으며 guard 자체는 optimizer 선택변수가 아니다.

UNKNOWN은 동일 facts/domain/readiness 상태에서 무한 재시도하지 않는다. 새 facts snapshot, ready event 또는 domain revision이 생겨야 다시 검사한다. MAY_REACH 반례만으로 NO를 만들 수 없다는 §6.3 규칙은 모든 support checker에 적용한다.

Immutable native universe는 pruning으로 바꾸지 않는다. Root domain에서 proved NO/dominance는 root BitSet만 수정한다. Search branch의 제한은 trail에 기록하고 rollback한다. Backtrack/re-add에서 revision을 증가시켜 오래된 NO cache가 살아나지 않게 한다. Facts의 source withdrawal/recompile은 새 snapshot을 만든다. 이때 필요한 facts 재분석을 candidate rebuild와 혼동하지 않는다.

### 5.4 cache 계약

|cache|key / value|무효화·메모리 원칙|
|---|---|---|
|native|전체 OperatorSignature+pattern → variants/coverage|immutable pure result; catalog/version 변경 시 분리|
|privacy condition|snapshot+op/access role+exact input privacy fact IDs+guard context → verdict/condition|조건부 결과를 무조건 permission으로 캐시 금지|
|movement template|policy version+native layout+datatype+shape class → recipes|source availability/lifetime은 포함하지 않는 순수 template|
|edge legality|snapshot+edge+producer/consumer rule IDs+recipe+binding arm → verdict|적극적 cache 상한; dense K² matrix를 기본 생성하지 않음|
|backedge legality|snapshot의 control-flow policy version+loop/slot+native writer/head IDs+guard/shape context → DIRECT verdict|Entry/일반 body supply 결과와 공유하지 않음. 별도 recipe/retention key 차원 없음|
|binding projection|snapshot+기존 ordered binding roots/positions+call/loop context+predicate read mask → symbolic view|input 순서/중복 보존. 정적 view라 domain 삭제로 RD를 재분석하지 않음; source tuple 표 없음|
|support witness|check+fixed variable/value+관련 domain/readiness stamps → local witness 또는 NO/UNKNOWN|YES의 live 선택·guard 전체 검증 범위 확인; NO는 완결된 정확한 residual scope에서만. 관련 없는 domain 변경은 miss 원인이 아님|
|selected legality|check+완전한 selected scope signature → universal verdict|existential support YES와 구분; 관련 selected rule/supply/head 변경만 invalidation. 모든 의무 등록/coverage를 별도 확인|
|cost term|cost snapshot+term owner+완전한 local assignment/context → exact modeled cost|fusion/activation/shape 누락 금지; 단순 hopId cache 금지|
|DPMemo|§7의 complete subproblem key → EXACT/BOUND/INCOMPLETE result|eviction은 재계산만 유발. cutoff result를 exact minimum으로 승격 금지|

### 5.5 Reaching-Definition-Driven Joint Feasibility: 범위와 생략 조건

**결정:** `실제 use → 정확한 binding → 그 binding을 관찰하는 조건 → 필요한 선택변수` 순서로 검사 범위를 정한다. 별도 전역 joint feasibility pass, consumer별 프로그램 재분석, 모든 ancestor의 선택을 붙인 거대 joint factor는 만들지 않는다. 다만 RD는 **관련성을 찾는 근거**이고, 검사 생략의 근거는 **조건의 정확한 분해**다. “여러 RD가 있으니 joint”, “RD가 하나거나 서로 안 겹치니 독립” 둘 다 틀리다.

#### 현재 소스에서 확인한 사실

|근거|현재 의미 / 재사용 / 바꿀 부분|
|---|---|
|[PlacementProgramFacts.analyzeCfg/CfgAnalysis][rd-cfg]|read별 writer ordinals, function outputs, formal-input 표지를 계산한다. 정확한 occurrence/정의 identity를 보존하지만 집합의 union만으로 branch 공동 환경은 알 수 없다. 현재 모든 predicate의 실행 경로가 정확하게 판별된다고 주장하지 않음|
|[PlacementAnalysis.compiledInputEdge][rd-edge], [cfgDefinitionSourcesInCanonicalOrder][rd-sources]|consumer와 input position, physical 후보의 존재와 독립적인 CFG definition source 관계를 재사용한다. 현재 input-edge API는 matrix/frame 중심이므로 새 semantic-use 등록에는 scalar/control/effect 입력을 HOP/facts에서 보완한다. 같은 source의 반복 입력 위치를 합치지 않음|
|[JointInputAnalysis.tuplesForReads/project][joint-query]|같은 block의 ordered reads를 공동 관측에서 projection. 임의 writer product가 아니다. 기존 exact source/position/call provenance 의미는 보존|
|[cacheConsumerSlice/analyzeFor][rd-slice]|같은 tracked-variable slice의 consumer를 batch/cache한다. 다른 slice는 top-level sequence를 다시 실행하고 observation/환경 및 invocation memo를 재구축할 수 있다. “모든 consumer마다 무조건 전체 분석”은 사실이 아님|
|[JointValueMapRelations.from][rd-relations]|직접 TRead 입력이 둘 이상인 consumer를 중심으로 JointTuple→physical Row를 만든다. FunctionOp 제외와 missing source의 null은 새 설계의 `불필요/합법` 증명이 아님. Mixed/nested HOP 입력과 call boundary는 새 exact binding 경로로 처리|
|[ExactPhysicalModel.addJointFactors][rd-physical]|fixed-map 또는 alignment가 제약하지 않는 consumer의 vacuous factor를 이미 생략한다. 이후 support-owner 확장·receipt/observation 생성이 남는다. 새 설계는 vacuity를 source product 생성 전에 판정하고 해당 표현 자체를 생성하지 않음|

**[해석]** Fixed W/canonical layout은 과거 서로 다른 source map의 공동 alignment 탐색을 상당 부분 불필요하게 만든다. shape별 canonical DIRECT 및 native capability가 입력마다 증명되면 그 alignment 조건의 joint check는 없다. 그러나 conditional privacy, correlated shape, loop 귀납과 compiled-owner 선택까지 자동으로 분리되지는 않는다. **[미검증]** 이 구분으로 LogReg/GLM에서 없어질 check 수와 wall time은 아직 측정하지 않았다.

#### 검사 위치와 최소 범위

검사 단위는 새로운 graph node가 아니라 기존 **input edge, consumer predicate, loop invariant 또는 call binding의 ID**다. 초기 facts 처리에서 각 의무를 한 번 분류한다. `EDGE`는 기존 supply 검사에 맡김, `JOINT`는 비분리 predicate handle 등록, `STATIC_TRUE`는 snapshot의 증명된 조건, `PENDING`은 아직 coverage/의미가 불명확한 의무다. `PENDING`을 `STATIC_TRUE`로 바꾸지 않는다. 삭제된 정의·증명된 dead arm만 제외하며, 단순히 현재 witness가 없거나 기대 빈도0이라는 이유로 의무를 삭제하지 않는다.

|상황|필요한 검사 / joint 생략의 정확한 조건|
|---|---|
|단일 exact definition의 한 input|edge의 source/version/role/privacy/movement 검사. 원래 다변수 predicate가 없으면 joint 없음|
|여러 reaching writers의 한 input|`Select/Head/Call`이 고르는 각 실제 정의에 guarded edge 검사. writer 하나를 비용에 따라 선택하지 않음. 한 edge의 universal binding 검증만으로 충분하면 별도 consumer joint 없음|
|여러 inputs + 고정 Oracle pattern|native/shape/privacy legality가 입력별 조건의 conjunction이고 나머지 coupling이 별도 정확한 owner/boundary 제약에 이미 있으면 edge만 사용. 같은 guard여도 이 분해가 성립하면 별도 joint row가 필요 없음|
|입력 두 개 이상을 함께 보는 조건|해당 input positions의 binding roots와 predicate가 읽는 native/head/supply 선택만 등록. shape equality, 선택 의존 release, 함께 만족해야 하는 native 조건 등. 나머지 입력은 그 input의 edge 검사가 담당|
|반복 입력 `A+A`, multi-output owner, shared producer|한 owner 선택변수를 모든 관련 위치가 참조. 이것만으로 해결되는 equality를 다시 n-ary factor로 만들지 않음. edge propagation이 충분하지 않은 전체 조합은 같은 변수들을 사용하는 global search가 해결|
|Loop|carried slot별 entry/기존 DIRECT backedge/last-exit 의무를 등록. 공동 invariant가 실제로 여러 slot을 관찰할 때만 그 slot들을 joint로 연결. 무관한 loop 전체나 모든 body 변수를 넣지 않음. predicate/control use와 entry grounding은 live-out 유무와 독립적으로 필요|
|Function|actual/formal/return의 정확한 call binding과 공통 compiled owner domain을 재사용. formal 간 비분리 조건이 있을 때만 필요한 actual positions를 projection. body owner가 공유되면 RD가 다른 calls도 같은 선택변수의 incident checks에 연결|
|Shared materialization/lifetime|같은 origin/version/target/scope의 action에 관련 demand만 연결. 비용 공유는 feasibility joint를 만들 이유가 아니지만 global cost coupling은 남음. 실제 co-existence/ownership/hard resource 조건이 있을 때만 해당 hard check를 유지|

Exact RD는 authoritative binding 식에서 사용한다. 현재 CFG가 준 **완전한 may-reaching 상한**에 없는 definition은 제외할 수 있으나, 그 집합에 있다고 실제 공동 경로가 존재한다고 단정할 수 없다. §6.3의 guard precision/UNKNOWN 계약을 따른다. Global dependence 탐지를 위해 모든 pair의 RD-set intersection이나 per-consumer ancestor closure를 계산하지 않는다. 기존 HOP edges와 owner ID의 공유 자체가 연결을 보존한다.

#### 등록과 관련성 projection

```text
registerChecks(facts, memo):
  for use in facts.semanticUsesInProgramOrder():
    # matrix/scalar/metadata/control/effect/return uses; not only final numeric sinks
    binding = facts.exactBinding(use.occurrence, use.inputPosition)
    attach existing edge/backedge check to binding (or PENDING if not closed)
  for site in facts.consumerAndBoundarySites():
    for predicate in requiredConditions(site, memo.nativeConditionIds):
      roots = ordered bindings actually read by predicate
      # no source tuples; repeated input positions remain distinct
      if predicate is already discharged by named edge/owner/static conditions:
        record that coverage disposition; do not allocate a duplicate check
      else:
        attach (siteId, predicateId, rootsRef) to existing site
        register direct domain/owner refs and binding-node reverse references
  close registration only when all use/boundary/condition obligations are covered
```

`requiredConditions`에는 Oracle condition API의 input-position read mask와 loop/call/privacy 조건의 명시 read set이 필요하다. 이 정보가 없는 legacy predicate는 모든 실제 입력을 보수적으로 포함한다. **처음 관찰한 witness에서 읽은 선택만** watch하는 최적화는 기본으로 채택하지 않는다. 다른 branch/rule에서 읽힐 의존성을 놓치기 때문이다. 추후 predicate가 selector 변경을 반드시 통지한다는 계약을 증명한 경우에만 watch set을 더 줄일 수 있다.

선택된 rule에 따라 predicate가 vacuous해질 수 있다. 이 경우도 consumer owner가 등록된 gate dependency이므로 domain 확장/backtrack에서 되살아난다. 모든 immutable variants가 무조건 vacuous라는 증명이 있을 때만 snapshot 전체에서 handle을 생략한다. 정적 facts/식은 intern된 view로 재사용하고 check마다 환경 사본을 만들지 않는다.

### 5.6 국소 support, dependency event, cache와 전체 선택 검증

검사 c의 선택 scope를 `S_c`, 실제 실행환경의 필요한 binding projection을 `π_c(e)`라 하면, rule 값 v의 support는 다음 필요조건이다.

```text
Support_c(v, D) = exists x on S_c from D, x[variable]=v,
                        one expressible guarded supply schedule σ_c:
                   for all e in relevant reachable executions,
                     enabled_c(e) => P_c(x, σ_c, π_c(e))
```

동일 owner/head는 scope 안에서 하나의 변수다. 환경마다 native rule을 새로 고르지 않는다. `σ_c`도 기존 emitter가 표현 가능한 guarded schedule이며 환경별 임의 witness가 아니다. c 밖의 조건은 이 support 검사에서 완화한다. 따라서 **local NO는 전체 선택에서 v가 불가능함을 증명하지만 local YES 여러 개를 붙여 전체 합법이라고 선언할 수는 없다.** `∀e`와 `∃x`의 순서를 바꾸지 않는다.

분해 조건은 선택 x를 고정했을 때 `P_c = ∧ P_edge`임을 증명하는 것이다. 그러면 `∀e ∧ P_edge = ∧ ∀e P_edge`로 branch 식을 그대로 둔 edge 검사가 가능하다. 각 edge가 찾은 서로 다른 x를 합치는 단계는 허용되지 않는다. 공통 x를 찾는 작업은 shared owner를 참조하는 global optimizer가 수행한다. 이것이 “joint handle 생략”과 “joint optimization 생략”의 차이다.

```text
support(c, fixedValue, D):
  if c has incomplete dependency/registration coverage: return UNKNOWN
  if pure static/edge case: use existing evaluator
  if bounded live witness verifies fixedValue and all projected guards: return YES
  visit local native/head/supply choices lazily in heuristic order
    follow existing binding expressions under one guard context
    evaluate only predicate-read positions; prune proven incompatible prefixes
    reuse the same owner selection at repeated positions and calls
    if one common choice/schedule passes every relevant environment: return YES
  if all choices exhausted with complete domains and exact NO evidence: return NO
  return UNKNOWN  # budget, MAY_REACH-only counterexample or incomplete invariant
```

`Select(g,...)`들은 같은 guard evaluation identity를 함께 사용한다. 중첩·독립 guards를 하나로 합치지 않으며 function invocation/loop epoch의 동적 guard는 구별한다. projection에서 빠진 조건은 existentially 숨긴 실행환경으로 해석하되, 생략한 변수와의 상관을 잃은 과대 근사만으로 NO를 만들지 않는다. `LoopHead`를 무한히 unfold하지 않고 기존 base/step/exit invariant로 검사한다. 일반 symbolic guard/shape의 증명 비용은 별도이며 정확히 풀 수 없는 경우 UNKNOWN이다. 모든 joint tuples나 전체 native assignment를 미리 저장하지 않는다.

#### 최소 저장과 갱신 계약

```java
// Sketch of compact arrays/views attached to existing facts sites, not a graph API.
record CheckRef(int siteId, int predicateId, int bindingRootsRef,
                int directSelectionRefsOffset, int directSelectionRefsCount) {}
// Existing definition/use and binding-node parent refs are reused.
int[] checkUseOffsets, checkUses; // reverse refs from direct selections/facts roots
long[] dirtyStamp, lastEvaluatedStamp;
BitSet queued, pending;
// Bounded caches only: local support hints and selected-scope universal verdicts.
```

1. ProgramFacts가 static def-use/binding/call/guard/invariant 식과 입력 순서를 소유한다. Feasibility state가 역참조·queue·active domains·cache를 소유한다. Memo에는 source/검사/환경의 복사본을 넣지 않는다.
2. Native owner/head/supply 선택의 변화는 그 선택의 직접 uses/conditions에 통지한다. Alias/Select/Call/Head의 **기존 식 reverse references**를 통해 필요한 check까지 전달한다. 순환 binding에서는 같은 event wave의 노드를 한 번만 방문한다. 이 traversal은 placement closure 재계산이 아니라 dirty 통지다.
3. 역참조를 따라 전달되는 범위는 그 조건의 보수적이지만 완전한 read set이다. Input/native layout mask가 같아도 shared rule identity/privacy/variant가 바뀌면 기본 구현은 통지한다. “같은 partition이라 영향 없음” 최적화는 해당 predicate의 관찰 동치가 증명된 경우에만 허용한다.
4. Consumer domain 삭제도 같은 relation의 producer 쪽 support를 재검사한다. 한 compiled owner의 모든 call views, joint invariant의 다른 carried slots도 그 관계를 통해 통지한다. 무관 consumer/loop/call은 방문하지 않는다. 여러 삭제 event는 queue에서 check ID로 합친다.
5. 최초 등록/publication에는 각 필수 check를 한 번 방문한다. 이후 **관련 domain·readiness·partial assignment 변경**이 없으면 다시 평가하지 않는다. Facts/guard/shape/privacy의 진실은 snapshot 안에서 불변이므로 별도 전역 guard poller가 없다. Facts 변경·source withdrawal/recompile은 새 snapshot이다. 처음 등록, coverage ready, cache eviction 후 실제 query, 최종 emission 독립 검증은 정당한 최초/재검사 사유이며 domain 변경으로 위장하지 않는다.
6. `dirtyStamp`는 이 역참조를 통해서만 증가한다. 모든 영향을 빠짐없이 전달한다는 전제가 있어야 local revision vector를 대신할 수 있다. Memoized query의 key는 snapshot/check/fixed value와 **정확한 관련 residual state**를 구분하며, 전역 revision을 넣어 무관 변경까지 miss시키지 않는다. 해시만 비교하지 않고 equality를 확인한다. 관련 read set이 미완결이면 cached YES/NO로 확정하지 않는다.
7. Root 삭제와 search-branch 삭제를 분리한다. Undo/re-add/decision 해제도 stamp를 올려 affected checks를 통지한다. UNKNOWN은 같은 local stamp에서 반복 호출하지 않는다. Support witness가 아직 live이고 그 전체 environment 검증 조건이 같으면 재사용할 수 있으나 `existential YES`는 selected-plan verdict cache로 승격하지 않는다.

큰 관계의 support 탐색은 한정된 effort 후 UNKNOWN으로 남겨 global search의 같은 evaluator로 넘긴다. 단순 queue 재진입이나 매 search node 방문마다 동일 domain의 비싼 UNKNOWN 탐색을 재실행하지 않는다. 더 강한 판단은 relevant domain/decision 축소 또는 완전한 selected assignment를 받았을 때 수행한다. 최종 선택 검증 mode는 support-search budget 소진과 다른 query이므로 별도 key를 쓴다.

#### 전역 최적화와 emission 연결

Search region은 RD overlap만으로 분리하지 않는다. HOP/compiled owner, **등록된 hard check와 cost/action term의 직접 incidence**를 기존 separator 계산이 함께 사용한다. 관련성 인덱스가 좁아졌다고 비용 공유나 compiled-body 공통 선택을 복제하지 않는다. 순수 shared-cost term은 joint feasibility predicate가 아니며 해당 term의 dirty 평가만 수행한다.

각 leaf는 dirty/pending인 selected-scope 의무를 검사하고 이미 동일 selected scope에서 검증된 의무는 재사용한다. 모든 의무의 등록·coverage 완결과 unresolved count=0이 필요하며, queue empty만으로 feasible이라고 하지 않는다. 처음 leaf에서는 필요한 의무를 한 번 검사할 수 있지만 전역 source/환경 Cartesian table을 만들지 않는다. 최종 **선택된 plan**의 emission 전에는 exact binding/action/loop invariant를 독립적으로 재검증한다. 이는 전체 가능한 joint assignment의 재탐색이 아니라 선택 결과의 검증이다. 구조/guard 증명이 어려운 경우의 비용은 여전히 남는다.

DPMemo의 최소 key는 §7.5대로 유지한다. RD projection ID는 정적 binding 재사용 key이지 optimum key가 아니다. Region의 외부 owner/head/supply/action 선택과 관련 residual domains가 같아야 exact cost를 재사용한다. Feasibility 체크가 줄어도 native-output cheapest pruning이나 lower-bound 조건은 완화하지 않는다.

### 5.7 정확성·복잡도 및 반례

**Soundness 조건:** (a) 모든 semantic use와 boundary 의무를 등록하거나 증명된 다른 조건에 배정, (b) exact definitions/guards/epochs와 predicate read set의 완전성, (c) 공통 owner 선택, (d) local NO만 삭제, (e) 변경 event의 완전한 전달, (f) 선택된 전체 계획의 모든 의무 확인. 이 조건이면 joint handle을 분해·생략해도 conjunction의 의미는 같다.

**Completeness/optimality 조건:** 이번 개정은 Lspec을 더 좁히지 않는다. Joint support를 덜 강하게 실행하여 UNKNOWN 후보를 남기는 것은 search 양을 바꿀 뿐 합법 계획이나 optimum을 잃지 않는다. May-reaching precision 부족, incomplete native coverage, 증명되지 않은 invariant를 NO로 바꾸지 않으며, 해결되지 않은 leaf가 있으면 기존 typed result 계약을 적용한다. 제약·비용 term과 선택 공간이 동일하므로 exact search 완료 시 같은 optimum/tie다. 불필요한 condition을 생략했다는 주장은 tiny exhaustive **legal-set 비교**로 검증하고 optimum 하나만 비교하지 않는다.

|최소 반례/fixture|금지되는 단순화|정확한 처리|
|---|---|---|
|`A=P; U=abs(A); A=Q; V=abs(A);`|변수명/RD 집합 cache로 두 read 합치기|U는 P의 exact version, V는 Q. Q domain 변화가 U의 binding 검사를 깨우지 않음; 다른 공통 owner 제약이 있다면 그 관계는 별도|
|`if(p){X=A;Y=B;}else{X=C;Y=D;} Z=X+Y;`|RD(X)∩RD(Y)=∅이므로 correlation 버리기|`(A,B)/(C,D)`만 관찰. 고정 pattern/개별 공급으로 legality가 분해되면 joint handle은 없어도 guard binding은 남음. 실제 비분리 privacy/shape 조건이 있으면 두 positions를 함께 검사|
|`A=abs(X); B=rowSums(A); C=colSums(A);` 및 `Z=A+A;`|각 consumer/position이 A의 다른 native rule을 선택|하나의 A owner domain을 모든 edge가 사용. Binary support가 다 남아도 global common assignment 필요; ancestor product는 만들지 않음|
|`A=X; for(i in 1:2){ if(p[i,1]>0){A=A+Y;}else{A=abs(A);} } Z=sum(A);`|Entry의 ROW witness로 불일치 taken-backedge 구제|관련 writer/head와 guard만 DIRECT 검사. Entry/Exit/일반 body movement는 유지. 여러 carried slots의 swap은 그 joint invariant의 scope만 확장|
|`f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);} A=f(P); B=f(Q);`|RD(P)/RD(Q)가 독립이라 호출별 body 선택 분리|call bindings는 분리, compiled body owner는 공유. P/Q별 허용 rule 교집합이 비면 공통 plan 없음. Empty를 숨길 call별 witness 금지|
|관계 `x=y`, `y=z`, `x≠z`, 각 domain={0,1}|모든 local support가 있으므로 FEASIBLE|각 check는 YES여도 공통 assignment 없음. Global search의 선택 검증이 거절. DML capability를 가정하지 않는 작은 solver fixture|
|두 소비자가 같은 `(origin,target,scope)` upload 사용|joint feasibility가 없으므로 tree별 transfer 두 번 과금|hard predicate는 불필요할 수 있지만 shared action cost owner는 한 번 과금하고 separator에 노출|

복잡도 기호: F는 기존 structured facts/binding DAG 크기, E는 exact use 참조 수, J는 필요한 비분리 check 수, I는 추가된 **직접** selection/check incidence, `V(δ)`는 변경 event δ가 방문한 기존 역참조 수, `q_c`는 c의 실제 재평가 수, `w_c`는 c가 읽는 선택 scope 폭, `P_c`는 한 assignment의 symbolic guard/shape/경계 검증 비용이다.

|단계|시간·공간 / 절감과 잔여 비용|
|---|---|
|등록/역색인|기존 facts가 준비된 뒤 primitive 조건 분류와 direct reference 연결에 O(F+E+I+등록한 조건 수), 저장 O(F+E+I+J). 비분리성의 일반 정리 증명은 이 상한에 포함하지 않음: 알려진 decomposition만 쓰고 모르면 JOINT/PENDING|
|정적 projection|동일 binding roots/position/context의 symbolic view 재사용. 모든 source set을 각 consumer에 flatten하지 않음. View key/조건 read mask 저장량 별도; 필요 node traversal 합을 측정|
|변경 통지|O(Σδ V(δ))와 dirty queue 연산. 같은 wave에서 reverse node 중복 방문 제거. 한 변화가 실제 모든 사용에 영향을 주면 O(F+E+I) 방문 가능; 항상 국소 O(1)이라고 주장하지 않음|
|Joint support|O(Σc q_c · d^w_c · P_c) 최악. P_c 자체도 guards/invariant에 따라 지수일 수 있음. Bounded DFS/cache로 tuple 표 resident allocation 제거; 지수 **시간** 보장은 없어지지 않음|
|No-rebuild|RuleMemo/domain 규모는 유지. domain 삭제마다 analyzeFor/CFG/function-slice closure를 재실행하지 않음. 의미상 필요한 초기 RD/shape/privacy SCC와 search의 domain propagation은 유지|
|Resident memory|기존 O(R+L+RM+E+F)에 직접 incidence O(I+J), queue/stamps, bounded witness/selected-verdict cache B와 trail 추가. O(NK+EM)는 기본 Memo/input 목표이며 I·F·B를 숨기지 않음|
|Global solver|같은 선택·비용 공간. Vacuous factors/불필요한 ancestor scope 제거로 폭을 줄일 가능성은 있으나 실제 separator/search 폭은 남는 의미 제약에 의해 결정. Global optimum 증명은 여전히 최악 지수|

새 방식은 `analyzeFor`의 slice별 전체 재순회 및 Environment→snapshot→JointTuple→Row 전개를 새 경로에서 없애며, predicate 분류·역색인·dirty notification 비용을 추가한다. 모든 과거 joint 비용이 사라지는 것은 아니다. §11.8의 cache cold/warm, incremental/full-check, exhaustive reference 비교와 §12.3의 작업량 계측으로 이동한 비용까지 분리해서 확인한다.

## 6. Control Flow와 최소 공동 제약

각 예제의 FED layout은 DML 문법이 아니라 입력 fixture의 source metadata 또는 선택 가능한 native rule에 부여하는 조건이다. 아래 코드가 특정 partition을 자동 생성한다고 주장하지 않는다.

### 6.1 Multi-parent / shared producer

```dml
A = abs(X);
B = rowSums(A);
C = colSums(A);
Z = sum(B) + sum(C);
```

`R_A`는 하나다. 각 edge는 `S_AB`, `S_AC`를 갖는다. 제약은 `compatible(R_A,S_AB,R_B) ∧ compatible(R_A,S_AC,R_C)`와 exact source/version, materialization scope다. Fixture에서 B가 ROW, C가 COL을 요구한다면 native LOCAL A + 두 UPLOAD는 가능하다. Native ROW A + C용 COL 공급은 D1 OFF에서 불가능하다. 두 consumer를 독립 최적화하여 A를 각각 ROW와 COL로 실행했다고 계산하면 잘못이다.

같은 target/scope/version인 두 공급은 action slot 하나를 공유한다. 다른 target은 별도 action이다. `Cost = C(A 한 번)+C(B)+C(C)+Σ활성 action C(action)`이다. 두 edge 각각에 A 실행비와 같은 upload비를 넣지 않는다. 초기에 edge support pruning, 이후 shared `R_A` 및 activation factor를 global search에 유지한다. 현재 대응: [ExactPhysicalModel.build][physical-build], [materialization lifetime][lifetime-cost], [shared-producer test][shared-producer-test].

### 6.2 Transient read/write

```dml
X = A;
U = sum(X);
X = B;
V = sum(X);
```

`X#def1`, `X#def2`는 다르다. U는 def1, V는 def2에만 연결한다. 같은 변수명 또는 같은 partition이라고 두 writer를 교환하면 안 된다. TRead/TWrite는 새로운 kernel choice를 발명하는 연산이 아니라 exact value binding이다. Runtime boundary에서 layout 변환이 필요하면 별도 supply action을 선택한다. 현재 `<CP,LOUT>/<FED,FOUT>` gate는 emitter adapter에 남으며 planner Memo에 LOUT/FOUT enum을 유지할 이유는 없다. [boundary-policy], [transient-facts]

Structured analysis는 statement sequence를 왼쪽부터 한 번 순회하며 `environment[name]=DefinitionExpr`를 갱신한다. Branch는 Select, loop는 head/backedge binder, function은 formal/return binder를 만든다. Read의 여러 reaching writer는 list의 자유 선택이 아니라 binding expression의 runtime-selected 정의다. `DIRECT` alias는 비용 0이고 실제 transfer만 action으로 계산한다. Foreign snapshot/source 또는 아직 실행되지 않은 writer의 값을 선택하면 NO다.

### 6.3 If-Else: guard를 선택하지 않는 symbolic environment

```dml
if (p) { X = A; Y = B; }
else   { X = C; Y = D; }
Z = X + Y;
```

Facts는 `X=Select(p,A,C)`, `Y=Select(p,B,D)`로 보관한다. 허용 환경은 `(A,B)`와 `(C,D)`이며 `(A,D)/(C,B)`는 생성하지 않는다. Iterator가 필요하면 guard DAG를 함께 따라 실제 tuple 하나만 잠시 반환한다. 모든 tuple의 List를 만들지 않는다.

선택된 Z rule는 동일 compiled owner의 하나의 선택이다. 각 arm에서 그 rule의 같은 abstract input requirements를 직접 만족하면 arm별 native layouts를 유지한다. 그렇지 않으면 선택된 arm exit/consumer supply로 맞춘다. 모든 join variable에 하나의 stored layout을 강제하지는 않는다. 다만 **현재 명세의 rule pattern 자체는 정적**이다. 입력 패턴까지 runtime에 따라 바뀌는 map-following native variant는 초기 scope에서 제외한다. D08에 있는 그러한 합법 계획은 추가 지원 제한으로 기록하며 canonical 계약만으로 제외됐다고 주장하지 않는다. 이를 지원하려면 source-free abstract pattern relation을 가진 parametric native rule와 lowering 검증이 필요하지만 이번 최소 API에 미구현 경로로 섞지 않는다.

Then producer와 else producer는 서로 다른 execution owner이므로 ExecType/nativeOutput을 같게 만들 필요가 없다. 예를 들어 then의 native ROW와 else의 native LOCAL은 ROW consumer에 각각 DIRECT와 UPLOAD로 공급할 수 있다. 반대로 LOCAL consumer에는 COLLECT와 DIRECT를 선택한다. Privacy가 특정 arm의 movement를 금지하면 그 rule 조합만 불가이며, join 전체의 transfer를 금지하지 않는다. 이 관계는 기존 guard별 producer→consumer edge 그대로이고 별도 If-Else placement solver/normalization mode를 추가하지 않는다.

합법성은 `∃(R,S정책) ∀reachable e: legal(R,S(e),e)`다. 비용은 branch 활성 가중치로 `wp*Cthen+(1-wp)*Celse+Cjoin`을 계산한다. `wp`는 cost profile/facts의 추정이며 합법성에는 영향을 주지 않는다. 확정된 false guard만 제거한다. Loop에서 바뀌는 p를 초기값만 보고 true로 고정하면 안 된다. [frequency], [loop-predicate-test]

Guard reachability는 `PROVEN_REACHABLE / MAY_REACH / DEAD`를 구분한다. MAY 환경에도 합법인 계획은 sound하다. 그러나 MAY 환경 하나에서 실패했다는 이유만으로 실제 실행계획의 불가능성을 증명할 수는 없다. 이 경우 필요한 predicate 관계를 facts에서 증명하거나 `UNRESOLVED_REACHABILITY`로 남긴다. 일반 DML 산술 predicate의 만족 가능성 전체를 해결하는 새 SMT 계층은 도입하지 않는다. Boolean guard ID 상관과 검증된 compiler facts가 초기 지원 계약이다.

### 6.4 Loop: T>1, 정적 physical contract, live-out

```dml
A = X;
for (i in 1:2) { A = A + Y; }
Z = sum(A);
```

Entry는 X, iteration t의 input은 `Head(t)`, body output은 `Next(t)`다. `Head(0)=X`, `Head(t+1)=Next(t)`, `Exit=Next(T-1)`이다. T>1 전제는 `Exit=X` zero arm만 없앤다. 일반적인 predicate 평가, 정상 종료, exit instructions, Z가 마지막 값을 읽는 의미를 유지한다. [while-runtime], [for-runtime]

```java
// bodyRules/partitionRequirements는 SelectedRule/RuleMemo에서 읽는다.
// executionFrequency는 기존 frequency facts를 참조한다. 중복 저장하지 않는다.
record LoopBoundary(int statementBlock, int[] carriedDefinitionIds,
                    int[] entryBindingExprs, int[] backedgeBindingExprs,
                    int[] exitUses, int invariantFactId, int frequencyFactId,
                    int fixedPhysicalContractId) {}
```

검증은 다음 세 의무다.

```text
Base: entry values exist before loop; compatible(entry supplies, head requirements)
Step: for every enabled state s satisfying joint invariant I(s,t),
      selected body variant + ordinary guarded supplies legal;
      if the backedge is taken, native Next(t) DIRECT-supplies Head(t+1)
      and reestablishes I(s',t+1); no transfer/materialization on this binding
Exit: on actual termination at T>1, final backedge values satisfy all live-out uses
```

Initializer/entry action은 loop invocation당 1회다. Backedge 연결은 T−1번 존재하지만 **그 transfer/action 비용항은 생성하지 않는다: 합법 DIRECT의 전송 비용은 0**이다. Body kernel과 body 내부 일반 movement는 실제 빈도에 따라 계속 과금한다. 마지막 output을 exit에 공급하는 action은 별도로 1회일 수 있다. While predicate는 정상 종료 시 T+1회, for의 bounds/increment expression 평가와 loop control은 실제 평가 위치/빈도로 별도 소유한다. Body/entry/exit의 같은 action 공유는 기존 version/scope 규칙으로 계산한다. Invariant source의 일반 body supply를 loop 밖 scope에 retain하면 1회, updated value의 일반 body supply는 매epoch일 수 있다. Backedge 비용 제거를 body 전체 movement 비용 0으로 확대하지 않는다.

**최소 self-grounding 반례:** `A = A + Y;`를 entry 없는 loop body로 분석한 synthetic facts fixture에서 A input/output의 FED/ROW가 같아도 plan은 불법이다. DML parser가 undefined variable을 먼저 거절하더라도 planner facts/alias SCC에 이 검사를 유지한다. 실제 DML 반례는 위 예제의 X를 canonical ROW, Y를 같은 shape의 canonical COL, 양쪽을 PRIVATE_AGGREGATE source로 설정하고 head와 body에 FED/COL을 선택하는 경우다. Entry X에는 COL 공급이 없는데 backedge COL의 DIRECT 호환만으로 첫 iteration을 정당화하면 오류다. D1 OFF이고 protected raw collect도 금지되므로 entry를 생략해서는 안 된다. [loop-seed], [loop-test], [grounding-test]

**Shape 반례:** `A=X; for(i in 1:2){ A=rbind(A,Y); } Z=sum(A);`에서 ExecType/ROW가 같아도 새 shape의 canonical row boundaries와 native append ranges가 다를 수 있다. **공동 carried 반례:** `(A,B)=(X,Y)`에서 body의 if가 `(A,B)`를 함께 swap/갱신하면 각 변수의 marginal layout 집합만으로 가능한 head tuple를 판단할 수 없다. 하나의 guard와 joint invariant에 두 정의를 함께 연결한다. Body가 loop condition을 바꾸면 initial guard folding은 금지한다.

CFG 전체는 필수가 아니다. Structured DML의 sequence/if/loop/call tree와 위 binders로 대체한다. 단, 이 구조에서 value links를 정확히 만들었다는 전제가 필요하다. 현재 [facts CFG/reaching-definition][cfg] 및 [joint loop fixed point][joint-loop]의 의미를 differential test로 대조한 뒤 제거한다.

현재의 반복은 하나가 아니다. `PlacementProgramFacts`의 reaching-definition 안정화, `PlacementJointInputAnalysis.loopFixedPoint`의 공동 입력 환경 안정화, `PlacementRelationClosure.closePlacementAndFeasibility`의 placement/support 갱신을 구분해야 한다. Closure의 provisional loop 후보는 가능한 continuity를 열어두는 역할이며, 실제 entry seed 및 exact single-partition/source support의 discharge가 없는 cyclic 후보를 최종 근거로 쓰는 것과 다르다. 새 모델은 provisional 객체/Proof route를 유지하지 않고 **entry 존재 + 지연된 backedge + joint induction**으로 이 의미를 대체한다. Domain worklist가 안정화될 때까지 반복하는 것 자체는 남지만 native 후보 재생성은 하지 않는다. [closure], [loop-seed]

Placement 후보 fixed-point는 없앤다. Reaching-definition은 cyclic set의 반복 복제 대신 entry/backedge binders를 직접 만든다. Shape/privacy/guard abstract transfer에 귀납 정보가 필요하면 **loop SCC에만** monotone worklist를 수행한다. 유한 lattice 높이 H가 있으면 O(H·Iloop·transferCost); 그런 상한이 없으면 widening은 합법성 증명만 보수화하고 실패를 infeasible로 바꾸지 않는다. Provided invariant의 base/step/exit가 한 번에 검증되면 이 fixed-point도 생략한다.

유한 T 동안만 합법인 shape/phase를 무한 반복 invariant로 거절하지 않도록 `0≤t<T`, 알려진 trip bound, phase를 invariant에 포함할 수 있다. 이를 증명하지 못하면 `UNRESOLVED_LOOP_INVARIANT`다. 모든 iteration을 전개하지 않는다. Nested loop는 inner summary부터 분석하고 outer carried values와 연결한다. Inner invariant가 outer version에 의존하면 context를 summary key에 포함한다.

T>1은 compiler가 증명한 사실 또는 명시된 실행 전제의 provenance로 facts에 기록한다. 사용자 전제를 다시 임의로 T=0까지 확대하지 않는다. `fixedPhysicalContractId`는 각 loop 정의의 선택된 ExecType/nativeOutput을 고정한다. **실행 중 recompile로 epoch이 바뀌어도 같은 loop invocation의 이 pair는 유지**한다. 새 snapshot은 이 계약을 boundary constraint로 받아 domain을 제한한다. 만족하는 새 plan이 없으면 명시 실패이며 중간 iteration의 배치를 몰래 바꾸지 않는다. Epoch 안에서는 input pattern/native variant까지 고정한다는 추가 제한과 구분한다.

### 6.5 Function call, compiled owner, recompilation

```dml
f = function(matrix[double] X) return (matrix[double] Y) { Y = abs(X); }
A = f(P);
B = f(Q);
Z = sum(A) + sum(B);
```

Nonrecursive call graph는 DAG이므로 구조·binding summary를 bottom-up으로 만든다. 그러나 공통 body의 최적 physical plan을 각 callsite의 최솟값으로 대체할 수는 없다. Formal/return은 exact alias binding이며 실제 layout 변환만 별도 supply다. P/Q의 정의·shape/privacy·activation을 구별한다. `f` 내부의 같은 compiled HOP은 하나의 choice owner, 두 호출의 occurrence는 별개다. [function-invoke], [function-runtime], [function-call-runtime]

수치 반례: 두 body rules의 call별 비용이 `(1,100)`과 `(100,1)`이면 common body 최솟값은 101이다. Call별 독립 최솟값 2는 compiler가 실제로 body를 복제하지 않은 한 emission 불가다. Call 간 같은 invariant actual materialization을 공유한다면 비용은 `min(R,S)[Σcall Cbody + Ccross-call-actions]`로 계산한다. summary는 boundary assignment별 함수이며 입력 context와 무관한 scalar best 값이 아니다.

Memo 방식은 **B에 compiled owner/occurrence view를 더한 것**을 선택한다: native rule templates 재사용 + call별 input/output bindings + 공유 compiled choice. A식 `(HopId,CallContext)` 독립 rule 선택은 실제 compiler clone이 존재하는 경우에만 적용한다. Recompile은 [Recompiler][recompiler]와 [boundary policy][boundary-policy]의 실제 lowering 제한을 지킨다. 새 epoch의 facts/rules/domains/selection을 만들고 old selection을 재사용하지 않는다. Runtime이 불확실한 shape에서 자동으로 다른 FED/CP instruction을 고르게 두고 static-plan 보장을 주장하지 않는다.

Recursion은 `UNSUPPORTED_RECURSION`이다. Function summary는 shape/privacy/guard/compiled policy별 parametric 내용을 재사용할 수 있지만 모든 actuals를 분석하기 전 공통 owner domain을 finalize하지 않는다. 기본 body 공유를 없애는 호출별 specialization은 이번 설계 범위 밖이다.

Global optimality는 **동결된 snapshot의 선언된 실행·비용 모델 전체**에 대한 보장이다. 미래의 데이터 의존 재컴파일 결과까지 예측하여 여러 epoch를 공동 최적화한다는 보장은 아니다. Recompile boundary가 모델을 바꾸면 새 최적화 결과와 cost snapshot을 별도 기록한다. Loop invocation의 fixed physical pair와 외부로 전달되는 live values는 위 경계 계약으로 보존한다. 이 차이를 숨긴 whole-run optimality 주장은 금지한다.

### 6.6 확정된 Loop/If-Else Transfer 정책의 최소 구현 계약

**정책의 검사 대상은 제어 흐름의 value binding이다.** `Next(t)→Head(t+1)`와 `Head(t)→body operator`를 구분한다. 전자는 동일 carried value의 iteration 간 연결, 후자는 실제 연산에 입력을 공급하는 일반 HOP edge다. Header/carrier는 새 계산을 하는 kernel이 아니다. 그 stored layout에 따른 CP/FED carrier 계약이 다음 표의 소비 측이다. 실제 body kernel에 LOCAL 임시 operand를 upload/broadcast하는 일반 입력 준비까지 금지하지 않는다.

|Binding/route|선택과 비용|검사|
|---|---|---|
|Entry→Head(0)|DIRECT 또는 합법 UPLOAD/COLLECT; 실제 생성당 1회|privacy, source 존재, 선택된 head shape/layout|
|Backedge native LOCAL→LOCAL/CP head|암묵적 DIRECT만, supply/action 선택 없음|exact version/slot, privacy, shape|
|Backedge native FED p→FED p head|암묵적 DIRECT만, supply/action 선택 없음|p∈ROW/COL/REP, W·canonical map 직접 호환|
|Backedge LOCAL→FED 또는 FED→LOCAL/CP head|금지. 이미 만들어 둔 별도 copy도 변환 대용으로 선택하지 않음|`BACKEDGE_REQUIRES_TRANSFER` 정책 사유|
|Backedge ROW→COL/REP 등 layout 불일치|금지; 같은 FED exec만으로 충분하지 않음|`BACKEDGE_LAYOUT_MISMATCH`|
|Head→실제 body operator / body HOP→HOP|기존 movement/input preparation 유지|기존 Oracle/privacy/D1/availability/공유 비용|
|Last→외부 consumer|DIRECT 또는 합법 movement; 최종 epoch 기준|정확한 마지막 정의, 외부 요구 partition, privacy|
|If-Else output→join consumer|branch별 DIRECT/합법 movement|shared guard, 정확한 reaching definition, 실제 arm activation 비용|

Producer의 **exec 자체**를 head exec와 같게 강제하지 않는다. FED operator가 native LOCAL을 만들면 LOCAL/CP head에 DIRECT 공급 가능하다. CP producer의 LOCAL output에 available ROW copy가 있다고 FED head와 직접 호환한다고 판정하면 안 된다. Metadata-only input과 function handle alias는 기존 role 계약이며 payload 이동을 발명하지 않는다.

Identity carrier는 자신의 native layout을 임의로 선택하지 않고 alias origin의 실제 representation을 투영한다. Body의 upload/collect copy를 새 native output으로 승격하지 않는다. Identity-only loop에서 origin이 현재 `Head(t)`이면 entry로 grounding된 head representation을 그대로 forward할 수 있다. 이는 허용된 Entry transfer의 결과를 보존하는 경우이며, body normalization을 native로 재명명하는 경우와 다르다. Entry/base 검증 없이 head의 자기 참조만으로 이 representation을 증명할 수 없다.

```text
checkBackedge(L, slot, env, selectedRules):
  if facts.proveThisBackedgeNotTaken(L, env): return YES
  source, aliasRoute = facts.finalDefinitionAndAliasRoute(L, slot, env)
  writer = selected native producer / exact identity carrier
  head = selected head/carrier input contract(L, slot, env)
  require source is the exact previous-iteration value at this binding
  require aliasRoute contains no inserted UPLOAD/COLLECT/normalization
  require (writer.native == LOCAL and head.exec == CP and head.layout == LOCAL)
       or (writer.native == head.layout != LOCAL and head.exec == FED)
  require canonicalMapDirectlyMatches(writer.outputShape, head.inputShape, W)
  require privacy, availability and joint guard/shape invariant
  return YES / proven NO / UNKNOWN

support(binding, assignments):
  if binding.kind == LOOP_BACKEDGE:
      return checkBackedge(binding.loop, binding.slot, binding.env, assignments)
  return ordinaryGuardedSupplySupport(binding, assignments, allowedOffers)
```

Static body rule마다 ExecType/nativeOutput은 전 iteration에 고정한다. 두 branch에 서로 다른 HOP가 있으면 서로 다른 pair를 선택할 수 있다. Loop 안의 branch가 끝나서 **직접 backedge에 연결되는 경우**에는 가능한 각 taken-backedge environment가 위 검사를 만족해야 한다. 이는 일반 If-Else join의 same-output 제한이 아니라 특정 loop edge의 DIRECT 의무다. Branch join 뒤 실제 native 연산이 새 carried value를 만들면 그 새 output을 검사한다.

마지막 iteration 뒤에는 next body가 없으므로 final-only branch output을 가상의 backedge에 맞추지 않는다. `Repeat(t)`는 facts가 표현하는 실행 조건이지 optimizer 선택변수가 아니다. 해당 binding이 실행되지 않는다는 증명이 없으면 검사 의무를 제거하지 않는다. While의 마지막 predicate evaluation 등 실제 control uses는 별도 edge/frequency로 유지한다. T>1은 final definition이 entry와 같다는 뜻이 아니다.

**Alias/중첩 loop:** TRead/TWrite, 단순 대입, exact function-return alias 사이에 transfer를 삽입하고 “일반 body 이동”이라고 이름만 바꿔 carried output을 normalize하는 것은 금지다. 실제 body operator의 별도 input preparation은 허용한다. Optimizer가 dummy identity kernel을 새로 발명해 정책을 우회하지 않는다. 내부 loop의 exit에서 바꾼 copy를 aliases만 통해 외부 backedge에 넘기는 route도 외부 loop의 DIRECT 의무를 함께 검사한다. 반대로 내부 exit 이동 후 실제 외부 body 연산이 새 값을 계산하면 그 native output부터 외부 backedge를 검사한다.

현재 [writeDefinition/aliasInputDefinition][alias-backedge]는 alias provenance를 보존하면서 `_PLACEMENT`를 벗길 수 있다. 정확한 origin 분석은 재사용하되 실제 movement가 있었는지까지 지우면 안 된다. [exactIdentityLoopBackedge][identity-backedge]의 direct TRead→TWrite 검사만으로 모든 branch/nested alias route를 포괄했다고 주장하지 않는다.

|컴포넌트|제거 / 변경|유지|
|---|---|---|
|ProgramFacts|binding arm별 kind와 loop/slot 참조로 구분; alias route에 숨은 boundary action 검사|entry/backedge/last version, guard·shape·privacy·source grounding|
|RuleMemo|backedge 때문에 전역 Offer를 지우지 않음; backedge가 읽는 값은 nativeOutput|source-free native universe와 다른 edge의 movement options|
|Feasibility|backedge recipe/scope 열거 대신 direct relation support; endpoint domain 변경만 enqueue|양방향 propagation, 공동 guard/loop invariant, UNKNOWN/readiness 규칙|
|DP key|backedge recipe/action activation/retention 선택 차원 삭제|carried head layout·관련 native rule/exec, value/guard/shape context, entry/exit 및 일반 body sharing state|
|Cost model|backedge 전용 transfer/creation/retention cost factor 삭제; 불일치는 0-cost 대안이 아니라 hard NO|body compute/일반 transfer, predicate, entry/exit, version별 공유 비용|
|Global optimizer|backedge SupplyChoice 변수와 normalization 대안 삭제|shared producer 선택, 각 reachable environment의 DIRECT hard constraint, 정확한 global search|
|Emission|backedge upload/collect 명령·materialization registry/action slot 생성 없음|DIRECT alias/liveness, Entry/Exit/If-Else/일반 body action 및 atomic validation|

**최소 반례·acceptance fixtures** (layout/선택 rule은 fixture metadata이며 DML 주석이 실행 배치를 강제하지 않는다):

```dml
# F1: entry upload와 final exit collect는 허용한다.
A = X;
for(i in 1:2) { A = abs(A); }
Z = sum(A);
# 선택: X LOCAL, head/body A FED ROW, Z CP LOCAL.
# Entry UPLOAD 1회, backedge DIRECT, final COLLECT 1회.

# F2: 일반 body collect는 계속 허용한다.
A = X;
for(i in 1:2) {
    B = abs(A);
    print(sum(B));
    A = B + 1;
}
Z = sum(A);
# head/A/B ROW, sum(B)는 CP: B→sum(B) COLLECT는 body 일반 edge.

# F3: If-Else join의 서로 다른 native outputs는 합법이다.
if(p) { X = abs(A); } else { X = abs(B); }
Z = sum(X);
# then FED ROW / else CP LOCAL / Z CP: then COLLECT, else DIRECT.
# Z가 ROW 입력 FED이면 then DIRECT, else UPLOAD (privacy 허용 시).
```

F1에서 writer를 native LOCAL로 바꾸고 head를 FED ROW로 유지한 **선택 조합**은 backedge upload 없이는 불가능하므로 거절한다. Writer ROW/head LOCAL 또는 writer ROW/head COL도 거절한다. Entry upload가 존재하거나 LOCAL writer의 availableOutputs에 ROW가 있다는 이유로 통과시키지 않는다. 실제 body computation 뒤 native ROW를 만드는 다른 rule은 정상적으로 탐색한다. F2의 body COLLECT까지 삭제하면 과도한 pruning이다.

F3에서 guard probability를 p로 두면 `CjoinMove = p*CthenMove + (1-p)*CelseMove`이고 shared action이 있으면 기존 activation union을 적용한다. `(A,B)/(C,D)` 공동 정의 fixture도 함께 두어 cross-arm 조합이 생성되지 않음을 검사한다. PRIVATE_AGGREGATE raw collect가 필요한 arm은 그 조합만 거절하며 aggregate native LOCAL 대안은 Oracle가 지원하면 유지한다.

추가 NEW fixture는 (a) 같은-layout backedge materialization도 미생성, (b) transparent alias/`_PLACEMENT` 우회 거절, (c) branch 안의 한 backedge만 불일치, (d) final-only arm의 Exit transfer 허용, (e) nested inner-exit/outer-backedge 분류, (f) shape 변화 후 canonical DIRECT 검사, (g) stale iteration copy 거절, (h) metadata/일반 body input preparation 유지다. Current code는 이 새 정책을 아직 구현하지 않았다.

## 7. Cost, Safe Dominance, DP, Global Optimization

### 7.1 비용의 단일 소유권

동결된 목적함수는 다음과 같다.

```text
C(plan) = Σ executionOwner o  frequency(o) * nativeCost(o, selectedRule, context)
        + Σ selectedMaterialization a activation(a, guards, versions, scope) * moveCost(a)
        + Σ verified nonseparable/fusion terms exactly once
```

두 번째 합의 a에는 일반 body/branch/entry/exit supplies만 들어간다. LOOP_BACKEDGE는 action이 없으므로 `CbackedgeTransfer=0`을 나타내는 factor조차 만들 필요가 없다. Direct incompatibility는 hard legality 실패다. Entry/Exit에서 각자 합법인 이동을 허용한다는 것은 같은 binding의 금지된 FED→LOCAL→FED D1 route를 허용한다는 뜻이 아니다. 반대로 값의 전체 수명에서 이동을 무조건 한 번으로 제한하여 합법 entry와 final exit 중 하나를 지우지도 않는다.

Native operator 내부의 broadcast/sliced PUT/reduction/forced-local GET은 native cost에 귀속한다. 외부 value를 공급하는 명시 movement만 두 번째 항이다. 같은 traffic을 양쪽에 넣지 않는다. Fused MMChain 같은 실제 lowering은 exclusive inner kernels를 fused owner로 옮긴다. Shared child는 사라지지 않으므로 독립 비용을 보존한다. [compute-owner], [fusion-test], [native-cost]

기존 `PlacementCostSemantics`, primitive `FederatedCostModel` 함수, execution frequency, `ExactMaterializationActivation`의 guard union/lifetime 의미를 우선 재사용한다. [activation]의 conservative union은 실제 probability의 exact 계산이라는 주장이 아니라 현재 cost estimator의 일부다. 새 구현의 exact란 이 동결 함수의 exact 최소값이다. Branch 확률/iteration 추정치를 바꾸면 별도 cost snapshot이며 성능 개선과 섞지 않는다.

Frozen binary64 primitive term을 exact dyadic 값으로 비교/합산하는 기존 utility를 재사용하고 최종 표시값만 정해진 방식으로 반올림한다. Legacy double 누산과 비교할 때 term mapping/rounding 차이를 별도 기록한다. NaN/미정 cost는 0이 아니라 `UNRESOLVED_COST`다. 기존 raw-bit parity가 요구되는 fixture에서는 동일 term decomposition/누산 계약으로 검증한다. Tie는 source 객체 주소가 아닌 stable semantic rule/edge/action ID 순서다. Old tie가 다른 경우 최적 cost 동률의 다른 계획임을 명시하고, old fingerprint와 같다고 위장하지 않는다. [dyadic]

### 7.2 Supply schedule과 lifetime: 비용과 emitter가 같은 것을 선택한다

일반/Entry/Exit supply edge는 recipe와 **retention region**을 선택한다. LOOP_BACKEDGE에는 이 선택을 만들지 않는다. Region은 기존 structured statement region tree의 노드이며 임의 새 CFG vertex를 만들지 않는다. Allowed region은 demand의 ancestor이고, source definition이 해당 guard에서 첫 생성·사용보다 선행하며, 실제 retention 구간의 exact value/version·privacy·shape와 lowering scope가 합법이어야 한다. Source가 region 진입 때 이미 존재할 필요는 없다. First-demand 때 존재하면 된다. Region 안에서 같은 static definition이 반복 실행되면 새 dynamic epoch으로 구분하고 재사용하지 않는다. 대안 region 수가 M 외에 S가 되므로 복잡도에서 숨기지 않는다.

Action의 정적 identity는 `(snapshot, originValueId, target, recipe, retentionRegion, loweringVariant)`다. 같은 identity를 선택한 edges는 하나의 slot을 공유한다. Branch별 first-use site들은 그 slot의 guarded creation sites이며 별도 source product가 아니다. Runtime key는 여기에 `region invocation token, dynamic definition epoch, actual object uniqueId/mutationVersion, actual shape`를 포함한다. 서로 다른 logical definitions는 object가 우연히 같아도 merge하지 않는다. 검증된 transparent alias만 같은 origin을 공유한다.

규범 schedule:

1. Region 진입은 빈 selected-action slots만 준비한다. 실제 upload/collect를 선실행하지 않는다.
2. 첫 **실제** guarded demand에서 정확한 source와 모든 guards를 검증하고 action을 한 번 실행한다.
3. 같은 dynamic value epoch의 후속 demand는 생성된 output을 사용한다. COLLECT는 distinct LOCAL output, UPLOAD는 distinct canonical FED output이다.
4. Region 종료 또는 source version invalidation 시 해당 slot을 release한다. Nested loop/call invocation token을 섞지 않는다.
5. Recompile은 snapshot이 바뀌므로 old slots/selection을 새 plan의 권위로 쓰지 않는다. 필요한 LOCAL/FED 결과는 explicit boundary binding으로 넘긴다.

동적 definition epoch는 현재 loop counter 자체가 아니라 **source definition이 실제 실행된 횟수/문맥**이다. Loop-invariant source는 반복해도 같은 epoch다. Slot은 `EMPTY→CREATING→READY`로 바뀌고 실패 시 `FAILED`이며 output을 publish하지 않는다. Scope 종료는 exception/return에도 cleanup한다. 선택된 action output이 caller/outer region으로 alias-export되면 기존 variable liveness에 ownership reference를 이전하고 pin한다. Slot 종료는 그 slot의 reference만 해제하며 다른 live alias가 있는 remote value에 무조건 rmvar하지 않는다. Demand까지 source도 liveness로 pin한다.

최소 신규 runtime helper는 root execution-owned `PlannedSupplyStore` 하나와 context별 region token stack이다. Function은 새 ExecutionContext를 만들므로 store handle과 허용된 ancestor scope를 명시 전달해야 한다. 임의 cross-call cache가 아니라 선택된 action IDs만 등록한다. 반환·예외 cleanup, alias ownership, exact input pinning 테스트 전에는 해당 scope를 advertise하지 않는다. [function-call-runtime]

Scope choice가 cost/lifetime에 영향을 주면 optimizer 변수로 남긴다. 모든 consumer subsets를 미리 열거하지 않는다. Edge가 고른 scope별 demanded action key 집합의 activation을 lazy factor로 계산한다. Same target라도 서로 다른 region에 생성된 복사본은 따로 계산한다. 한 scope의 first demand를 여러 번 비용으로 세지 않는다. Guard union이 축약되지 않으면 symbolic expression을 재사용하며, exact estimator 평가의 어려움을 숨기지 않는다.

**현재 runtime과 차이:** planned `FederatedFoutMaterialize`의 `sharing=single`은 output liveness 공유이지 위 cross-iteration/call cache 구현이 아니다. [fout-lop], [fout-cache] 기존 동일 실행 내 exact rewiring부터 지원하고, scope slot lowering가 검증된 뒤 invariant loop/cross-call scopes를 활성화한다. 그 전의 rollout은 명시적인 좁은 Lspec이고, 기존 shared-cost plans 전체와 parity를 선언할 수 없다. 이는 §13 blocker에 포함한다.

메모리 상한을 사용자가 실행 제약으로 주면 scope별 live bytes를 hard constraint로 모델링해야 한다. 주어지지 않은 임의 peak cap으로 계획을 pruning하지 않는다. Compiler cache budget과 runtime materialization lifetime/memory는 서로 다른 개념이다.

### 7.3 안전한 cost dominance의 최대 범위

전역적으로 r을 q로 바꿔도 안전하려면 모든 외부 연장 e에 대해

```text
legal(r,e) ⇒ legal(q,e)
(cost(q,e), tie(q,e)) ≤lex (cost(r,e), tie(r,e))
and observable value, privacy, availability, effects, emission remain equivalent.
```

이 조건 자체는 일반적으로 global 문제만큼 어렵다. 실제 초기 구현은 (a) 동일 continuation signature의 exact duplicate interning, (b) finite recipe/condition 포함 관계로 **증명된** subsumption, (c) 완전한 boundary assignment 안의 최소값/Pareto 제거만 수행한다. Signature는 input requirements/roles, native variant의 output/effects, conditional offers, compiled/compute ownership, source/lifetime interface, joint participation, cost context를 포함한다. Partition label만 hash하지 않는다.

`r.cost<=q.cost`가 shape/trip/frequency에 의존하면 모든 허용 context에서 증명하거나 그 context의 DPMemo 안에서만 제거한다. 예: setup/iteration 비용 (100,1)과 (0,5)는 T=2이면 102 대10, T=100이면 200 대500이다. Loop physical state 고정은 T 의존을 지우지 않는다.

**공유 producer 비용 반례:** P의 LOCAL/ROW 실행비는 0/10, H는 LOCAL input일 때 1, ROW input일 때 4이며 둘 다 LOCAL output, Q는 ROW input을 요구하고 자체비용 0, layout 이동은100이다. `(PLOCAL,Hlocal)=101`, `(PLOCAL,Hrow)=104`, `(PROW,Hlocal)=111`, `(PROW,Hrow)=14`다. H의 locally cheapest rule만 남기면 global optimum 14를 잃는다. 이 값은 알고리즘 fixture용 수치이며 runtime 예측값이 아니다. [cost-prune-test]

Rule universe는 지우지 않는다. Root domain의 permanent dominance에는 증명 이유를 기록하고, boundary/branch-local dominance는 해당 DP cell/trail 안에서만 적용한다. Oracle 직후에는 source와 무관한 duplicate/native-cost domination만 가능하다. Feasibility 후에는 동등한 interface의 dominated rule, tree DP에서는 고정 required supply의 최소값, global search에서는 certified lower-bound cut을 추가한다.

### 7.4 Local/tree DP의 정확한 상태와 점화식

닫힌 tree 조건: 서로 다른 child subtree의 owner/value/action 집합이 겹치지 않고, 외부 shared producer·fusion·guard correlation·call owner·retention action과 결합되지 않는다. 동일 child가 input position 두 개에 반복되거나 diamond descendant가 있으면 이 초기 tree DP에서 제외한다. 나중에 같은 child의 demand mask로 정확히 요약하는 확장은 가능하지만 기본 구현이 아니다.

고정 context θ에서 q는 consumer가 요구하는 **available supply partition**이다.

```text
Dθ(h,q) = min over native rule r and legal offer o with target q:
              OwnNativeCostθ(h,r)
            + Σ input positions i Dθ(child_i, requiredPartition(r,i))
            + OwnMovementCostθ(h,r,o)
```

Metadata input은 role-specific dependency 비용으로 대체한다. Source base case는 실제 available native state와 합법 primitive movement다. 각 cost term이 이 subtree에만 소유되어 있고 child term이 서로 독립이라는 것이 점화식의 전제다. Pairwise producer/consumer-specific native cost가 추가로 있으면 `q`만으로 부족하며 boundary state로 승격한다.

`LocalKey=(snapshot,regionRoot,requiredSupply,contextId)`; contextId는 shape/privacy/cost profile/call activation/loop frequency·compiled owner 조건을 intern한다. Snapshot 안에서 region이 이 context를 유일하게 정하면 key에서 중복 필드를 뺀다. Value는 `(EXACT cost, ruleId, offerId, childKey/backpointers, canonicalTie)`다. RuleMemo에 비용으로 선택된 candidate를 다시 넣지 않는다.

Root부터 저장된 rule/offer와 child backpointer를 따라 reconstruction한다. NativeOutput별 cheapest rule 하나가 안전한 경우는 그 output이 완전한 external interface이고 모든 alternatives의 effects/privacy/movements/lifetime가 동등하며 독립 subtree 최소비용까지 이미 합산된 경우다. 보통 필요한 것은 native output보다 requested supply 또는 complete boundary state다.

### 7.5 Global/regional DP의 최소 Key/Value

Region은 incidence를 따라 외부 coupling을 완전히 separator로 노출할 수 있을 때만 분리한다. §5.5에서 필요한 edge/boundary/joint 조건만 등록하되 shared owner와 cost/action incidence는 함께 사용한다. RD 집합의 분리만으로 region을 나누지 않는다. 기본은 maximal closed-tree islands+나머지 한 global residual region이다. 거대한 최적 tree decomposition을 사전에 구하는 별도 optimizer는 만들지 않는다. 싸게 얻는 articulation/separator만 활용한다.

```text
V(U,b,a) = min over internal choices consistent with boundary b and exposed a:
             Σ terms owned by U + Σ child-region exact messages

GlobalKey = (snapshot, regionId, boundaryAssignmentId, residualDomainId?)
Value = (EXACT | BOUND | INCOMPLETE, modeledCost/lowerBound,
         exposedActionState if not determined by boundary, selectedBackpointers)
```

Boundary에는 외부와 공유하는 compiled rule 선택, exact reaching definition/binding arm 조건, 공급/retention 선택, 외부 비용에 영향을 주는 action activation interface가 필요하다. Guard 식과 value IDs가 region에 정적으로 고정되면 key에 문자열로 반복 넣지 않고 region facts를 참조한다. Runtime guard truth는 solver variable이 아니다. 모든 필요한 정보가 selected supply로 결정되면 별도 `SharedMaterializationState`를 저장하지 않는다.

Loop boundary key에서는 backedge transfer recipe, materialization activation, retention region 차원을 삭제한다. 하지만 carried head 계약, writer의 native rule/slot, joint guard/shape/privacy와 entry/exit·일반 body action의 외부 영향은 남긴다. HOP별 cheapest 또는 `Partition` 하나로 key를 더 축소할 근거가 되지는 않는다. If-Else는 기존 guard별 supply/action boundary state를 그대로 사용한다. 정책 변경 전 DPMemo는 새 policy version/snapshot으로 무효화한다.

내부 선택이 외부에서 action을 활성화하는지에 영향을 주면 그 activation을 interface `a`로 노출한다. `a`별 최솟값을 유지하고, 외부 비용을 알기 전에 cheapest `a` 하나를 고르지 않는다. 동일 value·guard·lifetime이며 다른 coupling이 없을 때만 최대 16개 output demand masks로 요약 가능하다.

각 hard/cost term은 scope의 least containing region **한 곳**이 소유한다. Child message에 외부 shared computation/transfer 비용을 넣고 parent에서 다시 더하지 않는다. Cross-boundary guard/owner relation을 잃는 분리는 금지한다.

`residualDomainId` 생략은 outer search가 region 내부 변수를 직접 제한하지 않고, root domain+boundary만으로 내부 domain이 유일하게 결정될 때만 허용한다. 그렇지 않으면 exact BitSet signature를 key에 포함하고 hash collision을 equality로 확인한다. `{r1 cost1,r2 cost4}`의 answer1을 restricted `{r2}`에 answer1로 재사용하면 오류다. Broader-domain minimum은 restricted query의 lower bound로만 사용할 수 있다.

### 7.6 하나의 exact optimizer와 안전한 lower bound

```text
plan(program):
  facts = analyzeStructuredProgramAndFreeze()
  memo, rootDomains, checks = buildRuleMemoAndRegisterChecks(facts)
  # Registration/publication use the same incremental queue; no joint-analysis pass.
  propagate(rootDomains, remainingPublicationEvents)
  applyCertifiedRootDominance(rootDomains)
  regions = closedTreesAndCheapSeparators(facts, memo, rootDomains)
  incumbent = legalSeedIfAvailable()  # 기존 Local은 선택적 seed, 정답 증명 아님
  result = solve(rootRegion, emptyBoundary, rootDomains, incumbent)
  if result.status != OPTIMAL: return typed status; do not silently emit fallback
  plan = reconstruct(result.backpointers)
  validateAllReachableBindingsPrivacyScopesAndCost(plan)
  return transactionalEmit(plan)

solve(U,b,D,incumbent):
  key = completeKey(U,b,D)
  if DPMemo[key].kind == EXACT: return cached exact result
  propagate(D, eventsFromBoundaryOrDecision)
  if isClosedTree(U,b):
      result = computeTreeDPWithReadinessAndConditionalLegality()
      cache EXACT only if coverage and all necessary conditions discharged
      return result  # EXACT / BOUND / INCOMPLETE
  if closed empty domain: return exact INFEASIBLE
  lb = ownedTermRelaxationAndCertifiedChildBounds(U,b,D)
  if lb > incumbent.cost or certifiedTieCannotImprove(lb,incumbent):
      return BOUND(lb)                 # not an EXACT cell minimum
  if all choices fixed:
      v = validateDirtySelectedChecksAndCoverage() # exact scope cache, §5.6
      if v == YES: evaluate exact frozen cost; update incumbent; return exact leaf
      if v == NO: return exact infeasible leaf
      return INCOMPLETE(reason=UNKNOWN)
  choose MRV variable, break ties by high incident/shared impact
  visit values in heuristic cost order without dropping any legal value
  for value:
      push trail; assign; recursively solve affected residual/components; undo
      combine only terms owned here; preserve exact/unknown/bound status
  if every branch closed with exact minima or certified no-better bounds:
      certify root optimum; cache EXACT only where cell minimum is known
  else return incumbent + certified lower bound + INCOMPLETE
```

Lower bound는 모든 미지 cost term의 검증된 minimum을 한 번씩 더하는 relaxation 또는 complete tree message다. Shared term을 여러 tree에서 복제하여 더하면 LB가 실제 optimum보다 커질 수 있다. Nonnegative가 증명된 미활성 materialization은 0 lower bound 가능하다. 검증되지 않은 lazy factor/negative adjustment에 0을 가정하지 않는다. 그런 term은 exact conditional minimum, 알려진 lower bound, 또는 −∞를 사용한다. Fusion을 negative credit로 표현하기보다 owner를 분리해 중복을 없애되 기존 의미와 대조한다. [lazy-factor-test]

Incumbent로 잘린 cell은 root optimum 증명에 기여할 수 있지만 그 cell의 exact minimum은 모를 수 있다. 이를 DPMemo의 EXACT로 저장하지 않는다. Equal-cost plan의 deterministic tie를 요구하면 `LB == incumbent`만으로 잘라서는 안 된다. Leaf의 unresolved legality가 남아 있으면, 그 branch가 certified bound로 더 좋을 수 없음이 증명되지 않는 한 OPTIMAL을 선언할 수 없다.

Separator 폭/추정 key cardinality가 크면 region 분할과 cache를 생략하고 동일 DFS solver로 진행한다. Cache memory 한도는 values/key interning/backpointers 모두 포함하며 eviction은 completeness를 바꾸지 않는다. Incumbent reconstruction을 pin하거나 완전한 선택 배열을 별도로 보존한다. 같은 상태가 eviction 뒤 재계산될 수 있으므로 hit 비율만 성능으로 제시하지 않는다.

### 7.7 작은 end-to-end fixture

```dml
A = abs(X);       # fixture: A는 LOCAL로 한 번 계산됨
u = sum(A);
v = max(A);
print(u + v);
```

Oracle fixture는 sum/max 각각 CP LOCAL input 비용7, FED ROW input/native LOCAL 비용1을 제공하고 A의 ROW upload 비용8이다. Privacy가 upload와 aggregate 결과를 허용한다고 고정한다.

|선택|계산|모델 비용|
|---|---|---:|
|CP / CP|7+7|14|
|CP / FED|7+1+8|16|
|FED / CP|1+7+8|16|
|FED / FED|1+1+공유 upload 8|10|

Memo는 operator당 native rules만 저장한다. A의 upload Offer는 실행되지 않은 선택지다. 두 edge가 같은 action slot을 선택하면 upload는 1회, scalar native output은 LOCAL이다. Pruning은 네 조합을 임의로 지우지 않으며 global search가 비용10을 선택한다. Reconstruction은 A 실행→ROW materialization 하나→두 FED aggregates→LOCAL scalar 합으로 복원한다. 이 수치는 설계 fixture이고 현 runtime 실측값이 아니다.

## 8. Runtime Contract, Reconstruction, Emission

### 8.1 Canonical map 계약

Ordered W는 endpoint와 session execution ownership을 포함하는 immutable descriptor다. 단순 worker 수만으로 identity를 판단하지 않는다. Descriptor ID는 runtime Program/session 수명 안에서 해석 가능해야 한다. Planner/runtime이 같은 geometry version과 산식을 사용한다. Shape는 실제 input dimensions와 계획의 guard를 대조한다. Unknown runtime shape가 guard를 위반하면 명시 실패/새 계획 요청이며 암묵적인 CP 실행이나 다른 pool 선택은 없다.

ROW/COL은 W개의 비어 있지 않은 contiguous ranges가 전체 shape를 중복 없이 덮는다. REP는 모든 worker에 full shape의 **동일 논리 값**이 있다. Source REP를 선언하려면 신뢰 가능한 ingress metadata/생성 provenance 또는 별도 검증이 필요하다. 같은 shape만으로 replica equality를 추정하지 않는다. 모든 collect에서 전체 payload equality를 검사하는 비싼 설계는 기본으로 채택하지 않으며 ingress/native 생성 계약과 parity 테스트로 보장한다.

### 8.2 구체적인 명령 변경 seam

현재 명령은 `FED fed_fout input anchorOperand output fType [sharing=single]`이다. [fout-lop], [fout-parse] 새 경로는 기존 parser와 충돌하지 않는 명시 버전/operand contract를 추가한다.

```text
FED fed_fout_canonical input output poolDescriptorId targetPartition
                       geometryVersion plannedActionId retentionScopeId
```

`FEDFoutInstruction` 또는 같은 parser가 위 opcode를 명확히 구별해 처리한다. `materializeLocalToPool(input, descriptor, canonicalRanges, mapType)`로 기존 PUT/sliced-upload transport를 재사용한다. Fake data anchor를 만들지 않는다. Legacy opcode는 old planner용으로 보존한다. W1 REP는 FULL, W>1 REP는 BROADCAST로 lower한다. 한 worker에만 존재하는 FULL을 W>1 REP라고 relabel하지 않는다.

COLLECT는 기존 planner-authorized synchronous `PrefetchCPInstruction` 경로를 재사용한다. 일반 asynchronous prefetch hint로 대체하지 않는다. `Dag.insertLocalMaterializeLops`가 distinct output과 exact consumer input position을 rewire하는 구조를 유지하되 새 `SelectedSupply`를 입력으로 받는다. [dag-collect], [prefetch]

**REP 보완:** 현재 `MatrixObject.readBlobFromFederated`는 PART만 특수 aggregate하고 그 외에는 `bindResponses`를 사용한다. `requestFederatedData`는 모든 worker GET, `bindResponses`는 비중첩 ranges를 전제로 NNZ를 합산한다. Full replica ranges에서는 NNZ가 중복 합산되는 정적 경로를 확인했다. 실행 실패를 재현한 것은 아니다. [matrix-collect], [all-worker-get], [bind-responses]

새 REP collect는 정확한 W/full ranges, 모든 response success/shape를 확인한 뒤 deterministic worker의 complete block을 안전한 ownership으로 publish한다. Matrix NNZ는 선택 block의 정확한 값이고 다른 replicas와 합하지 않는다. Traffic 비용은 W개 GET이다. Frame REP도 [FrameObject.readBlobFromFederated][frame-collect]의 partition schema merge 대신 schema/column names/metadata/payload를 함께 보존하는 replica-aware 경로가 필요하다. Frame에 matrix NNZ 오류가 있다고 주장하지 않는다.

### 8.3 Native operator별 canonical 적합성

Oracle는 단순 FType label뿐 아니라 **native map effect**를 검증한다. 모든 operator를 배제하는 것이 아니라 아래 path/shape 조건을 판별한다.

|현재 path / 근거|canonical 위험|단일 처리 원칙|
|---|---|---|
|Transpose, `ReorgFEDInstruction` [transpose-runtime]|ROW input이 COL output이 되는 native map 변환|동일 W, swapped shape/ranges가 canonical이면 허용. 새 논리 값 생성이므로 금지 RESHARD가 아님|
|Right indexing, [index-runtime]|worker subset/range filter, shrink output; scalar는 LOCAL일 수 있음|모든 W 유지와 canonical output을 증명한 variant 또는 FED/native LOCAL만 허용. subset path 제외|
|Append, [append-runtime]|map bind/offset, 같은 axis의 canonical balance가 깨질 수 있음|비append 축 shape와 output ranges를 검증한 variants만. 알려진 CP/native LOCAL 대안은 명시 rule로 가능|
|`rmempty`, [rmempty-runtime]|data-dependent partition counts/ranges|output canonicality를 facts로 증명하지 못하면 unresolved/미지원 variant. label ROW만으로 publish 금지|
|Roll/reshape 성격의 reorg, [roll-runtime]|boundary 이동·worker/range 대응 변화|map effect가 unique canonical geometry와 같다는 검증 필요; generic relabel 금지|
|Matmul, [mm-runtime]|BROADCAST operands, reduction/PART, forced FOUT variants|PART/OTHER는 persistent state에서 제외. 검증된 native REP/ROW/COL/LOCAL variants는 유지; 내부 reduction은 kernel cost에 포함|
|Aggregate unary, [agg-runtime]|partial/reduced 결과와 native LOCAL/FED의 차이|Oracle가 검증한 output과 internal collect를 구분. raw protected operand collect로 대체 금지|
|FULL/BROADCAST unary/binary [broadcast-compat], [unary-broadcast]|`UnaryElemwiseRule.caps`는 BROADCAST를 `cpCaps(BROADCAST_CONSTRAINT)`로 거절하고 FULL은 FED 가능. 단순 REP 치환은 abs 등의 W>1 capability를 발명할 수 있음|REP adapter는 W1/W>1 variant별 actual opcode parity 필요. 현재 거절 path를 검증 없이 개방하지 않음. temporary LOCAL sliced operand를 REP라고 저장 금지|
|Transformencode/multi-return [multi-runtime], [multi-owner]|FED matrix+LOCAL metadata, 공통 owner coupling|slot layout vector 유지. CP QR/eigen 결과의 upload가 owner를 FED로 바꾸지 않도록 수정 전 독립 carrier upload 제외|
|Current local-to-anchor upload [canonical-ranges]|같은 shape이면 uneven anchor mapping 유지; rows/cols<W 거절|새 allocator의 canonical 산식 사용, 빈 partition 제한 명시. 기존 range-preserve test는 legacy용 보존|
|Recompile/TR-TW [boundary-policy]|현재 independent CP→FOUT 또는 FED→LOUT boundary 제한|허용된 별도 action lowering가 있는 위치만 Offer; 새로운 layout enum만으로 gate를 해제하지 않음|

여기서 CP alternative는 privacy·runtime·source collect까지 검증되어 Memo에 들어 있는 정상 rule이다. 선택 실패 후 몰래 실행하는 fallback이 아니다. 지원하지 않는 native path를 canonical normalization이라고 부르며 추가 runtime movement를 숨기지 않는다. Direct RESHARD는 어떤 경우에도 만들지 않는다.

### 8.4 선택 검증과 atomic emission

```text
validate(plan):
  same snapshot, live occurrences/definition versions, completed owner domains
  one selected native rule per actual compiled owner; all output slots consistent
  one selected/derived head contract per loop carried slot; same across backedges
  every exact input position bound to actual reachable definition
  all guard environments, loop base/step/exit, call actual/formal/return legal
  each taken loop backedge is native DIRECT; no transfer action on its alias route
  privacy and ingress/native canonical-map predicates discharged
  each supply recipe/version/region has executable creation and cleanup
  every cost term owned once; reconstructed cost equals selected model value
  loweringVariantIds all supported, recompile scope compatible

emit(plan):
  prebuild lowering schedule and registry delta without mutating HOPs
  validate entire delta; open transaction/undo log
  set CP/FED + legacy runtime output mode only at adapter boundary
  install native instructions and exact supply rewires/action slots
  consume old/new projection authority only after successful lowering
  on any failure restore all owned HOP/registry/action-store surfaces
```

계획 검증은 Anchor/Proof 객체 존재 여부가 아니라 origin→creation→use의 실제 시간 선행, guarded availability, 귀납적 loop binding을 검사한다. Loop-carried edge는 이전 iteration이라는 explicit delay를 가져야 하며 intra-iteration cycle이 source 없이 자기 자신을 뒷받침하지 못한다. Current `PlacementEmissionTransaction.emitInternal/prevalidate`와 `Dag`의 transactional authority consumption을 보존·수정한다. [emission], [emission-test]

## 9. Correctness Argument와 Complexity

### 9.1 보장별 의무

|보장|논증 / 반드시 검증할 조건|
|---|---|
|Soundness|Oracle native variant가 runtime으로 실행 가능; 각 reachable binding이 정확하고 privacy/canonicality를 만족; 모든 의무를 exact use/조건에 등록하고 dependency event 누락 없음; entry가 grounding하고 step이 invariant 보존; emitter가 선택 action만 실행. 각각 만족하면 structured program에 대한 귀납으로 실행 의미 보존|
|Completeness within Lspec|지원 catalog의 모든 native variants와 일반/Entry/Exit primitive offers·지원 retention scopes가 포함되고 backedge는 DIRECT를 표현. NO 또는 proven dominance만 제거하며 search가 모든 남은 assignment를 검사. 새 backedge 정책으로 제외한 Lcanonical 계획과 다른 추가 scope 제외를 별도 기록|
|Cost preservation|기존 비용을 semantic compute/action owner에 대응시키고 term/activation/frequency/lifetime를 한 번씩 계산. Cache/DP는 완전한 context/boundary에서만 같은 함수를 재사용. 실제 wall-clock prediction의 정확성과 별개|
|Emission preservation|native slot/forced mode, exact edge input position, supply creation/cleanup/export, function compiled owner를 그대로 lower. Shape/W/epoch mismatch는 실패. Runtime 숫자·instruction·traffic 검증 필요|
|Global optimality|sound domains, complete search 또는 exact separator messages, admissible LB, tie-aware cuts, exact objective. 자원·UNKNOWN·coverage 부족이면 이를 증명한 것으로 표시하지 않음|

이들은 설계의 조건부 논증이지 새 구현의 증명 완료가 아니다. Oracle/runtime 검증 부족을 `VALIDATED`로 표기하지 않는다. Old legal-plan exporter가 proof object를 사용한다는 이유로 새 구조에도 같은 proof를 요구하지 않는다. Old exporter 자체의 completeness도 별도 검증한다.

### 9.2 단계별 worst-case 비용

N=정적 HOP occurrences, R=전체 native rules(≈NK), L=모든 rule input-pattern 길이 합, E=guarded def-use/input bindings, M=primitive movement 수, S=가능 retention scopes/edge, F=ProgramFacts 표현 크기, I=check incidence 수, d=최대 선택 domain, w=joint scope 폭, b=separator 폭, a=독립 exposed action bits, B=cache 예산이다.

|단계|시간|resident 공간 / 한계|
|---|---|---|
|Structured def-use/guard/call summary|기본 O(N+E+F), 필요한 SCC는 O(H·Iloop·transferCost)|O(F+E). 모든 nested branch/call contexts를 전개하면 지수; binding DAG/parametric summary로 보존|
|Oracle universe|O(Σ patterns Tnative + L); arity k이면 최악 4^k patterns|O(R+L+RM). datatype/shape/native variant 수는 별도|
|Initial privacy/ingress|O(R·conditionCost + sourceMapEntries)|O(conditions+R); fine-grained privacy가 큰 경우 F 안에서 명시|
|Pairwise compatibility|한정된 binary relation의 unique checks O(ΣE KpKcM)|lazy cache 상한 Bcompat; 모든 K² bitmatrix를 필수 저장하지 않음|
|Worklist|한 monotone root/branch 구간의 삭제 ≤전체 domain 값 수; exact reverse refs 방문 O(Σδ V(δ)); support 재탐색 추가|O(R+I+checks+witnessCache+trail). Undo/re-add는 새 event; 전체 search의 삭제 횟수를 R로 제한하지 않음|
|Joint support|필요한 check들에 O(Σc q_c d^w_c P_c), §5.7|DFS stack O(w), symbolic facts+bounded witnesses. 사전 truth table 없음; 무관 slice 변경으로 q_c 증가하지 않음|
|Closed tree DP|비용 조회가 O(1)이면 O(R+L+offers)|O(N·4+backpointers), context 종류 수를 곱함|
|Regional memo|distinct keys ≤ d^b·2^a·Dresidual·Dcontext|resident ≤B; 한 key 구성/비교 O(boundary length). 실제 도달 key만 생성|
|Global search|최악 모든 owner/supply/scope assignment의 곱; 일반 NP-hard 수준의 조합 탐색|DFS trail+facts+Memo+B+selected plan. dense VE 중간 표를 만들지 않지만 지수 시간은 남음|
|Selected reconstruction/emission|선택 edges/actions 및 reachable fact 검증 비용; 보통 O(N+E+selected actions)|선택된 plan/transaction만 저장. guard/invariant 검사 비용 별도|

목표 `O(NK+EM)`은 arity/native offer 수가 bounded이고 E의 guarded binding 표현이 compact인 **기본 Memo/input relation**에 대한 목표다. 정확한 식은 `O(R+L+RM+E+F+I)`이며 schedule options, facts, solver cache/trail은 포함하지 않은 목표라고 명시한다. S개의 scope나 d^b개의 DP states를 EM 안에 숨기지 않는다.

개정 정책에서 `E=Eg+Eb`로 나누면 edge supply 선택 저장의 목표는 `O(Eg·M+Eb)`이고 scope를 명시 열거하면 `O(Eg·M·S+Eb)`다. Eb는 backedge binding 수다. Writer/head별 pairwise 검사 상한은 backedge당 `O(Kw·Kh·Pdirect)`이며 이전 일반 supply의 movement/scope 축을 순회하지 않는다. 한 native assignment에 대한 supply 탐색 상한에서 `∏e∈Eb choices(e)`가 제거된다. 지역 boundary의 backedge action bits/recipe 차원도 사라진다. Alias provenance·joint guard/invariant 검사와 native choice 폭은 남으므로 전체 solver는 여전히 지수일 수 있다. 실제 시간 개선율이나 전체 loop transfer가 0이라고 주장하지 않는다.

Cache miss 시간은 `Qmiss * SolveCost + Qquery * KeyCost`다. Eviction이 있으면 Qmiss는 distinct keys보다 훨씬 클 수 있다. Native rule 수 감소와 total allocation 감소도 별도다. Worker maps의 canonical geometry는 shape+layout별 한 번 계산해 재사용하지만 runtime 실제 map 검증은 필요하다.

### 9.3 제거되는 작업과 남는 작업

|기존 작업|새 구조에서 제거/축소|남거나 새로 생기는 작업 / 위험|
|---|---|---|
|Source-owner/anchor 선택|고정 W descriptor와 canonical allocator|source identity/time/privacy 증명과 native map-effect 검사|
|Concrete input/source Cartesian product|source-free native pattern만 생성|solver가 실제 필요한 joint assignment를 lazy 방문; 방문 수도 측정|
|CandidateRule/Realization/InputBinding 중복|primitive rule arrays+facts binding refs|compiled owner/context coverage 준비 필요|
|SupportClause/Proof route·native continuity publication|exact def-use, structured branch/loop/call checks|joint support witness, loop invariant checker; 전역 논리 난도는 남음|
|Closure 전체 candidate 재생성/overlay 반복|frozen universe+domain worklist|필수 facts SCC, changed-edge propagation은 반복|
|PhysicalAlternative 재전개|선택 변수는 rule/supply IDs 직접|비분리 fusion/activation cost interface 필요|
|Dense cost/intermediate factor|lazy owned-term evaluation+bounded memo|B&B node explosion, expensive cache keys, weak LB 가능|
|같은 source movement 중복 비용|selected action slot/activation union|동적 lifetime lowering와 export ownership 구현 필요|
|모든 block CFG/RD sweep|structured binder 및 local SCC|loop/branch version semantics는 그대로 필요|
|slice별 joint program replay/tuple→Row|기존 exact bindings의 predicate별 projection, 필요한 check만 등록|direct reverse refs/dirty stamps/coverage 관리 추가; 비분리 조건의 lazy 탐색은 남음|

**Scope 축소 효과:** T>1은 zero arm 수만 줄인다. 정적 loop variant는 iteration별 R_t 선택을 하나로 합치지만 native candidate K나 값 epoch을 없애지 않는다. Nonrecursive는 call SCC 해법을 제거하나 call contexts/body 공유 제약은 남는다. `|R|^T`를 반복 전개하던 가상 설계에 비해 작다는 설명을 현재 구현의 실제 속도 개선율로 제시하지 않는다. 현재도 많은 선택이 이미 정적이기 때문이다.

### 9.4 실제 v50 병목과 측정 해석

[D22](FEDPLANNER_PROGRESS_REPORT_2026-10-13_0945_KO.md)의 frozen v50 조건은 COFEE 50,000×128, W1 LAN, DP-local, 8 CPU, container24GiB/JVM16GiB, X PRIVATE_AGGREGATE/Y worker PUBLIC이다. 각 workload 1회 관측이며 이번 작업에서 재실행하지 않았다.

|지표|LogReg|GLM|
|---|---:|---:|
|Analysis/Closure|275.768s|77.512s|
|Physical model|6.177s|5.284s|
|Cost surface|6.628s|9.741s|
|Optimizer|71.336s|27.836s|
|전체 initial planning|363.643s|125.151s|
|Coordinator cgroup peak|8.476GiB|4.469GiB|

v49→v50에서 LogReg native gate alternatives는 64,495,636→6,006,017로 줄었으나 proof/Clause 소비는 그대로였다. Overlay 누적 allocation은63.724→44.852GB, 배타 시간은69.403→68.840s였다. 전체 시간은357.282→363.643s다. 이 결과는 객체 일부를 덜 만든 것과 실제 성능 개선을 구분해야 한다는 근거다.

새 설계는 **처음부터** exact source/product/proof/PhysicalAlternative 객체를 만들지 않으므로 해당 allocation 경로를 제거한다. 하지만 71.336s의 optimizer 작업이 자동으로 사라지는 것은 아니다. Domain pruning/closed tree/complete-key reuse가 얼마나 줄이는지 실측해야 한다. V55–V59는 다른 commits, 4CPU/16GiB/JVM10GiB/JFR/watchdog60 계열이므로 위 수치와 직접 비교하지 않는다. Watchdog 60은 완료시간60s가 아니다. 기존 20초×각3회 목표 성공은 D22 기준0/3이며 이 문서는 목표 달성을 주장하지 않는다.

## 10. Counterexample / Risk Register

|위험: 발생 조건|최소 반례|해결 / 검증 / 남은 위험|
|---|---|---|
|Native rule 누락|단일 OpCaps만 이동해 CP 또는 FED/native LOCAL 대안 유실|old Oracle+generator native set와 독립 runtime catalog 비교. 누락 family는 coverage BLOCKER|
|잘못된 privacy pruning|PRIVATE_AGGREGATE X에서 `sum(X)` 공개 결과를 이유로 X collect 허용|input payload access와 output release 분리; protected+PUBLIC fixtures. Fine-grained propagation UNKNOWN 남을 수 있음|
|Source 혼동|§6.2의 X 재정의 전후 sum|definition/slot/epoch key, exact binding test. aliases의 mutation 추적 필요|
|Branch cross-combination|§6.3의 `(A,D)`|shared guard binding DAG; independent tiny environment oracle. 일반 predicate reachability는 unresolved 가능|
|Loop self-support/final value 손실|§6.4 entry 없는 SCC 또는 Z가 initializer를 읽음|base/step/exit, T>1 numeric fixture. 유한 phase invariant 표현 한계 명시|
|Backedge를 일반 supply로 처리|native LOCAL writer의 available ROW copy를 FED head에 사용|§6.6 DIRECT checker, backedge action 변수/명령 0 검사. 일반 body transfers는 유지|
|Loop body 전체 이동 금지|§6.6 F2의 B→CP sum collect를 삭제|boundary kind별 dispatch, body input-preparation regression. 반복 이동 전체가 없어진다는 성능 주장 금지|
|Branch join을 backedge처럼 제한|then ROW/else LOCAL의 합법 join collect/upload 삭제|guarded supply 그대로, branch producer pair 독립. Backedge와 겹치는 route만 해당 의무 검사|
|Function compiled owner 충돌|두 호출별 cheapest가 서로 다른 body rule|공통 R owner 및 prevalidate; 실제 recompile epoch 테스트. call clone 없는 독립 최적화 금지|
|D1 우회 은닉|ROW value collect→TWrite/TRead→COL upload|transparent origin identity 유지, policy test. CP identity rewrite가 alias인지 kernel인지 compiler facts로 구분|
|Noncanonical native output|ROW `rbind`가 모든 W를 써도 canonical 분할과 다름|map effect predicate/actual ranges 검사. operator별 검증 catalog 필요|
|REP collect metadata|2-worker 동일 dense/sparse matrix, NNZ를 합산|all-W replica-aware bind, values/dims/NNZ/GETcount 검사. 정적 경로 확인, 실행 미검증|
|Shared materialization stale reuse|`A=A+Y` 다음 iteration에 이전 upload cache 사용|origin definition epoch+scope token, updated/invariant twin fixture. exception/export cleanup 검증 필요|
|Scope 끝 조기 해제|함수 return이 action output alias인데 slot cleanup이 remote rmvar|ownership reference 이전/pinning; caller 소비·예외 테스트. runtime helper 신규 구현|
|불완전 DP key|rootdomain{1,4} answer1을 restricted{4}에 사용|residual signature 또는 internal ownership 규칙, cache on/off parity|
|잘못된 lower bound|공유 upload8을 두 subtree에 더해 LB16, 실제 optimum10|cost owner 하나, negative lazy factor certificate, brute force 비교|
|Cheapest output pruning|§7.3 optimum14를101로 바꿈|complete continuation signature, boundary minima. 최대 dominance 판별 자체가 비쌀 수 있음|
|Tie/cutoff cache 오염|LB==incumbent에서 더 작은 tie를 잘라냄; cutoff를 EXACT로 저장|lexicographic proof/status 분리, eviction/backtrack fixture|
|Emitted schedule 변경|optimizer는 upload1회, runtime은 call마다 PUT|selected slot scope와 actual PUT/GET audit; cost/emission parity gate|
|False infeasible|Oracle 미완성/shape unknown/자원 중단을 emptydomain 처리|closed-domain readiness+typed status. 해결 못한 branch가 최적성을 막으면 UNRESOLVED|
|기존 테스트 과대 해석|stub instruction test를 live-worker numeric 검증으로 보고|§11의 증거 수준 구분. E2E numeric fixture 별도 필요|


## 11. Test Reuse & Validation Matrix

### 11.1 조사 경계와 증거 수준

- 정확한 587개 파일 경계는 `src/test/java/org/apache/sysds/hops/fedplanner/**/*.java`이다: root 2, `fedAll` 8, `fedCostBased` 4, `fedCostBased/commons` 11, `fedCostBased/fedExact` 228, `fedHeuristic` 4, `placement` 302, `placement/adapter` 1, `placement/selector` 7, `rules` 16, `rules/bridge` 4.
- 정적 색인은 587개 파일의 파일명과 3,420개 `@Test` 메서드 선언을 모두 추출했다. 조사 당시 임시 색인은 `/tmp/fedplanner-test-inventory.tsv`에 생성했다. 임시 파일은 이 명세의 필수 실행 의존성이 아니다. 이는 이름/선언 색인이지 587개 테스트 본문의 전수 의미 검토가 아니다.
- §11.2 매트릭스는 37개 고유 클래스의 61개 메서드 행을 다룬다. §11.3은 경계 밖 1개 클래스의 5개 named instruction-level 메서드를 추가한다. 이들의 본문과 assertion을 읽었으며 live-worker E2E 검증으로 간주하지 않는다. 테스트 실행/build/benchmark는 하지 않았다.

분류: **REUSE** = 의미와 fixture/API 모두 대부분 유지, **ADAPT** = correctness invariant는 유지하되 Memo/ProgramFacts/SelectedRule/SelectedSupply API로 이식, **REPLACE** = 같은 invariant를 새 solver/facts 수준 테스트로 다시 작성, **OUT_OF_SCOPE** = 고정 W/canonical mapping 계약에서 탐색하지 않는 축, **OBSOLETE** = 구현 세부만 검사해 새 계약의 invariant가 없음, **NEW** = 현재 직접 대응 테스트가 없음.

### 11.2 Test Reuse & Validation Matrix

|영역|현재 테스트 (선언 행)|현재 assertion이 보존하는 의미|분류|새 API/semantic contract|
|---|---|---|---|---|
|privacy|[placement/CandidatePrivacyInputPruningTest.validationRejectsForeignAndStaleOccurrenceValueAuthorities](../src/test/java/org/apache/sysds/hops/fedplanner/placement/CandidatePrivacyInputPruningTest.java#L143)|같은 문자열/signature라도 foreign analysis, stale consumer/source/predecessor authority는 거부|ADAPT|`PrivacyFilter.filter(rule, OccurrenceId, ValueVersion)`가 exact epoch/definition authority를 검사|
|privacy|[CandidatePrivacyInputPruningTest.metadataOnlyInputIsNotMaskedAsProtectedPayload](../src/test/java/org/apache/sysds/hops/fedplanner/placement/CandidatePrivacyInputPruningTest.java#L230)|coordinator metadata input은 protected payload mask가 아님|REUSE|Oracle input role + privacy access kind가 metadata와 payload를 구분|
|privacy/control flow|[placement/EarlyPrivacyPruningLegalSpaceParityTest.loopBranchPhiAndFunctionRelationsKeepAllProtectedAlternatives](../src/test/java/org/apache/sysds/hops/fedplanner/placement/EarlyPrivacyPruningLegalSpaceParityTest.java#L151)|loop/branch/function의 protected region, remote alternative 존재, native payload flow, golden snapshot/repeatability를 검사; 독립 oracle로 완전성을 증명하지는 않음|REPLACE|각 `BranchEnvironment`/loop edge/call binding에서 filtered rule domain이 독립 oracle legal set과 같음; golden 후보 text/count는 보조 진단으로만 사용|
|privacy fail-closed|[placement/SharedPrivacyMovementLegalityAuditTest.strictPrivateDirectPrintMustFailBeforeSelection](../src/test/java/org/apache/sysds/hops/fedplanner/placement/SharedPrivacyMovementLegalityAuditTest.java#L48)|privacy-safe placement가 0이면 selection 전 실패하고 published states는 비어 있음|ADAPT|Memo insertion 전 rule/output option 제거; empty domain은 typed `NO_PRIVACY_SAFE_RULE` 실패 및 무변경 emission|
|privacy optimizer|[fedCostBased/fedExact/FederatedPlanLocalCostPrivacyConstraintTest.localCostDpPreservesPrivacyAcrossBranchLoopAndFunctionBoundaries](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCostPrivacyConstraintTest.java#L72)|optimizer 선택은 privacy-filtered domain 밖으로 나가지 않음|ADAPT|`SelectedRule[o] ∈ Memo[o].feasibleRules`, `SelectedSupply[e] ∈ allowedOutputs(rule)`를 모든 reachable env에 검증|
|occurrence identity|[placement/FunctionBoundaryOccurrenceIdentityTest.repeatedSameSourceAndFormalRetainDistinctCallOccurrences](../src/test/java/org/apache/sysds/hops/fedplanner/placement/FunctionBoundaryOccurrenceIdentityTest.java#L23)|같은 compiled actual/formal도 두 callsite boundary/context로 분리|REUSE|`OccurrenceId=(templateHop,callContext,recompileEpoch,valueVersion)`와 call-specific binding facts|
|stale/withdraw|[placement/NativePlacementContinuityProjectionMemoLifecycleTest.revisionRetainsOnlyCurrentFactIdentitiesAndKeepsProjectionModesSeparate](../src/test/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuityProjectionMemoLifecycleTest.java#L61)|revision 간 exact live fact만 재사용; equal reconstruction은 authority가 아님|ADAPT|facts cache key에 analysis/recompile epoch와 exact definition ID; stale entry invalidation. `assertSame` proof-object 재사용 자체는 OBSOLETE|
|stale/withdraw|[placement/PhysicalGenerationEnvelopeTest.freshPhysicalOwnerUsesOnlyCurrentCompleteAuthority](../src/test/java/org/apache/sysds/hops/fedplanner/placement/PhysicalGenerationEnvelopeTest.java#L75)|stale owner extras는 새 output에 복사되지 않고 current input pool 제거 시 derived materialization 철회|ADAPT|ProgramFacts snapshot의 current reaching definitions/availability만 공급 생성; removed definition invalidates dependent supply cache|
|branch grounding|[placement/BranchPlacementNormalizationTest.localArmCannotBorrowMapCreatedOnlyInsideSiblingArm](../src/test/java/org/apache/sysds/hops/fedplanner/placement/BranchPlacementNormalizationTest.java#L414)|실행되지 않은 sibling arm의 map으로 local arm upload를 정당화 금지|ADAPT|`isAvailable(supply, env)`는 creation guard가 consumer env에서 참임을 요구|
|branch pool alternatives|[BranchPlacementNormalizationTest.nestedBranchRetainsEachDistinctUploadTargetAsAnExactAction](../src/test/java/org/apache/sysds/hops/fedplanner/placement/BranchPlacementNormalizationTest.java#L450)|서로 다른 pool target/action을 후보로 따로 유지|OUT_OF_SCOPE|새 계약은 fixed W/canonical mapping이므로 pool 선택은 없음. 단, 서로 다른 guard/creation site의 supply identity는 별도 NEW 테스트로 유지|
|branch/recompile|[fedCostBased/fedExact/BranchExitPlacementNormalizationTest.functionBranchPreservesSelectedDownloadAndCarrierAcrossRecompile](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/BranchExitPlacementNormalizationTest.java#L79)|각 branch exit carrier와 선택 download가 recompile 후 실제 instruction과 일치|ADAPT|guarded `SelectedSupply[InputEdgeId,env]`, occurrence epoch rebinding, emission parity|
|branch reachability|[placement/OccurrenceExecutionFrequencyFactsConstantBranchTest.knownTrueAndFalsePredicatesSelectOnlyTheReachableArm](../src/test/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFactsConstantBranchTest.java#L22)|상수 guard는 reachable arm weight 1, dead arm 0이나 occurrence 자체는 보존|REUSE|ProgramFacts guard truth와 activation weight; optimizer가 guard를 선택하지 않음|
|branch reaching defs|[OccurrenceExecutionFrequencyFactsConstantBranchTest.branchJoinIncludesThePriorValueFromAnUnwrittenArm](../src/test/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFactsConstantBranchTest.java#L50)|한 arm이 쓰지 않으면 join에 incoming definition도 도달|REUSE|guarded reaching-definition rows `(p,newDef),(!p,priorDef)`|
|loop guard|[OccurrenceExecutionFrequencyFactsConstantBranchTest.loopWrittenPredicateIsNotSpecializedFromItsInitialValue](../src/test/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFactsConstantBranchTest.java#L77)|loop가 predicate를 쓰면 initial literal로 arm을 제거하지 않음|REUSE|loop-carried scalar version invalidates compile-time guard specialization|
|function context|[OccurrenceExecutionFrequencyFactsConstantBranchTest.functionActualsRemainDistinctAcrossCallContexts](../src/test/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFactsConstantBranchTest.java#L91)|같은 body의 호출별 actual/branch weight가 섞이지 않음|REUSE|call context별 environment/binding; compiled rule choice 공유 시에도 activation은 call별|
|loop sharing|[fedCostBased/fedExact/ExactLoopPhiSupplySharingTest.loopPhiChargesOneSupplyPerValueEpochInsteadOfPerInnerUse](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactLoopPhiSupplySharingTest.java#L50)|entry/backedge의 value epoch마다 body의 inner consumer supply를 공유; backedge 연결 자체의 transfer 테스트는 아님|ADAPT|일반 body input preparation의 version별 비용·retention은 유지. Backedge action은 미생성; D1 복합 경로는 별도 scope 판정|
|loop mutation|[ExactLoopPhiSupplySharingTest.semanticUpdateInsideInnerLoopDoesNotReusePhiSnapshot](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactLoopPhiSupplySharingTest.java#L95)|inner update마다 새 version; stale phi snapshot/retained supply 공유 금지|ADAPT|`ValueVersion`가 loop iteration semantic update를 분리; supply cache key에 version/epoch|
|loop legal space|[fedCostBased/fedExact/LoopEntryCompletePlacementSpaceTest.identityLoopAdmitsExactlyLocalAndEveryExecutableEntryLayout](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LoopEntryCompletePlacementSpaceTest.java#L50)|entry/backedge stored layout 일치와 unrelated grounding 금지; legacy는 같은-layout backedge normalization도 양수 비용 후보로 보존|ADAPT / OUT_OF_SCOPE 부분|grounding/DIRECT 의미 유지. Backedge normalization 기대 조합만 새 정책에서 제외; entry 이동은 유지. 과거 fixture는 old planner용 보존|
|loop cost|[LoopEntryCompletePlacementSpaceTest.uploadUnaryCostIsPositiveAndIndependentOfInnerTripCount](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LoopEntryCompletePlacementSpaceTest.java#L148)|entry upload은 양수이며 inner trip count를 곱하지 않음|REUSE|entry creation context weight 1; body 일반 action은 실제 빈도, backedge transfer factor 없음|
|function alias|[placement/FunctionBoundaryRuntimeAliasContractTest.functionInputSelectsIdentityOrExplicitLocalTransfer](../src/test/java/org/apache/sysds/hops/fedplanner/placement/FunctionBoundaryRuntimeAliasContractTest.java#L43)|formal binding은 exact alias 또는 명시 LOCAL transfer; LOUT→FOUT upload 숨김 금지; FType 보존|ADAPT|actual→formal `InputEdgeId`의 DIRECT/UPLOAD/COLLECT supply를 명시 선택|
|function return|[FunctionBoundaryRuntimeAliasContractTest.functionOutputIsAnExactAliasUntilRuntimeOwnsAnExplicitMaterialization](../src/test/java/org/apache/sysds/hops/fedplanner/placement/FunctionBoundaryRuntimeAliasContractTest.java#L75)|return은 exact alias이고 명시 materialization 없이 layout 변경 금지|ADAPT|return binding edge compatibility와 caller-owned supply action|
|function multi-call|[fedCostBased/fedExact/ExactRepeatedFunctionInputFeasibilityTest.sameActualAcrossTwoCallOccurrencesRetainsAFeasiblePhysicalModel](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactRepeatedFunctionInputFeasibilityTest.java#L26)|같은 actual을 두 call occurrence가 소비해도 하나의 합법 physical model 존재|ADAPT|call별 bindings + `compiledChoiceOwner`가 요구하는 공통 body rule domain; supplies는 activation context별|
|function GET sharing|[fedCostBased/fedExact/ExactFunctionGetSharingTest.ordinaryDemandStillCreatesOneGetBesideAliasedFunctionFormals](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactFunctionGetSharingTest.java#L50)|formal alias는 GET을 만들지 않고 실제 LOCAL consumer demand만 GET 1회|ADAPT|alias DIRECT 비용 0; collect materialization identity로 ordinary demand만 활성화|
|function no hidden GET|[ExactFunctionGetSharingTest.distinctFoutActualsDoNotCreateFunctionBoundaryGets](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactFunctionGetSharingTest.java#L84)|서로 다른 FOUT actual도 alias-compatible formal boundary 자체는 GET을 만들지 않음|ADAPT|call binding DIRECT; caller consumer가 LOCAL을 요구할 때만 COLLECT|
|transient cache|[placement/LogicalTransientReplayFactMemoTest.identicalReplayInputReusesOnlyTheLatestExactFact](../src/test/java/org/apache/sysds/hops/fedplanner/placement/LogicalTransientReplayFactMemoTest.java#L45)|동일 replay라도 latest exact fact만 권위 있음|ADAPT|transient facts keyed by variable definition version + epoch; prior revision cache invalid|
|transient cache|[LogicalTransientReplayFactMemoTest.stateInputOwnerAndOpcodeChangesCannotReuseAuthority](../src/test/java/org/apache/sysds/hops/fedplanner/placement/LogicalTransientReplayFactMemoTest.java#L67)|state/input/owner/opcode 변화 시 cached authority 재사용 금지|ADAPT|memo key includes occurrence semantic rule identity and exact input binding versions|
|transient branch|[placement/TransientPlacementAlternativesTest.compatibleAndIncompatibleBranchJoinsUseExactPhysicalMaps](../src/test/java/org/apache/sysds/hops/fedplanner/placement/TransientPlacementAlternativesTest.java#L153)|여러 reaching writer의 physical map을 branch correlation대로 검사|REPLACE|facts-owned guarded binding rows; 가능한 환경별 universal compatibility, cross-arm Cartesian product 금지|
|transient loop|[TransientPlacementAlternativesTest.identityLoopBackedgeRestoresAllReachingTransientRelations](../src/test/java/org/apache/sysds/hops/fedplanner/placement/TransientPlacementAlternativesTest.java#L221)|initializer와 backedge 정의 둘 다 보존|ADAPT|loop head phi facts with entry/backedge version and grounding|
|transient cross-block|[fedCostBased/fedExact/ExactTransientRealizationTest.crossBlockChoicesRetainWriterReaderRealizations](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactTransientRealizationTest.java#L29)|statement block writer/reader 선택이 같은 realization으로 연결|REPLACE|`TransientDefinitionId`와 read `InputEdgeId` binding; no CandidateRealization object|
|shared lifetime|[fedCostBased/fedExact/ExactActivationMaterializationCostTest.oppositeHalfFrequencyDemandsChargeOneWholeCopy](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactActivationMaterializationCostTest.java#L20)|상호 배타 0.5+0.5 demand의 공유 copy 비용은 1회|ADAPT|guard union activation probability, creation/cache owner별 shared cost factor|
|shared lifetime|[ExactActivationMaterializationCostTest.sourceLifetimeDistinguishesInvariantFromUpdatedLoopValues](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactActivationMaterializationCostTest.java#L61)|loop invariant와 loop-updated source의 retention/반복 비용이 다름|ADAPT|ProgramFacts lifetime + loop version/activation context|
|shared supply|[fedCostBased/fedExact/ExactCompiledSupplySharingTest.productionSurfaceSharesInvariantLoutAndFoutButNotUpdatedSources](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCompiledSupplySharingTest.java#L46)|invariant LOUT/FOUT 공급은 공유, updated source는 공유 금지|ADAPT|supply materialization key `(valueVersion,targetLayout,creationSite,cacheOwner)`|
|fused cost|[fedCostBased/fedExact/ExactFusedFactorReuseCostTest.twoRuntimeOwnersShareOneGetButEachOwnsItsPut](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactFusedFactorReuseCostTest.java#L96)|두 runtime owner가 collect 1회를 공유하지만 upload는 owner마다 1회|ADAPT|COLLECT cache owner와 UPLOAD creation owner를 별도 factor로 소유|
|function execution cost|[fedCostBased/fedExact/ExactExecutionOwnershipCostTest.dmlCallPlaceholderHasNoUnaryExecutionOrPayloadScan](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactExecutionOwnershipCostTest.java#L47)|DML call placeholder 자체는 kernel/payload scan 비용 없음|ADAPT|compiled choice owner가 callee kernels 비용을 소유; call binding은 movement만|
|function frequency|[ExactExecutionOwnershipCostTest.calleeWorkRetainsItsExecutionFrequency](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactExecutionOwnershipCostTest.java#L76)|callee kernel은 caller activation frequency로 비용 계산|ADAPT|`evaluateCost(rule, executionContext)`가 callsite activation을 곱함|
|fused owner|[fedCostBased/fedExact/MMChainCostOwnershipTest.exactLocalAssignmentTransfersExclusiveSourceKernelsToMMChain](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/MMChainCostOwnershipTest.java#L68)|exclusive inner kernels 비용은 fused owner로 이전되고 실제 `MapMultChain` lowering과 일치|ADAPT|Oracle rule에 fused execution ownership; selected rule emission과 cost owner parity|
|fused shared child|[MMChainCostOwnershipTest.sharedInnerKernelRetainsItsIndependentExecutionCost](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/MMChainCostOwnershipTest.java#L89)|공유 inner producer는 fused outer가 독점 소유할 수 없음|REUSE|producer consumer count/ownership facts; shared producer cost는 단 한 선택 변수에 귀속|
|canonical cost|[fedCostBased/fedExact/ExactStagedSupplyCanonicalCostTest.globalAndLocalUseTheSameCanonicalStagedSupplySurface](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactStagedSupplyCanonicalCostTest.java#L28)|global/local optimizer가 동일 canonical objective와 supply lifetimes를 평가|ADAPT|공통 `evaluateCost(SelectedRule,SelectedSupply,contexts)`; solver별 별도 비용 해석 금지|
|local unary dominance|[fedCostBased/fedExact/LocalCategoricalOptimizerTest.localPruningRetainsOnlyTheMinimumRepresentativePerState](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizerTest.java#L19)|외부 factor/constraint가 없는 단일 변수 unary fixture에서 같은 state의 비용 8과 2 중 2를 선택|ADAPT|선택값 `List.of(1)`은 안전한 semantic minimum 회귀로 유지. `retainedLocalStates`/`prunedLocalRepresentatives` 카운터는 해당 구현을 제거하면 OBSOLETE; 일반적인 same-partition rule dominance 근거로 확대하지 않음|
|shared producer solver|[LocalCategoricalOptimizerTest.sharedProducerBlockOptimizesProducerAndBothParentsTogether](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizerTest.java#L227)|수치 반례: greedy `x=0`보다 joint `(1,1,1)` objective 4가 최적|REUSE|한 `SelectedRule[A]`를 두 consumer factor가 공유하는 exact joint optimization|
|lazy factor safety|[LocalCategoricalOptimizerTest.lazyZeroFactorStillEnumeratesAndRejectsHiddenNegativeCost](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizerTest.java#L302)|검증되지 않은 lazy zero lower bound로 음수 cell을 생략하면 안 됨|REUSE|bound certificate가 없으면 lazy cell 평가; strict valid LB만 prune|
|DP boundary|[fedCostBased/fedExact/IncrementalBoundaryMessageTest.mergeComputesBoundaryMinimaAndRecursivelyDecodesExactEndpoint](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalBoundaryMessageTest.java#L17)|separator minima, lower bound, recursive backtrace가 exact optimum 복원|ADAPT|boundary key에 selected shared producer/supply/lifetime/residual domain 포함|
|DP cache atomicity|[IncrementalBoundaryMessageTest.leavesFreezeLazyFactorsAndRejectedMergeIsAtomic](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalBoundaryMessageTest.java#L74)|lazy factor freeze 후 resource rejection은 input/cache를 변경하지 않음|REUSE|residual-domain cache prepare/commit 분리; cutoff status와 reusable input 보존|
|safe pruning|[fedCostBased/fedExact/BoundaryPruningTest.conditionalBoundaryMatchesFullEnumerationAndSkipsMiddleInfinity](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/BoundaryPruningTest.java#L136)|full enumeration과 minima/backtrace 동일, infeasible/cost cut은 실제 work 감소|REUSE|certified support + LB pruning parity; counter는 진단값이지 correctness 기준은 parity|
|global vs local|[fedCostBased/fedExact/CertifiedCostPruningParityTest.higherLocalBoundaryCostCanStillBeTheGlobalWinner](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CertifiedCostPruningParityTest.java#L103)|국소 비용이 더 큰 boundary value가 외부 factor 때문에 global optimum일 수 있음|REUSE|separator별 message를 유지; same output cheapest 단일 pruning 금지|
|exact parity|[CertifiedCostPruningParityTest.oneHundredTenGlobalModelsMatchBruteForceDenseSparseAndDyadic](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CertifiedCostPruningParityTest.java#L143)|110 모델에서 dense/sparse/dyadic solve가 brute force와 optimum/tie/backtrace 동일|REUSE|새 Memo factor builder도 동일-domain brute-force oracle와 비교|
|conditional cache|[fedCostBased/fedExact/IncrementalConditionalReplayTest.successfulBlockIsReplayedAfterCoverRelease](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalConditionalReplayTest.java#L30)|cover release 후 cached exact block 재사용, explicit lift/objective bit 동일, exact solve 재실행 없음|ADAPT|cache key = separator assignment + residual domain + active factors + compiled choice epoch|
|cache invalidation|[IncrementalConditionalReplayTest.strictImprovementInvalidatesTheInvocationProjection](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalConditionalReplayTest.java#L181)|incumbent가 엄격히 개선되면 stale invocation projection 무효화|REUSE|bound/incumbent generation in cache key or explicit invalidation|
|emission transaction|[placement/PlacementEmissionTransactionRedTest.fullPlanIsPrevalidatedBeforeAnyHopOrRegistryMutation](../src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransactionRedTest.java#L81)|불완전 plan은 어떤 HOP/registry mutation 전 실패|ADAPT|all `SelectedRule`/`SelectedSupply`/bindings prevalidate then atomic commit|
|emission rollback|[PlacementEmissionTransactionRedTest.failureAfterFirstHopMutationRestoresEveryOwnedSurfaceExactly](../src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransactionRedTest.java#L164)|첫 mutation 뒤 실패해도 모든 planner-owned surface exact rollback|REUSE|새 emitter transaction undo log에 Memo-selected rule/supply lowering 포함|
|emission validation|[PlacementEmissionTransactionRedTest.malformedOrUnsupportedActionMetadataFailsWithoutMutation](../src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransactionRedTest.java#L404)|malformed/unsupported movement metadata는 무변경 실패|ADAPT|canonical `SupplyChoice` recipe/range validation before commit|
|shared HOP emission|[placement/PlacementEmissionSameHopOccurrenceRedTest.conflictingOccurrenceStatesFailClosedBeforeAnyOwnedMutation](../src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionSameHopOccurrenceRedTest.java#L93)|같은 concrete HOP에 모순된 occurrence states 선택 시 mutation 전 실패|ADAPT|`compiledChoiceOwner`/shared producer constraint가 solver에서 막고 emitter가 방어 검증|
|Oracle scalar native|[rules/bridge/OracleFacadeTest.scalarRightIndexExecutesFederatedButMaterializesLocally](../src/test/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacadeTest.java#L212)|scalar right-index는 FED 실행하지만 native output은 LOCAL, output FType 없음|ADAPT|`FeasibleRule(exec=FED,nativeOutput=LOCAL)` 직접 기대값|
|Oracle fail closed|[OracleFacadeTest.binaryFullMatrixWithUnknownCardinalityFailsClosed](../src/test/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacadeTest.java#L541)|FULL cardinality 미확정이면 FED/FOUT 가정 금지, missing proof 기록|ADAPT|REP 정규화 전에 runtime prerequisite를 ProgramFacts로 증명; 미확정 capability는 UNKNOWN/coverage evidence로 남기며 불가능한 rule로 오인하지 않음|
|runtime replica semantics|[rules/BinaryElemwiseBroadcastMatrixRuleTest.replicatedAndLocalMatrixPairsPublishBroadcastFout](../src/test/java/org/apache/sysds/hops/fedplanner/rules/BinaryElemwiseBroadcastMatrixRuleTest.java#L44)|runtime-supported replicated+local pair는 FED/FOUT/BROADCAST|ADAPT|planner state는 REP이나 Oracle adapter가 실제 BROADCAST dispatch 가능성을 증명|
|runtime FULL guard|[rules/AppendFullShapeGuardTest.fullCbindRejectsKnownRowMismatchInEitherOperandOrder](../src/test/java/org/apache/sysds/hops/fedplanner/rules/AppendFullShapeGuardTest.java#L45)|FULL append의 known non-append-axis mismatch는 양 operand order에서 FED 금지|ADAPT|planner-visible FULL은 REP로 바꾸되 Oracle/runtime evidence의 native FULL shape guard와 rejection은 유지|
|grounding|[placement/NativeValueMapFixedPoolContinuityTest.ungroundedAliasCycleCannotAuthorizeItself](../src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeValueMapFixedPoolContinuityTest.java#L81)|근거 없는 alias cycle은 availability를 스스로 증명 못함|REPLACE|entry/source creation event에서 시작하는 reachability/grounded check; cyclic SCC alone rejected|
|withdraw grounding|[NativeValueMapFixedPoolContinuityTest.sourceWithdrawalInvalidatesTransitivePoolAuthority](../src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeValueMapFixedPoolContinuityTest.java#L352)|근원 source 제거 시 transitive authority도 제거되고 cold result와 같음|ADAPT|facts dependency invalidation over value definitions/supplies; proof object는 불필요|
|versioned sharing|[fedCostBased/fedExact/ExactDerivedSupplySharingTest.samePlacementDoesNotMergeDifferentVersionsOrSingleUse](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactDerivedSupplySharingTest.java#L87)|같은 layout이어도 다른 value version은 merge 금지; single use는 retention 아님|REUSE|materialization key contains exact version; retention only for >1 activation demand/lifetime need|
|guarded shared cost|[ExactDerivedSupplySharingTest.conditionalInnerLoopSharesOneInvariantFoutSupplyAtItsActivationCost](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactDerivedSupplySharingTest.java#L114)|조건부 inner-loop invariant FOUT supply는 activation 0.5로 한 번 공유, 수치 cost 5|ADAPT|guarded demand union + cache lifetime factor|
|derived upload cost-model numeric|[fedCostBased/fedExact/ExactAuxiliaryNativeInputUploadTest.ctableLocalSecondaryIsSlicedExactlyOnce](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactAuxiliaryNativeInputUploadTest.java#L79)|`FederatedCostModel.computeMixedFedLocalCost`의 input-preparation 값을 같은 primitive helpers로 항별 재조립한 expected 식과 `1e-12`로 비교해 sliced PUT 1회 계상을 검증|REUSE|Oracle input preparation recipe + canonical UPLOAD cost. 독립 runtime numeric oracle 또는 실제 worker 결과 검증은 아님|

### 11.3 Runtime 수치/dispatch 증거의 실제 범위

|테스트|증거|분류|
|---|---|---|
|[src/test/java/org/apache/sysds/test/component/federated/AppendFoutRuntimeTest.singleWorkerFullPlusLocalCbindFoutStaysRemoteFull](../src/test/java/org/apache/sysds/test/component/federated/AppendFoutRuntimeTest.java#L106) 및 공통 helper :149|in-process `AppendFEDInstruction.processInstruction` 후 output이 federated FULL, map size 1, local-data가 아니며 정확한 rows/cols임을 검사; live worker/network end-to-end test는 아님|ADAPT: one-worker planner REP 정규화가 이 instruction-level FULL dispatch/result와 동치임을 adapter parity test로 연결|
|[AppendFoutRuntimeTest.rowCbindLoutIsLocal](../src/test/java/org/apache/sysds/test/component/federated/AppendFoutRuntimeTest.java#L126) / `rowRbindLoutIsLocal` :132 / `colRbindLoutIsLocal` :138 / `colCbindLoutIsLocal` :144|stubbed `ResultFederationMap`의 `MatrixBlock` 결과를 사용해 instruction-level output cell을 0 tolerance로 비교하고 LOUT가 local임을 검사(helper :179 이하)|REUSE: Oracle nativeOutput의 instruction-level numeric oracle; live federated execution parity는 별도 NEW test 필요|

### 11.4 NEW fixtures

|NEW 테스트|최소 fixture와 assertion|
|---|---|
|`SharedProducerDifferentConsumerLayoutsTest`|A의 rule choice는 하나; B=ROW, C=COL demand. A native LOCAL이면 두 UPLOAD를 동시에 허용하고 shared execution 1회 + movement 2회; A native ROW이고 no-detour면 C 공급 불가. producer rule을 consumer별 복제하지 않음|
|`SharedMaterializationCoexistenceTest`|한 native output에서 두 available output recipe가 각각 가능해도 실제 lowering/명시된 resource contract 때문에 동시에 유지 불가능한 조합만 reject; 공유 가능한 같은 materialization은 비용 1회|
|`BranchCorrelationNoCartesianProductTest`|`(A,B)` under p, `(C,D)` under !p만 검사; `(A,D)/(C,B)` assignment가 생성되지 않음. unknown p이면 양 환경 모두 legal이어야 함|
|`BranchSupplyGuardActivationCostTest`|arm-local creation은 sibling에서 unavailable; movement/materialization cost는 해당 guard에서만 활성화되고 join retention은 guard union으로 계산|
|`LoopGroundingAndFinalExitTest`|새 planner는 증명된 T≤1에 UNSUPPORTED_SCOPE, T>1 미증명에 UNRESOLVED_LOOP_BOUND를 반환하고, T=2/3에서 last backedge를 final Z가 소비함을 검사. Legacy zero-iteration 의미는 기존 planner test로 보존. Entry 없는 `A↔A` SCC는 거절|
|`LoopPlacementStationarityTest`|body rule/placement는 compiled epoch 동안 iteration마다 고정; version/activation만 반복. iteration별 자유로운 replanning assignment는 금지|
|`CompiledFunctionChoiceOwnerTest`|공통 compiled plan 정책이면 두 callsite가 하나의 body rule choice를 공유하고 각 actual binding/supply는 독립; 두 call 요구의 교집합이 없으면 전체 plan infeasible|
|`CallContextSpecificRecompileEpochTest`|runtime recompile이 실제 clone/epoch을 만들면 Memo occurrence 분리; 이전 epoch selection/supply를 새 clone에 재사용 금지|
|`TransientMultiWriterGuardedBindingTest`|같은 변수명 writer versions를 분리하고 read는 guard별 정확한 writer에만 bind; 존재하지 않는 cross-branch writer 선택 금지|
|`NoReshardPolicyTest`|ROW→COL/COL→ROW DIRECT 없음. `ROW→LOCAL→COL`은 정책 flag가 false면 불가; 향후 D1 ON 확장은 COLLECT+UPLOAD 두 action과 두 비용/availability를 검증; 현재는 ON 지원을 선언하지 않음|
|`CanonicalScheduleUniquenessTest`|같은 selected rule/supply가 둘 이상의 runtime schedule로 lower될 수 있으면 Oracle이 비용/feasibility 구별을 노출하거나 rule을 제외; emitter가 임의 첫 schedule 선택 금지|
|`OneWorkerRepDispatchParityTest`|LOCAL/legacy FULL/BROADCAST에서 정규화한 REP가 operator별 실제 opcode dispatch/native output과 같음을 runtime matrix로 검증; 지원하지 않는 opcode는 rule 없음|
|`MemoSolverBruteForceParityTest`|작은 DAG+branch+loop+function fixture에서 lazy B&B/tree-island DP 결과가 동일 Memo domain brute force의 feasibility, objective, canonical tie와 같음|
|`SeparatorResidualDomainCacheTest`|같은 boundary values라도 residual domain/active guard/shared lifetime가 다르면 cache miss; 완전히 같으면 hit; cutoff/resource status는 exact result로 오인하지 않음|
|`MemoEmissionRollbackTest`|새 `SelectedRule`+`SelectedSupply` plan의 prevalidation, mid-commit fault rollback, retry idempotence 및 actual instruction topology/numeric result 검증|

### 11.5 Assertion 전환 원칙

- `assertSame`은 일괄 삭제 대상이 아니다. occurrence/value/fact authority의 exact identity를 보장하는 assertion은 새 `OccurrenceId`/epoch equality로 **ADAPT**해야 한다. cached proof object 자체나 interned serialization 객체 재사용만 검사하는 assertion은 **OBSOLETE**다.
- publication order/count도 일괄 삭제하면 안 된다. canonical tie-break 또는 runtime emission ordering을 검증하면 유지한다. Closure pass 횟수, proof graph build 수, CandidateRealization 개수처럼 삭제 예정 구현의 work counter만 고정하면 obsolete다.
- `LocalCategoricalOptimizerTest.localPruningRetainsOnlyTheMinimumRepresentativePerState`의 단일-variable unary 선택값은 안전하게 재사용할 수 있다. 다만 그 fixture를 일반적인 same-partition rule dominance의 근거로 확대할 수 없고, 기존 retained/pruned representative 통계는 구현 제거 시 obsolete다.
- 현재 가장 강한 재사용 자산은 privacy fail-closed, exact occurrence/version identity, guarded reaching definitions, loop grounding, atomic emission, runtime native semantics, brute-force parity, strict numeric cost-model tests다. 확인한 live-worker campaign tests는 실행 성공과 fallback/repair 0을 검사하지만 결과 행렬의 기준값 비교는 하지 않으므로 end-to-end numeric parity는 공백이다. Anchor/Proof/SupportClause 객체의 모양과 closure candidate/proof count는 재사용 대상이 아니다.


추가 필수 NEW: REP dense/sparse collect의 values/dims/NNZ/all-W GET 검사, Frame REP의 schema/metadata 보존, scope exception cleanup·return alias pinning·cross-call store 전달, lineage reuse/ParFor scope 진입 검사. Backedge DIRECT-only 정책의 집중 tests는 §11.7에 정의한다. 같은 source/version에서 branch별 first-demand 비용과 실제 생성 횟수가 일치해야 한다.

### 11.6 독립 exhaustive correctness fixture

작은 reference는 새 solver/production pruning helper를 호출하지 않고 semantic rule tables와 primitive supply 조건으로 full assignment를 열거한다. N=2–6, owner당2–3 rules, W=1/2, PUBLIC/PRIVATE/PRIVATE_AGGREGATE, DIRECT/UPLOAD/COLLECT를 사용한다. 모든 runtime value를 전개하는 것이 아니라 이 작은 fixture만 전수 조사한다.

|fixture|모든 assignment에서 비교할 것|
|---|---|
|Tree + metadata input|required-supply DP와 brute force의 legal set/min cost/tie/backtrace; nrow를 payload collect로 오인하지 않음|
|Diamond + shared movement|§7.3 비용14 반례, §7.7 비용10 반례; compute/action 각각1회|
|2-arm/2-variable branch|두 실제 공동 환경만; guard를 optimizer가 고르지 않음; MAY/DEAD/known branch 차이|
|Transient overwrite + missing arm writer|prior definition과 새 definition의 정확한 read binding|
|Loop T=2,3 + invariant/updated source|entry/step/final exit, predicate 빈도, invariant supply1회/updated 매epoch, entry 없는 cycle 거절|
|Finite-phase/nested loop|무한 invariant로 finite-T 합법 상태를 false-prune하지 않음; 불명확하면 UNKNOWN|
|두 callsite/같은 body|공통 rule 비용101 반례, exact actual/return, cross-call action scope|
|Multi-output owner|slot별 다른 native output, owner 실행1회; output upload가 owner exec를 바꾸지 않음|
|Cache/resource/tie|cache off/on/eviction, residual domain, re-add revision, cutoff vs exact, negative lazy factors, 동률 tie|
|Runtime lowering|actual selected opcode/input positions/actions/order, rollback, REP/ROW/COL numeric+metadata|

Pruning **전후 legal plan set**은 capability/feasibility 단계에서 같아야 한다. Cost dominance 후에는 모든 legal plan을 유지할 필요는 없지만 최적 비용·canonical tie와 대표 대체가능성을 보존해야 한다. Constraint가 세졌는데 같은 optimum만 남은 것을 feasible-space completeness라고 부르지 않는다.

Old/new 비교는 동일 W/canonical sources/scope/static patterns/비용 profile와 DIRECT-only backedge 정책에서 한다. Old planner의 backedge normalization 계획은 비교 기준 공간에서 명시 분리하며 old 최적값과 무조건 같아야 한다고 요구하지 않는다. Old plan을 `(compiled owner variant, exact value bindings, primitive actions, scope/activation)`로 정규화하는 **검증용** exporter를 둔다. 새 production에 CandidateRealization을 다시 넣지 않는다. Encoding/ProofKey가 달라 fingerprint가 달라도 의미를 비교하되 기존 FAIL 기록을 덮어쓰지 않는다. 같은 semantic term의 cost raw bits, 전체 objective/tie, instruction topology, PUT/GET counts, 실제 출력 matrix/frame/scalar를 각각 검사한다.

Legacy만 지원하는 D1/worker subset/noncanonical/zero iteration/recursion tests는 삭제하지 않는다. Old planner용으로 유지하고 새 planner에서는 명시 unsupported 상태를 검사한다. Old test가 원래 recursion을 지원하지 않았으면 새로운 regression이라고 주장하지 않는다.

### 11.7 Control Flow Transfer 정책의 테스트 전환

아래는 현재 assertion을 읽은 결과다. 새 정책의 구현·runtime 검증을 수행한 것이 아니다. Old fixture 전체를 삭제하거나 이름에 loop가 있다는 이유로 모든 movement assertion을 폐기하지 않는다.

|현재 메서드 / assertion 근거|전환|새 의미|
|---|---|---|
|[LoopEntryCompletePlacementSpaceTest.identityLoopAdmitsExactlyLocalAndEveryExecutableEntryLayout](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LoopEntryCompletePlacementSpaceTest.java#L50): 86–99행의 stored-layout/정확한 consumer, 110–113행의 relocation=1, 129–133행의 최적 DIRECT|ADAPT; backedge normalization 조합만 OUT_OF_SCOPE|현재는 normalization을 합법·dominated로 두지만 새 모델에서는 처음부터 action/domain 비포함. Entry alternatives까지 함께 삭제하지 않음|
|[LoopEntryCompletePlacementSpaceTest.uploadUnaryCostIsPositiveAndIndependentOfInnerTripCount](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LoopEntryCompletePlacementSpaceTest.java#L148): entry upload 양수·T와 무관|ADAPT API / 의미 REUSE|새 scope의 T=2/3/10에서 entry 비용1회. T=1은 legacy 비용 helper 검증과 새 loop 지원 계약을 구분|
|[LoopIdentityBackedgeClosureTest.anIncompatibleBackedgeCannotBorrowEntryFullEvidence](../src/test/java/org/apache/sysds/hops/fedplanner/placement/LoopIdentityBackedgeClosureTest.java#L78): local-producing backedge를 entry FULL 근거로 살릴 수 없음|ADAPT|Entry movement/REP authority가 incompatible native backedge를 정당화하지 못함|
|[LoopIdentityBackedgeClosureTest.nestedIdentityBackedgesKeepTheGroundedFullAlternative](../src/test/java/org/apache/sysds/hops/fedplanner/placement/LoopIdentityBackedgeClosureTest.java#L60): depth1/2/4의 grounded identity|ADAPT|REP 정규화, 각 loop/slot의 entry grounding과 직접 연결. Proof/Closure 객체 형태는 REPLACE|
|[ExactLoopPhiSupplySharingTest.loopPhiChargesOneSupplyPerValueEpochInsteadOfPerInnerUse](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactLoopPhiSupplySharingTest.java#L50), [semanticUpdateInsideInnerLoopDoesNotReusePhiSnapshot](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactLoopPhiSupplySharingTest.java#L95)|ADAPT|body 소비용 supply 공유/변경 값의 재생성 비용 보존. 이 비용을 backedge 비용으로 잘못 삭제하지 않음; staged D1 부분은 기존 D1 정책과 별도 대조|
|[BranchPlacementNormalizationTest.branchExitTwTrCandidatesRemainCanonicalAndPrivacyFiltersLocalGet](../src/test/java/org/apache/sysds/hops/fedplanner/placement/BranchPlacementNormalizationTest.java#L103)|ADAPT|PUBLIC collect 가능, PRIVATE_AGGREGATE raw collect 금지. Branch별 guarded supply filter|
|[BranchPlacementNormalizationTest.selectedBranchExitGetEmitsExactLocalReceiptWithoutReclassifyingSource](../src/test/java/org/apache/sysds/hops/fedplanner/placement/BranchPlacementNormalizationTest.java#L160)|ADAPT|정확한 consumer/input position의 COLLECT; producer의 native FED 선택은 보존|
|[BranchPlacementNormalizationTest.localBranchResultCanUploadAtExitWithoutWeakeningTransientCarrier](../src/test/java/org/apache/sysds/hops/fedplanner/placement/BranchPlacementNormalizationTest.java#L220)|ADAPT|LOCAL arm→join UPLOAD 허용, transient carrier 계약 보존. Data anchor 권위는 fixed W/guarded creation으로 대체|
|[BranchExitPlacementNormalizationTest.functionBranchPreservesSelectedDownloadAndCarrierAcrossRecompile](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/BranchExitPlacementNormalizationTest.java#L79)|ADAPT|arm별 공급/정확한 join definition/선택 action의 emission·recompile 보존|
|[ExactZeroFrequencyCostTest.knownBranchAndDeadSiblingRetainOneLiveMaterialization](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactZeroFrequencyCostTest.java#L24)|ADAPT|실행 arm만 activation에 기여; dead sibling 때문에 live movement를 누락하거나 중복 과금하지 않음|

**NEW `LoopBoundaryMovementPolicyTest` acceptance:** §6.6 F1/F2/F3와 forbidden native writer/head pairs, alias 및 nested-loop 우회, final-only exit branch를 작은 exhaustive fixture로 검사한다. Backedge별 `SupplyChoice/materialization/action slot/cost factor/transfer instruction count=0`, native DIRECT hard constraint 존재, entry/exit 비용과 actual transfer count, body/branch 합법 이동 보존을 각각 assert한다. `Cbackedge=0`만 검사하면 불법 조합도 공짜로 통과하는 오류를 놓치므로 legal-set 비교를 함께 한다. Native source+head+guard/shape의 cache key에 policy version을 포함하고 이전 policy의 cached support를 재사용하지 않는 fixture도 필요하다.

### 11.8 RD-driven Joint Feasibility 전환과 acceptance

다음은 현재 테스트를 읽고 도출한 전환 명세다. 이번 문서 개정에서 테스트를 수정하거나 실행하지 않았다. 과거 row/Proof 객체의 보존을 새 설계 요구로 삼지 않고 **exact binding, 공동 선택, 선택적 invalidation**을 검증한다.

|기존 class/method·핵심 assertion|분류 / 새 assertion|
|---|---|
|[JointInputReachingDefinitionsTest.sameSliceConsumersAreBatchedIntoOneCfgPass](../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L64): 같은 slice의 세 consumer, 기존 한 CFG pass|ADAPT: 동일 binding projection 재사용, consumer별 전체 program replay0. 과거 CFG pass 수를 새 architecture의 고정 상수로 요구하지 않음|
|[sameTrackedSliceReusesAnalysisAndPreservesRequestedOrderAndDuplicates](../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L96)|ADAPT: ordered input positions와 중복 source 사용을 그대로 비교; 두 position이 한 owner 선택을 참조|
|[oneBranchDecisionRetainsOnlyAaAndBbTuples](../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L123), [independentBranchesRetainAllFourCombinations](../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L139)|ADAPT: reference의 2개/4개 공동 환경과 symbolic evaluator의 결과 일치. Production tuple 리스트 생성을 요구하지 않음|
|[sameBlockSequentialReadsKeepTheirObservedDefinitionVersions](../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L149)|ADAPT: 앞/뒤 write 버전 구분, 이름이 같다는 이유로 cache 합치기 금지|
|[unchangedArmAndRepeatedLoopBranchReachFiniteFixedPoint](../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L175), [loopHeaderReadSeesEntryAndBackedgeDefinitions](../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L251)|ADAPT: correlated carried tuple와 entry/backedge identity 유지. Fixed-point pass count 대신 base/step/exit verdict·DIRECT backedge 비교|
|[nonRecursiveFunctionReturnsKeepCallSiteAndExitCorrelation](../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L198), [formalNamesResolveDistinctCallerVariables](../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L222)|ADAPT: 실제 call/return/position·공동 정의 보존, formal 이름으로 실제 source 합치기 금지|
|[unrelatedBranchesDoNotConsumeTheRequestedDependencySlice](../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L239): 무관 분기15개에도 C의 두 환경 유지|ADAPT: 같은 semantic 결과에 더해 무관 owner domain 변경 시 C joint evaluator 호출 증가0, 별도 의무의 검사는 정상 수행|
|[loopedFunctionBindingHasFiniteStaticIdentity](../src/test/java/org/apache/sysds/hops/fedplanner/placement/JointInputReachingDefinitionsTest.java#L267)|ADAPT: 정적 binding/체크 수는 iteration 수에 비례해 늘지 않음; dynamic epoch의 value 구분은 유지|
|[NativeValueMapFixedPoolContinuityTest.unrelatedOwnerRevisionReusesValueMapProofWhileLeafRevisionInvalidatesIt](../src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeValueMapFixedPoolContinuityTest.java#L368)|REPLACE: `assertSame` proof/topology 객체 대신 unrelated delta cache reuse, hidden alias-leaf delta invalidation 및 warm/cold verdict parity. 의미상 의존성을 하나도 누락하지 않음|
|[IncrementalConditionalReplayTest.replayIdentityIgnoresBlockValuesButMatchesEveryOutsideValue](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalConditionalReplayTest.java#L81), [newerOutsideStateReplacesTheBlocksSingleEntry](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalConditionalReplayTest.java#L110)|ADAPT: exact relevant outside/boundary state와 residual domain key 검증; miss가 부분 선택을 변경하지 않음. 과거 cache 구현별 size=1 계약은 필수 아님|
|[LocalCategoricalOptimizerTest.sharedProducerBlockOptimizesProducerAndBothParentsTogether](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizerTest.java#L227)|REUSE: RD slice 분리로 shared 선택/최적비용4를 잃지 않음. 기존 shared action/function/privacy 테스트도 유지|

**NEW tiny reference fixture:** owner2–5개, 각 rule≤3, source layout4개, guard≤2개와 loop T=2/3, 비재귀 call2개를 각각 작은 사례로 만든다. Reference만 native/supply assignments와 실제 finite guards/iterations를 exhaustive 열거한다. 새 evaluator는 symbolic binding을 사용한다. Joint가 없는 independent case, 같은 guard지만 edge로 분해되는 case, 비분리 shape/privacy predicate, 반복 입력/공유 ancestor, compiled body 공유, multi-slot owner, DIRECT backedge, body/entry/exit 이동, alias source, 동시 materialization을 각각 포함한다. Synthetic predicate는 runtime이 해당 native variant를 지원한다는 근거로 사용하지 않는다.

Acceptance는 다음 순서다.

1. **Coverage/의미:** omitted/edge/joint/pending 분류의 합이 모든 native·use·boundary 의무를 덮는지 검사한다. Mixed TRead+일반 HOP, metadata/control uses를 포함한다. Unknown RD/미등록 call/late ready는 미해결이며 합법으로 누락하지 않는다.
2. **Sound pruning/완전성:** 모든 feasible reference assignment가 pruning 후에도 남고, 완전 선택의 verdict가 reference와 같아야 한다. Propagation이 불가능한 모든 rule을 삭제해야 하는 것은 아니다. Full-rescan과 incremental이 같은 check budget/evaluation 순서일 때 고정점 domain을 비교하고, 다른 순서/budget에서는 legal-set 보존과 최종 optimum을 비교한다.
3. **Event 정확성:** 관련 producer/consumer/head/call owner/선택 의존 privacy/supply 변화, hidden alias leaf, self requeue, restore/re-add를 각1개 변경하여 cold evaluator와 비교한다. 무관 변경은 해당 check 평가 증가0. 검사의 마지막 witness 이외 guard arm에 의존성이 있는 경우도 깨워야 한다. 같은-layout 다른 native rule 변경을 놓치지 않는다.
4. **상태 구분:** existential support YES가 있어도 다른 check와 공통 assignment가 없는 경우 거절한다. UNKNOWN이 같은 stamp에서 반복되지 않고 selected mode에서 필요한 검증을 수행하는지, cache eviction/backtrack이 오래된 NO/YES를 살리지 않는지 확인한다.
5. **Cost/최적성/emission:** incremental/full-check, joint 분해 ON/OFF, cache OFF/ON의 legal set·objective·global optimum/tie·selected actions가 같다. 뒤이어 기존 migration gate의 실제 runtime numeric/instruction parity를 통과해야 old joint/Proof/Closure 경로를 제거한다.

새 기준에서 같은 guard라는 이유만으로 joint 평가가 반드시1회 이상이어야 한다는 assertion은 금지한다. 정확히 edge로 분해되면 **joint handle0**이 올바르다. 반대로 mandatory 비분리 조건이 있는 fixture는 관련 selection delta에만 해당 check가 평가되는지 assert한다.

## 12. Implementation / Migration / Performance Plan

### 12.1 기존 클래스·함수의 전환 표

경로는 `src/main/java/org/apache/sysds/` 기준이며 링크는 현 작업 트리의 함수 또는 주요 정의 행이다. 제거는 아래 단계 gate 이후다.

|분류|현재 클래스/함수|최종 역할 / 최소 변경|
|---|---|---|
|수정·유지|[PlacementProgramFacts.analyze/analyzeCfg][facts-analyze]|structured def-use/guard/loop/call tables owner. 정확한 input positions와 reverse binding refs·coverage 제공. CFG/RD 전역 sweep은 parity 뒤 제거|
|통합|[PlacementJointInputAnalysis.tuplesForReads][joint-query], [cacheConsumerSlice/analyzeFor][rd-slice], [loopFixedPoint][joint-loop], [invoke][function-invoke]|기존 correlation/call identity를 binding DAG로 보존. 동일 roots projection 재사용, 필요한 predicate만 등록. Slice별 전체 program replay와 materialized environments/tuples 제거|
|제거 후 대체|[JointValueMapRelations.from][rd-relations], [ExactPhysicalModel.addJointFactors][rd-physical]|JointTuple→physical Row→support owner factor 전개 대신 기존 consumer/boundary에 최소 check handle. Vacuous/edge-separable 조건은 joint 미생성. Missing source/null은 UNKNOWN/coverage로 분리|
|유지·수정|[OccurrenceExecutionFrequencyFacts][frequency]|predicate/branch/body/call/entry activation 사실. CostMemo에 복제하지 않음|
|수정|[PlacementPrivacyFacts][privacy-facts], [ExecPlacementPolicy.decide][exec-policy]|source/privacy facts와 native execution/release/movement 조건 분리. runtime recompile/TRTW gates 보존|
|통합|[CandidatePrivacyInputPruning][privacy-pruning]|Oracle 직후 filter+edge condition eval; 반복 source-combination masking 제거|
|수정|[RulesApi.FedOracle.caps/OpCaps][oracle-api]|NativeResult variants/coverage, roles/output slots/lowering IDs. Producer/proof 없음|
|수정|[OracleFacade.PreparedDecision/prepareExecutionRelation][oracle-facade], [RulesCore.prepare][oracle-dispatch]|source-free set API adapter, first-match dispatch와 shape prerequisites 보존; native signature cache|
|유지·수정|[Rulesets][broadcast-compat]|검증된 native operator semantics. Canonical map-effect/REP adapter 이식|
|제거 후 대체|[PlacementCandidateGenerator.processCombination][generator-combinations]|RuleMemo builder가 abstract patterns만 생성. MRV/prefix native pruning은 재사용|
|분해·축소|[PlacementAnalysis][transient-facts]|ProgramFacts+RuleMemo+diagnostics 참조. Candidate/realization/proof storage 제거|
|제거|Source Owner/Anchor 탐색, CandidateRuleKey/Fact, CandidateRealization/InputBinding|exact source/version/edge는 facts IDs로 보존. `InputBinding` 객체 자체를 재도입하지 않음|
|제거|[SupportClause/Proof 및 NativeContinuitySupportClauses][continuity]|guarded relation과 domain support checks로 의미 대체. Sparse dirty 개선은 old 경로에서 보존|
|제거 후 대체|[PlacementRelationClosure.closePlacementAndFeasibility][closure]|facts 귀납 + owner ready events + incremental domain propagation. 전체 candidate regeneration 없음|
|축소·유지|[PlacementIdentity][identity]|compact snapshot/owner/value/edge IDs. 원래 ProofKey 문자열/Java identity 계약 대신 정확한 semantic identity|
|통합|[LocalMaterializationSelections.derive][local-copy]|SelectedSupply→selected action slots/scope. Producer nativeLOCAL이면 불필요 collect 만들지 않음|
|유지·수정|[PlacementCostSemantics.prepareExecutionCost][compute-owner]|native/fused cost kernel과 input preparation/release reuse. 객체 authority를 cost key에서 제거|
|유지·수정|[ExactMaterializationActivation][activation], [ExactPhysicalCostModel][cost-freeze]|activation/lifetime 계산 helper reuse. Dense freeze/all alternatives 의존 대신 lazy term IDs|
|제거 후 대체|[ExactPhysicalModel.Alternative/build][physical-build]|Rule/Supply IDs 직접 선택; joint checks는 facts 참조. 중복 PhysicalAlternative universe 없음|
|부분 재사용|[LocalPhysicalOptimizer][local-seed], [LocalCategoricalOptimizer][local-contract]|legal seed, incident indexing, 검증된 equivalence utilities. 5% result를 exact로 취급 금지|
|부분 재사용|[ExactPhysicalReducedSolver][quotient]|complete behavior signature quotient만 재사용; old proof/binding identity 서명은 재설계|
|부분 재사용|[IncrementalRegionalOptimizer][regional-options], [ExactCategoricalSolver][boundary-message]|boundary minima/backtrace/owned factor 개념, 작은 reference solver. Dense all-boundary table 기본 경로 제거|
|유지·수정|[RegionalSearchProblem][regional-problem], [ExactDyadicCosts][dyadic]|lazy cost evaluation/cache와 exact arithmetic 재사용. Source/PhysicalAlternative 의존 adapter 제거|
|수정·유지|[ExactPhysicalPlacementProjector][projector], [PlacementEmissionTransaction][emission]|Plan prevalidate/atomic projection. 기존 receipt 객체 대신 snapshot+semantic selection 검증|
|수정|[Dag.insertLocalMaterializeLops][dag-collect], materialization registries|exact selected input-position rewiring; 성공 후 authority consume, rollback 보존|
|신규 최소|`RuleMemo`, `Domains`, `SelectedSupply`, `DPMemo` helpers|기존 planner 내부 compact arrays/함수. Facts site의 작은 check refs·직접 역색인·dirty stamp/coverage counts 추가; generic graph/Proof 계층 없음|
|신규·수정|[FederatedFoutMaterialize][fout-lop], [FEDFoutInstruction][fout-parse], [FEDLocalMaterializeUtil][canonical-ranges]|pool descriptor/canonical opcode·allocator. legacy anchor opcode는 old 경로 보존|
|신규 최소|`PlannedSupplyStore` + context scope handle|선택된 actions만 첫 demand 실행/retention/export/cleanup. Full candidate cache가 아님|
|수정|[MatrixObject][matrix-collect], [FrameObject][frame-collect], [FederationUtils.bindResponses][bind-responses]|REP-aware all-W collect. ROW/COL disjoint bind는 유지|
|수정|[FunctionCallCPInstruction][function-call-runtime], [Recompiler][recompiler], [FunctionOp][multi-owner]|scope/store 전달, 새 compile epoch, shared owner/slot lowering. 실제 recompilation 주기 보존|
|수정|[FederatedPlannerFactory][factory]|old/new 명시 opt-in 분기; silent fallback 없음. 동일 지원 범위 비교 가능하게 유지|

### 12.2 단계별 착수·완료 gate

|단계|작업 / 선행 의존성|정확성·테스트 acceptance|계산량 측정 / 다음 단계 조건|
|---|---|---|---|
|M0 근거·scope 고정|본 명세 ADR을 configuration/version으로 고정; existing dirty snapshot 별도 보존; native family coverage/map-effect 표 작성|§13 B1/B4의 최소 read-only 조사로 opcode/slot/pattern/gate 목록 확정. 추가 지원 제한을 숨기지 않음|old v50/dirty engine/JAR/data/profile를 별도 manifest. 불명확 families는 구현 범위 표시|
|M1 facts/identity shadow|기존 analyzer를 유지하며 structured binders와 compact IDs·reverse refs 생성; selection은 old 경로|§11.8 ordered/duplicate definitions, related/unrelated slice, mixed HOP/control uses·branch/loop/call exact bindings 일치; unknown과 absent 구분|CFG/slice program replay vs new binding visits/F size/projection reuse. source/version/binding-kind 오류0이어야 M2|
|M2 Oracle/immutable RuleMemo|set API와 privacy/MovementPolicy; source product 없이 variants 생성|old Oracle+generator native set의 canonical projection과 비교; independent shape/privacy fixtures. READY/UNKNOWN/coverage gate 확인|Oracle hit/miss, abstract patterns, unique R, duplicate objects. 미해결 family는 완료 처리 금지|
|M3 feasibility worklist|§5.5 condition read sets/coverage 분류 후 RD-driven edge/joint checks+active BitSets, no rebuild|§11.8 full-check/incremental legal-set parity, hidden dependency·unrelated delta·late ready·self requeue·backtrack/re-add·UNKNOWN; DIRECT/일반 movement 구분; unsafe pruning0|joint 생략 reason·등록수·scope폭, reverse refs/dirty visits, cache miss 원인. Candidate rebuild/source tuple allocation0|
|M4 selected supply runtime|canonical allocator/opcode, synchronous collect/REP fix, action slots/scope/ownership|W1/W2 ROW/COL/REP actual instructions+numeric/metadata, backedge transfer 명령 0 및 entry/exit/body/branch action 보존, exception rollback, return aliases, same/updated epochs, all-W GET/PUT. Test stubs만으로 gate 통과 금지|selected vs actual transfer count/bytes/lifetime. 지원할 모든 scope에서 cost/emission 일치|
|M5 lazy cost+tree DP|shared compute/fusion/activation owners와 complete context cache|tiny all-assignment semantic cost parity; raw bits/rounding policy, metadata/no-hidden-GET, §7.7 optimum10|unique cost evaluation, DP hits/misses/key bytes. Cache off/on 동일 optimum|
|M6 exact global search|single solve(region,boundary), 같은 registered checks/cache, selected-scope validation, safe LB/tie/status|shared/call/branch/loop exhaustive optimum·cost/action parity; local support와 global 선택 구분; residual-domain/cutoff/negative factor/resource tests; UNKNOWN→OPTIMAL0|nodes/local support visits/selected-check reuse/boundary width/cache peak. Old Exact와 동일 objective 비교|
|M7 integration parity|Plan reconstruction/transactional emission+runtime recompile boundary|모든 in-scope semantic/emission/numeric fixtures 통과, old/new 동일 지원 범위 비교, 모든 stale selection/rollback tests 이식|분석~emission 총시간, peak/allocation, runtime traffic. 기존 핵심 계층 아직 보존|
|M8 ML performance·삭제|동일 frozen engines로 paired LogReg/GLM, ablation; 지원 범위별 실측|최적성·privacy·numeric·scope 변화 없이 성능 평가. 테스트 matrix 잔여 assertion 전환 완료|새 bottleneck 기록. M1–M7 parity와 필요 scopes 검증 후 old Anchor/Proof/Closure/PhysicalAlternative 제거 가능|

각 단계는 production 변경을 수행하라는 이번 작업의 실행 지시가 아니라 **후속 구현 계획**이다. 이 문서 작성 중에는 해당 build/test/benchmark를 실행하지 않았다. Old/new toggle는 사용자가 명시한 선택이며 새 planner 실패 시 old planner를 몰래 실행하지 않는다.

### 12.3 최소 performance 실험

실험 두 층을 분리한다. (A) 같은 동결 rule/cost model에서 기존 Exact/Local backend와 새 solver를 비교하여 solver 변화만 측정한다. (B) 같은 DML/data/worker/policy에서 old 전체 pipeline와 새 pipeline를 비교한다. 기존 Local 5%와 새 exact의 시간을 같은 optimality 조건인 것처럼 비교하지 않는다.

필수 workload는 보호 입력 LogReg/GLM의 기존 COFEE 조건, W1 및 W2+ canonical micro-workloads다. Source/JAR hash, dirty patch, Docker resource, JVM/BLAS, data/seed, DML, privacy, cost profile, lineage/recompile 설정, W/geometry, solver 종료 계약을 고정한다. 기존 `run_LAN_docker.sh` harness를 사용한다. 최소5회 paired 순서교대 실행의 median/분산/최악/peak를 보고하며, 기존20초×각3회 목표의 통과 여부도 별도로 표시한다. Profiling/JFR allocation run과 일반 timing run을 구분한다.

최소 ablation: structured facts만 → immutable rules/no product → worklist/privacy → tree DP → lazy boundary memo → safe dominance. 각 단계가 동일 Lspec/objective를 유지하는지 먼저 검증한다. B&B가 VE/Local보다 빠르다고 사전에 단정하지 않는다. Narrow separator에서 기존 VE가 유리할 수 있으므로 reference 측정으로 확인하되 새 기본 경로는 lazy solver 하나로 유지한다.

RD-driven 부분은 동일 immutable facts/rules/선택 순서를 고정해 **모든 등록 check 재검사 reference → direct dependency queue → edge decomposition/vacuity → projection/selected-verdict cache**를 별도 ablation한다. 전역 truth table을 reference 구현의 필수 조건으로 만들지 않는다. 무관 branches/definitions를 늘리는 micro-fixture와 실제 LogReg/GLM에서 평가 호출이 줄어도 역색인·key 구성·global search가 증가하는지 함께 확인한다. 이미 상관된 전체 region에서는 감소가 작을 수 있다.

|계측|정의 / 오해 방지|
|---|---|
|Native rules generated/active|unique rule 수와 누적 allocation 분리, native variants와 Offers 분리|
|Pruning stage/reason|rule마다 첫 증명 reason과 조건 ID; earliestReadyEvent와 실제 deletionEvent의 차이. 순서에 따라 다른 proof가 가능하므로 오프라인 fixture에서는 모든 첫 증명 시점 비교|
|중복 검사|`query semantic key+domain revision`별 evaluations/hit/miss; 동일 rule가 다른 env에서 검사된 것은 중복으로 단정하지 않음|
|Oracle/cache|calls, unique signatures, hits/misses, key 생성 시간/bytes, evictions|
|Worklist|enqueue/pop/dedup/no-change/removed/revisited, support witness hit와 실제 tuple visits|
|RD/Joint 관련성|semantic uses/obligations, EDGE/JOINT/STATIC_TRUE/PENDING 수·분해 사유, check별 input/owner scope폭, binding view 생성/재사용, 전역 slice replay 횟수|
|Joint delta/cache|reverse-reference 방문·dirty 원인·unrelated delta의 evaluator 호출, support/selected mode 분리, check local stamp별 중복 평가, hidden-dependency invalidation, UNKNOWN defer/재개, coverage 누락|
|Combination work|source tuple 객체 생성, lazy witness tuple 방문, materialized joint rows. 객체0이 탐색0이라는 뜻이 아님|
|Cost/DP/solver|term evals, bound hits, search nodes/leafs/prunes, key cardinality/폭, backpointer bytes, incomplete states, factor/VE intermediate cells|
|Time|facts/oracle/privacy/propagation/cost/DP/search/emission/total planning 각각; phase overlap/중복 timer 제외|
|Memory|heap/RSS/cgroup peak, retained caches/trail/key bytes, 누적 allocation/GC 별도. 누적GB를 peakGiB와 비교 금지|
|Emission/runtime|selected/actual PUT/GET count와 bytes, creation guard/epoch, reuse count, cleanup/export, numeric outputs|

실측 acceptance는 동일 지원 공간의 semantic/cost/emission gate 통과가 우선이다. Candidate count 감소만으로 성공 처리하지 않는다. Total time/peak가 나빠지면 key construction·weak bound·witness 재방문·scope helper 비용을 분리하고, 합법 후보를 버리는 방식으로 결과를 보정하지 않는다.

## 13. Open Issues, Blockers, Readiness Verdict

### 13.1 해결된 결정과 남은 증거

D1, ingress, static pattern/body, shared compiled owner, runtime action schedule, exact search/종료 계약은 **DECIDED**다. 두 구현이 공존하는 미결정 대안으로 남기지 않는다. 다음은 정책 미정이 아니라 실제 native coverage 및 lowering의 증거/구현 공백이다.

|ID / 상태|막는 범위|가장 작은 해결 작업|종료 기준|
|---|---|---|---|
|B1 **BLOCKER: native/canonical coverage**|전체 Lspec rule completeness·구현 범위 확정|D12의49 families를 출발점으로 현재 Rulesets+Generator CP/forced-local 확장+FED instructions를 pattern/role/shape/output slot/lowering별로 대조. Known map effects와 unknown을 표로 채움. 먼저 LogReg/GLM에 실제 쓰이는 families를 고정|in-scope 모든 pattern에 variant list 또는 검증된 NO; scalar/metadata/REP/multi-output 누락 없음. Unknown family를 지원한다고 표시하지 않음|
|B2 **BLOCKER: REP runtime preservation**|REP COLLECT를 포함한 end-to-end 계약|2-worker dense/sparse REP collect values/dims/NNZ/GETcount fixture; Frame REP schema/metadata fixture. 정적 발견 경로에 explicit replica-aware collect 구현|all-W success, exact result/NNZ, privacy, alias ownership, modeled traffic 일치. 기존 bind 경로를 검증 완료로 사용하지 않음|
|B3 **BLOCKER: selected supply scope lowering**|loop-invariant/cross-call sharing의 cost/emission parity|동일 실행 exact rewire→loop retained→function ancestor scope 순으로 `PlannedSupplyStore`·token·pin/export/exception cleanup 구현·instruction test 후 live-worker fixture|선택된 action별 생성횟수/guard/epoch/expiry가 runtime과 일치. Scope 비용만 구현하고 runtime 재실행을 남기는 상태 금지|
|B4 **BLOCKER: multi-output/recompile lowering coverage**|owner/slot 계획의 emission completeness|FunctionOp owner exec 선택과 output-only materialization 분리, recompile CP→FOUT/TRTW 허용 위치의 lowering catalog 작성; shared body 두 call과 actual recompile_once 호출 fixture|slot upload가 native owner exec를 바꾸지 않으며 epoch mismatch/stale selection을 거부. 같은 loop invocation의 fixed physical pair를 epoch 간 유지. 구현하지 않는 paths는 명시 scope 밖|
|O1 **OPEN, 비차단**|추가 지원 확대|D1 ON, noncanonical normalization, parametric input-pattern native variants, ParFor/lineage reuse, recursion/zero iteration 지원은 별도 설계 버전|현재 기본 정책을 바꾸지 않고 후속 scope·cost/emission 검증|
|O2 **OPEN, 성능 가설**|backend 적합성/실제 속도|M8 paired measurements 및 cache/solver ablation|새 planner total/peak·search 작업량 데이터. 개선율 사전 약속 없음|

B1/B4는 최소 정적 catalog 조사로 먼저 구현 착수 범위를 닫을 수 있다. B2/B3는 구체적 API와 구현 방향은 정해졌지만 실행 검증이 필요한 runtime 작업이다. 이번 read-only/document-only 범위를 어겨 build나 benchmark로 닫지 않았다. 새로운 추가 추상 계층을 도입해야만 해결되는 blocker라고 판단한 것은 아니다.

### 13.2 완료 판정

|완료 조건|현재 상태|
|---|---|
|문서 간 정책 충돌 해결|DECIDED: 본 ADR 우선; 사용자 T>1/live-out 전제 반영|
|owner/API/data/selection 경로|DECIDED: §§4–8, exact IDs와 compiled owner 명시|
|control-flow/movement/pruning/DP 계약|DECIDED, 핵심 반례와 정적 source 근거 포함|
|test reuse/새 fixture/migration 계획|DECIDED: §11–12. 전체3,420 tests의 본문 전수 검증 아님|
|complexity/병목/측정 정의|DECIDED: 최악 지수·새 scope/cache 비용 포함, 미측정 개선 주장 없음|
|native universe/모든 runtime paths 검증|B1/B2/B3/B4 남음. VALIDATED로 승격하지 않음|
|전체 Implementation-Ready 및 old 계층 제거|**아직 아님**. M0 catalog와 M4/M7 runtime parity gates 필요|

**최종 권고는 이 문서의 단일 아키텍처로 M0 catalog와 M1 shadow scaffolding을 시작하되, B1/B4 catalog를 먼저 닫고 B2/B3를 명시적인 runtime 구현 항목으로 처리하는 것이다.** 단순화를 위해 source/time/privacy/공동 선택 정보를 버리지 않으며, 기존 object identity·Proof/Closure 형태를 정확성의 정의로 고정하지도 않는다. 현 단계 산출물은 통합 구현 명세와 정직한 readiness 판정이며, 새 planner의 완료 보고가 아니다.

### 13.3 최초 통합 문서 작성의 검증 기록 (이번 정책 개정 이전 이력)

정적 조사만 수행했다. 기존 docs/source/tests/실험을 변경하지 않는 것을 acceptance로 삼았고, 시작 시 HEAD·tracked source patch hash·8,032개 tracked src 파일 hash·4,841개 기존 문서 hash·353개 실험 파일 stat을 snapshot에 기록했다. 최종 정적 검사에서 local links 171개와 line references 134개가 모두 존재/유효 범위였고 code fences 38개가 짝을 이뤘다. 원본 source/doc hashes 및 실험 stat 변경은 0, HEAD와 source patch hash도 동일했다. Git status의 유일한 추가 항목은 이 새 문서다. 임시 조사 파일은 `/tmp`에만 생성하며 구현·테스트 산출물로 취급하지 않는다.


### 13.4 Loop/If-Else Transfer 정책 개정의 검증 기록

이번 개정은 사용자 확정 정책을 ADR09/20, binding-arm 분류, 단일 head 계약, DIRECT checker, 비용·DP·emission 및 §11.7 테스트 전환에 반영했다. 통합 명세와 Control Flow/DP 보조 문서를 수정하고, 세 선행 설계 문서에는 최신 정책 우선 안내를 추가했으며 세션 이력을 갱신했다. 기존 문서의 과거 분석은 이력으로 보존한다.

개정 시작 snapshot과 비교하여 **변경 파일은 위 문서6개뿐**이었다. HEAD와 기존 source/test patch SHA-256은 동일했고 tracked src8,032개 내용 변경·삭제는0이었다. 실험353개 파일의 size/mtime도 그대로였으며 신규 문서는 없다. 기존 미커밋 변경과 git status 항목도 보존했다. 수정 문서 전체의 local links462개와 line references403개는 모두 존재하고 행 범위가 유효했으며 Markdown fences184개는 짝을 이뤘다. 개정 diff의 whitespace와 `git diff --check -- docs`도 확인했다.

독립적인 source/test 검토와 정책/API 재검토를 반영했다. 이는 문서·정적 근거 검증이며 새 planner의 실행 검증이 아니다. Build/test/benchmark/commit/push는 수행하지 않았다. 기존 B1–B4 readiness blocker는 유지하며, 새 정책의 실행 acceptance는 §11.7과 migration gates를 따라 별도로 검증한다.

### 13.5 RD-driven Joint Feasibility 개정의 검증 기록

이번 개정은 ADR21, §§5.3–5.7, global/DP/complexity 연결, §11.8 테스트 전환 및 M1/M3/M6 계측·acceptance를 수정했다. Control Flow/DP 보조 문서와 두 선행 Oracle/Pruning 문서의 우선 기준, 세션 기록도 갱신했다. 독립 source 조사 및 문서 재검토에서 중대한 정책/API 모순은 발견되지 않았다. `predicate read set`, 모든 의무의 `coverage`, reverse dependency의 변경 통지 완전성은 **후속 구현의 필수 acceptance**이며 검증 완료한 코드 특성으로 주장하지 않는다.

시작 snapshot 대비 변경은 문서5개이며 신규 문서는 없다. HEAD, 기존 source/test patch SHA-256, tracked src8,032개 hash, 실험353개 size/mtime 및 git status 항목은 동일하다. 수정 문서의 local links387개·line references333개는 모두 존재하고 유효 행 범위였으며 Markdown fences150개는 짝을 이뤘다. 개정 whitespace와 `git diff --check -- docs`도 확인했다. Source/test·실험은 수정하지 않았고 build/test/benchmark/commit/push는 수행하지 않았다.

이번 결과는 설계·정적 근거의 통합이다. 실제 legal-set/cost/emission parity와 성능은 §11.8/§12 gate 이후 검증하며 B1–B4 readiness blocker는 유지한다. RD-driven scope가 전역 최적화 자체를 국소 greedy로 바꾸거나 Lspec을 추가로 제한하지 않는다는 계약을 확정했다.

<!-- Current working-tree source/test references; historical document bases are recorded in §2. -->
[activation]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactMaterializationActivation.java#L81
[agg-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateUnaryFEDInstruction.java#L153
[all-worker-get]: ../src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationMap.java#L464
[append-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/AppendFEDInstruction.java#L166
[bind-responses]: ../src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationUtils.java#L1342
[boundary-message]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java#L1065
[boundary-policy]: ../src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java#L489
[broadcast-compat]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java#L319
[canonical-ranges]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDLocalMaterializeUtil.java#L125
[cfg]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java#L144
[closure]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L1048
[compute-owner]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java#L980
[continuity]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/NativeContinuitySupportClauses.java#L27
[cost-freeze]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java#L590
[cost-prune-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CostBasedPruningTest.java#L35
[dag-collect]: ../src/main/java/org/apache/sysds/lops/compile/Dag.java#L334
[dyadic]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactDyadicCosts.java#L189
[emission]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java#L337
[emission-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransactionRedTest.java#L81
[exec-policy]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java#L267
[factory]: ../src/main/java/org/apache/sysds/hops/ipa/FederatedPlannerFactory.java#L34
[facts-analyze]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java#L78
[for-runtime]: ../src/main/java/org/apache/sysds/runtime/controlprogram/ForProgramBlock.java#L106
[fout-cache]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDFoutInstruction.java#L273
[fout-lop]: ../src/main/java/org/apache/sysds/lops/FederatedFoutMaterialize.java#L70
[fout-parse]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/FEDFoutInstruction.java#L54
[frame-collect]: ../src/main/java/org/apache/sysds/runtime/controlprogram/caching/FrameObject.java#L260
[frequency]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFacts.java#L70
[function-call-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/cp/FunctionCallCPInstruction.java#L127
[function-invoke]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L946
[function-runtime]: ../src/main/java/org/apache/sysds/runtime/controlprogram/FunctionProgramBlock.java#L104
[fusion-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactFusedKernelOwnerProjectionTest.java#L18
[generator-combinations]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L231
[generator-prefix]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java#L1123
[grounding-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeValueMapFixedPoolContinuityTest.java#L81
[identity]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java#L320
[index-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/IndexingFEDInstruction.java#L176
[joint-loop]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L896
[joint-query]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L672
[lazy-factor-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizerTest.java#L302
[lifetime-cost]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java#L2167
[local-contract]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java#L27
[local-copy]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/LocalMaterializationSelections.java#L354
[local-seed]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java#L184
[loop-predicate-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFactsConstantBranchTest.java#L77
[loop-seed]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L8896
[loop-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/placement/LoopIdentityBackedgeClosureTest.java#L60
[matrix-collect]: ../src/main/java/org/apache/sysds/runtime/controlprogram/caching/MatrixObject.java#L553
[mm-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/AggregateBinaryFEDInstruction.java#L289
[multi-owner]: ../src/main/java/org/apache/sysds/hops/FunctionOp.java#L553
[multi-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/MultiReturnParameterizedBuiltinFEDInstruction.java#L83
[native-cost]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java#L350
[oracle-api]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesApi.java#L407
[oracle-dispatch]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesCore.java#L333
[oracle-facade]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L445
[physical-build]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L646
[prefetch]: ../src/main/java/org/apache/sysds/runtime/instructions/cp/PrefetchCPInstruction.java#L47
[privacy-facts]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementPrivacyFacts.java#L37
[privacy-pruning]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidatePrivacyInputPruning.java#L27
[projector]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalPlacementProjector.java#L36
[quotient]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java#L30
[recompiler]: ../src/main/java/org/apache/sysds/hops/recompile/Recompiler.java#L2379
[regional-options]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/IncrementalRegionalOptimizer.java#L55
[regional-problem]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchProblem.java#L20
[rmempty-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/ParameterizedBuiltinFEDInstruction.java#L598
[roll-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/ReorgFEDInstruction.java#L317
[shared-producer-test]: ../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizerTest.java#L227
[transient-facts]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L4427
[transpose-runtime]: ../src/main/java/org/apache/sysds/runtime/instructions/fed/ReorgFEDInstruction.java#L201
[unary-broadcast]: ../src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java#L1565
[while-runtime]: ../src/main/java/org/apache/sysds/runtime/controlprogram/WhileProgramBlock.java#L98

[alias-backedge]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L1032
[identity-backedge]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java#L8933
[rd-cfg]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java#L144
[rd-edge]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L6176
[rd-sources]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java#L6249
[rd-slice]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementJointInputAnalysis.java#L761
[rd-relations]: ../src/main/java/org/apache/sysds/hops/fedplanner/placement/JointValueMapRelations.java#L1151
[rd-physical]: ../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java#L1942


## P2 후속 확장 참조 (2026-10-14, 구현·검증 진행 중)

ADR01/02의 canonical source 제한을 실제 P2에 적용할 때의 지원 범위 충돌과, 유일 map 전제를 유지하는 session별 불변 axis-length profile 확장은 [P2 source geometry 계약](FEDPLANNER_P2_SOURCE_GEOMETRY_EXTENSION_2026-10-14_KO.md)에 명시한다. 기존 floor allocator의 기본값을 전역 ceil로 변경하거나 ingress 검사를 삭제하지 않는다. 해당 문서의 완료 gate를 통과하기 전까지 새 범위의 지원 완료를 주장하지 않는다.

## ADR22 — 0/1/many loop 실행과 비용 profile (DECIDED, 구현 검증 중)

사용자 명시 승인으로 T>1 지원 제한을 해제한다. Loop invocation별 실행 cardinality는 runtime 환경의 사실이며 optimizer가 자유롭게 고르는 변수가 아니다. 같은 loop의 모든 carried value는 하나의 correlated cardinality decision을 공유한다. Facts는 정적으로 확인한 cardinality를 좁히고, 불명확하면 ZERO/ONE/MANY 모두의 합법성을 검사한다.

- ZERO: body 및 entry transfer/action은 실행하지 않는다. Exit은 entry의 정확한 reaching definition이다. 초기화되지 않은 값을 새로 만들어 내지 않는다.
- ONE: 첫 iteration의 entry supply와 body를 각각 한 번 실행한다. FIRST와 LAST가 같은 iteration이라는 이유로 kernel/cost를 두 번 세지 않는다. Taken backedge는 없다. Exit은 유일한 body iteration의 최종 정의다.
- MANY: 기존 FIRST/MIDDLE/LAST 관계를 유지한다. 실제 다음 iteration이 있을 때만 direct backedge compatibility를 요구한다. Final-only output은 ordinary exit supply로 처리한다.
- Native execution choice는 여전히 compiled owner 하나이고, 각 실제 실행에서 동일 ExecType/native output을 사용한다. ZERO/ONE 경로 때문에 consumer별로 producer native rule을 복제하지 않는다.
- Runtime predicate 평가와 일반 종료/예외 의미를 유지한다. ZERO와 nonzero exit을 변수별로 독립 선택하여 존재하지 않는 공동 환경을 만들지 않는다.

**비용 모델 계약:** 정적 count가 알려지면 그 count로 0/1/다회 cost를 계산한다. Runtime-dependent count의 `unknownLoopIterations`는 단일 추정 count(기본 10)이지 실제 cardinality 분포가 아니다. 이 profile은 MANY의 비용을 추정하되 ZERO/ONE도 feasibility/emission 검증에서 반드시 보존한다. 빈도 0은 불가능성 증명이 아니며 rule/domain 삭제 사유가 아니다.

공유 materialization은 관련 guard cube의 union을 계산한다. Distinct unknown GuardDecision은 profile에서 독립으로 추정하고, 같은 `(guardId, callFrame)`의 반복 평가는 correlated로 추정한다. Proven phase 결과는 이 추정보다 우선한다. 이는 실측 확률이나 runtime 불변성이 아니라 명시적인 비용 추정 가정이다. Native 실행 p*T와 retained one-time action p는 이 고정 profile의 서로 다른 비용이다. Independent-per-iteration profile을 채택하면 action 확률은 1-(1-p)^T가 되며, 이는 별도 objective 변경이다. Global optimality는 동결된 추정 비용에 대해서만 주장한다.

**필수 검증:** T=0/1/2/3의 numeric exit와 실제 action 횟수, unknown-cardinality의 모든 공동 환경, zero path의 missing-entry/unsupported body 구별, branch가 포함된 loop-carried 다중 변수, nested loop/function context, static rule 공유, taken backedge transfer 0, 실제 GLM/LogReg helper loop의 0~1회 실행.


## ADR23 — positive residency와 privacy ingress (DECIDED, 실행 검증 진행 중)

`MemoResidency`는 exact privacy label이나 source provenance를 대신하지 않는다. Immutable binding dependency에 대해 RELEASED 여부만 안전하게 증명하여 반복 exact privacy 계산을 생략한다. 증명 실패는 ILLEGAL이 아니라 guarded exact 평가를 요구한다. Ordinary operation은 필요한 입력 grounding을 모두 요구한다. Loop head는 entry에 근거한 induction으로만 승인하며, 같은 loop/call frame을 다른 assumption map으로 재진입하여 스스로 증명할 수 없다. 원래 source/shape/availability 및 모든 reachable environment 검사는 독립적으로 유지한다.

현재 origin/main runtime에는 fine-grained range enforcement가 없다. 따라서 non-empty `fine_grained_privacy` metadata는 `UNSUPPORTED_FINE_GRAINED_PRIVACY`로 명시적으로 실패한다. 이를 PUBLIC 또는 coarse label로 축약하여 지원한다고 주장하지 않는다. Empty fine-grained metadata만 기존 coarse semantics로 처리하고 malformed metadata도 fail-closed다. 완전한 fine-grained 지원에는 별도의 range-aware propagation/runtime enforcement 검증이 필요하다. 이는 검증된 지원 범위의 명시적 제한이며, 일반 coarse privacy 보존과 구분한다.

## ADR24 — solver 재계산 경계 (DECIDED, component 검증)

Active domain 변경은 incident check/cost minima만 invalidation한다. Initial feasibility propagation 뒤 singleton이 된 변수는 실제 rule ordinal을 고정한 작은 Check/CostTerm adapter에서 한 번만 scope projection한다. Immutable native rule universe와 선택 ID는 유지한다. Search branch별로 candidate나 source product를 재생성하지 않는다.

Native/conditional/tree-boundary minima의 exact 합은 remove/undo의 dirty 항만 갱신한다. Small projected domains는 lazy exact minimum을 계산하고 큰 scope는 certified lower bound를 사용한다. Resource limit에 도달한 incumbent은 진단용으로 보존하되 OPTIMAL로 표시하거나 emission하지 않는다. Global optimum 증명과 메모리/시간 완료는 별도 acceptance gate다.

### ADR-25 — native execution cost context projection (DECIDED; validation in progress)

Native execution cost의 primitive context는 **occurrence/call frame + activation + loop phase/frequency + cost-relevant shape**다. 같은 비용을 갖는 exact source identity나 무관한 branch를 Cartesian product로 펼친 결과를 primitive identity로 취급하지 않는다. 따라서 source-only decision을 먼저 projection한 뒤 비용을 생성한다. 이는 후보/source 조합 전체를 생성한 뒤 unary factor를 합치는 방식과 다르다.

- 선택/shape/activation에 영향을 주는 guard와 정상 return 조건은 보존한다. Completion이 모든 경로에서 존재한다는 양의 증명만 source 해석 생략에 사용한다.
- Loop induction 중 가정에 의존하는 중간 true를 global cache로 publish하지 않는다. 완결된 top-level 증명만 캐시하거나 정확한 가정 문맥을 local proof key로 사용한다.
- 생성된 각 primitive는 기존 binary64 quantity model의 반환값이며 optimizer와 selected-cost audit은 이 값들을 정확히 합산한다. 이후 grouping 시 double pre-sum은 금지한다.
- 무관한 source branch가 만든 `cost*0.5 + cost*0.5` 등을 없애면 이전 임의 source-product 구현의 마지막 ULP가 달라질 수 있다. 그 반올림 artifact의 bitwise parity는 계약이 아니다. 비용상 관련 실행 빈도/shape/activation의 수학적 의미, 새 immutable objective에 대한 exact summation/최적성, 작은 exhaustive reference와의 일치는 계약이다.
- 기존 legacy estimator와 새로운 frozen guard estimator 차이는 별도 명시한다. 비용 추정의 변경을 runtime numeric parity나 feasible-space equivalence라고 주장하지 않는다.

### ADR-26 — post-selection physical fusion boundary (DECIDED; LogReg runtime validated)

Memo가 독립 native rule로 선택하고 비용·supply를 소유한 HOP를 LOP construction에서 암묵적으로 삭제하지 않는다. Committed Memo selection이 있는 intermediate에 한해 sum-product, outer-aggregate, MM-chain/transpose, relu-backward late fusion을 막고 원래 선택한 native kernels를 유지한다. 일반 compiler와 legacy planner는 이 Memo authority가 없으므로 기존 fusion을 유지한다. fused execution을 나중에 지원할 때는 candidate generation 이전의 명시적 rewrite 또는 공동 native variant/cost owner로 표현해야 한다. Missing-Lop 검증을 완화하거나 runtime fallback을 추가하지 않는다.
