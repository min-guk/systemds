# R54 공통 비용 회귀에서 PUBLIC 합성 fixture를 유지하는 근거

기본 지침은 PUBLIC 테스트를 ignore하지만, 사용자가 승인한 비용 통일 계획의 수용 기준은 동일 kernel의 CP/FED 가격 비교, DML call/body 실행 소유권, 실제 전송과 재사용 수명의 구분이다. PUBLIC 후보를 제외하면 이 비교 자체가 없어져 가격 오류를 놓친다.

따라서 R54는 기존 hermetic compiler-only PUBLIC fixture 및 공통 primitive unit test를 실행하고 필요한 비용 회귀를 추가한다. synthetic localhost URI의 실제 데이터에 접근하거나 보호 데이터를 수집하는 권한은 주어지지 않는다. PRIVATE/PRIVATE_AGGREGATE 대조 및 실제 선택 action/privacy 검증은 유지한다. 후보군, runtime 지원, TR/TW 및 recompile 제약은 완화하지 않는다.

DP-local/global 공통 비용 surface를 우선 검증하며 인접 planner 테스트는 공통 변경의 회귀 탐지 용도다. 새 장시간 runtime 캠페인을 실행하지 않는다. 이 예외는 해당 비용 검증 범위에 한정된다.
