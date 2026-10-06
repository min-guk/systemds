# R56 explicit WDivMM / alias GET 통일 및 Docker 처리량 실측

## 상태 / 편집 전 계획

코드 수정·대표 검증 완료. 추가 validation은 “이전 모델보다 적당히 나으면 그만”이라는 최신 사용자 지시에 따라 중단했다. 최초 사용자 요청은 (1) 명시적 Quaternary WDivMM의 FType-only 재사용 가정을 실제 selected range/address 기준으로 통일, (2) 서로 다른 TRead의 동일 physical value GET 통합, (3) 기존 Docker 조건에서 GET/PUT 처리량과 coordinator NIC 경합 측정이다. 전체 workload campaign이나 commit/push는 별도 요청 없이 수행하지 않는다.

### 보존 / 예외 근거

- main의 미커밋 작업과 target symlink를 보존하고 `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-alias-calibration-r56-20261005/source`에서 편집/빌드한다. R55 frozen source/JAR는 변경하지 않는다.
- cost만 바꾼다. runtime fallback, 후보 축소, Oracle/privacy/TR-TW/recompile 규칙 완화는 하지 않는다. 값 갱신·다른 호출 문맥·서로 다른 physical layout을 같은 객체로 합치지 않는다.
- R54/R55에 문서화한 PUBLIC compiler-only regression 예외는 같은 근거로 유지한다. throughput 측정에는 개인정보 없는 합성 payload를 쓴다. 합성 PUBLIC payload는 전송 처리량을 직접 측정하기 위한 것으로 privacy 우회나 PUBLIC workload 성능 우위를 논문 근거로 삼지 않는다. 전송량·수치검증·Docker 격리 및 자원 조건을 기록한다.
- compile/runtime timeout 없음. 외부 worker에 기존 실행이 있으면 소유권 확인 없이 종료/재설정하지 않는다. 측정도 `run_LAN_docker.sh` 경유를 원칙으로 하고 물리 호스트 Java 결과를 성능 근거로 채택하지 않는다.

### 회귀 우선 정리 순서

1. RED: explicit/fused aligned/misaligned ROW/FULL U, COL/COL_T V, MX vs EPS; relocation과 native-local 비용 중복; 서로 다른 TRead의 같은/다른 source version/context/lifetime/layout 및 branch/loop 경계.
2. 명시적 입력 준비 경로를 기존 runtime-alignment helper와 실제 selected input projection으로 통일한다. unknown geometry는 비용 할인 근거가 아니며 지원 가능한 후보는 그대로 둔다. 실제 GET은 materialization collector, PUT은 runtime owner에 귀속해 FType-only 예외를 삭제한다.
3. GET collector를 공통 physical source identity로 모으되 actual read-state demand를 보존한다. 같은 source occurrence/value version/context/creation lifetime/physical representation이 증명될 때만 union한다. ambiguous reaching definitions는 병합하지 않는 증거 경계로 유지하고 회귀로 고정한다.
4. 새/기존 회귀 → isolated package → final-JAR 회귀·lint·diff·독립 검토 → SHA guard 동기화.
5. 동일 Docker image/CPU/memory/network 조건 및 현재 job 부재를 확인한다. payload 크기·worker 수·warmup/repeat를 사전 기록하고 GET/PUT을 분리 측정한다. wall time, 실제 bytes, coordinator CPU/NIC, worker serialization을 가능한 측정 항목별로 분리한다. 식별되지 않은 유효 계수를 codec throughput이라고 부르지 않는다. raw data와 fit/held-out 오차를 남기며 측정이 뒷받침하지 않는 상수는 강제 도입하지 않는다.

### 위험 / 감지

- relocation GET/PUT 또는 runtime native-local 준비비용의 이중계상: 각 경로별 cost-component 회귀.
- alias 병합에 의한 오래된 값 재사용/비용 누락: updated version, branches, repeated function contexts, owner activation 검사.
- factor scope 증가/계획시간 퇴행: U/V/MX를 한 tensor로 합치지 않고 source/target 관측 동치 및 기존 activation encoding 사용, compile-only suite 시간 기록.
- throughput 측정 오염: 공유 job, warm cache, JIT, Docker shaping 및 artifact SHA를 분리 기록. 같은 프로파일의 기존 network를 임의로 바꾸지 않는다.

## 구현 및 대표 검증 결과

### 공통 비용 규칙

- 명시적 Quaternary WDivMM도 fused 경로와 같은 실제 selected worker/address/range 검사를 사용한다. FType만 같은 것은 무료 재사용의 증거가 아니다. ROW/FULL U, COL/COL_T V, MX matrix와 EPS scalar를 runtime 경로대로 구분한다. 실행 worker pool은 input0 W이며 remote U/V의 별도 worker를 kernel worker로 더하지 않는다.
- 일반 CP 수집, native-local 준비, REFED source, explicit/fused factor GET은 materialization collector로 통일했다. native-local/REFED 준비 factor에는 PUT/forwarding만 남기고 GET을 중복 계상하지 않는다. REFED 이후 새 FED 객체를 다시 수집해야 하는 경우에는 이전 source lifetime이 아니라 새 materialization의 실행 수명을 사용한다.
- 서로 다른 TRead를 합칠 때 unique origin/value version + creation profile + read execution context + 정확한 physical layout + 실제 read-state demand를 모두 유지한다. 갱신된 값, 다른 호출 문맥, ambiguous provenance, unknown map, fresh/derived output, active relocation은 동일 copy라고 추측하지 않는다. payload 가격 관측이 다른 경우도 보수적으로 분리한다.
- alias union으로 STEP-LM의 activation scope가 커져 categorical Cartesian table overflow가 발생했다. 동일 Event(weight,conditions)만 정확히 quotient하고 AND/OR Boolean chain으로 demand activation을 표현했다. 기존 conservativeUnion 비용 함수를 그대로 호출한다. 실제 사례는 16개 categorical 변수/16 demand → 5 unique event, source3 × 2^5 =96 monetary cells가 됐다. 서로 다른 event 수에 대한 지수 폭은 남으며, 근거 없는 pruning/cap은 추가하지 않았다.

### 검증

- 최종 JAR 비용284 + 인접188 = **472 PASS**, 기존 ignored2 유지.
- 별도 W2C14.7/production logging focused run **73 PASS** (위 tests의 중복 실행이므로 합계에는 더하지 않음).
- Maven package **BUILD SUCCESS,35.775초**. 이는 artifact build 시간이며 DML planning20초 보장과 다르다. 병행 실행한 테스트의 wall time을 성능 비교 근거로 쓰지 않는다.
- production3파일 `javac -proc:none -Xlint:all,-path` 경고0. probe의 incubator module3경고는 구분한다.
- 최종 JAR SHA256: `b56fc97e4b8b17b4fc5cc03f65faace7bbaf0cc7318de760221f2cd047bef131`.
- native-local ownership golden은 GET을 별도 source collector로 옮겨 구조/가격 fingerprint가 바뀌므로 갱신했다. aligned/remote explicit PUT·GET, 추가 CP consumer 공유, updated value 분리 테스트를 먼저 통과시킨 후 갱신했으며 legality full-product/survivor-cost 검사를 그대로 유지했다. 기대 planner 선택을 유리하게 강제하지 않았다.

### 실측 환경의 실제 문제와 수정

첫 preflight에서 so007에 JAR가 없음을 확인했다. `/grid/3`는 공유 FS가 아니라 **각 host의 로컬 disk**였다. 컨테이너 시작 전 실패했으며 실패 자료를 보존했다. per-run JAR/lib/probe/config를 동결하고 같은 절대경로로 host별 복제한 뒤 전체 mounted inventory의 SHA를 검증하도록 수정했다. 기존 부분/불일치 stage는 덮어쓰지 않는다. 이어 so002 Snap Docker가 `/grid/3` bind를 볼 수 없어 rc125를 반환했다(기존 evaluation runbook에도 문서화된 제약). daemon 권한/설정을 변경하지 않고 실제 Docker용 runtime stage 약290MiB만 `/home/mchoi/cofee-evaluation/transport-stages/<run-id>`에 동결한다. source와 evidence는 grid3에 보존하며 각 host에 stage 크기+1GiB 여유를 확인한다. symlink 우회 없이 실제 파일을 복제하고 BatchMode SSH 및 ancestor symlink 거부를 검증한다. 최종 Python18 tests PASS(main에서도18 PASS). 실제 runtime과 compile에는 timeout을 넣지 않는다.

합성 dense payload 64KiB/4/16/64MiB, W1/W3, fixed-total/fixed-per-worker/skew, GET/shared-broadcast PUT/distinct-slice PUT을 LAN/WAN-Light/Mid/Heavy에서 검증한다. warmup3 + 측정5회. 정확한 값 검증과 coordinator NIC 실제 counter·cgroup CPU, GET retrieval/decode와 assembly를 분리 기록한다. profile/worker당 fresh JVM1개이므로 warm 반복5회를 독립 JVM5개로 해석하지 않는다. `4/16MiB fit → 64MiB held-out` 보고서를 남기며 codec-only 계수라고 부르지 않는다.

### 원본 반영 및 실행 provenance

이번 production3 + Java test7 + Docker/script/probe/Python test4 =14파일을 baseline SHA guard 후 main working tree에 반영했다. 다른 dirty113파일, HEAD `0b51cc3ef883b9edb284cd4ae0f2b0358a38c592`, 기존 target symlink를 보존했다. production/test/builtin3697개가 isolated source와 동일하다. 최종 JAR의 변경 class67개가 target class와 byte-identical임을 확인했다. commit/push는 하지 않았다.

실행 중 independent review가 원격 disk reserve의 `python -c` argv index 오류를 찾아 수정했다. 실제 진행 중인 run은 이미 모든 host로 복제 및 SHA 검증을 완료한 상태여서 멈추거나 같은 측정을 다시 하지 않았다. **실제 실행 runner/probe/entrypoint는 output/executed-code에 manifest SHA와 일치하게 동결**했으며, 이후 수정은 staging guard의 필수 reserve 검증뿐이다. 실행 JAR/probe/전송식은 바뀌지 않았다. regression은 missing/oversized reserve를 stage 생성 전에 거부함을 RED→GREEN으로 고정했다.

## 실측 결과와 validation 종료 판단

최신 사용자 지시로 전체8-cell 검증을 완료할 때까지 반복하지 않았다. **LAN W1 100 + LAN W3 140 + WAN-Light W1 100 =340개** 검증된 timed sample을 보존했다. 세 cell 모두 전체 payload 수치 일치 및 netem 품질 검사를 통과했다. 진행 중이던 WAN-Light W3는 정확한 controller PID에 SIGINT를 보내 중단했으며 표본으로 채택하지 않았다. WAN-Mid/Heavy는 시작하지 않았다. 관련 coordinator/worker는 immutable ID/owner/spec 기준 cleanup이 모두 resolved이며 실행 컨테이너가 남지 않았다. 이는 **사용자 요청에 따른 중단**이지 runtime/compile timeout이 아니다.

### LAN 대표값 (5회 중앙값)

| 연산/분포 | W1 | W3 |
|---|---:|---:|
| GET, 총64MiB 고정 | 1.276초 /50.15MiB/s | 0.693초 /92.32MiB/s |
| GET, worker당64MiB | 1.201초 /53.29MiB/s | 2.408초 /총79.74MiB/s |
| sliced PUT, 총64MiB 고정 | 1.176초 /54.42MiB/s | 0.697초 /91.83MiB/s |
| broadcast PUT, worker당64MiB | 1.204초 /총53.14MiB/s | 2.138초 /총89.81MiB/s |

- NIC 조사에서 **coordinator와 세 worker의 실제 `ens5f0` link는1000Mbit/s**다. LAN netem의5000Mbit/s 설정은 물리 NIC를5Gbps로 만들어 주지 않는다. W3 fixed-total GET의 실제 coordinator RX는 약795Mbit/s였다. worker당 처리량을 단순히3배로 늘리는 가정은 이 공유 링크와 양립하지 않는다.
- 같은 총량은 병렬 분할로 빨라지지만, worker마다64MiB씩 응답하는 총192MiB 조건에서는 aggregate throughput이 제한된다. coordinator CPU도 해당 GET에서 약1.39core로 관측되어8개 thread가 언제나8배 codec 처리량을 제공한다는 근거가 없다.
- 이 측정은 실제 SystemDS GET_VAR/PUT_VAR + network/codec/remote 처리까지 포함한다. 순수 codec throughput이라고 부르거나 이 값만으로 workload 전체 runtime 개선율을 주장하지 않는다.

### 이전 설정 대비 추정 정확도

**LAN GET,4/16MiB로 적합하고 별도의64MiB 표본25개로 확인**했다.

- 이전 R55 공통 GET 식 + 현행 campaign의 W2C processing **14.7MiB/s** 설정: held-out 중앙 상대오차 **400.99%**.
- 실측 보정식 `t[s] =0.0038293 +0.00348185×largest[MiB] +0.00914895×total[MiB]`: held-out 중앙 상대오차 **17.64%**.
- broadcast PUT와 slice PUT의 동일 feature 적합식은 held-out 중앙 상대오차 각각 **20.81%**, **16.71%**다.

이 정도면 기존 보정값의 부정확성과 개선 방향을 확인한 대표 검증으로 충분하다는 최신 기준에 따라 추가 validation을 종료했다. **위 보정식은 새로 측정·적합한 후보이며 production 전역 상수에 자동 적용하지 않았다.** Java의 기본 serdes term 자체는0(disabled)이고14.7은 기존 campaign 설정이다. LAN fit을 WAN 공통 codec 상수로 옮기는 것은 이 데이터가 증명하지 않는다. WAN-Light는 W1만 완료됐으므로 largest/total 계수의 별도 fit 근거로 사용하지 않았다.

### 근거 경로

- source/JAR/evidence: `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-alias-calibration-r56-20261005/`
- accepted samples/fit/cleanup: `/grid/3/cofee-lm-sweep-mchoi-20260914/cost-alias-calibration-r56-20261005/transport-r56-20261005T062918Z/`
- `validation-stopped.json`:340 accepted rows, interruption/미실행 cell, 중단 사유 및 비교 수치.
- `fit-heldout.json`: LAN 적합계수,64MiB 개별 잔차, raw sample 기반 case medians, 계수 비채택 및 측정 한계.
- 각 cell의 `samples.json`, `network-quality.json`, `preflight.json`, `cleanup.json`; `executed-code/`는 실제 실행한 source snapshot이다.
- `evidence/final-build-proof.json`, `final-sync-proof.json`, `verification.json`, `independent-review.json`: JAR/source/main 일치 및 검증.

## 남는 한계 / 회귀 위험

1. unknown/ambiguous layout/provenance는 증명된 alias가 아니므로 여전히 보수적 중복 과금이 가능하다. 실제 지원 조합을 닫는 것으로 해결하지 않는다.
2. distinct activation event 수 자체가 큰 경우 monetary table의 지수 폭이 남는다. 이번에는 동일 event의 정확한 quotient만 적용했으며 approximate pruning은 없다.
3. 한 profile/worker-count당 fresh JVM1개이며 warm repeat5개가 독립 JVM5개는 아니다. sparse/compressed payload,8-worker scaling,다른 host 및 WAN-Mid/Heavy에 대한 계수 정밀도는 이번 종료 기준에 포함하지 않았다.
4. costs 재순위는 합법 계획 선택을 바꿀 수 있다. 전체 workload runtime 향상이나 모든 compile/planning20초를 검증한 것은 아니다. 추가 검증은 사용자 요청 없이 재개하지 않는다.
5. native-local PUT factor의 기존 binary/projection 틀은 유지했다. source 가격축은0이며 GET은 common collector에만 있다. 이를 unary로 바꾸는 별도 구조 변경은 현재 정확성에 필요하지 않아 하지 않았다.
