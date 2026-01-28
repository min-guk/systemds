# Session Issues (2026-01-28)

## MinST missing derived FED,FOUT (via refed) for FED,LOUT-only ops
- **상태**: 해결
- **환경/조건**: MinST planner, privacy=PUBLIC / PRIVATE_AGGREGATE_TO_PUBLIC, oracle caps exec=FED & placement=LOUT-only (native FOUT 미지원), federated anchor 존재
- **재현 절차**: MinST 계획에서 FED LOUT-only hop가 상위 FOUT을 요구받는 경우 (예: fedplanning 테스트에서 AggBinary 등)
- **관측 증상**: MinST가 `FED,FOUT`을 선택하지 못하거나 비용이 0으로 계산되어 잘못된 계획 생성. 실행 시 refed 삽입 없이 native FOUT을 시도해 런타임 오류 가능
- **원인 분석**: MinST가 `FED,FOUT`을 “native FOUT”으로만 해석하고, `FED,LOUT -> refed -> FOUT` 경로를 플래닝/비용/실행 단계에서 모델링하지 않음
- **해결 요약**:
  - MinST caps에 derived FOUT 모드 추가
  - derived FED/FOUT hop은 LOUT으로 강제 실행되도록 derived flag 사용
  - `FederatedRefedPolicy`가 FED+derived FOUT도 refed 등록
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/Hop.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
- **검증**: `mvn -Dtest=FederatedPCAPlanningTest,FederatedP2LMPlanningTest,FederatedLogRegPlanningTest,FederatedLMPlanningTest,FederatedL2SVMPlanningTest,FederatedKMeansPlanningTest test` 통과
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: derived flag 오설정 시 native FOUT 경로가 깨질 수 있음 → MinST 로그/실행에서 refed 삽입 누락 여부로 감지
- **의사결정 근거**: 플래너 규칙 수정(oracle 캡 유지, MinST 비용/선택 모델 보완) + 런타임 지원(refed) 사용

## 단일 패스 플래너에 loop-unroll 비활성화 API 부재
- **상태**: 해결
- **환경/조건**: 플래너(단일‑패스 Max‑FED/FOUT), DP rewire 사용, loop‑unroll 비활성화 필요
- **재현 절차**: `FederatedPlannerDpRewireTransTable.rewireProgram(...)` 호출 시 loop‑unroll depth가 고정(1)
- **관측 증상**: loop‑unroll을 끌 수 있는 API가 없어 “단일 패스 + unroll 비활성화” 요구사항 충족 불가
- **원인 분석**: `FederatedPlannerDpRewireTransTable`가 `MAX_UNROLL_DEPTH=1` 상수에 의존
- **해결 요약**: `maxUnrollDepth` 파라미터 오버로드 추가, 단일‑패스 플래너에서 `0`으로 호출
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpRewireTransTable.java`
- **검증**: `mvn -Dtest=FederatedPCAPlanningTest,FederatedP2LMPlanningTest,FederatedLogRegPlanningTest,FederatedLMPlanningTest,FederatedL2SVMPlanningTest,FederatedKMeansPlanningTest test` 통과
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: 파라미터 전달 누락 시 unroll 동작 변화 → 회귀 테스트로 감지
- **의사결정 근거**: 플래너 규칙(단일 패스 + unroll 비활성화 요구)

## FED LOUT → FOUT materialize 등록 경로 부재
- **상태**: 해결
- **환경/조건**: 단일‑패스 플래너에서 FED op가 LOUT만 지원하지만 FOUT 필요, anchor 존재
- **재현 절차**: FED LOUT-only op에서 FOUT materialize 필요
- **관측 증상**: `registerFromProgram`이 CP→FOUT만 처리하여 FED LOUT→FOUT 승격 계획이 실행 계획에 반영되지 않음
- **원인 분석**: `FederatedRefedPolicy`가 CP→FOUT만 등록하도록 설계됨
- **해결 요약**: FED LOUT→FOUT용 수동 등록 API(`registerFoutMaterializeCandidate(s)`) 추가 후 단일‑패스 플래너에서 호출
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAllMaxFedFoutSinglePass.java`
- **검증**: `mvn -Dtest=FederatedPCAPlanningTest,FederatedP2LMPlanningTest,FederatedLogRegPlanningTest,FederatedLMPlanningTest,FederatedL2SVMPlanningTest,FederatedKMeansPlanningTest test` 통과
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: 동일 hop 중복 등록 가능성 → registry 스냅샷/단일‑패스 테스트로 감지
- **의사결정 근거**: 플래너 규칙(LOUT만 지원되는 FED op에서도 FOUT 재배치 가능성 평가)

## Public privacy 테스트 케이스 비활성화 필요
- **상태**: 해결
- **환경/조건**: fedplanning 테스트 중 privacy=public 케이스
- **재현 절차**: public privacy 케이스가 포함된 테스트 실행
- **관측 증상**: 세션 원칙(“public privacy 케이스 ignore”)과 테스트 구성이 충돌
- **원인 분석**: 기존 테스트에 public 케이스가 활성화된 상태
- **해결 요약**: public 케이스에 `@Ignore("public privacy constraints ignored in this run")` 적용
- **수정 파일**:
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedPCAPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedLogRegPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedLMPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedL2SVMPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedKMeansPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedP2LMPlanningTest.java`
- **검증**: 테스트 리포트에서 public 케이스가 skipped로 표시됨
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: public 케이스 커버리지 감소 → skipped 수 확인으로 감지
- **의사결정 근거**: 작업 지침(테스트 정책)

## fedplanning 테스트에서 free port 확보 실패
- **상태**: 해결
- **환경/조건**: 로컬 mvn 테스트 실행, federated worker 포트 자동 할당
- **재현 절차**: `mvn -Dtest=FederatedPCAPlanningTest,FederatedP2LMPlanningTest,FederatedLogRegPlanningTest,FederatedLMPlanningTest,FederatedL2SVMPlanningTest,FederatedKMeansPlanningTest test`
- **관측 증상**: `Failed to find free port` 런타임 예외
- **원인 분석**: (과거) 환경에서 `ServerSocket(0)` 실패
- **해결 요약**: 환경 권한 정상화 이후 재현되지 않음
- **수정 파일**: 없음
- **검증**: 동일 테스트 재실행 시 `Failed to find free port` 미발생 (2026-01-28)
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: 동일 환경 제약 재발 시 federated 테스트 전반 실패 → 포트 오류 로그로 감지
- **의사결정 근거**: 런타임 제약(테스트 실행 환경)

## 단일 패스 플래너에서 전역 FED_INIT_VARS 참조로 로컬 실행이 FED로 오판
- **상태**: 해결
- **환경/조건**: 단일‑패스 플래너, PCA/KMeans fedplanning 테스트에서 federated 스크립트 후 reference 스크립트 연속 실행
- **재현 절차**: `mvn -Dtest=FederatedPCAPlanningTest,FederatedKMeansPlanningTest test`
- **관측 증상**: reference 스크립트에서 `Input is not federated` 또는 `Federated AggregateBinary not supported` 오류
- **원인 분석**: `planTransientRead`가 전역 `FederatedPlannerUtils.isFedInitVar`를 fallback으로 사용하여 이전 실행의 변수명을 federated로 오판
- **해결 요약**: 단일‑패스 플래너 내부에 **현재 프로그램의 FED_INIT 변수만** 추적하는 로컬 맵 추가, TRead fallback은 로컬 맵만 사용
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAllMaxFedFoutSinglePass.java`
- **검증**: `mvn -Dtest=FederatedPCAPlanningTest,FederatedP2LMPlanningTest,FederatedLogRegPlanningTest,FederatedLMPlanningTest,FederatedL2SVMPlanningTest,FederatedKMeansPlanningTest test` 통과
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: 변수명 재사용 시 전역 상태 영향 재발 가능 → reference 스크립트 연속 실행 테스트로 감지
- **의사결정 근거**: 플래너 규칙(실제 FederatedMap 기반 anchor만 허용, 런타임 fallback 금지)

## run_LAN.sh / run_LAN_docker.sh 스크립트 경로 부재
- **상태**: 진행중
- **환경/조건**: 테스트 통과 후 실행 단계
- **재현 절차**: repo 루트에서 `./run_LAN.sh` 실행
- **관측 증상**: `/bin/bash: line 1: ./run_LAN.sh: No such file or directory`
- **원인 분석**: 저장소 내 해당 스크립트가 존재하지 않음
- **해결 요약**: 미해결 (스크립트 위치 확인 필요)
- **수정 파일**: 없음
- **검증**: `find ... -name run_LAN*.sh` 결과 없음
- **잔여 이슈**: 스크립트 위치/대체 실행 경로 확인 필요
- **잠재 회귀 위험**: LAN 시나리오 검증 누락 → 스크립트 제공 후 실행으로 감지
- **의사결정 근거**: 작업 목표(테스트 통과 후 run_LAN 실행)

## KMeans planning 테스트 중 federated worker 오류 로그
- **상태**: 진행중
- **환경/조건**: `FederatedKMeansPlanningTest` 실행 중
- **재현 절차**: 위 테스트 수행 시 간헐적으로 federated worker 로그에 오류 출력
- **관측 증상**: `Failed to execute federated instruction: CP°r'...` 및 temp 파일 읽기 실패 로그가 출력되나 테스트는 PASS
- **원인 분석**: temp scratch 파일 레이스/클린업 타이밍으로 추정(확정 아님)
- **해결 요약**: 미해결 (로그만 기록)
- **수정 파일**: 없음
- **검증**: 테스트 PASS, 오류 로그 발생
- **잔여 이슈**: 로그 오류의 근본 원인 분석 필요
- **잠재 회귀 위험**: 실제 런타임 실패로 확대될 가능성 → 동일 로그 빈도/실패 전환 여부 모니터링
- **의사결정 근거**: 런타임 관찰(수정 없음)
