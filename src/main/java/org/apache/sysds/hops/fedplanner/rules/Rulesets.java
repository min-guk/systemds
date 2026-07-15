/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.hops.fedplanner.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.BaseRule;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.Guard;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Concrete rule implementations (compile-only).
 */
public final class Rulesets {
  private Rulesets() {}

  private static final List<FType> NO_TYPES = List.of();
  private static final String ATTR_Q_TYPE = "q.type";
  private static final String ATTR_WDIVMM_BASE_TYPE = "wdivmm.baseType";
  // map.margin is a rule-layer-only hint (defaults to 0) used by FrameMapRule.
  private static final String ATTR_MAP_MARGIN = "map.margin";
  private static final String SCALAR_LOUT_DETAIL = "scalar output → LOUT";
  private static final String WDIVMM_ALIGN_DETAIL = "output dims derive from U/V; partition misalignment risk";
  private static final String WDIVMM_NATIVE_X_AXIS_DETAIL =
      "federated WDivMM runtime supports ROW/COL partitioned X only";
  private static final String WSLOSS_X_AXIS_ONLY_DETAIL =
      "federated WSLoss runtime supports ROW/COL partitioned X only";
  private static final String FED_WRITE_DETAIL = "federated write target";
  private static final String ATTR_SPOOF_TEMPLATE = "spoof.template";
  private static final String ATTR_SPOOF_CELL_TYPE = "spoof.cellType";
  private static final String ATTR_SPOOF_ROW_TYPE = "spoof.rowType";
  private static final String ATTR_SPOOF_OUTER_TYPE = "spoof.outer.type";
  private static final String ATTR_VAR_WRITE_FED = "var.write.federated";
  private static final String ATTR_VAR_READ_FED = "var.read.federated";
  private static final String ATTR_VAR_READ_FTYPE = "var.read.ftype";
  private static final String ATTR_FCALL_NAMESPACE = "fcall.namespace";
  private static final String ATTR_FCALL_NAME = "fcall.name";
  private static final String ATTR_FCALL_TYPE = "fcall.type";
  private static final String ATTR_FUNOUT_FCALL_TYPE = "funout.fcall.type";
  private static final String MULTIRETURN_BUILTIN_TYPE = "MULTIRETURN_BUILTIN";
  private static final String WUMM_X_AXIS_ONLY_DETAIL =
      "WUMM supports only ROW or COL partitioned X (per QuaternaryWUMMFEDInstruction)";
  private static final String APPEND_FULL_SINGLE_RANGE_DETAIL =
      "Append with FType.FULL requires single federated range";
  private static final String ALIGNMENT_NOT_PROVABLE_NOTE =
      "alignment not statically provable; runtime may broadcast-slice";
  private static final String CUMOFF_FULL_SINGLE_RANGE_DETAIL =
      "FULL input assumed single federated range; runtime validates mapping";

  private static List<FType> candidates(List<List<FType>> in, int pos) {
    if (in == null || pos < 0 || pos >= in.size())
      return NO_TYPES;
    List<FType> res = in.get(pos);
    return (res == null) ? NO_TYPES : res;
  }

  private static FType typeAt(List<FType> types, int idx) {
    if (types == null || idx < 0 || idx >= types.size())
      return null;
    return types.get(idx);
  }

  private static boolean isAxis(FType t) {
    return t == FType.ROW || t == FType.COL;
  }

  private static boolean matchesAxis(FType t, FType axis) {
    return axis != null && axis == t;
  }

  private static boolean hasAxis(Collection<FType> types, FType axis) {
    if (types == null || axis == null)
      return false;
    for (FType t : types) {
      if (matchesAxis(t, axis))
        return true;
    }
    return false;
  }

  private static boolean isScalarLike(FType t) {
    return t == null;
  }

  private static boolean isBroadcastOrScalar(FType t) {
    return t == FType.BROADCAST || isScalarLike(t);
  }

  private static boolean isTrueFederated(FType t) {
    return t == FType.ROW || t == FType.COL || t == FType.PART;
  }

  private static boolean isFederatedLike(FType t) {
    return t == FType.ROW || t == FType.COL || t == FType.FULL || t == FType.PART;
  }

  private static boolean axisKnown(FType axis, ShapeHint hint) {
    if (axis == null || hint == null)
      return false;
    if (axis == FType.ROW)
      return hint.rows() > 0;
    if (axis == FType.COL)
      return hint.cols() > 0;
    return false;
  }

  private static boolean isVectorDims(long rows, long cols) {
    if (rows <= 0 || cols <= 0)
      return false;
    if (rows == 1 && cols == 1)
      return false;
    return rows == 1 || cols == 1;
  }

  private static boolean isVectorHint(ShapeHint hint) {
    if (hint == null)
      return false;
    long rows = hint.rows();
    long cols = hint.cols();
    if (rows <= 0 || cols <= 0)
      return false;
    if (rows == 1 && cols == 1)
      return false;
    return rows == 1 || cols == 1;
  }

  private static boolean aligned(List<FType> left, List<FType> right, FType axis, ShapeHint hint) {
    return axisKnown(axis, hint) && hasAxis(left, axis) && hasAxis(right, axis);
  }

  private static boolean aligned(FType left, FType right, FType axis, ShapeHint hint) {
    return axisKnown(axis, hint) && matchesAxis(left, axis) && matchesAxis(right, axis);
  }

  private static boolean matrixScalarPair(List<FType> left, List<FType> right, FType axis) {
    return (hasAxis(left, axis) && hasBroadcastOrScalarFromList(right))
        || (hasAxis(right, axis) && hasBroadcastOrScalarFromList(left));
  }

  private static boolean hasBroadcastOrScalarFromList(Collection<FType> types) {
    if (types == null)
      return false;
    for (FType t : types) {
      if (isBroadcastOrScalar(t))
        return true;
    }
    return false;
  }

  private static boolean matrixScalarPair(FType left, FType right, FType axis) {
    return (matchesAxis(left, axis) && isBroadcastOrScalar(right))
        || (matchesAxis(right, axis) && isBroadcastOrScalar(left));
  }

  private static boolean isOuterLike(FType left, FType right, ShapeHint hint) {
    boolean rowCol = matchesAxis(left, FType.ROW) && matchesAxis(right, FType.COL);
    boolean colRow = matchesAxis(left, FType.COL) && matchesAxis(right, FType.ROW);
    if (!(rowCol || colRow))
      return false;
    if (hint == null)
      return true;
    // Matrix-vector elementwise operations (e.g., X / rowVector) are not outer-product-like even
    // when their *output* is a full matrix. Treat them as broadcastable and avoid pessimistic
    // UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY fallbacks.
    boolean aVec = isVectorDims(hint.rowsA(), hint.colsA());
    boolean bVec = isVectorDims(hint.rowsB(), hint.colsB());
    if (aVec ^ bVec)
      return false;
    boolean rowsLarge = hint.rows() > 1 || hint.rows() == -1;
    boolean colsLarge = hint.cols() > 1 || hint.cols() == -1;
    return rowsLarge && colsLarge;
  }

  private static FTypeProfile profileOf(Set<FType> outs) {
    if (outs == null || outs.isEmpty())
      return FTypeProfile.empty();
    return FTypeProfile.ofOutput(new ArrayList<>(outs));
  }

  private static FTypeProfile primaryLikeProfile(List<List<FType>> inFTypeCandidates) {
    List<FType> primary = candidates(inFTypeCandidates, 0);
    if (primary.isEmpty())
      return FTypeProfile.empty();
    Set<FType> outs = new LinkedHashSet<>();
    if (primary.contains(FType.ROW))
      outs.add(FType.ROW);
    if (primary.contains(FType.COL))
      outs.add(FType.COL);
    if (primary.contains(FType.PART))
      outs.add(FType.PART);
    if (primary.contains(FType.FULL))
      outs.add(FType.FULL);
    return profileOf(outs);
  }

  private static boolean hasExpectedArity(List<FType> inFTypes, int expected) {
    return inFTypes != null && inFTypes.size() == expected;
  }

  private static String attrValue(OpSig sig, String key) {
    if (sig == null || key == null)
      return null;
    Map<String,String> attrs = sig.attrs();
    if (attrs == null || attrs.isEmpty())
      return null;
    return attrs.get(key);
  }

  private static boolean matchesQType(OpSig sig, String expected) {
    if (sig == null || expected == null)
      return false;
    String raw = attrValue(sig, ATTR_Q_TYPE);
    return raw != null && raw.equalsIgnoreCase(expected);
  }

  private static boolean attrBoolean(OpSig sig, String key) {
    if (sig == null || key == null)
      return false;
    String raw = attrValue(sig, key);
    return raw != null && Boolean.parseBoolean(raw);
  }

  private static FType attrFType(OpSig sig, String key) {
    if (sig == null || key == null)
      return null;
    String raw = attrValue(sig, key);
    if (raw == null || raw.isBlank())
      return null;
    try {
      return FType.valueOf(raw.trim());
    }
    catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static boolean matchesWeightedQuaternary(OpSig sig, Set<String> opcodes, String qType) {
    if (sig == null || sig.category() != OpCategory.QUATERNARY)
      return false;
    String opcode = normalizedOpcode(sig);
    if (opcodes != null && opcodes.contains(opcode))
      return true;
    return matchesQType(sig, qType);
  }

  private static OpCaps scalarCaps(OpSig sig, ExecType exec, ReasonCode reason) {
    OpCategory category = (sig != null) ? sig.category() : OpCategory.OTHER;
    String opcode = (sig != null && sig.opcode() != null) ? sig.opcode() : "";
    return OpCaps.newBuilder()
        .category(category)
        .opcode(opcode)
        .exec(exec)
        .placement(FederatedOutput.LOUT)
        .reason(reason)
        .detail(SCALAR_LOUT_DETAIL)
        .build();
  }

  private static OpCaps fedLocalWithDetail(OpSig sig, ReasonCode reason, String detail) {
    OpCategory category = (sig != null) ? sig.category() : OpCategory.OTHER;
    String opcode = (sig != null && sig.opcode() != null) ? sig.opcode() : "";
    return OpCaps.newBuilder()
        .category(category)
        .opcode(opcode)
        .exec(ExecType.FED)
        .placement(FederatedOutput.LOUT)
        .reason(reason)
        .detail(detail)
        .build();
  }

  private static Integer parseBaseType(String raw) {
    if (raw == null || raw.isBlank())
      return null;
    try {
      int code = Integer.parseInt(raw.trim());
      return (code >= 0 && code <= 4) ? code : null;
    } catch (NumberFormatException nfe) {
      return null;
    }
  }

  private static boolean isBasicBaseType(int code) {
    return code == 0;
  }

  private static boolean isLeftBaseType(int code) {
    return code == 1 || code == 3;
  }

  private static boolean isRightBaseType(int code) {
    return code == 2 || code == 4;
  }

  private static boolean axisPreserved(FType x, ShapeHint hint) {
    if (x == null)
      return false;
    if (x == FType.PART || x == FType.FULL)
      return true;
    if (hint == null)
      return true;
    if (x == FType.ROW)
      return dimsMatch(hint.rows(), hint.rowsA());
    if (x == FType.COL)
      return dimsMatch(hint.cols(), hint.colsA());
    return false;
  }

  private static boolean dimsMatch(long a, long b) {
    if (a < 0 || b < 0)
      return true;
    return a == b;
  }

  private static OpCaps cpCaps(OpSig sig, ReasonCode reason) {
    Objects.requireNonNull(sig, "sig");
    return OpCaps.newBuilder()
        .category(sig.category())
        .opcode(sig.opcode())
        .exec(ExecType.CP)
        .placement(FederatedOutput.LOUT)
        .reason(reason)
        .build();
  }

  private static OpCaps fedFoutCaps(OpSig sig, FType axis, ReasonCode reason) {
    Objects.requireNonNull(sig, "sig");
    return OpCaps.newBuilder()
        .category(sig.category())
        .opcode(sig.opcode())
        .exec(ExecType.FED)
        .placement(FederatedOutput.FOUT)
        .fout(true, axis)
        .reason(reason)
        .build();
  }

  private static OpCaps cpFoutCaps(OpSig sig, FType axis, ReasonCode reason, String detail) {
    Objects.requireNonNull(sig, "sig");
    OpCaps.Builder builder = OpCaps.newBuilder()
        .category(sig.category())
        .opcode(sig.opcode())
        .exec(ExecType.CP)
        .placement(FederatedOutput.FOUT)
        .fout(true, axis)
        .reason(reason);
    if (detail != null && !detail.isBlank())
      builder.detail(detail);
    return builder.build();
  }

  private static OpCaps fedLocalCaps(OpSig sig, ReasonCode reason) {
    Objects.requireNonNull(sig, "sig");
    return OpCaps.newBuilder()
        .category(sig.category())
        .opcode(sig.opcode())
        .exec(ExecType.FED)
        .placement(FederatedOutput.LOUT)
        .reason(reason)
        .build();
  }

  private static OpCaps guardAwareFout(OpSig sig, FType axis, ReasonCode reason, Guard.Result guard) {
    return guardAwareFout(sig, axis, reason, guard, null);
  }

  private static OpCaps guardAwareFout(
      OpSig sig, FType axis, ReasonCode reason, Guard.Result guard, String detail) {
    if (guard != null && guard.isFail())
      return guardFallbackBuilder(sig, guard).build();

    OpCaps.Builder builder = OpCaps.newBuilder()
        .category(sig != null ? sig.category() : OpCategory.OTHER)
        .opcode(sig != null ? sig.opcode() : "")
        .exec(ExecType.FED)
        .placement(FederatedOutput.FOUT)
        .fout(true, axis)
        .reason(reason);
    if (detail != null && !detail.isBlank())
      builder.detail(detail);
    if (guard == null || guard.isUnknown())
      builder.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
    else
      appendGuardPassNote(builder, guard);
    return builder.build();
  }

  private static OpCaps.Builder guardFallbackBuilder(OpSig sig, Guard.Result guard) {
    ReasonCode code = (guard != null && guard.isFail())
        ? ReasonCode.REPR_CHANGE_GUARD_FAIL
        : ReasonCode.REPR_CHANGE_GUARD_UNKNOWN;
    return OpCaps.newBuilder()
        .category(sig != null ? sig.category() : OpCategory.OTHER)
        .opcode(sig != null ? sig.opcode() : "")
        .exec(ExecType.CP)
        .placement(FederatedOutput.LOUT)
        
        .reason(code)
        .detail(guardDetail(guard));
  }

  private static void appendGuardPassNote(OpCaps.Builder builder, Guard.Result guard) {
    if (builder == null || guard == null || !guard.isPass())
      return;
    builder.note(ReasonCode.REPR_CHANGE_GUARD_PASS, guardDetail(guard));
  }

  private static String guardDetail(Guard.Result guard) {
    if (guard == null)
      return "guard result unavailable";
    String detail = guard.detail();
    if (detail != null && !detail.isBlank())
      return detail;
    if (guard.isFail())
      return "representation change guard failed";
    if (guard.isPass())
      return "representation change guard passed";
    return "insufficient guard hints";
  }

  private static OpCaps guardAwareFoutWithNote(OpSig sig, FType axis, ReasonCode reason,
      Guard.Result guard, String note) {
    if (note == null || note.isBlank())
      return guardAwareFout(sig, axis, reason, guard);
    if (guard != null && guard.isFail())
      return guardFallbackBuilder(sig, guard).build();

    OpCaps.Builder builder = OpCaps.newBuilder()
        .category(sig != null ? sig.category() : OpCategory.OTHER)
        .opcode(sig != null ? sig.opcode() : "")
        .exec(ExecType.FED)
        .placement(FederatedOutput.FOUT)
        .fout(true, axis)
        .reason(reason)
        .detail(note);
    if (guard == null || guard.isUnknown())
      builder.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
    else
      appendGuardPassNote(builder, guard);
    return builder.build();
  }

  private static String normalizedOpcode(OpSig sig) {
    return (sig == null || sig.opcode() == null)
        ? ""
        : sig.opcode().toLowerCase(Locale.ROOT);
  }

  private static FType defaultType(FType t) {
    return t;
  }

  private static FType axisOf(FType t) {
    if (matchesAxis(t, FType.ROW))
      return FType.ROW;
    if (matchesAxis(t, FType.COL))
      return FType.COL;
    return null;
  }

  private static ReasonCode reasonForNonAxis(FType t) {
    return (t == FType.FULL || t == FType.PART)
        ? ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY
        : ReasonCode.NO_FED_INPUT;
  }

  private static boolean isFedAxis(FType t) {
    return matchesAxis(t, FType.ROW) || matchesAxis(t, FType.COL);
  }

  private static boolean isScalarHint(ShapeHint hint) {
    if (hint == null)
      return false;
    long rows = hint.rows();
    long cols = hint.cols();
    if (rows < 0 || cols < 0)
      return false;
    return rows == 1 && cols == 1;
  }

  private static String axisLabel(FType axis) {
    return (axis == FType.COL) ? "COL" : "ROW";
  }

  private static void noteAligned(OpCaps.Builder builder, String name, FType candidate, FType axis) {
    if (builder == null || axis == null)
      return;
    if (matchesAxis(candidate, axis)) {
      builder.note(ReasonCode.ALIGNED_HINT,
          String.format(Locale.ROOT, "%s aligned to %s", name, axisLabel(axis)));
    }
  }

  private static void appendUVAlignNotes(OpCaps.Builder builder, FType axis, FType u, FType v) {
    noteAligned(builder, "U", u, axis);
    noteAligned(builder, "V", v, axis);
  }

  private static FType preserveOrAxis(FType t) {
    FType axis = axisOf(t);
    return (axis != null) ? axis : Objects.requireNonNull(t, "fType");
  }

  private enum SpoofTemplate { CELLWISE, ROWWISE, MULTIAGG, OUTER }
  private enum SpoofCellType { FULL_AGG, ROW_AGG, COL_AGG, NO_AGG }
  private enum SpoofRowType { FULL_AGG, ROW_AGG, NO_AGG, NO_AGG_B1, NO_AGG_CONST, COL_AGG }
  private enum SpoofOuterType { LEFT_OUTER_PRODUCT, RIGHT_OUTER_PRODUCT, CELLWISE_OUTER_PRODUCT, AGG_OUTER_PRODUCT }

  private static final class AttrValue<T> {
    final T value;
    final boolean invalid;
    final boolean present;

    private AttrValue(T value, boolean invalid, boolean present) {
      this.value = value;
      this.invalid = invalid;
      this.present = present;
    }

    static <T> AttrValue<T> missing() { return new AttrValue<>(null, false, false); }
    static <T> AttrValue<T> invalid() { return new AttrValue<>(null, true, true); }
    static <T> AttrValue<T> of(T value) { return new AttrValue<>(Objects.requireNonNull(value, "value"), false, true); }
    boolean hasValue() { return present && !invalid && value != null; }
  }

  private static final class TemplateInfo {
    final SpoofTemplate template;
    final boolean invalid;

    TemplateInfo(SpoofTemplate template, boolean invalid) {
      this.template = template;
      this.invalid = invalid;
    }
  }

  private static final class FedInfo {
    final boolean hasFederated;
    final boolean broadcastOnly;
    final boolean mixedAxes;
    final FType primary;

    FedInfo(boolean hasFederated, boolean broadcastOnly, boolean mixedAxes, FType primary) {
      this.hasFederated = hasFederated;
      this.broadcastOnly = broadcastOnly;
      this.mixedAxes = mixedAxes;
      this.primary = primary;
    }
  }

  private static TemplateInfo resolveTemplate(OpSig sig) {
    AttrValue<SpoofTemplate> attr = parseTemplateAttr(sig);
    boolean invalid = attr.invalid;
    SpoofTemplate template = attr.value;
    if (template == null)
      template = templateFromOpcode(sig);
    return new TemplateInfo(template, invalid);
  }

  private static AttrValue<SpoofTemplate> parseTemplateAttr(OpSig sig) {
    String raw = attrValue(sig, ATTR_SPOOF_TEMPLATE);
    if (raw == null || raw.isBlank())
      return AttrValue.missing();
    String token = normalizedAttrToken(raw);
    if (token == null)
      return AttrValue.invalid();
    switch (token) {
      case "cellwise": return AttrValue.of(SpoofTemplate.CELLWISE);
      case "rowwise": return AttrValue.of(SpoofTemplate.ROWWISE);
      case "multiaggregate":
      case "multiagg": return AttrValue.of(SpoofTemplate.MULTIAGG);
      case "outer":
      case "outerproduct": return AttrValue.of(SpoofTemplate.OUTER);
      default: return AttrValue.invalid();
    }
  }

  private static AttrValue<SpoofCellType> parseCellTypeAttr(OpSig sig) {
    String raw = attrValue(sig, ATTR_SPOOF_CELL_TYPE);
    if (raw == null || raw.isBlank())
      return AttrValue.missing();
    String token = normalizedAttrToken(raw);
    if (token == null)
      return AttrValue.invalid();
    switch (token) {
      case "fullagg": return AttrValue.of(SpoofCellType.FULL_AGG);
      case "rowagg": return AttrValue.of(SpoofCellType.ROW_AGG);
      case "colagg": return AttrValue.of(SpoofCellType.COL_AGG);
      case "noagg": return AttrValue.of(SpoofCellType.NO_AGG);
      default: return AttrValue.invalid();
    }
  }

  private static AttrValue<SpoofRowType> parseRowTypeAttr(OpSig sig) {
    String raw = attrValue(sig, ATTR_SPOOF_ROW_TYPE);
    if (raw == null || raw.isBlank())
      return AttrValue.missing();
    String token = normalizedAttrToken(raw);
    if (token == null)
      return AttrValue.invalid();
    switch (token) {
      case "fullagg": return AttrValue.of(SpoofRowType.FULL_AGG);
      case "rowagg": return AttrValue.of(SpoofRowType.ROW_AGG);
      case "noagg": return AttrValue.of(SpoofRowType.NO_AGG);
      case "noaggb1": return AttrValue.of(SpoofRowType.NO_AGG_B1);
      case "noaggconst": return AttrValue.of(SpoofRowType.NO_AGG_CONST);
      default:
        if (token.startsWith("colagg"))
          return AttrValue.of(SpoofRowType.COL_AGG);
        return AttrValue.invalid();
    }
  }

  private static AttrValue<SpoofOuterType> parseOuterTypeAttr(OpSig sig) {
    String raw = attrValue(sig, ATTR_SPOOF_OUTER_TYPE);
    if (raw == null || raw.isBlank())
      return AttrValue.missing();
    String token = normalizedAttrToken(raw);
    if (token == null)
      return AttrValue.invalid();
    switch (token) {
      case "leftouterproduct": return AttrValue.of(SpoofOuterType.LEFT_OUTER_PRODUCT);
      case "rightouterproduct": return AttrValue.of(SpoofOuterType.RIGHT_OUTER_PRODUCT);
      case "cellwiseouterproduct": return AttrValue.of(SpoofOuterType.CELLWISE_OUTER_PRODUCT);
      case "aggouterproduct": return AttrValue.of(SpoofOuterType.AGG_OUTER_PRODUCT);
      default: return AttrValue.invalid();
    }
  }

  private static String normalizedAttrToken(String raw) {
    if (raw == null)
      return null;
    StringBuilder sb = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      if (ch == '_' || ch == '-')
        continue;
      sb.append(Character.toLowerCase(ch));
    }
    String res = sb.toString().trim();
    return res.isEmpty() ? null : res;
  }

  private static SpoofTemplate templateFromOpcode(OpSig sig) {
    String opcode = normalizedOpcode(sig);
    if (opcode.isEmpty() || !opcode.contains("spoof"))
      return null;
    if (opcode.contains("spoofcell"))
      return SpoofTemplate.CELLWISE;
    if (opcode.contains("spoofrow") || opcode.contains("spoofra"))
      return SpoofTemplate.ROWWISE;
    if (opcode.contains("spoofmultiaggregate") || opcode.contains("spoofmulti") || opcode.contains("spoofma"))
      return SpoofTemplate.MULTIAGG;
    if (opcode.contains("spoofouterproduct") || opcode.contains("spoofouter") || opcode.contains("spoofop"))
      return SpoofTemplate.OUTER;
    return null;
  }

  private static FedInfo summarizeFedInputs(List<FType> inFTypes) {
    boolean hasFederated = false;
    boolean hasBroadcast = false;
    boolean row = false;
    boolean col = false;
    FType primary = null;
    if (inFTypes != null) {
      for (FType t : inFTypes) {
        if (t == null)
          continue;
        if (t == FType.BROADCAST) {
          hasBroadcast = true;
          continue;
        }
        if (isFederatedLike(t)) {
          if (!hasFederated)
            primary = t;
          hasFederated = true;
          row |= t == FType.ROW;
          col |= t == FType.COL;
        }
      }
    }
    return new FedInfo(hasFederated, hasBroadcast && !hasFederated, row && col, primary);
  }

  private static OpCaps fedPreconditionFailure(OpSig sig, FedInfo fed) {
    if (fed == null || !fed.hasFederated) {
      if (fed != null && fed.broadcastOnly)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      return cpCaps(sig, ReasonCode.NO_FED_INPUT);
    }
    if (fed.mixedAxes)
      return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
    if (fed.primary == null)
      return cpCaps(sig, ReasonCode.NO_FED_INPUT);
    return null;
  }

  private static boolean matchesSpoofTemplate(OpSig sig, SpoofTemplate expected) {
    TemplateInfo info = resolveTemplate(sig);
    return info.template == expected || (info.template == null && info.invalid);
  }

  // Weighted quaternary policies inspect only X; broadcast auxiliaries (U/V/W/eps) are allowed inputs.
  public static final class WeightedSquaredLossRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.WSLOSS.toString());
    private static final int EXPECTED_ARITY = 4;

    @Override public OpCategory category() { return OpCategory.QUATERNARY; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return matchesWeightedQuaternary(sig, OPCODES, "WSLOSS");
    }

    @Override public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (!hasExpectedArity(inFTypes, EXPECTED_ARITY))
        return scalarCaps(sig, ExecType.CP, ReasonCode.ARITY_MISMATCH);

      FType x = typeAt(inFTypes, 0);
      if (x == null)
        return scalarCaps(sig, ExecType.CP, ReasonCode.NO_FED_INPUT);
      if (x == FType.BROADCAST)
        return scalarCaps(sig, ExecType.CP, ReasonCode.BROADCAST_CONSTRAINT);
      if (!isFederatedLike(x))
        return scalarCaps(sig, ExecType.CP, ReasonCode.NO_FED_INPUT);
      // QuaternaryWSLossFEDInstruction rejects non-axis X mappings at runtime.
      if (x == FType.FULL || x == FType.PART)
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            .reason(ReasonCode.PARTITION_FORBIDDEN)
            .detail(WSLOSS_X_AXIS_ONLY_DETAIL)
            .build();
      return scalarCaps(sig, ExecType.FED, ReasonCode.OK);
    }
  }

  public static final class WeightedCrossEntropyRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.WCEMM.toString());
    private static final int EXPECTED_ARITY = 4;

    @Override public OpCategory category() { return OpCategory.QUATERNARY; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return matchesWeightedQuaternary(sig, OPCODES, "WCEMM");
    }

    @Override public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (!hasExpectedArity(inFTypes, EXPECTED_ARITY))
        return scalarCaps(sig, ExecType.CP, ReasonCode.ARITY_MISMATCH);

      FType x = typeAt(inFTypes, 0);
      if (x == null)
        return scalarCaps(sig, ExecType.CP, ReasonCode.NO_FED_INPUT);
      if (x == FType.BROADCAST)
        return scalarCaps(sig, ExecType.CP, ReasonCode.BROADCAST_CONSTRAINT);
      if (!isFederatedLike(x))
        return scalarCaps(sig, ExecType.CP, ReasonCode.NO_FED_INPUT);
      return scalarCaps(sig, ExecType.FED, ReasonCode.OK);
    }
  }

  public static final class WeightedSigmoidRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.WSIGMOID.toString());
    private static final int EXPECTED_ARITY = 3;

    @Override public OpCategory category() { return OpCategory.QUATERNARY; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return matchesWeightedQuaternary(sig, OPCODES, "WSIGMOID");
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return primaryLikeProfile(inFTypeCandidates);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (!hasExpectedArity(inFTypes, EXPECTED_ARITY))
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType x = typeAt(inFTypes, 0);
      if (x == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (x == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (!isFederatedLike(x))
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      Guard.Result guard = Guard.eval(sig);
      return guardAwareFout(sig, x, ReasonCode.OK, guard);
    }
  }

  public static final class ContainsRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.CONTAINS.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (inFTypes == null || inFTypes.isEmpty())
        return scalarCaps(sig, ExecType.CP, ReasonCode.ARITY_MISMATCH);

      FType x = typeAt(inFTypes, 0);
      if (x == null)
        return scalarCaps(sig, ExecType.CP, ReasonCode.NO_FED_INPUT);
      if (x == FType.BROADCAST)
        return scalarCaps(sig, ExecType.CP, ReasonCode.BROADCAST_CONSTRAINT);
      if (!isFederatedLike(x))
        return scalarCaps(sig, ExecType.CP, ReasonCode.NO_FED_INPUT);
      return scalarCaps(sig, ExecType.FED, ReasonCode.OK);
    }
  }

  public static final class ReplaceRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.REPLACE.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();
      List<FType> inputs = candidates(inFTypeCandidates, 0);
      if (inputs.isEmpty())
        return FTypeProfile.empty();
      Set<FType> outs = new LinkedHashSet<>();
      boolean vectorHint = isVectorHint(hint);
      for (FType cand : inputs) {
        if (cand == FType.ROW || cand == FType.COL || cand == FType.PART || cand == FType.FULL)
          outs.add(cand);
        else if (cand == FType.BROADCAST && vectorHint)
          outs.add(FType.BROADCAST);
      }
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (inFTypes == null || inFTypes.isEmpty())
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (in == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (!isFederatedLike(in))
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      Guard.Result guard = Guard.eval(sig);
      return guardAwareFout(sig, in, ReasonCode.OK, guard);
    }
  }

  public static final class RmemptyRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.RMEMPTY.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();
      List<FType> inputs = candidates(inFTypeCandidates, 0);
      if (inputs.isEmpty())
        return FTypeProfile.empty();
      Set<FType> outs = new LinkedHashSet<>();
      for (FType cand : inputs) {
        if (cand == FType.ROW || cand == FType.COL || cand == FType.PART || cand == FType.FULL)
          outs.add(cand);
      }
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (inFTypes == null || inFTypes.isEmpty())
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (in == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (!isFederatedLike(in))
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      Guard.Result guard = Guard.eval(sig);
      return guardAwareFout(sig, in, ReasonCode.OK, guard);
    }
  }

  public static final class RexpandRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.REXPAND.toString());
    private static final String ATTR_REXPAND_DIR = "rexpand.dir";

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();
      List<FType> inputs = candidates(inFTypeCandidates, 0);
      if (inputs.isEmpty())
        return FTypeProfile.empty();
      Set<FType> outs = new LinkedHashSet<>();
      boolean vectorHint = isVectorHint(hint);
      for (FType cand : inputs) {
        if (cand == FType.ROW || cand == FType.COL || cand == FType.PART || cand == FType.FULL)
          outs.add(cand);
        else if (cand == FType.BROADCAST && vectorHint)
          outs.add(FType.BROADCAST);
      }
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (inFTypes == null || inFTypes.isEmpty())
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (in == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (in != FType.ROW)
        return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);

      FType outAxis = in;
      String dir = attrValue(sig, ATTR_REXPAND_DIR);
      if (dir != null && dir.equalsIgnoreCase("rows"))
        outAxis = FType.COL;

      Guard.Result guard = Guard.eval(sig);
      return guardAwareFout(sig, outAxis, ReasonCode.OK, guard);
    }
  }

  public static final class WeightedUnaryMMRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.WUMM.toString());
    private static final int EXPECTED_ARITY = 3;

    @Override public OpCategory category() { return OpCategory.QUATERNARY; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return matchesWeightedQuaternary(sig, OPCODES, "WUMM");
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      // Restrict profile outputs to ROW/COL axes only (no FULL/PART)
      List<FType> xCands = candidates(inFTypeCandidates, 0);
      Set<FType> outs = new LinkedHashSet<>();
      if (xCands.contains(FType.ROW))
        outs.add(FType.ROW);
      if (xCands.contains(FType.COL))
        outs.add(FType.COL);
      return outs.isEmpty() ? FTypeProfile.empty() : FTypeProfile.ofOutput(new ArrayList<>(outs));
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      final int EXPECTED_ARITY = 3;
      if (!hasExpectedArity(inFTypes, EXPECTED_ARITY))
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType x = typeAt(inFTypes, 0);
      if (x == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (x == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (x == FType.FULL || x == FType.PART)
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.PARTITION_FORBIDDEN)
            .detail(WUMM_X_AXIS_ONLY_DETAIL)
            .build();

      // X is ROW or COL
      Guard.Result guard = Guard.eval(sig);
      if (guard != null && guard.isFail())
        return guardFallbackBuilder(sig, guard).build();

      FType axis = x; // output axis matches X
      OpCaps.Builder b = OpCaps.newBuilder()
          .category(sig.category())
          .opcode(sig.opcode())
          .exec(ExecType.FED)
          .placement(FederatedOutput.FOUT)
          .fout(true, axis)
          .reason(ReasonCode.OK);

      // Guard notes
      if (guard == null || guard.isUnknown())
        b.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
      else
        appendGuardPassNote(b, guard);

      // Alignment / Broadcast notes
      FType u = typeAt(inFTypes, 1);
      FType v = typeAt(inFTypes, 2);
      if (x == FType.ROW) {
        if (u == FType.ROW)
          b.note(ReasonCode.ALIGNED_HINT, "U aligned to ROW");
        else
          b.note(ReasonCode.BROADCAST_OR_ALIGNED_ROW, "U broadcast-sliced");
        b.note(ReasonCode.INFO, "V broadcast");
      }
      else if (x == FType.COL) {
        if (v == FType.COL)
          b.note(ReasonCode.ALIGNED_HINT, "V aligned to COL");
        else
          b.note(ReasonCode.BROADCAST_OR_ALIGNED_COL, "V broadcast-sliced");
        b.note(ReasonCode.INFO, "U broadcast");
      }

      return b.build();
    }
  }

  public static final class WeightedDivMMRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.WDIVMM.toString());
    private static final int EXPECTED_ARITY = 4;

    @Override public OpCategory category() { return OpCategory.QUATERNARY; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return matchesWeightedQuaternary(sig, OPCODES, "WDIVMM");
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return primaryLikeProfile(inFTypeCandidates);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (!hasExpectedArity(inFTypes, EXPECTED_ARITY))
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType x = typeAt(inFTypes, 0);
      if (x == null)
        return cpCaps(sig, ReasonCode.MISSING_IN_FTYPE);
      if (x == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (!isFederatedLike(x))
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      Integer baseType = parseBaseType(attrValue(sig, ATTR_WDIVMM_BASE_TYPE));
      if (baseType == null)
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);

      if (x == FType.FULL)
        return cpFoutCaps(sig, x, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY,
            WDIVMM_NATIVE_X_AXIS_DETAIL);
      if (x == FType.PART)
        return cpCaps(sig, ReasonCode.PARTITION_FORBIDDEN);

      Guard.Result guard = Guard.eval(sig);
      if (isBasicBaseType(baseType))
        return guardAwareFout(sig, x, ReasonCode.OK, guard);

      if (!isLeftBaseType(baseType) && !isRightBaseType(baseType))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);

      if (axisPreserved(x, hint))
        return guardAwareFout(sig, x, ReasonCode.OK, guard);

      return fedLocalWithDetail(sig, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY, WDIVMM_ALIGN_DETAIL);
    }
  }

  /**
   * Element-wise unary builtins executed per-partition.
   * Runtime parity: UnaryMatrixFEDInstruction.java:88-110 (callInstruction + setOutputFedMapping).
   * The opcode list intentionally stays conservative; extend it as additional builtins
   * gain verified runtime coverage.
   */
  public static final class UnaryElemwiseRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        OpOp1.EXP.toString(),
        OpOp1.LOG.toString(),
        OpOp1.LOG_NZ.toString(),
        OpOp1.SQRT.toString(),
        OpOp1.ABS.toString(),
        OpOp1.ROUND.toString(),
        OpOp1.FLOOR.toString(),
        OpOp1.CEIL.toString(),
        OpOp1.SIN.toString(),
        OpOp1.COS.toString(),
        OpOp1.TAN.toString(),
        OpOp1.ASIN.toString(),
        OpOp1.ACOS.toString(),
        OpOp1.ATAN.toString(),
        OpOp1.SINH.toString(),
        OpOp1.COSH.toString(),
        OpOp1.TANH.toString(),
        OpOp1.SIGN.toString(),
        OpOp1.SIGMOID.toString(),
        OpOp1.SPROP.toString(),
        Opcodes.PLOGP.toString(),
        OpOp1.ISNA.toString(),
        OpOp1.ISNAN.toString(),
        OpOp1.ISINF.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();
      List<FType> inputs = candidates(inFTypeCandidates, 0);
      if (inputs.isEmpty())
        return FTypeProfile.empty();
      Set<FType> outs = new LinkedHashSet<>();
      for (FType cand : inputs) {
        if (cand == FType.ROW || cand == FType.COL || cand == FType.PART || cand == FType.FULL)
          outs.add(cand);
      }
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!supports(sig))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (!hasExpectedArity(inFTypes, 1))
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (in == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (!isFederatedLike(in))
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      Guard.Result guard = Guard.eval(sig);
      return guardAwareFout(sig, in, ReasonCode.OK, guard);
    }
  }

  /**
   * UCUM builtins (row-federated cumulative ops).
   * Runtime parity: UnaryMatrixFEDInstruction.java:94-156 (row-only cumulative path) and
   * UnaryFEDInstruction.java:116-120 (COL inputs rejected). Spark routes these via bcumoff*
   * (UnaryFEDInstruction.java:189-191 + CumulativeOffsetRule), so a missing SP opcode here
   * is not a contradiction.
   */
  public static final class UnaryCumulativeRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.UCUMKP.toString(),
        Opcodes.UCUMM.toString(),
        Opcodes.UCUMKPM.toString(),
        Opcodes.UCUMMIN.toString(),
        Opcodes.UCUMMAX.toString());
    private static final String UCUMKPP_STAR_DETAIL = "ucumk+* → n×1 result; fed ranges updated";

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();
      List<FType> inputs = candidates(inFTypeCandidates, 0);
      if (inputs.isEmpty())
        return FTypeProfile.empty();

      String opcode = normalizedOpcode(sig);
      boolean allowCol = !Opcodes.UCUMKPM.toString().equals(opcode);
      Set<FType> outs = new LinkedHashSet<>();
      if (inputs.contains(FType.ROW))
        outs.add(FType.ROW);
      if (allowCol && inputs.contains(FType.COL))
        outs.add(FType.COL);
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!supports(sig))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (!hasExpectedArity(inFTypes, 1))
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (in == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);

      String opcode = normalizedOpcode(sig);
      Guard.Result guard = Guard.eval(sig);
      if (in == FType.COL) {
        if (Opcodes.UCUMKPM.toString().equals(opcode))
          return cpCaps(sig, ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME);
        return guardAwareFout(sig, in, ReasonCode.OK, guard);
      }
      if (in != FType.ROW)
        return cpCaps(sig, reasonForNonAxis(in));
      if (Opcodes.UCUMKPM.toString().equals(opcode))
        return rowGuardAwareFout(sig, guard, UCUMKPP_STAR_DETAIL);
      return rowGuardAwareFout(sig, guard, null);
    }

    private static OpCaps rowGuardAwareFout(OpSig sig, Guard.Result guard, String detail) {
      if (guard != null && guard.isFail())
        return guardFallbackBuilder(sig, guard).build();

      OpCaps.Builder builder = OpCaps.newBuilder()
          .category(sig != null ? sig.category() : OpCategory.OTHER)
          .opcode(sig != null ? sig.opcode() : "")
          .exec(ExecType.FED)
          .placement(FederatedOutput.FOUT)
          .fout(true, FType.ROW)
          .reason(ReasonCode.OK);
      if (detail != null && !detail.isBlank())
        builder.detail(detail);
      if (guard == null || guard.isUnknown())
        builder.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
      else
        appendGuardPassNote(builder, guard);
      return builder.build();
    }
  }

  /**
   * Reorg ops (transpose/rev/roll/diag). Runtime parity:
   * ReorgFEDInstruction.java:197-282 (ROW/COL only; PART/FULL rejected).
   */
  public static final class ReorgUnaryRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        ReOrgOp.TRANS.toString(),
        ReOrgOp.DIAG.toString(),
        ReOrgOp.REV.toString(),
        ReOrgOp.ROLL.toString());
    private static final String REORG_AXIS_ONLY_DETAIL =
        "ReorgFEDInstruction supports only ROW or COL partitioned input";

    @Override public OpCategory category() { return OpCategory.REORG; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();
      List<FType> inputs = candidates(inFTypeCandidates, 0);
      if (inputs.isEmpty())
        return FTypeProfile.empty();

      String opcode = normalizedOpcode(sig);
      Set<FType> outs = new LinkedHashSet<>();
      boolean isTrans = ReOrgOp.TRANS.toString().equals(opcode);
      boolean isRev = ReOrgOp.REV.toString().equals(opcode);
      boolean isRoll = ReOrgOp.ROLL.toString().equals(opcode);
      boolean isDiag = ReOrgOp.DIAG.toString().equals(opcode);
      if (isTrans) {
        if (inputs.contains(FType.ROW))
          outs.add(FType.COL);
        if (inputs.contains(FType.COL))
          outs.add(FType.ROW);
        if (inputs.contains(FType.FULL))
          outs.add(FType.FULL);
        if (inputs.contains(FType.BROADCAST))
          outs.add(FType.BROADCAST);
      }
      else if (isRev || isRoll || isDiag) {
        if (inputs.contains(FType.ROW))
          outs.add(FType.ROW);
        if (inputs.contains(FType.COL))
          outs.add(FType.COL);
        if (inputs.contains(FType.FULL))
          outs.add(FType.FULL);
        if (inputs.contains(FType.BROADCAST))
          outs.add(FType.BROADCAST);
      }
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!supports(sig))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);

      String opcode = normalizedOpcode(sig);
      boolean isTrans = ReOrgOp.TRANS.toString().equals(opcode);
      boolean isRev = ReOrgOp.REV.toString().equals(opcode);
      boolean isRoll = ReOrgOp.ROLL.toString().equals(opcode);
      boolean isDiag = ReOrgOp.DIAG.toString().equals(opcode);
      int inCount = (inFTypes == null) ? 0 : inFTypes.size();
      if (inCount == 0)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);
      if (isRoll) {
        if (inCount > 2)
          return cpCaps(sig, ReasonCode.ARITY_MISMATCH);
        if (inCount == 2 && sig.inputKind(1) != OpSig.InputKind.SCALAR)
          return cpCaps(sig, ReasonCode.OP_SHAPE_INCOMPATIBLE);
      }
      else if (inCount != 1)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (in == FType.PART)
        return axisOnlyCp(sig);
      // FULL/BROADCAST are runtime-supported and keep their mapping under transpose/rev/roll/diag.
      if (!(isAxis(in) || in == FType.FULL || in == FType.BROADCAST))
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      FType outAxis;
      if (in == FType.FULL || in == FType.BROADCAST) {
        outAxis = in;
      }
      else if (isTrans) {
        outAxis = (in == FType.ROW) ? FType.COL : FType.ROW;
      }
      else if (isRev || isRoll || isDiag) {
        outAxis = in;
      }
      else {
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      }
      Guard.Result guard = Guard.eval(sig);
      if (guard != null && guard.isFail())
        return guardFallbackBuilder(sig, guard).build();

      OpCaps.Builder builder = OpCaps.newBuilder()
          .category(sig.category())
          .opcode(sig.opcode())
          .exec(ExecType.FED)
          .placement(FederatedOutput.FOUT)
          .fout(true, outAxis)
          .reason(ReasonCode.OK);

      String infoNote = infoNoteFor(opcode, in);
      if (infoNote != null)
        builder.note(ReasonCode.INFO, infoNote);

      if (guard == null || guard.isUnknown())
        builder.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
      else
        appendGuardPassNote(builder, guard);

      return builder.build();
    }

    private static OpCaps axisOnlyCp(OpSig sig) {
      return OpCaps.newBuilder()
          .category(sig.category())
          .opcode(sig.opcode())
          .exec(ExecType.CP)
          .placement(FederatedOutput.LOUT)
          
          .reason(ReasonCode.PARTITION_FORBIDDEN)
          .detail(REORG_AXIS_ONLY_DETAIL)
          .build();
    }

    private static String infoNoteFor(String opcode, FType inAxis) {
      if (ReOrgOp.TRANS.toString().equals(opcode)) {
        if (inAxis == FType.ROW || inAxis == FType.COL)
          return "axis flipped ROW↔COL";
        return "mapping preserved (FULL/BROADCAST)";
      }
      if (ReOrgOp.REV.toString().equals(opcode))
        return (inAxis == FType.ROW) ? "ROW mapping reversed on workers" : null;
      if (ReOrgOp.ROLL.toString().equals(opcode))
        return "roll shift applied";
      if (ReOrgOp.DIAG.toString().equals(opcode))
        return "diag V2M/M2V shape handled at runtime";
      return null;
    }
  }

  public static final class ReshapeRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(ReOrgOp.RESHAPE.toString());
    private static final String ATTR_BYROW = "reshape.byrow";
    private static final String NOTE_GLOBAL_CELLS =
        "global cells must match (inRows*inCols == rows*cols)";
    private static final String NOTE_DIVISIBLE_COLS =
        "per-partition cell count must be divisible by cols";
    private static final String NOTE_DIVISIBLE_ROWS =
        "per-partition cell count must be divisible by rows";
    private static final String AXIS_UNKNOWN_DETAIL =
        "reshape.byrow not provided; cannot determine FOUT axis at compile time";

    @Override public OpCategory category() { return OpCategory.RESHAPE; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      String byRowAttr = attrValue(sig, ATTR_BYROW);
      if ("true".equalsIgnoreCase(byRowAttr))
        return FTypeProfile.ofOutput(List.of(FType.ROW));
      if ("false".equalsIgnoreCase(byRowAttr))
        return FTypeProfile.ofOutput(List.of(FType.COL));
      return FTypeProfile.ofOutput(List.of(FType.ROW, FType.COL));
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!supports(sig))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (inFTypes == null || inFTypes.isEmpty())
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (in == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (!isFederatedLike(in))
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      String byRowAttr = attrValue(sig, ATTR_BYROW);
      if ("true".equalsIgnoreCase(byRowAttr)) {
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.FED)
            .placement(FederatedOutput.FOUT)
            .fout(true, FType.ROW)
            .reason(ReasonCode.OK)
            .note(ReasonCode.BROADCAST_OR_ALIGNED_ROW, NOTE_DIVISIBLE_COLS)
            .note(ReasonCode.INFO, NOTE_GLOBAL_CELLS)
            .build();
      }
      if ("false".equalsIgnoreCase(byRowAttr)) {
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.FED)
            .placement(FederatedOutput.FOUT)
            .fout(true, FType.COL)
            .reason(ReasonCode.OK)
            .note(ReasonCode.BROADCAST_OR_ALIGNED_COL, NOTE_DIVISIBLE_ROWS)
            .note(ReasonCode.INFO, NOTE_GLOBAL_CELLS)
            .build();
      }

      return OpCaps.newBuilder()
          .category(sig.category())
          .opcode(sig.opcode())
          .exec(ExecType.FED)
          .placement(FederatedOutput.LOUT)
          
          .reason(ReasonCode.INFO)
          .detail(AXIS_UNKNOWN_DETAIL)
          .build();
    }
  }

  public static final class ReblockRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(Opcodes.RBLK.toString());
    private static final String NOTE_METADATA = "requires input metadata (format) at runtime";
    private static final String NOTE_BLOCKSIZE = "blocksize is updated to target blen";

    @Override public OpCategory category() { return OpCategory.REORG; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      List<FType> xs = candidates(inFTypeCandidates, 0);
      Set<FType> outs = new LinkedHashSet<>();
      if (xs.contains(FType.ROW))
        outs.add(FType.ROW);
      if (xs.contains(FType.COL))
        outs.add(FType.COL);
      if (xs.contains(FType.PART))
        outs.add(FType.PART);
      if (xs.contains(FType.FULL))
        outs.add(FType.FULL);
      return outs.isEmpty() ? FTypeProfile.empty() : FTypeProfile.ofOutput(new ArrayList<>(outs));
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!supports(sig))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (inFTypes == null || inFTypes.isEmpty())
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (in == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (!isFederatedLike(in))
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      return OpCaps.newBuilder()
          .category(sig.category())
          .opcode(sig.opcode())
          .exec(ExecType.FED)
          .placement(FederatedOutput.FOUT)
          .fout(true, in)
          .reason(ReasonCode.OK)
          .note(ReasonCode.INFO, NOTE_METADATA)
          .note(ReasonCode.INFO, NOTE_BLOCKSIZE)
          .build();
    }
  }

  public static final class RightIndexRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.RIGHT_INDEX.toString());

    @Override public OpCategory category() { return OpCategory.INDEXING; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      List<FType> mainInput = candidates(inFTypeCandidates, 0);
      if (mainInput.isEmpty())
        return FTypeProfile.empty();

      Set<FType> outs = new LinkedHashSet<>();
      if (mainInput.contains(FType.ROW))
        outs.add(FType.ROW);
      if (mainInput.contains(FType.COL))
        outs.add(FType.COL);
      if (mainInput.contains(FType.PART))
        outs.add(FType.PART);
      if (mainInput.contains(FType.FULL))
        outs.add(FType.FULL);
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (inFTypes == null || inFTypes.isEmpty())
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.MISSING_IN_FTYPE);
      if (!isFederatedLike(in))
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      for (int i = 1; i < inFTypes.size(); i++) {
        FType extra = typeAt(inFTypes, i);
        if (isFederatedLike(extra))
          return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      }

      if (in == FType.PART)
        return fedFoutCaps(sig, FType.PART, ReasonCode.OK);

      return fedFoutCaps(sig, in, ReasonCode.OK);
    }
  }

  public static final class LeftIndexRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.LEFT_INDEX.toString(),
        Opcodes.MAPLEFTINDEX.toString());

    @Override public OpCategory category() { return OpCategory.INDEXING; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      List<FType> lhs = candidates(inFTypeCandidates, 0);
      if (lhs.isEmpty())
        return FTypeProfile.empty();

      Set<FType> outs = new LinkedHashSet<>();
      if (lhs.contains(FType.ROW))
        outs.add(FType.ROW);
      if (lhs.contains(FType.COL))
        outs.add(FType.COL);
      if (lhs.contains(FType.PART))
        outs.add(FType.PART);
      if (lhs.contains(FType.FULL))
        outs.add(FType.FULL);
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (inFTypes == null || inFTypes.size() < 2)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType lhs = typeAt(inFTypes, 0);
      FType rhs = typeAt(inFTypes, 1);

      if (lhs == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (!isFederatedLike(lhs))
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      if (lhs == FType.PART)
        return fedFoutCaps(sig, FType.PART, ReasonCode.OK);

      if (lhs == FType.FULL)
        return fedFoutCaps(sig, FType.FULL, ReasonCode.OK);

      boolean rhsIsScalar = sig.inputKind(1) == OpSig.InputKind.SCALAR;
      if (rhsIsScalar)
        return fedFoutCaps(sig, lhs, ReasonCode.OK);

      if (rhs == null || rhs == FType.BROADCAST)
        return fedFoutCaps(sig, lhs, ReasonCode.OK);

      if (isFederatedLike(rhs)) {
        boolean rowAligned = matchesAxis(lhs, FType.ROW) && matchesAxis(rhs, FType.ROW);
        boolean colAligned = matchesAxis(lhs, FType.COL) && matchesAxis(rhs, FType.COL);
        if (rowAligned || colAligned)
          return guardAwareFout(sig, lhs, ReasonCode.OK, Guard.eval(sig));
        return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
      }

      return cpCaps(sig, ReasonCode.NO_FED_INPUT);
    }
  }

  /** Variable cast (matrix <-> frame). */
  public static final class CastRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.CAST_AS_FRAME_VAR.toString(),
        Opcodes.CAST_AS_MATRIX.toString());

    @Override public OpCategory category() { return OpCategory.VARIABLE_CAST; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      if (sig == null || sig.category() != category())
        return false;
      String opcode = normalizedOpcode(sig);
      return OPCODES.contains(opcode);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      List<FType> inputs = candidates(inFTypeCandidates, 0);
      if (inputs.isEmpty())
        return FTypeProfile.empty();

      Set<FType> outs = new LinkedHashSet<>();
      if (inputs.contains(FType.ROW))
        outs.add(FType.ROW);
      if (inputs.contains(FType.COL))
        outs.add(FType.COL);
      if (inputs.contains(FType.PART))
        outs.add(FType.PART);
      if (inputs.contains(FType.FULL))
        outs.add(FType.FULL);
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (inFTypes == null || inFTypes.size() != 1)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      // Smoke scenarios:
      // in=ROW -> ExecType.FED + FederatedOutput.FOUT(ROW) + OK
      // in=BROADCAST -> ExecType.CP + FederatedOutput.LOUT + BROADCAST_CONSTRAINT
      // in=FULL -> ExecType.FED + FederatedOutput.FOUT(FULL) + OK
      // in=LOCAL -> ExecType.CP + FederatedOutput.LOUT + NO_FED_INPUT

      if (in == FType.BROADCAST) {
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.BROADCAST_CONSTRAINT)
            .detail("broadcast input not supported by CastFEDInstruction")
            .build();
      }

      switch (in) {
        case ROW:
          return fedFoutCaps(sig, FType.ROW, ReasonCode.OK);
        case COL:
          return fedFoutCaps(sig, FType.COL, ReasonCode.OK);
        case PART:
          return fedFoutCaps(sig, FType.PART, ReasonCode.OK);
        case FULL:
          return fedFoutCaps(sig, FType.FULL, ReasonCode.OK);
        default:
          return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      }
    }
  }

  /** Unary cast to frame (castdtf). Mirrors CastFEDInstruction axis handling. */
  public static final class UnaryCastToFrameRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(OpOp1.CAST_AS_FRAME.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      List<FType> inputs = candidates(inFTypeCandidates, 0);
      if (inputs.isEmpty())
        return FTypeProfile.empty();

      Set<FType> outs = new LinkedHashSet<>();
      if (inputs.contains(FType.ROW))
        outs.add(FType.ROW);
      if (inputs.contains(FType.COL))
        outs.add(FType.COL);
      if (inputs.contains(FType.PART))
        outs.add(FType.PART);
      if (inputs.contains(FType.FULL))
        outs.add(FType.FULL);
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!hasExpectedArity(inFTypes, 1))
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      // Broadcast is rejected by CastFEDInstruction, so force CP/LOUT.
      if (in == FType.BROADCAST) {
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            .reason(ReasonCode.BROADCAST_CONSTRAINT)
            .detail("broadcast input not supported by CastFEDInstruction")
            .build();
      }

      if (!isFederatedLike(in))
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      return fedFoutCaps(sig, in, ReasonCode.OK);
    }
  }

  /** Variable write rule modeling federated side effects. */
  public static final class VariableWriteRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(Opcodes.WRITE.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      if (sig == null || sig.category() != category())
        return false;
      return OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      int arity = (inFTypes == null) ? 0 : inFTypes.size();
      if (arity < 2)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      String destAttr = attrValue(sig, "var.write.federated");
      boolean federatedDest = destAttr != null && destAttr.equalsIgnoreCase("true");
      if (!federatedDest)
        return cpCaps(sig, ReasonCode.NOT_IMPLEMENTED);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (in == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (in == FType.ROW || in == FType.COL || in == FType.PART || in == FType.FULL)
        return fedLocalWithDetail(sig, ReasonCode.OK, FED_WRITE_DETAIL);
      return cpCaps(sig, ReasonCode.NO_FED_INPUT);
    }
  }

  /** Transform Encode (frame -> matrix + meta). Primary output is matrix; meta is always local (LOUT). */
  public static final class TransformEncodeRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(Opcodes.TRANSFORMENCODE.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      List<FType> inputs = candidates(inFTypeCandidates, 0);
      if (inputs == null || inputs.isEmpty())
        return FTypeProfile.empty();

      Set<FType> outs = new LinkedHashSet<>();
      if (inputs.contains(FType.ROW))
        outs.add(FType.ROW);
      if (inputs.contains(FType.COL))
        outs.add(FType.COL);
      if (inputs.contains(FType.PART))
        outs.add(FType.PART);
      if (inputs.contains(FType.FULL))
        outs.add(FType.FULL);
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      try {
        if (inFTypes == null || inFTypes.size() != 1)
          return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

        FType in = typeAt(inFTypes, 0);
        if (in == null)
          return cpCaps(sig, ReasonCode.NO_FED_INPUT);

        if (in == FType.BROADCAST) {
          return OpCaps.newBuilder()
              .category(sig.category())
              .opcode(sig.opcode())
              .exec(ExecType.CP)
              .placement(FederatedOutput.LOUT)
              
              .reason(ReasonCode.BROADCAST_CONSTRAINT)
              .detail("broadcast input not supported by transformencode")
              .build();
        }

        if (in != FType.ROW && in != FType.COL && in != FType.PART && in != FType.FULL)
          return cpCaps(sig, ReasonCode.NO_FED_INPUT);

        Guard.Result guard = Guard.eval(sig);
        if (guard != null && guard.isFail())
          return guardFallbackBuilder(sig, guard).build();

        OpCaps.Builder builder = OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.FED)
            .placement(FederatedOutput.FOUT)
            .fout(true, in)
            .reason(ReasonCode.OK)
            .detail("second output (meta) is LOUT");
        if (guard == null || guard.isUnknown())
          builder.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
        else
          appendGuardPassNote(builder, guard);
        return builder.build();
      }
      catch (Throwable t) {
        return cpCaps(sig, ReasonCode.RULE_ERROR);
      }
    }
  }

  /** Aggregate ternary federated instructions (tak+*, tack+*). */
  public static final class AggTernaryRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.TAKPM.toString(),
        Opcodes.TACKPM.toString());

    @Override public OpCategory category() { return OpCategory.AGG_TERNARY; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null
          && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return profileOf(Set.of(FType.FULL));
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!OPCODES.contains(normalizedOpcode(sig)))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (inFTypes == null || inFTypes.size() != 3)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType x = defaultType(typeAt(inFTypes, 0));
      FType y = defaultType(typeAt(inFTypes, 1));
      FType z = defaultType(typeAt(inFTypes, 2));
      boolean scalarOut = isScalarHint(hint);

      if (x == FType.PART || y == FType.PART || z == FType.PART)
        return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);

      FType axis = null;
      if (matchesAxis(x, FType.ROW))
        axis = FType.ROW;
      else if (matchesAxis(x, FType.COL))
        axis = FType.COL;

      if (matchesAxis(y, FType.ROW)) {
        if (axis == null)
          axis = FType.ROW;
        else if (!matchesAxis(axis, FType.ROW))
          return cpCaps(sig, ReasonCode.UNALIGNED_OR_INSUFFICIENT_FED_INPUTS);
      }
      else if (matchesAxis(y, FType.COL)) {
        if (axis == null)
          axis = FType.COL;
        else if (!matchesAxis(axis, FType.COL))
          return cpCaps(sig, ReasonCode.UNALIGNED_OR_INSUFFICIENT_FED_INPUTS);
      }

      if (matchesAxis(z, FType.ROW)) {
        if (axis == null)
          axis = FType.ROW;
        else if (!matchesAxis(axis, FType.ROW))
          return cpCaps(sig, ReasonCode.UNALIGNED_OR_INSUFFICIENT_FED_INPUTS);
      }
      else if (matchesAxis(z, FType.COL)) {
        if (axis == null)
          axis = FType.COL;
        else if (!matchesAxis(axis, FType.COL))
          return cpCaps(sig, ReasonCode.UNALIGNED_OR_INSUFFICIENT_FED_INPUTS);
      }

      if (axis == null)
        return cpCaps(sig, ReasonCode.UNALIGNED_OR_INSUFFICIENT_FED_INPUTS);

      boolean xAxisFed = matchesAxis(x, axis);
      boolean yAxisFed = matchesAxis(y, axis);
      boolean zAxisFed = matchesAxis(z, axis);
      int axisFedCount = (xAxisFed ? 1 : 0) + (yAxisFed ? 1 : 0) + (zAxisFed ? 1 : 0);

      if (axisFedCount == 3)
        return fedLocalCaps(sig, ReasonCode.OK);

      if (axisFedCount == 2) {
        if (!xAxisFed)
          return cpCaps(sig, ReasonCode.UNALIGNED_OR_INSUFFICIENT_FED_INPUTS);
        if (!scalarOut)
          return cpCaps(sig, ReasonCode.NOT_IMPLEMENTED_FED_MATRIX_OUT);
        if (yAxisFed && !zAxisFed)
          return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT);
        return fedLocalCaps(sig, ReasonCode.OK);
      }

      if (axisFedCount == 1) {
        if (!scalarOut && xAxisFed && sig.inputKind(2) == OpSig.InputKind.MATRIX)
          return cpCaps(sig, ReasonCode.NOT_IMPLEMENTED_FED_MATRIX_OUT);
        return fedLocalCaps(sig, ReasonCode.OK);
      }

      return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
    }
  }

  /** Frame map ops (frame with scalar expression plus margin hint). */
  public static final class FrameMapRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(OpOp3.MAP.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      if (sig == null)
        return false;
      if (!OPCODES.contains(normalizedOpcode(sig)))
        return false;
      if (sig.arity() != 3)
        return false;
      return frameInputIndex(sig) >= 0;
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      int frameIdx = frameInputIndex(sig);
      if (frameIdx < 0)
        return FTypeProfile.empty();
      List<FType> frameCandidates = candidates(inFTypeCandidates, frameIdx);
      if (frameCandidates.isEmpty())
        return FTypeProfile.empty();

      Set<FType> outs = new LinkedHashSet<>();
      for (FType cand : frameCandidates) {
        if (cand == FType.ROW || cand == FType.COL || cand == FType.FULL || cand == FType.PART)
          outs.add(cand);
      }
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!OPCODES.contains(normalizedOpcode(sig)))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (inFTypes == null || inFTypes.size() != 3)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      int frameIdx = frameInputIndex(sig);
      if (frameIdx < 0)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType frameType = typeAt(inFTypes, frameIdx);
      if (!isFederatedLike(frameType))
        return marginMismatch(sig);

      int margin = parseMargin(attrValue(sig, ATTR_MAP_MARGIN));
      if (!marginMatches(margin, frameType))
        return marginMismatch(sig);

      Guard.Result guard = Guard.eval(sig);
      return guardAwareFout(sig, frameType, ReasonCode.OK, guard);
    }

    private static int frameInputIndex(OpSig sig) {
      if (sig == null || sig.arity() != 3)
        return -1;
      OpSig.InputKind in0 = sig.inputKind(0);
      OpSig.InputKind in1 = sig.inputKind(1);
      OpSig.InputKind in2 = sig.inputKind(2);
      if (in2 != OpSig.InputKind.SCALAR)
        return -1;
      if (in0 == OpSig.InputKind.FRAME && in1 == OpSig.InputKind.SCALAR)
        return 0;
      if (in1 == OpSig.InputKind.FRAME && in0 == OpSig.InputKind.SCALAR)
        return 1;
      return -1;
    }

    private static int parseMargin(String raw) {
      if (raw == null || raw.isBlank())
        return 0;
      try {
        return Integer.parseInt(raw.trim());
      } catch (NumberFormatException nfe) {
        return 0;
      }
    }

    private static boolean marginMatches(int margin, FType frameType) {
      if (margin == 0)
        return true;
      if (margin == 1)
        return frameType == FType.ROW;
      if (margin == 2)
        return frameType == FType.COL;
      return false;
    }

    private static OpCaps marginMismatch(OpSig sig) {
      return OpCaps.newBuilder()
          .category(sig.category())
          .opcode(sig.opcode())
          .exec(ExecType.CP)
          .placement(FederatedOutput.LOUT)
          
          .reason(ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY)
          .detail("margin mismatch or non-federated frame")
          .build();
    }
  }

  /** Element-wise ternary ops (ifelse, +*, -*). */
  public static final class TernaryElemwiseRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        OpOp3.IFELSE.toString(),
        OpOp3.PLUS_MULT.toString(),
        OpOp3.MINUS_MULT.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null
          && OPCODES.contains(normalizedOpcode(sig))
          && sig.arity() == 3;
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      Set<FType> outs = new LinkedHashSet<>();
      for (int i = 0; i < 3; i++) {
        List<FType> cand = candidates(inFTypeCandidates, i);
        if (cand.contains(FType.ROW))
          outs.add(FType.ROW);
        if (cand.contains(FType.COL))
          outs.add(FType.COL);
        if (cand.contains(FType.PART))
          outs.add(FType.PART);
        if (cand.contains(FType.FULL))
          outs.add(FType.FULL);
      }
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!OPCODES.contains(normalizedOpcode(sig)))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (inFTypes == null || inFTypes.size() != 3)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      int leaderIdx = leadingFedIndex(inFTypes);
      if (leaderIdx < 0)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      FType leader = typeAt(inFTypes, leaderIdx);
      if (isOuterPattern(leader, inFTypes, leaderIdx, hint))
        return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);

      Guard.Result guard = Guard.eval(sig);
      return guardAwareFout(sig, leader, ReasonCode.OK, guard);
    }

    private static int leadingFedIndex(List<FType> types) {
      if (types == null)
        return -1;
      for (int i = 0; i < types.size(); i++) {
        if (isFederatedLike(typeAt(types, i)))
          return i;
      }
      return -1;
    }

    private static boolean isOuterPattern(FType leader, List<FType> types, int leaderIdx, ShapeHint hint) {
      if (!matchesAxis(leader, FType.ROW) && !matchesAxis(leader, FType.COL))
        return false;
      for (int i = 0; i < types.size(); i++) {
        if (i == leaderIdx)
          continue;
        FType other = typeAt(types, i);
        if (isOuterLike(leader, other, hint))
          return true;
      }
      return false;
    }
  }

  /** Aggregate unary ops (sum/min/max/var, etc.). */
  public static final class AggUnaryRule extends BaseRule {
    private static final String ATTR_DIRECTION = "direction";
    private static final String ATTR_AGG_OP = "aggOp";

    private enum Dir { ROW, COL, ROWCOL }

    @Override public OpCategory category() { return OpCategory.AGG_UNARY; }
    @Override public Set<String> opcodes() { return Set.of(); }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null
          && sig.category() == category()
          && sig.attrs() != null
          && sig.attrs().containsKey(ATTR_DIRECTION);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();

      List<FType> inputs = candidates(inFTypeCandidates, 0);
      if (inputs.isEmpty())
        return FTypeProfile.empty();

      Dir dir = dirOf(sig);
      String agg = aggOf(sig);
      if ("VAR".equals(agg) || dir == Dir.ROWCOL || isScalarOutput(dir, hint))
        return FTypeProfile.empty();

      for (FType cand : inputs) {
        if (cand == null)
          continue;
        // BROADCAST/FULL are runtime-supported and preserve their mapping on output.
        if (cand == FType.BROADCAST || cand == FType.FULL)
          return FTypeProfile.ofOutput(List.of(cand));
        if (axisMatch(cand, dir))
          return FTypeProfile.ofOutput(List.of(cand));
      }
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);

      Dir dir = dirOf(sig);
      String agg = aggOf(sig);

      if ("VAR".equals(agg))
        return fedLocalCaps(sig, ReasonCode.VAR_REQUIRES_CONSOLIDATION);

      if (dir == Dir.ROWCOL)
        return fedLocalCaps(sig, ReasonCode.FULL_AGG_REQUIRES_CONSOLIDATION);

      if (isScalarOutput(dir, hint))
        return fedLocalCaps(sig, ReasonCode.SCALAR_CANNOT_BE_FEDERATED);

      // Runtime supports BROADCAST/FULL and preserves mapping semantics on output.
      if (in == FType.BROADCAST || in == FType.FULL)
        return fedFoutCaps(sig, in, ReasonCode.OK);

      if (!isAxis(in))
        return cpCaps(sig, ReasonCode.NON_ALIGNED_INPUT_FTYPE);

      if (axisMatch(in, dir))
        return fedFoutCaps(sig, in, ReasonCode.OK);

      // NOTE: Parameter swapping in uarimax/uarimin for column partitions is handled by runtime instructions.
      return fedLocalCaps(sig, ReasonCode.PARTITION_MISMATCH_PART_NOT_SUPPORTED);
    }

    private static Dir dirOf(OpSig sig) {
      String raw = (sig == null || sig.attrs() == null) ? null : sig.attrs().get(ATTR_DIRECTION);
      if (raw == null)
        return Dir.ROWCOL;
      try {
        return Dir.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException iae) {
        return Dir.ROWCOL;
      }
    }

    private static String aggOf(OpSig sig) {
      String raw = (sig == null || sig.attrs() == null) ? null : sig.attrs().get(ATTR_AGG_OP);
      return raw == null ? "SUM" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean axisMatch(FType in, Dir dir) {
      return (in == FType.ROW && dir == Dir.ROW) || (in == FType.COL && dir == Dir.COL);
    }

    private static boolean isScalarOutput(Dir dir, ShapeHint hint) {
      if (dir == Dir.ROWCOL)
        return true;
      if (hint == null)
        return false;
      if (dir == Dir.ROW)
        return hint.rows() == 1;
      if (dir == Dir.COL)
        return hint.cols() == 1;
      return false;
    }

  }

  // --- Central Moment (AGG_UNARY -> local scalar) -----------------------------------------------
  public static final class CentralMomentRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        OpOp3.MOMENT.toString());

    @Override public OpCategory category() { return OpCategory.AGG_UNARY; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      if (sig == null || sig.category() != category())
        return false;
      final String op = normalizedOpcode(sig);
      return OPCODES.contains(op) || hasCentralMomentHint(sig);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      final String op = normalizedOpcode(sig);
      final boolean opcodeMatch = OPCODES.contains(op);
      final boolean hintMatch = hasCentralMomentHint(sig);
      if (!opcodeMatch && !hintMatch)
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (inFTypes == null || inFTypes.isEmpty() || typeAt(inFTypes, 0) == null)
        return cpCaps(sig, ReasonCode.MISSING_IN_FTYPE);

      final FType x = typeAt(inFTypes, 0);
      final FType w = (inFTypes.size() > 1) ? typeAt(inFTypes, 1) : null;
      final boolean xIsFed = isFederatedLike(x);
      if (!xIsFed)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (isFederatedLike(w))
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);

      return fedLocalCaps(sig, ReasonCode.OK);
    }

    private static boolean hasCentralMomentHint(OpSig sig) {
      final Map<String,String> attrs = (sig == null) ? null : sig.attrs();
      if (attrs == null || attrs.isEmpty())
        return false;
      final String aggOp = attrs.getOrDefault("aggOp", "");
      final String opAttr = attrs.getOrDefault("op", "");
      final String cmFlag = attrs.getOrDefault("cm", "");
      return "cm".equalsIgnoreCase(aggOp)
          || "centralmoment".equalsIgnoreCase(aggOp)
          || "centralmoment".equalsIgnoreCase(opAttr)
          || "true".equalsIgnoreCase(cmFlag);
    }
  }

  // --- Transpose-self matrix multiply (TSMM) ---------------------------------------------------
  public static final class TsmmRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(Opcodes.TSMM.toString());

    private static final String TSMM_AXIS_ONLY_DETAIL =
        "TSMM supports only LEFT with ROW or RIGHT with COL partitioned X (per TsmmFEDInstruction)";
    private static final String TSMM_AGG_NOTE =
        "per-partition Gram aggregated to driver";
    private static final String TSMM_FORCED_BC_NOTE =
        "forced FOUT via broadcasted output (runtime executes tsmm on worker(s) then broadcasts result)";

    @Override public OpCategory category() { return OpCategory.TSMM; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      // byOpcode가 우선, supports는 보조 확인용
      if (sig == null || sig.category() != category())
        return false;
      String op = normalizedOpcode(sig);
      if (Opcodes.TSMM.toString().equals(op))
        return true;
      String typ = attrValue(sig, "tsmm.type");
      return typ != null && (typ.equalsIgnoreCase("LEFT") || typ.equalsIgnoreCase("RIGHT"));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      // 기본: 빈 프로파일 (필요 시 BROADCAST 반환 가능)
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!hasExpectedArity(inFTypes, 1))
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType x = typeAt(inFTypes, 0);
      if (x == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (x == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (x == FType.PART) {
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.PARTITION_FORBIDDEN)
            .detail(TSMM_AXIS_ONLY_DETAIL)
            .build();
      }

      String typ = attrValue(sig, "tsmm.type");
      if (typ == null)
        typ = "LEFT";
      boolean left = "LEFT".equalsIgnoreCase(typ);
      boolean right = "RIGHT".equalsIgnoreCase(typ);
      if (!left && !right)
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);

      // FULL is only supported for TSMM when it represents a single federated range
      // (e.g., worker=1). Treat unknown as multi-range for safety.
      boolean fullSingle = x == FType.FULL
          && hint != null
          && hint.fullSinglePartition().orElse(false);

      if (x == FType.FULL && !fullSingle) {
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.PARTITION_FORBIDDEN)
            .detail(TSMM_AXIS_ONLY_DETAIL)
            .build();
      }

      boolean axisOK = (left && (x == FType.ROW || x == FType.FULL))
          || (right && (x == FType.COL || x == FType.FULL));
      if (!axisOK) {
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.UNSUPPORTED_ALIGNMENT)
            .detail(TSMM_AXIS_ONLY_DETAIL)
            .build();
      }

      boolean forceFout = "true".equalsIgnoreCase(attrValue(sig, "force_fout"))
          || "FORCED".equalsIgnoreCase(attrValue(sig, "tsmm.fedOut"));
      if (forceFout) {
        Guard.Result guard = Guard.eval(sig);
        if (guard != null && guard.isFail())
          return guardFallbackBuilder(sig, guard).build();

        OpCaps.Builder builder = OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.FED)
            .placement(FederatedOutput.FOUT)
            .fout(true, FType.BROADCAST)
            .reason(ReasonCode.OK)
            .note(ReasonCode.INFO, TSMM_FORCED_BC_NOTE);
        if (guard == null || guard.isUnknown())
          builder.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
        else
          appendGuardPassNote(builder, guard);
        return builder.build();
      }

      return OpCaps.newBuilder()
          .category(sig.category())
          .opcode(sig.opcode())
          .exec(ExecType.FED)
          .placement(FederatedOutput.LOUT)
          
          .reason(ReasonCode.OK)
          .note(ReasonCode.INFO, TSMM_AGG_NOTE)
          .build();
    }
  }

  /**
   * MMFEDInstruction mirror rule (cases A–E, compile-only).
   * A) COL×ROW aligned on COL_T,
   * B) Left ROW/PART,
   * C) Right ROW,
   * D) Left COL,
   * E) Other layouts fall back to CP/LOUT.
   */
  public static final class MMFedRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.MAPMM.toString(),
        Opcodes.CPMM.toString(),
        Opcodes.RMM.toString(),
        Opcodes.PMM.toString());
    private static final String ATTR_ALIGN = "align";
    private static final String ATTR_R_IS_VECTOR = "r_is_vector";
    private static final String ALIGN_COL_T = "COL_T";

    private enum TriState { TRUE, FALSE, UNKNOWN }

    @Override public OpCategory category() { return OpCategory.BINARY_MM; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null
          && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      List<FType> left = candidates(inFTypeCandidates, 0);
      if (left == null || left.isEmpty())
        return FTypeProfile.empty();
      Set<FType> outs = new LinkedHashSet<>();
      if (hasAxis(left, FType.ROW))
        outs.add(FType.ROW);
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (inFTypes == null || inFTypes.size() < 2)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType left = defaultType(typeAt(inFTypes, 0));
      FType right = defaultType(typeAt(inFTypes, 1));
      if (!isFederatedOperand(left) && !isFederatedOperand(right))
        return cpCaps(sig, ReasonCode.NOT_FEDERATED_INPUTS);

      boolean alignedColT = isAlignColT(sig);
      TriState rState = resolveRightVectorState(sig, hint);
      boolean vecKnownFalse = (rState == TriState.FALSE);
      boolean partOut = isPartOut(left, right, vecKnownFalse);

      if (left == FType.COL && right == FType.ROW) {
        if (!alignedColT)
          return fedLocalCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT);
        return fedLocalCaps(sig, ReasonCode.OK);
      }

      if (left == FType.ROW || left == FType.PART) {
        if (partOut)
          return fedLocalCaps(sig, ReasonCode.FOUT_DISALLOWED_FOR_PART_OUT);
        if (left == FType.ROW) {
          Guard.Result guard = Guard.eval(sig);
          return guardAwareFout(sig, FType.ROW, ReasonCode.OK, guard);
        }
        return fedLocalCaps(sig, ReasonCode.OK);
      }

      if (right == FType.ROW)
        return fedLocalCaps(sig, ReasonCode.OK);

      if (left == FType.COL)
        return fedLocalCaps(sig, ReasonCode.OK);

      ReasonCode fallback = supports(sig)
          ? ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY
          : ReasonCode.OPCODE_UNSUPPORTED;
      return cpCaps(sig, fallback);
    }

    private static boolean isFederatedOperand(FType t) {
      return t == FType.FULL || isTrueFederated(t);
    }

    private static boolean isPartOut(FType left, FType right, boolean vecKnownFalse) {
      return left == FType.PART || (vecKnownFalse && right == FType.PART);
    }

    private static TriState resolveRightVectorState(OpSig sig, ShapeHint hint) {
      String attr = attr(sig, ATTR_R_IS_VECTOR);
      if ("true".equalsIgnoreCase(attr))
        return TriState.TRUE;
      if ("false".equalsIgnoreCase(attr))
        return TriState.FALSE;
      if (hint != null && hint.colsB() == 1 && hint.colsB() >= 0)
        return TriState.TRUE;
      return TriState.UNKNOWN;
    }

    private static boolean isAlignColT(OpSig sig) {
      String align = attr(sig, ATTR_ALIGN);
      return align != null && ALIGN_COL_T.equalsIgnoreCase(align);
    }

    private static String attr(OpSig sig, String key) {
      if (sig == null || key == null)
        return null;
      Map<String,String> attrs = sig.attrs();
      if (attrs == null || attrs.isEmpty())
        return null;
      return attrs.get(key);
    }

  }

  /** Binary matrix multiply (mmult). */
  public static final class BinaryMMRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(Opcodes.MMULT.toString());
    private static final String OPCODE_MM = Opcodes.MMULT.toString();
    private static final String ATTR_INNER = "inner";
    private static final String ATTR_OUTER = "outer";
    private static final String ATTR_ALIGN = "align";
    private static final String ATTR_R_IS_VECTOR = "r_is_vector";
    private static final String ATTR_TSMM_TYPE = "tsmm.type";
    private static final String ALIGN_COL_T = "COL_T";
    private static final String TSMM_AXIS_ONLY_DETAIL =
        "TSMM supports only LEFT with ROW or RIGHT with COL partitioned X (per TsmmFEDInstruction)";

    @Override public OpCategory category() { return OpCategory.BINARY_MM; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      if (sig == null)
        return false;
      String opcode = normalizedOpcode(sig);
      if (OPCODE_MM.equals(opcode))
        return true;
      if (sig.category() != OpCategory.BINARY_MM)
        return false;
      return matchAttr(sig, ATTR_INNER, "MULT") && matchAttr(sig, ATTR_OUTER, "SUM");
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      List<FType> left = candidates(inFTypeCandidates, 0);
      List<FType> right = candidates(inFTypeCandidates, 1);
      if (!hasFederated(left) && !hasFederated(right))
        return FTypeProfile.empty();
      if (isTsmmType(attr(sig, ATTR_TSMM_TYPE)))
        return FTypeProfile.ofOutput(List.of(FType.BROADCAST));

      boolean leftHasRow = hasAxis(left, FType.ROW);
      boolean leftHasCol = hasAxis(left, FType.COL);
      boolean rightHasRow = hasAxis(right, FType.ROW);
      boolean alignColT = isAlignColT(sig);

      Set<FType> outs = new LinkedHashSet<>();
      if (leftHasRow && !(leftHasCol && rightHasRow && alignColT))
        outs.add(FType.ROW);

      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      String tsmmType = attr(sig, ATTR_TSMM_TYPE);
      if (isTsmmType(tsmmType)) {
        if (inFTypes == null || inFTypes.isEmpty())
          return cpCaps(sig, ReasonCode.ARITY_MISMATCH);
        return tsmmCaps(sig, normalize(typeAt(inFTypes, tsmmInputIndex(tsmmType))), tsmmType, hint);
      }

      FType left = normalize(typeAt(inFTypes, 0));
      FType right = normalize(typeAt(inFTypes, 1));

      // FULL represents a single-worker federated mapping. Runtime AggregateBinaryFEDInstruction can execute
      // FULL x local and local x FULL by broadcasting the local side to the single federated worker.
      //
      // Some planners (notably MinST) may conservatively model local/vector operands as BROADCAST
      // (rather than null) even when the operand can be provided locally. Treat BROADCAST like a
      // local operand for this special worker=1 FULL case to avoid planner/oracle divergence that
      // forces expensive CP fallbacks (e.g., l2svm/pca worker=1).
      // This change is shared (Oracle) and therefore applies fairly to both DP and MinST.
      boolean rightLocalLike = (right == null) || (right == FType.BROADCAST);
      boolean leftLocalLike = (left == null) || (left == FType.BROADCAST);
      if ((left == FType.FULL && rightLocalLike) || (right == FType.FULL && leftLocalLike))
        return fedLocalCaps(sig, ReasonCode.OK);

      if (!eligible(left, right))
        return cpCaps(sig, ReasonCode.NOT_FEDERATED_INPUTS);

      boolean rIsVector = attrBoolean(sig, ATTR_R_IS_VECTOR);

      boolean leftRowLike = isRowPartition(left);
      boolean leftColLike = left != null && left.isType(FType.COL);
      boolean leftStrictRow = left == FType.ROW;
      boolean rightIsRow = right == FType.ROW;
      boolean rightRowLike = right != null && right.isType(FType.ROW);
      boolean alignColT = isAlignColT(sig);
      boolean partOut = (left == FType.PART) || (!rIsVector && right == FType.PART);

      // Runtime AggregateBinaryFEDInstruction handles COL_T-aligned (left COL-like, right ROW-like)
      // by local aggregation; forcing FOUT here causes planner/runtime mismatch.
      if (leftColLike && rightRowLike && alignColT)
        return fedLocalCaps(sig, ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME);

      if (leftRowLike) {
        if (partOut)
          return fedLocalCaps(sig, ReasonCode.FOUT_DISALLOWED_FOR_PART_OUT);

        if (leftStrictRow) {
          Guard.Result guard = Guard.eval(sig);
          return guardAwareFout(sig, FType.ROW, ReasonCode.OK, guard);
        }

        return fedLocalCaps(sig, ReasonCode.OK);
      }

      if (left == FType.COL && rightIsRow) {
        return fedLocalCaps(sig, ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME);
      }

      if (rightIsRow)
        return fedLocalCaps(sig, ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME);

      return fedLocalCaps(sig, ReasonCode.OK);
    }

    private static OpCaps tsmmCaps(OpSig sig, FType in, String tsmmType, ShapeHint hint) {
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (in == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (in == FType.PART)
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            .reason(ReasonCode.PARTITION_FORBIDDEN)
            .detail(TSMM_AXIS_ONLY_DETAIL)
            .build();

      // FULL is only supported for TSMM when it represents a single federated range.
      // Treat unknown as multi-range for safety.
      boolean fullSingle = in == FType.FULL
          && hint != null
          && hint.fullSinglePartition().orElse(false);
      if (in == FType.FULL && !fullSingle)
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            .reason(ReasonCode.PARTITION_FORBIDDEN)
            .detail(TSMM_AXIS_ONLY_DETAIL)
            .build();

      boolean left = "LEFT".equalsIgnoreCase(tsmmType);
      boolean right = "RIGHT".equalsIgnoreCase(tsmmType);
      if (!left && !right)
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);

      boolean axisOK = (left && (in == FType.ROW || in == FType.FULL))
          || (right && (in == FType.COL || in == FType.FULL));
      if (!axisOK)
        return OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            .reason(ReasonCode.UNSUPPORTED_ALIGNMENT)
            .detail(TSMM_AXIS_ONLY_DETAIL)
            .build();

      return fedLocalCaps(sig, ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME);
    }

    private static boolean isTsmmType(String tsmmType) {
      return "LEFT".equalsIgnoreCase(tsmmType) || "RIGHT".equalsIgnoreCase(tsmmType);
    }

    private static int tsmmInputIndex(String tsmmType) {
      // AggBinaryOp.checkTransposeSelf(): LEFT is t(X)%*%X (input1 is X), RIGHT is X%*%t(X) (input0 is X).
      return "LEFT".equalsIgnoreCase(tsmmType) ? 1 : 0;
    }

    private static boolean hasFederated(List<FType> types) {
      if (types == null)
        return false;
      for (FType t : types) {
        // FULL represents a single-worker federated mapping (one range spans the full matrix).
        // Treat it as a federated input for profile inference; otherwise the oracle can
        // incorrectly return NOT_FEDERATED_INPUTS for worker=1 FULL cases and force CP fallbacks.
        if (t == FType.ROW || t == FType.COL || t == FType.PART || t == FType.FULL)
          return true;
      }
      return false;
    }

    private static boolean matchAttr(OpSig sig, String key, String expected) {
      if (sig == null || expected == null)
        return false;
      String val = attr(sig, key);
      return val != null && val.equalsIgnoreCase(expected);
    }

  private static FType normalize(FType t) {
    return t;
  }

    private static boolean eligible(FType left, FType right) {
      // FULL represents a single-worker federated mapping. Even without ROW/COL/PART partitioning,
      // runtime can execute matrix multiplication federated by broadcasting the other operand to
      // that single worker. Without treating FULL as eligible here, the oracle can return
      // NOT_FEDERATED_INPUTS for FULL×(ROW/COL/...) combinations, causing planner/oracle divergence
      // and expensive CP fallbacks (notably MinST worker=1 in kmeans/l2svm/pca).
      if (left == FType.FULL || right == FType.FULL)
        return true;
      if (isRowPartition(left) && isTrueFederated(left))
        return true;
      if (left == FType.COL && isTrueFederated(left))
        return true;
      return isRowPartition(right) && isTrueFederated(right);
    }

    private static boolean isRowPartition(FType t) {
      return t == FType.ROW || t == FType.PART;
    }

    private static boolean attrBoolean(OpSig sig, String key) {
      String raw = attr(sig, key);
      return raw != null && Boolean.parseBoolean(raw);
    }

    private static boolean isAlignColT(OpSig sig) {
      String align = attr(sig, ATTR_ALIGN);
      return align != null && ALIGN_COL_T.equalsIgnoreCase(align);
    }

    private static String attr(OpSig sig, String key) {
      if (sig == null || key == null)
        return null;
      if (sig.attrs() == null || sig.attrs().isEmpty())
        return null;
      return sig.attrs().get(key);
    }
  }

  /** MMChain FED compile-only rule (XtXv / XtwXv / XtXvy). */
  public static final class MMChainRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.MMCHAIN.toString(),
        Opcodes.MAPMMCHAIN.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      if (sig == null || sig.category() != category())
        return false;
      final String op = normalizedOpcode(sig);
      return OPCODES.contains(op);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      // MMChain result is always locally aggregated (GET + aggAdd).
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      // 0) opcode guard (defensive)
      if (sig == null || !OPCODES.contains(normalizedOpcode(sig)))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);

      // 1) arity guard: 2 (XtXv) or 3 (XtwXv|XtXvy)
      final int n = (inFTypes == null) ? 0 : inFTypes.size();
      if (n != 2 && n != 3) {
        return OpCaps.newBuilder()
            .category(sig != null ? sig.category() : OpCategory.OTHER)
            .opcode(sig != null ? sig.opcode() : "")
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.ARITY_MISMATCH)
            .detail("expected 2 or 3 inputs, got=" + n)
            .build();
      }

      // 2) main input X must be ROW-federated (runtime parser enforces this)
      final FType X = typeAt(inFTypes, 0);
      if (X == null)
        return cpCaps(sig, ReasonCode.NOT_FEDERATED_INPUTS);
      if (X != FType.ROW)
        return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT);

      // 3) valid FED pathway, but final output is local (LOUT) in all cases
      final OpCaps.Builder b = OpCaps.newBuilder()
          .category(sig != null ? sig.category() : OpCategory.OTHER)
          .opcode(sig != null ? sig.opcode() : "")
          .exec(ExecType.FED)
          .placement(FederatedOutput.LOUT)
          
          .reason(ReasonCode.OK);

      // v is always broadcast
      b.note(ReasonCode.INFO, "v broadcast");

      // weighted?
      final boolean weighted = isWeighted(sig, n);
      if (weighted) {
        final Boolean alignedHint = parseBoolean(attr(sig, "alignedW"));
        final FType wType = (n > 2) ? typeAt(inFTypes, 2) : null;
        if (Boolean.TRUE.equals(alignedHint) || wType == FType.ROW)
          b.note(ReasonCode.ALIGNED_HINT, "w aligned to ROW");
        else
          b.note(ReasonCode.INFO, "w broadcast-sliced");
      }
      return b.build();
    }

    // --- tiny helpers (match existing patterns in Rulesets) ---
    private static String attr(OpSig sig, String key) {
      if (sig == null || key == null)
        return null;
      Map<String,String> a = sig.attrs();
      return (a == null) ? null : a.get(key);
    }

    private static Boolean parseBoolean(String v) {
      return (v == null) ? null : Boolean.parseBoolean(v);
    }

    private static boolean isWeighted(OpSig sig, int arity) {
      String w = attr(sig, "mmchain.weighted");
      if (w != null)
        return Boolean.parseBoolean(w);
      String typ = attr(sig, "mmchain.type"); // XtXv, XtwXv, XtXvy
      if (typ != null)
        return !"xtxv".equalsIgnoreCase(typ);
      return arity == 3; // fallback if no hints
    }
  }

  /** Element-wise binary ops (matrix-matrix / matrix-scalar). */
  public static final class BinaryElemwiseRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        OpOp2.PLUS.toString(),
        OpOp2.MINUS.toString(),
        OpOp2.MINUS1_MULT.toString(),
        OpOp2.MULT.toString(),
        OpOp2.DIV.toString(),
        OpOp2.MODULUS.toString(),
        OpOp2.INTDIV.toString(),
        OpOp2.LESS.toString(),
        OpOp2.LESSEQUAL.toString(),
        OpOp2.GREATER.toString(),
        OpOp2.GREATEREQUAL.toString(),
        OpOp2.EQUAL.toString(),
        OpOp2.NOTEQUAL.toString(),
        OpOp2.MIN.toString(),
        OpOp2.MAX.toString(),
        OpOp2.POW.toString(),
        OpOp2.AND.toString(),
        OpOp2.OR.toString(),
        OpOp2.XOR.toString(),
        OpOp2.BITWAND.toString(),
        OpOp2.BITWOR.toString(),
        OpOp2.BITWXOR.toString(),
        OpOp2.BITWSHIFTL.toString(),
        OpOp2.BITWSHIFTR.toString());

    @Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      List<FType> left = candidates(inFTypeCandidates, 0);
      List<FType> right = candidates(inFTypeCandidates, 1);
      Set<FType> outs = new LinkedHashSet<>();
      if (aligned(left, right, FType.ROW, hint))
        outs.add(FType.ROW);
      if (aligned(left, right, FType.COL, hint))
        outs.add(FType.COL);
      if (matrixScalarPair(left, right, FType.ROW))
        outs.add(FType.ROW);
      if (matrixScalarPair(left, right, FType.COL))
        outs.add(FType.COL);
      boolean vectorHint = isVectorHint(hint);
      if (vectorHint) {
        boolean leftHasBroadcast = left != null && left.contains(FType.BROADCAST);
        boolean rightHasBroadcast = right != null && right.contains(FType.BROADCAST);
        if ((leftHasBroadcast && hasBroadcastOrScalarFromList(right))
            || (rightHasBroadcast && hasBroadcastOrScalarFromList(left))) {
          outs.add(FType.BROADCAST);
        }
      }
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      FType left = typeAt(inFTypes, 0);
      FType right = typeAt(inFTypes, 1);
      boolean hasFedInput = isFederatedLike(left) || isFederatedLike(right);
      boolean outerLike = isOuterLike(left, right, hint);

      FType axis = null;
      if (!outerLike) {
        if (aligned(left, right, FType.ROW, hint))
          axis = FType.ROW;
        else if (aligned(left, right, FType.COL, hint))
          axis = FType.COL;
        else if (matrixScalarPair(left, right, FType.ROW))
          axis = FType.ROW;
        else if (matrixScalarPair(left, right, FType.COL))
          axis = FType.COL;
      }

      // Matrix-vector broadcasting: allow elementwise ops like X / rowVector to run FED by treating the
      // vector input as broadcastable to the matrix's federated axis.
      //
      // This avoids CP fallbacks due to ROW/COL ftype mismatches for row/col vectors (e.g., PCA scale()).
      if (axis == null && hasFedInput && !outerLike && hint != null) {
        boolean leftVec = isVectorDims(hint.rowsA(), hint.colsA());
        boolean rightVec = isVectorDims(hint.rowsB(), hint.colsB());
        if (leftVec ^ rightVec) {
          // (matrix ROW) op (rowVector 1 x cols)  ==> broadcast vector, keep ROW
          if (!leftVec && rightVec
              && matchesAxis(left, FType.ROW) && matchesAxis(right, FType.COL)
              && hint.rowsB() == 1 && hint.colsB() > 1
              && hint.colsA() > 0 && hint.colsB() == hint.colsA()) {
            axis = FType.ROW;
          }
          // (matrix COL) op (colVector rows x 1) ==> broadcast vector, keep COL
          else if (!leftVec && rightVec
              && matchesAxis(left, FType.COL) && matchesAxis(right, FType.ROW)
              && hint.colsB() == 1 && hint.rowsB() > 1
              && hint.rowsA() > 0 && hint.rowsB() == hint.rowsA()) {
            axis = FType.COL;
          }
          // (rowVector 1 x cols) op (matrix ROW)  ==> broadcast vector, keep ROW
          else if (leftVec && !rightVec
              && matchesAxis(right, FType.ROW) && matchesAxis(left, FType.COL)
              && hint.rowsA() == 1 && hint.colsA() > 1
              && hint.colsB() > 0 && hint.colsA() == hint.colsB()) {
            axis = FType.ROW;
          }
          // (colVector rows x 1) op (matrix COL)  ==> broadcast vector, keep COL
          else if (leftVec && !rightVec
              && matchesAxis(right, FType.COL) && matchesAxis(left, FType.ROW)
              && hint.colsA() == 1 && hint.rowsA() > 1
              && hint.rowsB() > 0 && hint.rowsA() == hint.rowsB()) {
            axis = FType.COL;
          }
        }
      }

      // FULL is a single-partition federated mapping (one worker holds the entire matrix).
      // The runtime supports elementwise binary FED execution for FULL inputs as long as the
      // mapping has exactly one partition (see BinaryMatrixMatrixFEDInstruction). The rules
      // layer, however, cannot always prove axis alignment statically, which previously caused
      // it to pessimistically return CP and trigger refed uploads inside loops (kmeans DP regression).
      //
      // Treat FULL as federated-capable: prefer FED/FOUT with FULL placement when at least one
      // input is FULL and we are not in an outer-product-like topology.
      if (axis == null && hasFedInput && !outerLike
          && (left == FType.FULL || right == FType.FULL)) {
        Guard.Result guard = Guard.eval(sig);
        if (guard != null && guard.isFail())
          return guardFallbackBuilder(sig, guard).build();
        OpCaps.Builder builder = OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.FED)
            .placement(FederatedOutput.FOUT)
            .fout(true, FType.FULL)
            .reason(ReasonCode.OK)
            .note(ReasonCode.INFO, "FULL federated elemwise (runtime validates single-partition mapping)");
        if (guard == null || guard.isUnknown())
          builder.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
        else
          appendGuardPassNote(builder, guard);
        return builder.build();
      }

      if (axis != null && hasFedInput) {
        Guard.Result guard = Guard.eval(sig);
        return guardAwareFout(sig, axis, ReasonCode.OK, guard);
      }

      FType softAxis = null;
      if (!outerLike && hasFedInput) {
        if (matchesAxis(left, FType.ROW) && matchesAxis(right, FType.ROW))
          softAxis = FType.ROW;
        else if (matchesAxis(left, FType.COL) && matchesAxis(right, FType.COL))
          softAxis = FType.COL;
      }

      if (softAxis != null && !axisKnown(softAxis, hint)) {
        Guard.Result guard = Guard.eval(sig);
        if (guard != null && guard.isFail())
          return guardFallbackBuilder(sig, guard).build();
        OpCaps.Builder builder = OpCaps.newBuilder()
            .category(sig.category())
            .opcode(sig.opcode())
            .exec(ExecType.FED)
            .placement(FederatedOutput.FOUT)
            .fout(true, softAxis)
            .reason(ReasonCode.OK)
            .note(
                softAxis == FType.ROW
                    ? ReasonCode.BROADCAST_OR_ALIGNED_ROW
                    : ReasonCode.BROADCAST_OR_ALIGNED_COL,
                ALIGNMENT_NOT_PROVABLE_NOTE);
        if (guard == null || guard.isUnknown())
          builder.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
        else
          appendGuardPassNote(builder, guard);
        return builder.build();
      }

      ReasonCode reason;
      if (!hasFedInput)
        reason = ReasonCode.NO_FED_INPUT;
      else if (outerLike)
        reason = ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY;
      else
        reason = ReasonCode.UNSUPPORTED_ALIGNMENT;
      return cpCaps(sig, reason);
    }
  }

  /** Element-wise nary ops (matrix-matrix / matrix-scalar). */
  public static final class NaryElemwiseRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        OpOpN.MULT.name().toLowerCase(Locale.ROOT));

    @Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (inFTypeCandidates == null || inFTypeCandidates.isEmpty())
        return FTypeProfile.empty();
      Set<FType> outs = new LinkedHashSet<>();
      for (List<FType> types : inFTypeCandidates) {
        if (hasAxis(types, FType.ROW))
          outs.add(FType.ROW);
        if (hasAxis(types, FType.COL))
          outs.add(FType.COL);
        if (types != null && types.contains(FType.PART))
          outs.add(FType.PART);
        if (types != null && types.contains(FType.FULL))
          outs.add(FType.FULL);
      }
      return profileOf(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (inFTypes == null || inFTypes.isEmpty())
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType axis = null;
      boolean hasFedInput = false;
      for (FType t : inFTypes) {
        if (t == null || t == FType.BROADCAST)
          continue;
        if (!isFederatedLike(t))
          continue;
        hasFedInput = true;
        if (matchesAxis(t, FType.ROW)) {
          if (axis == null)
            axis = FType.ROW;
          else if (!matchesAxis(axis, FType.ROW))
            return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT);
        }
        else if (matchesAxis(t, FType.COL)) {
          if (axis == null)
            axis = FType.COL;
          else if (!matchesAxis(axis, FType.COL))
            return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT);
        }
        else {
          return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
        }
      }

      if (!hasFedInput)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (axis != null)
        return guardAwareFout(sig, axis, ReasonCode.OK, Guard.eval(sig));
      return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT);
    }
  }

  /** Append (append opcode + cbind attribute). */
  public static final class AppendRule extends BaseRule {
    private static final String APPEND = Opcodes.APPEND.toString();
    private static final String CBIND = Opcodes.CBIND.toString();
    private static final String RBIND = Opcodes.RBIND.toString();
    private static final String ATTR_CBIND = "cbind";

    @Override public OpCategory category() { return OpCategory.APPEND; }
    @Override public Set<String> opcodes() { return Set.of(APPEND, CBIND, RBIND); }

    @Override
    public boolean supports(OpSig sig) {
      if (sig == null || sig.category() != OpCategory.APPEND)
        return false;
      String opcode = normalizedOpcode(sig);
      if (!APPEND.equals(opcode) && !CBIND.equals(opcode) && !RBIND.equals(opcode))
        return false;
      String attr = attr(sig, ATTR_CBIND);
      if (attr != null)
        return isTrue(attr) || isFalse(attr);
      return CBIND.equals(opcode) || RBIND.equals(opcode);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();
      boolean cbind = parseCbind(sig);
      List<FType> left = candidates(inFTypeCandidates, 0);
      List<FType> right = candidates(inFTypeCandidates, 1);
      boolean anyRow = hasAxis(left, FType.ROW) || hasAxis(right, FType.ROW);
      boolean anyCol = hasAxis(left, FType.COL) || hasAxis(right, FType.COL);
      List<FType> outs = new ArrayList<>();

      if (cbind) {
        if (anyCol) outs.add(FType.COL);
        if (anyRow) outs.add(FType.ROW);
      }
      else {
        if (anyRow) outs.add(FType.ROW);
        if (anyCol) outs.add(FType.COL);
      }

      if ((left != null && left.contains(FType.FULL))
          || (right != null && right.contains(FType.FULL))) {
        outs.add(FType.FULL);
      }

      if (outs.isEmpty())
        return FTypeProfile.empty();
      return FTypeProfile.outs(outs);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (!supports(sig)) {
        return baseCaps(sig)
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.NOT_IMPLEMENTED)
            .build();
      }
      boolean cbind = parseCbind(sig);
      List<NoteEntry> pendingNotes = new ArrayList<>();

      if (containsType(inFTypes, FType.FULL)) {
        boolean singleRange = hint != null && hint.fullSinglePartition().orElse(false);
        if (!singleRange) {
          OpCaps.Builder builder = baseCaps(sig)
              
              .reason(ReasonCode.NOT_IMPLEMENTED)
              .detail(APPEND_FULL_SINGLE_RANGE_DETAIL);
          applyNotes(builder, pendingNotes);
          return builder.build();
        }
        Guard.Result guard = Guard.eval(sig);
        if (guard != null && guard.isFail()) {
          OpCaps.Builder builder = guardFallbackBuilder(sig, guard);
          applyNotes(builder, pendingNotes);
          return builder.build();
        }
        OpCaps.Builder builder = baseCaps(sig)
            .exec(ExecType.FED)
            .placement(FederatedOutput.FOUT)
            .fout(true, FType.FULL)
            .reason(ReasonCode.OK);
        applyNotes(builder, pendingNotes);
        if (guard == null || guard.isUnknown())
          builder.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
        else
          appendGuardPassNote(builder, guard);
        return builder.build();
      }

      if (cbind) {
        if (rowsKnown(hint) && hint.rowsA() != hint.rowsB()) {
          return baseCaps(sig)
              
              .reason(ReasonCode.DIM_MISMATCH_ROWS)
              .detail("cbind requires matching row counts")
              .build();
        }
        if (!rowsKnown(hint))
          pendingNotes.add(NoteEntry.ok("rows unknown — deferring cbind check"));
      }
      else {
        if (colsKnown(hint) && hint.colsA() != hint.colsB()) {
          return baseCaps(sig)
              
              .reason(ReasonCode.DIM_MISMATCH_COLS)
              .detail("rbind requires matching column counts")
              .build();
        }
        if (!colsKnown(hint))
          pendingNotes.add(NoteEntry.ok("cols unknown — deferring rbind check"));
      }

      FType left = typeAt(inFTypes, 0);
      FType right = typeAt(inFTypes, 1);
      boolean hasRow = matchesAxis(left, FType.ROW) || matchesAxis(right, FType.ROW);
      boolean hasCol = matchesAxis(left, FType.COL) || matchesAxis(right, FType.COL);

      if (!hasRow && !hasCol) {
        OpCaps.Builder builder = baseCaps(sig)
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.NO_FED_INPUT);
        applyNotes(builder, pendingNotes);
        return builder.build();
      }

      FType outType = resolveOutType(cbind, hasRow, hasCol);
      ReasonCode cause = reasonFor(cbind, hasRow, hasCol);
      Guard.Result guard = Guard.eval(sig);
      if (guard != null && guard.isFail()) {
        OpCaps.Builder builder = guardFallbackBuilder(sig, guard);
        applyNotes(builder, pendingNotes);
        return builder.build();
      }

      OpCaps.Builder builder = baseCaps(sig)
          .exec(ExecType.FED)
          .placement(FederatedOutput.FOUT)
          .fout(true, outType)
          .reason(cause);
      applyNotes(builder, pendingNotes);
      if (guard == null || guard.isUnknown())
        builder.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
      else
        appendGuardPassNote(builder, guard);
      return builder.build();
    }

    private static boolean parseCbind(OpSig sig) {
      String attr = attr(sig, ATTR_CBIND);
      if (attr != null)
        return Boolean.parseBoolean(attr);
      String opcode = normalizedOpcode(sig);
      if (CBIND.equals(opcode))
        return true;
      if (RBIND.equals(opcode))
        return false;
      return false;
    }

    private static String attr(OpSig sig, String key) {
      if (sig == null || key == null)
        return null;
      Map<String,String> attrs = sig.attrs();
      if (attrs == null)
        return null;
      return attrs.get(key);
    }

    private static boolean isTrue(String v) { return "true".equalsIgnoreCase(v); }
    private static boolean isFalse(String v) { return "false".equalsIgnoreCase(v); }

    private static boolean rowsKnown(ShapeHint hint) {
      return hint != null && hint.rowsKnown();
    }

    private static boolean colsKnown(ShapeHint hint) {
      return hint != null && hint.colsKnown();
    }

    private static boolean containsType(List<FType> types, FType target) {
      if (types == null || target == null)
        return false;
      int limit = Math.min(2, types.size());
      for (int i = 0; i < limit; i++) {
        FType t = types.get(i);
        if (t == target)
          return true;
      }
      return false;
    }

    private static FType resolveOutType(boolean cbind, boolean hasRow, boolean hasCol) {
      if (cbind) {
        if (hasCol) return FType.COL;
        if (hasRow) return FType.ROW;
      }
      else {
        if (hasRow) return FType.ROW;
        if (hasCol) return FType.COL;
      }
      return null;
    }

    private static ReasonCode reasonFor(boolean cbind, boolean hasRow, boolean hasCol) {
      if (cbind)
        return hasCol ? ReasonCode.PREFER_BIND_COL : ReasonCode.BROADCAST_OR_ALIGNED_ROW;
      return hasRow ? ReasonCode.PREFER_BIND_ROW : ReasonCode.BROADCAST_OR_ALIGNED_COL;
    }

    private static OpCaps.Builder baseCaps(OpSig sig) {
      return OpCaps.builder()
          .category(sig != null ? sig.category() : OpCategory.APPEND)
          .opcode(sig != null ? sig.opcode() : APPEND);
    }

    private static void applyNotes(OpCaps.Builder builder, List<NoteEntry> notes) {
      if (notes == null || notes.isEmpty())
          return;
      for (NoteEntry note : notes) {
        builder.note(note.code, note.message);
      }
    }

    private static final class NoteEntry {
      private final ReasonCode code;
      private final String message;

      private NoteEntry(ReasonCode code, String message) {
        this.code = code;
        this.message = message;
      }

      static NoteEntry ok(String msg) {
        return new NoteEntry(ReasonCode.INFO, msg);
      }
    }
  }

  /** Quantile sort (qsort) compile-time rule preserving FED pipelines for qpick. */
  public static final class QuantileSortRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(Opcodes.QSORT.toString());

    @Override public OpCategory category() { return OpCategory.QUANTILE_SORT; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null
          && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      List<FType> in = candidates(inFTypeCandidates, 0);
      if (in.isEmpty())
        return FTypeProfile.empty();

      Set<FType> outs = new LinkedHashSet<>();
      if (in.contains(FType.ROW))
        outs.add(FType.ROW);
      if (in.contains(FType.COL))
        outs.add(FType.COL);
      if (in.contains(FType.FULL))
        outs.add(FType.FULL);

      return outs.isEmpty()
          ? FTypeProfile.empty()
          : FTypeProfile.ofOutput(new ArrayList<>(outs));
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!supports(sig))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);

      int n = (inFTypes == null) ? 0 : inFTypes.size();
      if (n < 1)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      FType in = typeAt(inFTypes, 0);
      if (in == null)
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      if (in == FType.BROADCAST)
        return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);
      if (in == FType.PART)
        return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);

      boolean columnPath = (in == FType.COL || in == FType.FULL);
      FType outAxis = columnPath ? in : FType.ROW;

      OpCaps.Builder builder = OpCaps.newBuilder()
          .category(sig.category())
          .opcode(sig.opcode())
          .exec(ExecType.FED)
          .placement(FederatedOutput.FOUT)
          .fout(true, outAxis)
          .reason(ReasonCode.OK);

      OpSig.InputKind weightKind = sig.inputKind(1);
      boolean hasWeights = weightKind == OpSig.InputKind.MATRIX || n > 1;
      if (hasWeights) {
        if (columnPath) {
          builder.note(ReasonCode.INFO, "weights driver-collected for qsort UDF (performance caution)");
        }
        else {
          builder.note(ReasonCode.BROADCAST_OR_ALIGNED_ROW,
              "weights broadcast-sliced to partitions; row-path result columns=(value,weight)");
        }
      }

      if (hint != null && hint.cols() > 1) {
        builder.note(ReasonCode.OP_SHAPE_INCOMPATIBLE,
            "qsort expects 1-column vectors (runtime validation handles enforcement)");
      }

      return builder.build();
    }
  }

  /** Quantile pick (qpick) compile-only rule (local result). */
  public static final class QuantilePickRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(Opcodes.QPICK.toString());

    @Override public OpCategory category() { return OpCategory.QUANTILE_PICK; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      if (sig == null || sig.category() != category())
        return false;
      String op = normalizedOpcode(sig);
      return OPCODES.contains(op);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return FTypeProfile.empty(); // result collected locally
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      if (!supports(sig))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);

      final int n = (inFTypes == null) ? 0 : inFTypes.size();
      if (n < 1 || n > 2)
        return cpCaps(sig, ReasonCode.ARITY_MISMATCH);

      final FType in1 = typeAt(inFTypes, 0);
      if (in1 == null)
        return cpCaps(sig, ReasonCode.MISSING_IN_FTYPE);

      if (n >= 2) {
        final FType in2 = typeAt(inFTypes, 1);
        if (in2 == FType.ROW || in2 == FType.COL || in2 == FType.PART
            || in2 == FType.FULL || in2 == FType.BROADCAST) {
          return cpLocal(sig)
              .reason(ReasonCode.BROADCAST_CONSTRAINT)
              .detail("quantile parameter must be local/small")
              .build();
        }
      }

      switch (in1) {
        case ROW:
        case PART:
          return fedLocalCaps(sig, ReasonCode.OK);

        case COL:
        case FULL:
          if (hint != null && hint.fullSinglePartition().isPresent()
              && !hint.fullSinglePartition().get()) {
            return cpLocal(sig)
                .reason(ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY)
                .detail("qpick column-path assumes single federated range")
                .build();
          }
          return fedLocalCaps(sig, ReasonCode.OK);

        case BROADCAST:
          return cpCaps(sig, ReasonCode.BROADCAST_CONSTRAINT);

        default:
          return cpCaps(sig, ReasonCode.NOT_FEDERATED_INPUTS);
      }
    }

    private static OpCaps.Builder cpLocal(OpSig sig) {
      return OpCaps.newBuilder()
          .category(sig.category())
          .opcode(sig.opcode())
          .exec(ExecType.CP)
          .placement(FederatedOutput.LOUT)
          ;
    }
  }

  /** CTABLE planner rule mirroring CtableFEDInstruction. */
  public static final class CtableRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(Opcodes.CTABLE.toString());
    private static final String ATTR_DISJOINT = "ctable_disjoint_bins";

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();

      List<FType> aCands = candidates(inFTypeCandidates, 0);
      List<FType> bCands = candidates(inFTypeCandidates, 1);

      boolean disjoint = parseBoolAttr(sig, ATTR_DISJOINT, false);
      if (!disjoint)
        return FTypeProfile.empty();

      boolean aRow = hasAxis(aCands, FType.ROW);
      boolean bRow = hasAxis(bCands, FType.ROW);
      FType axis = (!aRow && bRow) ? FType.ROW : FType.COL;
      return FTypeProfile.ofOutput(List.of(axis));
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      int n = (inFTypes == null) ? 0 : inFTypes.size();
      OpCategory category = (sig != null) ? sig.category() : OpCategory.OTHER;
      String opcode = (sig != null && sig.opcode() != null)
          ? sig.opcode()
          : Opcodes.CTABLE.toString();

      // CTABLE hops may include additional scalar inputs for output dimensions / flags
      // (e.g., the 6-input table variant). Only the first 2-3 inputs can be matrices.
      if (n < 2) {
        return OpCaps.newBuilder()
            .category(category)
            .opcode(opcode)
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.ARITY_MISMATCH)
            .detail("expected>=2, got=" + n)
            .build();
      }

      FType a = typeAt(inFTypes, 0);
      FType b = typeAt(inFTypes, 1);
      FType w = (n >= 3) ? typeAt(inFTypes, 2) : null;

      boolean hasRowFed = (a == FType.ROW) || (b == FType.ROW) || (w == FType.ROW);
      if (!hasRowFed) {
        return OpCaps.newBuilder()
            .category(category)
            .opcode(opcode)
            .exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.NO_FED_INPUT)
            .detail("no ROW input")
            .build();
      }

      boolean disjoint = parseBoolAttr(sig, ATTR_DISJOINT, false);
      boolean reversed = (a != FType.ROW) && (b == FType.ROW);
      FType outAxis = reversed ? FType.ROW : FType.COL;

      if (disjoint) {
        if (sig == null) {
          return OpCaps.newBuilder()
              .category(category)
              .opcode(opcode)
              .exec(ExecType.FED)
              .placement(FederatedOutput.FOUT)
              .fout(true, outAxis)
              .reason(ReasonCode.OK)
              .build();
        }
        return fedFoutCaps(sig, outAxis, ReasonCode.OK);
      }

      return OpCaps.newBuilder()
          .category(category)
          .opcode(opcode)
          .exec(ExecType.FED)
          .placement(FederatedOutput.LOUT)
          
          .reason(ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY)
          .detail("ctable_disjoint_bins=false (default)")
          .build();
    }

    private static boolean parseBoolAttr(OpSig sig, String key, boolean defVal) {
      if (sig == null || key == null)
        return defVal;
      Map<String,String> attrs = sig.attrs();
      if (attrs == null)
        return defVal;
      String v = attrs.get(key);
      return (v != null) ? Boolean.parseBoolean(v) : defVal;
    }
  }

  /** Explicit deny for quantile/interquantile (no federated instruction available). */
  public static final class QuantileInterquantileCtableDenyRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        OpOp3.QUANTILE.toString(),
        OpOp3.INTERQUANTILE.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Objects.requireNonNull(sig, "sig");
      return cpCaps(sig, ReasonCode.MISSING_FED_INSTRUCTION);
    }
  }

  /** Cumulative offset ops (bcumoff*). */
  public static final class CumulativeOffsetRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(
        Opcodes.BCUMOFFKP.toString(),
        Opcodes.BCUMOFFM.toString(),
        Opcodes.BCUMOFFPM.toString(),
        Opcodes.BCUMOFFMIN.toString(),
        Opcodes.BCUMOFFMAX.toString());
    private static final String OPCODE_ROW_ONLY = Opcodes.BCUMOFFPM.toString();

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null
          && sig.arity() == 2
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();

      final String opcode = normalizedOpcode(sig);
      final List<FType> left = candidates(inFTypeCandidates, 0);
      final Set<FType> outs = new LinkedHashSet<>();

      if (hasAxis(left, FType.ROW))
        outs.add(FType.ROW);
      if (!OPCODE_ROW_ONLY.equals(opcode) && hasAxis(left, FType.COL))
        outs.add(FType.COL);
      if (left.contains(FType.FULL))
        outs.add(FType.FULL);

      return outs.isEmpty()
          ? FTypeProfile.empty()
          : FTypeProfile.ofOutput(new ArrayList<>(outs));
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      final OpCaps.Builder b = OpCaps.builder()
          .category(sig != null ? sig.category() : OpCategory.OTHER)
          .opcode(sig != null ? sig.opcode() : "");

      if (inFTypes == null || inFTypes.size() < 2) {
        return b.exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.ARITY_MISMATCH)
            .build();
      }

      final String opcode = normalizedOpcode(sig);
      final FType in = typeAt(inFTypes, 0);

      if (in == null || in == FType.BROADCAST) {
        return b.exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.NO_FED_INPUT)
            .build();
      }

      if (in == FType.FULL) {
        boolean single = (hint == null) ? true : hint.fullSinglePartition().orElse(true);
        if (!single) {
          return b.exec(ExecType.CP)
              .placement(FederatedOutput.LOUT)
              
              .reason(ReasonCode.NO_FED_INPUT)
              .detail(CUMOFF_FULL_SINGLE_RANGE_DETAIL)
              .build();
        }
        Guard.Result guard = Guard.eval(sig);
        if (guard != null && guard.isFail())
          return guardFallbackBuilder(sig, guard).build();
        OpCaps.Builder builder = OpCaps.newBuilder()
            .category(sig != null ? sig.category() : OpCategory.OTHER)
            .opcode(sig != null ? sig.opcode() : "")
            .exec(ExecType.FED)
            .placement(FederatedOutput.FOUT)
            .fout(true, FType.FULL)
            .reason(ReasonCode.OK);
        if (OPCODE_ROW_ONLY.equals(opcode)) {
          builder.detail("Result is n×1 for bcumoff+* (runtime adjusts federated ranges).");
        }
        if (guard == null || guard.isUnknown())
          builder.note(ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, guardDetail(guard));
        else
          appendGuardPassNote(builder, guard);
        return builder.build();
      }

      if (OPCODE_ROW_ONLY.equals(opcode) && in != FType.ROW) {
        return b.exec(ExecType.CP)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY)
            .detail("bcumoff+* requires ROW-partitioned input")
            .build();
      }

      if (in == FType.PART) {
        return b.exec(ExecType.FED)
            .placement(FederatedOutput.LOUT)
            
            .reason(ReasonCode.FOUT_DISALLOWED_FOR_PART_OUT)
            .build();
      }

      final FType axis = (in == FType.COL) ? FType.COL : FType.ROW;
      if (OPCODE_ROW_ONLY.equals(opcode)) {
        b.detail("Result is n×1 for bcumoff+* (runtime adjusts federated ranges).");
      }
      return b.exec(ExecType.FED)
          .placement(FederatedOutput.FOUT)
          .fout(true, axis)
          .reason(ReasonCode.OK)
          .build();
    }
  }

  /**
   * Covariance는 항상 스칼라를 산출하므로 FOUT 불가; 최종 LOUT만 허용.
   * 두 입력이 모두 연합이면 같은 축(ROW/ROW, COL/COL)로 해석될 때만 FED 허용; 그 외(축 불일치/PART 개입)는 CP 폴백.
   * 가중치는 로컬/브로드캐스트/연합 어떤 형태든 허용(결정에는 영향 없음); 필요 시 표준화된 note 문자열 부여.
   */
  public static final class CovarianceRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(OpOp3.COV.toString());
    private static final String ATTR_ALIGN_HINT = "align_hint";

    @Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null
          && sig.category() == category()
          && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      try {
        if (sig == null)
          return cpLocal(null, ReasonCode.OPCODE_UNSUPPORTED).build();
        if (sig.category() != category())
          return cpLocal(sig, ReasonCode.OPCODE_UNSUPPORTED).build();
        if (!OPCODES.contains(normalizedOpcode(sig)))
          return cpLocal(sig, ReasonCode.OPCODE_UNSUPPORTED).build();
        if (inFTypes == null || inFTypes.size() < 2)
          return cpLocal(sig, ReasonCode.ARITY_MISMATCH).build();

        FType left = typeAt(inFTypes, 0);
        FType right = typeAt(inFTypes, 1);
        if (left == null || right == null)
          return cpLocal(sig, ReasonCode.MISSING_IN_FTYPE).build();

        FType weights = (inFTypes.size() >= 3) ? typeAt(inFTypes, 2) : null;
        boolean leftFed = isFederatedLike(left);
        boolean rightFed = isFederatedLike(right);

        if (!leftFed && !rightFed)
          return addWeightNote(cpLocal(sig, ReasonCode.NO_FED_INPUT), weights).build();

        if (leftFed && rightFed) {
          boolean sameRow = matchesAxis(left, FType.ROW) && matchesAxis(right, FType.ROW);
          boolean sameCol = matchesAxis(left, FType.COL) && matchesAxis(right, FType.COL);
          boolean hasPart = left == FType.PART || right == FType.PART;
          FType hintAxis = parseAlignHint(sig);
          boolean hintAligned = hasPart && hintAxis != null;

          if (sameRow || sameCol || hintAligned) {
            OpCaps.Builder ok = fedLocal(sig, ReasonCode.OK);
            if (hintAligned)
              ok.note(ReasonCode.ALIGNED_HINT, alignNote(hintAxis));
            return addWeightNote(ok, weights).build();
          }

          return addWeightNote(
              cpLocal(sig, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY),
              weights).build();
        }

        return addWeightNote(fedLocal(sig, ReasonCode.OK), weights).build();
      } catch (Exception ex) {
        return cpLocal(sig, ReasonCode.RULE_ERROR).build();
      }
    }

    private static OpCaps.Builder cpLocal(OpSig sig, ReasonCode reason) {
      return localCaps(sig, ExecType.CP, reason);
    }

    private static OpCaps.Builder fedLocal(OpSig sig, ReasonCode reason) {
      return localCaps(sig, ExecType.FED, reason);
    }

    private static OpCaps.Builder localCaps(OpSig sig, ExecType exec, ReasonCode reason) {
      return OpCaps.builder()
          .category(sig != null ? sig.category() : OpCategory.BINARY_EWISE)
          .opcode(sig != null ? sig.opcode() : "")
          .exec(exec)
          .placement(FederatedOutput.LOUT)
          .reason(reason);
    }

    private static OpCaps.Builder addWeightNote(OpCaps.Builder builder, FType weights) {
      if (builder == null || weights == null)
        return builder;
      if (isFederatedLike(weights))
        return builder.note(ReasonCode.INFO, "weights=broadcast-sliced");
      return builder.note(ReasonCode.INFO, "weights=local");
    }

    private static FType parseAlignHint(OpSig sig) {
      if (sig == null)
        return null;
      Map<String,String> attrs = sig.attrs();
      if (attrs == null)
        return null;
      String raw = attrs.get(ATTR_ALIGN_HINT);
      if (raw == null)
        return null;
      if ("row".equalsIgnoreCase(raw))
        return FType.ROW;
      if ("col".equalsIgnoreCase(raw))
        return FType.COL;
      return null;
    }

    private static String alignNote(FType axis) {
      return (axis == FType.COL) ? "align=hint:COL" : "align=hint:ROW";
    }
  }

  /** Solve op (explicit CP fallback). */
  public static final class SolveRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(Opcodes.SOLVE.toString());

    @Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return FTypeProfile.empty();
    }

    @Override
  public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      return cpCaps(sig, ReasonCode.NOT_IMPLEMENTED);
    }
  }

  public static final class SpoofCellwiseRule extends BaseRule {
    @Override public OpCategory category() { return OpCategory.SPOOF; }

    @Override public boolean supports(OpSig sig) {
      return matchesSpoofTemplate(sig, SpoofTemplate.CELLWISE);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      AttrValue<SpoofCellType> cellType = parseCellTypeAttr(sig);
      if (!cellType.hasValue())
        return FTypeProfile.empty();

      switch (cellType.value) {
        case NO_AGG:
          return primaryLikeProfile(inFTypeCandidates);
        case ROW_AGG: {
          Set<FType> outs = new LinkedHashSet<>();
          if (hasAxis(candidates(inFTypeCandidates, 0), FType.ROW))
            outs.add(FType.ROW);
          return profileOf(outs);
        }
        case COL_AGG: {
          Set<FType> outs = new LinkedHashSet<>();
          if (hasAxis(candidates(inFTypeCandidates, 0), FType.COL))
            outs.add(FType.COL);
          return profileOf(outs);
        }
        default:
          return FTypeProfile.empty();
      }
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      TemplateInfo template = resolveTemplate(sig);
      if (template.invalid)
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (template.template != SpoofTemplate.CELLWISE)
        return cpCaps(sig, ReasonCode.NO_RULE);

      AttrValue<SpoofCellType> cellType = parseCellTypeAttr(sig);
      if (!cellType.hasValue())
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);

      FedInfo fed = summarizeFedInputs(inFTypes);
      OpCaps fail = fedPreconditionFailure(sig, fed);
      if (fail != null)
        return fail;
      FType x = fed.primary;

      switch (cellType.value) {
        case NO_AGG:
          return guardAwareFout(sig, preserveOrAxis(x), ReasonCode.OK, Guard.eval(sig));
        case ROW_AGG:
          if (matchesAxis(x, FType.ROW))
            return guardAwareFout(sig, FType.ROW, ReasonCode.OK, Guard.eval(sig));
          return fedLocalCaps(sig, ReasonCode.OK);
        case COL_AGG:
          if (matchesAxis(x, FType.COL))
            return guardAwareFout(sig, FType.COL, ReasonCode.OK, Guard.eval(sig));
          return fedLocalCaps(sig, ReasonCode.OK);
        case FULL_AGG:
          return fedLocalWithDetail(sig, ReasonCode.OK, SCALAR_LOUT_DETAIL);
        default:
          return fedLocalCaps(sig, ReasonCode.OK);
      }
    }
  }

  public static final class SpoofRowwiseRule extends BaseRule {
    @Override public OpCategory category() { return OpCategory.SPOOF; }

    @Override public boolean supports(OpSig sig) {
      return matchesSpoofTemplate(sig, SpoofTemplate.ROWWISE);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      AttrValue<SpoofRowType> rowType = parseRowTypeAttr(sig);
      if (!rowType.hasValue())
        return FTypeProfile.empty();

      if (rowType.value == SpoofRowType.NO_AGG
          || rowType.value == SpoofRowType.NO_AGG_B1
          || rowType.value == SpoofRowType.NO_AGG_CONST) {
        Set<FType> outs = new LinkedHashSet<>();
        if (hasAxis(candidates(inFTypeCandidates, 0), FType.ROW))
          outs.add(FType.ROW);
        return profileOf(outs);
      }
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      TemplateInfo template = resolveTemplate(sig);
      if (template.invalid)
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (template.template != SpoofTemplate.ROWWISE)
        return cpCaps(sig, ReasonCode.NO_RULE);

      AttrValue<SpoofRowType> rowType = parseRowTypeAttr(sig);
      if (!rowType.hasValue())
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);

      FedInfo fed = summarizeFedInputs(inFTypes);
      OpCaps fail = fedPreconditionFailure(sig, fed);
      if (fail != null)
        return fail;

      FType x = fed.primary;
      if (!matchesAxis(x, FType.ROW))
        return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);

      if (rowType.value == SpoofRowType.NO_AGG
          || rowType.value == SpoofRowType.NO_AGG_B1
          || rowType.value == SpoofRowType.NO_AGG_CONST) {
        return guardAwareFout(sig, FType.ROW, ReasonCode.OK, Guard.eval(sig));
      }
      return fedLocalCaps(sig, ReasonCode.OK);
    }
  }

  public static final class SpoofMultiAggregateRule extends BaseRule {
    @Override public OpCategory category() { return OpCategory.SPOOF; }

    @Override public boolean supports(OpSig sig) {
      return matchesSpoofTemplate(sig, SpoofTemplate.MULTIAGG);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return FTypeProfile.empty();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      TemplateInfo template = resolveTemplate(sig);
      if (template.invalid)
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (template.template != SpoofTemplate.MULTIAGG)
        return cpCaps(sig, ReasonCode.NO_RULE);

      FedInfo fed = summarizeFedInputs(inFTypes);
      OpCaps fail = fedPreconditionFailure(sig, fed);
      if (fail != null)
        return fail;

      FType x = fed.primary;
      if (!matchesAxis(x, FType.ROW) && !matchesAxis(x, FType.COL))
        return cpCaps(sig, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
      return fedLocalCaps(sig, ReasonCode.OK);
    }
  }

  public static final class SpoofOuterProductRule extends BaseRule {
    @Override public OpCategory category() { return OpCategory.SPOOF; }

    @Override public boolean supports(OpSig sig) {
      return matchesSpoofTemplate(sig, SpoofTemplate.OUTER);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      AttrValue<SpoofOuterType> outerType = parseOuterTypeAttr(sig);
      if (!outerType.hasValue())
        return FTypeProfile.empty();

      List<FType> primary = candidates(inFTypeCandidates, 0);
      switch (outerType.value) {
        case CELLWISE_OUTER_PRODUCT:
          return primaryLikeProfile(inFTypeCandidates);
        case LEFT_OUTER_PRODUCT: {
          Set<FType> outs = new LinkedHashSet<>();
          if (hasAxis(primary, FType.COL))
            outs.add(FType.COL);
          return profileOf(outs);
        }
        case RIGHT_OUTER_PRODUCT: {
          Set<FType> outs = new LinkedHashSet<>();
          if (hasAxis(primary, FType.ROW))
            outs.add(FType.ROW);
          return profileOf(outs);
        }
        default:
          return FTypeProfile.empty();
      }
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      TemplateInfo template = resolveTemplate(sig);
      if (template.invalid)
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (template.template != SpoofTemplate.OUTER)
        return cpCaps(sig, ReasonCode.NO_RULE);

      AttrValue<SpoofOuterType> outerType = parseOuterTypeAttr(sig);
      if (!outerType.hasValue())
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);

      FedInfo fed = summarizeFedInputs(inFTypes);
      OpCaps fail = fedPreconditionFailure(sig, fed);
      if (fail != null)
        return fail;

      FType x = fed.primary;
      switch (outerType.value) {
        case CELLWISE_OUTER_PRODUCT:
          return guardAwareFout(sig, preserveOrAxis(x), ReasonCode.OK, Guard.eval(sig));
        case LEFT_OUTER_PRODUCT:
          if (matchesAxis(x, FType.COL))
            return guardAwareFout(sig, FType.COL, ReasonCode.OK, Guard.eval(sig));
          return fedLocalCaps(sig, ReasonCode.OK);
        case RIGHT_OUTER_PRODUCT:
          if (matchesAxis(x, FType.ROW))
            return guardAwareFout(sig, FType.ROW, ReasonCode.OK, Guard.eval(sig));
          return fedLocalCaps(sig, ReasonCode.OK);
        case AGG_OUTER_PRODUCT:
          return fedLocalWithDetail(sig, ReasonCode.OK, SCALAR_LOUT_DETAIL);
        default:
          return fedLocalCaps(sig, ReasonCode.OK);
      }
    }
  }

  /** Transient write acts as a pass-through for federated values. */
  public static final class TransientWriteRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(OpOpData.TRANSIENTWRITE.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return primaryLikeProfile(inFTypeCandidates);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      FType in = typeAt(inFTypes, 0);
      if (isFederatedLike(in))
        return fedFoutCaps(sig, preserveOrAxis(in), ReasonCode.OK);
      if (in == FType.FULL || in == FType.PART)
        return fedFoutCaps(sig, preserveOrAxis(in), ReasonCode.OK);

      if (attrBoolean(sig, ATTR_VAR_WRITE_FED))
        return fedLocalWithDetail(sig, ReasonCode.OK, FED_WRITE_DETAIL);

      return cpCaps(sig, ReasonCode.NO_FED_INPUT);
    }
  }

  /** Function output acts as a pass-through for federated values. */
  public static final class FunctionOutputRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(OpOpData.FUNCTIONOUTPUT.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return primaryLikeProfile(inFTypeCandidates);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      String sourceType = attrValue(sig, ATTR_FUNOUT_FCALL_TYPE);
      if (sourceType != null && sourceType.equalsIgnoreCase(MULTIRETURN_BUILTIN_TYPE))
        return cpCaps(sig, ReasonCode.MISSING_FED_INSTRUCTION);

      FType in = typeAt(inFTypes, 0);
      if (isFederatedLike(in))
        return fedFoutCaps(sig, preserveOrAxis(in), ReasonCode.OK);
      if (in == FType.FULL || in == FType.PART)
        return fedFoutCaps(sig, preserveOrAxis(in), ReasonCode.OK);

      if (attrBoolean(sig, ATTR_VAR_WRITE_FED))
        return fedLocalWithDetail(sig, ReasonCode.OK, FED_WRITE_DETAIL);

      return cpCaps(sig, ReasonCode.NO_FED_INPUT);
    }
  }

  /** Transient read forwards the underlying value's federated layout. */
  public static final class TransientReadRule extends BaseRule {
    private static final Set<String> OPCODES = Set.of(OpOpData.TRANSIENTREAD.toString());

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return OPCODES; }

    @Override
    public boolean supports(OpSig sig) {
      return sig != null && OPCODES.contains(normalizedOpcode(sig));
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return primaryLikeProfile(inFTypeCandidates);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      FType attrType = attrFType(sig, ATTR_VAR_READ_FTYPE);
      if (attrType != null)
        return fedFoutCaps(sig, preserveOrAxis(attrType), ReasonCode.OK);
      FType in = typeAt(inFTypes, 0);
      if (isFederatedLike(in) || in == FType.FULL || in == FType.PART)
        return fedFoutCaps(sig, preserveOrAxis(in), ReasonCode.OK);
      if (attrBoolean(sig, ATTR_VAR_READ_FED))
        return fedFoutCaps(sig, FType.BROADCAST, ReasonCode.OK);

      return cpCaps(sig, ReasonCode.NO_FED_INPUT);
    }
  }

  /**
   * Function call hops act purely as placeholders for their callee DAGs and never execute
   * directly; allow them to keep FED/FOUT placement when any input is federated.
   */
  public static final class FunctionCallRule extends BaseRule {
    private static final String OPCODE_PREFIX = "fcall";
    private static final String PLACEHOLDER_NOTE =
        "function call hop treated as federated placeholder; execution occurs inside callee";

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return null; }

    @Override
    public boolean supports(OpSig sig) {
      if (sig == null)
        return false;
      String opcode = normalizedOpcode(sig);
      return opcode.startsWith(OPCODE_PREFIX);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();
      if (isMultiReturnBuiltin(sig))
        return FTypeProfile.empty();
      return primaryLikeProfile(inFTypeCandidates);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (!supports(sig))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      if (isMultiReturnBuiltin(sig))
        return cpCaps(sig, ReasonCode.MISSING_FED_INSTRUCTION);
      Optional<FType> passthroughType = firstMeaningfulInputType(inFTypes);
      if (!passthroughType.isPresent())
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      OpCaps.Builder builder = OpCaps.newBuilder()
          .category(sig.category())
          .opcode(sig.opcode())
          .exec(ExecType.FED)
          .fout(true, passthroughType.get())
          .reason(ReasonCode.OK)
          .detail(describeFunction(sig));
      builder.note(ReasonCode.INFO, PLACEHOLDER_NOTE);
      return builder.build();
    }

    private Optional<FType> firstMeaningfulInputType(List<FType> inFTypes) {
      if (inFTypes == null || inFTypes.isEmpty())
        return Optional.empty();
      FType fallback = null;
      for (FType t : inFTypes) {
        if (isFederatedLike(t))
          return Optional.of(t);
        if (fallback == null && isMeaningfulFType(t))
          fallback = t;
      }
      return Optional.ofNullable(fallback);
    }

    private boolean isMeaningfulFType(FType t) {
      return t != null;
    }

    private String describeFunction(OpSig sig) {
      String ns = attrValue(sig, ATTR_FCALL_NAMESPACE);
      String name = attrValue(sig, ATTR_FCALL_NAME);
      String type = attrValue(sig, ATTR_FCALL_TYPE);
      List<String> parts = new ArrayList<>();
      if (name != null && !name.isBlank())
        parts.add("function=" + name);
      if (ns != null && !ns.isBlank())
        parts.add("namespace=" + ns);
      if (type != null && !type.isBlank())
        parts.add("type=" + type);
      if (parts.isEmpty())
        return "function call hop";
      return String.join(", ", parts);
    }

    private boolean isMultiReturnBuiltin(OpSig sig) {
      String type = attrValue(sig, ATTR_FCALL_TYPE);
      return type != null && type.equalsIgnoreCase(MULTIRETURN_BUILTIN_TYPE);
    }
  }

  /**
   * Specialized rule for .builtinNS::m_kmeans so it can keep a FED placement when any input
   * (the matrix argument) is federated. The body still runs in CP, but this lets the planner
   * consider privacy-compliant federated orchestrations.
   */
  public static final class BuiltinMKMeansRule extends BaseRule {
    private static final String OPCODE_PREFIX = "fcall";
    private static final String NS = ".builtinNS";
    private static final String FUNC = "m_kmeans";
    private static final String DETAIL = "builtin m_kmeans placeholder";

    @Override public OpCategory category() { return OpCategory.OTHER; }
    @Override public Set<String> opcodes() { return null; }

    @Override
    public boolean supports(OpSig sig) {
      if (sig == null)
        return false;
      String opcode = normalizedOpcode(sig);
      if (!opcode.startsWith(OPCODE_PREFIX))
        return false;
      String ns = attrValue(sig, ATTR_FCALL_NAMESPACE);
      String name = attrValue(sig, ATTR_FCALL_NAME);
      return ns != null && ns.equalsIgnoreCase(NS)
          && name != null && name.equalsIgnoreCase(FUNC);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      if (!supports(sig))
        return FTypeProfile.empty();
      return primaryLikeProfile(inFTypeCandidates);
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      if (!supports(sig))
        return cpCaps(sig, ReasonCode.OPCODE_UNSUPPORTED);
      Optional<FType> passthrough = firstMeaningfulInputType(inFTypes);
      if (!passthrough.isPresent())
        return cpCaps(sig, ReasonCode.NO_FED_INPUT);
      return OpCaps.newBuilder()
          .category(sig.category())
          .opcode(sig.opcode())
          .exec(ExecType.FED)
          .fout(true, passthrough.get())
          .reason(ReasonCode.OK)
          .detail(DETAIL)
          .build();
    }

    private Optional<FType> firstMeaningfulInputType(List<FType> inFTypes) {
      if (inFTypes == null || inFTypes.isEmpty())
        return Optional.empty();
      FType fallback = null;
      for (FType t : inFTypes) {
        if (isFederatedLike(t))
          return Optional.of(t);
        if (fallback == null && t != null)
          fallback = t;
      }
      return Optional.ofNullable(fallback);
    }
  }
}
