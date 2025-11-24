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
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Rule;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Rules CORE (single file)
 * - Registry + Engines
 * - Fallback order: opcode -> (category + supports)
 * - CP -> LOUT (policy)
 */
public final class RulesCore {

  private RulesCore() {}

  private static OpCaps cpDefault(OpSig sig, ReasonCode reason) {
    OpCategory category = (sig != null) ? sig.category() : OpCategory.OTHER;
    String opcode = (sig != null) ? sig.opcode() : "";
    return OpCaps.newBuilder()
        .category(category)
        .opcode(opcode)
        .exec(ExecType.CP)
        .placement(FederatedOutput.LOUT)
        .reason(reason)
        .build();
  }

  // --- Common base with safe defaults ----------------------------------------------------------
  public abstract static class BaseRule implements Rule {
    @Override public Set<String> opcodes() { return Collections.emptySet(); }
    @Override public boolean supports(OpSig sig) { return false; }
    @Override public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return FTypeProfile.empty();
    }
    @Override public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      return cpDefault(sig, ReasonCode.NOT_IMPLEMENTED);
    }
  }

  // Shared helpers across rule implementations.
  public static final class RuleUtils {
    private RuleUtils() {}

    public static boolean sideIsMatrix(Map<String,String> attrs, long[] dims, String argKey) {
      if (attrs != null) {
        String v = attrs.get(argKey);
        if (v != null) {
          if ("MATRIX".equalsIgnoreCase(v)) return true;
          if ("SCALAR".equalsIgnoreCase(v)) return false;
        }
      }

      if (dims != null && dims.length == 2) {
        long r = dims[0], c = dims[1];
        if (r == 1 && c == 1) return false;
        if (r == -1 || c == -1) return true;
        return (r != 1 || c != 1);
      }
      return false;
    }
  }

  /**
   * Representation change guard helper shared by FED rules (compile-time only).
   *
   * <p>This mirrors {@code ComputationFEDInstruction.checkGuardedRepresentationChange(...)} semantics
   * to avoid placing the planner/rule layer in charge of runtime state.</p>
   */
  public static final class Guard {
    /**
     * Local replica of the runtime safety margin (OptimizerUtils.SAFE_REP_CHANGE_THRES) to keep
     * guard semantics aligned even if runtime constants diverge.
     */
    static final long SAFE_REP_CHANGE_THRES = 8L * 1024 * 1024;

    private Guard() {}

    public static Result eval(OpSig sig) {
      Map<String,String> attrs = (sig == null || sig.attrs() == null)
          ? Collections.emptyMap()
          : sig.attrs();

      Result overrideDecision = override(attrs);
      if (overrideDecision != null)
        return overrideDecision;

      if (isSingleNode(attrs.get("rc.execMode")) && isFalse(attrs.get("rc.cachingActive"))) {
        return Result.pass("execMode=SINGLE_NODE,cachingInactive");
      }

      Long memReq = parseNonNegativeLong(attrs.get("rc.memReqEstBytes"));
      Long memIn1 = parseNonNegativeLong(attrs.get("rc.memIn1EstBytes"));
      Long memIn2 = parseNonNegativeLong(attrs.get("rc.memIn2EstBytes"));
      if (memReq != null && memIn1 != null && memIn2 != null) {
        long budget = saturatingAdd(saturatingAdd(memIn1, memIn2), SAFE_REP_CHANGE_THRES);
        if (memReq < budget) {
          return Result.pass(String.format(Locale.ROOT, "memReq=%d,budget=%d", memReq, budget));
        }
        return Result.fail(String.format(Locale.ROOT, "memReq=%d,budget=%d", memReq, budget));
      }

      if (allowUnknown(attrs.get("rc.guardDefaultIfUnknown"))) {
        return Result.pass("guardDefaultIfUnknown=allow");
      }
      return Result.unknown("insufficient guard hints");
    }

    private static Result override(Map<String,String> attrs) {
      String raw = attrs.get("rc.guardOverride");
      if (raw == null)
        return null;
      if ("true".equalsIgnoreCase(raw))
        return Result.pass("override=true");
      if ("false".equalsIgnoreCase(raw))
        return Result.fail("override=false");
      return Result.unknown("override unparsable");
    }

    private static boolean isSingleNode(String execMode) {
      return execMode != null && "SINGLE_NODE".equalsIgnoreCase(execMode);
    }

    private static boolean isFalse(String v) {
      return v != null && "false".equalsIgnoreCase(v);
    }

    private static boolean allowUnknown(String v) {
      return v != null && "allow".equalsIgnoreCase(v);
    }

    private static Long parseNonNegativeLong(String raw) {
      if (raw == null || raw.isBlank())
        return null;
      try {
        long parsed = Long.parseLong(raw.trim());
        return (parsed < 0) ? null : parsed;
      } catch (NumberFormatException nfe) {
        return null;
      }
    }

    private static long saturatingAdd(long a, long b) {
      return (a > 0 && b > Long.MAX_VALUE - a)
          ? Long.MAX_VALUE
          : a + b;
    }

    public static final class Result {
      private final boolean decided;
      private final boolean pass;
      private final String detail;

      private Result(boolean decided, boolean pass, String detail) {
        this.decided = decided;
        this.pass = pass;
        this.detail = (detail == null) ? "" : detail;
      }

      public boolean isPass() { return decided && pass; }
      public boolean isFail() { return decided && !pass; }
      public boolean isUnknown() { return !decided; }
      public String detail() { return detail; }

      static Result pass(String detail) { return new Result(true, true, detail); }
      static Result fail(String detail) { return new Result(true, false, detail); }
      static Result unknown(String detail) { return new Result(false, false, detail); }
    }
  }

  // --- Registry ---------------------------------------------------------------------------------
  public static final class RuleRegistry {
    private final EnumMap<OpCategory, List<Rule>> byCategory = new EnumMap<>(OpCategory.class);
    private final Map<String, Rule> byOpcode = new HashMap<>();

    public RuleRegistry() {
      for (OpCategory c : OpCategory.values()) {
        byCategory.put(c, new ArrayList<>());
      }
    }

    public void register(Rule rule) {
      Objects.requireNonNull(rule, "rule");
      byCategory.get(rule.category()).add(rule);
      Set<String> opcodes = rule.opcodes();
      if (opcodes == null)
        return;
      for (String op : opcodes) {
        final String key = norm(op);
        if (key.isEmpty())
          continue;
        if (byOpcode.containsKey(key)) {
          Rule existing = byOpcode.get(key);
          throw new IllegalArgumentException("Duplicate opcode registration: " + op
              + " (normalized=" + key + ") for " + rule.getClass().getName()
              + " already registered by " + (existing == null ? "unknown" : existing.getClass().getName()));
        }
        byOpcode.put(key, rule);
      }
    }

    public Optional<Rule> byOpcode(String opcode) {
      return Optional.ofNullable(byOpcode.get(norm(opcode)));
    }

    public List<Rule> ofCategory(OpCategory c) {
      List<Rule> bucket = byCategory.get(c);
      if (bucket == null)
        return Collections.emptyList();
      return Collections.unmodifiableList(bucket);
    }

    public List<Rule> allRules() {
      List<Rule> res = new ArrayList<>();
      for (List<Rule> bucket : byCategory.values()) {
        res.addAll(bucket);
      }
      return Collections.unmodifiableList(res);
    }

    private static String norm(String s) {
      if (s == null)
        return "";
      String lower = s.toLowerCase(Locale.ROOT);
      StringBuilder sb = new StringBuilder(lower.length());
      for (int i = 0; i < lower.length(); i++) {
        char ch = lower.charAt(i);
        if (ch == '_')
          continue;
        if (ch == '-') {
          boolean leftAlphaNum = (i > 0) && Character.isLetterOrDigit(lower.charAt(i - 1));
          boolean rightAlphaNum = (i + 1 < lower.length())
              && Character.isLetterOrDigit(lower.charAt(i + 1));
          if (leftAlphaNum || rightAlphaNum)
            continue;
        }
        sb.append(ch);
      }
      return sb.toString();
    }
  }

  // --- Engines ----------------------------------------------------------------------------------
  public static final class InferenceEngine {
    private final RuleRegistry reg;
    public InferenceEngine(RuleRegistry reg) { this.reg = Objects.requireNonNull(reg); }

    public FTypeProfile infer(OpSig sig, List<List<FType>> inCandidates, ShapeHint hint) {
      Optional<Rule> r = reg.byOpcode(sig.opcode());
      if (r.isPresent()) {
        return safeProfile(r.get(), sig, inCandidates, hint);
      }

      for (Rule rr : reg.ofCategory(sig.category())) {
        if (rr.supports(sig)) {
          return safeProfile(rr, sig, inCandidates, hint);
        }
      }

      return FTypeProfile.empty();
    }

    private static FTypeProfile safeProfile(Rule rule, OpSig sig, List<List<FType>> inCandidates, ShapeHint hint) {
      try {
        return rule.profile(sig, inCandidates, hint);
      } catch (Throwable t) {
        return FTypeProfile.empty();
      }
    }
  }

  public static final class OracleEngine {
    private final RuleRegistry reg;
    public OracleEngine(RuleRegistry reg) { this.reg = Objects.requireNonNull(reg); }

    public OpCaps decide(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      Optional<Rule> r = reg.byOpcode(sig.opcode());
      if (r.isPresent()) {
        return safeCaps(r.get(), sig, inFTypes, hint, ReasonCode.NOT_IMPLEMENTED);
      }

      for (Rule rr : reg.ofCategory(sig.category())) {
        if (rr.supports(sig)) {
          return safeCaps(rr, sig, inFTypes, hint, ReasonCode.NOT_IMPLEMENTED);
        }
      }

      return cpDefault(sig, ReasonCode.NO_RULE);
    }

    private static OpCaps safeCaps(Rule rule, OpSig sig, List<FType> inFTypes, ShapeHint hint, ReasonCode fallbackReason) {
      try {
        OpCaps caps = rule.caps(sig, inFTypes, hint);
        return (caps == null) ? cpDefault(sig, fallbackReason) : caps;
      } catch (Throwable t) {
        return cpDefault(sig, ReasonCode.RULE_ERROR);
      }
    }
  }

  // --- Module bootstrap: register all rules -----------------------------------------------------
  public static final class RulesModule {
    private RulesModule() {}

    public static RuleRegistry createDefaultRegistry() {
      RuleRegistry rr = new RuleRegistry();

      rr.register(new Rulesets.UnaryElemwiseRule());
      rr.register(new Rulesets.UnaryCumulativeRule());
      rr.register(new Rulesets.ReorgUnaryRule());
      rr.register(new Rulesets.ReshapeRule());
      rr.register(new Rulesets.ReblockRule());
      rr.register(new Rulesets.WeightedSquaredLossRule());
      rr.register(new Rulesets.WeightedCrossEntropyRule());
      rr.register(new Rulesets.WeightedSigmoidRule());
      rr.register(new Rulesets.WeightedUnaryMMRule());
      rr.register(new Rulesets.WeightedDivMMRule());
      rr.register(new Rulesets.AggTernaryRule());
      rr.register(new Rulesets.AggUnaryRule());
      rr.register(new Rulesets.AppendRule());
      rr.register(new Rulesets.FrameMapRule());
      rr.register(new Rulesets.TernaryElemwiseRule());
      rr.register(new Rulesets.BinaryElemwiseRule());
      rr.register(new Rulesets.MMFedRule());
      rr.register(new Rulesets.BinaryMMRule());
      rr.register(new Rulesets.CastRule());
      rr.register(new Rulesets.VariableWriteRule());
      rr.register(new Rulesets.CentralMomentRule());
      rr.register(new Rulesets.CovarianceRule());
      rr.register(new Rulesets.CtableRule());
      rr.register(new Rulesets.CumulativeOffsetRule());
      rr.register(new Rulesets.LeftIndexRule());
      rr.register(new Rulesets.MMChainRule());
      rr.register(new Rulesets.QuantileInterquantileCtableDenyRule());
      rr.register(new Rulesets.QuantileSortRule());
      rr.register(new Rulesets.QuantilePickRule());
      rr.register(new Rulesets.RightIndexRule());
      rr.register(new Rulesets.SolveRule());
      rr.register(new Rulesets.TransformEncodeRule());
      rr.register(new Rulesets.SpoofCellwiseRule());
      rr.register(new Rulesets.SpoofRowwiseRule());
      rr.register(new Rulesets.SpoofMultiAggregateRule());
      rr.register(new Rulesets.SpoofOuterProductRule());
      rr.register(new Rulesets.TsmmRule());
      rr.register(new Rulesets.TransientWriteRule());
      rr.register(new Rulesets.TransientReadRule());
      rr.register(new Rulesets.FunctionCallRule());
      rr.register(new Rulesets.BuiltinMKMeansRule());

      return rr;
    }
  }
}
