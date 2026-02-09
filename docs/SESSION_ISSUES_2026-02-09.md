# SESSION ISSUES (2026-02-09)

## 이슈 1: MinST가 OPTIONAL 입력에 업로드 힌트를 강제로 남겨 `CP->FOUT/fed_fout`가 과삽입됨

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `fed_min_st_cut`
  - 워크로드/프로파일: `lm`, `lan` / `wan_light`
  - 로그: `experiments/results/fed2/mkl-min-st-cut/lm_*_lan.log`, `experiments/results/fed2/mkl-min-st-cut/lm_*_wan_light.log`
- **재현 절차**:
  1. MinST 실행 로그에서 Oracle 입력 타입과 `CP->FOUT insert` 횟수를 비교한다.
  2. 같은 입력에서 `mkl-cost` 대비 `mkl-min-st-cut`의 `fed_fout` 삽입 및 Fed Put 수가 급증하는지 확인한다.
- **관측 증상**:
  - MinST에서 OPTIONAL 입력 경로에서도 업로드가 비용 모델에 반영되어, 반복 루프에서 `CP->FOUT/fed_fout`가 과삽입됨.
  - 결과적으로 Fed Put count/bytes와 `fed_fed_fout` heavy hitter가 증가.
- **원인 분석**:
  - `FederatedPlanMinSTRewire`가 OPTIONAL 입력(`InputRequirement.OPTIONAL`)에 대해서도 `markParentChildUploadHint`를 등록.
  - `FederatedPlanMinSTGraph.addParentChildNetEdge`가 이 hint를 근거로 upload hyperedge를 추가하여 불필요한 업로드 경로를 MinST에 강제.
- **해결 요약**:
  - `FederatedPlanMinSTRewire`에서 OPTIONAL 입력 경로의 `markParentChildUploadHint` 등록을 제거.
  - MinST가 OPTIONAL 입력을 기본적으로 local로 두고, 부모 실행 결정 시점에만 업로드를 선택하도록 정책 정렬.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest,FederatedRefedPolicyTest test`
  - 결과:
    - `FederatedPlannerFallbackIntegrationTest.testMinSTOptionalInputDoesNotMarkUploadHint` 통과
    - `FederatedRefedPolicyTest` 포함 전체 테스트 통과
- **잔여 이슈**:
  - 실험 로그 기준 성능 회복 확인(특히 `lm`의 `lan`/`wan_light`)은 별도 실험 재실행 필요.
- **잠재 회귀 위험**:
  - 특정 연산에서 OPTIONAL 입력 업로드가 실제로 필요한 경우 비용이 과소평가될 수 있음.
  - 감지 방법: MinST hyperedge/rewire 테스트에서 OPTIONAL 입력 경로 검증 추가.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 플래너( MinST rewire )의 과도한 업로드 가정이 원인이므로 플래너 정책을 수정.

## 이슈 2: MinST가 refed 불가능 벡터 홉에도 `BROADCAST`를 강제해 WAN에서 FED 연산이 CP로 기울어짐

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `fed_min_st_cut`
  - 워크로드/프로파일: `pca`, `wan_light`
  - 로그: `experiments/results/fed2/mkl-min-st-cut/pca_*_wan_light.log`
- **재현 절차**:
  1. Oracle 로그에서 `hop=227 (ua(meanC))`가 `exec=FED`임을 확인한다.
  2. 같은 실행의 최종 MinST 계획/LOP에서 `hop=227`이 `CP?uacmean`으로 배치되는지 확인한다.
- **관측 증상**:
  - MinST에서 `hop=227`이 CP로 선택되어 `uacmean`의 CP heavy hitter 시간이 급증.
  - DP(`mkl-cost`) 대비 WAN_LIGHT 성능 악화.
- **원인 분석**:
  - `FederatedPlanMinSTRewire`의 벡터 처리에서 `canGenerateCpfoutCandidate==false`인 홉까지 `fType=BROADCAST`로 강제.
  - 해당 값이 MinST의 비용/배치 판단에 들어가 WAN 프로파일에서 CP 선택이 과도하게 유리해짐.
- **해결 요약**:
  - 벡터 홉에서 `canGenerateCpfoutCandidate(...)`가 거짓이면 FType 강제 오버라이드(BROADCAST/axis)를 하지 않도록 변경.
  - 즉, refed 불가능한 경우 Oracle/기존 FType을 유지해 불필요한 BROADCAST 바이어스를 제거.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest,FederatedRefedPolicyTest test`
  - 결과:
    - `FederatedPlannerFallbackIntegrationTest.testMinSTVectorWithoutRefedDoesNotForceBroadcast` 통과
    - 전체 테스트 통과
- **잔여 이슈**:
  - `pca` WAN_LIGHT 실험의 실제 성능 회복 여부는 end-to-end 재실행으로 추가 확인 필요.
- **잠재 회귀 위험**:
  - 일부 벡터 연산에서 BROADCAST 비용 반영이 약해져 FED 선호가 과도해질 수 있음.
  - 감지 방법: MinST 벡터/브로드캐스트 관련 테스트 및 LM/PCA 회귀 실험 비교.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 fallback 없이, 플래너 입력 타입 결정(Oracle 정렬/브로드캐스트 힌트)만 수정.

## 이슈 3: 비용 fallback의 injected default가 고정 8B/cell이라 ValueType별 메모리 특성을 반영하지 못함

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `fed_dp`, `fed_min_st_cut` (공통 `FederatedCostModel` 경유)
  - 대상 로직: `getEffectiveInputMemEstimate`, `getEffectiveOutputMemEstimate`
- **재현 절차**:
  1. 비용 모델 fallback이 필요한 홉(메모리 추정치가 0/미정인 홉)을 구성한다.
  2. `FederatedCostModel`의 fallback이 ValueType과 무관하게 항상 동일 injected default(`8B`)를 사용하는지 확인한다.
- **관측 증상**:
  - BOOLEAN/FP32/STRING 등 ValueType 차이가 있어도 fallback이 동일 셀 크기를 사용해 비용 모델 편향 가능성이 존재.
- **원인 분석**:
  - `FederatedCostModel`이 고정 상수 `DEFAULT_MEM_ESTIMATE_PER_CELL = 8.0`만 사용하고, `Hop.getValueType()` 기반 분기를 하지 않음.
- **해결 요약**:
  - `FederatedCostModel`에 `Hop.getValueType()` 기반 injected default 분기를 추가.
    - BOOLEAN: `1B`, INT32/HASH32/UINT 계열: `4B`, INT64/HASH64: `8B`, FP32: `4B`, FP64: `8B`, STRING: `100B` 기준.
  - `getEffectiveOutputMemEstimate`가 고정 8B 대신 ValueType별 default를 사용하도록 변경.
  - `getEffectiveInputMemEstimate`는 부모 홉 공통 default 대신 각 입력 홉의 ValueType 기반 output fallback을 합산하도록 변경(중복 입력 처리 유지).
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedCostModelFallbackTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedCostModelFallbackTest,FederatedPlanMinSTHyperedgeTest,FederatedPlannerFallbackIntegrationTest,FederatedRefedPolicyTest test`
    - `mvn -q -DskipTests package`
  - 결과:
    - `FederatedCostModelFallbackTest`의 타입별 injected default 검증 테스트 통과
    - MinST/DP 관련 회귀 테스트 통과
    - 패키지 빌드 성공
- **잔여 이슈**:
  - UNKNOWN 타입은 기존 호환성을 위해 8B fallback을 유지했으므로, 필요 시 별도 경험값/프로파일 기반 튜닝 여지 존재.
- **잠재 회귀 위험**:
  - ValueType 분기 도입 시 기존 8B 가정 대비 네트워크/연산비 가중치가 바뀌어 일부 워크로드의 planner 선택이 변할 수 있음.
  - 감지 방법: DP/MinST 회귀 테스트 + LM/PCA LAN/WAN 로그 비교.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 fallback이 아니라 플래너 비용 모델(공통 cost helper) 수정.

## 이슈 4: MinST에서 planner가 BROADCAST를 계획해도 register 단계에서 `fed_refed`로 바뀌어 DP와 실행 패턴이 어긋남

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - 비교 대상: `mkl-cost(DP)` vs `mkl-min-st-cut`
  - 워크로드/프로파일: `pca`, `wan_mid`
  - 로그:
    - `experiments/results/fed2/mkl-cost/pca_P2P_coordinator_mkl-cost_20260209_030122_2035026_wan_mid.log`
    - `experiments/results/fed2/mkl-min-st-cut/pca_P2P_coordinator_mkl-min-st-cut_20260209_044507_2375513_wan_mid.log`
- **재현 절차**:
  1. 동일한 홉(`226/232`)에 대해 DP와 MinST 로그의 CP->FOUT 삽입 instruction(`fed_fout` vs `fed_refed`)을 비교한다.
  2. MinST Hop table에서 해당 홉의 planned FType이 `BROADCAST`인지 확인한다.
- **관측 증상**:
  - MinST Hop table은 `226/232`를 `FType=BROADCAST`로 계획했지만, register 단계에서는 `fed_refed`가 삽입되어 DP(`fed_fout`)와 materialization 방식이 달라짐.
  - 결과적으로 동일 workload에서 DP 대비 MinST의 WAN_MID 실행시간이 더 길어지는 편차가 관측됨.
- **원인 분석**:
  - `registerCpfoutWithSelection(...)`가 planner가 미리 확정한 hop FType(`BROADCAST`)를 우선하지 않고 anchor 기반 일반 분기로 들어가 `fed_refed`를 선택할 수 있었음.
- **해결 요약**:
  - `FederatedRefedPolicy.registerCpfoutWithSelection`에 정책 추가:
    - **planner가 hop FType을 `BROADCAST`로 확정한 CP->FOUT 후보는 항상 `fed_fout(BROADCAST)` materialize로 고정**
    - 해당 경우 `FederatedRefedRegistry`는 비우고 `FederatedFoutMaterializeRegistry`만 등록.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedRefedPolicyTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedRefedPolicyTest,FederatedPlannerFallbackIntegrationTest test`
  - 결과:
    - 신규 회귀 테스트 `testPlannedBroadcastPrefersFoutMaterializeOverRefed` 통과
    - 기존 fallback integration 테스트와 함께 전체 통과
- **잔여 이슈**:
  - `lm/pca` WAN_MID end-to-end 성능 수치 재확인은 동일 환경 재실행(노이즈 포함)으로 추가 확인 필요.
- **잠재 회귀 위험**:
  - planner가 BROADCAST를 과도하게 부여하는 케이스가 있다면 `fed_fout` 경로 비중이 증가할 수 있음.
  - 감지 방법: planner 로그의 hop FType(BROADCAST) 비율과 `fed_fout/fed_refed` heavy hitter 비율을 함께 모니터링.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 우회 없이, 플래너가 확정한 FType을 register/rewire 단계에서도 일관되게 보존하도록 정책 정렬.

## 이슈 5: MinST/DP의 LOUT hop FType 힌트 유실로 `fed_refed`가 재선택됨

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `fed_min_st_cut`, `fed_dp`
  - 워크로드/프로파일: `lm`, `pca` / `wan_mid`
- **재현 절차**:
  1. MinST 계획 표에서 LOUT hop이 `FType=BROADCAST`로 보이는 케이스를 확인한다.
  2. 동일 hop의 LOP 삽입 결과가 `fed_fout(BROADCAST)`가 아니라 `fed_refed`로 나오는지 확인한다.
- **관측 증상**:
  - planner 단계에서 BROADCAST 의도가 있었지만 register 단계에서 힌트를 못 보고 `fed_refed`를 재선택했다.
  - 특히 dims 미확정(unknown) 경로에서 dim/axis mismatch 검출이 약해져 `fed_refed`로 기울었다.
- **원인 분석**:
  - MinST의 `buildPlannedFTypeMap`이 LOUT hop에서 `cpFoutType`만 사용해, 값이 null이면 FType 힌트가 유실되었다.
  - DP도 LOUT hop의 FType을 전부 제거해 register 단계에서 planner 의도를 재사용하기 어려웠다.
- **해결 요약**:
  - MinST:
    - LOUT hop의 `cpFoutType`이 null일 때 `vertex.getDataType()`을 fallback 힌트로 유지.
  - DP:
    - non-transient LOUT hop의 FType 힌트를 유지해 CP->FOUT register가 planner 결정(BROADCAST/ROW/COL)을 참조 가능하도록 정렬.
    - transient read/write는 기존처럼 제외해 local TR/TW를 federated source로 오판하지 않도록 보존.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCut.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlanMinSTHyperedgeTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedPlanMinSTHyperedgeTest,FederatedRefedPolicyTest,FederatedPlannerFallbackIntegrationTest test`
    - `mvn -q -DskipTests package`
  - 결과:
    - 신규 회귀 테스트 `testBuildPlannedFTypeMapKeepsLoutHintWhenCpFoutTypeMissing` 통과
    - 관련 회귀 테스트/패키지 빌드 통과
- **잔여 이슈**:
  - end-to-end 성능 수치(lm/pca wan_mid)는 동일 환경에서 실험 재실행 후 재확인 필요.
- **잠재 회귀 위험**:
  - non-transient LOUT 힌트 유지로 planner 영향이 기존보다 넓어져 일부 케이스에서 `fed_fout` 비율이 증가할 수 있음.
  - 감지 방법: planner FType 분포와 `fed_fout/fed_refed` heavy hitter를 함께 비교.
- **의사결정 근거(oracle/런타임/플래너)**:
  - runtime 우회가 아니라 planner가 만든 FType 힌트 전달 경로를 보강해 register 결정과 일치시킴.

## 이슈 6: DP에서 OPTIONAL 완화 후 FED 가능성 게이트가 과완화되어 REQUIRED 입력 검증이 누락될 위험

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `compile_cost_based` (DP)
  - 테스트/검증: `FederatedPlanCostEnumeratorTest`, `FederatedPlannerFallbackIntegrationTest`
- **재현 절차**:
  1. DP 열거기 변경(diff)에서 `canSatisfyFedInputs=true`로 고정된 상태를 확인한다.
  2. 동일 코드에서 REQUIRED/OPTIONAL 판정이 후보 조합별이 아니라 사전 고정값(입력 리스트 기준)으로 계산되는지 확인한다.
- **관측 증상**:
  - FED 계획 생성 시 REQUIRED 입력의 materialization(anchored CP->FOUT 가능성) 검증이 약해져, 불가능한 FED 계획이 후보에 남을 수 있는 위험이 존재.
  - OPTIONAL 완화 목적과 별개로, REQUIRED 입력까지 동일하게 완화되는 부작용 가능성이 확인됨.
- **원인 분석**:
  - FED 게이트가 `canSatisfyFedInputs=true`로 고정되어 REQUIRED 입력 feasibility 검증이 사실상 제거됨.
  - 입력 요구사항(`getInputRequirementForFedExec`)이 후보별 `fedInputTypeMap`이 아니라 사전 계산 결과에 의존해, 조합별 판정 정밀도가 떨어짐.
  - 부모 입력 인덱스를 HopID 단일 매핑으로 잡으면 동일 Hop 중복 입력 시 위치별 requirement 판정이 틀릴 수 있음.
- **해결 요약**:
  - 후보 조합별(`selectedBits`, `fedInputTypeMap`)로 REQUIRED/OPTIONAL을 재판정하도록 변경.
  - FED 입력 feasibility는 후보별 `fedInputTypeMap`으로 `FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(...)`를 호출해 REQUIRED 입력의 CP->FOUT 가능성(앵커 포함)을 검증하도록 복원.
  - 부모 입력 인덱스는 `resolveParentInputIndices(...)`로 occurrence-aware 매핑하여 중복 입력 위치를 안전하게 해석.
  - 비용 계산에서 OPTIONAL 입력도 FED 실행 시 LOUT->FED forwarding(브로드캐스트 업로드) 비용을 항상 반영해 네트워크 비용 누락을 방지.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -DskipTests compile`
    - `mvn -q -Dtest=FederatedPlanCostEnumeratorTest,FederatedPlannerFallbackIntegrationTest test`
  - 결과:
    - 모두 통과
- **잔여 이슈**:
  - REQUIRED 다중 입력(anchor compatibility) 시나리오에 대한 DP 전용 회귀 테스트가 부족함.
- **잠재 회귀 위험**:
  - 특정 연산에서 REQUIRED/OPTIONAL 경계가 애매한 경우 비용/게이트 판정 편향 가능성.
  - 감지 방법: 다중 REQUIRED 입력 + 앵커 불일치 케이스를 DP 단위 테스트로 추가.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 우회 없이 플래너(DP) 단계에서 후보 게이트/비용 모델을 수정해 런타임 제약 반영을 복원.

## 이슈 7: `FederatedPlanTReadWriteConsistencyTest`의 MinST hard-edge 기대와 현재 MinST 정책 불일치

- **상태**: 진행중
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - 테스트: `org.apache.sysds.test.component.federated.FederatedPlanTReadWriteConsistencyTest`
- **재현 절차**:
  1. 아래 커맨드를 실행한다.
     - `mvn -q -Dtest=FederatedPlanTReadWriteConsistencyTest test`
  2. `testMinSTAddsHardConstraintEdgesForMultipleTWrites` 실패를 확인한다.
- **관측 증상**:
  - 실패 메시지: `Missing constraint edge 14 -> 18`
  - surefire 요약: `Tests run: 3, Failures: 1, Errors: 0`
- **원인 분석**:
  - 현재 MinST 코드는 loop-carry weighted edge 모델을 사용하며 TR/TW hard consistency edge를 의도적으로 강제하지 않는 경향이 있음.
  - 테스트는 hard constraint edge 존재를 기대하여 정책/테스트 계약 불일치가 발생.
- **해결 요약**:
  - 본 세션에서는 DP 안정화 범위를 우선으로 하여 MinST 정책/테스트 계약 정렬 작업은 미수행.
- **수정 파일**:
  - 없음
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedPlanTReadWriteConsistencyTest test`
  - 결과:
    - 위 단일 테스트 클래스 실패 재현
- **잔여 이슈**:
  - MinST 정책(soft/weighted loop-carry)과 테스트 기대(hard constraint) 중 어느 쪽을 기준 계약으로 둘지 결정 필요.
- **잠재 회귀 위험**:
  - 계약 정렬 없이 테스트만 우회/수정하면 TR/TW 제약 위반 회귀를 조기에 탐지하지 못할 수 있음.
  - 감지 방법: MinST 정책 문서화 + 계약 일치 테스트(soft 모델 vs hard 모델) 분리.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 플래너(MinST) 정책과 테스트 계약의 불일치 이슈로 분류, DP 수정과 분리해 추적.

## 이슈 8: `fromFTypes` planned-fed null 허용이 일부 보조 경로에 미반영되어 FED 가능성 판정이 불일치

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `compile_cost_based` (DP), 공통 정책: `FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes`
  - 변경 배경: `fTypeMap`에서 `containsKey(hopId)`를 planned-fed 신호로 사용(값 null 허용)
- **재현 절차**:
  1. `FederatedRefedPolicy`에서 `containsKey` 전환 적용 지점을 확인한다.
  2. 동일 파일의 보조 경로(`hasAnyPlannedFederatedMatrixInput`, `determineParentAnchor`)가 여전히 `get(...) != null`로 판정하는지 확인한다.
- **관측 증상**:
  - 메인 경로는 null FType planned-fed를 인정하지만, 보조 경로는 부정하여 같은 후보에서 FED 가능성/anchor 판정이 경로별로 달라질 수 있음.
- **원인 분석**:
  - planned-fed 시그널 정의(`containsKey`)가 `canSatisfyFederatedInputs(...)`의 일부 분기에서만 반영되고, 동일 의미를 쓰는 헬퍼 2곳에 누락됨.
- **해결 요약**:
  - 아래 2개 경로를 `get(...) != null` -> `containsKey(...)`로 통일.
    - `hasAnyPlannedFederatedMatrixInput(...)`
    - `determineParentAnchor(...)`
  - 결과적으로 null FType의 FOUT 선택 상태가 fromFTypes 경로 전체에서 일관되게 해석됨.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -DskipTests compile`
    - `mvn -q -Dtest=FederatedPlanCostEnumeratorTest,FederatedPlannerFallbackIntegrationTest,FederatedRefedPolicyTest test`
    - `mvn -q -Dtest=FederatedPlanTReadWriteConsistencyTest test`
  - 결과:
    - compile 통과
    - DP/Integration/RefedPolicy 테스트 통과
    - TReadWriteConsistencyTest는 기존과 동일하게 MinST 계약 이슈(`Missing constraint edge 14 -> 18`)로 실패
- **잔여 이슈**:
  - MinST hard-edge 계약 이슈는 별도(이슈 7)로 계속 추적.
- **잠재 회귀 위험**:
  - null FType planned-fed를 넓게 인정하면서 anchor type 추론이 약한 케이스에서 FED 후보가 증가할 수 있음.
  - 감지 방법: FunctionOp/anchor 선택 회귀 테스트에서 `containsKey + null FType` 시나리오 추가.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 우회 없이 정책 계층(FederatedRefedPolicy)에서 planned-fed 판정 정의를 일관화.

## 이슈 9: DP가 파생 `FED/FOUT`를 선택해도 rewrite 단계에서 `federatedOutputDerived`가 누락됨

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `compile_cost_based` (DP)
  - 변경 배경: `shouldEnableDerivedFedFout(...)`로 파생 `FED/FOUT` 후보를 DP가 생성
- **재현 절차**:
  1. DP 열거기에서 `derivedFedFout=true`일 때 `FED/FOUT` 계획이 생성되는 코드를 확인한다.
  2. DP rewrite(`FederatedPlannerDpFedCostBased.rewriteHop`)에서 `setFederatedOutput(...)` 이후 `setFederatedOutputDerived(...)` 호출이 없는지 확인한다.
  3. `Hop.setFederatedOutput(...)`는 `derived` 플래그를 false로 리셋하므로, 최종 Hop 상태에서 derived 정보가 사라짐을 확인한다.
- **관측 증상**:
  - 비용 모델에서는 파생 `FED/FOUT`(upload 포함)을 선택했지만, rewrite 이후 Hop의 `federatedOutputDerived`가 false로 남아 실제 LOP 전달 시 파생 의미가 손실될 수 있음.
- **원인 분석**:
  - DP memo plan에 `derived FED/FOUT` 메타데이터가 저장되지 않았고,
  - rewrite 단계에서도 해당 메타데이터를 Hop으로 복원하지 않음.
- **해결 요약**:
  - DP memo `FedPlan`에 `derivedFedFout` 필드를 추가.
  - DP 열거기에서 파생 `FED/FOUT` 후보 생성 시 `setDerivedFedFout(true)`를 저장.
  - DP rewrite에서 `resolvedExecType==FED && resolvedOutType==FOUT && plan.derivedFedFout`인 경우
    `setFederatedOutputDerived(true)`를 Hop에 반영.
  - 회귀 테스트를 추가해 rewrite 단계 플래그 전파를 검증.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpMemoTable.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -DskipTests compile`
    - `mvn -q -Dtest=FederatedPlanCostEnumeratorTest,FederatedPlannerFallbackIntegrationTest,FederatedRefedPolicyTest test`
    - `mvn -q -Dtest=FederatedPlanTReadWriteConsistencyTest test`
  - 결과:
    - compile 및 DP 관련 테스트 통과
    - `FederatedPlanTReadWriteConsistencyTest`는 기존과 동일한 MinST 계약 이슈(`Missing constraint edge 14 -> 18`)로 실패
- **잔여 이슈**:
  - MinST 계약 이슈(이슈 7)와 별개.
- **잠재 회귀 위험**:
  - 동일 원본 Hop의 clone 경합 시(동일 outType, 상이 derived 플래그) 최종 derived 선택 우선순위가 경로 의존적일 수 있음.
  - 감지 방법: clone 충돌 시나리오에서 DP rewrite 결과(`federatedOutputDerived`)를 고정 검증하는 테스트 추가.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 fallback 없이 플래너(DP)의 plan 메타데이터와 rewrite 반영 로직을 정합화.

## 이슈 10: DP 열거기 `enumerateHop` 시그니처 확장으로 reflection 기반 테스트가 `NoSuchMethod`로 붕괴

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - 테스트: `FederatedPlanTReadWriteConsistencyTest` (DP helper reflection 호출)
  - 변경 배경: `enumerateHop(...)`에 `parentChildUploadHints` 인자를 추가
- **재현 절차**:
  1. 아래 커맨드를 실행한다.
     - `mvn -q -Dtest=FederatedPlanTReadWriteConsistencyTest test`
  2. `NoSuchMethodException`(DP `enumerateHop` 구 시그니처)을 확인한다.
- **관측 증상**:
  - `testDpTReadRejectsMixedTWrites`, `testDpTReadAllowsUniformTWrites`가
    `NoSuchMethodException: FederatedPlannerDpCostEnumerator.enumerateHop(...)`로 실패.
- **원인 분석**:
  - 테스트는 기존 private 시그니처(`... privacyMap, unRefSet, numWorkers, oracle`)를 reflection으로 고정 호출.
  - 코드 변경으로 실제 메서드가 `parentChildUploadHints` 인자를 추가한 새 시그니처만 남아 binary-contract가 깨짐.
- **해결 요약**:
  - 기존 시그니처를 유지하는 private delegate 오버로드를 복원.
  - delegate에서 `parentChildUploadHints`를 빈 맵으로 전달해 기존 테스트 계약을 보존.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -DskipTests compile`
    - `mvn -q -Dtest=FederatedPlanCostEnumeratorTest,FederatedPlannerFallbackIntegrationTest,FederatedRefedPolicyTest test`
    - `mvn -q -Dtest=FederatedPlanTReadWriteConsistencyTest test`
  - 결과:
    - compile/DP 관련 테스트 통과
    - `FederatedPlanTReadWriteConsistencyTest`의 DP 2개 케이스는 복구되고,
      남은 실패는 기존 MinST 계약 이슈(`Missing constraint edge 14 -> 18`) 1건만 유지
- **잔여 이슈**:
  - MinST hard-edge 계약 이슈는 이슈 4에서 계속 추적.
- **잠재 회귀 위험**:
  - private helper 시그니처를 다시 변경할 때 reflection 기반 테스트가 재붕괴할 수 있음.
  - 감지 방법: `FederatedPlanTReadWriteConsistencyTest`를 DP 변경 후 smoke test로 항상 실행.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임/오라클 규칙 변경 없이 플래너(DP) 내부 API 호환성만 복원.

## 이슈 11: 공통 upload-hint API가 `hopId==0`을 무효 처리해 힌트를 누락함

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - 공통 유틸: `TransTableRewireUtils.markParentChildUploadHint/hasParentChildUploadHint`
  - DP forwarding 판정: `FederatedPlannerDpCostEnumerator.shouldAddFedForwardingForParentInput`
- **재현 절차**:
  1. `markParentChildUploadHint(map, 0L, X)` 또는 `(X, 0L)`를 호출한다.
  2. 즉시 `hasParentChildUploadHint`로 조회하면 false가 반환되는지 확인한다.
- **관측 증상**:
  - 첫 홉(예: hopId 0)이 포함된 parent-child 힌트가 저장/조회되지 않음.
  - 결과적으로 parent-child upload hint 자체가 누락되며, hint를 사용하는 비용/정책 로직이 있다면 누락될 수 있음.
- **원인 분석**:
  - 공통 API의 유효성 체크가 `<= 0`으로 구현되어 `0`을 invalid로 취급.
  - 실제 HOP ID는 `0`부터 할당될 수 있어 조건이 과도하게 엄격함.
- **해결 요약**:
  - 유효성 조건을 `<= 0` -> `< 0`으로 완화해 `0`을 정상 ID로 허용.
  - 회귀 테스트를 추가해 parent/child 어느 쪽이든 `hopId=0`일 때 힌트가 유지되는지 검증.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/TransTableRewireUtils.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -DskipTests compile`
    - `mvn -q -Dtest=FederatedPlanCostEnumeratorTest,FederatedPlannerFallbackIntegrationTest test`
  - 결과:
    - compile 및 테스트 통과
    - 신규 테스트 `testUploadHintApiAcceptsZeroHopId` 통과
- **잔여 이슈**:
  - parent-child hint를 실제 플래너 재배치 경로에서 언제/어떻게 생성할지(정책)는 별도 과제.
- **잠재 회귀 위험**:
  - 향후 유틸 정리 시 ID 검증 조건이 다시 강화되면 동일 누락이 재발할 수 있음.
  - 감지 방법: `hopId=0` 케이스를 포함한 hint API 단위 테스트 유지.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임/오라클 수정 없이 플래너 공통 유틸의 ID 유효성 규칙을 실제 HOP ID 체계에 맞게 교정.

## 이슈 12: DP에서 `CP/FOUT` 후보를 refed-전용 게이트로 막아 root 업로드가 불가능해짐

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `compile_cost_based` (DP)
  - 관련 로직: `FederatedPlannerDpCostEnumerator`의 `CP/FOUT` 후보 생성 게이트
  - 테스트: `FederatedPlannerFallbackIntegrationTest.testDpFallbackFTypeForCpfout`
- **재현 절차**:
  1. DP 열거기에서 `CP/FOUT` 후보 추가 조건에 `FederatedRefedPolicy.canGenerateCpfoutCandidateFromFTypes(...)`를 직접 사용한다.
  2. 아래 테스트를 실행한다.
     - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest#testDpFallbackFTypeForCpfout test`
- **관측 증상**:
  - `testDpFallbackFTypeForCpfout`가 `Expected CP_FOUT plan for binary plus`로 실패한다.
  - 원인: root hop(부모가 없는 hop)의 `CP/FOUT` 업로드 후보가 게이트에서 제거됨.
- **원인 분석**:
  - `canGenerateCpfoutCandidateFromFTypes`는 “FED 부모 사이에 refed/materialize를 삽입”하는 경로를 전제로 하며,
    `hop.getParent().isEmpty()`인 경우 false를 반환한다.
  - 하지만 DP의 `CP/FOUT`는 “최종 결과를 federated로 유지”하기 위한 root 업로드도 허용해야 하므로,
    refed-전용 게이트를 그대로 쓰면 과도하게 후보를 제거하게 된다.
- **해결 요약**:
  - `CP/FOUT` 게이트를 `canGenerateCpfoutCandidateSafe(...)`로 교체.
  - `canGenerateCpfoutCandidateSafe(...)`는:
    - hop에 부모가 있으면 `canGenerateCpfoutCandidateFromFTypes`로 기존(엄격) 검증을 사용하고,
    - root hop이면 “앵커 존재”만 확인한다:
      - `fTypeMap`(planned FOUT 입력) 비어있지 않음, 또는
      - `FederatedPlannerUtils.getUniqueFedInitVarName()` 존재.
    - `DMLRuntimeException`은 catch하여 후보 제거로 처리(플래너 전체 중단 방지).
  - 부수적으로 both-out child null-plan 예외 메시지를 `Missing <LOUT/FOUT>`로 정정해 디버깅 혼선을 줄임.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -DskipTests compile`
    - `mvn -q -Dtest=FederatedPlanCostEnumeratorTest,FederatedPlannerFallbackIntegrationTest,FederatedRefedPolicyTest test`
  - 결과:
    - compile 및 테스트 통과
- **잔여 이슈**:
  - root 업로드에서 “어떤 앵커를 실제로 사용할지”는 현재는 메타데이터 수준(계획)이며, rmvar로 앵커 변수가 제거되는 케이스는 별도 과제로 남음.
- **잠재 회귀 위험**:
  - `fTypeMap`이 비어있고 unique fed-init var도 없는 경우 root `CP/FOUT`를 의도적으로 불허하므로, 일부 워크로드에서 후보가 줄어 플랜 다양성이 감소할 수 있음.
  - 감지 방법: root `CP/FOUT`만이 유효한 synthetic 케이스를 단위 테스트로 추가.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 fallback 없이 플래너(DP) 후보 게이트 정의를 “refed 삽입”과 “root 업로드”를 구분해 정합화.

## 이슈 13: DP에서 OPTIONAL 입력의 LOUT->FED forwarding 비용을 0으로 처리해 FED 플랜 비용이 과소평가됨

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `compile_cost_based` (DP)
  - 관련 로직: `FederatedPlannerDpCostEnumerator` child forwarding cost 집계
  - 시나리오: FED 실행에서 OPTIONAL 입력(예: 브로드캐스트 벡터)이 local(LOUT)로 남는 경우
- **재현 절차**:
  1. `InputRequirement.OPTIONAL`로 분류되는 입력(예: vector)을 가진 hop에 대해, child가 LOUT로 선택된 조합에서 FED 실행 비용을 계산한다.
  2. OPTIONAL 입력의 LOUT->FED 업로드(브로드캐스트) 비용이 0으로 반영되는지 확인한다.
- **관측 증상**:
  - FED 실행이 OPTIONAL 입력의 업로드를 필요로 함에도, DP 비용 모델에서 해당 LOUT->FED 네트워크 비용이 제외될 수 있었다.
  - 결과적으로 DP가 FED 플랜을 과도하게 선호하는 비용 편향이 생길 수 있었다.
- **원인 분석**:
  - `shouldAddFedForwardingForParentInput(...)`가 OPTIONAL 입력에 대해 `upload hint`가 없으면 false를 반환해,
    `childForwardingCostToFED`가 0으로 합산되었다.
- **해결 요약**:
  - DP 비용 계산에서 parent가 FED 실행인 경우, matrix 입력은 REQUIRED/OPTIONAL 여부와 관계없이 LOUT->FED forwarding 비용을 항상 반영하도록 변경.
  - non-matrix 입력은 embedded literal로 취급해 별도 forwarding 비용을 추가하지 않는다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -DskipTests compile`
    - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest test`
  - 결과:
    - compile 및 테스트 통과
- **잔여 이슈**:
  - parent-child upload hint의 실사용 정책(어떤 경우에 hint를 생성/소비할지)은 별도 과제로 남음.
- **잠재 회귀 위험**:
  - FED 실행이 많은 그래프에서 업로드 비용이 증가해, DP가 이전보다 CP를 더 선택할 수 있음(성능 플랜 변화).
  - 감지 방법: LAN 워크로드(l2svm/logreg)에서 DP 플랜/비용 로그 비교.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 fallback 없이 플래너(DP) 비용 모델이 FED 실행의 필수 네트워크 비용(브로드캐스트 포함)을 반영하도록 교정.

## 이슈 14: runtime refed policy가 OPTIONAL local 입력까지 강제로 CP->FOUT로 올려 불필요한 `fed_fout`가 삽입됨

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Component: `FederatedRefedPolicy.ensureRequiredFederatedInputs`
  - 시나리오: FED parent가 OPTIONAL(BROADCAST 가능) vector 입력을 local(LOUT)로 유지 가능한 케이스
- **재현 절차**:
  1. `FED(X %*% p)` 형태에서 `X`는 federated, `p`는 local vector(LOUT)로 구성한다.
  2. `fTypeMap.put(pHopId, FType.BROADCAST)` 설정 후 `FederatedRefedPolicy.registerFromHops(...)`를 호출한다.
- **관측 증상**:
  - OPTIONAL vector 입력(`p`)이 local로 남아도 되는 경우에도, refed policy가 `p`를 REQUIRED처럼 취급해 `fed_fout` materialize를 등록/삽입했다.
  - 결과적으로 `FederatedRefedPolicyTest.testOptionalBroadcastInputStaysLocalForFedParent`가 실패했다.
- **원인 분석**:
  - `ensureRequiredFederatedInputs`가 OPTIONAL-but-local matrix 입력을 `requiredIndices`에 포함시켜
    `validateAndRegisterRequired(...)` 경로로 CP->FOUT 후보 등록을 강제했다.
- **해결 요약**:
  - OPTIONAL local 입력은, 동일 FED hop 내에 다른 anchor(=runtime federated 입력 또는 REQUIRED 입력)가 존재하면 local로 유지하도록 변경.
  - anchor가 전혀 없는 경우에만(=FED 실행 성립을 위해 필요할 때만) OPTIONAL local 입력을 federated로 만들도록 유지했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedRefedPolicyTest test`
  - 결과:
    - `FederatedRefedPolicyTest` 통과
- **잔여 이슈**:
  - 입력이 모두 OPTIONAL인 FED hop에서 anchor가 없는 경우의 선택(업로드 vs demote)은 워크로드 기반으로 추가 확인 여지.
- **잠재 회귀 위험**:
  - OPTIONAL input을 local로 유지하는 경로가 늘어나, 특정 연산에서 런타임이 local-input broadcast를 지원하지 않으면 실행 실패 가능.
  - 감지 방법: OPTIONAL-vector 케이스가 포함된 refed policy 테스트 유지 + LAN 워크로드에서 FED 실행 로그 확인.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 fallback 없이, planner의 OPTIONAL 의미(=broadcast 가능)를 refed policy가 과도하게 상쇄하지 않도록 런타임 계획을 정합화.

## 이슈 15: `CP->LOUT->FOUT->FED` forwarding 경로의 fan-out 지연 패널티 과소평가로 MinST/DP가 업로드 경로를 과선호함

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `fed_dp`, `fed_min_st_cut` (공통 비용모델 + 각 forwarding edge 비용 계산 경유)
  - 관측 맥락: WAN 프로파일(`wan_light`, `wan_mid`)에서 반복적 `fed_fout` 경로가 많은 워크로드(`lm`, `logreg`, `pca`)의 성능 편차
- **재현 절차**:
  1. MinST/DP 로그에서 `fed_fed_fout`, `Fed Put`, `Fed Execute`가 많은 케이스를 확인한다.
  2. 코드상 비용식을 확인하면 `LOUT->FED` forwarding 비용이 payload 기반 upload + 단일 latency만 반영되고,
     worker fan-out에 따른 추가 제어 지연((N-1)회 latency)이 별도 반영되지 않음을 확인한다.
- **관측 증상**:
  - 작은/중간 크기 벡터를 반복적으로 local->federated로 올리는 경로에서 실제 runtime 지연 대비 비용 예측이 낮아,
    planner가 `CP->LOUT->FOUT->FED` 체인을 상대적으로 과선호할 수 있었다.
- **원인 분석**:
  - 공통 업로드 비용식은 bandwidth와 단일 latency를 중심으로 계산되며,
    parent-child forwarding(LOUT->FED) 특유의 fan-out 제어 지연 패널티가 DP/MinST 모두에서 누락돼 있었다.
- **해결 요약**:
  - 공통 비용모델에 `computeLocalToFedForwardingPenalty(FType, numWorkers)`를 추가해
    forwarding fan-out 지연 패널티(`(numWorkers-1) * latency`)를 명시적으로 계산.
  - DP:
    - `FederatedPlannerDpCostEstimator.computeUploadCostWithFallback(...)`에서
      `LOUT->FED` forwarding upload 비용에 위 패널티를 합산.
  - MinST:
    - `FederatedPlanMinSTGraph.addParentChildNetEdge(...)`의 parent-child upload edge 비용에 동일 패널티를 합산.
    - `FederatedPlanMinSTCostEstimator.addLoopCarryEdgesForHop(...)`의 loop-carry upload 비용에도 동일 패널티를 합산.
  - 결과적으로 DP/MinST가 동일 forwarding 패널티 모델을 공유하도록 정렬.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCostEstimator.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedCostModelFallbackTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -DskipTests compile`
    - `mvn -q -Dtest=FederatedCostModelFallbackTest,FederatedPlanMinSTHyperedgeTest,FederatedPlannerFallbackIntegrationTest test`
    - `mvn -q -Dtest=org.apache.sysds.test.functions.federated.fedplanning.FederatedRefedPolicyTest,org.apache.sysds.test.component.federated.FederatedPlanMinSTRewireTest test`
  - 결과:
    - compile 통과
    - 신규/기존 관련 테스트 통과
- **잔여 이슈**:
  - 실제 `wan_light/wan_mid` 실험 로그에서 MinST vs DP 성능 수치 개선 폭은 사용자 실험 결과로 추가 확인 필요.
- **잠재 회귀 위험**:
  - fan-out 패널티 도입으로 FED 경로 비용이 상승해 일부 그래프에서 CP 선택 비율이 증가할 수 있음.
  - 감지 방법: WAN 프로파일에서 planner 선택(FED/CP 비율), `fed_fed_fout` count, `Fed Put` count를 함께 모니터링.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 fallback이 아니라 플래너 공통 비용모델 + planner edge cost(DP/MinST) 정합화를 통해 수정.
