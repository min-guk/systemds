# 공통 지원 관계 리팩터링 진행 보고

## 범위와 현재 결론

기준은 main `276f958efc91477e3d0e8a2a492929455dd77227`이다. 기존 dirty workspace와 이전 미채택 후보는 수정하지 않고 다음 별도 worktree에서 작업했다.

`/grid/3/cofee-lm-sweep-mchoi-20260914/factored-support-20261008/source`

**첫 번째 개선은 구현/단위 검증을 마쳤다. 전체 support 조합 및 DP 변수의 factorization을 완료한 것은 아니며, 전체 컴파일 20초 달성은 아직 증명되지 않았다.**

## 이번에 바꾼 것

`NativePlacementContinuity.resolveFixedValueMapGraph`의 두 반복 전체 순회를 변경했다.

| 이전 | 변경 |
|---|---|
| 같은 source의 local support row를 질의마다 다시 해석 | 같은 resolver revision/정확한 owner identity 안에서 불변 row 공유 |
| grounding 변화가 일부여도 graph 전체를 다시 검사 | reverse dependency를 따라 새로 grounding된 source만 전파 |
| exact partition/full geometry가 변할 때마다 전체 graph 반복 | 각 row의 geometry를 한 번 검사하고 두 false flag를 별도로 전파 |

기존 결과를 유지하려면 일반적인 FIFO queue만으로는 부족하다. 실제 anchor 선택이 최초 grounding 순서에 의존하므로 queue 우선순위를 **기존 sweep round + BFS 순번**으로 정의했다. 각 clause에는 하나 이상의 grounded source가 필요하고, 노드의 모든 clause가 충족되어야 한다. 일부 source로 먼저 grounding되더라도 나머지 reachable node가 미증명/충돌이면 질의 전체는 실패한다. 이후 도착한 source의 endpoint 충돌도 검사한다.

다른 질의에서 계산한 하위 pool을 terminal leaf로 대체하지 않았다. 이 최적화는 역사적인 대표 anchor 선택을 바꿀 수 있기 때문이다. 따라서 이번 변경은 **local row 해석 공유 + 질의 내부 증분 전파**이지, 모든 질의를 합쳐 전체 graph를 딱 한 번 푼다는 주장이 아니다.

오라클, 합법 후보, 탐색 상한, 비용식, privacy, runtime 실행/보정 규칙은 바꾸지 않았다.

## 검증

- 수정 전: 새 behavior-lock8개 + 기존13개 =21개 PASS.
- 과거 resolver를 복제한 test-only oracle과 고정 seed80개 순환 graph의 결과·대표 anchor·geometry flag·질의 순서/cache 동작을 비교했다.
- 작업량 테스트3개와 owner identity regression1개를 추가한 최종 fixed-pool suite: **25/25 PASS**.
- 관련30개 suite: **JUnit240개 PASS, 기존 ignore3개**. 이는 진단 counter/owner-cache 보강 전 알고리즘 판의 추가 검증이다.
- 최종 소스의 fresh offline Maven package: **48개 PASS / BUILD SUCCESS**.
- 2,384개 build input 변경 없음 및195개 builtin DML의 source/JAR byte 일치 확인.
- 독립 code review: 최종 APPROVE. `git diff --check` PASS.

작업량 테스트는101개 노드/102개 연결의 diamond에서 decoding101회, activation101회, grounding-edge 전파102회, geometry 방문103회, dynamic-layout의 false 전파102회를 확인했다. 같은 root의 cache hit는 추가 작업0회다. 중간 root를 먼저 질의한 뒤 전체 root를 질의하면 decoding은 공유되지만 grounding은 각 질의마다 실행되며, 이 누적 작업량도 검사한다. 이 수치는 **작업량 회귀 검증이지 전체 LogReg runtime 개선율이 아니다.**

초기 cache의 구조 동등성 key가 다른 owner identity의 결과를 공유할 수 있는 문제를 추가 검토에서 발견했다. 원본 PASS/초기 후보 FAIL을 확인하고, `IdentityHashMap<owner, Map<reference,row>>`로 고쳐 두 방향의 오염/권한 차용을 차단했다. 이 과정과 red/green 로그를 보존했다.

## 대형 워크로드 측정

- 기존 기본 설정 진단: LogReg W1이 약49분간 컴파일 미완료. controller만 SIGINT로 수동 중단했고 W3는 시작하지 않았다. 이것을 완료 시간이나 timeout 결과로 취급하지 않는다.
- 이번 후보는 같은 Docker 기반의 정식 성능검증 harness로 LogReg LAN DP-local W1/W3를 실행한다. **성능검증 전용60초 watchdog**은 기존과 동일하며 일반 compile/runtime의 무제한 기본 정책은 변경하지 않는다.
- benchmark harness3개 파일은 이전 baseline에서 byte-for-byte 가져온 것이며 이번 알고리즘을 위해 evaluator를 완화하지 않았다.
- 최종 측정 결과는 문서 마지막의 결과 표와 JFR 분석을 참조한다.

## 아직 남은 실제 factorization

다음 두 곳의 곱을 함께 제거해야 한다.

1. 공통 분석의 `enumerateImmediateSupports`: 입력별 옵션을 구체 support clause의 조합으로 펼친다.
2. `ExactPhysicalModel`: 각 clause와 input authority 조합을 다시 완전한 physical alternative로 펼친다.

첫 번째만 lazy list로 바꾸면 두 번째에서 같은 조합이 생성된다. 이것은 해결이 아니다.

후속 변경은 지원 관계를 **OR의 group / group 안의 AND 입력 선택**으로 보존하고, 실행·native output header, support group, 각 입력 공급 선택을 별도 변수/관계로 표현해야 한다. `(P0,Q0) OR (P1,Q1)`에서 `(P0,Q1)`이 생기면 안 되고, 동일 producer에는 하나의 실제 선택만 허용해야 한다. loop entry/backedge와 모든 reaching writer, fusion 권한도 유지해야 한다.

비용을 단순히 독립적인 edge 비용으로 분해해서는 안 된다. 선택된 여러 입력의 layout에 의존하는 공동 실행 비용, 공유 materialization 비용과 수명/빈도는 유지하고, solve 이후 선택된 관계의 membership을 검증해 정확히 하나의 분석 소유 receipt를 구성해야 한다. canonical tie/receipt 순서도 계약에 포함한다.

이 후속 단계는 작은 완전 열거 oracle로 합법한 전체 선택·비용·정답·receipt가 동일함을 증명하고, 큰 독립 입력 옵션 사례에서 **공통 분석부터 physical model까지** 저장/생성량이 product가 아닌 group+option 규모인지 확인해야 한다. 실제 결합 비용이나 graph 구조가 요구하는 전역 DP의 최악 복잡도까지 없어진다는 주장은 하지 않는다.

## 근거 경로

- 계획: `/grid/3/cofee-lm-sweep-mchoi-20260914/factored-support-20261008/PLAN.md`
- 검증/리뷰: 같은 디렉터리의 `evidence/`
- 최종 JAR SHA256: `b329a39b7f7ce2a3a2aa4a92ffa13ec50a88cbf7637c9d54fe31d13b0b1abcb4`
- 후보 Docker stage: `/home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z`
- 후보 campaign: `/grid/3/cofee-lm-sweep-mchoi-20260914/factored-support-20261008/campaign`

이번 작업은 commit/push하지 않았으며 origin/main을 바꾸지 않았다.

## 최종 Docker 측정 결과

| 조건 | 결과 | process wall time | 완료된 compile 시간 |
|---|---|---:|---|
| LogReg LAN DP-local W1 | 성능검증60초 watchdog rc124 | 61.021s | 없음 |
| LogReg LAN DP-local W3 | 성능검증60초 watchdog rc124 | 60.919s | 없음 |

두 조건 모두 `analysis_begin`만 남기고 공통 분석에서 종료되었다. process wall time은60초 제한과 종료/SSH 반환 지연을 포함하며 **완료된 컴파일 시간이 아니다**. baseline과 cell.dml/execution.xml이 byte 단위로 같음을 확인했다. 정식2회와 진단1회 모두 해당 컨테이너 정리와 각 campaign의8개 stage lease 해제가 완료되었다. 실행 중인 이번 실험은 없고 runtime은 돌리지 않았다.

**결론: 이번 변경만으로20초 목표를 달성하지 못했다. 원본 대비 전체 완료 시간 개선율도 측정되지 않았다.** 기본 설정의49분 미완료 진단과 이번60초 성능 control은 서로 다른 측정 정책이므로 속도 개선율로 나눠 계산하면 안 된다.

### 남은 비용: 실제60초 JFR 구간

main-thread sample3,818개에서 다음 비용이 관측되었다.

- canonical text 비교 top frame784개(20.5%); 이 중761개는 native proof signature comparator의 호출 경로였다.
- NativePoolWitness.equals top frame386개(10.1%).
- StringLatin1.hashCode top frame347개(9.1%).
- IdentityHashMap.clear top frame269개(7.0%).
- `pruneDeadAlternatives`를 포함한 stack684개(17.9%), `buildCandidateProofGraph`를 포함한 stack333개(8.7%).
- 이번에 바꾼 `resolveFixedValueMapGraph` 포함 sample은9개(0.24%)였다.

이 값은 계측된 앞60초의 sample 비율이며 전체 완료 시간 비율이나 개선율이 아니다. inclusive stack 비율은 서로 중복되고 stack depth 제한도 있다. 특히 **앞60초의 주된 비용은 여전히 proof 결과의 긴 비교/해싱, proof graph 생성 및 pruning**이다. 늦은 시점의 한 stack을 보고 고친 fixed-pool 순회만으로 이를 해결할 수 없었다.

`enumerateImmediateSupports` 포함 sample은1개였으므로, 실제 열거 함수 자체가 이 구간 CPU의 대부분이라고 주장하지 않는다. 조합/질의 개수에서 파생된 결과 정렬·proof 처리량을 줄이는 표현 변경이 필요하지만, 그 효과는 후속 구현의 end-to-end 검증으로 확인해야 한다.

세부 근거: `evidence/compile-results.json`, `evidence/remaining-hotspots.json`, `evidence/jfr-summary.txt`, `campaign-diagnostic/attempts/compile/01791467574437480797-9934b3b7/compile.jfr`.
