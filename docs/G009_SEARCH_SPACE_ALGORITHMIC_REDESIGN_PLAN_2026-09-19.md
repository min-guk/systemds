# G009 search-space 알고리즘 재설계: 10배 개선 목표

작성일: 2026-09-19. 구현 갱신: 2026-09-19. 상태: **R0·R1 구현, R2 부분 구현, R3 조건부 보류, 유한 correctness PASS, 10배·공식 workload OPEN**.

## 1. 요구사항·범위·종료 조건

- 목표: candidate completeness와 legality를 유지하면서 search-space 구축 시간의
  **동일 조건 baseline 대비 10배 이상 개선**을 검증한다. 10배는 목표이지 보장이나 현재 실적이 아니다.
- 대상: 문자열 기반 정규화, 반복 proof graph 구축, fixed-point 재계산,
  support 조합의 명시적 전개 및 selector/receipt 소비 경계.
- 작업 위치: `/home/mchoi/systemds-g009-correctness`, branch `audit/g009-completeness`.
  기존 HEAD는 `35d1f49507a9cf3566674e68c6b97ccc1354d079`이지만 현재 dirty 구현은 별도 snapshot이다.
  HEAD만으로 새 baseline identity를 표기하지 않는다.
- 선행 문서: [복잡도·최적화 계획](G009_SEARCH_SPACE_COMPLEXITY_OPTIMIZATION_PLAN_2026-09-19.md).
  이 문서는 기존 국소 최적화를 대체하는 reset 계획이 아니라, 남은 구조적 병목을 해결하는 후속 설계다.
- 초판 요청은 **계획 문서 작성만**이었다. 이후 구현 요청에 따라 production·test·계측을 변경했고,
  아래 11절에 실제 결과와 미완료 항목을 갱신했다.
- 구현 단계에서는 짧은 fixture로 단계별 판정을 끝낸 뒤에만 GLM 및 Docker 검증으로 진입한다.
  코드 변경마다 장시간 GLM을 실행하지 않는다.
- 종료 판정은 `구현`, `유한 correctness`, `10배 성능`, `메모리`, `workload qualification`,
  `전역 증명`을 별도 표시한다. 목표 미달을 구현 완료라는 말로 감추지 않는다.

### 불변 계약

1. candidate cap, sampling, 첫 proof만 유지, 임의 dominance 삭제 금지.
2. occurrence/producer authority/seed provenance/action/layout exactness가 다른 경우 구분한다.
3. TRead/TWrite는 CP/LOUT 또는 FED/FOUT만 허용하고 recompile CP/FOUT 및 runtime fallback은 금지한다.
4. 압축 표현도 모든 합법 조합을 나타내야 한다. oracle과 양방향 비교해 missing/extra를 검사한다.
5. finite corpus 통과는 전역 보존·종료 증명 완료가 아니다.
6. 실제 성능 비교는 동일 Docker 조건의 `run_LAN_docker.sh`만 사용한다.
   host JVM microfixture는 correctness·병목 진단이며 공식 workload 성능 결과가 아니다.

## 2. 현재 근거와 기존 주장 정정

### 2.1 직접 관측

2026-09-19 07:50 CEST 무렵 최종 GLM은 약 25분째 미완료였다.
`jcmd 3775111 Thread.print` 4회 관측은 모두 direct closure의 realization 병합에서
support clause 문자열 생성 또는 구조 hash 계산을 거쳐 정렬하는 경로에 있었다.
관측 명령은 세션 transcript에 있으며, 표본 수가 적어 전체 시간 비율은 알 수 없다.

당시 `jstat -gcutil`은 누적 GC 시간 약 38.6초, young GC 약 18,800회, full GC 0회를 보였다.
이는 높은 allocation pressure와 양립하지만 GC pause가 전체 지연의 주원인이라는 증거는 아니다.
같은 시점 fork RSS는 약 3.26GB였다. 앞선 2.32GB 관측을 안정적인 최종 peak로 사용할 수 없다.

### 2.2 코드 근거 지도

아래 행은 작성 시 dirty source 기준이며 실행 때 source hash와 symbol을 다시 확인한다.
경로 접두사 `P`는 `src/main/java/org/apache/sysds/hops/fedplanner/placement/`다.

| 근거 | 위치 | 관측 가능한 사실 |
|---|---|---|
| E1 | `P/PlacementAnalysis.java:354–368` | clause 비교가 normalized signature를 요청한다 |
| E2 | `P/PlacementAnalysis.java:542–567` | TreeSet으로 clause를 병합한 뒤 realization 생성자 canonicalization으로 전달한다 |
| E3 | `P/PlacementIdentity.java:821–840` | weak cache lookup에 구조 hash가 필요하며 예산 소진 후 새 문자열은 보관하지 않는다 |
| E4 | `P/NativePlacementContinuity.java:320–352,613–626` | private query마다 graph를 구축하고 pruning/grounding을 수행한다 |
| E5 | `P/NativePlacementContinuity.java:493–609` | grounding은 eligible graph/SCC refinement를 포함한다 |
| E6 | `P/NeutralPlacementGraphBuilder.java:2474–2491,2534–2553,2865–2910` | direct closure에서 revision마다 resolver를 새로 만들고 conservative dirty component를 계산한다 |
| E7 | `P/NeutralPlacementGraphBuilder.java:905,2919–3044` | 현재 factorization은 publication 직전 proof/binding/list 공유다 |
| E8 | `P/PlacementAnalysis.java:742–770` | clause별 receipt와 identity 기반 index/rank를 eager 생성한다 |
| E9 | `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java:93,247` | clause identity 검증 및 clause별 대안 생성이 있다 |
| E10 | `P/NeutralPlacementGraphBuilder.java:5876,5926–5944` | relocation product는 streaming이지만 leaf 열거 자체는 남아 있다 |

### 2.3 추론·불확실성

- **우선순위 높음:** 긴 문자열/재귀 hash를 중간 집합·정렬의 key로 쓰는 표현이 현재 병목에 기여한다(E1–E3).
- **가능성:** signature cache 예산 소진 후 반복 직렬화가 심해질 수 있다. 현재 fork의 실제
  cache saturation 여부와 총 allocation 비율은 추가 계측 없이 확정하지 않는다.
- **구조적 잔여 비용:** query graph 재구축과 명시적 support 전개가 남아 있다(E4–E10).
  이 비용이 GLM에서 각각 차지하는 비율은 미확정이다.
- 이전 답변의 **4–5배 처리량**, **16–33배 메모리 감소**는 검증된 실적으로 철회한다.
  객체 cardinality 도달 시간은 동일 작업 진척도를 보장하지 않고, cache 한도는 총 heap 절감량이 아니다.
- 기존 113분 중단 baseline에는 완료 시간이 없다. 이를 성공 실행 시간으로 나누어 speedup을 계산하지 않는다.
- 기존 P4는 제한적 structural sharing이다. selector까지 유지하는 압축 관계와 lazy receipt는 아직 구현되지 않았다.

## 3. 목표 구조

```text
기존: 반복 closure → query graph → support tuple 전개 → 문자열 정렬/병합
                   → 모든 clause receipt 생성 → selector

목표: analysis-local 구조 테이블
       → 공유 dependency graph + query별 제약
       → revision delta/SCC 재평가
       → 상관관계가 보존된 압축 support relation
       → relation-aware DP → 선택된 plan의 owner-bound receipt

공개 fingerprint / canonical ordering / 전체 export는 명시적 경계에서 처리
```

새 패키지 의존성은 추가하지 않는다. 세 변경을 한 번에 교체하지 않고 R1/R2/R3별로
구현·equality gate를 통과시킨다. 조건부 단계는 `미적용/근거`를 기록하며 완료로 표시하지 않는다.

## 4. R0 — 짧은 feedback loop와 비교 기준 동결

**수정 대상:** `SearchSpaceMetrics.java`, `SearchSpaceMetricsEvaluatorTest.java`,
`NeutralPlacementFixedPointCompositionTest.java`; 필요시 전용 regression/evaluator 클래스.

1. 현재 dirty source, patch, untracked Java, resource manifest, JDK/JAR/class hash를 동결한다.
   기존 최적화 전 HEAD와 현재 최적화 snapshot을 혼동하지 않는다.
2. 장시간 GLM 대신 세 축의 deterministic fixture를 만든다.
   - **정규화:** clause 수, 문자열 깊이/길이, duplicate 비율, cache 예산을 독립 증가.
   - **공유 query:** 공통 dependency subgraph를 공유하는 root/seed 질의 수를 증가.
   - **product:** 입력 수와 선택지 수, 독립/상관 branch, truly-unique support를 독립 증가.
3. 작은 크기에는 기존 구현과 독립 oracle의 전체 집합 비교를 적용한다.
   큰 크기는 work/bytes scaling 측정용으로 분리하고 전수 검증했다고 표현하지 않는다.
4. 수정 중 feedback 목표는 fixture당 10초 이내, 묶음 60초 이내로 둔다.
   초과 시 작은 동일 구조 fixture로 진단한다. 큰 case를 skip 처리해 acceptance를 통과시키지 않는다.
5. signature 호출/생성 bytes/cache hit, comparator/merge 횟수, graph 확장량, revision delta,
   SCC work, relation node/edge, leaf 및 receipt 수를 집계한다. instrumentation도 보유 예산을 둔다.

**Gate R0:** 보호 privacy의 비어 있지 않은 legal fixture, deterministic 출력,
old/instrumented 결과 동일, mutation sentinel이 missing과 extra를 각각 탐지한다.

## 5. R1 — 문자열 중심 정규화에서 구조 중심 정규화로

**우선 구현 대상:** E1–E3, `PlacementAnalysis`의 canonical list/merge,
`PlacementIdentity`의 structural identity, builder 중간 fact 비교.

### 구현 순서

1. immutable key를 analysis-local arena에 등록한다. key는 tag + 완전한 필드 + child ID로 구성한다.
   ID는 해당 analysis 안에서만 의미가 있으며 foreign analysis/owner ID는 거부한다.
2. hash는 계산 재사용을 위한 보조 값이다. 충돌 시 full structural equality를 확인한다.
   소유권이 필요한 occurrence/action은 identity token을 분리하고 서로 다른 owner를 합치지 않는다.
3. 중간 dedup/변경 검출을 구조 key로 바꾸고 canonicalized 객체를 revision 간 재사용한다.
   이미 정규화된 목록의 재정렬과 TreeSet→재정렬 경로를 제거한다.
4. **순서 의미를 먼저 audit한다.** first-match, tie-break, 진단에서 관측하는 순서를 보존해야 한다.
   순서가 무관한 중간 집합만 publication 시점으로 정렬을 미룬다.
5. 기존 signature의 정확한 lexical 순서를 재현하는 비교 경계를 만든다.
   단순 ID sort/필드 tuple sort로 바꾸지 않는다. escaping, 길이 prefix, 숫자, Unicode와
   긴 공통 prefix까지 기존 문자열 comparator와 차등 검증한다.
6. boundary 직렬화는 별도 수행한다. 긴 signature를 매 comparison에서 재구성하지 않도록
   구조 공유 token representation 또는 검증된 lexical rank를 사용한다. 전체 문자열을
   무제한 캐시하는 방식으로 메모리 문제를 되돌리지 않는다.

**Gate R1:**
- 작은 universe의 raw/decoded plan set, canonical ordering, fingerprint, 오류 계약 동일.
- hash collision/foreign owner/같은 pool의 다른 seed 테스트 통과.
- 중간 key lookup은 signature serialization을 호출하지 않음(counter assertion).
- 동일 집합 재병합은 불필요한 전체 정렬을 하지 않음; 신규 고유 입력 수와 비교해 work를 보고.
- 정규화 stress에서 시간/allocated bytes 감소, 전체 live heap/RSS 비악화.
  10배 목표는 이 단계 단독 성공으로 미리 가정하지 않는다.

## 6. R2 — query graph 재구축에서 공유 graph + delta 계산으로

**대상:** E4–E6, `NativePlacementContinuity` 및 direct closure.

### R2-A: 구조 공유와 제약 분리

- snapshot 내 연산·edge·candidate topology를 공유하되 query별 root pin, complete fixed environment,
  seed provenance, witness와 exactness, template mode는 overlay에 남긴다.
- 같은 endpoint/pool이라는 이유로 query 결과를 합치지 않는다. `(node,pool)` memo는 금지한다.
- root pin과 무관한 expansion만 공유한다. pin에 영향을 받는 대안은 guard/overlay로 표현하고
  기존 per-query solver와 비교한다. 분리가 입증되지 않은 경로는 기존 query 알고리즘을 유지한다.
- batch seed/witness 표현을 사용하더라도 bit 위치와 원래 authority를 일대일 대응시킨다.
  batch 크기 증가에 따른 메모리를 계측하며 단순 seed union으로 provenance를 지우지 않는다.

### R2-B: 증분 evaluation

- 기존 composed-transfer 순서를 유지한다. 영향 graph에는 positive뿐 아니라
  absent/rejected dependency와 같은-value alias 관측도 포함한다.
- addition은 새로 생긴 delta를 dependent에게 전달한다. remove/replace는 justification을
  invalidate하고 영향을 받은 영역을 재구축한다. add-only 알고리즘으로 의미를 바꾸지 않는다.
- SCC merge/split 및 ground loss는 영향 영역의 SCC를 다시 계산한다.
  cyclic proof를 단순 reference count로 유지하면 self-support가 남을 수 있으므로 금지한다.
- 기존 grounded/coinductive cycle 의미(E5)를 일반 reachability나 단순 least-fixed-point로
  대체하지 않는다. SCC solver 변경은 별도 equivalence 의무다.
- 불확실한 invalidation은 전체 재계산으로 넓힌다. 이것은 planner의 안전한 재계산이며
  runtime fallback이나 후보 축소가 아니다.
- cache/graph arena는 revision lifetime 및 보유 예산을 명시한다. 퇴거는 재계산만 유발한다.

**Gate R2:**
- root pin/occurrence/seed/range/exactness 한 필드씩 바꾼 poison matrix 통과.
- negative→positive, ground add/remove, edge replace, SCC split/merge, alias domain 변화에서
  full-recompute와 전체 출력 및 오류 동일.
- 독립 component 변경 시 최초 index 비용을 제외한 무관 component expansion/SCC scan 0.
- 동일 topology 질의 수를 늘릴 때 shared expansion 재사용량을 실제 counter로 증명.
- 0/small/default cache budget과 eviction 이후 결과 동일; IN_PROGRESS 결과 publish 금지.

## 7. R3 — 명시적 product에서 selector가 직접 소비하는 압축 관계로

**진입 조건:** R1/R2 후에도 고유 support 전개/receipt가 시간·메모리 병목임을 짧은
product fixture와 profiler로 확인한다. 미진입이면 `R3 보류: trigger 미확인`으로 기록한다.

**대상:** E7–E10, `CandidateSelections.java`, DP selector의 실제 호출 경계,
Exact 모델의 clause/receipt API. DP 호출 경로는 구현 시작 시 trace해 변경 symbol을 확정한다.

### 표현과 소비

1. `Choice(OR)`, `Conjunction(AND)`, binding/authority atom을 가진 immutable relation을 설계한다.
   공통 하위 관계는 공유하되 cyclic dependency는 SCC 관계로 취급한다. 전체를 DAG라고 가정하지 않는다.
2. 다음 두 식은 동치가 아니므로 변환하지 않는다.
   `(A1 AND B1) OR (A2 AND B2)` ≠ `(A1 OR A2) AND (B1 OR B2)`.
3. shared producer 선택, edge position, action, seed, exactness 및 관측 가능한 proof 차이를
   constraint/atom으로 유지한다. OR의 중복 제거 범위는 기존 equality와 같아야 한다.
4. DP는 relation과 경계 assignment를 직접 소비한다. 서로 독립이라는 증명 없이
   자식별 minimum을 합하지 않는다. shared producer/materialization 비용의 중복 계상도 막는다.
5. 선택 후 receipt를 생성하는 owner-scoped factory를 설계한다. 기존 eager ordinal/rank,
   `==` membership, serialization과 tie-break 계약을 audit하고 호환 방식을 먼저 확정한다.
6. 전체 enumeration은 oracle/export용 명시적 iterator로 제공한다. builder 바로 뒤에서
   모든 clause/receipt를 재전개하는 adapter는 성능 완료로 인정하지 않는다.
7. DP부터 통합하고 FedAll → Heuristic → Exact 순으로 소비 경계를 전환한다.
   과도기 eager adapter가 필요한 consumer는 비용과 지원 상태를 명시하며 전체 pipeline 완료로 부르지 않는다.

**Gate R3:**
- 작은 독립/상관 universe에서 `Decode(New) = Decode(Base) = Oracle` 양방향 비교.
- crossed OR, 동일 producer 재선택, 다른 relocation action, foreign receipt 반례 통과.
- DP optimum cost와 기존 canonical selected plan 동일; 작은 case는 Exact와 비용 parity 확인.
- 독립 8-input×8-option fixture는 표현 단계에서 16,777,216 tuple/receipt를 만들지 않음.
  전체 export를 실행하면 그 열거 비용은 별도 기록한다.
- builder→selector→선택 검증→serialization까지 allocation/live heap/RSS 측정.
  최종 단계에서 flatten하여 절감이 사라지면 FAIL.

## 8. 복잡도 목표와 하한

기호: `C` 고유 구조 수, `B` 구조 필드 총량, `Q` 질의 수, `Gq` 기존 질의별 누적 graph work,
`Gshared` 공유 topology 구축 비용, `Δ` 갱신 작업량, `H` 압축 relation 크기,
`U` 명시적 결과 수, `L` 기존 signature 길이, `N` 정렬 원소 수.

| 구간 | 바꾸려는 비용 | 남는 비용 |
|---|---|---|
| R1 | cache miss 비교마다 직렬화: 최악 `O(N log N × L)` 규모 | 구조 등록 `O(B)` expected + equality/collision + 정확한 canonical 경계 비용 |
| R2 | `Σq Gq`의 중복 topology expansion | `Gshared + Σq overlay/evaluation_q + delta/SCC work`; pin별 문제는 남음 |
| R3 | 선행 tuple/receipt 전개 `Ω(U)` | relation `H`와 제약 evaluation; 전체 export 시 `Ω(output bytes)` |

위는 목표 분해이지 전역 복잡도 정리가 아니다. 긴 구조 equality/lexical 비교는 상수 비용이 아니며,
dirty 영역은 최악에 전체 graph다. 강한 상관관계/공유 변수 때문에 `H`와 DP 상태도 지수적으로 커질 수 있다.
독립 product의 압축 성공을 일반 입력의 polynomial 보장으로 확대하지 않는다.

10배 전체 개선은 Amdahl 제약도 받는다. 기존 시간의 비율 `f`만 `s`배 개선하면
전체 배수는 `1 / ((1-f) + f/s)`다. 예를 들어 90% 구간을 10배 개선해도 전체는 약 5.26배다.
따라서 한 microbenchmark의 10배를 전체 GLM 10배로 보고하지 않는다.

## 9. 검증 순서와 정량 acceptance

1. **변경 전 테스트:** R0 corpus와 golden ordering/identity 계약부터 고정한다.
2. **단계별 짧은 gate:** R1 → R2 → 조건부 R3. 실패 시 해당 단계만 수정하고 실패 artifact 보존.
3. **넓은 회귀:** GlobalReceipt/ProductionDecoded/IndependentPlanSpaceGeneration/
   CandidateReceiptAssignment/NativeLineage completeness, NativePlacementContinuity,
   DynamicNativeLayoutComposition, FixedPointComposition, ExactPhysicalModelCertificate,
   privacy/TR-TW/recompile/relocation 회귀. PUBLIC-only skip은 pass에 포함하지 않는다.
4. **정적·build 검증:** compile/package, 설정된 static checks, production branch inventory
   재생성 및 추가/삭제 branch별 분류, `git diff --check`. 없는 lint를 실행했다고 표기하지 않는다.
5. **GLM 진입:** 채택한 단계 구현 및 짧은 gate가 모두 끝난 후 한 번 실행.
   새로운 실패가 나오면 동일 실패를 작은 fixture로 축소한 뒤 재수정한다.
6. **성능:** 동결 baseline/new의 동일 Docker image/JAR/data/privacy/worker/CPU/JDK/heap/GC 조건,
   fresh JVM 각각 최소 3회 교차 실행. profiler off의 raw timing과 profiler on의 allocation은 분리한다.
   동일 worktree Maven/target 동시 실행 금지; 다른 작업 부하도 기록·통제한다.
7. **10배 PASS:** 사전 선언한 GLM search-space build 구간의
   `median(Tbase) / median(Tnew) >= 10`, 모든 반복 정상 완료, correctness gate PASS.
   selection/serialization을 포함한 planning 총시간과 workload 총시간은 별도 표로 공개한다.
8. **메모리 PASS:** 동일 heap 완료 + 해당 컨테이너 및 allocated bytes 감소 + 전체 pipeline
   peak live heap/RSS 비악화. 오차가 크면 INCONCLUSIVE. heap histogram만으로 owner를 확정하지 않는다.
9. baseline 미완료는 `CENSORED`이며 median 배수는 OPEN. 같은 조건의 관측 한계와 new 완료로
   하한을 제시할 수 있는 경우에도 `하한`으로 명시하고 원시 배수와 구분한다.
10. GLM 외 ML/P1/P2/SliceLine은 각각 별도 qualification 행을 둔다. GLM 통과로 일괄 PASS하지 않는다.

### 산출물

`build/g009-redesign/<run-id>/`에 다음을 보관한다(아직 생성됐다는 뜻이 아님).

- manifest: baseline/new source·class·JAR·fixture·환경 hash, exact command, privacy, 상태
- oracle-diff: missing/extra 전체 diff, mutation sentinel, 지원 corpus 및 skip 사유
- work-metrics: phase/query/graph/serialization/relation/receipt counters와 scope
- timing: build/selection/serialization/전체 시간, 모든 반복 값·median·범위
- memory: allocated bytes, live heap, process/container RSS와 수집 방법
- source-before/after hash, branch inventory 분류, reviewer 판정, 실패 로그

## 10. 위험과 완화·현재 완료 판정

| 위험 | 완화 및 감지 |
|---|---|
| ID 도입으로 authority 병합 | analysis/owner token 분리, foreign owner와 hash collision negative test |
| 정렬 지연으로 first-match/tie-break 변화 | 소비 지점 audit, byte-exact lexical ordering·selected plan 차등 비교 |
| query overlay에서 pin 누락 | key-field poison matrix, per-query solver shadow |
| 증분 삭제로 self-grounding 잔존 | affected SCC 재평가, cyclic ground-loss/split/merge 회귀 |
| 압축 OR의 상관관계 소실 | 작은 전수 decoder oracle, crossed-branch mutation sentinel |
| DP의 shared 비용/선택 불일치 | shared-variable 경계 상태 유지, Exact cost parity |
| interning이 메모리 증가 | analysis lifetime, bounded optional caches, 전체 pipeline 측정 |
| 긴 GLM이 개발 loop를 점유 | 병목별 짧은 scale fixture 우선, 최종 gate에서만 GLM |

초판 시점에는 **후속 설계 문서만** 완료되어 있었다. 이후 구현 결과는 다음 절의 fresh artifact로만
판정한다. 유한 테스트 통과와 전역 증명, 진단 GLM과 공식 Docker workload는 계속 분리한다.


## 11. 2026-09-19 구현·실험·검증 결과

### 11.1 채택한 구현

- **R0 — 구현:** `SearchSpaceMetrics`와 opt-in evaluator를 추가했다. proof graph/SCC 계측은
  graph 생성과 Tarjan 순회에 합쳐 계측만을 위한 전체 edge 재순회를 제거했다. ACTIONS fingerprint는
  `48f343237ba65b7fbe074cf7b9592378c0477025cd80d6033a0a5faea3a1d0e7`로 유지됐다.
- **R1 — 구현:** analysis-local structural handle arena, 충돌 시 구조 equality, arena overflow용 음수
  resolver-local handle, exact lexical rope comparator, 이미 canonical인 list 공유, 불필요한 중간 sort 제거,
  bounded signature cache를 구현했다. legacy 문자열 순서와의 차등 테스트를 포함한다.
- **R2-A — 구현:** occurrence/witness별 `CandidateTopology`를 root pin/seed provenance overlay와 분리했다.
  clause-pinned handle과 query fixed handle을 topology skeleton에 보관해 edge마다 구조 handle을 다시 찾지 않는다.
- **R2-B — 부분 구현:** 완성 support solution을 `(root constraint, physical witness)`로 공유한다. 외부 seed
  provenance는 cache key에서 지우지 않고 결과 생성 경계에서 다시 붙인다. 각 solution이 실제 방문한
  occurrence footprint를 보유하고, 다음 revision에서 그 footprint의 fact가 모두 동일하며 명시적으로
  invalidated되지 않은 경우에만 이전한다. eviction/zero budget은 결과 축소가 아니라 exact 재계산이다.
  그러나 query-local prune/SCC 자체를 하나의 전역 delta solver로 완전히 대체하지는 않았다.
- **R3 — 부분 구현 후 조건부 보류:** final clause/proof/binding/list는 실제 replacement가 있을 때만
  재구축하며, receipt object와 legacy lexical rank는 selector가 요청할 때까지 생성하지 않는다. GLM에서
  `candidateReceiptsCreated=0`, `receiptRankKeyChars=0`, final relation slot `99,632`이므로 eager receipt가
  더 이상 주 병목이라는 진입 조건은 성립하지 않았다. `Choice/Conjunction` relation을 FedAll/Heuristic/Exact가
  직접 소비하는 전체 R3 전환은 구현하지 않았고 완료로 표시하지 않는다.
- **의미 계약:** candidate cap/sampling/dominance pruning/runtime fallback을 추가하지 않았다.
  TRead/TWrite 합법 배치는 기존 `<CP,LOUT>`/`<FED,FOUT>` 계약을 유지한다. function-input 생성자 검사는
  구조 authority와 row shape만 검증하고, 실제 candidate legality/physical edge/selector 검증을 대체하지 않는다.

### 11.2 짧은 gate와 유한 correctness

| 항목 | 결과 | 증거 |
|---|---:|---|
| ACTIONS fingerprint | PASS | `build/g009-redesign/fused-metrics-actions-20260919T143600+0200/metrics.json` |
| support memo/revision poison tests | PASS | exact seed provenance, fallback root rebinding, invalidated-footprint 재계산 |
| broad selected suite | 173 tests, failure/error 0/0, skip 4 | `build/g009-redesign/final-broad-20260919T144851+0200/` |
| candidate branch manifest | PASS | current `5,805`, HEAD `5,473`; added `618`, removed `286` 전부 분류 |
| package | exit 0 | `mvn -q -DskipTests package` |
| diff hygiene | PASS | `git diff --check` |

위 PASS는 repository가 보유한 유한 corpus에서 missing/illegal-extra가 관측되지 않았다는 뜻이다.
모든 가능한 DML program에 대한 candidate completeness·legality·termination의 **전역 증명은 완료하지 않았다**.

### 11.3 최종 GLM 진단 E2E

채택 artifact는 `build/g009-redesign/final-glm-revision-support-20260919T145212+0200/`다.
이 run 이후 public-result memo를 revision 간 이전하는 실험은
`build/g009-redesign/final-glm-public-memo-20260919T151513+0200/`에서 wall `15:50.40`,
RSS `31,670,436 KB`로 악화되고 실제 lookup hit도 늘지 않아 **기각·revert**했다. occurrence별
realization index 실험도 structural-key 버전 `17:27.72`/`30,464,588 KB`, handle-key 버전
`15:47.38`/`30,413,996 KB`로 느려졌다. 전자는 2,219만 structural membership, 후자는 같은 수의
handle lookup 비용을 새로 만들었으므로 둘 다 revert했다. 현재 production diff는
채택 artifact의 `src/main/java` patch와 SHA-256
`9009de2ee9f85f25f5030fc5540f094a11c683e6c028c423d2c236ad9a957bdc`로 일치한다.

| 지표 | 첫 정상 완료 | 채택 최종 | 변화 |
|---|---:|---:|---:|
| evaluator elapsed | 989.132 s | 837.223 s | 1.181x, 15.36% 감소 |
| process wall | 16:36.51 | 14:07.82 | 1.175x, 14.92% 감소 |
| max RSS | 32,677,300 KB | 30,550,684 KB | 6.51% 감소 |
| proof queries/graphs | 1,564,819 | 154,362 | 10.14x work 감소 |
| proof alternatives | 1,055,953,130 | 284,280,699 | 3.71x work 감소 |
| proof dependency edges | 1,815,978,863 | 513,715,666 | 3.53x work 감소 |
| SCC edges scanned | 2,638,082,016 | 872,529,353 | 3.02x work 감소 |

두 정상 run의 fingerprint는 모두
`98b41db8ffe6e38794fe7eb7788b49e9cf0dd66a6e2c75629ab6fedcdb387109`, node `1,992`,
candidate fact `2,160`, relocation action `293`이다. 최종 run은 support memo hit `1,410,457`, miss
`154,362`, revision reuse `66,326`을 기록했다.

### 11.4 acceptance 판정과 남은 병목

- **10배 workload PASS: OPEN/미달.** 첫 정상 완료 대비 wall 개선은 1.175x다. 과거 113분 초과
  중단 run과 조건이 같다고 가정해도 현재 완료 시간은 약 `>8.0x` 하한일 뿐이며 censored baseline으로
  10배 median을 주장할 수 없다.
- **공식 성능 PASS: 미실행.** 동일 Docker `run_LAN_docker.sh`, fresh JVM 3회 교차 실행이 없으므로
  위 수치는 correctness/병목 진단 E2E이지 공식 workload 성능값이 아니다.
- **메모리 PASS: OPEN.** 단일 진단 run RSS는 6.51% 낮지만 동일 조건 반복·container RSS·allocated bytes가
  없어 acceptance를 확정하지 않는다.
- **남은 병목:** graph/SCC work는 크게 줄었지만 wall은 같은 비율로 줄지 않았다. final realization merge,
  canonical boundary, provenance-bearing proof/closure 재생성의 allocation과 CPU 비율을 fresh profiler로
  분리해야 한다. full shared delta SCC와 selector-native compressed relation은 미완료다.
- **workload qualification:** GLM 외 ML training, P1, P2, SliceLine은 실행하지 않았고 모두 UNQUALIFIED다.
