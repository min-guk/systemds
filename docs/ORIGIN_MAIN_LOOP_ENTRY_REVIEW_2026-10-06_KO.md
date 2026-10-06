# origin/main 최근 변경 분석: loop 진입 변환과 J_v의 관계

작성일: 2026년 10월 6일. 원격·로컬 증거 확인 시각: 08:33 UTC / 10:33 CEST.

**최신 origin/main은 local 초기값을 한 번 업로드한 뒤 FED 배치로 반복하는 후보를 추가했다. 이는 기존 공통 reader 제약 아래에서 누락된 진입 변환을 복구한 변경이다. J_v나 분기별 서로 다른 map의 전달을 구현한 변경은 아니다.**

## 1. Fetch 결과와 분석 대상

실행 명령:

```sh
git fetch origin refs/heads/main:refs/remotes/origin/main
```

| 항목 | 확인 결과 |
|---|---|
| origin | `git@github.com:min-guk/systemds.git` |
| Fetch 종료 코드 | 0 |
| origin/main | `3d0d683c1bca099f004edf9105f5387369b4abea` |
| Fetch 전후 tracking ref | 동일. 기존 ref가 이미 최신 커밋을 가리키고 있었음 |
| 기존 작업 브랜치 HEAD | `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592` |
| HEAD에만 있는 커밋 / origin/main에만 있는 커밋 | 0 / 4 |
| 원격 소스 확인 경로 | `/home/mchoi/w1357-loop-entry-main-20261006` |

원격 소스 확인 경로의 HEAD가 위 origin/main SHA와 같고 분석한 tracked 소스에 변경이 없는 것을 확인했다. 커밋 diff와 이 스냅샷을 읽어 분석했으며, 기존 작업 브랜치에 merge·rebase를 수행하지 않았다. 기존 작업 트리에는 별도의 미커밋 변경이 있으므로 HEAD 비교와 실제 파일 내용 비교를 구분한다.

## 2. 최근 네 커밋의 의미

아래 시각은 커밋 시각이며 CEST 기준이다.

| 커밋 | 시각 | 주요 변경 | 공동 입력 논의와의 관계 |
|---|---|---|---|
| `535c52b1e6` | 10월 5일 03:54:59 | R44의 REFED 재사용·수명 관리, FULL profile, SOLVE shape 및 비용용 크기 상한 등을 반영 | Runtime·일부 후보·비용 근거의 복구. J_v 분석은 아님 |
| `ff203f4cc2` | 10월 5일 03:54:59 | AggLocal의 공개 sibling vector가 있는 local 연속 실행 선호 복구 | 공통 후보 공간을 유지하고 선택 우선순위를 변경 |
| `adaebee9cc` | 10월 5일 13:43:19 | AggLocal 정책 경로를 worklist와 index로 증분 전파 | 분석의 반복 작업 감소. 공동 입력 tuple 추가가 아님 |
| `3d0d683c1b` | 10월 6일 09:49:28 | Loop 진입 producer의 유료 materialization과 물리 배치 전달 보완 | Local 초기값에서 공통 FED 반복 배치로 들어가는 후보 복구 |

최신 커밋의 author 시각은 09:42:56, committer 시각은 09:49:28이다. 해당 커밋만의 변경 범위는 20개 파일, 1,867행 추가와 82행 삭제다.

첫 번째 커밋을 단순 runtime 수정으로만 분류하면 안 된다. REFED alias 수명·변경 감지 외에 FULL output profile과 SOLVE 결과 shape를 복구한다. 반면 새 크기 상한은 비용 산정용이며 exact 물리 geometry를 대신하는 근거로 사용하지 않는다.

AggLocal의 PUBLIC 조건은 해당 경계의 sibling vector에 적용된다. 모든 입력이 PUBLIC이라는 뜻이 아니며, raw protected matrix를 local로 수집하도록 허용하는 변경도 아니다. [AggLocal 선택 계약][S1]

증분 경로 분석에서도 다중 정의의 CFG 연결은 모든 writer가 지원해야 한다. 이 커밋의 “경로”는 선택 선호를 전달하는 분석 경로이며, 논문의 공동 입력 J_v와 다르다. [증분 경로 분석 설명][S2]

## 3. 최신 변경으로 새로 표현하는 계획

예를 들어 실제 federated input X의 map이 초기화 시점에 사용 가능하고, p를 local에서 초기화한다고 하자.

```text
X = federated(...)
p = local_initialization(...)

while (...) {
    p = update(p, X)
}
```

원하는 후보 중 하나는 다음과 같다.

```text
local 초기 계산
      │
      └─ 명시적 upload 1회 ─→ TW(p₀, 배치 S)
                                  │
                                  ▼
                              TR(p, 배치 S)
                                  │
                              반복 body
                                  │
                              TW(p, 배치 S)
                                  └──── backedge ─→ TR
```

기존에는 초기 producer가 직접 사용하지 않는 별도 concrete anchor를 진입 업로드에 활용하지 못하고, continuity 증명의 출발점도 native FED/FOUT 중심이었다. 그 결과 local 초기값에서 시작해 반복 상태를 FED에 유지하는 후보가 빠질 수 있었다.

새 코드는 loop read의 entry 정의를 찾아 실제 값을 만드는 producer까지 추적한다. 같은 namespace와 허용된 제어 흐름 위치에서 사용 가능한 concrete FEDERATED anchor를 수집한다. Loop 안에서 나중에 생성되거나 다른 branch에만 존재하는 anchor는 진입 변환의 근거로 사용할 수 없다. [Entry anchor 수집][S3]

Producer에 CP/LOUT 후보가 실제로 허용되어 있으면, 기존 materialization action을 이용하는 CP/FOUT 후보를 추가한다. 기존 local 후보를 유지하며, runtime에서 구성 가능한 ROW/COL/FULL/BROADCAST 배치만 검토한다. Recompile occurrence는 이 확장에서 제외한다. [진입 후보 추가][S4]

**CP/FOUT은 실제 초기 계산 producer의 후보다. TR/TW에 CP/FOUT을 허용한 것이 아니다.** 업로드 이후의 TW와 loop TR은 FED/FOUT 배치를 지원해야 한다. LOOP_PHI로 분류된 실제 transient access까지 업로드 확장에서 제외하도록 보완했다.

또한 선언된 action의 producer, source placement, 값 버전, scope, anchor owner와 물리 증명이 맞는 경우에만 그 upload를 새 map의 continuity 근거로 인정한다. 단순히 pool 이름을 붙여 실행 가능하다고 가정하지 않는다. [Materialization을 continuity 근거로 확인][S5]

## 4. 비용·배치·branch 연결의 변화

Top-level initializer가 한 번 실행되고 body가 T번 실행되는 경우의 의미는 다음과 같다.

```text
전체 비용 = 초기 계산 + 진입 upload + T × 반복 비용 + 필요한 종료 후 변환
```

진입 upload를 매 iteration마다 다시 과금하는 설계가 아니다. 비용의 소유자는 초기 producer이며 기존 실행 빈도 체계를 사용한다. 다만 “한 번”은 그 initializer의 실행 한 번을 기준으로 한다. 선택된 업로드는 initializer에서 수행되므로 loop가 0회 실행되더라도 생길 수 있다. 0회 실행에 맞춘 lazy upload나 별도 loop 특수화를 추가한 것은 아니다.

최신 main의 `PlacementCostSemantics` 변경은 materialized output의 layout 계산 helper 추가가 중심이다. 현재 다른 작업 트리의 비용 모델 변경 전체를 main에 옮긴 커밋이 아니다. [업로드 layout 계산][S6]

Planner의 예상 layout과 실제 lowering의 worker/range 순서를 맞추고 ROW/COL key에 전체 2-D range를 보존한다. Exact upload는 선택된 durable placement key를 사용한다. 같은 worker 집합이라는 이유로 순서나 범위가 다른 live map으로 대체하지 않는다. [물리 배치 등록][S7], [FOUT lowering][S8]

Loop entry가 여러 branch에서 정의되는 경우에도 한 entry만 seed하는 대신 모든 비재귀 entry 정의의 공통 지원으로 시작한다. 이후 최종 replay는 backedge를 포함한 모든 정의를 확인한다. **여러 branch initializer를 처리하는 것과 branch별 서로 다른 pool을 유지하는 것은 다르다.** [다중 entry seed][S9]

## 5. J_v 논의에서 바뀐 부분과 남은 부분

| 논의한 제한 | 최신 main의 상태 |
|---|---|
| Local 초기값 때문에 FED loop 배치로 진입하지 못함 | 사용 가능한 concrete anchor와 허용된 producer가 있으면 유료 진입 변환 후보 추가 |
| 모든 reaching definition이 공통 reader를 지원해야 함 | 유지 |
| Branch A의 map과 branch B의 다른 map을 그대로 전달 | 이 커밋의 지원 범위가 아님 |
| L2SVM의 s/g_old처럼 실제 함께 도달하는 정의 쌍 보존 | J_v 분석은 여전히 추가되지 않음 |
| 반복마다 다른 배치·실행 전략을 선택 | 추가되지 않음 |
| 함수 인자로만 알려지는 map을 일반적인 entry anchor로 사용 | 이 패치의 지원 범위 밖 |

CFG 분석은 여전히 변수별 정의 집합을 병합한다. 공통 reader의 exact layout 또는 native worker-pool 지원 검사와 다중 source의 상태 제약도 남아 있다. [변수별 정의 분석][S10], [공통 reader 검사][S11]

따라서 이번 변경으로 복구한 것은 **J_v 상관관계를 이용해야만 가능한 계획이 아니라, 공통 배치 모델 안에서도 명시적 entry 이동을 표현하면 가능했던 계획**이다. 앞선 보고서의 “단일 입력 map 표현, 공동 입력 pairing, 연산별 geometry 증명은 서로 다르다”는 구분은 계속 유효하다.

이 변경은 작은 구현으로 일부 후보 손실을 복구하는 구체적인 사례다. 그러나 이것이 branch-correlated map 문제까지 해결했거나 전체 계획 공간이 완전해졌다는 근거는 아니다. 커밋의 구현 문서도 이 범위를 명시한다. [명시된 지원 한계][S12]

## 6. 확인한 검증 증거와 한계

원격 SHA와 같은 tracked 소스를 가진 별도 작업 트리에서 기존 `main-regressions.log`를 읽었다. 로그는 **116 tests, failures 0, errors 0, skipped 7**, 즉 109개 통과와 `BUILD SUCCESS`를 기록한다. 실행 종료 시각은 2026-10-06 09:45:47 CEST다. 이번 분석에서 테스트를 재실행한 것은 아니다. [기존 통합 테스트 로그][S13]

| 항목 | 확인한 증거 | 해석의 한계 |
|---|---|---|
| 유한 계획 공간 | 8×2, 2-worker identity loop에서 raw 29,400개, admitted 280개, projected physical 조합 7개 기록 | 특정 fixture의 entry/steady-layout 관계. 모든 DML·모든 구체 배치의 완전성 증명은 아님 |
| 진입 비용 | T=1/2/10에서 upload 비용이 양수이며 동일한지 검사 | 0회 runtime 또는 single-trip 특화 계획 검증은 아님 |
| 실제 worker 실행 | ROW/BROADCAST 두 경우에서 3회 loop와 수치 결과 확인 | Entry와 steady FED 상태를 강제한 테스트. 일반 비용 선택의 증거는 아님 |
| 업로드 위치 | Static fed_fout instruction 한 개이며 loop 밖인지 검사 | 모든 동적 경로에서 전송 횟수가 정확히 한 번이라는 일반 증명은 아님 |
| FED 반복 실행 | fed_+ 실행 횟수가 3 이상인지 assertion. 기존 로그에는 3회 기록 | Assertion 자체는 정확히 3회 조건이 아님 |

유한 공간 테스트는 독립적으로 정한 일곱 projected 조합과의 equality를 확인한다. 세부 action/proof identity까지 포함한 모든 가능한 physical program을 전부 열거했다는 의미로 확대하지 않는다. [유한 공간 테스트][S14]

실제 worker 테스트는 실행 가능한 plan witness를 확인하는 데 가치가 있다. 다만 다음 검증은 추가로 구분해야 한다. [Runtime witness][S15]

- 0회·1회 loop의 실제 worker 실행과 COL/FULL runtime은 해당 두 테스트의 범위 밖이다.
- Range-sensitive 결과 5564는 원본 X의 row 배치 검사다. 업로드한 p의 worker/range 대응을 직접 검사하는 결과는 아니다. p는 최종 sum 변화 48로 검증한다.
- 해당 테스트의 fallback/repair counter는 production source에 증가 지점이 확인되지 않았다. Counter가 0이라는 assertion만으로 모든 fallback/repair 부재를 독립적으로 증명할 수는 없다. 이는 기존 관측 장치의 한계이며 새 실행 결함을 발견했다는 뜻은 아니다. [Counter 구현][S16]

이번 읽기 검토에서는 명백한 신규 실행 결함을 확인하지 못했다. 위 항목들은 구현 전체가 잘못되었다는 판정이 아니라, 현재 검증 증거가 보장하는 범위를 명확히 한 것이다.

## 7. 성능과 기존 실험에 대한 판단

커밋 문서에는 원래 training/validation script의 추정 objective가 약 19.469% 낮아졌다는 비교가 있다. 그러나 같은 문서가 다음을 명시한다. [기존 비교의 범위][S17]

1. 두 선택 계획 모두 p의 초기·반복 배치를 local로 유지했고 entry upload를 선택하지 않았다.
2. 92개의 선택된 HOP placement는 같았으며 relocation site 수가 8개에서 4개로 줄었다.
3. 수치는 runtime 측정이 아니라 planner 추정 비용이다.
4. Loop-entry 변경만 분리한 비교가 아니라 묶음 patch의 비교다.
5. 별도 snapshot에서의 비교이며 origin/main 통합 전후 비용 비교가 아니다.

따라서 “loop-entry upload로 main에서 학습 runtime이 19.469% 개선됐다”라고 보고하면 안 된다. 현재 14-workload campaign에서 이 새 후보가 실제로 선택되고 유리한지는 별도 증거가 필요하다.

기존 작업 트리에는 앞선 세 커밋의 핵심 변경이 내용상 이미 포함된 부분이 있다. 반면 최신 loop-entry helper는 해당 작업 트리에 없었고, 원격 스냅샷에서 확인했다. 앞으로 기존 workload의 후보 손실을 조사할 때에는 **현재 dirty tree와 최신 main 중 어느 소스·JAR를 기준으로 하는지 먼저 고정**해야 한다.

Main을 기준으로 한 다음 진단은 우선 해당 loop 초기 producer에 유료 entry 후보가 존재하는지, 존재한다면 비용 때문에 선택하지 않았는지부터 확인하는 것이다. 그 이후에도 남는 경로별 map 전달이나 공동 입력 관계 문제를 J_v 관련 제한으로 구분할 수 있다.

## 8. 수행 범위와 관련 문서

수행한 작업은 origin/main fetch, 커밋·소스 비교, 원격 SHA와 일치하는 작업 트리 확인, 기존 테스트 로그 및 문서 검토다. 기존 작업 브랜치의 production/test source, 배포 JAR와 실행 중인 실험은 변경하지 않았다. 이 보고서와 세션 기록만 작성했다.

- [J_v 정보 손실과 workload 개선 검토](JOINT_INPUT_WORKLOAD_IMPROVEMENT_REVIEW_2026-10-06_KO.md): 앞선 작업 트리 기준의 원인 구분과 실험 진단 방향.
- [현재 블록 간 배치 제약](CURRENT_CROSS_BLOCK_PLANNING_CONSTRAINTS_2026-10-06_KO.md): 기존 공통 reader 계약 설명.
- [세션 기록](SESSION_ISSUES_2026-10-06.md): 이번 fetch 및 분석의 범위.

아래 참조는 별도 표시가 없는 한 `3d0d683c1b`와 일치하는 `/home/mchoi/w1357-loop-entry-main-20261006`의 파일이다.

[S1]: /home/mchoi/w1357-loop-entry-main-20261006/docs/AGGLOCAL_LOCAL_VECTOR_RECOVERY_PLAN_2026-10-05_KO.md:8
[S2]: /home/mchoi/w1357-loop-entry-main-20261006/docs/AGGLOCAL_INCREMENTAL_ANALYSIS_2026-10-05_KO.md:11
[S3]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:623
[S4]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:8212
[S5]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:2173
[S6]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementCostSemantics.java:61
[S7]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/ExactPlacementRegistration.java:165
[S8]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/lops/compile/Dag.java:1293
[S9]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4995
[S10]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementProgramFacts.java:191
[S11]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4740
[S12]: /home/mchoi/w1357-loop-entry-main-20261006/docs/LOOP_ENTRY_IMPLEMENTATION_2026-10-06.md:60
[S13]: /home/mchoi/w1357-loop-entry-main-20261006/.omx/loop-entry-publish/main-regressions.log:265
[S14]: /home/mchoi/w1357-loop-entry-main-20261006/src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LoopEntryCompletePlacementSpaceTest.java:49
[S15]: /home/mchoi/w1357-loop-entry-main-20261006/src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LoopEntryRuntimeWitnessTest.java:158
[S16]: /home/mchoi/w1357-loop-entry-main-20261006/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java:110
[S17]: /home/mchoi/w1357-loop-entry-main-20261006/docs/LOOP_ENTRY_IMPLEMENTATION_2026-10-06.md:66
