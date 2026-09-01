# Privacy empty-domain 실패 측 audit 보완

**작성일:** 2026-08-31  
**대상:** privacy filtering 결과 occurrence의 physical candidate domain이 비는 fail-closed 경로  
**검증 artifact:** `audit-results/fed-planner-privacy-failure-audit-20260831T203831Z`

## 1. 문제와 범위

기존 candidate-space audit는 완성된 `PlacementAnalysis`를 성공 경로의 끝에서만 기록했다. 그러나 privacy closure가 emitted occurrence의 모든 state를 제거하면 analysis 생성 전에 `DMLRuntimeException`을 던진다. 따라서 legality와 fail-closed 동작은 맞았지만, 바로 그 실패 occurrence가 JSONL에 남지 않았다.

이번 변경은 이 **관측성 공백만** 보완한다. candidate 생성, privacy legality, selector 입력, selector 선택 및 예외 조건은 변경하지 않았다.

## 2. 구현

- `PlannerCandidateSpaceAudit.recordPrivacyFailure(...)`를 추가했다.
  - 기존 property `sysds.fedplanner.space.audit`가 켜진 경우에만 동작한다.
  - 완성되지 않은 analysis를 꾸며내지 않고, 별도 schema `fedplanner-candidate-space-privacy-failure-v1`를 쓴다.
  - occurrence/replay identities, opcode/HOP, effective privacy, pre-privacy states/rules, 빈 published domain, privacy exclusions, failure reason을 기록한다.
  - analysis가 생성되지 않았으므로 `analysisFingerprint`는 명시적으로 null이다.
- `NeutralPlacementGraphBuilder.closePrivacyDomains`는 기존과 동일한 empty-domain 조건에서 filtered node snapshot을 만든 뒤 audit hook을 호출하고, 즉시 동일한 `No privacy-safe physical placement` 예외를 던진다.
- `SharedPrivacyMovementLegalityAuditTest`는 strict `PRIVATE` direct-print에 대해 다음을 함께 고정한다.
  1. 기존 fail-closed 예외 유지
  2. JSONL row 정확히 1개
  3. effective privacy가 `PRIVATE`
  4. pre-privacy domain은 비어 있지 않음
  5. published domain과 `publishedStatesP`는 비어 있음
  6. published exclusion reason은 `PRIVACY`
  7. pre/published rule evidence 존재

## 3. 동작 불변성

- audit는 기본적으로 꺼져 있다.
- audit가 꺼져 있으면 추가 파일 I/O가 없다.
- privacy-safe candidate를 복구하거나 새로 추가하지 않는다.
- selector까지 빈 domain을 전달하지 않는다.
- 예외 종류와 메시지 조건을 유지한다.
- 성공 경로의 기존 `fedplanner-candidate-space-v1` schema는 변경하지 않는다.

## 4. 검증

집중 회귀 4개 클래스, 총 **28 tests**:

| Test class | tests | failures/errors/skips |
|---|---:|---:|
| `SharedPrivacyMovementLegalityAuditTest` | 2 | 0/0/0 |
| `SharedPrivacyPlacementAnalysisContractTest` | 8 | 0/0/0 |
| `PlannerSpaceAuditTest` | 16 | 0/0/0 |
| `FederatedPlanLocalCostPrivacyConstraintTest` | 2 | 0/0/0 |

검증 결과:

- Maven targeted tests: exit 0
- `git diff --check` (관련 3 files): exit 0
- artifact/source SHA-256 receipts 생성 완료

## 5. 수정 파일

- `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerCandidateSpaceAudit.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
- `src/test/java/org/apache/sysds/hops/fedplanner/placement/SharedPrivacyMovementLegalityAuditTest.java`

## 6. 판정

privacy 때문에 candidate domain이 완전히 빈 occurrence도 이제 fail-closed throw **직전** audit JSONL에 남는다. 이는 audit completeness 보완이며 planner legality/search semantics의 변경은 아니다.
