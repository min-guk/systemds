# Heuristic legality main publication 및 잔여 문제 감사

## 통합 범위

최신 fetch한 `origin/main` `57933f328c`에 Heuristic 구현 `93170f9dfb`를 병합했다. 새 worktree는 `/home/mchoi/heuristic-main-publication-20261007`이다. 기존 workspace를 수정하지 않았다. runtime fallback/repair, privacy/TR-TW/geometry 완화 또는 큰 factor로의 flattening을 추가하지 않았다.

공통 joint legality, WDIVMM support propagation, 제한된 selection repair, final publication support 정리를 포함한다. 상세 구현과 이전 검증은 [기존 구현 보고서](HEURISTIC_LEGALITY_IMPLEMENTATION_2026-10-06.md)에 있다. 최신 main의 support-certified alias projection 및 owner-consistent reference-product pruning을 보존했다.

## 병합 시 추가로 바로잡은 부분

main이 추가한 joint `partialTruth`에도 completed cost와 같은 단일 물리 입력 규칙을 적용했다. 물리 입력 위치가 하나 이하이면 입력 간 pool 정렬은 항진이다. 완성 tuple에서는 0인데 partial certificate가 금지하는 불일치를 방지한다. 변수 scope, observation encoding, 기존 support factors는 그대로 유지한다.

`JointPartialTruthTest.loopMatrixScalarKeepsPartialProofsSound`는 실제 joint relation, 해당 consumer가 첫 축인 canonical partial factor, 정확히 한 물리 입력을 가진 FED 대안의 존재를 확인한다. 모든 partial prefix와 완성 tuple을 전수 대조하고 frozen table의 raw-bit 비용 동치를 확인한다. 처음 시도한 local-matrix fixture는 joint factor를 만들지 않아 fixture assertion에 실패했으며 제거했다. 실패 로그도 보존한다.

## 최종 검증

19개 클래스 **100/100** 회귀와 최종 Maven package가 통과했다. 최초 실행의 101건 중 factor를 생성하지 않던 신규 fixture assertion 1건이 실패했으며, 이를 제거하고 실제 joint factor를 확인하는 loop 회귀를 보강해 해당 클래스 5/5 및 package를 재검증했다. 중복 재실행은 총계에 더하지 않았다. Python harness compile 검사와 staged/unstaged diff whitespace 검사도 통과했다. Docker Heuristic 6/6, 소형 ALS, 실제 builtin LogReg/L2SVM/lmCG 3/3도 통과했다. 이 성공 목록과 별개로 아래 StepLM 기존 오류 1건은 재현됐다. 최종 수치와 artifact hash는 `experiments/heuristic-main-publication-20261007/validation.json`에 기록한다. 모든 실제 학습 검증은 `scripts/fedplanner/run_LAN_docker.sh`의 pinned Docker 경로를 사용한다. 대형 모델은 제외한다.

소형 ALS는 50×20, rank10, 2회 반복에서 CP/FedAll 출력 200개 값 최대 오차 0.0, 출력 SHA 동일이다. `fed_wdivmm` 44회, physical lowering 74/74이며 fallback/repair 및 audit missing/mismatch는 0이다. Heuristic 검증은 함수·루프, mixed branch, correlated X/Y, independent protected의 runtime 전 거부, PUBLIC movement 대안의 6건이다.

| 소형 builtin 학습 | CP/FED 계수 | FED compile / execution (초) | modeled upper |
| --- | --- | --- | --- |
| multiLogReg | 16/16 일치, 최대 오차 2.22e-16 | 38.121 / 2.822 | 122.26631334184357 |
| l2svm | 8/8 일치, 최대 오차 8.42e-17 | 9.921 / 0.862 | 21.385960545366007 |
| lmCG | 8/8 일치, 최대 오차 1.23e-15 | 4.135 / 0.502 | 21.2628479582568 |

모든 학습 runtime audit/conversion 위반은 0이며 modeled upper는 이전 alias main 검증과 동일하다. 단일 관측 시간으로 성능 개선을 주장하지 않는다. 최종 source/class hash가 Docker frozen artifact와 일치하는지 확인했다.

원본 통합 로그: `/home/mchoi/heuristic-main-publication-20261007/target/main-publication-evidence/`.
Heuristic artifact: 같은 worktree의 `target/fedpolicy-greedy-docker/heuristic-legality-run-2axx2r65/`, ALS: `als-run-k0i7x50k/`.
학습 artifact: `/grid/3/cofee-lm-sweep-mchoi-20260914/boundary-seed-ml-20261006/heuristic-main-publication-20261007/`.

## 남은 항목과 근거의 시점

| 항목 | 판정 및 다음 작업 |
| --- | --- |
| StepLM CFG closure 비수렴 | **최신 통합본에서도 재현됨.** 20×5 compile-only 테스트가 229.971초 뒤 `CFG transient candidate closure did not converge`로 실패. `f=626/632`, `changed=[281,423]`, iteration1244로 과거 signature와 일치한다. 가장 먼저 해결할 확인된 오류다. |
| ALS / StepLM canonical Exact factor overflow | `0146f043e0`에서 `EXACT_VE_FACTOR_CELL_OVERFLOW` 확인. 원래 fixture는 50,000×2,100 metadata여서 이번 실행에서 제외했다. 최신 재현 여부는 미확정이며 factor 관계를 유지한 축소 fixture가 후속 작업이다. |
| Heuristic L2SVM full metadata closure 지연 | `93170f9dfb` 검증에서 selector 전에 오래 걸려 중단. 50,000×2,100 fixture의 검증 공백이며 현재 실행 실패로 확정하지 않는다. 소형 실제 L2SVM 학습과 구분한다. |
| WDIVMM unsupported weights 음성 회귀 | direct/latent 합법 선택 및 ALS runtime은 검증했다. 유효한 불법 weights end-to-end fixture가 아직 없다. 테스트 공백이다. |
| explicit upload 후 cost read/source 경계 | 업로드 전 원본까지 역추적한 조건은 합법 업로드를 거부할 수 있다. binary 회귀는 있지만 실제 WDIVMM factor 조립 경로에서 도달 가능한 결함인지는 미확정이다. |
| LogReg common analysis / dense factor 비용 | 최신 alias projection 뒤에도 분석 및 dense storage 비용이 남는다. correctness 오류가 아닌 성능 개선 항목이다. 작은 학습 성공으로 큰 모델의 모든 분석이 해결됐다고 주장하지 않는다. |

Greedy의 256회 대안 탐색 한도는 의도된 제한이다. 한도 소진은 전역 불가능성 증명이 아니다. LogReg/L2SVM/lmCG의 `TARGET_REACHED`도 합법적인 제한된 최적화 결과이며 전역 최적해 증명이 아니다. alias projection cycle-token 유지의 mutant 검출 공백은 [기존 main 보고서](experiments/joint-alias-20261007/validation.json)의 limitations에 보존한다.

과거 오류 근거는 [shared repair main-publication.json](experiments/shared-repair-reuse-20261006/main-publication.json)의 `exactOriginBaseline` 계열 기록, Heuristic 검증 공백은 [implementation-validation.json](experiments/heuristic-legality-20261006/implementation-validation.json)에 있다. 큰 fixture를 실행하지 않은 항목은 최신 main의 현재 버그로 재분류하지 않는다.

## 잠재 회귀 위험

alias projection의 동치는 유지된 realization-support factors와의 conjunction을 전제로 한다. 이를 단독 predicate 동치로 오해하면 legal space가 바뀔 수 있다. 기존 exhaustive alias support-conjunction/partial-truth 검사를 유지하고 소형 학습 전체 계수 CP/FED 비교, runtime audit 및 forbidden joint 사전 거부로 통합을 검증한다. 실험 시간은 공유 호스트 단일 관측이며 성능 A/B 개선으로 해석하지 않는다.

## StepLM 소형 재현

대상은 `FederatedPlannerFallbackIntegrationTest#testDpPlansSteplmWithSameNamedFormalTransientBinding`이며 실제 학습 실행 없이 `constructLops`까지 호출한다. JUnit method runner와 명령 근거를 원본 로그 디렉터리에 보존한다.

```bash
timeout --signal=TERM --kill-after=10s 300s java --add-modules jdk.incubator.vector -Xmx4g -XX:ActiveProcessorCount=4 -cp 'target/main-publication-evidence:target/classes:target/test-classes:target/lib/*' BoundedMethodRunner org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest testDpPlansSteplmWithSameNamedFormalTransientBinding
```

표준 Maven 선택자로도 같은 메서드를 지정할 수 있다: `-Dtest=FederatedPlannerFallbackIntegrationTest#testDpPlansSteplmWithSameNamedFormalTransientBinding test`. 165초 시점 thread dump는 optimizer 진입 전 `PlacementRelationClosure.replayUniqueCfgTransientForwardsMeasured`와 `closeCfgTransientCandidateDependenciesMeasured`에 있었다. 시간 제한 종료가 아니라 기존 비수렴 예외로 끝났다. 이번 publication은 이 독립 기존 결함을 수정하지 않는다.
