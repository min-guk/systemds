# G009 일반화 정확성·GLM 회귀 대응 실행 보고서

- 실행일: 2026-09-22.
- 근거 계획: [G009_GENERALIZED_CORRECTNESS_AND_GLM_REGRESSION_RESPONSE_2026-09-22.md](G009_GENERALIZED_CORRECTNESS_AND_GLM_REGRESSION_RESPONSE_2026-09-22.md) §5, §8–9.
- 범위: **planning-only**. 학습 runtime 완료나 임의 DML의 완전성 인증은 이 보고서의 결과가 아니다.

## 결론과 완료 경계

기존 oracle을 다시 만들지 않고 부족했던 ROW 전체 행·동적 열 slice의 **조건부 행축 의미론**과 보호된 함수 negation/loop reader assertion을 보강했다. 공통 builder의 권한 검사는 유지하면서, 변경 없는 privacy fact 재구성과 privacy 이후 중복 closure를 줄였다. 프로파일링으로 발견한 WDivMM 전용 whole-program 인덱스의 일반 HOP별 재생성을 막았다. 후보를 삭제하거나 selector의 정책을 공통 합법성으로 옮기지 않았다.

GLM DP-local 개발 pilot은 직전 정확성 수정본 53.786597초에서 46.946435초로 감소했지만, **고정 JAR의 최종 검증에서는 50.414815초**였다. 같은 production class bytes임에도 두 단회 관측이 3.468381초 다르다. 최종 수치는 이전 성공 기준선 45.141704초보다 **5.273111초(11.68%) 느리다.** 따라서 성능 회귀를 해소했다고 주장하지 않는다. 이 수치들은 단회 관측이며 통계적 신뢰 구간이 아니다.

## 재사용한 oracle와 남은 UNKNOWN

| 경계 | 재사용/보강한 증거 | 이번 판정의 한계 |
|---|---|---|
| full-row dynamic column slice | `MatrixCapabilityOracleTest.validDynamicColumnPreservesOnlyTheExactRowAxis`에서 4개 불균등 worker row 범위·유효/미상/무효 column bounds·COL/부분 행의 반례를 확인. `StepLmDynamicLoopPlacementTest`가 보호된 실제 compiler graph의 ROW FOUT reader와 초기 정의+두 backedge 관계를 확인 | 새 `checkFullRowColumnSliceRowAxis`는 row-axis 보존만 조건부 SUPPORTED. 값·privacy·전체 joint plan·worker runtime 결과는 인증하지 않음 |
| transpose→negation/function | 기존 `MatrixCapabilityOracleTest.transposeSwapsAxesAndRejectsPart` 재사용. `StepLmNegatedTransposePlacementTest`의 비어 있지 않은 graph만 검사하던 함수 사례를 정확한 보호 negation FOUT 존재 검사로 변경 | 모든 함수 합성/중간 producer 배치의 독립 전수 판정은 아님 |
| privacy 철회→physical rebuild | `PublicationSupportClosureTest`의 expired clause 제거·AND 의무 실패 테스트와 `PrivacyDerivedMaterializationClosureTest` 재사용. 변경 없는 fact의 **동일 emission 객체**일 때만 재사용하고, 다른 realization authority이면 새 fact를 만드는 assertion 추가 | 새 source·삭제·교체와 loop SCC 전체의 독립 의미론 oracle는 아직 UNKNOWN |
| 순환 joint legality | 기존 `BuilderOracleTest`의 B-18/19/20과 `PrimitivePlanEnumeratorTest` 재사용 | `JointPlanLegalityChecker`의 `S-CYCLE/UNKNOWN`, certification의 `S-COVERAGE/UNKNOWN`은 그대로 유지. fixture PASS를 전체 loop/plan-space 인증으로 승격하지 않음 |

`OracleIndependenceContractTest`는 oracle와 production의 의존성 경계를 재확인한다. 두 경로의 일치만으로 공유된 의미론 오류가 없다는 증거로 사용하지 않았다. 기존 `IndexingFEDRightIndexBoundsTest`는 helper 수준이며 FED worker의 실제 출력 값/FederationMap까지 검증했다는 뜻이 아니다. 기존 single-worker ALS fixture의 `PRIVATE_AGGREGATE` TRead 실패도 이 작업에서 고치지 않았다.

## 구현과 채택/제외

| 항목 | 처리 | 이유/검증 |
|---|---|---|
| 정확한 oracle·assertion 보강 | 채택 | 기존 `MatrixCapabilityOracle`, StepLM/Publication 테스트만 확장. UNKNOWN을 LEGAL/ILLEGAL로 바꾸지 않음 |
| privacy fact 객체 재사용 | 채택 | 필터·source closure를 **모두 수행한 뒤** emission 순서와 객체 identity가 같을 때만 같은 immutable fact 반환 |
| privacy가 아무 node/fact도 바꾸지 않았을 때 재-grounding 생략 | 채택 | 이미 완료된 CFG/direct/physical closure 다음에 `List.equals`로 정확한 상태를 비교. 철회가 있으면 기존 반복과 최종 publication 검사를 그대로 수행 |
| WDivMM source-owner 사전 판별 | 채택 | GLM 프로파일에서 일반 노드마다 전체 graph index 생성이 관측됨. 내부 exact matcher의 필수 조건인 transpose→단독 부모 matrix multiply만 앞에서 검사; 합법 후보는 변경하지 않음 |
| WDivMM 대상이 없을 때 closure 전체 no-op | **제외·되돌림** | 단회 GLM 48.907458초로 직전 46.946435초보다 느림. 원인을 확정하지 못했으므로 채택하지 않음 |
| owner/SCC revision 기반 캐시·증분 인덱스 | 미완료 | 현재 oracle가 authority/absence 의존성까지 독립적으로 증명하지 않아 캐시 무효화의 안전성을 주장할 수 없음. 시간 이득만으로 채택하지 않음 |
| seed/backedge 누락·privacy 재검사 생략의 별도 mutant 실행 | 미완료 | 기존 테스트는 모든 reaching definition의 관계와 expired conjunct를 검사하지만, 세 가지 생산 코드 mutant를 실제로 주입해 fail 여부를 모두 확인한 것은 아님 |
| 전체 HOP/DML 완전성·학습 runtime | 미완료/범위 밖 | 독립 R의 `S-CYCLE`, `S-COVERAGE`와 worker runtime 증거가 남음 |

## 측정 경계와 단회 결과

동일 `P2P2D`, GLM, worker=4, LAN, privacy constraint, seed `2026072701`, `mkl-cost` DP-local, 공식 Docker `--planning-validation --planning-only`, coordinator 16GiB/8 active processors를 사용했다. manifest SHA-256은 `e8bb6a11c7ff5cea4cdb57e51e67c14b5abe8eb80fc4ca60f3aae792ad5a040d`, compile config SHA-256은 `e24c3b4a4e6675af6c6af0daa7e46ea79a1299dfb922c32594b17b35e89b40e7`다. 표의 **전체 초기 planning**, CandidateE2E, 공통 analysis는 포함 관계가 있으므로 합산하지 않는다.

| JAR/후보 | 전체 초기 planning 초 | CandidateE2E 초 | analysis 초 | 판정 |
|---|---:|---:|---:|---|
| 이전 성공 `805095…` | 45.141704 | 43.154443 | 36.361183 | 성능 참고 기준; 현재 후보 공간의 정확성 기준은 아님 |
| 수정 직후 `f143c281…` | 53.786597 | 52.479944 | 45.039488 | 정확성 보존 참고 기준 |
| privacy 동일성 재사용+중복 closure 방지 `45e814…` | 51.761939 | 49.847766 | 42.465785 | 직전 대비 개선, 여전히 이전보다 느림 |
| WDivMM transpose 사전 판별 `ef7487…` | 48.783936 | 46.827076 | 37.350440 | 개선 |
| matrix-multiply/단독 부모 사전 판별 `c6da3b…` | **46.946435** | **45.084621** | **36.665292** | 채택할 production class bytes |
| 전체 no-op 시도 `ccdb2c…` | 48.907458 | 46.953383 | 38.365126 | 제외·되돌림 |

`c6da3b…`와 최종 재패키징 JAR의 `PlacementCostSemantics.class` 및 `NeutralPlacementGraphBuilder.class` 바이트는 동일하다. 최종 JAR SHA와 전체 56조건 결과는 아래에 별도로 기록한다. 프로파일링 JFR은 병목 진단용으로만 사용했으며, 그 실행 시간을 비계측 성능 수치로 채택하지 않았다. 실패까지의 예전 716초·36.633초 CandidateE2E·학습 runtime을 현재 전체 초기 planning과 섞지 않는다.

### 고정 최종 JAR의 GLM 4개 planner

최종 JAR SHA-256은 `9a3cd7abc4cecfb851bcc1b79bd840eb6b84fb061786c99b90ccc36cd57da407`이다. 재패키징 전 pilot `c6da3b…`와 JAR entry 내용은 모두 같고, 추가된 것은 planning과 무관한 `scripts/fedplanner/verify_e_factor_artifact.py` 1개였다. 같은 설정에서도 실행별 시스템/JIT 변동이 있으므로 아래 최종 결과를 pilot의 좋은 수치로 대체하지 않는다.

| planner | 이전 성공 `805095…` 초 | 직전 수정 `f143…` 초 | 최종 `9a3…` 초 | 이전 성공 대비 |
|---|---:|---:|---:|---:|
| FedFirst | 45.114049 | 53.290689 | **45.108064** | -0.006초 (단회 동률 수준) |
| AggLocal | 46.476850 | 53.845699 | **48.258923** | +1.782초 |
| DP-local | 45.141704 | 53.786597 | **50.414815** | +5.273초 |
| DP-global | 44.118993 | 57.674614 | **47.127119** | +3.008초 |

네 GLM receipt 모두 `success=true`, `runtime_executed=false`, worker=4이며 전체 초기 planning은 60초 미만이다. 그러나 **성능 기준선 회복은 FedFirst도 오차 수준일 뿐, 나머지 3개는 미달**이다. 최종 DP-local CandidateE2E는 48.760528초, analysis는 38.098883초다. 동일 code에 가까운 pilot의 analysis 36.665292초와 차이가 있어 남은 회귀를 특정 한 함수의 확정적 인과로 단정하지 않는다.

## 검증 상태

- 표적 Maven 테스트 8개 클래스: **70 통과, 1 기존 skip, 오류 0**. `MatrixCapabilityOracleTest`, `OracleIndependenceContractTest`, `StepLmNegatedTransposePlacementTest`, `StepLmDynamicLoopPlacementTest`, `PublicationSupportClosureTest`, `PrivacyDerivedMaterializationClosureTest`, `NeutralPlacementFixedPointCompositionTest`, `NativePlacementContinuityTest`.
- 기존 oracle·runtime bounds helper 재사용 테스트 3개 클래스: **42 통과, 오류 0**. `PrimitivePlanEnumeratorTest` 11개, `BuilderOracleTest` 29개, `IndexingFEDRightIndexBoundsTest` 2개. 두 묶음 합계 **112 통과, 기존 skip 1**이며, 전체 repository test suite를 돌렸다는 뜻은 아니다.
- Maven package: 성공. 수정 후 새로 드러난 독립 `PlanSpaceComparisonIdentity.java` 테스트 컴파일 shadowing(`compiled` 변수 중복)은 이름만 `expressionMatches`로 고쳐 빌드 가능하게 했다. 이 파일의 다른 기능은 변경하지 않았다.
- 기존 `CampaignBG014AlsPartitionedComputeCostRedTest`: 11개 중 9개가 `PRIVATE_AGGREGATE` TRead의 privacy-safe placement 부재로 오류. 기존 문서에 기록된 별도 single-worker ALS 문제와 같은 종류이며 이번 GLM 전용 사전 판별의 성공 증거로 세지 않았다.
- 최종 56조건 worker4 공식 Docker 결과: **56/56 정상 planning-only receipt**. 10 ML(`glm`, `steplm`, `pca`, `als`, `alsCG`, `kmeans`, `gnmf`, `gmm`, `logreg`, `l2svm`) + P1/P2 + 작은 SliceLine `ADULT`/`COVTYPE`, 각 4 planner를 각각 한 번 실행했다. 모든 행 `rc=0`, `success=true`, `runtime_executed=false`, `execution_seconds=0`, worker=4, 금지된 출력 부재가 재확인됐다. `progress.log`에 실패 marker가 없고 전체 JAR SHA는 시작/종료 시 동일하다. 상태표: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-generalized-final56-9a3/status.tsv`.
- 최종 네 GLM의 compile-config SHA-256은 같은 planner의 `f143…` 결과와 각각 일치한다. 입력 manifest도 고정돼 있다. 단, host/JIT 노이즈를 반복 측정으로 추정한 실험은 아니다.
- 실제 FED indexing runtime 의미론: 기존 `FederatedIndexingLayoutPlanningTest` 재실행은 **전체 PASS 아님**. 원본 fixture 설정 `compile_fed_all`은 현 enum에 없어서 첫 시도는 compile 단계에서 중단됐다. 테스트에만 현 `single-pass` 설정을 임시 적용한 진단에서는 실제 worker 실행과 로컬 기준값 `compareResults`가 통과하고 `fed_rightIndex` 4회가 관측됐지만, 기존의 `fed_leftIndex` 강제 assertion은 실패했다(현재 선택은 local `leftIndex` 4회). 임시 설정 변경은 되돌렸다. 따라서 이번 관측은 right-index 값의 부분 증거일 뿐, 해당 테스트 전체나 ROW FederationMap 전이의 통과 증거가 아니다. 로그: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-generalized-20260922/runtime-indexing-test*.log`.

## 증거와 다음 결정

- 단회 pilot 결과·JFR: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-generalized-20260922/`.
- 최종 JAR, manifest, 조건별 receipt/checksum: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009-generalized-final56-9a3/`.
- 60초 단회 통과와 이전 기준선보다 빠름은 별도 판단이다. 후자는 아직 달성하지 못했다. 다음 성능 작업은 이미 작은 phase 분석을 되풀이하지 말고, 남은 ~1.8초 차이를 host 변동과 필수 후보 비용으로 분리한 뒤 authority-safe 재사용을 작은 독립 반례로 먼저 증명해야 한다.
