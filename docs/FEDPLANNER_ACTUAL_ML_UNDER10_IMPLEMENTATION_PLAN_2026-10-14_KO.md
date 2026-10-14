# 실제 LogReg·GLM runtime 이전 compile 10초 목표 — 구현 계획

## 승인된 목표와 중단 조건

- 실제 sealed ML LogReg/GLM(50000×128), planning worker1/3/5/7, 총8조건.
- full initial planning 및 이를 포함한 전체 compile 각각10초 이내, RuntimeProgram 구성 완료, executionSeconds=0, OPTIMAL/원비용 audit 통과.
- 같은 pinned Docker4CPU/8GiB/heap3GiB, C26 default costs, 원DML/metadata/ranges/privacy/seed 유지. 작은ML E2E를 성능 근거로 사용하지 않고 실행 가능한 tiny LogReg/GLM 평가 경로를 제거한다.
- 후보별 조건당1회, 후보·진단을 별도root로 기록. 완료조건은 strict evaluator PASS와 Memo regression 통과, 삭제/교체 검증이다. timeout·UNRESOLVED·부분compile를 성공 처리하지 않는다.
- evaluator: `bash scripts/fedplanner/run_LAN_docker.sh --actual-ml-planning-performance --engine <frozen-engine> --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z --output-root <NEW>`.

## 근거

기존 실제W3 진단에서 forest-tail 재계산 main samples97.54%/92.98%, 두300초timeout. C26 normalized graph를 source와분리한진단engine으로캡처했고, search는수행하지않았다. LogReg 주요component76owners/129edges, GLM162/361. 따라서단순독립component분리만으로해결된다고가정하지않는다.

## 수정 순서

1. 엄격한 실제ML evaluator와threshold/error/receipt/input 계약단위테스트를 먼저 고정한다. 과거 tiny실험receipt를 cost권위로읽는의존도제거한다.
2. 기존C26 solver와독립brute-force정답을비교하는새unit-graph회귀를구현전실행한다. 작은unit graph는정확성증명도구이며ML성능평가가아니다. BigDecimal exact값은 compareTo==0으로검사해scale표현차이와수치차이를구별한다.
3. normalized immutablefactor scopes위에서 **bounded exact bucket elimination**을구현한다. owner와separator의모든관련check/cost를빠뜨리지않고각factor를정확히한번소비한다. immutablemessage는separator tuple에대한정확비용과argmin/backpointer만저장한다. 임의owner순서에서도globallexicographic tie를보존한다.
4. 전체incident check truthClasses와costClasses가같다는증명이있을때만solver내부ordinal을exactnativecost가최소인대표(동률최소ordinal)로정리하는방안을실측한다. 누락된annotation은actualordinal로구별한다. 이는shape-onlydedup이나RuleMemo후보삭제가아니다. UNKNOWN까지동일한factor관측을증명해야한다.
5. 실행전 inducedseparator/table작업량을계산해메모리를제한한다. 넓거나UNKNOWN이면기존정확탐색을유지한다. 최적해를근사하거나임의검사/후보를삭제하지않는다. finite-node-budget는기존경로를유지하여resource계약을보존한다.
6. actualW3에서후보성능과정확결과를확인하고,동결후전체8조건과기존Memo회귀로최종검증한다. cutoff/key/table최적화는측정된문제에만추가한다.

## 소유권과 정확성

- root: MemoOptimizer/필요한trace, 빌드/실제Docker측정/통합문서.
- executor: scripts/fedplanner 성능evaluator와tinyML삭제 및Python테스트.
- test-engineer: 독립exhaustive회귀,기존legacy forest/tail path회귀유지.
- architect: 읽기전용graph·correctness검토.
- 기존privacy/workerbinding/UNKNOWN/DEFER/feasibility/exactbinary64비용/원costaudit/runtimefallback금지 유지.
- 과거실험증거는삭제하지않고폐기된성능범위라고명시한다. 일반정확성단위테스트/다른boundaryE2E를일괄삭제하지않는다.
- 결과가10초를넘으면완료라고보고하지않는다. 큰exact문제에대한일반적인10초보장이아니라봉인된8실제조건에대한검증이다.

## 구현 중 확인 — 후보01

- 전역 owner quotient만으로는 GLM table이 충분히 줄지 않았다(약327M local assignments). 대신 **소비된 factor/message에 한정한 separator 축별 class 교집합**으로 압축했다. 미래 owner의 native cost를 separator class에 섞지 않고, 제거할 owner는 모든 live ordinal을 평가한다.
- 실제W3 graph에서 예정 local assignments/rows는 LogReg14744/2378, GLM1360977/229309이며 구현실측counter와일치했다. table/evaluation예산을넘으면기존exactsolver로복귀한다.
- 후보01 actualW3각1회: optimization0.798584s/2.070155s, 둘OPTIMAL 및원비용auditPASS. 그러나 이후 `Duplicate selected supply edge`로compile실패했으므로10초목표PASS아니다.
- 원인진단: `reconstructSupplies`가동일compiledHop edge의각occurrence별Supply를생성하는데, runtime/loweringregistry는물리edge별하나의완전한recipe집합을요구한다. DIRECT-only occurrence의조건도다른occurrence의movement와함께보존해야한다. 정확한target/source/workerrecipe충돌은계속거부하고,모든context의합법성/retained origin분석을유지하는수정만허용한다.

## 최종 상태

구현·검증 완료. 실제 LogReg/GLM × W1/W3/W5/W7 전체 compile이 각각 3.429–3.982초 / 6.239–8.499초로
8조건 모두 10초 gate를 통과했다. Java 700/Python 70 tests PASS.
상세 알고리즘·정확성·실패 기록·재현 명령은
[FEDPLANNER_ACTUAL_ML_UNDER10_RESULT_2026-10-14_KO.md](FEDPLANNER_ACTUAL_ML_UNDER10_RESULT_2026-10-14_KO.md)에 기록했다.
원 계획 4번의 전역 owner quotient는 채택하지 않았으며, 소비된 factor/message에 한정한 separator class 교집합으로 대체했다.
