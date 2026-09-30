# 여섯 질문에 대한 pruning·증분 전파·DP 비판적 감사

- 기준: `5a3ef7d759bf30ee2d848f7123fd963c4d5bbd57`, 2026-09-29.
- 상태: **현재 코드 읽기 및 계획 수정. 새 최적화 구현/테스트/실험은 하지 않음.** 실행 중 run06은 변경하지 않았다.
- 개정 대상: [성능 회복 계획](FEDPLANNER_REFACTOR_PERFORMANCE_RECOVERY_PLAN_2026-09-29_KO.md).
- 코드 약어: `P = src/main/java/org/apache/sysds/hops/fedplanner/placement/`, `D = src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/`.

## 0. 먼저 답변과 판정

| 질문 | 판정 | 핵심 |
|---|---|---|
| 1. Hop 내부에서 무엇을 일찍 제거하는가? | **부분 구현됨** | protected payload local input, 불법 emission, 알려진 bottom 등. Hop-static / operand-domain / complete-tuple / physical-authority를 구분해야 함 |
| 2. Hop 관계에서는 무엇을 제거하는가? | **부분 구현됨** | 같은 owner의 상충 binding, source/action 없는 support, OR 대안 전부 소실, all-writer 불만족. 완성되지 않은 관계의 부재는 불법 증거가 아님 |
| 3. source 출발 증분 전파인가? | **부분적으로만 맞음** | block 내부 후위 초기 생성 + dependency worklist/SCC가 있지만 전역 closure와 전체 인덱스 재구축도 남음 |
| 4. 불법만 제거하고 합법 공간을 유지하는가? | **계층별로 답이 다름** | shared 후보 legality pruning의 필수 계약은 맞음. DP의 quotient/선택/예산 종료까지 모두 “불법 제거”라고 하면 틀림. 전체 프로그램 완전성 검증도 아직 없음 |
| 5. 모두 가장 일찍 적용했는가? | **아님** | all-rejected mask 우회, full tuple 이후 Oracle, privacy-excluded row의 profile 계산, dense freeze 후 support reduction이 남음 |
| 6. DP는 복잡도 감소를 고려하는가? | **그렇지만 너무 늦은 축약도 있음** | weighted elimination-order portfolio와 conditional compaction이 이미 있음. 처음부터 모든 불법 조합을 피하거나 root 전처리가 싸다는 뜻은 아님 |

가장 중요한 원칙은 **“빨리”가 아니라 “그 불법성을 증명할 정보가 확정되는 즉시”**다. 어떤 prefix를 자르려면 이후 어떤 suffix를 붙여도 합법 complete plan이 될 수 없음을 증명해야 한다. 이를 확인하려고 다시 전체 suffix를 열거하면 성능상 이득이 없다. 명시적 단항 제약·모순·support index 같은 저렴한 충분조건부터 사용한다.

## 1. Hop 내부 early pruning: 단위를 섞지 않기

`Hop 삭제`, `input tuple 제외`, `emission 제외`, `materialization action 제외`는 다른 작업이다. `<CP,FOUT>` emission 하나가 불법이라고 같은 tuple의 `<CP,LOUT>`/`<FED,FOUT>`까지 제거해서는 안 된다.

| ID / 조건 | 필요한 확정 정보 | 현재 위치와 평가 | 가장 이른 안전 단계 / 보존할 반례 |
|---|---|---|---|
| L1: 입력 domain이 진짜 bottom | 해당 입력 revision이 완결되었고 가능한 값이 없음 | Generator `123–131`: Oracle 준비 전 이미 단락 | domain 확보 직후. 아직 처리하지 않은 loop writer/formal은 bottom이 아니라 미정 |
| L2: protected PAYLOAD의 `ABSENT_LOCAL` | exact compiled edge/value/consumer identity, source privacy, PAYLOAD 접근 | Closure `491–501,1480–1510,5118–5119`: Cartesian 이전 mask, 이미 조기 적용 | operand-domain 확정 직후. metadata(`nrow`)와 함수 handle 제외; UNKNOWN을 PRIVATE로 추정 금지 |
| L3: privacy 불법 output emission | output privacy + 실제 capability/profile + input payload 접근 | Generator `86–101`의 gate: emission/action 생성 전, 일부 state/set 작업은 앞서 수행 | 정보 확보 뒤 state/emission 생성 전에 동일 kernel 적용. PUBLIC 집계 결과가 PRIVATE_AGGREGATE 입력 수집까지 허용하지 않음 |
| L4: TRead/TWrite·recompile 금지 emission | Hop 종류 / recompile context | Generator `133–136,184–190,247–248`: 정책이 존재 | Hop-static deny-mask 준비 가능. **금지 emission만** 제외; whole tuple 삭제는 별도 증명 |
| L5: datatype/shape/partition상 runtime 미지원 | exact dimensions/literals, 입력 FType 조합, 필요시 single-partition proof | Generator `146–159,191–192`: 주로 complete tuple Oracle 이후 | 입력과 무관한 guard만 선계산. joint rule은 tuple 또는 검증된 partial-domain predicate가 필요. UNKNOWN shape 자체는 불법 증거가 아님 |
| L6: scalar output native FOUT | 확정 output datatype | OracleFacade `160–180`: FED/FOUT→FED/LOUT 정규화 | Hop-static 정규화 가능. scalar FED 실행까지 금지하면 안 됨 |
| L7: derived FOUT 불가능 | positive matrix shape, 허용 context/FType, exact durable anchor, **같은 row의 합법 native source emission** | Generator `205–239`; CostSemantics `1320–1348` | 정적 금지 조건은 anchor 탐색 전, authority-dependent 조건은 authority 확보 즉시. CP native capability만으로 전체 FOUT domain을 지우면 안 됨 |
| L8: tuple의 모든 suffix 불법 | prefix와 남은 domain 전체에 대한 authoritative 불가능 증명 | Generator `666–684`: privacy mask 이후에는 일반 prefix guard 없이 leaf에서 Oracle 호출 | 증명 가능한 guard만 prefix로 이동. 입력 FType가 서로 다르거나 비싸 보이는 것은 충분조건 아님 |

근거: `P/PlacementCandidateGenerator.java:86–101,117–263,358–406,666–684`; `P/CandidatePrivacyInputPruning.java:22–24,94–133`; `P/PlacementRelationClosure.java:1408–1527`; `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java:97–99,140–152,267–332`; `src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java:135–180`; `P/PlacementCostSemantics.java:1320–1348`.

### 추가로 발견한 earliest-stage 공백

1. **ALL_REJECTED mask:** `PlacementRelationClosure.java:1504–1507`은 mask 후 tuple 수가 0이면 mask를 적용하지 않는다. 따라서 현재 모든 privacy-impossible product가 생성 전에 없어지는 것은 아니다. 다만 이 guard 삭제는 곧바로 안전하지 않다. **certified terminal bottom과 provisional seed/CFG bottom을 분리하는 테스트부터** 추가한다. 전자만 전체 product 생략 후보로 삼는다.
2. **빈 emission의 profile:** Generator `391–403`은 privacy로 허용 emission이 없어도 profile 계산 뒤 excluded fact를 만든다. profile이 audit/오류/후속 inference 계약에 필요한지 먼저 확인한다. 결과가 필요하면 memo화하고, 필요 없다고 증명한 경로만 생략한다. 가짜 Oracle capability/profile을 만들어 채우지 않는다.
3. **Oracle 규칙 복제 금지:** capability용 Hop 준비/cache가 이미 있다(`OracleFacade:135–158`). 새 성능 개선은 기존 판정 kernel의 prepared unary guard 또는 profile 준비 경로를 재사용해야 한다. 같은 opcode 표를 다른 곳에 복사하는 것은 회귀 위험을 늘린다.

## 2. Hop 관계 그래프의 early pruning

| ID / 조건 | 실제 제거 단위와 증명 | 현재 경로 / earliest-safe frontier |
|---|---|---|
| G1: 동일 owner에 양립 불가한 exact binding | 한 선택 조합에서 같은 occurrence에 서로 다른 exact source realization을 요구함. suffix로 해결 불가 | Closure `7308–7350` prefix에서 이미 제거. 다른 owner끼리 geometry가 다르다는 이유로 제거하지 않음 |
| G2: 필수 source가 영구 부재 | clause의 AND-required source 중 하나가 **닫힌 source revision**에서 없음 | SupportRelations `262–283`: deletion epoch. upstream component가 닫히면 full publication 이전 semijoin 가능; 생성 중인 source 부재는 DEFER |
| G3: 모든 OR support clause 소실 | realization의 모든 대안이 무효여야 realization 제거 | 같은 worklist. 대안 하나가 사라졌다고 realization 전체를 삭제하지 않음 |
| G4: reaching writer 불만족 | writer별 지원은 OR, **모든 required writer는 AND**. writer 목록도 완결되어야 함 | SupportRelations `288–315`; CFG writer inventory가 닫힌 이후 |
| G5: relocation action 만료/불법 | exact current action authority, 활성 여부, privacy/anchor/pool compatibility | Closure `883–900`: **replacement 생성·bind 뒤** expired clause 제거. same-action의 inactive DIRECT_FOUT까지 삭제하지 않음 |
| G6: loop/SCC가 외부 근거 없음 | entry seed·backedge·모든 reaching definition·grounding 정보 완결 | CFG/SCC closure. 초기에 backedge가 없다는 이유로 삭제 금지. 반대로 support cycle끼리 가리킨다는 사실만으로 실행 가능 인정 금지 |
| G7: function 경계 불가능 | caller→formal, return→callsite, alias/value authority와 caller inventory | function boundary closure 뒤. 한 FED caller만 보고 모든 invocation을 확정하지 않음 |
| G8: tuple별 durable pool 충돌 | 선택된 exact anchors가 어떤 suffix로도 양립 불가 | 현재 DP product 후 검사 부분을 prefix로 이동할 후보. 단순 worker 수/FType 일치·불일치는 증명 아님 |

근거: `P/PlacementSupportRelations.java:189–337`; `P/PlacementRelationClosure.java:883–900,2835–3028,3051–3144,7308–7350`; `D/ExactPhysicalModel.java:486–618,1035–1069`.

**국소 support consistency는 전체 합법성의 충분조건이 아니다.** 예를 들어 이항 관계마다 지원 값이 있어도 세 변수의 순환 제약을 동시에 만족하지 못할 수 있다. 따라서 저렴한 semijoin으로 지울 수 있는 것만 지우고, 잔여 joint consistency는 기존 완전 검증/solver에 맡긴다. 모든 불법 조합을 생성 전에 알아내려는 전역 탐색은 원래 문제만큼 비쌀 수 있다.

## 3. Federated source에서 증분 전파하는가?

**같은 block의 초기 ordinary DAG에서는 producer-first인 것이 맞지만, 프로그램 전체가 source-first single-pass인 것은 아니다.**

현재 실제 구성:

1. statement-block의 정렬된 **root부터 입력으로 DFS하고 후위로 append**한다. 결과적으로 ordinary DAG의 입력이 consumer보다 먼저다. FED source뿐 아니라 local read·상수·metadata·함수/CFG 입력도 시작 정보다. (`P/PlacementGraphFingerprint.java:116–185`)
2. local seed → boundary structure → function relations → privacy → placement/feasibility → publication 순서로 closure한다. (`P/PlacementRelationClosure.java:404–417`)
3. 변경된 producer에서 consumer로 physical worklist를 돌리고, direct relation은 semantic dependency/support 및 alias invalidation을 이용한 SCC/wave를 사용한다. (`:3051–3144,5005–5059`)
4. 그러나 physical 호출마다 전체 owner/rule/consumer index 준비(`:5017–5048`), CFG pass의 전체 map/edge 준비(`:2872–2905`), direct wave의 전체 `LogicalBoundaryRealizations.close`(`:3107`), 여러 publication/relocation pass가 남아 있다.
5. source/action이 닫힌 한 epoch 안의 **삭제 전파**는 reverse-support worklist다. 새 epoch마다 인덱스를 다시 만드는 비용과 addition/replacement의 처리는 별개다. (`P/PlacementSupportRelations.java:189–337`)

### 수정할 방향

새 source-only 알고리즘으로 교체하지 말고 기존 scheduler를 강화한다.

```text
고정 topology / compiler facts를 한 번 준비
  → 이미 있는 component 순서에서 input-export delta가 있는 owner만 활성화
  → 동일 immutable snapshot + read-set이면 generation envelope 재사용
  → 정보가 확정된 local predicate를 product/state/action 생성 전에 적용
  → owner export가 실제 달라진 경우에만 consumer/alias dependents에 전파
  → additions/replacements가 끝난 닫힌 component/epoch에서 support deletion
  → SCC는 entry seed를 보존하고 내부 fixed point; topology 변경 시 schedule 갱신
  → 최종 authoritative validation 유지
```

이는 **새 구현 목표**이며 현재 전 과정이 이렇게 동작한다는 설명이 아니다. generation base와 action-bound/pruned view를 분리해야 이후 source가 추가되었을 때 후보를 되살릴 수 있다. alias invalidation edge를 모두 semantic SCC edge로 취급해서도 안 된다. 유효 결과가 K개면 Ω(K) 저장/방문 하한은 남는다.

## 4. 합법 search-space 보존: 무엇을 비교해야 하는가?

### 세 계층의 계약을 분리

1. **공통 후보 legality pruning:** 합법적인 실행 계획은 하나도 잃으면 안 된다. 비용이 크다는 이유로 합법 후보를 제거하지 않는다.
2. **DP 표현 축소:** 현재도 unsupported 값 삭제뿐 아니라 **합법적인 관찰 동등 값의 quotient**, singleton 대입, 변수 제거를 한다. ReducedSolver `471–482,619–710`은 factor 관찰값의 raw bits와 tie cost를 비교해 class를 만들며, `105–122`의 복원은 대표값을 반환한다. 이는 “불법이라 지웠다”가 아니다. 원래 후보 공간은 유지하고 표현 및 solver 목적에 따른 동치 계약으로 설명해야 한다.
3. **계획 선택:** FedFirst/AggLocal/DP-local은 하나의 계획을 선택하므로 모든 합법 조합을 끝까지 탐색하지 않는다. DP의 TIME/RESOURCE 종료도 불법성 판정이 아니다. 이 세 종류를 하나의 `pruned_count`로 합치지 않는다.

### 비교의 정확한 대상

동일 compiler/Oracle/runtime 제약 C에서 가능한 complete assignment의 집합을 `L_C`라고 하자. 새 공유 생성 결과가 `S_new`라면 다음 둘을 별도로 요구한다.

- **soundness:** 새 결과를 완성한 실행 계획은 모두 C를 만족한다.
- **completeness:** C를 만족하는 원래 계획은 새 결과에서도 표현·복원 가능하다.

기존 엔진이 같은 C를 사용하고 완전하다는 전제에서는 `Legal_C(S_old) = Legal_C(S_new)`를 양방향 비교한다. **raw tuple 수, 후보 개수, 최소 비용 하나, 최종 선택 계획 하나의 일치만으로는 충분하지 않다.** 다른 correctness 시대의 오래된 commit을 그대로 정답으로 삼지도 않는다.

표현 축약이 있는 경우 `project`는 단순히 대표 하나만 반환하는 함수가 아니라 **동치 class의 원래 witness 해석**을 포함해야 한다. shared legality를 검증할 때는 rule/emission/realization/support/anchor/action/value version을 보존하고, DP 최적화에서 허용한 동치와 섞지 않는다.

### 현재 검증의 실제 한계

- `EarlyPrivacyPruningLegalSpaceParityTest.java:54–173,197–224`는 네 corpus의 AVAILABLE 상태·row·emission·support·action golden과 repeatability를 검증한다. 모든 프로그램의 증명은 아니다.
- `CandidatePrivacyInputPruningTest.java:63–115`는 원래 Cartesian domain을 생성 tuple와 certified rejection으로 정확히 분할하는지 검사한다.
- `CandidateReceiptAssignmentCompletenessTest.java:36–40,105–131`는 **하나의 FedAll selected placement를 고정**하고, 현재 analysis에서 얻은 row domain에 대해 independent legality oracle을 적용한다. 논리 transient/function coupling을 제외한 fixture이며, 생성기에서 아예 빠진 후보까지 독립적으로 찾아내는 전역 oracle은 아니다.
- 지원 삭제의 full-pass/worklist differential과 별도 literal 8-plan fixture도 있으나 범위는 제한적이다. 이전 구현 보고서의 395 tests 중 알려진 GLM 오류 1건과 10 skip도 그대로 남아 있다. 이번 감사에서 테스트를 다시 실행하지 않았다.

### 계획에 추가할 증거

1. 동일 correctness baseline에서 old/new **전체 semantic-space** 비교. snapshot digest 실패 시 구체적인 missing/extra witness diff를 출력한다.
2. production survivor로 후보 universe를 만드는 대신 **독립된 bounded fixture/명시적 원래 domain**에서 모든 placement+receipt+action assignment를 열거하는 reference를 추가한다. 작은 DAG·branch·loop seed·function·다중 writer를 각각 다룬다.
3. 각 reject에 predicate ID, pruning unit, authority/read-set revision, original domain과 omitted region certificate를 연결한다. 큰 rejected suffix를 audit용으로 다시 열거하지 않는다.
4. 의도적으로 합법 source/action 하나를 누락시키는 mutation test가 반드시 실패해야 한다. old/new가 동일한 버그를 공유하는 blind spot을 줄인다.
5. 896 compile 성공은 통합 실행 가능성 증거이지 모든 후보의 완전성 증명이 아니다. 완전성 회귀와 별도 gate로 유지한다.

## 5. “가장 이른 단계”를 검증 가능한 계약으로 변경

고정된 함수 위치 하나를 earliest라고 선언하지 않는다. 같은 predicate도 ordinary DAG와 loop/function에서 정보가 확정되는 시점이 다르다.

**적용 준비 경계:** Hop-static facts → exact operand domains → complete tuple/capability → exact physical support/anchor → closed CFG/SCC/action epoch → DP model.

각 조건은 다음 정보를 갖는 작은 **문서/테스트용 조건표**로 관리한다. 별도 범용 pruning framework를 만드는 요구가 아니다.

```text
predicate_id, 제거 단위, 필요 사실/authority, 미확정 시 DEFER 여부,
현재 위치, 최초 안전 위치, revision invalidation, rejection witness,
긍정/반례 fixture, 피해야 할 downstream 작업(Oracle/product/allocation/freeze)
```

추가할 예정 테스트 `EarliestSafePruningStageTest`는 결과 일치뿐 아니라 다음을 검사한다.

- 정보를 가진 guard 뒤에 불법 tuple의 Oracle 호출/realization allocation이 실제로 발생하지 않는가?
- 정보가 없는 동안에는 보류하고, 정보를 얻은 다음 delta에서 재검사하는가?
- ordinary protected payload / metadata·handle / UNKNOWN→known / provisional→closed / zero-survivor / replacement action에 대해 가장 이른 **안전한** 경계가 각각 다른가?
- DP support pruning이 dense cell callback을 모두 실행한 뒤 진행되는 것을 “pre-freeze”로 잘못 계측하지 않는가?

전체 relation의 모든 불법 case를 가장 일찍 찾는 완전한 판정기를 만들지는 않는다. 증명 비용이 절감액보다 큰 predicate는 기존 단계에 남기고, 정확성 및 work counter를 근거로 결정한다.

## 6. DP-local의 실제 풀이 순서와 복잡도 평가

### 현재 순서

1. **physical model과 비용 구성.** decision별 alternative를 signature로 deduplicate/sort하고, hard factor를 종류별 고정 순서로 추가한다. cost surface에서도 ordinary factor freeze가 일부 발생한다. (`D/ExactPhysicalModel.java:223–260,330–423`; `D/ExactPhysicalCostModel.java:337–427`)
2. **producer-before-consumer greedy seed.** compiled-input + transient + function 관계를 넣은 decision graph에서 Kahn 순서, 동률은 기존 domain index, cycle 나머지는 domain 순서다. 각 변수의 모든 값을 검사해 **이미 닫힌 hard-factor 위반 수 → 닫힌 비용 → domain 순서**로 고른다. 미래 고차 제약은 아직 점수에 안 보일 수 있다. (`D/LocalPhysicalOptimizer.java:112–139,157–211`; `D/LocalCategoricalOptimizer.java:830–865,1148–1154`)
3. **첫 위반을 기준으로 regional repair.** 완성 seed의 hard factor를 순서대로 검사해 첫 위반 component를 잡고, 인접 hard-factor response layer를 한 겹 더한다. block을 exact하게 풀고 실패하면 영역을 확대한다. (`D/LocalCategoricalOptimizer.java:665–710,867–937`)
4. **첫 다변수 repair에서 whole-root 준비가 발생할 수 있음.** shared preparation이 전체 encoded problem을 먼저 freeze → unary/binary support AC → observational quotient → rebuild한다. (`D/SharedRegionalPreparation.java:74–100`; `D/ExactPhysicalReducedSolver.java:432–532`)
5. **boundary 고정 후 해당 block을 다시 compact하고 exact VE.** 필요한 factor를 가져오되 auxiliary 연결을 따라가며, original decision의 바깥 값은 고정한다. conditional compaction과 singleton 제거는 이미 적용 중이다. (`D/SharedRegionalPreparation.java:112–217`; `D/LocalCategoricalOptimizer.java:574–605`)
6. **seed 완성 후 incremental regional DP.** 원래 seed를 reduced root로 lift/검증하고 boundary message를 만든다. 8M initial-slot 제한을 초과하면 feasible incumbent를 보존하며 `RESOURCE_INITIAL` 종료. 이후 작은 slots/work의 merge를 우선 검토하고, cheapest-slot tier의 최대 16개 중 conflict-gain/work로 선택한다. EXACT/목표 gap/10초/자원 조건에 따라 종료한다. (`D/IncrementalRegionalOptimizer.java:80–96,119–221,225–310`; `D/IncrementalRegionalSeed.java:32–85,103–171`)
7. **decode → candidate/relocation receipt → projection/normalization → program emission.** 10초 incremental timer는 앞선 model/seed/whole-root 비용을 제한하지 않는다. 바깥 coordinator의 60초 watchdog이 전체를 제한한다. (`D/LocalPhysicalOptimizer.java:67–94`; `D/ExactPhysicalSelection.java:90–147`)

### 이미 복잡도를 줄이는 순서는 있는가?

있다. exact VE는 min-fill / min-separator-cells / min-elimination-assignments / min-degree를 **symbolic portfolio**로 평가하고, 최대 중간 cells → 총 cells → 최대/총 assignment work → width 등을 기준으로 고른다. 따라서 단순 “source부터 VE하면 빠르다” 또는 “min-fill을 새로 쓰자”는 진단은 맞지 않는다. (`D/ExactCategoricalSolver.java:897–975`; `D/ExactEliminationOrderPolicy.java:32–94`)

변수 v의 domain 크기가 d_v, 제거 후 남는 separator가 S_v일 때 대략:

```text
dense factor 준비 작업  ≈ Σ_f ∏_(v∈scope(f)) d_v
VE 대입 작업           ≈ Σ_v d_v × ∏_(u∈S_v) d_u
중간 table cells       ≈ Σ_v ∏_(u∈S_v) d_u
```

작업량을 크게 줄이려면 **domain의 확실한 불법 값, 불필요한 factor 축, separator 결합**을 줄여야 한다. Map/List 재사용만으로 지수항은 사라지지 않는다. 반대로 이미 작은 영역에 전역 consistency 탐색을 추가하면 더 느릴 수 있다.

### 기존 R2 계획의 중요한 오류와 수정

**factor truth/objective가 같고 logical scope를 남겼다고 DP-local의 탐색 정책까지 같은 것은 아니다.**

- 고차 factor를 여러 개로 나누면 seed에서 더 일찍 닫히고 위반 1개가 여러 개로 집계될 수 있다.
- factor ordinal/count가 바뀌면 첫 위반과 repair component가 바뀐다.
- 불필요 축 제거·singleton 선대입도 factor가 점수에 반영되는 시점을 바꿀 수 있다.
- encoded root를 바꾸면 message leaf, initialSlots, cheapest-slot tier와 merge 순서가 바뀐다.
- 심지어 전역적으로 불법인 domain 값을 없애는 것도, 부분 할당 상태의 greedy seed에서는 중간 선택을 바꿀 수 있다.

따라서 수정 계획은 다음처럼 검증을 나눈다.

**A. 동작 보존형 최적화:** cache/index/할당 제거와 같은 표현·domain 유지 변경은 원래 ordinal/scope/closure 시점/위반 단위/정밀 비용 순서를 유지하고 deterministic seed·repair·선택 parity를 요구한다. exact repair만 내부적으로 축약한다면 기존 논리 factor를 읽는 **policy view**와 solver 내부 표현의 대응을 검증한다. 이를 위해 거대한 새 이중 모델 프레임워크를 먼저 만들지는 않는다.

**B. 합법 공간 보존형 구조 최적화:** 불법 domain 값 삭제, encoded scope 축소·factorization·pre-freeze support로 root 표현까지 바꾸면 DP-local의 fixed-budget 탐색 경로/최종 계획은 달라질 수 있다. 이를 허용하는 별도 commit/검증 항목으로 명시한다. shared legal set, 모든 대응 assignment의 canonical cost, exact 지역 문제의 최적값/명시적 tie, `lower ≤ optimum ≤ upper`, cap 단위·timer 경계가 필수다. TIME/TARGET/RESOURCE 결과의 assignment·receipt가 항상 같다는 주장은 제거한다.

이것은 합법 후보 삭제나 새 임의 heuristic 도입 허가가 아니다. 기존 선택 기준은 유지하되 **표현 변경으로 생기는 방문 순서 차이**를 숨기지 않는 것이다. 작은 exhaustive 문제에서 bound/feasibility를 확인하고, 기존에 완료되는 control은 같은 조건에서 incumbent 비용 악화를 별도 gate로 조사한다. 비용 악화를 timeout 해소라는 이유로 묵인하지 않는다. FedFirst/AggLocal의 정책 변경은 이번 범위에 포함하지 않는다.

## 7. 실행 계획의 개정 내용

1. **R0 강화:** L1–L8/G1–G8의 현재/earliest-safe 위치, 삭제 단위, authority, DEFER, certificate를 표로 고정. 기존 test oracle의 범위를 명시하고 독립 작은 전체-plan universe·mutation 검사 추가.
2. **R1 분리:** DP illegal-product/pool-prefix와 allocation 제거를 별도 변경으로 구분. ALL_REJECTED terminal certificate와 빈-emission profile 재계산은 새 조사 항목; safe proof가 없으면 원래 보수 경로 유지.
3. **R3a 앞당김:** 같은 immutable snapshot의 준비/index/profile 재사용을 고위험 global factor 재작성보다 먼저 적용 가능하게 함. 아직 cross-revision cache는 아님.
4. **R2 검증 계약 교정:** 동작 보존형과 합법 공간 보존형 구조 변경의 acceptance를 분리. shape가 달라진 anytime DP에 불가능한 final-assignment parity를 요구하지 않음.
5. **R3b 구체화:** source-only 재작성 대신 기존 SCC/dirty scheduler에서 static topology와 dynamic exports를 분리, read-set delta/생성 전 no-op gate, 닫힌 component의 semijoin, replacement 이후 deletion을 구현 대상으로 지정.
6. **R6 유지:** 60초 고정·동일 엔진 896 compile gate·그 후 runtime. **전체 실행 성공과 search-space 완전성은 서로 다른 검증**이라는 문구 추가.

## 8. 증거 수준과 남은 한계

- **높은 확신 / 코드 직접 확인:** local mask의 위치, zero-survivor 우회, profile 순서, worklist와 전체 재구축의 공존, DP 처리 순서, quotient의 합법 값 병합, factorization이 seed/queue에 영향을 주는 구조.
- **추론 / 추가 계측 필요:** 어떤 개선이 현재 workload에서 가장 많은 초를 줄이는가. 이전 32 timeout은 planner 내부 진입만 증명하며 특정 함수의 현재 지배 비중은 알려주지 않는다.
- **아직 증명하지 않은 것:** 모든 프로그램의 완전성, 새로운 pruning의 성능·안전성, 896 compile 성공, runtime correctness. 이번 감사는 계획과 판정의 정확성을 높인 작업이지 구현 완료 보고가 아니다.
