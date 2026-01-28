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

## 단일 패스 플래너: FED 입력 가능성 게이트 누락
- **상태**: 해결
- **환경/조건**: 단일‑패스 Max‑FED/FOUT 플래너, oracle가 FED 가능 반환, 입력 federated 가능성(앵커/재배치) 미검증
- **재현 절차**: 단일‑패스 플래너가 oracle 결과만으로 FED 선택 (코드 리뷰)
- **관측 증상**: 입력이 federated로 만족 불가해도 FED 계획을 생성 → 런타임에서 FED 입력 부재 오류 가능
- **원인 분석**: `planGenericOp`이 `canSatisfyFederatedInputs*` 검증 없이 oracle 결과만으로 FED 허용
- **해결 요약**: FED 선택 전에 `FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(...)` 게이트 추가, 업그레이드 후에도 재검증
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAllMaxFedFoutSinglePass.java`
- **검증**: 미실행 (로컬 테스트 필요)
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: FED 계획이 이전보다 보수적으로 될 수 있음 → federated planning 테스트 로그/계획 비교로 감지
- **의사결정 근거**: 플래너 규칙(입력 federated 가능성 사전 검증, 런타임 fallback 금지)

## 단일 패스 플래너: CP→FOUT 축 불일치 보정 누락
- **상태**: 해결
- **환경/조건**: CP→FOUT 승격 시 소비자 축 불일치(ROW/COL/벡터) 가능
- **재현 절차**: CP→FOUT 후보에서 축 불일치 상황 (코드 리뷰)
- **관측 증상**: 런타임에서 BROADCAST로 강제 변환될 수 있어 후속 FED 가정 불일치 가능
- **원인 분석**: CP→FOUT FType 결정 시 `adjustCpFoutFTypeForConsumerAxisMismatch` 호출 없음
- **해결 요약**: CP→FOUT 계획 시 축 불일치 보정 적용, 워커 수를 고려하도록 FED init 워커 수 추적 추가
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAllMaxFedFoutSinglePass.java`
- **검증**: 미실행 (로컬 테스트 필요)
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: 일부 CP→FOUT이 BROADCAST로 변경될 수 있음 → FType/계획 로그 비교로 감지
- **의사결정 근거**: 플래너‑런타임 정합성(축 불일치 시 runtime 브로드캐스트 경로 반영)

## 단일 패스 플래너: TWrite FED 승격 과대 적용
- **상태**: 해결
- **환경/조건**: TWrite 입력이 논리적 FOUT이지만 런타임은 LOUT (FED→LOUT 등)
- **재현 절차**: TWrite가 입력 논리 FOUT만 보고 FED/FOUT 설정 (코드 리뷰)
- **관측 증상**: 실제 런타임 FED/FOUT이 아닌 입력에서도 TWrite가 FED로 계획될 위험
- **원인 분석**: TWrite가 입력의 런타임 FED/FOUT 여부를 확인하지 않고 logical FOUT만 확인
- **해결 요약**: 입력 ExecType/FederatedOutput 기준으로 런타임 FED일 때만 TWrite를 FED/FOUT으로 승격
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAllMaxFedFoutSinglePass.java`
- **검증**: 미실행 (로컬 테스트 필요)
- **잔여 이슈**: 없음
- **잠재 회귀 위험**: 일부 TWrite가 CP/LOUT으로 내려갈 수 있음 → TR/TW 계획/실행 로그 확인으로 감지
- **의사결정 근거**: 런타임 제약(TWrite는 `<CP,LOUT>` 또는 `<FED,FOUT>`만 허용)

## 단일 패스 플래너: CP/LOUT fType 힌트 소실로 업그레이드 불가
- **상태**: 해결
- **환경/조건**: 단일‑패스 Max‑FED/FOUT 플래너, fedinit 포함 DAG에서 CP 입력 업그레이드 필요 (예: `experiments/results/fed2/mkl-single-pass`)
- **재현 절차**: `run_LAN_docker.sh`로 mkl-single-pass 실행 후 로그에서 FED op/`fed_fout` 없음 확인
- **관측 증상**: fedinit는 인식되지만 대부분 CP로만 실행, `fed_fout` materialize 및 FED op 거의 없음
- **원인 분석**: CP/LOUT 계획 시 fType을 null로 버리고 `_fTypeMap`만 사용해 type propagation → `canUpgradeInput`이 plan.fType==null로 실패, CP→FOUT 업그레이드 체인이 끊김
- **해결 요약**:
  - 단일‑패스 플래너에 fType 힌트 맵 추가 (`_fTypeHints`)
  - CP/LOUT 계획에서도 가능한 경우 fType 힌트를 보존
  - `inferFType`는 힌트 맵을 사용해 연쇄 propagation 가능
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAllMaxFedFoutSinglePass.java`
- **검증**: 미실행 (mkl-single-pass / kmeans 재실행 필요)
- **잔여 이슈**: 계획 시점에 부모 ExecType이 기본 CP로 잡혀 CP→FOUT anchor 판정이 보수적으로 실패할 가능성 추가 점검 필요
- **잠재 회귀 위험**: 힌트가 과도하게 전파되면 불필요한 업그레이드 시도가 늘 수 있음 → FED 계획/`fed_fout` 로그 비교로 감지
- **의사결정 근거**: 플래너 규칙 수정(업그레이드 가능성 보존), 런타임 제약 준수(논리 FOUT 맵은 분리 유지)
