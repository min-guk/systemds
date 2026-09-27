# W1357 NativePoolWitness dynamic 변환 memo

## 상태

소스와 focused regression test 구현 완료. 패키지 빌드, 배포, workload replay 및 planning benchmark는 수행하지 않았다.

## 배경

readiness 변경 전체를 적용한 planning-only 비교는 약 `927.55s -> 923.53s`로 약 0.43% 차이에 그쳤고 선택 plan과 runtime program도 같았다. 따라서 그 변경은 성능 수정으로 채택하지 않았다. 이번 변경은 더 작은 할당 최적화만 독립적으로 검증한다.

`NativePoolWitness.withDynamicPartitionRanges()`는 같은 exact witness가 반복해서 ROW, COL 또는 FULL의 dynamic-range 증명으로 변환될 때마다 의미상 동일한 객체를 새로 만들었다. 이 변환은 proof path를 변경하지 않지만 공통 분석 과정에서 반복 할당을 유발할 수 있다.

## 변경

- 각 `NativePoolWitness` 인스턴스가 dynamic-range sibling 하나를 필요할 때만 생성해 보관한다.
- `exactPartitionRanges == false`인 witness는 즉시 자기 자신을 반환한다.
- exact ROW/COL/FULL witness만 같은 `fType`, endpoints와 partition intervals를 가진 dynamic sibling을 생성한다.
- candidate, proof path, matching 규칙 또는 candidate-space를 생략하거나 축소하지 않는다.
- 전역 cache, revision 간 cache 또는 resolver 외부 cache를 추가하지 않는다.
- cache 필드는 semantic fields, precomputed hash, `equals` 및 `matches`에 참여하지 않는다.

## 테스트 우선 검증

새 회귀는 reflection으로 기존 private `NativePoolWitness` 경계를 유지하면서 다음을 확인한다.

- ROW/COL/FULL 각각에서 같은 exact 인스턴스의 반복 변환은 같은 dynamic sibling을 반환한다.
- 이미 dynamic인 witness의 변환은 identity 기준으로 자기 자신이다.
- 변환 결과의 FType, endpoints, non-empty intervals, exact flag는 기존 생성 결과와 같다.
- exact witness와 dynamic witness는 계속 같지 않다.
- memo가 채워진 뒤에도 exact witness의 hash와 equality가 바뀌지 않는다.
- memoized 결과의 equality, hash와 `matches` 결과는 별도로 만든 기존 의미의 dynamic witness와 같다.
- BROADCAST, PART 및 OTHER처럼 지원하지 않는 FType은 exact witness도 identity 기준으로 자기 자신을 반환한다.

RED:

```text
Tests run: 40, Failures: 1, Errors: 0, Skipped: 1
Repeated conversion must reuse the exact witness's dynamic sibling
```

GREEN:

```text
NativePlacementContinuityTest: 40 tests, 0 failures, 0 errors, 1 skipped
Maven exit 0
```

최종 교차 검증은 위 40개와 이전 Exact/runtime-anchor/nary 회귀 48개를 한 Maven 호출로 실행했다.

```text
88 tests, 0 failures, 0 errors, 1 skipped
Maven exit 0
```

명령, 로그, Surefire XML 및 실행 메타데이터:

```text
/home/mchoi/w1357-diagnostics/witness-transform-20260925/source-validation
```

## Threading 분석

현재 witness와 resolver의 구축·조회는 planner 분석 인스턴스 안에서 단일 실행 흐름으로 제한된다. 따라서 일반 경로에서는 같은 exact witness에 대한 반복 호출이 동일 sibling identity를 재사용한다.

필드는 의도적으로 전역 동기화나 cross-instance coordination을 사용하지 않는다. 가정과 달리 같은 witness가 여러 thread에서 동시에 처음 변환되면 동일 값의 sibling이 중복 할당될 수 있다. 모든 의미 필드는 final이고 sibling 생성 인자는 immutable copy이므로 이 benign race는 FType, endpoints, ranges, hash, equality 또는 matching 결과를 바꾸지 않는다. cross-thread 객체 identity 동일성은 계약으로 제공하지 않는다.

## 잔여 검증 및 위험

- 실제 W1357 planning 시간과 allocation 감소는 아직 측정하지 않았다. 이 변경 채택 여부는 별도의 동일 조건 planning-only 비교로 판단해야 한다.
- exact witness마다 dynamic 변환이 실제 사용되면 sibling 하나가 witness 수명 동안 유지된다. 반복 임시 할당 감소와 교환되는 bounded per-instance retention이다.
- 테스트는 private class를 production API로 노출하지 않기 위해 reflection을 사용한다.
- 기존 memo disabled/eviction 및 dynamic reorg/reshape 회귀는 같은 전체 테스트 클래스 실행에 포함됐다.
