# MEMO_LOCAL certificate-first 구현·실측 보고서

기준: `5734de2021f2841d82539063a6b450db69b9f7cb`, 별도 worktree `cofee-tree-dp-20261014`. 이 문서는 [승인된 계획](COFEE_MEMO_LOCAL_CERTIFICATE_FIRST_PLAN_2026-10-15_KO.md)의 구현과 Docker 검증을 기록한다. 구현, 반복 실험, 독립 인증 감사를 완료했다. 정책 목표는 충족했으며 현재 성능의 한계는 §5와 §7에 기록한다.

## 1. 구현한 정책

`MEMO_LOCAL`은 `MemoLocalOptimizer`로 진입한다. 전체 문제의 `optimizeDirect()` 또는 `optimize()`는 호출하지 않는다. `MEMO_GLOBAL`의 기존 direct exact 우선 경로와 기본 `EXACT` 선택은 유지했다.

1. 모든 원본 후보 coverage를 확인하고 native 최소값과 원본 cost factor의 certified floor로 초기 전역 LB를 만든다. 상수 비용은 정확히 포함한다.
2. `MemoFeasibleSeed.findFirst()`가 첫 완전 합법 witness에서 반환한다. 모든 원본 check가 YES인지와 전체 원본 비용을 재검증해 UB로 등록하고 즉시 인증을 검사한다.
3. 설정된 경우 작은 원본 factor scope를 대상으로 `improveFirst()`를 실행한다. 네 scope, scope당 최대 4,096 heuristic work까지 시도하며 새 witness마다 검사한다. 이는 seed 개선량 제한이다. 전체 optimizer의 성공·종료 예산이 아니다.
4. 인증이 부족하면 메모리 admission을 거친 합법성 support filtering을 선택한다. 새 `propagateOnly()`는 기존 Memo의 NO-only 제거 규칙을 재사용하며 비용 최적화·tree·VE·탐색·witness 생성은 호출하지 않는다. UNKNOWN은 남긴다. 이 전체 support pass는 초기 인증 기회 이후에만 실행한다.
5. `MemoLocalMessages`가 작은 bucket 하나씩 완성해 LB를 강화한다. 모든 후보 bucket의 표를 먼저 만들지 않고, scalar cell/width 추정만 보관한 뒤 선택한 bucket의 표를 만든다. 원본 factor의 certified class가 증명하는 불변 축과 singleton 축은 dependency scope에서 제거하되 evaluator에는 원래 길이의 tuple을 전달한다.
6. 매 message 반영 후 기존 UB로 먼저 인증을 검사한다. 부족할 때만 witness를 복원·원본 검증한다. 작은 message가 더 이상 적합하지 않으면 최소 LB 영역을 우선하는 complete-cover search로 전환한다. 선택된 seed 값은 proof 분기 순서나 원본 domain을 고정하지 않는다.

현재 message 순서는 class-aware cell 수, separator 폭, owner ID에 따른 결정적 휴리스틱이다. 다음 작업의 실제 LB 증가량을 미리 아는 selector는 아니다. 작은 message cap은 탐색으로 넘길 표현을 선택하며, cap 때문에 성공을 선언하지 않는다. 어려운 문제에서는 거의 전체 message나 많은 domain split이 필요할 수 있다.

## 2. 경계와 하한의 의미

HOP input edge만으로 분해하지 않는다. 원본 `MemoProgramModel`의 모든 native cost, numeric cost factor, hard check가 연결을 정한다. 공유 producer·movement, 함수 인자/반환, branch와 loop 관계는 해당 factor의 scope로 유지된다. Tree DP의 표와 일반 table DP는 별개의 수학적 최적화 원리가 아니다. 여기서는 작은 separator에서 min-sum 표를 계산하고, 큰 경계는 남겨 탐색한다.

Owner `v`를 제거할 때 해당 owner에 연결된 **모든 현재 factor**를 `B_v`라 하면, separator `S`의 message는 다음과 같다.

`m_v(x_S) = min_{x_v} Σ_{f∈B_v} f(x_scope(f))`

Numeric primitive는 원본 factor 또는 그것을 대체한 message 중 하나에만 소유된다. 소비한 factor를 제거하고 완성된 message로 대체하므로 공유 이동 비용을 중복 합산하지 않는다. 원본 check의 NO는 금지하고, UNKNOWN은 LB 계산에서 허용한다. 따라서 완성된 message라도 원본 합법성의 exact 증명이라고 자동 간주하지 않는다.

아직 결합하지 않은 서로 다른 factor/message의 최소값을 합하면 일관성 제약을 풀어 준 전역 하한이다. 모든 비용은 기존 목적함수와 동일한 binary64 값의 정확한 합(`new BigDecimal(double)`)으로 계산한다. `BigDecimal.valueOf(double)`의 짧은 십진 표기로 목적함수를 바꾸지 않는다.

Domain split 이후 원본 공간의 대안 영역은 합하지 않는다.

`LB = min(UB, min_open L_R, min_unknown L_R, min_epsilon_deferred L_R)`

부모를 제거하기 전에 두 자식 영역과 하한을 확보한다. 할당 admission이 실패하면 부모와 그 하한이 남는다. 자식 하한은 부모 하한, 원본 독립 factor 하한, 재사용 가능한 message 하한의 max다. 이미 제거한 내부 변수에 대한 새 domain 제한은 기존 message query에서 무시한다. 이것은 더 약한 relaxation이므로 안전하다. 원본 domain cover가 최종적으로 해당 제한의 정확한 검증을 담당한다.

항상 검증된 완전한 witness만 UB다. `LB=UB`이면 비용 최적성을 증명해 OPTIMAL, `LB>0 && UB≤1.05×LB`이면 EPSILON_OPTIMAL이다. 후자는 `(UB−OPT)/OPT≤5%`도 보장한다. LB가 0 이하이면 상대 오차 종료는 적용하지 않는다. UNKNOWN이나 메모리가 인증을 막으면 UNRESOLVED/RESOURCE_EXHAUSTED이며 성공 계획으로 적용하지 않는다. Local의 비용 최적성은 Global의 canonical tie 선택 계약과 구분한다.

## 3. 메모리와 계측

기본 admission은 JVM/cgroup headroom과 기존 16–256 MiB reserve를 사용한다. 명시적인 `memoryBytes` 상한은 시험용으로도 지원한다. 표·선택한 후보 metadata·propagation workspace·동시 child publication을 예상한 뒤 할당한다. Optional cache와 history는 필수 proof cover보다 먼저 버린다. 메모리 크기는 Local 진입 정책을 바꾸지 않는다.

추정치는 JVM 전체 RSS나 물리적 최대 실행 가능 메모리의 증명이 아니다. 외부 evaluator 또는 JVM 자체의 OOM까지 복구한다고 주장하지 않는다. Docker peak memory와 solver의 estimated peak를 구분해 기록한다. 고정 시간·반복 수에 의한 성공 종료는 없다. 실험 외부 watchdog이나 개발 중 수동 중단은 인증 실패/중단으로 별도 기록한다.

`[MemoDualBound]`는 LB/UB 진행, `[MemoLocalAction]`은 초기 bound·seed·propagation·message·split, `[MemoLocalSummary]`는 완료/미처리 bucket, candidate preparation, message가 만진 원본 factor/variable, active/UNKNOWN/deferred 영역과 메모리 거절을 남긴다. `Result.localStatistics()`에도 최종 counters가 보존된다.

**messageTouchedFactors/Variables는 message 계산 범위다.** 원본 모델 구축, seed 및 완전한 UB 검증이 해당 factor를 읽지 않았다는 뜻이 아니다. 또한 untouched bucket 수만으로 planning 속도 향상을 주장하지 않는다. 선택 순서, bound query, 검증, reconstruction 비용도 optimizer 시간에 포함한다.

## 4. 검증 방식과 개발 중 드러난 문제

독립 Cartesian enumeration은 signed·fractional·high-arity·shared-cost 모델의 OPT를 계산해 모든 공개 bound를 검사한다. 초기 100/105 인증, 5% 경계 바로 바깥, 비양수 LB, UNKNOWN, 불가능·불완전 coverage, seed의 domain 비고정, 작은 일부 message 이후 종료, memory admission/atomic child 보존을 검사한다. 별도의 무작위 GAC oracle로 support filtering도 검사한다. 전체 요구와 실제 test의 대응 및 남은 강한 조건은 [coverage 표](../experiments/memo-local-certificate-first-20261015/correctness-coverage.md)에 구분했다.

개발 파일럿은 최종 비교와 분리 보존한다.

- v4: 원본 전체 domain과 불필요한 factor 연결 때문에 LogReg에서 큰 표와 탐색이 발생했다. 명시적으로 중단한 미인증 개발 실험이며 성공 수에 넣지 않는다.
- v5: support filtering을 재사용해 LogReg에서 LB 3943.74784, UB 4105.40554의 4.0991% 인증을 얻었다. 그러나 물리 lowering에서 `One physical Memo producer cannot publish multiple native origin markers`가 발생해 전체 pipeline 실패로 기록했다. GLM도 과도한 경계 탐색을 보여 중단했다.
- v6: 세 모델 모두 인증했지만 LogReg의 다른 producer에서 같은 lowering 오류가 발생했다. 모델 인증만으로 compiler 전체 성공이라고 표시하지 않았다. v7은 세 workload × 세 알고리즘의 fullcompile 9건을 모두 통과했다.
- 원인 수정: certified-invariant factor 축의 연결을 제거하고 상수 factor 평가를 재사용했다. **동일 HOP·동일 statement block**에 속한 논리 occurrence의 native origin을 공통 physical producer에 한 번 정의하고, caller별 반환 identity는 첫 export source remap을 통해 기존 chain으로 전달한다. 정확한 multi-output slot 구분과 lowering의 단일 marker 검사는 유지한다. 다른 HOP들이 후속 lowering에서 같은 Lop으로 합쳐지는 경우까지 일반화하지 않았다. 이 변경은 후보·비용·합법성 검사를 완화하지 않는다.

최종 Memo 관련 회귀 검증은 117개 class, **775 tests / failures 0 / errors 0 / skipped 0**이다. Python benchmark harness 23개와 독립 auditor 5개도 통과했다. 변경된 production Java 7개를 `javac --release 17 -Xlint:all`로 검사해 source 경고는 없었다. 기존 의존 jar의 manifest가 참조하는 optional JAXB/Derby locale 경로 경고 20개는 남아 있다. 전체 프로젝트의 모든 테스트나 RAT 검증을 실행한 것은 아니다. [독립 리뷰](../experiments/memo-local-certificate-first-20261015/final-review.md)는 차단 문제 없이 승인했고, [검증 결과](../experiments/memo-local-certificate-first-20261015/test-summary.json)와 XML을 보존했다.

## 5. 최종 Docker 평가

**정책 분리와 5% 인증은 달성했다. 이 세 ML workload에서는 새 Local이 기존 direct exact보다 빠르지는 않았다.** 원본 문제를 보존한 비교에서 모든 최종 compile 시도는 성공했지만, 기본 optimizer를 Local로 바꿀 성능 근거는 없다. GNMF 비교에서 메시지를 켠 Local은 이전 Local의 direct-exact-disabled 탐색보다 크게 빨랐다.

LogReg·GLM·GNMF는 sealed ADULT feature 입력 `50,000 × 128`, private-aggregate, worker 3을 사용한다. LogReg/GLM의 public Y는 `50,000 × 1`이다. 파일의 원래 DML bytes, 데이터·metadata hash, 생산 비용 기본값을 보존했다. 대규모 데이터 자체의 학습 실행이 아니라 실제 training program 전체의 계획·lowering을 측정한 실험이다.

### 5.1 성공 여부와 인증

최종 **195/195 source-fullcompile** 시도: comparison 126, ablation 24, runtime suite의 compile 3, memory 12, old-search 12, seed-off 18. 독립 감사는 **119개 certificate와 최종값을 포함한 4,515개 LB/UB 지점**을 동일 문제의 별도 EXACT 결과와 비교했다. 새 Local 49건은 모두 `LOCAL_CERTIFICATE_FIRST`, directAttempts=0, directExactSolves=0이다. 최종 suite의 인증 실패·watchdog·OOM·lowering 실패는 0건이다. 중단/실패한 개발 파일럿은 이 수에 포함하지 않는다.

최종 실제 비용은 다음과 같다. 출력은 반올림했지만 감사에는 보존된 전체 decimal을 사용했다.

| workload | EXACT OPT | Local LB | Local UB | 인증 gap | 실제 cost gap | Local status |
|---|---|---|---|---|---|---|
| LogReg | 4041.331216 | 4027.691903 | 4193.677114 | 4.121100% | 3.769696% | EPSILON_OPTIMAL |
| GLM | 426.222945 | 407.816699 | 426.222945 | 4.513362% | 0.000000% | EPSILON_OPTIMAL |
| GNMF | 217.047325 | 217.001403 | 217.047325 | 0.021162% | 0.000000% | EPSILON_OPTIMAL |

GLM·GNMF의 UB가 별도 EXACT의 OPT와 같아도 Local 자체의 LB가 UB와 같지 않으므로 EPSILON_OPTIMAL로 보고한다. 별도 oracle의 답을 Local의 종료 증명에 주입하지 않았다. 기존 MEMO_GLOBAL은 모든 주 비교에서 DIRECT 경로로 OPTIMAL을 증명했다.

### 5.2 기존 버전과 planning 시간

주 비교는 각 cell 7회다. 아래 시간은 초, memory는 Docker MemUsage sample high-water의 반복 중앙값(MiB)이다. `optimization`에는 controller와 bound/validation/reconstruction 비용이 포함되고, `fullcompile`에는 후보·모델 생성과 lowering 등도 포함된다. 큰 heap이 곧 optimizer 메모리 사용량은 아니다.

| workload | algorithm | 이전 opt | 신규 opt 중앙값 [최소, 최대] | fullcompile 이전 → 신규 | container MiB 이전 → 신규 |
|---|---|---|---|---|---|
| LogReg | EXACT | 0.8620 | 0.8889 [0.7744, 1.2877] | 4.1007 → 4.2599 | 303.1 → 307.8 |
| LogReg | MEMO_GLOBAL | 0.9188 | 0.8874 [0.7710, 0.9772] | 4.3020 → 4.2010 | 297.0 → 302.0 |
| LogReg | MEMO_LOCAL | 0.9143 | 3.3725 [2.9160, 3.6230] | 3.9497 → 6.7416 | 291.8 → 543.5 |
| GLM | EXACT | 2.5263 | 2.4853 [2.2844, 2.8010] | 10.1793 → 10.1235 | 993.4 → 1001.0 |
| GLM | MEMO_GLOBAL | 2.3542 | 2.2669 [2.1468, 2.7226] | 9.8189 → 9.5868 | 912.2 → 1048.6 |
| GLM | MEMO_LOCAL | 2.8094 | 5.6507 [4.6147, 6.0533] | 9.8618 → 13.6677 | 998.3 → 1066.0 |
| GNMF | EXACT | 0.1985 | 0.2128 [0.1609, 0.2558] | 1.4274 → 1.6622 | 162.1 → 153.7 |
| GNMF | MEMO_GLOBAL | 0.2191 | 0.2276 [0.1851, 0.2935] | 1.5849 → 1.5737 | 164.5 → 156.5 |
| GNMF | MEMO_LOCAL | 0.2399 | 0.5231 [0.4327, 0.6148] | 1.7476 → 1.7904 | 164.9 → 161.1 |

Strong-seed 옵션을 켠 신규 Local의 optimizer 중앙값은 동일 신규 EXACT 대비 LogReg **3.79배**, GLM **2.27배**, GNMF **2.46배**였다. 이전 Local은 메모리가 충분해 direct exact를 먼저 풀던 정책이므로 기존 Local과의 차이도 명시한다. Global/EXACT의 작은 before/after 시간 차이는 알고리즘 개선 효과로 주장하지 않는다.

### 5.3 얼마나 일부만 처리했는가

아래는 원본 factor 전체를 기준으로 한 message kernel 계측이다. 모든 주 비교 반복에서 작업 수와 최종 비용은 같았다.

| workload | 변수 | 원본 factor | 완료 bucket | 미처리 | message touched factor | touched 변수 | message cells | search nodes |
|---|---|---|---|---|---|---|---|---|
| LogReg | 489 | 9595 | 487/489 (99.59%) | 2 | 6237/9595 | 92 | 20699 | 0 |
| GLM | 1001 | 17093 | 929/1001 (92.81%) | 72 | 3655/17093 | 163 | 5431 | 0 |
| GNMF | 85 | 614 | 83/85 (97.65%) | 2 | 263/614 | 18 | 2885 | 0 |

이것은 HOP DAG의 순수 tree 비율이 아니다. 모든 hard/cost dependency를 반영하고 certified-invariant 축을 제거한 뒤의 작은 separator 작업량이다. GLM에서는 72개 bucket을 남겼지만 전체의 92.81%를 완료했고, LogReg/GNMF도 대부분의 bucket이 필요했다. touched factor가 적어도 seed·full witness 검증은 원본 전체를 볼 수 있다.

첫 반복의 LB/UB 흐름은 다음과 같다. 각 원본 로그에는 전체 progression을 남겼다.

| workload | 지점 | 완료 message | LB | UB |
|---|---|---|---|---|
| LogReg | 첫 합법 seed | 0 | 16.70189 | 5847.29788 |
| LogReg | message 약 50% | 244 | 1111.21925 | 5847.29788 |
| LogReg | 인증 종료 | 487 | 4027.69190 | 4193.67711 |
| GLM | 첫 합법 seed | 0 | 14.28611 | 509.53017 |
| GLM | message 약 50% | 465 | 152.99567 | 509.53017 |
| GLM | 인증 종료 | 929 | 407.81670 | 426.22295 |
| GNMF | 첫 합법 seed | 0 | 8.80269 | 880.46033 |
| GNMF | message 약 50% | 42 | 8.84326 | 880.46033 |
| GNMF | 인증 종료 | 83 | 217.00140 | 217.04733 |

네 번의 optional seed 개선은 주 비교 21개 Local 실행에서 초기 UB를 한 번도 낮추지 못했다. 이후 message 복원에서 더 좋은 합법 계획을 얻었다. 작은 cell부터 처리하는 현재 순서는 많은 중간 bucket에서 LB를 올리지 못한다. 따라서 “conflict 없는 작업을 먼저 처리하면 가장 빠르다”는 결론은 이 실험으로 지지되지 않는다.

아래 단계 시간은 반복 중앙값이다. 서로 다른 실행의 중앙값이므로 합계 분해로 해석하지 않는다.

| workload | 첫 UB까지 s | seed 전체 s | message build s | propagation s | solver 추정 peak MiB |
|---|---|---|---|---|---|
| LogReg | 0.6015 | 1.0873 | 1.3007 | 0.1198 | 10.40 |
| GLM | 1.6826 | 3.0914 | 0.7012 | 0.1951 | 14.58 |
| GNMF | 0.1265 | 0.2491 | 0.1511 | 0.0355 | 4.97 |

Bound query·reconstruction·selector·최종 validation의 나머지 비용도 전체 optimization에 포함된다. `clusterQueryNanos=0`은 별도 계측하지 않았다는 뜻이며 query overhead가 없다는 뜻이 아니다.

### 5.4 메시지와 seed를 분리한 실험

GNMF ablation은 각 설정 3회다. 메시지를 끈 경우에도 모든 대안을 유지한 complete-cover search를 수행하며, node 수 한도 때문에 성공을 선언하지 않는다.

| GNMF 설정 | opt 중앙값 s | search nodes | message cells | container MiB |
|---|---|---|---|---|
| messages-off-seed-off | 2.9879 | 3357 | 0 | 323.3 |
| messages-off-seed-on | 2.5350 | 3357 | 0 | 301.9 |
| messages-on-seed-off | 0.5112 | 0 | 2885 | 171.9 |
| messages-on-seed-on | 0.5074 | 0 | 2885 | 160.7 |

Messages off는 3,357개 node를 풀어 OPTIMAL, messages on은 0 search node로 0.0211624% gap을 인증했다. 이 비교에서는 메시지가 유리하다. Seed on/off의 작은 시간 차이는 실행 범위가 겹치고 UB 개선도 없어 개선 효과로 주장하지 않는다.

기본 typed 옵션에 맞춘 `localImproveSeed=false`도 세 workload에서 별도로 3회씩 측정했다. 첫 합법 seed는 여전히 만든다.

| workload | 동시 비교 EXACT opt s | 기본 Local opt s [최소, 최대] | Local fullcompile s | container MiB |
|---|---|---|---|---|
| LogReg | 0.7859 | 2.4355 [2.3589, 3.9580] | 5.7209 | 505.4 |
| GLM | 2.5390 | 3.5983 [2.9749, 4.1434] | 11.1472 | 1138.7 |
| GNMF | 0.1723 | 0.4420 [0.4382, 0.4870] | 1.7324 | 146.0 |

추가 seed 개선을 꺼도 최종 LB/UB와 message 수는 같다. 기본 설정도 이 세 workload에서 EXACT보다 느리므로, seed 비용 제거만으로 문제가 해결되지는 않는다.

이전 v3 Local의 direct exact만 끈 비교도 GNMF에서 3회씩 수행했다.

| 이전 Local 설정 | opt 중앙값 s | search nodes | container MiB |
|---|---|---|---|
| before-search-seed-false | 20.2062 | 37653 | 473.5 |
| before-search-seed-true | 21.5787 | 37653 | 477.0 |

두 설정 모두 37,653개 node, 최종 [207.3058446, 217.0473254]의 인증으로 종료했다. 신규 메시지 Local은 이 탐색 경로보다 크게 빠르다. 이것은 기존 direct exact 대비 이득과는 별개의 결과다. 모든 variant의 raw LB/UB와 반복별 수치는 `evaluation/report-data.csv`에 있다.

### 5.5 메모리 변화

추가 heap 실험은 각 cell 1회이며 통계적 속도 순위가 아니라 실행 가능성과 정책 독립성을 확인한다. 3,072 MiB 주 비교와 동일한 stronger-seed 설정이다.

| JVM heap MiB | workload | Local opt s | container peak MiB | pass | 결과 |
|---|---|---|---|---|---|
| 1024 | LogReg | 2.6705 | 391.4 | 1 | EPSILON_OPTIMAL / direct=0 |
| 1024 | GLM | 5.0367 | 680.2 | 1 | EPSILON_OPTIMAL / direct=0 |
| 1024 | GNMF | 0.6271 | 187.9 | 1 | EPSILON_OPTIMAL / direct=0 |
| 1536 | LogReg | 3.1435 | 454.1 | 1 | EPSILON_OPTIMAL / direct=0 |
| 1536 | GLM | 5.0077 | 842.0 | 1 | EPSILON_OPTIMAL / direct=0 |
| 1536 | GNMF | 0.5977 | 182.2 | 1 | EPSILON_OPTIMAL / direct=0 |

모두 같은 인증을 유지했고 메시지/필수 proof 메모리 admission 거절은 없었다. 따라서 이 범위에서 메모리가 Local 정책을 바꾸지 않음을 확인했다. 물리적 OOM 직전까지 sweep한 실험은 아니다. RESOURCE_EXHAUSTED 경로는 별도의 작은 자원·atomic publication unit fixture로 검증했다.

### 5.6 Synthetic, legacy 및 실제 federated 실행

Synthetic은 같은 JVM 내 평균 7회이며 큰 ML의 cold-JVM 중앙값과 직접 비교하지 않는다. 6개 graph × 기본 5개 algorithm과 작은 문제의 추가 search/seed variant를 합쳐 42개 row 모두 인증을 검증했다. 아래는 mean milliseconds다.

| case | EXACT | TREE_DP_GLOBAL | TREE_DP_LOCAL | MEMO_GLOBAL | MEMO_LOCAL |
|---|---|---|---|---|---|
| adversarial-shared-wide | 2.478 | 2.418 | 1.300 | 2.412 | 3.668 |
| tree-32 | 2.325 | 1.127 | 0.934 | 1.551 | 6.267 |
| cycle-16 | 0.854 | 0.264 | 0.279 | 1.295 | 3.316 |
| diamond-8 | 0.355 | 0.252 | 0.234 | 0.672 | 1.462 |
| shared-wide-8 | 0.589 | 0.337 | 0.346 | 0.791 | 1.300 |
| epsilon-boundary-3 | 0.159 | 0.152 | 0.080 | 0.402 | 0.196 |

`tree-32`에서도 신규 Local은 전용 Tree DP보다 느렸다. 작은 tree에 대한 효율적인 exact kernel의 장점이 있으며, certificate-first의 반복 query·witness·admission 비용이 ε 조기 종료 이득을 넘을 수 있다. 신규 Local의 tree-32 결과 [7.78125, 8.15625], 독립 OPT 7.96875는 5% 보장을 만족한다. cycle-16도 [3.78125, 3.96875], OPT 3.84375로 보장을 확인했다.

동일 factor로 변환한 legacy adapter 한 사례에서는 Memo EXACT, Tree, Memo Global/Local, ExactCategoricalSolver와 LocalCategoricalOptimizer가 모두 비용 **1.4375**로 일치했다. Regional local은 전체를 하나의 block으로 두어 동일 문제임을 확인한 fixture다(198 block assignments, 단일 측정 63.271 ms). 이것을 일반 legacy local/global의 대형 ML 속도 비교로 확대하지 않는다. Legacy와 Memo 전체 프로그램의 후보 공간·비용 의미가 동일하다고 증명하지 못해 그 ML 비용을 직접 비교하지 않았다. 기존 상세 비교는 [legacy/Memo/Tree 보고서](COFEE_LEGACY_MEMO_TREE_DP_COMPARISON_2026-10-15_KO.md)에 있다.

Docker suite의 JUnit **18개**가 통과했다. 이 중 **11개 method는 실제 federated worker를 사용**하고(Planner runtime 9, training 1, shared supply 1), 나머지 **7개는 multi-output compiler/binding 검증**이다. Training method 안에는 2-worker linear/logistic 학습 각각 3 step × 5 algorithm의 **10개 수치 비교**가 포함된다. 독립 산술 기대값과 비교했고, shared-supply method는 세 번의 non-inlined 함수 호출 반환·upload/reuse/cleanup을 검증했다. 큰 50,000-row training source 전체를 실제로 학습 완료시킨 것은 아니다. GNMF 전체 수치 runtime도 이번 범위에 포함하지 않았다.

최종 결과의 기준은 [독립 감사](../experiments/memo-local-certificate-first-20261015/evaluation/result-validation.json), [실험 완결성 감사](../experiments/memo-local-certificate-first-20261015/evaluation/receipt-validation.json), [전체 시간·메모리 표](../experiments/memo-local-certificate-first-20261015/evaluation/report-tables.md), [반복별 CSV](../experiments/memo-local-certificate-first-20261015/evaluation/report-data.csv)다.

## 6. 재현

모든 성능 실험은 `scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark`를 통해 수행한다. `run_evaluation.py`는 이 진입점만 호출한다. Source-fullcompile 실험과 실제 worker를 사용하는 작은 training runtime 검증은 별개다. 큰 LogReg/GLM/GNMF source의 compile 결과를 전체 대규모 학습 throughput으로 해석하지 않는다.

빌드·freeze·반복 실험·독립 감사 명령은 [실험 README](../experiments/memo-local-certificate-first-20261015/README.md)에 있다. 고정 image는 `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`, stage는 `/home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z`이다. main comparison은 worker 3, container 4 CPU/8 GiB, JVM heap 3,072 MiB, 새 JVM 7회이며 workload·algorithm·before/after 순서를 seed `20261015`로 섞었다. 전용 독점 host에서 수행한 실험은 아니므로 범위를 함께 보고하고 작은 시간 차이를 일반화하지 않는다.

v8 [manifest](../experiments/memo-local-certificate-first-20261015/engine-v8-sha256.json)의 8,903개 frozen file과 3,981개 Java source hash가 모두 일치한다. 비교 v3의 8,881개 file도 일치하며, baseline의 3,975개 Java source가 비교 기준 commit과 동일함을 별도로 확인했다. 신규 Java 6개는 baseline에 없는 것이 정상이다. [provenance 감사](../experiments/memo-local-certificate-first-20261015/provenance-validation.json)를 참조한다. Frozen binary 디렉터리는 Git에 넣지 않으며 stage/image도 이 변경에 포함되지 않는다.

공개 선택은 기존 `MEMO_LOCAL`이다. `sysds.fedplanner.memo.local.messages`의 기본값은 true다. `sysds.fedplanner.memo.local.improveSeed`를 명시하지 않으면 기존 typed `improveSeed` 옵션을 따른다(기본 false). Benchmark CLI는 stronger-seed 실험을 위해 `--local-improve-seed true`를 명시하므로 실제 기본값과 구분한다. `dual.clusterCells=4096`은 Local에서 다음 작은 message의 cell 상한이며, 큰 표를 일괄 생성하거나 종료하는 예산이 아니다. `directExact` 옵션은 Local의 root exact 호출을 활성화하지 않는다.

## 7. 적용 범위와 다음 개선

정책 분리는 구현했지만 모든 상황에서 최소 계산량을 선택하는 알고리즘을 증명한 것은 아니다. 기본 `EXACT`를 교체할 근거도 아직 없다. 큰 heap에서 Local이 몰래 전체 direct exact로 전환하는 경로는 제거했고, 5% 인증 직후 종료하는 계약은 유지한다. 순수 tree도 필요한 작은 message를 순차 계산하는 과정에서 exact 증명이 끝날 수 있다.

다음 성능 개선은 후보 삭제나 합법성 완화가 아니라 다음 순서로 진행하는 것이 타당하다.

1. **Seed 개선 선택을 실제 이득에 맞춘다.** 현재 최대 네 개의 작은 scope 선택은 비용 감소를 보장하지 않는다. 첫 합법 UB는 빠르게 확보하고, 추가 개선은 예상 이득과 비용이 뒷받침되는 경우에 선택한다. 현재 false인 production 기본값을 근거 없이 true로 바꾸지 않는다.
2. **인증에 영향을 주는 message를 우선한다.** 현재 small-width 순서는 table 크기에는 유리하지만 LB가 움직이지 않는 많은 bucket을 먼저 처리할 수 있다. singleton/constant owner의 빈 작업을 줄이고, 큰 primitive floor의 약점과 boundary 불일치를 반영하는 selector를 평가한다. 잘못된 conditioned optimum을 전역 LB로 올리지 않도록 기존 ownership/cover 불변식을 유지한다.
3. **Bound query와 witness 복원을 재사용한다.** 전체 residual factor의 반복 최소값 계산, 매 message 후 전체 witness 검증, class metadata 준비를 줄이는 것이 다음 대상이다. 재사용의 key에는 root domain과 factor 문맥을 포함해야 한다. certificate check 자체를 생략해 Local이 불필요한 exact 계산을 하도록 바꾸지 않는다.
4. **전체 support pass의 비용을 제한 가능한 작업으로 나눈다.** 지금은 최초 인증 기회 이후의 optional 전체 pass이며 query당 support 한도만 있다. 큰 graph에서 총 work quantum과 우선순위가 필요하다. UNKNOWN과 미처리 support는 제거 근거가 될 수 없다.

남은 검증 범위는 별도 최소-LB selector ablation, 깊은 message backtracking의 stack 한계, 임의 할당 실패 주입, 물리적 OOM 직전의 최대 heap 경계, 서로 다른 HOP이 동일 Lop으로 합쳐지는 경우다. JVM headroom admission이 임의 외부 evaluator OOM을 복구한다는 보장은 없다. 계획의 모든 강한 acceptance 조건을 완전히 검증했다고 주장하지 않으며, [coverage 표](../experiments/memo-local-certificate-first-20261015/correctness-coverage.md)의 Partial 항목을 후속 검증 대상으로 남긴다.
