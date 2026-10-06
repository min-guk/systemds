# 세션 이슈 — 2026-10-07

## Derived supply sharing 잔여 항목 — 구현·실험 완료, 확대 Exact 한계는 별도 잔여

- **요청/환경**: 사용자가 이전 결과 보고서의 남은 항목 모두 진행을 요청했다. 전용 worktree `/home/mchoi/w1357-derived-supply-sharing-20261006`, 기준 `ada24ffd4be4d17240f32f5bd1c2b5a98e1a0c8a`에서 기존 변경을 보존했다. 결과는 [완료 보고서](DERIVED_SUPPLY_REMAINING_WORK_2026-10-07_KO.md)와 [remaining-validation.json](experiments/automatic-supply-sharing-20261007/remaining-validation.json)에 있다. 미커밋 상태다.
- **Flat 증상/원인**: QB/QC의 공동 S→UB 이동 후보는 있었지만 QM의 required realization support가 child의 입력 공급 경로까지 동일해야 한다고 요구해 합법적인 complete assignment를 거부했다. 강제 assignment에서 두 hard 위반과 호환 parent 후보 0개를 확인했다.
- **Flat 해결/수정 파일**: `PlacementIdentity`, `CandidateSelections`, `ExactPhysicalModel`에서 같은 compiled owner가 정확히 같은 DURABLE_MAP을 만드는 required support에 한해 input-route 차이를 허용한다. Candidate 자신의 receipt와 DIRECT binding·boundary 비교는 그대로다. 후보333/hard149/encoded-factor335는 유지되고 auxiliary는112→116이다. 출력 map·owner 차이 음성 검사와 독립 factor-cell oracle, 기존 golden hash를 통과했다.
- **PHI 증상/해결**: outer snapshot N개를 inner loop에서 K번 사용하는 entry/backedge 두 origin을 N×K번 과금했다. `ExactPhysicalCostModel`이 transparent ancestry의 singleton loop carrier와 entry/backedge·context·profile을 증명한 경우에만 direct owner의 생성 횟수를 N으로 계산한다. 후보별 source mask와 GET creation scope를 사용한다. `PlacementRelationClosure`에는 upstream `825acfca…`의 grounding 보존 수정을 반영했고 임시 전체 재계산 fallback은 제거했다.
- **PHI 검증/제한**: `ExactLoopPhiSupplySharingTest` 2/2 PASS. N=K=3일 때 direct LOUT/FOUT upload·FOUT staging collection은9→3, inner semantic update는9 유지. 자동 PHI DML도 Local/Global 모두9 supplies/3 creations/6 hits/3 GET/3 PUT이다. Branch guard·함수 return·복수 carrier는 증명 범위 밖이다. 같은 occurrence/layout의 합법적인 fresh-owner 대안 직접 음성 fixture는 확보하지 못했다.
- **메모리 검증 도구**: Test-only worker launcher에서 기존 STATIC cache를 명시적으로 초기화한다. Production 기본 cache 비활성 정책은 유지한다. Worker별 temp/scratch 분리, 실제 cache 활성화·budget·target FS write/restore·supply identity를 gate로 검사한다. 초기 경로 충돌과 bind 실패 run은 성공 근거에서 제외했다.
- **메모리 결과**: 동일216 MiB 공급/선택계획으로1 GiB와256 MiB 각3회 실행. 전부9 supplies/3 creations/6 hits/3 GET/3 PUT. 256 MiB target의 FS restore4/6/4회에도 추가 업로드 없음. 실행 중앙값9.842→13.662초; source 파일 읽기와GC가 증가했다. Canonical cost bits는 같고 비용3634.783943ms이다. 3회 관측값으로 일반 보정계수를 만들지 않는다.
- **정리·잔여**: 각 run의3개 WORKER_RESET CLEAR 응답을 확인했고 logical canonical count/bytes는0이다. SOURCE_REMOVAL은 비동기 정리 제출 성공과 응답 확인을 구분한다. 기존 `cleanupEnabled(false)` 때문에 활성 cache backing 파일은 남는다. Worker-local disk I/O·GC·파일 재읽기는 정적 비용 모델의 별도 항목이 아니다. 전체 plan/surface fingerprint는 run별로 다르지만 선택된 native/supply/receipt/lifetime 내용과 별도 semantic digest는 같다.
- **회귀 결과**: Java220건=218 PASS/1 ERROR/1 SKIP; Python42/42; 자동 DML8/8; 메모리6/6; shell/Python compile/diff check PASS. Source ordinal reflection7건을 현행3인자 계약에 이식해 검증을 유지했다. 함수 fixture의 오래된8개 proof-path 기대값은 canonical formal map의2개 물리 계획과 정확한 DIRECT binding으로 바로잡았다. 상세한 certificate ERROR 귀속은 다음 항목에 기록한다.
- **재현/증거**: `run_LAN_docker.sh --supply-sharing-e2e` 사용. 원본 root `/grid/3/cofee-lm-sweep-mchoi-20260914/automatic-supply-sharing-20261006/`; 최종 run은 `remaining-automatic-final-r2`, `remaining-flat-final-r7`, `remaining-memory-216m-{1g,256m}-final-r{1,2,3}`. Production source inventory와 현재 파일의 hash가 모두 같다.
- **규칙/회귀 위험**: runtime fallback, retained 선택 차원, privacy 또는 TW/TR·함수 경계 완화는 추가하지 않았다. 다른 source/map의 잘못된 지원, source 갱신 후 재사용, 다른 identity의 runtime 실행을 각각 negative unit·lifecycle·정확한 identity join으로 감지한다.

## Certificate campaign의 잘못된 후보 기대와 대형 Exact 한계 — fixture 해결, solver 제한 미해결

- **증상/원인**: `ExactPhysicalModelCertificateTest`가 KMEANS의 coarse AVAILABLE derived emission을 무조건 executable candidate로 기대했다. 해당 입력은 필수 ROW FOUT이지만 compiled producer가 CP/LOUT만 가능하고 relocation과 DIRECT 지원이 없다. 수정 전 동결 코드에서도 같은 assertion이 실패했다.
- **해결/의사결정 근거**: 테스트에서 candidate가 없을 때 neutral graph·compiled edge·source legal state·relocation·support clause로 명시적인 impossible-input 조건을 독립 증명한다. Production builder를 oracle로 사용하지 않는다. 애매한 복수 producer나 FOUT/relocation/DIRECT 가능성이 하나라도 있으면 면제하지 않는다. 별도 읽기 전용 리뷰에서 soundness blocker를 발견하지 못했다.
- **새로 드러난 제한**: 앞선 잘못된 assertion을 통과하자 L2SVM에서 Exact factor가 커진다. 동일 수정 테스트를 이전 `automatic-sharing-final-r7` main/dependency에 올리면 optimizer의 `EXACT_VE_FACTOR_CELL_OVERFLOW`; 현재 코드는 model analyze의 `EXACT_VE_FACTOR_LIMIT_EXCEEDED|cells=19322130|limit=10000000|input`이다. 양쪽8건 중7 PASS/1 ERROR. 대형 campaign 실패는 이번 작업 전에도 존재하지만 도달하는 한계는 달라졌다.
- **검증/증거**: 최종 중앙 Maven `-Dtest=ExactPhysicalModelCertificateTest test`에서도 동일 ERROR를 확인했다. 로그/XML은 `remaining-regression-final`, 동일 수정 테스트 baseline 비교는 `remaining-regression-certificate-proof`에 명령·source hash·trace와 함께 보존했다.
- **잔여/위험**: solver/test limits와 legal candidates는 그대로 유지했다. 해당 L2SVM 끝까지와 뒤 workload는 이 campaign에서 검증되지 않았다. 한도만 높이는 scratch 진단도 더 큰 cell/materialization 한계에 도달했으며 production 해결책으로 채택하지 않았다. Solver factorization 개선과 전체 대형 campaign 완주는 별도 남은 문제다. 테스트 전체 무실패로 보고하지 않는다.

## Derived supply sharing 자동 DML·메모리 검증 — 완료

- **환경/요청**: 사용자가 [추가 검증 보고서](DERIVED_SUPPLY_E2E_AND_MEMORY_VALIDATION_2026-10-06_KO.md)대로 실행을 요청했다. 전용 worktree는 `/home/mchoi/w1357-derived-supply-sharing-20261006`, 기준은 `ada24ffd4be4d17240f32f5bd1c2b5a98e1a0c8a`다. 10월 6일 시작한 작업의 완료 기록이다.
- **문제 정의**: 기존 proof는 특정 공급 후보/직접 구성 instruction의 1×/N× 동작을 검사했다. 파싱부터 optimizer 자동 선택·실제 DML loop까지의 연결과 planned copy의 메모리 관측이 필요했다.
- **해결/수정 파일**: 기본 OFF `RefedReuseAudit`, `FederationUtils`/`FEDRefedInstruction` lifecycle 연결, source 제거·mutation 이유 전달, `FederatedData`의 기존 CLEAR 성공 결과 전달, test-only `AutomaticSupplySharingDockerProbe`, `run_supply_sharing_e2e.py`, `run_LAN_docker.sh` dispatch 및 Java/Python 회귀를 추가했다. Buffer는 scalar/digest만 보유하며 원본 객체를 retain하지 않는다. Candidate·비용·privacy·boundary·cache 유지 정책은 바꾸지 않았다.
- **최종 검증**: `automatic-sharing-final-r7`의 Local/Global invariant 2건은 공급3/생성1/GET1/PUT1, updated 2건은 바깥에서 만든 새 값3개 × 안쪽사용3회로 공급9/생성3/hit6/GET3/PUT3이다. CP 출력 일치, canonical 비용 bits·surface·선택 state·lifetime 일치, fallback/repair0. Updated canonical peak는1이다. 후속 검토에서 정리 의미를 명확히 했다: 이전 두 copy의 SOURCE_REMOVAL은 비동기 cleanup 제출 성공이고, 마지막 WORKER_RESET은 CLEAR 응답 성공이다.
- **메모리 검증**: 24 MiB copy3개를 유지하는 `automatic-sharing-memory-72m-1g-final`과 `automatic-sharing-memory-72m-256m-final` 모두 PASS. 공급별 GET/PUT1회 유지, 종료 후 canonical0, 세 copy의 원격 정리 성공. Worker caching은 비활성이고 FS spill/restore0이다. 최종 실행3.997/4.015초, source worker GC26/39회였다. Profile당 한 번의 최종 측정으로 유의한 성능 차이나 spill 비용 정확도를 주장하지 않는다.
- **재현/증거**: 결과와 명령은 [최종 보고서](DERIVED_SUPPLY_AUTOMATIC_E2E_RESULTS_2026-10-07_KO.md), 수치는 [validation.json](experiments/automatic-supply-sharing-20261007/validation.json)에 있다. 원본 root는 `/grid/3/cofee-lm-sweep-mchoi-20260914/automatic-supply-sharing-20261006/`이다. 기존 workspace/실험은 수정하지 않았고 이번 변경은 commit/push하지 않았다.
- **검증 도구 문제/해결**: 같은 DML coordinator의 추가 observer UDF가 runtime audit를 위반하던 문제는 별도 observer JVM으로 분리했다. 성공·실패 후 남던 Netty client를 `clearWorkGroup`으로 정리했으며 audit를 완화하거나 observer에서 worker CLEAR를 보내지 않았다. 과거 실패 run과 수정 이력은 보존했다.
- **잠재 회귀 위험/감지**: 계측으로 인한 객체 보관·예외 변경, source/version 오귀속, audit 손실, 원격 정리 미확인을 각각 unit·strict lifecycle/dispatch gate·CLEAR 응답 검사로 감지한다. 감사 property는 process 동안 고정한다.

## 확대 회귀의 기존 reflection fixture 오류 — 초기 기록, 위 후속 작업에서 해결

- **증상**: 관련14개 Java 클래스75건 중68 PASS,7 ERROR,skip0. `ExactSharedSourceOrdinalReuseTest`5건과 `ExactSharedSourceOrdinalLifecycleTest`2건이 `NoSuchElementException`으로 실패했다.
- **원인**: 두 기존 테스트가 `AlternativeHeader` 생성자를14개 인자로 찾지만 기준 commit의 생성자는3개 인자다. 실패 위치는 각각 `construct:256`과 `construct:131`이다. 해당 test/encoding source는 이번에 바꾸지 않았다.
- **검증/대응**: 수정한 기존 production6개 파일의 HEAD source를 격리 class overlay로 컴파일해 두 클래스를 새로 실행했다. 같은7건·예외·위치가 재현됐다. 증거는 위 root의 `regression-baseline/evidence/`에 있다. 원래 실패와 나머지68건의 통과 XML을 보존하고 전체 무실패로 보고하지 않는다. Python은 신규15건+기존20건=35/35 PASS, shell 문법·Python compile·diff check PASS다.
- **잔여/위험**: 기존 reflection fixture를 현 encoding 계약으로 이식하는 작업이 남는다. 이번 runtime 관측 수정의 회귀로 잘못 분류하거나 오류를 숨기는 것을 baseline 재현 기록으로 방지한다.

## Flat loop의 미선택 공급과 lifetime 범위 — 초기 진단 기록, 위 후속 작업으로 대체

- **관측**: 초기 작은 matmul source는 native 요청에 실어 보내는 공급이 선택돼 공유 coverage를 충족하지 못했다. 이후 `updated-r5`의 protected elementwise 두 consumer에는 동일 S→B relocation 후보가 존재하지만 Local/Global 모두 선택하지 않았다. 해당 DML 자체의 수치 결과와 canonical recost는 통과했다.
- **확인/미확인 경계**: r5의 shape·source version·FULL target·anchor·physical identity는 후보 생성 단계에서 일치한다. 이것만으로 두 후보를 함께 선택한 전체 assignment가 합법이고 더 저렴하다는 증명은 되지 않는다. 숨은 결합 제약 또는 contribution 차이에 대한 진단은 미완료다. 정상 비용 선택이나 solver 버그 어느 쪽으로도 단정하지 않는다.
- **검증 fixture 결정**: 최종 updated는 `S=B+i`로 바깥 iteration마다 새 값을 만들고 안쪽 loop에서 반복 사용한다. 단일 origin의 creation profile3과 consumer profile9를 production 코드가 추적할 수 있어, 같은 version의 공유와 서로 다른 version의 분리를 자동 선택부터 검증한다. 후보·privacy·비용을 강제하지 않는다. 직접 갱신 PHI의 여러 origin을 정밀하게 묶는 lifetime 증명은 이 결과의 범위 밖이다.
- **기타 실패 보존**: 중간 elementwise fixture에서 보호된 Nary plus 및 nested divide의 privacy-safe placement 부재가 발생했다. Oracle 완화 없이 지원되는 matmul fixture로 검증했으며 실패 source/log는 보존했다.
- **후속/회귀 위험**: r5 두 consumer의 대체 assignment를 완전한 제약 검사와 contribution별 비용 차이로 조사한다. 이번 중첩 loop의3회 생성을 단일 loop SINGLE_USE 자동 선택 증거로 혼동하지 않는다. 72 MiB보다 큰 working set 및 활성 spill cache의 비용 측정도 별도 범위다.
