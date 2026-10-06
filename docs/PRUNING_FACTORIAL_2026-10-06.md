# Local을 고정한 dominance/support 개별 효과

## 최종 반영

**기본값은 `LOCAL_ONLY`다. local boundary prefix pruning을 유지하고, 추가 dominance와
n-ary support는 후속 사용자 요청에 따라 구현과 실험 옵션을 모두 삭제했다. GLOBAL도 제거했다.**

| 기법 | 반영 | 판단 근거 |
|---|---|---|
| Local | 기본 유지 | 사용자 지정 고정 조건; 기존 exact boundary 검증 유지 |
| Dominance | 구현·실험 선택지 제거 | 같은 cohort의 DSL/SL에서 optimizer +9.05%, ML9 9/9개 증가 |
| 추가 n-ary support | 구현·실험 선택지 제거 | 후보는 줄지만 일관된 시간 이득 미확인; 최종 SL/L optimizer −0.42% |
| Global | 구현·실험 선택지 제거 | 이전 실험 결과를 재사용하고 사용자 결정 반영 |

추가 전처리의 순이득이 입증되지 않은 상태에서 기본 구성을 단순화한 결정이다. 모든 workload에서
L이 가장 빠르다는 뜻은 아니다. 기존 equality quotient, unary/binary support와 privacy/runtime
legality는 계속 적용한다. property를 지정하지 않은 실행이 L이다. 현재 실행기는 `baseline`과
`local_only`만 받는다. 과거 DSL을 뜻하던 `local`과 D/S/global 조합은 잘못된 설정으로 거부하며,
다른 의미로 재해석하지 않는다. 아래 네 조합의 기록은 삭제 전 동결 자료로 보존한다.

## 완료된 4조합 비교

기존 L/DSL 60개를 재사용하고 DL/SL 60개와 같은 시점 DSL 대조군 30개를 추가했다.
총 150개 선택 결과에서 목적함수 bits·선택 플랜·종료 이유가 모두 동일했다.
새 측정은 90/90 성공했으며 재시도하거나 유리한 결과만 골라 넣지 않았다.

아래는 ML9의 workload·반복 번호가 대응하는 27개 시간비의 기하평균이다.
음수는 시간 감소, 양수는 증가다. 다른 시점 비교는 원시 관측값이며 인과 효과로 해석하지 않는다.

| 비교 | 전체 초기 planning | Optimizer | 비교 범위 |
|---|---:|---:|---|
| L | 기준 | 기준 | 재사용 |
| DL / L | −1.59% | +7.24% | 새 DL / 과거 L |
| SL / L | +0.17% | −0.53% | 새 SL / 과거 L |
| DSL / L | −2.67% | +0.16% | 같은 과거 cohort, 재사용 |
| DSL / SL: support 위에 dominance 추가 | +0.91% | +9.05% | 같은 새 cohort |
| DSL / DL: dominance 위에 support 추가 | +2.72% | +1.15% | 같은 새 cohort |
| 새 DSL / 과거 DSL | +3.85% | +8.30% | 같은 설정의 시점 차이 |

dominance는 support가 켜진 상태에서 ML9 **9/9개의 optimizer 시간을 늘렸다**.
추가 축약은 logreg의 값 8개·factor cell 472개뿐이며 그 workload에서도 optimizer 시간이
8.04% 늘었다. 따라서 이번 ML9 기준의 기본값에서는 dominance를 끈다.
전체 시간의 작은 차이만으로 dominance가 항상 느리다고 주장하는 것은 아니다.

support의 추가 축약은 다음과 같다. 후보 수 감소 자체를 latency 개선과 동일시하지 않는다.

| Workload | SL이 L보다 제거한 값 | 제거한 factor cell |
|---|---:|---:|
| logreg | 21 | 887 |
| l2svm | 22 | 1,572 |
| als | 1 | 10 |
| glm | 7 | 276 |
| gnmf | 4 | 56 |
| pca, kmeans, lm, gmm | 0 | 0 |

두 수준의 balanced main effect는 D가 전체 −2.21%·optimizer +3.91%, S가 전체 −0.47%·
optimizer −3.61%다. 이는 아래에 명시한 공통 cohort 배율 가정에 의존하며, D를 끈 상태에서
support만 켜는 결정의 직접 근거로 대체할 수 없다. SL/L의 시점 혼동을 줄이기 위해
global 제거 후 같은 조건으로 **L/SL × 10 workload × 1회, 20개**를 추가 비교했다.
이 작은 비교는 전체 실험을 다시 수행하지 않고 기본값 선택과 제거 후 compile 검증을 겸한다.

STEP 호환군은 별도다. 같은 새 cohort의 D 추가 효과는 전체 −13.86%·optimizer −11.23%로
ML9과 반대였고, S 추가 효과는 전체 −2.60%·optimizer +4.25%였다. 이를 숨기거나 ML9의
기본값 판단을 모든 workload에 일반화하지 않는다.

GNMF의 support 관련 비교에서 lower bound만 1 ULP 차이가 났다
(`10947.472674026232` 대 `10947.47267402623`). 선택 비용과 upper는 동일하다.
GLM의 DL 3회차 전체 planning 105.4초 표본은 analysis phase가 67.28초로 늘어난 관측값이며,
오류로 제거하지 않았다. 전체·optimizer·analysis의 원시 값과 최소/중앙/최대는
[표](pruning-factorial-20261006/tables.md), [CSV](pruning-factorial-20261006/stats.csv),
[전체 분석](pruning-factorial-20261006/analysis.json)에 보존한다.

## 최종 L/SL 직접 비교

global 제거 후 새 JVM을 사용한 20/20개의 정규 compile/lowering audit가 통과했다.
모든 결과의 비용 bits·플랜 fingerprint·종료 이유는 앞선 150개와 같은 workload에서 일치한다.
GNMF의 support 관련 lower bound 1 ULP 차이는 그대로이며, 비용 악화는 없다.

| SL / L | 전체 초기 planning | Optimizer | Analysis |
|---|---:|---:|---:|
| ML9 기하평균 | +6.28% | −0.42% | +8.54% |
| 시간 감소 workload 수 | 3/9 | 4/9 | 3/9 |
| STEP 호환군, 별도 | +3.53% | +5.84% | +6.19% |

| Workload | 전체 planning 변화 | Optimizer 변화 |
|---|---:|---:|
| logreg | +2.86% | −1.90% |
| l2svm | +6.29% | +16.96% |
| pca | −9.58% | −26.64% |
| als | +9.24% | −9.24% |
| kmeans | −7.71% | +8.87% |
| lm | +17.03% | +7.42% |
| glm | +25.04% | +6.40% |
| gnmf | +46.41% | +17.38% |
| gmm | −19.01% | −13.68% |

같은 시점·환경으로 맞춰도 한 번씩 측정한 cold-JVM/shared-host 표본에는 큰 변동이 있다.
analysis phase도 8.54% 늘었으므로 전체 +6.28%를 support 전처리의 순수 overhead라고
해석하지 않는다. 앞선 SL/L 원시 비교의 optimizer −0.53%, balanced S main −3.61%,
이번 −0.42%를 함께 보면 후보 감소가 일관된 latency 이득으로 이어졌다는 근거가 부족하다.
따라서 추가 support는 기본값에서 제외한다. 통계적 유의성이나 실제 training 시간 개선을
주장하지 않으며, 동일 조건 반복을 유리한 수치가 나올 때까지 계속하지 않았다.

합계는 **기존 60개 재사용 + 신규 110개 = 선택 결과 170개**다. 원시 cohort에 포함된 과거
baseline 30개까지 200개를 검증했다. [bridge 분석](pruning-factorial-20261006/bridge-analysis.json)과
[workload별 원시 시간/비용 비교](pruning-factorial-20261006/bridge-pairs.csv)에 수치를 보존한다.

## 질문과 비교

global pruning은 폐기하고 local boundary prefix pruning은 유지한다. 추가 dominance와
n-ary support 중 실제로 도움이 되는 기법을 기본값에 반영하기 위해 다음 네 조합을 비교한다.

| 이름 | 실험 property | Dominance | 추가 n-ary support | Local prefix |
|---|---|---:|---:|---:|
| L | `local_only` | 꺼짐 | 꺼짐 | 켜짐 |
| DL | `dominance_local` | 켜짐 | 꺼짐 | 켜짐 |
| SL | `support_local` | 꺼짐 | 켜짐 | 켜짐 |
| DSL | `local` | 켜짐 | 켜짐 | 켜짐 |

기존 `support` 실험 이름은 D+S이며 S 단독이 아니다. 이전 누적 ablation의 이름과 결과를
새 조합으로 재해석하지 않는다. 기존 equality quotient, unary/binary support, privacy 및
runtime legality는 모든 조합에서 유지한다. 표의 support는 추가 root n-ary 전처리를 뜻한다.

## 기존 결과 재사용과 대조군

[앞선 비교](PRUNING_DEFAULTS_2026-10-06.md)의 `local-only-study-v1`에서 L 30개와 DSL 30개를
그대로 재사용한다. 각 조합은 ML9와 별도 STEP 호환군, 3회 반복이다. 당시 baseline 30개도
파일 검증에는 포함하지만 이번 네 조합의 효과 집계에는 넣지 않는다.

새 `factorial-study-v1`에서는 빠진 DL/SL 60개를 측정한다. 과거와 현재의 호스트 상태 차이가
기법 효과로 섞이는 것을 확인하기 위해 DSL 30개를 같은 회전 block의 대조군으로 포함한다.
새 측정 90개와 재사용 60개로 총 150개 결과를 분석한다. 실패·누락·중복을 제외하고 유리한
성공만 고르는 집계는 허용하지 않는다.

ML9는 logreg, l2svm, pca, als, kmeans, lm, glm, gnmf, gmm이다. STEP-LM은 `maxi=20`,
`max_features`가 없는 기존 호환 정의이며 별도 결과로 제시한다. 정의가 다른 원래 STEP-LM
실패를 성공으로 대체하거나 ML9 평균에 합치지 않는다.

## 측정 조건과 해석

X=50,000×128 ROW PrivateAggregation, Y가 있으면 50,000×1 Public인 기존 sealed stage를
사용한다. 모델상 W3 WAN-Mid의 고정 비용 profile이다. 실제 worker나 학습 코드는 실행하지
않고 정규 HOP planning, LOP lowering, runtime-program construction까지 컴파일한다.

진입점은 `scripts/fedplanner/run_LAN_docker.sh --pruning-ablation`이다. 같은 content-addressed
image, CPU 8–15, JVM 16 GiB·8 processors, container 24 GiB, network none을 사용한다.
한 번에 새 JVM 하나를 실행하며, workload별 세 variant 순서를 반복마다 순환한다.
timeMillis=0, gap=0.05, merge assignments=1,000,000, retained slots=8,000,000,
scored candidates=16, earlyStop=true를 유지한다.

새 enum 조합 두 개와 이미 알려진 implicit 기본값 복구 외에, 과거와 새 측정 사이의
production source 차이는 없다. 차이가 있는 파일은 `PruningAblation.java` 하나다.
image, probe, dependency, CPU/JVM, 비용 환경, renderer, stage와 rendered DML을 검증한다.
global 코드는 이 90회 실험에서 비활성 상태였으며, 측정 완료 후 별도로 제거했다.

기본 성능 지표는 전체 초기 플래닝 시간이다. optimizer 시간과 domain/cell/비용 조회 수를
함께 보고 어떤 계산이 줄었는지 확인한다. 선택 플랜의 유효성, 선택 비용, 종료 이유와 하한을
먼저 비교하고 비용 악화를 단순한 시간 이득으로 숨기지 않는다.
각 workload 3회의 최소·중앙·최대와 같은 workload/반복 번호에 대응시킨 시간비의 기하평균을
제시한다. 공유 호스트의 cold-JVM 측정이므로 작은 차이에 유의성을 주장하지 않는다.

## 개별 효과의 정의

같은 새 cohort에서 직접 비교하는 두 값은 현재 DSL에서 무엇을 뺄지 판단하는 근거다.

- `D_given_S = new DSL / new SL`: support가 켜져 있을 때 dominance 추가 효과.
- `S_given_D = new DSL / new DL`: dominance가 켜져 있을 때 support 추가 효과.

1보다 작으면 해당 기법을 추가했을 때 시간이 줄고, 1보다 크면 늘어난다.
`new DL / old L`, `new SL / old L`도 제시하지만 서로 다른 시점의 비교로 표시한다.
`new DSL / old DSL`은 동일 설정의 cohort 간 시간 변화를 보여준다.

두 수준을 균등하게 합친 log-scale main effect는 다음 식이다.

```text
D main = sqrt((new DL / old L) * (old DSL / new SL))
S main = sqrt((new SL / old L) * (old DSL / new DL))
```

같은 workload/반복에서 새 cohort의 시간에 공통 배율이 곱해졌다고 가정하면 그 배율은 식에서
상쇄된다. 이 가정이 모든 workload와 variant에 완벽하게 성립한다고 주장하지 않는다.
`old DSL * old L / (new DL * new SL)`인 raw interaction은 시점 효과와 섞이므로 순수한
기법 간 interaction으로 해석하지 않는다. 두 기법의 개별 효용을 묶어서 단정하지 않는다.

## 재현

아래 Docker 명령은 측정 당시 사용한 명령이다. 보존된 root의 manifest는 당시 binary/source에
고정되어 있으므로 현재 최종 binary로 덮어 재실행하지 않는다. 새 실험에는 새 root를 사용하고,
동일 binary의 재현에는 원시 root의 frozen source/overlay와 기록된 환경을 사용한다.
분석 명령은 보존된 원시 결과를 그대로 재검증한다.

```bash
bash scripts/fedplanner/run_LAN_docker.sh --pruning-ablation \
  --root /home/mchoi/w1357-pruning-ablation-20261006/factorial-study-v1 \
  --cost-profile /home/mchoi/w1357-pruning-ablation-20261006/study-v1/cost-profile.json \
  --stage /home/mchoi/w1357-stage-privacy-test-only-e21cac2-07726cd \
  --evaluation-root /grid/3/cofee-lm-sweep-mchoi-20260914/pruning-ablation-20261006/evaluation-snapshot \
  --renderer-file /grid/3/cofee-lm-sweep-mchoi-20260914/pruning-ablation-20261006/evaluation-snapshot/legacy/render_w1357_workload.py \
  --variants dominance_local support_local local --repetitions 3

python3 docs/pruning-factorial-20261006/analyze.py \
  --reused-root /home/mchoi/w1357-pruning-ablation-20261006/local-only-study-v1 \
  --new-root /home/mchoi/w1357-pruning-ablation-20261006/factorial-study-v1 \
  --output docs/pruning-factorial-20261006
```

측정 JAR SHA-256은 `21c9f59473ff9fd11a09e438eab665241e1fa7a720346551887d2cc68839f2af`이다.
manifest, 원시 receipt/명령/로그, frozen-harness, frozen-pruning-source 및 cohort-lineage를
실험 root에 보존한다. 측정 전에 Java 대상 83건과 package가 통과했다. 분석기는 합성 회귀에서
cohort shift를 기법 효과로 오인하지 않는 계산과 비용 악화의 방향·크기를 검증한다.

최종 bridge 실행은 위 Docker 명령에서 `--root`를 `support-bridge-v1`로 바꾸고
`--variants local_only support_local --repetitions 1`을 사용했다. 그 측정 JAR SHA-256은
`15fab31bdf9ed80279b8735a679ad5db511149ff5bd31ca590fc5fbe3c579ff9`이다.
GLOBAL 제거 3개 production 파일 외에는 직전 측정 source와 동일하다.

```bash
python3 docs/pruning-factorial-20261006/analyze_bridge.py \
  --root /home/mchoi/w1357-pruning-ablation-20261006/support-bridge-v1 \
  --factorial-root /home/mchoi/w1357-pruning-ablation-20261006/factorial-study-v1 \
  --output docs/pruning-factorial-20261006
```

bridge 분석기는 frozen runner/matrix/overlay/JAR, 정확한 20개 sample matrix, log/probe/pruning
receipt, Docker 명령/image/CPU/JVM/memory/network/비용 환경, 동일 input 및 종료·비중첩을
검증한다. 이전 90개 cohort를 먼저 다시 검증한 뒤 비용/플랜 연속성을 비교한다.

## 4조합 측정 완료 시점의 구현과 검증 — 삭제 전 기록

- `PruningAblation.java`: implicit 기본값 L, D/S의 네 factorial gate, GLOBAL 선택지 제거.
- `RegionalSearchProblem.java`: global 준비·marginal·초기 상한 domain restriction 전용 코드 삭제.
- `LocalPhysicalOptimizer.java`: global 호출과 전용 계측 6필드 삭제. 공용 seed/비용 검증 보존.
- `PruningAblationTest.java`: 기본 L과 explicit L의 domain/boundary 값·하한·복원 assignment 동치,
  DL/SL 독립 gate, 제거된 global의 명시적 거부 검증.
- `run_pruning_ablation.py` 및 테스트: 기본 실험 행렬 L/DL/SL/DSL, global 제거,
  baseline 없는 비교에서도 선언된 reference를 사용한 플랜/비용 비교.

GLOBAL 제거 때 `mvn clean test`로 삭제된 내부 class를 청소했다. 이후 최종 L 기본값으로
대상 Java **80건**, package 확인 **10건**, Python 실행/분석 **19건**이 통과했다.
`target/`와 모든 패키지에서 `GlobalPreparation`, `FactorMarginal`, `GlobalBounds` 잔존 class는 0개다.
Python compile, shell syntax, diff 공백 검사도 통과했다. 원시 결과의 독립 재계산은 동일했고,
분석기 리뷰에서 지적된 이전 cohort 검증·frozen import hash 연결·과장된 결과 표현을 수정했다.

최종 JAR SHA-256은 `4894a32e4fbf0ff18f9a1c23f381884fd7d60160d6c978fa7b718af0764fd4ac`이다.
bridge 측정 source와의 production 차이는 `PruningAblation.java`의 implicit 기본 반환값 한 줄과
설명 주석 한 줄이다. 20개 측정은 explicit L/SL을 사용했으며 두 경로는 변경하지 않았다.

검증 로그는 `/home/mchoi/w1357-pruning-ablation-20261006/`의
`factorial-no-global-tests.log`, `factorial-no-global-package.log`,
`factorial-final-tests.log`, `factorial-final-package.log`에 있다.
원시 결과의 hash 검증 보존본은
`/grid/3/cofee-lm-sweep-mchoi-20260914/pruning-ablation-20261006/` 아래의
`factorial-study-v1`, `support-bridge-v1`이고, 최종 코드·보고서·JAR·검증 로그는
`factorial-delivery`에 보존한다. 과거 실험 자료는 덮어쓰지 않았다.

기존 별도 이슈인 `EarlyPrivacyPruningLegalSpaceParityTest.unknownShapeKeepsSafeAggregateAndCenteringAlternatives`
golden mismatch는 미해결 상태다. 이번 대상 80건과 구별하며 전체 저장소 테스트가 통과했다고
주장하지 않는다. 측정은 `timeMillis=0`인 초기 compile planning으로, 실제 학습 runtime이나
기본 10초 optimizer cutoff 조건의 성능 검증은 아니다. D/S를 끄면 더 넓은 탐색이 자원 제한에
영향을 줄 수 있으므로 이후 workload에서도 선택 비용·플랜·종료 이유를 함께 확인해야 한다.

## 후속 구현 삭제 및 main 통합 검증

사용자 요청으로 dominance·추가 n-ary support를 전용 구현과 실험 옵션까지 삭제했다.
`ExactPhysicalReducedSolver`와 seed 경로는 기존 exact quotient와 unary/binary support로
복구했고, D 전용 canonical 인증 scan을 제거했다. local prefix의 numeric certificate는 유지한다.
실험 receipt용 전수 계측도 explicit 실행에서만 계산하도록 정리했다. 활성 비교 옵션은
`baseline`과 `local_only` 두 개이며, 과거 `local`/D/S/global 설정은 명시적으로 거부한다.

최신 main `10e14bc791`의 비용 모델 수정을 보존하여 통합했다. 최종 대상 Java **82건**,
package 검사 **10건**, Python **19건**이 통과했다. 확대 검사에서 발견한 과거 global
trace 필드 기대값은 실제 소비 코드가 없음을 확인하고 제거된 스키마에 맞췄다.
Docker wrapper에서 logreg/GNMF의 baseline/local_only **4/4 compile/lowering audit**가 통과했고,
각 쌍의 플랜·비용·lower/upper·종료 이유가 모두 같다. 이 확인을 새 latency ablation으로
해석하지 않으며 이전 연구의 수치는 그대로 보존한다.

최종 JAR SHA-256: `58c889c77fb9d6dcd2ba54659a9410b608303d1f594b542eca7447239f594dc1`.
[삭제 후 검증 기록](pruning-factorial-20261006/removal-validation.json)에 테스트·샘플·source 근거를
기록했다. 원시 Docker 자료는 home 실험 root의 `pruning-deletion-main-v1` 및 동일 이름의
grid 보존본에 있고, 최종 코드는 `pruning-deletion-delivery`에 보존한다.
