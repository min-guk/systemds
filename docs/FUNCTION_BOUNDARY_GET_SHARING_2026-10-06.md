# 함수 경계 배치 보존과 GET 공유 비용 수정

## 변경 범위

- 함수 입력도 출력 및 TW/TR과 동일하게 값의 배치를 보존한다. actual → input binding은 SAME_VALUE_PLACEMENT, binding → formal read는 SAME_PLACEMENT다. 바인딩 자체에 다운로드를 숨기지 않는다. 인라인 함수 trace는 실제 runtime 호출 바인딩과 구분한다.
- 같은 원본 MatrixObject를 호출자와 함수 내부에서 읽으면 다운로드를 공유한다. 원본 생성 occurrence, 실제 호출 ancestry, 정확한 native layout, 비용 단가를 기준으로 합친다. 새 값·다른 호출에서 생성된 값·relocation은 무조건 합치지 않는다.
- 후보 축소가 언제나 비용을 보존한다는 주장은 하지 않는다. if 합류의 미해결 표현력 문제는 아래에 명시한다.

## 원래 작업 snapshot의 14 workload 비교

WAN-Mid, DP-local, worker 1/3, seed1011081480. 기존 함수 경계 이동 허용 A와 배치 보존 B에 동일한 수정 비용 모델을 사용했다. Docker compile-only 56/56 성공. 선택 배치·생성 명령·비용은22/28조건에서 동일하며6조건에서 달랐다. STEP-LM W1은 그 세 항목이 동일하지만 candidate input witness가 달라 전체 physical fingerprint 일치는21조건이다.

이 비교는 main에 없는 다른 미커밋 비용 모델 변경도 포함한 격리 snapshot에서 수행했다. 아래 비용을 이번 origin/main 통합 코드의 비용으로 해석하면 안 된다. 원본 artifact: `/home/mchoi/function-boundary-all14-20261006/` (`build-manifest.json`, `comparison.json`, `validation.json`, `results/{A,B}/`).

| 조건 | A 비용 | B 비용 | B−A | 노드별 physical 후보 합 A→B |
|---|---:|---:|---:|---:|
| logreg_w1 | 22931.667942 | 22466.228568 | -465.439374 | 78650→78633 |
| logreg_w3 | 78460.224427 | 78460.224427 | +0.000000 | 24701→24684 |
| l2svm_w1 | 5315.551166 | 5406.406292 | +90.855125 | 9196→8856 |
| l2svm_w3 | 7289.116873 | 7198.293365 | -90.823508 | 8315→6598 |
| pca_w1 | 1539.036995 | 1539.036995 | +0.000000 | 877→877 |
| pca_w3 | 1250.242321 | 1250.242321 | +0.000000 | 177→177 |
| als_w1 | 131381.417860 | 131381.417860 | +0.000000 | 335→335 |
| als_w3 | 131126.225782 | 131126.225782 | +0.000000 | 273→273 |
| kmeans_w1 | 85850.614347 | 85850.614347 | +0.000000 | 2640→2640 |
| kmeans_w3 | 14593.084815 | 14593.084815 | +0.000000 | 977→977 |
| lm_w1 | 481.113712 | 481.113712 | +0.000000 | 195→195 |
| lm_w3 | 503.065460 | 503.065460 | +0.000000 | 140→140 |
| steplm_w1 | 28316690.330166 | 28316690.330166 | +0.000000 | 3887→3734 |
| steplm_w3 | 76349457.702494 | 76349276.548443 | -181.154051 | 1043→868 |
| glm_w1 | 21910.766676 | 21355.270630 | -555.496046 | 116291→116006 |
| glm_w3 | 80360.619654 | 79805.123521 | -555.496133 | 54561→54158 |
| gnmf_w1 | 18501.025172 | 18501.025172 | +0.000000 | 774→774 |
| gnmf_w3 | 18521.179041 | 18521.179041 | +0.000000 | 172→172 |
| gmm_w1 | 25042.749584 | 25042.749584 | +0.000000 | 2005→1972 |
| gmm_w3 | 18305.956166 | 18305.956166 | +0.000000 | 810→808 |
| P1_FULL_w1 | 127343291.489023 | 127343291.489023 | +0.000000 | 2794→2790 |
| P1_FULL_w3 | 340637561.839881 | 340637561.839881 | +0.000000 | 1875→1875 |
| P2_PREP_w1 | 7275.921294 | 7275.921294 | +0.000000 | 324→324 |
| P2_PREP_w3 | 6714.429860 | 6714.429860 | +0.000000 | 205→205 |
| ADULT_w1 | 2217941.581178 | 2217941.581178 | +0.000000 | 2315→2293 |
| ADULT_w3 | 5481083.781845 | 5481083.781845 | +0.000000 | 984→984 |
| COVTYPE_w1 | 2579872.304263 | 2579872.304263 | +0.000000 | 2315→2293 |
| COVTYPE_w3 | 6055808.970394 | 6055808.970394 | +0.000000 | 984→984 |

후보 합은13조건에서 감소했다. 전체317815→314625이며, 실행 가능한 전체 계획 수가 아니다. 모든 physical snapshot은 incomplete를 보고했으므로 기록된 선택·명령 비교를 완전한 runtime 동등성 증명으로 사용하지 않는다. 분산 실행과 실측 시간은 측정하지 않았다.

## 남은 문제: if 합류와 원본 배치 equality

L2SVM W1에서 입력 Y를 FOUT으로 유지하면, if를 실행하지 않은 경로가 그대로 FOUT이다. 현재 공통 TR 제약은 새 Y를 계산하는 경로도 FOUT으로 맞춘다. 선택된 정규화 산술은 CP/LOUT이므로 새 Y 업로드가 추가되어 비용이90.855125 증가했다. 소비자 앞 GET은 원래 변수 바인딩을 바꾸지 않으므로 기존의 로컬 정규화·로컬 저장 조합을 복구하지 못한다.

문제 정의: 서로 배타적인 분기의 원본 배치를 같게 강제하는 대신, 각 경로에서 계획된 명시적 이동을 거쳐 합류 배치를 만족할 수 있어야 한다. TW/TR은 전달받은 값의 배치를 보존한다. 분기별 이동 후보, 해당 경로의 실행 빈도, 원본 캐시 공유, privacy와 lowering을 함께 모델링해야 하며 equality만 삭제해서는 안 된다. 이 확장은 이번 커밋에 구현하지 않았다.

나머지 변경 사례: LogReg W1은 caller의 +1을 FED/FOUT에서 CP/LOUT으로 변경, L2SVM W3는 업로드가 loop consumer에서 조건부 정규화 write로 이동, STEP-LM W3는 업로드 제거, GLM W1/W3는 두 matmul이 FED/FOUT에서 FED/LOUT으로 변경됐다. DP-local은 bounded search이므로 후보 축소가 탐색 경로와 선택 비용을 바꿀 수 있다. 전역 최적해를 증명한 결과는 아니다.

## origin/main 통합

기준: `3d0d683c1b` (one-time loop entry materialization). 최신 main의 loop-entry 및 AggLocal 변경을 보존한 별도 worktree에서 이번 작업만 옮겼다. 원래 작업 트리의 다른 미커밋 변경은 포함하지 않았다.

main에는 기존 작업 snapshot의 전역 GET 그룹화가 없어 원래 비용 patch를 그대로 적용할 수 없었다. 따라서 함수 호출 ancestry를 노출하는 최소 의존성과 native alias의 GET 그룹화만 기존 비용 구조에 이식했다. 기존 업로드 계산과 무관한 네트워크·fused 연산 변경은 포함하지 않았다. 이식 전 동일6개 회귀는6개 모두 정확히 두 배의 GET 비용으로 실패했다.

통합 검증 결과는 이 문서 하단과 `SESSION_ISSUES_2026-10-06.md`에 기록한다. 로컬 증거: `/home/mchoi/function-alias-main-publish-20261006/`, 통합 worktree의 `verification/`.


### 통합 최종 검증

- Java17 Maven production/test 컴파일 성공. 관련 **120 tests PASS**, 0 failures. 기존 main에서도 별도 재현한 두 메서드 실패는 제외해 별도 기록했다: `OccurrenceExecutionFrequencyFactsConstantBranchTest.actualBuiltinGlmDeadStraightenXIsZeroAndCgRemainsPositive`(line-sensitive GLM Gram 기대), `ExactNativeLocalAnchorFanoutCostTest.protectedNativeLocalCostSurfaceIsBitAndStructureStable`(기존 fingerprint golden 불일치). 후자는 구조와 raw cost bits 검사는 통과한다.
- `NeutralPlacementGraphUploadRelocationRedTest` 전체 실행은 일부 fixture의 localhost worker metadata 미제공으로 중단했다. 이를 성공으로 계산하지 않았다. 함수 alias/인라인/anchor 전용 테스트와 실제 workload 컴파일은 별도로 통과했다.
- 신규 alias GET 6개는 수정 전 모두 중복 과금으로 실패했고 수정 후 통과했다. 추가 `ExactFunctionAliasActivationEncodingTest`는 duplicate/unresolved event와 producer/consumer masks를 포함한64개 모든 입력 assignment에서 원래 비용과 분해된 비용의 raw bits가 동일함을 검증했다. 잘못된 2-GET 기대를 가진 임시 CP/FOUT 테스트는 제거했으며 통과 수에 포함하지 않는다.
- 최종 production class snapshot으로 **14 workload × worker1/3 = 28/28 Docker compile-only PASS**. WAN-Mid, DP-local, canonical seed. Java engine은 main 통합 소스이고, DML은 frozen 실험 스크립트를 사용했다. main builtin에는 아직 실험용 STEP-LM `max_features`가 없으므로 원래 실험 DML을 검증 자산으로 제공했다. builtin 소스 변경은 이번 커밋에 포함하지 않았다.
- 검증한 production SHA와 현재 소스7파일의 일치를 확인했다. 명령/로그는 통합 worktree `verification/` 및 `/home/mchoi/function-alias-main-publish-20261006/results/B/`; portable 요약은 [validation.json](experiments/function-alias-main-20261006/validation.json).
- 독립 scoped code review APPROVE; Python compile, shell syntax, diff whitespace 검사 PASS. 원래 dirty 작업 트리와 실험 target/JAR는 보존했다. 분산 수치 실행·실측 시간은 검증하지 않았다.
