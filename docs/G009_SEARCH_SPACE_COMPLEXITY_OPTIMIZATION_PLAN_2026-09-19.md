# G009 search-space 시간복잡도·중복 계산·메모리 개선 계획

작성일: 2026-09-19. 상태: **P0–P4 구현 완료 / 유한 oracle·package PASS / 최종 GLM 검증 진행 중**.

> 범위 보충: 위 P4는 내부 support 객체/list 공유의 제한 구현이다. selector까지 유지하는
> 압축 관계는 구현되지 않았다. 10배 성능 목표의 후속 변경은
> [알고리즘 재설계 계획](G009_SEARCH_SPACE_ALGORITHMIC_REDESIGN_PLAN_2026-09-19.md)을 따른다.
> 앞선 대화의 4–5배 처리량 및 16–33배 메모리 감소는 검증된 실적이 아니며 철회한다.

## 1. 요구사항과 범위

- 기준: `/home/mchoi/systemds-g009-correctness`, `audit/g009-completeness`,
  `35d1f49507a9cf3566674e68c6b97ccc1354d079`.
- 목표: 합법 candidate/realization/support/proof/action/decoded plan을 보존하면서 반복 계산,
  중간 product materialization, 오래된 snapshot 보유량을 줄인다.
- 대상은 selector 이전의 shared `PlacementAnalysis` 구축이다. DP 선택 비용도 후속 측정하되,
  이번 GLM 지연을 DP solver 지연으로 분류하지 않는다.
- [기존 분석](G009_SEARCH_SPACE_CONSTRUCTION_ANALYSIS_2026-09-19.md)의 코드·thread dump를 출발점으로 한다.
- 성능 lane의 기존 계획
  `/home/mchoi/systemds-g009-performance/docs/G009_PLANNING_PERFORMANCE_PLAN_2026-09-19.md`를 대체하지 않는다.
  조회한 문서 SHA-256은 `ca8bf94f7ef451872b2653da084202bfefe2a2adc45c5ca47ac16f1f8ee967e5`다.
  아래 P0–P4는 그 실행 순서를 유지하면서 복잡도·cache 경계·메모리 합격 기준을 보충한다.
- 최초 작성 시점에는 문서만 작성했으나, 후속 요청에 따라 이 correctness worktree에서 P0–P4를
  구현했다. 다른 worktree의 변경은 reset하거나 덮어쓰지 않았다.

### 보존 계약

1. 기준과의 equality와 독립 oracle correctness는 별도다. 작은 지원 corpus에서
   `Decode(New) = Decode(Base) = LegalPhysicalPlans`를 검사한다. 기준에서 결함이 발견되면
   반례와 기준 수정부터 별도 처리하고 성능 개선에 섞지 않는다.
2. 후보 cap, sampling, 첫 proof만 보존, 비용 dominance에 따른 legal plan 삭제는 금지한다.
3. 동일 endpoint/geometry라도 occurrence, producer authority, seed provenance, action, layout
   exactness가 다르면 동일 객체로 단정하지 않는다. hash는 equality의 대체물이 아니다.
4. TRead/TWrite는 CP/LOUT 또는 FED/FOUT만, recompile CP/FOUT 금지, privacy와 runtime fallback
   금지 계약을 유지한다. PUBLIC-only skip은 pass에 포함하지 않는다.
5. 유한 corpus의 missing/extra=0은 전역 증명 완료가 아니다. G009 전역 의무는 계속 OPEN이다.

## 2. 코드 근거 지도

아래 `B`, `N`, `A`는 모두 `src/main/java/org/apache/sysds/hops/fedplanner/placement/` 아래 파일이다.
행 번호는 위 baseline 기준이며 구현 때 symbol과 source hash를 다시 확인한다.

| ID | 파일·행 | 직접 확인된 동작 |
|---|---|---|
| B1 | `NeutralPlacementGraphBuilder.java:5172-5184,7850-7866` | 입력 domain product를 리스트로 만든 뒤 rule 평가 |
| B2 | `NeutralPlacementGraphBuilder.java:2542-2552,2681-2712` | FType seed index는 이미 존재; 후보별 모든 호환 seed에 proof query |
| B3 | `NeutralPlacementGraphBuilder.java:677-715,2416-2533` | semantic → CFG → direct/physical closure 반복; continuity 객체 재생성 |
| B4 | `NeutralPlacementGraphBuilder.java:5601-5653,5667-5678` | input별 dedup 후 전체 relocation assignment 리스트 생성·소비 |
| N1 | `NativePlacementContinuity.java:172-219,435-445` | exact/dynamic witness별 새 graph; query 안에서 DFS visited 공유 |
| N2 | `NativePlacementContinuity.java:449-576` | 각 state에서 fact/emission/realization/clause 재탐색과 dependency 생성 |
| N3 | `NativePlacementContinuity.java:276-310` | reverse index/queue, 제거할 때 owner ArrayList에 removeIf |
| N4 | `NativePlacementContinuity.java:327-425` | Tarjan SCC, eligible graph 재분할과 grounding 반복 |
| N5 | `NativePlacementContinuity.java:228-273,313-324` | support product 생성, proof 리스트 보유 후 distinct/sort |
| A1 | `PlacementAnalysis.java:328-340,463-470,514-540` | signature 기반 비교와 realization별 OR clause merge |
| A2 | `PlacementAnalysis.java:703-742` | 최종 support-clause별 receipt/index와 canonical rank 생성 |

기존 N5 enumerator는 이미 callback을 사용한다. 여기의 문제는 callback 자체가 아니라 caller가 모든
proof를 리스트에 쌓는 점이다. 기존 B2의 seed index나 query 내부 visited를 새 개선으로 재제안하지 않는다.

## 3. 현재 시간복잡도

### 3.1 변수와 해석

전체 HOP 수 `n`만으로 `O(n)` 또는 `O(2^n)`을 붙이지 않는다. 다음 출력·반복 민감 변수로 분해한다.
아래 graph 비용의 hash lookup은 expected O(1) 가정이며, 긴 문자열/구조 비교 비용은 별도 집계한다.

| 기호 | 의미 |
|---|---|
| `k_h`, `d_hi` | HOP h의 입력 수와 입력 i의 실제 domain 크기 |
| `D_h = ∏ d_hi` | 초기 input tuple 수; 빈 입력 tuple은 1개, empty domain이 있으면 0개 |
| `C_h` | tuple당 oracle/profile/shape 처리 비용; 상수라고 가정하지 않음 |
| `Q` | 모든 실제 closure pass에서 수행한 proof query 수; exact/dynamic query를 별개로 셈 |
| `V_q`, `A_q`, `E_q` | query q의 proof state, alternative, alternative→dependency edge 수 |
| `B_q` | graph 생성 중 조사한 fact/emission/clause 및 rejected 대안의 총 work |
| `a_qv` | state v의 alternative 수 |
| `J_q`, `G_q` | SCC/grounding에서 실제 수행한 graph scan 횟수와 누적 state/alternative/edge 방문 work |
| `r_qai`, `P_qa = ∏ r_qai` | root alternative a의 입력별 support 선택지와 product leaf 수 |
| `P_q`, `U_q`, `k_q` | query의 raw proof leaf 총수, 고유 proof 수, 최대 binding 수 |
| `σ` | 문자열 생성·hash/equality·정렬 comparator의 실제 처리량; 고정 길이 가정 금지 |
| `F_live`, `L`, `M_out` | live facts 크기, 동시 보유 snapshot 수, 최종 결과 bytes |

### 3.2 단계별 비용

| 단계 | 현재 시간 | 현재 추가 공간 | 근거 |
|---|---|---|---|
| 입력 product | `O((k_h + C_h) D_h)` | tuple 리스트 `O(k_h D_h)` + 결과 facts | B1 |
| proof graph 생성 | `O(B_q + V_q + A_q + E_q)` + σ/정렬 | `O(V_q + A_q + E_q)` + identity bytes | N1/N2 |
| dead pruning | expected `O(V_q + A_q + E_q + Σ_v a_qv²)` 상한 | `O(V_q + A_q + E_q)` | N3 |
| Tarjan 한 번 | `O(V_q + A_q + E_q)` | 같은 graph + O(V_q) stack/index | N4 |
| 전체 SCC grounding | `O(G_q)` + σ; 단순 한 번의 Tarjan 비용이 아님 | graph 외 중첩 refinement 임시 map/list/stack | N4 |
| support product/proof 정규화 | `O(P_q k_q log(k_q+1) + U_q log(U_q+1))` + σ와 option 준비 | 현재 `O(P_q k_q)` + 고유 결과/문자열 | N5 |
| relocation product | assignment 복사만 `O(k P)`; leaf 비용 별도 | 전체 assignment 리스트 `O(k P)` + 결과 | B4 |
| OR merge/receipt publication | clause/receipt 수와 정렬·σ에 비례 | 최종 clause별 receipt/index/서명 | A1/A2 |

**주요 주의사항:**

- N3는 queue를 쓰지만 `removeIf`가 owner alternatives를 반복 scan한다. 한 state의 a개 대안을
  차례로 제거하면 `a+(a-1)+...+1 = O(a²)`가 된다. 현재 pruning 전체를 선형이라고 부르지 않는다.
- N4는 eligible graph를 다시 만들고 SCC를 재귀 refinement한다. `G_q`는 그 모든 방문의 합이다.
  각 scan이 원 graph보다 작다는 조건에서 `O(J_q(V_q+A_q+E_q))`로 느슨하게 표현할 수 있으나,
  J_q 자체를 상수로 가정하지 않는다. retained refinement stack도 별도 메모리 항목이다.
- N5의 option 준비에는 `canonicalReferences`의 TreeMap과 signature bucket 내 equality scan이 있다.
  같은 signature로 서로 다른 구조가 몰리는 경우 bucket 크기에 대해 이차 비교가 생길 수 있다(N5).
- signature/authority 길이, input 수, rule/profile 비용을 숨기고 전 구간 O(V+E)라고 주장하지 않는다.

### 3.3 전체 계산량 모델

```text
T_build = T_CFG/shape/function/privacy/relocation/publication
        + Σ (각 실제 node-build 호출 h) [(k_h + C_h) D_h]
        + Σ (각 실제 proof query q) [
             B_q + V_q + A_q + E_q + Σ_v a_qv²
             + G_q + option-preparation_q
             + P_q k_q log(k_q+1) + U_q log(U_q+1)
          ]
        + signature/ordering work
```

이는 코드에서 분해한 parameterized 비용 모델이지, 프로그램 크기 n에 대한 전역 종료/복잡도 정리가 아니다.
Q는 이미 전체 pass의 호출 수를 포함하므로 여기에 R을 다시 곱하지 않는다. 직관적 축약은 다음과 같다.

```text
실제 closure 방문 횟수 × 재평가 candidate 수 × 호환 seed 수 × witness 종류 × query 비용
```

외부 closure는 사실의 추가뿐 아니라 삭제/교체도 수행한다(B3). pass limit이 있다는 사실만으로
공통 monotone lattice 높이에 비례한 수렴이나 polynomial time을 주장할 수 없다.

### 3.4 공간복잡도와 제거할 수 없는 하한

```text
peak live memory ≈ final output
                 + 동시에 살아 있는 old/new fact snapshots
                 + active proof graphs/reverse indices/SCC refinement temporaries
                 + eager tuple/assignment/proof products
                 + cached identity/signature/index
```

snapshot 공유가 없을 때 보유 상한 모델은 `O(L F_live)`이며 실제 sharing 정도는 heap dominator로 확인한다.
GC live heap, allocated bytes, RSS, container peak는 서로 다른 지표다.

U개 고유 결과를 명시 출력하면 최소 Ω(U)의 시간이 필요하며, 전체 결과를 메모리에 보유하는 현재 API에서는
그 결과 크기만큼의 공간도 필요하다. 외부 sink에 streaming할 때 이 공간 하한을 작업 heap에 적용하지 않는다.
binding까지 출력하는 시간·출력 저장량 하한은 **직렬화된 결과의 총 bytes**에 비례한다. 고유 legal support가 k개 입력에서
각 r개 독립 선택을 가지면 `r^k`가 될 수 있다. streaming은 임시 메모리를 줄일 뿐 이 하한을 없애지 않는다.

## 4. 실행 계획: P0 → P1 → P2 → P3 → P4

### P0 — 기준 동결, 계측, 회귀 계약

**변경 전에:** 작은 보호 privacy fixture를 완주하는 old baseline과 generation-independent oracle을 확보한다.
GLM baseline 완료를 기다리느라 전체 개선을 막지는 않지만, GLM equality를 미완료 상태로 명시한다.

1. B3의 semantic/CFG/direct pass별 revision과 진입/종료 counters를 추가한다. counter는 bounded
   aggregate로 유지하고 per-query 전체 문자열을 timed 실행에서 저장하지 않는다.
2. N1/N2에 `queries`, `unique exact-context queries`, `states/alternatives/edges built`, B_q를 기록한다.
   같은 root라도 다른 revision·pin·witness이면 중복으로 세지 않는다.
3. N3/N4에 `remove-list elements scanned`, SCC invocation/scan work, refinement depth를 기록한다.
4. B4/N5에 prefix/leaf/unique proof/duplicate proof, peak pending assignments를 기록한다.
5. cold/warm JVM 조건, JDK/heap/CPU/GC, 전체 source·class/JAR hash와 dirty diff, fixture/privacy
   metadata를 동결한다. source/target가 바뀐 실행은 비교에서 제외한다.
6. allocation/GC는 별도 profiler 실행으로 수집한다. live histogram은 참조 owner를 증명하지 않으므로
   memory 원인 확정에는 필요시 heap dominator 분석을 추가한다. 계측 없는 timing 실행과 구분한다.

**P0 산출물:** `build/g009-complexity/<run-id>/{manifest.json,phase-metrics.json,oracle-diff.json,memory.json}`.
이 경로는 예정 산출물이며 이번 문서 작성으로 실험 자료가 생성됐다는 뜻이 아니다.

**통과:** old/new 계측 on/off decoded 결과 동일, missing=0/extra=0, 모든 counter의 scope/revision 정의 명시.
종료되지 않은 실행은 측정 하한만 기록하고 median/speedup 분모에서 성공 실행처럼 사용하지 않는다.

### P1 — 중간 product 제거; 그 다음에 증명된 조기 거절

**첫 구현 단위는 기존 성능 계획과 같이 B4 relocation assignments streaming만** 수행한다.
leaf 순서/predicate/실패 동작과 최종 canonical 결과를 유지한다. 별도 새 추상 framework 없이 기존
enumerator를 leaf consumer로 변경한다. mutable traversal buffer가 결과에 alias되지 않도록 소비 시 복사한다.

| 작업 | 기대 변화 | 합격 조건 |
|---|---|---|
| B4 assignments streaming | 임시 `O(kP)` → O(k), 최종 출력 공간 불변 | leaf 수·방문 순서 동일; pending completed assignment ≤1; buffer 보유 ≤k |
| 후속 B1 input streaming | tuple 임시 `O(k_h D_h)` → O(k_h) | oracle 호출 tuple 순서/count·facts 동일 |
| 후속 N5 sink/accumulator | raw proof 보유 `O(P_q k_q)` → `O(U_q k_q)` + traversal | legal proof set 동일; duplicate raw proof 미보유; 최종 U는 모두 유지 |

N5의 canonical accumulator에는 seed/witness/authority를 포함한 완전한 구조 equality를 사용한다.
proof 하나의 모든 OR 대안을 입력별 union으로 평탄화하지 않는다.

**조기 거절은 pure streaming과 별도 커밋:** 동일 occurrence의 양립 불가능한 source 선택 등
`LegalCompletions(prefix)=∅`를 독립 oracle로 증명한 경우에만 prefix를 생략한다.
staging/unknown metadata/현재 미grounded 상태는 영구 불법이 아니다. 기준 raw rejected 진단은 유지하며
변화는 표현 공유/증명된 불법 조합/실제 missing·extra로 분류한다. 다른 continuity caller까지
caller-specific executable predicate를 무조건 push down하지 않는다(B2/N5).

### P2 — 동일 의미의 생성·정규화 중복 제거

P0가 반복 생성을 확인한 지점만 순차 변경한다. 무조건 큰 memo를 추가하지 않는다.

1. **동일 product descriptor를 한 번 확장:** ordered input option relation과 correlated OR branch,
   root/seed/witness/pins/action identity가 모두 같은 경우만 공유한다. 동일 pool만 같으면 공유 불가.
   입력별 dedup은 B4에 이미 있으므로 root/clause 간 중복률을 먼저 측정한다.
2. **analysis-scoped structural sharing:** immutable binding/clause/identity를 전체 구조 equality로
   intern하고 기존 object를 재사용한다. hash 충돌 시 equality로 검증한다. static/global interner는 금지한다.
   interner metadata가 객체 절약량보다 크면 채택하지 않는다. revision 종료 시 root를 해제한다.
   기존 IdentityHashMap/`==` 기반 owner membership도 계약이다. 구조 equality만으로 다른 owner의
   객체를 대체하지 않고, receipt/analysis ownership positive·negative 회귀를 추가한다(A2).
3. **문자열은 필요한 경계에서 생성:** 가능하면 구조 key/analysis-local ID로 hot-path lookup을 수행한다.
   기존 canonical lexical ordering을 바꾸는 단순 ID sort는 금지한다. 공개 signature/fingerprint/tie-break는
   기존 순서를 유지하는 comparator 또는 경계 rank로 검증한다(A1/A2).
4. **N3의 반복 removeIf 제거:** alternative별 alive flag와 owner별 live count로 queue를 처리하고
   마지막에 한 번 compact한다. boolean 조건·대안 identity·안정 순서를 유지한다.
   expected work 목표는 `O(V_q+A_q+E_q)`이며 객체 hash/비교 비용은 별도다.

**통과:** 고유 결과 고정·중복 경로 1/2/4/8 증가 fixture에서 고유 result 생성량은 고정된다.
입력 확인 자체는 중복 수에 비례해 남을 수 있다. dead-alternative fan-out fixture에서 list 재scan이
사라지고 각 alternative 제거는 최대 한 번이다. allocation bytes와 live heap을 함께 보고한다.

### P3 — graph 재사용과 변경 부분만 재계산

#### P3-A: 안전한 query 재사용 경계

처음에는 **동일 immutable snapshot의 완전히 같은 query만** 재사용한다.

```text
Context = analysis identity + immutable semantic revision
Query = root occurrence + rule/ordered inputs + pinned realization
      + external seed identity + witness(FType,endpoints,ranges,exactness)
      + template mode + complete fixed/pinned environment
```

revision은 nodes/origins/facts/compiled edges/CFG reaching definitions/incomplete-source/privacy 관련
관측 상태 변경을 빠짐없이 덮어야 한다. root pin은 전체 하위 query에 영향을 준다(N1/N2).
`(node,pool)`만 같은 graph를 다른 root에 그대로 재사용하면 안 된다. full-context hit율이 낮다면
이 memo는 채택하지 않고 **변하지 않는 facts/edge index** 공유만 먼저 적용한다.

- graph expansion을 완료한 뒤에만 completed result를 publish한다. IN_PROGRESS나 부분 SCC 결과를
  성공/실패 cache로 노출하지 않는다. failure/empty 결과도 현재 revision에만 유효하다.
- graph/state intern은 동일 context에서 수행하며 full graph·reverse index·proof list를 모든 query에
  중복 저장하지 않는다. root-independent core와 pin overlay 분리는 영향이 분리됨을 증명한 후의 선택지다.
- memo에 entry/추정-byte 예산을 둔다. 예산 초과 시 cache entry만 퇴거하거나 저장을 생략하고
  동일 알고리즘으로 다시 계산한다. 이는 후보 cap이나 runtime fallback이 아니다.
- 예산 아래 resident된 동일 key는 build 1회, 퇴거 후 재계산은 허용·계수한다. 유한 memory와 임의
  query 순서에서 모든 중복 계산을 없애겠다고 약속하지 않는다.

#### P3-B: 영향 cone worklist

기존 physical worklist와 N3 reverse index를 재사용 가능한 범위에서 확장한다.
우선 closure 한 곳에만 적용하며 full-recompute 구현과 shadow comparison한다.

- source domain/support/edge/constraint/action → dependent query/state의 reverse dependency를 만든다.
- **성공한 dependency뿐 아니라 부재·실패·탈락의 근거**도 등록한다. 현재 candidate가 없던 parent의
  domain에 새 row가 추가될 때 negative cache/query가 깨어나야 한다.
- add/remove/replace, privacy 변경, reaching-definition 추가/삭제, layout exactness 변경을 모두 invalidate한다.
  변경 영향이 불확실하면 dirty 범위를 전체 snapshot으로 넓혀 재계산한다. legality는 바꾸지 않는다.
- 삭제는 SCC split, 추가는 기존 SCC 간 merge를 만들 수 있다. 이전 SCC 하나만 재계산하면 부족하다.
  영향을 받는 연결 영역의 SCC를 다시 계산하고, 경계 증명이 부족하면 기존 전체 SCC 재계산을 유지한다.
- composed transfer의 동일 경계에서 안정성을 비교한다(B3). 임의의 monotone add-only worklist로
  바꾸지 않는다. 순서 독립성 미증명 동안 기존 phase/schedule을 유지한다.

**통과:** add/remove/replace/late-ground/cycle-ground-loss/negative-to-positive에서 full recompute와
동일한 canonical 결과·오류. 사전 index 이후 독립 component 하나만 변경할 때 무관한 component의
proof build/SCC scan 0. 작은/중간 corpus에서 default·작은·0 cache budget 결과 동일.

### P4 — 고유 출력 자체가 클 때만 factorized relation

P1–P3 이후 `U`, `M_out` 자체가 병목이면 별도 설계로 진행한다. 이는 단순 cache 패치가 아니다.

- 기존 realization → OR clauses → AND bindings 구조(A1)를 확장해 같은 subproof를 공유한다.
  cyclic graph는 SCC 내부 관계로 유지하고 SCC 간 graph만 DAG로 다룬다.
- correlated alternatives를 보존한다. `(A1 AND B1) OR (A2 AND B2)`를
  `(A1 OR A2) AND (B1 OR B2)`로 바꾸면 불법 조합이 생기므로 금지한다.
- decoder는 occurrence/authority/action/edge position/proof multiplicity의 기존 의미를 복원한다.
  작은 universe에서 baseline과 독립 oracle 양쪽으로 전수 비교한다.
- A2의 eager receipt publication, `CandidateSelections.java`,
  `fedCostBased/fedExact/ExactPhysicalModel.java`와 DP 소비 경계를 함께 검토한다.
  builder만 factorize하고 곧바로 모든 proof를 flatten하면 최종 peak 감소로 인정하지 않는다.
- selector 진입·선택·검증·serialization까지 전체 pipeline memory를 측정한다. explicit export는
  필요할 때 streaming하지만 총 U 출력 시간 하한은 그대로다.

**통과:** decoded 의미 equality 및 DP 비용/canonical 선택 동일, builder 이후까지 peak live memory 감소,
정해진 동일 heap에서 완료. 일반 입력에 polynomial memory/time을 보장하지 않는다.

## 5. 개선 후 기대 복잡도: 조건부 목표

| 변경 | 제거되는 비용 | 남는 비용·제약 |
|---|---|---|
| streaming | tuple/assignment 전체 중간 리스트 | 모든 leaf 계산과 최종 고유 결과 |
| early structural dedup | `P`회 중복 결과 객체 구성/보유 | 입력 조사; 실제 U개의 생성·canonicalization |
| alive flag/count pruning | `Σ a_v²` repeated list scans | expected `V+A+E` + hash/equality |
| 같은 query memo | resident key의 반복 `T_q` | 최초 query, lookup, result iteration/output; evicted key 재계산 |
| context-safe shared graph | 중복된 state expansion | root pins로 다른 문제는 별개; 합집합 graph 자체가 클 수 있음 |
| dirty worklist | 무관한 영역의 전체 재build | dependency index 관리, dirty cone/SCC 재계산; 최악에는 전체 graph |
| shared support relation | 반복 subproof의 저장 | 고유 relation 크기; 명시 decode/export 시 Ω(output bytes) |

동일 snapshot에서 Q번 중 UQ개의 exact-context query만 있다면 bounded resident memo의 이상적
graph work는 `Σ_(unique q) T_q + O(Q × lookup)`다. **출력 소비와 복사는 이 식 밖에서 여전히 발생**한다.
무효화·퇴거가 있으면 그 재계산 비용을 더한다. 개선의 목표는 낭비 제거이지 전역 worst-case 지수성 제거가 아니다.

## 6. 검증 매트릭스와 acceptance criteria

### 6.1 구현 전에 고정할 회귀

아래 기존 테스트는 `src/test/java/org/apache/sysds/hops/fedplanner/placement/`에서 출발한다.
새 stress fixture는 이들에 추가하거나 별도 작은 테스트로 만들며 현재 존재한다고 주장하지 않는다.

| 검증 축 | 기존 출발점 | 추가할 반례·정량 assertion |
|---|---|---|
| missing/extra 양방향 | GlobalReceiptPlanSpaceCompletenessTest, ProductionDecodedPlanSpaceCompletenessTest | baseline/new raw·decoded 전체 비교와 삭제/추가 mutation sentinel |
| exact candidate/support | CandidateReceiptAssignmentCompletenessTest, CandidateRealizationCanonicalizationTest | 동일 pool의 다른 producer/seed, 동일 reference의 여러 clause |
| native/SCC | NativePlacementContinuityTest, NativeLineagePlanSpaceCompletenessTest | root pin 차이, crossed AND/OR cycle, 외부 ground 추가/삭제 |
| dynamic layout | DynamicNativeLayoutCompositionTest | exact ↔ dynamic, 같은 endpoint의 다른 ranges |
| closure | NeutralPlacementFixedPointCompositionTest | old/new revision·negative cache·SCC split/merge·schedule 비교 |
| relocation | RelocationActionPlanSpaceCompletenessTest | direct-only FED/LOUT, action identity, buffer alias negative |
| production inventory | CandidateAffectingBranchInventoryTest | 관련 production 변경 시 inventory 재생성·현재 소스 대응 |

privacy/TR-TW/recompile와 ExactPhysicalModelCertificate 회귀는 별도 함께 실행한다. compiler fixture가
PRIVATE/PRIVATE_AGGREGATE의 비어 있지 않은 legal case인지 확인한다. PUBLIC-only는 제외 수를 기록한다.

### 6.2 규모 축과 work budget

- product: input 수 2/4/8과 각 option 수 2/4/8을 독립 증가시키되 작은 전수 가능 셀부터 실행한다.
- duplicate factor: 고유 결과 고정, 동일 derivation 1/2/4/8회; 고유 객체 생성량 고정 여부 확인.
- query reuse: 같은 exact key 반복과 한 필드씩 다른 key를 섞어 hit와 잘못된 hit를 함께 검증.
- dead pruning: 한 owner의 alternative 수를 늘려 scan work가 이차에서 선형으로 바뀌는지 확인.
- dirty cone: 독립 component 수를 늘리고 변경 영역 고정; initial index 비용과 update 비용을 분리.
- truly unique product: 모든 support가 합법·서로 다른 경우도 포함; 임의 cap/잘못된 병합 탐지.

### 6.3 측정·채택 gate

1. 변경은 한 단위씩 적용하고 reader와 다른 reviewer가 equality/cache/schedule 경계를 검토한다.
2. 단위 gate: missing=0, extra=0, canonical ordering·진단 계약 동일. count나 optimum 하나만 비교하지 않는다.
3. targeted Maven → 관련 broad suite → compile/package 및 설정된 static checks → diff check 순서로 검증한다.
   Maven/target 공유 동시 실행은 금지한다. 미실행 check는 명시한다.
4. 완료하는 baseline/new fixture를 동일 조건 fresh JVM 각 최소 3회 교차 실행하고 raw 시간·median·범위를
   전부 보존한다. buildAnalysis/selection/serialization/GC를 분리한다. 데이터 행 수만 줄인 것을
   HOP graph 복잡도 감소로 해석하지 않는다.
5. memory 단계는 **해당 중간 컨테이너 감소 + 총 allocation/peak live heap/RSS 비악화**를 검사한다.
   cache는 절약 work와 추가 retained bytes를 함께 보고, 속도가 개선돼도 총 peak가 악화되면 이 계획의
   memory 합격으로 인정하지 않는다. 노이즈가 큰 차이는 PASS 대신 INCONCLUSIVE다.
6. 실제 workload/공식 성능 비교는 기존 lane이 동결할 Docker 명세와 `run_LAN_docker.sh`만 사용한다.
   image/JAR/dataset/privacy/worker/CPU/heap 전체 identity를 고정한다. host JVM 결과는 correctness/진단용이다.
   DP → FedAll → Heuristic → Exact 순서이며 ML/P1/P2/SliceLine qualification은 별도 gate다.
7. GLM은 timeout을 늘렸다는 이유로 pass 처리하지 않는다. 정상 완료와 equality 근거가 필요하다.
   baseline가 미완료라면 speedup 배수와 full-GLM baseline equality는 OPEN으로 둔다. 기존 성능 계획의
   180초 목표는 실험 목표일 뿐 correctness timeout/skip 기준이 아니다.
8. missing/extra, stale identity, 순서 변화, 미인증 negative cache 또는 memory budget 위반 시 해당 변경만
   보류·수정한다. 다른 세션의 변경을 reset하지 않으며 실패 산출물을 보존한다.

## 7. 담당·산출물·남은 위험

- correctness lane: 독립 oracle, cache-key/invalidations 반례, 전체 set 비교 및 이 복잡도 계약.
- performance lane: production P0–P4와 측정. 기존 계획·production 파일을 이 세션이 동시 수정하지 않는다.
- 통합 단위: baseline SHA, 변경 SHA, source/class hash, exact command, full diff/oracle 산출물,
  phase/work/memory 계측, reviewer 판정을 묶어 전달한다. 새 기준 통합은 직렬 수행한다.
- 첫 실행 단위: **P0의 작은 기준·counter·회귀 확보 → B4 pure streaming 하나 → 검증**.
  memo/interning/worklist/factorization을 동시에 누적하지 않는다.

### 증상·대응·미해결·회귀 위험

| 항목 | 기록 |
|---|---|
| 문제 | GLM shared graph 생성이 약 113분 뒤 사용자 중단; proof-build CPU hot path |
| 대응 | 출력 민감 복잡도 분해, streaming/dedup/safe reuse/dirty closure/factorized relation 순차 계획 |
| 아직 미해결 | 실제 bottleneck 비율, exact-context hit율, fixed-point 일반 종료/보존, GLM 완료/equality |
| 회귀 위험 | pin/context 누락 cache, failed-query 영구화, OR 상관관계 소실, transient stale reference, cache 메모리 증가 |
| 감지 | key 한 필드 변형·revision 변화·ground 삭제·SCC split/merge oracle와 전체 pipeline heap 비교 |
| 의사결정 | runtime/오라클 규칙 완화가 아니라 동일 합법 의미의 계산·표현 효율화만 허용 |

아래 8절은 후속 구현·검증 결과다. 유한 corpus 결과와 전역 증명 상태를 계속 분리한다.

### 계획 검토·문서 검증 결과

- 작성과 분리된 native `critic`의 read-only 검토: **APPROVE**, 필수 수정 사항 없음.
  검토 범위는 실제 계획의 복잡도 식, resident cache 계약, negative dependency, SCC split/merge,
  OR 상관관계 및 기존 성능 계획의 순서다. 구현 correctness 승인이나 전역 증명을 뜻하지 않는다.
- source 참조 11개 항목의 경로/행 범위와 문서에 명명한 기존 테스트 클래스 존재 확인.
- 새 문서 whitespace/final newline 및 `git diff --check` 통과.
- Java/워크로드 실행 없음. 중단 실행 `INCOMPLETE_NOT_PASS` 유지.

## 8. 구현·실험 결과

### 8.1 완료한 구현

| 단계 | 구현 | 의미 보존 경계 |
|---|---|---|
| P0 | analysis-scoped `SearchSpaceMetrics`; fixed-point/direct closure, query/graph/pruning/SCC/product/template/memo/incremental/factorization counter | production 기본 경로는 collector `null`; exact-context 진단 보유는 기본 4,096개로 제한하고 초과를 별도 계수 |
| P1 | relocation assignment와 input tuple callback streaming, proof `LinkedHashSet` accumulator | 기존 leaf 순서, zero/empty domain, `null` local input, 예외 전달, immutable leaf 보존 |
| P2 | 동일 support product descriptor 1회 확장, signature bucket set, direct-template index, dead-pruning live-count+1회 compaction, bounded signature cache | query 내부 root/seed/witness 고정; canonical lexical ordering과 완전 구조 equality 유지 |
| P3-A | resolver-snapshot 한정 completed-result LRU | root occurrence identity+전체 source+seed key; entry/proof/추정-byte 3중 예산; eviction/0 budget은 재계산 |
| P3-B | direct closure의 conservative dirty connected component | compiled input, CFG reaching definition, support source, same-value alias를 무방향 확장; 불확실 영역은 넓혀 계산하며 full-recompute shadow 유지 |
| P4 | analysis-scoped support substructure factorization | clause owner 객체는 매번 새로 생성; proof owner, producer occurrence, relocation action identity가 모두 맞는 내부 객체/list만 공유 |

P4는 새 factorized API로 selector를 다시 쓰는 방식이 아니라, 현재 eager receipt/Exact 계약을 유지하면서
관측된 반복 proof/binding/list만 공유하는 제한 구현이다. OR clause를 평탄화하거나 clause 자체를 intern하지 않았다.

### 8.2 짧은 fixture 계측

최종 ACTIONS 증거: `build/g009-complexity/final-short/phase-metrics.json`.

- analysis fingerprint: `48f343237ba65b7fbe074cf7b9592378c0477025cd80d6033a0a5faea3a1d0e7`
- proof query/state/alternative/edge: `52 / 128 / 144 / 92`
- dead-pruning final compaction scan: `144`; built alternative `144`와 동일
- support descriptor: `50`회 확장, 동일 descriptor `16`회 재사용
- relocation leaves `164`, peak pending completed assignment `1`
- input leaves `33`, traversal peak depth `8`
- dirty pass: recomputed facts `24`, reused facts `36`
- memo resident: proof `2`, 추정 `5,566 bytes`
- factorized clauses `75`; proof object/list reuse `28/59`, binding object/list reuse `33/36`

이 counter는 ACTIONS의 실제 work를 나타내며 일반 입력의 상한이나 GLM speedup 배수는 아니다.

### 8.3 유한 correctness·build 결과

최종 증거: `build/g009-complexity/final-bounded-green-20260919T071941+0200/`.

- 26 suites, **161 tests**, failures `0`, errors `0`, skipped `8`.
- `GlobalReceiptPlanSpaceCompletenessTest`, `ProductionDecodedPlanSpaceCompletenessTest`,
  `IndependentPlanSpaceGenerationCompletenessTest`, `CandidateReceiptAssignmentCompletenessTest`,
  `NativeLineagePlanSpaceCompletenessTest`의 양방향 set equality 통과.
- 따라서 **선언한 유한 protected corpus에 한해 missing plan `0`, illegal extra plan `0`**.
- `ExactPhysicalModelCertificateTest`, privacy, dynamic layout, relocation, streaming buffer,
  full-recompute shadow, cache eviction/0/oversize budget 회귀 통과.
- `mvn -q -DskipTests package` exit `0`; `git diff --check` 통과.
- source/resource before-after hash 동일. 최대 RSS는 전체 Maven process tree 계측 기준
  `1,510,248 KiB`; baseline의 `1,568,204 KiB`보다 낮지만 서로 다른 suite 수와 시스템 부하가 있어
  memory 개선 배수로 사용하지 않는다.

production branch inventory는 `5,473 → 5,637` rows로 재생성했다. ID 기준 added `196`, removed `32`이며
분류는 (1) continuity memo/product dedup/linear pruning, (2) builder streaming/index/dirty cone/factorization,
(3) bounded signature cache다. 갱신 후 inventory 테스트가 통과했다.

### 8.4 독립 구현 검토

별도 read-only architecture 검토는 네 correctness 경계에서 재현 가능한 blocker를 찾지 못했다.
판정은 `WATCH`이며, exact memo의 resolver scope와 key, completed-only publication, query-local descriptor
dedup, conservative dirty cone, owner-preserving factorization을 확인했다. 리뷰에서 지적한 unbounded
`observedQueries`와 proof-count-only memo budget은 각각 bounded tracking+overflow counter와
추정-byte budget으로 후속 수정했다.

### 8.5 아직 분리하는 주장

- 유한 tests의 missing/extra `0`은 전역 correctness proof가 아니다. 전역 증명 의무는 **OPEN**이다.
- 최초 GLM baseline은 113분에 사용자 중단됐으므로 성공 baseline 시간이 없다. 최종 GLM이 완료돼도
  그 실행 하나만으로 엄밀한 median speedup을 주장하지 않는다.
- Docker workload qualification은 unit/GLM 검증과 별도이며 `run_LAN_docker.sh`만 허용한다.

## 9. 알고리즘 재설계 후속 상태

이 절은 8절의 국소 P0–P4 snapshot을 후속
[G009 알고리즘 재설계](G009_SEARCH_SPACE_ALGORITHMIC_REDESIGN_PLAN_2026-09-19.md)가 대체한 결과다.
특히 현재 final factorization은 replacement가 없으면 원 clause/realization/fact를 재사용하므로,
8.1의 “clause owner 객체는 매번 새로 생성” 설명은 당시 구현에만 해당한다.

- 구조 handle/segmented lexical ordering, shared topology/query overlay, precomputed dependency handle,
  footprint-validated support solution revision reuse, lazy receipt/rank, final relation sharing을 구현했다.
- 채택 GLM artifact는
  `build/g009-redesign/final-glm-revision-support-20260919T145212+0200/`이며 fingerprint
  `98b41db8ffe6e38794fe7eb7788b49e9cf0dd66a6e2c75629ab6fedcdb387109`를 유지한다.
- 첫 정상 완료 대비 wall `16:36.51 → 14:07.82`(1.175x), RSS
  `32,677,300 → 30,550,684 KB`다. proof query work는 10.14x 줄었으나 전체 wall 10배 목표는 미달이다.
- broad selected suite는 173 tests, failure/error `0/0`, skip `4`; manifest는
  HEAD `5,473`에서 current `5,805`, added `618`/removed `286`을 전부 별도 TSV에 분류했다.
- 이 수치는 host evaluator 진단이다. 동일 Docker 3회 성능, ML/P1/P2/SliceLine qualification,
  전역 correctness/termination 증명은 여전히 OPEN이다.
