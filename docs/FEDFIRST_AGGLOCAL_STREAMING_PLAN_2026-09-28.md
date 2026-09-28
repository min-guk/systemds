# FedFirst · AggLocal 경량 greedy 전환 계획 — 공통 전체 후보 생성 유지

- 작성일: 2026-09-28
- 개정: **v2 — 사용자 최신 지시 반영. 공통 후보 전체 생성·공유를 유지하며 v1의 lazy/demand-driven 생성 설계는 철회한다.**
- 상태: **계획 수립 당시 snapshot**. 후속 구현·검증 현황 및 구체화 차이는 `docs/FEDFIRST_AGGLOCAL_IMPLEMENTATION_2026-09-28.md` 참조.
- 로컬 기준: `fe758c413d4d156cec249248f99a744eaa45edb5`
- upstream 비교 기준: Apache/SystemDS `b82911b0a032e59a01bcba3d669fd457e9e0042c`의 세 planner 소스. 최신 main 전체와의 동등성을 주장하지 않는다.
- 요청: upstream의 정책 철학을 따르되, optimality보다 낮은 **선택 비용**과 single-pass/online에 가까운 결정을 우선한다. 후보 생성은 FedFirst/AggLocal도 DP/Exact와 같은 전체 공통 분석을 사용한다.
- 선행 근거: [현재 알고리즘 보고서](FEDFIRST_AGGLOCAL_ALGORITHM_REPORT_2026-09-28.md), [품질 검토](FEDFIRST_AGGLOCAL_REVIEW_2026-09-28.md).

## 1. 결정 요약

**확정 범위: 전체 후보 생성은 그대로 공유하고, 그 위의 전역 first-feasible 선택을 정확한 실행 증거를 동반하는 producer-first greedy로 교체한다.**

```text
공통 전체 후보·제약·실행 증거 생성 — 현 구조 유지
    ├─ DP / Exact: 기존 선택 방식 유지
    ├─ FedFirst: 같은 후보에서 입력 우선 greedy 선택
    └─ AggLocal: 같은 greedy + 현재 aggregate-vector의 LOUT 선호
→ 공통 검증·atomic emission
```

여기서 유지하는 것은 현재 공통 builder의 입력 tuple·상태·candidate/realization·이동 후보 생성이다. **이미 생성된 후보를 공유하는 것과, 선택기에서 프로그램 전체 계획의 조합을 재탐색하는 것은 다르다.** 없애려는 것은 후자다. [L1, L2, L4, L6]

1. **FedFirst:** 이미 선택된 실제 입력에서 합법적인 FED 실행을 우선한다. 미래 FED/FOUT 개수나 이동 비용의 전역 최적값은 찾지 않는다.
2. **AggLocal:** FedFirst와 같은 흐름을 사용하되, 특정 aggregate-vector 연산의 **출력 LOUT**만 지역적으로 선호한다. 모든 후속 연산을 CP로 묶지 않는다.
3. 상태 → candidate row → relocation을 따로 전역 탐색하지 않고, **현재 연산의 상태·row·입력 binding·물리 action을 한 묶음으로 확정**한다.
4. AggLocal selector의 local-prefix hard projection과 전체 MOVEMENT_FIRST 재시작은 제거한다. 공통 분석이 이미 만드는 heuristic path facts는 이번 범위에서는 그대로 두되 새 정책 결정에 사용하지 않는다.
5. 공통 eager 후보 생성·closure·authority 구조는 변경하지 않는다. 선택 단계의 읽기 전용 인덱스, greedy 결정, 필요한 검증 접근만 개선한다.
6. privacy/runtime/anchor/TR-TW/authority 검증과 atomic emission은 유지한다. DP/Exact의 합법 후보 공간은 줄이지 않는다.

**이전 검토의 iterative DFS는 스택 오류만 고치는 최소 수정안이었다. 이번 요구에는 충분하지 않으므로 최종 설계로 채택하지 않는다.** 기존 탐색기를 당분간 유지해야 할 때의 별도 응급 수정과 이번 경량화 전환을 혼동하지 않는다. [L1, L2]

## 2. 요구사항과 비목표

### 반드시 지킬 것

- FedFirst/AggLocal/DP/Exact가 동일한 전체 후보 생성 경로와 canonical `PlacementAnalysis` 계약을 사용한다. heuristic 전용 축소 domain이나 lazy 후보 생성기를 추가하지 않는다.
- 반환된 계획은 실제 runtime과 privacy 규칙 아래 실행 가능해야 한다.
- 실제 FederationMap에 근거한 durable anchor만 사용하고, 업로드/다운로드/재배치는 명시적 증거와 action을 가진다.
- TRead/TWrite는 `<CP,LOUT>` 또는 `<FED,FOUT>`만 허용한다. recompile에서 금지된 CP/FOUT을 새로 허용하지 않는다.
- upstream의 지역 휴리스틱과 우리 시스템의 더 강한 합법성 검증을 결합한다. upstream의 단순한 지원 규칙이나 제어 흐름 가정을 그대로 복사하지 않는다.
- 이미 확정한 DAG 결정을 되돌리는 전역 backtracking, 전체 재시작, 암묵적 DP/Exact 전환을 새 경로에서 사용하지 않는다.
- 동등한 입력·규칙·authority에서 결정 및 certificate가 재현 가능해야 한다.
- 현재 성공하던 protected workload/경계 회귀를 그대로 통과해야 기본 경로를 전환할 수 있다.

### 목표에서 제외

- 공통 후보 생성 방식·전체 inventory·analysis authority 변경, heuristic 전용 on-demand Oracle 호출, full/demand 두 종류 analysis 도입.
- FED 수, FOUT 수, 통신량, 실행시간의 전역 최적성 또는 policy-maximality 증명.
- 임의 제약 그래프의 모든 feasible plan을 찾는 completeness 보장.
- 모든 CFG/함수/loop 분석까지 문자 그대로 단 한 번 읽는 strict streaming.
- 새로운 runtime fallback, privacy 완화, 지원 opcode 임의 차단, 임의 top-K 후보 절단.

**중요한 계약:** “optimal하지 않아도 된다”는 요청을 “기존 합법 workload가 새로 실패해도 된다”는 허가로 해석하지 않는다. bounded greedy가 모든 조합 제약을 해결한다고 보장할 수도 없다. 이 충돌은 6절의 경계 계약과 9절의 전환 게이트로 관리한다.

## 3. upstream에서 가져올 것과 가져오지 않을 것

| 항목 | upstream 소스에서 직접 확인한 동작 | 이번 설계 판단 |
|---|---|---|
| FedAll | federated 입력으로 지원되는 연산을 FED로 강제한다. 입력을 먼저 처리하고 HOP memo를 사용한다. | producer-first 지역 결정과 memoization을 따른다. [U1] |
| 출력 | FED 실행 여부와 결과 FType을 따로 판단하고, 결과 FType이 있으면 FOUT을 표시한다. | `FED 실행 = 반드시 FOUT`으로 취급하지 않는다. [U1, U3] |
| FedHeuristic | FedAll이 계산한 결과 FType이 ROW이고 열 수가 1이거나, COL이고 행 수가 1인 AggBinary 결과를 local로 취급한다. | aggregate-vector 출력의 지역 LOUT 선호로 구현한다. 모든 sum/집계 연산을 CP로 바꾸는 정책이 아니다. [U2] |
| 후속 연산 | 소비자의 실제 입력 FType으로 다시 FED 가능성을 판단한다. | local 결과의 모든 후손을 CP로 강제하지 않는다. 다른 resident FED 입력이 있으면 다시 FED가 가능하다. [U1, U3] |
| 제어 흐름 | conditional decision 일관성을 가정한다는 TODO가 있다. 함수 호출마다 본문을 다시 방문할 수도 있다. | 이 가정/재귀 구현까지 복제하지 않는다. 우리 shared formal·phi·loop 계약을 유지한다. [U1] |

**해석:** “주어진 federated 데이터 흐름을 이용하는 설명 가능한 저비용 baseline”이라는 방향은 위 코드로부터의 설계적 해석이다. 저자 의도를 따로 확인한 진술이나 upstream 전체 프로그램의 엄밀한 O(V+E) 증명은 아니다. 일반 HOP DAG의 memo 순회는 선형적이지만 함수 재방문, 규칙 평가, worker range 처리 비용은 별도다. [U1, U3]

우리 Oracle/runtime이 upstream보다 지원하는 연산이 많아도 그대로 활용한다. upstream whitelist로 후보 공간을 되돌리지 않는다. [L4]

## 4. 정책 명세 — 구현 전에 테스트로 고정

### 4.1 FedFirst

입력은 부모들의 선택 결과와 현재 소비 위치의 확정된 boundary contract다.

1. 실제 resident FED 입력을 사용할 수 있고 정확한 실행 증거가 있으면 FED를 우선한다.
2. 그런 FED 실행 안에서는 native FOUT을 선호하되, runtime이 local 출력만 지원하면 FED/LOUT을 그대로 채택한다.
3. 현재 FED consumer를 실현하는 데 필요한 합법적인 입력 이동/정렬은 허용한다. 정확한 source·slot·anchor·privacy 증거를 함께 확정한다.
4. FED가 현재 입력에서 성립하지 않고 합법적인 local 실행이 있으면 CP를 선택한다.
5. 입력이 모두 local이고 별도 hard requirement가 없다면, **어딘가에 anchor가 있다는 이유만으로 업로드해서 FED를 만들어내지 않는다.** 이때 CP가 자연스러운 선택이다.
6. CP가 hard constraint 때문에 불가능하고 정확히 증명된 anchored materialization으로만 실행이 가능하다면 그 합법적인 경계 처리는 허용한다. 정책 선호 때문에 실행 가능한 조합을 공통 domain에서 삭제하지 않는다.

동순위에서는 현재 입력을 직접 소비하는 증거, 이미 선택된 동일 action의 재사용, 안정적인 rule/occurrence 순서를 사용한다. future FED 개수나 전체 이동량을 계산하지 않는다. 이 순서는 최적화가 아니라 deterministic local preference다.

**의도적인 변화:** 현재의 “가능한 상태 중 FED/FOUT 우선 + 전역 해가 나올 때까지 되돌리기”보다 약한, 실제 입력에 조건부인 FED 우선 정책이다. 오래된 클래스명 `MaxFedFoutSinglePass`를 의미 명세로 사용하지 않는다. [L1]

### 4.2 AggLocal

FedFirst에 다음 **현재 연산의 출력 선호**만 추가한다.

- 현재 선택된 입력 tuple에서 aggregate-vector 패턴이고 exact FED/LOUT realization이 합법이면 그 출력을 먼저 선택한다. 연산 자체는 FED일 수 있다.
- 핵심 ROW/COL 조건은 upstream의 **결과 orientation** 의미에 맞춘다. 임의의 미선택 입력 대안 중 하나가 맞는다는 이유로 marker를 만들지 않는다.
- 우리 FULL one-worker vector 조건은 upstream literal rule에는 없는 확장이다. runtime의 exact local-output 증거가 있을 때만 **명시적으로 문서화된 지역 선호**로 유지한다. shared legal universe는 그대로 둔다. [L4]
- privacy 또는 shared formal/TR-TW 계약이 FOUT을 요구하면 **그 연산의 LOUT 선호만 적용하지 않는다**. 다른 aggregate의 선호를 전부 폐기하지 않는다.
- 후속 연산은 실제 입력으로 재판단한다. all-local이면 일반적으로 CP, resident FED sibling을 native로 소비할 수 있으면 FED다.
- LOUT을 바로 다시 업로드할 필요가 없는 native row가 있으면 그것을 우선한다. 실제 소비 계약이 요구하는 명시적 reentry는 허용하되 global frontier를 미리 탐색하지 않는다.
- `localPrefix` hard projection과 strict→base/MOVEMENT_FIRST 전체 재탐색은 없앤다. `cameFromLocalAggregate`를 남기더라도 설명용 태그이지 후손을 강제하는 제약이 아니다. [L3]

예상 동작:

```text
resident FED matrix → aggregate-vector FED/LOUT → local unary CP
                                                 ↓
                                다른 resident FED matrix와 연산 → native FED 가능
```

**FED/LOUT ≠ CP/LOUT, local output ≠ downstream all-CP.** 기존 AggLocal의 강한 local-chain 보장을 유지하는 리팩터링이 아니라 upstream 쪽으로 정책을 단순화하는 변경이다.

## 5. 실행 구조 — 단일 decision pass, 증거는 같이 선택

### 5.1 준비·선택·적용 분리

```text
공통 builder가 만든 완성된 PlacementAnalysis 전체
  → 기존 후보·realization·support·action의 읽기 전용 인덱스
  → equality/alias 묶기 + CFG/function/loop boundary contract
  → iterative producer-first ready queue
  → 기존 후보 중 현재 입력과 호환되는 exact bundle 검사
  → state + exact row + support + input actions 묶음을 한 번 확정
  → selected-plan 전체 검증 1회
  → 기존 atomic emission
```

여기서 “확정”은 계획 내부의 immutable decision 확정이지 HOP/registry 즉시 변경이 아니다. 실제 변경은 전체 검증 뒤 transaction에서만 한다. [L8]

개념적 묶음은 기존 receipt/value 타입을 우선 재사용한다. 이름만 다른 추상화 계층을 여러 개 추가하지 않는다.

```text
DecisionBundle = {
  occurrence/equality-group, exec/output/FType,
  exact candidate + realization + support clause,
  input bindings + selected movement receipts + durable anchor metadata
}
```

```text
enqueueInitiallyReadyUnits(boundaryContracts)
while readyQueue or pendingMonotoneFactEvents:
    applyFactDeltasAndWakeIndexedSubscribers()
    unit = popReadyUnitIfAny()
    if unit is NONE or unit is already committed: continue
    result = selectFromOwnedBundlesWithCurrentCertifiedFacts(unit, policy)
    if result is DEFERRED(waitingOnFactVersions):
        registerIndexedWaiters(unit, waitingOnFactVersions)
    else if result is a fully witnessed best local bundle:
        commitDecisionOnce(result)
        publishSelectedOutputAndBoundaryFactDeltas(result)
    else:
        return typedFailureBeforeEmission(unit)

if any unit is unresolved:
    return UNRESOLVED_BOUNDARY_CONTRACT(noProgressSccEvidence)

freezeSelectedPlanUsingOwnedReceipts()
validateWholePlanWithoutSearch()
emitAtomically()
```

- 현재 occurrence의 기존 후보를 비교하는 것은 허용한다. 새로운 Oracle 평가나 후보 조합 생성을 하지 않으며 확정된 producer를 되돌리는 backtracking도 하지 않는다.
- state만 먼저 선택한 뒤 전체 row·relocation 조합을 다시 찾는 방식은 금지한다.
- movement는 exact action key를 intern해서 이미 선택된 동일 이동만 재사용한다. 전역 최소 movement 조합은 구하지 않는다.
- FOUT source를 local consumer가 쓰는 경우 원본 producer를 CP로 되돌리지 않는다. 합법적인 per-use local materialization을 선택한다.
- future state에 의존하는 action 활성화/suppression은 현재 참으로 가정하지 않는다. boundary contract에 포함하거나 결정을 보류한다.

### 5.2 보류와 재개 — 미확정 노드만 사건 기반으로 처리

`DEFERRED(waitingOn)`는 실패와 다르다. 아직 확정되지 않은 producer 출력 또는 boundary/support fact의 **식별자와 관측 version**을 함께 반환한다.

- `fact → waiting units` 역색인을 사용한다. 명시한 fact가 단조롭게 바뀔 때만 해당 미확정 unit을 깨운다. 매 round마다 모든 deferred unit을 스캔하지 않는다.
- 동일 fact/version 이벤트와 queue 항목은 중복 제거한다. waiter 등록 시 version을 재확인해 조회와 등록 사이 변경을 놓치지 않는다.
- 한 번 확정된 decision은 구독을 해제하며 다시 선택하지 않는다. 보류 노드의 재평가와 이미 확정된 선택의 backtracking을 구별한다.
- hard fact별 유한 lattice 높이 `h_f`를 명세한다. unit의 재평가 횟수는 초기1회와 구독 fact의 실제 version 전이 수로 제한한다. 예컨대 상한은 `1 + Σ_f(h_f - 1)`이다. duplicate wake는 이 상한을 소모하지도, 재평가를 일으키지도 않는다.
- fact 전파 작업은 `Q`, 각 wake에서 실제 수행한 기존 candidate/support 검사는 `R`에 모두 포함한다. version 증가 자체만 세고 재평가의 내부 비용을 숨기지 않는다.
- SCC 요약의 seed/entry와 모든 필요한 계약이 인증되면 해당 unit이 ready가 된다. 내부의 미래 producer를 자기 증거로 가정해 순환을 끊지 않는다.
- ready queue와 monotone fact event queue가 모두 비었는데 미확정 unit이 남으면 no-progress SCC와 waiting fact 목록을 보존하고 `UNRESOLVED_BOUNDARY_CONTRACT`로 종료한다. 전체 재시작·polling·hidden solver는 없다.
- 입력/경계 증거가 아직 unknown이면 `NO_LEGAL_WITNESS_FOR_COMMITTED_INPUTS`로 조기 실패시키지 않는다. 이 결과는 필요한 입력 계약이 확정된 상태에서만 사용한다.

### 5.3 전체 후보는 공통 생성, 인덱스만 정책 선택기가 소유

**현재는 selector 실행 전에 전체 PlacementAnalysis가 만들어진다. 이 구조를 그대로 유지한다.** 입력 domain의 Cartesian product, closure, privacy/shape/anchor 및 기존 heuristic facts 생성 비용은 모든 planner가 공유하는 선행 비용으로 둔다. [L4, L5, L6]

읽기 전용 인덱스에 담을 것은 이미 존재하는 다음 관계다.

- occurrence → candidate rule/allowed emission/realization/support clause
- producer 또는 boundary fact → 이를 기다리는 candidate/support incidence
- consumer/input slot → 가능한 input binding/local materialization/relocation action
- action key → 기존 graph-owned action, source/anchor 및 activation/suppression 조건
- equality/alias group → 기존 hard constraint와 후보 호환 관계

구현 계약:

1. 기존 analysis의 canonical state/key/receipt 참조와 조회 API를 우선 사용한다. 필요한 인덱스는 selector invocation-local로 한 번 만들고 호출 종료 시 버린다. 기존 적합한 인덱스가 있으면 재사용한다.
2. 읽기 전용 wrapper는 후보/authority를 새로 만드는 계층이 아니다. policy가 호출할 Oracle, candidate generator, 별도 analysis builder는 없다.
3. 후보 순회는 occurrence별 인덱스로 제한한다. 노드마다 모든 후보를 다시 스캔하거나 row×relocation×다른 노드의 곱을 만들지 않는다.
4. action은 선택한 row의 정확한 support/input binding에 연결된 기존 후보에서 고른다. 여러 대안이 있으면 지역 우선순위로 고르되 전역 조합 최적화나 별도 relocation DFS로 넘어가지 않는다.
5. 논리적으로 같은 값으로 새 state를 재구성해 identity 검사를 우회하지 않는다. 결과는 기존 domain API를 통해 소유권이 검증된 receipt로 만든다. [L7, L8]
6. 특정 committed input과 맞지 않는 row는 그 선택기 실행의 활성 후보에서만 제외한다. 원본 universe에서 삭제하거나 모든 planner에 불법인 후보로 기록하지 않는다.
7. `DMLProgram`, `DMLTranslator`, `NeutralPlacementGraphBuilder`, `PlacementCandidateGenerator`, `PlacementRelationClosure`, `PlacementProgramFacts`의 생성 경로와 `PlacementAnalysis`의 authority/receipt 소유 계약은 이번 구현의 수정 대상이 아니다.
8. 공통 `heuristicPolicyFacts`의 eager 계산도 일단 유지한다. 새 AggLocal이 사용하지 않는다는 이유로 공통 builder를 바꾸지 않는다. 이 계산의 제거/지연화는 본 계획의 후속 필수 단계도 아니다.

**v1의 demand-driven analysis 단계는 삭제한다. 별도 후보 생성 경로·lazy receipt 등록·새 authority freeze 계약을 만들 필요가 없다.**

## 6. 공유 값·분기·루프: 가벼움과 실행 가능성의 경계

### 허용하는 사전 작업

- 동일 concrete Hop alias와 `SAME_PLACEMENT`를 먼저 묶는다. 서로 다른 shared callsite가 같은 physical Hop을 제각각 확정하지 않는다.
- 실제 proof dependency까지 포함한 그래프를 만들고, iterative SCC와 안정적인 ready order를 계산한다.
- phi는 모든 reaching definitions를 본다. 한 branch의 local 값만으로 합류점을 local이라고 판단하지 않는다.
- loop는 entry/backedge, 함수는 실제/formal/output의 공동 계약을 먼저 확정한다. iteration마다 프로그램 전체를 다시 계획하지 않는다.
- hard legality fact의 monotone delta-worklist는 허용한다. fact 변화에 연결된 incidence만 갱신한다. 이것은 정책 선호를 반복 개선하는 탐색과 구별한다. [L9]

### 허용하지 않는 우회

- 선택 단계에서 SCC 전체 계획의 Cartesian product를 새로 풀거나 “작은 exact solver”를 숨겨 넣는 것. 공통 생성기가 기존 입력 tuple을 전부 만드는 것과 구분한다.
- union-find 또는 arc consistency만으로 전역 실행 가능성이 증명됐다고 주장하는 것.
- 임의 SCC 크기/top-K 가드로 runtime-supported 후보를 공통 universe에서 삭제하는 것.
- 현재 선택과 맞지 않는 phi 입력을 무시하거나 shared formal/보호된 값을 임의 local로 내리는 것.

**설계 게이트:** straight-line 영역과 지원하는 boundary 종류 각각에 대해, 입력 계약에서 선택한 묶음들이 어떻게 합성되는지 증명한다. SCC 요약은 유한한 fact와 명시된 transfer rule이어야 하며, 인터페이스 모든 조합의 표를 만들어 비용을 숨기지 않는다. 해결되지 않는 boundary는 새 기본 경로 전환의 blocker다.

### 실패 의미

개발·검증 중 새 선택기가 멈출 수 있는 경우를 다음처럼 typed 결과로 구분한다.

| 결과 | 의미 |
|---|---|
| `DEFERRED(waitingOn)` | 진행 중인 상태. 지정한 fact 전이로만 재개하고, no-progress일 때 아래 boundary 오류로 종료한다. |
| `NO_LEGAL_WITNESS_FOR_COMMITTED_INPUTS` | 지금 선택한 입력에 맞는 증거가 없음. 다른 전역 선택까지 불가능하다는 뜻이 아님. |
| `UNRESOLVED_BOUNDARY_CONTRACT` | 함수/phi/loop/anchor 결합의 합법성을 현재 경량 계약으로 증명하지 못함. |
| `PROVEN_LEGALITY_CONFLICT` | 명시적 hard rule과 그 근거로 충돌이 증명됨. 증명이 포괄하는 범위도 기록함. |
| `INTERNAL_PROOF_MISMATCH` | compiler/authority 버그. 정책 완화로 덮지 않음. |

실패 결과는 모두 emission 전에 종료한다. 예외 메시지 prefix 파싱, 조용한 CP runtime fallback, 숨은 old-search/DP/Exact 전환은 없다. 운영상 work budget을 두더라도 초과를 별도 **미해결**로 기록하며 복잡도 증명의 대체로 삼지 않는다.

**지원 범위가 실제로 줄어드는 결과가 나오면:** 후보를 자르거나 성공 기준을 낮추지 않는다. 해당 사례를 boundary contract 개선 항목으로 돌리고 기본 전환을 보류한다. 임의 프로그램의 completeness를 추가로 요구하면 single-pass 목표와의 tradeoff를 다시 설계해야 한다. 비최적성만 포기한다고 이 문제가 저절로 해결되지는 않는다.

## 7. 복잡도 목표와 측정 계약

다음 기호를 사용한다.

- `V, E`: 정규화된 occurrence 수와 data/proof dependency incidence 수. 원문 HOP 수만 세지 않는다.
- `C`: 공통 생성이 끝난 candidate/allowed emission/realization record 수.
- `S`: 그 후보에 연결된 support clause·입력 binding의 incidence 총수.
- `M`: 이미 생성된 materialization/relocation/anchor 및 activation/suppression 관계의 크기.
- `R`: 선택 중 실제 수행한 candidate/support/action incidence 검사량. 재평가 비용도 포함한다.
- `Q`: 선택기 내부 hard boundary/호환성 fact 갱신·이벤트 전달 작업량. 공통 builder의 closure 비용은 아래 T_common에 포함한다.
- `P`: 선택한 proof/action/receipt의 총 크기.
- `T_common, M_common`: **기존 전체 공통 후보 생성·closure·authority 구성 시간과 메모리**. 줄이거나 selector 밖으로 숨기는 대상이 아니다.

목표:

```text
T_index   = O(V + E + C + S + M)
T_select  = O((V + E) α(V) + R + Q) + T_stable_order
T_total   = T_common + T_index + T_select + T_validate_emit
M_total   = M_common + O(V + E + C + S + M + P)
```

- **핵심 목표는 생성된 전체 후보·증거의 크기에 가까운 선형 선택 overhead**다. 일반 DAG에서는 각 row/입력 incidence의 검사 횟수를 제한해 `R=O(C+S+M)`을 지향한다. boundary 재평가에서는 실제 fact 전이와 영향을 받은 incidence로 상한을 증명한다.
- equality union-find의 α(V), canonical sorting이 필요할 때의 O(n log n)을 숨기지 않는다.
- `C/S/M` 자체가 원문 HOP 수보다 훨씬 클 수 있다. 전 조합 생성을 유지하므로 **전체 계획이 HOP 수에 O(V+E)라고 주장하지 않는다.** 공통 생성에 이미 있는 조합 비용은 사용자 선택에 따라 그대로 수용한다.
- boundary의 fact lattice 높이와 재평가 비용을 검증하기 전에는 일반 CFG 전체에 선형 선택을 보장하지 않는다.
- 목표는 **공통 전체 생성 이후의 입력 우선·단일 commit 흐름**이다. 생성까지 streaming하거나 전체 메모리를 O(1)로 만드는 계획이 아니다.
- HashMap 평균 비용과 정렬/서명 직렬화 비용도 실제 계측에 포함한다. 긴 normalized signature를 매 비교마다 재생성하지 않는다.
- 최종 validator도 입력마다 모든 action을 스캔하면 선형이 아니다. consumer/input-slot/action-key index로 selected incidence만 확인한다. [L10]

필수 counter: 공통 영역의 `generatedCandidateCount`/생성 시간은 그대로 기록하고, 선택 영역에 `indexEntries`, `candidateChecks`, `proofIncidenceVisits`, `boundaryFactUpdates`, `decisionCommits`, `decisionRevisions`, `globalSearchCalls`, `policyRestarts`, `selectorOracleCalls`, `selectedProofCount`, `policyPreferenceDeclines`, `runtimeFallbackCount`를 분리한다.

`policyPreferenceDeclines`는 합법성 때문에 현재 선호를 적용하지 않은 횟수다. runtime fallback과 섞지 않는다. 기존 component 지표도 structural/constraint/SCC 중 어떤 의미인지 명시한다. 최적성 상계 certificate는 휴리스틱 성공 조건에서 제거하고 legality/policy/work certificate로 대체한다.

## 8. 구현 순서와 파일 경계

각 단계는 계획이며 아직 수행하지 않았다. 각 단계 시작 전 보호 테스트를 추가하고, 큰 동작 변경과 구조 추출을 별도 commit으로 나눈다.

| 단계 | 작업·주요 대상 | 완료 게이트 |
|---|---|---|
| P0 공통 공간·정책 회귀 고정 | 두 adapter/planner 및 기존 selector 테스트. 전체 후보·proof inventory와 policy 전후 fingerprint, 4절 기대 선택을 고정. [L1–L4, L7] | 기존21건과 4,096-node 스택 실패 증거 보존. 모든 planner의 같은 생성 경로·후보 공간 및 성공 corpus 목록 확정. |
| P1 읽기 전용 index·경계 준비 | `placement/selector/`의 invocation-local index. `CandidateSelections`, `RelocationSelections`, `PlacementSupportRelations`의 기존 조회/검증 로직을 필요한 만큼만 재사용·추출. [L2, L7–L10] | 추가 후보/Oracle 호출0, analysis mutation0. 정확한 row/support/action 연결과 hard boundary 합성 검증. DEFERRED version wakeup·finite bound·no-progress 회귀 통과. |
| P2 FedFirst greedy | `placement/selector/`의 iterative 선택기, `FedAllPlacementAdapter`, `FederatedPlannerFedAllMaxFedFoutSinglePass`. [L1] | state/row/relocation의 전역 탐색·rollback0. 기본 stack의 깊은 DAG 통과. canonical 전체 universe에서 정확한 상태·증거 동시 선택. |
| P3 AggLocal 지역 규칙 | 같은 선택기에 지역 preference 추가, `HeuristicPlacementAdapter`, `FederatedPlannerFedHeuristicSinglePass` 및 receipt 설명 정비. 공통 closure는 수정하지 않음. [L3, L5] | selector의 localPrefix 강제·MOVEMENT_FIRST 재시작0. 공통 path facts는 생성 유지하되 정책에 미사용. native FED 재진입·무관한 demotion 보존. |
| P4 검증·관측 정비 | selected input/action index, 기존 normalization/emission, 선택 작업 counter/certificate 및 stale 주석. [L8, L10] | 기존 authority/privacy 검증 유지. 반복 전체 scan 제거. 생성 비용과 선택 비용 분리, 기존 config 호환 유지. |
| P5 Docker 검증 및 전환 | 아래 검증 행렬, factory/default 연결, 알고리즘 문서 갱신. | 성공 corpus 감소0, 공통 생성 domain 불변, 숫자·privacy·placement 정확성, 선택 작업량 상한·시간 개선 확인 후에만 기본 경로 전환. |

DP/Exact 알고리즘·공통 후보 생성·analysis binding은 수정 범위 밖이다. P1/P4에서 공유 helper나 validator를 추출/정비하면 DP/Exact 동등성 회귀를 수행한다. 기존 탐색 코드를 바로 삭제하지 않고 차등 검증 기준으로 남기되, 새 경로 실패 시 자동 호출하지 않는다. 호출자가 없어지는 코드만 마지막에 제거한다.

## 9. 테스트 가능한 수용 기준

| ID | 필수 검증 | 합격 기준 |
|---|---|---|
| A1 | FedFirst 실제 FED 입력/FOUT·LOUT/전부 local | 4.1의 기대 선택과 일치. all-local은 불필요한 speculative FED 유도0. exact hard requirement 경계는 별도 성공. |
| A2 | ROW/COL aggregate-vector, FULL 확장, scalar/AggUnary 반례 | 지정된 AggBinary local-output 선호만 적용. generic aggregate→CP 규칙 없음. |
| A3 | demotion→unary→FED sibling, nested aggregate | local unary CP와 native FED 재진입 모두 정확한 receipt로 확인. 후손 일괄 CP 없음. |
| A4 | PRIVATE/PRIVATE_AGGREGATE, protected sibling/formal | 금지된 원본 수집0. 합법적인 aggregate 결과 수집 성공. privacy 때문에 한 선호가 거절돼도 무관한 선호는 유지. |
| A5 | missing/다른 pool anchor, rmvar, local-only FED output→FOUT 필요 | 가짜 anchor/불법 이동0. 지원되는 실제 anchored boundary는 성공. 기존 durable metadata 계약 유지. |
| A6 | TR/TW, shared Hop/formal, branch 양쪽·한쪽 local, loop entry/backedge, recompile | 현 합법성 계약과 동일. CP/FOUT transient0. 한 predecessor 증거만으로 phi 승인0. |
| A7 | 선호 local bundle 실패, 작은 reconvergent 반례 | 현재 unit 내 다음 합법 bundle 검증. 이전 commit 의존 충돌은 typed 미해결, 전역 infeasible 오판0, emission 변이0. 기존 성공 corpus에서 이런 실패가 나오면 cutover 불가. |
| A8 | 깊은 connected chain 512/4,096/16,384/65,536 및 diamond/fanout | 고정한 기본 JVM stack에서 새 selector StackOverflow0. decisionCommits=unit수, revisions/globalSearchCalls/policyRestarts=0. graph seam과 실제 Oracle/DML 경로를 따로 검증. |
| A9 | 후보 수·arity 증가, 다중 anchor/consumer, 큰 boundary | 실제 전체 C/S/M을 분모로 candidateChecks/visits 상한 검사. **공통 전체 조합 생성은 유지**, 선택기에서 추가 조합 생성·Oracle 호출·전역 경계 조합표 생성0. 단순 timeout 성공을 선형성 근거로 사용하지 않음. |
| A10 | 공통 전체 universe·analysis identity·mutation/foreign receipt | 같은 analysis를 전달받고 정책 선택 전후 domain/proof inventory·fingerprint 불변. 전환 전후 공통 후보/physical multiset 양방향·multiplicity 동일. DP/Exact 결과 계약 유지, 비소유 receipt 거절. |
| A11 | 최종 candidate/action 검증·emission 실패 주입 | 정확한 slot/anchor/activation 검증 유지. 실패 시 HOP/registry 복원, runtime fallback/repair0. |
| A12 | 반복·삽입 순서·동시 호출·재컴파일 | 결정 및 canonical certificate 재현성, cache/authority 누출0. |
| A13 | Docker 수치·계획비용 | 사전 고정 corpus 모두 수치 허용오차 내 동일; 기존 성공 대비 신규 실패0. 공통 생성/선택/검증 시간을 분리하고 전체 계획 시간·heap도 함께 보고. 생성 비용을 줄였다고 해석하지 않음. |
| A14 | 늦게 준비되는 증거·중복 이벤트·waiter 등록 경합·seed 없는 SCC | 필요한 사실이 도착하면 보류 unit이 성공하고, 같은 fact/version 재평가0. wake 횟수는 선언한 fact 전이 상한 이내. 진전 없는 순환은 조기 infeasible이나 무한 재스캔 없이 boundary 오류, emission 변이0. |

기존 21건은 출발점이며 전체 correctness 증명이 아니다. 기존 테스트가 global-backtracking 또는 강제 local-prefix 자체를 요구한다면 새 계약과 맞는지 분류하고 **정책 assertion만 명시적으로 변경**한다. hard legality·수치·privacy assertion은 약화하지 않는다. 특히 기존에 성공한 feasible 반례를 단순 expected-failure로 바꿔 기본 전환을 통과시키지 않는다.

공통 정책 비교는 upstream과 우리 Oracle이 모두 지원하고 hard constraint 차이가 없는 부분에서 수행한다. 전체 plan hash 동일성, FED 최대 개수, DP/Exact와 같은 최적 선택은 합격 조건이 아니다.

## 10. 검증 실행·성능 실험 계획

1. JUnit: 기존 selected21건 + 새 protected/boundary/greedy/authority 회귀 → 공통 분석·DP/Exact 회귀 순서로 실행한다. 저장소 지침대로 public privacy fixture는 실행 선택에서 제외하고 그 공백을 명시한다. 새 테스트 성공을 위해 public 여부나 Ignore를 바꾸지 않는다.
2. 바뀐 Java에 대해 compile, 해당 저장소에서 재현 가능한 style/static checks, targeted tests를 수행한다. 기존 baseline findings와 새 findings를 구분한다.
3. **현재 `scripts/fedplanner/run_LAN_docker.sh`는 selector/runtime workload가 아닌 frozen 공통 search-space harness의 wrapper다.** 이 wrapper의 성공만으로 새 planner의 수치 정확성·전체 시간을 검증했다고 할 수 없다. [L11]
4. P5 전에 versioned Docker planning/runtime lane을 준비하고 같은 `run_LAN_docker.sh` 진입점에서 명시적으로 실행할 수 있게 한다. frozen 외부 harness/evidence는 수정하지 않고, 기존의 별도 paused 성능 goal을 재개하지 않는다. host `run_LAN.sh`는 사용하지 않는다.
5. 동일 source/JAR provenance, image, worker 수, 데이터, 네트워크, JVM heap/stack으로 old/new를 비교한다. baseline과 새 경로 모두 analysis부터 certificate/emission까지 포함한다.
6. cohort는 chain/diamond/n-ary/boundary별 규모와 protected 실제 workload로 사전에 고정한다. 각 조건 warm-up1회·측정5회 같은 반복 규칙과 순서를 **실험 전 manifest에 고정**하고 실패·불리한 표본을 모두 보존한다. 결과를 보고 반복 횟수를 늘리거나 좋은 실행만 채택하지 않는다.
7. deterministic counter bound를 주 증거로, wall time/peak heap을 실측 보조 증거로 삼는다. 생성된 C/S/M과 dependency incidence가2배인 cohort에서는 **선택 단계** visits의 증가를 검사한다. HOP 수만2배인데 후보 수가 더 크게 증가한 실험을 선형 선택의 반증/증명으로 오해하지 않는다.
8. 기본 전환 기준: 모든 필수 성공 corpus 유지, counter 계약 준수, 새로운 스택 실패 없음, 선택 비용이 큰 cohort에서 선택 시간 감소. 공통 생성 지배 workload는 전체 시간 개선이 작아도 이를 숨기지 않는다. 기존 workload의 전체 계획 median이5% 넘게 악화되면 원인 조사·해결 전 전환을 보류한다. 이5%는 앞으로의 조사 게이트이지 이미 측정된 성능 약속이 아니다.

현재 wrapper 확장과 실제 Docker 실행은 아직 수행하지 않았다. 구현 전에는 고정 배수의 속도 향상이나 모든 workload의 runtime 향상을 약속하지 않는다. 정책 최적성을 포기하므로 생성 계획의 실행시간이 달라질 수 있으며 planning time과 runtime을 따로 보고한다.

## 11. 주요 위험·완화

| 위험 | 완화 / 중단 조건 |
|---|---|
| greedy가 나중의 coupling을 막음 | 공통 분석의 합법 후보 존재만으로 전체 조합이 호환됨을 가정하지 않음. 경계·alias 계약 및 기존 성공 corpus 유지 게이트. 미해결을 infeasible로 위장하지 않고 지원 감소 시 P1로 복귀. |
| 선택용 index/filter가 원본 domain을 변경함 | 원본 객체 참조·분리된 invocation-local 상태 사용. 선택 전후 universe/analysis fingerprint 및 DP/Exact multiset parity. |
| global search를 row/anchor/boundary helper로 옮김 | 실제 호출 counter와 incidence bound, n-ary·큰 SCC 반례. budget으로 실패만 빨라지는 결과는 개선으로 인정하지 않음. |
| validator/certificate가 다시 quadratic | selected input/action index와 digest cache, 전체 pipeline 계측. |
| AggLocal 단순화로 통신/실행시간 증가 | 기존 강한 local-chain 정책과 다름을 명시하고 Docker에서 planning/runtime/movement를 분리 보고. optimality를 새 목표로 되살리지 않음. |
| 공통 생성 비용이 계속 지배함 | 의도적으로 수용한 범위. 선택 overhead 개선과 전체 시간 개선을 구별하고 end-to-end single-pass를 주장하지 않음. |
| 구조 변경 범위가 커짐 | builder/Oracle/authority 변경 금지, 기존 receipt·validator 재사용, 최소 selector/helper 변경과 테스트 우선. |

## 12. 근거 목록

### 공식 upstream 소스

- **U1** [FederatedPlannerFedAll.java, 49–163](https://github.com/apache/systemds/blob/b82911b0a032e59a01bcba3d669fd457e9e0042c/src/main/java/org/apache/sysds/hops/fedplanner/FederatedPlannerFedAll.java#L49-L163): baseline 설명, 제어 흐름 가정, memoized input-first 처리, FED/FOUT 설정.
- **U2** [FederatedPlannerFedHeuristic.java, 25–41](https://github.com/apache/systemds/blob/b82911b0a032e59a01bcba3d669fd457e9e0042c/src/main/java/org/apache/sysds/hops/fedplanner/FederatedPlannerFedHeuristic.java#L25-L41): aggregate-vector local-output override.
- **U3** [AFederatedPlanner.java, 65–179](https://github.com/apache/systemds/blob/b82911b0a032e59a01bcba3d669fd457e9e0042c/src/main/java/org/apache/sysds/hops/fedplanner/AFederatedPlanner.java#L65-L179): 실제 입력 FType 기반 지원/출력 판정, range 기반 source FType.

이 세 파일은 공식 raw endpoint에서 다시 가져와 `/home/mchoi/w1357-diagnostics/fedpolicy-plan-20260928-q1x3hukf/`에 SHA-256과 URL을 보존했다. 별도 researcher 생성은 thread limit으로 불가능하여 root가 원문을 확인했다.

### 현재 저장소 — 아래 src/scripts 경로는 저장소 루트 기준

- **L1** `src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/PolicyFirstFeasiblePlacementSelector.java:109–143,475–569`: global retry, state recursion/order. 실제 4,096-node 실패는 선행 품질 검토 참조.
- **L2** `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java:1142–1163,2279–2365`, `RelocationSelections.java:536–581`: 별도 row/relocation 탐색.
- **L3** `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/HeuristicPlacementAdapter.java:65–109,221–375`: 정책 projection·global relaxation.
- **L4** `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCandidateGenerator.java:104–204,518–537,615–638,653–655`: tuple enumeration, 정확한 emission, vector/FULL 및 transient 조건.
- **L5** `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:995–1007,1562–1595,1709–1830`: eager policy facts, 반복 경로 분석.
- **L6** `src/main/java/org/apache/sysds/parser/DMLTranslator.java:349–412,531–565`, `DMLProgram.java:64–77`; `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:161–185`; `src/main/java/org/apache/sysds/hops/ipa/IPAPassRewriteFederatedPlan.java:83–113`: authority 선생성·planner 연결.
- **L7** `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java:2642–2663,2683–2694,3632–3633`: owned domain/receipt 및 eager heuristic facts 계약.
- **L8** `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementPlanApplication.java:24–59`, `PlacementEmissionTransaction.java:128–175,303–393`: 전체 검증·atomic emission·동일 Hop 충돌 검증.
- **L9** `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:509–542,689–714,1878–1886`; `PlacementProgramFacts.java:74–133`: shared formal/TR-TW/reaching definition 및 현재 CFG refinement.
- **L10** `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java:1499–1529`: exact validation 및 반복 action scan.
- **L11** `scripts/fedplanner/run_LAN_docker.sh:1–10`: 현재 frozen analysis-only wrapper.

## 13. 계획 검토 상태

- **v2 적용 범위:** 사용자의 최신 지시로 full candidate 생성·공유를 고정했다. v1의 lazy generation, full/demand authority 분리, builder/closure 변경 단계와 그에 종속된 수용 기준은 모두 철회했다. 기존 정책 철학·정확한 증거·경계 안전성 요구는 유지한다.
- v1의 독립 architect WATCH와 reviewer READY_WITH_RISKS는 당시 계획의 역사적 검토다. 이를 v2의 새 승인으로 그대로 표시하지 않는다. 일반 greedy completeness 경고와 DEFERRED 사건 기반 재개 계약은 이번에도 보존했다.
- v1 원본은 `/home/mchoi/w1357-diagnostics/fedpolicy-shared-plan-20260928-hdbteu18/`에 보존했다. v2의 별도 문서 검증 및 재검토 결과는 같은 디렉터리에 기록한다.
- v2 독립 재검토: **READY_WITH_RISKS**. 최신 full-generation 공유 범위, authority 보존, 선택 단계 조합 탐색 금지, T_common 및 C/S/M 분리와 readiness 계약에 material 충돌이 없음을 확인했다. wake마다 전체 후보를 재스캔하는 위험과 일반 greedy completeness 한계는 P1/A9 및 기존 성공 corpus 전환 게이트로 관리한다.
- 문서 검증: v2 두 사본 일치, 내부 링크/fence/공백, 활성 단계 P0–P5와 lazy 생성 단계 제거, source/test/POM/conf/scripts의 전체 tracked-file SHA 무변이 및 git diff --check를 확인했다. 결과는 plan-v2-verification.json에 기록했다. 각 boundary 계약의 fact 높이·transfer rule·증거 합성은 P1의 구현 게이트이며 미입증이면 기본 전환을 보류한다.
- **이번 산출물은 실행 계획이다. 코드 수정, 새 JUnit, Docker 성능 검증 또는 구현 완료를 주장하지 않는다.**
