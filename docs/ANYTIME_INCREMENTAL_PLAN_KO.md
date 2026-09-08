# Compact Global 대비 점진적 Anytime 인증 개선 계획

작성일: 2026-09-08. 최신 사용자 지시에 따라 Global에도 compact+exact를 적용하고, Anytime은 3%·5% 인증에 필요한 일부 coupling만 복원하는 방법을 구현·검증한다. 이전 fast 보고서의 비대칭 비교는 새 알고리즘의 성능 근거로 재사용하지 않는다.

## 목표와 평가 경계

- Global과 Anytime은 같은 원래 finite encoded model과 exact-preserving reduction/compaction을 사용한다.
- Anytime은 초기 유효 L과 feasible U를 구하고, 예상 또는 제한된 시험으로 측정한 인증 개선량/계산 비용이 큰 부분을 선택한다.
- 선택한 부분의 전역 relaxation coupling을 복원해 L을 강화한다. Incumbent 바깥을 고정한 Regional optimum을 global L로 사용하지 않는다.
- 영향을 받지 않은 부분의 계산 결과는 재사용한다. 목표 gap에서 즉시 종료하고, 미달·자원 제한은 그대로 보고한다.
- 3%·5%에서의 non-global-exact time-to-threshold를 평가한다. 1%는 compact Global exact를 권하는 범위이며 이번 근사 방법의 목표가 아니다.
- so007 native JVM planning-only. 네 Docker 환경의 bandwidth/RTT 수치만 사용하고 실제 Docker, worker, workload execution은 수행하지 않는다.

## 구현 방향과 확인할 사항

1. 기존 정확한 reduction/compaction을 exact compile과 분리해 공통 입력으로 제공한다. Global은 이 입력을 전체 exact로 푼다.
2. 작은 width MBE에서 생긴 replica relaxation의 독립 부분을 보관하고, consistency를 복원할 때 영향을 받는 부분만 재해결하는 경로를 검토한다. 현재 Nested 구현처럼 시험마다 전체 replica model을 다시 푸는 비용을 피하는 것이 목표다.
3. 후보 수, exact table/work, 진단 캐시와 retained memory를 제한한다. 실패한 시도도 비용에 포함하고, 중단된 후보를 성공 상태에 반영하지 않는다.
4. 원래 feasible seed의 modeled regret가 이미 threshold보다 큰 경우에는 U 개선도 필요하다. 기존 GLM seed regret는 WAN-mid 15.45%, WAN-heavy 20.76%이므로 동일 coupling 정보를 작은 primal repair에도 사용한다.
5. 초기 Regional 비용이 compact Global보다 크면 전체 속도 목표가 불가능할 수 있다. Seed·reduction·초기 LB·증분 갱신의 시간을 분리해 측정하고, 필요하면 compact 입력을 이용하는 seed 경로를 별도 ablation으로 검증한다.

## 검증과 정지 조건

- 작은 exhaustive oracle에서 모든 게시 checkpoint가 L≤C*≤U이며 단조적인지 검증한다.
- 독립 부분 재사용, equality 누적 보존, 상수/auxiliary 복원, numerical downward bound, cancellation/resource atomicity를 확인한다.
- Global compact와 legacy exact의 canonical objective parity를 검증한다.
- Native paired pilot은 같은 모델·입력·초기화 정책·목표에서 실행한다. 성공률, 전체 planner와 launcher TTT, 초기 비용·LB/U trajectory·실제 작업·peak RSS·exact 전환 여부를 포함한다.
- 결과를 보고 병목을 좁혀 개선을 한 번 더 평가한다. 실제로 빠르지 않은 사례를 누락하거나 exact 완료를 부분 탐색의 성과로 바꾸어 보고하지 않는다.

위 내용은 구현 시작 시점의 계획이며, 증분 자료구조의 최종 선택과 성능은 별도 검토·실험으로 확정한다.
