# G009 selector 독립 공간 계획 실행 보고서

- 계획: [selector 독립적 search space 보존 계획](G009_SELECTOR_INDEPENDENT_SEARCH_SPACE_PLAN_2026-09-21.md)
- 소스 출발점: `a6281207cf520171af47f5f8755a34cbd28ecf37` (`integration/g009-baseline-20260919`)
- 상태: **부분 실행; A1–A12 전체 수용을 주장하지 않는다.** 각 항목의 미확인 범위를 아래에 남긴다.
- 기준 원칙: 공통 입력에서 비용/선호도에 의한 후보 삭제를 추가하지 않는다. 한 selector의 최적해 보존은 다른 selector의 공간 보존을 증명하지 않는다.

## 1. S0: 실제 호출 경계의 후보 삭제 감사

경로 prefix: `src/main/java/org/apache/sysds/hops/fedplanner/`. 아래는 선언된 호출 경계에 한정한 감사이며 전 저장소 증명이 아니다. `미검증`은 합법적인 pruning이라는 뜻이 아니다.

| 위치/효과 | 소유·근거 | 처분 및 현재 검증 수준 |
|---|---|---|
| `placement/PlacementAnalysis.java` canonical list의 정렬·`equals` 중복 제거 | 공통 표현, 동일 값 중복 | **유지**: 값이 구분되는 관계를 병합하지 않는지 기존 completeness 테스트와 작은 fixture로 검사; 모든 물리 identity에 대한 전수 검사는 미완료 |
| `placement/NeutralPlacementGraphBuilder.java` privacy/runtime/authority closure, source와 realization binding | 공통 합법성 | **유지·두 결함 수정**: FED-only node에 CP→FOUT 상태를 허구로 추가하지 않음; 마지막 publication pass에서 삭제된 relocation action을 참조하는 구 realization을 재결합. 실제 SliceLine에서 두 번째 오류 재현; 전체 공간 oracle 미완료 |
| `placement/selector/PolicyCandidateSelectionView.java`, `placement/CandidateSelections.java` PRESENT 극값, anchor-aligned 우선, `CandidateEffectKey` | FedAll/heuristic의 정책 내부 | **유지(공통 입력에 전파 금지)**: `CandidateEffectKey`가 receipt 자체를 포함하므로 현재 distinct receipt identity는 key에 남음. 정책 view만 축소; `PolicyQuotientIsolationTest`가 DP domain 및 analysis 불변을 검사. 전 context의 비용 우위 증명으로 사용하지 않음 |
| `placement/CandidateSelections.java` support reachability, candidate-domain 가능성, 선호도 상한, zero-target, 물리 emission 하한, canonical tie 및 interaction component | 해당 정책 Search 내부; 목적은 lexicographic PRESENT/anchor/physical emission/rank | **유지·부분 검증**: bounded literal Cartesian 대조 추가. fixture는 selected assignment와 일부 row domain만 열며 realization 가능성과 relocation subsolver는 production 사용; 따라서 독립 전체 공간/모든 prefix의 충분한 근거는 **미검증** |
| `placement/RelocationSelections.java`, `placement/LocalMaterializationSelections.java` exact demand, suppressible emission 및 local 하한 | 정책/물리 scorer 내부 | **잠정 유지·미검증**: bounded 대조/기존 회귀 통과. 모든 공유 action, non-zero optimum, suppression prefix의 독립 하한 oracle은 필요 |
| `fedCostBased/fedExact/ExactPhysicalModel.java` | analysis raw facts를 DP 변수로 구축 | **유지**: 정책 view가 DP 경로에 들어오지 않음(`PolicyQuotientIsolationTest`), 두 selector의 *도달 가능한 결합 계획 전체* 검증은 아님 |
| `fedCostBased/fedExact/ExactPhysicalReducedSolver.java` factor observation/tie가 같은 값의 quotient | DP-global selector 내부 | **유지·목적 한정**: active factor의 observation을 값 단위로 비교하고 tie를 검사; 기존 randomized reduced/unreduced 테스트. 대표값의 선택 동등성이지 구분되는 원래 물리 계획을 공통 공간에서 삭제할 허가는 아님 |
| `fedCostBased/fedExact/LocalCategoricalOptimizer.java` local state 대표값 축소 | DP-local selector 내부 | **잠정 유지·미검증**: 자신의 declared local state/cost 계약에만 적용, 완전 joint-optimum 보장은 없으며 공통 축소로 이동 금지 |

특정 SELECTOR가 보는 원본 전체 결합 공간을 row 수나 단일 winner만으로 증명할 수 없다. 누락 여부는 support, authority, 공유 action, 실행 배치가 포함된 decoded plan 집합으로 판정해야 한다.

## 2. S1–S5: 구현, 테스트, 미이행

1. `NeutralPlacementGraphBuilder`에서 CP/LOUT 소스 상태가 실제 domain에 존재할 때만 CP→FOUT materialization target을 추가하도록 수정했다. 기존 `IndependentPlanSpaceGenerationCompletenessTest#protectedTransientGenerationMatchesExplicitCompileAndRecompileUniverse`는 수정 전 `NoSuchElementException`, 수정 후 통과했다. 이는 privacy-safe FED-only 상태를 CP 실행 가능으로 위조하지 않는다.
2. 최종 publication closure가 relocation action을 다시 계산해 제거하면서 그 action을 요구하는 오래된 input binding을 그대로 발행하는 문제를 수정했다. 마지막 action domain에 없는 RELOCATION binding을 감지할 때만 기존 `bindRelocationCandidateRealizations`/logical transient binder를 재실행하고 fixed point를 다시 검사한다. 수정 전 `graphOwned=false, consumerCompatible=true, sourceCompatible=true` 오류가 **13열로 잘못 생성한 SliceLine 스크립트**에서 재현됐다. 따라서 이 재현만으로 올바른 14열 입력의 결함이라고 일반화하지 않는다. 후속 workload 결과는 §3에 별도로 기록한다.
3. 기존 `ProductionDecodedPlanSpaceCompletenessTest`는 사전 선언된 보호된 X/Y/U/V/D 물리 범위와 DIRECT binding으로 구성한 **서로 결합된 8개 계획**을 1,344개 finite-support Cartesian 조합의 missing/extra와 대조한다. X의 NATIVE_LINEAGE source row 전체 및 D의 B geometry row 전체를 각각 고의로 삭제하면, 서로 다른 결합 계획 4개가 누락되고 새 계획은 생기지 않음을 확인하도록 회귀를 확장했다. A/B worker geometry 및 두 입력 AND support가 검사되지만, relocation·derived-FOUT·shared physical action은 이 fixture의 명시적 비목표다. 새 `CandidateSelectionPruningOracleTest`는 B-22의 일부 variable row domain을 256개 이하 product로 열고 나머지는 정책 결과로 pin한다. 정책 filter, upper/lower bound, component 분리 없이 Cartesian 각 leaf를 방문해 정책의 winner tuple/receipt/action/emission count를 비교한다. **제약**: 새 oracle의 compatible 판단과 relocation/local physical emission scorer는 생산 코드를 호출하며, 이미 고정된 assignment와 pin된 도메인 외부는 증명하지 않는다.
   별도의 기존 `RelocationActionPlanSpaceCompletenessTest`는 ROW/COL 및 두 anchor 조합의 literal relocation 계획 집합과 action 한 개 삭제 변이, canonical 정책과 다른 cost를 준 최소 비용이 각기 다른 계획을 고르는 경우를 검사한다. 이는 relocation **부분 문제**의 반례이며 전체 planner A3의 두 상반된 목적함수 fixture를 대체하지 않는다.
   추가 privacy 회귀 그룹에서 `MixedPrivacyRelocationContractTest`의 기존 실패를 발견하고 기준 커밋의 builder로 임시 격리해 **동일 실패가 수정 전부터 존재함**을 확인했다. 실제 FED_ALL 결과는 C의 두 `PRESENT` 입력을 한 candidate support clause의 `[0:DIRECT protected A, 1:RELOCATION public B]`로 모두 증명하지만, 기존 test가 "두 입력이면 relocation receipt도 반드시 두 개"로 잘못 가정해 A의 direct support를 누락했다고 판정했다. 테스트 assertion만 **candidate input binding 두 개 + graph-owned public relocation choice 한 개** 검사로 바로잡아 일시 진단에서 통과시켰다. 다만 이 fixture의 B 소스가 `Privacy.PUBLIC`이므로 repository `AGENTS.md`에 따라 최종 일반 회귀 실행에서는 `@Ignore`로 **제외**한다. 두 보호 소스 부정 사례는 active이며 통과했다. production 후보 공간/privacy 제약은 변경하지 않았다.
4. 현행 테스트 `PolicyQuotientIsolationTest`는 FedAll→heuristic 및 heuristic→FedAll 순서를 교환한 뒤 analysis graph, fingerprint, receipt identity, Exact DP domain 불변을 검사한다. DP-global/DP-local 실행 양방향까지 독립 literal decoded 집합을 계산한 검증은 아니다.
5. 유효한 SLICELINE heuristic 120초 timeout의 Java thread sample은 `NeutralPlacementGraph.isRelocationActive` → `RelocationSelections.ExactEmissionScorer.optionAllowed`를 가리킨다. 공통 후보 삭제 대신 ExactEmissionScorer의 **한 leaf 안에서만** `ScoredOption`/`ScoredAction` 판정을 identity cache로 재사용하는 S5 변경을 구현하여 회귀 테스트와 별도 JAR `3442c440eadd9347d8822beffe7cac33868d7a4c22c68d651376a595794a4857`로 실험했다. 하지만 동일 SLICELINE heuristic의 120초 제한에 다시 걸렸고 baseline/캐시의 총 관측은 `133.109/133.165초`(둘 다 timeout+cleanup), 메모리 peak `2357937045/2428804005` bytes. **planning 완료도 성능 개선도 확인되지 않아 변경을 제외하고 코드를 되돌렸다.** 이 두 총 시간의 0.056초 차이를 실제 planning 개선/회귀율이라고 주장하지 않는다. S1에서 요청한 상반된 두 목적함수 fixture, 다른 worker/range/authority support pair의 독립 joint oracle, S4의 모든 prefix/completion 독립 oracle은 **미완료**.
6. publication의 오래된 relocation binding 검사에서 매 binding×매 action identity 탐색을 **action key identity index**로 바꾸는 S5 후보도 구현·검증했다. `PlacementAnalysis`와 같은 `==` 권한 semantics로 `Collections.newSetFromMap(new IdentityHashMap<>())`를 사용했다. 관련 5개 테스트 class와 JAR build 통과. 하지만 JAR `d76b709f785c6c65477b6f421eee00214bd42776325a5b3990e4be99a30d60b2` P1 heuristic single-cell pilot에서 같은 16GB/config의 **CandidateE2E Total** `14.691505→14.724891s`, wall `17.38→17.60s`였다. Analysis 하위 구간만 `9.912860→9.411677s`로 감소해도 전체 기획 시간이 빨라졌다고 할 수 없다. **전체 E2E 개선 증거가 없어 identity-index 최적화 역시 코드를 되돌렸다.** 단일 측정으로 통계적 회귀도 단정하지 않는다.

### 통과한 범위

최종 `mvn -q -DskipTests=false -Dtest=PolicyQuotientIsolationTest,ExactPhysicalReducedSolverTest,LocalCategoricalOptimizerTest,CandidateSelectionPruningOracleTest,IndependentPlanSpaceGenerationCompletenessTest,ProductionDecodedPlanSpaceCompletenessTest,RelocationActionPlanSpaceCompletenessTest,CandidateReceiptAssignmentCompletenessTest,GlobalReceiptPlanSpaceCompletenessTest,CandidateIncomingSupportCompletenessTest,PolicyFirstFeasiblePlacementSelectorTest,HeuristicNativeContinuationContractTest,FunctionReturnSinglePartitionFactsTest,MixedPrivacyRelocationContractTest,PrivacyDerivedMaterializationClosureTest,SharedPrivacyMovementLegalityAuditTest,PrivacyMovementCertificationTest,RelocationPrivacyIndexReuseTest,FederatedPlanLocalCostPrivacyConstraintTest test` 종료 0. Surefire XML: **19 class, 총 118 case, failure 0, error 0, ignored 6**(실행 112). `mvn -q -DskipTests package` 종료 0; 마지막 JAR hash가 Docker 관측에 사용한 `8f10f189…`와 일치함을 확인했다. `git diff --check` 종료 0. 오류를 내더라도 SystemDS CLI의 OS 종료 코드가 0일 수 있으므로 planning receipt 또는 로그의 오류 유무를 별도로 검사한다.

## 3. S6: worker=4 planning-only 관측

**단일 실행은 유의성 검정이 아니다.** P1/P2는 Docker 4 workers, `run_LAN_docker.sh --planning-only --skip-net-check --workers 4` 경로의 세 planner (`mkl-heuristic-first`, `mkl-exact`, `mkl-cost`)에서 receipt 성공, `runtime_executed=false`, `execution_seconds=0`을 확인했다. 원본 로그/receipts는 외부 workspace `.../final-selector-space-20260921/results-p1v3` 및 `results-p2`에 있다. 이 여섯 관측은 **두 번째 SliceLine binding fix 이전 JAR** sha256 `7c1169b5968848739257059de3d1d82c8a4d370d2d136e6e402e4cf7e772b08b`로 생성됐다. 최종 수정본 JAR `8f10f1891f3cd99bd492b1ad5ac4913aff054fe74afd2d1f16370a0acc4833df`와 동일 소스로 취급하지 않는다.

| workload | heuristic wall / CandidateE2E (s) | DP-global wall / CandidateE2E (s) | DP-local wall / CandidateE2E (s) |
|---|---:|---:|---:|
| P1 | 17.55 / 14.996039 | 17.25 / 14.276602 | 16.99 / 14.348034 |
| P2 | 3.67 / 2.045063 | 3.96 / 2.033397 | 3.90 / 1.970412 |

표의 wall은 coordinator 로그의 shell `real`이며 planning receipt의 complete initial planning과 동일한 경계가 아니다. 본 표의 수치는 이전 `36.633s` baseline 공식 18-pair와 JAR/입력/설정/측정 경계가 동일하지 않아 속도 향상률로 **비교하지 않는다**. 과거 `716s` diagnostic도 이 표의 분모가 아니다. P2의 기존 공개 메타데이터 opt-in을 그대로 유지하고 privacy-safe 조건을 임의 완화하지 않았다.

되돌린 두 S5 후보를 포함하지 않는 **최종 소스/JAR SHA-256은 `8f10f1891f3cd99bd492b1ad5ac4913aff054fe74afd2d1f16370a0acc4833df`**다. 단일 pilot마다 JAR hash를 분리해 `3442c440…`/`d76b709f…` 측정과 섞지 않는다. 기존 7c1169b5… 중간 결과와 같은 DML hash/JVM 16GB/worker 8GB/config로 `results-matched-p1`, `results-matched-p2`를 실행한 **최종 여섯 planning receipt 모두 `success=true`, `workers=4`, `runtime_executed=false`**다:

| 조건 | 이전 중간 JAR 7c1169b5… CandidateE2E / wall(s) | 최종 JAR 8f10f189… CandidateE2E / wall(s) | 해석 |
|---|---:|---:|---|
| P1 heuristic | 14.996039 / 17.55 | 14.691505 / 17.38 | 단일 관측, 약간 빠름 |
| P1 DP-global | 14.276602 / 17.25 | 14.951082 / 17.69 | 단일 관측, 느림 |
| P1 DP-local | 14.348034 / 16.99 | 14.627237 / 17.58 | 단일 관측, 느림 |
| P2 heuristic | 2.045063 / 3.67 | 2.334580 / 3.98 | 단일 관측, 느림 |
| P2 DP-global | 2.033397 / 3.96 | 1.900285 / 3.41 | 단일 관측, 빠름 |
| P2 DP-local | 1.970412 / 3.90 | 1.893675 / 3.69 | 단일 관측, 빠름 |

이는 공식 GLM B0 비교가 아니라 두 **중간 JAR**의 같은 조건 단일 관측이다. 방향이 섞이므로 시스템 전반 성능 개선을 주장하지 않는다.

최종 JAR에서 첫 P1/P2 harness 재측정(`results-final-*`)은 비교에서 제외한다. Compose가 JVM 플래그로 받는 환경변수는 `CAMPAIGN_COORDINATOR_JAVA_OPTS`인데 첫 재측정은 host의 `SYSTEMDS_STANDALONE_OPTS`만 설정했다. 결과적으로 coordinator가 **기본 `-Xmx8g`**로 시작하여 P1의 첫 수치는 기존 16GB와 자원 조건이 달랐다. P2의 세 조건은 기본 설정에 명시적 `-Dsysds.privacy.allowPublicRecodeMetadata=true`도 빠져 `FunOut M` privacy-safe placement 실패로 기록됐으며, 이를 최종 소스 결함으로 해석하지 않는다. `results-matched-*`에서 정확히 기존 coordinator 16GB/JVM 옵션, 기존 worker 8GB, 동일 opt-in을 고정해 다시 한 번 실행한다.

유효한 SliceLine **진단**에는 **SLICELINE** 397행×5열 worker=4 PRIVATE_AGGREGATE 데이터, ML 진단에는 P2P2D 데이터를 사용했다. 독립 Docker coordinator + 실제 worker1:8001...worker4:8004, `sysds.benchmark.compile_only=true`, `-noFedRuntimeConversion`으로 **runtime 없이** 컴파일/planning만 실행한다. 다만 repository `AGENTS.md`가 실험/최종 검증에 `run_LAN_docker.sh`만 허용하므로 이 **직접 Docker 결과는 병목/기능 진단 자료일 뿐 공식 S6 채택 증거가 아니다**. 직접 경로는 netem/일부 harness 측정 항목을 포함하지 않으므로 P1/P2 wall 또는 기존 기준과 동등한 절대 성능 비교에 쓸 수 없다. 실패나 timeout을 privacy-safe한 계획이 존재하지 않는다는 증명으로 취급하지 않는다.

**입력 정합성 발견:** 첫 SliceLine DML 생성에서 `D=13`, federated range의 종료 열 13이지만 실제 ADULT worker metadata의 `cols=14`였다. 이 입력의 heuristic 수정본 실행은 600초 경과 후 종료/cleanup 되었고 peak 약 2.362GB였으나 **입력 범위가 틀려 유효한 성능 관측에서 제외한다**. D/range를 14로 고친 ADULT 실행도 heuristic이 180초 timeout되었으며 이는 target `SLICELINE`과 **다른 dataset**이다. 같은 ADULT의 DP-global은 9.9초 내 완료되었지만 역시 target 비교에 넣지 않는다. 올바른 SLICELINE 397×5 fixture를 별도로 생성해 3 planner에 각각 한 번씩 실행한다. 이들 ADULT 로그를 후보 조합의 privacy infeasibility나 SLICELINE 성능이라고 발표하지 않는다.

| workload | heuristic | DP-global | DP-local | 근거/잔여 |
|---|---|---|---|---|
| ML/PCA (P2P2D) | 1회 성공 | 1회 성공 | 1회 성공 | 세 조건 모두 cache 변경 이전 JAR; runtime 실행 없음 |
| ML/GLM (P2P2D) | 120초 timeout, 미완료 | 120초 timeout, 미완료 | 120초 timeout, 미완료 | 세 조건 모두 cache 변경 이전 JAR; 이전 무-worker GLM `Connection refused`는 무효 |
| SliceLine (SLICELINE 397×5) | 120초 timeout; planning completion 없음 | 1회 성공 | 1회 성공 | 세 조건 모두 cache 변경 이전 JAR; ADULT 로그 제외 |

같은 고정 JAR `8f10f189…`에서 완료된 **직접 Docker 단일 관측** (`CandidateE2E Total / coordinator host wall / sampled peak RSS`)은 SLICELINE DP-global `5.707244s / 9.299129s / 2232MiB`, DP-local `5.897192s / 9.599626s / 2249MiB`; PCA heuristic `2.517815s / 5.571035s / 698MiB`, DP-global `2.711355s / 6.461323s / 651MiB`, DP-local `2.626626s / 5.575100s / 671MiB`이다. SLICELINE heuristic은 `HOST_CELL_WALL_SECONDS=133.109364`(120초 제한 + cleanup), peak 2249MiB, *CandidateE2E 미완료*다. GLM heuristic/global/local 역시 각 약 134.55/134.38/134.30초(120초 제한+cleanup), peak 약 10.6GiB에서 *CandidateE2E를 발행하지 못했다*. GLM DP-local의 JVM sample은 `NeutralPlacementGraphBuilder.bindDirectNativeCandidateRealizations`/`closeCfgTransientCandidateDependencies` 내부에서 실행 중이어서 이 한 sample은 공통 builder 병목과 일치한다; 전체 GLM 프로파일/세부 원인 증명은 아니다. 완료되지 않은 시간을 완성된 E2E의 수치 비교 근거로 쓰지 않는다. 정상 종료 여부는 DML 오류 marker도 점검했으며, 해당 직접 경로에는 공식 planning receipt 파일이 없어서 위 `Compile Phase ... Total`은 그 대체 측정임을 명시한다.

Peak RSS는 현재 P1/P2 harness가 제공하는 메모리 구간과 직접 Docker coordinator의 sampling 경계가 달라 미확인이다. 같은 조건 baseline 1회도 마련되지 않아 before/after 메모리/시간 비율을 주장하지 않는다.

## 4. A1–A12 판정 및 후속 조치

| 기준 | 현 상태 |
|---|---|
| A1 | **부분**: 보호 fixture의 8개 literal joint plan missing/extra 및 소스 layout/소비자 geometry 삭제 변이 각각 4개 누락 감지; relocation/공유 action/authority 조합은 부족 |
| A2/A4 | **부분**: policy/Exact 입력 격리 및 FedAll/heuristic 순서 확인; 전체 DP/반복 decoded 공간 불변 미확인 |
| A3 | **부분**: literal relocation 부분 문제에서 canonical winner와 anchor 비용 winner가 달라짐; 전체 selector의 실행시간 대 통신량 반대 winner 미검증 |
| A5 | **부분**: 감사한 코드 경계에 새로운 공통 정책 축소 없음; 모든 builder/index 경로 전수 증명 불가 |
| A6 | **미완료**: common quotient 전체의 geometry/worker/authority/support/share identity 복원 proof 부재 |
| A7/A8 | **부분**: 제한된 Cartesian winner와 기존 randomized quotient 테스트; non-zero/suppression/coupling/prefix 전체 독립 oracle 부재 |
| A9 | **부분**: 보호 상태 회귀 및 두 보호 소스 부정 사례 통과; PUBLIC 소스 fixture는 policy에 따라 제외. SliceLine/GLM timeout을 불법 계획이라고 추정하지 않음 |
| A10 | **부분**: 19 class/112실행·6 ignored, Java compile/package, diff 검증 통과; repo-wide 별도 lint/정적 분석 미실행 |
| A11 | **미완료**: 허용된 공식 harness는 최종 JAR P1/P2 6/6 성공만 해당. 직접 Docker 진단 SLICELINE 2/3, GLM 0/3(120초 timeout), PCA 3/3은 공식 S6 증거가 아님. 따라서 4대상 12칸 중 공식 확인은 6칸; GLM 공식 동일 조건 B0 및 전체 초기 planning/RSS 비교 부재 |
| A12 | **부분**: 지정 경계의 목록과 처분 기록; 미검증 항목을 미검증으로 분리 |

**다음 안전한 작업:** SliceLine/ML의 최종 JAR planning-only 상태를 확인해 성공/실패와 후보 탐색 비용을 분리하고, 그 다음 상반된 목적함수·non-zero emission·share/suppression·authority를 포함한 독립 literal decoded universe를 먼저 고정한다. 그 전에는 selector 내부의 미검증 pruning을 전 범위 정확성 보장으로 승인하지 않는다. 60초 달성도 합법 후보 삭제의 정당화가 아니다.
