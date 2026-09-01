# SESSION ISSUES (2026-02-06)

## 이슈 1: `fed_refed`/`fed_fout`의 로컬→Federated materialize 경로 중복

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Runtime instruction 경로: `runtime/instructions/fed`
  - 대상 시나리오: CP 출력을 `fed_refed` 또는 `fed_fout`로 업로드/재배치
- **재현 절차**:
  1. `FEDRefedInstruction`의 `materializeFallback`와 `FEDFoutInstruction`의 local materialize 블록을 비교한다.
  2. 동일한 분할 계산/업로드/맵 구성 로직이 두 군데에 중복됨을 확인한다.
- **관측 증상**:
  - 기능은 동작하지만, 동일 로직이 2곳에 분산되어 수정 시 동기화 누락 위험이 높음.
  - 채널 종료 재시도, BROADCAST map 타입 정규화 같은 런타임 정책이 경로별로 불일치할 가능성이 있음.
- **원인 분석**:
  - `fed_refed`는 fallback 경로에 자체 구현을, `fed_fout`는 별도 구현을 유지하여 공통 로직이 분리됨.
  - 계획/런타임 모델은 유사한 업로드 단계가 필요하지만 코드 재사용 계층이 없었음.
- **해결 요약**:
  - 공통 helper `FEDLocalMaterializeUtil`를 추가해 로컬→Federated 업로드를 단일 구현으로 통합.
  - `FEDFoutInstruction` local path는 helper를 사용하도록 교체.
  - `FEDRefedInstruction`의 dimension-mismatch fallback도 helper를 사용하도록 교체.
  - `fed_refed`의 strict 제약(입력은 local이어야 함)과 direct path(앵커 슬라이싱/PUT_VAR)는 유지.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/runtime/instructions/fed/FEDLocalMaterializeUtil.java`
  - `src/main/java/org/apache/sysds/runtime/instructions/fed/FEDFoutInstruction.java`
  - `src/main/java/org/apache/sysds/runtime/instructions/fed/FEDRefedInstruction.java`
- **검증**:
  - 컴파일 검증:
    - `mvn -q -DskipTests compile`
    - 결과: 성공(에러 없음)
- **잔여 이슈**:
  - 본 변경은 코드 통합/정합성 개선 중심이며, 캐시 hit-rate 최적화(예: anchor key 확장)는 별도 과제.
- **잠재 회귀 위험**:
  - `fed_refed` mismatch fallback에서의 map type(`ROW/COL`→`BROADCAST` 강등) 처리 차이 가능성.
  - 감지 방법: `fed_refed`/`fed_fout` heavy hitter 및 federation map type assertion 테스트를 함께 실행.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 플래너 정책은 변경하지 않고 런타임 공통 경로만 통합해, 기존 계획 의미를 유지하면서 중복 구현 위험만 제거.
