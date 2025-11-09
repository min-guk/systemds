package org.apache.sysds.hops.fedplanner.ftype.handlers.ophandlers;

import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.*;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.ftype.handlers.FederatedTypeHandler;

/**
 * Handler for AggUnaryOp operations (aggregate unary operations)
 */
public class AggUnaryOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) {
        return hop instanceof AggUnaryOp;
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        AggUnaryOp auop = (AggUnaryOp) hop;
        AggOp aggOp = auop.getOp();

        // [0] UAggOuterChain 패턴 차단 (FEDInstructionParser에 없음)
        // outer product 후 aggregation 패턴은 federated에서 미지원
        if (isUaggOuterChainPattern(auop)) {
            return HandlerResult.unsupported("AggUnaryOp: UAggOuterChain pattern not supported in federated execution");
        }

        // [1] 지원 aggOp 먼저 필터링
        if (!(aggOp == AggOp.SUM || aggOp == AggOp.MIN || aggOp == AggOp.MAX ||
              aggOp == AggOp.SUM_SQ || aggOp == AggOp.MEAN || aggOp == AggOp.VAR ||
              aggOp == AggOp.MAXINDEX || aggOp == AggOp.MININDEX)) {
            return HandlerResult.unsupported("AggUnaryOp: " + aggOp + " not supported");
        }

        // [2] null/empty 입력 특례
        if (inputTypes.length == 0 || (inputTypes.length > 0 && inputTypes[0] == null)) {
            return HandlerResult.supported(FType.BROADCAST,
                "AggUnaryOp: " + aggOp + " with null/empty input → BROADCAST");
        }

        if (!hasFederatedFirstInput(inputTypes)) {
            return HandlerResult.unsupported("AggUnaryOp: Requires federated first input");
        }

        FType firstFType = inputTypes[0];

        // [3] BROADCAST 입력 차단 (중복 집계 위험)
        if (firstFType == FType.BROADCAST) {
            return HandlerResult.unsupported(
                "AggUnaryOp: BROADCAST input not supported - would cause duplicate aggregation");
        }

        // ===== [추가] +RC(전체 집계) → 항상 BROADCAST =====
        // 프로젝트별 enum 명칭 차이를 고려해 name() 비교 사용
        final String dirName = auop.getDirection().name();
        final boolean isFullAggRC = "ROW_COL".equalsIgnoreCase(dirName) || "RowCol".equalsIgnoreCase(dirName);
        if (isFullAggRC) {
            return HandlerResult.supported(FType.BROADCAST,
                "AggUnaryOp: Full aggregation (+RC) produces global result → BROADCAST");
        }
        // ===================================================

        boolean isColAgg = auop.getDirection().isCol();

        // [4] Full aggregation 패턴 (상보 축) → BROADCAST
        if ((firstFType == FType.ROW && isColAgg) ||
            (firstFType == FType.COL && !isColAgg)) {

            if (aggOp == AggOp.SUM || aggOp == AggOp.SUM_SQ ||
                aggOp == AggOp.MIN || aggOp == AggOp.MAX || aggOp == AggOp.MEAN) {
                return HandlerResult.supported(FType.BROADCAST,
                    "AggUnaryOp: Federated aggregation with broadcast result");
            }
            return HandlerResult.unsupported("AggUnaryOp: Full aggregation produces scalar result");
        }

        // [5] Partial aggregation → 구조 유지
        if (firstFType == FType.ROW || firstFType == FType.COL) {
            return HandlerResult.supported(firstFType,
                "AggUnaryOp: Partial aggregation maintains structure");
        }

        return HandlerResult.unsupported("AggUnaryOp: Unsupported pattern");
    }

    /**
     * UAggOuterChain 패턴 감지: outer product 후 aggregation
     * 조건: input이 BinaryOp이고 isOuter()이며,
     *      aggOp이 MAXINDEX/MININDEX/SUM이고,
     *      BinaryOp의 연산자가 비교 연산자
     */
    private boolean isUaggOuterChainPattern(AggUnaryOp auop) {
        Hop input = auop.getInput().get(0);
        AggOp aggOp = auop.getOp();

        if (!(input instanceof BinaryOp)) {
            return false;
        }

        BinaryOp binOp = (BinaryOp) input;

        // UAggOuterChain 생성 조건 (AggUnaryOp.java:560-563 참조)
        boolean isOuterProduct = binOp.isOuter();
        boolean isSupportedAgg = (aggOp == AggOp.MAXINDEX || aggOp == AggOp.MININDEX || aggOp == AggOp.SUM);
        boolean isCompareOp = isCompareOperator(binOp.getOp());

        return isOuterProduct && isSupportedAgg && isCompareOp;
    }

    /**
     * 비교 연산자 체크
     */
    private boolean isCompareOperator(Types.OpOp2 op) {
        return op == Types.OpOp2.EQUAL || op == Types.OpOp2.NOTEQUAL ||
               op == Types.OpOp2.LESS || op == Types.OpOp2.LESSEQUAL ||
               op == Types.OpOp2.GREATER || op == Types.OpOp2.GREATEREQUAL;
    }
}
