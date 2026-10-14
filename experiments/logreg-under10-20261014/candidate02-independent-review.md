# Candidate02 독립 정확성 검토

## 결론

**승인.** Candidate01 동결본과 최종 Candidate02를 대조한 결과, 차단할 정확성 결함을 찾지 못했다.
최종 검토 대상 SHA-256은 다음과 같다.

- `MemoOptimizer.java`: `e0cf3db74652f3d7b1402c3dd9001b4bac345afaacd671e66484369c918abca8`
- `MemoOptimizerDomainCacheTest.java`: `dae43bd2f06aab14ff8ab93b304ba663e7f995f3cd6a95e4b7e833b8a409a39f`
- 최종 격리 테스트 로그: `candidate02-targeted-junit-06.log`,
  `adc2d6220910c0268a114ea01172a78f81484e9799589e66a7d5a7738fb8d5ce`

이 판정은 소스와 단위 회귀에 대한 정확성 판정이다. Docker 성능 측정 결과나 `<10 s` 달성 여부를
미리 판정하지 않는다.

## 검토 결과

### 도메인 표현과 캐시 키

- 크기 63과 64는 `long` 전체 비트 마스크, 65 이상은 `BitSet`으로 분기한다. 크기 64는
  시프트 래핑을 피하려고 `-1L`로 초기화하므로 ordinal 63의 부호 비트도 보존된다
  (`MemoOptimizer.java:179-188`).
- compact 마스크는 `remove`와 역순 `undo`에서 기존 `BitSet` 및 cardinality와 함께 갱신된다.
  `-ea`에서는 각 변경 직후 `BitSet.cardinality()` 및 첫 word와의 일치를 검사한다
  (`MemoOptimizer.java:1142-1176`). Width 63/64/65의 최고 ordinal을 강제하는 회귀와 같은
  ordinal을 backtracking 뒤 복원하는 회귀가 cache 0/1/4096 결과를 비교한다
  (`MemoOptimizerDomainCacheTest.java:84-113`).
- 삽입 키는 compact 값을 복사하고 wide 값만 `BitSet.clone()`한다. 조회 probe는 동기식
  `Map.get` 동안만 현재 상태를 빌리고 `finally`에서 참조를 제거한다
  (`MemoOptimizer.java:1264-1274, 1336-1362`). 따라서 캐시 안의 키가 backtracking으로
  변하지 않는다.
- 해시는 id, fixed owner/value, 각 위치의 표현 종류와 전체 compact 값 또는
  `BitSet.hashCode()`를 섞는다. hit 판정은 별도로 domain 개수, 표현 종류, 각 compact 값 또는
  전체 `BitSet.equals`를 모두 검사한다 (`MemoOptimizer.java:1276-1314`). 해시는 가속기일 뿐이며
  동등성 증명이 아니다.
- wide collision 회귀는 Java `BitSet`의 singleton bit 0과 bit 32 해시가 같음을 먼저 확인하고,
  width 65의 서로 다른 상태에서 cache-off/on 해와 선택 ordinal 32가 같음을 검사한다
  (`MemoOptimizerDomainCacheTest.java:69-82`). 최종 wide 해시는 `BitSet.hashCode()`만 입력으로
  사용하므로 이 fixture의 두 상태는 실제 동일한 scoped hash를 갖는다.
- `support`는 동일한 check id, fixed owner/value, 전체 ordered scoped domains에서만 결과를
  재사용한다. 유한 support budget으로 생긴 `UNKNOWN`도 같은 budget을 가진 동일 optimizer의
  같은 상태에서만 재사용되므로 의미가 바뀌지 않는다. Budget 1의 cache-off/on 동등성은
  `finiteSupportBudgetUnknownIsPreserved`가 잠근다
  (`MemoOptimizerDomainCacheTest.java:60-67`).
- normalization은 dependency index와 대칭 adjacency를 다시 만들고 모든 check/cost/domain
  cache를 비운다. 따라서 정규화 전 id 또는 scope의 키가 정규화 후 해석으로 넘어가지 않는다
  (`MemoOptimizer.java:262-343, 367-395`).

### cardinality와 branch connectivity

- 기존 `domainCardinalities`가 모든 도메인 크기 판단의 기준이며 `remove`/`undo`에서만
  감소·증가한다. 새 compact 마스크는 같은 두 변경점에 결합되어 별도 변경 경로가 없다.
- `activeBranchNeighbors[owner]`는 현재 cardinality와 재구축된 대칭 adjacency에서 초기화된다
  (`MemoOptimizer.java:380-394`). 이 때문에 초기 propagation 뒤 normalization에서 singleton이 된
  owner도 정확히 반영된다.
- 이 값의 의미는 “해당 owner에 인접하고 cardinality가 1보다 큰 owner 수”이다. 따라서 어떤
  owner가 2→1이 될 때 모든 인접 owner의 count를 하나 줄이고, undo로 1→2가 될 때 하나 늘리는
  것이 정확하다. 3→2, 1→0, 0→1에는 active 여부가 바뀌지 않아 갱신하지 않는 구현이 맞다
  (`MemoOptimizer.java:1123-1172`).
- 모든 조회와 두 전이 직후에 기존 전체 스캔 결과와의 `-ea` assertion이 있다
  (`MemoOptimizer.java:1123-1131, 1148-1169`). 최종 36-test 실행은 assertions enabled였고,
  exhaustive parity fixture까지 통과했다.
- reduced core owner 목록은 `BitSet.stream()`의 오름차순으로 한 번 고정하고 기존 owner scan 두
  곳에서만 재사용한다 (`MemoOptimizer.java:899-904, 923-949`). owner 선택 조건과 tie-break 순서는
  바뀌지 않았고, `branchConnectivity`도 기존 스캔과 같은 값을 반환하므로 탐색 heuristic과
  lexicographic tie 의미를 보존한다.

### bound 갱신과 수치 표현

- no-op 판정은 참조 동일성 또는 `BigDecimal.equals`를 사용한다
  (`MemoOptimizer.java:1060-1068`). 따라서 수치는 같지만 scale이 다른 값은 생략하지 않아,
  baseline의 add/subtract가 만들 수 있는 노출 `BigDecimal` 표현을 보존한다.
- 새 bound는 no-op 판정 전에 항상 계산된다. `old == null`은 절대 생략되지 않으며 각 component
  슬롯은 생략 여부와 무관하게 새 참조로 갱신되고 dirty state도 해제된다
  (`MemoOptimizer.java:960-977, 1027-1068`). 비용 함수 호출, certified minimum 검사,
  UNKNOWN 처리 또는 최종 objective 조립을 건너뛰지 않는다.
- boundary exactness, UNKNOWN, finite node budget, cache 0/1/4096 및 lexicographic tie는
  `MemoOptimizerBoundaryBoundTest` 네 fixture가 검증하며 최종 targeted suite에 포함됐다.

## 검증 증거

- Candidate01 frozen source와 최종 source의 직접 diff를 전부 검토했다. Candidate02 production
  변경은 compact/wide scoped-domain 표현, exact incremental connectivity, stable core-owner 목록,
  representation-preserving bound no-op에 국한된다.
- `candidate02-main-javac-06.log`, `candidate02-test-javac-06.log`: 컴파일 성공. 출력은 incubating
  vector module 경고 3건뿐이다.
- `candidate02-targeted-junit-06.log`: `-ea`, **36 tests PASS**, 2.014 s. 이 묶음은 domain cache
  회귀 5개(신규 2개, 수정 1개 포함), boundary bound 회귀 4개, 기존 optimizer/exhaustive parity
  회귀를 포함한다.
- `git diff --check`는 최종 production/test diff와 이 문서에 대해 통과했다.

## 남은 한계

- 새 collision fixture는 내부 equality 호출 횟수를 계측하지 않는다. 다만 fixture가 같은 최종
  scoped hash를 만드는 사실은 hash 소스와 `BitSet.hashCode()` 동등 assertion으로 확인되고,
  production hit는 전체 domain equality를 반드시 통과한다.
- scale이 다른 동일 수치 bound만을 인위적으로 반복시키는 전용 회귀는 추가하지 않았다.
  scale-sensitive `BigDecimal.equals` 사용과 기존 scale-sensitive result equality 회귀를 소스 검토로
  확인했다.
- 통합 Maven suite와 authorized Docker 1회 측정은 root 검증 단계의 책임이며, 이 독립 검토는
  추가 workload를 실행하지 않았다.
