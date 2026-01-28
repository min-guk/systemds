# 단일 패스 Max-FED/FOUT 플래너 스펙 (2026-01-28)

## 1) 목적
- **DP 열거(비용 기반) 없이**, 단일 패스(single-pass)로 HOP DAG를 한 번 훑으면서 **가능한 범위 내에서 FED/FOUT을 최대화**한다.
- **DP rewire(Loop-unroll 제외)** 결과를 재사용하여 TR/TW 연결 및 입력 의존성을 정확히 반영한다.
- 런타임 fallback 없이 **플래너가 가능한/불가능을 사전에 판단**하고 그 계획을 그대로 실행하도록 한다.

## 2) 범위 / 비범위
### 범위
- DP rewire 결과 재사용(단, loop-unroll 비활성화).
- 단일 패스의 FED/FOUT 강화를 위한 규칙 설계.
- TR/TW 연결(분기/루프 포함)을 rewireTable로 반영하고 **TR/TW 일관성 기반**으로 배치 결정.

### 비범위
- DP cost enumeration/MinST 등 **비용 기반 플래너는 사용하지 않음**.
- runtime fallback/implicit 보정 로직 추가 금지.

## 3) 핵심 제약 (반드시 준수)
- **TRead/TWrite는 `<CP,LOUT>` 또는 `<FED,FOUT>`만 허용**.
- **recompile 경로에서 `<CP,FOUT>` 금지**.
- **CP→FOUT / FED,LOUT→FOUT는 “기존 federated anchor(실제 FederationMap)”가 있어야만 가능**.
- **FED/FOUT 미지원 op는 FOUT 금지**(oracle/runtime 제약 준수).
- **Matrix만 FOUT 허용**.
- **runtime fallback 금지**.

## 4) 입력/출력
### 입력
- HOP DAG + privacy constraint
- oracle (Rules 기반 OpCaps)
- DP rewire 결과(rewireTable)
- federated anchor registry (FederationMap)

### 출력
- 각 Hop의 ExecType(FED/CP) + FederatedOutput(FOUT/LOUT)
- fTypeMap (Hop→FType)
- CP→FOUT / FED,LOUT→FOUT materialize 계획 등록

## 5) DP rewire 재사용 (loop-unroll 제외)
- **FederatedPlannerDpRewireTransTable**의 rewire 결과를 사용하되,
  loop unroll은 **비활성화**한다.
- 구현 방식(예시):
  - rewire API에 `maxUnrollDepth=0` 또는 `disableUnroll=true` 파라미터 추가
  - 또는 기존 상수(MAX_UNROLL_DEPTH)를 0으로 설정하는 경로 제공
- 결과: TR/TW 연결 + 소비자/생산자 의존성은 반영하되, loop unroll로 인한 DAG 확장은 하지 않음.

## 6) 단일 패스 플래닝 알고리즘 (개요)
1. **rewireTable 생성** (DP rewire, loop-unroll 제외)
2. StatementBlock 단위로 HOP DAG를 **단일 DFS/토폴로지 순회**
3. 각 Hop에서 아래 우선순위로 **ExecType/Placement 결정**:
   - (A) FED 가능하면 FED 선택
   - (B) FED 결과는 가능하면 **FOUT 선호**
   - (C) FED가 불가하면 CP 선택하되 **가능하면 FOUT 승격**
4. TR/TW는 rewireTable 기반으로 **연결된 TWrite들의 가능 배치 교집합 + FType 호환성**으로 결정
5. 결정 결과를 fTypeMap과 refed/materialize registry에 등록

## 7) Hop 결정 규칙 (상세)
### 7.1 FED 가능성 게이트
- `FederatedRefedPolicy.canSatisfyFederatedInputs...`로 **FED 입력 충족 가능 여부** 판단
- oracle가 `ExecType.FED`를 허용하는지 확인
- 위 조건을 만족하지 못하면 FED 금지

### 7.2 FED일 때 FOUT 우선
- oracle가 placement=FOUT이면 FOUT 우선
- placement가 LOUT이어도 **FType 추론 가능 + anchor 존재 시 FOUT materialize 시도**
- FType 추론 실패 시 LOUT으로 강등

### 7.3 CP일 때 FOUT 승격
- `canGenerateCpfoutCandidate(...)`가 true이면 CP→FOUT
- anchor 없거나 재컴파일 구간이면 CP→LOUT

### 7.4 TR/TW 제약
- TR/TW는 `<CP,LOUT>` 또는 `<FED,FOUT>`만 허용
- TR은 **연결된 TWrite들의 가능한 배치 교집합**으로 결정한다.
  - FOUT 허용 조건: 모든 연결 TWrite가 **FOUT plan 보유 + FType non-null + 동일 FType**
  - LOUT 허용 조건: 모든 연결 TWrite가 **LOUT plan 보유 + (존재한다면) 동일 FType**
  - 둘 다 불가하면 **오류**(TR이 참조하는 TWrite 배치가 일관되지 않음)
- 둘 다 가능하면 **전역 우선순위(FED,FOUT > CP,LOUT)**를 따른다.
- 그 외에는 TR을 CP,LOUT으로 결정 (TWrite 승격/수정은 하지 않음)

## 8) FType 추론
- oracle의 foutFType → 최우선
- fallback:
  - `OracleUtils.inferFallbackFType(...)`
  - `FederatedTypePropagator` 기반 추론
- CP→FOUT은 **consumer axis mismatch 보정** (`adjustCpFoutFTypeForConsumerAxisMismatch`)

## 9) refed/materialize 등록
- CP→FOUT 또는 FED,LOUT→FOUT이 필요하면
  `FederatedRefedPolicy`의 등록 로직을 사용
- 앵커는 **실제 FederatedMap 기반**으로만 선택

## 10) 로깅/디버그
- Hop 단위 결정 로그: ExecType, FOUT/LOUT, FType, 승격 여부
- TR/TW 결정 로그: 연결된 TWrite 수, 허용된 배치(FOUT/LOUT), FType 일치 여부

## 11) 구현 방식: 신규 클래스 생성
### 11.1 구현 원칙
- **기존 FedAll 수정 대신 신규 클래스 추가**로 구현한다.
- 목적: 기존 플래너의 동작/실험을 보존하고, 단일‑패스 Max‑FED/FOUT 규칙을 분리한다.

### 11.2 후보 클래스/패키지
- 기본 선택: `org.apache.sysds.hops.fedplanner.fedAll`
  - 클래스명 예시: `FederatedPlannerFedAllMaxFedFoutSinglePass`
- 대안: `org.apache.sysds.hops.fedplanner.fedSinglePass` 패키지 신설
  - 다만 기존 FedAll 의존 코드를 재사용할 경우 `fedAll` 내부가 유지보수에 유리

### 11.3 변경/추가 파일(후보)
- 신규 플래너 클래스: `fedAll/FederatedPlannerFedAllMaxFedFoutSinglePass.java`
- DP rewire 제어 API 추가
  - `fedCostBased/fedDp/FederatedPlannerDpRewireTransTable.java`
- refed/materialize 정책 연동 (기존 `FederatedRefedPolicy` 재사용)

## 12) 검증 시나리오
- 단순 DAG (no control-flow): FED/FOUT 최대화 확인
- if/else 분기에서 TR이 **연결된 TWrite 배치와 일치**하는지 확인
- loop에서 TR이 **loop 내부/외부 TWrite 배치와 일치**하는지 확인
- TR/TW 제약 위반 없음 확인
- recompile 구간에서 CP→FOUT 금지 확인

### 12.1 로컬 회귀 테스트 세트(필수)
- 목적: 신규 단일‑패스 Max‑FED/FOUT 플래너의 **로컬 재현/회귀 체크**를 기존 테스트 스위트에 편입
- 전제:
  - **public privacy** 케이스는 기존 정책대로 `@Ignore` 유지
  - 우선순위는 **DP → FedAll → Heuristic → MinST**
- 대상 테스트(각 클래스에 1개 이상 추가):
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedPCAPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedP2LMPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedLogRegPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedLMPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedL2SVMPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedKMeansPlanningTest.java`
- 실행 방식:
  - 각 테스트는 **단일‑패스 플래너 전용 config**를 사용
    - 예: `SystemDS-config-single-pass.xml` (scripts/functions/privacy/fedplanning 경로)
    - planner 키는 신규 플래너로 연결 (예: `sysds.federated.planner=compile_fed_all_max_fed_fout_single_pass`)
  - privacy는 기본적으로 `private-aggregate`로 실행
  - 검증 방식은 각 기존 테스트의 패턴(참조 DML 비교 또는 실행 성공 여부)에 맞춤
- 반복 규칙:
  - **아래 테스트들을 “수정 -> 실행 -> 실패 원인 분석 -> 수정” 사이클로 전부 PASS 될 때까지 3번 반복**한다.

## 13) 미해결/결정 필요 항목
- “서로 다른 FType”을 가진 TWrite들이 연결될 때의 처리 우선순위(기본은 FOUT 불가)
- FED,LOUT→FOUT 승격 시 anchor 선택 우선순위
