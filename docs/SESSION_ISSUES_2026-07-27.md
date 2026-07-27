# Session Issues — 2026-07-27

## Campaign preregistration rejected its own nested-tree inventory

- **상태**: 해결
- **환경/조건**: G007 Docker-only guarded campaign; SystemDS commit `bb30c9af39b7288e3912a5c468284e918ced89af`; immutable final stage `g007-stage-fb48376bd8e62f607b096f66641d209355f289ef735765dc908051f99bd6f773`; exact 336-cell preregistration build.
- **재현 절차**: `python3 -m unittest scripts.federated_campaign.tests.test_determinism_contract.DeterminismContractTest.test_campaign_preregistration_accepts_canonical_nested_tree_order`; RED log `/tmp/g007-nested-tree-order-red.log` (SHA-256 `8d71bf375eb2c76837a74554dfdffdc3d34b52ac48453d8daa6eb46a9b7a1031`).
- **관측 증상**: `build_campaign_preregistration_manifest`가 생성한 dataset/source-tree inventory를 `validate_campaign_preregistration_manifest`가 `dataset frozen tree order is not canonical`로 거부했다. 실제 데이터의 `ADULT_features.data/0-m-00000`와 `ADULT_features.data.mtd`, runtime tree의 `target/lib/hadoop/bin/...`과 `target/lib/hadoop-annotations-...jar` 조합에서 재현됐다.
- **원인 분석**: `_dataset_records`는 `Path` 객체를 정렬하여 디렉터리 컴포넌트 우선 순서를 만들었지만, `_validate_frozen_tree`는 직렬화된 POSIX 상대경로 문자열의 전역 사전식 순서를 요구했다. 중첩 디렉터리가 없는 기존 fixture는 두 순서의 차이를 드러내지 못했다.
- **해결 요약**: tree inventory 생성 시 `path.relative_to(root).as_posix()`를 명시적 정렬 키로 사용했다. 생성기와 검증기가 동일한 canonical representation을 사용하며 후보 축소, runtime fallback, planner 동작 변경은 없다. 중첩 디렉터리와 동일 prefix의 sibling metadata 파일을 포함한 회귀 테스트를 추가했다.
- **수정 파일**:
  - `scripts/federated_campaign/determinism_contract.py`
  - `scripts/federated_campaign/tests/test_determinism_contract.py`
  - `docs/SESSION_ISSUES_2026-07-27.md`
- **검증**:
  - Focused GREEN: `/tmp/g007-nested-tree-order-green.log`, 1/1 PASS, SHA-256 `353239c6a391a5fe2e900b988e30c68aa3a27170e16d3a962ba3592c634b44fa`.
  - Full campaign-contract GREEN: `/tmp/g007-federated-campaign-full-green.log`, 102/102 PASS, SHA-256 `f87fee06200eda18eb47b6a0dbf533e5d32f2cf2989f368357d11f9606c4916c`.
- **잔여 이슈**: 수정된 계약 모듈을 새 immutable stage에 포함하고 실제 1,032-file dataset 및 321-file runtime tree로 preregistration을 다시 생성·검증해야 한다.
- **잠재 회귀 위험**: manifest tree order와 hash가 이전 flat-only fixture 기준과 달라질 수 있다. 동일 입력 두 번의 manifest hash 일치 및 semantic validator 통과로 감지한다.
- **의사결정 근거**: determinism/oracle 계약 생성기를 검증기와 일치시킨 수정이다. planner/oracle legality, TRead/TWrite, recompile placement, runtime 실행 규칙은 변경하지 않았다.
- **적용 원칙/제약**: Docker-only, 동일 데이터/시드, runtime fallback 금지, 편의상 후보군 축소 금지, 실패 원인의 구조적 소유자 수정.
