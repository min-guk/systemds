# FED Planner P/L/R 감사 체크포인트 — 2026-08-31 20:19 CEST

## 1. 현재 상태

이 보고서는 직전 전체 checkpoint
`docs/FED_PLANNER_P_L_R_AUDIT_CHECKPOINT_2026-08-31_1947_KO.md` 이후의 변경과 검증을 기록한다.
직전 보고서의 Binary, Indexing, WDivMM 분석과 증거 경계는 그대로 유효하다.

현재 확정된 핵심 결과는 다음과 같다.

1. 완료된 forced-state target은 84개에서 **94개**로 늘었고 **94/94가 성공**했다.
2. WSigmoid shared rule이 runtime이 거부하는 `FULL/PART` primary input을 FED 후보로 공개하던
   spurious-feasibility 결함을 발견하고 수정했다.
3. WSigmoid/WSLoss의 valid ROW/COL candidate 10개를 target별 fresh JVM에서 강제했다.
4. WSigmoid의 `CP/FOUT`는 CP 연산 뒤 synthetic `FEDFoutInstruction`으로 ROW/COL 결과를 만드는
   composite state임을 runtime receipt로 확인했다.
5. WSLoss의 `FED/LOUT`는 worker-side FED 실행 뒤 coordinator에 local scalar를 만드는 계약임을
   확인했다.
6. 최신 집중 회귀는 Java 94개와 Python 5개가 모두 통과했다.
7. 남은 family는 WCeMM/WUMM, aggregate, reorg, ternary/nary/quantile 순으로 병렬 감사한다.

현재 `R`은 성공적으로 관측·강제한 상태의 집합이다. runtime 전체의 독립 exhaustive enumeration이
아니므로 전역 `Missing=0`/`Spurious=0` 주장은 계속 보류한다.

## 2. 작업 위치와 실행 정책

```text
worktree: /home/mchoi/g014-fed-runtime-space-audit-0d769014-20260830
branch:   g014/fed-runtime-space-audit-20260830
base:     0d769014d18ffb6a915b186c9bc05596710a3e24
host:     dams-so002
```

- `so001`은 proxy이므로 사용하지 않는다.
- `so002`--`so006`만 허용한다.
- 이 감사가 소유하지 않은 SliceLine 및 기타 장기 실행 process는 종료하거나 변경하지 않았다.
- `so003`--`so006` 병렬 실행 preflight는 모두 통과했다.

## 3. 누적 완료 현황

| family | forced target | 결과 |
|---|---:|---:|
| Binary | 34 | 34/34 |
| Indexing | 28 | 28/28 |
| WDivMM LEFT/RIGHT | 14 | 14/14 |
| WDivMM BASIC | 8 | 8/8 |
| WSigmoid/WSLoss direct ROW/COL | 10 | 10/10 |
| **합계** | **94** | **94/94** |

## 4. WSigmoid feasibility 결함

Runtime `QuaternaryWSigmoidFEDInstruction`은 primary `X`가 ROW 또는 COL일 때만 실행하며,
FULL/PART이면 예외를 발생시킨다. 그러나 shared `WeightedSigmoidRule`은 기존에 모든
`isFederatedLike(X)`를 FED/FOUT 후보로 허용했다.

Red test:

```text
audit-results/fed-runtime-wsigmoid-rule-red-20260831T175257Z/maven.log
scenario=wsSigmoid-full-forbidden expected:<CP> but was:<FED>
```

수정 파일은 `src/main/java/org/apache/sysds/hops/fedplanner/rules/Rulesets.java`이다. FULL/PART
primary input을 `CP/LOUT`, `PARTITION_FORBIDDEN`으로 제한하고 runtime의 ROW/COL-only 계약을
detail에 기록했다. Green evidence는
`audit-results/fed-runtime-wsigmoid-rule-green-20260831T175357Z/`에 있다.

이 수정은 selector별 보정이 아니라 FedAll/Heuristic/DP/Exact가 공통으로 소비하는 후보 공간에서
불가능한 FED 상태를 제거한다.

## 5. Weighted-quaternary direct fixture와 후보 공간

추가 파일:

```text
src/test/java/org/apache/sysds/test/functions/federated/fedplanning/
  FederatedWeightedQuaternaryLayoutPlanningTest.java
src/test/scripts/functions/privacy/fedplanning/
  FederatedWeightedQuaternaryLayoutPlanningTest.dml
  FederatedWeightedQuaternaryLayoutPlanningTestReference.dml
```

Fixture는 PUBLIC `12 x 1002` matrix와 rank 3 factors를 사용해 두 worker ROW/COL federation에서
direct WSigmoid와 WSLoss를 모두 생성한다. local reference와 수치 결과를 비교하며 기본 smoke에서
`fed_wsigmoid` 두 건과 `fed_wsloss` 두 건을 확인했다.

Discovery는 `audit-results/fed-runtime-weighted-quaternary-layout-e2e-v1-20260831T180004Z/`이다.

| operation/input | published states |
|---|---|
| WSigmoid/ROW | `CP/LOUT`, `FED/FOUT/ROW`, `CP/FOUT/ROW` |
| WSigmoid/COL | `CP/LOUT`, `FED/FOUT/COL`, `CP/FOUT/COL` |
| WSLoss/ROW | `CP/LOUT`, `FED/LOUT/ROW` |
| WSLoss/COL | `CP/LOUT`, `FED/LOUT/COL` |

총 10개 target이며 WSigmoid에 근거 없는 `FED/LOUT`을 추가하지 않았다. WSLoss는 scalar output이라
FED 실행 상태도 결과 residency는 LOUT이다.

## 6. Forced-runtime 결과

Campaign은
`audit-results/fed-runtime-weighted-quaternary-layout-forced-v1-20260831T180534Z/`이다.

| 항목 | 결과 |
|---|---:|
| expected target | 10 |
| result row | 10 |
| constraint satisfied | 10 |
| fresh JVM chunk | 10 |
| failed JVM chunk | 0 |
| runtime capability row | 46 `SUCCESS` |
| targets with runtime receipt | 10 |
| infrastructure | `PASS` |
| classification | `ALL_SUCCESS` |

| requested state | operation lowering | runtime/output witness |
|---|---|---|
| `CP/LOUT` | `CP/LOUT` | local execution audit |
| `CP/FOUT/ROW` | operation `CP/LOUT` | `FEDFoutInstruction`, federated ROW |
| `CP/FOUT/COL` | operation `CP/LOUT` | `FEDFoutInstruction`, federated COL |
| `FED/FOUT/ROW` | `FED/FOUT` | WSigmoid output federated ROW |
| `FED/FOUT/COL` | `FED/FOUT` | WSigmoid output federated COL |
| `FED/LOUT/ROW` | `FED/LOUT` | WSLoss output local scalar |
| `FED/LOUT/COL` | `FED/LOUT` | WSLoss output local scalar |

행 단위 검증 파일 `WEIGHTED_QUATERNARY_RUNTIME_STATE_VALIDATION.json`의 10개 행은 모두
`PASS`이다.

## 7. Attempt-local 비교

`attempt-local-comparison-v1/summary.json`의 결과는 다음과 같다.

| 항목 | 결과 |
|---|---:|
| successful runtime row | 46 |
| runtime row with planned target | 24 |
| `EXACT_PLANNED_TARGET_IN_P` | 24 |
| `NO_EXACT_ACTUAL_INPUT_SIGNATURE` | 20 |
| `NO_EXACT_SINGLE_OCCURRENCE` | 2 |
| confirmed Missing | 0 |
| selected input divergence | 0 |
| coverage complete | false |

`NO_EXACT_*`는 init/reblock 또는 exact single occurrence로 귀속할 수 없는 auxiliary row이며 Missing으로
과대 분류하지 않는다.

## 8. 최신 회귀 검증

```text
audit-results/fed-runtime-weighted-quaternary-validation-20260831T181306Z/
```

- Java: 94 tests, 0 failures, 0 errors, 0 skipped
- Python audit/comparator: 5 tests, 0 failures
- Python compile: PASS
- shell syntax: PASS
- `git diff --check`: PASS

## 9. 병렬화 현황과 진행 중 작업

원격 preflight는 `audit-results/remote-parallel-preflight-20260831T181839Z/`에 있다.
`so003`--`so006`은 모두 SSH 성공, root filesystem 여유 234--297 GiB, 낮은 load를 확인했다.

1. shared privacy legality `L(o,i)`를 PUBLIC/PRIVATE/PRIVATE_AGGREGATION별로 독립 감사 중이다.
2. weighted evidence의 source/discovery/campaign/validation checksum을 독립 verifier가 재검증 중이다.
3. 다음 direct fixture는 WCeMM/WUMM이며, 이후 aggregate/reorg/ternary-nary campaign을 서로 다른
   `so003`--`so006` 서버에 배치한다.
4. source hash와 manifest hash가 일치하지 않는 원격 결과는 합치지 않는다.

세부 잔여 범위는 `docs/FED_PLANNER_RUNTIME_SPACE_GAPS_2026-08-31_KO.md`에 기록했다.

독립 verifier 결과도 `PASS`이다.

```text
discovery receipt SHA-256: c25c56fe46703e0cbd8fd0f11ff2ac0bf0aa69c763abe9d6267c3192463b3608
campaign receipt SHA-256:  c6f8ba9ec74c198dd992e133404fbd6aa7486cf1fa12753e85a436d283b5acae
validation receipt SHA-256:141d869d6c4a7ad497ad1a795e73f6edbe28e8b187253f7f5c298dbf7874aa9c
source receipt SHA-256:    b5f0b1b3d7e222a83f95949d337b9f15d7f853ada31c41811efbe0aac6beb5f4
```

Verifier는 discovery 11개, campaign 92개, validation 23개, source 17개 checksum을 모두
재검증했다. 공유 worktree가 dirty이므로 HEAD만이 아니라 source receipt를 재현성 authority로 쓴다.

## 10. 현재 한계와 stop condition

- 현재 94/94는 해당 published target의 실행 가능성을 입증하지만 negative runtime space를 완전히
  열거한 것은 아니다.
- privacy별 candidate exclusion은 별도 `L` fixture가 끝나기 전까지 완료로 보지 않는다.
- WCeMM/WUMM 및 나머지 family가 남아 있으므로 작업을 계속한다.
