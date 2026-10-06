# R55 aligned factor reuse 및 GET 처리량 모델 수정

## 요청 / 편집 전 계획

R54의 남은 두 항목을 수정한다. (1) 런타임에서 aligned FED U/V를 직접 사용하는데도 fused 준비 모델이 업로드를 과금하는 누락, (2) Netty 스레드8개를 codec 처리량으로 환산하고4MiB에서 처리율을 바꾸는 근거 없는 GET 가정이다.

### 보존 범위

- 현재 main working tree의 R54, 기존 CG/MM-chain, runtime/AggLocal 수정 모두 보존한다. 별도 `cost-reuse-transport-r55-20261005/source` 및 target에서 빌드·검증한다.
- 비용만 수정한다. 후보/Oracle/privacy/TR-TW/recompile gate, runtime instruction 동작, value version/context/activation의 재사용 수명은 바꾸지 않는다. commit/push나 장시간 runtime campaign은 포함하지 않는다.
- R54 PUBLIC compiler-only 회귀 예외의 동일 근거를 R55에 적용한다. 실제 worker 데이터 접근이나 PUBLIC 성능실험 허용으로 확대하지 않는다.

### 수정 설계 / 정리 순서

1. 동작 보존 및 RED: selected aligned/misaligned factor, 다른 worker/range, CP factor, transpose/current occurrence, shared consumer, GET W1/3/8/16·4MiB 양쪽·skew·명시적 rate0·기존 설정 migration을 회귀로 고정한다.
2. aligned reuse: source opcode나 FType만으로0처리하지 않는다. 실제 선택된 factor의 FOUT 여부와 runtime의 주소/range alignment 조건을 비용에 반영한다. 없는 업로드뿐 아니라 제거된 source shell 경계에서 생기는 phantom collect도 제거하고, 비정렬/CP 경로의 실제 준비 및 CP owner의 collect는 유지한다. source lifetime/activation 집계는 기존 구조를 사용한다. 새 DP decision variable은 추가하지 않고 factor scope는 필요한 소유자·입력 의존성으로 제한한다.
3. GET: 공통 산식을 `largestResponse/wireBW + totalResponse/effectiveW2CProcessingBW`로 단순화한다. thread-count 기반 나눗셈과4MiB 분기를 삭제한다. rate는 기존 directional `SYSDS_FED_COST_NET_SERDES_BW_W2C`를 사용하며0=disabled 계약을 유지한다. 이 값은 codec-only 실측 성능이 아니라 aggregate response-processing 유효 계수다.
4. 구 설정: `INBAND_RESULT_SERDES_BW_W2C`와 `REUSABLE_GET_VAR_FAST_MAX_MB`를 새 의미로 몰래 재해석하지 않는다. 명시 설정 시 한 번 migration 경고를 내고 무시한다. ordinary/reusable/in-band payload 가격, FULL/PART 및 실제 batch 횟수는 유지한다.
5. RED→GREEN → 기존 비용/activation/privacy/MM-chain 회귀 → 격리 Maven package → 최종 JAR 회귀·Xlint·diff·독립 검토 → SHA guard로 owned 파일만 main 반영.

### 근거 및 위험

- 런타임 `QuaternaryWDivMMFEDInstruction` ROW/FULL은 aligned ROW U를 재사용하고 V는 broadcast, COL은 U broadcast와 aligned V 재사용을 구분한다. 임의의 FOUT factor 전부를 무료로 만들면 안 된다.
- `FederatedData`의 Netty8스레드는 실제 동시 codec 처리량 보장이 아니다. runtime에4MiB 처리 방식 전환 근거도 없다.
- 독립 architect의 network 검토: 기존14.7은 native codec 측정치가 아니라 과거 transport/binding 유효 계수이다. total coordinator bytes 계약으로 되돌리는 것은 근거가 있으나 새 실측 정확도는 주장하지 않는다. wire의 worker-path 병렬성, 공유 NIC 경합은 별도 한계다.
- alignment 선택 의존성 때문에 factor scope가 증가할 수 있다. 새 decision variable/전역 후보 조합을 만들지 않고 기존 runtime operand 범위에서 가격화하며 compile-only 회귀로 검증한다.

## 진행 / 결과

**완료.** 요청한 두 항목을 수정하고 아래 최종 검증 후 main working tree에 production3/test6파일을 반영했다. commit/push 및 runtime 재개는 하지 않았다.

| 최종 검증 | 결과 |
|---|---|
| 격리 Maven package | **BUILD SUCCESS**,38.777초 |
| 새 final JAR 비용 suite | **269 PASS**,90.645초 |
| 새 final JAR 인접/privacy/AggLocal/MM-chain suite | **188 PASS**,43.047초, 기존 ignored2 |
| 합계 | **457 PASS** |
| 독립 검토자의 W2C14.7 + production ERROR logging focused run | **77 PASS** (위 suite의 중복 검증이므로 합계에 더하지 않음) |
| production3파일 `javac -proc:none -Xlint:all,-path` | 경고0 |
| main 및 격리 `git diff --check` | PASS |
| 독립 코드/설계 검토 | **APPROVE**, 지적3건 반영 |
| SHA guard 동기화 | owned9파일만 반영, 무관한107파일 보존, 모든 Java source/test 및 builtin DML 일치 |

최종 JAR:
`/grid/3/cofee-lm-sweep-mchoi-20260914/cost-reuse-transport-r55-20261005/source/target/systemds-3.4.0-SNAPSHOT.jar`

SHA256: `ab4ca9a0b77fc98d99317291405e6ddbf9fea91ab803ef6d66a334985df6c209`

`final-build-proof.json`, `final-sync-proof.json`, `independent-review.json`, `verification.json`에 provenance와 완료 판정을 기록했다. 옛 concurrency 주석2곳 정리 후에도 재빌드/JAR 검증을 반복했고 최종 JAR SHA가 동일함을 확인했다. main의 기존 target symlink는 유지했으므로 실행 중인/기존 실험 artifact가 자동 교체되지는 않았다.

### 1. Fused factor의 실제 전송 소유권

- `PlacementCostSemantics.RuntimeFactorInput`은 실제 U/V source occurrence, operand position, runtime-generated transpose 여부를 보존한다. 제거되는 outer product의 FType을 factor의 FType으로 사용하지 않는다.
- ROW/FULL W의 U는 ROW-capable type과 **같은 row range/worker 주소**가 모두 맞을 때만 재사용한다. COL W의 V는 런타임과 동일하게 **COL 또는 COL_T 전체-map alignment** 중 하나가 성립해야 한다. range마다 제각각 다른 alignment를 조합하지 않는다. 같은 축에 매칭되는 모든 range의 주소가 같아야 한다.
- 선택된 source가 LOUT이거나 exact geometry가 없으면 재사용으로 할인하지 않는다. runtime이 새로 만드는 `V=t(B)`는 B의 기존 map을 V의 map으로 간주하지 않는다.
- 기존 owner/W factor에서 U/V 일괄 업로드를 제거했다. 실제 PUT은 **owner/W/U**, **owner/W/V** 두 factor로 나누어 해당 owner의 실행 횟수만큼 과금한다. U/V를 한 tensor로 합치거나 새로운 placement decision variable을 추가하지 않았다.
- GET demand는 `owner=CP` 또는 `owner=FED && !alignedReuse`이며, 기존 ordinary CP consumer의 materialization activation collector에 합친다. 제거된 shell로 향하던 phantom GET은 지우되 공유되어 살아남는 outer/transpose의 전송은 보존한다. 같은 read/layout/creation scope에서 CP consumer와 두 fused owner가 요구해도 GET은 한 번이다. source version/context 및 기존 creation-lifetime cap은 유지한다.

### 2. GET 공통 산식

```text
GET payload(ms) = largestResponseBytes / wireBytesPerSecond * 1000
                + totalResponseBytes / effectiveW2CProcessingBytesPerSecond * 1000
GET total(ms)   = GET payload(ms) + additionalBatches * (RTT + control)
```

- `min(workers,8)`과 4MiB 전환을 삭제했다. worker 증가가 coordinator의 aggregate processing을 자동으로 나누지 않는다. 불균형 shard는 largest response의 wire 항목에 반영된다.
- ordinary GET/reusable GET/in-band 결과가 같은 payload 산식을 사용한다. 별도 GET은 추가 batch1, 기존 FED request에 들어 있는 결과는 추가 batch0 계약을 유지한다.
- `SYSDS_FED_COST_NET_SERDES_BW_W2C`를 aggregate response-processing **MiB/s** 계수로 사용한다. generic `SYSDS_FED_COST_NET_SERDES_BW` 상속, directional0=processing 비활성화 계약은 유지한다. 필수 실측 상수14.7을 새로 하드코딩한 것이 아니다.
- 구 `SYSDS_FED_COST_INBAND_RESULT_SERDES_BW_W2C`, `SYSDS_FED_COST_REUSABLE_GET_VAR_FAST_MAX_MB`는 명시 property/env가 있을 때 키별1회 migration 진단을 stderr에 출력하고 무시한다. 기본 log level ERROR에서 WARN이 사라지는 문제를 독립 검토가 발견하여, 전역 로그 설정을 바꾸지 않고 구성 진단 자체가 보이도록 수정했다.
- stale 테스트의 `W5 cost = W1/5`, `BROADCAST GET = one response` 가정도 수정했다. 전자는 wire만 감소하고 총 processing은 그대로이며, 후자는 실제 map의 모든 응답을 처리한다. FULL은 단일 source 계약을 유지한다.

### 검증 및 재현

- 최초 factor RED: aligned와 remote U 비용이 모두 `2.4850985953211784`로 같아 기대 assertion 실패. 수정 후 GET/PUT 각각을 검사한다.
- 새 factor14회귀: ROW aligned/misaligned, CP owner, shared CP consumer, 두 owner의 GET1/PUT2, COL W/direct owner의 V COL_T reuse, FULL W1, unknown/LOUT, generated transpose, 중복 range의 다른 worker, per-worker 혼합 alignment 거부.
- GET 회귀: W1/3/8/16, 4MiB 양쪽의 연속성, skew, rate0, property/env migration 진단 키별1회, ordinary/reusable/in-band 및 batch parity.
- 패치 클래스 검증 이후, 새 final JAR만으로 테스트를 다시 컴파일해 검증한다. 과거 R54 JAR/patch 클래스 혼합은 최종 성공 근거로 사용하지 않는다.

```bash
cd /grid/3/cofee-lm-sweep-mchoi-20260914/cost-reuse-transport-r55-20261005/source
/home/hadoop/apache-maven-3.9.7/bin/mvn -B -DskipTests -Dmaven.test.skip=true package
bash ../evidence/run-suite.sh final cost
bash ../evidence/run-suite.sh final adjacent
```

Maven package는 테스트를 건너뛰므로 후속 packaged-JAR 회귀 결과와 함께 해석한다. timeout wrapper 없음. 검증 로그/manifest/동기화 증거는 위 경로의 `../evidence/`에 보관한다.

### 수정 파일 / 보존 범위

Production3파일:
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java`

Test6파일: `FederatedNetworkCostUnificationTest`, `FederatedCostModelFixedInstructionStageTest`, `FederatedCostModelFallbackTest`, 새 `GetAggregateProcessingCostTest`, `ExactFusedFactorReuseCostTest`, `WdivmmFactorAlignmentTest`.

기존 CG/MM-chain, runtime/AggLocal, privacy/Oracle/TR-TW/recompile 동작과 main의 target symlink/HEAD는 보존한다. 새 의존성, runtime fallback, 후보 축소 없음. cleanup은 비용 중복 및 근거 없는 정책 삭제이며, unknown geometry에서 전송비를 유지하는 것은 비용의 증거 부족 처리이지 runtime 우회가 아니다. UI 해당 없음, 별도 security scanner는 수행하지 않았다.

### 잔여 이슈 / 위험 / 탐지

1. **이번 aligned 수정 범위는 R54 fused U/V 모델**이다. 기존 explicit `QuaternaryOp` 준비 경로의 FType-only 근사와 서로 다른 TRead 간 alias materialization 통합은 별도 기존 한계로 남는다. 이들까지 전면 해결했다고 주장하지 않는다.
2. aggregate processing 계수의 절대값은 여전히 환경 calibration이다. 8/4MiB 근거 없는 구조 가정은 제거했지만, 새로운 실제 처리량 측정을 했다는 뜻은 아니다. shared coordinator NIC 경합, unknown range의 balanced/full 근사도 남는다.
3. 비용 재순위로 다른 합법 계획이 선택될 수 있다. 다음 동일 Docker runtime 검증에서 plan/실제 GET·PUT/수치 결과/시간을 함께 대조해야 한다. 이번 수정으로 모든 workload planning20초 또는 runtime 개선율을 확인한 것은 아니다.
4. commit/push 및 runtime 실험 재개는 이번 요청 범위에 없으므로 하지 않는다.
