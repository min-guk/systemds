# 일반 FED·상관 관계의 압축 소비 구현 계획

기준선은 `e468797556`이다. 이전 scalar CP 전용 family와 독립 support 최적화를 일반화하며, 여섯 요청 항목을 별도로 검증한다.

1. **일반 FED rule**: exact key와 조건부 family를 구분한다. Capability뿐 아니라 profile·emission·output action의 의존 입력 축을 증명하고, 서로 다른 header를 조건부 region으로 보관한다. Tuple별 fact 복원은 정확한 member 조회/최종 선택 경계에서 수행한다.
2. **상관 support**: 입력별 dictionary와 합법 관계를 유지한다. 같은 source owner의 선택 일관성, proof와 DIRECT/RELOCATION, joint/VALUE_MAP 조건을 관계에서 검사한다. 임의의 sparse holes를 rectangular product로 확대하지 않는다.
3. **Physical Model**: exact support 관계 하나를 Alternative 하나와 source 선택 제약으로 연결한다. 생산자 선택으로 support member를 결정하고 최종 receipt만 복원한다. 물리 authority가 다른 branch는 조건부로 표현한다.
4. **비용**: 입력에 분리 가능한 항과 여러 입력의 geometry·shared supply에 의존하는 항을 구분한다. 분리 불가능한 `max(sum(worker bytes))` 등을 독립 최소값의 합으로 근사하지 않는다. 기존 비용 raw bits와 공유 비용 1회 청구를 비교한다.
5. **DP**: quotient의 preimage와 sparse relation join을 지연하고 이미 고정된 축과 호환되는 행만 탐색한다. 가능한 경우 selector 조건부 relation도 wildcard/product 형태로 유지한다. Canonical tie와 Local/Exact 최적값을 보존한다.
6. **실제 workload**: PRIVATE_AGGREGATE LogReg와 GLM을 동일 Docker 설정의 기준선/신규 엔진에서 실행한다. 생성량, analysis/model/cost/optimizer/compile 시간, 메모리와 학습 결과를 기록한다. 모든 성능 근거는 `run_LAN_docker.sh`에서 수집한다.

먼저 작은 explicit 전수 검사로 기존 합법성과 비용을 고정하고, 생산 경로에서 object count·입력 관계 수를 확인한다. 큰 sparse/product 검사는 객체를 모두 만든 뒤 개수를 줄인 결과와 구분한다. 일반 상관 관계의 저장은 최악의 경우 합법 row 수에 비례할 수 있으며, 어떤 compression도 임의의 관계 전체를 항상 작게 표현할 수 있다고 가정하지 않는다.

현재 stage/build/runtime 자료는 root 디스크 공간 제약 때문에 `/grid/3/cofee-lm-sweep-mchoi-20260914/general-factorized-plan-space-20261008/`에 저장한다. 소스와 작은 재현 자료는 저장소에 둔다.

## 검증 중 설계 수정

일반 FED header만으로 generation을 생략하는 최초 구현은 weighted LOCAL 출력의 DIRECT source binding을 잃었다. 따라서 일반 FED의 생성 생략과 미리 만들어진 header만으로 Closure를 우회하는 분기를 제거했다. 현재 scalar weighted의 일반 경로는 기존 Closure에서 action·source·proof를 먼저 확정하고, 같은 닫힌 authority를 공유하는 행만 relation으로 압축한다. 이 경우 tuple별 생성 비용은 남는다. Priority 1의 전체 생성 제거를 구현했다고 해석하면 안 된다.

압축 효과를 측정할 때 `생성된 fact`, `게시된 exact fact`, `relation region`, `logical tuple`, `최종 member 복원`을 따로 센다. Singleton wrapper를 늘린 것을 압축 성과로 보고하지 않는다. 참조 검증은 전체 Closure를 거친 명시적 기준선과 대조하고, 새 relation을 사후 전개한 결과만으로 의미 보존을 판단하지 않는다.
