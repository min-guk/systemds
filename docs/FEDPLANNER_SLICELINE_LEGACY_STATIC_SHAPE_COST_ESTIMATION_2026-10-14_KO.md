# SliceLine legacy planning과 정적 shape·비용 추정의 역할

작성일: 2026-10-14
범위: 기존 실험 기록·소스 분석과 설계 방향의 문서화. **production 구현 변경이나 새 workload 실행은 하지 않았다.**

## 1. 질문과 핵심 결론

> 이전 legacy planner에서는 SliceLine planning이 가능했다. 모든 중간 shape를 실행 전에 정확히 알 수 없다면, 정적으로 알 수 있는 정보와 default/heuristic shape로 계획해야 하는 것 아닌가?

**이전 planning 성공은 실제 기록으로 확인된다.** 당시 보고서와 run ID에서 prepared ADULT/COVTYPE로 기록한 조건은 초기 planning과 RuntimeProgram 생성을 완료했다. 모든 중간 shape를 실행 전에 정확히 알아냈다는 뜻은 아니다. 아래에서 입력 크기의 보고 출처와 개별 rendered input identity 검증의 한계를 구분한다.

이 문서의 핵심 구분은 다음과 같다.

1. **정확한 중간 shape의 사전 결정과 정적 계획 생성은 같은 요구가 아니다.**
2. Legacy 기준 commit의 비용 경로에는 연산별 worst-case 추정, 메모리 기본값, 미확정 전송량 추정이 존재했다. 성공과 양립하는 메커니즘이지만, 측정 JAR의 각 HOP가 실제 어느 fallback을 사용했는지는 미확인이다.
3. 현재 Memo는 비용 구성에서도 concrete dimension을 요구한다. 이 제한을 static planning 일반의 불가능성으로 확대하면 안 된다.
4. 비용 추정을 허용하더라도 **실행 합법성·privacy·worker binding을 추정으로 증명해서는 안 된다.**
5. 사전 데이터 실행이나 staged planning을 먼저 도입하기보다, **정확한 사실과 비용 추정의 분리**를 검토할 근거가 있다. 단, 이것만으로 SliceLine 지원 완료가 보장되지는 않는다.

기존 [SliceLine 근본 원인 보고서](FEDPLANNER_SLICELINE_PLANNING_BLOCKERS_ROOT_CAUSE_2026-10-14_KO.md)의 정보 부족 반례는 여전히 유효하다. 다만 그 반례는 **나열된 통계만으로 실제 중간 크기를 정확하게 결정할 수 없다**는 뜻이지, **추정 비용을 사용하는 초기 planner가 계획을 만들 수 없다**는 뜻이 아니다.

## 2. 실제 legacy 성공 증거

### 2.1 공식 compile-only 결과

2026-09-21 final805 캠페인, worker=4, 각 조건 1회. 아래 시간은 optimizer 단독 시간이 아니라 로그의 **Total compilation time**이다.

| Prepared workload / 크기: 당시 보고서 기준 | FedFirst | AggLocal | DP-local | DP-global |
|---|---:|---:|---:|---:|
| ADULT 32,561×13 | 7.501851초 | 7.175817초 | 7.721446초 | **7.871327초** |
| COVTYPE 581,012×54 | 7.566769초 | 7.435902초 | 7.371283초 | **7.970540초** |

8개 공식 receipt 모두 `success=true`, `runtime_executed=false`, `execution_seconds=0`, `workers=4`다. 각 receipt에 기재된 coordinator log와 compile config의 SHA-256도 일치한다.

DP-global의 `RuntimeProgram` 단계 시간은 ADULT **0.050265초**, COVTYPE **0.051161초**다. 따라서 단순 rc=0나 optimizer 종료만 확인한 결과가 아니라, 초기 RuntimeProgram 생성까지 완료한 기록이다.

- ADULT: [receipt](evidence/sliceline-legacy-static-planning-2026-10-14/ADULT/mkl-exact/receipt.json), [coordinator log](evidence/sliceline-legacy-static-planning-2026-10-14/ADULT/mkl-exact/coordinator.log), [compile config](evidence/sliceline-legacy-static-planning-2026-10-14/ADULT/mkl-exact/compile.xml)
- COVTYPE: [receipt](evidence/sliceline-legacy-static-planning-2026-10-14/COVTYPE/mkl-exact/receipt.json), [coordinator log](evidence/sliceline-legacy-static-planning-2026-10-14/COVTYPE/mkl-exact/coordinator.log), [compile config](evidence/sliceline-legacy-static-planning-2026-10-14/COVTYPE/mkl-exact/compile.xml)
- 전체 8조건: [결과 집계](evidence/sliceline-legacy-static-planning-2026-10-14/legacy-results.json), [evidence manifest](evidence/sliceline-legacy-static-planning-2026-10-14/manifest.json)

### 2.2 현재 workload와의 관계 및 비교 한계

- 당시 `slicefinder_core.dml`과 현재 2026-10-08 봉인 core 파일은 byte-identical이다. SHA-256: `07a1be572c5611eaff67dde0cbc23b17796b79dd6635df04584f482b470a348d`.
- 당시 wrapper도 `maxL=3`, `ltype="for"`를 사용했다. [core](evidence/sliceline-legacy-static-planning-2026-10-14/workload/slicefinder_core.dml), [wrapper](evidence/sliceline-legacy-static-planning-2026-10-14/workload/sliceline_fed.dml).
- 보존된 wrapper는 `N=__N__`, `D=__D__`, 주소·range placeholder를 포함한 **template**이다. harness가 입력 크기와 federation 정보를 compile 전에 정적으로 넣도록 설계되어 있다. 이는 중간 후보 크기를 계산해 주는 것과 다르다. 개별 성공 run의 rendered DML은 이번 묶음에 확보하지 않았으므로, receipt/log만으로 실제 N/D와 전체 input binding을 독립 재검증했다고 주장하지 않는다. 위 표의 크기는 당시 보고서에 근거한다.
- 당시 보고서와 보존된 core에 따르면 “이전에는 전혀 다른 작은 SliceLine만 통과했다”고 일축할 근거가 없다. 다만 전체 입력 artifact, worker pool, metadata가 현재와 모두 동일하다고 확인한 것은 아니다.
- 당시 환경은 coordinator 16GiB/8 active processors, worker별 8GiB/8 active processors, W4였다. 현재 후보의 W3/4CPU/8GiB 조건과 직접적인 성능 A/B 비교가 아니다.
- 당시 소스 기준은 `fe000959c48ffa1172399e49124d082fe42d0c6d` **+ 미커밋 변경**, 보고된 JAR SHA는 `805095277f03d4cda0a85278be64c09072c118696f908f00e1f03f62afbf3f28`이다. 이 문서의 historical source 발췌는 기준 commit의 코드이며 측정 JAR 전체를 복원했다는 뜻이 아니다.

환경·빌드 근거: [당시 실행 보고서](G009_FEDALL_HEURISTIC_STREAMING_EXECUTION_STATUS_2026-09-21.md):14–31.

### 2.3 이 성공 기록이 증명하지 않는 것

- 실제 SliceLine 실행 결과의 정확성 및 runtime 전체 성공
- 모든 중간 dimension/NNZ의 사전 확정
- 실제 데이터에서 발생할 비용에 대한 전역 최적성
- 실행 중 동적 재컴파일이 얼마나 필요했으며 성공했는지

ADULT 로그의 HOP 재컴파일 횟수는 `0/0`, 시간은 0이다. 이는 compile-only 경계와 일치하며 runtime 재컴파일의 성공 증거가 아니다. Heavy hitter에 scalar 곱셈·ceil·max 등이 있으므로, “컴파일 동안 어떤 연산도 실행되지 않았다”는 절대 표현도 사용하지 않는다. **본 workload 실행 0과 상수식 계산은 구분한다.**

## 3. legacy는 미확정 크기를 어떻게 처리했는가

다음은 **소스로 확인한 허용 메커니즘**이다. 당시 각 SliceLine HOP가 어느 fallback을 몇 번 사용했는지까지 trace로 복원한 것은 아니다.

### 3.1 연산 의미에서 worst-case 크기 추정

`ParameterizedBuiltinOp.inferOutputCharacteristics()`의 RMEMPTY는 선택 벡터의 NNZ를 모르면 입력 행/열 크기를 사용한다. 입력 통계를 이용한 worst-case 메모리 추정이지, 모든 행이 실제로 살아남는다는 증명이 아니다.

```text
removeEmpty(X, select=mask)

정확한 사실: 결과는 mask와 X의 값에 따라 결정됨
정적 비용 추정: 생존 수를 모르면 입력 크기를 이용한 worst-case 크기 사용
금지된 해석: “실제 생존 행 수가 입력 행 수와 정확히 같다”
```

근거: 기준 commit의 [ParameterizedBuiltinOp.java:598–657 발췌](evidence/sliceline-legacy-static-planning-2026-10-14/legacy-source/ParameterizedBuiltinOp.java.txt), [Hop.java:930–1005 발췌](evidence/sliceline-legacy-static-planning-2026-10-14/legacy-source/Hop.java.txt). 일반적인 bound를 구현할 때는 빈 결과의 연산별 표현 규칙도 확인해야 한다.

### 3.2 default memory와 전송량 추정

`Hop.computeMemEstimate()`는 유효한 출력 통계를 얻지 못하면 `OptimizerUtils.DEFAULT_SIZE`를 사용한다. 이것은 **기본 메모리 추정량**이지 `rows=특정값, cols=특정값`이라는 실제 shape가 아니다.

Legacy `FederatedCostModel`에는 다음 정책이 있다.

- input/descendant/indexing 정보를 이용한 미확정 메모리 크기 보정
- `SYSDS_FED_COST_UNKNOWN_DIM_TRANSFER_MB`, 기본 **256MiB**
- 미확정 크기의 전송량 추정에 위 값과 입력 기반 제한을 적용하는 clamp

코드 주석에는 SliceLine에서 과소 추정하면 CP/LOUT를 과선호하고 이후 local→FED 비용을 크게 지불할 수 있다고 명시되어 있다. 즉, 미확정 크기를 비용 정책으로 처리한다는 사실은 명시적이다.

그러나 **256MiB는 데이터로부터 증명한 상한이 아니며, 모든 미확정 결과가 256MiB라는 뜻도 아니다.** 단위·default·clamp 정책을 실제 크기나 안전성 증명으로 해석하면 안 된다.

근거: 기준 commit의 [FederatedCostModel.java 발췌](evidence/sliceline-legacy-static-planning-2026-10-14/legacy-source/FederatedCostModel.java.txt).

### 3.3 미확정 차원을 1로 계산하는 FLOPs 경로

기준 commit의 `ComputeCost.getSize()`는 다음과 같다.

```java
return Math.max(hop.getDim1(), 1) * Math.max(hop.getDim2(), 1);
```

따라서 dimension=-1이어도 이 경로는 즉시 실패하지 않고 비용 계산을 계속한다. 이것은 HOP의 실제 dimension을 1로 변경하는 코드가 아니다. 또한 큰 데이터의 계산량을 심하게 과소평가할 가능성이 있으므로 **legacy 성공을 이유로 그대로 복원할 정책도 아니다.**

근거: 기준 commit의 [ComputeCost.java 발췌](evidence/sliceline-legacy-static-planning-2026-10-14/legacy-source/ComputeCost.java.txt).

### 3.4 초기 계획과 실행 시 shape 구체화의 구분

현재 저장소에도 남아 있는 SystemDS 경로는 실행 시점에 필요하면 statement block을 재컴파일한다. `Recompiler`는 실제 `MatrixObject`/`FrameObject`의 rows/cols를 반영하고 메모리 추정을 갱신한다. 선택된 placement를 복원하는 코드도 있다.

근거: [BasicProgramBlock 발췌](evidence/sliceline-legacy-static-planning-2026-10-14/current-source/BasicProgramBlock.java.txt), [Recompiler 발췌](evidence/sliceline-legacy-static-planning-2026-10-14/current-source/Recompiler.java.txt).

여기서 주장은 **초기 RuntimeProgram 생성이 모든 runtime shape의 사전 확정을 뜻하지 않는다**는 것이다. 현재 코드를 당시 JAR의 동일 코드로 가장하거나, 이번 compile-only 성공이 runtime 재컴파일의 정확성까지 증명했다고 주장하지 않는다. 재컴파일과 전역 placement 재최적화도 같은 개념이 아니다.

## 4. 현재 Memo와 다른 지점

현재 `MemoCostModel.ensureKernelSizesKnown()`은 비용 대상 matrix/frame의 output/input dimension이 0 이하이면 `UNKNOWN_SIZE`를 발생시킨다. [현재 코드 발췌](evidence/sliceline-legacy-static-planning-2026-10-14/current-source/MemoCostModel.java.txt).

| 항목 | 확인한 legacy 경로 | 현재 Memo의 해당 경로 |
|---|---|---|
| 정확한 dimension | 비용 계산에 사용 | 비용 계산에 사용 |
| 일부 dimension 미확정 | worst-case/byte 추정/default 경로 존재 | kernel size gate에서 거절 |
| 비용 추정값과 실제 dimension | 추정량으로 계산을 계속할 수 있음 | 해당 primitive 구성에 concrete dimension 요구 |
| 초기 compile 성공의 의미 | RuntimeProgram 생성 완료 | 추가적인 Memo coverage/계약을 충족해야 함 |

따라서 “중간 shape가 미확정이므로 어떤 static planner도 계획할 수 없다”가 아니라, **현재 Memo의 비용 입력 요구가 legacy보다 엄격한 지점이 있다**가 정확한 설명이다.

단, 현재 실제 SliceLine candidate06의 첫 실패는 여전히 `UNSUPPORTED_PARFOR`다. 이 gate 이후의 실제 비용 실패 위치나 시간은 아직 관측하지 못했다. 위 비교는 코드의 계약 차이이며, shape 비용 gate가 현재 첫 실패라는 주장이 아니다.

## 5. 정적 분석을 유지하면서 검토할 방향

### 5.1 사실·상한·추정을 구분한다

| 정보 | 예시 | 허용된 사용 | 금지된 사용 |
|---|---|---|---|
| 증명된 사실/관계 | 입력 rows=N, 전치 축 교환, 두 행 수의 동일성 | 합법성·binding·비용 계산 | 증명 범위를 넘어 다른 context에 적용 |
| 증명된 bound | 연산 의미에 따른 출력 크기 상한 | 범위 기반 증명, 보수적 비용 정책 | bound 값을 실제 dimension으로 간주 |
| 비용 추정 | 추정 생존 수, 예상 bytes, 명시적 default | 비용 순위 비교 | Oracle YES, privacy 허용, range 정렬의 근거 |
| 미확정 | shape 또는 합법성 의무 미해결 | UNKNOWN/DEFER와 의무 보존 | 비용 수치가 있다는 이유로 해결 처리 |

**비용 추정은 실행 결과의 근사화가 아니다.** 실제 연산 결과는 원래 DML 의미를 정확히 따라야 한다. 다만 비용 추정이 틀리면 선택한 계획의 성능은 나쁠 수 있다.

### 5.2 단순한 처리 순서

1. 기존 입력 metadata·literal·정적 관계로 정확한 shape를 우선 추론한다.
2. 정확한 크기가 없으면 기존 연산별 worst-case/bound 유틸리티를 재사용할 수 있는지 검토한다.
3. 남은 미확정에는 **비용 전용**으로 명시된 추정 정책을 적용한다. 임의의 전역 default shape를 HOP나 `MemoShapes`의 EXACT fact로 주입하지 않는다.
4. 추정 정책·입력 identity·context를 고정해 primitive 생성과 selected-cost 재계산이 같은 비용 모델을 사용하게 한다.
5. Oracle 합법성, privacy, UNKNOWN/DEFER, 실제 worker/range와 exact source binding은 별도로 유지한다.

이 경로는 입력 row payload 조회, worker 통계 수집, SliceLine prefix 실행을 필수로 추가하는 설계가 아니다. **이미 허용된 metadata와 정적 분석만으로 비용을 추정하는 범위**를 먼저 검토한다.

### 5.3 실행 계약에 남는 조건

비용을 계산할 수 있다고 실행 가능한 계획이 자동으로 생기지는 않는다. 미확정 dimension에서 선택한 전략이 유효함을 증명해야 한다.

- 실제 shape와 무관하게 합법적인 연산 전략이면 정확한 중간 수치를 몰라도 정적 선택이 가능할 수 있다.
- 실제 dimension을 정상 실행 중 읽어 buffer를 할당하거나, 사전에 정의한 관계에 따라 range를 계산하는 것과 **실패 뒤 임의로 실행 위치를 바꾸는 fallback**은 다르다.
- 그러한 parameterized 실행 계약을 현재 runtime이 지원하는지는 별도 검증 사항이다. 기존 실제 FederationMap을 추정 map으로 대체해서는 안 된다.
- 특정 후보의 합법성에 필요한 관계를 끝내 증명하지 못하면 미확정 의무를 유지한다. 비용 추정 때문에 불가능하다고 단정하거나 합법하다고 허용하지 않는다.
- 일반 PARFOR의 task-local binding·planned supply·merge·privacy 의미 지원은 별도의 구현 과제다.

## 6. exact 최적해 보장의 의미

실제 중간 크기에서의 비용을 `C(p)`, 정적 추정 정책으로 정의한 비용을 `Ĉ(p)`라 하자. 동일한 합법 계획 집합 `F`에 대해 solver는 다음을 정확하게 풀 수 있다.

```text
p* ∈ argmin_{p ∈ F} Ĉ(p)
```

이것만으로 `p* ∈ argmin C(p)`를 보장하지는 않는다. 추정 오차가 계획의 순위를 바꿀 수 있기 때문이다. 또한 여기서 `C`도 명시된 비용 모델의 값이지 실제 wall-clock 시간을 완벽하게 예측한다는 뜻은 아니다.

유지해야 할 것은 **합법성 증명, 후보 coverage, exact primitive 합산, solver의 최적성 및 tie 계약**이다. 변경되는 것은 비용 입력의 추정 정책이다. 추정치를 쓰면서 solver만 exact라는 이유로 “실제 데이터 비용에서도 무조건 최적”이라고 표현하면 안 된다.

기존 spec에 없는 dimension 추정 정책을 추가하면 **단순 성능 최적화가 아니라 비용 모델 계약의 변경**으로 문서화해야 한다. 기존 [Oracle-guided cost projection 설계](FEDPLANNER_ORACLE_GUIDED_COST_PROJECTION_DESIGN_2026-10-13_KO.md)의 unknown 비용을 0으로 바꾸지 않는 원칙과도 정합성을 검토해야 한다. 이번 문서 작성은 정책 변경의 구현·승인을 대신하지 않는다.

## 7. 향후 검증 항목과 완료 기준

| 검증 영역 | 필요한 증거 |
|---|---|
| 추정/증명 분리 | 비용 estimate가 변해도 proof facts·Oracle 입력의 사실·privacy 판정이 오염되지 않음. 비용 기반 선택 결과는 달라질 수 있음 |
| 정적 분석 범위 | 데이터 row payload·worker RPC·본 workload prefix 실행이 없는 cold compile 경계 |
| 비용 일관성 | 알려진 shape에서 기존 primitive parity, 미확정에서 정책별 provenance, selected-cost 재계산 일치 |
| context/identity | 다른 입력·call frame·정책 버전의 estimate가 부당하게 합쳐지지 않음 |
| 탐색 정확성 | 선언된 추정 비용에 대한 exhaustive 소규모 정답과 optimum/tie 일치. fixture는 성능 근거가 아님 |
| 실제 workload | 원본 sealed ADULT/COVTYPE의 fullcompile 및 unresolved 의무 없는 receipt. worker 조건별 1회, 동일 Docker 환경 |
| runtime 의미 | 별도 실행 검증으로 결과 동등성, 실제 worker binding·range·PARFOR merge 확인. compile-only와 분리 |

현재는 **문서와 증거 조사 완료**이며, 비용 추정 정책 도입·일반 PARFOR·SliceLine 전체 지원 완료가 아니다. 새로운 benchmark, production 테스트, commit/push는 수행하지 않았다.

## 8. 최종 요약

확인된 것은 **legacy의 초기 compile 성공**과 **기준 commit에 존재하는 미확정 크기의 비용 추정 경로**다. 둘은 양립하며, 성공을 “모든 shape를 정확히 알아냈다”로 해석할 근거는 없다. 그러나 기준 commit에 미커밋 변경을 더한 측정 JAR에서 어느 fallback이 실제 각 HOP에 사용되어 성공에 기여했는지는 추가 trace 없이는 확정하지 않는다.

우리에게 필요한 검토는 **알 수 없는 shape를 임의의 EXACT 값으로 채우는 것**이 아니라, **정적 사실과 비용 추정의 역할을 분리해 원래의 실행 정확성을 보존하면서 계획할 수 있게 하는 것**이다. 따라서 사전 데이터 실행이나 staged planning이 유일한 해결책은 아니다. 동시에 비용 추정만으로 모든 합법성·PARFOR 문제가 해결된다고 주장해서도 안 된다.
