# 2026-09-09 세션: 입력 privacy별 incremental planning 검증

## Regional 공통 준비 재사용 + 5% 즉시 exact — 구현·3회 검증 완료

- **요청 / 성공 기준**: 준비 시간 세분화와 비용표 중복 생성·복사 제거, 분해된 encoded factor 및 전처리 결과 재사용. 첫 전역 LB의 보수적 상대 gap이 5% 이하면 반환하고 초과하면 남은 coupling을 exact로 닫는다. StepLM/L2SVM/GLM의 native JVM planning-only 각 3회로 기존 경로·새 경로·독립 Global을 비교한다.
- **원인 / 변경 계획**: 기존 Regional은 원래 고차 contribution factor를 지역별로 materialize/reduce하고 LB는 별도 encoded root를 다시 준비한다. immutable dense 입력의 내부 복사를 줄이고, encoded root reduction 한 번을 지역 조건부 solve와 첫 LB에 공유한다. 지역 밖 original decisions는 고정하되 영향받는 auxiliary component의 모든 factor를 포함한다. 원래 region 선택/feasibility/canonical 평가 계약을 유지한다.
- **전처리 / 비교 조건**: singleton compact=false, exact support reduction과 quotient는 양쪽에 적용한다. 공통 solver-core 개선은 Global에도 적용한다. 새 Regional backend는 명시적 옵션으로 끌 수 있어 같은 JAR에서 ablation한다. X PRIVATE_AGGREGATE/Y PUBLIC, LAN 모델값만 사용하며 Docker/worker/runtime workload 실행은 하지 않는다. 이는 기존 사용자 native/mixed 지시를 따른다.
- **구현 단계**: (1) core copy 및 phase timer + 회귀 테스트, (2) encoded conditional block backend 및 root 재사용, (3) 5% 분기 테스트, (4) 별도 source/JAR 빌드와 3개 workload×3개 방법×3회 소규모 검증, (5) 측정 근거 보고서.
- **잔여 문제 / 회귀 위험**: quotient value 역매핑, 변경된 boundary의 cache invalidation, auxiliary 연결 누락, 부동소수점 동률과 제한된 Regional infeasibility를 전역으로 오인하는 문제를 oracle 테스트로 검증한다. 모델 후보/legality/privacy/runtime 규칙은 변경하지 않는다. exact가 실행 중이면 hard deadline은 보장하지 않는다.
- **최종 구현**: ExactCategoricalSolver의 내부 불변 표 공유/identity remap 생략, ReducedSolver의 단계별 timer와 양방향 값 매핑, SharedRegionalPreparation의 encoded auxiliary closure/경계별 표 cache, RegionalSearchProblem의 root cache를 구현했다. compact=false의 공유 block은 root를 다시 reduce하지 않고 직접 compile한다. 기본 sharedPreparation=true이며 일반 Regional 및 legacy certificate seed와 remaining-exact에 적용되고 옵션 false로 기존 경로를 비교한다. 별도 다른 search 알고리즘 seed 연결은 유지했다. Remaining-exact 기본relativeGap=.05, 명시값 우선이며 첫gap 초과시 기존closeRemaining을 단 한 번 실행한다.
- **빌드 / 정확성**: 별도 remote build `/home/mchoi/so007-regional-shared-preparation-20260909`, evidence `/home/mchoi/so007-regional-shared-preparation-evidence-20260909`. 최종 v2 clean package 11classes/109tests 모두 통과, 실패/오류/skip0. auxiliary를 포함한 각 작은 region을 전체 조건부 oracle과 비교, cache invalidation/원래값 복원/5% routing/root 객체 재사용/defensive copy를 확인했다. git diff --check 및 Python syntax check 통과. 전체 Java suite/style/license/RAT는 실행하지 않았다.
- **실험 결과**: native JVM planning-only3workloads×3methods×3reps=27/27 정상,9/9triplets모델동일/oracle포함. 기존/공유9쌍 seedU와첫LB 동일. 최종 canonical/certificate-ready 중앙값 초 기존/공유/Global: L2SVM .816279/1.060851/.811282; StepLM3.652537/1.757613/1.454641; GLM5.900967/5.568420/9.730161. L2는공유경로29.96%느려졌고, Step은51.88%감소하지만Global보다느리며, GLM은5.64%감소및Global보다빠르다. 세표본변동은보고서/CSV에전부포함했다.
- **5% / 재사용 증거**: L2첫gap76.014%, Step380.296%여서12/12Regional실행은단1회closure후Global과rawbits같았다. GLM1.694623%여서6/6초기인증에서종료(exact0회),실제modeledregret.293521%. 공유9회모두root1회생성/LB재사용1회/fallback0. 조건부표hit L2=1,Step=140,GLM=457(각반복동일),encoded block freeze/support/quotient/rebuild시간0.
- **병목 / 제한**: Step지역준비2.119→.525초. L2기존지역compile.043초보다encoded지역compile.239초가커서공유비용을상쇄하지못했다. GLM지역준비3.389→1.536초지만solve.336→2.063초로늘어순이득이작다. root전처리를LB에서아낀시간은Regional앞단에포함했으며MBE품질변경은없다. 공통lowcopy변경의단독speedup을측정하지않았고모든workload의개선을주장하지않는다.
- **보존 / 주석 정정**: postflight newsource7515/baseline7513/input161/runtime318검증,outputs없음/active0. JAR`0a172441ab63ef9a09729efd4ce8cbd33441a38586d575c6f428a6d1628cc4ed`, sourcemanifest`ffc913d04af31f6e3afd167b15baacd783bcb0e28b5641562ee94609b6d61e33`. frozencontext의부모sourcehash주석만stale이며실제JAR/protocol sourcehash는처음부터v2와일치; frozen파일은보존하고provenance-annotation.json에정정. 중간v1은tests만실행했으며성능행없음. 이전96행보고서의최종auditPASS/CLEAR완료.
- **보고서 / 종료**: `/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_SHARED_PREPARATION_5PCT_REPORT_2026-09-09_KO.md`. 최종source는로컬작업트리및새원격격리build에있고오래된원격작업트리source는덮어쓰지않았다. 추가실험없이요청한소규모검증종료.

## 전처리 일치 네 단계 — 사용자 지시에 따라 3회 보고, 측정 종료

- **문제 / 원인**: 이전 v2에서는 compact=false인 Global이 exact domain support reduction과 objective-identical quotient를 수행했지만 Regional conditional block은 raw compile했다. 사용자가 양쪽 전처리를 동일하게 맞춘 후 같은 실험을 요청했다.
- **구현 / 결정 근거**: LocalCategoricalOptimizer.FactorizedBlockSolver를 단일 Prepared 경로로 정리해 compact=false에서도 ExactPhysicalReducedSolver.prepare를 호출하고 원래 assignment를 복원한다. compact=true는 prepareCompacted이며 resource fallback도 prepare를 사용해 raw fallback을 제거했다. 원래 legality/privacy/objective를 보존하는 exact 전처리다. compact 플래그는 singleton substitution만 결정한다.
- **같다는 의미 / 한계**: 같은 reducer와 자원 설정을 사용한다. Global은 auxiliary 포함 전체 encoded factor, Regional은 incumbent 경계를 고정한 원래 변수의 지역 factor에 각각 적용한다. 전처리 결과/그래프가 같다고 주장하지 않는다. 지역별 준비가 반복되는 비용도 Regional 전체 시간에 포함한다.
- **수정 파일 / 보존**: main LocalCategoricalOptimizer.java, test RegionalCompactTest.java의 두 파일만 frozen v2에서 변경했다. seed/region/revisit/LB/remaining-exact/Global/계측 경계/legality/runtime 규칙은 유지했다. 별도 build `/home/mchoi/so007-regional-fourway-matched-preprocessing-20260909`, evidence `/home/mchoi/so007-regional-fourway-matched-evidence-20260909`. 로컬 작업트리와 이 새 원격 build에 구현돼 있으며 이전 frozen v2와 오래된 원격 작업트리를 덮어쓰지 않았다.
- **검증**: fresh Maven clean package 표적10class/98tests failure/error/skip0. forced-domain/equivalent state 축소, singleton 유지, 원래 index와 objective 복원, 경계 변경 재풀이, conditional infeasibility expansion을 포함한다. source 두 파일 delta, 이전과21개 protocol 항목·160행 matrix·runner modules·입력161개·runtime318개(JAR 제외) 일치를 preflight에서 확인했다. 독립 architect source/test 검토 CLEAR. 전체 Java suite/style/license/RAT는 실행하지 않았다.
- **실험 조건**: native JVM planning-only, LAN625MB/s 양방향·latency0.001초, X PRIVATE_AGGREGATE/Y PUBLIC(N/A일 때 제외), ML10+P1/P2+SliceLine4. Regional은 매 반복 한 번 실행하고 Regional-ready/+최초LB/+남은exact를 관측하고 별도 Global exact 한 번과 비교했다. 공통 Compile Phase FedPlanner 시작부터 canonical modeled plan/certificate ready, selection materialization 전까지 측정한다. 전체 emitted planner와 JVM wall time은 별도다.
- **사용자 범위 변경 / 종료**: 처음에는5회·160attempts로 동결했다. 사용자가 "3회만 보고해"라고 지시했을 때 supervisor만 SIGTERM으로 안전 중단했다.1~3회96attempts가 모두 완료돼 이들만 집계한다. 이미 완료된4회차12개와 중단1개는 raw109개 전체와 함께 보존하고 성능에 관계없이 제외한다. 원래 protocol/matrix/rows를 수정하지 않고 stopped-after-three.json으로 새로운 집계 범위를 기록했다. 활성pilot0이며 이후추가실험0이다.
- **정확성 결과**: 집계96회 중90정상, paired45쌍 모두 final exact canonical bits 일치 및 모든 checkpoint의 Global oracle enclosure·gap·시간·model fingerprint 검증. P2양쪽각3회(총6회)는 transformencode metadata M의 PRIVATE_AGGREGATE placement 지원 문제로 optimizer 진입 전에 실패했다. privacy를 완화하지 않았다. 이전v2의 같은1~3회와도 정상90개 model/exact objective가 같고45개 초기Regional U와첫LB가 모두같다.
- **3회 중앙값 초**: L2SVM Regional0.703433/+LB0.870267/+exact1.107697/Global0.651357. StepLM2.718300/2.982665/3.419366/1.439382. GLM5.064623/6.090239/8.433087/10.299231. P12.315754/2.821054/4.079669/5.663933. Regional자체가느린중앙값은LM/L2SVM/StepLM/GNMF. +남은exact까지빠른중앙값은ALS/KMeans/GLM/P1이며3회기술통계의관측일뿐보편적speedup은아니다.
- **계산량 / 병목**: L2SVMRegional assignment269973→43226, StepLM2535945→45505, GLM13748593→1612747. StepLM새지역준비중앙값2.213880초, exact solve0.045941초로 준비가대부분이다. 준비시간도Regional비용이며DP계산량만으로속도개선을주장하지않는다.
- **첫LB 인증**: 실패도포함한48회중5%15회,3%12회,1%6회. 첫인증gap은L2SVM76.014%, StepLM380.296%, GLM1.695%, P1326.124%. 이값은실제오차의상한이며실제Regional modeled regret와다르다. epsilon0경로의중간관측이지별도threshold조기종료성능측정이아니다.
- **잔여 문제 / 회귀 위험**: Regional의반복전처리·높은arity factor freezing이병목일수있고전처리일치만으로해결되지않았다. 동률representative·조건부경계재사용·자원실패는oracle/trace/feasibility테스트로검출한다. runtime/placement정책은이번범위밖이다. 두방법모두선택적전처리를끄는비교도가능하다고설명했지만이배치의설정을바꾸거나새실험을추가하지않았다.
- **보존 / 보고서**: postflight source7513/base7513/input161/runtime318hash검증, v2보존807파일 및v1QC195파일보존, workloadoutput없음. JAR `58a8e5ddaa912c080bf45c1e34e52fda00424887bc124559b3d37f95d38cae76`. 보고서 `/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_FOURWAY_MATCHED_PREPROCESSING_2026-09-09_KO.md` 및 CSV에3개샘플·중앙값/평균/표준편차·실패·RSS·원본경로를제공한다. 최종보고서독립검토PASS/CLEAR완료.

## Mixed privacy 네 단계 관측 × 5회 — 완료

- **요청 / 범위**: X PRIVATE_AGGREGATE/Y PUBLIC, ML10 + P1/P2 + SliceLine4 =16입력. Regional / Regional+최초LB / Regional+LB+남은exact / Global exact의 네 상태를 각5회 관측했다. Regional은 매 반복 한 번 실행하고 세 단계에서 관측하며 독립Global을 한 번 실행했다. 총160 native JVM planning-only, LAN, 양쪽singleton compaction off. 기존 Docker/public-ignore 지침은 사용자 native/mixed 지시가 대체했다.
- **측정 문제 / 해결**: DMLTranslator의 기존 Compile Phase FedPlanner 시작 시각을 공유하는 ThreadLocal clock을 추가했다. Regional seed canonical parity 직후 REGIONAL_READY, INITIAL_BOUND, REMAINING_EXACT 및 GLOBAL_EXACT_READY를 관측한다. 모두 selection materialization 전의 modeled assignment/certificate 준비 시각이며 standalone Regional emission 완료 시간으로 부르지 않는다. Whole planner는 별도 기록했다.
- **계측 오류 / 재시도 보존**: 독립 검토에서 첫 배치 Global marker만 ExactPhysicalSelection.create 후여서 종료점이 비대칭임을 발견했다. 완료37회+중단1회 전체를 timing-QC 자료로 보존하고 정식통계에서 제외했다. Global marker만 optimizer canonical parity 직후로 이동하고 새160회를 수행했다. 결과별 선택 배제가 아니며 `/home/mchoi/so007-regional-fourway-mixed-evidence-20260909/validation/stopped-timing-qc.json`에 보존했다.
- **수정 파일 / 격리**: DMLTranslator.java, FederatedPlannerTrace.java, LocalPhysicalOptimizer.java, FederatedPlanLocalCost.java, FederatedPlanExact.java 및 FederatedPlannerTraceTimingTest.java. solver·region·후보/legality·privacy/runtime 규칙은 유지했다. 격리build `/home/mchoi/so007-regional-fourway-observation-v2-20260909`, evidence `/home/mchoi/so007-regional-fourway-mixed-v2-evidence-20260909`.
- **테스트 / 검증**: fresh Maven clean package 표적10class/97tests 모두 통과(failure/error/skip0), clock reset/cleanup/thread isolation 포함. git diff --check와 helper AST 통과. 전체Java suite/style/license/RAT는 미실행. 독립architect가 preselection 계측 경계를 CLEAR로 확인했다.
- **실험 결과 / 정확성**: 선언한160회 완료, 정상150회, paired75쌍에서 모든checkpoint의 Global oracle enclosure·수치gap·canonical bits·model fingerprint·시간단조성을 검증했다. Hybrid75회 모두 exact 완료하고 최종비용이 Global과 bit-identical. 초기 U/L/인증오차와 assignment work도5회 동일하게 재현됐다. 실패10회는 P2이며 실패도분모에 유지했다.
- **P2 실패**: transformencode의 두 번째 출력M이 PRIVATE_AGGREGATE인데 current privacy-safe placement 후보가 없어 search 전에 두방법 모두5회 실패했다. metadata output의 graph/policy 지원 문제이며 단순planner속도실패나원리적privacy불가능성으로 해석하지 않는다. privacy를 완화하지 않았다.
- **첫 LB / threshold**: 5%는 PCA/ALS/GLM/GMM/SliceLine-kdd98에서5회 모두 달성(25/80). 3%는 PCA/GLM/GMM/SliceLine-kdd98(20/80), 1%는PCA/GMM(10/80). epsilon0 실행중 관측이며 별도threshold 조기종료 실행시간은 측정하지 않았다. L2SVM/GNMF/일부SliceLine은 이미Regional 최적이지만LB가약했다. LM/LogReg는실제modeled regret가77.99%/62.98%여서계획개선도필요하다.
- **시간 / 해석**: 공통시작점 중앙값초: L2SVM Regional0.809/+LB1.026/+exact1.223/Global0.780; StepLM3.068/3.386/3.783/1.403; GLM6.944/7.955/10.240/9.472; P1 2.322/2.883/4.122/5.759. +남은exact가5회모두빠른경우는P1,5회모두느린경우는StepLM/GNMF이고나머지는승패변동이있다. 5회기술통계로보편적speedup이나statistical reliability를보장하지않는다.
- **전처리 해석 정정 / 비교 한계**: compact=false는Global의singleton substitution만비활성화하며domain reduction/quotient는유지한다. Regional의compact-off블록은그reducer를사용하지않고일반compile한다. 따라서둘다compactoff라는표현을전처리동일조건으로해석하면안된다. 이번표는현재구현경로의비교이며전처리를통제한순수지역분할ablation은아니다. 이한계를보고서주요표앞에명시했다. 후속비교는두경로의domainReduction/singletonCompaction을독립적으로맞춰야하며아직새실험은하지않았다.
- **Regional 느림의 원인 / 미해결**: 각local block마다factor축약·compile·solve를반복하고전역elimination결과를공유하지않는다. 현재Regional compact-off는일반compile이며Global compact-off는domain reduction/quotient를유지한다. LB전assignment work는L2SVM269973 vsGlobal54678, StepLM2535945 vs683215, GNMF238269 vs76820이었다. 카운터비율을wall-clock인과기여율로동일시하지않는다.
- **새 개선안은 미구현**: 동등한domain reduction 적용,Global/지역예상작업량 사전비교·준비재사용,누적작업예산을넘는지역의실행방지 또는사전Global선택을제안했다. 이번실험중에는적용하지않았으며Regional성능문제를해결했다고주장하지않는다. 작업량제한만으로항상Global이하시간·동일계획품질은보장되지않는다.
- **보존 / 종료**: postflight에서 새source7513,base7512,input161,runtime318개hash확인,이전base runtime318개및v1raw195파일·source/JAR보존확인. output없음/활성pilot0,추가trial0. JAR01bee10f2929273d25e08b9546f19cd26875897b786dc8e78d786f429e66f444. 선언한5회에서종료했다.
- **보고서**: `/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_FOURWAY_MIXED_FIVE_RUNS_2026-09-09_KO.md`. CSV·5개원본samples·mean/median/std/min/max·paired차이·RSS·실패·원본logs및sourcehash링크포함. 실제runtime은실행하지않았다.

## Compact off Regional + LB + 남은 coupling exact — 구현 및 8회 검증 완료

- **문제 / 요청**: 사용자가 양쪽 compact를 끄고 Regional + LB + 남은 것만 exact 대 처음부터 Global exact를 비교하도록 지시했다. Regional이 완료한 지역을 고정해도 전역 최적이라는 해석은 허용하지 않는다.
- **해결 정의**: 전체 encoded replica relaxation에서 아직 빠진 동일 변수의 equality를 모두 복원한다. 이 복원으로 연결되는 현재 component들을 묶어 한 번씩 exact로 풀고, 영향을 받지 않는 component 결과만 재사용한다. 원래 decision과 auxiliary 전체에 적용한다.
- **compact 경계**: 두 방법의 singleton 제거는 끈다. Global의 기존 compact=false처럼 exact domain reduction/quotient는 공통으로 유지한다. Regional local compact는 false다. 원래 변수의 replica equality를 복원하기 위한 contraction은 singleton 제거와 다르다.
- **수치 / 중단 계약**: 완료된 component만 원자적으로 반영하고, 미완료 공간은 이전 LB로 남긴다. 전체 equality 복원 및 expanded encoded / canonical 비용 bit parity가 확인된 경우에만 L=U exact 종료를 게시한다.
- **범위 / 결과**: 기존 16회 compact pilot을 보존한 새 L2SVM/LAN mixed/public 두 방법 각2회 총8 native JVM planning-only를 완료했다. epsilon=0의 동일 exact endpoint, 첫 LB의 3%·5% 여부를 검증했다. 원본8/8 정상, paired4/4 oracle 일치, 모든 checkpoint의 enclosure·단조성·보수적 gap, canonical objective bit 일치를 확인했다.
- **잔여 위험 / 감지**: 남은 coupling이 전체 모델에 가까우면 중복 계산만 추가될 수 있다. 재사용 component 수, 재풀이 범위, 초기 LB/정확한 완성 시간과 전체 planner 시간을 함께 보고한다. 수치 합산/auxiliary 복원 오류는 exhaustive 및 physical oracle과 parity 검사로 검출한다.
- **근거 경로**: /home/mchoi/so007-regional-remaining-exact-evidence-20260909.
- **수정 파일 / 구현**: `RemainingExactOptimizer.java`, `IncrementalReplicaBound.java`, `ExactPhysicalReducedSolver.java`, `RegionalSearchOptimizer.java`, `FederatedPlanLocalCost.java` 및 관련 test3개. 기존 compensated cost sum과 canonical evaluator를 재사용했고 의존성·기본 알고리즘·privacy/legality/runtime 규칙은 바꾸지 않았다. 격리 빌드는 `/home/mchoi/so007-regional-remaining-exact-20260909`다.
- **발견한 버그 / 해결**: 최초 빌드에서 미시도/미완료 `remainingClosureAttempts/Completed` 통계가 누락돼 새 테스트2개가 NPE였다. 공통 Statistics에서 0 초기화한 뒤 동일 표적92개를 다시 실행해 failure/error/skip0으로 통과했다. 테스트 변경 없이 수정했고 실패 로그·소스는 `validation/attempt-1/`에 보존했다.
- **테스트 / 검증 한계**: Maven clean package, 8개 class/92 tests 통과. 부분 component commit 이후 cancellation/resume 및 resource 실패, tie equality, singleton 유지와 encoded/canonical parity 포함. git diff --check와 Python AST 통과. 전체 Java suite·style/license/RAT는 미실행. 독립 architect가 구현 및 pilot 비교/증거 계약을 CLEAR로 검토했다.
- **관측 시간**: 전체 planning 평균 초는 혼합 Global1.4620605 / hybrid1.420169, PUBLIC1.1832445 /1.7419435다. 혼합은 반복별 승패가 바뀌고 PUBLIC은 두 번 모두 hybrid가 느렸다. Cell당2회로 통계적 speedup을 주장하지 않는다. Full Regional 평균0.356392370/0.625746299초, root reduction+첫LB0.206571990/0.200037923초, 남은 exact0.197408900/0.220199749초다. Nested timer를 중복 합산하지 않는다.
- **인증 / 재사용 결과**: 두 privacy 모두 처음부터 Regional U=Global C*=846.0188882981099 modeled ms. 첫 L=480.6543391449701, gap약76.014%여서 첫LB만으로3/5% 모두 미달. Closure 이후 L=U. 재사용97component의 work는110뿐이었다. 남은 exactAssignments54568/97162는 Global54678/97272의99.799%/99.887%여서 비싼 proof 작업을 거의 줄이지 못했다. 첫LB 추가8641/12959 및 Regional 작업은 별도다.
- **Threshold 해석**: 3/5%를 별도 실행한 time-to-threshold가 아니다. epsilon0 실행의 처음/마지막 인증을 확인했으며, 현재 closure 묶음 사이에서 더 일찍3/5%로 중단하는 정책은 미구현·미측정이다.
- **보존 / 완료**: postflight에서 source7512개·이전source7509개·이전runtime318개, privacy별input161개/runtime318개 hash와 output없음·활성pilot0을 확인했다. 새JAR `2f42f568e33ff45efba4f210b8f77534a174a7e8f0b6795a6524a817452be364`. 선언한8회에서 종료하고 이전 대규모 campaign을 재개하지 않는다.
- **보고서 / 잔여 이슈**: `/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_REMAINING_EXACT_PILOT_2026-09-09_KO.md`. 실제 재사용 대상은 LB component이며 Regional의 DP table이 아니다. 정확성 검증은 통과했으나 전체 성능 우위는 미입증이며 GLM/P2 별도 미해결 이슈는 이번 범위 밖이다. 소스/원본은 그대로 보존해 대규모 결합·수치·메모리 회귀를 targeted oracle와 work/RSS로 감지한다.

## Regional compact + 최초 LB 비교 — 구현 및 16회 검증 완료

- **문제 / 범위**: 사용자가 Regional에도 compact를 적용한 뒤 Global과 비교하도록 승인했다. L2SVM/LAN의 mixed/public 두 privacy, Global compact on/off와 Regional compact on/off + 최초 width-2 LB 한 번의 네 구성으로 한정한다. 각 구성 2회, 총16 native JVM planning-only이며 실패도 보존한다.
- **구현 계약**: 지역 밖 assignment를 고정한 factor에 exact reduction/quotient/singleton compaction을 적용하고 원래 assignment를 복원한다. 기본값 off로 기존 동작을 보존하며 실험에서 on/off를 명시한다. seed 순서·region 구성·privacy·canonical/legality 규칙은 변경하지 않는다.
- **검증 계획**: 작은 exhaustive oracle, 강제 단일 상태/상수, 경계 변경, 기본값 및 invalid option, physical canonical 계약을 확인한다. 이전 frozen source/JAR/input을 보존한 별도 빌드에서 표적 테스트를 먼저 실행한다.
- **잔여 위험**: 지역 high-arity factor의 전처리 자원 비용, 동률 선택 변화, 경계별 compact 상태 재사용 오류. Global LB는 별도 unconditioned 전체 모델에서만 계산한다.
- **완료 결과**: 16/16 native planning-only 통과, 두 privacy 모두 Regional cost=Global optimum846.0188882981099이고 초기gap76.014%로3/5% 미달이다. Full Regional local compact는 실제 적용됐고 fallback0. DP solve는 혼합0.234817→0.046054초, PUBLIC0.364760→0.074167초로 줄었으나 준비 시간이 늘어 전체 planning의 일관된 개선은 없었다.
- **테스트 / 보존**: primary71개 testcase 중70실행 통과/기존 선택형 exporter1skip, supplemental5개 통과(기존4재실행+추가1). 원본16회, source7509개 및 이전source7508개/runtime318개, 각privacyinput161개 hash와 output부재 확인. 추가trial0, activepilot0.
- **산출물**: `/home/mchoi/so007-regional-compact-evidence-20260909`, 보고서 `/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_COMPACT_FIRST_LB_PILOT_2026-09-09_KO.md`. 후속8회와 JAR/protocol이 다르므로 시간값을 섞지 않는다.

## Regional에 compact 적용 가능한가 — 코드 확인 완료, 적용 미실시

- **문제 / 증상**: 현재 Regional이 compact를 사용하지 않는 이유가 알고리즘의 제약인지 질문받았다.
- **확인 / 원인**: 기본 Regional의 FactorizedBlockSolver는 지역 밖 선택을 factor에 대입한 뒤 ExactCategoricalSolver.compile을 직접 호출한다(LocalCategoricalOptimizer:457). 단일 상태 변수 치환까지 하는 prepareCompacted는 연결돼 있지 않다. 반면 별도 RegionalSearchProblem의 조건부 solve에는 compactPreparation에 따라 prepareCompacted를 선택하는 경로가 이미 있다(:368). compact는 Global 전용 기법이 아니다.
- **해결 / 판단 근거**: 지역 밖을 고정한 정확한 조건부 모델에서 reduction/compact 후 DP, 원래 assignment 복원과 hard/canonical 검증을 수행하는 설계가 가능함을 설명한다. 밖의 변수는 현재도 factor scope에서 제외되므로 추가 제거 대상은 지역 안에 남은 singleton 등이다. 초기 seed/region 구성의 변경 없이 전처리만 비교해야 한다.
- **수정 파일 / 검증**: 세션 기록만 갱신했다. ExactPhysicalReducedSolver.prepareCompacted/compactModel/expandAssignment와 지역 solver 호출 경로를 직접 확인했다. Java·실험·빌드 변경 없음.
- **잔여 이슈 / 위험**: 지역 factor 폭과 반복 전처리 비용에 따라 순이익은 달라 아직 속도 개선을 주장하지 않는다. 경계 조건이 바뀌면 그 조건에서 만든 축약/수치 결과를 무조건 재사용하지 않아야 한다. 동률 선택이 후속 지역 경로를 바꿀 수 있으므로 local objective와 최종 canonical 비용을 검증해야 한다.

## 최초 LB 1회와 Anytime 강화 비용의 설명 구분 — 기존 결과 확인 완료

- **문제 / 원인**: Regional에는 compact가 없는데 LB 한 번만 붙여도 Global보다 비싼지 질문받았다. 최근 Anytime 1.246/1.428초는 3%까지 반복 강화한 결과여서 최초 LB 비용으로 인용하면 잘못이다.
- **해결 / 검증**: 이전 RegionalCertify paired JSON을 다시 확인했다. L2SVM mixed/public의 Regional+LB 전체는 1.082844/1.304845초, 같은 pilot compact-on Global은 1.211039/0.989488초다. 인증 추가 전체 0.135770259/0.192251569초에는 LB용 compact가 포함되고 순수 MBE는 0.054915833/0.087860629초다. 첫 인증 상대 gap은 모두 약76.014%다.
- **수정 파일 / 적용 원칙**: `docs/REGIONAL_GLOBAL_TIMING_EXPLANATION_2026-09-09_KO.md`에 1회 인증의 실제 비용과 비교 범위를 추가했다. 이전 JAR의 1회 인증 시간을 최신 compact-off Global과 직접 결합하지 않는다. 원본 증거·코드·모델·privacy 규칙은 변경하지 않았고 새 실험은 없다.
- **잔여 이슈 / 회귀 위험**: Regional과 LB 양쪽 모두 compact를 끈 구성 대 Global off의 동일 JAR 비교는 미측정이다. HOP별 bottom-up DP와 현재 baseline의 차이도 남아 있다. 단일 측정 및 다른 pilot의 시간 혼합을 피한다. 문서만 변경해 실행 회귀는 없다.

## HOP별 DP와 지역 solver 호출의 설명 혼동 — 코드 경로 확인 완료

- **문제 / 증상**: 사용자가 각 HOP의 DP와 지역별 DP가 무엇이 다른지 질문했다. 앞선 DP 3회는 지역 solver 호출 수인데 이를 HOP 처리 횟수처럼 읽을 수 있었다.
- **원인 / 확인**: 현재 초기 `selectLocalState`는 producer 순서로 이미 결정된 factor 비용/legality를 보고 상태 하나를 고르는 경로다. HOP별 상태별 최적 subplan을 저장하는 bottom-up DP라고 부를 수 없다. 이후 operator/direct-input, shared producer, materialization 블록을 조건부 exact variable elimination으로 개선한다. factorizedSolves는 이 지역 solve 호출 수다.
- **해결 / 수정 파일**: `docs/REGIONAL_GLOBAL_TIMING_EXPLANATION_2026-09-09_KO.md`에 구분과 소스 링크를 추가했다. 현재 baseline을 HOP 순서 초기 선택 + 지역 exact 개선으로 명시하며 논문/이전 Regional과의 동등성은 별도 확인 없이 주장하지 않는다.
- **검증 / 원칙**: LocalCategoricalOptimizer의 초기 순회(548), selectLocalState(671), 지역 solve(796)와 LocalPhysicalOptimizer 지역 구성(294)을 직접 읽었다. Java·후보 공간·privacy·runtime 변경이나 새 실험은 없다.
- **잔여 이슈 / 위험**: 사용자가 지칭한 HOP별 DP와 baseline 알고리즘의 대응은 아직 검증하지 않았다. 실험 결과의 baseline 명칭만으로 서로 동일시하지 않는 것이 필요하다. 문서만 변경해 실행 회귀는 없다.

## 인증 없는 PUBLIC Regional의 시간 역전 — 기존 근거 재분석 완료

- **문제 / 증상**: 사용자가 인증 없는 Regional 1.321210초가 compact off Global 1.000791초보다 긴 이유를 질문했다. 전체 planner를 순수 local DP 시간으로 오인할 수 있었다.
- **확인 / 원인 범위**: 정상 Regional seed는 0.620049517초이며 DP 준비 0.091184351초, solve 0.463994471초다. seed 밖 planner 잔여 시간은 0.701160483초이고 MBE/인증은 없다. PUBLIC Regional은 지역 block 4개 중 factorized solve 3회, blockAssignments 566030을 기록했다. Global off의 eliminationAssignments는 97272이며 off에서도 domain reduction/quotient가 유지된다. 지역별 준비/풀이 누적이 가능한 비용 요인이지만, 단일 JVM 시간 차이의 원인을 중복 계산 하나로 확정하지 않는다.
- **측정 한계**: 동일 작업 카운터의 Anytime full Regional seed는 별도 JVM에서 0.397808469초였다. JIT/호스트/trace 영향을 분리하지 않아 Regional의 보편적 열세나 카운터 비율에 비례한 시간 차이는 주장하지 않는다.
- **해결 / 수정 파일**: 설명 문서 `docs/REGIONAL_GLOBAL_TIMING_EXPLANATION_2026-09-09_KO.md`와 이 세션 기록만 추가했다. 기존 보고서·원본 결과·Java·frozen source는 변경하지 않는다.
- **검증 / 판단 근거**: 기존 8회 구조화 결과와 PUBLIC 원본 trace, DMLTranslator의 planner timer 범위, LocalPhysicalOptimizer의 seed/인증 분기, 지역 solver 카운터의 집계 경로를 확인했다. 독립 explore가 source mapping을 확인했다. 새 실험/빌드 0회, 모델·privacy·runtime 규칙 변경 없음.
- **잔여 이슈 / 회귀 위험**: 세부 residual 0.701초의 항목별 시간과 안정적인 반복 평균은 미측정이다. 문서 변경만으로 실행 회귀는 없으며, 겹친 timer의 중복 합산과 단일 관측의 인과 해석을 피한다.

## Global compact 제거 비교 — 완료

- **문제 정의**: 사용자가 일반 Regional은 compact를 사용하지 않는다고 지적하며 compact 없는 Global과 비교를 요청했다. normal Regional에는 whole-model compactRoot가 없지만 현재 Anytime LB는 이를 사용한다.
- **해결 / 적용 경계**: Global의 singleton 치환만 끄는 기본값 true 옵션 `sysds.fedplanner.exact.compact`와 `Exact-Preparation` trace를 추가했다. on/off 모두 exact domain reduction/quotient, legality, canonical objective, production resource limits를 유지한다. raw/unreduced Global 또는 모든 방법의 compact-off 비교가 아니다. 옵션은 Global 진입점에만 적용된다.
- **수정 파일**: ExactPhysicalOptimizer.java, RegionalSearchPhysicalIntegrationTest.java. `/home/mchoi/so007-global-compact-ablation-20260909`에 이전 batch/reuse frozen source를 복사해 별도 빌드했다. 기존 runtime/실험을 보존했다.
- **검증**: targeted 3개 class의 43/43 Java 테스트, failure/error/skip 0. 기본값=on, off와 canonical optimum/legality 일치, off의 encoded 변수 유지, 잘못된 옵션 거부를 확인했다. git diff --check 및 Python syntax 통과. 전체 suite/style/license/RAT는 실행하지 않았다. 독립 architect가 구현 경계를 확인했다.
- **실험 / 결과**: L2SVM/LAN mixed/public × Global on/off/normal Regional/Batch3% 총 8/8 native JVM planning-only 통과. 혼합 전체 planner 초: Global on 0.921824, off 1.226291, Regional 0.663038, Batch 1.245995. PUBLIC: on 0.871686, off 1.000791, Regional 1.321210, Batch 1.428013. 두 Global과 Regional/Batch 비용은 모두 846.0188882981099 modeled ms. Batch 인증 gap은 혼합 0.693330%, PUBLIC 2.954455%로 3%·5% 모두 달성했다.
- **원인 근거**: Global on은 혼합 encoded407→compiled230, PUBLIC422→255. off는407/422 전부 유지한다. 준비 시간 on/off는 혼합0.277/0.490초, PUBLIC0.276/0.426초. 이 값은 compact 자체만의 시간이 아니라 reduction/compile을 포함한다.
- **현재 판단**: 혼합에서 compact off Global과 Batch는 약0.020초 차이로 거의 같다. PUBLIC에서는 off Global도 더 빠르다. Cell당1회로 작은 차이의 우위나 통계적 speedup은 주장하지 않는다. Batch는 LB에서 compact를 유지하므로 이를 전처리가 동일한 비교라고 부르지 않는다. Regional은 자체 인증이 없는 baseline이며 optimum과 같다는 사실은 독립 Global로 사후 확인했다.
- **잠재 회귀 / 잔여 이슈**: compact off에서 더 큰 그래프나 resource 실패가 생길 수 있으며 실패도 유지해야 한다. 동률 assignment의 on/off 동일성은 보장하지 않는다. `exact.compact` 이름은 모든 exact 경로의 공통 스위치처럼 보일 수 있어 Global-only 범위를 보고서/trace에 명시했다. Global 기본값은 on 유지. GLM WAN_mid 등 별도 이슈는 수정/실험하지 않았다.
- **보존**: postflight에서 새/이전 source 각각7,508개, 이전 runtime318개, privacy별 input161개/runtime318개 hash 일치. workload output 없음, 활성 pilot 프로세스 없음, 추가trial0. JAR `6a66597af77c9f0c9defbaecc7a4e8b380064eba0d74ce7f2bc0c0c1652c1a45`.
- **근거 / 보고서**: `/home/mchoi/so007-global-compact-ablation-evidence-20260909/validation/ablation-results.json`, `native-tests.json`, `final-postflight.json`; `/home/mchoi/so007-anytime-incremental-20260908/docs/GLOBAL_COMPACT_ABLATION_2026-09-09_KO.md`. 이번 요청은8회에서 완료했다.

## L2SVM LB 강화의 batch·준비 재사용 — 구현·소규모 검증 완료

- **문제 정의 / 증상**: L2SVM의 제대로 된 Regional은 Global과 같은 비용 846.018888을 반환했으나 최초 width-2 LB가 480.654339로 인증 gap은 약 76.014%였다. 기존 incremental은 한 encoded 변수씩 복원하며 zero-gain과 component 반복 준비 비용이 컸다.
- **사용자 범위 / 적용 원칙**: 사용자가 묶음 강화와 준비 재사용 두 기능을 구현·확인하도록 지시했다. 최근의 소수 실험 요청에 맞춰 L2SVM/LAN의 9개 native JVM planning-only로 한정했다. Docker, worker/workload execution, privacy·placement/candidate-space 완화는 포함하지 않는다.
- **해결 요약**: connected batch(실험 최대 8 encoded 변수)를 원변수별 replica equality로 복원하고 touched component를 한 번에 푼다. 실패하면 single seed를 한 번 시도하며, clone/완료/atomic commit으로 중단 시 기존 bound를 보존한다. 기존 component의 제거 순서만 현재 representative로 투영해 compile하고 현재 scopes/cells/work를 재검증한다. 유효하지 않거나 비싼 순서는 일반 compile로 전환한다. 수치 table은 재사용하지 않는다.
- **비교 초기화**: opt-in incrementalFullSeed=true를 추가해 네 구성이 모두 normal Regional 전체를 완료한 동일 incumbent에서 출발하도록 했다. 기존 batch=1/reuse=false/fullSeed=false 기본값은 유지한다. Global의 production compact+exact 경로를 사용했다.
- **수정 파일**: IncrementalReplicaBound.java, IncrementalAnytimeOptimizer.java, LocalPhysicalOptimizer.java, FederatedPlanLocalCost.java, ExactCategoricalSolver.java, 새 ReplicaComponentPreparation.java 및 세 focused test 파일. 이전 certificate pilot 대비 source delta 9개이며 새 빌드는 `/home/mchoi/so007-anytime-batch-reuse-20260909`에 격리했다.
- **검증**: so007 Maven clean package 성공. targeted 10개 class/117 testcase 중 116 실행 통과, 실패·오류 0. 기존 선택형 TSV exporter 1개만 skip. Plateau batch 개선, single fallback, cancellation rollback, 현재 domain/scope/수치 검증, full-seed 동일성을 확인했다. git diff --check와 Python syntax/CONFIG contract 확인 통과. 전체 style/license/RAT와 전체 Java suite는 실행하지 않았다. 독립 architect read-only review에서 blocking correctness 결함 없음.
- **실험 결과**: 9/9 JVM 정상, 7/7 요청 인증 달성, 7/7 독립 Global oracle 포함 및 모든 checkpoint 단조성 확인. Mixed/3% 전체 planner 초: Global 0.724438, Single 2.000367, Reuse 1.308149, Batch 1.248478, BatchReuse 1.805569. 강화 횟수 39/31/6/6. Public BatchReuse는 3% 1.685787초, 5% 1.357102초, 해당 Global 0.795816초였다. Mixed BatchReuse 5%는 1.293252초. 모든 seed/final U는 oracle과 같고, 모든 Anytime의 fullyRestored=0/wholeClosureCompleted=0이었다.
- **현재 판단 / 잔여 이슈**: 이번 mixed cell에서 Batch만 켠 실행이 네 Anytime 중 가장 빨랐지만 Global보다 느리다. 통합법은 재사용 성공 1회/일반 compile 전환 5회로 준비가 중복됐고, reuse-only도 준비를 줄이는 대신 exact work를 늘렸다. 표의 zero-gain은 엄격한 0이며 batch 2·3단계의 약 1e-13 증가는 의미 있는 개선으로 해석하지 않는다. Cell당 1회이며 3%/5% mixed 통합법의 동일 6단계 실행 사이 timing 변동도 있어 통계적 speedup을 주장하지 않는다.
- **잠재 회귀 위험 / 감지**: 이전 제거 순서가 새 component에서 비싸거나 동률 assignment를 바꿀 수 있다. Batch가 큰 component를 결합해 준비 비용이 커질 수 있으며 hard deadline은 없다. 새 oracle/resource/cancellation tests와 hit/fallback/work/시간 trace로 감지한다. 신규 옵션의 기본값은 바꾸지 않는다. GLM WAN_mid lowering 문제는 별도 미해결이다.
- **근거**: `/home/mchoi/so007-anytime-batch-reuse-evidence-20260909/validation/pilot-results.json`, `native-tests.json`, `final-code-review.md`, `final-postflight.json`. 새 JAR SHA-256 `4629b63aafb672cf1ac84e09e0c2314f9679ce0dfab305d4ee5f6340c5afdca9`. Postflight에서 새 source 7,508개, 이전 source 7,506개, runtime 318개 및 privacy별 input 161개 hash 일치, output 없음, 활성 pilot 프로세스 없음, 추가 trial 0을 확인했다.
- **보고서**: `/home/mchoi/so007-anytime-incremental-20260908/docs/L2SVM_BATCH_REUSE_PILOT_2026-09-09_KO.md`. 로컬·so007의 동일 경로에 보존하며 새 빌드 docs에도 복사한다. 이번 요청은 9회 검증에서 완료하며 과거 대규모 campaign을 재개하지 않는다.

## Full Regional + 최초 인증 소규모 재검증 — 완료

- **문제 정의**: V5 incremental의 ordered seed는 `regionalSeed(..., false)`로 비용 개선용 지역 블록과 materialization 블록을 생략한다. 이를 원래 Regional 완료 후 인증한 결과로 해석할 수 없다. 기존 `regionNanos`는 incremental의 추가 보정이며 원래 Regional DP 시간이 아니다.
- **승인 범위 / 판단 근거**: 사용자가 제대로 된 Regional로 진행하되 실험은 몇 개로 제한하도록 지시했다. GLM/L2SVM × LAN × mixed/public × Global/RegionalCertify의 총 8개 native JVM planning-only로 한정한다. 목표별 중복 실행 없이 같은 최종 인증으로 3%와 5%를 평가한다.
- **해결 방법**: 기존 `mode=certify, algorithm=legacy`의 full Regional seed와 단일 MBE 경로를 재사용한다. 선택적 `certifyCompact=true`가 전체 unconditioned encoded model에 Global과 같은 exact compaction을 먼저 적용한다. 원래 계획/assignment는 그대로 보존한다. LocalCategoricalOptimizer 전체와 그 하위 block 준비/solver 시간, Regional seed 전체, compact 준비, MBE, 인증 전체를 분리 기록한다.
- **수정 파일**: `LocalCategoricalOptimizer.java`, `LocalPhysicalOptimizer.java`, `CertifiedRegionalOptimizer.java`, CONFIG trace의 `FederatedPlanLocalCost.java`와 focused regression tests 2개 파일. 기존 V5 실행 source/runtime은 보존하고 `/home/mchoi/so007-regional-certify-20260909`에 격리 빌드했다.
- **검증 결과**: 8/8 planning-only JVM 및 4/4 Global oracle 비교 통과. 인증 전후 Regional assignment/canonical cost 일치, 원래 지역 블록 실행, 최초 BOUND 1회/추가 REGION 0회, resource 실패 시 feasible plan 및 유효 bound 보존, trace timer 포함 관계, 모든 runtime execution 시간 0을 확인했다. 독립 verifier의 제한된 정확성/계측 검토도 통과했다.
- **관측 결과**: 혼합/public GLM의 인증 gap은 각각 1.6035%/1.3061%로 최초 LB에서 3%·5%를 달성했다. Regional 전체는 6.160930/12.693431초, 인증 추가 전체는 0.661302/0.684479초였다. 전체 planner는 8.733639/15.412081초로 Global 6.449812/5.643602초보다 느렸다. L2SVM은 두 privacy 모두 U=C*=846.018888이나 최초 LB가 480.654339여서 gap 76.014%, 두 목표 모두 미달이다. 이 값은 실제 regret가 아니다.
- **빌드 검증**: Maven clean package 성공, 8개 focused class의 94개 testcase 중 93개 실행 통과/실패 0/오류 0. 기존 선택형 TSV exporter 1개는 출력 경로 미지정으로 skip됐다. 최초 wrapper의 skip=0 assertion을 실제 skipped testcase 확인 후 해당 exporter만 허용하도록 수정했다. Maven 실패나 새 correctness test skip이 아니다. Checkstyle/spotless/license/RAT 전체 검사는 수행하지 않았다.
- **보존 검증**: postflight 2026-09-09T03:20:30.832526+00:00에 각 privacy input 161개/runtime 318개, 새 source 7,506개, 기존 source 7,506개/runtime 318개 hash 일치. 새 JAR `ffaee7aebb04fb2fa0afbb1634ce792df77cd2148b90c32bb2bf89b41c70811e`. Pilot 활성 프로세스 0, 추가 trial 0.
- **보고서 / 근거**: `/home/mchoi/so007-anytime-incremental-20260908/docs/REGIONAL_CERTIFY_PILOT_2026-09-09_KO.md`, `/home/mchoi/so007-regional-certify-evidence-20260909/validation/pilot-results.json`, `final-postflight.json`. 사용자 요청대로 이번 8회로 종료하며 이전 대규모 campaign을 재개하지 않는다.
- **잔여 이슈**: GLM WAN_mid의 occurrence identity 손실에 의한 lowering 오류는 이 LAN pilot과 별개로 미해결이다. 후보/legality/runtime 규칙을 바꾸지 않는다. 원래 Regional의 기본 seedRevisitPasses=0을 유지하므로 optional 추가 재방문은 수행하지 않는다.
- **잠재 회귀 위험 / 감지**: compaction 과정의 상수 비용/auxiliary 처리 오류는 독립 exact oracle과 기존 compaction tests로 검출한다. nested timer는 서로 합쳐 전체 시간으로 중복 계상하지 않는다. 기존 V5 원본 결과와 runtime JAR는 보존한다.

## 별도 privacy 조건 실험 — 완료

- **상태**: 512/512 실행과 두 campaign 원본/oracle 감사 완료. Supervisor 완료 시각은 2026-09-09 00:05:07 UTC, 추가 보존 검증은 00:08:20 UTC다. 중간 보고서는 347/512 시점의 고정 snapshot으로 보존한다.
- **문제 정의 / 조건**: 기존 V5는 실제 입력 metadata가 모두 PRIVATE_AGGREGATE였다. 새로운 두 privacy 조건은 원래 모델과 다른 문제이므로 같은 조건의 Global oracle로 다시 검증해야 한다.
- **적용 지침 / 판단 근거**: 이번 명시적 사용자 요청이 과거 AGENTS의 public-case ignore 지침을 대체하며, 앞선 사용자 요청에 따라 so007 native JVM planning-only를 유지한다. Docker 및 workload/worker 실행은 하지 않는다. Privacy/placement legality나 runtime 규칙은 변경하지 않는다.
- **해결 방법**: 기존 161개 작은 입력·DML·config 파일을 별도 디렉터리에 복사하고 metadata의 privacy 필드만 변경한다. 데이터 값은 읽거나 복사하지 않는다. 원본 DML·shape·nnz·seed·비용 환경과 V5 JAR 및 planner 설정을 유지한다. X-only 입력은 Y 비해당으로 구분한다. SliceLine의 Y는 error 입력 `e`이다.
- **비교 범위**: 16개 입력 × 4개 modeled network × 2개 목표(5%, 3%) × Global/Anytime × 2개 privacy 조건, 조건당 paired repetition 1회. 두 privacy campaign은 순차 실행하며 목표 미달 및 실패를 모두 보존한다.
- **수정 파일**: 새 evidence `/home/mchoi/so007-anytime-privacy-evidence-20260909/native/prepare_privacy.py`와 실험 protocol/context/input metadata. Java 소스는 변경하지 않는다.
- **검증 방법 / 현재 상태**: 각 raw audit가 256/256 attempts, 479/479 external assets, 오류 0으로 통과했다. Mixed 118 pairs와 public 126 pairs의 model/oracle enclosure를 검증했다. Anytime은 mixed 114개 인증/4개 미달/10개 실패, public 122개 인증/4개 미달/2개 실패다. Python harness 45개와 report/diagnostic helper 6개 테스트가 통과했다. Java는 변경·재빌드하지 않았다.
- **잔여 이슈**: mixed P2는 여전히 실패하며 PUBLIC 본실험 16 JVM은 모두 통과했다. GLM WAN_mid는 두 privacy에서 동일하게 실패하고 LAN/WAN_light Anytime은 목표 미달이다. 로그가 X/Y privacy 값을 직접 출력하지 않으므로 해당 값은 고정 staged metadata와 production source binding으로 검증한다.
- **잠재 회귀 위험 / 감지**: 잘못된 privacy fixture는 다른 모델의 결과를 요청한 조건으로 오인하게 할 수 있다. 실제 staged metadata와 production source binding, input/context hash 및 paired model fingerprint로 검출한다. 원본 frozen V5 결과는 변경하지 않는다.

### 중간 산출물과 재개 경로

- 중간 보고서: `/home/mchoi/so007-anytime-incremental-20260908/docs/ANYTIME_PRIVACY_PROGRESS_2026-09-09_KO.md` — 로컬과 so007의 동일 경로, SHA-256 `e82768a0b185ce92b26e6f90efd0002796b3d07e8fe1ca470858c3d6a36ee681`.
- Supervisor PID: `3239256`; 최종 상태 `complete`. 보고서 작성 중 계산을 중단하거나 재시작하지 않았다.
- 진행 확인: `ssh -o BatchMode=yes mchoi@130.149.237.17 python3 /home/mchoi/so007-anytime-privacy-evidence-20260909/validation/progress.py`.
- 원본 감사: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/main-mixed-audit.json`.
- 최종 보고서: `/home/mchoi/so007-anytime-incremental-20260908/docs/ANYTIME_PRIVACY_COMPARISON_2026-09-09_KO.md`.
- 추가 보존 검증: `/home/mchoi/so007-anytime-privacy-evidence-20260909/validation/final-postflight-v1.json`. 원본 161개 파일 및 JAR hash 일치, 정상 488 JVM의 runtime_executed=false / execution_seconds=0 확인.
- Frozen analyzer와 runner는 변경하지 않았다. 같은 조건의 독립 Global을 사용해 모든 목표를 검증했으며 실패/미달을 전체 분모에 남겼다.

## GLM mixed의 3%·5% 목표 미달 — 원인 분해 완료, 개선 미실시

- **증상 / 환경**: LAN 및 WAN_light의 3%·5% 총 4개 Anytime trial이 TIME_BUDGET으로 종료했다.
- **원인 분석**: LAN 5%에서 `L=8918.487633402097`, `C*=8943.130143933382`, `U=58481.94416834239`. LB slack은 C* 대비 약 0.276%지만 actual modeled regret는 약 553.931%다. 초기 U와 최종 U가 같으며 제한된 두 Regional solve의 개선량이 모두 0이었다. U를 고정한 LB 강화만으로 3%·5% 인증이 불가능하다. 구체적으로 어떤 경계 결정을 더 풀어야 하는지는 아직 증명하지 않았다.
- **판단 근거**: `U-L=(U-C*)+(C*-L)`로 사후 분해했다. C*는 비교용 독립 Global 결과이며 온라인 scheduler 입력으로 사용하지 않았다.
- **해결 / 수정 파일**: 실험은 원래 V5 설정으로 유지했다. 새 `validation/diagnose_privacy_results.py`와 테스트가 오차 분해 및 원본 예외를 기록하며 `mixed-posthoc-diagnostics.json`에 보존한다. Java 수정 없음.
- **검증 / 재현 증거**: `/home/mchoi/so007-anytime-privacy-evidence-20260909/reviews/glm-mixed-diagnosis.md`; trial `privacy-mixed-v1_0113_glm_lan_AnytimeIncremental` 및 대응 `_0112_..._Global`. 진단 helper 테스트 3개 통과.
- **잔여 이슈 / 회귀 위험**: LB 진단으로 고른 작은 region이 U 개선에 적합하다는 보장은 없다. 이 사후 원인 분석을 새로운 region 정책의 성능 증거로 오인하지 않는다.

## GLM WAN_mid의 lowering 오류와 P2 privacy placement — 기록 완료, 미해결

- **증상**: mixed GLM/WAN_mid에서 Global·Anytime × 두 목표의 4 JVM이 `LopsException -- fed_refed lowering found ambiguous selected consumer hop=568 for local hop=567 matches=2`로 실패했다. P2는 모든 mixed 조건의 16 JVM에서 `FunOut M:M (privacy=PRIVATE_AGGREGATE)`의 privacy-safe placement를 찾지 못했다.
- **원인 범위**: GLM은 계획 선택 이후 lowering에서 consumer 매칭이 모호한 공통 문제다. P2는 현재 planner/encoding의 placement 실패다. 모든 가능한 실행의 불가능성을 증명한 결과는 아니다.
- **처리 / 판단 근거**: legality를 우회하지 않고 실패로 보존한다. GLM은 exit code 0이어도 execution footer가 없어 runner가 거절했다. Frozen analysis의 `other` 분류를 바꾸지 않고 사후 진단에 실제 예외를 기록한다.
- **검증**: 20개 실패 JVM의 원본 hash와 실제 exception line을 사후 JSON에 보존했다. 정상 처리 로그의 DML 문자열 `GLM Input Error`를 예외로 오인하지 않도록 실제 exception line만 선택한다.
- **잔여 이슈**: Public GLM WAN_mid도 같은 consumer 모호성 오류로 4 JVM이 실패했다. Public P2는 본실험 16 JVM이 모두 통과했다. 이 campaign 중 수정 또는 성공 행으로의 대체는 하지 않았다.
- **잠재 회귀 위험 / 감지**: optimizer trace만으로 계획 생성 성공을 선언하면 오류가 숨겨진다. 최종 receipt, runtime-executed guard, execution footer, 원본 audit를 함께 확인한다.

## 최종 성능 판단 — 검증 완료

- Mixed는 5%/3% 각각 57/64 인증, planner 중앙값 Global/Anytime은 1.282/1.346초와 1.324/1.463초다. Public은 각각 61/64 인증, 1.411/1.878초와 1.480/1.960초다.
- 실제 incremental 강화 이후 인증은 mixed 88개/public 105개, 그중 Global보다 planner가 빠른 경우는 28개/9개다. 초기 bound 인증은 각각 26개/17개이며 증분 성과로 세지 않는다.
- L2SVM은 두 privacy의 16개 pair 모두 인증했지만 Anytime이 더 느렸다. Public P1과 SliceLine-adult는 이번 중앙값에서 Anytime이 더 짧다. Cell당 1회이므로 통계적 speedup이나 privacy만의 timing 인과 효과를 주장하지 않는다.
- Public GLM LAN/WAN_light는 U를 줄였어도 실제 modeled regret가 약 1594%/1529%였다. LB slack은 약 0.276%/0.226%로, 같은 U의 LB 강화만으로 3%·5% 인증을 달성할 수 없다. 초기 incumbent와 primal region 선택 개선은 후속 구현 과제다.
- 최종 보고서, 전체 비교 JSON, 오차 분해, 시간 분해, 원본 감사와 snapshot을 로컬/so007 동일 절대 경로에 보존한다. 보고서 표의 열 수, 절대 경로 존재, 236인증+8미달+12실패=256 Anytime 분모 및 server-source hash를 검증했다.

## so007 SSH 접속 경로 — 해결

- **증상 / 원인**: `dams-so007` 별칭은 호스트 키 검증에 실패했다. 기존 SSH 설정에 so007 별칭이 없었다.
- **해결 / 검증**: 알려진 키가 있는 `mchoi@130.149.237.17`로 접속하여 실제 hostname `dams-so007` 및 기존 V5 JAR SHA-256 일치를 확인했다. 호스트 키 검사를 끄거나 기존 키를 교체하지 않았다.
- **잔여 이슈 / 위험**: 별칭 접속 설정은 변경하지 않았다. 이 실험에는 검증된 IP 경로를 사용한다.

<!-- Existing main session records retained during publication merge. -->

# Session issues — 2026-09-09

## StepLM mixed privacy planning ablation — completed (planning only)

- **문제 정의**: X=PRIVATE_AGGREGATE, Y=PUBLIC만으로 과거의 planner별 physical-plan
  차이가 복원되는지 확인. 기존 보호 데이터를 덮어쓰거나 모든 privacy를 풀지 않음.
- **방법/환경**: c8f13e3654 repaired source의 고정 JAR, StepLM WAN-Mid/w3, 4 planners,
  2 privacy 설정. 세 Y shard sidecar의 privacy 필드만 복사본에서 public으로 변경해
  readonly Docker file overlay. X sidecar와 모든 데이터 값은 불변.
  network none, compile only, skip worker control. 실제 source privacy audit 검증.
- **결과/검증**: 8/8 compile 성공; 오류 부재/complete trace/audit/execution=0 검사.
  matrix singleton 163→149; 16 occurrence domain 확장. FedFirst/AggLocal/Global/
  Regional 선택 변화는 각각 3/3/2/2개. FedFirst↔AggLocal 차이는 0→5개.
  FedFirst는 반복 direct-solve RHS의 local Y 공급, AggLocal은 residual 수집 후
  CP 제곱/합계를 선택. Global/Regional은 초기 y 통계만 변경.
- **과거 대조**: authenticated historical observer의 사라진 Global/Regional 48개
  상태 중 12개가 다시 feasible. 실제 선택이 12개 복원된 것은 아님.
- **산출물/재현**: `/home/mchoi/g014-steplm-y-public-planning-20260909/REPORT.md`,
  `run_ablation.py`, `ANALYSIS.json`, `summary.json`, `diffs/*`.
- **수정 파일**: production source/DML/기존 sidecar 수정 없음. 새 진단 artifact와
  본 session 문서만 작성.
- **잔여 검증**: runtime 미실행. 반복 호출의 materialization 재사용 및 실제 RPC/
  transfer 횟수·성능 차이는 추론 가능성이지만 측정 사실이 아님. 과거 전체 계획
  또는 큰 runtime 격차가 복원됐다고 주장하지 않음.
- **위험/감지**: future runtime은 workers에도 같은 Y-public 시나리오를 별도 배포하고
  privacy/provenance 일치를 검사해야 함. 현재 metadata overlay만으로 worker runtime의
  privacy를 바꾼 것이 아님. 기존 both-PA 결과에 섞지 않음.
- **판단 근거**: user-authorized synthetic metadata ablation, shared domain/selected
  candidate/emission evidence로 비교; privacy/code guard를 임의로 완화하지 않음.

## FedFirst producer-first policy restoration — implemented and verified (bounded scope)

- **Problem**: current first-feasible uses MRV decision ordering; its state rank does not
  compare reachable federated input counts across equally ranked output states. StepLM
  Y-public therefore picks FED/LOUT with collected Y although remote-Y FED/LOUT is legal.
- **Plan before edits**: add failing selector regressions first; retain the frozen domains,
  privacy facts, candidate reachability, equality and release constraints unchanged.
  FEDERATED_FIRST alone will visit producer groups in deterministic dependency postorder
  computed once from shared facts, rather than MRV. Backedges are ordering ties only,
  never removed feasibility constraints. Within equal FED/FOUT rank, maximize reachable
  PRESENT input count (the existing final candidate-row policy) before movement hints.
  This retains remote input supply where possible without adding a cost optimizer.
- **Scope**: policy selector + read-only indexed candidate query + regressions. AggLocal
  MOVEMENT_FIRST and Global/Regional algorithms, candidate builder, privacy and runtime
  untouched. No domain exclusion, explicit binding variable, dependency, or runtime fallback.
- **Verification plan**: red/green targeted tests; broader selector/candidate/privacy tests;
  compile/package; isolated Docker planning-only paired StepLM both-protected/Y-public
  with four planners, same data/config/old JAR control, source-sidecar hashes unchanged.
  Compare domains, candidate inputs and emitted runtime instructions; no runtime-speed claim.
- **User override of old repo instructions**: current explicit FedFirst + Y-public request
  takes precedence over historical DP-first/public-test-ignore instructions. Docker-only
  benchmark and protected X requirements remain in force.
- **Risks**: producer order can need more backtracking than MRV; cyclic relationships have
  no strict topological order. No global FED/FOUT maximum or linear worst-case guarantee.
  Measure planning wall time and inspect regressions, do not hide trade-offs.

### 완료 증거 및 잔여 범위

- 구현: `PolicyFirstFeasiblePlacementSelector.java`, `CandidateSelections.java`.
  producer dependency index를 selector invocation당 한 번만 만들고 component에 재사용.
  동순위의 reachable PRESENT-input hint와 equality-group trial overlay를 추가.
- 테스트: selector source-first/cycle 회귀와 실제 StepLM candidate-fact hint 테스트.
  최종 53/53 PASS, package 및 diff check PASS. AggLocal/Exact/privacy 회귀 포함.
- 실제 Docker planning-only: StepLM WAN-Mid w3 × 4 planners × 2 privacy = 8/8 PASS.
  8개 모두 기존 domain/legality 동일. 다른 3개 플래너의 6개 계획 동일.
  FedFirst Y-public LOCAL 2→0, FED 74/FOUT 69 유지; m_lm 및 m_lmCG 입력 COL,ROW 유지.
  both-PA는 FunctionOp authority 1개만 변경, emitted program 동일.
- 산출물: `/home/mchoi/g014-fedfirst-producer-first-20260909/REPORT.md`,
  `COMPARISON.json`, `SOURCE_MANIFEST.json`, `tests-final/`, `summary.json`.
- 기존 캠페인/데이터/JAR 덮어쓰기 없음. runtime 미실행. compile speed는 단발·진단
  및 테스트 자원 공유 때문에 비교 주장하지 않음. 전역 최적/엄밀한 single-pass 미보장.
- 잔여 위험: high-fanout hint lookup overhead, cycle/SAME_PLACEMENT 특수 alias의 추가
  회귀 커버리지. 전체 2-worker unit fixture의 초기 consistency 실패는 baseline 미확인;
  단독 fixture 문제인지 기존 분석 문제인지 단정하지 않는다. 더 좁은 실제 facts unit
  + 원래 3-worker Docker full planning으로 본 변경을 검증했다.

## AggLocal on Apache-like FedFirst traversal (2026-09-09, in progress)
- User explicitly requests Heuristic implementation following Apache FedAll inheritance and L2SVM planning with X PRIVATE_AGGREGATE/Y PUBLIC. This overrides older DP-first and ignore-public test instructions for this bounded task.
- Plan: retain shared domains/privacy and existing analysis-owned aggregate-vector demotions/local continuation/frontier policy; replace production single-pass AggLocal movement-first/MRV selector with FedFirst FEDERATED_FIRST producer ordering. No oracle, runtime, DML or source metadata changes.
- Regression first: assert production comparator, certificate and policy identity; preserve local Xd HOP/LOP and privacy guards. Compare frozen old backend with new backend for FedFirst/AggLocal and both privacy variants at WAN-Mid w3 in isolated Docker compile-only mode.
- Apache reference: FederatedPlannerFedHeuristic extends FedAll; overrides getFederatedOut for ROW column-vector / COL row-vector AggBinaryOp outputs. COFEE keeps its existing whole-program policy extension and legal reentry; not claiming byte-for-byte Apache implementation or global maximality.
- Risk: different variable/state order may alter layout/relocations or reveal policy-domain inconsistency. Check exact candidate receipts, privacy audit, physical instructions, and no runtime execution before reporting.

### AggLocal Apache-style change: completed verification
- Changed production `FederatedPlannerFedHeuristicSinglePass` to reuse default FEDERATED_FIRST producer-first selector; retained existing adapter demotions/local-prefix/reentry policy. Legacy exhaustive config unchanged.
- Tests: 56/56 PASS, 0 failures/errors/skips. Regression archive `/home/mchoi/g014-agglocal-apache-l2svm-20260909/tests-final/`. New assertions initially failed old comparator as intended; one intermediate test incorrectly expected a new certificate suffix, corrected to existing FedFirst certificate, no production certificate change.
- Maven package and `git diff --check` PASS. Independent code review APPROVE with no findings. Built JAR f59fb7348bbdcfe4edb4dc8df568a76db60ae711bb476592d28108dbd3c635d7 differs from frozen FedFirst baseline05acf by exactly the HeuristicSinglePass class.
- Planning: L2SVM WAN-Mid w3, 4 planners x2 privacy variants x2 backends =16/16 PASS, compile-only Docker/no worker startup/no runtime. X stays PRIVATE_AGGREGATE; Y-public uses read-only diagnostic metadata overlays. Original sidecars unchanged.
- Only old/new AggLocal/Y-public cell changes: selectedFED28→37/FOUT16→28; static FED instructions19→29; LOCAL materializations2→1; relocations3→3. Other7 paired cells unchanged. Canonical base candidate domains identical in all8 fixed-privacy old/new pairs.
- New backend privacy comparison: static FED counts (Y protected→PUBLIC) FedFirst36→38, AggLocal30→29, Global28→8, Regional28→8. This is a real planning change, NOT a demonstrated performance improvement. Restoring FED-first can select more remote work than movement-first.
- Residual/risk: no runtime measurement, and PUBLIC does not force Y or all downstream loops to CP. Do not infer remote operation count at execution or latency from static instruction count. Inner loop104–114; outer post-inner120/124 have different frequency scopes. Existing COFEE pathwise heuristic remains an extension of Apache's local HOP rule; no claim of exact equivalence or linear worst-case traversal.
- Full report/diff/metadata/JAR manifests: `/home/mchoi/g014-agglocal-apache-l2svm-20260909/REPORT.md`, `AGGLOCAL_SOURCE_CHANGES.diff`, `COMPARISON.json`. No commit/push or campaign artifact overwrite.

## ML10 shape/privacy pilot (50Kx128, w3) — 2026-09-09T01:55Z

- Isolated artifacts: `/home/mchoi/g014-ml10-shape-privacy-20260909`.
- No planner/backend source change; frozen `f59fb7348bbdcfe4edb4dc8df568a76db60ae711bb476592d28108dbd3c635d7` JAR.
- Correct-shape metadata planning L2SVM LAN A/B/C x4 =12/12 pass; source audit confirms X PRIVATE_AGGREGATE50K128 and Y PUBLIC local/PUBLIC FED/PA FED.
- Static FED counts A28/23/4/4, B38/29/8/8, C36/30/29/29 (FedFirst/AggLocal/Global/Regional). Inner-loop counts A9/9/0/0, B9/8/0/0, C9/9/9/9. No runtime claim.
- Real synthetic data generated and persisted readback verified (~51MiB); Y public/private views reuse same bytes. Main controlled data differs from old2100-column onehot P2P2D.
- Harness bug: missing central ADULT_features.data.mtd silently rendered100000x1 despite shard metadata50K128. Corrected and invalid attempts separated; must validate generated DML ranges, not requested CLI dimensions alone.
- Subsequent real-data bind-mount setup hit readonly-parent missing mountpoints. Treat as harness failure, not planner/runtime failure; fix and canary before restarting grid.
- Existing so002–004/so007 runtime kept intact. Runtime launch remains resource/data/preflight-gated. Do not reinterpret compile-only results as timings.

## AggLocal local-continuation-first (in progress)
- Problem: analysis traces certified FED reentry before checking local vector continuation. With public worker Y, L2SVM demoted vectors can be re-uploaded despite legal local vector processing.
- Plan: regression first; local scalar/vector supply preference with shared candidate/privacy/materialization certification; preserve protected large-matrix frontier. See /home/mchoi/g014-agglocal-local-continuation-20260909/IMPLEMENTATION_PLAN.md.
- Scope: AggLocal policy facts/preferences only, no Global/Regional/FedFirst policy, runtime, data or frozen campaign change. Latest explicit user request overrides historical public-test-ignore/DP-first guidance.
- Risks: shared formal/CFG obligations and incomplete candidate tuple support; test negative protected sibling and existing loop/function safeguards. Runtime performance remains unmeasured.

## AggLocal local-continuation-first completed (2026-09-09)
- Fixed local-vector preference before FED reentry; exact native LOUT proof and retention at protected downstream matrix markers. No base-domain/privacy/runtime changes.
- Real PUBLIC-worker-Y L2SVM: static FED29->14; inner loop104-114 FED8->0; explicit relocation3->0. Protected Y case unchanged30/9. CP prefetch still provides Y; do not equate FED counts to RPC counts or speedup.
- 71/71 targeted tests, package, diff-check PASS. Docker compile-only25/25 (L2SVM12 + ML9 smoke13), canonical domains and privacy unchanged25/25. Other3selector L2SVM plans unchanged9/9.
- Final JAR d0f7b232bde2a1f063da830056f59697184a33128aea35b9a3d3ff9bc3a48d9c. No runtime deployment/measurement; previous campaign untouched.
- Full report: /home/mchoi/g014-agglocal-local-continuation-20260909/REPORT.md. Final comparisons FINAL_COMPARISON.json and SMOKE_COMPARISON.json; initial COMPARISON.json is failed preliminary design evidence, not the final result.

## ML10 size sweep (frozen backend d0f7b232, 2026-09-09)
- Evidence root: `/home/mchoi/g014-ml10-size-sweep-20260909`.
- Six shapes, ten workloads, four planners, two network cost profiles, w3: 480/480 distinct compile-only cells passed; 480 raw bundles checksum verified. No production planner modifications in this task.
- Provisional runtime-screen baseline 50K×512; not a certified maximum-speedup dataset. L2SVM 200K/LAN produces identical AggLocal/Global/Regional placement and printed-program fingerprints at all three tested d values; increasing n does not necessarily widen policy separation.
- Diagnostic common scorer reproduces one DP objective bit-for-bit, but FedFirst normalized receipt ambiguously maps input-authority alternatives [2,3] at check_min; no policy score invented and no secondary optimization used. Exact/AggLocal scoring not certified.
- Separate so006 single-host pilot setup failed initially on missing /usr/bin/time, then stale Hadoop .mtd CRC after privacy metadata update. These are new pilot harness/data-preparation issues, not evidence of planner failures. Preserve failures and regenerate metadata CRC without weakening privacy; no historical campaign data modified. Root stopped only the failed pilot coordinator after Netty threads persisted; original so002/3/4/7 campaign untouched.
- Follow-up: pilot harness/CRC issues resolved without production backend changes; 12/12 L2SVM LAN/w3 runtime cells passed for50K×{128,512,2100}. Cross-planner model maxabs2.22e-15; pilot containers removed. Runtime evidence refines initial recommendation to50K×128 (not universal ML10 optimum); see task RECOMMENDATION.md/RUNTIME_SUMMARY.json.

## User-selected ML10 50K×128 campaign started (2026-09-09)
- Control: `/home/mchoi/g014-ml10-n50000-d128-20260909`; stage: `/home/mchoi/cofee-ml10-n50000-d128-20260909/stage`.
- X private-aggregate 50K×128, aligned worker Y public 50K×1; binary for L2SVM/LogReg, continuous for LM/StepLM/GLM (GLM thresholds internally). Authentic data27 artifacts / shard reconstruction maxabs0 / all CRCs verified.
- User's scope preserved: ten ML workloads, four selectors, four network profiles, w1/3/5 =>480 runtime cells. so007 coordinator, prefix workers so002–so006; so001 proxy and so008/9 reserved. No production edits, no P1/P2/SliceLine dataset changes.
- Driver thin adapter6/6 tests;120-block supervisor separates planning gates from trace-free runtime, retains lifecycle network/output/cross-planner parity; mode/block-separated archive destinations avoid overwrite. tmux `cofee-ml10-50k128-20260909`.
- First Docker mount failed because excluded data/results/tmp/references directories were absent below read-only parent; added only empty mountpoints, preserved failure, then PCA LAN/w1 planning4/4 and runtime4/4 passed; identical output semantics. Next ALS block runs automatically. No universal performance ordering claimed.
- Offline CP references are separate so006 correctness runs, not additional benchmark cells. CP reference creation must finish before campaign w5 uses so006.
- Previous completed size-screen duplicate expanded logs removed only after per-file hash comparison with retained archive, freeing1.24GB; COMPACTION.json records proof. Local free~2.1GiB remains limited, per-cell bulky archives offload hash-verified toso007.

## P2 explicit metadata-only release (2026-09-09)
- 상태: metadata-only 구현/검증 완료, 전체 P2 downstream planning 차단 별도 확인.
- 요청/권한: 사용자가 P2 합성 범주 사전/인코딩 mapping 공개를 승인했다. X/X0 PRIVATE_AGGREGATE 유지. 일반 PUBLIC/다른 release 허용으로 확장하지 않음.
- 증상/원인: transformencode second output M이 source privacy를 그대로 상속하여 CP metadata output이 배제됨. 별도 선언 없는 사전에는 올바른 기존 거부다. opt-in 구현 초기 named parameter map은 multi-return builtin에서 비어 있었으므로 exact builtin/type/arity/output identity + positional spec으로 수정했다.
- 해결: `cofeePublicRecodeMetadata:true` spec와 `sysds.privacy.allowPublicRecodeMetadata=true`의 dual opt-in. recode/dummycode만 허용. exact M output만 PA→PUBLIC, PRIVATE 및 원본/encoded rows는 그대로. CP/FED는 encoder 전에 validate-and-strip; 일반 transform parser 문법은 변경하지 않음.
- 수정 파일: `placement/NeutralPlacementGraphBuilder.java`; CP/FED `MultiReturnParameterizedBuiltin*Instruction.java`; 신규 `runtime/transform/TransformEncodeMetadataPrivacy.java`; privacy contract/utility tests.
- 검증: 25/25 targeted tests, package, diff-check PASS. Docker compile-only encoded prefix 4 planners×w1/3/5=12/12 PASS; 전부 X0 FED/FOUT, M CP/LOUT; FED transformencode 및 CP metadata write 확인. Deployment opt-in off canary는 M에서 거부. Runtime 실행 없음.
- 잔여: full original P2 12/12은 downstream에서 실패. w1 b(<) FULL single-range proof/domain; w3/5 b(+) X physical domain 또는 policy consistency. 사전 공개가 전체 pipeline 성공을 의미하지 않음. Source closure/physical facts 후속 점검 대상이며 privacy를 완화하지 않는다.
- 회귀 위험: output identity 오류/unknown spec 승인/PRIVATE 혼동은 unit negative tests로 검출. EncoderFactory 원본 marker 거부 및 normalized dictionary/dimension parity로 parser grammar widening 방지. Runtime property gate는 legacy/manual instruction의 독립 worker-side privacy enforcement를 대체하지 않는다.
- 기존 ML10 frozen JAR `d0f7b232...` 변경 없음. 이번 backend `ee970725...`와 실험 artifacts 분리.
- 보고서/증거: `/home/mchoi/g014-p2-public-recode-metadata-20260909/REPORT.md`; task-only diff `P2_METADATA_RELEASE.diff`; `ENCODE_LOWERING_AUDIT.json`, `FULL_P2_BLOCKERS.json`, `TEST_RESULTS.json`.
- 독립 review: metadata 기능 APPROVE. w3/w5 추가 진단: clipping outer b(+)의 두 PRESENT ROW inputs에 필요한 exact input/relocation receipts 미발행으로 ExactPhysicalModel domain이 비어짐; multi-return-primary-result→X0 read anchor 연결 누락은 아직 원인 후보. 일반 fallback 금지. w1은 SinglePartitionFacts의 FRAME cardinality seed 누락 조건 확인, 미수정/미해결로 유지.

## P2 전체 planning: FRAME cardinality / encoded-primary pool continuity — 해결
- **증상**: metadata release 후에도 w1 comparison 후보 소실, w3/w5 clipping 마지막 b(+) physical domain empty; 4 planners×w1/3/5 전체 0/12.
- **원인**: SinglePartitionFacts의 FRAME/cast 증명 단절; multi-return FunOut carrier의 logical FRAME input이 일반 compiled input edge가 아니어서 native pool 공급 증명 누락.
- **해결**: literal FRAME 단일 range 및 matrix↔FRAME cast cardinality; 정확한 transformencode primary의 기존 DOMINATES/input0/multi-return-output-value 관계만 resolver/native continuity에 전달. ROW 행 구간 보존 및 single FULL endpoint만 사용; raw encoded column geometry 복사 금지. generic control marker 및 multi-input fallback은 미허용.
- **수정 파일**: SinglePartitionFacts.java, NativePlacementContinuity.java, NeutralPlacementGraphBuilder.java 및 4개 회귀 테스트.
- **의사결정 근거**: runtime이 보존하는 endpoint/ROW axis 증명을 공통 분석에 복구한 것; privacy나 exact physical feasibility 계약 완화가 아님.
- **검증**: 118/118 focused tests, package, diff-check PASS; 실제 전체 P2 Docker compile-only 12/12 PASS. X0 FULL(w1)/ROW(w3,w5), 보호 carrier FED/FOUT, M CP/LOUT, execution0. 공개승인 off canary는 M/PA에서 차단.
- **잔여 범위**: runtime 실행/수치정확성 미검증; 다른 transform spec은 별도 근거 필요. 이번에 확인한 P2 planning blocker 없음.
- **잠재 회귀 위험/감지**: pool provenance와 value/range identity 혼동; X0 no-anchor, 두 ROW input authority, COL/omit negative, stale-width FULL tests로 감지.
- **상세 보고서**: `/home/mchoi/g014-p2-placement-repair-20260909/REPORT.md`.

## AggLocal/FedFirst 미커밋 변경 전체 게시 — 2026-09-09
- **요청**: P2 게시에서 제외했던 미커밋 변경도 모두 커밋하여 `github` (`min-guk/systemds`) main에 반영.
- **범위**: FedFirst producer-first / reachable federated input preference, AggLocal aggregate/vector local-continuation 및 certified re-entry, 관련 테스트와 본 세션 기록. Privacy/feasibility 우회 없이 기존 수정 그대로 포함.
- **새 검증**: Maven targeted suite 58개, 50 PASS, assertion failures 0, errors 8. 실패는 CampaignBHeuristicProvenanceContractTest 5개와 CampaignBHeuristicRealVectorPolicyRedTest 3개이며 `localhost:1234/1235` Connection refused → source privacy 획득 거부로 종료. 이 검증을 전체 PASS로 보고하지 않는다.
- **통과 범위**: HeuristicLocalContinuation, FedFirstRemoteInputPreference, L2SVM locality, protected nested demotion, first-feasible selector, pathwise re-entry, P2 clipping authority, shared privacy contracts.
- **잔여**: 두 legacy fixture의 워커 또는 hermetic source-privacy 준비 후 재검증 필요. 이 게시 작업에서는 privacy fail-closed를 완화하거나 테스트를 삭제하지 않았다. Runtime 실험 없음.
- **증거**: `/home/mchoi/g014-p2-placement-repair-20260909/agglocal-publish/tests.log`, `TEST_RESULTS.json`, `test-results/`.

- **게시 전 빌드**: `mvn -q package -Dmaven.test.skip=true` PASS, staged diff whitespace 검사 PASS.
