# Reuse semantic fusion 조사 및 비교

## 판단

**의미적 통합은 가능하다. 기본 구현은 기존 hard-factor/observation decomposition과 Boolean OR-chain/terminal cost를 유지하는 것이 적절하다.** 통합 relation을 dense higher-order solver factor로 만들 이유가 없다. 이번 작업은 조사·test-only executable specification·분해 실험이며 production planner/runtime은 변경하지 않았다.

현재 main의 정보만으로 동적 cache eviction까지 포함하는 완전한 residency-aware factor를 만들었다고 주장할 수는 없다. 정적 version/creation lifetime과 실제 runtime copy availability가 다른 경계에 있다. 이 둘을 추가 모델링 없이 합치면 오히려 잘못된 공유 또는 합법적인 재생성 계획의 제거로 이어진다.

마지막 OR Boolean 하나를 비용에 흡수하는 제한적 대안도 조사했다. scope는 작게 유지하지만 table cell 수가 증가하는 반례가 있고 production 속도 이득을 입증하지 못했다. **외부 의미 정의는 채택할 가치가 있으나, 이 실험의 terminal contraction은 기본 production 최적화로 채택하지 않는다.**

## 기준, 격리 및 계획

- fetch한 `origin/main`: `8f6bb285e5a27b52308ceceb63ceae2445b22782`.
- 실험 당시 worktree: `/home/mchoi/reuse-semantic-fusion-20261006` (선별 게시 후 삭제).
- branch: `investigate/reuse-semantic-fusion-20261006`.
- 기존 workspace의 source/미커밋 변경을 가져오거나 수정하지 않았다. fetch 및 worktree 등록의 Git 공용 metadata만 갱신했다.
- 순서: main 읽기 전용 조사 → 기존 회귀 → test-only semantic relation/제한적 contraction → 독립 검토 → 동일 Docker 비교.
- 전체 OR-chain flatten, 새 의존성, candidate pruning, cost 재정의, runtime 동작 변경은 없다.

## 실제 main의 factor 구조 — Evidence

아래 scope는 canonical 의미와 solver encoding을 구분한다. `S`는 source decision, `D_i`는 demand가 관찰하는 decision, `A_i`는 Boolean accumulator다.

| 역할 | canonical scope / 의미 | 실제 encoding |
|---|---|---|
| input-authority validity | consumer + direct source + 같은 source `ValueVersionKey`의 모든 decision. `inputSatisfied`는 NATIVE_LOCAL/DIRECT_FOUT/relocation receipt·privacy·source-output 검사 후 0 또는 +∞ | observation quotient가 유리할 때 원래 축↔observation의 이항 link + observation truth factor. truth factor arity 자체는 원래 scope에 의존하며 3 이하로 보장되지 않음 |
| realization support | consumer와 정확한 dependency owner, 동일 owner면 unary. 같은 배치가 아니라 선택된 candidate reference를 요구 | 1/2축 hard factor 또는 observation quotient |
| derived FOUT anchor authority | producer와 graph action이 지목한 durable anchor owner | 1/2축 hard factor |
| resolved shared creation cost | source + 각 demand의 source/consumer/layout 관찰 변수 합집합. 활성 demand의 OR에 activation multiplicity×unit price 한 번 | 복합 demand AND observation chain(2/3축), activation OR-chain 첫 link `(D_1,A_1)` 2축, 나머지 `(D_i,A_{i-1},A_i)` 3축, terminal `(S,A_n)` 2축 |
| unresolved activation union | source + 모든 demand 관찰 변수. 기존 conservative capped-union objective | demand AND/event OR-chain은 2/3축. monetary factor는 `(S,E_1,…,E_k)`: unique event 수 k에 따라 k+1축. 이 경로까지 상수 scope라고 부르면 틀림 |
| native/local input 준비 PUT | producer, consumer | 동일 source cost-row class가 있으면 `(producer,class)` + `(class,consumer)` 2축. 공유 GET collector와 별도 per-instruction PUT |
| unary output creation | 선택된 producer alternative | unary 비용. 모든 upload/derived creation이 generic shared-GET처럼 합쳐지는 것은 아님 |

근거:

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalModel.java`: `addRealizationSupportFactors`(1294 부근), `addDerivedFoutAnchorFactors`(1428), `addInputAuthorityFactors`(1463), `inputSatisfied`(1828).
- 같은 directory의 `ExactHardFactorObservationDecomposition.java:52`: quotient는 encoded cells가 줄어들 때만 선택하며, 마지막 truth factor는 observation axes 전체를 유지한다.
- `ExactPhysicalCostModel.java:1817`: `addMaterializationActivationFactors`; `:1960` observation chain; `:2391` native/local 비용; `:846–926` unary 비용.
- `ExactActivationClassFactorDecomposition.java:82`: canonical OR와 solver factors 분리. repeated consumer mask는 합치며 zero-price class는 비용 auxiliary를 생략할 수 있다. **비용이 0이어도 validity hard constraints는 생략할 수 없다.**
- `ExactPhysicalCostModel.java:481–567`: 기존 canonical contribution ordinal, exact solver factors, `FrozenDyadicCostTransport`의 one monetary table 연결을 보존한다.

`ExactPhysicalSharedSourceEncoding`은 candidate reference 중복을 owner별 source variable로 줄이는 별도 solver 표현이다. 이름에 shared-source가 있다고 runtime materialization cache의 identity/lifetime 구현으로 해석하면 안 된다.

## Source, version, layout, availability — Evidence와 한계

1. `PlacementIdentity.ValueVersionKey`(`:314`)는 program/lexical variable/defining region/definition ordinal/version kind/predecessors를 포함한다. `RelocationActionKey`(`:737`)는 source version, target placement/FType, durable anchor, statement-block scope, compatible consumers까지 포함한다. 같은 placement만으로 합칠 수 없다.
2. `ExactPhysicalCostModel.java:1317`의 creation scope와 `:1595`의 exact-layout/transparent-alias 조건은 동일 runtime MatrixObject임을 증명하는 경우에만 GET을 묶는다. relocation/derived output은 private identity를 보존한다. unit price도 grouping key에 포함된다. runtime owner별 PUT은 GET 공유와 구별한다.
3. `PlacementSupportRelations.java:185`는 current domain에서 사라진 exact source reference의 support clause를 제거한다. 이는 planner reference availability다.
4. `PlacementProgramFacts.java:245,644`의 LOOP_HEAD_PHI/LOOP_BACKEDGE/FUNCTION_INPUT version은 **정적 정의**다. 각 동적 iteration epoch를 decision variable로 열거하지 않는다. source/consumer activation profile과 creation multiplicity가 반복 비용을 표현한다.
5. `FederationUtils.java:129,1341`의 owned REFED key는 정확한 MatrixObject identity + mutation version + rows/cols/nnz + tid + layout + output FType이다. `CacheableData.java:706,721`은 modify 전 retirement 및 version 증가를 한다. `ExecutionContext.java:911,943`은 삭제 시 cache를 정리한다.
6. retirement/eviction 후 같은 owner/version/layout로 다시 요청해도 기존 copy를 쓰지 않고 새 materialization을 만든다. 현재 static planner에는 cache-residency/eviction decision axis가 없다. 따라서 **expired copy를 reuse하는 선택은 불법이지만, 비용을 지불해 새 copy를 생성하는 plan 전체는 합법일 수 있다.**

호출 횟수 자체를 version으로 사용해서는 안 된다. 같은 불변 객체를 전달한 여러 함수 호출은 공유 가능하며, 호출/반복 안에서 새로 만든 값은 creation scope 및 runtime owner/mutation identity로 분리한다.

## 제안하는 외부 semantic relation

materialization family `m`의 identity를 다음과 같이 정의한다.

```
m = (source/value-version authority,
     target physical layout + movement/emission kind,
     proven creation context/lifetime,
     exact source/anchor/receipt dependencies)
```

선택 `x`의 demand/authority 유효성을 `V_m(x)`, 기존 canonical shared-creation 비용을 `C_m(x)`라 하면:

```
φ_reuse,m(x) = C_m(x)    if V_m(x)
               +∞       otherwise
```

동적 availability를 외부 계약에 넣을 때는 `V_m(x; availability facts)`처럼 환경/실행 이력을 명시해야 한다. main에 없는 cache residency 지식을 암묵적으로 추가하지 않는다. 서로 다른 valid creation 선택은 별도 copy family로 남긴다.

resolved activation class g에 대한 기존 의미는 `C_m(x) = ordered_sum_g(unit_m(S) × multiplicity_g × OR_j demand_g,j(x))`다. 불확실한 event overlap은 현재 conservative union을 그대로 사용한다. 따라서 “one shared creation”은 모든 프로그램에서 비용 하나라는 의미가 아니라, 같은 creation lifetime/activation class 안의 중복 demand를 한 번 charge한다는 의미다.

내부 구현은 다음의 existential/min-sum representation을 유지한다.

```
φ_reuse,m(x) = min_z [ H_m(x,z) + C_m,terminal(x,z) ]
```

`H_m`은 기존 authority/support guards와 AND/OR consistency다. 이는 solver에 큰 새 factor를 넣으라는 뜻이 아니다. semantic relation은 기존 factor ID/auxiliary/monetary-contribution ID를 참조하는 묶음이며, compiler는 같은 factor를 기존 순서대로 한 번만 방출한다. 여러 relation에서 필요한 guard는 참조를 공유한다. 물리적으로 hard factor를 중복하면 Local의 위반 집계·repair 정책이 달라질 수 있다.

정확성 근거: 일관된 original assignment에는 deterministic observation/OR auxiliary extension이 존재한다. 해당 extension의 hard 비용은 0이고 terminal 비용은 원래 canonical charge다. invalid assignment는 guard가 +∞이므로 어떤 extension에서도 유한 비용을 얻지 못한다. zero-price일 때도 동일하다. 따라서 projection의 legal space와 pointwise 비용이 보존된다. factor/variable 객체와 순서까지 그대로 두면 primal graph, 기존 elimination order/width, table representation 및 Local 정책도 변하지 않는다.

주의: finite contribution들을 새 subtotal 하나로 합쳐 재배치하면 binary64 summation 순서가 달라질 수 있다. 의미 정의는 통합하되 canonical ordered contributions, compensated sum, dyadic transport의 소유권을 유지해야 한다.

## 실행한 검증과 비교의 성격

- **합성 algebra oracle**: source/version/layout/use time이 명시된 finite fixture. same version+compatible lifetime은 한 creation, 다른 source/version/layout·미도달 availability·expired copy는 +∞, zero price라도 invalid는 +∞. 작은 original assignment 전수에 대해 baseline, semantic evaluator, auxiliary min-elimination 및 bounded terminal contraction의 raw bits를 비교했다. 이 테스트가 실제 compiler의 loop/call provenance를 새로 유도한 것은 아니다.
- **실제 production B-21, PRIVATE_AGGREGATE**: original 2,304개 assignment 전수 중 312 legal, 1,992 illegal. production hard model과 test-only semantic view의 합법성·canonical bits를 비교했다. 실제 loop/function 별 production 검증은 아래 기존 회귀를 사용했다.
- **Global/Local**: 각 선택 plan을 동일 canonical surface와 Local의 `RegionalSearchProblem.evaluate`로 교차 평가하고 exact solver에 고정해 재검증했다. 같은 plan의 비용은 같지만 두 heuristic/optimization policy가 같은 plan을 선택해야 하는 것은 아니다.
- **production control 비교**: baseline과 semantic-view는 byte-identical optimizer를 실행한다. 이는 의미적 grouping을 solver graph 변경 없이 둘 수 있다는 계약/측정 잡음 확인이다. 새 production fusion adapter의 속도 개선 실험이 아니다. test-only exhaustive cell table은 oracle용이며 solver 입력이나 제안하는 production representation이 아니다.
- **contraction 실험**: 마지막 OR factor와 terminal price를 join한 뒤 마지막 Boolean만 min으로 제거한다. prefix OR-chain과 authority guards를 유지한다. 전체 chain flatten은 하지 않았다. 작은 fixture에서 equivalent이어도 임의의 더 큰 graph에 대한 induced-width 비악화 정리를 주장하지 않는다.

기존 fresh-main 회귀는 activation/compiled lifetime/function alias GET/loop·call reaching definitions/realization legal space/owned REFED cache를 포함했다. `ExactFunctionAliasGetCostTest`는 stable value 1회, loop 갱신 3회, 서로 다른 source 2회, 반복 outer creation 2회 및 새 함수 반환값의 호출별 과금을 확인한다. `ExactCompiledMaterializationScopeTest`도 실제 compiled stable/updated loop를 구분한다.

추가 runtime 회귀 두 개는 `OwnedRefedReuseTest`에 있다: unchanged owner/version/layout라도 retired copy는 재사용되지 않고, byte-budget으로 evicted된 owner가 돌아오면 fresh copy가 생성된다. 실제 distributed workload에서 request 수를 계측한 결과와는 구분한다.

## Docker 수치 — Evidence

최종 run 보관 위치: `/grid/3/cofee-lm-sweep-mchoi-20260914/reuse-study-archive-20261006/evidence/reuse-fusion/docker/20261006T183640Z-7d06d9b4`. `run_LAN_docker.sh --reuse-semantic-fusion`에서 clean compile 후 실행했다. image `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`, 4 CPUs/8 GiB, network none, Java `-Xms256m -Xmx3g`. classes/test-classes/dependencies를 복사·hash 검증 후 read-only mount했다. build 전후 source inventory 일치, HEAD/dirty diff/build command/image/hash manifest를 보존했다.

### Production B-21: 기존 encoding → semantic grouping control

| 지표 | 기존 | semantic grouping |
|---|---:|---:|
| original decision variables | 30 | 30 |
| auxiliary variables (hard 4 + cost-chain 11) | 15 | 15 |
| solver factors (hard 57 + cost bundle 131) | 188 | 188 |
| 최대 input factor scope | 3 | 3 |
| 최대 input factor cells | 24 | 24 |
| input factor cells 합계 | 585 | 585 |
| Global 최대 separator / induced width | 3 | 3 |
| Global 최대 solver factor cells | 45 | 45 |
| Global materializedFactorCells | 573 | 573 |
| Local terminal retained slots | 622 | 622 |
| legal original assignments | 312 / 2,304 | 312 / 2,304 |

cost bundle의 131개에는 OR consistency hard factor도 포함된다. 이것이 monetary contribution 131개라는 뜻은 아니다. input cells는 scope domain product의 합이며 lazy table의 실제 heap 보관량이 아니다. Global materializedFactorCells는 solver accounting 지표다. Local retained slots는 별도 untimed trace의 실제 incremental slot 지표이며, Local 결과 API의 `retainedLocalStates=49`와 혼동하지 않는다. 같은 API에 나타나는 `inducedWidth=0, materializedFactorCells=0`도 incremental 전체 그래프가 비었다는 뜻이 아니므로 비교표에는 사용하지 않았다.

3 fresh JVM forks × 2 warmups + 5 측정/arm, 순서를 교대했다. 시간은 이미 생성한 analysis/model/cost surface에 대한 optimize 호출이며, source 분석/모델 생성·semantic oracle construction/postcheck·Docker startup은 제외했다. 결과는 다음과 같다.

| planner | baseline 중앙값 ms | semantic control 중앙값 ms | 각 arm의 canonical objective (모델 ms) |
|---|---:|---:|---:|
| Global | 9.014 | 9.104 | 3.0001992958784105 |
| Local | 5.646 | 6.011 | 3.0002631407976152 |

각 arm당 15회 모두 같은 original plan 및 cost bits를 반환했다. Global은 alternative ordinal 24→3, 27→4, 28→1이고 나머지 0이다. Local은 모두 0이다. 두 비용 bits는 각각 `4613938267015495025`, `4613938410781472195`다. **각 planner 내부 baseline/view 선택은 같고, Global과 Local의 선택은 다르다.** 같은 plan을 Global canonical surface와 Local shared objective로 평가하면 raw bits가 같다. Local trace는 gap `2.1280e-5`에서 `TARGET_REACHED`다.

이 control/control 시간 차이는 동일 코드의 실행 변동이다. semantic grouping의 성능 개선 또는 회귀라고 해석하지 않는다. raw min/max 및 모든 selected assignment는 [요약 JSON](evidence/reuse-semantic-fusion-20261006.json), 각 fork의 `production-relation.json`에 있다.

### 마지막 OR Boolean만 제거한 test-only 대안

n은 consumer 수, s는 source domain, d는 request domain이다. 각 case에 n개의 이항 validity guard도 포함했다. 구조 표는 relation 자체이며, timed solve에 공통으로 넣은 unary preference factors는 이 표의 factor/cell 수에서 제외했다. semantic grouping은 원본과 모든 구조 수치가 같으므로 아래는 원본→terminal contraction이다.

| n, s, d | factors / aux | 최대 scope | separator width | 최대 input cells | input cells 합 | materialized cells |
|---|---|---|---|---|---|---|
| 1, 4, 8 | 3/1 → 2/0 | 2→2 | 2→1 | 32→32 | 56→64 | 67→69 |
| 2, 4, 8 | 5/2 → 4/1 | 3→3 | 3→2 | 32→64 | 120→144 | 151→163 |
| 8, 4, 8 | 17/8 → 16/7 | 3→3 | 3→3 | 32→64 | 504→528 | 679→687 |
| 20, 4, 8 | 41/20 → 40/19 | 3→3 | 3→3 | 32→64 | 1,272→1,296 | 1,735→1,743 |
| 20, 16, 18 | 41/20 → 40/19 | 3→3 | 3→3 | 288→576 | 7,196→7,668 | 9,027→9,435 |

n≥2에서 마지막 OR+price의 table 용량은 대략 `4d+2s`이고, contraction 결과는 `2sd`다(변수 중복이 없는 경우). Boolean 축 하나를 없애는 대신 categorical source/consumer의 곱이 생기는 이유다. guard와 동일 scope인 n=1 비용을 추가로 합치는 별도 최적화는 이번 비교에 없다.

각 synthetic arm은 3 JVM forks × 3 warmups + 7 측정, arm당 21회다. original / semantic-group / tail 중앙값(ms):

| n, s, d | original | semantic-group | tail |
|---|---:|---:|---:|
| 1, 4, 8 | 0.938 | 0.952 | 0.642 |
| 2, 4, 8 | 0.713 | 0.739 | 0.539 |
| 8, 4, 8 | 1.922 | 1.877 | 1.748 |
| 20, 4, 8 | 6.052 | 6.115 | 5.625 |
| 20, 16, 18 | 3.385 | 3.423 | 3.392 |

모든 측정 solve에서 original decision vector와 objective raw bits를 직접 비교했다(auxiliary를 포함한 assignment hash 비교가 아님). 요구가 전부 NONE인 자명한 최적해가 되지 않도록 active 공유 계획을 선호하는 비용을 사용했다. 작은 case의 시간이 줄어도 넓은 domain에서 일관된 이득은 없고 table accounting은 악화됐다. 이것은 production workload의 시간 개선 근거가 아니다. 8개 case 전체 지표와 preference 포함 목적값/선택 vector는 요약 JSON에 있다.

## 검증 상태와 재현

- 최종 clean production/test compilation 성공.
- 최종 Docker focused JUnit **29 tests, 0 failures/errors**: semantic algebra 5, production relation 3, owned REFED runtime 21(기존 19 + expiry 2).
- baseline의 별도 관련 suite: **92 tests, 91 PASS / 1 FAIL**, skipped 0. 실패는 아래 기존 assertion 하나다. 새 focused 29건이 baseline 92건에 모두 추가되는 것은 아니다(OwnedRefedReuseTest 중복).
- `bash -n`, Python syntax/AST, `git diff --check` 통과. 새 의존성 없으며 `git diff HEAD -- src/main`은 비어 있다.
- 독립 리뷰에서 synthetic plan projection, hard/cost auxiliary 합계, table-capacity 명칭, mask-aware contraction, class/source provenance를 점검·보강했다.

baseline main의 `ExactPhysicalDyadicCertificateTest.allFourConstructionCasesCarryTypedMetadataWithoutDescriptorParsing`은 unresolved union transport를 `IDENTITY`로 기대하지만 main은 `ONE_MONETARY_TABLE`로 생성하여 실패했다. production 변경 전 92건 중 91 PASS/1 FAIL이며 이 기존 assertion을 완화하거나 제거하지 않았다.


실험 당시 명령(기록): 아래 전용 runner와 probe는 main에 게시하지 않았다. 재실행하려면 archive의 실험 source를 baseline commit의 새 checkout에 복구해야 한다.

```bash
cd /home/mchoi/reuse-semantic-fusion-20261006
bash scripts/fedplanner/run_LAN_docker.sh --reuse-semantic-fusion --forks 3 --timeout-seconds 600
```

당시 runner는 `mvn -q -DskipTests -Djacoco.skip=true clean test-compile`을 먼저 실행했다. output은 clean에 지워지지 않는 `.omx/reuse-fusion/docker/<run-id>`다. baseline 회귀 명령(변경 전 class로 실행한 정확한 명령)은 `.omx/reuse-fusion/baseline-tests.log`와 보관 Surefire에 대응한다:

```bash
mvn -q -Djacoco.skip=true -Dtest-forkCount=1 -Dtest-perCoreThreadCount=false \
  -Dtest=ExactActivationClassFactorDecompositionTest,ExactActivationMaterializationCostTest,ExactMaterializationActivationTest,ExactCompiledMaterializationScopeTest,ExactFunctionAliasGetCostTest,ExactFusedFactorReuseCostTest,IndependentCompletePlacementSpaceTest,ExactPhysicalDyadicCertificateTest,OwnedRefedReuseTest,JointInputReachingDefinitionsTest,RelocationProofReuseTest,ExactHardFactorObservationDecompositionTest \
  surefire:test
```

아래 원본 evidence 링크는 저장소 밖의 로컬 archive를 가리킨다. GitHub에 게시한 축약 결과는 [요약 JSON](evidence/reuse-semantic-fusion-20261006.json)에 있다.

원본 evidence: [manifest](/grid/3/cofee-lm-sweep-mchoi-20260914/reuse-study-archive-20261006/evidence/reuse-fusion/docker/20261006T183640Z-7d06d9b4/manifest.json), [Docker 결과](/grid/3/cofee-lm-sweep-mchoi-20260914/reuse-study-archive-20261006/evidence/reuse-fusion/docker/20261006T183640Z-7d06d9b4/result.json), [29건 JUnit](/grid/3/cofee-lm-sweep-mchoi-20260914/reuse-study-archive-20261006/evidence/reuse-fusion/docker/20261006T183640Z-7d06d9b4/focused-junit.log), [factor inventory](/grid/3/cofee-lm-sweep-mchoi-20260914/reuse-study-archive-20261006/evidence/reuse-fusion/docker/20261006T183640Z-7d06d9b4/production-0/production-factor-inventory.json), [Local retained-slot trace](/grid/3/cofee-lm-sweep-mchoi-20260914/reuse-study-archive-20261006/evidence/reuse-fusion/docker/20261006T183640Z-7d06d9b4/production-diagnostic/stdout.log), [baseline Surefire](/grid/3/cofee-lm-sweep-mchoi-20260914/reuse-study-archive-20261006/evidence/reuse-fusion/baseline-surefire). 전체 source/class/dependency inventory 및 fresh build log를 보관한다. 축약 수치는 repository의 `docs/evidence/reuse-semantic-fusion-20261006.json`에도 남겼다.

## 채택 범위와 남은 위험

- 채택 권고: 외부 모델/설명에서는 `reuse/materialization relation`으로 통합하고, 내부에서는 기존 guard + observation quotient + bounded AND/OR + terminal monetary factor를 유지한다. semantic scope가 크다는 사실은 solver scope를 크게 만들어야 한다는 뜻이 아니다.
- 채택 보류: 마지막 accumulator 제거를 기본 최적화로 사용하기. 적은 factor/aux 수가 작은 table/빠른 계획을 보장하지 않는다. 초기 조사에는 production A/B가 없었고 저장량 반례가 있었다. 아래 후속 ML training A/B에서도 일관된 실행시간 이득을 확인하지 못했다.
- 더 작은 scope가 필요하면 기존 observation quotient를 유지·개선하는 방향이 우선이다. 정확히 같은 scope의 hard/cost factor만 합치는 것은 새 edge를 만들지 않지만 hard-only transport 분류·Local 위반 단위·numeric receipt 변경을 별도로 증명해야 한다. 이번에 구현하지 않았다.
- runtime cache eviction/oversized non-retention을 포함한 정확한 비용 예측, 동적 residency-aware 계획, distributed end-to-end 생성 요청 계측은 이번 scope 밖이다. 이 정보를 추가하는 일은 단순 semantic refactoring이 아니라 모델 확장이다.

## 후속 실제 ML training 비교

사용자 요청에 따라 같은 main commit에서 실제 OR-tail contraction 후보를 별도 worktree에 만들고, 대형 모델을 제외한 LM/KMeans W1/W3을 측정했다. 원래50K×128 데이터와 학습 설정으로6쌍씩48실행을 검증했다. 출력·초기 배치·canonical cost는 같고 retained slots는 줄었지만, 학습 실행시간의 일관된 개선은 없었다. 따라서 앞선 semantic fusion 가능/기존 small-scope decomposition 유지 권고를 바꾸지 않는다. 상세 시간·실험 조건·한계는 [실제 training 보고서](REUSE_TRAINING_RUNTIME_2026-10-06_KO.md), raw-derived summary는 `docs/evidence/reuse-training-20261006.json`을 참조한다.

## 선별 게시 및 보관

조사 보고서·실행시간 보고서·요약 JSON 2개와 `OwnedRefedReuseTest`의 retirement/eviction 재생성 회귀 테스트 2개만 main에 반영한다. OR-tail production 후보, 실험용 구조 테스트/probe/harness/Docker dispatch는 반영하지 않는다. 본문의 측정·코드 참조는 실험 baseline `8f6bb285e5` 기준이다.

원시 증거와 실험 source patch는 `/grid/3/cofee-lm-sweep-mchoi-20260914/reuse-study-archive-20261006`에 해시 검증 후 보관했다. `evidence/`는 원시 run, `source/primary`와 `source/candidate`는 원본 패치·추가 파일, `archive-manifest.json`은 archive 파일 inventory다. 당시 worktree와 이를 대신해 만든 게시용 임시 worktree는 push 확인 후 삭제한다. 원본 manifest 안의 경로는 실험 당시 provenance로 보존했으며 JSON 최상위의 현재 증거 경로는 archive로 갱신했다. 실험 전용 runner는 main에서 직접 호출할 수 없다. 복구 절차와 Docker 경로 제약은 archive의 `README.md`를 참조한다.

게시 전 검증: 최신 `origin/main` `0146f043e0` 위에서 `mvn -q -DskipTests=false -Djacoco.skip=true -Dtest-forkCount=1 -Dtest-perCoreThreadCount=false -Dtest=OwnedRefedReuseTest test`를 실행했고 **21 tests, 0 failures/errors/skips**를 확인했다. 실험 당시 baseline 검증과 구분하며 main production 코드 변경은 없다.
