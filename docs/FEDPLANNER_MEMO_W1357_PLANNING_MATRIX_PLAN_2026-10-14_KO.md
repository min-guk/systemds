# 게시된 Memo C26의 W1/3/5/7 전체 workload planning 평가 계획

## 목적과 게시 기준

사용자 요청에 따라 추가3초최적화를종료하고 C26 `802cd1381f38796f61e71956d0730728d44837ae`를GitHuborigin/main에 fast-forward 게시했다. 후속목적은실제MLtraining10종과P1/P2,SliceLine2종에서각worker수별planning가능성및소요시간을확인하는것이다. 정확성/범위검사를완화하거나runtimefallback을추가하지않는다.

## 56조건

- ML: PCA, ALS, KMeans, LM, LogReg, L2SVM, StepLM, GLM, GNMF, GMM.
- Pipeline: P1_FULL, P2_PREP.
- SliceLine: ADULT, COVTYPE.
- 각W1/W3/W5/W7,LAN프로필,각조건단1회. 평균이나안정적인speedup을주장하지않는다.
- 봉인된 W1357_STAGE의데이터크기/rowpartition/ranges/metadata/privacy/템플릿/seed를유지한다. ML X50000×128 PRIVATE_AGGREGATE,Y PUBLIC;P1 X100000×68 PA;P2 X100000×1001 PA;ADULT32561×13 PA;COVTYPE581012×54 PA.
- 앞선192×8 tinyE2E와다른입력이므로C26의3.028/5.072초와직접비교하지않는다.

## 실행과 판정

기존 `run_LAN_docker.sh`의명시적Memo planning-only lane에서실행한다. 기존sealedrenderer는Memo enum을받지않으므로DP-global렌더결과는입력생성용receipt로만사용하고실행config에는COMPILE_MEMO를명시한다. 원renderer/validator자체를완화하지않는다. 현재 renderer와검증된봉인stage `/home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z`를사용한다(manifestSHA e3544c65da20e46b3318c02a8d984aff108bf32435bf7a18b66e65d15bfd6a1e). 원 IP endpoint를변경하지않는다. 동일workerpath의봉인된local sidecar를읽는production메타데이터경로를사용하므로실제workerJVM/RPC/학습데이터읽기는필요없다. 데이터파일이없는metadata-only compile상태를명시하고training성공으로주장하지않는다.

`sysds.benchmark.compile_only=true`와`-stats`로production DMLScript의기존분기를사용한다. RuntimeProgram구성까지수행하지만training/runtime명령은실행하지않는다. worker구성/ranges는실제Memo입력에서검증한다.

- 성공: 실제MemoOPTIMAL+전체compile정상종료+worker수/compile-only일치.
- planning성공후compile실패: OPTIMAL이나후속lowering/compile실패,별도표시.
- planning오류: INFEASIBLE/UNRESOLVED/RESOURCE_EXHAUSTED또는실제planner예외.
- timeout: coordinator300초제한내완료하지못함;불가능하다고증명한것이아니다.
- 환경/입력오류: sealed입력검증/Docker/workerstartup/환경문제로planning에진입하지못함;planner실패와분리.

MemoFacts/Rules/Model/Optimization/Projection의완전한합과각phase시간을수집한다. 전체initialplanning/compile및harnesswall은별도필드다. 실패한partial로그를전체Memo시간으로대체하거나외부wall에서추론하지않는다.

각조건마다freshcontainer/coordinator를사용한다. 성공/실패/timeout은terminal결과이며resume시기존attempt를건너뛰고숨은재시도를하지않는다. 작업소유container만정리하고기존타작업공간/봉인입력/불관련프로세스를수정하지않는다. 메모리/CPU/heap은모든worker수에서동일한계약을사용해결과receipt에기록한다.

## 검증/산출물

하네스unit/syntax/render-only사전검증과독립review뒤실행한다. productionJava는C26동결본그대로이며새planner최적화는하지않는다. 조건별command/DML/config/renderreceipt/입력동등성receipt/log/returncode/예외분류및JSON/CSV집계,최종한국어비교표를남긴다. 완료뒤하네스와결과문서도추가커밋/푸시한다.

## 환경 계약

새행렬은게시된C26의pinnedimage4CPU/8GiB,coordinatorheap3GiB/ActiveProcessorCount4,nativeBLASnone/codegenfalse,추가SYSDS_FED_COSTenv없는productiondefault비용계약을사용한다. 과거전체campaign의동적MKL/LANcalibration성능비교로주장하지않는다. workload/ranges/metadata/privacy계약과engine환경계약을별도hash로기록한다.
