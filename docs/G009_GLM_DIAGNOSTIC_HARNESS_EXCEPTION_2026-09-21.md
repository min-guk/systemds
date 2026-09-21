# GLM 원인 진단 실행 경로 예외 근거

공식 `run_LAN_docker.sh --planning-only --workers 4 --dataset P2P2D --conf mkl-cost --alg glm`를 먼저 시도했으나 `workload is outside the frozen seven-workload set: glm`로 JVM 실행 전 거절되었다. 로그: `/home/mchoi/g009-glm-diagnosis-20260921/harness.log`.

사용자 요청은 성능 채택/정확성 인증이 아닌 현재 지연 원인 확인이다. frozen workload 계약을 수정하거나 다른 workload로 위장하지 않고, 이전과 동일한 GLM DML/JAR/config와 4-worker Docker에서 JFR을 한 번 수집한다. 이 경로는 공식 실험/성능 비교/완료 검증을 대체하지 않는다. host run_LAN.sh는 사용하지 않는다. runtime fallback 및 후보 삭제는 변경하지 않는다. JFR 표본은 호출 수나 완성된 E2E 시간이 아니며 별도 표기한다. 생성한 전용 컨테이너/네트워크만 정리한다.
