# Session issues — 2026-08-05

## 1. ALS MinST worker=1의 합법적인 idle-worker 증거를 harness가 실패로 오판함

- **상태**: 해결 — harness 수정 및 실제 보존 artifact 재검증 통과
- **환경/조건**:
  - campaign: `/home/mchoi/g014-one-pass-results-96858a1-cac3730-20260804-v2`
  - cell: `WAN-light/ALS/MinST/worker=1` (`cells/089-ba137724f34d`)
  - source/JAR/stage: `96858a1...` / `7d812e31...` / `268670c...`
- **재현 절차**:
  - `cells/089-ba137724f34d/runner.stderr.log`에서
    `ValueError: instruction CSV has no instruction rows`를 확인한다.
  - 실제 CSV는 frozen stage의
    `results/workers/stats/worker1-8001_d5d5597bf418b9e6d7b51267b0ca21a0_wan_light.csv`다.
- **관측 증상**:
  - cold `125.995 s`, warm `125.654 s` 실행과 semantic oracle/runtime scan/fallback/teardown은 성공했다.
  - coordinator/CP-dominant 계획이라 worker instruction row가 합법적으로 0개였으나 harness가 이를 실패 처리했고,
    campaign은 88/336에서 중단됐다.
- **원인 분석**:
  - planner/runtime 결함이 아니라 evidence collector가 schema-valid header-only statistics stream과 손상된 빈
    evidence를 구분하지 못했다.
- **해결 요약**:
  - header/path/hash/runtime receipt 인증은 유지하고 row 0개를 명시적 idle-worker evidence로 표현했다.
  - 0-byte/malformed CSV와 nonempty row의 빈 opcode는 계속 거부한다.
- **수정 파일**:
  - harness `experiments/tools/worker_evidence.py`
  - harness `experiments/tests/test_worker_evidence.py`
  - harness/source의 `docs/SESSION_ISSUES_2026-08-05.md`
- **검증**:
  - harness focused 4/4, 전체 147/147 통과, `py_compile` 통과, basedpyright 0 errors.
  - 실제 보존 run을 수정된 collector/validator로 읽어 row 0/opcodes `[]` 및 payload SHA-256
    `90f61d738eb2d48038fea7fc359e6e1c7b06207f88eef6c8a7eec0f8ea0d947f`로 인증했다.
- **잔여 이슈**:
  - v2는 재개/재시도/결과 stitching하지 않는다. issue 13의 MinST LogReg objective 이상을 해결하고 새 immutable
    stage에서 ALS idle-worker canary와 LogReg canary를 거친 뒤 fresh 336을 시작한다.
- **잠재 회귀 위험**:
  - malformed file 허용 위험은 nonempty/schema/opcode tests로 감지하며 file SHA와 receipt binding은 유지된다.
- **의사결정 근거**: planner 후보나 runtime을 바꾸지 않고 실제 합법 실행을 evidence layer가 표현하게 했다.
- **적용 원칙/제약**: runtime fallback 금지, Docker-only, exactly-once, 실패 campaign stitching 금지.
