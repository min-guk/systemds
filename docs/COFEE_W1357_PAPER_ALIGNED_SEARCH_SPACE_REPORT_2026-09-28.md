# W1357 search space: 논문 개념과 구현의 대응

## 범위와 현재 단계

승인된 2026-09-28 계획을 별도 worktree
`/home/mchoi/w1357-paper-aligned-refactor`에서 구현한다. 원본 엔진과 평가
저장소의 진행 중 변경은 보존한다. P0/P1/P2 책임 추출과 기준 동등성
검증을 완료했다. P3/P4 및 Docker 비교는 후속 단계다. 외부 correctness
최종 commit `cfab6c8258`의 자료를 받아 C0 기준을 새로 고정한다.

## 세 계층과 실제 진입점

| 논문 개념 | 코드 진입점 | 소유하는 사실 또는 상태 |
| --- | --- | --- |
| L1 프로그램 사실 | `PlacementProgramFacts.analyze`, `compiledOccurrenceKey`, `valueVersion` | compiler occurrence, CFG/shape 정밀화, 원본 shape snapshot, 원래 value identity |
| L2 지역 실행 대안 | `PlacementCandidateGenerator.buildNode` | ordered input domain을 평가한 새 rule/emission; prepared Oracle와 기존 출력 계약 |
| L3 실행 가능한 관계 | `PlacementRelationClosure.close` | 현재 nodes, ruleKeys, ruleFacts, transientBindings, relocations의 유일한 쓰기 소유자 |
| 경계 관계 | `closeBoundaryStructure`, `closeFunctionBoundaryRelations` | 모든 reaching writer, 후보에 의존하는 function/anchor/cardinality 갱신 |
| privacy 적합성 | `closePrivacyRelations`, `closePrivacyDomains` | 현재 관계에 의존하는 effective privacy와 재처리 |
| 배치·지원 관계 수렴 | `closePlacementAndFeasibility`, `closePhysicalDependencies`, `closeDirectComponents` | 현재 source/action 증명, dirty SCC 및 삭제/추가 전파 |
| support clause의 적합성 | `PlacementSupportRelations.pruneUnsupportedRealizations`, `projectCandidateNodesToExecutableStates` | OR-of-AND clause와 executable node projection; 현재 snapshot을 직접 덮어쓰지 않음 |
| 게시 | `PlacementRelationClosure.publish` | 동등한 graph-owned state 정규화, factorization, authority 검증, analysis 구성 |
| 관측 | `PlacementClosureDiagnostics`, 기존 `SearchSpaceMetrics` | recurrence/export 차이와 문자열; 의미 상태의 정본이 아님 |

`NeutralPlacementGraphBuilder`는 공개 생성자/API, facts→closure 조립과
analysis scope의 시작·종료를 유지한다. Oracle tuple 처리, CFG 정밀화,
support 제거, 상세 진단 문자열의 본체는 각 소유자로 이동했다.

이는 책임 계층이다. 모든 분석이 프로그램당 한 번만 실행된다는 뜻은
아니다. L1의 CFG/shape 정밀화와 L3의 함수/물리/privacy 수렴은 별개다.
L3는 입력 domain이 바뀌면 같은 L2 generator를 다시 사용한다.

```mermaid
flowchart TD
  P[Compiled program] --> B[공개 builder와 analysis scope]
  B --> F[L1 ProgramFacts: CFG와 원본 shape]
  F --> S[L2 generator로 초기 지역 대안 생성]
  S --> R[L3 경계 구조와 함수 관계]
  R --> V[L3 privacy와 필요한 재처리]
  V --> C[L3 physical·support·action 수렴]
  C -->|입력 domain 변경| G[L2 affected alternatives 재생성]
  G --> C
  C -->|여섯 성분 안정| U[publish: 정규화와 authority 검증]
  U --> A[PlacementAnalysis]
  A --> D[기존 planner의 선택과 적용]
```

## 실제 순서를 반영한 알고리즘

아래는 P2의 실제 phase 순서다. 원래 계획의 상위 transfer 초안으로
실행 순서를 바꾸지 않았다. 각 단계 내부의 기존 반복·guard도 유지한다.

```text
BuildSearchSpace(program):
    begin analysis scope
    facts := PlacementProgramFacts.analyze(program)
    seedLocalAlternatives(facts)
    closeBoundaryStructure(program)
    closeFunctionBoundaryRelations()
    closePrivacyRelations()
    closePlacementAndFeasibility():
        repeat:
            previous := (nodes, ruleKeys, ruleFacts, transientBindings,
                         relocations, pendingPhysicalRebuildOrdinals)
            refresh function boundaries and rebuild affected physical alternatives
            bind candidate, derived-output and relocation authority
            close CFG/transient support and apply current privacy
            prune missing-source clauses; project executable nodes
            discover and bind replacement actions
            prune expired-action clauses; project executable nodes
            update pending affected owners and action authority
        until all six components equal previous
    result := publish()
    finally release closure state and end analysis scope
    return result
```

후보 수나 pending queue 길이만으로 안정성을 판정하지 않는다.
새 replacement action을 결합한 뒤 이전 action의 만료 clause를 제거한다.
유한한 후보 수만으로 전체 transfer의 단조성이나 최소 고정점을 주장하지
않는다. publication에서는 후보 생성이나 관계 복구를 호출하지 않는다.

## 후보 계층을 통과하는 작은 예제

기존 B-21 fixture의 핵심은 함수 안의 `Y=rowSums(X)`와 함수 경계다.
동일한 값이라도 호출 위치와 emitted occurrence identity를 구별한다.

1. **Occurrence / value:** L1은 `rowSums`와 `TWrite Y`의 compiler occurrence,
   callsite, 원래 value version을 제공한다. 함수 확장 시 후보에 의존하는
   synthetic boundary는 L3에서 기존 순서로 생성한다.
2. **Rule / ordered inputs:** L2는 입력 위치별 native/local 상태 조합으로
   `CandidateRuleKey`를 만들고 prepared Oracle에서 capability/shape 근거를
   얻는다. 알려진 bottom domain을 local 입력으로 바꾸지 않는다.
3. **Emission:** 연산의 CP/FED 실행 위치와 LOUT/FOUT 결과 위치를 따로
   표현한다. TRead/TWrite에는 기존 CP/LOUT 또는 FED/FOUT 계약을 적용한다.
4. **Realization / support:** L3는 해당 실행을 지탱하는 exact source,
   worker/range, placement와 action을 연결한다. 두 가능한 joint witness가
   `(X@r1 AND Y@s1) OR (X@r2 AND Y@s2)`라면 두 clause를 그대로 유지한다.
   입력별 집합의 Cartesian product로 바꾸지 않는다.
5. **Publication / selection:** 지원이 닫힌 관계를 정규화하고 소유권을
   검증한 뒤 기존 DP/Exact가 전체 선택을 수행한다. 같은 output label만으로
   source/clause/action을 합치지 않는다.

고정 기준 B-21은 P와 E 각각 192 raw proofs / 36 physical plans다. 두 값과
양방향 physical identity, multiplicity를 따로 검증하며 160/30으로 낮추지
않는다. 리팩터링 후 전체 비교 결과는 최종 검증 표에 기록한다.

## 수명과 무효화 계약

- Compiler 사실은 읽기 전용이다. 초기 `SinglePartitionFacts`와 현재
  candidate 관계에서 refinement된 cardinality를 구별한다.
- generation base, CFG replay baseline, loop seed ledger, committed proof
  inventory, native revision은 closure 안에서 기존 수명을 유지한다.
- Owner의 node/index/key/fact commit 뒤 proof inventory를 무효화한다.
  query memo reset과 source revision commit은 서로 대체하지 않는다.
- Direct support의 edge 추가/삭제가 SCC 구조를 바꾸면 schedule을 다시
  만들면서 미처리 작업을 보존한다. P2에서 이 알고리즘은 변경하지 않는다.
- metrics OFF에서는 상세 비교와 문자열을 만들지 않는다. 진단 snapshot을
  실행 상태로 사용하지 않는다.
- Builder 재사용과 예외 종료 모두 `finally`에서 closure의 현재 상태와
  proof context를 해제한다. 게시된 불변 객체를 clear하지 않는다.

## 검증 추적

전체 명령·source manifest·XML·JAR·NDJSON은
`/home/mchoi/w1357-diagnostics/paper-refactor-20260928/evidence/`에 있다.
P0/P1 상세는 `BASELINE_AND_P1_SUMMARY.md`를 참조한다.

| 항목 | 현재 확인 결과 |
| --- | --- |
| 고정 기준 | `aaa574ebb5`; 원본 HEAD와 correctness patch를 별도 기록 |
| P1 | `a5e4996cf6`; 역이름변환 시 production 소스가 바이트 단위로 원본과 일치 |
| 기준/P1 clean 대상 회귀 | 각각 170건, 동일한 기존 실패 5, 오류 0, 기존 skip 4 |
| 기준 C0 원래 세 메서드 | 3/3 통과; 관련 회귀 실패로 C0 전체는 미충족 |
| 기준 protected P/E | 216 proofs / 56 physical; 양방향 집합 및 multiplicity 일치 |
| B-01 기준→P1 | P와 E 각각 차이 0; proof multiplicity 및 P audit 일치 |
| P2 | clean 365건 결과가 기준과 동일; protected P/E 216/56 전후 집합·multiplicity 동일 |
| P3/P4 | C0 선행조건에 의해 미실행 |
| Docker 성능 비교 | 미실행; P5 최종 근거로 남아 있음. 속도 향상 주장 없음 |

실패 5건의 메서드·증상은 실행 기록과 원본 XML에 보존한다. 기대값을
약화하거나 구조 변경에 correctness 수정을 섞지 않는다. 이 문서는 전체
리팩터링 완료 보고서가 아니다.
