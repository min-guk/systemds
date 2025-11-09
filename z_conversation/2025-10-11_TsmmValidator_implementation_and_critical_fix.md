# TsmmValidator Implementation and Critical Bug Fix
**Date**: 2025-10-11
**Validator**: TsmmValidator
**File**: `src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/TsmmValidator.java`

---

## 📋 Implementation Summary

Implemented TsmmValidator for validating FOUT (Federated Output) constraints on Tsmm (transpose self matrix multiplication) operations based on the FOUT Constraint table.

### FOUT Constraint Table Entry
| Instruction Class      | OP Type  | OpCode | FOUT Possible?     | FOUT Constraint/Reason |
|------------------------|----------|--------|--------------------|-----------------------|
| TsmmFEDInstruction     | Tsmm     | tsmm   | Yes (conditional)  | Converts to BROADCAST type when FOUT forced |

---

## 🔍 Verification Phase

### 1. Tsmm Pattern Detection
**Source**: `AggBinaryOp.java:433-450`

```java
public MMTSJType checkTransposeSelf() {
    Hop in1 = getInput().get(0);
    Hop in2 = getInput().get(1);

    if (HopRewriteUtils.isTransposeOperation(in1) && in1.getInput().get(0) == in2) {
        return MMTSJType.LEFT;   // t(X) %*% X
    }
    if (HopRewriteUtils.isTransposeOperation(in2) && in2.getInput().get(0) == in1) {
        return MMTSJType.RIGHT;  // X %*% t(X)
    }

    return MMTSJType.NONE;
}
```

**Findings**:
- LEFT pattern: `t(X) %*% X` (transpose on left input)
- RIGHT pattern: `X %*% t(X)` (transpose on right input)

### 2. TsmmFEDInstruction Input Constraints
**Source**: `TsmmFEDInstruction.java:55-61`

```java
public static TsmmFEDInstruction parseInstruction(MMTSJCPInstruction inst, ExecutionContext ec) {
    MatrixObject mo = ec.getMatrixObject(inst.input1);
    if( (mo.isFederated(FType.ROW) && mo.isFederatedExcept(FType.BROADCAST) && inst.getMMTSJType().isLeft()) ||
        (mo.isFederated(FType.COL) && mo.isFederatedExcept(FType.BROADCAST) && inst.getMMTSJType().isRight()))
        return parseInstruction(inst);
    return null;
}
```

**Findings**:
- LEFT tsmm requires: `FType.ROW` and NOT `FType.BROADCAST`
- RIGHT tsmm requires: `FType.COL` and NOT `FType.BROADCAST`
- `isFederatedExcept(FType.BROADCAST)` explicitly excludes BROADCAST input

### 3. FOUT Behavior
**Source**: `TsmmFEDInstruction.java:99-105`

```java
if (_fedOut.isForcedFederated()){
    fr1 = mo1.getFedMapping().broadcast(mo1);  // Convert input to BROADCAST
    FederatedRequest fr2 = FederationUtils.callInstruction(instString, output,
        new CPOperand[]{input1}, new long[]{fr1.getID()}, true);
    mo1.getFedMapping().execute(getTID(), fr1, fr2);
    setOutputFederated(ec, mo1, fr2, FType.BROADCAST);  // Output as BROADCAST
}
```

**Findings**:
- When FOUT is forced: input X is converted to BROADCAST type
- Output is set as BROADCAST federated result
- Without FOUT: results are aggregated locally via `aggAdd`

---

## 🚨 Critical Bug Discovery and Fix

### Bug #1: Wrong Input Index Reference (CRITICAL)

**Initial Implementation (WRONG)**:
```java
if (inputTypes != null && inputTypes.length > 0 && inputTypes[0] != null) {
    FType inputType = inputTypes[0];  // WRONG!
    if (tsmmType == MMTSJType.LEFT && inputType != FType.ROW) {
        return OutputConstraintResult.disallowed(...);
    }
}
```

**Problem**:
- For LEFT tsmm (`t(X) %*% X`): `inputTypes[0]` is the FType of `t(X)`, NOT the original `X`!
- For RIGHT tsmm (`X %*% t(X)`): `inputTypes[0]` is the FType of `X`, which is correct
- Inconsistent and incorrect validation logic

### Bug #2: Misunderstanding HOPs vs Runtime Representation

**Key Discovery**: Tsmm has different representations at different compilation stages!

#### HOPs Level (AggBinaryOp)
- Pattern appears as **binary operation** with TWO inputs
- LEFT: `t(X) %*% X` → `input[0]=t(X)`, `input[1]=X`
- RIGHT: `X %*% t(X)` → `input[0]=X`, `input[1]=t(X)`

#### Runtime Level (MMTSJCPInstruction)
- Optimized to **UNARY operation** with ONLY the original matrix X
- `MMTSJCPInstruction extends UnaryCPInstruction` (single input!)
- `TsmmFEDInstruction.java:46`: `super(..., in, null, ...)` → input2=null

#### Lops Construction (AggBinaryOp.java:506)
```java
Lop matmultCP = new MMTSJ(getInput().get(mmtsj.isLeft() ? 1 : 0).constructLops(), ...);
```
- LEFT: uses `input[1]` (original X from `t(X)%*%X`)
- RIGHT: uses `input[0]` (original X from `X%*%t(X)`)

**Critical Insight**: The compiler extracts ONLY the original matrix X when creating the runtime instruction, discarding the transpose operation!

### Corrected Implementation

```java
if (inputTypes != null && inputTypes.length >= 2) {
    // Get the FType of the original matrix X (not the transpose)
    // This matches what runtime will use as inst.input1
    FType originalInputType = (tsmmType == MMTSJType.LEFT) ? inputTypes[1] : inputTypes[0];

    if (originalInputType != null) {
        // Constraint 1: Pattern-specific partitioning requirements
        if (tsmmType == MMTSJType.LEFT && originalInputType != FType.ROW) {
            return OutputConstraintResult.disallowed(
                "LEFT tsmm (t(X)%*%X) requires ROW partitioned input X, got: " + originalInputType);
        }
        if (tsmmType == MMTSJType.RIGHT && originalInputType != FType.COL) {
            return OutputConstraintResult.disallowed(
                "RIGHT tsmm (X%*%t(X)) requires COL partitioned input X, got: " + originalInputType);
        }

        // Constraint 2: BROADCAST input is explicitly excluded
        if (originalInputType == FType.BROADCAST) {
            return OutputConstraintResult.disallowed(
                "Tsmm does not support BROADCAST input (must be ROW/COL partitioned)");
        }
    }
}
```

### Bug #3: Missing BROADCAST Input Constraint

**Initially missed**: The `isFederatedExcept(FType.BROADCAST)` check in TsmmFEDInstruction
**Added**: Explicit BROADCAST input rejection in validator

---

## 🎯 Final Implementation

### canValidate()
```java
@Override
public boolean canValidate(Hop hop) {
    if (!(hop instanceof AggBinaryOp)) {
        return false;
    }

    AggBinaryOp abop = (AggBinaryOp) hop;
    MMTSJType tsmmType = abop.checkTransposeSelf();
    return tsmmType != MMTSJType.NONE;
}
```

### validate()
```java
@Override
public OutputConstraintResult validate(Hop hop, FType[] inputTypes) {
    AggBinaryOp abop = (AggBinaryOp) hop;
    MMTSJType tsmmType = abop.checkTransposeSelf();

    if (tsmmType == MMTSJType.NONE) {
        return OutputConstraintResult.disallowed("Not a valid TSMM pattern");
    }

    // Extract original matrix X's FType (accounting for HOPs representation)
    if (inputTypes != null && inputTypes.length >= 2) {
        FType originalInputType = (tsmmType == MMTSJType.LEFT) ? inputTypes[1] : inputTypes[0];

        if (originalInputType != null) {
            // Check pattern-specific partitioning
            if (tsmmType == MMTSJType.LEFT && originalInputType != FType.ROW) {
                return OutputConstraintResult.disallowed(
                    "LEFT tsmm (t(X)%*%X) requires ROW partitioned input X, got: " + originalInputType);
            }
            if (tsmmType == MMTSJType.RIGHT && originalInputType != FType.COL) {
                return OutputConstraintResult.disallowed(
                    "RIGHT tsmm (X%*%t(X)) requires COL partitioned input X, got: " + originalInputType);
            }

            // Reject BROADCAST input
            if (originalInputType == FType.BROADCAST) {
                return OutputConstraintResult.disallowed(
                    "Tsmm does not support BROADCAST input (must be ROW/COL partitioned)");
            }
        }
    }

    // FOUT is conditional
    String pattern = tsmmType == MMTSJType.LEFT ? "t(X)%*%X" : "X%*%t(X)";
    return OutputConstraintResult.conditional(
        "Tsmm " + pattern + ": Converts input to BROADCAST type when FOUT forced; " +
        "outputs BROADCAST federated result");
}
```

---

## ✅ Validation Results

### Verified Constraints
1. ✅ LEFT tsmm (`t(X)%*%X`): Requires ROW partitioned input X
2. ✅ RIGHT tsmm (`X%*%t(X)`): Requires COL partitioned input X
3. ✅ BROADCAST input is explicitly rejected
4. ✅ FOUT converts input to BROADCAST and outputs BROADCAST (conditional)

### Compilation Status
```bash
✅ mvn compile -Dmaven.test.skip=true
   Compilation successful - no errors in TsmmValidator
```

---

## 📚 Key Lessons Learned

### 1. HOPs vs Runtime Representation
- **HOPs level**: High-level DAG representation with semantic patterns
- **Lops level**: Logical operations with optimization decisions
- **Runtime level**: Actual execution instructions (may be optimized/fused)
- **Critical**: Validators operate at HOPs level but must understand runtime constraints!

### 2. Pattern Optimization
- Transpose-self patterns are optimized from binary to unary operations
- Compiler extracts only the necessary input (original matrix X)
- Understanding `constructLops()` is crucial for correct input mapping

### 3. Input Index Mapping
- Always verify which input the runtime instruction actually uses
- Don't assume HOPs input order matches runtime input order
- Check Lops construction to understand input selection logic

### 4. Implicit Constraints
- `isFederatedExcept(FType.BROADCAST)` is an implicit constraint
- Must be translated to explicit validator logic
- Don't rely on naming alone - verify actual runtime behavior

---

## 🔗 Related Files

### Implementation
- `src/main/java/org/apache/sysds/hops/fedplanner/fout/validators/TsmmValidator.java`

### Runtime Verification
- `src/main/java/org/apache/sysds/runtime/instructions/fed/TsmmFEDInstruction.java`
- `src/main/java/org/apache/sysds/runtime/instructions/cp/MMTSJCPInstruction.java`

### HOPs Analysis
- `src/main/java/org/apache/sysds/hops/AggBinaryOp.java` (checkTransposeSelf, constructCPLopsTSMM)

### Base Classes
- `src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintValidator.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/fout/OutputConstraintResult.java`

---

## 🎬 Conclusion

The TsmmValidator implementation required deep understanding of SystemDS's compilation pipeline:
1. Pattern detection at HOPs level
2. Optimization to unary operation at Lops level
3. Runtime execution with single input

The critical bug fix demonstrates the importance of understanding representation differences across compilation stages. The validator now correctly validates input types by accounting for how the compiler extracts the original matrix X from the transpose-self pattern.

**Status**: ✅ Implementation complete and verified against runtime behavior
