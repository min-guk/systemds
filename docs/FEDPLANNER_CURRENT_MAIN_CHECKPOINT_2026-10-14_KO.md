# COFEE main 체크포인트 — 2026-10-14

## 1. 게시 범위와 완료의 의미

사용자의 “현재 cofee를 origin main에 커밋하고 푸시” 요청에 따른 **현재 구현의 체크포인트**다. 모든 workload 지원 완료나 성능 목표 달성을 선언하는 릴리스는 아니다.

- 부모 기준: `b95989bdddd7f4547ba6d77c4659b5ab9f056815`.
- 작업 저장소: `/home/mchoi/fedplanner-under3-20261014`.
- 게시 대상: `git@github.com:min-guk/systemds.git`의 `refs/heads/main`.
- 이 작업 clone의 `origin`은 `/home/mchoi/fedplanner-memo-20261013`이라는 **다른 로컬 저장소**다. 그 저장소의 미커밋 작업을 변경하지 않고 실제 GitHub URL에 정상 fast-forward push한다. force push하지 않는다.
- source/test, 실행 스크립트, 설계·문제 보고서와 선별된 검증 근거를 포함한다. `.omx`, build 결과, 대용량 `experiments/` 작업 디렉터리와 입력 데이터는 게시하지 않는다.

## 2. 포함한 변경

| 영역 | 구현 내용 | 검증/지원 한계 |
|---|---|---|
| 정적 프로그램 분석 | 증명 가능한 단일 반복 PARFOR, scalar/shape 전파, 반복 runtime contract, immutable context 재사용 | 일반 PARFOR 지원으로 확대하지 않는다. 새 실제 전체 matrix 결과는 없다. |
| 입력 geometry | 요청별 axis cut profile, POOLv2와 v1 호환, source 간 충돌 검사, Oracle·indexing의 같은 geometry 사용 | 입력 reshard나 range 덮어쓰기를 하지 않는다. |
| P2 metadata 준비 | 기본값 OFF opt-in, 기존 worker encoder build/merge 재사용, 준비 소유 read/cast/build/cleanup | count-only 수집이 아니다. partial dictionary와 column names의 기존 release 정책을 요구한다. |
| Shape/cost 연결 | 모든 reachable context의 source binding 검증, source/spec identity에 묶인 요청별 proof, multi-output shape 및 비용 연결 | 외부에서 width만 넣거나 다른 source의 proof를 재사용하지 않는다. 실패/UNKNOWN을 exact proof로 바꾸지 않는다. |
| FED runtime | matrix→frame 출력 map의 FRAME 타입 적용, build-only helper, 실패 시 발급한 변수 정리 및 future drain | metadata 준비가 본 transform 실행을 대체하지 않으므로 본 실행 비용을 차감하지 않는다. |
| 평가 도구 | `run_LAN_docker.sh --memo-metadata-planning`, sealed P2 입력 검증, metadata/compile receipt, 격리된 lifecycle | 실제 workload 실행 전이며 아래 §4의 조건 차이를 해소/명시해야 한다. |

상세 계약: [P2 준비](FEDPLANNER_P2_METADATA_ASSISTED_COMPILATION_CONTRACT_2026-10-14_KO.md), [geometry](FEDPLANNER_P2_SOURCE_GEOMETRY_EXTENSION_2026-10-14_KO.md), [단일 반복 PARFOR](FEDPLANNER_EXACT_SINGLE_ITERATION_PARFOR_CONTRACT_2026-10-14_KO.md).

**입력 metadata에 K를 두는 방안:** K를 planning 중 반드시 수집해야 한다는 결론은 아니다. 데이터 버전·전역 범위·encoder의 동등성/결측치 의미·공개 정책이 보장된 column statistics를 입력 metadata로 제공하는 방식이 가능하다. 그 reader/저장 형식/소비 경로는 아직 구현하지 않았다. 현재 포함한 collector는 이를 이용할 수 없을 때 명시적으로 선택하는 대안이다. exact K가 전체 P2 비용 오차의 주원인이라는 실측 근거도 없다. [추가 metadata 요구사항](FEDPLANNER_ADDITIONAL_METADATA_REQUIREMENTS_2026-10-14_KO.md)은 설계 제안이며 구현 완료 보고서가 아니다.

## 3. 이번 검증

근거 디렉터리: [`evidence/main-checkpoint-2026-10-14/`](evidence/main-checkpoint-2026-10-14/).

- `mvn -q -DskipTests test-compile`: PASS. `MAVEN_OPTS='-XX:ActiveProcessorCount=4 -Xmx4g'` 적용.
- Java JUnit: **114 classes / 779 tests PASS**. JUnit 보고 시간 114.758초, wrapper wall 116.163초. 이 시간은 회귀 테스트 시간이지 workload planning 성능이 아니다. [실행 인자](evidence/main-checkpoint-2026-10-14/regression-command.json), [결과](evidence/main-checkpoint-2026-10-14/regression-result.json), [원본 로그](evidence/main-checkpoint-2026-10-14/regression.log).
- 최초 Python 전체 검사: **670 tests, 2 failures + 16 errors, 1 skipped**. 동일 부모 commit의 별도 작업 트리는 **661 tests, 동일한 18개 실패, 1 skipped**였다. 저장소 경로만 정규화하면 traceback과 오류 메시지도 동일했다. 신규 metadata harness 9개는 PASS. [비교](evidence/main-checkpoint-2026-10-14/python-tests-comparison-summary.json), [현재 로그](evidence/main-checkpoint-2026-10-14/python-tests.log), [기준 로그](evidence/main-checkpoint-2026-10-14/python-tests-baseline-same-parent.log). 외부 sealed fixture/manifest 및 경로 의존 실패를 숨기거나 임의 갱신하지 않았다.
- 게시 전 리뷰에서 metadata receipt의 stderr 누락, health/resource 증거 수집 실패 후 성공 판정, container 시작 전 실패 시 UUID namespace 정리 누락을 발견했다. 수정과 후속 회귀 결과는 아래 최종 검증 절에 기록한다.

재현: repository root에서 Maven 명령을 수행한 다음 다음과 같이 보존된 JUnit 인자를 실행한다. Python 전체 검사는 외부 evaluation fixture가 필요한 테스트를 포함한다.

```bash
python3 - <<'PY'
import json, subprocess
from pathlib import Path
args = json.loads(Path('docs/evidence/main-checkpoint-2026-10-14/regression-command.json').read_text())
raise SystemExit(subprocess.run(args).returncode)
PY
python3 -m unittest discover -s scripts/fedplanner/tests -p 'test_*.py'
bash -n scripts/fedplanner/run_LAN_docker.sh
git diff --check -- . ':(exclude)docs/evidence/**'
```

## 4. 미완료 항목 및 비교 주의사항

1. **현재 코드의 실제 P2 workers=1/3/5/7 planning/fullcompile·10초 목표·학습 runtime 결과 동등성은 미검증**이다. compile-only 성공과 본 workload 실행 성공도 다르다.
2. 기존 main `b95989b`의 실제 LogReg/GLM under10 결과는 이전 코드에 대한 증거다. 이후 candidate06에서는 GLM W3가 **10.920863초**였고, 당시 전체 14개 중 5개만 fullcompile 성공했다. 해당 기록을 새 체크포인트의 성능 보장으로 사용하지 않는다. [과거 actual 결과](FEDPLANNER_ACTUAL_WORKLOAD_SUPPORT_STATUS_2026-10-14_KO.md).
3. 새 metadata lane은 기존 multihost adapter를 재사용하여 **so007 coordinator, cpuset 0–3, LAN netem 1ms/5000Mbit** 조건이다. 기존 local coordinator/CPU quota 기반 no-RPC compile lane과 동일 조건이 아니다. 이를 manifest의 `lifecycleAdapterConstraint`로 공개하며 서로의 시간을 직접 같은 조건의 결과처럼 비교하지 않는다. 이 lane을 실제로 실행하지 않았으므로 P2 수집 시간도 없다.
4. metadata preparation은 compile 구간 내부 시간으로 별도 receipt를 내며 전체 compile 시간에 이미 포함된다. 중복 가산하거나 전체 시간에서 빼지 않는다. worker 배치·데이터 검증·Docker 시작 비용은 별도 wall/lifecycle 비용과 구분한다.
5. source 수명은 cooperative sealed input 계약이며 filesystem snapshot isolation이 아니다. 요청 간 mutable proof/cache를 공유하지 않는다. 새로운 입력 column-statistics reader나 persistent cache도 없다.
6. general PARFOR, SliceLine 데이터 의존적 중간 shape, ALS timeout 등 지원 경계는 남아 있다. 단위 테스트 통과를 actual workload 완료 증거로 대신하지 않는다.

## 5. 게시 직전 최종 검증

- 수정 후 root 재검증: **신규 harness 15 tests PASS**; Python 전체 **676 tests, 2 failures + 16 errors, 1 skipped**. 기준의 18개 실패와 ID/traceback이 동일하며 신규 실패는 없다. [최종 비교](evidence/main-checkpoint-2026-10-14/python-final-comparison.json), [전체 로그](evidence/main-checkpoint-2026-10-14/python-tests-final.log), [harness 로그](evidence/main-checkpoint-2026-10-14/python-harness-final.log).
- 최종 독립 code-reviewer: 수정 범위 **APPROVE, 남은 finding 0**. [검토 기록](evidence/main-checkpoint-2026-10-14/review-summary.md).
- `py_compile`, `bash -n`, source/test/scripts/보고서의 staged whitespace 검사 PASS. Raw evidence의 원래 공백·개행은 hash 보존을 위해 수정하지 않으며 whitespace 검사 대상에서 `docs/evidence/**`만 제외한다. Java 검증 뒤 source/test 33파일의 SHA가 불변임을 확인했다. [Java fingerprint](evidence/main-checkpoint-2026-10-14/java-tested-source-sha256.json), [실행 도구 fingerprint](evidence/main-checkpoint-2026-10-14/tested-script-sha256.json).
- 변경 문서의 로컬 링크 검사에서 새 링크 누락은 없었다. 기존 통합 spec의 누락 참조 8건(7개 파일)은 이번 변경 이전에도 존재하며 이 체크포인트에서 복구하지 않았다.
- GitHub main의 최종 commit SHA는 push 후 원격 `refs/heads/main`과 로컬 HEAD를 비교해 확인하며 사용자에게 보고한다. 이 문서는 게시 대상 commit의 상태를 기록하며 push 성공을 미리 가정하지 않는다.
