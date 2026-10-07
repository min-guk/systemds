# L2SVM Exact planning 수정 및 검증

## 기준과 결과

Derived supply sharing은 `8ae75aff00cf790d1afa3820b3b7ce9a2492d04d`로 `origin/main`에 먼저 게시했다. 이후 L2SVM 수정 `7945e2aab1`에 최신 main `241b9c491a56e46e41c197b461801ec4b9b0948a`를 통합했다. 통합 검증 commit은 `7f35faa05d2cf89068eaced423589f3f079f4c97`, 후속 비용 snapshot 수정은 `f0f919f023`이다. Workspace는 `/home/mchoi/w1357-derived-supply-main-20261007`이다. 기존 workspace와 다른 실행은 수정하지 않았다.

대형 PUBLIC L2SVM의 production Exact optimizer가 기존 **factor당10,000,000 / 누적60,000,000 cells, 기본3GB JVM**에서 통과했다. Physical candidate를 삭제하거나 제한을 높이지 않았다. 최종 목표 비용은 **1014.5096925175934ms**, canonical 재계산과 raw bits까지 같다.

최신 main 통합 회귀는 Java253건 중252 PASS/1 ERROR였고, 후속 비용 snapshot 수정을 포함한 집중 회귀는 **19클래스84/84 PASS**다. Python56/56과 Maven package도 통과했다. 확대 회귀의 오류는 campaign에서 L2SVM 다음에 실행하는 별도 PUBLIC LogReg의 용량 제한이다. 최종 Docker에서는 **Local/Global L2SVM6건과 sharing8건 모두 통과**했다. Strict canonical proof, CP 결과 비교, runtime audit 및 fallback/repair0을 확인했다. 세 실행의 frozen 소스·클래스·dependency 전체가 현재 빌드와 일치한다.

## 기존 코드가 실패한 이유

### 1. Legality 도우미가 이미 있는 encoding을 우회했다

`ExactPhysicalModel.analyze()`와 `solveLegalityOnly()`는 canonical hard factors를 그대로 dense 입력으로 넘겼다. L2SVM의 realization-support/input-authority 관계는 `9,613 × 2,010 = 19,322,130` cells여서 solver에 들어가기 전에10m 제한에 걸렸다.

모델에는 이미 동일한 관계를 보조변수로 분해하는 observation encoding이 있었다. Production optimizer가 사용하는 이 표현을 두 도우미에도 적용하고, 기존 reduction/compaction을 사용했다. 해를 구한 뒤 원래 decision 부분만 돌려주고 **원본 canonical hard factors로 다시 합법성을 검사**한다.

### 2. Shared-source 표현의 support 일치 기준이 canonical 모델과 달랐다

Canonical required-output support는 같은 owner의 같은 `DURABLE_MAP`이면 rule이 달라도 호환 가능하다. Shared-source channel은 exact `(rule, realization)`을 요구해 이 조합을 제외할 수 있었다. 또한 하나의 internal header가 서로 다른 output support를 묶어 `HEADER_DOES_NOT_DETERMINE_SELECTED_REFERENCE`로 표현 적용을 거부했다.

공통 `CandidateSelections.requiredInputSupportIdentity`를 사용하고, output support를 유일하게 복원할 수 있도록 **내부 header만 세분화**했다. 비-durable support의 exact rule 제약, 실제 선택 receipt, movement 및 TW/TR·함수 경계의 일치성은 유지한다. 물리 candidate를 추가하거나 retained 선택 차원을 다시 넣는 변경은 아니다.

### 3. 실제 sparse 저장량 대신 dense 전체 조합 수를 제한했다

앞의 두 수정을 해도 separator의 논리적 Cartesian product가15,258,196,800 cells여서 실패했다. 기존 solver에는 `BucketProjection`과 finite-support join이 있지만, projection 이전에 원래 product를 dense `int` 배열 크기로 계산하므로 사용할 수 없었다.

**인증된 physical shared-source dyadic 경로**에서만 기존 sparse kernel을 먼저 적용하고 실제 저장량으로 budget을 검사하도록 했다. 새 solver나 근사 pruning은 넣지 않았다.

- Map에 새 finite key를 넣기 전에 단일 factor 및 누적 budget을 검사한다.
- Dense로 전환할 때는 전체 배열 길이가 두 한도 안에 들어가는지 검사한다.
- Join 중간 row/index도 증가 전에 한도를 검사한다.
- Projection 후 실제 keyspace는 기존 int indexing 범위를 검사한다.
- 일반 solver와 tie callback 경로의 사전 검사 계약은 유지한다.
- 물리 cost surface 인증 → encoding → reduction/compiled object binding → solve → canonical recost 순서를 유지한다. 숫자 정확도만 인증한 certificate로 새 경로를 호출할 수 없다.

이는 합법 계획을 버리는 최적화가 아니라, 동일한 factor를 필요한 크기로 저장하는 수정이다. 불가능한 tuple은 원래 hard constraint가 배제한다.

### 4. Runtime 재컴파일 이후의 비용 재구성이 live HOP 추정치를 읽었다

통합본의 첫 Docker(`r2`)에서 federated Y true/false 사례는 Local/Global 모두 학습을 끝냈으나, 이후 strict canonical proof에서 cost fingerprint가 달랐다. 선택된 objective·배치·sharing lifetime은 일치했다.

별도 진단에서 같은 immutable analysis로 runtime 없이 surface를3번 만들면 fingerprint와 모든 contribution 순서가 같았다. 실제 실행의 전후 비교에서는 analysis·candidate·domain·hard factor 해시와 비용 factor0~974가 같고, l2svm75의 placement Y→TW Y transfer부터 값이 달라졌다. 전체 contribution 수는1,360→1,358이었다.

원인은 `estimatedBytes()`가 runtime/recompile이 변경한 `Hop.getOutputMemEstimate()`를 다시 읽는 것이다. 이 bytes가 비용뿐 아니라 transfer grouping key에도 들어가므로 그룹 수까지 바뀐다. 단순 해시 순서 불안정이 아니다. 분석 시점의 transfer estimate 입력을 immutable fact로 고정하고 같은 분석의 재검산에 재사용하도록 수정했다. Occurrence별 raw/effective output/upload, shape·nnz, multi-return estimate 및 function source/read fallback을 보존한다. 원래 estimator 우선순위와 가상 노드의 fallback을 유지하며 전체 surface fingerprint 비교도 유지한다.

회귀는 기존 frozen build에서2건 모두 실패하고 수정 후 통과했다. 같은 분석의 전송 추정치를 이후 갱신해도 fingerprint와 transfer keys/group이 같고, 초기 추정치가 다른 새 분석은 구조 fingerprint가 같아도 전송 비용과 cost fingerprint가 달라짐을 확인했다. 임의의 연산 비용 변경까지 불변이라고 주장하는 테스트는 아니다.

## 변경 파일

| 클래스/함수 | 변경 내용 |
|---|---|
| `ExactPhysicalModel.analyze/solveLegalityOnly` | 기존 hard encoding 재사용, 원래 decisions 투영, canonical hard 재검사 |
| `ExactPhysicalSharedSourceEncoding` | 공통 support identity와 selected output support별 header 구분 |
| `ExactPhysicalReducedSolver.prepareSharedSource` | surface/source factor authority를 확인한 뒤 deferred 준비 |
| `ExactPhysicalOptimizer`, `ExactEliminationOrderPolicy` | 인증된 shared-source 실행 경로 연결, 기존 order 선택 유지 |
| `ExactCategoricalSolver` | projection 우선 실행, 실제 저장량 budget 및 통계 |
| `ExactFiniteSupportJoin` | 중간 finite-support 저장 증가 전 budget 검사 |
| `PlacementAnalysis` 및 `ExactPhysicalCostModel` | 실행 전에 비용 추정 입력 고정, 실행 후 동일 transfer cost surface 재구성 |
| `run_joint_boundary_e2e.py` | Local/Global 선택, strict canonical proof 및 stale class 방지 |
| `AutomaticSupplySharingDockerProbe` | 기존 probe에 검증된 DML named arguments 지원 |

관련 회귀에는 raw/encoded legality, canonical support 교차 rule, output header 관계, assignment/동률 representative parity, 잘못된 certificate 거부, factor/누적 budget 초과, oversized dense 전환 거부가 포함된다. Runtime 공급 후보·실행 코드·privacy 정책은 이 capacity 수정에서 바꾸지 않았다.

## 대형 L2SVM 수치

Fixture는 PUBLIC federated X50,000×2,100과 Y50,000×1, 각각2분할, builtin `l2svm`, `maxIterations=30`이다. 실제 파서·공통 분석·물리 cost surface·production optimizer를 실행하는 metadata 회귀이며, 이 크기의 전체 수치 학습을 실행했다는 뜻은 아니다.

| 항목 | 원래 모델/일반 표현 | 수정 후 |
|---|---:|---:|
| Physical decisions | 241 | 241 |
| Physical alternatives | 59,429 | 59,429 |
| Canonical hard factors | 676 | 676 |
| Canonical cost contributions | 1,218 | 1,218 |
| Hard encoding factors / auxiliaries | 794 / 118 | 794 / 118 |
| 비용 포함 solver factors | 3,308 | shared encoding3,450 |
| 비용 포함 입력 cells | 23,598,280 | shared encoding4,911,013 |
| Shared-source headers / reference variables | 표현 적용 거부 | 5,292 / 44 |
| Reduction/compaction 뒤 입력 cells | — | 1,447,984 |
| 최대 message 크기 | dense 예측15,258,196,800 | 실제 저장9,028,800 |
| 입력+모든 message cells | dense 예측34,434,299,872 | 실제 저장19,349,167 |
| Objective raw bits | optimizer 완료 실패 | 4652134937446297763 |

Factor **개수**는 늘 수 있지만 각 factor의 domain product가 작아진다. 후보 수 감소와 표현 크기 감소를 구분해야 한다. Dense 예측과 실제 저장은 같은 값을 재측정한 성능 A/B가 아니라 서로 다른 저장 기준이다.

초기 overlay 진단의3,905,024/8,347,373은 finite-entry 수를 물리 저장량으로 잘못 표기한 값이었다. Sparse→dense 전환233개를 반영해 원본 trace를 다시 집계하면 위 production 수치와 정확히 같다. 3,905,024는 가장 큰 **sparse** message의 finite-entry 수이며, 그 projected keyspace는71,318,016이다. 큰 dense 배열을 할당했다는 의미가 아니다.

## 검증 결과와 한계

| 검증 | 결과 |
|---|---|
| 첫 capacity 수정, Java12클래스 | 91/91 PASS |
| 최신 main 통합 `7f35`, Java35클래스 | 253건 중252 PASS/1 ERROR/skip0 |
| 최종 snapshot 수정 `f0f919`, Java19클래스 | 84/84 PASS/skip0, source 변경0 |
| 대형 PUBLIC L2SVM production optimizer | PASS, 기존10m/60m·3GB 유지 |
| Python E2E harness | 56/56 PASS |
| Maven package | PASS |
| snapshot 수정 전 실제 일반 L2SVM Local/Global | 2/2 PASS, canonical proof 네 항목 모두 참 |
| snapshot 수정 전 실제 true/false Local/Global | 실행 완료 후 cost fingerprint 불일치; 원인 수정 완료 |
| snapshot 수정 전 sharing Local/Global | 8/8 PASS |
| 최종 snapshot 수정 후 L2SVM Local/Global | true·false·일반 학습6/6 PASS |
| 최종 snapshot 수정 후 sharing Local/Global | invariant·updated·PHI·flat8/8 PASS |

남은 campaign 오류는 `ExactPhysicalModelCertificateTest.sevenWorkloadsBuildBaselineFreePhysicalDomainsAndFactors`의 **PUBLIC LogReg**에서 발생한423,588,286-cell separator 제한이다. 같은 LogReg fixture에 origin/main `241b9c491a`의 `ExactPhysicalModel.java`만 현재 dependencies 위에 올린 baseline overlay도436,840,500-cell raw input 제한으로 실패했다. 기존 helper도 실패함을 확인한 범위의 증거이며, 전체 clean baseline 빌드로 주장하지 않는다. 뒤 ALS/StepLM은 해당 campaign에서 도달하지 않았다. 테스트를 skip 처리하거나 한도를 완화하지 않았다.

최종 `l2svm-{local,global}-final-r3`는 true·false·`ml_l2svm`을 각각 자동 선택→runtime까지 실행했다. 모든 사례에서 objective bits, 전체 cost surface fingerprint, selected states, sharing lifetimes가 canonical 재구성과 일치한다. Fallback/repair와 audit 위반은0이다.

| 실제 DML | Local 비용 / Global 비용(ms) | Local compile / runtime(s) | Global compile / runtime(s) |
|---|---:|---:|---:|
| Federated Y, true | 8.027077571079134 / 동일 | 296.889 / 0.662 | 88.747 / 0.572 |
| Federated Y, false | 8.027077723667025 / 동일 | 275.315 / 0.329 | 90.603 / 0.328 |
| 일반 builtin 학습 | 21.385960545366007 / 동일 | 10.617 / 1.617 | 14.292 / 1.934 |

Compile 시간은 공통 분석을 포함한 전체 compilation이다. 각1회 기능 검증이며 Local/Global 컨테이너가 겹쳐 실행된 구간이 있다. 수정 전후 latency A/B 또는 일반적 속도 향상률로 해석하지 않는다.

`ml_l2svm`은192×8 PRIVATE_AGGREGATE X/3 ROW workers, local PUBLIC Y,262 decisions/1,885 alternatives다. 모델8계수 전체가 CP와 일치하며 최대 절대오차는8.413408858487514e-17이다. 대형 PUBLIC metadata fixture와 구분한다. 앞선 `r1/r2` 기록은 보존하고 최신 source의 실행으로 대체 표기하지 않았다.

최종 `sharing-final-r3`의 전송 검증은 전체 애플리케이션 I/O가 아니라 **해당 selected supply action에 귀속한** GET/PUT 횟수다. 아래 결과는 Local/Global 모두 같다.

| 공급 사례 | 사용 / logical versions | source GET / REFED creation / target PUT | 의미 |
|---|---:|---:|---|
| invariant staging | 3 / 1 | 1 / 1 / 1 | 같은 immutable value의 staging 공유 |
| updated | 9 / 3 | 3 / 3 / 3 | 각 버전 안에서3회 공유, 버전 간 공유 없음 |
| PHI | 9 / 3 | 3 / 3 / 3 | loop entry/backedge의 서로 다른 값 구분 |
| flat updated | 3 / 3 | 3 / 3 / 3 | 각 iteration의 공통 공급 생성, 반복 간 retain 없음 |

공유·이동 비용은 각각46.42780322869491,142.38353164836383,142.38353164836383,90.1380469173193ms의 **전체 모델 objective**에 포함되며 Local/Global과 canonical 값이 같다. 이 숫자 전체를 movement-only 비용으로 해석하지 않는다. Runtime creation 횟수·버전·cleanup과 비용 surface의 sharing 결정을 함께 검사했다.

잔여 범용 한계는 projection 후 keyspace의 int 범위, 선택된 order에서 실제 저장량이 한도를 넘는 경우, boxed map의 JVM heap overhead다. 이번 L2SVM 통과가 모든 workload의 고정 메모리 내 성공을 보장하지 않는다. JUnit wall time이나 공유 호스트의 단일 Docker 실행으로 성능 향상률을 주장하지 않는다.

## 재현과 증거

회귀 command, source inventory SHA, 클래스별 결과, baseline attribution 및 최종 Docker 목록은 [validation.json](experiments/l2svm-exact-capacity-20261007/validation.json)에 기록한다.

공통 evidence root:

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/l2svm-exact-capacity-20261007/
```

- `baseline-8ae75aff00/`: 게시 baseline JAR와 최초 오류.
- `sparse-first-r1/`: 첫91건 및 production 비용·저장량.
- `merged-regression-final/`: 통합253건, source inventory, Python 결과.
- `logreg-baseline-attribution/`: 독립 baseline helper 재현.
- `l2svm-{local,global}-final-r2/`: snapshot 수정 전 실제 학습 및 strict proof 실패 보존.
- `sharing-final-r2/`: 수정 전 sharing8건 성공.
- `fingerprint-diagnostics/`: runtime 없는 반복 재구성 및 snapshot RED/GREEN 근거.
- `fingerprint-regression-final/`: 최종84건·source inventory·package·Python 결과.
- `l2svm-{local,global}-final-r3/`: 최종 L2SVM6건 성공 및 frozen 입력.
- `sharing-final-r3/`: 최종 sharing8건 성공 및 runtime 전송·lifetime 증거.
- `final-runtime-summary.json`: 14건 요약과 현재 source/class/dependency 일치 확인.

재현 예:

```bash
bash scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e \
  --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/l2svm-exact-capacity-20261007 \
  --run-id l2svm-recheck --planner global --canonical-proof \
  --case l2svm_true_01 --case l2svm_false_m11 --case ml_l2svm \
  --timeout-seconds 3600 --case-timeout-seconds 900
```
