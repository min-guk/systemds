# Federated planner 남은 작업 처리 및 검증 — 2026-10-06

기준 커밋: `10e14bc791d391a678ecab67ffd9f98949be32c6`.
검증 대상은 이 커밋 위의 수정된 소스이며, 파일별 SHA와 최종 jar SHA는
[validation.json](experiments/remaining-planner-work-20261006/validation.json)에 있다.

## 변경 사항

- **보조 통신 비용**: VAR의 mean, aligned/weighted covariance의 mean·weight sum,
  ROW cumulative의 부분 결과·보정 upload, CTABLE의 차원 탐색, reshape의 metadata batch를
  실제 runtime batch 소유권에 맞춰 추가했다. 기존 기본 instruction/native result 비용과
  중복하지 않으며, occurrence별 행렬 크기와 실행 횟수를 적용한다.
- **StepLM 탐색 실패**: shared exact reduction에서 제거된 초기 boundary 값을 고정하지
  않도록 충돌 수정 범위를 확장한다. 기존 encoded model을 재사용하며 후보나 cap을 변경하지 않는다.
- **if 분기 이동**: immutable analysis 전에 실제 분기 끝 `TR → TW`를 만들어
  그 입력 edge에서 명시적 LOCAL/REFED 이동을 선택하고 과금한다.
  같은 값을 가리키는 TW/TR와 함수 binding의 배치 일치 규칙은 유지한다.
- **재컴파일**: 분기별 식별자를 clone/signature에 보존하고, runtime 값이 없는
  함수 재컴파일 2단계에서 1단계가 생성한 분기 이동 명령을 삭제하지 않도록 했다.
- **반복 함수 호출**: 같은 인자를 여러 번 전달할 때 lookup은 호출 경계를 구분한다.
  같은 source/formal/slot에 대한 static placement authority만 중복 제거하고,
  호출별 횟수·생성 문맥은 보존한다.
- **k-means 후보 생성**: 이미 증명된 DIRECT support를 재결합 중 보존한다.
  만료된 근거는 기존 support/action 검증으로 제거하여 worker layout 간 진동을 해소했다.
- **GLM 메모리 사용**: 축소한 비용 테이블을 만들면서 사용이 끝난 원본 테이블의 참조를
  순차 해제한다. 64MiB 재현과 같은 10GB 한도의 실제 GLM compile을 모두 통과했다.
- **GMM 크기 정보**: 분기에서 정의되지 않은 변수에 새 `Y=Y`를 만들지 않도록
  definite assignment를 추적한다. UNKNOWN shape가 10GiB 기본값으로 과대 계산되던 문제를
  제거했다. 기존 입력값이 있는 빈 else의 변환은 유지한다.

분기 이동은 예를 들어 다음처럼 실행된다.

```text
분기 결과(FOUT) → TW(FED/FOUT) → TR(FED/FOUT)
                                  │
                             LOCAL 다운로드
                                  ↓
                             TW(CP/LOUT) → 합류 TR(CP/LOUT)
```

루프는 0회 실행 가능성을 고려하여, 루프 내부에서 처음 정의한 값을 루프 밖의
항상 존재하는 값으로 간주하지 않는다.

## MMChain·업로드 감사 결과

- 이전 MMChain probe는 root만 FED로 강제했으며 전체 유효 계획을 증명하지 않았다.
  실제 DP 선택에서는 physical normalization과 fusion boundary를 거쳐 일반 FED multiply와
  transpose가 실행됐다. 확인되지 않은 융합 비용을 임의로 삭제하지 않았다.
  forced-FOUT TSMM도 같은 이유로 단순 문법 패턴만 보고 추가 과금하지 않는다.
- 최신 mixed-privacy 반복 입력 probe에는 선택된 명시적 REFED가 없었다.
  FED matmul의 local RHS 이동은 intrinsic preparation이므로 REFED cache 증거로 세지 않는다.
  이번 유효 계획들에서는 추가 업로드 중복 과금을 재현하지 못했다.
- 이 감사는 모든 가능한 DML·shape·cache 상태에서의 버그 부재 증명은 아니다.

[MMChain 근거](experiments/remaining-planner-work-20261006/MMCHAIN_AUDIT.md),
[업로드 근거](experiments/remaining-planner-work-20261006/UPLOAD_AUDIT.md),
[GMM 원인 분해](experiments/remaining-planner-work-20261006/GMM_SHAPE_DIAGNOSIS.md).

## 최종 검증

- Java production/test 전체 컴파일 및 jar 생성 성공.
- 77개 테스트 클래스, **606 tests PASS, failures/errors/skips 0**.
- 14개 실험 × W1/W3: **28/28 compile PASS**.
- 실제 Docker worker 2개: **6/6 수치 결과 및 runtime audit PASS**.
  모든 실행에서 fallback=0, repair=0.
- Shell/Python 문법, `git diff --check` 통과.

실제 출력은 aggregate=92, shape=4480, linear=3588, control=304,
branch_true=95, branch_false=92이며, weighted checksum으로 연산이 단순 합계로
제거되지 않도록 검증했다. 실제 FED VAR 경로도 관찰했다.
Public covariance와 shape 연산은 이번 DP 선택에서 CP로 실행됐으므로 해당 FED native
경로의 실제 worker 검증으로 간주하지 않는다.

28개 실험은 이전 비교와 같은 고정 campaign script/support를 사용한다.
StepLM/lmCG support에는 campaign의 `max_features` 확장이 포함된다.
계획 컴파일 검증과 실제 worker 검증을 구분한다.

실제 worker는 컨테이너 loopback에서 실행했다. 예측 objective에는 기존 WAN-Mid
비용 설정을 사용했으며, compile/execution/container wall time과 요청·네트워크 통계를
별도로 기록했다. 이 결과는 WAN 실측 성능 정확도나 속도 개선율을 뜻하지 않는다.

[검증 명령·SHA](experiments/remaining-planner-work-20261006/validation.json),
[단위 테스트 결과](experiments/remaining-planner-work-20261006/unit-results.json),
[실제 worker 결과](experiments/remaining-planner-work-20261006/runtime-receipt.json).

## 14개 실험의 계획 변화

계획·후보 수는 일부 달라졌다. 비용 모델과 후보 공간이 함께 바뀌었으므로 objective
증감을 그대로 실행시간 증감으로 해석하지 않는다. 아래 후보 수는 coarse alternatives의
합계이며, 전체 가능한 조합 수가 아니다. 물리 후보 수와 선택 상태 histogram은
[전체 비교 JSON](experiments/remaining-planner-work-20261006/all14-comparison.json)에 있다.

특히 GLM W1은 21,355→46,213ms, L2SVM W3는 7,198→9,015ms로 예측 비용이 증가했다.
두 경우 모두 대응되는 기존 노드 중 4개의 배치가 달라졌다. GLM은 line 983의
transpose/multiply 및 matmul 배치, L2SVM은 Y의 write/read 배치가 바뀌었다.
이 비교는 실제 선택 변경을 확인한 것이며, 비용 보정과 로컬 탐색 품질의 영향을
추가 분해한 결과는 아니다. 따라서 모든 실험의 계획 불변이나 비용 개선을 보장하지 않는다.

| Case | 이전 objective (ms) | 수정 후 objective (ms) | 그래프 노드 | Coarse 후보 |
| --- | ---: | ---: | ---: | ---: |
| ADULT_w1 | 2217941.581178 | 2218143.581364 | 717 → 757 | 806 → 846 |
| ADULT_w3 | 5481083.781846 | 5481285.785274 | 729 → 769 | 757 → 797 |
| COVTYPE_w1 | 2579872.304263 | 2580074.304450 | 717 → 757 | 806 → 846 |
| COVTYPE_w3 | 6055808.970395 | 6056010.973823 | 729 → 769 | 757 → 797 |
| P1_FULL_w1 | 127343190.489742 | 127343291.599442 | 1464 → 1516 | 1625 → 1681 |
| P1_FULL_w3 | 340637460.840600 | 340637562.170686 | 1472 → 1524 | 1537 → 1593 |
| P2_PREP_w1 | 7275.921294 | 7378.545998 | 130 → 130 | 138 → 138 |
| P2_PREP_w3 | 6714.429861 | 6820.298774 | 146 → 146 | 144 → 144 |
| als_w1 | 131381.417860 | 131381.417861 | 194 → 210 | 233 → 257 |
| als_w3 | 131126.225782 | 131126.225783 | 202 → 218 | 225 → 249 |
| glm_w1 | 21355.270631 | 46212.521643 | 1175 → 1229 | 1611 → 1655 |
| glm_w3 | 143482.407946 | 20834.288099 | 1187 → 1241 | 1551 → 1594 |
| gmm_w1 | 25042.749584 | 25042.749584 | 384 → 398 | 534 → 558 |
| gmm_w3 | 18305.956166 | 18305.956167 | 392 → 406 | 447 → 465 |
| gnmf_w1 | 18501.025173 | 18501.025173 | 81 → 85 | 119 → 123 |
| gnmf_w3 | 18521.179041 | 18521.179042 | 89 → 93 | 109 → 113 |
| kmeans_w1 | 85850.614348 | 85850.614348 | 327 → 347 | 408 → 428 |
| kmeans_w3 | 14593.084816 | 14593.084816 | 335 → 355 | 386 → 408 |
| l2svm_w1 | 5406.406292 | 5406.645764 | 227 → 231 | 351 → 361 |
| l2svm_w3 | 7198.293366 | 9014.979377 | 239 → 243 | 322 → 332 |
| lm_w1 | 481.113712 | 481.113712 | 34 → 34 | 48 → 48 |
| lm_w3 | 503.065460 | 503.065462 | 46 → 46 | 60 → 60 |
| logreg_w1 | 22365.229287 | 22264.199155 | 477 → 509 | 724 → 782 |
| logreg_w3 | 162245.525136 | 119794.002460 | 489 → 521 | 644 → 690 |
| pca_w1 | 1539.036995 | 1539.036995 | 86 → 90 | 134 → 144 |
| pca_w3 | 1149.243041 | 1149.243041 | 94 → 98 | 123 → 133 |
| steplm_w1 | 28316690.330166 | 28316690.330167 | 442 → 482 | 493 → 533 |
| steplm_w3 | 76349276.548443 | 76349276.548445 | 454 → 494 | 471 → 511 |


## 계획 비용 증가 후속 해결

위 단계 이후 확인한 GLM W1 탐색 누락과 L2SVM W3 분기 경계 GET 과금을 추가 수정했다. 최종 결과는 GLM 20,956.64ms, L2SVM 4,259.97ms이며 681 unit/28 compile/6 actual runtime PASS다. 최신 상태와 검증 범위는 [후속 해결 보고서](FEDPLANNER_SEARCH_BOUNDARY_FIX_2026-10-06.md)를 참고한다.
