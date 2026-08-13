# Session Issues — 2026-08-13

## 1. LM workers>=2에서 planner-selected inner direct FOUT이 MMChain fusion으로 소실됨

- **상태**: 해결; source 회귀, 16셀 planning-only, worker=2 cold/warm Docker runtime canary 검증 완료
- **적용 원칙/제약**: planner가 선택하고 비용화한 direct `FED/FOUT` 경계를 Lop fusion이 지우지 않는다. runtime fallback이나 후보 축소를 추가하지 않고, lowering이 선택된 placement를 그대로 보존한다.
- **환경/조건**: source commit `a4f8825130b8d560562028ec70d6170b0e07422d` 기반 predecessor, Docker WAN-Light, P2P2D LM, workers=2..4, FedAll. 중단 campaign은 `/home/mchoi/g014-full-results-a4f8825-4f0b380-20260812-v1`이며 64/336 terminal row에서 중단했다.
- **재현 절차**: predecessor stage에서 `run_LAN_docker.sh --net-profile wan_light --workers 2 --dataset P2P2D --conf mkl-fout --salg lm`을 실행하고 planning trace와 runtime program을 비교한다. planning receipt는 `results/planning/6501fc8735054d26482051410a06c912_wan_light_coordinator1/mkl-fout.json`, raw planning log는 `results/fed2/mkl-fout/lm_dataset-P2P2D_coordinator_mkl-fout_6501fc8735054d26482051410a06c912_wan_light_coordinator1.log`이다.
- **관측 증상**: FedAll trace는 LM line 129의 외부 MM hop 246을 `FED/LOUT/ROW`, 내부 MM hop 245를 direct `FED/FOUT/ROW`로 선택하고 둘 모두 `emittedWork=true`로 기록했다. 하지만 runtime program에는 `FED mmchain X p ... XtXv` 하나만 남아 내부 FOUT이 독립적인 FederationMap으로 생성되지 않았다. 그 결과 workers=2..4에서 FedAll의 emission plan은 DP/MinST와 달랐지만 runtime-plan SHA와 instruction fingerprint는 모두 같은 `mmchain` 계획으로 붕괴했다.
- **원인 분석**: `AggBinaryOp.checkMapMultChain()`은 외부 aggregate-binary의 direct FOUT만 별도로 막았다. 내부 aggregate-binary는 `hasPlannerMaterializationBoundary()`가 REFED/derived-FOUT/local-materialization registry만 확인해 direct FOUT을 경계로 인식하지 않았고, `constructCPLopsMMChain()`이 내부 HOP을 제거했다.
- **해결 요약**: `hasPlannerMaterializationBoundary()`가 planner-selected direct FOUT(`FOUT && !isFederatedOutputDerived()`)도 executable boundary로 취급하게 했다. 이 공통 경계는 MMChain 및 Lop-level transpose fusion이 내부 FOUT을 지우지 못하게 한다. direct FOUT 경계가 없는 LOUT-only MMChain과 derived FOUT의 명시적 materialization 경로는 그대로 유지한다. 이 LM plan에서는 DP/MinST도 inner direct FOUT을 선택하므로 선택 충실성을 위해 명시적 두 MM으로 lowering된다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/AggBinaryOp.java`, `src/test/java/org/apache/sysds/test/component/federated/FederatedDagExactRefedInputProjectionTest.java`.
- **검증**: 새 `selectedDirectFoutInnerXtXvRemainsUnfused` 회귀는 수정 전 `expected NONE but was XtXv`로 실패했다. 수정 후 inner Lop이 명시적 `FED ba+* ... FOUT`이고 outer가 `MapMultChain`이 아님을 검사해 통과했다. 관련 8개 클래스 총 43 tests가 failures/errors/skips 0으로 통과했다.
- **검증 추가**: clean source commit `5ea324bbc59be05f8e930b0381c7bd8d8b6384fb`, JAR SHA-256 `bd9f0dadf04b459dd1d190b6efa15fe6d249fa2206a3c61050ab2f2ca019d94c`의 immutable stage `g007-stage-bebc4325fa072984a1d0282fadd296211d32004a64f6567a2bac339a5e36459f`에서 WAN-Light LM workers=1..4 x 4 planner planning-only를 실행했다. 16/16 receipt가 `success=true`, `runtime_executed=false`, fallback/repair trace 0으로 통과했다. workers=2..4의 runtime-program fingerprint는 DP/MinST `ba+*:3;fedinit:2;r':2`, FedAll `ba+*:3;fed_fout:1;fedinit:2;r':2`, Heuristic `ba+*:3;fed_refed:1;fedinit:2;r':2`로 정책별로 분리됐다. 특히 FedAll workers=2..4에는 각각 명시적 inner `FED ba+* ... FOUT`과 outer `FED ba+* ... LOUT`이 남고 `FED mmchain`은 0개였다. 전체 감사 산출물은 `/home/mchoi/g014-lm-inner-fout-canary-5ea324b-20260813-v1/planning-audit.json`(SHA-256 `e0948a95908402ee907a5c39505b779215bcf9ba0a7868cbb35227cf70e7a347`)이다.
- **Docker runtime 검증**: 같은 stage의 WAN-Light LM workers=2 FedAll을 cold/warm으로 실행했다. cold/warm runtime은 13.014/9.054초, runtime-plan SHA-256은 양쪽 모두 `f08cb4fa1b56044a5cf78646364e3e11e9dccf0b5677d88766c3f47dc37c1695`, 출력 SHA-256은 양쪽 모두 `ae3ca8405f01303ff5a466c2563d86004488513ae04044ad33df53e4252fcbc6`였다. 실제 heavy hitter에 `fed_ba+*` 91회와 `fed_fed_fout` 1회가 기록됐고 `fed_mmchain`은 없었다. 두 phase 모두 semantic oracle 통과(objective relative error `8.716e-15`, prediction NRMSE `1.604e-15`), runtime scan의 error/fallback/resource-invalid/timeout 0, cold/warm plan/output parity를 통과했다. 감사 산출물은 `/home/mchoi/g014-lm-inner-fout-canary-5ea324b-20260813-v1/runtime-audit.json`(SHA-256 `09089e9b0c3fc3357c3993d4e57ca52bdaf3ee053118666540df357e477e3c2c`)이다.
- **잔여 이슈**: 이 수정이 다른 workload의 선택된 direct FOUT fusion에도 적용되므로 predecessor row를 재사용하지 않는 새 336-cell Docker campaign에서 전체 7 workload x 4 planners x 4 workers x 3 profiles를 검증한다.
- **잠재 회귀 위험**: direct FOUT input을 포함하는 다른 Lop fusion도 이전보다 보수적으로 차단되어 성능이 달라질 수 있다. 이는 후보 폐쇄가 아니라 선택된 실행 경계를 보존하는 변화이며, 관련 FOUT/MMChain/rewire 회귀와 전체 Docker campaign의 planning/runtime fingerprint 차이로 감지한다.
- **의사결정 근거**: Oracle/runtime capability는 합법적인 inner `FED/FOUT/ROW`를 이미 제공한다. 문제는 capability가 아니라 lowering의 plan fidelity이므로 Oracle 후보나 heuristic 규칙을 변경하지 않았다.

## 2. 직접 runtime canary에서 runtime explain 계약을 누락함

- **상태**: 해결; 잘못된 호출은 격리하고 올바른 계약으로 재실행 완료
- **적용 원칙/제약**: 성능 근거는 authenticated planning receipt와 cold/warm runtime-plan parity를 만족한 Docker 실행만 채택한다.
- **환경/조건**: 위 immutable stage, WAN-Light LM, workers=2, FedAll, 직접 `run_LAN_docker.sh` canary 첫 시도 run id `20260813_043212_1535196_wan_light`.
- **재현 절차**: `CAMPAIGN_PLAN_EXPLAIN`을 설정하지 않고 non-lifecycle runtime canary를 호출한다.
- **관측 증상**: workload runtime 자체는 끝났지만 cold phase receipt가 `runtime plan explain marker must occur exactly once`로 fail-closed했다.
- **원인 분석**: direct canary 호출에서 cold receipt가 요구하는 `CAMPAIGN_PLAN_EXPLAIN=1`을 누락했다. source/runtime 결함이 아니라 실험 호출 계약 위반이다.
- **해결 요약**: 첫 시도는 `/results/failures/20260813_043212_1535196_wan_light_coordinator1/mkl-fout`에 격리하고 어떤 성능/정확성 근거에도 사용하지 않았다. `CAMPAIGN_PLAN_EXPLAIN=1`을 명시해 run id `20260813_043703_1546934_wan_light`로 재실행했으며 cold/warm 두 phase 모두 성공했다.
- **수정 파일**: source 변경 없음; canary 호출 환경만 수정.
- **검증**: 재실행의 runtime planning receipt는 `success=true`, `runtime_executed=true`이고 cold/warm canonical runtime-plan SHA-256이 동일하다. 상세 결과는 이슈 1의 runtime 검증과 `runtime-audit.json`에 기록했다.
- **잔여 이슈**: 없음. 전체 campaign은 `run_one_pass_performance.py`가 lifecycle request와 explain 계약을 자동 생성하므로 동일 누락이 발생하지 않는다.
- **잠재 회귀 위험**: 임의 direct canary를 다시 실행할 때 환경 변수를 누락할 수 있다. planning receipt가 fail-closed하므로 잘못된 실행은 최종 row로 인증되지 않는다.
- **의사결정 근거**: 검증 계약을 완화하지 않고 호출을 수정했다.
