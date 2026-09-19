# G009 두 워크스페이스 비판적 평가 및 선택 병합

작성일: 2026-09-19. 통합 위치: `/home/mchoi/systemds-g009-integration`,
브랜치: `integration/g009-baseline-20260919`.

## 결론과 병합 범위

correctness의 채택 재설계와 독립 회귀·oracle·계측·실행 도구를 모두 보존하고,
unified의 timeout 없는 snapshot evaluator 및 DAG pruning/grounding fast path를 채택한다.
DAG의 효율성 판정은 **잠정적(bounded/provisional)**이며 Docker 규칙을 충족한 공식 성능 PASS가 아니다.
unified 미커밋 overlay cache는 개선 근거가 부족하고 부분 비교가 악화되어 제외한다.
원본 두 워크스페이스와 미커밋 실험은 그대로 보존한다.

두 작업은 동등한 대안 구현이 아니다. unified의 `6392a7ebe7`이 correctness의 작업 트리를
이미 통합한 뒤 성능 실험을 이어간 관계다. 따라서 correctness 브랜치 HEAD만 merge하면
미커밋 재설계를 놓치고, 작업 트리를 통째로 덮어쓰면 후속 DAG 개선을 잃는다.
실제 파일 내용과 커밋 관계를 대조한 뒤 기존 integration의 `35d1f49507`을 채택본
`71c598b398`까지 fast-forward했다. 별도 중복 구현이나 캐시 계층을 추가하지 않았다.

원본 보고서 두 개도 이 디렉터리에 보존했다. 원본의 '현재', '미커밋', '통합 미완료'는
각 보고서 작성 당시 원본 워크스페이스를 뜻한다. 현재 통합 판정은 이 문서를 따른다.

## 선택표

| 대상 | 판정 | 근거와 적용 경계 |
|---|---|---|
| correctness 결함 수정 및 독립 oracle/회귀 | 모두 보존 | 통합 공통 이력과 최초 통합 커밋에 포함. 단순 성능 실험과 교환할 대상이 아니다. |
| P0–P4 streaming, 중복 제거, bounded memo, dirty closure, owner-safe sharing | 채택 | 후보 의미를 유지하는 계산·표현 변경. 현재 파일이 최초 통합본과 동일함을 확인. |
| R0/R1, R2 topology/support memo·revision reuse, R3 lazy receipt/final sharing | 구현된 범위 채택 | broad gate와 GLM 완료 artifact 확인. global delta-SCC와 selector-native relation은 구현되지 않음. |
| timeout 없는 snapshot evaluator | 독립 채택 | 회귀 동일성 검증 도구. budget=0의 성공은 성능 목표 달성을 뜻하지 않음. |
| DAG pruning/grounding fast path | 조건부 성능 채택 | DAG에서 SCC 계산을 제거하고 cycle은 기존 알고리즘으로 처리. LM snapshot 및 GLM fingerprint 일치 기록. 공식 workload 성능 인증은 별도. |
| overlay cache 미커밋 실험 | 제외·원본 보존 | 6쌍 중앙값 9,587→10,573ms, 약 10.28% 악화. 12쌍·전체 gate·GLM 비교 미완료. |
| broadcast index, no-op pruning, singleton SCC, dependency sharing, state interning, early-empty | 기각 유지 | 반복 시간 개선을 지지하지 않음. 객체/edge 감소 자체는 채택 기준이 아님. |
| public memo revision 이전, realization index 두 변형 | 기각 유지 | 실제 시간 악화·reuse 부족. 이미 되돌린 실험을 병합하지 않음. |
| 두 보고서 및 실험 실패 기록 | 독립 보존 | 성공·실패의 범위와 후속 실험 중복 방지에 필요. |

## 비판적 평가

### 1. 정확성 증거는 강한 유한 회귀이나 전역 증명은 아니다

correctness broad artifact의 173 discovered / 169 active pass / 4 skip / 0 failure / 0 error와
GLM 정상 완료는 원본 artifact에서 확인했다. 반례 기반 회귀와 독립 oracle는 단순 fingerprint보다
강한 근거다. 그러나 21개 proof obligation과 48개 rule-family 전체의 일반 증명은 여전히 OPEN이다.
PUBLIC-only skip을 pass에 더하거나 모든 privacy 조합에 대한 증거로 사용할 수 없다.

snapshot evaluator는 graph/rule/emission/canonical receipt를 직렬화한다.
동일성 검증은 이 관측 범위에 한정된다. 실제 DP/Exact 선택 비용, runtime 실행 결과와
모든 지원 프로그램의 plan 완전성을 이 evaluator 하나가 검증하는 것은 아니다.
correctness 보고서의 'GLM diagnostic E2E 완료'도 `buildAnalysis()`와 constraint 검사까지의
완료를 뜻하며, 실제 GLM 학습·DP selection·runtime 결과 정확성 E2E 완료를 뜻하지 않는다.

### 2. 작업량 감소와 전체 시간 개선을 분리해야 한다

correctness 전체 redesign의 완료 baseline 비교는 evaluator 989.132→837.223초
(15.36% 감소), wall 16:36.51→14:07.82(14.92% 감소)다.
10.14배 proof-query 감소는 immediate predecessor와의 비교이며, 같은 구간의 wall 감소는
3.09%다. unified 보고서 5.2의 '최초 정상 완료 대비 10.14배' 표현은 비교 기준이 부정확하다.
원본 보고서는 변경하지 않고 이 정정을 병합 판단에 적용한다.
113분 중단 실행은 완료 baseline이 아니므로 10배 속도 향상 입증에 사용할 수 없다.

### 3. DAG fast path는 현재 가장 합리적인 채택이나 통계적 확증은 제한적이다

unified contemporaneous GLM pair는 794.699→716.592초(9.83% 감소)이며
SCC edge scan 872,529,353→0이라는 구조적 근거와 일치한다. historical 837.223초와
새 결과를 직접 비교한 14.4%를 후속 변경의 효과로 주장하지 않는다.
LM 12쌍 중앙값은 10,304.5→9,733ms(5.55% 감소), current 승리는 7/12다.
두 집단 중앙값의 차이는 paired difference의 중앙값과 다르며, 이 승률과 단일 GLM pair만으로
다양한 workload에서 안정적인 개선을 확증할 수 없다. 따라서 코드 채택과 공식 성능 인증을 분리한다.
RSS는 사실상 그대로이며 GLM 약 29GiB 수준의 peak RSS 문제도 해결되지 않았다.

### 4. 높은 재사용률만으로 캐시를 채택할 수 없다

overlay observer의 높은 중복률은 가능한 공유의 상한일 뿐 lookup·key·retention·eviction 비용을
상쇄한다는 증거가 아니다. overlay cache는 85% 수준 hit에도 완료된 부분 표본에서 느려졌다.
부분 표본만으로 보편적인 열화를 단정하지는 않지만, 채택하려면 필요한 개선 증거가 없다.
의미적으로 독립적인 기능이라도 비용만 늘리는 실험까지 무조건 합치지는 않는다.

### 5. 남아 있는 핵심 위험

- revision invalidation에서 negative/dead dependency footprint를 누락하면 stale proof를 재사용할 수 있다.
- structural handle과 memo의 authority/provenance/owner 경계를 보존해야 한다.
- DAG 판별과 dependency-first 순서가 잘못되면 grounding 결과가 달라질 수 있다. cyclic 경로를 유지한다.
- 다단계 dead cascade/diamond DAG에 대한 직접 전용 회귀는 부족하며 기존 composition/snapshot의
  간접 검증에 의존한다. 향후 알고리즘 확장 시 보강할 테스트 경계다.
- branch manifest는 선언한 소스의 구조 변화 감지 장치이며 predicate의 필요충분성 증명은 아니다.
- host 진단과 Docker workload 성능을 혼합하지 않는다. 공식 성능 비교는 `run_LAN_docker.sh`만 사용한다.

## 병합 무결성

- correctness의 modified/untracked 22개 항목을 최초 통합 `6392a7ebe7`과 비교했다.
  차이는 종합 보고서 미포함과 construction analysis 첫 네 줄의 trailing whitespace뿐이다.
  독립적인 production/test/script 변경 누락은 없었다.
- correctness의 기존 tracked patch SHA-256:
  `487b31ef7a2cda51f66b0b7ff3fe7e6fe472b018255ebe30bbf635ffaaa4c505`.
  이 해시는 당시 untracked 신규 파일을 포함하지 않는다. 신규 metrics/test/script의 포함 여부는
  최초 통합 커밋과의 개별 blob 비교로 별도 확인했다.
- unified의 보존 대상 미커밋 patch SHA-256:
  `7be95ed72cc64e0bd2490ac3d7cc81a776df13553c5d7d53f1d3e5c093907341`.
- integration production/test/script/resource는 채택본 `71c598b398`과 일치한다.
  미채택 overlay cache는 integration에 들어오지 않았다.
- 종료 전 원본 두 tracked patch의 SHA-256이 위 값과 동일함을 재확인했다.
  복사한 두 보고서도 원본과 byte 동일하다.

## 이번 통합의 fresh 검증

`run_g009_planning_correctness.sh`에 선언된 26개 bounded 클래스를
integration의 독립 `target/`에서 직렬 실행해 Maven exit 0을 확인했다.
fresh XML 기준 **168 discovered / 164 active pass / 4 skip / 0 failure / 0 error**다.
branch inventory, DAG/cyclic continuity, fixed point, owner/canonicalization, streaming,
independent plan oracle 및 runtime REV 회귀가 이 범위에 포함된다.
원본 보고서의 173개 gate와 선택 범위가 같다고 주장하지 않는다.
`mvn -q -DskipTests package`도 exit 0이다. 빌드의 기존 Python gateway smoke가 출력한
`failed startup` 문자열은 최종 Maven 실패가 아니며 명령 exit로 판정했다.
`G009PlanningPerformanceEvaluatorTest`를 two-source/local-mix/LM 각각 실행해 모두 exit 0을
확인했다. expected는 unified의 `build/g009-unified/acyclic-fastpath-smoke-20260919/`에
보존된 각 snapshot이다. baseline 재생성 없이 byte equality를 검사했다.
이 실행의 시간 출력은 성능 비교나 Docker qualification에 사용하지 않는다.

| snapshot | SHA-256 |
|---|---|
| two-source | `4053fcd5f281d7ca37c856363bfd4d917f37700326b70fa6135e896cd07ce5c0` |
| local-mix | `79851b54863f3e4d54e6e1f65d34be51d84aa1a443c99147f23d1cf439898db5` |
| LM | `9000bff430f7fde79b901e5af6414eb4e537c1806af73056234f05830d63551a` |

`git diff --check`, `bash -n scripts/fedplanner/run_g009_planning_correctness.sh`,
`git diff --exit-code 71c598b398 -- src scripts`도 통과했다.
공식 Docker 성능·180초 목표·전역 증명을 이번 병합 완료와 혼동하지 않는다.

bounded gate 재현 명령:

```bash
cd /home/mchoi/systemds-g009-integration
bounded_selector=$(sed -n '/^BOUNDED_TESTS=(/,/^)/p' scripts/fedplanner/run_g009_planning_correctness.sh | sed '1d;$d' | tr -d '\t' | paste -sd, -)
mvn -q -Dtest="$bounded_selector" -Dtest-parallel=none -Dtest-threadCount=1 -Dtest-perCoreThreadCount=false -Dtest-forkCount=1 -Drerun.failing.tests.count=0 test
mvn -q -DskipTests package
```

bounded XML 사본은 `build/g009-merge-review-20260919/bounded-xml/`에 있다.
집계는 위 selector의 26개 클래스만 대상으로 하며, 이전 실행이나 package smoke의 XML을
전체 glob 합산하지 않았다.

핵심 코드 근거: `NativePlacementContinuity.java:527`의 DAG/cycle 분기,
`:668`의 DAG pruning, `:707`의 grounding, `:873`의 cycle 감지와 `:893`의 completion order.
`NativePlacementContinuityTest.java:439`는 dead sibling revision footprint,
`:653`은 dependency-first chain, `:673`은 dead dependency 제거 회귀다.
파일은 모두 `src/{main,test}/java/org/apache/sysds/hops/fedplanner/placement/` 아래 있다.

## 후속 우선순위

1. 현재 채택 코드를 다음 정확성·성능 실험의 단일 기준으로 사용한다.
2. 성능 인증이 필요하면 동일 Docker 조건의 fresh-JVM 교차 반복과 분산·RSS를 먼저 확보한다.
3. 추가 최적화는 phase별 실제 시간과 allocation을 측정해 traversal/topology lookup/downstream
   materialization 중 지배 비용을 확인한 뒤 진행한다. reuse 비율만으로 캐시를 추가하지 않는다.
4. 전역 proof closure와 full delta-SCC/selector-native relation은 별도 설계·검증 과제로 유지한다.
