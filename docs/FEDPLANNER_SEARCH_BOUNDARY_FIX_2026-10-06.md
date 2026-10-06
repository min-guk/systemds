# GLM 탐색 및 분기 경계 GET 비용 수정

> 후속 변경: 이 보고서의 고정 resource budget 정책은 [Local/Global DP resource policy](FEDPLANNER_RESOURCE_POLICY_2026-10-06.md)로 대체됐다. 아래 수치는 당시 빌드의 검증 이력이다.

## 문제와 변경

현재 모델에서 GLM W1의 선택은 약 46,212.52ms였지만, 모든 hard factor를 만족하는 약 21,977.12ms witness가 있었다. 처음에는 이미 root가 소유한 dense factor 배열을 Regional 추가 저장 예산에 다시 포함하여 `RESOURCE_INITIAL`로 탐색을 시작하지 못했다. 중복 집계를 제거한 뒤에도 큰 persistent message 때문에 탐색이 중단되므로, 검증된 cover의 scalar lower bound를 보존하고 저장 공간을 해제한 뒤 작은 conditional exact 문제로 incumbent를 개선한다.

Conditional 영역은 현재 비용과 그 영역 최소 비용의 차이를 이용해 우선순위를 정하고, owned root factor로부터 앞서 내부화한 원본 decision도 복원한다. 보조변수로 연결된 관계와 원본 연산의 주변 두 단계를 묶으며, 큰 영역이 한도로 거절돼도 작은 원본 영역을 시도할 수 있게 유지한다. 우선순위는 휴리스틱이며 lower bound를 바꾸지 않는다. 모든 개선 후보는 전체 canonical objective와 feasibility로 확인한다. 조건화 table과 intermediate의 추가 저장량을 사전 검사하고, root table을 다시 compact하여 복사하지 않는다. 선택적 solve/lift의 resource 실패는 기존 incumbent를 유지하며, 의미/검증 오류는 숨기지 않는다.

추가 원인은 elimination 순서 선택이었다. 네 연산의 conditional 문제에서 기본 순서는 최대 단계 작업량 3,799,552로 1,000,000 한도를 넘었다. 같은 portfolio의 다른 정확한 순서는 593,680으로 한도 안에 있었다. 기존 configured 순서를 우선 사용하고, 그것이 한도를 넘을 때만 모든 후보 순서 중 메모리와 단계 작업량 한도를 동시에 만족하는 것을 다시 선택한다. 상태/배치 후보를 버리는 작업이 아니다.

L2SVM에서는 branch carrier 뒤의 여러 reaching definitions 때문에 GET 생성 수명을 inner-loop TRead 실행 횟수로 대체한 것이 문제였다. MatrixObject 생성 원본의 수명과 분기 도달 guard를 분리하고 같은 원본의 경로는 합집합으로 과금한다. loop에서 매번 갱신되는 값, 실제 REFED로 새로 생성되는 값은 이전 원본의 cache와 합치지 않는다.

새 계획은 동일 anchor의 legacy 1D/full 2D 표현 차이도 드러냈다. live Lop shape가 없을 때 complete counterpart가 비분할 축의 일관된 전체 범위를 증명하는 경우에만 정규화한다. 다른 worker·partition·범위는 계속 거부한다.

## 변경 파일

- Solver: `ExactCategoricalSolver`, `RegionalSearchProblem`, `SharedRegionalPreparation`, `IncrementalRegionalOptimizer`, `LocalPhysicalOptimizer` 및 관련 회귀 테스트.
- 비용: `ExactPhysicalCostModel`, 새 `TransparentCarrierCostAuthorityTest`의 8개 branch/lifetime 회귀.
- Lowering: `Dag`, `FederatedPlannerFallbackIntegrationTest`의 anchor 동치/비동치 회귀.

## 검증 상태

최종 소스 전체 compile/jar 성공. **86개 클래스 681 tests PASS**, 실패/오류/제외 0. **14 workload × W1/W3 = 28/28 compile/lowering PASS**, 실제 2-worker 실행 **6/6 PASS**, fallback=repair=0이다. 단위 XML freshness, 각 Docker command와 결과 freshness, source SHA, 실행 jar SHA를 교차 확인했다. GLM/L2의 isolated overlay와 전체 Maven snapshot의 production source hash도 모두 일치한다.

| 사례 | 수정 전 예측 비용(ms) | 수정 후 예측 비용(ms) |
|---|---:|---:|
| GLM W1 | 46,212.52 | **20,956.64** |
| L2SVM W3 | 9,014.98 | **4,259.97** |

GLM은 이전에 확보한 feasible witness 21,977.12ms보다도 저렴하다. 핵심 네 연산의 변경은 외부 matmul FED/LOUT의 BROADCAST→FULL, transpose와 elementwise multiply의 FED/FOUT→CP/LOUT, 내부 matmul의 FED/FOUT→FED/LOUT이다. 전체 canonical 평가로 검증된 선택이며, 종료 상태는 RESOURCE라 전역 최적은 주장하지 않는다. GLM compile은 약 270.34초였으며 위 objective와 다른 측정이다.

28개 모두 graph node/coarse alternative 수가 유지됐다. 0.01ms 허용 오차 기준 **11개 비용 감소, 17개 유지, 증가 0개**다. Lowering 이후 재구성하는 physical alternative 수는 일부 달라지므로 전체 physical domain이 동일하다고 주장하지 않는다.

실제 실행 checksum은 aggregate=92, shape=4480, linear=3588, control=304, branch_true=95, branch_false=92다. 전체 28개는 compile-only이며 실제 학습 실행 검증과 구분한다.

- [검증 receipt 및 소스/jar SHA](experiments/search-boundary-fix-20261006/validation.json)
- [28-case 비교](experiments/search-boundary-fix-20261006/compile-comparison.json)
- [GLM 선택과 탐색 checkpoint](experiments/search-boundary-fix-20261006/glm-final-proof.json)
- [계산 순서 거절 원인](experiments/search-boundary-fix-20261006/glm-order-rejection-evidence.json)
- [단위 테스트](experiments/search-boundary-fix-20261006/regression-results.json)
- [실제 worker receipt](experiments/search-boundary-fix-20261006/runtime-receipt.json)

비교의 수정 전은 이번 작업 시작 시의 미커밋 소스 검증본(`fedplanner-remaining-20261006`, 606-test/28-case/6-runtime receipt)이다. HEAD `10e14bc` 자체와 혼동하지 않는다. 기존 미커밋 변경/자료는 보존했고 이 작업에서 commit/push는 하지 않았다.

## 범위와 한계

후보를 임의로 삭제하거나 cap을 올리거나 runtime fallback을 도입하지 않았다. TW/TR의 CP/LOUT 또는 FED/FOUT 규칙은 유지한다. 자원 한도는 추가 numeric-cell storage 및 elimination당 assignment 수이다. 전체 JVM 메모리 제한을 증명하거나 solve 내부를 wall-clock으로 강제 중단하는 기능은 아니다. 시간은 attempt 사이에 검사한다.

예측 objective는 측정 실행시간이 아니다. 실제 worker 검증은 container loopback을 사용하므로 WAN 성능 정확도나 전역 최적성을 주장하지 않는다.

L2SVM의 이전 7,198.29ms와 비용 수정 후 7,289.33ms 사이 약 91.04ms는 삭제할 중복이 아니었다. 기존 runtime explain에도 있었던 post-join `CP prefetch Y` 한 번이 과거 모델에서 누락된 것이다. 이후 탐색으로 더 싼 선택을 찾는 문제와 구별한다.

외부 전체 소스 snapshot, command, raw log: `/home/mchoi/fedplanner-search-boundary-fix-20261006/`.
