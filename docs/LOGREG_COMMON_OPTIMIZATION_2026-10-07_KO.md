# LogReg common 분석 재사용 최적화

**완료:** origin/main `56d2ac628e` 기준 실제 LogReg common은 최종 3회 **16.714 / 18.749 / 17.716초**였다. CommonPreparation도 모두 20초 미만이고, 정확성·회귀 검증을 통과했다.

## 범위와 완료 조건

실제 builtin `multiLogReg`의 common analysis와 `Planner-CommonPreparation`을 동일 Docker 조건에서 모두 20초 미만으로 낮춘다. 최종 세 실행을 확인하며, 전체 컴파일 시간과 common 시간을 구분한다.

입력은 기존 검증 fixture인 192×8, 클래스 3개, `maxi=10`, `maxii=5`, PRIVATE_AGGREGATE X, local labels, ROW worker 3개다. Docker는 4 CPU/8 GiB이며 image는 `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`로 고정했다. 새 coordinator/worker JVM을 사용하고, 채택용 실행에는 JFR 및 상세 SearchSpaceMetrics를 켜지 않는다.

최초 기준은 `2faff2a2a5`다. 이후 `origin/main`의 alias reachability 수정 `241b9c491a`와 L2SVM/비용 snapshot/fingerprint 수정 `56d2ac628e`까지 통합했다. 최종 `final-v4`와 대조군 `baseline-56d`는 같은 최신 main을 기준으로 한다. 대조군의 production source는 HEAD Git blob과 전수 비교했다. 큰 W1 입력이나 다른 데이터 크기에서 20초 미만을 보장하는 결과는 아니다.

## 변경과 정확성 경계

- **CFG replay**: owner/reference별 immutable 입력을 인덱싱하고, reader replay가 실제로 읽은 node·shape·definition·privacy 문맥과 positive/negative 조회 결과를 검증한 뒤 재사용한다. Native query는 현재 resolver에 다시 질의해 출력 witness와 precision을 확인한다. Loop seed 설치 및 이전 compatibility의 live-reference 검사는 계속 수행한다.
- **물리 후보 closure**: 호출 내부 전용 inventory에 owner delta만 반영한다. Immutable snapshot 경로와 mutable owned 경로를 구분하고, physical normalization에는 현재 owner를 전체 authority index 위에 겹쳐서 조회한다.
- **Direct closure**: complete changed-owner 집합에 속한 슬롯만 비교한다. 안정된 boundary session의 빈 delta는 반복 탐색하지 않는다. 기존 grounded OR support를 유지하고, 새 증명이 정확히 포함된 경우 canonical merge와 wrapper 재생성을 생략한다.
- **Native publication**: 현재 proof 조회와 binding completeness/exactness 검사를 통과한 뒤 같은 proof·owner 객체와 같은 emission 값의 순수 출력 조립 결과를 재사용한다. 다른 emission-state wrapper의 hit에서는 기존 `rebindRealization`으로 현재 wrapper를 복원하고 immutable support clause만 공유한다. 출력 anchor, lineage, input exactness도 키에 포함한다. 저장 한도 이후에도 최근 결과를 받아들이도록 LRU를 사용한다.
- **Dependency component 일정**: owner identity와 등록 순서, 방향 있는 semantic edge 및 방향 없는 alias edge가 모두 같을 때 immutable SCC 일정을 재사용한다. Primitive ordinal pair로 키를 구성한다. 캐시 퇴거는 재계산을 유발할 뿐 후보나 탐색 범위를 줄이지 않는다.
- **불필요한 증명 구조 생성**: immutable native SCC는 실제 SCC-aware 질의가 처음 필요할 때 한 번 생성한다. WorkerPool의 보조 native 증명 resolver 전체도 미사용 시 생성하지 않는다. 생성 전 owner/node 변경은 현재 revision에서 읽고, 생성 후에는 기존 exact revision/query reset 경로를 유지한다. Origins/privacy snapshot과 독립 revision 경계를 보존한다.
- **직렬화와 조회 인덱스**: 즉시 버려지던 예비 fingerprint를 제거해 최종 fingerprint를 한 번 계산한다. Lowercase 64-hex 치환은 정규식과 동등한 선형 스캔으로 처리하고 SHA 바이트 인코딩에 HexFormat을 사용한다. 정렬 순서가 필요 없는 reference 조회는 hash map/set으로 바꾸되 충돌 bucket 내부 ordinal 순서는 유지한다.
- **공유 입력 표현**: joint Environment의 변하지 않은 값/read-source 축과 native query의 immutable candidate snapshot을 공유한다. Query별 memo·visiting 상태는 계속 분리한다.

후보 상한, row decomposition, oracle/privacy/TR-TW 완화, 비용식 또는 solver 정책 변경, runtime fallback은 추가하지 않았다.

## 검증

최신 main 통합본의 중앙 Maven package는 **86개 클래스, 731건 중 725 PASS, 기존 ignore 6건, 실패/오류 0건**이다. 빌드 전후 Java source SHA가 같았고, 산출물과 main/test source를 `final-v4`로 동결했다. 관련 변경에 대한 독립 검토도 완료했다.

주요 회귀는 negative→positive 조회, support 삭제/복원, 같은 reference의 authority 변경, equal-but-foreign owner, emission-state identity, dynamic/exact layout, current owner overlay와 cold 재구성 동치, direct delta의 net cancellation, SCC edge 추가/삭제/self-edge/cycle/alias/퇴거를 다룬다. 기존 PhysicalGenerationEnvelope 및 MaterializationProofInventory fixture의 잘못된 reflection receiver와 누락된 origins/shapes도 실제 authority를 주입하도록 고쳤다.

실제 학습은 CP/FED 전체 16개 계수, shape 8×2, runtime audit/conversion 위반, analysis fingerprint, 시간 필드를 제외한 DP checkpoint 1,224개를 기준과 비교한다. 최종 3회에서 이 비교가 모두 통과했다. 최대 계수 오차는 2.22e-16이고 runtime audit/conversion 위반은 0이다. L2SVM도 4개 checkpoint 및 모델/analysis 동등성 검증을 통과했다.

## 최종 비계측 결과

| 실행 | common 분석 | CommonPreparation | 전체 컴파일 | 학습 실행 |
|---|---:|---:|---:|---:|
| main `56d2ac628e` 대조군 |23.186초|23.205초|33.047초|3.501초|
| 최종 1 |16.714초|16.735초|25.224초|3.327초|
| 최종 2 |18.749초|18.765초|28.084초|3.619초|
| 최종 3 |17.716초|17.736초|26.385초|3.249초|
| publication/SCC 일정 캐시 제거 ablation |18.647초|18.664초|27.870초|3.152초|

최종 common 평균은 **17.726초**이며 최신 대조군의 단일 측정보다 **23.55%** 낮다. 전체 컴파일은 25.224–28.084초로 common과 구분한다. 세 최종 실행의 main/test source·class·dependency digest가 같고, 대조군과 Docker image·CPU/메모리·network·runner/dispatch·설정·fixture·34개 입력 hash가 같음을 자동 검증했다. JFR와 상세 계측은 껐다. 현재 3,595개 Java source도 동결 빌드와 일치한다.

Ablation은 실제 학습과 모든 동등성 검사를 통과했다. 캐시 제거 시 18.647초였지만 단일 ablation이며 최종 실행 간 변동 범위와 겹친다. 이 수치만으로 두 캐시 각각의 확정적인 절감 시간을 주장하지 않는다. 앞선 SCC hit 91 / miss 3 및 publication 재사용 계측과 함께 채택 근거로 삼는다.

L2SVM smoke는 common **6.852→7.047초**, 전체 컴파일 9.544→10.251초였다. 기능·모델·checkpoint는 같으며, L2SVM 성능 개선을 주장하지 않는다. 이번 목표와 최종 3회 시간 gate는 LogReg다.

대조군은 최종 classpath를 복사한 뒤 변경된 production class family를 제거하고 HEAD source로 재컴파일한 overlay다. Production source 전수 HEAD 일치를 확인했으며 독립 clean Maven 빌드로 표현하지 않는다. 새 대조군의 LogReg 1,224 / L2SVM 4개 non-time checkpoint는 기존 2faff 대조군과도 같았다.

재현 명령, 전체 중간 측정 이력, build/test 요약과 증거 SHA는 [검증 기록](experiments/logreg-common-20261007/validation.json)에 저장했다. 원자료의 `verify-final-v4.py`는 환경/fixture/artifact 동일성과 세 시간 gate를 함께 검사한다.

## 측정 근거와 한계

중간 screen에서 19.754초를 관측했지만 동일 source/class의 반복 실행은 22.242초였다. 이를 목표 달성으로 채택하지 않았다. 이후 direct 부분집합/no-derived-action 변경은 21.402초였으며, publication/SCC 재사용 결합 screen은 18.104초였다. 마지막 screen 뒤 emission-state identity 경계를 보강했으므로 최종 산출물의 세 실행을 별도로 검증했다.

첫 최종 후보 `final-v2`는 common 20.389초 / CommonPreparation 20.409초여서 목표를 통과하지 못했다. 별도 계측에서 publication hit 22,646 / miss 112,121, value가 같은 wrapper 관련 miss 27,080, 저장소 32,768개 포화를 확인했다. 이를 현재-wrapper rebind와 LRU로 수정했고, CFG context의 반복 `nodes.indexOf(read)`도 pass별 first-equal index로 대체했다. 구조적으로 같은 foreign node, 첫 위치 및 absent `-1`의 cold parity를 유지한다. 이 후속 v3도 common 20.911초로 목표를 넘겼다. Lazy SCC와 fingerprint 중복 제거 후 19.952초, 전체 native resolver 지연 생성·hash index·HexFormat 결합 후 18.519초를 관측했다. 이후 main 56d를 통합했으므로 최종 v4 세 실행과 별도로 구분한다.

별도 진단 사본의 CFG exclusive 시간은 8.472→2.834초이고 replay memo hit 9,220회를 관측했다. 이 계측 시간과 공유 호스트의 비계측 wall time을 섞어 개선율을 계산하지 않는다. 남은 common 작업과 downstream planner 시간도 존재한다.

원자료: `/grid/3/cofee-lm-sweep-mchoi-20260914/logreg-common-opt-20261007/`의 `PLAN.md`, 각 실행 `*-command.json`/`*-validation.json`, `final-v4-regression-command.json`, `final-v4-regression-summary.json`, `final-v4-build-status.json`, 동결 source/classes. 모든 실제 workload 실행은 `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e`를 사용했다.
