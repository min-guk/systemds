# Append/Reshape federated planning-space 감사 보고서

## 결론

Append와 Reshape의 공개(`PUBLIC`) 입력 직접 실행 범위에서, planner가 공개한 privacy-filtered 후보 집합 `P`의 31개 상태를 모두 독립 JVM에서 강제 실행했다. 31개 모두 legality constraint를 만족했고 실제 federated runtime에서 성공했다. manifest와 authoritative result의 exact union은 일치하며 duplicate, missing, unexpected target은 모두 0이다.

이 결과는 **관측하고 강제한 31개 published state가 실행 가능하다**는 것을 검증한다. 그러나 runtime이 잠재적으로 지원하는 모든 상태 `R`을 완전 열거한 것은 아니므로 `coverageComplete=false`이며, 전체 `R \cap L - P`가 공집합이라는 완전성 주장은 하지 않는다.

## 범위와 대상

- fixture: `FederatedAppendReshapeLayoutPlanningTest`
- 직접 대상 opcode: `b(cbind)`, `b(rbind)`, `r(rshape)`
- manifest: 31 unique targets
  - `cbind`: 13
  - `rbind`: 9
  - `rshape`: 9
- manifest SHA-256: `f47ddfdce6b0bd6b4a9d00a668af29f00c9a101de81ca4876fbd509f91b2686f`
- source receipt SHA-256: `d24b1294d2734f73f0a388ba9f190f348dbdc7dd401868b4411a0743ecd45089`
- 실행 격리: `TARGETS_PER_JVM=1`

## 병렬 실행과 빌드 격리

`so001`은 사용하지 않았다. `so003`--`so006` 네 서버에 동일 source receipt를 배포하고, 각 source root 내부의 물리적 `target/`에서 독립적으로 build/run했다. 네 target directory는 symlink가 아니며 `(device,inode)`가 서로 다르고 build 전후 동일하다.

| 서버 | targets | 성공 | 물리 target inode |
|---|---:|---:|---:|
| so003 | 8 | 8 | 19827685 |
| so004 | 8 | 8 | 19146872 |
| so005 | 8 | 8 | 7602311 |
| so006 | 7 | 7 | 26228729 |

인프라 검증은 `INFRASTRUCTURE_VALIDATION.json`에서 `PASS`이다.

## Exact-union 및 runtime 결과

`authoritative-aggregate/TARGET_UNION_VALIDATION.json`:

- manifest rows / unique targets: `31 / 31`
- authoritative rows / unique targets: `31 / 31`
- duplicate target IDs: `0`
- missing target IDs: `0`
- unexpected target IDs: `0`
- outcome `SUCCESS`: `31`
- `constraintSatisfied=true`: `31`
- classification `PUBLISHED_LEGAL_EXECUTED`: `31`
- validation: `PASS`

각 target 실행 중 기록된 runtime-capability row는 총 335개이며 전부 `SUCCESS`이다. attempt-local `P`/runtime join 결과는 exact planned target 170건이 모두 `P`에 존재했고, confirmed missing은 0, selected/runtime input divergence도 0이다.

## 증거 위치

- campaign root: `audit-results/append-reshape-auth2-20260831T203727Z/`
- authoritative summary: `authoritative-aggregate/SUMMARY.json`
- exact union: `authoritative-aggregate/TARGET_UNION_VALIDATION.json`
- aggregate checksums: `authoritative-aggregate/SHA256SUMS.txt`
- checksum verification: `authoritative-aggregate/CHECKSUM_VERIFICATION.txt`
- attempt-local comparison: `authoritative-runtime-space-comparison-v2/summary.json`
- target/source isolation: `INFRASTRUCTURE_VALIDATION.json`
- 전체 evidence checksum index: `EVIDENCE_SHA256SUMS.txt`

## 제외한 실행

`append-reshape-auth-20260831T203328Z` 캠페인은 source snapshot의 `target`이 symlink였으므로 authoritative evidence에서 전부 제외했다. 이후 runner에 source-local physical target fail-closed guard를 적용했고, 본 보고서의 결과는 새 campaign `append-reshape-auth2-20260831T203727Z`만 사용한다.

## 해석 한계

본 검증은 PUBLIC privacy와 fixture가 노출한 direct Append/Reshape 후보에 한정된다. 따라서 `confirmedMissing=0`은 관측된 successful FED instruction에 대한 결과이며, 모든 입력 shape/privacy/layout 및 runtime conversion 조합을 포괄하는 `R` 완전성 증명은 아니다.
