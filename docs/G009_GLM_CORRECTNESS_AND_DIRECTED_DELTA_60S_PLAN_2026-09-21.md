# G009 GLM 정상 planning 및 방향성 증분 갱신 기반 60초 실행 계획

- 작성일: 2026-09-21
- 상태: **실행 결과는 [실행 보고서](G009_GLM_CORRECTNESS_AND_DIRECTED_DELTA_60S_EXECUTION_REPORT_2026-09-21.md)에 기록.** 핵심 DP-local 60초 목표는 단일 실행에서 달성했으나, 계획 전체는 부분 완료다. 아래는 작성 당시의 실행 명세다.
- 목표: 동일 P2P2D GLM, worker=4, DP-local에서 **정상 완료한 전체 초기 planning < 60초**. 공통 후보 공간·privacy·authority·selector 계약은 보존한다.
- 선행 근거: [최적화 실행 보고서](G009_GLM_SPACE_PRESERVING_OPTIMIZATION_EXECUTION_REPORT_2026-09-21.md), [JFR 진단](G009_GLM_PLANNING_BOTTLENECK_DIAGNOSIS_2026-09-21.md), [selector 독립 공간 계획](G009_SELECTOR_INDEPENDENT_SEARCH_SPACE_PLAN_2026-09-21.md).
- 이전 O0–O5의 미완료 항목을 이 계획에서 이어받는다. 이미 구현한 arena/canonical/no-change 최적화를 다시 만드는 계획이 아니다.

## 1. 요구사항과 고정 조건

1. X=50,000×2,100, 4-worker 배치, 기존 X/Y privacy·metadata·range 유지. `glm(dfam=2, link=2, vpow=0, lpow=1, yneg=0, icpt=0, disp=0, reg=0, tol=1e-6, moi=20, mii=5, verbose=FALSE)` 및 기존 label 전처리 유지. 입력 근거: `/home/mchoi/g009-glm-optimization-20260921/candidate6-tmp/cell-1/planning-only/gen_glm_P2P2D_4.dml`.
2. 입력 축소, iteration 수 감소, GLM을 다른 알고리즘으로 교체, privacy 완화, CP/FOUT TRead/TWrite 허용, runtime fallback, 임의 candidate cap/비용 dominance 추가 금지.
3. 실행은 `run_LAN_docker.sh`의 명시적 GLM planning-only 경로만 사용. worker=4는 placement 조건이며 worker JVM/학습 runtime 실행 허가가 아니다. 기존 frozen manifest를 GLM인 것처럼 위장하지 않는다.
4. 기본 개발 루프는 **작은 회귀 테스트 → 한 논리 묶음 구현 → GLM 조건당 1회**. 다회 pilot/ABBA, 전체 planner×workload matrix 자동 반복, 상시 JFR, 새 계측 프레임워크를 도입하지 않는다.
5. 60초를 만족해도 후보 누락·순서/권한 변경·메모리 회귀가 있으면 채택하지 않는다. 구현, 검증, 채택, 미완료를 분리한다. 이 계획은 60초 달성의 보장이 아니다.

## 2. 현재 사실, 정정, 가설

| 항목 | 근거 | 해석과 한계 |
|---|---|---|
| 원래/수정 GLM 모두 실패 | 실행 보고서, `baseline2` 182.87초 / `candidate6` 97.66초, 같은 `Y_prob` 오류 | 둘 다 coordinator time-to-error. 성공한 planning baseline이 아니며 46.6%를 E2E 가속으로 주장하지 않음. |
| 실패 지점 | `NeutralPlacementGraphBuilder.java:3915–3953` | replay alternatives가 reader PRIVACY exclusion에 의해 걸러진 뒤 states가 비면 실패. 실제 불가능인지 후보/CFG 누락인지는 미판별. |
| GLM의 복잡성 | `scripts/builtin/glm.dml:302–357,886–940,978–1018` | IRLS/CG 루프, 함수 호출, 조건부 대입/합류, layout-sensitive 연산의 조합. 실제 학습 반복을 실행해서 planning이 느린 것은 아님. |
| 반복 재처리 비용 | builder `:2589–2669`; JFR 보고서 | direct closure와 physical rebuild 후 grounding 모두 비용 소비. 고유 후보 증가와 반복 재처리량의 비율은 미측정. |
| 방향성 갱신의 근거 | `NativePlacementContinuity.java:1142–1187,659–703`; builder `:4041–4058` | proof는 입력 producer를 읽고, 변경 통지는 producer→consumer. physical closure도 이미 같은 방향 사용. |
| 양방향의 근거 부족 | builder `:3013–3073`; baseline2/candidate5/candidate6 로그 | 이전 GLM 오류는 단방향 전파의 반례가 아님. 현재 주석의 ‘양방향 필수’ 주장은 증명이 아니라 보수적 정책. 다만 edge만 뒤집어 모든 경우가 검증됐다고 하지 않음. |
| SCC는 이미 존재 | `NativePlacementContinuity.java:794–816` | SCC 알고리즘을 새로 추가하기보다 query/revision 간 재처리와 변경 전파 범위를 개선. |
| 실제 곱 열거 존재 | `NativePlacementContinuity.java:593–628,776–791` | 입력별 support 조합을 명시적으로 열거. 루프가 없는 DAG에서도 발생 가능. |
| 상수 전파도 이미 존재 | `DMLTranslator.java:300–320,374–393`; `FunctionCallSizeInfo.java:266–290` | placement 이전 IPA가 수행됨. 모든 generic GLM 분기가 남는다고 가정하지 않음. `glm.dml:939`는 link=2에도 남는 공통 문장. |

경로가 파일명만인 production 항목의 prefix는 `src/main/java/org/apache/sysds/hops/fedplanner/placement/`이며 DMLTranslator와 FunctionCallSizeInfo는 각각 `parser/`, `hops/ipa/` 아래에 있다. 행 번호는 작성 시점 작업 트리 기준이다.

## 3. 성공 기준과 측정 경계

### 3.1 60초의 정의

주 지표 `T_initial`은 동일 coordinator JVM 안에서 **DML parse 시작 → 초기 rewrite/placement/selector/검증·등록 및 LOP/runtime-program 생성 완료 → 실행을 건너뛰는 compile-only 종료 경계**까지의 wall time이다. 입력 metadata 접근이 이 경계 안에 있으면 포함한다. 정확한 timer 위치는 G0에서 코드와 receipt로 고정한다. Docker setup·worker 준비·JVM 부팅·cleanup은 별도의 command wall로 보고하고 섞지 않는다.

`T_initial = T_frontend + T_common_prepare + T_analysis + T_planner + T_publish_lowering`

기존 `CandidateFormationTiming`은 physical normalization 이전부터 receipt handoff까지다(`CandidateFormationTiming.java:10–37`). `CandidateE2E`는 위 전체 시간의 **부분 구간**이므로 더해서 이중 계산하지 않는다. `optimizer`, `selection`, `model/cost surface`, `otherPlanning`을 기존 exclusive timing으로 구분한다. `planner_begin/end`를 selector 단독 시간으로 표기하지 않는다. attribution이 불완전하면 그 구간은 미분리로 보고한다.

완료 조건:
- 정상 planning receipt, 오류 marker 없음, runtime_executed=false, execution_seconds=0.
- 동일 조건 단일 실행에서 `T_initial < 60.000s`; CandidateE2E와 analysis/selector 구간을 함께 제출.
- 작은 독립 oracle의 missing/extra=0, 기존 selector별 winner/tie-break/receipt 순서 보존.
- 동일 방법으로 측정한 coordinator peak memory가 성공한 correctness baseline 대비 5% 이내. 측정이 없으면 통과로 표시하지 않음.
- 단일 관측임을 명시하며 p95·통계적 유의성·모든 머신에서 60초 보장을 주장하지 않음.

### 3.2 baseline 분리

- **B_fail:** 원래 `8f10f189…` JAR. 실패 재현용으로 보존하며 성능 성공 baseline으로 사용하지 않음.
- **B_correct:** B_fail의 소스/patch를 재구성하고 G1의 최소 정확성 수정만 적용한 성공 baseline. 기존 미채택 최적화를 몰래 포함하지 않음.
- **C:** 같은 정확성 수정 위에 각 최적화 묶음을 적용한 후보. 현재 `209cc3b6…` 및 미커밋 patch는 실험 시작점이지 B_correct가 아님.
- 소스·JAR·입력/metadata·XML·JVM·Docker 이미지·CPU 배치·코드 옵션 manifest를 각각 보존. B_correct 재구성이 불가능하면 이유를 기록하고 before/after 가속률을 보류하되 절대 60초 검증은 별도로 진행.
- G1 자체가 합법적 공간을 복구하면 B_fail과 후보 수가 달라질 수 있다. 이 차이는 오류 수정으로 oracle에 근거해 검증하며, 이후 성능 변경은 **B_correct 공간**을 보존해야 한다.

## 4. 실행 단계

### G0. 현재 상태와 최소 측정 경계 고정

대상: `CandidateFormationTiming.java`, `DMLTranslator.java:369–494`, `Statistics.java:1221–1242`, 외부 `experiments/run_LAN_docker.sh` 및 GLM validator.

- 기존 실패 로그/JAR/미커밋 patch를 보존하고 원래 변경과 새 변경을 구분한다.
- 기존 timer/receipt를 우선 사용하고 필요한 시작·완료 marker만 보완한다. 실패 시 성공 receipt 금지.
- 별도 opt-in으로 기존 SearchSpaceMetrics의 pass 수, 방문/변경 row 수, support-product leaf 수, 캐시 reuse/overflow를 revision 요약 단위로 확인한다. 전체 고유 clause 집합 생성·per-candidate 출력 금지. 느린 진단은 성능 실행과 분리.
- GLM 실행 경로는 명시적 non-campaign workload로 유지. 최종 검증에는 입력/metadata SHA, runtime 미실행과 성공 receipt 검증이 있어야 하며 frozen campaign 인증으로 부르지 않는다. 부족한 validation만 최소 확장한다.

통과: 지표 이름·경계·manifest와 오류/timeout 판정이 재현 가능. 계측 확장 때문에 G1 착수를 지연하지 않는다.

### G1. `Y_prob` 정상 planning 확보 — 최우선

대상: `glm.dml:886–940`; builder `replayUniqueCfgTransientForward`, `exactTransientReplay:3542–3652`, `buildExactLogicalTransientRead:3910–3957`; `LogicalBoundaryRealizations`와 shared privacy analysis 관련 경로.

1. 실패가 난 **placement 직전 compiled HOP/CFG**에서 `Y_prob`의 실제 reaching writers, 각 source의 allowed realization/support, reader exclusions, 함수 boundary를 한 번 추출한다. 소스 DML의 모든 분기가 남았다고 가정하지 않는다.
2. protected source로 `link=2`의 `replace(±Inf) → exp/cbind → rowSums 나눗셈 → Inf 보정 → transient 합류`를 축소 재현한다. 함수/분기/loop를 한 요소씩 붙여 첫 실패 경계를 찾는다. 수치적 Inf/NaN 동작이나 초기값 경로를 없애지 않는다.
3. 작은 독립 expected relation으로 (a) 잘못된 reaching definition, (b) FED support 소실, (c) privacy authority 불일치, (d) 실제 infeasibility를 구분한다. 실패 메시지의 ‘privacy-safe’만으로 (d)를 선택하지 않는다.
4. 버그이면 담당 oracle/CFG/binding 경계에서 최소 수정한다. privacy guard 제거, source 없는 FED 후보 추가, 임시 fallback으로 성공시키지 않는다. 정확성 변화는 별도 patch로 보존한다.
5. 작은 재현이 통과한 뒤 동일 GLM DP-local 한 번으로 성공 receipt를 확인한다. 이후 B_correct와 C를 동일 정확성 상태로 맞춘다.

통과: positive protected fixture에서 기대한 전체 관계 보존, illegal/ungrounded negative fixture 거절, GLM 정상 planning 완료. 실제 infeasibility가 입증되면 60초 정상 성공 목표는 고정 입력 계약과 충돌하는 것으로 보고한다. 데이터/정책/스크립트 변경은 자동으로 하지 않으며 이 경우에만 범위 결정을 요청한다.

### G2. fact-only direct closure를 방향성 변경 전파로 전환

대상: builder `changedCandidateOccurrences:2994`, `affectedDirectClosureOccurrences:3019`, direct/physical-direct 루프 `:2589–2669`; `NativePlacementContinuity.nextRevision:234–279`.

보존 조건을 먼저 고정한다. 고정된 epoch의 불변 정보 M에 대해:

`x'_v = F_v(x_v, {x_u | u ∈ Dep(v)}, M)`

실제 의존 edge는 `u→v`다. 변경 row 집합 Δ 이후 방문 집합은 `Δ ∪ Reach(E_dep, Δ)`로 충분하려면 **F_v의 모든 가변 읽기**가 Dep(v)에 포함돼야 한다. self/negative lookup, 존재하지 않던 source에 대한 읽기도 고려한다. 이전 전체 pass가 완료된 상태에서 영향 밖 입력과 M이 같으므로 그 row를 유지할 수 있다는 불변식을 명시한다.

- compiled producer→consumer, CFG writer→reader, function argument/result carrier, source-support→owner를 목록화한다. 함수 경계가 별도 full closure로 처리되면 그 완료와 변경 row 검출을 보존한다.
- before/after 의존 edge를 모두 반영해 삭제·edge 교체도 무효화한다. 동일 value-version alias는 동등성/공유 상태 의미가 확인될 때만 묶고, 초기에는 기존 alias 확장을 유지한다.
- topology·geometry·privacy·runtime capability·publication owner가 바뀌면 fact-only 조건이 깨진다. 해당 epoch/의존 노드 재구성 또는 동일 의미의 full compiler recomputation을 한다. runtime fallback이 아니다.
- proof 조회의 consumer→producer 방향과 dirty 통지의 producer→consumer 방향을 혼동하지 않는다. selector의 양방향 constraint propagation은 이 수정 범위가 아니다.
- synchronous revision semantics와 closure 종료 조건 유지. 단방향 구현을 빌미로 후보 삭제·조기 수렴을 도입하지 않는다.

선행 테스트: `A→B→D, A→C`에서 B만 변경 시 A/C 재방문 없음; producer 변경 시 모든 consumer 처리; diamond, grounded/ungrounded cycle, CFG loop backedge, function input/output, alias, source 삭제·추가, authority 교체. `NeutralPlacementFixedPointCompositionTest:217–238`의 full 대조를 확장하되 독립 작은 oracle도 사용한다.

통과: full recomputation과 decoded 관계·ordered receipt·고정점 일치, fixture에서 방문 row 수 실제 감소, 후보 수만 같다는 기준으로 대체하지 않음. GLM 한 번으로 평가.

### G3. 불변 topology와 candidate-dependent 인덱스의 증분 갱신

대상: builder `DirectBindingIndex:2692–2720`, `bindDirectNativeCandidateRealizations:2724–2748`, `NativePlacementContinuity.nextRevision`.

- 이미 구현된 불변 인덱스 재사용을 유지하고 nativeByParent/nativeRealizations/executableReferences를 변경 producer row 단위로 갱신한다.
- 삭제와 중복 key 처리, insertion/canonical 순서, reference/owner identity를 유지한다. 동일 normalized signature가 여러 owner에서 쓰이면 단일 삭제로 다른 owner의 membership을 지우지 않도록 기존 소유 단위로 관리한다.
- 갱신 도중 일부 새 row와 일부 옛 row가 섞여 관측되지 않게 revision publication 경계를 유지한다. 전체 두 revision의 깊은 복제 대신 변경 row의 최소 전후 정보만 보관한다.
- G2에서 쓰는 의존 인덱스도 같은 epoch에서 재사용한다. 매 pass 전체 support를 훑는 비용을 남겨 놓고 O(Δ)라고 주장하지 않는다.

통과: full index rebuild와 순서/내용/소유권 일치; 삭제/중복/다중consumer 검사 통과; 변경 producer 수에 비례한 index update 계수 확인; GLM 1회.

### G4. physical rebuild 이후 무변경 fact와 proof 재사용

대상: builder `:2628–2679`, `LogicalBoundaryRealizations.close/bind:199–251`, `NativePlacementContinuity.nextRevision:234–279`, support footprint `:634–637`, SCC grounding `:794–816`.

- physical rebuild가 기존 binding을 버리는 이유를 유지하며, 입력 revision/owner/geometry가 불변임이 확인된 영역만 재사용한다.
- 완료 proof가 읽은 occurrence-domain, 실패/empty lookup, node geometry/range, privacy, capability, source relation과 publication authority를 빠짐없이 invalidation 대상으로 삼는다. topology 동일만으로 public proof 재사용 금지.
- 기존 root-independent support cache와 SCC 결과의 재사용 범위를 확장한다. query별 pinning이 다른 결과는 섞지 않는다. 공개 receipt owner는 원래 계약대로 새로 생성하거나 유효성을 검증한다.
- 영향받은 실제 순환 영역 안에서만 재수렴하고, SCC 외부에는 출력이 달라질 때 전파한다. query마다 다른 proof graph를 하나의 공통 SCC라고 가정하지 않는다.
- 의존성 완전성을 확인할 수 없는 부분은 full recomputation을 유지하고 해당 하위 항목을 미완료로 남긴다.

통과: cycle seed 변경/철회, 함수 boundary 변경, metadata/authority-only 변경, 다른 query pinning의 대조 테스트 통과; 공간·receipt 순서 동일; bounded cache 및 GLM 1회. 기존 O1–O3의 별도 미채택 상태도 함께 재평가한다.

### G5. 실제 남은 generic branch가 있을 때만 상수 전파 보강

대상: `DMLTranslator.java:300–320`, `IPAPassPropagateReplaceLiterals.java:60–92`, `FunctionCallSizeInfo.java:266–290`, `RewriteRemoveUnnecessaryBranches.java:49–76`.

- G1에서 확인한 compiled graph에 dfam=2/link=2/icpt=0 등으로 실행 불가능한 분기가 실제 남을 때만 진행한다. 이미 제거됐으면 ‘검토 완료·변경 불필요’로 기록.
- 기존 IPA에 빠진 전달/반복 경계를 수정하는 방법을 우선한다. 여러 callsite의 인자가 다르면 전역 상수화하지 않는다. 무제한 함수 복제·전면 인라이닝·loop unrolling은 범위 밖이다.
- 상수로 입증된 실행 불가능 경로만 제거한다. 모든 runtime-reachable 경로의 합법적 physical 후보는 유지한다. `link=2`도 필요한 Inf/NaN 처리·데이터 의존 조건은 보존한다.

통과: 다른 인자 callsite, literal/variable 인자, Inf/NaN 의미와 보호 데이터 placement 테스트 통과. compiled branch 변화와 근거 기록. 변경이 있으면 GLM 1회.

### G6. 입력 support product의 압축·지연 소비

대상: `NativePlacementContinuity.java:593–628,776–791`, `PlacementAnalysis`의 support/receipt 관계 및 DP-local 소비 경로. 기존 relation/receipt rank 구현을 먼저 사용한다.

- 입력별 대안 수 k_i의 product를 매 revision `∏k_i`개 객체로 만들지 않고, 정확한 OR/AND 관계와 provenance로 유지한다.
- 공유 producer·action·authority 제약을 잃은 단순 독립 Cartesian product로 바꾸지 않는다. 소비 시 기존 결합 일관성 검사를 유지한다.
- DP 모델 구축→선택→검증→receipt/rank까지 지연 관계를 전달한다. 첫 단계에서 압축하고 바로 다음 단계에서 전체 펼치면 완료가 아니다.
- 기존 lexical order, duplicate detection, selector tie-break 및 owner identity를 보존한다. handle allocation order를 정렬 기준으로 쓰지 않는다. 다른 selector에 넘기는 공통 공간을 축소하지 않는다.
- 완전한 열거를 요구하는 소비자가 있으면 정확한 iterator로 제공한다. 압축으로 모든 문제의 지수 복잡도가 사라진다고 주장하지 않는다. 후보 상한/beam/top-k로 목표를 맞추지 않는다.

통과: 작은 독립 full enumeration과 decode missing/extra=0, 반대 목적의 두 테스트 selector가 각자 기대 winner 선택, 공유 입력/action/순서 반례 통과; leaf 객체 생성/메모리 감소; GLM 1회. 성능상 불필요하더라도 검토 결과를 명시하며 미구현을 구현 완료로 바꾸지 않는다.

## 5. 빠른 feedback과 60초 초과 시 대응

1. G0의 최소 경계를 정한 즉시 G1. G1 fixture 분석과 G2의 의존성 읽기 감사는 독립적으로 진행 가능하나 같은 파일 동시 편집·동시 Maven 실행은 금지.
2. 일반 개발 상한 120초. 이미 알려진 정상성 확인에 더 긴 시간이 필요하면 한 번의 명시적 진단 상한(기본 240초)을 사용하고 성능 채택 표본과 구분한다. timeout만 계속 반복하지 않는다.
3. correctness 실패 시 benchmark 확대 대신 작은 반례로 복귀한다. 실패/timeout 시간으로 가속률을 계산하지 않는다.
4. 정상 완료 후 60초를 넘으면 가장 큰 **배타적** 구간에 따라 다음 미완료 묶음을 선택한다: 많은 방문/rebuild는 G2–G4, 실제 불필요 branch는 G5, product materialization은 G6. 진단 없이는 새 범용 cache를 계속 추가하지 않는다.
5. selector가 병목으로 드러나면 동일 목적함수·순서 계약 안에서 기존 인덱스/지연 관계 소비를 개선한다. 새로운 목적함수 pruning은 추가하지 않는다. 불가피한 탐색량이면 입증된 잔여 비용과 범위를 보고한다.
6. 채택은 성공한 B_correct/C의 동일 경계 비교로 판단한다. 기존 기준처럼 단일 관측 5% 이상 감소를 우선하고 미세 차이는 보류한다. 전체 초기 planning과 CandidateE2E 악화 없음, memory +5% 이내가 필요하다. 단독 이득이 불분명하지만 통합에 필요한 변경은 의존성을 밝히고 통합 묶음으로 평가한다.
7. 목표 충족 시 불필요한 benchmark 반복은 멈추되, G0–G6 각 항목의 구현/검증/채택/불필요/미완료 상태표는 완성한다. 필수 정확성 미완료를 60초 도달로 덮지 않는다.

## 6. 최종 검증과 산출물

- 관련 테스트: `NeutralPlacementFixedPointCompositionTest`, `NativePlacementContinuityTest`, `PlacementStructuralArenaTest`, `CandidateRealizationCanonicalizationTest`, `ProductionDecodedPlanSpaceCompletenessTest`, `IndependentPlanSpaceGenerationCompletenessTest`, protected privacy/boundary 테스트 및 새 작은 oracle. PUBLIC source fixture는 repo 정책대로 제외하고 제외 수를 보고한다.
- targeted tests → Maven package → `git diff --check` → 프로젝트에 정의된 관련 lint/static checks. 없는/실행하지 않은 검사는 미실행으로 기록. Maven 동시 실행 금지.
- 최종 JAR로 GLM DP-local 1회. 같은 JAR·조건의 직전 성공 실행은 재사용한다. DP-local 성공 후 heuristic·DP-global 각 1회로 공통 공간/receipt 회귀를 확인하되 이 계획의 우선 60초 수용 대상은 DP-local이다.
- P1/P2/SLICELINE은 지원되는 `run_LAN_docker.sh` planning-only 경로에서 DP-local 각 1회. 미지원 workload는 최소 명시적 validation 확장 또는 blocker로 남기고 직접 Docker 실행을 공식 완료 증거로 대체하지 않는다. PUBLIC metadata release 옵션은 해당 workload의 기존 승인된 spec에만 적용하고 GLM 해결책으로 사용하지 않는다.
- 각 결과 행: source/patch/JAR/input/config SHA, 실행 ID, T_initial, CandidateE2E exclusive phases, peak memory/측정법, receipt/runtime flag, 성공/오류/timeout, 정확성 결과.
- 보고서에 **정확성 수정 / 채택 최적화 / 제외 변경 / 미완료 의무**를 따로 기록. 세션 이슈 문서 누적. 작성 당시에는 계획만 수립했으며, 이후 사용자 요청으로 실행했다. 결과는 위 실행 보고서가 우선한다.

## 7. 주요 위험과 중단·대응 조건

| 위험 | 대응 및 검증 |
|---|---|
| Y_prob를 실제 infeasible로 오진 | protected 최소 반례와 source/reader relation oracle. timeout/같은 오류만으로 판정 금지. |
| 단방향 dirty에서 숨은 의존성 누락 | mutable read-set 목록, negative lookup·before/after edge·metadata invalidation, full 대조. |
| loop 순서 변경으로 잘못된 고정점 | seed/철회/cycle 테스트, synchronous 경계 유지, 기존 SCC grounding 의미 보존. |
| 서로 다른 authority를 캐시로 혼합 | owner identity와 epoch 포함, query pinning 격리, rank/receipt 테스트. |
| 압축이 합법적 조합을 없애거나 불법 조합을 추가 | 독립 decode oracle·공유 producer/action 반례, counterexample 시 기존 정확 경로 유지. |
| 성공 baseline 없이 ‘개선’ 주장 | B_fail/B_correct/C 구분. success와 전체 경계가 없으면 가속률·60초 완료 판정 금지. |
| 미완료 최적화를 일부 구현으로 완료 처리 | G0–G6 하위 항목 상태표와 검증 증거 연결. 위험한 재사용은 보류하되 남은 작업을 숨기지 않음. |

**종료 기준:** 정상 DP-local GLM 전체 초기 planning 60초 미만, 공간/선택/authority 검증, 메모리 기준과 최종 회귀 확인을 충족하면 목표 달성. 입증된 infeasibility·실험 경로/자원 제약 등으로 완료할 수 없으면 구체적인 blocker와 미완료 항목을 보고하고 성공으로 표시하지 않는다.
