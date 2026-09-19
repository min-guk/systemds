# G009 모든 possible plan 보존 감사 진행 현황

기준 시각: 2026-09-19 (Europe/Berlin)
상태: **OPEN — current-source bounded 재인증 완료, 전역 감사와 timeout 해소 필요**

## 1. 목표와 엄격한 완료 기준

G009의 목표는 특정 fixture에서 후보 몇 개가 남는다는 사실이 아니라, 지원되는 프로그램 `P`마다 production pipeline이 만드는 모든 최종 plan이 독립적으로 정의한 합법 물리 plan 집합과 정확히 같음을 증명하는 것이다.

```text
Decode(S(P)) = LegalPhysicalPlans(P)
```

완료하려면 다음 조건을 모두 만족해야 한다.

1. 지원되는 occurrence/value/CFG/function/recompile/privacy 문맥의 유한 universe가 누락 없이 열거되어야 한다.
2. 48개 rule family의 opcode, shape, input/output FType, worker endpoint/range, native/forced-LOUT, privacy, recompile, runtime entry 축에 positive/negative 증거가 있어야 한다.
3. 후보 생성, fixed-point closure, action/anchor 생성, canonicalization, selection, Exact/DP 및 runtime emission의 각 변환이 합법 plan을 삭제하거나 불법 plan을 추가하지 않음을 증명해야 한다.
4. expected와 production을 declaration-side filter 없이 비교하고 `missing`과 `extra`를 모두 진단해야 한다.
5. 순서·mutation·cache·SCC·higher arity·multiple caller/exit·multi-anchor/multi-row·dynamic/exact layout 변형에 민감한 회귀가 있어야 한다.
6. PUBLIC-only fixture는 저장소 정책에 따라 skip하며 pass나 privacy 증거로 계산하지 않아야 한다.
7. 최종 안정 소스 snapshot에서 직렬 Maven gate, source hash 불변성, manifest/checker, `git diff --check`가 모두 통과해야 한다.

현재 bounded equality와 focused regression은 여러 하위 주장을 닫았지만 위 전역 등식을 증명하지 못했다. 따라서 G009 전체는 완료가 아니다.

## 2. 현재 durable 판정

| 범위 | 현재 판정 | 근거 |
|---|---|---|
| G009 전체 | **OPEN** | 21개 proof obligation과 48개 rule-family row의 전역 증명이 닫히지 않음 |
| 독립 full-universe oracle | **bounded pass** | 보호된 compiled-function fixture에서 expected/legal/decoded 각 8 plan 동등성 |
| fixed-point/order/idempotence | **PARTIAL** | 대표 보호 fixture는 안정화·재빌드·독립 statement 순서 불변; 공통 monotone order와 전 변환 보존 정리 부재 |
| action/anchor | **PARTIAL** | 선언된 2-row/2-anchor/2-demand selection universe는 완전; production builder 전수 생성은 미증명 |
| policy non-reentry (PO-15) | **CLOSED for current declared source boundary** | policy quotient 단일 caller/API 경계와 Exact/DP source dependency guard |
| program relations PO-01/07/08/18/20 | **PARTIAL** | bounded protected 관계는 통과; 일반 문맥과 PUBLIC privacy 축은 미완료 |
| rule parity RF01–RF24 | **0 CLOSED / 24 partial-open** | family별 공통 open 축이 남음 |
| rule parity RF25–RF48 | **0 CLOSED / 20 PARTIAL / 4 OPEN** | runtime binding 또는 축별 direct evidence 부족 |
| candidate-affecting branch inventory | **current-source 구조 inventory CLOSED / universal proof OPEN** | 5개 파일 5,473 site / 507 method를 재생성했고 Maven checker 통과; 5,207 CONDITIONAL / 266 BOUNDED / 0 universally proved |
| native/privacy PO-05/06/17/18 후속 | **진행 중 / PARTIAL** | OTHER source-route 반례와 dynamic-layout 결함은 수정됐으나 PART·all-opcode·전체 privacy/recompile cross-product 재인증 미완료 |

[summary.json](../build/plan-space-audit-20260919/g009-branch-proof-post-index/summary.json)은 현재 안정 소스의
**5,473 site / 507 method / 5,207 CONDITIONAL / 266 BOUNDED**를 기록한다.
`NativePlacementContinuity.java` SHA-256은 `220712b8...`, `NeutralPlacementGraphBuilder.java`는
`4e74a6ca...`이며 manifest와 checker gate 전후 전체
production/test source hash가 동일했다. 이 결과는 선언된 파일과 AST kind의 구조 열거를 닫지만,
각 조건의 필요충분성이나 PO-21의 universal compositional proof를 닫지 않는다.

## 3. 완료된 감사 lane과 검증 수치

아래 수치는 각 artifact가 선언한 bounded 범위에만 적용된다.

| Lane | 결과 | Artifact |
|---|---:|---|
| Independent oracle | 16 tests, 16 pass, 0 skip/fail/error; expected/legal/decoded 각 8 plan | [g009-oracle/REPORT.md](../build/plan-space-audit-20260918/g009-oracle/REPORT.md) |
| Fixed-point composition | 53 tests, 53 pass, 0 skip/fail/error | [g009-fixed-point/REPORT.md](../build/plan-space-audit-20260918/g009-fixed-point/REPORT.md), [proof-status.json](../build/plan-space-audit-20260918/g009-fixed-point/proof-status.json) |
| Action/anchor finite oracle | 27 tests, 27 pass, 0 skip/fail/error | [g009-actions/REPORT.md](../build/plan-space-audit-20260918/g009-actions/REPORT.md) |
| Policy boundary | 14 tests, 14 pass, 0 skip/fail/error | [g009-policy-boundary/REPORT.md](../build/plan-space-audit-20260918/g009-policy-boundary/REPORT.md), [evidence.json](../build/plan-space-audit-20260918/g009-policy-boundary/evidence.json) |
| Program relations | 40 discovered, 35 active pass, 5 PUBLIC-only skips, 0 fail/error | [g009-program-relations/REPORT.md](../build/plan-space-audit-20260918/g009-program-relations/REPORT.md), [coverage.json](../build/plan-space-audit-20260918/g009-program-relations/coverage.json) |
| RF01–RF24 | 118 tests, 118 pass, 0 skip/fail/error | [g009-rule-parity-a/REPORT.md](../build/plan-space-audit-20260918/g009-rule-parity-a/REPORT.md), [rule-parity-rf01-rf24.json](../build/plan-space-audit-20260918/g009-rule-parity-a/rule-parity-rf01-rf24.json) |
| RF25–RF48 | 92 tests, 92 pass, 0 skip/fail/error | [g009-rule-parity-b/REPORT.md](../build/plan-space-audit-20260918/g009-rule-parity-b/REPORT.md), [rule-family-parity-rf25-rf48.json](../build/plan-space-audit-20260918/g009-rule-parity-b/rule-family-parity-rf25-rf48.json), [TESTS.md](../build/plan-space-audit-20260918/g009-rule-parity-b/TESTS.md) |
| Final pre-latest current-source gate | 50 discovered, 48 active pass, 2 PUBLIC-only skips, 0 fail/error | [final-current-source-gate/REPORT.md](../build/plan-space-audit-20260918/final-current-source-gate/REPORT.md) |
| Dynamic native focused regression | 37 discovered, 36 active pass, 1 PUBLIC-only skip, 0 fail/error | 현재 [SESSION_ISSUES_2026-09-18.md](SESSION_ISSUES_2026-09-18.md)의 12절과 해당 Surefire reports에 기록 |
| Latest focused + branch current-source gate | 38 discovered, 37 active pass, 1 PUBLIC-only skip, 0 fail/error | [focused-plus-branch-stable.log](../build/plan-space-audit-20260919/final-dynamic-layout/focused-plus-branch-stable.log) |
| Latest G009 key-class current-source gate | 33 discovered, 30 active pass, 3 PUBLIC-only skips, 0 fail/error | [g009-key-classes-stable.log](../build/plan-space-audit-20260919/final-dynamic-layout/g009-key-classes-stable.log) |
| Final combined stable gate | 74 discovered, 70 active pass, 4 PUBLIC-only skips, 0 fail/error | [final-combined-stable.log](../build/plan-space-audit-20260919/final-dynamic-layout/final-combined-stable.log) |
| Pre-multiproof current-source integration gate | 132 discovered, 128 active pass, 4 PUBLIC-only skips, 0 fail/error | [g009-final-integration-current](../build/plan-space-audit-20260919/g009-final-integration-current/) |
| Final post-multiproof current-source integration gate | 133 discovered, 129 active pass, 4 PUBLIC-only skips, 0 fail/error | [g009-final-integration-post-multiproof](../build/plan-space-audit-20260919/g009-final-integration-post-multiproof/) |
| Final repaired current-source integration gate | 134 discovered, 130 active pass, 4 PUBLIC-only skips, 0 fail/error | [g009-final-integration-current-repaired](../build/plan-space-audit-20260919/g009-final-integration-current-repaired/) |
| Post-index current-source integration gate | 134 discovered, 130 active pass, 4 PUBLIC-only skips, 0 fail/error | [REPORT.md](../build/plan-space-audit-20260919/g009-final-integration-post-index/REPORT.md), [evidence.json](../build/plan-space-audit-20260919/g009-final-integration-post-index/evidence.json) |

최신 current-source integration gate는 실행 전후 전체 production/test source hash와 branch
hash가 같았다. 134 discovered / 130 active pass / 4 PUBLIC-only skips / 0 fail/error는 실행한
23개 class의 bounded 증거이며 G009의 전체 21 obligation이나 48 family row 완료 수치가 아니다.
선행 74-test gate 뒤 test-only 변경과 transient multiproof production 수정이 있었다. 이후
durable-anchor seed index 변경까지 포함해 5,473-row branch inventory를 재생성했으며, post-index
gate에서 source와 manifest hash가 모두 유지됐다.

## 4. 확인되어 수정된 실제 결함

다음 항목은 추정이 아니라 fixture 또는 runtime/source parity가 재현한 결함이다.

1. **Aligned binary plan 삭제**: aligned ROW/ROW 또는 COL/COL binary가 합법인데 `NativePlacementContinuity`가 resident input을 정확히 하나만 요구해 plan을 삭제했다. 조건을 “적어도 하나의 resident input”과 “모든 PRESENT input의 동일 witness type”으로 고쳤고 positive aligned 및 negative mixed-pool/type/ungrounded 회귀로 잠갔다. 근거는 [oracle-verification-v2/REPORT.md](../build/plan-space-audit-20260918/oracle-verification-v2/REPORT.md)와 [oracle-verification-v3/REPORT.md](../build/plan-space-audit-20260918/oracle-verification-v3/REPORT.md)다.
2. **Dynamic predecessor의 stale durable geometry 승격**: `A -> rev(A) -> exp(R)`에서 `rev`가 동적 native map을 만들었는데 후속 `exp`가 원본 range의 `DURABLE_MAP`으로 재승격될 수 있었다. endpoint residency와 exact range 증명을 분리하고 동적 DIRECT predecessor가 stale durable map을 발행하지 못하게 했다.
3. **ROW REV 입력 map mutation**: runtime `ReorgFEDInstruction`이 입력 `FederationMap`에 `reverseFedMap()`을 직접 적용했다. 새 output ID map 복사본을 만든 뒤 복사본만 변경하도록 수정했다.
4. **ROW ROLL 후보 누락**: runtime은 split/non-split range를 처리하지만 compiler continuity 후보가 제거됐다. 동일 endpoint residency를 보존하고 runtime이 range를 재계산하는 dynamic native layout으로 모델링했다.
5. **RF23/RF24 local operand 의미 오류**: explicit `null` FType slot을 missing으로 오인하거나 legal local/federated covariance 경로를 거절했다. `CentralMomentRule`과 `CovarianceRule`을 runtime contract에 맞췄다. 근거는 [g009-rule-parity-a/REPORT.md](../build/plan-space-audit-20260918/g009-rule-parity-a/REPORT.md)다.
6. **OTHER literal source route 삭제**: PART/OTHER를 durable/native seed에서 제외하는 과정에서 B-13의 literal OTHER source가 executable realization을 잃어 legal downstream OTHER matrix-scalar route까지 사라졌다. 별도 `SOURCE_LINEAGE` 물리 유형으로 source-rooted route를 보존하면서 durable anchor/native worker-pool seed와 relocation 권한은 발행하지 않도록 수정했다. FedAll receipt 단언을 포함한 최신 B13 회귀는 133-test current-source integration gate에서 통과했다.
7. **FULL/COL ROLL과 동적 합성 누락**: FULL·COL ROLL의 runtime range split을 dynamic native authority로 표현하고 `REV -> ROLL -> EXP`, `FULL ROLL -> EXP`, `COL ROLL -> EXP` production/FedAll 회귀를 추가했다. dynamic FULL은 map-preserving 소비에만 전달하고 exact single-partition FULL 전제 연산에는 전달하지 않는다.
8. **Transient replay 복수 proof 손실**: 같은 source realization/seed에 여러 grounded `NativeContinuityProof`가 있어도 replay가 첫 대안만 보존했다. `proveCandidateAlternatives()` 전체를 정렬·중복 제거해 전달하도록 수정했고, 동일 worker geometry의 서로 다른 durable producer identity 두 개가 모두 남는 회귀를 추가했다.
9. **Dynamic native authority의 transient replay 손실**: dynamic ROW REV 결과가 TWrite/TRead를 통과하면 endpoint witness가 사라져 downstream EXP의 exact assignment가 없어질 수 있었다. dynamic replay seed와 typed endpoint witness를 보존하는 production 회귀로 잠갔다.
10. **Exact replay receipt-domain 축소**: transient replay가 `NativePoolWitness.asAnchor()`의 lossy worker-pool 표현을 exact geometry로 노출했다. 이 표현은 endpoint를 canonicalize하면서 경로를 잃고 orthogonal extent를 1로 재구성해, 합법 receipt 조합을 제거하고 ProductionDecoded cardinality를 1,344에서 525로 줄였다. exact proof는 원래 exact seed geometry를 유지하고 dynamic proof만 endpoint-only witness를 전달하도록 경계를 수정했다. 복구된 factorization은 **X=2, Y=2, U=4, V=4, D=21**이며 곱은 1,344다. 근거는 [g009-decoded-525-fix/REPORT.md](../build/plan-space-audit-20260919/g009-decoded-525-fix/REPORT.md), [g009-dynamic-transient-exactness/REPORT.md](../build/plan-space-audit-20260919/g009-dynamic-transient-exactness/REPORT.md), [post-index gate](../build/plan-space-audit-20260919/g009-final-integration-post-index/REPORT.md)다.

마지막 항목의 현재 판정은 “반례 원인과 수정 확인”이며 PO-05 전체 CLOSED가 아니다. OTHER bounded subcase와 PART 일반성은 구분해야 한다.

## 5. PUBLIC privacy skip 처리

PUBLIC-only 테스트는 저장소 정책상 의도적으로 실행 제외한다. 다음 수치는 pass에 포함하지 않는다.

- Program-relations lane: 5 PUBLIC-only skips.
- Final current-source gate: 2 PUBLIC-only skips.
- Dynamic native focused regression: 1 PUBLIC-only skip.
- 과거 G003 fresh 결합 실행: 12 PUBLIC-only skips.

따라서 PUBLIC compile/recompile 행은 PO-18을 닫는 positive evidence가 아니다. 현재 활성 protected compile/recompile 비교는 bounded subclaim을 지지하지만, privacy × compile/recompile 전체 cross-product는 PARTIAL이다. 정책과 개별 skip 이유는 [privacy-policy/README.md](../build/plan-space-audit-20260918/privacy-policy/README.md)와 [final-current-source-gate/skips.tsv](../build/plan-space-audit-20260918/final-current-source-gate/skips.tsv)에 있다.

## 6. acceptance에서 제외한 Maven 실행

공유 working tree와 `target/`에서 겹쳐 실행된 결과는 성공처럼 보이더라도 acceptance evidence로 사용하지 않는다.

- 다른 G009 compile/source edit와 겹쳐 `ClassNotFoundException`, `NoClassDefFoundError`, class replacement, stale manifest, fork/OOM 또는 source-hash 변화가 발생한 실행.
- 잘못된 package FQCN을 사용한 최초 `PolicyQuotientIsolationTest` invocation. 올바른 FQCN 재실행만 반영했다.
- [final-dynamic-layout/sequential](../build/plan-space-audit-20260919/final-dynamic-layout/sequential/) 아래에서 oracle 실행과 겹쳐 kill된 loop. `summary.tsv`가 존재해도 완료 gate가 아니다.
- source edit 전후가 동일하지 않은 첫 broad dynamic-layout 결합 실행. [final-dynamic-layout](../build/plan-space-audit-20260919/final-dynamic-layout/)의 해당 로그는 진단 자료일 뿐이다.
- action lane의 최초 compile race와 이후 shared-target fork race. 최종 27-test `surefire:test` gate만 action bounded evidence로 쓴다.

이 제외 규칙은 실패를 숨기기 위한 것이 아니다. 동일 source snapshot과 직렬 실행이라는 선행 조건을 만족하지 못해 결과의 귀속이 불가능하기 때문이다.

## 7. NativePlacementContinuity 성능 조사: v11/v12

### 확인된 증거

- production-builder diagnostic는 반복해서 제한 시간 안에 완료되지 않았고, thread dump의 hot path는 `NativePlacementContinuity.candidateProofAlternatives` 및 이를 호출하는 direct-native binding/closure 쪽을 가리킨다. 초기 진단은 [g009-actions/REPORT.md](../build/plan-space-audit-20260918/g009-actions/REPORT.md)에 기록돼 있다.
- v11은 [maven-upload-method-fixed-v11.log](../build/plan-space-audit-20260918/g009-actions/maven-upload-method-fixed-v11.log), [maven-upload-method-fixed-v11.exit](../build/plan-space-audit-20260918/g009-actions/maven-upload-method-fixed-v11.exit), [v11-thread-dump.txt](../build/plan-space-audit-20260918/g009-actions/v11-thread-dump.txt)를 남겼다. exit `143`은 정상 통과가 아니라 중단이며, dump에서 test worker가 `candidateProofAlternatives`에 머문다.
- v12는 다른 Maven과 겹치지 않은 exclusive 실행이었고 120초 제한에서 exit `124`로 종료됐다. assertion failure는 기록되지 않았지만 test completion도 없으므로 pass가 아니다. 채취 표본은 약 **2,827 states / 2,826 dependencies / 1,772 alternatives**를 보였고, query마다 reverse-dependency index를 반복 구축하는 경로가 남아 있었다. 근거는 [maven-upload-method-fixed-v12.log](../build/plan-space-audit-20260918/g009-actions/maven-upload-method-fixed-v12.log), [maven-upload-method-fixed-v12.exit](../build/plan-space-audit-20260918/g009-actions/maven-upload-method-fixed-v12.exit), [v12-thread-dump.txt](../build/plan-space-audit-20260918/g009-actions/v12-thread-dump.txt)다. 따라서 global production-builder 경로는 여전히 blocked다.
- v12 뒤 bounded action/native gate는 **39 discovered / 38 active pass / 0 failures / 0 errors / 1 policy-required PUBLIC skip**, `BUILD SUCCESS`로 끝났다. 이 결과는 bounded action 및 native 회귀를 보존했다는 증거이며 global builder 완료 증거는 아니다. 근거는 [maven-final-bounded.log](../build/plan-space-audit-20260918/g009-actions/maven-final-bounded.log)다.
- 2026-09-19 00:45의 추가 45초 진단에서도 완료되지 않았다. 7.5초 표본은
  `bindDirectNativeCandidateRealizations`가 각 native candidate에 대해 전체 `nodes`에서 seed anchor를
  다시 수집하는 `NeutralPlacementGraphBuilder.java:2676`에 머물렀다. 같은 실행의 histogram은
  그 시점에 candidate facts 약 6.3k, realizations 약 6.5k, graph nodes 약 2.3k를 보였다. 이는
  proof graph 이전의 반복 seed scan도 비용에 기여한다는 진단 증거다. 이 실행은 진단 전용이며
  pass로 계산하지 않는다. artifact는
  [timeout-diagnostic-0045](../build/plan-space-audit-20260919/final-dynamic-layout/timeout-diagnostic-0045/)에 있다.
- 후속 bounded 최적화는 `bindDirectNativeCandidateRealizations` 진입 시 모든 durable anchor를
  FType별 immutable index로 한 번 구성하고, 각 native realization이 기존과 같은 전체 indexed
  seed 목록을 사용하도록 바꿨다. immediate-source loop와 마지막
  `distinct().sorted()` canonicalization은 유지했으며 cap, sampling, 대표값 선택, fallback,
  proof filtering을 추가하지 않았다. 이 변경은 후보 의미를 보존하는 반복 scan 제거다.
  그러나 대상 method는 exclusive 180초 실행에서도 **180,770 ms 뒤 exit 124**였고 Surefire XML을
  생성하지 못했다. 따라서 assertion 결과와 완료 여부는 알 수 없으며 pass로 계산하지 않는다.
  근거는 [g009-upload-performance/REPORT.md](../build/plan-space-audit-20260919/g009-upload-performance/REPORT.md)다.

### 추론

v12의 exclusive timeout과 표본은 proof-state 탐색량 및 per-query reverse-dependency indexing이
병목이라는 강한 진단 증거다. 전체-node seed 재수집 비용은 의미 보존 FType index로 제거했지만
target method timeout은 남았다. 다음 조사 대상은 root realization, seed witness, fixed identities,
layout exactness를 모두 포함한 정확한 query key 아래에서 proof graph/reverse-dependency 계산을
공유하는 것이다. 적용 전후 proof-set equality를 확인해야 하며 후보 집합 축소나 runtime fallback은
허용하지 않는다.

## 8. 남은 OPEN/PARTIAL 의무

- **PO-01**: occurrence/value/control/function/recompile의 모든 지원 문맥을 독립 생성하지 못했다.
- **PO-02/PO-09/PO-21**: bounded fixed point, replay idempotence, 독립 statement order는 통과했지만 모든 inner transform의 공통 monotone order와 모든 유효 schedule의 보존 정리는 없다.
- **PO-05**: OTHER source-rooted route 반례는 수정했지만 PART production fixture와 전체 relocation/non-relocation 대안 보존이 미완료다.
- **PO-06**: endpoint-only dynamic witness와 exact-range witness의 대표 REV/ROLL/reshape 경계는 검사하지만 지원되는 모든 native seed/layout 조합을 열거하지 않았다.
- **PO-07**: protected branch correlation은 있으나 all-reader/all-writer loop 전수 증거의 PUBLIC 행은 skip되어 계산할 수 없다.
- **PO-08**: 두 callsite와 bounded function projection은 있으나 multiple semantic exits, 임의 caller/output은 미완료다.
- **PO-10~PO-13**: synthetic action selection universe는 닫혔지만 builder action 생성, 모든 authority/action type, cache/search leaf, multi-anchor/multi-row runtime correspondence는 미증명이다.
- **PO-15**: 현재 source dependency/API 경계에는 CLOSED이지만 Java module/reflection/외부 fork까지 보장하는 주장은 하지 않는다.
- **PO-17**: 보호된 native-lineage fixture와 runtime tests가 있어도 all supported opcode/layout의 generation-independent oracle은 없다. PUBLIC-only higher-arity native fixture는 pass로 셀 수 없다.
- **PO-18**: protected compile/recompile bounded 비교는 있으나 privacy 전체 cross-product는 PUBLIC 제외 때문에 PARTIAL이다.
- **PO-20**: 하나의 3-input AND와 2-way OR decoder는 일반 higher arity 정리가 아니다.
- **RF01–RF48**: family별 worker count, range/layout, privacy, recompile, compiler-to-runtime binding 축이 모두 채워진 row가 없다.
- **Witness multiplicity와 unfiltered universe**: 물리적으로 같은 plan의 서로 다른 proof witness 수와 declaration 밖 receipt의 일반 합법성이 미판정이다.
- **Runtime qualification**: Docker/new JAR/stage identity 및 분산 ML10/P1/P2/SliceLine 실행은 별도 pending이며 보존 정리 증거에 포함하지 않는다.

## 9. 직렬 다음 단계와 중단 조건

1. seed index 뒤에도 남은 per-query proof graph/reverse-dependency 반복과 proof-state 폭증을 plan suppression 없이 줄이고, global production-builder method가 제한 시간 안에 완료되는지 exclusive 실행으로 확인한다. 같은 `target/`에 Maven을 병렬 실행하지 않는다.
2. 성능 변경이 생기면 소스를 다시 동결하고 production/test source hash manifest를 갱신한다. 현재
   현재 5,473-row branch snapshot은 `NativePlacementContinuity`의 `220712b8...`와
   `NeutralPlacementGraphBuilder`의 `4e74a6ca...` 소스 조합에만 유효하다.
3. native/privacy owned tests의 최신 current-source 통합 실행은 완료했다. 향후 production 변경이
   생기면 B13 OTHER source lineage, dynamic native layout, native lineage completeness, native
   continuity, transform/encode, runtime reorg를 다시 실행한다.
4. PUBLIC skip을 별도 집계하고 active pass 수와 분리한다.
5. PO-05/06/17/18 evidence ledger를 작성해 각 subclaim의 CLOSED/PARTIAL/OPEN과 `missing`/`extra` 진단을 고정한다.
6. 성능 변경으로 AST가 바뀌면 candidate-affecting branch manifest를 재생성·분류하고 direct 및
   Maven checker를 다시 통과시킨다.
7. oracle, fixed-point, action, policy, relations, rule parity 및 native/privacy gate를 직렬 current-source suite로 재실행한다.
8. source-before/source-after hash 동일성, Surefire XML, skip reason, command log, exit code, `git diff --check`를 하나의 final receipt에 묶는다.
9. 남은 obligation 각각에 independent expected universe와 positive/negative/mutation/runtime 증거가 없으면 CLOSED로 올리지 않는다.

작업 중단 조건은 두 가지뿐이다. 전역 완료를 선언하려면 21개 obligation과 48개 family row가 엄격한 축별 기준을 모두 충족하고 안정 소스 직렬 gate가 통과해야 한다. 그렇지 않으면 문서 상태를 **OPEN / additional audit required**로 유지하고, 정확한 bounded blocker를 남긴다.

## 10. 독립 검토 판정

- Architecture: **APPROVE_FOR_BOUNDED_SCOPE**. exact seed와 dynamic endpoint witness 경계, SCC
  grounding 및 현재 regression 범위에 대한 승인이다. 전역 정리나 timeout 경로 승인이 아니다.
- Code review: **REQUEST CHANGES**. upload-relocation fixture timeout, 5개 core 파일에 한정된 branch
  inventory, recursive Tarjan 깊이 위험이 남아 있다. 근거는
  [CODE_REVIEW.md](../build/plan-space-audit-20260919/g009-final-review/CODE_REVIEW.md)다.

따라서 최종 APPROVE나 G009 완료를 선언하지 않는다.

## 11. working tree와 commit 주의

현재 working tree에는 여러 agent의 production, test, docs 및 untracked evidence 변경이 함께 존재하며 의도적으로 미커밋 상태다. 주요 baseline 커밋은 `d8fbd30b54`이지만 이 문서의 G009 결과 상당수는 그 이후 변경을 대상으로 한다. reset/stash/revert로 다른 lane의 변경을 제거하지 않았고, 이 문서도 커밋 완료나 clean tree를 주장하지 않는다.

이 파일은 현재 진행 상태를 설명하는 보고서이며 source-of-truth test receipt를 대체하지 않는다. 최종 판정은 안정된 source hash에 결속된 직렬 실행 artifact를 기준으로 갱신해야 한다.
