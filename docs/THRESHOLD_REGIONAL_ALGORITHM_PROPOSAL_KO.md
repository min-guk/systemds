# Threshold Regional: 목표 오차 인증을 위한 적응형 Regional 알고리즘 제안

| 문서 정보 | 값 |
| --- | --- |
| 작성일 | 2026-09-08 |
| 대상 프로젝트 / 서버 | Cofee / SystemDS, so007 (`dams-so007`) |
| 문서 성격 | **알고리즘 설계 제안. 아래 새 기능은 아직 구현·실험하지 않음.** |
| 기반 구현 | Certified / Anytime Regional, implementation commit `6a2cf13a2253f0f1de0624a3de928cb62302fa3a` |
| 근거 | 기존 소스, 완료된 KMeans planning pilot, 앞선 알고리즘 제안 및 문헌 검토 |
| 실험 범위 | 후속 검증도 planning-only. 이 보고서 작성 중 새 빌드·실험을 실행하지 않음. |

## 1. 제안의 목적과 핵심

사용자가 원하는 것은 저렴하게 초기 인증 구간을 얻고, 원하는 threshold에 도달할 때까지 계획을 개선하거나 lower bound를 강화하는 알고리즘이다. 이를 **Threshold Regional**이라고 부른다.

현재 feasible plan의 modeled cost를 U, 동일 encoded model의 최적 비용을 C*, 그 최적 비용의 유효한 하한을 L이라고 하자. 알고리즘은 다음 구간을 유지한다.

\[
L_k\le C^*\le U_k.
\]

핵심 제안은 다음 세 가지다.

1. 목표 threshold까지 남은 거리를 명시적인 목적 지표로 삼는다.
2. 계획 비용 U를 낮추는 작업과 하한 L을 높이는 작업 중, 예상 시간당 목표 거리 감소가 큰 작업을 선택한다.
3. 탐색이 작은 개선에 머무르지 않도록 범위 확대를 강제하고, 충분한 자원이 있으면 전체 exact solve까지 진행할 경로를 둔다.

현재 구현에도 threshold 검사는 이미 있다. 이 제안의 차이는 `while gap > threshold`를 추가하는 데 있지 않다. **작업 선택, 하한 강화의 구조, 정체에서 벗어나는 정책**을 바꾸는 것이 핵심이다.

보장의 대상은 기존 privacy, placement legality, auxiliary encoding과 canonical evaluator가 정의한 동일 finite cost model이다. 실제 workload 실행시간, 비용 추정 오차 또는 모델 밖의 모든 계획을 인증하는 것은 아니다.

## 2. 최적 비용의 LB와 오차의 상한

### 2.1 처음 계산하는 L의 의미

처음 구하는 L은 **오차의 lower bound가 아니라 최적 비용 C*의 lower bound**다. 계획 P가 원래 모델에서 feasible하면 U=C(P)는 최적 비용의 upper bound다.

\[
L\le C^*\le U
\quad\Longrightarrow\quad
0\le\underbrace{U-C^*}_{\text{실제 modeled regret}}
\le\underbrace{U-L}_{\text{regret의 인증 상한}}.
\]

U를 낮추면 현재 계획의 실제 modeled regret도 낮아진다. 반면 U를 유지하면서 L만 높이면 계획 자체는 바뀌지 않고, 그 계획의 품질에 대한 인증이 더 정밀해진다.

| 값 | 의미 | 탐색 중 직접 알 수 있는가 |
| --- | --- | --- |
| C* | 동일 finite model의 전역 최적 비용 | 일반적으로 모름. Exact oracle 완료 시 확인 가능 |
| U | 원래 모델에서 검증한 feasible plan 비용 | 알 수 있음 |
| L | 전역 완화 문제에서 얻은 유효 하한 | 알 수 있음 |
| U−C* | 실제 modeled regret | C*를 알아야 정확히 계산 가능 |
| U−L | 절대 regret의 인증 상한 | 알 수 있음 |

### 2.2 상대 threshold

목표 상대 오차를 τ≥0이라고 하자. τ=0.01이면 1% 인증을 요구한다. L>0일 때,

\[
\frac{U-C^*}{C^*}
\le\frac{U-L}{L}.
\]

따라서 종료 조건은 다음과 같다.

\[
\boxed{\frac{U-L}{L}\le\tau}
\quad\Longleftrightarrow\quad
\boxed{U\le(1+\tau)L}.
\]

이 조건을 만족하면 반환 계획은 모델상 τ-optimal이다. 실제 오차가 이미 작더라도 L이 약하면 인증을 못 할 수 있으므로, threshold 미달을 실제 계획 품질이 나쁘다는 뜻으로 해석하지 않는다.

### 2.3 절대 threshold와 경계 조건

절대 modeled regret의 허용치를 δ≥0으로 정하면 종료 조건은 U−L≤δ다. δ의 단위는 cost model의 비용 단위다.

비음수 비용 모델에서 경계 조건은 다음처럼 처리한다.

| 조건 | 처리 |
| --- | --- |
| L>0 | 상대 gap 인증 가능 |
| L=0<U | 상대 인증 gap은 무한대. 절대 gap을 함께 기록하고 개선을 계속함 |
| U=0 | 비음수 모델에서 C*=0이므로 exact 종료 |
| 초기 feasible plan 없음 | 유한한 U를 가진 인증서로 보고하지 않음 |
| NaN, 잘못된 bound, canonical mismatch | 정상적인 threshold 미달로 숨기지 않고 모델/계산 오류로 처리 |

L=0일 때 임의의 epsilon으로 분모를 바꾼 값은 원래 relative optimality guarantee와 같지 않다. 이 보고서의 상대 threshold는 양수 L을 분모로 사용한다.

## 3. 현재 구현과 제안의 차이

현재 [CertifiedRegionalOptimizer.java](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/CertifiedRegionalOptimizer.java)는 Regional seed, MBE, 누적 region exact solve, gap 검사와 checkpoint를 이미 제공한다. 다만 round, width 증가와 region growth가 미리 정한 설정을 따른다.

| 요소 | 현재 구현 | 이번 설계 제안 |
| --- | --- | --- |
| 초기 계획 | 기존 Regional feasible seed | 재사용 |
| 초기 하한 | 작은 width의 global MBE | 재사용 |
| 종료 판정 | absolute/relative gap 검사 존재 | 재사용하되 목표 달성 여부를 결과의 중심에 둠 |
| 작업 순서 | 설정에 따른 bound/region 실행 | 예상 threshold 거리 감소량 / 시간으로 선택 |
| 하한 강화 | width 증가 후 greedy MBE 재계산 | 복원한 consistency를 유지하는 nested relaxation 강화 |
| 게시 하한 | 완료한 유효 하한의 최댓값 | 계속 사용 |
| 진단 | deterministic argmin disagreement | tie를 고려한 진단과 작은 시험 병합을 후보 순위에 활용 |
| Region | 고정 growth/cap의 누적 확장 | 진단·비용에 따른 확장 및 주기적인 강제 coverage 확대 |
| Exact endpoint | 모든 원래 결정의 joint solve가 완료되면 exact | 도달 가능성과 진행 정책을 명시적으로 설계 |
| 새 정책의 성능 | 아직 없음 | 후속 planning-only ablation으로 검증해야 함 |

기존 148개 테스트와 8종 Docker pilot은 기존 Certified / Anytime Regional의 증거다. 새 scheduler, explicit replica relaxation, 강제 진행 정책을 검증한 결과로 재사용해 주장하지 않는다.

## 4. 알고리즘의 상태와 초기화

### 4.1 유지할 상태

| 상태 | 내용 |
| --- | --- |
| P, U | 현재 최선의 feasible plan과 canonical cost |
| L | 지금까지 완료한 유효 global lower bound의 최댓값 |
| RelaxationState | 현재 relaxation, split/replica 식별자, 복원한 consistency 관계, 계산 상태 |
| Q | 누적 region에 포함한 원래 결정들의 집합 |
| ActionHistory | 작업 종류·대상·실제 시간·개선량·실패/자원 제한 기록 |
| Gain/Time estimates | 후보별 예상 개선량과 예상 planning 시간 |
| Coverage state | 강제 확장을 위해 아직 포함하지 않은 원래 결정 정보 |
| Checkpoints | 계획 및 bounds, threshold 달성 여부와 종료 사유 |

`RelaxationState`와 위 작업 이력은 제안하는 데이터 구조이며 현재 Java API에 이미 존재하는 타입 이름이 아니다.

### 4.2 초기 계획과 하한

기존 Regional로 P0를 만들고 원래 hard constraints와 canonical evaluator로 확인한다. 그다음 전체 factor model에 작은 width w0의 MBE를 실행한다.

\[
U_0=C(P_0),\qquad
L_0=\operatorname{MBE}(M,w_0).
\]

하한 계산은 region 밖 assignment를 고정하지 않는다. 모든 원래 결정과 auxiliary를 포함한 global problem을 완화해야 한다. Region 밖을 고정한 conditional optimum은 이 목적의 global lower bound가 아니다.

MBE를 이용해 계산 비용과 bound 품질 사이의 절충을 제공하는 접근은 기존 연구에 기반한다. 이 제안은 MBE 자체의 발명을 주장하지 않는다. [Dechter와 Rish, 2003](https://ics.uci.edu/~dechter/publications/r62.pdf)

초기 MBE가 완료되지 못하면 성공한 하한으로 게시하지 않는다. 기존 모델의 비음수 계약 아래 trivial L=0과 feasible U를 유지할 수 있으며, 해당 실행의 시간/자원 한도 종료를 기록한다.

## 5. 계획 개선과 하한 강화

### 5.1 계획 개선 작업

진단이 지목한 producer, consumer와 shared materialization 주변을 region 후보로 만든다. 후보는 현재 Q를 포함하도록 누적 확장한다.

\[
Q_k\subseteq Q_{k+1}.
\]

Region 내부 원래 결정과 모든 auxiliary를 자유롭게 두고, region 밖 원래 결정은 incumbent의 assignment로 고정하여 exact solve한다.

\[
\alpha'_Q\in\arg\min_{\gamma_Q}
C(\gamma_Q,\alpha_{\bar Q}).
\]

새 계획은 원래 hard feasibility와 canonical cost로 재검증한다. 기존 exact encoding과 canonical objective의 일치 계약도 유지한다. Strict improvement일 때만 계획을 교체한다.

\[
U_{k+1}=\min\{U_k,C(P')\}.
\]

이 과정이 exact여도 밖의 결정을 고정한 상태의 최적화다. 해당 objective를 그대로 global L로 사용하는 것은 허용하지 않는다. 모든 원래 결정이 자유로운 exact endpoint는 별도다.

### 5.2 하한 강화 작업

MBE에서 coupling을 풀어 주는 과정을, 같은 원변수에 대한 여러 replica가 서로 다른 값을 선택할 수 있게 하는 relaxation으로 명시적으로 표현한다.

예를 들어 producer state의 replica가 A와 B에서 독립적으로 선택되던 부분에 다음 equality를 복원할 수 있다.

\[
x^{(A)}=x^{(B)}.
\]

복원한 equality 집합을 Ek라고 하면,

\[
E_k\subseteq E_{k+1}
\]

를 유지한다. 원래 encoded feasible assignment를 replica 공간에 복사하는 사상을 ι라고 하자. 원래 assignment의 모든 replica가 같은 값을 가지므로, 각 relaxation은 원래 feasible model을 포함한다.

\[
\iota(D)\subseteq R(E_{k+1})\subseteq R(E_k).
\]

여기서 D는 원래 encoded feasible set이며, 복사된 assignment에서 목적함수는 보존돼야 한다. 동일 replica 공간과 objective에서 각 relaxation을 정확히 최적화하면,

\[
\operatorname{opt}(R(E_k))
\le\operatorname{opt}(R(E_{k+1}))
\le C^*.
\]

여기서 원변수는 original decision뿐 아니라 auxiliary를 포함한 모든 encoded variable을 뜻한다. 분리된 각 encoded variable의 replica들이 equality로 연결되면 원래 coupling을 회복한다. 그러나 그 문제를 실제로 푸는 비용은 커질 수 있다.

구현 전제는 split마다 replica와 제거한 equality를 구성하고 기존 MBE bound와의 대응 관계를 검증하는 것이다. 현재 MBE 결과를 사후적으로 replica relaxation이라고 부르는 것만으로 이 보장이 생기지는 않는다.

또한 width 한도를 맞추기 위해 이미 복원한 coupling을 다시 풀어 버리면 nested 보장이 깨질 수 있다. 필요한 계산이 자원 한도를 넘으면 이전 성공 상태와 L을 보존하고 그 시도를 제한된 작업으로 기록한다.

일반적인 cluster/consistency 추가에 의한 relaxation 강화는 선행 연구가 있다. LP/MAP 문헌의 특정 dual 개선 보장이 현재 MBE 코드에 자동으로 적용되는 것은 아니므로, 위 모델 대응과 계산 조건을 별도로 확인해야 한다. [Sontag 등, 2008](https://people.csail.mit.edu/tommi/papers/Sontag_etal_UAI08.pdf)

### 5.3 Raw bound와 게시 bound의 구분

Nested 집합이 보장하는 것은 relaxation의 **최적값**의 비감소다. 내부 solver가 불완전한 하한만 반환하면 raw 결과의 단조성은 별도 문제다.

따라서 유효한 새 raw lower bound를 ℓ이라고 할 때, 게시하는 값은 계속 다음과 같이 갱신한다.

\[
L_{k+1}=\max\{L_k,\ell\}.
\]

유효하지 않은 값, 중단된 계산, 원래 모델과 다른 objective의 값은 이 최댓값에 포함하지 않는다. `max`는 잘못된 하한을 유효하게 만들어 주는 장치가 아니다.

### 5.4 Disagreement와 merge loss의 용도

기존 단일 argmin 비교는 tie가 있을 때 같은 최소값을 함께 선택할 수 있는 상황까지 conflict로 볼 수 있다. 제안에서는 가능하면 argmin 집합의 교집합과 작은 시험 병합을 후보 순위에 활용한다.

유한한 비용을 가진 고정 boundary context z에서 두 메시지의 local merge loss는 다음과 같이 정의할 수 있다.

\[
\delta(z)=\min_x[f(x,z)+g(x,z)]
-\min_x f(x,z)-\min_x g(x,z)\ge0.
\]

이 값은 해당 context에서 분리된 결정의 일치를 요구할 때 발생하는 비용 차이다. 전체 문제를 다시 풀면 다른 context로 이동할 수 있으므로 **전역 L의 개선량과 같지 않다**. Hard infeasibility 때문에 무한대 연산이 발생하는 경우도 일반적인 유한 score와 구분해야 한다.

진단값은 작업 순위용이고, 실제 ΔL은 global bound를 재계산한 뒤 확정한다. 진단 계산 시간도 planning 비용에 포함한다.

## 6. Threshold를 기준으로 한 작업 선택

### 6.1 목표까지 남은 거리

다음 residual을 사용한다.

\[
H_k=U_k-(1+\tau)L_k.
\]

양수 H는 아직 인증 목표에 도달하지 않았음을 뜻한다. H 자체는 실제 modeled regret가 아니다. 임의의 threshold τ에 맞춘 종료 조건의 residual이다.

작업 후 감소한 U와 증가한 L을 각각 다음처럼 정의한다.

\[
\Delta U=U_k-U_{k+1}\ge0,
\qquad
\Delta L=L_{k+1}-L_k\ge0.
\]

그러면 목표 거리의 감소량은 정확히 다음과 같다.

\[
H_k-H_{k+1}=\Delta U+(1+\tau)\Delta L.
\]

### 6.2 선택 점수

작업 a의 예상 개선량과 양수 예상 시간으로 다음 score를 계산한다.

\[
\boxed{
S(a)=\frac{\widehat{\Delta U}_a+(1+\tau)\widehat{\Delta L}_a}
{\widehat{T}_a}
}.
\]

분자는 예상 modeled-cost residual 감소량이고 분모는 planning 시간이다. 실행시간을 예측하는 cost model의 단위와 planner 자체의 wall-clock 시간을 혼동하지 않는다.

초기 추정에는 scope/domain 크기, 기존 solver 작업 통계와 제한된 진단을 사용한다. 이후 작업 종류와 크기가 비슷한 실제 시도의 개선량·시간으로 갱신할 수 있다. 구체적인 추정기는 후속 구현과 ablation에서 선택할 항목이다.

개선이 0이거나 실패한 시도도 이력과 비용 추정에 남긴다. 같은 incumbent 경계와 relaxation 상태에서 같은 실패 작업을 무한 반복하지 않게 한다. 반대로 incumbent 경계가 바뀌면 이전 region을 다시 푸는 것이 의미 있을 수 있으므로, 시도 식별에 상태 version을 포함하는 편이 맞다.

이 score는 scheduling heuristic이다. 최단 planning 시간, 최대 누적 개선 또는 전역적으로 최적인 작업 순서를 보장하는 정리는 아니다.

## 7. 정체 처리와 exact endpoint

### 7.1 개선이 없을 때

정체를 감지하면 region을 더 크게 묶거나, 복원할 consistency 범위를 확대한다. 반복적으로 개선이 없는 작은 region을 같은 조건으로 다시 푸는 것은 피한다.

하지만 정체가 나타났을 때만 범위를 키우는 정책은 충분하지 않을 수 있다. 작은 개선이 계속 관측되면 중요한 비싼 작업을 계속 미룰 수 있기 때문이다.

### 7.2 강제 coverage 진행

권장하는 구체화는 다음과 같다. 최대 m회의 일반 작업 선택마다 한 번은, 아직 Q에 들어오지 않은 원래 결정을 적어도 하나 포함하도록 coverage 확장을 강제한다. 그때도 기존 Q는 유지하고 joint solve한다.

이렇게 원래 결정의 coverage를 별도로 보장하면 heuristic score가 exactness에 필요한 확장을 영원히 미루는 일을 막을 수 있다. m은 아직 확정하지 않은 설계 parameter이며, 후속 ablation에서 비용과 효과를 평가한다.

유한한 원래 결정 수, 각 단계의 유한한 완료, 충분한 자원과 이러한 강제 진행을 전제로 하면 결국 모든 원래 결정을 자유롭게 한 exact solve에 도달한다. 완료된 endpoint에서는,

\[
L=U=C^*.
\]

Region coverage가 유한 단계에 완료된다는 사실은 전체 실행시간이 작다는 보장이 아니다. 마지막 joint solve의 계산량은 매우 클 수 있다.

### 7.3 시간·메모리 제한

고정된 width/cell/region cap이 필요한 확장이나 exact solve를 막으면 위 endpoint 도달 조건이 성립하지 않는다. 이 경우 목표 미달과 현재 인증서를 반환해야 한다.

예산 의미도 명시해야 한다. 기존 구현의 scheduling budget은 feasible seed와 초기 canonical 평가 이후 시작하며, exact region solve는 강제 중단하지 못한다. 후속 구현에서는 seed, 초기 MBE, 진단, refinement와 전체 planning 시간을 각각 기록하고, 예산이 어느 구간을 포함하는지 설정과 로그에 남겨야 한다.

이번 제안만으로 hard wall-clock timeout 지원이 생기는 것은 아니다. 중단 가능한 exact solver를 추가하지 않는 한, 실행 중인 phase가 예산을 초과할 수 있으며 실제 초과 시간도 결과에 포함한다.

## 8. 전체 알고리즘과 반환 계약

### 8.1 의사코드

아래 상태명과 함수명은 설계 설명용이다. 현재 구현에 같은 이름의 API가 추가됐다는 뜻은 아니다.

```text
ThresholdRegional(model M, relative threshold τ, budget B):

    P ← 기존 Regional의 feasible plan
    U ← 원래 feasibility와 canonical cost 검증
    L ← 0
    Q ← 빈 region
    초기 checkpoint 저장

    U = 0이면 exact 반환
    자원/예산 범위에서 초기 global MBE 실행
    완료된 유효 하한과 relaxation 상태만 반영

    반복:
        보수적으로 계산한 인증 gap이 τ 이하이면:
            반환 P, [L, U], TARGET_REACHED

        예산이나 진행을 막는 자원 한도에 도달하면:
            반환 P, [L, U], TARGET_NOT_REACHED, 상세 종료 사유

        coupling 진단으로 후보 작업 생성:
            P-action: Q를 포함하는 region의 joint exact solve
            L-action: replica consistency 복원과 global bound 재계산

        강제 coverage 차례이면:
            새로운 원래 결정을 포함하는 P-action 선택
        아니면:
            예상 H 감소량 / 예상 시간으로 작업 선택

        P-action이면:
            후보의 원래 feasibility 및 canonical objective 검증
            strict improvement일 때 P와 U 갱신
            모든 원래 결정의 exact solve가 완료됐으면 L ← U

        L-action이면:
            복원한 consistency를 유지하는 강화 문제 계산
            완료된 유효 global bound만 L ← max(L, 새 bound)

        개선 없음과 실패를 포함해 실제 비용 및 작업 결과 기록
        checkpoint 저장
```

### 8.2 반환 정보

| 필드 | 의미 |
| --- | --- |
| plan / canonical cost | 검증된 incumbent와 U |
| lower / upper | 현재 유효한 인증 구간 |
| absolute / relative gap | 보수적으로 계산한 modeled regret 상한 |
| target / targetReached | 요청한 threshold와 실제 인증 달성 여부 |
| stopReason | 목표 달성, exact, 시간, resource 등의 구분 |
| planning timings | seed, 초기 bound, 진단, region, tightening, 전체 시간 |
| progress trace | 선택 작업, 예상/실제 개선량, Q와 relaxation version, 실패 기록 |

`TARGET_NOT_REACHED`는 모델상 더 나은 계획이 없다는 증명이 아니다. 사용한 예산과 계산 결과로는 요청한 품질을 인증하지 못했다는 뜻이다.

부동소수점에서는 H의 직접 계산만으로 성공을 선언하지 않는다. 기존 구현처럼 하한을 보수적으로 계산하고 gap을 상향 반올림하여 false certification을 피해야 한다. 모델/수치 계약 위반은 정상적인 예산 종료와 구분한다.

## 9. 보장과 증명 개요

### 9.1 전제

1. 동일한 비음수 finite encoded cost model과 원래 legality 조건을 사용한다.
2. 초기 U와 이후 후보 U는 원래 모델에서 feasible한 계획의 canonical cost다.
3. 게시하는 모든 raw lower bound는 해당 global model에 유효하다.
4. 기존 auxiliary encoding과 canonical objective의 동등성 계약을 유지한다.
5. 자원 중단, 수치 처리와 오류 판정이 유효하지 않은 값을 성공으로 게시하지 않는다.

### 9.2 인증 구간과 단조성

Feasible plan 비용은 upper bound다. 유효한 relaxation의 lower bound는 C* 이하이며, 그러한 하한들의 최댓값도 C* 이하이다. 따라서 갱신식으로부터 모든 게시 checkpoint에서,

\[
L_k\le C^*\le U_k,
\quad L_{k+1}\ge L_k,
\quad U_{k+1}\le U_k
\]

를 유지한다. 절대 인증 gap은 비증가하고, 양수 L 구간에서는 상대 인증 gap도 비증가한다.

### 9.3 Threshold 종료의 정확성

L>0이고 U≤(1+τ)L일 때,

\[
U\le(1+\tau)L\le(1+\tau)C^*.
\]

따라서 반환 계획의 relative modeled regret가 τ 이하임을 보장한다. 이 정리는 작업의 개선량 예측이 정확하다는 전제를 요구하지 않는다. 예측이 나쁘면 탐색 효율이 나빠질 수 있지만, 검증된 bounds로만 종료한다면 인증의 정확성은 유지된다.

### 9.4 주장하지 않는 보장

매 반복에서 strict improvement가 발생한다는 보장, 임의의 고정 budget 안에서 threshold에 도달한다는 보장, scheduler가 가장 빠르다는 보장은 없다. Fair coverage와 충분한 자원을 추가해야 eventual exact endpoint를 주장할 수 있다.

위 내용은 설계 계약의 증명 개요다. 새로운 구현을 완료하거나 proof assistant로 전체 프로그램을 기계 검증한 결과가 아니다.

## 10. 기존 KMeans 결과로 본 1% 목표

### 10.1 분석에 사용한 값

다음 값은 이미 완료된 KMeans/P2P2D, PRIVATE_AGGREGATE, worker 1의 planning-only pilot에서 가져왔다. 새 알고리즘을 실행해 얻은 값이 아니다. 하한은 기존 `Anytime` 행을 사용했다.

| 값 | Modeled cost ms |
| --- | ---: |
| L | 28020.954360612595 |
| C* — 독립 Global 결과 | 29057.63845842688 |
| U | 29701.732755786634 |

기존 relative certificate gap은 약 5.998291%, 실제 modeled regret는 약 2.216609%였다. 원본은 [comparison.json](/home/mchoi/so007-certified-regional-evidence-20260908/docker/runs/20260908t0351ablation-kmeans/comparison.json)에 있다.

### 10.2 한쪽만 개선할 때의 한계

현재 U를 고정한 채 L만 높이면, 유효한 하한의 최대는 C*다. 따라서 가장 좋은 경우에도,

\[
\min\text{ certificate gap at fixed }U
=\frac{U-C^*}{C^*}\approx2.216609\%.
\]

현재 L을 고정한 채 U만 낮추면, feasible 비용의 최소는 C*다. 따라서 가장 좋은 경우에도,

\[
\min\text{ certificate gap at fixed }L
=\frac{C^*-L}{L}\approx3.699674\%.
\]

| 개선 방법 | 다른 경계는 현재 값으로 고정 | 가능한 최선의 상대 인증 gap | 1% 도달 |
| --- | --- | ---: | --- |
| L만 개선, 이론적으로 L=C*까지 | U=29701.732755786634 | 2.216609% | 불가능 |
| U만 개선, 이론적으로 U=C*까지 | L=28020.954360612595 | 3.699674% | 불가능 |

따라서 이 사례에서 1% 인증을 얻으려면 U와 L을 모두 개선해야 한다. 이것은 두 경계의 개선이 항상 모든 문제에서 필요하다는 일반 정리가 아니라, 이번 초기 구간과 독립 C*에서 도출한 결론이다.

### 10.3 목표를 만족하려면 필요한 변화

τ=0.01에 대한 조건을 수치로 쓰면 다음과 같다.

| 조건 | 계산한 경계 |
| --- | ---: |
| 현재 U를 고정할 때 필요한 L≥U/1.01 | 29407.656194 |
| 현재 L을 고정할 때 필요한 U≤1.01L | 28301.163904 |
| L을 C*까지 높여도 필요한 U≤1.01C* | 29348.214843 |
| U를 C*까지 낮춰도 필요한 L≥C*/1.01 | 28769.939068 |

첫 행은 C*보다 큰 하한을 요구하고, 둘째 행은 C*보다 작은 feasible 비용을 요구하므로 각 조건에서 단독 개선은 불가능하다.

나머지 두 행은 1% 인증을 위한 필요조건이다. 각각 만족한다고 충분한 것은 아니며, **실제로 반환하는 두 값의 조합이 U≤1.01L을 만족해야 한다.**

현재 U에서 최소한 약 353.517913 modeled ms의 비용 감소는 필요하다. 이 감소만으로 충분하다는 뜻은 아니며, 이를 달성할 region이나 실제 후보 계획의 존재를 이번 분석으로 확인한 것도 아니다.

이 분석은 기존 Global oracle을 알고 난 뒤 수행한 사후 분석이다. 제안 알고리즘의 온라인 작업 선택이나 종료에 C*를 입력으로 요구하지 않는다.

## 11. 후속 planning-only ablation 설계

이 절의 모든 실험은 **아직 수행하지 않은 제안**이다. Workload 실행, worker JVM 실행과 새 CP reference 생성은 포함하지 않는다. 후속 실험은 기존 원칙에 따라 `run_LAN_docker.sh --planning-only` 경로로 수행한다.

### 11.1 비교할 구성

| 구성 | 비교 목적 |
| --- | --- |
| 기존 Anytime | 동일 τ를 적용한 현재 고정 bound/region schedule과 비교 |
| U-only | 초기 L을 고정하고 계획 개선만 수행했을 때의 한계 |
| L-only | 초기 U를 고정하고 하한 강화만 수행했을 때의 한계 |
| 교대 선택 | 새 후보 작업·nested tightening·강제 coverage를 유지하고 두 작업을 교대로 선택 |
| Threshold 적응 선택 | 같은 후보 생성기와 자원 정책에서 H 감소량 / 시간 score로 선택 |
| 적응 선택 + 기존 width 증가 | Nested tightening 대신 기존 width 증가를 사용해 하한 강화 구조의 기여 비교 |
| 적응 선택 + 기존 disagreement | Tie/시험 병합 진단을 기존 단일 argmin 진단으로 바꿔 후보 선택 기여 비교 |
| 적응 선택 − 강제 coverage | 유한 budget에서 정체와 성공률에 미치는 영향 측정. Eventual exactness 주장은 제외 |
| Global | 가능한 cell에서 동일 modeled objective의 독립 최적값 oracle |

교대 선택과 적응 선택 비교에서는 초기 P/L, 후보 생성기, 진단 비용 계상, resource cap과 강제 진행 규칙을 맞춘다. 정책에 따라 이후 방문 경로가 달라질 수 있으므로 매 단계 후보가 동일했다고 가정하지 않고 실제 경로를 기록한다.

단순히 모든 구현에 같은 round 수를 주는 것만으로 공정한 비교가 되지 않는다. 동일 작업 한도를 비교하는 실험과 전체 planning 시간의 비교를 구분한다. Global oracle 계산 시간은 각 방법의 time-to-threshold에 섞지 않는다.

### 11.2 주요 지표

- 요청 threshold의 인증 성공률과 **time-to-threshold**.
- 전체 planning 시간 및 seed, 초기 MBE, 진단, refinement의 시간 분해.
- U/L 및 상대 인증 gap trajectory.
- Global이 가능한 cell의 실제 modeled regret와 certificate slack.
- 작업 종류별 시도·성공·zero gain·실패 수와 예측/실제 ΔH.
- Region membership, 복원한 equality, elimination order와 partition identity.
- Materialized cells, exact/MBE work, 자원 제한과 예산 초과.

Threshold 예시는 5%, 2%, 1%로 둘 수 있다. 이는 확정된 실험 설정이 아니라 후속 protocol에서 정할 후보 값이다. Threshold 미달 행은 빠뜨리지 않고 성공률의 분모에 포함한다. 도달한 행만의 평균 시간에는 선택 편향이 있으므로 개별 trial과 미달성 정보를 함께 보고한다.

기존 KMeans pilot은 각 variant 1회였으므로 새 scheduler의 우위나 통계적 speedup 근거가 아니다. 후속 연구는 여러 seed와 paired repetitions, variant 실행 순서의 균형화를 포함해야 한다. Model fingerprint뿐 아니라 cross-JVM의 실제 elimination/partition 상태도 확인한다.

### 11.3 구현 전후 확인할 정확성 항목

| 대상 | 필요한 검증 |
| --- | --- |
| 초기 relaxation | 기존 MBE bound와 explicit replica model의 대응을 작은 exhaustive oracle로 확인 |
| Consistency 복원 | 원래 feasible assignment가 제거되지 않는지, 복원한 관계가 유지되는지 확인 |
| Bound 게시 | 중단/잘못된 raw bound를 제외하고 모든 checkpoint가 oracle을 포함하는지 확인 |
| 계획 개선 | 원래 hard feasibility와 canonical objective parity 확인 |
| Scheduler | 추정 오차, zero gain, 실패 비용 및 동일 상태 반복 방지 확인 |
| 강제 coverage | 진단 score가 0이거나 오도될 때도 모든 원래 결정을 결국 포함하는지 확인 |
| 종료 | 0 비용, L=0, threshold 경계, TIME/RESOURCE 미달, exact endpoint 확인 |

이 표는 새로 실행한 테스트 결과가 아니라 필요한 검증 계획이다. 기존 모델의 privacy/placement 합법성이나 runtime 규칙을 테스트 편의상 완화하지 않는다.

## 12. 근거와 산출물의 관계

### 12.1 프로젝트 근거

| 자료 | 용도 |
| --- | --- |
| [기존 구현 상세 보고서](CERTIFIED_REGIONAL_IMPLEMENTATION_REPORT_KO.md) | 현재 구현, 전제, 테스트와 완료된 실험의 상세 내용 |
| [기존 결과 요약](CERTIFIED_REGIONAL_RESULTS.md) | 완료된 build와 planning pilot 결과 |
| [기존 ablation 계획](CERTIFIED_REGIONAL_ABLATION_PLAN.md) | 이전 구현·비교 계획 |
| [세션 이슈 기록](SESSION_ISSUES_2026-09-08.md) | 작업 경계, 해결 이력과 잔여 한계 |
| [현재 MBE 코드](../src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/MiniBucketLowerBound.java) | 기존 partition 및 bound 계산 |
| [원본 pilot summary](/home/mchoi/so007-certified-regional-evidence-20260908/docker/runs/20260908t0351ablation-kmeans/summary.json) | 현재 정책별 checkpoint와 실제 작업량 |

### 12.2 문헌과 제안의 경계

[Dechter와 Rish의 MBE 논문](https://ics.uci.edu/~dechter/publications/r62.pdf)은 bounded inference와 계획 품질 bound의 기반이다. [Sontag 등의 연구](https://people.csail.mit.edu/tommi/papers/Sontag_etal_UAI08.pdf)는 cluster/consistency 강화로 relaxation을 조이는 관련 선행 연구다.

이 보고서는 그 기법들을 현재 Regional planner의 canonical cost와 feasibility 계약에 맞추어 연결하는 설계 제안이다. Threshold residual 기반 작업 선택과 강제 진행 정책의 실용적인 효과는 별도 검증 대상이며, 이 조합이 문헌상 최초라는 주장을 하지 않는다.

기존 구현 보고서와 이 문서는 서로 다른 상태를 기록한다. 전자는 이미 완료한 구현·검증 결과이고, 이 문서는 그다음 버전의 알고리즘, 성립 조건과 검증 계획이다.
