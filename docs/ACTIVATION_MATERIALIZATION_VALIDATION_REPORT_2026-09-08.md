# Activation-aware Materialization 검증 및 Ablation 보고서

- 작성일: 2026-09-08
- 검증 환경: so009 (`130.149.237.19`), Java 17, Maven 3.8.7
- 대상: creation scope의 activation union을 사용하는 materialization 비용 모델과 Global DP의 Boolean OR factor 분해
- 검증 범위: 정적 컴파일, 비용 계산, 계획 선택, solver objective 및 factor 표현

## 1. 핵심 판단

**핵심 activation 의미론과 factor 분해는 수행한 테스트 범위에서 검증됐다. 전체 저장소 테스트 통과나 실제 실행 환경의 포괄적인 robustness가 입증된 상태는 아니다.**

기존 선별 테스트 95개가 통과했고, 후속 activation 전용 테스트 35개와 패키징도 통과했다. 독립 oracle에서는 4,096개 비교·경계 검사를 통과했다. 다만 추가 PRIVATE_AGGREGATE 회귀 검사에는 원본과 변경본에서 동일하게 발생하는 실패가 남아 있으며, 실제 materialization 생성 횟수·전송 bytes·Docker 실행시간은 측정하지 않았다.

Ablation에서는 비용 의미의 변경, 실제 선택 계획의 변경, 동일 objective에 대한 OR 분해 효과를 각각 확인했다. 이 결과만으로 실행시간 개선을 주장하지 않는다.

## 2. 테스트 범위와 결과

| 검증 항목 | 결과 | 해석 및 범위 |
| --- | --- | --- |
| 기존 선별 suite | 15개 클래스, 95개 테스트 통과 | 전체 저장소 suite가 아님. 일부 기존 fixture에 PUBLIC 등록이 포함됨 |
| 후속 activation 전용 suite | 6개 클래스, 35개 테스트 통과, 패키징 성공 | 기존 95개와 중복됨. 컴파일 fixture는 PRIVATE_AGGREGATE를 사용 |
| 독립 event oracle | 4,096개 비교·경계 검사 통과 | 아래 두 종류의 검사 횟수이며, 별도 JUnit 테스트 4,096개를 뜻하지 않음 |
| 추가 PRIVATE_AGGREGATE 회귀 | 원본·변경본 각각 8개 중 6개 통과, 2개 실패 | 동일한 실패가 양쪽에서 재현됨 |
| PRIVATE_AGGREGATE workload 비교 | 원본·변경본 모두 7개 중 6개 계획 생성 성공 | StepLM fixture는 양쪽에서 계획 생성 실패 |

95개와 35개를 합쳐 독립적인 테스트 130개가 통과했다고 계산하면 안 된다. 또한 합성 factor 테스트에는 source privacy 입력 자체가 없다.

후속 activation suite는 2026-09-08 00:05:49 CEST에 **35개 테스트, 실패 0, 오류 0, 건너뜀 0, `BUILD SUCCESS`**로 완료됐다. 독립 oracle은 저장소의 `ExactActivationIndependentOracleTest.java`에 회귀 테스트로 반영했다.

### 2.1 독립 oracle의 구성

| 검사 | 구성 | 결과 |
| --- | --- | --- |
| Structured activation union | 고정 seed로 생성한 branch tree 32개, tree당 concrete execution leaf 16개, demand 5개의 모든 선택 부분집합과 producer 상태 2개 | 2,048개 조건에서 독립 생성-event 집계와 canonical·solver 비용이 일치 |
| Unknown-overlap bound | 고정 seed로 생성한 concrete event set 64개, demand 5개의 모든 선택 부분집합 | 2,048개 조건에서 실제 union 및 최대 marginal 이상, creation-scope cap 이하임을 확인 |

유한한 작은 입력에서의 검증이므로 임의의 프로그램에 대한 완전한 정확성 증명이나 runtime 계측의 대체물로 해석하지 않는다.

### 2.2 남아 있는 실패

| 대상 | 관측 결과 | 원본과 변경본 비교 |
| --- | --- | --- |
| KMeans 크기 추정 회귀 | 기대 cardinality `800013.0`, 실제 추정 `0.0` | 동일하게 실패 |
| LM 계획 선택 회귀 | 기대 실행 유형 `CP`, 실제 선택 `FED` | 동일하게 실패 |
| StepLM PRIVATE_AGGREGATE fixture | `EXACT_VE_NO_FEASIBLE_ASSIGNMENT` | 동일하게 계획 생성 실패 |

앞의 두 회귀 실패는 이번 변경 이전에도 존재한다. 따라서 이번 변경이 새로 만든 실패라는 근거는 없지만, 해당 동작이 정상이라고 검증된 것도 아니다. StepLM 결과 역시 이 fixture의 PRIVATE_AGGREGATE 조건에서 실패한 것으로 기록하며, 전체 StepLM workload의 지원 여부로 일반화하지 않는다.

실패한 기대값을 완화하거나 합법적인 FED 후보를 제거하는 수정은 하지 않았다. Workload 보고용 Maven 실행도 StepLM 실패를 반영해 `BUILD FAILURE`를 반환했다.

## 3. Ablation A: materialization 비용 의미의 변경

기존 모델은 compatible demand의 frequency-weighted 비용 중 최대값을 사용한다. 새 모델은 하나의 creation lifetime에서 copy를 요구하는 activation들의 union에 비용을 부과한다.

공통 단위 비용을 갖는 resolved activation class에 대해서는 다음 objective를 인코딩한다.

\[
C_r(P) = c_r(P)\sum_k q_k\,\mathbf{1}\bigl[\exists j:\ j\text{가 class }g_k\text{에서 }r\text{을 요구함}\bigr].
\]

단위 생성 비용을 \(u\)라고 하면 핵심 차이는 다음과 같다.

| 조건 | 기존 모델 비용 | 새 모델 비용 |
| --- | ---: | ---: |
| 두 consumer가 항상 함께 활성화 | \(u\) | \(u\) |
| 배타적인 두 branch, 각각 확률 0.5 | \(0.5u\) | \(u\) |
| Loop-invariant copy를 3회 소비하는 검증 fixture | \(3u\) | \(u\) |
| 매 반복 갱신되는 copy를 3회 생성하는 검증 fixture | \(3u\) | \(3u\) |

실제 컴파일 fixture에서 동일한 producer·consumer alternative의 transfer contribution을 비교한 값은 다음과 같다.

| 대상 | 기존 모델 | 새 모델 |
| --- | ---: | ---: |
| Loop-invariant 값 | 3.002471923828125 | 1.000823974609375 |
| Loop-carried updated 값 | 3.002471923828125 | 3.002471923828125 |

이는 **정적 모델 비용**이다. 실제 실행시간이나 계측한 생성 횟수·bytes를 뜻하지 않는다. 변경은 배타 branch의 과소계상과 invariant copy의 반복 과대계상을 각각 다룬다.

## 4. Ablation B: 실제 선택 계획의 변경

계획 생성에 성공한 6개 PRIVATE_AGGREGATE workload에서 원본 decision key와 domain size를 대응시켰다. 다른 버전의 선택을 정확한 alternative signature로 복원한 뒤, 양쪽 비용식으로 교차 평가하고 hard constraint 만족 여부를 확인했다.

| Workload | 선택 변화 |
| --- | --- |
| KMeans, PCA, LM, LogReg, ALS | 선택 alternative와 최종 canonical objective bits가 동일 |
| L2SVM | Alternative 2개 변경. 그중 연산 상태 변경은 1개로, `tmp` 연산이 `FED/FOUT/ROW`에서 `CP/LOUT`으로 변경 |

L2SVM 전체가 CP 계획으로 바뀐 것은 아니다. 일부 연산과 입력 처리 alternative가 바뀌었다.

### 4.1 L2SVM 교차 평가

| 비용식 | 기존 선택 계획 | 새 선택 계획 |
| --- | ---: | ---: |
| 기존 max-demand 모델 | **2465.55** | 2541.88 |
| 새 activation 모델 | 2465.55 | **2383.18** |

각 비용식 아래에서 어느 계획이 선호되는지가 뒤집혔다. 서로 다른 모델의 최적 비용 두 개만 비교한 결과가 아니라, **두 고정 계획을 양쪽 비용식으로 평가한 결과**다. 두 계획은 교차 평가에서도 hard constraint를 만족했다.

주요 원인은 바깥 loop에서 생성되어 안쪽 loop에서 재사용되는 `Xd`의 materialization 비용이다. 같은 새 CP 선택을 평가할 때 이 contribution은 기존 모델의 **176.32293701171875**에서 새 모델의 **17.632293701171875**로 줄었다.

해당 연산을 CP에서 수행하면 modeled compute 비용이 100 줄고, 두 movement contribution의 변화는 서로 상쇄된다. 따라서 기존 모델에서 CP 선택의 순증 비용은 약 76.32였지만, 새 모델에서는 약 82.37의 절감으로 바뀐다. 이 결과는 계획 선택의 근거가 달라졌음을 보여주며, 실제 실행시간 개선 여부는 별도로 측정해야 한다.

## 5. Ablation C: 동일 objective의 OR factor 분해

새 activation objective를 고정하고, 직접 factor로 푸는 경우와 Boolean OR auxiliary로 분해하는 경우를 비교했다.

| Controlled fixture | Induced width: 직접 → OR | 최대 factor cells: 직접 → OR | Materialized cells: 직접 → OR |
| --- | --- | --- | --- |
| 항상 함께 활성화되는 demand 16개 | 16 → 2 | 131,072 → 8 | 262,177 → 257 |
| 두 배타 class에 속하는 demand 16개 | 8 → 2 | 512 → 8 | 2,079 → 255 |

두 표현은 동일한 유일 최적해와 raw-bit 수준에서 동일한 objective를 반환했다. 이는 새 objective의 OR 표현이 큰 직접 factor table을 피할 수 있음을 보여준다.

**기존 max factor도 이미 분해되어 있었다.** 따라서 위 표를 기존 버전 대비 실행 속도 향상으로 해석하지 않는다. 기존 모델과 새 모델의 비교는 비용 의미 변경을 포함하고, 이 표는 같은 새 objective의 표현 방식만 비교한다.

## 6. Robustness 판단과 남은 검증

현재 근거가 뒷받침하는 범위는 structured activation union, creation scope에 따른 비용 구분, 작은 유한 입력에서의 unknown-overlap bound, canonical·OR solver objective 일치, 그리고 수행한 fixture에서의 선택 변화다.

다음 항목은 검증 또는 해결이 남아 있다.

- **전체 suite:** 저장소 전체 테스트를 실행한 결과가 아니며, 추가 검사에서 재현된 기존 실패가 남아 있다.
- **Runtime 정확도:** 실제 materialization 생성 횟수와 전송 bytes를 계측하지 않았다.
- **Runtime 성능:** 동일 Docker 조건에서의 workload 실행시간 비교를 수행하지 않았다. 후속 성능 실험은 저장소 규칙에 따라 `run_LAN_docker.sh`를 사용해야 한다.
- **Unknown-overlap 확장성:** 정적으로 관계를 확정할 수 없는 union은 보수적인 capped-union factor로 남으며, 많은 변수를 포함할 수 있다. 큰 group에서 solver resource limit을 만족하는지는 별도 검증이 필요하다.
- **추정 오차:** branch·loop frequency는 정적 추정값이고, 불명확하거나 순환하는 source provenance는 비용을 과대계상할 수 있다.

Global의 exactness는 **인코딩한 유한 objective를 solver의 resource limit 내에서 완료했을 때의 최적화 정확성**에 한정된다. 알려지지 않은 joint activation 확률이나 측정 runtime에 대한 정확성을 뜻하지 않는다.

## 7. 검증 이력과 근거 파일

비교한 원본 source base는 `8c929e1370fa7934b3daefe45b7a59ae01896945`다. Activation 구현의 비교 대상은 local `72ddfffd92a78413681fa338a643ababd02caa1b`, so009 `a64f21748be4065c0dec4d352608dbb320116718`이다. 독립 oracle과 후속 검증 문서는 local `5152f072e21c4fe4b0a63c0b60e2a64488c84433`, so009 `2765bb89ee7e043a1f1ae9864ef98d5f13eab9c7`에 반영했다. 후속 audit에서 production 구현은 변경하지 않았다.

- [상세 세션 검증 기록](SESSION_ISSUES_2026-09-08.md): 실행 명령, 실패 내역, 정확한 비용값과 잔여 위험.
- [후속 검증 receipt](/home/mchoi/so009-activation-classes-validation-20260907/followup-verification-receipt.json): source·artifact hash, commit, 테스트 결과.
- [Paired ablation 요약 데이터](/home/mchoi/so009-activation-classes-validation-20260907/ablation/results/paired-summary.json): workload별 교차 비용과 선택 변화.
- [Activation 패키징 로그](/home/mchoi/so009-activation-classes-validation-20260907/followup-activation-package.log): 후속 35개 테스트 및 패키징 결과.

원본 로그와 TSV는 로컬 `/home/mchoi/so009-activation-classes-validation-20260907/ablation/results/`, so009 `/home/mchoi/so009-activation-ablation-results-20260907/`에 보관되어 있다.
