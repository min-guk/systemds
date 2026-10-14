# FedPlanner 실제 ML 성능 평가 하네스 전환

## 공식 성능 범위

LogReg와 GLM의 성능 합격 여부는 더 이상 작은 synthetic E2E 입력으로 판정하지 않는다.
공식 범위는 sealed W1357 stage가 렌더링하는 실제 LogReg·GLM workload와 worker
`1,3,5,7`의 조합 8개다. 각 후보는 각 조합을 한 번만 실행한다.

```bash
scripts/fedplanner/run_LAN_docker.sh --actual-ml-planning-performance \
  --engine <frozen-engine> \
  --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z \
  --output-root <new-empty-output-root>
```

합격하려면 8개 모두 Memo와 exact search가 `OPTIMAL`이어야 하며,
`PlanningFullInitialReceipt`, `RuntimeProgram`, 전체 compile 시간이 존재해야 한다.
full-initial planning과 전체 compile은 각각 10초 이하여야 하고 runtime execution은 0이어야 한다.
sealed DML, metadata, data block, privacy, range, worker topology hash 검증도 통과해야 한다.

## 삭제·보존 경계

기존 tiny LogReg/GLM 결과를 production cost 계약의 근거로 사용하던 경로 의존은 삭제했다.
하네스는 cost override가 없는 production 기본값과 4 CPU, 8 GiB, 3 GiB heap 계약을 직접 기록한다.
`run_joint_boundary_e2e.py`에서 runnable tiny `ml_logreg`, `ml_glm` case와 DML 생성 분기,
LogReg 전용 tiny label fixture를 삭제했다. 기존 이름을 `--case`로 요청하면 실제 workload
성능 명령을 안내하며 즉시 실패한다. 범용 E2E framework와 LogReg/GLM이 아닌 correctness case,
Java unit-graph correctness test는 유지한다. 과거 결과와 evidence는 재현 기록으로만 보존한다.

삭제 inventory:

- `Case("ml_logreg", ...)`, `Case("ml_glm", ...)` 선택 항목
- tiny `multiLogReg`·`glm` DML 생성 분기
- `Y_ML_LOGREG` 192행 synthetic label 생성
- 위 case를 성능 또는 JFR 대상으로 선택하던 Python test 경로

## 최종 검증 강화와 결과

- evaluator는 pinned renderer와 sealed stage를 다시 읽어 DML/config/render/adaptation artifact를 정확히 비교한다.
  topology/privacy/data block 검증을 새로 복제하지 않고 기존 renderer 검증을 재사용한다.
- 실행 engine의 C26 비용 모델 family/config bytecode와 전체 physical file inventory를 확인한다.
  외부 mutable dependency 및 Docker mount 실패를 막기 위해 symlink는 preflight에서 거부한다.
- matrix의 비정상 종료는 gate 결과와 관계없이 최종 실패로 보존한다.
- 실제 최종 8조건 모두 OPTIMAL/compile-only/10초 기준을 통과했다.
  [전체 결과 보고서](FEDPLANNER_ACTUAL_ML_UNDER10_RESULT_2026-10-14_KO.md).
