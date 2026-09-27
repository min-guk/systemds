# W1357 search-space 최종 작업 체크포인트 — 2026-09-27

## 1. 현재 상태

사용자 요청에 따라 추가 성능 최적화/실험을 중단하고 현재 변경을 정리하여
`origin/main`에 공유한다. **20초 목표는 미달이며 성능 목표 상태는 paused다.**
이 문서는 과거 세션 문서의 진행 중/재실험 예정 문구보다 최신 상태다.

목표 범위는 ML training 10종, P1_FULL/P2_PREP, SliceLine ADULT/COVTYPE의
14개 workload × LAN W1/W3/W5/W7 = 56개 조건이다.
시간은 **selector·runtime emission·실제 workload 실행 이전의 search-space 구성 시간**이며,
전체 학습 실행 시간 20초를 의미하지 않는다. 동일 최종 JAR의 56개 조건과
14개 planning-only consumer 검증은 아직 완료하지 않았다.

## 2. 완료한 변경

- prepared oracle dispatch, canonical identity/realization 표현 및 비교의 중복 계산 감소.
- exact dependency scheduling, closure/relocation/native proof 계산의 중복 감소.
- 동일 committed inventory 구간에서 물리 proof 인덱스를 재사용하되,
  실제 node/fact commit 직후 폐기하고 owner별 query state는 새로 시작.
- stable direct wave는 parent **identity**와 전체 fact equality를 확인한 경우에만 생략.
- 기존 SliceLine CTABLE aggregate-release와 explicit LOUT 런타임 경로를 연결하는
  누락된 ordinary CTABLE forced-local capability 보완. ctableexpand는 제외.
- search-space-only 준비 API/계측 및 평가 probe의 경계·SHA256 provenance receipt 추가.

원본 privacy, exact source/action/witness authority, TRead/TWrite 계약을 유지한다.
임의 후보 cap, 새 runtime fallback, 입력/iteration 축소로 성능을 맞추지 않았다.

## 3. 마지막 완료 OFF 측정

아래는 서로 다른 frozen revision의 단일 LAN W1 측정이다.
현재 게시 소스의 공통 성능 결과나 전체 조건 통과로 합산하지 않는다.

| Workload | Frozen revision | Search-space 시간 | 20초 기준 |
| --- | --- | ---: | --- |
| SliceLine ADULT | r5 | 8.280152433초 | 해당 측정 충족 |
| SliceLine COVTYPE | r5 | 8.898616912초 | 해당 측정 충족 |
| logreg | r7 | 28.062707632초 | 미달 |
| GLM | r7 | 32.659365073초 | 미달 |
| P1_FULL | r7 | 28.773104992초 | 미달 |

r6 logreg는 27.623442615초였다. 최신 r7이 더 빠르다고 주장하지 않는다.
r7 frozen JAR SHA256: `8185213ee04b3d6fb04759e8bd10ccdcaa3364b4ddde28c428d657c56d216aad`.
그 뒤의 r8 변경은 **정확성 검증만 완료했으며 성능은 미측정**이다.

## 4. r8 정확성 증거와 게시 검증

- identity-aware stable-wave: 기존 구현에서 intended RED 1건, 수정 후 50건 PASS.
- Native/Worker query lifecycle: 80건, 실패/오류 0, 기존 skip 1.
- 실제 physical loop 및 inventory: 최종 11건 PASS.
- post-commit invalidation 제거 mutation: intended 1건 실패 후 원복/재검증 PASS.
- 독립 correctness review CLEAR. 이는 성능 acceptance가 아니다.
- 원격 `main` 병합 이후의 clean 회귀/package 결과와 미해결 3건은 §6에 기록한다.

상세 변경/실패 이력: [세션 이슈](SESSION_ISSUES_2026-09-27.md).
로컬 원본 증거: `/home/mchoi/w1357-diagnostics/all14-searchspace20-20260927/`.
로컬 실험, `.omx` state, 생성 manifest는 소스 커밋에 포함하지 않는다.

## 5. 남은 이슈와 위험

1. logreg/GLM/P1이 마지막 완료 측정에서 20초를 초과한다.
2. 현재 합성 소스의 성능 및 W3/W5/W7 전체 범위는 입증되지 않았다.
3. inventory 무효화 누락, identity/equality 혼동, query memo 누출이 주요 회귀 위험이다.
   실제 commit counting, mutation, cold parity 및 authority 테스트로 감지한다.
4. 전체 저장소 테스트/전역 style 통과와 실제 전체 학습 실행 성공을 주장하지 않는다.
   최종 게시 검증은 명시한 대상 회귀와 package/probe 검증 범위로 한정한다.

## 6. 원격 main 통합 후 최종 검증 결과

**전부 통과한 release가 아니라, 미해결 검증 격차를 포함한 작업 체크포인트다.**

- clean Maven 대상: 48개 클래스, **468건 중 통과 459 / 실패 2 / 오류 1 / 기존 skip 6**.
  `mvn -q clean -Dtest-forkCount=2 -Dtest=<48-class selector> test`의 종료 코드는 1이다.
  실패를 skip 처리하거나 전체 green으로 분류하지 않았다.
- 다음 세 건은 남아 있다.
  1. 기존 OracleFacade FULL matrix의 FED/CP 기대 불일치.
  2. 기존 비정본 alsCG PRIVATE_AGGREGATE placement 오류.
  3. B-21 P/E cardinality: baseline raw192/physical36 대비 현재 raw160/physical30.
     P/E 상호 집합/중복도는 일치하지만 **기존 계획 공간 보존을 입증하지 못했다**.
     projector/physical identity 코드는 origin과 동일하다. 원래 테스트 기대값192/36을
     유지했으며 정당한 모델 변화인지 합법 계획 유실인지 미해결이다.
- recursive direct-publication 테스트의 전수 assignment 비교는 bounded-shadow API 용도를
  벗어나 OOM이 발생했다. 전체 graph definition signature와 exact candidate facts 비교로
  테스트만 조정했고 해당 회귀는 통과한다. heap·production·후보 수 제한 변경은 없다.
- package: `mvn -q -Dmaven.test.skip=true package` 성공. 이는 위 테스트 실패를 무효화하지 않는다.
  최종 JAR SHA256: `9ea86df3b0ff04f29d9a6179f557544187aa4c183d035dcbb4481732b98f2834`.
  build 전후 전체 source hash 동일, 주요 JAR class entry와 target/classes 일치 확인.
- upstream Python 변경 38모듈: **362건, 실패/오류0, 기존 skip1**.
  절대 producer 경로에 묶인 library-resolution 1모듈은 현재 worktree에서 fail-closed이고,
  정확한 원래 origin/main checkout에서 별도 **5/5** 통과했다. receipt를 재발급하지 않았다.
- evaluation Python 14모듈: **122/122** 통과. 최종 엔진 JAR에 연결한 기존 probe 회귀 **8/8** 통과.
- 새 probe의 tiny compile-only smoke: metrics OFF/ON 모두 selector·runtime emission·workload count0,
  script/config/JAR SHA256 및 fingerprint parity 확인. 잘못된 metrics flag는 fail-closed 확인.
  이는 성능 측정이나 실제 workload 완료 검증이 아니다.
- 신규 성능 측정, 전체 학습 실행, 동일 JAR56+14 수용시험은 수행하지 않았다.

상세 로그/manifest/실패 원본은 로컬 증거 디렉터리의 `wrapup-final-*`,
`wrapup-b21-physical-cardinality-diagnosis.md`,
`wrapup-library-binding-verification.md`에 보존한다.
