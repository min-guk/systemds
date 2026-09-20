# G009 기준선 우선 전체 최적화 결과

- 작성일: 2026-09-20
- 기준 계획: `G009_BASELINE_FIRST_FULL_OPTIMIZATION_PLAN_2026-09-20.md`
- 최종 production 결정: **B0 + R1-A만 채택**
- 상태: **구현·screening·통합·실제 GLM 의미 검증 완료**
- 통계 주의: 사용자의 마지막 지시에 따라 탐색 후보는 1회 screen으로 전환했고, byte-identical 최종 후보에 대해서는 이미 완료된 R1-A 3-pair 결과를 재사용했다. 원 계획의 별도 18-pair holdout은 실행하지 않았으므로 이 문서는 18-pair 통계적 확정 보고서가 아니다.

## 1. 결론

현재 `origin/main`의 C0 묶음은 동시점 B0보다 느렸다. 따라서 C0를 새 기준선으로 삼지 않고 B0에서 각 변경을 다시 분리했다.

최종적으로 채택한 새 변경은 `ExactPhysicalModel`의 **owner별 candidate rule index(R1-A)** 하나다.

| metric | B0 median | final median | final/B0 | 변화 |
|---|---:|---:|---:|---:|
| `Tplanning_full_initial` | 39.475155 s | 37.952445 s | 0.961426 | **-3.857%** |
| `CandidateE2E` | 37.713625 s | 36.036976 s | 0.955543 | **-4.446%** |
| process max RSS | 2,053,220 KiB | 2,071,540 KiB | 1.008923 | +0.892% |
| cgroup peak | 2,121,105,408 B | 2,140,401,664 B | 1.009097 | +0.910% |

세 pair의 ratio는 다음과 같다.

- full planning: `0.984819`, `0.922903`, `0.961426`
- CandidateE2E: `0.987984`, `0.929976`, `0.955543`
- 즉 두 metric 모두 3/3 pair에서 B0보다 빨랐고, median 개선은 2% gate를 넘었으며 memory 증가는 5% 이내였다.
- final CandidateE2E 관측값은 `36.948369`, `35.417709`, `36.036976`초로 모두 60초 미만이었다.
- 역사적 10배 목표에는 도달하지 못했다. 현재 개선은 약 `1/0.955543 = 1.0465x`다.

증거:

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/
  g009-baseline-first-r1a-pilot-20260920193441/summary.json
```

## 2. 기준선과 C0 재평가

새 full-planning receipt와 수정된 proof binding으로 B0/C0를 다시 3-pair 측정했다.

| metric | B0 median | C0 median | C0/B0 | 판정 |
|---|---:|---:|---:|---|
| `Tplanning_full_initial` | 39.166764 s | 43.583386 s | 1.112765 | C0 **11.276% 회귀** |
| `CandidateE2E` | 37.437663 s | 41.665681 s | 1.112935 | C0 **11.293% 회귀** |
| RSS | 2,054,684 KiB | 2,100,928 KiB | 1.022507 | memory gate 내 |
| cgroup | 2,122,149,888 B | 2,173,190,144 B | 1.024051 | memory gate 내 |

세 pair 모두 두 시간 지표가 회귀했다. 회귀의 주된 median phase는 optimizer `+3.446100 s`, analysis `+1.636623 s`였다. C0 전체는 production에서 제외했다.

증거:

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/
  g009-baseline-first-m0-pilot-r4-20260920191254/summary.json
```

과거 `716.591802초`는 host diagnostic evaluator이며 현재 Docker full planning이나 CandidateE2E의 분모로 사용하지 않았다.

## 3. 항목별 전수 판정

### 3.1 B0에 이미 포함된 P1/P2

다음 항목은 이번 campaign에서 새 변경으로 더하지 않고 B0 내재 구현으로 확인했다.

| item | B0 구현 근거 | 판정 |
|---|---|---|
| P1-1 relocation assignment streaming | `NeutralPlacementGraphBuilder.enumerateBindingAssignments`, relocation prefix/leaf counter | `ACCEPTED_BASELINE_INHERENT` |
| P1-2 input tuple streaming | `enumerateInputCombinations`, input prefix/leaf counter | `ACCEPTED_BASELINE_INHERENT` |
| P2-1 product descriptor dedup | `NativePlacementContinuity`의 descriptor expanded/reused counter | `ACCEPTED_BASELINE_INHERENT` |
| P2-2 analysis-scoped structural sharing | factorized proof/binding/list reuse counter | `ACCEPTED_BASELINE_INHERENT` |
| P2-3 structural handle와 lexical boundary 분리 | `PlacementIdentity.structuralHandle`, canonical comparator/rank boundary | `ACCEPTED_BASELINE_INHERENT` |
| P2-4 alive/live-count와 최종 compaction | `NativePlacementContinuity` dead queue, liveCounts, owner compaction counters | `ACCEPTED_BASELINE_INHERENT` |

이 항목은 B0 구성 요소이므로 이번 final/B0 speedup으로 다시 세지 않았다.

### 3.2 새 구현/prototype 결과

| item/bundle | 구현 또는 prototype | 정확성/빌드 | GLM 결과 | production 판정 |
|---|---|---|---|---|
| P1-3 raw proof sink | `3b1ca3fcdf` | focused test/package PASS | full `+14.013%`, CandidateE2E `+15.389%` | `REJECTED_WITH_EVIDENCE` |
| P1-4 proven-empty prefix rejection | `23ce32b05d` | focused test/package PASS | full `+1.083%`, CandidateE2E `+1.568%`; noisy | `REJECTED_WITH_EVIDENCE` |
| R1-A DP owner grouping | `1dbaea4822` | owner ordinal/authority/work test PASS, package PASS | full `-3.857%`, CandidateE2E `-4.446%` | **ACCEPTED** |
| P3-A + R1-B exact-context/index lifetime | `3d772a7106` | 67 PASS, 1 skip; package PASS | full `+4.309%`, CandidateE2E `+4.443%` | `REJECTED_WITH_EVIDENCE` |
| R2-1..5 exact incremental/no-op relation | `5ee3589946` | 79 PASS, 6 skip; package PASS | available medians full `-0.221%`, CandidateE2E `+0.183%`; pair 방향 불안정 | `REJECTED_WITH_EVIDENCE` |
| P3-B + R3-B influence cone/delta recombination | `c53b37851d` | 79 PASS; package PASS | full `+6.913%`, CandidateE2E `+7.722%` | `REJECTED_WITH_EVIDENCE` |
| R3-A exact physical authority reuse | `25c9139e5d` | 47 PASS, 2 skip; package PASS | 1회 full `-2.076%`, CandidateE2E `-2.345%`, 그러나 GLM `17 attempts / 0 hits` | `REJECTED_WITH_EVIDENCE` |
| R4 outer-epoch topology reuse | `df8fb6efe1` | 40 PASS; package PASS | full `+11.314%`, CandidateE2E `+11.408%`, memory `+11.9%` | `REJECTED_WITH_EVIDENCE` |
| P4 + R5-A/B/C factor-aware consumer/selected receipt | `6aa6ab65d5`, activation `a1ddbe9b42` | 116 + activation 5 PASS; package PASS | full `+24.062%`, CandidateE2E `+25.280%` | `REJECTED_WITH_EVIDENCE` |
| P5/R6 integration | B0 + R1-A, exact tree/JAR/stage 동결 | planning proof와 actual runtime semantic PASS | faster-than-B0, <60 s, memory PASS | **ACCEPTED under fast-loop policy** |

R3-A는 단일-run 숫자만 보면 빨랐지만 채택하지 않았다. 전체 GLM script를 같은 분석 빌더와 metrics로 실행한 결과:

```text
R3_HIT_DIAG elapsedNanos=21617825115 attempts=17 hits=0
```

즉 실제 재사용이 한 번도 발생하지 않았으므로 -2% 관측치를 알고리즘 효과로 귀속할 수 없다.

## 4. 빠른 feedback-loop 정책

원 계획은 bundle별 3-pair와 final 18-pair를 요구했다. 실행 도중 사용자가 “한 번만 실행해도 충분”하다고 지시했으므로 이후 정책을 다음과 같이 변경했다.

1. 새 exploratory bundle은 공통 B0 1회와 후보 1회로 screen한다.
2. 이전에 이미 끝난 3-pair 결과는 폐기하거나 다시 실행하지 않는다.
3. 최종 후보가 R1-A와 byte-identical이므로 R1-A의 기존 3-pair를 최종 engineering acceptance에 재사용한다.
4. 별도 18-pair는 실행하지 않는다. 따라서 `r_(13)` confidence gate는 계산하지 않는다.

이 변경은 실행 시간을 줄이지만 표본 불확실성을 키운다. 이 문서의 “채택”은 현재 사용자가 선택한 빠른 engineering gate 기준이며, 원 계획의 18-pair 통계적 확정과 동일한 주장이 아니다.

## 5. 최종 동결 identity

최종 source는 R1-A qualification tree와 byte-identical하다.

| identity | 값 |
|---|---|
| final source commit(실험 worktree) | `9e23015b908c77d7d92ebe88bfd89a662949d245` |
| qualified R1-A commit | `49b9a35dbd2239140ac9a91b16561cb842b6d739` |
| source tree | `9ecbdb2c4425afb3af62281040806afd3d3b68c8` |
| stage id | `c5c5015b4be7576cf72372e6b0a569dff95f67be43a070b34a2f1dbfa1138129` |
| SystemDS JAR SHA-256 | `5ab1fe15bcfd76324291ac0683a04bdff02d08eb2c14e4eb04056ce9b1e66246` |
| harness commit | `713500f46b7a13b79c3a99a04b0a061df6b76434` |
| data tree SHA-256 | `0a7066c7dbb6964292d60820115b87f9368d3a6171bdc2dfbe1f5d599bf07e5f` |
| reference tree SHA-256 | `fcd015051e214847969f1491910efd58e336763511c56d41da3f489bf375c87b` |

Stage:

```text
/home/mchoi/g009-p0-stage-c5c5015b4be7576cf72372e6b0a569dff95f67be43a070b34a2f1dbfa1138129
```

현재 integration worktree의 `src/main`과 `src/test`도 이 final tree와 byte 단위로 같다. 문서와 commit ancestry만 다르다.

## 6. 실제 GLM runtime 의미 검증

Planning-only timing과 별도로 final JAR로 strict runtime-plan audit가 포함된 G009 GLM runtime campaign을 한 번 실행했다. 한 campaign 안에서 cold와 fresh-coordinator-JVM warm phase를 순차 실행한다.

| phase | SystemDS execution time | output SHA-256 | prediction NRMSE | scaled objective gap | semantic |
|---|---:|---|---:|---:|---|
| cold Docker E2E | 16.737 s | `692cfc...496f` | `8.873634e-12` | `1.196800e-17` | PASS |
| warm fresh coordinator JVM | 13.992 s | `692cfc...496f` | `8.873634e-12` | `1.196800e-17` | PASS |

두 tolerance는 모두 `1e-6`이며, runtime fallback/error/timeout/resource-invalid는 모두 false였다. cold/warm output hash도 동일했다. 이 16.737/13.992초는 **실제 GLM execution metric**이고 37.952초 full initial planning이나 36.037초 CandidateE2E와 합치지 않는다.

증거:

```text
/home/mchoi/g009-p0-stage-c5c5015b4be7576cf72372e6b0a569dff95f67be43a070b34a2f1dbfa1138129/
  results/phase-bundles/cell-3/cold-docker-e2e/
  results/phase-bundles/cell-3/warm-fresh-coordinator-jvm/

/grid/3/cofee-lm-sweep-mchoi-20260914/
  g009-baseline-first-final-runtime-20260920234815/runtime-campaign.log
```

## 7. 최종 코드 변경

채택된 알고리즘 변경은 작다.

1. candidate facts를 owner occurrence별 canonical list로 한 번 index한다.
2. 각 decision node는 전체 fact list를 다시 scan하지 않고 자신의 owner list만 본다.
3. legacy full-scan 경로를 test oracle로 남겨 Alternative ordinal, authority source, signature가 완전히 같은지 비교한다.
4. lookup statistics로 owner lookup 수와 실제 examined fact 수 감소를 검증한다.

통합 시 C0에서 추가되었지만 B0 대비 느렸던 factorized relation, proof product, delta closure 관련 production 변경은 제거했다. 관련 결과 문서는 삭제하지 않아 실패 원인과 실험 이력을 보존했다.

## 8. 남은 위험

- 최종 18-pair를 생략했으므로 긴 시간축 drift에 대한 원 계획의 `r_(13)` 통계 보장은 없다.
- R1-A 3-pair는 일관되게 빨랐지만 표본 수는 3이다.
- 실제 runtime은 semantic gate용 1 campaign이며 B0와 runtime 속도를 비교한 실험이 아니다. 알고리즘의 목적도 runtime kernel이 아니라 planning 단축이다.
- 10배 목표는 달성하지 못했다. 현재 최종 개선은 full planning 약 3.9%, CandidateE2E 약 4.4%다.

그럼에도 현재 증거에서 R1-A는 두 planning 지표, memory, 실제 runtime 의미를 모두 통과한 유일한 새 production 변경이다.
