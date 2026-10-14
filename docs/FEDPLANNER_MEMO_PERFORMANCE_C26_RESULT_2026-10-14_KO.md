# FedPlanner Memo 성능 최적화 C26 마무리

## 결론

사용자 요청에 따라 **정확성이 검증된 C26을 현재 최종본으로 게시**한다. 두 workload 모두 3초 이내 목표는 달성하지 못했다. 근사화·후보 축소·privacy 완화·runtime fallback은 추가하지 않았다.

| 공식 Docker 각 1회 | C24 | C25 (철회) | C26 (최종) | 3초 기준 |
|---|---:|---:|---:|---|
| LogReg 전체 planning | 3.115664233s | 3.525030810s | **3.028378372s** | 미달 |
| GLM 전체 planning | 5.225493361s | 6.133669572s | **5.072434165s** | 미달 |

시간은 Facts+Rules+Model+Optimization+Projection이며 학습 runtime이 아니다. 같은 pinned Docker image,4CPU/8GiB,W3,동일 데이터·privacy·JVM·JFR 조건이고 warmup/성능 재시도 없이 각각 1회다. 단일 관측이므로 특정 변경의 인과적 speedup 또는 평균 성능을 주장하지 않는다.

## 최종 변경 범위

- 정확한 check/cost 상태 투영과 지원 witness 재사용, unary 증명 및 정확한 forest-tail 해결을 통해 중복 탐색을 줄였다. UNKNOWN/DEFER와 전역 최적해·tie-break 의미를 보존했다.
- 완전한 Oracle 입력 identity를 사용하는 요청 재사용, immutable fact/context 분석 재사용, contextual shape와 native/movement 비용의 정확한 재사용을 적용했다.
- 모든 source 조합을 생성한 뒤 삭제하지 않으며, 기존 feasibility/privacy/worker binding·정확한 binary64 primitive 및 BigDecimal 합산 의미를 유지한다.
- 최종 C26은 이동 그룹 안에서 이미 동일성이 검증된 origin을 private event key에서 제거하고, scope 중복 검사에서 boxed stream을 primitive 검사로 바꾼 작은 단순화다.
- 성능이 악화된 C19와 C25는 채택 철회했다. 이후 실험 후보는 production에 포함하지 않았다.

## 검증과 한계

- Maven compile/test-compile 성공, 선택한104classes/686tests 통과. 전체 repository suite 통과라는 주장은 아니다.
- 두 workload 모두 OPTIMAL, exact objective, native universe, CP/FED 출력 및 privacy/runtime audit 통과.
- 원 evaluator19/23: 두 시간 gate와 C21부터 달라진 native request/generation telemetry로 인한 원 reference construction gate2개 실패다. 검사를 수정하거나 면제하지 않았다.
- GLM 일부 search traversal counter는 C24와 다르지만 C23과 같으며 exact objective·출력은 동일하다. 변경 원인은 단일 실행으로 확정하지 않는다.
- C26 GLM 단계별: Rules2.279118496s,Model1.242508682s,Optimization1.375370440s. 아직 규칙/모델 구성과 optimizer 모두 개선 여지가 있다.

## 근거

- [최종 실측 보고서](evidence/memo-performance-c26-2026-10-14/candidate26-runtime-report.md)
- [원 evaluator](evidence/memo-performance-c26-2026-10-14/candidate26-evaluation.json)
- [전체 회귀 로그](evidence/memo-performance-c26-2026-10-14/candidate26-unit.log)
- [파일/실행 검증](evidence/memo-performance-c26-2026-10-14/candidate26-verification.json)
- [누적 문제·설계·회귀 기록](SESSION_ISSUES_2026-10-14.md)

후속 요청인 ML training+P1/P2+SliceLine2종,workers1/3/5/7의 전체 planning 검증은 이 결과와 별도로 수행·보고한다.
