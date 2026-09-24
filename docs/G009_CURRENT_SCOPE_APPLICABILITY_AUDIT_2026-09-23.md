# G009 current scope applicability audit

작성일: 2026-09-23  
판정: **INCOMPLETE — `FULL_CURRENT` 승격 불가**

## 대상과 판정 원칙

동결 v4 catalog에서 현재 P/E campaign 밖에 놓인 `UNSUPPORTED` 148행과
`HISTORICAL` 16행을 전수 감사했다. 파일명 일치, launcher에서 이름이 보이지
않는다는 사실, `archive` 디렉터리라는 사실만으로 현행 owned workload에서
제외하지 않았다. 역할과 도달성의 자동 증거가 부족한 항목은 모두
`UNRESOLVED`로 유지했다.

감사기는 164라는 행 수만 확인하지 않는다. 감사 대상 catalog cell ID와
discovery ID가 각각 유일한지 검사하고, discovery ID 집합이 source inventory의
`UNSUPPORTED`/`HISTORICAL` ID 집합과 정확히 같은지 확인한다. 한 discovery를
복제해 다른 discovery를 지우거나, 알 수 없는 ID로 한 행을 교체하면서 총 행 수를
164로 유지하는 변조도 거부한다.

감사 입력은 다음 SHA에 결속된다.

- catalog: `/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/frozen-capture-cohort-derived-argv-v4/catalog.json`, SHA-256 `7d55c9f469077038f6d1b5a3a6a4ef5887bc55089712c2d74c1738b4812376ac`
- source inventory: `src/test/resources/fedplanner/plan-space/workloads.json`, SHA-256 `62a48eb5c5ece38dba9beb2e2bf944fb54e9b3ef2940c2d27dd2e93971f0c10c`

## 결과

machine-readable ledger는
`/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-scope-applicability-audit-v1/ledger.json`에 있다.
파일 SHA-256은 `cbd9bcfe2997c3060844d76753fcbe99569817e047e4cad3395f7c5f83ff3220`,
canonical record SHA-256은
`1d5f8d167174902aa74ec26bcc196c3a8479792acbbb934f73237405a0d9b779`이다.
ledger는 producer script SHA-256
`2b8ccd467c6ff5edcc8c87b7625ca457d54afdc6dd3a1b8bdbb23daaae803134`도
입력 결속에 포함한다.

동일 ledger의 `frozenCohortSupersession`은 `IN_SCOPE` 671행 가운데
`conditionId=unresolved`인 placeholder 59행을 같은 discovery ID의 동결
successor 388행에 결속했다. 분포는 28×1, 8×4, 10×12, 13×16이며,
나머지 planning snapshot 224행을 포함한 실제 동결 셀은 총 612행이다.
모든 612행의 sourceFiles를 재해시해 서로 다른 파일 830개의 byte를 검증했다.
placeholder 및 successor ID 중복, 조건 중복, 소스 변조는 거부한다.
이 관계의 canonical record SHA-256은
`2f969586bb81674b35e45557913f3c8f495229acf06205919b2dd1c9cca65a52`다.
59행의 판정은 `SUPERSEDED_IN_FROZEN_COHORT`이며 범위상 중복 제거를 뜻한다.
별도 164개 적용성 미해결과 SliceLine·microbench의 파생 입력 정책 문제는
그대로 남으므로 `FULL_CURRENT` 승격 근거는 아니다.

| 항목 | 수 |
|---|---:|
| 감사 행 | 164 |
| 기존 `UNSUPPORTED` | 148 |
| 기존 `HISTORICAL` | 16 |
| 최종 `UNRESOLVED` | 164 |
| source 후보가 있고 SHA를 검증한 행 | 161 |
| source 후보 자체가 없는 optional 등록 | 3 |

역할 후보는 archive revision 16, data/FederationMap generator 36, DML dependency
11, entrypoint 22, entrypoint-or-template 58, reference implementation 14,
unknown role 7이다. 이는 조사 우선순위이며 범위 제외 판정이 아니다.

source 후보가 전혀 없는 세 등록은 `optional:alsCG`, `optional:naiveBayes`,
`optional:msvm`이다. 나머지 optional 등록 중 일부는 동일 basename의 서로 다른
source revision이 여러 개여서 이름으로 하나를 선택할 수 없다.

16개 historical source는 모두 지정된 archive 위치와 inventory SHA가 일치한다.
그러나 frozen input 조건과 현행 workload revision에 대한 equivalence 또는 명시적
scope contract가 없으므로 제외를 승인하지 않았다. 148개 unsupported도 launcher
이름 언급과 DML 간 basename 참조를 증거로 기록했지만, 간접 경로와 문자열 조립을
증명하는 call graph가 없어 제외하지 않았다.

## 재현과 검증

다음 명령은 ledger를 원본 입력에서 다시 생성해 byte-for-byte 비교한다. Ledger가
`INCOMPLETE`여도 무결성 검증 자체가 성공하면 exit 0이다.

```bash
python3 scripts/fedplanner/audit_current_scope_applicability.py \
  --check \
  --output /grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-scope-applicability-audit-v1/ledger.json
```

새 ledger를 생성하는 명령은 미해결 항목이 있으므로 의도적으로 exit 1을 반환한다.
이는 164행이 catalog에서 사라지거나 성공으로 오인되는 것을 막는 gate다.

```bash
python3 scripts/fedplanner/audit_current_scope_applicability.py \
  --output /grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/current-scope-applicability-audit-v1/ledger.json
```

회귀 검사는 다음과 같다.

```bash
python3 -m unittest scripts/fedplanner/tests/test_audit_current_scope_applicability.py
```

## FULL_CURRENT 전 최소 증거

각 미해결 source discovery에 대해 다음 중 하나가 필요하다.

1. 현행 launcher에서 해당 source까지 이어지는 경로와 정확한 frozen input 조건을
   동결해 새 P/E cell로 승격한다.
2. helper/library/data generator라면 parser 기반 top-level 실행 가능성, 실제 import
   edge, 이를 사용하는 active cell의 source hash 결속을 제시한다.
3. 중복 revision이라면 source byte뿐 아니라 imports, compiler arguments, input,
   privacy, partition, worker와 network 조건의 canonical identity를 증명한다.
4. archive/deprecated source라면 현행 owned workload contract와 revision equivalence
   또는 명시적 제거 근거를 SHA로 고정한다.

이 근거가 채워져 164개 결정이 모두 기계 검증되기 전에는 612 cohort를
`FULL_CURRENT`라고 부를 수 없다.

## 기존 capture 보존 조건

v11 P/E model bytes와 기존 v4 artifact는 변경하지 않는다. 새 catalog scope를 만들
때는 기존 612개 각 셀의 `sourceBinding`, source/input bytes, compiler arguments,
privacy, partition, worker와 network binding이 기존 셀과 동일함을 먼저 증명해야
한다. 그 뒤 별도 artifact root에서 receipt binding만 재발행할 수 있다. 적용성
감사가 미해결인 현재 단계에서는 rebinding을 구현하거나 수행하지 않는다.
