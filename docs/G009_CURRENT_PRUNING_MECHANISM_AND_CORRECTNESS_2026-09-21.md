# G009 현재 pruning 방식과 정확성 조건

- 작성일: 2026-09-21
- 분석 기준 커밋: `a6281207cf520171af47f5f8755a34cbd28ecf37`
- 목적: 현재 코드가 어떤 후보/조합을 제외하는지 설명하고, 후보 공간의 완전성과 최적 선택 보존을 구분한다.
- 범위: `CandidateSelections`의 정책용 후보 projection과 내부 `Search`/`ComponentSearch`, 이에 사용하는 relocation/local materialization 하한.
- 범위 밖: DP-global/DP-local의 모든 pruning에 대한 전수 감사, runtime 성능 최적성 증명, 새로운 코드 수정/실험.
- 근거 표기: **확인**은 코드 또는 기존 기록, **조건부 논증**은 명시된 전제가 맞을 때의 수학적 결론, **미검증**은 아직 확보하지 못한 증거다.

## 1. 핵심 결론

현재 구현에는 서로 다른 두 종류의 축소가 있다.

1. **정책용 후보 축소:** 합법적인 후보라도 FedAll/Heuristic 계열의 선호 정책에 맞지 않으면 정책용 domain에서 제외한다.
2. **조합 탐색 pruning:** 부분 선택이 완성 불가능하거나, 현재 최선의 해보다 좋아질 수 없다고 판단하면 그 하위 조합을 방문하지 않는다.

따라서 “현재 pruning은 불가능한 후보만 제거한다”는 설명은 부정확하다.

후보 표현의 완전성을 복구한 뒤 최적해를 보존하며 탐색을 생략하는 것은 가능하다. 그러나 **정책 projection을 전체 합법적 후보 공간으로 착각하거나, 잘못된 하한으로 합법적인 최적해를 생략하면 지난 완전성 수정의 목적을 훼손한다.**

현재 확인한 설계에는 pruning을 정당화할 구조가 있다. 다만 모든 새 pruning의 최적해 보존을 독립적으로 검증했다고 단정할 증거는 부족하다. 이는 확인된 반례가 있다는 뜻이 아니라, **구현의 의도와 검증된 보장의 범위를 구분해야 한다는 뜻**이다.

## 2. 후보 표현, 정책 선택, 탐색 생략의 차이

전체 합법적 계획 집합을 \(S\), 표현을 해석해 얻는 계획 집합을 \(R\)이라고 하자.

완전하고 정확한 표현의 계약은 다음과 같다.

\[
R=S
\]

- \(S\setminus R\): 누락된 합법적 계획.
- \(R\setminus S\): 잘못 허용된 불법 계획.

정책용 projection \(Q\)는 특정 정책의 선택을 위해 \(Q\subseteq R\)을 만들 수 있다. 이때 \(Q\)가 전체 공간이라고 주장하면 안 된다.

부분 선택 \(p\)의 합법적 완성 집합을 \(C(p)\)라고 하면, 탐색 pruning은 다음 근거로 가능하다.

### 2.1 완성 불가능

\[
C(p)=\varnothing
\]

support 충돌처럼 이후 선택으로 해결할 수 없는 모순이 여기에 해당한다. 현재 domain이 실제보다 좁다면 이 판단도 잘못될 수 있다.

### 2.2 incumbent보다 좋아질 수 없음

최소화 목적함수 \(f\)와 실제 합법적 incumbent \(x^*\)에 대해:

\[
LB(p)\le\min_{x\in C(p)}f(x),\qquad LB(p)>f(x^*)
\]

이면 가지를 제거할 수 있다. 하한은 낙관적이어도 되지만 **과대평가하면 안 된다.** 동점까지 제거하려면 tie-break도 개선 불가능해야 한다.

이 보장은 최적해 선택에 대한 것이다. **모든 합법적 계획을 열거해 반환하는 기능**에서는 합법적이지만 열등한 계획도 필요하므로 같은 pruning을 적용할 수 없다.

## 3. 적용 범위: 정책용 Search와 비용 기반 DP

현재 production 호출 경계는 다음과 같다.

```text
PolicyFirstFeasiblePlacementSelector
  → PolicyCandidateSelectionView.select(placement assignment)
    → CandidateSelections.selectMaterializationMaximal(...)
      → 정책용 후보 domain
      → Search / ComponentSearch
```

**확인:** `PolicyCandidateSelectionView`는 materialization-maximal projection을 정책 소유의 view로 분리한다. 클래스 주석은 Exact/DP가 analysis 소유의 후보 공간을 직접 소비하며 이 package-private view를 받지 않는다고 명시한다. `select()`는 호출 전후 analysis/graph fingerprint 불변도 확인한다.

이 문서의 `Search`는 이름에 exact가 등장하더라도 **주어진 placement assignment와 정책 domain에 대한 receipt 조합 선택기**다. 이것을 DP 전체 최적화와 동일시하면 안 된다.

주의:

- 정책 projection을 사용하지 않는다는 사실은 DP에 다른 pruning이 없다는 뜻이 아니다.
- 공유 graph, feasibility, authority 모델의 오류는 정책 플래너와 DP 모두에 영향을 줄 수 있다.
- P2에서 세 플래너가 성공했다는 실험만으로 각 플래너의 성능 개선 원인이 모두 이 `Search`라고 단정할 수 없다.

## 4. 단계 A: 탐색 전 정책용 후보 축소

구현: `CandidateSelections.materializationMaximalVariants()`.

### 4.1 realization-dependent로 분류된 consumer

function/transient 경계와 required-support 관계 등에 참여하는 consumer는 feasible row 목록을 그대로 유지한다. 개별 row의 선호도만 보고 제거하면 다른 consumer의 합법적 선택을 막을 수 있기 때문이다.

**조건부 논증:** 안전하려면 `realizationDependentConsumers()`가 필요한 결합 관계를 빠뜨리지 않아야 한다. 이름이 dependent라는 이유만으로 완전성이 증명되지는 않는다.

### 4.2 나머지 consumer

다음 순서로 축소한다.

| 순서 | 동작 | 의미 |
|---|---|---|
| 1 | FED 실행이면 PRESENT 입력 수 최대 row만 유지 | 정책의 입력 선호도 |
| 2 | CP 실행이면 PRESENT 입력 수 최소 row만 유지 | 정책의 입력 선호도 |
| 3 | 같은 `CandidateEffectKey`별 첫 row만 유지 | 물리 효과 동등성에 따른 대표 선택 |
| 4 | FED에서 anchor-aligned 대안이 있으면 해당 row만 유지 | 정책의 anchor 정렬 선호도 |

1과 2는 실행 위치에 따른 대안 분기다. 여기서 PRESENT는 candidate 입력 상태이며, “모든 FED 입력” 또는 “실제 발생한 action 하나”와 동일한 개념으로 취급하면 안 된다.

**합법적인 후보가 이 단계에서 제외될 수 있다.** 이 축소의 근거는 불법성 판정이 아니라 정책과 동등성이다.

필요한 정확성 조건:

- 개별 선호도 축소가 전역 feasible 조합을 없애지 않아야 한다.
- 같은 effect key인 두 row를 바꿔도 support, authority, 공유/억제 action과 최종 tie-break에 관련된 차이가 없어야 한다.
- 이 축소 결과를 비용 기반 DP의 전체 후보 universe로 전달하지 않아야 한다.

## 5. 단계 B: interaction component와 탐색 순서

### 5.1 고정 변수와 가변 변수

domain 크기가 1인 consumer는 먼저 선택한다. domain 크기가 2 이상인 consumer가 탐색 변수가 된다.

### 5.2 component 분리

`exactInteractionComponents()`는 다음 요소를 공유하는 가변 consumer를 같은 component로 합친다.

- required realization support
- logical transient 및 function 등의 logical boundary 관계
- relocation anchor/activity/suppression/shared-emission factor
- local materialization producer factor
- derived-FOUT action identity

component를 하나씩 풀고 선택을 고정한다. 앞서 선택한 component의 기여는 이후 탐색에서 상수로 취급된다.

**조건부 논증:** 모든 feasibility 결합이 component 안에 있고 목적값이 component별 기여와 고정 상수로 분해된다면 독립 최적화가 가능하다. 결합을 과하게 추가하면 느려질 뿐이지만, 필요한 결합을 누락하면 정답이 달라질 수 있다.

### 5.3 탐색 순서 변경

`incumbentSearchOrder()`는 이미 선택한 이웃 수, 전체 이웃 수, domain 크기를 이용해 제약을 일찍 드러낼 순서를 정한다.

이 자체는 pruning이 아니다. **같은 domain을 다른 변수 순서로 탐색한다.** 첫 feasible 해를 빨리 찾는 용도이며, 그 첫 해가 canonical optimum이라고 가정하지 않는다.

## 6. Search가 실제 비교하는 목적함수

고정된 placement assignment에서 receipt 조합 \(x\)의 비교 순서는 다음과 같다.

\[
\min_{\mathrm{lex}}\left(-P(x),-A(x),E(x),\rho(x)\right)
\]

- \(P\): FED consumer에는 PRESENT 입력 수를 더하고, CP consumer에는 그 수를 빼는 input preference의 합.
- \(A\): anchor-aligned 판정에 따른 preference의 합.
- \(E\): relocation + local materialization + derived-FOUT의 물리 emission 수.
- \(\rho\): canonical consumer 순서의 receipt rank tuple.

즉, 선호도가 더 좋은 해라면 action 수가 더 많아도 먼저 선택된다.

**이 목적함수의 exact optimum은 runtime 최솟값, 통신 바이트 최솟값 또는 일반적인 DP 비용 최솟값을 뜻하지 않는다.** action 수가 0이어도 compute 시간과 다른 실행 비용이 0이라는 뜻은 아니다.

## 7. 단계 C: 부분 조합에서 가지를 자르는 조건

구현: `ComponentSearch.solve()`.

### 7.1 anchor 및 realization 충돌

- `relocationScorer.hasAnchorConflict()`가 참이면 제거.
- `realizationsCanStillBeCompatible(...)`가 거짓이면 제거.

완성 후에도 유지되는 모순만 제거해야 한다. partial 상태의 미선택을 잘못된 선택으로 취급해서는 안 된다.

### 7.2 실제 candidate domain을 이용한 support forward check

`candidateDomainsCanStillSupportSelectedRows()`는 다음을 검사한다.

1. 선택한 row의 required support owner가 선택되어 있으면, 그 owner row와 reference가 일치해야 한다.
2. owner가 미선택이고 domain이 있으면, 그 reference와 일치하는 row가 적어도 하나 있어야 한다.
3. 현재 component의 모든 미선택 consumer는 위 검사에 통과할 row를 적어도 하나 가져야 한다.

예:

```text
B=b1은 A=a2 realization을 요구한다.
이미 A=a1을 선택했고 a1은 해당 reference와 다르다.
→ A=a1, B=b1인 가지는 다른 변수를 선택해도 완성할 수 없다.
```

코드는 support owner의 domain이 없다는 이유만으로 곧바로 실패시키지는 않는다. 또한 이 검사는 완전한 CSP 풀이가 아니라 필요조건 검사다. 개별 support가 남아 있어도 전역 조합이 불가능할 수 있으므로 이후 탐색/검증이 필요하다.

### 7.3 선호도 suffix 상한

미선택 변수 집합을 \(U\)라 하면:

\[
U_P(p)=P(p)+\sum_{i\in U}\max_{r\in D_i}P_i(r)
\]

각 변수의 최대 input preference를 달성하는 row들 중 최대 aligned preference를 합해, 그 input 상한 달성 조건에서의 \(U_A(p)\)도 계산한다.

- \(U_P(p)<P(x^*)\)이면 제거.
- \(U_P(p)=P(x^*)\)이고 \(U_A(p)<A(x^*)\)이면 제거.

변수 간 충돌을 무시해 상한이 실제보다 커지는 것은 안전하다. 탐색을 덜 줄일 뿐이다. 상한이 실제 가능한 선호도보다 작아지면 안전하지 않다.

### 7.4 물리 emission 하한

앞의 두 선호도 상한이 incumbent 값과 **둘 다 같은 경우에만** 다음 하한을 사용한다.

\[
L_E(p)=L_{\mathrm{relocation/FOUT}}(p)+L_{\mathrm{local}}(p)
\]

**relocation/FOUT 하한:**

- 해당 consumer의 모든 남은 row가 derived-FOUT를 요구하면 가능한 action identity들을 대안 집합으로 만든다.
- 모든 남은 row에 공통인 relocation demand를 찾는다.
- action 필요 여부가 candidate 선택에 따라 바뀌거나 emission을 피할 가능성이 있으면 해당 요구를 하한에서 제외한다.
- 대안 집합들이 서로 겹치지 않는 요구를 골라 개수를 센다. 같은 action으로 여러 요구를 충족할 수 있는 경우를 단순 합산하지 않는다.

**local materialization 하한:**

- 이미 선택한 derived-FOUT가 해당 local action을 억제하면 세지 않는다.
- 남은 allowed row에 억제 가능한 derived-FOUT가 있으면 세지 않는다.
- 그렇지 않고 base requirement 또는 선택한 row의 local requirement가 있으면 센다.

여기서 allowed 목록은 현재 suffix보다 넓을 수 있다. 불가능한 미래 억제까지 가능하다고 취급하면 하한이 약해지지만, 과대평가를 피하는 방향이다.

제거 조건:

```text
L_E > incumbent emission 수
  → 제거

L_E = incumbent emission 수
  그리고 canonical rank도 개선 불가능
  → 제거
```

주의: 현재 local action 수는 이후 derived-FOUT 선택으로 줄어들 수 있다. 따라서 “현재 action 수가 많다”만으로는 pruning 근거가 되지 않는다. 두 하한의 합도 동일 효과를 중복 계산하지 않는다는 조건이 필요하다.

### 7.5 canonical rank 하한

미선택 변수에 모두 rank 0을 넣어 낙관적인 canonical tuple을 만든다. 그 tuple조차 incumbent보다 작지 않으면 어떤 완성도 rank를 개선할 수 없다.

이 검사는 **앞선 목적값과 emission 하한 비교가 허용하는 경우에만** 가지를 제거한다. rank가 나쁘다는 이유로 더 좋은 선호도/비용을 가진 해를 제거하는 구조는 아니다.

### 7.6 완성 leaf 검사

전체 row를 선택한 뒤에도 relocation의 정확한 최소 emission 계산이 불가능(`Integer.MAX_VALUE`)하면 그 leaf를 거부한다. 통과하면 local/FOUT 수를 합쳐 목적 tuple과 canonical 순서로 incumbent를 갱신한다.

## 8. 단계 D: 0-emission optimum 전용 경로

### 8.1 첫 feasible 해는 우선 incumbent일 뿐이다

factor 순서의 probe는 첫 feasible 해에서 멈춘다. 내부 `provenOptimal` 변수는 probe 종료에도 사용되므로 **그 변수명만 보고 전역 최적성이 증명됐다고 읽으면 안 된다.**

상위 `Search.solve()`가 별도로 검사한다.

\[
P(x)=U_P(\varnothing),\quad A(x)=U_A(\varnothing),\quad E(x)=0
\]

이 조건이면 비음수 emission 수와 선호도 상한에 의해 앞의 세 목적값은 더 좋아질 수 없다. 조건을 만족하지 않으면 probe 해를 incumbent로 넘겨 일반 branch-and-bound를 수행한다.

### 8.2 canonical self-reduction

0-emission optimum의 목적값이 확인돼도 factor 순서의 첫 해가 canonical optimum인 것은 아니다.

1. canonical consumer 순서대로 진행한다.
2. 현재 witness보다 rank가 작은 row를 차례로 임시 선택한다.
3. 그 prefix에서 같은 선호도와 emission 0으로 완성 가능한지 target 탐색한다.
4. 가능하면 새 witness를 채택하고, 불가능하면 다음 row를 검사한다.
5. 선택을 고정하고 다음 consumer로 간다.

**조건부 논증:** target feasibility 탐색이 완전하다면 각 위치에서 최적 완성이 존재하는 최소 rank를 선택하므로 canonical optimum이 보존된다.

### 8.3 zero-target 전용 즉시 제거

target emission 수가 0인 탐색에서 다음 중 하나이면 바로 반환한다.

```text
selectedRelocationDemandRows != 0
또는 foutEmissionCount != 0
```

이것은 일반적인 “현재 action 수가 최종 하한이다” 규칙이 아니다. 코드는 exact relocation binding이 named action을 강제하고 derived-FOUT가 emission을 소유한다고 전제한다.

**미검증 핵심:** 해당 binding이 모든 합법적 완성에서 실제로 양의 emission을 강제하는지, action suppression/공유/authority 모델과 함께 검증해야 한다. 이후 선택으로 0까지 억제 가능한 반례가 있다면 이 pruning은 잘못이다.

## 9. 전체 흐름 의사코드

```text
feasible candidate rows for a fixed placement assignment
  → 정책용 선호도 축소 및 effect 대표 선택
  → singleton 선택
  → interaction component 분리

각 component:
  factor 순서로 첫 feasible witness 탐색
  if 선호도 상한 달성 AND emission 0:
    target feasibility 검색으로 canonical self-reduction
  else:
    witness를 incumbent로 삼아 일반 branch-and-bound
  winner를 고정

최종 조합의 compatibility와 relocation completion 확인
최종 선택 materialize

각 부분 탐색:
  zero-target에서 강제 relocation/FOUT가 있으면 반환
  incumbent보다 선호도 상한이 작으면 반환
  anchor / realization / candidate-domain support가 충돌하면 반환
  선호도 상한이 같고 emission 하한/rank로 이길 수 없으면 반환
  미선택 변수가 있으면 모든 row를 순회
  없으면 정확한 leaf 평가 및 incumbent 갱신
```

최종 조합이 합법적이라는 검사만으로 전역 최적성을 검증할 수는 없다. 누락된 더 좋은 조합이 있어도 최종 선택 자체는 합법적일 수 있다.

## 10. P2 관측 결과가 증명하는 것과 하지 않는 것

[기존 세션 보고서](G009_P1_P2_FEDPLANNING_SESSION_REPORT_2026-09-21.md)의 trace 기록:

```text
consumers = 141
full row product = 198,135,565,516,800
component products = [171,992,678,400, 144, 8]
```

보고서는 가장 큰 component에서 325개 leaf로 선호도 상한과 emission 0인 witness를 찾았다고 기록한다. 이후 canonical self-reduction이 수행됐다.

- 이 product는 **후보 domain의 Cartesian product**다. 모든 조합이 합법적이라는 뜻이 아니다.
- 325는 문서에 기록된 해당 witness 탐색의 leaf 수이며, 모든 target 탐색을 포함한 전체 작업량으로 해석하면 안 된다.
- 이 기록은 이번 문서 작업에서 새로 실행하거나 재측정한 결과가 아니다.
- planning 성공은 해당 실행에서 선택을 구성했다는 근거이지 전체 후보 공간/최적성의 보편적 증명은 아니다.
- planning-only 결과를 runtime 실행 검증으로 해석하면 안 된다.

기존 보고서의 “비용 최적성과 canonical tie-break를 모두 유지”라는 표현은 이 문서의 **목적함수 범위와 검증 제한**을 함께 읽어야 한다. 일반 runtime 비용 최적성 또는 모든 입력에 대한 증명으로 확장하지 않는다.

## 11. 검증 상태와 남은 증명 의무

| 항목 | 현재 근거 | 한계 |
|---|---|---|
| 원본 후보 공간 완전성 | `CandidateReceiptAssignmentCompletenessTest`, `GlobalReceiptPlanSpaceCompletenessTest` 등의 유한 fixture/oracle 검사 | 새 탐색 pruning의 최적해 보존과는 별개 |
| 기존 focused regression/package | 이전 세션 PASS 기록 | 이번 문서 작업에서 재실행하지 않음 |
| exhaustive와 정책 Search 비교 | `NeutralPlacementGraphUploadRelocationRedTest.candidateMaterializationSearchMatchesBoundedExhaustiveOracle` 존재 | 실제 GLM의 일부 domain을 고정한 bounded 비교이며 모든 경우의 증명 아님 |
| 넓은 suite | 기존 보고서에 해당 클래스 한 항목의 builder GC-heavy 중단 기록 | 이 기록만으로 위 비교 메서드의 최종 PASS를 확정하지 않음 |
| 기존 heuristic 실패 | 기준 커밋에서도 동일한 두 실패를 재현했다는 기록 | 프로젝트 전체 suite clean은 아님 |
| 새 pruning별 독립 검증 | 이번 읽기에서 전 규칙을 포괄하는 증거를 확보하지 못함 | 미검증으로 유지 |

### 11.1 이후 채택/보장에 필요한 검사

다음은 **추가 검증 요구사항이며 이번 문서 작업에서 실행한 결과가 아니다.**

1. **representation oracle:** raw universe에서 합법 계획 집합이 독립 정의와 일치하는지 비교한다.
2. **projection 검증:** 정책 전수 탐색과 projection 이후 결과를 비교해 앞단 축소의 정책 최적해 보존을 검사한다.
3. **search oracle:** 동일 domain을 pruning 없이 전수 열거한 결과와 최적 목적 tuple·receipt·authority·canonical rank를 비교한다.
4. **prefix 하한 검사:** 모든 작은 부분 선택에 대해 \(L_E(p)\le\min_{x\in C(p)}E(x)\)를 직접 확인한다.
5. **제거 branch 검사:** 불가능 판정이면 합법 completion이 0개인지, dominance 판정이면 당시 incumbent를 이길 completion이 없는지 확인한다.
6. **component 검사:** 분리 풀이와 통합 전수 풀이가 일치하는지 비교한다.

필수 반례 축: coupled support, function/transient 경계, 같은 action 공유, action suppression, 서로 다른 authority, non-zero optimum, preference와 emission의 충돌, canonical 동점, 방문 순서 변경.

검증을 빠르게 하려면 작은 synthetic fixture를 쓰는 것이 적합하다. 대형 workload 반복 성공 횟수를 늘리는 것으로 수학적 조건 검증을 대체하지 않는다.

## 12. 코드 위치 안내

모든 경로와 행 번호는 위 기준 커밋 기준이다. 이후 행 번호가 이동하면 메서드명으로 찾는다.

| 소스 | 주요 위치/메서드 | 역할 |
|---|---|---|
| [CandidateSelections.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java) | 1078–1138 `selectMaterializationMaximal*` | 정책용 Search 진입 |
| 같은 파일 | 1195–1289 `realizationDependentConsumers`, `materializationMaximalVariants` | dependency 분류 및 후보 축소 |
| 같은 파일 | 2020–2098 `Search` 초기화 | preference/support/rank 및 scorer 준비 |
| 같은 파일 | 2109–2270 `incumbentSearchOrder`, `solve`, `canonicalizeProvenZeroEmissionOptimum` | 탐색 순서와 zero-optimum 경로 |
| 같은 파일 | 2345–2479 `ComponentSearch` | 상한·하한·충돌 pruning과 leaf 평가 |
| 같은 파일 | 2482–2537 support 검사 및 `canonicalCompletionCanBeatBest` | forward check와 rank 비교 |
| 같은 파일 | 2556 이후 `exactInteractionComponents` | factor component 분리 |
| [LocalMaterializationSelections.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/LocalMaterializationSelections.java) | 292 `minimumPossiblePhysicalEmissionCount` | 억제를 고려한 local 하한 |
| [RelocationSelections.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/RelocationSelections.java) | 524 `hasExactRelocationDemand`, 556 이후 `unavoidableCombinedPhysicalEmissionCount` | zero-target demand와 relocation/FOUT 하한 |
| [PolicyCandidateSelectionView.java](../src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/PolicyCandidateSelectionView.java) | 24–29, 52–58 | 정책 projection 소유 경계 |
| [NeutralPlacementGraphUploadRelocationRedTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphUploadRelocationRedTest.java) | 294 이후 `candidateMaterializationSearchMatchesBoundedExhaustiveOracle` | bounded exhaustive 비교 |

## 13. 최종 판단

**현재 pruning의 종류와 적용 조건은 코드로 설명할 수 있다. 그러나 설명 가능하다는 것과 모든 구현 조건이 검증됐다는 것은 다르다.**

우선 확인할 위험은 다음 네 가지다.

1. 정책 축소가 원본 후보 공간을 대체하거나 잘못된 독립성 판단으로 합법적 조합을 제거하는가?
2. component 분리에서 숨은 feasibility/authority/공유 비용 결합을 누락하는가?
3. action 하한이 억제·대체·공유를 놓쳐 과대평가되는가?
4. zero-target에서 exact relocation demand를 제거하는 불변식이 모든 합법적 완성에 성립하는가?

이번 작업에서는 이 위험을 새 버그로 확정하거나 해결하지 않았다. **코드 변경 없이 현재 동작, 조건부 정당화, 남은 검증 의무를 문서화했다.**
