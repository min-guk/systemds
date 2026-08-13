# Session Issues — 2026-08-13

## 1. LM workers>=2에서 planner-selected inner direct FOUT이 MMChain fusion으로 소실됨

- **상태**: source 수정 및 표적 회귀 완료; 새 immutable Docker canary 진행 예정
- **적용 원칙/제약**: planner가 선택하고 비용화한 direct `FED/FOUT` 경계를 Lop fusion이 지우지 않는다. runtime fallback이나 후보 축소를 추가하지 않고, lowering이 선택된 placement를 그대로 보존한다.
- **환경/조건**: source commit `a4f8825130b8d560562028ec70d6170b0e07422d` 기반 predecessor, Docker WAN-Light, P2P2D LM, workers=2..4, FedAll. 중단 campaign은 `/home/mchoi/g014-full-results-a4f8825-4f0b380-20260812-v1`이며 64/336 terminal row에서 중단했다.
- **재현 절차**: predecessor stage에서 `run_LAN_docker.sh --net-profile wan_light --workers 2 --dataset P2P2D --conf mkl-fout --salg lm`을 실행하고 planning trace와 runtime program을 비교한다. planning receipt는 `results/planning/6501fc8735054d26482051410a06c912_wan_light_coordinator1/mkl-fout.json`, raw planning log는 `results/fed2/mkl-fout/lm_dataset-P2P2D_coordinator_mkl-fout_6501fc8735054d26482051410a06c912_wan_light_coordinator1.log`이다.
- **관측 증상**: FedAll trace는 LM line 129의 외부 MM hop 246을 `FED/LOUT/ROW`, 내부 MM hop 245를 direct `FED/FOUT/ROW`로 선택하고 둘 모두 `emittedWork=true`로 기록했다. 하지만 runtime program에는 `FED mmchain X p ... XtXv` 하나만 남아 내부 FOUT이 독립적인 FederationMap으로 생성되지 않았다. 그 결과 workers=2..4에서 FedAll의 emission plan은 DP/MinST와 달랐지만 runtime-plan SHA와 instruction fingerprint는 모두 같은 `mmchain` 계획으로 붕괴했다.
- **원인 분석**: `AggBinaryOp.checkMapMultChain()`은 외부 aggregate-binary의 direct FOUT만 별도로 막았다. 내부 aggregate-binary는 `hasPlannerMaterializationBoundary()`가 REFED/derived-FOUT/local-materialization registry만 확인해 direct FOUT을 경계로 인식하지 않았고, `constructCPLopsMMChain()`이 내부 HOP을 제거했다.
- **해결 요약**: `hasPlannerMaterializationBoundary()`가 planner-selected direct FOUT(`FOUT && !isFederatedOutputDerived()`)도 executable boundary로 취급하게 했다. 이 공통 경계는 MMChain 및 Lop-level transpose fusion이 내부 FOUT을 지우지 못하게 한다. 외부/내부가 LOUT인 DP/MinST MMChain과 derived FOUT의 명시적 materialization 경로는 그대로 유지한다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/AggBinaryOp.java`, `src/test/java/org/apache/sysds/test/component/federated/FederatedDagExactRefedInputProjectionTest.java`.
- **검증**: 새 `selectedDirectFoutInnerXtXvRemainsUnfused` 회귀는 수정 전 `expected NONE but was XtXv`로 실패했다. 수정 후 inner Lop이 명시적 `FED ba+* ... FOUT`이고 outer가 `MapMultChain`이 아님을 검사해 통과했다. 관련 8개 클래스 총 43 tests가 failures/errors/skips 0으로 통과했다.
- **잔여 이슈**: 새 clean commit/JAR/immutable stage에서 LM workers=1..4의 네 planner planning-only 및 runtime canary를 실행해 FedAll workers>=2가 명시적 inner `fed_ba+* ... FOUT`을 보존하고, DP/MinST LOUT은 기존 `fed_mmchain`, Heuristic은 정책상 REFED 계획을 유지하는지 확인한다. 통과 후 predecessor row를 재사용하지 않고 새 336-cell campaign을 시작한다.
- **잠재 회귀 위험**: direct FOUT input을 포함하는 다른 Lop fusion도 이전보다 보수적으로 차단되어 성능이 달라질 수 있다. 이는 후보 폐쇄가 아니라 선택된 실행 경계를 보존하는 변화이며, 관련 FOUT/MMChain/rewire 회귀와 전체 Docker campaign의 planning/runtime fingerprint 차이로 감지한다.
- **의사결정 근거**: Oracle/runtime capability는 합법적인 inner `FED/FOUT/ROW`를 이미 제공한다. 문제는 capability가 아니라 lowering의 plan fidelity이므로 Oracle 후보나 heuristic 규칙을 변경하지 않았다.

