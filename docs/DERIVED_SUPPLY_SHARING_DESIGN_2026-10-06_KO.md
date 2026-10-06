# Native 실행 후보와 supply 및 파생 공유의 분리

> FOUT staging lifetime은 이후 [별도 설계와 검증](INVARIANT_FOUT_SHARING_2026-10-06_KO.md)에서
> 수정했다. Immutable 원본의 단순 staging은 새로운 logical value/version으로 취급하지 않는다.

- 기준 origin/main: `eb64f9c939708735940f2ae095c5c8bd526decf7`
- 새 작업 폴더: `/home/mchoi/w1357-derived-supply-sharing-20261006`
- 브랜치: `refactor/derived-supply-sharing-20261006`
- 기존 작업 폴더와 실험은 변경하지 않는다. 구현과 증거는 이 작업 폴더에만 둔다.

## 조사 결과와 변경할 실제 경계

현재 main에는 transient/retained supply enum이나 retained-copy 선택 변수가 없다. `ExactPhysicalModel.Alternative`는 execution, input authorities, output realization, derived output movement를 하나의 행으로 열거한다. DIRECT_FOUT/RELOCATION은 이미 있는 map의 사용과 실제 이동의 구별이므로 삭제할 중복이 아니다. TW/TR의 transient라는 이름도 retention과 무관하다.

`ExactPhysicalCostModel.addPhysicalCompiledTransferFactors`는 이미 선택된 demand의 activation과 source creation context, value version, layout에서 공유를 유도한다. OR 보조 변수는 그 비용 함수의 인코딩이다. 반면 `addPhysicalUnaryFactor`에는 native 실행과 출력 이동의 회계가 섞여 있다. Runtime `FEDRefedInstruction`은 계획의 실제 공유 여부와 무관하게 owned cache를 사용한다.

따라서 없는 retained 차원을 삭제했다고 주장하지 않는다. 실제 목표는 native 실행/supply representation을 분리하고, 기존 정확한 공유 비용을 유지하면서 runtime retention을 선택된 공유 수요에 연결하는 것이다.

## Representation 및 불변식

1. `a_v`의 identity는 execution, required input states, native output state/layout이다. Derived upload의 native output은 `DerivedFoutMaterializationActionKey.sourcePlacement`에서 얻는다. Post-operation action, moved output receipt, retention은 identity에 포함하지 않는다.
2. `b_e`는 source/value provenance, supply action, exact target state/layout이다. Output movement도 별도 supply다. Availability/creation scope는 provenance/action의 계약으로 보존한다. 같은 배치의 다른 source/version은 합치지 않는다.
3. 기존 graph-owned `Alternative`/receipt는 분석·lowering의 정확한 권한과 합법 tuple의 증거로 보존할 수 있다. 이 내부 관계 행을 native 후보라고 부르지 않는다. Native/supply 투영의 무조건 Cartesian product는 금지한다. 같은 native 실행에 correlated supply 행이 여러 개면 정확한 관계로 연결한다.
4. 기존 solver의 shared-source/observation encoding과 canonical receipt decoder를 재사용한다. 새로운 표현은 원래 합법 행과 양방향 대응해야 한다. 내부 관계 auxiliary가 필요하면 명시하고 candidate 수, relation witness 수, solver factor 수를 따로 보고한다. 가짜 후보 감소를 보고하지 않는다.

## Cost와 runtime

- 연산 실행의 ownership 및 fused kernel ownership은 유지한다. Explicit upload/collect/redistribution 비용은 supply contribution으로 구분한다. Native FED 연산의 본래 반환 응답 비용과 별도 강제 이동은 구별한다.
- 공유는 선택된 source/value, layout, creation action, availability/lifetime에서 유도한다. 기존 activation OR factorization을 유지한다. Canonical recost와 Global/Local의 동일성을 검증한다.
- 하나의 DAG 실행에서 동일 materialization의 여러 소비자는 기존 한 movement Lop의 출력을 공유한다. 그 출력은 일반 liveness에 따라 해제한다.
- 반복 실행 사이의 공유가 비용 모델에서 인정된 경우만 source/value lifetime까지 유지하도록 최종 계획에서 metadata를 유도한다. Invariant source의 loop 내부 REFED를 매번 다시 실행하도록 바꾸면서 비용만 한 번으로 두면 안 된다.
- Runtime key의 실제 MatrixObject identity, mutation version, dimensions, layout/type, thread 구별을 보존한다. 계획의 creation-action/group identity도 유지한다. 서로 다른 호출/반복의 새 값은 재사용할 수 없다.
- 계획에서 공유가 없는 단일 이동은 owned retained cache에 무조건 넣지 않는다. 모든 결과를 영구 유지하는 정책을 도입하지 않는다. Runtime fallback은 금지한다.

## 구현 순서와 검증

1. 기존 비용·공유·권한 회귀를 기준 빌드에서 실행하고 class/source와 결과를 동결한다.
2. Native/supply representation 및 exact relation을 추가하고 후보의 pure identity와 양방향 계획 공간 대응을 검사한다.
3. Supply 비용 ownership과 selected sharing metadata를 연결한다.
4. Plan realization에서 선택된 공유 lifetime만 유지하도록 한다.
5. 단일 소비자, 동일 source/version 공유, 다른 version, 다른 layout, loop/call creation context를 검증한다. Global/Local canonical recost 및 독립 유한 공간 검사를 포함한다.
6. 기존 Docker harness를 새 작업 폴더의 독립 run-id/output/stage로만 실행한다. 기존 컨테이너/실험을 중단하거나 재사용하지 않는다.

기준 회귀: 8개 class, 82 tests, 실패/오류/skip 0. 명령과 결과는 `.omx/derived-supply-sharing/baseline-tests.*`; 기준 class는 같은 디렉터리의 `baseline-classes`, `baseline-test-classes`에 보존한다.

## 위험과 범위

Public privacy fixture는 이번 명시적 사용자 검증 범위에 포함하므로 새로 skip하지 않는다. 이미 존재하는 다른 네 oracle 실패 및 큰 logreg factor overflow는 이 변경의 성공으로 숨기지 않는다. 새 표현의 합법성/비용 변화와 별도 원인을 구분한다. 새로운 runtime 연산 지원, recursive function 지원, 임의 pruning, 전역 solver 교체는 범위가 아니다.
