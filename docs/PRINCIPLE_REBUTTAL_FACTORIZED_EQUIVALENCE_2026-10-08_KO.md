# Factorized 표현 동등성 검사에서 PUBLIC 단위 fixture를 유지하는 이유

AGENTS.md의 PUBLIC 테스트 제외 지침은 보호 데이터의 실제 FED 경로를 우선 검증하려는 목적이다. 이번 성능 검증의 LogReg, GLM, ROW weighted 입력은 PRIVATE_AGGREGATE로 실행한다.

단, 기존 CP family와 신규 FED relation의 합법 조합을 비교하는 작은 단위 fixture는 PUBLIC인 경우도 실행한다. CP 실행이 합법인 입력과 불법인 입력 양쪽을 확인해야 family가 privacy로 금지된 후보를 다시 허용하거나, 합법인 CP 후보를 잃는 회귀를 발견할 수 있다. PUBLIC fixture를 모두 끄면 사용자가 요청한 기존 방식과의 합법 조합·비용 동등성을 검증할 수 없다.

따라서 이 예외는 표현 동등성·privacy 음성 검사와 기존 회귀 검사에 한정한다. PUBLIC 단위 fixture의 시간을 실제 보호 workload의 성능 개선 근거로 사용하지 않는다. Privacy 규칙, Oracle 판정, 런타임 fallback 금지 원칙은 변경하지 않는다.
