# Solver cache lookup 중복 할당 제거

- 변경 위치: MemoOptimizer의 truth/cost/support/conditional-bound cache lookup과 TupleKey.
- 기존 full id+int tuple hash/equality 및 immutable 저장 key는 유지한다. Hit 검사 동안만 invocation-local probe가 입력을 빌려 쓰고, Map.get이 끝나면 즉시 참조를 해제한다. Predicate/cost evaluator는 probe가 reset된 뒤에 실행한다.
- Hit에는 TupleKey/clone을 생성하지 않고, miss 결과 저장 시에만 배열을 복사한다. Revision/domain content key의 의미, bounded eviction, cache capacity0, normalization 시clear, UNKNOWN 및 exact BigDecimal 값을 변경하지 않는다.
- 대규모 dense table/domain-content support cache/branch-order 변경은 추가하지 않았다.
- BEFORE lock: frozen25662ee7b4 엔진으로 기존21+hash collision 신규1 =22 tests PASS. `(0,31)/(1,0)`, `(-1,31)/(0,0)` hash충돌에도 cache0/1/4096에서 status/exact cost/selection을 보존한다.
- AFTER targeted: 새 solver bytecode로 동일22 tests PASS. 최종 통합compile/fullsuite/독립review예정.
- C 구조 재사용은 Facts occurrence-context 캐시와 Shapes full-context proof 캐시를 공통 경로에 적용하므로 MemoProgramModel에 중복 cache 계층을 만들지 않는다. Movement의 기존 DIRECT/IMPOSSIBLE gate, input legality/UNKNOWN, original selected-cost audit는 그대로 유지된다.
