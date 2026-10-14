# 실제 LogReg·GLM: planning 및 전체 compile 10초 이내 결과

## 1. 결론

**실제 sealed LogReg·GLM × planning worker 1·3·5·7, 총 8조건 모두 통과했다.**
각 조건은 최종 engine에서 **1회씩** 실행했으며, 전부 `OPTIMAL`, `RuntimeProgram` 생성 완료,
`executionSeconds=0`이다. 작은 E2E의 시간을 실제 workload 성능으로 대체 보고하지 않았다.

- 전체 compile: **LogReg 3.429–3.982초, GLM 6.239–8.499초**.
- 전체 initial planning: 최대 **8.480969256초**.
- Docker 실행 호출부터 종료까지의 harness wall time도 최대 **9.532초**였다.
- Java 회귀 **700개**, 변경 하네스의 Python 회귀 **70개** 통과.
- 원본 [acceptance.json](evidence/actual-ml-under10-2026-10-14/acceptance.json): `passed=true`, `acceptedCells=8`, 오류 없음.

이 결과는 **runtime 실행 전까지**의 결과다. 전체 ML training 실행 시간이 10초라는 뜻은 아니다.
한 번씩 측정하라는 요구에 맞춘 단일 측정이므로 평균·p95·반복 실행 분산을 주장하지 않는다.

## 2. 실제 측정 범위와 시간

입력은 기존 sealed stage의 실제 ADULT feature **50,000×128**, label **50,000×1**이다.
LogReg는 binary view, GLM은 continuous view를 사용한다. feature의 `private-aggregate`,
label의 public metadata 및 원본 DML·seed·range·worker endpoint를 변경하지 않았다.

| Workload | Planning workers | 전체 initial planning (s) | 전체 compile (s) | Optimizer (s) | Docker 호출 wall (s) |
|---|---:|---:|---:|---:|---:|
| LogReg | 1 | 3.588281020 | 3.602665 | 0.668912 | 4.705237 |
| LogReg | 3 | 3.537060235 | 3.545233 | 0.676106 | 4.721293 |
| LogReg | 5 | 3.964220586 | 3.982456 | 0.790017 | 5.210451 |
| LogReg | 7 | 3.414508382 | 3.428583 | 0.754656 | 4.556527 |
| GLM | 1 | 6.223617742 | 6.238675 | 1.435232 | 7.413669 |
| GLM | 3 | 8.480969256 | 8.499098 | 2.284437 | 9.532364 |
| GLM | 5 | 7.589684572 | 7.603589 | 2.072023 | 8.732560 |
| GLM | 7 | 6.843516872 | 6.864629 | 1.983015 | 8.146626 |

- `fullInitialPlanningSeconds`: DML parse 시작부터 HOP/LOP 및 `RuntimeProgram` 구성 완료까지.
- `fullCompileSeconds`: 위 구간에 compile 통계 준비와 runtime explain 출력까지 포함한다.
- `Optimizer`: Memo 내부 최적화 단계만이다. 이 값만으로 목표 통과를 판정하지 않았다.
- `harnessWallSeconds`: Docker 호출–종료 구간. 사전 sealed input hash 검증 및 사후 acceptance 검증 시간은 제외된다.
- compile-only이므로 실행 worker JVM/RPC를 시작하지 않는다. planning workers는 sealed source의 실제 worker topology를 의미한다.

환경은 기존과 동일한 pinned Docker image, coordinator **4 CPU / container 8 GiB / heap 3 GiB**,
`native BLAS=none`, codegen off, production 기본 비용, cost environment override 없음이다.
시스템의 다른 사용자 작업을 중단하지 않았고, 측정 중 이 세션의 빌드/테스트는 실행하지 않았다.

원본 [summary.csv](evidence/actual-ml-under10-2026-10-14/summary.csv),
[summary.json](evidence/actual-ml-under10-2026-10-14/summary.json),
[실행 manifest](evidence/actual-ml-under10-2026-10-14/manifest.json).

## 3. 300초 timeout의 근본 원인과 해결

이전 실제 8조건은 모두 300초 제한 timeout이었다. 별도 실제 W3 JFR 진단에서
LogReg main CPU sample의 **97.54%**, GLM의 **92.98%**가 forest-tail 경로에 있었다.
원본 근거는 [실제 workload timeout 원인 보고서](FEDPLANNER_FULL_WORKLOAD_TIMEOUT_ROOT_CAUSE_2026-10-14_KO.md)에 있다.

### 3.1 반복되는 forest DP를 exact bucket elimination으로 대체

`MemoOptimizer`는 기존 legality/cost factor graph에서 cyclic core의 각 assignment마다
forest-tail DP를 다시 계산했다. primitive cache가 있어도 **부분문제의 최적 비용과 선택 결과**를
재계산하므로 실제 큰 cyclic workload에서 폭발했다.

새 경로는 정규화된 immutable factor graph에 **bounded exact bucket elimination**을 적용한다.

1. owner를 제거하면서 관련 original factor와 기존 message를 각각 한 번 소비한다.
2. 남은 separator 값에 대한 정확한 최소 비용과 선택 backpointer를 message로 저장한다.
3. 동일 separator 관측을 가진 상위 assignment는 이 message를 재사용한다.
4. 원래 factor의 Truth/비용도 class-local 배열에 보관하여 작은 전역 cache의 churn을 피한다.

단순히 모든 owner identity를 하나로 합친 것이 아니다. **해당 bucket이 실제 소비하는 factor들의
인증된 축별 class의 교집합**만 separator 압축에 사용한다. 인증이 없는 축은 실제 ordinal로
구별한다. 미래에 평가할 native cost는 이 message의 동치 조건에 끼워 넣지 않으며,
제거하는 owner의 모든 live ordinal은 그대로 평가한다.

W3의 elimination 작업량은 LogReg **14,744 local evaluations / 2,378 message rows**,
GLM **1,360,977 / 229,309**다. 원래의 core 전체 조합마다 같은 forest를 다시 푸는 방식과 다르다.
source Cartesian product를 다시 만들거나 Oracle generation을 우회하지 않는다.

### 3.2 전체 compile에서 드러난 physical supply 중복 수정

최적화가 빨리 끝난 첫 후보는 이후 `Duplicate selected supply edge`로 실패했다.
기존 `MemoProgramModel.reconstructSupplies()`가 동일 compiled Hop input의 각 occurrence마다
별도 Supply를 생성했기 때문이다. 이 실패를 planning 성공으로 포장하지 않았다.

수정은 physical edge별로 **모든 occurrence의 source recipe를 먼저 합친 뒤 Supply 하나를 emit**하는 것이다.
DIRECT-only 생략은 전체 union 이후에만 수행하므로, 예를 들어 LOCAL/UPLOAD와 ROW/DIRECT가
동시에 필요한 shared input에서 DIRECT 조건이 사라지지 않는다.

- 다른 physical edge는 합치지 않는다.
- target, 동일 source의 movement/worker recipe 충돌, IMPOSSIBLE은 계속 fail-closed 한다.
- 각 use/context의 legality와 activation은 그대로 검사한다.
- schedule은 여전히 모든 use/frame의 정확한 origin identity·retention scope로 action을 만든다.
- 비용 계산이나 서로 다른 동적 value의 비용 소유권을 합치지 않는다.

## 4. 정확성 보존

| 항목 | 보존 방법과 검증 |
|---|---|
| Feasibility / privacy / worker binding | 기존 factor와 context legality를 소비하며 후보·검사를 임의 생략하지 않음. 실제 8조건 및 기존 privacy/worker/runtime 회귀 통과 |
| UNKNOWN / DEFER | coverage가 불완전하면 기존 UNRESOLVED 유지. elimination 도중 UNKNOWN이면 부분해를 OPTIMAL로 공개하지 않고 기존 exact 경로로 복귀 |
| 정확한 비용 | binary64 primitive를 기존 exact `BigDecimal` 의미로 합산. native 및 original factor 각각 한 번. `MemoProgramModel`의 원비용 재계산 audit 유지 |
| 전역 최적해 / 동률 | 모든 live ordinal 평가. 동일 비용이면 descendant witness를 복원하여 전체 owner 순서의 lexicographic optimum 선택 |
| 자원 한계 | 실행 전 최대 2,000,000 local cells / 500,000 factor+message rows 검사. 넘으면 기존 exact solver 사용, 근사해로 전환하지 않음 |
| 기존 finite node budget | 기존 탐색 경로 유지. legacy forest/pseudoforest/cache counter 회귀는 해당 경로를 명시적으로 테스트 |

새 독립 brute-force 비교 회귀는 cyclic/unsorted factor, 음수 및 비정수 정확 비용,
class-compressed message, 이전 owner의 lex tie, singleton projection, ordinal 68,
여러 bucket 처리 뒤 UNKNOWN, width cap fallback을 포함한다.

실제 baseline은 timeout 때문에 정답 plan을 얻지 못했다. 따라서 실제 baseline과 완료 plan을
직접 비교했다고 주장하지 않는다. 대신 exact algorithm 불변식 리뷰, 독립 exhaustive 비교,
기존 회귀, 실제 최종 plan의 원비용 audit로 검증했다.

## 5. 작은 성능 workload 삭제 및 실제 evaluator 전환

`run_joint_boundary_e2e.py`에서 다음 활성 경로를 삭제했다.

- tiny builtin `ml_logreg`, `ml_glm` case 등록
- tiny `multiLogReg`/`glm` DML 생성 분기
- `Y_ML_LOGREG` 192행 label fixture
- 과거 tiny 성능 receipt를 비용 계약 권위로 읽던 의존

이름을 재사용하면 새 실제 평가 명령을 안내하며 즉시 실패한다. 과거 실패/성능 evidence는
감사 기록으로 보존하고, 일반 correctness unit graph와 별도 수작성 `ml_logreg_gd` correctness
case는 성능 합격 근거로 사용하지 않는다. 삭제 대상은 이전에 성능 근거로 썼던 tiny builtin ML 경로다.

새 `--actual-ml-planning-performance`는 fresh output root에서 실제 8조건만 각각 한 번 실행한다.
평가기는 pinned renderer로 sealed stage를 다시 검증/렌더링하고 DML·config·input receipt·adaptation을
실제 파일과 정확히 비교한다. sealed template/metadata/data block/privacy/range/topology 검사는
기존 renderer 로직을 재사용한다. engine 전체 file hash와 C26 기준 비용 모델/config bytecode도 검증한다.
Matrix 실행 실패를 후행 gate 결과로 덮어쓰지 않는다.

상세 [하네스 이전 문서](FEDPLANNER_ACTUAL_ML_PERFORMANCE_HARNESS_MIGRATION_2026-10-14_KO.md).

## 6. 검증과 실패 기록

- Maven `test-compile`: 성공.
- Java 106 classes / **700 tests PASS**, JUnit 78.541초. 기존 Memo/soundness, Oracle, lowering,
  retained supply/runtime 회귀 및 신규 exact solver/physical supply 회귀 포함.
- Python 변경 하네스: **45 + 14 + 11 = 70 tests PASS**.
- `bash -n`, `py_compile`, `git diff --check`: 통과.
- optimizer 및 supply 독립 architect review CLEAR, 실제 evaluator code review APPROVE.
- 최종 독립 verifier **PASS / CLEAR**: 원시 coordinator log를 재파싱하여 result JSON 전 필드와 비교, 조건당 receipt 1개, tested class/최종 engine/current source 일치 확인. [완료 audit](evidence/actual-ml-under10-2026-10-14/completion-audit.json).
- [Java 로그](evidence/actual-ml-under10-2026-10-14/candidate02-unit.log),
  [정확한 test command](evidence/actual-ml-under10-2026-10-14/candidate02-unit-command.txt),
  [Python 로그](evidence/actual-ml-under10-2026-10-14/final-python-tests.log).

**전체 Python discovery는 all-pass가 아니다.** 별도로 전체 `test_*.py`를 실행한 결과
661 tests / failures 2 / errors 16 / skipped 1이었다. 외부 legacy fixture·pinned launcher/source
manifest·campaign timeout contract·compact verifier 관련 실패가 남아 있다.
이번 변경 하네스의 70개 테스트 및 Java 700개 soundness/regression은 통과했다.
전체 discovery의 변경 전 동일 환경 비교까지 수행하지 않았으므로 모든 실패를 새 변경과 무관하다고 단정하지 않는다.
[전체 Python 실패 로그](evidence/actual-ml-under10-2026-10-14/full-python-discovery.log)를 보존했다.

두 종류의 실행 실패를 숨기지 않았다.

1. **후보01 actual W3 각 1회:** optimizer 0.799/2.070초였으나 supply 중복으로 compile 실패.
   [실패 evidence](evidence/actual-ml-under10-2026-10-14/candidate01-compile-failure/summary.json).
2. **최초 final packaging 시도:** `target/lib` symlink가 `/grid`로 연결되어 Snap Docker mount가
   8조건 모두 rc125로 실패했다. JVM/성능 측정은 시작되지 않았다.
   [setup 실패 evidence](evidence/actual-ml-under10-2026-10-14/failed-packaging/summary.json).
   기존 evidence는 보존하고 dependency를 실제 파일로 동결한 `evaluation-engine-v2`로 교정했다.
   preflight에 symlink 거부 회귀도 추가했다. 이후 최종 8개 workload 실행은 각각 1회다.

## 7. 재현 방법 및 산출물

```bash
# 원본 workspace: /home/mchoi/fedplanner-under3-20261014
bash scripts/fedplanner/run_LAN_docker.sh --actual-ml-planning-performance \
  --engine "$PWD/experiments/full-ml-under10-20261014/evaluation-engine-v2" \
  --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z \
  --output-root /home/mchoi/full-ml-under10-new-independent-run
```

출력 root는 존재하지 않아야 한다. 위 예시를 기존 측정 재실행으로 자동 수행하지는 않았다.
workload 재실행 없이 최종 evidence만 재검증하려면:

```bash
python3 scripts/fedplanner/evaluate_actual_ml_planning.py \
  --root /home/mchoi/full-ml-under10-final-r2-20261014
```

- 최종 raw root: `/home/mchoi/full-ml-under10-final-r2-20261014`
- 최종 engine: `experiments/full-ml-under10-20261014/evaluation-engine-v2` (**5,303 physical files, 301 JARs**)
- [engine file hash inventory](evidence/actual-ml-under10-2026-10-14/evaluation-engine-v2-sha256.json)
- [source provenance](evidence/actual-ml-under10-2026-10-14/provenance.json)
- [production patch](evidence/actual-ml-under10-2026-10-14/candidate02-production.patch)
- 기준 commit: `f7ca28d75134b62d1cf1eb4eaa7e82371d82f98f` + 위 patch/현재 변경 소스

주요 구현 위치는 `MemoOptimizer`의 `EliminationPlan`, `MemoProgramModel.reconstructSupplies`,
`FederatedPlanMemo`의 elimination trace다. 신규 production dependency나 runtime fallback은 추가하지 않았다.

## 8. 남는 범위와 위험

- 이번 성능 판정은 지정된 실제 8조건에 대한 것이다. 모든 arbitrary cyclic graph의 10초 보장이 아니다.
- width/cell cap을 넘는 문제나 unresolved analysis는 기존 exact 경로를 유지하므로 다시 오래 걸릴 수 있다.
- GLM W3 전체 compile의 10초 대비 여유는 약 1.50초다. 향후 회귀는 같은 실제 evaluator로 검출해야 한다.
- 실제 training runtime 전체 수행은 이번 요청 범위 밖이며, 여기서 그 성공/속도를 주장하지 않는다.
