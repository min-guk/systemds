# FED rule·상관 support·DP 압축 구현과 검증

기준선은 `e468797556`이다. 여섯 영역을 수정·검증했지만 **모든 연산과 공동 비용을 끝까지 symbolic하게 처리하는 구현은 아니다.** 특히 입력별 물리 authority가 다른 FED tuple, VALUE_MAP와 함수 경계의 clause-sensitive 경로는 정확한 기존 전개를 유지한다. 아래에서 생성 단계의 객체 감소와 실제 workload의 성능을 구분한다.

| 요청 영역 | 이번 구현·검증 범위 | 남은 범위 |
| --- | --- | --- |
| 일반 FED rule | 정확한 closed header의 조건부 relation API·게시, 압축 이득이 있을 때만 사용 | 실제 weighted 생성의 686개 fact 제거는 미완료; live 압축 사례 미입증 |
| Joint/correlated support | 반복 source owner의 교집합 product, sparse 합법 ID, 혼합 DIRECT/RELOCATION region | VALUE_MAP·함수 경계의 clause-sensitive 경로 |
| Physical Model | 같은 authority/layout 관계를 Alternative와 source 제약으로 소비 | authority·geometry가 다른 branch 전개 |
| 공유·비분리 비용 | 기존 joint/shared factor 보존, explicit 대비 비용 bits·최적값 검증 | 임의 공동 비용의 일반적인 symbolic 분해 |
| DP | conditional union, quotient preimage, boundary join·projection의 압축 유지 | 일반 numeric cost table과 조밀한 elimination 결과 |
| LogReg·GLM | PRIVATE_AGGREGATE Docker 결과·비용·receipt 및 시간·메모리 비교 | 대규모 입력·반복 측정의 통계적 개선 입증 |

## 1. 일반 FED rule: header와 member 분리

`CandidateRuleRelation`은 입력 축의 product와 공통 capability·shape proof·profile·emission header를 가진 disjoint region의 union이다. 기존 Oracle rule이 선언한 의존 입력 축을 고정하고 나머지 축을 공유한다. Privacy와 emission 정책은 기존 판정 함수를 재사용한다.

현재 일반 FED 생산 경로는 **Closure 이전의 exact fact 생성을 제거하지 않는다.** 최초 구현은 scalar `WSLOSS`/`WCEMM` FED/LOUT 영역의 tuple 생성을 생략했으나, 기존 Closure가 붙이던 DIRECT source binding과 관련 action authority가 사라졌다. 비용과 출력은 같아도 의미 보존 조건에 실패했으므로 이 생성 생략은 철회했다.

수정된 경로는 다음과 같다.

1. 기존 생성·Closure에서 정확한 source·action·proof를 확정한다. 다른 support·derived action·transient compatibility가 참조하는 exact rule key는 변환에서 제외한다.
2. 동일한 닫힌 emission authority를 공유하는 행을 축별로 합쳐 조건부 relation으로 게시한다.
3. 실제로 region 수가 줄지 않는 singleton/sparse 경로는 기존 exact 표현을 유지한다.
4. Relation 소비와 최종 member 복원은 header의 원래 support 객체를 보존한다.

Source 참조 검사는 Factorized 축과 Indexed binding dictionary를 읽어 product를 전개하지 않는다. Header 그룹은 hash index로 만들고 축별로 한 번씩 병합한다. 서로 다른 header의 반복 쌍별 비교를 하지 않는다. 컴포넌트 검사는 `2×2→1 region`, sparse hole 보존, 서로 다른 exact authority의 비병합을 확인했다. 실제 weighted pipeline은 singleton이어서 explicit 표현을 유지하며, live FED relation의 성능 개선은 입증하지 않았다.

따라서 다음 생성량 표를 정확한 한계로 사용한다.

| 표현 | 생성하는 exact fact | 별도 CP family의 논리 tuple |
| --- | ---: | ---: |
| 완전 explicit reference | 2,401 | 0 |
| 이번 작업 기준선: CP family 적용 | 686 | 1,715 |
| source authority를 보존한 이번 변경 | 686 | 1,715 |

수정 전의 “686개 fact를 추가로 없앴다”는 결과는 채택하지 않는다. 일반 FED에서 이 생성 비용까지 줄이려면 relocation obligation·source inventory·proof binding 생성도 tuple fact 대신 relation을 직접 받아야 한다. 이번 변경의 주요 실제 감소는 상관 support의 물리 Alternative와 DP의 projection/join에 있다.

Physical Model은 header·support authority가 공통인 relation을 소비하고 필요할 때 정확한 member를 복원한다. 입력 authority·전송·비용이 다른 경우에는 Alternative 전개가 남는다. Legacy CandidateSelections/FedAll/PolicyGreedy API도 필요한 exact member를 순회한다. Canonical member 하나로 다른 입력 선택을 대체하지 않는다.

Oracle 의존 축 선언의 별도 검토에서는 weighted 및 unary의 정방향 capability·shape proof 동등성을 확인했다. Unary 23종×6배치의 138개 비교가 일치했다. 일반 matrix multiplication은 facade가 `mmult`를 선택하므로 적용되지 않는 `MMFedRule` family 선언은 제거했다. 실제 MM은 기존 exact 경로다.

## 2. 상관 support: source owner 그룹과 합법 ID

같은 source owner를 여러 입력이 공유하면 각 축을 독립 곱으로 보지 않는다. 정확한 `requiredInputSupportIdentity`가 같은 선택을 교집합으로 묶고, 서로 다른 owner 그룹 사이에만 product를 둔다.

예를 들어 `A, A, B`에서 A와 B의 source 선택이 각각 100개라면 `100³`개를 만든 뒤 불일치하는 A를 제거하지 않는다. A 선택 100개와 B 선택 100개로 합법 관계 10,000개를 표현한다. 실제 Closure 테스트에서 저장 선택은 200개, Clause 생성은 0개였고, 한 correlated binding의 무효화가 같은 그룹 및 후속 support에 전파되는지 검사했다.

일반 sparse 관계는 기존 합법 조합 ID와 binding dictionary를 유지한다. Physical Model에 넘길 때 proof metadata, source owner, DIRECT/RELOCATION 종류, 정확한 action, 전달되는 물리 layout이 같은 행끼리 region으로 묶는다. Region마다 생산자 선택의 joint 조건을 붙이므로 축을 projection하면서 존재하지 않는 조합을 다시 허용하지 않는다.

- `2×3` 중 4개만 합법인 관계: captured Alternative 1개와 조건부 support factor로 처리하고 나머지 두 hole은 계속 금지한다.
- DIRECT/RELOCATION이 섞인 `4×5` 관계: 20개 행을 물리 authority가 같은 captured region 2개로 묶는다. 부수적인 relocation-source Alternative는 별개다.
- Group이 완전한 owner product인지는 합법 ID의 개수·중복·source 일관성으로 증명한다. Product 자체를 생성해 검사하지 않는다.

Indexed relation의 fingerprint·canonical ordering·receipt rank는 행 metadata와 binding index를 직접 읽는다. 이전의 `fullyMaterializedSupportClauseCount()`가 indexed handle을 0으로 보고하던 문제도 수정했다. 새 검사는 fingerprint/rank 작성 전후 handle 0개, 실제 한 행 선택 후 handle 1개를 확인한다. Physical Model은 아직 region당 대표 clause 하나를 만들 수 있으므로 모든 단계에서 객체 0개라고 주장하지 않는다.

## 3. Physical Model과 비용

한 region의 authority와 전달 layout이 일정할 때만 대표 Alternative를 재사용한다. 실제 source identity는 생산자 변수의 정확한 support key와 조건부 factor에 남아 있으며, 선택이 끝나면 원래 relation이 소유한 clause를 찾아 receipt로 복원한다.

비용은 다음 의미를 보존한다.

- Kernel이 보는 입력 layout은 region의 고정된 실제 전달 layout이다.
- LOCAL source 업로드와 이미 FED에 있는 source의 비용은 구분한다.
- Shared supply의 활성화·한 번 청구·lifetime은 기존 공동 factor가 계속 계산한다.
- VALUE_MAP, 함수 boundary, 여러 입력의 geometry에 의존하는 비용을 입력별 최소값의 합으로 근사하지 않는다.

Compact/explicit 모델의 모든 합법 fixture 행에 대해 비용 raw bits와 receipt를 비교했고, Local/Exact 최적값도 비교했다. 기존 joint cost, source 공유, privacy, 함수 경계 검사를 함께 실행한다. **공유 비용을 항상 분리 가능한 함수로 바꾼 것은 아니다.** 분리 불가능한 cost factor와 clause-sensitive owner는 기존 정확한 표현을 유지한다.

## 4. DP join·projection·elimination

새 conditional support factor는 selector 값별 product의 union이다. 제한되지 않은 selector는 wildcard이고, 제한된 selector에 허용 region이 없으면 불법이다. Reduced solver, boundary message, regional merge가 이 표현을 보존한다.

Quotient의 한 값이 원래 여러 값에 대응해도 preimage product를 ID 목록으로 펼치지 않는다. Base row와 축별 preimage를 유지하고 이미 고정된 변수와 호환되는 선택만 natural join에서 방문한다.

| 검증 | 논리 공간 | 실제 보관·탐색 |
| --- | ---: | --- |
| Quotient projection | 100,000,000 cells | base row 1개와 축별 mapping; 고정값 join에서 support 방문 3회, 결과 1개 |
| Conditional regional boundary | 2,000,000 cells | 선택 index 4개; merge child 평가 2회, support 방문 최대 4회 |
| 무작위 quotient parity | 작은 공간 200개 | explicit 전개와 결과 집합 일치, 중복 결과 없음 |

이 숫자는 논리 cardinality와 저장량/방문량의 차이다. 실행 시간이나 JVM 전체 heap 감소율로 환산하지 않는다. Arbitrary sparse 관계는 합법 행 수에 비례하는 저장이 필요할 수 있고, 모든 numeric cost table과 모든 elimination 결과가 압축되는 것은 아니다.

### 시간·공간 복잡도가 바뀌는 범위

입력 축 크기가 `d₁,…,dₙ`이면 전체 tuple 수는 `P=∏dᵢ`이다. Tuple별 header 생성은 적어도 `Θ(P)`번 필요했지만, header가 의존하는 축 집합이 D이면 relation header 생성은 해당 결정 축의 조합 수 `∏ᵢ∈D dᵢ`에 좌우된다. Weighted 테스트는 Oracle가 이미 결정 축만 검사했으므로 호출 수는 전후 6회로 같다. 다만 정확한 source support를 확정하는 현재 일반 FED 경로는 여전히 개별 fact/key를 만들므로, 이 이론적인 생성량 감소를 일반 FED의 구현 성과로 해석하면 안 된다.

이 감소가 전체 컴파일 복잡도를 같은 비율로 줄인다는 뜻은 아니다. 물리 authority가 다른 tuple을 Alternative로 펼치는 fallback은 여전히 `Θ(P)` 작업을 할 수 있다. 독립 owner 그룹의 support product는 저장량을 각 그룹 선택 수의 합에 가깝게 유지하지만, 일반 sparse 관계는 합법 행 K와 입력 수 n에 대해 `O(Kn)` 검사·인덱스 처리가 남는다. DP도 결합·제거 후 관계가 조밀해지면 변수 domain의 곱에 비례할 수 있다. 압축은 구조가 있는 관계의 중복 비용을 줄이며, 임의의 공동 제약을 항상 다항 시간에 해결하는 알고리즘은 아니다.

## 5. 실제 LogReg·GLM 검증

동일 Docker image, Local planner, 4 CPU, 8 GiB container, coordinator `-Xmx3g`, PRIVATE_AGGREGATE ROW 입력으로 비교한다. LogReg는 192×8 입력과 3 class, `maxi=10`, `maxii=5`; GLM은 같은 크기 입력의 binomial/logit, `moi=5`, `mii=5`이다. GLM의 실제 `scripts/algorithms/GLM.dml`을 실행하도록 harness를 확장했다. 이 크기는 실제 알고리즘을 수행하는 회귀 workload이며 대규모 운영 입력을 대표한다고 가정하지 않는다.

Proof 검증과 성능 측정은 분리한다. Proof run은 commit 직후의 동일 result·receipt plan hash에 묶인 canonical reconstruction과 실행 후 runtime audit을 검사한다. 실행 후 mutable Hop으로 과거 계획을 재구성하지 않는다. Baseline에도 동일한 test-only observer와 진단 로그 overlay를 적용한다. 성능 run은 canonical 재구성·live metrics를 끈 동일 조건으로 비교한다.

아래는 동일 조건 각 1회의 uninstrumented Docker 관측값이다. 최종 소스 엔진 `engine-final-authority-20261008T2135`에서 다시 측정했고, weighted의 잘못된 support 감소는 포함하지 않는다. 시간 변동과 계측 해상도가 있으므로 통계적으로 확정된 개선율은 아니다.

| 지표 | LogReg 기준선 → 변경 | GLM 기준선 → 변경 |
| --- | ---: | ---: |
| FED 컴파일 | 24.824 → 24.241초 | 30.804 → 29.443초 |
| Candidate planning 전체 | 23.666 → 23.060초 | 28.974 → 27.809초 |
| PlacementAnalysis | 13.860 → 13.893초 | 26.523 → 25.294초 |
| Physical Model | 0.481 → 0.427초 | 0.240 → 0.271초 |
| Cost surface | 0.746 → 0.685초 | 0.320 → 0.338초 |
| Optimizer | 7.821 → 7.331초 | 0.647 → 0.687초 |
| 컨테이너 최고 관측 메모리 | 2.241 → 2.860 GB | 1.298 → 1.407 GB |
| 물리 Alternative | 5,304 → 5,295 | 1,774 → 1,755 |
| 전체 Oracle 호출 | 7,989 → 7,989 | 5,821 → 5,821 |

메모리는 coordinator·worker·harness를 포함한 컨테이너 전체의 `docker stats` 반복 관측값이며 JVM retained heap이 아니다. 각 호출 후 1초 대기하므로 실제 간격에는 호출 지연도 포함된다. 표의 최대 관측값은 실제 순간 peak를 보장하지 않는다. `candidateOracleCalls`는 `relationOracleCalls`를 포함하므로 더해서 집계하지 않는다. 두 workload의 Closure support leaf/prefix와 retained 구조 handle counter는 전후 같았다. 즉 실제 workload에서 큰 조합 생성 감소를 입증한 결과는 아니다. Planning 전체는 LogReg 약 2.56%, GLM 약 4.02% 줄었지만, LogReg analysis는 약 0.24% 늘었고 GLM analysis도 여전히 약 25.3초로 가장 큰 비용이다. 최고 관측 메모리는 LogReg **27.65%**, GLM **8.35% 증가**했다. 따라서 실제 workload의 전체 메모리 절감을 입증하지 못했다. 압축된 support·DP의 국소적인 저장 감소를 JVM/컨테이너 전체 메모리 감소로 확대해 해석하지 않는다.

두 workload의 최적 비용 raw bits, 선택한 exact candidate receipt·전송·공유 lifetime은 기준선과 같았다. FED 모델 파일 SHA256도 기준선과 같고, CP 기준 모델과의 최대 차이는 LogReg `2.22e-16`, GLM `6.25e-17`이었다. Runtime fallback·repair는 모두 0이다. 표현을 포함하는 cost-surface fingerprint는 바뀌므로 hash 동일성을 주장하지 않는다. 별도 weighted 검증에서 발견한 source binding 누락은 수정했다. 최종 Docker의 WSLOSS/WCEMM은 exact source·action·proof·support가 기준선과 일치하며, objective bits와 cost-surface fingerprint도 같았다. 기준선의 52개 Alternative, 65개 hard factor, support counter 54개가 모두 복원됐다. 실제 published FED family는 0개다.

원자료는 `experiments/general-factorized-plan-space-20261008/` 및 아래 재현 경로에 보관한다.

## 6. 재현과 남은 전개 지점

통합 컴파일·테스트:

```bash
python3 experiments/general-factorized-plan-space-20261008/verify.py
python3 -m unittest discover -s scripts/fedplanner/tests -p test_run_joint_boundary_e2e.py -v
git diff --check
```

`verify.py`는 동결된 기존 engine/dependencies 위에 변경 소스를 컴파일하며 compile/test 명령과 SHA256을 기록한다. 전체 Maven test suite 실행과는 구분한다. 전체 engine와 Docker 원자료는 `/grid/3/cofee-lm-sweep-mchoi-20260914/general-factorized-plan-space-20261008/`에 있다. 마지막 요약 저장 때 root 파일시스템이 가득 차 이번 worktree의 `target`을 같은 저장소의 `repo-target-final-local-archive`로 옮기고 원래 경로에 symlink를 유지했다. 소스·산출물은 보존했고 비교 JSON을 원본 로그로 복구했다. Docker daemon에서 이 mount가 보이지 않아 실행용 입력은 별도의 `/dev/shm` 경로로 복사하며, 종료 후 원자료를 `/grid`로 회수한다. 저장소에는 작은 명령·로그·JSON을 남긴다.

최종 소스의 통합 JUnit **533개 통과**, 기존 ignore 3개, Python harness **38개**와 receipt authority 비교기 **7개 통과**를 확인했다. Production 소스 32개와 테스트 소스 34개를 `javac -Xlint:unchecked`로 컴파일했다. Production 경고는 없고 기존 `NormalizedPlannerResult` raw-list 반환을 감싼 테스트 helper에 unchecked 경고 2개가 남는다. 새 solver의 독립 리뷰 및 focused 회귀 53개도 통과했다. 오래된 hash 상수는 baseline/revised 양쪽 구조·cost bits·fingerprint가 같은 증거를 확보한 뒤 갱신했으며, 관련 검사나 privacy 정책은 삭제하지 않았다.

남은 전개 지점은 명확하다.

1. 일반 FOUT/derived-action rule의 exact source/action key는 아직 region selector로 대체되지 않았다.
2. 일반 FED의 source/action Closure는 tuple별 fact를 아직 생성한다. 게시 단계의 같은-authority 압축과 생성 제거는 다르다. 비용·authority가 다른 tuple은 Physical Model에서도 Alternative로 전개한다.
3. 혼합 proof/action을 생성하는 Closure 일부는 임시 Clause를 만든 뒤 합법 ID로 게시한다.
4. VALUE_MAP, 함수 경계 및 그 support dependency가 선택 clause를 관찰하면 명시적 Alternative를 유지한다.
5. Indexed sparse fallback은 합법 ID 수에 비례한다. DP는 conditional/quotient hard relation을 보존하지만 모든 수치 비용 함수를 factorized하게 표현하지는 않는다.
6. Legacy selector의 exact-row API와 최종 emission/receipt 복원은 실제 member를 만든다.

따라서 “처음부터 끝까지 모든 관계의 전개가 없어졌다”는 결론은 성립하지 않는다. 생성량 감소가 증명된 경로, 정확한 fallback, 실제 workload에서 남은 병목을 구분해 후속 작업 기준으로 삼는다.

최종 요약 데이터는 [workload-comparison.json](../experiments/general-factorized-plan-space-20261008/workload-comparison.json), 테스트·소스 일치는 [verification.json](../experiments/general-factorized-plan-space-20261008/verification.json), weighted의 구조화된 authority 비교는 [final-weighted-authority-comparison.json](../experiments/general-factorized-plan-space-20261008/final-weighted-authority-comparison.json)에 있다. 수정 전 실패 자료는 삭제하지 않고 별도 이름으로 보존했다.
