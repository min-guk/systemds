# L2SVM Exact factor 크기 제한 조사·수정

## 기준과 목표

Derived sharing 변경은 `8ae75aff00cf790d1afa3820b3b7ce9a2492d04d`로 `origin/main`에 게시했고 원격 HEAD 일치를 확인했다. 게시 검증은 Java134, Python52, 자동 DML8, Heuristic6건 모두 통과했다. 이후 별도 branch `fix/l2svm-exact-factor-20261007`에서 이 문제를 처리한다. Workspace는 `/home/mchoi/w1357-derived-supply-main-20261007`다.

목표는 대형 L2SVM metadata fixture의 Exact 검증을 기존 `10,000,000` factor cells / `60,000,000` materialized cells 한도 안에서 완료하는 것이다. 후보를 줄이거나 한도를 올리지 않고, physical legality·canonical cost·실제 계획 선택을 보존한다.

## 확인된 원인

게시된 코드에서 `ExactPhysicalModelCertificateTest`를 다시 실행해 동일 오류를 재현했다. 8개 테스트 중7개는 통과하고 campaign이 `model.analyze()`에서 중단된다.

```text
EXACT_VE_FACTOR_LIMIT_EXCEEDED|cells=19322130|limit=10000000|input
```

`ExactPhysicalModel.analyze()`와 `solveLegalityOnly()`는 원본 decision domain과 canonical hard factors를 직접 solver에 전달한다. 그러나 모델은 이미 각 hard factor가 관찰하는 정보만으로 domain을 분류하는 observation encoding을 만들고 있다. Production optimizer는 이 표현과 exact reduction을 사용하지만 두 검증 도우미는 사용하지 않았다. 입력 factor 검사에서 실패하므로 elimination 순서를 바꿔도 첫 오류는 해결되지 않는다.

실제 큰 factor는 `l2svm.dml:124`의 multiply/matmul 사이 realization-support와 input-authority다. Domain 크기 `9,613 × 2,010 = 19,322,130`의 관계이며 둘 다 이미 lossless decomposition을 갖는다. 단순히 모든 조합이 불법이라 삭제할 수 있는 후보는 아니다.

| 항목 | 게시된 L2SVM 모델 |
|---|---:|
| Decision / physical alternative | 241 / 59,429 |
| Canonical hard factor | 676 |
| 분해된 canonical factor | 59 |
| Encoded hard factor / auxiliary | 794 / 118 |
| Canonical hard cells 전체 | 76,377,371 |
| Encoded hard cells 전체 | 16,191,892 |
| 최대 encoded 입력 factor | 4,119,830 |

게시된 immutable JAR을 사용하는 별도 metadata 진단에서 기존 `ExactPhysicalReducedSolver.prepareCompacted()`와 encoded hard factors의 legality solve는 기존 한도에서 통과했다. 반환한 원래 decision assignment를 canonical hard factors로 재검사한 비용도0이다. 최대 factor는7,820,918 cells, 전체 materialization은37,047,094 cells다.

그러나 같은 진단에서 비용 surface 생성 후 실제 `ExactPhysicalOptimizer.optimize()`는 `EXACT_VE_FACTOR_CELL_OVERFLOW`로 실패했다. `ExactCategoricalSolver.prepare():1580`의 separator 크기 계산에서 발생하며 `ExactPhysicalOptimizer:87`의 일반 compacted 경로다. 따라서 검증 도우미의 encoding 우회와 별개의 production 비용 최적화 문제가 있다. 가장 큰 encoded 입력 factor는4,119,830 cells이므로 입력 자체의19,322,130-cell 오류와 구분해야 한다. Cost encoding 선택과 중간 factor/order를 추가 조사한다.

추가 진단에서 비용의 dyadic certificate는 `CERTIFIED(q=-74, maximumSumBits=95)`다. 그러나 shared-source 표현은 `HEADER_DOES_NOT_DETERMINE_SELECTED_REFERENCE`로 적용되지 않는다. 한 internal header가 서로 다른 output reference를 묶어, header에서 selected source reference를 복원할 수 없기 때문이다. 숫자 정확도 제한이 원인은 아니다. 일반 표현에서 네 가지 elimination order는 모두 평가되며 가장 작은 것도 중간 separator가 `Integer.MAX_VALUE`를 넘는다. 현재는 solver 한도를 변경하는 대신, 기존 shared-source 표현이 정확히 복원할 수 있도록 header 구분이 누락된 경로를 조사한다.

독립 검토에서는 별도의 의미 불일치도 확인했다. Canonical required-output support는 같은 owner의 같은 `DURABLE_MAP`에 대해 upstream rule 차이를 허용한다. 하지만 shared-source의 reference channel은 exact `(rule, realization)`을 비교해 이 합법 조합을 제외할 수 있다. 따라서 header만 세분화해서 적용 거부를 없애면 충분하지 않다. 이미 canonical 모델에서 사용하는 `requiredInputSupportIdentity`를 reference channel에도 사용해야 한다. 비-durable layout은 이 identity 자체가 exact rule을 포함하므로 기존 제약을 유지한다.

첫 번째 무손실 후보인 output별 header 분할은 표현 적용에는 성공했다(59,429 rows →5,292 headers, 44 source variables, factor4,911,013 cells). 그러나 최선의 기존 order도 중간 factor가15,258,196,800 cells여서 여전히 실패했다. 별도 overlay의 weighted-fill order도 개선하지 못해 채택하지 않는다. 이는 한도를 늘리거나 검사 예외로 넘겨 해결할 오류가 아니다.

후속 overlay에서는 출력 support를 `H + 필요한 input references → output reference`의 조건부 함수로 표현했다. 그러나 header가5,292→5,259로만 줄고 factor cells는 약9.86m로 늘었으며 capacity 실패가 유지되어 이 변경은 되돌렸다. Support identity 정규화와 출력 support별 header 분할은 유지한다. 독립적인 raw-bit 검사를 통한 불필요한 factor 축 제거도 최선의 separator를15.26b→2.08b로 줄였지만 기존10m 한도에는 부족했다. 같은 predicate의 auxiliary501개를 합쳐도 최대462,369,600 cells가 필요했다. 이 변경과 일반화 arc consistency·추가 order는 production에 넣지 않았다.

최종 원인은 solver 사전 검사가 sparse 실행의 실제 저장량 대신 전체 조합의 dense 크기를 검사한다는 것이다. `BucketProjection`과 finite-support join은 이미 구현되어 있지만 `prepare()` 및 `solve()`가 projection 전에 원래 separator를32-bit dense index로 계산하므로 활용하지 못했다. 별도 overlay에서 raw logical 크기를 진단용 long으로 남기고 projection 뒤의 실제 저장량을 검사하자 대형 L2SVM production optimizer가 canonical 검증까지 통과했다.

| 동일한 대형 모델 | Dense 예측 | 실제 sparse 저장 |
|---|---:|---:|
| 최대 message cells | 15,258,196,800 | 3,905,024 |
| 입력+모든 message cells | 34,434,299,872 | 8,347,373 |

입력1,447,984 cells와 message6,899,389 cells를 합한 overlay 수치다. 최종 objective는1014.5096925175934ms이며 원래 canonical hard/objective 검사를 통과했다. 가장 큰 projected keyspace는71,318,016이지만 이 크기의 dense 배열을 만들지 않는다. 이 값은32-bit 범위 안이므로 기존 projected key, `valueMaps`, backpointer를 그대로 쓸 수 있다. 진단은 `.omx/l2svm-sparse-analysis/`에 있다.

Production budget 검사와 회귀를 포함한 중앙 Maven 검증도 기본3GB JVM에서 통과했다. 12클래스91건이 모두 통과하고, 최종 production L2SVM의 실제 저장량은 최대9,028,800/누적19,349,167 cells다. 이는 overlay 관측과 구별되는 최종 구현 수치이며 두 한도를 모두 만족한다. 원래241 decisions/59,429 alternatives,5,292 headers/44 references를 유지하고 objective bits4652134937446297763도 canonical과 같다. 테스트 중 Java source 변경0이며 증거는 `sparse-first-r1/`에 보존했다. 최신 main 통합 후 최종 회귀는 별도로 기록한다.

## 최소 수정 설계

1. 두 검증 도우미가 원래 decisions 뒤에 기존 hard auxiliary를 붙이고 `exactSolverHardFactors()`를 사용한다.
2. 이미 production에서 쓰는 exact reduction/compaction을 재사용한다. Cost surface가 없는 legality 검사에 비용 encoding을 만들지 않는다.
3. Solve 결과에서 원래 decision 개수만 반환하고, 원래 canonical hard factors로 다시 검사한다. 불일치는 실패로 처리한다.
4. `variables()`·`hardFactors()` 자체는 canonical 표현으로 유지해 독립 oracle과 final recost가 그대로 동작하게 한다.
5. Shared-source 내부 reference channel을 canonical required-output support identity로 정규화한다. 실제 selected receipt·이동·TW/TR·function boundary의 identity는 바꾸지 않는다.
6. 출력 support가 유일하게 복원되도록 내부 header를 구분한다. `a_v`/`b_e` candidate를 새로 만들거나 retained 선택을 되살리는 변경이 아니다. 원래 rows, 비용 congruence 검사, canonical 재검사를 보존한다.
7. Physical surface와 source-factor identity가 인증된 shared-source dyadic 경로에만 실제 저장량 기준의 준비 경로를 사용한다. 원래 일반 solver·tie callback 경로의 검사 계약은 유지한다. Encoding→reduction→compiled object의 authority와 최종 canonical recost는 그대로 검증한다.
8. Projection 전에 원래 dense 배열 크기를 요구하지 않는다. Projection 이후 실제 flattening은 기존 int 범위를 검사한다. Sparse map에 새 finite key를 넣기 전 단일 factor 한도, 이전 입력/message 누적량을 합한 전체 한도를 검사한다. Dense 전환은 그 배열 전체가 두 한도 안에 들어올 때만 허용한다. Statistics는 실행 후 실제 저장량을 보고하고 dense 예측과 구분한다.

## 실제 DML 검증

기존 joint-boundary Docker harness는 Local만 고정했으므로 그대로 실행한 결과를 Global 성공으로 보고할 수 없었다. `--planner local|global`과 canonical proof 선택을 추가했다. Global은 Exact receipt를 필수로 확인하고, Local의 proof 모드도 기존 adaptive checkpoint를 유지한다. 공통으로 선택된 비용·cost surface·상태·lifetime의 canonical 일치와 runtime fallback/repair0을 확인한다. 이 검증 장치는 test/probe 변경이며 production 후보나 runtime 동작을 바꾸지 않는다.

실제 builtin L2SVM의192×8 PRIVATE_AGGREGATE X/세 worker와192×1 local Y에서 Local·Global 모두 자동 선택→runtime을 통과했다. 8개 모델 계수 전체가 CP와 일치한다(최대 절대오차8.4134e-17). 두 플래너의 canonical objective는21.385960545366007ms, raw bits는4626712830428570801이며, 비용 surface·선택 상태·sharing lifetime 증명이 모두 참이다. Runtime fallback/repair와 audit 위반은0이다. 이 fixture는262 decisions/1,885 alternatives이며 대형 PUBLIC metadata의241 decisions/59,429 alternatives와 다르다. 따라서 이 성공으로 대형 capacity 해결을 주장하지 않는다.

| Docker run | Compilation / execution | 결과 |
|---|---:|---|
| `ml-l2svm-local-canonical-r1` | 15.334487s / 2.596s | PASS |
| `ml-l2svm-global-exact-r1` | 10.908019s / 2.106s | PASS; 최대 factor69,264 cells |

각1회 실행의 기능 검증이며 성능 비교 결론은 내리지 않는다. Run root는 `/grid/3/cofee-lm-sweep-mchoi-20260914/l2svm-exact-capacity-20261007/`다. 동일 수정의 Java14클래스77건, Python harness56건도 통과했다. 증거는 `correctness-r1/`에 보존했다.

새 solver, retained 차원, fallback, privacy/boundary 완화는 추가하지 않는다. 작은 fixture의 raw/encoded feasibility 동치, auxiliary projection, canonical 재검사, 기존 대형 L2SVM campaign을 회귀로 확인한다. 이후 실제 production optimizer 및 Docker DML 경로를 별도로 검증한다.

## 현재 증거

- 게시 직후 baseline: `target/l2svm-current-certificate.log`
- Immutable 게시 JAR의 factor/encoded 경로: `target/l2svm-published-paths.log`
- 진단 source: `target/l2svm-diagnostics/L2SvmFactorDiagnostics.java`
- 게시 baseline JAR·진단·해시 보존: `/grid/3/cofee-lm-sweep-mchoi-20260914/l2svm-exact-capacity-20261007/baseline-8ae75aff00/`

현재는 조사·수정 진행 기록이며 최종 성공 결과와 미해결 항목은 후속 검증 후 갱신한다.
