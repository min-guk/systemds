# FED Oracle relation-native 구현 및 검증 결과

## 결과 범위

이번 변경은 모든 FED 연산을 end-to-end factorized로 완성한 변경은 아니다. 49개 등록 family를 조사하고 공통 Oracle 의존성 계약을 추가했다. 정확한 source/action authority를 증명한 **scalar WSLOSS/WCEMM**은 입력 tuple별 fact 생성 없이 조건부 relation을 생성하고 Closure의 입력별 binding 표를 보존한다. 나머지 FOUT/source-producing family와 증명하지 못한 joint 관계는 exact 경로를 유지한다.

가장 큰 남은 경계는 Physical Model이다. 새 weighted relation도 현재 FType tuple별 emission/Alternative는 평가한다. 압축 support의 source 선택을 Clause별 Alternative로 펼치는 경로는 줄였지만, 입력 tuple 자체를 DP 변수 제거까지 모두 압축했다는 주장은 할 수 없다.

## 기준선과 작업 보존

- 기준선: HEAD `e468797556e6789100736355ca2463941302221f`와 요청 시작 시점의 미커밋 129개 변경/신규 파일 snapshot. HEAD만 기준으로 삼지 않았다.
- 구현: 독립 weighted, unary/matrix, validation worktree와 root 통합. 동시 실행 한도 4개에 맞춰 inventory/review 담당을 교대로 배치했다.
- 기존 파일과 테스트를 보존했다. 디스크가 가득 찼을 때 checkout 14,189개 파일/링크와 git 상태의 동일성을 확인하고 `/grid`로 이동했으며, 원래 경로는 symlink로 유지한다.
- commit/push는 하지 않았다.
- 대용량 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/evidence/`.

## 전체 Oracle inventory와 설계

49개 family별 caps/profile 의존성, shape/source 경계 및 구현 상태는 [전체 inventory](FEDPLANNER_ORACLE_INVENTORY_2026-10-08_KO.md)에 기록했다. 이 숫자에는 CP-only, alias/함수 경계 및 placeholder가 포함된다. 실제 `mmult`는 `BinaryMMRule`이며 별도 `MMFedRule`과 구분했다.

`DecisionDependencies`는 capability 결정 축, 원본 FType에 의존하는 shape selector 축, 허용되는 shape fact를 구분한다. 기존 forward rule을 호출하고 capability의 reason/detail/notes 및 shape consultation을 보존한다. 미선언 shape fact를 읽으면 실패한다. mapped FType만으로 FULL proof를 캐시하지 않는다.

새 API를 도입했다고 모든 rule에 region을 생성하지 않는다. 실제로 변하는 독립 축이 없는 새 선언은 기존 streaming 경로로 돌아간다. 또한 4,096개 seed 상한을 넘으면 exact streaming으로 돌아간다. 이 상한은 합법 조합을 버리는 pruning이 아니라 표현 선택이다. 기존 shape-independent 선언은 호환성을 위해 유지한다.

CP/LOUT family와 scalar weighted FED relation은 직접 생성한다. `CandidateRuleRelation.MemberEmissions`는 닫힌 입력별 source/action 표로부터 해당 인덱스의 정확한 emission을 복원한다. empty LOCAL support로 필요한 DIRECT/RELOCATION authority를 대체하지 않는다. 기존 no-action LOCAL clause가 합법인 경우에는 그 clause와 정확한 binding 경로를 함께 보존한다. 계약은 [API/구현 계획](FEDPLANNER_ORACLE_NATIVE_API_PLAN_2026-10-08_KO.md), weighted 상세는 [Closure 설계](FEDPLANNER_WEIGHTED_RELATION_NATIVE_CLOSURE_DESIGN_2026-10-08.md)에 있다.

## 실제 생성량: 논리 후보와 객체를 구분

| 검증 범위 | Explicit / 이전 경로 | 새 경로 | 해석 |
|---|---:|---:|---|
| 넓은 weighted generation fixture의 FED exact facts | 686 | 0 | generation에서 tuple fact를 생성하지 않음 |
| 같은 fixture의 전체 논리 입력 조합 | 2,401 | 2,401 | 합법/비합법 정의 또는 논리 공간을 축소한 수치가 아님 |
| 같은 fixture의 CP/FED 표현 | CP 1,715행 + FED 686행 | CP header 5개 + FED region 2개 | 이전 미커밋 구현은 CP만 압축하고 FED 686개를 유지했음 |
| 같은 fixture의 generation Clause 생성 | 완전 explicit 3,087 | 9 | Closure 및 Physical 단계의 객체는 포함하지 않음 |
| 같은 fixture의 Closure action witness | full FED tuple 686 | pair witness 254 | exact fact 생성이 전체 파이프라인에서 0이라는 뜻은 아님 |
| 실제 파싱한 16-member relation의 분석 중 fact 생성 | 270 | 210 | 완전 explicit generation+Closure와 비교 |
| 같은 16-member 분석의 Clause 생성 | 665 | 420 | 논리 조합 16개와 모든 exact authority는 동일 |
| 작은 protected Docker weighted의 분석 중 fact 생성(v1) | 73 | 90 | 작은 domain에서는 witness 비용으로 증가 |
| 같은 protected Docker Clause / indexed handle(v1) | 76 / 6 | 69 / 0 | 저장 객체 감소와 총 fact 감소가 다를 수 있음 |

Constructor 계측은 분석 scope 안의 실제 `CandidateRuleKey`, `CandidateRuleFact`, explicit Clause, indexed support handle 생성 호출을 센다. 최종 저장 크기나 전체 JVM 할당량과 동일하지 않다. Physical 단계의 emission cache/임시 receipt와 scope 밖 객체는 이 표의 constructor counter에 포함되지 않는다. JFR와 cgroup peak를 별도로 확인한다. DP join/projection의 실제 방문량과 support materialization의 모든 호출을 포괄하는 새 end-to-end 계측은 추가하지 않았으므로, 해당 방문량이 줄었다고 주장하지 않는다.

## Source/action/proof와 비용 보존

- DIRECT는 원래 exact source rule/reference를 유지한다. RELOCATION은 최종 action inventory의 정확한 객체/identity와 결합한다.
- 같은 owner를 공유하는 입력은 기존 source-consistent binding product 생성기를 사용한다. 독립 product로 간주해 sparse hole을 채우지 않는다.
- emission은 final source/action Closure 후 게시한다. action witness는 원래 binding 및 source pruning을 거쳐 검증한 뒤 projection한다.
- 일반 DIRECT와 endpoint-only DIRECT를 구분한다. 후자는 기존 의미대로 PRESENT matrix 입력이 하나인 경우에만 허용한다.
- worker pool, layout, privacy, shape proof, source/action/proof를 포함한 전체 exact signature를 **별도 파싱한 explicit generation+Closure**와 비교했다. 새 relation을 펼친 결과끼리만 비교한 것이 아니다.
- Physical Model의 relation Alternative는 소유 region의 exact emission identity를 확인한다. relocation 합법성 검사는 임시 receipt를 평가하지만 fact를 intern하지 않는다. 최종 선택된 member만 `requireExact()`로 복원한다.
- cost model의 공식이나 shared/nonseparable cost 정의는 바꾸지 않았다. compact support의 공동 제약과 기존 exact fallback을 사용한다. unknown/clause-sensitive authority는 명시 경로를 유지한다.
- 16-member 반례에서 최적 비용이 같아도 CP family의 동률 선택이 달랐다. Physical Model의 raw rule ordering과 policy receipt의 length-prefix ordering을 분리하고, explicit physical order로 정렬·복원하도록 수정했다. 기존 authority signature는 dedup/fingerprint용으로 유지한다.

## 검증

### 구현 및 의미 회귀

최종 통합 FedPlanner Java 회귀는 **565개 통과**(118.388초), 기존 ignore 3개다. 동결 engine 위에 변경 소스를 targeted javac로 컴파일한 검증이며 저장소 전체 Maven test suite 실행은 아니다. 아래 실행 결과를 반영한다. 전체 명령, 소스 SHA-256, javac/JUnit 로그는 `experiments/fed-oracle-native-20261008/`에 있다.

- Oracle dependency: small-domain exhaustive capability/proof parity, raw FULL selector, undeclared shape fact 거부, 독립 축 없는 fallback 및 기존 shape-free hint 재사용.
- Weighted Closure: PUBLIC/PRIVATE_AGGREGATE, ROW/COL, dimensions 4/6/8, rank 2/3, local/different-pool/same-owner를 fixed seed로 검사했다. WSLOSS와 WCEMM, 반복 owner, PRIVATE fail-closed, function boundary, dynamic/transient exact fallback을 포함한다.
- 16-member parsed relation: 전체 closed signature 및 action inventory, Local/Exact objective raw bits, selected state, 전체 receipt 비교.
- 기존 correlated support/sparse holes/mixed DIRECT·RELOCATION, joint/shared-cost, function/branch, canonical fingerprint 및 DP 회귀를 함께 실행했다. 각 49개 family에서 새 end-to-end 경로의 randomized/exhaustive 검증을 모두 수행한 것은 아니다.
- Python harness 및 phase parser: 55개 테스트 통과. 누락된 source/proof/input authority가 양쪽에 동시에 존재해도 비교가 통과하지 않도록 fail-closed 검사한다.
- `git diff --check`와 targeted javac를 사용했다. 저장소 전체 Maven checkstyle은 기존 대량 위반으로 clean 증거를 제공하지 못한다.

### 발견하여 수정한 실패

1. weighted 입력 tuple 생략 시 DIRECT/action authority가 사라질 수 있어, 기존 binder와 final inventory 검증을 재사용하는 조건부 표로 변경했다.
2. action witness를 한 축만 바꾸면 다른 anchor 축과의 조합을 놓쳐 pair witness로 보완했다.
3. CP family fixture를 explicit로 변환하면서 원래 family도 남기면 영역이 중첩되는 테스트 bridge를 수정했다.
4. 일부 shape-sensitive/non-gain relation 경로에서 CFG 회귀가 발생했다. 독립 축 이득이 없는 새 선언의 streaming fallback 후 회귀는 통과했다. 이 실패의 모든 내부 인과를 특정했다고 주장하지 않는다.
5. 실제 16-member 테스트에서 비용/배치는 같아도 CP receipt tuple이 달랐다. 표현별 정렬 키를 exact physical 순서에 맞춰 수정했다.
6. GLM 초기 실행 하나는 디스크 부족으로 Java 시작 전에 실패했다. isolated harness HOME/TMPDIR를 evidence mount로 옮긴 동일 조건 paired 재실행을 사용했다. 인프라 실패 로그도 보존한다.

## 소규모 LogReg/GLM 회귀 측정 — COFEE 대규모 성능 결과 아님

입력은 기존 harness의 **192×8**, ROW 3분할(각 64×8), worker 3개다. 실제 `multiLogReg`(maxi=10,maxii=5,3 classes)와 `glm`(moi=5,mii=5) DML을 호출했다. 대규모 학습 데이터 크기의 별도 실행 결과는 아니다. 동일 seed 7, Docker 4 CPUs/8 GiB, coordinator heap 3g/worker heap 1g, 동일 workload/input/cost profile을 사용한다. Docker workload는 Local planner로 실행하며 Exact/Global 동등성은 별도 Java fixture에서 검증한다. 전체 실제 workload를 Global로 반복 실행한 결과는 아니다. B→C / C→B를 교차하는 3 paired runs를 사용하며 JFR run은 timing run과 분리한다. cgroup peak는 coordinator만의 heap이 아니라 해당 컨테이너 전체 peak다. 단계별 peak 메모리는 별도 측정되지 않았으며 JFR sampled allocation도 live heap peak와 다르다.

첫 구현(v1)의 LogReg 3회는 정확성 비교가 모두 통과했지만 compilation 중앙 paired ratio는 **1.0732**, 즉 약 7.3% 느렸다. Oracle 호출 7,989, facts 55,207, Alternative 5,295는 그대로였고 region 진단만 203→1,972로 늘었다. 이 결과를 개선으로 보고하지 않는다. GLM v1 1회도 호출/객체 수는 그대로이고 region 50→1,002로 증가했다. 이에 새 선언에서 변하는 독립 축이 없는 relation 생성과 불필요한 full shape hint 생성을 제거한 v2를 다시 측정한다.

### v2 소규모 회귀: LogReg/GLM 각각 3 paired runs

모든 쌍의 합법 관계, objective bits, 선택 상태/authority/receipt, relocation/materialization 비교가 통과했다. FED numeric output은 LogReg 16개 값과 GLM 8개 값 모두 변경 전후 double bits가 같았다.

아래 시간은 각 variant의 3회 중앙값(초)이다. C/B는 **쌍별 비율의 중앙값**이므로 두 중앙값의 나눗셈과 다를 수 있다.

| Workload / 단계 | Baseline (s) | Candidate (s) | Paired C/B |
|---|---:|---:|---:|
| LogReg / 전체 compilation | 25.087 | 24.627 | 0.9759 |
| LogReg / PlacementAnalysis | 14.397 | 14.353 | 0.9970 |
| LogReg / Physical Model | 0.512 | 0.399 | 0.7897 |
| LogReg / Cost Surface | 0.701 | 0.556 | 0.8115 |
| LogReg / Local optimizer | 6.565 | 6.589 | 1.0451 |
| LogReg / Candidate E2E | 23.912 | 23.507 | 0.9805 |
| GLM / 전체 compilation | 29.490 | 30.610 | 1.0380 |
| GLM / PlacementAnalysis | 24.944 | 25.930 | 1.0395 |
| GLM / Physical Model | 0.282 | 0.279 | 0.9894 |
| GLM / Cost Surface | 0.339 | 0.314 | 0.9389 |
| GLM / Local optimizer | 0.706 | 0.674 | 0.9549 |
| GLM / Candidate E2E | 27.769 | 28.822 | 1.0379 |

| Workload | Baseline peak 중앙값 (MiB) | Candidate peak 중앙값 (MiB) | Paired C/B | 개별 paired 비율 |
|---|---:|---:|---:|---|
| LogReg | 2112.7 | 2066.3 | 0.9992 | 0.6673, 0.9992, 1.0023 |
| GLM | 1228.8 | 1511.7 | 1.0276 | 1.0276, 1.0120, 1.2701 |

| Workload | Oracle calls B→C | Key B→C | Fact B→C | Clause B→C | Indexed handle B→C | Alternative B→C |
|---|---:|---:|---:|---:|---:|---:|
| LogReg | 7,989→7,989 | 9,009→9,009 | 55,207→55,207 | 198,577→198,577 | 4,361→4,361 | 5,295→5,295 |
| GLM | 5,821→5,821 | 7,009→7,009 | 22,367→22,367 | 32,957→32,957 | 387→387 | 1,755→1,755 |

LogReg compilation은 약 2.4% 낮고 GLM은 약 3.8% 높게 측정됐다. 메모리는 LogReg 약 동일, GLM 약 2.8% 높았다. 3쌍의 변동성과 동일한 생성량을 고려하면 실제 workload의 일반적인 성능 개선 또는 메모리 감소가 입증됐다고 할 수 없다. 특히 Model/Cost Surface 시간 차이를 이 변경의 인과적 개선으로 단정하지 않는다.

최종 후보 engine tree SHA-256: `7f9cd5d1c18a94309b126c329a6ddf6a8a6e7e0abb818844c7aa8a9237e8b216`. 원자료: `evidence/paired-logreg-glm-final-v2/paired-summary.json`, 각 `runs/*/cases/*/fed.log`. Baseline은 constructor 계측만 추가한 `baseline-engine-counter-overlay`, 후보는 `candidate-engine-final-v2-20261008T2205`다. 재현 명령은 [validation 문서](../experiments/fed-oracle-native-validation-20261008/INDEPENDENT_VALIDATION.md)에 있다.

### 분석 단계의 시간·할당량과 support work

다음 값은 기존 `SEARCH_SPACE_LIVE` terminal snapshot에서 구한 3회 중앙값이다. 할당량은 계측 thread가 누적 할당한 GiB이며 live heap 또는 peak memory가 아니다. 부모/자식의 inclusive 값은 중복되므로 행끼리 합산하지 않는다.

| Workload / 단계 | B inclusive (s) | C inclusive (s) | B 누적 할당 (GiB) | C 누적 할당 (GiB) |
|---|---:|---:|---:|---:|
| LogReg / ANALYSIS | 14.355 | 14.318 | 6.573 | 6.583 |
| LogReg / CLOSURE_REPLAY | 10.891 | 10.923 | 4.886 | 4.895 |
| LogReg / DIRECT_BINDING | 4.914 | 4.824 | 2.215 | 2.221 |
| LogReg / CFG_REPLAY | 1.886 | 1.921 | 0.764 | 0.764 |
| LogReg / SUPPORT_PRODUCT_RELATION_MATERIALIZATION | 0.380 | 0.358 | 0.190 | 0.191 |
| GLM / ANALYSIS | 24.900 | 25.894 | 4.976 | 5.004 |
| GLM / CLOSURE_REPLAY | 20.775 | 21.788 | 3.242 | 3.268 |
| GLM / DIRECT_BINDING | 0.696 | 0.730 | 0.205 | 0.205 |
| GLM / CFG_REPLAY | 17.868 | 18.889 | 1.916 | 1.941 |
| GLM / SUPPORT_PRODUCT_RELATION_MATERIALIZATION | 0.077 | 0.078 | 0.032 | 0.032 |

3회 합계의 support work는 양쪽이 동일하다. LogReg: support prefix **262,842**, support leaf **189,108**, relocation leaf **18,906**. GLM: **15,801 / 10,422 / 1,059**. Oracle call 및 object count뿐 아니라 이 방문량에서도 감소가 없다. DP join/projection 방문량은 이 로그에 없으므로 별도 감소 주장을 하지 않는다.

LogReg는 Closure/DIRECT binding, GLM은 CFG replay가 주된 분석 비용이다. 별도 Oracle·Candidate Generation stopwatch는 기존 기록에 없으므로 그 시간만 정확히 분리한 수치는 제공하지 않는다. Phase별 누적 할당과 전체 peak는 구분했다. 상세 JSON은 `evidence/paired-logreg-glm-final-v2/analysis-phase-report.json`이다.

### 최종 protected weighted Docker 추가 검증

최종 v2의 한 paired run도 50개 합법 행과 source/action/support, objective bits `4611719720386145812`가 일치했다. fact **73→90**, Clause **76→69**, indexed handle **6→0**이고 Oracle 50회, Alternative 52개는 동일했다. Compile **1.456→2.008초**, peak **911,380,480→860,037,120 bytes**다. 작은 사례의 witness overhead와 단일 실행 변동을 숨기지 않으며, phase trace가 없는 사례에서 단계별 속도 향상을 추정하지 않는다.

### 소규모 LogReg JFR 검증

별도 profiling pair도 source/proof/output 비교를 통과했다. Sampled represented allocation은 전체 **17.549→17.106 GB**, planner에 귀속된 표본은 **13.911→14.238 GB**다. JFR는 표본 계측이므로 exact 총 할당량/peak 또는 성능 개선의 증거로 해석하지 않는다. 주요 남은 candidate frame은 `JointValueMap.SelectedFixedPoolGraph.matches`(2.756 GB), `PlacementIdentity.IdentityList.equals`(2.043 GB), `WeakIdentityCache.get`(1.731 GB)다. 이는 새로운 Oracle region보다는 기존 identity/joint 관계 처리의 할당 비용이 여전히 크다는 조사 근거다. 원자료는 `evidence/paired-logreg-jfr-final-v2/`에 보존한다.

## COFEE-evaluation 대규모 검증 — 후속 사용자 지정

위 192×8 결과는 대규모 workload의 플래닝/실행 시간 답변으로 대체할 수 없다. 사용자 지시에 따라 현재 COFEE-evaluation 정본의 **50,000×128** dataset과 전체 학습 DML로 검증을 추가한다. 진행 및 최종 측정은 [COFEE 대규모 검증 기록](FEDPLANNER_COFEE_LARGE_VALIDATION_2026-10-08_KO.md)에 별도로 기록한다. 이 문서의 소규모 수치를 COFEE 대규모 결과로 인용하지 않는다.

## 남은 전개 지점과 이유

| 위치 | 남아 있는 전개 | 이유 / 다음 조치 |
|---|---|---|
| 일반 FOUT/source-producing FED | tuple별 CandidateRuleKey/fact/action | downstream source reference가 exact rule identity를 소유한다. layout 대표로 대체하면 authority를 잃음 |
| weighted Closure | 단일/쌍 축 action witness fact, binding 생성 | 기존 relocation projection과 source pruning의 정확한 권한 증거. 작은 domain에서는 압축 비용이 더 큼 |
| `ExactPhysicalModel.forEachRelationInputs` | region의 입력 tuple 전개 | tuple별 emission/authority/cost domain을 생성함. 다음 최우선 병목 |
| `ConditionalRegion.emissionsFor` | 방문한 tuple의 emission 영구 cache | fact가 0이어도 메모리 Θ(방문 tuple)가 될 수 있음 |
| Physical support | clause-sensitive/unknown authority의 Clause 전개 | VALUE_MAP/경계/공동 비용의 분리 가능성을 증명하지 못한 fallback |
| DP | 일부 support는 factorized, tuple Alternative domain은 explicit | join/projection/elimination의 모든 조합 전개 제거를 완료하지 않음 |
| legacy selector / audit | 필요 시 exact member 전체 순회 | 정책 호환 또는 검증 도구가 exact signature를 요구함; production의 선택 복원과 구분 |

관계 축 크기가 d₁…dₙ이고 Oracle 의존 축이 D이면 evidence 평가의 유리한 상한은 ∏ᵢ∈D dᵢ, 독립 축 저장은 ∑ᵢ∉D dᵢ다. 하지만 source/action authority와 모든 consumer도 압축을 유지해야 전체 이득이 된다. 현재 Physical 단계가 모든 tuple를 방문하는 경우 여전히 ∏ᵢ dᵢ 비용이 남는다. 공유 제약의 비용을 독립 합으로 근사하지 않았다.

## 다음 우선순위

1. 실제 LogReg의 Closure/DIRECT/proof 재계산과 GLM의 CFG replay를 우선 profiling·개선한다. 현재 workload에서 시간이 큰 경로이며, 이번 변경으로 방문량은 줄지 않았다.
2. Physical domain을 tuple Alternative 대신 conditional authority/cost relation으로 표현하고, 정확한 physical tie-break까지 유지하는 indexed argmin/traceback을 구현한다.
3. 해당 표현을 DP join/projection/elimination에 전달한다. shared owner와 sparse hole을 포함한 variable scope를 보존하고 선택된 source assignment만 복원한다.
4. FOUT exact source reference를 region+member index로 소유하는 계약을 도입한 뒤 일반 FED family를 확장한다. 먼저 identity/receipt/action 계약을 통과해야 한다.
5. weighted witness 생성의 작은-domain 비용을 줄이고, 불필요한 conditioned-emission cache 보유를 제거한다.
6. 실제 LogReg/GLM에서 영향을 받는 rule별 호출/할당 profile에 따라 적용 범위를 확장한다. synthetic 압축 비율을 실제 전체 compilation 성능으로 일반화하지 않는다.
