# Local/Global pruning 독립 재검증 — 최초 발견 기록

> **후속 수정 완료:** 아래는 수정 전 재검증 기록이다. 선택적 할당·빈 bucket 결함을 수정하고 영구 회귀를 보강했으며, 독립 code-reviewer APPROVE / architect CLEAR를 받았다. 실제 LogReg/GLM 8회는 공통 분석에서 60초 watchdog 미완료여서 속도 효과는 미확인이다. 최신 결과는 [수정 및 실제 측정 보고서](DP_PRUNING_REPAIR_2026-10-08_KO.md)를 따른다.

## 당시 판정

**REQUEST CHANGES.** 이전 구현 보고서의 최종 APPROVE는 이 재검증으로 대체한다. 목적값/lower/witness가 달라지는 수치 반례는 발견하지 못했지만, 신규 구현의 오류 경로 2건을 재현했다.

- code-reviewer: **REQUEST CHANGES** — HIGH 1, MEDIUM 1, LOW 1.
- architect: **WATCH** — 같은 상태의 pruning 증명은 타당하나, 자원 부족 처리와 production 적용 계측/회귀 커버리지에 주의가 필요하다.
- 이번 작업은 **검증과 문서화만** 수행했다. production 코드와 저장소 테스트는 수정하지 않았다. 재현용 소스/로그는 저장소 밖 evidence에 보존했다.
- 대상 HEAD: `e468797556e6789100736355ca2463941302221f` + 이전 미커밋 pruning 변경. 이전 candidate와 현재 대상 소스 3개 및 solver class 49개가 byte-identical함을 확인했다.

## 발견 1 — HIGH: 선택적 하한 배열 할당 실패가 정확한 계산을 중단

- **위치**: `ExactCategoricalSolver.java:2345`, `:2382–2384` (`boundaryCostPruning`, `globalCostPruning`). 소스 prefix는 `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/`다.
- **원인**: 선택적 suffix bound 배열의 `PlannerResourceGuard.allocateDoubles`가 `ResourceExhaustedException`을 던지면 최적화만 생략하는 경로가 없다. 기본값이 SUFFIX이므로 Local/Global의 완료 가능성에 영향을 준다.
- **계약 위반**: 승인된 계획 P3.6은 하한 준비 자원이 부족하면 기존 정확한 계산으로 복귀하도록 요구한다. 이는 runtime fallback이 아니라 선택적 비용 최적화의 미적용이다.
- **재현**: 두 factor `[3,4]`, `[7,9]`에서 선택적 bound 배열 할당만 실패하도록 주입했다. 같은 주입 조건에서 LEGACY는 최솟값 10을 반환하지만 SUFFIX는 Local과 Global 모두 `ResourceExhaustedException`으로 종료한다.
- **증거 구분**: 실제 머신의 heap을 고갈시킨 측정이 아니라, 해당 할당 위치에만 예외를 주입한 결정론적 오류 경로 검증이다.
- **필요 수정**: optional bound 준비 부분에서만 자원 예외를 처리하고 기존 정확한 합산을 계속하도록 한다. 비용 검증/산술 오류나 다른 필수 할당 실패를 광범위하게 삼키면 안 된다. Local/Global 두 경로 모두 회귀 테스트가 필요하다.

## 발견 2 — MEDIUM: 빈 factor bucket이 새 계측 경로에서 0으로 나누기

- **위치**: `ExactCategoricalSolver.java:2506–2508`, `saturatedMultiply`의 `:3914`.
- **원인**: 어느 factor에도 사용되지 않는 합법 변수는 `bucket.size()==0`이다. 새 `fullChildEvaluations` 계측이 이를 곱셈 helper의 나눗셈 분모로 전달한다.
- **재현**: 변수 x,y와 y의 factor 하나, 제거 순서 x→y. public `solve`는 `[0,0]`을 반환한다. 동일 문제를 counters가 있는 `solveWithPruningForTest`로 풀면 `ArithmeticException: / by zero`다.
- **영향 범위**: 현재 counters 없는 production 기본 경로에는 이 오류가 발생하지 않는다. 새 계측/test/benchmark 경로의 결함이다.
- **필요 수정**: 곱셈의 어느 인자든 0이면 0을 반환하거나 빈 bucket 계측을 명시적으로 처리한다. isolated-variable 회귀를 추가한다.

두 문제를 검증하는 별도 `PruningVerificationFailuresTest`는 **3 tests / 3 failures**로 재현됐다: Local 할당, Global 할당, 빈 bucket 계측 각각 1건. `reproduced-failures.log`에 stack trace가 있다.

## LOW / 추가 관찰: 저장소 회귀 및 production 계측의 빈틈

1. 일부 기존 Local lower/residue/infinity/overflow 테스트는 `baseline` / `local_only`만 비교한다. 두 옵션은 의도적으로 LEGACY이므로 새 suffix를 검증하지 않는다. 저장소의 큰 dyadic work fixture도 PREFIX↔SUFFIX 비교 중심이며 raw LEGACY↔SUFFIX 비교가 부족하다.
2. Local production counters는 explicit ablation에서만 생성되는데 그 경우 새 pruning이 꺼진다. Global default도 counters가 null이다. 소스에서 새 최적화의 호출 경로는 확인했지만 기존 Docker receipt로 실제 suffix cut 빈도는 알 수 없다.
3. 새로운 배열/scan은 O(bucket) 추가 비용이다. 단위 fixture의 child read 감소만으로 모든 workload의 속도 개선을 증명하지 않는다.

이번 별도 검증으로 1의 즉시 반례 탐색은 강화했지만, 해당 테스트를 저장소의 영구 회귀군으로 통합한 것은 아니다.

## 새로 수행한 검증

| 검증 | 결과와 정확한 범위 |
|---|---|
| 기존 핵심 32 suites / 270 tests | 별도 fresh 재실행 **270 PASS**, 실패/오류/skip 0 |
| Local support 기존 fixture를 기본 SUFFIX로 replay | **14 PASS**. lower≠exact, nonabsorbing infinity, overflow, quotient/witness 포함 |
| work fixture를 LEGACY↔SUFFIX로 replay + 수치 경계 추가 | **16 PASS**. 다중 factor subnormal/near-max, 105-bit raw dyadic word, 잘못된 suffix 입력의 오류 보존 포함 |
| Global fixed-seed 무작위 20,000 모델 | LEGACY/PREFIX/SUFFIX의 objective/assignment/모든 elimination raw high·low·choices 비교 PASS |
| Local fixed-seed 무작위 30,000 생성 시도 | 유효 boundary 문제의 논리 값/lower 최솟값/feasibility/witness 비교 PASS. 존재하지 않는 boundary를 뽑은 입력은 기존 validation 거부로 분리 |
| 신규 오류 경로 재현 | **3/3 실패**, 위 두 결함을 입증 |
| 이전 Docker 증거 무결성 재검증 | 20 runs / 120 workloads의 모든 보관 파일 SHA256, 환경/입력, objective/assignment/actions/numeric output, fallback/repair=0 재확인 |

**이번에는 Docker를 새로 실행하거나 성능을 다시 측정하지 않았다.** 기존 120회 증거가 지금 코드/바이너리에 해당하는지 검증한 것이다. 대규모 standalone DP-Global/LogReg 성능·peak memory 공백은 그대로다.

### 확장 회귀군: 전체 green 아님

- fedExact 및 관련 placement 총 179 suites를 선택했다. 별도 runtime/campaign 14 suites는 제외 목록에 명시했다.
- 178 suites의 완료된 보고서: **958 tests = 934 PASS + 16 failures + 8 errors**, skip 0.
- 나머지 `IndependentCompletePlacementSpaceTest`는 180초 이상 raw Cartesian universe 열거를 계속했다. thread dump를 보존하고 해당 테스트 JVM만 중단했다. 완료/통과로 간주하지 않는다. 이 때문에 Maven 전체 결과도 BUILD FAILURE다.
- 실패 24개 중 **21개는 변경 전 동결 baseline jar에서도 같은 test method가 실패**했다. 19개는 예외 타입과 전체 메시지가 같았고, 2개는 같은 assertion/type이지만 실행 문맥의 fingerprint 등 상세 진단 문자열이 달랐다. 이전에 보고했던 7건도 여기에 포함된다.
- `ExactPhysicalSemanticBindingOracleTest`의 1건은 `/tmp` 디스크 부족이었다. test JVM의 `java.io.tmpdir`를 여유 있는 grid로 명시한 재실행은 PASS였다. 부모 Maven JVM의 TMPDIR만 설정하는 것으로는 forked test JVM의 임시 경로가 바뀌지 않았다.
- `ExactPhysicalDyadicCampaignScaleTest`의 child watchdog 실패와 `ExactGlmCostSurfaceScalabilityTest`의 baseline 재실행 75초 watchdog은 미확인으로 남긴다. timeout을 pruning 회귀나 기존 실패라고 단정하지 않는다.

따라서 “저장소 전체 테스트 통과” 또는 “기존 실패가 오직 7건뿐”이라는 주장은 하지 않는다. 확인된 신규 오류 경로와 baseline/환경/미완료 사례를 분리했다.

## 재현 자료와 다음 수정 범위

Evidence root:
`/grid/3/cofee-lm-sweep-mchoi-20260914/dp-pruning-parity-20261008/verification-20261008T2020/`

- `review-verdict.json`: 두 독립 리뷰의 종합 판정.
- `probe-src/PruningVerificationFailuresTest.java`, `reproduced-failures.log`: 실패 주입 및 빈 bucket 재현.
- `probe-src/BoundarySuffixReverificationTest.java`, `default-suffix-boundary.log`: Local 기본 SUFFIX replay.
- `probe-src/SuffixNumericBoundaryReverificationTest.java`, `suffix-numeric-boundaries.log`: raw LEGACY↔SUFFIX 및 수치 극한 replay.
- `probe-src/PruneProbe.java`, `probe-src/LocalProbe.java`, `global-20000-parity.log`, `local-30000-parity.log`: 임의 모델 비교.
- `expanded-candidate.log`, `candidate-surefire/`, `expanded-summary.json`, `focused-270-fresh.log`, `focused-270-fresh-summary.json`: 이번 fresh 회귀 결과.
- `recheck_failures.py`, `failure-recheck.json`, `baseline-*.log`: 원본 binary와 실패 대조.
- `long-test-thread-dump.txt`, `bounded-enumeration-stop.json`: 미완료 열거의 중단 근거.
- `docker-evidence-audit.json`, `source-sha256.json`, `probe-source-sha256.json`: 증거/소스 동일성.

수정 순서는 **선택적 할당 실패 처리 → 빈 bucket 계측 → 해당 영구 회귀 테스트와 raw dyadic parity 보강 → 독립 재검토**다. 기존 합법 plan 공간, exact/lower/witness 계약, runtime fallback 금지 규칙은 바꾸지 않는다.
