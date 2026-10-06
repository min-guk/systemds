# Shared reduction과 hard-conflict repair의 domain 정렬

## 결론과 범위

repair의 원래 변수 공간에서 후보를 열거할 때도 **경계를 고정하지 않은 shared root에서 지원되는 원래 값**만 사용한다. 경계를 고정한 뒤 생기는 추가 축소는 해당 repair 문제 안에서만 사용하고, 경계 값이 바뀌거나 repair 영역에 포함되면 root에서 다시 준비한다. 경계에 root가 지원하지 않는 값이 있으면 관련 변수들을 함께 repair 영역에 넣는다.

초기 greedy 선택은 기존 전체 domain을 유지한다. 초기 선택까지 축소한 실험에서는 StepLM의 anytime 결과가 나빠졌으므로 그 부분은 채택하지 않았다. 재사용 범위를 실제 repair/block 탐색으로 한정했다.

이번 변경은 `LocalCategoricalOptimizer`와 `SharedRegionalPreparation`에 한정된다. 기존 unary/binary support 축소 결과를 공유하며, 삭제한 heuristic dominance/global/n-ary pruning을 추가하지 않는다. 앞선 StepLM 수정과 비용 모델 변경은 이 실험의 baseline에 포함되어 있다.

## 어떤 정보를 재사용하는가

| 정보 | repair에서의 사용 | 유효한 전제 |
| --- | --- | --- |
| shared root의 unary/binary support 제거 | 직접 열거, factorwise 검사, original-factor 재시도에서 재사용 | 동일한 immutable 문제의 변수·factor·고정 정책 |
| shared root의 exact quotient와 원래 값 mapping | shared factor conditioning과 결과 projection에 재사용 | 해당 root의 factor 전체에서 동등성이 유지됨 |
| root quotient의 대표값 목록 | 원래 domain을 이 목록으로 대체하지 않음 | 같은 quotient에 속하는 원래 값도 모두 지원값임 |
| 특정 boundary에 맞춘 factor table | boundary 전체가 같을 때만 cache 재사용 | reduced 값 좌표와 free 표시 `-1`까지 같음 |
| boundary slice에서 얻은 추가 support/quotient | 그 prepared solver에만 보관 | boundary 변경/확대 시 root에서 재계산 |

`RegionalSearchProblem.reducedRoot(limits)`는 현재 assignment나 repair block을 입력받지 않는다. 따라서 이 root의 불가능성 판정은 임시 repair 경계에 의존하지 않는다. production에서는 forced assignment 없이 문제를 생성한다. 별도 audit에서 forced constraint를 포함했다면 그것도 해당 문제의 고정 전제이며, 이를 바꿀 때는 문제/root도 새로 만들어야 한다.

root의 지원값은 모든 전역 불가능성을 찾아낸 집합이라는 뜻은 아니다. 기존 reduction이 **이미 불가능함을 증명한 값**을 제외한 집합이다. finite cost가 큰 합법 후보는 이 인터페이스로 제거하지 않는다.

## 경계가 풀리면 값이 돌아오는 예

원래 `x`의 값이 `{0,1,2,3}`이고 고정된 모델 제약 때문에 `{1,3}`만 가능하다고 하자. `y=0`에서는 `x=1`, `y=1`에서는 `x=3`만 가능하다.

```text
immutable model              x = {1,3}, y = {0,1}
       |
       +-- repair x, boundary y=0  --> x = {1}
       |
       +-- repair x, boundary y=1  --> x = {3}
       |
       +-- repair {x,y}            --> x = {1,3}, y = {0,1}
```

`x=0,2`는 어떤 repair에서도 다시 열거할 필요가 없다. 반면 첫 slice에서 제외한 `x=3`은 `y`의 값이나 자유도가 바뀌면 돌아와야 한다. 구현은 첫 slice의 축소 domain을 두 번째/세 번째 문제의 입력으로 사용하지 않는다.

## 구현

`BlockPreparation.unconditionalDomains(originalVariables)`는 원래 값 번호로 지원 domain을 제공한다. assignment/block 인자를 받지 않으며, shared reduction이 없는 일반 호출은 `null`로 기존 전체 domain을 사용한다. `SharedRegionalPreparation`은 `root.reducedValue(i, value) >= 0`인 **모든** 원래 값을 반환한다. quotient 대표값으로만 좁히지 않는다.

Local context는 이 값을 `domainValues`로 보관한다. 직접 block 열거, factorwise-minimum 검사, block의 state-key 중복 검사와 탐색 순서가 동일한 domain을 참조한다. 진짜 resource limit 때문에 original-factor 경로를 다시 준비하는 경우에도 이미 증명된 지원값은 유지한다. 그 경로의 factor 좌표와 결과 좌표는 `domainValues`로 원래 값에 대응시킨다. shared solver의 결과는 이미 원래 값이므로 다시 변환하지 않는다.

초기 `selectLocalState`의 domain 및 state-key 대표 선택은 기존 그대로다. 이 단계는 아직 완성되지 않은 assignment에서 임시 선택을 만들며, unsupported 값을 고르면 이어지는 hard repair가 수정한다. 이미 지원하지 않는다고 알려진 값을 repair 내부에서 다시 열거하는 것은 허용하지 않는다.

shared preparation은 block과 연결된 auxiliary factor를 닫은 뒤, 선택된 factor의 모든 고정 경계를 table 생성 전에 검사한다. unsupported 원래 변수 번호들을 한 번에 반환하고, hard repair가 이 변수들을 free block에 추가한 뒤 incident hard/cost와 auxiliary closure를 다시 준비한다. unsupported 경계 자체는 canonical factor 재시도 사유가 아니다.

conditional table cache는 기존의 root-factor별 한 항목 정책을 유지한다. key에는 scope 전체의 고정된 reduced 값과 free 축의 `-1`이 포함된다. fixed→free도 key 변경이므로 해당 table은 다시 만들어진다. compact 경로의 추가 reduction은 반환된 solver에만 속한다. 별도 provenance graph나 conditional-domain 전역 cache를 추가하지 않았다.

## 정확성 근거

전체 모델의 feasible assignment 집합을 `F`, root가 남긴 원래 값 집합을 `U_i`라고 하자. 기존 support 축소의 계약에 의해 모든 `a ∈ F`에 대해 `a_i ∈ U_i`다. 따라서 repair가 `U_i`만 열거해도 전체 feasible plan이나 그 최적값은 잃지 않는다. 경계를 바꾸거나 자유롭게 해도 모델 자체가 같으면 이 포함 관계는 유지된다.

반면 boundary `b`에서 얻은 조건부 집합 `U_i(b)`에는 이런 전역 보장이 없다. 확장 시 `U_i(b)`를 버리고 root의 `U_i`에서 시작하는 이유다. 실제 repair는 전체 문제의 일부를 푸는 단계이므로, 기존에는 가능해 보였던 임시 local assignment 중 전역적으로 불가능한 것은 더 이상 선택하지 않을 수 있다.

factorized shared representation과 auxiliary closure를 유지하고 원래 값으로 projection하므로, reduction은 문제의 표현을 줄인다. 작은 모델의 정확해는 전수 열거와 비교한다. 큰 workload의 anytime 종료에서는 seed와 탐색 경로가 달라질 수 있으므로, 후보를 안전하게 줄였다는 사실만으로 같은 시간 예산의 incumbent 품질이나 latency 향상을 보장하지 않는다.

## 회귀 검증

- `sharedSupportRestrictsRepairWithoutChangingSeedOrDroppingEquivalentValues`: 초기 후보 열거 9개를 유지하면서 지원하지 않는 값의 비용 평가를 초기 선택의 2회로 제한한다. quotient alias `{1,3}`를 유지하고, 실제 block assignment 수가 plain 5→2, compact 2→0으로 줄어든다. 원래 assignment와 전수 최적값은 동일하다.
- `originalFactorRetryKeepsSharedSupportAndProjectsOriginalValues`: shared block 준비를 사용할 수 없는 경로에서도 noncontiguous 원래 값과 최적값 복원.
- `SharedReductionRepairTest` 6건: boundary 값 변경, fixed→free, 조건부 infeasible slice의 확장, 이미 준비한 solver의 불변성, auxiliary closure와 noncontiguous projection, unsupported 경계 일괄 검출, seeded 작은 모델 전수 대조.
- 기존 unsupported-boundary hard repair 회귀, compact/plain parity 및 StepLM overflow 통합 회귀 유지.

최종 구현의 Java 17 Maven `package -DskipTests -Djacoco.skip=true`가 성공했고, 86개 클래스 **645건이 모두 통과**했다. seed 선택을 보존하도록 범위를 좁힌 뒤 전체 회귀를 다시 실행한 결과다. focused 43건도 통과했다. root 지원값의 소비만 끈 별도 진단 class에서는 phase 회귀가 예상대로 실패했다(unsupported 비용 평가 2회 기대, 3회 관측). 독립 read-only 검토도 두 구현의 정확성과 anytime 경로 차이를 별도로 확인했다.

## 측정 설계와 한계

수정 직전의 compiled classes와 source를 별도 artifact에 동결하고, 동일한 절대 DML/config 경로·내용·Docker image·입력 조건으로 비교한다. 표준 `scripts/fedplanner/run_LAN_docker.sh --function-boundary-compare`를 사용한다. 대상은 StepLM, GLM, logreg, PCA의 worker 1/3 조건이다.

관측 대상은 원래/지원 domain 크기, 초기 hard conflict, repair block 및 확장 수, conditioned table 수, solver assignment/factor-cell 작업량이다. semantic fingerprint와 목적함수, 종료 단계/gap도 함께 기록한다. 이 실험은 compiler/lowering 검증이며 training runtime을 실행하지 않는다. timing 필드는 진단용이고, latency 개선을 주장하는 반복 성능 실험이 아니다.

shared root 준비가 seed 생성 앞으로 이동했다. 총 solver 경로가 필요로 하는 root는 동일하지만 첫 incumbent까지의 시간 분포가 바뀔 수 있다. root 준비가 resource limit으로 실패할 때 기존 전체 domain 경로를 유지하며, immutable 실패를 반복 호출에서 memoize하는 추가 변경은 이번에 도입하지 않았다.

## 초기 선택 축소 실험 — 최종 변경에서 제외

첫 A/B 비교는 수정 전 baseline과 seed까지 지원 domain으로 제한한 구현이었다. 8개 조건 모두 compile/lowering에 성공했고 초기 후보 수 합계는 757,506→748,963으로 1.128% 줄었다. 7개 조건은 동일한 선택과 비용을 유지했다. 그러나 StepLM W3에서 아래 trade-off가 있었다.

| StepLM W3 지표 | baseline | 초기 선택도 축소한 실험 |
| --- | ---: | ---: |
| 초기 후보 수 | 868 | 822 |
| 초기 hard conflict | 1 | 0 |
| hard repair의 solver assignment 수 | 2,188 | 0 |
| incremental assignment 수 | 18,318 | 24,544 |
| 최종 upper, 모델 비용 ms | 76,349,276.55 | 77,527,467.27 |
| relative gap | 0.0004813 | 0.0159203 |
| 종료 단계 | TARGET_REACHED | TARGET_REACHED |

합법 후보나 전수 최적해를 제거한 것은 아니지만, 기존 conflict repair가 함께 수행하던 비용 최적화 기회가 사라졌다. 그 결과 설정된 gap에서 종료하는 anytime 선택 비용이 1.543% 증가하고 이후 assignment 수도 33.99% 늘었다. 이를 성능 개선으로 채택하지 않았다. 초기 선택의 두 줄을 원래 정책으로 되돌리고, 실제 repair에서 지원값을 사용하는 변경은 유지했다. 추가 모드나 보상용 heuristic block을 도입하지 않았다.

이 실험의 source/classes/build와 전체 측정은 `seed-reduced-rejected/`에 보존한다. 최종 구현은 같은 baseline A를 재사용하여 B 8개 조건을 다시 실행했다. `Exact-PreSolveWork` 첫 기록은 seed/repair 유무에 따라 caller가 달라질 수 있으므로 caller가 같은 경우에만 대응 비교한다. `rootBuilds`가 bootstrap trace에서 0→1인 것도 준비 시점 이동과 전체 root 재계산 증가를 구분해야 한다.

## 최종 workload 비교 결과

최종 B 8건이 모두 compile/lowering에 성공했다. 동결한 A 8건과 비교해 **8/8에서 objective certificate, 선택 상태, 종료 단계, upper/lower/gap, 초기 후보 수, incremental assignment/retained-slot 수가 동일**하다. StepLM W3도 기존 값으로 복원되었다. 이들 비교는 같은 fixture/image/source 경로를 사용하며, A를 불필요하게 다시 실행하지 않았다.

| 실제 repair가 있는 조건 | baseline block assignments | 최종 block assignments | incremental assignments, 양쪽 동일 |
| --- | ---: | ---: | ---: |
| StepLM W3 | 2,188 | 2,188 | 18,318 |
| logreg W3 | 855,051,271,670 | 855,051,271,670 | 8,794,064 |

`block assignments`는 solver가 보고하는 elimination assignment 통계이며 CPU 명령 수나 실제 wall-time 연산 횟수로 해석하지 않는다. 위 두 조건의 비교 가능한 `Exact-PreSolveWork` 변수/factor/cell 수도 동일하다. 나머지 6개 조건은 양쪽 모두 bootstrap hard repair가 0회다. 따라서 **이번 대표 workload에서는 추가 작업량 감소가 관측되지 않았다**. 작은 회귀에서 관측한 block assignments 감소(plain 5→2, compact 2→0)와 구분한다.

최종 B가 제공하는 지원 domain은 원래 domain 합계 757,506개 중 748,963개다. 차이 8,543개는 기존 root가 제거한 값을 repair 경로에 전달한 수량이다. 초기 선택은 양쪽 모두 757,506개를 열거하며, 기존 shared multi-variable repair는 이미 reduced root를 사용했으므로 이 실험에서 solver 작업량이 더 줄지 않은 것과 일치한다.

StepLM W1·logreg W3·PCA W1은 `RESOURCE`, GLM W1/W3·logreg W1은 `RESOURCE_INITIAL`, StepLM W3·PCA W3은 `TARGET_REACHED`로 양쪽 모두 같다. 이 workload 결과는 전역 최적해 인증으로 해석하지 않는다. 정확한 최적값 보존은 위의 전수 열거 회귀 및 immutable-root 재사용 조건으로 별도 검증했다.

`LocalPhysicalOptimizer`는 bootstrap 이후에도 반드시 `problem.reducedRoot(limits)`를 얻어 incremental solver에 전달하며, 그 호출은 같은 root를 캐시에서 반환한다. 따라서 bootstrap trace의 `rootBuilds=0→1`만으로 전체 root 구성 횟수가 증가했다고 결론 내릴 수 없다. 준비 시점을 미루기 위한 추가 Context 상태/모드는 도입하지 않았다. 학습 runtime과 반복 latency 실험을 하지 않았으므로 **latency 개선 주장은 없다**.

최종 [검증 기록](experiments/shared-repair-reuse-20261006/validation.json), [workload 비교표](experiments/shared-repair-reuse-20261006/workload-brief.json), [전체 비교](experiments/shared-repair-reuse-20261006/workload-comparison.json), [제외한 seed 축소 실험](experiments/shared-repair-reuse-20261006/rejected-seed-filter-brief.json)을 보관한다. 원본 로그·명령·동결 classes·fixture manifest는 아래 artifact root에 있다.

원본 artifact: `/home/mchoi/cost-followup-20261006/shared-repair-reuse/`.

## 최신 main 통합 검증

위의 645건과 A/B 비교는 작업 커밋 `33dfbdc4c8`까지의 증거다. 공개 전에 `0146f043e0`의 joint-input, resource-policy, dynamic native authority 변경을 병합해 별도로 검증했다. main의 선행 repair 확장과 이번 immutable domain 재사용/typed boundary 확장을 함께 유지하고, support table 할당에 기존 resource guard를 적용했다. 자동 병합으로 겹친 보조 network 비용은 full mixed stage에서 한 번만 소유하도록 정렬했다.

최초 통합 733건 중 6건이 실패했다. CTABLE의 factor와 transfer key가 일대일이라는 오래된 테스트 가정은 정확한 총 GET 비용 검사로 정정했다. 나머지 5개 메서드는 현재 main에서도 실패한다. StepLM closure, ALS/StepLM canonical factor overflow, LogReg privacy placement 오류 4개는 같은 signature를 확인했다. ALS FedAll은 입력 경로를 고정한 대조에서 양쪽 실패 로그가 byte 단위로 같았다. 임시 경로가 다른 최초 실행의 greedy conflict 문구까지 동일하다고 주장하지 않는다.

5개 기존 실패를 명시적으로 분리한 첫 재검증은 L2SVM 테스트가 전역 receipt를 초기화하지 않는 순서 의존성도 드러냈다. 시작/종료의 test reset만 추가하고 원래 배치·비용·emission 검사를 유지했다. 최종 Maven package와 **나머지 728건이 모두 통과**했다. 고정 fixture/image의 Docker StepLM W3·PCA W3 compile/lowering도 2/2 통과했다. 전체 733건 무실패 또는 학습 runtime/latency 향상으로 해석하지 않는다.

통합 증거와 명시적 제외 목록은 [publication 검증 기록](experiments/shared-repair-reuse-20261006/main-publication.json)에 있다. 원본 로그와 baseline overlay는 `/home/mchoi/cost-followup-20261006/main-publication/`에 보존한다.

푸시 직전 `db1064c3c9`까지 추가된 main 변경도 병합했다. 이 후속 변경에는 production 수정이 없고 문서와 캐시/정확 비용 인증 테스트만 있다. 최종 두 테스트 클래스를 별도 컴파일해 26/26 통과했으며, 나머지 source는 위 728건을 통과한 source와 동일하다.
