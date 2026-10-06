# 비용 추정 나머지 항목 재검토: 물리량과 실행 소유권으로 통일

## 범위와 결론

- **요청**: `cost_estimater_fix.md`에서 R52에 남긴 항목을 현재 소스로 재검토하고, 가능한 한 계산을 통일하며 예외를 줄인다.
- **대상**: `/home/mchoi/w1357-paper-aligned-refactor`의 **R52 로컬 수정까지 포함한 working tree**. HEAD `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592`만 읽은 검토도, origin/main의 검토도 아니다.
- **이번 결과는 분석이며 구현이 아니다.** production/test 코드를 수정하거나 빌드·테스트·runtime 실험·commit·push를 수행하지 않았다. 검토 결과와 필수 세션 기록만 문서화한다.
- **결론**: 앞선 검토는 보정을 즉시 삭제할 위험에 치우쳐 통일 가능한 범위를 너무 좁게 잡았다. 현재 소스에서는 **잘못된 비용을 덮는 보정**, **같은 runtime에 적용하는 서로 다른 정책**, **반드시 유지해야 하는 실행 차이**를 구분할 수 있다. 앞의 두 종류는 제거·통일하고, 마지막 종류는 별도 비용식이 아니라 공통식의 물리량/단계/소유권 입력으로 표현하는 것이 타당하다.

표기: **증거**는 소스/기존 테스트의 직접 관찰, **판단**은 그로부터의 수정 방향, **미확인**은 실행·측정 없이 확정할 수 없는 부분이다. 아래 행 번호는 이 working tree 기준이다.

## 우선순위별 판정

| 우선순위 | 원문 항목 | 현재 소스에 근거한 판단 | 확신/제한 |
|---|---|---|---|
| 1 | §10 AggUnary cap | 입력 scan을 출력 크기만의 비용으로 덮는 cap은 잘못됐다. 실제 worker reduction work로 대체하고 제거한다. | 높음: kernel 실행이 mapping 생성보다 먼저 일어난다. |
| 1 | §4 DML function floor | 본문이 별도 계상되는 비실행 DML call placeholder에 추가 payload/FLOPs surrogate를 붙이지 않는다. | 높음: production 도달 경로 확인. 모든 FunctionOp를 무료로 만드는 변경은 아님. |
| 2 | §1·3 whole-cost/W 및 unscaled 목록 | worker별 FLOPs/read/write를 계산해 CP와 같은 식으로 평가하면 blanket scaling과 다수 unscaled 분기를 없앨 수 있다. | 높음: shard kernel 실행 확인. 정밀 sparse/skew 물리량은 추가 검증 필요. |
| 2 | §4 WDivMM floor | rank/active-weight를 기본 kernel FLOPs에 넣고, 명시적·fused WDivMM 모두 같은 산정기를 사용한다. 사후 max/floor는 제거 대상이다. | 높음: rank-width kernel 확인. fusion의 실행 소유권은 유지. |
| 2 | §10 Indexing cap | slice/overlap의 실제 work를 기본값으로 계산하고 CP/FED의 중복 min cap을 제거한다. | 높음: runtime이 range filter 후 slice 실행. sparse 탐색량은 별도 확인. |
| 2 | §5·8 network helper와 codec | 공통 payload 산식으로 통일 가능하다. collect/reusable GET의 다른 codec 정책은 runtime 경로 차이로 정당화되지 않는다. | 경로 동일성 높음, 정확한 codec 병렬 계수는 미확인. |
| 3 | §6·7 RTT/control | RPC batch 횟수와 control을 명시적으로 분리하되 왕복을 없애거나 RTT/2로 할인하지 않는다. | 높음: 현재 campaign은 RTT를 주입하고 FOUT도 응답한다. |
| 3 | §11 movement/materialization | 같은 transfer 가격을 공유하고 activation 수명 계층은 유지한다. 현재 주요 경로도 이미 가격×생성 횟수다. | 높음: source version/context 기반 factor와 회귀 테스트 존재. |

§2·9·12의 R52 구현은 별도 [선별 수정 기록](COST_ESTIMATOR_SELECTIVE_REPAIR_2026-10-05_KO.md)에 남긴다. 특히 §2가 boundary upload의 worker 수까지 모두 해결한 것은 아니다.

## 1. 계산 비용: 다른 식이 아니라 다른 작업량

**증거**:
- `FederatedCostModel.java:342–368`은 ternary/cell-nary/indexing/transpose/all-broadcast를 unscaled로 두고 나머지는 local 비용 **전체**를 W로 나눈다.
- `TernaryFEDInstruction.java:199–204,345–346,375–387`, `BuiltinNaryFEDInstruction.java:143–199`은 shard 연산에 필요한 local 입력을 `broadcastSliced`하기도 한다. **local 입력이라는 이유만으로 전체 복제라고 볼 수 없다.**
- `ReorgFEDInstruction.java:184–201`은 worker에 PUT와 transpose 실행을 보낸 후 map을 바꾼다. transpose가 모두 mapping-only라는 전제도 맞지 않는다.
- `PlacementCostSemantics.java:693–703`은 latent WDivMM에 다시 별도의 whole-cost/W 보정을 적용한다.

**판단**: 기존 roofline 가정 자체는 유지하면서, 평가 입력을 시간값에서 물리량으로 바꾼다.

```text
Exec(F, R, W) = max(F / computeRate, R / memoryRate) + W / memoryRate

CP kernel:  Exec(coordinator quantities)
FED kernel: max_i Exec(actual worker i quantities)
```

여기서 W는 위 식 안에서 **write bytes**이며 worker 수가 아니다. worker 병렬 실행의 critical path를 max로 나타내는 것은 모델 가정이지 모든 실행의 실측 등식은 아니다.

- partitioned operand는 해당 worker shard, replicated operand는 해당 worker가 읽는 전체 operand를 반영한다.
- partial reduction 결과가 worker마다 전체 벡터라면 output write도 각 worker의 전체 벡터다. 최종 출력 bytes/W로 대신하지 않는다.
- slicing은 runtime이 사용하는 range에 맞춘다. exact partition이 없으면 균등분할 등 추정임을 명시한다.
- FULL/BROADCAST도 별도 가격 예외가 아니라 실제 배치·중복 작업량으로 표현한다.
- `FTypeProfile`은 output FType 집합이지 모든 입력의 slice/replication을 표현하는 구조가 아니다 (`RulesApi.java:263–285`). 이것만으로 물리량을 산출할 수 있다고 가정하지 않는다.

재사용 가능한 기존 증거는 `PlacementAnalysis.CandidateInputState/CandidateRuleKey` (`1055–1085`), `CandidateEmissionFact` (`1519`), `AnchorProvenanceObserver`의 partition 정보 (`47–54,263–264`), R52 occurrence shape resolver (`PlacementCostSemantics.java:425` 이후)다. 새 거대 framework보다 기존 kernel별 산정과 cost projection을 연결하는 변경이 적합하다.

## 2. AggUnary와 Indexing cap은 같은 문제가 아니다

### AggUnary: 입력 작업 누락 — 수정 우선

**증거**: `FederatedCostModel.java:411–427`은 output cells/bytes만으로 만든 가격과 기본 compute 가격 중 작은 값을 취한다. 그러나 `AggregateUnaryFEDInstruction.java:194–199`는 실제 aggregate instruction을 worker에서 실행한 **뒤** output map을 만든다.

**판단**: 작은 결과가 나온다고 입력 reduction이 사라지지 않는다. 예를 들어 같은 행 수의 row-sum에서 열 수가 증가하면 출력 크기는 같아도 입력 작업량은 증가한다. 이를 보존하는 worker FLOPs/read/write로 교체해야 한다. FOUT/LOUT 차이는 결과 회수·병합 단계에서 표현하고 worker scan을 깎는 근거로 쓰지 않는다.

### Indexing: slice 작업은 타당하나 사후 cap은 불필요

**증거**: `IndexingFEDInstruction.java:176–228`은 입력 federation map을 범위로 filter하고 worker별 overlap bounds로 rightIndex를 실행한다. CP도 `MatrixIndexingCPInstruction.java:80`에서 `slice(...)`한다. 반면 비용은 `FederatedCostModel.java:536–590`의 CP/FED helper 양쪽에서 min cap으로 보정한다.

**판단**: slice kernel이 만지는 영역을 먼저 산정하면 양쪽 cap과 blanket unscaled 분기를 제거할 수 있다. FED 참여 worker 수도 전체 pool이 아니라 겹치는 partition 기준이어야 한다. sparse 자료구조에서 output bytes가 곧 모든 탐색 read bytes인지는 아직 증명하지 않았다.

## 3. Floor를 없애려면 runtime kernel과 실행 소유권을 바로잡는다

### WDivMM

**증거**: `ComputeCost.java:216–229`의 WDIVMM은 weight cells에 약4 FLOPs만 반영한다. 이를 `FederatedCostModel.java:1237–1277`과 `PlacementCostSemantics.java:975–1025,1084–1097`의 rank-aware floor가 보정한다. 실제 `LibMatrixMult.java:4185–4255`는 rank 길이 dot/vector update를 하고 sparse 경로 (`3272–3365`)는 active weight entries를 순회한다.

**판단**: 기본 FLOPs 산정에 rank와 active cells를 반영하고 explicit/fused WDivMM이 이를 공유해야 한다. dense logical-cell floor를 sparse 입력에 무조건 적용하는 것도 피한다. 기존 `DirectWdivmmRuntimeFact`/`LatentWdivmmTransposePairFact`는 실제 실행 kernel 식별에 재사용한다. fusion에서 제거되는 intermediate 비용을 다시 더하지 않는다. floor의 이름만 바꾸어 동일한 이중 보정을 유지하는 것은 통일이 아니다.

### DML call placeholder — 추가로 확인된 과계상 경로

**증거**:
1. `PlacementProgramFacts.java:585–590`은 DML FunctionOp를 **non-executing FUNCTION_CALL placeholder**로 분류한다. 실행 가능한 multi-return builtin은 OPERATION이다.
2. `ExactPhysicalModel.java:448–471`은 decision node를 domain으로 만들며, `SharedPlannerFunctionPlanPropagationRedTest.java:194–203`에도 DML FUNCTION_CALL decision node가 있다.
3. `ExactPhysicalCostModel.java:819–823`의 zero 처리는 FUNCTION_INPUT/OUTPUT만 포함한다. FUNCTION_CALL은 `845–846` → `cpUnaryCost:2243–2247` → `unitLocalCost:2126–2130` → `PlacementCostSemantics.java:400–422` → FCM `1219–1234,1304–1329`의 FLOPs floor와 payload scan 비용에 도달한다.
4. 본문은 `OccurrenceExecutionFrequencyFacts.java:326–363`에서 각 call context의 횟수로 별도 인덱싱된다. production은 guarded function context가 없으면 거부하며 (`ExactPhysicalCostModel.java:429–430`), DML body가 없으면 `PLACEMENT_FUNCTION_ROOT_UNPROVEN`으로 실패한다 (`OccurrenceExecutionFrequencyFacts.java:350–354`).

**판단**: 본문 FLOPs를 정확히 두 번 더한다는 뜻은 아니지만, **본문 비용 + 근거 없는 call-level cell surrogate**를 더하는 경로다. opaque DML fallback을 위해 이 floor가 필요하다는 근거는 현재 production surface에서 없다. 기존 `analysis.isDmlFunctionCallBoundary(key)` 등 비실행 경계 계약을 이용해 payload/FLOPs를0으로 두고 본문·실제 전달 비용을 각 소유자에게만 계상하는 것이 타당하다. 실제 call control overhead를 모델링한다면 별도 측정 가능한 control 항이어야지 전체 입력 scan의 대용이어서는 안 된다. 모든 FunctionOp/builtin을0으로 만드는 변경은 아니다.

## 4. 네트워크: 산식 통일과 정책 통일을 분리해서 검증

**증거**: FCM의 다음 helper들은 bandwidth+codec 산식을 중복 구현한다.

| helper / 행 | wire critical bytes | codec critical bytes | 추가 fixed stage |
|---|---:|---:|---:|
| `computeDirectionalNetworkCost:2177` | 전체 D | 전체 D | 1 |
| `computeParallelDownloadCost:2127` | D/fanIn | 전체 D | 1 |
| `computeParallelInBandResultPayloadCost:1086` | D/fanIn | D/fanIn | 0 |
| `computeReusableMaterializationDownloadCost:2111` | D/fanIn | D/fanIn | 1 |
| `computeInBandUploadPayloadCost:2162` | D×replication | 같은 양 | 0 |

**같은 GET 경로**: `PrefetchCPInstruction.java:60–71` → `MatrixObject.java:553–573` → `FederationMap.java:464–476` → `FederatedData.java:268–283,348–363`. 일반 collect와 reusable materialization은 같은 pooled connection/decoder를 사용한다. 따라서 두 목적에 서로 다른 codec 병렬 정책을 붙이는 것은 **protocol 차이로 증명되지 않는다**. 재사용 횟수는 아래 activation 계층의 문제다.

**판단**:
1. 순수 `Payload(wireCriticalBytes, codecCriticalBytes, directionalBandwidth, codecRate)` 산식을 하나로 만든다. wrapper는 입력 물리량과 단계만 정한다. 이 단계는 기존 수치 보존으로 검증 가능하다.
2. 같은 runtime GET에는 같은 codec/concurrency 정책을 적용한다. 이것은 실제 가격 변경이므로 위 arithmetic 정리와 분리 검증한다. 호출 목적(reusable/ordinary)만으로 요율을 바꾸지 않는다.
3. 현 coordinator transport 생성 코드는 `DMLConfig.java:135`의 상수8을 `FederatedData.java:720–723`에서 event-loop 수로 사용한다. 이는 동시 codec8 보장도, 무한 W-way decode 증거도 아니다. 무조건 D/W 또는 무조건 D 중 하나를 소스만으로 정답이라 하지 않는다.
4. reusable GET의4MiB threshold (`FCM:2119`)에 대응하는 runtime 분기는 확인되지 않았다. `FederatedWorker.java:137–155`의 encoder는 예상 응답 크기로 buffer를 만든다. threshold 측정 provenance도 제한 검색에서는 찾지 못했다. 기존 rate 전체가 무근거라는 뜻은 아니다.

**Calibration 한계**: `SESSION_ISSUES_2026-08-23.md:18–34`, `2026-02-25.md:37–49`, `2026-09-06.md:80`에는 in-band/전송 hotspot/codec 측정 배경이 기록되어 있다. 특히14.7MB/s를 순수 codec rate로 부르면 안 된다는 이전 기록이 있다. 이번에는 연결된 외부 원시 측정물을 재검증하지 않았다. 검증되지 않은4MiB 경계를 runtime 불변조건처럼 설명하지 말고, 공통 calibration으로 정리하거나 근거를 확보한 후 제거한다.

## 5. RTT/control도 같은 단계 모델로 표현하되 왕복을 지우지 않는다

**증거**:
- `freeze_campaign_conditions.py:312–324`는 `rtt_ms / 1000`을 `SYSDS_FED_COST_NET_LATENCY`에 넣는다. FCM `594–600`도 logical instruction batch당 one round trip이라고 명시한다.
- `FederationMap.java:364–377`, `FederatedData.java:439–455`는 worker마다 request 배열을 한 번 제출한다. `FederatedWorkerHandler.java:302–374`는 배열을 처리해 최종 응답을 고른다. EXEC+GET+cleanup도 같은 batch일 수 있다 (`AggregateBinaryFEDInstruction.java:452–459`).
- FOUT의 EXEC도 SUCCESS_EMPTY/nnz 응답을 보낸다 (`FederatedWorkerHandler.java:165–169,216–218,758–759`). payload가0이어도 RPC가0은 아니다.

**판단**: 공통 network payload 식과 **의존적인 request-response batch 수×RTT**, control 항을 분리한다. in-band result에는 이미 계산한 실행 batch의 RTT를 또 더하지 않고, 별도 GET에는 새 batch를 더한다. 왕복을 두 편도 leg로 표기할 수는 있지만 왕복 전체를 RTT/2로 할인해서는 안 된다. control 분류 이동은 총비용을 바꿀 이유가 아니다. 현재 refed 경로의 collect+upload도 근거 없는 direct F→F 한 번으로 바꾸지 않는다.

## 6. Materialization은 가격 예외가 아니라 수명/재사용 문제

**증거**: `ExactPhysicalCostModel.java:975–1039`은 transfer unit price를 만든 뒤 source value version, output layout identity, creation context와 실제 선택 demand로 activation factor를 만든다. `1110–1159`는 단가×activation 수를 계상한다. 이것은 별도의 물리적인 materialization 세금이 아니다.

`materializationActivation:1077–1103`과 `ExactMaterializationActivation.java:144–166`의 cap은 source lifetime 내 생성 횟수/사건 합집합의 상한이다. AggUnary처럼 비싼 kernel을 작은 output 가격으로 깎는 cap과 **의미가 다르다**.

**판단**: movement와 materialization의 transfer primitive는 공유하고, 활성화·수명·version/context 식별은 유지한다. 변경되지 않는 값을 loop에서10회 쓰는 경우 한 번 생성, 매회 변경되는 값은10회 생성이라는 기존 계약을 지우면 과거 반복전송 오추정으로 돌아간다 (`ExactActivationMaterializationCostTest.java:61–79`). 불확실한 branch 중첩을 독립이라고 가정해 임의 확률을 곱하지 않는다.

추가 잔여: `ExactPhysicalCostModel.java:983–984` 등의 upload는 아직 graph-wide workers를 전달한다. R52에서 실행 pool을 고쳤다고 output/relocation 대상 pool까지 고쳐졌다고 볼 수 없다. 가격식은 그대로 두고 해당 emission의 source/target layout 증거를 쓰는 방향이 맞다.

## 구현 시 검증해야 하는 경계

이 절은 **후속 수정의 수용 기준**이며 이번에 테스트를 실행했다는 뜻이 아니다.

- 동일 kernel/shape/representation에서 W1 CP/FED의 worker 연산량은 일치하되 RPC/전송/control 비용은 별도로 남는다.
- 같은 출력 크기에서 aggregate 입력량을 늘리면 worker 작업량이 늘어난다.
- 분할 작업은 shard 크기에 따라, 복제 작업은 전체 operand에 따라 산정한다. 같은 W라도 불균형 partition이면 max-worker 비용이 달라진다.
- ternary/nary/transpose는 실제 shard 및 sliced 입력에 맞춰 평가한다. 기존 `testElementwiseTernaryFederatedComputeUsesUnscaledFloor` 같은 테스트는 runtime 계약 대신 현재 보정을 고정하므로 새 물리량 회귀로 대체해야 한다.
- indexing은 overlap worker/range를 반영한다. WDivMM은 rank와 sparse active entries에 반응한다.
- DML call placeholder execution은0, 실제 본문 및 multi-return builtin execution은 양수, 함수 호출 횟수와 실제 경계 전송은 유지한다.
- 같은 GET topology의 ordinary/reusable payload 단가는 같고 생성 횟수만 다르다. in-band GET 추가 RTT0, standalone GET 추가 RTT1, FOUT EXEC RTT1을 검사한다.
- activation의 once-per-lifetime, loop-updated value, branch overlap, 서로 다른 함수 context 회귀를 보존한다.
- shape/worker/kernel 물리량을 cache 입력에 추가하면 그 차이를 key에도 반영한다. 출력 map과 실행 pool을 다시 혼동하지 않는다. unknown/bounded shape를 exact fact로 승격하지 않는다.
- 후보군/privacy/oracle/runtime fallback/TR-TW 제약은 바꾸지 않는다. kernel primitive 때문에 새 DP decision dimension이나 후보별 전체 graph 재탐색을 만들지 않는다.

**DP-local과 DP-global은 이미 같은 physical cost surface를 호출한다** (`FederatedPlanLocalCost.java:53–61`, `FederatedPlanExact.java:59–68`). estimator를 각각 고치는 것이 아니라 공통 물리량/가격 계층을 고쳐야 한다. 그러나 비용식 정확도 개선이 global DP 탐색시간20초 달성이나 runtime 개선을 보장한다는 결론은 이 검토에서 나오지 않는다.

## 소스 위치와 재현

주요 별칭의 전체 경로(위 runtime 파일은 해당 패키지 아래):
- FCM: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
- PCS: `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java`
- EPC: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java`
- placement facts: `src/main/java/org/apache/sysds/hops/fedplanner/placement/`
- runtime instruction: `src/main/java/org/apache/sysds/runtime/instructions/{fed,cp}/`
- runtime transport: `src/main/java/org/apache/sysds/runtime/controlprogram/federated/`
- MatrixObject: `src/main/java/org/apache/sysds/runtime/controlprogram/caching/MatrixObject.java`
- LibMatrixMult: `src/main/java/org/apache/sysds/runtime/matrix/data/LibMatrixMult.java`
- campaign configuration: `scripts/fedplanner/freeze_campaign_conditions.py`

검토 시점 SHA256:

```text
1cb8cb7bb60d2947ea017f51e024480cd60111c73b875b8e892d604353c3eba5  ComputeCost.java
d12f921d55a939c5a145e9a15020321c95bff5fc309d7d9a9dc69cf76dd439fb  FederatedCostModel.java
124fef81fc8b2c0a093c33a5c01d1e177b408a3e8f0b47c68b165cc2b8377d24  ExactPhysicalCostModel.java
83c310c12f8a299e9d26d9ee042370dbfcc9ec0f546d174c57cf5c0559a49cf5  PlacementCostSemantics.java
```

정적 코드 경로를 두 독립 native agent의 compute/function 및 network/runtime 조사와 함께 대조했다. 과거 R52의306 PASS를 이번 미구현 변경의 검증으로 재사용하지 않는다. 남은 미확인은 codec/NIC/CPU의 실제 병렬 처리량, sparse/skew의 정밀 작업량, 전체 workload별 새 plan/runtime이다.
