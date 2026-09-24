# 2026-09-24 세션 이슈

## 동결 v12 classpath에 Python bytecode가 다시 생성되어 P matrix 최종 결속 검사 실패

- **상태**: P/E model matrix 발행 및 독립 저장물 재검사 완료. 오염된 classpath 결속으로 시작한 Pca pilot은 최종 guard에서 중단됐고, 복구된 classpath와 전체 검증 행렬로 새 실행을 진행 중이다.
- **환경/조건**: `current-pe-build-v12-ledger-aware-candidate`, 612셀 P/E native-model capture. 계획/런타임 실행 문제가 아니라 검증 artifact의 동결 결속 문제다.
- **재현 절차**: `current-pe-campaign-v12-ledger-aware/p-models`의 612개 `COMPLETE` receipt가 발행된 직후 `capture_current_plan_matrix.py`가 `matrix source, classpath, catalog, or runner changed during capture`로 종료했다. 당시 `matrix.json`은 아직 없었다.
- **관측 증상**: receipt의 source SHA-256 `6dfd5fb7441b60724feb60c8272831a9a5bf4b37e59703a2e9354e6cb900d44e`, catalog SHA-256 `7d55c9f469077038f6d1b5a3a6a4ef5887bc55089712c2d74c1738b4812376ac`, runner SHA-256 `7feabc8972c2dbc142881796c0eb51d504f4afd2e74cd2014375edb4ed2c4352`는 현재 값과 같았다. classpath SHA-256만 초기 `bbcc1cd57bd649be2c12ca0f7191b4e1ae37366e9d787163b695b9f688924acb`에서 `3798c546a1451e9cc0b6598ab006d2aac6436270e25517c649717ba9af447427`로 바뀌었다.
- **원인 분석**: `run_current_pe_cell.py:class_tree_sha`는 `target/classes`의 모든 파일을 해시한다. 별도 artifact-only 검사에서 동결 빌드 안의 Python 스크립트를 import하면서 2026-09-24 16:43 CEST에 `target/classes/scripts/fedplanner/__pycache__`의 `.pyc` 5개가 다시 생성됐다. 이 파일들은 Java `.class`/JAR가 아니지만 전체 classpath digest를 바꾸었다. 5개를 격리한 후 source tree·JAR·Java 클래스 등 나머지 파일 해시는 보존됐으며, 원본 repo `target/classes`의 동일 경로 5개를 대입하는 조합만 초기 전체 digest와 일치했다.
- **해결 요약**: 새 `.pyc` 5개를 `current-pe-campaign-v12-ledger-aware/classpath-pycache-quarantine-20260924`에 보존하고 초기 digest와 일치하는 원본 5개를 동결 빌드로 복원했다. 복원 후 `class_tree_sha`가 정확히 `bbcc1cd57bd649be2c12ca0f7191b4e1ae37366e9d787163b695b9f688924acb`를 재현했다. 612개 P receipt는 삭제하지 않았고 같은 runner의 `--resume`으로 원문 모델 SHA를 다시 검사한 뒤 matrix를 발행하도록 재실행했다.
- **수정 파일**: 이 이슈 문서. 복구 대상은 소스 파일이 아닌 위 동결 빌드의 `.pyc` 5개이며, 원본/새 byte 모두 보존했다.
- **검증**: source/catalog/runner digest 동일, classpath 초기 digest 복구를 확인했다. 새/원본 `.pyc` 각각의 SHA와 복구된 전체 SHA를 저장한 `current-pe-campaign-v12-ledger-aware/classpath-restoration-diagnostic.json`은 SHA-256 `c01e484da6b6ff55650694ffaca92be2eb19fb650a390881532f6e84ce8956d0`이다. 같은 runner의 `--resume`이 612/612 모델 원문 해시를 다시 확인해 P `matrix.json`을 `COMPLETE`로 발행했다(SHA-256 `7f99da1553207443c7ed2c5297624895e22d3cc1ecc58e168a93a8dc866974fc`). 별도 P offline verifier도 612/612 구조 검증 `PASS`, 실패 0개였다(`p-models/verification.json` SHA-256 `b782d9af847093dc293463667a9555a1f115e186d264a22638a0d42602ebf33d`). E matrix도 612/612 `COMPLETE`, P와 동일한 source/class 해시로 발행됐다(`matrix.json` SHA-256 `42541ce2d52bf69162800555cf663d8fa904f76716a1e40ae813289a6a9377a6`). E 저장 factor 검증은 612/612 known, UNKNOWN 0, 실패 0으로 `PASS`였다(`verification.json` SHA-256 `8e6cf37b25dd801e3038c49ca0d1b1f00a8708e22b9fa9b9dfc23b0d55a53727`). Pca 물리 certificate는 완료 후 확인해야 한다.
- **잔여 이슈**: 향후 동결 빌드의 Python 사본을 실행할 때 `PYTHONDONTWRITEBYTECODE=1` 또는 외부 pycache 경로를 강제해야 한다. 현재 classpath 해시 계약은 실행과 무관한 Python 캐시까지 포함하므로 다음 버전에서 artifact 계약을 조정할 수 있지만, 진행 중인 v12의 결속 규칙을 소급 변경하지 않는다.
- **잠재 회귀 위험/감지**: 같은 위치에 `.pyc`가 다시 써지면 P/E·물리 run의 종료 digest 검사가 다시 실패할 수 있다. 최종 matrix와 certificate의 source/class digest가 시작 receipt와 정확히 같은지 검사한다.
- **Pca 영향**: 별도 1셀 pilot의 P/E model matrix는 classpath가 오염된 동안 시작해 `3798c546a1451e9cc0b6598ab006d2aac6436270e25517c649717ba9af447427`에 묶였다. 전체 612셀 matrix의 동일 P/E gzip 모델 바이트와 각각 완전히 같았지만, pilot의 P/E 물리 행 출력 후 원래 classpath를 복구하면서 `isolated build changed during native capture`가 발생해 certificate가 발행되지 않았다. 해당 물리 행은 진단용으로 보존하고 최종 근거로 사용하지 않는다. Pca w5를 복구된 `bbcc1cd57bd649be2c12ca0f7191b4e1ae37366e9d787163b695b9f688924acb` 빌드와 전체 검증 행렬 import로 다시 실행한다.
- **의사결정 근거**: oracle·planner·runtime 규칙은 변경하지 않았다. 이미 실행된 Java 클래스/JAR를 그대로 두고 검증기 캐시 오염만 원상복구하는 infrastructure 수정이다.

## 함수 라이브러리 applicability 영수증의 optional GMM 과잉 판정과 provenance 누락

- **상태**: 수정·독립 재검토 완료. 잘못된 사전 검토 receipt/ledger는 `invalid-pre-review-*` 및 후속 `invalid-pre-*` 이름으로 격리했고 유효 결과로 사용하지 않는다.
- **관측 증상/원인**: 첫 영수증은 `optional:gmm_p1_compat`를 같은 basename의 유일한 발견 파일에 연결해 `ACTIVE_IMPORTED_FUNCTION_LIBRARY`로 판정했다. 그러나 해당 inventory 행에는 source path·SHA·명시적 alias가 없어 이것만으로 같은 discovery라고 증명할 수 없다. 별도 검토에서 catalog↔inventory SHA 누락, probe source↔실행 class 결속 누락, parser nested class 해시 누락도 발견했다. v1 감사 코드에 v2 adapter를 직접 추가해 과거 producer SHA를 현재 코드의 자기 SHA처럼 기록한 중간 수정 역시 폐기했다.
- **해결**: 기존 `audit_current_scope_applicability.py`를 원본 SHA `2b8ccd467c6ff5edcc8c87b7625ca457d54afdc6dd3a1b8bdbb23daaae803134`로 복원했다. 새 `certify_scope_library_rows.py`는 실제 DML parser probe를 현재 소스에서 임시 디렉터리로 새로 컴파일하고 parser 본체·nested class 56개의 canonical tree SHA와 catalog/inventory SHA를 결속한다. 새 `audit_current_scope_library_resolution.py`가 v1 ledger를 부모로 받아 별도 v2 ledger를 발행한다. Common GMM과 SliceFinder 두 행만 구조적 imported function library로 해소하고 optional GMM을 포함한 162행은 `UNRESOLVED`로 유지한다.
- **검증**: 기존 v1 ledger SHA `cbd9bcfe2997c3060844d76753fcbe99569817e047e4cad3395f7c5f83ff3220` 및 `--check` 불변. 유효 v3 영수증 SHA `89a836c794949be14e65a3a43db982385310559f52063425a8cc844c1e543446`, 별도 v2 ledger SHA `b162f7b26753b095ebffa5d4cc7bec6698d512a35db0909e7891638b790bf894`, 둘 다 저장물 `--check` 통과. 독립 재검토의 HIGH/MEDIUM 잔여 지적 0건, 전체 fedplanner Python 240개 테스트 통과.
- **잔여 범위**: 두 행의 증거는 함수 정의만 있는 source와 정확한 활성 셀 import의 구조적 연결이다. 함수의 runtime 의미·실제 호출 reachability 또는 다른 162행의 현재 적용성을 인증하지 않는다.

## Pca12 집계 실행기의 E factor status 계약 불일치

- **상태**: 첫 실제 Pca certificate에서 발견해 수정했고 표적 회귀 9개가 통과했다.
- **관측 증상/원인**: 집계 실행기의 `verify_cell`은 E factor status를 문자열 `PASS`로 예상했지만 실제 `run_current_pe_cell.py verify`는 `INDEPENDENT_FACTOR_TABLE_VERIFIED`를 반환한다. 그대로 두면 물리 집합 차집합 0인 셀도 집계에서 `ERROR`가 된다.
- **해결/검증**: 집계 실행기를 실제 verifier 계약에 맞춰 수정하고 긍정 테스트를 추가했다. `cell_b86071769d8225c53d14`의 생산 certificate와 별도 artifact-only 재검사는 모두 P/E 물리 identity 600개씩·P-only/E-only 0으로 `PASS`였으며, 집계 실행기의 실제 한 셀 smoke도 `CAPTURED_EQUAL`을 반환했다. P acceptance는 여전히 `PRODUCER_RECEIPT_ONLY`다.

## 템플릿 적용성 영수증의 생성 바이트·권한 연결 누락

- **상태**: 수정·독립 재검토 완료. 결함이 있던 v1/v3과 v2/v4 receipt/ledger는 각각 `invalid-pre-independent-render-v1`, `invalid-pre-workload-authority`로 격리했다. 이 판본들은 유효한 판정에 사용하지 않는다.
- **관측 증상/원인**: v1은 inventory의 템플릿 경로·SHA와 catalog의 생성 프로그램 SHA를 각각 확인했지만, 실제 프로그램이 해당 템플릿에서 만들어졌다는 연결을 검사하지 않았다. ALS/PCA 템플릿 경로·SHA를 서로 바꾸고 condition hash를 다시 계산해도 `COMPLETE`가 나왔다. parent source evidence·successor discovery authority·전역 cell ID 중복에도 검증 공백이 있었다.
- **해결/검증**: 독립 렌더러가 13개 정확한 템플릿과 동결 partition/context/protocol/seed/metadata/output에서 224조건의 프로그램을 만들어, 서로 다른 56개 프로그램의 실제 바이트·SHA와 대조했다. Parent source evidence, inventory alias, discovery worker·workload 권한, 전역 224 cell ID 유일성을 검사한다. ALS/PCA template 및 discovery 교환, sourceEvidence 삭제, alias 경로 변조, 중복 cell ID 변조 테스트가 모두 실패를 검출한다. 최종 v3 receipt SHA-256 `12773007df5ef162960bae4be67404f6627455d93f14ee4ce5881b97d337e08f`, v5 ledger SHA-256 `cce670d6c863b2d0910c87b88e6048a3f46784957ac47efa6129da50e373b001`이며 둘 다 `--check`를 통과했다. 독립 재검토 HIGH/MEDIUM 잔여 지적 0건, 전체 fedplanner 테스트 266개 통과. 164행 중 15행 resolved·149행 unresolved이며 runtime 의미는 여전히 이 영수증의 범위 밖이다. 초기 실패 receipt SHA-256 `30338071bea4721c3003cc350952bd866483d002c0268a263b8feb3e3d66042b`, ledger SHA-256 `de1fab027e1b46d0ceb818225816d711b37ebbc2800498b503648899660c6b9f`는 진단용으로만 보존한다.

## Harness alias 적용성의 과도한 승격

- **상태**: 첫 `ACTIVE` 영수증과 v6 ledger를 `invalid-review-overclaim` 경로로 격리하고, 후보 증거만 남기는 수정본을 독립 재검토해 승인받았다.
- **관측 증상/원인**: repository의 ML10 harness template 9개는 frozen planning 144조건의 프로그램을 독립 렌더링하면 같은 바이트가 되지만, 동결 planning launcher나 catalog가 실제로 그 harness 경로를 선택했다는 직접 근거는 없다. 별도 ML10 stage의 동일 바이트와 source manifest 경로만으로 planning 적용성을 `ACTIVE`로 낮춘 것이 오류였다.
- **해결/검증**: 최종 `current-scope-harness-alias-render-candidates-v2/receipt.json` SHA-256 `2964ffa2a265ad0e49533a6ca3206a014043b6e5fa32a46cffa847bfa32fe44e`는 정확한 출처·조건·재렌더링 동등성만 고정하고 active selection/reference 및 repository→stage ancestry는 미증명이라고 명시한다. `current-scope-applicability-audit-v6-harness-alias-candidates/ledger.json` SHA-256 `a7c42a14e7fdf2abae5290ca7d063cb2365584085ca535725335c93395e4a0af`는 9행을 전부 `UNRESOLVED`로 유지해 총 149행 미해결이다. 집중 테스트 13개와 두 artifact `--check`, 별도 검토가 통과했다.
- **잔여 범위**: 실제 planning 선택 경로 또는 정확한 source ancestry를 추가로 증명하기 전에는 이 후보를 분모 폐쇄로 사용할 수 없다.

## E factor-forest 진단 검증기의 경계 사례와 자원 상한

- **상태**: 독립 검토의 HIGH/MEDIUM 지적을 수정하고 최종 재검토 승인. 이 도구는 물리 plan 비교기나 P/E 동등성 인증기가 아니다.
- **관측 증상/원인**: 초기 artifact-only 검증은 재서명된 상태·claim 승격을 받아들였고, source replay 예산 초과에도 `PASS`를 반환했다. 이후 all-singleton·zero-factor E 모델을 처리하지 못했고, 저장 gzip 및 재생성 예산에 verifier-side 상한이 빠져 있었다.
- **해결/검증**: 정확한 진단 계약·commitment·trace를 강제하고 예산 초과는 `INCOMPLETE`로 닫았다. 빈 변수·빈 factor의 상수 관계를 명시적으로 표현하며 `UNKNOWN`은 definite-ALLOW에서 제외한다. 저장 파일의 압축/해독 크기와 재생성의 여섯 예산을 해시·파싱·build 전에 검사한다. 최종 관련 테스트 41개와 250개 작은 E 모델의 독립 전수 satisfiability 대조가 통과했고, 별도 코드 재검토에서 잔여 지적은 0건이었다. P2 진단 artifact SHA-256은 `bee3cf416a7e7453dd53755391958ff26439763130475279839231d683a20711`이다.
- **잔여 범위**: P2 결과는 E hard-factor native satisfiability만 다룬다. 물리 좌표와 P acceptance 규칙을 연결하지 않았고 큰 모델의 독립 exhaustive 의미 재생도 차단 상태이므로 P/E 동등성 증거로 사용하지 않는다.
