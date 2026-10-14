# LogReg main 통합 독립 검토

## 범위와 기준

- 통합 worktree: `f9faf7b97a` 위에 기능 commit과 문서를 적용한 현재 `HEAD`
- 검증 후보: `/home/mchoi/fedplanner-oracle-cost-20261013`의 최종 Candidate03 source
- 집중 파일: `MemoProgramFacts`, `MemoProgramModel`, `MemoRuleBuilder`, `MemoShapes`
- 검토 방식: Candidate03와 통합 source의 전행 diff, `f9faf7b97a..HEAD` diff, main 전용 회귀와 직접 생성자 call site를 읽었다. 이 검토에서는 source, index, Git state를 수정하지 않았다.

## 판정

**APPROVE WITH FIX APPLIED.** Production 통합에서 정확성을 해치는 충돌은 찾지 못했다. Candidate03의 guard/loop cost projection, movement reuse, exact optimizer 개선은 유지되며, main 전용 request authority, shared privacy, conditional-return shape 처리가 함께 남아 있다.

검토 중 한 건의 test compile blocker를 발견했다. `MemoRuleBuilder.Result`에 request-local `MemoPrivacy`가 추가되어 record component가 여섯 개인데 `MemoAnalysisReuseTest`의 수동 생성자가 다섯 인자를 사용했다. Root가 `original.requests()`를 여섯 번째 인자로 추가했다. 수정 전 실패는 `compile-before-api-fix.log`에 보존됐으며, 현재 call site는 새 API와 일치한다.

## 통합 근거

- `MemoProgramFacts`는 Candidate03의 loop/guard index를 유지하면서 함수별 call index를 program-fact 순서로 별도 동결한다. `callsForFunction`은 inlined call도 포함하고 immutable list를 반환한다. 기존 전체 `calls()` 자료는 그대로 남아 있다.
- `MemoRuleBuilder`는 전달된 `MemoShapes`가 동일 facts/source snapshot 소유인지 먼저 검사한다. 규칙 생성에 사용한 하나의 `MemoPrivacy`를 결과에 담아, source-grounded privacy pruning과 모델 legality가 동일한 immutable request 분석을 공유한다.
- `MemoProgramModel`은 `universe.privacy()`와 그 resolver를 재사용하고 facts/source identity를 fail-closed로 검사한다. 따라서 Candidate03 movement/cache 구조와 main의 privacy/residency 해석 사이에 별도 resolver 또는 별도 source snapshot이 생기지 않는다.
- `MemoShapes`는 Candidate03 proof reuse를 유지한다. 통합본의 phase-shape cache는 projected key 대신 main의 full `ShapeContextKey`를 사용하며 guards, loop phase, iteration, cardinality, call frame, max depth와 모든 `headIsBound` 상태를 포함한다. Completion-only guard와 다른 return의 bound 상태를 payload dependency projection에서 잃지 않는다. Mutable selection lookup은 `BitSet`으로 snapshot하며 rule/layout은 logical shape key에서 제외한다.
- Conditional call return은 completion binding을 먼저 해석하여 `TERMINATED_AT_RETURN`을 shape join에서 제외하고 `NORMAL_CONTINUATION`만 payload shape에 반영한다. Consumer activation에 normal continuation이 없으면 frozen normal-path shape를 되살리지 않고 UNKNOWN을 반환한다.
- Main의 `MemoPrivacy`, `MemoResidency`, `MemoBindingResolver`는 `f9faf7b97a..HEAD`에서 변경되지 않았다. 해당 main 전용 privacy/residency 동작은 cherry-pick에 의해 덮이지 않았다.
- Candidate03와 통합본의 네 파일 차이는 위 main 전용 request/call-index/return-shape 변경과 분석 counter뿐이다. 충돌 marker나 미해결 TODO는 없다.

## 남은 검증 경계

- Source review는 통합 의미를 승인한다. 최종 판정에는 수정 후 Maven compile, 계획된 55-class suite, 그리고 별도의 integrated Docker run 결과가 추가되어야 한다.
- Full `ShapeContextKey`는 projected key보다 cache cardinality가 커질 수 있다. 이는 정확성을 보수적으로 지키는 선택이며, 통합 Docker 측정에서 성능과 메모리만 확인하면 된다.
