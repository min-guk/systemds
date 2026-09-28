# 공통 후보 생성의 조기 privacy pruning 및 추가 축소 계획

> 이 문서는 설계 시점 기록이다. 후속 구현·검증 상태: [P0–P3 구현 보고서](FEDPLANNER_EARLY_PRIVACY_PRUNING_IMPLEMENTATION_2026-09-28_KO.md).

- 작성: 2026-09-28. 분석 기준 HEAD `8769ef4dfaae5cc521188e0df07a9185cac1c3a2`.
- 상태: **설계 계획. 엔진·harness 구현을 변경하거나 새 성능 개선을 검증한 상태가 아니다.**
- 적용: DP-local / FedFirst / AggLocal / DP-global의 **동일한 공통 후보 생성기**.
- 유지: 사용자 지정 compile/runtime **60초 timeout**, Docker 전용 실험, runtime fallback 금지.
- 이전 계획의 “공통 후보 생성은 수정하지 않음”은 이번 요청으로 변경한다. 다만 플래너별로
  다른 후보를 생성하지 않고 **공통의 모든 합법적 대안을 보존**한다는 요구는 유지한다.

## 1. 결론

**privacy를 가능한 가장 이른, 증거가 확정된 지점에서 적용한다.** 순서는 다음을 권장한다.

1. 기존 privacy 판정기를 재사용해 **불법 emission의 realization/support 생성을 먼저 차단**한다.
2. 확정된 입력 privacy를 **Cartesian product 전에 적용**하여 불법 tuple은 Oracle 호출부터 생략한다.
3. source/action의 정확한 호환 관계로 binding product를 줄이고, 지원이 사라진 후보만 증분 제거한다.
4. 이후에도 DP/Exact가 크면 hard-support와 factor 표현을 별도로 개선한다. 임의 top-K는 쓰지 않는다.

중요한 구분:

| 줄이는 대상 | 이번 권장 방식 | 보존할 것 |
|---|---|---|
| 나중에 privacy로 버리는 중간 후보 | privacy predicate 앞당김 | 기존 최종 합법 후보·계획 |
| 서로 양립할 수 없는 binding 조합 | exact compatibility join / prefix rejection | 합법적인 모든 입력 증거 조합 |
| 살아 있는 지원이 없는 후보/증거 | 기존 support fixed point의 조기·증분 적용 | 최종 fixed point와 OR 대안 |
| 같은 의미를 반복 표현한 객체/계산 | 기존 exact dedup·cache 활용 | source/authority/비용/선택 순서 |
| 합법적이지만 비싸거나 선호하지 않는 후보 | **공통 공간에서 삭제하지 않음** | 네 플래너의 선택 자유 |

**조기 privacy pruning은 최종 합법 공간을 더 작게 만드는 새 규칙이 아니다.** 이미 나중에
탈락하던 후보를 만드는 비용을 없애는 것이다. 따라서 “최종 후보 수가 동일하지만 생성량·시간이
감소”해도 성공이다. 이것만으로 DP의 모든 조합 탐색이 작아지거나 60초 이내로 끝난다고 약속하지 않는다. [S1–S5, S13]

## 2. 현재 구현에서 확인한 사실

### 2.1 Privacy가 마지막에만 적용되는 것은 아니다

현재 큰 순서는 다음과 같다. [S1, S2]

```text
occurrence / CFG / shape 등의 program facts
  → 최초 후보 생성: 입력 domain의 Cartesian product, Oracle, emission
  → boundary / function 관계 확정
  → privacy inference 및 filtering
  → physical / relocation / support closure와 후보 재생성
      ↳ privacy 재적용
  → 최종 authority / 선택 / 검증 / emission
```

문제는 **첫 product를 생성할 때 privacy가 입력으로 들어가지 않는다는 것**이다.
`PlacementCandidateGenerator.buildNode`는 각 tuple마다 Oracle과 후보 객체를 만들고,
`forEachInputCombination`은 privacy prefix 검사를 하지 않는다. 현재도 tuple은 하나씩 순회하므로
단순히 iterator/stream으로 바꾸는 것만으로 방문 조합 수가 줄지는 않는다. [S3]

### 2.2 이미 있는 기능은 재구현하지 않는다

- privacy emission kernel, protected-payload input 검사, fresh-base privacy projection이 있다. [S4, S5]
- exact source/action에 따른 support 제거와 publication fixed point가 있다. [S10]
- relocation의 value/consumer 인덱스, source-option dedup, product cache가 있다. [S11]
- DP/Exact의 support reduction·동등값 quotient·singleton substitution과 DP의 conditioned compaction도 있다.
  다만 reducer의 arc consistency는 현재 factor 평가/동결 **뒤**에 실행된다. [S13]

따라서 개선은 주로 **판정 시점·중간 표현·반복 범위**를 바꾸는 작업이다.

## 3. 조기 privacy pruning의 정확한 규칙

### 3.1 먼저 구분할 세 가지 사실

1. **값의 privacy:** 정확한 occurrence와 value version에 속한 값이 공개 가능한가.
2. **소비 방식:** 해당 input position에서 coordinator가 payload를 읽는가,
   메타데이터만 읽는가, DML 함수에 handle만 전달하는가.
3. **물리적 실행:** 선택한 native/derived emission, 실제 source/anchor/action이 이동을 요구하는가.

이 세 가지를 `Hop → protected 여부` 하나로 합치지 않는다. 기존 authority와 predicate를 재사용한다.
함수 호출 handle 예외를 함수 formal의 payload 연산까지 확장하지 않는다. [S4–S7]

### 3.2 가장 먼저 제거할 수 있는 후보

| 확정된 증거 | 조기 제거 | 유지해야 할 예외/한계 |
|---|---|---|
| unreleased PRIVATE/PRIVATE_AGGREGATE **출력**과 해당 연산 규칙 | 허용되지 않는 CP·LOUT emission | 기존 함수 handle/metadata 및 연산별 정책 그대로 사용 |
| 해당 operand가 protected **payload**이고 입력 authority가 확정 | 그 소비 위치의 `ABSENT_LOCAL`; CP 계산 emission | metadata-only 접근과 함수 handle 전달은 별도 |
| exact native source emission이 privacy상 불법 | 그 source를 실행해야 하는 derived FOUT | 같은 row의 source가 필요; 다른 row의 합법 state로 대신하지 않음 |
| source의 모든 가능한 owner와 실제 movement 필요 여부가 확정 | origin-bound payload를 이동해야 하는 binding | direct/no-op까지 없애지 않음; UNKNOWN은 보존 |

근거는 기존 판정과 같다. 특히 **집계 결과가 PUBLIC이어도 원본 protected 입력을 CP로 모아
집계하는 것은 불법**이다. 반대로 이미 합법적으로 공개된 집계 결과를 입력으로 받는 후속 CP
경로는 유지한다. PRIVATE_AGGREGATE를 “모든 후손 FED-only”로 해석하지 않는다. [S4–S7]

### 3.3 UNKNOWN은 불법이 아니다

- 완전한 predecessor/CFG/function/value-version 정보가 없는 경우 pruning을 보류한다.
- 초기 PUBLIC seed를 “공개로 확정”한 사실로 재사용하지 않는다.
- loop/phi/callsite는 필요한 관계가 닫힌 시점의 revision에 판정을 묶는다.
- 아직 생성되지 않은 source/action을 영구 부재로 판정하지 않는다.
- `numWorkers` 전역 수치를 특정 candidate의 실제 worker pool/range 증거로 대체하지 않는다.
- source metadata 해석 실패나 Oracle 오류를 privacy rejection으로 포장하지 않는다.

`projectFreshBasePrivacy`도 임시 bottom이면 후속 CFG/source replay를 허용한다.
이 계약을 없애는 일괄 선필터는 금지한다. UNKNOWN 보존은 **compile-time 판단 보류**이며
runtime fallback이나 privacy 완화가 아니다. 최종 authoritative 검증은 그대로 남긴다. [S2, S5–S8]

## 4. 권장 구현 순서

### P0. 비교 기준과 숨은 단계 의존성 고정

**대상:** 기존 privacy/support/domain 테스트, `SearchSpaceMetrics`, `PlannerCandidateSpaceAudit`.

- 현재 최종 AVAILABLE row / emission / realization / OR support clause / action 관계와
  canonical 순서, raw cost를 작은 protected corpus에서 고정한다. 상태 개수만 비교하지 않는다.
- 초기 emission suppression과 실제 tuple omission을 분리해서 측정할 수 있게 한다.
- `PRIVACY_EXCLUDED` 행 존재로 `privacyAlreadyClosed`를 추론하는 코드를 우선 회귀로 고정하고,
  early rejection이 그 단계 완료를 의미하지 않도록 **명시적인 기존 privacy-authority 완료 상태**와 분리한다.
- 새 일반 분석 프레임워크를 만들지 않는다. 기존 immutable fact/closure context에 필요한 최소 정보만 둔다.

**통과 조건:** 기존 동작 parity, stage marker를 조기에 참으로 만드는 회귀 방지,
계측 on/off의 합법 관계·선택 결과 동일. [S8, S14–S16]

### P1. Emission filtering을 realization/support 생성 전으로 이동 — 첫 구현

**대상:** `PlacementCandidateGenerator`, `PlacementRelationClosure`, `ExecPlacementPolicy`.

- 확정된 privacy projection을 generator/재생성 경로에 전달한다.
- 최초에는 확정된 literal source/완전한 선행 정보가 있는 영역과, 기존 boundary/privacy closure
  **이후**의 replay부터 적용한다. 미확정 영역은 기존 방식으로 생성·최종 검증한다.
- 기존 output policy + protected input 검사 + same-row native-source closure를 같은 판정기로 적용한다.
- 불법 emission의 derived-action/realization/support 확장을 시작하지 않는다.
- 이 단계에서는 기존 row와 실제 Oracle capability/profile 계약을 보존한다.
  따라서 **Oracle 호출 수나 tuple 수 감소까지 달성했다고 주장하지 않는다.**
- `projectFreshBasePrivacy` 및 최종 privacy/authority 검사는 안전망으로 유지한다.

**통과 조건:** 최종 AVAILABLE 관계·비용·순서 동일; 보호 fixture에서 거절 emission의
후속 객체/증거 생성 감소; public aggregate/metadata/handle/derived-source 회귀 통과. [S3–S5, S8, S9]

### P2. 확정된 privacy를 Cartesian product 전에 적용 — 핵심 목표

**대상:** `PlacementProgramFacts`/`PlacementRelationClosure`의 fact 준비,
`PlacementCandidateGenerator`, `CandidateDomainRefinement`, candidate domain/lookup/audit 계약.

**A. 필요한 사실을 최대한 먼저 준비**

- source privacy, output transfer, consumer별 payload/metadata 접근을 위한 기존 관계를 재사용한다.
- 확정된 occurrence/value/CFG/function 관계만으로 닫을 수 있는 영역은 후보 생성 전에 privacy를 계산한다.
  일반 DAG는 producer-first 처리, cycle은 기존 유한 privacy lattice의 dependency worklist를 사용한다.
- 아직 후보/physical closure와 분리할 수 없는 경계는 기존 closure에서 확정된 직후부터 적용한다.
  전체 boundary 구조를 한 번에 candidate-free로 재작성하는 것을 첫 전환의 전제조건으로 삼지 않는다.
- 같은 관계/metadata를 다시 구축하거나 source privacy RPC를 중복 발행하지 않는다.

**B. Consumer별 입력 mask / prefix rejection**

- 확정된 protected-payload slot에서는 local 옵션을 제거한 domain으로 product를 순회한다.
  **producer의 전역 domain을 지우는 것이 아니라 해당 consumer/input position만 제한**한다.
- 나머지 operand 값과 무관하게 이미 불법인 prefix는 suffix를 생성하지 않는다.
  완성 tuple용 Oracle 결과를 근거 없이 임의 prefix predicate로 일반화하지 않는다.
- `buildNode`뿐 아니라 별도의 consumer/profile 입력 열거 경로에도 같은 증거 계약을 적용한다.
  그 profile은 가설적 FType 조합일 수 있으므로 **해당 consumer/source/revision의 authority가
  확정된 경우에만** 제한한다. 주변 실제 후보의 mask를 복사하지 않고, 미확정 profile은 보존한다.
- surviving tuple 순서는 기존 canonical 순서를 유지한다.

**C. “생성하지 않은 조합”의 완전성·진단 계약**

현재 excluded row도 실제 capability/profile을 요구한다. Oracle를 호출하지 않았는데
가짜 `PRIVACY_EXCLUDED` row를 만들거나, 누락 row를 전부 privacy rejection으로 간주하지 않는다. [S9]

- 좁은 immutable rejection evidence를 둔다: consumer/operand, exact privacy authority·value/domain revision,
  금지 predicate 및 적용 범위. 초기에는 단순 operand mask로 충분하며 일반 symbolic solver는 만들지 않는다.
- domain keys와 facts는 일관되게 구성한다. 실제 predecessor shrink와 consumer-local privacy mask를 구별한다.
- resolver는 **검증된 pruning 증거에 속한 요청**만 명시적 privacy 거절로 반환한다.
  증거가 없는 누락/foreign/reordered/stale 요청은 기존처럼 실패한다.
- audit는 생존 tuple뿐 아니라 압축된 거절 영역도 기록한다. 계측을 위해 거절 suffix를 다시 전개하지 않는다.
- 작은 fixture에서는 **같은 원래 unmasked domain revision**의 product를 독립적으로 열거해
  다음을 검증한다. 이미 pruning한 domain을 원래 기준선으로 삼지 않는다.

```text
원래 입력 product = 생성한 tuple ⊎ privacy로 거절함을 증명한 tuple
생성한 tuple ∩ privacy-거절 tuple = ∅
거절 tuple의 기존 최종 합법 emission/plan = ∅
```

현재 Cartesian completeness 테스트를 단순 삭제/완화하지 않고 위 coverage 검사로 강화한다.
후보와 증거 사이의 참조는 살아 있는 canonical 객체를 계속 사용한다. [S9, S12, S15]

**통과 조건:** 잘못된 거절·coverage 누락 0, 최종 합법 관계 동일,
protected fixture의 실제 input leaves/Oracle calls 감소, stale 증거 mutation 거절.

### P3. 추가 pruning: exact binding join + zero-support 증분 전파

**대상:** `PlacementRelationClosure`, `PlacementSupportRelations`.

1. 기존 `optionsByValue`/consumer-action 인덱스에서 source·candidate·slot·action authority가
   맞는 항목만 binding choices에 넣는다. product를 만든 뒤 버리는 것을 앞당긴다.
2. partial binding만으로 증명되는 동일 owner의 모순/expired source/불가능한 exact action은 즉시 거절한다.
   미선택 future action이나 OR sibling의 부재를 임의로 가정하지 않는다.
3. exact source → dependent clauses 역색인과 live-support count로 삭제를 전파한다.
   모든 facts를 매 round 재스캔하는 대신 영향을 받은 clause/realization만 갱신한다.
4. **action rebind가 완료된 삭제 전용 epoch**에서만 deletion worklist를 적용한다.
   CFG/loop widening·source/action replacement가 발생하면 해당 revision의 index/support를 갱신한다.
5. 기존 exact dedup/product cache를 재사용한다. 의미가 같은지 확인하지 않은 hash/FType/geometry 축약은 하지 않는다.

한 realization의 OR support는 하나라도 살아 있으면 유지한다. 반면 transient/phi에는
필요한 **모든 reaching writer**의 지원을 확인한다. 둘을 같은 “하나만 있으면 됨”으로 합치지 않는다.
COL×ROW FED/LOUT 등의 endpoint/lineage 경로가 있으므로 모든 FED 입력에 동일 전체 anchor geometry를
강제하지도 않는다. [S10, S11]

**통과 조건:** 기존 support fixed point와 canonical survivor 관계 동일;
expired OR sibling/모든 writer/action replacement/cycle 회귀 통과;
binding prefixes/leaves 및 전체 재스캔 작업 감소.

### P4. 선택 단계가 여전히 크면: 별도 DP/Exact 표현 개선 — 후순위

- 현재 reducer는 factor freeze 이후 unary/binary support reduction을 한다.
  **이미 구조적으로 아는 hard-support 관계**를 dense cost-factor 평가 전에 이용할 수 있는지 검토한다.
- generic cost callback을 미리 전수 평가하는 방식은 이득이 없으므로 하지 않는다.
- 적용 시 원래 값으로 복원되는 mapping, raw cost bits/tie 계약, auxiliary 의미,
  기존 cap-before-callback/error 순서를 별도 회귀로 보존한다. 그 계약을 만족하지 못하면 이 단계는 보류한다.
- 기존 conditioned compaction/quotient를 새 기능처럼 다시 만들지 않는다.
- higher-arity 전역 consistency나 cost dominance는 첫 계획에 넣지 않는다.
  전처리 자체가 또 하나의 무거운 탐색기가 되지 않게 한다. [S13, S16]

이 단계는 공통 합법 후보 삭제가 아니라 solver의 **동등한 문제 표현**을 줄이는 작업이다.
P1–P3 결과와 따로 측정하고, FedFirst/AggLocal에 비용 최적화 전처리를 강제하지 않는다.

## 5. 복잡도 목표와 금지할 shortcut

기존 input product가 `∏ |D_i|`라면 조기 mask 적용 후에는 `∏ |D'_i|`이며 `D'_i ⊆ D_i`다.
불법 prefix의 suffix를 방문하지 않는 것이 핵심이다. legal product 자체가 크면 최악의 경우는 여전히
조합적이다. “streaming으로 바꾸면 O(V+E)” 또는 “arc consistency로 전역 feasible 증명”은 주장하지 않는다. [S3, S10, S13]

목표는 **준비한 fact/인덱스 + 실제 변경된 dependency + 방문한 prefix + 실제 생성한 증거량**에
비례하는 작업이다. source range/Oracle 판정/closure revision 비용도 계측에서 숨기지 않는다.
FedFirst/AggLocal 선택부의 비백트래킹 정책은 유지한다.

제외하는 방법:
- top-K/beam/sample로 후보를 자르기, timeout을 넘기는 opcode를 임의 차단하기.
- worker=1을 이유로 privacy를 없애기, unknown shape/anchor를 불가능으로 취급하기.
- 가장 싼 candidate만 공통 domain에 남기기, FedFirst/AggLocal 선호로 DP/Exact 공간을 축소하기.
- PRIVATE_AGGREGATE downstream CP 전부 제거, protected relocation/no-op 전부 제거.
- TR/TW의 CP/FOUT 허용, recompile 업로드 완화, cap 상향, runtime fallback.

## 6. 검증·측정 계획

### 정확성: 구현 전에 고정할 회귀

| 축 | 필수 확인 |
|---|---|
| privacy | strict PRIVATE, PRIVATE_AGGREGATE, 합법 aggregate release, 공개 결과의 protected payload 수집 금지 |
| access | metadata-only nrow/ncol/length, DML handle vs formal payload, P2 authorized recode metadata만 공개 |
| physical | same-row native→derived source, CP/FOUT·FED/LOUT→FOUT, 실제 worker/range, no-op vs emitted move |
| control flow | mixed call contexts, 여러 writer, phi/loop widening, temporary bottom, recompile |
| authority | foreign/stale/version mismatch, missing vs certified-rejected tuple, hash collision, expired OR sibling |
| four planners | 동일한 공통 AVAILABLE 관계와 raw cost; deterministic surviving 순서·선택·receipt·최종 검증 유지 |

재사용할 주요 테스트는 `SharedPrivacyPlacementAnalysisContractTest`,
`CampaignBG014PlacementCandidateRuleFactsSliceATest`, `PublicationSupportClosureTest`,
`CandidateIncomingSupportCompletenessTest`, `IndependentPlanSpaceGenerationCompletenessTest`,
`GlobalReceiptPlanSpaceCompletenessTest`, `RelocationActionPlanSpaceCompletenessTest`,
`MixedPrivacyRelocationContractTest`, `PrivacyDerivedMaterializationClosureTest`다.
P4에는 `ExactPhysicalReducedSolverTest`, `SharedRegionalCompactionParityTest`, `RegionalCompactTest`를 추가한다.
PUBLIC-only workload는 기존 지침대로 제외하되 protected-X/public-Y 등 mixed 조건은 보존한다. [S15, S16]

### 성능: 최종 개수와 생성 작업량을 분리

기존 `SearchSpaceMetrics`에 부족한 항목만 추가한다. [S14]

- input prefixes/leaves, Oracle 호출 수, privacy mask/prefix 거절 수 및 UNKNOWN 보류 수.
- allocated emission/realization/support/action 수, final retained 수, 최대 live/할당 bytes.
- binding prefixes/leaves, source/action join 후보 수, worklist notifications와 scan 수.
- shared search-space / model·cost-factor build / selection / 전체 compile 시간을 구분.
- diagnostic 계측 on/off를 분리하고, timeout에는 완료 시간이나 감소율을 지어 넣지 않는다.

### Docker 전환 게이트

1. 계획 작성 중에는 현재 run05의 **frozen engine을 변경하지 않는다**. baseline survey는 계속한다.
2. 구현에 착수할 때 기존 실행을 소유권 확인 후 안전한 경계에서 종료하고 cleanup/lease를 확인한다.
3. targeted regression → 독립 diff review → package → 새 source/JAR/harness root 동결.
4. `run_LAN_docker.sh`만 사용해 DP protected logreg·l2svm 및 boundary fixture부터 비교한다.
   W1/3/5/7과 나머지 네트워크/워크로드/플래너로 확대한다. 이전 성공을 새 엔진 결과로 합산하지 않는다.
5. **60초 제한은 유지**한다. 동일 엔진의 896 compile 조건 통과 전에는 workload runtime을 시작하지 않는다.

## 7. 완료 기준 및 리스크

완료 기준:
1. P1–P3 전후 최종 합법 관계와 작은 프로그램의 전체 합법 물리 계획 집합 차집합이 양쪽 모두0.
2. P2 coverage partition이 완전·비중복이고, 인증 없는 row 누락은 계속 fail-closed.
3. 보호 fixture에서 P1의 불법 emission 후속 생성, P2의 tuple/Oracle 방문, P3의 binding/재스캔 작업이 각각 감소.
4. 새 pruning 이유마다 privacy/runtime/global-legality 근거와 회귀 테스트가 존재.
5. 최종 authority/privacy 검증 및 네 플래너 공통 생성 계약 유지, 정책/timeout/cap 변경0.
6. compile 시간·메모리·timeout 결과를 원본 증거로 보고. **계획만으로 개선율이나 full896 성공을 선언하지 않음.**

주요 리스크는 **잠정 정보를 확정 정보로 오인**, **privacy 금지와 증거 누락 혼동**,
**OR/CFG/relocation 재생성 관계 유실**, **전처리 자체의 비용 증가**다.
각각 revision-scoped authority/UNKNOWN 보존, certified coverage, 기존 fixed point parity,
실제 counter·phase timing으로 검출한다. 위험한 전역 재작성보다 P1→P2→P3의 독립 전환을 우선한다.

## 소스 근거

아래 경로는 `src/main/java/org/apache/sysds/hops/fedplanner/` 기준이며 이 문서 작성 HEAD의 line이다.

- **S1:** `placement/NeutralPlacementGraphBuilder.java:161–176`; `placement/PlacementProgramFacts.java:64–105` — 초기 facts와 closure 진입.
- **S2:** `placement/PlacementRelationClosure.java:386–399,492–542,647–684,799–858,1059–1164` — 생성/경계/privacy 순서 및 반복 closure.
- **S3:** `placement/PlacementCandidateGenerator.java:77–204,565–600,612–640` — input product, Oracle 및 별도 consumer/profile 경로.
- **S4:** `fedCostBased/commons/ExecPlacementPolicy.java:123–146,279–324`; `fedCostBased/FederatedPlannerUtils.java:1522–1590` — 기존 privacy kernel/transfer.
- **S5:** `placement/PlacementRelationClosure.java:1259–1393,1470–1489` — protected input, fresh projection, same-row native source.
- **S6:** `placement/PlacementAnalysis.java:3791–3799,3854–3869`; `placement/PlacementPrivacyFacts.java:58–74` — access 종류와 exact identity.
- **S7:** `placement/RelocationSelections.java:1642–1700` — 모든 source owner 및 no-op/movement 구분.
- **S8:** `placement/PlacementRelationClosure.java:1235–1241,1376–1379,2326–2338,2663–2673` — worker count, temporary bottom/widening, privacy 단계 추론.
- **S9:** `placement/PlacementAnalysis.java:1375–1398,1434–1448,1468–1491`; `placement/PlacementCandidateRuleResolver.java:147–170` — row evidence·domain·lookup 계약.
- **S10:** `placement/PlacementSupportRelations.java:114–200`; `placement/PlacementRelationClosure.java:811–872` — exact source/action, OR/전체 writer 및 support fixed point.
- **S11:** `placement/PlacementRelationClosure.java:6785–6814,6948–7028,7092–7140` — 기존 인덱스/cache, relocation product와 endpoint/layout 구분.
- **S12:** `placement/CandidateDomainRefinement.java:31–75`; `placement/PlacementRelationClosure.java:5255–5298`; `placement/PlannerCandidateSpaceAudit.java:78–116` — 합법 shrink와 raw/published audit.
- **S13:** `fedCostBased/fedExact/ExactPhysicalReducedSolver.java:434–481,561–647`; `fedCostBased/fedExact/SharedRegionalPreparation.java:153–188`; `fedCostBased/fedExact/LocalCategoricalOptimizer.java:599–607` — 기존 factor reduction/conditional compaction.
- **S14:** `placement/SearchSpaceMetrics.java:28–50,463–485,652–693`; `placement/CandidateFormationTiming.java:23–67` — 기존 작업량 및 시간 계측.
- **S15:** `src/test/java/org/apache/sysds/hops/fedplanner/placement/SharedPrivacyPlacementAnalysisContractTest.java:69–217,365–513`; `src/test/java/org/apache/sysds/hops/fedplanner/placement/CampaignBG014PlacementCandidateRuleFactsSliceATest.java:242–400`; `src/test/java/org/apache/sysds/hops/fedplanner/placement/PublicationSupportClosureTest.java:54–176` — privacy/domain/support 회귀.
- **S16:** `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolverTest.java:73–212,320–513`, `SharedRegionalCompactionParityTest.java`, `RegionalCompactTest.java` — reduction·raw cap·수치 의미 회귀.

S15/S16은 저장소 root 기준 경로다. 과거 정책용 projection/Search를 설명한
`G009_CURRENT_PRUNING_MECHANISM_AND_CORRECTNESS_2026-09-21.md`를 현재 greedy 실행 경로의 근거로
대체 인용하지 않았다. 초기 architecture 위험 평가는 **WATCH: 단계별 구현은 권장, 일괄 privacy closure 이동은 금지**다.
이 위험을 반영한 실제 계획 문서는 독립 architect 검토 **APPROVE**를 받았으며,
가설적 profile의 authority 범위와 unmasked revision 기준을 추가로 명시했다. 구현 승인/테스트 통과를 뜻하지 않는다.
