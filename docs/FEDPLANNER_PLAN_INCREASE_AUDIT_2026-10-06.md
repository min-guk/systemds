# GLM / L2SVM 계획 비용 증가 확인

## 후속 해결 완료

아래는 수정 전 감사 기록이다. 후속 구현으로 GLM 탐색의 추가 메모리 중복 집계/연산 결합 영역/한도에 맞는 elimination 순서 선택을 수정하고, L2SVM의 branch guard와 GET cache 생성 수명을 분리했다. 최종 GLM W1 **20,956.64ms**, L2SVM W3 **4,259.97ms**, 단위 681건/compile 28건/실제 worker 6건 모두 PASS. 자세한 원인·변경·제한·receipt는 [후속 해결 보고서](FEDPLANNER_SEARCH_BOUNDARY_FIX_2026-10-06.md)를 기준으로 한다.

## 수정 전 감사 결론

GLM W1은 **같은 현재 모델에서 더 싼 합법적 계획을 놓친 탐색 품질 문제**가 확정됐다.
L2SVM W3은 **분기 경계 정규화 이후 동일한 기존 배치의 물리 구현 비용이 바뀐 경우**다.
L2SVM 증가분을 불가피한 정상 비용이라고 확정하거나 탐색 문제가 없다고 주장하지 않는다.

| 사례 | 현재 선택 | 현재 모델로 검증한 대안 | 판단 |
|---|---:|---:|---|
| GLM W1 | 46,212.521644ms | 21,977.116965ms | 모든 제약을 만족하는 더 싼 계획 존재 |
| L2SVM W3 | 9,014.979380ms | 기존 191개 배치 유지 시 조건부 최저 27,412.450394ms | 이전 배치를 복원하는 것만으로 개선되지 않음 |

## GLM W1

현재 emission 이전 physical model/cost surface에서 line983의 7개 occurrence를
과거 coarse state로 제한하고 나머지 모든 변수를 현재 선택에 고정했다.
4개 선택만 바뀐 complete witness가 **3,458개 hard factor, 위반 0**을 통과했다.
비용 감소는 24,235.404679ms(52.44%)이며, contribution 차이 합계와 일치한다.
첫 번째로 찾은 저비용 physical realization이므로 전역 최적이나 조건부 최적은 주장하지 않는다.
과거 다른 모델의 21,355.270631ms를 현재 비용으로 혼용하지 않는다.

실제 종료 원인은 `RESOURCE_INITIAL`, merges=0, improvements=0이다.
축소 후 factor 전체를 dense leaf로 만들 때 필요한 초기 슬롯 합이 8,000,000 한도를 넘는다.
따라서 시간 제한 이전에 초기 계획을 그대로 반환했다. Private-variable projection은
그 후에 실행되므로 초기 admission 실패를 구하지 못한다.

남은 수정은 초기 factor를 sparse/lazy 상태로 유지하거나 필요한 projection을 먼저 수행하여
예산 내에서 개선 탐색을 가능하게 하는 것이다. 한도 검사만 제거하면 실제 배열 할당 문제가 남는다.

## L2SVM W3

같은 현재 비용 모델에서 normalizer 호출만 제거하면 239-node 그래프와 7,198.293376ms가
복원된다. Normalizer 적용 후에는 243 nodes, 9,014.979380ms이다.
이는 normalizer의 영향이 있음을 보여 주지만 증가분의 정당성이나 전역 최적성을 증명하지 않는다.

네 Y occurrence만 FED로 되돌린 56개 조합은 carrier/binding hard factor 때문에 모두 실패했다.
그러나 이 결과를 기존 계획 전체가 불가능하다고 해석하면 안 된다.
기존 대응 coarse state **191개를 고정하고 새 carrier 4개 및 보조 선택을 자유롭게 풀면**
완전한 extension이 존재하며, 이 조건부 문제는 `EXACT`, lower=upper=27,412.450394ms,
hardCost=0으로 종료된다. 새 carrier 네 개는 모두 FED/FOUT/ROW다.

현재 선택과 이 조건부 해의 큰 차이는 line109의 Y/multiply 그룹(+18,210.952274ms),
line124(+1,821.095227ms), line120(-1,816.470162ms)에서 나온다.
경계가 추가된 뒤 동일 coarse state가 갖는 물리 구현/이동 비용을 더 확인해야 하며,
이 수치를 실제로 필요한 추가 네트워크 통신으로 단정하지 않는다.

현재 unrestricted 탐색도 `RESOURCE`에서 중단했고 upper=9,014.979380ms,
lower=3,358.047271ms이므로, 현재 전역 최적이 과거 7,198.293376ms보다 비싸다고 증명하지 못했다.

## 검증과 범위

모든 실험은 `scripts/fedplanner/run_LAN_docker.sh --function-boundary-compare`와 동일 pinned
Docker image를 사용했다. GLM 최종 diagnostic compile PASS. L2SVM의 고정 조건 exact solve와
일반 compile 결과는 외부 로그 및 보고서에 보존했다. Production source manifest와 jar SHA는
직전 606-test/28-case 검증본과 동일하다. 이번에는 본체 코드를 수정하거나 push하지 않았다.
수치는 모델 objective이며 실제 WAN 시간 또는 실행시간 개선율이 아니다.

- [요약 receipt](experiments/remaining-planner-work-20261006/plan-increase-audit/summary.json)
- [GLM witness](experiments/remaining-planner-work-20261006/plan-increase-audit/glm-witness.json)
- [L2SVM 상세 보고서](experiments/remaining-planner-work-20261006/plan-increase-audit/l2svm-report.md)
- 외부 전체 command/source/log: `/home/mchoi/fedplanner-plan-increase-20261006/`
