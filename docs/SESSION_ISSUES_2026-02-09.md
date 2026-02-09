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
