# 2026-09-25 세션 이슈

아래 수치와 진행 상태는 이슈를 발견한 당시의 시점 기록이다. 현재 판정과 완료 증거는 `G009_PE_EXECUTION_FIRST_REPORT_2026-09-25.md` 및 저장된 612행 상태표를 따른다.

## P/E 전체 비교보다 검증 컴포넌트 개발이 선행한 실행 순서

- **상태**: 재정렬 계획의 실행 단계 진행 중. 612행 상태표 생성, 중단됐던 Pca16 실행 재개, 별도 pilot 재검사 진행.
- **문제 정의/증상**: 사용자는 실제 workload 검증이 끝나지 않고 검증 도구만 늘어나는 점을 지적했다. 저장 결과 612개 중 551개가 미완료로 남아 있었다.
- **원인 분석**: `physical-results/*/receipt.json`을 직접 집계하니 551개 모두 기본 `E_RAW_BUDGET` 사전 제한이었다. 이 중 37개는 완료한 Pca pilot의 raw 상한 이하인데, 실제 실행 우선순위와 전체 독립 acceptance/출처 감사/overlay 구축이 섞여 있었다. raw 상한 통과가 실행 가능성을 보장하는 것은 아니다.
- **해결 방법**: `.omx/plans/g009-pe-execution-first-reset-20260925.md`에 612행 상태표 → 기존 경로의 실제 비교 → 제한된 raw/독립 legality 회귀 → P2 전체 양방향 비교 → 전체 확대 순서를 기록했다. 새 overlay·범용 checker 개발은 당장 선행 조건에서 제외했다.
- **수정 파일**: 위 계획 문서와 이 세션 문서 생성, 기존 현황 보고서의 사전 종료 사유·CLI 예시·최신 계획 연결 정정.
- **검증 근거**: baseline receipt 직접 집계 61 `CAPTURED_EQUAL`, 551 `E_RAW_BUDGET`; 그 551개 중 raw ≤ 2,057,529,600은 37개, 초과 514개. Pca12 저장 재검사 12/12 `CAPTURED_EQUAL`. 이후 Pca16 run directory에서 완료 certificate/profile 8쌍을 찾았다. 현재 프로세스가 없음을 확인한 뒤 `run_current_pe_medium_pca.py run`을 같은 worklist와 artifact root에 `--resume` 경로로 재개했다. `current-pe-execution-first-20260925/current-pe-progress.tsv`의 612행은 73개 기존 재검사 완료, 15개 추가 certificate 재검사 대기, 524개 미완료를 구분한다. 15개 중 8개는 재개한 Pca16이고 나머지 7개는 별도 artifact-only 검사 중이다. 우선 raw 범위에서 완료 증거가 없는 셀은 Pca 8개와 microbench 2개다. 동결 v12 빌드의 기존 raw/P-E 대응 Java 테스트 38개, 물리 identity·cell verifier Python 테스트 17개가 모두 통과했다. 수는 서로 겹치는 셀을 제외한 실행 표의 시점별 상태다.
- **잔여 이슈**: P 전체 acceptance·P1/P2 physical image, applicability 149행, 미완료 overlay의 검토 지적은 해결되지 않았다. 단순화 계획을 전체 인증 완료로 해석하지 않는다.
- **잠재 회귀 위험/감지**: captured 비교를 full certification으로 승격하거나 작은 oracle을 모든 workload 증명으로 일반화할 위험. 기존 strict gate·claim 명칭·차집합/coverage 검사를 유지하고, 분모별 완료 상태를 따로 명시한다.
- **의사결정 근거**: planner/oracle/runtime 규칙을 완화하지 않고 실행 순서와 보고 단위를 수정한다. 아직 어떤 production 후보도 제거하거나 추가하지 않았다.

### 실행 중 추가로 확인한 경계

- 기존 Pca16의 완료된 8셀은 재개 실행기에서 다시 `CAPTURED_EQUAL`로 끝났다. 새 4셀도 각각 물리 plan 600:600, 양방향 차집합 0, 실행기의 별도 cell verifier 통과로 `CAPTURED_EQUAL`이 됐다. 나머지 4셀은 같은 worklist에서 실행 중이며, 최종 summary 및 aggregate `verify` 전에는 16/16 완료로 세지 않는다.
- 별도 pilot의 terminal certificate 7개를 기존 cell verifier로 별도 프로세스에서 재생해 7/7 `PASS`, 각 양방향 차집합 0을 확인했다. `current-pe-execution-first-20260925/pilot-replay-results.json`을 보존했다. medium 실행기 로그·certificate·profile을 대조한 per-cell 12개까지 반영해 612행 상태표는 92 `CAPTURED_EQUAL`, 520 `INCOMPLETE`다. medium 전체 aggregate 검증은 진행 중이다.
- 우선 raw 범위에 남은 microbench `cell_capture_5723bab37fbbc1a33864`를 state budget 512·E raw budget 727,833,600·7,200초 제한으로 기존 P shard 32개/E receipt에서 재개했다. 약 70분 후 기존 E factor verifier의 component 원시 열거 상한 2,000,000에서 실패했다(`microbench-reuse-k2-resume.log`). 저장 certificate는 terminal PASS가 아니고 P/E 차집합은 계산되지 않았다. 독립 factor count 경로의 `run`/`verify`는 raw 727,833,600, accepted 49,152, UNKNOWN 0, max bag 180을 재현했다(`microbench-reuse-k2-e-factor-count.json`, SHA-256 `786aba25ec6d9b1261c226dce729a836334d738620c21d710c9799d9cda05a94`). producer 출력 49,152개 ordinal을 독립 factor 관계와 연결하는 좁은 fallback을 격리 worktree에서 시험한다. 다른 `cell_capture_98da15c5d573a51c1aaf`는 E receipt가 없어 medium 작업의 JVM 상한을 고려해 대기한다.
- P2 `cell_00d1aa1ca27bce14d826`는 동결된 조건·JVM 옵션·network 환경에서 P native placement state `[0,1)`만 120초 제한 실행했다. exit 124, CPU 139.01초, 최대 RSS 1,052,860 KiB, 최종 receipt/물리 row 없음. 로그는 `current-pe-execution-first-20260925/p2-one-state/profile.log`다. 10바이트 임시 gzip 두 개가 생성되어 스트림 단계까지 도달했으나 그 파일만으로 emit 수는 알 수 없다. 이어서 같은 state 0을 짧게 재실행해 `p2-state0-stack/stack-{1,2,3}.txt`를 채집했고 세 표본 모두 `ClosedPlanRelationEnumerator.selectCandidates` 145프레임 안의 호환성·boundary 검사에 있었다. 이는 P/E 불일치나 plan 불가능의 증거가 아니라 현행 후보 열거의 비용 위치다. 후보·relocation을 포함한 P raw product는 66자리이므로 전체 예산을 단순 상향하지 않는다. 다음 한 가지 비용 감소 가설은 기존 `CandidateSelections.realizationsCanStillBeCompatible`를 P 후보 prefix 가지치기에 적용하는 것이다. 이 최적화만으로 P2 전체 양방향 비교가 끝나지는 않는다.
- 소형 fixture B-01/B-14에서 raw 전수·P 압축 열거·E 물리 집합이 같은지 기존 대응 테스트에 연결했다. 이 테스트와 독립 유한 legality 사례를 포함한 JUnit 30건이 통과했다. derived-FOUT 회귀 테스트는 hermetic privacy 등록과 정확한 realization/support clause 선택으로 현재 모델 계약에 맞췄다. derived-FOUT 테스트 자체는 생산 predicate를 재사용하므로 독립 합법성 인증은 아니다.

### 재현과 잠재 위험

- 상태표: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-execution-first-20260925/current-pe-progress.tsv`. campaign 612개 ID와 동일한 집합·유일성을 재검사했다. catalog 전체는 835행이다. 기존 612개 receipt와 명시한 pilot root의 terminal certificate/profile만 결합한 시점 기록이며 verifier의 대체물이 아니다.
- Pca16 재개: 같은 증거 루트의 `current-pe-v12-medium-pca16/run-resume-20260925.log`와 `worklist.json`. 실행 명령은 `.omx/plans/g009-pe-execution-first-reset-20260925.md`의 예산(4 cell, 2 shard, 최대 8 JVM)을 따른다. 최종 `summary.json`과 별도 `verify`가 나오기 전에는 16셀 완료로 보고하지 않는다.
- 추가 pilot 재검사: `current-pe-execution-first-20260925/pilot-replay.log`, 같은 디렉터리의 `pilot-replay-results.json`에 최종 7/7 `PASS`로 저장했다.
- **위험**: 살아 있는 runner와 별도 직접 실행이 같은 cell/artifact root를 쓰면 race·중복이 생긴다. Pca16 완료 전에는 그 8개 미완료 셀을 따로 실행하지 않는다. 현재 집계 코드는 새로운 overlay 파일을 읽거나 수정하지 않는다.

## 직전 현황 보고서의 재검사 CLI 예시 불일치

- **상태**: 예시 정정 완료. 해당 보고서의 무거운 재검사는 이번 작업에서 실행하지 않음.
- **증상/원인**: `G009_CURRENT_PE_WORK_STATUS_REPORT_2026-09-25.md`의 `--physical-result-dir`는 실제 parser에 없으며 `--artifact-root`, `--result-dir`, 동결 binding 재현 옵션이 필요하다.
- **해결 방법**: 장기 실행 보고서의 예시·실제 parser·저장 summary binding에 맞춰 경로와 병렬/예산 옵션을 정정했다. 새 계획에는 실제 지원되는 cell-level `verify` 명령을 수록했다.
- **수정 파일**: `G009_CURRENT_PE_WORK_STATUS_REPORT_2026-09-25.md`, 계획 및 이슈 기록. 기존 runner 변경 없음.
- **검증**: `verify_current_pe_matrix_parallel.py:354`, `run_current_pe_cell.py:865`의 CLI 인자 정의 직접 확인.
- **잔여 이슈/잠재 회귀 위험**: 과거 복사본의 명령은 여전히 잘못될 수 있다. 정정된 문서를 사용하고 후속 실행 전 옵션과 저장 binding을 대조한다.
- **의사결정 근거**: 저장 artifact의 정확성 문제가 아니라 문서의 명령 예시 오류다.

## 실제 셀 실행으로 확인한 추가 이슈와 종료 상태

- 작은 E raw 후보 37셀은 기존 전수·compact 비교 경로로 모두 실행·저장 재검사를 마쳤다. 전체 campaign은 98/612 `CAPTURED_EQUAL`, 514개 `E_RAW_BUDGET` 미실행이다. 완료 셀의 P-only/E-only는 모두 0이다. 현재 값과 run별 근거는 `G009_PE_EXECUTION_FIRST_REPORT_2026-09-25.md`에 있다.
- `reuse-k2`의 E factor verifier가 200만 component 열거 상한에서 멈췄다. 독립 exact count와 모든 compact ordinal의 factor 적합성·유일성·SHA를 대조하는 좁은 fallback을 추가했다. 실제 셀 전체·offline 검증이 모두 PASS, 물리 plan 8,400:8,400이다. 작은 relation의 기존 전수 경로를 유지했고 관련 Python 테스트 26개가 통과했다.
- `update-k2`의 기본 E 생산은 Java heap 4GiB에서 JSON 직렬화 OOM이 났다. 전체 stderr로 위치를 확인하고 동결 소스 변경 없이 기존 성분 열거 옵션과 24GiB heap으로 E receipt를 완료했다. 이후 셀 전체·offline 검증이 모두 PASS, 물리 plan 8,400:8,400이다.
- Pca16은 실행 summary와 별도 `verify` summary 모두 16/16 `CAPTURED_EQUAL`, 각 물리 plan 600:600이다. verifier 변경 전 certificate 96개는 Git 커밋 `c03c55246a4924b94d38b05ba970773764af07c9`의 코드 SHA에, 두 microbench는 새 코드 SHA에 묶인다. 변경 전 커밋의 임시 체크아웃으로 baseline 실제 offline PASS를 재현했다.
- **남은 차단점**: P2 한 셀의 66자리 P raw 관계와 약 109억 E accepted 관계를 현재 열거·물리 행 방식으로 전체 양방향 비교할 수 없다. prefix 병목은 stack으로 확인했지만 전체 압축 관계 비교 경로는 없다. 따라서 514셀, applicability 149행, 독립 P acceptance/feasibility와 strict gate는 미완료다. 부분 count나 단일 state 실행을 완료로 승격하지 않는다.
