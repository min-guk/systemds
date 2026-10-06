# 논문 Cost Model과 현재 구현의 정합성 분석 및 수정안

- 작성일: 2026-10-05
- 대상: 사용자가 제공한 `Cost Model and Optimization Objective` 절의 여섯 식과 설명
- 기준 작업공간: `/home/mchoi/w1357-paper-aligned-refactor`
- 기준 HEAD: `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592`
- **비교 대상은 HEAD만이 아니라 R54–R56 변경이 반영된 현재 dirty working tree다.**
- **R57 작성 당시 작업은 분석·문서 작성만이었다. 아래 현행 코드 비교와 line 참조는 R54–R56 snapshot 기준으로 보존한다.**
- **사용자 후속 결정 반영:** 논문과 비용 모듈 모두 one-way stage를 기본 단위로 한다. 목표 network 식은 **latency + bandwidth 시간 + codec 시간**의 세 항뿐이며, 별도 fixed/control/RTT 가산항은 두지 않는다. 초기안의 `t_fixed` 도입 권고는 철회한다. §4.1은 수정 전 코드 설명이다.
- **R58 후속 구현 완료:** one-way 공통 primitive, 별도 control 제거, 실제 request/response latency, 방향별 설정·MiB/s 단위 및 campaign wiring을 반영했다. FULL transpose의 잘못된 통신비 면제도 제거했다. [구현 범위·검증·잔여 과제](COST_ONEWAY_NETWORK_IMPLEMENTATION_2026-10-05_KO.md)를 참조한다. Shared-NIC profile 확장, 함수 lifetime 통합, 새 calibration까지 완료한 것은 아니다. 새 runtime 실험·commit·push는 하지 않았다.

## 1. 결론

**목적함수와 재사용 계산을 다시 만들 필요는 없다. 공통 네트워크 모듈을 논문과 같은 one-way 세 항 모델로 통일하고, 실행 단계·통신 stage 구분·보정 수준을 정확히 표현하는 것이 핵심이다.**

현재 코드와 논문은 다음 뼈대를 공유한다.

1. 한 프로그램 실행의 비용은 **실행 횟수로 가중한 연산 비용 + 생성 횟수로 가중한 명시적 이동 비용**이다.
2. 연산은 `max(compute, input read) + output write`로 가격화하고, 병렬 worker 실행은 합이 아닌 최댓값을 사용한다.
3. 연산 내부 통신과 명시적 GET/PUT은 **같은 네트워크 payload 산식**을 사용한다. 비용을 내는 주체와 횟수만 다르다.
4. 정확히 증명된 copy sharing은 activation OR로 한 번 과금한다. 불명확한 sharing은 capped union으로 계산한다.
5. 재사용 때문에 연산 실행 횟수까지 줄이지 않으며, 제거된 fused shell이나 단순 metadata node에 가상의 실행 비용을 붙이지 않는다.

그러나 **“논문 그대로 이미 구현되어 있다”라고 쓰면 안 되는 부분**이 있다.

| 우선순위 | 차이 또는 한계 | 판단 | 조치 |
|---|---|---|---|
| P0 | 논문의 one-way latency와 코드의 request/response **batch RTT + control**이 다름 | 직접 확인, 높음 | 코드를 one-way stage 세 항 식으로 통일. 별도 fixed/control 제거, 실제 요청·응답 stage만 계산 |
| P0 | GET wire cost에 coordinator **공유 NIC 유입량**이 명시적으로 없음 | 직접 확인, 높음 | 공통 네트워크 primitive를 resource bottleneck 식으로 확장 |
| P0 | Docker shaping Mbit/s → 비용 설정 변환이 decimal MB/s인데 Java는 MiB/s로 해석 | 직접 확인, 높음 | 설정 경계에서 단위 통일. 논문도 bytes/time 단위를 명시 |
| P0 | 현재 processing 계수에 RPC/Netty 처리가 포함되어 pure codec 처리량은 아님 | 직접 확인, 높음 | codec 계수의 측정 범위를 명시. 세 항 모델의 잔차를 보정하되 별도 intercept/control을 넣지 않음 |
| P1 | fused alternative의 직렬 preparation/kernel을 코드가 각각 가격화하지만 논문은 단일 roofline처럼 표현 | 직접 확인, 높음 | execution primitive는 phase별 적용, 직렬 phase는 합산. coordinator는 preparation도 포함 |
| P1 | 논문은 site-calibrated throughput, 코드는 전역 memory rate와 kernel-class compute rate | 직접 확인, 높음 | 현 구현은 homogeneous-site 근사라고 명시. 이를 맞추려고 매 실행 profiler를 만들지 않음 |
| P1 | `c_op(a_v)`가 coarse node placement만의 함수처럼 읽힐 수 있음 | 정의에 따른 조건부 차이 | 선택된 supply/layout까지 포함한 실행 alternative임을 명시 |
| P1 | DP-local을 포함해 항상 전역 최소를 얻는다고 해석할 여지 | 직접 확인, 높음 | 목적함수의 정의와 search backend의 최적성 보장을 분리 |
| P1 | 함수 인자 경계가 일반 lifetime/alias union이 아닌 call-weight 별도 factor를 사용 | 직접 확인; 실제 중복 과금 발생은 미확인 | 동일 가격 primitive 사용과 동일 lifetime union 적용을 구분; §6.4 참조 |

**수정하지 않을 것:** feasibility/oracle, privacy, TR/TW 상태 제약, recompile 제약, runtime fallback 금지, resolved-sharing 식, unresolved capped-union 식. 비용이 잘못되었다고 합법 후보를 닫지 않는다.

## 2. 증거의 범위와 읽는 법

- **확인**: 현재 소스 또는 보존된 이전 실측 artifact로 직접 확인했다.
- **해석**: 확인된 코드에 대한 수학적 대응 또는 예상 영향이다.
- **미확인/제안**: 아직 적용·측정하지 않았다. 예상 성능을 실측 성능처럼 쓰지 않는다.
- 사용자가 제공하지 않은 `Analysis`, `Search`, operator-frequency 절 전체는 이번 비교의 증거가 아니다. 특히 `a_v`의 세부 정의나 전역 최적성 주장이 다른 절에서 이미 제한되어 있는지는 단정하지 않는다.

소스 약어는 아래 실제 파일을 뜻한다. 본문 줄 번호는 이 문서 작성 시점 기준이다.

| 약어 | 파일 |
|---|---|
| FCM | `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java` |
| EPC | `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.java` |
| EMA | `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactMaterializationActivation.java` |
| PCS | `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java` |
| FREQ | `src/main/java/org/apache/sysds/hops/fedplanner/placement/OccurrenceExecutionFrequencyFacts.java` |
| LOCAL | `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java` |
| GLOBAL | `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalOptimizer.java` |
| DRIVER | `/home/mchoi/cofee-evaluation/driver/run_multihost_campaign_network_quality_v2.py` — 별도 저장소 |
| DOCKER | `/home/mchoi/cofee-evaluation/driver/tools/multihost_docker.py` — 별도 저장소 |

## 3. 식별 대응표

| 논문 | 현재 코드 | 판정 및 필요한 수정 |
|---|---|---|
| `C(P) = Σ f_v c_op + Σ f_m c_net` | physical contribution을 합산한다. execution/result/input/function transfer contribution을 구성한다. EPC:318–331,447–473,914–917 | **구조 일치.** Java factor 이름이 그대로 논문의 op/movement 분류인 것은 아님. native result factor는 의미상 op 내부 비용일 수 있음 |
| `f_v = Σκ f_vκ`를 고정 | immutable occurrence profiles를 한 번 분석하고 합산. FREQ:105–118,135–173; EPC:845–864 | **일치.** 빈도는 추정치이며 실제 측정된 모든 iteration 수라는 뜻은 아님 |
| `max(Ops/ρcomp,Din/ρmem)+Dout/ρmem` | FCM:1033–1051에서 같은 산식; 내부 seconds→milliseconds 변환 | **단일 실행 단계의 산식 일치.** 사이트별 rate와 직렬 단계 범위는 별도 설명 필요 |
| worker critical path `max_k` | worker별 geometry로 수량을 구하고 maximum. PCS:227–241 | **일치.** 균등 `1/W`를 모든 경우에 강제하지 않음 |
| site-calibrated `ρcomp(k),ρmem(k)` | 전역 memory 설정 1개, 일반/행렬 AggBinary compute 설정. FCM:176–177,211–214,1048–1051 | **문장 그대로는 불일치.** site별 실측 완료를 주장하지 말 것 |
| local alternative의 worker/intrinsic 집합은 공집합 | CP 실행은 local cost; CP/FOUT이면 별도 upload contribution. EPC:863–869,914–917 | **일치.** local 연산을 선택해도 전체 계획의 이동 비용은 0이 아닐 수 있음 |
| FED = worker + intrinsic communication + coordinator work | EPC:937–961,2539–2578, FCM:643–715,989–1000 | **구조상 대응.** 직렬 실행 phase 합, 요청·응답 stage, coordinator preparation을 정의하고 별도 control 항 제거 필요 |
| `latency + wire/rate + codec/rate` | 공통 payload 산식 FCM:1878–1888 + 별도 소유된 batch fixed cost | **현재 차이 있음.** 목표는 one-way latency를 유지하고 독립 fixed/control 항을 없앤 세 항 식 |
| 연산 내부/명시적 이동의 같은 `c_net` | in-band result와 retained GET이 같은 GET payload helper; PUT도 같은 payload arithmetic. FCM:960–976,1860–1888,1904–1964 | **이미 공유됨.** 새 비용식을 operation/transfer에 따로 구현할 필요 없음 |
| source/version/availability에 따른 횟수 | EPC:1340–1416,1479–1505 | **일치하는 구현 존재.** source 생성 횟수와 consumer 횟수를 구분 |
| resolved `Σ_g f_g OR_j d_hj` | EMA partition + EPC activation-class monetary factor. EMA:81–137,209–219; EPC:1577–1590 | **직접 일치.** 증명 가능한 event 구조에 한정 |
| unresolved `min(scope,Σ selected bound)` | EMA:140–166; EPC:1526–1572 | **직접 일치.** selected 중복/포함 event 정규화를 거친 집합으로 해석 |
| duplicate OR 보존 / contained event의 containing selection 필요 | EMA:84–96,148–166; EPC:1541–1572 | **일치.** 단순히 큰 event가 존재한다는 이유로 작은 독립 선택을 삭제하지 않음 |
| initial/native output에 추가 creation charge 없음 | nonexecuting shell 제거 PCS:873–895, boundary activation 조건 EPC:1324–1338 | **한정해 일치.** 생산 연산·원래 입력 읽기·필요한 후속 GET까지 무료라는 뜻은 아님 |
| 전체 feasible set에서 최소화 | 같은 physical objective를 사용하되 LOCAL과 GLOBAL search가 다름 | **목표는 같음. 보장은 같지 않음.** §5.3 참조 |

## 4. 네트워크 비용: 현재 식과 실제로 바꿀 식

### 4.1 현재 공통 가격 산식

payload를 보내는 worker별 바이트를 `D_i`, `DΣ = Σ_i D_i`, `Dmax = max_i D_i`라고 쓰자. 아래 표현의 byte와 throughput은 같은 단위로 정규화되어 있다고 가정한다.

```text
현재 GET payload = Dmax / B_W2C + DΣ / P_W2C
현재 PUT payload = DΣ   / B_C2W + DΣ / P_C2W
고정비            = 자신이 소유한 추가 batch 수 × (RTT + control)
```

- GET: `computeGetResponsePayloadCost`는 largest bytes와 total bytes를 구분한다. 정확한 응답 geometry가 없으면 balanced approximation을 사용한다. FCM:1891–1911, PCS:243–256.
- PUT: BROADCAST는 이미 `logical payload × 실제 worker 수`로 총량을 계산한다. 일반 partitioned upload는 logical payload 총량이다. FCM:1931–1954.
- `FULL`은 단일 worker map이고 `BROADCAST`는 복제 map이다. FULL GET에 전역 worker 수를 곱하지 않는다. FCM:1840–1846.
- processing은 aggregate response/endpoint 처리량이다. **worker 수·event-loop 8개·4MiB threshold로 나누지 않는다.** FCM:1900–1911.
- Java processing 설정의 `0`은 이 항을 끄는 호환 의미다. 실제 처리량이 0인 환경이라는 뜻이 아니다. 논문에서는 사용하지 않는 항을 0으로 두거나 rate를 무한대로 해석해야 하며 `D/0`으로 쓰면 안 된다. FCM:120–126,145–146,1886–1887.

호출 관계의 핵심은 다음과 같다.

```text
operator intrinsic result ─── GET payload helper ─┐
retained materialization ──── GET payload helper ─┤
operator in-band preparation ─ PUT payload ──────┼─ computeNetworkPayloadCost
explicit upload / REFED PUT ── PUT payload ──────┘

batch 고정비와 creation activation 횟수는 각 실제 owner가 별도로 부여
```

따라서 사용자의 질문인 **“operation cost와 explicit transfer/materialization이 네트워크 내부항을 공유하나?”에는 현재도 그렇다고 답할 수 있다.** 다만 *payload primitive 공유*, *추가 RTT 소유권*, *copy 생성 횟수 공유*는 서로 다른 문제다.

### 4.2 확정 수정 방향: one-way stage 세 항으로 통일, fixed/control 제거

현재 코드는 FED instruction의 병렬 request/response batch에 RTT 한 번을 붙인다. worker마다 RTT를 더하지 않는다. FCM:488–511,244–254. 실제 campaign도 `rtt_ms/1000`을 Java latency 설정에 넣는다. DRIVER:480.

현재 설정은 bandwidth/processing은 방향별이지만 RTT와 control은 공통 값이다. FCM:178–205. **이 현행 구조를 논문에 맞추도록 바꾼다. 논문만 one-way로 쓰고 구현에 RTT+control을 별도 기본항으로 남겨 두는 안이 아니다.**

공통 primitive의 입력 단위 `x`는 **실제로 발생하는 한 방향의 통신 stage**다.

```text
networkCost(x)
  = oneWayLatency(direction(x))
  + wireTransferTime(x)
  + codecTime(x)
```

- 별도 `fixedCost`, `controlMs`, `RPC overhead`, `RTT` 가산항을 두지 않는다.
- 기존 `SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS` 및 FED coordination 가산은 목표 비용 경로에서 제거한다. 기존 0.35ms를 latency나 coordinator execution 비용으로 옮겨 숨기지 않는다.
- RTT는 실제 왕복 stage들의 latency 합을 설명하거나 기존 설정을 변환할 때만 등장한다. 핵심 비용 primitive의 독립 입력/항이 아니다.
- 수량을 만들 때 같은 batch·같은 방향의 payload를 하나의 stage에 모은다. PUT/EXEC/GET 같은 helper 호출 이름마다 stage를 새로 만드는 방식은 쓰지 않는다.

| 실제 runtime 통신 | 공통 모듈에 전달할 방향별 stage |
|---|---|
| 독립 GET | C2W 요청 + W2C 데이터 응답 |
| 독립 PUT | C2W 데이터 요청 + W2C 완료 응답 |
| PUT·EXEC·GET이 하나의 요청–응답 batch에 포함 | C2W 요청 payload 전체 + W2C 응답 payload 전체. **각 방향 한 stage** |
| 행렬 payload 없는 실제 remote instruction | 요청 및 완료 응답 stage는 존재. 모델에서 payload 바이트가 0이어도 latency는 남음 |
| RPC 없는 metadata 처리 / 이미 쓸 수 있는 copy의 재사용 | 새로운 network stage 없음. 비용 0 |

worker에 병렬로 요청하는 batch를 worker 수만큼 직렬 latency로 과금하지 않는다. 동종 환경의 one-way stage는 latency 한 번이며, 전송량·공유 NIC 제약은 bandwidth 항이 처리한다. 연산 내부 통신은 `I(a)`, 독립 copy 생성의 요청·응답은 `M(P)`에 속하고, **같은 물리 stage는 한 소유자에만 속한다.**

예를 들어 C2W latency 3ms, W2C latency 7ms이면 payload 비용을 제외한 GET 왕복은 10ms다. 여기에 RTT 10ms나 control 0.35ms를 다시 더하지 않는다. 같은 batch의 embedded GET 때문에 새 요청 stage를 더하지도 않는다.

**설정 이전:** 현재 Docker profile은 양 방향에 같은 `one_way_delay_ms = rtt_ms/2`를 적용한다(DOCKER:107–114,437–445). 이 명시적 대칭 환경에서는 양 방향 latency를 각각 `rtt_ms/2`로 전달할 수 있다. 임의의 비대칭 환경에서는 RTT 하나만으로 두 one-way latency를 식별할 수 없으므로 방향별 값 또는 명시적 대칭 가정이 필요하다. 기존 RTT 설정 값을 이름만 유지한 채 one-way로 재해석해 두 배 과금하지 않는다. legacy 변환이 필요하면 설정 입력 경계에만 두고 core에는 one-way 값만 전달한다.

**정확도 한계:** 실제 byte-independent RPC bookkeeping은 사라지는 것이 아니라 이 단순 모델에서 독립적으로 가격화하지 않는 것이다. small-payload 오차가 남을 수 있다. 이를 감추려고 별도 intercept를 다시 만들거나 예전 control 상수를 latency/codec에 자동 전가하지 않는다.

### 4.3 현재 GET wire 식에 빠진 공유 NIC 병목

현재 GET 식은 worker leg의 최댓값을 쓰지만 coordinator가 모든 응답을 받는 공유 NIC의 총량을 독립적으로 제한하지 않는다.

```text
현재: T_wire(GET) = Dmax / B_worker
누락:              DΣ   / B_coordinator_ingress
```

이는 **worker가 늘면 같은 총량 GET이 계속 1/W로 빨라질 수 있는 wire 항**이다. aggregate processing 항이 총량을 과금하므로 전체 GET이 무조건 1/W가 되는 것은 아니다. 그러나 codec/processing 계수에 NIC 병목을 숨겨 맞추면 다른 worker 수·네트워크 환경에서 계수의 의미가 깨진다.

이전 R56 실측에서 LAN shaping은 5000Mbit/s였지만 host NIC link는 1000Mbit/s였다. **5000Mbit/s 설정은 물리 NIC를 5Gbps로 만드는 설정이 아니다.** 이 차이는 미실측 추측이 아니라 보존된 preflight/측정 근거다. 단, 이 실측만으로 모든 future workload의 유효 처리량을 정한 것은 아니다.

### 4.4 제안하는 공통 wire 식 — 아직 미구현

directional stage `x`가 사용하는 네트워크 resource 집합을 `L(x)`, resource `ℓ`을 통과하는 실제 바이트를 `Dℓ(x)`, 고정된 유효 용량을 `Bℓ`로 두자.

\[
T_{\mathrm{wire}}(x)=\max_{\ell\in\mathcal L(x)}\frac{D_\ell(x)}{B_\ell}.
\]

현재 star topology에서는 다음 특수형으로 구현할 수 있다.

\[
T_{\mathrm{wire}}(x)=\max\!\left\{
  \max_i\frac{D_i(x)}{B_i(r(x))},\quad
  \frac{\sum_iD_i(x)}{B_0(r(x))}
\right\}.
\]

여기서 `B_i`는 각 worker leg, `B_0`는 coordinator의 해당 방향 공유 NIC 용량이다. 이질적 worker bandwidth 정보가 없다면 현재처럼 homogeneous worker rate를 사용하고 이를 명시한다. 동종 조건에서는 기존 `totalBytes/largestBytes` 요약을 재사용할 수 있다.

이 star 특수형은 worker leg들이 서로 독립적인 경우다. 여러 worker container가 같은 host NIC를 공유한다면 그 NIC도 `L(x)`의 공유 resource로 두고 통과 바이트를 합산해야 한다. container 수만큼 물리 bandwidth가 늘어난다고 가정하지 않는다.

최종 공통 stage 가격은 다음으로 제안한다.

\[
c_{\mathrm{net}}(x)=t_{\mathrm{lat}}(x)
 +T_{\mathrm{wire}}(x)
 +\frac{D^{\mathrm{codec}}(x)}{\rho_{\mathrm{codec}}(r(x))}.
\]

- `t_lat`: 해당 방향 stage의 one-way network latency. 별도 fixed/control 가산 없음.
- `Dcodec/rho_codec`: 직렬화·역직렬화 등 payload에 비례하는 유효 endpoint 처리 시간. 현 설정의 RPC/Netty 포함 범위를 명시하고 kernel arithmetic/aggregation과 중복하지 않는다. 별도 byte-independent control을 이 항에 끼워 넣지 않는다.
- `Dℓ`: resource별 실제 모델링 payload. bandwidth 비율을 곱해 가짜 wire bytes를 만들어 기존 논문 식과 같다고 주장하지 않는다.
- 모든 throughput은 최적화 시작 전에 고정한다. 계획 선택은 실제 전송 수량·worker set·stage 유무·재사용 횟수만 바꾼다.
- `max`는 공유 병목을 반영하는 **fluid-throughput 근사**다. packet scheduling, slow start, cross-traffic, wire/processing overlap을 모두 정확히 재현하는 runtime 법칙은 아니다.

**이것은 현재 식의 의미 있는 확장이다.** 논문 식을 그대로 두고 “이미 공유 NIC까지 정확히 모델링했다”고 쓰는 대신, 이 wire 항을 논문과 코드에 함께 반영해야 한다.

### 4.5 방향별 rate 제한의 위치

DOCKER:437–455는 각 container `eth0`의 **egress**에 netem을 적용한다.

| 방향 | worker leg 제약 | coordinator 공유 제약 |
|---|---|---|
| W2C / GET | worker별 egress shaping 및 worker NIC | coordinator RX NIC. coordinator의 C2W egress shaping을 RX에 적용하면 안 됨 |
| C2W / PUT | worker RX NIC/실제 경로 | coordinator egress shaping 및 coordinator TX NIC |

같은 resource의 알려진 제한은 최솟값으로 합성한다. **worker별 shaping을 coordinator aggregate rate에 그대로 복사하거나, PUT 총량에 worker 수를 두 번 곱하지 않는다.** 알려지지 않은 NIC를 임의로 1Gbps라고 가정하지 않고 profile의 미확인 항목으로 남긴다.

### 4.6 별도의 확정 버그: decimal MB/s와 MiB/s 혼동

- DRIVER:474–476: `Mbit/s / 8`로 숫자를 만든다. 이는 decimal MB/s다.
- FCM:1884–1887: byte를 `1024²`로 나누므로 설정을 MiB/s로 소비한다.
- DOCKER:455: shaping은 `Mbit/s × 1,000,000 / 8` bytes/s다.

따라서 설정 interface를 MiB/s로 유지한다면 변환은 다음이어야 한다.

```text
MiB/s = Mbit/s × 1,000,000 / (8 × 1,048,576)
1000 Mbit/s = 약 119.2093 MiB/s       # 125 MiB/s가 아님
```

현재 숫자는 bandwidth를 약 **4.8576% 크게**, 그 wire 시간만 약 **4.6326% 작게** 만든다. 이는 전체 runtime 오차율이 아니다. `transport_calibration.py:498`의 기존 예측식도 `/8`을 사용하므로 새 모델 평가 경로는 같은 단위로 맞춰야 한다. **보존된 과거 `R55 prediction` artifact를 소급 덮어쓰지는 않는다.**

FCM 기본값의 “125 = 1Gbps”, “25000 = 25GB/s” 주석에도 같은 단위 혼동이 있다. 외부에서 물리 단위를 변환하는 경계와 이미 설정된 empirical MiB/s 계수를 구분한다. 기존 14.7/210을 근거 없이 재환산하거나, 모든 과거 설정을 자동 치환하지 않는다.

## 5. 실행 비용과 최적화 목적의 차이

### 5.1 실행 primitive는 같지만 site calibration 주장은 강하다

FCM의 현재 산식은 단일 kernel에 대해 논문과 같다. worker별 work quantity도 계산한다. 그러나 throughput은 다음과 같다.

```text
memory: 모든 site에 공통인 설정 1개
compute: 일반 kernel rate / matrix AggBinary rate
worker별 별도 실측 rate: 현재 함수 인자로 받지 않음
```

권장 수정은 **새 site profiler를 추가하는 것이 아니라 논문의 가정을 명확히 하는 것**이다.

> Throughputs are fixed configuration parameters. Our implementation uses
> a homogeneous-site memory rate and kernel-class compute rates; worker-specific
> work quantities still reflect the selected physical partitioning.

수식은 `ρ_comp(k)` 대신 `ρ_comp(k,τ(a))`로 kernel class 의존성을 드러내거나, kernel-class 의존성을 표기에서 생략했다고 설명한다. 현 구현에서는 site별 값이 같다는 특수형이다. site별 실측을 실제 하지 않았으므로 “site-calibrated”를 구현 완료 사실처럼 쓰지 않는다.

coordinator reduction도 arithmetic/read의 max 뒤 output write를 더하는 구조다. select-existing, disjoint bind, partial reduction은 실제 작업량이 다르므로 같은 바이트를 무조건 `W`배 하는 것으로 통일하면 안 된다. FCM:690–715,989–1000. **통일해야 할 것은 수량을 시간으로 바꾸는 산식이지, 서로 다른 runtime 작업량이 아니다.**

### 5.2 `c_op(a)`가 가격화하는 실제 alternative의 범위

EPC:850–855,937–958,1063–1122는 selected support, actual source realization, relocation anchor, worker/range 등을 사용한다. 따라서 단지 `CP/FED, LOUT/FOUT, FType` 세 값만 같다고 같은 비용이라고 볼 수 없다.

- 논문 `a_v`가 이미 이러한 realized layout/supply choices를 포함한다면 **충돌은 없고 한 문장 보강이면 된다**.
- `a_v`가 coarse node label만이면 `ã_v(P)`를 “선택된 edge supplies로 구체화된 실행 alternative”로 정의하고 `c_op(ã_v(P))`로 써야 한다.
- 소스의 고정 논리 dimension/statistics와 계획이 결정하는 physical partition geometry는 서로 다르다. 후자가 바뀌는 것은 “분석 facts 고정”과 모순되지 않는다.
- `K_v` 전체에 하나의 estimate를 쓰는 것은 코드와 같은 모델링 선택이다. 함수 호출마다 데이터 크기가 모두 같거나 context별 실제 시간이 완벽히 같음을 증명한 것은 아니다.

이는 표기를 정확히 하는 작업이다. 이를 위해 edge 선택을 전부 새 node variable로 복제하거나 DP 차원을 늘릴 이유는 없다.

### 5.3 objective 최소화와 backend의 최적성 보장을 구분

- LOCAL:60–109는 동일한 canonical physical objective를 사용하지만 local seed와 incremental regional optimization으로 incumbent를 개선한다. `incremental.upper()`가 선택 비용이다.
- GLOBAL:66–99는 전체 encoded hard/cost problem을 exact solver로 풀고 검증한다. 여기서도 보장 범위는 **고정된 후보/추정 모델 안에서 성공적으로 완료된 solve**다.
- `ExactPhysicalOptimizer.Result`라는 반환 type만 보고 DP-local 결과를 전역 optimum이라 부르면 안 된다.

논문에서 “minimizes”가 **목적함수의 정의**라면 유지 가능하다. 모든 backend의 **달성 보장**으로 쓰려면 다음처럼 제한한다.

> All cost-based backends evaluate the same complete-plan objective. Global exact
> solving returns its minimum over the encoded feasible domain when completed;
> regional/local search may return a feasible incumbent without a global-optimality certificate.

모델 비용의 전역 최적값과 실제 runtime의 전역 최적값도 다르다. 이 절은 DAG 전체의 중첩 실행을 스케줄링한 makespan이 아니라 **가중 additive 실행 비용 모델**이다.

### 5.4 이미 제거된 heuristic을 현재 차이로 오진하지 말 것

- `computeSingleWorkerFedExecPenalty`: FCM:1003–1006은 항상 `0.0`.
- `computeLocalToFedForwardingPenalty`: FCM:1967–1977은 항상 `0.0`.
- EPC:2552–2557에 남은 single-worker penalty 변수/오래된 주석은 실제 양수 penalty의 증거가 아니다.

이 호환 shim/주석은 후속 소규모 cleanup 대상일 수 있지만, 이를 없애야 성능 문제가 해결된다고 주장하지 않는다.

### 5.5 중요한 추가 차이: 직렬 preparation과 kernel을 하나의 roofline으로 합치면 안 된다

실제 코드에는 같은 site에서 수행되는 직렬 실행 phase가 있다.

- PCS:969–985: fused WDivMM의 `U %*% B` 패턴에서 runtime rewrite가 필요한 `V=t(B)`를 생성하면, 그 transpose 실행비를 별도로 계산한다. 이미 명시적 transpose/direct-right operand가 있으면 새 준비비를 중복 부여하지 않는다.
- PCS:884–891: **local** WDivMM kernel 실행비에 `factorTransposeCost()`를 더한다.
- EPC:937–961: **FED** fused owner는 worker kernel critical path 외에 coordinator factor-transpose preparation을 더한다. native local result에는 별도의 응답/aggregation 비용이 뒤따른다.

따라서 원문처럼 FED coordinator 비용을 **post-processing만**으로 정의하면 준비 transpose가 빠진다. local alternative에서도 전체 alternative의 work를 한 번에 합쳐 roofline을 적용하면 현재 구현과 일반적으로 다르다.

phase `p`의 compute/read/write 시간을 `C_p,R_p,W_p`라고 하면:

\[
\underbrace{\sum_p[\max(C_p,R_p)+W_p]}_{\text{현재 직렬 phase 합}}
\;\geq\;
\underbrace{\max(\sum_p C_p,\sum_p R_p)+\sum_p W_p}_{\text{전체 work를 먼저 합친 roofline}}.
\]

phase마다 병목 종류가 다르면 부등호가 엄격해질 수 있다. 예를 들어 phase별 `(C,R)`가 `(1,10)`, `(10,1)`이면 write를 제외하고 **20 대 11**이다. 이 숫자는 수학 예시이며 특정 workload 실측이 아니다.

**권장:** `eq:kernel-cost`를 개별 execution phase의 primitive로 명시하고, 직렬 phase는 합산한다. coordinator phase에는 preparation과 postprocessing을 모두 넣는다. 일반적인 local 또는 worker kernel 하나만 있는 경우 기존 식으로 환원된다. 이미 올바르게 분리된 transpose를 억지로 합쳐 코드 비용을 낮추지 않는다. §9.3에 예외 없는 phase-wise 표현을 제안한다.

## 6. Movement and Reuse: 유지할 부분과 경계

### 6.1 Resolved-sharing 식은 실제 구현과 맞는다

EMA는 event의 weight와 branch conditions를 모아 whole-scope partition을 만든다.

1. 동일한 `(weight, conditions)` event를 합치되 demand index를 보존한다.
2. event 쌍의 containment 또는 mutual exclusion이 증명되어야 한다.
3. containment parent가 유일해야 하고 자식 weight의 합이 부모를 초과하면 안 된다.
4. 조건을 만족하면 활성 패턴별 residual weight와 해당 demand 집합을 만든다.

EPC:1577–1590은 각 group에 `multiplicity × unitPrice`를 만들고 selected demand 중 **하나라도 활성화되었을 때 한 번** 과금한다. 논문의 `Σ_g f_g max_j d_hj(P)`와 같은 구조다.

여기서 “resolved”는 모든 가능한 control flow에 대해 일반적인 확률 추론을 한다는 뜻이 아니다. 현재 증명 가능한 포함/배타 관계와 일관된 고정 weight 범위에서만 resolved다. 나머지를 unresolved로 보내는 것은 논문의 fallback 취지와 맞는다.

### 6.2 Unresolved capped-union도 같은 식이다

EMA:140–166의 순서가 중요하다.

```text
현재 선택된 demand의 event만 수집
→ exact duplicate event 합치기
→ 선택된 containing event가 있을 때만 contained event 제거
→ min(scopeWeight, 남은 event weight 합)
```

논문 `E_h(P)`를 이 selected-event 정규화 이후 집합으로 설명하면 코드와 정확히 대응한다. 선택되지 않은 broad event가 있다는 이유로 narrow event를 지우는 것은 허용하지 않는다.

또한 source 수명보다 더 안쪽 loop에서 branch가 반복되면 then/else를 source lifetime 전체에서 상호 배타라고 볼 수 없다. EPC:1495–1498은 이러한 경우를 별도 repeated-arm으로 표현하고 독립성을 가정하지 않는다.

**“보수적 상계”는 공급된 scope/event bound가 유효할 때의 모델 기대값에 대한 조건부 주장이다.** branch prior나 loop count 추정 자체가 실제 실행의 절대 상계임을 증명한 것은 아니다. 사용자가 준 논문의 “Under the supplied bounds”는 유지해야 한다.

R56의 동일 event quotient + Boolean AND/OR 변경은 이 금액 함수를 바꾸지 않는 solver 표현 축소다. 별도의 할인·pruning·확률 독립 가정이 아니다. distinct event 수가 큰 경우의 지수적 중간 결합 가능성은 여전히 남는다.

### 6.3 공유 단위는 변수 이름이 아니라 실제 copy lifetime

현재 alias GET key에는 `origin`, source occurrence profile, read context, exact physical layout, private identity, unit price가 들어간다. EPC:1207–1212,1340–1416.

서로 다른 TRead를 합칠 때는 다음을 증명해야 한다.

- 동일 source/version 생성 경로와 호환 execution context.
- 같은 copy가 유지되는 availability scope.
- 실제 worker/range map이 정확하고 동일함.
- fresh relocation/derived upload가 아니며 다른 materialization 객체가 아님.
- 같은 값이어도 bytes/price 관측이 다르면 무리하게 합치지 않음.

특히 REFED 이후 새 MatrixObject에 대한 GET은 원래 입력의 긴 lifetime을 무조건 상속하지 않는다. EPC:1343–1346. 업데이트 전·후 값을 묶어 할인하는 수정은 하지 않는다.

논문 `h`에 이 물리 identity를 명시하면 좋다. `u_h(P)=c_net(m)`의 unit price와 `f_m(P)`의 creation count는 계속 분리한다. aligned WDivMM factor도 같은 원칙으로 실제 정렬된 copy가 있으면 이동 자체가 없고, 필요한 GET은 생성 횟수로, instruction마다 실행되는 PUT 준비는 실행 횟수로 가격화한다.

### 6.4 함수 경계의 통일은 별도 계층의 문제

**확인:** EPC:2392–2485의 `addPhysicalLogicalFunctionFactors`는 logical function input 경계에 `callWeight × GET/PUT unit` factor를 별도로 만든다. 일반 materialization 경로의 `addMaterializationActivationFactors` 호출과 동일한 구현 경로는 아니다. 공통 GET/PUT 가격 helper는 사용한다.

따라서 다음 두 주장을 구분한다.

1. “함수 경계도 동일 GET/PUT unit-price helper를 사용하고, 명시적 call-weight factor로 비용을 낸다” — 확인 가능.
2. “함수 경계를 가로지르는 모든 동일 source copy의 activation union이 완전히 통합되어 있다” — 위 사실만으로 보장 불가.

별도 factor라는 이유만으로 중복 과금 버그라고 단정하지 않는다. 실제 emission이 매 호출 새 copy를 생성한다면 call-weight 과금이 맞고, 같은 retained MatrixObject를 재사용한다면 생성 scope 기준 union이 필요하다. **추가 할인은 runtime lifetime 증거를 먼저 확보한 뒤 적용**해야 한다.

현재 runtime에는 실제로 재사용 가능한 기반이 있다.

- `src/main/java/org/apache/sysds/runtime/instructions/cp/FunctionCallCPInstruction.java:163–174,191–202`: actual `Data` 객체를 formal parameter map에 그대로 넣고 호출 중 입력 cleanup을 막는다. matrix 인자가 같은 `MatrixObject`를 공유할 수 있다.
- `src/main/java/org/apache/sysds/runtime/controlprogram/caching/CacheableData.java:574–614,774–801`: 기존 `_data`/cache를 먼저 사용한다. 데이터가 없을 때 federated read를 하며, release가 매번 다음 호출의 원격 GET을 강제하는 것은 아니다.
- `src/main/java/org/apache/sysds/runtime/controlprogram/caching/MatrixObject.java:553–568`: 실제 federated read가 원격 요청과 aggregate/bind를 수행한다.
- `src/main/java/org/apache/sysds/runtime/instructions/cp/PrefetchCPInstruction.java:61–71`: synthetic prefetch도 source의 acquire/read 경로를 사용한다. 따라서 prefetch 실행 횟수와 실제 GET 횟수가 항상 같지는 않다.

**미확인:** 특정 emitted plan에서 source 갱신/재생성·cache 소실·경계 위치와 일반 factor의 동시 활성화 여부. 그러므로 “모든 함수 호출마다 GET이 발생한다”도, “모든 반복 호출을 GET 한 번으로 줄여도 된다”도 현재 증거로 주장할 수 없다.

후속 통일의 방향은 새 함수 전용 reuse 식을 만들기보다, retained 여부가 증명된 function-boundary demand를 기존 `source/version/context/availability` collector에 연결하는 것이다. 증명되지 않은 경계를 공통 이름만 보고 합치지 않는다. 논문 마지막 문장은 **ownership 원칙**으로 유지하되 universal dedup 구현 완료 주장으로 확대하지 않는다.

## 7. 보정은 무엇을 바꾸며 무엇을 증명하지 않는가

### 7.1 매 실행 profiling을 하지 않는다

보정은 환경별 one-way latency와 wire/codec rate를 준비하는 작업이다. 이를 optimization 시작 전에 읽어 고정하고, 계획 후보마다 실측하거나 프로파일을 갱신하지 않는다. 별도 fixed-overhead profile은 만들지 않는다. candidate에 따라 바뀌는 것은 `D_i`, work quantities, active stages, occurrence counts다.

새 shared-NIC wire model을 먼저 정한 뒤 기존 raw data에서 다음 잔차를 본다.

```text
codec residual = measured transfer time
               - sum(actual directional-stage latencies)
               - sum(modeled wire times)
```

여러 payload/worker-count case에 대해 payload당 비음수 계수를 적합하고 held-out case로 확인한다. **자유로운 fixed intercept는 적합하지 않는다.** residual이 일관되게 음수거나 topology마다 달라지면 wire/codec 중첩, 환경 또는 모델 식별 문제이지 “음수 codec 비용”을 허용할 이유가 아니다. residual에 남는 상수 오버헤드는 단순 모델의 오차로 공개한다. 실측에서 codec만 완전히 분리했다고 주장하거나, 기존 control 상수를 coefficient로 자동 환산하지 않는다.

### 7.2 이전 17.64%가 뜻하는 것

이전 R56의 LAN GET 후보 적합식은 다음이었다.

```text
t[sec] = 0.0038293
       + 0.00348185 × largest[MiB]
       + 0.00914895 × total[MiB]
```

- 4/16MiB training → 64MiB held-out 25개에서 중앙 상대오차가 기존 R55 식 + campaign 14.7 설정의 **400.99% → 17.64%**였다.
- 이는 **위 affine candidate의 transfer-time 추정 오차**다.
- 위 계수를 pure codec/physical NIC throughput으로 각각 식별한 것이 아니다.
- **§4.4의 새 shared-NIC max 식을 검증한 결과가 아니다.** 그 식은 아직 적용/재적합하지 않았다.
- production 상수에 적용되지 않았으며 전체 workload runtime 개선율도 아니다.
- 특히 이 과거 후보는 0.0038293초 intercept를 포함한다. **최신 세 항 모델에 이 intercept를 넣지 않는다.** free-intercept fit의 17.64%를 no-extra-fixed 모델의 정확도라고 재사용할 수 없다.

현재 채택한 표본은 LAN W1/W3, WAN-Light W1의 총 340개다. 사용자 요청으로 추가 validation을 중단했다. WAN-Light W3 미완료 및 WAN-Mid/Heavy 미실행을 완료처럼 다루지 않는다.

**후속 검증도 먼저 기존 340개를 재사용한다.** 새로운 전체 campaign, 각 workload 재실행, 매 실행 calibration을 자동으로 시작하지 않는다. 보정 범위 밖 topology/환경에서의 정확도는 미확인으로 남긴다.

## 8. 구체적인 수정 순서와 완료 기준

아래는 **후속 구현 계획**이다. 이번 문서 작성에서 이미 완료한 코드 변경 목록이 아니다.

### 단계 A — 논문 표현과 환경 단위부터 고정

1. 논문의 `one-way latency`를 유지한다. 공통 network 모듈도 같은 stage 단위로 바꾸며 별도 fixed/control/RTT 가산항을 제거한다.
2. `site-calibrated` → homogeneous-site/kernel-class configured rates라는 현 구현 가정 명시.
3. codec은 payload 비례 유효 endpoint 처리량으로 정의한다. 현재 설정의 RPC/Netty 포함 한계를 명시하되 독립 control/intercept는 추가하지 않는다.
4. realized alternative와 local/global search의 보장 범위를 명시.
5. DRIVER `network_cost_environment` 및 신규 calibration prediction에서 Mbit/s→MiB/s 변환 통일. 원본 측정/과거 예측 artifact는 보존.

**완료 기준:** 논문과 구현의 network 기본항이 one-way latency + bandwidth 시간 + codec 시간으로 같고, 미구현 site profiler·DP-local 전역 보장을 주장하지 않으며 설정 단위가 물리 shaping과 일치.

### 단계 B — 공통 network primitive 하나만 확장

주 수정 파일: FCM. 수량/설정 전달이 필요한 범위만 PCS와 EPC 및 `FederatedPlannerConfiguration` 경계를 검토한다.

1. 기존 response summary/actual layout에서 `Dmax`, `DΣ`, 필요한 resource 정보를 사용한다.
2. 공통 wire 계산을 `max(worker-leg, coordinator-aggregate)`로 바꾼다. 이는 bandwidth 항 내부이며 별도 fixed 항을 만드는 일이 아니다.
3. GET, PUT, explicit transfer, intrinsic preparation/result가 하나의 `oneWayLatency + wireTime + codecTime` primitive를 사용한다.
4. 현재 fixed-stage/coordination 호출을 실제 방향별 stage 조합으로 대체한다. 같은 batch·같은 방향의 payload는 합쳐 한 stage로 가격화한다. 요청·완료 응답은 필요한 경우 포함하고, 같은 물리 stage를 op와 movement가 동시에 과금하지 않는다.
5. copy activation/source lifetime/oracle는 변경하지 않는다.
6. 새 고정 profile/version 또는 geometry가 cost cache key/fingerprint에 필요하면 기존 projection 구조에 포함한다. worker 수만 같다고 서로 다른 resource 조건을 같은 cost로 재사용하지 않는다.
7. `SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS`를 비용 모델에서 제거하고 구 설정은 명시적 migration 진단을 낸다. `NET_LATENCY`의 기존 RTT 의미는 입력 경계에서 처리하며 내부에는 one-way 값만 남긴다. 실제 RPC dispatch나 runtime protocol을 없애는 변경은 아니다.

**하지 않을 것:** W1/W3, L2SVM/STEP-LM, 4MiB, codec thread 8 같은 workload/크기별 특별 규칙. 새 decision variable이나 거대한 Cartesian factor table도 추가하지 않는다. 변경 대상은 **stage 수량/조합과 공통 가격 함수**이며 별도 latency 할인 예외가 아니다.

**완료 기준:** 같은 one-way stage quantity·profile이면 어떤 call path에서도 같은 가격. 실제 stage 구성과 activation 횟수만 결과 차이를 만든다. 제거한 control 상수는 어느 다른 비용 항에도 자동 이식되지 않는다.

### 단계 C — 코드의 의미를 바꾸지 않는 경계 통일

- coordinator preparation/postprocessing의 실행 단계 범위를 논문과 맞춘다.
- 함수 경계가 retained copy를 사용하는지 emission/runtime lifetime으로 분류한다. 증명된 retained 경계만 기존 activation collector로 연결한다.
- 항상 0인 legacy penalty 호출과 잘못된 오래된 주석은 필요 범위에서 정리하되 새 heuristic으로 대체하지 않는다.
- unknown dimension/geometry는 exact facts, size bounds, 명시된 fallback을 구분한다. FCM의 256MiB는 최후의 설정 fallback이지 실제 차원을 알아낸 값이 아니다. 비용을 맞추려고 upper bound를 exact shape로 승격하지 않는다.

**완료 기준:** source/version/lifetime이나 실행 소유권을 숨기는 특례를 줄이되, runtime이 다른 실제 작업까지 억지로 동일하게 만들지 않음.

### 단계 D — 기존 데이터 보정과 제한된 회귀

- 새 wire 식, 방향별 latency 및 올바른 단위를 고정한 뒤 **기존 raw data**로 no-extra-intercept codec profile을 적합.
- 기존과 동일한 train/held-out 구분을 유지하고, 추정오차 비교에 새 식과 새 profile을 명시.
- 계수는 환경별 profile에 넣고 optimization 동안 고정. 불충분한 환경은 calibrated라고 표시하지 않음.
- 논문용 workload runtime 개선 검증과 transfer estimator 검증을 분리.

아래 회귀만 우선 설계하고, 실제 구현 시 실행한다. **이번 문서 작업에서는 실행하지 않았다.**

| 회귀 | 확인할 계약 |
|---|---|
| 동일 총량 W1/W3/W7 | worker leg가 빨라져도 coordinator bottleneck 이하로 wire 시간이 떨어지지 않음 |
| worker당 동일 바이트 | aggregate 총량 증가를 반영 |
| skewed ranges / FULL / BROADCAST / PART | 실제 response 총량과 largest 구분, FULL 단일 map, replicas/partials 누락 없음 |
| C2W broadcast/slice | worker multiplier 정확히 한 번, coordinator egress shaping 공유 |
| in-band vs standalone | 동일 payload 산식, 실제 batch·방향별 stage 개수만 반영. 추가 fixed/control 없음 |
| 방향별 latency / control 제거 | C2W 3ms + W2C 7ms는 10ms. worker 수·helper 수로 중복하지 않음. 구 control 설정 값을 바꿔도 목표 비용은 불변 |
| 0-byte stage / absent stage | 실제 요청·응답은 latency 유지, 통신 없는 경우는 0. payload=0이라는 이유만으로 실제 stage를 지우지 않음 |
| explicit WDivMM / fused / native result / reusable GET | 같은 quantity/profile에 공통 가격, GET/PUT owner 중복 없음 |
| loop-invariant / loop-recreated / updated alias / cross-function | creation 횟수·version·availability 보존 |
| resolved / unresolved / duplicate / contained selected | 기존 OR/capped-union과 canonical factor 값 동일 |
| 설정 단위 | 1000Mbit/s가 119.2093MiB/s로 변환; 과거 artifact는 수정하지 않음 |

기존 관련 테스트를 우선 재사용한다: `FederatedNetworkCostUnificationTest`, `GetAggregateProcessingCostTest`, `FederatedCostModelFixedInstructionStageTest`, `ExactAliasGetCoalescingTest`, `ExactExplicitWdivmmTransferTest`, `ExactFusedFactorReuseCostTest`, `ExactActivationMaterializationCostTest`, `ExactCompiledMaterializationScopeTest`, `ExactExecutionOwnershipCostTest`.

**중단 기준:** 필요한 focused 회귀와 기존 데이터 held-out 비교가 충분하면 검증을 끝낸다. 사용자 중단 요청을 무시하고 모든 네트워크×worker×workload를 새로 돌리지 않는다. main의 `target`은 보존된 R37 artifact symlink이므로 후속 빌드도 별도 격리 작업공간에서 해야 한다.

## 9. 논문에 넣을 수정 문안

### 9.1 지금 바로 정정할 수 있는 설명

아래 문장은 현재 구현의 범위를 명확히 한다. full-paper의 notation 정의와 중복되지 않도록 삽입 위치를 조정한다.

```latex
An alternative is priced after its selected input supplies and physical
execution layout have been instantiated. Logical statistics, occurrence
frequencies, and configured throughput parameters are fixed during search;
selected plans determine physical work quantities and active stages.

Our implementation uses homogeneous-site memory throughput and
kernel-class compute throughput. Worker-specific quantities nevertheless
reflect the selected worker ranges. These configured rates need not be
independently calibrated for every site.

Resolved sharing is used only when the required source/version,
availability, event-relation, and frequency facts are established.
Otherwise, the bounded estimate is used without assuming additional reuse.
```

### 9.2 네트워크 구현 수정과 함께 적용할 식

**아래는 목표 식이다. 현재 구현 완료를 주장하는 본문으로 먼저 출판하면 안 된다.** 기존 `eq:network-primitive` label은 유지할 수 있다.

```latex
For a one-way communication stage $x$, let $t_{\mathrm{lat}}(x)$ be its
directional network latency, $\mathcal L(x)$ its modeled network
resources, $D_\ell(x)$ the bytes crossing resource $\ell$, and
$\rho_\ell$ its fixed effective capacity. A bottleneck-throughput
approximation gives
\begin{equation}
  c_{\mathrm{net}}(x)=t_{\mathrm{lat}}(x)
  +\max_{\ell\in\mathcal L(x)}\frac{D_\ell(x)}{\rho_\ell}
  +\frac{D^{\mathrm{codec}}(x)}{\rho_{\mathrm{codec}}(r(x))}.
  \label{eq:network-primitive}
\end{equation}
The empty maximum is zero. No separate fixed, control, or round-trip
charge is added. A request--response exchange contains a request stage
and a response stage; payloads carried in the same batch and direction
belong to the same stage. A stage with no modeled matrix payload still
incurs its one-way latency, whereas an absent stage contributes zero.
Each physical stage has exactly one cost owner. Codec throughput denotes
effective aggregate payload processing, including serialization and
deserialization, not a per-thread rate or an extra constant overhead.
Network latencies and throughput parameters remain fixed during search.

For a coordinator--worker star, the wire term is the maximum of the
slowest worker-leg time and the aggregate coordinator-link time.
Resource capacities respect both physical links and shaping at the
resource where that shaping is applied.
```

논문에서 bytes/ms를 쓴다면 모든 rate를 그 단위로 맞춘다. MiB/s와 방향별 latency 단위에서 milliseconds로의 변환을 명시한다. legacy RTT 설정은 core 식에 넣지 않고 §4.2의 설정 경계에서 변환한다. RPC의 별도 byte-independent control 비용을 생략한다는 모델링 한계도 숨기지 않는다.

### 9.3 직렬 phase를 포함하는 실행식 — 현재 코드 의미를 명시

coordinator preparation만 예외항으로 덧붙이기보다 다음처럼 같은 primitive의 phase 합으로 쓸 수 있다. `S(a)`는 모델이 순차적으로 가격화하는 실행 phase들이며, `K_p(a)`는 그 phase에서 병렬로 작업하는 site 집합이다. 실제 source code에 새로운 phase planner를 만들자는 제안이 아니라 **현재 분리 계산의 수학적 표현**이다.

여기서 `c_net`은 현재 또는 수정 후의 공통 네트워크 primitive를 뜻한다. 이 실행식 설명이 §9.2의 네트워크 구현 완료를 전제하지는 않는다.

```latex
For execution phase $p$ at site $k$, we use
\begin{equation}
  c_{\mathrm{exec}}(a,p,k)=\max\!\left\{
    \frac{\operatorname{Ops}(a,p,k)}{\rho_{\mathrm{comp}}(k,\tau(a,p,k))},
    \frac{D_{\mathrm{in}}(a,p,k)}{\rho_{\mathrm{mem}}(k)}\right\}
    +\frac{D_{\mathrm{out}}(a,p,k)}{\rho_{\mathrm{mem}}(k)}.
  \label{eq:kernel-cost}
\end{equation}
Here $\tau$ denotes the modeled kernel class. Let $\mathcal S(a)$ contain
the sequentially priced execution phases and $\mathcal K_p(a)$ their
participating sites. Then
\begin{equation}
  c_{\mathrm{op}}(a)=
    \sum_{p\in\mathcal S(a)}\max_{k\in\mathcal K_p(a)}c_{\mathrm{exec}}(a,p,k)
    +\sum_{x\in\mathcal I(a)}c_{\mathrm{net}}(x).
  \label{eq:operator-unit-cost}
\end{equation}
Coordinator phases include any required preparation and post-processing;
worker phases use the critical path of participating workers. Empty sums
and maxima are zero. A local alternative has only coordinator execution
phases and no intrinsic remote stages. For a single worker phase and a
single coordinator phase, this reduces to the usual worker-critical-path
plus intrinsic-network plus coordinator expression.
```

원래 operator 식을 간결한 주 식으로 유지하고 싶다면, 그것이 **단일 worker phase 및 coordinator 실행비의 phase 합을 축약한 표기**임을 명시해야 한다. `max` 안에서 서로 다른 직렬 phase의 work를 먼저 합치는 표현과 동일하다고 쓰면 안 된다. 여러 worker phase 사이에 barrier가 있으면 `max_k Σ_p`가 아니라 `Σ_p max_k`가 필요하다.

### 9.4 유지할 식

`eq:cost-decomposition`, `eq:shared-movement`, `eq:unresolved-movement`는 구조를 유지한다. `eq:kernel-cost`의 roofline 산식 자체는 유지하되 phase 범위를 명시한다. 큰 방향은 새 heuristic 항을 덧붙이는 것이 아니라 **같은 primitive에 올바른 수량·단계·횟수를 공급하는 것**이다.

## 10. 검증 상태와 재현 근거

### 이번 문서 작업

- 현재 source 경로/호출/산식의 읽기 전용 대조.
- source 인용 49개 line range 유효성, 참조 파일 존재, Markdown fence, tracked/untracked 문서 공백 검사 **PASS**. line range 검사는 참조 위치의 유효성 검사이며, 주장 자체는 위 소스 대조로 검토했다.
- 별도 explore 두 lane의 담당 경로 재대조에서 중대한 사실 오류 없음. 함수 경계의 검증 범위와 목표 network 식/현행 execution 식의 상태 구분을 보강했다.
- `src/scripts/tests`의 8,647개 파일 SHA manifest가 작성 전후 동일: `8c8f34d01c23e2f78ff867a0a43e3110ffa2f7990fd372a8541dc4da2977ccc6`. HEAD와 R37 `target` symlink도 동일하다.
- 구현 테스트·빌드·runtime experiment는 새로 실행하지 않았다.

### 이전 R56 근거 — 이번에 새로 실행한 결과가 아님

- 비용/인접 Java 회귀 **472 PASS**, 기존 ignored 2 유지.
- Python **18 PASS**.
- 340개 채택된 Docker transfer sample. full workload runtime 재실험은 아님.
- 보고서: [R56 구현·실측 기록](COST_EXPLICIT_ALIAS_CALIBRATION_2026-10-05_KO.md).
- 실측: `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-alias-calibration-r56-20261005/transport-r56-20261005T062918Z/`의 `fit-heldout.json`, `validation-stopped.json`, 각 cell raw/preflight/netem/cleanup.
- 구현 검증: 같은 R56 root의 `evidence/final-build-proof.json`, `final-sync-proof.json`, `verification.json`.

### 기준 source SHA-256

| 파일 약어 | SHA-256 |
|---|---|
| FCM | `3644733308dffa83a086ba0a55bee7617a871fb43ddb063777ea74bb59bc7b43` |
| EPC | `3903de6ad9a8fc0d1bc63b54f1e224b62c2079364c5b421551a8278a2f3fc5d4` |
| EMA | `2255a384edcf14062f8ea53a28f2ea171f0a3101421e2fd1a0b4cb84908899cd` |
| PCS | `7e347137c28922f9ce6f0f9f89fa3307aad6b784e422e5f6e1a9335abfb5b067` |
| FREQ | `c1ccff526f588e442486e4e93382accfba8279aaa2449362f471a20341b4aa0d` |

### 남는 위험

1. wire/processing overlap 때문에 새 식도 runtime의 정확한 등식은 아니다. 기존 sample 밖 환경의 정확도는 아직 모른다.
2. unknown/sparse/skewed geometry, context별 shape 차이, 함수 경계 copy 수명은 별도의 모델링 한계다.
3. 가격 수정으로 다른 합법 계획이 선택될 수 있다. estimator 오차 개선만으로 모든 workload runtime 개선을 보장하지 않는다.
4. 공통 분석 재계산/DP 중간 결합 문제와 network 산식 수정은 구별해야 한다. 이 문서는 **compile+planning 20초 달성 증명**이 아니다.

**최종 권고:** 목적함수·재사용 식은 보존하고, network를 **one-way latency + bandwidth 시간 + codec 시간**으로 통일한다. 별도 fixed/control 항은 없애고 실제 방향별 stage만 한 번씩 가격화한다. 처리량은 고정된 환경 profile로 사용하며, 실제 구현보다 강한 보정·최적성 주장은 논문에서 제거한다.
