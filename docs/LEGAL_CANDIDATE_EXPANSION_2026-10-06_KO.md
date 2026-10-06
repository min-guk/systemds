# 합법성에 근거한 후보 확장과 pruning

기준은 fetch한 `origin/main`의 `ada24ffd4be4d17240f32f5bd1c2b5a98e1a0c8a`이다. 기존 boundary-seed 변경을 보존하고 그 위에서 후보 확장을 조사·수정했다. Java 329건과 Docker의 실제 builtin 학습 3건이 통과했다. 기존에 timeout이던 multiLogReg도 완료했다. generic oracle 및 dense 저장까지 모든 product를 제거한 변경은 아니다.

## 원칙과 logreg 병목

후보 하나가 합법이라고 그 후보들을 곱한 모든 조합이 합법인 것은 아니다. 같은 decision owner는 하나의 realization을 선택해야 하고, 함께 실행하는 FED 입력은 해당 실행 행에서 호환되는 worker 배치를 가져야 하며, CFG 분기 및 함수 경계의 source 상관관계를 지켜야 한다. 이미 이 조건을 위반한 prefix는 남은 축을 펼치지 않는다.

반대로 아직 고르지 않은 입력은 불법이 아니다. 부분 선택이 모순임을 증명하지 못했다면 남겨야 한다. 서로 다른 분기 행이 서로 다른 worker pool을 쓰는 것도 합법이다. 비싼 합법 후보도 다른 비용/제약과 결합하면 최적해가 될 수 있으므로 개별 비용이 크다는 이유만으로 삭제할 수 없다.

최신 main의 builtin multiLogReg에서는 baseline과 boundary-seed 버전 모두 300초 제한에 걸렸다. candidate thread dump는 `SharedRegionalPreparation.initialize → ExactPhysicalReducedSolver.reduce → ExactCategoricalSolver.freezeValidatedFactor → RepresentativeTruthEvaluator → Grounding.rows`를 가리킨다. `INITIAL_BOUND`와 `SEED_BOUNDARY` checkpoint 이전이므로 이 실행의 병목을 새 seed 개선 단계의 비용으로 해석하면 안 된다. unary/binary support 축소 후에도 남는 고차원 hard factor를 모든 조합에 대해 평가하는 경로이다.

## 조사한 경로와 처리

| 경로 | 기존 동작/합법성 근거 | 이번 처리 |
|---|---|---|
| `ExactPhysicalModel.expandAuthorityGroups`, per-link 확장 | 전체 product 후 동일 exact consumer anchor 검사 | 같은 검사를 prefix마다 적용. 서로 다른 metadata ID여도 동일 물리 worker pool이면 유지 |
| `JointPhysicalCostRows.executionRows` | mixed authority product 생성 후 source relation 검사 | relation에 확장될 수 있는 prefix만 재귀 확장 |
| `JointPhysicalCostRows.possibleExecutionRows` | 비용 불변성 증명용 작은 superset 생성 후 pool 검사 | 증명된 cross-pool 충돌을 prefix에서 제거. 기존 128-combination 한도는 증명 포기 기준이며 후보 제한으로 바꾸지 않음 |
| `PlacementRelationClosure.enumerateReferenceProducts` | 같은 reader owner가 여러 input position에 나타나도 독립적으로 곱함 | 같은 owner의 상충 reference를 prefix에서 제거. compatible duplicate 및 기존 순서 유지 |
| `ExactCategoricalSolver.freezeValidatedFactor` | lazy factor의 모든 dense cell에서 callback 호출 | opt-in hard proof가 모든 completion을 0 또는 infinity로 증명하면 subtree를 한 번에 채움 |
| `ExactPhysicalSharedSourceEncoding.materialize` | 별도의 전체 callback 루프 | 같은 proof-aware materializer 재사용 |
| `ExactHardFactorObservationDecomposition` | 각 축의 관측 category는 축소하지만 마지막 truth factor는 고차원 | category 대표값 mapping에서 partial proof를 보존 |
| `ExactPhysicalReducedSolver.restrictToSupportedValues` | unary/binary 축소 뒤 plain lazy wrapper로 원래 값에 mapping | 지원 domain mapping에서도 partial proof를 보존 |
| `NativePlacementContinuity.enumerateImmediateSupports`, `PlacementRelationClosure.enumerateBindingAssignments`, `CandidateSelections.solvePolicyRows` | 기존 동일 owner/pool/support prefix 검사 있음 | 기존 검사 보존 |
| `ExactFiniteSupportJoin`, sparse elimination, `DenseFactor.projectedFiniteCells` | finite support natural join 또는 finite tuple alias만 확장 | 기존 합법성 기반 경로 보존 |
| `LogicalBoundaryRealizations.enumerateOptions`, grounded replay products | 서로 다른 writer/source의 독립적 선택; 이질적인 pool도 합법 | 근거 없는 pool-equality/개수 제한 추가 안 함. 일반 product 자체는 남음 |
| `PlacementCandidateGenerator.enumerateInputCombinations`, `OracleFacade.backtrackCandidates` | operator oracle가 완성된 FType 조합을 판정 | generic partial legality 계약이 없으므로 자의적인 prefix 삭제 안 함. 이 경로 전체를 제거했다고 주장하지 않음 |
| solver separator, quotient certification, observation 비교 | 정확한 최소화/동등성 증명에 필요한 상태 비교 | 기존 finite-support 및 인증된 비용 pruning 유지. 이 루프 전체 제거는 별도 표현 변경 필요 |
| dense table 크기·stride, resource 예산, neighborhood scoring의 곱셈 | 저장/예산/점수 계산 | 후보 생성이 아니므로 유지 |

## hard factor의 부분 증명 계약

`PartialHardCostFunction.partialTruth`는 `-1`을 미할당으로 받는다. 결과는 `UNKNOWN`, `ALL_ZERO`, `ALL_FORBIDDEN`이다. 확정 결과는 **현재 prefix를 완성하는 모든 선택**에 대해 원래 callback과 같은 값을 보장해야 한다. generic lazy callback에는 이 계약을 자동 적용하지 않는다. 기존 generic callback의 방문 순서, invalid cost 검출 및 예외 순서를 보존한다.

freezer는 재귀 stack 대신 반복 순회로 원래 축·값 순서를 유지한다. 확정된 prefix 아래의 연속 dense 구간을 0 또는 infinity로 채우고 남은 조합의 callback을 호출하지 않는다. 이는 평가량을 줄이는 변경이다. dense 저장 자체는 여전히 곱셈 크기이며 기존 메모리/셀 preflight 한도를 유지한다. 이미 할당된 infinity 셀을 sparse 표현으로 완전히 없앴다고 주장하지 않는다.

`Grounding`은 미할당 owner, 선택됐지만 receipt가 없는 owner, 확정된 pool을 구분한다. 미할당은 unknown이다. 선택된 source의 realization 상충, 근거 없는 cycle, source 부재, 확정 pool 간 충돌은 이후 선택으로 복구할 수 없는 경우만 forbidden이다. immutable invariant pool의 기존 증명은 재사용하지만 현재 선택에 의존하는 부분 결과를 다음 조건부 문제에 캐시하지 않는다.

joint factor는 VALUE_MAP reader가 선택될 때만 적용되는 guard를 갖는다. guard가 아직 미확정이면 내부 row의 실패만으로 전체 factor를 forbidden으로 만들지 않는다. `CP/LOUT` consumer는 기존 canonical 규칙대로 zero다. CFG 각 행의 pool을 독립적으로 검사하여, AA/BB 상관관계의 합법성을 보존하고 AB/BA의 불법성을 구분한다.

## 비용 pruning과 남는 한계

이번 변경의 직접적인 pruning 근거는 합법성과 일정한 hard-factor 값이다. 비용 pruning은 기존 incumbent와 검증된 lower bound, exact arithmetic 계약을 유지한다. root factor의 합법 행을 개별 가격만 보고 지우지 않는다. partial evaluator가 unknown이면 완성된 조합에서 판정해야 하므로 모든 product가 사라지는 변경은 아니다.

row별 hard factor 분해 또는 worker-pool auxiliary를 이용한 완전 sparse 표현은 dense 공간까지 줄일 수 있는 별도 설계다. 그러나 현재 observation encoding과 shared-source profiling이 하나의 truth factor를 전제로 하고, 함수/loop의 provenance와 동률·조건부 복원까지 유지해야 한다. 이번 proof 기반 수정의 효과를 실제 workload에서 계측한 뒤 남는 병목과 구분한다.

## 검증 기록

전체 재컴파일 후 **41개 클래스 329건, failure/error/skip 0**, JAR 빌드 성공이다. 기존 generic callback 방문/오류 순서, 자원 preflight, 최적 비용과 동률 선택, finite-support join, observation/reduced-domain mapping, boundary fixed→free 복원을 포함한다. 모든 production/test source SHA가 빌드 시작 시 snapshot과 같다.

authority prefix 회귀는 변경 전 20개 suffix 방문(기대 0)으로 실패하고 수정 후 통과했다. source relation 확장은 무작위 200개 작은 product의 완전 열거 oracle와 합법 결과 및 순서가 같다. reference product 검사는 같은 owner 상충 이후의 축을 읽지 않는 것, compatible duplicate와 이질적인 독립 pool의 보존을 확인한다. physical proof는 branch/loop/function의 모든 완성 조합과 raw-bit table parity를 검사한다. 단순 branch에는 조기 pruning 기회가 없을 수 있으므로 함수 alias fixture 및 별도 factor에서 non-leaf pruning을 추가로 검증한다.

초기 통합 검사에서는 병렬 Maven이 남긴 오래된 main class 때문에 새 기능 검사 5건이 실패했다. 해당 기록을 숨기지 않고 `regression.log`에 남겼다. 빌드를 직렬화하고 **1,642개 main source 전체 재컴파일**을 확인한 최종 `regression-final.log`만 완료 근거로 사용한다.

### 실제 ML training

`run_LAN_docker.sh --joint-boundary-e2e`의 `legal-prefix-candidate-01`은 **PASSED**다. 동일 이미지·192×8 입력·3-worker ROW·PRIVATE_AGGREGATE X·public local labels·4 CPU·8GiB·3GiB coordinator heap을 유지했다. 비교 대상은 같은 최신 main 위의 boundary-seed만 적용한 `latest-main-candidate-01`이다.

| builtin | 변경 전 planner | 변경 후 planner | 변경 후 전체 compilation | 학습 실행 | CP/FED 계수 비교 |
|---|---:|---:|---:|---:|---|
| `multiLogReg` | 300초 process 제한, 미완료 | 44.728초 | 89.965초 | 3.869초 | 16/16, 최대 절대 오차 2.23e-16 미만 |
| `l2svm` | 2.737초 | 2.127초 | 10.658초 | 0.970초 | 8/8, 최대 절대 오차 8.42e-17 미만 |
| `lmCG` | 1.085초 | 0.857초 | 5.614초 | 0.606초 | 8/8, 최대 절대 오차 1.23e-15 미만 |

300초는 FED process 전체 제한이며 44.728초는 공통 analysis를 제외한 planner 구간이다. 서로 같은 범위의 speedup ratio로 나누지 않는다. 변경 후 logreg의 공통 analysis만 43.897초로, 아직 작은 데이터에 비해 planning 준비가 비싸다. 공유 호스트에서 trace/audit를 켠 단일 대조 실행이므로 통계적 속도 향상을 일반화하지 않는다.

logreg의 큰 truth factor는 지원 domain 축소 후 `[8, 1, 104, 17, 104, 17]`, **25,006,592 logical cells**였다. 이번 실행에서 partial 판정은 2,547,709회이고, non-leaf에서 증명한 340개 subtree가 22,602,112개 셀을 덮었다. 약 90.4%의 셀은 개별 완성 조합을 평가하지 않고 채웠다. 이 factor의 준비는 20.175초다. `leafCalls=0`만으로 일을 모두 없앴다고 판단하지 않는다. partial evaluator도 완성된 tuple에서 실행될 수 있으므로 위 partialCalls와 non-leaf subtreeCells를 함께 기록한다. 대부분의 큰 subtree는 합법인 zero 영역의 상수 증명이며, 이를 전부 불법 후보 삭제 수로 해석하지 않는다.

logreg는 이후 seed를 1521.6329→122.2663 modeled ms로 개선하고 추가 merge 뒤 gap 1.43%에서 `TARGET_REACHED`였다. 전역 최적 증명은 아니다. l2svm/lmCG의 최종 modeled 비용은 변경 전후 각각 21.385960545366007ms, 21.2628479582568ms로 같다. 세 실행 모두 audit mismatch/missing 0, runtime conversion 위반 0이며, class preflight와 physical proof 10건도 통과했다.

```bash
scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e \
  --run-id legal-prefix-candidate-01 \
  --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/boundary-seed-ml-20261006 \
  --case ml_logreg --case ml_l2svm --case ml_lm \
  --timeout-seconds 2400 --case-timeout-seconds 300
```

새 실행에는 다른 `--run-id`를 사용한다. [검증 집계](experiments/boundary-seed-20261006/legal-prefix-ml-validation.json)에 비교한 source 8개, manifest·입력/image/dependency 대조, phase 시간, 전체 계수 비교, checkpoint 및 factor 작업량이 있다. Raw evidence는 `/grid/3/cofee-lm-sweep-mchoi-20260914/boundary-seed-ml-20261006/legal-prefix-candidate-01`, build/RED/isolated 로그는 `target/candidate-pruning-evidence/`에 보존했다. 정식 staged campaign의 외부 모듈 부재와 소규모 합성 데이터 한계는 이전 검증과 같다.

### 게시 후 row 분해 타당성 확인

검증된 구현과 최신 main의 LogReg native-proof closure 수정은 `1dc7fbd7ca`로 `origin/main`에 게시했다. 통합 회귀는 Java **356/356 PASS**, 실제 builtin 학습 3종 및 ALS CP/FedAll 전체 출력 비교 PASS다. [최종 게시 기록](experiments/boundary-seed-20261006/main-publication-r2.json)에 결과를 보존했다.

후속으로 production을 그대로 두고 별도 source/class 복사본에 row별 support-owner 진단만 추가했다. 같은 Docker logreg에서 `gs = sum(S * Grad)`의 relation은 12개 row이며, 그중 **row 3·7·11**은 전체 큰 축을 필요로 했다. 원래 domain은 `[57,1,104,38,104,17]`, 기존 observation category는 `[8,1,104,17,104,17]`이다. 큰 축에는 Grad read와 Grad를 보존하는 else의 placement/read/write 연쇄가 함께 포함된다.

| 현재 observation keys를 유지한 표현 | 전체 저장 셀 추정 | 최대 factor 셀 추정 |
|---|---:|---:|
| 기존 whole relation | 25,029,616 | 25,006,592 |
| row별 독립 factor·observation link | 75,142,200 | 25,006,592 |

이는 지원 domain 축소 전 canonical domain과 truth·binary-link 크기로 계산한 표현 비용이다. 실제 peak heap 측정이 아니다. 다른 세 joint relation에서도 같은 방식의 row 분해는 총 저장량을 줄이지 못했다. 따라서 이 분해는 production에 적용하지 않았다. 더 거친 row별 observation, 공유 link, 논리적으로 증명한 pool-query 의존성 분해까지 불가능하다는 결론은 아니다. 다음 표현 개선은 이 loop-back 연쇄의 중복 의존성을 먼저 증명해야 한다.

[진단 결과와 재현 artifact](experiments/boundary-seed-20261006/joint-row-diagnostic.json)에 row scope·크기·source SHA를 보존한다. 진단은 factor/solver 선택을 바꾸지 않았고, 실행 시간에는 추가 grounding·출력 비용이 포함되므로 성능 개선의 근거로 사용하지 않는다.

진단 Docker 학습도 PASS이며 전체 16계수 CP 일치, audit/runtime conversion 위반 0, 게시본과 최종 modeled upper 122.26631334184357 일치를 확인했다. 실제 freezer의 25,006,592셀 및 partial/subtree 작업량도 동일했다.
