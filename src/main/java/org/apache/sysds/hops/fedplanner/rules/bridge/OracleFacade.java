package org.apache.sysds.hops.fedplanner.rules.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.codegen.SpoofFusedOp;
import org.apache.sysds.hops.fedplanner.FTypes;
import org.apache.sysds.hops.fedplanner.rules.RulesApi;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Rule;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.RuleRegistry;
import org.apache.sysds.lops.MapMultChain.ChainType;
import org.apache.sysds.lops.MMTSJ.MMTSJType;
import org.apache.sysds.runtime.codegen.CodegenUtils;
import org.apache.sysds.runtime.codegen.SpoofCellwise;
import org.apache.sysds.runtime.codegen.SpoofMultiAggregate;
import org.apache.sysds.runtime.codegen.SpoofOperator;
import org.apache.sysds.runtime.codegen.SpoofOuterProduct;
import org.apache.sysds.runtime.codegen.SpoofRowwise;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.common.Types.OpOpDG;
import org.apache.sysds.parser.DataExpression;

/**
 * Bridge that exposes the rule oracle via canonicalized {@link OpSig} inputs.
 */
public final class OracleFacade {
	public record NodeShape(DataType dataType, long rows, long cols) { }

	/** Canonical rules-boundary capture of immutable compiled-Hop shape metadata. */
	public static NodeShape nodeShape(Hop hop) {
		Objects.requireNonNull(hop, "hop");
		return new NodeShape(hop.getDataType(), hop.getDim1(), hop.getDim2());
	}
	public record DecisionEvidence(RulesApi.OpCaps caps, RulesApi.ShapeProof shapeProof) {
		public boolean shapeDependent() { return !shapeProof.requiredFacts().isEmpty(); }
	}
  private static final String ATTR_DIRECTION = "direction";
  private static final String ATTR_AGG_OP = "aggOp";
  private static final String ATTR_Q_TYPE = "q.type";
  private static final String ATTR_WDIVMM_BASE_TYPE = "wdivmm.baseType";
  private static final String ATTR_MAP_MARGIN = "map.margin";
  private static final String ATTR_BYROW = "reshape.byrow";
  private static final String ATTR_VAR_WRITE_FED = "var.write.federated";
  private static final String ATTR_VAR_READ_FED = "var.read.federated";
  private static final String ATTR_VAR_READ_FTYPE = "var.read.ftype";
  private static final String ATTR_INNER = "inner";
  private static final String ATTR_OUTER = "outer";
  private static final String ATTR_ALIGN = "align";
  private static final String ATTR_R_IS_VECTOR = "r_is_vector";
  private static final String ATTR_MMCHAIN_TYPE = "mmchain.type";
  private static final String ATTR_MMCHAIN_WEIGHTED = "mmchain.weighted";
  private static final String ATTR_ALIGNED_W = "alignedW";
  private static final String ATTR_TSMM_TYPE = "tsmm.type";
  private static final String ATTR_TSMM_FED_OUT = "tsmm.fedOut";
  private static final String ATTR_CTABLE_DISJOINT = "ctable_disjoint_bins";
  private static final String ATTR_CBIND = "cbind";
  private static final String ATTR_SPOOF_TEMPLATE = "spoof.template";
  private static final String ATTR_SPOOF_CELL_TYPE = "spoof.cellType";
  private static final String ATTR_SPOOF_ROW_TYPE = "spoof.rowType";
  private static final String ATTR_SPOOF_OUTER_TYPE = "spoof.outer.type";
  private static final String ATTR_GUARD_DEFAULT = "rc.guardDefaultIfUnknown";
  private static final String ATTR_FCALL_NAMESPACE = "fcall.namespace";
  private static final String ATTR_FCALL_NAME = "fcall.name";
  private static final String ATTR_FCALL_TYPE = "fcall.type";
  private static final String ATTR_FUNOUT_FCALL_TYPE = "funout.fcall.type";
  private static final String ALIGN_COL_T = "COL_T";

  private final RuleRegistry registry;
  private final RulesCore.OracleEngine oracle;
  private final RulesCore.InferenceEngine inference;

  public OracleFacade(RuleRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.oracle = new RulesCore.OracleEngine(registry);
    this.inference = new RulesCore.InferenceEngine(registry);
  }

  public RulesApi.OpCaps decide(Hop hop, List<FTypes.FType> inFTypes) {
    return decide(hop, inFTypes, buildShapeHint(hop, inFTypes));
  }

  public RulesApi.OpCaps decide(Hop hop, List<FTypes.FType> inFTypes, ShapeHint hint) {
	return decideWithEvidence(hop, inFTypes, hint).caps();
  }

	public DecisionEvidence decideWithEvidence(Hop hop, List<FTypes.FType> inFTypes, ShapeHint hint) {
    Objects.requireNonNull(hop, "hop");
    OpSig sig = buildSignature(hop);
    List<FType> mapped = mapFederatedTypes(hop, inFTypes);
    ShapeHint effectiveHint = (hint != null)
        ? mergeFullSinglePartitionHint(hint, hop, inFTypes)
        : buildShapeHint(hop, inFTypes);
    logOracleInvocation(hop, sig, mapped, effectiveHint, "begin");
    RulesApi.OpCaps caps = oracle.decide(sig, mapped, effectiveHint);
    logOracleResult(hop, caps);
	return new DecisionEvidence(caps, effectiveHint.proof());
  }

  public List<RulesApi.OpCaps> exploreAll(
      Hop hop, List<Set<FTypes.FType>> candidates, ShapeHint hint) {
    Objects.requireNonNull(hop, "hop");
    List<List<FTypes.FType>> combos = enumerateCandidates(candidates);
    List<RulesApi.OpCaps> results = new ArrayList<>(combos.size());
    for (List<FTypes.FType> combo : combos) {
      results.add(decide(hop, combo, hint));
    }
    return results;
  }

  public RulesApi.FTypeProfile inferProfile(
      Hop hop, List<List<FType>> inCandidates, ShapeHint hint) {
    Objects.requireNonNull(hop, "hop");
    OpSig sig = buildSignature(hop);
    ShapeHint effectiveHint = (hint != null) ? hint : buildShapeHint(hop, null);
    return inference.infer(sig, inCandidates, effectiveHint);
  }

  OpSig describe(Hop hop) {
    Objects.requireNonNull(hop, "hop");
    return buildSignature(hop);
  }

  private OpSig buildSignature(Hop hop) {
    String opcode = CanonicalOpcode.from(hop);
    OpCategory category = resolveCategory(hop, opcode);
    Map<String,String> attrs = new LinkedHashMap<>();
    attrs.put(ATTR_GUARD_DEFAULT, "allow");
    enrichAttributes(hop, attrs);
    List<InputKind> kinds = inputKinds(hop);
    return new OpSig(opcode, category, attrs, kinds);
  }

  private OpCategory resolveCategory(Hop hop, String opcode) {
    Optional<Rule> direct = registry.byOpcode(opcode);
    if (direct.isPresent())
      return direct.get().category();

    if (hop instanceof AggUnaryOp)
      return OpCategory.AGG_UNARY;
    if (hop instanceof QuaternaryOp)
      return OpCategory.QUATERNARY;
    if (hop instanceof SpoofFusedOp)
      return OpCategory.SPOOF;
    if (hop instanceof ReorgOp)
      return OpCategory.REORG;
    if (hop instanceof AggBinaryOp && ((AggBinaryOp) hop).isMatrixMultiply())
      return OpCategory.BINARY_MM;
    if (hop instanceof AggBinaryOp)
      return OpCategory.BINARY_EWISE;
    if (hop instanceof BinaryOp || hop instanceof NaryOp)
      return OpCategory.BINARY_EWISE;
    if (hop instanceof TernaryOp)
      return OpCategory.OTHER;
    if (hop instanceof UnaryOp)
      return OpCategory.OTHER;
    return OpCategory.OTHER;
  }

  private void enrichAttributes(Hop hop, Map<String,String> attrs) {
    if (hop instanceof AggUnaryOp)
      addAggUnaryAttrs((AggUnaryOp) hop, attrs);
    if (hop instanceof AggBinaryOp)
      addAggBinaryAttrs((AggBinaryOp) hop, attrs);
    if (hop instanceof FunctionOp)
      addFunctionAttrs((FunctionOp) hop, attrs);
    if (hop instanceof QuaternaryOp)
      addQuaternaryAttrs((QuaternaryOp) hop, attrs);
    if (hop instanceof TernaryOp)
      addTernaryAttrs((TernaryOp) hop, attrs);
    if (hop instanceof ReorgOp)
      addReorgAttrs((ReorgOp) hop, attrs);
    if (hop instanceof DataOp)
      addDataOpAttrs((DataOp) hop, attrs);
    if (hop instanceof ParameterizedBuiltinOp)
      addParamBuiltinAttrs((ParameterizedBuiltinOp) hop, attrs);
    if (hop instanceof SpoofFusedOp)
      addSpoofAttrs((SpoofFusedOp) hop, attrs);
    if (isAppend(hop))
      attrs.put(ATTR_CBIND, Boolean.toString(isCbind(hop)));
  }

  private void addParamBuiltinAttrs(ParameterizedBuiltinOp hop, Map<String,String> attrs) {
    if (hop == null || attrs == null)
      return;
    if (hop.getOp() != ParamBuiltinOp.REXPAND)
      return;

    Hop dirHop = hop.getParameterHop("dir");
    if (dirHop instanceof LiteralOp) {
      String dir = ((LiteralOp) dirHop).getStringValue();
      if (dir != null && !dir.isBlank())
        attrs.put("rexpand.dir", dir);
    }
  }

  private void addAggUnaryAttrs(AggUnaryOp hop, Map<String,String> attrs) {
    Direction dir = hop.getDirection();
    String dirToken;
    switch (dir) {
      case Row:
        dirToken = "ROW";
        break;
      case Col:
        dirToken = "COL";
        break;
      case RowCol:
      default:
        dirToken = "ROWCOL";
        break;
    }
    attrs.put(ATTR_DIRECTION, dirToken);
    attrs.put(ATTR_AGG_OP, hop.getOp().name());
  }

  private void addFunctionAttrs(FunctionOp hop, Map<String,String> attrs) {
    if (hop == null)
      return;
    if (hop.getFunctionNamespace() != null)
      attrs.put(ATTR_FCALL_NAMESPACE, hop.getFunctionNamespace());
    if (hop.getFunctionName() != null)
      attrs.put(ATTR_FCALL_NAME, hop.getFunctionName());
    FunctionOp.FunctionType type = hop.getFunctionType();
    if (type != null)
      attrs.put(ATTR_FCALL_TYPE, type.name());
  }

  private void addAggBinaryAttrs(AggBinaryOp hop, Map<String,String> attrs) {
    if (hop.getInnerOp() != null)
      attrs.put(ATTR_INNER, hop.getInnerOp().name());
    if (hop.getOuterOp() != null)
      attrs.put(ATTR_OUTER, hop.getOuterOp().name());

    Boolean rightVector = vectorFlag(hop.getInput(), 1);
    if (rightVector != null)
      attrs.put(ATTR_R_IS_VECTOR, rightVector.toString());

    ChainType chain = hop.checkMapMultChain();
    if (chain != null && chain != ChainType.NONE) {
      attrs.put(ATTR_MMCHAIN_TYPE, chain.name());
      attrs.put(ATTR_MMCHAIN_WEIGHTED, Boolean.toString(chain != ChainType.XtXv));
    }

    MMTSJType tsmm = hop.checkTransposeSelf();
    if (tsmm != null && tsmm != MMTSJType.NONE) {
      attrs.put(ATTR_TSMM_TYPE, tsmm.name());
      attrs.put(ATTR_ALIGN, ALIGN_COL_T);
      if (hop.hasFederatedOutput())
        attrs.put(ATTR_TSMM_FED_OUT, "FORCED");
    }
  }

  private void addQuaternaryAttrs(QuaternaryOp hop, Map<String,String> attrs) {
    attrs.put(ATTR_Q_TYPE, hop.getOp().name());
    if (hop.getOp() == org.apache.sysds.common.Types.OpOp4.WDIVMM)
      attrs.put(ATTR_WDIVMM_BASE_TYPE, Integer.toString(hop.getBaseType()));
  }

  private void addTernaryAttrs(TernaryOp hop, Map<String,String> attrs) {
    if (hop.getOp() == OpOp3.MAP) {
      Hop marginHop = hop.getInput().size() > 2 ? hop.getInput().get(2) : null;
      Long margin = literalLong(marginHop);
      if (margin != null)
        attrs.put(ATTR_MAP_MARGIN, Long.toString(margin));
    }
    if (hop.getOp() == OpOp3.CTABLE) {
      boolean disjoint = hop.isDisjointInputs() || inferDisjointCtableBins(hop);
      attrs.put(ATTR_CTABLE_DISJOINT, Boolean.toString(disjoint));
    }
  }

  /**
   * Best-effort static hint for whether a CTABLE can keep its output federated (disjoint bins).
   * <p>
   * CTABLE disjointness is ultimately a data property (see {@code CtableFEDInstruction.isFedOutput}),
   * but in common patterns (e.g., table with a sequence-based row index vector), we can infer
   * disjoint bins without accessing runtime data. This hint is intentionally conservative and only
   * triggers on clear sequence-based constructions.
   */
  private static boolean inferDisjointCtableBins(TernaryOp hop) {
    if (hop == null || hop.getOp() != OpOp3.CTABLE)
      return false;
    List<Hop> inputs = hop.getInput();
    if (inputs == null || inputs.size() < 2)
      return false;
    // Focus on the "table with output dims" variant used by sliceline and related workloads.
    if (inputs.size() < 5)
      return false;
    // Only inspect the index inputs (first two) for a clear seq(.., incr=1) lineage.
    for (int i = 0; i < 2; i++) {
      if (containsUnitIncrementSeq(inputs.get(i), 0, new HashSet<>()))
        return true;
    }
    return false;
  }

  private static boolean containsUnitIncrementSeq(Hop hop, int depth, Set<Long> visited) {
    if (hop == null)
      return false;
    // Protect against deep/recursive graphs.
    if (depth > 8)
      return false;
    if (!visited.add(hop.getHopID()))
      return false;
    if (hop instanceof DataGenOp && ((DataGenOp) hop).getOp() == OpOpDG.SEQ) {
      DataGenOp dg = (DataGenOp) hop;
      // Mirror existing seq checks used in CTABLE rewrites: accept literal incr=1 or
      // recompiler-propagated incrementValue==1.0.
      if (dg.getIncrementValue() == 1.0)
        return true;
      List<Hop> in = dg.getInput();
      if (in != null) {
        int ix = dg.getParamIndex(org.apache.sysds.parser.Statement.SEQ_INCR);
        if (ix >= 0 && ix < in.size() && in.get(ix) instanceof LiteralOp) {
          try {
            if (((LiteralOp) in.get(ix)).getDoubleValue() == 1.0)
              return true;
          }
          catch (Exception ignored) {
            // fall through
          }
        }
      }
    }
    if (hop instanceof LiteralOp) {
      String v = ((LiteralOp) hop).getStringValue();
      if (v != null && v.contains("seq("))
        return true;
    }
    List<Hop> in = hop.getInput();
    if (in == null || in.isEmpty())
      return false;
    for (Hop child : in) {
      if (containsUnitIncrementSeq(child, depth + 1, visited))
        return true;
    }
    return false;
  }

  private void addReorgAttrs(ReorgOp hop, Map<String,String> attrs) {
    if (hop.getOp() != ReOrgOp.RESHAPE)
      return;
    Hop byRow = hop.getInput().size() > 4 ? hop.getInput().get(4) : null;
    Boolean flag = literalBoolean(byRow);
    if (flag != null)
      attrs.put(ATTR_BYROW, flag.toString());
  }

  private void addDataOpAttrs(DataOp hop, Map<String,String> attrs) {
    if (hop.getOp() == OpOpData.FUNCTIONOUTPUT) {
      FunctionOp.FunctionType sourceType = resolveFunctionOutputSourceType(hop);
      if (sourceType != null)
        attrs.put(ATTR_FUNOUT_FCALL_TYPE, sourceType.name());
    }

    if (hop.isWrite()) {
      boolean federatedTarget = hop.getFileFormat() == FileFormat.FEDERATED
          || hop.isFederatedDataOp();
      attrs.put(ATTR_VAR_WRITE_FED, Boolean.toString(federatedTarget));
    }
    else if (hop.getOp() == OpOpData.TRANSIENTREAD) {
      // Hint only if this variable was initialized via federated init (privacy fetched from workers)
      boolean fedInit = FederatedPlannerUtils.isFedInitVar(hop.getName());
      attrs.put(ATTR_VAR_READ_FED, Boolean.toString(fedInit));
      if (fedInit) {
        FType initType = FederatedPlannerUtils.getFedInitFType(hop.getName());
        if (initType != null)
          attrs.put(ATTR_VAR_READ_FTYPE, initType.name());
      }
    }
  }

  private static FunctionOp.FunctionType resolveFunctionOutputSourceType(DataOp hop) {
    if (hop == null || hop.getOp() != OpOpData.FUNCTIONOUTPUT)
      return null;
    List<Hop> inputs = hop.getInput();
    if (inputs == null || inputs.isEmpty() || inputs.get(0) == null)
      return null;

    FunctionOp.FunctionType fallback = null;
    for (Hop parent : inputs.get(0).getParent()) {
      if (!(parent instanceof FunctionOp))
        continue;
      FunctionOp.FunctionType type = ((FunctionOp) parent).getFunctionType();
      if (type == FunctionOp.FunctionType.MULTIRETURN_BUILTIN)
        return type;
      if (fallback == null)
        fallback = type;
    }
    return fallback;
  }

  private void addSpoofAttrs(SpoofFusedOp hop, Map<String,String> attrs) {
    Class<?> generator = hop.getGeneratorClass();
    if (generator == null)
      return;
    try {
      SpoofOperator op = CodegenUtils.createInstance(generator);
      if (op instanceof SpoofCellwise) {
        attrs.put(ATTR_SPOOF_TEMPLATE, "cellwise");
        attrs.put(ATTR_SPOOF_CELL_TYPE,
            cellTypeToken(((SpoofCellwise) op).getCellType()));
      }
      else if (op instanceof SpoofRowwise) {
        attrs.put(ATTR_SPOOF_TEMPLATE, "rowwise");
        attrs.put(ATTR_SPOOF_ROW_TYPE,
            rowTypeToken(((SpoofRowwise) op).getRowType()));
      }
      else if (op instanceof SpoofMultiAggregate) {
        attrs.put(ATTR_SPOOF_TEMPLATE, "multiagg");
      }
      else if (op instanceof SpoofOuterProduct) {
        attrs.put(ATTR_SPOOF_TEMPLATE, "outer");
        attrs.put(ATTR_SPOOF_OUTER_TYPE,
            outerTypeToken(((SpoofOuterProduct) op).getOuterProdType()));
      }
    } catch (Exception ex) {
      // Ignore instantiation issues; attributes remain unset.
    }
  }

  private static String cellTypeToken(SpoofCellwise.CellType type) {
    if (type == null)
      return null;
    switch (type) {
      case FULL_AGG:
        return "fullagg";
      case ROW_AGG:
        return "rowagg";
      case COL_AGG:
        return "colagg";
      case NO_AGG:
      default:
        return "noagg";
    }
  }

  private static String rowTypeToken(SpoofRowwise.RowType type) {
    if (type == null)
      return null;
    switch (type) {
      case FULL_AGG:
        return "fullagg";
      case ROW_AGG:
        return "rowagg";
      case NO_AGG_B1:
        return "noaggb1";
      case NO_AGG_CONST:
        return "noaggconst";
      case NO_AGG:
        return "noagg";
      default:
        return type.name().toLowerCase(Locale.ROOT);
    }
  }

  private static String outerTypeToken(SpoofOuterProduct.OutProdType type) {
    if (type == null)
      return null;
    switch (type) {
      case LEFT_OUTER_PRODUCT:
        return "leftouterproduct";
      case RIGHT_OUTER_PRODUCT:
        return "rightouterproduct";
      case AGG_OUTER_PRODUCT:
        return "aggouterproduct";
      case CELLWISE_OUTER_PRODUCT:
      default:
        return "cellwiseouterproduct";
    }
  }

  private boolean isAppend(Hop hop) {
    if (hop instanceof BinaryOp) {
      OpOp2 op = ((BinaryOp) hop).getOp();
      return op == OpOp2.CBIND || op == OpOp2.RBIND;
    }
    if (hop instanceof NaryOp) {
      org.apache.sysds.common.Types.OpOpN op = ((NaryOp) hop).getOp();
      return op == org.apache.sysds.common.Types.OpOpN.CBIND
          || op == org.apache.sysds.common.Types.OpOpN.RBIND;
    }
    return false;
  }

  private boolean isCbind(Hop hop) {
    if (hop instanceof BinaryOp)
      return ((BinaryOp) hop).getOp() == OpOp2.CBIND;
    if (hop instanceof NaryOp)
      return ((NaryOp) hop).getOp() == org.apache.sysds.common.Types.OpOpN.CBIND;
    return false;
  }

  private static Boolean vectorFlag(List<Hop> inputs, int idx) {
    if (inputs == null || idx < 0 || idx >= inputs.size())
      return null;
    return vectorFlag(inputs.get(idx));
  }

  private static Boolean vectorFlag(Hop hop) {
    if (hop == null)
      return null;
    if (hop.rowsKnown() && hop.colsKnown()) {
      long rows = hop.getDim1();
      long cols = hop.getDim2();
      return (rows == 1 && cols >= 0) || (cols == 1 && rows >= 0);
    }
    return null;
  }

  private static Long literalLong(Hop hop) {
    if (!(hop instanceof LiteralOp))
      return null;
    LiteralOp lit = (LiteralOp) hop;
    switch (lit.getValueType()) {
      case INT64:
      case UINT8:
      case FP64:
      case FP32:
      case INT32:
        return lit.getLongValue();
      default:
        return null;
    }
  }

  private static Boolean literalBoolean(Hop hop) {
    if (!(hop instanceof LiteralOp))
      return null;
    LiteralOp lit = (LiteralOp) hop;
    switch (lit.getValueType()) {
      case BOOLEAN:
        return lit.getBooleanValue();
      case FP64:
      case FP32:
      case INT64:
      case INT32:
      case UINT8:
        return lit.getDoubleValue() != 0d;
      default:
        return null;
    }
  }

  private List<InputKind> inputKinds(Hop hop) {
    List<InputKind> kinds = new ArrayList<>();
    if (hop.getInput() == null || hop.getInput().isEmpty())
      return kinds;
    for (Hop in : hop.getInput()) {
      if (in == null) {
        kinds.add(InputKind.UNKNOWN);
        continue;
      }
      DataType dt = in.getDataType();
      if (dt == DataType.MATRIX)
        kinds.add(InputKind.MATRIX);
      else if (dt == DataType.FRAME)
        kinds.add(InputKind.FRAME);
      else if (dt == DataType.SCALAR)
        kinds.add(InputKind.SCALAR);
      else
        kinds.add(InputKind.UNKNOWN);
    }
    return kinds;
  }

  private List<FType> mapFederatedTypes(Hop hop, List<FTypes.FType> runtimeTypes) {
    List<FType> mapped = new ArrayList<>();
    int hopInputSize = hop.getInput() == null ? 0 : hop.getInput().size();
    int runtimeSize = runtimeTypes == null ? 0 : runtimeTypes.size();
    int limit = Math.max(hopInputSize, runtimeSize);
    for (int i = 0; i < limit; i++) {
      FTypes.FType rt = (runtimeTypes == null || i >= runtimeSize) ? null : runtimeTypes.get(i);
      Hop hopInput = (hop.getInput() == null || i >= hopInputSize) ? null : hop.getInput().get(i);
      mapped.add(mapFederatedType(rt, hopInput));
    }
    return mapped;
  }

  private FType mapFederatedType(FTypes.FType runtimeType, Hop input) {
    if (input != null) {
      DataType dt = input.getDataType();
      if (dt != DataType.MATRIX && dt != DataType.FRAME)
        return null;
    }
    if (runtimeType == null)
      return null;
    switch (runtimeType) {
      case ROW:
        return FType.ROW;
      case COL:
        return FType.COL;
      case FULL:
        return FType.FULL;
      case PART:
        return FType.PART;
      case BROADCAST:
        return FType.BROADCAST;
      default:
        return null;
    }
  }

  private ShapeHint buildShapeHint(Hop hop) {
    return buildShapeHint(hop, null);
  }

  private ShapeHint buildShapeHint(Hop hop, List<FTypes.FType> inFTypes) {
    long rows = hop.getDim1();
    long cols = hop.getDim2();
    int blockSize = hop.getBlocksize();
    Hop a = hop.getInput() != null && hop.getInput().size() > 0 ? hop.getInput().get(0) : null;
    Hop b = hop.getInput() != null && hop.getInput().size() > 1 ? hop.getInput().get(1) : null;
    long rowsA = (a != null) ? a.getDim1() : -1;
    long colsA = (a != null) ? a.getDim2() : -1;
    long rowsB = (b != null) ? b.getDim1() : -1;
    long colsB = (b != null) ? b.getDim2() : -1;
    Optional<Boolean> fullSinglePartition = inferFullSinglePartition(hop, inFTypes);
    return new ShapeHint(rows, cols, blockSize, fullSinglePartition, rowsA, colsA, rowsB, colsB);
  }

  private static ShapeHint mergeFullSinglePartitionHint(
      ShapeHint hint, Hop hop, List<FTypes.FType> inFTypes) {
    if (hint == null)
      return hint;
    if (hint.fullSinglePartition().isPresent())
      return hint;
    Optional<Boolean> inferred = inferFullSinglePartition(hop, inFTypes);
    if (!inferred.isPresent())
      return hint;
    return new ShapeHint(hint.rows(), hint.cols(), hint.blockSize(), inferred,
        hint.rowsA(), hint.colsA(), hint.rowsB(), hint.colsB());
  }

  private static Optional<Boolean> inferFullSinglePartition(Hop hop, List<FTypes.FType> inFTypes) {
    if (hop == null || inFTypes == null || inFTypes.isEmpty())
      return Optional.empty();

    List<Hop> inputs = hop.getInput();
    boolean sawFull = false;
    boolean anyMulti = false;
    boolean anyKnown = false;

    for (int i = 0; i < inFTypes.size(); i++) {
      if (inFTypes.get(i) != FTypes.FType.FULL)
        continue;
      sawFull = true;
      Hop inHop = (inputs != null && i < inputs.size()) ? inputs.get(i) : null;
      Optional<Integer> count = inferFederatedRangeCount(inHop);
      if (!count.isPresent())
        continue;
      anyKnown = true;
      if (count.get() > 1) {
        anyMulti = true;
        break;
      }
    }

    if (!sawFull)
      return Optional.empty();
    if (anyKnown)
      return Optional.of(!anyMulti);

    // Fallback: if the program is effectively single-worker, treat FULL as single-range.
    return (FederatedPlannerUtils.getMaxFedInitWorkers() == 1)
        ? Optional.of(true)
        : Optional.empty();
  }

  private static Optional<Integer> inferFederatedRangeCount(Hop inputHop) {
    if (inputHop == null)
      return Optional.empty();

    if (inputHop instanceof DataOp) {
      DataOp dop = (DataOp) inputHop;
      if (dop.getOp() == OpOpData.FEDERATED) {
        int ridx = dop.getParameterIndex(DataExpression.FED_RANGES);
        if (ridx >= 0) {
          Hop ranges = dop.getInput(ridx);
          List<Hop> rangeItems = (ranges != null) ? ranges.getInput() : null;
          if (rangeItems != null && !rangeItems.isEmpty() && rangeItems.size() % 2 == 0) {
            return Optional.of(rangeItems.size() / 2);
          }
        }
      }
    }

    String name = inputHop.getName();
    if (name == null || name.isEmpty())
      return Optional.empty();
    return inferFederatedRangeCountFromVarName(name, new HashSet<>());
  }

  private static Optional<Integer> inferFederatedRangeCountFromVarName(String varName, Set<String> visited) {
    if (varName == null || varName.isEmpty() || visited == null)
      return Optional.empty();
    if (!visited.add(varName))
      return Optional.empty();

    String signature = FederatedPlannerUtils.getFedInitSignature(varName);
    if (signature == null || signature.isEmpty()) {
      String anchorKey = FederatedPlannerUtils.getFedAnchorKey(varName);
      if (anchorKey == null || anchorKey.isEmpty())
        return Optional.empty();
      if (anchorKey.startsWith("VAR:")) {
        String ref = anchorKey.substring("VAR:".length());
        int pipeIx = ref.indexOf('|');
        if (pipeIx >= 0)
          ref = ref.substring(0, pipeIx);
        return inferFederatedRangeCountFromVarName(ref, visited);
      }
      signature = anchorKey;
    }

    int bar = signature.indexOf('|');
    String addrPart = (bar >= 0) ? signature.substring(0, bar) : signature;
    if (addrPart.isEmpty())
      return Optional.empty();
    int count = 0;
    for (String tok : addrPart.split(";")) {
      if (tok != null && !tok.isBlank())
        count++;
    }
    return (count > 0) ? Optional.of(count) : Optional.empty();
  }

  private List<List<FTypes.FType>> enumerateCandidates(List<Set<FTypes.FType>> candidates) {
    List<List<FTypes.FType>> combos = new ArrayList<>();
    if (candidates == null || candidates.isEmpty()) {
      combos.add(List.of());
      return combos;
    }
    backtrackCandidates(candidates, 0, new LinkedList<>(), combos);
    return combos;
  }

  private void backtrackCandidates(
      List<Set<FTypes.FType>> candidates,
      int idx,
      LinkedList<FTypes.FType> current,
      List<List<FTypes.FType>> result) {
    if (idx == candidates.size()) {
      result.add(new ArrayList<>(current));
      return;
    }
    Set<FTypes.FType> options = candidates.get(idx);
    if (options == null || options.isEmpty()) {
      current.add(null);
      backtrackCandidates(candidates, idx + 1, current, result);
      current.removeLast();
      return;
    }
    for (FTypes.FType option : options) {
      current.add(option);
      backtrackCandidates(candidates, idx + 1, current, result);
      current.removeLast();
    }
  }

  private void logOracleInvocation(Hop hop, OpSig sig, List<FType> inFTypes,
      ShapeHint hint, String phase) {
    if (hop == null || sig == null)
      return;
    ShapeHint.DiagnosticSnapshot diagnostic = hint == null ? null : hint.diagnosticSnapshot();
    String message = String.format(Locale.ROOT,
        "[Oracle::%s] hop=%d (%s) opcode=%s ns=%s name=%s inFTypes=%s hint=[r=%d,c=%d,b=%d]",
        phase, hop.getHopID(), hop.getOpString(), sig.opcode(),
        attrValue(sig, ATTR_FCALL_NAMESPACE), attrValue(sig, ATTR_FCALL_NAME),
        formatFTypes(inFTypes), diagnostic == null ? -1 : diagnostic.rows(),
        diagnostic == null ? -1 : diagnostic.cols(), diagnostic == null ? -1 : diagnostic.blockSize());
    FederatedPlannerLogger.logInfoMessage(message);
  }

  private void logOracleResult(Hop hop, RulesApi.OpCaps caps) {
    if (hop == null || caps == null)
      return;
    String message = String.format(Locale.ROOT,
        "[Oracle::end] hop=%d exec=%s placement=%s reason=%s detail=%s",
        hop.getHopID(), caps.exec(), caps.placement(), caps.reason(),
        caps.detail().orElse(""));
    FederatedPlannerLogger.logInfoMessage(message);
  }

  private String formatFTypes(List<FType> types) {
    if (types == null || types.isEmpty())
      return "[]";
    List<String> parts = new ArrayList<>(types.size());
    for (FType t : types) {
      parts.add(t == null ? "null" : t.name());
    }
    return parts.toString();
  }

  private static String attrValue(OpSig sig, String key) {
    if (sig == null || key == null)
      return null;
    Map<String,String> attrs = sig.attrs();
    return (attrs == null) ? null : attrs.get(key);
  }

  private static boolean isMapLeftIndex(LeftIndexingOp hop) {
    if (hop == null || hop.getInput() == null || hop.getInput().size() < 2)
      return false;
    Hop lhs = hop.getInput().get(0);
    Hop rhs = hop.getInput().get(1);
    if (rhs == null)
      return false;
    if (rhs.getDataType() == DataType.SCALAR)
      return true;

    long m1Rows = (lhs != null) ? lhs.getDim1() : -1;
    long m1Cols = (lhs != null) ? lhs.getDim2() : -1;
    long m1Blen = (lhs != null) ? lhs.getBlocksize() : -1;
    long m2Rows = rhs.getDim1();
    long m2Cols = rhs.getDim2();
    long m2Nnz = rhs.getNnz();
    if (m1Rows <= 0 || m1Cols <= 0 || m2Rows <= 0 || m2Cols <= 0 || m1Blen <= 0)
      return false;

    boolean broadcastRhs = OptimizerUtils.checkSparkBroadcastMemoryBudget(
        m2Rows, m2Cols, (int) m1Blen, m2Nnz);
    if (broadcastRhs)
      return true;

    boolean aligned = rhs.getDataType() == DataType.MATRIX
        && ((m1Rows == m2Rows && m1Cols <= m1Blen)
        || (m1Cols == m2Cols && m1Rows <= m1Blen));
    return aligned;
  }

  private static final class CanonicalOpcode {
    private CanonicalOpcode() {}

    static String from(Hop hop) {
      if (hop == null)
        return "";

      if (hop instanceof AggBinaryOp && ((AggBinaryOp) hop).isMatrixMultiply())
        return Opcodes.MMULT.toString();
      if (hop instanceof LeftIndexingOp)
        return isMapLeftIndex((LeftIndexingOp) hop)
            ? Opcodes.MAPLEFTINDEX.toString()
            : Opcodes.LEFT_INDEX.toString();
      if (hop instanceof IndexingOp)
        return Opcodes.RIGHT_INDEX.toString();
      if (hop instanceof ReorgOp)
        return ((ReorgOp) hop).getOp().toString();
      if (hop instanceof AggUnaryOp)
        return hop.getOpString().toLowerCase(Locale.ROOT);
      if (hop instanceof UnaryOp)
        return ((UnaryOp) hop).getOp().toString();
      if (hop instanceof BinaryOp)
        return ((BinaryOp) hop).getOp().toString();
      if (hop instanceof NaryOp)
        return ((NaryOp) hop).getOp().toString();
      if (hop instanceof TernaryOp)
        return ((TernaryOp) hop).getOp().toString();
      if (hop instanceof QuaternaryOp)
        return ((QuaternaryOp) hop).getOp().toString();
      if (hop instanceof FunctionOp)
        return FunctionOp.OPCODE;
      if (hop instanceof ParameterizedBuiltinOp) {
        ParamBuiltinOp op = ((ParameterizedBuiltinOp) hop).getOp();
        return (op != null) ? op.toString() : fallback(hop);
      }
      if (hop instanceof DataOp) {
        return ((DataOp) hop).getOp().toString();
      }
      return fallback(hop);
    }

    private static String fallback(Hop hop) {
      String raw = hop.getOpString();
      return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
  }
}
