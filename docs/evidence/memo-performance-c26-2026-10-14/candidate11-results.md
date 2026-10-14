# Candidate11 Docker 결과

고정 이미지/자원/JFR/워크로드 조건으로 각 1회 실행했다. 전체 planning은 LogReg **3.861050224초**, GLM **6.776918729초**로 둘 다 3초 목표 미달이다.

| 항목 | LogReg | GLM |
| --- | ---: | ---: |
| Facts | 0.057798319 | 0.050844833 |
| Rules | 1.734332103 | 3.136894532 |
| Model | 0.554415594 | 1.359841519 |
| Optimization | 1.357889972 | 2.115567500 |
| Projection | 0.156614236 | 0.113770345 |
| 전체 planning | 3.861050224 | 6.776918729 |
| C10 대비 감소 | 8.92% | 14.89% |
| Coordinator CPU초 | 24.69 | 30.13 |
| HWM KiB | 523,708 | 702,320 |
| JFR allocation weight bytes | 1,773,745,728 | 4,002,334,264 |
| GC STW초 | 0.175542 | 0.247769 |

총23 gate 중21 PASS이며 실패는 두 시간 gate다. Exact objective, OPTIMAL, native universe, runtime audit, CP/FED numeric equivalence, frozen engine 12,628files와 JFR loss0를 확인했다. Maven43.948초,84classes600tests83.363초 PASS. 기존 stale training inventory5개는 suite에서 제외되어 미해결이다.

단일 관측이므로 분산과 변경별 인과를 확정하지 않는다. 공유 호스트의 비소유 VSCode JDT 프로세스 추가 부하도 기록했으며 종료하지 않았다. GLM은 이전 baseline runtime실패로 baselineCSV와 비교할 수 없고 독립 CP/FED numeric/audit로 검증했다.

기계 판정과 원 증거: candidate11-evaluation.json, candidate11-comparison.json, candidate11-verification.json, candidate11-artifact-sha256.txt 및 phase-hotspots/profile/monitor 파일. 성능 목표는 계속 진행 중이다.
