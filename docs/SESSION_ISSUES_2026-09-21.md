# Session Issues — 2026-09-21

## FedFirst·AggLocal 재계획: 엄격한 single-pass 대신 로컬 정책 우선 첫 해 종료

- **상태:** 계획 수정만 완료. 사용자가 strict single-pass는 불필요하되 각 단계에서 정책에 가장 가까운 선택을 우선해야 한다고 명확히 했고, 최신 요청으로 구현 작업을 중단했다.
- **문제/원인:** 이전 계획의 rollback=0·전역 경계 summary 선행 인증은 사용자 목적보다 강한 조건이었다. 동시에 단순히 임의의 첫 feasible을 반환하면 로컬 정책을 무시하고, 바깥의 최적 탐색만 제거하면 내부 relocation 최적화와 첫 probe 병목이 남는다.
- **해결 계획:** [수정 계획](G009_FEDALL_HEURISTIC_STREAMING_SINGLE_PASS_DESIGN_2026-09-21.md)을 로컬 state/input/anchor/추가 action/identity 순서, 충돌 시 차선 복구, 첫 완전한 합법 witness 뒤 탐색 종료로 교체했다. state→candidate→action 실패 전달, support 조건 전파, policy certificate 의미 분리, DP/공통 공간 보존을 L0–L6에 포함했다. 기존 56조건·worker4·보호 입력·planning-only·조건당 1회는 유지한다.
- **수정 파일/검증:** 계획 문서·`.omx/plans/` 사본·이 세션 기록만 갱신한다. 문서 사본 일치·참조 경로·whitespace 검사 및 코드 파일 hash 불변 확인. 이 계획 수정에서는 Maven/Docker/benchmark/commit/push를 실행하지 않는다.
- **기존 부분 변경:** 직전 실행 요청 중 `RelocationSelections.java`에 first-feasible API와 조기 종료 변경이 작성됐으나 미검증이다. 계획만 요청 이후 해당 agent를 중단했고 diff를 임의 원복하거나 완성으로 채택하지 않았다. 기존 source/test 및 병행 독립 인증 작업도 보존한다.
- **잔여/회귀 위험:** 첫 해 탐색도 최악에는 지수적으로 증가할 수 있다. 낮은 정책 row를 미리 삭제하거나 시간 초과를 불가능으로 판정하지 않는다. 작은 보호 fixture에서 로컬 winner·충돌 복구·성공 후 탐색 0·DP 불변을 확인한 뒤 GLM 단회와 동일 최종 JAR 56조건으로 검증한다.
- **의사결정 근거:** 사용자 최신 계약만 완화하며 privacy/runtime/authority와 공통 후보 보존 원칙은 완화하지 않는다. 아직 새 selector 구현·성능 개선·56조건 성공을 주장하지 않는다.

## Search-space 독립 인증 구현과 전체 corpus 미완료

- **상태:** 작은 독립 전수 회귀와 실행기 기반 구현, 전체 워크로드 인증은 `INCOMPLETE`. [실행 상태](G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_EXECUTION_STATUS_2026-09-21.md).
- **증상/원인:** 기존 Exact/census는 production 후보와 hard factor를 공유하여 생성 전 누락을 탐지하지 못한다. 최근 14개 목록도 전체 workload를 대표하지 않는다. 독립 primitive grammar, 전역 legality, 전체 joint-set exporter, frozen workload 입력이 아직 없었다.
- **해결(변경 요약):** source-qualified discovery inventory, 48-rule/56-transformation OPEN ledger, 작은 의미론 oracle·고정 source-anchor 반례, production receipt/reject capture, source별 shard/재개/차집합 runner, bounded gate를 추가했다. full semantic coverage가 입증되지 않으면 전체 PASS를 내지 않는 계약이다.
- **수정 파일:** `scripts/fedplanner/build_plan_space_inventory.py`, `build_plan_space_rule_ledger.py`, `run_plan_space_certification.sh`와 inventory 테스트, `src/test/java/org/apache/sysds/test/component/federated/placement/oracle/semantic/`, `.../shadow/ProductionPlanSpaceCapture*.java`, `.../shadow/ProductionJointPlanSpaceProbe*.java`, `.../fedExact/ExactPhysicalRawSpaceExporter*.java`, `src/test/resources/fedplanner/plan-space/`, scope/status docs; 외부 `cofee-evaluation/calibration/plan_space_verify.py`와 테스트/문서. 동시 진행 중인 production planner 수정은 이 작업의 변경으로 간주하지 않는다.
- **검증 방법/결과:** bounded gate에서 inventory 테스트 4개, runner 테스트 21개, Java 여섯 클래스 24 case(11+1+3+3+1+5)가 통과했다. 실제 279개 discovery를 사용한 full scope probe는 `verify`와 `check_certificate` 모두 `UNKNOWN`; 115개 in-scope ID와 224개 planned condition이 미매핑이다. 이는 corpus 실행이나 전체 인증이 아니다.
- **잔여 이슈:** 279 발견 항목(115 `IN_SCOPE`, 148 `UNSUPPORTED`, 16 `HISTORICAL`)의 조건별 frozen cell 매핑, 모든 rule tuple/변환 proof, 독립 full domain, production/기존 exhaustive full joint exporter, 공식 Docker 재생, 대형 artifact 저장 공간.
- **잠재 회귀 위험/감지:** 원시 도메인 누락·source revision 병합·cache 오염·audit 유실·동일성 충돌을 source-qualified inventory, mutation fixture, 독립 review, corruption/resume/coverage 테스트로 감지한다.
- **의사결정 근거:** runtime/privacy/authority를 완화하거나 후보를 임의로 닫지 않았고, 불확실한 legality는 `UNKNOWN`으로 처리한다. workload 실험은 기존 Docker launcher 계약 안에서만 채택한다.

## FedFirst·AggLocal streaming 실행: C0 push, 하네스 사전검사, 결합 경계 미해결

- **상태:** [실행 상태 보고서](G009_FEDALL_HEURISTIC_STREAMING_EXECUTION_STATUS_2026-09-21.md) 기준 부분 완료. C0 `fe000959c48ffa1172399e49124d082fe42d0c6d`의 `origin/main` fast-forward push와 remote SHA 확인은 완료했으나, strict streaming 구현·최종 56조건 planning 검증은 미완료다.
- **문제/원인:** 기존 두 policy adapter의 state selector가 재귀 rollback하고 후보 선택도 component DFS를 사용한다. 추가한 producer→두 consumer 합류 반례는 pairwise arc consistency만으로 공동 continuation을 인증할 수 없고 현 selector가 실제 trial을 철회함을 보였다. 정확한 joint summary 없이 DFS만 제거하면 합법 해 누락 또는 가짜 성공 위험이 있다.
- **수정:** exact Search의 기존 `requiredSupports` 인덱스를 hot compatibility check에 재사용해 반복 support 정렬을 없앴다. 이는 DFS 제거가 아니므로 single-pass 완료로 표시하지 않는다. 외부 공식 하네스에는 별도 planning-validation profile/56-cell registry/보호 입력 사전검사를 추가했다. 병행 작업의 독립 plan-space 문서는 이 작업의 변경 범위 밖이다.
- **검증:** selector·candidate oracle 표적 테스트, Maven package, diff check 통과. 외부 하네스 `bash -n`, Python 3개 단위 테스트 통과. Prepared ADULT에서 worker4 분할·보호 metadata를 생성·검증해 56개 셀 중 입력 사전검사 **52개 통과/4개 실패(COVTYPE)**. 실제 planning receipt 0개, GLM 신규 성능 수치 없음.
- **잔여/위험:** P3의 14-script factor inventory와 protected joint oracle, P4–P6 atomic witness selector, COVTYPE worker4 분할(약 242 MiB; 현재 여유 약 573 MiB), 최종 동일 JAR 56회가 남았다. timeout/stuck/preflight는 성공으로 세지 않고 privacy를 낮추거나 toy 데이터를 대입하지 않는다. C1 commit 및 최종 push는 아직 하지 않았다.

## 전체 workload search-space의 독립 건전성·완전성 검증 계획

- **상태:** 계획 작성. production 구현·빌드·전수 검증·인증은 이번 작업에서 수행하지 않음.
- **문제 정의/증상:** 기존 exhaustive가 같은 candidate domain과 hard factor를 사용하면 builder 단계에서 누락한 feasible plan 또는 잘못된 legality를 함께 놓친다. workload planning 성공·최적 plan 일치·후보 수 일치는 전체 결합 공간의 일치를 증명하지 않는다.
- **환경/근거:** 검토 source HEAD `fe000959c48ffa1172399e49124d082fe42d0c6d`. `ExactPhysicalModel.java:173`은 production candidate facts를 소비하고 외부 `cofee-evaluation/calibration/java/WorkloadSpaceCensus.java:27`도 그 variables/hardFactors를 사용한다. census의 capture 완료와 exhaustive 완료는 별개다. 기존 production decoded test는 1,344개 finite-support 조합 안의 literal 8-plan DIRECT 계약이며 전체 workload 증명이 아니다.
- **해결 방법:** [독립 plan-space 인증 계획](G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_PLAN_2026-09-21.md)에 S0–S6와 12개 수용 기준 작성. 후보 생성 이전 IR·입력에서 독립 원시 domain과 legality 명세를 만들고, 새 공간/기존 exhaustive/독립 reference의 양방향 집합 차이를 검사한다. 전체 workload inventory 대조, rule별 positive/negative 근거, mutation, 병렬 shard coverage, content-bound cache와 재개, 공식 Docker replay, fast/full gate를 포함한다.
- **수정 파일:** 위 계획과 이 문서만. 계획에 제안된 runner/oracle/CLI는 아직 구현되지 않았다.
- **검증 방법/결과:** 현행 builder·Exact·oracle tests·census runner·workload manifest를 read-only 대조했다. 독립 critic의 세 지적(독립 domain 유한화 근거, 기존 exhaustive 결함의 non-PASS 판정, tuple별 rule coverage)을 반영하고 재검토 APPROVE를 받았다. 주요 근거 8개 파일 경로, S0–S6·12개 수용 기준, docs/.omx 사본 일치 및 diff whitespace 검사 통과. 과거 실행 결과를 이번 새 검증 결과로 간주하지 않는다.
- **잔여 이슈:** authoritative 전체 workload inventory 확정, independent semantics/열거기 구현, 큰 공간 exact 비교, 전체 인증 모두 후속 실행 항목이다. 현재 root 여유 약 0.58GB로 대형 저장 전 충분한 artifact volume을 확보해야 한다. PUBLIC-only ignore와 동적 미확인 문맥은 인증 공백으로 표시한다.
- **잠재 회귀 위험/감지:** 정답에 production 후보 재사용, normalizer의 과도한 병합, 전역 coupling 누락, stale/partial cache를 dependency gate·literal/mutation·separator/coverage 검사·crash-resume/corruption tests로 검출한다. 문서만 추가했으므로 runtime 동작 변경은 없다.
- **의사결정 근거:** 불필요한 후보 축소·privacy 완화·runtime fallback 금지 원칙을 유지하며, 비용/selector 선호와 legality를 분리한다. timeout/UNKNOWN/미지원/부분 열거는 전체 PASS가 아니다.

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

## 전체 search-space 독립 인증 실행: 범위 동결과 UNKNOWN certificate

- **상태:** 재실행 가능한 작은 검증 게이트와 전체 범위의 비통과 certificate를 구현·실행했다. 사용자 계획의 전체 feasible/illegal plan 인증은 미완료이며 결과는 `UNKNOWN`이다. [상세 실행 상태](G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_EXECUTION_STATUS_2026-09-21.md).
- **문제/원인:** 기존 exhaustive도 공통 builder/domain을 공유하므로 P↔E 일치만으로 생성 전 누락을 잡지 못한다. 현행 외부 workload source는 279개 발견으로 흩어져 있고, 224개 planning profile도 실제 데이터·privacy·compiler snapshot까지 완결된 cell이 아니다. B-21에서는 candidate fact owner가 decision graph 밖에 있어 초기 P 원시 exporter가 예외를 냈다. 함수 fixture B-07은 HOP 호출이 아닌 parser의 inlined call boundary에 호출 정보가 남아 초기 스냅샷 테스트가 실패했다.
- **수정:** 발견 inventory·rule ledger·447-cell backlog와 planning literal import/`.mtd` sidecar hash를 추가했다. 독립 원시 열거기/선별된 runtime·matrix·state 의미론, 사전 builder AST/HOP 스냅샷, P/E 원시 ordinal exporter, 범위 분할·resume·정확한 집합 비교 runner를 연결했다. B-21의 decision-graph 밖 candidate receipt는 삭제하지 않고 P 원시 product에 보존하며 해당 행을 `UNKNOWN`으로 처리한다. B-07 스냅샷은 inlined call boundary와 binding을 기록한다. 미완료 preflight/attestation은 runner에서 PASS 불가로 고정했다.
- **검증/근거:** 작은 게이트는 inventory/ledger/backlog drift, 4개 inventory 테스트, 25개 Python runner/backlog 테스트, 13개 Java 클래스 53개 case에서 통과했다. 초기 통합 게이트의 oracle independence 실패는 parser 스냅샷 어댑터를 semantic core에서 `shadow` 패키지로 옮겨 해결했다. 별도 raw-pack 무결성 테스트 1개와 실제 B-21 E-model 6,048 ordinal 전체 압축 artifact 재검사가 통과했다. full backlog `verify`와 `check-certificate`는 각각 `UNKNOWN`: 447 cell 중 283 in-scope 미증빙, 148 unsupported, 16 historical; 279 ID/224 profile 누락 0, 조건 미해결 발견 115개, runtime tuple coverage UNKNOWN이다.
- **저장/재사용:** `/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/`에 full certificate, 로그, SHA receipt와 fixture 원시 행을 보존했다. B-21 E-model의 6,048행 원본 1.08GB를 검증 후 gzip 약 35MB로 압축하고 `scripts/fedplanner/check_raw_fixture_pack.py`로 해제 SHA·ordinal·감사 행을 재검사한다. 이는 E의 **현재 모델 곱** 전수이지 독립 물리 공간의 완전성 증명이 아니다.
- **잔여/회귀 위험:** B-21 P 원시 곱은 324,699,527,577,600개, B-22 P는 37,748,736개다. 임의 cap/샘플을 전수 PASS로 바꾸지 않았다. 모든 cell의 실제 data/FederationMap/compiler/privacy attestation, 48 family/56 transformation 전체 tuple 증거, 독립 full grammar, P/E/R 공통 physical identity·set 비교, 공식 Docker runtime replay와 병렬 큰 공간의 정확한 완료 증거가 남았다. Stage data/references가 비어 있고 launcher는 protected 14개 worker4 LAN 범위만 지원한다. 동시 작업 중인 production planner 파일은 이 인증 작업에서 수정하거나 되돌리지 않았다.
- **의사결정 근거:** legality 미확인과 resource 한계를 `UNKNOWN`으로 보존하고, 새 후보 삭제·privacy 완화·runtime fallback 없이 독립 source 경계와 실패가 드러나는 재실행 경로를 우선했다. 전체 PASS는 계획의 12개 최종 수용 기준이 실제로 모두 충족될 때만 허용한다.

### 독립 인증 최종 게이트와 재사용 번들 갱신

- **최신 검증:** 실제 context에 실린 14 case × worker 1/3/5/7의 DML 56개를 파싱·HOP 구성·builder 이전 스냅샷과 대조했고 56/56 성공했다. `P1_FULL`과 `P2_PREP` 및 후자의 `Xraw` 보호 privacy도 포함된다. 다만 24개 템플릿의 28개 함수 호출에서 input/output name 배열이 null이어서 그 call-boundary legality는 UNKNOWN이다.
- **게이트 결과:** 2026-09-21 최신 full gate의 inventory/ledger/backlog drift, 저장소 Python 8건, 평가 저장소 Python 27건, Maven 14 class/54 case는 failure/error/skip 0이다. 전체 certificate와 독립 `check-certificate`는 모두 `UNKNOWN`(exit 1)이다. 최신 경로는 [실행 상태 문서](G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_EXECUTION_STATUS_2026-09-21.md)에 기록한다. 447 cell 중 283 in-scope 미증빙, 148 unsupported, 16 historical이며 rule tuple coverage는 UNKNOWN이다.
- **정적 입력 대조 추가:** 문맥 원래 경로에는 없던 `inputs.json`/`worker-partitions.json`의 동일 SHA 복사본을 input template tree에서 찾았다. 4개 worker 조건의 8개 metadata manifest를 context SHA에 고정하고 profiled 224개 condition의 DML worker/range, sidecar, partition manifest 전역 shape/privacy/range를 교차 검증했다. manifest 해시나 범위를 바꾸면 테스트가 실패한다. 실제 data bytes와 runtime FederationMap은 여전히 없다.
- **저장/재실행:** SHA-256 객체 613개로 현재 이용 가능한 757개 파일을 `input-bundle-context-pinned`에 고정했다. bundle index SHA-256은 `083e1a86a93346f7d58943c91e3b795fd05638b6bfa04f970cfa588a7cc5b0fa`이며 무결성 및 checkout-current 검사가 통과한다. 같은 경로에서 다른 입력으로 index를 덮어쓰지 못하게 하고 source 변경·객체 손상 회귀를 검사한다. 이것은 데이터 전체를 담은 실행 snapshot이 아니며 planCoverage는 UNKNOWN이다.
- **미완료:** 283 in-scope cell의 실제 worker data/런타임 FederationMap/compiler 조건과 P/E/R 전체 adapter, 48 family·56 transform의 tuple별 의미론 및 전체 삼중 집합 대조가 없다. user-facing 전체 인증 PASS나 zero illegal/missing plan은 선언할 수 없다.

## StepLM protected worker4 증명 누락 및 비수렴 정상화 계획

- **상태:** 직접 실패 사슬 확인·계획 작성 완료. 신규 구현/테스트/benchmark 없음.
- **환경/증상:** P2P2D, worker=4, planning-only의 네 planner 모두 `steplm.dml:132:21`의 `TRead X_global`, `PRIVATE_AGGREGATE`에서 builder 실패. 기존 최종 JAR `805095277f03d4cda0a85278be64c09072c118696f908f00e1f03f62afbf3f28`의 52/56 결과는 변하지 않았다.
- **확인 원인:** reaching TWrite 122·160·163행에는 FED/FOUT/ROW state가 있으나 staging native realization의 worker-pool witness가 없어서 exact CFG replay에 필요한 executable source 관계가 비었다. `NativePlacementContinuity.exactFullRowColumnSlice`는 동적 column과 symbolic NROW를 literal 조건으로 제외한다. privacy 실패는 원본 반출을 막은 결과이며 실제 합법 플랜 부재 증명이 아니다.
- **잔여 원인:** 동적 column 증명 확장 진단 JAR의 CFG 폐쇄 비수렴은 확인됐지만, 반복된 hash만으로 정확한 두 상태/row/authority 교체를 증명하지 못한다. 작은 fixture의 replay/physical/direct/final replay별 정확한 delta로 최초 손실 transfer를 규명해야 한다.
- **해결 계획:** `docs/G009_STEPLM_ROOT_CAUSE_AND_REPAIR_PLAN_2026-09-21.md`에 S0 작은 재현 → S1 전체 행 동적 열 선택의 ROW-axis 보존 → S2 확인된 폐쇄 transfer 최소 수정 → S3 privacy/publication 통합 → S4 StepLM 네 planner 및 동일 최종 JAR 56조건 단회 검증을 정의. 현재 runtime의 index-bound validation을 당연한 전제로 두지 않고 음성 테스트로 확인한다.
- **수정 파일:** 위 신규 계획, `.omx/plans/g009-steplm-root-cause-repair-20260921.md` 포인터, 이 세션 기록. production/test/JAR는 수정하지 않았다.
- **검증/재현 근거:** 기존 final805 네 실패 로그 및 source/facts/dynamiccol probe 로그를 소스와 교차 확인했다. 구체적 경로·행·단일 Docker 명령은 계획 §7에 수록. 전체 56건 재실행 또는 새로운 성공 측정은 하지 않았다.
- **잠재 회귀 위험/감지:** 열 너비 변화를 full geometry 동일성으로 오인, ungrounded loop seed 승인, privacy 후 권한 부활, 잘못된 single-worker ROW 타입 유지. 각각 동적 너비/불일치 backedge/권한 철회/FULL 재분류 회귀 테스트로 감지하도록 계획했다.
- **의사결정 근거:** 런타임이 지원하는 정확한 연산/배치 계약을 표현하고 공통 후보·모든 reaching definition·privacy를 보존한다. selector 정책 변경이나 CFG edge 삭제로 우회하지 않는다.

## StepLM worker4 수리 및 최종 56조건 검증

- **상태:** [실행 보고서](G009_STEPLM_ROOT_CAUSE_AND_REPAIR_EXECUTION_REPORT_2026-09-21.md) 갱신 완료. 최종 JAR `f143c28197921ffb2d0c5a2138912bdb7ccb245898a309fc0e039bd29da27f30`의 56조건 planning-only receipt가 모두 성공했고, GLM 4건 전체 초기 planning은 53.290689261/53.845699407/53.786597195/57.674614112초로 모두 60초 미만이었다.
- **수정:** 동적 전체 행 열 slice의 정확한 ROW-axis witness, loop SCC pinned identity와 proof 의무, privacy/physical closure의 권한 재검사, protected transpose 후 direct realization seed, 엄격한 AggLocal 선호 뷰가 불가능할 때 공통 공간에서 첫 합법 플랜을 택하는 명시적 policy relaxation을 구현했다. Privacy 우회·backedge 삭제·공통 후보 pruning은 하지 않았다.
- **검증:** StepLM 네 planner의 전체 초기 planning은 14.809766494/23.521718765/16.928691582/14.406992680초. targeted 61 test 중 60 실행 통과·기존 skip 1, Maven package와 `git diff --check` 통과. `/grid/3/cofee-lm-sweep-mchoi-20260914/g009steplm-final56-f143/audit-result.json`은 정확한 56-cell 행렬, 고유 run ID, planner enum, worker=4, planning-only, config·log 해시와 GLM 60초 게이트를 재대조해 `PASS`, 오류 0을 기록했다.
- **별도 무효 시도:** 공간 확보 중 `target/lib` 이동으로 한 GLM DP-global 프로세스가 classpath 오류, 이후 중복 `RUN_ID`로 실행 전 거절됐다. 복구 후 고유 ID로 재실행했으며 두 무효 시도는 56행에서 제외하고 로그를 보존했다.
- **남은 한계:** 실제 ML training runtime은 미실행. 기존 single-worker `StepLmPrivateAggregatePlanningContractTest`의 별도 `lmCG.dml:129` 사례 및 독립 전체 plan-space 인증 `UNKNOWN`은 이 4-worker planning-only 결과로 해결됐다고 주장하지 않는다. 단회 시간으로 통계적 가속률을 주장하지 않는다.

## 현재 P/E와 확장 이전 Git 공간의 closed-model 비교 계획

- **상태:** 계획 작성·소스/역사 조사 완료. 이번 요청에서는 production/exporter/runner 구현이나 새 workload 실험을 수행하지 않았다.
- **문제 정의/원인:** 기존 full certificate는 독립 runtime 의미론 R의 미완성으로 UNKNOWN을 강제한다. 사용자는 우선 현재 P/E의 고정된 유한 모델과 확장 이전 Git 공간을 전수 비교하려 한다. 실제 P exporter에는 nondecision candidate owner·derived-FOUT 좌표가 미해결이며, 기존 fixture artifact는 공통 physical identity 대신 raw signature와 blanket UNKNOWN을 저장한다.
- **해결 계획:** [P/E·역사 공간 비교 실행 계획](G009_PE_AND_LEGACY_PLAN_SPACE_COMPARISON_PLAN_2026-09-21.md)에 T0–T6를 정의했다. 독립 R 인증과 별도의 `closed-model-pe-history-v1` 계약, 공통 physical identity, complete native P/E adapter, 버전별 legacy bridge, exact relation/prefix 열거와 coverage verifier, 재개 가능한 병렬 runner, 반례 수정·전체 재검사를 포함한다.
- **Git 기준:** B0=`ffb7be5bd85367156ed9ea86dacbaff4be0f035d`는 realization 확장 직전, B1=`d8fbd30b5476a1ceef460c9f3886381a369ac619`는 G009 completeness 대규모 변경 직전이다. a628은 이미 확장 후다. 현재 C는 dirty/untracked 및 형제 입력·launcher/build까지 해시로 동결한다. 구버전에는 최신 production predicate를 이식하지 않는다.
- **수정 파일:** 위 신규 계획, `.omx/plans/g009-pe-legacy-plan-space-comparison-20260921.md` 계획 사본, 이 세션 기록만. 병행 production 변경은 보존한다.
- **검증:** native explore의 Git boundary/API 조사 및 closed P/E 좌표/validator 조사, 별도 critic의 계약 검토를 반영했다. 경로·Markdown 링크·whitespace를 검사한다. 신규 테스트/성능 결과 없음.
- **잔여/잠재 회귀 위험:** closed는 유한성이지 equality나 단시간 완주 보장이 아니다. B-21의 약 3.25e14 raw 곱, compiler 차이, legacy 언어/연산 미지원, null call-name identity, proof-only 중복 병합을 정확히 처리해야 한다. 고정 applicability와 모든 REQUIRED pair coverage, native/projection identity mutation, 단순 전수와 빠른 관계 경로 대조로 검출하도록 계획했다.
- **의사결정 근거:** P/E native 집합 비교는 R 미완성에 의존하지 않게 하되 native 미해독·미처리 범위를 UNKNOWN→PASS로 이름만 바꾸지 않는다. 현재 P/E는 EQUAL/DIFFERENT, 역사 변화는 SAME/EXPANDED/REDUCED/CHANGED로 판정하고, 실패·미완료 pair를 완료로 세지 않는다.

## P/E·과거 공간 비교 실행: fixture 검증 완료, 전체 corpus 차단

- **상태:** [최종 실행 보고서](G009_PE_AND_LEGACY_PLAN_SPACE_COMPARISON_EXECUTION_REPORT_2026-09-22.md) 기준 `INCOMPLETE`. 현재 보호 fixture 21개의 source-coordinate P/E 비교는 통과했으나 전체 closed-model campaign PASS는 아니다. 실행 계획의 T0–T6 완료 기준은 유지한다.
- **환경/재현:** `scripts/fedplanner/run_plan_space_comparison.sh --jobs 4 --resume`는 catalog 447 cell의 `nativeInputCapture` 283개와 역사 pair 1,698개가 미판정이므로 `preflight.json`을 남기고 exit 2로 정지한다. 현재 source snapshot, baseline 별도 worktree, fixture 원시 결과는 `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/`에 저장한다.
- **문제/원인:** 기존 P exporter의 비실행 함수 템플릿 owner가 가짜 raw 선택축/UNKNOWN을 만들었고, E/P의 native 문자열·HOP ID는 버전 간 같은 물리 plan의 identity가 아니다. 기존 runner에는 역사 `NATIVE_UNSUPPORTED` pair의 증빙/재검사 경로가 없고, 전체 입력 adapter는 B fixture 전용이다. B-21의 P raw 곱은 40,587,440,947,200이며 실제 planning 입력의 raw 곱은 훨씬 커서 단순 전수 shard는 완료 경로가 아니다.
- **수정:** P owner 역할 분류 및 derived-FOUT 감사, E hard-factor 좌표 export, source/ordered-input/value-version 구조 catalog와 현재 P/E 물리 row projector, P exact candidate 관계 열거, SHA 고정 source snapshot, B0/B1 별도 native bridge, 압축·병렬·resume·양방향 diff·독립 certificate 재검사 runner를 추가했다. 역사 applicability ledger는 정확 pair와 SHA 고정 진단을 요구하고, `FULL_CORPUS`는 catalog native input receipt가 없으면 PASS를 거부한다. 과거 bridge는 현재 validator를 이식하지 않고 `NATIVE_AUDIT_ONLY`로 둔다.
- **검증:** 최신 13,920파일 source snapshot에서 표적 Java 45 case 중 44통과/1환경 skip/실패 0, Python runner·semantic gate 36/36, catalog generator 2/2. B-01…B-22 중 B-13 negative 제외 21개 fixture의 P/E 물리 key 56개씩, 양방향 차집합 0을 Python validator로 재확인했다. 독립 B-02/B-21 논리 입력 oracle이 처음에는 B-21 함수 경계 누락을 잡았고 수정 후 2/2 통과했다. B-21 P exact relation의 raw cardinality balance는 40,587,440,947,200=192+40,587,440,947,008+unknown 0. B0/B1 B-01 native audit는 각각 P 256/accepted 1, E 1/accepted 1이나 full physical decode는 불가능해 표현 한계를 유지한다. 기존 full runtime-semantic R certificate는 이 비교에서 PASS로 바뀌지 않는다.
- **잔여 이슈:** 283개 현재 native input/model capture 완료 및 역사 applicability 1,698개 판정, B0/B1 공통 물리 decoder, 수백 자리 planning raw space의 lossless relation, 모든 필수 pair 비교와 재검사가 남는다. 과거 56개 P capture는 production HOP rewrite 이전 모델이라는 결함이 확인돼 전체 증거로 쓰지 않는다. rewrite 후 P1 w1 pilot은 COMPLETE지만 raw 568자리, P2 w1은 보호된 FunOut 배치 오류, GLM w1은 150초 TIMEOUT이다. 오류를 빈 집합/과거 미지원으로 취급하지 않는다.
- **잠재 회귀 위험/감지:** support clause의 대체 증명을 하나의 물리 plan으로 합칠 때 실제 authority/action 차이가 사라질 수 있다. B-21 proof-collapse witness, ordered binding·emitted-action 필드 mutation, P/E 양방향 diff와 독립 reviewer로 확인한다. Source snapshot과 native model hash가 달라지면 cache를 재사용하지 않는다.
- **의사결정 근거:** runtime/privacy/공통 후보를 축소하지 않는다. native predicate의 닫힌 모델 비교와 독립 runtime 의미론 R의 합법성 증명을 분리하고, 미완료 입력·표현은 gate에서 실패 폐쇄한다.
