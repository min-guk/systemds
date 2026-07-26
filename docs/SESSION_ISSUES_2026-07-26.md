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

## 이슈 2 — Discovery→Pilot 전이 및 P/S/R/F 의미 검증 누락

- **상태**: 해결
- **환경/조건**: Docker-only federated campaign, preregistration P v3, 336-cell discovery, 120-row pilot, HDFS/local exact resume.
- **재현 절차**: 기존 계약에서 self-hash만 다시 계산한 P/S/R를 입력하거나, discovery ledger가 비어 있는 상태에서 `CampaignHarnessAdapter.begin(kind="performance", cell="pilot_class=...")` 호출.
- **관측 증상**: 336개 discovery 성공을 증명하는 전이 영수증 없이 pilot lease를 만들 수 있었고, P/S/R의 파생 수치를 변조한 뒤 self-hash를 다시 만들면 일부 ingress를 통과할 수 있었다.
- **원인 분석**: self-hash 무결성과 의미적 정당성을 혼동했다. 또한 planner-major barrier는 다음 discovery planner만 막았고 discovery 전체 완료와 pilot 시작 사이에는 원자적 계약 경계가 없었다.
- **해결 요약**:
  - 정확히 정렬된 336개 canonical latest-success를 재검증하여 self-hashed `systemds-federated-discovery-completion/v2` 영수증 D를 생성한다.
  - pilot lease는 D와 그 exact SHA-256이 invocation manifest에 없으면 intent 파일 생성 전에 fail-closed 한다.
  - P는 336 cells, 4×84 barriers, seed 19 Williams pilot schedule, frozen core/privacy/topology/lineage/resource invariants를 의미적으로 재검증한다.
  - S v4는 canonical 120 pilot rows를 포함하고 24 groups×5 repeats, order/identity/evidence uniqueness, Q95/eta/repeat 선택을 builder로 재계산하여 exact equality를 요구한다.
  - R v3는 S에서 다시 산출한 nearest-rank P95, margin 1.20, 5GiB floor와 exact equality를 F ingress에서 요구한다.
  - F v4와 S v4 lineage에 D hash를 포함한다.
- **수정 파일**:
  - `scripts/federated_campaign/determinism_contract.py`
  - `scripts/federated_campaign/hdfs_archive.py`
  - `scripts/federated_campaign/tests/test_determinism_contract.py`
  - `scripts/federated_campaign/tests/test_hdfs_archive.py`
- **검증**: exact/incomplete D, empty-ledger pilot, forged P, duplicate S, understated/cross-pair R 적대 테스트와 전체 campaign contract unittest를 실행한다.
- **잔여 이슈**: Docker runner가 새 API에 연결되어야 한다. `complete_discovery(P)` 뒤 반환된 D를 pilot invocation manifest의 `discovery_completion_sha256`에 기록하고, S/R/F 호출에 동일한 P와 D를 전달해야 한다.
- **잠재 회귀 위험**: 기존 v3 S/F 또는 v2 R를 직접 조립하던 호출자는 v4/v3 API로 이행하지 않으면 fail-closed 한다. 전체 harness integration 테스트로 감지한다.
- **적용 원칙/의사결정 근거**: 런타임 fallback 없이 planner 이전의 evidence/state-transition 계약을 강화했다. 후보군이나 planner/runtime 지원 범위는 변경하지 않았다.

### Docker lifecycle wall producer integration API

`lifecycle_wall_seconds`는 계약 계층이 추정하거나 `cold_seconds + warm_seconds`로 대체하지 않는다. Docker runner가 각 lifecycle의 setup 직전 monotonic timestamp부터 validation, strict teardown, zero-resources 확인 직후까지 직접 측정하여 shared replicate manifest 최상위 필드에 기록해야 한다. Atomic ledger는 caller가 기록한 값을 서명된 bundle manifest의 `resource_evidence.lifecycle_wall_seconds`로 보존하고, HDFS receipt와 exact resume에서 동일 값을 재검증한다. `archive_bytes` 역시 artifact footprint 대체값으로 사용할 수 없다.

## 이슈 3 — Caller-forged pilot evidence callback 및 pilot allocation 우회

- **상태**: 해결
- **환경/조건**: P v3 / D v2 / S v4 / R v3 / F v4, local+HDFS exact resume, Docker-only campaign facade.
- **재현 절차**: 존재하지 않는 pilot path/digest 또는 변경한 invocation hash로 S를 재봉인한 뒤 R/F direct builder를 호출하거나, raw `AtomicEvidenceLedger.begin_attempt` 및 일반 facade `begin`에 pilot 형태 cell을 넘긴다.
- **관측 증상**: 이전 S semantic rebuild가 no-op validator를 사용하여 수학적으로 일관된 fabricated 120 rows를 수용할 수 있었고, `pilot_class=` 문자열 prefix가 gate 여부를 결정해 phase purpose가 caller-controlled였다. P의 일부 nested numeric alias/extra key도 self-hash 재계산 후 의미 검증이 불완전했다.
- **원인 분석**: 무결성 hash와 live ledger 존재성 검증의 경계가 분리되지 않았고, pilot phase를 typed API가 아니라 문자열로 추론했다. P validator는 top-level 중심이라 v2 builder가 보장했던 nested schema/type 규칙을 모두 재적용하지 않았다.
- **해결 요약**:
  - S 생성/재검증은 pilot 120 rows 및 D 336 rows 모두 live evidence validator를 반드시 호출한다. no-op fallback을 삭제했다.
  - R/F direct builders를 private contract helper로 내리고, 지원되는 campaign API를 `CampaignHarnessAdapter.build_pilot_resource_reservation` 및 `build_final_campaign_manifest`로 한정했다. facade가 local/HDFS exact-resume validator를 주입한다.
  - PerformanceKey의 exact key set과 `run_token`, evidence status/location exact schema를 검증한다.
  - `begin_pilot` 전용 typed surface가 class/planner/workers/profile/repeat, Williams order/period, P/D/invocation lineage를 결정한다. 일반 `begin`은 pilot identity를 거부한다.
  - raw ledger `begin_attempt`는 항상 거부하고 typed facade만 allocation capability를 가진다. final performance는 일반 facade `begin`에서 D 없이 계속 허용한다.
  - P validator는 artifact/file/tree records, network, privacy, JVM, threads, endpoints, topology, oracle binding, resources, barriers, pilot seed/schedule를 exact nested schema 및 strict int/bool/float 타입으로 재검증한다.
- **수정 파일**: `atomic_ledger.py`, `determinism_contract.py`, `hdfs_archive.py`, 관련 세 테스트 및 본 문서.
- **검증**: fabricated path, relabelled invocation, numeric aliases, nested extra key, raw ledger bypass, generic pilot begin, invalid typed pilot identity RED→GREEN 테스트와 전체 suite/static 검사를 수행한다.
- **잔여 이슈**: Docker lifecycle driver는 pilot에 `begin_pilot`만 사용하고 R/F는 facade methods만 호출하도록 통합해야 한다.
- **잠재 회귀 위험**: raw ledger allocation 또는 이전 direct R/F helper를 사용한 외부 호출자는 fail-closed 한다. `CampaignHarnessAdapter.integration_operations` 및 harness integration test로 감지한다.
- **적용 원칙/의사결정 근거**: planner/runtime 동작이나 candidate space는 변경하지 않고 evidence 및 phase-transition control plane만 강화했다. runtime fallback은 추가하지 않았다.

## 이슈 4 — P builder divergence, allocation capability 노출, discovery invocation 미결합

- **상태**: 해결
- **환경/조건**: Docker-only campaign P v3, discovery D v2, typed campaign facade, local/HDFS evidence resume.
- **재현 절차**:
  1. workload별 artifact path/digest를 중복시키거나 command argv에 leading-space/NUL, tolerance version에 whitespace를 넣고 P self-hash를 재계산한다.
  2. `str` subclass가 `startswith()`를 거짓으로 반환하도록 만든 pilot cell로 일반 `begin`을 호출하거나 raw `_allocate_attempt`/`_begin_attempt_from_adapter`를 직접 호출한다.
  3. 동일 P hash 아래 discovery invocation manifest를 임의 변경해 336개 success를 만든 뒤 D를 재봉인한다.
- **관측 증상**: P validator가 original v2 builder의 distinct-artifact 및 normalized-string 규칙 일부를 재검증하지 않았다. underscore allocation methods는 이름만 private이고 capability 검사가 없었다. D row가 discovery evidence의 invocation hash를 포함하지 않아 P와 다른 명령으로 실행된 success를 구분할 수 없었다.
- **원인 분석**: semantic validator가 builder의 모든 nested invariant와 동등하지 않았고, allocation authority 및 discovery command lineage가 명시적 데이터 계약이 아니었다.
- **해결 요약**:
  - `fed_dmls`, `cp_dmls`, `oracle_files`, `reference_artifacts` 각각의 workload path와 digest가 모두 distinct인지 재검증한다.
  - command argv는 exact built-in normalized nonempty string이며 leading/trailing whitespace와 NUL을 거부한다. tolerance version 역시 normalized exact string만 허용한다.
  - ledger/facade allocation 내부 경로에 서로 다른 module-private object capability를 요구한다. public raw allocation 및 capability 없는 underscore 호출은 intent 생성 전에 거부한다.
  - facade/ledger identity string은 subclass가 아닌 exact built-in `str`만 허용한다. 일반 performance begin은 canonical 또는 변형된 모든 `pilot[_ -]?class` 표기를 거부하고 typed `begin_pilot`만 허용한다.
  - P와 cell에서 exact `systemds-federated-discovery-invocation/v1` manifest를 파생한다. campaign discovery `begin`은 P, P hash, supplied invocation의 exact equality를 요구한다.
  - D를 `systemds-federated-discovery-completion/v2`로 올리고 모든 336 row에 `invocation_manifest_sha256`을 포함한다. D builder는 P-derived expected hash와, facade live validator는 local/HDFS evidence hash와 각각 비교한다.
- **수정 파일**: `atomic_ledger.py`, `determinism_contract.py`, `hdfs_archive.py`, 관련 세 테스트 및 본 문서.
- **검증**: self-resealed duplicate artifact path/digest, leading/NUL command, whitespace tolerance, mixed D invocation, `Evil(str)`, 변형 pilot 표현, raw internal capability bypass 테스트와 전체 suite/static 검사를 실행한다.
- **잔여 이슈**: Docker driver는 discovery campaign cell마다 `build_canonical_discovery_invocation(P, cell)` 결과를 그대로 facade `begin`에 전달해야 한다.
- **잠재 회귀 위험**: 과거 임의 discovery invocation 또는 raw underscore allocation을 사용하던 호출은 fail-closed 한다. campaign harness integration과 D 336-row completion test로 감지한다.
- **적용 원칙/의사결정 근거**: planner/runtime 및 후보 공간은 변경하지 않았다. P-derived invocation과 evidence lineage를 planner 실행 전에 고정하며 runtime fallback은 추가하지 않았다.
