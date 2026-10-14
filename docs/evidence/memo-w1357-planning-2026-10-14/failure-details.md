# 조건별 실패 원로그 진단

raw 결과는변경하지않았다. 아래진단은완료된Memo시간이아니며원로그의오류와완료phase를수집한것이다.

## ml|pca|lan|w1|Memo

- 관측분류: UNKNOWN_SIZE
- rawCategory: evidence_validation_failure
- 종료wall: 2.093847081065178s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-pca-w1/coordinator.log)

```text
CostException -- UNKNOWN_SIZE: unknown multi-return output for hop 4027 FunctionOp/fcall _internal EIGEN UNKNOWN -1x-1
```

## ml|pca|lan|w3|Memo

- 관측분류: UNKNOWN_SIZE
- rawCategory: evidence_validation_failure
- 종료wall: 2.1844250485301018s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-pca-w3/coordinator.log)

```text
CostException -- UNKNOWN_SIZE: unknown multi-return output for hop 4184 FunctionOp/fcall _internal EIGEN UNKNOWN -1x-1
```

## ml|pca|lan|w5|Memo

- 관측분류: UNKNOWN_SIZE
- rawCategory: evidence_validation_failure
- 종료wall: 2.259118027985096s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-pca-w5/coordinator.log)

```text
CostException -- UNKNOWN_SIZE: unknown multi-return output for hop 4334 FunctionOp/fcall _internal EIGEN UNKNOWN -1x-1
```

## ml|pca|lan|w7|Memo

- 관측분류: UNKNOWN_SIZE
- rawCategory: evidence_validation_failure
- 종료wall: 1.9607459530234337s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-pca-w7/coordinator.log)

```text
CostException -- UNKNOWN_SIZE: unknown multi-return output for hop 4484 FunctionOp/fcall _internal EIGEN UNKNOWN -1x-1
```

## ml|als|lan|w1|Memo

- 관측분류: UNKNOWN_SIZE
- rawCategory: evidence_validation_failure
- 종료wall: 2.7664879858493805s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-als-w1/coordinator.log)

```text
CostException -- UNKNOWN_SIZE: unknown kernel input 0 for hop 10835 ReorgOp/r(r') MATRIX 10x128: hop 10836 DataOp/TRead V MATRIX -1x-1
```

## ml|als|lan|w3|Memo

- 관측분류: AMBIGUOUS_EXECUTION_LAYOUT
- rawCategory: evidence_validation_failure
- 종료wall: 2.764679752290249s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-als-w3/coordinator.log)

```text
CostException -- AMBIGUOUS_FED_EXECUTION_LAYOUT: FED/LOCAL rule does not prove one execution layout for hop 10425 QuaternaryOp/q(wdivmm) MATRIX 50000x10: [ROW, LOCAL, COL, LOCAL] variant=oracle/wdivmm/ok
```

## ml|als|lan|w5|Memo

- 관측분류: AMBIGUOUS_EXECUTION_LAYOUT
- rawCategory: evidence_validation_failure
- 종료wall: 2.710731163620949s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-als-w5/coordinator.log)

```text
CostException -- AMBIGUOUS_FED_EXECUTION_LAYOUT: FED/LOCAL rule does not prove one execution layout for hop 10575 QuaternaryOp/q(wdivmm) MATRIX 50000x10: [ROW, LOCAL, COL, LOCAL] variant=oracle/wdivmm/ok
```

## ml|als|lan|w7|Memo

- 관측분류: AMBIGUOUS_EXECUTION_LAYOUT
- rawCategory: evidence_validation_failure
- 종료wall: 3.114755317568779s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-als-w7/coordinator.log)

```text
CostException -- AMBIGUOUS_FED_EXECUTION_LAYOUT: FED/LOCAL rule does not prove one execution layout for hop 10725 QuaternaryOp/q(wdivmm) MATRIX 50000x10: [ROW, LOCAL, COL, LOCAL] variant=oracle/wdivmm/ok
```

## ml|kmeans|lan|w1|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.3243687078356743s
- 완료phase: 
- [로그](initial56/attempts/ml-kmeans-w1/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=1]]
```

## ml|kmeans|lan|w3|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.068269245326519s
- 완료phase: 
- [로그](initial56/attempts/ml-kmeans-w3/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=1]]
```

## ml|kmeans|lan|w5|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.378827691078186s
- 완료phase: 
- [로그](initial56/attempts/ml-kmeans-w5/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=1]]
```

## ml|kmeans|lan|w7|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.1675049141049385s
- 완료phase: 
- [로그](initial56/attempts/ml-kmeans-w7/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=1]]
```

## ml|lm|lan|w1|Memo

- 관측분류: UNRESOLVED
- rawCategory: planner_failure
- 종료wall: 2.3089351281523705s
- 완료phase: facts,rules,model,optimization
- [로그](initial56/attempts/ml-lm-w1/coordinator.log)

```text
IllegalStateException -- Memo planner did not prove an optimum: UNRESOLVED [privacy-pruned owner=10 occurrence=10 variant=cp/local reason=protected input requires federated residency, privacy-pruned owner=10 occurrence=10 variant=fed/forced-local reason=protected output slot 0 may not be materialized locally, owner=26 occurrence=26 hop=744 op=b(solve) Diagnostic[kind=COVERAGE_GAP, code=NATIVE_CATALOG_NOT_IMPLEMENTED, pattern=[REP, REP], message=the selected oracle rule has no capability implementation]]
```

## ml|lm|lan|w3|Memo

- 관측분류: UNRESOLVED
- rawCategory: planner_failure
- 종료wall: 2.3125415444374084s
- 완료phase: facts,rules,model,optimization
- [로그](initial56/attempts/ml-lm-w3/coordinator.log)

```text
IllegalStateException -- Memo planner did not prove an optimum: UNRESOLVED [privacy-pruned owner=18 occurrence=18 variant=cp/local reason=protected input requires federated residency, privacy-pruned owner=18 occurrence=18 variant=fed/forced-local reason=protected output slot 0 may not be materialized locally, privacy-pruned owner=18 occurrence=18 variant=fed/forced-local reason=protected output slot 0 may not be materialized locally, privacy-pruned owner=18 occurrence=18 variant=fed/forced-local reason=protected output slot 0 may not be materialized locally, owner=26 occurrence=26 hop=802 op=r(rdiag) Diagnostic[kind=COVERAGE_GAP, code=CANONICAL_EFFECT_NOT_AUDITED, pattern=[COL], message=The native output has no proved canonical range effect for this family and shape], owner=38 occurrence=38 hop=814 op=b(solve) Diagnostic[kind=COVERAGE_GAP, code=NATIVE_CATALOG_NOT_IMPLEMENTED, pattern=[ROW, ROW], message=the selected oracle rule has no capability implementation]]
```

## ml|lm|lan|w5|Memo

- 관측분류: UNRESOLVED
- rawCategory: planner_failure
- 종료wall: 2.236114487051964s
- 완료phase: facts,rules,model,optimization
- [로그](initial56/attempts/ml-lm-w5/coordinator.log)

```text
IllegalStateException -- Memo planner did not prove an optimum: UNRESOLVED [privacy-pruned owner=26 occurrence=26 variant=cp/local reason=protected input requires federated residency, privacy-pruned owner=26 occurrence=26 variant=fed/forced-local reason=protected output slot 0 may not be materialized locally, privacy-pruned owner=26 occurrence=26 variant=fed/forced-local reason=protected output slot 0 may not be materialized locally, privacy-pruned owner=26 occurrence=26 variant=fed/forced-local reason=protected output slot 0 may not be materialized locally, owner=34 occurrence=34 hop=854 op=r(rdiag) Diagnostic[kind=COVERAGE_GAP, code=CANONICAL_EFFECT_NOT_AUDITED, pattern=[COL], message=The native output has no proved canonical range effect for this family and shape], owner=50 occurrence=50 hop=870 op=b(solve) Diagnostic[kind=COVERAGE_GAP, code=NATIVE_CATALOG_NOT_IMPLEMENTED, pattern=[ROW, ROW], message=the selected oracle rule has no capability implementation]]
```

## ml|lm|lan|w7|Memo

- 관측분류: UNRESOLVED
- rawCategory: planner_failure
- 종료wall: 2.3673149943351746s
- 완료phase: facts,rules,model,optimization
- [로그](initial56/attempts/ml-lm-w7/coordinator.log)

```text
IllegalStateException -- Memo planner did not prove an optimum: UNRESOLVED [privacy-pruned owner=34 occurrence=34 variant=cp/local reason=protected input requires federated residency, privacy-pruned owner=34 occurrence=34 variant=fed/forced-local reason=protected output slot 0 may not be materialized locally, privacy-pruned owner=34 occurrence=34 variant=fed/forced-local reason=protected output slot 0 may not be materialized locally, privacy-pruned owner=34 occurrence=34 variant=fed/forced-local reason=protected output slot 0 may not be materialized locally, owner=42 occurrence=42 hop=906 op=r(rdiag) Diagnostic[kind=COVERAGE_GAP, code=CANONICAL_EFFECT_NOT_AUDITED, pattern=[COL], message=The native output has no proved canonical range effect for this family and shape], owner=62 occurrence=62 hop=926 op=b(solve) Diagnostic[kind=COVERAGE_GAP, code=NATIVE_CATALOG_NOT_IMPLEMENTED, pattern=[ROW, ROW], message=the selected oracle rule has no capability implementation]]
```

## ml|logreg|lan|w1|Memo

- 관측분류: OPTIMIZER_TIMEOUT
- rawCategory: timeout_not_proven
- 종료wall: 300.4747371673584s
- 완료phase: facts,rules,model
- [로그](initial56/attempts/ml-logreg-w1/coordinator.log)

```text
coordinator300초timeout; 완료된search/optimizer/Memo총시간없음
```

## ml|logreg|lan|w3|Memo

- 관측분류: OPTIMIZER_TIMEOUT
- rawCategory: timeout_not_proven
- 종료wall: 300.47937363386154s
- 완료phase: facts,rules,model
- [로그](initial56/attempts/ml-logreg-w3/coordinator.log)

```text
coordinator300초timeout; 완료된search/optimizer/Memo총시간없음
```

## ml|logreg|lan|w5|Memo

- 관측분류: OPTIMIZER_TIMEOUT
- rawCategory: timeout_not_proven
- 종료wall: 300.53942435979843s
- 완료phase: facts,rules,model
- [로그](initial56/attempts/ml-logreg-w5/coordinator.log)

```text
coordinator300초timeout; 완료된search/optimizer/Memo총시간없음
```

## ml|logreg|lan|w7|Memo

- 관측분류: OPTIMIZER_TIMEOUT
- rawCategory: timeout_not_proven
- 종료wall: 300.4855346009135s
- 완료phase: facts,rules,model
- [로그](initial56/attempts/ml-logreg-w7/coordinator.log)

```text
coordinator300초timeout; 완료된search/optimizer/Memo총시간없음
```

## ml|l2svm|lan|w1|Memo

- 관측분류: INFEASIBLE
- rawCategory: planner_failure
- 종료wall: 2.8060792610049248s
- 완료phase: facts,rules,model,optimization
- [로그](initial56/attempts/ml-l2svm-w1/coordinator.log)

```text
IllegalStateException -- Memo planner did not prove an optimum: INFEASIBLE [shape owner=-1 binding=121 LOOP_CARRIED_SHAPE_MISMATCH Shape[dataType=SCALAR, valueType=INT64, rows=-1, columns=-1, nonZeros=-1, knowledge=EXACT] vs Shape[dataType=SCALAR, valueType=FP64, rows=-1, columns=-1, nonZeros=-1, knowledge=EXACT], MOVEMENT_FACTOR_WIDTH origin=264 width=11]
```

## ml|l2svm|lan|w3|Memo

- 관측분류: INFEASIBLE
- rawCategory: planner_failure
- 종료wall: 2.720000095665455s
- 완료phase: facts,rules,model,optimization
- [로그](initial56/attempts/ml-l2svm-w3/coordinator.log)

```text
IllegalStateException -- Memo planner did not prove an optimum: INFEASIBLE [shape owner=-1 binding=121 LOOP_CARRIED_SHAPE_MISMATCH Shape[dataType=SCALAR, valueType=INT64, rows=-1, columns=-1, nonZeros=-1, knowledge=EXACT] vs Shape[dataType=SCALAR, valueType=FP64, rows=-1, columns=-1, nonZeros=-1, knowledge=EXACT], MOVEMENT_FACTOR_WIDTH origin=276 width=11]
```

## ml|l2svm|lan|w5|Memo

- 관측분류: INFEASIBLE
- rawCategory: planner_failure
- 종료wall: 2.897880494594574s
- 완료phase: facts,rules,model,optimization
- [로그](initial56/attempts/ml-l2svm-w5/coordinator.log)

```text
IllegalStateException -- Memo planner did not prove an optimum: INFEASIBLE [shape owner=-1 binding=121 LOOP_CARRIED_SHAPE_MISMATCH Shape[dataType=SCALAR, valueType=INT64, rows=-1, columns=-1, nonZeros=-1, knowledge=EXACT] vs Shape[dataType=SCALAR, valueType=FP64, rows=-1, columns=-1, nonZeros=-1, knowledge=EXACT], MOVEMENT_FACTOR_WIDTH origin=288 width=11]
```

## ml|l2svm|lan|w7|Memo

- 관측분류: INFEASIBLE
- rawCategory: planner_failure
- 종료wall: 3.0778934583067894s
- 완료phase: facts,rules,model,optimization
- [로그](initial56/attempts/ml-l2svm-w7/coordinator.log)

```text
IllegalStateException -- Memo planner did not prove an optimum: INFEASIBLE [shape owner=-1 binding=121 LOOP_CARRIED_SHAPE_MISMATCH Shape[dataType=SCALAR, valueType=INT64, rows=-1, columns=-1, nonZeros=-1, knowledge=EXACT] vs Shape[dataType=SCALAR, valueType=FP64, rows=-1, columns=-1, nonZeros=-1, knowledge=EXACT], MOVEMENT_FACTOR_WIDTH origin=300 width=11]
```

## ml|steplm|lan|w1|Memo

- 관측분류: UNRESOLVED_COST_CONTEXT
- rawCategory: evidence_validation_failure
- 종료wall: 2.9989390894770622s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-steplm-w1/coordinator.log)

```text
IllegalStateException -- UNRESOLVED_COST_CONTEXT: occurrence 5 frame=[5, 2, 0] phases={LoopDecision[loopId=2, callFrame=[5]]=MIDDLE, LoopDecision[loopId=3, callFrame=[5]]=FIRST} cardinalities={LoopCardinalityDecision[loopId=2, callFrame=[5]]=MANY, LoopCardinalityDecision[loopId=3, callFrame=[5]]=ONE} guards={GuardDecision[guardId=9, callFrame=[5], valueBindingId=490, valueFrame=[5]]=false, GuardDecision[guardId=4, callFrame=[5], valueBindingId=336, valueFrame=[5]]=false, GuardDecision[guardId=8, callFrame=[5], valueBindingId=478, valueFrame=[5]]=true, GuardDecision[guardId=7, callFrame=[5], valueBindingId=411, valueFrame=[5]]=true}
```

## ml|steplm|lan|w3|Memo

- 관측분류: UNRESOLVED_COST_CONTEXT
- rawCategory: evidence_validation_failure
- 종료wall: 2.9212823510169983s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-steplm-w3/coordinator.log)

```text
IllegalStateException -- UNRESOLVED_COST_CONTEXT: occurrence 5 frame=[5, 2, 0] phases={LoopDecision[loopId=2, callFrame=[5]]=MIDDLE, LoopDecision[loopId=3, callFrame=[5]]=FIRST} cardinalities={LoopCardinalityDecision[loopId=2, callFrame=[5]]=MANY, LoopCardinalityDecision[loopId=3, callFrame=[5]]=ONE} guards={GuardDecision[guardId=4, callFrame=[5], valueBindingId=336, valueFrame=[5]]=false, GuardDecision[guardId=9, callFrame=[5], valueBindingId=490, valueFrame=[5]]=false, GuardDecision[guardId=7, callFrame=[5], valueBindingId=411, valueFrame=[5]]=true, GuardDecision[guardId=8, callFrame=[5], valueBindingId=478, valueFrame=[5]]=true}
```

## ml|steplm|lan|w5|Memo

- 관측분류: UNRESOLVED_COST_CONTEXT
- rawCategory: evidence_validation_failure
- 종료wall: 3.0707706063985825s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-steplm-w5/coordinator.log)

```text
IllegalStateException -- UNRESOLVED_COST_CONTEXT: occurrence 5 frame=[5, 2, 0] phases={LoopDecision[loopId=2, callFrame=[5]]=MIDDLE, LoopDecision[loopId=3, callFrame=[5]]=FIRST} cardinalities={LoopCardinalityDecision[loopId=2, callFrame=[5]]=MANY, LoopCardinalityDecision[loopId=3, callFrame=[5]]=ONE} guards={GuardDecision[guardId=7, callFrame=[5], valueBindingId=411, valueFrame=[5]]=true, GuardDecision[guardId=8, callFrame=[5], valueBindingId=478, valueFrame=[5]]=true, GuardDecision[guardId=4, callFrame=[5], valueBindingId=336, valueFrame=[5]]=false, GuardDecision[guardId=9, callFrame=[5], valueBindingId=490, valueFrame=[5]]=false}
```

## ml|steplm|lan|w7|Memo

- 관측분류: UNRESOLVED_COST_CONTEXT
- rawCategory: evidence_validation_failure
- 종료wall: 3.7663173377513885s
- 완료phase: facts,rules
- [로그](initial56/attempts/ml-steplm-w7/coordinator.log)

```text
IllegalStateException -- UNRESOLVED_COST_CONTEXT: occurrence 5 frame=[5, 2, 0] phases={LoopDecision[loopId=3, callFrame=[5]]=FIRST, LoopDecision[loopId=2, callFrame=[5]]=MIDDLE} cardinalities={LoopCardinalityDecision[loopId=3, callFrame=[5]]=ONE, LoopCardinalityDecision[loopId=2, callFrame=[5]]=MANY} guards={GuardDecision[guardId=9, callFrame=[5], valueBindingId=490, valueFrame=[5]]=false, GuardDecision[guardId=7, callFrame=[5], valueBindingId=411, valueFrame=[5]]=true, GuardDecision[guardId=8, callFrame=[5], valueBindingId=478, valueFrame=[5]]=true, GuardDecision[guardId=4, callFrame=[5], valueBindingId=336, valueFrame=[5]]=false}
```

## ml|glm|lan|w1|Memo

- 관측분류: OPTIMIZER_TIMEOUT
- rawCategory: timeout_not_proven
- 종료wall: 300.57923033088446s
- 완료phase: facts,rules,model
- [로그](initial56/attempts/ml-glm-w1/coordinator.log)

```text
coordinator300초timeout; 완료된search/optimizer/Memo총시간없음
```

## ml|glm|lan|w3|Memo

- 관측분류: OPTIMIZER_TIMEOUT
- rawCategory: timeout_not_proven
- 종료wall: 300.52790255099535s
- 완료phase: facts,rules,model
- [로그](initial56/attempts/ml-glm-w3/coordinator.log)

```text
coordinator300초timeout; 완료된search/optimizer/Memo총시간없음
```

## ml|glm|lan|w5|Memo

- 관측분류: OPTIMIZER_TIMEOUT
- rawCategory: timeout_not_proven
- 종료wall: 300.55429469794035s
- 완료phase: facts,rules,model
- [로그](initial56/attempts/ml-glm-w5/coordinator.log)

```text
coordinator300초timeout; 완료된search/optimizer/Memo총시간없음
```

## ml|glm|lan|w7|Memo

- 관측분류: OPTIMIZER_TIMEOUT
- rawCategory: timeout_not_proven
- 종료wall: 300.5415024757385s
- 완료phase: facts,rules,model
- [로그](initial56/attempts/ml-glm-w7/coordinator.log)

```text
coordinator300초timeout; 완료된search/optimizer/Memo총시간없음
```

## ml|gmm|lan|w1|Memo

- 관측분류: UNLOCALIZED_NULL_POINTER
- rawCategory: evidence_validation_failure
- 종료wall: 1.9971819445490837s
- 완료phase: 
- [로그](initial56/attempts/ml-gmm-w1/coordinator.log)

```text
NullPointerException -- Cannot invoke "java.lang.Double.doubleValue()"
```

## ml|gmm|lan|w3|Memo

- 관측분류: UNLOCALIZED_NULL_POINTER
- rawCategory: evidence_validation_failure
- 종료wall: 2.336933448910713s
- 완료phase: 
- [로그](initial56/attempts/ml-gmm-w3/coordinator.log)

```text
NullPointerException -- Cannot invoke "java.lang.Double.doubleValue()"
```

## ml|gmm|lan|w5|Memo

- 관측분류: UNLOCALIZED_NULL_POINTER
- rawCategory: evidence_validation_failure
- 종료wall: 2.1440023854374886s
- 완료phase: 
- [로그](initial56/attempts/ml-gmm-w5/coordinator.log)

```text
NullPointerException -- Cannot invoke "java.lang.Double.doubleValue()"
```

## ml|gmm|lan|w7|Memo

- 관측분류: UNLOCALIZED_NULL_POINTER
- rawCategory: evidence_validation_failure
- 종료wall: 2.472548946738243s
- 완료phase: 
- [로그](initial56/attempts/ml-gmm-w7/coordinator.log)

```text
NullPointerException -- Cannot invoke "java.lang.Double.doubleValue()"
```

## p1|P1_FULL|lan|w1|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.1627928614616394s
- 완료phase: 
- [로그](dependency12/attempts/p1-P1_FULL-w1/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=54]]
```

## p1|P1_FULL|lan|w3|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.5587354078888893s
- 완료phase: 
- [로그](dependency12/attempts/p1-P1_FULL-w3/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=54]]
```

## p1|P1_FULL|lan|w5|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.488534078001976s
- 완료phase: 
- [로그](dependency12/attempts/p1-P1_FULL-w5/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=54]]
```

## p1|P1_FULL|lan|w7|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.24262535572052s
- 완료phase: 
- [로그](dependency12/attempts/p1-P1_FULL-w7/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=54]]
```

## p2|P2_PREP|lan|w1|Memo

- 관측분류: UNKNOWN_SIZE
- rawCategory: evidence_validation_failure
- 종료wall: 2.1198412403464317s
- 완료phase: facts,rules
- [로그](initial56/attempts/p2-P2_PREP-w1/coordinator.log)

```text
CostException -- UNKNOWN_SIZE: unknown kernel input 0 for hop 5236 UnaryOp/u(nrow) SCALAR 0x0: hop 5237 DataOp/TRead X MATRIX -1x-1
```

## p2|P2_PREP|lan|w3|Memo

- 관측분류: CANONICAL_INGRESS_ERROR
- rawCategory: evidence_validation_failure
- 종료wall: 1.8986019268631935s
- 완료phase: 
- [로그](initial56/attempts/p2-P2_PREP-w3/coordinator.log)

```text
DMLRuntimeException -- Federated value violates canonical ingress at worker index 1.
```

## p2|P2_PREP|lan|w5|Memo

- 관측분류: UNKNOWN_SIZE
- rawCategory: evidence_validation_failure
- 종료wall: 2.044003874063492s
- 완료phase: facts,rules
- [로그](initial56/attempts/p2-P2_PREP-w5/coordinator.log)

```text
CostException -- UNKNOWN_SIZE: unknown kernel input 0 for hop 6026 UnaryOp/u(nrow) SCALAR 0x0: hop 6027 DataOp/TRead X MATRIX -1x-1
```

## p2|P2_PREP|lan|w7|Memo

- 관측분류: CANONICAL_INGRESS_ERROR
- rawCategory: evidence_validation_failure
- 종료wall: 2.0506730377674103s
- 완료phase: 
- [로그](initial56/attempts/p2-P2_PREP-w7/coordinator.log)

```text
DMLRuntimeException -- Federated value violates canonical ingress at worker index 5.
```

## sliceline|ADULT|lan|w1|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.198424354195595s
- 완료phase: 
- [로그](dependency12/attempts/sliceline-ADULT-w1/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=8]]
```

## sliceline|ADULT|lan|w3|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.188357450067997s
- 완료phase: 
- [로그](dependency12/attempts/sliceline-ADULT-w3/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=8]]
```

## sliceline|ADULT|lan|w5|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.280199520289898s
- 완료phase: 
- [로그](dependency12/attempts/sliceline-ADULT-w5/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=8]]
```

## sliceline|ADULT|lan|w7|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.0187913402915s
- 완료phase: 
- [로그](dependency12/attempts/sliceline-ADULT-w7/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=8]]
```

## sliceline|COVTYPE|lan|w1|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.258522853255272s
- 완료phase: 
- [로그](dependency12/attempts/sliceline-COVTYPE-w1/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=8]]
```

## sliceline|COVTYPE|lan|w3|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.5682389810681343s
- 완료phase: 
- [로그](dependency12/attempts/sliceline-COVTYPE-w3/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=8]]
```

## sliceline|COVTYPE|lan|w5|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.516119174659252s
- 완료phase: 
- [로그](dependency12/attempts/sliceline-COVTYPE-w5/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=8]]
```

## sliceline|COVTYPE|lan|w7|Memo

- 관측분류: UNSUPPORTED_PARFOR
- rawCategory: evidence_validation_failure
- 종료wall: 2.4299979731440544s
- 완료phase: 
- [로그](dependency12/attempts/sliceline-COVTYPE-w7/coordinator.log)

```text
IllegalStateException -- Memo program facts are UNSUPPORTED: [Diagnostic[status=UNSUPPORTED, code=UNSUPPORTED_PARFOR, message=Memo planning supports sequential loops only, regionId=8]]
```

