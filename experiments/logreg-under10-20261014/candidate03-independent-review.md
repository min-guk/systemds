# Candidate03 독립 정확성 검토

## 결론

**소스 동결 승인.** Candidate02 frozen source와 Candidate03 최종 diff를 대조한 결과, exact
conditional-forest tail이 기존 해의 합법성, 비용, UNKNOWN, 자원 한도 및 전역 lexicographic tie를
깨는 차단 결함을 찾지 못했다.

최종 검토 대상 SHA-256은 다음과 같다.

- `MemoOptimizer.java`: `533e7128b2ec9fcbb41671609bb50ca5e1a8a22cb877e1bccab0ba8fb99705ef`
- `MemoOptimizerForestTailTest.java`: `619b7ecb7d784b68b1ab1e362f0198ee17be87905ae5bcbc6cbe6c21dee9dfc9`
- `candidate03-targeted-junit-08.log`: `44309dace69e9b0b78e6ad676d9e56257893e5757144994464274764f503fdfb`

이 판정은 production source와 targeted regression에 대한 정확성 승인이다. 전체 suite와 단 한 번의
공식 Docker/JFR 결과는 root 검증 단계에 남아 있다.

## 구조적 적용 조건

- Tail은 기존 `propagate`, assignment refresh, lower-bound prune 뒤에만 시도된다. 따라서 이미
  모순이거나 기존 incumbent로 안전하게 prune된 상태를 다시 해석하지 않는다
  (`MemoOptimizer.java:932-945`).
- 현재 cardinality가 1보다 큰 core owner만 active residual graph에 남긴다. 기존
  `branchAdjacent`는 모든 scope를 clique로 연결하고 parallel edge를 `BitSet`으로 deduplicate한
  그래프에서 만들어졌으므로, active induced graph가 forest이면 모든 projected scope의 arity도
  최대 2다. 세 active owner가 남은 scope는 triangle을 만들어 사전 검사에서 거부된다
  (`MemoOptimizer.java:971-997`).
- BFS parent가 이미 정해진 non-parent neighbor를 만나면 실제 cycle로 판정한다. 구조가 forest가
  아니거나 남은 node budget이 모든 cell을 수용하지 못하면 factor bucket이나 DP cell을 만들기
  전에 기존 ordinary enumeration으로 돌아간다. 이 admission fallback은 domain, incumbent 및
  assignment를 변경하지 않는다.
- 구조 검사가 끝난 뒤 원래 check/cost scope를 현재 singleton에 따라 constant, unary, binary로
  분류한다. 평가는 `tailValues`가 고정 값과 1~2개 후보 값을 **원래 scope 순서**에 복원한 tuple로
  기존 `evaluate`/`evaluateCost`를 호출하므로 predicate, cache identity 및 certified-minimum 검사를
  우회하지 않는다 (`MemoOptimizer.java:998-1014, 1094-1115`).

## 비용을 정확히 한 번 더하는 근거

- Conditional constant는 기존 `base`에서 시작한다. Projected arity-0 term과 singleton core
  owner의 `ownerMessageCosts[owner][rule]`만 여기에 한 번 더한다
  (`MemoOptimizer.java:1008-1022`).
- Variable core cell은 해당 rule의 `ownerMessageCosts`에서 시작해 unary term, child cell 및 binary
  edge term을 한 번씩 더한다 (`MemoOptimizer.java:1025-1068`). Parallel factors는 같은 graph edge를
  공유하지만 각 id가 edge bucket에 보존되어 하나도 유실되지 않는다.
- `ownerMessageCosts`에는 native cost와 기존에 제거된 boundary-tree message가 이미 들어 있다
  (`MemoOptimizer.java:872-905`). Tail cell은 그 비용을 다시 더하지 않고 boundary root/rule만
  reconstruction child로 붙인다. 따라서 native, boundary, unary/binary term과 `base`가 각각 한 번만
  최종 합에 들어간다.
- 계산은 기존 `BigDecimal` 값과 evaluator를 그대로 사용하며 `MathContext`를 추가하지 않았다.
  신규 exhaustive fixture는 negative/dyadic term, arity-0 base, projected high-arity term 및 parallel
  edge를 포함하고 scale-sensitive `BigDecimal.equals`로 결과를 비교한다
  (`MemoOptimizerForestTailTest.java:55-72, 139-159`).

## UNKNOWN, infeasible 및 node budget

- Constant check가 `UNKNOWN`이면 즉시 ordinary enumeration으로 돌아간다. Unary/edge cell에서
  발견한 UNKNOWN은 local flag와 미게시 `tailCells`에만 남고, 모든 cell 생성 뒤 ordinary
  enumeration으로 돌아간다. `bestCost`와 `bestAssignment`는 모든 component의 exact root를 고른
  뒤에만 갱신된다 (`MemoOptimizer.java:1008-1013, 1023-1092`). 따라서 UNKNOWN이나 exhaustion의
  부분 DP를 optimum으로 게시하지 않는다.
- UNKNOWN과 알려진 NO가 섞이면 구현은 UNKNOWN 경로를 보수적으로 fallback한다. UNKNOWN이 전혀
  없고 constant가 NO이거나 exact forest root에 가능한 rule이 없을 때만 현재 branch를 완료된
  infeasible proof로 닫는다.
- Tail admission은 현재 active domain cardinality의 합을 필요한 cell 수로 계산한다. 전체가 남은
  전역 한도 안에 들 때만 DP를 시작하고, 각 실제 cell 직전에도 한도를 다시 검사한 뒤 `nodes`와
  `forestTailCells`를 하나씩 증가시킨다 (`MemoOptimizer.java:993-997, 1030-1033`).
- 신규 회귀는 무제한 실행에서 관측한 정확한 node 수를 한도로 준 실행도 동일 OPTIMAL/cost/selection을
  반환하고, 한 개 작은 한도에서는 `RESOURCE_EXHAUSTED`가 됨을 확인한다
  (`MemoOptimizerForestTailTest.java:107-117`). Support budget 1에서 partial UNKNOWN이던 고차 check를
  tail의 complete tuples가 전부 NO로 판정하는 별도 fixture는 `INFEASIBLE`과 실제 tail completion을
  확인한다 (`MemoOptimizerForestTailTest.java:96-105`).
- Admitted tail이 UNKNOWN 뒤 fallback하면 이미 검사한 cell은 전역 node budget에 남는다. 이는
  설계된 실제 작업량 계측이며, 부족한 budget에서 OPTIMAL을 허위 게시하지 않는다.

## 전역 lexicographic tie

- `tailCells=cells.clone()`은 기존 removed-owner cell 행을 보존하고 core owner 행만 새로 만든다.
  각 variable core cell의 children에는 dynamic forest children과 해당 owner에 붙은 모든 기존
  `BoundaryTree.root/rule`을 함께 넣는다 (`MemoOptimizer.java:1023-1075`).
- Singleton core owner에 붙은 boundary tree는 local candidate에 즉시 reconstruct한다
  (`MemoOptimizer.java:1015-1022`). Variable child/root 동률은 기존 `compareTreeChoice`가 새 core
  subtree와 보존된 removed subtree를 모두 완전한 owner-id 배열로 reconstruct한 뒤 비교한다.
- 따라서 제거된 leaf owner id가 core owner보다 앞서거나 여러 component 사이에 interleave되어도
  subtree-local rule 번호가 아니라 완전한 original assignment의 사전순을 사용한다. 신규 fixture는
  더 이른 removed leaf가 core rule 선택을 뒤집는 동률에서 `[0,1,0,0]`을 확인한다
  (`MemoOptimizerForestTailTest.java:74-83`).
- 모든 component root를 local candidate에 reconstruct한 뒤에만 기존 incumbent와 전체 배열을
  비교한다 (`MemoOptimizer.java:1078-1092`).

## 검증 증거

- Candidate02 frozen engine에서 신규 fixture의 semantic adapter 5개가 통과했다
  (`candidate03-before-junit.log`). 이는 새 counter API 이전에도 상태/cost/selection 기대값이 기존
  solver와 일치했음을 잠근다.
- `candidate03-targeted-junit-08.log`: assertions enabled, **42 tests PASS**, 1.794 s. 신규 forest-tail
  6개와 기존 optimizer/domain/boundary/exhaustive parity 회귀를 함께 실행했다.
- 동일 8-cycle+leaf fixture에서 Candidate02 frozen solver는 OPTIMAL, nodes 75,
  selection `[1,1,1,1,1,1,1,1,0]`; Candidate03은 같은 OPTIMAL/selection으로 nodes 33을 기록했다
  (`candidate03-node-structural-baseline.log`, `candidate03-node-structural-current.log`). 이는 synthetic
  구조 회귀에서 42 node, 약 56% 감소이며 workload 성능 주장으로 확대하지 않는다.
- `candidate03-main-javac-08.log`와 `candidate03-test-javac-08.log`는 컴파일 성공이며 incubating vector
  module 경고 외 오류가 없다. 최종 production/test/trace diff의 `git diff --check`도 통과했다.
- `[MemoForestTail]` trace는 attempts/applicable/completions/cells만 출력한다. 이 값은 적용 경로와
  작업량 증거이며 정확성 또는 성능 성공 판정 자체로 사용하지 않는다
  (`FederatedPlanMemo.java:220-229`).

## 남은 한계

- 신규 fixture는 고차 projection, parallel edge, base/negative/dyadic cost, interleaved leaf tie,
  UNKNOWN, proven NO 및 finite budget을 직접 다룬다. 임의 생성 forest/cyclic graph differential은
  추가하지 않았으며, 기존 `MemoExhaustiveParityTest`의 다섯 fixture가 더 넓은 native-universe/tie
  parity를 제공한다.
- 전체 assertions-enabled suite와 공식 LogReg Docker/JFR은 이 문서 작성 시 진행 전이다. 공식
  측정에서 planning `<10 s`를 달성하는지는 이 소스 정확성 승인과 별도다.
