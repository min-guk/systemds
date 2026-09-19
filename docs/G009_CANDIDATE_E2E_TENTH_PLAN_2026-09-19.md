# G009 후보 형성 E2E 시간을 현재의 1/10로 줄이기 위한 수학적 분석과 실행 계획

작성일: 2026-09-19. 기준 소스: `a266622b37c63d07b5e9a13451d4c1c4ee47b125` (`origin/main`).
상태: **계획만 작성. 새 production 구현·성능 실험 없음. 10배 가능성은 아직 입증하지 않음.**
이 문서는 기존 R0–R3를 처음부터 반복하는 계획이 아니라, 통합 이후 남은 비용을 대상으로 한다.

## 1. 결론과 요구사항

우선순위는 **측정 경계 수정 → 반복 relation 재구성과 정규화 제거 → context를 보존한 공유 proof 평가 →
composed closure의 정확한 delta 전파 → 필요시 support relation과 DP 소비 방식 동시 변경**이다.
캐시를 더 붙이거나 SCC를 더 최적화하는 것만으로 10배를 기대할 근거는 없다(E2–E5).

목표는 동일 입력·정책에서 모든 합법 후보와 authority를 보존하면서, 현재 main 대비 후보 형성과
실제 DP 소비를 포함하는 **고정 production E2E wall**을 0.1배 이하로 만드는 것이다.
후보 비용만 임의로 분류한 합계를 공식 성공 지표로 사용하지 않는다. CPU/heap/worker를 늘려 얻은 개선은 별도 결과다.
candidate cap, sampling, 첫 proof만 유지, 성능 목적의 후보 가드, privacy 완화, runtime fallback은 금지한다.
TRead/TWrite와 recompile 계약은 그대로 유지한다([AGENTS.md](../AGENTS.md)).

**10배를 보장할 수는 없다.** 변경하지 못하는 시간이 전체의 10% 이상이면 그 밖을 아무리 빠르게 해도
유한한 개선으로 목표에 도달하지 못한다. 모든 후보를 명시적으로 출력해야 한다면 출력 크기의 하한도 있다.
따라서 첫 단계의 산출물은 profiling 결과뿐 아니라 '어느 부분을 함께 바꿔야 10배가 가능한가'라는
수치 기반 판단표다. 구현 완료, 유한 correctness, 10배 성능, 전역 증명은 각각 따로 판정한다.

## 2. 근거 지도: 사실·추론·미확정의 구분

`P = src/main/java/org/apache/sysds/hops/fedplanner/placement/`,
`T = src/test/java/org/apache/sysds/hops/fedplanner/placement/`.
행 번호는 위 기준 소스 기준이다. 아래 현황은 코드 또는 기존 artifact에서 확인한 **사실**이다.

| ID | 근거 | 확인한 사실 |
|---|---|---|
| E1 | `T/G009PlanningPerformanceEvaluatorTest.java:31–40,97`; `T/SearchSpaceMetricsEvaluatorTest.java:20–28,49–58` | 전자는 build만 측정하고 receipt snapshot은 이후 생성. 후자는 compile부터 analysis 완료까지 계측하여 서로 시간 구간이 다름. |
| E2 | `P/NativePlacementContinuity.java:498–535,949–1031` | topology 공유가 이미 있고, support miss마다 root를 pin한 query graph를 만든 뒤 DAG/SCC 분기. |
| E3 | `P/NativePlacementContinuity.java:541–587,728–743` | 동일 product descriptor를 중복 제거하지만 고유 descriptor의 모든 immediate-support leaf는 여전히 전개. |
| E4 | `P/NeutralPlacementGraphBuilder.java:624,708,766,2458–2576` | function/semantic/publication/CFG/physical closure가 중첩되어 exact relation을 반복 재구성. |
| E5 | `P/NeutralPlacementGraphBuilder.java:2875–2927,3860` | dirty cone은 무방향 연결 성분으로 보수적 확장. physical downstream worklist는 이미 존재. |
| E6 | `P/NeutralPlacementGraphBuilder.java:2930`; `P/PlacementAnalysis.java:85,1153,1241` | 현재 factorization은 명시적 clause 생성 이후 내부 객체 공유. 구조 정렬·lazy receipt·lazy rank는 이미 구현. |
| E7 | `P/PlacementAnalysis.java:1378,1402`; `P/CandidateSelections.java:181` | requireAll은 모든 clause의 receipt 생성. 첫 다중 canonicalization이 전체 rank를 계산. 해당 consumer index의 직접 caller는 PolicyFirstFeasible이며 DP의 model 경로와 구분해야 함. |
| E8 | `P/NativePlacementContinuity.java:309–385,400–413` | public memo miss가 proof instantiation/sort와 size estimation을 수행. size estimation은 signature 길이를 사용하고 budget 검사보다 먼저 실행. |
| E9 | `P/PlacementIdentity.java:64–99`; `P/NativePlacementContinuity.java:1151` | bounded arena, overflow시 resolver-local exact handle 경로가 이미 존재. overflow는 오류나 후보 삭제가 아님. |
| E10 | `P/SearchSpaceMetrics.java:9–114,152–163` | 현재 metrics는 횟수 중심이며 단계별 exclusive wall time을 제공하지 않음. |
| E11 | `P/NativePlacementContinuity.java:746–807`; `T/NativePlacementContinuityTest.java:612–649` | cyclic grounding은 eligible SCC refinement와 외부 ground 조건을 사용. 단순 Boolean least fixed point와 동일하다고 가정하면 안 됨. |
| E12 | `P/PlacementAnalysis.java:1355,2187`; `P/NeutralPlacementGraphBuilder.java:911–959` | owner-bound receipt·boundary validation·최종 identity/authority 보존이 공개 계약의 일부. |
| E13 | [병합 평가](G009_MERGE_REVIEW_2026-09-19.md), [unified 보고서](G009_UNIFIED_WORKSPACE_REPORT_2026-09-19.md) §7–9 | 164 active pass/4 skip, 세 snapshot 일치. DAG 이후 여러 캐시·allocation 절감 실험의 시간 개선 불충분. |
| E14 | `src/main/java/org/apache/sysds/parser/DMLTranslator.java:366–462`; `DMLProgram.java:59–65` | common preparation→analysis→planner→final boundary verify→receipt handoff. 기존 planner timer는 analysis 이후 시작. |
| E15 | `src/main/java/org/apache/sysds/hops/ipa/FederatedPlannerFactory.java:45`; `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java:43–72` | DP는 COMPILE_COST_BASED→FederatedPlanLocalCost. ExactPhysicalModel→cost surface→LocalPhysicalOptimizer→ExactPhysicalSelection→application 순서. |
| E16 | `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:149,247`; `ExactPhysicalSelection.java:160–169`(같은 디렉터리) | DP model이 모든 support clause를 alternative로 전개. 선택된 receipt를 materialize하고 resolveAndValidate 수행. |

원본 GLM artifact **A1**:
`/home/mchoi/systemds-g009-unified/build/g009-unified/acyclic-fastpath-glm-r1-20260919/metrics.json`.
동시점 비교 **A2**:
`/grid/3/cofee-lm-sweep-mchoi-20260914/g009-unified-acyclic-fastpath-glm-pair-r1-20260919/summary.txt`.
LM observer **A3**:
`/home/mchoi/systemds-g009-unified/build/g009-unified/proof-graph-reuse-observer-lm/summary.txt`.
원본 artifact는 Git에 포함되지 않으므로 새 benchmark manifest에는 접근 경로와 SHA-256을 함께 기록한다.

### 현재 GLM에서 관측한 작업량(A1)

| 지표 | 값 | 해석의 한계 |
|---|---:|---|
| evaluator elapsed | 716.591801515초 | host 진단이며 strict 후보 E2E baseline이 아님(E1). |
| direct closure passes / stable passes | 273 / 77 | pass마다 작업량이 달라 시간 비율로 해석할 수 없음. |
| proof graph / state | 154,362 / 5,264,559 | support memo miss 후의 누적 생성량. |
| alternative / dependency edge | 284,280,699 / 513,715,666 | 최종 출력 크기나 전역 고유 개수가 아님. |
| SCC edge scan | 0 | 이 GLM에서는 SCC 자체를 더 줄일 여지가 없음. |
| support leaf / final factorized clause | 4,114,384 / 99,632 | scope가 달라 비율을 제거 가능한 중복률로 간주하면 안 됨. |
| canonical sort / comparison | 5,556,665 / 30,025,517 | 이미 구조 key를 쓰지만 반복 횟수가 남음. |
| structural arena overflow events | 19,391,911 | 누적 사건 수. 고유 객체 수·miss율·소요 시간은 아님. |
| public memo hit / miss / eviction | 2,763 / 793,977 / 735,691 | hit 비율 약 0.347%. public 경계 유지비 점검 가치가 있음. |
| support memo hit / miss | 1,410,457 / 154,362 | hit 약 90.14%. public memo와 다른 캐시이므로 함께 없애면 안 됨. |
| created receipt / rank chars (build 단계) | 0 / 0 | downstream 소비 비용이 0이라는 뜻이 아님(E7). |

**추론:** 여러 revision/context에서 동일 의미 relation을 재구성하고 정렬하는 비용과 명시적 product
전개를 함께 줄이는 것이 유력하다. **미확정:** 각 단계의 실제 wall share, GLM의 안전한 context 공유율,
최종 DP 소비 비용, 압축 가능한 비율은 현재 자료만으로 알 수 없다.

A3의 LM whole-graph 반복은 91.85%이나 그 graph가 차지한 반복 edge는 72.56%다.
순수 횟수 비율을 전체 시간 절감률로 바꾸거나 GLM에 그대로 적용하지 않는다.
overlay hit 85.28%에도 부분 paired 시간이 악화된 실험(E13)은 이 구분이 필요한 직접 사례다.

## 3. E2E 계약부터 수정한다

같은 프로그램에 대해 다음 시각과 범위를 명시하고 baseline/new에 동일하게 적용한다.

- `t0`: `DMLTranslator.java:366`의 commonPreparationStarted 지점. physical normalization·memory refresh·metadata 생성 **이전**(E14).
- `ta`: `DMLProgram.bindPlacementAnalysisAtFinalHopBoundary` 호출 직전.
- `t1`: metadata와 `buildDetachedAnalysis`를 포함한 위 호출 반환. `Tanalysis=t1-ta`.
- `t2`: `FederatedPlanLocalCost`의 model/cost surface 준비 완료, optimize 호출 직전(E15).
- `t3`: `ExactPhysicalSelection.create` 완료. 선택된 receipt와 validation 비용 포함(E16).
- `t4`: `rewriteProgram` 반환 후 final boundary verification·등록·receiptConsumer handoff까지 완료한 지점(E14). runtime 학습 실행 전.
- `TformDiagnostic`: `Tanalysis + Tmodel + TcostSurface + TlateCandidate`의 겹치지 않는 합.
  마지막 항에는 optimization/selection/application 안에서 실행되는 후보 생성·해석·receipt/rank·validation을 포함한다.
- **`TcandE2E=t4-t0`**: common preparation·analysis·DP model/optimize·selection/application·최종 검증까지 포함한 공식 wall metric.
- `Tjob`: 별도로 실제 Docker GLM 작업의 처음부터 끝까지. 전체 학습 10배를 후보 형성 10배와 혼동하지 않는다.

`t1`과 DP model setup 사이의 registry/setup 시간은 따로 기록하며 모두 후보 생성 시간으로 오인하지 않는다.
unit evaluator의 `buildAnalysis` 시간은 별도 Tbuild로 유지한다. production의 경로와 구간이 다르다.
`CandidateSelections.PartialReachabilityIndex`는 PolicyFirstFeasible 경로이므로 이를 DP 준비 시간의
대리 지표로 사용하지 않는다(E7,E15,E16).

주 목표는 `TcandE2E_new/TcandE2E_base <= 0.10`이다. TformDiagnostic은 병목을 설명하는 용도일 뿐이다.
새 representation의 계산이 optimizer나 application 안으로 이동해도 공식 wall 안에 남는다.
이 구간은 실험 전 고정하며 결과를 보고 좁히거나 attribution 신뢰도에 따라 나중에 목표를 바꾸지 않는다.
fresh JVM, unbound analysis, 동일 pre-t0 입력 상태를 확인하고 후보 계산·캐시 warming을 t0 이전에
옮기는 변경은 acceptance 위반으로 처리한다. 목적상 좁은 build-only 결과도 별도로 보고할 수 있으나 E2E PASS가 아니다.
snapshot 직렬화는 진단 작업이므로 TcandE2E에 넣지 않되 `Texport`로 따로 공개하고 byte equality를 검사한다.
compile은 별도 `Tcompile`로 공개한다. correctness 실행과 성능 실행을 구분한다.

716.5918초의 1/10은 **71.6592초**이나 이것은 기존 진단의 산술 참고값이다.
`TcandE2E_base`는 아직 측정되지 않았으므로 71.66초를 공식 E2E 합격선으로 고정하지 않는다.
180초는 이 참고값 대비 약 4배 개선이며 새 10배 목표의 성공 기준이 아니다.

## 4. 수학적 평가

### 4.1 Amdahl 제약과 시간 예산

현재 TcandE2E를 겹치지 않는 단계들로 분할한다. 바꾸지 않는 비중 `f`, 개선 대상 비중 `p_i`,
각 대상의 실제 speedup `s_i`, 새 관리 비용 `h=Tnew_overhead/Tbase`라 하면

```text
f + Σ p_i = 1
Tnew / Tbase = f + Σ(p_i / s_i) + h ≤ 0.10.
```

GC pause와 allocation CPU를 겹쳐 더하지 않는다. exclusive phase wall은 합이 실제 wall과 맞아야 하고,
GC·CPU sampling·allocated bytes는 그 시간을 설명하는 별도 차원으로 기록한다.

나머지를 하나의 비율로 개선한다고 할 때 `s >= (1-f)/(0.10-f-h)`이며 분모가 양수여야 한다.

| 변경하지 않는 비중 f | 새 overhead h | 나머지에 필요한 speedup |
|---:|---:|---:|
| 15% | 0% | 불가능: 무한히 빨라도 6.67배 한계 |
| 10% | 0% | 유한한 speedup으로 달성 불가 |
| 5% | 0% | 19배 이상 |
| 2% | 0% | 12.25배 이상 |
| 3% | 1% | 약 16.17배 이상 |

예를 들어 비용의 60%만 개선하면 상한은 2.5배다. 단일 hot method 10배 최적화와 전체 10배는 다르다.
단계별 speedup을 `2×3×2`처럼 곱하지 않고 **현재 잔여 시간 분할에 대해 식을 다시 계산**한다.
P0 이후 표의 각 항목에 실제 초·측정 오차·목표 초를 채운다. 근거 없는 '20초/30초' 배분은 하지 않는다.

### 4.2 현재 계산량과 output-sensitive 하한

revision `r`, support miss query `q`, 해당 graph의 state/alternative/dependency를 `S_rq,A_rq,E_rq`,
고유 product descriptor `d`의 input option 수를 `n_di`라 하자.

```text
Wgraph = Σ_r Σ_q (S_rq + A_rq + E_rq)           # DAG인 경우에도 매 query 생성·순회
L      = Σ_d ∏_i n_di                          # 현재 전개하는 leaf 총수
Wmerge = Σ_merge touched_clauses
Wsort  = Σ_sort actual_comparisons              # key comparison 자체 비용도 별도
T      ≈ α Wgraph + β L + γ Wmerge + δ Wsort + Tother.
```

마지막 식은 profiling으로 계수를 추정할 **모형**이지 기존 count에서 시간을 복원하는 등식이 아니다.
cache lookup, provenance 재부착, allocator, arena overflow, validation을 Tother에 감추지 않고 P0에서 분리한다.
GLM의 SCC=0이므로 SCC-only 개선은 이 baseline의 Wgraph를 없애지 못한다(A1,E2).

서로 독립적인 k개 입력에 각각 d개 선택이 있으면 명시적 결과는 `d^k`개다.
모든 결과를 출력해야 하는 계약은 적어도 `Ω(Lout)` 시간, byte 출력을 요구하면 `Ω(Bout)` 작업을 가진다.
대상 하드웨어의 가능한 throughput 상한을 `bmax`라 할 때 `Tout >= Bout/bmax`가 시간 하한이다.
측정한 평균 throughput은 하드웨어 상한의 증명이 아니므로 이를 이용한 '하한'은 추정치로 표시한다.

따라서 **flat 결과를 꼭 전부 소비해야 하고 그 자체가 시간 예산을 넘는다면**, 상수 최적화만으로
10배는 불가능하다. 압축 표현과 consumer를 함께 바꾸거나 해당 제약에서 목표 미달을 보고해야 한다(E3,E7).
반대로 같은 결과 크기도 baseline의 불필요한 중간 계산이 대부분이었다면 10배가 가능할 수 있다.

### 4.3 공유 solver의 가능성과 한계

현재 Q개 query에 graph work가 반복된다. full context를 보존한 공유 representation의 고유 work를 U,
query별 실제 차이 work를 D, lookup/validation/index 유지 work를 H라 두면

```text
Wold = Σ_q |Gq|
Wnew = U + D + H
해당 단계의 이상적 work speedup = Wold / (U + D + H).
```

`Gq`에는 root pin, full physical witness, template mode, occurrence/owner, revision과 policy 의존성이
반영돼야 한다. 같아 보이는 worker pool만으로 query를 합치지 않는다(E2,E12).
Q는 최대 context 수만큼 남을 수 있고 U가 Wold만큼 크면 개선은 없다. graph fingerprint를 만들기 위해
먼저 graph 전체를 재생성하면 그 생성비는 남으므로 whole-graph cache hit만으로 이 목표를 충족하지 못한다.

기본 설계는 **공유 immutable topology/alternative 구조 + query context 값 + 결과 relation**을 나누고,
context가 실제 읽는 필드와 지원 predicate가 같은 부분만 공유하는 것이다.
key를 간략화하는 행위마다 observational equivalence 또는 read-set independence를 입증한다.
hash는 index일 뿐 equality proof가 아니다. 최초에는 안전한 full context 분리 상태에서 계산 중복을 계측한다.

### 4.4 delta 처리: 전체 재구성 대신 변경분만 처리

고정된 finite domain 위의 단조 함수 F에 한해 `X_(r+1)=X_r∪F(X_r)`,
`Δ_(r+1)=F(X_r)\X_r`와 같은 semi-naive 계산을 사용할 수 있다.
예를 들어 additions-only relation A,B에 대해 정확히

```text
Δ(A×B) = (ΔA × B_old) ∪ (A_old × ΔB) ∪ (ΔA × ΔB)
       = (ΔA × B_new) ∪ (A_old × ΔB).
```

tuple/provenance projection 후의 중복·순서는 기존 계약대로 처리한다. 식이 있다고 index 탐색비가 0은 아니다.
이 접근은 **해당 연산자와 epoch가 단조임을 입증한 구간에만** 적용한다.
현재 pipeline은 privacy 제거, physical 재구축, staging 제거, executable projection을 포함하므로
전체가 additions-only라는 가정은 거짓일 수 있다(E4,E5).

typed reverse dependencies와 epoch 경계를 두고 positive additions만 delta 전파한다.
deletion/authority replacement 및 negative dependency 변화는 보수적으로 영향 범위를 찾은 뒤
그 부분을 정확히 재계산한다. 반복 횟수 cap으로 종료시키지 않는다.
work는 `O(U + Σ affected_r + Lnew)`를 **색인 접근비·출력 생성비·충돌 검사비를 포함한 조건부 형태**로
모형화한다. affected_r가 매번 전체 domain일 수 있으므로 보편적인 선형·상수 개선 주장은 하지 않는다.

특히 현재 cyclic semantics는 단순 least Boolean fixed point가 아니다.
`loop ← loop AND ground`가 외부 ground를 가진 경우 받아들이는 회귀가 있다(E11).
`μX.(X∧true)=false`인 일반 Boolean solver로 바꾸면 합법 후보를 잃는다.
초기 shared/delta solver는 DAG만 대상으로 하고 cyclic 변경 component는 기존 eligible-SCC 알고리즘을
정확히 실행한다. 이것은 runtime fallback이 아니라 planner 내부에서 적용 조건을 나누는 계산 방식이다.

보존해야 할 cyclic 의미를 명시하면, 이미 grounded인 집합 G와 component C에 대해
alternative a는 `(directGround(a) OR deps(a)≠∅)`이고 모든 dependency가 `C∪G` 안에 있을 때 eligible이다.
eligible edge로 SCC를 재분할한다. 재분할되지 않는 component에서는 **모든 state에 eligible 대안이 있고**,
**component 전체에 direct ground 또는 이미 grounded인 외부 dependency가 최소 하나 있을 때만** C를 G에 추가한다.
재분할되면 각 subcomponent에서 같은 조건을 재평가하고 안정할 때까지 진행한다(E11).
ground 제거·SCC split/merge·negative dependency 변경 시 이전 grounded 표지를 그대로 유지하지 않고
영향 영역의 상태를 재검증한다. additions-only reference count로 자기 지지를 남기면 안 된다.

### 4.5 factorized support와 DP의 결합

완전히 독립적인 option domain의 product는 `Θ(Σ n_i)` 구조로 `∏ n_i`개 조합을 나타낼 수 있다.
그러나 상관관계가 있으면 독립 product로 바꾸면 안 된다. 예를 들어

```text
허용: (a1,b1) OR (a2,b2)
금지 변환: (a1 OR a2) AND (b1 OR b2)   # (a1,b2),(a2,b1)을 새로 만듦
```

`Choice`, ordered `Conjunction`, input-position binding, authority/provenance annotation을 갖는 DAG로
동일 relation을 나타내는 방향을 검토한다. 현재 factorization(E6)과 달리 **leaf 생성 이전**에 공유한다.
모든 relation이 작게 압축되지는 않는다. worst case에는 representation도 원래 출력만큼 커질 수 있다.

`Cost(Choice)=min`, `Cost(Conjunction)=sum`은 비용이 분리되고 shared decision/비용이 없을 때만 맞다.
공유 producer의 선택 일치성, relocation 비용 중복, worker/layout, privacy, action을 경계 상태에 포함해야 한다.
domain 최대 d, 경계 변수 폭 w라면 DP table에 `O(d^w)` 항이 생길 수 있다. w가 크면 압축 relation만으로
선택도 선형 시간이 되지 않는다. 정확성·비용·tie-break 보존 증명 없이 scalar min-plus로 대체하지 않는다.
현재 DP의 실제 eager 경계는 ExactPhysicalModel의 support-clause 전개다(E16).
LocalPhysicalOptimizer를 전역 최적 exact solver로 가정하지 않는다. 기존 DP와의 결정·비용 일치를 우선
보존하고, exhaustive Exact는 작은 입력의 feasible domain·objective 검증을 위한 독립 oracle로 사용한다.
candidate formation 10배를 위해 selector API가 바뀌는 경우 DP만 먼저 구현하고,
다른 플래너에는 동일 집합을 제공하는 exact adapter를 둔다. 다른 플래너 성능까지 개선됐다고 주장하지 않는다.

### 4.6 캐시의 손익분기점

질의 수 N, hit율 h, 원래 재계산 비용 C, 매 lookup 비용 L, miss 후 저장·크기 산정 비용 I를
동일 비용이라는 단순 모형으로 놓으면 `Tcache=N[L+(1-h)(C+I)]`, `Tplain=NC`다.
따라서 캐시가 유리하려면 `hC > L+(1-h)I`여야 한다. 유지한 객체의 GC·메모리 영향은 추가 비용이다.
실제 query 비용이 다르면 hit율 대신 **hit에서 절약한 시간의 합**과 **모든 lookup/저장/추가 GC 시간의 합**을 비교한다.
싼 query만 hit하고 비싼 query는 miss할 수 있기 때문이다.

public memo hit 0.347% 또는 overlay hit 85.28%라는 숫자만으로 득실을 결정할 수 없다(A1,E13).
P1은 이 부등식의 각 항을 측정하여 해당 경계를 없애거나 계산을 구조적으로 공유할지 결정한다.
재사용률을 높이기 위해 authority·root pin·witness key를 제거하는 것은 선택지가 아니다.

## 5. 단계별 실행 계획과 중단·진행 기준

각 단계는 작은 반례/전수 oracle → 기존 회귀 → Docker short workload → 필요할 때 GLM 순서로 검증한다.
같은 `target/`에서 병렬 Maven 실행을 하지 않는다. 새 라이브러리 의존성은 추가하지 않는다.

### P0. 동일 E2E baseline과 비용 분해 — 필수 선행 단계

수정 예정: `T/G009PlanningPerformanceEvaluatorTest.java`, `T/SearchSpaceMetricsEvaluatorTest.java`,
`P/SearchSpaceMetrics.java`, builder의 phase 경계 및 실제 DP 호출 경계.

1. 기준 commit/JAR/class/data/config/Docker image/harness script의 SHA-256과 전체 JVM fork args를 고정한다.
   harness는 기존 `run_LAN_docker.sh` 하나를 선택해 절대 경로·해시·인자를 기록한다.
2. §3의 t0–t4와 `Tcompile/Texport/Tjob`을 측정한다. 단계별 exclusive wall 합의 오차는
   TcandE2E의 2% 이내를 목표로 하고, nested inclusive 시간을 중복 합산하지 않는다.
3. proof topology/overlay/grounding, public proof materialization, clause merge/canonicalization,
   closure replay, publication validation, receipt/rank/consumer 준비의 시간·CPU·allocation을 수집한다.
   profiler 없는 성능 run과 profiler 있는 진단 run을 분리한다.
4. GLM의 context 구분별 고유 key와 weight를 bounded observer로 조사한다. hash collision을 검증하고
   observer overflow를 보고한다. observer 때문에 graph를 한 번 더 전부 순회하는 비용도 분리한다.
5. cold-cache fresh JVM baseline을 최소 3회 확보하고 baseline 계측 on/off 비교로 overhead를 평가한다.
   작은 synthetic scale fixtures로
   Q/revision 수, 변경 비율, independent/correlated product 폭을 각각 변화시킨다.

**Gate:** 동일 fingerprint뿐 아니라 비어 있지 않은 oracle 결과·동일 입력 계약을 확인한다.
단계별 실측 `f,p_i,h`와 필요한 `s_i` 표, 소비 경계의 Lout/Bout, 메모리 예산을 작성한다.
타이밍 overhead가 반복 비교에서 3%를 넘으면 진단 계측과 acceptance 타이밍을 분리한다.
어떤 작은 개선의 최대 절감도 5% 미만이면 주 경로를 지연시키며 확대하지 않는다.
이 5%는 효율적인 실험 순서를 위한 운영 기준이지 수학 정리가 아니다.

### P1. 반복 provenance·정규화·캐시 유지비 제거 — profiling 조건부

수정 예정: `P/NativePlacementContinuity.java:309–413`, `P/PlacementAnalysis.java:85`,
`P/PlacementIdentity.java:64–99`, builder의 exact relation 병합 경계.

- public memo의 0.347% hit에 대해 lookup/size estimation/eviction/instantiate/sort 시간을 따로 확인한다.
  cache-disabled 경로에서도 먼저 signature를 산출하는 불필요한 일을 피하는 변경부터 검토한다(E8).
  support memo는 hit 90.14%인 별도 계층이므로 구분한다. 예산을 무조건 키우지 않는다.
- seed 중립 support와 provenance wrapper를 분리해 동일 support를 seed마다 깊게 복제/정렬하지 않게 한다.
  외부 seed·owner는 결과에서 정확히 보존한다. 현재 instantiateSupportTemplates를 대체할 때 source rebinding 검사 유지.
- revision별 같은 canonical relation을 병합할 때 immutable structural identity로 변경 유무를 판단하고,
  신규 clause만 정확히 merge한다. equality 검사와 cache lookup의 비용도 포함한다.
- arena overflow를 늘어난 메모리로만 덮지 않는다. 어떤 값이 반복 overflow하는지 먼저 측정하고,
  변경하지 않은 구조의 handle lifetime/representation을 고친다. budget=0도 동일 결과를 내야 한다.

**Gate:** source pin/provenance/owner/order 차등 테스트, zero-budget/eviction/collision 테스트 통과.
baseline 대비 exclusive 해당 시간·allocation 감소, TcandE2E 비악화. cache hit율만으로 채택하지 않는다.
P1만으로 10배를 기대하지 않으며 P0의 Amdahl 표를 갱신한다.

### P2. immutable support 구조 위에서 context별 평가를 공유 — 첫 구조 변경

수정 예정: `P/NativePlacementContinuity.java:498–611,868–1120`, `P/SearchSpaceMetrics.java`.

- 이미 존재하는 CandidateTopology를 기반으로 stable alternative/dependency ID를 만들고,
  query마다 동일 wrapper graph를 생성하는 경로를 실제로 없앤다. ID는 owner/analysis/epoch 범위가 명시돼야 한다.
- full root-pin 환경·witness·template mode를 포함한 context를 먼저 분리한다.
  context 중립 subproblem만 공유하고 해당 조건의 read-set independence를 테스트와 별도 논증으로 기록한다.
- 공유 graph 위의 DAG grounding/viability와 지원 relation 생성 결과를 재사용한다.
  방문한 negative/dead dependency footprint를 유지한다. hash-equivalent graph를 먼저 만들고 캐시하는 방식은 피한다.
- memory 예산 도달 시 후보를 줄이지 않고 exact recomputation 또는 exact chunking을 사용한다.
  서로 다른 context를 Boolean grounded 하나로 합치지 않는다. cyclic 경로는 E11 계약을 유지한다.

**Gate:** 작은 full-context universe에서 old/new relation equality; root pin·same pool/different seed·
template fallback·dead sibling·diamond·ground-loss/split/merge poison matrix 통과.
고정 topology에서 Q를 늘릴 때 shared work U와 query-dependent D를 각각 측정한다.
`U+D+H`가 Wold보다 작아지지 않으면 구조를 확대하지 않고 NO-GO로 기록한다.

### P3. composed fixed point의 정확한 delta 전파 — P2와 겹치는 이득을 중복 계산하지 않음

수정 예정: `P/NeutralPlacementGraphBuilder.java:624–959,2458–2576,2875–2927`,
`P/NativePlacementContinuity.java:nextRevision` 및 dependency 기록부.

- semantic/CFG/physical/privacy/publication 연산자별 read/write domain을 정의한다.
  coarse FType 변화와 exact relation/authority 변화를 구별하는 typed dependency index를 구축한다.
- 이미 있는 physical worklist(E5)를 재활용하고 남은 전체 replay만 delta 방식으로 바꾼다.
  canonical relation identity가 그대로인 연산자는 기존 결과를 재사용한다.
- 단조 epoch 안에서는 §4.4의 delta product/merge를 사용한다.
  privacy 제거·layout authority replacement·staging 제거가 발생하면 epoch를 바꾸고 영향 domain을 재검증한다.
- 기존 composed transfer의 최종 fixed point를 old/new로 비교한다.
  direct loop 중간값의 안정성을 전체 안정성으로 착각하지 않도록 publication barrier를 유지한다.

**Gate:** finite domain additions-only에서는 새로운 fact가 한 번만 delta로 진입함을 counter로 확인.
deletion/negative-change에서는 old full recompute와 매 checkpoint 결과를 비교한다.
변경 비율 0%, 1%, 10%, 100% fixture에서 시간·재계산량·메모리를 측정한다.
최악에 전체 재계산이 필요하다는 사실을 숨기지 않는다. unbounded domain의 전역 종료 증명은 별도 OPEN이다.

### P4. factorized support를 실제 DP 소비까지 유지 — 출력·소비 비용이 예산을 막으면 필수

수정 예정: `P/PlacementAnalysis.java`의 support/receipt domain,
`P/NativePlacementContinuity.java:583`, `P/NeutralPlacementGraphBuilder.java:2930`,
`src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java`,
같은 디렉터리의 `ExactPhysicalCostModel.java`, `FederatedPlanLocalCost.java`, `ExactPhysicalSelection.java`,
`P/CandidateSelections.java`의 validation 및 다른 planner adapter, `P/PlacementIdentity.java`의 owner binding.

- 모든 candidate의 의미는 보존하면서 Choice/Conjunction의 정확한 관계를 생성한다.
  exact full export adapter로 기존 byte ordering과 multiplicity를 검증한다.
- candidate relation은 끝까지 압축 상태로 유지하고, DP가 실제 선택한 결과만 owner-bound receipt로 materialize한다.
  legacy consumer가 곧바로 requireAll을 호출한다면 그 경로의 비용도 TcandE2E에 포함한다.
- 공유 선택·비용·action은 separator state에 넣고 작은 exhaustive Exact로 domain과 objective를 검증한다.
  old/new DP는 같은 결정·비용·tie-break를 내야 한다. 기존 DP가 Exact 전역 최적과 항상 같다고 요구하지 않는다.
  독립 곱과 correlated relation의 역례를 모두 만든다.
- factorization이 도움이 없는 adversarial case도 동일 결과를 생성해야 한다.
  적응적 representation 선택은 의미나 후보 집합을 바꾸지 않는 경우만 허용한다.

**Gate:** decode(new)=decode(old) 양방향 equality, missing/extra mutation sentinel,
owner/foreign receipt 거부, old/new DP 선택 witness parity, old/new Exact feasible domain·최적값 parity.
`Tbuild+deferred candidate work`와 TcandE2E를 함께 개선해야 하며 exporter로 밀어낸 비용을 공개한다.
Lout 전수 출력을 생산 계약으로 요구하는 경우에는 flat E2E 10배와 factorized E2E 10배를 별도 표시한다.

### P5. 최종 통합·성능 qualification

- 선택한 단계들을 차례로 통합한다. 각 단계가 같은 비용을 줄이면 이전 baseline 배수를 곱하지 않는다.
- correctness gate: 기존 26개 bounded 클래스 + 이번 단계별 새 회귀, branch manifest/추가·삭제 분류,
  compile/package, 설정된 정적 검사, `git diff --check`. PUBLIC skip은 별도 집계한다.
- 공식 성능은 동일 `run_LAN_docker.sh`, CPU quota/affinity, heap/GC/JDK/image, worker/data/privacy/config에서만 비교한다.
  실제 fork args를 확인한다. 각 revision은 독립 build/target/JAR를 사용하고 profiler-off fresh JVM으로 실행한다.
- 초기 pilot 최소 3쌍은 correctness·분산·drift 진단용이며 acceptance 표본에서 제외한다.
  최종 후보를 동결한 뒤 표본 수 n과 AB/BA 균형·블록 무작위 순서를 사전 등록한다. 실행 중 유리한
  결과를 보고 조기 종료하거나 n을 바꾸지 않는다. 기본 설계는 아래의 **18쌍**이며 pilot에서 drift가 크면
  먼저 환경을 안정화하고, 필요한 표본 수 변경은 최종 run 시작 전에만 한다.
  기존 12분 baseline이면 이 최종 gate 자체가 약 4시간 이상 걸릴 수 있으므로 매 패치마다 반복하지 않는다.
- 사전 정의한 모든 pair를 보고한다. OOM/timeout/중단은 제외해 좋은 pair만 남기지 않고 실패/미완료로 기록한다.
  입증된 외부 오염만 사전 규칙으로 해당 pair 전체를 무효화하고 이유와 원자료를 보존한다.

**10배 성능 PASS:** 모든 유효 run 정상 완료, correctness PASS.
primary statistic은 contemporaneous pair의 `r_i=TcandE2E_new_i/TcandE2E_base_i`이며 population median의
one-sided 95% 이상 upper confidence bound가 0.10 이하여야 한다. ratio of medians는 설명용으로만 보고한다.

분포 형태를 가정하지 않는 order-statistic gate를 사용한다. n개 ratio를 정렬해 `r_(k)`라 하면
`Σ_(j=k..n) C(n,j)/2^n <= 0.05`가 되는 가장 작은 k를 정하고 **`r_(k)<=0.10`**을 요구한다.
median confidence limit의 binomial/order-statistic 근거는 [NIST Median Confidence Limits](https://itl.nist.gov/div898/software/dataplot/refman1/auxillar/mediancl.htm)를 따른다.
독립·동일 실험 조건의 pair라는 가정이 필요하며, autocorrelation/drift가 확인되면 이 CI를 유효하다고 주장하지 않는다.
예를 들어 n=12이면 k=10, coverage 약 98.07%이나 true success probability 0.8에서 검정력은 약 55.83%다.
사전 설계 대립가설 `Pr(r<=0.10)=0.8`과 목표 검정력 80%를 쓰면 n=18,k=13은 coverage 약 95.19%,
검정력 약 86.71%다. 이 p=0.8은 성능 예측이 아닌 표본 설계 가정이며 pilot 3쌍으로 이를 입증하지 않는다.
점추정만 10배인 경우는 INCONCLUSIVE로 남긴다. 작은 표본 bootstrap 수치로 이를 덮지 않는다.

사전 지정한 마지막 3개 pair의 ratio도 운영 관측치로 따로 보고하되 **추가 PASS 조건으로 사용하지 않는다**.
각 new run은 자기 contemporaneous control과 비교하며 과거 baseline median과 비교하지 않는다.
`p*=0.8`에서 '마지막 3개 전부 성공'을 필수화하면 그 조건만의 통과 확률이 `0.8^3=51.2%`라서
목표 검정력 80%와 양립하지 않는다. 전체 유효 표본의 primary order-statistic gate로 성능을 판정한다.
이 판정은 typical/median E2E 10배의 근거이며 **모든 run 또는 worst-case 10배 보장**은 아니다.

**부가 gate:** 실제 GLM 결과/수치 허용오차 기준 동일, runtime fallback 없음.
같은 heap에서 OOM 없음, container peak RSS median은 baseline의 1.05배 이내(운영 노이즈 허용치),
allocation/live heap은 각각 보고한다. 한 workload의 성공을 LM/P1/P2/SliceLine 전체로 일반화하지 않는다.

## 6. 정확성 논증과 독립 검토 산출물

| 변경 | 필요한 논증 | 반례/자동 검증 |
|---|---|---|
| 구조/ID 공유 | 공유 전후 owner·occurrence·root pin·witness 관측이 동일 | collision, foreign owner, same pool/different geometry/seed |
| DAG 평가 | dependency-first 순서에 대한 귀납으로 viable/grounded 및 지원 대안 보존 | chain, diamond, dead sibling와 descendants |
| cyclic 처리 | 기존 eligible-SCC와 동일 relation, 외부 ground 제거 시 자기 지지 금지 | E11 두 반례, SCC split/merge/ground loss |
| delta closure | epoch 내 단조성·sound reverse deps·완전한 change detection | full-recompute shadow, negative dependency, privacy removal |
| factorization | decoder의 양방향 포함과 authority·multiplicity·순서 보존 | crossed OR/AND mutation, exact/dynamic witness |
| DP 소비 | 경계 상태가 shared decisions/비용에 충분하며 기존 DP tie-break 동일 | old/new DP parity, exhaustive bounded Exact objective 비교, relocation 공유 비용 |

유한 도메인에서 단조 addition 수에 의한 종료는 증명할 수 있으나, 모든 프로그램의 authority/geometry
생성 도메인이 유한하다는 일반 정리는 별개다. naive cycle proof expansion으로 무한 provenance 경로를 만들지 않는다.
semantic reference는 기존 accepted 결과이며, 누락/불법을 잡는 independent oracle도 함께 사용한다.
작성자와 별도 reviewer가 각 구조 변경의 invariants와 성능 경계 이동 여부를 검토한다.

## 7. 채택하지 않을 접근과 전환 기준

- **또 다른 overlay cache부터 추가:** 이전 실패와 동일 유지비를 반복할 위험(E13). P0에서 실제 비용·공유율이 달라졌다는 증거가 있을 때만 재검토.
- **SCC 최적화 중심:** 해당 GLM의 SCC count가 이미 0(A1). cyclic workload의 별도 개선 과제로만 취급.
- **receipt lazy만 추가:** 이미 구현(E6); consumer가 모두 소비하는 경계(E7)를 바꾸지 않으면 비용 이동에 그침.
- **무조건 thread 수 증가:** 현재 동기화·owner·memory bandwidth 병목 여부가 미확정. 알고리즘 개선 후 profile이 CPU-bound이며 독립성이 증명될 때 별도 lane으로 검토. CPU 증가로 10배를 대체하지 않음.
- **full global delta-SCC부터 구현:** GLM 목표와 직접 맞지 않고 semantics 위험이 큼(E11). DAG 공유 + affected cyclic exact recomputation 우선.

P0에서 변경 불가능한 비용 `f+h >= 0.10`이면 그 경계를 바꿀 수 있는 P4까지 범위를 검토한다.
P4에도 출력 하한/consumer 계약 때문에 예산을 넘으면 **현 제약의 10배 목표 미달**을 수치로 보고한다.
후보를 삭제하거나 타이밍 경계를 좁혀 PASS로 만들지 않는다. 현재 합법 후보를 보존하는 가장 빠른 검증본은 유지한다.

## 8. 외부 원리와 적용 한계

다음은 알고리즘 선택의 근거이며 SystemDS에 대한 속도 보장이나 새 의존성 도입 권고가 아니다.

- [McSherry et al., Differential Dataflow, CIDR 2013](https://www.microsoft.com/en-us/research/wp-content/uploads/2013/01/differentialdataflow.pdf): 입력 revision과 loop iteration을 구분해 nested iteration의 변경분을 재사용한다. 이 프로젝트에서는 epoch와 semantic dependency 정의가 먼저이며, 논문의 처리량 수치를 가져오지 않는다.
- [Olteanu & Závodný, Size Bounds for Factorised Representations of Query Results, TODS 2015](https://www.cs.ox.ac.uk/dan.olteanu/papers/oz-tods15.pdf): relation 구조에 따라 flat tuple보다 작은 표현을 만들 수 있지만 전수 열거는 출력 크기의 비용을 지불한다. 프로젝트의 ordered authority/receipt와 비용 결합은 별도 보존 조건이다.
- [Green, Karvounarakis & Tannen, Provenance Semirings, 저자 발표자료](https://www.cis.upenn.edu/~plclub/propr/greg-slides.pdf): 대안과 결합의 derivation을 표현하는 관점을 참고한다. positive relational algebra의 법칙을 삭제·ordered list·owner identity에 무조건 적용하지 않는다.
- [NIST Median Confidence Limits](https://itl.nist.gov/div898/software/dataplot/refman1/auxillar/mediancl.htm): median의 order-statistic confidence bound를 binomial 확률로 정한다. 본 계획의 one-sided k와 검정력 수치는 이 식으로 직접 계산했으며 독립 pair 가정이 충족돼야 한다.

## 9. 이번 요청의 완료 범위

현재 코드·원본 metrics·실패 실험을 대조하고 수학적 가능 조건 및 단계별 gate를 작성했다.
독립 검토를 반영해 attribution 기반 성공 지표를 고정 production wall로 바꿨고,
cyclic 의미 계약을 명시했으며, paired order-statistic gate와 검정력을 모순 없이 정리했다.
실측 phase 비중과 Docker E2E baseline은 **미확정**으로 남긴다. production 코드 수정, 새 benchmark,
Docker 실행, commit/push는 이 계획 작성에서 수행하지 않는다. 다음 구현의 첫 작업은 P0다.
