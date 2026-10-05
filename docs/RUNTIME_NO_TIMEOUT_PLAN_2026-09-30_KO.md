# 사용자 정정: compile/runtime 시간 제한 제거

## 명시적 목표

사용자는 compile과 runtime 모두 timeout이 없다고 정정하고, 제한을 없애서 실행하도록 지시했다.
기존 runner의 60초 GNU timeout과 controller 150초 subprocess watchdog은 잘못 적용된 정책이다.
두 deadline을 제거한다. 임의로 더 큰 유한 시간으로 바꾸지 않는다.

## 변경 순서와 보존 조건

1. 기존 실행을 execute_cell의 try 내부에서 안전하게 중단하고 finally strict cleanup,
   lease release, controller 종료를 확인한다. source/manifest/result는 덮어쓰지 않는다.
2. 회귀 테스트를 먼저 no-deadline 계약으로 바꾼다. compile/runtime 모두 remote 명령에
   `timeout` wrapper가 없어야 하며 local subprocess도 timeout=None이어야 한다.
   명시적 유한 compile/runtime timeout CLI 옵션은 거부하고 manifest/result는 null을 기록한다.
3. R33 JAR·seed·입력·플래너·Docker·fresh JVM·수치/해시/audit·network·cleanup은 유지한다.
   연결 수립/행정 명령/cleanup의 bounded wait는 workload 실행 deadline과 구분한다.
   reference/semantic 계산 경로에도 실험을 자르는 다른 deadline이 있는지 확인한다.
   실제 후보의 FED request 기본 read timeout(86400초)도 기존 지원 설정
   `sysds.federated.timeout=-1`로 비활성화한다. Java 소스/JAR 변경은 없다.
4. 새 immutable run06은 run05와 그 원본 run04의 검증 완료 결과를 SHA/provenance와 함께
   이어받는다. 성공 결과는 재실행하지 않는다. 기존 실패는 삭제/성공 처리하지 않고
   `--retry-failed`로 제한 없이 재측정한다. 중단된 불완전 조건도 재실행한다.
5. continuation은 엔진 동일성과 원본 증거를 계속 검사하되 **60초→무제한**이라는
   사용자 승인 정책 전환만 명시적으로 허용한다. 이전 결과의 timeout=60 provenance는 보존한다.
6. Docker 전용 run_LAN_docker.sh로 재개하고, 실제 원격 JVM 명령에 timeout이 없고
   60초가 넘어도 계속 실행/완료되는지 확인한다.

## 위험과 검출

- 무제한 실행은 실제 hang도 자동으로 끊지 않는다. 이는 사용자 요청대로 동작하는 것이며
  진행 로그/프로세스 상태로 관찰한다. 수치/정리 실패를 통과 처리하는 fallback은 없다.
- source chain은 cycle/변조/미정리를 거부하고 원본 출처를 유지한다.
- 이미 실행 중인 frozen harness를 몰래 바꾸지 않으며 새 root로 정책을 분리한다.

## 별도 검증 경로의 범위

이번 정정은 측정 대상 candidate의 compile/runtime 종료 제한이다. reference 생성과
post-timer 수치 검증은 다른 프로그램/출처 계약이므로 소스와 기존 제한을 유지한다.
예: P2 reference generator 14400초, comparator attached run 3900초, semantic metric
producer 3600초, binary decoder 300초. 이 제한들이 candidate workload JVM의 컴파일이나
실행을 60초에 끊는 것은 아니다. 따라서 “모든 SSH/검증 timeout까지 제거했다”고 주장하지 않는다.
