# FedPlanner v9a 리팩터링 설계 적합성 검토 보고서

작성일: 2026-10-13. 개정: 초기 구현부터 v9a까지의 누적 결정·실패·수정 이력을 반영. 대상: `experiments/memo-migration-20261013/docker-engine-v9a/snapshot.json`이 가리키는 동결 소스와 바이너리.

**누적 이력을 반영한 판정: v9a는 핵심 Memo 구조와 작은 Docker LogReg 검증까지 도달한 단계적 구현이며, 전체 migration 완료본은 아니다.** Immutable RuleMemo, exact 탐색, 명시적 supply, DIRECT-only taken backedge는 설계 방향을 따른다. ZERO/ONE 지원, 동일 epoch의 kernel 보존, private shape/cost snapshot, 후단 fusion 제한은 구현 중 문서에 남긴 결정·수정이다. 반면 metadata 합법 계획 누락과 이전 Memo 선택 권위의 잔존은 v9a에서 재현된다. Caller/formal guard의 비용 차이도 재현되지만, 당시 명시한 추정 모델과 후속 상관 의미 보강을 구분해 평가해야 한다.

**Docker LogReg 성공은 유효한 부분 완료 증거다.** 그것만으로 GLM, 다중 출력 연산, 전체 재컴파일·수명 계약, legacy 삭제 및 성능 개선까지 완료되었다고 해석하면 안 된다. 최초 보고서의 소스 대조에 더해, §1.4–§1.6은 어느 단계에서 무엇이 바뀌었는지와 그 기록 때문에 조정한 판정을 제시한다. 구현 코드는 수정하지 않았다.

## 1. 무엇을 기준으로 비교했는가

### 1.1 구현 기준을 현재 작업 트리와 분리

- 요청의 `snapshot.jsondptj`는 `snapshot.json에서`라는 의미로 해석했다. 실제 파일은 [v9a snapshot.json](../experiments/memo-migration-20261013/docker-engine-v9a/snapshot.json)이다.
- 감사 소스는 `docker-engine-v9a/main-sources/org/apache/sysds/`, 테스트는 같은 스냅샷의 `test-sources/`이다. 현재 `src/`에는 후속 수정이 있으므로 v9a 구현으로 취급하지 않았다.
- 기반 HEAD는 `4314a345c16c20752421ce6251bbc2f366c85825`이며, v9a는 v8에 명시된 delta를 추가한 동결 체크포인트다. manifest 목적도 LogReg late fusion 및 emission atomicity 진단이며 진행 중인 GLM/builtin 변경을 제외한다고 명시한다.
- Java 파일은 main 1,683개, test 2,186개다. 감사 시작 시 현재 작업 트리와 내용이 다른 대응 파일은 각각 15개와 8개였다. 이 숫자는 **2026-10-13 20:24:12 UTC에 고정한 비교값**이다.
- v8 대비 실제 내용 변경은 main 7개, test 3개다. `mainDelta`에는 8개가 열거되지만 `PlannedSupplyRuntime.java`는 v8과 byte 단위로 동일하다. 목록은 overlay 대상으로 읽어야 하며, 8개 모두의 내용이 바뀌었다는 증거는 아니다.
- v9a의 전체 main-source 파일 1,692개와 test-source 파일 2,188개는 Docker LogReg 실행이 보존한 source 사본과 모두 일치한다. Java 외 resource를 포함해 위 Java 개수와 다르다. Docker preflight 대상 class 7개도 현재 동결 바이너리의 SHA-256과 일치한다.

근거: [파일별 SHA-256과 차이 목록](evidence/memo-v9a-design-audit-2026-10-13/manifest.json), [Docker 소스 전체 대조](evidence/memo-v9a-design-audit-2026-10-13/docker-source-match.json), [Docker manifest](evidence/memo-v9a-design-audit-2026-10-13/docker-manifest.json).

### 1.2 설계 문서의 우선순위

설계 기준은 [통합 구현 명세의 감사 시작 시 사본](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md)이다. Simple Memo, Earliest Safe Pruning, Oracle Supply, Control Flow DP 문서는 보조 근거로 읽었다. 작업 중 문서가 갱신될 수 있어 이 보고서의 설계 행 번호는 `design-baseline/` 사본을 기준으로 한다.

| 결정 | 이번 감사에서 적용한 해석 |
|---|---|
| ADR20 | Loop의 **실제로 실행되는 backedge만 DIRECT-only**. Entry·exit·일반 body edge의 합법 이동은 허용한다. |
| ADR21 | 정확한 binding으로 의존 범위를 찾고, 실제 비분리 조건을 필요한 위치에서 검사한다. |
| ADR22 | 사용자 승인으로 T>1 제한을 **ZERO/ONE/MANY 지원**으로 대체했다. 이전 문서의 T>1 문장과 달라도 결함이 아니다. |
| ADR23 | Positive residency는 양의 증명만 제공하며, 비어 있지 않은 fine-grained privacy는 명시적으로 지원 범위 밖이다. |
| ADR24 | 변경 domain에 연결된 검사·비용만 갱신하고 초기 propagation 뒤 고정 scope를 축약한다. |
| ADR25 | 비용과 무관한 source 조합을 **비용 primitive 생성 전에** 제거한다. 후속 결정이며 v9a에는 아직 반영되지 않았다. |
| ADR26 | 독립적으로 선택한 kernel을 후단 fusion이 삭제하지 않도록 한다. v9a delta에 반영됐다. |

`구현 진척 문서`의 v10 결과나 이후 lifecycle/builtin 수정은 후속 이력이다. 이를 v9a의 통과 증거로 옮기지 않았다. 정확한 추가 시각이 없는 ADR은 날짜만으로 v9a 이전 결정이라고 단정하지 않는다.

### 1.3 판정 용어

- **확인된 결함**: 동결 구현에서 작은 반례로 설계와 다른 결과를 재현했다.
- **설계보다 제한된 구현**: 동작 방식이 명세보다 좁다. 즉시 오답이라는 뜻은 아니다.
- **미완료 범위**: 설계가 요구하지만 해당 체크포인트에서 아직 구현·검증되지 않았다.
- **검증 공백**: 필요한 보장보다 검증 수단이 약하다. 구체적인 오동작과 구분한다.

각 발견에는 사실·해석·미확인 범위를 구분했다. 심각도는 감사 우선순위이며 발생 빈도를 측정한 값이 아니다. 누적 기록의 **사용자 승인 정책**, **의도한 구현 결정**, **실행 증거**, **진행 중이라는 자기보고**도 구분한다. 문서에 변경 이유가 있다는 사실만으로 모든 검증 gate가 통과한 것은 아니지만, 그 이유를 읽지 않고 최초 설계와의 차이를 모두 결함으로 분류해서도 안 된다.

### 1.4 초기 구현부터 v9a까지 누적으로 바뀐 내용

실제로 보존된 이름은 번호 없는 초기 구현 기록, `docker-engine-01`의 diagnostic01/02, `integration-v3`부터 `v8`, 그리고 `v9a`다. **독립적인 v1·v2·v9 완료본/manifest는 확인되지 않았다.** 따라서 diagnostic01을 v1, diagnostic02를 v2로 임의 대응시키지 않는다. `native-vN-classes` 같은 개별 개발 lane의 번호도 중앙 integration 버전과 동일하지 않다.

아래 변경 내용은 누적 문서가 각 checkpoint 전후에 기록한 내용이다. 모든 중간 버전의 완전한 source snapshot이 있는 것은 아니므로 ‘이 줄이 정확히 vN에서 처음 추가됐다’는 전수 diff 결과와는 구분한다. 테스트 결과는 이번에 재실행한 값이 아니라 **해당 이름의 과거 원본 로그를 다시 읽은 결과**다.

| 단계 | 누적으로 구현·수정한 내용 | 그 단계의 검증과 남은 문제 |
|---|---|---|
| 초기 기반 구현 — 번호 미기재 | Structured Facts, guard-correlated resolver, immutable rules, exact solver, canonical upload/REP collect, loop 계약, `COMPILE_MEMO` integration 착수. 아직 same-SB sharing과 더 넓은 retention을 구분했다. | supply/loop 87개, 별도 loop 5개, model 12개는 component 증거. 실제 compiler·ML·cross-scope gate는 미완료. [누적 진척 기록:37](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L37) |
| engine-01 / diagnostic01 | 초기 부분 구현을 동결해 Docker 진단 시작. | `/grid` stage의 Docker bind 실패로 DML 실행 전 중단. planner 실패로 해석할 수 없다. snapshot 자체가 `diagnostic partial implementation; not acceptance`라고 선언한다. [누적 이슈 기록:727](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L727) |
| 같은 초기 engine / diagnostic02 | stage root를 `/home/mchoi` 아래로 바꾸고 실제 reference/Memo 실행까지 진행. occurrence별 shape와 loop phase의 필요성이 드러남. | CP LogReg/GLM은 nonzero model 생성. Memo는 label `Y` 및 loop-carried `w`의 `UNKNOWN_SIZE`로 emission 전 실패. 아직 수치 parity 없음. [누적 이슈 기록:731](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L731) |
| v3 | FIRST/MIDDLE/LAST, occurrence shape, 함수 export/completion, exact origin dispatch, shared guard-union 비용을 통합하던 단계. 이어서 ZERO/ONE 지원으로 scope를 확장. | main/test compile **43.335초 PASS**. unit **169개 중 4 FAIL**, W2 **5/5 PASS**. 문서는 3개 shape-grounding 실패와 stale loop-registry reset assertion을 기록한다. compile 성공을 통합 성공으로 보면 안 된다. [누적 진척 기록:54](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L54), [누적 이슈 기록:773](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L773) |
| v4 | 승인된 ZERO/ONE/MANY, 실제 final value 및 taken-backedge 구분을 통합. 이후 consumer privacy를 producer-choice 조합마다 재평가하지 않도록 ownership 분리 진행. | compile **46.447초**, unit **179/179**, W2 **5/5 PASS**. 이후 privacy factoring은 이 gate 뒤의 변경이므로 다음 checkpoint 검증이 필요했다. GLM·Memo ML 완주 미완료. [누적 진척 기록:58](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L58), [누적 이슈 기록:780](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L780) |
| v5 | core·runtime·재사용 semantics를 확장 검증. 이후 generic lazy loop-privacy summary가 다른 assumption으로 자기 귀납을 정당화할 위험을 발견해 **그 접근을 철회**하고 positive-only residency와 정확한 invocation context로 전환. | compile **40.030초**, unit **187/187**, W2 **5/5**, reused semantics **99/99 PASS**. v5 통과는 그 뒤 수정된 privacy 경로의 자동 승인 증거가 아니다. [누적 진척 기록:68](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L68), [누적 이슈 기록:797](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L797) |
| v6 및 v7 전 통합 | W2 T=0/1/2/3와 entry upload 횟수, call-frame privacy, 원본 비용 재감사, one upload/one reuse/cleanup 연결 검증. Live HOP memory 값이 비용을 바꾸던 문제를 private snapshot clone으로 수정. | compile **41.818초**, core **217/217 PASS**. W2/privacy 합동 로그는 **25개 중 scanner 1 FAIL**; scanner 별도 수정 **18/18 PASS**, W2 **7/7**은 진척 문서 기록. LogReg 10m-node도 RESOURCE_EXHAUSTED, GLM은 source-product 비용 생성으로 정체. [누적 진척 기록:77](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L77), [누적 이슈 기록:812](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L812) |
| v7 및 v8 전 보완 | planner-neutral privacy 테스트 14개 추출, parsed DML exhaustive fixture, 초기 propagation 후 scope projection·incident bounds, co-owner connectivity를 활용한 search 순서 개선. 독립 검토가 emission preflight/return marker 관련 4개 수정을 요구. | compile **40.922초**, core **222/222**, runtime/privacy **28/28**, reused semantics **256/256**, harness **39/39 PASS**. Host LogReg OPTIMAL 130,061 nodes는 별도 모델 진단이며 Docker 성공이 아니다. [누적 진척 기록:89](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L89) |
| v8 | 중앙 component/runtime gate까지 통합. Docker에서 exact 선택 이후의 실제 lowering 경계를 검사. | compile **37.779초**, core **245/245**, W2/shared runtime **10/10 PASS**. 그러나 Docker LogReg은 solver OPTIMAL 뒤 aggregate fusion이 선택된 multiply를 제거해 `lop=null`로 실패. **탐색 timeout 문제가 아니라 emission 결함**이다. [누적 진척 기록:100](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L100), [누적 이슈 기록:834](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L834) |
| v9a — 독립 v9 기록 없음 | v8에 selected-kernel fusion 보존, lowering 사전검사, return marker/중복 caller output cleanup 보완을 overlay. GLM/builtin WIP는 의도적으로 제외. | delta **53/53 PASS**. Docker LogReg 최초 확인 성공: W3, model 8×2의 16 entries CP 일치. 전체 migration 완료 선언은 없고 GLM·multi-output·guard-cost audit·최종 삭제 gate를 남겼다. [누적 진척 기록:98](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L98) |

과거 로그는 [checkpoint 로그 인덱스](evidence/memo-v9a-design-audit-2026-10-13/history-review/checkpoint-log-index.json)와 그 안의 `logs/` 사본으로 보존했다. v8 snapshot의 `tests pending`은 동결 당시 metadata이며, 뒤의 `integration-v8-*` 로그가 이후 통과 증거를 추가한다. 반대로 크기가 0인 `v9a-delta-compile.log`만으로 compile 성공을 입증하지는 않는다. 버전마다 suite 구성도 달라져 테스트 개수를 더하거나 증가율을 품질 지표로 삼지 않는다.

### 1.5 구현 중 명시적으로 채택하거나 철회한 결정

| 항목 | 누적 문서가 남긴 이유와 변화 | v9a 판정에 반영한 의미 |
|---|---|---|
| Live data anchor → canonical W descriptor | 통합 설계의 명시 결정이며 과거 로컬 지침 충돌을 별도 반박 문서로 정리. PUBLIC semantic fixtures 유지도 함께 명시. [정책 변경 기록:3](evidence/memo-v9a-design-audit-2026-10-13/history-review/PRINCIPLE_REBUTTAL_MEMO_2026-10-13.md#L3) | Anchor-free upload나 PUBLIC fixture 존재를 과거 AGENTS 규칙 위반으로 분류하지 않는다. Privacy/native carrier/no-fallback 조건은 그대로다. |
| T>1 → ZERO/ONE/MANY | 사용자가 0~1회도 정확히 지원하도록 선택. 단순히 rejection guard를 지우지 않고 correlated cardinality, entry/exit/cost를 변경. [누적 이슈 기록:773](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L773) | 승인된 지원 확대다. 이전 T>1 문장만 기준으로 결함을 만들지 않는다. |
| Same-epoch recompile에서 kernel DAG 보존 | 선택 뒤 scalar/algebraic rewrite가 DataGen/TRead를 없애거나 variant를 바꾸던 W2 실패를 해결. 통계/memory refresh와 exact clone은 유지하고 새 physical 선택만 새 planning epoch로 분리. [누적 이슈 기록:691](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L691) | 기존 §3.4의 포괄적인 ‘recompile 계약 위반’ 판정을 좁힌다. 동일 epoch replay는 의도된 결정이며, 새 모델 재최적화 범위는 별도 gate다. |
| Guard-union 및 반복 평가 비용 profile | 서로 다른 `(guardId, callFrame)`는 독립, 같은 decision의 반복 평가는 correlated로 **명시 추정**. retained transfer `p`와 native `p*T`를 구분. [누적 이슈 기록:765](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L765) | 독립 반복 확률 `1-(1-p)^T`와 다르다는 이유만으로 오류라고 하지 않는다. Caller/formal 동일 값 통합은 이 profile의 의미 정밀도와 후속 보강 문제로 구분한다. |
| Generic lazy loop summary 철회 | 다른 assumption map으로 귀납에 재진입하는 위험을 검토에서 발견. Positive residency 및 loop/call-qualified guard로 대체. [누적 이슈 기록:797](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L797) | v5 당시 통과 수만으로 철회된 접근까지 정당화하지 않는다. v9a에 남은 최종 방식과 구분한다. |
| Private cost/shape HOP clone | Lowering 전후 같은 선택의 movement 비용이 **1.00048828125 → 32769**로 바뀌는 문제를 관측. Live HOP 대신 보존 snapshot에서 clone하도록 수정. [누적 이슈 기록:812](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L812) | Clone 존재를 단순 ‘설계 단순화 미완료’로 분류한 문구를 수정한다. 비용 불변성을 위한 의도된 repair다. |
| Lowering preflight와 함수 return 정리 | v7 검토가 mutation 전 preflight, callee export 검사, nonsingleton accessor 거절, 현재 DAG 한정 marker rewire를 요구; v9a에서 보완 및 delta 검증 기록. [누적 진척 기록:93](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L93), [누적 진척 기록:102](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L102) | ‘atomicity 작업이 없었다’고 읽히지 않도록 이미 해결·검증한 사례와 추가 실패 주입 미검증 범위를 분리한다. |
| 후단 implicit fusion 억제 | v8의 missing selected multiply를 덮기 위해 checker를 완화하지 않고 committed Memo kernel을 보존. 일반/legacy fusion은 유지하도록 설계. [누적 이슈 기록:834](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L834) | v9a의 의도된 수리이며 ADR26과 연결된다. 같은 권위가 일반 planner 전환 후 남는 §3.3은 별도 lifecycle 결함이다. |
| Legacy 삭제 보류 | 처음부터 replacement parity와 ML·semantic gate 이후의 최종 단계로 명시. [누적 진척 기록:24](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L24) | 미완료이지만 단계 순서 위반은 아니다. |

### 1.6 최초 보고서의 판정에서 조정한 부분

| 기존 항목 | 누적 이력 반영 후 판정 |
|---|---|
| §3.1 metadata false-INFEASIBLE | **유지.** 확인한 v9a까지의 Memo 누적 기록에는 이 singleton metadata layout 축소를 승인한 결정이나 해당 반례의 해결 기록이 없다. |
| §3.2 caller/formal 비용 0.5→0.25 | **표현 수정.** 재현값은 유지하되 v9a 명시 estimator와의 불일치로 단정하지 않는다. 당시 syntactic guard profile의 상관 정밀도 한계이며, 이후 R4/v10이 exact boolean provenance를 결함 수정으로 기록했다. |
| §3.3 stale Memo authority | **유지하되 이력 추가.** v9a 이후 R3/R5 및 v10에 발견·수정 기록이 있다. v9a 문제는 남지만 현재 소스에도 반드시 남았다고 주장하지 않는다. |
| §3.4 recompile replay | **부분 정정.** 동일 epoch에서 선택을 보존하는 의도적 결정으로 인정한다. 새로운 물리 선택을 요구하는 변화의 재최적화 범위가 아직 좁다는 점만 별도 미완료로 남긴다. |
| §3.5 source-product 비용 | **알려진 미완료로 명확화.** v6/v7 문서 자체가 이미 GLM blocker로 보고했고, v9a는 해당 WIP를 제외했다. 후속 ADR25를 뒤늦게 소급 적용해서만 발견한 항목이 아니다. |
| §3.7 retention | **범위 명확화.** 초기 same-SB에서 실제 cross-call creation/reuse/cleanup까지 확장된 증거를 인정한다. 남은 차이는 여러 retention region을 optimizer가 선택하는지다. |
| §4 HOP clone·atomicity | **표현 수정.** Clone은 비용 불변성 repair, atomicity에는 이미 검증한 preflight/return/rollback 사례가 있다. 더 넓은 구조적 보장과 미검증 예외만 남긴다. |

후속 경계를 분명히 하면, native-cost projection·multi-output/bias·owner lifecycle·guard provenance 보강은 **v9a 이후의 기록**이다. 이 보고서는 그 결과를 v9a에 소급하지 않는다. [누적 진척 기록:107](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L107), [누적 진척 기록:116](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L116). 따라서 누적 기록을 반영한 뒤에도 metadata와 lifecycle 반례, 전체 GLM/migration 미완료라는 결론은 유지되지만, **의도한 동일 epoch 동작과 비용 추정 가정까지 모두 초기 설계 위반으로 묶었던 표현은 바로잡는다.**

## 2. 설계와 구현의 대응표

| 설계 요구 | v9a 구현 | 판정과 근거 |
|---|---|---|
| ProgramFacts와 native capability·비용 분리 | `MemoProgramFacts`, `MemoNativeRules`, `RuleMemo`, `MemoProgramModel/CostModel`로 분리 | **일치**. [FederatedPlanMemo.java:69](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/FederatedPlanMemo.java#L69) |
| Source-free immutable RuleMemo | rule에는 exec/input/output/variant만 저장, immutable list 복사. active BitSet은 별도 | **의미상 일치**. 단 compact primitive/CSR 중심 표현은 미완성. [RuleMemo.java:23](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/RuleMemo.java#L23) |
| 정확한 transient 정의·guard·함수 binding | sequence별 version, SELECT, loop head/LAST, actual/formal/return frame | **대체로 일치**. 호출 경계의 동일 boolean provenance 및 비용 profile 한계는 §3.2 참조. [MemoProgramFacts.java:488](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramFacts.java#L488) |
| 같은 compiled owner는 공통 rule 하나 | occurrence/shape context별 native rule 교집합 | **일치**. [MemoRuleBuilder.java:103](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoRuleBuilder.java#L103) |
| Metadata 접근은 payload 이동과 분리 | metadata role은 있지만 하나의 input layout과 일치해야 함 | **불일치·재현**. §3.1 |
| RD 기반 domain propagation | dependency incidence, revision, support cache, undo trail | **핵심 일치**. semantic obligation 등록 coverage는 별도 검증 공백. [MemoOptimizer.java:420](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java#L420) |
| Privacy UNKNOWN은 보류, positive residency만 shortcut | source-grounded induction과 guarded exact 평가, DEFER 유지 | **핵심 일치**. 초기 pruning 기대를 검증하는 테스트의 실패 1건은 §5.2에 별도 기록. [MemoPrivacy.java:330](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoPrivacy.java#L330), [MemoResidency.java:24](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoResidency.java#L24) |
| ZERO/ONE/MANY, entry·final exit, taken backedge DIRECT | cardinality를 공동 결정하고 마지막 iteration에서 backedge 검사를 하지 않음 | **ADR22와 일치**. [MemoProgramFacts.java:751](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramFacts.java#L751), [MemoLoopRuntime.java:102](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/runtime/controlprogram/federated/MemoLoopRuntime.java#L102) |
| Canonical map, anchor-free upload, REP collect | ordered pool와 balanced ranges, exact ingress 검사, replica-aware reader | **주요 기능 일치**. session/provenance 계약 검증은 제한적. [CanonicalWorkerPool.java:131](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/runtime/controlprogram/federated/CanonicalWorkerPool.java#L131), [FederationReplicaReader.java:1](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/runtime/controlprogram/federated/FederationReplicaReader.java#L1) |
| Compute·shared movement 비용을 각각 한 번 계산 | primitive cost와 shared-origin movement 분리, 선택 뒤 원래 비용 재감사 | **핵심 일치**. 상관 비용·source product는 §3.2·§3.5 예외. [MemoProgramModel.java:196](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L196) |
| Closed-tree DP와 exact global search | forest DP, lazy B&B, certified bound, exact sum | **알고리즘 핵심 일치**. 구현된 feasible space와 목적함수의 정확성과는 별도 문제. [MemoOptimizer.java:221](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java#L221) |
| Supply recipe와 retention 범위 공동 선택 | recipe는 source/target에서 결정, retention은 가장 넓은 허용 범위로 고정 | **제한된 구현**. §3.7 |
| 선택한 kernel 그대로 lowering | 선택 intermediate를 보존하는 fusion guard 및 lowering 검사 | **ADR26 반영**. variant 식별 검증은 더 약함. [AggUnaryOp.java:509](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/AggUnaryOp.java#L509), [MemoPlanRegistry.java:338](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoPlanRegistry.java#L338) |
| Same-epoch kernel 보존과 새 planning epoch 구분 | 같은 epoch의 committed plan을 exact clone에 replay | **문서화된 결정**. 새 모델의 자동 재최적화 범위는 §3.4의 별도 gate. |
| 프로그램별 selection 수명·rollback | strong global plan map 및 global registry replacement | **불일치·재현**. §3.3 |
| Multi-output owner의 slot vector | 구조상 List는 있으나 native 생성은 단일 output | **미완료**. §3.6 |
| Legacy 삭제와 전체 Docker 성능 gate | legacy와 공존, 작은 LogReg 1회 통과 | **미완료이지만 gate 순서상 타당**. §5.3·§6 |

## 3. 확인된 차이와 영향

### 3.1 Metadata layout을 과도하게 고정해 합법적인 분기 계획을 제거함

**분류: 확인된 결함 / 우선순위 높음 / 확신 높음.**

**설계:** `nrow/ncol` 등은 데이터 payload를 LOCAL로 모으는 연산이 아니다. metadata role에 대해 읽을 수 있는 layout 집합과 availability를 검사해야 한다. 서로 다른 branch의 native layout이 달라도 동일 metadata 접근이 가능하다. [구현 명세:180](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L180), [구현 명세:273](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L273), [Oracle Supply 57행](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_ORACLE_SUPPLY_DESIGN_2026-10-13_KO.md#L57).

**구현:** `MemoNativeRules`는 metadata 입력을 `[LOCAL]`, `[ROW]`, `[COL]`, `[REP]`인 별개의 CP rule로 만든다. `MemoProgramModel`은 metadata edge에서도 source layout과 선택 rule의 input layout을 정확히 일치시킨다. [MemoNativeRules.java:157](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoNativeRules.java#L157), [MemoNativeRules.java:311](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoNativeRules.java#L311), [MemoProgramModel.java:301](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L301).

**재현:** 같은 worker 2개, 같은 4×4 shape의 canonical ROW source `P`와 COL source `Q`를 두고 다음 모델을 생성했다.

```dml
if (p) { A = P; } else { A = Q; }
n = nrow(A);
print(n);
```

동결 v9a 결과는 `status=INFEASIBLE`, `diagnostics=[]`이다. 하나의 정적 nrow rule이 두 branch를 모두 수용하지 못한다. 각 source의 shape metadata는 이미 있으므로 이 사례는 이동을 추가해야 할 상황이 아니다. 이 probe는 nrow HOP가 남아 있는 모델을 검사했다. 앞선 compiler rewrite가 해당 접근을 상수로 제거하는 경우의 전체 pipeline 실패까지 주장하지 않는다.

**영향:** 단순 성능 문제가 아니라 합법 계획 집합을 축소한다. 이 표현으로 얻은 `INFEASIBLE`은 전체 설계 공간의 불가능성 증명이 아니다. [probe 소스](evidence/memo-v9a-design-audit-2026-10-13/MemoMetadataBranchProbe.java), [실행 결과](evidence/memo-v9a-design-audit-2026-10-13/MemoMetadataBranchProbe.log).

### 3.2 Caller와 formal이 같은 boolean 값이어도 비용 확률을 두 번 곱함

**분류: 재현된 상관 모델 한계·후속 의미 보강 대상 / 영향 높음 / 재현 확신 높음.**

**설계와 당시 비용 결정:** exact 실행 상관과 정상 continuation 보존이 목표다. 다만 v9a 이전 누적 기록은 `(guardId, callFrame)`가 다른 unknown decision을 독립으로 추정한다고 명시했다. 따라서 아래 0.25는 **그 당시 선언된 syntactic-guard 비용 함수의 계산 결과와 모순된다고 할 수 없다.** 의미상 같은 actual boolean을 formal alias와 통합하지 못해 정상 반환 비용을 더 할인한다는 정밀도 한계다. 이후 R4/v10은 이를 exact boolean provenance 결함으로 식별하고 수정한 것으로 기록한다. 당시 명시한 반복 guard 추정 가정과 후속 강화된 의미 계약을 구분한다. [누적 이슈 기록:765](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L765), [누적 이슈 기록:864](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L864).

**구현:** v9a `GuardDecision`의 identity는 syntactic `guardId`와 `callFrame`이다. caller guard와 callee guard가 별도로 삽입되고 각각 branch 확률이 곱해진다. [MemoBindingResolver.java:51](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoBindingResolver.java#L51), [MemoProgramModel.java:1060](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L1060), [MemoProgramModel.java:1219](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L1219).

**재현:** `if(p){A=f(p); C=abs(...);}`에서 `f(q)`가 `if(q)`일 때만 반환하는 작은 모델을 사용했다. `p=true`인 호출에서는 `q=true`도 확정되므로 함수 정상 반환을 다시 확률 0.5로 할인하면 안 된다.

| 값 | 결과 |
|---|---:|
| 하나의 correlated 0.5 guard를 적용한 native primitive | 약 0.0000015416741371154786 |
| v9a가 합산한 해당 owner 비용 | 약 0.0000007708370685577393 |
| 실제 / 기대 | **0.5** |

의미상 동일 boolean의 상관을 보존하는 기준에서는 원 primitive의 0.5인데, v9a의 syntactic decision 독립 가정에서는 0.25가 된다. Exact search는 후자의 명시 함수에 대해 최적일 수 있다. 이 probe는 **두 상관 모델의 비용 차이**를 재현했으며, 모든 v9a OPTIMAL 반환이 수학적으로 잘못됐다는 뜻은 아니다. 최종 선택 역전이나 실제 privacy 유출도 재현하지 않았다. 이후 provenance 수정이 feasibility/privacy에 미치는 영향은 비용 profile과 별도로 검증해야 한다.

[probe 소스](evidence/memo-v9a-design-audit-2026-10-13/MemoGuardV9aProbe.java), [실행 결과](evidence/memo-v9a-design-audit-2026-10-13/MemoGuardV9aProbe.log).

### 3.3 이전 Memo 선택 정보가 일반 컴파일 이후에도 살아 있음

**분류: 확인된 lifecycle 결함 / 우선순위 높음 / 확신 높음.**

**설계:** selection과 cache의 권위는 해당 snapshot/프로그램의 살아 있는 정의에 한정되며, 다른 compile 상태로 전환할 때 이전 selection을 계속 사용하면 안 된다. 실패 시 해당 owner의 상태를 복구해야 한다. [구현 명세:133](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L133), [구현 명세:913](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L913), [구현 명세:929](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L929).

**구현:** `MemoPlanRegistry`는 strong `IdentityHashMap`으로 프로그램과 plan을 보존하고, `Plan`도 owner를 참조한다. 프로그램별 제거·원래 HOP 상태 복구 경로가 없다. `preservesNativeKernel()`는 남아 있는 plan을 조회한다. Supply 등록과 recompile 복원도 전역 map을 교체한다. [MemoPlanRegistry.java:44](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoPlanRegistry.java#L44), [MemoPlanRegistry.java:179](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoPlanRegistry.java#L179), [MemoPlanRegistry.java:295](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoPlanRegistry.java#L295), [MemoPlanRegistry.java:547](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoPlanRegistry.java#L547), [MemoPlanRegistry.java:606](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoPlanRegistry.java#L606).

**재현:** `sum(X*Y)`를 Memo로 계획한 동일 `DMLProgram`을 `FEDERATED_PLANNER=NONE`, federated compilation 비활성 상태로 다시 LOP 생성했다.

```text
beforeOrdinary hasPlan=true selected=true preservesKernel=true
afterOrdinary  hasPlan=true selected=true preservesKernel=true
parentLop=org.apache.sysds.lops.PartialAggregate
```

일반 컴파일로 전환했지만 Memo authority와 선택 플래그가 남는다. 따라서 Memo의 선택 보존용 fusion 억제가 일반 경로에도 계속 적용된다. [probe 소스](evidence/memo-v9a-design-audit-2026-10-13/MemoLifecycleProbe.java), [실행 결과](evidence/memo-v9a-design-audit-2026-10-13/MemoLifecycleProbe.log).

**추가 정적 위험:** A의 plan을 보존한 채 B를 publish하면 전역 supply map이 B의 내용으로 바뀐다. A의 recompile scope가 나중에 닫히면 이전 global snapshot을 복원해 B의 변경까지 덮을 수 있다. 이는 owner별 격리가 없는 코드 경로에서 도출한 위험이며, 이번 직접 probe는 동일 프로그램의 planner 전환만 실행했다. 후속 문서에 있는 owner별 weak lifetime/lease/rollback 수정은 v9a에는 없다.

### 3.4 동일 epoch 재컴파일은 의도적으로 기존 선택을 보존하고 새 계획 전환은 별도 처리함

**분류: 문서화된 동일 epoch 결정 + 새 계획 전환의 미완료 범위 / 확신 높음.**

**설계:** 재컴파일 때 새로운 epoch의 facts/rules/domains/selection을 만들고 이전 selection을 그대로 재사용하지 않는다. 같은 loop invocation의 ExecType/native output pair는 새 snapshot의 경계 제약으로 유지한다. 모델이 달라지면 비용 snapshot과 최적화 결과도 새로 기록한다. [구현 명세:579](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L579), [구현 명세:594](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L594), [구현 명세:598](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L598).

**누적 결정:** 구현 초기에 선택 뒤 scalar/algebraic rewrite가 DataGen/TRead kernel을 없애는 W2 문제가 있었다. 이를 해결하기 위해 **선택을 바꾸지 않는 통계/memory refresh 및 exact clone은 같은 Memo epoch로 유지**하고, 실제 새 physical plan만 별도 planning epoch로 만들기로 기록했다. [누적 이슈 기록:691](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L691). 최초 보고서는 이 구분 없이 recompile replay 전반을 명세 위반으로 읽은 점이 과했다.

**구현:** 일반 recompile 경로는 기존 plan을 찾아 clone의 origin ID에 따라 실행형과 output을 다시 입힌다. 기존 epoch/action identity를 사용해 supply schedule을 remap하며 새 `MemoProgramModel.optimize()`를 호출하지 않는다. Dynamic rewrite도 이 경로에서 차단한다. [MemoPlanRegistry.java:386](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoPlanRegistry.java#L386), [MemoPlanRegistry.java:516](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoPlanRegistry.java#L516), [Recompiler.java:487](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/recompile/Recompiler.java#L487), [Recompiler.java:526](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/recompile/Recompiler.java#L526).

테스트도 committed plan 재사용과 새로운 세 번째 shape의 stale 거절을 기대한다. [MemoCompilerIntegrationTest.java:98](../experiments/memo-migration-20261013/docker-engine-v9a/test-sources/org/apache/sysds/hops/fedplanner/memo/MemoCompilerIntegrationTest.java#L98), [MemoCompilerIntegrationTest.java:132](../experiments/memo-migration-20261013/docker-engine-v9a/test-sources/org/apache/sysds/hops/fedplanner/memo/MemoCompilerIntegrationTest.java#L132).

**영향:** 이 경로가 같은 epoch의 검증된 clone을 replay하는 것은 문서화된 선택이다. 다른 variant가 필요한 shape/model 변화는 stale로 거절하며, 확인한 recompile 경로 안에서 자동으로 새 facts/domain/비용/selection을 만들지는 않는다. 남은 gate는 ‘동일 epoch 보존을 없앨 것’이 아니라 **새 물리 계획이 필요한 변화에서 명시적인 새 planning epoch로 전환하는 지원 범위**다. 최상위 재계획 호출과 runtime 재컴파일의 역할을 정합화해 문서화해야 한다.

### 3.5 Native 비용을 만들 때 source 환경 조합을 여전히 순회함

**상세 후속 설계:** [Memo 비용 조합 폭발과 Oracle 기반 생성·비용 투영 설계](FEDPLANNER_ORACLE_GUIDED_COST_PROJECTION_DESIGN_2026-10-13_KO.md). Source/layout/cost-context 조합의 차이, v9a 이후 ADR25 적용 이력, 가능한 관계에서만 생성하는 알고리즘, 정확성 조건과 검증 기준을 별도로 정리했다.

**분류: v6/v7부터 기록된 GLM blocker·v9a에서 제외한 WIP / 우선순위 중간 / 확신 높음.**

**설계:** native cost primitive의 문맥은 occurrence, activation, loop phase/frequency, 비용에 영향을 주는 shape이다. 비용과 무관한 source identity 조합은 primitive를 생성하기 전에 제거한다. [구현 명세:1465](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L1465).

**구현:** `nativeCostTerms()`는 모든 input binding을 `visitActive()`에 넘기고, 내부에서 `resolver.visitJoint()`로 source 환경을 순회한 뒤 환경마다 unary 비용 배열을 만든다. 그 visitor가 source identity를 직접 비용 입력으로 사용하지 않더라도 해당 환경을 먼저 펼친다. [MemoProgramModel.java:889](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L889), [MemoProgramModel.java:931](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L931).

**영향:** native RuleMemo의 source-free 성격과 별개로, **비용 생성 단계에 조합 증가가 남는다**. 동일 가중합을 여러 primitive로 분해하므로 불필요한 작업 및 반올림 분해 차이가 생길 수 있다. 이 사실만으로 모든 계산 비용이 틀렸다고 단정하지 않는다. [누적 진척 기록:85](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L85)와 [누적 이슈 기록:819](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L819)는 v9a 이전부터 180초 진단·35.2만 개 이상의 unary term을 남긴 알려진 GLM blocker로 기록했다. v9a는 이 WIP를 명시적으로 제외했고, 후속 ADR25와 구현이 projection 해결책을 구체화했다. 따라서 알려진 미완료 항목을 새로 발견한 소급 설계 위반처럼 읽으면 안 된다.

### 3.6 Multi-output owner는 자료구조만 준비되고 실제 native coverage가 미완료

**분류: 미완료 지원 범위 / 해당 연산 사용 시 우선순위 높음 / 확신 높음.**

**설계:** 하나의 builtin 실행 owner가 여러 output slot의 layout vector를 공동으로 결정하며, 실행 비용은 한 번만 소유한다. [구현 명세:132](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L132), [구현 명세:186](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L186), [구현 명세:271](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L271).

**구현:** `RuleMemo.Rule.outputs`는 list지만 v9a의 native/forced-local variant 생성은 `List.of(value)` 형태다. 현재 작업 트리의 QR, transformencode, CP bias 지원과 관련 테스트를 v9a에 귀속하면 안 된다. [RuleMemo.java:25](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/RuleMemo.java#L25), [MemoNativeRules.java:200](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoNativeRules.java#L200).

**영향:** 다중 출력 native 연산 전체를 지원한다고 주장할 수 없다. DML 함수의 여러 return binding 지원과 builtin 한 번 실행의 slot별 layout 지원은 다른 문제다. 명세 자체도 native multi-output을 blocker로 남겼으므로, 이를 조용한 runtime 오답으로 단정하기보다 명시적인 완료 gate로 다뤄야 한다. [구현 명세:1309](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L1309).

### 3.7 Supply retention 범위는 최적화 선택이 아니라 고정 정책

**분류: 설계보다 제한된 구현 / 우선순위 중간 / 확신 높음.**

**설계:** materialization의 생성 위치·재사용 범위·수명과 비용이 같은 선택을 나타내야 하고, 허용 범위를 선택하는 문제를 optimizer와 emitter가 공유한다. [구현 명세:710](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L710).

**구현:** `retentionScope()`는 exact origin을 보존할 수 있으면 가장 넓은 `PROGRAM_ROOT` 또는 `FUNCTION_INVOCATION`을 결정한다. 여러 structured retention region을 domain으로 만들어 비교하지 않는다. Action 및 region ID는 선택 후 이 정책에서 복원한다. [MemoProgramModel.java:677](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L677), [MemoProgramModel.java:794](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L794).

**누적 진전:** 초기 문서는 same-SB sharing만으로 cross-iteration/call 완료를 주장하지 말라고 했다. 이후 v6→v7 기록에는 한 모델 UPLOAD 비용·한 selected action·두 함수 demand의 한 creation/한 reuse·정확한 수치·종료 후 lease 0을 연결한 W2 검증이 있다. [누적 진척 기록:44](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L44), [누적 진척 기록:83](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L83). 따라서 v9a에 cross-call 재사용 자체가 없다는 뜻은 아니다.

**남은 영향:** 구현된 고정 수명 정책 안의 최적성은 가능하지만, 명세가 서술한 retention 선택 공간 전체를 최적화한 것은 아니다. 이번 감사에서는 더 좁은 scope가 실제로 더 좋은 목적함수 값을 만드는 반례를 재현하지 않았다. 따라서 즉시 비용 오답으로 분류하지 않는다.

## 4. 추가 구현 차이와 검증 공백

다음 항목은 직접 재현한 metadata·lifecycle 결함 및 guard 비용 모델 차이와 구분해야 한다.

| 항목 | 설계와 다른 점 | 현재 판단 |
|---|---|---|
| 데이터 표현 | RuleMemo의 nested List/record는 명세의 primitive/CSR 표현과 다르다. 반면 shape/cost용 private HOP clone은 live compiler mutation이 비용을 바꾸던 결함을 고친 명시 결정이다. [구현 명세:169](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L169), [RuleMemo.java:39](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/RuleMemo.java#L39), [MemoRuleBuilder.java:127](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoRuleBuilder.java#L127) | **RuleMemo 표현 최적화는 별도 미완료; clone은 의도된 repair**. [누적 이슈 기록:812](evidence/memo-v9a-design-audit-2026-10-13/history-review/SESSION_ISSUES_2026-10-13.md#L812). Clone 존재 자체를 미이행 근거에서 제외한다. heap 개선은 실측하지 않았다. |
| ADR21 condition coverage | 명세는 모든 use/boundary 의무의 EDGE/JOINT/STATIC_TRUE/PENDING 분류와 등록 누락 검사를 요구한다. 구현의 `coverage[]`는 주로 native owner coverage다. [구현 명세:354](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L354), [MemoProgramModel.java:158](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L158) | **검증 공백**. 지금 조건들이 충분히 분해된다는 전제와 향후 새로운 비분리 조건의 등록 완전성은 별개다. |
| Lowering variant 보존 | Rule에는 variant가 있지만 committed Selection은 exec/output/class/opcode/arity 위주다. `verifyLowering`은 주로 Lop 존재·type·exec·FOUT을 검사한다. [구현 명세:922](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L922), [MemoPlanRegistry.java:61](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoPlanRegistry.java#L61), [MemoPlanRegistry.java:338](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoPlanRegistry.java#L338) | **검증 공백**. 다른 variant가 실제로 잘못 실행되는 현재 반례는 찾지 못했다. |
| Canonical descriptor | `POOLv1`은 endpoint 목록을 직렬화한다. 명세의 program/session 소유 descriptor와 geometry version·REP 생성 provenance를 descriptor 자체에서 모두 증명하지는 않는다. [구현 명세:866](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L866), [CanonicalWorkerPool.java:86](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/runtime/controlprogram/federated/CanonicalWorkerPool.java#L86) | **계약 표현/증거가 더 제한적**. endpoint/range 검사나 일반 REP collect 자체가 실패했다는 뜻은 아니다. |
| Emission rollback | 사전검증 뒤 function metadata와 DAG를 순차 수정한다. 전체 변경을 되돌리는 명시적인 undo log는 보이지 않는다. [구현 명세:924](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L924), [MemoSupplyLowering.java:73](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/lops/compile/MemoSupplyLowering.java#L73), [MemoSupplyLowering.java:139](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/lops/compile/MemoSupplyLowering.java#L139) | **이미 고친 사례와 더 넓은 보장 구분**. v7 검토의 metadata/return/marker 문제는 v9a preflight·cleanup 수정 및 delta 53개 PASS로 연결됐다. [누적 진척 기록:93](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L93), [누적 진척 기록:102](evidence/memo-v9a-design-audit-2026-10-13/history-review/FEDPLANNER_IMPLEMENTATION_PROGRESS_2026-10-13.md#L102). 추가 구조적 rollback 보장은 미검증이며, 같은 옛 결함이 남았다고 재판정하지 않는다. |
| 예외 정리 | runtime session/frame 복원은 cleanup 뒤 수행되며 `finally` 보호가 없다. Loop entry의 여러 변수 이동도 순차 commit된다. [구현 명세:724](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L724), [PlannedSupplyRuntime.java:152](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/runtime/controlprogram/federated/PlannedSupplyRuntime.java#L152), [PlannedSupplyRuntime.java:193](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/runtime/controlprogram/federated/PlannedSupplyRuntime.java#L193), [FEDSupplyInstruction.java:100](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/runtime/instructions/fed/FEDSupplyInstruction.java#L100) | **예외 경로 위험**. 앞선 이동 성공 뒤 후속 이동/cleanup 실패 시 부분 상태가 남을 수 있다. 이번 감사에서는 실패 주입을 실행하지 않았다. |

이 목록에 있는 구조적 차이만으로 수치 오류나 privacy 위반을 주장하지 않는다. 직접 반례가 있는 항목을 먼저 고치고, 이후 필요한 보장의 실제 검증으로 연결하는 것이 타당하다.

## 5. 실행 증거와 그 한계

### 5.1 이번 감사에서 새로 확인한 결과

| 검증 | 결과 | 증명하는 범위 |
|---|---|---|
| v9a frozen component 20개 class | **225 tests PASS**, 17.288초 | 현재 작업 트리 대신 동결 classpath를 사용한 component 계약. 전체 ML·전체 source build 통과는 아님. |
| 작은 exhaustive fixture 5개 | 총 **2,604 assignments**, cache off/on optimum·cost·tie 일치 | propagation/solver가 모델의 predicate와 비용을 보존한다는 증거. |
| Metadata mixed-layout probe | **INFEASIBLE 재현** | §3.1의 합법 계획 누락. |
| Caller/formal guard probe | **상관 보존 기준 비용의 0.5 재현** | §3.2의 선언된 syntactic-guard 모델과 provenance 보강 모델의 차이. |
| Memo→ordinary compile probe | **hasPlan/selected/preservesKernel 모두 true 유지** | §3.3의 stale authority. |
| 별도 protected model 테스트 1개 | **FAIL** | 초기 privacy pruning 시점에 관한 기존 assertion 불일치. 상세는 아래. |

[225개 실행 로그](evidence/memo-v9a-design-audit-2026-10-13/frozen-focused-tests.log), [정확한 실행 명령](evidence/memo-v9a-design-audit-2026-10-13/focused-test-command.txt), [세 probe 재현 명령](evidence/memo-v9a-design-audit-2026-10-13/reproduce-probes.txt).

Exhaustive fixture의 독립성에는 한계가 있다. Reference enumeration은 solver의 pruning/DP를 사용하지 않지만 **같은 모델의 predicates와 `evaluateCost()`를 호출**한다. 따라서 §3.1처럼 모델에서 빠진 합법 계획이나 §3.2처럼 잘못 정의된 목적함수를 독립적으로 발견하는 시험은 아니다. [MemoExhaustiveParityTest.java:52](../experiments/memo-migration-20261013/docker-engine-v9a/test-sources/org/apache/sysds/hops/fedplanner/memo/MemoExhaustiveParityTest.java#L52).

### 5.2 실패와 중단 기록을 통과 기록에 섞지 않음

초기에는 `MemoFederatedModelTest`까지 포함한 더 넓은 묶음을 실행했다. 이 class의 GLM 모델 생성 진단이 오래 계속되어 해당 감사 프로세스만 중단했다. 이 실행을 전체 통과/전체 실패 결과로 사용하지 않았다. [중단 로그](evidence/memo-v9a-design-audit-2026-10-13/frozen-component-tests.log).

중단 전에 관찰된 실패는 별도 메서드 실행으로 확인했다.

```text
MemoFederatedModelTest.protectedRepresentativeAbsLoopMultiplyAndSumHasAnExactPlan
AssertionError: source-grounded protected ABS must be privacy-pruned before cost construction
MemoFederatedModelTest.java:127
run=1 failures=1
```

이 메서드는 앞에서 `OPTIMAL` 여부를 확인한 뒤 초기 RuleMemo에 CP rule이 남아 있는지를 검사한다. 따라서 **보호 데이터가 실제로 local 실행되었다는 증거가 아니라, earliest pruning에 대한 테스트 기대가 충족되지 않은 증거**다. 테스트 기대가 낡았는지, 초기 분석이 늦는지, fixture의 source wiring을 수정해야 하는지는 추가 구분이 필요하다. 이 실패를 제외하고 ‘전체 테스트가 통과했다’고 보고할 수 없다.

[MemoFederatedModelTest.java:118](../experiments/memo-migration-20261013/docker-engine-v9a/test-sources/org/apache/sysds/hops/fedplanner/memo/MemoFederatedModelTest.java#L118), [실패 로그](evidence/memo-v9a-design-audit-2026-10-13/protected-model-method.log), [실행 명령](evidence/memo-v9a-design-audit-2026-10-13/protected-model-command.txt).

### 5.3 기존 Docker LogReg 성공의 정확한 범위

v9a `memo-v9a-logreg-diagnostic04`의 보존 결과를 읽고 소스/class identity를 대조했다. 이번 감사에서 Docker workload를 다시 실행하지는 않았다. 실제 workload 성능 근거는 이 Docker 결과만 사용했으며 host 모델 probe 시간을 성능 비교로 쓰지 않았다.

| 항목 | 보존된 값 |
|---|---|
| 실행 | `run_LAN_docker.sh` 계열 harness, `planner=memo`, `ml_logreg` 한 case |
| 환경 | 고정 image SHA-256, 4 CPU, container 8 GiB, worker 3개, container loopback |
| 판정 | `PASSED`, `selectedOptimal=true`, `nativeFedExecution=true`, `loweringVerified=true` |
| 탐색 | native rules 920, search nodes 2,934,107 |
| 계획 시간 | facts+rules+model+optimization+projection = **20.665881174초** |
| Memo compile/runtime | **21.571258초 / 3.071초** |
| CP reference compile/runtime | **1.174157초 / 0.665초** |
| 수치 결과 | 8×2 model, 16 entries 일치, 최대 절대 오차 **1.6653345369377348e-16** |
| 관측 위반 | runtime conversion/audit violation 배열이 비어 있음 |

근거: [Docker result](evidence/memo-v9a-design-audit-2026-10-13/docker-result.json), [phase 및 lowering 로그](evidence/memo-v9a-design-audit-2026-10-13/docker-fed.log), [환경 manifest](evidence/memo-v9a-design-audit-2026-10-13/docker-manifest.json).

**한계:** CP는 수치 reference이며 이전 federated planner와의 성능 비교가 아니다. 별도 canonical proof 파일은 요청되지 않았고 존재하지 않는다. `loweringVerified`는 구현된 checker가 통과했다는 뜻으로 §4의 variant 검증 공백을 해소하지 않는다. LogReg 1회 결과에 GLM 성공, 모든 shape/privacy/epoch 지원, 5회 paired 성능 비교, peak memory 및 ablation 완료를 포함시킬 수 없다. 명세의 성능 gate는 [구현 명세:1274](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L1274)에 더 넓게 정의되어 있다.

## 6. 전체 migration 완료 여부

**현재 판정은 ‘Memo 구현 및 일부 통합 검증 완료, 전체 migration 미완료’다.**

v9a에는 `placement/` Java 76개와 `fedCostBased/fedExact/` 32개가 그대로 남아 있다. `COMPILE_COST_BASED`는 old Local, `COMPILE_EXACT`는 old Exact, `COMPILE_MEMO`는 새 Memo를 생성한다. [FederatedPlannerFactory.java:35](../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/ipa/FederatedPlannerFactory.java#L35). 다만 Memo package 내부에서 old placement/fedExact package를 직접 import하지 않으므로, 새 planner를 실제로 별도 구현했다는 점도 확인된다.

Legacy 잔존 자체는 설계 위반으로 볼 수 없다. 명세 M7–M8은 semantic/numeric/workload gate를 통과한 뒤 삭제하도록 요구한다. 아직 GLM 및 전체 semantic 전환 근거가 부족한 v9a에서 삭제를 미룬 것은 그 순서와 맞는다. [구현 명세:1258](evidence/memo-v9a-design-audit-2026-10-13/design-baseline/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L1258). 다만 ‘기존 구현을 모두 대체하고 제거했다’는 완료 설명은 사실과 다르다.

| 범위 | 완료 판단 |
|---|---|
| Facts·immutable rules·incremental propagation 기반 | 실질 구현 확인. metadata/guard 반례와 coverage 제한이 있어 전체 semantic 완료 판정은 유보. |
| Canonical supply·loop·함수 runtime | 상당한 구현과 component 증거 존재. 전체 scope/lifecycle/recompile/예외 보장은 미완료. |
| Cost·exact optimizer | 구현 모델에 대한 exact 탐색 증거 존재. 모델의 전체 설계 적합성과 cost context projection은 별도 미완료. |
| LogReg | 동결된 작은 Docker fixture 1회의 수치·선택/lowering 검증 성공. |
| GLM·native multi-output·재컴파일 확대 | v9a 전체 완료 증거 없음. 후속 source 결과와 구분 필요. |
| 성능 개선·legacy 제거 | 명세의 paired 측정/ablation 및 삭제 gate 미충족. |

## 7. 다음 구현·검증의 우선순위

1. **Metadata 입력의 layout mask/access 계약 복원.** §3.1 반례에서 ROW/COL 양 arm을 이동 없이 읽는 계획을 보존하고, metadata를 payload movement로 바꾸지 않는 회귀를 추가한다.
2. **Actual/formal alias의 guard provenance와 profile 버전 구분.** §3.2의 상관 정밀도를 높이는 수정은 당시 선언 objective와 후속 강화 objective를 구분해 검증한다. 동일 조건·반대 조건·새 boolean version, 비용과 privacy cache를 각각 확인한다.
3. **프로그램/epoch별 selection 수명 관리.** 일반 planner 전환, A/B 프로그램 공존, stale recompile 및 실패 rollback에서 해당 owner의 권위만 변경되는지 확인한다. 이후 버전의 수정이 존재하더라도 해당 버전에서 다시 검증해야 한다.
4. **Recompile 경계의 문서 정합화.** 이미 기록된 same-epoch kernel 보존 결정을 유지하면서, 새로운 물리 선택이 필요할 때의 새 planning epoch 진입 조건·재최적화 지원 범위를 명확히 한다.
5. **ADR25·multi-output·retention 범위의 미완료 gate 해소.** 소스 product 없는 비용 생성, slot별 semantics, 선택 scope와 runtime lifetime 일치를 각각 확인한다.
6. **별도 protected-model 실패를 해소하고 독립 semantic oracle을 보강.** 현재 solver와 동일 predicate를 재사용하는 exhaustive 시험에 더해, 설계상 합법이어야 하는 작은 사례를 독립적으로 검사한다. 이 protected-model 실패는 v6 기록의 legacy source-scanner 실패와 다른 메서드이므로, 과거 scanner 수정으로 해결됐다고 간주하지 않는다.
7. **동일 동결 조건에서 LogReg/GLM 및 성능 gate를 완료한 후 legacy를 제거.** 현 audit의 component 시간이나 CP reference를 old/new 성능 향상으로 인용하지 않는다.

## 8. 변경 범위와 재현 자료

이번 누적 이력 보완에서는 구현이나 테스트를 다시 실행하지 않고 문서·과거 로그를 대조했다. §5.1–§5.2의 실행 결과는 최초 감사에서 수집한 값이며 이 개정의 새 테스트 결과가 아니다.

이 감사는 production/test 구현과 기존 스냅샷을 수정하지 않았다. 새 보고서, 설계 기준 사본, 비교 manifest, 테스트/probe 로그와 재현용 작은 Java probe를 문서 evidence 폴더에 저장했다. 저장소 지침에 따라 `SESSION_ISSUES_2026-10-13.md`에는 감사 결과를 별도 항목으로 추가한다.

- [근거 폴더](evidence/memo-v9a-design-audit-2026-10-13/)
- [고정한 설계·소스 hash manifest](evidence/memo-v9a-design-audit-2026-10-13/manifest.json)
- [재현 명령](evidence/memo-v9a-design-audit-2026-10-13/reproduce-probes.txt)
- [최종 문서·동결 소스 검증](evidence/memo-v9a-design-audit-2026-10-13/verification.json)

**누적 기록까지 반영한 최종 판정:** 초기 compiler/shape 실패에서 v4 이후 component·W2 보완, v8 실제 fusion 실패, v9a LogReg 성공까지 진행한 구현이다. 의도된 epoch·snapshot·추정 정책과 후속 미완료를 구분하면 상당수 차이는 설명되지만, v9a의 metadata·lifecycle 반례와 GLM/전체 migration gate까지 해소되지는 않는다.
