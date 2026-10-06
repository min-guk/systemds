# Cost-based와 Heuristic 합법성 비교 — 조사 완료, 추가 구현 미실시

조사 기준은 `82b2f63735783ce4a3d35b142bf434d5addc49dd`다. 이 커밋은 앞서 검증한 unbound relocation publication 오류 2건의 수정이다. 이번에는 production/test source를 더 수정하지 않고 코드 비교와 작은 compile/selection probe만 수행했다. 원격 push는 하지 않았다.

**질문:** cost-based planner에서 수행하는 것 중 Heuristic이 합법적인 plan을 고르기 위해 공유해야 할 것이 있는가?

**결론:** 있다. `VALUE_MAP`의 실행 가능한 분기 조합별 worker-pool 정렬 검사는 실제로 공통 합법성 경로에 빠져 있다. Heuristic 선택·정규화·emission 사전 검사가 이를 수락하는 반례를 확인했다. 비용 점수나 cost solver 전체를 가져올 필요는 없으며, 해당 hard relation을 공유해야 한다. WDIVMM runtime 입력 관계의 조기 전파와 greedy의 충돌 복구는 별도 우선순위다.

## 우선순위와 확신

| 순위 | 대상 | 판단 | 근거/확신 |
|---|---|---|---|
| 1 | Joint `VALUE_MAP`의 행별 물리 pool 정렬 | 공통 최종 validator 및 선택 중 legality 지원에 반영할 필요가 확인됨 | **확인된 누락, 높음**. Exact가 거부한 동일 분석을 Heuristic·normalization·emission prevalidation이 수락하며 4행 중 2행이 불일치 |
| 2 | latent/direct WDIVMM 실제 weights 입력 조건 | 기존 공통 predicate를 선택 전에도 전파할 가치가 있음 | **코드상 시점 차이, 높음**. cost-based는 이항 hard factor, Heuristic의 동일 조건은 최종 receipt 검증에 있음. 실제 추가 workload 실패는 미확인 |
| 3 | 조건부 충돌 복구/선택 되돌리기 | 합법 해를 더 많이 찾으려면 검토할 대상 | **불완전 탐색 재현, 높음**. 합법 CP 대안이 있는 3-node 예제에서 AGG_LOCAL도 첫 commit 뒤 실패. 현재 no-backtracking 정책 변경이 필요 |
| 4 | cost surface의 runtime read–source compatibility | 기존 transient/function/alias 관계와의 중복성부터 검사 | **차이 존재, 영향 미확인**. 직접 함수 호출 유무만으로 누락이라고 판정할 수 없음 |

## 확인된 누락: 독립 분기의 VALUE_MAP

X와 Y는 각각 8×2 ROW 배치이고 서로 다른 worker pool에 있다. 두 source 모두 PRIVATE_AGGREGATE인 기존 hermetic fixture를 사용했다. 실제 데이터나 worker를 실행하지 않았다.

```text
상관된 분기: if(p) { A=X; B=X; } else { A=Y; B=Y; }
독립된 분기: if(p) { A=X; } else { A=Y; }
             if(q) { B=X; } else { B=Y; }
공통 소비자: C=A+B
```

상관된 분기는 `(X,X)/(Y,Y)` 두 조합에서 각각 pool이 맞는다. 독립된 분기는 `(X,Y)/(Y,X)`도 가능하므로 DIRECT 입력 두 개를 정렬 없이 함께 실행할 수 없다. 여기서 필요한 것은 모든 분기 값을 한 pool에 고정하는 규칙이 아니라, **각 실행 가능한 조합 내부에서의 정렬**이다.

공개 `DMLTranslator.prepareSearchSpaceOnly(program,false)`로 production과 같은 공통 준비를 거쳐 canonical analysis를 만들었다. `false`는 상세 metrics만 끈다. 두 planner에 동일 analysis를 주고 Heuristic의 normalize 반환값을 실제 `PlacementEmissionTransaction.prevalidate`에 전달했다. reflection은 fixture 접근과 private 검사 메서드 호출에만 사용했으며, authority나 검사 결과를 덮어쓰지 않았다.

| Canonical fixture | Exact legality-only | Heuristic 정규화 | DIRECT pool 불일치 | Emission prevalidate |
|---|---|---|---|---|
| 상관된 분기, 76 decisions | feasible | 통과 | 0/2행 | 통과 |
| 독립된 분기, 93 decisions | `EXACT_VE_NO_FEASIBLE_ASSIGNMENT` | **통과** | **2/4행** | **통과** |

독립 분기의 선택된 소비자는 FED/FOUT/ROW, VALUE_MAP, PRESENT/ROW 입력 두 개, DIRECT binding 두 개다. 선택된 relocation은 0개이므로 LOCAL/broadcast나 relocation target을 누락해서 생긴 오판이 아니다. 네 행 모두 실제 선택 receipt에서 해석됐고 두 행의 pool이 다르다. 수정 전 main `d053a8f24a`의 깨끗한 worktree/classes에서도 같은 결과를 재현했다. 앞선 relocation publication 수정으로 새로 생긴 문제가 아니다.

**원인에 대한 직접 근거:**

- `PlacementRelationClosure.java:1193`은 reader별 VALUE_MAP realization의 Cartesian product를 support clause로 게시한다. exact reference 일치만으로 행별 물리 정렬이 보장되지는 않는다.
- `JointValueMapRelations.java:181`의 `Grounding.rows`는 runtime row마다 선택된 source/value origin을 따라 pool을 해석한다.
- `ExactPhysicalModel.java:1164`의 `addJointFactors`는 이 관계를 hard factor로 넣는다. `:1246`부터 DIRECT 입력의 source pool과 RELOCATION의 target pool을 구분하고, `:1258`부터 모든 행의 `samePhysicalWorkerPool`을 확인한다. LOCAL/broadcast의 원래 source pool을 강제로 맞추지 않는다.
- `CandidateSelections.java:1442`의 exact realization/support 일치 및 transient/boundary 검증은 존재하지만, 위 joint row 정렬 전체를 대신하지 못한다. 이번 probe가 실제 차이를 확인한다.
- production emission이 호출하는 `PlacementEmissionTransaction.java:305`의 사전 검사도 해당 witness를 수락했다. 이 단계 뒤의 runtime-audit registration, 실제 emission commit, Lop lowering, worker 실행은 검사하지 않았다.

**필요한 변경 방향 — 아직 구현하지 않음:** 기존 `Grounding`과 동일한 row별 hard predicate를 공유 legality 경로에서 검증하고, 선택 도중에도 그 관계를 보존해야 한다. 먼저 최종 검사로 잘못된 witness의 수락을 막고, 합법적인 LOCAL/relocation 대안은 선택 전 support propagation 또는 명시적 탐색으로 찾는다. 전역 pool 하나로의 강제 통일이나 VALUE_MAP 전체 금지는 과도한 후보 축소다. 기존 exact observation decomposition을 보존하거나 관계 기반으로 검사하여 거대한 dense factor로 펼치지 않는다.

## 이미 공통인 것과 선택 시점이 다른 것

**확인된 사실:** Heuristic은 placement 상태만 찍고 끝내지 않는다. `PolicyGreedyPlacementSelector.java:420` 이후의 graph state support, reciprocal input requirements, physical pool, relocation privacy, transient/boundary support를 전파한다. `:702`의 `CandidateSelections.resolveAndValidateSelected`, `:706`의 `validateRealizationSelections`와 `:707`의 selected-proof grounding 검사도 수행한다. Exact의 projector 역시 같은 candidate/relocation 검증을 재사용한다(`ExactPhysicalPlacementProjector.java:48`).

source/value version과 materialization authority도 공통 경로에 있다. `RelocationSelections.java:999`의 physical emission identity는 source value version, materialization FType, durable anchor, statement scope를 구분한다. `PlacementEmissionTransaction.java:729`는 LOCAL source version, `:738`은 scope, `:789` 이후는 정확한 relocation demand와 privacy를 검증한다. Heuristic은 cost-based의 반복 실행용 `sharedSupplyLifetimes` 결정을 채우지 않으며 기본값은 빈 집합이다(`NormalizedPlannerResult.java:66`). 비용 과금/OR encoding을 옮겨야 이러한 기존 계약이 생기는 구조는 아니다.

**WDIVMM의 확인된 차이:** `ExactPhysicalModel.java:1057`은 fused runtime 입력을 owner–weights 이항 관계로 묶는다. latent FED owner에는 필요한 FOUT/FType weights가 있어야 하고, direct WDIVMM은 `PlacementCostSemantics.directWdivmmRuntimeAssignmentCompatible`를 사용한다. 공통 최종 receipt 검증에도 같은 의미가 있다(`CandidateSelections.java:1791`, `:1845`, `:1861`). greedy에는 이 관계의 명시적인 사전 인덱싱이 없고, 제거되는 inner→transpose edge는 건너뛴다. closure 주석도 fused weights를 exact model이 별도로 연결한다고 설명한다(`PlacementRelationClosure.java:9831`). 기존 input support가 일부 사례를 간접적으로 제한하므로, 모든 WDIVMM 선택이 잘못된다는 뜻은 아니다. 작은 scope의 기존 predicate를 조기에 공유하는 것이 개선 후보이며 workload별 효과는 아직 측정하지 않았다.

## 합법성을 지키는 것과 합법 해를 찾는 것은 별개다

기존 `PolicyGreedyPlacementSelectorTest.java:120`의 3-node 재합류 fixture를 현재 compiled classes로 다시 실행했다. p를 FED로 선택하면 l은 LOCAL, r은 FED여야 하는데 l과 r은 같아야 한다. p의 CP 대안을 사용하면 합법 해가 있다. 기존 `PolicyFirstFeasiblePlacementSelector`는 그 해를 찾는다. FED_FIRST와 **AGG_LOCAL 모두 첫 commit 뒤 `GreedyConflictException`, `commits=1`, `not global infeasibility`**를 냈다.

따라서 joint legality 검사를 고쳐도 greedy가 모든 feasible plan을 찾아낸다는 보장은 생기지 않는다. cost-based의 `LocalCategoricalOptimizer.java:919` 이후 hard-conflict component repair, unsupported boundary를 푸는 재시도(`:956`)의 원리는 참고할 만하다. 그러나 현재 Heuristic은 의도적으로 no-backtracking이므로 이는 별도 정책 변경이다. 제한된 lookahead/repair도 전역 완전성을 보장하지는 않는다. 이 문제에 monetary cost는 필수가 아니다.

**그대로 가져오지 않을 것:** `RegionalSearchProblem.java:66`의 reduced root에는 hard factors뿐 아니라 cost surface도 들어 있다. `SharedRegionalPreparation.unconditionalDomains` 전체를 Heuristic에 연결하면 cost auxiliary와 quotient 표현까지 따라온다. 공유 hard predicates만 대상으로 삼고, 조건부 경계 때문에 지운 후보는 경계를 풀면 복구해야 한다. FLOP/network 점수, canonical creation-cost OR-chain, 비용 탐색 전체 또는 자동 exact/runtime fallback을 도입해야 합법성이 생기는 것은 아니다.

`ExactPhysicalCostModel.java:2343`의 `runtimeReadCompatibilityFactor`처럼 cost surface 안에 0/+∞ hard 관계가 있는 점은 별도 감사 대상이다. 이는 read FOUT에 대해 resolved source의 FOUT/동일 FType을 요구한다. 현존 transient/function/alias 관계와 겹치며 이번에 별도 반례를 확인하지 않았으므로 확정된 누락으로 집계하지 않는다.

## 증거와 한계

- [기계 판독 결과](experiments/heuristic-legality-20261006/analysis.json)
- [Canonical VALUE_MAP probe](experiments/heuristic-legality-20261006/CanonicalJointLegalityProbe.java), [상관 분기 로그](experiments/heuristic-legality-20261006/canonical-joint-correlated.log), [독립 분기 로그](experiments/heuristic-legality-20261006/canonical-joint-independent.log), [수정 전 main 대조](experiments/heuristic-legality-20261006/canonical-joint-baseline-independent.log)
- [Greedy completeness probe](experiments/heuristic-legality-20261006/GreedyCompletenessProbe.java), [양 policy 결과](experiments/heuristic-legality-20261006/probe.log)
- 원본 command receipt와 초기 detached-analysis 시도는 `/home/mchoi/heuristic-relocation-20261006/.omx/heuristic-legality-analysis/`에 있다. 초기 시도는 양쪽 모두 canonical ownership 검사에서 멈췄으므로 emission 근거로 쓰지 않았고, 이후 canonical 준비로 재검증했다.

이번 조사는 합법성 parity 누락을 찾아냈지만 구현하지 않았다. 실제 emission commit 이후 동작이나 runtime 결과, 큰 workload 실패율·실행시간은 측정하지 않았다. 새 보호 규칙을 추가할 때 상관된 AA/BB, 독립 AB/BA, 명시적 LOCAL/relocation이 있는 합법 대안을 함께 검증해야 과도한 domain 축소를 막을 수 있다. 특히 기존 공통 validator가 cost-based의 모든 hard predicate를 완전히 포함한다는 가정은 이번 반례로 부정됐다.
