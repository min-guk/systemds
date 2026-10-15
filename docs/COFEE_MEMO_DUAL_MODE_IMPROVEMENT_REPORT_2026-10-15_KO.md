# COFEE Memo Global/Local 후속 개선 및 검증

기준은 `origin/main`의 `b8623e3481072940c07ef3962bcd55be526f1d09`에서 만든 독립 worktree `/home/mchoi/cofee-tree-dp-20261014`다. 기존 구현과 측정은 [이전 구현 보고서](COFEE_MEMO_DUAL_MODE_IMPLEMENTATION_REPORT_2026-10-15_KO.md)에 보존했다. 이번 작업은 factor 구조 진단, direct 경로와 메모리 admission 개선, 최초/최선 UB와 인증 시점 측정, 작은 exact cluster 하한과 조건부 seed 개선을 구현하고 비교한다. 기본 optimizer `EXACT`와 기존 `TREE_DP_GLOBAL/LOCAL`의 선택 경로는 유지한다.

## 1. 구현과 보장

### 공통 exact 경로와 두 종료 조건

`MEMO_GLOBAL`과 `MEMO_LOCAL`은 기존 `RuleMemo`, `MemoProgramModel`의 후보·원본 factor·합법성·비용 및 reconstruction을 공유한다. 먼저 propagation, 정확한 상수/동일 factor 정규화, tree kernel, class-aware variable elimination(VE)을 시도한다. Direct에서 끝나면 search 전용 BigDecimal 배열, cost-class 복제, cluster 및 seed를 만들지 않는다.

Direct가 작업량 또는 메모리 조건으로 적합하지 않으면 같은 원본 domain을 덮는 DFS cover로 넘어간다. 미생성 sibling도 부모 frame에 남아 있으므로 아직 탐색하지 않은 영역이 전역 LB에서 사라지지 않는다. 각 조건부 영역에서 다시 exact kernel을 적용하고, 영향 없는 factor의 domain별 하한과 작은 cluster 테이블을 재사용한다.

- **Global:** 전체 최적 비용과 기존 canonical tie 규칙까지 증명한다.
- **Local:** 정확해가 증명되면 `OPTIMAL`; 그렇지 않으면 `LB > 0`이고 `UB <= 1.05 × LB`일 때만 `EPSILON_OPTIMAL`이다.
- 0 또는 음의 LB에는 상대 오차 식을 적용하지 않는다. `UNKNOWN`이나 메모리/명시적 진단 한도 때문에 증명을 끝내지 못하면 성공으로 표시하지 않는다.
- 시간 또는 iteration 횟수로 Local을 성공 종료하지 않는다. Seed 작업 한도와 cluster cell 한도는 선택적인 추가 작업을 제한하며, 전체 인증 탐색의 종료 조건이 아니다.

`LB ≤ OPT ≤ UB`이고 `LB > 0`이므로 인증 조건은 `(UB−OPT)/OPT ≤ (UB−LB)/LB ≤ 0.05`도 보장한다. 비교는 exact BigDecimal로 수행하며 부동소수점 tolerance로 5% 경계를 넓히지 않는다.

`OPT`는 이 Memo 후보 공간과 비용 모델의 최적값이다. 실제 분산 학습 wall time의 5% 보장이나 다른 후보/비용 모델인 legacy optimizer에 대한 5% 보장은 아니다.

### 작은 cluster의 공동 하한

원본 목적함수를 native 비용과 movement 등 numeric primitive의 합 `F(x)=Σ f_i(x_scope(i))`로 둔다. Numeric primitive를 중복 없이 작은 그룹 `I_g`에 배정하되, 그룹의 변수 scope는 겹쳐도 된다. 각 그룹 안에 scope가 완전히 포함되는 원본 check를 적용하여 테이블을 완전히 계산한다. `NO` row는 제외하고 `UNKNOWN` row는 LB relaxation에서 허용한다.

그룹 밖 경계와 다른 그룹의 일치 조건을 풀어 둔 채 계산하는

`L_g(D) = min { Σ(i∈I_g) f_i(x) : x ∈ D_scope(g), contained checks ≠ NO }`

는 모든 전역 합법 해의 해당 비용 합 이하이다. 기존 독립 factor 하한의 그룹 합을 `B_g(D)`라 하면 각 그룹에 `max(B_g,L_g)`를 사용한다. 그룹에 배정되지 않은 numeric primitive의 기존 하한도 더한다. Numeric ownership이 서로 겹치지 않으므로 합계는 전역 하한이다. 같은 check가 여러 그룹에 적용되어도 비용 중복 계산은 없다. 이 논리는 signed cost에서도 성립한다.

빈 지역 테이블은 이번 구현에서 전역 infeasibility로 승격하지 않고 기존 finite 하한으로 돌아간다. 이후 원본 check/정확 탐색이 그 영역을 닫는다. 완성되지 않은 테이블은 공개하지 않는다. Query key에는 그룹 scope의 모든 원본 ordinal domain을 넣고, 초기 domain 밖으로 넓히는 query는 거부한다.

이는 작은 지역의 Cartesian table과 조건부 최소값 cache다. 모든 HOP을 무조건 tree로 만들거나, 최적 tree decomposition/treewidth를 구하는 구현은 아니다. 기존 tree/VE kernel과 함께 쓰며, 큰 나머지는 exact conditional search로 처리한다. 그룹 생성은 큰 scope 우선의 bounded heuristic이므로 최선의 partition이라는 보장은 없다.

### 초기 UB와 조건부 개선

기존 seed는 이미 닫힌 factor와 native 비용을 보며 greedy 선택하고, 막히면 조건부 exact repair를 한다. 추가 `improveSeed` 기능은 cluster 밖을 현재 incumbent에 고정한 작은 문제를 정확히 푼다. 모든 원본 check가 `YES`이고 원본 전체 비용이 엄격히 줄어든 후보만 채택한다. Conditional block의 infeasibility나 제한 도달을 전체 문제의 증명으로 쓰지 않는다. Seed 선택은 proof domain을 변경하지 않는다.

최초 pilot에서 GNMF seed 비용은 910.4603→870.4603으로 개선됐지만, incumbent 기반 탐색 순서가 바뀌면서 Global 방문 노드가 111→4,052로 증가했다. 따라서 **조건부 seed 개선은 구현·검증하되 기본값은 false**로 두었다. Cluster 하한은 기본 4,096 cells로 유지한다. 더 낮은 초기 UB가 전체 DFS를 언제나 빠르게 만든다는 가정은 이 측정에서 반증됐다.

### 메모리와 측정

VE preparation 뒤 이미 할당된 metadata를 새 allocation으로 다시 계산하던 부분을 고쳤다. `admit(residentMetadata, additionalTablesAndCache)`로 구분하여 configured ceiling에는 둘의 합을, 현재 JVM/cgroup headroom에는 추가 allocation만 비교한다. Search-only 배열은 direct 실패 뒤로 미뤘고 동일 native binary64 값의 exact 변환을 공유한다. Cache 삽입 때 동일 admission을 두 번 읽던 경로도 제거했다. `directCells=0`이면 tree를 시도한 뒤 VE preparation을 생략한다.

기본 solver 메모리 상한은 `Long.MAX_VALUE`이며 현재 JVM/cgroup headroom으로 admission한다. 진행과 reconstruction을 위한 reserve는 `min(256 MiB, max(16 MiB, JVM heap / 8))`다. 메모리가 부족하면 optional cache/cluster/history를 버릴 수 있고, 기존 frame 하한은 유지한다. 필수 proof state도 보유할 수 없으면 `RESOURCE_EXHAUSTED`다. JVM 전체의 정확한 heap 모델이나 OOM 불가능 보장은 아니다. 후보 생성·shape 계산 등 solver 진입 전 메모리는 여전히 별도 제약이다.

추가 통계 `firstUpperNanos`, `bestUpperNanos`, `certificateNanos`는 controller 생성 시점부터 최초 검증된 UB, 최종 비용을 처음 얻은 시점, 성공 인증 반환 시점까지다. 실패 시 certificate는 -1이다. 동일 비용의 canonical witness 변경은 best-UB 시간을 바꾸지 않는다. `setupNanos`, `kernelNanos`, `seedNanos`, `boundNanos`, `searchNanos`, cluster build/query 통계를 함께 기록한다. 서로 포함 관계가 있으므로 단순 합산해서 전체 시간으로 해석하지 않는다.

## 2. 실제 의존성 진단

`-Dsysds.fedplanner.memo.factorDiagnostics=true`일 때만 factor 등록 위치에서 provenance를 붙이고 graph 및 evaluator timing을 출력한다. 분류는 native/privacy unary, input supply, loop boundary/entry, retained shared movement, unretained movement다. Retained category 안에서도 `shared:true/false`, demand 수를 구분한다. 함수/branch/loop는 실제 binding `CALL_ARG`, `CALL_RETURN`, `FORMAL`, `SELECT`, `LOOP_HEAD`, `LAST`와 region/frame을 따라 tag한다.

원본 scope의 primal graph에 대해 edge, connected component, leaf peeling, core, min-fill separator와 table row 추정을 계산한다. Category 또는 relation tag 하나를 제거한 graph도 같은 원본 domain에서 계산한다. 이는 **진단용 반사실적 graph**이며 solver에서 그 제약을 제거하지 않는다. Tag는 서로 겹치므로 제거 효과를 합산할 수 없다. 각 graph에서 deterministic min-fill order를 별도로 구하므로 숫자는 treewidth 또는 인과적 기여율이 아니다. Native base-array lookup은 timing wrapper 대상이 아니며 `instrumented=false`로 표시한다.

이 raw graph를 이전 [정규화 후 ML 구조 측정](COFEE_ML_STRUCTURE_AND_SEED_AUDIT_2026-10-15_KO.md)의 active-variable graph와 혼동하면 안 된다. 이전 측정의 실제 multi-valued 변수 leaf 축약 비율은 LogReg 15.0%, GLM 12.7%, GNMF 0.0%였다. Singleton이 많은 raw HOP/model graph의 높은 leaf 비중만으로 tree fast path의 효용을 주장할 수 없다.

### 원본 factor graph에서 관측한 구조

세 workload의 diagnostic direct solve는 모두 원래 EXACT와 같은 비용으로 `OPTIMAL`이었다. 아래 core는 singleton을 포함한 원본 scope의 2-core이며, 앞서 설명한 정규화 후 active-variable core와 다르다.

| 원본 graph | LogReg | GLM | GNMF |
|---|---:|---:|---:|
| 모델 변수 슬롯 | 489 | 1,001 | 85 |
| 전체 edge | 504 | 1,155 | 87 |
| 전체 core 변수 | 225 | 447 | 39 |
| retained movement 제외 후 edge/core | 461 / 223 | 1,025 / 447 | 84 / 39 |
| input-supply 제외 후 edge/core | 223 / 85 | 533 / 196 | 38 / 20 |
| function-tag 제외 후 edge/core | 84 / 22 | 146 / 55 | 38 / 14 |
| loop-tag 제외 후 edge/core | 130 / 54 | 504 / 205 | 46 / 21 |
| 전체 min-fill 최대 separator 변수 | 13 | 21 | 4 |

Retained movement 제거만으로 core가 없어지는 workload는 없었다. 특히 GNMF는 edge 3개가 줄어도 core 39개가 유지됐다. 따라서 materialization을 유일한 conflict 원인으로 보는 접근은 이 세 사례와 맞지 않는다. Function/loop tag 제거는 다른 factor category까지 함께 제거하므로 “함수가 전체 시간의 몇 %를 유발한다”는 해석도 할 수 없다. Raw graph의 큰 table-row 추정은 원본 ordinal을 이용한 진단값이며 실제 VE는 propagation과 certified class 압축을 적용한다.

### Factor 수와 평가 비용

전체 raw factor 출력을 수집한 별도 실행에서 LogReg 9,595개, GLM 17,093개, GNMF 614개를 기록했다. 첫 diagnostic의 기본 출력 cap 10,000은 GLM raw row를 잘랐기 때문에 최종 run은 cap 50,000으로 재실행했다. Graph와 evaluator 합계는 raw 로그 cap과 독립적이다.

| Factor 분류 | LogReg | GLM | GNMF |
|---|---:|---:|---:|
| Native unary(배열 포함) | 4,651 | 9,882 | 228 |
| Privacy unary | 561 | 884 | 92 |
| Input supply | 4,024 | 5,544 | 249 |
| Loop boundary | 71 | 349 | 9 |
| Retained movement | 224 | 342 | 25 |
| 그중 여러 demand를 가진 shared:true | 53 | 76 | 7 |
| Unretained movement | 49 | 64 | 8 |
| Loop entry | 15 | 28 | 3 |

같은 diagnostic 실행에서 retained movement evaluator의 누적 시간은 LogReg 389.22 ms, GLM 1,262.96 ms, GNMF 54.43 ms로 모든 category 중 가장 컸다. Input supply는 85.85/277.69/13.33 ms였다. **구조의 핵심 의존성과 가장 비싼 factor 평가가 동일하지 않을 수 있다.** 이 시간은 instrumentation을 켠 한 run의 누적 evaluator 시간이며 전체 planner 시간의 독립적 분해나 계측 없는 성능 median이 아니다.

## 3. 검증 방법과 비교 조건

새 단위 테스트는 별도 구현한 Cartesian exhaustive oracle을 사용한다. 겹치는 scope에서 numeric ownership 1회 계산, 서로 충돌하는 factor별 최소값, signed/high-order 비용, UNKNOWN, immutable ordinal/class, cache domain signature, memory decline, seed 개선을 검증한다. 추가 randomized test는 20개 5-binary fixture × cluster budget 0/8/16/64 × Global/Local, 총 160개 solve를 독립 oracle과 비교한다. 모든 공개 LB/UB 점의 단조성과 최적값 포함, Global canonical tie, Local exact 5% 부등식을 확인한다.

Docker 성능 실행은 `scripts/fedplanner/run_LAN_docker.sh --memo-tree-dp-benchmark`를 통해 순차 실행한다. W3, 4 CPU, container 8 GiB, 기본 Java heap 3,072 MiB이며 이전 frozen engine과 새 engine의 순서, workload와 algorithm 순서를 seed 20261015로 섞는다. 각 cell은 독립 cold JVM이다. Host 전체가 독점인 환경은 아니므로 최솟값 하나로 향상을 주장하지 않고 반복 median/range와 원본 로그를 보존한다.

컨테이너 peak는 Docker MemUsage sampling의 high-water로, charged cache를 포함하고 RSS나 정확한 JVM allocation peak는 아니다. Low-heap 및 diagnostic 실험은 일반 성능 median에 합치지 않는다. 외부 watchdog은 실험 실패를 분류하기 위한 장치이며 optimizer의 epsilon 성공 조건으로 쓰지 않는다. 기존 legacy Regional은 동일 후보·합법성·목적함수로 정렬한 synthetic 문제에서만 직접 비용 비교한다. 실제 ML에서 모델이 다른 legacy Local/Global과 cost 숫자를 동일 OPT로 비교하지 않는다.

## 4. 실측 결과

### 4.1 기본 설정: 이전/수정 엔진 각각 7회

126회 source-fullcompile 비교가 모두 성공했고, 84개 dual-mode solve는 모두 `DIRECT / OPTIMAL`이었다. 아래는 optimizer phase의 cold JVM median 초이며, candidate/model 구축을 포함하는 fullcompile 시간은 별도 artifact에 기록했다.

| Workload | EXACT 이전→수정 | Global 이전→수정 | Local 이전→수정 |
|---|---:|---:|---:|
| LOGREG | 0.865 → 0.866 | 0.926 → 0.908 | 0.838 → 0.918 |
| GLM | 2.222 → 2.458 | 2.630 → 2.367 | 2.452 → 2.377 |
| GNMF | 0.190 → 0.181 | 0.195 → 0.228 | 0.233 → 0.198 |

수정판 Global/동시 측정 EXACT 비율은 LogReg 1.049, GLM 0.963, GNMF 1.262다. Local은 각각 1.060, 0.967, 1.095다. **GNMF Global의 direct 회귀는 해소되지 않았다.** 또한 LogReg Local은 이전 대비 약 9.6% 느려졌다. GLM Global은 이전 대비 약 10.0% 짧았지만, 수정하지 않은 EXACT의 GLM median도 2.222→2.458초로 변했다. 따라서 단일 코드 변경의 인과적 10% 향상으로 단정하지 않는다.

수정판의 optimizer phase 범위와 Docker MemUsage median은 다음과 같다.

| Workload / algorithm | 계획 시간 min–max (초) | MemUsage median (MiB) |
|---|---:|---:|
| LOGREG / EXACT | 0.754–1.116 | 314.9 |
| LOGREG / MEMO_GLOBAL | 0.814–1.146 | 290.3 |
| LOGREG / MEMO_LOCAL | 0.851–1.160 | 301.8 |
| GLM / EXACT | 2.057–3.256 | 1040.4 |
| GLM / MEMO_GLOBAL | 2.253–3.300 | 1105.9 |
| GLM / MEMO_LOCAL | 2.252–3.046 | 1068.0 |
| GNMF / EXACT | 0.153–0.230 | 174.0 |
| GNMF / MEMO_GLOBAL | 0.199–0.280 | 157.9 |
| GNMF / MEMO_LOCAL | 0.168–0.284 | 174.6 |

Direct 경로에서는 중간 search LB/UB 자체를 만들지 않는다(`boundNanos=searchNanos=0`). Global의 search 경로도 epsilon 부등식을 검사하지 않는다. 가지치기를 위한 하한 계산과 단순한 인증/출력 검사를 구분해야 하며, 이번 실험은 인증 검사만 제거한 ablation이 아니므로 “중간 검사를 끄면 더 빠르다”는 결론을 내리지 않는다.

이 비교에서는 search-only cluster/seed가 실행되지 않았다. 따라서 이 표로 cluster의 효과를 주장하지 않는다. 기존 EXACT의 전역 default를 새 모드로 바꾸지 않았다.

### 4.2 GNMF conflict-search ablation: 각 설정 3회

Direct VE를 `directCells=0`으로 비활성화하고 tree kernel은 유지했다. 같은 run의 EXACT는 정상 direct 경로를 사용하므로 이 ablation의 속도 향상은 **동일한 search 경로에서 cluster를 넣은 효과**다. Default EXACT보다 빠르다는 실험이 아니다. 세 설정은 모두 같은 원본 optimum `217.0473253831168…`을 사용한다.

| 설정 | Global 시간 / 노드 | Local 시간 / 노드 | Local 최종 상태 |
|---|---:|---:|---|
| independent factor LB | 2.966초 / 651 | 3.280초 / 651 | OPTIMAL |
| cluster LB | 1.771초 / 111 | 1.595초 / 108 | EPSILON_OPTIMAL |
| cluster + 조건부 seed 개선 | 9.903초 / 4,052 | 10.222초 / 4,049 | EPSILON_OPTIMAL |

Cluster는 Global 시간 40.3%, Local 시간 51.4%를 줄였다. Root LB는 `130.812119… → 161.6541459664603…`으로 강화됐다. Local의 terminal `LB=207.3058445875744…`, `UB=217.0473253831168…`이므로 certified gap은 **4.699086%**다. 독립 EXACT와 비교하면 실제 비용 오차는 0이지만, 이 Local run은 exact 증명을 끝내지 않았으므로 `OPTIMAL`로 승격하지 않는다.

27개 그룹에 총 4,096 table cells를 만들었고 retained estimate는 551,080 bytes(약 0.526 MiB)였다. Global은 545회의 전체 cluster query에서 13,328회의 개별 그룹 cache hit를 기록했다. 한 전체 query가 27개 그룹을 검사하므로 두 수치는 같은 단위가 아니다. Local은 각각 520회와 12,693회다. Max pending frame은 두 모드 모두 10이었다.

각 timestamp의 median은 다음과 같다. Controller 생성 이후의 초이며, optimizer phase 전체 시간과 시작점이 약간 다르다.

| 설정 / mode | 첫 합법 UB | 최선 UB 최초 도달 | 인증 반환 |
|---|---:|---:|---:|
| independent Global | 0.269 | 0.826 | 2.951 |
| independent Local | 0.189 | 0.717 | 3.257 |
| cluster Global | 0.601 | 1.064 | 1.751 |
| cluster Local | 0.528 | 0.982 | 1.576 |
| cluster+seed Global | 0.440 | 9.712 | 9.883 |
| cluster+seed Local | 0.487 | 10.003 | 10.206 |

기존 independent 경로도 최적 비용의 UB를 일찍 찾았다. 인증까지 남는 시간이 길었으므로 이 사례에서는 UB만 개선하는 것보다 LB를 강화하는 편이 효과적이었다. Cluster build median은 Global 0.369초, Local 0.346초다. 첫 UB는 늦어졌지만 이후 certificate가 빨라졌다. Cluster Global의 kernel/bound/search 시간은 각각 0.683/0.339/1.149초이고 independent는 1.648/0.380/2.764초다. Search에는 kernel/bound 호출이 포함된다.

조건부 seed 개선은 초기 UB를 910.4603→870.4603으로 낮췄으나 incumbent를 선호하는 DFS 순서를 바꿔 최선 UB 도달이 약 10초로 늦어졌다. 단순히 seed 개선 호출 자체가 비싸서 생긴 회귀만은 아니다(그 seed phase median은 약 0.24–0.29초). 이 옵션을 기본으로 켜지 않은 근거다. Pilot과 반복 실험 모두 같은 노드 수를 재현했다.

### 4.3 저메모리, runtime 및 최종 감사

128/256/512 MiB heap에서 GLM과 GNMF를 EXACT/Global/Local로 각각 실행했다. GNMF는 세 heap에서 모두 성공했다. GLM은 256/512에서 모두 성공했고, 128에서는 **세 solver 모두 공통 후보/shape 준비 중 OOM**으로 실패했다. 이 실패에는 유효한 certificate가 없으며 성공 계획으로 집계하지 않는다. 128 MiB가 모든 프로그램의 보편적 최소치라는 의미는 아니다.

GLM 256 MiB에서는 이전/수정 엔진을 추가로 각각 3회 순서 혼합 비교했다.

| Algorithm | 이전 median [min–max], 초 | 수정 median [min–max], 초 | 경로 변화 |
|---|---:|---:|---|
| EXACT | 2.805 [2.724–3.086] | 2.926 [2.570–2.952] | 기존 exact 경로 유지 |
| MEMO_GLOBAL | 127.581 [2.973–198.607] | 3.107 [3.096–3.191] | 이전 search 2/3회 → 수정 direct 3/3회 |
| MEMO_LOCAL | 5.019 [3.023–5.792] | 3.029 [2.707–3.620] | 이전 search 2/3회 → 수정 direct 3/3회 |

이전 Global의 search 두 run은 557/371노드를 처리했고, 나머지는 direct였다. 모든 run은 결국 OPTIMAL로 끝났다. 수정판 여섯 dual run은 모두 search 준비 없이 direct OPTIMAL이었다. 이 표는 동적 headroom 때문에 **이전 admission의 경로 선택이 불안정했다는 관측**과 수정 후 이번 표본의 경로 안정화를 보여 준다. 3회만으로 모든 heap 상태에 대한 상한이나 일반적인 41배 향상을 주장하지 않는다. 수정판도 같은 heap의 EXACT median보다 Global 약 6.2%, Local 약 3.5% 느리다.

별도 admission trace에서는 32 MiB reserve를 유지하면서, VE 직전 resident 1,356,200 bytes / 추가 24,467,680 bytes를 허용했다. reserve 차감 후 physical headroom은 31,389,656 bytes였다. 이미 할당된 metadata와 앞으로 할당할 table을 구분한 판단이며 강제 GC나 heap 확대를 사용하지 않았다. Lazy 준비와 admission 수정 각각의 단독 효과는 별도 ablation하지 않았으므로 두 변경의 기여율을 분리하지 않는다.

검증 결과는 다음과 같다.

- Java **158 tests**, failures/errors/skipped 모두 0. 여기에는 160개의 randomized oracle solve를 수행하는 test가 포함된다. 초기 clean 통합 실행 뒤 추가 randomized case 및 기본 옵션 변경에 대한 targeted rerun을 합친 최종 XML이다.
- Benchmark harness Python **21 tests**, independent 결과 감사기 hostile Python **10 tests** 통과.
- Source-fullcompile **208 attempts: 205 passed, 3 failed(OOM)**. 이전/수정 비교, pilot, ablation, heap, diagnostic, runtime suite의 compile을 모두 포함한다.
- **139 certificates: OPTIMAL 131, EPSILON_OPTIMAL 8**. Progression과 final을 포함한 **787개 bound point** 모두 `LB≤same-source EXACT OPT≤UB`, LB 비감소·UB 비증가, 정확한 epsilon 부등식, terminal plan cost=UB를 만족했다. Missing-baseline/unaudited certificate는 0이다.
- Counterfactual factor 삭제는 진단에만 사용했다. 기본 EXACT의 candidate/cost/legality/reconstruction 변경과 runtime fallback은 없다.

전체 프로젝트 test suite나 별도 lint/정적 분석 도구 전체를 통과했다고 주장하지 않는다. Java compile/type checking, 표적 회귀·oracle·실행 테스트, Python syntax/unit tests, `git diff --check`와 문서/산출물 대조를 수행했다. OOM을 임의 위치에 주입한 모든 failure path나 모든 가능한 ML 프로그램을 전수 검증한 것도 아니다.

### 4.4 실제 실행 및 기존 Tree DP/Regional 비교

Docker에서 작은 federated linear/logistic 학습을 5개 알고리즘으로 실제 실행한 **10개 검사가 통과**했다. 결과 weights와 원본 비용을 비교한다. LogReg/GLM/GNMF 대형 대표 source의 실험은 **전체 compile 및 계획 검증**이며, 대규모 학습을 끝까지 실행한 throughput benchmark는 아니다. 이 둘의 검증 범위를 구분한다.

Synthetic benchmark는 6개 구조에서 총 42개 결과 row를 냈다(기본 5개 모드 30개 + 추가 탐색 옵션 12개). 아래는 동일 JVM 안의 각 알고리즘 7회 평균(ms)이다. 실행 순서/JIT/heap 상태가 이어지므로 위 cold JVM 표와 합치지 않는다.

| 구조 | EXACT | 기존 Tree Global | 기존 Tree Local | 새 Memo Global | 새 Memo Local |
|---|---:|---:|---:|---:|---:|
| tree-32 | 1.910 | 1.172 | 0.946 | 1.959 | 1.931 |
| cycle-16 | 0.990 | 0.345 | 0.344 | 1.401 | 1.374 |
| diamond-8 | 0.411 | 0.320 | 0.312 | 0.752 | 0.771 |
| shared-wide-8 | 0.473 | 0.452 | 0.452 | 1.002 | 0.935 |
| adversarial-shared-wide | 2.322 | 2.452 | 1.502 | 3.017 | 1.960 |
| epsilon-boundary-3 | 0.144 | 0.160 | 0.087 | 0.477 | 0.425 |

작은 문제에서는 controller/admission/검증 비용이 상대적으로 크며 새 Memo가 더 느린 경우가 많다. `epsilon-boundary-3`의 기존 Tree Local은 epsilon 종료, 표의 새 Memo 두 모드는 direct exact 종료다. 같은 시간값이라도 종료 보장의 차이를 함께 보아야 한다.

같은 numeric/legality factors로 정렬한 별도 `adversarial-shared-wide` Regional 비교에서는 legacy Regional, categorical exact, Memo EXACT, 기존 Tree Global, 새 Memo Global/Local 모두 비용 **1.4375**로 일치했다. 이는 일반 legacy Local/Global ML 계획의 후보·비용 모델이 Memo와 같다는 주장이 아니다. 역사적 solver/모델 차이는 [기존 상세 비교 문서](COFEE_LEGACY_MEMO_TREE_DP_COMPARISON_2026-10-15_KO.md)를 참조한다.

## 5. 선택 및 재현

MEMO planner 안에서 `-Dsysds.fedplanner.memo.algorithm=MEMO_GLOBAL` 또는 `MEMO_LOCAL`로 독립 선택한다. 지정하지 않으면 기존 `EXACT`다.

| 새/관련 옵션 | 기본값 | 역할 |
|---|---:|---|
| `sysds.fedplanner.memo.dual.clusterCells` | 4096 | 모든 선택 cluster의 aggregate table cell 한도; 0이면 independent bound |
| `sysds.fedplanner.memo.dual.improveSeed` | false | 조건부 초기 UB 개선; 측정된 ordering 회귀 때문에 opt-in |
| `sysds.fedplanner.memo.dual.seedWork` | 100000 | seed/repair/improvement 작업 한도; proof search 한도 아님 |
| `sysds.fedplanner.memo.dual.directCells` | 2000000 | direct VE 작업 선택 기준; 0에서도 tree kernel은 시도 |
| `sysds.fedplanner.memo.dual.memoryBytes` | Long.MAX_VALUE | 추정 resident+additional 상한; 실제 headroom 별도 적용 |
| `sysds.fedplanner.memo.dual.diagnostics` | false | kernel phase와 memory admission 이유 JSON |
| `sysds.fedplanner.memo.factorDiagnostics` | false | typed factor/graph/evaluator 진단 |

예외적인 resource/UNKNOWN 결과는 호출부에서 명시적으로 실패 처리한다. 인증되지 않은 incumbent가 있어도 성공한 계획으로 내보내지 않는다.


```bash
mvn -q clean -Dspotless.check.skip=true -Drat.skip=true \
  '-Dtest=MemoDualMode*Test,MemoFeasibleSeedTest,MemoDirectKernelTest,MemoOptimizer*Test,MemoTreeDp*Test,MemoClusterBoundsTest,MemoFactorDiagnosticsTest' test
python3 -m unittest discover -s scripts/fedplanner/tests -p test_run_memo_tree_dp_benchmark.py
python3 -m unittest discover -s experiments/memo-dual-improvement-20261015 -p analyze_results_test.py
python3 scripts/fedplanner/prepare_memo_tree_dp_engine.py --output /absolute/path/new-engine
python3 experiments/memo-dual-improvement-20261015/run_evaluation.py \
  --suite comparison --engine /absolute/path/new-engine --repetitions 7 --output /absolute/path/new-comparison
```

`--suite diagnostics`, `ablation`, `heap`, `runtime`도 각각 새 output 경로로 실행한다. Ablation은 GNMF에서 direct VE를 끄고 independent, cluster, cluster+seed를 비교한다. 모든 manifest, argv, source hash, fullcompile evidence, certificate와 bound progression을 남긴다. Sealed stage 및 고정 Docker image가 필요하며 그것 없이 동일 입력을 재생성하는 완전 자급형 benchmark라고 주장하지 않는다.

전체 저장 결과의 독립 인증 감사는 `python3 experiments/memo-dual-improvement-20261015/analyze_results.py --root experiments/memo-dual-improvement-20261015`로 재현한다. 새 실험의 경우 `--root`에 같은 형식으로 수집한 결과 루트를 지정한다.

## 6. 결론과 남는 제한

이번 결과는 **작은 cluster의 공동 하한이 conflict-search 인증 비용을 줄일 수 있다**는 근거다. 반면 실제 세 ML source의 기본 설정은 이미 direct exact로 처리되므로 그 경로에서 5% Local의 조기 종료 이득은 관측되지 않았다. Tree 여부만으로 구현을 선택하기보다 정규화된 전체 factor graph, certified class 수, separator 상태 수와 memory admission을 함께 봐야 한다.

현재 권장 설정은 기존 EXACT default 유지, 새 모드 opt-in, 공통 tree/VE 우선, fallback의 clusterCells=4096, improveSeed=false다. 이 권장은 측정한 workload/조건에 대한 판단이며 모든 graph의 가장 빠른 알고리즘이라는 주장은 아니다. Small-region table은 아직 certified class로 추가 압축한 cluster DP나 min-sum dual decomposition 구현이 아니다. 그룹 순서/크기 selector와 incumbent 기반 branch 순서도 heuristic이다.

남는 성능 문제는 GNMF direct 오버헤드, 작은 문제에서의 controller/admission 비용, cluster 준비에 따른 first-UB 지연, 모델 준비 단계의 GLM128 OOM이다. 더 낮은 initial UB만을 추구하면 인증 시간이 오히려 늘 수 있다는 반례도 보존했다. 메모리를 더 허용하는 것만으로 원본 factor의 큰 separator나 인증 탐색 복잡도가 사라지는 것은 아니다.

## 7. 코드와 증거

- [공통 dual controller](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoDualModeOptimizer.java), [cluster bound](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoClusterBounds.java), [feasible seed](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoFeasibleSeed.java)
- [기존 exact kernel의 additive direct API](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoOptimizer.java), [resource policy](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoResourcePolicy.java), [typed diagnostics](../src/main/java/org/apache/sysds/hops/fedplanner/memo/MemoFactorDiagnostics.java)
- [실험 실행기](../experiments/memo-dual-improvement-20261015/run_evaluation.py), [독립 감사기](../experiments/memo-dual-improvement-20261015/analyze_results.py), [감사기 hostile tests](../experiments/memo-dual-improvement-20261015/analyze_results_test.py)
- [집계 결과](../experiments/memo-dual-improvement-20261015/aggregate.json), [인증 감사](../experiments/memo-dual-improvement-20261015/result-validation.json), [factor 분석](../experiments/memo-dual-improvement-20261015/factor-analysis.json)
- [Java 결과](../experiments/memo-dual-improvement-20261015/java-test-summary.json), [Python harness 결과](../experiments/memo-dual-improvement-20261015/python-test-output.txt), [감사기 tests 결과](../experiments/memo-dual-improvement-20261015/auditor-test-output.txt)
- [최종 engine-v3 manifest](../experiments/memo-dual-improvement-20261015/engine-v3-sha256.json), [이전 engine-v1 manifest](../experiments/memo-dual-mode-20261015/engine-v1-sha256.json)
- [최종 source/binary 해시·문서 링크·인증 검증](../experiments/memo-dual-improvement-20261015/delivery-validation.json)
- [동일 JVM synthetic 및 실제 실행 결과](../experiments/memo-dual-improvement-20261015/runtime/runtime-and-regional/summary.json), [pilot의 seed 회귀](../experiments/memo-dual-improvement-20261015/pilot-ablation/cluster-seed-01/summary.json)

기존 engine-v1과 pilot engine-v2는 덮어쓰지 않았다. 최종 구현/기본 설정은 engine-v3에 고정했다. 재현 명령의 output 경로는 새 경로를 사용해야 한다. `run_evaluation.py --suite heap-paired --repetitions 3`은 추가 GLM256 순서 혼합 비교를 재현한다. 모든 실행은 원본 `summary.json`, `manifest.json`, `command.json`, `container-run.sh`, coordinator 로그를 보존한다.
