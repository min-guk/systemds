# L2SVM native planning pilot 결과 — 기존 6개 방법

작성일: 2026-09-08. 이 문서는 이미 완료한 c20 구현의 L2SVM 후속 pilot 60행을 정리한다. 새 Target Anytime나 네 환경 7-way 본 측정 결과는 포함하지 않는다.

## 관측 결과

이 LAN 조건에서는 Global과 기존 Anytime가 모든 반복에서 1%를 인증했다. Algorithm 1/2/3은 20초 budget에서도 인증하지 못했다. 반환 계획의 실제 modeled regret와 인증 gap은 크게 달랐다.

| Budget | 방법 | Planning receipt | 1% 인증 | Planner 중앙값 s | JVM 시작→인증 중앙값 s | 최종 인증 gap 중앙값 | 실제 modeled regret 중앙값 |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 5s | Global | 5/5 | 5/5 | 0.832528 | 3.455010 | 0% (exact) | 0.000000% |
| 5s | Regional | 5/5 | 해당 없음 | 0.727107 | — | — | 0.052328% |
| 5s | LegacyAnytime | 5/5 | 5/5 | 3.178032 | 6.169292 | 0.000000% | 0.000000% |
| 5s | Algorithm1Threshold | 5/5 | 0/5 | 5.831944 | — | 29.536837% | 0.000000% |
| 5s | Algorithm2TargetGap | 5/5 | 0/5 | 5.797604 | — | 29.514328% | 0.052328% |
| 5s | Algorithm3Reuse | 5/5 | 0/5 | 5.578626 | — | 25.908798% | 0.052328% |
| 20s | Global | 5/5 | 5/5 | 0.833075 | 3.601915 | 0% (exact) | 0.000000% |
| 20s | Regional | 5/5 | 해당 없음 | 0.607918 | — | — | 0.052328% |
| 20s | LegacyAnytime | 5/5 | 5/5 | 2.889102 | 5.304933 | 0.000000% | 0.000000% |
| 20s | Algorithm1Threshold | 5/5 | 0/5 | 20.789036 | — | 29.536837% | 0.000000% |
| 20s | Algorithm2TargetGap | 5/5 | 0/5 | 20.627300 | — | 29.514328% | 0.052328% |
| 20s | Algorithm3Reuse | 5/5 | 0/5 | 20.712795 | — | 21.446443% | 0.052328% |

`Planning receipt` 통과는 유효한 planning-only 결과라는 의미이며 threshold 달성을 뜻하지 않는다. Regional은 자체 certificate를 반환하지 않으므로 인증 성공률을 0%로 표기하지 않는다. 인증 시간은 도달한 실행에서만 계산하고 각 방법의 전체 5회 분모를 함께 표시했다.

## 왜 이 결과가 중요한가

독립 Global 최적 비용은 1911.0122928756668 modeled ms, 초기 Regional 비용은 1912.0122928756668, 초기 MBE 하한은 1475.2655244755279였다. 초기 Regional의 실제 modeled regret는 약 0.052328%로 이미 1%보다 작았지만, 그 계획을 인증하는 gap은 약 29.604621%였다.

20초에서 Algorithm 1은 다섯 번 모두 Global과 같은 계획 비용에 도달했어도 약 29.536837% certificate gap이 남았다. 계획을 더 좋게 만드는 것과 이미 좋은 계획임을 증명하는 것은 다른 작업이라는 예다. Algorithm 3은 하한을 더 올려 gap을 약 21.446443%로 줄였으나 목표에는 미달했다.

기존 Anytime는 누적 region이 전체 문제를 덮어 exact로 닫는 경로를 사용했고 1%를 모두 인증했다. Global 자체의 planner 중앙값도 약 0.83초로 작았다. 이 결과는 L2SVM에서 Global이 저렴할 때 exact admission을 지나치게 제한하면 bounded search에 더 많은 비용을 쓸 수 있음을 보여준다.

## 비교의 조건과 적용 한계

- so007 native JVM planning-only, worker 1, P2P2D X 50000×2100/Y 50000×1, PRIVATE_AGGREGATE, LAN modeled bandwidth/latency.
- 상대 threshold 1%, seed 이후 soft budget 5초/20초, 각 조건·방법 5회, 6개 방법으로 총 60행. 별도 smoke 6행은 표에서 제외했다.
- Width 2→4, factor/total cells 1m/5m, maxRegion 512, regionGrowth 8, rounds/maxSteps 256.
- 새 세 controller의 regionWorkLimit은 30000, B/C의 nonterminal whole admission은 10000이었다. Legacy와 독립 Global은 이 새 admission gate를 사용하지 않는다. 따라서 순수한 scheduling 정책만 동일 자원 아래 분리한 실험으로 해석하지 않는다.
- 이 조건의 gate와 JAR는 후속 7-way practical protocol의 100000/100000 및 새 Target Anytime JAR와 다르다. 두 캠페인의 행이나 wall-clock 시간을 섞지 않는다.
- 실제 workload·worker JVM·Docker 실행이 아니라 compile-only 검증이다. 모델상 최적 비용 대비 오차이며 실제 L2SVM 실행시간의 성능을 측정한 결과가 아니다.

## 검증과 원본

60행 모두 planning receipt를 통과했고, comparison의 12개 정확성 검사가 모두 참이다. 같은 encoded model·analysis·initial Regional U·initial MBE·new-order fingerprint를 확인했고, 모든 checkpoint가 독립 Global을 포함했다. 각 새 알고리즘의 고유 작업 및 B/C probing 재사용도 trace에서 확인했다.

- Source implementation: `c20c4f0cab281d2d2d36dd6b40167837b22be8b4`.
- JAR SHA-256: `c9b14ad770225f07ca59ddce93de2ac67be05516412ae71c0bcf6a4d988dc108`.
- [원본 summary](/home/mchoi/so007-regional-threeway-evidence-20260908/native/runs/native-l2svm-v1-measured-l2svm/summary.json).
- [비교 검증](/home/mchoi/so007-regional-threeway-evidence-20260908/native/runs/native-l2svm-v1-measured-l2svm/comparison.json).
- [고정 protocol](/home/mchoi/so007-regional-threeway-evidence-20260908/native/runs/native-l2svm-v1-measured-l2svm/protocol.json).
- [KMeans·PCA 기존 결과 보고서](/home/mchoi/so007-regional-threeway-20260908/docs/REGIONAL_THREEWAY_RESULTS_KO.md).

실패한 threshold 행도 표의 분모에 모두 포함했다. 이 결과만으로 다른 네트워크·워크로드에서의 우위나 새 Target Anytime 성능을 일반화하지 않는다.
