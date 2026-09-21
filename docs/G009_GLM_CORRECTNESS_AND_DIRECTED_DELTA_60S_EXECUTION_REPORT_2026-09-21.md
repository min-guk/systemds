# G009 GLM 정상 planning·60초 계획 실행 보고서

- 날짜: 2026-09-21
- 상태: **핵심 DP-local 정상 planning 목표 달성(단일 관측 45.779초). 전체 계획은 부분 완료:** 성공한 B_correct, 메모리 비교, G4 전체 proof 재사용, G6 지연 관계 소비 및 일부 workload 회귀는 미완료다.
- 계획: [G009_GLM_CORRECTNESS_AND_DIRECTED_DELTA_60S_PLAN_2026-09-21.md](G009_GLM_CORRECTNESS_AND_DIRECTED_DELTA_60S_PLAN_2026-09-21.md)

## 판정 원칙

고정 P2P2D GLM, worker=4, DP-local, planning-only의 정상 전체 초기 planning을 잰다. 실패까지의 시간은 `T_initial`, CandidateE2E 또는 가속률이 아니다. 공통 합법 후보·privacy·authority를 완화하지 않았다. 조건당 한 번 실행하고 별도 원인 진단은 성능 표본으로 취급하지 않았다. 성공한 B_correct가 없으므로 **baseline 대비 가속률은 계산하지 않는다.**

## 현재 단계 상태

| 단계 | 상태 | 근거·한계 |
|---|---|---|
| G0 고정 | 완료 | `/home/mchoi/g009-glm-optimization-20260921/g0-frozen-20260921/`에 HEAD, 시작 patch/JAR·입력·XML·Docker image SHA와 설정 보존. 성공한 B_correct는 없음. |
| G1 정상성 | DP-local·DP-global 전체 완료 | all-row ROW slice, 호출별 실제 함수 특수화, Nary 다중 local broadcast를 순차 수정했다. single-range ROW가 runtime에서 FULL로 재형식화되는 경우도 guard로 제외한 뒤 최종 JAR에서 두 selector의 정상 receipt를 확인했다. 특수화는 literal-zero 직접 호출과 제한된 복제에 한정된다. |
| G2 방향성 | 구현·통합 성공, 단독 속도 미판정 | fact-only direct closure의 compiled/CFG/support 의존성을 producer→consumer로 변경. 변경 전후 support edge와 value-version alias 보존. diamond·cycle·function-style reaching·삭제/추가 및 기존 full-vs-incremental 테스트 포함 12건 통과. 단독 속도 이득은 성공 B_correct 부재로 주장하지 않는다. |
| G3 delta source index | 구현·통합 성공, 단독 속도 미판정 | 변경 owner의 native refs/realizations/executable membership만 재구축. 중복 reference는 전수 rebuild fallback, signature는 refcount, 순서는 per-owner 유지. 관련 테스트 14건 통과. 전체 binder/continuity는 아직 전수 순회하므로 전체 O(Δ) 주장은 불가. |
| G4 physical/proof 재사용 | 일부 구현·미완료 | post-CFG physical dirty-cone 내 statement block의 `Hop→Node` lookup을 rebuild마다 전수 생성하지 않고 revision-local로 재사용·변경 row 갱신. 14건 회귀 통과. `nextRevision`의 기존 row-equality/cache reuse는 유지하나 모든 proof/SCC를 넘기는 완전한 G4는 아님. topology 동일만으로 proof를 재사용하지 않음. |
| G5 상수 분기 | 구현·통합 성공, 단독 속도 미판정 | `IPAPassPropagateReplaceLiterals`에서 If predicate를 body의 조건부 대입 전에 incoming constant로 평가. main·single-literal function·서로 다른 두 callsite 회귀 3건 통과. GLM에서 실제 제거 분기 수·단독 기여량은 계측하지 않았다. |
| G6 exact support 압축 | 경계 감사 완료·미구현·미채택 | producer만 lazy로 바꾸면 builder·DP model/cost/fingerprint·receipt rank가 다시 전체 product를 전개한다. 최종 DP-local의 selection은 0.742초로 확인되었으나 analysis는 36.676초다. G6 전체 계약 변경은 별도 독립 oracle과 소비자 수정 없이는 안전하지 않다. 후보를 삭제하거나 eager product를 단순 치환하지 않음. |

## G1 진단 사실

1. 사전 후보 JAR `209cc3b6…`은 `Y_prob:939`에서 coordinator 97.66초 후 실패. 원래 JAR `8f10f189…`도 같은 오류에서 182.87초 후 실패했다. 둘 모두 정상 planning 시간이 아니다.
2. 첫 gated GLM 진단 JAR `472800600192…`: `Y_prob:939` TRead의 reaching writer는 line917 TWrite 하나. reader는 PRIVATE_AGGREGATE로 CP/LOUT가 제외되고 FED/FOUT ROW가 필요하나 replay가 CP 대안만 만들었다.
3. 둘째/셋째 진단에서 line917 writer는 한때 executable durable FED realization 2개와 native proofs를 갖다가 post-physical replay에서 staging NATIVE_LINEAGE 하나로 퇴화했다. 마지막 진단 JAR `d45deaeea2e5…`에서 직전 cbind/rowSums/div의 FED witness가 모두 0이며, call stack은 `closeCfgTransientCandidateDependenciesMeasured`의 physical-direct 이후 relation replay를 가리켰다. 이는 **관찰된 권한 소실**이지 그 권한을 무조건 보존해도 안전하다는 증명이 아니다.
4. G2/G3/G5 통합 JAR SHA-256 `7ee522d33e356d8ccf82be3238c421cab939b48197d6161c638a32a9436d07ff`의 공식 planning-only 단일 실행은 `glm_initialize:609`의 protected `TRead linear_terms`에서 coordinator `real 10.75s` 후 실패했다. 전체 harness wall `49.32s`에는 setup/cleanup이 포함된다. 둘 다 성능 성공 기준선이 아니다.
5. 같은 JAR·입력의 off-by-default `PlannerCandidateSpaceAudit` 진단은 privacy 전 line609 TRead가 CP/LOUT만 갖고, capability `TRead/NO_FED_INPUT`, `producerOutputs=[]`임을 기록했다. privacy는 PRIVATE_AGGREGATE에서 CP를 정당하게 제외했다. Audit: `/home/mchoi/g009-glm-optimization-20260921/g1audit-tmp/cell-1/planning-only/g1audit/candidate-space-87.jsonl`.
6. 동일 generated script를 로컬 compile한 CFG 진단에서 line609 TRead의 유일한 reaching definition은 line586 `TWrite linear_terms`이고 function-input skip은 아니다. line586의 FED/FOUT ROW/BROADCAST fact는 존재할 수 있으나 executable FED realization이 0인 상황을 확인했다. 따라서 현재 검증 대상은 line586과 입력 함수 경계까지의 실제 native witness 연결이다.
7. 로컬 protected compile에서 `Y[,1]`(`glm.dml:532`)의 all-row ROW 열 슬라이스는 입력 ROW worker map과 행 분할을 보존할 수 있는데, 기존 `NativePlacementContinuity.operationPreservesWitness`는 FULL 단일 endpoint만 허용했다. all-row·정적 열 범위·정확한 ROW 입력·local bound scalar로 제한한 증명 수정 후 첫 line609 실패를 넘겼다. 부분/동적 행 슬라이스는 여전히 거절하는 타깃 테스트가 통과했다. 이는 공식 GLM 성공 실행이 아니다.
8. 다음 로컬 실패는 함수 formal `linear_terms`(`glm.dml:913`)이다. CFG상 ordinary reaching writer가 아니라 function input이다. 중간 `glm_log_likelihood_part:866`의 formal은 세 caller argument를 공유하는데, `glm_initialize:609`은 ROW witness, loop `m_glm:354` matmul도 ROW witness인 반면 초기 `m_glm:290` 인수는 compiled `dg(rand):tmp` CP/LOUT만 가진다. 이 DataGen은 50,000×1, min=max=0인 public local 영행렬이다. `LogicalBoundaryRealizations.supportedPools`는 모든 caller에 공통 FOUT pool을 요구하여 FED proof가 0이다. 현행 CP/FOUT action은 producer의 직접 PRESENT anchor를 요구하며 이 zero에는 없고, FUNCTION_CALL relocation도 금지다. 따라서 후보에 FED만 추가하는 것은 불법이다. 명시적 pre-call 업로드와 receipt/cost/runtime 연결 또는 호출별 함수 특수화가 필요하다.
9. 호출별 함수 특수화를 추가해 해당 local-zero 호출만 실제 DML 함수·중첩 callee 복제본으로 보내고 나머지 호출은 기존 함수를 유지했다. frozen GLM HOP 검사에서 `glm_log_likelihood_part` 호출 3개 중 zero 호출 1개가 분리되고 `binomial_probability_two_column`도 별개 복제됨을 확인했다. 국소 테스트 2건을 포함한 관련 7건 통과. 같은 protected local 분석은 line913을 넘겨 `glm_dist:718` Nary MULT에서 22.756초 후 실패했다. 이 시간 역시 성공 성능 수치가 아니다.
10. line718의 off-by-default 후보 감사(`/tmp/g1-dist718-space-audit/candidate-space-1301267.jsonl`)는 privacy 전 CP/LOUT만 있고 두 candidate rule 모두 `BROADCAST_CONSTRAINT`임을 기록했다. `Y * (Y_prob %*% flip_pos) * Y_prob`의 두 local matrix를 당시 `BuiltinNaryFEDInstruction`가 한 개만 broadcast할 수 있어 oracle도 FED를 거절했다. privacy는 CP를 정상적으로 제외했다. 이는 후보 공간 삭제가 아니라 당시 runtime 연산 능력 제약이었고, 다음 단계에서 별도 회귀를 거쳐 수정했다.
11. `BuiltinNaryFEDInstruction`가 각 local matrix에 독립 sliced broadcast ID를 부여하고 둘 이상일 때 기존 `FederationMap.executeMultipleSlices`를 쓰도록 수정했다. Oracle은 **개수 제한만** 제거했고 FULL 단일 분할·정렬 제한은 유지한다. 담당 범위 표적 테스트 14건 통과. 동일 frozen protected GLM 로컬 `buildAnalysis`를 다시 실행한 결과 **55.540808348초 후 성공**했다(`/tmp/g1-after-nary-analysis.log`, SHA-256 `ee301a06…`). 이 실행은 candidate-space audit가 켜진 **analysis-only**이며 selector·receipt·LOP 생성·공식 Docker 조건을 포함하지 않으므로 `T_initial` 또는 60초 달성 증거가 아니다.
12. 후속 리뷰에서 ROW map이 단일 range일 때 runtime `FederationMap.filter`가 FULL로 재형식화하는 반례를 발견했다. all-row ROW 열 slice witness 허용은 **서로 겹치지 않는 복수 ROW range**로 제한했다. 단일-range 반례는 수정 전 red, 수정 후 green이고 `NativePlacementContinuityTest` 전체 targeted 재검증이 통과했다.

## 최종 동일 조건 실행과 기준선 판정

모든 GLM 실행은 같은 생성 DML SHA-256 `8df7a7a608c3fbea858b88eb07366dee9e23e5650cbaae5e18163914e0fda4a7`, P2P2D, worker=4, 16 GiB coordinator JVM, 최종 JAR SHA-256 `815e75aec0db38e6131e423f1675f841198e1f65800fa0de13711a52d7533331`, planning-only, 설정별 1회다. 입력·설정의 전체 고정값은 G0 artifact와 각 receipt에 있다. `T_initial`은 JVM 내부 `PlanningFullInitialReceipt`이며 command wall과 다르다.

| 조건 | 최종 상태 | `T_initial` | CandidateE2E | 근거·한계 |
|---|---|---:|---:|---|
| GLM DP-local (`mkl-cost`) | **정상 성공**, runtime 미실행 | **45.779340355초** | 44.023057896초 | receipt `success=true`, `runtime_executed=false`, `execution_seconds=0`; log SHA-256 `5efe713fca69a938660bcdb2bff1be2555158e5239eb9acabd11cb5619750b3f` |
| GLM DP-global (`mkl-exact`) | **정상 성공**, runtime 미실행 | **44.644206309초** | 42.710041073초 | 별도 단일 실행, 동일 JAR·입력; planner `COMPILE_EXACT` |
| GLM heuristic (`mkl-heuristic-first`) | **180초 제한 종료** | 없음 | 없음 | `analysis_end` 후 `planner_begin`까지 진행, selector/receipt 미완료. timeout은 privacy 불가능 판정이나 완료 시간 아님 |
| `B_correct` 시도 | **실패** | 없음 | 없음 | frozen pre-G1 patch에 G1 정확성 수정만 overlay한 JAR `4873d69d…`가 `glm_initialize:586`의 protected `TRead y_corr` transient replay 실패. 통합 C와 공간/정확성 상태를 맞춘 성공 baseline이 아님 |

DP-local receipt: `/home/mchoi/g009-glm-optimization-20260921/g1final-results/planning/g009-glm-g1final-20260921_lan_coordinator1/mkl-cost.json` (SHA-256 `9717f40ac8c55d9ffc090591f963250276355930879cc1ae007df63f4be1c252`); compile config SHA-256 `e24c3b4a4e6675af6c6af0daa7e46ea79a1299dfb922c32594b17b35e89b40e7`. DP-local phase exclusive: analysis **36.675915907초**, model 1.430957011초, cost surface 0.570062375초, optimizer 2.531625162초, selection **0.741519052초**, conversion 1.779425300초. `Total compilation time`은 45.782269초다. CandidateE2E는 `T_initial`의 부분 구간이므로 합산하지 않는다.

DP-global receipt는 `/home/mchoi/g009-glm-optimization-20260921/g1exact-results/planning/g009-glm-g1exact-20260921_lan_coordinator1/mkl-exact.json`이고, heuristic timeout log는 `/home/mchoi/g009-glm-optimization-20260921/g1heur-results/fed4/mkl-heuristic-first/`에 있다. 두 selector의 약 1.135초 차이는 단일 실행이므로 우열/통계적 가속률로 해석하지 않는다. 별도 `B_correct` 재구성 patch/JAR와 실패 log는 `/home/mchoi/g009-glm-optimization-20260921/bcorrect-artifact/`, `bcorrect2-results/`에 보존했다. 실패까지 약 130초는 속도 기준선이 아니다.

P1/P2의 최종 JAR harness 단회 회귀를 시도했으나 `run_LAN_docker.sh`가 `--salg P1_FULL`와 `--alg P1_FULL` 모두 허용하지 않아 coordinator 시작 전에 거절했다. script/file 존재·harness 검증 경로가 맞지 않는 상태에서 직접 Docker 실행을 공식 receipt로 대체하지 않았다. SLICELINE도 frozen workload 목록에 없어 공식 경로에서 미지원이다. 따라서 세 workload의 최종 JAR 회귀는 **미검증**이다.

## G6 계약 감사

`NativePlacementContinuity`의 support Cartesian product를 표현만 압축해도 `PlacementAnalysis` realization canonicalization, builder factorization, `ExactPhysicalModel`의 clause별 alternative, `ExactPhysicalCostModel`의 dense arrays 및 cost-surface fingerprint, `PlacementAnalysis`의 전체 rank 초기화가 다시 펼친다. 따라서 G6 완료에는 OR-of-products의 상관관계·순서·authority·owner를 보존하는 관계 표현, builder/validator의 관계 연산, DP-local domain/cost/fingerprint의 지연 소비, 선택된 receipt의 안정적 identity/rank가 모두 필요하다. 작은 독립 전수 oracle 및 eager/lazy DP winner·receipt 동치가 선행되어야 한다. 현재는 구현·채택·성능 개선을 주장하지 않는다.

## 검증과 남은 기준

- 최종 관련 suite 12개 class, **94 case 중 92 실행·2 skip, failure/error 0**. `NativePlacementContinuityTest`는 38건 중 37 pass·1 skip, `GlmProtectedTransientReplayTest` 2건 pass; single-range ROW 반례는 수정 전 red→수정 후 green. 다중 local Nary 대상 14건 및 runtime `BuiltinNaryFEDInstructionBroadcastTest` 2건도 별도 통과했다. G5의 서로 다른 두 callsite 등 3건, G2/G3/G4 합계 targeted 14건이 포함된다. `OracleFacadeTest` 전체에는 별도 `binaryFullMatrixWithLocalMatrixDoesNotRequireEncodedWidth` 실패가 있어 suite-wide green이라고 주장하지 않는다.
- `mvn -q -DskipTests package` 성공, 최종 `target/SystemDS.jar` SHA-256 `815e75aec0db38e6131e423f1675f841198e1f65800fa0de13711a52d7533331`; `git diff --check` 통과. repo 전체 lint/별도 정적 분석 및 독립 GLM 전체 공간 oracle은 실행하지 않았다. 작은 oracle과 full-vs-incremental 회귀는 대규모 모든 결합의 완전성 증명이 아니다.
- **충족:** 동일 고정 GLM DP-local 정상 전체 초기 planning 45.779초 <60초, runtime 미실행, CandidateE2E와 selector 분리. **미충족/미증명:** 성공 B_correct 대비 개선률, peak memory +5% 기준, heuristic 완료, P1/P2/SLICELINE 최종 JAR 공식 회귀, G4 전체 proof/SCC 재사용, G6 관계 기반 압축·지연 소비. 목표의 핵심 시간 조건 달성과 계획 전체 완료를 구분한다.
- G1에 대한 기능 수정 없이 staging FED를 executable로 승격시키거나 privacy guard를 우회하지 않았다. G4/G6도 권한/결합 증명 전에는 완료·채택이라고 표기하지 않는다.

## 변경 파일(이번 계획에서 추가/수정한 범위)

- `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`: G2, G3, G4 revision-local physical block lookup. 임시 G1 진단 코드는 제거했다.
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java`: G1의 제한된 all-row ROW slice witness 증명.
- `src/main/java/org/apache/sysds/parser/FederatedLocalZeroCallsiteSpecializer.java`, `DMLTranslator.java`, `FunctionOp.java`, `Hop.java`: G1의 실제 호출별 local-zero 함수 특수화와 원본 유지.
- `src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java`, `src/main/java/org/apache/sysds/runtime/instructions/fed/BuiltinNaryFEDInstruction.java`: G1 다중 local matrix Nary broadcast의 oracle/runtime 일치.
- `src/main/java/org/apache/sysds/hops/ipa/IPAPassPropagateReplaceLiterals.java`: G5.
- `src/test/java/org/apache/sysds/hops/fedplanner/placement/DirectedDirectClosureDirtyConeTest.java`, `DirectSourceIndexRevisionTest.java`, `ConditionalLiteralPredicateRewriteTest.java`, `GlmProtectedTransientReplayTest.java`, parser specializer 및 runtime Nary broadcast tests.
- 진단 실행/입력/JAR 보존: `/home/mchoi/g009-glm-optimization-20260921/`. 임시 CFG 진단 테스트와 off-by-default 출력은 최종 소스에서 제거했다.
