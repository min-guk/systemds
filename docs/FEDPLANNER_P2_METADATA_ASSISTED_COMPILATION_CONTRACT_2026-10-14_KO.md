# P2 metadata-assisted compilation 계약

**상태: 구현 체크포인트 및 Java 회귀 검증 완료. 실제 P2 Docker planning/fullcompile·시간·runtime 결과 동등성 검증은 미완료다. 실제 P2 성공 또는 10초 달성을 의미하지 않는다.**

최신 검증과 실행 도구 제약은 [main 체크포인트](FEDPLANNER_CURRENT_MAIN_CHECKPOINT_2026-10-14_KO.md)를 참조한다. 이 경로는 기본값 OFF인 대안이며, exact K 수집을 모든 planning의 필수 조건으로 정한 것이 아니다. 입력 데이터에 버전·인코딩 의미·공개 정책이 결합된 global column statistics를 보존/소비하는 방안은 별도 설계이고 아직 구현하지 않았다.

관련 문서: [근본 원인](FEDPLANNER_P2_PLANNING_BLOCKERS_ROOT_CAUSE_2026-10-14_KO.md), [source geometry 확장](FEDPLANNER_P2_SOURCE_GEOMETRY_EXTENSION_2026-10-14_KO.md).

## 1. 선택한 해결 방식

sealed P2의 원본 DML·입력·privacy·range를 유지한다. dummycode 폭을 추정하거나 상수로 넣지 않고, **명시적으로 허용된 planning 준비 단계에서 실제 worker의 encoder build 결과를 얻는다.** 기존 encoder의 domain/equality/NA/merge 순서를 재사용한다. 전체 transform apply, outlier 처리, scale, split 및 본 RuntimeProgram은 실행하지 않는다.

별도 persistent metadata artifact나 cross-run cache는 만들지 않는다. 각 planning 요청에서 다음을 순서대로 수행한다.

1. 모든 source의 geometry 및 privacy metadata를 획득·검증한다.
2. transformencode owner의 모든 reachable occurrence/context를 분석하여 입력이 동일한 직접 FED source의 `as.frame` 결과임을 증명한다.
3. 별도 planning-time metadata opt-in과 기존 public-recode release 정책을 모두 확인한다.
4. 준비 단계 소유의 FED 변수로 source를 읽고 worker-local numeric matrix→frame 변환을 수행한다.
5. 기존 FED encoder build/merge 및 metadata 초기화까지만 실행한다.
6. encoded matrix와 metadata frame의 크기만 immutable request-local proof로 추출한다. mutable encoder/FrameBlock을 proof에 보관하지 않는다.
7. 준비 소유 변수만 정리하고, proof를 shape 전파와 기존 정확한 비용 모델에 전달한다.

이 방식은 artifact parser·stale cache·외부 proof loader를 추가하는 방식보다 작다. source/spec가 같은 경우의 재사용도 **한 요청 안에서 검증된 source identity에 대해서만** 허용한다. shape가 같다는 이유로 다른 source를 합치지 않는다.

## 2. 실행·privacy 경계

- `sysds.fedplanner.memo.prepareTransformMetadata=true`: planning 중 metadata 준비 RPC를 허용하는 별도 opt-in이다. 기본값은 꺼짐이다.
- `sysds.privacy.allowPublicRecodeMetadata=true`와 raw spec의 `cofeePublicRecodeMetadata:true`도 필요하다. 기존 엄격한 recode/dummycode-only validator를 우회하지 않는다.
- 기존 compile-only/no-RPC lane은 그대로 둔다. 새 Docker lane의 이름은 **metadata-assisted compilation**이다.
- 본 workload execution time이 0이어도 worker 읽기·변환·dictionary build와 RPC는 발생한다. 이를 “worker 실행 0”이라고 표현하지 않는다.
- 기존 worker build는 partial dictionary 및 column names를 coordinator에 반환한다. 이는 count-only 수집이 아니며, 최종 global metadata만 공개하는 것과 관측 범위가 다르다. 새 opt-in은 이 준비 경로를 명시적으로 선택한다. 모든 보호된 행이나 encoded matrix를 coordinator로 collect하지 않는다.
- preparation 실패/부분 응답을 성공 proof로 바꾸지 않는다. global `CLEAR`, 다른 요청 변수 정리, runtime fallback은 금지한다.

## 3. 정확성 계약

### Context와 source

모든 reachable occurrence와 call context에 대한 binding 방문이 COMPLETE이고, 정상 witness가 하나 이상이며, 모든 witness가 동일한 source로 이어져야 owner-invariant proof를 설치한다. `as.frame`를 거슬러 올라갈 때 origin의 callFrame, guards, loop phase 및 cardinality를 보존한다. STOPPED/UNRESOLVED/zero-environment는 증명이 아니다. arithmetic·slice·변형된 입력을 원 source와 동일시하지 않는다.

source identity는 요청 소유 facts/sources와 source owner, 실제 endpoint/path/range/type/privacy snapshot에 묶인다. 같은 크기라도 다른 source에는 proof를 재사용하지 않는다. 외부에서 dimension만 채워 넣어 dummycode 비용 gate를 통과시킬 수 없어야 한다.

### Shape와 비용

shape 계산과 contextual cloned HOP의 output slot 적용은 같은 owner proof를 사용한다. clone HOP ID를 원본 owner ID로 간주하지 않는다. 비용 호출은 명시적으로 proof를 받으며 raw spec·입력 type/shape·출력 arity와 두 slot의 크기를 다시 확인한다. proof 없는 기존 API는 data-dependent transform을 계속 거절한다.

준비 단계는 plan 선택 이전의 고정 추가 작업이다. **본 실행의 transformencode 비용에서 build 비용을 빼지 않는다.** 실제 RuntimeProgram은 원래 transformencode를 수행하므로 build도 다시 수행한다. runtime encoder reuse는 이번 범위에 없다. 기존 feasibility/privacy/UNKNOWN·DEFER/worker binding 및 선언된 목적함수에 대한 exact optimum을 유지한다.

### 데이터 수명

이 경로는 **입력이 준비부터 본 실행까지 변하지 않는 static sealed workload** 계약이다. 현재 구현 대상은 일반 mutable live source의 snapshot isolation이 아니다. persistent artifact를 없애도 준비 이후 데이터 변경 문제 자체가 사라지는 것은 아니다.

Docker `:ro`는 container의 쓰기만 막으며 host 변경을 막지 않는다. 실제 실험 harness는 content 검증과 협력적 stage lease를 사용하고, 그 신뢰 경계를 기록한다. hash 자체나 lease 자체를 강제 filesystem snapshot으로 설명하지 않는다. 이 계약을 보장할 수 없는 배포에서는 opt-in을 켜지 않는다.

## 4. 실제 workload 평가 방법

실행 진입점은 `scripts/fedplanner/run_LAN_docker.sh --memo-metadata-planning`이다. 기존 sealed renderer·engine/cost hash 검사·fullcompile receipt 해석을 재사용한다. 원본 actual P2 W1/3/5/7을 조건/candidate마다 1회씩 실행한다. 작은 대체 workload의 성능으로 합격 처리하지 않는다.

시간은 다음처럼 구분한다.

| 지표 | 포함 범위 |
|---|---|
| `metadataPreparationSeconds` | compiler 내부 source read/cast/encoder build/merge/cleanup 준비 구간 |
| `fullCompileSeconds` | metadata 준비를 **포함**한 전체 compile |
| `coldToReadySeconds` | 필요한 snapshot 검증·worker lifecycle 준비부터 fullcompile 완료까지의 외부 wall; 정확한 배포 포함 범위를 receipt에 기록 |

metadata 시간을 fullCompile에 다시 더하지 않는다. JVM startup, remote validation 및 Docker lifecycle은 별도 외부 wall에 보인다. OS page cache를 강제로 비우지 않았다면 disk-cold라고 주장하지 않는다. warm 결과가 없으면 warm latency를 추정해서 쓰지 않는다.

성공 조건은 반환 코드만이 아니라 OPTIMAL, worker count, full initial planning 및 RuntimeProgram compile receipt, 본 실행 0, metadata preparation receipt, worker failure/OOM 없음과 준비 resource 정리 증거다. 성능 수치와 runtime 수치 동등성은 서로 다른 검증이다.

## 5. 필수 회귀

- opt-in OFF 및 unauthorized spec: 준비 RPC 없음.
- 같은 shape·다른 source/path, multiple call sites, unresolved context: 부당한 proof 재사용 없음.
- 정상 alias/as.frame 및 cloned HOP: exact slot shape 전달.
- 기존 encoder와 duplicate/NA/global domain/merge order/metadata mapping parity.
- FED cast map의 datatype/schema/ranges 및 값 보존.
- partial worker failure: proof 미설치, 준비 소유 ID만 정리.
- no-proof dummycode cost 거절, spec/shape/arity mismatch 거절, 현재 policy opt-out 거절.
- 기존 soundness 회귀 및 실제 P2 W1/3/5/7 planning/fullcompile.

**잔여 위험:** P2의 이후 scale/split/binding/compile 경로에 독립적인 지원 공백이 있을 수 있다. metadata 수집 성공만으로 P2 전체 성공을 주장하지 않는다. <10초 여부도 실제 준비 포함 측정 후 판정한다.
