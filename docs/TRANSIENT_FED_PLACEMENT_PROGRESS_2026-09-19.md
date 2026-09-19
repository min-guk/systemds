# Transient/FED placement 진행 상황 — 2026-09-19

기준 시각: 2026-09-19 02:10 +0200
기준 HEAD: `d8fbd30b5476a1ceef460c9f3886381a369ac619`
작업 상태: **주요 결함 수정 및 current-source 재인증 완료, 미커밋, G009 전역 증명 진행 중**

## 현재 판정

Transient/FED placement 독립 리뷰에서 재현된 세 결함은 현재 작업 트리에서 수정됐다.
독립 재검토에서 추가로 발견한 FULL ROLL suppression과 `REV -> ROLL -> EXP` 연속 동적 layout
누락도 수정했다. transient replay의 복수 native continuity proof 중 첫 대안만 남기던 후속
결함과 dynamic native authority의 TWrite/TRead replay 손실, exact replay 이후 receipt-domain 축소도
수정했다. 최신 post-index current-source integration gate는 23개 class에서
134 discovered / 130 executed-pass / 4 PUBLIC-only skips / 0 failures / 0 errors로 통과했고,
실행 전후 전체 production/test source hash와 branch-manifest 관련 hash가 동일했다.

최신 독립 재검토 판정은 architecture **APPROVE_FOR_BOUNDED_SCOPE**, code review
**REQUEST CHANGES**다. Architecture 판정은 아래 exact/dynamic authority와 SCC/replay 회귀 범위에
한정된다. Code review는 upload-relocation fixture timeout, 5개 core 파일에 한정된 branch inventory,
recursive Tarjan 깊이 위험 때문에 최종 승인을 보류했다. 근거는
[CODE_REVIEW.md](../build/plan-space-audit-20260919/g009-final-review/CODE_REVIEW.md)다.

전역 `Decode(S(P)) = LegalPhysicalPlans(P)` 증명은 완료되지 않았다. G009의 21 proof obligation,
48 rule-family runtime-parity row, unfiltered universe, closure/order/idempotence, action/anchor,
policy non-reentry 완료 조건은 그대로 열려 있다. Docker runtime qualification도 계속 열려 있으며
이번 compiler/placement 완료 근거에 포함하지 않는다.

## 완료한 구현

### 1. 동적 ROW REV 이후 stale exact map 재발행 방지

재현 프로그램은 `A -> rev(A) -> exp(...) -> sum(...)`이다. ROW REV 결과는 endpoint residency는
보존하지만 worker별 range ownership을 다시 계산한다. 기존 코드는 REV의 동적
`NATIVE_LINEAGE`를 다음 unary 연산에서 원본 A range의 `DURABLE_MAP`으로 승격할 수 있었다.

- `NativePlacementContinuity`는 exact partition ranges와 endpoint residency를 분리한다.
- 동적 DIRECT predecessor가 있으면 다음 연산도 동적 authority를 보존한다.
- `NeutralPlacementGraphBuilder`는 전체 선택 proof path가 exact일 때만 durable map을 발행한다.
- 동일 endpoint의 FED/LOUT aggregate는 선택된 동적 결과를 DIRECT로 소비할 수 있다.
- `DynamicNativeLayoutCompositionTest`가 production builder와 FedAll 선택을 함께 고정한다.

### 2. ROW REV 입력 FederationMap 변경 제거

`ReorgFEDInstruction`은 입력 map에 `reverseFedMap()`을 직접 적용하지 않는다. 새 output ID를 가진
복사본을 만들고 복사본만 reverse한다. `ReorgFEDInstructionFullTest`는 입력 range/worker 연관이
그대로이고 출력 map 객체가 별도임을 확인한다.

### 3. runtime-supported ROW ROLL 후보 보존

ROW ROLL은 split/non-split range를 runtime에서 처리하지만 compiler continuity에서 제거되고
있었다. 현재 모델은 endpoint residency를 보존하고 shift에 따른 range를 동적 layout으로 표현한다.
`NativePlacementContinuityTest`와 `ReorgFEDInstructionFullTest`가 positive continuity 및 runtime map
구성을 검사한다.

### 4. FULL ROLL과 연속 동적 layout 보존

- FULL ROLL은 runtime/rule contract가 허용하지만 continuity가 ROW만 동적 range 재계산으로
  분류해 후보를 제거하고 있었다. 단일 worker FULL residency를 유지하되 split map의 range는
  dynamic으로 표현한다.
- range를 다시 계산하는 연산은 predecessor의 exact range가 아니라 endpoint residency를
  소비한다. 이에 따라 `REV -> ROLL -> EXP`에서 ROLL과 EXP가 선택된 동적 결과를 DIRECT로
  계속 소비하며 FedAll에 exact legal assignment가 남는다.
- 두 회귀는 수정 전 각각 실패했으며, 수정 후 production-builder/FedAll 및 runtime FULL
  split/non-split map 테스트가 통과했다.
- dynamic FULL은 map-preserving unary/aggregate와 단일 placement-input 경로에만 전달한다.
  단일 endpoint를 단일 partition으로 오인하지 않도록 matrix-matrix 등 exact FULL 전제 연산은
  dynamic FULL을 받지 않는다.
- COL ROLL도 runtime이 row ranges를 split하므로 stale durable geometry를 발행하지 않고 dynamic
  native authority로 유지한다. COL ROLL -> EXP production/FedAll 및 runtime map 회귀를 추가했다.

### 5. 문서 수치 정정

PUBLIC-only skip은 pass로 계산하지 않도록 과거 결과를 다음처럼 정정했다.

| 실행 | Discovered | Executed-pass | Skipped |
|---|---:|---:|---:|
| G003 fresh combined | 128 | 116 | 12 |
| G008 broad sequential | 145 | 132 | 13 |
| final current-source gate | 50 | 48 | 2 |
| dynamic-layout focused gate | 37 | 36 | 1 |
| latest focused + branch gate | 38 | 37 | 1 |
| latest G009 key-class gate | 33 | 30 | 3 |
| final combined stable gate | 74 | 70 | 4 |
| final independent focused review | 43 | 42 | 1 |
| pre-multiproof current-source integration gate | 132 | 128 | 4 |
| final post-multiproof current-source integration gate | 133 | 129 | 4 |
| final repaired current-source integration gate | 134 | 130 | 4 |
| post-index current-source integration gate | 134 | 130 | 4 |

정정 내용과 증거 경계는 `SESSION_ISSUES_2026-09-18.md` 12절 및
`PLAN_SPACE_AUDIT_2026-09-18.md`의 2026-09-19 절에 반영했다.

### 6. transient replay 복수 proof 보존

`exactTransientReplay`는 같은 source realization과 seed에 대해 여러 개의 grounded
`NativeContinuityProof`가 있어도 `proveCandidate()`가 고른 첫 대안만 전달했다. 현재는 정렬·중복
제거된 `proveCandidateAlternatives()` 전체를 `TransientCompatibilityProof`로 변환한다. 동일 worker
geometry의 서로 다른 durable producer identity 두 개가 모두 replay 경계를 통과하고 raw candidate
universe cardinality는 바뀌지 않는 회귀를 추가했다. 후보 cap, suppression, fallback은 추가하지 않았다.

### 7. dynamic transient replay 및 exact geometry 복구

- dynamic ROW REV 결과를 TWrite/TRead로 replay한 뒤 EXP가 DIRECT로 소비하는 경로에서 endpoint
  witness를 유지하도록 수정했다.
- transient replay가 `NativePoolWitness.asAnchor()`의 lossy endpoint/extent 표현을 exact geometry로
  노출해 receipt domain을 축소하던 문제를 수정했다. exact proof는 원래 exact seed geometry를
  유지하고 dynamic proof만 endpoint-only witness를 전달한다. 복구된 factorization은
  **X=2, Y=2, U=4, V=4, D=21**이며 `ProductionDecodedPlanSpaceCompletenessTest`의 물리 Cartesian
  cardinality 1,344가 현재 gate에서 다시 통과한다.
- replay는 candidate suppression이나 runtime fallback 없이 source-state identity와 dynamic/exact
  layout authority를 구분해 fixed point까지 반복한다.

## 검증 결과

### 채택 가능한 증거

- compile/test-compile: 성공.
- `DynamicNativeLayoutCompositionTest`, `NativePlacementContinuityTest`,
  `ReorgFEDInstructionFullTest`, `CandidateAffectingBranchInventoryTest`: latest combined gate 안에서
  41 discovered / 40 executed-pass / 1 PUBLIC-only skip / 0 failures / 0 errors.
- Global/Independent/ProductionDecoded/CandidateReceipt/CandidateIncoming/NativeLineage,
  fixed-point/action/policy isolation 9-class gate: 33 discovered / 30 executed-pass /
  3 PUBLIC-only skips / 0 failures / 0 errors. 이 안에서
  `GlobalReceiptPlanSpaceCompletenessTest`는 10/10 executed-pass다.
- candidate-affecting branch inventory: 5,473 sites / 507 methods. 분류는
  5,207 CONDITIONAL / 266 BOUNDED이며 universally proved site는 0이다. 5개 core 파일로 제한된
  구조 inventory이며 manifest checker는 post-index Maven gate에서 1/1로 통과했다.
- G009 action selection oracle: 27 discovered / 27 executed-pass / 0 skips. 이는 선언된
  2-row/2-anchor/2-demand selection 범위의 bounded 증거다.
- 최종 current-source integration: 23개 class, 134 discovered / 130 executed-pass /
  4 PUBLIC-only skips / 0 failures / 0 errors. Surefire XML 23개를 별도 재집계했고 Maven exit는
  0이다. 실행 전후 source hash와 branch hash가 동일하며 `git diff --check`도 전후 통과했다.
- `git diff --check`: 현재 통과.

주요 evidence는 `build/plan-space-audit-20260919/final-dynamic-layout/`와
`build/plan-space-audit-20260919/g009-transient-multiproof/`,
`build/plan-space-audit-20260919/g009-transient-multiproof-fix/`,
`build/plan-space-audit-20260919/g009-final-integration-post-multiproof/`,
`build/plan-space-audit-20260919/g009-final-integration-current-repaired/`,
`build/plan-space-audit-20260919/g009-final-integration-post-index/`,
`build/plan-space-audit-20260919/g009-branch-proof-post-index/`,
`build/plan-space-audit-20260919/g009-upload-performance/`,
`build/plan-space-audit-20260918/g009-actions/`에 있다.

### 제외한 실행

`build/plan-space-audit-20260919/final-dynamic-layout/`의 첫 combined broad run은 다른 G009
compile/source 변경과 겹쳤다. source hash가 실행 중 바뀌었고 shared `target`에서
`NoClassDefFoundError`, fork/OOM, stale branch manifest가 발생했으므로 acceptance evidence에서
제외한다.

`NeutralPlacementGraphUploadRelocationRedTest#rewrittenInlinedOutputRetainsItsCompilerDeclaredTargetAuthority`
는 여러 번의 격리 실행과 proof-state 최적화 뒤에도 120초 또는 180초 제한을 넘었다. 마지막
post-index exclusive 실행은 **180,770 ms 뒤 exit 124**였고 Surefire XML을 생성하지 못했다.
제품 assertion 실패는 관찰되지 않았지만 assertion 결과 자체도 없으므로 pass가 아니다. 이
timeout 때문에 builder action-generation completeness는 닫히지 않았다.

## 현재 동시 작업 상태

`NativePlacementContinuity`의 proof graph 상태 identity, SCC grounding, canonical reference,
signature 계산과 후속 FULL/chained-dynamic 수정은 현재 안정된 상태다. 이전 선행 snapshot의
SHA-256은 `26d782dc...`였고 현재 post-index 기준은 아래와 같다.
이 기준에서 branch manifest를 다시 생성했고 focused/current-source gate 전후 해시 일치를
확인했다. 최종 74-test gate 뒤 `DynamicNativeLayoutCompositionTest.java`만 정리돼 test source
SHA-256이 `3b5ee1742cbdd4536b3b4337af4aa145d6b1bcc7ceef7e705cfa40a886038e15`로 바뀌었으며,
위 132-test integration gate가 이 현재 버전을 다시 인증했다. production 소스와 branch inventory는
그 시점에는 변하지 않았다. 이후 multiproof 수정으로 `NeutralPlacementGraphBuilder.java` SHA-256은
`03dcb0ae58ea41cba7526408179171ea9d33e8f7f80b24562e2263d6f4fd53b8`가 됐고 branch inventory는
5,438-row로 재생성됐다. 후속 exact/dynamic replay 수정 뒤
`NativePlacementContinuity.java` = `220712b88c1e3907d355868a128e8cbfcfbd4260f7f28f50fad896dad3a4d34b`,
seed index 적용 뒤 `NeutralPlacementGraphBuilder.java` =
`4e74a6ca7a658602b20f2f65913a7934ff28fb2d0ac699b98c8b88adcd195595`이며,
branch inventory는 5,473-row로 다시 생성됐다. post-index 134-test gate가 이 현재 소스를 인증한다. 이후
소스가 다시 바뀌면 이 증거는 새 기준으로 재생성해야 한다.

seed index는 `bindDirectNativeCandidateRealizations` 진입 시 모든 durable anchor를 FType별 immutable
목록으로 한 번 구성해 반복 full-node scan을 제거한다. 각 realization은 기존과 동일한 전체 seed
목록을 받고 마지막 `distinct().sorted()`도 유지하므로 bounded 의미 보존 최적화다. 후보 cap,
sampling, 대표값 선택, fallback 또는 proof filtering은 없다. 다만 위 exclusive timeout이 남아
성능 blocker는 OPEN이다.

## 이어서 수행할 작업

1. timeout 테스트의 proof-state 병목을 더 좁히되 후보 suppression이나 runtime fallback 없이
   필요한 경우 최소 재현으로 성능 원인을 분리한다.
2. G009의 21 obligation과 48 family row를 현재 증거에 매핑해 충족/부분/미충족 상태를 갱신한다.
3. G009 완료 조건을 충족하지 못하면 전역 증명은 OPEN으로 유지하고 정확한 timeout/미증명
   범위를 최종 문서에 기록한다.

## 작업 트리와 커밋 경계

변경은 아직 커밋하지 않았다. 기존 dirty tree를 보존했고 reset/stash, 후보 suppression,
runtime fallback, privacy/TRead-TWrite/recompile 규칙 완화는 사용하지 않았다. 커밋 전에는
production/test/docs/evidence를 분리해 검토 가능한 파일 목록과 최종 검증 hash를 먼저 확정한다.
