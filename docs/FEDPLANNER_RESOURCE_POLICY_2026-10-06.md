# Local/Global DP resource policy — 2026-10-06

## 변경 계약

Global은 전체 hard/cost factor와 모든 변수의 해를 exact로 계산한다. 연결되지 않은 component도 포함하며 한 component가 infeasible이면 전체 성공을 반환하지 않는다. 자원 부족을 부분 해 성공으로 바꾸는 경로는 없다.

Local production에서 1,000,000 merge assignments, 8,000,000 retained slots, 10초, 16개 scoring 후보 한도를 삭제했다. 기존 네 property를 설정하면 `*_REMOVED`로 명시적으로 거부한다. 유한 한도는 package-private test Options에만 남아 있다. Local의 기본 5% relative-gap 종료 기준은 품질 기준으로 유지하며 Global에 적용하지 않는다.

메모리 보호는 JVM 최대 heap보다 큰 불가능한 요청, 실제 primitive array 할당 실패, Java 배열 인덱스/산술 표현 한계에만 적용한다. 현재 free heap은 진단 정보이며, 회수 가능한 garbage가 있다는 이유로 할당을 미리 거절하지 않는다. 모든 JVM/native allocation을 포괄적으로 보장하는 장치는 아니다.

## 계산 순서

Global과 Local 내부 exact 부분 문제의 portfolio는 peak factor cells → 누적 materialized cells → 최대 단계 assignments → 총 assignments → induced width 순으로 비교한다. Local incremental 병합은 가장 작은 separator를 우선하며 작업량과 conflict 점수를 사용한다. 순서는 휴리스틱이며 최적의 계산 복잡도를 보장하지 않는다. 변수/상태/요인 제거를 생략하는 정책은 아니다.

`fastOrderAssignments`는 단일 순서 shortcut을 수용할지 전체 순서 portfolio를 비교할지 결정하는 기준이다. 이를 초과해도 풀이를 중단하지 않으므로 resource budget과 구별해 유지한다.

## 검증

- production Local이 기존 1M assignment 한도를 초과하여 EXACT에 도달하는 회귀.
- Global의 연결/비연결 변수를 포함한 48개 전체 assignment oracle과 다섯 elimination order 대조.
- infeasible disconnected component에서 부분 해 성공을 거부하는 회귀.
- 64MiB JVM에서 불가능한 lazy table의 평가 전 실패, 개별 요청은 가능하나 누적 live allocation으로 발생한 실제 OOME 처리.
- JVM 현재 여유 메모리보다 크지만 최대 heap 이내인 요청을 허용하는 결정적 검사.

최종 통합 및 Docker 검증은 아래와 같이 완료했다. 근거 디렉터리: `/home/mchoi/fedplanner-resource-policy-20261006`.

## 실행시간 해석

고정 한도 제거 후 기존에 중단했던 큰 테이블을 실제로 계산하므로 Local도 오래 걸릴 수 있다. 특히 STEP-LM 회귀에서 이전보다 큰 DP 병합이 실행 중임을 thread dump로 확인했다. 이 시간을 숨기기 위해 production에 새로운 cutoff를 넣지 않는다. Docker의 CPU/heap/container 설정은 실험의 시스템 자원 조건이며 planner의 고정 탐색 예산이 아니다.

## 불가능한 조합의 합산 단축

DP merge에서 exact 값과 certified lower bound가 모두 `+Infinity`이면 뒤의 message를 더해도 두 값이 바뀌지 않는다. 이때만 해당 조합의 남은 합산을 생략한다. 변수 assignment를 삭제하거나 feasible 조합의 계산을 중단하지 않는다. 둘 중 하나라도 finite이면 계속 계산한다.

동일 3GiB heap의 STEP-LM 검증에서 이전 kernel은 Local merge를 10분 넘게 수행 중이었고, 보완한 kernel은 Local/Global 두 selector를 모두 포함한 전체 테스트를 wall 72.16초에 통과했다. 이전 실행을 끝까지 측정한 speedup 배율은 아니다. 최종 소스로 통합 회귀까지 통과했다.

## 실제 heap 소진의 진단 보완

L2SVM W1의 10GiB JVM에서 배열 할당 실패를 보고하는 `Long.toString`도 메모리를 얻지 못하는 사례를 재현했다. 배열 할당 wrapper는 사전 검사와 상세 진단 생성 중의 OOME까지 처리하고, 상세 진단을 만들 수 없으면 class 초기화 때 준비한 typed resource exception을 재사용한다. 새로운 메모리 비율/예약량/시간 제한을 두지 않는다.

실패했던 동일 Docker 조건은 보완 후 PASS했다. 누적 3,474,725,814 assignments 후 실제 자원 부족을 처리하고 Local의 feasible incumbent를 반환했다. Global은 이런 부분 해를 성공으로 내보내지 않는다. 일반 `checkAdditionalCells`/`checkAdditionalBytes` 직접 호출을 포함해 모든 JVM/native allocation의 OOME를 정규화한다는 주장은 하지 않는다.

## 이번 요청에서 변경한 파일

이전 완료 빌드의 source hash와 비교하면 production 변경은 `ExactCategoricalSolver`, `ExactPhysicalReducedSolver`, `IncrementalRegionalOptimizer`, `RegionalSearchProblem`, `SharedRegionalPreparation`, 새 `PlannerResourceGuard`의 6개 파일이다. 테스트는 `IncrementalBoundaryMessageTest`, `IncrementalRegionalConfigurationTest`, `IncrementalRegionalOptimizerTest`, 새 `GlobalExactCompletionTest`, 새 `PlannerResourceGuardTest`의 5개 파일이다. 이전 미커밋 비용/분기/런타임 변경은 보존했다.

## 최종 검증 완료

- Maven compile/test/jar: **89개 클래스, 699 tests PASS**. 실패·오류·제외 0, XML freshness 확인.
- Docker 14 scripts × W1/W3: **28/28 compile/planning/lowering PASS**. 모든 결과 JSON `status=passed`와 command/result freshness를 확인했다. 이 campaign의 selector는 DP-LocalConflict이며 28건 모두 Global 실험이라고 해석하지 않는다.
- 실제 2-worker 실행: **6/6 PASS**, numeric checksum 일치, runtime fallback=repair=0.
- Global 검증: 전체 assignment oracle/다섯 순서/비연결 component/부분 성공 금지/64MiB resource failure 회귀, 실제 STEP-LM의 `compile_exact` whole-program selection.
- source hash, 3,713개 class hash, 실행 JAR hash 일치. `git diff --check`, shell/Python syntax 검사 통과.
- 최종 JAR SHA-256: `e241c19e63ae11dafb30a5cb8cd9463f920766273e582a0c8a982f0353ebc1d1`.

Local 종료 이유는 TARGET_REACHED 22건, 실제 자원/표현 한계 RESOURCE 4건, EXACT 2건이다. TIME 종료는 없고 최종 누적 assignments 최대값은 5,560,794,829이다. 고정 1M를 넘는 단일 production merge 허용은 별도 회귀 테스트로 확인한다.

28개 모두 graph node/coarse candidate 수가 유지됐다. Lowering 후 physical domain 수까지 불변이라고 주장하지 않는다. 이전 완료 빌드 대비 예상 비용은 0.01ms 허용 오차로 4건 감소·22건 유지·2건 증가했다. 아래 수치는 모델 예상 비용이며 측정 학습시간이 아니다.

| Case | 이전 예상 비용(ms) | 변경 후(ms) | 차이(ms) | 종료 |
|---|---:|---:|---:|---|
| COVTYPE_w1 | 2,579,973.31 | 2,580,074.30 | +101.00 | TARGET_REACHED |
| glm_w1 | 20,956.64 | 21,967.23 | +1,010.59 | TARGET_REACHED |
| gmm_w1 | 22,943.17 | 20,822.18 | -2,120.98 | TARGET_REACHED |
| kmeans_w1 | 52,940.26 | 15,017.07 | -37,923.20 | TARGET_REACHED |
| l2svm_w3 | 4,259.97 | 4,169.15 | -90.82 | RESOURCE |
| logreg_w3 | 101,607.41 | 21,917.01 | -79,690.40 | RESOURCE |

COVTYPE W1과 GLM W1은 모두 TARGET_REACHED였다. GLM W1은 lower≈20,956.04ms, upper≈21,967.23ms로 gap≈4.83%에 도달했다. Local은 기존 5% 품질 기준을 유지하므로 budget 제거 후에도 이전 실행보다 항상 더 싼 incumbent를 반환한다는 보장은 없다. Global에는 이 품질 종료 조건이 없다.

최종 근거: [validation](experiments/resource-policy-20261006/validation.json), [case별 비교](experiments/resource-policy-20261006/compile-comparison.json), [단위 테스트](experiments/resource-policy-20261006/regression-results.json), [worker 실행](experiments/resource-policy-20261006/runtime-receipt.json). 외부 원본은 `/home/mchoi/fedplanner-resource-policy-20261006/all14-verified`, `runtime-verified`에 있다. 중간 실패·중단 기록은 최종 PASS 근거에 포함하지 않는다.

이번 요청에서는 commit/push하지 않았다.
