# C / AnytimeTarget 개선 및 재검증 계획

작성일: 2026-09-08. 구현·파일럿 진행 중이며 최종 성능 결과가 아니다.

기존 seven-way 실험은 사용자 요청으로 2,072개 committed trial에서 중단했다.
중단된 trial과 원본 로그는 보존하며, 예정됐던 84개 추가 실험은 실행하지 않는다.
이후 비교 대상은 Global, C (`Algorithm3Reuse`), `AnytimeTarget`이다.

## 범위와 성공 기준

- so007의 로컬 JVM에서 planning-only로 실행한다. Docker의 네 네트워크 설정에서
  bandwidth와 RTT 값만 cost model에 전달한다.
- 기존 10개 ML, P1/P2, 실제 SliceLine 네 데이터셋을 유지한다: 총 16개 입력.
- 상대 인증 목표 5%, 3%, 1%를 각각 독립 실행한다. 목표가 정책과 종료에 영향을
  주므로 1% 실행의 trace만 잘라 세 실험으로 간주하지 않는다.
- 원래 legality/privacy, finite encoded objective, canonical cost 검증을 유지한다.
  P2의 기존 privacy-safe placement 실패를 정책 완화로 없애지 않는다.
- 모든 checkpoint에서 Global oracle을 포함하고, L은 비감소, U는 비증가여야 한다.
- 인증 성공률, 전체 planner 및 JVM time-to-threshold, 시간 한도 미달, phase 시간,
  작업 수와 JVM peak RSS를 함께 보고한다. 성공한 실행만의 시간 평균을 주 결론으로
  사용하지 않는다.

## 이전 결과가 지목한 문제

1. C는 조건부 MBE로 L을 높이면서도 분기 조건을 강제하는 Regional 개선에서 좋은
   incumbent를 찾지 못했다. LM에서 더 높은 L과 더 나쁜 U가 함께 관측됐다.
2. 사전 작업량 검사는 raw model을 분석했지만 실제 exact solver는 arc consistency와
   동등한 original state의 quotient를 사용했다. GNMF는 raw 951,321 대 reduced
   76,820 assignments였다. StepLM reduced 667,963도 기존 100,000 한도를 넘는다.
3. Regional seed는 기존 interaction block을 한 번만 처리했다. 이후 연결된 block의
   변화로 이전 block을 다시 개선할 여지가 생길 수 있다.
4. AnytimeTarget은 한 width의 낮은 개선량으로 이후 width를 영구 보류했다.
   한 단계 plateau가 모든 더 큰 width의 무효성을 증명하지는 않는다.

이 수치는 기존 완료 결과를 이용한 진단이다. 새 구현의 실험 결과로 제시하지 않는다.

## 첫 구현과 분리 검증

| 변경 | 구현 범위 | 제한 및 인증 계약 |
| --- | --- | --- |
| Reduced prepared admission | 실제 exact reduction과 동일한 model을 준비·분석하고 바로 다음 solve에서 재사용 | 원본 factor의 hard materialization cap 유지. frontier 전체 table cache 대신 준비 상태 한 개만 보관 |
| C incumbent 개선 | 첫 분기 전 및 일정 횟수 조건부 시도에서 U 개선이 없을 때, 분기 조건 없는 incumbent region solve | 총 2회 이하. 누적 region과 동일 exact work gate 사용. U만 갱신하며 분기 공간을 제거하지 않음 |
| Bounded Regional revisit | 기존 seed pass 후 incident assignment가 바뀐 active block 재방문 | 기본 2 pass, 0으로 ablation. strict full-objective 개선과 hard feasibility 검증. cap에서 fixed point를 주장하지 않음 |
| Target width 재개 | region coverage가 끝난 후 보류했던 남은 width를 재시도 | maximumWidth와 시간·메모리 한도를 유지. 완료한 global lower bound의 max만 게시 |

시작 pilot에서는 exact/region work gate를 기존 100,000으로 유지해 reduction 효과와
gate 인상을 섞지 않는다. StepLM 등의 제한이 계속 관측되면, reduced assignments와
실측 phase 시간에 근거해 별도 gate 실험을 수행하고 최종 설정을 동결한다. Admission은
hard deadline이 아니다. 실행 중인 준비·exact 계산은 soft budget을 넘을 수 있다.

## 단계

1. 원래 버전의 대표 7개 입력 LM, StepLM, GNMF, P1, LogReg, KMeans, L2SVM을
   LAN/WAN-mid에서 1% 목표로 새로 측정한다. Global 포함 42개 diagnostic trial이다.
2. 동일 입력에서 새 admission/Target 정책을 먼저 측정하고, seed revisit 및 C incumbent
   rescue를 끄고 켜는 ablation으로 비용과 효과를 구분한다. 이는 설정 선택용 pilot이다.
3. 인증 오류·model mismatch는 원인을 수정하고 새 artifact로 재검증한다. 느려진 원인은
   seed/preparation/MBE/Regional/exact phase와 attempted/skipped work로 판단한다.
4. 선택한 코드·JAR·입력·harness·protocol의 hash를 동결한 후 전체 범위를 측정한다.
   계획은 16 inputs × 4 profiles × 3 methods × 3 thresholds × 3 paired repetitions
   = 1,728 trial이다. 세 방법의 순서를 순환해 각 cell에서 세 위치를 모두 사용한다.
5. raw receipts와 config, runtime asset hash, 모델·초기 assignment/LB 일치, oracle
   enclosure, 목표 성공과 미달의 반환 상태를 검증하고 최종 한국어 보고서를 작성한다.

공통 초기 계획의 비용뿐 아니라 assignment SHA-256도 기록한다. Global은 독립 exact
oracle이며 C/Target의 온라인 탐색이나 종료에 oracle 값을 전달하지 않는다.

## 해석 경계

기존 2,072개 결과는 비균형 중단 prefix다. 기존 균형 4회 1,792개 보고서는 보존한다.
새 pilot 한 번의 시간 차이를 통계적 speedup으로 주장하지 않는다. 최종 비교에서도
root/full-region exact의 사용량을 공개해 단순 exact 전환 효과와 탐색 효과를 구분한다.
보장 대상은 modeled optimality이며 실제 workload 실행시간이나 예측 오차가 아니다.

근거: `/home/mchoi/so007-regional-refinement-evidence-20260908/reviews/initial-improvement-review.md`.
