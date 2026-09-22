# G009 StepLM 원인 분석·수정 실행 보고서

- 작성일: 2026-09-21, 최종 검증 갱신: 2026-09-22
- 기준 계획: [G009_STEPLM_ROOT_CAUSE_AND_REPAIR_PLAN_2026-09-21.md](G009_STEPLM_ROOT_CAUSE_AND_REPAIR_PLAN_2026-09-21.md)
- 상태: **StepLM 수정·작은 회귀 검증·최종 동일 JAR 56/56 planning-only 검증 완료.** GLM 4/4의 전체 초기 planning도 각각 60초 미만이다. 이는 학습 runtime 성공 또는 통계적 성능 향상 증명이 아니다.
- 최종 JAR SHA-256: `f143c28197921ffb2d0c5a2138912bdb7ccb245898a309fc0e039bd29da27f30`. 최종 조건 목록·각 영수증·시간은 `/grid/3/cofee-lm-sweep-mchoi-20260914/g009steplm-final56-f143/status.tsv`, `progress.log`, `audit-result.json`에서 확인한다.

## 1. 원인과 수정

1. **동적 열 선택의 정확한 행 배치 증명 누락.** 기존 `exactFullRowColumnSlice`는 `1:nrow(X)`와 동적 `column_best`를 literal-only 조건으로 배제했다. `isAllRows()`와 다중 ROW partition의 정확한 worker↔row interval을 요구해 행 축만 보존하도록 바꿨다. 출력 열 너비가 같다고 가정하지 않는다. FED right-index에는 음수·역순·범위 초과 global bound 검증을 filter 전에 추가했다.
2. **loop-carried 증명의 SCC identity 분열.** CFG cbind 출력의 pinned root는 `templateRoot=true`인데 동일 root를 향한 backedge는 `false`여서 실제 SCC가 죽은 acyclic leaf로 보였다. pinned 여부를 dependency state에 보존하고 staging template의 정확한 input 의무를 재귀 증명에 포함했다. 외부 근거 없는 cycle을 승인하지 않으며, 임시 loop seed는 direct grounding 단계에서만 쓰고 전체 reaching definition으로 재증명한다.
3. **privacy/physical replay의 권한 부활.** physical rebuild가 기각된 CP row를 복구할 수 있었다. post-physical과 최종 relation 단계에 고정 privacy facts로 privacy closure를 재적용해 provisional authority가 publication으로 넘어가지 않도록 했다.
4. **후속 `lmCG.dml:106` 실패.** 실제 worker4의 기존 `X_global` 오류를 넘은 뒤 `0 - t(X)`에서 실패했다. source `t(X)`의 실행 가능한 FED/FOUT/COL durable realization은 남았지만 direct proof는 원본의 ROW literal anchor만 seed로 조회해 COL 후속 연산의 realization을 모두 잃었다. 직전 입력의 *실행 가능한 정확한* durable realization anchor도 seed로 포함하고 후보별 input binding/proof를 그대로 검사한다. 무관한 worker pool을 강제하지 않는다.
5. **Heuristic 정책 투영의 불완전성.** 공통 그래프에서는 DP-local·FedFirst·DP-global이 성공하지만, AggLocal의 엄격한 demotion/local-prefix 투영은 StepLM의 완전한 후보-도달 가능 할당을 제거했다(`heuristicImpossiblePairs=[]`). 이는 planner별 선호이지 합법성 증명이 아니다. 엄격한 뷰가 실제로 불가능할 때에만 원래 공통 그래프로 돌아가 `MOVEMENT_FIRST` 순서의 첫 완전 합법 플랜을 선택하며 `fallbackUsed=true`와 `POLICY_PREFERENCE_RELAXED`를 기록한다. runtime/legacy fallback이나 privacy 우회는 없다.

## 2. 작은 검증과 실제 canary

- `StepLmDynamicLoopPlacementTest`: protected ROW 4-worker, 동적 column, 초기 정의 + 두 cbind backedge. 세 reaching definition 모두 같은 정확한 reader relation을 보유함을 확인하고 incremental/direct 재계산과 full 재계산의 fingerprint·candidate facts·logical relation을 비교했다.
- `NativePlacementContinuityTest`: 동적 열·symbolic 전체 행·aligned cbind 및 기존 미근거 SCC/불일치 layout 음성 사례. `IndexingFEDRightIndexBoundsTest`: 유효/무효 right-index.
- `StepLmNegatedTransposePlacementTest`: protected transpose 뒤의 elementwise 뺄셈과 함수 경계의 native FOUT 후보.
- 최근 targeted Maven: `StepLmDynamicLoopPlacementTest,StepLmNegatedTransposePlacementTest,HeuristicProtectedNestedDemotionTest,PolicyFirstFeasiblePlacementSelectorTest,NativePlacementContinuityTest,IndexingFEDRightIndexBoundsTest` 통과(61개 중 기존 skip 1개). 로그 `/grid/3/cofee-lm-sweep-mchoi-20260914/g009steplm-heuristic-targeted.log`. `mvn -q -DskipTests package`와 `git diff --check` 통과.
- 진단 JAR `f593761f…`에서 StepLM P2P2D/worker4 planning-only: DP-local·FedFirst·DP-global 성공, AggLocal은 strict policy graph에서 후보-도달 가능 완전 할당 없음. 진단 코드를 제거한 `f143c281…`에서 AggLocal canary 성공 receipt(`success=true`, `runtime_executed=false`, `workers=4`, `execution_seconds=0`). 경로: `experiments/results/planning/g009steplm_heur_f143_20260921_lan_coordinator1/mkl-heuristic-first.json`.
- 동일 중간 JAR의 세 성공은 최종 56건과 합산하지 않는다. 실제 ML training runtime은 실행하지 않았다.

## 3. 최종 56조건 게이트 — 통과

`/grid/3/cofee-lm-sweep-mchoi-20260914/g009steplm-final56-f143/run.sh` 및 무효 시도 뒤의 `resume-after-duplicate-id.sh`는 동일 고정 JAR·manifest로 P2P2D ML 10개×4, P1/P2×4, 작은 SliceLine ADULT/COVTYPE×4를 각각 단회 공식 Docker planning-only로 수행했다. **유효한 56개 행 모두 `rc=0`, `success=true`**이며 미실행·실패·skip은 0이다. 독립 사후 대조에서 56개 고유 run ID와 정확한 workload/dataset/route/config 행렬, planner enum, worker=4, `runtime_executed=false`, `execution_seconds=0`, `forbidden_output_absent=true`, 각 compile config와 coordinator log의 SHA-256이 모두 일치했다. 결과는 `audit-result.json`의 `PASS`, `errors=[]`에 보존했다.

| planner config | GLM 전체 초기 planning | StepLM 전체 초기 planning |
|---|---:|---:|
| `mkl-single-pass` (FedFirst) | 53.290689261초 | 14.809766494초 |
| `mkl-heuristic-first` (AggLocal) | 53.845699407초 | 23.521718765초 |
| `mkl-cost` (DP-local) | 53.786597195초 | 16.928691582초 |
| `mkl-exact` (DP-global) | 57.674614112초 | 14.406992680초 |

시간은 coordinator의 `PlanningFullInitialReceipt.Tplanning_full_initial_nanos`이고 Docker 기동부터 종료까지의 wall-clock이나 학습 runtime이 아니다. GLM 4/4은 각각 `<60,000,000,000ns`를 통과했다. 각 조건 단회이므로 planner 간 미세한 시간 차이를 통계적 우열로 해석하지 않는다.

입력 reference manifest SHA-256은 `e8bb6a11c7ff5cea4cdb57e51e67c14b5abe8eb80fc4ca60f3aae792ad5a040d`다. compile config SHA-256은 config 순서대로 `1cedf5ae786ee4a2247c88d15dc8c839357b123db6f2720a24a16fbe638df29b`, `6f71370af37dfa19ea09613ee20981661ce1eb4c9ab4ab90d286c6ce6e2a7008`, `e24c3b4a4e6675af6c6af0daa7e46ea79a1299dfb922c32594b17b35e89b40e7`, `7a6dc0ee2c6633c4fb952e80ab1ac5ebb695e530ff87cd92d07343250d9805c5`다. 각 행의 receipt에 설정·로그 경로 및 개별 해시가 있다.

환경 오류도 따로 기록한다. 검증 중 공간 확보를 위해 `target/lib`를 컨테이너에 보이지 않는 경로로 옮긴 직후 GLM DP-global 프로세스가 `commons-cli`를 찾지 못했다. 이를 planner 실패나 시간으로 세지 않고 라이브러리를 원위치한 뒤 재시도했다. 첫 재시도는 기존 `RUN_ID`의 provenance 충돌로 실행 전 거절됐으므로 별도 기록하고 새 ID로 다시 시작했다. 무효 시도의 status·coordinator log는 위 디렉터리의 `status-with-invalid-*`, `4_glm_invalid_*`에 남겨 두었으며 유효한 56행에 합산하지 않았다.

## 4. 변경 범위·남은 위험

- production: `NativePlacementContinuity.java`, `NeutralPlacementGraphBuilder.java`, `IndexingFEDInstruction.java`, `HeuristicPlacementAdapter.java`.
- tests: `NativePlacementContinuityTest.java`, `StepLmDynamicLoopPlacementTest.java`, `StepLmNegatedTransposePlacementTest.java`, `IndexingFEDRightIndexBoundsTest.java`.
- 기존 single-worker `StepLmPrivateAggregatePlanningContractTest`의 `lmCG.dml:129` protected TRead는 실제 worker4 `lmCG.dml:106`과 다른 재현으로 분리했다. 이 세션의 4-worker planning-only 성공을 그 테스트의 성공이라고 주장하지 않는다.
- 공통 후보를 정책 목적으로 삭제하지 않았다. 다만 heuristic 선호 뷰가 불가능하면 정책 relaxation이 발생하므로, 이때 선택 플랜은 원래 엄격한 AggLocal 경로와 다를 수 있다. `fallbackUsed`와 정책 fingerprint에서 이를 구별한다.
- 최종 판정은 **계획의 worker4 planning-only·56조건·GLM 60초 게이트 통과**다. 독립적인 전체 plan-space 인증은 여전히 `UNKNOWN`이고, 단회 결과만으로 성능 개선률·privacy-safe 학습 runtime·모든 입력의 정확성을 증명하지 않는다.
