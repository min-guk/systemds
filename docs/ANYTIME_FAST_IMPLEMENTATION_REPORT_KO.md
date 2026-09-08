# AnytimeTarget 3%·5% 인증 지연 개선 구현 보고서

작성일: 2026-09-08  
구현 커밋: `a2a210d4bf0f3e6231b2e6d8be1e5067265d43e7`  
대상: native so007 planning-only의 `AnytimeTarget` 3%·5% 인증 지연  
비교 기준: 변경하지 않은 기존 `Global`

## 1. 결론과 현재 상태

이번 구현은 `AnytimeTarget`의 exact preparation에서 AC와 exact quotient를 거친 뒤 domain이 하나로 확정된 reduced variable을 factor에 대입하고, 남은 non-singleton variable만 exact solver에 compile한다. 구현 진입점은 `ExactPhysicalReducedSolver.prepareCompacted(...)`이며 다음 계약을 지킨다.

- raw factor/domain/cell cap 검사와 lazy factor freeze를 기존 순서대로 먼저 수행한다.
- 기존 arc consistency와 quotient 결과만 사용한다. incumbent나 경험적 규칙으로 auxiliary 값을 고정하지 않는다.
- factor 순서와 각 factor의 scope 순서, arity 0으로 줄어든 constant factor를 모두 보존한다.
- compact assignment를 original decision과 auxiliary 전체 assignment로 복원한 뒤 기존 canonical evaluator로 다시 검증한다.
- preflight가 허용한 `Prepared` 객체를 solve에서 한 번 소비하므로 같은 conditional model을 다시 prepare하지 않는다.
- `ANYTIME_TARGET`이면서 `targetCompactPreparation=true`일 때만 compact 경로를 사용한다. 기본값은 `true`다.
- `Global`은 기존 `ExactPhysicalReducedSolver.solve(...)`와 noncompact preparation 경로를 그대로 사용한다.

로컬 환경과 native so007 환경에서 동일한 19개 suite, 158개 test가 각각 통과했다. native 검증은 추적 대상 source 7,502개를 확인했고 두 환경에서 생성한 JAR의 SHA-256은 모두 `222d47cd4a8ddd56746f4c75efb86f195455e4b5f2372fea1dbfd3732f5b0869`였다. 독립 구현 검토는 `CLEAR` 판정을 내렸다.

완료된 1회 반복 진단에서는 `before-fast-v1`과 `compact-v1`의 AnytimeTarget이 각각 16개 조건 중 14개에서 목표를 달성했다. `whole1m-v1`은 16개 조건 모두에서 목표를 달성했다. 이 결과는 구현과 설정을 좁혀 가는 진단 근거이며 최종 반복 성능 결론이 아니다. 최종 성능은 별도 `ANYTIME_FAST_RESULTS_KO.md`에서 다룬다.

## 2. 목표와 고정 범위

사용자 목표는 3%와 5% certificate까지의 시간을 줄여 `AnytimeTarget`이 변경하지 않은 `Global`보다 빠른 대표 조건을 확보하는 것이다. 다음 범위는 변경하지 않았다.

| 항목 | 고정 계약 |
| --- | --- |
| 실행 대상 | native so007, planning-only |
| 비교 방법 | `Global`, `AnytimeTarget` |
| threshold | 5%, 3% |
| cost model | 같은 workload/profile 안에서 동일한 finite encoded cost model |
| 데이터·workload | 기존 입력과 workload 파일 그대로 사용 |
| worker | 1 worker, 기존 worker 구성 그대로 사용 |
| legality·privacy | 기존 planner/runtime legality, privacy, canonical 검증 유지 |
| 런타임 실행 | 실제 workload와 transport를 실행하지 않음 |
| 네트워크 | bandwidth와 RTT가 cost model에 주는 영향만 변경 |
| 실행 방식 | 사용자 지시에 따라 native JVM 사용; Docker 규칙은 이번 작업에서 명시적으로 override |

최종 확인 프로토콜에서 사용하는 네 가지 modeled network 설정은 다음과 같다. 대역폭 값은 방향별 Mbit/s이며, cost environment의 bandwidth 값은 MB/s다.

| Profile | Coordinator→worker | Worker→coordinator | RTT | Cost-model latency |
| --- | ---: | ---: | ---: | ---: |
| `lan` | 5,000 Mbit/s | 5,000 Mbit/s | 1 ms | 0.001 s |
| `wan_light` | 2,500 Mbit/s | 1,000 Mbit/s | 10 ms | 0.010 s |
| `wan_mid` | 250 Mbit/s | 200 Mbit/s | 100 ms | 0.100 s |
| `wan_heavy` | 100 Mbit/s | 100 Mbit/s | 200 ms | 0.200 s |

이 설정은 network cost를 모델링할 뿐 traffic shaping, 실제 분산 workload 실행, worker 교체를 뜻하지 않는다.

## 3. 문제 정의

기존 exact reduction은 raw input을 freeze하고 AC와 quotient를 수행한 뒤에도 reduced domain 크기가 1인 variable을 categorical compiler의 variable 목록에 남겼다. 이 variable은 이미 정확하게 값이 결정됐지만 네 개의 greedy elimination-order 후보 계산과 이후 factor compilation에 계속 참여했다.

초기 GLM 진단에서 WAN-light/mid/heavy의 Target 진단·preparation은 약 11.52/10.12/13.57초였고, bound·MBE는 약 1.45초, regional 단계는 약 0.72/0.73/1.68초였다. Global은 같은 encoded model의 exact solve를 약 9~11초에 마쳤다. 이는 preparation을 첫 최적화 대상으로 정할 근거였지만 sampling profiler로 함수별 CPU 비율을 확정한 결과는 아니다.

GLM/WAN-mid compact 진단에서 root compact problem의 예상 exact work는 688,683 assignments였다. 기존 root admission gate 100,000은 이를 거절했고, 이후 13개 region이 총 약 7.4 million work를 소비했다. 이미 준비된 root 문제를 exact하게 한 번 푸는 편이 더 작았으므로 새 알고리즘을 추가하기 전에 root gate만 1,000,000으로 높이는 설정 실험을 수행했다.

## 4. 구현 구조

### 4.1 처리 순서

compact preparation은 기존 reduction의 앞부분을 바꾸지 않고 그 뒤에 한 단계를 추가한다.

```text
original conditional model
  → raw scope/domain/factor-cell cap 검증
  → lazy factor freeze
  → arc consistency
  → exact quotient와 representative 생성
  → singleton reduced variable 대입
  → non-singleton reduced variable만 compile
  → exact solve
  → reduced/compact assignment를 original assignment로 복원
  → physical canonical feasibility·objective raw-bit 검증
```

`prepareCompacted(...)`도 기존 `prepare(...)`와 같은 `reduce(...)`를 호출한다. 따라서 compaction 전에 적용되는 input limit, infeasibility, AC와 quotient 의미는 동일하다.

### 4.2 singleton 대입

`compact(...)`는 reduced variable을 기존 index 순서대로 순회한다.

- domain 크기가 2 이상이면 compact variable 목록에 같은 순서로 추가한다.
- domain 크기가 1이면 compile 목록에서 제외하고 `reducedToCompiled[variable] = -1`로 표시한다.
- non-singleton에는 compact assignment의 index를 기록한다.

각 factor도 기존 factor list 순서대로 정확히 한 번 처리한다. scope에서는 non-singleton만 기존 상대 순서대로 남긴다. compact tuple을 평가할 때 원 factor scope를 다시 만들고 singleton 위치에는 reduced value `0`을 넣는다. 이렇게 얻은 cell 값을 dense compact factor에 기록한다.

factor가 모두 singleton만 참조하면 scope가 비어 arity 0 constant가 된다. 이 factor를 삭제하거나 다른 constant와 합치지 않고 같은 factor 위치에 남긴다. 이는 exact solver의 factor 순서별 정밀 합산 순서를 유지하는 데 필요하다. 모든 variable이 singleton인 모델도 빈 assignment의 compiled problem과 모든 constant factor를 통해 정상적으로 풀린다.

### 4.3 quotient와 auxiliary 계약

original free decision은 기존 exact observation quotient를 사용한다. 같은 observation과 tie 계약을 만족하는 원래 값들을 class로 묶고 각 class의 첫 값을 representative로 저장한다. auxiliary는 active original value마다 singleton class를 만든다. 따라서 compaction이 새 equivalence를 만들거나 서로 다른 auxiliary 값을 합치지 않는다.

compaction이 대입하는 값은 reduction 결과의 domain이 정확히 하나인 variable의 reduced index `0`이다. hard feasibility와 기존 reduction이 확정하지 않은 값을 incumbent에 맞춰 제거하는 경로는 없다.

### 4.4 assignment 복원

`Prepared`는 다음 정보를 보관한다.

- reduction 이전 conditional model의 전체 variable 수
- 각 reduced value에서 original value로 가는 `representatives`
- 각 reduced variable에서 compiled variable로 가는 `reducedToCompiled`
- compact `CompiledProblem`
- compiled problem의 exact statistics

solve 결과를 확장할 때 singleton은 reduced value `0`을 사용하고, non-singleton은 `reducedToCompiled`이 가리키는 compact assignment 값을 사용한다. 그 값을 다시 `representatives[variable][reducedValue]`에 통과시켜 original decision과 auxiliary 값을 모두 복원한다.

`RegionalSearchProblem`은 복원된 배열의 original free decision prefix를 원 decision 위치에 삽입한다. 이어서 기존 evaluator로 hard feasibility와 physical canonical objective를 다시 계산한다. solver objective와 canonical objective의 `double` raw bits가 다르면 `REGIONAL_SEARCH_CANONICAL_MISMATCH`로 실패한다. 따라서 mapping 오류가 잘못된 incumbent나 certificate로 조용히 게시되지 않는다.

### 4.5 preflight와 solve의 일회 재사용

whole/region preflight는 compact `Prepared`의 `eliminationAssignments`를 읽어 admission gate와 비교한다. admission에 성공하면 다음 key와 함께 `pendingPreparation`에 저장한다.

- whole: fixed assignment와 solver limits
- region: fixed assignment, 정렬된 region, reference assignment와 solver limits

뒤따르는 `solveWhole(...)` 또는 `solveRegion(...)`은 key가 같을 때 이 객체를 꺼내고 pending slot을 즉시 비운다. solve는 이미 compile된 factor를 사용하므로 raw lazy factor를 다시 평가하거나 reduction과 compaction을 반복하지 않는다. key가 다르거나 pending 객체가 없으면 기존처럼 새로 prepare한다.

GLM/WAN-mid의 `whole1m-v1`에서는 이미 준비된 root work 688,683을 gate가 허용했다. 같은 `Prepared`를 한 번 소비해 약 0.33초 preparation과 약 0.18초 solve로 exact gap 0을 만들었고 regional pass는 실행하지 않았다. 이 수치는 단일 진단 관측이다.

### 4.6 적용 범위와 Global 격리

설정 키는 다음과 같다.

```text
sysds.fedplanner.regional.targetCompactPreparation
```

허용 값은 대소문자를 무시한 `true` 또는 `false`이며 기본값은 `true`다. 다른 문자열은 `REGIONAL_SEARCH_BOOLEAN_INVALID`로 거절한다.

`RegionalSearchOptimizer.State`는 `algorithm == ANYTIME_TARGET && targetCompactPreparation`일 때만 compact mode의 `RegionalSearchProblem`을 만든다. 새 instance는 기존 variable/factor identity, factor order와 evaluator를 재사용하되 pending prepared state는 공유하지 않는다. 다른 regional algorithm은 flag와 관계없이 기존 noncompact 경로를 사용한다.

`Global`의 `ExactPhysicalOptimizer`는 계속 기존 `ExactPhysicalReducedSolver.solve(...)` overload를 호출한다. 이 overload는 `prepareCompacted(...)`가 아니라 기존 `prepare(...)`로 이어진다. 따라서 이 커밋은 Global의 reduction, exact objective, resource statistics와 solver path를 변경하지 않았다.

## 5. correctness와 수치 계약

### 5.1 certificate invariant

최소화 문제에서 게시 가능한 상태는 다음을 만족해야 한다.

```text
0 ≤ L ≤ C* ≤ U < +∞
```

- `L`은 admissible MBE 또는 exact 결과에서 오며 `max(currentL, candidateL)`로만 증가한다.
- `U`는 original physical canonical evaluator가 검증한 feasible assignment의 cost다.
- 새 incumbent는 canonical cost가 현재 `U`보다 엄격히 작을 때만 채택한다.
- exact root closure는 원래 feasible space의 optimum을 풀고 `L = U = C*`를 만든다.

목표 판정은 absolute gap 또는 relative gap 중 하나가 tolerance 이하일 때 성공한다. 이 작업은 absolute tolerance 0과 relative tolerance 0.03/0.05를 사용한다. 구현의 상대 gap은 `L > 0`일 때 보수적으로 올림한 `(U-L)/L`이며, `L == 0 < U`이면 무한대다. 차이와 나눗셈 결과에 `Math.nextUp`을 사용하므로 부동소수점 반올림이 certificate를 낙관적으로 만들지 않는다.

### 5.2 model과 feasible-space 불변성

compact 단계는 AC와 exact quotient가 끝난 모델만 변환한다. singleton의 유일한 reduced value를 대입하는 것은 원래 feasible assignment를 제거하지 않는다. non-singleton domain, factor 값과 factor ordering은 그대로 대응한다.

완료된 pilot 비교의 16개 조건에서 initial seed cost, encoded assignment, initial raw MBE와 elimination order가 before/compact/whole1m 사이에 일치했다. 각 완료 pair는 동일 encoded model/cost와 독립 Global oracle을 검증했다. 이는 모델과 시작점이 바뀌어 생긴 성능 차이를 배제하는 증거다.

### 5.3 부동소수점 순서

factor 목록과 각 factor scope의 순서를 유지하고 constant factor도 삭제·통합하지 않는다. exact solver의 active factor 순서별 double-double accumulation 계약이 유지된다. 최종 objective는 canonical evaluator와 raw-bit 단위로 비교한다.

이 계약 때문에 수학적으로 같은 값을 허용 오차로 비교해 통과시키지 않는다. assignment 복원, factor ordering 또는 evaluation 경로가 달라져 bit가 바뀌면 실행은 실패한다.

### 5.4 exact stop과 threshold stop

두 종료를 구분해야 한다.

| 종료 | 조건 | 의미 |
| --- | --- | --- |
| `TARGET_REACHED` | absolute 또는 relative gap이 설정 threshold 이하 | 최적임을 증명한 것은 아니며 지정 품질의 certificate를 얻음 |
| `GLOBAL_EXACT` | root/전체 exact solve가 완료되거나 `U=0`인 비음수 모델 | `L=U`이며 gap 0인 exact closure |

`whole1m-v1`의 GLM/WAN-mid는 3% 또는 5%에서 멈춘 근사 개선이 아니라 root exact closure로 gap 0을 만들었다. 반면 initial MBE만으로 이미 threshold를 만족하는 조건은 root preflight에 들어가기 전에 `TARGET_REACHED`로 끝날 수 있다.

## 6. 설정 방법

구현을 활성화하는 최소 JVM property는 다음과 같다. `mode=anytime`과 `algorithm=anytime-target`이 선행 조건이다.

```text
-Dsysds.fedplanner.regional.mode=anytime
-Dsysds.fedplanner.regional.algorithm=anytime-target
-Dsysds.fedplanner.regional.targetCompactPreparation=true
-Dsysds.fedplanner.regional.relativeGap=0.03
```

5% 실행은 `relativeGap=0.05`를 사용한다. 아래 표의 나머지 예산과 cell 설정까지 포함한 실제 JVM 명령은 각 campaign의 `trials/*/command.json`에 보존돼 있다. 위 네 property만으로 전체 실험 설정을 재현한다는 뜻은 아니다.

선택된 `whole1m-v1` 진단 설정은 compact preparation에 더해 root whole admission만 다음처럼 높였다.

```text
-Dsysds.fedplanner.regional.exactClosureAssignments=1000000
-Dsysds.fedplanner.regional.regionWorkLimit=1000000
```

여기서 `exactClosureAssignments=1000000`은 protocol의 config-only 변경이다. Java 코드의 generic 기본값을 바꾸지 않았다. `exactClosureAssignments`와 `regionWorkLimit`의 코드 기본값은 모두 계속 `100000`이다. hard limit도 변경하지 않았으며 protocol은 factor 1,000,000 cells, total 5,000,000 cells를 유지한다.

최종 확인 프로토콜의 관련 설정은 다음과 같다.

| 설정 | 값 |
| --- | ---: |
| initial/max width | 2 / 4 |
| rounds/max steps | 256 / 256 |
| probe candidates | 2 |
| coverage period | 3 |
| maximum frontier | 2,048 |
| maximum region variables | 512 |
| root exact work gate | 1,000,000 |
| region exact work gate | 1,000,000 |
| factor/total cell hard cap | 1,000,000 / 5,000,000 |
| after-seed soft budget | 20,000 ms |
| outer process watchdog | 60 s |

이 recipe는 frozen native protocols와 완료된 diagnostic campaigns에서 사용한 property 구성을 옮긴 것이다. 문서 생성은 보존된 build·experiment 증거를 사용한다.

## 7. 수정 파일

구현 커밋은 다음 파일을 변경했다.

| 파일 | 변경 내용 |
| --- | --- |
| `src/main/java/.../fedExact/ExactPhysicalReducedSolver.java` | `Prepared` mapping, `prepareCompacted`, singleton factor conditioning, full assignment expansion 추가 |
| `src/main/java/.../fedExact/RegionalSearchProblem.java` | compact preparation mode와 mode별 problem instance, whole/region preflight→solve prepared reuse 연결 |
| `src/main/java/.../fedExact/RegionalSearchOptimizer.java` | `targetCompactPreparation` option, 기본값/boolean 검증, AnytimeTarget-only mode 선택 추가 |
| `src/main/java/.../fedExact/FederatedPlanLocalCost.java` | planner trace에 실제 `targetCompactPreparation` 상태 기록 |
| `src/test/java/.../fedExact/ExactPhysicalReducedSolverTest.java` | compaction·복원·상수·infeasibility·cap·freeze·random parity·GLM형 work 감소 테스트 추가 |
| `src/test/java/.../fedExact/TargetAnytimeOptimizerTest.java` | certificate/work 감소와 property disable·invalid value 테스트 추가 |
| `docs/ANYTIME_FAST_PLAN_KO.md` | implementation/pilot 진행 결정과 root1m 후속 판단 기록 |
| `docs/SESSION_ISSUES_2026-09-08.md` | 문제, 변경, 검증과 회귀 위험 기록 |

본 문서는 위 커밋을 설명하는 별도 구현 보고서이며, 구현 소스나 runner를 추가로 변경하지 않는다.

## 8. 테스트와 검토 증거

### 8.1 집중 테스트

새 테스트는 다음 위험을 직접 고정한다.

- original/auxiliary가 섞이고 singleton index가 비연속인 모델의 복원
- factor arity collapse, constant factor, all-singleton/zero-free solve
- AC 이후 infeasible model 처리
- raw lazy factor를 평가하기 전 input cap 거절
- preparation당 lazy factor 1회 freeze와 다음 preparation에서의 값 refresh
- seeded random forced model에서 raw solver, legacy reduced prepare, compact prepare의 assignment/objective bit parity
- GLM형 singleton 모델의 objective 유지와 exact work 감소
- physical integration에서 certificate 유지와 work 감소
- property `true`/`false`, 기본값과 invalid boolean 처리
- compact flag가 다른 algorithm과 Global 경로에 새 동작을 적용하지 않는 범위

### 8.2 실행 결과

| 환경 | Suite | Test | 실패 | 오류 | Skip | JAR SHA-256 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| local | 19 | 158 | 0 | 0 | 0 | `222d47cd4a8ddd56746f4c75efb86f195455e4b5f2372fea1dbfd3732f5b0869` |
| native so007 | 19 | 158 | 0 | 0 | 0 | `222d47cd4a8ddd56746f4c75efb86f195455e4b5f2372fea1dbfd3732f5b0869` |

native 검증은 추적 대상 `src/main`, `src/test`, `pom.xml`, `bin`의 source 7,502개를 확인했다. 이는 full project test suite 결과가 아니라 exact solver와 physical integration에 초점을 둔 19개 class의 결과다.

### 8.3 독립 검토

`reviews/compact-implementation-v1.md`의 판정은 `CLEAR`다. 검토는 다음을 확인했다.

- false certificate로 이어지는 assignment loss가 보이지 않음
- factor/scope ordering과 constant 보존
- preflight와 solve 사이 prepared object의 일회 재사용
- AnytimeTarget-only integration과 Global 기존 경로 유지

검토가 남긴 핵심 주의점은 solver statistics가 compact compiled problem의 admission work를 나타낼 뿐 preparation 전체의 peak memory를 나타내지 않는다는 것이다.

## 9. 실험 provenance와 현재 해석 범위

### 9.1 완료된 진단

세 campaign은 각각 32 trials다. PCA, LM, StepLM, GLM × LAN/WAN-mid × 3%/5% × Global/AnytimeTarget × 1 repetition으로 구성한 16 paired conditions다. 이를 16개 workload로 해석하면 안 된다.

| Campaign | 구현/설정 | Target 목표 달성 | 계약 검증 |
| --- | --- | ---: | --- |
| `pilot-before-fast-v1` | compact 전 baseline | 14/16 | canonical/raw audit valid |
| `pilot-compact-v1` | compact preparation | 14/16 | raw audit 0 errors, 479 external assets 확인, initial parity valid |
| `pilot-whole1m-v1` | compact + root gate 1m | 16/16 | raw audit 0 errors, 479 external assets 확인, initial parity와 paired oracle valid |

`compact-v1`의 GLM/WAN-mid 3%는 preparation이 약 10.42초에서 3.12초, 전체 planner가 16.14초에서 10.32초로 줄었지만 마지막 gap 11.6965%로 목표를 놓쳤다. root work 688,683을 100k gate가 거절한 뒤 13 regions와 약 7.4m work를 썼다.

`whole1m-v1`은 같은 prepared root를 허용했다. GLM/WAN-mid planner time은 3%에서 Target 4.6110초, Global 9.5600초였고 5%에서 Target 5.0087초, Global 12.2264초였다. Target은 두 threshold 모두 gap 0을 얻었다. 이 수치는 1 repetition 진단이므로 변동성을 일반화하지 않는다.

### 9.2 완료된 focused 확인

`glm-confirm-v1`은 GLM × 네 network profiles × 3%/5% × 두 methods × 2 paired repetitions, 총 32 trials로 시작됐다. protocol SHA-256은 `85d0ae8be6efe130882523aa282d986fa89941b1e0cae129984e6b81793409f8`이며 구현 커밋과 JAR/context를 고정했다.

32개 JVM 실행을 완료했고, AnytimeTarget의 16개 실행 모두 요청한 목표를 달성했다. 독립 Global과의 16개 paired 비교 모두에서 planner 시간과 JVM 시작부터 첫 인증까지의 시간이 짧았다. 각 환경·threshold당 2회 측정이다. Native 원본 감사는 모든 raw trial과 479개 외부 자산을 재해시해 오류 0개였고, canonical analysis의 16개 paired oracle 검증도 통과했다.

WAN-mid의 두 반복 중앙값은 5%에서 AnytimeTarget planner 4.556초, Global 10.242초이며 3%에서는 4.566초와 9.615초다. JVM 시작부터 첫 인증까지는 각각 14.558초 대 19.710초, 13.998초 대 19.081초였다. LAN은 초기 약 1.888% gap으로 멈췄고, 세 WAN profile은 root exact closure로 gap 0을 얻었다. 전체 표와 해석 범위는 [결과 보고서](ANYTIME_FAST_RESULTS_KO.md)에 기록했다.

### 9.3 중단된 기존 campaign

사용자가 범위를 AnytimeTarget 3%/5%로 좁히면서 기존 1,728-row broad campaign은 durable row 350에서 중단됐다. 중단 증거는 보존했으며 이 작업에서 재개하지 않았다. 따라서 full 16-workload campaign이 완료됐다고 주장하지 않는다.

## 10. 시간 지표의 의미

프로토콜은 서로 다른 경계를 분리해 기록한다.

| 지표 | 포함 범위 | 해석 |
| --- | --- | --- |
| launcher TTT | fresh JVM launch부터 AnytimeTarget의 첫 목표 달성 checkpoint 또는 Global의 `[Physical-CostContributionComplete]` 로그 도착까지 | JVM 시작, compile/planning과 seed를 포함; Global의 끝점은 exact 결과이며 별도 threshold checkpoint가 아님 |
| `planner_seconds` | 로그의 `Compile Phase FedPlanner` | 모델 구성·seed·인증 탐색 등을 포함하는 전체 federated planner 계측; 본문의 planner 비교 지표 |
| `certificate.methodElapsedMs` | certified search 진입부터 해당 로그까지 | seed와 initial bound를 포함하는 search 경로 계측; 전체 FedPlanner 시간과 다름 |
| `compilation_seconds` | 로그의 `Total compilation time` | planner 외 compiler 비용도 포함하는 전체 compilation 시간 |
| diagnostic/preparation | exact preflight와 preparation 누적 | freeze/reduction/compaction 및 admission 검사 포함 |
| bound/MBE | lower-bound 계산 누적 | initial/strengthening MBE 비용 |
| regional/exact | admitted solve 누적 | preparation과 구분된 compiled solve 비용 |

TTT 중앙값과 paired ratio는 양쪽 method가 모두 목표를 달성한 pair에서만 계산한다. 다만 miss, process failure와 누락 attempt는 전체 분모와 all-attempt planner cost에 남긴다. 성공한 실행만 골라 속도를 주장하지 않는다.

## 11. 메모리와 deadline 제한

### 11.1 메모리

compact `Prepared.statistics()`는 compact compiled problem의 `eliminationAssignments`, materialized cells와 maximum factor cells를 보고한다. 이 값은 exact admission 판단에는 맞지만 preparation peak memory 측정값은 아니다.

`prepareCompacted` 중에는 frozen raw table, reduced factor table과 compact factor table이 일시적으로 함께 존재할 수 있다. 따라서 compiled statistics가 줄었다고 process peak RSS가 같은 비율로 줄었다고 말할 수 없다. 진단 JVM은 `-Xms8g -Xmx8g -Xmn800m -XX:ActiveProcessorCount=8`로 고정했지만 별도 heap allocation profiler로 객체별 기여를 측정하지 않았다. profiler 근거 없이 특정 함수나 table이 peak RSS의 몇 퍼센트를 차지한다고 주장하지 않는다.

### 11.2 soft deadline

20초 budget은 seed upper bound를 계산한 뒤 `State.start`가 설정되는 after-seed soft scheduling budget이다. `expired()`는 interrupt 또는 elapsed time을 확인하지만 모든 내부 연산을 선점하는 hard deadline은 아니다. exact preparation 전후와 solver의 cancellation check 사이에 실행 중인 작업은 budget 경계를 넘길 수 있다.

별도의 60초 outer watchdog가 JVM을 제한한다. watchdog가 종료한 incomplete JVM에는 certificate 성공을 만들지 않는다. 따라서 다음 값을 혼동하면 안 된다.

- after-seed 20초: controller가 안전한 check point에서 중단하는 soft budget
- launcher TTT: seed와 JVM/compile overhead까지 포함
- outer 60초: process-level hard watchdog

## 12. 성능 주장 경계

이번 결과가 직접 지지하는 주장은 다음과 같다.

- selected compact preparation과 root1m 설정을 사용한 AnytimeTarget은 GLM 네 환경·두 threshold·각 2회 확인의 16개 paired 비교 모두에서 변경하지 않은 Global보다 짧은 planner/launcher TTT로 3%·5%를 달성했다.
- compact preparation은 검증 fixture와 pilot에서 model, initial seed/MBE/order와 canonical certificate를 유지했다.
- root1m은 새 Java 알고리즘이 아니라 이미 준비된 compact exact root를 허용한 config-only 선택이다.

다음 주장은 현재 증거가 지지하지 않는다.

- 모든 workload와 network에서 AnytimeTarget이 Global보다 빠르다.
- full 16-workload campaign을 완료했다.
- threshold search 자체가 동일 kernel의 Global보다 알고리즘적으로 우월하다.
- compact preparation이 process peak memory를 특정 비율로 줄였다.
- profiler로 exact preparation의 함수별 병목 비중을 확정했다.

특히 Global에는 singleton compaction을 적용하지 않았다. 따라서 `whole1m-v1`의 Global 대비 속도 차이는 optimized AnytimeTarget system과 unchanged Global의 제품 경로 비교다. 동일하게 compact한 Global을 대조군으로 두지 않았으므로 threshold 탐색의 고유한 알고리즘 speedup과 compact exact kernel의 효과를 분리하지 못한다.

## 13. 잔여 병목과 미구현 제안

### 13.1 LM·StepLM seed 비용

LM과 StepLM에서는 seed 생성·평가 및 initial planning 비용이 Global 시간과 비슷하거나 더 클 수 있다. 이 비용은 compact exact preparation 이전에 발생하며 after-seed soft budget에도 포함되지 않지만 planner time과 launcher TTT에는 포함된다. 완료된 단일 반복 진단에서도 LM은 일부 조건에서 Target이 Global보다 느렸고 StepLM은 네 LAN/WAN-mid threshold 조건에서 Target이 느렸다.

따라서 exact compaction만으로 universal speedup을 만들 수 없다. 다음 최적화가 필요하다면 LM/StepLM의 seed 경로를 별도로 측정하고, model·assignment·canonical cost를 유지하는 범위에서 병목을 좁혀야 한다. 현재 자료에는 함수별 sampling profiler 증거가 없으므로 구체 원인을 단정하지 않는다.

### 13.2 reduced-global-MBE 제안

`reviews/reduced-global-mbe-proposal.md`는 root exact preflight가 work gate에서 거절됐을 때 이미 계산한 exact reduction/compaction을 root-only MBE input으로 한 번 재사용하는 방안을 제안했다. 핵심 조건은 다음과 같다.

- initial raw MBE와 easy exit 뒤에만 실행
- unconstrained root 전용 token으로 conditional/region leakage 방지
- `L = max(L, L_reduced)`로만 게시
- compact conflict의 decision과 auxiliary identity를 원 source identity로 모두 복원
- resource failure 때 기존 L과 raw guidance 유지
- 한 번 소비한 input reference를 즉시 release
- process peak RSS를 별도로 검증

이 제안은 구현하지 않았다. 독립 검토의 판정은 `WATCH`이며, 구현 전에 root gate 100k→1m config-only 판정을 먼저 하라고 권고했다. `whole1m-v1`에서 root exact가 admission되어 gap 0을 만들었으므로 이번 생산 커밋에는 새 MBE lane을 추가하지 않았다. conditional reduced model을 범용 lower bound API로 노출하거나 raw initial MBE를 대체하는 방식도 채택하지 않았다.

## 14. 회귀 위험과 감지 방법

| 위험 | 현재 방어 | 추가 관측 |
| --- | --- | --- |
| singleton mapping으로 잘못된 original assignment 복원 | 비연속 mixed test, random parity, canonical raw-bit 검사 | physical integration에서 mismatch 예외와 assignment fingerprint 확인 |
| constant 삭제/합산으로 objective bits 변경 | factor 순서와 arity-0 factor 보존 test | independent Global oracle raw-bit parity |
| lazy factor 재평가로 값·시간 변동 | preparation 1회 freeze test, pending prepared 재사용 | preflight/solve counter와 factor evaluation counter 확인 |
| compact statistics를 peak memory로 오해 | admission work로만 문서화 | process peak RSS와 heap profile을 별도 수집 |
| root gate 증가로 큰 exact solve 허용 | 1m work gate와 기존 hard cell cap 유지 | exact assignments, preparation/solve time, outer watchdog, RSS 확인 |
| Global 또는 다른 algorithm 동작 변경 | ANYTIME_TARGET 조건과 property disable test | unchanged Global objective/statistics regression 확인 |
| soft deadline 초과 | cancellation check와 60초 outer watchdog | after-seed elapsed와 launcher wall time을 함께 보고 |

## 15. 증거 위치

- 구현 소스: `/home/mchoi/so007-anytime-fast-20260908`
- 작업 상태: `/home/mchoi/so007-anytime-fast-evidence-20260908/WORK_STATE.md`
- 구현 검토: `/home/mchoi/so007-anytime-fast-evidence-20260908/reviews/compact-implementation-v1.md`
- reduced MBE 제안 검토: `/home/mchoi/so007-anytime-fast-evidence-20260908/reviews/reduced-global-mbe-proposal.md`
- local/native test 증거: `/home/mchoi/so007-anytime-fast-evidence-20260908/validation`
- before campaign: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-before-fast-v1`
- compact campaign: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-compact-v1`
- whole1m campaign: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/runs/pilot-whole1m-v1`
- whole1m 비교: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/comparison-whole1m-v1/pilot_comparison.json`
- focused protocol: `/home/mchoi/so007-anytime-fast-evidence-20260908/native/protocol-glm-confirm-v1.json`

## 16. 완료 기준

구현 보고서 기준으로 다음 항목을 완료했다.

- production implementation과 적용 범위를 현재 source 기준으로 설명했다.
- invariant, model, numerical, reconstruction과 prepared reuse 계약을 기록했다.
- Global unchanged 경로와 root1m config-only 변경을 분리했다.
- local/native 19 suites·158 tests, source 7,502개와 동일 JAR hash 증거를 기록했다.
- 완료된 세 diagnostic pilot과 GLM focused confirmation의 범위·반복 수·원본 감사를 구분했다.
- timing, memory, soft deadline, seed bottleneck과 미구현 reduced-global-MBE의 한계를 기록했다.

완료한 성능 확인은 [결과 보고서](ANYTIME_FAST_RESULTS_KO.md)와 [GLM 반복 상세](ANYTIME_FAST_GLM_CONFIRM_KO.md)에서 확인할 수 있다. 모든 workload나 동일하게 compact한 Global 대비 우위는 검증하지 않았다.
