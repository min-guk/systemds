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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Rules API (compile-only scaffolding).
 *
 * <p>This layer intentionally lives in isolation from the runtime/planner to allow
 * easy experimentation. All state required by the rule engine must be funneled
 * through the types declared here.</p>
 */
public final class RulesApi {

  private RulesApi() {}

  // Why a decision was taken / or not taken
  public enum ReasonCode {
    OK,
    INFO,
    NO_RULE,
    NOT_IMPLEMENTED,
    CP_REQUIRES_LOUT,
    PARTITION_FORBIDDEN,
    FOUT_DISALLOWED_FOR_PART_OUT,
    FOUT_ALLOWED_ONLY_IF_FORCED,
    FOUT_NOT_SUPPORTED_BY_RUNTIME,
    NOT_FEDERATED_INPUTS,
    UNSUPPORTED_ALIGNMENT,
    RULE_ERROR,
    NO_FED_INPUT,
    DIM_MISMATCH_ROWS,
    DIM_MISMATCH_COLS,
    PREFER_BIND_ROW,
    PREFER_BIND_COL,
    BROADCAST_OR_ALIGNED_ROW,
    BROADCAST_OR_ALIGNED_COL,
    ALIGNED_HINT,
    FULL_MULTI_PARTITIONS_UNSUPPORTED,
    PART_REQUIRES_OTHER_NONFED,
    SHAPE_BROADCAST_MISMATCH,
    UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY,
    FULL_AGG_REQUIRES_CONSOLIDATION,
    SCALAR_CANNOT_BE_FEDERATED,
    VAR_REQUIRES_CONSOLIDATION,
    PARTITION_MISMATCH_PART_NOT_SUPPORTED,
    NON_ALIGNED_INPUT_FTYPE,
    MISSING_IN_FTYPE,
    OPCODE_UNSUPPORTED,
    ARITY_MISMATCH,
    BROADCAST_CONSTRAINT,
    OP_SHAPE_INCOMPATIBLE,
    UNALIGNED_OR_INSUFFICIENT_FED_INPUTS,
    NOT_IMPLEMENTED_FED_MATRIX_OUT,
    MISSING_FED_INSTRUCTION,
    REPR_CHANGE_GUARD_PASS,
    REPR_CHANGE_GUARD_FAIL,
    REPR_CHANGE_GUARD_UNKNOWN
  }

  // Operation categories: extend as new rules are introduced
  public enum OpCategory {
    AGG_UNARY,
    AGG_TERNARY,
    BINARY_MM,
    BINARY_EWISE,
    APPEND,
    INDEXING,
    REORG,
    RESHAPE,
    TSMM,
    SPOOF,
    QUATERNARY,
    QUANTILE_PICK,
    QUANTILE_SORT,
    VARIABLE_CAST,
    OTHER
  }

  /**
   * Immutable signature object of an operation.
   *
   * <p>Attributes funnel runtime planner hints into the rules layer. When present, keys prefixed
   * with {@code rc.} expose guarded representation change metadata such as:
   * {@code rc.execMode}, {@code rc.cachingActive}, {@code rc.memReqEstBytes},
   * {@code rc.memIn1EstBytes}, {@code rc.memIn2EstBytes}, {@code rc.guardOverride},
   * {@code rc.reprChange}, and {@code rc.guardDefaultIfUnknown}.</p>
   */
  public static final class OpSig {
    public enum InputKind { MATRIX, FRAME, SCALAR, UNKNOWN }

    private final String opcode;
    private final OpCategory category;
    private final Map<String,String> attrs;
    private final List<InputKind> inputs;

    public OpSig(String opcode, OpCategory category, Map<String,String> attrs) {
      this(opcode, category, attrs, Collections.<InputKind>emptyList());
    }

    public OpSig(String opcode, OpCategory category, Map<String,String> attrs, InputKind... inputs) {
      this(opcode, category, attrs, inputs == null ? null : Arrays.asList(inputs));
    }

    public OpSig(String opcode, OpCategory category, Map<String,String> attrs, List<InputKind> inputs) {
      this.opcode = Objects.requireNonNull(opcode, "opcode");
      this.category = Objects.requireNonNull(category, "category");
      Map<String,String> tmp = new LinkedHashMap<>();
      if (attrs != null)
        tmp.putAll(attrs);
      this.attrs = Collections.unmodifiableMap(tmp);
      List<InputKind> kinds = new ArrayList<>();
      if (inputs != null)
        kinds.addAll(inputs);
      this.inputs = Collections.unmodifiableList(kinds);
    }

    public String opcode() { return opcode; }
    public OpCategory category() { return category; }
    public Map<String,String> attrs() { return attrs; }
    public int arity() { return inputs.size(); }

    public boolean inputIsMatrix(int idx) {
      return inputKind(idx) == InputKind.MATRIX;
    }

    public InputKind inputKind(int idx) {
      if (idx < 0 || idx >= inputs.size())
        return InputKind.UNKNOWN;
      return inputs.get(idx);
    }

    public static OpSig of(String opcode, OpCategory category, Map<String,String> attrs, InputKind... inputs) {
      return new OpSig(opcode, category, attrs, inputs);
    }
  }

  // Shape hint (unknowns as -1). `fullSinglePartition` is optional as it may not always be known.
  public static final class ShapeHint {
    private final long rows;
    private final long cols;
    private final int blockSize;
    private final Optional<Boolean> fullSinglePartition;
    private final long rowsA;
    private final long colsA;
    private final long rowsB;
    private final long colsB;
	private final Map<String,String> consultedFacts = new java.util.TreeMap<>();

    public ShapeHint(long rows, long cols, int blockSize) {
      this(rows, cols, blockSize, Optional.<Boolean>empty(), -1, -1, -1, -1);
    }

    public ShapeHint(long rows, long cols, int blockSize, Boolean fullSinglePartition) {
      this(rows, cols, blockSize, Optional.ofNullable(fullSinglePartition), -1, -1, -1, -1);
    }

    public ShapeHint(long rows, long cols, int blockSize, Optional<Boolean> fullSinglePartition) {
      this(rows, cols, blockSize, fullSinglePartition, -1, -1, -1, -1);
    }

    public ShapeHint(long rows, long cols, int blockSize, Optional<Boolean> fullSinglePartition,
        long rowsA, long colsA, long rowsB, long colsB) {
      this.rows = rows;
      this.cols = cols;
      this.blockSize = blockSize;
      this.fullSinglePartition = fullSinglePartition == null
          ? Optional.<Boolean>empty()
          : fullSinglePartition;
      this.rowsA = rowsA;
      this.colsA = colsA;
      this.rowsB = rowsB;
      this.colsB = colsB;
    }

    public long rows() { record("rows", rows); return rows; }
    public long cols() { record("cols", cols); return cols; }
    public int blockSize() { record("blockSize", blockSize); return blockSize; }
	/**
	 * Returns raw values for diagnostics without recording semantic shape consultation.
	 * This observational snapshot must not be used as evidence of shape legality.
	 */
	public DiagnosticSnapshot diagnosticSnapshot() {
	  return new DiagnosticSnapshot(rows, cols, blockSize);
	}
	public record DiagnosticSnapshot(long rows, long cols, int blockSize) { }
    public Optional<Boolean> fullSinglePartition() {
      consultedFacts.put("fullSinglePartition", fullSinglePartition.map(String::valueOf).orElse("UNKNOWN"));
      return fullSinglePartition;
    }
    public long rowsA() { record("rowsA", rowsA); return rowsA; }
    public long colsA() { record("colsA", colsA); return colsA; }
    public long rowsB() { record("rowsB", rowsB); return rowsB; }
    public long colsB() { record("colsB", colsB); return colsB; }
    public boolean rowsKnown() { record("rowsA", rowsA); record("rowsB", rowsB); return rowsA >= 0 && rowsB >= 0; }
    public boolean colsKnown() { record("colsA", colsA); record("colsB", colsB); return colsA >= 0 && colsB >= 0; }
	public boolean wasConsulted() { return !consultedFacts.isEmpty(); }
	public ShapeProof proof() {
	  Set<String> required = Collections.unmodifiableSet(new java.util.TreeSet<>(consultedFacts.keySet()));
	  Set<String> missing = new java.util.TreeSet<>();
	  consultedFacts.forEach((name, value) -> { if("UNKNOWN".equals(value)) missing.add(name); });
	  return new ShapeProof(Collections.unmodifiableMap(new java.util.TreeMap<>(consultedFacts)), required,
	    Collections.unmodifiableSet(missing));
	}
	private void record(String name, long value) {
	  consultedFacts.put(name, value < 0 ? "UNKNOWN" : String.valueOf(value));
	}

    @Override public String toString() {
      return "ShapeHint{"
          + "rows=" + rows
          + ", cols=" + cols
          + ", blockSize=" + blockSize
          + ", fullSinglePartition=" + fullSinglePartition
          + ", rowsA=" + rowsA
          + ", colsA=" + colsA
          + ", rowsB=" + rowsB
          + ", colsB=" + colsB
          + '}';
    }
  }

  /** Immutable proof of the exact shape facts consulted by the selected rule. */
  public record ShapeProof(Map<String,String> consultedFacts, Set<String> requiredFacts, Set<String> missingRequiredFacts) {
	public ShapeProof {
	  consultedFacts = Collections.unmodifiableMap(new java.util.TreeMap<>(consultedFacts));
	  requiredFacts = Collections.unmodifiableSet(new java.util.TreeSet<>(requiredFacts));
	  missingRequiredFacts = Collections.unmodifiableSet(new java.util.TreeSet<>(missingRequiredFacts));
	}
  }

  // Simple FType propagation payload used by inference/testing helpers
  public static final class FTypeProfile {
    private final List<FType> outputs;

    private FTypeProfile(List<FType> outputs) {
      this.outputs = outputs;
    }

    public List<FType> outputs() { return outputs; }
    public List<FType> outs() { return outputs(); }

    public static FTypeProfile empty() {
      return new FTypeProfile(Collections.<FType>emptyList());
    }

    public static FTypeProfile ofOutput(List<FType> outs) {
      if (outs == null || outs.isEmpty())
        return empty();
      return new FTypeProfile(Collections.unmodifiableList(new ArrayList<>(outs)));
    }

    public static FTypeProfile outs(List<FType> outs) {
      return ofOutput(outs);
    }
  }

  // Final decision (Oracle)
  public static final class OpCaps {
    private final OpCategory category;
    private final String opcode;
    private final ExecType exec;
    private final FederatedOutput placement;
    private final FType foutFType;
    private final ReasonCode reason;
    private final String detail;
    private final List<DecisionNote> notes;

    private OpCaps(Builder b) {
      this.category = b.category;
      this.opcode = b.opcode;
      this.exec = b.exec;
      this.placement = b.placement;
      this.foutFType = b.foutFType;
      this.reason = b.reason;
      this.detail = b.detail;
      this.notes = Collections.unmodifiableList(new ArrayList<>(b.notes));
    }

    public OpCategory category() { return category; }
    public String opcode() { return opcode; }
    public ExecType exec() { return exec; }
    public FederatedOutput placement() { return placement; }
    public boolean foutEnabled() {
      return placement == FederatedOutput.FOUT;
    }
    public Optional<FType> foutFType() {
      return foutEnabled() ? Optional.ofNullable(foutFType) : Optional.<FType>empty();
    }
    public ReasonCode reason() { return reason; }
    public Optional<String> detail() {
      return (detail == null || detail.isBlank()) ? Optional.empty() : Optional.of(detail);
    }
    public List<DecisionNote> notes() { return notes; }

    public static Builder builder() { return new Builder(); }
    public static Builder newBuilder() { return builder(); }

    public static Builder allow(ExecType exec, FederatedOutput placement) {
      return builder().exec(exec).placement(placement);
    }

    public static Builder reject(ReasonCode reason) {
      return builder().reason(reason);
    }

    public static Builder reject(ReasonCode reason, String detail) {
      return reject(reason).detail(detail);
    }

    public static final class Builder {
      private OpCategory category = OpCategory.OTHER;
      private String opcode = "";
      private ExecType exec = ExecType.CP;
      private FederatedOutput placement = FederatedOutput.LOUT;
      private FType foutFType = null;
      private ReasonCode reason = ReasonCode.NOT_IMPLEMENTED;
      private String detail = "";
      private final List<DecisionNote> notes = new ArrayList<>();

      public Builder category(OpCategory v) { this.category = Objects.requireNonNull(v, "category"); return this; }
      public Builder opcode(String v) { this.opcode = (v == null) ? "" : v; return this; }
      public Builder exec(ExecType v) { this.exec = Objects.requireNonNull(v, "exec"); return this; }
      public Builder placement(FederatedOutput v) { this.placement = Objects.requireNonNull(v, "placement"); return this; }
      public Builder fout(boolean enabled) {
        if (enabled) {
          this.placement = FederatedOutput.FOUT;
        }
        else {
          this.placement = FederatedOutput.LOUT;
          this.foutFType = null;
        }
        return this;
      }

      public Builder fout(boolean enabled, FType type) {
        if (enabled) {
          this.placement = FederatedOutput.FOUT;
          this.foutFType = Objects.requireNonNull(type, "foutFType");
        } else {
          this.placement = FederatedOutput.LOUT;
          this.foutFType = null;
        }
        return this;
      }
      public Builder reason(ReasonCode v) { this.reason = Objects.requireNonNull(v, "reason"); return this; }
      public Builder detail(String v) { this.detail = (v == null) ? "" : v; return this; }
      public Builder note(ReasonCode code, String message) {
        notes.add(new DecisionNote(Objects.requireNonNull(code, "code"), message == null ? "" : message));
        return this;
      }

      public OpCaps build() { return new OpCaps(this); }
    }

    public static final class DecisionNote {
      private final ReasonCode code;
      private final String message;

      private DecisionNote(ReasonCode code, String message) {
        this.code = code;
        this.message = message;
      }

      public ReasonCode code() { return code; }
      public String message() { return message; }
    }
  }

  // FType propagation (per-op)
  public interface FTypeInfer {
    FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint);
  }

  // Oracle (per-op)
  public interface FedOracle {
    OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint);
  }

  // Rule contracts
  public interface Rule extends FTypeInfer, FedOracle {
    OpCategory category();
    Set<String> opcodes(); // Optional: can be empty; registry fallback will check supports(...)
    default boolean supports(OpSig sig) { return false; }
  }
}
