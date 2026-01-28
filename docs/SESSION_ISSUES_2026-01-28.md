# Session Issues (2026-01-28)

## 1) MinST missing derived FED,FOUT (via refed) for FED,LOUT-only ops
- **상태**: 해결
- **환경/조건**: MinST planner, privacy=PUBLIC / PRIVATE_AGGREGATE_TO_PUBLIC, oracle caps with exec=FED & placement=LOUT-only (native FOUT 미지원), federated anchor 존재.
- **재현 절차**: MinST 계획에서 FED LOUT-only hop가 상위 FOUT을 요구받는 경우 (예: fedplanning 테스트에서 AggBinary 등). 로그에서 `FOUT_NOT_SUPPORTED_BY_RUNTIME`와 함께 FOUT 요구 시 계획 실패/누락.
- **관측 증상**: MinST가 `FED,FOUT`을 선택하지 못하거나 비용이 0으로 계산되어 잘못된 계획 생성. 실행 시 refed 삽입 없이 native FOUT을 시도해 런타임 오류 가능.
- **원인 분석**: MinST가 `FED,FOUT`을 “native FOUT”으로만 해석하고, `FED,LOUT -> refed -> FOUT` 경로를 플래닝/비용/실행 단계에서 모델링하지 않음. `FederatedRefedPolicy`는 CP->FOUT만 등록.
- **해결 요약**: 
  - MinST caps에 derived FOUT 모드 추가하고, oracle가 FED LOUT-only일 때(행렬 + anchor 존재 + privacy 허용) derived FOUT 허용.
  - MinST graph에서 derived hop은 내부 변환 간선을 제거하고, exec(FED)에 download unary, placement(FOUT)에 upload(refed) unary 비용을 추가.
  - 선택된 derived FED/FOUT hop은 LOP에 LOUT로 강제되도록 Hop에 derived flag 추가.
  - `FederatedRefedPolicy`가 FED+derived FOUT도 refed 등록하도록 확장.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/Hop.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTGraph.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
- **검증**:
  - `mvn -Dtest=FederatedPCAPlanningTest,FederatedP2LMPlanningTest,FederatedLogRegPlanningTest,FederatedLMPlanningTest,FederatedL2SVMPlanningTest,FederatedKMeansPlanningTest test` (3회 반복, 모두 PASS)
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: derived flag가 잘못 세팅될 경우 FED instruction이 LOUT으로 강제되어 native FOUT 경로가 깨질 수 있음. **감지**: MinST 로그/실행에서 `CP->FOUT runtime conversion is not supported` 또는 refed 삽입 누락 여부 확인.
- **의사결정 근거**: 플래너 규칙 수정(oracle 캡은 유지, MinST 비용/선택 모델 보완) + 런타임 지원(refed) 사용.

## 2) privacy=public 테스트 케이스 무시(AGENTS 지침)
- **상태**: 해결
- **환경/조건**: federated/fedplanning 테스트 내 privacy="public" 케이스.
- **재현 절차**: public 케이스가 포함된 테스트 실행.
- **관측 증상**: 지침 상 public 케이스는 무시 필요.
- **원인 분석**: AGENTS.md 작업 목표에 따라 public privacy 케이스는 ignore 처리해야 함.
- **해결 요약**: public privacy 테스트 메서드에 `@Ignore` 추가.
- **수정 파일**:
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedLogRegPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedKMeansPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedPCAPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedL2SVMPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedLMPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedP2LMPlanningTest.java`
- **검증**: 위 테스트 실행 시 public 케이스가 skipped로 표시됨.
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: public 케이스 커버리지 손실. **감지**: 테스트 리포트에서 skipped 수 확인.
- **의사결정 근거**: 작업 지침(테스트 정책) 준수.

## 3) run_LAN.sh / run_LAN_docker.sh 스크립트 부재
- **상태**: 진행중
- **환경/조건**: 테스트 완료 후 스크립트 실행 단계.
- **재현 절차**: repo에서 `run_LAN.sh`, `run_LAN_docker.sh` 탐색.
- **관측 증상**: 스크립트 파일이 repo 내에 존재하지 않음.
- **원인 분석**: 스크립트가 repo에 포함되지 않았거나 경로가 다름.
- **해결 요약**: 미해결. 위치/제공 필요.
- **수정 파일**: 없음
- **검증**: `rg -n "run_LAN" -g"*"` 결과 AGENTS.md 외 없음.
- **잔여 이슈**: 스크립트 경로 확인 필요.
- **잠재 회귀 위험**: LAN 시나리오 검증 누락. **감지**: 스크립트 제공 후 실행 성공 여부 확인.
- **의사결정 근거**: 테스트 절차 지침 준수 시도.

## 4) KMeans planning 테스트 중 federated worker 오류 로그
- **상태**: 진행중
- **환경/조건**: `FederatedKMeansPlanningTest` 실행 중.
- **재현 절차**: 위 테스트 수행 시 간헐적으로 federated worker 로그에 오류 출력.
- **관측 증상**: `Failed to execute federated instruction: CP°r'...` 및 temp 파일 읽기 실패 로그가 출력되나 테스트는 PASS.
- **원인 분석**: temp scratch 파일 레이스/클린업 타이밍으로 추정(확정 아님).
- **해결 요약**: 미해결. 로그만 기록.
- **수정 파일**: 없음
- **검증**: 테스트 3회 모두 PASS (로그 오류 발생).
- **잔여 이슈**: 로그 오류의 근본 원인 분석 필요.
- **잠재 회귀 위험**: 실제 런타임 실패로 확대될 가능성. **감지**: 동일 로그의 빈도/실패 전환 여부 모니터링.
- **의사결정 근거**: 런타임 관찰(수정 없음).
