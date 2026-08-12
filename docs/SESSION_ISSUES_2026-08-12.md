# Session issues — 2026-08-12

## 1. LM의 DP/MinST가 수렴 루프의 안전 상한을 실제 반복 횟수로 비용화함

- **상태**: 수정 완료, 새 Docker JAR 검증 진행중
- **적용 원칙/제약**: 후보를 workload/opcode 가드로 닫지 않고 공유 비용 사실을 수정한다. DP의 부모-자식 국소 최적성 한계는 허용하지만 DP/MinST가 공유하는 반복 비용의 오류는 허용하지 않는다.
- **환경/조건**: Docker WAN-Light, P2P2D LM, workers=1..4, planner DP/MinST, 수정 전 source commit `b5ef1ae40bf8cbf78e054c6e0033f02ab121d009`.
- **재현 절차**: `/home/mchoi/g014-full-results-b5ef1ae-639649e-20260811-v1/rows.jsonl`에서 LM 행을 조회한다. 계획만 재현하려면 격리 stage의 `run_LAN_docker.sh --planning-only --net-profile wan_light --workers <n> --dataset P2P2D --conf mkl-cost|mkl-min-st-cut --alg lm`을 사용한다.
- **관측 증상**: workers=1에서 DP/MinST 59.238/58.964초, Heuristic/FedAll 19.522/20.185초였고 workers=2에서도 DP/MinST 20.313/19.917초, Heuristic 10.651초였다. `scripts/builtin/lmCG.dml`은 `while(i < max_iteration & norm_r2 > target)`이며 `max_iteration`의 안전 상한은 2100이지만 실제 실행은 약 45회에 수렴한다.
- **원인 분석**: `RewireConstants.estimateWhileLoopWeight`가 수렴 조건과 결합된 induction cap 2100을 기대 반복 횟수 2099로 취급했다. 따라서 DP/MinST가 루프 안 FED compute/network cost를 수십 배 과대평가하고, 반복 구간에서 X를 FED로 유지하는 plan보다 CP materialization을 선택했다. 추가로 exact induction guard 두 개의 AND에서 더 큰 bound를 택해 종료 의미와 반대였다.
- **해결 요약**: predicate가 오직 해석 가능한 induction comparison으로 구성되면 exact count를 사용한다. 데이터 의존/수렴 조건이 하나라도 있으면 induction/body 값은 상한으로만 취급하고 `min(cap, DEFAULT_LOOP_WEIGHT)`를 기대 횟수로 사용한다. exact induction AND는 첫 번째로 소진되는 작은 bound를 사용하며, epsilon 같은 1 미만 상수는 iteration cap으로 보지 않는다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/RewireConstants.java`, `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerDpRewireTransTableTest.java`.
- **검증**: `FederatedPlannerDpRewireTransTableTest`의 수렴+2100 cap 회귀와 두 exact counter AND 회귀를 포함한 표적 Maven 테스트가 통과했다. 새 JAR의 planning-only trace에서 LM loop weight와 선택 plan이 바뀌는지, 이후 Docker runtime에서 DP/MinST가 개선되는지는 아직 검증중이다.
- **잔여 이슈**: generic expected count 10은 관측 기반 모델이 아니라 보수적 기본값이다. 최종 runtime 정렬이 깨지면 후보를 닫지 않고 trace의 loop multiplicity와 실제 반복 횟수 차이를 다시 측정한다.
- **잠재 회귀 위험**: 수렴이 느린 알고리즘은 cap보다 훨씬 작게 비용화될 수 있다. exact-counter와 convergence-counter 회귀를 분리하고, planner trace의 loop weight와 dynamic instruction multiplicity를 비교해 감지한다.
- **의사결정 근거**: Oracle/후보 공간이 아니라 DP와 MinST가 공유하는 cost fact producer의 의미를 바로잡았다.

## 1-1. LM에서 선택된 FED XtXv가 Lop 생성 중 CP mmchain으로 강등됨

- **상태**: 수정 및 새 Docker JAR canary 검증 완료
- **적용 원칙/제약**: runtime fallback 없이 planner가 선택한 실행 위치를 컴파일된 instruction이 그대로 보존해야 한다. 합법 후보를 닫지 않고 HOP→Lop lowering의 실행 위치 손실을 수정한다.
- **환경/조건**: Docker WAN-Light LM, P2P2D, workers=1, planner DP/MinST, `-noFedRuntimeConversion`, source commit `537b788e3c`의 planning-only trace.
- **재현 절차**: `run_LAN_docker.sh --planning-only --skip-net-check --net-profile wan_light --workers 1 --dataset P2P2D --conf mkl-cost --alg lm`을 실행하고 `lmCG.dml:129`의 `Emission-Select`와 출력된 runtime program의 `mmchain` instruction을 비교한다.
- **관측 증상**: DP emission은 `t(X)`, 내부 `ba+*`, 외부 `ba+*`를 각각 `FED/FOUT`, `FED/FOUT`, `FED/LOUT`으로 선택했다. 그러나 runtime program에는 `CP mmchain X p ... XtXv`가 생성되었고, 이전 runtime instruction fingerprint에도 반복문의 `fed_mmchain`이 없었다. workers=1에서 DP/MinST가 약 59초인 반면 Heuristic/FedAll은 약 20초였다.
- **원인 분석**: `AggBinaryOp.constructLops()`는 선택 exec type이 FED여도 MAPMM_CHAIN 경로에서 `constructCPLopsMMChain`을 호출했고, 이 메서드는 `MapMultChain`을 무조건 `ExecType.CP`로 생성했다. 일반 runtime conversion은 CP mmchain을 FED로 바꿀 수 있지만 본 실험은 planner 계획을 강제하기 위해 `-noFedRuntimeConversion`을 사용하므로 강등된 CP instruction이 그대로 실행됐다. 또한 직접 FED mmchain을 파싱하는 `FEDInstructionParser` 경로가 연결되지 않았다.
- **해결 요약**: 선택된 `ExecType`을 `MapMultChain`까지 전달하고 FED일 때 `FED mmchain ...` instruction을 직접 생성하도록 했다. FED parser에 MMChain을 연결했다. 직접 FED mmchain은 정확한 `ROW` 또는 range가 하나인 정확한 `FULL`만 허용하며, `FType.isType`의 포함 관계를 사용하지 않아 multi-range FULL을 ROW로 오인하지 않는다. runtime conversion을 다시 켜거나 fallback을 추가하지 않았다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/AggBinaryOp.java`, `src/main/java/org/apache/sysds/lops/MapMultChain.java`, `src/main/java/org/apache/sysds/runtime/instructions/FEDInstructionParser.java`, `src/main/java/org/apache/sysds/runtime/instructions/fed/MMChainFEDInstruction.java`, `src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java`, `src/test/java/org/apache/sysds/test/component/federated/FederatedDagExactRefedInputProjectionTest.java`, `src/test/java/org/apache/sysds/test/component/federated/MMChainFEDInstructionCompileTest.java`, `src/test/java/org/apache/sysds/test/functions/fedplanner/rules/MMChainRuleTest.java`.
- **검증**: planner-selected FED XtXv의 Lop exec type/instruction/parser를 검사하는 component 회귀와 `ROW`, single-range `FULL`, multi-range `FULL`, `COL` capability 회귀를 포함한 6개 표적 테스트 클래스가 통과했다. `mvn -DskipTests -Dcheckstyle.skip=false validate`와 `mvn -DskipTests package`도 통과했다. immutable stage `25b292...`의 WAN-Light/worker=1 planning-only에서 DP와 MinST가 동일한 runtime-plan digest와 `FED mmchain`을 생성했다. DP Docker runtime canary는 cold/warm 5.321/3.155초, `fed_mmchain` 45회, `CP mmchain` 0회로 성공했다(수정 전 DP 약 59초).
- **잔여 이슈**: weighted chain(XtwXv/XtXvy)도 동일 lowering 경로를 사용하므로 전체 336셀에서 실제 workload instruction을 확인한다. fused mmchain cost가 unfused 두 `ba+*` 비용과 얼마나 일치하는지도 최종 runtime 결과와 비교한다.
- **잠재 회귀 위험**: FED parser 형식이나 output placement가 어긋나면 compile/runtime failure가 난다. parser unit, planning receipt의 runtime-plan fingerprint, `-noFedRuntimeConversion` Docker canary로 감지한다.
- **의사결정 근거**: planner/Oracle 선택은 이미 FED였으므로 비용이나 후보군을 왜곡하지 않고 lowering contract만 수정했다.

## 2. KMeans 그래프에 16개 중 10개 점만 존재함

- **상태**: planning canary 4/4 완료, 전체 immutable campaign 재측정 예정
- **적용 원칙/제약**: 서로 다른 source commit의 결과를 한 그래프에 섞지 않는다. 실패/누락 셀을 과거 결과로 채우지 않고 동일 Docker/JAR에서 실행한다.
- **환경/조건**: Docker WAN-Light, P2P2D KMeans, workers=1..4, planner 4종, 수정 전 source commit `b5ef1ae...`.
- **재현 절차**: `/home/mchoi/g014-full-results-b5ef1ae-639649e-20260811-v1/rows.jsonl`에서 `profile=wan_light`, `workload=kmeans`, `systemds_commit=b5ef1ae...`만 집계한다.
- **관측 증상**: 동일 commit KMeans runtime row는 10/16개다. workers=1 DP/FedAll/MinST와 workers=2 DP/FedAll/Heuristic은 predecessor commit의 completion-only row라 최신 그래프가 의도적으로 제외했다. 별도 planning-only 감사에서는 workers=1..2 × 4 planner의 8/8 receipt가 생성되어 planning 자체의 그래프 누락은 없었다.
- **원인 분석**: plotting/NaN 문제가 아니라 immutable source boundary를 지키면서 아직 실행하지 않은 성능 셀이다. 과거 캠페인의 부분 재개가 commit 변경 시 성공 prefix를 재사용하지 못하도록 한 provenance 규칙이 정상 작동한 결과다.
- **해결 요약**: 새 source/harness commit으로 생성한 하나의 immutable stage에서 전체 336 셀을 한 번씩 실행한다. 새 캠페인은 KMeans 16/16과 전체 matrix 336/336을 완료 조건으로 검사한다.
- **수정 파일**: source 수정 없음. 최종 검사는 harness의 campaign row/receipt 감사기에 둔다.
- **검증**: 수정 전 동일-commit completeness 10/16을 확인했다. 새 stage `25b292...`의 worker=1 planning-only에서 DP/FedAll/Heuristic/MinST 4/4가 모두 성공하고 서로 독립적인 인증 receipt를 생성했다.
- **잔여 이슈**: 새 JAR runtime 16/16 완료 전까지 KMeans 성능 곡선은 검증 완료로 간주하지 않는다.
- **잠재 회귀 위험**: predecessor 결과를 조용히 합치면 누락은 사라져 보이지만 plan/JAR provenance가 깨진다. source/harness/stage digest와 cell key의 유일성으로 감지한다.
- **의사결정 근거**: 플래너 로직 수정이 아니라 실험 provenance/completeness 문제로 분류했다.

## 3. LogReg FedAll workers=1(FULL)만 거대한 transpose 다운로드/재업로드 plan을 선택함

- **상태**: 수정 및 worker=1 Docker runtime canary 검증 완료
- **적용 원칙/제약**: runtime이 실제 지원하는 합법 후보를 닫지 않고 Oracle 누락을 보완한다. runtime fallback/암묵적 materialization은 추가하지 않는다.
- **환경/조건**: Docker WAN-Light LogReg, P2P2D, FedAll, workers=1(FType.FULL) 대 workers=2..4(row partitioned), 수정 전 commit `b5ef1ae...`.
- **재현 절차**: 수정 전 warm log `/home/mchoi/g014-full-results-b5ef1ae-639649e-20260811-v1/cells/056-6a95be49c473/phases/cell-1/warm-fresh-coordinator-jvm/raw_coordinator.log`와 해당 planning receipt를 확인한다.
- **관측 증상**: workers=1 FedAll은 9204.099초인데 workers=2..4는 34.480~37.431초였다. runtime plan은 `FED r' X -> FOUT`, `CP prefetch transpose`, `FED ba+* local-transpose FOUT-RHS -> FOUT`을 만들었고, 약 840MB transpose를 coordinator로 내린 뒤 같은 worker로 다시 올렸다. `fed_ba+*` 누적이 8667초/532회였다.
- **원인 분석**: `BinaryMMRule`은 FULL×local/local×FULL FOUT만 표현하고, 같은 single worker에 이미 존재하는 FULL×FULL 직접 FOUT capability를 누락했다. “가능한 FOUT을 모두 선택”하는 FedAll은 비용 기반 planner가 아니므로, FOUT을 유지할 수 있는 유일한 우회 후보인 거대 CP materialization+upload를 철학대로 선택했다. runtime `AggregateBinaryFEDInstruction`은 alignment와 complete co-location을 확인한 뒤 remote IDs로 FULL×FULL을 직접 실행할 수 있었다.
- **해결 요약**: `ShapeHint.fullSinglePartition=true`인 FULL×FULL BinaryMM에 `FED/FOUT/FULL` capability를 추가했다. 기존 FULL×local/local×FULL도 동일한 single-partition 증거가 있을 때만 허용하도록 명시해 다중 range FULL을 과도하게 열지 않았다. runtime은 수정하지 않았다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java`, `src/test/java/org/apache/sysds/test/functions/fedplanner/rules/BinaryMMTsmmRuleTest.java`, `src/test/java/org/apache/sysds/test/component/federated/AggregateBinaryFoutRuntimeTest.java`.
- **검증**: Oracle rule test와 runtime direct-execution test가 통과했다. runtime test는 output이 1-range FULL 2×4이고 양쪽 input에 PUT_VAR/GET_VAR가 없음을 검사한다. source commit `537b788e3c`의 Docker planning-only 로그에서 FedAll worker=1의 기존 `FED r' X -> CP prefetch(거대 transpose) -> FED ba+*` 경로가 사라지고 FULL×FULL direct FOUT이 선택됨을 확인했다. 새 stage `25b292...` runtime canary도 cold/warm 70.303/66.589초로 성공해 수정 전 9204.099초의 worker=1 폭증이 제거됐다.
- **잔여 이슈**: `fullSinglePartition`은 각 FULL input의 single range를 증명하지만 서로 다른 source worker 간 물리 co-location 자체는 별도 anchor/alignment fact다. 현재 workload generator는 X/Y를 같은 worker에 배치하고 runtime도 fail-fast alignment 검사를 한다. 일반 cross-source FULL co-location 증거는 별도 모델 확장 후보다.
- **잠재 회귀 위험**: 서로 다른 worker의 single-range FULL 두 개를 planner가 compatible로 오판할 수 있다. 다른-address FULL×FULL runtime failure 회귀와 emitted anchor signature 비교로 감지한다.
- **의사결정 근거**: planner gate를 완화한 것이 아니라 이미 runtime이 지원하는 co-located FULL×FULL capability를 shared Oracle에 표현했다.

## 4. L2SVM workers=1 FedAll 폭증과 Heuristic의 vector aggregation 후 정책 차이

- **상태**: FedAll 공통 원인 수정 및 Heuristic 정책 차이의 새 Docker runtime 검증 완료
- **적용 원칙/제약**: vector×federated 연산의 실제 output constraint를 유지한다. Heuristic은 LOUT으로 demote한 occurrence만 REFED를 금지하고 나머지는 FedAll과 같은 feasible-FOUT 후보군을 사용한다.
- **환경/조건**: Docker WAN-Light L2SVM, workers=1..4, planner 4종, 수정 전 commit `b5ef1ae...`.
- **재현 절차**: 위 격리 캠페인의 L2SVM rows와 `/home/mchoi/g014-planning-audit-stage-639649e-b5ef1ae-20260811-v1/.../results/planning/diag-b5-wanlight-l2svm-w{1,2}-*/` receipt/log를 비교한다.
- **관측 증상**: FedAll workers=1은 1731.253초, workers=2..4는 약 83초였다. worker=1 plan에도 LogReg과 같은 `FED r' X FOUT -> CP prefetch -> FED ba+*` 우회가 있고 `fed_ba+*` 누적 57.286초/61회가 async prefetch 대기를 포함했다. 반면 Heuristic은 workers=1/2 모두 vector aggregation 이후의 `ba+*` 네 occurrence를 명시적으로 demote하여 FedAll과 다른 plan을 이미 생성했다.
- **원인 분석**: FedAll anomaly는 이 workload만의 vector aggregation 규칙 문제가 아니라 이슈 3과 동일한 BinaryMM FULL×FULL Oracle omission이다. Heuristic과 FedAll이 동일하다는 가설은 planning trace와 일치하지 않았다. workers=1 Heuristic의 추가 static relocation은 FULL layout과 loop/recompile multiplicity에서 발생하며 exact occurrence 단위 검증이 필요하다.
- **해결 요약**: FULL×FULL direct FOUT capability 수정은 LogReg/L2SVM에 공통 적용된다. Heuristic의 vector aggregation demotion 로직이나 항상-local output constraint는 변경하지 않았다. 새 캠페인 receipt가 `Heuristic-PolicySummary`와 exact trace를 모든 셀에 보존하도록 harness 계약을 강화했다.
- **수정 파일**: 이슈 3의 source 파일들과 harness의 `run_LAN_docker.sh`, `code/distributedExpNew.sh`, `tools/planning_receipt.py`, `tools/run_one_pass_performance.py` 및 회귀 테스트.
- **검증**: 기존 JAR planning-only에서 L2SVM worker=1/2 각각 네 `ba+*` Heuristic demotion을 확인했다. 새 stage `25b292...`에서도 FedAll/Heuristic plan digest가 다르고, FedAll은 `fed_fout`, Heuristic은 `fed_refed` 경로를 생성했다. runtime canary warm은 FedAll 55.754초, Heuristic 55.933초로 모두 성공했으며 수정 전 FedAll 1731.253초 폭증은 제거됐다. Heuristic runtime에는 `fed_refed` 61회가 실제 실행되어 단순 로그 차이가 아니라 실행 경로 차이임을 확인했다.
- **잔여 이슈**: worker=1에서 두 합법 경로의 runtime은 0.179초(0.32%) 차이로 사실상 동률이다. workers=1..4 전체에서 plan 차이와 runtime 근접성의 관계를 최종 receipt/instruction 통계로 검토한다.
- **잠재 회귀 위험**: FULL fix 과정에서 vector aggregation의 FED→LOUT-only capability를 잘못 FOUT으로 열면 불법 plan이 생긴다. Oracle ReasonCode, emission trace, runtime instruction을 함께 검사한다.
- **의사결정 근거**: vector 규칙은 유지하고, 별개의 BinaryMM capability 누락만 shared Oracle에서 수정했다.

## 5. 모든 성능 셀에서 planner 선택→emission→runtime plan 증거를 강제하지 않음

- **상태**: harness 수정 및 단위 검증 완료, 실제 Docker cold-runtime canary 진행 예정
- **적용 원칙/제약**: primary runtime은 trace가 꺼진 warm fresh coordinator JVM으로 유지한다. 증거 수집이 metric을 오염시키지 않도록 traced cold phase와 metric warm phase를 분리한다.
- **환경/조건**: 기존 `b5ef1ae` 캠페인은 `CAMPAIGN_PLAN_EXPLAIN=1`, `CAMPAIGN_PLANNER_TRACE=0`; runtime fingerprint는 있으나 planner policy trace가 없음.
- **재현 절차**: 기존 row의 `cold_evidence`/`warm_evidence`와 Docker command environment를 확인한다. 새 harness에서는 한 cell의 cold phase 후 `results/planning/<cold-run-id>/<conf>.json`을 확인한다.
- **관측 증상**: 기존 성능 row로는 DP/MinST candidate objective, FedAll selection, Heuristic demotion, common emission authority를 실제 실행 plan에 직접 연결할 수 없었다. 따라서 “실행시간 차이가 작다”는 사실만으로 planner 철학을 판정할 수 없었다.
- **원인 분석**: `distributedExpNew.sh`가 `BENCHMARK_PLANNER_TRACE`를 compile-only 분기 안에서만 반영했고 performance runner가 traced planner receipt를 셀 증거 계약으로 요구하지 않았다.
- **해결 요약**: cold Docker runtime에만 planner trace를 켜고 warm primary runtime에는 끈다. cold log에서 runtime-mode planning receipt를 생성해 planner/config/profile/workers/workload, trace summary, emission digests, runtime-plan digest/fingerprint, raw-log/config checksum을 fail-closed 검증한다. campaign row와 predecessor resume 모두 이 receipt를 인증해야 한다.
- **수정 파일**: harness `sigmod2021-exdra-p523/experiments/code/distributedExpNew.sh`, `run_LAN_docker.sh`, `tools/planning_receipt.py`, `tools/run_one_pass_performance.py`, `tests/test_planning_receipt.py`, `tests/test_one_pass_performance.py`.
- **검증**: `python3 -m unittest -v tests.test_planning_receipt tests.test_one_pass_performance` 33 tests 통과, `python3 -m py_compile`, `bash -n`, `git diff --check` 통과. 실제 Docker cold-runtime receipt는 새 JAR canary에서 확인한다.
- **잔여 이슈**: runtime dynamic recompile이 top-level planner invocation을 추가로 기록하는 workload에서는 “exactly one summary” 가정이 맞는지 canary로 확인해야 한다. 실제 합법 다중 invocation이면 invocation별 bounded receipt로 확장하고 trace를 약화하지 않는다.
- **잠재 회귀 위험**: trace가 cold runtime에만 적용되지 않으면 warm timing이 오염되거나 로그가 폭증할 수 있다. phase별 JVM option, cold/warm plan digest parity, receipt trace count, bundle checksum으로 감지한다.
- **의사결정 근거**: runtime fingerprint만으로 정책 철학을 추정하지 않고, 모든 실행에 선택→emission→runtime lineage를 보존한다.

## 6. traced cold runtime의 planner metadata를 runtime fallback으로 오탐함

- **상태**: 해결 및 실패 로그 재검증 완료
- **적용 원칙/제약**: runtime fallback 금지는 유지하되, 실행되지 않는 planner 감사 metadata와 실제 runtime 관측을 구분한다. planner trace 자체는 별도 planning receipt로 계속 fail-closed 인증한다.
- **환경/조건**: immutable stage `25b292...`, WAN-Light KMeans, workers=1, DP, 전체 one-pass campaign 첫 셀.
- **재현 절차**: 실패 campaign `/home/mchoi/g014-full-results-13b8e32-13e0da7-20260812-v1`의 첫 cold phase `scan.json`과 `raw_coordinator.log`를 확인한다.
- **관측 증상**: SystemDS 실행은 return code 0, semantic oracle은 ARI 1.0/SSE 상대오차 0으로 통과했지만 `scan.json`의 `fallback=true` 때문에 셀이 실패했다. raw log의 `fallback` 327건은 모두 `[PlannerTrace][DP-DecisionMap-ExactSelectionConflict]`가 직렬화한 `fallbackMaterializations=[]` 필드였으며 실제 fallback 메시지는 없었다.
- **원인 분석**: harness `phase_bundle.py`는 runtime explain instruction만 scan에서 제외하고 새로 활성화한 `[PlannerTrace]` line은 제외하지 않았다. 따라서 모든 셀에 trace를 의무화한 변경과 단순 substring `fallback` scan이 충돌했다.
- **해결 요약**: `[PlannerTrace]` line을 runtime failure scan에서 제외했다. 실제 runtime line의 `fallback`, timeout, DMLRuntimeException 탐지는 그대로 유지한다. compiler trace는 `planning_receipt.py`가 planner identity, stage count, emission digest, runtime-plan digest와 함께 별도로 인증한다.
- **수정 파일**: harness `sigmod2021-exdra-p523/experiments/tools/phase_bundle.py`, `tests/test_g007_harness.py`.
- **검증**: 실제 실패 raw log를 새 scanner로 다시 검사해 timeout/error/fallback/resource_invalid가 모두 false임을 확인했다. planner trace 내부 `fallbackMaterializations`는 무시하되 실제 `coordinator timed out and entered fallback`은 계속 탐지하는 회귀와 기존 planning/one-pass 33개 테스트가 통과했다.
- **잔여 이슈**: 새 harness commit으로 immutable stage를 다시 만들고 첫 KMeans 셀부터 재실행해야 한다. 이전 campaign의 성공 prefix는 0개다.
- **잠재 회귀 위험**: 실제 runtime 오류가 `[PlannerTrace]` prefix로 잘못 출력된다면 scan에서 제외될 수 있다. planner trace는 compiler-owned structured prefix로만 사용하고, worker/coordinator exception은 기존 비-prefix runtime line 및 return code/semantic oracle로 감지한다.
- **의사결정 근거**: fallback을 허용한 것이 아니라 증거 채널의 유형 오류를 수정했다.

## 7. KMeans의 ROW×local BinaryMM 후보가 무관한 FULL multiplicity 증거 때문에 제거됨

- **상태**: source 수정 및 37개 표적 회귀 통과, 새 Docker stage 재검증 예정
- **적용 원칙/제약**: runtime이 지원하는 후보를 workload/opcode 가드로 닫지 않는다. 후보의 합법성과 무관한 unknown metadata 때문에 후보가 제거되면 shared Oracle의 fact consultation을 바로잡는다.
- **환경/조건**: Docker WAN-Light KMeans, P2P2D, workers=2, planner DP/FedAll/Heuristic/MinST, source commit `f901376d29` 기반 stage. 중단한 campaign은 `/home/mchoi/g014-full-results-f901376-4f0b380-20260812-v2`이다.
- **재현 절차**: `run_LAN_docker.sh --planning-only --net-profile wan_light --workers 2 --dataset P2P2D --alg kmeans`를 네 planner config로 실행하고 `scripts/builtin/kmeans.dml:134`의 `X %*% t(C)` candidate/selection trace를 확인한다. 중단 campaign의 runtime-plan fingerprint에서는 FedAll/Heuristic/DP 모두 `fedinit:1;uasqk+:1`만 남았다.
- **관측 증상**: workers=2에서 FedAll/Heuristic/DP runtime plan이 사실상 동일했고 KMeans의 핵심 거리 계산 `ba(+*)`가 CP로 컴파일됐다. exact candidate는 input `[PRESENT:ROW, ABSENT_LOCAL]`, native capability `FED/FOUT/ROW`였지만 `missingRequiredFacts=[fullSinglePartition]` 때문에 neutral graph에서 배제됐다. 중단 campaign은 11/336에서 정지했으며 최종 결과로 사용하지 않는다.
- **원인 분석**: `BinaryMMRule`이 input FType과 무관하게 `ShapeHint.fullSinglePartition()`을 항상 조회했다. ROW/COL 후보에는 필요 없는 FULL 전용 fact가 unknown이면 proof가 불완전한 것으로 기록되어 합법적인 ROW×local FED 후보까지 제거됐다. runtime `AggregateBinaryFEDInstruction`은 ROW×local MM을 RHS broadcast 후 FED 실행하고 non-vector output을 FOUT으로 유지하는 경로를 실제 지원한다.
- **해결 요약**: `left` 또는 `right`가 `FType.FULL`인 경우에만 `fullSinglePartition`을 조회한다. ROW/local 및 local/ROW 후보는 FULL multiplicity와 독립적으로 기존 runtime capability를 유지한다. single-worker FULL×FULL에는 기존의 single-partition proof 요구를 그대로 보존했다. candidate-space 축소, runtime fallback, workload 특례는 추가하지 않았다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java`, `src/test/java/org/apache/sysds/test/functions/fedplanner/rules/BinaryMMTsmmRuleTest.java`, `src/test/java/org/apache/sysds/hops/fedplanner/fedAll/CampaignBG014FedAllKMeansRuntimeRecompileDerivedFoutRedTest.java`, `src/test/java/org/apache/sysds/hops/fedplanner/fedAll/CampaignBG014FedAllKMeansDerivedFoutAuthorityRedTest.java`.
- **검증**: rule 회귀는 ROW/local 결과가 `FED/FOUT/ROW`이고 proof가 `fullSinglePartition`을 consult하거나 missing으로 기록하지 않음을 검사한다. workers=2 runtime 회귀는 KMeans line 134 `ba(+*)`가 `FED/FOUT`으로 선택되고 실제 heavy hitter에 `fed_ba+*` 5회가 나타나며 fallback/repair가 0임을 검사한다. single-worker 회귀는 FULL direct `FED/FOUT` distance product를 검사하며, direct runtime support가 생긴 뒤에도 derived upload를 반드시 선택해야 한다는 낡은 기대값을 제거했다. 관련 10개 클래스 37 tests가 failures/errors/skips 0으로 통과했다.
- **잔여 이슈**: 새 immutable JAR의 KMeans workers=1/2 네 planner planning-only receipt에서 실제 plan fingerprint와 policy 차이를 다시 확인하고, runtime canary 후 새 336-cell campaign을 처음부터 실행해야 한다.
- **잠재 회귀 위험**: FULL 입력인데 multiplicity fact 조회가 누락되면 multi-range FULL을 single-worker FULL로 오판할 수 있다. `hasFullInput` 조건과 기존 multi-range FULL rule/runtime 회귀로 감지한다. 반대로 ROW/COL에서 다시 irrelevant fact가 required로 기록되면 KMeans plan collapse 회귀가 즉시 실패한다.
- **의사결정 근거**: runtime 지원을 새로 주장하거나 후보를 닫은 것이 아니라, 이미 `FED/FOUT/ROW`인 shared Oracle 결과가 무관한 proof metadata로 폐기되는 분석 버그를 수정했다.

## 실행/검증 완료 조건

1. 새 source/harness commit과 JAR로 immutable stage를 만든다.
2. WAN-Light 표적 planning/runtime canary에서 LM, LogReg, L2SVM, KMeans의 위 원인을 직접 검증한다.
3. WAN-Light → WAN-Mid → LAN 순서, 각 구간 DP → FedAll → Heuristic → MinST 순서로 336개 Docker cell을 정확히 한 번씩 실행한다.
4. 336/336 row와 336/336 authenticated planner receipt가 존재하고 각 `(profile, workload, workers, planner)` key가 유일해야 한다.
5. runtime 정렬 `MinST <= DP <= Heuristic <= FedAll`은 노이즈 허용 범위와 함께 검사한다. 동일/근접 runtime은 plan digest와 policy trace를 근거로 합리성을 설명하고, 정렬 위반은 후보 가드가 아니라 cost/size/layout/oracle/emission fact를 재감사한다.
6. runtime 3×7 및 compile-time 3×7 그래프는 같은 최신 immutable campaign만 사용한다.
