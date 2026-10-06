# 반환 GET 및 native result RTT 중복 과금 수정

기준 커밋: `e1fdfe4446180ef9d6fbd93fd0c4f5ab899d7440`. DP와 Exact가 공유하는 비용 모델을 수정했다. Candidate legality, TW/TR·함수 바인딩 배치 규칙, runtime instruction은 바꾸지 않았다.

## 수정 내용

### 함수 반환 GET

`ExactPhysicalCostModel`이 function output의 값 연결을 따라 같은 runtime MatrixObject의 생성 원본까지 추적한다. 일반 함수 반환은 정확한 caller→callee 문맥으로 매핑하고 인라인 반환은 같은 문맥을 유지한다. 같은 HOP라도 다른 호출에서 생성된 값은 구분한다. 반환 경로가 다르거나 원본·호출 문맥이 불명확하면 임의로 합치지 않는다. Relocation/derived 출력의 기존 별도 identity도 유지한다.

함수 내부 CP read 후 같은 값을 반환하고 호출자에서 CP read하는 원래 반례:

| 항목 | 수정 전 | 수정 후 |
|---|---:|---:|
| 양수 GET factor | 2 | 1 |
| 다운로드 비용 | 2.00262451171875 ms | 1.001312255859375 ms |

이는 유효한 선택 상태의 모델 비용 검증이며, 해당 상태가 무제약 최적해이거나 이 수치가 실측 시간이라는 뜻은 아니다. 새 테스트는 단일·중첩·반복 pass-through와 callee가 생성한 fresh 값의 단일·반복 반환을 검증한다. Fresh 값도 같은 invocation 내부/외부 read는 공유하지만 호출마다 생긴 값은 별도 과금한다.

### Native result RTT

계산 응답에 결과가 이미 포함되는 아래 경로의 추가 RTT를 제거한다.

- `contains`
- 비가중 `moment`
- 한 입력만 FED이고 다른 입력은 local인 `cov`
- `WSLOSS`, `WCEMM`
- FED/LOUT `Spoof`

`FederatedCostModel`에 입력 배치를 받는 overload를 추가하고 production `fedCostProjection`이 이를 전달한다. 일반 binary/rightIndex의 별도 GET과 aligned covariance의 기존 비용은 유지한다. Payload 비용을 제거하지 않고 중복 RTT만 제거한다. C2W=3ms, W2C=7ms인 재현에서는 contains/mixed covariance의 결과 비용에서 정확히 10ms가 감소했다.

## 변경 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactFunctionAliasGetCostTest.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactNativeResultBatchCostTest.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/NativeResultBatchOwnershipCostTest.java`
- `src/test/java/org/apache/sysds/runtime/instructions/fed/NativeResultBatchContractTest.java`

## 검증

- Maven `compile`, `test-compile` 성공.
- 수정 전 pass-through single/nested/repeated는 1 GET 대신 각각 2/2/3 GET 과금으로 실패했다. Mixed covariance production projection은 payload-only보다 1 RTT 크게 계산돼 실패했다.
- 최종 56개 Java test class, **372 tests 중 371 PASS / 1 기존 실패**. 이번에 추가한 회귀 17개는 모두 PASS.
- 유일한 실패: `FederatedPlannerFallbackIntegrationTest.testDpPlansSteplmWithSameNamedFormalTransientBinding`, `EXACT_VE_FACTOR_CELL_OVERFLOW`. 기존 main 통합 검증에서도 재현·기록된 실패이며 테스트를 끄거나 삭제하지 않았다. 이전 근거: [validation.json](experiments/cost-model-main-20261006/validation.json).
- Docker DP compile-only **14 workloads × W1/W3 = 28/28 PASS**. 각 결과의 `planningSucceeded=true`, `planner=DP-LocalConflict`, `runtimeExecuted=false`를 확인했다.
- `git diff --check` 통과. 별도 리뷰에서 native batch 분류, 출력 alias의 호출별 identity, 인라인 문맥, latent WDivMM의 기존 feasibility 보존을 확인했다.

실행 명령:

```bash
mvn -q -DskipTests -Dcheckstyle.skip -Drat.skip=true compile
mvn -q -DskipTests -Dcheckstyle.skip -Drat.skip=true test-compile
bash scripts/fedplanner/run_LAN_docker.sh --function-boundary-compare \
  --artifact-root /home/mchoi/cost-transfer-fix-20261006/docker \
  --variant B --jobs 2
```

Java suite는 `java --add-modules jdk.incubator.vector -Xmx6g -XX:ActiveProcessorCount=4 -cp 'target/classes:target/test-classes:target/lib/*' org.junit.runner.JUnitCore ...`로 실행했다. 정확한 56개 클래스와 명령은 아래 artifacts의 `java-classes.json`, `java-command.json`에 보존했다.

## 증거와 한계

전체 결과: [validation.json](experiments/transfer-cost-dedup-20261006/validation.json). 상세 로컬 로그는 `/home/mchoi/cost-transfer-fix-20261006`에 보존했다.

- `java-tests.log`, `build.log`, `test-build.log`
- `return-get-after.log`, `contains-after.log`, `covariance-after.log`
- `docker/results/B/runs.json` 및 각 case JSON/log/command receipt
- 수정 전 RTT 실패: `/home/mchoi/cost-transfer-fix-native-rtt-20261006/baseline-test.log`
- 원래 감사: `/home/mchoi/cost-transfer-audit-20261006/REPORT.md`

빌드 이후 ExactPhysicalCostModel에 선택적 단일-statement for braces 제거와 들여쓰기만 변경된 것을 발견했다. 해당 두 편집을 되돌린 텍스트가 빌드 직전 저장 SHA-256과 정확히 일치함을 독립 검증했다. `format-only-provenance.json`과 `ExactPhysicalCostModel.built-snapshot.java`에 기록했으며 검증한 실행 의미는 최종 소스와 같다. 이후 의미 변경은 없다.

Docker fixture는 기존 frozen campaign에서 복사하고 절대 경로를 새 artifact root로 치환했다. Frozen STEP-LM/lmCG builtin과 dependency code는 이전 검증 조건을 유지했다. 따라서 이번 검증은 compile 회귀이며 이전 JSON의 경로 기반 hash나 선택 계획이 byte-identical하다는 주장은 하지 않는다. 실제 분산 실행 성능 측정도 아니다. Runtime batch 테스트는 실제 instruction/UDF의 요청 생성 코드를 mock transport로 실행한다.

## 잔여 이슈 및 회귀 위험

업로드의 조건부 중복 위험, 별도 보조 stage 과소계상, MMChain 문제는 이번 확정된 두 결함 수정과 분리했다. 출처·호출 문맥을 증명하지 못한 반환 alias는 기존의 보수적 개별 과금을 유지한다. Latent WDivMM의 별도 원본 해석은 확장하지 않아 새로운 planning 거부를 만들지 않는다.

후속 변경에서는 다른 호출에서 생성한 행렬을 잘못 합치거나 실제 별도 GET batch를 제거하는 위험을 경계해야 한다. 새 함수 반환 및 request-batch 회귀가 이를 검사한다. Runtime fallback이나 후보 공간 축소로 해결하지 않았다.

후속 상태: 보조 단계와 MMChain의 추가 점검·수정은 [2026-10-06 후속 보고서](COST_MODEL_FOLLOWUP_2026-10-06.md)에 기록한다. 이 절의 미해결 목록은 당시 시점의 기록이며, 최신 판정은 후속 보고서를 따른다.
