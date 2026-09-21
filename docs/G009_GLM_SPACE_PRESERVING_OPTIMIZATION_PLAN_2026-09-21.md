# G009 GLM 후보 공간 보존형 비교·정렬·재구성 최적화 계획

- 작성일: 2026-09-21
- 상태: **실행·단일 진단 실험 수행. 완료/채택 판정은 [실행 보고서](G009_GLM_SPACE_PRESERVING_OPTIMIZATION_EXECUTION_REPORT_2026-09-21.md) 참조. GLM planning 성공 및 최종 성능 채택은 미완료.**
- 선행 근거: [단일 실행 병목 진단](G009_GLM_PLANNING_BOTTLENECK_DIAGNOSIS_2026-09-21.md)
- 상위 계약: [selector 독립 search space 보존 계획](G009_SELECTOR_INDEPENDENT_SEARCH_SPACE_PLAN_2026-09-21.md). 해당 계획의 미완료 oracle/pruning 증명 의무를 취소하거나 완료로 바꾸지 않는다.
- 구현 기준: HEAD a6281207 + 현재 미커밋 수정, JAR `8f10f1891f3cd99bd492b1ad5ac4913aff054fe74afd2d1f16370a0acc4833df`. 시작 시 다시 확인하고 정확한 patch/JAR/설정 manifest를 보존한다.

## 1. 목표와 비목표

**목표:** 모든 합법적인 후보와 결합 관계를 보존하면서 GLM의 공통 analysis 생성 비용을 줄이고, 마지막에는 selector 탐색 시간까지 분리해 planning 전체를 비교한다. 가장 먼저 깊은 구조 비교·canonical 정렬·동일 표현 재생성을 줄인다.

- 60초는 최종 동일 조건 초기 planning E2E의 도전 목표이지, 정확성 대체 기준이나 보장된 예측이 아니다.
- 후보 수가 아니라 decoded joint plan space, authority, 기존 순서와 selector 선택 계약을 보존한다.
- 새로운 비용 dominance, 후보 상한, 임의 hop/action 삭제, privacy 완화, runtime fallback, 새 의존성은 도입하지 않는다.
- worker=4는 federated 배치 조건이다. coordinator 후보 생성을 네 개 worker에 분산한다는 뜻이 아니다. 병렬화는 이번 우선 범위 밖이다.
- 모든 아래 작업 묶음은 검토·구현·검증 여부 및 채택/제외/막힘을 기록한다. 일부가 빨라졌다고 나머지를 완료로 표시하지 않는다. 위험한 재사용을 억지로 구현하지 않고 증명 조건이 충족되지 않으면 미완료/보류로 남긴다.

## 2. 증거와 새롭게 확인한 사항

경로 prefix: `src/main/java/org/apache/sysds/`. 행 번호는 현재 작업 트리 기준.

| 근거 | 관측 또는 코드 사실 | 계획에 미치는 영향 |
|---|---|---|
| 진단 보고서, `parser/DMLTranslator.java:389–418` | 93.30초 thread dump에서 최초 analysis binding 중; selector는 그 뒤 호출 | selector pruning보다 builder 우선 |
| JFR main 5,589표본 | 구조적 equals42.1%, canonical text16.9%, proof17.2%, IdentityHashMap13.2%; 서로 겹침 | 합산/Amdahl 예측에 직접 사용하지 않음 |
| 진단 GC log | 약109초 STW2.72초; 메인 CPU90.85초/elapsed93.30초 | 우선 CPU/할당 작업을 줄이고 GC 튜닝은 뒤로 |
| `hops/fedplanner/placement/PlacementIdentity.java:64–99` | 기존 structural arena가 identity miss 시 구조 hash/equals 수행; byStructure cap 이후에도 기존 구조에 해당하는 새 객체를 byIdentity에 추가 가능 | 캐시 추가만으로 해결 안 됨. 재생성 억제와 두 map의 독립 한도 필요 |
| `placement/PlacementAnalysis.java:74–110,238–245` | canonicalization마다 새 context와 정렬 키 생성, 마지막 인접 equals 검사 | 기존 exact ordering 계약 그대로 재사용 경계 확대 |
| `placement/PlacementAnalysis.java:118–160` | 이미 rope형 정렬 표현과 동일 leaf 문자열 skip 존재 | 문자열 캐시/rope를 새로 중복 구현하지 않음. 동일 subtree skip 등을 기존 구조에 보강 |
| `placement/PlacementAnalysis.java:907–949` | 이미 단일 realization fast path 및 일부 기존 realization 재사용 존재 | 부족한 다중 realization/no-change 경로만 개선 |
| `placement/NeutralPlacementGraphBuilder.java:2554–2668,2690–2736,2955` | 중첩 closure, physical rebuild 뒤 rebinding, dirty 처리 전 전역 인덱스 재구성, emission 재생성 | no-change reuse 이후 증분 인덱스/closure 개선 |
| `placement/NativePlacementContinuity.java:234–268,398–412` | 기존 revision topology/support 재사용 존재; public proof는 재생성·정렬 | 기존 캐시의 정확한 재사용 실패 지점 보강, 새 범용 캐시 계층 금지 |

한계: JFR 2,187표본은 스택이 잘렸고, 정확한 pass/고유 clause/재생성 수는 미측정이다. 기존 진단은 worker 지연 시작과 공식 harness의 GLM 미지원 문제가 있어 **정상 준비된 baseline 성능 표본이 아니다.**

## 3. 수학적 보존 계약과 비용 모델

### 3.1 바꾸지 않을 것

입력 I의 기존 표현 R0, 변경 표현 R1, 합법적 공간 S에 대해:

`Decode(R1(I)) = Decode(R0(I))`를 이번 변경의 회귀 계약으로 삼는다.

상위 목표 `Decode(R(I)) = S(I)`는 독립 oracle로 별도로 검증해야 한다. **기존 구현과 같다는 사실만으로 기존 누락이 없음을 증명하지 않는다.**

selector j에 대해서는 입력 불변성과 `Select_j(R1) = Select_j(R0)`를 기존 objective/tie-break/receipt 계약 아래 검사한다. 서로 다른 selector의 winner 일치는 요구하지 않는다. 반대 목적을 가진 작은 테스트 selector 두 개가 서로 다른 합법 대안을 선택할 수 있어야 한다.

구조 handle은 analysis/권한 scope 내부 계산용이다. fingerprint 충돌만으로 동등 판정하지 않으며, 서로 다른 worker/range/authority/support/receipt를 합치지 않는다. allocation order의 정수 handle을 기존 canonical 순서 대신 사용하지 않는다.

### 3.2 실제 비용의 분해

`T_initial = T_frontend + T_common_prepare + T_analysis + T_selector + T_publish_lops`

`T_analysis ≈ Σ_revision [T_index + Σ_visited_fact (T_bind + T_canonical + T_compare) + T_replay]`

여기서 canonicalization의 목록 길이를 m, 비교당 실제 방문하는 중첩 구조/텍스트 크기를 L이라고 하면 정렬은 대략 `O(m log m × L)`이다. 동일 하위 구조를 매번 재생성하면 같은 의미라도 identity fast path를 활용하지 못한다.

- O1/O2: L 및 동일 구조 재처리 횟수 감소.
- O3: 변경 없는 fact의 생성/정렬/비교 자체를 생략.
- O4/O5: revision마다 전체 N을 다시 처리하는 비용을 영향받는 변경분 Δ 중심으로 이동. 항상 O(Δ)가 된다고 보장하지 않는다. 전역 의존성/광범위 변경이면 전체 재계산 허용.
- 최종 병목이 selector로 이동하면 두 시간을 분리해 보고한다. analysis 개선을 전체 planning 개선으로 바꿔 말하지 않는다.

배타적으로 측정한 비용 비율 p를 얻었을 때만 Amdahl 식 `S = 1 / ((1-p)+p/s)`를 사용한다. 현재 inclusive 표본42.1%와16.9%를 더해 예상 가속률을 계산하지 않는다.

## 4. 실행 순서

### O0. 최소 측정/재현 경계 고정 — 큰 계측 프로젝트 금지

**대상:** `CandidateFormationTiming.java:89–99,142–144`, `parser/DMLTranslator.java:389–418`, `SearchSpaceMetrics.java`, 공식 `experiments/run_LAN_docker.sh` 및 workload registry.

1. 기존 timing 경계에 analysis/selector 시작·완료 시점의 opt-in flush만 추가한다. 종료 전에 중단해도 마지막 진입 구간을 확인할 수 있어야 한다. per-candidate 로그는 금지.
2. 기존 SearchSpaceMetrics를 재사용해 closure/revision별 요약만 남긴다: 방문/변경 fact 수, realization/clause 수, 직접 closure pass 수, full/incremental 여부, arena identity/structure entry·hit·overflow, canonicalization 입력량. 값의 의미를 구분하고 고유 clause 전역 계산을 매 pass 추가하지 않는다.
3. timing 자체와 expensive diagnostics를 분리한다. baseline과 candidate는 같은 가벼운 설정으로 실행한다. JFR은 매 실험 반복하지 않는다.
4. GLM을 공식 planning-only harness에 **명시적 별도 진단/벤치 workload 등록**하는 최소 경로를 마련한다. 기존 frozen seven-workload manifest를 변경하거나 다른 이름으로 위장하지 않는다. 실제 학습 호출 없음과 metadata 기반 planning을 확인하고 worker 수4를 receipt에 기록한다. formal planning-only가 worker JVM을 금지하면 그 계약을 유지하며, 실제 workers를 시작하는 다른 경로와 결과를 섞지 않는다.
5. harness 확장이 작은 수정 범위를 넘으면 blocker로 기록한다. 기존 예외 진단 경로는 원인 확인에만 쓰며 formal 수치를 주장하지 않는다. 이 문제가 작은 코드 최적화/회귀 테스트 전체를 막지는 않는다.

**완료:** 고정된 입력 SHA/config/JAR/JVM/worker placement/측정 경계 manifest; runtime 미실행 확인; baseline 한 번. 불필요한 새 대시보드·프로파일 프레임워크 없이 O1로 이동.

### O1. 깊은 구조 비교와 identity cache 압력 감소 — 최우선

**대상:** `PlacementIdentity.java:64–99,1022–1035`, `PlacementAnalysis.java:679–719,907–949`, 실제 profile의 equals 호출 지점.

- 먼저 높은 비용의 immutable support/realization 경로에 한정한다. 전체 record를 일괄 class로 바꾸지 않는다.
- 기존 structural arena를 활용하되 composite 구조의 키를 타입 tag + 정확한 primitive/leaf + 검증된 child structural handle로 만들 수 있는 경로부터 검토한다. 기존 HashMap<Object,...>에 똑같은 복합 객체를 넣는 캐시를 추가하지 않는다.
- child handle은 동일 scope의 완전한 구조 동등성이 확인됐을 때만 사용. authority-sensitive 객체는 owner identity를 키에 포함하고 원본 graph-owned 객체를 유지한다. 지원하지 않는 타입/예산 초과는 기존 exact equals로 돌아간다.
- 기존 인스턴스 또는 같은 scope에서 검증된 구조 handle이 같으면 exact equality fast path를 사용한다. 불일치/unknown의 처리도 정확해야 하며 raw hash 일치만으로 true를 반환하지 않는다.
- byIdentity와 byStructure 모두 별도 entry/메모리 예산을 적용한다. 구조 종류가 적고 동등한 복제 객체만 많아도 identity map이 무제한 커지지 않게 한다. scope 종료/예외에서 정리한다.

**선행 테스트:** 같은 값 다른 객체, 동일 hash 다른 내용, 동일 geometry 다른 authority, 단일 support 차이, arena overflow/재진입/연속 analysis, baseline canonical 결과 일치. 기존 `CandidateRealizationCanonicalizationTest` 확장.

**완료:** small fixture에서 exact 비교와 전부 일치, 충돌 반례 통과, 예산 준수. GLM 한 번에서 시간을 줄였는지 판정. 세부 비교가 개선돼도 전체가 느리면 기본 채택하지 않는다.

### O2. Canonical 정렬 키와 결과 재사용

**대상:** `PlacementAnalysis.java:74–245`, `NativePlacementContinuity.java:398–412,1310–1316`.

- 기존 CanonicalText/SharedCanonicalList/identity context를 확장한다. 새 serializer를 병행 도입하지 않는다.
- 동일 immutable 객체의 ordering text를 analysis-local bounded context에서 재사용한다. 서로 다른 객체의 공유는 O1의 exact structural equivalence가 검증된 경우만 허용한다.
- cursor에서 같은 CanonicalText subtree와 같은 offset이면 subtree 전체를 건너뛰는 fast path를 검토한다. 동일 leaf만 skip하는 기존 경로보다 넓히되 실제 UTF-16 lexical order를 보존한다.
- 정렬된 proof/template을 재사용할 때 root/seed 치환이 비교 순서를 바꾸는지 확인한다. 치환 전 순서가 자동 보존된다고 가정하지 않는다. 증명되지 않으면 재정렬하되 ordering key 생성만 재사용한다.
- 전체 signature를 무제한 문자열로 평탄화하거나 캐시 budget만 늘리는 방식은 제외한다.

**선행 테스트:** 기존 comparator와 sign/order 일치, 긴 공통 prefix, 중첩 subtree, 동일 문자열 다른 구조, length prefix 9/10/99/100, 빈 값·Unicode, insertion order permutation, overflow fallback. ordered receipts와 duplicate rejection 동작 보존.

**완료:** 기존 sort/exception 계약 일치; bounded retention; GLM 한 번으로 canonical 비용과 전체 시간 함께 비교.

### O3. 변경 없는 emission/fact를 다시 만들지 않기

**대상:** `NeutralPlacementGraphBuilder.java:2690–2960`, `PlacementAnalysis.java:907–949`.

- dirty라고 표시돼도 실제 결과가 같으면 원본 emission/fact를 재사용한다. 기존 단일 realization fast path를 중복 추가하지 않는다.
- 생성 후 전체 deep equals를 한 번 더 돌리는 방식이 아니라, 입력 revision/dependency와 canonical child 결과를 근거로 no-change를 판정한다.
- producer가 같아도 support·authority·geometry가 바뀌면 재생성해야 한다. relocation binding은 같은 값처럼 보여도 새 publication owner면 기존 객체를 재사용하지 않는다.
- physical rebuild가 native binding을 제거하는 이유를 유지한다. 근거 없이 과거 binding을 붙잡는 shortcut 금지.

**선행 테스트:** no-op replay의 객체 재사용, 입력 한 개의 support 변경, 후보 삭제, authority 교체, CFG loop backedge/function return 변경, full rebuild 대비 decoded 공간 일치.

**완료:** no-op에서 불필요한 merge/sort 감소; 실제 delta 전파 정확; GLM 한 번에서 O1/O2와 통합 효과 확인.

### O4. Dirty closure에서도 전역 인덱스를 매번 만들지 않기

**대상:** `NeutralPlacementGraphBuilder.java:2554–2668,2690–2736`, `NativePlacementContinuity.java:234–268`.

- occurrence/node topology/compiled input edges가 같은 epoch 안에서만 인덱스를 재사용한다.
- candidate-dependent nativeByParent/realization map은 변경된 producer에 한해 갱신하되, 전체 이전 revision을 보존해 메모리를 두 배 쓰는 방식은 피한다.
- parent→consumer 및 transient/function 관계를 통해 delta를 전파한다. 삭제·합법성 변화도 추가와 동일하게 invalidation한다.
- identity equality를 authority로 사용하는 경계를 structural equality로 바꾸지 않는다.
- full/incremental reference 경로를 작은 테스트에서 대조할 수 있게 기존 옵션/테스트 경로를 활용한다. 새 runtime 정책 플래그는 만들지 않는다.

**완료:** 변경 없는 topology 재구축 횟수 감소; chain/diamond/loop/function/다중consumer/삭제 fixtures에서 full 방식과 동일; GLM 한 번에서 비용 확인.

### O5. Physical rebuild 이후 proof/closure 재사용

**대상:** `NeutralPlacementGraphBuilder.java:2624–2668`, `NativePlacementContinuity.java:234–268,398–412`.

- O1–O4 뒤에도 재-grounding 비용이 크면 기존 topology/support revision cache의 재사용 조건을 보강한다.
- proof의 의존성 서명에는 producer candidate domain, geometry/range, runtime capability, privacy, logical source, owner authority를 포함해야 한다. topology가 같다는 이유만으로 reuse하지 않는다.
- 영향 범위가 작으면 delta만 처리하고, 불확실/넓은 변경에서는 **동일 의미의 full recomputation**을 한다. 이것은 runtime fallback이 아닌 compiler 계산 경로다.
- nested fixed point 전체 재설계는 자동 확대하지 않는다. epoch 경계 변경이 필요하면 불변식과 작은 reference oracle을 먼저 작성한다.

**완료:** cycle/loop seed/함수 입출력/공유 action에 대해 reference와 동일한 고정점, 후보 누락 없음; 시간·메모리 한도 통과. 안전한 dependency key를 만들 수 없으면 O5를 미완료로 명시하고 이전 구현 유지.

## 5. 빠른 feedback 루프와 채택 기준

### 각 묶음의 기본 루프

`작은 회귀 테스트 작성 → 묶음 하나 수정 → 관련 테스트/compile → GLM 1회 → 채택/제외/미확정 기록`

- 이전의 3쌍 pilot/18쌍 ABBA는 이번 개발 루프에 적용하지 않는다. 사용자 요청대로 조건당 한 번을 기본으로 한다.
- baseline은 동일 준비 조건으로 한 번 고정하고, 후보는 마지막 채택 조합과 비교한다. 매번 모든 planner/workload를 다시 돌리지 않는다.
- 첫 GLM은 DP-local만. 재현 실패·입력 오류·계측 오류는 원인 수정 후 실행하며 유효한 성능 표본과 분리한다.
- 기본 planning 관측 상한120초. 중단 시 completed E2E는 `미완료`로 기록하고, setup/cleanup을 planning 시간에 더하지 않는다.
- 둘 다 timeout이면 중간 작업량/heap/마지막 phase로 다음 수정을 고를 수 있지만 전체 가속률과 최종 채택은 미확정이다. 충분한 진전으로 완료가 가까운 근거가 있으면 마지막 비교에만 동일한 확대 상한을 적용한다. 무작정 장시간 재실행하지 않는다.
- 미세한 시간 차이는 노이즈로 취급한다. 운영상 단일 관측5% 이상 감소를 우선 채택 후보로 삼되 통계적 유의성으로 표현하지 않는다. 5% 미만은 기본 채택하지 않고 더 명확한 근거/통합 효과가 있을 때만 재평가한다.
- 정확성 통과, 전체 initial planning 및 CandidateE2E 모두 악화 없음, peak memory baseline 대비5% 이내를 최종 채택 기준으로 한다. 같은 peak 측정법/프로파일 설정을 사용한다. baseline 미완료면 full E2E/peak 기준 충족 여부도 미확정이다.
- correctness-preserving 중간 변경을 실험 branch에 보유하는 것과 production 기본 채택을 구분한다.

### 최종 검증

1. 관련 canonicalization/structural identity/timing/decoded completeness/privacy 테스트, `mvn -DskipTests package`, `git diff --check`, 프로젝트에서 정의된 관련 lint/static 검사. 없는 검사를 실행했다고 보고하지 않는다.
2. GLM worker4 heuristic·DP-global·DP-local 각각1회. DP-local 개발 측정과 완전히 같은 최종 JAR/조건이면 그 성공 결과 재사용 가능. 실제 ML 학습 runtime은 실행하지 않는다.
3. P1/P2/SLICELINE은 공통 builder 변경 영향 확인용: 우선 DP-local 각1회, selector 의미 변경 징후가 있으면 해당 planner만 추가. 전 matrix 자동 반복 금지.
4. 작은 독립 joint oracle에서 missing/extra0 및 반대 목적 selector의 입력 공간 불변을 검사한다. 기존 production 재사용 oracle만으로 전체 증명 완료 선언 금지. PUBLIC 소스 테스트 금지/ignore 등 repo 정책 준수, 가능한 fixture는 protected 합법 대안으로 구성한다.
5. 보고서 표: source/JAR/config/입력, initial planning, common prepare, analysis, selector, publish/LOP, CandidateE2E, peak memory, 성공/timeout/runtime 미실행, decode/receipt 결과, 단일 관측임을 명시.

## 6. 리스크와 차단 조건

| 위험 | 방지·검증 |
|---|---|
| 캐시가 오히려 hash/equals·메모리를 증가 | 기존 cache를 우선 보강; 두 map 예산, bounded fallback, 단일 GLM 확인 |
| fingerprint collision로 후보 병합 | exact 구조 검증/동일 scope handle; 강제 충돌 테스트 |
| 같은 값 다른 authority를 재사용 | owner identity 포함; publication owner 교체 회귀 |
| 정렬 변경으로 selector tie-break 변화 | 기존 comparator 차등 테스트와 ordered receipt 일치 |
| dirty invalidation 누락 | 추가뿐 아니라 삭제·privacy·CFG·function·geometry 변화를 full reference와 비교 |
| no-change 감지가 생성비용보다 비쌈 | child 결과/의존성으로 판정, 전체 deep equals 중복 호출 금지 |
| 상한시간 내 완료만 보고 잘못 가속 주장 | timeout은 censored; completed E2E끼리만 비율 계산 |
| 기존 baseline의 공간 누락까지 보존 | differential 검증과 독립 공간 oracle을 분리; 기존 미완료 의무 유지 |

## 7. 산출물과 중단 기준

- 논리 묶음 O0–O5별 patch/테스트/단일 실험/채택 여부 표.
- 최종 채택 조합과 제외 변경, 미완료 증명 의무를 분리한 실행 보고서.
- 모든 안전한 묶음을 평가하고 최종 검증을 마치면 종료. 60초가 안 되어도 후보 삭제로 맞추지 않으며 남은 병목을 보고한다. 60초가 되어도 미검토 묶음과 correctness 의무를 자동 완료 처리하지 않는다.
- 이 문서는 실행 지시의 세부 계획이다. 실행 중 확인한 미완료 항목과 같은 오류에서 종료한 비교는 위 실행 보고서에 별도로 기록한다.
