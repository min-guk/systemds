# 함수 입력 바인딩 배치 보존 변경 및 비교 계획 (2026-10-06)

사용자가 함수 인자 바인딩을 TW/TR과 같은 배치 보존 연결로 수정하고 실제 계획/후보 변화 측정을 명시적으로 요청했다. 기존 전역 원칙의 후보 축소 금지에 대한 이 변경의 근거는 runtime 불가능 판정이 아니라 사용자 지정 계획 표현 변경이다. 함수 입력/출력 바인딩은 CP/LOUT 또는 FED/FOUT를 유지하고, 실제 이동은 생산자 emission 또는 일반 소비자 materialization으로 처리한다. 개인정보·worker 배치·재컴파일 제한은 완화하지 않는다.

검증 전에는 동등한 계획 공간 또는 성능을 주장하지 않는다. 변경 전 현재 dirty tree를 /home/mchoi/function-boundary-ablation-20261006/baseline에, 수정본을 modified에 보존했다. 기존 실험 JAR/target은 수정하지 않는다.

1. 함수 입력 actual→binding을 SAME_VALUE_PLACEMENT로 통일하고 formal TR의 후보 closure를 배치 보존으로 맞춘다. 생산자 CP/FOUT→binding FED/FOUT 및 FED/LOUT→binding CP/LOUT는 값의 배치가 일치하므로 허용한다.
2. 실제 그래프의 경계 규칙과 모든 가능한 source/target 값 배치를 검증하는 회귀 테스트를 작성한다.
3. 동일 소스·입력·비용 설정으로 baseline/modified compile-only planning을 비교한다. STEP-LM, 일반 LM/기타 함수 workload, 함수 없는 학습 루프 및 작은 완전탐색 fixture를 사용한다.
4. 노드/후보 수는 전역 실행 가능 assignment 수와 구분한다. 작은 fixture 외에는 전체 계획 수를 주장하지 않는다. 변화한 다운로드 위치·회수·cost 및 compile 실패를 그대로 기록한다.
5. targeted 테스트와 실제 script planning이 끝난 뒤 변경만 원래 트리에 반영한다. Docker runtime을 새로 실행하지 않으면 수치 결과/성능 검증으로 보고하지 않는다.

잔여 위험: 실제 입력 경계 GET 후보가 제거되므로 더 안쪽 소비자 이동으로 대체되지 않는 계획이 있을 수 있다. shared function body, alias lifetime, multiple callers 및 재컴파일 source-map의 범위가 후보/비용에 영향을 줄 수 있다. 실험 결과를 근거로 보고하며 이를 조용히 fallback으로 우회하지 않는다.

## 완료 결과

소스 변경·테스트56개·A/B 플래닝16회 완료. STEP-LM W1 물리 후보 합계3887→3734, W3 1043→868; L2SVM9196→8856 및 선택 추정비용+1.7092%. LM/PCA/함수 없는 루프는 선택 상태·목적값 동일. 작은 전체열거 fixture의 합법 물리 assignment32→12, 선택비용 동일. 일반 계산 결과 전달 fixture도 후보87→84 및 선택 추정비용+48.7448%로 변화했다. 따라서 최초 입력 읽기에만 국한된 변화가 아니며, 완전히 동등한 plan space라고 주장할 수 없다.

상세 결과: [/home/mchoi/function-boundary-ablation-20261006/REPORT.md](/home/mchoi/function-boundary-ablation-20261006/REPORT.md). 비교 JSON: [/home/mchoi/function-boundary-ablation-20261006/comparison.json](/home/mchoi/function-boundary-ablation-20261006/comparison.json). 재현 코드·baseline/modified 소스·명령·metadata·전체 로그·해시를 같은 디렉터리에 보존했다.

한계: 새 runtime 실행 및 성능 측정은 하지 않았다. DP-local 선택 objective의 변화이며 전역 최적값 비교가 아니다. 광범위 worker 의존 테스트는 privacy metadata 부재/timeout으로 완료하지 못했으며 focused56개만 통과로 보고한다. 반복 동일-source 호출 조회 ambiguity는 baseline에서도 재현되어 범위 밖으로 유지했다.
