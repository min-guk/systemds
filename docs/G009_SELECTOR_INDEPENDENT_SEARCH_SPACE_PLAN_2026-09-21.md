# G009 selector 독립적 search space 보존 및 제한적 pruning 재계획

- 작성일: 2026-09-21
- 소스 기준: `a6281207cf520171af47f5f8755a34cbd28ecf37`
- 상태: 최초 작성 시 계획만 수립. 후속 실행 결과 및 미완료 기준은 [실행 보고서](G009_SELECTOR_INDEPENDENT_SEARCH_SPACE_EXECUTION_REPORT_2026-09-21.md)를 참조.
- 선행 분석: [현재 pruning 방식과 정확성 조건](G009_CURRENT_PRUNING_MECHANISM_AND_CORRECTNESS_2026-09-21.md)
- 이번 계획의 우선순위: **합법적 공간 보존 → selector 경계 보장 → 제한적 pruning 검증 → 표현/탐색 성능 개선**.

## 1. 요구사항 요약

selector마다 목적함수·정책·제약이 다르다. 공통 후보 생성기가 어느 selector의 선호도를 미리 적용하면 다른 selector의 정답을 없앨 수 있다.

따라서 다음 계약을 구현·검증한다.

1. runtime, privacy, authority 및 명시된 전역 합법성 조건을 만족하는 계획을 공통 공간에서 보존한다.
2. 공통 공간은 PRESENT 수, anchor 정렬, hop/action 수, 특정 비용 가중치로 합법적 후보를 제거하지 않는다.
3. 모든 selector는 같은 분석 입력에 대해 전체 합법적 대안과 결합 조건에 접근할 수 있어야 한다.
4. selector는 진입 이후 자신의 정책·제약을 적용할 수 있다. 그 결과를 원본 공간이나 다른 selector로 전파하지 않는다.
5. 동등 표현 압축은 손실 없이 복원 가능해야 한다. 단순히 결과값이 같거나 hop이 적다는 이유로 서로 다른 물리 계획을 합치지 않는다.
6. 목적함수 기반 pruning은 selector 내부에서만, 그 목적함수에 대한 근거와 테스트가 있을 때 유지한다.
7. 정확성 보장 없이 후보를 줄여 60초를 달성하는 것을 성과로 인정하지 않는다.

**비목표:** selector들의 서로 다른 정책을 하나로 통일하기, 모든 selector에 전역 최적성 강요하기, runtime fallback 추가, privacy 완화, 전체 Cartesian product의 상시 materialization, 새로운 범용 solver/의존성 도입.

## 2. 현재 확인된 사실과 감사 대상

아래 행 번호는 기준 커밋 기준이다. “감사 대상”은 확인된 버그라는 뜻이 아니다.

| 근거 | 현재 확인 사항 | 이번 계획의 처리 |
|---|---|---|
| `placement/selector/PolicyCandidateSelectionView.java:24–29,52–58` | materialization-maximal projection을 정책 소유 view로 분리하며 analysis/graph 불변을 확인 | 이미 있는 경계를 재사용하고 실행 테스트로 고정 |
| `placement/CandidateSelections.java:1237–1289` | PRESENT 선호도, effect 대표화, anchor 정렬로 정책 domain 축소 | 공통 feasibility가 아닌 selector 정책으로 분류; 실제 누출이 있을 때만 이동/제거 |
| `placement/CandidateSelections.java:2388–2537` | support 검사, 선호도 상한, emission 하한, zero-target, rank pruning | selector 전용 규칙별 독립 검증 |
| `placement/CandidateSelections.java:2556` 이후 | factor component 분리 | 모든 결합 관계 포함 및 분리 전후 결과 비교 |
| `placement/RelocationSelections.java:524,556` 이후 | exact demand와 relocation/FOUT 하한 | zero-target·suppression·공유 효과 검증 |
| `placement/LocalMaterializationSelections.java:292` | 미래 derived-FOUT 억제를 고려한 local 하한 | prefix별 admissibility 검사 |
| `fedCostBased/fedExact/ExactPhysicalModel.java:177–198` | analysis의 candidate facts를 읽음 | selector 입력에서 정책 projection 우회/누출 여부 확인 |
| `fedCostBased/fedExact/ExactPhysicalReducedSolver.java:36` 부근 | exact quotient라고 명시 | 명칭이 아니라 decode와 대체 가능성으로 확인 |
| `fedCostBased/fedExact/LocalCategoricalOptimizer.java:843` 부근 | local representative 축소 통계/처리 존재 | DP-local 내부 범위와 기존 알고리즘 계약에 맞는지 확인 |
| `placement/PlacementAnalysis.java:74–108` | canonical 정렬과 동일 항목 처리 | 정렬/중복 처리와 의미 있는 대안 삭제를 구분 |

경로의 공통 prefix는 `src/main/java/org/apache/sysds/hops/fedplanner/`이다.

현재 증거만으로 “DP도 정책 projection으로 공간이 잘렸다”고 단정하지 않는다. 반대로 정책 view 하나가 분리됐다는 사실만으로 전체 builder/index/selector 경계가 안전하다고 결론내리지 않는다.

## 3. 수학적 계약

### 3.1 표현의 완전성과 건전성

입력 및 합법성 조건 \(I\)에 대한 합법적 계획 집합을 \(S(I)\), 공통 표현을 \(R(I)\)라고 한다.

\[
\operatorname{Decode}(R(I))=S(I)
\]

Decode는 row 수가 아니라 geometry, placement, realization, support, relocation choice, authority, emission 관계를 포함한 **결합된 계획**을 복원해야 한다.

압축 때문에 raw row 수가 달라져도 decoded 계획 집합은 같아야 한다. 동일 input bitmask, 동일 출력 shape, 동일 scalar cost만으로 같은 계획이라고 보지 않는다.

### 3.2 selector별 목적함수 분리

selector \(j\)의 정책/제약을 \(Q_j\), 목적함수를 \(f_j\)라고 하자.

\[
\text{InputUniverse}(j)=S(I),\qquad
S_j=\{x\in S(I)\mid Q_j(x)\}
\]

\(Q_j\)는 selector가 입력을 받은 뒤 적용한다. 공통 runtime/privacy 합법성 조건과 selector가 선택한 정책 제약을 별도로 기록한다.

Exact selector는 선언된 목적함수와 tie-break에 따른 최적 선택을 보존해야 한다. Heuristic/DP-local이 전역 최적성을 약속하지 않는다면 그 알고리즘의 명시된 선택 계약을 검증하며, 서로 다른 selector의 winner가 같아야 한다고 요구하지 않는다.

### 3.3 공통 계층에서의 dominance 제한

어떤 계획 \(a\)가 현재 \(f_1\)에서 \(b\)보다 싸다는 사실은 다른 \(f_2\)에서도 그렇다는 뜻이 아니다. 허용되는 목적함수 전체를 한정하지 않았다면 보편적 비용 dominance를 주장하지 않는다.

따라서 초기 구현에서는 공통 계층의 새로운 비용 dominance를 도입하지 않는다. 짧은 계획으로 대체하는 최적화도 다음을 증명하지 못하면 두 대안을 유지한다.

- 어느 합법적 consumer 문맥에서도 결과 의미·수치 요구·placement·privacy·authority가 보존됨.
- 다른 consumer의 공유 결과와 이후 비용/메모리/제약에 관찰 가능한 차이가 없음.
- selector가 구분하는 정보와 원래 대안을 복원하는 데 필요한 정보가 보존됨.

비용상 우열이 있지만 관찰 가능한 차이가 있다면 selector가 비교할 대안으로 남긴다.

## 4. pruning 분류 및 기본 처분

| 분류 | 공통 계층 허용 | 기본 처분/증명 의무 |
|---|---|---|
| runtime/privacy/authority 위반 | 허용 | 명시적 제약과 위반 witness; 합법 반대 사례도 테스트 |
| 부분 선택의 합법 completion 없음 | 허용 | 정확한 domain에 대한 필요조건; 독립 oracle로 completion 0 확인 |
| 동일 객체/동일 의미의 중복 인코딩 | 조건부 허용 | 관찰 가능한 구분 손실 없음; 원래 대안의 decode/identity 계약 보존 |
| PRESENT/anchor 선호도 | 불허 | 해당 selector 내부로 제한 |
| action/hop 개수에 따른 축소 | 불허 | selector 목적함수에 명시됐을 때만 내부 적용 |
| 비용 dominance/상한·하한 pruning | 불허 | selector 목적함수·제약·tie-break를 지정하고 내부 적용 |
| effect/representative quotient | 이름만으로 허용 안 함 | 정보 손실 없으면 압축으로 유지; 증명 불충분하면 대안 복원 |
| component 분리 | 조건부 허용 | feasibility와 해당 selector 목적의 모든 coupling 포함; 비용 분해 불가하면 분리 최적화 금지 |
| 캐시·인덱스·지연 열거·탐색 순서 | 허용 | 도달 가능한 계획 집합과 선택 계약 불변 |

증거가 없는 공통 후보 삭제는 보존 방향으로 바꾼다. 증거가 없는 selector 내부 pruning은 해당 shortcut만 제외하고 같은 domain의 완전 탐색 또는 기존 안전 경로를 사용한다. timeout을 “불가능”으로 바꾸거나 합법성 검사를 해제하지 않는다.

## 5. 실행 단계

### S0. 기준 고정과 bounded 삭제 지점 목록

**작업**

- 기준 commit, source diff, JAR hash, 입력/설정/privacy 조건, planning 측정 경계를 기록한다.
- 우선 표 2의 코드 및 각 production selector 진입까지의 호출 경계를 추적한다.
- 후보 `filter/skip/dedup/representative`, reachability, component 분리별로 다음을 문서 표에 남긴다: 위치, 원본/selector 내부 여부, 적용 목적함수, 삭제 근거, 복원 가능성, 테스트, 유지/이동/제거/미확인 판정.
- 일반 정렬/캐싱을 후보 삭제와 혼동하지 않는다. 호출 관계를 벗어난 전 저장소 무제한 감사는 하지 않는다.

**완료 기준:** 알려진 정책 projection, effect quotient, exact/local representative 및 신규 pruning에 모두 소유 계층과 처분이 기록됨. 미확인은 완료로 처리하지 않음.

### S1. 변경 전 작은 독립 회귀 테스트 고정

**기존 기반:** `CandidateReceiptAssignmentCompletenessTest`, `GlobalReceiptPlanSpaceCompletenessTest`, `IndependentPlanSpaceGenerationCompletenessTest`, `ProductionDecodedPlanSpaceCompletenessTest`, `RelocationActionPlanSpaceCompletenessTest`.

**작업**

- 큰 GLM builder에 의존하지 않는 작은 fixture에서 합법 계획 집합을 literal/독립 규칙으로 정의한다.
- oracle은 production의 정책 filter, effect key, lower bound, component 분리를 정답 생성에 재사용하지 않는다.
- 같은 합법 공간에서 실행시간 목적과 통신량 목적의 winner가 반대가 되는 fixture를 만든다.
- 같은 shape/입력 bitmask지만 worker/range/authority/support가 다른 후보를 함께 넣는다.
- 결합된 support, 공유 action, suppressible local action, non-zero optimum, canonical 동점을 각각 작은 사례로 분리한다.

**완료 기준:** missing/extra decoded 계획을 양방향 검출함. 대안 하나 또는 support edge 하나를 고의로 빠뜨린 테스트 데이터에서 검사가 실패함. 안전한 기존 동작은 회귀 테스트로 고정하고 실제 결함은 failing test로 먼저 재현함.

### S2. selector 입력의 전체 공간 및 불변성 보장

**파일 범위:** `PlacementAnalysis`, `NeutralPlacementGraphBuilder`, `CandidateSelections`, `PolicyCandidateSelectionView`, `PolicyFirstFeasiblePlacementSelector`, `ExactPhysicalModel`; 필요한 경우 발견된 실제 호출부만 추가한다.

**작업**

- 원본 analysis가 합법 대안과 결합 관계를 보유하고 selector가 그 공간을 소비하는 경계를 확인·수정한다.
- PRESENT/anchor 등의 정책 filter가 공통 경로에 발견되면 selector 내부로 옮긴다. 이미 내부인 경로는 불필요하게 재작성하지 않는다.
- selector별 mutable working domain/cache가 원본 후보나 다른 selector의 view를 오염시키지 않도록 기존 immutable 구조와 호출별 상태를 재사용한다.
- 같은 입력에서 heuristic → DP 및 DP → heuristic 순서로 실행해 원본 decoded 집합이 변하지 않는지 확인한다. 모든 selector에 동일한 순회 API나 eager 객체 목록을 강요하지 않는다.

**완료 기준:** 각 selector 진입점의 도달 가능한 decoded 공간이 독립 oracle과 일치하고, 실행 전후/실행 순서 변경 후에도 원본 공간이 동일함. 다른 목적함수의 selector가 서로 다른 올바른 대안을 선택할 수 있음.

### S3. 공통 dedup/quotient를 손실 없는 표현으로 제한

**파일 범위:** S0에서 실제 공통 삭제로 분류한 builder/analysis/identity/index 지점. `CandidateEffectKey`가 정책 내부라면 공통 dedup과 별도로 검사한다.

**작업**

- 각 key가 placement geometry, ordered input occurrence, realization/support, relocation source/anchor, authority, 공유/억제 emission 차이를 보존하는지 확인한다.
- 완전히 동일한 중복 표현만 합친다. 차이가 있는 대안은 관계로 보존하거나 기존 원본 대안에 대한 복원 참조를 유지한다.
- “같은 출력이므로 같은 계획”, “같은 action 수이므로 동일 효과”인 축소를 허용하지 않는다.
- 결과가 같은 더 짧은 plan도 문맥 대체 가능성/관찰 동등성 증거가 없으면 제거하지 않는다.

**완료 기준:** 압축 전후 decoded 계획 집합 일치; 의도적으로 다른 authority/range/공유 효과를 넣으면 서로 구분됨. 정보 보존 증거 없는 common quotient는 채택 목록에 남지 않음.

### S4. selector별 내부 pruning 재검증

**파일 범위:** `CandidateSelections.Search/ComponentSearch`, `RelocationSelections`, `LocalMaterializationSelections`, `ExactPhysicalReducedSolver`, `LocalCategoricalOptimizer`; DP-global solver는 S0의 실제 호출 경로에 따라 제한적으로 추적한다.

**작업**

- selector별 목적함수·hard constraint·tie-break·exact/heuristic 계약을 명시한다.
- 정책 Search의 input/aligned preference 상한과 physical emission 하한을 다른 selector의 비용 하한으로 재사용하지 않는다.
- zero-target 강제 relocation/FOUT, action suppression, local 하한, canonical self-reduction을 각각 작은 exhaustive oracle로 검사한다.
- component 분리에 feasibility factor뿐 아니라 selector의 목적 coupling도 포함되는지 확인한다.
- pruning 없는 test-only 기준 경로와 비교한다. 테스트를 위해 production에 일반적인 “안전성 검사 끄기” 옵션을 추가하지 않는다.
- 증명/테스트가 없는 shortcut은 개별적으로 제외하고 원본 domain을 유지한다. selector의 의도된 정책을 임의로 변경하지 않는다.

**완료 기준:** exact 경로의 winner tuple/receipt/authority/rank가 전수 기준과 일치함. prefix별 하한이 실제 최적 completion보다 크지 않음. 불가능 판정된 prefix는 completion이 없음. heuristic/local 경로는 전역 optimum 대신 선언한 기준 알고리즘과 합법성/공간 불변을 검증함.

### S5. 후보를 삭제하지 않는 성능 회복

**작업**

- S2–S4 변경으로 비용이 증가한 실제 구간만 최소 계측으로 찾는다.
- 기존 immutable index 재사용, memoization, 중복 join 제거, structural key 공유, lazy decoding, factor 탐색 순서를 우선 적용한다.
- 상관관계를 잃는 단순 domain 압축을 피한다. component 분리는 S4 검증을 통과한 경우만 사용한다.
- 성능 때문에 S1–S3의 공간 보존 검사를 약화하지 않는다. 새로운 범용 추상화/외부 의존성을 도입하지 않는다.

**완료 기준:** 각 채택 최적화가 decoded 집합과 selector 계약을 보존하고 대상 구간의 측정 이득이 있음. 느린 결과는 회귀로 보고하며, 후보 삭제로 숨기지 않음.

### S6. worker=4 planning-only 1회 비교와 최종 보고

**대상:** 기존 ML training, P1, P2, sliceline workload. heuristic, DP-global, DP-local 각각 1회. FedAll은 이번 실행 행렬에서 제외한다.

**작업**

- `run_LAN_docker.sh` 기반 기존 실행 경로를 재사용하고 planning-only 종료를 확인한다. `run_LAN.sh` 결과를 채택 근거로 쓰지 않는다.
- source/JAR/input/config, worker=4, planner 옵션, memory/JVM, privacy 계약, 측정 경계가 같은 변경 전 기록이 있으면 재사용한다. 없으면 비교에 필요한 기준 조건을 각 1회만 실행한다.
- P2는 기존 명시적 공개 계약/설정을 동일하게 유지한다. performance 통과를 위해 opt-in이나 privacy를 바꾸지 않는다. 부정 privacy 사례는 작은 회귀 테스트로 유지한다.
- 전체 초기 planning, CandidateE2E, planner 전용 시간, wall, peak RSS를 구분한다. 프로세스 RC뿐 아니라 planning 완료/error marker도 판독한다.
- 한 번의 결과는 관측치로 보고한다. 통계적 유의성이나 반복 안정성을 주장하지 않는다. 18쌍/다회 pilot으로 확대하지 않는다.

**완료 기준:** 4 workloads × 3 planners의 결과/실패 원인이 기록되고 runtime 본실행이 없음을 확인함. 합법성이 기대되는 사례는 성공해야 하며 실패하면 해결하거나 미완료로 남김. 같은 조건의 기준 대비 시간·메모리 차이를 보고함.

## 6. 최종 수용 기준

| ID | 통과 조건 |
|---|---|
| A1 | 독립 finite oracle과 공통 decoded 계획 집합이 일치; missing=0, extra=0 |
| A2 | 목적함수/정책 변경은 selector 내부 선택만 바꾸며 원본 공간은 동일 |
| A3 | 서로 반대되는 목적함수의 두 selector가 동일 입력에서 각자 다른 정답을 선택할 수 있음 |
| A4 | selector 실행 순서와 반복 소비에도 원본/다른 selector domain이 오염되지 않음 |
| A5 | 공통 계층에 PRESENT/anchor/hop/action/특정 가중 비용에 의한 합법 후보 삭제가 없음 |
| A6 | 모든 채택 quotient의 decode가 구분 가능한 geometry/authority/support/shared-effect를 보존 |
| A7 | exact 내부 pruning 결과가 독립 전수 기준과 목적값 및 tie-break까지 일치 |
| A8 | prefix 하한/불가능 판정/zero-target/component 분리의 경계 사례 검사 통과 |
| A9 | privacy/runtime/전역 합법성 부정 사례는 계속 거부; 오류를 성공으로 취급하지 않음 |
| A10 | 관련 테스트, Java compile/package, 적용 가능한 기존 정적 검사 및 `git diff --check` 결과 기록; 미실행/기존 실패 구분 |
| A11 | worker=4 planning-only 1회 행렬 완료; 기준 조건과 시간 경계를 구분한 결과표 존재 |
| A12 | pruning 목록의 각 항목에 유지/이동/제거와 근거/테스트가 연결됨; 해결되지 않은 항목은 미완료 |

메모리/시간 목표를 만족하더라도 A1–A9를 대체할 수 없다. 작은 oracle 통과를 모든 입력에 대한 형식 증명이라고 표현하지 않으며, 일반 논증과 유한 검증의 범위를 함께 보고한다.

## 7. 빠른 feedback 및 검증 방법

1. 변경 전 작은 failing/회귀 테스트를 만든다.
2. 논리적 변경 한 묶음마다 그 테스트만 실행한다.
3. 통과 후 관련 completeness/selector 테스트로 확장한다.
4. 통합 시 한 번 compile/package 및 repository의 적용 가능한 정적 검사를 수행한다. 없는 lint/typecheck 체계를 새로 도입하지 않는다.
5. 최종에만 위 planning-only 행렬을 실행한다. 실패 디버깅에 필요한 해당 조건만 추가 실행하고 이유를 남긴다.

신규 독립 테스트는 큰 GLM fixture의 builder 비용을 피한다. 기존 넓은 suite의 GC-heavy 중단을 전체 증명 완료로 취급하지 않는다. 반복 benchmark보다 작은 반례의 missing plan/잘못 잘린 prefix를 먼저 확인한다.

## 8. 위험과 대응

| 위험 | 대응 |
|---|---|
| 합법 대안 복원 후 시간/메모리 증가 | lazy/factorized 표현과 기존 index 재사용; correctness 유지한 채 회귀를 공개 |
| oracle이 production filter를 재사용해 같은 버그를 놓침 | literal 기대 집합, 독립 legality 정의, missing/extra mutation 검사 |
| 전체 후보 수는 같지만 결합 관계가 손실됨 | row 수가 아닌 decoded joint plan identity 집합 비교 |
| 같은 scalar cost를 물리 동등성으로 착각 | authority/geometry/support/shared-effect 구분 fixture |
| policy Search의 optimum을 DP/runtime optimum이라고 보고 | selector별 목적함수·보장 수준을 결과표에 분리 |
| pruning을 모두 끄면서 합법성 검사까지 비활성화 | 불법성 게이트 유지; 검증되지 않은 objective shortcut만 개별 제외 |
| 새 객체 계층/계측 시스템 구축에 시간 소모 | 기존 view/analysis/test utility 재사용; 문서 표와 bounded probe만 사용 |
| 한 번의 timing 차이를 확정적 개선으로 표현 | 단일 관측임을 명시; 성능 통계 주장 금지 |

## 9. 산출물과 종료 조건

예정 산출물:

1. source 위치별 pruning 분류/처분 표.
2. selector 입력 공간·불변성·다중 목적함수 회귀 테스트.
3. raw/decoded 공간 및 제한적 pruning 독립 oracle 테스트.
4. 필요한 최소 코드 수정과 검증된 성능 변경만 포함한 diff.
5. worker=4 planning-only 1회 결과표와 baseline 조건/증거 경로.
6. 남은 미검증 항목, 기존 결함, 잠재 회귀가 명시된 최종 문서.

**종료는 “60초 미만”이나 “기존 P2가 성공”이 아니라 A1–A12의 충족 여부로 판단한다.** 해결되지 않은 항목은 완료로 숨기지 않는다.

최초 계획 작성 요청에서는 코드 구현·실험을 포함하지 않았다. 이후 별도 요청에 따라 실행했으며, 결과 및 수용 기준은 실행 보고서에 별도로 기록한다.
