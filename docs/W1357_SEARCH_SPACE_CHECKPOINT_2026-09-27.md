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
- 원격 `main` 병합 이후의 최종 clean 회귀/package 결과는 게시 검증 절에 추가한다.

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
