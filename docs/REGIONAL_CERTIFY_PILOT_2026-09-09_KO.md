# 원래 Regional 완료 후 최초 LB 1회: GLM·L2SVM 소규모 검증 보고서

작성일: 2026-09-09. 실행 서버: so007 (`dams-so007`). 상태: **8/8 실행 및 4/4 paired 검증 완료**. 모든 실행은 native JVM planning-only이며, 조건별 반복은 1회다.

**원래 Regional을 끝내면 계획 품질은 크게 개선됐다. GLM은 LB 한 번으로 3%와 5%를 인증했다. 다만 GLM에서는 Regional 지역 DP 자체가 오래 걸려, 인증을 한 번만 붙여도 전체 planning이 compact Global보다 느렸다.** L2SVM은 Global과 같은 비용의 계획을 찾았지만 최초 LB가 약해 3%·5%를 인증하지 못했다.

이번 변경은 기존 `mode=certify, algorithm=legacy` 경로에 전역 compact 준비와 시간 계측을 추가한 것이다. 반복 refinement를 실행하지 않았다. V5 `AnytimeIncremental`의 기본 초기화 정책을 바꿨다고 보고하는 문서는 아니다.

**왜 다시 확인했는가**

앞선 V5 incremental의 초기화는 `regionalSeed(..., false)`를 사용해 비용 개선을 위한 원래 interaction/materialization 지역 블록을 생략했다. 따라서 당시 초기 U를 “원래 Regional을 다 수행한 계획의 비용”으로 해석하면 안 된다. 그 초기 계획에 대해 계산한 유효 LB와 gap은 의미가 있지만, 이를 원래 Regional의 품질 평가로 확대할 수 없다.

이번에는 원래 `regionalSeed(..., true)` 경로를 완료한 후 그 assignment와 canonical cost를 유지하면서 LB를 딱 한 번 계산했다. 원래 Regional의 optional 추가 revisit 횟수는 기본값 0이다. 즉 기존 지역 개선을 정상 수행한 결과이며, 겹치는 모든 region을 fixed point까지 반복한 결과를 뜻하지는 않는다.

**실험 조건과 횟수**

| 항목 | 설정 |
| --- | --- |
| 조합 | GLM·L2SVM × 혼합 privacy·모두 PUBLIC × Global·RegionalCertify = 8 JVM |
| 혼합 privacy | X=PRIVATE_AGGREGATE, Y=PUBLIC |
| 모두 PUBLIC | X=PUBLIC, Y=PUBLIC |
| 데이터 metadata | P2P2D, X=50,000×2,100, Y=50,000×1; 기존 DML·metadata 재사용 |
| 네트워크 비용 모델 | LAN: C2W/W2C 각 5,000 Mbit/s, RTT 설정 1 ms |
| 실제 실행 방식 | so007 로컬 JVM. Docker·worker·workload 실행 없음 |
| Global | 같은 JAR와 모델의 기존 compact + exact 경로 |
| RegionalCertify | 원래 Regional 완료 → 전역 exact compaction → width-2 MBE 1회 → gap 보고 |
| 인증 설정 | `width=2`, `maxWidth=2`, `rounds=1`, `certifyCompact=true`, 추가 region 탐색 없음 |
| 자원 설정 | factor 1,000,000 cells, total 5,000,000 cells; after-seed scheduling budget 20초, 외부 JVM watchdog 60초 |
| 목표 | 실행 인자 5%; 같은 단일 BOUND 결과로 3%도 판정. 목표별 재실행 없음 |
| JVM | `-Xmx8g -Xms8g -Xmn800m -XX:ActiveProcessorCount=8` |
| Seed | SystemDS 2026072701, Regional 20260908 |
| 반복·순서 | 각 cell 1회. GLM은 Global→Regional, L2SVM은 Regional→Global; mixed 이후 public |

네트워크 값은 비용 함수의 입력이다. 실제 네트워크 지연을 가하거나 데이터를 전송하지 않았다. 사용자 지시에 따라 기존 Docker-only/public-ignore 로컬 지침보다 이번 native JVM·두 privacy 조건을 우선했다. 공개 조건을 위해 privacy나 placement legality를 완화하지 않았다.

**계획 비용과 인증 결과**

다음 비용의 단위는 **modeled ms**이며 workload 실행시간 측정값이 아니다. C*는 같은 조건에서 독립 실행한 Global 결과다. 온라인 인증은 C*를 입력으로 사용하지 않는다.

| 조건 | Global 최적 비용 C* | Regional 비용 U | 최초 LB L | 실제 modeled regret | 인증 gap | 3% / 5% |
| --- | --- | --- | --- | --- | --- | --- |
| GLM / 혼합 privacy | 8943.130144 | 8969.380144 | 8827.827412 | 0.2935% | 1.6035% | 달성 / 달성 |
| L2SVM / 혼합 privacy | 846.018888 | 846.018888 | 480.654339 | 0.0000% | 76.0140% | 미달 / 미달 |
| GLM / 모두 PUBLIC | 8943.130144 | 8943.130144 | 8827.827412 | 0.0000% | 1.3061% | 달성 / 달성 |
| L2SVM / 모두 PUBLIC | 846.018888 | 846.018888 | 480.654339 | 0.0000% | 76.0140% | 미달 / 미달 |

검증한 식은 `L ≤ C* ≤ U`다. 실제 modeled regret는 `(U−C*)/C*`, 보수적인 인증 gap은 `(U−L)/L`의 상한이다. L은 **최적 비용의 하한**이고, U−L은 **오차의 상한**이다.

GLM 혼합 privacy의 실제 modeled regret는 약 0.294%이고 인증 gap은 약 1.603%다. 모두 PUBLIC에서는 Regional이 Global과 같은 비용을 찾았고 인증 gap은 약 1.306%다. 두 경우 모두 최초 bound에서 3%와 5%를 충족한다.

L2SVM은 두 privacy 모두 U=C*다. 그럼에도 L=480.654339가 C*=846.018888보다 많이 낮아 인증 gap이 76.014%다. **계획에 실제로 76% 오차가 있다는 뜻이 아니다. 이번 한 번의 LB 검사만으로는 좋은 계획이라는 사실을 충분히 증명하지 못한 것이다.** Raw 종료 코드는 `CERTIFIED`지만 `targetReached=false`다. 이 코드를 목표 달성이나 exact 인증으로 해석하지 않는다.

**Regional을 끝내는 시간과 오차 확인 비용**

아래 시간 단위는 모두 **초**다. `그중` 열은 앞선 전체 시간에 포함되므로 다시 더하지 않는다.

| 조건 | Regional 전체 | 그중 지역 DP solve | 인증 추가 전체 | 그중 compact + MBE |
| --- | --- | --- | --- | --- |
| GLM / 혼합 privacy | 6.160930 | 5.859329 | 0.661302 | 0.560749 |
| L2SVM / 혼합 privacy | 0.379782 | 0.254553 | 0.135770 | 0.120517 |
| GLM / 모두 PUBLIC | 12.693431 | 12.389255 | 0.684479 | 0.583378 |
| L2SVM / 모두 PUBLIC | 0.559984 | 0.439937 | 0.192252 | 0.176306 |

질문한 “지역 DP 연산 자체”에 가장 가까운 측정은 `지역 DP solve`다. 이는 single-variable enumeration 또는 `FactorizedBlockSolver.solve()` 호출의 누적 시간이다. Factor 준비와 전체 planner의 공통 작업은 제외하고, 호출 내부의 solver 작업은 포함한다. 단순 산술 kernel만을 따로 측정한 값은 아니다.

“정상 Regional 계획 하나를 얻는 데 걸리는 시간”은 `Regional 전체`다. Producer order와 region 구성, ordered pass, 필요한 hard repair, interaction/materialization 개선, 최종 local objective 확인을 포함한다.

“그 계획의 오차를 확인하기 위해 추가한 비용”은 `인증 추가 전체`다. 인증 입력 검증·준비·compact·MBE와 해당 경로의 trace overhead를 포함한다. 핵심 LB 계산 비용만 보면 `compact + MBE`다. **MBE 시간만으로 인증 비용을 보고하면 compact 준비를 빠뜨리게 된다.**

세부 계측은 다음과 같다.

| 조건 | Local optimizer 전체 | DP 준비 | compact 준비 | MBE | 지역 블록 수 |
| --- | --- | --- | --- | --- | --- |
| GLM / 혼합 privacy | 6.071835 | 0.115936 | 0.266820 | 0.293930 | 67 |
| L2SVM / 혼합 privacy | 0.362484 | 0.070857 | 0.065601 | 0.054916 | 3 |
| GLM / 모두 PUBLIC | 12.609894 | 0.137572 | 0.269357 | 0.314021 | 65 |
| L2SVM / 모두 PUBLIC | 0.545823 | 0.076063 | 0.088445 | 0.087861 | 4 |

GLM에서 compact+MBE는 약 0.56~0.58초, 인증 경로 전체는 0.66~0.68초였다. 반면 Regional 자체는 6.16~12.69초, 그중 지역 DP solve는 5.86~12.39초였다. **이 pilot의 GLM 병목은 최초 LB보다 Regional DP에 있다.** L2SVM의 인증 경로 추가 비용은 0.136~0.192초였지만 LB 품질이 부족했다.

**Global보다 빨랐는가**

전체 planner 시간은 로그의 `Compile Phase FedPlanner` 측정이다. 앞선 Regional/인증 타이머 외에도 모델 구성 등 planner 공통 작업을 포함하므로 그 두 값을 더한 것과 같지 않다. 두 방법 모두 같은 경계의 전체 planner 시간을 비교했다.

| 조건 | Global planner | Regional + 인증 planner | 관측한 상대 시간 | 3%·5% 인증 |
| --- | --- | --- | --- | --- |
| GLM / 혼합 privacy | 6.449812 | 8.733639 | 1.35배 | 달성 |
| L2SVM / 혼합 privacy | 1.211039 | 1.082844 | 0.89배 | 미달 |
| GLM / 모두 PUBLIC | 5.643602 | 15.412081 | 2.73배 | 달성 |
| L2SVM / 모두 PUBLIC | 0.989488 | 1.304845 | 1.32배 | 미달 |

GLM은 3%·5% 인증에 성공했지만 전체 planner 비용은 Global보다 컸다. 혼합 privacy L2SVM은 한 번의 관측에서 Regional+인증이 조금 빨랐지만, 요청한 목표는 미달이므로 “3%·5% 인증을 Global보다 빨리 했다”는 성공으로 세지 않는다. **이번 4개 cell에는 목표를 인증하면서 Global보다 빨랐던 사례가 없다.**

GLM의 3%와 5%는 같은 최초 BOUND에서 함께 충족됐다. 표의 값은 해당 인증 결과를 반환하는 전체 planner 완료 비용이며, 두 threshold에 대해 별도 반복 실험한 time-to-threshold 추정치가 아니다.

JVM 시작·컴파일·종료를 포함한 wall time과 프로세스 peak RSS도 보존했다. RSS는 전체 JVM의 관측치이며 planner 자료구조만의 메모리가 아니다.

| 조건 | Global JVM wall 초 | Regional JVM wall 초 | Global peak RSS MiB | Regional peak RSS MiB |
| --- | --- | --- | --- | --- |
| GLM / 혼합 privacy | 18.966 | 20.899 | 1621.2 | 1649.4 |
| L2SVM / 혼합 privacy | 4.479 | 4.152 | 761.4 | 839.8 |
| GLM / 모두 PUBLIC | 17.802 | 26.255 | 1614.2 | 1648.5 |
| L2SVM / 모두 PUBLIC | 4.291 | 4.787 | 833.5 | 1090.0 |

**이전 초기 계획과 비교했을 때 바뀐 부분**

| 조건 | 이전 V5 INITIAL 비용 | 원래 Regional 완료 비용 | 동일 모델 fingerprint 확인 |
| --- | --- | --- | --- |
| GLM / 혼합 privacy | 58481.944168 | 8969.380144 | 일치 |
| L2SVM / 혼합 privacy | 1211.039816 | 846.018888 | 일치 |
| GLM / 모두 PUBLIC | 402715.923929 | 8943.130144 | 일치 |
| L2SVM / 모두 PUBLIC | 168269.402390 | 846.018888 | 일치 |

네 cell 모두 이전 V5 결과와 analysis/cost fingerprint가 일치한다. 최초 LB도 부동소수점 마지막 자리 수준을 제외하면 같다. 개선의 중심은 초기 계획 U다. 이전 V5의 전체 실행 최종값이 아니라 `INITIAL_BOUND` 시점의 비용과 비교했다.

GLM 혼합 privacy의 초기 비용 58,481.944168은 원래 Regional을 완료하자 8,969.380144가 됐다. 모두 PUBLIC에서는 402,715.923929에서 Global과 같은 8,943.130144가 됐다. 따라서 이전에 큰 gap을 관측했다는 이유만으로 원래 Regional 자체가 나쁜 계획을 낸다고 설명한 것은 부정확했다.

다만 이번 결과는 GLM planning space 전체가 runtime의 모든 합법적인 계획을 완벽하게 표현한다는 증명은 아니다. 독립 Global은 동일 encoded model 안의 oracle이다. 기존 GLM WAN_mid의 occurrence identity 손실에 의한 lowering 오류는 별개로 남아 있으며, 이번 LAN 성공으로 해결됐다고 처리하지 않았다.

**구현 변경과 인증 계약**

| 파일 | 변경 내용 |
| --- | --- |
| [CertifiedRegionalOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CertifiedRegionalOptimizer.java) | 선택적 `certifyCompact`를 bound-only 경로에 적용. 전체 unconditioned model의 exact compaction 후 MBE. 준비 실패 시 기존 feasible plan과 유효 bound 보존 |
| [LocalPhysicalOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalPhysicalOptimizer.java) | Regional seed 전체와 그 뒤 인증 경로 전체의 시간·실행 경계를 기록 |
| [LocalCategoricalOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizer.java) | local optimizer 전체, block 준비, block solve 누적 시간. 기존 statistics 생성자 호환 유지 |
| [FederatedPlanLocalCost.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java) | `certifyCompact` 설정을 CONFIG trace에 기록 |
| [CertifiedRegionalPhysicalIntegrationTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CertifiedRegionalPhysicalIntegrationTest.java) | 인증 전후 assignment/canonical cost 보존, Global enclosure, 자원 제한의 보존 계약 검증 |
| [LocalCategoricalOptimizerTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LocalCategoricalOptimizerTest.java) | timer 포함 관계와 기존 statistics 생성자 검증, 비결정적 시간을 알고리즘 결정성 비교에서 제외 |

새 옵션은 `!expandRegions`일 때만 적용된다. Bound 모델에 region 밖 incumbent assignment를 고정하지 않는다. Hard factor, auxiliary, 고정으로 생긴 상수 비용을 포함한 전역 모델을 유지한다. Canonical cost는 원래 assignment와 evaluator로 확인한다. 원래 Regional의 조건부 최적 비용을 global LB로 사용하는 경로는 없다.

**검증 결과와 재현 가능한 증거**

- so007의 격리된 source에서 Maven `clean package` 성공. 8개 focused class, 94개 선언 testcase 중 **93개 실행·통과**, 실패 0, 오류 0.
- Skip 1개는 기존 선택형 TSV exporter `writesControlledAblationRowsWhenOutputIsConfigured`다. 출력 경로 property가 없을 때 건너뛰는 테스트이며 새로운 correctness regression의 skip이 아니다.
- 최초 build wrapper는 skip=0을 기대해 Maven 성공 이후 assertion을 냈다. 실제 skipped testcase를 확인하고 위 exporter만 허용하도록 검증 조건을 정정했다. 빌드 결과를 성공으로 조작하거나 테스트를 추가로 건너뛰도록 바꾸지 않았다.
- 이번 빌드에서는 checkstyle/spotless/license/RAT 전체 검사를 생략했다. 관련 Java 테스트와 `git diff --check`, Python helper 문법 검증을 수행했다. 저장소 전체 테스트를 수행했다고 주장하지 않는다.
- 8/8 planning-only 성공. Runtime 실행 시간 0, output 미생성, coordinator log·command·receipt hash와 paired 모델 일치 확인.
- 4/4 쌍의 `L ≤ C* ≤ U`, 원래 지역 블록 실행, `INITIAL` 뒤 `BOUND` 정확히 1회, 추가 region 탐색 0회, 인증 전후 U 보존 확인.
- Native verifier의 제한된 독립 correctness/timing review 통과. 기록: [CERTIFY_CORRECTNESS_REVIEW.md](/home/mchoi/so007-regional-certify-evidence-20260909/validation/CERTIFY_CORRECTNESS_REVIEW.md).
- 최종 postflight: privacy별 input 161개·runtime asset 318개, 새 source 7,506개, 기존 source 7,506개와 기존 runtime 318개 보존 확인. Pilot 활성 프로세스 0. 검사 시각 `2026-09-09T03:20:30.832526+00:00`.

실험별 1회, workload 2개, LAN 1개 환경의 pilot이다. 시간 변동·privacy의 인과적 성능 차이·통계적 speedup은 결론 내리지 않는다. Peak RSS에도 JVM 초기 heap과 컴파일 등 공통 비용이 포함된다. Exact region solve에 hard deadline 지원을 추가한 작업도 아니다.

| 산출물 | 절대 경로 / 식별자 |
| --- | --- |
| 보고서 | `/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_CERTIFY_PILOT_2026-09-09_KO.md` |
| 실행용 격리 source — so007 | `/home/mchoi/so007-regional-certify-20260909` |
| Local 작업 checkout 기반 commit | `c8d23ffe283668483fde257521fad7c925be6d18` |
| 그 이전 V5 implementation commit | `db092e3fea85789b28e4e4570f14f9ffc48a0036` |
| 새 구현 | 위 source에 6개 파일 patch 적용; 새 commit으로 표시하지 않음 |
| Patch SHA-256 | `d6ce636a31cc55f16feb261339f73c6a2a90952645d0a26c5e0334a906be8cde` |
| 새 JAR SHA-256 | `ffaee7aebb04fb2fa0afbb1634ce792df77cd2148b90c32bb2bf89b41c70811e` |
| Source manifest SHA-256 | `8fa17a0f12f314b8372c5790f5d828234c5e37790156aebb871d1d851a18a82c` |
| 결과 JSON | [pilot-results.json](/home/mchoi/so007-regional-certify-evidence-20260909/validation/pilot-results.json) |
| 소스·빌드·테스트 | `/home/mchoi/so007-regional-certify-evidence-20260909/validation/` 내 `source-manifest.json`, `source-patch-manifest.json`, `implementation.patch`, `native-build-command.json`, `native-tests.json` |
| 최종 보존 검증 | [final-postflight.json](/home/mchoi/so007-regional-certify-evidence-20260909/validation/final-postflight.json) |
| 혼합 privacy 원본 | `/home/mchoi/so007-regional-certify-evidence-20260909/native/runs/regional-certify-mixed-v1/` |
| 모두 PUBLIC 원본 | `/home/mchoi/so007-regional-certify-evidence-20260909/native/runs/regional-certify-public-v1/` |
| 고정 protocol/context | `/home/mchoi/so007-regional-certify-evidence-20260909/native/protocol-mixed.json`, `protocol-public.json`, `context-mixed.json`, `context-public.json` |

보고서와 결과·검증 자료는 로컬 및 so007의 동일 절대 경로에 보존한다. 실행용 frozen runtime과 source build는 so007에 있다. 원본 로그 바로가기:

- GLM / 혼합 privacy: [Regional 원본 로그](/home/mchoi/so007-regional-certify-evidence-20260909/native/runs/regional-certify-mixed-v1/trials/regional-certify-mixed-v1_0001_glm_lan_RegionalCertify/results/fed1/mkl-cost/glm_mkl-cost_regional-certify-mixed-v1_0001_glm_lan_RegionalCertify.log)
- L2SVM / 혼합 privacy: [Regional 원본 로그](/home/mchoi/so007-regional-certify-evidence-20260909/native/runs/regional-certify-mixed-v1/trials/regional-certify-mixed-v1_0002_l2svm_lan_RegionalCertify/results/fed1/mkl-cost/l2svm_mkl-cost_regional-certify-mixed-v1_0002_l2svm_lan_RegionalCertify.log)
- GLM / 모두 PUBLIC: [Regional 원본 로그](/home/mchoi/so007-regional-certify-evidence-20260909/native/runs/regional-certify-public-v1/trials/regional-certify-public-v1_0001_glm_lan_RegionalCertify/results/fed1/mkl-cost/glm_mkl-cost_regional-certify-public-v1_0001_glm_lan_RegionalCertify.log)
- L2SVM / 모두 PUBLIC: [Regional 원본 로그](/home/mchoi/so007-regional-certify-evidence-20260909/native/runs/regional-certify-public-v1/trials/regional-certify-public-v1_0002_l2svm_lan_RegionalCertify/results/fed1/mkl-cost/l2svm_mkl-cost_regional-certify-public-v1_0002_l2svm_lan_RegionalCertify.log)

**이번 결과에 따른 판단**

“Regional 계획을 만든 뒤 오차만 한 번 검사한다”는 단순한 경로는 구현과 인증 논리가 성립한다. GLM에서는 실제로 목표도 충족했다. 그러나 속도 개선을 위해서는 GLM의 지역 DP 중복·블록 크기·중간 table 작업을 줄이는 문제가 남는다. 이는 계측이 가리키는 후속 조사 대상이며 이번에 개선했다고 주장하지 않는다.

L2SVM에서는 계획을 더 바꾸기보다 이미 좋은 계획을 짧은 시간에 인증할 강한 LB가 필요한 상황이다. 한 번의 약한 MBE로 모든 workload를 인증할 수 있다고 기대하기 어렵다. 이 pilot만으로 Anytime 전체의 가능성이나 불가능성을 판정할 근거는 없다.

사용자가 요청한 소규모 검증은 여기서 완료했다. 추가 workload·네트워크·threshold 실행이나 대규모 campaign 재개는 수행하지 않았다.
