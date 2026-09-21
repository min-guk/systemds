# Session Issues — 2026-09-21

## Streaming 구현 전 snapshot push와 protected 56조건 검증으로 계획 확대

- **상태:** 계획 수정만 완료. 사용자가 이번 턴은 계획만 작성하도록 명확히 지정하여 commit/push·production 구현·빌드·실험을 시작하지 않았다.
- **요청/범위:** 현재 source를 C0로 commit하고 origin/main에 push한 뒤 FedFirst/AggLocal streaming single-pass를 구현하고, ML10 + P1/P2 + 작은 SliceLine2를 worker4·privacy 적용·planning-only로 네 planner에 검증한다.
- **확인/결정:** 현재 HEAD와 실제 remote main은 `a6281207cf520171af47f5f8755a34cbd28ecf37`. ML10은 PCA/ALS/ALSCG/KMeans/GNMF/GMM/LogReg/L2SVM/StepLM/GLM이며 LM을 추가하지 않는다. 사용자 “작은 두 개”는 prepared X 원소 수 기준으로 ADULT(32,561×13)·COVTYPE(581,012×54)를 선택한다. KDD98은 행 수는 적지만 469열이라 원소 수가 더 크다.
- **계획 수정:** [수정 실행 계획](G009_FEDALL_HEURISTIC_STREAMING_SINGLE_PASS_DESIGN_2026-09-21.md)에 P0–P9, 실제 planner enum, immutable candidate/witness 계약, 공식 launcher registry/generator/validator 복구, 입력·privacy·RSS/시간·receipt 검증과 최종 56/56 성공 조건을 명시했다. 초기 snapshot은 완전한 correctness baseline으로 위장하지 않는다.
- **수정 파일/검증:** 위 계획, 이 세션 기록, `.omx/plans/` 사본만 수정. Git/코드/config/기존 artifact를 read-only 확인하고 matrix 산술·metadata 크기·문서 링크·diff check를 검증한다. 이번 턴 새 benchmark 결과 없음.
- **잔여/위험:** 공식 launcher가 일부 ML/SliceLine과 P1/P2 생성 경로를 완전히 지원하지 않고 home 여유 공간이 약 0.6GB다. 실행 계획에 사전 복구/용량 점검을 필수로 넣었다. strict single-pass의 일반적 completeness 한계를 유지하되 대상 workload의 stuck/timeout을 PASS로 계산하지 않는다.
- **의사결정 근거:** 사용자 최신 범위(계획만/작은 SliceLine2)를 우선하고, 공통 공간 삭제·privacy 완화·runtime fallback·원격 force push는 금지한다.

## FedAll·Heuristic streaming/single-pass 계약 재설계

- **상태:** 원인 확인·설계 제안 완료, 이번 요청에서는 production 구현 미착수. [설계 문서](G009_FEDALL_HEURISTIC_STREAMING_SINGLE_PASS_DESIGN_2026-09-21.md).
- **증상/근거:** 최종 JAR GLM Heuristic 180초 timeout 후 별도 90초 진단에서 45.80초·79.57초 main stack 모두 candidate component의 첫 feasible probe 안에 있었다. 배치 선택 뒤 support/relocation을 다시 조합 탐색하며 매 분기 전체 selected support와 논리 경계를 검사한다. FedAll의 확인된 과거 실패는 별개로 8GB 공통 analysis에서 signature 정렬 중 OOM; 최종 JAR FedAll은 미검증이다.
- **제안:** full candidate/support/action witness를 함께 확정하는 selector-local constructive traversal, immutable 공통 관계 iterator, 변경 의존성별 검사로 전환. DP exact engine·runtime/privacy/authority는 유지한다. 기존 전역 candidate objective 대신 명시적 local preference를 쓰는 정책 변경으로 구분한다.
- **수정 파일:** 설계 문서, 이 세션 기록, `.omx/plans/` 설계 사본만. 신규 source/test/JAR 변경 없음.
- **검증:** 실제 진단 스택과 호출 경로 대조, 독립 critic의 contract 검토, 문서 경로·diff check. 새 benchmark/코드 테스트 없음. 기존 진단은 성능 성공 표본이 아니다.
- **잔여 이슈/위험:** strict irrevocable single-pass는 임의 결합 제약의 feasible completeness를 보장하지 못한다. DAG diamond·함수 다중 caller·shared authority가 있어 SCC만으로도 부족하다. 안전 continuation을 입증하지 못하면 정책 구성 미완료로 구분하고 infeasible 또는 성공 receipt로 표시하지 않는다. 작은 전수 oracle과 diamond/loop/alias/공유 action 반례로 회귀를 감지한다.
- **의사결정 근거:** selector 알고리즘·정책·certificate만 재설계 대상으로 삼고 공통 후보 삭제, privacy 완화, 가짜 anchor, runtime fallback은 금지한다.

## G009 pruning 설명과 최적해 보존 보장 범위

- **상태:** 동작 설명 문서화 완료 / pruning별 독립 정확성 검증은 미완료.
- **환경/조건:** `systemds-g009-integration`, 기준 커밋 `a6281207cf520171af47f5f8755a34cbd28ecf37`. 정책용 candidate projection 및 receipt 조합 Search 분석.
- **문제 정의/증상:** 후보 공간의 완전성을 복구한 후 추가된 pruning이 다시 합법적 후보나 최적해를 누락하지 않는지 사용자가 근거를 요청했다. 이전의 “exact/최적성 보존” 표현은 목적함수와 검증 범위를 충분히 구분하지 않았다.
- **원인 분석:** 정책에 따른 합법 후보 축소, 불가능한 부분 조합 제거, 목적함수 dominance에 따른 탐색 생략은 서로 다른 계약이다. planning 성공이나 원본 후보 공간 테스트만으로 세 계약을 모두 검증할 수 없다.
- **해결 방법/변경 요약:** [현재 pruning 방식과 정확성 조건](G009_CURRENT_PRUNING_MECHANISM_AND_CORRECTNESS_2026-09-21.md)에 실행 순서, 실제 목적함수, projection/DP 경계, 상한·하한·zero-target 조건, component 독립성 및 검증 공백을 기록했다.
- **수정 파일:** 위 설명 문서와 이 세션 이슈 문서만 추가. production 코드·테스트·설정 변경 없음.
- **검증 방법/결과:** 기준 소스와 기존 세션 보고서를 대조하고 문서의 상대 링크 및 whitespace를 검사했다. 이번 문서 작업에서 테스트/benchmark를 재실행하지 않았다. 이전 PASS 및 중단 기록은 신규 검증 결과와 구분해 인용했다.
- **잔여 버그/이슈:** 이번 분석으로 새 반례가 확인된 것은 아니다. 하한 admissibility, component factor 완전성, effect 동등성, zero-target의 강제 emission 조건에 대한 포괄적 증거는 미확보다.
- **수정으로 인한 버그 가능성:** 문서만 추가하므로 실행 동작 변경은 없다. 문서를 전 범위 정확성 인증으로 읽거나 정책 optimum을 runtime optimum으로 읽는 위험을 범위/주의사항으로 명시했다.
- **잠재 회귀 감지:** 작은 독립 fixture의 전수 oracle, prefix별 하한 비교, projection 전후 및 component 분리 전후 winner 비교가 필요하다. 대형 workload 반복 실행만으로 대체하지 않는다.
- **의사결정 근거:** oracle/runtime/planner 규칙은 수정하지 않았다. 합법 후보의 임의 축소 금지와 privacy/authority 보존 원칙 아래 현재 구현 및 증명 의무만 명시했다.

## Selector 독립적 search space 계약으로 재계획

- **상태:** 계획 작성 완료 / 구현·실험 미착수.
- **문제 정의/증상:** selector마다 목적함수와 정책이 다르므로 특정 목적의 최적해 보존만으로 공통 후보 축소를 정당화할 수 없다.
- **원인:** 표현의 완전성, 공통 합법성, selector 내부 정책/pruning의 책임 구분이 필요하다. 현재 정책 view는 이미 분리된 부분이 있으므로 실제 누출을 확인하지 않고 전면 재작성해서도 안 된다.
- **해결 방법:** [selector 독립적 search space 보존 계획](G009_SELECTOR_INDEPENDENT_SEARCH_SPACE_PLAN_2026-09-21.md)에 S0–S6 단계와 A1–A12 수용 기준을 작성했다. 공통 공간에는 합법 대안을 유지하고, 목적함수 기반 축소는 검증된 selector 내부로 제한한다.
- **수정 파일:** 신규 계획 문서, 이 세션 기록, `.omx/plans/`의 해당 계획 사본. 소스·테스트·설정 변경 없음.
- **검증 방법/결과:** 참조 소스/테스트 경로 및 현재 코드 위치 대조, 문서 링크·형식 검사. 계획의 구현/성능 결과는 아직 없다.
- **잔여 이슈:** pruning별 삭제 근거, quotient의 decode 보존, selector 간 공간 불변성과 내부 하한 검증은 계획 실행 시 확인해야 한다.
- **수정으로 인한 버그 가능성/감지:** 문서만 수정했으므로 runtime 회귀는 없다. 실행 시 표현 복원으로 인한 성능 회귀는 worker=4 planning-only 단일 비교로 기록하고, 정확성은 작은 독립 oracle로 우선 검사한다.
- **의사결정 근거:** runtime/privacy/authority 규칙은 유지한다. 특정 선호도·action 수·비용 가중치를 공통 합법성으로 취급하지 않는다는 사용자 요구를 계획의 최상위 계약으로 반영했다.

## Selector 독립 공간 실행: 보호된 상태 및 publication authority

- **상태:** 부분 수정·검증 완료, 전체 A1–A12 완료 아님. [실행 보고서](G009_SELECTOR_INDEPENDENT_SEARCH_SPACE_EXECUTION_REPORT_2026-09-21.md)에 감사·시험 근거와 잔여 기준을 기록했다.
- **환경/조건:** 기준 소스 `a6281207cf520171af47f5f8755a34cbd28ecf37`; 수정된 JAR SHA-256 `8f10f1891f3cd99bd492b1ad5ac4913aff054fe74afd2d1f16370a0acc4833df`; 4-worker Docker, planning-only.
- **문제/원인:** `NeutralPlacementGraphBuilder`의 CP→FOUT closure가 CP/LOUT 실제 source 없는 FED-only privacy 상태에 존재하지 않는 CP target을 발행해 보호된 transient fixture의 빌드가 실패했다. 다른 문제로 마지막 publication의 재계산에서 relocation action이 사라져도 이전 candidate support clause가 해당 action을 참조할 수 있었다. 후자는 잘못 생성된 13열 SliceLine DML에서 재현되었으므로 유효한 14열 입력에 동일 증상이 있는지는 별도로 확인한다.
- **수정:** CP/LOUT source가 legal states에 있을 때만 CP→FOUT 추가; 마지막 action set과 binding이 어긋날 때만 기존 relocation binder를 호출해 logical transient source 및 fixed point를 재검사. 새로운 공통 정책/비용 pruning은 추가하지 않았다.
- **검증:** 수정 전 protected transient generation은 `NoSuchElementException`; 수정 뒤 해당 suite와 selector/completeness 그룹, 13개 테스트 클래스의 총 94개 case에 failure/error 0, Maven package 및 `git diff --check` 통과. 큰 GLM 전 범위 oracle은 수행하지 않았으며 이 수치로 보장하지 않는다.
- **실험 범위:** 두 번째 수정 전 JAR에서 P1/P2 각 planner 1회씩 6개 planning-only receipt 성공; 변경 전후 matched baseline이 없어 가속률 없음. 첫 SliceLine 입력은 worker metadata `cols=14`와 generated DML `D=13`이 불일치해 600초 제한 종료 관측은 성능 표본에서 제외하고 14열로 고쳐 재시험한다. 워커 없는 GLM 단독 실행의 Connection refused도 privacy 불가능으로 간주하지 않는다.
- **잔여 위험/재현 감지:** 독립 decoded joint oracle의 support/authority/공유 emission 반례, relocation/local bound 및 canonical tie의 모든 prefix, 다른 비용 목적의 반대 winner, ML/SliceLine의 최종 4-worker 결과, matched baseline과 memory 측정이 미완료. 입력 metadata를 기준으로 생성 DML의 열·행 범위를 확인하고, process RC뿐 아니라 DML 오류·receipt·runtime flag를 검사한다.

### 후속 실험과 성능 채택 판정

- **정정한 실행 입력:** ADULT 기반 처음 SliceLine 스크립트는 열 수가 틀렸고 공식 대상 `SLICELINE` dataset도 아니므로 비교에서 제외했다. 397×5 SLICELINE 데이터를 worker 4개에 메타데이터와 같은 range로 분할해 planning-only 조건당 1회 실행했다. DP-global/DP-local은 CandidateE2E 약 5.71/5.90초에 완료, heuristic은 120초 제한을 넘겨 완료하지 못했다.
- **ML:** 동일 4-worker, privacy-safe P2P2D에서 PCA의 세 planner는 planning 완료(약 2.5–2.7초 CandidateE2E), GLM 세 planner는 120초 제한을 넘겨 끝나지 않았다. JVM stack sample은 GLM의 `bindDirectNativeCandidateRealizations` 내부를 가리킨다. timeout은 불법/불가능 계획의 증명이 아니다.
- **두 성능 후보:** relocation scorer의 leaf-local option cache는 SLICELINE heuristic을 120초 안에 완료시키지 못해 **채택하지 않았다**. 마지막 action binding 검사 identity-index 역시 P1 heuristic의 Analysis 구간만 빨라지고 전체 CandidateE2E/wall이 개선되지 않아 **채택하지 않았다**. 두 후보 모두 원복하고 최종 JAR sha256 `8f10f1891f3cd99bd492b1ad5ac4913aff054fe74afd2d1f16370a0acc4833df`를 재생성했다.
- **harness 설정 이슈:** 첫 P1/P2 추가 측정에 host `SYSTEMDS_STANDALONE_OPTS`만 넘겨 Compose의 `CAMPAIGN_COORDINATOR_JAVA_OPTS`가 기본 8GB가 되었다. P2는 공개 메타데이터 opt-in 부재로 잘못 실패했다. 16GB/기존 opt-in을 명시해 P1/P2 각 planner 조건을 다시 한 번 실행한 결과 최종 6개 receipt가 성공, runtime 모두 미실행. 이전 중간 JAR 대비 단일 관측의 방향은 planner별로 달라 일괄 가속을 주장하지 않는다.
- **추가 작은 oracle:** 보호된 8개 결합 계획 oracle에서 X의 native source 및 D의 대체 geometry를 고의로 각각 제거하면 4개 plan이 누락되는 검사 추가. 다른 목적의 전체 selector optimum과 GLM full planning 완료는 아직 미검증. 자세한 행별 결과와 A1–A12 판정은 실행 보고서 참조.
- **기존 테스트 계약 불일치:** 추가 privacy 테스트에서 `MixedPrivacyRelocationContractTest`가 baseline 소스에서도 동일하게 실패함을 임시 HEAD builder 격리 실행으로 확인했다. C의 A 직접 입력은 candidate clause에서 `DIRECT`로, B 공개 입력은 action `RELOCATION`으로 증명된다. "모든 PRESENT 입력에 relocation choice가 각각 존재해야 한다"는 기존 assertion만 바꾸어 두 정확한 binding과 하나의 실제 공개 재배치 action을 검증한다. 진단 실행에서는 두 positive/negative 테스트가 통과했지만, **PUBLIC 소스 positive fixture는 `AGENTS.md`의 privacy-test 정책에 따라 최종 `@Ignore`**로 제외한다. 두 protected 소스 negative 테스트는 active이며 production privacy/space 코드는 변경하지 않았다.
- **최종 확인:** selector/completeness/privacy 19 class, 총 118 case 중 실행 112·무시 6, 실패/오류 0; `mvn -q -DskipTests package`, `git diff --check` 통과. 최종 JAR hash가 matched P1/P2 및 SLICELINE/PCA/GLM 진단에서 사용한 8f10f189…와 동일. repo-wide lint/별도 정적 분석 및 GLM 성공/독립 전체 공간 증명은 미실행·미완료.

## GLM 지연 원인 JFR 확인

- **상태:** 단일 실행 원인 진단 완료, 최적화/전체 planning 완료는 아님.
- **문제:** search-space 구성과 selector 지연을 구분할 근거 부족.
- **확인:** 같은8f10f189 JAR DP-local GLM에서93.30초 main CPU90.850초, 최초 공통 analysis builder 내부. JFR main5,589표본 중 placement equals42.1%, canonical text16.9%(inclusive/중복 범주). GC STW 합2.72초/약109초. selector가 오래 탐색한다는 설명보다 후보 표현의 비교·정렬·재구성 비용이 강하게 지지됨.
- **방법/수정 파일:** production 변경 없음. `docs/G009_GLM_PLANNING_BOTTLENECK_DIAGNOSIS_2026-09-21.md`와 공식 harness GLM 미지원의 진단 예외 근거 문서 추가. 외부 `/home/mchoi/g009-glm-diagnosis-20260921/`에 원시자료 보존.
- **실행 이슈:** 공식 harness frozen set에서glm 거절; 직접 진단 worker 명령 WORKER→-w로 복구. worker 지연 시작 때문에 동일 준비상태 E2E 비교가 아님. jcmd 미설치→SIGQUIT thread dump. 후보/권한/runtime 정책 변경 없음.
- **잔여 이슈:** 정확한 closure pass/고유 후보 증가/중복 생성량과 완성된 builder·selector 시간 미측정. 표본 깊이 잘림2,187건 때문에 호출자 없는 표본을 selector로 오분류하지 않는다.
- **잠재 회귀 위험:** 소스 변경 없음. 표본 비율을 wall-time 또는 속도개선 수치로 오독하는 위험은 보고서에 명시. 전용 Docker 자원 정리 확인, `git diff --check` 통과.

## GLM 공간 보존형 최적화 재계획

- **상태:** 계획 작성만 완료; 신규 구현/실험 미착수.
- **문제/원인:** 단일 JFR에서 builder의 깊은 equals/canonical 정렬/재구성이 확인됐으며 기존 캐시가 있어도 재생성된 객체의 구조 비교가 반복된다.
- **해결 계획:** `G009_GLM_SPACE_PRESERVING_OPTIMIZATION_PLAN_2026-09-21.md`에 O0 최소 경계→O1 exact 구조 비교→O2 정렬 재사용→O3 no-change 재사용→O4 증분 인덱스→O5 proof/closure 재사용을 정의. 조건당1회 빠른 feedback, 공통 후보 pruning 추가 금지.
- **수정 파일/검증:** 계획 문서와 .omx/plans 포인터, 이 issue 기록만 작성; 경로/내용/whitespace 검사. 코드·JAR 변경/테스트/벤치마크 없음.
- **잔여 이슈/위험:** 정확한 pass/후보 증가율, formal GLM harness, 독립 oracle 증명 미완료. 단일 측정과 timeout을 통계적 가속으로 오해하지 않도록 명시.
- **의사결정 근거:** selector 독립 decoded 공간/authority/정렬 계약을 보존하며 데이터 표현의 재처리만 최적화한다.

## GLM 공간 보존형 최적화 실행과 baseline 자체의 planning 실패

- **상태:** O0–O5 부분 구현·작은 회귀 검증 완료, 성능 채택 및 GLM planning E2E 완료는 **미완료**. [실행 보고서](G009_GLM_SPACE_PRESERVING_OPTIMIZATION_EXECUTION_REPORT_2026-09-21.md)에 항목별 결과 기록.
- **문제/원인:** P2P2D GLM 4-worker planning-only에서 최적화 전 JAR까지 `scripts/builtin/glm.dml:939`의 `TRead Y_prob`가 `No privacy-safe physical placement for transient replay`로 실패. 현재는 실제 합법적 선택 부재인지 compiler의 CFG/physical replay 누락인지 미판별. 수정본의 빠른 동일 오류는 성공한 planning 가속이 아니다.
- **수정/제외:** exact child structural arena, canonical subtree skip, no-change emission/fact 유지, closure 불변 인덱스, revision row 비교 1회 캐시, opt-in phase marker를 실험 작업 트리에 구현. 단방향 dirty cone은 권한 보존 증명 부족으로 양방향 복귀; eager proof/template sort와 1.3% 단일 차이의 deep-hash 진단 변경은 원복. 공통 후보 삭제·privacy 완화 없음.
- **검증:** 기존 JAR `8f10f189…` 동일 오류까지 coordinator 182.87초, 보수적 수정 JAR `209cc3b6…` 97.66초(단일 time-to-error 관측; completed E2E 아님). targeted 50 case 중 48 실행·2 skip, failure/error 0, 추가 canonical/completeness 테스트와 Maven package 및 diff check 통과. 변경 JAR의 peak memory, selector 경계, 전체 GLM 성공, formal campaign receipt는 없음.
- **잔여 위험/회귀 감지:** O4 candidate-dependent 인덱스 delta와 O5 완전한 proof dependency key는 미구현; 작은 테스트의 동등성은 GLM 전체 공간 완전성을 보증하지 않는다. `Y_prob`의 source realization/CFG reaching/privacy exclusion을 독립 fixture로 판별하고, 이후 성공한 GLM baseline/수정본을 동일 조건에서 비교해야 채택 가능. 워커 학습 runtime은 실행하지 않았고 Docker 자원은 정리했다.
- **설정 판별:** P2에 쓰인 `allowPublicRecodeMetadata=true`를 같은 수정 JAR GLM에 한 번 적용했으나 동일 `Y_prob` 오류(105.12초). 이 속성은 명시적 metadata-only transformencode release에만 적용되고 GLM 스크립트에는 그 요청이 없어 GLM 해결책으로 채택하지 않았다.

## GLM 60초 재계획: 방향성 의존성과 정상성 선행

- **상태:** 계획 작성 완료, 새 구현·실험 미착수. [실행 계획](G009_GLM_CORRECTNESS_AND_DIRECTED_DELTA_60S_PLAN_2026-09-21.md).
- **문제/원인:** 양방향 dirty cone이 필요하다는 이전 설명은 충분한 근거가 없었다. fact-only direct closure는 입력 producer를 읽으며 physical closure도 producer→consumer로 전파한다. GLM 오류는 단방향·양방향·원래 JAR에 공통이므로 단방향의 반례가 아니다. 한편 GLM의 IRLS/CG·함수·합류 구조는 반복 재계산과 support 조합 비용을 함께 유발할 수 있으나 두 비용의 비율은 미측정이다.
- **해결 계획:** G0 측정 경계/실패 baseline 분리 → G1 Y_prob 독립 반례/정상성 → G2 실제 방향성 의존성 → G3 delta 인덱스 → G4 authority-safe proof 재사용 → G5 compiled graph에 남은 상수 분기만 검토 → G6 정확한 support 압축·지연 소비. 기존 SCC/IPA를 새로 중복 구현하지 않는다.
- **수정 파일:** 새 계획 문서, `.omx/plans/` 포인터, 이 세션 기록만. production/test/JAR 변경 및 benchmark 없음.
- **검증:** 관련 코드·기존 로그 대조, 계획 경로/링크 및 whitespace 확인. 현재 planning 60초 달성이나 정상 완료는 검증한 것이 아님.
- **잔여 위험/회귀 감지:** hidden/negative dependency 누락, 삭제·authority 교체, query별 SCC/pinning 혼합, 압축 결합 누락을 독립 oracle/full recomputation 대조로 검증해야 한다. G1에서 실제 infeasibility가 입증되면 고정 입력/정책 변경을 자동 수행하지 않고 범위 결정을 요청한다.
- **의사결정 근거:** 공통 합법 후보 보존, privacy/runtime 규칙 유지. 실패까지의 97.66초를 성공 baseline으로 바꾸지 않으며 정상 전체 초기 planning <60초를 별도 수용 기준으로 고정한다.

## GLM 정확성·방향성 증분 계획 실행 결과

- **상태:** 핵심 DP-local 정상 초기 planning **45.779340355초**로 단일 관측 60초 목표 달성. 계획 전체는 부분 완료. [실행 보고서](G009_GLM_CORRECTNESS_AND_DIRECTED_DELTA_60S_EXECUTION_REPORT_2026-09-21.md)에 G0–G6별 구현·검증·미완료와 evidence 경로를 기록했다.
- **원인/수정:** protected `Y_prob` 연쇄 실패를 ROW all-row 열 slice의 native witness 누락, 여러 caller가 공유하는 함수 formal의 local-zero/FED 충돌, Nary MULT의 다중 local matrix broadcast 불지원으로 추적했다. ROW 증명은 disjoint multi-range에 한정하고, zero caller만 실제 함수·중첩 callee 복제로 분리했으며, 기존 `executeMultipleSlices`로 runtime/oracle의 다중 broadcast 계약을 맞췄다. G2 producer→consumer dirty, G3 changed-owner index, G4 closure-local physical lookup, G5 상수 If predicate도 통합했다. Privacy/authority를 우회하거나 후보를 cap/prune하지 않았다.
- **최종 실험:** 최종 JAR SHA-256 `815e75aec0db38e6131e423f1675f841198e1f65800fa0de13711a52d7533331`, 동일 P2P2D/worker=4/planning-only 각 조건 1회. DP-local 45.779340355초, CandidateE2E 44.023057896초, selection 0.741519052초, 정상 receipt·runtime 미실행. DP-global도 44.644206309초 정상 성공. Heuristic은 analysis 후 planner에서 180초 timeout. 두 성공 수치의 차이를 통계적 우열로 해석하지 않는다.
- **기준선 문제:** frozen pre-G1에 G1 수정만 이식한 B_correct JAR `4873d69d…`는 `glm_initialize:586`의 `TRead y_corr` protected transient replay에서 실패했다. 130초 time-to-error는 성능 baseline이 아니며 baseline 대비 가속률과 memory +5% 판정은 보류한다. 재구성 patch/JAR와 실패 log 보존.
- **검증:** 최종 targeted 94 case 중 92 실행·2 skip, 실패/오류 0; Nary runtime 2건 별도 pass; Maven package 및 `git diff --check` pass. 단일-range ROW 반례 red→green. 전체 `OracleFacadeTest`에는 별도 기존 계약 관련 실패 1건이 있어 전체 suite pass로 확대하지 않는다. GLM 전역 독립 공간 oracle와 repo-wide lint/static analysis는 미실행.
- **잔여:** G4 전체 proof/SCC 재사용과 G6 support 관계의 DP/receipt까지의 지연 소비 미구현. 최종 JAR의 P1/P2/SLICELINE 공식 회귀는 현 harness의 workload 허용/스크립트 경로 불일치로 시작 전 차단됐다. Heuristic timeout 원인, 성공 correctness baseline, peak memory 비교도 남았다. 60초 핵심 달성과 계획 전체 완료를 구분한다.
