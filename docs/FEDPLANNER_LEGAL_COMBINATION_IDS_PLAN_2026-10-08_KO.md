# 합법 조합 ID 저장 및 downstream 소비 구현 계획

사용자 요청은 합법 판정이 끝난 **전체 조합의 ID만** 저장하고 같은 판정을 반복하지 않는 것이다. 이전 독립 product 최적화는 보존하고, 상관관계·혼합 전송·VALUE_MAP 등 명시적 support의 저장 경로를 일반화한다.

## 변경과 검증 순서

1. 기존 clause의 동등성·해시·canonical 순서·정확한 source/action/proof 복원 계약을 회귀 검사로 고정한다.
2. 이미 합법으로 생성된 support를 선택지 사전과 합법 조합 ID 배열로 보관한다. 불법 조합 bit나 전체 Cartesian product를 만들지 않는다. 후보 수 계산 overflow로 합법 후보를 버리지 않는다.
3. 기존 accessor를 통해 downstream이 ID에서 사전 정보를 읽도록 한다. 경량 ID handle과 원래의 완전한 clause 객체/입력 목록을 구분해 계측한다. 생성·조회에 oracle을 새로 호출하지 않는다.
4. 정확한 소유권과 선택 receipt를 유지하고, 기존 독립 factorized product를 명시적 ID 목록으로 펼치지 않는다. 판정 문맥은 immutable analysis/relation 수명에 묶는다.
5. 작은 명시적/indexed 모델의 합법성·비용·최적값·receipt를 비교한다. sparse correlated, 같은 source owner, DIRECT/RELOCATION 혼합, proof 차이, 큰 인덱스 및 불법 ID를 검사한다.
6. 관련 Java 회귀 검사와 `run_LAN_docker.sh`의 DP joint/privacy 사례를 실행한다. 결과와 실제 한계를 별도 문서에 남긴다.

## 추가 요청: 생성 단계에서 제약을 먼저 적용

- 물리 source/binding 열거는 선택할 때마다 같은 owner의 나머지 domain을 줄이고, 현재 남은 domain이 가장 작은 입력부터 탐색한다. 결과의 입력 위치는 보존한다.
- Oracle 입력 열거는 privacy 단항 필터를 먼저 적용하고, operation rule이 증명하는 부분 제약으로 forward checking 및 MRV를 수행한다. 완전한 tuple oracle을 전수 호출해 사전 표를 만드는 것으로 대체하지 않는다.
- 미할당 입력과 ABSENT_LOCAL을 구분한다. FED 불가능만 증명된 경우 CP 후보가 합법이면 보존한다. 복잡한 규칙에 부분 판정 계약이 없으면 UNKNOWN으로 남긴다.
- UDF는 일반 kernel의 부분 제약을 적용하지 않는다. 기존 함수 호출 placeholder가 가진 출력 정보와 함수 경계 조건은 보존한다.
- 비용이 싼 FType/value-version/identity/privacy 조건에서 탈락한 후보는 pool·oracle·joint 등 후속 검사에 전달하지 않는다. 조건이 읽는 정보가 다르거나 output-dependent이면 의미를 유지할 수 있는 지점에서만 앞당긴다.
- 추가 검증: dynamic MRV 순서, brute-force 합법 관계 parity, 부분 조합/완전 oracle 호출 수 감소, CP·privacy·UDF 및 shape evidence 보존.

## 판정과 표현의 경계

합법 ID 저장은 최초 조합 발견 자체를 없애지 않는다. 기존에 판정하지 않은 전역 공유 선택·joint 조건은 유지한다. 변경된 source/privacy 문맥에 예전 ID를 재사용하지 않는다. 새로운 후보 제거 가드나 runtime fallback은 추가하지 않는다.

완료 조건은 합법 관계와 비용을 보존하면서 일반 support 저장·downstream 조회가 ID와 공유 사전을 사용하고, 반복 조회가 clause 내용을 재구축하지 않는 것이다. 합법 후보 수만큼 필요한 비용 비교나 DP 상태가 모두 없어졌다고 주장하지 않는다.
