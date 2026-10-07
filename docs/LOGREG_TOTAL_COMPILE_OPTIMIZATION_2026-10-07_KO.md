# LogReg 전체 컴파일 10초 목표 — 2026-10-07

## 범위와 상태

사용자가 확정한 목표는 common + planner + lowering을 포함한 **전체 컴파일 10초 이하**다. 실제 ML 학습 실행 시간은 별도다. 기준은 직전 게시본 `d26bb596102557d4e9d8c76fd5530892688155ad`이다. **최종 검증까지 완료했지만10초 목표는 미달이다.** 전체 컴파일은3회 평균28.877→23.053초로20.17% 감소했고, 중앙값은27.652→24.195초로12.50% 감소했다. 수정본의 범위는20.397–24.566초다. 최솟값을 목표 달성으로 해석하지 않는다.

기존 실제 builtin `multiLogReg` fixture를 그대로 사용한다. X 192×8, 3 classes, maxi10/maxii5, PRIVATE_AGGREGATE X와 local labels, ROW worker 3개, Docker 4 CPU/8GiB, main JVM `-Xmx3g -XX:ActiveProcessorCount=4`다. 모든 workload는 `scripts/fedplanner/run_LAN_docker.sh`로 실행했다. 학습 iteration·입력·합법 후보·비용·privacy/TR-TW를 줄이거나 runtime fallback을 추가하지 않았다. StepLM/GLM 및 큰 W1의 성능은 이 결과로 일반화하지 않는다.

## 최종 반복 측정

| 순서 | d26 common(s) | 수정 common(s) | d26 전체 컴파일(s) | 수정 전체 컴파일(s) |
|---|---:|---:|---:|---:|
|1|17.825|17.794|27.200|24.566|
|2|22.636|17.230|31.780|24.195|
|3|17.684|13.968|27.652|20.397|
|평균|19.382|16.331|28.877|23.053|
|중앙값|17.825|17.230|27.652|24.195|

기준본/수정본을 번갈아 실행했으며 빌드·테스트와 성능 실행을 겹치지 않았다. 각 실행에서 LogReg 모델16계수의 CP/FED 최대 절대 오차는2.22e-16, 시간 제외1,224 checkpoints 및 analysis fingerprint는 동일하고 runtime audit/conversion 위반0이다. 실제 학습 시간 평균은3.083→3.299초이며 학습 속도 개선은 주장하지 않는다. 입력·fixture/config·runner/dispatch·dependency·test source/class digest와 Docker4CPU/8GiB 조건이 같다. 최종3회 main source/class digest도 같고 동결 inventory를 다시 hash해 확인했다. 기준 main source는 Git d26 blob과 전수 일치한다.

L2SVM은 양쪽 각1회 regression smoke에서 모델·4 checkpoints·fingerprint·audit를 통과했다. 성능 목표의 주된 대상은 LogReg다. 소형 fixture의 결과를 큰 W1이나 임의 데이터 크기에 일반화하지 않는다.

| Ablation(각1회) | common(s) | 전체 컴파일(s) | 해석 |
|---|---:|---:|---|
|exact ordering/boundary/value-class hash 개선 제거|16.080|23.583|최종 반복 범위와 겹치므로 exact 변경의 고유 wall-time 기여율은 확정하지 않는다.|
|common/identity/publication/관측 비용 개선 제거|18.403|29.494|결합본보다 느렸지만1회이므로 변경별 기여율로 분해하지 않는다.|

각 ablation도 모델·fingerprint·1,224 checkpoints·audit 동치를 통과했다. Exact 변경은 cold oracle·정밀도·메모리 회귀를 통과했고 소스 검토로 확인한 중복 계산 제거를 유지하되, 단일 ablation만으로 확정적인 가속률을 부여하지 않는다. 공통 변경의 전체 효과와 개별 미세 변경의 효과도 구분한다. 공유 호스트와 cold JVM의 실행 변동이 존재하므로3회 측정의 평균/중앙값/범위를 함께 보고한다.

## 구현

| 변경 | 제거한 작업과 유지한 계약 |
|---|---|
| Exact elimination ordering | 단계 사이에도 지표를 유지한다. 제거 변수의 이웃과 새 fill edge의 공통 이웃만 정확히 무효화한다. 4개 ordering과 tie-break, 포화 산술을 유지한다. |
| Exact boundary merge | 내부 assignment를 동일 순서의 mixed-radix odometer로 열거하면서 child cell index를 증분 갱신한다. sparse stride의 저장량은 실제 child scope 교집합에 비례한다. addition 순서·정밀도·pruning·backpointer를 유지한다. |
| Factor value-class hash | 대표 row의 hash를 매번 재계산하지 않는다. 추가 hash 배열의 여유 공간이 없으면 기존 비교 경로를 유지하며 raw-bit equality는 그대로다. |
| Direct/physical closure | 변경이 없는 direct wave의 전체 복사·index 준비를 생략한다. 한 호출의 고정 입력 준비를 공유하고 FULL single-partition proof는 실제 조회 때만 만든다. |
| VALUE_MAP와 realization 조회 | 동일 fact 목록의 owner/type별 reference와 분석 소유 fact의 realization lookup을 인덱싱한다. duplicate·순서·foreign owner 거부를 유지한다. |
| Native revision | exact fact snapshot을 공유한다. 동일 topology의 모든 row/dependency가 현재의 구조 handle과 맞을 때만 immutable topology를 공유하며, query-local handle은 기존 재인덱싱을 수행한다. |
| Logical boundary | 하나의 closure 호출에서 Session을 재사용한다. 최초 실행과 VALUE_MAP carrier 변화에 따른 topology 재구성은 유지한다. |
| 불변 identity 목록 | 검증된 private 목록의 hash·내용·정렬을 공유한다. public record와 List 동치/불변성, 담긴 owner 객체 identity를 유지한다. |
| Signature cache | 실제 analysis scope 종료 때 문자열 캐시와 사용 예산을 함께 해제한다. 동일 문자열을 가리키는 identity alias front cache는65,536항목으로 제한하고 정확한 structural lookup으로 이어진다. 이는 후보 제한이 아니다. |
| Binder와 native layout | action/realization 객체와 순서가 그대로인 경우에만 기존 emission/fact를 반환한다. canonical partition 재생성을 줄이고 layout 문자열은 별도 immutable key로 캐시한다. |
| Publication·진단 | receipt의 ordering descriptor/cursor와 이미 계산한 길이를 재사용한다. audit JSONL의 byte 계약을 유지하면서 stream으로 기록한다. DEBUG가 꺼졌을 때 Oracle 로그의 준비 비용을 생략한다. |

## 검증

중앙 Maven 97개 클래스 **821건:815 PASS/기존 ignore6/실패·오류0**, package BUILD SUCCESS다. 빌드 전후 source SHA는 같다. 이후 두 테스트의 검증을 보강하고 해당2클래스7건과 package를 재검증했다. main source는 중앙 검증과 동일하다. 최종 main/test source와 classes는 별도 동결했다.

독립 cold oracle, 무작위 ordering400그래프×4정렬, 기존 boundary union oracle, signed zero/NaN/hash collision, memory guard,2,048 singleton child 메시지, native revision의 positive/fallback handle, foreign equal owner/action, source withdrawal/restore, layout precision, 캐시 예산 종료·포화, 모든 collection mutation surface와 audit JSONL 동일 byte를 확인했다. 독립 source review에서 correctness blocker0이며 `git diff --check`도 통과했다.

새 cache lifecycle 회귀는 수정 전 retained4096≠0으로 실패했고 수정 후 통과했다. identity alias 포화 회귀도 수정 전 실패/수정 후 통과했다. 기존 `PlacementIdentityKnownEqualityContractTest`의 PCA fixture는 변경 전 동결 d26에서도 동일한 `PLACEMENT_FUNCTION_ROOT_UNPROVEN`으로 실패한다. 해당 기존 실패를 이번 변경의 성공으로 집계하지 않았다.

## 중간 측정과 채택 해석

| 실행 | common(s) | 전체 컴파일(s) |
|---|---:|---:|
|동시기 d26 baseline01|19.948|28.756|
|screen01|18.283|27.285|
|screen02|16.801|24.474|
|screen04|17.214|24.018|
|screen05|16.797|23.580|
|screen06|16.146|25.307|

이는 서로 다른 중간 구현의 단일 screen이다. 최솟값을 최종 성능이나 반복 개선율로 사용하지 않는다. 모두 모델·fingerprint·시간 제외1,224 checkpoints 동치와 audit/conversion0을 확인했다. profiler를 켠 실행은 진단으로만 사용한다. 최종 비교는 위 표의 동일 동결본3회와2개 ablation으로 별도 확정했다.

## 남은 병목과 회귀 위험

최종 수정본에서 common은13.968–17.794초, 평균16.331초로 전체 컴파일의 약70.84%다. common만으로도10초를 넘는다. 기존 구현은 이미 seed placement ID와 무관한 물리 witness별 support 공유 및 비순환 sibling 지원 공유를 수행한다. 같은 기능의 캐시를 다시 추가해 큰 개선을 기대할 근거는 없었다. 전체 CFG를 구조적 equals만으로 생략하면 현재 proof·음성 lookup·loop seed·mutable Hop·privacy 문맥을 놓칠 수 있어 적용하지 않았다. 메서드 전체의 재사용은 별도의 완전한 입력/변경 계약이 필요하다.

새 저장소는 분석/호출 범위와 기존 예산에 한정한다. 주요 위험은 invalidation 누락·foreign equal authority의 대체·정렬/tie 변화·cache 포화 후 hash 비용 증가다. identity 음성 회귀, cold differential, 실제 모델과 planner checkpoint 비교로 감지한다. 이번 변경으로10초를 달성하지 못했다. 완전한 dependency/authority 변경 집합에 기반한 고정점 전파 범위 축소가 남은 설계 과제이며, 아직 구현·검증한 성능으로 제시하지 않는다.

## 재현 자료

원자료: `/grid/3/cofee-lm-sweep-mchoi-20260914/logreg-common-round2-20261007`.

- `final-regression-command.json`, `final-regression-summary.json`, `final-build-status.json`: 중앙 검증.
- `final-postreview-command.json`, `final-postreview-status.json`: 테스트 보강 후 package 검증.
- `final/manifest.json`: 최종 Java source SHA.
- `final-benchmark-plan.json`, 각 run의 `manifest.json`, `result.json`, `*-command.json`, `*-validation.json`: 반복/ablation 원본.
- `evaluate.py`, `bench-final.py`, `prepare-final-experiments.py`: 동일 Docker 평가/동결 비교 도구.

소스를 빌드한 뒤 기존 Docker harness의 `--joint-boundary-e2e --case ml_logreg`에 `--classes`, `--main-sources`, `--test-classes`, `--test-sources`를 지정하면 같은 fixture를 검증할 수 있다. 정확한 전체 argv는 각 실행의 command JSON에 보존했다.

최종 통합 검증 JSON: [validation.json](experiments/logreg-total-compile-20261007/validation.json). 최상위 상태는 `TARGET_NOT_MET`, 정확성/조건 검증은 각각 `PASSED`로 구분한다.

수정한 production 파일은 `PlacementRelationClosure.java`, `NativePlacementContinuity.java`, `LogicalBoundaryRealizations.java`, `PlacementIdentity.java`, `PlacementAnalysis.java`, `PlannerCandidateSpaceAudit.java`, `ExactCategoricalSolver.java`, `ExactFactorValueClasses.java`, `FederatedPlannerLogger.java`, `OracleFacade.java`다. 각 파일은 기존 모듈 안에서 변경했으며 새 의존성이나 사용자용 플래너 옵션은 추가하지 않았다.

## 최신 main 통합 후 게시 검증

LogReg 변경을 `5f9ede00f1`로 커밋한 뒤 origin/main `9409f503d8`의 StepLM joint proof/relocation 변경을 병합했다. `PlacementRelationClosure`의 충돌은 upstream SCC 순서와 revision 갱신을 보존하면서 동일 authority의 fact 재사용을 적용해 해결했다.

통합본은 Java 102클래스 838건 중832 PASS/기존 ignore6/실패·오류0, Python35건 PASS 및 Maven package를 통과했다. 빌드 전후3,604개 Java source가 같고 Docker 실행에는 이 동결본을 사용했다. 독립 병합 검토에도 correctness blocker가 없었다.

실제 LogReg 학습의 모델16계수는 CP와 maxdiff2.22e-16, runtime audit/conversion 위반0이다. 이전 d26의1,224 checkpoints와는 upstream의 joint factor 변경 때문에 일치하지 않았다. 그 비교 실패는 원자료에 보존했다. 기존 main 게시 실행의 동결 main source1,645개가 Git9409와 전수 일치함을 확인한 뒤 비교했으며, 통합본의 시간 제외1,228 checkpoints와 analysis fingerprint는 최신 main 결과와 전부 동일했다.

이 통합 smoke1회에서 common15.885초, 전체 컴파일22.554984초, 학습3.638초였다. 앞의3회 반복 성능은 통합 전 동결본의 결과이며, 이 단일 관측과 섞어 개선율을 다시 계산하지 않는다. **전체10초 목표는 계속 미달이다.** [게시 검증 기록](experiments/logreg-total-compile-20261007/publication-validation.json)에 비교 기준·명령·source/결과 SHA를 보존했다.
