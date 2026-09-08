# Target Anytime 구현 보고서

| 문서 정보 | 값 |
| --- | --- |
| 작성일 | 2026-09-08 |
| 대상 프로젝트 / 서버 | Cofee / SystemDS, so007 (`dams-so007`) |
| 문서 범위 | 기존 Anytime와 새 Target Anytime의 차이, 구현 계약, 자원 제한, 테스트 근거 |
| 기준 commit | `c20c4f0cab281d2d2d36dd6b40167837b22be8b4` |
| 구현 commit | `370d6a8ba3eb99425c3b5c1c83ffb113ee554e07` |
| 구현 JAR SHA-256 | `0b896e12ebbdf0a061bece458e826ac02923eeadae0307fea143995e5909ed4a` |
| 구현 상태 | 완료, so007 clean package와 선택 테스트 통과 |
| 성능 결과 | 이 문서에 포함하지 않음. 7-way 전체 planning campaign은 이 문서 작성 시점에 아직 완료되지 않음 |

## 1. 구현 결과

기존 Anytime를 대체하지 않고 별도 정책인 **Target Anytime**를 추가했다. 설정값은
`sysds.fedplanner.regional.algorithm=anytime-target`이고 내부 enum은
`ANYTIME_TARGET`이다. 기존 방식은 `algorithm=legacy`로 그대로 선택할 수 있다.

새 정책의 목적은 인증 공식을 새로 만드는 것이 아니다. 기존 Anytime도 이미 다음 세 가지를
수행한다.

1. 기존 Regional이 만든 feasible plan을 incumbent로 사용하고 그 canonical modeled cost를
   upper bound $U$로 둔다.
2. 전체 encoded model의 mini-bucket elimination(MBE)으로 lower bound $L$을 계산하고,
   더 큰 width의 완료된 유효 하한으로 $L$을 높인다.
3. 누적 region exact solve로 더 좋은 feasible plan을 찾으면 $U$를 낮추고, 설정한 절대 또는
   상대 threshold를 만족하면 종료한다.

따라서 “Anytime은 LB만 개선한다”거나 “기존 Anytime에는 threshold 검사가 없다”는 설명은
정확하지 않다. 이번 변경은 기존 Anytime의 종료·작업 선택 정책에서 확인된 다음 한계를
별도 구현으로 보완한다.

- 기존 방식은 목표를 아직 달성하지 못했어도 설정한 `rounds`가 끝나면
  `ITERATION_LIMIT`으로 종료할 수 있다.
- region exact solve가 개선을 만들지 못해도 다음 region 크기는 항상 고정된
  `regionGrowth`만큼만 증가한다.
- 다음 MBE width가 현재 목표 residual에 거의 기여하지 않아도 설정한 순서대로 시도한다.
- 전체 exact solve가 현재 자원 한도 아래 매우 저렴해도 먼저 bounded work를 확인해 바로
  닫는 전용 경로가 없다.
- 기존 region exact solve가 resource limit에서 거절되면 해당 실행은 즉시 종료하며, 더 큰
  누적 coverage가 다른 구조를 만들어 통과할 가능성을 시험하지 않는다.

Target Anytime는 기존 공통 인증 상태를 재사용하면서 실행 제어를 다음처럼 바꾼다.

```text
Regional feasible seed와 초기 global MBE
→ 전체 exact work를 한 번 사전 계산
→ 저렴하면 즉시 Global exact closure
→ 아니면 아직 유용한 다음 MBE width를 최대 한 번 계산
→ cumulative region을 확장해 exact Regional 개선
→ U가 내려가면 기본 growth로 복귀
→ U가 내려가지 않거나 region이 거절되면 다음 growth를 두 배로 가속
→ threshold, 시간, region 또는 resource 한도에서 종료
```

이 구현은 기존 세 알고리즘 `THRESHOLD`, `TARGET_GAP`, `REUSE`의 정책 파일을 수정하지
않았다. 공통 enum, trace, 통계 필드와 typed whole preflight만 확장했다.

## 2. 인증 대상과 수학적 계약

현재 feasible plan $P$의 canonical modeled cost를 $U=C(P)$, 동일한 finite encoded
model의 전역 최적값을 $C^*$, global relaxation의 유효한 하한을 $L$이라고 한다.

\[
L_k\le C^*\le U_k.
\]

절대 modeled regret의 인증 상한은 $U-L$이다. $L>0$일 때 상대 인증 gap은 다음과
같다.

\[
g_{rel}=\frac{U-L}{L}.
\]

상대 목표를 $\tau$라고 하면 종료 조건은 다음과 동치다.

\[
g_{rel}\le\tau
\quad\Longleftrightarrow\quad
U\le(1+\tau)L.
\]

구현은 곱셈식만으로 성공을 선언하지 않는다. 공통 `RegionalSearchOptimizer.State.reached()`가
절대 gap과 상대 gap을 각각 계산하고 설정한 두 tolerance 중 하나를 만족하는지 확인한다.
`gap`과 `relative`는 `Math.nextUp`을 사용해 binary64 반올림에 대해 위쪽으로 보수적으로
계산한다. $L=0<U$이면 상대 gap은 무한대이고, $L\ge U$일 때만 0이다.

이 인증의 대상은 privacy, placement legality, hard factor, activation auxiliary와 canonical
physical cost surface가 함께 정의하는 encoded model이다. 실제 workload wall-clock 시간이나
비용 모델 자체의 예측 오차는 인증 범위에 포함되지 않는다.

## 3. 기존 Anytime와 Target Anytime의 정확한 비교

| 항목 | 기존 `LegacyAnytime` | 새 `AnytimeTarget` |
| --- | --- | --- |
| 초기 feasible plan | 기존 Regional seed | 동일 seed |
| 초기 LB | 전체 모델의 initial-width MBE | 동일 MBE를 한 번만 계산하고 재사용 |
| $U$ 개선 | 누적 Regional exact solve | 누적 Regional exact solve |
| $L$ 개선 | width를 1씩 올린 global MBE | 목표 residual에 의미 있는 동안만 width를 1씩 올린 global MBE |
| threshold 검사 | 이미 존재 | 동일한 공통 보수적 검사 사용 |
| 일반 종료 구동자 | `rounds`, 시간, region/resource cap | threshold, 시간, region/resource cap |
| `rounds` | iteration 상한 | CONFIG에 남지만 policy가 무시 |
| `maxSteps` | Legacy에는 해당 없음 | 공통 설정에 남지만 policy가 무시 |
| region 증가 | 항상 `regionGrowth`만큼 | 무개선/거절 시 다음 증가량을 두 배로 가속, 개선 시 기본값으로 복귀 |
| exact shortcut | full region에 도달하면 exact | root whole preflight가 허용하면 coverage 전에 exact, 아니면 full region endpoint |
| region work 거절 | resource 종료 | 더 큰 누적 region을 계속 시도하고 유한 cap에서 정직하게 종료 |
| 기존 정책 보존 | 기준 구현 | 별도 selector이므로 Legacy를 변경하지 않음 |

Target Anytime가 `rounds/maxSteps`를 무시한다는 것은 제한 없이 계속 돈다는 뜻이 아니다.
region 크기는 매 attempt마다 증가하며 `maximumRegionVariables`를 넘지 않는다. Post-initial MBE
width도 각각 최대 한 번만 실행되고 `maximumWidth`에서 끝난다. 여기에 soft time budget,
factor/table 한도, whole assignment 한도와 region work 한도가 적용된다. 따라서 작업 집합은
유한하고, 목표가 미달되면 명확한 `TIME_BUDGET`, `REGION_LIMIT` 또는 `RESOURCE_LIMIT`으로
종료한다.

## 4. 공통 초기화와 상태 재사용

[RegionalSearchOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchOptimizer.java)는
모든 새 certified search에 공통인 상태 경계를 제공한다.

1. Regional seed assignment를 immutable copy로 보관한다.
2. `RegionalSearchProblem.evaluate`로 canonical cost를 계산해 `seedUpperBound`와 현재 $U$로
   둔다.
3. `INITIAL` checkpoint를 게시한다.
4. initial width의 global MBE를 한 번 실행한다.
5. 완료한 MBE 하한만 `raiseLower`로 반영하고 `INITIAL_BOUND`를 게시한다.
6. 그 뒤 선택한 policy로 dispatch한다.

Target Anytime는 `nextWidth=initialWidth+1`에서 시작한다. 초기 MBE 결과의 conflict 목록은
첫 region growth의 guidance로 재사용한다. 따라서 새 policy 때문에 initial bound가 중복
계산되지 않는다.

공통 `raiseLower`는 새 하한이 finite, nonnegative이고 현재 $U$ 이하여야 한다. 조건을
만족하면 `max(oldLower, candidate)`만 게시한다. 공통 `accept`는 solver assignment를
canonical evaluator로 다시 평가하고 solver objective와 raw binary64 bit가 같은지 확인한다.
후보 비용이 $L$보다 작으면 모델 또는 계산 불일치로 처리하며, 현재 $U$보다 엄격히 작을
때만 incumbent를 바꾼다.

## 5. 저렴한 whole exact closure

Target Anytime는 초기 bound 뒤에 root whole exact problem의 구조적 작업량을 한 번 분석한다.
구현은 단순히 원래 결정 수만 세지 않는다.

[RegionalSearchProblem.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchProblem.java)의
`preflightWhole`은 root condition을 만든 뒤 `ExactCategoricalSolver.analyze`를 호출한다. 그
결과에서 다음 값을 받는다.

- elimination assignments
- 전체 materialized factor cells
- maximum factor cells
- hard factor/table resource limit 초과 여부

Root condition은 원래 결정을 하나도 고정하지 않는다. `condition`은 원래 결정 뒤에 있는
모든 encoded auxiliary도 free variable로 남긴다. 따라서 preflight에는 original decision뿐
아니라 auxiliary와 elimination 도중 만들어지는 중간 table 작업도 포함된다.

Whole solve는 다음 조건을 모두 만족할 때만 허용된다.

\[
\text{eliminationAssignments}\le
\texttt{exactClosureAssignments}
\]

그리고 factor/table 크기가 공통 `Limits` 안에 있어야 한다. 허용되면 exact solve를 한 번
실행하고 결과가 feasible이며 기존 incumbent보다 나쁘지 않은지 확인한다. 검증이 끝난
뒤에만 $U$를 반영하고 $L=U$로 닫아 `GLOBAL_EXACT`를 반환한다.

Preflight가 거절된 경우에도 search 전체를 실패시키지 않는다. hard resource limit인지
assignment efficiency cap인지 trace에 구분해서 남기고, MBE와 cumulative Regional 경로를
계속 실행한다.

Preflight는 exact solve의 모든 wall-clock 비용을 완벽히 예측하는 모델은 아니다. 특히 exact
solver 앞뒤의 arc consistency나 quotient 처리 비용 전체를 직접 예측하지 않는다. 이 때문에
해당 gate는 “현재 구조적 work cap 아래에서 실행을 허용하는가”만 판단하며 hard deadline을
보장한다고 주장하지 않는다.

## 6. Threshold-directed MBE 강화

초기 하한 뒤에 다음 width를 실행할지 판단하기 위해 현재 relative-threshold residual을 쓴다.

\[
R(U,L,\tau)=\max\{0,U-(1+\tau)L\}.
\]

Width $w$의 완료된 유효 하한으로 $L$이 $\Delta L$ 증가했다면 다음 width를 허용하는
조건은 다음과 같다.

\[
(1+\tau)\Delta L\ge 0.001\,R(U,L_{before},\tau),
\qquad \Delta L>0.
\]

즉 방금 실행한 width가 목표 residual의 최소 0.1%를 줄일 정도로 기여했을 때만 다음 width를
시험한다. `MINIMUM_RESIDUAL_REDUCTION=0.001`은 현재 코드 상수이며 CONFIG trace에는
`targetBoundMinResidualFraction=0.001`로 기록된다.

이 residual은 **scheduler 전용 값**이다. 일반 binary64 산술로 계산한 residual이 0이거나 작게
나왔다고 인증을 선언하지 않는다. 실제 종료는 앞 절의 보수적인 공통 gap 검사만 사용한다.

각 post-initial width는 최대 한 번 실행된다. 결과가 유효하면 `raiseLower`가 monotone $L$을
유지하고, 다음 width 번호로 이동한다. gain이 기준보다 작거나 resource limit이 발생하면 이후
width를 suspend한다. 작은 양의 gain은 실제로 $L$에 반영하지만 `zeroGainActions`로 세지
않는다. 정확히 0인 gain만 zero gain으로 센다.

이 정책은 “뒤의 더 큰 width에서 큰 LB jump가 절대 없다”고 가정하지 않는다. 0.1% 기준은
현재 목표까지의 planning 비용을 줄이기 위한 heuristic trade-off다. 이를 끄고 모든 width를
계속 시도하는 별도 옵션은 이번 commit에 추가하지 않았다.

## 7. Cumulative Regional coverage

Region $Q$는 `LinkedHashSet<Integer>`로 유지되므로 한 번 포함한 원래 결정은 제거하지 않는다.
매 Regional attempt 전 목표 크기는 다음과 같다.

\[
|Q|_{target}=\min\{\texttt{maxRegion}, |Q|+g\},
\]

여기서 $g$는 현재 growth다. `growRegion`은 MBE conflict 우선순위를 먼저 사용하고 부족하면
factor incidence를 따라 결정적으로 확장한다. 그래도 부족하면 decision index 순서 fallback을
사용한다. 요청한 target에 도달하지 못하면 `ANYTIME_TARGET_COVERAGE_STALLED` 오류를 내어
같은 상태의 무한 반복을 숨기지 않는다.

Regional solve는 region 밖의 **원래 결정만** 현재 incumbent로 고정한다. Region 안의 결정과
모든 auxiliary는 자유롭다. 실행 전에는 별도의 region preflight가 elimination assignments와
materialized cells를 분석하고 `regionWorkLimit` 및 공통 hard limits를 확인한다.

Regional 결과가 incumbent를 엄격히 개선하면 다음 growth를 기본 `regionGrowth`로 되돌린다.
개선하지 못하면 다음 growth를 두 배로 한다.

\[
g_{next}=\min\{2g,\text{remaining decisions}\}.
\]

Region preflight가 work/resource limit으로 거절돼도 동일한 가속 규칙을 적용한다. 이미 누적한
region은 유지되므로 다음 attempt는 더 큰 coverage를 가진다. 이는 거절된 작은 region 하나가
후속 진행 전체를 영구적으로 막지 않게 한다.

`region.size()==decisionCount`인 solve는 region 밖에 남은 원래 결정이 없고 auxiliary가 모두
free이므로 encoded global problem의 exact solve다. 결과의 feasibility와 objective를 검증한
뒤에만 $L=U$로 닫는다. Full region이 work cap 때문에 실행되지 못하면 exact라고 주장하지
않고 `RESOURCE_LIMIT`으로 반환한다.

## 8. 실행 순서와 종료 계약

구현을 의사코드로 정리하면 다음과 같다.

```text
TargetAnytime(model, Regional seed, target, resource options):

    U ← seed의 canonical feasible cost
    L ← 0
    INITIAL checkpoint

    target을 이미 만족하면 반환

    initial global MBE(width0)
    L ← max(L, completed valid lower bound)
    INITIAL_BOUND checkpoint

    target을 만족하면 반환

    wholeWork ← root exact structural preflight
    if wholeWork admitted:
        exact root solve
        feasible/canonical validation
        L ← U ← exact objective
        GLOBAL_EXACT 반환

    Q ← empty cumulative region
    growth ← base regionGrowth
    nextWidth ← width0 + 1
    boundAvailable ← refineBound and nextWidth ≤ maxWidth

    repeat:
        target 또는 soft time budget 검사

        if boundAvailable:
            global MBE(nextWidth)를 최대 한 번 실행
            완료된 유효 LB만 게시
            residual 감소가 0.1% 미만이면 이후 width suspend
            target 또는 soft time budget 재검사

        if |Q| < maxRegion:
            Q를 current growth만큼 누적 확장
            region preflight 후 exact Regional solve
            improvement이면 growth를 base로 복귀
            no improvement 또는 work rejection이면 growth 두 배
            target 또는 soft time budget 재검사

        if full original coverage solve가 완료되면:
            검증 후 L = U, GLOBAL_EXACT 반환

        if 더 진행할 region/width가 없으면:
            REGION_LIMIT 또는 RESOURCE_LIMIT 반환
```

종료 사유의 의미는 다음과 같다.

| 종료 사유 | 의미 |
| --- | --- |
| `TARGET_REACHED` | 보수적 절대 또는 상대 인증 threshold를 만족 |
| `GLOBAL_EXACT` | zero-cost seed 또는 검증된 whole/full-region exact closure |
| `TIME_BUDGET` | phase 사이에서 확인한 soft scheduling budget 소진 또는 cancellation |
| `REGION_LIMIT` | configured maximum region이 전체 결정 수보다 작고 더 유효한 작업이 없음 |
| `RESOURCE_LIMIT` | 전체 coverage에 도달했지만 solve work/resource gate를 통과하지 못했거나 더 진행할 bound가 없음 |

## 9. 시간과 자원 제한의 정확한 의미

`timeMillis`는 seed plan을 얻은 뒤 시작하는 scheduling budget이다. `State.expired()`는 thread
interrupt 또는 elapsed time을 확인한다. MBE는 cancellation supplier를 전달받지만 exact
Regional/whole solve는 실행 중 강제 선점 가능한 hard timeout이라고 볼 수 없다. CONFIG에도
`budgetScope=after-seed softDeadline=true`를 명시한다.

따라서 phase 시작 전에는 예산 안이었지만 exact phase가 끝난 시점에는 예산을 초과할 수 있다.
실험 harness의 outer JVM watchdog은 별도 운영 경계이며 알고리즘 내부 인증의 일부가 아니다.
Watchdog이 JVM을 종료한 경우 부분 trace를 완성된 certificate로 받아들이면 안 된다.

자원 제한은 다음처럼 나뉜다.

| 설정 | 기본값 | 역할 |
| --- | ---: | --- |
| `factorCells` | 1,000,000 | 개별 factor/table 최대 cell 수 |
| `totalCells` | 5,000,000 | 전체 materialized cell 수 |
| `exactClosureAssignments` | 100,000 | root whole exact shortcut의 elimination assignment cap |
| `regionWorkLimit` | 100,000 | 각 cumulative Regional exact attempt의 work cap |
| `maxRegion` | 64 | 누적 region에 포함할 원래 결정의 최대 수 |
| `maxWidth` | 8 | post-initial global MBE의 최대 width |
| `timeMillis` | 1,000 ms | seed 이후 soft scheduling budget |

`exactClosureAssignments=0`으로 두면 root whole shortcut을 사실상 비활성화할 수 있다. 이 경우에도
Target Anytime의 cumulative full-region endpoint는 `regionWorkLimit`과 hard table limit 아래에서
허용될 수 있다. 이 구분은 후속 ablation에서 whole shortcut 효과와 coverage 정책 효과를
분리하기 위해 필요하다.

## 10. 설정과 선택 방법

Target Anytime는 certified Regional의 `mode=anytime`에서만 선택할 수 있다. 핵심 system
properties는 다음과 같다.

```text
-Dsysds.fedplanner.regional.mode=anytime
-Dsysds.fedplanner.regional.algorithm=anytime-target
-Dsysds.fedplanner.regional.width=2
-Dsysds.fedplanner.regional.maxWidth=8
-Dsysds.fedplanner.regional.regionGrowth=8
-Dsysds.fedplanner.regional.maxRegion=64
-Dsysds.fedplanner.regional.timeMillis=1000
-Dsysds.fedplanner.regional.relativeGap=0.01
-Dsysds.fedplanner.regional.absoluteGap=0
-Dsysds.fedplanner.regional.factorCells=1000000
-Dsysds.fedplanner.regional.totalCells=5000000
-Dsysds.fedplanner.regional.exactClosureAssignments=100000
-Dsysds.fedplanner.regional.regionWorkLimit=100000
```

`rounds`와 `maxSteps`도 공통 CONFIG에 출력되지만 Target Anytime의 종료 제어에는 사용되지
않는다. 이를 숨기지 않도록 trace는 다음 두 필드를 추가한다.

```text
targetIgnoresRoundsSteps=true
targetBoundMinResidualFraction=0.001
```

다른 세 정책에서는 첫 필드가 `false`다. 두 번째 필드는 동일 trace schema를 위해 출력되지만
현재 상수는 Target Anytime scheduler에만 사용된다.

`algorithm=legacy`는 기존 `CertifiedRegionalOptimizer` 경로를 선택한다. `mode=off`는 일반
Regional baseline이고 새 search를 실행하지 않는다. 알 수 없는 algorithm이나 anytime mode와
호환되지 않는 조합은 조용히 fallback하지 않고 설정 오류로 처리한다.

## 11. Trace와 통계

새 policy는 다음 phase를 게시한다.

| phase | 의미 |
| --- | --- |
| `ANYTIME_TARGET_EXACT_ADMITTED` | root exact preflight가 shortcut을 허용 |
| `ANYTIME_TARGET_EXACT_SKIPPED` | root exact preflight가 work 또는 hard limit으로 거절 |
| `ANYTIME_TARGET_BOUND` | post-initial width MBE 완료와 실제 $\Delta L$, residual, 계속 여부 |
| `ANYTIME_TARGET_BOUND_RESOURCE` | 해당 width가 resource limit으로 완료되지 못함 |
| `ANYTIME_TARGET_REGION` | cumulative Regional solve 완료, $\Delta U$, 다음 growth |
| `ANYTIME_TARGET_REGION_SKIPPED` | region preflight가 work/resource limit으로 거절 |
| `ANYTIME_TARGET_GLOBAL_EXACT` | 검증된 root whole 또는 full-region exact closure |
| `STOP_*` | 최종 종료 사유와 마지막 인증 상태 |

추가한 주요 counter는 다음과 같다.

| counter | 의미 |
| --- | --- |
| `wholePreflightCalls` | root whole 구조 분석 호출 수 |
| `wholePreflightSkips` | shortcut 거절 수 |
| `wholeHardResourceSkips` | hard factor/table limit에 의한 거절 수 |
| `wholePreflightAssignments` | 사전 분석한 elimination assignments |
| `wholePreflightMaterializedCells` | 사전 분석한 materialized cells |
| `wholeClosureAttempts` / `wholeClosureCompleted` | 실제 whole exact 시도와 검증 완료 수 |
| `coverageAttempts` | 누적 region 확장/solve 시도 수 |
| `coverageVariables` | 마지막 누적 region의 원래 결정 수 |
| `zeroUbGrowthAccelerations` | 무개선 또는 거절 때문에 growth를 가속한 횟수 |
| `boundWidthPasses` | post-initial width 시도 수 |
| `boundWidthSuspensions` | plateau 또는 resource limit으로 추가 width를 멈춘 횟수 |

공통 `Statistics` map에 필드를 추가했으므로 Algorithm 1/2/3 trace에도 사용하지 않은 필드가
0으로 출력될 수 있다. 이는 기존 정책의 실행 의미나 결과를 바꾸지 않으며 동일한 7-way trace
schema로 비교하기 위한 변경이다.

## 12. 구현 파일별 변경

### 12.1 새 파일

- [TargetAnytimeOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/TargetAnytimeOptimizer.java)
  - root whole preflight와 exact shortcut
  - residual-qualified MBE width schedule
  - cumulative region과 adaptive growth
  - full coverage exact closure 및 종료 판정
- [TargetAnytimeOptimizerTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/TargetAnytimeOptimizerTest.java)
  - policy 진행, resource 경계, 기존 Legacy 보존, certificate invariant 테스트 9개

### 12.2 수정 파일

- [RegionalSearchOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchOptimizer.java)
  - `ANYTIME_TARGET` enum, property parser와 dispatch
  - whole preflight wrapper와 관련 telemetry
  - 새 공통 counter
- [RegionalSearchProblem.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchProblem.java)
  - cancellation과 hard/efficiency cap을 구분하는 typed `preflightWhole`
- [FederatedPlanLocalCost.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FederatedPlanLocalCost.java)
  - 새 policy의 설정값과 soft deadline 계약을 CONFIG trace에 기록
- [RegionalSearchProblemTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchProblemTest.java)
  - `anytime-target` 설정 parsing과 기존 invalid-mode 경계 검증
- [RegionalSearchPhysicalIntegrationTest.java](../src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/RegionalSearchPhysicalIntegrationTest.java)
  - enum 전체 순회 및 configured receipt 대상에 새 policy 포함

Commit 통계는 production/test 7개 파일, 447 insertions, 5 deletions다.

## 13. 테스트와 빌드 근거

so007에서 Java 17.0.20과 Maven 3.9.7을 사용해 clean package를 실행했다. 정확한 명령 인자는
[build-command.json](/home/mchoi/so007-sevenway-evidence-20260908/validation/build-command.json)에
보존돼 있다. 선택한 19개 test class의 결과는 다음과 같다.

| 결과 | 값 |
| --- | ---: |
| Test classes | 19 |
| Tests | 136 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |
| Maven 결과 | `BUILD SUCCESS` |
| Maven total time | 56.454 s |

요약은 [test-summary.json](/home/mchoi/so007-sevenway-evidence-20260908/validation/test-summary.json),
전체 로그는 [maven-package.log](/home/mchoi/so007-sevenway-evidence-20260908/validation/maven-package.log)에
있다. 이 build에서 snapshot한 실행 JAR의 SHA-256은
`0b896e12ebbdf0a061bece458e826ac02923eeadae0307fea143995e5909ed4a`다.
[implementation-provenance.json](/home/mchoi/so007-sevenway-evidence-20260908/validation/implementation-provenance.json)은
구현 commit, base commit, JAR, build log, source manifest와 runtime context를 연결한다.

새 policy에 직접 초점을 둔 테스트는 다음과 같다.

| 테스트 | 검증 내용 |
| --- | --- |
| `targetAnytimeProgressesPastLegacyRoundAndStepLimits` | `rounds=1`, `maxSteps=1`을 넘어 region 1→3→4로 누적 진행해 exact 종료 |
| `plateauSuspendsFurtherWidthsWithoutSuppressingPrimalCoverage` | LB width 중단 뒤에도 Regional coverage 계속 진행, 0.1% 판정 경계 |
| `admittedWholePreflightClosesBeforeCoverage` | whole shortcut이 coverage 전에 정확히 한 번 실행되고 exact closure |
| `initialTargetReturnsWithoutBoundOrExactWork` | 비용 0 seed의 즉시 exact 반환 |
| `regionCapReturnsHonestCertificateWithoutClaimingTarget` | max-region 미달 시 false target 없이 유효 구간 반환 |
| `rejectedRegionDoesNotDisableLargerCoverageAttempt` | 작은 region work 거절 후에도 더 큰 누적 coverage 시도 |
| `hardResourceWholePreflightStillAllowsAffordableRegionalWork` | whole hard-limit 거절이 affordable Regional을 막지 않음 |
| `zeroBudgetStopsBeforeInitialBoundAndTargetPolicyWork` | 0 budget에서 검증된 seed와 $L=0$을 정직하게 반환 |
| `legacyOptimizerStillHonorsItsSingleRoundLimit` | 새 selector 추가 후에도 기존 Legacy의 round 동작 보존 |

`RegionalSearchPhysicalIntegrationTest`는 auxiliary가 존재하는 실제 encoded fixture에서 모든
`Algorithm.values()`를 순회한다. 각 정책이 독립 Global과 같은 exact result에 도달하고, 모든
checkpoint가 독립 optimum을 포함하며, $L$ 비감소·$U$ 비증가·canonical assignment cost
일치를 유지하는지 확인한다. 또한 새 selector가 complete legal planning receipt를 생성하는지와
zero scheduling budget 경계를 확인한다.

독립 코드 검토 결과는
[anytime-target-final.md](/home/mchoi/so007-sevenway-evidence-20260908/reviews/anytime-target-final.md)에
있으며 최종 판정은 `CLEAR`다. 검토는 false lower bound, 잘못된 `L=U` closure, auxiliary 고정,
무한 scheduling loop가 없는지를 중점적으로 확인했다. 해당 검토 문서는 commit 직전 snapshot을
읽었기 때문에 새 파일이 당시 untracked였다는 주의 문구를 포함한다. 이후 source와 test는
구현 commit `370d6a8...` 및 위 JAR에 포함됐고, provenance와 clean package로 별도 확인했다.

별도의 native JVM smoke는 KMeans, PCA, L2SVM의 LAN 조건에서 7개 방법 21행이 모두 planning-only
receipt를 완료했음을 확인했다. 이 smoke는 draft protocol, 반복 1회, 2초 budget의 기능 점검이므로
방법 간 성능 우열의 근거로 사용하지 않는다. 전체 16 workload × 4 network profile × 7 method
측정이 완료되기 전에는 Target Anytime가 Legacy나 Algorithm 1/2/3보다 빠르다고 주장하지 않는다.

## 14. Algorithm 1/2/3과의 관계

이번 Target Anytime는 사용자 제안 세 가지를 다시 구현한 네 번째 복제본이 아니다. 공통
certificate와 Regional/MBE primitive를 사용하지만 탐색 상태와 강화 방식이 다르다.

| 정책 | 핵심 메커니즘 | Target Anytime와의 차이 |
| --- | --- | --- |
| Algorithm 1 `THRESHOLD` | 명시적 replica relaxation과 equality의 누적 복원, primal/LB action 선택, 강제 coverage | Target Anytime는 replica/equality를 만들지 않고 기존 global MBE width만 제한적으로 강화 |
| Algorithm 2 `TARGET_GAP` | best-bound frontier, 복수 후보 all-value probing, 조건부 Regional, 선택 partition 분기 | Target Anytime는 frontier나 branch condition을 만들지 않고 단일 cumulative region을 확장 |
| Algorithm 3 `REUSE` | 한 변수 all-value 조건부 MBE, 부모 LB 강화, 조건부 Regional, 계산한 자식 재사용 | Target Anytime는 child cache나 branching 없이 global MBE와 incumbent 주변 Regional만 사용 |

특히 이전 설계 문서에서 제안했던 **nested replica equality 복원**을 Target Anytime가 구현했다고
해석하면 안 된다. 그 메커니즘은 Algorithm 1의 `NestedMiniBucketRelaxation`에 있다. Target
Anytime의 $L$ 단조성은 각 completed MBE 하한을 `max`로 게시하는 공통 계약에서 나오며,
width 증가 자체가 raw bound의 단조성을 보장한다고 가정하지 않는다.

또한 이번 commit은 다음 파일의 정책 로직을 변경하지 않았다.

- `CertifiedRegionalOptimizer.java` — 기존 Legacy Anytime
- `AdaptiveThresholdOptimizer.java` — Algorithm 1
- `BranchingRegionalOptimizer.java` — Algorithm 2/3
- `LocalPhysicalOptimizer.java` — 공통 seed 경로

공통 statistics에 새 0-valued 필드가 추가되고 selector/dispatch가 확장된 것은 있지만, A/B/C의
작업 순서나 frontier 의미를 변경하지 않았다.

## 15. 알려진 한계와 성능 검증에서 분리할 요소

첫째, root whole shortcut은 작은 문제에서 매우 효과적일 수 있지만 이후 cumulative policy를
실행하지 않고 곧바로 Global exact로 닫는다. 따라서 practical campaign에서 Target Anytime의
성공률이 높더라도 `wholeClosureCompleted` 비율을 함께 보지 않으면 adaptive region 정책의
기여로 잘못 해석할 수 있다. `exactClosureAssignments=0`인 별도 ablation이 필요한 이유다.

둘째, MBE 0.1% plateau 기준은 heuristic이다. 한 width의 작은 gain 뒤에 더 큰 width에서 큰
gain이 생기는 문제도 가능하다. 현재 구현은 planning 비용을 제한하는 쪽을 선택하며, 이
trade-off의 효과는 workload별 time-to-threshold로 평가해야 한다.

셋째, 무개선 시 growth를 두 배로 하면 full coverage에 빨리 접근하지만 유용한 중간 region
크기를 건너뛸 수 있다. 반대로 개선이 있으면 기본 growth로 돌아가 세밀한 local improvement를
계속 허용한다. 어느 쪽이 유리한지는 factor graph와 network cost profile에 따라 달라질 수 있다.

넷째, soft budget은 hard wall-clock deadline이 아니다. exact phase가 시작된 뒤에는 설정 시간을
넘길 수 있다. 실험은 planner-reported phase time, native JVM wall time, watchdog termination을
구분해 기록해야 한다.

다섯째, `maxRegion<decisionCount`이면 충분한 자원이 있어도 global endpoint에 도달할 수 없다.
이때 `REGION_LIMIT`은 현재 certificate가 무효라는 뜻이 아니라 configured coverage 아래에서는
목표를 더 이상 증명하지 못했다는 뜻이다.

여섯째, 이 구현 검증은 source correctness와 planning receipt 경로를 확인한다. 전체 7-way
campaign이 아직 완료되지 않았으므로 다음 항목은 이 보고서에서 결론 내리지 않는다.

- Legacy 대비 target 달성률과 time-to-threshold
- 네 network profile에서의 상대적 우위
- 16개 workload 전체의 peak RSS와 planning cost
- root shortcut 제거 시 cumulative policy 자체의 효과
- Algorithm 1/2/3 대비 가장 효과적인 방법

## 16. 현재 결론

구현 관점에서 Target Anytime는 기존 Anytime의 $U/L$ 개선과 threshold 인증을 그대로
유지하면서, 목표 미달인데 임의의 round/step 수 때문에 끝나는 경로를 제거했다. 작은 전체
문제는 구조적 work gate 뒤 exact로 조기에 닫고, 그렇지 않은 문제는 global MBE의 기여가
작아지면 추가 width 비용을 멈춘 뒤 cumulative Regional coverage를 계속 넓힌다. 무개선과
resource skip은 더 큰 coverage로 진행하는 신호로 사용하며, 모든 exact closure는 feasibility와
canonical objective 검증 뒤에만 $L=U$로 게시한다.

so007 clean package의 136개 테스트와 독립 코드 검토는 이 구현의 certificate·resource·numeric
계약이 코드 수준에서 유지됨을 뒷받침한다. 성능 추천은 7-way planning campaign의 성공/실패
전체를 포함한 결과가 나온 뒤 별도 결과 보고서에서 결정해야 한다.
