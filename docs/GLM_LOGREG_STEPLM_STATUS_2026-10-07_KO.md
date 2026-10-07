# GLM LogReg StepLM 현재 문제와 검증 상태

작성일: 2026년 10월 7일. 별도 작업 공간의 완료된 실험과 작업 기록을 기준으로 하며, 게시 전 origin/main `9668cb432f`의 LogReg 개선 및 StepLM 통합 결과를 반영했다.

**GLM은 공통 분석 시간과 factor 메모리 사용량, LogReg는 큰 사례의 분석 지연과 별도 Exact 용량 제한이 남아 있다. StepLM은 joint 제약표의 overflow와 후보 전파의 진동을 수정해 소형 실제 학습까지 통과했으며, 계획 시간과 추가 검증이 남아 있다.**

세 문제를 모두 학습 계산 오류로 묶으면 대응 방향을 잘못 잡게 된다. 공통 분석, 제약·비용 모델 구성, 계획 탐색, 실제 실행 중 어느 단계의 문제인지 구분해야 한다. 여기서 factor는 여러 후보 선택에 대해 합법성이나 비용을 평가하는 표 또는 그와 동등한 표현이다. W1과 W3는 각각 worker 1개와 3개 조건이다.

| 대상과 조건 | 확인한 문제 | 완료된 검증 | 남은 범위 |
|---|---|---|---|
| GLM W3 | 공통 분석과 계획 생성이 오래 걸림 | 최신 완료 수정본에서 계획 생성 성공, 전체 wall 약 798초 | 계획 시간 단축과 같은 최종 코드의 실제 학습 |
| GLM W1 | 수치 factor 처리 중 메모리 부족 | 약 962초 후 실패 원인과 실패 시 프로그램 미게시 확인 | 배열 보관·복사에 따른 메모리 사용량 해결, 계획 생성 완료 |
| LogReg 소형 | 공통 분석을 개선했으며 전체 컴파일은 약 25–28초 | 최종 3회 공통 분석 20초 미만, 실제 학습·계수·해시 검증 | 더 큰 입력의 검증과 전체 계획 시간 단축 |
| LogReg 대형 PRIVATE_AGGREGATE W1 | 공통 분석이 20분 제한 안에 끝나지 않음 | 중단 시점의 분석 stack 확인 | 분석 완료와 후속 계획·실행 검증 |
| LogReg 별도 PUBLIC 사례 | Exact 중간 제약표의 논리적 크기가 제한 초과 | 423,588,286 cells 대 10,000,000 cells 제한으로 실패 확인 | 해당 제약표·메시지 표현과 처리 경로 개선 |
| StepLM 소형 20×5 | joint overflow와 후보 전파 진동을 수정함 | main 통합 검증 완료, 계수 5개와 변수 선택 순서가 CP와 일치 | 통합 검증에서 약 105초 컴파일, 최적성 gap, 원격 CSV 검증 |

**작업 기준과 결과의 범위**

최초 작성 시 원격 origin/main은 `56d2ac628e`였고, 게시 전에는 `9668cb432f051d0313c5668719b9adadb0c60314`까지 전진했다. LogReg 공통 분석 최적화 `d26bb59610`과 StepLM 수정·통합이 포함됐다. 아래 실험의 원래 기준은 그대로 유지한다.

| 대상 | 작업 공간 또는 근거 | 기준 |
|---|---|---|
| GLM | `/home/mchoi/w1357-cost-model-main-20261006` | HEAD `93706bbaa9`와 해당 작업의 로컬 변경. 최신 완료 결과는 동결 수정본 v39 |
| LogReg 분석·해시 | `/home/mchoi/w1357-main-revalidation-r4-20261007` | HEAD `56d2ac628e`. 대형 W1 실험 자체는 앞선 `fbfd4d790f`와 alias 수정 overlay 기준 |
| LogReg PUBLIC 용량 오류 | `/home/mchoi/w1357-derived-supply-main-20261007`의 L2SVM 통합 회귀 기록 | `7f35faa05d` 통합 회귀에서 확인. 최신 main 전체 재실행 결과로 전용하지 않음 |
| StepLM | `/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-joint-compact-20261007` | 원래 `2faff2a2a5` 기준 수정. 이후 `879383b42c`로 커밋하고 `9668cb432f`까지 main 통합·게시 검증 완료 |

이전 답변의 “StepLM에 도달하지 못했다”는 L2SVM 다음 LogReg에서 멈춘 특정 캠페인의 범위였다. 별도 StepLM 작업 공간에서는 실제 학습까지 검증했다. 서로 다른 캠페인의 결과를 구분한다.

**1. GLM은 분석 반복과 수치 표의 메모리 사용량이 문제다**

최신 완료된 v39 진단 실행에서 W3는 계획 생성에 성공했다. 전체 프로세스 wall은 798.244초이고, 내부 CandidateE2E Total은 795.592초다. 두 수치는 측정 범위가 다르다.

| W3 내부 단계 | 시간 |
|---|---:|
| 공통 분석 | 482.446초 |
| 물리 모델 구성 | 21.088초 |
| 비용 표 구성 | 125.965초 |
| Optimizer | 150.232초 |
| 선택 결과 처리 | 13.837초 |

W1은 961.916초 후 exact-numeric 단계에서 실패했다. 원본에는 `java.lang.OutOfMemoryError: Java heap space`가 있으며, 실패한 요청은 double 7,208,343개에 해당하는 57,666,744-byte 배열이다. JVM 최대 heap은 10 GiB였다. 이 배열의 크기는 실패 직전 추가 할당 요청이며, 전체 메모리 사용량을 뜻하지 않는다. 실패 후 게시된 프로그램은 0개다. W1과 W3 모두 실제 학습 runtime은 실행하지 않았다. [v39 원본 결과](/grid/3/cofee-lm-sweep-mchoi-20260914/boundary-correctness-20261006/merged-v39-glm-final-summary.json)

느린 분석에서는 불변 후보와 배치 증명을 반복 비교하고, 정규화된 문자열을 만들고 비교하며, 동일한 공급 관계의 고정점을 다시 계산하는 작업이 관측됐다. 따라서 입력 데이터의 수치 계산을 빠르게 하는 것만으로는 이 시간을 줄일 수 없다. 후보·증명 정보의 표현과 갱신 방식이 대상이다.

비용 표 구성에도 별도 문제가 있었다. v39에서는 fingerprint의 UTF-8 처리를 문자별로 수행하면서 큰 문자열에 대한 JVM 일괄 인코딩의 이점을 잃었다. 비용 표 구간은 v38의 71.094초에서 v39의 125.965초로 길어졌고 관련 profile도 확인됐다. 현재 작업에서는 비교 context와 분석 결과 재사용, 일괄 UTF-8 인코딩 복원, hard 제약표의 압축 보관을 검증 중이다. 이들 개선의 최종 통합 성공과 W1 완료를 이미 달성한 것으로 집계하지 않는다. [최신 작업 기록](/home/mchoi/w1357-cost-model-main-20261006/docs/SESSION_ISSUES_2026-10-07.md:252)

앞선 답변의 W3 약 785초와 W1 약 995초는 v38 결과다. 문서 작성 중 완료된 v39 결과를 위에 반영했다. 두 수정본의 실행은 모두 중간에 JFR을 붙인 진단 실행이므로 정밀한 성능 개선률이나 20초 목표 달성 근거로 사용할 수 없다. 현재 완료 조건은 W1의 계획 생성 성공, W1/W3의 계획 시간 목표 검증, 그리고 같은 최종 코드의 실제 학습이다.

**2. LogReg의 대형 분석 지연과 PUBLIC 용량 오류는 별개다**

소형 multiLogReg는 실제 학습을 완료했고 CP와 FED의 전체 16계수가 최대 절대 오차 2.22e-16 이내로 일치했다. Runtime audit와 conversion 위반도 없었다. 소형 성공을 큰 입력의 성공으로 확대할 수는 없다.

게시 전 추가된 공통 분석 최적화의 최종 3회는 분석 16.714 / 18.749 / 17.716초, 전체 컴파일 25.224 / 28.084 / 26.385초였다. 192×8, PRIVATE_AGGREGATE X, local labels, worker 3개의 고정된 소형 조건에서 공통 분석 20초 미만 목표를 통과한 결과다. 아래 대형 W1 완료를 증명하는 결과는 아니다. [최신 소형 개선 결과](/home/mchoi/w1357-derived-supply-main-20261007/docs/LOGREG_COMMON_OPTIMIZATION_2026-10-07_KO.md)

대형 W1 검증은 50,000×2,100 입력, PRIVATE_AGGREGATE X/Y, worker 1개, Docker 4 CPU·16 GiB 및 JVM 10 GiB 조건이었다. 1,200초 제한까지 공통 분석이 끝나지 않았고 optimizer 시작 기록도 없었다. 마지막 stack은 후보의 증명 절을 병합하고 direct-native realization을 구성하는 경로였다. 이 실행에서는 OOM이나 factor overflow가 관측되지 않았다. 따라서 이것은 제한 시간 안에 분석을 완료하지 못한 결과이며, 합법적인 계획이 없다는 판정은 아니다. [대형 W1 기록](/home/mchoi/w1357-main-revalidation-r4-20261007/docs/ALIAS_REACHABILITY_VALIDATION_2026-10-07.md:45)

Alias 탐색 중복을 줄이는 수정은 작은 workload에서 실제 root 탐색을 1,506회에서 14회로 줄였다. 그러나 대형 W1은 그 수정 경로에 도달하기 전의 공통 분석에서 시간이 소진됐다. 탐색 횟수 감소만으로 대형 W1의 전체 문제가 해결됐다고 볼 수 없다.

별도 PUBLIC 모델 회귀에서는 공통 분석 이후 Exact 합법성 처리 중 중간 separator의 논리적 크기가 423,588,286 cells로 계산되어 10,000,000 cells 제한을 넘었다. 원본 오류의 세 후보 축 크기는 11,638, 2,141, 17이며, 곱은 다음과 같다.

`11,638 × 2,141 × 17 = 423,588,286`

이 숫자는 해당 조합 표를 전체로 표현할 때의 칸 수다. 실제로 그만큼 메모리를 할당했다는 뜻도, 그 모든 조합이 합법이라는 뜻도 아니다. 큰 논리적 곱을 요구하는 표현과 solver 경로를 조사해야 한다. StepLM의 작은 factor 분해나 L2SVM의 sparse 처리가 그대로 적용되는지는 별도 검증이 필요하다. [용량 오류 검증 기록](/home/mchoi/w1357-derived-supply-main-20261007/docs/L2SVM_EXACT_CAPACITY_2026-10-07_KO.md:105)

한편 같은 실행의 costFingerprint와 planHash가 달라지던 문제는 해결됐다. 객체 identity 기반 map의 순회 순서가 joint 비용 factor의 생성 순서를 바꾸던 것이 원인이었고, 기존 모델의 고정된 domain 순서로 생성하도록 수정했다. 이 변경은 현재 main에 포함됐지만 대형 분석 지연이나 factor 용량을 해결하는 변경은 아니다. [해시 수정과 검증](/home/mchoi/w1357-main-revalidation-r4-20261007/docs/ALIAS_REACHABILITY_VALIDATION_2026-10-07.md:61)

LogReg의 후속 검증은 대형 PRIVATE_AGGREGATE W1의 분석 완료와 PUBLIC Exact 용량 오류를 각각 고정된 조건으로 확인해야 한다. 소형 학습 성공이나 해시 수정 성공으로 두 항목을 대체하지 않는다.

**3. StepLM은 구조 오류를 넘어 소형 학습까지 성공했다**

StepLM에는 서로 다른 두 원인이 있었다. 첫째는 joint 입력의 물리 정렬을 검사하는 표가 지나치게 커지는 문제다. `lmCG.dml:129`의 실제 공동 입력 관계는 10개 행이지만, 재귀적으로 연결된 공급자의 realization과 clause 선택까지 하나의 scope에 모으면 21개 축이 된다. 기존 표현의 논리적 곱은 약 1.36×10²⁴ cells였고, 테이블 생성 전 크기 검사에서 실패했다.

수정본은 각 공급 경로가 증명하는 배치와 최종 입력들의 정렬 조건을 작은 factor들로 나눈다. 실제로 도달 가능한 입력 행의 관계를 유지하고, 공급자가 서로를 근거로 삼는 순환 증명을 막는 조건도 둔다. 검사 결과가 항상 참인 joint는 조기에 생략한다.

| 같은 소형 분석의 항목 | 기존 표현 | 작은 factor 표현 |
|---|---:|---:|
| 원래 결정 변수 | 556 | 556 |
| 전체 encoded hard factors | 1,742 | 2,072 |
| 최대 hard factor scope | 21 | 3 |
| 남은 joint의 encoded cells 합 | long 범위 초과 | 46,832 |
| 전체 encoded hard table cells 합 | 약 1.36×10²⁴ | 625,066 |

이 비교의 기존 표현은 항상 참인 joint 2개를 먼저 생략한 reference다. 보조 변수와 factor 개수는 늘지만, 모든 공급 결정을 한 번에 곱하는 거대한 표를 없앴다. 표의 입력 크기를 줄인 것이며, 이후 모든 optimizer 메시지가 같은 크기 안에 든다는 보장은 아니다. Canonical 합법성 비교와 작은 공간의 전수 검증을 유지한다. [인코딩과 동치 검증](/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-joint-compact-20261007/docs/STEPLM_JOINT_COMPACT_ENCODING_2026-10-07.md:9)

둘째는 공급 정보의 갱신 순서 문제다. 소비자가 생산자의 이전 snapshot을 보고 DIRECT 지원을 지운 다음, 후속 단계가 생산자의 이동 결과를 복원하면서 후보가 주기적으로 사라졌다가 되살아났다. 최종 수정은 기존 CFG 고정점 안에서 action binding과 direct 전파를 함께 처리한다. 생산자에서 소비자 방향으로 처리하되 순환 component 내부는 같은 snapshot을 읽고 동시에 갱신한다. 이전 loop seed 비수렴 수정과 이 후속 publication 문제를 구분해야 한다. [갱신 순서의 원인과 수정](/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-joint-compact-20261007/docs/STEPLM_JOINT_COMPACT_ENCODING_2026-10-07.md:93)

통합 전 소형 Docker 실행은 joint 사례 6개, 작은 ML 3개, StepLM 1개를 합쳐 10/10 통과했다. StepLM은 DML로 생성한 full-rank 20×5 입력을 사용했다.

- CP와 FED의 계수 5개가 모두 정확히 같고 최대 절대 차이는 0이다.
- 변수 선택 순서는 양쪽 모두 `[3,1,5]`다.
- Runtime audit 위반은 0이다.
- 전체 컴파일은 120.694863초, 실제 실행은 0.903초다.

[실제 실행 결과](/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-joint-compact-validation-20261007/steplm-joint-compact-03/result.json:335)

위 실행의 계획 탐색 자체는 약 97.003초였다. 기존 RESOURCE 종료 조건에서 합법성을 검증한 계획을 반환했고, upper cost는 37,040.115994 ms, lower bound는 19,529.714827 ms다. 로그의 상대 gap은 약 89.66%로, 전역 최적성까지 증명한 결과는 아니다. 이 수치는 비용 모델의 상하한 차이이며 실제 학습 시간이 그 비율만큼 느리다는 뜻은 아니다. [통합 전 검증과 성능 한계](/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-joint-compact-20261007/docs/STEPLM_JOINT_COMPACT_ENCODING_2026-10-07.md:99)

이후 최신 main 변경을 통합한 게시 검증도 Java 209건, Python 35건, 실제 Docker 10건을 통과했다. 이때 StepLM 컴파일은 104.571702초, 실행은 0.731초, 계획 탐색은 약 88.611초였다. 계수와 선택 순서는 여전히 CP와 일치하고 audit 위반은 0이며, upper/lower와 상대 gap도 같다. 서로 다른 단일 검증 실행의 시간 차이를 통제된 성능 개선률로 해석하지 않는다. 수정과 통합은 origin/main `9668cb432f`에 게시됐다. [게시 통합 검증](/home/mchoi/w1357-derived-supply-main-20261007/docs/experiments/steplm-joint-compact-20261007/publication-validation.json)

원격 CSV StepLM은 최종 수정 후 다시 실행하지 않았다. 대형 입력과 전역 Exact 최적화도 이번 완료 범위에 포함되지 않는다. 통합 검증 완료와 이후의 계획 시간·품질 개선을 구분한다.

**후속 작업의 구분**

| 대상 | 필요한 작업 | 완료를 판단할 근거 |
|---|---|---|
| GLM | 분석·비용 계산 중복 제거와 dense factor 메모리 문제 해결 | 같은 최종 소스에서 W1/W3 계획 생성 성공, 고정 조건의 시간 측정, 실제 학습 검증 |
| LogReg 대형 W1 | 공통 분석의 반복 병합·비교 병목 해소 | 기존 입력·privacy·worker 조건에서 분석과 후속 계획 생성 완료 |
| LogReg PUBLIC | 중간 factor의 큰 논리적 곱을 처리하는 표현 개선 | 해당 오류 재현의 통과와 canonical 합법성·비용 일치 |
| StepLM | 원격 CSV 확인, 계획 탐색 시간과 품질 개선 | 게시본의 성공 회귀 유지, CSV 실제 실행, 시간과 상하한 gap 측정 |

각 개선은 실제 지원되는 후보와 입력 상관관계를 보존해야 한다. 비용이 큰 것과 제약표의 표현이 큰 것은 구분하며, 후보 삭제나 privacy·TW/TR 제약 완화로 용량 문제를 숨기지 않는다. 이번 결과들만으로 J_v 자체를 제거해야 한다는 결론은 나오지 않는다. StepLM의 검증은 상관관계를 유지하면서 제약을 더 작은 factor로 표현할 수 있음을 보여준다.
