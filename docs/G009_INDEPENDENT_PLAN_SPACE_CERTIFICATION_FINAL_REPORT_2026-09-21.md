# G009 전체 워크로드 독립 plan-space 인증 최종 보고서

- 보고일: 2026-09-21. 대상은 `systemds-g009-integration`과 형제 저장소 `cofee-evaluation`이다.
- 현재 checkout 재점검 시각: 2026-09-21 22:37 CEST. 이 시각 이후의 변경은 아래 현재-source 판정에 포함되지 않는다.
- 기준 HEAD: `fe000959c48ffa1172399e49124d082fe42d0c6d`. 두 체크아웃 모두 dirty 상태이므로 HEAD만으로 실행 소스를 식별하지 않는다. 아래 SHA-256 번들과 attempt receipt가 **당시** 이용 가능한 파일을 식별한다.
- **최종 판정: 전체 인증 `UNKNOWN` / 계획 수용 기준 미달.** 작은 범위의 회귀 게이트는 통과했지만, 모든 워크로드의 feasible plan 누락 0개와 illegal plan 추가 0개는 증명되지 않았다. `UNKNOWN`을 `PASS` 또는 infeasible로 해석하면 안 된다.
- 이 문서가 이번 인증 시도의 단일 결과 보고서다. 원래 [검증 계획](G009_INDEPENDENT_PLAN_SPACE_CERTIFICATION_PLAN_2026-09-21.md)의 12개 최종 수용 기준을 판정 계약으로 적용했다.

## 판정 방법과 신뢰 경계

각 고정된 실행 조건 `I`에 대해 `P(I)`는 새 production 공간이 selector 정책 적용 **전에** 표현하는 전체 joint physical plan 집합, `E(I)`는 기존 exhaustive 구현이 내보내는 전체 집합이다. `R(I)`는 builder의 후보·receipt·Exact domain을 정답으로 사용하지 않고, builder 이전 compiler/source snapshot에서 독립 primitive universe를 만들고 별도 runtime legality 명세로 판정한 집합이다. 요구되는 결과는 모든 in-scope cell에서 `R\P=∅`, `P\R=∅`, 미판정 0개, 그리고 `P=E=R`이다. 개수·해시·최적해 일치만으로 집합 일치를 주장하지 않는다. 기존 exhaustive도 production analysis에 의존하므로 `P=E`만으로 생성 전 누락을 잡을 수 없다.

현재 구현의 작은 독립 oracle은 제한된 literal fixture의 원시 Cartesian product, 일부 runtime/matrix/state capability, source authority 및 mutation을 검증한다. 전체 opcode·shape·FType·partition·privacy·action·function 조합의 독립 물리 plan grammar와 판정 규칙은 아직 없다. 따라서 작은 oracle의 통과를 전체 `R(I)`가 완성된 것으로 해석하지 않았다. 공유 compiler 자체도 명시된 신뢰 경계이며, pre-builder snapshot과 수기 fixture 대조만으로 compiler 전체 정확성을 증명하지 않는다.

## 동결한 범위와 입력 확인

| 항목 | 이번 시도에서 확인한 범위 | 남은 한계 |
|---|---:|---|
| source-qualified 발견 | 279개: `IN_SCOPE` 115, `UNSUPPORTED` 148, `HISTORICAL` 16 | 148개와 16개는 검증 성공으로 제외된 항목이 아니다. workload/helper 역할과 현행 지원 범위를 검토해야 한다. |
| 외부 DML | 206개 파일 해시 기록 | DML만으로 실행 입력 전체가 고정되지 않는다. |
| planning 조건 | 14 case × worker 1/3/5/7 × network 4 = 224개 조건 서명 | 네트워크 서명은 실제 런타임 FederationMap·데이터·compiler 실행을 증명하지 않는다. 나머지 in-scope 발견 59개는 조건 목록이 없다. |
| 실행 backlog | 447개 cell; 모든 279 discovery ID와 224 계획 조건이 매핑됨 | 283개 in-scope cell에 완전한 입력 attestation과 P/E/R adapter가 없다. 148개 unsupported, 16개 historical은 미해결이다. |
| 정적 planning 입력 | 실제 프로파일의 56개 DML을 파싱·HOP 구성·builder 이전 snapshot과 대조; 224개 조건의 literal worker 순서·range·sidecar shape/privacy 대조 | 8개 추가 planning template에는 frozen profile case가 없다. 24개 template의 28개 함수 호출에서 입력/출력 이름 배열이 null이다. |
| context metadata | worker별 `inputs.json`과 `worker-partitions.json`의 동일 해시 복사본 8개를 찾고, context SHA 및 프로파일의 DML/range와 교차 검증 | context에 기록된 원래 runtime tree·worker input manifest 위치는 없고, 실제 worker data bytes와 live FederationMap도 없다. |
| PUBLIC ignore | 14개 `@Ignore` planner/placement test를 파일·메서드·사유·해시와 함께 기록 | 보호된 fixture 결과를 PUBLIC 조건의 인증으로 확장하지 않았다. |

발견 inventory는 [workloads.json](../src/test/resources/fedplanner/plan-space/workloads.json), 조건 backlog는 형제 저장소의 [`plan-space-cells.json`](/home/mchoi/cofee-evaluation/plan-space-cells.json), 미실행 예외는 [ignored-tests.json](../src/test/resources/fedplanner/plan-space/ignored-tests.json)에 있다. 48개 rule family와 과거 감사의 56개 transformation을 [rule-ledger.json](../src/test/resources/fedplanner/plan-space/rule-ledger.json)에 기록했지만, 48 family의 runtime tuple parity proof는 모두 `OPEN`이다. Ledger는 소스 drift 검출 장치이며 규칙 정당성 증명이 아니다.

## 구현한 검증 경로

1. **builder 이전 독립 경계.** `oracle/semantic`의 plain DTO, mixed-radix 원시 열거기, 별도 legality checker 및 literal/mutation fixture를 추가했다. `shadow/PrebuilderSnapshot`은 AST/HOP/CFG, ordered input occurrence, source, 함수·inlined call·nested control·recompile metadata를 후보 생성 전에 캡처하고 별도 verifier로 원래 graph와 대조한다. Production 의미론을 읽는 adapter는 semantic core 밖에 두고 독립성 회귀를 실행했다.
2. **새 공간 `P`와 기존 exhaustive `E` 관측.** Production test adapter는 policy 이전 placement·receipt·graph-owned relocation의 raw 선택지를 ordinal별로 기록한다. Exact test adapter는 목적함수 pruning 이전의 model assignment와 hard-factor 결과, occurrence·입력 순서·action·authority 식별자를 기록한다. 두 경로 모두 전체 workload physical domain 증명이 없으면 receipt를 `UNKNOWN`으로 남긴다. Protected B-21에서 decision graph 밖 candidate owner를 발견해 raw receipt 좌표를 버리지 않고 보존했다.
3. **분할·저장·비교.** 형제 저장소 `calibration/plan_space_verify.py`는 독립 raw index 범위, 병렬 shard, checksum 기반 resume/cache, 정렬된 canonical bytes의 정확한 streamed set 비교, per-index audit와 certificate 재검사를 제공한다. Synthetic test가 runner 동작을 확인했지만, 실제 447개 cell에서 세 전체 집합이 비교된 것은 아니다. Full semantic tuple coverage가 미구현이므로 full status는 `UNKNOWN`이다.
4. **재사용 입력 번들.** [bundle-index.json](/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/input-bundle-context-pinned/bundle-index.json)은 당시 이용 가능한 757개 파일 identity를 613개 SHA-256 객체로 중복 제거해 보존한다. index SHA-256은 `083e1a86a93346f7d58943c91e3b795fd05638b6bfa04f970cfa588a7cc5b0fa`다. 동일 경로를 다른 입력으로 덮어쓰지 못하고 객체 손상·source drift를 검사한다. worker 데이터와 runtime state가 없으므로 번들의 `planCoverage`도 `UNKNOWN`이다.

## 실행 증거와 관찰

| 검사 | 결과 | 해석 |
|---|---|---|
| inventory·rule·PUBLIC 제외·447-cell backlog drift gate, bounded 회귀 | 당시 통과 | 등록 범위와 구현된 작은 의미론의 회귀 검출이다. |
| Python 단위 검사 | SystemDS 8건, evaluation 27건 통과 | 번들 손상·metadata hash/range 변조·runner shard/cache 등을 포함한다. |
| Java 집중 검사 | 14 class, 54 case, failure/error/skip 0 | 실제 56개 프로파일 DML의 snapshot capture 포함. |
| `full` 실행 및 독립 `check-certificate` | 모두 `UNKNOWN`, return code 1 | [certificate-full.json](/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/5325fa7ba44ade685ed44724cc16b5773ca1468c3b36b5422f51589f50579141/certificate-full.json): 447 cell 중 283 `UNKNOWN`, 148 `UNSUPPORTED`, 16 `HISTORICAL`; rule tuple coverage `UNKNOWN`. |
| Protected B-21 Exact 원시 model | 6,048개 ordinal 전체 export, 압축 archive·해제 SHA·ordinal/audit 재검사 통과 | [pack.json](/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/fixtures/E-B-21-full-model/pack.json)의 `planCoverage=UNKNOWN`. 이는 현재 **model 곱** 전수이지 feasible physical plan 전수가 아니다. |
| Raw product 크기 추정 | B-21 `P`: 324,699,527,577,600; B-22 `P`: 37,748,736 | 표현상의 조합 수이며 feasible plan 수가 아니다. 두 P fixture에서는 각각 3개 ordinal만 표본 export했다. 임의 cap이나 샘플을 전수 검증으로 세지 않았다. |

실행 당시 로그는 [full-final-context-pinned.log](/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/full-final-context-pinned.log), 관련 파일의 SHA-256 결합은 [attempt-context-pinned.json](/grid/3/cofee-lm-sweep-mchoi-20260914/plan-space-certification-20260921/attempt-context-pinned.json)에 남겼다. 이 receipt는 `UNKNOWN` 시도의 증거 색인이지 PASS certificate가 아니다. Python/shell syntax 및 두 저장소의 `git diff --check`도 당시 통과했다. Official Docker workload runtime replay는 실행하지 않았다.

## 보고 시점의 현재 checkout과 과거 인증서 구분

최종 시도 뒤 **같은 공유 checkout의 production planner 파일이 변경됐다.** 보고 작성 시점에는 inventory·제외 ledger·backlog 검사는 통과하지만 `build_plan_space_rule_ledger.py --check`가 source drift로 실패한다. 과거 번들은 `--check`로 객체 무결성 `PASS`이나 `--check --check-current`는 `CandidateSelections.java` 해시 차이에서 실패한다. 보존된 인증서를 현재 dirty checkout에 적용할 수 없다. 이 변화의 의미론이나 새 코드의 품질은 이번 인증서가 판정하지 않는다. 기존 번들·certificate는 덮어쓰지 않고 과거 시도 증거로 보존한다.

## 남은 수용 기준과 재실행 순서

전체 PASS를 위해서는 먼저 148 unsupported/16 historical 발견의 역할과 115 in-scope 발견의 모든 실행 조건을 확정해야 한다. 각 cell의 DML/import·data bytes·metadata·privacy/release·실제 FederationMap·compiler/HOP/CFG·JAR/build를 함께 고정하고, 원래 경로가 사라진 snapshot 입력을 검증 가능한 현행 입력으로 다시 연결해야 한다. 공식 `run_LAN_docker.sh`는 현재 확인된 범위가 protected 14 case, worker 4, LAN planning이며 전체 corpus launcher가 아니다.

그다음 모든 cell에서 가능한 원시 물리 선택지의 유한 경계와 legality를 runtime branch별로 독립 명세화하고, 48 family의 모든 실제 capability tuple 및 56 transformation의 보존 의무를 positive/negative fixture와 연결해야 한다. 함수·transient·recompile·action 공유·authority·geometry·privacy·native cycle도 포함해야 한다. Full `P`, `E`, `R` exporter를 같은 physical identity로 runner에 연결한 뒤 모든 shard를 완료하고 양방향 집합 차집합과 미판정 0개를 검사해야 한다. Lowering/runtime 대표 plan은 공식 Docker trace로 따로 확인한다. 하나라도 누락·미판정·미실행이면 전체 PASS를 내지 않는다.

현재 checkout에서 재개할 때는 아래 순서를 따른다. Production 변경을 검토한 뒤 rule ledger를 **재생성·리뷰**하고, 이전 번들을 덮어쓰지 않는 새 output 경로를 사용한다. `full`의 return code 1과 `UNKNOWN` certificate는 현재 의도된 실패 상태다.

```bash
cd /home/mchoi/systemds-g009-integration
python3 scripts/fedplanner/build_plan_space_inventory.py --check
python3 scripts/fedplanner/build_plan_space_rule_ledger.py --check  # 현재 source drift로 실패
# production 변경을 검토한 뒤 ledger를 재생성·리뷰한다. 재생성만으로 rule proof가 끝나지는 않는다.
python3 scripts/fedplanner/build_plan_space_rule_ledger.py
python3 scripts/fedplanner/build_plan_space_rule_ledger.py --check
python3 scripts/fedplanner/build_plan_space_exclusion_ledger.py --check
python3 /home/mchoi/cofee-evaluation/calibration/build_plan_space_cells.py --check
python3 scripts/fedplanner/freeze_plan_space_bundle.py --output NEW_BUNDLE_DIRECTORY
python3 scripts/fedplanner/freeze_plan_space_bundle.py --output NEW_BUNDLE_DIRECTORY --check --check-current
scripts/fedplanner/run_plan_space_certification.sh tiny
PLAN_SPACE_ARTIFACT_ROOT=NEW_ARTIFACT_DIRECTORY PLAN_SPACE_JOBS=4 scripts/fedplanner/run_plan_space_certification.sh full
cd /home/mchoi/cofee-evaluation
python3 calibration/plan_space_verify.py check-certificate --certificate NEW_CERTIFICATE --expected-discovery-sha SHA256_OF_workloads.json
```

현 시점에서 결론은 명확하다. 작은 fixture/정적 입력/runner 무결성의 회귀 기반은 마련됐지만, 모든 워크로드에 대한 `missing=0`, `extra=0`, `unknown=0`의 독립 전수 증거는 아직 없다.
