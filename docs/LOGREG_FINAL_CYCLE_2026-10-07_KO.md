# LogReg 마지막 최적화 사이클 — 추가 변경 미채택 (2026-10-07)

이번 사이클은 종료했다. 최신 비교 기준 `88d77d641c4f5f9177b82724bca3ea70ce4edb63`과 수정본을 실제 LogReg 학습으로 각각 3회 측정했으나 **전체 컴파일 개선을 확인하지 못했다**. 평균은 16.540→16.836초(+1.79%), 중앙값은 16.688→16.442초(-1.48%)다. 마지막 실행을 제외하거나 유리한 통계만 골라 채택하지 않는다. 이는 회귀의 인과 증명도 아니며, 공유 호스트의 실행 변동과 작은 효과를 구분할 근거가 부족하다.

사용자는 “이번 사이클에서 개선되었으면 커밋하고 푸쉬하고 마무리”하도록 요청했다. 따라서 **이번 추가 Java 변경은 철회했고 커밋·푸시하지 않았다**. 이전에 게시된 개선과 다른 작업의 main 커밋은 유지한다. 10초 목표는 미달이며 추가 최적화 실험을 진행하지 않는다. 2026-10-08 후속 요청에 따라 이 보고서와 검증 JSON을 origin/main에 게시한다. 미채택 성능 변경은 재적용하지 않는다.

## 실제 학습 검증

`multiLogReg`, X 192×8, 3 classes, maxi10/maxii5, icpt0, tol1e-7, reg1e-4, PRIVATE_AGGREGATE X/local labels, ROW worker 3개를 사용했다. 모든 실행은 `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e --case ml_logreg` 경로다. Docker 4 CPU/8GiB, main JVM `-Xmx3g -XX:ActiveProcessorCount=4`, 동일 이미지·입력·fixture/config·dependency다. 입력, iteration, 합법 후보, privacy/TR-TW, runtime 제약을 바꾸지 않았다. JFR/추가 계측을 켜지 않았고 빌드·테스트와 성능 실행을 겹치지 않았다.

전체 컴파일은 common + planner + lowering/기타의 합이다. 학습 실행 시간은 별도다.

| 회차 | main common(s) | 수정 common(s) | main 전체(s) | 수정 전체(s) |
|---|---:|---:|---:|---:|
| 1 | 11.976150 | 11.915067 | 16.688401 | 16.441851 |
| 2 | 11.353847 | 11.276768 | 15.883794 | 15.713272 |
| 3 | 11.941125 | 12.821554 | 17.047228 | 18.354046 |
| 평균 | 11.757041 | 12.004463 | 16.539808 | 16.836390 |
| 중앙값 | 11.941125 | 11.915067 | 16.688401 | 16.441851 |

실행 순서는 main1→수정1→수정2→main2→main3→수정3이다. 각 실행은 fresh JVM이며 각 variant의 3회 source/class inventory가 동일함을 확인했다. main의 Java source 3,618개는 Git88d의 blob과 전수 일치한다. 최종 수정본은 소스가 변하지 않은 Maven package에서 별도로 동결했다.

6회 모두 실제 모델 16계수의 CP/FED 최대 절대 오차2.22e-16, 시간 제외1,228 planner checkpoints·analysis fingerprint 동일, runtime audit/conversion 위반0이다. 후보/계획을 줄여 속도를 얻는 변경은 없다. 데이터 크기나 다른 workload의 성능으로 일반화하지 않는다.

## 검증 결과와 철회한 구현

최종 통합 후보는 **Java140클래스1,089건 중1,083 PASS/기존ignore6/실패·오류0**, Python harness35/35 PASS 및 Maven package를 통과했다. 독립 병합 검토도 CLEAR였다. 이 결과는 correctness 검증이며 성능 채택 근거와 구분한다. 저장소 전체 테스트를 실행했다는 의미는 아니다.

이번 후보는 canonical realization의 검증된 provenance 재사용, 짧은 literal 병합, descriptor cache의 삽입 ledger/rollback, emission tuple 중복 판정, compiled input edge 및 VALUE_MAP owner delta 재사용, 동일 authority의 native resolver/topology 재사용, logical boundary session 재사용, FType seed 목록 공유, dense DP boundary의 scalar 정밀 합산을 포함했다. 모든 기존 legality 판정과 invalidation을 유지하는 회귀를 추가했다.

변경 대상은 `PlacementAnalysis.java`, `PlacementRelationClosure.java`, `NativePlacementContinuity.java`, `LogicalBoundaryRealizations.java`, `ExactCategoricalSolver.java`와 관련 테스트였다. 최종 비교에서 이 조합의 이득이 재현되지 않아 **전부 이번 게시 대상에서 제외**했다. 새로운 의존성이나 runtime fallback은 없다.

앞선 실험의 FIFO signature front, fact projection cache, subset merge 및 provisional resolver도 성능 이득이 입증되지 않아 이미 제외했다. 각각의 소스·테스트·명령·실패/성공 로그는 보존한다. upstream에서 별도로 반영한 private signature front 교체 등은 Git88d의 일부이므로 그대로 유지한다.

## 비교 기준 변경과 남은 한계

작업 시작 시 게시본260ec의 단일 전체 컴파일 관측은22.555초였으나, 작업 중 main에 StepLM의 공통 분석·boundary 압축·조회 개선이 합쳐졌다. f298의 원본2회18.575/18.278초와 수정본18초대 측정도 과거 기준이다. 이 값들과 최종15–18초대 결과를 섞어 이번 변경의 가속률을 주장하지 않는다.

최종 기준 main의 common 평균은11.757초, planner3.759초, lowering/기타1.024초다. **전체10초 목표는 달성하지 못했다.** 캐시 invalidation·authority·정렬·정밀 산술 회귀는 검사했으나, 성능 차이가 작은 상황에서 추가 복잡도를 채택할 근거가 부족하다. 사용자 종료 요청에 따라 다음 최적화 사이클을 열지 않는다.

## 재현과 보존 자료

원자료 경로: `/grid/3/cofee-lm-sweep-mchoi-20260914/logreg-common-round3-20261007/integration-88d`.

- [검증 JSON](experiments/logreg-final-cycle-20261007/validation.json): 모든 반복 측정, 모델/계획 동치, 빌드·회귀 결과, source identity 및 inventory digest.
- [명령 JSON](experiments/logreg-final-cycle-20261007/commands.json): Maven/Python 및 Docker 전체 argv.
- `publication/final/`: 검증한 후보의 전체 Java source/classes/test source/classes 동결본.
- `baseline`: Git88d Java source와 일치하는 upstream 검증 동결본.
- `rejected-final/candidate.patch`, `rejected-final/files/`, `rejected-final/manifest.json`: 철회 전 최종 패치와 파일 SHA-256. Git88d에 재적용해 검토할 수 있다.
- `final-benchmark-summary.json`, 각 run의 `manifest.json`·`result.json`·`*-validation.json`: 개별 실행 원자료.
- `publication/final-regression-summary.json`, `publication/final-regression.log`, `integration-review.json`: 후보 회귀/검토 증거.

로컬 Java source3,618개의 Git88d 일치를 확인했고 빌드 산출물도 복구한 뒤 package를 재생성했다(BUILD SUCCESS, 테스트 재실행 생략). 보존 패치의 git apply --check도 통과했다. 보고서와 원자료는 다음 작업에서 측정 실패를 반복하거나 과거 최솟값을 성능으로 오인하지 않도록 보존한다.

## 2026-10-08 게시 및 정리

사용자가 빠른 버전을 main에 유지하고 커밋·푸시한 뒤 workspace 정리를 요청했다. 반복 평균이 더 빠른 비교 기준88d는 이미 main의 조상이며, 최신 main의 후속 GLM·StepLM 변경을 그대로 유지했다. 이번 게시는 마지막 LogReg 실험 결과와 미채택 결정을 기록하는 문서 변경이다. 최신 main 전체의 성능을88d의16.54초 측정값으로 주장하지 않는다.

정리 범위는 이 작업의 `target`과 `logreg-common-round3-20261007`의 중복 파일이다. target은 외부 grid 볼륨에 압축 보관하고 검증 후 제거하며, 실험 자료는 동일 내용 파일을 hard link로 통합해 경로와 bytes를 보존한다. 다른 작업의 worktree·데이터·프로세스는 유지한다. 정리 manifest와 archive는 `/grid/3/cofee-lm-sweep-mchoi-20260914/logreg-workspace-cleanup-20261008`에 보존한다.
