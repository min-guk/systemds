/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalCostModel.PhysicalCostSurface;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

/**
 * Exact shared-source relation encoding for certified global optimization.
 *
 * <p>The representation replaces copies of an upstream candidate reference in a consumer
 * alternative by one source variable per upstream owner. {@link ExactPhysicalOptimizer}
 * selects it only with an owner-bound numeric-optimum certificate.
 * Unsupported input returns the complete legacy model with an explicit
 * reason; it never drops a row, factor, forced constraint, or source axis.</p>
 */
final class ExactPhysicalSharedSourceEncoding {
	private static final String STRUCTURAL_TIE = "MINIMUM_ORIGINAL_ORDINAL";

	@FunctionalInterface
	interface Decoder {
		List<Integer> decode(List<Integer> assignment);
	}
	@FunctionalInterface
	interface OriginalAssignmentReconstructor {
		List<Integer> reconstruct(List<Integer> assignment);
	}
	@FunctionalInterface
	private interface ProjectedTupleVisitor {
		boolean visit(int[] tuple);
	}

	record Statistics(boolean transformed, String reason, int originalRows, int headerValues,
		int sourceVariables, long encodedTuples, long factorCells, long rawProfileCells,
		int truthQuotientAxes, int projectedLinks, int denseComposedLinks, String tiePolicy) {
		Statistics {
			Objects.requireNonNull(reason, "reason");
			Objects.requireNonNull(tiePolicy, "tiePolicy");
		}
	}

	static final class Encoding {
		private final PhysicalCostSurface sourceSurface;
		private final List<ExactCategoricalSolver.Variable> variables;
		private final List<ExactCategoricalSolver.Factor> factors;
		private final int decisionPrefixCount;
		private final Decoder decoder;
		private final OriginalAssignmentReconstructor reconstructor;
		private final Statistics statistics;

		private Encoding(PhysicalCostSurface sourceSurface, List<ExactCategoricalSolver.Variable> variables,
			List<ExactCategoricalSolver.Factor> factors, int decisionPrefixCount,
			Decoder decoder, OriginalAssignmentReconstructor reconstructor, Statistics statistics) {
			this.sourceSurface = Objects.requireNonNull(sourceSurface, "sourceSurface");
			this.variables = List.copyOf(variables);
			this.factors = List.copyOf(factors);
			this.decisionPrefixCount = decisionPrefixCount;
			this.decoder = Objects.requireNonNull(decoder, "decoder");
			this.reconstructor = Objects.requireNonNull(reconstructor, "reconstructor");
			this.statistics = Objects.requireNonNull(statistics, "statistics");
		}

		List<ExactCategoricalSolver.Variable> variables() { return variables; }
		List<ExactCategoricalSolver.Factor> factors() { return factors; }
		int decisionPrefixCount() { return decisionPrefixCount; }
		List<Integer> decode(List<Integer> assignment) { return decoder.decode(assignment); }
		List<Integer> reconstructOriginalAssignment(List<Integer> assignment) {
			return reconstructor.reconstruct(assignment);
		}
		Statistics statistics() { return statistics; }
		PhysicalCostSurface sourceSurface() { return sourceSurface; }
	}

	/** One occurrence of an upstream source selection in an original row. */
	record SourceSelection(Object owner, Object reference) {
		SourceSelection {
			Objects.requireNonNull(owner, "owner");
			Objects.requireNonNull(reference, "reference");
		}
	}

	/** Original alternative ordinal, its non-clause header, and its selected sources. */
	record RelationRow(int originalOrdinal, Object header, List<SourceSelection> selections) {
		RelationRow {
			if(originalOrdinal < 0)
				throw new IllegalArgumentException("EXACT_SHARED_SOURCE_ORDINAL_INVALID");
			Objects.requireNonNull(header, "header");
			selections = List.copyOf(selections);
		}
	}

	/** Raw values of one original factor incidence indexed by original alternative ordinal. */
	record RawIncidence(String key, double[] valuesByOriginalOrdinal) {
		RawIncidence {
			if(key == null || key.isBlank())
				throw new IllegalArgumentException("EXACT_SHARED_SOURCE_INCIDENCE_KEY_INVALID");
			valuesByOriginalOrdinal = Objects.requireNonNull(valuesByOriginalOrdinal,
				"valuesByOriginalOrdinal").clone();
		}

		@Override public double[] valuesByOriginalOrdinal() {
			return valuesByOriginalOrdinal.clone();
		}
	}

	/** Executable exact encoding of one conditional row relation. */
	static final class RelationEncoding {
		private final boolean supported;
		private final String reason;
		private final List<Object> sourceOwners;
		private final List<ExactCategoricalSolver.Variable> variables;
		private final List<ExactCategoricalSolver.Factor> factors;
		private final Map<IntTuple,List<Integer>> fibers;
		private final Statistics statistics;

		private RelationEncoding(boolean supported, String reason, List<Object> sourceOwners,
			List<ExactCategoricalSolver.Variable> variables,
			List<ExactCategoricalSolver.Factor> factors, Map<IntTuple,List<Integer>> fibers,
			Statistics statistics) {
			this.supported = supported;
			this.reason = reason;
			this.sourceOwners = List.copyOf(sourceOwners);
			this.variables = List.copyOf(variables);
			this.factors = List.copyOf(factors);
			this.fibers = Map.copyOf(fibers);
			this.statistics = statistics;
		}

		boolean supported() { return supported; }
		String reason() { return reason; }
		List<Object> sourceOwners() { return sourceOwners; }
		List<ExactCategoricalSolver.Variable> variables() { return variables; }
		List<ExactCategoricalSolver.Factor> factors() { return factors; }
		Statistics statistics() { return statistics; }

		List<Integer> fiber(int... encodedValues) {
			return fibers.getOrDefault(new IntTuple(encodedValues), List.of());
		}

		int decode(int... encodedValues) {
			List<Integer> fiber = fiber(encodedValues);
			if(fiber.isEmpty())
				throw new IllegalArgumentException("EXACT_SHARED_SOURCE_ILLEGAL_ENCODED_TUPLE");
			return fiber.get(0);
		}
	}

	private record IntTuple(int[] values) {
		private IntTuple { values = values.clone(); }
		@Override public int[] values() { return values.clone(); }
		@Override public boolean equals(Object other) {
			return other instanceof IntTuple that && Arrays.equals(values, that.values);
		}
		@Override public int hashCode() { return Arrays.hashCode(values); }
	}

	private record IdentityKey(Object value) {
		@Override public boolean equals(Object other) {
			return other instanceof IdentityKey that && value == that.value;
		}
		@Override public int hashCode() { return System.identityHashCode(value); }
	}

	private record CanonicalRow(int ordinal, int header, int[] selectedValueByOwner) { }
	private record SupplyProvenanceHeader(IdentityKey sourceDecision, Object valueVersion,
		IdentityKey durableAnchor) { }
	private record SupplyHeader(Object direction, int inputPosition,
		SupplyProvenanceHeader source, Object actionKind, IdentityKey action,
		Object targetState, Object targetLayout) { }
	private record BindingShape(Object kind, int inputPosition,
		IdentityKey sourceOwner, IdentityKey action) { }
	private record AlternativeHeader(
		ExactPhysicalNativeSupplyRepresentation.NativeCandidate nativeCandidate,
		List<SupplyHeader> supplies, List<BindingShape> bindingShapes) { }

	private static final class DomainView {
		private final int originalIndex;
		private final ExactPhysicalModel.DecisionDomain domain;
		private final ExactCategoricalSolver.Variable headerVariable;
		private final List<AlternativeHeader> headers;
		private final int[] headerByRow;
		private final List<CompiledHopKey> sourceOwners;
		private List<IdentityHashMap<CompiledHopKey,CandidateRealizationReference>> refsByRow;
		private List<IdentityHashMap<CompiledHopKey,Integer>> sourceOrdinalsByRow;

		private DomainView(int originalIndex, ExactPhysicalModel.DecisionDomain domain,
			List<AlternativeHeader> headers, int[] headerByRow,
			List<CompiledHopKey> sourceOwners,
			List<IdentityHashMap<CompiledHopKey,CandidateRealizationReference>> refsByRow) {
			this.originalIndex = originalIndex;
			this.domain = domain;
			this.headers = List.copyOf(headers);
			this.headerByRow = headerByRow.clone();
			this.sourceOwners = List.copyOf(sourceOwners);
			this.refsByRow = List.copyOf(refsByRow);
			headerVariable = new ExactCategoricalSolver.Variable(
				"exact-shared-source|decision=" + originalIndex + "|header", headers.size());
		}

		private void freezeSourceOrdinals(IdentityHashMap<CompiledHopKey,ReferenceView> references)
			throws Unsupported {
			// All reference domains, including demanded-only values, are complete now.
			// Resolve their structural equality once, retaining sparse owner identities;
			// these rows are metadata, not new factors or a dense owner/row product.
			List<IdentityHashMap<CompiledHopKey,Integer>> frozen = new ArrayList<>(refsByRow.size());
			for(IdentityHashMap<CompiledHopKey,CandidateRealizationReference> row : refsByRow) {
				IdentityHashMap<CompiledHopKey,Integer> ordinals = new IdentityHashMap<>(row.size());
				for(Map.Entry<CompiledHopKey,CandidateRealizationReference> entry : row.entrySet()) {
					ReferenceView reference = references.get(entry.getKey());
					Integer ordinal = reference == null ? null : reference.ordinals.get(entry.getValue());
					if(ordinal == null)
						throw new Unsupported("SOURCE_REFERENCE_DOMAIN_INCOMPLETE");
					ordinals.put(entry.getKey(), ordinal);
				}
				frozen.add(ordinals);
			}
			sourceOrdinalsByRow = List.copyOf(frozen);
			refsByRow = null;
		}

		private int sourceOrdinal(int row, int owner) {
			return sourceOrdinalsByRow.get(row).getOrDefault(sourceOwners.get(owner), -1);
		}
	}

	private static final class ReferenceView {
		private final DomainView owner;
		private final List<Object> values;
		private final Map<CandidateRealizationReference,Integer> ordinals;
		private final ExactCategoricalSolver.Variable variable;
		private final int[] selectedByHeader;

		private ReferenceView(DomainView owner, List<Object> values,
			Map<CandidateRealizationReference,Integer> ordinals, int[] selectedByHeader) {
			this.owner = owner;
			this.values = List.copyOf(values);
			this.ordinals = Map.copyOf(ordinals);
			this.selectedByHeader = selectedByHeader.clone();
			variable = new ExactCategoricalSolver.Variable(
				"exact-shared-source|decision=" + owner.originalIndex + "|reference", values.size());
		}
	}

	private record ObservationRecovery(int sourceVariable, int[] sourceToObservation) { }
	private record FrozenFactor(ExactCategoricalSolver.Factor factor, double[] evaluated,
		int cells) {
		double value(int cell) {
			return evaluated == null ? factor.denseCostAt(cell) : evaluated[cell];
		}
		long allocatedCells() { return evaluated == null ? 0 : cells; }
	}
	private record TruthQuotient(int[] domains, int[][] representatives,
		Map<Integer,int[]> composedLinks, Map<Integer,FrozenFactor> frozenTruthFactors,
		Map<Integer,ObservationRecovery> observationRecovery, int axes, long profiledCells,
		long retainedTemporaryCells) { }
	private record AxisPlan(int axis, DomainView view, boolean[] dependencies, int[] classes,
		boolean[][] boundByHeader, Map<IntTuple,List<Integer>> provenanceFibers) { }
	private record PreparedActual(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, Decoder decoder,
		OriginalAssignmentReconstructor reconstructor, Statistics statistics) { }
	private static final class Unsupported extends Exception {
		private static final long serialVersionUID = 1L;
		private Unsupported(String message) { super(message); }
	}
	private static final Object NO_SELECTED_REFERENCE = new Object();

	private ExactPhysicalSharedSourceEncoding() { }

	/** Production boundary; callers remain responsible for the separate numeric activation gate. */
	static Encoding prepare(ExactPhysicalModel model, PhysicalCostSurface surface,
		List<ExactCategoricalSolver.Factor> extraHardFactors,
		ExactCategoricalSolver.Limits limits) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(surface, "surface");
		Objects.requireNonNull(extraHardFactors, "extraHardFactors");
		Objects.requireNonNull(limits, "limits");
		if(surface.owner() != model.analysis())
			throw new IllegalArgumentException("EXACT_SHARED_SOURCE_OWNER_MISMATCH");
		List<ExactCategoricalSolver.Variable> decisions = model.variables();
		if(surface.variables().size() != decisions.size())
			throw new IllegalArgumentException("EXACT_SHARED_SOURCE_DECISION_PREFIX_MISMATCH");
		for(int ordinal = 0; ordinal < decisions.size(); ordinal++)
			if(surface.variables().get(ordinal) != decisions.get(ordinal))
				throw new IllegalArgumentException("EXACT_SHARED_SOURCE_DECISION_PREFIX_MISMATCH");

		List<ExactCategoricalSolver.Variable> legacyVariables = surface.exactSolverVariables();
		List<ExactCategoricalSolver.Factor> legacyFactors = new ArrayList<>(
			model.exactSolverHardFactors().size() + surface.exactSolverFactors().size()
				+ extraHardFactors.size());
		legacyFactors.addAll(model.exactSolverHardFactors());
		legacyFactors.addAll(surface.exactSolverFactors());
		legacyFactors.addAll(extraHardFactors);
		validateScopes(legacyVariables, legacyFactors);
		if(!extraHardFactors.isEmpty())
			return legacy(model, surface, legacyVariables, legacyFactors,
				"EXTRA_HARD_FACTORS_REQUIRE_CONGRUENCE");
		try {
			PreparedActual actual = prepareActual(model, legacyVariables, legacyFactors, limits, true);
			return new Encoding(surface, actual.variables(), actual.factors(), decisions.size(),
				actual.decoder(), actual.reconstructor(), actual.statistics());
		}
		catch(Unsupported unsupported) {
			return legacy(model, surface, legacyVariables, legacyFactors, unsupported.getMessage());
		}
		catch(ArithmeticException overflow) {
			return legacy(model, surface, legacyVariables, legacyFactors,
				"TRANSFORM_ARITHMETIC_OVERFLOW");
		}
	}

	static Encoding prepareWithoutProjectedLinksForTest(ExactPhysicalModel model,
		PhysicalCostSurface surface, ExactCategoricalSolver.Limits limits) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(surface, "surface");
		List<ExactCategoricalSolver.Variable> variables = surface.exactSolverVariables();
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(model.exactSolverHardFactors());
		factors.addAll(surface.exactSolverFactors());
		try {
			PreparedActual actual = prepareActual(model, variables, factors, limits, false);
			return new Encoding(surface, actual.variables(), actual.factors(), model.variables().size(),
				actual.decoder(), actual.reconstructor(), actual.statistics());
		}
		catch(Unsupported | ArithmeticException failure) {
			return legacy(model, surface, variables, factors, "DENSE_REFERENCE_PREPARATION_FAILED|"
				+ failure.getMessage());
		}
	}

	private static Encoding legacy(ExactPhysicalModel model, PhysicalCostSurface surface,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, String reason) {
		int decisions = model.variables().size();
		Statistics statistics = new Statistics(false, reason,
			model.domains().stream().mapToInt(domain -> domain.alternatives().size()).sum(),
			0, 0, 0, countCells(factors), 0, 0, 0, 0, STRUCTURAL_TIE);
		Decoder decoder = assignment -> {
			if(assignment == null || assignment.size() != variables.size())
				throw new IllegalArgumentException("EXACT_SHARED_SOURCE_ASSIGNMENT_SIZE_MISMATCH");
			return List.copyOf(assignment.subList(0, decisions));
		};
		OriginalAssignmentReconstructor reconstructor = assignment -> {
			if(assignment == null || assignment.size() != variables.size())
				throw new IllegalArgumentException("EXACT_SHARED_SOURCE_ASSIGNMENT_SIZE_MISMATCH");
			return List.copyOf(assignment);
		};
		return new Encoding(surface, variables, factors, decisions, decoder, reconstructor, statistics);
	}

	private static PreparedActual prepareActual(ExactPhysicalModel model,
		List<ExactCategoricalSolver.Variable> originalVariables,
		List<ExactCategoricalSolver.Factor> originalFactors,
		ExactCategoricalSolver.Limits limits, boolean projectCertifiedLinks) throws Unsupported {
		IdentityHashMap<ExactCategoricalSolver.Variable,DomainView> byVariable = new IdentityHashMap<>();
		IdentityHashMap<CompiledHopKey,DomainView> byDecision = new IdentityHashMap<>();
		IdentityHashMap<CompiledHopKey,Integer> decisionOrder = new IdentityHashMap<>();
		for(int index = 0; index < model.domains().size(); index++)
			decisionOrder.put(model.domains().get(index).node().key(), index);
		List<DomainView> domains = new ArrayList<>();
		int originalRows = 0;
		int headerValues = 0;
		for(int index = 0; index < model.domains().size(); index++) {
			DomainView view = buildDomain(model, index, model.domains().get(index), decisionOrder);
			domains.add(view);
			byVariable.put(view.domain.variable(), view);
			byDecision.put(view.domain.node().key(), view);
			originalRows += view.domain.alternatives().size();
			headerValues += view.headers.size();
		}
		LinkedHashSet<CompiledHopKey> referencedOwners = new LinkedHashSet<>();
		for(DomainView domain : domains)
			referencedOwners.addAll(domain.sourceOwners);
		IdentityHashMap<CompiledHopKey,ReferenceView> references = buildReferences(model,
			referencedOwners, byDecision, domains);
		for(DomainView domain : domains)
			domain.freezeSourceOrdinals(references);
		List<ReferenceView> orderedReferences = references.values().stream()
			.sorted(Comparator.comparingInt(reference -> reference.owner.originalIndex)).toList();

		TruthQuotient quotient = truthQuotient(model, originalVariables, originalFactors, limits);
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> originalPosition = positions(originalVariables);
		IdentityHashMap<ExactCategoricalSolver.Variable,ExactCategoricalSolver.Variable> mapped =
			new IdentityHashMap<>();
		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>(
			originalVariables.size() + orderedReferences.size());
		for(int index = 0; index < originalVariables.size(); index++) {
			ExactCategoricalSolver.Variable original = originalVariables.get(index);
			DomainView domain = byVariable.get(original);
			ExactCategoricalSolver.Variable replacement;
			if(domain != null)
				replacement = domain.headerVariable;
			else if(quotient.domains()[index] != original.domainSize())
				replacement = new ExactCategoricalSolver.Variable(
					original.key() + "|exact-truth-quotient", quotient.domains()[index]);
			else
				replacement = original;
			mapped.put(original, replacement);
			variables.add(replacement);
		}
		for(ReferenceView reference : orderedReferences)
			variables.add(reference.variable);

		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		long factorCells = 0;
		long rawProfileCells = quotient.profiledCells();
		int projectedLinks = 0;
		int denseComposedLinks = 0;
		for(int ordinal = 0; ordinal < originalFactors.size(); ordinal++) {
			ExactCategoricalSolver.Factor originalFactor = originalFactors.get(ordinal);
			boolean composedLink = quotient.composedLinks().containsKey(ordinal);
			FrozenFactor originalValues = composedLink ? null
				: quotient.frozenTruthFactors().get(ordinal);
			boolean retainedTruthProfile = originalValues != null;
			if(!composedLink && originalValues == null) {
				long available = remainingAllocation(limits.maximumMaterializedCells(), factorCells,
					quotient.retainedTemporaryCells(), 0,
					"ORIGINAL_FACTOR_TEMPORARY_LIMIT|ordinal=" + ordinal);
				originalValues = freezeProfileFactor(originalFactor, limits.maximumFactorCells(), available,
					"ORIGINAL_FACTOR_NOT_MATERIALIZABLE|ordinal=" + ordinal);
				rawProfileCells = Math.addExact(rawProfileCells, originalValues.cells());
			}
			List<ExactCategoricalSolver.Variable> oldScope = originalFactor.scope();
			int[] oldDomains = oldScope.stream()
				.mapToInt(ExactCategoricalSolver.Variable::domainSize).toArray();
			List<AxisPlan> plans = new ArrayList<>();
			List<ExactCategoricalSolver.Variable> scope = new ArrayList<>();
			for(int axis = 0; axis < oldScope.size(); axis++) {
				ExactCategoricalSolver.Variable oldVariable = oldScope.get(axis);
				DomainView domain = byVariable.get(oldVariable);
				if(domain == null) {
					addUnique(scope, mapped.get(oldVariable));
					continue;
				}
				int[] classes;
				if(axis == 0 && composedLink)
					classes = quotient.composedLinks().get(ordinal);
				else {
					int stride = 1;
					for(int next = axis + 1; next < oldScope.size(); next++)
						stride = Math.multiplyExact(stride, oldScope.get(next).domainSize());
					classes = factorAxisClasses(originalValues, oldVariable.domainSize(), stride);
				}
				boolean[] dependencies = dependency(domain, references, classes);
				plans.add(axisPlan(axis, domain, dependencies, classes, references));
				addUnique(scope, domain.headerVariable);
				for(int owner = 0; owner < dependencies.length; owner++)
					if(dependencies[owner])
						addUnique(scope, references.get(domain.sourceOwners.get(owner)).variable);
			}
			if(projectCertifiedLinks && composedLink && plans.size() == 1
				&& plans.get(0).axis() == 0 && oldScope.size() == 2) {
				List<ExactCategoricalSolver.Factor> projected = projectedLinkFactors(plans.get(0),
					mapped.get(oldScope.get(1)), quotient.composedLinks().get(ordinal), references,
					factorCells, quotient.retainedTemporaryCells(), limits,
					"PROJECTED_LINK_LIMIT|ordinal=" + ordinal);
				if(projected != null) {
					for(ExactCategoricalSolver.Factor factor : projected) {
						long projectedCells = cells(factor.scope());
						factorCells = checkedFactorBudget(factorCells, projectedCells, limits,
							"PROJECTED_LINK_LIMIT|ordinal=" + ordinal);
						factors.add(factor);
					}
					projectedLinks++;
					continue;
				}
			}
			if(composedLink)
				denseComposedLinks++;
			long cells = cells(scope);
			if(cells > Integer.MAX_VALUE || cells > limits.maximumFactorCells())
				throw new Unsupported("TRANSFORMED_FACTOR_LIMIT|ordinal=" + ordinal);
			factorCells = checkedFactorBudget(factorCells, cells, limits,
				"TRANSFORMED_MATERIALIZATION_LIMIT|ordinal=" + ordinal);
			checkedAllocationBudget(factorCells - cells, quotient.retainedTemporaryCells(),
				originalValues == null ? 0 : profileTemporaryCharge(
					retainedTruthProfile, originalValues.allocatedCells()), cells,
				limits.maximumMaterializedCells(),
				"TRANSFORMED_MATERIALIZATION_LIMIT|ordinal=" + ordinal);
			double[] transformed = new double[(int)cells];
			IdentityHashMap<ExactCategoricalSolver.Variable,Integer> localPosition = positions(scope);
			int[] local = new int[scope.size()];
			int[] oldLocal = new int[oldScope.size()];
			for(int cell = 0; cell < transformed.length; cell++) {
				decodeCell(cell, scope, local);
				boolean membershipLegal = true;
				for(int axis = 0; axis < oldScope.size(); axis++) {
					ExactCategoricalSolver.Variable oldVariable = oldScope.get(axis);
					DomainView domain = byVariable.get(oldVariable);
					if(domain == null) {
						int originalIndex = originalPosition.get(oldVariable);
						int quotientValue = local[localPosition.get(mapped.get(oldVariable))];
						oldLocal[axis] = quotient.representatives()[originalIndex][quotientValue];
					}
					else {
						AxisPlan plan = plan(plans, axis);
						oldLocal[axis] = findRow(plan, local, localPosition, references);
						if(oldLocal[axis] < 0) {
							membershipLegal = false;
							break;
						}
					}
				}
				if(!membershipLegal) {
					transformed[cell] = Double.POSITIVE_INFINITY;
					continue;
				}
				if(composedLink) {
					if(oldScope.size() != 2)
						throw new Unsupported("TRUTH_LINK_ARITY_MISMATCH|ordinal=" + ordinal);
					int observation = local[localPosition.get(mapped.get(oldScope.get(1)))];
					transformed[cell] = quotient.composedLinks().get(ordinal)[oldLocal[0]] == observation
						? 0.0 : Double.POSITIVE_INFINITY;
				}
				else
					transformed[cell] = originalValues.value(
						Math.toIntExact(encodeCell(oldLocal, oldDomains)));
			}
			factors.add(ExactCategoricalSolver.Factor.denseOwned(scope, transformed));
		}

		for(ReferenceView reference : orderedReferences) {
			long cells = (long)reference.owner.headers.size() * reference.values.size();
			factorCells = checkedFactorBudget(factorCells, cells, limits, "SOURCE_LINK_LIMIT");
			checkedAllocationBudget(factorCells - cells, quotient.retainedTemporaryCells(), 0, cells,
				limits.maximumMaterializedCells(), "SOURCE_LINK_LIMIT");
			double[] values = hardValues(cells);
			for(int header = 0; header < reference.selectedByHeader.length; header++)
				values[header * reference.values.size() + reference.selectedByHeader[header]] = 0.0;
			factors.add(ExactCategoricalSolver.Factor.denseOwned(
				List.of(reference.owner.headerVariable, reference.variable), values));
		}
		for(DomainView consumer : domains)
			for(int owner = 0; owner < consumer.sourceOwners.size(); owner++) {
				ReferenceView reference = references.get(consumer.sourceOwners.get(owner));
				long cells = (long)consumer.headers.size() * reference.values.size();
				factorCells = checkedFactorBudget(factorCells, cells, limits, "MEMBERSHIP_LIMIT");
				checkedAllocationBudget(factorCells - cells, quotient.retainedTemporaryCells(), 0, cells,
					limits.maximumMaterializedCells(), "MEMBERSHIP_LIMIT");
				double[] values = hardValues(cells);
				int sourceOwner = owner;
				fillMembershipValues(values, consumer.headers.size(), reference.values.size(),
					consumer.headerByRow, row -> consumer.sourceOrdinal(row, sourceOwner));
				factors.add(ExactCategoricalSolver.Factor.denseOwned(
					List.of(consumer.headerVariable, reference.variable), values));
			}

		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> encodedPositions = positions(variables);
		Decoder decoder = assignment -> {
			if(assignment == null || assignment.size() != variables.size())
				throw new IllegalArgumentException("EXACT_SHARED_SOURCE_ASSIGNMENT_SIZE_MISMATCH");
			int[] values = assignment.stream().mapToInt(Integer::intValue).toArray();
			List<Integer> decoded = new ArrayList<>(domains.size());
			for(DomainView domain : domains) {
				boolean[] all = new boolean[domain.sourceOwners.size()];
				Arrays.fill(all, true);
				decoded.add(decodeRow(domain, all, values, encodedPositions, references));
			}
			return List.copyOf(decoded);
		};
		OriginalAssignmentReconstructor reconstructor = assignment -> {
			if(assignment == null || assignment.size() != variables.size())
				throw new IllegalArgumentException("EXACT_SHARED_SOURCE_ASSIGNMENT_SIZE_MISMATCH");
			List<Integer> decisions = decoder.decode(assignment);
			int[] original = new int[originalVariables.size()];
			for(int index = 0; index < original.length; index++) {
				DomainView domain = byVariable.get(originalVariables.get(index));
				if(domain != null)
					original[index] = decisions.get(domain.originalIndex);
				else {
					int quotientValue = assignment.get(encodedPositions.get(
						mapped.get(originalVariables.get(index))));
					original[index] = quotient.representatives()[index][quotientValue];
				}
			}
			for(Map.Entry<Integer,ObservationRecovery> entry :
				quotient.observationRecovery().entrySet()) {
				ObservationRecovery recovery = entry.getValue();
				int sourceValue = original[recovery.sourceVariable()];
				original[entry.getKey()] = recoverOriginalObservation(sourceValue,
					recovery.sourceToObservation());
			}
			return Arrays.stream(original).boxed().toList();
		};
		long relationTuples = 0;
		for(DomainView domain : domains)
			for(int header = 0; header < domain.headers.size(); header++) {
				long tuples = 1;
				for(CompiledHopKey owner : domain.sourceOwners)
					tuples = saturatedMultiply(tuples, references.get(owner).values.size());
				relationTuples = saturatedAdd(relationTuples, tuples);
			}
		Statistics statistics = new Statistics(true, "EXACT_SHARED_SOURCE_TRUTH_QUOTIENT",
			originalRows, headerValues, orderedReferences.size(), relationTuples, factorCells,
			rawProfileCells, quotient.axes(), projectedLinks, denseComposedLinks, STRUCTURAL_TIE);
		return new PreparedActual(List.copyOf(variables), List.copyOf(factors), decoder,
			reconstructor, statistics);
	}

	private static DomainView buildDomain(ExactPhysicalModel model, int index,
		ExactPhysicalModel.DecisionDomain domain,
		IdentityHashMap<CompiledHopKey,Integer> decisionOrder) throws Unsupported {
		LinkedHashMap<AlternativeHeader,Integer> headerOrdinals = new LinkedHashMap<>();
		List<AlternativeHeader> headers = new ArrayList<>();
		int[] headerByRow = new int[domain.alternatives().size()];
		List<IdentityHashMap<CompiledHopKey,CandidateRealizationReference>> refsByRow =
			new ArrayList<>();
		LinkedHashSet<CompiledHopKey> owners = new LinkedHashSet<>();
		for(int row = 0; row < domain.alternatives().size(); row++) {
			ExactPhysicalModel.Alternative alternative = domain.alternatives().get(row);
			AlternativeHeader header = header(model, domain, row);
			Integer headerOrdinal = headerOrdinals.get(header);
			if(headerOrdinal == null) {
				headerOrdinal = headers.size();
				headerOrdinals.put(header, headerOrdinal);
				headers.add(header);
			}
			headerByRow[row] = headerOrdinal;
			IdentityHashMap<CompiledHopKey,CandidateRealizationReference> selected =
				new IdentityHashMap<>();
			CandidateRealizationSupportClause clause = alternative.supportClause();
			if(clause != null)
				for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
					CompiledHopKey owner = binding.source().rule().parentOccurrence();
					owners.add(owner);
					CandidateRealizationReference previous = selected.putIfAbsent(owner,
						binding.source());
					if(previous != null && !previous.equals(binding.source()))
						throw new Unsupported("CONFLICTING_REPEATED_SOURCE|decision=" + index);
				}
			refsByRow.add(selected);
		}
		List<CompiledHopKey> orderedOwners = owners.stream().sorted(Comparator.comparingInt(owner ->
			decisionOrder.getOrDefault(owner, Integer.MAX_VALUE))).toList();
		int originalHeaderCount = headers.size();
		for(int header = 0; header < originalHeaderCount; header++) {
			List<Integer> rows = new ArrayList<>();
			for(int row = 0; row < headerByRow.length; row++)
				if(headerByRow[row] == header)
					rows.add(row);
			List<CompiledHopKey> required = new ArrayList<>();
			for(CompiledHopKey owner : orderedOwners) {
				boolean any = rows.stream().anyMatch(row -> refsByRow.get(row).containsKey(owner));
				boolean all = rows.stream().allMatch(row -> refsByRow.get(row).containsKey(owner));
				if(any != all)
					throw new Unsupported("HEADER_MIXES_BOUND_AND_WILDCARD_SOURCE|decision=" + index);
				if(all)
					required.add(owner);
			}
			List<HashSet<CandidateRealizationReference>> projections = new ArrayList<>();
			for(int ignored = 0; ignored < required.size(); ignored++)
				projections.add(new HashSet<>());
			HashSet<List<CandidateRealizationReference>> tuples = new HashSet<>();
			for(int row : rows) {
				List<CandidateRealizationReference> tuple = new ArrayList<>();
				for(int owner = 0; owner < required.size(); owner++) {
					CandidateRealizationReference reference = refsByRow.get(row).get(required.get(owner));
					tuple.add(reference);
					projections.get(owner).add(reference);
				}
				tuples.add(List.copyOf(tuple));
			}
			long product = 1;
			for(HashSet<?> projection : projections)
				product = saturatedMultiply(product, projection.size());
			if(product != tuples.size()) {
				// Correlated support is a union of exact source tuples, not the
				// Cartesian product of its projections. Refine this internal header
				// by tuple instead of abandoning shared-source encoding for every
				// other decision. Membership factors then preserve precisely these
				// rows, including duplicate-proof fibers and demanded-only references.
				Map<List<CandidateRealizationReference>,Integer> refined = new LinkedHashMap<>();
				AlternativeHeader original = headers.get(header);
				for(int row : rows) {
					List<CandidateRealizationReference> tuple = required.stream()
						.map(owner -> refsByRow.get(row).get(owner)).toList();
					Integer refinedHeader = refined.get(tuple);
					if(refinedHeader == null) {
						refinedHeader = refined.isEmpty() ? header : headers.size();
						if(!refined.isEmpty()) headers.add(original);
						refined.put(tuple, refinedHeader);
					}
					headerByRow[row] = refinedHeader;
				}
			}
		}
		return new DomainView(index, domain, headers, headerByRow, orderedOwners, refsByRow);
	}

	private static AlternativeHeader header(ExactPhysicalModel model,
		ExactPhysicalModel.DecisionDomain authority, int originalOrdinal) {
		ExactPhysicalNativeSupplyRepresentation.Domain domain =
			model.nativeSupplyRepresentation().domain(authority.node().key());
		List<SupplyHeader> supplies = domain.supplies(originalOrdinal).stream().map(supply -> {
			var source = supply.source();
			return new SupplyHeader(supply.direction(), supply.inputPosition(),
				new SupplyProvenanceHeader(new IdentityKey(source == null ? null
					: source.sourceDecision()), source == null ? null : source.valueVersion(),
					new IdentityKey(source == null ? null : source.durableAnchor())),
				supply.actionKind(), new IdentityKey(supply.action()), supply.targetState(),
				supply.targetLayout());
		}).toList();
		ExactPhysicalModel.Alternative alternative = authority.alternatives().get(originalOrdinal);
		List<BindingShape> bindingShapes = alternative.supportClause() == null ? List.of()
			: alternative.supportClause().inputBindings().stream().map(binding ->
				new BindingShape(binding.kind(), binding.inputPosition(),
					new IdentityKey(binding.source().rule().parentOccurrence()),
					new IdentityKey(binding.relocationAction()))).toList();
		return new AlternativeHeader(domain.nativeCandidate(originalOrdinal), supplies, bindingShapes);
	}

	private static IdentityHashMap<CompiledHopKey,ReferenceView> buildReferences(
		ExactPhysicalModel model, LinkedHashSet<CompiledHopKey> owners,
		IdentityHashMap<CompiledHopKey,DomainView> byDecision, List<DomainView> domains)
		throws Unsupported {
		IdentityHashMap<CompiledHopKey,ReferenceView> references = new IdentityHashMap<>();
		List<CompiledHopKey> orderedOwners = owners.stream().sorted(Comparator.comparingInt(owner -> {
			DomainView domain = byDecision.get(owner);
			return domain == null ? Integer.MAX_VALUE : domain.originalIndex;
		})).toList();
		for(CompiledHopKey owner : orderedOwners) {
			DomainView ownerDomain = byDecision.get(owner);
			if(ownerDomain == null)
				throw new Unsupported("MISSING_SOURCE_OWNER_DOMAIN");
			LinkedHashMap<CandidateRealizationReference,Integer> ordinals = new LinkedHashMap<>();
			List<Object> values = new ArrayList<>();
			int noneOrdinal = -1;
			for(ExactPhysicalModel.Alternative alternative : ownerDomain.domain.alternatives()) {
				CandidateRealizationReference reference = candidateReference(model, alternative);
				if(reference == null && noneOrdinal < 0) {
					noneOrdinal = values.size();
					values.add(NO_SELECTED_REFERENCE);
				}
				else if(reference != null && !ordinals.containsKey(reference)) {
					ordinals.put(reference, values.size());
					values.add(reference);
				}
			}
			for(DomainView consumer : domains)
				for(IdentityHashMap<CompiledHopKey,CandidateRealizationReference> row : consumer.refsByRow) {
					CandidateRealizationReference demanded = row.get(owner);
					if(demanded != null && !ordinals.containsKey(demanded)) {
						// Retain the consumer row without inventing an owner choice: the unchanged
						// owner H->R link leaves this demanded-only value entirely infeasible.
						ordinals.put(demanded, values.size());
						values.add(demanded);
					}
				}
			int[] selectedByHeader = new int[ownerDomain.headers.size()];
			Arrays.fill(selectedByHeader, -1);
			for(int row = 0; row < ownerDomain.domain.alternatives().size(); row++) {
				CandidateRealizationReference selected = candidateReference(model,
					ownerDomain.domain.alternatives().get(row));
				int selectedValue = selected == null ? noneOrdinal : ordinals.getOrDefault(selected, -1);
				if(selectedValue < 0)
					throw new Unsupported("SOURCE_REFERENCE_DOMAIN_INCOMPLETE");
				int header = ownerDomain.headerByRow[row];
				if(selectedByHeader[header] < 0)
					selectedByHeader[header] = selectedValue;
				else if(selectedByHeader[header] != selectedValue)
					throw new Unsupported("HEADER_DOES_NOT_DETERMINE_SELECTED_REFERENCE");
			}
			references.put(owner, new ReferenceView(ownerDomain, values, ordinals, selectedByHeader));
		}
		return references;
	}

	private static TruthQuotient truthQuotient(ExactPhysicalModel model,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, ExactCategoricalSolver.Limits limits)
		throws Unsupported {
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> variablePositions = positions(variables);
		IdentityHashMap<ExactCategoricalSolver.Factor,Integer> factorPositions = new IdentityHashMap<>();
		for(int ordinal = 0; ordinal < factors.size(); ordinal++)
			factorPositions.put(factors.get(ordinal), ordinal);
		int[] degree = new int[variables.size()];
		for(ExactCategoricalSolver.Factor factor : factors)
			for(ExactCategoricalSolver.Variable variable : factor.scope())
				degree[variablePositions.get(variable)]++;
		int[] domains = variables.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize).toArray();
		int[][] representatives = new int[domains.length][];
		for(int variable = 0; variable < domains.length; variable++) {
			representatives[variable] = new int[domains[variable]];
			for(int value = 0; value < domains[variable]; value++)
				representatives[variable][value] = value;
		}
		Map<Integer,int[]> composed = new LinkedHashMap<>();
		Map<Integer,FrozenFactor> frozenTruth = new LinkedHashMap<>();
		Map<Integer,ObservationRecovery> recovery = new LinkedHashMap<>();
		int axes = 0;
		long profiled = 0;
		long retainedTemporaryCells = 0;
		for(ExactPhysicalModel.HardFactorEncoding encoding : model.hardFactorEncodings()) {
			var decomposition = encoding.decomposition();
			// Pool proof circuits already expose small local factors. They have no
			// single truth table or degree-two observation axes to quotient here.
			if(!decomposition.isObservationStar())
				continue;
			List<ExactCategoricalSolver.Factor> local = decomposition.solverFactors();
			List<int[]> observations = decomposition.observations();
			Integer truthOrdinal = factorPositions.get(local.get(local.size() - 1));
			if(truthOrdinal == null)
				throw new Unsupported("HARD_DECOMPOSITION_TRUTH_FACTOR_MISSING");
			ExactCategoricalSolver.Factor truthFactor = factors.get(truthOrdinal);
			long available = remainingAllocation(limits.maximumMaterializedCells(),
				retainedTemporaryCells, 0, 0, "TRUTH_FACTOR_TEMPORARY_LIMIT");
			FrozenFactor truth = freezeProfileFactor(truthFactor, limits.maximumFactorCells(), available,
				"TRUTH_FACTOR_LIMIT");
			frozenTruth.put(truthOrdinal, truth);
			profiled = Math.addExact(profiled, truth.cells());
			retainedTemporaryCells = Math.addExact(retainedTemporaryCells,
				truth.allocatedCells());
			int stride = 1;
			for(int axis = truthFactor.scope().size() - 1; axis >= 0; axis--) {
				ExactCategoricalSolver.Variable observation = truthFactor.scope().get(axis);
				int observationIndex = variablePositions.get(observation);
				if(degree[observationIndex] != 2)
					throw new Unsupported("OBSERVATION_DEGREE_NOT_TWO");
				int[] classes = factorAxisClasses(truth, observation.domainSize(), stride);
				int[] reps = ExactFactorValueClasses.representatives(classes);
				Integer linkOrdinal = factorPositions.get(local.get(axis));
				if(linkOrdinal == null)
					throw new Unsupported("HARD_DECOMPOSITION_LINK_FACTOR_MISSING");
				ExactCategoricalSolver.Factor linkFactor = factors.get(linkOrdinal);
				if(linkFactor.scope().size() != 2 || linkFactor.scope().get(1) != observation)
					throw new Unsupported("HARD_DECOMPOSITION_LINK_SHAPE");
				if(linkFactor.scope().get(0) != encoding.canonicalFactor().scope().get(axis))
					throw new Unsupported("HARD_DECOMPOSITION_LINK_SOURCE_MISMATCH");
				int[] sourceToObservation = observations.get(axis);
				if(sourceToObservation.length != linkFactor.scope().get(0).domainSize())
					throw new Unsupported("HARD_DECOMPOSITION_OBSERVATION_DOMAIN_MISMATCH");
				int[] sourceToClass = composeObservationClasses(classes, sourceToObservation,
					observation.domainSize());
				domains[observationIndex] = reps.length;
				representatives[observationIndex] = reps;
				composed.put(linkOrdinal, sourceToClass);
				recovery.put(observationIndex, new ObservationRecovery(
					variablePositions.get(linkFactor.scope().get(0)), sourceToObservation.clone()));
				axes++;
				stride = Math.multiplyExact(stride, observation.domainSize());
			}
		}
		return new TruthQuotient(domains, representatives,
			Collections.unmodifiableMap(new LinkedHashMap<>(composed)),
			Collections.unmodifiableMap(new LinkedHashMap<>(frozenTruth)),
			Collections.unmodifiableMap(new LinkedHashMap<>(recovery)), axes, profiled,
			retainedTemporaryCells);
	}

	static int[] composeObservationClasses(int[] truthClasses, int[] sourceToObservation,
		int observationDomain) throws Unsupported {
		Objects.requireNonNull(truthClasses, "truthClasses");
		Objects.requireNonNull(sourceToObservation, "sourceToObservation");
		if(observationDomain <= 0 || truthClasses.length != observationDomain)
			throw new Unsupported("HARD_DECOMPOSITION_TRUTH_DOMAIN_MISMATCH");
		int[] sourceToClass = new int[sourceToObservation.length];
		for(int source = 0; source < sourceToClass.length; source++) {
			int selected = sourceToObservation[source];
			if(selected < 0 || selected >= observationDomain)
				throw new Unsupported("HARD_DECOMPOSITION_OBSERVATION_VALUE_INVALID");
			sourceToClass[source] = truthClasses[selected];
		}
		return sourceToClass;
	}

	static int recoverOriginalObservation(int originalSourceValue, int[] sourceToObservation) {
		Objects.requireNonNull(sourceToObservation, "sourceToObservation");
		if(originalSourceValue < 0 || originalSourceValue >= sourceToObservation.length)
			throw new IllegalArgumentException("EXACT_SHARED_SOURCE_RECOVERY_SOURCE_INVALID");
		return sourceToObservation[originalSourceValue];
	}

	static int[] profileAxisClassesForTest(ExactCategoricalSolver.Factor factor, int domain,
		int stride, ExactCategoricalSolver.Limits limits) {
		try {
			FrozenFactor frozen = freezeProfileFactor(factor, limits.maximumFactorCells(),
				limits.maximumMaterializedCells(), "TEST_PROFILE_LIMIT");
			return factorAxisClasses(frozen, domain, stride);
		}
		catch(Unsupported failure) {
			throw new IllegalArgumentException(failure.getMessage());
		}
	}

	static boolean materializationFitsForTest(long current, long next, long limit) {
		try {
			checkedAllocationBudget(current, 0, 0, next, limit, "TEST_LIMIT");
			return true;
		}
		catch(Unsupported | ArithmeticException failure) {
			return false;
		}
	}

	static boolean projectedOutputFitsForTest(int headerDomain, int quotientDomain,
		int[] dependencyDomains, long retainedOutput, long retainedTemporary,
		ExactCategoricalSolver.Limits limits) {
		return projectedOutputFits(headerDomain, quotientDomain, dependencyDomains,
			retainedOutput, retainedTemporary, limits, "TEST_PROJECTED_LIMIT");
	}

	private static long profileTemporaryCharge(boolean retained, long allocatedCells) {
		if(allocatedCells < 0)
			throw new IllegalArgumentException("EXACT_SHARED_SOURCE_NEGATIVE_PROFILE_ALLOCATION");
		return retained ? 0 : allocatedCells;
	}

	static long profileTemporaryChargeForTest(boolean retained, long allocatedCells) {
		return profileTemporaryCharge(retained, allocatedCells);
	}

	private static boolean[] dependency(DomainView domain,
		IdentityHashMap<CompiledHopKey,ReferenceView> references, int[] classes)
		throws Unsupported {
		boolean[] dependency = new boolean[domain.sourceOwners.size()];
		Arrays.fill(dependency, true);
		if(!functional(domain, references, classes, dependency))
			throw new Unsupported("RAW_FACTOR_NOT_FUNCTIONAL|decision=" + domain.originalIndex);
		// Exact greedy deletion has no source-count cap. It starts from the conservative
		// all-source dependency and removes an axis only after a complete FD check.
		for(int owner = 0; owner < dependency.length; owner++) {
			dependency[owner] = false;
			if(!functional(domain, references, classes, dependency))
				dependency[owner] = true;
		}
		return dependency;
	}

	private static boolean functional(DomainView domain,
		IdentityHashMap<CompiledHopKey,ReferenceView> references, int[] classes,
		boolean[] dependency) throws Unsupported {
		if(classes.length != domain.headerByRow.length)
			throw new Unsupported("RAW_FACTOR_AXIS_DOMAIN_MISMATCH");
		int width = 1;
		for(boolean included : dependency)
			if(included)
				width++;
		int[] key = new int[width];
		Map<IntTuple,Integer> observed = new LinkedHashMap<>();
		for(int row = 0; row < classes.length; row++) {
			key[0] = domain.headerByRow[row];
			int next = 1;
			for(int owner = 0; owner < dependency.length; owner++)
				if(dependency[owner])
					key[next++] = domain.sourceOrdinal(row, owner);
			Integer previous = observed.putIfAbsent(new IntTuple(key), classes[row]);
			if(previous != null && previous != classes[row])
				return false;
		}
		return true;
	}

	private static AxisPlan axisPlan(int axis, DomainView domain, boolean[] dependencies,
		int[] classes, IdentityHashMap<CompiledHopKey,ReferenceView> references) {
		boolean[][] boundByHeader = new boolean[domain.headers.size()][dependencies.length];
		Map<IntTuple,List<Integer>> fibers = new LinkedHashMap<>();
		for(int row = 0; row < domain.headerByRow.length; row++) {
			int header = domain.headerByRow[row];
			int width = 1;
			for(boolean dependency : dependencies)
				if(dependency)
					width++;
			int[] key = new int[width];
			key[0] = header;
			int next = 1;
			for(int owner = 0; owner < dependencies.length; owner++)
				if(dependencies[owner]) {
					int selected = domain.sourceOrdinal(row, owner);
					if(selected < 0)
						key[next++] = -1;
					else {
						boundByHeader[header][owner] = true;
						key[next++] = selected;
					}
				}
			fibers.computeIfAbsent(new IntTuple(key), ignored -> new ArrayList<>()).add(row);
		}
		Map<IntTuple,List<Integer>> frozen = new LinkedHashMap<>();
		for(Map.Entry<IntTuple,List<Integer>> entry : fibers.entrySet()) {
			entry.getValue().sort(Comparator.naturalOrder());
			frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
		}
		return new AxisPlan(axis, domain, dependencies.clone(), classes.clone(), boundByHeader,
			Map.copyOf(frozen));
	}

	private static int findRow(AxisPlan plan, int[] local,
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions,
		IdentityHashMap<CompiledHopKey,ReferenceView> references) {
		DomainView domain = plan.view();
		int header = local[positions.get(domain.headerVariable)];
		int width = 1;
		for(boolean dependency : plan.dependencies())
			if(dependency)
				width++;
		int[] key = new int[width];
		key[0] = header;
		int next = 1;
		for(int owner = 0; owner < plan.dependencies().length; owner++)
			if(plan.dependencies()[owner]) {
				CompiledHopKey sourceOwner = domain.sourceOwners.get(owner);
				key[next++] = plan.boundByHeader()[header][owner]
					? local[positions.get(references.get(sourceOwner).variable)] : -1;
			}
		List<Integer> fiber = plan.provenanceFibers().get(new IntTuple(key));
		return fiber == null ? -1 : fiber.get(0);
	}

	private static List<ExactCategoricalSolver.Factor> projectedLinkFactors(AxisPlan plan,
		ExactCategoricalSolver.Variable quotientVariable, int[] sourceToClass,
		IdentityHashMap<CompiledHopKey,ReferenceView> references, long retainedOutput,
		long retainedTemporary, ExactCategoricalSolver.Limits limits, String failure)
		throws Unsupported {
		int quotientDomain = quotientVariable.domainSize();
		List<Integer> dependencyOwners = new ArrayList<>();
		for(int owner = 0; owner < plan.dependencies().length; owner++)
			if(plan.dependencies()[owner])
				dependencyOwners.add(owner);
		int[] dependencyDomains = projectedDependencyDomains(dependencyOwners, plan, references);
		if(!projectedOutputFits(plan.view().headerVariable.domainSize(), quotientDomain,
			dependencyDomains, retainedOutput, retainedTemporary, limits, failure))
			return null;
		BitSet[] headerAllowed = bitSets(plan.view().headers.size());
		List<BitSet[]> sourceAllowed = new ArrayList<>();
		for(int dependencyDomain : dependencyDomains)
			sourceAllowed.add(bitSets(dependencyDomain));
		for(Map.Entry<IntTuple,List<Integer>> entry : plan.provenanceFibers().entrySet()) {
			int expected = sourceToClass[entry.getValue().get(0)];
			visitProjectedTuples(entry.getKey().values.clone(), 0, dependencyDomains, tuple -> {
				headerAllowed[tuple[0]].set(expected);
				for(int dependency = 0; dependency < dependencyOwners.size(); dependency++)
					sourceAllowed.get(dependency)[tuple[dependency + 1]].set(expected);
				return true;
			});
		}
		for(Map.Entry<IntTuple,List<Integer>> entry : plan.provenanceFibers().entrySet()) {
			int expected = sourceToClass[entry.getValue().get(0)];
			boolean exact = visitProjectedTuples(entry.getKey().values.clone(), 0,
				dependencyDomains, tuple -> {
				BitSet intersection = (BitSet)headerAllowed[tuple[0]].clone();
				for(int dependency = 0; dependency < dependencyOwners.size(); dependency++)
					intersection.and(sourceAllowed.get(dependency)[tuple[dependency + 1]]);
				return intersection.cardinality() == 1 && intersection.get(expected);
			});
			if(!exact)
				return null;
		}
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		factors.add(projectedHardFactor(plan.view().headerVariable, quotientVariable,
			headerAllowed, quotientDomain));
		for(int dependency = 0; dependency < dependencyOwners.size(); dependency++) {
			ReferenceView reference = references.get(
				plan.view().sourceOwners.get(dependencyOwners.get(dependency)));
			factors.add(projectedHardFactor(reference.variable, quotientVariable,
				sourceAllowed.get(dependency), quotientDomain));
		}
		return List.copyOf(factors);
	}

	private static boolean projectedOutputFits(int headerDomain, int quotientDomain,
		int[] dependencyDomains, long retainedOutput, long retainedTemporary,
		ExactCategoricalSolver.Limits limits, String failure) {
		try {
			preflightProjectedOutput(headerDomain, quotientDomain, dependencyDomains,
				retainedOutput, retainedTemporary, limits, failure);
			return true;
		}
		catch(Unsupported | ArithmeticException projectedOnlyLimit) {
			return false;
		}
	}

	private static long preflightProjectedOutput(int headerDomain, int quotientDomain,
		int[] dependencyDomains, long retainedOutput, long retainedTemporary,
		ExactCategoricalSolver.Limits limits, String failure) throws Unsupported {
		long anticipated = retainedOutput;
		long headerCells = (long)headerDomain * quotientDomain;
		anticipated = checkedFactorBudget(anticipated, headerCells, limits, failure);
		checkedAllocationBudget(retainedOutput, retainedTemporary, 0,
			anticipated - retainedOutput, limits.maximumMaterializedCells(), failure);
		for(int dependencyDomain : dependencyDomains) {
			long sourceCells = (long)dependencyDomain * quotientDomain;
			anticipated = checkedFactorBudget(anticipated, sourceCells, limits, failure);
			checkedAllocationBudget(retainedOutput, retainedTemporary, 0,
				anticipated - retainedOutput, limits.maximumMaterializedCells(), failure);
		}
		return anticipated;
	}

	private static int[] projectedDependencyDomains(List<Integer> dependencyOwners,
		AxisPlan plan, IdentityHashMap<CompiledHopKey,ReferenceView> references) {
		int[] domains = new int[dependencyOwners.size()];
		for(int dependency = 0; dependency < domains.length; dependency++)
			domains[dependency] = references.get(
				plan.view().sourceOwners.get(dependencyOwners.get(dependency))).values.size();
		return domains;
	}

	private static boolean visitProjectedTuples(int[] tuple, int dependency,
		int[] dependencyDomains, ProjectedTupleVisitor visitor) {
		if(dependency == dependencyDomains.length)
			return visitor.visit(tuple);
		if(tuple[dependency + 1] >= 0)
			return visitProjectedTuples(tuple, dependency + 1, dependencyDomains, visitor);
		for(int value = 0; value < dependencyDomains[dependency]; value++) {
			tuple[dependency + 1] = value;
			if(!visitProjectedTuples(tuple, dependency + 1, dependencyDomains, visitor)) {
				tuple[dependency + 1] = -1;
				return false;
			}
		}
		tuple[dependency + 1] = -1;
		return true;
	}

	static List<int[]> projectedWildcardTuplesForTest(int[] tuple, int[] dependencyDomains,
		int maximumVisits) {
		Objects.requireNonNull(tuple, "tuple");
		Objects.requireNonNull(dependencyDomains, "dependencyDomains");
		if(tuple.length != dependencyDomains.length + 1 || maximumVisits < 0
			|| Arrays.stream(dependencyDomains).anyMatch(domain -> domain <= 0))
			throw new IllegalArgumentException("EXACT_SHARED_SOURCE_PROJECTED_TUPLE_TEST_INVALID");
		List<int[]> visited = new ArrayList<>(Math.min(maximumVisits, 16));
		visitProjectedTuples(tuple.clone(), 0, dependencyDomains.clone(), current -> {
			if(visited.size() >= maximumVisits)
				return false;
			visited.add(current.clone());
			return visited.size() < maximumVisits;
		});
		return List.copyOf(visited);
	}

	private static BitSet[] bitSets(int size) {
		BitSet[] sets = new BitSet[size];
		for(int index = 0; index < size; index++)
			sets[index] = new BitSet();
		return sets;
	}

	private static ExactCategoricalSolver.Factor projectedHardFactor(
		ExactCategoricalSolver.Variable source, ExactCategoricalSolver.Variable quotient,
		BitSet[] allowed, int quotientDomain) {
		double[] values = new double[Math.multiplyExact(source.domainSize(), quotientDomain)];
		Arrays.fill(values, Double.POSITIVE_INFINITY);
		for(int sourceValue = 0; sourceValue < source.domainSize(); sourceValue++)
			for(int quotientValue = allowed[sourceValue].nextSetBit(0); quotientValue >= 0;
				quotientValue = allowed[sourceValue].nextSetBit(quotientValue + 1))
				values[sourceValue * quotientDomain + quotientValue] = 0.0;
		return ExactCategoricalSolver.Factor.denseOwned(List.of(source, quotient), values);
	}

	private static int decodeRow(DomainView domain, boolean[] dependencies, int[] local,
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions,
		IdentityHashMap<CompiledHopKey,ReferenceView> references) {
		int row = findRow(domain, dependencies, local, positions, references);
		if(row < 0)
			throw new IllegalArgumentException("EXACT_SHARED_SOURCE_ILLEGAL_ENCODED_ASSIGNMENT");
		return row;
	}

	private static int findRow(DomainView domain, boolean[] dependencies, int[] local,
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions,
		IdentityHashMap<CompiledHopKey,ReferenceView> references) {
		int header = local[positions.get(domain.headerVariable)];
		for(int row = 0; row < domain.headerByRow.length; row++) {
			if(domain.headerByRow[row] != header)
				continue;
			boolean matches = true;
			for(int owner = 0; owner < dependencies.length; owner++)
				if(dependencies[owner]) {
					CompiledHopKey sourceOwner = domain.sourceOwners.get(owner);
					int selected = domain.sourceOrdinal(row, owner);
					if(selected >= 0 && selected
						!= local[positions.get(references.get(sourceOwner).variable)]) {
						matches = false;
						break;
					}
				}
			if(matches)
				return row;
		}
		return -1;
	}

	private static AxisPlan plan(List<AxisPlan> plans, int axis) {
		for(AxisPlan plan : plans)
			if(plan.axis() == axis)
				return plan;
		throw new IllegalStateException("EXACT_SHARED_SOURCE_AXIS_PLAN_MISSING");
	}

	private static double[] materialize(ExactCategoricalSolver.Factor factor, long limit,
		String failure) throws Unsupported {
		long cells = cells(factor.scope());
		if(cells > Integer.MAX_VALUE || cells > limit)
			throw new Unsupported(failure);
		double[] values = new double[(int)cells];
		ExactCategoricalSolver.materializeFactorValues(factor, values, false);
		return values;
	}

	private static FrozenFactor freezeProfileFactor(ExactCategoricalSolver.Factor factor,
		long maximumFactorCells, long availableTemporaryCells, String failure) throws Unsupported {
		long count = cells(factor.scope());
		if(count > Integer.MAX_VALUE)
			throw new Unsupported(failure);
		try {
			factor.denseCostAt(0);
			return new FrozenFactor(factor, null, (int)count);
		}
		catch(IllegalStateException notDense) {
			if(count > maximumFactorCells || count > availableTemporaryCells)
				throw new Unsupported(failure);
			return new FrozenFactor(factor, materialize(factor, count, failure), (int)count);
		}
	}

	private static int[] factorAxisClasses(FrozenFactor factor, int domain, int stride) {
		long block = (long)domain * stride;
		if(domain <= 0 || stride <= 0 || block > Integer.MAX_VALUE || factor.cells() % block != 0)
			throw new IllegalArgumentException("Dense factor shape does not match axis domain and stride");
		int[] hashes = new int[domain];
		Arrays.fill(hashes, 1);
		for(int outer = 0; outer < factor.cells(); outer += block)
			for(int value = 0; value < domain; value++)
				for(int offset = 0; offset < stride; offset++) {
					long bits = Double.doubleToRawLongBits(
						factor.value(outer + value * stride + offset));
					hashes[value] = 31 * hashes[value] + Long.hashCode(bits);
				}
		int[] classes = new int[domain];
		Map<Integer,List<Integer>> representativesByHash = new LinkedHashMap<>();
		int classCount = 0;
		for(int value = 0; value < domain; value++) {
			List<Integer> representatives = representativesByHash.computeIfAbsent(hashes[value],
				ignored -> new ArrayList<>());
			int matching = -1;
			for(int representative : representatives)
				if(sameFactorProfile(factor, domain, stride, value, representative)) {
					matching = classes[representative];
					break;
				}
			if(matching < 0) {
				matching = classCount++;
				representatives.add(value);
			}
			classes[value] = matching;
		}
		return classes;
	}

	private static boolean sameFactorProfile(FrozenFactor factor, int domain, int stride,
		int left, int right) {
		int block = Math.multiplyExact(domain, stride);
		for(int outer = 0; outer < factor.cells(); outer += block)
			for(int offset = 0; offset < stride; offset++)
				if(Double.doubleToRawLongBits(factor.value(outer + left * stride + offset))
					!= Double.doubleToRawLongBits(factor.value(outer + right * stride + offset)))
					return false;
		return true;
	}

	private static long checkedFactorBudget(long current, long next,
		ExactCategoricalSolver.Limits limits, String failure) throws Unsupported {
		if(next > Integer.MAX_VALUE || next > limits.maximumFactorCells())
			throw new Unsupported(failure);
		return checkedAllocationBudget(current, 0, 0, next,
			limits.maximumMaterializedCells(), failure);
	}

	private static long checkedAllocationBudget(long retainedOutput, long retainedTemporary,
		long currentTemporary, long next, long limit, String failure) throws Unsupported {
		long remaining = remainingAllocation(limit, retainedOutput, retainedTemporary,
			currentTemporary, failure);
		if(next < 0 || next > remaining)
			throw new Unsupported(failure);
		return Math.addExact(retainedOutput, next);
	}

	private static long remainingAllocation(long limit, long first, long second, long third,
		String failure) throws Unsupported {
		if(first < 0 || second < 0 || third < 0 || first > limit)
			throw new Unsupported(failure);
		long remaining = limit - first;
		if(second > remaining)
			throw new Unsupported(failure);
		remaining -= second;
		if(third > remaining)
			throw new Unsupported(failure);
		return remaining - third;
	}

	private static double[] hardValues(long cells) throws Unsupported {
		if(cells > Integer.MAX_VALUE)
			throw new Unsupported("HARD_FACTOR_ARRAY_LIMIT");
		double[] values = new double[(int)cells];
		Arrays.fill(values, Double.POSITIVE_INFINITY);
		return values;
	}

	private static void fillMembershipValues(double[] values, int headerDomain, int sourceDomain,
		int[] headerByRow, IntUnaryOperator selectedByRow) {
		boolean[] bound = new boolean[headerDomain];
		for(int row = 0; row < headerByRow.length; row++) {
			int selected = selectedByRow.applyAsInt(row);
			if(selected >= 0) {
				int header = headerByRow[row];
				values[header * sourceDomain + selected] = 0.0;
				bound[header] = true;
			}
		}
		for(int header = 0; header < headerDomain; header++)
			if(!bound[header])
				Arrays.fill(values, header * sourceDomain, (header + 1) * sourceDomain, 0.0);
	}

	private static <T> IdentityHashMap<T,Integer> positions(List<T> values) {
		IdentityHashMap<T,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < values.size(); index++)
			positions.put(values.get(index), index);
		return positions;
	}

	private static void addUnique(List<ExactCategoricalSolver.Variable> values,
		ExactCategoricalSolver.Variable value) {
		for(ExactCategoricalSolver.Variable present : values)
			if(present == value)
				return;
		values.add(value);
	}

	private static void decodeCell(int cell, List<ExactCategoricalSolver.Variable> scope,
		int[] values) {
		for(int axis = scope.size() - 1; axis >= 0; axis--) {
			values[axis] = cell % scope.get(axis).domainSize();
			cell /= scope.get(axis).domainSize();
		}
	}

	private static long saturatedMultiply(long left, long right) {
		return left == 0 || right == 0 ? 0
			: left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
	}

	/** Exact relation core used by focused regressions and the later activation patch. */
	static RelationEncoding encodeRelationForTest(String key, List<RelationRow> rows,
		List<Object> sourceOwners, List<RawIncidence> incidences,
		ExactCategoricalSolver.Limits limits) {
		if(key == null || key.isBlank())
			throw new IllegalArgumentException("EXACT_SHARED_SOURCE_KEY_INVALID");
		Objects.requireNonNull(rows, "rows");
		Objects.requireNonNull(sourceOwners, "sourceOwners");
		Objects.requireNonNull(incidences, "incidences");
		Objects.requireNonNull(limits, "limits");
		if(rows.isEmpty())
			return unsupported("EMPTY_RELATION", rows.size(), sourceOwners);

		IdentityHashMap<Object,Integer> ownerPosition = new IdentityHashMap<>();
		for(int index = 0; index < sourceOwners.size(); index++) {
			Object owner = Objects.requireNonNull(sourceOwners.get(index), "source owner");
			if(ownerPosition.put(owner, index) != null)
				return unsupported("DUPLICATE_SOURCE_OWNER", rows.size(), sourceOwners);
		}
		List<LinkedHashMap<IdentityKey,Integer>> valueOrdinals = new ArrayList<>();
		for(int ignored = 0; ignored < sourceOwners.size(); ignored++)
			valueOrdinals.add(new LinkedHashMap<>());
		LinkedHashMap<IdentityKey,Integer> headerOrdinals = new LinkedHashMap<>();
		List<CanonicalRow> canonical = new ArrayList<>(rows.size());
		IdentityHashMap<RelationRow,Boolean> seenRows = new IdentityHashMap<>();
		HashSet<Integer> seenOrdinals = new HashSet<>();
		for(RelationRow row : rows) {
			Objects.requireNonNull(row, "row");
			if(seenRows.put(row, Boolean.TRUE) != null)
				return unsupported("DUPLICATE_ROW_IDENTITY", rows.size(), sourceOwners);
			if(!seenOrdinals.add(row.originalOrdinal()))
				return unsupported("DUPLICATE_ORIGINAL_ORDINAL", rows.size(), sourceOwners);
			int header = headerOrdinals.computeIfAbsent(new IdentityKey(row.header()),
				ignored -> headerOrdinals.size());
			int[] selected = new int[sourceOwners.size()];
			Arrays.fill(selected, -1);
			for(SourceSelection selection : row.selections()) {
				Integer owner = ownerPosition.get(selection.owner());
				if(owner == null)
					return unsupported("UNKNOWN_SOURCE_OWNER", rows.size(), sourceOwners);
				int value = valueOrdinals.get(owner).computeIfAbsent(
					new IdentityKey(selection.reference()), ignored -> valueOrdinals.get(owner).size());
				if(selected[owner] >= 0 && selected[owner] != value)
					return unsupported("CONFLICTING_REPEATED_SOURCE", rows.size(), sourceOwners);
				selected[owner] = value;
			}
			canonical.add(new CanonicalRow(row.originalOrdinal(), header, selected));
		}
		for(Map<IdentityKey,Integer> values : valueOrdinals)
			if(values.isEmpty())
				return unsupported("SOURCE_OWNER_WITHOUT_REFERENCE", rows.size(), sourceOwners);

		int[] domains = new int[sourceOwners.size() + 1];
		domains[0] = headerOrdinals.size();
		for(int owner = 0; owner < sourceOwners.size(); owner++)
			domains[owner + 1] = valueOrdinals.get(owner).size();
		long tuples;
		try {
			tuples = cellCount(domains);
		}
		catch(ArithmeticException overflow) {
			return unsupported("ENCODED_RELATION_CELL_OVERFLOW", rows.size(), sourceOwners);
		}
		if(tuples > limits.maximumMaterializedCells())
			return unsupported("ENCODED_RELATION_MATERIALIZATION_LIMIT", rows.size(), sourceOwners);
		long anticipatedFactorCells = 0;
		for(int source = 1; source < domains.length; source++) {
			long membershipCells = (long)domains[0] * domains[source];
			if(membershipCells > limits.maximumFactorCells()
				|| membershipCells > limits.maximumMaterializedCells() - anticipatedFactorCells)
				return unsupported("ENCODED_FACTOR_LIMIT", rows.size(), sourceOwners);
			anticipatedFactorCells += membershipCells;
		}
		if(!incidences.isEmpty() && (tuples > Integer.MAX_VALUE
			|| tuples > limits.maximumFactorCells()))
			return unsupported("ENCODED_FACTOR_LIMIT", rows.size(), sourceOwners);
		for(int ignored = 0; ignored < incidences.size(); ignored++) {
			if(tuples > limits.maximumMaterializedCells() - anticipatedFactorCells)
				return unsupported("ENCODED_FACTOR_LIMIT", rows.size(), sourceOwners);
			anticipatedFactorCells += tuples;
		}
		if(anticipatedFactorCells > limits.maximumMaterializedCells())
			return unsupported("ENCODED_FACTOR_LIMIT", rows.size(), sourceOwners);

		Map<IntTuple,List<Integer>> fibers = new LinkedHashMap<>();
		int[] tuple = new int[domains.length];
		for(long cell = 0; cell < tuples; cell++) {
			decodeCell(cell, domains, tuple);
			List<Integer> fiber = new ArrayList<>();
			for(CanonicalRow row : canonical) {
				if(row.header() != tuple[0])
					continue;
				boolean compatible = true;
				for(int owner = 0; owner < sourceOwners.size(); owner++)
					compatible &= row.selectedValueByOwner()[owner] < 0
						|| row.selectedValueByOwner()[owner] == tuple[owner + 1];
				if(compatible)
					fiber.add(row.ordinal());
			}
			if(!fiber.isEmpty()) {
				fiber.sort(Comparator.naturalOrder());
				fibers.put(new IntTuple(tuple), List.copyOf(fiber));
			}
		}
		if(!isConditionallyRectangular(domains, fibers))
			return unsupported("NON_RECTANGULAR_HEADER", rows.size(), sourceOwners);

		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>();
		variables.add(new ExactCategoricalSolver.Variable(
			"exact-shared-source|" + key + "|header", domains[0]));
		for(int owner = 0; owner < sourceOwners.size(); owner++)
			variables.add(new ExactCategoricalSolver.Variable(
				"exact-shared-source|" + key + "|source=" + owner, domains[owner + 1]));
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		long factorCells = 0;
		for(int owner = 0; owner < sourceOwners.size(); owner++) {
			int source = owner + 1;
			double[] membership = new double[Math.multiplyExact(domains[0], domains[source])];
			Arrays.fill(membership, Double.POSITIVE_INFINITY);
			for(IntTuple legal : fibers.keySet())
				membership[legal.values[0] * domains[source] + legal.values[source]] = 0.0;
			factors.add(ExactCategoricalSolver.Factor.denseOwned(
				List.of(variables.get(0), variables.get(source)), membership));
			factorCells = Math.addExact(factorCells, membership.length);
		}
		for(RawIncidence incidence : incidences) {
			double[] original = incidence.valuesByOriginalOrdinal;
			double[] retargeted = new double[Math.toIntExact(tuples)];
			Arrays.fill(retargeted, Double.POSITIVE_INFINITY);
			for(Map.Entry<IntTuple,List<Integer>> entry : fibers.entrySet()) {
				List<Integer> fiber = entry.getValue();
				int representative = fiber.get(0);
				if(representative >= original.length)
					return unsupported("RAW_FACTOR_ORDINAL_MISSING|" + incidence.key(),
						rows.size(), sourceOwners);
				long bits = Double.doubleToRawLongBits(original[representative]);
				for(int ordinal : fiber)
					if(ordinal >= original.length
						|| Double.doubleToRawLongBits(original[ordinal]) != bits)
						return unsupported("RAW_FACTOR_FIBER_MISMATCH|" + incidence.key(),
							rows.size(), sourceOwners);
				retargeted[Math.toIntExact(encodeCell(entry.getKey().values, domains))] =
					original[representative];
			}
			factors.add(ExactCategoricalSolver.Factor.denseOwned(variables, retargeted));
			factorCells = Math.addExact(factorCells, retargeted.length);
		}
		if(factors.stream().anyMatch(factor -> cells(factor.scope()) > limits.maximumFactorCells())
			|| factorCells > limits.maximumMaterializedCells())
			return unsupported("ENCODED_FACTOR_LIMIT", rows.size(), sourceOwners);
		Statistics statistics = new Statistics(true, "EXACT_RELATION", rows.size(), domains[0],
			sourceOwners.size(), fibers.size(), factorCells, 0, 0, 0, 0, STRUCTURAL_TIE);
		return new RelationEncoding(true, "EXACT_RELATION", sourceOwners, variables,
			factors, fibers, statistics);
	}

	private static RelationEncoding unsupported(String reason, int rows, List<Object> owners) {
		return new RelationEncoding(false, reason, owners, List.of(), List.of(), Map.of(),
			new Statistics(false, reason, rows, 0, owners.size(), 0, 0, 0, 0, 0, 0,
				STRUCTURAL_TIE));
	}

	private static boolean isConditionallyRectangular(int[] domains,
		Map<IntTuple,List<Integer>> fibers) {
		for(int header = 0; header < domains[0]; header++) {
			List<boolean[]> projections = new ArrayList<>();
			for(int axis = 1; axis < domains.length; axis++)
				projections.add(new boolean[domains[axis]]);
			for(IntTuple tuple : fibers.keySet())
				if(tuple.values[0] == header)
					for(int axis = 1; axis < domains.length; axis++)
						projections.get(axis - 1)[tuple.values[axis]] = true;
			for(IntTuple tuple : allTuplesForHeader(header, domains)) {
				boolean projected = true;
				for(int axis = 1; axis < domains.length; axis++)
					projected &= projections.get(axis - 1)[tuple.values[axis]];
				if(projected != fibers.containsKey(tuple))
					return false;
			}
		}
		return true;
	}

	private static List<IntTuple> allTuplesForHeader(int header, int[] domains) {
		long perHeader = 1;
		for(int axis = 1; axis < domains.length; axis++)
			perHeader = Math.multiplyExact(perHeader, domains[axis]);
		List<IntTuple> tuples = new ArrayList<>(Math.toIntExact(perHeader));
		int[] values = new int[domains.length];
		for(long cell = (long)header * perHeader; cell < (long)(header + 1) * perHeader; cell++) {
			decodeCell(cell, domains, values);
			tuples.add(new IntTuple(values));
		}
		return tuples;
	}

	private static void validateScopes(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors) {
		IdentityHashMap<ExactCategoricalSolver.Variable,Boolean> known = new IdentityHashMap<>();
		for(ExactCategoricalSolver.Variable variable : variables)
			known.put(variable, Boolean.TRUE);
		for(ExactCategoricalSolver.Factor factor : factors)
			for(ExactCategoricalSolver.Variable variable : factor.scope())
				if(!known.containsKey(variable))
					throw new IllegalArgumentException("EXACT_SHARED_SOURCE_FOREIGN_FACTOR_VARIABLE");
	}

	private static long countCells(List<ExactCategoricalSolver.Factor> factors) {
		long total = 0;
		for(ExactCategoricalSolver.Factor factor : factors) {
			long cells = cells(factor.scope());
			total = total > Long.MAX_VALUE - cells ? Long.MAX_VALUE : total + cells;
		}
		return total;
	}

	private static long cells(List<ExactCategoricalSolver.Variable> scope) {
		long cells = 1;
		for(ExactCategoricalSolver.Variable variable : scope)
			cells = cells > Long.MAX_VALUE / variable.domainSize()
				? Long.MAX_VALUE : cells * variable.domainSize();
		return cells;
	}

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	private static long cellCount(int[] domains) {
		long cells = 1;
		for(int domain : domains)
			cells = Math.multiplyExact(cells, domain);
		return cells;
	}

	private static void decodeCell(long cell, int[] domains, int[] values) {
		for(int axis = domains.length - 1; axis >= 0; axis--) {
			values[axis] = (int)(cell % domains[axis]);
			cell /= domains[axis];
		}
	}

	private static long encodeCell(int[] values, int[] domains) {
		long cell = 0;
		for(int axis = 0; axis < domains.length; axis++)
			cell = Math.addExact(Math.multiplyExact(cell, domains[axis]), values[axis]);
		return cell;
	}

	private static CandidateRealizationReference candidateReference(ExactPhysicalModel model,
		ExactPhysicalModel.Alternative alternative) {
		var rule = alternative.captured() ? alternative.candidateRule() : alternative.executionRule();
		var emission = alternative.captured() ? alternative.candidateEmission() : alternative.executionEmission();
		if(rule == null || emission == null || alternative.realization() == null)
			return null;
		CandidateSelectionReceipt receipt = model.analysis().canonicalCandidateReceipt(rule.key(),
			emission, alternative.realization(), alternative.supportClause());
		return CandidateRealizationReference.of(receipt.rule(), receipt.realization());
	}
}
