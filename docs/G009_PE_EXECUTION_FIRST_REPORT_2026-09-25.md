# G009 P/E 실행 우선 재정렬 결과

작성 기준: 2026-09-25. 실행 계획은 `.omx/plans/g009-pe-execution-first-reset-20260925.md`다.

**결과: 작은 E raw 후보 37/37셀의 P/E 양방향 비교와 저장 재검사를 완료했다. 전체 campaign은 98/612셀 일치, 514셀 미완료다. 따라서 모든 workload의 `P == E`와 전체 feasible plan 보장은 아직 입증되지 않았다.**

## 판정 범위

이번 실행의 고정 분모는 `frozen-capture-cohort-derived-argv-v4`의 612셀이다. 판정 `CAPTURED_EQUAL`은 동결된 현재 P/E 생산자가 낸 **전체 물리 plan 집합**의 양방향 차집합이 0이고 저장 artifact 재검사가 통과했다는 뜻이다. P acceptance를 독립적으로 모두 해석했다는 뜻이나 전체 현재 workload의 `FULL_CURRENT` 판정은 아니다.

증거 루트 `B`는 `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921`이다. `B/current-pe-execution-first-20260925/current-pe-progress.tsv`는 **campaign의 612개 ID**를 정확히 한 행씩 담는다(고정 catalog 자체는 835행). 표의 ID 집합과 campaign의 ID 집합이 같고 양쪽에 중복이 없음을 재검사했다. 개별 certificate와 기존 verifier 결과가 권위 있는 증거이며, 상태표는 중복 실행을 피하기 위한 시점 기록이다.

물리 비교에 사용 중인 격리 빌드 `B/current-pe-build-v12-ledger-aware-candidate`의 source tree SHA-256은 `6dfd5fb7441b60724feb60c8272831a9a5bf4b37e59703a2e9354e6cb900d44e`, class tree SHA-256은 `bbcc1cd57bd649be2c12ca0f7191b4e1ae37366e9d787163b695b9f688924acb`다. 저장 artifact의 개별 binding은 해당 receipt를 따른다.

기존 612셀 physical 결과에서 61셀만 `CAPTURED_EQUAL`이었다. 나머지 551셀의 receipt는 모두 `E_RAW_BUDGET` 사전 종료였고 실제 차집합을 계산하지 않았다. 그중 37셀은 이미 끝낸 Pca12와 같은 E raw 상한 이하, 514셀은 이를 초과한다. Pca12의 별도 저장 재검사 12/12를 포함한 실행 시작 시점의 확인된 수는 73/612였다. 별도 pilot이나 재개 실행 중인 셀을 중복 합산하지 않는다.

## 실제 실행

`B/current-pe-v12-medium-pca16/worklist.json`의 Pca 16셀은 같은 artifact root에서 `run_current_pe_medium_pca.py run --resume`으로 재개했다. 실행기 자체가 각 cell run 뒤 별도 cell verifier를 호출하고, 종료 전에 16셀 전체의 저장 산출물을 다시 검사한다. 이어 `verify` 모드가 16셀을 병렬 재검사해 저장 summary와 비교했다. `B/current-pe-v12-medium-pca16/results/summary.json`과 `summary-verification.json`은 자원 기록을 제외한 판정 내용이 같으며, 모두 **16/16 `CAPTURED_EQUAL`**, 각 셀 물리 plan 600:600, P-only/E-only 0이다. 실행·재검사 로그는 같은 디렉터리의 `run-resume-20260925.log`, `verify-20260925.log`다. 실행은 4개 cell·cell당 2개 shard·최대 8 JVM으로 제한했다.

새 base-Pca 첫 4셀은 P state shard 8/8, E receipt, 별도 verifier를 모두 마쳤다. 네 셀 각각 물리 plan 600:600, P-only/E-only 0이다. 첫 셀 `cell_capture_5ef3459b24cc9b331caa`의 profile은 전체 실행 4,485.988초 중 P 물리 열거 2,034.888초, E 물리 열거 2,237.152초, cell 내부 저장 검사 209.726초를 기록한다. E raw는 첫 4셀 모두 2,057,529,600이지만 P raw는 첫 두 셀에서 각각 `19406463038893963907513029371101184000`, `4968054537956854760323335519001903104000`이다. 따라서 E raw만으로 셀 비용을 예측할 수 없다. 긴 P shard의 stack sample 3개는 `B/current-pe-execution-first-20260925/medium-pca-stack`에 보존했다.

기존 terminal certificate가 있던 별도 pilot 7셀은 `run_current_pe_cell.py verify`로 artifact만 다시 검사했다. `B/current-pe-execution-first-20260925/pilot-replay-results.json`의 7/7이 exit 0, `PASS`, P-only/E-only 0이다. 원래 완료 61셀과 이번에 닫은 작은 후보 37셀을 합쳐 **98/612 `CAPTURED_EQUAL`**, 514개 미완료다. 확인된 셀은 campaign의 서로 다른 workload 명칭 115개 중 24개에 속하며, 아직 한 셀도 비교 완료하지 못한 명칭은 91개다. `B/current-pe-execution-first-20260925/current-pe-progress.tsv`의 612개 ID가 원래 campaign 디렉터리의 612개 ID와 정확히 같고 중복이 없음을 확인했다. 98개 certificate의 PASS·raw 수·물리 수·차집합 0을 상태표와 대조했고, 남은 514개는 모두 E raw > 2,057,529,600인 `E_RAW_BUDGET` 미실행 셀이다. 상태표 SHA-256은 `f40404a88d3c845ce0d318a4d817b5821737ad0f5d814609e3f3a9e1bf2dc495`다.

우선순위 37셀은 이전에 완료된 31셀, 이번에 완료한 Pca 4셀과 microbench 2셀로 모두 닫혔다. 두 microbench 모두 이전 state budget 128 때문에 `incomplete.json`이 남았고 P shard 32개를 저장해 두었다. `reuse-k2`(`cell_capture_5723bab37fbbc1a33864`)의 첫 재개는 기존 `verify_e_factor_artifact.py`의 200만 component 열거 상한에서 실패했다. 이것은 P/E 차이가 아니라 저장 E factor의 verifier 상한이었다. 기존 `exact_e_factor_count.py`의 독립 count와 모든 compact reference ordinal의 factor 적합성·정렬·유일성·개수·SHA를 결합한 좁은 fallback을 추가했다. 작은 relation과 noncompact 경로는 기존 전수를 유지한다. 변경은 실제 저장 artifact에서 PASS, 별도 코드 검토에서 결함 0건, 관련 Python 테스트 26건 PASS를 얻었다.

동일한 동결 build와 state budget 512, E raw budget 727,833,600, `--compact --resume`으로 `reuse-k2` 전체 셀을 완료했다. certificate와 별도 offline `run_current_pe_cell.py verify`가 둘 다 PASS이며 P/E 물리 plan 8,400:8,400, P-only/E-only 0이다. 실행 로그는 `B/current-pe-execution-first-20260925/microbench-reuse-k2-fallback-resume.log`, offline 로그는 `microbench-reuse-k2-offline-verify.log`다. 본 작업트리에 통합한 verifier의 SHA와 저장 certificate의 `verifierSha256`도 일치한다.

`reuse-k2`의 같은 E 모델을 기존 `exact_e_factor_count.py`로 독립 계수해 raw 727,833,600, accepted 49,152, rejected 727,784,448, UNKNOWN 0, 최대 elimination bag 180칸을 재현했고 `run`/`verify`가 일치했다. 저장 근거는 `B/current-pe-execution-first-20260925/microbench-reuse-k2-e-factor-count.json`(SHA-256 `786aba25ec6d9b1261c226dce729a836334d738620c21d710c9799d9cda05a94`)이다. fallback은 전체 E 모델의 의미적 완전성을 주장하지 않고 저장된 factor relation과 출력의 일치만 증명한다.

두 번째 `update-k2`(`cell_capture_98da15c5d573a51c1aaf`)의 저장 E matrix 모델도 같은 독립 계수기의 `run`/`verify`가 raw 727,833,600, accepted 49,152, UNKNOWN 0, max bag 180을 재현했다(`B/current-pe-execution-first-20260925/microbench-update-k2-e-factor-count.json`, SHA-256 `84163133718049f81e163b80b685395921ddb7cef3157299612190fa24fccf75`). 기본 E 생산은 Java heap 4GiB에서 OOM이 났고, 전체 stderr를 보존한 진단 실행으로 `ExactPlanningPhysicalRows.emit`의 JSON 직렬화 지점임을 확인했다. 소스 변경 없이 기존 `g009.eComponentEnumeration=true`, `-Xss32m`, `-Xmx24g`로 동일 동결 입력을 다시 실행해 E receipt를 완료했다. receipt의 raw·accepted·rejected·UNKNOWN·ordinal SHA는 독립 factor count와 일치한다. E 생산 진단 로그는 `B/current-pe-execution-first-20260925/microbench-update-k2-e-heap24.*`다. 이 저장 E 출력으로 전체 P/E 셀 실행과 본 작업트리의 별도 offline verifier가 모두 PASS였다. 물리 plan 8,400:8,400, P-only/E-only 0이며 로그는 같은 디렉터리의 `microbench-update-k2-verify-resume.log`, `microbench-update-k2-offline-verify.log`다.

## 저장 검증기의 버전 경계

좁은 E factor fallback 때문에 cell verifier의 SHA binding이 바뀌었다. 완료 98셀 중 기존 96개 certificate는 변경 전 verifier SHA `512d7825caee276849e663f61959628202cae3c043abfc4327a3fbcca6731512`, 두 microbench는 새 SHA `ab42055efc61799decb3b984ee68370eb774b875ce7cb5be3c8e2b3def018799`에 묶여 있다. 이전 certificate를 새 코드에서 검사하면 의도대로 거부된다. 이는 기존 PASS를 취소하는 뜻이 아니라 **저장 결과를 검증할 때 해당 코드 버전을 사용해야 한다**는 계약이다.

변경 전 Git 커밋 `c03c55246a4924b94d38b05ba970773764af07c9`를 임시 worktree로 체크아웃해 기존 96개 certificate의 verifier SHA가 모두 일치함을 확인했다. baseline `cell_capture_f39d44b38a56443dbe40`은 그 worktree의 `run_current_pe_cell.py verify`로 실제 PASS, 물리 plan 64:64, P-only/E-only 0을 재현했다(`B/current-pe-execution-first-20260925/pre-fallback-pinned-verifier-smoke.log`). 임시 worktree는 검사 후 정리했다. 필요하면 같은 커밋을 다시 체크아웃할 수 있다. 새 코드에 묶인 microbench는 본 작업트리에서 검사한다.

## 작은 공간의 전수·합법성 회귀

기존 B-01/B-14 raw ordinal 전수 대조와 B-21 frontier 대조에 더해, B-01/B-14에서 raw 채택 proof의 물리 집합을 최적화 P 및 E 집합과 직접 비교한다. `CurrentPePhysicalSetCorrespondenceTest` 변경분의 표적 실행 2건과, 독립 유한 사례·함수 경계·derived-FOUT·privacy 관련 묶음 30건이 통과했다. derived-FOUT fixture는 hermetic privacy와 정확한 realization/support clause를 사용하도록 고쳤다. 별도 검토는 두 테스트 파일에서 actionable 결함 0건이었다.

이 회귀는 작은 fixture 범위의 추가·누락을 잡는다. raw와 optimized P는 analysis와 최종 production validator를 공유한다. derived-FOUT 회귀도 production predicate를 호출하므로 모든 실제 셀의 독립 feasibility 증명으로 승격하지 않는다.

## P2 큰 셀의 비용 경계

대상 `cell_00d1aa1ca27bce14d826`의 저장 모델은 P placement state 4,096개, 후보 선택 product 약 `2.1290797557439836e52`, relocation product 2,147,483,648개로 P raw product가 66자리다. E raw는 `196378274278932480000`, E accepted는 `10,926,161,920`, E UNKNOWN은 0이다. 따라서 현재의 raw row 출력 및 materialization 방식으로 전체 양방향 집합을 직접 열거하는 것은 실용적인 실행 계획이 아니다.

동결 조건으로 P placement state `[0,1)` 한 개만 120초 제한 실행했다. `B/current-pe-execution-first-20260925/p2-one-state/profile.log`에 exit 124, CPU 139.01초, 최대 RSS 1,052,860 KiB가 남았고 최종 물리 row/완료 receipt는 생성되지 않았다. 10바이트짜리 임시 gzip 두 개가 있으므로 모델 준비와 enumerator 생성 이후의 스트림 단계까지 도달했지만, timeout 시 임시 파일만으로 정확히 어느 루프에서 시간을 썼거나 행을 emit하지 않았다고 단정할 수 없다. 이것은 자원 미완료 증거이며 P-only/E-only 판정은 없다.

P2 state 0을 같은 동결 조건으로 짧게 다시 실행해 `B/current-pe-execution-first-20260925/p2-state0-stack/stack-{1,2,3}.txt`를 채집했다. 세 번 모두 `ClosedPlanRelationEnumerator.selectCandidates` 재귀(145 stack frame) 안에서 `CandidateSelections.resolveAndValidatePartial` 또는 `LogicalBoundaryRealizations.possible`을 호출 중이었다. 진단을 종료해 exit 143으로 기록했고 전체 shard receipt는 없다. 이 표본은 **P2의 현재 후보 선택/늦은 호환성 검사 비용**을 직접 가리키지만, 전체 비용 비율이나 안전한 단축 배수까지 측정한 것은 아니다.

다음 **한 가지 비용 감소 가설**은 `ClosedPlanRelationEnumerator.selectCandidates`의 후보 prefix에서 기존 `CandidateSelections.realizationsCanStillBeCompatible`를 사용해 불가능한 suffix를 일찍 가지치기하는 것이다. 현재 이 호환성 검사는 최종 validator/production exact search에는 있으나 raw audit 열거의 prefix에는 없다. 가지치기할 때 raw rejected 수를 남은 후보 domain product와 relocation product만큼 정확히 더하고 ordinal·UNKNOWN 의미를 보존해야 한다. 최적화해도 P symbolic acceptance와 E canonical physical image 및 양방향 차집합은 남는다. 동결 실행 코드를 지금 수정하면 완료된 셀의 binding도 다시 만들어야 하므로 P2 전체 비교로 연결되는 경로 없이 새 빌드를 추가하지 않는다. 단일 target의 E membership이나 성분별 count를 완료로 세지 않는다.

참고로 당시 실행 중이던 **다른 base-Pca 셀**의 긴 P shard에서 `jcmd` stack 3회를 저장했다(`B/current-pe-execution-first-20260925/medium-pca-stack`). 세 번 모두 `ClosedPlanRelationEnumerator.selectCandidates` 재귀 안에서 `CandidateSelections`/logical boundary 검사에 있었으므로 그 셀의 비용 위치는 확인됐다. 이는 P2 자체의 stack sample이 아니며 P2 병목 확정 근거로 사용하지 않는다.

## 남은 완료 조건

작은 후보 37셀은 모두 전체 비교·저장 재검사로 닫혔다. 그러나 전체 campaign 612셀 중 514셀은 아직 `INCOMPLETE`다. 이들은 E raw가 2,057,529,600을 초과해 기존 campaign에서 사전 종료됐고, P2 예시처럼 현재의 raw 열거·물리 행 materialization을 예산만 올려 재실행할 수 없다. P2/P1의 전체 압축 관계 비교, 적용성 미해결 149행, P 독립 acceptance/feasibility도 남아 있다. **이번 실행은 P == E 전체 workload 검증이나 모든 feasible plan 보장을 완료하지 못했다.** 현재 strict gate는 성공하지 않으며, 증거가 없는 셀은 `INCOMPLETE`, 독립 완전성은 미검증으로 유지한다.
