# Session Issues (2026-01-28)

## 단일 패스 플래너에 loop-unroll 비활성화 API 부재
- **상태**: 해결
- **환경/조건**: 플래너(단일‑패스 Max‑FED/FOUT), DP rewire 사용, loop‑unroll 비활성화 필요
- **재현 절차**: `FederatedPlannerDpRewireTransTable.rewireProgram(...)` 호출 시 loop‑unroll depth가 고정(1)이라 단일‑패스 플래너 요구사항 충족 불가
- **관측 증상**: loop‑unroll을 끌 수 있는 API가 없어서 spec의 “단일 패스 + unroll 비활성화”를 만족할 수 없음
- **원인 분석**: `FederatedPlannerDpRewireTransTable`가 `MAX_UNROLL_DEPTH=1` 상수에 의존
- **해결 요약**: `maxUnrollDepth` 파라미터를 추가한 오버로드를 제공하고, 단일‑패스 플래너에서 `0`으로 호출
- **수정 파일**: 
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpRewireTransTable.java`
- **검증**: 빌드/테스트 미실행 (로컬 실행 필요)
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: 기존 호출 경로가 기본값을 유지하지만, 신규 파라미터 전달 누락 시 기대와 다른 unroll 동작 가능 → 단위 테스트/회귀 테스트로 감지
- **의사결정 근거**: 플래너 규칙(단일 패스 + unroll 비활성화 요구)

## FED LOUT → FOUT materialize 등록 경로 부재
- **상태**: 해결
- **환경/조건**: 단일‑패스 플래너에서 FED op가 LOUT만 지원하지만 FOUT 필요, anchor 존재
- **재현 절차**: FED op가 LOUT만 가능한 경우 FOUT materialize를 등록하려 해도 `registerFromProgram`은 CP→FOUT만 처리
- **관측 증상**: FED LOUT→FOUT 승격을 계획해도 materialize registry 등록 경로가 없어 실행 계획 반영 불가
- **원인 분석**: `FederatedRefedPolicy`가 CP→FOUT만 등록하도록 설계됨
- **해결 요약**: FED LOUT→FOUT용 수동 등록 API(`registerFoutMaterializeCandidate(s)`) 추가 후 단일‑패스 플래너에서 호출
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAllMaxFedFoutSinglePass.java`
- **검증**: 빌드/테스트 미실행 (로컬 실행 필요)
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: 동일 hop에 중복 등록 가능성 → registry 스냅샷 로그/단일‑패스 테스트에서 감지
- **의사결정 근거**: 플래너 규칙(LOUT만 지원되는 FED op에서도 FOUT 재배치 가능성 평가/반영)

## Public privacy 테스트 케이스 비활성화 필요
- **상태**: 해결
- **환경/조건**: fedplanning 테스트 중 privacy=public 케이스
- **재현 절차**: federated planning 테스트가 public privacy 케이스를 포함
- **관측 증상**: 세션 원칙(“public privacy 케이스는 ignore”)과 테스트 구성이 충돌
- **원인 분석**: 기존 테스트에 public 케이스가 활성화된 상태
- **해결 요약**: 지정된 fedplanning 테스트 클래스에서 public 케이스에 `@Ignore` 적용
- **수정 파일**:
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedPCAPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedLogRegPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedLMPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedL2SVMPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedKMeansPlanningTest.java`
- **검증**: 빌드/테스트 미실행 (로컬 실행 필요)
- **잔여 이슈**: 다른 클래스의 public 케이스는 미검토
- **잠재 회귀 위험**: public 케이스가 필요한 시나리오에서 커버리지 감소 → 테스트 목록 재검토로 감지
- **의사결정 근거**: 플래너 규칙(작업 목표: public privacy 케이스 ignore)

## fedplanning 테스트에서 free port 확보 실패
- **상태**: 해결
- **환경/조건**: 로컬 mvn 테스트 실행, federated worker 포트 자동 할당
- **재현 절차**: `mvn -Dtest=FederatedPCAPlanningTest,FederatedP2LMPlanningTest,FederatedLogRegPlanningTest,FederatedLMPlanningTest,FederatedL2SVMPlanningTest,FederatedKMeansPlanningTest test`
- **관측 증상**: `Failed to find free port` 런타임 예외로 다수 테스트가 Error 처리됨
- **원인 분석**: (과거) 테스트 환경 자체에서 소켓 생성이 금지됨. `ServerSocket(0)` 기반 포트 할당이 실패.
- **해결 요약**: 환경 권한 정상화 이후 재현되지 않음.
- **수정 파일**: 없음
- **검증**: 동일 테스트 재실행 시 `Failed to find free port` 미발생 (2026-01-28)
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: 동일 환경 제약 재발 시 federated 테스트 전반 실패 → 포트 할당 오류 로그로 감지
- **의사결정 근거**: 런타임 제약(테스트 실행 환경)

## 단일 패스 플래너에서 전역 FED_INIT_VARS 참조로 로컬 실행이 FED로 오판
- **상태**: 해결
- **환경/조건**: 단일‑패스 플래너, PCA/KMeans fedplanning 테스트에서 federated 스크립트 후 reference 스크립트 연속 실행
- **재현 절차**: `mvn -Dtest=FederatedPCAPlanningTest,FederatedKMeansPlanningTest test`
- **관측 증상**: reference 스크립트에서 `Input is not federated` 또는 `Federated AggregateBinary not supported` 오류로 실패
- **원인 분석**: `planTransientRead`가 전역 `FederatedPlannerUtils.isFedInitVar`를 fallback으로 사용하여, 이전 실행에서 등록된 동일 변수명(X)을 federated로 오판
- **해결 요약**: 단일‑패스 플래너 내부에 **현재 프로그램의 FED_INIT 변수만** 추적하는 로컬 맵을 추가하고, TRead fallback은 해당 로컬 맵만 사용하도록 변경
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAllMaxFedFoutSinglePass.java`
- **검증**: `mvn -Dtest=FederatedPCAPlanningTest,FederatedP2LMPlanningTest,FederatedLogRegPlanningTest,FederatedLMPlanningTest,FederatedL2SVMPlanningTest,FederatedKMeansPlanningTest test` 통과
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: 동일 변수명이 다른 스크립트에서 재사용될 때 전역 상태가 다시 영향 줄 수 있음 → reference 스크립트 연속 실행 테스트로 감지
- **의사결정 근거**: 플래너 규칙(실제 FederatedMap 기반 anchor만 허용, 런타임 fallback 금지)

## run_LAN.sh / run_LAN_docker.sh 스크립트 경로 부재
- **상태**: 진행중
- **환경/조건**: 테스트 통과 후 실행 단계
- **재현 절차**: repo 루트에서 `./run_LAN.sh` 실행
- **관측 증상**: `/bin/bash: line 1: ./run_LAN.sh: No such file or directory`
- **원인 분석**: 저장소 내 해당 스크립트가 존재하지 않음 (검색 결과 없음)
- **해결 요약**: 미해결 (스크립트 위치 확인 필요)
- **수정 파일**: 없음
- **검증**: `find ... -name run_LAN*.sh`에서 결과 없음
- **잔여 이슈**: 스크립트 위치/대체 실행 경로 확인 필요
- **잠재 회귀 위험**: 자동 검증 단계 누락 → 스크립트 경로 제공 시 재실행으로 감지
- **의사결정 근거**: 작업 목표(테스트 통과 후 run_LAN 실행)
