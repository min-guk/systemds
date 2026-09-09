# Regional 준비 재사용 및 5% 초과 시 즉시 exact: 구현·검증 보고서

작성일: 2026-09-09. 서버: `dams-so007` / so007. 상태: 구현 및 소규모 planning-only 검증 완료. 추가 실험은 실행하지 않고 3회 측정에서 종료했다.

두 가지 최적화를 구현했다. 첫째, 준비 비용을 단계별로 측정하고 불변 비용표의 중복 생성·복사를 줄였다. 둘째, 분해된 encoded factor와 전역 전처리 결과를 Regional과 첫 LB 계산이 공유하도록 했다. 첫 인증 gap이 5% 이하면 계획을 반환하고, 초과하면 추가 LB 강화 없이 남은 coupling 전체를 exact로 닫는다.

이번 관측에서 StepLM은 크게 빨라졌고 GLM은 소폭 빨라졌다. L2SVM은 새 공유 경로가 느려졌다. 요청한 정책과 인증의 정확성은 검증됐지만, 모든 workload에서 Global보다 빠르다는 결과는 얻지 못했다.

## 1. 핵심 결과

다음은 각 방법 3회 중앙값이다. 기존 경로도 **이번 새 JAR의 공통 solver 개선**을 사용한다. 따라서 기존/공유 비교는 분해 표현·전처리 공유의 추가 효과를 비교하며, 예전 JAR와 새 JAR의 순수한 복사 제거 효과를 분리한 실험은 아니다.

| Workload | 기존 Regional + 첫 LB + 필요 시 exact | 공유 Regional + 첫 LB + 필요 시 exact | 독립 Global exact | 공유 경로 변화 |
| --- | ---: | ---: | ---: | ---: |
| L2SVM | 0.816279초 | 1.060851초 | 0.811282초 | 29.96% 증가 |
| StepLM | 3.652537초 | 1.757613초 | 1.454641초 | 51.88% 감소 |
| GLM | 5.900967초 | 5.568420초 | 9.730161초 | 5.64% 감소 |

시간은 `DMLTranslator`의 공통 Compile Phase FedPlanner 시작부터 canonical 모델 계획 또는 인증서가 준비된 시점까지다. Global은 `GLOBAL_EXACT_READY`, 두 Regional 방법은 최종 `STOP_*` checkpoint를 쓴다. 둘 다 selection materialization 전이며 파싱을 포함한 JVM 전체 wall time과 구분한다. Regional→첫 LB→최종 반환은 같은 JVM 안의 누적 관측이다.

| Workload | 첫 상대 인증 gap | 첫 Regional 실제 modeled regret — Global로 사후 확인 | 5% 정책 동작 |
| --- | ---: | ---: | --- |
| L2SVM | 76.013992% | 0.000000% | 3/3 즉시 exact |
| StepLM | 380.295922% | 0.100272% | 3/3 즉시 exact |
| GLM | 1.694623% | 0.293521% | 3/3 첫 LB 후 반환, exact 0회 |

**27/27 정상 완료, 9/9 세 방법 비교 묶음에서 모델 동일성과 oracle 포함을 검증했다.** 기존/공유 Regional의 초기 U와 첫 L은 모든 9쌍에서 동일했다. L2SVM·StepLM의 12개 Regional 실행은 exact 결과가 Global과 canonical raw bits까지 같았다. GLM의 6개 Regional 실행은 실제 modeled regret 약 0.294%의 동일한 계획을 1.695% 인증으로 반환했다.

## 2. 무엇을 변경했는가

### 2.1 준비 비용 세분화와 표 복사 제거

`ExactPhysicalReducedSolver.PreparationStatistics`에 다음 시간을 추가했다. 성공한 준비 결과에서 `Prepared`와 `CompactModel`을 통해 읽고 `Exact-PreparationPhases` trace로 기록한다.

| 필드 | 측정 내용 |
| --- | --- |
| freezeNanos | 입력 구조·예산 검증 및 lazy 비용표 materialization |
| supportNanos | unary/binary factor에서 유한한 지원이 없는 상태 제거 |
| quotientNanos | 모든 관련 factor에서 비용 관측이 같은 상태 묶기 |
| rebuildNanos | 축소된 domain/scope에 맞는 factor 표 구성 |
| compileNanos | exact elimination 순서와 구조 준비 |
| totalNanos | 위 준비 전체와 기타 준비 오버헤드 |

공개 `Factor.dense`는 호출자 배열을 계속 방어적으로 복사한다. 내부에서 소유권을 넘긴 불변 배열은 `denseOwned`로 전달한다. 검증한 dense 입력을 solve할 때 다시 복사하지 않고, `FrozenInputs`도 불변 표를 공유한다. 상태 번호가 그대로인 identity reduction은 표를 다시 순회·생성하지 않는다. Lazy 표는 해당 preparation 안에서 한 번 materialize하고, 기존 Regional의 조건부 evaluator도 경계를 한 번 snapshot하여 cell마다 임시 배열을 할당하지 않는다. Lazy 입력이 다른 실행에서 달라질 수 있다는 기존 계약은 유지한다.

이 공통 core 변경은 **Global에도 동일하게 적용**했다. 비용 함수, privacy, placement legality, 실행 지원 규칙은 변경하지 않았다.

### 2.2 분해된 factor와 전처리 결과 공유

새 `SharedRegionalPreparation`은 원래의 Regional 순서·region 선택을 유지하면서 다변수 block의 solve backend를 바꾼다. 전역 encoded 모델의 support reduction/quotient를 한 번 수행하고, 그 결과와 원래 값↔축소 값 매핑을 run 안에서 보관한다.

Block의 원래 결정에서 시작해 해당 factor를 포함하고 auxiliary 연결을 따라 필요한 factor를 모두 포함한다. Region 밖 원래 결정은 incumbent 값으로 고정한다. Auxiliary를 통과할 때 그 auxiliary의 모든 incident factor를 포함하므로 activation/공유 materialization 제약을 임의로 떼지 않는다. Block과 무관한 독립 부분은 조건부 최적화의 상수이므로 지역 목적에서 제외할 수 있고, 후보 수락 시 기존 원래 hard factors 및 canonical incident cost를 다시 평가한다.

`compact=false`에서는 이 조건부 reduced slice를 직접 compile한다. 지역마다 support/quotient/rebuild를 다시 수행하지 않는다. 모든 지역 solve 결과는 root 매핑으로 원래 decision 값에 복원한다. `compact=true` 경로는 conditional compact preparation을 유지하며 이번 실험 대상이 아니다.

같은 root factor와 같은 고정 경계에는 이전 조건부 표를 재사용한다. 경계 값 또는 free scope가 바뀌면 재생성한다. Cache는 root factor마다 최근 entry 하나만 보관하며, cache의 dense cell 합계는 root 입력 표의 cell 합계를 넘지 않는 구조다. 미해결 탐색 공간을 cache eviction으로 버리는 방식은 아니다. Resource 제한 또는 지원에서 제거된 경계를 포함한 hard-repair 중간 상태에서는 기존 Regional 조건부 경로를 사용할 수 있고 이 횟수를 기록한다. 이번 공유 경로 9회에서는 fallback이 0이었다.

`RegionalSearchProblem.reducedRoot`가 같은 객체를 첫 LB에 전달한다. First LB는 지역 밖을 고정한 문제가 아니라 **전체 encoded 모델**의 relaxation이다. 공유 경로의 9회 모두 root 생성 1회, LB의 root 재사용 1회를 확인했다. 마지막 exact도 해당 global relaxation의 이미 해결된 component를 유지하면서 미복원 coupling을 닫는다.

설정 `sysds.fedplanner.regional.sharedPreparation=false`로 기존 준비 경로를 비교할 수 있다. 기본은 true이며 일반 Regional 및 legacy certificate가 사용하는 Regional seed, `remaining-exact`에 적용된다. 별도 `ANYTIME_INCREMENTAL`, `ANYTIME_TARGET`, `THRESHOLD`, `TARGET_GAP`, `REUSE` search 경로의 seed에는 이 새 공유 연결을 적용하지 않았다. 공통 low-copy solver 개선은 공통 solver를 쓰는 경로에 적용된다.

### 2.3 5% 종료 및 exact

유효한 전역 하한 L과 feasible incumbent U를 유지한다. L>0에서 보수적으로 계산한 `(U−L)/L ≤ 0.05`이면 반환한다. 그렇지 않으면 `closeRemaining`을 한 번 호출해 아직 복원하지 않은 equality를 모두 복원한다. 성공하면 encoded objective, 원래 factor objective와 canonical objective의 raw-bit 일치를 확인한 뒤 L=U로 닫는다. L=0<U에 임의의 epsilon 분모를 넣어 인증하지 않는다.

기존 remaining-exact controller의 첫 LB→threshold 확인→exact 순서를 재사용했고, 해당 알고리즘의 기본 relativeGap을 0.05로 바꿨다. 명시한 relativeGap은 기본값보다 우선하며 다른 알고리즘의 기본 tolerance는 유지했다. Work/resource/time limit으로 exact가 완료되지 못하면 정상적인 성공으로 포장하지 않고 미달성 사유와 유효한 현재 인증을 반환한다. Exact solver의 hard wall-clock interruption을 새로 구현한 것은 아니다.

## 3. 준비 비용과 남은 병목

아래는 3회 중앙값, 초 단위다. 단계별 중앙값은 각각 계산하므로 합이 전체 시간의 중앙값과 정확히 일치할 필요는 없다. 공유 경로의 Regional 준비에는 최초 root preparation도 들어간다.

| Workload | 기존 지역 준비 | 공유 지역 준비 | 기존 지역 solve | 공유 지역 solve |
| --- | ---: | ---: | ---: | ---: |
| L2SVM | 0.188696 | 0.333638 | 0.035167 | 0.040814 |
| StepLM | 2.119342 | 0.524679 | 0.034440 | 0.120867 |
| GLM | 3.389386 | 1.536493 | 0.336149 | 2.063376 |

StepLM은 기존 지역 준비의 표 materialization 중앙값이 약 1.777초였다. 공유 경로에서는 원래의 큰 표를 지역별로 만들지 않으므로 전체 지역 준비가 약 2.119초에서 0.525초로 줄었다. 지역 solve 자체는 0.034초에서 0.121초로 늘었지만 준비 절감이 더 컸다.

L2SVM은 기존 원래 변수의 지역 문제를 준비하는 비용이 이미 작았다. Encoded 표현으로 auxiliary를 포함하면 elimination 구조 준비가 커진다. Compile 중앙값은 기존 약 0.043초, 공유 약 0.239초였고, root 준비 비용도 앞단에 추가되어 전체 Regional 준비가 0.189초에서 0.334초로 증가했다. 복사와 재전처리를 제거해도 새로운 표현의 구조 준비 비용이 더 클 수 있다는 결과다.

GLM은 지역 준비가 약 3.389초에서 1.536초로 줄었지만 지역 solve는 0.336초에서 2.063초로 늘었다. 이 때문에 Regional 자체의 전체 시간 개선은 작았다. Root를 LB에 재사용한 효과까지 포함하면 인증 완료 중앙값은 약 5.6% 줄었다. 준비 최적화가 같은 비율의 전체 planning 개선으로 이어진다고 주장하지 않는다.

| Workload | 기존: LB 단계 root 전처리 | 공유: LB 단계 root lookup | 기존 MBE | 공유 MBE |
| --- | ---: | ---: | ---: | ---: |
| L2SVM | 0.024758 | 0.000272 | 0.115262 | 0.102019 |
| StepLM | 0.019653 | 0.000298 | 0.187589 | 0.230959 |
| GLM | 0.174316 | 0.000332 | 0.665222 | 0.640399 |

공유 경로에서 LB 단계 root lookup이 짧은 것은 root 계산을 Regional 앞단에서 이미 했기 때문이다. 그 비용을 없어진 것으로 계산하지 않았으며 모든 전체 시간에 포함했다. 이번 변경은 MBE 자체의 알고리즘이나 bound 품질을 개선한 것이 아니다.

| Workload | 변경 없이 사용한 root 표 참조 횟수 | 조건부 표 생성 | 같은 경계 cache hit |
| --- | ---: | ---: | ---: |
| L2SVM | 1160 | 89 | 1 |
| StepLM | 2573 | 310 | 140 |
| GLM | 5897 | 922 | 457 |

위 횟수는 세 반복에서 각각 동일했다. 표 참조 횟수는 유일한 표 개수나 byte 수가 아니므로 메모리 절감량으로 직접 환산하지 않는다.

## 4. 누적 단계와 개별 표본

| Workload | 경로 | Regional 준비 완료 | 첫 LB 인증 준비 완료 | 최종 반환 | 전체 emitted planner |
| --- | --- | ---: | ---: | ---: | ---: |
| L2SVM | 기존 Regional 경로 | 0.510056 | 0.649396 | 0.816279 | 1.006522 |
| L2SVM | 공유 Regional 경로 | 0.793825 | 0.892957 | 1.060851 | 1.229646 |
| StepLM | 기존 Regional 경로 | 3.018641 | 3.235640 | 3.652537 | 3.880977 |
| StepLM | 공유 Regional 경로 | 1.161064 | 1.400694 | 1.757613 | 1.972081 |
| GLM | 기존 Regional 경로 | 4.924202 | 5.900549 | 5.900967 | 6.960473 |
| GLM | 공유 Regional 경로 | 4.867849 | 5.567879 | 5.568420 | 6.842287 |

위 Regional 시점은 seed를 완성한 뒤 canonical 검증까지 끝난 시점이다. 순수 block DP solve만의 시간이 아니다. GLM의 첫 LB와 최종 반환 차이는 checkpoint/종료 처리이며 exact 시간이 아니다.

| Workload | 방법 | 1회 반환 초 | 2회 반환 초 | 3회 반환 초 | 표준편차 초 | peak RSS 중앙값 MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| L2SVM | 기존 Regional 경로 | 0.816279 | 1.449315 | 0.786483 | 0.374382 | 872.77 |
| L2SVM | 공유 Regional 경로 | 1.060851 | 1.218755 | 0.823994 | 0.198692 | 935.54 |
| L2SVM | Global exact | 0.811282 | 0.645438 | 1.026715 | 0.191175 | 910.23 |
| StepLM | 기존 Regional 경로 | 3.263149 | 3.883317 | 3.652537 | 0.313446 | 1287.31 |
| StepLM | 공유 Regional 경로 | 1.659513 | 1.757613 | 3.078436 | 0.792416 | 1221.41 |
| StepLM | Global exact | 1.402003 | 1.454641 | 1.636067 | 0.122795 | 1212.92 |
| GLM | 기존 Regional 경로 | 6.754213 | 5.900967 | 5.842692 | 0.510277 | 1663.88 |
| GLM | 공유 Regional 경로 | 5.451897 | 5.568420 | 6.463849 | 0.553688 | 1641.96 |
| GLM | Global exact | 9.935737 | 9.375629 | 9.730161 | 0.283336 | 1641.06 |

Peak RSS는 launcher/JVM 프로세스 전체 관측값이다. Planner 전용 heap peak 또는 비용표만의 메모리 크기로 해석하지 않는다. StepLM·GLM의 공유 경로 RSS 중앙값은 약간 낮았고 L2SVM은 높았다. 3회만으로 일반적인 메모리 절감이나 통계적 speedup을 주장하지 않는다.

공유 경로가 같은 반복의 Global보다 빠른 횟수는 L2SVM 1/3, StepLM 0/3, GLM 3/3이었다. 기존 경로 대비 공유 경로의 paired 개선 횟수는 L2SVM 1/3, StepLM 3/3, GLM 2/3이었다. GLM은 3회차에서 기존 경로보다 느렸다. 실패한 행은 없었고 모든 27행을 집계했다.

## 5. 인증값의 해석

| Workload | 초기 L | 초기 U | 독립 Global C* |
| --- | ---: | ---: | ---: |
| L2SVM | 480.654339145 | 846.018888298 | 846.018888298 |
| StepLM | 221433387.927 | 1063535533.26 | 1062470168.43 |
| GLM | 8819.91580279 | 8969.38014393 | 8943.13014393 |

L은 최적 modeled cost의 하한이다. U−L은 계획 오차의 인증 상한이며 실제 regret U−C*와 다르다. L2SVM은 Regional 계획 자체가 이미 Global과 같았지만 첫 LB가 약해 76% 인증 gap이 나왔다. StepLM도 실제 modeled regret는 약 0.100%인데 첫 인증 gap은 380%였다. 사용자가 지정한 정책에 따라 두 사례는 정확성 증명을 위해 exact를 실행했다. 이 추가 비용이 Global보다 느린 중요한 이유이며, Regional 계획이 실제로 그만큼 나쁘다는 뜻이 아니다.

이 사례들에서 더 큰 개선을 얻으려면 L2SVM의 encoded 지역 구조 준비 비용, GLM의 encoded 지역 solve 비용, 그리고 첫 LB가 지나치게 느슨한 문제를 별도로 다뤄야 한다. 이번 결과로 새 공유 경로를 모든 workload에 더 빠른 방법이라고 추천하지 않는다. 이번 요청 범위의 구현과 검증은 완료했고, 추가 정책이나 큰 재실험은 수행하지 않았다.

## 6. 실험 조건과 재현

- so007 로컬 Java 17 JVM, Docker·worker·workload execution 없음.
- X PRIVATE_AGGREGATE / Y PUBLIC. 기존 frozen DML·metadata·shape·nnz 사용.
- LAN 모델: upload/download 625 MB/s, latency 0.001초, modeled worker 1개.
- 세 방법 모두 singleton compact=false, 같은 encoded cost/legality 모델과 같은 JAR.
- 3개 workload × 3개 방법 × 3회 = 27번 fresh JVM. 방법 순서는 workload와 반복에 따라 회전.
- JVM `-Xmx8g -Xms8g -Xmn800m -XX:ActiveProcessorCount=8`.
- relativeGap=0.05, absoluteGap=0, width=2, maxWidth=2, factorCells=10000000, totalCells=50000000.
- scheduling budget=20000ms, outer JVM timeout=60초. Seed 이후 scheduling budget이며 실제 전체 planning 시간은 별도 기록.
- exactClosureAssignments와 regionWorkLimit은 이번 제한된 파일럿에서 Long.MAX_VALUE. Factor/cell 한도는 유지.

실제 method별 전체 설정과 실행 순서는 [동결 protocol](/home/mchoi/so007-regional-shared-preparation-evidence-20260909/native/protocol.json) 및 [matrix](/home/mchoi/so007-regional-shared-preparation-evidence-20260909/native/runs/shared-five-percent-v1/matrix.json)에 있다. 기본 정책의 핵심 설정은 아래와 같다. 재현 시 factor/resource/time 설정까지 protocol과 맞춰야 한다.

```text
-Dsysds.fedplanner.regional.mode=anytime
-Dsysds.fedplanner.regional.algorithm=remaining-exact
-Dsysds.fedplanner.regional.sharedPreparation=true
-Dsysds.fedplanner.regional.relativeGap=0.05
-Dsysds.fedplanner.regional.absoluteGap=0
-Dsysds.fedplanner.regional.compact=false
-Dsysds.fedplanner.exact.compact=false
```

구현은 로컬 작업트리와 별도 원격 source/build에 있다. 이전 frozen baseline source/JAR 및 결과는 보존했다.

- 로컬 작업트리: `/home/mchoi/so007-anytime-incremental-20260908`
- so007 새 source/build: `/home/mchoi/so007-regional-shared-preparation-20260909`
- so007 검증 JAR: `/home/mchoi/so007-regional-shared-preparation-20260909/target/SystemDS.jar`
- so007 실제 pilot runtime JAR: `/home/mchoi/so007-regional-shared-preparation-evidence-20260909/native/runtime/target/SystemDS.jar`
- JAR SHA-256: `0a172441ab63ef9a09729efd4ce8cbd33441a38586d575c6f428a6d1628cc4ed`
- source manifest SHA-256: `ffc913d04af31f6e3afd167b15baacd783bcb0e28b5641562ee94609b6d61e33`

## 7. 검증과 산출물

최종 remote Maven clean package에서 11개 class, **109 tests, failures/errors/skips 모두 0**이었다. 5% 조기 종료, 초과 시 정확히 한 번의 closure, root 객체 재사용, boundary 변경에 따른 cache 재생성, 원래 domain 복원, 공개 배열 방어 복사, lazy 표 single materialization을 검사했다. Physical integration test는 auxiliary가 있는 실제 encoded 모델에서 지역 결과를 전체 조건부 exact oracle과 비교했다. 기존 canonical/feasibility 및 Regional/replica 회귀 테스트도 포함했다.

독립 verifier도 27개 raw log를 직접 읽어 위 시간·초기 U/L 동일성·5% 분기·root 재사용을 확인했고 PASS를 반환했다. `git diff --check`와 Python syntax check를 통과했다. 전체 repository Java suite와 Checkstyle/Spotless/license/RAT 전체 검사는 수행하지 않았다. 새 실험에는 최종 v2 JAR만 사용했다. 중간 v1도 109 tests를 통과했지만 재전처리 제거 전 단계이며 성능 데이터가 없다.

자동 검증은 27개 raw log와 command/receipt/observation hash, 모델 fingerprint, checkpoint 단조성 및 보수적 gap, Global oracle 포함, exact raw-bit parity, 실제 branch sequence를 확인했다. Postflight에서 새 source 7,515개, 이전 source 7,513개, 입력 161개, runtime assets 318개를 확인했고 output이 없으며 활성 pilot 프로세스가 없는 것을 확인했다.

Provenance 주석 하나는 별도로 정정했다. 동결 context의 `source_manifest_sha256`는 부모 build 값을 상속한 상태였다. 실제 새 JAR hash 및 protocol의 source hash는 처음부터 최종 v2와 일치했다. 입력·실행 설정·계산 결과에는 영향이 없으며 동결 context는 수정하지 않고 [provenance-annotation.json](/home/mchoi/so007-regional-shared-preparation-evidence-20260909/validation/provenance-annotation.json)에 올바른 연결을 기록했다.

| 자료 | 경로 |
| --- | --- |
| 최종 결과 / 모든 27행 | [pilot-results.json](/home/mchoi/so007-regional-shared-preparation-evidence-20260909/validation/pilot-results.json) |
| 관측 CSV | [observations.csv](/home/mchoi/so007-regional-shared-preparation-evidence-20260909/validation/observations.csv) |
| 최종 109 tests | [native-tests-v2.json](/home/mchoi/so007-regional-shared-preparation-evidence-20260909/validation/native-tests-v2.json) |
| 실제 Maven command | [native-build-command-v2.json](/home/mchoi/so007-regional-shared-preparation-evidence-20260909/validation/native-build-command-v2.json) |
| 원시 빌드 로그 | [native-build-v2.log](/home/mchoi/so007-regional-shared-preparation-evidence-20260909/validation/native-build-v2.log) |
| 사전 조건 | [preflight.json](/home/mchoi/so007-regional-shared-preparation-evidence-20260909/validation/preflight.json) |
| 사후 source/input/JAR 검증 | [postflight.json](/home/mchoi/so007-regional-shared-preparation-evidence-20260909/validation/postflight.json) |
| 변경 파일 manifest | [source-patch-manifest.json](/home/mchoi/so007-regional-shared-preparation-evidence-20260909/validation/source-patch-manifest.json) |
| 구현 이슈와 잔여 한계 | [SESSION_ISSUES_2026-09-09.md](/home/mchoi/so007-anytime-incremental-20260908/docs/SESSION_ISSUES_2026-09-09.md) |

변경 파일은 기존 대비 main 9개, test 5개다. 핵심 구현은 다음 파일에 있다.

- [SharedRegionalPreparation.java](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/SharedRegionalPreparation.java)
- [LocalCategoricalOptimizer.java](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java)
- [LocalPhysicalOptimizer.java](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java)
- [ExactCategoricalSolver.java](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.java)
- [ExactPhysicalReducedSolver.java](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalReducedSolver.java)
- [RegionalSearchProblem.java](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchProblem.java)
- [RemainingExactOptimizer.java](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RemainingExactOptimizer.java)
- [CertifiedRegionalOptimizer.java](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CertifiedRegionalOptimizer.java)
- [ExactPhysicalOptimizer.java](/home/mchoi/so007-anytime-incremental-20260908/src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalOptimizer.java)
