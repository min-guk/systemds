# Global compact on/off와 Regional·Anytime 비교

작성일: 2026-09-09. 상태: **구현·검증 완료**. so007 native JVM, L2SVM planning-only 8회, 조건별 1회.

## 1. 질문에 대한 답

**일반 Regional은 Global의 전체-model compact를 사용하지 않는다.** 다만 앞선 비교의 묶음 Anytime은 정상 Regional로 계획을 만든 뒤, **전체 모델을 compact하여 최초 LB와 증분 LB 강화를 수행**한다. Regional과 Anytime의 인증 계산을 구분해야 한다.

사용자 요청에 따라 Global의 compact를 끈 실행을 추가했다. 이번 혼합 privacy에서 Global은 compact on 0.921824초, off 1.226291초였다. 묶음 Anytime은 1.245995초로 compact 없는 Global과 거의 같은 수준이었다. PUBLIC에서는 compact off Global도 1.000791초로 묶음 Anytime 1.428013초보다 빨랐다.

따라서 **compact를 제거하면 비교 격차가 줄어들지만, 이번 두 조건에서 Anytime이 더 빠르다는 결과는 얻지 못했다.** 혼합 조건의 약 0.020초 차이로 우위를 판단할 수 없다. 단일 fresh JVM 관측이므로 반복 실험의 평균이나 통계적 결론이 아니다.

## 2. 무엇을 켜고 껐는가

현재 Global 전처리는 정확성을 보존하는 domain reduction/동등 상태 quotient를 만들고, 그 결과 **값이 하나만 남은 변수를 치환해 제거**한 뒤 exact elimination을 수행한다. 이번 compact off는 마지막 singleton 치환만 끈 것이다. domain reduction과 quotient는 유지했다. 모든 전처리를 제거한 raw categorical exact 실험은 아니다.

| 방법 | Global과 같은 whole-model reduction | singleton compact | 반환 품질 정보 |
| --- | --- | --- | --- |
| Global compact on | 사용 | 사용 | 전체 exact optimum |
| Global compact off | 사용 | 사용하지 않음 | 전체 exact optimum |
| 일반 Regional | 별도 local block 처리 | Global compact를 호출하지 않음 | feasible plan 비용. 자체 global gap 인증 없음 |
| 묶음 Anytime | LB 계산에 사용 | LB 계산에 사용 | feasible plan과 global 인증 구간 |

일반 Regional은 region 바깥 assignment를 고정하고, 그 조건을 반영한 local factor를 직접 compile한다. 이는 Global의 whole-model reduction/compact와 다른 경로다. 그러므로 이번 off를 ‘Regional과 완전히 같은 전처리’라고 표현하지 않는다. 또한 이 Global 옵션이 Anytime의 compactRoot까지 끄는 것은 아니다.

## 3. 전체 planning 결과

| 방법 | 혼합 privacy 전체 planner 초 | PUBLIC 전체 planner 초 |
| --- | --- | --- |
| Global compact on | 0.921824 | 0.871686 |
| Global compact off | 1.226291 | 1.000791 |
| 일반 Regional | 0.663038 | 1.321210 |
| 묶음 Anytime, 3% 목표 | 1.245995 | 1.428013 |

혼합 privacy는 X=PRIVATE_AGGREGATE, Y=PUBLIC이고 PUBLIC은 둘 다 PUBLIC이다. 모든 실행의 canonical cost는 `846.0188882981099 modeled ms`로 같았다. 비용 단위의 ms와 위 planner wall-clock 초는 다른 지표다.

두 Global 모두 동일한 optimum을 반환했다. 일반 Regional도 사후 oracle 비교에서 실제 modeled regret가 0이었다. 일반 Regional 자체가 이를 인증한 것은 아니다.

묶음 Anytime의 최종 상대 인증 gap은 혼합 **0.693330%**, PUBLIC **2.954455%**로 3%·5% 기준을 모두 만족했다. 새 JVM은 3% 목표로 각각 한 번 실행했고 5% 전용 JVM을 추가하지 않았다. 전체 exact endpoint로 닫은 것도 아니며 `wholeClosureCompleted=0`이었다.

Global off/on의 관측 시간비는 혼합 약 1.330배, PUBLIC 약 1.148배다. Anytime/Global off는 혼합 약 1.016배, PUBLIC 약 1.427배다. 과거 pilot의 Global 0.724초를 새 측정에 섞지 않고, 이번 같은 JAR의 새 Global을 비교 기준으로 사용했다.

## 4. compact가 줄인 작업

| Privacy | Global compact | encoded 변수 | 제거 대상 변수 | 준비 초 | assignments | materialized cells | induced width |
| --- | --- | --- | --- | --- | --- | --- | --- |
| mixed | on | 407 | 230 | 0.277037 | 47369 | 25578 | 6 |
| mixed | off | 407 | 407 | 0.489509 | 54678 | 26615 | 9 |
| public | on | 422 | 255 | 0.276269 | 92455 | 38423 | 6 |
| public | off | 422 | 422 | 0.425693 | 97272 | 40000 | 10 |

혼합 모델은 전체 encoded 변수 407개 중 177개가 singleton 치환으로 제거돼 230개를 elimination한다. PUBLIC은 422개에서 255개로 줄었다. off에서는 각각 407개와 422개가 실제 제거 순서에 남는 것을 trace로 확인했다.

on/off에서 objective를 바꾸지 않고, 제거 순서 준비가 다룰 그래프를 줄인 것이다. 준비 시간은 혼합 0.490→0.277초, PUBLIC 0.426→0.276초로 감소했다. 이 준비 시간은 domain reduction, 선택된 compact 단계, exact compile을 포함하며 전체 planner의 내부 시간이다. compact 자체 시간만 분리한 수치는 아니다.

assignment 수와 table cells도 달라지지만, singleton 제거가 변경한 그래프에서 제거 순서도 다시 선택되므로 모든 work 차이를 단순 변수 개수 비율로 설명할 수는 없다.

## 5. Regional의 경로와 실제 시간

| Privacy | 실행 | Regional 전체 초 | Regional DP 준비 초 | Regional DP solve 초 | LB compact 초 | 최초 LB 초 |
| --- | --- | --- | --- | --- | --- | --- |
| mixed | 일반 Regional | 0.240092 | 0.047855 | 0.157512 | — | — |
| mixed | 묶음 Anytime, 3% 목표 | 0.270335 | 0.052326 | 0.181643 | 0.053544 | 0.073635 |
| public | 일반 Regional | 0.620050 | 0.091184 | 0.463994 | — | — |
| public | 묶음 Anytime, 3% 목표 | 0.397808 | 0.056427 | 0.303187 | 0.055645 | 0.078916 |

두 실행 모두 정상 Regional의 local interaction/materialization 경로를 사용했다. 일반 Regional 실행에는 LB 계산이 없다. Anytime 행에는 자신의 Regional seed와 그 뒤 LB용 compact·최초 bound 시간이 각각 들어간다. 추가 강화 시간은 원본 checkpoint/counters에 보존했다.

같은 Regional 코드여도 서로 다른 fresh JVM에서 위 시간이 변한다. 특히 PUBLIC의 일반 Regional 0.620초와 Anytime seed 0.398초는 단일 실행의 차이다. 전체 planner 행을 서로 빼서 ‘인증에 추가로 든 시간’으로 보고하지 않는다. 계측된 같은 실행 내부의 phase 시간을 사용해야 한다.

## 6. 실험 조건과 한계

- L2SVM/P2P2D, X 50,000×2,100, Y 50,000×1, 모델상의 worker 수 1.
- LAN의 modeled bandwidth C2W/W2C 5,000 Mbit/s(625 MB/s), latency 1 ms만 채택했다. 네트워크 통신 성능을 실제 측정한 실험이 아니다.
- 새 JAR, 같은 입력 metadata/DML/config, 동일 cost environment와 seed를 사용했다. JVM Xmx/Xms 8 GiB, Xmn 800 MiB, ActiveProcessorCount=8.
- Global on/off의 production cell 한도 10,000,000/50,000,000을 동일하게 유지했다. Anytime은 기존 width=2, batch=8, reuse=false, fullSeed=true, 후보 probe=1, component/region work 1,000,000 및 cell 1,000,000/5,000,000이다.
- Anytime은 20초 after-initialization soft scheduling budget, 모든 방법의 외부 process watchdog은 60초다. 이번에 자원 실패·timeout은 발생하지 않았다.
- Mixed는 on→off, PUBLIC은 off→on 순서로 측정했다. 조건당 1회이며 JVM/JIT/호스트 변동을 통계적으로 제거하지 않았다.
- 총 8회만 실행했고 모든 시도를 보존했다. Docker, workload, worker 실행은 하지 않았다. 실제 데이터 값 대신 고정 metadata를 사용했다.

## 7. 구현과 검증

Global-only 옵션 `-Dsysds.fedplanner.exact.compact=false`를 추가했다. 기본값은 true이며 기존 compact+exact 동작을 유지한다. 이 이름의 적용 범위는 **ExactPhysicalOptimizer의 Global 진입점**이고 모든 exact/Regional/Anytime 경로에 적용되는 공통 스위치가 아니다.

- [ExactPhysicalOptimizer.java](/home/mchoi/so007-global-compact-ablation-evidence-20260909/source-patch/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalOptimizer.java): prepareCompacted/prepare 선택, 현재 encoded/compiled 변수 수와 준비 시간 trace. 원래 canonical cost, forced-state audit, legality 검증을 유지한다.
- [RegionalSearchPhysicalIntegrationTest.java](/home/mchoi/so007-global-compact-ablation-evidence-20260909/source-patch/src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchPhysicalIntegrationTest.java): default-on, 명시적 on/off, 동일 canonical optimum, 원래 hard feasibility, 실제 compiled 변수 수, 잘못된 설정의 명시적 오류를 검증한다.
- Maven clean package, targeted 3개 class **43/43 테스트 통과**, failure/error/skip 0. `git diff --check`, Python syntax 확인 통과. 전체 Java suite/style/license/RAT 검사는 수행하지 않았다.
- 8/8 JVM의 compile-only receipt, execution_seconds=0, runtime_executed=false, output 부재를 확인했다.
- privacy별 네 방법의 model/cost fingerprint 일치, Global on/off의 canonical objective bits 일치, 모든 Anytime checkpoint의 `L≤C*≤U`와 L/U 단조성, 보수적 gap 및 목표 판정을 확인했다.
- Global on/off가 동률 최적해에서 서로 다른 assignment를 선택할 수 있으므로 두 모드 사이 assignment 동일성은 요구하지 않았다.
- 독립 architect는 singleton compact ablation의 구현 경계를 확인했고, Batch가 여전히 compactRoot를 사용한다는 비교 범위를 명확히 하도록 검토했다.

## 8. 실행 전체 시간과 메모리

| Privacy | 방법 | JVM wall 초 | peak RSS MiB |
| --- | --- | --- | --- |
| mixed | Global compact on | 4.003607 | 754.16 |
| mixed | 일반 Regional | 3.552059 | 853.27 |
| mixed | 묶음 Anytime, 3% 목표 | 4.364890 | 1130.10 |
| mixed | Global compact off | 4.848022 | 903.30 |
| public | Global compact off | 4.170807 | 928.87 |
| public | 일반 Regional | 4.955974 | 1049.09 |
| public | 묶음 Anytime, 3% 목표 | 4.994971 | 1177.72 |
| public | Global compact on | 3.810620 | 807.35 |

위 JVM wall은 시작과 class loading 및 전체 compile을 포함한다. peak RSS는 프로세스 전체의 최대 resident memory이며 planner나 compact의 live heap만을 나타내지 않는다. 주 비교는 앞선 전체 planner 시간이다.

## 9. 결과를 어떻게 사용할 것인가

논문·보고서에는 **Global compact on/off를 별도 ablation 행으로 유지**하는 것이 맞다. on은 현재 최적화한 Global의 성능, off는 singleton compaction이 얼마나 기여했는지 보여 준다. 일반 Regional은 빠른 feasible 계획 생성의 비용과 사후 actual regret를 보고하고, 인증이 필요한 Anytime은 LB 준비·강화까지 포함해 비교한다.

이번 혼합 조건에서는 일반 Regional만 필요한 경우 0.663초에 optimum과 같은 비용의 계획을 얻었다. 3% 인증까지 요구하면 1.246초가 필요했고 compact+exact Global 0.922초보다 길었다. compact를 끈 Global 1.226초와는 거의 같았다. PUBLIC에서도 Global off가 더 빨랐다. 이 두 cell에서 확인한 사실이며 다른 workload에 일반화하지 않는다.

## 10. 절대 경로와 보존

- 보고서: `/home/mchoi/so007-anytime-incremental-20260908/docs/GLOBAL_COMPACT_ABLATION_2026-09-09_KO.md`.
- so007 검증 소스/빌드: `/home/mchoi/so007-global-compact-ablation-20260909`.
- evidence: `/home/mchoi/so007-global-compact-ablation-evidence-20260909`.
- [구조화 결과](/home/mchoi/so007-global-compact-ablation-evidence-20260909/validation/ablation-results.json), [테스트 결과](/home/mchoi/so007-global-compact-ablation-evidence-20260909/validation/native-tests.json), [고정 실험 계획](/home/mchoi/so007-global-compact-ablation-evidence-20260909/validation/pilot-plan.json), [보존 검증](/home/mchoi/so007-global-compact-ablation-evidence-20260909/validation/final-postflight.json).
- 원본 `native/runs/*/trials/*/command.json`, `receipt.json`, `observation.json`, coordinator log, protocol/context와 harness snapshot을 보존했다.
- 새 JAR SHA-256: `6a66597af77c9f0c9defbaecc7a4e8b380064eba0d74ce7f2bc0c0c1652c1a45`.
- source manifest SHA-256: `18ccd14c467295b6f0e5d7027555930fb42b8448cc83ff915a6485a014e1c298`.
- 기존 batch/reuse 빌드와 runtime은 별도로 보존했다. 새 Java delta는 2개 파일이고 미커밋 상태다. 이전 9회 pilot의 결과를 수정하거나 새 성공 행으로 대체하지 않았다.

이번 요청의 검증은 8회에서 완료했다. GLM이나 다른 네트워크의 실험은 재개하지 않았다.
