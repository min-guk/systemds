# AggLocal local-vector continuation 복구 및 AggLocal 전용 재실험

## 요청과 범위
- 사용자의 2026-10-05 승인: 제안한 AggLocal 규칙을 구현하고 새 버전으로 AggLocal만 재실험한다.
- L2SVM부터 실제 선택/수치/통신을 확인한 뒤 기존 workload·worker·network 조건의 AggLocal 전용 실행을 준비한다. 다른 planner의 과거 결과를 새 버전 결과로 수입하지 않는다.
- 기존 실행과 frozen JAR/source/result는 변경하지 않는다. 공유 worker/netem 자원에 겹치는 실험을 실행하지 않는다. compile/runtime timeout은 사용하지 않는다.

## 고정할 선택 계약
1. analysis-owned aggregate-vector release에서 이어진 경로에서, 모든 데이터 입력과 출력이 scalar/provably-vector이고 exact CP/LOUT 입력 조합(필요한 명시적 local materialization 포함)이 지지되면 CP/LOUT을 FED보다 먼저 선택한다.
2. 그 외의 인증된 aggregate-vector 결과는 FED/LOUT을 우선한다. 단, 즉시 소비자가 PRESENT(FOUT) 입력만 허용하는 경계는 기존 FOUT을 유지한다. 이는 shared protected formal에서 gather 후 즉시 재업로드하는 회귀를 막는 기존 규칙이다. 보호된 비벡터 X와의 행렬곱은 FED 실행을 유지한다. LOUT은 CP 실행을 뜻하지 않는다.
3. 나머지는 기존 FedFirst 순서를 따른다. unknown shape를 vector로 가정하지 않는다. 비벡터 입력을 local 연쇄 유지 목적으로 수집하지 않는다.
4. 공개 sibling vector의 local view는 동일 값 버전/호환 scope에서 공유한다. raw PRIVATE_AGGREGATE sibling의 수집은 금지한다. native 경계의 새 sibling은 PUBLIC일 때만 CP 우선순위를 부여한다. PRIVATE_AGGREGATE_TO_PUBLIC인 별도 aggregate sibling은 합법적으로 수집 가능한 경우에도 기존 native 연속성을 우선한다(후보 금지가 아닌 정책 순서).
5. CFG 전달은 정확한 reaching definitions와 공통 transient/boundary 제약을 사용한다. 개별 경로의 합법성만으로 전체 선택의 합법성을 주장하지 않는다.
6. common candidate universe, privacy/runtime capability, TR/TW 계약을 변경하지 않는다. selector의 선호와 exact support propagation만 수정한다. 전역 backtracking, greedy 재시도, runtime fallback은 추가하지 않는다.

## 구현·검증 순서
1. 기존 local continuation / protected sibling / non-vector boundary / native FED/LOUT / loop 테스트를 새 selector 메타데이터와 맞추고 실제 배치 assertion을 보존한다. 수정 전 실패 증거를 남긴다.
2. PolicyGreedyPlacementSelector에서 현재 무시하는 분석의 local path facts를 exact candidate 우선순위에 연결한다. HeuristicPlacementAdapter는 공통 그래프를 유지하며 V5 정책과 실제 경로 정보를 보고한다.
3. /grid/3의 별도 build/evidence root에서 컴파일·JUnit 검증한다. 작업 tree의 target symlink 및 이미 실행 중인 JAR는 변경하지 않는다.
4. FedFirst 비변경, candidate universe parity, 보호 데이터 비수집, exact final witness, W1/3/5/7 L2SVM inner CP + X matrix multiplication FED를 확인한다.
5. 새 immutable JAR/source hash를 고정하고 run_LAN_docker.sh 기반 AggLocal 전용 실험을 실행한다. 수치·runtime audit·netem·cleanup 검증을 유지한다. 기존 원본 DS workload와 동일 parameters를 유지한다.

## 성공과 잔여 위험
- 성공: 실제 L2SVM local-vector inner-loop CP, 불필요한 Xd re-upload 없음, 보호 X는 FED, 수치 정확성, 새 엔진 AggLocal 결과/로그 확보.
- 성능 개선과 전체 workload 성공은 실측 전 주장하지 않는다. vector-only 정책은 byte-size/cost 최적성 보장이 아니다.
- greedy support propagation은 전역 해 존재 판정이 아니다. 충돌이나 최종 검증 실패를 runtime fallback/거짓 PASS로 덮지 않는다.
- 범위 밖 dirty R37/R44 수정은 보존하고 baseline/source 차이를 receipt에 남긴다.

## 사용자 변경 요청 — 실험 중단 및 origin/main 푸시
- 2026-10-05 사용자가 기존 실험을 우선 중단하고 origin/main에 푸시하도록 요청했다. 신규 AggLocal 실험 시작은 취소한다.
- 기존 run12의 PID/start ticks를 검증한 뒤 SIGINT로 정상 finally cleanup/lease release를 유도한다. 완료된 결과는 보존한다.
- 새 JAR는 R44 original-DS source를 별도 worktree로 복사하고 selector/adapter 두 production 파일만 수정해 빌드했다. 작업 tree의 CG 강제 변경이나 무관한 그래프 산출물을 섞지 않는다.
- 최종 대상 테스트 및 원격 main 상태를 확인한 뒤 검증된 변경을 커밋/푸시한다. 새 runtime 성능은 미측정으로 남긴다.

## 최종 검증 및 중단 결과
- 원본 DS 유지: R44 대비 production 차이는 selector/adapter 두 파일이며 builtin195개는 동일하다. R44 prerequisite(크기 상한/FULL profile/owned reuse/timeout 없는 runner)와 AggLocal 정책 변경을 별도 커밋으로 게시한다.
- 최종 Maven package 성공. 패키징된 JAR 기준 JUnit **139개**, 크기/shape9개, Python runner85개 통과. JAR SHA256: `47f050f3f0612ffb699bcbee4f5ebf8680e33007cdace16ca10387c04f20185d`.
- 추가 회귀: PUBLIC Y와의 nested vector aggregate는 CP/LOUT, protected-X-derived aggregate sibling은 FED/LOUT을 유지한다. 두 입력 모두 vector여도 privacy 경계가 다른 경우를 구분한다. 공통 후보군을 바꾸지 않는다.
- run12는 `2026-10-05T01:28:16Z` 종료(exit130). 해당 coordinator/worker container 정리와8개 host lease 해제가 확인됐다. 완료39개 PASS 결과 보존. 새 실험은 시작하지 않았다.
- 근거: `/grid/3/cofee-lm-sweep-mchoi-20260914/agglocal-local-vector-r51-20261005/evidence/`의 `final-v5-packaged-all-tests.log`, `final-size-shape-tests.log`, `final-python-runner-tests.log`, `source-delta-proof.json`, `run12-stop-request.json`.
- 최신 별도 요청인 cost estimator 수정은 이 검증 JAR/게시 범위에 포함하지 않는다. 해당 변경은 별도 검증한다.
