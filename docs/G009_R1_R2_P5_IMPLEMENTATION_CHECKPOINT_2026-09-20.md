# G009 P5 + R1-A + R2 구현 체크포인트

- 날짜: 2026-09-20
- 기준 저장소: `systemds-g009-integration`
- 기준 커밋: `e29f4fc30d19cc228b013e786d76424cb7954d7e` + 현재 미커밋 구현

## 1. 결론과 현재 상태

P5의 반복 비용 축소, R1-A의 DP owner index, R2의 exact no-op relation 재사용을 구현했고 집중 회귀 검증을 통과했다. 그러나 **현재 소스 조합의 성능 개선량은 아직 측정하지 않았다**. 아래 P3/P4/P5a 수치는 이전 host 진단의 역사적 참고값이며, 현재 구현이나 production Docker E2E의 성능 결과가 아니다.

현재 성능 판정을 막는 작업은 다음과 같다.

1. 변경을 clean commit으로 고정한다.
2. 그 커밋에서 JAR를 다시 package한다.
3. 현재 커밋·tree·JAR와 일치하는 immutable stage를 만든다.
4. 같은 Docker 조건에서 production 후보 E2E와 정확성 산출물을 검증한다.

따라서 이 체크포인트의 판정은 **구현 및 집중 정확성 검증 완료, 현재 성능 qualification 미완료**다.

## 2. 구현한 최적화

### 2.1 P5: 반복 hash·lookup·topology 비용 축소

| 변경 | 구현 내용 | 보존한 계약 |
|---|---|---|
| broadcast capability 사전 계산 | `NativePlacementContinuity` 생성 시 broadcast 가능한 `ValueVersionKey` 집합을 한 번 만들고 privacy 판단에서 재사용한다. | 동일 value-version alias 전체의 legal alternative를 기준으로 한다. |
| factorized topology revision 재사용 | occurrence의 local candidate facts가 구조적으로 동일하면 보수적 invalidation 집합에 포함돼도 factorized topology를 다음 revision으로 넘긴다. | flat topology와 completed support memo는 기존의 더 넓은 invalidation 경계를 유지한다. local facts가 바뀌면 fresh rebuild한다. |
| direct-binding 정적 문맥 hoist | node/anchor/input/template index를 direct closure 내부 pass마다 다시 만들지 않고 해당 closure 구간 밖에서 한 번 만든다. | FULL/DELTA/SHADOW의 선택 및 비교 경계는 유지한다. |
| executable-reference hash bucket | normalized string 집합 대신 구조 hash bucket과 `equals` 확인으로 membership을 판정한다. | hash collision은 bucket 내부 `equals`로 분리하므로 참조 동등성은 바뀌지 않는다. |
| analysis-local identity hash cache | 분석 scope에서 immutable 객체의 구조 hash를 identity로 memoize한다. scope 종료 시 ThreadLocal을 제거한다. | scope 밖에서는 원래 `hashCode()`를 사용하며 객체 equality를 대체하지 않는다. |
| product hash 선계산 | `ProductRoute`와 `NativeProofProduct`가 생성 시 정확한 list-hash를 계산해 보관한다. | Java list hash 순서와 모든 equality 구성요소를 유지한다. |
| topology hit 계측 경계 수정 | cache hit 확인을 `PROOF_TOPOLOGY` expansion timer 밖으로 옮겼다. build와 hit aggregate counter는 모두 유지한다. | phase 시간은 실제 expansion call만 세고 hit 진단은 별도 counter로 남긴다. |

`ProductRoute`의 binding slot 정규화는 shared canonical comparable list를 사용한다. 중복 제거와 canonical 순서를 동시에 보존하며, factorized relation을 flat leaf로 펼치지 않는다.

### 2.2 R1-A: 기존 DP owner index 재사용

`ExactPhysicalModel.build`는 별도의 owner map을 다시 만들지 않는다. 각 decision node에서 이미 `CandidateRuleFacts`가 제공하는 `orderedFactsForParent(node.key())`를 호출하고, 그 owner-local ordered list를 `alternatives`에 전달한다. 이로써 exact physical model의 전체 fact 목록 반복 스캔과 중복 index 구축을 모두 피한다.

비용 구조는 전체 스캔 기준의 대략적인 `Ndecision × F`에서 기존 index 조회와 `owner-local work`로 바뀐다. `CandidateRuleFacts`가 보장하는 owner별 순서, `CompiledHopKey` identity 의미, 최종 alternative signature sort, empty-domain 진단 내용은 유지한다.

### 2.3 R2: exact no-op closure identity 재사용

`LogicalBoundaryRealizations.close`는 `List.equals` 대신 factorized support를 인지하는 `CandidateClosureDependencies.structurallyEqual`로 고정점 여부를 판정한다. 새 결과가 구조적으로 정확히 같으면 새 목록이 아니라 입력 `current`를 반환한다.

이 변경은 exact no-op에서 다음 identity를 그대로 보존한다.

- analysis-owned fact list
- 기존 `CandidateRuleFact`
- 기존 `CandidateSupportRelation`
- owner/epoch와 연계된 객체 권한

서로 다른 객체로 만든 동일한 factorized relation의 `sameSupportAs`와 `containsAllSupportOf`도 leaf materialization 없이 성립하는 테스트를 추가했다.

## 3. 되돌린 최적화: 전역 canonical sort 제거

P5 작업 중 direct DAG dependency 결과에 대한 최종 canonical sort를 없애는 변경을 검토했으나 되돌렸다. 각 dependency-state 결과 목록이 개별적으로 canonical이어도 여러 state의 결과를 이어 붙인 전체 목록은 전역 canonical 순서를 보장하지 않는다.

따라서 두 direct DAG aggregation 지점 모두 `canonicalReferences(...)`를 유지한다. 이 정렬을 제거하면 다음 문제가 생길 수 있다.

- state 연결 순서에 따른 결과 순서 변경
- ordinal 및 tie-break 입력 변경
- snapshot byte 차이
- 같은 후보 집합이라도 downstream canonical rank가 달라지는 회귀

성능보다 결정적 순서 계약을 우선했다. 전역 순서가 불필요하다는 별도 증명이 없는 한 이 sort는 제거 대상이 아니다.

## 4. 검증 결과

### 4.1 집중 검증

| 검증 | 결과 |
|---|---:|
| Maven compile | 통과 |
| 8-class 통합 suite | 108 tests: 107 통과, 1 policy skip, 0 failure, 0 error |
| `CandidateClosureDependencies` 인접 suite | 9/9 통과 |
| `NativeProofProduct` 인접 suite | 15/15 통과 |
| builder oracle 인접 suite | 5/5 통과 |
| unknown-metadata reconciliation 인접 suite | 6/6 통과 |
| R1-A 수정 후 exact physical model 집중 suite | 12/12 통과 |
| `SearchSpaceAttributionTimingTest` | 6/6 통과 |
| 변경 집합 whitespace 검사 | 통과 |

8-class suite에는 R1-A의 exact physical model 선택 경계와 P5/R2의 변경된 placement 경계를 함께 넣었다. exact physical model 관련 4개 selector class는 합계 12/12가 통과했고, placement 변경 관련 class는 95 통과와 의도된 policy skip 1건이었다.

R1-A가 중복 owner map 대신 `CandidateRuleFacts.orderedFactsForParent`를 재사용하도록 수정한 뒤에도 exact physical model 관련 4개 집중 class, 합계 12개 테스트가 모두 통과했다.

R2 전용 검증에서는 `LogicalBoundaryRealizationsTest`와 `CandidateSupportRelationTest` 합계 33개가 통과했다. 반복 closure의 list/fact/relation identity 재사용과 factorized relation 비교 시 `leafMaterializationCount == 0`을 확인한다.

계측 경계에는 `SearchSpaceAttributionTimingTest`의 전용 회귀 테스트를 추가했고 전체 6개가 통과했다. 이 테스트는 public proof materialization의 최초 호출만 `PUBLIC_PROOF_MATERIALIZATION`에 정확히 한 번 계상되고, 같은 질의의 memo hit가 해당 phase에 다시 진입하지 않음을 검증한다.

### 4.2 독립 코드 리뷰 결과

독립 리뷰에서 critical 또는 high finding은 없었다. 보고된 medium/low finding은 모두 해소했다.

- R1-A는 중복 owner index 구축을 제거하고 기존 `CandidateRuleFacts.orderedFactsForParent`를 재사용한다.
- public proof materialization의 timed-call 및 memo-hit 비재진입 계약을 전용 테스트로 고정했다.

남은 성능 위험은 analysis-local identity hash cache의 retained-memory 영향이다. 기능 정확성 회귀는 집중 테스트에서 발견되지 않았지만, 메모리 영향은 현재 revision의 fresh package와 immutable-stage 실행에서 peak RSS를 다시 측정하기 전까지 미확정이다.

### 4.3 package-wide 결과와 해석 제한

package-wide wildcard 실행은 626 tests에서 7 failures, 14 errors, 14 skips를 기록했다. 이 결과 때문에 package-wide 전체를 통과했다고 주장하지 않는다.

그중 대표적인 CFG non-convergence인 `PrivateAggregateFourPlannerContractTest`는 관련 production 파일 6개를 일시적으로 HEAD 상태로 복원한 조건에서도 재현되어 **현재 P5/R1-A/R2 diff가 만든 회귀가 아님을 확인했다**. 별도의 `PlacementIdentityKnownEqualityContractTest#knownFunctionBoundaryCanonicalOriginRemainsByteIdentical` 오류도 변경된 hash/order 경로 진입 전의 기존 standalone `FunctionOp` fixture 문제와 일치한다.

다만 이 확인은 package-wide의 모든 7 failures와 14 errors를 일괄적으로 “기존 문제”라고 증명하지 않는다. 현재 변경의 채택 근거는 통과한 집중·인접 suite와 위 두 오류의 분리 재현이며, 전체 package inventory drift는 별도로 추적해야 한다.

## 5. R0 harness와 provenance 점검

### 5.1 계측·qualification 경계

기존 harness는 새 계측 프레임워크를 추가하지 않고 다음 계약을 확인할 수 있는 상태다.

- `CandidateFormationTiming`: final normalization부터 receipt consumer success까지 14개 exclusive phase를 구성하며 관련 테스트 5/5 통과
- `run_g009_p0_baseline.sh qualify-glm`: clean worktree, current HEAD/tree/JAR, stage, receipt, log, phase, calls, `exactPhaseCalls` 검사
- profiler-off fresh JVM: worker 1, `P2P2D`, repetition 1, agent/JFR/`JAVA_TOOL_OPTIONS` 없음, heap 8 GiB, active processor 8
- Docker client/daemon 29.6.1과 필요한 image가 로컬에 존재

현재 발견된 G009 stage는 P0의 과거 revision용이며 current source와 맞지 않고 `reference_tree_sha`도 비어 있다. 현재 `target/SystemDS.jar` 역시 P0 시점의 stale artifact다. 이 둘을 현재 성능 증거로 재사용하면 안 된다.

### 5.2 외부 reference bundle 복구

외부 benchmark workspace에서 삭제되어 있던 `target/lib`를 검증된 stage의 동일 파일로 hardlink 복구했다.

- 복구 대상: `/home/mchoi/g014-planner-compile-benchmark-56f20af559-20260820-v1/build/target/lib`
- 파일: 316/316 inode hardlink 일치
- canonical tree SHA-256: `86c7af015f48e3a6035c907b1d4cb3396a505db2125fe612b8f86e2ebf00979d`
- canonical stage validation: 통과
- published bundle validation: 통과
- combined lifecycle descriptor validation: 통과

따라서 clean commit과 package가 끝나면 현재 immutable stage를 만드는 외부 reference blocker는 없다.

## 6. 역사적 성능 참고값 — 현재 결과가 아님

다음은 동일 계열 GLM host 진단의 완료된 과거 측정이다. production Docker E2E qualification이 아니며, 현재 P5 + R1-A + R2 working tree의 성능을 나타내지 않는다.

| stage | `buildAnalysis` | canonical export | peak RSS |
|---|---:|---:|---:|
| 초기 진단 | 716.591801515초 | 미고정 | 미고정 |
| P3 `c0dc78e819` | 450.976635131초 | 365.349828672초 | 35,859,276 KiB |
| P4 `e29f4fc30d` | 279.017960532초 | 368.578775850초 | 14,030,516 KiB |
| P5a r8 | 245.047392853초 | 358.010841376초 | 14,725,452 KiB |

P5a build의 주요 exclusive phase는 closure replay 125.723093294초, proof topology 65.822146064초, analysis exclusive 36.897856933초, clause merge/canonicalization 5.752초, proof grounding 4.066초, support-product relation materialization 3.164초, receipt/rank preparation 2.387초였다. P5a snapshot SHA-256은 다음과 같다.

`78bb2d116061fc7d8aad97059c56cba3665367215bb3fe3a1679a613a077ed09`

P5a benchmark JVM은 약 06:34에 시작됐고 현재 hash-cache source는 약 06:39에 수정됐다. 따라서 **P5a 245.047초에는 현재 hash-cache 변경이 포함되지 않는다**. 이후 R1-A와 R2도 추가됐으므로 과거 P5a 수치로 현재 speedup을 계산하거나 60초 목표 진척을 주장할 수 없다.

## 7. R3-A0 설계 판정

R3의 첫 단계는 **method-local whole-input exact reuse만 조건부 GO**다. physical worklist는 항상 기존대로 먼저 실행하고, 이전 grounding 시점의 raw nodes/domain/facts 및 physical/reaching edges가 모두 정확히 같을 때만 이전 grounded facts를 재사용한다. 그 경우에도 final CFG replay와 pending obligation은 생략하지 않는다.

다음 접근은 현재 NO-GO다.

- rule/emission이 비슷하다는 이유만으로 과거 grounded fact 복원
- physical worklist 중간에 과거 grounded fact 주입
- source support, anchor, privacy, reaching edge, incomplete source, topology 또는 deferred proof 일부를 생략한 key

R3-A0는 WATCH 상태로 진행해야 한다. P5a의 closure replay 125.723초 전체가 grounding 비용은 아니며, 분리 계측된 proof grounding은 4.066초뿐이다. 따라서 closure 전체가 제거될 것처럼 예상하면 안 된다. 현재 immutable-stage benchmark에서 exact-reuse hit와 실제 wall 감소를 확인하기 전에는 더 큰 per-owner R3-A1 framework로 확장하지 않는다. 현재는 R3-A1의 채택을 정당화할 숫자 기반 threshold가 아직 없다.

## 8. 남은 작업과 완료 기준

| 순서 | 남은 작업 | 완료 증거 |
|---:|---|---|
| 1 | 현재 구현과 문서를 review하고 clean commit으로 고정 | review finding 해소, clean tree, commit SHA |
| 2 | current commit에서 package | 새 `target/SystemDS.jar`, 성공 로그 |
| 3 | current immutable stage 생성 | HEAD/tree/JAR/reference hash가 일치하는 descriptor |
| 4 | host GLM 진단 | snapshot byte/hash parity와 phase별 현재 수치 |
| 5 | 필요 시 R3-A0 구현·검증 | exact no-change reuse, invalidation, FULL/DELTA/SHADOW parity 테스트 |
| 6 | production Docker E2E | 고정 환경의 end-to-end wall, correctness receipt, peak RSS |
| 7 | R4/R5 필요성 재판정 | 60초까지 남은 비용에 근거한 GO/NO-GO |

최종 완료 판정에는 다음이 모두 필요하다.

- legal candidate와 canonical selection/receipt 보존
- clean immutable revision에서 fresh package
- 동일 Docker 조건의 production 후보 E2E 측정
- 60초 absolute 목표와 기존 10배 ratio gate를 각각 보고
- memory gate 및 snapshot/receipt 정확성 통과

현재 체크포인트는 이 중 구현·집중 테스트·harness 준비만 충족했다. 성능 개선률과 최종 목표 달성 여부는 미확정이다.

## 9. 관련 문서

- [현재 알고리즘 상세 설명](G009_CURRENT_ALGORITHM_EXPLAINED_2026-09-20.md)
- [60초 최적화 개정 계획](G009_REVISED_60S_OPTIMIZATION_PLAN_2026-09-20.md)
- [P3 최종 host 진단](G009_P3_FINAL_DIAGNOSTIC_2026-09-20.md)
- [P4 factorized relation host 진단](G009_P4_FACTORIZED_RELATION_DIAGNOSTIC_2026-09-20.md)
- [후보 E2E 1/10 계획](G009_CANDIDATE_E2E_TENTH_PLAN_2026-09-19.md)
