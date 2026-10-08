# 2026-10-08 세션 이슈

## LogReg 검증된 main 유지 및 workspace 정리 — 게시/정리

- **증상/원인**: 최종 LogReg 추가 후보는 원본보다 평균이 빨라지지 않았다(16.54→16.84초). 원본88d는 이미 main에 있고 원격에는 추가 GLM/StepLM 변경이 있다. 로컬 root 볼륨은 여유152MB로 포화됐다.
- **해결/판단**: 기존 빠른 기준과 최신 main 변경을 유지하고 미채택 후보를 재도입하지 않는다. 최종 보고서/JSON/session 기록만 commit/push한다. 성능 정책·합법 후보·runtime은 변경하지 않는다.
- **검증**: 게시 diff에 src/scripts 변경이 없음을 확인하고 JSON, 수치, Git diff를 검사한다. 기존 후보 검증은 Java1,083PASS/ignore6, Python35PASS 및 실제Docker6회 correctness PASS다. 이 수치는 과거 동결 후보 검증이며 최신 main 재측정이 아니다.
- **정리 계획**: 이 worktree의 target을 grid 볼륨에 압축하고 파일별SHA를 검증한 뒤 제거한다. R3 실험 디렉터리는 같은 bytes의 regular file만 hard link로 통합한다. 모든 source/patch/로그/모델/manifest 경로를 보존하며 다른 worktree와 외부 symlink 대상은 건드리지 않는다.
- **수정 파일**: SESSION_ISSUES_2026-10-07.md, 본 문서, LOGREG_FINAL_CYCLE_2026-10-07_KO.md, experiments/logreg-final-cycle-20261007의JSON.
- **잔여/위험**: 전체10초 목표는 미달이다. 정리된 실험 artifact는 읽기 전용으로 취급하며 재실험에는 새 사본을 사용한다. target은 재빌드하거나 archive로 복구한다. cleanup manifest는 /grid/3/cofee-lm-sweep-mchoi-20260914/logreg-workspace-cleanup-20261008에 기록한다.

## Plan space 생성 비용과 global pruning 분석 문서화 — 완료

- **문제 정의/증상**: pruning을 수행하는데도 planner 이전의 plan space 생성이 오래 걸리는 이유, global에서 동일 조건의 비싼 후보를 미리 제거할 수 있는지, 단계별 시간 복잡도에 대한 설명이 필요했다.
- **환경/조건**: main `276f958efc`의 구현과 테스트 소스, 2026-10-08 원본 main LogReg/L2SVM compile control 및 LogReg W1 JFR 기록을 기준으로 한다.
- **원인 분석**: 정확한 source/support/relocation의 Cartesian 조합 전개와 closure 갱신이 비용을 만든다. 원본 main JFR은 native proof 비교와 상태 인덱스 관련 비용을 보여준다. Global 사전 축약은 비용 패턴 동치 병합이며, 동일 경계 최솟값 선택은 후속 DP 계산에서 수행된다.
- **해결/변경 요약**: 사용자 요청에 따라 설명을 [한국어 분석 문서](FEDPLANNER_PLAN_SPACE_PRUNING_COMPLEXITY_2026-10-08_KO.md)로 저장했다. 안전한 dominance의 충분조건, 공유 의존성 반례, 코드에서 유도한 복잡도와 측정 한계를 구분했다.
- **수정 파일**: `docs/FEDPLANNER_PLAN_SPACE_PRUNING_COMPLEXITY_2026-10-08_KO.md`, 본 문서.
- **검증 결과**: 문서 링크 16개 대상의 존재, 주요 소스 줄 번호 범위, 원본 JFR sample 수, 조합 예시의 산술, code fence와 공백 검사를 통과했다. `git diff --check`도 통과했다. 문서만 변경했으며 새 workload 실행이나 코드 테스트는 수행하지 않았다.
- **의사결정 근거**: oracle·runtime·planner 규칙은 변경하지 않고, 기존 합법 후보 보존 원칙 아래 사전 동치 축약과 dominance의 차이를 설명한다.
- **잔여 이슈**: 실제 compile 성능 문제와 추가 dominance 구현은 해결되지 않았다. 원본 실행은 60초 watchdog 종료이며 global table이 해당 timeout의 원인이라고 단정하지 않는다.
- **잠재 회귀 위험**: 실행 동작 변경은 없다. 이후 코드 변경으로 줄 번호와 구현 설명이 낡을 수 있으므로 문서의 기준 커밋 및 연결된 테스트와 대조한다.

## 입력 공급 조합의 의미와 실제 경우의 수 문서화 — 완료

- **문제 정의/증상**: source, worker pool, partition, relocation, support가 각각 독립적으로 곱해지는 것으로 오해할 수 있어, 실제 product의 축과 후보 수의 의미를 구체화할 필요가 있었다.
- **원인 분석**: 입력별 정확한 공급 binding 목록을 조합하며, 동일 출력 realization에도 여러 support clause가 붙는다. Action 수, 유효 공급 조합 수, 최종 고유 계획 수와 누적 생성량은 서로 다르다.
- **해결/변경 요약**: 사용자 요청에 따라 [입력 공급 조합 설명 문서](FEDPLANNER_CANDIDATE_COMBINATIONS_EXPLAINED_2026-10-08_KO.md)를 저장하고 앞선 복잡도 문서와 상호 연결했다. 설명용 예시, 현재 테스트의 8개 action/4개 계획, 과거 v78 GLM의 누적 counter를 구분했다.
- **수정 파일**: `docs/FEDPLANNER_CANDIDATE_COMBINATIONS_EXPLAINED_2026-10-08_KO.md`, `docs/FEDPLANNER_PLAN_SPACE_PRUNING_COMPLEXITY_2026-10-08_KO.md`, 본 문서.
- **검증 결과**: 관련 문서 3개의 링크 31개, 예시 산술, v78 JSON counter 7개와 GLM 코드 위치, Markdown 공백과 code fence 검사를 통과했다. `git diff --check`도 통과했다. 실행 코드 변경과 새 workload 실행은 없다.
- **의사결정 근거**: 기존 source value와 placement authority를 보존하는 구현을 설명하며 oracle·runtime·planner 규칙은 변경하지 않는다.
- **잔여 이슈**: 과거 진단에는 요청별 axis 길이가 없어 실측값을 단일 a×b×c로 역산할 수 없다. 현재 main LogReg의 후보 수를 새로 측정한 결과가 아니다.
- **잠재 회귀 위험**: 과거 GLM 누적 수치를 최종 고유 계획 수나 현재 main 측정치로 인용하는 해석 오류가 가능하므로 각 counter의 범위와 기준 실행을 문서에 명시했다.

## 두 배치와 여덟 입력 공급 조합 예시 문서화 — 완료

- **문제 정의/증상**: 용어 정의에 이어 하나의 연산에서 실제로 어떤 실행·이동 대안을 조합하는지 단계별 설명이 필요했다.
- **해결/변경 요약**: 사용자 요청에 따라 `Z = U + V`의 source 후보 두 개씩과 목표 배치 A/B를 가정하고, 배치별 네 공급 조합과 support 조건을 [별도 예시 문서](FEDPLANNER_INPUT_SUPPLY_EXAMPLE_2026-10-08_KO.md)로 저장했다. 기존 조합 설명 문서에서도 연결했다.
- **수정 파일**: `docs/FEDPLANNER_INPUT_SUPPLY_EXAMPLE_2026-10-08_KO.md`, `docs/FEDPLANNER_CANDIDATE_COMBINATIONS_EXPLAINED_2026-10-08_KO.md`, 본 문서.
- **검증 결과**: 관련 문서 3개의 링크 21개, A/B 표의 서로 다른 여덟 조합, partition 구간 산술, Markdown 공백과 code fence 검사를 통과했다. `git diff --check`도 통과했다. 실행 코드 변경과 새 workload 실행은 없다.
- **의사결정 근거**: worker와 partition을 하나의 배치로 설명하고, support를 독립적인 추가 곱셈 축으로 취급하지 않는 기존 구현 의미를 따른다.
- **잔여 이슈**: 예시는 모든 경로가 합법적이라는 가정 아래 설명한 것이며 실제 workload의 후보 수를 새로 측정한 결과가 아니다.
- **잠재 회귀 위험**: 설명용 여덟 조합을 실제 전체 plan space의 크기로 오해할 수 있으므로 예시 범위와 조건을 명시했다. 실행 동작 변경은 없다.

## 전체 worker 사용 가정에 맞춘 예시 정정 — 완료

- **문제 정의/증상**: 사용자가 모든 FED 연산은 전체 worker를 사용한다고 명확히 했다. 앞선 A/B 예시는 서로 다른 worker 부분집합을 선택하는 일반 사례여서 이 가정에 맞지 않았다.
- **해결/변경 요약**: 예시 문서를 전체 worker `{w1,w2,w3,w4}`와 하나의 ROW layout P로 수정했다. Source별 직접 사용/업로드의 `1×1×2×2=4` 공급 조합으로 정정하고 기존 조합 문서에도 정책 범위를 명시했다. 위의 두 배치·여덟 조합 기록은 정정 전 설명의 이력이다.
- **수정 파일**: `docs/FEDPLANNER_INPUT_SUPPLY_EXAMPLE_2026-10-08_KO.md`, `docs/FEDPLANNER_CANDIDATE_COMBINATIONS_EXPLAINED_2026-10-08_KO.md`, 본 문서.
- **코드 근거**: `PlacementProgramFacts`는 입력 주소와 구간으로 anchor를 구성한다. `PlacementRelationClosure`는 입력에서 얻은 anchor를 수집하고 같은 pool을 합치며 native 출력 구성은 seed worker를 따른다. 확인한 경로는 임의 worker 부분집합 생성이 아니다.
- **검증 결과**: 네 공급 조합, 전체 worker의 겹침 없는 1,000행 partition, 관련 문서 링크와 공백/code fence 검사를 통과했다. `git diff --check`도 통과했다. 새 workload 실행은 하지 않았다.
- **의사결정 근거**: 사용자 가정에 맞게 설명을 바로잡으며, 합법 후보를 제한하는 planner 코드 변경은 하지 않는다.
- **잔여 이슈**: 일반적인 자료구조가 서로 다른 pool을 표현할 수 있다는 사실과 실제 workload가 추가 pool을 생성한다는 주장은 다르다. 전역 전체-worker 불변식을 모든 경로에서 검증한다는 결론까지 내린 조사는 아니다.
- **잠재 회귀 위험**: 전체 worker 고정을 ROW/COL/BROADCAST나 실제 partition metadata의 동일성으로 오해하지 않도록 구분했다. 실행 동작 변경은 없다.

## 실제 DML 기반 후보·support 생성 재현 — 완료

- **문제 정의/증상**: 가정으로 만든 조합 설명만으로는 source·전송·support가 어떻게 증가하는지 확인하기 어려웠다. 사용자가 실제 소스코드로 작은 스크립트를 실행하고 답변을 문서 파일로 남기도록 요청했다.
- **환경/조건**: main `276f958efc91477e3d0e8a2a492929455dd77227`의 검증된 JAR을 사용했다. JAR SHA256은 `2ece4ec7ce5e6866ad10563c454bdc7cd0191ab17268c12a62433f42b5e0eb61`이며 기존 baseline build-validation 및 staged JAR과 일치한다. A/B 모두 전체 worker 두 개와 같은 ROW 8×2 입력, privacy는 PRIVATE_AGGREGATE다.
- **해결 요약**: 작은 Java 진단 프로그램이 실제 parser/HOP rewrite/`NeutralPlacementGraphBuilder.buildAnalysis()`를 호출한 후 rule, realization, support binding, exact proof signature, 누적 work counter를 JSON으로 출력한다. source privacy metadata만 기존 테스트 hook으로 제공하며 네트워크 없는 Docker에서 실행한다. production 후보 생성 로직은 변경하지 않았다.
- **재현 절차**: `scripts/fedplanner/run_LAN_docker.sh --plan-space-example --script scripts/fedplanner/examples/plan_space_aggregated.dml --engine-target /home/mchoi/w1357-stage-main276-20261008T1025Z/systemds/target --output-dir <새로운 home 경로>`로 실행한다. 집계 전 예제는 `plan_space_private.dml`로 바꾼다. 정확한 명령과 출력 해설은 `docs/FEDPLANNER_EXECUTED_PLAN_SPACE_EXAMPLE_2026-10-08_KO.md`에 있다.
- **관측/원인 분석**: 집계 예제의 C는 rule 4개, realization 19개, support 151개다. 그중 S37은 source 두 개와 DIRECT/두 anchor별 RELOCATION의 결합으로 입력별 binding 5개씩을 가지며, 최종 25개 clause가 완전한 5×5 product임을 확인했다. 동일 endpoint에서도 exact anchor/action identity가 다르고 source BROADCAST 1×2와 relocation 기준 ROW 8×2 anchor의 exact layout 비교가 달라 추가 relocation 경로가 남는다. 집계 전 예제의 후속 abs는 앞 support를 복사하지 않고 S20/S21 realization을 참조한다.
- **수정 파일**: `scripts/fedplanner/PlanSpaceExample.java`, `scripts/fedplanner/run_plan_space_example.py`, `scripts/fedplanner/run_LAN_docker.sh`, `scripts/fedplanner/examples/plan_space_{private,aggregated}.dml`, 위 한국어 문서, `experiments/plan-space-example-20261008/`, 본 문서.
- **검증 결과**: 최종 진단 소스로 Docker 두 예제 모두 exit 0. 집계 예제는 전체 rule/realization/support 29/48/184, 집계 전은 23/25/32. 모든 source/proof/action 참조, support 수, endpoint pool, S37의 전체 product 및 abs 입력 참조를 검사했다. Java 컴파일, Python 구문, dispatcher Bash 구문 검사도 통과했다. 증거는 experiments 디렉터리의 trace/provenance/validation에 보존한다.
- **의사결정 근거**: 실제 합법 후보를 임의로 닫지 않고 production common analysis를 관찰한다. repository의 Docker 전용 실행 규칙과 PUBLIC fixture 제외 규칙을 따른다.
- **잔여 이슈**: 실제 worker의 수치 계산, selector의 전역 최적화 및 비용 dominance를 검증한 실행은 아니다. 큰 workload 성능 문제나 추가 후보 축약은 해결하지 않았다. 진단 프로그램은 직선형 DML만 지원한다.
- **잠재 회귀 위험**: 새 dispatcher 분기를 명시적으로 선택했을 때만 동작한다. production JAR과 진단 소스 API가 달라지면 Java 컴파일/실행이 실패할 수 있다. anchor와 materialized output layout을 혼동하거나 누적 counter를 최종 후보 수로 해석하는 위험은 문서에서 구분했으며 provenance 및 JSON과 대조해 감지한다.

## 이전 데이터 anchor에서 독립적인 목적 배치 설계 검토 — 분석 완료

- **문제 정의/증상**: 사용자가 전체 worker에 ROW/COL/BROADCAST 등 몇 가지 배치로 업로드하면 되는데, 이전 데이터의 anchor별로 후보를 구분할 필요가 있는지 질문했다.
- **원인 분석**: planner의 exact anchor identity에는 배치 출처가 포함되지만 runtime에는 literal worker/range/FType만으로 map을 복원하는 경로가 이미 있다. 따라서 살아 있는 ancestor는 업로드의 근본적인 필수조건이 아니다. 다만 같은 전체 worker와 FType이라도 partition 경계와 worker 대응은 다를 수 있다.
- **해결 요약**: `docs/FEDPLANNER_ANCHOR_FREE_PLACEMENT_DESIGN_2026-10-08_KO.md`에 독립적인 목적 layout, 값 identity, conversion을 분리하는 설계 판단을 정리했다. 기본 세 가지 분할과 기존 실제 layout의 정렬을 보존하고, 동일 목적 layout은 출처에 무관하게 합치는 방향을 설명했다.
- **수정 파일**: 위 설계 문서와 본 세션 기록. production 코드 및 실행 정책 변경은 없다.
- **검증**: `FederatedFoutMaterialize`, `FederationUtils.buildAnchorMapFromKey`, `FEDLocalMaterializeUtil`, `FederationMap.isAligned`, planner anchor/action key 소스를 대조했다. 앞선 Docker 예제의 실측값을 근거로 사용했으며 새 workload를 실행하지 않았다.
- **의사결정 근거**: 합법성의 근거는 실제 worker·partition 명세와 값/실행 조건이다. 이전 데이터의 출처를 동일 목적 배치의 구분자로 유지해야 한다는 runtime 요구는 확인되지 않았다.
- **잔여 이슈**: 제안은 미구현이다. 5×5에서 2×2로의 축약은 목적 layout 동치와 값 재사용 조건이 보존될 때의 설계상 설명이며 변경 후 실측값이 아니다. 전역 비용·공유 및 동적 shape 처리는 구현 검증이 필요하다.
- **잠재 회귀 위험**: 모든 ROW를 하나로 합쳐 실제 구간 정렬을 잃거나, 같은 layout인 서로 다른 값을 합치는 오류가 가능하다. 불균등 partition·worker별 구간 대응·값 버전·변환 공유를 검증해 감지해야 한다.

## 후보 생성 최적화 여섯 단계의 계획 반영 — 완료

- **문제 정의/증상**: 사용자가 중복 원인 분류, 입력 정규화, 조기 pruning, 생성 중 deduplication, delta 병합, factorized support의 여섯 단계를 기존 계획에 반영하도록 요청했다.
- **해결 요약**: `docs/FEDPLANNER_ANCHOR_FREE_PLACEMENT_DESIGN_2026-10-08_KO.md`에 실행 순서·적용 지점·단계별 산출물·완료 기준을 추가했다. anchor 출처에서 독립적인 목적 layout 정의를 2단계에 연결했다.
- **원인 분석/판단**: 925는 기존 trace의 누적 exact 중복 병합 건수다. 세 원인의 비율은 현 계측으로 알 수 없어 1단계 진단 대상으로 남겼다. exact key가 달라 counter에 잡히지 않는 물리적 동등 경로는 별도로 계측한다. 기존 source 충돌 pruning과 product descriptor 재사용은 확장 대상으로 명시했다.
- **수정 파일**: 위 설계 문서와 본 세션 기록. 실행 코드 변경과 새 workload 실행은 없다.
- **검증**: 계획의 여섯 단계, 기존 counter·prefix pruning·descriptor 재사용 코드와의 대응, 문서 링크·공백·code fence를 확인한다. 실제 최적화 검증은 각 단계의 구현 이후 수행하도록 구분했다.
- **의사결정 근거**: 사용자 제안의 순서를 유지하면서 합법적인 선택과 비용 의미를 보존한다. 중복 표현을 없애는 것과 실행 가능한 후보를 제거하는 것을 구분한다.
- **잔여 이슈**: 계획 반영만 완료했으며 여섯 단계의 구현은 미착수다. 925건의 실제 원인별 수치와 최적화 효과는 아직 측정하지 않았다.
- **잠재 회귀 위험**: 무효화 누락, 조기 pruning의 잘못된 배제, factorization의 공동 제약 유실, 비용의 후속 소비자 전가가 가능하므로 각각 전수 관계 비교·전체 재계산 비교·실제 Docker 실행·단계별 비용 측정을 계획에 포함했다.

## 목적 layout 정규화·후보 생성 중복 제거 구현 — 진행중

- **문제 정의/증상**: 같은 worker·실제 출력 구간에 대한 업로드가 원본 파일/anchor 출처별로 나뉘고, 이미 목적 배치에 있는 FOUT 입력도 기준 anchor의 옛 shape와 비교하여 relocation 후보를 만들었다. Closure 반복은 같은 support를 재생성·재병합한다.
- **원인 계측**: strict `276f958efc`에 계측만 추가한 Docker 재실행에서 기존 29 rule / 48 realization / 184 support / 중복 병합 925건을 그대로 재현했다. 925건은 동일 clause 객체 37, 같은 route·revision에서 새 객체 695, 같은 revision의 다른 route 79, 서로 다른 revision의 동일 내용 114로 분류된다. 미분류와 trace overflow는 없다. 695건의 계측 범위는 하나의 product 호출보다 넓으므로 모두 단일 product 내부 중복이라고 단정하지 않는다.
- **변경 요약**: 실제 업로드 후 endpoint/range/FType으로 목적 layout을 정규화하고 source 값·action·scope identity는 유지한다. 불변 product descriptor 재사용에 추가/삭제 delta 처리를 연결한다. 반복 source owner에 대한 미래 domain 충돌을 prefix에서 검사한다. 독립 축과 동일 proof 집합은 factorized support로 표현하고 비교·hash·일부 병합이 곱을 열거하지 않도록 한다.
- **진행 검증**: source 충돌 prefix 예제는 동일 5개 leaf를 유지하며 prefix 18→12, 고정 seed 무작위 250개 product는 전수 결과와 순서가 같다. 초기 정규화 Docker 진단은 전체 support 184→70, C support 151→37, C의 두 입력 공급은 5×5→2×2다. 이 값은 중간 빌드이며 최종 승인 수치가 아니다. rule/state/output-layout/source/실제 전송으로 투영한 관계는 기준과 변경본 모두 동일한 70개다(opaque proof signature 동치까지 증명하는 검사는 아님).
- **발견한 회귀**: `EarlyPrivacyPruningLegalSpaceParityTest`의 함수/loop fixture에서 early/late privacy pruning의 support가 14 대 10으로 다르다. Strict baseline은 통과하며, delta/cache/prefix pruning을 각각 제거해도 차이가 남으므로 layout 정규화 경로를 분리 조사 중이다. Golden hash 변경은 원인·의미를 확인하기 전 갱신하지 않는다.
- **수정 파일**: `PlacementIdentity`, `PlacementCostSemantics`, `PlacementRelationClosure`, `PlacementAnalysis`, `SearchSpaceMetrics`, `FactorizedSupportClauses` 및 관련 회귀 테스트. 별도 진단 probe/runner와 `experiments/plan-space-implementation-20261008/`에 증거를 보존한다.
- **의사결정 근거**: runtime이 지원하는 literal endpoint/range 배치 명세와 동일 값의 직접 재사용을 근거로 표현 중복을 제거한다. 불균등 partition·worker 대응은 보존하며 알 수 없는 shape의 구간을 추측하지 않는다. 기존 privacy/연산 capability/TR·TW 규칙은 유지한다.
- **잔여 이슈**: 위 early/late 관계 회귀 수정, 최종 통합 검증, 실제 worker 수치 실행이 남아 있다. 동일 proof의 독립 Cartesian relation만 압축하며 mixed-action/공동 source 제약은 명시적 관계로 보존한다. 전체 planner가 다항 시간으로 바뀌었다는 주장은 하지 않는다.
- **잠재 회귀 위험**: source/action 객체 authority 손실, 동적 shape나 CFG 증명 누락, delta 무효화 누락, 압축 관계의 잘못된 hash·순서·소유 clause identity다. 전수/증분 관계 비교, 기존 authority 테스트, 실제 Docker runtime audit로 감지한다.

### 최종 검증과 적용 범위 확정

- **상태**: 위 적용 범위 구현·검증 완료. 상세 결과는 `FEDPLANNER_PLAN_SPACE_OPTIMIZATION_RESULT_2026-10-08_KO.md`에 기록했다.
- **회귀 해결**: endpoint 동치만으로 CFG relocation proof를 지우던 변경을 정확한 layout/source authority 경계로 수정했다. Early/late 네 fixture가 일치하고 최상위 legal domain 수가 보존됨을 감사한 후 golden을 갱신했다. Unknown shape는 symbolic target을 유지하며, known shape의 ROW/COL worker 수 부족·multi-worker FULL·잘못된 exact range 등은 runtime layout 생성 불가를 근거로 제외한다.
- **추가 정확성 수정**: FEDFout cache가 uneven target을 balanced key로 재사용하지 않도록 실제 보존 layout signature를 사용한다. 압축 relation의 proof/action/source 객체 authority, canonical hash·순위·K-way union을 검증했다. Receipt rank는 길이별 개수 DP를 group별로 한 번 저장하고 요청한 clause만 조회한다.
- **최종 결과**: 집계 DML의 rule 29→29, realization 48→45, support 184→74, C support 151→37, 대표 product 5×5→2×2. 누적 relocation leaf 214→32, 중복 병합 925→295. 집계 전 DML은 exact proof 경로 보존으로 support 32→42이며 모든 fixture의 표현 수가 줄지는 않는다. 물리적 실행 관계 투영은 두 예제 각각 70개/24개로 기준과 동일하다.
- **검증**: 최종 Java 소스 hash와 일치하는 빌드에서 JUnit 228 PASS, 기존 ignore 3. 실제 Docker DP 3개 workload 모두 CP 수치 fingerprint 일치, runtime conversion 위반 없음, class hash preflight와 model proof 통과. Python/Bash 구문 및 diff/link 검사 통과. 최종 JAR SHA256 `d006cc1bcede08197fd1c1db97421aea3174b3ccfb1bc5d83f28cfe3c45d4b0c`.
- **시간·측정 한계**: 작은 집계 DML의 같은 Docker/probe 세 번씩 교대 실행에서 분석 시간 중앙값 1,244.4→1,143.8ms. 관측 범위가 겹치고 한 쌍에서는 느려져 대규모 workload 속도 개선으로 일반화하지 않는다. Support의 물리적 관계 투영은 opaque proof signature를 제외하므로 기존 authority/CFG 회귀 테스트를 함께 근거로 삼는다.
- **검증 범위 한계**: 전체 Maven build/test는 수행하지 않았다. 별도 `FEDLocalMaterializeUtilTest` 시도 중 host worker localhost:15000 연결이 필요한 테스트 하나는 연결 거부로 실패했고, 전체 통과로 산정하지 않았다. 실제 workload는 모두 `run_LAN_docker.sh`로 실행했다.
- **잔여 확장/위험**: uniform-proof 독립 relation만 압축한다. 혼합 action/proof·상관 제약·구조적 rank group tie는 기존 명시적 처리를 유지하며 임의의 downstream 전체 순회는 여전히 product를 전개할 수 있다. worker registry 신설, 모든 anchor API 제거, 대형 성능 검증, global cost dominance는 미포함이다. 해당 경계를 넘어선 변경에는 정확한 관계 비교와 runtime audit를 다시 수행해야 한다.
- **증거**: `experiments/plan-space-implementation-20261008/`의 trace·snapshot·timing·validation·runtime JSON과 `/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-implementation-20261008/`의 전체 build/runtime artifacts. Commit/push는 수행하지 않았다.


## Oracle privacy 융합 및 factorized downstream — 진행중

- **증상**: closure가 마스킹한 입력을 우회해 generator를 직접 호출하면 protected ABSENT 조합에도 oracle/key 할당이 발생한다. 기존 factorized support도 ExactPhysicalModel의 clause별 alternative 생성과 semantic fingerprint 순회에서 다시 전체 전개된다.
- **원인**: 입력 privacy의 조기 적용이 caller 마스킹에 의존하고, downstream의 categorical domain이 clause 하나당 row 하나를 전제로 한다.
- **해결 계획**: `FEDPLANNER_PRIVACY_FACTORIZED_DOWNSTREAM_PLAN_2026-10-08_KO.md`에 기록했다. authoritative 입력 privacy guard와 emission pruning을 결합한다. 고정 relocation의 독립 product를 입력별 producer 선택과 membership 제약으로 소비하고 선택 후 정확한 clause를 복원한다.
- **수정 예정 파일**: PlacementCandidateGenerator/GenerationPrivacy, PlacementAnalysis/FactorizedSupportClauses, ExactPhysicalModel, ExactPhysicalNativeSupplyRepresentation, ExactPhysicalCostModel, PhysicalSemanticDagFingerprint, 관련 회귀 테스트.
- **검증 계획**: RED→GREEN 직접 generator 테스트, 작은 전수 모델 parity, 100×100 downstream materialization 검사, 관련 Java 회귀 검사, Docker local DP 수치/authority 검사. 아직 이번 변경의 완료나 성능 개선을 주장하지 않는다.
- **의사결정 근거**: 합법성은 기존 privacy 정책과 runtime oracle을 따르고, 압축은 실제 독립 관계와 비용 불변성이 증명되는 경우에만 적용한다. 그 외에는 합법 후보를 유지한 명시적 경로를 사용한다.
- **잔여 이슈/잠재 회귀 위험**: representative clause가 실제 source 선택을 대신하는 오류, 공유 전송 비용 누락, geometry 차이에 따른 비용 변경, joint/CFG 상관 제약 누락을 전수 parity·정확한 receipt·Docker audit로 감지한다.


### Oracle privacy·factorized downstream 최종 구현 및 검증 — 완료

- **해결 요약**: generator의 protected ABSENT_LOCAL prefix를 rule key/oracle 전에 제거하고 기존 emission privacy 검사를 유지했다. 고정 owner/action의 독립 relocation product는 compact consumer alternative와 기존 producer 변수의 membership 제약으로 표현한다. 정확한 source 선택은 최종 owned clause로 복원한다.
- **추가로 발견한 병목과 수정**: PlacementAnalysis 생성자의 authority 검증, semantic fingerprint, derived anchor의 공통 pool 조회, 최종 receipt validator도 전체 clause를 전개했다. 공통 metadata/각 axis option 검증과 selected-only validator로 변경했다. Source/action identity 검사는 유지한다. Native supply는 미선택 source를 deferred로 표시한다.
- **수정 파일**: PlacementCandidateGenerator, SearchSpaceMetrics, PlacementAnalysis, FactorizedSupportClauses, CandidateSelections, JointValueMapRelations, ExactPhysicalModel, ExactPhysicalCostModel, ExactPhysicalNativeSupplyRepresentation, ExactPhysicalSharedSourceEncoding, PhysicalSemanticDagFingerprint, JointPhysicalCostRows, ExactPhysicalSelection 및 관련 테스트. 이전 최적화 변경도 보존했다.
- **표현 검증**: 100×100 product의 논리 조합 10,000개를 consumer header 1개로 표현한다(consumer 전체 domain 2). 분석 생성은 clause 0개, model/cost/Local DP까지 최대 1개, 비대표 조합의 최종 선택까지 2개다. Fingerprint 변경 전에는 동일 검사에서 10,000개를 materialize했고 변경 후에는 0개다.
- **정확성 검증**: 2×3 전체 6개 assignment의 모든 hard factor가 합법이며 explicit/compact의 canonical raw cost bits와 정확한 source/action receipt가 같다. 실제 Local/Exact 최적값도 같다. 같은 축의 LOCAL/FED source 가격 차이가 유지됨을 별도 검사했다.
- **검증 결과**: frozen production 17개 수정 소스 컴파일 통과. 관련 Java 검사 고유 375개 통과, 기존 ignore 3. 통합 실행 중 테스트의 옛 record 필드 목록과 private overload reflection 2건을 수정했고 해당 suite 전체 9개 재실행 통과(실행 정책 변경 없음). Docker DP 정상 3건은 CP 수치 일치, privacy 불법 1건은 계획 거부: 4/4 통과. Model proof/class hash preflight/runtime conversion audit 통과. 최종 소스 SHA256이 Docker에 사용한 소스와 일치한다.
- **기존 실패의 분리**: 이전 final-verified engine에서도 privacy certificate 검사 6건, PCA certificate 1건, hard-factor/native-local structure golden 2건이 같은 방식으로 실패한다. 이번 통과 집계에 포함하지 않으며 전체 Maven suite 통과를 주장하지 않는다. Privacy baseline 실패 로그를 증거 디렉터리에 보존했다.
- **잔여 이슈**: 혼합 DIRECT/RELOCATION, VALUE_MAP/joint/logical 및 상관 support는 명시적 경로를 유지한다. 큰 workload의 wall-clock 개선과 일반 DP의 최악 복잡도 개선은 미검증이다. 비용 fingerprint 스키마는 v3이며 이전 v2 캐시/비교값과 구분해야 한다.
- **잠재 회귀 위험/감지**: representative를 선택된 source로 오인하는 오류, 공유 비용 누락, 불균등 geometry 손실을 비용 차이가 있는 explicit parity와 정확한 receipt 검사로 감지한다. Authority/CFG 회귀와 Docker runtime audit도 함께 유지한다.
- **의사결정 근거**: 기존 privacy/runtime 정책을 변경하지 않고 검사를 생성 경계에 함께 적용했다. 압축 가능성 가드는 합법 후보를 제거하지 않으며 불변성이 증명되지 않으면 원래 표현을 유지한다.
- **증거/재현**: `docs/FEDPLANNER_PRIVACY_FACTORIZED_DOWNSTREAM_RESULT_2026-10-08_KO.md`, `experiments/privacy-factorized-downstream-20261008/validation.json` 및 command/log 파일. 전체 frozen engine과 Docker 산출물은 `/grid/3/cofee-lm-sweep-mchoi-20260914/privacy-factorized-downstream-20261008/`. Commit/push 없음.

## 인덱스·boolean 제약으로 상관 support를 압축할 수 있는지 — 설명 완료, 확장 미구현

- **문제 정의/증상**: 기존 적용 범위 표가 같은 source owner, 혼합 전송, joint/VALUE_MAP, 결합 비용은 원리적으로 압축할 수 없다는 의미로 읽힐 수 있었다. 사용자가 조합 인덱스와 합법성 boolean으로 표현할 수 있는지 질문했다.
- **원인**: 독립 product에 한정한 구현 조건과 일반적인 관계 표현의 한계를 충분히 구분하지 않았다.
- **해결 요약**: 입력별 인덱스·공유 변수·정확한 합법성 factor로 해당 유형들을 표현할 수 있음을 문서화했다. 전체 bitmap은 여전히 product 크기라는 점, 부분 선택의 미결정 상태, 결합 비용과 source/proof 복원 필요성을 함께 설명했다. 기존 결과 문서에 범위 정정과 링크를 추가했다.
- **수정 파일**: `docs/FEDPLANNER_INDEXED_CONSTRAINT_RELATION_DESIGN_2026-10-08_KO.md`, `docs/FEDPLANNER_PRIVACY_FACTORIZED_DOWNSTREAM_RESULT_2026-10-08_KO.md`, 본 문서.
- **검증 방법/결과**: PlacementAnalysis의 eligibility, ExactPhysicalModel의 support/joint factor, ExactCategoricalSolver의 lazy/partial evaluator, hard-factor observation decomposition 및 cost model을 소스와 대조했다. 문서의 인덱스·bitmap 산술과 parity 제약 예시, 상대 링크, code fence, 공백 검사 및 `git diff --check`를 통과했다. Production 변경 및 새 runtime 실험은 없다.
- **의사결정 근거**: 기존 runtime/privacy 합법 관계와 authority를 보존하면서 표현을 일반화한다. 번호 부여나 pairwise 투영만으로 정확성이 보장된다고 가정하지 않는다.
- **잔여 이슈**: 일반화된 relation 및 downstream 이행은 미구현이다. 전체 조합 저장을 없애더라도 solver의 최악 지수 복잡도가 사라지지는 않는다.
- **잠재 회귀 위험/감지**: 미래 구현에서 공동 제약을 잘못 분해하거나 미결정을 false로 취급하거나 공유 비용을 누락할 수 있다. 작은 전수 합법성·비용·receipt parity와 큰 축의 materialization 계측으로 감지한다. 이번 문서 변경 자체는 실행 동작을 바꾸지 않는다.

## 합법인 전체 조합 ID만 저장하자는 제안의 의미 정정 — 설명 완료

- **증상/원인**: 사용자는 합법 판정이 끝난 전체 조합의 인덱스만 저장하자고 제안했다. 응답은 입력별 인덱스의 재조합과 제약식 평가에 초점을 맞춰 제안을 정확히 설명하지 못했다.
- **해결 요약**: 기존 설계 문서 첫 부분에 `{3, 17, 25}` 같은 합법 조합 ID 집합의 저장·비용 비교·선택 복원을 명시했다. 같은 판정 문맥에서는 oracle 합법성 재검사가 불필요함을 분명히 했다. 전체 bitmap 및 factorization은 별도 추가 설계로 구분했다.
- **수정 파일**: `docs/FEDPLANNER_INDEXED_CONSTRAINT_RELATION_DESIGN_2026-10-08_KO.md`, 본 문서.
- **검증**: 문서 변경만 수행한다. 기존 FactorizedSupportClauses의 ordinal 기반 복원과 PlacementAnalysis의 support/proof 참조를 근거로 설명했으며, 일반적인 합법 ID relation이 이미 구현됐다고 주장하지 않는다.
- **의사결정 근거**: 이미 증명한 동일 문맥의 합법성을 재사용하되, 그 증명이 포함하지 않는 전역 조건까지 증명됐다고 확대하지 않는다.
- **잔여 이슈**: 일반적인 합법 ID relation 및 downstream 직접 소비는 미구현이다. 최초 합법 ID 발견 비용은 이 표현 변경만으로 제거되지 않는다.
- **잠재 회귀 위험/감지**: 향후 구현에서 사전 순서 변경으로 ID 의미가 바뀌거나 오래된 privacy/source 판정을 재사용할 수 있다. 안정적인 ID 대응과 판정 문맥별 갱신·복원 검증으로 감지한다. 이번 변경은 실행 동작에 영향이 없다.

## 합법 전체 조합 ID 저장 구현 — 진행중

- **증상/원인**: 독립 product 밖의 support는 합법 조합마다 clause와 입력 목록을 보관한다. 같은 source owner나 혼합 action 때문에 인덱스 기반 표현까지 불가능한 것은 아니다.
- **해결 계획**: `FEDPLANNER_LEGAL_COMBINATION_IDS_PLAN_2026-10-08_KO.md`에 기록했다. 합법 조합 ID와 공유 사전을 저장하고 기존 downstream이 정확한 ID handle을 소비하도록 한다.
- **수정 예정 파일**: IndexedSupportClauses, PlacementAnalysis, PlacementRelationClosure, 모델 소비 경계와 관련 테스트.
- **검증 계획**: 기존 동등성/순서 계약, sparse legality·source/action/proof 복원, explicit/indexed 비용·최적값 parity, Docker DP joint/privacy 검증.
- **의사결정 근거**: 확정된 합법성 판정을 재사용한다. 판정되지 않은 전역 조건이나 authority 검증을 임의로 생략하지 않는다.
- **잔여 이슈/위험**: indexed handle의 equals/hash/identity 혼동, canonical 순서 및 큰 ID overflow, 관계 수명 밖 ID 재사용을 회귀 검사로 감지한다. 완료 검증 전 성능 개선을 주장하지 않는다.

### 추가 요청: 최소 domain 우선 탐색 및 싼 검사 선행 — 구현/통합 검증 진행중

- **증상/원인**: 고정된 입력 순서로 탐색하여 뒤쪽의 작은 domain이 강제하는 제약을 독립적인 큰 domain을 펼친 뒤 적용한다. Oracle 인터페이스도 완전 입력의 판정만 제공했다.
- **해결 요약**: 물리 binding/참조 열거에 dynamic MRV와 같은 source owner의 forward domain filtering을 적용했다. 입력 FType/value-version 등 싼 조건은 pool 판정보다 먼저 검사한다. Oracle에는 보수적인 partial FED 계약을 추가하고, authoritative privacy로 FED가 필수인 경우에 부분 가지를 제거한다. CP가 합법이면 유지한다.
- **검증 중 결과**: 기존 물리 입력 순서에서 prefix 301회였던 fixture는 같은 100개 합법 결과를 103회로 생성한다. 기존/신규 물리 MRV·hash·receipt·fingerprint 검사 33개 통과. Indexed sparse/mixed pipeline 6개 통과. Oracle 부분 판정은 별도 lane에서 weighted·indexing·함수·privacy 회귀를 검증 중이다.
- **수정 파일**: PlacementRelationClosure, PlacementCandidateGenerator, RulesApi/RulesCore/Rulesets, OracleFacade, IndexedSupportClauses 및 관련 테스트.
- **의사결정 근거**: 제거 근거는 기존 exact source 일관성, authoritative privacy, 해당 operation의 FED 불가능 증명이다. UDF placeholder와 전역 joint 조건은 의미를 보존한다.
- **잔여 이슈/위험**: nullable ABSENT와 미할당 구분, shape-proof cache 재사용, 부분 검사 자체의 반복 비용을 추가 점검한다. 전체 wall-clock 성능 개선 수치가 아니라 통제된 탐색 작업량 비교다.

### 합법 ID 저장 및 MRV 최종 통합 검증 — 완료

- **해결 요약**: 확정된 non-product support를 identity 사전과 합법 whole-combination ID로 저장한다. Downstream은 동일 accessor를 통해 dictionary-backed handle을 소비한다. 물리 source/reference 생성은 dynamic MRV와 forward filtering, 보호 FED 필수 입력은 operation별 partial oracle를 사용한다. 싼 FType/value-version 검사를 pool 검사보다 먼저 적용한다.
- **주요 수정 파일**: `placement/IndexedSupportClauses.java`, `PlacementAnalysis.java`, `PlacementRelationClosure.java`, `PlacementCandidateGenerator.java`, `rules/RulesApi.java`, `RulesCore.java`, `Rulesets.java`, `rules/bridge/OracleFacade.java`, storage/MRV/pipeline 회귀 테스트. 자세한 파일 링크와 동작은 `FEDPLANNER_LEGAL_IDS_AND_MRV_RESULT_2026-10-08_KO.md`에 기록했다.
- **검증 결과**: 현재 소스에서 production 22개/test 19개 컴파일. 통합 JUnit 426개 통과, 기존 ignore 3개. 전체 Maven suite 통과를 주장하지 않는다. Docker `run_LAN_docker.sh --joint-boundary-e2e`, planner local에서 branch upload/correlated source/function calls/privacy negative 4/4 통과. Frozen source/test hash가 workspace와 일치한다.
- **작업량/정확성**: 물리 prefix 301→103, 최종 합법 100개 유지. WSIG privacy-mask 이후 oracle 입력 대상 45→18, partial 평가 11회. Sparse 2×3 중 4개 ID만 저장하고 2개 hole 보존. Explicit/indexed 비용 raw bits·Local/Exact 최적값·receipt 일치. 기존 100×100 factorized 경로도 유지했다.
- **검토 중 수정**: null primary 예외, canonical cached handle 밖의 소유권 인정, metadata linear scan, long ID의 불필요한 BigInteger decode, 부분 조합 무제한 캐시를 수정했다. Mutable ShapeHint proof를 섞을 수 있는 완전 oracle 캐시는 제거해 기존 동작을 보존했다.
- **의사결정 근거**: 합법 후보를 임의로 닫지 않는다. Partial FED 불가능으로 가지를 제거하는 것은 authoritative privacy가 FED를 필수로 만드는 경우로 제한한다. UDF placeholder의 완전 판정과 전역 joint/함수 조건을 보존한다.
- **잔여 범위**: ID 압축은 closure publication 경계에 적용되며 모든 임시 clause 생성까지 제거하지 않았다. 부분 oracle는 현재 5개 rule에 제공하며 다른 rule은 기존 경로다. 범용 검사 비용/제거율 학습 scheduler와 대형 workload wall-clock 개선은 미검증이다.
- **잠재 회귀 위험/감지**: 새로운 부분 rule이 UNKNOWN을 불가능으로 취급하거나 CP 후보를 잘못 제거하면 oracle parity·privacy 회귀로 감지한다. source/proof identity·ID dictionary 수명·cost 공유는 storage 및 pipeline parity와 Docker joint 검증을 유지한다.
- **증거/재현**: `experiments/legal-combination-ids-20261008/`의 compile/test/runtime 명령·로그·SHA256·validation JSON. 전체 산출물 `/grid/3/cofee-lm-sweep-mchoi-20260914/legal-combination-ids-20261008/`. Commit/push 없음.

## Closure 임시 Clause와 MRV 이후 공동 제약의 의미 — 설명 완료

- **증상/원인**: 최종 ID 저장과 생성 시점의 객체 비용, 개별 support의 합법성과 여러 연산 선택의 공동 합법성을 구분한 설명이 부족했다. 공유 비용도 합법성 검사와 묶여 혼동을 만들었다.
- **해결 요약**: Clause 생성 후 publication에서 ID로 바꾸는 경로를 설명하고, producer 선택·분기 VALUE_MAP·함수 호출·공유 업로드 예시로 남은 관계를 구분했다. 이미 증명한 동일 조건은 재검사할 필요가 없으며, 공유 비용은 비용 집계 문제임을 명시했다.
- **수정 파일**: `docs/FEDPLANNER_CLOSURE_AND_GLOBAL_CONSTRAINT_LIMITS_2026-10-08_KO.md`, `docs/FEDPLANNER_LEGAL_IDS_AND_MRV_RESULT_2026-10-08_KO.md`, 본 문서.
- **검증 방법/결과**: Closure의 publication·relocation 생성 경로, ExactPhysicalModel의 support/joint 부분 판정, JointValueMapRelations 및 비용 모델의 공유 공급 처리를 소스와 대조했다. 두 설명 문서의 상대 링크 22개·code fence·공백 검사와 `git diff --check`를 통과했다. Production 코드 변경 및 새 runtime 테스트는 없다.
- **의사결정 근거**: 문서 설명 요청에 맞춰 현재 구현과 추가 개선 방향을 구분하고, 예시를 실측값이나 실제 생성 trace로 오인하지 않도록 표시했다.
- **잔여 이슈/위험**: Closure 전 구간의 ID 기반 구성과 모든 공동 제약의 조기 이동을 구현한 것은 아니다. 문서 변경 자체는 실행 동작을 바꾸지 않는다. 후속 구현에서 미결정을 불법으로 처리하거나 공유 비용을 누락하는 위험은 별도 정확성 검증이 필요하다.

## Rule 기반 입력 관계 생성과 압축 소비 — 구현·통합 검증 완료

- **증상/원인**: MRV 이후에도 독립 FType 축마다 exact Oracle를 호출하고 fact를 만든다. 물리 source/action product에도 표현·소비 조건에 따라 명시적 전개가 남아 있다.
- **해결 요약**: 기존 `caps`를 weighted 4개 규칙이 선언한 결정 축에만 적용하고 privacy로 비는 영역을 판정 전에 제거한다. Generator는 허용 rectangle을 직접 순회한다. Closure 동일성 비교에서 product 전개를 제거하고, 정확한 rectangle 포함 관계의 병합과 동일 배치 DIRECT 축의 압축 Cost/DP 소비를 추가했다.
- **수정 파일**: RulesApi/Core/Sets, OracleFacade, PlacementCandidateGenerator, PlacementRelationClosure, PlacementAnalysis, FactorizedSupportClauses, SearchSpaceMetrics, ExactPhysicalModel/CostModel, 관련 테스트 4개, run_joint_boundary_e2e.py와 harness 테스트. 설계/결과는 `FEDPLANNER_RULE_DIRECTED_GENERATION_PLAN_2026-10-08_KO.md`, `FEDPLANNER_RULE_DIRECTED_GENERATION_RESULT_2026-10-08_KO.md`.
- **검증 결과**: 생산 소스 22개/테스트 소스 20개 컴파일, 관련 JUnit 440개 통과(기존 ignore 3개), harness Python 37개 통과. Weighted Docker 전후 각 3회, 기존 공동 source·함수·privacy negative 3개 통과. 별도 PRIVATE_AGGREGATE ROW weighted 사례도 전후 각 1회 통과하고 출력·비용·계획 fingerprint가 일치한다. 전체 Maven suite는 실행하지 않았다.
- **작업량/측정**: 동일 WSIGMOID 18개 합법 tuple에서 완전 Oracle 18→5회, 부분 검사 11→0회. Closure no-op 비교의 100×100 product는 clause 10,000→0개 생성. DIRECT 16개 source에서 최대 2개 clause만 materialize하며 비용 bits·최적값·선택 receipt가 일치한다. Docker compile 중앙값 2.226→1.889초이나 범위가 겹치고 analysis 0.923→0.926초로 동일 수준이라 전체 성능 향상을 확정하지 않는다.
- **의사결정 근거**: 기존 Oracle 실행 의미를 재사용하며, privacy나 runtime 합법성을 완화하지 않는다. 역방향 규칙을 별도 복제하여 정방향과 달라지는 설계를 피한다.
- **잔여 범위**: 허용 FType rectangle 안의 fact/profile/emission은 여전히 tuple별로 구성한다. 일반 union-of-products, 공동 제약 전체의 조기 이동, 대형 workload 성능 개선은 미구현/미검증이다. 최초 보호 weighted 실패 probe는 단일 FULL 배치에서 발생했으며 모든 보호 weighted 연산이 불가능하다는 증거는 아니다.
- **잠재 회귀 위험/감지**: ShapeHint 의존성을 잘못 선언하거나 CP 영역을 누락하면 exhaustive caps/proof·privacy 회귀가 실패한다. 외부 owner/action, holes, DIRECT의 다른 rule receipt는 identity·containment·비용 parity로 확인한다. Runtime fallback과 privacy 정책은 변경하지 않았다.
- **증거/재현**: `experiments/rule-directed-generation-20261008/`에 명령·SHA256·RED/GREEN 로그·성능 결과를 보관한다. 전체 frozen engine/runtime은 `/grid/3/cofee-lm-sweep-mchoi-20260914/rule-directed-generation-20261008/`에 있다. Commit/push 없음.

## Factorized relation 직접 소비 확대 — 지원 경로 구현·검증 완료, 일반화 미완료

- **증상/원인**: Oracle rectangle 뒤의 tuple별 fact 생성, Closure support 검사·source inventory·publication의 clause 전개, solver lazy factor의 Cartesian table 작성이 남아 있었다.
- **설계 근거**: 정확한 CandidateRuleKey를 wildcard로 바꾸면 source/action authority를 잃는다. Scalar CP/LOUT처럼 축별 선택이 실행과 비용에 영향을 주지 않는 영역은 별도 family로 보관하고 최종 선택 때 exact member를 복원한다. 일반 FED 축은 동일 capability만으로 통합하지 않는다.
- **수정 범위**: CpRuleFamily와 generator/analysis/model/receipt 연결, support 삭제의 factor option reverse index, source metadata 색인·proof 필터·publication binding 축 검사, sparse finite-support solver 및 Local/Reduced 경계.
- **검증 중 발견·해결**: Family-only consumer 최종 coverage, family evidence fingerprint 누락, 기존 non-family fingerprint/hash 회귀를 수정했다. 최초 신규 Docker weighted 실패는 최종 projection의 family receipt 조회 누락이었으며 수정 후 5개 사례 모두 통과했다. Canonical member의 문자열 길이-prefix 순서, receipt 등록/rank 갱신, noncanonical exact member 검증, graph-owned state 재결합도 회귀 테스트로 확인했다. FunctionalMap의 기존 전용 실행을 일반 sparse 실행으로 대체하면 큰 시간 회귀가 발생하여 기존 전용 실행을 보존했다. 기존의 큰 cell counter는 물리 메모리가 아닌 logical 수치였으므로 메모리 개선으로 해석하지 않는다.
- **수정 파일**: `CpRuleFamily.java`, `PlacementCandidateGenerator.java`, `PlacementAnalysis.java`, `PlacementRelationClosure.java`, `PlacementSupportRelations.java`, `CandidateSelections.java`, `PlannerCandidateSpaceAudit.java`, ExactPhysicalModel/CostModel/NativeSupplyRepresentation, ExactCategoricalSolver/ReducedSolver, PhysicalSemanticDagFingerprint 및 관련 테스트. 구체적인 경로·설계·제한은 `FEDPLANNER_FACTORIZED_PIPELINE_RESULT_2026-10-08_KO.md`에 기록했다.
- **검증 결과**: 현재 production/test 소스 각각 27개 컴파일. 통합 JUnit 469개 통과(기존 ignore 3개), Python harness 37개 통과. Workspace/frozen source hash 일치, Python syntax와 `git diff --check` 통과. 전체 Maven suite는 실행하지 않았다.
- **Docker**: 지정된 `run_LAN_docker.sh --joint-boundary-e2e`, planner local. Weighted/public+protected, protected ROW weighted, correlated joint, function calls, private negative 5개를 기준선과 after-v2 모두 통과했다. 성공한 4개 사례의 출력·objective bits·assignment·exact candidate receipt·전송 선택이 동일하고 fallback/repair 0이다. Weighted 혼합의 fingerprint 차이는 WSLOSS 한 occurrence의 `CAPTURED_RULE→CP_RULE_FAMILY` 표현 변경이며 53개 최종 receipt는 모두 같았다.
- **생성량**: 넓은 입력 축의 실제 buildNode 테스트에서 논리 tuple 2,401개와 Oracle 호출 6회는 유지하고 exact fact 2,401개를 686개 + family header 5개로 바꿨다. Family tuple 1,715개는 model 소비 전 복원 0개다. 100×100 support 검사에서는 clause 0개·dependency option 200개다. 실제 weighted Docker의 family는 크기 1이어서 대규모 후보 감소를 관측한 workload는 아니다.
- **시간·메모리**: Docker 단일 측정의 compile은 weighted 혼합 2.287→2.136초, 보호 ROW 1.958→1.787초, 공동 source 3.510→3.133초, 함수 3.799→2.910초였다. 반복 측정의 유의한 개선을 주장하지 않는다. 전체 컨테이너 최고 관측 메모리는 879,964,979→891,184,742B로 증가하여 전체 메모리 감소는 입증하지 못했다. Host JVM microbenchmark는 진단용으로만 보관하고 Docker 성능 근거로 채택하지 않는다.
- **기존 실패 분리**: `ExactPhysicalRealizationSupportFactorCacheTest`의 hardcoded SHA assertion은 동일 원본 테스트를 기존 frozen 엔진에 실행해도 실패한다. 이 baseline 실패는 해결하지 않았고 통합 469개 성공과 별도로 보고한다. 해당 클래스의 sparse legality semantic test는 통과했다. `fingerprint-baseline/`에 양쪽 증거가 있다.
- **의사결정 근거**: 기존 Oracle 실행 규칙·privacy·정확한 source/action/proof authority를 유지했다. 표현 선택의 sparse threshold는 후보 pruning이 아니며 runtime fallback이나 합법성 완화는 추가하지 않았다.
- **측정/재현**: `experiments/factorized-plan-space-20261008/`의 `verify.py`, compile/test 명령·로그·SHA256, `runtime-comparison.json`, baseline/after-v2 runtime 명령·모니터링. 전체 engine/runtime은 `/grid/3/cofee-lm-sweep-mchoi-20260914/factorized-plan-space-20261008/`. Commit/push 없음.
- **잔여 이슈**: 일반 FED rule family, 혼합 source/action의 일반 support selector, 모든 joint/함수 경계의 압축 소비는 아직 완료되지 않았다. Clause-sensitive 소비자와 일반 indexed fallback의 Alternative 전개가 남는다. 전체 end-to-end 목표 완료로 보고하지 않는다.
- **잠재 회귀 위험/감지**: Family 적용 조건을 넓히면서 shape/profile/privacy의 공통성을 가정하거나 source identity를 지우면 의미가 달라질 수 있다. 전수 rule 비교·source/action/proof identity·Local/Exact objective raw bits·Docker protected/joint 음성 사례로 감지한다. Sparse factor의 holes, canonical tie, 조건부 source 의존성을 빠뜨리는 위험은 dense parity와 boundary/reduced 회귀로 확인한다.

## Native VALUE_MAP 증명의 반복 전체 순회 — 수정/검증 중

- **증상**: 동일 main276 기반 canonical LogReg W1의 기본 planner/cache 설정 진단에서 컴파일이 약49분 동안 완료되지 않았다. main-thread dump는 `fixedValueMapPool → resolveFixedValueMapGraph`의 endpoint 비교를 가리킨다. 단일 stack sample이 전체 시간 비중을 증명하지는 않는다.
- **환경**: 별도 source worktree, main276 기준. 같은 Docker/입력/자원/고정 cost profile, production DMLScript CLI, compile_only. timeout 없이 실행하던 진단을 이번 수정 진행을 위해 controller SIGINT로 수동 중단했다. W3는 시작하지 않았고 해당 컨테이너 정리/8개 stage lease 해제를 확인했다.
- **원인**: 쿼리마다 도달 가능한 VALUE_MAP graph를 만들고, grounding과 두 종류의 geometry exactness를 각각 전체 graph 반복 순회로 구했다. alias chain에서는 한 pass에 일부 노드만 바뀌는데도 모든 clause/source를 다시 읽는다.
- **해결**: 같은 resolver revision 안에서 불변 local row를 공유하고, reverse dependency를 따라 변경만 전파한다. grounding queue는 기존 `(sweep round, BFS ordinal)` 순서를 보존하므로 최초로 선택되는 실제 anchor가 바뀌지 않는다. partition exactness와 full-geometry exactness는 서로 다른 bit로 전파한다.
- **적용 원칙**: oracle/runtime/후보/비용 규칙은 변경하지 않았다. 모든 reachable node의 grounding, 모든 clause의 동일 worker endpoint, PART/OTHER의 증명 불가, revision 경계 및 기존 cache 의미를 유지한다. 임의 FIFO나 이미 계산한 하위 pool을 leaf로 치환하는 단축은 anchor 선택을 바꿀 수 있어 사용하지 않았다.
- **수정 파일**: `src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java`, `src/test/java/org/apache/sysds/hops/fedplanner/placement/NativeFixedPoolWorklistTest.java`.
- **검증**: production 수정 전 신규8+기존13=21 tests PASS. 수정 후 같은21 PASS. 관련30개 suite의 JUnit240 tests PASS, 기존 ignore3. 신규 테스트는 고정 seed80개 순환 graph를 과거 BFS/전체 순회 oracle과 비교하며 pool 객체 값, 두 exactness flag, 질의 순서/cache, revision 변경, 늦게 grounding되는 anchor를 포함한다. 추가된 결정론적 작업량 테스트3개를 포함해 신규11 tests, offline Maven package47 tests PASS/BUILD SUCCESS. 101-node/102-edge dynamic diamond에서 decoding101, activation101, grounding102, geometry103, false 전파102회를 확인했다. 실제 Docker control은 후속 기록한다.
- **잔여 이슈**: 이것은 공통 분석의 첫 번째 독립 개선이다. `enumerateImmediateSupports`의 조합 생성과 ExactPhysicalModel의 clause/input-authority 곱은 아직 변경하지 않았다. 이를 제거하려면 관계 표현·물리 변수·공동 비용·선택 receipt를 함께 이관해야 하며, 단순 lazy list를 완성으로 간주하지 않는다. 20초 달성 여부는 아직 미확인이다.
- **잠재 회귀 위험/감지**: round/BFS 순서 차이로 anchor가 바뀌거나, 별도 선택 가능 clause의 미grounding cycle을 잘못 허용할 위험. frozen oracle 및 명시적인 R->[A,B], A->X, B=b, X=a 테스트로 탐지한다. revision을 넘어 공유되는 transitive 결과는 추가하지 않았다.
- **계획/근거**: `/grid/3/cofee-lm-sweep-mchoi-20260914/factored-support-20261008/PLAN.md`, `evidence/`; 이전 중단 진단은 `../logreg-production-default-20261008T1225Z/STOP_RESULT.md`.

### 별도 owner 객체 사이의 decoded-row cache 오염 — 수정

- **증상/원인**: 초기 변경의 구조 동등성 기준 cache가 동일 값이지만 다른 객체인 CompiledHopKey의 증명 실패/성공을 공유할 수 있었다. 원래 candidate-fact 조회는 owner identity 기준이며, 원래 내부 BFS는 다른 질의의 결과 cache를 참조하지 않았으므로 새 회귀였다.
- **해결**: decoded-row cache를 exact owner identity별로 나누고 그 내부에서만 reference 구조 동등성을 재사용한다. 기존 outer 결과 cache 의미는 변경하지 않는다.
- **검증**: foreign C 질의 뒤 canonical R->C가 실패하는 regression은 원본 main PASS / 수정 전 후보 FAIL을 먼저 확인했다. 역방향(정상 C의 cache가 foreign C에 권한을 빌려주는 경우)도 함께 검사한다. 최종 테스트는 신규12개, fixed-pool 관련25개 PASS이며 fresh offline Maven package48개 PASS/BUILD SUCCESS다. 최종 source hash는 6838409912094496101dad1aeb18827994574512a3e0254389e5b030c27b634f다.
- **원칙/위험**: worker endpoint가 같아도 분석의 owner identity를 공유하는 것은 아니다. 구조 cache key가 identity-owned 권한 경계를 넘지 않도록 검사하며 두 방향 회귀 테스트로 재발을 탐지한다.

### 최종 검증/성능 상태

- 최종 fresh offline Maven package48 PASS/BUILD SUCCESS, fixed-pool25 PASS.195builtin byte 일치, production delta는 NativePlacementContinuity.java 하나다. 독립 리뷰 최종 APPROVE.
- LogReg LAN DP-local W1/W3는 같은 DML/XML에서 각각 성능검증60초 watchdog rc124로 공통 분석 미완료. process wall61.021/60.919초는 완료 시간이 아니다.20초 목표 미달, 전체 compile 개선율 미확인.
- bounded JFR1회에서 canonical text comparison top784/3818 samples, native witness equality386, string hash347, identity-map clear269; pruning 포함684, proof graph 생성 포함333. 이번 fixed-pool 경로는9 samples로 앞60초의 주된 비용을 해결한 것은 아니다. 단일 late stack에 대한 최적화와 전체 병목 해결을 구별한다.
- 정식2회/진단1회 모두 cleanup과 stage lease 해제 완료. Runtime 미실행, commit/push 없음.
- 다음 구현 범위는 공통 support 관계와 physical/DP 변수 표현을 연결한 factorization이다. 현행 support products/ExactPhysicalModel expansion은 남아 있으며 완료로 보고하지 않는다.
- 보고서: `docs/FEDPLANNER_SHARED_SUPPORT_PROGRESS_2026-10-08_KO.md`. benchmark harness3파일은 baseline에서 byte-identical로 가져온 것이고 이번 수정에서 evaluator 정책을 바꾸지 않았다.


## origin/main fetch 및 작업 브랜치 병합 — 완료

- **상태/환경**: `refactor/factored-support-20261008`에서 `origin/main`을 fetch하고 `276f958efc → e468797556` fast-forward 병합했다.
- **문제/원인**: 기존 미커밋 변경을 임시 stash한 뒤 복원할 때, 양쪽에서 같은 세션 문서 끝에 추가한 기록이 충돌했다. 코드 충돌은 없었다.
- **해결/수정 파일**: 이 문서의 upstream 추가 기록과 기존 로컬 추가 기록을 모두 보존했다. 다른 기존 수정 파일과 미추적 파일은 원본 bytes 그대로 복원했다.
- **검증 방법**: HEAD와 fetch한 commit 일치, unmerged index 없음, 기존 6개 비문서 파일 SHA256 일치, 이 문서의 양쪽 추가분 포함, `git diff --check` 확인. 빌드/테스트는 이번 Git 동기화 요청에서 실행하지 않았다.
- **잔여 이슈/잠재 회귀 위험**: 새 upstream 코드와 기존 미커밋 구현의 실행 호환성은 후속 테스트가 필요하다. 기존 pruning 계획은 이전 HEAD 기준이므로 구현 착수 전 새 factorization과 대조한다.
- **의사결정 근거**: 합법성/oracle/runtime 규칙은 별도 수정하지 않고 요청된 upstream 동기화와 기존 작업 보존만 수행했다.
- **복구 자료**: `/home/mchoi/.omx/merge-backups/dp-origin-main-20261008-kjzf2hm8`에 원본 파일, patch, manifest와 stash OID를 보존했다.

## Local/Global 인증 비용 pruning 확대 — 구현 및 한정 검증 완료

- **문제/원인**: Local support/quotient 및 Global dense/sparse/dyadic 합산은 같은 separator 상태에서 이미 확보한 best를 이용한 인증 비용 cutoff가 빠져 있었다. 다른 boundary 상태를 현재 비용만으로 삭제하면 exact message/lower/witness 계약이 깨지므로 같은 상태의 계산 생략만 추가했다.
- **해결/수정 파일**: `ExactCategoricalSolver.java`에 인증된 prefix와 suffix-minimum 하한을 적용했다. Local cached minimum을 재사용하고 Global은 bucket의 저장 row를 scan한다. Local compact row의 stored/logical index 혼용도 수정했다. 신규 `CertifiedCostPruningParityTest`, `CertifiedCostPruningWorkTest`가 exact/lower/모든 elimination message/witness와 실제 child read 감소를 검사한다.
- **앞단 합법성**: 최신 `e468797556`의 rule-directed/MRV/option-level support를 조사해 재사용했다. 이미 생성 전에 차단되는 불법 후보 guard를 중복 추가하지 않았고, discovery의 late support와 joint 관계는 보존했다. 합법 상태의 비용 동등성 압축을 불법성으로 재분류하지 않았다.
- **검증**: 변경 전 230 fixed-seed 모델 behavior lock 통과. 변경 후 32 suites **270 tests PASS**, Maven package 성공, 마지막 동일 270개 재실행도 성공했다. 신규 19개 중 결정론적 fixture는 Local/Global suffix-only cut과 read 감소를 확인한다. 독립 최종 code review APPROVE, `git diff --check` 통과.
- **기존 실패**: 변경 전부터 존재한 `ReferenceProductPrefixPruningTest` 2건과 `CandidatePrivacyInputPruningTest` 5건은 변경 후에도 같은 method에서 실패한다. MRV 방문 기대와 current-domain pruning certificate/audit provenance 계약의 문제로 분리했으며 테스트를 완화/비활성화하지 않았다. 저장소 전체 테스트가 모두 green이라는 주장은 하지 않는다.
- **Docker 검증/성능**: 공식 `run_LAN_docker.sh --cost-runtime-validation`, 동일 pinned image/worker 2/CPU 4/RAM 8 GiB/input/cost/probe에서 5쌍 + 재측정 5쌍을 실행했다. 총 **120/120 workload PASS**, objective certificate/assignment/action/numeric output 동일, runtime fallback/repair 0. 첫 측정 aggregate compile median +5.19%로 gate에 걸렸으나 추가 5쌍에서 +1.07%, 재측정 모든 case가 +5% 이내여서 지속 회귀는 재현되지 않았다. 범용 속도 향상 보장은 하지 않는다.
- **실행 환경 이슈**: snap Docker가 `/grid` bind source를 읽지 못해 최초 container가 애플리케이션 실행 전 실패했다. 원본 bytes를 자체 `/home` stage에 배치해 해결했고, 20개 성공 run은 SHA256 대조 후 grid로 보존했다. live container 참조가 없음을 확인한 뒤 자체 stage만 정리했다.
- **잠재 위험/잔여 범위**: suffix 준비 scan이 작은 bucket에서는 절약한 read보다 클 수 있다. 큰 standalone DP-Global/LogReg 전체 runtime·peak memory는 미측정이다. 음수/residue/불충분한 인증/Local lower 불일치/Global tie callback에는 새 cutoff를 적용하지 않는다. whole-plan incumbent로 boundary cell을 삭제하는 기법은 별도 계약 설계가 필요하다.
- **기존 작업 보존**: 이전 continuity/benchmark scripts/진행 문서/테스트는 원본 bytes 그대로 유지했다. 이 세션 문서는 기존 내용 뒤에만 추가했다. 새 의존성/공개 옵션/runtime fallback/commit/push는 없다.
- **상세 결과/재현**: `docs/DP_PRUNING_PARITY_2026-10-08_KO.md`, `/grid/3/cofee-lm-sweep-mchoi-20260914/dp-pruning-parity-20261008/`의 baseline, candidate, evidence, docker-pairs, docker-recheck.

## pruning 후속 독립 재검증 — 수정 필요, 코드 미변경

- **증상/원인 1 (HIGH)**: 선택적 Local/Global suffix bound 배열 할당 실패가 기존 정확한 계산으로 복귀하지 않고 `ResourceExhaustedException`으로 종료한다. solver `:2345,:2382–2384`가 승인 계획 P3.6의 자원 부족 시 최적화 미적용 경로를 구현하지 않았다.
- **증상/원인 2 (MEDIUM)**: factor가 없는 합법 변수의 빈 bucket에서 새 counters 계산이 `saturatedMultiply`의 분모 0을 만든다 (`:2507,:3914`). counters 없는 production 경로는 정상이다.
- **검증/재현**: 선택적 할당에만 실패를 주입했을 때 LEGACY는 10을 반환하고 SUFFIX는 Local/Global 모두 실패했다. isolated-variable 계측에서도 `/ by zero`를 재현했다. 별도 3 tests / 3 failures. 실제 heap 고갈 측정이 아닌 오류 경로 주입임을 구분한다.
- **추가 긍정 증거**: 핵심 270 tests 재통과, default SUFFIX lower/infinity/overflow replay 14 tests PASS, raw LEGACY↔SUFFIX 및 subnormal/near-max/105-bit 경계 replay 16 tests PASS. Global 20,000 모델 및 Local 30,000 생성 시도의 유효 모델에서 수치/선택 차이를 찾지 못했다.
- **확장 테스트 한계**: 완료된 178 suites / 958 tests 중 24건 실패·오류. 그중 21개는 변경 전 binary에서도 같은 method가 실패했고, 1건은 test JVM 임시 경로를 grid로 바꿔 디스크 부족을 해소한 후 PASS. 나머지 2건의 timeout 비교는 미확인이다. 별도 Cartesian universe 열거 1 suite는 180초 초과 후 자기 JVM만 중단했으며 통과로 보고하지 않는다.
- **해결 방향/잔여 버그**: 이번 요청은 검증이므로 코드 수정 없이 재현 증거와 필요한 수정 범위를 기록했다. optional bound 준비에서만 자원 예외를 처리하고, 곱셈의 0 인자를 처리하며 영구 회귀 테스트를 추가해야 한다. 비용 검증/산술 오류를 넓게 삼키지 않는다.
- **현재 판정/원칙**: code-reviewer REQUEST CHANGES, architect WATCH. 이전 APPROVE는 최신 판정으로 대체한다. 합법성/oracle/runtime 규칙 변경이나 runtime fallback 없이 최적화 경계만 바로잡아야 한다.
- **수정 파일/증거**: 문서 3개만 갱신했다. 상세 `docs/DP_PRUNING_VERIFICATION_2026-10-08_KO.md`, 증거 root `.../dp-pruning-parity-20261008/verification-20261008T2020/`. 기존 Docker 120회 기록은 SHA256 및 현재 byte-identical solver와의 대응만 재감사했으며 새 Docker 실행/성능측정은 아니다.


## pruning 선택적 자원 경계·빈 bucket 수정 및 실제 planning 측정 — 수정 승인 / 성능 효과 미확인

- **상태**: correctness 수정 승인. 독립 code-reviewer APPROVE, architect 최종 CLEAR. 실제 LogReg/GLM 속도 개선은 확인하지 못했다.
- **증상/원인**: SUFFIX용 Local/Global bound 배열 할당 실패가 전체 exact 계산을 중단했고, 빈 factor bucket의 신규 counter 곱셈이 0으로 나눴다.
- **해결/의사결정 근거**: 선택적 `allocateDoubles` 호출에서만 `ResourceExhaustedException`을 처리해 해당 bound 최적화를 생략한다. 기존 exact 합산을 재사용하고 skipped bucket/준비시간을 계측한다. Global 두 번째 low-word 할당도 포함한다. 곱셈의 0 인자는 즉시 0으로 반환한다. 합법성/oracle/runtime 규칙과 공개 ablation 의미는 변경하지 않았다. Runtime fallback이 아니다.
- **수정 파일**: `ExactCategoricalSolver.java`, 신규 `CertifiedCostPruningFailureTest.java`, 보강 `CertifiedCostPruningWorkTest.java`; 이전 보고서 2개에 최신 판정 링크, 상세 `DP_PRUNING_REPAIR_2026-10-08_KO.md`.
- **실패 재현/검증**: 수정 전 8개 중 5개 실패. 수정 후 raw LEGACY↔SUFFIX high/low/choices/lower/witness, subnormal/near-max/105-bit 및 Local lower≠exact/nonabsorbing-infinity/overflow를 영구 검사한다. 최종 fresh Maven package **33 suites / 283 tests PASS**, 실패/오류/skip 0, BUILD SUCCESS. 필수 할당·validation·산술 오류가 계속 전파됨도 검사한다. 전체 저장소 green 주장은 아니다.
- **실제 Docker 조건/결과**: 공식 `run_LAN_docker.sh --function-boundary-compare`만 사용. 동결 pre-pruning/fixed JAR 전체 추출 classes, 동일 pinned image/비용/입력/4CPU quota/16GiB/10GiB heap, 실제 LogReg·GLM X50000×128 PRIVATE_AGGREGATE W1, Local/Global 각각 A/B 직렬 총8회. 모두 공통 분석 `analysis_begin` 뒤 60초 watchdog 미완료, 완료 receipt 없음, 관찰 OOM 0. 완료 시간·개선율을 산출하지 않는다. 61.29–61.63초 supervisor wall에는 진단/cleanup이 포함된다.
- **진단/잔여 이슈**: 이미지에 jcmd가 없어 별도 official candidate 진단1회에서 SIGQUIT로 stack을 수집했다. 공통 `PlacementRelationClosure` direct-native realization binding에서 관찰됐으며 단일 stack을 전체 CPU profile로 해석하지 않는다. 비용 DP 전 공통 분석이 완료되지 않아 SUFFIX의 실제 workload 효과를 판단할 수 없었다. W3/runtime/production cut 횟수도 미검증이다.
- **환경/보존**: snap Docker의 grid bind 불가와 root disk 부족은 자체 tmpfs stage로 대응했다. 두 JAR 차이는 solver class family뿐임을 독립 검증. 원본 명령/log/cgroup samples/hashes 및 전체 stage tar는 `/grid/3/cofee-lm-sweep-mchoi-20260914/dp-pruning-repair-20261008/`에 보존했다. own container9개 부재 확인 후 own tmpfs만 정리했고 다른 장기 실행 작업은 건드리지 않았다. 기존 unrelated dirty6파일 보존, commit/push 없음.
- **잠재 회귀 위험/감지**: 광범위한 예외 catch로 비용 오류를 숨기지 않도록 failure propagation 테스트를 유지한다. 주입 테스트가 임의 실제 OOM 복구를 보장하지 않으며, suffix scan 준비 비용도 workload별 재측정이 필요하다. 고정 receipt가 있는 완료 run끼리만 시간·memory 효과를 비교한다.

## 공통 Placement 분석 세부 계측 — 계측/병목 확인 완료, 실제 DP 성능은 미측정

- **상태/문제 정의**: LogReg/GLM full compile이 DP 전 공통 분석에서 60초 watchdog에 걸려, pruning 개선 효과를 평가할 수 없었다. timeout을 늘리는 대신 direct binding/candidate/support/합법성 관계의 비용과 반복을 분리했다.
- **환경/재현**: 공식 `run_LAN_docker.sh --function-boundary-compare`만 사용. 실제 `multiLogReg(maxi30,maxii5)`/`glm(moi20,mii5)`, X50000×128 PRIVATE_AGGREGATE W1, 같은 image/cost/input/4CPU/16GiB/10GiB heap. 기존 계측2회, 새 v1 계측2회, 상세 계측 OFF JFR2회, 최종 v2 계측2회, 총8회. JFR55초/watchdog60초 유지. root/grid snap 제약은 자체 tmpfs stage로만 대응했다.
- **변경/해결 방법**: `SearchSpaceMetrics.java`의 기존 opt-in collector에 direct proof consumption/emission canonicalization phase, 사건·cache counters, 실제 candidate route, explicit/factorized relocation 분리를 추가했다. `PlacementRelationClosure.java`, `PlacementCandidateGenerator.java`의 실제 경로에 null-gated 관측만 붙였다. 기본 생산 collector=null, semantic predicate/owner identity/Oracle/DP/runtime 의미 그대로다. 경로 선택용 기존 MRV guard는 순수 helper로만 추출했다.
- **계측 정확성 문제와 해결**: (1) raw opcode label에 변수명/상수값이 들어가 64칸 table이 조기 포화됐다. 진단 label suffix만 제거하고 overflow와 독립적인 fixed route totals를 추가했다. 최종 overflow0. (2) publication counter는 전체 publication이 아니라 memoized native 경로였으므로 명칭을 `MEMOIZED_NATIVE_*`로 바로잡았다. (3) 기존 signature observer의 hashing이 LogReg 계측 ON main sample18.83%를 차지했다. OFF/JFR 대조에서는0이며 생산 병목 우선순위를 다시 판단했다. (4) JFR의 5-frame 제한은 recording이 아닌 export default여서 execution sample을 depth64로 재분석했다.
- **실제 관측/원인**: 최종 LogReg55.082초 snapshot의 direct binding inclusive43.670초(79.3%), proof consumption self15.309초; proof 소비2,336,957회, input 검사4,177,602회. OFF/JFR에서는 direct binding80.31%, support-clause hash22.32%, containsExact21.43%의 main sample. GLM53.521초 snapshot의 CFG replay self23.348초(43.6%); OFF/JFR canonical comparison52.20%. 비중은 서로 다른 부분 관측이며 더하거나 속도 개선율로 환산하지 않는다. 시간/할당에는 계측 비용이 포함되고 할당 bytes는 live heap/객체 수가 아니다.
- **기존 최적화 적용**: 최종 후보 route는 Cartesian1501/4338, execution relation/CP family/MRV0/0이다. 주로 quaternary에 한정된 rule-directed 최적화가 이번 경로에 사용되지 않았으며 direct support는 별도 ordinal-prefix 열거다. 최종 snapshot에는 relocation0이지만 v1GLM의 더 늦은57.503초 snapshot에 compact product50개/logical188/explicit236084가 관측돼 실제 제한된 적용은 확인했다. 두 cutoff를 섞지 않는다.
- **검증/수정 파일**: 새 `SearchSpaceFineGrainedMetricsTest.java`, `CandidateRouteMetricsTest.java`로 bounded counts, reset/live output, 실제 route 및 off/on analysis fingerprint/candidate facts parity를 검사했다. 최종 fresh Maven package **40 suites / 346 tests PASS**, 실패/오류/skip0, BUILD SUCCESS. 이전 pruning numerical/failure/parity도 포함한다. 단독 테스트의 vector module 누락 및 테스트의 route 순서 가정은 수정 후 재실행했고 실패 로그도 보존했다. 독립 code-reviewer APPROVE, architect CLEAR. 전체 저장소 테스트 green 주장은 아니다.
- **artifact audit 문제와 해결**: v2 final manifest가 v1의 ZIP diff 목록을 재사용한 것을 독립 verifier가 발견했다. 실제182entries로 재계산하고 v1목록138entries는 별도 보존했다. 3개 계측 family 외에 기존 solver45class가 debug 재컴파일로 byte차이가 난다. solver source는 작업 전 SHA와 동일하다. javap에서 v2의 추가 mask local을 처음 accumulator로 오독한 것도 바로잡았다. high/low는0이며 차이는 mask local/slot 이동 및 debug metadata다. 최종 Maven 산출물 SHA `5d52d5ecbf5a5bfac236f18a46089e23423050601b4a5691015fce5f794fee42`로 테스트·진단 provenance를 기록했다.
- **잔여 이슈/잠재 위험**: 8회 모두 common analysis 미완료/완료 receipt 없음. 따라서 실제 Local/Global SUFFIX cut·DP시간·전체 속도 향상은 미측정이고 다음 과제로 남긴다. 계측의 observer effect, 장시간 후 counter overflow, table의 per-opcode detail 손실 가능성이 있다. partial event count를 distinct semantic 중복으로 해석하면 잘못이다. collector-disabled JFR 대조와 exact full-context identity/parity tests로 잘못된 최적화 결론을 방지한다.
- **다음 개선/의사결정 근거**: GLM ordered environment 재사용/긴 canonical compare 회피, LogReg immutable support/proof hash 및 exact membership 비용을 우선 줄인다. 이번 턴에는 의미 변경 최적화를 구현하지 않았다. runtime fallback/정책 완화/합법 후보 임의 삭제/timeout 확대 없이 측정과 증거 기반 우선순위만 확정했다.
- **증거/보존**: 상세 `docs/PLACEMENT_ANALYSIS_PROFILE_2026-10-08_KO.md`; `/grid/3/cofee-lm-sweep-mchoi-20260914/placement-analysis-profile-20261008`의 원본 logs/JFR/commands/manifests, 독립 분석, tests. 자체 container8개 부재 확인; stage 전체 tar와 SHA, 5371개 sealed file의 archive/live hash를 독립 대조한 후 own stage만 정리 완료했다. 최종 독립 evidence audit PASS (bounded scope). 기존 dirty 작업을 보존하고 이 문서는 append-only 갱신했다. commit/push 없음.

## Placement 병목 최적화와 training 연산 전수 경로 확장 — 구현/통합 검증 완료, Docker 측정 진행

- **증상/원인**: 기존 LogReg는 immutable support/proof hash·exact membership, GLM은 canonical environment 비교와 ordered-set 재구축에 많은 비용을 썼다. topology cache hit 뒤에도 overlay 객체와 input lookup 작업이 남았고, 기존 determinant 경로는 weighted quaternary에 한정됐다.
- **해결/의사결정 근거**: immutable hash 캐시·identity fast path, 작은 binding 선형 검색/큰 다중 입력에만 bounded index, exact 96-character canonical prefix/변경 없는 ordered-set 재사용, 불변 proof dependency overlay 공유를 적용했다. 전역 합법성/oracle 판단 자체는 완화하지 않았다.
- **후보 생성**: 증명한 13 rule family의 determinant 선언을 추가했다. 나머지는 privacy를 통과한 각 tuple에 fresh exact ShapeHint로 forward rule을 실행하는 exact residual 경로를 사용하며, 완전히 같은 caps/ordered notes/full ShapeProof와 candidate header/profile만 build-local 최대256개로 공유한다. cache 포화는 공유만 중단하며 candidate나 평가를 삭제하지 않는다. right-index anchor/bounds 증거는 identity shortcut을 우회한다. profile inference/예외 전파를 생략하지 않는다.
- **검토 중 발견/수정**: direct input lookup을 작은 proof마다 eager nested-map으로 만들면 회귀할 수 있었다. binding>=8 && requiredInput>=4에만 proof당 한 번 만들고 retained binding65536개로 제한했다. 처음 inventory의 CP_ONLY 분류는 대표 tuple 표본만으로 과도한 주장이므로 제거하고 relation32/exact residual56으로 수정했다. CP-only 관측은 비권위 표본 부가 정보로만 보존한다.
- **수정 파일**: PlacementAnalysis, PlacementIdentity, PlacementRelationClosure, PlacementJointInputAnalysis, NativePlacementContinuity, PlacementCandidateGenerator, SearchSpaceMetrics, Rulesets, OracleFacade; 신규6개 테스트 및 기존2개 계측 테스트 보강; training DML10종/연산 inventory resources.
- **검증**: fresh Maven package 69 suites/563 tests, failure/error/skip0, BUILD SUCCESS. hash/owner identity/collision/zero-hash, canonical legacy differential, overlay, determinant parity, fresh exact residual/full proof/포화, 실제 generator header/profile identity sharing, 계측 reset/live 검증 포함. 독립 code-reviewer APPROVE/architect CLEAR. ML10 fixed DML의 함수·제어 predicate 포함 constructed9717+rewritten3192=12909 occurrences/88 family route 검사.
- **남은 검증/잠재 위험**: 고차 arity는 first-two 7x7 및 나머지 축 perturbation 표본이지 전체 Cartesian 검증이 아니다. inventory는 HOP rewrite까지만 실행하며 ML10 전체 placement closure/runtime privacy 성공 증거가 아니다. residual tuple 열거/forward calls 자체는 줄이지 않으며 모든 연산의 support product factorization을 주장하지 않는다. PlacementProofKey는 accessor/equals/hash/toString 계약을 유지하지만 reflection상 record가 아닌 class다. 96-char prefix는 환경당 메모리 tradeoff다.
- **성능/환경**: 공식 run_LAN_docker.sh의 같은 pinned image/cost/입력/4CPU/16GiB/10GiB heap/60초 watchdog으로 동결 baseline/candidate 비교 진행. 완료 전 speedup/Local·Global DP cut 수치를 주장하지 않는다. root disk/snap grid mount 제약은 자체 tmpfs stage로 대응하며 unrelated workload는 건드리지 않는다.
- **증거**: /grid/3/cofee-lm-sweep-mchoi-20260914/placement-optimization-20261008; PLAN.md, before-manifest.json, evidence/maven-package.log, evidence/artifact-manifest.json. 기존 dirty 파일 보존; 신규 의존성/runtime fallback/privacy 완화/timeout 증가는 없다.

### Placement 최종 Docker 측정 및 한계

- **측정 완료**: 공식 Docker baseline OFF/JFR2회 + candidate OFF/JFR2회 + candidate ON/JFR2회, 총6회. 동일60초 watchdog,55초 JFR. 모두 공통 analysis_begin 이후 미완료/receipt없음/OOM관측0; 자체 container6개 부재 확인.
- **실제 적용**: 마지막 detailed snapshot의 determinant route LogReg651/GLM1600, exact residual850/3091. evidence재사용804/2569, header808/2583, profile795/2907. MRV/CP-family는 여전히0이다. residual route와 Cartesian counter는 겹치므로 합산하지 않는다.
- **Collector OFF JFR**: LogReg3482→3199 main samples에서 support hash22.95%→0%관측, proof hash21.37%→0%관측, containsExact22.17%→0.13%. GLM3725→3204 samples에서 joint-input 아래 canonical comparison48.97%→35.46%. inclusive 중첩·부분 진행 표본이며 전체 speedup으로 환산하지 않는다. 첫 분석기의 canonical category가 존재하지 않는 class명을 사용해0으로 나온 것을 실제 CanonicalTextComparison.compare + joint-input caller 조건으로 수정했고 초기결과도 별도 보존했다.
- **남은 병목/위험**: LogReg direct binding78.02%와 문자열 hash/정규화/graph materialization, GLM canonical fallback이 남는다. sampled cgroup peak LogReg5.903→8.699GB, GLM5.597→5.085GB로 완료 동일작업 비교가 아니며 memory개선은 주장하지 않는다. 전체시간·Local/Global DP효과와 ML10 전체 runtime 성공은 미확인이다.
- **최종 검증/보존**:69suites563tests PASS/BUILD SUCCESS, source reviewer APPROVE/architect CLEAR. 독립 verifier가 626개 frozen source,69 XML,10DML 원본bytes, per-run tree/image/input/cost 및 기존dirty 보존을 대조했다. detailed 결과는 PLACEMENT_OPTIMIZATION_2026-10-08_KO.md와 placement-optimization-20261008 artifact에 기록했다.

### JFR 기반 proof authority 중복 hash 추가 제거 — lifecycle 회귀 수정 후 재검증

- **문제 정의/증상**: v1의 기존 hashCode frame은 사라졌지만 freshly materialized authority String의 첫 hash 계산이 PlacementProofKey 생성자로 이동해 LogReg JFR의 약18%를 차지했다. “hash frame0=총hash비용0”은 잘못된 해석이다.
- **해결/의사결정 근거**: immutable NormalizedText의 정확한 Java String hash를 사용하는 package-private type-safe factory를 추가하고 directNativePublication에만 연결했다. 임의 raw hash를 받는 외부 API는 없고 기존 String 생성자/hash/equals 계약은 유지한다. materialization 자체는 유지한다.
- **통합에서 발견/수정한 회귀**: factory에서 text만 materialize하면 기존 proof.normalizedSignature()의 object-local/global signature cache 및 structural rope release를 우회했다. 실제 directNativePublication regression이 수정 전 실패함을 확인하고 factory 직후 기존 normalization을 호출해 같은 String 재사용·rope release를 복원했다. 두 번째 flatten은 발생하지 않는다.
- **수정 파일/검증**: PlacementIdentity, PlacementRelationClosure, NativePlacementContinuity(accessor visibility/comment), LogRegHashMembershipOptimizationTest. empty/blank/zero/collision/Unicode/unpaired surrogate/long segmented string legacy parity와 실제 publication lifecycle 테스트; isolated affected169PASS. fresh Maven/Docker 및 독립 재검토 진행.
- **잔여/잠재 위험**: 이미 literal로 바뀐 descriptor의 모든 hash를 제거했다고 주장하지 않는다. cached text hash의 exact UTF-16 계약과 publication 후 rope release를 영구 테스트로 감지한다. v1 source/JAR/69suite563test/JFR/report는 candidate-v1와 기존 run 디렉터리에 보존해 최종 후보와 섞지 않는다.

### Authority hash v2의 warm-cache 문자열 중복 — 독립 설계 검토에서 차단

- **증상/원인**: cold factory 경로는 정확했지만 이미 정규화된 proof의 literal(A)를 materialize하면 새 String B를 만들고, 뒤의 normalizedSignature()는 기존 A를 반환해 key가 B를 추가 보유했다. 생성자 structural signature cache hit에도 발생한다. 수치/합법성 차이가 아니라 메모리 회귀다.
- **조치**: architect WATCH를 최신 판정으로 채택했다. v2의 LogReg 진단은60초 종료 후 보존했고 진행 중인 자체 GLM container/supervisor만 중단해 aborted-review.json으로 기록했다. v2를 최종 성능 결과로 채택하지 않는다.
- **해결 방향/검증**: proof-local helper에서 기존 String이 있는 경로는 기존 String constructor를 유지하고, 진짜 cold structural text에만 exact cached hash factory를 사용한다. cold/warm/constructor-cache-hit 실제 publication의 동일 String identity와 rope release를 회귀 테스트로 검사한다. fresh package/Docker 재실행 전 source review를 갱신한다.
- **보존/위험**: candidate-v2에 JAR/source/70suite571tests/manifest를 보존했고 candidate-v2-jfr에 완료·중단 원본로그를 보존했다. transient peak와 누수를 동일시하지 않는다. 추가 캐시·rawhash authority·runtime fallback은 도입하지 않는다.

### Placement 최종 v3 승인/측정 — 70 suites / 573 tests PASS

- **최종 수정/검증**: proof-local helper는 cold 경로에만 segmented hash를 사용하고 warm/structural-cache-hit에는 기존 String identity를 유지한다. 실제 publication 3경로 회귀 테스트와 fresh 70 suites / 573 tests가 failure/error/skip 0으로 통과했다. BUILD SUCCESS 23:10:26, source 재검토 APPROVE / architect CLEAR.
- **최종 Docker 결과**: baseline 2회 + 최종 v3 OFF/JFR 2회 + ON/JFR 2회를 primary 6회로 비교했다. v1 추가 4회와 제외된 v2의 완료 1회/검토 중단 1회도 별도로 보존했다(총 12개 container). Primary는 모두 60초 watchdog에 걸렸고, 공통 분석 미완료/receipt 없음/OOM 관측 0이다. 최종 JAR SHA는 5368fb9e56752215b7d98a033ee7d7a0110315b727960dab0868f58f3e8de466이다.
- **최종 수치**: OFF/JFR LogReg의 main samples는 3,482→3,130이다. Support/proof hash는 22.95%/21.37%→각 0% 관측, containsExact는 22.17%→0.06%, direct binding은 79.67%→73.64%다. 최종 proof-key 생성자는 0.42%, StringLatin1 hash는 2.24%다. GLM은 3,725→3,183 samples이며 joint canonical은 48.97%→45.52%다. v1에서 더 낮았던 GLM 표본을 최종 값으로 대체하지 않았다.
- **실제 새 경로**: 최종 detailed snapshot은 LogReg 55.043초 / GLM 57.438초다. Determinant 651/1,600회, residual 850/3,091회, evidence 재사용 804/2,569회, header 재사용 808/2,583회, profile 재사용 795/2,907회다. Residual은 tuple 열거/forward 평가를 유지하고 불변 payload만 공유한다.
- **남은 위험/범위**: Sampled cgroup peak는 LogReg 5.903→6.161 GB / GLM 5.597→5.679 GB다. 모두 미완료 상태에서 서로 다른 진행 지점을 관측한 단일 A/B이므로 시간·memory 개선율을 확정하지 않는다. Direct binding, proof materialization, NativePoolWitness 비교, canonical fallback이 남는다. 실제 DP cut과 전체 ML10 runtime/privacy 성공은 미검증이다.
- **보존**: Primary/이력별 source/JAR/class-tree/input/image/cost seals를 분리했다. Archive는 498,288,640 bytes이며 SHA는 1f08d7a67fbb28b6588f08f832a8323ec81afb3f253e743b12627cffcbef7ab4다. 기존 dirty 소스를 보존했다. 신규 dependency, runtime fallback, timeout 증가, commit/push는 없다.

- **최종 독립 artifact 감사/정리**: verifier PASS. Frozen source 626개, 70 XML/573 tests, 버전별 JAR↔class tree, baseline→v3 ZIP 차이 321개(의도한 9개 outer class family만), 12개 실행 출처, archive의 sealed 파일 19,597개/JFR 12개를 대조했다. 누락/불일치 0이다. 자체 container 12개가 없음을 재확인하고 검증된 자체 tmpfs stage만 삭제했다. Source 변경 없이 문서와 cleanup evidence만 갱신했다.

## Publication prerequisite — campaign fixture contracts (해결)

- **문제/증상**: origin/main 게시 전 추가 Python 검증에서 campaign 32개 중 1 failure/6 errors. 기존 fixture가 manifest-bound timeout 및 current-reference 계약을 반영하지 않았다.
- **원인/해결**: production 정책을 완화하지 않고 테스트 fixture의 identity/measurement timeout 정책, compile gate manifest, P2 current-reference lease/hash를 현재 계약에 맞췄다. 고정 60초/무제한 정책의 명령 차이와 잘못된 정책·reference 변조 거부를 추가 검증했다.
- **수정 파일**: `scripts/fedplanner/tests/test_run_matrix_campaign.py`, `scripts/fedplanner/tests/test_matrix_current_reference.py`.
- **검증**: publication root 재실행에서 runtime 8 + reference 3 + campaign 35 = **46 tests PASS**. 기존 Java artifact manifest의 source hash 626개는 동일하며 70 suites/573 tests 검증 대상과 일치한다. 증거: `/grid/3/cofee-lm-sweep-mchoi-20260914/placement-bottleneck-drive-20261008/evidence/publication-*-verified.log`.
- **잔여 이슈**: full LogReg/GLM common analysis 60초 timeout은 아직 해결되지 않았다. 게시 후 별도 bottleneck 측정/개선 대상으로 유지한다.
- **잠재 회귀/감지**: mocked runner 검증은 실제 Docker 성능 검증을 대신하지 않는다. frozen `OPERATION_OCCURRENCES.tsv`의 literal 끝 공백 107행은 의미 있는 데이터이므로 보존한다.
- **의사결정 근거**: oracle/runtime/planner 합법성 및 timeout 정책은 변경하지 않고 테스트 계약만 수정했다.

## Post-publication bottleneck drive — 정확한 구조 공유 (진행중)

- **게시 상태**: 기존 검증된 47파일을 `50855b5df4c6a2312c3a12ee8a31415a9d96aa27`로 commit하고 `git push origin HEAD:main`을 수행했다. `git ls-remote origin refs/heads/main` SHA 일치 확인. 이후 변경은 별도 검증 중이다.
- **측정 조건**: 기존 pinned Docker image와 full LogReg/GLM, PRIVATE_AGGREGATE X, 4CPU/16GiB/10GiB heap, JFR55초/watchdog60초 유지. `run_LAN_docker.sh`만 사용한다.
- **원인/관측**: LogReg는 support memo 적중에도 새 public-proof wrapper와 새 canonical binding-list를 만들어 publication memo의 proof identity key가 거의 적중하지 않았다(이전 상세 snapshot 요청 2,159,608/적중 27,808). GLM은 TreeSet 삽입 때 재생성된 환경의 긴 canonical text를 반복 순회한다.
- **변경 요약**: native witness geometry의 exact bounded interning, 정확히 같은 proof prefix 비교 생략. Support template의 불변 binding-list identity를 보존하고 동일 nonempty list 및 seed/output/precision 전체가 일치할 때만 기존 bounded publication memo를 재사용한다. GLM canonical text를 persistent AVL inorder rope로 유지하고 정확한 Map.equals 기반의 bounded axis 공유를 적용한다. 원래 owner/emission identity, lexicographic order, privacy 및 환경 상한은 그대로다.
- **수정 파일**: NativePlacementContinuity, PlacementRelationClosure, PlacementJointInputAnalysis 및 NativePoolWitnessInterningTest/DirectSupportUnionScheduleTest/PlacementJointInputOrderedEnvironmentOptimizationTest.
- **회귀 잠금**: publication memo 수정 전 실제 support-template instantiation 경로 포함 15개 테스트에서 의도한 2개 sharing assertion만 실패했다. 빈 proof의 기존 query-identity 보수적 계약은 유지한다. GLM은 randomized AVL rotation/update 및 delimiter/Korean/astral UTF-16 parity, saturation/clear를 검사한다.
- **검증 이력**: v1 targeted Maven package 성공(159 tests, failure/error 0, repository PUBLIC-only 정책에 따른 skip 1). v1 Docker 두 workload는 여전히 60초 timeout. v2 independent source review APPROVE이며 package/성능 검증 진행 중이다. v1 테스트의 Comparator import 및 v2 empty Map generic inference 컴파일 오류는 바로 수정했다.
- **실험 무효화**: 첫 published-baseline 재실행은 subagent의 짧은 javac/JUnit 작업과 겹쳤으므로 acceptance 성능 비교에서 제외한다. 최종 baseline을 별도로 다시 실행한다. collector ON v1의 긴 String hash 표본 다수는 SignatureAdmissionObserver의 진단 비용이므로 collector OFF 성능과 혼동하지 않는다.
- **잔여 이슈**: 아직 full planning completion receipt가 없다. partial work counters/sample 비중은 speedup이 아니다. 남은 graph overlay/pruning 및 materialization을 계속 측정한다.
- **잠재 회귀/감지**: 캐시 key의 identity authority 누락, AVL 직렬화 순서, pool retention, materialized proof rope 수명. exact negative tests/기존 parity suites/동일-budget Docker와 독립 review로 검증한다.
- **의사결정 근거**: 합법 후보를 제거하지 않고 동일 불변 증거·문자열 구조의 중복 작업만 줄인다. 신규 timeout/정책 flag, fallback, dependency 추가는 없다.

### Post-publication v3/v4 — traversal 반복 및 publication authority (진행중)

- **증상/근거**: v2 상세 LogReg 55초 snapshot에서 graph 25,736개, state 2,469,336개, dependency 50,313,686개를 방문했다. Publication 요청 2,480,151개 중 적중 62,519개로, list identity만 보존하는 v2는 동일 내용을 재생성하는 경우를 놓친다. OFF와 ON 계측은 서로 다른 조건이므로 speedup 비교에 섞지 않는다.
- **v3 해결**: 불변 default topology에만 첫 등장 순서의 distinct successor schedule을 저장한다. 원본 alternatives/빈 row fallback은 유지한다. 모든 graph state가 비어 있지 않은 DAG에서만 dead pruning을 생략한다. 생성/overlay 경로는 기존 동작을 유지한다. GLM rope의 offset=0 whole literal은 길이가 달라도 String.compareTo를 활용하되, 비교값이 길이 차이와 같은 경우 반드시 실제 prefix인지 확인한다.
- **발견한 회귀/수정**: cached negative component가 empty-state counter를 우회했다. 재현 테스트가 수정 전 5개 중 1개 실패했고, negative summary도 dead seed로 계수하도록 수정했다. 실제 NativePlacementContinuity DAG/negative dependency fixture도 함께 검사한다.
- **v3 검증**: 기존 전체 selector 및 추가 suite로 fresh Maven **694 tests, failure/error 0, skip 1(PUBLIC-only 정책)**, BUILD SUCCESS. JAR `270bb380540242aeb378b41aa1bbd1e836b2bc1a9a25bb6b3a91982b27c64e61`; source/patch/test reports를 `placement-bottleneck-drive-20261008/candidate-v3`에 봉인했다. 독립 source review에서 blocker 없음. 같은 60초 Docker를 재측정 중이다.
- **v4 해결 방향**: nonempty proof는 완전한 proof value equality와 모든 binding parent/relocation consumer의 exact object identity를 동시에 확인한 경우에만 기존 publication을 공유한다. empty proof는 기존 proof identity 계약 유지. 해시는 기존 immutable proof cached hash를 사용하며 충돌은 exact equality로 처리한다. 추가 캐시·후보 축소·privacy 완화 없음.
- **v4 회귀 잠금/검토**: copied/reconstructed binding과 relocation authority 테스트를 먼저 추가했고 v3 binary에서 **16 tests 중 의도한 sharing assertion 2개 실패** 확인. 외국 owner/consumer와 seed/output/precision/outer context 구분은 유지한다. production 변경 독립 review APPROVE; fresh green/package 측정은 대기 중이다.
- **잔여 위험/감지**: 기본 schedule을 query-dependent overlay에 재사용하면 안 된다. Negative 결과, cycle, duplicate alternative identity, root rebind, lexical prefix 충돌을 영구 테스트로 검증한다. full planning completion은 아직 달성했다고 주장하지 않는다.
- **의사결정 근거**: oracle/runtime 합법성은 변경하지 않고 정확히 동일한 불변 관계의 순회와 publication 재생성만 줄인다.

## 일반 FED·상관 관계와 LogReg/GLM 검증 — 진행 중

- **증상/원인**: scalar CP 및 독립 support 외에는 rule fact·clause·Alternative를 tuple별로 생성하고, DP quotient projection도 preimage product를 펼친다.
- **해결 계획**: `FEDPLANNER_GENERAL_RELATIONS_PLAN_2026-10-08_KO.md`의 여섯 항목을 구현한다. 조건부 rule header, 상관 support 선택, source 인덱스 기반 physical factor, 정확한 공동 비용, 지연 join/projection을 연결한다.
- **검증 계획**: explicit 합법 row·cost raw bits·Local/Exact·receipt parity와 actual PRIVATE_AGGREGATE LogReg/GLM Docker 비교. 기준선 `e468797556`.
- **의사결정 근거**: 의미가 일정함이 증명된 축만 header를 공유하고, 나머지는 조건부 관계와 exact index로 보존한다. Privacy나 runtime 가능성을 완화하지 않는다.
- **잔여 이슈/위험**: generic family의 profile/emission 의존성 누락, correlated hole 제거 실패, shared cost 중복/누락, canonical tie 변화가 위험이다. 동등성 회귀와 workload 결과로 확인한다.

### 통합에서 확인한 경계와 검증 수정

- **증상/원인**: singleton unary fact를 Closure 후 relation으로 바꾸면 기존 policy selector가 참조하는 active consumer/source identity가 빠졌다. 일반 FED relation의 임의 canonical tuple만 legacy selector에 넘겨도 서로 다른 입력 authority를 잃을 수 있다.
- **해결**: 생성 단계에서 실제 fact를 생략하는 scalar WSLOSS/WCEMM 영역만 게시한다. 비용이 다른 tuple은 Physical Model의 정확한 Alternative fallback으로 유지한다. Legacy selector는 필요한 relation member를 정확하게 전개한다. 이 경로를 완전한 end-to-end symbolic 비용 처리로 보고하지 않는다.
- **관측**: 첫 통합 실행 527건 중 12건 실패를 분류했다. Selector 누락, 독립 fingerprint oracle의 누락된 compact signature, 잘못된 테스트 source ranges, 기준선부터 실패하던 hash 기대값이 포함됐다. 원본 로그는 `experiments/general-factorized-plan-space-20261008/integration-first.log`.
- **기존 기대값**: 동결된 `e468797556`과 수정 엔진에 동일 fixture를 실행해 hard-factor SHA, 구조 SHA, 비용 raw-bit SHA, cost fingerprint가 동일함을 확인한 뒤 오래된 상수만 갱신했다. `baseline-historical-tests.log`, `baseline-hash-probe.log`, `revised-hash-probe.log`를 보존했다. 계산 결과를 근사하거나 검사를 삭제하지 않았다.
- **실제 학습 후 proof 검증 문제**: LogReg의 실행 중 dimension 갱신과 GLM의 정상 dynamic recompile 이후 live Hop으로 계획을 재구성하면 기존 비용/구조가 변한다. Test-only commit observer로 emit 완료 직후 immutable canonical proof를 캡처하고, 실행 뒤에는 동일 result identity·plan hash 및 runtime audit을 확인하도록 수정했다. Baseline에도 같은 diagnostic overlay를 적용한다. Production 구조 검사를 완화하지 않는다.
- **Anchor 회귀 방지**: 동일 worker/range를 가진 서로 다른 값은 동일 source가 아니다. `exactAnchorHopHint`의 완전한 DurableAnchorKey 동일성 검사를 유지하고, 정확한 live hint가 없을 때 `-1`과 기존 action key를 쓰는 의미를 fixture에 반영했다. 같은 배치·다른 값 음성 테스트를 추가했다.
- **검증 원칙**: 실제 성능은 Docker 경로만 사용한다. PUBLIC 단위 fixture의 제한된 동등성 검사는 `PRINCIPLE_REBUTTAL_FACTORIZED_EQUIVALENCE_2026-10-08_KO.md`에 근거와 범위를 기록했다.
- **통합 검증**: production 32개·테스트 34개 컴파일, JUnit 532개 통과(기존 ignore 3개), Python 38개 통과. `ExactNativeLocalAnchorFanoutCostTest`의 오래된 privacy-pruning proof 전제도 baseline과 신규 모두 source domain이 이미 PRESENT FULL뿐임을 확인해 수정했다. 원래 없던 ABSENT_LOCAL에 privacy 증명을 만들어 넣지 않고 exact lookup의 MISSING_FACT를 검사한다. 최종 실행 로그는 `experiments/general-factorized-plan-space-20261008/tests.log`.
- **측정 해석**: `candidateOracleCalls`는 `relationOracleCalls`를 이미 포함한다. 두 값을 더하면 중복 집계다. `factorizedClauses`는 누적 counter이며 retained heap/object 수가 아니다. 전체 컨테이너 관측 메모리와 JVM heap도 별개로 보고한다.

### 최종 기준선 대조에서 발견한 FED family authority 누락 — 수정·재검증 중

- **증상**: PRIVATE_AGGREGATE ROW weighted 실행은 출력·objective bits·선택 배치가 기준선과 같았지만, WSLOSS/WCEMM의 새 family가 선택한 support에서 DIRECT input-0 source binding 및 관련 exact action authority가 빠져 있었다. 같은 구현으로 생성한 canonical proof의 자체 통과만으로 전후 의미 동등성이 증명되지 않았다.
- **원인**: Generator에서 tuple fact를 생략한 뒤, Closure가 원래 수행하던 source/action binding 확정까지 생략했다. 기본 LOCAL emission의 empty support를 닫힌 relation으로 게시하면 source identity가 보존되지 않는다. 새 relation을 나중에 explicit으로 펼친 참조 테스트도 같은 오류를 공유했다.
- **조치**: 공통 header가 정확한 입력 support를 소유하지 않으면 생성 생략을 허용하지 않는다. 기존 Closure가 확정한 권위를 활용하는 제한된 압축 경로를 검토·구현한다. 독립 기준선의 selected input authority, support bindings, proof keys를 구조적으로 비교하는 fail-closed 도구 및 음성 회귀를 추가했다.
- **근거/회귀**: 수정 전 weighted 비교는 이 새 authority comparator에서 실제 실패했다. 수정 전 hard factor 65→63 및 support 54→48 감소는 최적화 근거로 사용하지 않는다. 당시 532개 JUnit 통과와 runtime 출력 동등성은 이 누락을 검출하지 못했으므로 최종 수정 소스에서 다시 검증한다.
- **한계**: 일반 FED의 source/action 생성이 exact rule fact에 의존하는 동안, Generator→Closure 전체의 fact 생성 제거가 완료됐다고 보고하지 않는다. 생산 경로에서 의미 보존이 입증된 압축만 유지한다.

### FED authority 회귀 수정 최종 확인

- **해결**: 미검증 FED header가 tuple 생성을 생략하는 분기와 Closure 우회 publication을 제거했다. 닫힌 exact emission 객체를 공유하는 행만 hash index·축별 sweep으로 병합하며, region 수가 줄지 않으면 explicit을 유지한다. 다른 support·derived action·transient endpoint가 참조하는 exact rule key는 변환에서 제외한다. Factorized 축/Indexed dictionary를 사용하여 참조 수집에서 Cartesian 전개를 피한다. 비활성 MMFed family 선언도 제거했다.
- **검증**: 최종 소스 production 32개/test 34개 컴파일, JUnit 533개 통과(기존 ignore 3개). Python harness 38개·authority comparator 7개 통과. 컴파일에 사용한 모든 소스 SHA256이 workspace와 일치한다. 독립 리뷰의 focused 30개도 통과했다.
- **Docker authority**: 최종 보호 weighted는 WSLOSS/WCEMM의 exact input authority, relocation action, DIRECT input0 X_PROTECTED source, support/proof inventory가 모두 기준선과 일치했다. objective bits `4611719720386145812`, cost-surface fingerprint, 52개 Alternative·65개 hard factor·support counter 54개도 일치한다. 수정 전 비교기의 실패 로그는 유지한다.
- **수정 파일**: PlacementCandidateGenerator, PlacementRelationClosure, PlacementSupportRelations, Rulesets, CandidateRuleRelationTest, CpRuleFamilyPipelineTest 및 probe/비교기.
- **잔여 범위**: 일반 FED fact 생성 감소는 0개다(생성 686개 유지). 실제 weighted는 압축 이득이 없어 explicit이며, live FED relation 압축을 검증했다고 주장하지 않는다. 컴포넌트에서는 2×2 직사각형 압축, sparse hole 보존, 서로 다른 authority 비병합을 검증했다.
- **위험/감지**: 미래 적용 범위를 확장하면 exact source/action identity를 지울 수 있다. 참조 보호와 독립 full-Closure 기준선의 구조화된 selected authority 비교를 유지한다. 출력·비용만 같다는 이유로 의미 동등성을 통과시키지 않는다.

### 최종 LogReg·GLM 측정 및 저장소 복구 — 완료

- **검증**: 최종 동일 frozen engine에서 LogReg proof·성능 및 GLM proof·성능 4건 모두 성공했다. PRIVATE_AGGREGATE 입력, Local planner, 기존 canonical proof·runtime audit 유지. Objective bits·선택 candidate·모델 결과가 기준선과 같고 fallback/repair 0이다. 실제 Docker는 Local이며 Exact 최적값 동등성은 별도 compact/explicit 회귀로 검증했다.
- **단일 성능 관측**: 컴파일 LogReg 24.823644→24.241345초, GLM 30.803939→29.443179초. Planning 전체는 각각 2.56%, 4.02% 감소. Oracle 호출은 7,989/5,821로 전후 같고, Alternative는 5,304→5,295 및 1,774→1,755. Closure support counters는 그대로다.
- **메모리 한계**: 전체 컨테이너 최대 관측 메모리는 LogReg 2,240,899,187→2,860,448,219B(+27.65%), GLM 1,298,153,865→1,406,601,789B(+8.35%). Blocking docker stats 호출 뒤 1초 대기하므로 고정 1초 주기가 아니며 실제 순간 peak도 아니다. 전체 메모리 감소나 통계적인 시간 개선을 입증했다고 주장하지 않는다.
- **디스크 장애/복구**: root 파일시스템 0B 상태에서 workload-comparison.json 쓰기가 중단됐다. 모든 원본은 /grid에 있어 유실되지 않았다. 이번 worktree의 target 빌드 산출물을 /grid의 repo-target-final-local-archive로 보존 이동하고 원래 target에 symlink를 유지하여 약 83MB를 확보했다. JSON은 원자료에서 재생성했고 정상 파싱 및 PASSED 상태를 확인했다.
- **증거**: experiments/general-factorized-plan-space-20261008/workload-comparison.json, verification.json, source-verification.json, final-weighted-authority-comparison.json. 최종 엔진 engine-final-authority-20261008T2135. 자세한 범위와 한계는 FEDPLANNER_GENERAL_RELATIONS_RESULT_2026-10-08_KO.md.

## FED Oracle relation-native 생성과 독립 paired 검증 — 2026-10-08 후속 작업

- **기준선/보존**: 이번 요청 시작 시점의 dirty tree 129개 파일, HEAD e468797556를 별도로 snapshot했다. 기존 작업을 삭제하지 않았고 commit/push하지 않았다. 세 독립 worktree에서 family/API/검증을 나누고 native agent를 순환 배치했다.
- **구현**: 등록된 49개 rule inventory, determinant/raw shape selector/allowed shape fact API, scalar WSLOSS/WCEMM의 생성 단계 relation과 immutable input-indexed emission binding을 연결했다. Physical Model은 relation의 compact support를 소비하고 최종 선택에서 exact fact와 clause를 복원한다. 실제 constructor counter를 추가했다.
- **리뷰에서 수정한 오류**: AggTernary/TernaryElemwise/Replace/Rexpand/CumulativeOffset의 shape 선언 누락, high-arity eager seed 메모리 증가, relation의 relocation 검사 receipt 누락, 선택 action 수집 누락, endpoint-only DIRECT 조건 누락, 한 축 witness의 다른-pool action 누락, family-only action의 최종 projection 소실을 수정했다. Witness는 ABSENT base 및 모든 두 축 투영으로 만들고 기존 binder·source pruning·action projection을 통과시킨다.
- **통합 중 실패와 조치**: 최초 553-test 실행에서 fixture의 CP family/exact row 중첩과 post-CFG FULL closure 회귀가 발생했다. Fixture의 원래 explicit expansion 계약을 복원했다. 독립 축이 없는 shape-qualified Oracle는 압축 이득 없이 eager table만 늘리므로 기존 streaming 경로로 돌렸다. 이 guard로 해당 회귀는 해소됐지만, 실패의 직접 원인 전체가 증명된 것은 아니다. Protected payload에서 relation이 CP region을 미리 제외하고 explicit 경로는 PRIVACY_EXCLUDED fact를 남길 수 있는 inventory 차이를 별도로 기록한다.
- **회귀 검증**: 수정 후 통합 JUnit 557개 통과, 기존 ignore 3개. Oracle/rule 전용 suite와 compact/sparse/mixed consumer parity가 별도로 통과했다. PRIVATE_AGGREGATE protected weighted Docker의 exact DIRECT source/action/proof, objective bits, 전체 candidate inventory 비교도 통과했다.
- **생성량 해석**: 7^4 fixture는 generator의 686 FED exact fact를 생략하지만 Closure에 254개의 pairwise exact witness가 남는다. 전체 fact 생성 0으로 보고하지 않는다. 작은 실제 weighted Docker는 key 50→72, fact 73→90으로 늘고 explicit support 76→69, indexed handle 6→0으로 줄었다. Synthetic 압축을 실제 compilation 개선으로 일반화하지 않는다.
- **잔여 전개**: 일반 FOUT/external source identity, VALUE_MAP/함수 경계, nonseparable cost, Physical Model의 FType tuple별 Alternative, 복잡한 DP join은 exact 경로가 남는다. 큰 family 전체의 end-to-end factorization 완료를 주장하지 않는다.
- **증거**: FEDPLANNER_ORACLE_INVENTORY_2026-10-08_KO.md, FEDPLANNER_ORACLE_NATIVE_API_PLAN_2026-10-08_KO.md, experiments/fed-oracle-native-20261008 및 /grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/evidence. 반복 LogReg/GLM 결과는 최종 보고서에 추가한다.

- **디스크 장애 후 보존 이동**: root 여유가 0B가 되어 Git index 갱신과 GLM candidate의 Java 시작 전 임시 디렉터리 생성이 실패했다. 현재 checkout 14,189개 파일/링크의 내용을 검증하여 `/grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/workspace-preserved`에 보존 이동했다. 기존 `/home/mchoi/w1357-structural-grounding-20261006` 경로는 symlink로 유지하며 이동 전후 Git status와 파일 SHA256이 같다. 약 289MB를 확보했다. 다른 checkout/임시파일은 삭제하지 않았다. GLM 실패 기록은 보존하고 동일한 새 temp 경로로 paired run 전체를 재시도한다.
- **후보 v1 성능 회귀**: LogReg 3 paired run에서 모든 출력/선택/권한이 같고 Oracle 7,989·Alternative 5,295·analysis 객체 생성 수도 같지만, execution region은 203→1,972로 증가했다. Compile 중앙값 ratio 1.0732, analysis 1.0469, optimizer 1.2214였다. 독립 축이 없는 신규 shape-independent declaration도 eager region을 만드는 경로를 찾아 v2에서 streaming으로 돌리는 검증을 진행한다. v1 결과는 삭제하지 않는다.

### Oracle-native final integration and canonical receipt regression

- Added a parsed 16-member WSLOSS relation fixture. Full explicit generation+Closure matched all exact authorities, but Local/Exact CP receipt tie-breaking differed despite equal objective and states. Fixed representation-independent physical ordering and per-axis raw-key canonical inputs; left length-framed policy receipt ordering unchanged. Independent architecture review: CLEAR for this bounded fix.
- Final integrated JUnit: 565 passed (118.388s), existing 3 ignored. Python comparator/harness: 54 passed. Source hashes and logs archived under evidence/integration-final-565-passed.
- Generation broad fixture eliminates 686 FED tuple facts, but temporary Closure pair witnesses and Physical tuple Alternatives remain. Parsed 16-member analysis facts 270→210, clauses 665→420. Do not characterize this as complete end-to-end factorization.
- Final v2 Docker paired performance campaign follows a first v1 campaign with LogReg compile ratio 1.0732 (regression); v2 avoids new relation regions when no varying independent axis exists.

## origin/main 병합과 COFEE worker-count 재탐색 — 진행 중

- **증상/환경**: 동일 COFEE 50K×128, W1 LAN, DP-local baseline은 Analysis486.384초 이후 Cost Surface에서30분 이상 완료되지 않았다. 진단 전환 후 정상 수집한 세 JVM stack에서 `realizationSupportWorkerCount`의 upstream support 재귀가 반복됐다. 이 실행은 intentional diagnostic stop이며 paired 성공 시간에서 제외한다.
- **원인**: root Alternative memo 외에 중간 source aggregate 재사용이 없어 공유 DAG를 경로마다 반복 순회한다. Direct support 자체에도 tuple별 continuity proof publication이 남는다. DML/Y를 원인으로 단정하지 않고 입력을 고정한다.
- **병합**: 사용자 지시로 origin/main `50855b5df4c6a2312c3a12ee8a31415a9d96aa27`을 fast-forward했다. 미커밋173개 파일을 외부 tar/hash 및 stash `873c5d097784205a677a1ba231910b8d5ada2457`로 보존한 뒤 복원했다. 누락0. NativePlacementContinuity/Rulesets/SearchSpaceMetrics/본 문서의 충돌은 양쪽 변경을 유지해 해소했다. 새 commit/push는 없다.
- **통합 수정**: Native fixed-pool worklist와 완전한 identity-owner invalidation footprint를 결합했다. Oracle의 추가 determinant contract와 shape-qualified factorization을 함께 유지했다. Solver의 새 SUFFIX pruning overload가 일반 SupportRelation을 받도록 타입을 정합화했다. 첫 compile에서 발견된 overload 타입 불일치2건은 수정 후 compile 통과했다.
- **검증**: 병합 전 통합582 JUnit PASS. 병합 후 Python campaign/reference/runtime46 PASS; Native fixed-pool12, VALUE_MAP14, direct source projection10, relocation revision1 targeted PASS. 병합 후 전체 확장 Java suite 진행 중. 이 결과를 아직 완료되지 않은 대규모 runtime 성공으로 표현하지 않는다.
- **후속 수정/위험**: 중간 source aggregate는 순환으로 ancestor edge를 건너뛰지 않은 경우만 재사용한다. 별도 reviewer가 structural source key의 owner-identity 충돌을 발견해 차단했으며, identity-scoped key와 반례 테스트를 추가 중이다. 순환/ambiguous0/fallback/derived-FOUT 비용 의미 및 bounded cache를 검증 후 통합한다.
- **의사결정 근거**: candidate/Oracle/runtime/cost 의미를 바꾸거나 합법 후보를 임의로 제거하지 않는다. 동일 권한의 반복 계산과 객체 생성을 줄인다. 실제 생성량·pruning 수·전체 시간은 구분해서 보고한다.
- **증거**: `/grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/evidence/origin-main-merge-50855b5/`, `integration-v3/`; COFEE 상세는 `FEDPLANNER_COFEE_LARGE_VALIDATION_2026-10-08_KO.md`.

### 병합 연동 회귀와 source memo 검증

- 첫 통합659 tests 중7 failure를 보존했다.6개는 새 비용 certificate/suffix minimum 코드가 conditional hard support의 null numeric array를 순회한 문제였다. typed hard relation은0/INF임을 사용해 numeric certificate에서 제외하고 하한0을 유지한다. relation의 feasibility 검사는 그대로 남는다. 기존 sparse elimination의 새 overload도 일반 SupportRelation을 받도록 수정했다.
- 추가된 `globalConditionalHardSupportRetainsCertifiedSuffixPruning`은 LEGACY/SUFFIX의 objective raw bits, 모든 elimination step/backpointer, assignment parity를 검사하고 suffix cut 및 numeric bound cell6개를 확인한다. 관련46 tests PASS, 독립 검토 CLEAR.
- 나머지1개 inventory 차이는 TRead/TWrite의 대소문자 opcode 비교 때문에 dependency 선언이 누락되던 문제와 REPLACE의 불필요한 shape 의존 선언을 수정했다. 전방 실행 규칙은 유지한다. RMEMPTY/rshape의 input0 determinant 관계는 유효한 기존 local 개선이므로 inventory를 새 경로로 갱신했다. 관련41 tests PASS.
- Source worker-count memo는 owner identity로 분리해 complete aggregate만 invocation-local로 최대4096개 보관한다. 순환에 의존하는 결과는 재사용하지 않고 ambiguous exact0과 fallback을 분리한다. reviewer의 최초 structural-key BLOCK을 해소한 뒤 CLEAR 및 lane588 tests PASS.14단 공유 fanout fixture에서 explicit resolver32,766→26회, count4동일.4,100개 source fixture는4,096개만 저장한다. 이 수치는 실제 COFEE 속도 개선으로 일반화하지 않는다.
- 수정 production/test 파일은 root에서 시작·완료 SHA를 검증해 통합했다. origin/main과 합친 전체 suite 및 동일 COFEE LogReg 실행은 후속 진행한다.

### 병합 최종 검증 통과

- Production/test compile 성공, merged suite **666 JUnit PASS**(126.948초), Python32+46 PASS. Compiled source hash와 현재 파일 전수 일치, unmerged path0, diff-check PASS. 전체 Maven suite는 아니다.
- 증거: `evidence/origin-main-merge-50855b5/final-integration-green/validation-result.json`. 새 candidate JAR `84e97e5d4450e7c81443a6200316bc8e97095a8319ada97e09707e803160304f`, overlay4,575 byte 검증 mismatch0.
- 동일 COFEE50K128 LogReg를 candidate-first로 실행해 Cost Surface 완료 여부 및 numeric receipt를 확인한다. 아직 새 대규모 완료 시간·peak·paired 개선율은 없다.

- **실행 준비 오류/해결**: 새 candidate wrapper가 manifest에만 dependency 위치를 기록하고 실제 `target/lib` symlink가 없어 probe compile이 실패했다. runtime 시작 전 오류로 분리하고 wrapper-only 경로 수정·301개 dependency preflight 후 새 run root에서 시작했다. Frozen JAR hash는 그대로다. 추가 profiling 없이 실제 LogReg→GLM 검증을 수행한다.

### 2026-10-09: 일반 MRV 및 구조적 privacy generation 공백

- 실제 merged candidate Analysis476.715초, MRV0/source conflict0/privacy avoided0, Clause21,979,887을 확인했다. 객체 압축 지표를 generation pruning으로 보고하지 않는다. 추가 profiling 대신 재현 테스트와 코드 수정으로 진행한다.
- 원인: 일반 rule에는 partial FED hook이 없어 MRV fallback에 진입하지 않았고, 구조적 privacy projection이 완성돼도 full physical privacy closure 이전의 replay는 same-block seed만 사용했다. Source 검사는 기존에도 있었으나 원래 축 순서로 수행했다.
- 수정: authoritative forward dependency 기반 일반 early feasibility + evidence 재사용, ordinary compiled op에 구조적 privacy projection 전달, direct support MRV 및 identity owner별 forward narrowing. Carrier/CP/UDF 및 exact source/action/proof 의미는 유지한다.
- Direct source regression은 기존 코드에서 prefix 감소 assertion RED를 확인한 뒤 수정했다.80 legal leaves 동일, prefix321→83; random sparse240회 및 identity-owner 반례 parity.119 targeted JUnit PASS(이후 추가된 metrics/empty-domain test는 통합에서 재검증). 기존 vector module 누락으로 난 harness failure는 --add-modules=jdk.incubator.vector로 수정했고, 기존 conflict counter 의미도 option rejection으로 유지했다.
- 일반 MRV24 targeted PASS. Privacy lane 및 전체 integrated regression은 진행 중. 대규모 compile/peak/numeric/receipt 동등성은 아직 완료되지 않았다.

- 첫 generation-pruning 통합은679 tests 중1 failure였다. `ExactPhysicalRealizationSupportFactorCacheTest.flatPrivateAggregateRetainsSameDurableOutputAcrossSupplyRoutes`에서 FULL single-partition proof가 UNKNOWN으로 돌아간 replacement row가 조기 FED 검사로 사라져 기존 Closure의 exact retraction 증명이 누락됐다. Direct MRV만 적용한 독립 engine에서 같은2 tests는PASS(4.258초). UNKNOWN shape evidence를 INFEASIBLE로 확정하지 않고 기존 exact row 경로로 유지하는 수정 검증 중이다. 로그/컴파일 SHA는 `evidence/generation-pruning/first-integration-red/`에 보존했다.
- 추가 privacy certificate 테스트5 failure는 survivor-only replay가 이전 exact unmasked-domain 증거를 제거해서 발생했다. 최초 재사용안의 protected-position/domain-only 비교는 독립 reviewer BLOCK이었다. 현재는 consumer와 모든 protected source의 canonical PrivacyFact 객체 identity가 같고 masked domain이 정확히 같은 경우에만 동일 certificate 객체를 유지한다. 다른 source/value authority 반례를 추가했다. 재사용으로 pruning 수치를 중복 증가시키지 않는다.

- 최종 pruning 통합: **687 JUnit PASS(132.232초)**, COFEE Python helper17 tests PASS.35 main/58 test compile source hash 일치,4579 frozen class hash 일치. UNKNOWN shape retraction 보존 및 exact privacy certificate identity fix를 포함한다. 일반MRV/Privacy/Source MRV 독립 reviewCLEAR.
- 새JAR `83d8ea70497341def4981482fb74c909581ca951ff5c2905a40aa5f96b7cae50`로 동일 COFEE50K128 LogReg 검증을 시작한다. 이전 엔진의 미완료 실행은 supersession marker+정상 owned cleanup 후 보존하며 성공 timing으로 계산하지 않는다. Profiling 없이 실제 generation counters와 phase completion을 확인한다.

- Superseded attempt는 `superseded-validation.json`이 있으면 result가 passed로 남아도 성공 timing에서 제외하도록 report helper를 수정했고, 해당 회귀 포함18 Python tests PASS. 이전 engine attempt의 owned container cleanup 성공을 확인한 뒤 새 engine 실행으로 교체한다.

- 수정본 실제 COFEE 시작 성공(campaign w1357-bounded-cb66bb81880742, attempt01791497648443771201-a50c1fad). 이전 run stage lease release는 BrokenPipe로 receipt가 unproven이었으나 exact owned containers cleanup 성공 후 새 normal acquire가 lease 가용성을 입증했다. 다른 lock을 우회/삭제하지 않았다. 실제 seq4에서 MRV11/check36, privacy projection379/protected34/mask checks34, avoided34/generator reject12. OracleMRV cut/source conflict cuts는 아직0이며 성공한 제거로 과장하지 않는다.

- 실제 pruning v1 Analysis완료490.139초(CPU482.001초). 이전 matched-engine관측476.715초 대비+2.8%이며 paired 전체compile성공결과는 아니다. 생성keys27926→27442, facts227285→222582, Clause21979887→20882097(−5.0%), supportleaf4191721→3942161(−6.0%), allocation261498370024→249704432960바이트(−4.51%, peak아님). MRV241/check881/cut0; source descriptor176111/check0/cut0; privacy avoided125/reject12. 적용공백은고쳤으나 조합전개·시간문제해결로 보고하지않는다.
- 실제 immediateowner가독립인제품은 정적MRV순서로 탐색해 per-prefix owner map/MRVscan을 제거하는 추가경로를 작성했다. Sharedowner경로는기존동적검사를유지한다. 독립reviewCLEAR; targeted test진행중. Cost단일worker증명은별도수정/검증중이며 현재 v1실행에는포함하지않았다.

## 2026-10-09 generation-pruning v2 검증

- 독립 owner 입력은 MRV 순서를 한 번 정해 유지한다. 논리 member 수는 동일하며 공유 owner의 동적 pruning은 유지한다.
- Cost Model에 bounded singleton-worker certificate를 추가했다. 낮은 수준 exact count의 cycle/ambiguous zero 의미를 바꾸지 않고, 유효 worker 수가 반드시1인 Alternative 질의만 재귀 계산을 생략한다.
- 독립 검토에서 anchored derived FOUT, non-FOUT WDIVMM W source, compact W witness/axis 불일치 반례를 발견하고 모두 수정·회귀 테스트했다. Factorized 축과 indexed metadata를 직접 검사하며 budget/authority 불확실성은 기존 경로로 fallback한다.
- 최종 frozen main/test source는35/59개, mismatch0. 통합692 JUnit PASS(143.026초), COFEE helper18 PASS, 별도 reviewer CLEAR. 전체 Maven suite 결과는 아니다.
- Freeze: `evidence/candidate-engine-generation-pruning-20261009-v2`,4582 class/resource files, manifest SHA `9e1066bf4e77cd22d4d209ff06c24207ba884c68374511fbf993d0e50936722b`. v1 actual evidence와 구분한다.
- 실제 COFEE는 새 profiling 없이 동일 DML·Y·privacy·자원으로 검증한다. 이전 미완료 v1은 superseded marker와 로그를 보존하며 성공 시간에 포함하지 않는다.
- 사용자의 병목 질문에는 객체/member 전개 및 경로 재방문을 주된 구조적 문제로 설명했다. 문자열에는 lazy/segmented representation과 identity memo가 이미 있으므로, 문자열만의 실행시간 비중은 근거 없이 단정하지 않았다.

## 2026-10-09 post-v2 metadata 조회 전개 제거

- `NativePlacementContinuity.hasDynamicNativeLayout`은 기존 source/rule/realization 권한 조회를 그대로 유지하고, 최종 support 판정만 저장 표현에 맞게 처리한다. Factorized 공통 witness/exactness와 indexed admitted-row metadata를 읽으며 Clause/handle을 생성하지 않는다.
- Empty product(0축)의 logical size1, 빈 축 거부, indexed unused metadata 경계를 별도 reviewer가 확인했다. Explicit/factorized/indexed parity, NONE/EXACT/DYNAMIC witness, mixed indexed metadata, 실제10×10×10 product의 Clause 생성0 및 빈 explicit list를 검증했다.
- 최종 통합695 JUnit PASS(131.025초), diff-check PASS. Freeze `evidence/candidate-engine-generation-pruning-20261009-v3`, manifest SHA `3e32306b7476a176b9305df52143bf82f22700d1689b9075005763a4abf05f9f`.
- 실제 COFEE는 frozen v2(`w1357-bounded-1979f8d127b04e`, `01791499397504112528-81392061`)로 계속 실행하며 v3로 중간 교체하지 않는다. v3의 실제 workload 성능 효과는 아직 측정하지 않았다.
- 사용자의 병목 질문에 대한 별도 문서: `docs/FEDPLANNER_BOTTLENECK_EXPLANATION_2026-10-09_KO.md`. Oracle FType 관계와 물리 source/support 관계를 구분하고, 문자열의 별도 시간 비중은 미확인으로 기록했다.

## 2026-10-09 실제 v2 실패와 20초 목표

- Actual v2 COFEE LogReg Analysis460.527664756초; keys27442/facts222582/Clause20882097 등 생성량은 v1과 동일. 누적 allocation249236625608B, coordinator cgroup peak7462850560B, OOM event0.
- `COST_WORKER_COUNT_PROOF|certified=true|refs=1135|anchors=6522|clauses=38711|bindings=60002`를 확인했다. 이후 cost preflight의 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 실패해 전체 planning/runtime/numeric 성공은 없다. 소유 container cleanup resolved.
- Raw symbolic support 검사와 실제 ordinary table freeze 한도 검사를 구분하는 수정 중이다. 기존 strict 후속 solver 한도는 유지되며 감축 후에도 큰 generic 관계는 여전히 실패할 수 있다. 작지만 indexable인 solver factor를 합산 budget에서 빠뜨린 반례도 검출하여 수정한다.
- 사용자 최신 지시로 주기적인 origin/main 커밋·푸시·병합이 승인됐다. 이전 commit/push 금지 지시는 이 범위에서 대체된다. 최신 fetch 결과 origin/main=`a03365eade3e1553340dc36d23baa57f03ac5000`; 미커밋 작업을 보존해 병합 준비 중이다.
- 명시적 목표: 동일 COFEE50K×128/W1 LogReg와GLM의 전체 초기 planning각20초이하. `performance-goal` skill 적용, slug `cofee-planning-20s`; native Codex goal active(예산 미지정). 각workload3회 clean runtime, 동일 frozen engine/입력/privacy/DML/seed/profile/JVM/CPU/메모리, numeric/receipt/regression통과를 PASS조건으로 고정했다.
- Evaluator: `python3 experiments/fed-oracle-native-validation-20261008/evaluate_planning_goal.py --evidence-list .omx/goals/performance/cofee-planning-20s/evidence-list.json`. 최초실행 FAIL(각0/3), OMX fail checkpoint기록. 일반 unit test통과만으로 goal complete를 선언하지 않는다.
