# Native candidate / supply / derived sharing 구현 보고서

> 후속 수정: 이 보고서의 invariant FOUT→fresh staging→REFED N× 설명은 당시 구현의
> 결과다. 현재는 원본 logical source/version을 유지하여 1×로 공유한다.
> [후속 수정과 검증 보고서](INVARIANT_FOUT_SHARING_2026-10-06_KO.md)를 참조한다.

## 작업 기준과 격리

- 기준 `origin/main`: `eb64f9c939708735940f2ae095c5c8bd526decf7` (`fix(fedplanner): track replay changes by candidate owner`).
- 새 worktree: `/home/mchoi/w1357-derived-supply-sharing-20261006`.
- 브랜치: `refactor/derived-supply-sharing-20261006`.
- 기존 작업 폴더 및 실행 중인 실험은 수정하거나 중단하지 않았다. 테스트와 빌드는 새 worktree에서 수행한다.
- 구현 전 조사·설계: [설계 문서](DERIVED_SUPPLY_SHARING_DESIGN_2026-10-06_KO.md).

## 기존 모델의 실제 문제

기준 main에는 `transient`와 `retained`를 두 supply 후보로 열거하는 enum이나 retained 선택 변수가 **없었다**. 기존 `Alternative`는 실행·입력 공급 권한·출력 배치·출력 이동을 함께 담는 합법 관계 행이다. 기존 비용 모델도 이미 source/version, creation context, demand activation에서 공유 비용을 유도했다. 따라서 존재하지 않던 retained 후보를 삭제하여 후보 수를 절반으로 줄였다는 설명은 맞지 않는다.

실제로 고친 경계는 다음과 같다.

1. 실행 후보와 이동 공급의 표현 및 비용 소유가 섞여 있었다.
2. `fed_refed` runtime이 선택된 계획의 공유 여부와 무관하게 retained cache를 사용했다.
3. 비용에서는 한 번으로 묶인 consumer별 action이 registry에서는 분리되어 두 movement 명령으로 내려갈 수 있었다.
4. FOUT → local staging → REFED는 매 실행 새 local 객체를 생성한다. 이 업로드를 원본 FOUT의 긴 lifetime에 맞춰 한 번만 과금하면 runtime과 비용이 달라진다.

## 변경한 representation

`ExactPhysicalNativeSupplyRepresentation`에서 기존 합법 행을 다음처럼 분리한다.

| 구성 | 의미 |
|---|---|
| `NativeCandidate` (`a_v`) | 선택된 execution, required input states, native output state/layout |
| `SupplyCandidate` (`b_e`) | source/value provenance, supply/movement action, target state/layout |
| `RelationWitness` | 어떤 `a_v`와 `b_e` 조합이 원래 graph-owned 합법 행인지 기록 |
| `SupplySharingGroup` | 선택된 demand 및 source creation/lifetime으로부터 유도하는 비용·실행 공유 관계 |

FED 계산의 native 결과가 로컬일 수 있으므로 **execution type과 native output state는 별도 필드**다. 연산 capability가 FED를 지원한다는 사실을, 선택된 CP 실행을 FED로 가격 매길 근거로 사용하지 않는다. 명시적인 post-output upload는 `OUTPUT_MATERIALIZATION` supply에만 들어간다.

Retention은 candidate identity나 solver의 독립 선택 차원이 아니다. 기존 `Alternative`는 정확한 합법 관계 및 receipt decoder로 유지한다. 투영한 후보들을 무조건 Cartesian product로 조합하지 않는다. Global의 shared-source encoding과 Global/Local 공통 비용 surface가 새 표현을 사용한다.

## 공유, 비용, 실행의 연결

- 같은 source/version, exact target layout, creation action/scope 및 activation 관계가 입증된 demand만 movement 비용을 공유한다. 같은 placement만으로 합치지 않는다.
- 기존 activation/OR auxiliary factorization을 유지한다. Operator execution 및 native return은 operator가, 명시적인 post-output/upload는 supply가 소유한다.
- 같은 DAG의 consumer별 relocation receipt가 같은 `physicalEmissionIdentity`이면 exact consumer input을 합쳐 registry authority와 movement Lop을 하나로 만든다. 전체 원래 receipt는 normalized plan과 hash에 남기고, runtime audit은 실제 emitted representative를 검사한다.
- 반복 실행 간 공유가 필요할 때만 selected plan에 `sharedSupplyLifetimes`를 유도한다. 이 정보는 normalized result → emission transaction → registry → 재컴파일 복원 → Lop → 직렬화된 FED 명령으로 전달한다.
- 반복 여부는 consumer만 속하는 loop도 검사한다. 예를 들어 확률 0.5인 분기의 2회 loop는 평균 실행 횟수가 1이어도, 분기가 선택되면 한 copy를 두 번 사용하므로 공유 lifetime이 필요하다.
- `REFED|...`와 `ACTION|...` 모두 기존의 유효한 물리 action identity다. FED/LOUT consumer의 input upload를 문자열 prefix 때문에 거부하지 않고, 정확한 selected movement 집합에 속하는지 검증한다.
- 단일 사용 REFED는 생성한 map을 직접 출력하며 owned retained copy를 만들지 않는다. 같은 DAG의 공유 결과는 일반 변수 liveness로 관리한다.
- 반복 공유 REFED의 key는 실제 `MatrixObject` identity, mutation version, dimensions, thread, layout/FType 및 group identity를 포함한다. 새 값·다른 객체·다른 layout은 공유하지 않는다.
- 계획에서 한 번으로 과금한 shared copy를 기존 LRU 한도 때문에 조용히 재생성하지 않도록, 해당 copy는 source value가 무효화되거나 제거될 때까지 유지한다. Legacy 명령의 기존 cache 정책은 유지한다.
- FOUT에서 새 local staging을 거치는 REFED upload는 consumer 실행 횟수만큼 과금하고, 원본 source의 retained lifetime을 상속하지 않는다.
- Planner가 만든 `fed_fout`는 producer 출력의 일반 liveness를 사용하며, legacy의 암묵적인 비소유 cache에 넣지 않는다.

## 후보 및 factor 수

동일한 fixture: federated `X`(8×3), local `p`(3×1), `pred=X%*%p; print(sum(pred));`.

| 항목 | 기준 main | 변경 후 |
|---|---:|---:|
| 원래 합법 authority 행 | 33 | 33 |
| 별도로 표현한 native 후보 | 별도 표현 없음 | 26 |
| 별도로 표현한 supply 후보 | 별도 표현 없음 | 41 |
| 정확한 relation witness | 원래 행 자체 | 33 |
| 원래 decision variable | 23 | 23 |
| Canonical hard factor / cell | 31 / 101 | 31 / 101 |
| Canonical cost factor / cell | 98 / 210 | 98 / 210 |
| Encoded solver variable | 37 | 37 |
| Encoded solver factor / cell | 112 / 336 | 112 / 336 |

26+41은 새 solver domain의 크기를 더한 수가 아니다. 이 fixture에서 검색 차원이나 factor 수가 줄었다는 결과는 없으며, 불필요한 retained 차원을 추가하지 않고 표현과 실행 책임을 분리한 결과다. 재현 probe는 `ExactNativeSupplyCountProbe`; 원본 결과와 실행 방법은 `.omx/derived-supply-sharing/native-supply-count-probe.*`에 있다.

## 주요 수정 지점

| 경로/클래스 | 주요 변경 |
|---|---|
| `fedExact/ExactPhysicalModel`, `ExactPhysicalNativeSupplyRepresentation` | native/supply catalog와 정확한 관계 생성 |
| `ExactPhysicalSharedSourceEncoding` | native/supply header와 기존 source reference encoding 연결 |
| `ExactPhysicalCostModel.physicalCostSurface`, `addPhysicalUnaryFactor`, `addPhysicalCompiledTransferFactors` | 실행·이동 ownership, derived sharing, fresh staging 비용 |
| `ExactPhysicalOptimizer`, `LocalPhysicalOptimizer`, `ExactPhysicalSelection`, `ExactPhysicalPlacementProjector` | canonical 선택에서 sharing lifetime 도출 및 투영 |
| `placement/adapter/*NormalizedPlannerResult*`, `PlacementPlannerAdapter` | 공유 metadata의 불변 snapshot·검증 |
| `PlacementEmissionTransaction`, `PlannerRuntimePlacementAudit` | physical emission 병합, 전체 receipt 보존, plan hash 및 runtime audit |
| `FederatedRefedRegistry`, `FederatedRefedPolicy`, `Dag` | registry·재컴파일·Lop lowering으로 lifetime 전달 |
| `FederatedRefed`, `FEDRefedInstruction`, `FederationUtils` | 명시적 single/shared 실행, version/layout 안전성, cleanup |
| `FederatedFoutMaterialize`, `FEDFoutInstruction` | 계획된 output movement의 암묵적 cache 사용 제거 |

## 검증 결과

### 단위·통합 회귀

- 기준 main: 8개 class, **82 tests 통과**, 실패/오류/skip 0.
- 최종 소스: 35개 class, **308 cases 중 304 통과**, 실패/오류 0, 기존 skip 4. Maven exit 0. Skip은 `PrivacyMovementCertificationTest`의 기존 항목이며 이번 변경에서 추가하지 않았다.
- 새 native/supply 투영 테스트는 33개 원래 authority 행의 relation 복원 및 미인증 조합 거부를 검사했다. Loop entry 유한 공간 검사는 **raw 32,928 / admitted 292 / physical 10**으로 통과했다.
- Global/Local 공통 surface, canonical raw-bit recost, activation OR encoding, source/function alias, fused kernel ownership, selected plan emission 및 runtime audit 회귀를 실행했다.
- 보호된 native-local fixture의 raw contribution bits digest는 `ed382489615a62cb3bccecf5f90264d071a1a8413505a8f60dc15bbfe2dda2db`로 유지됐다. Operator/movement ownership 이름이 명시되면서 structure/fingerprint digest는 의도적으로 바뀌었다. 숫자 digest를 바꿔 실패를 숨기지 않았다.
- Runtime `OwnedRefedReuseTest` 25개는 single-use 직접 출력, 공유 생성 1회, mutation/다른 객체/다른 group/layout/FType 구분, 큰 shared copy의 보존 및 source cleanup을 포함한다.

최종 선택 test class와 개별 결과: `.omx/derived-supply-sharing/verified-tests.json`. Maven 로그: `verified-regression.log`. 실행 JVM은 `ActiveProcessorCount=2`, Maven test fork 1, test heap 3 GiB로 제한했다.

### 실제 production cost surface에서의 movement 가격

아래는 wall-clock 측정이 아니라 기존 모델의 **밀리초 단위 비용**이다. DML을 파싱해 만든 production physical model에서 해당 supply의 canonical contribution을 평가했다.

| 선택된 supply | 비용 | creation 횟수에 해당하는 배율 | 반복 공유 metadata |
|---|---:|---:|---|
| Invariant local source → loop REFED | 1.002685546875 | 1× | 있음 |
| 매 iteration 갱신되는 local source → REFED | 3.008056640625 | 3× | 없음 |
| FOUT → 새 local staging → REFED | 3.008056640625 | 3× | 없음 |

`ExactCompiledSupplySharingTest`가 위 비율과 FED/LOUT 공급의 `ACTION` identity를 검사한다. `ExactActivationMaterializationCostTest`는 단일·공동 demand, 서로 배타적인 branch, invariant/updated loop lifetime, 조건문 안의 반복 및 canonical/solver 일치를 검사한다. 숫자 증거: `final-production-cost-prices.log`.

동일 physical action의 consumer별 receipt는 emission 테스트에서 authority 1개와 두 input obligation으로 내려갔다. 다른 anchor/layout은 authority 2개로 유지됐다. 같은 placement여도 다른 source 객체/version/group이면 runtime creation을 공유하지 않았다.

### Docker E2E

`scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e`를 사용한다. 새 고유 컨테이너, pinned image, network none, 4 CPU / 8 GiB, 동결한 class/source/dependency를 사용한다. 기존 컨테이너는 사용하거나 중단하지 않았다.

- 첫 통합 실행: 12개 케이스 모두 기대 결과, model proof 10 tests 통과, class preflight 통과, 계획 밖 runtime conversion 0.
- 최종 추가 경계 수정 후에도 **12/12 기대 결과 충족**, model proof 10 tests 및 class preflight 통과, 계획 밖 runtime conversion 0. `derived-sharing-verified-r2/result.json`의 status는 `PASSED`다.
- 케이스: L2SVM true/false, correlated/independent branches, loop toggle, 함수 호출, branch upload, private 조합 및 protected-Y 거부.
- 증거 루트: `/grid/3/cofee-lm-sweep-mchoi-20260914/derived-supply-sharing-20261006/`.
- 최종 Docker에 동결한 main/test source 및 class가 작업 폴더의 최종 파일과 일치하는지도 SHA-256으로 확인했다. 증거: `.omx/derived-supply-sharing/final-docker-source-class-match.json`.

재현 명령:

```bash
scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e \
  --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/derived-supply-sharing-20261006 \
  --stage-root "$PWD/target/derived-supply-sharing-e2e" \
  --run-id <새로운-run-id> --timeout-seconds 1200 --case-timeout-seconds 300
```

### 기준 main에서도 발생하는 별도 실패

`IndependentCompletePlacementSpaceTest`의 4개 중 3개는 `bounded protected receipt needs an exact worker map`으로 실패한다. 변경 소스와 frozen baseline에서 동일하게 재현했다. 별도로 확인한 KMEANS oracle 실패도 baseline에서 재현된다. 이를 이번 변경의 통과 결과에 포함하거나 테스트를 수정해 숨기지 않았다. 전체 repository 테스트가 모두 정상이라고 주장하지 않는다. 증거: `baseline-independent-complete.log`, `baseline-kmeans-oracle-evidence.txt`.

## 범위와 남은 한계

Shared copy의 lifetime은 **source value lifetime이라는 보수적인 상한**이다. 마지막 consumer 직후에 정확히 해제하는 새 liveness 분석은 추가하지 않았다. Source가 오래 살아 있으면 실제 마지막 공유 사용 이후에도 copy가 남을 수 있고, 여러 큰 공유 copy의 합계 메모리는 기존 opportunistic cache 한도로 제한되지 않는다. 모든 movement를 영구 retain하는 방식은 아니며, source 변경·제거 및 실행 정리 시 해제한다.

새로운 연산 지원이나 임의 후보 pruning은 추가하지 않았다. 기존의 source/version, privacy, TW/TR 및 함수 경계 계약을 보존한다. 전체 benchmark의 planning/runtime 속도 개선을 주장하지 않는다.
