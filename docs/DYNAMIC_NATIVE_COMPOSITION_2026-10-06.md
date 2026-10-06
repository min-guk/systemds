# Dynamic native 배치 합성 오류 수정

## 문제 정의

기준 커밋: `d7e88516a128a28be2ebe336fb6e5e97a1971c89`.

2-worker ROW 8×2 입력에 PRIVATE_AGGREGATE를 적용한 다음 프로그램의 analysis가 실패했다.

```dml
if(sum(gate)>0) { T=rev(A); } else { T=rev(A); }
U=exp(T);
print(sum(U));
```

오류는 `One realization cannot mix unproven or physically distinct native worker pools`였다. 기존 `DynamicNativeLayoutCompositionTest.transientReplayPreservesDynamicReverseAuthority`로 재현했다. 이전 native geometry 수정 전 기준 빌드에서도 같은 오류가 확인되어 있었다.

## 원인과 해결

`PlacementRelationClosure.bindDirectNativeCandidateRealizationsMeasured`는 연산 owner와 seed 배치로 continuity 질의를 식별한다. 한 질의는 서로 다른 선택 입력에 따라 여러 증명을 반환할 수 있다. 그러나 결과를 게시할 때도 질의의 동일한 lineage를 사용했다.

재현 로그의 충돌은 분기 뒤 EXP에 대한 두 증명이었다.

- Exact: worker별 `[0,0)→[4,2)`, `[4,0)→[8,2)` 전체 geometry.
- Dynamic: 같은 worker들에 대한 endpoint authority. witness의 비분할 축 1은 추상 표현이며 정확한 열 수를 보장하지 않는다.

두 증명은 합쳐질 수 없는 정확도인데 동일 realization key를 받았다. 생성자 검사가 이를 올바르게 거부한 것이다.

수정은 게시되는 lineage에 **최종 출력 witness의 정규화된 geometry와 exactness**를 포함한다. generation query의 기존 owner+seed identity는 유지한다. TWrite alias도 owner+pool에 exactness를 포함하여 같은 표현상의 충돌을 방지한다.

기존 증명/입력 binding을 유지하며, merge invariant를 완화하거나 후보를 삭제하지 않는다. DP에 새 factor나 source-grounding 검사, runtime fallback을 추가하지 않는다. 앞서 수정한 전체 native output geometry 보존도 그대로 적용된다.

## 검증과 재현

증거 디렉터리: `.omx/dynamic-native-evidence/`.

- `reproduction.log`: 기존 dynamic 5개 중 transient reverse 1개 실패. 임시 진단으로 owner/key/witness/exactness를 확인했으며 진단 코드는 최종 변경에 포함하지 않는다.
- `focused.log`: 수정 후 기존 dynamic 5개, lineage normalization 1개, support-union 6개 모두 PASS.
- `regressions.log`: 추가 회귀를 처음 작성하는 과정의 테스트 컴파일 오류 기록. 최종 성공 로그와 구분한다.
- `regressions-final.log`: 18개 class, 214 tests 중 212 PASS / 기존 skip 1 / 신규 테스트의 잘못된 exact-output 가정으로 failure 1. Production 오류는 재발하지 않았다.
- `dynamic-final.log`, `dynamic-corrected.log`: 테스트 oracle 작성 중 exact-output 및 native-reader binding 표현을 잘못 가정한 실패 기록. Native reader의 reaching writer는 경계 관계에 저장되며 VALUE_MAP support-clause 표현과 다르다.
- `dynamic-verified.log`: 실제 표현에 맞춰 교정한 dynamic class 최종 재검증. 최종 집계는 아래에 기록한다.
- `harness-tests.log`: Python harness 20 tests PASS.

새 Java 회귀는 `A+1`과 `rev(A)`가 만나는 분기에서 정확/동적 writer의 증명을 모두 보존하고, 각 동적 reader의 경계 관계가 두 reaching writer를 모두 포함하는지 검사한다. downstream EXP의 DIRECT source reference가 실제 analysis에서 해석되는지, 동적 endpoint authority와 정확도 제한을 확인한다. 명시적인 materialization과 native publication을 구별한다. 혼합 분기의 최종 native 출력이 exact와 dynamic으로 모두 남아야 한다는 초기 테스트 가정은 잘못되어 제거했다. 양쪽 분기에 공통으로 성립하는 native authority는 동적이며, 정확한 배치로의 명시적 materialization은 별도 대안이다.

Docker harness의 `joint_dynamic_reverse`는 서로 다른 worker에 4×3 PRIVATE_AGGREGATE shard 두 개를 두어 ROW 8×3을 구성한다. 실행된 `rev`, `exp` 모두 계획/실제 FED/FOUT 일치를 요구한다. CP/FED SUM·NORM2·shape에 더해 `sum(rowSums(Z)*seq(1,nrow(Z)))`를 비교하므로 행 순서를 잘못 보존하는 오류도 감지한다. 기존 loop/function case도 함께 검증한다.

```bash
bash scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e \
  --run-id dynamic-native-runtimefix \
  --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/dynamic-native-composition-20261006 \
  --model-proof-class org.apache.sysds.hops.fedplanner.placement.DynamicNativeLayoutCompositionTest \
  --case joint_dynamic_reverse --case joint_loop_toggle --case joint_function_calls
```

## 실제 실행에서 드러난 REV lowering 누락

첫 Docker 실행은 분석/DP 선택과 model proof를 통과했지만, REV에서 `plannedPhysical=FED/FOUT/ROW actual=FED/NONE` lowering mismatch가 발생했다. `Transform.getInstructions`가 REV의 `_fedOutput` flag를 명령 문자열에 쓰지 않았기 때문이다. HOP→LOP 전달과 FED parser의 flag 해석은 이미 구현되어 있었다.

`Transform.java`의 기존 DIAG flag 직렬화 조건에 REV를 포함했다. FOUT/LOUT 모두 Transform→FED parser 왕복 테스트를 추가했으며, 수정 전 FOUT→NONE 실패와 수정 후 통과를 확인했다. 관련 Reorg/Reshape 및 dynamic/geometry 회귀도 통과했다. 런타임 검사를 완화하거나 fallback을 추가한 변경은 아니다.

- `rev-lowering-red.log`: FOUT→NONE 재현.
- `rev-lowering-green.log`: 6개 class 인접 회귀 PASS.
- 첫 Docker 결과: `.../dynamic-native-final/result.json` (dynamic case FAIL, loop/function PASS).
- 최종 Docker 결과: 아래 최종 결과 참조.

## 잔여 범위와 위험

Replay key 변경이 입력 참조/후보 보존에 영향을 줄 수 있으므로 complete-space, support-union, loop, joint-boundary, canonicalization 테스트를 포함한다. 모든 연산/배치의 완전성 또는 성능 향상을 주장하는 변경은 아니다. Factor overflow/메모리 및 기존 P1/GLM 등 별도 통합 이슈는 이번 수정 범위 밖이다.

## 최종 결과

- Java 20개 class의 최신 결과 합계: **230 tests / 229 PASS / 기존 skip 1 / failure·error 0**. 18개 class 확대 실행 후 신규 oracle을 교정해 dynamic class를 재실행했고, REV lowering 변경 뒤 관련 6개 class를 재실행한 합계다. 모든 class를 마지막 변경 뒤 한 번의 명령으로 돌린 결과는 아니다.
- Python harness **20/20 PASS**. 누락된 weighted fingerprint와 rev/exp FED 실행 증거를 거부하는 negative 검사 포함.
- HEAD `d7e88516a1`의 `PlacementRelationClosure`를 별도 class 디렉터리에 컴파일하여 최종 dynamic 6 tests에 적용하면 원래 transient reverse와 신규 mixed branch가 동일 합성 오류로 실패한다. 나머지 4 tests PASS. `baseline.json`, `baseline-new-tests.log`.
- Production 및 최종 oracle 독립 read-only review CLEAR. `git diff --check` PASS.
- Docker **3/3 PASS**: `joint_dynamic_reverse`, `joint_loop_toggle`, `joint_function_calls`. Model proof 6/6, frozen class preflight PASS, runtime conversion 위반·audit error 0.
- Reverse 결과: 8×3, SUM `124.07728482348034`, NORM2 `1267.9351982067865`, WEIGHTED `571.954806162415`; CP/FED 일치. REV와 EXP의 실제 FED/FOUT/ROW 실행 audit MATCH.
- 결과: `/grid/3/cofee-lm-sweep-mchoi-20260914/dynamic-native-composition-20261006/dynamic-native-runtimefix/result.json`.
- 최종 변경 Java source 및 main/test class bytes가 성공한 Docker의 frozen artifact와 일치함을 확인했다. 전체 집계·SHA·결과: `.omx/dynamic-native-evidence/validation.json`; JUnit XML 사본: `surefire/`.
- 이번에 지정한 dynamic 합성 오류와 검증 중 드러난 REV 출력 계약 누락은 해결됐다. 다른 기존 통합 실패는 별도 범위다.
