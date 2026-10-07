# LogReg common 분석 잔여 병목 — 2026-10-07

## 질문과 검증 범위

현재 LogReg 공통 분석이 왜 약40초 걸리는가를 조사했다. 코드 기준은 `2faff2a2a5`다. production 소스는 수정하지 않았다. 기존 `SearchSpaceMetrics`를 켜고 snapshot을 출력하는 DMLTranslator 진단 사본만 별도 컴파일했다. `diagnostic.patch`에 전체 차이를 남겼다.

실제 builtin multiLogReg, X192×8, PRIVATE_AGGREGATE, ROW worker3개, local public label, numclasses3, maxi10/maxii5, 동일 Docker4CPU/8GiB로 실행했다. `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e --case ml_logreg --profile-jfr`를 사용했다. 다른 담당자의 실행은 중단하지 않았다.

직전 비계측 게시본은 common39.677초였다. 이번 진단은46.332초이며 계측/JFR 및 공유 호스트 상태의 영향이 있으므로 둘의 차이를 회귀나 계측 오버헤드의 확정값으로 해석하지 않는다. 아래는 **동일 진단 실행 내부의 병목 분해**다.

## 계측으로 확인한 순위 — 신뢰도 높음

시간은 하위 phase를 제외한 exclusive wall time이다. 아래 모든 phase의 exclusive 합계가 전체 analysis46.331735590초와 정확히 같다.

| 순위 | phase | 시간(s) | 비중 | phase 호출 수 |
|---|---|---:|---:|---:|
|1|CFG_REPLAY|11.309|24.41%|97|
|2|CLOSURE_REPLAY|7.577|16.35%|12|
|3|PHYSICAL_REBUILD|7.135|15.40%|106|
|4|DIRECT_BINDING|6.197|13.38%|1,869|

상위4개 합계는69.54%다. 나머지는 proof topology2.594초, receipt preparation1.906초, materialization1.827초, 분석 본체1.374초, proof overlay1.206초 등이다. phase 호출 수는 전체 graph를 해당 횟수만큼 완전히 재생성했다는 뜻이 아니다. 예를 들어 physical phase에는 changedOrdinals가 없어서 곧바로 반환한 호출도 포함될 수 있다.

전체 common의 current-thread CPU는44.455초, 누적 할당은14.571GB다. JFR에서 common 호출 스택을 포함하는 CPU 표본1,260개와 가중 할당14.532GB를 확인했다. common 표본의 첫/마지막 시각으로 근사한 GC pause 합계는1.109초다. 누적 할당은 peak heap이 아니며 GC 시간이 CPU/wall과 독립적으로 더해지는 것도 아니다. 이 실행은 주로 CPU 계산 비용으로 설명된다.

## 1. CFG replay: reader의 exact 후보·증명·표현을 다시 구성

**확인된 코드 경로:** `PlacementRelationClosure.java:5103`의 replay가 occurrences를 돌며 source facts와 prior logical inputs를 조사한다. `5364`의 `exactTransientReplay`는 source·layout별 정확한 compatibility를 만들고, `5516`에서 VALUE_MAP을 ROW/COL/FULL/BROADCAST별로 구성한다. `6005`부터 reader facts·source/reader compatibility·logical inputs·node를 다시 만든다. composed round 안에서 replay가 첫 번째와 마지막에 호출된다(`3622`, `3773`). 두 번째 replay는 direct grounding 이후 identity와 all-definition 관계를 반영하므로 단순 삭제할 수 없다.

**JFR 근거:** CFG CPU172표본 중 `exactTransientReplay`를 포함하는 표본99개다. CFG 가중 할당은5.004GB이며 이 중 exact replay2.816GB, canonical ordering key1.659GB, value-map alternative0.879GB가 관측됐다. 이 값들은 중첩된 inclusive stack 집계여서 합산하지 않는다.

이전에 제거한 거대 compatibility 문자열 재생성이 다시 생긴 것은 아니다. 현재는 새 replay 객체의 segmented canonical text를 구성하는 비용이 남는다. CFG app-leaf allocation에서 `CanonicalTextBuilder.appendFields`0.536GB, `CanonicalText.<init>`0.472GB, `logicalTransientReplayFact`0.444GB가 관측됐다. 마지막 메서드(`6059`)의 capability detail에는 전체 reader realization signature 목록을 여전히 문자열로 만든다.

또한 `PlacementJointInputAnalysis.Environment.stableKey`가 CFG app-leaf allocation0.680GB다. key는 Environment별로 저장되지만 `with/observe`가 새 Environment와 TreeMap을 만들고 전체 variable/read definition을 다시 직렬화한다(`PlacementJointInputAnalysis.java:110`, `117`, `122`, `140`). 동일 Environment에서 매번 key를 재계산한다고 표현하면 부정확하다.

## 2. Closure 자체: revision/index 준비와 수렴 확인 비용

CFG/direct/physical의 nested phase를 제외하고도7.577초가 든다. `closeCfgTransientCandidateDependenciesMeasured`가 node/reaching/compiled-edge/direct 인덱스를 준비하고, direct components·value-map boundary를 닫은 뒤 node/domain/fact/logical input 전체 동치로 수렴을 확인한다(`PlacementRelationClosure.java:3502`, `3559`, `3800`).

JFR에서 세 하위 경로를 제외한 closure 표본358개 중 `closeDirectComponents`162개, `PlacementDependencyComponents.<init>`51개, `NativePlacementContinuity.<init>`40개, `nextRevisionInternal`30개가 포함됐다. 이 그룹은 JFR 스택 기준이며 측정 phase의 정확한 exclusive 경계와 동일하지 않다. 의존성 component 구성, revision map 갱신, 깊은 equality 비교가 반복된다는 코드 경로를 뒷받침한다.

## 3. Physical rebuild: generator보다 generation-envelope 정규화·resolver 비용이 큼

7.135초의 phase에서 CPU312표본 중 `normalizePhysicalGenerationEnvelope`146개, `WorkerPoolAnchorResolver.<init>`95개, `PhysicalCandidateState.singlePartitionProofs`87개가 포함됐다. 공통 분석 전체에서 `buildNode`를 포함하는 CPU 표본은14개였다. 이 표본 수를 시간으로 환산하거나 서로 합산하지 않는다.

owner commit(`PlacementRelationClosure.java:6143`)은 기존 proof inventory의 owner revision을 갱신한다. resolver delta constructor(`10648`)는 구조 인덱스를 공유하지만 candidate owner map·realization TreeMap·signature owner map을 복사한다. query 준비(`10749`)는 memo를 비우고 `freshQueryState()`를 만든다. NativePlacementContinuity constructor는 candidate-facts map을 새 IdentityHashMap으로 감싼다(`NativePlacementContinuity.java:278`, `3531`). 따라서 **전체 resolver를 매번 cold rebuild하는 비용**으로 단정하지 않고, **delta revision에서도 남는 map 복사와 query-state 생성**으로 구분한다.

새 single-partition proof index도 영향을 받은 cone의 fixed point 계산은 계속 수행한다. 이전에 제거한 매 commit 전체 inventory 재구축을 현재 원인으로 다시 지목하지 않는다. Derived-FOUT identity의 action signature SHA-256·anchor/proof/fact 생성(`PlacementRelationClosure.java:8489`)도 남은 비용이다.

## 4. Direct binding: 이미 delta/memo가 있지만 결과가 같은 wave 계산이 많음

6.197초의 direct binding 외에 그 하위 proof phase가 따로 있다. 이번 분석에서 direct component wave1,869회 중1,676회(89.67%)가 boundary closure 적용 후 변경 owner가 없었다. dirty fact 방문76,225회, 재사용 fact 방문1,125,396회다. 재사용은 이미 작동하고 있다.

**중요한 해석 경계:** stable 판정은 selected facts를 다시 bind하고 `boundarySession.close`까지 실행한 뒤의 결과다(`PlacementRelationClosure.java:3926`). 89.67%를 사전에 생략 가능하거나 그만큼 빨라진다는 뜻으로 쓰지 않는다.

public proof memo는34,311 hit/30,822 miss, topology는304,958 hit/10,417 build다. 실제 support proof graph 구성은17,214회다. `exactContextRepeatedQueries=0`은 memo miss 뒤 계산에 들어온 요청을 resolver revision별 observer로 본 수치다. 전체 분석에서 동일 작업이 반복되지 않았다는 뜻이 아니다(`NativePlacementContinuity.java:247`, `884`, `1104`, `1167`).

Direct CPU230표본 중 realization merge89개, canonical clause-run merge68개가 포함됐다. 후보를 반환할 때 proof clauses를 합치고 구조적 동등성을 비교하는 비용도 남아 있다.

## 현재 근거로 낮아진 설명과 아직 모르는 것

- privacy closure 자체는0.212초다. privacy 규칙을 완화할 이유가 없다.
- VALUE_MAP의 `enumerateGroundedReplayProducts`는 공통 JFR CPU 표본0개, 가중 할당약0.001GB다. sampling은 비용0의 증명이 아니지만, 이 실행에서 큰 병목이라는 근거는 없다. value-map 전체0.879GB 비용과 product list 자체를 혼동하지 않는다.
- DP 최적화는 common 분석 뒤에 실행된다. 이번 common46초를 DP factor merge나 학습 iteration 시간으로 설명하지 않는다.
- GC pause가 주된 지연 원인이라는 근거는 약하다. 객체를 만들고 hash/sort/compare하는 CPU 비용은 별개로 크다.
- 반복 계산을 모두 없앨 수 있다는 증명은 없다. source/witness가 같아도 node domain, realization/support, reaching definition, loop seed, privacy가 바뀌면 proof 답이 달라질 수 있다.
- StepLM/GLM 및 다른 입력 크기에 대한 병목 비율은 이번 실행으로 일반화하지 않는다. 신규 구현과 속도 개선 ablation은 이번 조사 범위 밖이다.

## 근거에 따른 다음 수정 후보 — 아직 구현하지 않음

우선순위는 (1) reader 입력 revision이 동일함을 증명한 CFG replay 결과 재사용과 joint Environment의 불변 갱신 재사용, (2) physical owner-delta/query-state의 공유 가능한 인덱스·map 복사 축소, (3) direct/closure의 불변 의존성 인덱스와 canonical clause 표현 재사용이다. 이는 측정된 호출 경로에 근거한 후보이며 예상 절감률은 아직 없다. source lookup 인덱스도 좁은 개선 후보지만 이번 JFR상 남은 전체 시간의 주원인으로 과장하지 않는다.

## 검증·재현

실제 학습 PASS, CP/FED16계수 최대 절대 오차2.22e-16, audit/conversion 위반0. 게시본 비계측 실행과 analysis fingerprint 및 시간 제외 DP checkpoint1,224개가 같다. 진단 overlay와 현재 main 소스 차이는 DMLTranslator의 metrics 연결/출력뿐이다. production 수정0.

원자료 root: `/grid/3/cofee-lm-sweep-mchoi-20260914/logreg-common-profile-20261007`.
- `command.json`, `compile-command.json`, `diagnostic.patch`, `source-commit.txt`: 명령과 계측 범위.
- `phase-metrics.json`: 정확한 phase 시간·CPU·누적 할당·횟수.
- `jfr-summary.json`, `targeted-jfr-attribution.json`, `selected-allocation-callers.json`: sampled stack 집계.
- `jfr-compact.jsonl.gz`: common sample의 method/line 압축 원자료.
- `validation.json`: 학습·audit·게시본 checkpoint 동치 확인.
- `logreg-common-2faff-metrics-jfr/cases/ml_logreg/fed.jfr`: 원본 JFR.

JFR JSON을 재생성하려면 `jfr print --json --stack-depth 256 --events jdk.ExecutionSample,jdk.ObjectAllocationSample,jdk.GarbageCollection,jdk.GCPhasePause <fed.jfr> > jfr-events.json`을 사용한 뒤 `summarize_jfr.py`를 실행한다. 1.3GB의 중간 JSON은 원본 JFR와 압축 sample/집계 보존 후 공간 절약을 위해 제거했다.


## L2SVM과 동일 조건 비교 — 1초 구간 확인 및 현재 코드 재실행

### 이전 1초대의 정확한 구간

앞서 사용한 `boundary-seed-ml-20261006/joint-alias-candidate-01/cases/ml_l2svm/fed.log`에서:

- common `analysis_begin/end`: **10.526747129초**.
- `TARGET_REACHED.plannerElapsedNanos`: **1.846755955초**.
- optimizer: **0.994523234초**.
- 전체 planner:2.296218445초, 전체 컴파일14.022254초, 실제 학습1.072초.

따라서 해당 1초대는 common이 아니다. 최근 같은 builtin L2SVM fixture의9개 기록에서 common은7.028–17.759초였다. 이9회는 여러 revision/실행 조건의 과거 기록이므로 현재 코드 성능 추정이나 반복 실험으로 묶지 않는다. 다른 fixture에서 common1초가 나올 가능성까지 부정하지 않는다.

### 현재 같은 코드·동일 실행 묶음의 결과

`l2svm-logreg-common-2faff-pair`에서 두 실제 builtin을 순차 실행했다. main/test/dependencies와 Docker image를 한 번 동결해 공유했고, X192×8·PRIVATE_AGGREGATE·ROW3·공개 local label·outer10/inner5 조건이다. LogReg는3 classes, L2SVM은binary labels다. 둘 다 metrics/JFR을 켰다.

| 항목 | L2SVM | LogReg | LogReg/L2SVM |
|---|---:|---:|---:|
|common phase marker(s)|8.497|34.769|4.09×|
|전체 planner(s)|2.308|10.869|4.71×|
|전체 컴파일(s)|11.828|46.647|3.94×|
|실제 학습(s)|1.973|2.147|1.09×|
|감사 기록의 compiled occurrences|252|524|2.08×|
|행렬 TRead 지점|21|59|2.81×|
|행렬 TWrite 지점|13|45|3.46×|
|Fixed-point passes|11|13|1.18×|
|실제 support proof 계산|4,509|17,214|3.82×|
|누적 proof states 생성|53,811|340,648|6.33×|
|누적 proof alternatives 생성|83,107|658,249|7.92×|
|누적 proof dependency edges 생성|74,481|738,448|9.91×|
|canonical 비교 횟수|178,414|1,639,278|9.19×|
|누적 할당(GB)|2.717|14.529|5.35×|

Proof states/alternatives/edges는 여러 query와 revision에서 생성한 **누적 작업량**이며 최종 합법 후보의 고유 개수가 아니다. Fixed-point passes도 실제 학습 iteration 수와 다르다.

| Exclusive common 구간 | L2SVM(s) | LogReg(s) |
|---|---:|---:|
|CFG_REPLAY|1.463|8.472|
|CLOSURE_REPLAY|1.748|5.669|
|PHYSICAL_REBUILD|1.567|5.048|
|DIRECT_BINDING|0.592|4.556|
|PROOF_TOPOLOGY|0.316|1.817|

### 왜 LogReg의 작업량이 더 커지는가

**소스에서 확인:** 둘 다 중첩 반복을 사용한다. L2SVM의 outer CG/inner line search는 행렬 상태를 직선형으로 갱신한다. inner 계산과 outer `w/Xw/s/g_old` 갱신은 한 경로로 실행된다(`scripts/builtin/l2svm.dml:96`, `104`, `117`). fixture에서 verbose는FALSE다.

multiLogReg는 inner trust-boundary 조건의 양쪽에서 S/R을 다른 식으로 갱신하고, V는 한쪽에서만 갱신한다(`scripts/builtin/multiLogReg.dml:207`). outer acceptance 조건에서는 B/P/Grad를 갱신하거나 이전 값으로 유지한다(`284`). 이 분기 결과가 loop backedge로 돌아오므로 다음 reader의 가능한 정의와 지원 증명이 더 복잡해진다. 확률/class 행렬 P/P_new/Q/HV/LT와 그 연산 관계도 추가된다(`197`, `235`, `245`). 두 builtin의 helper/multi-output 함수 호출 차이는 없다.

**수치와 코드에 따른 추론:** 노드 수는2.08배, fixed-point pass는11→13에 그치지만 proof 대안 생성은7.92배, 의존 edge 생성은9.91배, canonical 비교는9.19배다. 따라서 이번 차이를 단순한 pass 횟수 증가로 설명할 수 없다. 분기 합류와 loop-carried 상태가 더 많은 물리 배치/지원 관계를 만들고, 현재 closure의 반복 replay·revision·정규화·비교가 그 구조 차이를 확대하는 설명이 계측과 일치한다. 각 분기의 인과 기여율은 branch ablation 없이 확정하지 않는다.

이를 실제 학습이4배 무겁다는 뜻으로 해석하지 않는다. 이 실행의 학습 시간은L2SVM1.973초/LogReg2.147초였고, 차이가 크게 난 것은 common 분석이다. 구조 차이가 있다는 이유로34–40초가 불가피하다고 주장하지 않는다.

### 비교의 검증과 한계

두 workload 실제 학습 PASS. L2SVM CP/FED8계수 max error8.41e-17, LogReg16계수 max error2.22e-16. audit/conversion 위반0. LogReg의 이전 진단과 이번 진단은 시간 제외1,224 checkpoints가 같다. production 수정0.

이전 LogReg 단독 계측46.332초와 이번34.769초는 코드와 누적 주요 작업량이 같은데도 달랐다. 공유 호스트/실행 변동이 있으므로 이를 신규 최적화의 개선으로 보고하지 않는다. 이번 pair는 원인 비교를 위한1회 계측이며 안정적인 시간 비율의 통계적 추정이 아니다.

같은 원자료 root의 `paired-command.json`, `paired-comparison.json`, `paired-validation.json` 및 `l2svm-logreg-common-2faff-pair/`에 로그·audit·JFR·동결 source/classes가 있다.
