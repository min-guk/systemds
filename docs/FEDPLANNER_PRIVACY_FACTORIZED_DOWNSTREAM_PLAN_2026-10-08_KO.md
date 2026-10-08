# Oracle privacy pruning과 factorized downstream 실행 계획

## 목표와 보존 조건

Oracle의 입력 조합 검사에 authoritative privacy 정책을 함께 적용해 불법 입력과 출력을 후보 할당 전에 제거한다. 알 수 없는 privacy는 기존 정책대로 보수적으로 처리한다. 기존 early/late privacy의 최종 합법 관계, runtime capability, TR/TW 및 recompile 규칙을 보존한다.

Factorized support를 cost model과 production local DP까지 유지한다. 입력별 source 선택은 기존 producer decision variable로 표현하고, consumer와 producer 사이의 membership 제약으로 연결한다. 선택된 producer assignment에서 정확한 support clause 하나를 복원해 기존 receipt 검증을 통과시킨다. 비용 공유, input geometry, source/action 객체 authority를 보존한다.

## 변경 순서

1. Generator를 직접 호출하는 privacy 회귀 검사를 먼저 실행한다. 입력 마스킹을 거치지 않은 호출에서도 불법 조합이 oracle 호출이나 candidate allocation으로 이어지는지 확인한다.
2. 독립적인 relocation support의 작은 전수 모델과 큰 product 회귀 검사를 마련한다. 기존 downstream에서 product가 펼쳐지는 사실을 확인한다.
3. Placement 계층에 immutable independent-product 조회 및 선택 API를 추가한다. Consumer는 realization당 압축 header를 만들고 입력 축별 allowed support 제약을 연결한다.
4. Cost model은 고정 relocation action의 입력 geometry와 공통 witness를 직접 소비한다. Semantic fingerprint도 축과 공통 proof를 직접 해시한다. 최종 선택에서만 graph-owned clause를 복원한다.
5. 작은 명시적 모델과 합법 assignment·비용·receipt를 비교하고, 큰 product의 retained options/후보 수/실제 clause 생성 수를 측정한다. 관련 Java 회귀 검사 후 `run_LAN_docker.sh`로 실제 DP를 검증한다.
6. 변경·측정 결과와 남은 한계를 별도 한국어 결과 문서와 세션 이슈 문서에 기록한다.

## 압축 적용 경계

첫 적용 대상은 입력 축마다 source owner와 relocation action이 고정되고, 축 사이 source owner가 서로 다르며, support identity와 binding이 일대일인 실제 독립 product다. 공통 proof/witness와 input-authority 불변성이 필요하다. Joint/value-map/logical-boundary 등 clause를 직접 관찰하는 관계는 명시적 경로를 유지한다. 이는 합법 후보를 없애는 pruning이 아니라 표현 방식 선택이다.

Header에 사용할 owned 대표 clause 한 개와 최종 선택 clause 한 개는 허용한다. 전체 product를 순회한 뒤 캐시하거나 지연 순회하는 구현은 완료 조건을 충족하지 않는다. 압축할 수 없는 상관 관계는 기존 정확한 관계를 보존한다.

## 검증 기준

- Privacy: 불법 입력은 rule key/oracle 전에, 불법 출력은 emission 전에 제외하며 최종 합법 관계는 기존 결과와 동일하다.
- Downstream: 100×100 support는 10,000개 consumer alternative로 늘어나지 않는다. Model/cost/fingerprint/DP 준비 중 clause 생성은 상수 개수이고 입력 선택지는 합에 비례해 보관한다.
- 정확성: 작은 전수 baseline과 최적 비용 및 선택된 정확한 receipt/action이 일치한다. 공유 전송과 source 제약을 유실하지 않는다.
- Runtime: 보호된 데이터 fixture의 Docker local DP 결과가 CP 수치 fingerprint와 일치하고 암묵적 runtime conversion이 없다.
- 전체 DP의 최악 복잡도가 다항 시간이 된다고 주장하지 않는다. 남은 공동 제약과 그래프 구조에 따른 비용은 별도로 남는다.
