# LogReg 현재 병목 확인 및 제거

기준은 이전 reuse 최적화를 포함한 `experiments/optimizer-reuse-20261014/after-engine`이다. Git HEAD만으로는 해당 변경 전체를 나타내지 않으므로 frozen source/class hash manifest를 기준으로 삼는다. 원본 workspace와 기존 evidence는 보존한다.

1. 공식 Docker wrapper로 LogReg baseline JFR 1회. 이전 29.823초 non-JFR 수치와 새 JFR 시간을 혼합하지 않는다. 기존 프로파일 분포는 현재 병목의 증거로 재사용하지 않는다.
2. fresh 실행/할당 표본, 실제 phase timer, source call path를 결합해 dominant bottleneck을 확인한다. Solver support/propagation/bounds와 movement/context/legality를 분리한다.
3. 측정에 대응되는 최소 개선을 선택하고 production 변경 전 behavior regression을 고정한다. Pure immutable input 재사용을 우선하며 전체 조합 열거, 임의 pruning, UNKNOWN/DEFER 축약, exact primitive 변경을 금지한다.
4. 부분/완전 assignment, cache eviction 및 0 capacity, backtracking, normalization, finite support/node budget을 검증한다. 통합 46-class suite와 필요한 새 fixture, build, independent review를 통과시킨다.
5. 검증한 최종 engine으로 동일 조건의 LogReg JFR 1회. 시간·CPU·memory·hotspot·정확한 objective·selected supply·native rules·수치 출력·runtime audit를 비교한다. GLM 및 별도 non-JFR workload는 이번에 실행하지 않는다.
6. 변경 내용, 측정 원인, 개선 전후 근거와 잔여 병목을 문서와 SESSION_ISSUES에 기록한다. 단일 before/after 관측을 일반적인 speedup으로 확대하지 않는다.
