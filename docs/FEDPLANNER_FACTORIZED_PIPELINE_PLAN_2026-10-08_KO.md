# Factorized plan space 구현 계획

기준선은 이전 rule-directed generation 구현을 포함한 frozen engine이다. Git HEAD와 비교하면 이번 변경의 효과와 이전 변경의 효과가 섞인다.

## 확인한 전개 지점

- Oracle의 rectangle을 CandidateGenerator가 tuple별 key/profile/emission/fact로 만든다.
- Closure source inventory는 support clause를 모두 읽은 뒤 동일한 source/pool을 다시 합친다.
- Support 삭제 고정점은 product의 모든 clause에 reverse incidence를 만든다.
- ExactPhysicalModel은 제한된 independent product 밖에서 clause별 Alternative를 만든다.
- Solver의 lazy factor도 freeze/materialize 단계에서 전체 scope table을 만든다.

## 구현 순서와 정확성 조건

1. Support 삭제를 입력 축 option의 reverse dependency로 처리한다. 기존 explicit 삭제 결과와 연쇄 삭제·cycle·action/proof identity를 비교한다.
2. Source inventory는 실제 사용하는 reference, pool witness, exact-layout 정보만 색인한다. 이 정보가 일정한 product는 clause를 읽지 않는다.
3. Solver에 이미 합법인 row의 sparse factor를 전달하고, validation/freezing/elimination에서 전체 Cartesian table을 만들지 않게 한다. Local 경계와 기존 functional factor에도 연결한다.
4. Candidate family는 정확한 key와 구분한다. 우선 실행·전송 비용 및 합법성이 축 선택과 무관함을 증명할 수 있는 scalar CP/LOUT 영역을 대상으로 한다. FED의 FType/source/action 차이를 대표 key로 덮지 않는다.
5. 선택된 tuple만 기존 analysis-owned fact/receipt로 복원한다. 비분리 비용, VALUE_MAP, 함수 경계 등 아직 직접 소비하지 못하는 경로는 정확한 기존 표현을 유지하고 결과에 명시한다.

## 검증

변경 전후의 합법 조합·비용 bits·Local/Exact 최적값·source/action/proof를 작은 exhaustive fixture로 비교한다. 큰 product에서는 materialization counter와 stored row 수를 검사한다. 실제 실행은 지정된 Docker harness의 PRIVATE_AGGREGATE weighted 및 joint/function/privacy 사례로 검증한다. 시간·메모리 측정의 변동과 샘플링 한계를 함께 기록한다. 전체 end-to-end 요구는 Candidate→Closure→Cost→DP의 실제 연결 증거가 있을 때만 완료로 보고한다.
