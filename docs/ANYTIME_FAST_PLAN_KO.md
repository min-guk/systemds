# AnytimeTarget 3%·5% 인증 지연 개선 계획

작성일: 2026-09-08. 사용자 요청에 따라 C를 포함한 전체 비교를 중단하고, AnytimeTarget과 Global의 목표 인증까지 걸리는 시간에 집중한다. 이전 본 실험 350행과 중단 증거는 보존한다.

## 관측한 병목

GLM 첫 반복의 WAN-light/mid/heavy에서 AnytimeTarget은 진단·exact preparation에 약 11.52/10.12/13.57초, MBE에 약 1.45초, Regional solve에 약 0.72/0.73/1.68초를 사용했다. Global은 같은 모델을 약 9–11초에 풀었다. 이 수치로 준비 비용이 큰 것은 확인했지만, 특정 내부 함수의 기여 비율까지 sampling profiler로 확정한 것은 아니다.

현재 조건부 모델은 모든 auxiliary를 보존한다. Exact reduction 후 domain이 하나인 변수도 categorical compiler의 네 가지 greedy elimination-order 계산에 남는다. 첫 개선은 이 확정된 변수를 대입하고 남은 변수만 compile하는 별도 preparation 경로다.

## 첫 번째 변경과 안전 계약

1. 기존 raw freeze, factor/cell cap 검사, arc consistency와 exact quotient를 그대로 수행한다.
2. Reduction 후 singleton 변수를 각 factor에서 대입한다. 모든 factor의 순서와 상수 factor를 보존한다.
3. 남은 non-singleton 변수만 compile하며, 결과를 원래 변수 순서와 representatives로 복원한다.
4. 기존 Global의 prepare/solve 경로는 유지하고 RegionalSearchProblem의 preparation에서 명시적인 compact 경로를 호출한다.
5. Auxiliary의 값을 incumbent로 임의 고정하지 않는다. 원래 hard constraints의 reduction으로 singleton이 된 경우만 대입한다.
6. Canonical feasibility와 objective raw-bit 검증, 보수적인 LB와 threshold 판정은 유지한다.

필수 회귀: all-singleton/zero-free model, singleton과 non-singleton 혼합 및 비연속 인덱스, auxiliary 조건 변경, 상수 비용, raw cap을 넘는 lazy factor의 사전 거절, infeasibility, compact 전후 exhaustive optimum 및 canonical bit parity. Global 경로가 기존 통계를 유지하는지도 확인한다.

## 작은 비교부터 진행

첫 진단 pilot은 PCA, LM, StepLM, GLM × LAN/WAN-mid × 3%/5% × Global/AnytimeTarget로 32회다. 이전 JAR의 baseline을 새로 측정하고, 동일 입력·모델·seed·하한 시작 설정에서 compact 변경을 비교한다. 단일 진단 반복의 작은 시간 차이로 통계적 우위를 주장하지 않는다.

주요 지표는 목표 달성 수/전체 시도 수, 전체 planner 시간, launcher→첫 인증 시간이다. Preparation, MBE, Regional의 시간과 실제 작업량을 함께 기록한다. 실패와 미달을 제외한 시간만으로 채택하지 않는다.

Compact 변경 후에도 하한이 약하면, 동일 reduced global model을 이용한 MBE strengthening을 별도 변경으로 검토한다. 이 후속안은 아직 구현·검증하지 않았으며, 초기 raw MBE로 이미 목표를 달성하는 경우에 추가 준비 비용을 부과하지 않는 구조가 필요하다.

유망한 변경만 추가 workload/network와 paired repetition으로 검증한다. 모든 입력에서 Global보다 항상 빠르다는 보장은 목표로 주장하지 않는다. Native so007 planning-only, 기존 legality/privacy/canonical 계약, Docker의 bandwidth/RTT 수치만 사용하는 제약을 유지한다.
