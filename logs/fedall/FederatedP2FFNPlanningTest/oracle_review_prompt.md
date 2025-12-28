(FederatedP2FFNPlanningTest)
역할: SystemDS federated planner 검증자. 런타임 소스(`src/main/java/org/apache/sysds/runtime/instructions/fed`)를 최종 근거로 사용해 Oracle trace 판단을 검증해줘.

입력:
1) `trace_fout_propagation.py` 출력 (필수, `--tree` 포함): logs/fedall/FederatedP2FFNPlanningTest/trace_fout_tree.txt
2) Oracle 원본 로그 파일 경로(필수): logs/fedall/FederatedP2FFNPlanningTest/test_cost.log
3) 런타임 소스 기준 경로(필수): src/main/java/org/apache/sysds/runtime/instructions/fed
4) 실행 DML 또는 테스트 설정(선택, 행/열 추정용): src/test/scripts/functions/privacy/fedplanning/P2_FFN.dml ; src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedP2FFNPlanningTest.java
5) 실험 종류/플래너 설정(선택): cost-based (SystemDS-config-cost-based.xml)

요청:
- 각 Path에서 최초로 parent_fout이 ROW가 아닌 지점(특히 none)을 검증.
- 해당 reason이 runtime 소스 기준으로 정당한지 판단.
- 정당하지 않은 판단에 대해서만 runtime 근거 파일/라인(가능하면) 제시.
