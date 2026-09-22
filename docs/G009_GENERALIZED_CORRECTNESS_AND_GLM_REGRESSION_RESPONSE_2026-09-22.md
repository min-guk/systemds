# G009: 다른 HOP/DML의 정확성 보장과 GLM planning 회귀에 대한 답변

- 작성일: 2026-09-22.
- 계획 수정: 기존 oracle **재사용 우선**, 미지원 계약만 확장하도록 §5·§9를 구체화했다. 새 oracle 프레임워크 구축은 범위에 포함하지 않는다.
- 범위: 현재 소스·테스트·기존 성공 로그를 분석한 답변과 후속 실행 제안이다. **이번 작업에서는 production 수정·새 benchmark·학습 runtime 실행을 하지 않았다.**
- 관련 결과: [StepLM 수정 및 56조건 검증 보고서](G009_STEPLM_ROOT_CAUSE_AND_REPAIR_EXECUTION_REPORT_2026-09-21.md).
- 표시: **확인**은 코드/로그의 직접 증거, **추론**은 그 증거에 대한 설명, **미확인**은 추가 검증이 필요한 부분이다.

## 1. 먼저 답변

### 1.1 다른 곳에는 버그가 없다고 보장할 수 있는가?

**현재는 보장할 수 없다. 56/56 planning 성공을 그런 보장으로 표현하면 잘못이다.**

이번에 확인한 것은 고정된 입력·worker=4·네 planner에서 정상 planning이 완료됐다는 사실이다. 모든 HOP 조합, shape, partition, 함수/루프, privacy 조건에서 합법적 후보를 빠짐없이 표현하고 불법 후보를 만들지 않는다는 증명은 아니다. 실제 분산 학습 실행의 정확성도 별도다.

해결 방향은 workload 목록을 계속 늘리는 것만이 아니다. 이번 버그들을 **배치 전달, 순환 의존성, 권한 철회, 후보 관계 보존이라는 공통 계약**으로 바꾸고, 기존 독립 oracle에 빠진 fixture·assertion·의미론만 보강하여 검증해야 한다. 제한된 명세에 대한 조건부 보장은 가능하지만, 유한한 테스트 통과로 임의 DML에 미지의 버그가 전혀 없다는 결론을 낼 수는 없다.

### 1.2 GLM은 실제로 느려졌는가? 해결 가능한가?

**기존 성공 로그 대비 관측 시간은 증가했다. DP-local은 45.142초 → 53.787초, 약 19.15% 증가다.** 60초 미만이라는 사실로 이 회귀를 무시해서는 안 된다.

증가한 CandidateE2E 시간의 약 **93.06%가 공통 후보·증명 분석 단계**에 있다. DP-local의 `selection` 시간 증가는 약 0.011초다. 따라서 우선 고칠 곳은 selector가 아니라 공통 builder/closure의 반복 처리다.

반복 처리·인덱스 재생성·동일 증명 재계산을 줄일 여지는 코드에서 확인된다. 다만 각 변경의 시간 기여율은 아직 분리되지 않았으므로 **“어떤 한 줄 때문에 느려졌다”거나 “45초로 반드시 복구된다”는 주장은 아직 할 수 없다.** 새로 복구한 합법적 후보와 정확성 검사는 유지하고, 같은 일을 반복하는 비용을 줄여야 한다.

## 2. 현재 보장과 미보장을 구분해야 한다

| 검증 | 현재 확인한 사실 | 여기서 결론낼 수 없는 것 |
|---|---|---|
| worker4 공식 Docker 56조건 | 최종 동일 JAR, 각 조건 정상 planning-only receipt | 모든 입력·모든 joint plan의 합법성/완전성, 학습 runtime 정확성 |
| 표적 61 test | 60 실행 통과, 기존 skip 1 | skip 조건·미포함 HOP/CFG 조합의 안전성 |
| StepLM loop fixture | 동적 열·cbind·초기 정의와 두 backedge의 reader support | 임의 loop와 함수 합성의 정확성 |
| incremental/full 재계산 비교 | 해당 fixture에서 decoded 관계가 일치 | 두 경로가 공유하는 잘못된 의미론의 발견 |
| 독립 공간 인증 | 전체 판정 `UNKNOWN` | production 공간에 누락/불법 후보가 0이라는 보장 |

[독립 인증 최종 보고서](G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_FINAL_REPORT_2026-09-21.md)의 「판정 방법과 신뢰 경계」는 production과 기존 exhaustive가 동일 builder/domain에 의존할 수 있음을 명시한다. 당시 447-cell backlog 중 283개 in-scope 증빙이 미완료였고, 48 rule family의 runtime tuple parity proof도 OPEN이었다. 이후 [P/E·과거 공간 비교 보고서](G009_PE_AND_LEGACY_PLAN_SPACE_COMPARISON_EXECUTION_REPORT_2026-09-22.md)의 작은 fixture 비교도 전체 corpus 인증을 완료한 것은 아니다.

**앞선 “마무리”의 의미는 StepLM worker4 수정과 요청한 56조건 실행의 완료다. 전체 planner의 무결점 인증 완료가 아니다.** 기존 single-worker `StepLmPrivateAggregatePlanningContractTest`의 별도 `lmCG.dml:129` 실패 역시 이번 성공으로 해결됐다고 세지 않는다.

## 3. StepLM 특수 문제가 아니라 어떤 공통 계약이 깨졌는가?

| 버그 계열 | 이번에 드러난 사례 | 다른 script에서도 확인해야 할 계약 |
|---|---|---|
| 배치 변환 표현 누락 | 동적 열 slice의 ROW witness 소실 | 열 너비와 행 partition 축을 구별하고, 유효한 전체 행 slice의 정확한 worker↔row 관계를 보존 |
| 중간 producer 표현 누락 | ROW 입력의 transpose가 만든 COL 결과 이후 `0-t(X)` | 원본 literal anchor뿐 아니라 실행 가능한 중간 producer의 실제 배치도 consumer에 전달 |
| 순환 proof identity 불일치 | pinned root와 backedge의 template identity 분열 | 동일한 의미의 proof 의존성이 올바른 SCC로 연결되고, 서로 다른 authority는 합치지 않음 |
| 폐쇄 단계 간 권한 부활 | privacy가 제거한 row를 physical rebuild가 복원 | 재생성 후에도 source identity·privacy·지원 관계를 재검사하고 철회된 권한을 게시하지 않음 |
| 선호와 합법성 혼동 | AggLocal의 엄격한 선호 뷰에 완전한 계획이 없음 | 선호 뷰의 infeasible을 공통 합법 공간의 infeasible로 해석하지 않음 |

따라서 `steplm` 이름이나 특정 행 번호를 조건으로 처리할 문제가 아니다. 동일한 연산/CFG 구조가 등장하는 다른 DML에서도 같은 불변식이 필요하다.

### 현재 테스트에서 바로 보강할 수 있는 구체적 약점

1. `StepLmNegatedTransposePlacementTest.java:45–56`의 함수 사례는 **graph가 비어 있지 않음**만 단언한다. 이름과 달리 해당 negation의 정확한 FED/FOUT/COL 후보·source binding·authority 존재를 직접 단언하지 않는다. graph의 다른 node만 살아 있어도 통과할 수 있다.
2. 같은 파일 `:59–78`은 보호된 negation에 대해서만 검사한다. 보호된 negation 수/존재 자체를 별도로 단언해야 privacy 전파가 잘못 사라지는 회귀도 놓치지 않는다.
3. `IndexingFEDRightIndexBoundsTest.java:25–40`은 bounds helper 호출 테스트다. 실제 FED right-index가 반환하는 FederationMap, 값, 범위 오류의 worker 동작까지 검증한 것은 아니다.
4. `StepLmDynamicLoopPlacementTest.java:49–59`의 incremental/full 비교는 중요하지만 **독립 의미론 oracle가 아니다.** 두 구현 경로가 같은 잘못된 rule을 호출하면 함께 통과할 수 있다.

이것들은 모두 “새 production 버그가 발견됐다”는 뜻은 아니다. **현재 테스트로 놓칠 수 있는 구체적 회귀 경로**다.

## 4. 무엇을 수학적으로 보장해야 하는가?

고정 입력 계약 I에는 compiler IR, worker/range, shape, privacy, runtime 연산 지원, value version 및 관찰 가능한 action을 포함한다.

- R(I): 이 계약의 독립 의미론에 따라 합법적인 joint physical plan 집합.
- P(I): 공통 builder가 selector 이전에 표현하는 **결합 관계까지 포함한** plan 집합.
- S_j(P): selector j가 자신의 목적함수/선호로 선택한 결과.

필수 계약은 다음과 같다.

```text
Soundness:     P(I) \ R(I) = ∅       # 불법 계획을 추가하지 않음
Completeness:  R(I) \ P(I) = ∅       # 명시한 지원 범위의 합법 계획을 누락하지 않음
Selection:     S_j(P(I)) ∈ P(I)      # 선택 결과가 정확한 공통 관계를 만족
Non-mutation:  P_before = P_after    # selector가 공통 공간을 변경하지 않음
```

단순 node별 후보 목록이 같다고 P가 같은 것은 아니다. input binding, AND/OR support, 여러 consumer의 shared producer 선택, relocation, authority가 결합을 결정한다. candidate 수·해시·최적해 하나가 같다는 비교도 부족하다.

독립 명세 R의 범위·가정 자체가 틀리면 위 조건을 검증해도 실제 runtime 전체를 보장하지 못한다. 그래서 작은 실제 FED 연산 계약 테스트로 명세와 runtime을 연결해야 한다. 지원/증명이 미확인인 경우는 `UNKNOWN`으로 남겨야 하며, “불법이니 삭제 가능” 또는 “합법 계획 없음”으로 바꾸면 안 된다.

### 반드시 보존할 다섯 가지 국소 불변식

1. **축 보존:** 유효한 `X[:,a:b]`에서 worker별 행 구간은 보존되지만 열 너비는 달라진다. 같은 worker 집합과 같은 배치는 다르다. single-worker FULL 재분류, COL, 부분 행, misaligned cbind는 별도 계약이다.
2. **모든 reaching definition:** TRead는 초기 정의와 모든 실행 가능한 backedge 각각에 정확한 지원을 가져야 한다. 한 seed만 맞는다고 전체 loop를 승인하지 않는다.
3. **순환 grounding:** 외부 근거 없는 자기 순환은 권한이 아니다. SCC 내부의 각 transfer와 AND 의무를 증명하고, 외부 근거·value version·선택 호환성을 함께 검증한다. 0/1/2회 loop 테스트만으로 무한 반복에 대한 귀납이 증명되지는 않는다.
4. **철회와 identity:** source/권한 삭제·교체 후 오래된 proof를 재사용하지 않는다. 같아 보이는 worker pool로 expired reference를 대체하지 않는다.
5. **고정점:** 후보뿐 아니라 facts·binding·relation·authority를 포함한 상태 X에서 완성된 transfer F(X)=X를 확인한다. 추가와 삭제가 섞인 경로에 `old ∪ new`를 적용하거나, hash 일치만으로 수렴을 선언하지 않는다.

## 5. 다른 HOP/DML까지 커버하는 현실적인 검증 방식

**전 워크로드를 매번 다시 돌리기보다, 작은 공통 계약을 강하게 검사하고 큰 workload는 최종 회귀로 사용한다.**

### A0. 기존 oracle 재사용과 부족한 범위 판별 — 새 프레임워크 금지

아래 기존 코드는 `src/test/java/org/apache/sysds/test/component/federated/placement/` 아래에 있다. **전수 열거기·판정 프레임워크·비교 runner·fixture 체계를 새로 만들지 않는다.**

| 기존 구성 | 재사용할 책임 | 이번에 허용하는 보강 |
|---|---|---|
| `oracle/semantic/PrimitiveDomainGenerator`, `PrimitivePlanEnumerator` | 독립 primitive domain과 작은 공간의 전수 열거 | 새 fixture 표현에 꼭 필요한 domain 항목만 추가. 열거/분할 엔진 재작성 없음 |
| `oracle/semantic/JointPlanLegalityChecker`와 `Runtime/Matrix/StateCapabilityOracle` | 기존의 독립 합법성 판정과 명시적 UNKNOWN | 실제 미지원인 배치 합성·순환 grounding·권한 계약만 확장. production predicate 복사 금지 |
| `oracle/builder/BuilderOracle`와 기존 fixture/test | 이미 표현하는 builder 계약의 기대 결과 | 모델이 해당 계약을 표현할 수 있으면 fixture/assertion 추가. 표현력이 부족하면 기존 모델을 최소 확장하거나 UNKNOWN 유지 |
| `oracle/selector/ExactSelectorOracle` | 작은 명시적 공간의 selector 선택 계약 | 공통 builder 완전성 증거로 혼용하지 않고, selector 변경 시 관련 회귀만 재사용 |
| `oracle/independence/OracleIndependenceContractTest` | oracle와 production 구현 사이의 독립성 경계 검사 | 의미론/adapter 의존성 변경 후 재실행. 통과 자체를 의미론 정확성 증명으로 취급하지 않음 |
| `shadow/PlanSpaceFixtureArtifactAdapter`와 기존 P/E/R 비교 도구 | 입력/출력 연결, 대응 가능한 공간의 차집합·증거 기록 | 필요한 fixture 연결만 보강. 공유 domain 누락을 놓치는 P/E 일치를 독립 R 인증으로 승격하지 않음 |

구현 전에는 세 고위험 경계에 대해서만 다음 상태를 기존 테스트 이름·assertion·rule ID와 연결해 짧게 기록한다. 전체 inventory/인증 체계를 다시 구축하지 않는다.

1. **기존 지원·검증됨:** 구현/테스트를 새로 작성하지 않는다. 관련 변경이 있으면 영향 회귀로 재실행한다.
2. **의미론 지원, fixture/assertion 부족:** 기존 oracle에 반례와 정확한 assertion만 추가한다.
3. **표현/의미론 미지원:** runtime 계약과 수기 기대 결과를 먼저 명시하고 기존 모델/판정기를 최소 확장한다.
4. **아직 판정 불가:** UNKNOWN과 부족한 증거를 유지한다. 테스트 편의를 위해 LEGAL 또는 ILLEGAL로 바꾸지 않는다.

특히 현재 `JointPlanLegalityChecker.check()`는 미명세 순환에서 `S-CYCLE/UNKNOWN`을 반환하고, `checkForCertification()`는 bounded 판정을 통과해도 전체 의미론 증빙 부족을 `S-COVERAGE/UNKNOWN`으로 남긴다. **loop fixture 추가만으로 이 두 제한이 해결된 것은 아니다.** 필요한 bounded loop 계약을 증명해 확장하되 전체 인증 guard를 제거하지 않는다. 기존 테스트가 해당 범위를 이미 충분히 검사한다면 중복 oracle를 만들지 않는다.

### A. 작은 protected fixture와 기존 독립 oracle 보강

- 우선 `slice → cbind → transient replay`, `transpose → unary/binary → function boundary`, `privacy withdrawal → physical rebuild`의 세 경계를 선정한다.
- 아주 작은 2–4 HOP 비순환 조합과 제한된 loop-template를 생성한다. worker 1/2/4, 균등/불균등 range, 정렬/불일치, literal/dynamic width, PRIVATE/PRIVATE_AGGREGATE 조건을 명시한다. 기존 PUBLIC ignore를 몰래 전체 보장에 포함하지 않는다.
- 각 작은 universe는 기존 `PrimitivePlanEnumerator`와 지원 범위가 확인된 독립 판정기로 열거해 P와 양방향 차집합을 비교한다. Production의 candidate generator·proof predicate를 정답 생성에 재사용하지 않는다. 의미론 미지원 상태에서는 열거가 끝나도 PASS로 세지 않는다.
- 기대 합법/불법 양쪽을 둔다. 빈 domain만 확인하지 말고 **왜 불법인지**, 반대로 합법 예에서는 어떤 정확한 관계가 남아야 하는지 단언한다.
- 모든 차원의 전체 곱을 무조건 실행하지 않는다. 기본 pairwise에 loop×authority×layout 같은 고위험 다중 경계는 명시적으로 추가하고, 작은 oracle가 전수인 범위와 조합 표본 범위를 구별한다.

### B. 합성·변형·mutation 검사

- 변수 이름 변경이나 독립 statement 순서 변경 등, 의미 보존이 확인된 변형 전후에 대응 관계를 정의하고 관찰 가능한 후보 공간을 비교한다. 함수 인라인/분리에서는 occurrence와 value-version 대응부터 정의한다.
- source identity 변경, 한 backedge의 layout 변경, 권한 철회, producer 삭제 시 incremental 결과를 fresh full 재계산과 대조한다.
- 의도적으로 seed 누락·backedge 누락·privacy 재검사 생략을 주입했을 때 테스트가 실제로 실패하는지 확인한다. 실패를 잡지 못하는 테스트는 보호 장치로 세지 않는다.
- runtime 명세와의 대조는 작은 FED 연산 단위로 별도 수행한다. 전체 ML 학습을 돌리지 않아도 rightIndex/cbind/transpose의 값·map 계약을 확인할 수 있다. planning-only 결과를 runtime 증거로 대신하지 않는다.

### C. 변경 영향 기반 회귀

- rule/identity/closure별 영향 테스트 목록을 두고, 그 경계를 고친 경우 관련 테스트를 먼저 실행한다.
- 개발 중에는 StepLM 최소 fixture와 GLM DP-local 단회로 빠르게 판단한다.
- 통합 후보가 고정된 뒤 필요한 네 planner·전체 workload 회귀를 한 번 수행한다. 매 작은 수정마다 56조건을 반복하지 않는다.
- 통과 범위, skip, UNKNOWN, 아직 남은 single-worker 사례를 따로 보고한다. 56개의 상관된 조건을 독립 무작위 표본처럼 취급해 미지의 버그 확률을 계산하지 않는다.

## 6. GLM 회귀: 기존 로그가 실제로 말해 주는 것

### 6.1 가장 가까운 이전 성공본과 비교

이전 JAR `805095277f03d4cda0a85278be64c09072c118696f908f00e1f03f62afbf3f28`과 최종 JAR `f143c28197921ffb2d0c5a2138912bdb7ccb245898a309fc0e039bd29da27f30`의 성공 receipt를 비교했다. 단위는 초다.

| planner | 이전 전체 초기 planning | 수정 후 | 관측 증가율 | 이전 analysis | 수정 후 analysis |
|---|---:|---:|---:|---:|---:|
| FedFirst | 45.114049 | 53.290689 | +18.12% | 37.877526 | 46.111291 |
| AggLocal | 46.476850 | 53.845699 | +15.85% | 37.145987 | 45.589775 |
| DP-local | 45.141704 | 53.786597 | **+19.15%** | 36.361183 | 45.039488 |
| DP-global | 44.118993 | 57.674614 | **+30.73%** | 35.165836 | 46.538599 |

**조건 대조 확인:** 동일 harness/P2P2D/worker4/LAN/planning-only, seed `2026072701`, planner별 compile config SHA-256 동일, coordinator heap 16GiB·young 1600MiB·ActiveProcessorCount=8 등 JVM 옵션 동일이다. 이전 FedFirst·DP-local 행은 동일 이전 JAR의 성공 canary를 재사용한 결과다.

**한계:** 서로 교차 배치한 반복 실험은 아니다. host 경합/JIT 변동까지 통제되지 않았고, JAR 사이의 모든 차이를 개별 patch로 분리한 실험도 아니다. 따라서 증가한 관측치와 위치는 확인되지만 인과적 회귀율의 정밀 추정은 아니다. 이전 GLM이 성공했다는 것 자체도 이전 공통 공간의 완전성을 보장하지 않는다.

더 오래된 `815e75…`에서도 DP-local 45.779340초, DP-global 44.644206초였다. 대략 45초였다는 참고 근거지만, 바로 위 `805095…`가 더 가까운 비교 대상이다. 실패까지의 716초나 예전 36.633초 CandidateE2E를 이번 전체 초기 planning과 혼합하지 않는다.

### 6.2 DP-local 증가분의 위치

| 지표 | 이전 | 수정 후 | 차이 |
|---|---:|---:|---:|
| 전체 초기 planning | 45.141704 | 53.786597 | +8.644893 |
| CandidateE2E 전체 | 43.154443 | 52.479944 | +9.325501 |
| 공통 analysis | 36.361183 | 45.039488 | **+8.678305** |
| model | 1.317600 | 1.557408 | +0.239808 |
| cost surface | 0.515138 | 0.560698 | +0.045559 |
| optimizer | 1.969136 | 2.264653 | +0.295517 |
| selection | 0.810156 | 0.820901 | **+0.010745** |
| conversion | 1.864340 | 1.924220 | +0.059880 |

위 표는 일부 세부 단계만 표시했다. **analysis ⊂ CandidateE2E ⊂ 전체 planning**이므로 표의 행을 모두 합하면 중복 계산이다. HopsRewrite는 오히려 1.141711→0.680987초로 줄었고, LopsBuild는 43.182468→52.516903초로 늘었다. analysis는 최종 HOP 경계에서 `bindPlacementAnalysisAtFinalHopBoundary()`를 호출하는 구간이다([S1]).

```text
Δanalysis / ΔCandidateE2E = 8.678305 / 9.325501 ≈ 93.06%
```

따라서 **“selector가 더 많은 최적 조합을 찾아서 주로 느려졌다”는 설명은 현재 로그와 맞지 않는다.** 다만 DP의 model/cost/optimizer도 별도 탐색 비용이므로 selection 필드 하나가 DP 전체라고 보지는 않는다. FedFirst/AggLocal의 dedicated selection=0은 탐색이 공짜라는 뜻이 아니며, 해당 경로의 비용은 OtherPlanning 등에 잡힌다. 두 경로의 OtherPlanning은 이번 비교에서 감소했다.

DP-local GC 누적 시간은 0.599→0.751초로 약 0.152초 증가했다. 이 관측만으로 8.645초 증가 전체를 GC 탓으로 설명할 수 없다. JIT compilation 누적 시간은 planning wall-time에 더하는 항목이 아니다.

## 7. 왜 공통 analysis 비용이 늘었을 가능성이 높은가?

| 우선순위 | 확인된 코드 변화 | 해석과 미확인점 |
|---|---|---|
| 1 | privacy 적용 후 CFG/direct/physical closure를 다시 호출하는 외부 고정점, 그 내부의 추가 privacy closure [S2] | **확인:** 전체 관계 재검사 호출과 구조 동등성 비교가 추가됐다. **추론:** no-change여도 반복되는 전체 처리의 유력 비용. GLM의 pass별 시간·호출 수는 미측정 |
| 2 | 즉시 producer의 실행 가능한 native realization을 seed에 추가하고 정렬/중복 처리 [S3] | **확인:** 이전보다 더 많은 합법적 증명 경로가 처리될 수 있다. 이것을 불필요한 후보로 간주해 삭제하면 다시 누락 버그가 된다. GLM의 실제 seed/관계 증가량은 미측정 |
| 3 | loop bootstrap용 pool과 모든 definition용 pool의 별도 구성, occurrence 순회 [S4] | 역할은 다르지만 변경 없는 topology/index를 반복 생성할 수 있다. 정확한 반복량과 캐시 가능 범위는 추가 확인 필요 |
| 4 | staging template의 의존성 참여와 template-root identity 보존 [S5] | 필요한 SCC 상태가 복구되며 proof graph가 커질 수 있다. 커진 공간 자체의 필수 비용과 중복 방문 비용을 구별해야 함 |
| 별도 | AggLocal strict-view 실패 후 원래 공간에서 다시 first-feasible 선택 [S6] | 실패한 선호 탐색을 두 번 할 위험은 있으나 **DP-local/global의 공통 analysis 증가 원인은 아니다.** 이번 GLM AggLocal에서 이 경로가 실행됐다고도 확인되지 않음 |

요약하면 **정확성 복구로 필요한 작업이 늘어난 부분**과 **정확성을 유지하면서 줄일 수 있는 중복 작업**이 섞여 있을 가능성이 높다. 아직 후보 수·pass 수 카운터가 없어 둘의 비율은 확정할 수 없다.

## 8. 해결 방향: 올바른 공간을 보존한 채 중복 처리를 줄인다

### 8.1 기준선을 두 개로 관리한다

- **성능 참고 기준:** 직전 성공본 `805095…`의 GLM 수치. 느린 새 구현을 기준선으로 바꿔 회귀를 지우지 않는다.
- **정확성 보존 기준:** 수정본 `f143c281…`의 계약·decoded 후보/지원 관계. 최적화가 이전의 누락을 다시 도입해서 빨라지는 것을 막는다. 단, 이 기준 자체가 완전한 독립 oracle라는 뜻은 아니다.

목표는 두 기준을 함께 만족하는 것이다. 동일 조건에서 약 45초 수준을 회복하는 것을 우선 성능 목표로 삼고, 최종 판단은 각 planner의 참고 시간과 비교한다. 60초 미만은 회귀를 허용하는 이유가 아니다. 특히 DP-global 57.675초의 여유는 2.325초뿐이며 단회 통과가 다음 실행의 60초 미만을 보장하지 않는다.

### 8.2 먼저 최적화할 세 가지

1. **증명된 no-change 재검사 생략 / 변경 영향 범위만 처리**
   - completed closure의 입력 revision과 privacy 결과가 정확히 같고, facts·relation·authority·미처리 의존성까지 안정됐다는 조건에서만 결과를 재사용한다.
   - privacy 삭제/authority 교체가 있으면 reverse support index로 영향받는 consumer와 해당 SCC를 무효화한다. 단순 HOP adjacency만으로 의존성을 정하지 않는다.
   - 최종 publication 검사를 제거하지 않는다. 단순히 “privacy enum이 안 바뀜”이나 “후보 수가 같음”을 skip 조건으로 쓰지 않는다.
2. **proof topology/index 재사용**
   - bootstrap 가정과 검증 완료 권한은 분리하되, 공유 가능한 불변 topology·owner 인덱스는 재사용한다.
   - candidate identity, input binding, value version, template-root, privacy/authority, 삭제와 부재 의존성까지 포함한 정확한 key를 사용한다. negative 결과 캐시는 이전에 없던 source가 생기는 경우에도 무효화한다.
   - 같은 worker pool이라는 이유로 proof를 합치지 않는다. 메모리 한도를 넘으면 안전하게 재계산하며 correctness를 낮추지 않는다.
3. **producer seed의 변경분 처리와 손실 없는 중복 제거**
   - 변경된 producer의 realization만 다시 인덱싱하고 기존 정렬 키/immutable 객체를 재사용한다.
   - 관찰 가능한 배치·authority·action·선택 관계가 다른 후보는 유지한다. 동등성을 증명한 경우만 내부 표현을 공유한다.

AggLocal은 별도 후순위로 **선호를 hard exclusion보다 탐색 순서로 표현**하는 방향을 검토할 수 있다. 그러면 엄격한 뷰의 불가능성을 먼저 끝까지 증명한 뒤 다시 시작하는 비용을 줄일 수 있다. 기존 로컬 정책 우선순위·차선 허용·첫 합법 해 종료 계약을 테스트해야 하며, 정책 변경을 GLM DP 회귀 수정과 섞지 않는다. 일반 제약 문제에서 first-feasible도 최악 경우에는 오래 걸릴 수 있으므로 “경량”을 무조건 선형 시간이라는 보장으로 포장하지 않는다.

### 8.3 필요한 성능 개선량

현재 DP-local 전체를 `T = A + B`로 나누면:

```text
A = analysis = 45.039488초
B = 나머지 전체 planning = 8.747109초
목표 T ≤ 45.141704초라면, B가 같을 때 A ≤ 36.394595초
→ analysis 시간 약 19.19% 절감, 즉 약 1.238배 가속 필요
```

반면 selection 0.820901초를 전부 없애도 전체의 약 1.53%만 줄어든다. model/optimizer를 포함한 비용도 살펴야 하지만 현재 우선순위는 **전체의 약 83.74%인 analysis**다. 이 계산은 최적화의 방향/필요량이며 달성 예측이 아니다.

## 9. 실행 제안: 계측보다 짧은 수정·검증 루프

| 순서 | 작업 | 통과 기준 |
|---|---|---|
| 0 | §5 A0의 기존 oracle·fixture를 세 고위험 경계에 매핑하고 지원/fixture 부족/의미론 부족/UNKNOWN으로 구분 | 기존 테스트·rule ID와 부족한 assertion/계약만 나열. 새 열거기·검증 framework·전체 inventory 구축 없음 |
| 1 | §3의 약한 assertion과 기존 oracle의 누락 fixture를 보강. 의미론이 미지원인 경우에만 기존 판정기/모델을 최소 확장 | seed/backedge/privacy mutation을 검출하고 기대 joint 관계를 확인. 변경된 oracle의 기존 회귀와 독립성 검사 통과. 미지원은 UNKNOWN 유지 |
| 2 | 이미 있는 full/CandidateE2E/analysis receipt 재사용. 필요한 경우 closure 시간·pass 수·전체/dirty owner 수·realization/support 수만 집계 | GLM 한 번의 실행 종료 시 작은 요약으로 원인 분리; per-row 문자열 trace나 새 계측 체계는 만들지 않음 |
| 3 | 가장 큰 반복 항목 하나만 의미 보존형으로 수정 | fixture의 old-correct/full/incremental decoded 관계 일치 + GLM DP-local 단회 + StepLM 관련 회귀 |
| 4 | 이득이 있는 변경만 통합하고 다른 planner 영향 확인 | 최종 정확성 검증 유지, 비용 이동/메모리 폭증 없음, 이전 GLM 성능 참고치와 비교 |
| 5 | 최종 JAR를 고정한 뒤 영향 범위의 공식 Docker 회귀 완료 | 공통 closure를 바꾸면 56조건 단회 확인. 개발 중 매번 56조건 반복 금지 |

추가 계측이 필요하더라도 **큰 단계 병목이 analysis라는 사실을 다시 확인하는 데 시간을 쓰지 않는다.** 그 안에서 어느 반복이 늘었는지만 최소 집계한다. 기준선/수정본은 동일 입력·설정·JVM·측정 경계로 비교하고, source/JAR 해시를 함께 보존한다. 안전 검사를 꺼 보는 실험은 원인 진단용일 뿐 채택 후보로 사용할 수 없다.

단회 미세 차이는 개선으로 확정하지 않는다. 차이가 작거나 실행 중 host 경합이 확인되면 “불명확”으로 남기고 해당 조건만 추가 확인한다. 자동으로 18쌍/ABBA 실험이나 전체 학습으로 확대하지 않는다. 이번 문서 작성에서는 이 실행 단계들을 시작하지 않았다.

### 이번 보강의 완료 기준과 중단 경계

- 세 고위험 경계마다 **기존 검증 재사용 / fixture 보강 / 의미론 확장 / 미완료**를 구분하고 근거 테스트를 연결한다. 기존 검증 전체를 재작성하는 것은 완료 조건이 아니다.
- 최적화가 변경하는 계약은 필요한 oracle/fixture gate가 통과한 뒤 채택한다. 그 계약이 UNKNOWN이면 해당 변경은 미검증으로 남기며, 시간만 개선됐다는 이유로 채택하지 않는다.
- 무관한 전체 447-cell 인증 완료를 GLM 최적화의 선행 조건으로 삼지는 않는다. 동시에 작은 경계 보강의 완료를 전체 공간 인증 완료라고 부르지도 않는다.
- 같은 oracle/입력 revision의 기존 증거는 재사용한다. 관련 구현이나 의미론이 바뀌면 영향받는 테스트를 다시 실행하며, 오래된 PASS를 새 revision의 결과로 복사하지 않는다.
- 최종 보고서에 새로 만든 항목보다 **재사용한 구성·보강한 계약·남은 UNKNOWN**을 먼저 적는다.

## 10. 최종 의사결정

1. **“이제 모든 script가 안전하다”는 주장을 하지 않는다.** 공통 계약과 검증 범위를 명시하고 미확인은 UNKNOWN으로 남긴다.
2. **GLM 관측 회귀는 인정한다.** 현재 가장 직접적인 증거는 공통 analysis의 약 8.7초 증가이며 selector 중심 대응은 우선순위가 아니다.
3. **기존 oracle의 빈틈만 보강하고, 반복 closure를 줄인다.** 별도 oracle 체계를 다시 만들거나 정확성 검사 제거·공통 후보 pruning·worker pool 강제·loop edge 삭제로 시간을 줄이지 않는다.
4. **성공 판정은 정확성과 성능을 분리해 함께 보고한다.** 56/56 성공, 독립 공간 인증, baseline 대비 시간, 단회 60초 통과는 서로 다른 주장이다.

## 11. 근거 색인

### 소스와 테스트 — 작성 시점 line 기준

- [S1] `src/main/java/org/apache/sysds/parser/DMLTranslator.java:367–397`: CandidateE2E 시작, common preparation과 analysis 구간. `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateFormationTiming.java:23–66`: phase partition; `src/main/java/org/apache/sysds/utils/Statistics.java:1215–1252`: full 및 CandidateE2E receipt.
- [S2] `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:906–943,2726–2736,2785–2792`: privacy 후 재-grounding·중첩 closure·정확한 수렴 비교.
- [S3] 같은 builder `:3126–3147`: 실행 가능한 직접 producer realization의 seed 반영.
- [S4] 같은 builder `:2677–2697,2738–2746,2778–2784`: loop seed 탐색, seed pool과 모든 definition pool.
- [S5] `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:962–974,1190–1200,1339–1377,1922–1944`: staging 의존성, template-root identity, 동적 column ROW 계약.
- [S6] `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/HeuristicPlacementAdapter.java:68–108`: strict 선호 뷰와 공통 공간에서의 재선택.
- [S7] `src/main/java/org/apache/sysds/runtime/instructions/fed/IndexingFEDInstruction.java:176–184,304–313`: filter 이전 bounds 검사. `src/test/java/org/apache/sysds/runtime/instructions/fed/IndexingFEDRightIndexBoundsTest.java:25–40`: helper 단위 검사.
- [S8] `src/test/java/org/apache/sysds/hops/fedplanner/placement/StepLmNegatedTransposePlacementTest.java:30–78`, `StepLmDynamicLoopPlacementTest.java:35–96`: 현재 regression fixture와 한계.

### 실험 기록 — 이번에는 기존 파일을 읽기만 함

- 이전 실행 조건: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009policy-run-fixedpoint-805.sh`; 행 색인 `/grid/3/cofee-lm-sweep-mchoi-20260914/g009policy-final805b/status.tsv`.
- 수정 후 조건/행/독립 사후 대조: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009steplm-final56-f143/`의 `run.sh`, `resume-after-duplicate-id.sh`, `status.tsv`, `audit-result.json`. DP-global은 무효 시도가 아닌 `g009stepfinalr_f143_4…` 성공 행이다.
- 이전 DP-local 로그: `/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/experiments/results/fed4/mkl-cost/glm_dataset-P2P2D_coordinator_mkl-cost_g009policy_final805_1_glm_P2P2D_mkl-cost_20260921_lan_coordinator1.log`.
- 수정 후 DP-local 로그: `/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/experiments/results/fed4/mkl-cost/glm_dataset-P2P2D_coordinator_mkl-cost_g009stepfinal_f143_3_glm_P2P2D_mklcost_20260921_lan_coordinator1.log`.
- 두 로그의 line 6에 full receipt, line 21–35에 phase 통계, line 36에 나노초 CandidateE2E receipt가 있다. 다른 planner의 정확한 로그 경로는 status의 run ID에 대응하는 `experiments/results/planning/<run_id>_lan_coordinator1/<conf>.json`의 `coordinator_log`에서 찾을 수 있다.
