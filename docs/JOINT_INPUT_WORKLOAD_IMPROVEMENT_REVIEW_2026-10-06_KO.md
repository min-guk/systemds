# J_v 정보 손실과 현재 실험의 개선 대상

작성일: 2026년 10월 6일

분석 대상: `/home/mchoi/w1357-paper-aligned-refactor`의 작업 트리, 현재 campaign·renderer, 실험 entry DML과 관련 builtin 및 기존 로컬 조사 기록. HEAD는 `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592`이며 커밋되지 않은 변경이 있다. 작업 트리와 이미 배포된 실험 JAR의 내용을 동일하다고 가정하지 않는다.

**현재 확인한 것은 공동 입력 정보가 사라지는 지점이다. 그 때문에 현재 실험에서 실제로 몇 개의 물리 계획을 잃었는지, 얼마나 느려졌는지는 아직 확인되지 않았다.** 또한 지금까지 논의한 모든 제한이 J_v 부재 때문인 것도 아니다.

권고는 L2SVM의 구체적인 공동 입력 지점에서 후보 탈락 원인을 먼저 확인하고, 원인에 해당하는 표현이나 증명만 보완하는 것이다. 전체 J_v 구현을 현재 실험의 필수 수정으로 단정하지 않는다. 반대로 실험상의 손실이 아직 입증되지 않았다는 이유로 공통 저장장소 제약이 runtime의 필수조건이라고 주장하지도 않는다.

## 1. 무엇을 잃었다는 것인가

J_v가 보존하는 정보는 **여러 입력의 정의 중 어떤 것들이 한 번의 실행에 함께 도달할 수 있는가**다.

```text
실제로 가능한 입력 쌍: (X_true, Y_true), (X_false, Y_false)

변수별 정의 집합:
    X ← {X_true, X_false}
    Y ← {Y_true, Y_false}
```

변수별 집합만으로는 `(X_true, Y_false)`와 `(X_false, Y_true)`가 실제로 불가능하다는 정보를 표현하지 못한다. 실행 경로 자체가 사라지는 것이 아니라, 입력 사이의 pairing 정보가 사라진다.

하지만 **정보 손실과 계획 손실은 별개**다. 모든 X 후보와 Y 후보가 어떤 조합에서도 해당 연산을 지원한다면, pairing을 몰라도 그 연산 후보는 유지될 수 있다. 실제 가능한 두 조합은 실행 가능하지만 불가능한 교차 조합은 호환되지 않는 경우에야, 그 정보의 부재가 더 강한 제약과 후보 손실로 이어질 수 있다.

현재 구현이 반드시 네 tuple을 직접 생성해 검사한다는 뜻은 아니다. 앞서 논의한 A/B pool 사례에서는 그보다 앞선 단계인 **각 변수의 모든 정의가 공통 reader realization을 지원해야 한다는 요구**가 제한으로 작용한다. `exactTransientReplay`와 `CandidateEmissionRealization`은 그러한 공통 물리 지원을 요구한다. [Reader 후보 생성][S1], [Realization의 공통 witness][S2]

따라서 다음 수량을 구분해야 한다.

| 수량 | 의미 | 이번 조사 결과 |
|---|---|---|
| 공동 입력 tuple 수 | 실제 함께 도달할 수 있는 정적 정의 조합 | L2SVM의 특정 지점에서 구체적인 두 조합 확인 |
| 후보 realization 수 | 개별 노드에서 선택 가능한 물리 대안 수 | J_v 유무에 따른 차이 미측정 |
| 합법 전역 계획 수 | 모든 연결·실행 조건을 함께 만족하는 선택 조합 수 | 미측정 |
| 선택 계획의 비용·runtime | 후보 중 고른 계획의 추정 비용과 실제 실행시간 | J_v 효과로 귀속할 비교 결과 없음 |

Tuple이 두 개라는 사실로 전역 계획을 두 개 잃었다고 계산할 수 없다. 후보 수의 합도 전역 계획 수가 아니다.

## 2. 서로 다른 세 가지 제한

| 사례 | 현재 놓칠 수 있는 계획 | 필요한 개선 |
|---|---|---|
| 분기마다 X가 다른 pool에 있고 `X + 1` 실행 | 도착한 X의 map에서 그대로 계산하는 계획 | Reader가 도착한 map을 전달하는 표현. 공동 입력 J_v는 불필요 |
| `(X_A,Y_A)` 또는 `(X_B,Y_B)`가 함께 도착 | 각 실행의 정렬된 입력끼리 재배치 없이 계산하는 계획 | Map 전달 표현과 공동 입력 관계 모두 필요 |
| 같은 selector로 X/Y를 필터링해 partition 범위가 변함 | 두 결과의 alignment를 이용하는 계획 | Selector와 입력 배치에 근거한 연산별 증명. J_v만으로 부족 |

### 단일 matrix 입력: J_v 없이도 설명되는 제한

아래는 설명용 의사 코드다. X_A와 X_B는 shape가 같고, 같은 FED 연산이 지원하는 배치이며, privacy가 해당 실행을 허용한다고 가정한다.

```text
if (c) {
    X = X_A;    // pool A
} else {
    X = X_B;    // pool B
}
Z = X + 1;
```

FED matrix-scalar instruction은 실행 시 실제 X의 FederationMap에서 요청을 실행하고, FOUT 결과에 그 map의 새 ID를 부여한다. 따라서 이 instruction 자체에는 A와 B가 같아야 할 이유가 없다. [실제 실행 구현][S3]

TW→TR에서 이동을 하지 않는다는 조건은 **각 실행에서 도착한 TW의 map을 TR이 그대로 읽는다**는 뜻이다. 서로 다른 실행의 TW들이 같은 map이어야 한다는 결론은 여기서 나오지 않는다. 그 결론에는 TR 하나가 공통된 물리 배치만 표현한다는 현재 모델의 추가 가정이 들어간다.

이 사례는 instruction 수준의 지원 근거다. 현재 planner·emission·재컴파일까지 해당 계획을 그대로 승인하고 실행한다는 E2E 검증 결과는 아니다.

### 다중 입력: 공동 관계가 필요한 제한

한 분기가 X와 Y를 함께 선택하고, true에서는 A/A, false에서는 B/B가 되며 각 쌍이 연산의 alignment를 만족한다고 가정한다. 한 가지 FED 연산 전략으로 두 실행을 처리할 수 있어도, 변수별로 공통 A 또는 공통 B를 요구하면 원래 map을 유지하는 계획을 표현하지 못할 수 있다.

이 경우 J_v는 실제 두 입력 쌍을 보존하는 데 도움이 된다. 그러나 reader가 여전히 하나의 공통 pool만 표현하면 J_v를 추가해도 그 제한은 남는다. Reader 표현과 연산의 입력 조건 검증을 함께 연결해야 한다.

또한 X와 Y를 서로 독립적인 조건으로 선택하면 A/B와 B/A도 실제 가능하다. 이 조합을 J_v에서 지우거나, 실행에 유리한 tuple 하나만 골라 검사해서는 안 된다. 선택한 연산 전략은 가능한 실행 모두에서 지원되어야 한다.

### 동적 geometry: tuple 외에 필요한 정보

동일한 selector를 사용했다는 사실과 입력 row alignment를 이용하면 두 필터링 결과의 row 범위 관계를 증명할 수 있다. 두 결과가 함께 도달한다는 tuple만으로 그 관계가 증명되지는 않는다. 마찬가지로 같은 worker endpoints라는 사실만으로 연산에 필요한 partition alignment가 자동으로 성립하지 않는다.

## 3. 실제 공동 입력 사례: L2SVM

현재 builtin의 관련 부분은 다음과 같다. 표시한 행 번호는 이 작업 트리의 DML 소스 기준이다. [l2svm.dml][S4]

```text
91:  g_old = t(X) %*% Y
92:  s = g_old

96:  while (...) {
         ...
131:     tmp = sum(s * g_old)
         ...
136:     s = be * s + g_new
137:     g_old = g_new
     }
```

131행의 사용 지점에서 가능한 정적 정의 조합은 다음과 같다.

| s의 정의 | g_old의 정의 | 함께 도달 가능한가? |
|---|---|---|
| 진입의 s@92 | 진입의 g_old@91 | 가능: 첫 iteration |
| backedge의 s@136 | backedge의 g_old@137 | 가능: 후속 iteration |
| 진입의 s@92 | backedge의 g_old@137 | 불가능 |
| backedge의 s@136 | 진입의 g_old@91 | 불가능 |

실제 조합은 두 개다. 변수별 두 집합의 Cartesian product는 네 개이며, 그중 두 개가 실제로 불가능하다. 이는 현재 workload에 존재하는 공동 reaching-definition 관계다.

다만 두 vector가 모두 local이거나 공통된 물리 배치의 후보로 처리되면, pairing 정보의 유무가 실행 가능성 판정을 바꾸지 않을 수 있다. 이 지점에서 J_v가 새 계획을 복구하는지 판단하려면 다음을 확인해야 한다.

1. DML rewrite·inlining 이후 이 사용이 어떤 최종 HOP와 producer occurrence에 대응하는가.
2. 진입과 backedge 정의에 실제로 어떤 물리 realization이 존재하는가.
3. 기존 reader 또는 consumer 후보가 어떤 조건 때문에 배제되는가.
4. 공동 입력 관계를 보존하면 그 조건을 올바르게 증명할 수 있는가.
5. 복구된 후보가 전역적으로 유효하고 비용상 유리한가.

이번 조사는 이 다섯 단계의 candidate trace나 E2E 비교를 수행하지 않았다. 정적 정의 tuple은 후속 iteration의 매번 달라지는 실제 map 관계까지 자동으로 증명하는 것도 아니다.

## 4. 현재 실험 구성에서 무엇이 관련되는가

Campaign은 14개 workload, 네 planner, W1/W3/W5/W7, 네 network profile을 별도 cell로 구성한다. Worker 수가 달라지는 것은 서로 다른 실행이며, 한 DML의 branch가 W1과 W3을 선택하는 구조가 아니다. [Campaign 구성][S5]

Renderer는 한 cell의 각 input role에 동일한 worker 수를 전달하고, topology의 같은 앞부분을 사용한다. X/Y의 파일이 달라도 초기 worker 집합은 같은 prefix다. [Worker 선택][S6], [Input role 생성][S7]

이전에 확인한 entry template에서는 별도 pool 두 쌍을 읽어 AA/BB를 분기로 선택하는 구성이 발견되지 않았다. 따라서 그 가상 예제를 현재 benchmark의 손실 재현이라고 제시할 수 없다. 초기 입력의 pool이 같다는 사실로 모든 중간 결과의 배치도 같다고 추론할 수는 없다.

| 대상 | 실제 구조 | 우선 확인할 부분 | 현재 증거 수준 |
|---|---|---|---|
| L2SVM | s와 g_old의 공동 entry/backedge | 공동 정의 정보의 부재로 reader·consumer 후보가 탈락하는지 | DML 관계 확인, 물리 계획 손실 미확인 |
| STEP-LM | column 선택, 반복적인 cbind, regression 호출 | 동적 열 수에서도 row map의 계승과 consumer 조건이 증명되는지 | 관련 구조와 기존 지원 확인, J_v 효과 미확인 |
| GNMF entry | `xmin < 0`이면 `X = X - xmin` | 원래 X와 갱신 X의 map 전달이 유지되는지 | 단일 matrix 입력의 전달 사례, J_v 필요성 미확인 |
| P2의 split | 동일한 I로 X/Y row filtering | removeEmpty의 map·shape 전달 및 필요한 출력 관계 | Shared selector 확인, 해당 entry의 공동 alignment 이익 미확인 |

STEP-LM은 `Xi = cbind(X_global, X_orig[, i])`를 만들어 regression에 전달하고, 선택한 feature를 X_global에 반복적으로 붙인다. 핵심은 column 변경에도 필요한 row 배치 관계가 유지되는지다. [steplm.dml][S8]

이 영역에는 이미 ROW cbind 등의 native continuity 규칙과 protected loop reader 회귀 테스트가 있다. 기존 지원을 확인하지 않고 모든 dynamic loop 문제를 J_v 구현으로 다시 해결하려 해서는 안 된다. 이번 문서 작업에서 테스트를 새로 실행한 것은 아니다. [기존 규칙][S9], [관련 회귀 테스트][S10]

GNMF entry의 조건부 갱신은 matrix-scalar 연산이다. 원래 X와 갱신된 X 사이의 map 전달을 점검하는 사례로 적절하며, 그 자체가 다중 입력 pairing 사례는 아니다. [GNMF entry][S11]

`split`은 같은 I로 X_Train과 Y_Train을 만들고, I의 보수로 test 데이터를 만든다. 그러나 P2 entry는 이 결과들을 각각 sum한 뒤 scalar checksum으로 합친다. Xtrain/Ytrain을 함께 aligned binary consumer에 넣는 사용이 아니므로, shared-I 관계가 있다는 사실만으로 현재 P2의 성능 향상을 예상할 수 없다. [split.dml][S12], [P2 entry][S13]

## 5. 무엇을 어떻게 개선할 것인가

### 먼저 후보 탈락 원인을 관측한다

첫 대상은 **DP-local / L2SVM / W3 / LAN**을 제안한다. 이는 최초 진단 범위를 작게 잡기 위한 선택이며 최적 조건이라는 측정 결과가 아니다. 해당 DML 사용과 최종 HOP의 대응부터 고정한다.

기존 `--diagnostic-plan-details`와 단일 cell 진단 경로, `PlannerCandidateSpaceAudit`를 활용한다. Candidate audit에는 이미 다음 JVM property가 있지만, 현재 campaign의 상세 trace 옵션이 이것까지 자동 활성화하는 것은 아니다. 진단 경로에 property 전달과 생성 파일 수집을 연결하는 보완이 필요하다. [기존 audit][S14], [Campaign 진단 옵션][S15]

```text
sysds.fedplanner.space.audit
sysds.fedplanner.space.audit.dir
```

현재 audit의 상태·capability·privacy 기록에 다음 물리 관계를 연결하면 원인을 구분할 수 있다.

- Reader와 consumer의 occurrence identity 및 소스 위치.
- Reader에 도달하는 모든 writer와 각 writer의 물리 realization.
- 공통 reader 후보별 source 지원 성공·실패와 그 이유.
- Consumer 후보가 요구하는 입력 alignment·shape·privacy 조건.
- 가능한 공동 정의 조합 및 후보가 최종 선택되지 않은 이유.

특히 `no-all-definition-compatible-realization`은 모든 reader 대안이 사라졌을 때 발생한다. CP/LOUT 대안이 남아 있으면 FED 대안만 사라져도 이 오류가 발생하지 않는다. 따라서 이 오류의 부재를 FED 후보 손실의 부재로 판단하면 안 된다. [현재 탈락 조건][S16]

### 원인에 해당하는 부분만 보완한다

| 진단 결과 | 보완 대상 | 유지해야 할 조건 |
|---|---|---|
| 단일 입력 연산은 지원되지만 공통 pool이 없어 reader 후보를 못 만듦 | Reader와 realization에 실제 도착한 map을 전달하는 표현 | 서로 다른 map을 같다고 위조하지 않고, 이후 consumer까지 검증 |
| 같은 endpoints의 동적 slicing/cbind에서 기존 계승 증명이 끊김 | 기존 native continuity·shape 전달 규칙 | Runtime이 실제로 보존하는 관계만 증명 |
| 실제 공동 조합은 모두 지원되지만 pairing 정보가 없어 후보를 승인하지 못함 | 해당 consumer의 공동 정의 관계와 map 표현 | 가능한 tuple 모두에 실제 선택된 producer·consumer 후보를 검사 |
| 같은 selector의 출력 alignment를 증명하지 못함 | Selector identity와 입력 배치에 근거한 연산 전달 규칙 | 같은 selector라는 사실만으로 임의 입력 배치를 aligned로 간주하지 않음 |
| 필요한 후보가 이미 있고 비용 때문에 선택되지 않음 | 비용·빈도·이동량 추정과 선택 결과 | J_v 부재를 원인으로 삼지 않음 |

공동 입력 관계가 필요한 지점부터 분석 범위를 정할 수 있다. 그러나 그 지점의 tuple이 생기는 nested branch·loop·비재귀 호출 연결은 빠짐없이 반영해야 한다. 분석 범위를 줄인다는 이유로 가능한 실행을 생략할 수는 없다.

이 표는 원인별 개선 방향이다. 기존 호환성 검사를 삭제하거나, 임시 옵션으로 TR/TW 허용 상태를 완화하거나, runtime fallback으로 실패를 보정하는 제안이 아니다. 새로운 J_v 구현에 착수했다는 뜻도 아니다.

### 후보 복구와 성능 개선을 따로 검증한다

변경이 필요하다고 확인되면 다음 순서로 효과를 판정한다.

1. 기존에 없던 물리 후보가 실제로 생성되는지 확인한다.
2. 그 후보가 모든 가능한 실행에서 지원되고 최종 선택과 일치하는지 확인한다.
3. 계획이 바뀌었다면 필요한 데이터 이동과 연산이 어떻게 달라졌는지 확인한다.
4. 정답·실행 계약을 검증한 동일 Docker 조건에서 runtime과 초기 planning 시간을 비교한다.

실험은 기존 `run_LAN_docker.sh` 경로를 사용한다. 진단 로그를 켠 실행시간을 benchmark 성능으로 채택하지 않는다. 실제 실행에 사용한 JAR·builtin·entry DML·입력·비용 설정을 고정해야, 현재 작업 트리의 관계 분석과 비교 결과를 연결할 수 있다. [Docker entrypoint][S17]

일반적인 표현 확장을 검증할 때에는 단일 입력 A/B, 공동 AA/BB, 독립 조건의 AB/BA, loop entry/backedge를 구분하는 작은 protected correctness 사례가 유용하다. 이는 기존 workload에서의 성능 효과와 구분해서 보고한다.

## 6. 현재까지 주장할 수 있는 범위

| 주장 | 판단 |
|---|---|
| L2SVM DML에 공동 entry/backedge 정의 관계가 있다 | 확인 |
| 변수별 집합만으로 그 pairing을 표현할 수 없다 | 확인 |
| 모든 def의 공통 저장장소가 runtime 연산의 일반적인 필요조건이다 | 성립하지 않음 |
| J_v만 추가하면 map 전달과 모든 동적 alignment 문제가 해결된다 | 성립하지 않음 |
| 현재 benchmark에서 J_v 미구현 때문에 특정 개수의 합법 계획을 잃었다 | 미확인 |
| 현재 benchmark가 J_v 구현으로 특정 비율만큼 빨라진다 | 미확인 |
| 전체 J_v를 지금 구현해야 현재 실험 목표를 달성할 수 있다 | 근거 부족 |

기존 선택 계획 비교와 runtime PASS 기록은 중요한 실행 증거지만, 선택되지 않은 후보의 탈락 원인을 자동으로 알려주지는 않는다. 별도로 수행된 함수 입력 경계 A/B 비교 역시 해당 경계 모델 변경의 효과이며, J_v 유무를 비교한 결과가 아니다. 이를 이 보고서의 손실 계획 수로 가져오지 않는다. [함수 경계 비교 문서](FUNCTION_BOUNDARY_ALIAS_ABLATION_2026-10-06_KO.md)

이번 문서는 앞선 읽기 전용 조사를 정리한 것이다. 모든 transitive builtin의 물리 trace를 조사한 것이 아니며, 새 production/test source 수정, 빌드, 테스트, 분산 E2E 실행 또는 성능 측정을 수행하지 않았다. 문서 작성 시 코드 참조·관련 링크·형식을 확인했다.

## 7. 관련 문서

- [현재 블록 간 배치 제약](CURRENT_CROSS_BLOCK_PLANNING_CONSTRAINTS_2026-10-06_KO.md): 현재 구현이 강제하는 계약. Runtime에서 필요한 최소조건과 구분해서 읽는다.
- [Correlated execution과 계획 완전성 검토](CORRELATED_EXECUTIONS_PLAN_COMPLETENESS_2026-10-06_KO.md): 표현력 제한에 대한 분석.
- [J_v 관계 노드 설계](JOINT_INPUT_RELATION_NODE_DESIGN_2026-10-06_KO.md): 구현하지 않은 확장 제안. 현재 workload의 필요성을 입증한 문서가 아니다.
- [세션 기록](SESSION_ISSUES_2026-10-06.md): 조사 범위, 근거 및 판단의 변화.

[S1]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4594
[S2]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java:1343
[S3]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/runtime/instructions/fed/BinaryMatrixScalarFEDInstruction.java:58
[S4]: /home/mchoi/w1357-paper-aligned-refactor/scripts/builtin/l2svm.dml:91
[S5]: /home/mchoi/w1357-paper-aligned-refactor/scripts/fedplanner/run_matrix_campaign.py:88
[S6]: /home/mchoi/cofee-evaluation/campaign/render_w1357_workload.py:449
[S7]: /home/mchoi/cofee-evaluation/campaign/render_w1357_workload.py:714
[S8]: /home/mchoi/w1357-paper-aligned-refactor/scripts/builtin/steplm.dml:138
[S9]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java:3172
[S10]: /home/mchoi/w1357-paper-aligned-refactor/src/test/java/org/apache/sysds/hops/fedplanner/placement/StepLmDynamicLoopPlacementTest.java:37
[S11]: /home/mchoi/w1357-stage-privacy-test-only-e21cac2-07726cd/harness/sigmod2021-exdra-p523/experiments/code/exp/gnmf_fed.dml:5
[S12]: /home/mchoi/w1357-paper-aligned-refactor/scripts/builtin/split.dml:62
[S13]: /home/mchoi/w1357-stage-privacy-test-only-e21cac2-07726cd/harness/sigmod2021-exdra-p523/experiments/code/exp/P2_PREP.dml:22
[S14]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlannerCandidateSpaceAudit.java:60
[S15]: /home/mchoi/w1357-paper-aligned-refactor/scripts/fedplanner/run_matrix_campaign.py:703
[S16]: /home/mchoi/w1357-paper-aligned-refactor/src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.java:4733
[S17]: /home/mchoi/w1357-paper-aligned-refactor/scripts/fedplanner/run_LAN_docker.sh:1
