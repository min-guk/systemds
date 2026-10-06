# Pruning 기본값 선택 — local 단독 비교

> 후속 개별 ablation으로 아래의 기본값 결정은 대체되었다. 현재는 **local만 기본 활성화**하고
> dominance·추가 support·global은 구현과 실험 옵션을 삭제했다.
> [최종 4조합 비교와 결정](PRUNING_FACTORIAL_2026-10-06.md)을 참조한다. 아래 수치는 당시 실험 기록이다.

## 최종 결정과 결과

**기존 dominance+support+local 조합을 기본값으로 유지하고 global은 기본 비활성 상태로 둔다.**
local 단독으로 바꾸는 이득은 확인하지 못했다. `local_only`는 재현 가능한 실험 옵션으로만
남겼고, 측정 전에 적용했던 임시 local_only 기본값은 누적 `local`로 되돌렸다.

90회 모두 정규 compile/lowering audit를 통과했다. ML9의 같은 workload·반복 번호 27개
시간비를 기하평균한 결과는 다음과 같다. 음수는 시간 감소다.

| 비교 | 전체 초기 플래닝 | Optimizer | 선택 플랜·비용·종료 이유 |
|---|---:|---:|---|
| 기존 local / baseline | −1.15% | −8.83% | 27/27 동일 |
| local_only / baseline | +1.56% | −8.98% | 27/27 동일 |
| local_only / 기존 local | +2.74% | −0.16% | 27/27 동일 |

기존 local은 baseline보다 전체 시간이 줄어든 workload가 7/9개였다. local_only는
baseline보다 빨라진 workload가 4/9개였고, 기존 local보다 느려진 workload가 6/9개였다.
local_only의 optimizer 시간은 기존 local과 사실상 같았다. 전처리를 추가로 끄는 기본값
변경을 뒷받침할 전체 시간 개선이 없어 기존 설정을 유지한다.

전체 시간 차이는 작고 반복별 변동도 있다. local_only/기존 local의 analysis 시간도 3.37%
증가했으므로 관측한 전체 차이를 dominance/support의 순수 인과 효과라고 단정하지 않는다.
이번 결론은 기존 기본값을 교체할 근거가 없다는 것이며, 기존 local이 모든 workload에서
확실히 더 빠르다는 주장이 아니다. 앞선 누적 ablation에서도 global은 local보다 optimizer
27.00%, 전체 시간 1.39%가 더 걸렸으므로 기본 활성화할 근거가 없었다.

| Workload | 기존 local의 전체 시간 변화 | Optimizer 변화 | Child 비용 조회 감소 |
|---|---:|---:|---:|
| logreg | −2.63% | −13.68% | 70.22% |
| l2svm | +2.36% | −10.64% | 61.65% |
| pca | −2.18% | −4.84% | 54.34% |
| als | +2.49% | −7.05% | 해당 병합 작업 없음 |
| kmeans | −3.91% | −21.58% | 73.99% |
| lm | −1.77% | −7.59% | 37.41% |
| glm | −0.46% | +4.63% | 해당 병합 작업 없음 |
| gnmf | −2.41% | −29.65% | 78.46% |
| gmm | −1.66% | +19.55% | 46.64% |

모두 baseline 대비이며 시간은 3개 대응 비율의 기하평균이다. 비용 조회 수는 각 설정에서
3회 모두 같았다. 전체 시간의 가장 큰 관측 증가는 ALS의 2.49%였다. GMM의 optimizer는
19.55% 증가해, 기본 조합 역시 모든 단계·workload에서 이득을 보장하지 않는다.
workload별 초 단위 중앙값과 최소·최대는 [상세 표](local-only-pruning-20261006/tables.md)에 있다.

선택 목적함수 bits, 물리 플랜 fingerprint, 종료 이유는 모든 설정·반복에서 동일했다.
30개 workload/설정 조합 모두 반복 간 같은 플랜·비용을 선택했다. GNMF의 누적 local은
하한만 `10947.472674026232`에서 `10947.47267402623`으로 1 ULP 낮아졌다. 따라서 하한까지
포함한 일치는 local 관련 비교에서 24/27, local_only/baseline은 27/27이다. 이는 선택 비용의
악화가 아니며 두 하한 모두 선택 비용 이하이다.

STEP 호환군 9회도 모두 통과하고 플랜·비용을 유지했다. 기존 local/baseline은 전체 −2.74%,
optimizer +7.24%; local_only/baseline은 전체 +0.45%, optimizer +6.40%였다. 이 결과는 ML9
기본값 판단과 분리하며, ML9의 optimizer 감소를 뒷받침하는 결과로 사용하지 않는다.

## 목적과 판단 기준

사용자는 실제로 도움이 되는 pruning만 기본으로 사용하기를 요청했다. 앞선
[누적 ablation](PRUNING_ABLATION_2026-10-06.md)은 dominance, support, local을 차례로
추가했으므로 local 단독 효과와 선행 전처리의 필요성을 구분할 수 없었다.
이번에는 같은 바이너리에서 baseline, local_only, 기존 local을 직접 비교한다.

선택 플랜과 비용의 보존을 먼저 확인하고, 전체 초기 플래닝 시간과 optimizer 시간을
함께 비교한다. 작은 시간 차이는 3회 측정만으로 확정하지 않는다. 플랜 품질을 유지하면서
불필요한 계산을 줄이는 간단한 기본값을 선택하는 것이 목적이다.

## 비교하는 설정

| 설정 | 새 dominance | 추가 n-ary support | Boundary prefix pruning | Global U pruning |
|---|---:|---:|---:|---:|
| baseline | 꺼짐 | 꺼짐 | 꺼짐 | 꺼짐 |
| local_only | 꺼짐 | 꺼짐 | 켜짐 | 꺼짐 |
| local (과거 누적 설정) | 켜짐 | 켜짐 | 켜짐 | 꺼짐 |

기존 equality quotient, unary/binary support, privacy 및 runtime legality는 모든 설정에서
유지한다. 여기서 끄는 support는 root 전처리에 추가했던 n-ary support다. seed를 원래
모델로 복원할 때 필요한 support 전파와 auxiliary completion은 유지한다.

`local_only`의 infinity-prefix 가지치기는 이미 불가능한 부분 조합의 나머지 비용 조회를
생략한다. cost-prefix 가지치기는 같은 경계 상태에서 현재 부분 비용이 최선 비용 이상인
조합의 나머지를 생략한다. 후자는 실제 message 비용의 비음수·정확 합산 인증을 통과할 때만
작동한다. 두 기법 모두 선행 dominance나 root n-ary support를 전제로 하지 않는다.

`sysds.fedplanner.pruning.ablation` property가 없으면 실험 계측 없이 누적 `local`을 사용한다.
명시적인 `local`은 재현성을 위해 과거의 dominance+support+local 의미를 유지한다.
새 `local_only`는 단독 비교를 위한 명시적 선택이다. 과거 다섯 variant의 실험 기본 순서는
변경하지 않았다. global은 계속 명시적인 실험 옵션이다.

## 측정 조건

- 같은 workload·반복 번호의 대응 비교. 10개 workload × 3설정 × 3반복 = 90회.
- 주 집계: logreg, l2svm, pca, als, kmeans, lm, glm, gnmf, gmm의 9개 workload.
- STEP-LM은 `maxi=20`, `max_features`가 없는 호환 정의를 별도로 표시하며 주 집계에서 제외.
- sealed X=50,000×128, ROW, PrivateAggregation; Y가 있으면 50,000×1 Public.
- 모델상 worker 3개와 고정 WAN-Mid 비용 profile. 실제 worker/학습 실행은 없음.
- 정규 parse/HOP planning/LOP lowering/runtime-program construction을 실행한 compile-only 측정.
- `run_LAN_docker.sh --pruning-ablation`만 사용. 한 번에 coordinator container 하나, network none.
- 동일 Docker image, CPU 8–15, JVM 16 GiB·8 processors, container 24 GiB. 매 sample 새 JVM.
- variant 순서를 매 반복 순환하여 workload마다 세 설정이 각 순서에 한 번씩 위치.
- optimizer 시간 제한은 0, gap 0.05, merge assignments 1,000,000, retained slots 8,000,000,
  scored candidates 16, earlyStop=true. 앞선 ablation과 동일.

전체 초기 플래닝 시간과 optimizer 시간은 서로 다른 범위의 측정이다. optimizer에서 줄인
비용 조회가 전체 컴파일 시간을 같은 비율로 줄이지는 않는다. 주 집계는 ML9의 27개 대응
시간비의 기하평균이고, workload별 중앙값·최소·최대도 남긴다. 공유 호스트의 cold-JVM
탐색적 측정으로, 통계적 유의성이나 실제 학습 실행시간의 개선을 주장하지 않는다.

## 변경 및 검증 범위

이 후속 작업의 production source 변경은 `PruningAblation.java`뿐이다. `LOCAL_ONLY` 실험
variant를 추가하고 gate를 variant의 네 boolean으로 직접 표현한다. 최종 기본값은 이전과 같다.
cost model, 후보의 runtime legality, 물리 실행 지원 규칙은 변경하지 않았다. feature별 Java
테스트는 해당 실험 variant를 명시하도록 변경했으며, 기본 설정과 명시적 local의
domain·경계 최소값·min-marginal·lower bound·argmin 일치도 확인한다.

임시 local_only 기본값을 검증한 뒤, 최종 local 기본값에 대해 Java 대상 테스트 83건을
다시 실행해 실패·오류·제외 없이 통과했고 패키징도 성공했다. 범위는
PruningAblation, BoundaryPruning, CostBasedPruning, IncrementalRegionalOptimizer,
IncrementalRegionalSeed, ExactPhysicalReducedSolver, IncrementalBoundaryMessage다.
더 넓은 이전 회귀에서 알려진 `EarlyPrivacyPruningLegalSpaceParityTest`의 unknown-shape
golden 불일치는 별도 미해결 이슈이며, 이 작업에서 golden 교체나 제외를 하지 않았다.

실험·분석 스크립트 테스트 13건도 통과했다. 새 분석기는 실제 receipt와 manifest를 읽고,
중복 셀·global 활성화·잘못된 bits·0/boolean timing 등 6개 증거 변조를 거부하는 것을 확인했다.
90개 원시 container 기록에서 image/CPU/memory/JVM/비용 환경/시간 정책 일치와 실행 구간의
비중첩을 확인했다. workload별 rendered DML과 입력·dependency hash는 설정·반복 간 같았고,
앞선 ML9 및 STEP 호환군 정의와도 일치했다. 남아 있는 해당 study container는 없다.

## 재현 및 증거

측정 바이너리 SHA-256:
`2cfb6398b03eb844699f0b513c341a12d6ed8af7eb491a75e54e2e95158c4a55`.
원시 결과는 `/home/mchoi/w1357-pruning-ablation-20261006/local-only-study-v1`에 있으며
manifest, 실제 명령, rendered DML, receipt, 로그와 동결한 실행 스크립트를 보존한다.
과거 ablation의 바이너리와 원시 결과는 수정하지 않는다. 측정은 모두 명시적 variant를
지정했으므로, 이후 implicit 기본값만 local로 복구한 변경이 측정된 세 경로를 바꾸지 않는다.
최종 기본값 복구 후 패키지 SHA-256은
`4ece87ebdfa652a4f40752f38cb63faca68af42f78cc127af7cf1632de406957`이다.
측정 source hash와 일치하는 이전 `PruningAblation.java`를
`/home/mchoi/w1357-pruning-ablation-20261006/local-only-measured-PruningAblation.java`에 별도 보존했다.
두 production source snapshot의 차이는 implicit 반환값 `LOCAL_ONLY`→`LOCAL` 한 줄뿐이다.

[분석 결과](local-only-pruning-20261006/analysis.json),
[검증 기록](local-only-pruning-20261006/validation.json),
[전체 수치](local-only-pruning-20261006/stats.csv)를 함께 보존한다. 최종 회귀/build 로그는
실험 root의 `local-default-final-tests.log`, `local-default-final-package.log`다.
별도 보존본은 `/grid/3/cofee-lm-sweep-mchoi-20260914/pruning-ablation-20261006/local-only-study-v1`에,
최종 소스 patch·미추적 파일·보고서·바이너리·검증 로그는 같은 상위 경로의
`local-default-delivery`에 둔다. 앞선 `raw-results`와 `delivery`는 보존한다.

```bash
bash scripts/fedplanner/run_LAN_docker.sh --pruning-ablation \
  --root /home/mchoi/w1357-pruning-ablation-20261006/local-only-study-v1 \
  --cost-profile /home/mchoi/w1357-pruning-ablation-20261006/study-v1/cost-profile.json \
  --stage /home/mchoi/w1357-stage-privacy-test-only-e21cac2-07726cd \
  --evaluation-root /grid/3/cofee-lm-sweep-mchoi-20260914/pruning-ablation-20261006/evaluation-snapshot \
  --renderer-file /grid/3/cofee-lm-sweep-mchoi-20260914/pruning-ablation-20261006/evaluation-snapshot/legacy/render_w1357_workload.py \
  --variants baseline local_only local --repetitions 3

python3 docs/local-only-pruning-20261006/analyze.py \
  --root /home/mchoi/w1357-pruning-ablation-20261006/local-only-study-v1 \
  --output docs/local-only-pruning-20261006
```

`analyze.py`는 계획된 90개 전부의 성공을 요구한다. 누락·중복·실패 sample을 조용히 제외하지
않으며 manifest와 결과 payload, 실제 compile-only 경로, lowering audit, 비용 bits,
플랜 fingerprint를 검증한다. 과거 study와 시점이 다른 데이터를 섞어 새 효과로 집계하지 않는다.
