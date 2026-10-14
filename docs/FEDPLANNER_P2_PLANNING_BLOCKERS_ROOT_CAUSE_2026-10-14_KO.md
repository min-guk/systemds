# P2_PREP planning 실패: 실제 원인과 해결 경계

## 0. 결론

**P2는 단순히 optimizer가 느린 문제가 아니다. 현재 확인한 문제는 세 층이다.**

1. **입력 범위 계약 충돌:** 실제 W3/W7의 shard 범위가 Memo의 canonical 분할과 다르다. 현재 spec은 이를 거절하도록 정의했고, 실제 코드가 그대로 거절한다. 따라서 이 부분은 **spec과 다른 구현이 아니라 spec의 지원 범위와 실제 workload의 충돌**이다.
2. **dummycode 입력 정보 부족:** `dummycode:[1]`의 출력 열 수에는 첫 번째 열의 exact global domain이 필요하다. 봉인된 입력 metadata에는 그 정보가 없다. worker별 distinct count를 더하는 것으로도 해결되지 않는다.
3. **planner 연결·비용 지원 공백:** 현재 `MemoShapes`와 `MemoCostModel`은 shape-preserving recode만 특별히 지원한다. 비용 경로는 `dummycode`가 있는 spec을 명시적으로 거절한다. **따라서 정확한 domain을 수집하고 출력 폭만 채워도 현재 코드 그대로는 충분하지 않다.**

기존 C26 W1/W5에서는 실제 `UNKNOWN_SIZE`가 관측됐고, W3/W7에서는 ingress 오류가 먼저 발생했다. 최신 지원 후보 candidate06에서는 W3 ingress 실패를 다시 관측했다. 이 버전·조건을 섞어 최신 W1/W5까지 재검증했다고 말하지 않는다.

이 문서는 **문제 분석 및 해결 설계 보고서**다. 새 production 수정, worker metadata 수집, workload 재실행 또는 commit/push를 수행한 결과가 아니다.

## 1. 실제 측정에서 확인한 것

### 1.1 버전과 성공 기준

- 과거 **C26**: [전체 W1/3/5/7 평가](FEDPLANNER_MEMO_W1357_PLANNING_MATRIX_RESULT_2026-10-14_KO.md)에 사용한 동결 엔진. 이후 main 및 candidate06과 다르다.
- **candidate06**: `b95989bdddd7`를 기반으로 추가 지원 변경을 포함한 미커밋 동결 엔진. 해당 commit hash만으로 candidate06 binary를 식별할 수 없으므로 engine/source SHA manifest와 구분한다.
- 조건: 실제 sealed P2_PREP, Docker 4CPU/8GiB, coordinator heap3GiB, compile-only, worker process0, 조건당1회. 기존 실행 결과를 읽었고 이번 보고서를 위해 다시 실행하지 않았다.
- 완료는 `OPTIMAL` 및 전체 RuntimeProgram/compile receipt로 판정한다. **아래 모든 행은 실패**이며, 실패까지의 외부 wall time은 planning 완료 시간이 아니다.

| 버전 | Worker 수 | 첫 관측 오류 | 실패까지 외부 wall | 전체 compile 시간 |
|---|---:|---|---:|---|
| C26 | 1 | `UNKNOWN_SIZE`, `nrow` 입력 `TRead X MATRIX -1x-1` | 2.119841s | 미완료/null |
| C26 | 3 | `canonical ingress at worker index 1` | 1.898602s | 미완료/null |
| C26 | 5 | `UNKNOWN_SIZE`, `nrow` 입력 `TRead X MATRIX -1x-1` | 2.044004s | 미완료/null |
| C26 | 7 | `canonical ingress at worker index 5` | 2.050673s | 미완료/null |
| candidate06 | 3 | `canonical ingress at worker index 1` | 2.128807s | 미완료/null |

원시 로그: [C26 W1](evidence/p2-planning-blockers-2026-10-14/c26-w1/coordinator.log), [W3](evidence/p2-planning-blockers-2026-10-14/c26-w3/coordinator.log), [W5](evidence/p2-planning-blockers-2026-10-14/c26-w5/coordinator.log), [W7](evidence/p2-planning-blockers-2026-10-14/c26-w7/coordinator.log), [candidate06 W3](evidence/p2-planning-blockers-2026-10-14/candidate06-w3/coordinator.log).

**이 결과는 300초 timeout이 아니다.** 최신 [result.json](evidence/p2-planning-blockers-2026-10-14/candidate06-w3/result.json)은 `harnessTimeout=false`, `status=failed`, `fullCompileSeconds=null`이다. 프로세스 returncode가0이어도 완료 receipt가 없어 실패다. `executionSeconds=null`이므로 실패 결과에 성공 실행의 `executionSeconds=0` receipt가 있다고 쓰지 않는다. harness는 compile-only/no-RPC로 구성됐고, 실제 training/preprocessing 결과 검증은 수행하지 않았다.

### 1.2 관측과 해석의 구분

| 설명 | 근거 수준 / 확신 | 한계 |
|---|---|---|
| 실제 W3/W7 range가 canonical과 다름 | DML·receipt·allocator 산술 확인 / 높음 | 이것으로 전체 이후 오류를 알 수는 없음 |
| ingress는 rules/model/optimizer 전에 거절 | 현재 소스 호출 순서 확인 / 높음 | raw 로그에는 Java stack이 없음 |
| global domain이 sidecar에 없음 | 입력 metadata 확인 / 높음 | 실제 domain의 값·개수는 측정하지 않음 |
| dummycode는 현 cost predicate에서 거절 | 현재 코드 직접 확인 / 높음 | 최신 W3가 이 후속 gate까지 도달한 것은 아님 |
| W1/W5 전체 오류가 domain 하나만으로 해결됨 | **입증하지 않음** | binding/후속 shape 전파, M 출력, native coverage·비용 검증이 남음 |
| 개선 후 P2 cold planning+compile10초 | **미측정** | 수집·변환·통신 비용을 포함한 평가 필요 |

## 2. P2에서 원래 수행하는 계산

[실제 candidate06 DML](evidence/p2-planning-blockers-2026-10-14/candidate06-w3/cell.dml)의 경계는 다음과 같다.

```text
Xraw: federated numeric matrix (100000 × 1001)
  → as.frame(Xraw)
  → transformencode(dummycode:[1]) → X0 및 metadata frame M
  → colSds/colMeans 기반 이상치 대체
  → scale(center=TRUE, scale=TRUE)
  → split(f=0.7, cont=FALSE, 고정 seed)
  → train/test 결과의 aggregate checksum 및 M 출력
```

y는100000×1이며 X와 같은 row partition을 사용한다. [입력 sidecar](evidence/p2-planning-blockers-2026-10-14/input-metadata/P2_features.data.mtd)는 `matrix/double`, rows100000, cols1001, nnz100100000, binary, `private-aggregate`를 기록한다. 원래 입력은 이미 frame인 것이 아니라 **numeric matrix를 frame으로 변환**한다.

P2_PREP는 **전처리 자체가 workload**다. transformencode·이상치 처리·scale·split을 미리 실행한 후 남은 compile만 재서 P2 전체를 지원했다고 할 수 없다. aggregate checksum과 M은 현재 workload의 출력이며, 이를 삭제하거나 작은 DML로 바꾸지 않는다.

SliceLine reproduce의 “X/e 준비 후 slicing 실행” 경계는 P2 전체 전처리를 무료 입력 준비로 옮기는 근거가 아니다. 두 workload의 목적이 다르다. 관련 [원본 reproduce 및 metadata 계약](FEDPLANNER_SLICELINE_REPRODUCE_METADATA_CONTRACT_2026-10-14_KO.md)을 함께 참조한다.

## 3. 문제 A — canonical input range와 실제 분할의 충돌

### 3.1 실제 숫자

전체 N=100000행에 대해 현재 canonical allocator는 다음을 사용한다.

```text
q = floor(N / W), r = N mod W
앞 r worker: q+1행, 나머지 worker: q행
boundary(i) = i*q + min(i,r)
```

실제 P2 분할은 ceil 길이를 앞 worker에 주고 마지막 worker가 나머지를 갖는 형태다. 각 range는 end-exclusive이며 아래 index는 **0-based**다.

| W | 실제 row boundary | canonical row boundary | 불일치 worker index |
|---:|---|---|---|
| 1 | 0,100000 | 동일 | 없음 |
| 3 | 0,33334,**66668**,100000 | 0,33334,**66667**,100000 | 1,2 |
| 5 | 0,20000,40000,60000,80000,100000 | 동일 | 없음 |
| 7 | 0,14286,28572,42858,57144,71430,**85716**,100000 | 0,14286,28572,42858,57144,71430,**85715**,100000 | 5,6 |

W3 실제 shard rows는33334/33334/33332, canonical은33334/33333/33333이다. W7 실제는14286행×6+14284행, canonical은14286행×5+14285행×2다. X와 y 모두 같은 차이를 갖는다. W1/W5 geometry 일치는 planning 성공을 뜻하지 않는다.

### 3.2 spec과 소스는 무엇을 요구하는가

- [기존 implementation spec](FEDPLANNER_IMPLEMENTATION_SPEC_2026-10-13_KO.md):116–117, **ADR01/02**는 floor+remainder-front 분할과 정확한 source W/type/range/shape 일치를 명시한다. 불일치는 scope 거절이다.
- 같은 spec:108–110,283은 지원 범위 밖과 지원 범위 안의 `INFEASIBLE`을 구분한다.
- [simple Memo redesign](FEDPLANNER_SIMPLE_MEMO_REDESIGN_2026-10-13_KO.md):726–740은 arbitrary actual map을 같은 ROW 상태 뒤에 숨기는 문제와 별도 import normalization 경계를 설명한다.
- `CanonicalWorkerPool.java:140–164,190–204`는 allocator와 exact ingress 검사를 구현한다.
- `FederatedPlanMemo.java:74–88` → `MemoSources.acquire():77–94` → `validateIngress()` 순서상 source 확인에서 중단되며 rules/model/optimizer에 이르지 않는다.

소스 경로의 기준 디렉터리는 `src/main/java/org/apache/sysds/`이며, [행 번호가 포함된 동결 발췌](evidence/p2-planning-blockers-2026-10-14/source-excerpts.txt)에 전체 경로와 SHA를 보존했다. 오류 문자열 자체는 endpoint/type/range 여러 불일치를 포괄하지만, 이 사례의 range 차이는 별도로 직접 확인했다.

**판정:** 현재 거절은 ADR01/02와 일치한다. 실제 P2를 그대로 지원하려면 입력 지원 계약을 확장해야 한다. 현재 코드가 이미 typed `UNSUPPORTED_SCOPE` receipt를 반환한다는 뜻은 아니며, 실제 관측은 `DMLRuntimeException`이다.

### 3.3 해서는 안 되는 수정

- actual range 숫자만 canonical 숫자로 바꾸기: 실제 shard 내용/행 수와 metadata가 달라진다.
- `validateIngress()`만 삭제하기: 현재 `(partition, shape, W)`로 유일한 map을 재구성하는 전제가 깨진다. 서로 다른 ROW map을 DIRECT로 오판할 수 있다.
- 전체 데이터를 coordinator로 몰래 collect해 재분할하기: protected input·비용·실행 경계 계약을 위반할 수 있다.
- 외부에서 데이터를 실제 재분할한 뒤 기존 sealed workload 그대로 통과했다고 보고하기: 원본 partition이 달라진 별도 실험이다.

### 3.4 검토할 해결 방향

**현재 내부 canonical 모델을 최대한 유지하는 방향**은 source 경계에 실제 map을 명시하고, 지원되는 경우에만 **계획에 포함된 import/repartition**으로 canonical 공급을 만드는 것이다. 이 변환의 runtime 지원, 정확한 worker/range, privacy, 통신·materialization 비용을 먼저 증명해야 한다. runtime fallback이나 공짜 normalize가 아니다. 현 ADR02의 확장이며 아직 구현하지 않았다.

다른 방향은 actual map을 native 계획의 정식 identity로 유지하는 것이다. 이 경우 source뿐 아니라 DIRECT alignment, native output-map effect, supply/retention, movement cost 및 runtime validation까지 map identity를 보존해야 한다. canonical allocator 자체를 ceil-tail로 바꾸는 전역 변경보다 명시적이지만, 변경 범위가 더 넓을 수 있다.

두 방향 중 어느 쪽이 더 작고 안전한지는 기존 runtime의 exact redistribution 지원 확인 후 결정해야 한다. 입력/출력 내용만 같다고 worker binding 차이를 무시할 수 없으며, 본 보고서에서 spec을 변경하거나 한 방향을 구현한 것은 아니다.

## 4. 문제 B — dummycode의 exact global domain이 없다

첫 column만 dummycode하는 현재 spec에서, K를 runtime recode 의미에 따른 유효한 global distinct 수라 하면 해당 column의 폭 D는 `max(1,K)`다. 나머지1000열이 그대로 통과하므로:

```text
ncol(X0) = 1000 + max(1,K)
```

근거는 `ColumnEncoderDummycode.java:209–248`의 domain 갱신과 metadata 초기화다. 이 식은 현재 단일 dummycode spec에 한정한다. 일반적인 omit/bin/hash/다중 변환에 무조건 적용하는 식이 아니다.

현재 `.mtd`의 rows/cols/NNZ는 K를 알려주지 않는다. 데이터가 dense라는 것도 첫 column의 서로 다른 값 개수를 정하지 못한다. **실제 K와 X0의 폭은 이번 조사에서 측정하지 않았다.**

### 4.1 local distinct count만 수집해도 부족하다

산술 예시이며 실제 P2 데이터나 성능 benchmark가 아니다.

| 예시 | worker별 category 집합 | 각 local distinct | global distinct |
|---|---|---|---:|
| A | {1,2}, {1,2}, {1,2} | 2,2,2 | 2 |
| B | {1,2}, {2,3}, {3,4} | 2,2,2 | 4 |

sum=6도 max=2도 일반적인 정답이 아니다. encoder와 같은 equality/NA/변환 의미를 보존한 exact global union 또는 동등한 exact 절차가 필요하다. sampling/HLL 같은 근사치를 EXACT로 승격시키지 않는다.

**P2와 SliceLine의 차이:** P2의 이 domain은 *주어진 입력과 transform spec*의 통계다. SliceLine의 pruning 후 후보 개수처럼 알고리즘의 후속 탐색 결과를 전부 알아야 하는 문제와는 다르다. 허용된 수집으로 이 한 지점은 해결할 수 있지만, geometry 및 planner/cost 연결까지 자동 해결되지는 않는다.

## 5. 문제 C — metadata를 알아도 현재 shape/cost 연결이 부족하다

### 5.1 shape propagation

`MemoShapes.java:2064–2095`의 multi-return shape transfer는 input shape가 exact이고 `shapePreservingTransformEncode()`가 참일 때 slot0을 input과 같은 크기로 보존한다. 이 predicate는 recode가 있고 **dummycode/omit/impute/bin/hash가 없어야** 한다.

P2의 `dummycode:[1]`는 이 범위에 들지 않는다. exact domain artifact를 추가하더라도 그것을 이 transfer가 소비하도록 연결하고, encoded matrix와 metadata frame의 각 output slot 및 downstream binding에 올바르게 전파해야 한다.

### 5.2 비용 경로의 명시적 거절

`MemoCostModel.java:140–192`의 `multiReturnBuiltinCost()`도 같은 recode-only predicate를 사용하며, 조건이 거짓이면 다음 오류를 발생시킨다.

```text
UNKNOWN_SIZE: data-dependent transformencode output ...
```

이 조건은 **출력 dimension이 이미 채워졌는지와 무관하게 dummycode spec을 거절**한다. 따라서 “K만 알아낸다 → ncol만 채운다 → 완료”는 현재 코드의 해결 절차가 아니다. exact proof를 수용하는 shape/cost 계약과 실제 encoder 연산에 맞는 비용 primitive를 함께 검증해야 한다. 기존 primitive의 뜻을 임의로 바꾸거나 거절 조건을 무조건 지우는 수정은 하지 않는다.

한편 W1/W5의 실제 첫 오류는 이 메시지가 아니라 `nrow`의 입력 `TRead X -1x-1`이었다. 일반 비용 경로의 `ensureKernelSizesKnown():409–418`은 matrix/frame의 양 dimension이 양수인지 검사한다. 따라서 **로그의 첫 증상**, **부족한 domain**, **후속 transform cost 지원 공백**은 구분한다. nrow의 부분 shape 증명이나 binding 전파를 고칠 여지도 별도 검토 대상이며, 이 문서가 그 dataflow의 단일 원인까지 실측으로 확정한 것은 아니다.

slot1 M의 shape/크기·privacy, 함수 경계를 지나는 shape, 이후 scale/split의 coverage까지 확인해야 한다. X0의 폭 하나를 증명한 것으로 전체 program의 exact cost와 feasibility가 완성됐다고 주장하지 않는다.

## 6. worker metadata 수집은 무엇을 실제로 공개하는가

현재 DML은 `cofeePublicRecodeMetadata:true`, 실행 설정은 `sysds.privacy.allowPublicRecodeMetadata=true`를 사용한다. `TransformEncodeMetadataPrivacy.java:35–115`는 두 opt-in과 제한된 spec을 함께 검증한다. `MemoPrivacy.java:1082–1095`는 해당 multi-return의 **metadata slot1**을 공개 대상으로 취급한다. 원본 row-level matrix 전체를 PUBLIC으로 바꾸는 기능이 아니다.

현 FED runtime의 동작은 다음과 같다.

1. `MultiReturnParameterizedBuiltinFEDInstruction.java:159–211`: 정책 확인 후 worker별 encoder build를 요청하고 logical range 순서로 merge한다.
2. 같은 파일:360–389: worker는 `FrameObject`를 읽어 `encoder.build(fb,1)`을 수행한다.
3. 반환값은 count 하나가 아니라 **built MultiColumnEncoder와 column names**다.
4. `ColumnEncoderRecode.java:264–317,340–363`: dictionary union, token/code·distinct metadata, dictionary 직렬화가 포함된다.
5. instruction:219–227: global metadata를 만든 뒤224행부터 실제 X0 encoding을 수행한다.

따라서 현재 코드 재사용을 **“category는 전혀 밖으로 나가지 않는 count-only 집계”라고 설명하면 틀리다.** global dictionary 공개, worker별 partial dictionary, 어느 shard에 category가 있는지의 노출은 구분해야 한다. 새로운 collector의 권한·보관·공개 범위가 기존 policy에 맞는지 검토해야 하며, private-aggregate라는 이름만으로 모든 metadata 공개가 허용되는 것은 아니다.

또한 P2의 입력은 numeric matrix이고 encoder helper는 FrameObject를 요구한다. collector는 기존 `as.frame`의 value conversion/equality 의미를 유지해야 한다. 필요한 worker 측 변환·읽기·materialization 비용을 생략할 수 없다.

## 7. 최소한의 해결 설계와 검증 위치

아래는 구현 완료 목록이 아니라 **검증 가능한 작업 분해**다. 새 범용 cache/대체 encoder를 추가하기보다 기존 경계와 로직 재사용을 우선한다.

| 문제 | 구현·설계 위치 | 기대 효과 | 정확성 위험 / 검증 |
|---|---|---|---|
| Noncanonical ingress | ADR01/02, `MemoSources`, `CanonicalWorkerPool`, source supply 경계 | 원본 W3/W7을 왜곡하지 않고 지원할 경로 마련 | 실제 map과 canonical map을 혼동하지 않음. overlap/gap/worker순서/type/range 부정 테스트 및 planned movement 비용 검증 |
| exact domain 획득 | 기존 FED encoder build/merge 부분의 build-only 재사용 경계 | X0 폭에 필요한 증거 제공 | matrix→frame 의미, NA·중복·빈 domain·merge순서·partial failure. 실제 CP/FED encoder parity |
| artifact identity | 기존 source metadata acquisition과 검증된 immutable transform-domain fact 연결 | 동일 입력의 준비 결과만 재사용 | dataset content/sidecar/schema/spec/worker/range/policy/encoder version 변경 시 무효화. stale·부분 artifact 거절 |
| multi-return shape | `MemoShapes.multiReturnOutputShape` | exact X0 및 필요한 M 정보의 downstream 전파 | 각 slot·함수 호출·binding 구분, UNKNOWN 보존. 범위를 벗어난 spec에 적용 금지 |
| exact 비용 지원 | `MemoCostModel.multiReturnBuiltinCost` 및 기존 primitive adapter | dummycode를 proof 기반으로 비교 가능하게 함 | build/apply/통신/output ownership 중복·누락, 원 cost 재평가, optimum parity |
| Oracle/context | 기존 `MemoNativeRules` canonical key authority | 새로운 정보가 native capability에 영향을 줄 때 정확하게 구분 | RuleBuilder에 key 복제 금지. 실제 Oracle 결정 입력을 보존하고 context별 activation/privacy/coverage/UNKNOWN·DEFER 유지 |
| 완료 판정/측정 | 기존 Docker campaign과 별도 준비 receipt | cold/warm 및 실제 성공을 구별 | rc0만으로 성공 금지. OPTIMAL/fullcompile, worker binding·privacy·coverage, runtime 결과 별도 검증 |

### 권장 검증 순서

1. **geometry와 데이터 정보를 별개로 고정:** 아래 정적 검증을 회귀 fixture로 삼고 지원 계약 변경 범위를 먼저 명시한다. 이번 보고서에서 원본 shard를 수정하지 않는다.
2. **domain/shape/cost 회귀:** 입력이 같은데 domain만 다른 경우, 동일 domain count지만 dictionary mapping이 다른 경우, missing/unauthorized/stale 정보를 검사한다. 기존 recode-only 동작도 유지한다.
3. **runtime 동등성:** 허용된 Docker 실행에서 원본 encoder와 값·shape·M mapping·실제 ranges를 비교한다. RPC 완료 순서가 바뀌어도 의미가 유지돼야 한다. 원 raw rows 공개를 허용하지 않는다.
4. **통합 soundness:** feasibility/privacy/UNKNOWN·DEFER/worker binding/원 목적함수 재평가 및 exact optimum을 검증한다. 범위 밖을 INFEASIBLE로 위장하거나 미해결 후보를 조용히 지우지 않는다.
5. **실제 P2 W1/3/5/7 각1회:** 고정 DML/데이터/범위/privacy/seed·비용 모델을 사용하고 준비 단계 포함 여부를 공개한다. 완료한 뒤에만 시간 목표를 평가한다.

이 순서로 해결해도 새로운 downstream blocker가 드러날 수 있다. 현재 증거는 전체 완료나10초 달성을 보장하지 않는다.

## 8. 실험 경계와 시간 계산

**P2 spec에 맞춰 dictionary를 build하는 것은 transformencode의 의미적 준비 연산**이다. dataset의 공통 등록 통계라고 이름만 바꾸어 시간을 제외하지 않는다. 아래를 분리한다.

- `T_metadata`: 필요한 worker 읽기/변환/build, 통신, exact merge, identity·정책 검증.
- `T_plan_compile`: 유효한 artifact 이후 planning 시작부터 전체 RuntimeProgram 생성까지.
- `T_cold_to_ready`: artifact가 없는 최초 요청부터 runtime 직전 준비 완료까지 직접 잰 시간. 필요한 입력 변환/normalization 준비가 있다면 포함한다.
- 본 workload execution: compile-only와 별도. metadata를 runtime에도 재사용하는 경우 build를 중복 수행하는지, 원본 의미와 cost ownership이 같은지 공개한다.

**“최초 요청부터 runtime 이전까지10초”에는 필요한 metadata 수집도 들어가야 한다.** warm artifact가 있는 compile만10초이면 그렇게 한정해 보고한다. CPU/메모리/RPC/bytes도 별도로 기록한다. 수집 시간은 아직 측정하지 않았으므로 예상 speedup 수치를 제시하지 않는다.

현 no-RPC/network-none compile-only harness에 worker 수집을 숨겨 넣지 않는다. 기존 `run_LAN_docker.sh` 기반의 명시적 준비 단계와 그 receipt가 필요하다. render receipt의 `execution_authorized=false`는 그 자체로 live worker 실행 권한이 아님도 명확히 한다. 기존 no-new-metadata 비교와 metadata-assisted 비교를 분리하고, baseline이 같은 정보를 실제로 소비하지 못하면 그 정보 비대칭을 공개한다.

## 9. 검증 자료와 현재 미완료 범위

- [evidence manifest](evidence/p2-planning-blockers-2026-10-14/manifest.json): C26 네 조건과 candidate06 W3의 원로그/DML/result/render receipt, 입력 sidecar8개, source/spec 발췌 및 검증 스크립트·결과의 SHA를 보존한다.
- [정적 검증 결과](evidence/p2-planning-blockers-2026-10-14/analysis.json): DML ranges와 render receipt 일치, gap/overlap 없는 actual map, canonical 차이, 실패 결과·시간, local/global distinct 반례를 확인한다.
- source manifest는 current working tree와 candidate06 전체 파일 hash의 일치 여부를 기록한다. 현재 source 발췌를 새로 실행한 binary 증거로 취급하지 않는다.

재현 명령 — 저장소 루트에서 실행하며 **worker·SystemDS·dataset row payload를 읽거나 실행하지 않는다**. 동일 디렉터리의 `analysis.json`만 재생성한다.

```bash
python3 docs/evidence/p2-planning-blockers-2026-10-14/verify_p2_evidence.py
```

현재 완료한 것은 보고서와 정적 증거 검증이다. **actual K, metadata 수집 latency/privacy runtime 검증, ingress 확장, dummycode cost 지원, 최신 P2 네 조건 전체 compile은 미완료**다. 기존 작업 트리의 미검증 변경도 이 보고서의 검증으로 합격 처리하지 않는다.

문서 검증 결과: evidence31개 SHA 및 원본 복사 일치, source/spec12개 SHA, 상대 링크15개, 정적 검증 스크립트 PASS. 독립 읽기 전용 review에서도 구체적인 오류·과장이 발견되지 않았다. production build나 실제 workload 실행을 검증한 결과와는 구분한다.

**최종 요약:** P2의 해결 조건은 **실제 input map을 정직하게 표현하고**, **허용된 exact domain을 확보하며**, **그 proof를 shape·비용·runtime 계약에 일관되게 연결하는 것**이다. metadata 수집 하나만 추가하거나 ingress 검사 하나만 삭제해서 해결되는 문제가 아니다.
