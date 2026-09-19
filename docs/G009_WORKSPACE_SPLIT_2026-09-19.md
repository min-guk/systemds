# G009 공통 기준 및 작업 분리

## 2026-09-19 후속 통합 상태

아래 본문은 최초 분리 당시 기록이다. 이후 correctness 채택 재설계와 unified의 DAG fast path를
이 문서의 integration 워크스페이스에 통합했다. 현재 코드 기준은 `71c598b398`이며,
당시 미검증 memo에 관한 문장을 이후 검증된 support memo 전체에 적용하지 않는다.
미채택 overlay cache는 원본 unified 작업 트리에 보존하고 통합에서 제외했다.
현재 선택 근거와 검증 범위는 [두 워크스페이스 병합 평가](G009_MERGE_REVIEW_2026-09-19.md)를 따른다.

## 문제와 확인 결과

두 사용자 세션이 같은 `/home/mchoi/systemds-lm-worker-count-fix` 디렉터리를 사용했다.
2026-09-19 확인 시 로컬 HEAD와 원격 main은 모두 `451acabf038906c4219ede8dab7eecffcabc7eb2`였다.
따라서 서로 다른 소스의 merge conflict가 아니라 공유 파일 및 `target/` 경합 문제다.
기존 디렉터리에는 이후 seed index, 미검증 proof memo, 문서 변경이 남았다.

## 통합 범위

통합 worktree: `/home/mchoi/systemds-g009-integration`
브랜치: `integration/g009-baseline-20260919`

- 검증된 호출별 durable-anchor FType index와 해당 branch manifest를 통합한다.
- 진행 문서의 잘못된 원인 설명과 승인 판정을 정정한다.
- proof-query memo는 성능 실험으로 분리하며 main에 포함하지 않는다.
- build/, .omx* 및 진단 산출물은 커밋하지 않는다.
- 기존 공유 디렉터리의 변경은 삭제하거나 reset하지 않는다. 두 세션 모두 이후 작업은 새 worktree에서 한다.

## 담당 분리

| 항목 | 완전성·정확성 lane | 성능 lane |
|---|---|---|
| worktree | `/home/mchoi/systemds-g009-correctness` | `/home/mchoi/systemds-g009-performance` |
| branch | `audit/g009-completeness` | `perf/g009-planning` |
| 우선 소유 | 독립 oracle, 회귀 테스트, obligation/rule inventory | profiling, seed/proof indexing, 캐시·탐색 구현 |
| 핵심 질문 | 모든 합법 plan을 만들고 불법 plan을 배제하는가? | 같은 plan·authority 집합을 더 적은 시간·메모리로 만드는가? |
| 합격 조건 | 독립 expected 대비 missing=0 및 extra=0, 선언 범위와 미증명 축 명시 | 동일 fixture에서 전후 plan/receipt/proof-set 동등성 및 측정된 성능 개선 |

현재 세션은 성능 lane, 다른 세션은 완전성 lane을 맡는 배치를 제안한다. 다른 세션이 이 문서를 읽고
소유 범위를 확인하기 전에는 두 세션이 동시에 공유 production 파일을 수정하지 않는다.
완전성 lane이 production 결함을 찾으면 먼저 반례 테스트와 영향을 기록하고 통합 담당이 파일별 수정 소유를 조정한다.
각 lane은 독립 target/을 사용한다. main 반영은 통합 worktree에서 직렬 수행하고 force push하지 않는다.

## 검증과 잔여 이슈

seed index 이후 기존 23-class gate는 134 discovered / 130 active pass / 4 PUBLIC skips /
0 failures / 0 errors였다. 이는 bounded 증거이며 전역 증명이 아니다.
업로드 fixture는 seed index에서 180770 ms, memo 실험에서 180946 ms 뒤 exit 124였다.
두 timeout은 통과나 성능 개선으로 계산하지 않는다. memo의 전후 proof-set 동등성은 미검증이다.
G009의 전역 보존, 전체 rule/runtime 축, 재귀 깊이 및 Docker qualification은 OPEN이다.

## 회귀 위험과 다음 순서

1. 통합 worktree에서 새 빌드 및 23-class 회귀를 통과시킨 뒤 main에 fast-forward push한다.
2. 동일 통합 커밋에서 두 lane을 생성한다. 기존 공유 worktree는 증거 보관용으로 둔다.
3. 성능 lane은 memo의 hit rate·보유 메모리부터 측정한다. 단순 캐시 추가를 성공으로 간주하지 않는다.
4. 완전성 lane은 독립 합법성 정의와 지원 범위를 고정하고 작은 조합의 exhaustive oracle 및 반례를 확장한다.
5. 합법 후보 cap, sampling, privacy 완화, runtime fallback으로 timeout을 감추지 않는다.
6. 정확성 수정은 양 lane의 새 공통 기준으로 먼저 통합하고 그 위에서 성능을 다시 비교한다.

성능 비교는 최종 선택 비용만 아니라 전체 plan/receipt authority 보존을 검사한다.
compiler 진단과 실제 workload 성능은 구분하며 workload 비교에는 저장소의 Docker 실행 규칙을 적용한다.

## 통합 worktree의 fresh 검증

2026-09-19 독립 target/에서 Maven test를 실행해 23개 클래스, 134 discovered / 130 active pass /
4 PUBLIC skips / 0 failures / 0 errors, BUILD SUCCESS를 확인했다.
명령은 `mvn -DskipRat -DforkCount=1 -DreuseForks=true -Dparallel=none -DthreadCount=1 -Dtest=<23 classes> test`이며
class 목록과 로그는 통합 worktree의 `build/reconciliation/`에 보관한다.
NativePlacementContinuity는 원격 기준과 동일한 `220712b8...`, Builder는 검증된 `4e74a6ca...`다.
미검증 memo는 통합 소스에 없다.
