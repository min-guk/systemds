# AggLocal 공개 sibling 회귀 검증의 한정 예외

## 충돌과 근거
AGENTS.md의 기본 작업 순서는 DP 우선이며 PUBLIC privacy 테스트를 제외한다. 그러나 최신 사용자는 공개 worker-Y를 사용하는 L2SVM의 AggLocal 정책 회귀를 분석한 뒤, 해당 규칙 수정 및 AggLocal 전용 재실험을 명시적으로 요청했다. PUBLIC Y 사례를 제외하면 바로 그 회귀를 검증할 수 없다.

## 한정 예외
- 이번 변경에서 AggLocal 및 PUBLIC sibling-vector 회귀 테스트를 실행한다. PRIVATE_AGGREGATE X의 보호는 유지하며 PRIVATE_AGGREGATE Y 비수집 대조군도 함께 검사한다.
- privacy/runtime/배치 합법성 규칙이나 assert를 완화하지 않는다. 기존 정확한 candidate/owned receipt를 사용한다.
- 실험은 run_LAN_docker.sh만 사용한다. 다른 planner를 재실험하거나 변경하지 않는다. compile/runtime timeout을 추가하지 않는다.
- 이 문서는 PUBLIC 원본 데이터의 무단 수집이나 모든 테스트 제외 지침의 일반 폐지를 허용하지 않는다.
