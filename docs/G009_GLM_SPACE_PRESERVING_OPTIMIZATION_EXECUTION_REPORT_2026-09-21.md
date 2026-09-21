# G009 GLM 후보 공간 보존형 최적화 실행 보고서

- 날짜: 2026-09-21
- 기준 계획: [G009_GLM_SPACE_PRESERVING_OPTIMIZATION_PLAN_2026-09-21.md](G009_GLM_SPACE_PRESERVING_OPTIMIZATION_PLAN_2026-09-21.md)
- 상태: **부분 구현·회귀 테스트 통과, 성능 채택 보류. GLM planning E2E 완료 없음.**
- 작업 트리: `/home/mchoi/systemds-g009-integration`, HEAD `a6281207cf520171af47f5f8755a34cbd28ecf37`. 이 작업 이전부터 존재하던 미커밋 selector 독립 공간 변경은 되돌리지 않았다. 이번 최적화 역시 커밋·푸시하지 않았다.

## 핵심 판정

같은 P2P2D GLM/4-worker planning-only 입력에서 원래 JAR와 수정 JAR는 모두 `scripts/builtin/glm.dml:939`의 `TRead Y_prob`에 대해 **`No privacy-safe physical placement for transient replay`**로 종료했다. 따라서 어느 실행도 analysis 완료, selector 진입, CandidateE2E receipt 또는 성공한 initial planning E2E 시간을 제공하지 않는다. 동일 오류까지의 coordinator 내부 경과시간은 원래 JAR 182.87초, 수정 JAR 97.66초로 단일 관측상 46.6% 짧았지만, **성공한 planning의 46.6% 가속이 아니다**. 런타임 학습은 실행하지 않았다.

후보 삭제, 비용 dominance, privacy 완화, runtime fallback은 추가하지 않았다. 작은 테스트에서 기존 decoded 후보·권한·순서는 유지됐지만 독립 GLM 전수 oracle 및 전 목적함수 search-space 완전성은 증명되지 않았다. 아래 변경은 정확성·성능 채택 기준을 충족하지 못했으므로 실험 작업 트리에만 보유한다.

## 단계별 구현·판정

| 단계 | 수행한 내용 | 검증 및 판정 |
|---|---|---|
| O0 최소 계측 | `DMLTranslator`에 opt-in `sysds.fedplanner.phaseMarkers` analysis/planner 경계 추가. 별도 workspace의 `run_LAN_docker.sh`에 명시적 `--diagnostic-glm-planning` 경로와 validator 추가. frozen seven-workload manifest는 유지. | 원래/수정 JAR, 입력 DML, compile XML, worker 수와 JVM 조건 고정. 그러나 실패 전에 `analysis_end`가 없고 selector 시간은 측정 불가. aggregate SearchSpaceMetrics의 실제 GLM 처리량/peak memory 계측은 미완료. 진단 경로는 formal campaign 증거가 아님. |
| O1 구조 비교 | 기존 `PlacementIdentity.StructuralArena`에 proof/support의 exact child handle 구조 키와 identity/structure 독립 상한 65,536을 추가. overflow는 exact 경로로 복귀. | 충돌·다른 객체·상한 0 테스트와 canonical/completeness 테스트 통과. 첫 시도에서 nullable handle 자동 unboxing NPE를 발견해 수정. 성공한 GLM E2E가 없으므로 성능 채택 보류. |
| O2 정렬 재사용 | 기존 `CanonicalText` cursor의 동일 immutable subtree를 정확히 같은 offset에서 한 번에 건너뛰는 경로와 순서 회귀 테스트 추가. | 기존 lexical 순서 테스트 통과. 별도 proof/template eager sort 시도는 문자열 생성 복잡도 증가 우려로 제거. analysis-wide bounded key 재사용은 미완료. |
| O3 재생성 방지 | 동일 emission/template이고 새 native binding이 필요 없는 경우 원본 emission/fact를 재사용. `LogicalBoundaryRealizations`도 재계산 대상이 없으면 기존 fact 유지. | fixed-point/full-vs-incremental, canonical, authority 관련 테스트 통과. GLM 성공 비교가 없으므로 성능 채택 보류. |
| O4 인덱스/dirty | direct closure 루프 안에서 불변 node/anchor/input/template 인덱스를 한 번만 구성. 정확히 바뀐 row와 재방문 cone을 분리; 앞·뒤 revision의 support edge를 모두 고려. | candidate-dependent nativeByParent/nativeRealizations/executableReferences는 아직 매 pass 전체 재구축: **부분 구현**. producer→consumer 단방향 dirty cone은 충분한 증명 없이 안전하지 않아 보수적 양방향으로 복귀. GLM 오류는 양방향에서도, 원래 JAR에서도 발생해 이 시도만의 회귀라고 단정할 수 없음. |
| O5 proof/closure | 기존 `NativePlacementContinuity.nextRevision`이 동일 occurrence row를 memo entry마다 재비교하지 않고 revision당 한 번 exact 비교하도록 개선. 완료 support memo는 기존 의존 occurrence가 모두 그대로일 때만 이동. | 관련 revision/test 통과. public proof의 geometry/privacy/authority를 포함한 완전한 의존성 서명과 physical rebuild 전체의 재사용은 **미완료**. 위험한 topology-only 재사용은 하지 않음. |
| 추가 진단비용 시도 | 성공 경로에서 `closureTrace` deep hash 대신 크기만 남기는 변경을 시험. | 같은 오류까지 97.66→96.44초, 약 1.3% 단일 차이로 기준 5% 미만. **성능 채택 제외·원복**. |

## 단일 GLM 실행 근거

원시 로그/JAR/스크립트: `/home/mchoi/g009-glm-optimization-20260921/`. 모든 실행은 `run_LAN_docker.sh --diagnostic-glm-planning --planning-only --skip-net-check --workers 4 --dataset P2P2D --salg glm --net-profile lan --conf mkl-cost`, `-Xmx16g -Xms16g -Xmn1600m -XX:ActiveProcessorCount=8` coordinator, `PLANNING_REPETITIONS=1`. 입력 DML SHA-256 `8df7a7a608c3fbea858b88eb07366dee9e23e5650cbaae5e18163914e0fda4a7`; compile XML SHA-256 `e24c3b4a4e6675af6c6af0daa7e46ea79a1299dfb922c32594b17b35e89b40e7`. worker 컨테이너 네 개는 준비했으나 planning-only 계약상 worker JVM 및 학습 runtime은 실행하지 않았다.

| 실행 | JAR SHA-256 | 제한/관측 | 결과 |
|---|---|---|---|
| 원래 baseline2 | `8f10f1891f3cd99bd492b1ad5ac4913aff054fe74afd2d1f16370a0acc4833df` | 240초 상한; coordinator `real 182.87`, `user 264.49`초 | 같은 `Y_prob` transient replay 오류. 이전 baseline 실행은 timeout 전달 오류로 수동 중단한 censored 표본이므로 비교에서 제외. |
| candidate1 | `68233539920df6e0c445fabd29f824d0cd11e4b062b899adbacdcb99f1e394f2` | 22.31초 | O1 nullable handle NPE; 수정 전의 무효 표본. |
| candidate2 | `883956fc9f3560d352036cebc5c9472037187c2e570f775d33e18195841a5c65` | 120초 상한 | analysis 중 timeout; completed planning 시간 없음. |
| candidate3 | `b501c63135f0058ebf28acdf2bdbfe0cf8b7c1d9f608ac6c1c75ed080e643fd8` | 120초 상한 | analysis 중 timeout. eager sort 변경은 제외. |
| candidate4 | `7ffe1b7df9eb2b82e414785fb37c7add5e1bd835da18608528f06867592a06f1` | 120초 상한 | analysis 중 timeout. eager sort 변경은 제외. |
| candidate5 | `140192dc455ddbfbb0b2dfae865d146d989adacbf03e025e462bd799eb5cd8a8` | coordinator `real 91.85`초 | 같은 `Y_prob` 오류. 단방향 dirty 실험 포함; 이후 복귀. |
| candidate6, 현재 보유 JAR | `209cc3b6c8ef0c9f921c42bda663f48578d69a0a73aa996189457bf9d0919f80` | 180초 상한; coordinator `real 97.66`, `user 155.23`초 | 같은 오류. 원래 JAR 대비 time-to-error 46.6% 감소·CPU 41.3% 감소라는 **단일 진단 관측**만 가능. |
| candidate7 | `b758f9855c2897b1acc3397918ce95d9912f445641cf6e6d866e6fa795519ffa` | coordinator `real 96.44`초 | 같은 오류. deep-hash 생략은 단독 효과 불명확해 원복. |
| candidate8, 설정 판별 | candidate6과 동일한 `209cc3b6…` | `-Dsysds.privacy.allowPublicRecodeMetadata=true`, coordinator `real 105.12`초 | 같은 오류. P2의 transformencode metadata 예외와 GLM transient 실패는 같은 설정 문제로 묶을 근거가 없음. 성능 비교 표본에서 제외. |

Docker 전체 wall에는 setup/network shaping/cleanup이 포함되어 coordinator planning과 비교하지 않았다. 시간 제한 종료는 completed E2E로 취급하지 않았다. peak memory의 동일 방법 비교가 없으므로 계획의 5% 메모리 기준도 판정 불가다. `G009_PHASE analysis_begin`만 기록됐고 `analysis_end`/`planner_begin`은 없었다. GLM 공통 analysis 실패이므로 heuristic·DP-global·DP-local의 최종 selector 시간·winner 비교를 수행할 수 없었다.

## 정확성 검증과 남은 작업

- 변경 뒤 targeted `NeutralPlacementFixedPointCompositionTest`, `NativePlacementContinuityTest`, `ProductionDecodedPlanSpaceCompletenessTest`, `IndependentPlanSpaceGenerationCompletenessTest`: 50 case, failure/error 0, skip 2. fixed-point 비교는 protected action, CFG branch, function fixture를 포함한다.
- `PlacementStructuralArenaTest`, `CandidateRealizationCanonicalizationTest`와 기존 selector/decoded completeness/privacy 12-class 묶음도 통과했다. 테스트는 작은 fixture의 회귀 잠금이며 GLM search space 전수 증명이 아니다.
- `mvn -q -DskipTests package`, `git diff --check` 통과. 별도 repo-wide lint/static-analysis 명령은 수행하지 않았다. 원래 JAR도 같은 오류이므로 실패를 새 최적화의 회귀나 privacy-safe 계획 부재로 단정하지 않는다.
- **미완료/차단:** `Y_prob` TRead의 reaching definition별 source realization, privacy exclusion, physical replay 호환성 원인을 독립적으로 판별해야 한다. 합법적 선택이 없다면 infeasibility를 증명하고 GLM benchmark 입력을 정당하게 바꿔야 하며, 버그라면 privacy/authority를 유지하며 수정해야 한다. 그 전에는 성공한 GLM 전체 planning·CandidateE2E·selector 분해, 세 planner, P1/P2/SLICELINE 최종 matrix, peak memory, 60초 및 최종 채택 판정을 할 수 없다. 전체 oracle/pruning 의무도 별도 미완료다.
- P2에서 필요했던 public-recode-metadata opt-in을 GLM에도 적용해 보았으나 같은 오류였다. 해당 속성은 코드상 명시적으로 metadata-only `transformencode` release spec을 요청한 경우에만 작동하며, 생성 GLM DML 및 `glm.dml`에는 그 요청이 없다. 따라서 이를 일반적인 privacy 완화/수정책으로 채택하지 않는다.
- **다음 순서:** 작은 `Y_prob` CFG 반례와 full/incremental/reference 비교 → 원인 수정 또는 입증된 infeasibility 판정 → 동일 baseline/수정 JAR의 성공한 planning-only GLM 한 번씩 → analysis/selector/CandidateE2E/peak memory 비교 → O4 candidate-dependent delta 및 O5 권한 서명 검증 후 필요 시 추가 최적화. 오류까지의 빠른 도달을 성공으로 포장하지 않는다.
