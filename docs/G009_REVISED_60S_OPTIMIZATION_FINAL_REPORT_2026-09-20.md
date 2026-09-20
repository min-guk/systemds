# G009 개정 60초 최적화 최종 보고서

- 작성일: 2026-09-20
- 대상 계획: [G009 개선 계획 개정판](G009_REVISED_60S_OPTIMIZATION_PLAN_2026-09-20.md)
- 관련 구현 체크포인트: [P5 + R1-A + R2](G009_R1_R2_P5_IMPLEMENTATION_CHECKPOINT_2026-09-20.md)
- 판정 원칙: 아래에서 **증거**는 고정 산출물로 재검증 가능한 사실이고, **해석**은 그 증거로부터의 판단이다.

## 1. 최종 판정

| 목표/계약 | 결과 | 핵심 증거 |
|---|---:|---|
| 공식 Docker `CandidateE2E ≤ 60초` | **PASS** | 후보 18회 모두 `39.271416–45.866749초`, 중앙값 `43.153788초` |
| 단일 공식 proof | **PASS** | `43.009023950초`, 60초 대비 여유 `16.990976050초` |
| correctness/동일 signature | **PASS** | 후보·기준선 36/36 실행의 correctness signature 동일 |
| fallback/fatal/OOM/timeout | **PASS** | 각각 `0/0/0/0` |
| process RSS 증가율 `≤ 1.05` | **PASS** | ratio of medians `1.043522` |
| cgroup peak 증가율 `≤ 1.05` | **PASS** | ratio of medians `1.044481` |
| paired 10배 `r_(13) ≤ 0.10` | **FAIL** | ratio median `1.131662`, `r_(13)=1.181232` |
| 실제 GLM cold/warm 실행·의미·audit | **PASS** | `16.733초` / `14.000초`, 동일 출력·fingerprint·의미 verdict |

**결론:** 사용자가 요구한 production 후보 형성의 **60초 절대 목표는 달성**했다. 그러나 기존의 **엄격한 P0 대비 10배 목표는 달성하지 못했다**. 두 판정을 합쳐서 성공으로 표현하지 않는다.

## 2. 공식 R6 acceptance 증거

공식 acceptance root는 다음과 같다.

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/g009-r6-acceptance-20260920T084314Z
```

사전 등록한 18 pair, 총 36개 실행을 순차 수행했다. 순서는 AB 9개와 BA 9개이며 early stop은 없었다. 모든 qualifier exit는 0이었다.

| 항목 | 값 |
|---|---:|
| candidate 최소 / 중앙값 / 최대 | `39.271416 / 43.153788 / 45.866749초` |
| baseline 최소 / 중앙값 / 최대 | `35.104667 / 36.632751 / 42.125914초` |
| paired ratio 중앙값 | `1.131662` |
| preregistered order statistic | `r_(13)=1.181232` |
| ratio of medians | `1.178011` |
| candidate process RSS 중앙값 | `2,132,634 KiB` |
| baseline process RSS 중앙값 | `2,043,688 KiB` |
| candidate/baseline process ratio of medians | `1.043522` |
| candidate/baseline cgroup ratio of medians | `1.044481` |

주요 고정 해시는 다음과 같다.

| 산출물 | SHA-256 |
|---|---|
| `preregistration.json` | `c3b68017e243e2ce3f63499a60505320f3becccb12495860e16c64c247a0d78e` |
| `results.json` | `20ed24339cd3bbd45a93ad19eaf8572e81e1d68fa6235904b25e55fd4b770289` |
| `runs.tsv` | `98c55906a5020ddafcb344f0ada172800c9aacac3afdb78714815225800485eb` |
| `pairs.tsv` | `990fe08060642c19f0a9c810cb140ca25cf62931965475b25fbe92982edf5583` |
| `SHA256SUMS_FINAL` 자체 | `79ed35982b384a8f2ff70ed3cf707c8f5538f03c53bf2415432f10fe6de122f9` |

단일 공식 planning proof는 다음 위치에 있고 `CandidateE2E=43.009023950초`다.

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/g009-final-qualify-final202609200801404c87f8/g009-glm-p0-proof.json
file SHA-256:    46130eceda533edd8e73f3ffc25a99664b10dae5bb997e59dd3486943a7a69e9
payload SHA-256: 19f0fa798ae5f98906fbd26a012b48d5711bced2455ce7f1ab9054dbc341b279
```

## 3. 10배 목표는 명시적으로 FAIL

후보/기준선 ratio 중앙값은 `1.131662`, 사전 등록 판정 통계량은 `r_(13)=1.181232`다. 요구값은 `≤0.10`이므로 여유 없는 **FAIL**이다. ratio of medians도 `1.178011`이며, 후보 중앙값은 엄격한 P0 중앙값보다 약 `17.8%` 느리다.

기준선 자체가 `36.632751초`이므로 같은 경계에서 10배를 만족하려면 후보가 대략 `3.663초` 이하여야 한다. 현재 채택된 구현과 측정한 로컬 패치들은 고정 planning phase와 검증 경계를 유지하면서 이 예산에 도달할 수 있다는 증거를 제공하지 못했다.

**해석:** 남은 10배 목표에는 candidate formation 경계 또는 알고리즘의 근본적 재설계가 필요할 가능성이 높다. 이는 관측된 실행 시간과 현재 패치의 효과에 근거한 공학적 판단이지, `3.663초` 미만을 수학적으로 불가능하다고 증명한 lower bound는 아니다. 역사적 host 진단의 `716초`는 strict production Docker E2E 기준선이 아니었으며, 이를 분모로 사용해 10배 달성을 주장하지 않는다.

## 4. R0–R6 실행 결정

| 단계 | 최종 결정 | 근거 |
|---|---|---|
| R0 | 완료 | immutable stage, 공식 qualifier, 메모리·정확성·proof 경계 확정 |
| R1 | **채택** | 기존 owner index 재사용으로 중복 전체 scan 제거; exact ordering 보존 |
| R2 | **채택** | 구조적으로 동일한 relation의 exact no-op identity 재사용; leaf materialization 회피 |
| P5 | **채택** | analysis-local hash/topology/direct-binding 비용 감소; focused correctness 통과 |
| R3 | 조건부 **NO-ENTRY** | 공식 E2E가 이미 60초 미만이고, 추가 physical-authority 수명 변경의 측정 이득보다 의미 위험이 큼 |
| R4 | 조건부 **NO-ENTRY** | outer-epoch topology 공유 확대의 elapsed 이득이 입증되지 않았고 retained-memory/invalidity 위험이 남음 |
| R5 | 조건부 **NO-ENTRY** | compressed DP consumer 전환은 큰 interface·selection-order 위험; 60초 달성 뒤 강행할 근거 없음 |
| R6 | 완료 | 18-pair acceptance, 실제 cold/warm 실행, proof/audit/memory 검증 완료 |

채택된 production SystemDS는 `49a313125f76c270f8bc63165326b9a844568737`이다. 이 커밋은 P5/R1/R2를 유지하고, 측정상 이득이 없던 두 실험을 되돌렸다.

## 5. NO-GO 실험과 rollback

host 진단은 공식 Docker E2E가 아니라 원인 분리용이다.

| 후보 | build/주요 결과 | 판정 |
|---|---:|---|
| retained P5/R1/R2 (`5fc4c7987b`) | build `241.9617초`; support `12.256초`; RSS `14,995,176 KiB` | 기준 진단 |
| k-way canonical merge (`8a557f08db`) | build `243.2617초`; support `14.883초`; RSS `15,874,204 KiB` | **NO-GO** |
| boundary fastpath (`a6270f6c0d`) | build `251.7149초`; support `13.103초`; RSS `15,284,384 KiB` | **NO-GO** |

k-way merge는 global sort를 대체하려 했지만 HashSet·structural handle·priority queue 할당이 증가했다. boundary fastpath도 queue 일부를 제거했으나 전체 elapsed를 줄이지 못했다. 둘은 `49a313...`에서 rollback했고, 정확한 log filter 수정만 유지했다. 이는 “기능이 있어 보인다”가 아니라 **wall time과 memory로 채택 여부를 결정**한 결과다.

## 6. 실제 런타임 gap closure

공식 R6 qualifier는 planning-only이며 실제 GLM 학습을 실행하지 않는다. 이를 숨기지 않고 별도의 actual-runtime proof로 보완했다.

초기 runtime semantic v1은 다음 순수 상대오차를 사용했다.

```text
|lossA - lossR| / max(|lossR|, 1e-15)
```

P2P2D는 reference loss가 거의 0인 분리 가능한 문제라서 절대 loss 차이 `≈4.15e-13`, prediction NRMSE `≈8.87e-12`, 분류 불일치 0인데도 상대값이 약 `2.5e-5`가 되었다. 이는 구현 오차가 아니라 near-zero 분모의 수치적 ill-conditioning이었다.

runtime-only v2는 stable softplus를 사용한다.

```text
loss(z,y) = max(z,0) - y*z + log(1 + exp(-|z|))
objectiveScaledGap = |lossA-lossR| / max(|lossR|, N*log(2))
```

- prediction NRMSE와 scaled gap threshold는 모두 기존과 같은 `1e-6`이다.
- 기존 pure-relative 값은 `legacy_objective_relative_error_diagnostic`으로 보존하지만 acceptance에는 사용하지 않는다.
- canonical planning v1 계약, 기존 `metrics/glm.dml`, reference manifest/beta는 변경하지 않았다.

최종 actual-runtime proof:

```text
/home/mchoi/g009-p0-stage-5e52123fc1f321c7c14dc27ab7839bad7b94affc9be94625b991b5d4688cb97c/
  results/g009-runtime-g009rtruntimefinal202609201539488adfd14d/proof.json
```

| 항목 | cold | warm |
|---|---:|---:|
| actual runtime | `16.733초` | `14.000초` |
| output SHA-256 | `692cfc0137f7c51f1c8339ace0ad15761e5da15da03f7cc78a2c035c4bec496f` | 동일 |
| prediction NRMSE | `8.873634192919827e-12` | 동일 |
| objective scaled gap | `1.1968001322782984e-17` | 동일 |
| legacy relative diagnostic | `2.4537703167813905e-5` | 동일, non-gating |

두 실행의 다섯 fingerprint는 정확히 동일하다.

```text
2f16633908dfeb701ba8859b22ed328b6f4c00cf15a2e2ee8450c15239ff5227
33dbda2d98c7148c329571c2989fc9bcae9275b253037a0445ef32d1c32c5393
edb64ccdcdcc95d6cb05f85e019081e3faa7fb9685a502a0ed022322d9bbd954
ba324466ac3ca6238af91331f753b915b9beabb4d4510678f9553fab9b47ba97
fbe7809e62fd1eb288e560953c3af74c0123fb634a2e796efa85b0193527ae3b
```

coordinator audit는 각 실행에서 mismatch와 missing physical hop이 0이고, worker audit·process/cgroup memory receipt·OOM event·teardown zero-resource 검증도 통과했다.

### 첫 runtime stage의 fingerprint blocker

첫 cold/warm 시도는 algorithm 차이가 아니라 harness가 phase별 물리 임시 경로를 생성 DML의 source/output literal에 포함했기 때문에 fingerprint가 달랐다. 비교를 정규화하거나 약화하지 않았다. 대신 cold/warm 모두 같은 stable logical alias를 DML에 제공하고, 실제 산출물은 별도 물리 phase 디렉터리에 유지했다.

alias/physical output에는 canonical parent, 비-symlink regular file, dev:inode 동일성, `test -ef`, quarantine cleanup을 적용했다. phase directory 또는 alias가 바뀌면 fail closed하고 충돌 증거를 보존한다. 이 변경은 독립 보안 리뷰에서 발견된 symlink/TOCTOU 문제를 수정한 뒤 승인됐다. 따라서 최종 proof의 fingerprint 동일성은 경로 문자열을 지운 결과가 아니라 **동일한 논리 실행 identity를 고정한 결과**다.

## 7. 최종 identity와 해시

| 항목 | 값 |
|---|---|
| SystemDS commit | `49a313125f76c270f8bc63165326b9a844568737` |
| SystemDS tree | `9f6779330a591beb55564931178c0b05050ff9b3` |
| SystemDS JAR SHA-256 | `fd07b9a056a6ac3cb58c4c17dddb25508d01a1d9f3c63d9578e2bee78b560752` |
| runtime semantic v2 harness commit | `cc847aad8939bb8b9b289c8b8034cbd78fd8a6fa` |
| stable replay identity harness commit | `94ba30b7b6ae877c5e2b92b265c7b1054d7537b0` |
| final stage id | `5e52123fc1f321c7c14dc27ab7839bad7b94affc9be94625b991b5d4688cb97c` |
| descriptor file SHA-256 | `d94e2eae7e3c58a3f28212bec018e9bdfa5798be689a350e2cf548980865d382` |
| descriptor payload | `7036fd727b1f87fa7e37c574cdea1fb6c7f7a3cf7f949798d8f2641a822ea277` |
| runtime proof file SHA-256 | `06179728a8735353649ac2619dba356cdfc8c971a01c75afa74e9203138cdabc` |
| runtime proof payload | `84c3bc6e73881ad6a27cb7c83ac05e72a5aceace2ebd7f0e7a10d98322244e5a` |
| planning proof file SHA-256 | `46130eceda533edd8e73f3ffc25a99664b10dae5bb997e59dd3486943a7a69e9` |
| planning proof payload | `19f0fa798ae5f98906fbd26a012b48d5711bced2455ce7f1ab9054dbc341b279` |

두 harness 커밋은 외부 로컬 repository에만 있으며 remote가 없다.

## 8. 한계와 남은 위험

1. **10배 목표는 FAIL**이다. 60초 달성과 혼동하면 안 된다.
2. actual-runtime proof는 공식 planning proof의 `CandidateE2E`를 bind한다. actual execution `16.733/14.000초`를 새로운 CandidateE2E로 재정의하지 않는다.
3. 최종 `origin/main`에 추가될 문서 전용 커밋은 staged SystemDS code commit `49a313...`보다 새롭다. stage가 검증한 production code/JAR identity는 변하지 않는다.
4. runtime harness 두 커밋은 remote가 없어 다른 장비에서 commit만으로 가져올 수 없다. 현재 stage와 proof가 immutable evidence 역할을 한다.
5. R3/R4/R5는 구현 완료라고 가장하지 않는다. 공식 60초 통과 뒤 위험 대비 이득이 없어 진입하지 않은 것이다. 10배를 다시 요구하면 별도의 근본 재설계 계획이 필요하다.
6. 공식 acceptance의 correctness 36/36 PASS는 고정된 G009 qualification 범위의 결과이지 project-wide suite 전체가 clean하다는 뜻이 아니다. 역사적 checkpoint의 package-wide wildcard는 626 tests에서 7 failures, 14 errors, 14 skips였고, 대표 오류 일부만 변경과 분리했을 뿐 모든 실패가 기존 문제였다고 증명하지 않았다. 자세한 제한은 [구현 체크포인트 §4.3](G009_R1_R2_P5_IMPLEMENTATION_CHECKPOINT_2026-09-20.md#43-package-wide-결과와-해석-제한)을 따른다.

## 9. 재현·검증 명령

```bash
# 공식 acceptance 요약과 고정 해시
cat /grid/3/cofee-lm-sweep-mchoi-20260914/g009-r6-acceptance-20260920T084314Z/RESULTS.md
sha256sum /grid/3/cofee-lm-sweep-mchoi-20260914/g009-r6-acceptance-20260920T084314Z/{preregistration.json,results.json,runs.tsv,pairs.tsv,SHA256SUMS_FINAL}

# 최종 stage/proof 파일과 payload 확인
sha256sum \
  /home/mchoi/g009-p0-stage-5e52123fc1f321c7c14dc27ab7839bad7b94affc9be94625b991b5d4688cb97c/stage-descriptor.json \
  /home/mchoi/g009-p0-stage-5e52123fc1f321c7c14dc27ab7839bad7b94affc9be94625b991b5d4688cb97c/results/g009-runtime-g009rtruntimefinal202609201539488adfd14d/proof.json \
  /grid/3/cofee-lm-sweep-mchoi-20260914/g009-final-qualify-final202609200801404c87f8/g009-glm-p0-proof.json

python3 - <<'PY'
import json
from pathlib import Path
proof = Path('/home/mchoi/g009-p0-stage-5e52123fc1f321c7c14dc27ab7839bad7b94affc9be94625b991b5d4688cb97c/results/g009-runtime-g009rtruntimefinal202609201539488adfd14d/proof.json')
v = json.loads(proof.read_text())
print(v['stage'])
print(v['planning_proof']['candidate_e2e'])
print([(Path(p['path']).name, p['seconds'], p['output_sha256'], p['verdict']) for p in v['phases']])
print([p['fingerprints'] for p in v['planning_receipts']])
PY
```

이 보고서의 stop condition은 “60초 absolute gate, 정확성, memory, 실제 runtime 의미/audit가 고정 증거로 통과하고 10배 실패를 별도 공개”하는 것이다. 해당 조건은 충족됐다.
