# FedPlanner 성능 복구 기준선 고정 — 2026-09-30

## 결정

사용자의 **추가 최적화 중단·기존 최선 후보 고정·커밋·푸시** 요청에 따라 R33을 고정한다.
이는 **20초 목표 달성이나 전체 성능 검증 완료를 뜻하지 않는다.**

- 고정 소스: `candidate-r33-linear-preparation-source`와 `src/main` 전체 내용 동일. 테스트는 아래 공백 정리 외에는 동일하다.
- 검증 JAR SHA-256: `2faab28099fb383d6240f9868a0c870cea14c75defd484300022fffe76b6fa40`.
- R34는 설계만 진행했고 소스에 포함하지 않았다.
- R35 UTF-8 버퍼 변경은 단위 검증만 완료된 미측정 실험이므로 제외했다. 실험 소스와 패치는 외부 증거 디렉터리에 보존했다.
- 로컬 `target`은 고정 후보의 빌드 산출물을 복사한 별도 쓰기 가능한 디렉터리를 가리킨다. 원래 R11 산출물과 immutable R33 증거는 수정하지 않는다. JAR/로컬 절대 경로 symlink는 Git에 커밋하지 않는다.

## 선택 근거: 동일 Docker 일반 compile-only 측정

| 후보 | GLM W1 DP-global | GLM W3 DP-global |
|---|---:|---:|
| R29 | 49.710초 | 30.712초 |
| R30 | 48.648초 | **30.458초** |
| R32 | 48.317초 | 34.093초 |
| **R33 — 고정** | **45.865초** | 30.732초 |

R33은 이 두 검증 조건의 최악 시간과 합계가 가장 작다. W3 단독 최저는 R30이다.
각 값은 단일 일반 측정이며, 통계적으로 입증된 우승이나 모든 workload에서의 우위를 주장하지 않는다.
JFR 진단 시간을 일반 측정에 섞지 않았다.

R33 두 실행 모두 compile/플래닝/lowering/runtime-program construction 성공, cleanup 완료,
lowering mismatch/missing 0, workload 실행 0이다. W1/W3 각각 최종 plan fingerprint는 R30/R32와 동일하다.
기존 60초 watchdog, DP-local 타이머, 수치/탐색 cap, privacy, TR/TW 및 recompile 제약은 유지한다.

## 포함된 복구와 남은 병목

- 공통 분석의 immutable identity·서명·지원 관계 재사용과 정확한 삭제 전파 처리를 유지한다.
- 공유 source/header 표현과 정확한 dyadic separator 계산으로 전역 DP의 중간 결합을 압축한다. 후보를 임의로 잘라내거나 runtime fallback을 넣지 않는다.
- R33은 membership의 `header × rows` 반복을 한 번의 행 순회로 바꾸고, 함수 종속성 검사에서 boxed tuple 대신 방어적으로 복사된 primitive tuple을 사용한다.
- 여전히 W1 공통 분석만 23.913초이다. 전체 20초 목표는 **미달**이다.
- 전체 canonical 896 cells와 3회 fresh paired control gate는 완료하지 않았다. 고정은 사용자 요청에 따른 검증 가능한 기준선 확보이지 성능 gate의 승인으로 간주하지 않는다.

## 검증과 재현

- frozen R33 Maven: 64 classes / 581 tests / failure 0 / error 0 / 기존 ignore 1.
- 실제 JAR 대상 회귀: 533 tests PASS. 고정 후 같은 소스/JAR로 재실행하여 533/533 PASS를 확인했다.
- 커밋 전 새 테스트의 공백-only 3줄을 정리했다. 동일 javac로 정리 전후 생성한 class 파일 SHA가 완전히 같음을 확인했으며 production 변경은 없다.
- 성능 evaluator 단위 테스트: 10 PASS. 실제 20초 evaluator는 예상대로 FAIL.
- 전체 프로젝트 테스트 통과 주장은 하지 않는다. 이전 기준선에서도 재현된 `ExactPhysicalModelCertificateTest.sevenWorkloads`의 canonical cap 실패와 `ExactPhysicalForcedStateAuditTest.forcedPublishedState` fixture 실패가 별도로 남아 있다.

회귀 대상 클래스·원본 receipt 경로·phase 시간은 [기계 판독 기준선](FEDPLANNER_PINNED_BASELINE_2026-09-30.json)에 기록했다.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
TESTS=$(python3 -c 'import json; print(",".join(json.load(open("docs/FEDPLANNER_PINNED_BASELINE_2026-09-30.json"))["maven_test_classes"]))')
mvn -Djacoco.skip=true -Dtest-forkCount=1 -Dtest-perCoreThreadCount=false -Dtest="$TESTS" test
mvn -Djacoco.skip=true -Dmaven.test.skip=true package
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/fedplanner/tests -p test_evaluate_planning_performance.py
```

성능 재측정은 `scripts/fedplanner/run_LAN_docker.sh --campaign --phase compile`만 사용한다.
`run_LAN.sh`, host probe 시간, workload runtime을 성능 근거에 섞지 않는다.

## 증거 보관

외부 증거 루트:
`/grid/3/cofee-lm-sweep-mchoi-20260914/planning-30s-recovery-20260930`.

- `r33-validation/`: fresh build/회귀/manifest.
- `r33-first-docker-results.json`: 두 일반 Docker 실행 요약.
- `final-r33-pin/`: 고정 전 패치·제외한 실험·전체 source/test manifest·설치 기록·고정 후 회귀.
- 새 최적화 작업과 후속 진단 예약은 중단했다. 이미 실행 중이던 W3는 정상 cleanup까지 마친 뒤 serial driver를 종료했다.
