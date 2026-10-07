# StepLM 전체 planning 20초 최적화

## 범위와 완료 기준

기존 joint compact / publication closure 수정은 `9668cb432f`로 `origin/main`에 게시했다.
이후 성능 수정은 별도 worktree `steplm-planning-20s-20261007`에서 진행한다.

측정 대상은 실제 StepLM builtin을 실행하는 full-rank 20×5 `ml_steplm_local_matrix`다.
전체 compilation에는 공통 분석, 비용 모델, DP 선택, plan 변환·emission과 나머지 compiler 작업이 포함된다.
학습 execution과는 별도다. 큰 모델과 별도 CSV 입력 문제는 이 작업에서 제외한다.

완료 조건은 동일 image·4CPU·8GiB·coordinator 3GiB·network none 환경에서 fresh JVM 3회가
각각 compilation ≤20초이며, 다음 정확성 조건도 모두 통과하는 것이다.

- CP/FED 모델의 5개 계수가 정확히 일치하고 선택 순서가 `[3,1,5]`다.
- canonical 선택 비용은 기준 `37040.115993804146`보다 나빠지지 않는다.
- runtime audit, conversion, overlay/class preflight가 통과한다.
- 합법 후보, source/version/lifetime/layout authority와 비용 의미를 보존한다.
- 임의 탐색 제한, resource 정책 완화, runtime fallback이나 거짓 수렴을 사용하지 않는다.

## 중간 실제 측정

아래 수치는 계측 없는 official Docker 실행이다. JFR과 host 단위 테스트의 시간은 이 표에 넣지 않는다.
screen21에서 처음 20초 미만을 관측했지만, 같은 코드를 동결한 첫 최종 3회는
23.978670 / 22.038707 / 23.449315초로 모두 실패했다. 정확성·source/class provenance는
모두 통과했으므로 19.42초 단일 관측을 완료 근거로 사용하지 않는다. 추가 최적화를 진행한다.

| 실행 | 전체 compile(s) | planner checkpoint(s) | DP(s) |
|---|---:|---:|---:|
| 게시 기준 | 104.571702 | 88.610979 | 83.289749 |
| screen01 | 59.079543 | 40.239282 | 34.454673 |
| screen03 | 35.078982 | 18.147644 | 12.821352 |
| screen05 | 26.082991 | 8.722863 | 4.434218 |
| screen06 | 26.748982 | 9.559372 | 4.643374 |
| screen07 | 25.627615 | 9.099918 | 4.414254 |
| screen08 | 27.177834 | 9.270564 | 4.627897 |
| screen10 | 24.088358 | 7.530373 | 3.953473 |
| screen11 | 25.869660 | 7.914483 | 4.322217 |
| screen13 | 22.906487 | 7.646249 | 3.794578 |
| screen14 | 24.593830 | 8.841332 | 5.205567 |
| screen16 | 25.068025 | 7.598327 | 3.959416 |
| screen17 | 21.998949 | 7.094256 | 3.579083 |
| screen18 | 22.911385 | 8.097155 | 4.380934 |
| screen19 | 21.966860 | 6.798383 | 3.576946 |
| screen21 | 19.416974 | 5.731694 | 3.279668 |
| screen23 | 22.990939 | 6.348077 | 3.524877 |

모든 실행에서 선택 비용·5개 계수·선택 순서·audit가 일치했다. 개별 screen 사이의 작은 차이를
기능별 확정 성능 개선으로 해석하지 않는다. 원자료 경로와 SHA는
[`progress.json`](experiments/steplm-planning-20s-20261007/progress.json)에 기록한다.

## 구현과 의미 보존

DP boundary merge는 high와 certified lower가 모두 +∞인 행만 제외하고, 가능한 support를 join한다.
원래 child 합산 순서, high/low raw 값, 독립 lower bound, 원래 좌표의 동률 backtrace를 유지한다.
overflow 안전성을 증명할 수 없는 경우에는 기존 dense 계산을 사용한다.

child들의 high/low/lower 응답이 같은 축값은 같은 저장 위치를 사용한다. 논리 domain/scope와
사전 cell cap은 그대로이며, 다음 factor가 동치류를 나누는 경우와 실제 좌표의 backtrace도 지원한다.
retained storage가 줄어 동일 resource 정책 안에서 더 많은 merge를 진행할 수 있게 됐다.
기준 4,869 merge·하한 `19529.714826506737`에서 screen05 이후 4,899 merge·하한
`23216.114867705463`으로 바뀌었다. 모든 search checkpoint가 기준과 동일하다는 주장은 하지 않는다.
최종 phase는 RESOURCE이며 전역 최적해가 증명됐다는 뜻도 아니다.

조건부 solve는 같은 immutable root의 준비 결과를 공유하고, 같은 block·같은 외부 assignment에서
성공한 결과를 재사용한다. solver가 계산한 auxiliary witness도 보존한다. root identity·고정 경계·
좌표 변환을 확인하고 전체 canonical 비용 검증으로 개선이 없음을 증명한 경우에만 lift를 생략한다.
incumbent는 바꾸지 않는다. 개선 후보, replay, witness 부재·불일치는 기존 lift와 acceptance를 수행한다.
제거 순서 점수는 정확한 영향 범위만 무효화하며 네 기존 정책과 동률 기준을 유지한다.

공통 분석에서는 소비되지 않는 중간 native context 재구성, 빈 direct delta의 인덱스 생성,
함수 경계의 반복 topology 재구성을 줄인다. generated proof 재사용은 pruning 전 실제 root 의존성을
기준으로 하며, source/owner/action identity 변경은 계속 검증한다.

immutable realization과 support clause의 소유권 조회에는 identity 인덱스를 사용한다. 구조적으로
같아 보이는 외부 객체를 권한으로 인정하지 않으며 중복 개수 검사도 유지한다. canonical 문자열은
동일한 UTF-16 내용·hash·순서를 보존하면서 길이 prefix의 piece 수를 줄이고, 동일 clause 객체의
중복을 정렬 전에 제거한다. 서로 다른 객체의 구조적 중복 검사는 그대로 남긴다.

직접 proof의 read-set은 성공·실패 조회, generated support, primitive/alias/변경된 support까지
포함한다. read-set이 완전하고 변경 영향이 없는 SCC 구성원만 재검사를 생략하며, 알 수 없는
의존성과 최초 dirty SCC는 계속 보수적으로 처리한다. function-boundary session은 같은 호출의
round 사이에서만 공유하고 carrier 생성·취소 시 topology/options를 재구축한다.

worker-pool 및 joint-row DAG 탐색은 이미 완료된 부분을 재사용하되, 재방문 edge의 authority
검사는 계속 수행한다. 방송 가능 value version은 immutable structural context에서 인덱싱하며,
다른 value version과 PRIVATE_AGGREGATE 제약은 구분한다. audit는 동일 JSONL 내용을 stream으로
출력하고, 비활성 로그를 위한 진단 문자열 생성은 생략한다.

`CandidateRealizationReference`는 hash를 저장하기 위해 record에서 immutable final class로 바뀐다.
생성자·factory·accessor·equals/hash·비교·문자열은 회귀 테스트로 유지하지만, `Record` 상속과
`Class.isRecord()` 결과는 달라진다. 저장소 안에서는 이를 사용하는 reflection/deconstruction 경로가
발견되지 않았다. 외부에서 record 자체를 reflection하는 비공개 planner 연동에는 이 차이가 있다.

## 기각하거나 아직 채택하지 않은 방법

전체 CFG의 성공 출력만으로 다음 호출도 no-op이라고 간주하는 cache는 사용하지 않는다.
새 호출의 loop seed, entry binding, direct template 및 generation-base 상태가 다를 수 있기 때문이다.

direct 검사 구독 정보로 pending 집합만 줄인 최초 후보는 실제 선택 owner가 0.17%만 줄어 기각했다.
SCC가 pending owner 하나만 있어도 묶음 전체를 다시 검사했기 때문이다. SCC 내부 생략은
완전한 실제 read-set과 이전 direct 결과의 안정성을 증명한 owner에 한해서 적용했다.
변경·취소된 결과와 알 수 없는 의존성은 보수적으로 다시 검사한다. 진단 fixture의 선택 owner는
85,856개에서 71,439개로 줄었으며, fixed-point wave·변경 수는 같았다.

비용 worker-count memo 확대와 조건부 preparation의 추가 cache도 실제 중복 빈도가 낮아 반영하지 않았다.
최신 진단과 실패한 측정은 삭제하지 않고 보존한다.

## 재현 및 증거

실제 workload 실행 경로는 `scripts/fedplanner/run_LAN_docker.sh` 하나로 제한한다.
최종 3회 판정기는 다음 명령이다.

```sh
python3 scripts/fedplanner/evaluate_steplm_planning.py \
  --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/steplm-planning-20s-validation-20261007 \
  --stage-root /home/mchoi/steplm-planning-20s-docker-stage-20261007 \
  --run-id steplm-20s-final
```

최종 결과·검증 횟수·채택 여부는 동결 소스의 반복 검증 후 확정한다.

## 후속 병목 진단 (진행 중)

캐시 entry/weight 상한을 그대로 사용한 compile-only fixture에서 canonical text cache가
전체 2,719,318 조회 중 190,681번째(7.01%)에 weight 한도를 채웠다.
59,255개 entry에서 추정 retained weight가 67,108,806/67,108,864였고,
이후 2,265,287개 admission이 실패하고 새 admission은 0개였다.
이후 hit rate는 10.41%(포화 전 68.92%)였다. 이 수치는 실제 ML 학습 실행시간으로 사용하지 않는다.

상한 증가 없이 캐시 내용을 교체한다. 새 표현 하나의 전체 DAG를 빈 identity ledger 기준으로
계산하고, 기존 entry/weight 한도 안에 들어가는 경우에만 준비된 map들을 교체한다. 들어가지 않으면
기존 map identity와 weight를 보존한다. semantic authority와 후보/비용 테이블은 교체 대상이 아니며,
immutable 문자열 표현만 대상이다. 64MiB는 descriptor의 보수적 retained-weight 추정치이며,
교체 준비 중 old/new map이 잠시 함께 존재하므로 JVM peak heap의 엄밀한 상한은 아니다.

같은 진단 fixture에서 canonical cache 재계산은 약 232만 회에서 51만 회로 줄었다.
다만 실제 screen19는 21.97초로, 작업량 감소만으로 실행시간 목표가 달성됐다고 판단하지 않는다.
중앙 canonical/authority/StepLM 회귀 테스트 95개는 모두 통과했다.

후속 JFR(profile20)은 native proof의 dead-alternative pruning 51/994 sample,
DP known-zero 합산 46/994 sample을 보였다. pruning은 같은 graph를 유지하면서 list iterator와
중간 reverse-edge 배열을 줄인다. 조건부 DP는 연속된 zero factor의 길이를 보존하며, 실제
high/low raw 값이 고정된 뒤에만 남은 같은 연산을 생략한다. 임의의 high/low에서 zero 덧셈이
항상 idempotent라는 가정은 사용하지 않는다.

cost-surface 진단에서는 shape 조회 287,427회 중 후반 input-shape 계산 180,232회가 있었고,
109,900회는 결과를 사용하지 않는 연산이었다. 입력 조회 180,296회가 전체 간선 38,763,640개를
검사했다. 기존 analysis 인덱스를 사용하고 Row/Col 집계와 write만 입력 shape를 재귀 계산하도록
수정한다. abstract/captured/anchor/function/transient/CFG/multi-return shape authority 우선순위는
유지하며, 기존 재귀·스캔 구현을 독립 oracle로 보존한 테스트로 비교한다.

첫 최종 3회 실패 후 profile22에서는 조건부 solve가 이미 계산한 auxiliary 값을 버린 뒤 global
lift에서 다시 계산하는 경로가 55/931 CPU sample이었다. 위 witness 전달은 그 중복만 줄인다.
동률 auxiliary 선택이 이후 탐색에 미치는 영향을 피하기 위해 개선 후보에는 기존 lift를 유지한다.

relocation binder는 consumer의 정확한 identity와 placement별로 동일 action·target-pool 목록을
한 번 준비한다. equals 중복의 첫 authority 객체와 기존 canonical 정렬 순서를 유지한다.
canonical field 길이의 99.9%가 4096 미만인 진단 결과에 따라 길이·delimiter 문자열만 고정된
8192개 metadata 문자열로 공유한다. payload identity, UTF-16 내용, 기존 cache 상한은 유지한다.
공유 prefix는 identity ledger에 한 번 계산되므로 불필요한 allocation과 반복 weight 계산이 줄어든다.
이 단계의 개별 검증은 canonical 96개, conditional 89개, relocation 6개를 통과했다.

## 중간 게시 상태

사용자 요청에 따라 검증된 현재 개선분을 중간 게시한다. screen23은 22.990939초이며,
전체 20초 목표는 미달성이다. 중앙 step17은 183/183 PASS, 이후 조건부 quotient/singleton 복원
테스트를 포함한 별도 집중 검증은 90/90 PASS다. 최신 main 통합 검증을 이어서 기록한다.

## 최신 main 통합 중간 게시 검증

성능 구현은 `09588f7f4d`로 보존한 뒤 `0b6dc23522186bf8d67bfbcfb1bb82c905abf4e2`를 통합했다.
압축된 DP child의 내부 좌표 증가에 저장 좌표 차이를 적용해 upstream의 dense 순회 최적화와
결합했다. 두 개의 동일 realization/broadcast 인덱스는 하나로 합치고 양쪽 authority 회귀를 유지했다.
Native 테스트 2개의 제거된 내부 메서드 reflection 오류는 retained index의 cold-scan/갱신 검증으로
옮겼다. 압축된 비연속 내부 좌표와 두 번째 축의 rollover도 독립 dense oracle로 추가 확인했다.

통합 Java 71개 클래스 657건 중 **656 PASS / 기존 제외 1 / 실패·오류 0**, Python78 PASS,
package/diff check PASS, 독립 병합 검토 CLEAR다. Docker11개 소형 케이스가 모두 통과했다.
StepLM의5계수·선택[3,1,5]가 CP/FED에서 정확히 같고 audit/conversion 위반은 없다.

이번 StepLM compile은 **17.911837초**, 학습은1.096초였다. 다만 upstream0b6dc23522는
StepLM 내부의 `lm` 호출을 `lmCG`로 바꾼다. 이 실행의 비용65701.88346157457을 이전
lm 기준37040.115993804146과 직접 비교하지 않는다. 이전 실패 기록도 그대로 보존한다.
같은 builtin과 환경을 고정한 fresh JVM3회 검증이 남아 있으므로20초 목표 완료를 선언하지 않는다.
자세한 SHA·source/class inventory·검증 결과는
[`publication-validation.json`](experiments/steplm-planning-20s-20261007/publication-validation.json)에 있다.

## 중간 게시 후 원래 workload 재검증 및 추가 할당 제거

중간 개선본은 `f298378925c2aebb8facbc5a363b277f5d557a74`로 origin/main에 게시했다.
원래 `lm` builtin을 별도 class snapshot에 고정한 fresh JVM 3회(`legacy-final2`)는
20.870906 / 19.681566 / 19.244135초다. 첫 실행이 20초를 넘으므로 최종 판정은 **FAIL**이다.
세 실행 모두 비용 `37040.115993804146`, 모델·선택·audit·artifact 검증은 통과했다.

현재 main의 `lmCG` workload도 별도로 비교한다. 최적화 전 origin `0b6dc23522`의 Java
1,645개가 보존된 빌드의 source와 일치함을 확인하고 정확한 builtin 두 파일을 적용해 실행했다.
실제 Docker 기준 실행 `steplm-20s-origin-cg-26`은 전체 compile 91.989792초, 비용
`65701.88346157457`로 성공했다. 원래 lm 비용 기준을 높이는 근거로 사용하지 않는다.
완료에는 원래 lm과 현재 lmCG 각각 동일 workload 기준의 fresh JVM 3회 통과가 필요하다.

평가기에는 전체 builtin DML의 파일명·내용 fingerprint와 frozen class inventory의 실제 재hash
검사를 추가했다. baseline/candidate Java bytecode가 서로 다른 것은 허용하지만, 각 실행은
자신의 manifest와 실제 class inventory가 일치해야 한다. 비용 상한은 정확성·환경 검증을
통과한 실제 baseline receipt에서만 가져오며 임의의 상향 수치를 받지 않는다. 관련 Python
회귀 16개가 통과했다.

profile27에서 후보 rule key 비교의 ListItr 할당 251.2MB, DP boundary RHS 임시 비용 객체
246.3MB가 sample weight로 관측됐다. rule key는 같은 immutable ordered list를 인덱스로
비교하고, DP는 기존 high/low/tie 덧셈 순서를 유지하며 primitive 필드를 직접 전달한다.
audit shortHash의 hex 출력도 String.format 대신 같은 소문자 16자 배열로 만든다.
shortHash 전체의 348.7MB는 getBytes/digest 호출 경로이며 hex 변경으로 전부 없어졌다는
뜻은 아니다. 구현별 wall-time 개선율도 따로 주장하지 않는다.

변경 후 Java 집중 회귀 105/105, package, 독립 검토 CLEAR다. 실제 legacy screen28은
20.989561초로 정확성 PASS / 시간 목표 미달이다. 원래 record equality와 비용 덧셈을
독립 oracle로 비교하고 raw low 값, signed zero, INF/null/overflow 우선순위, hash 충돌,
잘못된 UTF-16 및 locale를 검증했다. 추가 성능 조사와 최종 반복 검증을 계속한다.

## support 좌표 반복 계산과 identity map 재확장 제거

profile27의 788개 CPU sample 중 25개는 selective support의 count/fill 두 pass에서
동일 quotient 좌표를 나눗셈으로 다시 decode하는 경로였다. 각 child scope 길이만큼의
scratch를 사용하는 cursor로 바꾸고, 같은 ascending row 순서를 증분 계산한다.
두 번째 pass 전에 초기 representative의 실제 storage 좌표로 reset한다. 기존의
`finite rows * 2 > cells` 조건이 확정되면 count를 종료한다. 이 조건은 support-index
사용 여부만 결정하며 factor 또는 후보를 제거하지 않는다. range/overflow certificate,
논리 scope/cell preflight, support 배열 내용과 backtrace는 유지한다.

별도로 정확한 entry 수를 이미 알고 있는 identity map 9곳을 그 크기로 초기화한다.
반복 resize/rehash를 줄이는 변경이며 identity equality와 명시적 정렬 순서는 유지한다.
선택 후보·cache/resource cap을 늘리지 않는다. 작은 map의 내부 순서는 달라질 수 있으나
해당 소비 경로는 lookup/membership 또는 명시적 정렬만 사용함을 독립 검토했다.

압축된 비연속 storage map, 여러 축의 rollover, singleton·empty scope, selective/omitted
relation을 기존 dense 계산과 대조한 isolated 회귀 51/51 PASS와 독립 실제 diff 검토
CLEAR를 확보했다. 중앙 검증과 두 workload의 동결 3회 측정을 이어서 수행한다.

## 두 workload 동결 반복 판정 — 시간 목표 미달

step19 중앙 171건 중 170 PASS / 기존 제외 1, package PASS 후 소스·class를 동결했다.
원래 lm(`legacy-final3`)은 22.836013 / 21.851988 / 18.783427초, 현재 lmCG(`cg-final2`)는
21.698392 / 19.212769 / 19.255013초다. 두 평가기 모두 **시간 조건만 FAIL**이며, 비용·모델·
선택·builtin·resource·각 실행 artifact manifest 및 batch source 불변 조건은 모두 PASS다.
단일 20초 미만 실행을 선택해서 목표 달성으로 보고하지 않는다. 실패 평가 원문은 결과 root의
`steplm-20s-legacy-final3-evaluation.json`, `steplm-20s-cg-final2-evaluation.json`에 보존한다.

후속 profile29는 983 CPU sample이다. support merge 전체 비중은 profile27의 55/788에서
36/983으로 줄었으나, 이는 표본 관측이며 독립 wall-time 개선율이 아니다. 공통 분석과 조건부
solve가 남아 있다. profile29에서는 실행 중 GC pause 총량이 작아 heap/GC 설정 변경 근거로
사용하지 않는다. 기존 Docker/JVM 조건은 유지한다.

큰 revisioned CandidateFactIndex 및 LogicalBoundary Session의 호출 간 공유는 채택하지
않았다. 동적 owner/slot/order/anchor/carrier 검증 경계를 넓혀야 하고 현재 근거에 비해 변경이
크다. 대신 signature identity front의 포화 이후 재사용 가능성을 좁은 범위에서 검증한다.

## signature identity front 포화 후 반복 hash 해소

StepLM compile-only fixture에서 identity front는 실제로 65,536개 한도에 도달했다.
회전만 제거한 isolated control은 capacity 상태 조회 6,668,400회, admission 거절
1,714,667회, identity hit 6,731,340회, structural hit 1,773,560회였다. 회전 후보는
identity front를 3번 교체해 identity hit 8,254,290회, structural hit 255,426회다.
비싼 structural fallback이 1,518,134회(85.6%) 줄었고 canonical miss는 양쪽 모두
6,643회다. 전체 호출 수 8,511,543 대 8,516,359 차이는 내부 재귀 조회 경로 차이이며
동일 호출 수를 주장하지 않는다. 양쪽 같은 StepLM 컴파일 회귀가 통과했다. 이 계측은
작업량 근거이며 host 시간을 ML 성능으로 사용하지 않는다.

변경은 한도에 도달한 private active identity front를 다음 유효 admission에서 clear하고
현재 key를 넣는 것이다. 실제 문자열을 가진 structural cache와 64M character 예산,
analysis 밖 weak cache, structural arena·candidate-reference authority는 유지한다.
기존 identity hit는 rotation을 일으키지 않으며 예산을 넘는 serialization도 front를 지우지
않는다. 70,000개 동등 alias의 동일 String instance, 두 admission 경로, bound와 scope
cleanup 회귀 6/6 PASS 및 독립 검토 CLEAR다. 원자료는
`target/planning-evidence/identity-front-telemetry-01/{control,new}.log`에 있다.

기존 hot alias가 front에서 빠져 다시 준비될 수 있으므로 작업량 감소만으로 채택 성능을
확정하지 않는다. 중앙 검증 후 같은 builtin·환경의 새 official Docker 3회씩을 판정한다.

후속 중앙 step20은 117건 중 **116 PASS / 기존 제외 1 / 실패·오류 0**, package/diff check
PASS다. 후속 구현 커밋 `4736b23e01`을 동결한 실제 Docker `legacy-final4`는
20.507247 / 21.926934 / 21.471499초, `cg-final3`는 19.225814 / 21.122758 /
18.991695초다. 비용·모델·선택·audit·builtin·resource·artifact·source 검증은 6회 모두
PASS지만 두 평가기는 시간 조건으로 FAIL이다. 이 결과까지 보존해 중간 게시하고,
변경된 owner와 무관한 경계 재검사의 비용을 추가 조사한다. 20초 목표는 미완료다.
