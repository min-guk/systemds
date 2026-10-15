# MEMO_LOCAL 반복 작업 재사용: 구현 및 비교 보고서

## 목적과 변경 범위

기준 버전은 `origin/main`에 게시한 `035f9c8eb76fc7cf793ffbde0127690ef131d16e`이다. 이 버전에서 Local은 이미 전체 exact를 먼저 호출하지 않고, 합법적인 UB와 완화 LB를 만든 뒤 필요한 conflict/message만 추가로 처리한다. 이번 후속 작업은 그 정책을 유지하면서 **인증에 기여하지 않는 반복 조회와 합산을 줄이는 것**이다. 7회 Docker 비교에서 Local optimizer median은 LogReg 21.34%, GLM 47.80% 줄었고, GNMF는 0.79% 느려졌다. 동일한 최종 인증과 선택한 구조적 작업 counter를 유지했다.

변경은 세 production 파일에 한정한다.

| 파일 | 변경 | 보존하는 동작 |
|---|---|---|
| `MemoFeasibleSeed.java` | Local 전용 first-feasible/first-improvement 경로에서 factor scope 및 마지막 배정 owner별 index 생성 | 후보 순서, 원래 evaluator, UNKNOWN, work limit, 완전한 UB 검증 |
| `MemoLocalMessages.java` | factor별 root minimum 캐시, message 교체 시 LB 증분 합산, insertion order를 보존하는 identity factor 제거 | 선택되는 bucket, 모든 비용 소유권, conditional query, exact arithmetic |
| `MemoLocalOptimizer.java` | 고정 domain/setup 메모리 추정 재사용, root 전용 bound query 사용 | 초기 인증 기회, 매 message 직후 인증 검사, 전체 proof cover |

기존 `find`/`improve` seed API, default EXACT, MEMO_GLOBAL의 최적화 경로는 바꾸지 않았다. 원래 RuleMemo 후보 공간, 비용, 함수·분기·루프·공유 producer/movement 제약과 plan reconstruction은 그대로 사용한다. 스케줄러를 바꾸거나, 5% 인증 검사를 생략하거나, 탐색 후보를 줄인 변경이 아니다.

## 왜 안전하게 빠르게 할 수 있는가

### Seed에서 검사 가능한 factor만 조회

Local의 seed 열거는 owner ordinal 순서대로 배정한다. scope의 가장 큰 owner가 현재 owner인 factor만 이 단계에서 새로 완성된다. `checksClosingAt[owner]`와 `costsClosingAt[owner]`를 준비하면, 매 후보마다 모든 factor를 훑고 scope 배열을 복사하는 대신 해당 목록만 확인할 수 있다.

이 index는 factor의 의미를 바꾸지 않는다. 원래 scope 순서로 evaluator tuple을 만들고, 최종 witness는 전체 원본 check와 cost로 재검증한다. UNKNOWN을 infeasible proof로 사용하지 않는다. Seed가 UNKNOWN 후보를 보류하더라도, UB로 채택하는 assignment는 모든 원본 check가 YES여야 한다. scope가 비어 있는 factor는 최종 전체 검증에 남는다. 기존 seed API는 원래 경로를 유지한다.

추가 index의 보수적인 메모리 추정을 allocation 전에 admission에 포함한다. 다만 `workLimit=0` 또는 모든 개선 scope가 비어 있으면 index를 사용하지 않으므로, 기존 workspace 추정과 incumbent 반환 동작을 유지한다. 독립 리뷰가 이 경계의 회귀 가능성을 지적했고 유한 메모리 회귀 테스트와 함께 수정했다.

### Root LB를 다시 합산하지 않아도 되는 이유

현재 활성 factor 집합을 \(F\), 현재 root domain을 \(D\)라고 하자. 각 factor의 유효한 하한을 \(\ell_f(D)\le\min_D f\)로 두면 root bound는 다음과 같다. Native/message에는 domain상 최솟값을 쓰고, 아직 합쳐지지 않은 비상수 cost에는 `certifiedMinimum`, 비상수 check에는 0을 쓴다. 따라서 이름이 minimum인 캐시도 모든 원본 factor를 완전 열거한 최솟값이라는 뜻은 아니다.

\[
L(F,D)=\sum_{f\in F}\ell_f(D).
\]

각 항이 해당 factor의 모든 가능한 비용 이하이므로, 같은 전역 assignment에 대해 합산해도 이 값은 전역 최적값의 하한이다. 제약 factor는 불법 tuple을 배제하며, UNKNOWN은 증명된 불법으로 간주하지 않아 완화를 보존한다. 상수·음수 비용도 원래 부호 그대로 합산한다.

owner \(v\)에 incident한 bucket \(B\)를 완전히 풀어 message \(m\)을 만들면,

\[
m(s)=\min_{x_v}\sum_{f\in B}f(x_v,s),\qquad
F'=(F\setminus B)\cup\{m\}.
\]

이번 구현은 root의 domain이 변하지 않는 동안 다음 항등식을 사용한다.

\[
L(F',D)=L(F,D)-\sum_{f\in B}\ell_f(D)+\min_D m.
\]

영향을 받지 않은 factor의 하한 항은 다시 계산할 필요가 없다. `BigDecimal` 덧셈·뺄셈으로 정확하게 유지하며, 완성되고 admission된 message만 활성 factor 교체와 함께 게시한다. representation이 거절되면 이전 factor와 bound가 남는다. 불가능한 relaxed state의 `null` 의미도 유지한다.

**이 캐시는 원래 root domain에만 적용한다.** 이후 분기에서 더 좁아진 conditional domain은 전체 활성 factor를 기존 방식으로 검사한다. 이미 eliminated된 변수의 restriction을 무시하는 기존 conditional relaxation도 그대로 유지하며, 복원한 assignment를 원래 모델과 요청 state에 대조하기 전에는 state를 닫지 않는다.

### 인증 기준과 Global의 검사 비용

Local은 매 완료된 refinement 후 \(LB>0\)이고 \(UB\le1.05LB\)인지 확인한다. UB는 원본 전체 모델에서 합법성을 검증한 plan의 비용이다. 성공 조건에 시간이나 고정 iteration 수를 추가하지 않았다. 정확한 최적성 증명이 없으면 `OPTIMAL`로 바꾸지 않는다. 자원 부족이나 미해결 UNKNOWN은 기존의 명시적 실패/미해결 상태를 유지한다.

이번 개선은 인증 검사를 없애서 얻는 속도 향상이 아니다. **검사에 필요한 LB를 만드는 중복 계산을 줄인다.** Global의 중간 LB/UB 검사 정책이나 direct-exact 전략은 이번 변경 범위 밖이다. Local이 언제나 Global보다 빠르다는 보장은 없다. 낮은 treewidth의 전체 exact가 저렴한 문제에서는 초기 UB와 부분 인증 준비 자체가 더 비쌀 수 있다.

## 실험 설계와 해석 경계

- 기준 엔진: 이전 보고서의 frozen `engine-v8`; 새 엔진: `memo-local-efficiency-20261015/engine-v1`. Java source와 binary SHA-256 manifest를 보관한다.
- 비교: LogReg, GLM, GNMF × EXACT, MEMO_GLOBAL, MEMO_LOCAL × 변경 전후 × 7회. 총 126 compile cells. 엔진 및 workload/algorithm 순서는 seed 20261015로 섞고 각 cell은 새 JVM에서 실행한다.
- 기본 설정: `improveSeed=false`를 양쪽에 명시한다. 이전 보고서의 strong-seed main 표와 직접 섞지 않는다.
- Ablation: GNMF에서 messages on/off × seed improvement on/off × 3회, 각각 EXACT를 함께 실행한다. 총 24 compile cells.
- 추가 검증: 실제 federated worker 테스트, 동일 factor 문제의 synthetic/legacy Regional 비교, heap 1,024/1,536 MiB. 기본 coordinator heap은 3,072 MiB이다.
- 실행 경로: `run_LAN_docker.sh`만 사용한다. 3 workers, 4 CPU, 8 GiB container 조건을 사용한다. 외부 watchdog은 실패를 기록하기 위한 장치이며 인증 성공 조건이 아니다.
- 큰 50,000×128 ML 입력은 전체 source compile 측정이다. 실제 수치 학습은 작은 linear/logistic fixture에서 검증한다. 큰 GNMF training 전체를 실행한 것으로 해석하면 안 된다.
- legacy Regional은 동일 factor로 구성할 수 있는 synthetic 문제에서만 비용을 비교한다. 원래 legacy와 Memo의 전체 ML optimization universe가 같다고 가정하지 않는다.
- container memory는 worker와 charged cache를 포함하는 Docker MemUsage high-water이며 RSS나 live Java heap이 아니다. solver 추정 bytes와 별도로 기록한다.
- 7회 측정의 median과 paired range는 기술 통계다. host 부하/GC/JIT 변동이 있으므로 통계적 유의성이나 모든 workload에 대한 우월성을 주장하지 않는다.

## 정확성 및 실험 검증

변경 후 targeted 82 tests, 확대한 `*Memo*Test` 915 tests/141 classes가 통과했다. 새 테스트는 signed/UNKNOWN/상수 factor, root/conditional bound 분리, admission 실패 후 동일 bound, seed 순서·작업 제한·repair, zero-work/empty-scope의 유한 메모리 동작을 다룬다. 기존 exhaustive/oracle, adversarial shared movement, 함수·분기·루프 회귀도 포함한다.

첫 broad 테스트의 깊은 source fixture에서 stack overflow가 발생했다. 수정 전 frozen 엔진에서도 `MemoBindingResolver.resolve/resolveSelect` 재귀 overflow가 재현된다. 별도 `-Xss4m` baseline 실행에서는 해당 클래스 6 tests가 통과했고, 최종 broad Maven rerun도 four forks/default stack에서 통과했다. Maven의 CLI argLine override는 기존 POM/JaCoCo 설정에 의해 덮어써졌으므로, 최종 Maven이 스택을 늘려 통과했다고 기록하지 않는다. 이 기존 깊이 한계는 남아 있으며 테스트를 비활성화하거나 runtime fallback을 추가하지 않았다.

실험 validator는 기존의 독립적인 LB/UB·oracle·action-order 감사를 재사용하고 다음 조건을 추가했다: harness 반환 코드 0, `summary.complete=true`, runtime/synthetic 성공, memory suite 존재 및 행 수, 실제 seed mode. 이는 실행 실패 뒤 summary 파일만 남아도 성공처럼 보일 수 있었던 실험 수집의 빈틈을 막는다. 기존 보고서 수치를 소급 변경하지 않으며, 새 최종 판정은 강화된 validator를 사용한다.

## 최종 수치와 판단

### 성능: 동일 설정의 7회 변경 전후 비교

| workload | 이전 Local s | 새 Local s | 시간 감소 | 새 EXACT s | 새 MEMO_GLOBAL s |
|---|---:|---:|---:|---:|---:|
| logreg | 2.8498 | 2.2416 | 21.34% | 0.8373 | 0.8915 |
| glm | 3.5669 | 1.8620 | 47.80% | 2.3245 | 2.5617 |
| gnmf | 0.4257 | 0.4291 | -0.79% | 0.1813 | 0.1963 |

LogReg와 GLM은 7개 paired observation 모두 새 Local이 빨랐다. GNMF는 median 기준 0.8% 느리고 paired 비율도 양방향으로 흔들린다. 따라서 GNMF 개선은 주장하지 않는다. GLM Local optimizer는 EXACT보다 약 19.9% 빨랐지만, LogReg는 EXACT의 약 2.68배, GNMF는 약 2.37배 시간이 걸렸다. 후보를 줄이거나 더 나쁜 oracle을 사용해 만든 비교가 아니다.

| workload | Local paired speedup median [min,max] | 전체 compile 이전→이후 s | container memory 이전→이후 MiB |
|---|---:|---:|---:|
| logreg | 1.252 [1.067, 1.545] | 6.3655 → 5.4926 | 464.5 → 444.6 |
| glm | 1.730 [1.667, 2.734] | 11.1831 → 9.4848 | 1030.1 → 991.6 |
| gnmf | 1.124 [0.876, 1.536] | 1.7782 → 1.8016 | 172.1 → 163.5 |

speedup > 1은 새 버전이 빠르다는 뜻이다. Ratio of medians와 median of paired ratios는 서로 다른 통계다. 변경하지 않은 EXACT/Global도 실행별 변동이 있으므로 작은 차이를 개선으로 해석하지 않는다. GLM의 새 전체 compile 시간은 Local 9.4848초, EXACT 9.5043초로 거의 같다. Source facts/rules/model 구성 등의 비용 때문에 optimizer 단계의 우위가 전체 compile에서 같은 비율로 나타나지 않는다.

| workload | seed 시간 이전→이후 s | message 단계 이전→이후 s | 새 solver 추정 peak MiB |
|---|---:|---:|---:|
| logreg | 0.6936 → 0.4041 | 1.5077 → 1.3884 | 10.40 |
| glm | 1.6097 → 0.6813 | 0.5332 → 0.4831 | 14.58 |
| gnmf | 0.1197 → 0.1055 | 0.1692 → 0.2119 | 4.97 |

GLM의 seed 비용 감소가 특히 크다. 이번 실험은 세 개선을 함께 적용한 결과이며 각 코드 변경의 독립적인 인과 효과를 분리한 ablation은 아니다. Message phase에는 이제 증분 root 합산도 들어가므로 이전과 새 phase 분류의 미세한 차이에 유의해야 한다. 전체 optimizer 시간이 최종 비교 지표다.

### LB/UB와 선택적 작업량

| workload | 최종 LB | 최종 UB | 독립 EXACT OPT | 인증 gap | 실제 cost gap | 완료 bucket/owner |
|---|---:|---:|---:|---:|---:|---:|
| logreg | 4027.691903 | 4193.677114 | 4041.331216 | 4.121100% | 3.769696% | 487/489 |
| glm | 407.816699 | 426.222945 | 426.222945 | 4.513362% | 0.000000% | 929/1001 |
| gnmf | 217.001403 | 217.047325 | 217.047325 | 0.021162% | 0.000000% | 83/85 |

표의 숫자는 표시용 반올림이며 인증은 원래 정밀도의 값으로 검사했다. GLM/GNMF의 비용이 별도 EXACT 결과와 같더라도 Local 자체의 LB가 exact를 증명하지 않았으므로 결과는 `EPSILON_OPTIMAL`이다. 변경 전후 동일 source·최종 bound·status·route와 선택한 구조적 작업 counter가 일치한다. 새 기본 경로의 message cells는 LogReg 20,699, GLM 5,431, GNMF 2,885이고 search nodes는 모두 0이다.

분해는 모든 원본 factor scope를 기준으로 한다. 완료 bucket 비율이 92.8–99.6%인 점도 그대로다. 이번 속도 향상은 더 적은 conflict를 풀어서 생긴 것이 아니라 동일 proof 작업의 중복 overhead를 줄여 얻은 결과다. UB 검증·모델 구성은 여전히 전체 원본 factor를 읽을 수 있으므로 `messageTouchedFactors`를 전체 모델 미접근 비율로 해석하면 안 된다.

### GNMF message/seed ablation

| messages | extra seed improvement | optimizer median s | seed median s | search nodes |
|---|---|---:|---:|---:|
| off | off | 2.7184 | 0.1034 | 3357 |
| off | on | 2.6468 | 0.1791 | 3357 |
| on | off | 0.3684 | 0.0784 | 0 |
| on | on | 0.4770 | 0.2212 | 0 |

각 조합은 3회 측정했다. Messages가 없는 경우에도 전역 domain cover를 유지하는 탐색으로 5% 인증을 얻었지만 3,357 nodes를 처리했다. Messages on/seed off는 약 7.38배 빨랐다. Extra seed improvement는 각 실행의 네 번 시도 모두 초기 UB를 낮추지 못했고, messages-on에서는 median 0.3684→0.4770초로 느려졌다. 따라서 default seed improvement를 켜는 변경은 하지 않는다. 이 ablation은 GNMF에 한정하며 다른 ML workload의 strong-seed 성능으로 일반화하지 않는다.

### 최종 감사와 남은 한계

- 29 jobs, **165/165 compile cells**, **104 certificates**, **5,073 recorded LB/UB points**가 독립 감사를 통과했다. 새 Local 40 rows 모두 directAttempts=directExactSolves=0이다. 최종 matrix에서 timeout, 자원 고갈, lowering 실패, 미인증 성공은 없었다.
- Docker JUnit **18 methods**가 통과했다. 이 중 실제 worker 기반 11 methods(그 안의 작은 linear/logistic 수치 학습 10 cases 포함), compiler/binding 7 methods이다. **42 synthetic rows**와 동일 factor로 제한한 legacy Regional adapter도 통과했다.
- 1,024/1,536 MiB heap의 12 compile cells에서 같은 Local 정책·최종 인증을 유지했다. 임의의 OOM 회복이나 모든 큰 workload 실행 가능성을 증명한 것은 아니다.
- Java **915/915**, harness Python **23**, 기존 auditor Python **5**, 새 validator Python **8** tests 통과. `javac -Xlint:all`은 변경 소스의 경고 없이 성공했고, dependency JAR manifest의 누락된 선택적 Class-Path 경고는 로그에 남겼다.
- 현재 3,982 Java sources와 frozen 엔진 8,907 files가 SHA-256과 일치한다. 기준 엔진의 3,981 sources는 커밋 `035f9c8eb7`과 일치하며 before/after receipt의 엔진 경로도 각각 단일한 서로 다른 엔진이다.

“Local이 언제나 exact보다 빠르다” 또는 “최소 계산으로 인증한다”는 결론은 성립하지 않는다. GNMF 성능은 개선되지 않았고, LogReg는 exact보다 여전히 느리다. 또한 깊은 binding recursion의 기본 stack 한계는 이번 변경으로 해결하지 않았다.

다음 최적화 후보는 (1) LogReg의 반복 factor 평가 재사용, (2) 실제 gap 감소에 비해 비싼 bucket의 선택 순서, (3) message마다 복원/검증하는 UB 작업의 재사용이다. 각각 전체 미탐색 영역의 LB, 원래 합법성, 비용 소유권과 certificate check를 보존하는 별도 실험이 필요하다. 이번에는 검증된 반복 작업 재사용까지만 반영했으며, 측정하지 않은 다음 단계의 성능 향상을 주장하지 않는다.

## 재현 자료

- [실험 README와 명령](../experiments/memo-local-efficiency-20261015/README.md)
- [실행 계획](../experiments/memo-local-efficiency-20261015/implementation-plan.md)
- [Java 테스트 요약](../experiments/memo-local-efficiency-20261015/java-test-summary.json)
- [커널 리뷰](../experiments/memo-local-efficiency-20261015/wave1-kernel-review.md)
- [Seed 리뷰 및 수정 검증](../experiments/memo-local-efficiency-20261015/wave1-seed-review.md)
- [실험 드라이버 리뷰](../experiments/memo-local-efficiency-20261015/wave1-benchmark-review.md)
- [이전 certificate-first 보고서](COFEE_MEMO_LOCAL_CERTIFICATE_FIRST_REPORT_2026-10-15_KO.md)

- [최종 통합 감사](../experiments/memo-local-efficiency-20261015/evaluation/evaluation-validation.json)
- [전체 비교 표](../experiments/memo-local-efficiency-20261015/evaluation/report-tables.md)
- [paired 비교](../experiments/memo-local-efficiency-20261015/evaluation/paired-efficiency.md)
- [소스·엔진 provenance 검증](../experiments/memo-local-efficiency-20261015/final-provenance-validation.json)
