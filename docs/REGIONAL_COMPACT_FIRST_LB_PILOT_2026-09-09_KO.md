**Regional compact 적용 및 최초 LB 1회 — 이전 16회 pilot 결과**

작성일: 2026-09-09. 이 문서는 이미 완료한 이전 compact pilot의 원본 16회를 정리한다. 아래 표를 만들기 위해 추가 빌드·실험을 실행하지 않았다. 후속 “남은 exact” 8회와는 다른 frozen JAR/protocol이며, 시간 값을 교차 결합하지 않는다.

Regional local solver에도 compact를 적용할 수 있음을 구현·검증했다. 지역 DP의 solve 시간과 작업량은 줄었지만, 추가 준비 비용 때문에 전체 planning 시간의 일관된 개선은 확인하지 못했다. 모든 Regional 계획 비용은 독립 Global optimum과 같았지만, 최초 width-2 LB의 인증 gap은 약76.014%였다.

조건은 L2SVM/P2P2D, LAN(양방향5000 Mbit/s·latency1ms), X PRIVATE_AGGREGATE/Y PUBLIC 및 둘 다 PUBLIC이다. so007 native JVM planning-only, 방법별2회, 네 방법×두 privacy×2회=16회였다. 두 Regional 행은 모두 LB용 compact를 켰으며, Regional **지역 solver**의 compact만 on/off했다. Global on/off는 별도 두 baseline이다.

| Privacy | Global compact on 평균(초) | Global compact off 평균(초) | Regional compact off + LB 평균(초) | Regional compact on + LB 평균(초) |
| --- | ---: | ---: | ---: | ---: |
| X PRIVATE_AGGREGATE / Y PUBLIC | 0.898031 | 1.266940 | 1.140156 | 1.134229 |
| X PUBLIC / Y PUBLIC | 1.237986 | 1.457136 | 1.171679 | 1.223744 |

모두 새 JVM의 전체 planning 시간이며 실패는0/16이었다. Cell당2회로 단순한 관측 평균이며 통계적 speedup을 주장하지 않는다. 혼합의 Regional compact on은 평균약0.52% 짧고 PUBLIC에서는 약4.44% 길었다.

| Regional 내부 시간(초, 평균) | 혼합 off | 혼합 on | PUBLIC off | PUBLIC on |
| --- | ---: | ---: | ---: | ---: |
| full Regional | 0.356520 | 0.371709 | 0.484289 | 0.462234 |
| DP 준비 | 0.066335 | 0.269568 | 0.070300 | 0.338184 |
| DP solve | 0.234817 | 0.046054 | 0.364760 | 0.074167 |
| 인증 추가 전체 | 0.167635 | 0.132081 | 0.122361 | 0.133981 |

DP 준비와 solve는 full Regional 내부에 포함되는 timer다. 인증 추가 전체에는 LB용 compact와 MBE, 검증·기록 비용이 포함된다. 위 행 전체를 합산하지 않는다. 지역 DP solve는 혼합약80.39%, PUBLIC약79.67% 줄었지만 준비는 각각약0.066→0.270초, 0.070→0.338초로 늘었다.

| 지역 solver 작업량 | 혼합 off | 혼합 on | PUBLIC off | PUBLIC on |
| --- | ---: | ---: | ---: | ---: |
| block들의 compile 변수 수 합 | 118 | 90 | 186 | 145 |
| elimination assignment 작업 | 269973 | 36229 | 566030 | 81954 |

변수 수 합은 여러 block의 중복을 포함하며 전체 모델의 고유 변수 수가 아니다. Compact 요청 block 모두 실제 compact 경로를 사용했고 fallback은0이었다. Local seed의 구성·순서·추가 재방문 정책을 바꾼 비교가 아니다.

두 privacy와 모든 방법에서 modeled cost는846.0188882981099 ms였다. Regional의 최초 L은480.65433914497413 ms, 상대 인증 gap은76.01399163545994%였다. 최초 LB로3%·5%를 인증한 경우는0이었다. 실제 modeled regret가0이라는 사실은 독립 Global을 확인한 사후 결과다.

이전 primary build는 관련71개 testcase 중70개 실행 통과, 기존 선택형 TSV exporter1개 skip, 실패·오류0이었다. 이후 supplemental RegionalCompactTest5개도 통과했다(기존4개 재실행 및 추가1개). 이 추가 test를 기존 frozen runtime JAR에 사후로 섞지 않았다. 후속 남은-exact 빌드는 이 test까지 포함해92개를 새로 검증했다. 전체 Java suite/style/license/RAT는 실행하지 않았다.

원본16회, 모든 모델/비용 oracle, timer 포함 관계, compile-only/output 부재를 감사했다. Postflight는 새 source7509개·이전source7508개·runtime318개와 privacy별입력161개를 확인했고, 추가trial0 및 활성pilot0이었다. 원본 source/JAR/입력은 보존했다.

보고서: [/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_COMPACT_FIRST_LB_PILOT_2026-09-09_KO.md](/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_COMPACT_FIRST_LB_PILOT_2026-09-09_KO.md).
원본 비교: [/home/mchoi/so007-regional-compact-evidence-20260909/validation/pilot-results.json](/home/mchoi/so007-regional-compact-evidence-20260909/validation/pilot-results.json).
빌드 검증: [/home/mchoi/so007-regional-compact-evidence-20260909/validation/native-tests.json](/home/mchoi/so007-regional-compact-evidence-20260909/validation/native-tests.json).
보존 감사: [/home/mchoi/so007-regional-compact-evidence-20260909/validation/final-postflight.json](/home/mchoi/so007-regional-compact-evidence-20260909/validation/final-postflight.json).
이전 JAR SHA-256: `cd45e674111a262811ae15c9c57486e3e55e4cfcfaf7ca3fb5e1720406d94e2a`.

Regional compact 자체는 가능하며 DP solve 작업을 줄였다. 이 pilot은 준비 비용까지 포함한 전체 Regional의 확실한 속도 개선을 입증하지 못했다. 후속 “양쪽 compact off + 남은 exact” 비교는 별도 보고서에서 동일 JAR의 새 Global baseline과 대조한다.
