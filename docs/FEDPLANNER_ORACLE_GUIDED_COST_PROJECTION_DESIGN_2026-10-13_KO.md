# Memo 비용 조합 폭발과 Oracle 기반 생성·비용 투영 설계

작성일: 2026-10-13. 대상: v9a 감사의 **문제 4 / §3.5 native 비용 생성 시 source 환경 조합 증가**. 상태: 원인 분석 및 후속 구현 설계. 이번 작업은 문서 작성이며 production/test 코드를 변경하지 않았다.

**해결의 핵심은 source 조합을 만든 다음 합치는 것이 아니라, Oracle과 프로그램 조건으로 허용된 관계를 먼저 정하고, 비용에 필요한 문맥만 남긴 뒤 비용 primitive를 만드는 것이다.** Source 개수가 늘어났다는 이유만으로 같은 kernel의 비용 객체가 늘어나서는 안 된다. 동시에 source의 privacy·가용성·정확한 정의·공유 전송 관계까지 삭제해서는 안 된다.

이 문서는 아래 세 상태를 구분한다. **[확인]**은 동결 소스 또는 누적 기록이 보여 주는 사실, **[추론]**은 그 사실에서 도출한 영향, **[제안]**은 앞으로 구현·검증할 계약이다. 후속 작업 트리의 주된 읽기 기준은 **2026-10-13 20:52:42 UTC**이며 관련 파일 사본과 hash를 [근거 manifest][capture]에 보존했다. 검토 중 추가한 파일은 별도 읽기 시각을 기록했다. 이 사본은 통합 릴리스나 새 테스트 통과 증거가 아니다.

## 1. 무엇이 문제이고, 어디까지 해결되었는가

### 1.1 v9a의 문제

**[확인]** RuleMemo가 저장하는 native rule은 source identity를 포함하지 않는다. 그러나 v9a `MemoProgramModel.nativeCostTerms()`는 occurrence의 모든 input binding을 모아 `visitActive()`에 넘긴다. 이 경로는 caller 정상 진행과 activation을 처리한 뒤 `MemoBindingResolver.visitJoint()`로 입력들을 공동 해석한다. 방문한 환경마다 owner의 rule 수만큼 `double[]`을 만들고 native 비용을 계산한다. [v9a 비용 생성][v9cost], [v9a joint 호출][v9joint], [resolver 열거][v9enum].

```text
source-free RuleMemo
    ↓
nativeCostTerms(occurrence)
    → 모든 input binding 수집
    → caller/activation 및 input source 환경 공동 해석
    → 환경마다 shape view, frequency, probability 계산
    → 환경마다 double[owner의 rule 수] 생성
    → unary CostTerm 등록
```

저장 위치를 RuleMemo에서 CostTerm으로 옮겼을 뿐, 비용과 무관한 source 분기를 먼저 펼치는 작업이 남는다. 여기서 unary는 **optimizer 선택변수 하나에 의존한다**는 뜻이다. Unary term이 적게 생긴다거나 source 환경이 하나라는 뜻이 아니다.

`A`의 source가 100개이고 `B`의 source가 100개이면, 서로 독립적이며 모든 쌍이 도달 가능한 경우 최대 10,000개 환경이 생길 수 있다. Source 조합마다 term 하나, term마다 rule 수 `R`의 비용 값이 있다면 값 저장량만 `10,000 × R`이다. 객체·guard map·shape view 등의 부가 비용은 별도다.

**단, 100 × 100은 설명용 최악 사례이지 GLM에서 직접 측정한 source 수가 아니다.** v9a resolver는 공유 guard와 loop 결정을 함께 해석하므로 실제로 불가능한 모든 쌍을 무조건 방문한다고 단정할 수 없다. 정확한 문제는 **도달 가능한 source 환경이라도 native 비용에 불필요하면 방문하지 않아야 하는데, 그 환경까지 비용 생성에 사용한다**는 것이다.

### 1.2 누적 구현·진단 이력

| 시점 | 확인된 내용 | 해석의 한계 |
|---|---|---|
| v6/v7 무렵 GLM 진단 | 180초 제한 JFR의 표본 stack이 `nativeCostTerms → MemoBindingResolver.enumerate`에 집중. 70초 시점 unary term/배열 35.2만 개 이상. `registerMovementCosts` 미도달 | 누적 진단 기록이다. 이번 문서 작업에서 재실행하지 않았다. 당시 병목을 solver 탐색이나 Oracle의 비용 산술로 설명하면 틀린다 |
| v9a 동결 | Snapshot purpose에 GLM/builtin WIP 제외를 명시. 위 source 환경 비용 생성 경로 유지 | LogReg Docker 성공이 GLM 병목 해결을 뜻하지 않는다 |
| v9a 이후 R1 | Native 비용에서 source-only 조합 제거. Activation과 shape 관련 guard로 투영. GLM 모델 구성 약 2.8~2.96초라는 host model-only 진단 기록 | Docker 시간·전체 planning 시간·최적해 획득 시간이 아니다. 180초와 직접 나눠 speedup을 주장하지 않는다 |
| 후속 R4/v10 | 동일 boolean provenance, NNZ 및 scalar shape parameter 누락 보강 기록 | 투영은 단순히 행·열 수만 같다고 합칠 수 없다 |
| 이번에 읽은 후속 소스 | `visitActive(occurrence, List.of(), ...)`와 shape guard 투영 존재. 별도로 shape guard의 false/true 재귀와 native layout 전체 product 생성도 존재 | ADR25 핵심 수정이 전혀 없다고 말해서는 안 된다. 반대로 모든 조합 문제가 해결되었다고 볼 수도 없다 |

근거: [GLM 당시 기록][history-glm], [v9a manifest][snapshot], [R1 진단][history-r1], [R4 보강][history-r4], [후속 비용 경로][current-cost], [후속 layout 생성][current-pattern]. R1 이후 privacy/가용성 검사로 병목이 이동했다는 기록도 있으므로, 한 단계의 개선과 전체 GLM 완료를 구분한다.

## 2. 서로 다른 세 조합을 분리해야 한다

| 조합 | 예 | 필요한 의미 | 목표 |
|---|---|---|---|
| Native capability 조합 | ExecType, input layout, output layout, kernel variant | Runtime이 어떤 물리 연산을 지원하는가 | Oracle이 지원하는 pattern/variant 관계에서 생성 |
| Program/source 환경 조합 | 어떤 branch·call·loop phase에서 어떤 정의가 입력인가 | 가용성, privacy, exact binding, 실행 여부 | ProgramFacts의 조건부 관계로 보존. 비용 때문에 전체 source tuple을 만들지 않음 |
| Cost 문맥 조합 | 실행 빈도, shape/NNZ, 크기 관련 scalar, native variant | 동결 비용 함수가 무엇을 읽는가 | 관련 변수로 먼저 투영하고, 도달 가능한 문맥에서만 primitive 생성 |

현재 `OracleFacade.PreparedDecision`의 partial feasibility는 **입력 FType의 FED 가능성**을 다룬다. 호출 정상 종료나 guard 상관관계를 판정하는 프로그램 Oracle이 아니다. 따라서 기존 partial API를 연결하는 것만으로 cost 문맥 문제까지 해결되었다고 주장할 수 없다. [Oracle partial API][oracle-partial].

또한 비용이 source identity에 무관하다는 사실은 Oracle로 불가능한 조합을 제거하는 것과 다르다. **합법적인 10,000개 조합도 비용이 같으면 비용 목적으로 열거할 필요가 없다.** 이 문제에는 합법성 기반 생성과 비용 의존성 투영이 함께 필요하다.

## 3. “가능한 조합만 생성”의 구현 계약

**[제안]** 다음을 생산 경로의 필수 계약으로 둔다.

1. 전체 source product, 전체 layout product 또는 전체 guard assignment 목록을 먼저 만들지 않는다.
2. Oracle의 지원 관계와 준비된 ProgramFacts 조건으로 허용된 다음 값·영역을 얻는다. 이미 불가능하다고 증명된 prefix의 하위 조합은 탐색·객체 생성하지 않는다.
3. Source identity가 비용 결과와 실행 여부에 영향을 주지 않는다는 양의 증명이 있으면, 관련 source 결정을 비용 문맥에서 **join/열거 전에** 제거한다.
4. Native rule은 capability가 확인된 경우에 만들고, 비용 primitive는 비용에 필요한 문맥·통계·가중치가 확인된 경우에 만든다. 미해결 조건은 별도의 coverage/조건 의무로 유지한다.
5. 전역 합법성은 공유 owner, privacy, movement, loop 계약까지 검사한다. Native Oracle의 허용만으로 완전한 프로그램 계획이 합법하다고 선언하지 않는다.
6. `UNKNOWN`을 `NO`로 바꿔 후보를 없애지 않는다. 비용이 unknown인 것을 0으로 바꾸지도 않는다. 필요한 의무가 끝까지 해결되지 않으면 `UNRESOLVED`, 자원 부족이면 그에 맞는 미완료 상태로 끝낸다.

여기서 **생성하지 않는다**는 것은 후보/source tuple/비용 배열을 먼저 할당한 뒤 탈락시키지 않는다는 뜻이다. Oracle 내부에서 작은 capability 조건이나 부분 할당을 검사하는 일까지 금지하는 것은 아니다. 일반 전역 합법성을 Oracle 한 번으로 전부 알아낼 수 있다고 가정하면, optimizer의 비싼 문제를 Oracle 안에 숨기게 된다.

기존 native rule이 나중에 다른 owner의 domain 변경으로 support를 잃어 active bit에서 제거되는 것은 정상적인 증분 propagation이다. 금지하는 것은 **이미 알려진 불가능성과 비용 무관성을 무시하고 전체 조합을 먼저 생성하는 방식**이다. 이 경계는 기존 earliest-safe-pruning의 closed-domain/UNKNOWN 원칙과 같다. [생성·전파 명세][spec-enum], [준비 상태와 root pruning][early-ready].

## 4. 비용에서 무엇을 남기고 무엇을 지울 것인가

### 4.1 비용 문맥의 최소 내용

**[제안]** Primitive의 의미적 식별자는 다음 정보에서 출발하되, 실제 estimator 의존성을 검증한 뒤 더 줄인다.

```text
동결 snapshot / objective version
실행 occurrence / call frame
선택된 native owner rule 및 kernel variant
정상 activation / continuation 조건
loop phase / cardinality / 추정 실행 빈도
비용 함수가 읽는 input·output 통계 및 scalar parameter
worker pool / 비용 profile
```

| 정보 | Native 비용 투영 시 처리 | 이유 |
|---|---|---|
| 비용과 무관한 exact writer/source ID | 제거 가능 | 같은 연산·통계·빈도에 동일 비용을 부여하는 경우 |
| Input/output rows, cols | 보존 | 연산량과 전송 byte 수에 영향 |
| NNZ, sparsity, estimator가 읽는 memory 통계 | 보존 | 같은 크기의 dense/sparse 비용이 다를 수 있음 |
| Index 범위, reshape/sequence 크기 관련 scalar | 보존 | Scalar도 shape와 계산량에 영향 |
| Guard의 값 provenance·극성 | 필요한 조건만 보존 | Caller의 `p`와 formal alias를 독립 결정으로 세면 안 됨 |
| 정상 return 및 앞선 statement 완료 조건 | 보존 또는 all-path 증명으로 축약 | Kernel 자체 shape가 같아도 실행되지 않는 경로가 있음 |
| Call frame·loop phase·value version | 의미가 같은 경우만 축약 | 같은 HOP template이라도 호출별 크기·빈도가 다름 |
| Privacy·원본 value ID·supply 공유 identity | Native 비용 key에서 제외 가능해도 해당 합법성/action 계층에는 유지 | 비용 동등성이 원본 데이터와 정책의 동등성을 뜻하지 않음 |

후속 `MemoShapes.guardSensitiveDecisions()`와 비용 통계 검사가 이 방향의 기존 기반이다. 행·열 크기만 비교하는 축약으로 되돌려서는 안 된다. [shape 의존성][shape-deps], [동일 크기·상이 NNZ 회귀][shape-test].

### 4.2 Source를 지워도 되는 증명

같은 실행 occurrence에 도달하는 두 환경 `e1`, `e2`에 대해 다음이 같아야 동일 native 비용 문맥으로 묶을 수 있다.

- 비교 대상 rule별 native kernel과 비용 함수 입력 통계가 같다.
- 실행 activation, 정상 진행, loop phase 및 빈도의 차이가 투영된 가중치에 정확하게 남는다.
- 비용 함수가 source의 위치·전송 경로·기타 identity 정보를 추가로 읽지 않는다. 그런 의존성이 생기면 signature를 확장한다.
- 사라진 source 정보가 필요한 feasibility와 공유 movement 검사는 원래 정보를 계속 참조한다.

비용 함수가 지금 우연히 같은 숫자를 반환했다는 것만으로 증명하지 않는다. 다른 rule, worker 수, NNZ 또는 profile에서 달라질 수 있다. Primitive를 만들고 숫자가 같은지 비교하는 방식은 중복 생성 비용을 이미 지불한 뒤다.

## 5. 100 × 100 예제를 어떻게 처리하는가

### 5.1 두 입력의 비용 통계가 모두 불변인 경우

가정: A의 100개 정의와 B의 100개 정의는 각각 동일한 비용 통계를 제공한다. 연산은 정상 경로에서 한 번 실행된다. Source 선택 분기는 이 실행 여부를 바꾸지 않는다.

```text
A의 guarded binding DAG → 비용 통계 불변 증명 → 통계 클래스 1개
B의 guarded binding DAG → 비용 통계 불변 증명 → 통계 클래스 1개
Oracle의 합법 native variant 관계
    + 정상 실행 문맥 1개
    → native 비용 벡터 1개
```

A/B의 정의를 읽는 선형 분석 비용은 남을 수 있다. 그러나 10,000개의 `(sourceA, sourceB)`를 방문할 필요는 없다. Owner의 합법 rule이 `R`개라면 해당 occurrence 비용 벡터는 값 `R`개다. Source가 100개에서 1,000개로 늘어도 비용 통계·activation 관계가 같으면 벡터 수가 늘지 않는 것을 검증해야 한다.

이 축약은 A/B가 값 자체도 같다는 뜻이 아니다. 서로 다른 값이어도 같은 동결 비용 추정값을 가질 수 있으며, 실행과 privacy 검사는 실제 정의를 계속 사용한다.

### 5.2 A에는 비용 통계 두 종류, B에는 한 종류가 있는 경우

A가 작은 행렬 40개 정의/큰 행렬 60개 정의이고 B가 한 통계 클래스라면, source 개수 100 × 100 대신 **도달 가능한 작은-A/큰-A 비용 문맥**을 다룬다. 모든 작은 정의의 activation·빈도가 하나의 문맥으로 합쳐질 수 있다는 조건에서 비용 문맥은 두 개다.

단, 가중치는 `40/100`, `60/100`이 아니다. Source 정의 개수는 실행 확률이 아니다. 가중치는 프로그램 guard·call·loop와 선언된 profile에서 구한다. 두 클래스의 실제 분기 확률이 0.1/0.9이면 그 가중치를 쓴다.

### 5.3 A와 B가 같은 guard를 공유하는 경우

```text
if (p) { A = A_small; B = B_small; }
else   { A = A_large; B = B_large; }
Y = kernel(A, B);
```

가능한 비용 통계 쌍은 `(small, small)`과 `(large, large)`다. A와 B의 marginal 집합을 따로 만든 뒤 2 × 2로 곱하면 없는 두 경로가 생긴다. **같은 canonical guard 관계를 join하면서** 두 경로만 반환해야 한다. Caller의 `p`와 callee의 alias, `!p`의 극성도 같은 원칙이다. Boolean 관계를 모르면 임의로 독립이라고 선언하지 않고 선언된 모델의 미해결 관계를 보존한다.

## 6. Oracle이 native rule을 생성하는 방법

### 6.1 현재 남아 있는 layout product

**[확인]** 읽은 후속 `MemoNativeRules.nativePatterns()`는 payload/metadata 입력별 partition 축을 만든 뒤 `product(axes)`로 전체 `List<List<MemoPartition>>`을 먼저 생성한다. 이후 각 pattern을 Oracle에 묻는다. 이는 source product와는 별개지만, 입력 수가 `a`이고 각 축이 4개이면 최대 `4^a` abstract pattern을 미리 만든다. CP metadata pattern도 같은 product helper를 쓴다. [현재 구현][current-pattern].

따라서 **source-free라는 이유만으로 Oracle 기반 생성이 완성된 것은 아니다.** 사용자가 요청한 방향을 지키려면 이 API도 생성 순서를 바꿔야 한다.

### 6.2 제안 API와 생성 순서

**[제안]** 기존 Oracle과 runtime dispatch를 권위로 삼아, 아래 의미의 지원 관계/streaming visitor를 제공한다. 이름은 설계용이며 현재 구현된 API 이름이 아니다.

```text
visitSupportedNativeRegions(signature, knownFacts, visitor)

각 반환 영역:
  입력 위치별 허용 layout / input role
  위치 사이의 필요한 관계와 shape 조건
  native ExecType / output-slot layout vector / kernel variant
  capability 근거와 coverage 상태
```

영역은 가능하면 여러 layout을 mask로 표현한다. 다만 `(ROW,ROW)` 또는 `(COL,COL)`만 가능한 조건을 두 입력의 `{ROW,COL}` mask 두 개로 바꾸면 없는 교차 조합이 추가된다. 분리되지 않는 관계는 관계로 남기거나 서로 겹치지 않는 두 영역으로 반환한다. Mask에서 제약 없는 전체 product를 복원하고 원래 관계를 사후 검사해서는 안 된다.

```text
for region in Oracle의 지원 영역 스트림:
    준비된 필수 facts/closed root domain과 관계를 교차
    if 증명된 NO: region 전체를 중단
    if capability/shape coverage 미해결:
        obligation 등록; 합법 rule로 확정하지 않음
    else:
        region 안의 지원 native variant만 publish
        exact binding과 조건 관계를 연결
```

Compiled owner가 여러 호출에서 공유되면, 특정 호출의 편한 variant만 골라 합법이라고 해서는 안 된다. 같은 owner 선택이 모든 필요한 호출 문맥을 만족해야 한다. Native CP, FED→LOCAL, forced-local variant, multi-output slot의 runtime 의미도 보존한다. 모든 Ruleset의 결과를 무조건 합치는 구현은 first-match runtime dispatch에 없는 실행 방법을 발명할 수 있다. [native 생성 명세][spec-enum].

기존 `partialFedFeasibility`, dependency declarations, `prepareExecutionRelation`은 재사용 후보다. 그러나 `prepareExecutionRelation`도 determinant 축을 순회하고, 지원하지 않는 경우 기존 tuple 경로로 돌아갈 수 있으므로 API 이름만으로 무조합 생성이 증명되지는 않는다. [기존 relation 준비][oracle-relation]. 지원 relation을 제공하지 못하는 family는 capability 계약을 보완한다. 임의로 family를 미지원 처리하거나 source 전수 조합으로 조용히 우회해 완료시키지 않는다.

### 6.3 기존 RuleMemo에 연결하는 구체적 선택

**[제안: 1차 구현은 기존 concrete RuleMemo를 유지한다.]** 현재 `RuleMemo.Rule.inputs`는 구체적인 `List<MemoPartition>`이다. 지원 mask/관계를 그대로 rule 한 개로 넣을 수는 없다. 따라서 지원 영역은 **생성기를 안내하는 표현**으로 쓰고, Oracle의 `allowedValues(prefix)`가 지원하는 concrete pattern만 방문해 기존 rule을 생성한다. [RuleMemo 표현][rule-model].

```text
ownerRelation = required reachable 문맥별 native 지원 관계의 교집합
ownerCoverage = 모든 required 문맥의 coverage 상태를 함께 보존

emitSupportedRules(ownerRelation, prefix):
    if prefix가 모든 입력 위치를 지정:
        require 동일 capability / output / variant가 모든 required 문맥에서 지원됨
        intern 기존 RuleMemo.Rule(prefix, outputs, variant)
        return
    position = ownerRelation의 다음 결정 위치
    for value in ownerRelation.allowedValues(prefix, position):
        emitSupportedRules(ownerRelation, prefix + value)
    미해결 영역은 owner coverage obligation으로 별도 전달
```

문맥별 지원 결과를 union해서 owner에 publish하지 않는다. 기존 `MemoRuleBuilder`처럼 **모든 필요한 도달 문맥에 공통인 rule**만 owner universe에 넣는다. 교집합도 전체 source/context product 표를 만들지 않고 관계 또는 지원 조회로 계산한다. 어느 문맥이든 coverage가 미해결이면 확인된 rule을 보존할 수는 있어도 owner를 complete로 표시하거나 전체 최적성을 승인하지 않는다. Proven inactive 문맥과 단순히 미분석인 문맥을 구분한다.

`allowedValues`는 원래 전체 layout 축을 그대로 반환하는 함수가 아니다. 같은 prefix와 호환되는 지원 관계의 부분만 조회한다. 예를 들어 위 두 대각선 관계에서 첫 입력이 ROW이면 두 번째는 ROW만 반환한다. 반환값의 지원이 아직 미확정이면 확정 집합에 끼워 넣거나 삭제하지 않고 별도 미해결 영역으로 처리한다. Partial `YES`도 이후 모든 자식이 허용된다는 뜻이 아니므로 각 단계에서 관계를 다시 제한한다.

모든 `4^a` pattern이 실제로 서로 다른 합법 native rule인 경우 기존 concrete 표현에는 그만큼의 rule이 필요할 수 있다. 이때 생성되는 것은 합법 rule이며, 불법 조합을 생성하고 지우는 방식과 다르다. 이를 더 압축하려면 RuleMemo·입력 호환성·선택·lowering의 relational rule 지원이 필요한 별도 변경이다. 이번 1차 설계는 그 변경까지 이미 제공한다고 가정하지 않는다. **지원 관계에서 concrete rule publication까지 전체 product 목록 없이 이어지고, 모든 합법 rule이 정확히 표현되는 것**을 완료 조건으로 삼는다.

## 7. Cost 문맥을 가능한 경로에서만 생성하는 방법

### 7.1 의존성 분석을 joint 해석보다 먼저 수행

**[제안]** `MemoShapes`와 ProgramFacts의 기존 binding DAG에서 다음을 읽는다.

1. 비용 함수가 읽는 input/output 통계와 scalar를 지정한다.
2. 그 값, activation, 정상 continuation, phase/frequency를 바꿀 수 있는 결정만 찾는다.
3. 각 `SELECT`/call/loop 관계를 투영한다. 두 branch의 통계가 같아도 한쪽이 반환하지 않으면 continuation 조건은 남긴다.
4. 비용에 무관한 source-only node는 투영된 자식 관계로 대체한다. 모든 source 쌍을 만든 뒤 동일 signature를 찾지 않는다.
5. 같은 guard와 loop/call 관계를 공유하는 투영 결과끼리 조건을 교차한다. 불가능한 교차 영역은 비용 문맥 객체를 만들기 전에 제거한다.

이 과정은 기존 DAG에 필요한 signature와 조건 view를 붙이는 것으로 시작할 수 있다. 새로운 전체 프로그램 Proof Graph나 모든 조합을 담는 범용 factor table은 필요하지 않다. 캐시는 `(binding/occurrence, call frame, 필요한 phase, snapshot, 실제 의존조건)` 범위로 제한한다.

### 7.2 Context 지원 조회의 계약

Native Oracle과 별도로, ProgramFacts의 이미 존재하는 reachability·activation·shape 관계를 조회하는 다음 helper가 필요하다. 이것은 runtime capability를 중복 정의하는 두 번째 Oracle이 아니다.

```text
nextSupportedCostContexts(projectedFacts, partialContext)
    → supported context regions + unresolved obligations

support(partialContext)
    → YES / NO / UNKNOWN
```

`YES`의 의미는 동결된 프로그램 모델 안에서 해당 부분 문맥의 지원이 확인되었다는 것이다. 아직 변수들이 남아 있으면 모든 하위 조합이 합법이라는 뜻은 아니다. `NO`는 모든 관련 completion이 불가능하다는 증명이며 prefix를 중단할 수 있다. `UNKNOWN`은 조건부 관계를 유지하거나 관련 변수만 더 정밀하게 검사한다.

가능한 다음 값을 지원 관계에서 직접 반환하는 구현을 우선한다. 일반 guard 관계에서 그것이 어렵다면 작은 부분 할당과 필요조건 검사로 내려가되, full source tuple과 전체 guard 목록은 만들지 않는다. 비용 문맥의 필요한 도달성·통계가 미해결이면 primitive를 확정하지 않고 obligation으로 남긴다. 지원 조회의 예산 초과를 빈 지원집합으로 반환하지 않는다.

### 7.3 비용 생성의 의사코드

```text
for occurrence, callFrame in structuredOccurrences:
    deps = costDependencies(frozenStats, activation, completion, phase, profile)
    relation = projectBindingDAGBeforeJoin(deps)   // source tuple 열거 없음

    visitSupportedCostContexts(relation, partial = empty, onContext = q):
        require q의 activation/통계/빈도 의무 확인
        require 같은 context 영역을 중복 반환하지 않음
        weight = frozenProfile.executionWeight(q)

        if weight == 0:
            비용 항만 생략                      // legality 검사는 보존
        else:
            for r in 해당 owner의 native rule universe:
                primitive = nativeCost(frozenView(q), r, workers, weight)
                registerOriginalPrimitive(owner, q, r, primitive)

    if 필요한 coverage 또는 cost 의무 미해결:
        propagate UNRESOLVED                     // 최적성/실행 승인 금지
```

위 pseudocode는 의미를 설명한다. 실제 저장은 현재처럼 문맥당 rule 비용 벡터를 사용할 수 있다. Search branch마다 새로운 rule universe나 primitive를 만들지 않는다. Root에서 안전하게 준비한 immutable 비용과 현재 active domain을 사용한다.

현재 후속 코드는 source input을 빈 목록으로 전달하는 수정까지 갖췄지만, `visitNativeShapeGuards()`는 남은 guard에 false/true로 재귀 분기한다. 따라서 다음 구현에서는 이 재귀를 **투영된 프로그램 관계를 따르는 context visitor**로 바꾸는 것이 구체적인 출발점이다. 기존 activation 검사도 내부에서 무관한 source 조합을 다시 펼치는지 함께 계측해야 한다. [후속 cost/guard 경로][current-cost].

### 7.4 Projection helper 내부에서 product를 숨기지 않기

`projectBindingDAGBeforeJoin`을 새 이름의 full resolver로 구현하면 같은 문제가 반복된다. 최초 구현은 다음과 같이 구조를 따라 축약한다.

```text
project(node, demandedFields, context):
    key = snapshot + node + demandedFields + 관련 call/phase/condition
    if 완결된 memo 결과가 있음: return 그 결과

    source/definition:
        요청된 frozen 통계와 필요한 가용성 조건을 반환  // source ID는 cost leaf에 없음
    alias:
        실제 연결된 node의 projection을 참조
    SELECT(guard, left, right):
        L = project(left, demandedFields, context)
        R = project(right, demandedFields, context)
        if L과 R의 요청 필드·activation/완료 조건이 동일하다고 증명:
            return L
        return canonical guard를 유지한 조건부 view(L, R)
    call / loop:
        정확한 frame/entry/phase 계약에 따라 projection
        순환 가정에 의존한 미완결 결과는 global positive cache에 넣지 않음
```

입력별 projection 이후의 join도 source 목록이 아니라 이 조건부 view를 연결한다. 동일 guard를 만나면 같은 branch만 함께 내려가고, 알려진 모순이면 즉시 끝낸다. 서로 독립적이고 실제 비용 통계를 바꾸는 결정은 그때 필요한 만큼 분기한다. 한 입력에서 불필요해진 guard라도 다른 입력 또는 activation에서 필요하면 공동 관계에 남는다. Guard 가중치도 이 공동 관계에서 구하며 입력별 확률을 임의로 곱하지 않는다.

동일성은 모든 요청 필드에 대한 증명이지 대표 source 하나의 값 비교가 아니다. 큰 조건 관계를 항상 작게 만들 수 있다는 보장은 없으며, 캐시·순환 처리·남은 조건 탐색의 비용을 §13 counter로 함께 드러낸다.

## 8. 정확한 합법성을 보존하는 조건

### 8.1 Source는 optimizer가 고르는 대체 입력이 아니다

어떤 실행 환경에서 A의 실제 정의가 A17이면, A42가 더 싼 위치에 있다는 이유로 대신 사용할 수 없다. Projection은 비용 표현을 줄이는 연산이다. ProgramFacts의 exact binding은 그대로 남는다.

공유 compiled owner의 중요한 양화사는 대략 다음과 같다.

```text
∃ 공통 owner 선택:
    ∀ 도달 가능한 활성 실행 환경:
        그 환경의 exact binding과 rule/supply/privacy 계약 만족
```

이를 `환경마다 서로 다른 owner 선택이 하나씩 존재한다`로 바꾸면 원래 실행할 수 없는 계획이 만들어진다. 비용 projection에서 source를 없앴다는 이유로 legality에서도 가장 편한 source 하나만 witness로 남기는 것은 금지한다.

### 8.2 Local support와 global support의 차이

입력 edge 각각에 지원 후보가 있다는 사실만으로 전체 프로그램은 합법이 아니다. 예를 들어 이진 선택변수의 `X=Y`, `Y=Z`, `X≠Z`는 모든 pairwise edge에서 support가 있어도 공동 해가 없다. 이런 관계는 관련 변수만 다루는 joint 검사 또는 global exact search에 남긴다.

따라서 “Oracle 기반으로 가능한 조합만 생성”을 “미래 global search에서 탈락할 값이 단 하나도 없는 universe를 공짜로 생성”한다는 뜻으로 해석하지 않는다. Capability·알려진 facts로 불가능한 영역은 미생성하고, 아직 결정되지 않은 전역 관계는 symbolic하게 보존한다. 기존 설계도 이 한계와 `UNKNOWN`을 명시한다. [조합 복잡도 및 support 한계][early-complexity].

### 8.3 Root 증명과 search branch 증명

Closed root domain에서 확정된 불가능성은 생성 생략에 사용할 수 있다. Search 중 임시 domain에서 support가 없어진 결과는 trail에 기록해 되돌릴 수 있어야 한다. Backtracking으로 domain이 넓어진 뒤 이전 `NO_SUPPORT`를 재사용하면 합법 계획을 잃는다. Rule universe는 epoch 안에서 불변이며, 새로운 shape/runtime/privacy 조건은 별도의 snapshot으로 처리한다.

## 9. 가중치, 중복 실행, 수치 계약

**[제안]** 완전 환경 집합 `E`를 실제로 열거하기 위한 것이 아니라 정확성 설명용으로 생각하자. 환경 `e`의 profile weight가 `μ(e)`, 해당 occurrence 실행 횟수가 `n(e)`, 단위 비용 통계가 `s(e)`라면 실수 의미의 native 비용은 다음과 같다.

```text
C_native(r) = Σ_e μ(e) · n(e) · unitCost(r, s(e))

q = 비용 관련 정보로 투영된 문맥
W(q) = Σ_{e가 q로 투영됨} μ(e) · n(e)
C_native(r) = Σ_q W(q) · unitCost(r, s(q))
```

구현은 왼쪽 전체 환경을 만든 뒤 오른쪽으로 집계하지 않는다. ProgramFacts의 조건 관계에서 오른쪽 문맥과 가중치를 직접 구해야 한다. 서로 겹치는 guard cube를 단순 합하면 같은 실행을 두 번 세므로, disjoint region 또는 정확한 union/weight 계산을 사용한다.

다음은 특히 금지한다.

- Source 수로 확률을 정하거나, 비용과 무관한 source 분기를 독립 확률 변수로 추가한다.
- 하나의 owner 선택이 불법인 runtime 환경을 버린 뒤 남은 환경의 확률을 1로 재정규화한다. 그 선택의 legality를 판단해야 한다.
- Profile weight 0을 해당 runtime 경로가 불가능하다는 증거로 쓴다.
- 같은 guard를 `p × p`로 중복 곱한다. 추정상 독립인지 여부도 고정 profile 계약에 따른다.
- Source identity를 지우는 김에 서로 다른 실행 occurrence나 call의 비용까지 한 번으로 센다.

위 식은 실수 의미의 설명이다. 실제 ADR25는 **각 의미적 문맥에서 비용 adapter가 반환한 binary64 값을 original primitive로 고정하고, optimizer와 selected-cost audit이 그 값들을 정확히 합산**하도록 요구한다. Primitive 여러 개를 먼저 `double`로 더해 단일 값으로 만드는 방식은 허용하지 않는다. [ADR25][adr25], [native 비용 adapter][cost-adapter].

비용에 무관한 source 분기로 생겼던 `c×0.5 + c×0.5` 같은 분해를 없애면 v9a의 최종 ULP가 달라질 수 있다. ADR25는 그 우연한 bitwise 결과를 보존 대상으로 삼지 않는다. 새로운 동결 objective의 primitive, 정확한 합, 최적성 검증을 일치시킨다. Runtime numeric parity와 비용 estimator parity는 각각 검사한다.

## 10. Privacy·movement·loop에서 지켜야 하는 경계

| 경계 | 잘못된 축약 | 필요한 처리 |
|---|---|---|
| Privacy | 비용 통계가 같으므로 PRIVATE/PUBLIC source도 같은 합법성 취급 | Privacy 관련 guard/source만 별도로 투영하고 실제 release/movement 정책 유지 |
| Shared movement | 크기와 byte 비용이 같으면 다른 source의 upload도 하나로 합침 | Exact value/version, target placement, lifetime, activation의 동일 action 여부로 공유 판단 |
| FED→LOCAL native 결과 | Kernel 내부 fan-in과 edge COLLECT를 함께 계산 | Native 내부 준비/결과 비용과 reusable supply 비용의 소유권 유지 |
| Function return | 양쪽 반환 shape가 같으면 항상 정상 진행한다고 가정 | Missing return, early termination, caller prerequisite 보존 |
| Loop | 순환 dependency를 만났으므로 invariant/available라고 긍정 | Entry에 근거한 induction, phase/case, ZERO/ONE/MANY 계약 유지 |
| Backedge | Cost가 싸면 upload/collect로 head mismatch 보정 | Taken backedge DIRECT 계약 유지. 일반 entry/exit supply와 분리 |
| Snapshot/cache | 임시 loop 가정에서 얻은 true를 global cache로 재사용 | 완결된 positive proof만 publish하거나 정확한 가정 문맥을 local key로 유지 |

R1 뒤의 GLM 진단에서 conditional privacy가 다음 병목이 되었다는 기록은 이 분리를 뒷받침한다. Native 비용 경로에서 product를 없앤 후 privacy 경로에서 같은 product를 다시 만들면 전체 성능 목표가 달성되지 않는다. 다만 privacy 검사를 생략하거나 unknown을 PUBLIC으로 간주하는 것은 해법이 아니다. [R1 남은 병목][history-r1].

## 11. 구현 순서와 수정 지점

아래는 **제안하는 후속 작업 순서**다. v9a로 되돌아가 이미 반영된 R1/R4를 다시 구현하라는 뜻이 아니다. 실제 착수 시 최신 통합 snapshot을 고정하고 그 위의 차이만 수정한다.

| 순서 | 작업/주요 위치 | 완료 조건 |
|---|---|---|
| 1 | `MemoProgramModel`, `MemoBindingResolver`: native 비용 원인별 방문/할당 counter | Source-only 환경 방문과 activation support 방문을 구분해 측정 가능 |
| 2 | `MemoShapes`, `MemoCostModel`: 통계·scalar·activation 의존성 계약 확인 | 빠진 NNZ/parameter 없이, source 수만 늘린 fixture의 cost 문맥 수가 일정 |
| 3 | `MemoNativeRules`, `OracleFacade`: 지원 영역/허용값 기반 native 생성 | `nativePatterns → product → Oracle` 경로 제거. §6.3의 streaming concrete rule publication 및 합법 variant 집합 보존 |
| 4 | `MemoProgramModel.visitNativeShapeGuards`, ProgramFacts/Resolver helper | Relevant guard 전체 product 대신 조건 관계를 따라 context 생성. 불가능한 문맥의 cost 배열 할당 0 |
| 5 | Native 비용 원본 primitive와 selected-cost audit 연결 검증 | 모든 작은 합법 assignment의 의미적 비용·exact 합·최적해/tie 계약 일치 |
| 6 | Privacy/availability 및 shared movement 경로의 중복 투영 검사 | 병목을 다른 단계로 옮기지 않았다는 분리 계측. 정책·공유 action 의미 보존 |
| 7 | 같은 Docker 환경의 GLM/LogReg 전체 검증 | 전체 planning 완료 상태, 실행 성공, numeric parity, 비용 audit, resource 사용 확인 |

3번의 native capability 개선과 4번의 cost-context 개선은 별도 모듈 작업이지만 같은 canonical layout/guard/facts 계약을 사용해야 한다. 증분 적용 중에도 기존 합법 공간을 줄이는 임시 skip guard, 임의 source 상한, 잘린 조합 중 최저 비용 선택을 넣지 않는다.

## 12. 반드시 필요한 회귀 검증

기존 후속 테스트에는 shape guard 투영, source-only guard 비분할, missing return의 가중치 보존 사례가 있다. 이는 재사용할 기반이며, 이번 문서에서 새로 실행한 결과는 아니다. [기존 model 테스트][projection-tests], [비용 통계 테스트][shape-test].

| 검증 사례 | 확인할 내용 |
|---|---|
| A/B source 각 1→10→100→1,000, 비용 통계/activation 고정 | Cost 문맥·primitive 수 일정. Native 비용 목적으로 source pair 방문 0. 입력 facts 분석 증가와 구분 |
| Source 100개 중 두 비용 통계 클래스 | Primitive는 실제 비용·activation 영역 수에 비례. Source 개수로 weight를 계산하지 않음 |
| Shared `p`로 A/B 동시 분기 | `(small,large)` 같은 모순 문맥은 생성되지 않음 |
| Caller `p` → callee alias / 직접 NOT / 반복 조회 순서 | 동일 결정 상관·극성 보존, 조건 캐시가 조회 순서와 무관 |
| Rows/cols 같고 NNZ 다름; shape scalar만 다름 | 비용 의존성 누락 없음. 단일 수치가 우연히 같아도 관련 통계 보존 |
| 동일 통계·상이 privacy/availability | Native 비용 축약과 별개로 불법 collect, missing source 등을 정확히 거절 |
| Missing return, early termination, inactive call | 정상 continuation에 맞는 비용, 실제 불법/미해결 상태 보존 |
| Loop T=0/1/2/3, nested call, entry-grounded cycle | 빈도 중복 없음, grounded proof 없는 축약 없음, taken backedge transfer 0 |
| 동일 source 두 consumer / 다른 source 같은 크기 | 전자는 합법 shared action의 1회 비용, 후자는 같은 action으로 오병합하지 않음 |
| Owner 하나가 상충하는 두 호출 문맥에서 공유 | 환경별로 서로 다른 owner rule을 골라 통과시키지 않음 |
| Binary support는 있지만 joint 해는 없는 fixture | Local Oracle support를 global feasibility로 오인하지 않음 |
| UNKNOWN shape/coverage, 작은 support budget, backtracking | Unknown≠NO, budget≠empty, undo 후 부정 cache 오재사용 없음 |
| Native family별 tiny exhaustive reference | Runtime dispatch 및 CP/FED→LOCAL/forced-output/multi-output 합법 집합 보존 |
| 비용 original primitive 및 전체 assignment 비교 | 같은 optimum 하나만 아니라 합법 공간과 각 비용 보존. ADR25의 ULP 계약 명시 |

작은 테스트의 exhaustive reference는 검증용으로만 허용한다. 생산 경로는 reference enumerator를 호출하지 않는다. Reference가 production predicate를 그대로 재사용하면 solver 보존만 확인할 수 있으므로, runtime capability fixture와 수식/작은 독립 계산을 함께 사용한다. 최적해 숫자 하나가 같다는 이유만으로 non-optimal 합법 계획이 사라지지 않았다고 주장해서는 안 된다.

## 13. 계측과 성능 합격 기준

**[제안]** 전체 시간만 재지 말고 적어도 아래 counter를 phase별로 분리한다. 계측 자체가 환경별 event 객체를 무제한 저장하지 않도록 aggregate counter와 제한된 sample을 사용한다.

| 구분 | 계측 항목 |
|---|---|
| Oracle | Request/cache miss, determinant/prefix 방문, 지원 영역 수, rule 할당 수, coverage UNKNOWN |
| Context 분석 | Binding DAG 방문, projection/cache hit, 관련 guard 수, 지원 prefix 검사, NO 영역 생략, UNKNOWN 의무 |
| Native 비용 | **Source-only 조합 방문 수**, projected context 수, primitive/vector 수, 값 개수, allocation byte |
| 합법성 | Edge/joint/privacy/availability 검사 수, 같은 revision 중복, support budget 초과 |
| Solver | 모델 생성과 분리한 propagation/search 시간·nodes·status·incumbent/optimum |
| Runtime | 선택 비용 재평가, 공급 action 수, numeric parity, 실제 실행 시간 |

합격 기준은 다음과 같다.

1. Source-only scaling fixture에서 native 비용용 source tuple 생성/방문 0, 고정 의미에 대한 primitive 수 일정.
2. 이미 증명된 불가능한 layout/context 영역의 rule 또는 비용 배열 생성 0. 나중에 domain 변경으로 생긴 정상적인 propagation 삭제는 별도 집계.
3. 합법 집합, 공유 owner, 정확한 binding, privacy·movement 계약, 원본 비용 합 검증 통과.
4. 동일 Docker 이미지·worker 수·CPU/memory 제한·입력/설정에서 baseline과 변경 버전 비교. 워밍업과 반복 횟수, timeout/OOM/UNRESOLVED 포함 결과를 기록.
5. GLM에서 비용 모델 구성뿐 아니라 전체 planning의 완료 상태와 실행·numeric parity까지 확인. `run_LAN_docker.sh` 사용. Host model-only 진단을 최종 성능 근거로 승격하지 않음.

현재 근거로 새 구현의 목표 초 수나 speedup을 확정할 수 없다. Source projection 후 privacy 또는 exact search가 지배할 수 있으므로, 이번 병목 개선과 전체 planner 성능을 분리해서 보고한다.

## 14. 남는 복잡도와 하지 말아야 할 해결

Native 비용 생성의 목표 크기는 source product가 아니라 대략 `Σ_occurrence Q_occurrence × R_owner`다. `Q`는 실제 비용·실행 문맥 수, `R`은 owner의 native rule 수다. ProgramFacts 분석, Oracle 지원 관계, 조건부 reachability, privacy·movement 및 solver 탐색 비용은 이 식에 포함되지 않는다.

`Q` 자체가 지수적일 수도 있다. 모든 guard가 실제 shape/빈도를 다르게 만들거나 많은 변수가 공동 제약에 걸리면, 정확한 문제를 푸는 데 필요한 조합은 남는다. 본 설계는 **비용과 무관한 source 곱, 알려진 불가능 조합, 같은 의미의 반복 분석을 없애는 것**이지 모든 프로그램의 exact optimization을 선형 시간으로 만드는 주장이 아니다.

| 피해야 할 방식 | 이유 |
|---|---|
| 모든 source 조합 생성 후 `distinct`/groupBy | 열거·중간 객체 병목을 이미 지불 |
| 최종 CostTerm만 합치기 | Primitive 생성 전 projection이라는 ADR25 목적을 충족하지 못함 |
| Lazy iterator로 product 전체를 결국 방문 | Peak 메모리는 줄어도 불필요한 시간복잡도 유지 |
| 모든 source 중 대표 하나만 선택 | Shape/activation/정확한 정의·privacy 상관 손실 가능 |
| 크기만 같으면 source/statistics 합치기 | NNZ와 scalar parameter 의존성 누락 |
| Oracle 앞에 opcode별 임의 skip/상한 | 지원 가능한 합법 공간과 최적성 훼손 |
| Prefix UNKNOWN을 false 처리 | 아직 증명하지 못한 합법 영역 제거 |
| 지원 prefix가 있다고 모든 하위 조합 허용 | Existential support와 전체 영역 합법성을 혼동 |
| Global feasibility를 생성 전에 무제한 전수 검사 | 별도 optimizer를 한 번 더 돌리는 병목 |
| 비용 primitive를 double로 먼저 합산 | Frozen primitive exact-sum 및 audit 계약 위반 |

## 15. 문서 작업의 검증 범위

v9a 원인, 누적 R1/R4 기록, 후속 source의 projection·layout product·shape guard 재귀를 서로 대조했다. 관련 baseline 사본과 hash는 [근거 폴더 manifest][capture], 문서 링크·행 범위·사본 hash 검사 결과는 [verification.json][verification]에 남긴다.

이번 작업에서 production/test 변경, 새 unit test 실행, GLM/LogReg Docker 재실행은 하지 않았다. 위 알고리즘과 acceptance matrix는 **구현해야 할 설계와 통과해야 할 기준**이며 이미 완료한 성과가 아니다. 원래 [v9a 적합성 보고서][audit]의 역사적 판정을 유지하면서, 후속 구현에 존재하는 ADR25 수정과 추가로 필요한 Oracle 기반 생성 작업을 구분했다.

[capture]: evidence/memo-cost-projection-design-2026-10-13/capture.json
[verification]: evidence/memo-cost-projection-design-2026-10-13/verification.json
[audit]: FEDPLANNER_V9A_DESIGN_CONFORMANCE_REPORT_2026-10-13_KO.md
[snapshot]: ../experiments/memo-migration-20261013/docker-engine-v9a/snapshot.json
[v9cost]: ../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L889
[v9joint]: ../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L931
[v9enum]: ../experiments/memo-migration-20261013/docker-engine-v9a/main-sources/org/apache/sysds/hops/fedplanner/memo/MemoBindingResolver.java#L523
[history-glm]: evidence/memo-cost-projection-design-2026-10-13/baseline/docs/SESSION_ISSUES_2026-10-13.md#L819
[history-r1]: evidence/memo-cost-projection-design-2026-10-13/baseline/docs/SESSION_ISSUES_2026-10-13.md#L842
[history-r4]: evidence/memo-cost-projection-design-2026-10-13/baseline/docs/SESSION_ISSUES_2026-10-13.md#L864
[adr25]: evidence/memo-cost-projection-design-2026-10-13/baseline/docs/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L1465
[spec-enum]: evidence/memo-cost-projection-design-2026-10-13/baseline/docs/FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md#L241
[early-ready]: evidence/memo-cost-projection-design-2026-10-13/baseline/docs/FEDPLANNER_EARLIEST_SAFE_PRUNING_2026-10-13_KO.md#L274
[early-complexity]: evidence/memo-cost-projection-design-2026-10-13/baseline/docs/FEDPLANNER_EARLIEST_SAFE_PRUNING_2026-10-13_KO.md#L466
[current-cost]: evidence/memo-cost-projection-design-2026-10-13/baseline/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModel.java#L942
[current-pattern]: evidence/memo-cost-projection-design-2026-10-13/baseline/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoNativeRules.java#L393
[oracle-partial]: evidence/memo-cost-projection-design-2026-10-13/baseline/src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L252
[oracle-relation]: evidence/memo-cost-projection-design-2026-10-13/baseline/src/main/java/org/apache/sysds/hops/fedplanner/rules/bridge/OracleFacade.java#L452
[shape-deps]: evidence/memo-cost-projection-design-2026-10-13/baseline/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoShapes.java#L193
[shape-test]: evidence/memo-cost-projection-design-2026-10-13/baseline/src/test/java/org/apache/sysds/hops/fedplanner/memo/MemoCostShapeProjectionTest.java#L33
[cost-adapter]: evidence/memo-cost-projection-design-2026-10-13/baseline/src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoCostModel.java#L81
[projection-tests]: evidence/memo-cost-projection-design-2026-10-13/baseline/src/test/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramModelTest.java#L167
[rule-model]: evidence/memo-cost-projection-design-2026-10-13/baseline/src/main/java/org/apache/sysds/hops/fedplanner/memo/RuleMemo.java#L25
