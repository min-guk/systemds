> **검토 상태 (2026-10-05): 아래는 제안 원문이며 승인된 구현 명세가 아닙니다.**
> [비판적 검토·선별 수정 기록](COST_ESTIMATOR_SELECTIVE_REPAIR_2026-10-05_KO.md)을 우선합니다.
> R52 구현 범위는 실행 worker 수, AggUnary coordinator topology, exact-shape FLOPs 보정입니다.
> 나머지 항목의 최신 판정은 [현재 소스 기반 통일 재검토](COST_ESTIMATOR_REMAINING_UNIFICATION_REVIEW_2026-10-05_KO.md)를 참고하세요. 후속 검토는 아직 구현하지 않았습니다.
> 특히 §9의 `q=1`은 기존 코드에서 응답 수가 아니라 **전체 출력 한 벌에 해당하는 payload 배수**입니다.
> §6의 RTT 일괄 반감/고정 단계 삭제, §3·4·10의 보정 일괄 삭제는 채택하지 않습니다.
> 원문을 보존하되, 아래 식/주장을 모두 검증된 사실로 인용하지 마세요.

맞아. 지금까지 나온 내용을 전부 합치면, **단순히 network만 통일하는 게 아니라 cost estimator 전체를 한 번 정리해야 합니다.** 핵심은 “예외를 계속 덧붙이는 구조”를 버리고, **실제 physical execution에서 compute/memory/network 양을 먼저 구한 뒤 공통 cost primitive를 적용**하는 겁니다.

## 목표로 할 최종 구조

공통 primitive는 두 개만 둡니다.

\[
\boxed{
C_{\mathrm{exec}}
=
\max(C_{\mathrm{comp}},C_{\mathrm{mem,in}})
+
C_{\mathrm{mem,out}}
}
\]

\[
\boxed{
C_{\mathrm{net}}^{r}(x)
=
C_{\mathrm{lat}}^{r}
+
C_{\mathrm{bw}}^{r}(x)
+
C_{\mathrm{codec}}^{r}(x)
}
\]

여기서 \(r\in\{L\to F,F\to L,F\to F\}\).

그다음:

\[
\boxed{
C_{\mathrm{op}}^L=C_{\mathrm{exec}}^L
}
\]

\[
\boxed{
C_{\mathrm{op}}^F
=
C_{\mathrm{exec}}^F
+
C_{\mathrm{net}}^{L\to F}
+
C_{\mathrm{net}}^{F\to L}
+
C_{\mathrm{exec}}^{L,\mathrm{coord}}
}
\]

그리고 operator 밖 movement는

\[
\boxed{
C_{\mathrm{move}}(P)
=
\sum_x w_x(P)C_{\mathrm{net}}^{r_x}(x)
}
\]

입니다.

---

# 1. Worker cost: `whole cost / W`를 없애기

현재 가장 큰 문제입니다.

지금 일반 FED 경로는 대략

\[
C_{\mathrm{worker}}
=
\frac{
\max(C_{\mathrm{comp}},C_{\mathrm{mem,in}})
+
C_{\mathrm{mem,out}}
}{W}
\]

식입니다.

그러면 compute뿐 아니라 **replicated input read와 output write까지 전부 \(W\)로 나뉩니다.** 현재 구현이 실제로 local primitive 전체를 \(W\)로 scaling한다는 점은 보고서에서도 확인됐습니다. Pasted markdown

이걸 다음처럼 바꿔야 합니다.

\[
\boxed{
C_{\mathrm{exec}}^F
=
\max
\left(
\frac{F_{\mathrm{worker}}}{\rho},
\frac{D_{\mathrm{in,worker}}}{\mu}
\right)
+
\frac{D_{\mathrm{out,worker}}}{\mu}
}
\]

즉:

> **Cost를 \(W\)로 나누는 게 아니라, worker 하나가 실제 처리하는 FLOPs와 bytes를 먼저 계산한다.**

예를 들어 partitioned input \(D_p\)와 replicated input \(D_r\)가 같이 있으면:

\[
D_{\mathrm{in,worker}}
\approx
\frac{D_p}{W}+D_r.
\]

이게 물리적으로 훨씬 자연스럽습니다.

---

# 2. \(W\)도 actual operator worker count로

현재 generic FED compute가 사용하는 \(W\)는 항상 해당 operator의 worker 수가 아니라 **graph 전체에 등장하는 worker union**입니다. Pasted markdown

이것도 바꿉니다.

\[
\boxed{
W_o
=
|\operatorname{Workers}(\beta(o))|
}
\]

즉 selected physical alternative가 실제 사용하는 worker pool의 크기.

그러면 7-worker graph 안에서 3개 worker만 사용하는 operator는 정확히 \(W_o=3\)을 씁니다.

---

# 3. 기존 `/W 예외`들을 가능한 한 없애기

현재는 먼저 `/W`를 하고 나서:

- all-local/BROADCAST
- 일부 ternary
- Nary cell op
- Indexing
- transpose

등을 다시 unscaled 처리합니다. Pasted markdown

이 구조 자체가 복잡합니다.

새 방식에서는 각 operator가 실제 worker에서 처리하는

\[
F_{\mathrm{worker}},
D_{\mathrm{in,worker}},
D_{\mathrm{out,worker}}
\]

를 제대로 계산하면, 상당수 `/W` exception이 **자연스럽게 사라집니다.**

예를 들어 broadcast input은 처음부터 worker마다 전체 input을 읽는 것으로 계산하면 되므로 “broadcast이면 unscaled” 같은 후처리가 필요 없어집니다.

물론 operator-specific physical semantics는 남습니다. 하지만 그걸 **scaling exception**으로 표현하지 말고 **work량 산출 규칙**으로 표현하는 게 좋습니다.

---

# 4. Generic `floor`를 제거

현재 원고처럼

\[
C_{\mathrm{exec}}
=
\max
\left\{
F/\rho,
c_{\mathrm{floor}},
D_{\mathrm{in}}/\mu
\right\}
+
D_{\mathrm{out}}/\mu
\]

로 일반적인 `kernel floor`를 두면 안 됩니다.

실제 코드에서 확인된 것은 일반적인 kernel-class minimum cost가 아니라:

- WDivMM rank-aware FLOP lower bound
- WDivMM supplemental time lower bound
- DML FunctionOp cell-count lower bound

같은 **특수 operator correction**입니다. Pasted markdown

따라서 기본식은:

\[
\boxed{
C_{\mathrm{exec}}
=
\max
\left(
F/\rho,
D_{\mathrm{in}}/\mu
\right)
+
D_{\mathrm{out}}/\mu
}
\]

로 단순화합니다.

그리고 정말 필요한 operator만:

\[
F_o
\leftarrow
\max(F_o,F_o^{\mathrm{special}})
\]

처럼 보정합니다.

즉:

> **generic floor는 제거하고, 실제 runtime kernel 차이를 보정하기 위한 명시적인 operator-specific lower bound만 유지.**

---

# 5. Network cost를 하나로 완전히 통일

현재는 upload, download, native result, reusable GET 등이 서로 다른 helper를 쓰면서 약간씩 다른 식을 갖습니다.

이걸 전부:

\[
\boxed{
C_{\mathrm{net}}^r(x)
=
\ell_r
+
\frac{D_x^{\mathrm{wire}}}{B_r}
+
\frac{D_x^{\mathrm{codec}}}{S_r}
}
\]

하나로 통일합니다.

단위 변환만 구현에서 ms/MB convention에 맞추면 됩니다.

여기서 중요한 건:

- \(r\): direction
- \(\ell_r\): **one-way latency**
- \(D^{wire}\): network critical path가 실제 운반하는 bytes
- \(D^{codec}\): serialization/deserialization이 실제 처리하는 bytes
- \(B_r\): directional bandwidth
- \(S_r\): directional codec throughput

입니다.

---

# 6. RTT 기반 fixed stage → one-way latency로 변경

현재 configuration은 RTT에서 latency 값을 만들고, FED instruction에 fixed stage를 한 번 부과합니다. Pasted markdown

이걸 없애고 **directional one-way latency**로 통일합니다.

\[
\boxed{
\ell_{L\to F},
\qquad
\ell_{F\to L}
}
\]

실측 one-way 값이 없고 RTT만 있다면 우선

\[
\ell_{L\to F}
=
\ell_{F\to L}
=
\mathrm{RTT}/2
\]

로 derive할 수 있습니다.

그러면:

\[
C_{\mathrm{net}}^{L\to F}
=
\ell_{L\to F}
+
C_{\mathrm{bw}}^{L\to F}
+
C_{\mathrm{codec}}^{L\to F},
\]

\[
C_{\mathrm{net}}^{F\to L}
=
\ell_{F\to L}
+
C_{\mathrm{bw}}^{F\to L}
+
C_{\mathrm{codec}}^{F\to L}.
\]

더 이상 별도의

> `fixed FED latency once per instruction`

이라는 특수 규칙이 필요 없습니다.

---

# 7. Control cost는 network latency와 분리

현재는 `latency + control`을 fixed stage처럼 묶습니다.

새 모델에서는 실제 의미를 분리하는 게 좋습니다.

- network propagation/request delay → \(C_{\mathrm{lat}}\)
- coordinator/runtime CPU bookkeeping → coordinator execution

즉 control이 실제 CPU/runtime overhead라면

\[
\boxed{
C_{\mathrm{exec}}^{L,\mathrm{coord}}
=
C_{\mathrm{control}}
+
C_{\mathrm{reduce/assemble}}
}
\]

쪽으로 옮기는 게 더 자연스럽습니다.

그러면 정말로:

\[
\boxed{
\text{Execution}=\text{compute + memory}
}
\]

\[
\boxed{
\text{Network}=\text{latency + bandwidth + codec}
}
\]

라는 구분이 깨끗하게 유지됩니다.

---

# 8. Wire bytes와 codec bytes를 명시적으로 계산

현재 ordinary collection은 대략

\[
D_{\mathrm{wire}}=D/f,\qquad
D_{\mathrm{codec}}=D
\]

인데, native return/reusable GET에서는

\[
D_{\mathrm{wire}}=D/f,\qquad
D_{\mathrm{codec}}=D/f
\]

를 쓰는 경우가 있습니다. Pasted markdown

이걸 helper 종류에 따라 하드코딩하지 말고 실제 concurrency 의미에서 계산해야 합니다.

예를 들어 worker-side codec이 parallel이면

\[
D_{\mathrm{codec}}^{crit}
=
\max_i D_i,
\]

coordinator가 모든 payload를 serially decode하면

\[
D_{\mathrm{codec}}
=
\sum_i D_i.
\]

즉:

> **wire parallelism과 codec parallelism을 명시적으로 모델링.**

---

# 9. AggUnary의 `q`와 \(W\) 혼용 수정

이건 실제 버그 후보입니다.

일부 aggregate에서는 실제 returned payload 개수를

\[
q=1
\]

로 판단하면서 network fan-in과 reduction에는 여전히 \(W\)를 씁니다. Pasted markdown

새 모델에서는:

\[
\boxed{
q_o
=
\text{actual number of returned partial results}
}
\]

하나만 사용합니다.

그러면:

\[
D_{\mathrm{wire}}^{F\to L}
=
\text{critical payload implied by }q_o
\]

그리고 coordinator aggregation도

\[
C_{\mathrm{coord}}
=
C_{\mathrm{reduce}}(q_o).
\]

즉:

- actual partial 1개 → fan-in 1, reduction 없음
- actual partial \(W_o\)개 → fan-in \(W_o\), \(W_o-1\) merge operations

로 일관됩니다.

---

# 10. Aggregate / Indexing cap도 재검토

현재 일부 aggregate/indexing은

\[
C
\leftarrow
\min(C_{\mathrm{default}},C_{\mathrm{reduced/slice}})
\]

와 같은 cap을 사용합니다. Pasted markdown

새 estimator에서 실제:

- worker FLOPs
- worker input bytes
- worker output bytes
- returned result bytes

를 제대로 계산하면, **이 cap 중 일부는 더 이상 필요하지 않을 수 있습니다.**

따라서 무조건 삭제하지 말고:

1. 실제 runtime kernel이 정말 reduced output만 처리하는지 확인
2. 맞다면 그 값을 처음부터 \(F_{\rm worker},D_{\rm out,worker}\)에 반영
3. 그러면 사후 `min` cap 제거

하는 게 좋습니다.

즉 `cap`도 가능하면 **physical quantity calculation으로 흡수**합니다.

---

# 11. Movement와 materialization을 하나로 통합

이건 그대로 갑니다.

둘 다 network transfer입니다.

\[
\boxed{
C_{\mathrm{move}}(P)
=
\sum_{x\in X(P)}
w_x(P)
C_{\mathrm{net}}^{r_x}(x)
}
\]

여기서

\[
w_x(P)=\text{expected number of actual transmissions}.
\]

Reuse가 없으면 매번 전송:

\[
w_x=\text{movement execution count}.
\]

Reuse가 있으면 같은 source lifetime에서 한 번:

\[
w_x=\text{number of source lifetimes requiring creation}.
\]

현재 materialization activation 구현도 본질적으로 one-creation transfer price에 lifetime-aware count를 곱하는 방식입니다. Pasted markdown

따라서 별도

\[
C_{\mathrm{mat}}
\]

은 없어도 됩니다.

---

# 12. Shape refinement도 compute/memory에 동일하게 적용

현재 occurrence-aware shape refinement는 bytes는 고치면서 일반 FLOP 계산의 dimensions는 동일하게 교정하지 않을 수 있습니다. Pasted markdown

이것도 통일합니다.

먼저 physical alternative \(d\)에 대해

\[
\boxed{
\operatorname{Shape}(o,d)
}
\]

를 resolve한 뒤, 그 하나의 shape로

\[
F_o,\qquad
D_{\mathrm{in}},\qquad
D_{\mathrm{out}}
\]

을 모두 계산합니다.

그래야 compute와 memory estimate가 서로 다른 shape를 보고 계산되는 일이 없습니다.

---

# 전체적으로 보면 수정은 이렇게 정리됨

| 현재 문제 | 수정 |
|---|---|
| `local cost / W` | actual per-worker FLOPs/bytes 계산 |
| graph-wide \(W\) | operator-specific actual worker count |
| `/W` exception 다수 | layout별 physical work 계산으로 흡수 |
| generic `c_floor` | 제거, 실제 operator-specific correction만 유지 |
| RTT-based fixed stage | directional one-way latency |
| network helper마다 다른 식 | 하나의 \(C_{\rm net}\)으로 통합 |
| latency+control 혼합 | latency는 network, control은 coordinator execution |
| wire/codec payload 혼용 | 각각 명시적으로 계산 |
| AggUnary `q` vs \(W\) | actual returned partial count 하나로 통일 |
| aggregate/indexing cap | actual work/output 모델로 가능하면 흡수 |
| move vs materialization | 같은 transfer + 다른 \(w_x\) |
| compute/memory shape 불일치 | 하나의 resolved physical shape 사용 |

---

## 최종 runtime pipeline

고친 뒤에는 코드도 이 순서로 움직이면 됩니다.

```text
1. Resolve physical alternative
   - execution location
   - actual worker pool
   - input/output layouts
   - physical shapes
   - returned partial count

2. Derive physical quantities
   - worker FLOPs
   - worker input bytes
   - worker output bytes
   - coordinator FLOPs/bytes
   - L→F transfer bytes
   - F→L transfer bytes

3. Execution cost
   Cexec = max(Ccomp, Cmem_in) + Cmem_out

4. Network cost
   Cnet = one-way latency + bandwidth + codec

5. FED operator
   Cop^F =
       Cexec^F
     + Cnet^{L→F}
     + Cnet^{F→L}
     + Cexec^{L,coord}

6. Explicit movement
   Cmove = Σ w_x Cnet(x)

7. Whole plan
   Cost = Cop + Cmove
```

### 핵심 철학 하나로 요약하면

**현재 코드:**
`generic cost를 먼저 만든 뒤 /W, floor, cap, exception을 계속 붙임`

**수정 코드:**
`physical plan에서 실제 FLOPs/bytes/workers/transfers를 먼저 계산한 뒤 공통 Cexec/Cnet 식에 넣음`

이 방향으로 바꾸면 **floor, worker scaling, aggregate 예외, network helper 차이, materialization**이 전부 훨씬 일관되게 정리됩니다.
