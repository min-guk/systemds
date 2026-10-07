# Derived supply sharing의 main 게시와 L2SVM 후속 작업

## 게시 범위와 기준

사용자 요청 순서는 검증한 derived sharing 변경을 `origin/main`에 게시한 뒤 L2SVM Exact solver 문제를 수정하는 것이다. 원래 작업을 `3fe367b213`으로 커밋하고 새 통합 worktree `/home/mchoi/w1357-derived-supply-main-20261007`를 만들었다. 기존 workspace와 실행 중인 실험은 변경하지 않았다.

첫 통합 기준 main은 `73d1eb024f6f70a7d869f363d895d72313bcbff6`이고, 이후 도착한 StepLM closure main `fbfd4d790feccc84997c3ac749eb8742b9e03a59`도 포함한다. 기존 상세 결과는 [잔여 작업 보고서](DERIVED_SUPPLY_REMAINING_WORK_2026-10-07_KO.md)에 보존한다. 그 보고서의 원시 수치와 hash는 이전 기준으로 실행한 기록이며 최신 main의 측정값으로 바꾸지 않는다.

세션 문서 두 파일의 충돌은 양쪽 내용을 모두 유지해 해결했다. Production Java는 자동 병합됐다. `PlacementRelationClosure`의 grounding 수정은 main에도 이미 있으므로 중복 적용하지 않았고, main의 partition worklist·alias projection·StepLM closure를 보존했다.

## 병합에서 발견한 지원 관계 차이

main의 `PolicyGreedyPlacementSelector`는 required output support를 여전히 exact rule reference로 찾는다. 새 공통 모델은 같은 compiled owner와 정확히 같은 DURABLE_MAP 출력에 대해 producer의 입력 공급 경로 차이를 허용한다. Heuristic/FedAll의 required support 인덱스도 이 계약과 일치시켜야 한다.

수정 범위는 `indexInputs`/`InputSupports`의 출력 지원 비교다. 선택된 receipt, DIRECT/RELOCATION binding, logical transient/function 경계, privacy와 pool/layout 검사는 기존 정확한 비교를 유지한다. Cost-based Local은 이 selector를 호출하지 않는다. 따라서 Local/Global flat 실행만으로 이 문제를 검증했다고 주장하지 않는다. `PolicyGreedyGroundingTest`는 모든 policy에서 같은 owner/map의 다른 rule을 실제 선택하고 최종 receipt 검증을 통과한다. 다른 owner/layout 및 VALUE_MAP rule 차이는 실제 selector 호출에서 거부한다. 기존 reciprocal row-pair 회귀도 유지하며 별도 읽기 전용 검토에서 blocker는 발견되지 않았다.

## 검증 기록

- 첫 main `73d1eb…` 통합: Java 17개 클래스 91/91, Python 49/49, flat DML Local/Global 2/2 PASS.
- 위 결과는 StepLM과 selector 호환 수정 전의 기록이다.
- 최종 통합본: Maven package 및 Java 23개 클래스 **134/134**, Python **52/52** PASS. 이 게시 회귀에는 후속 수리 대상인 대형 L2SVM certificate campaign을 포함하지 않았다.
- 최종 자동 DML: Local/Global 각각 invariant, updated, PHI, flat의 **8/8 PASS**. Invariant GET/PUT은1/1, updated·PHI는3/3이다. Flat의 두 consumer는 iteration마다 같은 공급을 사용하고, 값이 바뀌는3 iteration에서 공급 GET0/PUT3이다. 두 planner 모두 CP/native→REFED를 자동 선택하고 canonical 비용은60.572832073569295ms로 같다. 이전 기준의 Local1ms 차이를 현재 main에 남은 문제로 분류하지 않는다.
- 자동 실행의 CP 수치, canonical objective bits·state·lifetime, 실제 selected/runtime identity와 fallback/repair0 검사를 통과했다. Frozen production inventory와 현재 source hash가 일치한다.
- 최종 Heuristic 실제 worker 검증 **6/6 PASS**. 함수·loop, mixed branch, correlated 입력, 독립 protected 조합의 runtime 전 거부, 허용된 이동을 확인했다. 성공 실행의 수치·runtime audit와 fallback/repair0을 유지했다.
- 원시 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/derived-supply-main-20261007/`.

최종 자동 run은 `publication-automatic-final-r1`, `publication-flat-final-r1`이다. Heuristic은 `publication-heuristic-final`, Java XML·빌드·Python 로그는 `publication-regression-final`에 보존한다. [기계 판독 검증 요약](experiments/derived-supply-publication-20261007/validation.json)에 fixture/source hash와 검사별 결과를 기록한다. 이 통합본을 일반 fast-forward push로 게시하며 기존 main 이력을 덮어쓰지 않는다.

## L2SVM 범위

이전 certificate campaign은 L2SVM에서 `EXACT_VE_FACTOR_LIMIT_EXCEEDED|cells=19322130|limit=10000000|input`으로 중단됐다. 동일 테스트를 수정 전 코드에 적용해도 optimizer cell overflow가 발생했다. 게시 완료 후 최신 통합 코드에서 다시 재현하고 factor의 표현과 제거 순서를 조사한다. 한도만 올리거나 합법 후보를 제거하는 방식은 해결책으로 삼지 않는다.
