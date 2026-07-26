# Session Issues — 2026-07-26

## G007 성능 pilot 자원 증거가 local/archive resume 사이에서 보존되지 않음

- **상태**: 해결
- **환경/조건**: Docker-only G007 campaign, performance evidence v2, 120-row pilot, local atomic ledger 및 HDFS archive eviction/resume
- **재현 절차**: performance success를 원장에 commit한 뒤 HDFS archive로 이동하고 local raw bundle을 eviction한다. 기존 receipt/normalized evidence에서 `archive_bytes`만 남고 local regular-file footprint와 전체 lifecycle wall time은 사라졌다.
- **관측 증상**: pilot P95 계산이 local 경로에서는 파일을 다시 세어 얻은 값, archive-only resume에서는 TAR 크기와 `cold_seconds + warm_seconds`로 대체될 수 있었다. 두 값은 동일한 측정량이 아니다.
- **원인 분석**: evidence v2 bundle manifest와 shared replicate manifest가 phase별 cold/warm 시간만 보존했고, archive receipt가 TAR 크기만 기록했다. pilot selection과 resource reservation 사이에도 exact 120-row evidence lineage가 없었다.
- **해결 요약**:
  - performance shared manifest에 runner가 setup 직전부터 validation 및 strict teardown/zero-resource 완료까지 측정한 `lifecycle_wall_seconds`를 필수화했다.
  - atomic ledger가 최종 `bundle_manifest.json`까지 포함한 regular-file `artifact_bytes`/`artifact_inodes`를 고정점 계산으로 기록하고 매 validate/resume 시 실제 tree와 재검증한다.
  - HDFS receipt가 동일 `resource_evidence`를 self-hash에 포함하고 remote archive 크기/hash, extracted manifest, local/archive parity를 재검증한다.
  - normalized/exact-resume evidence가 동일 resource evidence를 그대로 노출한다.
  - pilot selection row/inventory가 resource evidence를 포함하며, canonical reservation v2가 exact selection/row/inventory hashes에서 nearest-rank P95와 고정 1.20 margin을 계산한다. final manifest는 다른 selection에서 만든 reservation을 거부한다.
- **수정 파일**:
  - `scripts/federated_campaign/atomic_ledger.py`
  - `scripts/federated_campaign/hdfs_archive.py`
  - `scripts/federated_campaign/determinism_contract.py`
  - 관련 `scripts/federated_campaign/tests/`
- **검증**: 전체 Python suite, `py_compile`, six-file `basedpyright`, `git diff --check`; interrupted archive 후 archive-only exact resume와 adversarial resource/receipt/cross-pair mutation 테스트 포함.
- **잔여 이슈**: 실제 Docker runner가 의미상 정확한 구간에서 `lifecycle_wall_seconds`를 측정해 shared manifest에 기록해야 한다. 계약 계층은 값의 구간 의미를 자체적으로 측정할 수 없으므로 runner integration 검증이 필요하다.
- **잠재 회귀 위험**: 기존 v2 performance producer가 새 필드를 누락하면 fail-closed된다. shared manifest schema 테스트와 archive-only resume parity 테스트로 감지한다.
- **의사결정 근거**: planner/runtime 규칙은 변경하지 않았다. runtime fallback 없이 evidence/oracle contract와 archive lifecycle만 강화했다.
- **적용 원칙/제약**: Docker-only, runtime fallback 금지, stale/partial evidence 금지, 비용 최적화 이전에 실제 측정량을 고정한다.
