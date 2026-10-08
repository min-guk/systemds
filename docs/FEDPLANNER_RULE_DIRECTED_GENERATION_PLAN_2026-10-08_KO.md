# Rule 기반 입력 관계 생성 최적화 계획

목표는 정방향 Oracle의 합법 조합·proof·privacy 의미와 비용 기반 선택을 보존하면서 후보 생성량, Oracle 호출, 분석 시간과 메모리를 줄이는 것이다. 기존 ID/MRV 구현을 비교 기준으로 유지한다.

현재 소스에서 확인한 병목은 두 층이다. `PlacementCandidateGenerator.buildNode`는 입력 FType tuple마다 rule key와 exact Oracle evidence를 만든다. MRV가 불가능한 가지를 제거해도 살아남은 독립 입력들의 product는 leaf로 전개된다. `PlacementRelationClosure.generateRelocationBindingProduct`는 그 아래의 source/action 조합을 구성한다. 독립·uniform product만 처음부터 압축되며, 나머지는 clause 구성 비용이 발생할 수 있다.

첫 구현은 Oracle 규칙을 별도로 복제하지 않고 **규칙이 결과에 영향을 주는 입력 위치를 선언하고, 해당 축에서 기존 `caps`를 평가해 실행 관계를 구성**하는 방식으로 한다. 동일 결과를 갖는 나머지 축은 rectangle로 유지한다. 초기 대상은 shape proof에 의존하지 않는 weighted 규칙이며, 다른 규칙은 같은 계약을 입증할 수 있을 때만 포함한다. CP가 허용되는 영역을 제거하지 않는다. 보호 privacy 때문에 FED가 필수일 때만 CP-only 영역을 생성 전에 제외한다. Public Oracle API나 mutable ShapeHint를 전역 캐시하지 않는다.

둘째 구현은 실제 source/action product가 소비 단계에서 다시 펼쳐지는 조건을 확인하고, uniform delivery 의미를 입증할 수 있는 경우 압축 소비 범위를 확장한다. DIRECT와 RELOCATION, 공유 owner, VALUE_MAP/joint, 함수 경계, proof를 단지 FType이 같다는 이유로 합치지 않는다. 구현 범위와 미지원 경로는 결과 문서에서 구분한다.

실행 순서는 다음과 같다.

1. 기존 소스·테스트·Docker 실행을 기준으로 보존하고 병목 및 계측 항목을 확인한다.
2. 기존 정방향 Oracle 전수 결과와 작은 명시적 source product를 동등성 기준으로 테스트한다.
3. Rule 관계 생성과 generator 연결, 증명 가능한 source product의 생성·소비 개선을 구현한다.
4. 관련 회귀 테스트와 비용·최적값·선택 receipt 동등성을 검증한다.
5. `run_LAN_docker.sh`의 동일 Docker 조건에서 DP 실행 결과, 후보/Oracle 작업량, compile/planning 시간과 메모리를 비교한다.
6. 측정 근거·변경 파일·효과·남은 전개 지점을 결과 문서에 기록한다.

통과 조건은 작은 전수 공간에서 합법 tuple의 누락·추가가 없고, 원래 caps/proof와 비용·최종 실행 결과가 일치하는 것이다. 시간·메모리는 실제 측정 범위만 보고하며, source product cardinality와 실제 materialization 수를 구분한다. 개선이 없는 경로도 숨기지 않는다. Runtime fallback, privacy 완화, 임의 후보 폐쇄, 새 의존성 도입은 하지 않는다.
