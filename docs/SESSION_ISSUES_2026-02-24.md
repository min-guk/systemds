# SESSION ISSUES - 2026-02-24

## 이슈 1: DP에서 runtime 지원 조합을 “휴리스틱 skip/닫기”로 제거(RMEMPTY/TWrite/colMaxs-MULT) + 대형 local input demotion
- **상태**: 해결(코드 제거 + 비용모델 보정 + 단위 테스트 통과)

- **환경/조건**:
  - systemds root: `tmp/systemds-local`
  - planner: DP(`fedCostBased/fedDp`) + 공통 policy(`FederatedRefedPolicy`)
  - 문제 성격: cost-based DP의 candidate-space를 runtime 지원 여부와 무관하게 축소하는 guard 존재

- **관측/문제 정의**:
  - 아래 로직들이 “runtime이 지원하는 조합”임에도 불구하고, DP enumeration 단계에서 특정 bit 조합을 `continue`로 스킵하거나,
    Exec/placement 후보를 강제로 닫아(cost 비교 자체를 못 하게) cost-based 철학과 충돌했다.
    - DP: `RMEMPTY` target을 localize(LOUT)하는 bit 조합 스킵 + FED/FOUT 가능 시 LOUT 후보 강제 닫기
    - DP: large `TWrite` input을 localize(LOUT)하는 bit 조합 스킵 + FED/FOUT 가능 시 LOUT 후보 강제 닫기
    - DP: `MULT`가 `colMaxs`에만 소비되는 패턴에서 FED 후보 강제 닫기
    - Policy: 대형 local REQUIRED input이 존재하면 (일부 op 범위) FED/CP->FOUT feasibility를 `false`로 내려 candidate를 사실상 닫기

- **원인 분석**:
  - 성능/안정성 이슈를 비용 모델로 해결하지 않고, planner가 candidate-space를 임의로 축소하는 방식으로 대응한 흔적.
  - 추가로, `RMEMPTY`는 output이 shrink될 수 있어 “output cell 기반”의 compute-cost가 과소평가될 수 있음(측정 문제).

- **해결 요약**:
  1) 위 휴리스틱 기반 candidate-space 축소 로직을 전부 제거.
  2) `RMEMPTY`의 compute cost가 output 크기만 보지 않도록, input-scan을 반영하도록 cost model 보정.
  3) 원칙을 `AGENTS.md`에 강하게 명시:
     - runtime이 지원하는 조합을 guard로 닫지 말 것
     - 닫고 싶어질 때는 먼저 cost/memory 측정(ComputeCost/Hop mem estimate/FederatedCostModel/boundary cost)을 점검·수정할 것

- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
  - `src/main/java/org/apache/sysds/hops/cost/ComputeCost.java`
  - `AGENTS.md`

- **검증**:
  - 컴파일: `mvn -q -DskipTests compile`
  - 단위 테스트:
    - `mvn -q -Dtest=FederatedPlanCostEnumeratorTest test`
    - `mvn -q -Dtest=FederatedPlanMinSTRewireTest test`

- **잔여 이슈**:
  - 본 변경은 “candidate-space를 닫지 않는다”를 우선 적용한 것으로, 특정 workload에서 성능이 흔들릴 수 있음.
  - 성능 문제가 재발하면 guard로 닫지 말고 비용 모델(특히 FED per-op overhead/네트워크 항, mem estimate)을 먼저 교정해야 함.

- **잠재 회귀 위험 + 감지**:
  - 위험: sliceline/kmeans WAN에서 DP/MinST가 다시 CP/LOUT 쪽으로 과선택할 가능성(성능 저하).
  - 감지: matrix sweep 재실행 후 `inst_stats`의 `FED°fed_refed`/`FED°CP->FOUT`/`Total execution time` 비교 + planner trace로 후보/비용 확인.

- **의사결정 근거(oracle/runtime/planner)**:
  - runtime 지원 조합은 cost-based로 비교되어야 하므로 planner-side prune를 제거했고,
    필요한 경우 cost model을 보정하는 방향으로 정렬했다.

## 이슈 2: MinST에서 “FED/FOUT 미지원 + FED/LOUT 지원” 상황을 CP/FOUT 강제 닫기로 회피하던 문제
- **상태**: 해결(derived FED/FOUT 모델링 + CP/FOUT feasibility 게이트 추가 + 단위 테스트 통과)

- **문제 정의**:
  - 일부 opcode에서 oracle/runtime cap이 `FED/LOUT만 가능(FED/FOUT 불가)`인데, `CP/FOUT`는 anchor 기반 물질화로 가능할 수 있음.
  - 이때 MinST(min-cut 인코딩)에서 illegal `(FED,FOUT)` 선택을 막기 위해 `CP/FOUT`를 강제로 꺼버리면(runtime 지원 후보를 닫음)
    cost-based 철학과 충돌.

- **해결 요약**:
  1) **derived FED/FOUT**(= `FED/LOUT` 결과를 **LOUT→FOUT(refed/fed_fout)**로 물질화) 를 MinST 후보로 켜서,
     `CP/FOUT`과 동일한 “LOUT→FOUT 가능” 케이스를 cost-based로 비교 가능하게 함.
  2) `CP/FOUT`는 **anchor 기반 물질화가 실제로 가능할 때만** 후보로 유지하도록 게이트 추가
     (`canGenerateCpfoutCandidate...`가 false면 `CP/FOUT`도 닫음).  
     → “가능한데 닫기”가 아니라 “불가능하니 닫기(합법성)”로 정렬.

- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
  - (테스트 정합성) `src/test/java/org/apache/sysds/test/component/federated/FederatedPlanTReadWriteConsistencyTest.java`

- **검증**:
  - `mvn -q -DskipTests compile`
  - `mvn -q -Dtest=FederatedPlanMinSTRewireTest,FederatedPlanTReadWriteConsistencyTest,FederatedPlanCostEnumeratorTest test`

- **의사결정 근거(oracle/runtime/planner)**:
  - FED/FOUT이 “native로 불가”여도 LOUT→FOUT 물질화가 가능하면 runtime 관점에서 **사실상 가능 조합**이므로 후보를 열어 cost-based로 비교.
  - 물질화가 불가능하면 CP/FOUT과 derived FED/FOUT 모두 합법성이 깨지므로 후보를 닫음(휴리스틱 prune 아님).
