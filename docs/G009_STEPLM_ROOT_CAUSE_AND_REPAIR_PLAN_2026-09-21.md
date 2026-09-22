# G009 StepLM 원인 분석 및 안전한 정상화 계획

- 작성일: 2026-09-21. **이번 작업은 기존 소스·로그 분석과 계획 작성만 수행했다. 구현·새 benchmark 실행은 하지 않았다.**
- 대상: 보호 입력 P2P2D, worker=4, StepLM planning-only. 공통 후보 공간·privacy·runtime authority를 보존하면서 DP-local → FedFirst → AggLocal → DP-global을 정상화한다.
- 현재 HEAD: `fe000959c48ffa1172399e49124d082fe42d0c6d` + 기존 미커밋 변경. 현재 JAR SHA-256: `805095277f03d4cda0a85278be64c09072c118696f908f00e1f03f62afbf3f28`.
- 관련 문서: [전체 실행 결과](G009_FEDALL_HEURISTIC_STREAMING_EXECUTION_STATUS_2026-09-21.md), [경량 selector 계획](G009_FEDALL_HEURISTIC_STREAMING_SINGLE_PASS_DESIGN_2026-09-21.md).
- 아래 소스 행 번호는 작성 당시 작업 트리 기준이다. **증거**, **추론**, **미확인**을 구별한다.

## 1. 결론: 어디가 문제인가

**현재 직접 원인은 selector가 아니라 공통 builder의 실행 가능한 배치 증명 누락이다.**

StepLM은 선택한 feature들을 `X_global`에 누적한다. 초기 열 선택과 루프의 두 `cbind` 갱신에서 생성된 세 TWrite 모두, 최종 재생에 필요한 worker-pool witness가 없는 staging FED 후보만 남는다. 따라서 TRead `X_global`에 대해 모든 reaching definition을 만족하는 FED/FOUT 관계가 게시되지 않는다. 남은 CP/LOUT 경로는 보호된 원본 데이터의 coordinator 반출을 요구하므로 privacy가 거절한다. 최종 메시지가 privacy 오류라는 이유만으로 **“실제로 합법적 계획이 없다”**고 해석하면 안 된다. [E1–E3, S2–S4]

| 순위 | 설명 | 판정/신뢰도 | 근거 |
|---|---|---|---|
| 1 | 세 reaching TWrite의 executable native witness가 없어 보호된 TRead의 domain이 비어짐 | 증거 / 높음 | E1–E3, S2–S4 |
| 2 | native continuity의 literal-only 열 slice 조건이 동적 feature 선택을 표현하지 못함 | 코드상 제한은 확정; 이 제한만 고치면 전체 해결된다는 주장은 부정 | S5–S6, E4 |
| 3 | slice 증명을 확장하면 CFG/direct proof/physical rebuild의 결합 폐쇄가 수렴하지 않음 | 비수렴은 확정; 반복되는 정확한 row/권한 변경 원인은 미확인 | E4, S7 |
| 4 | StepLM 자체가 privacy-safe 실행 불가능 | 현재 증거로 입증되지 않음 | S8–S10의 native 실행 경로와 §3의 국소 불변식 |
| 5 | FedFirst/AggLocal 정책이나 DP 목적함수가 원인 | 이번 실패의 직접 원인 아님 | 네 planner가 같은 공통 builder 위치에서 실패, E1 |

**명확히 분리할 두 문제:** (A) 동적 열 선택에 대한 정확한 배치 증명 계약, (B) 그 증명이 생겼을 때 CFG 폐쇄가 이를 안정적으로 유지하는 계약. A만 느슨하게 바꾸고 B의 비수렴을 숨기는 수정은 채택하지 않는다.

## 2. 실제 실패 사슬

### 2.1 프로그램 구조

`scripts/builtin/steplm.dml`의 관련 부분은 다음과 같다. [S1]

```text
X_orig = X                                  # 72
for i in 1:m_orig: evaluate X_orig[, i]      # 97–100
column_best = data-dependent feature index   # 104
if column_best != 0:
    X_global = X_orig[, column_best]         # 122: 진입 정의
    while continue:
        for i in 1:m_orig:
            Xi = cbind(X_global, X_orig[,i]) # 132: 현재 오류의 TRead
            evaluate Xi                     # 133
        if new feature:
            if all features selected:
                X_global = cbind(...)       # 160: 갱신 정의
            else:
                X_global = cbind(...)       # 163: 갱신 정의
```

현재 CFG는 132행 TRead에 대해 122·160·163행 TWrite를 reaching definitions로 연결한다. 81행의 초기 local zero 행렬은 이 read의 관측된 세 정의에 포함되지 않는다. 따라서 **이번 오류를 local-zero 초기화 충돌로 단정하거나 GLM 특수화를 그대로 복제하지 않는다.** 160행 경로가 이후 loop iteration에 실제로 도달하는지는 별도 CFG 분석 대상이며, 이번 수정에서 편의상 그 edge를 삭제하지 않는다. [E2, S1]

### 2.2 배치 state와 실행 증명은 다르다

```text
TWrite에 FED/FOUT/ROW state 존재
    ≠ 해당 row의 executable worker-pool witness 존재
    ≠ 모든 reaching definition과 호환되는 TRead witness 존재
```

- E3의 세 write에는 `NATIVE_LINEAGE/[false]`인 FED row가 있다. 여기서 `false`는 해당 진단이 출력한 **worker-pool witness 부재**다. 단순 `AVAILABLE` 또는 `ROW` 표지만으로 실행 가능하다고 판정할 수 없다.
- `sourceFederatedRealizations`/`sourceRealizations`는 `executableSourceRealization`을 적용하며, native support clause에 worker-pool witness가 없는 staging row를 실행 source로 사용하지 않는다. 이 검사는 제거할 대상이 아니다. [S2]
- `exactTransientReplay`는 각 reader 대안마다 **모든** reaching definition의 정확한 candidate support를 요구한다. 하나의 진입 seed만 맞는다고 backedge까지 증명되는 것은 아니다. [S3]
- privacy closure는 origin residency가 필요한 데이터의 CP/LOUT를 금지한다. 최종 publication도 필수 occurrence의 빈 실행 domain을 실패시킨다. 따라서 privacy 예외는 원인을 드러낸 최종 결과이지 완화해야 할 규칙이 아니다. [S4]

### 2.3 동적 열 선택의 구체적인 표현 누락

`exactFullRowColumnSlice`는 `isAllRows()`뿐 아니라 row bound 두 개와 column bound 두 개가 모두 `LiteralOp`일 것을 요구한다. 반면 `IndexingOp.isAllRows()` 자체는 `1:nrow(같은 입력)`도 인식한다. 즉 알려진 전체 행 범위를 다시 literal-only로 좁히고, 데이터에 따라 선택되는 `column_best`/loop index `i`를 지원하지 않는다. [S5–S6]

이것은 **native 배치 연속성 증명의 제한**이지 “FED rightIndex 연산 자체가 없다”는 뜻이 아니다. 런타임에는 rightIndex와 aligned ROW cbind 경로가 이미 있다. [S8–S10]

## 3. 수학적으로 보존해야 할 것은 ‘열 수’가 아니라 ‘행 배치’다

보호 행렬 X가 worker j에 다음 범위로 배치되어 있다고 하자.

`M_j(X) = (worker_j, [r_j, r_(j+1)) × [0,d))`

가정: 행 구간이 서로 겹치지 않는 유효한 다중 ROW partition이고 전체 행을 유지하며, 선택 열 범위는 실행 시 유효하고 비어 있지 않다. 그러면 열 선택 `C = X[:,a:b]`의 결과는

`M_j(C) = (worker_j, [r_j, r_(j+1)) × [0,b-a+1))`.

또한 A와 C가 같은 worker별 행 구간에 정렬되어 있다면

`M_j(cbind(A,C)) = (worker_j, [r_j,r_(j+1)) × [0,ncol(A)+ncol(C)))`.

따라서 불변식은 `I(V) = (ROW, worker↔row-interval의 정확한 대응)`이다. 초기 `X_global`이 I를 만족하고 각 cbind 갱신이 I를 보존하면, 반복 횟수나 선택 feature의 값·개수에 관계없이 모든 **유효한 실행**의 `X_global`이 I를 만족한다. 이 국소 귀납은 초기 열 선택과 두 갱신을 모두 포함해야 한다. [S1, S8–S10]

중요한 제한:

1. `ncol(X_global)`은 1에서 증가한다. 전체 2차원 geometry가 영원히 같다는 증명을 만들면 틀리다.
2. 같은 worker 집합만으로 충분하지 않다. worker별 행 구간까지 일치해야 aligned cbind 권한이 있다.
3. 기존 `NativePoolWitness`는 partition axis와 endpoint를 이미 분리한다. `samePhysicalLayout`은 양 축 전체 비교이고 `samePhysicalWorkerPool`은 ROW/COL의 분할 축 비교다. 새 범용 symbolic engine부터 만들기보다 기존 구분을 올바르게 적용한다. 두 비교 함수를 전역적으로 치환하지 않는다. [S11]
4. 이 귀납은 **해당 연산 사슬의 배치 가능성**을 설명할 뿐, `lm/lmCG`, 함수 경계, privacy, 최종 lowering까지 포함한 전체 StepLM 합법성 증명은 아니다.
5. 열 경계 유효성은 별도 의무다. 현재 `rightIndexing`은 global bound를 먼저 명시적으로 검증하지 않고 `filter`부터 호출한다. “런타임이 반드시 잘못된 index를 거절한다”는 가정으로 literal 조건만 삭제하지 않는다. 유효/범위 밖/빈 범위의 실제 계약을 먼저 작은 테스트로 확인한다. [S8]

**루프를 flatten하거나 2,100개 feature 조합을 전부 펼칠 필요는 없다.** script의 실행 반복량과 planner의 정적 proof 고정점 계산은 다른 문제다. 현재 관측은 selector 조합 탐색 폭발보다 witness 누락·폐쇄 비수렴을 가리킨다. [S1, E1, E4]

## 4. 왜 앞선 동적 열 수정만으로 해결되지 않았는가

E4에서는 literal column 제한 확장 후 `CFG transient candidate closure did not converge`가 발생했다. trace의 node/fact/logical-input hash가 두 패턴으로 반복된다. 일부 replay 단계의 `changed=[]`도 보인다.

다만 **hash 반복만으로 정확한 객체 관계의 2-cycle을 증명할 수는 없다.** 기존 trace는 한 pass의 입력과 첫 replay 결과를 보여줄 뿐, physical rebuild·direct grounding·마지막 relation replay 각각의 차이를 보여주지 않는다. `changed=[]`도 전체 pass가 안정됐다는 뜻이 아니다. [S7]

코드상 점검할 비단조 경계는 다음 세 가지다. 아래는 **원인 후보**이며 이번 분석에서 한 줄로 확정된 버그 위치가 아니다.

- replay 실패 시 직전 reader 관계를 baseline node/facts로 교체하고 기존 logical edges를 제거하는 경계. [S7:3550–3578]
- 물리 rebuild가 base emission을 복구하면서 candidate별 native binding을 다시 만들어야 하는 경계. [S7:2690–2748]
- 한 번만 쓰는 loop seed 이후 모든 정의를 검사하는 전환, 그리고 새 source identity와 이전 reader support가 맞물리는 경계. [S7:3628–3674]

이 때문에 첫 작업은 **작은 실패 fixture에서 최초로 증거가 사라지거나 바뀌는 transfer를 식별하는 것**이다. 곧바로 공통 그래프 전체를 SCC 엔진으로 교체하거나 이전/현재 후보 집합을 무조건 합치지 않는다.

## 5. 구현 계획과 게이트

### S0 — 작은 재현과 최초 손실 경계 확정

수정 전 작은 protected fixture를 만든다. `NativePlacementContinuityTest`, `TransientPlacementAlternativesTest`, `PublicationSupportClosureTest`의 패턴을 재사용한다.

- 단계적으로 구성: literal full-row slice → 동적 column slice → aligned cbind → 초기 정의+단일 backedge → 두 branch TWrite+read → nested loop/function.
- worker=4의 서로 다른 실제 row ranges, `PRIVATE_AGGREGATE`, 변화하는 열 너비를 유지한다. PUBLIC fixture로 대체하지 않는다.
- 대상 SCC/reader에만 단계별 delta를 저장한다: occurrence, rule/emission/realization identity, support owner, pool witness/정확도, reaching definition, node domain, pending physical ordinal.
- 첫 replay / physical rebuild / direct grounding / final replay를 구별한다. 동일 상태 여부는 hash 후보 검출 후 정확한 구조 동등성으로 확인한다. 전체 workload per-row trace·새 계측 체계는 만들지 않는다.
- **게이트:** 최소 fixture가 기존 실패 또는 정확한 cycle을 재현하고, 어느 transfer가 어떤 살아 있는 support를 잃거나 교체하는지 assertion으로 고정한다. 단순 에러 문자열 대조만으로 통과시키지 않는다.

### S1 — 전체 행 동적 열 선택의 정확한 native 계약

주요 파일: `NativePlacementContinuity.java` S5/S11, 필요 시 indexing oracle/rule, `IndexingFEDInstruction.java` S8. production 변경 전에 테스트를 작성한다.

- `1:nrow(same input)`을 포함해 전체 행 여부를 기존 `isAllRows`와 exact row witness로 증명한다.
- 유효한 동적 열 선택은 열 너비만 바꾸고 worker↔row interval을 보존함을 검증한다. witness를 원래 d열의 **완전한 출력 map**이라고 오표현하지 않는다.
- invalid/empty column 범위, unknown/partial row 범위, single partition의 FULL 재분류, COL/겹치는 BROADCAST를 음성 또는 별도 타입 사례로 확인한다.
- runtime 검증 부족이 실제로 확인되면 해당 연산의 의미론을 고친다. planner의 privacy/실행 가능성 gate를 우회하지 않는다. 학습 runtime은 실행하지 않고 작은 연산 계약 테스트만 사용한다.
- **게이트:** legal ROW case는 정확한 row-axis proof, 불일치 case는 거절 또는 실제 runtime type으로 모델링. 기존 literal·transpose·FULL 계약 회귀 없음.

### S2 — loop-carried proof를 안정적으로 닫기

주요 파일: `NeutralPlacementGraphBuilder.java` S3/S7, `NativePlacementContinuity.java`의 기존 candidate proof/SCC 구현.

S0 결과에 따라 최소 변경을 선택한다.

1. **stale support 또는 midpoint rebuild 문제이면:** 정확한 dirty dependency와 재생성 순서를 고친다. no-change 물리 row를 재사용하고, 변경된 source reference만 다시 증명한다. 의미가 바뀐 reference를 동등한 pool이라는 이유로 임의 remap하지 않는다.
2. **실제 순환 bootstrap 문제이면:** 기존 SCC proof에 loop 불변식을 연결한다. 진입 seed는 임시 가정으로만 사용하고 모든 backedge의 transfer-preservation 의무를 같은 검증 단위에서 완료한 후 게시한다. node 수준의 “외부 seed 하나 있음”만으로 모든 row를 승인하지 않는다.

정확한 candidate/AND-OR 지원 관계, shared source 선택의 양립성, alias·value version·authority를 유지한다. 모든 reaching definition에 대해 지원이 있어야 하며, 서로 다른 가능한 pool/row 대안은 공통 공간에 보존한다. 이 4-worker witness를 유일한 허용 pool로 hardcode하지 않는다.

- **게이트:** grounded loop는 정상 종료하고, 외부 근거 없는 self-cycle·불일치 backedge·권한 충돌은 거절한다. 한 차례 완성된 composed transfer를 다시 적용해 동일 nodes/facts/edges를 얻는다. 오류 시 부분 proof/receipt는 게시하지 않는다.
- 전체 closure의 단조성을 먼저 증명하지 않고 `old ∪ new`를 적용하지 않는다. 추가/삭제/권한 교체가 모두 존재하기 때문이다.

### S3 — privacy 및 publication 고정점 통합

주요 파일: `NeutralPlacementGraphBuilder.java:850–1040, 1290–1320`, 기존 publication/support 테스트.

- privacy가 source row를 제거하면 그 row를 참조하는 proof를 무효화하고 살아남은 대안에서 재증명한다.
- provisional seed 또는 물리 template 복구가 이미 거절된 권한을 되살리지 않도록 단계 경계를 검증한다.
- source identity가 바뀌면 관련 native/CFG cache를 무효화한다. 완전한 equality를 비용 때문에 생략하거나 iteration limit 도달을 정상 수렴으로 바꾸지 않는다.
- **게이트:** 작은 fixture에서 cached/incremental 결과와 fresh full recomputation의 decoded 후보·지원 관계가 같고 selector 호출 전후 공통 공간이 불변이다. 필요 시 진단용 실행 경로로 대조하며 새 production 옵션을 선행 요구하지 않는다.

### S4 — StepLM 정상 planning 및 전체 회귀

- 기존 single-worker `StepLmPrivateAggregatePlanningContractTest`는 별도 문제를 구분한다. 현재/C0에서 관측된 `lmCG` protected TRead 실패를 worker4 `X_global` 문제와 동일하다고 간주하지 않는다. worker4 최소 fixture와 실제 StepLM을 먼저 해결한다.
- 작은 계약 테스트와 `mvn -q -DskipTests package`, diff check 후, 공식 Docker launcher로 **StepLM DP-local 1회**. 실패하면 그 로그로 작은 fixture를 보강하고 같은 실패를 네 planner에서 반복하지 않는다.
- DP-local 정상 planning/lowering 후 같은 후보 JAR로 FedFirst·AggLocal·DP-global 각 1회. 실패 분류: builder 증명 부족 / runtime 미지원 / selector 탐색 / lowering·authority 문제를 구별한다.
- 최종 JAR가 고정되면 기존 ML10+P1/P2+SliceLine 2개 **56조건 각 1회**. 같은 최종 JAR·입력·설정의 직전 성공 canary만 해당 행에 재사용한다. 이전 `805095…`의 52건을 새 JAR 성공으로 대신 세지 않는다.
- 완료 receipt는 `success=true`, `runtime_executed=false`, `execution_seconds=0`, worker=4, 올바른 planner enum 및 정상 planning/lowering을 확인한다.
- GLM 네 planner 전체 초기 planning <60초를 회귀 목표로 유지한다. StepLM은 기존 planning timeout 180초 내 정상 종료가 우선 기준이며 별도 60초 목표를 새로 약속하지 않는다. 반복 pilot/ABBA는 하지 않고 단회 미세 차이를 통계적 개선으로 주장하지 않는다.

## 6. 완료 기준과 금지 사항

- [ ] 동적 열 slice·aligned cbind·변하는 열 너비·두 backedge가 포함된 protected fixture가 통과한다.
- [ ] 122/160/163 모든 reaching definition이 정확하고 공동으로 양립 가능한 reader support를 제공한다.
- [ ] grounded loop는 수렴하고, ungrounded/misaligned/privacy-denied 반례는 실패한다.
- [ ] 최종 publication에 staging-only support, dangling reference, provisional seed가 없다.
- [ ] common candidate 공간을 selector 목적함수로 축소하지 않고 DP exact 경로와 정책 경로의 계약을 보존한다.
- [ ] 동일 최종 JAR 56/56 정상 planning-only receipt, GLM <60초, 미실행·실패·skip 별도 보고.
- [ ] 변경 파일·테스트·JAR/입력/설정 해시·선택 계약·남은 문제를 실행 보고서에 기록한다.

금지: privacy 완화, CP→FOUT/recompile/TR-TW 예외, worker pool 강제 주입, loop backedge 임의 삭제, script의 feature 수/반복 수 축소, selector/runtime fallback, hash 동률로 수렴 판정, timeout을 infeasible 또는 성공으로 재분류. 이번 요청은 계획이므로 commit/push도 수행하지 않는다.

## 7. 근거 색인 및 재현 자료

소스 prefix `P = src/main/java/org/apache/sysds/hops/fedplanner/placement/`.

| ID | 근거 |
|---|---|
| S1 | `scripts/builtin/steplm.dml:72–104, 116–174, 180–196` — feature 선택·세 write·cbind·함수 호출 |
| S2 | `P/NeutralPlacementGraphBuilder.java:3922–3958` — executable source filtering |
| S3 | 같은 파일 `3730–3866` — 모든 reaching definition별 replay compatibility |
| S4 | 같은 파일 `1025–1038, 1290–1320` — privacy 및 빈 publication domain 실패 |
| S5 | `P/NativePlacementContinuity.java:1917–1955` — literal-only full-row slice, ROW type 유지 조건 |
| S6 | `src/main/java/org/apache/sysds/hops/IndexingOp.java:437–443` — symbolic NROW 전체 행 인식 |
| S7 | `P/NeutralPlacementGraphBuilder.java:2614–2755, 3518–3674` — composed closure·baseline 복원·loop seed |
| S8 | `src/main/java/org/apache/sysds/runtime/instructions/fed/IndexingFEDInstruction.java:90–95, 176–227, 292–303` — dynamic bounds와 native map 변환 |
| S9 | `src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederationMap.java:754–780` — filter/type 분류 |
| S10 | `src/main/java/org/apache/sysds/runtime/instructions/fed/AppendFEDInstruction.java:140–164` — aligned ROW cbind |
| S11 | `P/NativePlacementContinuity.java:2097–2169`, `P/PlacementIdentity.java:564–599` — exact partition axis와 전체 geometry 구분 |

아래 E1–E4는 **기존 실행 자료를 이번에 다시 읽은 것**이며 새 실행이 아니다.

- E1: `/grid/3/cofee-lm-sweep-mchoi-20260914/g009policy-final805b/status.tsv`의 StepLM 37–40번 실행. coordinator 로그 root는 `/home/mchoi/ml-p1p2-sliceline-fedplanning-w4-once-20260921/experiments/results/fed4/`; 각 config 하위 `steplm_dataset-P2P2D_coordinator_<conf>_g009policy_final805_<37..40>_steplm_P2P2D_<conf>_20260921_lan_coordinator1.log:5`에 같은 오류가 있다.
- E2: 같은 로그 root의 `mkl-single-pass/steplm_dataset-P2P2D_coordinator_mkl-single-pass_g009policy_steplm_source_probe_20260921_lan_coordinator1.log:27` — 세 source의 `federatedRefs=[]`.
- E3: 같은 디렉터리 `steplm_dataset-P2P2D_coordinator_mkl-single-pass_g009policy_steplm_facts_probe_20260921_lan_coordinator1.log:25–27` — staging native row.
- E4: 같은 디렉터리 `steplm_dataset-P2P2D_coordinator_mkl-single-pass_g009policy_steplm_dynamiccol_probe_4f7ab5_20260921_lan_coordinator1.log:5` — 비수렴과 반복 hash trace. 별도 진단 JAR이므로 최종 성능 표본에 포함하지 않는다.
- 공식 재현 환경/명령은 `/grid/3/cofee-lm-sweep-mchoi-20260914/g009policy-run-fixedpoint-805.sh`에 있다. **이 스크립트를 그대로 실행하면 56건을 돌리므로 S0/S4의 단일 StepLM 실행에 그대로 사용하지 않는다.** 같은 export/manifest 설정에서 launcher만 아래처럼 호출한다.

```bash
# 고정된 campaign 환경·JAR·입력/설정 manifest를 먼저 확인한 이후에만:
./experiments/run_LAN_docker.sh --planning-validation --planning-only \
  --skip-net-check --workers 4 --dataset P2P2D --net-profile lan \
  --conf mkl-cost --salg steplm --continue-on-failure 0
```

현재 전체 성공은 52/56이며, 이 계획 문서는 그 결과를 변경하지 않는다. **직접 실패 사슬은 확인됐고, 동적 열 확장 후 최초로 흔들리는 정확한 proof transfer는 S0에서 추가로 규명해야 한다.**
