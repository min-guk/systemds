# AnytimeTarget 3%·5% 지연 개선 결과

작성: 2026-09-08T18:03:02.098892+00:00. so007 native JVM planning-only.

**채택한 변경은 singleton compact preparation과 whole exact 작업 한도 조정이다.** 축소된 전체 문제를 한 번 푸는 편이 반복 Regional보다 저렴한 경우, 이미 준비한 모델로 exact 종료한다. 초기 Regional+MBE만으로 목표를 만족하면 즉시 반환한다.

GLM 네 가지 모델 네트워크의 3%·5% 집중 반복에서 목표 달성은 **16/16**다. 성공한 paired 비교 16개 중 planner 시간이 짧은 경우는 16개, JVM 시작→결과 수신 시간(TTT)이 짧은 경우는 16개였다. 각 network/threshold당 두 번의 제한된 측정이며 통계적 유의성이나 모든 workload의 우위를 주장하지 않는다.

**Global에는 같은 compact kernel을 적용하지 않았다.** 따라서 이 결과는 기존 Global 대비 구현·제어 개선의 효과이며, 동일하게 최적화한 Global 대비 threshold 알고리즘의 우위를 증명하지 않는다. LM·StepLM은 아래 pilot의 네 조건을 묶은 planner 중앙값에서 Global이 더 짧다.

## 완료 범위와 실패 분모

| 실험 | JVM 실행 | Anytime 목표 달성 | Global 완료 | Anytime 목표 미달 | 프로세스 실패 |
| --- | --- | --- | --- | --- | --- |
| 변경 전 | 32 | 14/16 | 16/16 | 2 | 0 |
| Singleton compact | 32 | 14/16 | 16/16 | 2 | 0 |
| Compact + whole 1m | 32 | 16/16 | 16/16 | 0 | 0 |
| GLM 집중 반복 | 32 | 16/16 | 16/16 | 0 | 0 |

앞의 세 pilot은 PCA·LM·StepLM·GLM × LAN/WAN-mid × 5%/3% × 두 방법 × 1회다. 마지막 집중 반복은 GLM × 네 환경 × 5%/3% × 두 방법 × 2회다. 미달 행을 삭제하지 않았으며, 16-workload 전체 연구를 완료한 결과가 아니다. 이전 1,728회 계획은 사용자 방향 변경으로 350행에서 중단한 상태를 보존했다.

## GLM 반복 결과

단위는 초, 각 칸은 두 실행의 중앙값이다. 3%와 5%는 독립 JVM 실행이다. Planner는 로그의 `Compile Phase FedPlanner` 계측값이다. TTT의 끝점은 AnytimeTarget에서는 처음 목표를 만족하는 checkpoint, Global에서는 exact 결과의 `[Physical-CostContributionComplete]` 로그 도착이다. 둘 다 JVM 시작부터 관측해 JVM 준비와 공통 compiler 전처리를 포함한다. Global에 별도 threshold checkpoint가 있다고 가정하지 않는다.

| 모델 환경 | 목표 | Anytime 성공 | Anytime planner | Global planner | Anytime TTT | Global TTT | Anytime exact 종료 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| lan | 5% | 2/2 | 4.062 | 8.991 | 13.679 | 19.235 | 0/2 |
| lan | 3% | 2/2 | 4.140 | 9.280 | 13.613 | 18.956 | 0/2 |
| wan_light | 5% | 2/2 | 4.639 | 9.675 | 14.526 | 20.481 | 2/2 |
| wan_light | 3% | 2/2 | 5.188 | 9.308 | 16.497 | 18.982 | 2/2 |
| wan_mid | 5% | 2/2 | 4.556 | 10.242 | 14.558 | 19.710 | 2/2 |
| wan_mid | 3% | 2/2 | 4.566 | 9.615 | 13.998 | 19.081 | 2/2 |
| wan_heavy | 5% | 2/2 | 5.156 | 9.955 | 14.613 | 19.795 | 2/2 |
| wan_heavy | 3% | 2/2 | 4.597 | 9.597 | 14.435 | 18.944 | 2/2 |

LAN은 초기 Regional+MBE의 약 1.888% gap으로 두 목표에 도달한다. WAN의 exact 종료는 3%·5%보다 강한 L=U=C* 인증이다. 이 exact 종료를 근사 탐색만으로 threshold에 도달한 사례로 세지 않는다.

각 환경의 실제 process peak RSS도 함께 기록했다. 아래 단위는 MiB이며 3%·5%의 두 반복을 합친 방법별 네 프로세스다. Table cells로 환산한 메모리가 아니다.

| 모델 환경 | 방법 | 관측 수 | Peak RSS 중앙값 MiB | Peak RSS 최대 MiB |
| --- | --- | --- | --- | --- |
| lan | AnytimeTarget | 4 | 1624.150 | 1629.582 |
| lan | Global | 4 | 1617.895 | 1620.352 |
| wan_light | AnytimeTarget | 4 | 1622.904 | 1632.934 |
| wan_light | Global | 4 | 1624.885 | 1632.117 |
| wan_mid | AnytimeTarget | 4 | 1630.373 | 1648.344 |
| wan_mid | Global | 4 | 1626.707 | 1633.379 |
| wan_heavy | AnytimeTarget | 4 | 1634.451 | 1637.605 |
| wan_heavy | Global | 4 | 1616.748 | 1627.477 |

## 원인: GLM/WAN-mid 3%의 단계별 변경

| 설정 | 전체 planner | 진단·준비 | Regional solve 수 | Whole exact 완료 | 최종 인증 gap | 목표 달성 |
| --- | --- | --- | --- | --- | --- | --- |
| 변경 전 | 16.144 | 10.415 | 6 | 0 | 28.0829% | false |
| Singleton compact | 10.321 | 3.120 | 13 | 0 | 11.6965% | false |
| Compact + whole 1m | 4.611 | 0.324 | 0 | 1 | 0.0000% | true |

Compact root의 예상 elimination 작업량은 688,683, materialized cells는 187,556이었다. Whole 한도 100,000은 이 준비 결과를 거절했고, 13개 region에 합계 7,400,179 작업을 사용했다. Whole 한도를 기존 region 한도와 같은 1,000,000으로 맞추자 준비 결과를 한 번 사용해 종료했다. 예상 작업량은 wall-clock 시간의 단위가 아니므로 실제 준비·solve 시간을 함께 비교했다.

## 다른 workload의 결과도 포함

아래는 whole 1m pilot의 LAN/WAN-mid × 5%/3% 네 실행을 묶은 planner 중앙값이다. 각 cell 1회인 진단 결과이며, pooling을 통계적 speedup 근거로 사용하지 않는다.

| Workload | Anytime planner | Global planner | Anytime 목표 달성 |
| --- | --- | --- | --- |
| pca | 0.575 | 0.745 | 4/4 |
| lm | 1.103 | 0.827 | 4/4 |
| steplm | 4.062 | 1.855 | 4/4 |
| glm | 4.279 | 10.738 | 4/4 |

LM·StepLM에서는 초기 Regional+setup 자체가 큰 비용이다. 특히 StepLM의 seed 이후 처리만 줄여서는 이미 더 빨리 끝나는 Global을 일관되게 이길 수 없다. State-key 호출 중복도 조사했지만 Alternative.signature는 이미 저장된 문자열의 accessor이므로 문자열 재생성 병목으로 단정하지 않았고, 근거 없는 캐시 변경은 추가하지 않았다.

## 설정·정확성·범위

- 구현 commit: `a2a210d4bf0f3e6231b2e6d8be1e5067265d43e7`.
- JAR SHA-256: `222d47cd4a8ddd56746f4c75efb86f195455e4b5f2372fea1dbfd3732f5b0869`.
- 선택 설정: `targetCompactPreparation=true`, `exactClosureAssignments=1000000`, `regionWorkLimit=1000000`.
- Java의 generic work-limit 기본값은 100,000을 유지했다. 1m은 이번 실험에서 검증한 명시적 설정이다.
- Certified hard cap: factor 1,000,000 cells / total 5,000,000 cells. Global은 기존 production cap과 solver 경로 유지.
- 동일 초기 계획·assignment·raw MBE·order, modeled objective, privacy/placement 규칙을 검증했다.
- 로컬·so007 각각 19개 클래스 158개 테스트 통과, source 7,502파일 검증, 동일 JAR 확인.
- 각 campaign 원본 로그·receipt·command·관측 trace 및 선언된 479개 runtime/input 파일을 native에서 다시 해시 검증했다.
- 예산은 seed 이후 soft 20초, 별도 JVM watchdog 60초다. 실행 중인 exact solve에 hard deadline을 보장하지 않는다.
- Docker·worker·실제 workload는 실행하지 않았다. 네 환경의 bandwidth/RTT 수치만 cost model에 적용했다.
- Reduced global MBE, active-decision region selection, 새 adaptive scheduler는 이번 변경에 구현하지 않았다.

Prepared의 table 통계는 전체 JVM peak memory가 아니다. 원본 row의 `peak_rss_kib`가 실제 자원 지표이며 프로세스별 값은 상세 보고서/JSON에 보존돼 있다. Global의 phase별 counter는 없으므로 미계측을 0 비용으로 해석하지 않는다.

## 근거 파일

[구현 상세](ANYTIME_FAST_IMPLEMENTATION_REPORT_KO.md), [집중 반복 상세](ANYTIME_FAST_GLM_CONFIRM_KO.md), [whole 1m pilot 상세](ANYTIME_FAST_WHOLE1M_PILOT_KO.md).

- 변경 전: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-before-fast-v1`; analysis SHA `21c89f67949cebb3e31caf68e59e0b795ef2d626389469d3c1a9f4bbc34cade8`; native audit `/home/mchoi/so007-anytime-fast-evidence-20260908/validation/before-fast-native-audit-v1.json`.
- Singleton compact: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-compact-v1`; analysis SHA `b66de262fdb5497bf3b4936df7bcc934d233752858debd74950cd35fa5430288`; native audit `/home/mchoi/so007-anytime-fast-evidence-20260908/validation/compact-v1-native-audit.json`.
- Compact + whole 1m: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-whole1m-v1`; analysis SHA `06167a0fb07cf71041dc1cfb20ebe58e311e09bb19a85bd8d1a0ce7d81c1febf`; native audit `/home/mchoi/so007-anytime-fast-evidence-20260908/validation/whole1m-v1-native-audit.json`.
- GLM 집중 반복: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/glm-confirm-v1`; analysis SHA `16f9018616ef64f351ec9ffdbf647029d35813edb1762d47e020aa58f5cc9388`; native audit `/home/mchoi/so007-anytime-fast-evidence-20260908/validation/glm-confirm-v1-native-audit.json`.
