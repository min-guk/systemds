# Alias reachability 실제 workload 검증

`fbfd4d790f`의 동일 source/classes/dependencies에서 `JointValueMapRelations`만 수정 전후로 비교했다. 두 버전 모두 Java17의 같은 명령으로 해당 파일을 재컴파일했다. 새 main 변경은 포함하지 않았다.

실험은 `run_LAN_docker.sh --joint-boundary-e2e`로만 수행했다. 소형 실제 `multiLogReg`는192×8 PRIVATE_AGGREGATE X, public local labels,3 ROW workers, numclasses3, maxi10/maxii5다. 동일 pinned Docker image,4CPU8GiB, 새 worker/coordinator JVM에서 baseline→candidate→candidate→baseline 순서로 실행했다. 공유 호스트의 다른 작업과 별도 W1 compile이 일부 겹쳤으므로 exclusive CPU 실험이 아니다.

## 실행 시간

| 실행 | 공통 분석(s) | 모델 구성·비용표(s) | 전체 planner(s) | 컴파일(s) | 학습(s) |
|---|---:|---:|---:|---:|---:|
| baseline-r1 | 47.579 | 4.770 | 11.337 | 60.362 | 3.894 |
| candidate-r1 | 49.121 | 5.688 | 13.102 | 63.602 | 3.927 |
| candidate-r2 | 49.186 | 6.408 | 14.974 | 65.542 | 3.825 |
| baseline-r2 | 49.655 | 6.101 | 13.801 | 64.900 | 3.750 |
| baseline 평균 | 48.617 | 5.435 | 12.569 | 62.631 | 3.822 |
| candidate 평균 | 49.153 | 6.048 | 14.038 | 64.572 | 3.876 |

소형의 평균 컴파일은62.631→64.572초(+3.10%)로, latency 개선은 확인하지 못했다. 각 군2회이며 공유 호스트 변동이 있어 이 차이만으로 안정적인 성능 회귀도 확정하지 않는다.

## 실제 탐색 작업량

별도 계측 복사본만 query/search/expansion counter를 추가했다. 이 두 실행의 시간은 위 평균에 포함하지 않았다.

| 지표 | Baseline | Candidate |
|---|---:|---:|
| Alias fallback 질의 | 1,506 | 1,506 |
| 실제 root 탐색 | 1,506 | 14 |
| 완료 질의 cache hit | 0 | 1,492 |
| Realization adjacency 조회 | 6,822 | 54 |

실제 탐색은99.07%, adjacency 조회는99.21% 감소했다. 앞선 합성 diamond의3,071→22회 감소와 별개인 실제 builtin 학습 측정이다. 이 작은 workload에서는 alias 작업 감소가 전체 컴파일 시간 감소로 나타나지 않았다.

## 정확성 확인과 한계

시간 비교4회와 계측2회 모두 학습 및 runtime audit를 통과했다. CP/FED 전체16계수 최대 절대 오차는2.22e-16이고, 여섯 FED 실행의 계수는 서로 동일하다. Runtime mismatch/missing/conversion 위반은0이다. 다음 항목도 모든 실행에서 일치했다.

- 동일 입력·DML·config·runner hash 및 analysis fingerprint.
- PID만 제외한 candidate-space audit 전체.
- 524개 lowering 노드의 실행·저장 선택.
- 시간 필드만 제외한 DP 체크포인트1,224개 전체.
- 최종 upper122.26631334184357, lower120.54269578813249, gap1.4298813731%, assignments17,336,646, merges4,566. 이 결과는 전역 최적해 증명은 아니다.

Cost fingerprint와 plan hash는 같은 variant의 반복끼리도 다르다. plan hash가 cost fingerprint를 담은 objective certificate를 포함하므로 두 hash 차이는 독립적인 선택 차이 증거가 아니다. 최초 cost-hash 입력 차이는 현재 로그에서 분리하지 못했다. 전체 cost surface의 byte 동일성까지 입증했다고 주장하지 않는다. 이번 alias 변경 외의 해시 구현은 수정하지 않았다.

## 대형 W1

동일 n=50,000, d=2,100, PRIVATE_AGGREGATE X/Y, worker 1, Docker 4CPU·16GiB, JVM 10GiB heap·비용 설정으로 후보 compile/lowering을 1회 실행했다. **TIME_LIMIT_BEFORE_ALIAS**로 종료했다. 1,200초 제한의 정리까지 1,204.876초가 걸렸고, `analysis_end`와 `planner_begin`은 없었다. 마지막 1,180초 stack은 `CandidateEmissionFact.mergeCanonicalClauseRuns → mergeRealizations → bindDirectNativeCandidateRealizationsMeasured`의 공통 분석이다.

예외·OOM·factor overflow는 관측하지 않았고, 출력/선택 플랜/비용 receipt도 생성되지 않았다. 따라서 큰 W1의 alias 수정 효과와 전체 성공 여부는 이번 실행으로 검증하지 못했다. 단순 timeout을 불법/불가능 판정으로 해석하지 않는다. 정확히 소유한 container와 runner/JVM을 정리했다. 시작 전 artifactRoot 경로 오류는 별도 infrastructure-invalid receipt로 보존했고 유효 후보 실행과 구분했다. [W1 최종 receipt](experiments/alias-reachability-20261007/w1-verdict.json)의 SHA256은 `249d2c6a5101f53e4cb067809928edf5465d3ef566d7a09a0c1e6719c43aceef`다.

## 근거

[검증 JSON과 정확한 argv](experiments/alias-reachability-20261007/workload-validation.json), [집중 회귀22건](experiments/alias-reachability-20261007/validation.json). Raw artifact root는 `/grid/3/cofee-lm-sweep-mchoi-20260914/alias-reachability-20261007`, runner/소스·class 비교군은 `/home/mchoi/alias-reachability-validation-20261007`에 보존했다.

## 게시 전 main 통합 검증

게시 전 `origin/main`의 `2faff2a2a53ed1f608f2e1f6b2a13ca427d71f52`에 rebase했다. Alias production 파일의 upstream 변경은 없었고, 세션 문서의 append 충돌은 양쪽 기록을 보존해 해결했다. Maven `test-compile` 성공 후 새 `target/classes`와 `target/test-classes`로 같은 5개 클래스의 22건을 다시 실행해 12.056초에 모두 통과했다. [통합 검증 기록](experiments/alias-reachability-20261007/publication-validation.json)에 명령과 로그 hash를 남겼다.

위 Docker workload 수치는 계속 `fbfd4d790f` 기준 비교다. 최신 main의 workload 재측정 결과로 해석하지 않는다. 실험 snapshot은 측정 당시의 독립 Docker `frozen-inputs` 복사본에서 복원하고 파일별 SHA를 확인해 새 Maven 산출물과 분리했다.
