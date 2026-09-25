# G009 현재 P/E 검증 작업 현황 보고서

작성일: 2026-09-25  
대상 계획: `.omx/plans/g009-current-pe-all-workloads-20260923.md`  
현재 판정: **INCOMPLETE — 전체 workload의 P == E를 아직 인증하지 못함**

## 현재 결론

현재 작업은 612개 `FROZEN_COHORT_DERIVED_ARGV_612` 조건을 대상으로 P와 E의 모델 산출물을 동결하고, 저장된 증거를 독립적으로 다시 읽을 수 있는 단계까지 진행됐다. 그러나 이 612개는 현재 workload 전체를 의미하는 `FULL_CURRENT` 분모가 아니다. 원래 registry와의 적용성 감사에서 149개 행이 여전히 `UNRESOLVED`이며, 따라서 이 cohort의 성공을 전체 workload 완전성으로 승격하지 않는다.

또한 현재의 `CAPTURED_EQUAL`은 각 구현이 저장한 accepted native proof를 공통 물리 identity로 투영해 비교했다는 뜻이다. P acceptance의 전체 의미를 독립적으로 재구성했다는 뜻이 아니며, E의 대형 공간에 대한 정확한 물리 image relation을 완성했다는 뜻도 아니다. 최종 주장인 `CURRENT_NATIVE_PHYSICAL_SET_EQUALITY`는 다음 조건을 모두 만족한 뒤에만 발행해야 한다.

```text
P-only = ∅
E-only = ∅
P acceptance 독립 재검사 = COMPLETE
E physical projection 독립 재검사 = COMPLETE
필수 분모와 applicability ledger = COMPLETE
저장 artifact-only 재검사 = PASS
```

현재는 위 조건 중 마지막 조건의 일부 pilot만 충족한다.

## 분모와 입력 상태

최종 동결 입력은 다음 경로에 있다.

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/frozen-capture-cohort-derived-argv-v4/catalog.json
/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/frozen-capture-cohort-derived-argv-v4/campaign.json
```

이 cohort의 구성은 다음과 같다.

| 범위 | 조건 수 | 현재 상태 |
|---|---:|---|
| 기존 planning cohort | 224 | 입력·P/E 모델 artifact 재검사 완료 |
| base/ML10 | 296 | compiler argv가 고정된 derived 입력 준비 완료 |
| generated microbench | 28 | compile-model 입력 준비 완료 |
| base SliceLine | 32 | sealed metadata에서 파생된 입력 준비 완료 |
| planning-parent SliceLine | 32 | parent manifest에서 파생된 입력 준비 완료 |
| 합계 | **612** | derived cohort; `FULL_CURRENT` 아님 |

적용성 ledger는 다음에 있다.

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-scope-applicability-audit-v5-template-closure/ledger.json
```

ledger 상태는 `INCOMPLETE`이며, template/library closure 이후에도 149개 행이 `UNRESOLVED`다. 이 149개를 근거 없이 `OUT_OF_SCOPE` 또는 `ACTIVE`로 분류하지 않는다.

## 완료된 검증 증거

저장 모델 행렬에 대해서는 612개 P/E 모델 artifact가 준비됐고, 기존 저장물 재검사 경로가 동작한다. 이 결과는 P/E 모델의 구조·해시·영수증 무결성을 말해 주지만, 물리 plan 집합 동등성 자체를 완성하지는 않는다.

동결 612조건의 기존 물리 결과는 다음과 같다.

| 저장 결과 | 개수 | 의미 |
|---|---:|---|
| `CAPTURED_EQUAL` | 61 | 저장된 P/E physical identity 집합이 일치한 조건 |
| `INCOMPLETE` | 551 | 물리 비교에 필요한 저장 relation 또는 재검사 조건이 부족한 조건 |
| `DIFFERENT` | 0 | 현재 저장 결과에서 확인된 차이 없음 |
| `ERROR` | 0 | 현재 저장 결과에서 확정된 오류 없음 |

이 집계는 다음 artifact-only 병렬 재검사 결과에 근거한다.

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-campaign-v12-ledger-aware/physical-results/parallel-verification.json
```

상태는 `INCOMPLETE`이고 종료 코드는 2다. 이는 실패가 아니라 미완료 범위를 숨기지 않는 게이트 동작이다.

Pca 대표 12조건은 별도 exact capture/replay 경로로 완료됐다.

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-v12-ledger-aware-pca-representatives
```

생산 집계와 빈 Python bytecode cache에서 실행한 독립 `verify`의 `summary.json` 및 `summary-verification.json`은 모두 다음 SHA-256을 가진다.

```text
9f4405abf493c20fa676f7674b99a7698c313cbbd7192380ae2205979000dddf
```

Pca 12개는 모두 `CAPTURED_EQUAL`이며 각 조건의 P/E 물리 identity 차집합은 0이다. 이 증거의 범위는 captured native physical set이다.

추가로 작은 Pca·ML·base 및 microbench pilot과 두 microbench 회귀는 성공했지만, 대표 조건의 수가 612개 전체를 대체하지 않는다.

## 현재 실행 중인 작업

중간 규모 Pca 16조건의 병렬 실행은 계속 진행 중이다.

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-pe-v12-medium-pca16
```

현재 확인 시점에는 다음 상태였다.

- `certificate.json`과 `profile.json`이 모두 존재하는 완료 cell: 4개
- 실행 중인 E Java capture: 4개
- 각 cell은 P/E capture, compact physical rows, exact comparison, artifact-only verification을 순서대로 수행한다.
- 완료된 cell은 `run.log`에 `CAPTURED_EQUAL`로 기록된다.

실행 중인 셀을 임의로 종료하거나 partial artifact를 최종 결과로 읽지 않는다. producer가 certificate를 먼저 쓰고 profile을 나중에 쓰는 구간이 있으므로 discovery는 두 파일이 모두 terminal 상태인지 확인해야 한다.

## 독립성 및 정확성의 남은 공백

P 측에는 다음 7개 생산 predicate의 독립적인 assignment-level 재구성이 아직 없다.

1. privacy/status 원시 사실
2. candidate feasible variant와 source reachability
3. candidate selection과 realization compatibility
4. relocation active demand
5. relocation selection과 worker pool
6. candidate/relocation alignment
7. Java validator exception classification

따라서 accepted proof를 저장하고 다시 읽는 것만으로 `INDEPENDENT_FULL_ACCEPTANCE_VERIFIED`를 발행할 수 없다.

E 측에서는 non-PHI typed decoder와 일부 bounded membership 검사가 통과했지만, P1/P2 대형 공간에 대해 native assignment를 정확히 존재 양화한 physical image relation은 아직 없다. PHI, UNKNOWN, 대형 factor 예산 초과와 Java projector 의미 연결은 계속 `INCOMPLETE` 또는 `BLOCKED`로 남긴다.

현재 확정된 독립 E membership 증거는 captured typed decoder 내부의 local claim이다. Java planner 의미론과 P/E 양방향 전체 집합 동등성을 주장하는 증거가 아니다.

## 집계기 구현 상태

다중 artifact root를 하나의 진행 집계로 묶는 새 파일은 현재 작업 트리에 있으나 아직 commit되지 않았다.

```text
scripts/fedplanner/aggregate_current_pe_artifacts.py
scripts/fedplanner/tests/test_aggregate_current_pe_artifacts.py
```

이 집계기는 다음을 목표로 한다.

- baseline 및 추가 artifact root를 cell identity별로 합산
- output path가 입력 root와 겹치거나 symlink를 통해 이탈하는 경우 거부
- lock과 staged generation을 사용한 원자적 summary 게시
- helper source composite SHA와 input manifest 결속
- run tree의 전후 manifest 비교로 producer 동시 변경 감지
- final `profile.json`이 없는 candidate를 terminal result로 읽지 않음
- 저장된 generation file set과 manifest가 다르면 offline verify 거부

그러나 최종 독립 검토에서 두 가지 차단 결함이 발견됐다.

- terminal candidate 검증 중 파일이 바뀌면 해당 evidence를 버리고 baseline으로 조용히 fallback할 수 있었다. 이 경우 실제 `DIFFERENT` 후보가 baseline `CAPTURED_EQUAL`로 숨겨질 수 있다. bounded retry 후 `ERROR` 또는 `INCOMPLETE`로 보존해야 한다.
- missing/symlink extra artifact root 테스트가 선행 입력 root 부재를 먼저 만나는 false positive였다. 테스트 fixture를 보강해 실제 extra-root 검증 분기를 실행해야 한다.

이 결함이 남아 있어 다중 root overlay의 실제 frozen run과 결과 publication은 아직 수행하지 않았다. 따라서 overlay output directory가 생성됐다고 보고하지 않는다.

## 저장소와 원격 반영 상태

`origin/main`에 반영된 마지막 commit은 다음이다.

```text
ef28d55d18ee8965247822f6ea49a7c5144bb208  Check P FOUT materialization reachability leaf
```

직전 관련 commit은 다음과 같다.

```text
0ed10b0ef76fff8baee503c15eb88fe335764acb  Verify stored P transient support and certify Pca12 replay
41e6d357d9         Certify non-PHI E membership semantics and P support leaf
```

현재 branch는 `integration/g009-baseline-20260919`이며 `origin/main`과 마지막 commit이 일치한다. 집계기 두 파일은 아직 untracked 상태이므로, 최종 review·targeted test·실제 overlay run·offline verify를 끝낸 뒤 별도 commit으로 반영해야 한다.

## 재현 명령

기존 612조건 artifact-only 병렬 재검사는 다음 경로에서 재실행할 수 있다.

```bash
cd /home/mchoi/systemds-g009-integration
B=/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921
TMPDIR="$B" PYTHONDONTWRITEBYTECODE=1 \
PYTHONPYCACHEPREFIX="$(mktemp -d "$B/pycache-verify-XXXXXXXX")" \
python3 scripts/fedplanner/verify_current_pe_matrix_parallel.py verify \
  --campaign "$B/frozen-capture-cohort-derived-argv-v4/campaign.json" \
  --catalog "$B/frozen-capture-cohort-derived-argv-v4/catalog.json" \
  --evaluation-root "$B/frozen-capture-cohort-derived-argv-v4/evaluation" \
  --verification-root /home/mchoi/cofee-evaluation \
  --p-matrix-dir "$B/current-pe-campaign-v12-ledger-aware/p-models" \
  --e-matrix-dir "$B/current-pe-campaign-v12-ledger-aware/e-models" \
  --physical-result-dir "$B/current-pe-campaign-v12-ledger-aware/physical-results" \
  --jobs 8
```

현재 기대 상태는 `INCOMPLETE`, exit code 2이며, 61 `CAPTURED_EQUAL`과 551 `INCOMPLETE`을 유지해야 한다.

중간 규모 실행은 다음 로그에서 확인한다.

```bash
tail -f "$B/current-pe-v12-medium-pca16/run.log"
```

다중 root overlay의 다음 실행은 집계기 review 결함을 수정하고 targeted test를 다시 통과시킨 뒤에만 수행한다. 결과가 일부 `CAPTURED_EQUAL`이어도 전체 status는 미완료 셀이 남아 있는 한 `INCOMPLETE`이어야 한다.

## 다음 종료 조건

작업을 최종적으로 닫으려면 다음 순서를 지켜야 한다.

1. 집계기 두 review blocker를 수정하고 mutation test를 추가한다.
2. 집계기 targeted test, 전체 fedplanner Python test, `py_compile`, `git diff --check`를 fresh bytecode cache에서 통과시킨다.
3. baseline·Pca·microbench·중간 규모 root를 포함한 실제 multi-root overlay를 실행한다.
4. 별도 빈 cache에서 overlay `verify`를 실행하고 generation manifest, receipt SHA, cell counts를 대조한다.
5. 중간 규모 16조건과 추가 workload 실행 결과를 완료 또는 명시적 `INCOMPLETE`로 저장한다.
6. 149개 applicability ledger와 P 7개 opaque predicate를 닫기 전에는 `FULL_CURRENT` 또는 전체 P==E `PASS`를 발행하지 않는다.
7. 모든 필수 범위가 닫힌 뒤에만 새 report, commit, `origin/main` push를 수행한다.

현재 보고서의 종료 상태는 위 조건을 충족하지 않았으므로 **INCOMPLETE**이다.
