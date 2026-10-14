# SliceLine planning 문제의 정확한 정의와 근본 원인

> **후속 보완:** legacy의 실제 SliceLine 초기 fullcompile 성공을 확인했다. 중간 shape의 정확한 사전 결정과 추정 비용을 사용하는 정적 계획 생성은 다르다. [Legacy planning·정적 shape·비용 추정 보고서](FEDPLANNER_SLICELINE_LEGACY_STATIC_SHAPE_COST_ESTIMATION_2026-10-14_KO.md)는 이 문서의 정보 부족 분석을 유지하면서, 비용 추정과 합법성 증명을 분리하는 추가 검토 방향을 설명한다. 사전 실행/단계별 planning만이 가능한 해법이라는 뜻은 아니다.

## 0. 질문과 결론

**질문:** 원본 reproduce처럼 workload를 유지하면서 worker metadata를 수집하면 현재 실패를 해결할 수 있는가? 무엇이 구현 버그이고, 무엇이 현재 planning 계약의 한계인가?

**결론:** 현재 문제는 하나의 성능 병목이 아니다. 다음 문제를 분리해야 한다.

1. **관측된 첫 실패:** SliceLine ADULT/COVTYPE는 현재 `UNSUPPORTED_PARFOR`에서 중단된다. metadata 수집을 추가한다고 이 병렬 실행 의미가 구현되는 것은 아니다.
2. **소스에서 확인한 별도 정보 부족:** 본 알고리즘의 필터·pruning·후보 중복 제거가 중간 행 수를 바꾼다. 입력 차원·NNZ·column domain만으로 그 크기를 결정할 수 없다.
3. **현재 planner 계약과의 충돌:** Memo는 지원한 비용 모델의 concrete 비용을 실행 전에 구성하고 exact optimum을 인증하려 한다. 필요한 dimension이 UNKNOWN이면 비용 구성을 거절한다. 이를 해결하려고 본 알고리즘을 먼저 수행하면 그 작업은 무료 metadata가 아니라 preprocessing/실행이다.
4. **P2는 별개:** 실제 W3 첫 실패는 canonical input range 불일치다. dummycode global distinct 정보 부족은 별도의 후속 과제이며, SliceLine 중간 후보 수 문제와 같지 않다.

**아직 증명하지 않은 결론:** “어떤 static planner도 이 workload를 컴파일할 수 없다”, “metadata-assisted 방식은 리뷰어가 받아들이지 않는다”, “SliceLine의 실제 300초 timeout 원인이 중간 shape다”라고 결론 내리지 않는다. 현재 SliceLine은 solver timeout 이전의 facts 단계에서 실패했다.

## 1. 증거의 범위와 용어

- **[관측]** frozen candidate06 실제 sealed workload, W3, Docker 4CPU/8GiB/heap3GiB, 조건당 1회. `run_LAN_docker.sh --memo-planning-matrix`로 planning과 전체 compile만 수행했다. 본 workload 실행은 0이다.
- **[소스 확인]** 봉인된 adaptation DML, 원본 reproduce, 현재 Memo 계약과 runtime 코드를 직접 읽었다.
- **[추론]** 코드와 명시한 입력 정보로부터 도출한 결론이다. 아직 통과하지 못한 단계의 실제 오류 로그로 가장하지 않는다.
- **[미확인]** 실제 training 결과 동등성, general PARFOR를 구현한 뒤의 SliceLine 전체 compile, 신규 metadata collector 성능·privacy audit는 검증하지 않았다.

여기서 **exact cost**는 명시된 비용 모델의 primitive와 빈도를 일관되게 평가한다는 뜻이다. 실제 wall-clock 실행 시간을 오차 없이 예측한다는 뜻이 아니다. 기존 모델에는 NNZ=-1에 대한 정해진 estimator 입력도 존재한다. 따라서 모든 실제 NNZ/반복 횟수를 미리 알아야 한다는 주장이 아니다. 이 보고서가 지적하는 것은 **현재 비용 API가 concrete dimension을 요구하는 지점에 필요한 증거가 없다는 것**이다.

`RuntimeProgram 생성`과 `본 workload 실행`도 다르다. 일반 컴파일러는 동적 shape를 갖는 코드를 생성할 수 있다. 이번 제약은 SystemDS 언어 자체가 아니라 **현재 Memo의 비용·합법성·최적성 인증 경로**에 있다.

## 2. 무엇이 실제로 실패했는가

| 대상 | 실제 candidate06 W3 관측 | 판단 / 확신 |
|---|---|---|
| SliceLine ADULT | `UNSUPPORTED_PARFOR`, `regionId=10` | facts 단계의 지원 거절. 높음 |
| SliceLine COVTYPE | 같은 오류 | facts 단계의 지원 거절. 높음 |
| P2_PREP | `Federated value violates canonical ingress at worker index 1` | 실제 input map과 canonical map 계약 불일치. 높음 |
| P1_FULL | `UNSUPPORTED_PARFOR`, `regionId=78` | 오류는 관측. 별도 소스에서 GMM 초기화 KMeans `runs=10` 호출 확인. region78과의 직접 매핑 receipt는 없음 |
| SliceLine의 후속 shape 비용 오류 | 위 PARFOR gate 이후까지 이번 실제 실행은 도달하지 못함 | 구체적인 첫 후속 오류 위치·시간은 미확인 |

원시 로그: [ADULT](evidence/sliceline-planning-blockers-2026-10-14/sliceline-ADULT-w3/coordinator.log), [COVTYPE](evidence/sliceline-planning-blockers-2026-10-14/sliceline-COVTYPE-w3/coordinator.log), [P2](evidence/sliceline-planning-blockers-2026-10-14/p2-P2_PREP-w3/coordinator.log), [P1](evidence/sliceline-planning-blockers-2026-10-14/p1-P1_FULL-w3/coordinator.log). receipt/DML/로그 해시는 [manifest](evidence/sliceline-planning-blockers-2026-10-14/manifest.json)에 보존했다.

**해석상 주의:** region10이 정확히 어느 lexical PARFOR인지 이 raw 로그만으로 확정하지 않았다. source에 남아 있는 `decodeTopK` PARFOR를 아래에서 설명하지만, 이것을 region10의 확인된 매핑이나 실제 task 실행 증거라고 주장하지 않는다.

## 3. 원본 reproduce는 무엇을 측정했는가

**[소스 확인]** 원본 `run4PrepareLocalData.sh:3–5,13–17`은 feature transformation과 모델 학습으로 recoded X 및 오류 벡터 e를 준비한다. Adult의 `dataprepAdult.dml:25–49`는 transformation, 학습/예측 이후 **recoded X와 e**를 저장한다. `SlicingExp2.dml:1–11`은 그 X/e를 읽어 slicefinder를 실행한다.

그러나 원본 알고리즘 variant `SlicingExp1_3.dml`에는 one-hot 구성, candidate 생성·pruning, 내부 ID recode가 남아 있다. **입력 준비를 분리했다는 사실은 본 알고리즘의 모든 중간 결과를 미리 계산했다는 뜻이 아니다.** Exp1_3 소스를 Exp2가 사용한 jar builtin과 byte-identical이라고 검증한 것은 아니다.

원본 측정 shell은 Java 프로세스 외부 시간에서 1,500ms를 차감하며 3회 반복한다(`runExperiment2.sh:6–16`). 현재 실험은 이 차감이나 반복 횟수를 복사하지 않는다. 요청대로 조건당 1회, 시간 차감 없이 보고한다.

현재 adaptation도 준비된 X/e를 입력으로 받지만, `maxL=3`, `ltype="for"`, federated/privacy 설정은 별도 adaptation이다. 원본 Adult의 Exp2 `maxL=0` 등과 모든 조건이 동일한 실험이라고 주장할 수 없다.

근거 전체와 11개 파일 SHA는 [기존 원본 조사 문서](FEDPLANNER_SLICELINE_REPRODUCE_METADATA_CONTRACT_2026-10-14_KO.md), [원본 evidence manifest](evidence/sliceline-metadata-methodology-2026-10-14/manifest.json)를 참조한다. 원본 reproduction script를 실행하거나 데이터 준비를 다시 수행하지 않았다.

## 4. 문제 A — 일반 PARFOR는 metadata 문제가 아니다

현재 `MemoProgramFacts.validateActiveLoopIterations()`는 도달 가능한 호출 환경에서 같은 exact 0회/1회 반복을 증명하는 범위만 지원한다. 미확정, 2회 이상, 호출 간 cardinality 불일치는 거절한다. 근거: `src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoProgramFacts.java:1739–1775`.

현재 SliceLine wrapper의 `ltype="for"`는 본문의 block evaluation 분기를 선택하는 인자다. 다음 함수까지 순차 실행으로 바꾸는 옵션이 아니다.

```text
slicefinderX → decodeTopK
  if nrow(TK) > 0:
    parfor j in 1:ncol(foffb):
      R[,j] = ...
```

근거: [slicefinder_core.dml](evidence/sliceline-metadata-methodology-2026-10-14/adaptation/workloads/sliceline/slicefinder_core.dml):55–74,94,265–277. `foffb`는 입력 feature별 offset이고, top-K가 비어 있지 않으며 feature가 여러 개이면 다회 병렬 body가 필요하다. **이번 compile-only 로그는 실제 TK가 비어 있는지, task가 몇 번 실행됐는지를 증명하지 않는다.**

일반 PARFOR 지원에는 적어도 iteration-local 변수/worker binding, planned supply의 수명과 공유, 결과 merge, privacy, 비용 및 runtime 계약이 필요하다. 입력 열 수를 정확히 알아도 이 의미는 자동으로 구현되지 않는다.

따라서 다음은 해결책이 아니다.

- PARFOR 거절 검사만 삭제하기
- PARFOR를 FOR로 간주하거나 workload를 수정해 병렬 부분 제거하기
- 알려지지 않은 cardinality를 1로 고정하기

**[소스 확인]** P1도 다회 PARFOR를 요구하는 호출을 포함한다. main KMeans는 runs1이지만 GMM 초기화가 같은 builtin KMeans를 runs10으로 호출한다. `n_components=1`은 k를 정할 뿐 runs를 1로 만들지 않는다. 이는 원본 호출 의미를 보존한 general PARFOR 구현 과제다. 다만 linked raw log만으로 region78과 이 lexical PARFOR의 직접 매핑까지 입증했다고 주장하지 않는다.

## 5. 문제 B — 입력 domain을 알아도 중간 후보 크기는 결정되지 않는다

아래는 [현재 봉인된 slicefinder_core.dml](evidence/sliceline-metadata-methodology-2026-10-14/adaptation/workloads/sliceline/slicefinder_core.dml)의 실제 계산 흐름이다.

| 단계 | 소스 | 크기를 결정하는 정보 | 입력 통계만으로 해결 가능한가 |
|---|---|---|---|
| One-hot 폭 | 13–18 | `colMaxs(X)`의 recoded domain extent 합 | 허용된 exact domain/extent를 얻으면 이 지점은 해결 가능 |
| 기본 후보 필터 | 105–120 | `cCnts=colSums(X2)`, `err=t(e)%*%X2`, `selCols=(cCnts>=minSup & err>0)` | domain/개별 열 빈도만으로 X와 e의 연관성을 알 수 없음 |
| top-K 유지 | 146–159 | score, support, 정렬 후 생존 수 | 본 연산의 데이터 의존 결과 |
| 후보 pairing | 178–196 | 이전 생존 S, join mask, nonzero pair 수 | 이전 결과에 의존 |
| 중복 제거·pruning | 214–248 | 생성된 ID의 recode, `max(ID)`, upper-bound score, parent count | 입력 원본 domain과 다른 **중간 후보 domain** |
| 다음 level 실행 | 38–59 | `nrow(S)`, `sum(S)`, candidate block 수 | 후속 계산/반복 경로도 영향을 받음 |

특히 `transformencode`라는 같은 연산 이름이 나와도 P2의 원본 입력 category dictionary와 SliceLine의 **생성된 후보 ID dictionary**는 같은 metadata가 아니다. 원본 입력에 관한 통계를 수집했다고 후자를 알 수는 없다.

### 5.1 검증 가능한 반례: 통계가 같아도 기본 후보 행 수가 달라진다

이는 실제 workload를 작은 workload로 바꾼 benchmark가 아니다. **입력 통계만으로 결과 shape가 결정되지 않는다는 산술적 증명용 예시**다. 실제 성능 근거로 사용하지 않는다.

worker3개가 각각 32행을 보유한다고 하자. 각 worker에서 아래 4행 패턴을 8번 반복한다.

| 행 패턴 | X의 두 feature | e(A) | e(B) |
|---|---|---:|---:|
| 1 | (1,1) | 1 | 1 |
| 2 | (1,1) | 1 | 0 |
| 3 | (2,2) | 0 | 1 |
| 4 | (2,2) | 0 | 0 |

두 경우의 X는 실제 값까지 동일하다. e는 배치만 다르고, global 및 worker별 rows/cols/NNZ/min/max/sum/distinct/histogram이 모두 같다. N=96이고 현재 wrapper와 같은 `minSup=max(ceil(0.01*N),8)=8`이다. one-hot 폭도 모두4다.

하지만 소스의 기본 후보 계산은 달라진다.

| 계산 | A | B |
|---|---|---|
| `cCnts` | [48,48,48,48] | [48,48,48,48] |
| `err` | [48,0,48,0] | [24,24,24,24] |
| `selCols` | [true,false,true,false] | [true,true,true,true] |
| `nrow(attr)` / S shape | 2 / **2×4** | 4 / **4×4** |

정수 산술로 원본 식의 counts/filter를 검증했다: [검증 스크립트](evidence/sliceline-planning-blockers-2026-10-14/verify_metadata_witness.py), [결과 JSON](evidence/sliceline-planning-blockers-2026-10-14/metadata-witness.json). SystemDS 실제 workload 실행이나 optimum parity 테스트는 아니다.

**중요한 경계:** 두 e의 content hash는 다르다. 서로 다른 Oracle 입력을 같은 identity로 합쳐도 된다는 사례가 아니다. hash는 데이터를 구별하지만 값과 feature의 연관 통계를 대신 제공하지 않는다. 이 반례는 **나열한 통계의 불충분성**을 증명하지, 모든 가능한 풍부한 metadata로도 해결할 수 없다고 증명하지 않는다.

feature-error 결합 집계를 추가하면 이 첫 단계는 더 알 수 있다. 그러나 그것은 이미 소스의 `t(e)%*%X2` 같은 계산이고 후속 후보 통계도 계속 필요하다. 본 연산을 사전 수행하면서 이름만 metadata로 바꾸고 시간을 제외하면 실험 경계가 바뀐다.

## 6. 문제 C — 현재 concrete cost 구성의 요구

`MemoCostModel.ensureKernelSizesKnown()`은 비용 대상 matrix/frame의 출력 또는 입력 dimension이 0 이하이면 `UNKNOWN_SIZE`를 발생시킨다(`MemoCostModel.java:409–418`). native costs는 model 구성 중 쌓이고 optimizer는 그 비용을 사용한다. `MemoProgramModel.optimize():435–445`는 선택 후 원래 비용 ownership으로 목적함수를 재계산해 비교한다.

현재 경로를 단순화하면 다음과 같다.

```text
봉인된 입력 정보
  → program facts / shape / privacy / native coverage
  → concrete cost primitives
  → exact optimization
  → selected-plan contracts / RuntimeProgram
  → 본 workload 실행
```

문제의 순서는 다음과 같다.

1. SliceLine intermediate dimension은 일부 본 연산 결과에 의존한다.
2. 현재 입력 정보에는 그 결과가 없다.
3. 현재 비용 API는 해당 concrete dimension 없이는 primitive 구성을 완료하지 못한다.
4. 그 결과를 얻기 위해 먼저 본 연산을 실행한다면, 기존의 “그 실행 이전에 단일 concrete 전역 계획 완성”과 실행 순서가 달라진다.

**[추론, 높음]** 현재 정보/표현/순서를 그대로 둔 채 metadata 옵션 하나만 추가하는 것으로 모든 SliceLine 비용 문제를 해결한다고 보장할 수 없다.

이것은 “어떤 plan도 선택할 수 없다”는 수학적 불가능성 주장이 아니다. 모든 크기에서 같은 plan이 우세하다는 별도 증명, exact symbolic 비용/합법성, 완전한 piecewise 계획이 가능할 수 있다. 그러나 현재 코드가 그런 증명을 제공하거나 일반적으로 지원한다는 증거는 없다. 단순 dimension 상한이나 평균을 넣는 것은 같은 비용 의미의 개선이 아니라 모델/보장 변경이다.

## 7. Oracle-guided generation과 혼동하면 안 되는 이유

두 종류의 ‘후보’가 있다.

- **planner native 후보:** Exec/placement/worker/source 선택. 사용자의 요구대로 Oracle이 가능한 조합을 안내해야 하며, 전 조합을 만든 뒤 불가능한 조합을 지우는 방식으로 되돌리지 않는다.
- **SliceLine 알고리즘 후보 S/P:** 분석할 데이터 slice들의 집합. X/e에서 score·support를 계산하고 pruning하는 본 workload의 데이터다.

Oracle 요청 dedup과 native generation 개선은 전자를 줄인다. 그것만으로 후자의 행 수·score·ID cardinality를 알아내지는 못한다. Oracle은 자신에게 제공되지 않은 데이터 통계를 만들어내는 장치가 아니다. **현재 문제를 ‘source Cartesian product 제거가 덜 됐기 때문’으로만 설명하면 원인이 다르다.**

## 8. P2의 worker metadata 수집은 어디까지 타당한가

P2의 `dummycode:[1]`는 원본 입력 column의 exact global domain에 따라 출력 폭이 달라진다. 이 정보는 worker에서 정확하게 수집할 여지가 있다. 하지만 다음 조건을 분리해야 한다.

1. **현재 실제 W3 첫 실패는 range geometry다.** metadata를 수집해도 `canonical ingress` 불일치가 없어지지는 않는다. 실제 worker/range를 다른 canonical map으로 가장해서도 안 된다.
2. **global distinct는 local distinct 합이 아니다.** 겹친 category를 정확하게 결합하고 기존 encoder의 null/NA/equality 의미를 유지해야 한다.
3. **count 공개와 dictionary 공개는 다르다.** 기존 encoder build는 partial dictionary를 포함한 객체를 coordinator로 반환한다. 기존 opt-in/global M 공개 정책을 count-only 수집 권한이라고 설명하면 부정확하다. 개인정보·category·worker membership의 공개 범위를 검토해야 한다.
4. **P2_PREP는 전처리 자체가 workload다.** `transformencode → outlier 처리 → scale → split`을 측정 밖으로 이동하면 workload가 달라진다. dictionary build 역시 transform-spec-specific preprocessing이며 공짜 metadata가 아니다.
5. **input statistics는 SliceLine의 intermediate ID/pruning 결과를 대신하지 못한다.**

현재 production collector는 구현하지 않았다. 기존 local sealed metadata-only/network-none compile 결과와 새 metadata-assisted 결과를 섞지 않는다. 구체적인 build-only helper/identity/privacy/평가 계약은 [metadata 설계 문서](FEDPLANNER_SLICELINE_REPRODUCE_METADATA_CONTRACT_2026-10-14_KO.md):5–7절에 정리했다.

## 9. 해결 방향과 보장 차이 — 아직 어느 것으로 바꾸었다는 뜻이 아님

| 방향 | 변경/장점 | 유지하거나 새로 증명할 것 | 한계 |
|---|---|---|---|
| A. 현재 계약 유지 + 정적 증명/입력 exact 통계 보강 | proof 누락 버그 수정, 허용된 입력 domain 수집, 일반 PARFOR 정식 구현 | 동일 cost 의미·privacy·UNKNOWN/DEFER·worker binding·전역 최적성 | 데이터 의존 중간 결과가 계속 필요하면 지원을 완료하지 못할 수 있음 |
| B. symbolic/guarded 전역 계획 | 중간 dimension을 변수로 두고 조건별 비용·합법성을 표현 | 모든 가능한 metadata 구간의 coverage, runtime guard, exact 비용/lex tie, 최적성 | 표현·solver 확장 필요; 구간/함수 복잡도가 커질 수 있음. 구현·성능 미검증 |
| C. 명시적 단계별 실행·재계획 | 필요한 결과를 생성한 뒤 다음 단계를 정확히 계획 | stage boundary, privacy, materialization/통신/retention 비용, 전체 결과 동등성 | **stage별 최적해가 전체 workload 최적해를 자동 보장하지 않음**. 실행 전 단일 계획 요구와 다른 계약 |

C는 장애 시 runtime이 임의 보정하는 fallback과 다르게 설계할 수 있다. 그러나 이름을 “정상적인 staged planning”으로 바꿔도 기존 목표와 같아지지는 않는다. 전체 최적성까지 주장하려면 future cost와 stage 간 placement/state 결합을 포함한 별도 증명이 필요하다. 현재 사용자 결정 없이 이 방식으로 전환하지 않았다.

**지금 확정 가능한 안전한 조치:** 관측된 구현 오류와 의미 지원 공백을 분리하고, static proof가 가능한 부분부터 검증한다. generic metadata로 해결되는 부분은 정확히 한정한다. “모든 workload 성공”을 위해 UNKNOWN, privacy, range, optimality 검사를 없애지 않는다.

## 10. 리뷰어에게 설명 가능한 실험 계약

리뷰어 수용을 보장할 수는 없다. 다만 다음을 지켜야 결과가 주장과 일치한다.

- 원본과 같은 준비된 입력 경계를 공개하고 현재 adaptation 차이를 함께 기록한다.
- workload-independent 등록 통계와 spec-specific preprocessing을 구분한다.
- `T_metadata`, `T_plan_compile`, 수집 없는 첫 요청부터의 `T_cold_to_ready`, 본 workload 실행 시간을 분리한다. 최초 요청 10초 주장에는 필요한 수집 비용을 포함한다.
- 기존 native/no-new-metadata 비교와 common-information metadata-assisted 비교를 별도로 제시한다. baseline도 실제로 같은 정보를 소비해야 한다.
- 수집 artifact는 dataset content/version, spec, 실제 worker/range, policy, encoder version에 묶어 검증한다. stale/partial 실패는 UNKNOWN/실패로 유지한다.
- intermediate pruning을 사전 수행했으면 “metadata-only”가 아니라 실행했다고 밝히고 비용·통신을 포함한다.
- Oracle 요청 감소·비용 객체 감소·solver speedup·새 metadata 제공 효과를 따로 측정한다.
- 성능 성공은 OPTIMAL뿐 아니라 전체 compile receipt 및 미해결 feasibility/privacy/coverage가 없는 것을 확인한다. 실제 training 결과 동등성은 별도 runtime 검증이다.

## 11. 현재 상태와 미확인 사항

- 원본 reproduce 조사 및 정보 부족 반례 검증 완료.
- 실제 candidate06 W3 전체14 중5개 fullcompile 완료,9개 미완료. 상세는 [현재 지원 현황](FEDPLANNER_ACTUAL_WORKLOAD_SUPPORT_STATUS_2026-10-14_KO.md).
- SliceLine의 첫 관측 실패는 PARFOR다. 이를 통과한 실제 비용/solver profile은 아직 없다.
- P2 collector, 일반 PARFOR, symbolic/단계별 planning은 구현 완료가 아니다.
- 공개 main의 LogReg/GLM under10 결과와 현재 미커밋 지원 후보를 구분한다. candidate06 GLM fullcompile10.920863s는 under10 미달이다.
- 보고서 요청 이후 새 production 수정이나 추가 workload 평가를 시작하지 않았다. 이미 시작된 ALS 진단 결과만 회수했다. 진행 중이던 KMeans SEQ 수정은 **미컴파일·미검증 작업 트리 변경**으로 남아 있다. candidate06의745 tests PASS를 그 변경의 검증으로 인용하지 않는다. 새 commit/push 없음.
- 문서 검증: 두 evidence manifest의 총25개 파일 SHA와 원본 복사 일치, 문서 내 local link 존재, 산술 반례 재실행을 확인했다. 이는 문서 증거 검증이며 production 테스트의 대체물이 아니다.

### 최소 후속 검증

1. region10과 정확한 lexical PARFOR의 매핑 및 control-flow reachability를 읽기 전용 진단으로 확정.
2. 일반 PARFOR 지원 설계/회귀 이후 원본 SliceLine에서 다음 최초 blocker를 실제로 관측.
3. 허용된 입력 통계를 추가했을 때 어떤 shape만 해결되는지 단계별 proof receipt로 확인.
4. symbolic/단계별 계약을 선택하는 경우 정확성·최적성 주장부터 명시한 뒤 구현과 cold/warm 평가.

**요약:** “worker metadata를 더 수집하면 해결된다”가 아니라, **어떤 정보를 어느 시점에 어떤 비용과 공개 권한으로 얻으며, 그 정보로 무엇을 정확히 보장하는가**가 핵심이다. 현재는 PARFOR 의미 지원, 입력 통계 획득, 데이터 의존 중간 shape, 평가 경계를 각각 해결해야 한다.

### 반례 재현

저장소 루트에서 아래 명령으로 정수 산술 검증을 다시 실행할 수 있다. 데이터셋·worker·SystemDS workload는 실행하지 않으며 같은 폴더의 `metadata-witness.json`만 재생성한다.

```bash
python3 docs/evidence/sliceline-planning-blockers-2026-10-14/verify_metadata_witness.py
```
