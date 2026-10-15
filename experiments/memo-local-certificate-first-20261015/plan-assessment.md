# MEMO_LOCAL 계획 대비 최종 구현 평가

작성 시점: 2026-10-15. 기준 계획은 `docs/COFEE_MEMO_LOCAL_CERTIFICATE_FIRST_PLAN_2026-10-15_KO.md`이고, 구현은 동결된 `engine-v8` 소스다. Java 코드를 변경하거나 Maven/benchmark를 추가 실행하지 않고 소스와 현재 산출물만 읽었다.

## 판정

정확성 구조와 실행 정책의 핵심은 구현됐다. Local은 Global direct exact와 분리되어 첫 LB/UB를 먼저 만들고, `LB>0 && UB<=1.05*LB`만 ε 인증으로 인정한다. 원래 domain cover, UNKNOWN·ε-deferred 영역, atomic split, 자원 고갈 상태도 유지한다.

성능 정책은 계획의 중간 단계다. 현재 Local은 고정된 네 개 seed 개선, 전체 legality propagation, cell 수 중심의 variable-elimination message를 순서대로 실행한다. 이 구성은 “필요한 증명 작업을 선택”한다기보다 값싼 것으로 추정한 메시지를 거의 끝까지 제거한 뒤 인증하는 형태에 가깝다. 현재 부분 데이터에서는 정확한 cell 수를 줄여도 seed·message·propagation 오버헤드 때문에 모든 세 workload에서 MEMO_GLOBAL보다 느리다.

## 계획 준수 상태

| 계획 항목 | 최종 구현 | 평가 |
|---|---|---|
| Local/Global 시작 정책 분리 | `MemoLocalOptimizer`가 독립 route이며 Local의 direct attempt/solve는 0 | 완료 |
| cheap global LB와 첫 feasible UB 후 즉시 검사 | coarse floor → `findFirst` → 전체 witness 검증 → certificate 순서 | 완료 |
| positive-LB 5% 및 exact 상태 구분 | `LB==UB`만 OPTIMAL, positive LB의 `UB<=1.05*LB`만 EPSILON_OPTIMAL | 완료 |
| 미탐색 영역을 포함하는 전역 cover | 최소-LB priority queue, UNKNOWN/deferred floor, 양 child atomic publication | 완료 |
| seed와 proof 상태 분리 | seed는 UB만 갱신하며 message/branch 순서는 incumbent 값에 의존하지 않음 | 완료 |
| 좋은 UB의 선택적 개선 | 작은 cost scope 최대 네 개를 고정 순서로 모두 시도 | 부분 구현: 효과/비용 selector와 무개선 조기 전환 없음 |
| lazy factor/cluster/tree refinement | class-aware exact min-sum message를 owner 하나씩 완성 후 게시; 미리 모든 table을 만들지 않음 | 대체로 구현: 별도 factor-minimum 작업과 명시적 tree/component 경로는 없음 |
| 인증 기여 중심 conflict selector | message는 `cells → separator width → owner id`, search branch는 degree 중심 | 미구현: 예상 LB 상승, relaxed 불일치, 전역 gap 기여를 선택에 사용하지 않음 |
| 최소-LB cover 처리 | search frontier는 lower bound 오름차순 | 완료 |
| 긴 작업의 명시적 quantum/yield | seed cap, message cell cap, split 1회 단위는 존재 | 부분 구현: message solve, whole propagation, factor table은 내부 resume 불가 |
| 유한 진행과 실패 상태 | message는 owner를 한 번만 제거하고 이후 split; node cap·메모리 부족은 성공이 아닌 RESOURCE_EXHAUSTED | 완료 |
| 보수적 memory admission | domain/frontier/message/propagation 추정과 atomic admission, optional cache eviction | 정확성 완료; retained table과 실제 container peak 최적화는 남음 |
| 계측과 독립 감사 | phase 시간, action, LB/UB, message touch, propagation, receipt/certificate audit | 대체로 완료 |
| 계획의 전체 ablation/7회 평가 | 실행 중 | 미완료 |

Tree DP와 Table DP를 별도 알고리즘으로 중복 구현하지 않은 점은 타당하다. 현재 message는 separator 상태별 exact table을 만드는 Tree/VE DP다. 성능 차이는 명칭보다 separator 폭, equivalence class 압축, 처리 순서, 그리고 인증에 불필요한 message를 생략하는지에 의해 결정된다. 현재 남은 문제는 DP 표현이 아니라 선택 정책이다.

## 현재 부분 평가가 보여 주는 것

아래 수치는 `evaluation/report-data.json`의 2026-10-15 11:21 스냅샷이다. 목표는 cold JVM 7회지만 이 스냅샷에는 after 5회, before 4회만 집계되어 있다. receipt에는 이후 job이 더 추가되고 있었으므로 **아래 median과 비율은 최종 benchmark 결과가 아니다.** 성능 방향을 정하는 임시 신호로만 사용한다.

| Workload | Local optimizer | Global optimizer | Local/Global | Local seed | Local message | Local propagation | Local/Global exact cells |
|---|---:|---:|---:|---:|---:|---:|---:|
| GLM | 5.075s | 2.267s | 2.24× | 3.084s | 0.538s | 0.184s | 5,431 / 1,360,977 |
| LogReg | 3.373s | 0.857s | 3.93× | 1.087s | 1.301s | 0.120s | 20,699 / 14,744 |
| GNMF | 0.523s | 0.228s | 2.30× | 0.249s | 0.151s | 0.036s | 2,885 / 3,308 |

해석 가능한 신호는 다음과 같다.

- 세 workload의 15개 after Local 행 모두 네 번의 `SEED_IMPROVEMENT`를 수행했지만 그 단계에서 UB가 한 번도 낮아지지 않았다. seed 시간은 Local optimizer 시간의 약 61%(GLM), 32%(LogReg), 48%(GNMF)였다.
- 최종 message reconstruction은 최초 seed 대비 UB를 GLM 83.31, LogReg 1,653.62, GNMF 663.41 낮췄다. 현재 workload에서는 고정 seed 개선보다 message가 UB와 LB 양쪽에 기여했다.
- GLM은 Global exact cell의 약 0.4%만 평가했지만 여전히 2.24배 느렸다. 이는 선택적 계산 자체는 성공했으나 seed/dispatch/evaluation overhead가 절감한 table 계산보다 비싸다는 뜻이다.
- LogReg은 Local message cell이 Global보다 약 40% 많았고 owner 489개 중 487개를 제거했다. GNMF도 85개 중 83개, GLM은 1,001개 중 929개를 제거했다. 현재 selector는 변수 기준으로 거의 전체 DP에 접근한다.
- original factor touch는 GLM 약 21%, LogReg 약 65%, GNMF 약 43%여서 factor 수준의 선택성은 존재한다. `completedMessages`를 전체 factor 접근량으로 해석하면 안 된다.
- propagation의 직접 LB 증가는 세 workload에서 message 증가보다 매우 작았지만 각각 481, 255, 29개 rule을 제거했다. 이 제거가 후속 message cell을 얼마나 줄였는지 ablation 전에는 propagation을 순손실로 단정할 수 없다.
- 부분 median의 container peak는 Local/Global 대비 GLM 1.04×, LogReg 1.76×, GNMF 1.02×다. 특히 LogReg의 retained-message/temporary allocation을 조사할 가치가 있지만 최종 반복과 phase별 메모리 증거가 먼저 필요하다.

## 중요한 설계 편차와 검증 공백

### 1. selector가 인증 부족분을 보지 않는다

`MemoLocalMessages.Estimate.betterThan`은 오직 cells, separator width, owner id를 비교한다. 현재 LB를 막는 factor, 예상 하한 상승, relaxed minimizer 불일치, `UB-1.05*LB` 감소량은 고려하지 않는다. 이 때문에 작은 메시지를 많이 계산하면서 거의 모든 owner를 제거할 수 있다.

search frontier가 최소 LB 영역을 먼저 처리하는 부분은 계획과 일치한다. 다만 search 진입 전 message 단계는 root 전체에 대한 단일 elimination stream이고, search branch도 degree/cardinality 기준이다. “최소 LB에 영향을 주는 conflict부터”라는 계획은 message/branch 선택까지 완성되지 않았다.

### 2. seed 개선은 선택 작업이 아니라 고정 선행 비용이다

첫 feasible plan 뒤 최대 네 scope를 시도하고, 각 결과가 UB를 낮추지 않아도 다음 scope로 진행한다. proof 상태를 오염시키지는 않으므로 정확성 문제는 없지만, 현재 부분 workload에서는 가장 큰 시간 낭비다. 계획의 “개선 이득이 작거나 LB 작업이 더 싸면 conflict로 전환” 규칙이 아직 없다.

### 3. factor 정밀화와 component/tree 선택이 없다

초기 floor 다음 선택은 whole legality propagation과 VE message다. 특정 numeric factor의 작은 Cartesian minimum만 계산하는 작업, 독립 component별 ledger, acyclic leaf/component를 먼저 수축하는 명시적 경로는 없다. VE가 tree를 정확히 처리할 수는 있지만, 현재 min-cell 순서만으로는 어떤 tree message가 global certificate에 필요한지 구분하지 못한다.

### 4. quantum은 호출 경계에만 있다

각 message는 `clusterCells` 이하로 제한되지만 `solve` 내부 table은 한 번에 완성한다. propagation도 fixpoint 전체를 한 호출에서 수행하고, factor bound도 허용 cell 전체를 한 번에 돈다. 현재 cap은 성공 종료 budget이 아니므로 요구사항 위반은 아니지만, selector가 작업 중간 효과를 보고 전환하거나 큰 작업을 재개하는 구조는 아니다.

### 5. 평가 matrix가 계획 전체를 아직 분리하지 않는다

현재 ablation driver는 messages on/off × seed improvement on/off 네 조합이다. 이는 가장 중요한 첫 비교에는 충분하지만 다음 항목은 분리하지 않는다.

- legality propagation on/off와 제거 rule 대비 downstream cell 절감
- min-cell selector와 gap/conflict-aware selector
- generic loopy VE와 acyclic tree/component 우선 처리
- factor-only bound refinement
- resumable quantum 크기
- message value/backpointer retention 및 cache eviction

`old-search` suite는 root direct를 끈 이전 Local 기준선을 제공한다. 하지만 계획에 적힌 minimum-LB ordering과 exact-tree-message 자체의 ablation은 현재 switch가 없어 직접 측정할 수 없다.

정확성 테스트도 주요 certificate invariant는 강하지만, 다음 강한 조건은 별도 fixture가 남아 있다.

- 서로 다른 두 seed에서 동일 proof snapshot의 다음 conflict/branch 순서가 같다는 직접 비교
- 무관한 큰 cluster와 독립 component를 결합한 선택성 사례
- 조건부 boundary optimum을 전역 LB로 잘못 승격하는 전용 반례
- 여러 quantum에 걸친 작업 재개와 동일 작업 비재시도
- 충분한 서로 다른 memory ceiling에서 같은 시작 정책을 확인하는 parameterized test

## 다음 성능 개선 우선순위

### P0. 진행 중 평가를 먼저 완결하고 auditor로 잠근다

7회 comparison, ablation, memory, runtime, old-search receipt가 모두 완전한지 확인한 뒤 certificate audit를 통과시킨다. 현재 부분 median을 최종 결론이나 회귀 기준으로 고정하지 않는다. 특히 seed-off 결과가 아래 P1의 효과를 직접 판정한다.

### P1. 무효 seed 개선을 즉시 중단한다

첫 feasible witness 뒤 네 scope를 무조건 실행하지 않는다. 가장 작은 한 scope를 시도해 UB 개선이 없으면 message/LB 작업으로 전환하거나, 예상 UB slack이 큰 scope만 선택한다. 현재 데이터에서는 seed improvement를 기본 off로 두는 후보도 강하다. 다만 최종 ablation에서 seed-on이 이기는 workload가 있으면 workload-independent한 안전 규칙으로 “평가당 UB 감소”와 남은 gap을 사용한다.

정확성 상태는 바꿀 필요가 없다. 개선 후보는 계속 전체 원본 check/cost로 검증하고 proof domain과 message state를 수정하지 않는다.

### P2. message selector를 `예상 인증 기여 / cells` 중심으로 바꾼다

sound bound는 완성된 message만 게시하되, 작업 순서는 휴리스틱으로 바꿀 수 있다. 후보 점수에는 다음을 순서대로 넣는 것이 적절하다.

1. active factor의 incumbent cost와 현재 certified floor 사이의 slack
2. relaxed minimizer가 separator에서 요구하는 값의 불일치 수
3. candidate가 흡수하는 아직 floor-only인 original factor 수와 shared movement weight
4. 예상 cells, separator width, 기존 cache 재사용

점수가 없는 경우에만 기존 min-cell 순서를 fallback으로 사용한다. 한 message 뒤 실제 LB/UB 개선과 소요 cells를 기록해 다음 선택을 갱신한다. 이 변경의 목표는 LogReg/GNMF의 99%/98% owner elimination을 줄이는 것이다.

### P3. 순수 tree/독립 component를 구조적으로 우선 처리한다

factor graph의 connected component와 leaf/acyclic core를 계산하고, 작은 separator tree message 중 global gap을 실제로 줄일 수 있는 component를 먼저 처리한다. 독립 component의 LB/UB는 합하고, component 내부 cover는 최소를 취한다. Tree DP 전용 자료구조를 새로 만들 필요는 없고 현재 exact message kernel을 재사용하면 된다.

이 단계는 GLM처럼 적은 factor만 만져도 큰 exact table을 대체할 수 있는 문제에서 효과가 크다. 모든 tree component를 먼저 완주하는 정책은 피하고 certificate가 성립하면 즉시 중단한다.

### P4. propagation과 factor-only refinement를 selector의 선택지로 만든다

whole propagation을 항상 실행하지 말고 예상 check work, non-singleton domain, 이전 제거율을 기준으로 선택한다. 먼저 propagation-off ablation으로 rule 제거가 message cells와 certificate time을 얼마나 줄였는지 측정한다.

작은 numeric factor의 exact minimum이 큰 slack을 제거할 수 있으면 VE message보다 먼저 factor-only bound를 계산한다. 이 작업은 separator table과 backpointer가 없어 더 싸며, 현재 계획의 누락된 refinement 종류를 채운다.

### P5. retained message 메모리와 긴 작업 latency를 줄인다

소비된 message는 이후 exact 계산에 필요한 value table과 최종 reconstruction에 필요한 choice/backpointer의 수명이 다르다. 새 message가 입력을 흡수한 뒤 이전 value table을 해제하고 compact backpointer만 보존할 수 있는지 검토한다. phase별 heap/GC 측정으로 LogReg의 container peak가 실제 retained table 때문인지 확인한 뒤 적용한다.

message/propagation/factor 작업을 resumable quantum으로 만들면 controller가 실제 gain을 보고 다음 작업으로 전환할 수 있다. 이는 현재 성능 병목을 확인한 뒤 P1–P4 다음에 진행하는 편이 낫다.

## 완료 판단

현재 구현은 certificate-first Local의 정확성 기준을 충족하며, “전체 exact 우선” 문제를 해결했다. 다음 성능 단계의 성공 기준은 단순히 Local status가 `EPSILON_OPTIMAL`인 것이 아니다.

- first feasible 이후 불필요한 seed evaluation 감소
- 인증 시점의 completed message/owner 및 touched factor 감소
- Local optimizer/certificate time이 줄어들고 LB/UB certificate가 그대로 유지됨
- 어려운 문제에서는 줄지 않더라도 RESOURCE_EXHAUSTED/UNRESOLVED/OPTIMAL 상태가 정직하게 유지됨
- 최종 7회 반복과 모든 ablation receipt가 완전하고 독립 auditor가 모든 인증을 승인함

이 기준에서 가장 먼저 바꿀 부분은 DP kernel이 아니라 seed scheduling과 conflict selector다.
