# G009 P/E 및 확장 이전 Git plan-space 비교 실행 보고서

- 기준일: 2026-09-22
- 계약: `closed-model-pe-history-v1`
- 전체 판정: **INCOMPLETE**. 현재 보호 fixture의 P/E 물리 행 비교는 통과했으나 전체 workload·역사 버전의 필수 물리 집합 비교가 끝나지 않았다. 아래의 부분 결과를 전체 PASS 또는 runtime 합법성 인증으로 읽지 않는다.
- 실행 계획: [G009_PE_AND_LEGACY_PLAN_SPACE_COMPARISON_PLAN_2026-09-21.md](G009_PE_AND_LEGACY_PLAN_SPACE_COMPARISON_PLAN_2026-09-21.md)
- 증거 저장소: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/`
- 최종 Java 검증에 사용한 동결 source snapshot: `snapshots/1d6bc6edf488c8f5cfd4987d3a55176a2e0d8258542c637f61a610e7f9acc5de` (13,920개 파일; 독립 `check` 통과). 마지막 preflight snapshot `cbcae685359e9f199e6e13a9e62c7cb857eee84dad9e28b8da5e6eae56aa3770`과의 파일 index 차이는 이 실행 보고서 및 세션 기록 두 문서뿐이다.

## 확인된 결과

| 범위 | 결과 | 판정의 한계 |
|---|---|---|
| 현재 보호 B-01…B-22 | B-13은 양쪽 compiler에서 privacy-safe placement 오류. 나머지 21개에서 현재 P/E native accepted proof와 투영 물리 집합·proof multiplicity가 일치한다. E accepted 216행, 물리 key 56개. B-21은 192 proof → 36 key. | 고정 fixture의 투영 동등성이다. 모든 proof-collapse가 runtime에서 같은 행위라는 독립 증명은 없다. |
| 물리 identity | source occurrence, value version/CFG predecessor, ordered input, PHI alternatives, selected input authority 및 action 참조, geometry, durable/source authority, transient 및 인라인 함수 입력 관계를 기록한다. 엄격한 Python validator가 row 내부 연결을 검사한다. | historical bridge와 실제 DML 모든 관계에는 아직 적용되지 않았다. |
| P exact relation | B-21의 P raw product `40,587,440,947,200`에 대한 exact relation 열거는 accepted 192, unknown 0 및 raw coverage balance를 보고한다. 작은 fixture에서는 no-prune 기준과 일치한다. | 수백 자리 raw product인 실제 planning model까지 확장한 일반 lossless relation engine은 없다. |
| Git 기준 | B0 `ffb7be5bd85367156ed9ea86dacbaff4be0f035d`, B1 `d8fbd30b5476a1ceef460c9f3886381a369ac619`를 별도 worktree로 고정했다. 각 버전 원래 native API로 B-01/B-11/B-21 일부 raw audit를 얻었다. | 두 버전의 공통 물리 decoder 및 전체 P/E/역사 pair matrix는 없다. `LEGACY_REPRESENTATION_LIMIT`을 빈 집합이나 EQUAL로 바꾸지 않는다. |
| 실제 planning DML | 고정 current P snapshot으로 14 DML × worker 1/3/5/7, 총 56개의 진단용 native model capture를 시도했다. 결과는 COMPLETE 33, ERROR 19, TIMEOUT 4. P1_FULL w1의 raw product만 798자리다. | 이 capture는 production의 `rewriteHopsDAG` 이전 HOP에 분석을 적용한 사실이 뒤늦게 확인됐다. 33개 COMPLETE도 production 모델 인증으로 쓰지 않는다. |
| runner·회귀 게이트 | source/case hash, native audit, 압축 chunk, 양방향 차집합, 병렬·resume, 손상 및 certificate 재검사를 구현했다. 미고정 입력·역사 applicability는 fail-closed한다. | 실제 전체 campaign manifest와 어댑터가 없어서 단일 명령은 preflight에서 exit 2다. |

## 계획 단계별 상태

| 단계 | 상태 | 남은 완료 조건 |
|---|---|---|
| T0 소스·입력 동결 | PARTIAL | 447 catalog cell 중 현재 in-scope 283개 native input receipt 미고정; 역사 applicability 1,698 pair 미분류. |
| T1 공통 identity | PARTIAL | 더 많은 함수 입력·분기·값 버전 fixture, 모든 proof quotient 충돌, cross-version bijection 검증. |
| T2 현재 P/E adapter | PARTIAL | 실제 모든 DML의 P/E native domain과 full physical decoder, zero error/unknown, 전체 비교. |
| T3 역사 adapter | PARTIAL | B0/B1의 full physical decode와 동일 버전 self-equality; compiler delta와 표현 한계 분류. |
| T4 exact relation | PARTIAL | 실제 거대 domain의 lossless relation/DAG와 독립 coverage verifier. |
| T5 runner·certificate | 구현·단위 회귀 통과 | 실제 P/E/B0/B1 campaign과 독립 certificate 재검사. |
| T6 전체 실행 | 미완료 | 모든 REQUIRED pair의 미처리 frontier/decoder error 0, 역사적 삭제 설명, 단일 명령 PASS. |

주요 구현 파일은 `PlanSpaceComparisonIdentity.java`, `CurrentPPhysicalPlanRows.java`, `ExactPhysicalComparisonRow.java`, `ClosedPlanRelationEnumerator.java`, `PlanningNativeModelCapture.java`, `scripts/fedplanner/build_closed_comparison_cases.py`, `snapshot_plan_space_versions.py`, `run_plan_space_comparison.sh`, 형제 저장소의 `calibration/closed_physical_identity.py`, `plan_space_compare.py`, 그리고 해당 표적 테스트다. 기존 full semantic runner를 대체하지 않고 공통 정렬·차집합 기능을 재사용했으며 새 의존성은 추가하지 않았다.

`preflight.json`은 `uncapturedCurrentCells=283`, `unclassifiedHistoricalPairs=1698`, `campaignManifestPresent=false`, `status=INCOMPLETE`를 기록한다. 전체 447개 catalog는 283 `IN_SCOPE`, 148 `UNSUPPORTED`, 16 `HISTORICAL`이다. `NATIVE_UNSUPPORTED`는 SHA로 고정된 진단 없이 인정하지 않는다. 현재 결과에는 전체 corpus 비교 완료 certificate가 없다.

56개 진단 capture의 19개 ERROR는 export 전 builder/analysis에서 발생했다: P2_PREP·logreg privacy closure 7, SliceLine inlined 출력 이름 충돌 8, w1 glm/l2svm/lm post-CFG 폐쇄 3, w1 gmm-vvi transient realization 참조 1이다. `PlanningNativeModelCapture`가 `constructHops` 뒤 곧바로 P analysis를 호출하고 production `DMLScript`의 `rewriteHopsDAG` 단계를 생략했다. P2의 별도 JVM privacy opt-in도 빠졌으나 단독 추가 재현에서는 같은 오류가 남았다. GLM w3 재현은 CFG transient replay와 native support 검사에서 CPU 실행 중이었다. 따라서 어느 ERROR/TIMEOUT도 feasible plan 0개라는 결론으로 쓰지 않는다.

이후 capture를 `constructHops → prebuilder snapshot → rewriteHopsDAG → source privacy 재등록 → P analysis` 순서로 수정했다. 수정 후 고정 입력 P1_FULL w1 pilot은 `POST_REWRITE_HOPS_DAG_PRE_PLANNER`, pre/post HOP 노드 2,144/1,341, 명시적 source HOP 1개, raw product **568자리**로 COMPLETE다. 같은 절대 경로 snapshot에서 별도 JVM을 두 번 띄워 pre/post graph SHA, native domain SHA, raw count가 일치함을 검사했다. 증거는 `planning-capture/rewrite-probes/w1-P1_FULL.capture.json`·`w1-P1_FULL-repeat.comparison.json`에 있다. 이 재현성은 경로·환경을 바꾼 전역 cache 동등성 증명은 아니다. 같은 수정본에서 P2_PREP w1은 privacy-safe FunOut 오류로 fail-closed했고, GLM w1은 150초 제한에서 TIMEOUT이었다. 이 pilot도 E 집합 또는 plan relation을 열거하지 않았다. 원본 DML의 `cofeePublicRecodeMetadata:true` 입력은 고정됐지만 오류를 해소하지 않았다.

최신 행을 Python `canonical_plan`으로 직접 재검사한 결과 P artifact `P-physical-inlined-v2.ndjson`는 409행(전체 fixture 테스트의 B-01/B-21 재실행 중복 포함), 21개 fixture/56개 key, SHA-256 `6ed24afc8cd8a2bf6547d19c39d14bab3c391af1427aa8b7b8702dd1fc0daeae`이었다. E artifact `E-physical-inlined-v2.ndjson`는 216행, 같은 21개 fixture/56개 key, SHA-256 `20a66eaf1de8da8e5ace84f63451e47f1347c249fb898c9b19aa70015627dc87`이었다. Fixture별 두 차집합은 각각 0개였다. `current-pe-fixture-set-check.json`은 두 입력 hash·identity 코드 hash·각 fixture의 양방향 개수를 기록한다(SHA-256 `ba72f753d9adf1dfa96c01dcd82a36f63eba386060b1f9eca3a06d81b3fdd55f`). Java 교차 테스트가 accepted proof multiplicity도 검사한다.

별도 read-only reviewer는 초기 21개 fixture의 P/E 행에서 추가적인 거짓 일치 결함을 찾지 못했지만, 두 projector가 같은 analysis 기반 logical catalog를 쓰는 공유 누락 위험을 지적했다. 이후 독립 prebuilder·원본 DML oracle이 실제 B-21 함수 인자 `A → f(X)` 누락을 검출했다(수정 전 테스트 실패). 수정 후 B-02 transient 5개, B-21 transient 3개 및 인라인 함수 입력 1개가 정확히 일치한다(독립 oracle 2/2). B-17의 `f(X+1)`는 표현식 source occurrence로 기록하고, 함수 입력 경계가 비방출이면 `targetEmitted=false`를 명시한다. 이 수기 oracle은 두 fixture 범위이며 전체 workload의 논리 관계 완전성 증명은 아니다.

B0·B1의 B-01 보호 입력 full native stream에서는 버전마다 P raw 256개 중 accepted 1/rejected 255/error 0, E raw 1개 중 accepted 1/error 0을 확인했다. 이 수치는 full physical equality가 아니다. 과거 audit 행에 공통 value version, source authority, geometry, logical relation이 없어 `LEGACY_REPRESENTATION_LIMIT`을 유지한다.

## 재현과 검증

```bash
cd /home/mchoi/systemds-g009-integration
scripts/fedplanner/run_plan_space_comparison.sh --jobs 4 --resume

cd /home/mchoi/cofee-evaluation
TMPDIR=/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921 \
  python3 -m unittest calibration.tests.test_plan_space_compare -q
TMPDIR=/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921 \
  python3 -m unittest calibration.tests.test_plan_space_verify -q
```

첫 명령이 exit 2로 중단되는 것은 현재 분모의 미완료를 드러내는 의도된 결과다. `plan_space_compare.py check-certificate`는 완료된 실제 campaign에만 적용한다. 기존 full semantic `R` gate는 별도이며, 이 계약의 `runtimeSemanticCoverage`는 `NOT_ASSESSED_BY_THIS_CONTRACT`다.

최신 동결 snapshot에서 공통 identity·독립 논리 oracle·P/E projector·native relation/capture/exporter/adapter 등 12개 Maven 표적 suite는 총 45 case, 실패·오류 0, 환경 미충족으로 skipped 1이었다. 실행된 44 case는 모두 통과했다. B-21 relation 출력은 raw 40,587,440,947,200 = accepted 192 + rejected 40,587,440,947,008 + unknown 0을 기록했다. Python runner/기존 semantic gate 회귀는 36/36, catalog generator는 2/2 통과했고 양쪽 repo `git diff --check`는 통과했다.

## 결론을 바꾸는 데 필요한 일

1. 283 current cell의 DML/import/privacy/config/metadata와 version별 compiler 입력을 각각 해시로 동결하고, 1,698 역사 pair의 적용 여부를 증거와 함께 확정한다.
2. 현재 E와 B0/B1 P/E native domain을 같은 입력에서 캡처하고 모든 좌표를 공통 물리 행으로 해독한다. 과거 compiler가 동일 입력을 표현하지 못하면 해당 pair를 근거 있는 비완료 상태로 유지한다.
3. 큰 raw 곱을 lossless relation으로 전수 처리하고 독립 verifier로 원래 Cartesian domain의 accepted+rejected coverage 및 양방향 차집합을 재검산한다. Timeout·OOM·unknown을 완료로 바꾸지 않는다.
4. 모든 현재 P/E 반례를 고정·수정하고 새 source snapshot의 전체 matrix를 재실행한다. 과거 old-only 삭제에는 compiler 차이 또는 의미론 정정 근거를 연결한다.

이 조건을 만족하기 전까지는 현재 fixture의 일치를 전체 workload의 feasible plan 완전성이나 합법성으로 확대하지 않는다.
