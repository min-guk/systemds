package org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.ophandlers;

import org.apache.sysds.common.Types.*;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.HandlerResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.FederatedTypeHandler;

/**
 * Handler for UnaryOp operations (element-wise unary operations)
 */
public class UnaryOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) {
        return hop instanceof UnaryOp;
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        UnaryOp uop = (UnaryOp) hop;
        OpOp1 op = uop.getOp();

        // ---- ① Unsupported opcodes in FED execution ----
        // BROADCAST is not supported because there's no BroadcastFEDInstruction
        // and InstructionType.Broadcast is not in FEDInstructionParser (line 58-90)
        if (op == OpOp1.BROADCAST) {
            return HandlerResult.unsupported("UnaryOp: BROADCAST opcode not supported in federated execution (no BroadcastFEDInstruction)");
        }

        // LOG_NZ is not supported because InstructionType.Builtin is not in FEDInstructionParser
        if (op == OpOp1.LOG_NZ) {
            return HandlerResult.unsupported("UnaryOp: LOG_NZ opcode not supported in federated execution");
        }

        // COMPRESS and DECOMPRESS are not supported in federated execution
        // - FEDInstructionParser.java has no case for Compression/DeCompression InstructionType
        // - No CompressionFEDInstruction or DeCompressionFEDInstruction classes exist
        // - Although Compression.java (Lop) allows ExecType.FED, runtime instruction parsing will fail
        if (op == OpOp1.COMPRESS || op == OpOp1.DECOMPRESS) {
            return HandlerResult.unsupported("UnaryOp: " + op + " not supported in federated execution (no FED instruction implementation)");
        }

        // TRIGREMOTE is not supported in federated execution
        // - InstructionType.TrigRemote is defined (InstructionType.java:58) but completely unused
        // - FEDInstructionParser.java has no case for TrigRemote (line 58-90)
        // - CPInstructionParser.java also has no case for TrigRemote (line 89-228)
        // - This is a legacy/placeholder InstructionType with no actual implementation
        if (op == OpOp1.TRIGREMOTE) {
            return HandlerResult.unsupported("UnaryOp: TRIGREMOTE not supported in federated execution (InstructionType.TrigRemote has no parser implementation)");
        }

        // ---- ② CP-only / 금지 / 사이드이펙트 연산: 최우선 차단 ----
        // 항상 federated 의미의 출력 없음 → unsupported(null)
        if (isCpOnlyOrSideEffect(op)) {
            return HandlerResult.unsupported("UnaryOp: " + op + " is CP-only / side-effect");
        }

        // ---- ② 메타데이터/스칼라 결과 연산: 전역 상수 → BROADCAST ----
        // nrow/ncol/typeof 등 '스칼라'를 반환하는 조회형 연산은 항상 BROADCAST
        if (isScalarMetadataOp(op) || (isScalarOutput(hop) && op != OpOp1.CAST_AS_SCALAR)) {
            return HandlerResult.supported(FType.BROADCAST,
                "UnaryOp: scalar metadata/result → BROADCAST");
        }

        // ---- ③ 원소별(Element-wise) 연산: LOCAL-only / 무입력 → BROADCAST bridge ----
        boolean allLocalOrNoInput = (inputTypes.length == 0)
            || java.util.Arrays.stream(inputTypes).allMatch(t -> t == null);
        if (allLocalOrNoInput) {
            return HandlerResult.supported(FType.BROADCAST,
                "UnaryOp: LOCAL-only input → BROADCAST bridge");
        }

        // 단항에서 '첫 입력은 federated 여야 함' 보장
        if (!hasFederatedFirstInput(inputTypes)) {
            return HandlerResult.unsupported("UnaryOp: Requires federated first input");
        }

        // ---- ④ 원소별(Element-wise) 파티션 유지 ----
        // castdts/castdtf/castdtm/castvti는 '스칼라 캐스트'가 아니라 element-wise 캐스트로 취급
        // → ROW/COL/BROADCAST 입력 구조 유지
        FType in = inputTypes[0];
        if (in == FType.BROADCAST) {
            return HandlerResult.supported(FType.BROADCAST,
                "UnaryOp: element-wise on BROADCAST maintains BROADCAST");
        }
        return HandlerResult.supported(in,
            "UnaryOp: element-wise maintains partition (" + in + ")");
    }

    // ---- Helpers ----
    /**
     * Checks if opcode is CP-only or has side effects.
     *
     * Categories:
     * - Side-effect ops (coordinator only): PRINT, ASSERT, STOP
     * - GPU cache management (coordinator only): _EVICT (EvictLineageCache - InstructionType not in FEDInstructionParser)
     * - LibCommonsMath ops (not regular FED): INVERSE, CHOLESKY, DET, SQRT_MATRIX_JAVA
     * - Metadata ops (coordinator only): DETECTSCHEMA, COLNAMES
     *
     * TODO: EIGEN, SVD are included but actually MultiReturnBuiltin, not Unary - need to verify if they should be here
     */
    private boolean isCpOnlyOrSideEffect(OpOp1 op) {
        return op == OpOp1.PRINT || op == OpOp1.ASSERT || op == OpOp1.STOP           // side-effect
            || op == OpOp1._EVICT                                                    // GPU cache eviction
            || op == OpOp1.INVERSE || op == OpOp1.EIGEN || op == OpOp1.CHOLESKY      // LibCommonsMath
            || op == OpOp1.DET || op == OpOp1.SVD || op == OpOp1.SQRT_MATRIX_JAVA    // LibCommonsMath
            || op == OpOp1.DETECTSCHEMA || op == OpOp1.COLNAMES;                     // metadata
    }

    private boolean isScalarMetadataOp(OpOp1 op) {
        // 스칼라 메타데이터 반환 연산(예: NROW/NCOL/TYPEOF 등)을 환경에 맞게 추가
        return op == OpOp1.NROW
            || op == OpOp1.NCOL
            || op == OpOp1.TYPEOF;
    }
}
