/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Locks the exact hard-factor truth tables for a realization-dependent fixture. */
public class ExactPhysicalRealizationSupportFactorCacheTest {
	private static final String EXPECTED_TRUTH_SHA256 =
		"982ec0a922e1d52f88b9520d2663ff3f4c2f8f6dda9ac25837242099004feb01";

	@Test
	public void realizationSupportCachingPreservesEveryHardFactorCell() throws Exception {
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis());
		long directFoutAuthorities = model.domains().stream()
			.flatMap(domain -> domain.alternatives().stream())
			.flatMap(alternative -> alternative.inputAuthorities().stream())
			.filter(authority -> authority.kind() == ExactPhysicalModel.InputAuthorityKind.DIRECT_FOUT)
			.count();
		Assert.assertTrue("fixture must exercise graph-owned relocation actions",
			!model.analysis().graph().relocationActions().isEmpty());
		Assert.assertTrue("fixture must exercise direct-FOUT input authority factors",
			directFoutAuthorities > 0);
		long requiredSupports = model.domains().stream()
			.flatMap(domain -> domain.alternatives().stream())
			.filter(alternative -> alternative.supportClause() != null)
			.flatMap(alternative -> alternative.supportClause().requiredInputSupport().stream())
			.count();
		Assert.assertTrue("fixture must exercise realization-support factors", requiredSupports > 0);
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause>
			uniqueClauses = Collections.newSetFromMap(new IdentityHashMap<>());
		long alternatives = 0;
		for(var domain : model.domains())
			for(var alternative : domain.alternatives()) {
				alternatives++;
				if(alternative.supportClause() != null)
					uniqueClauses.add(alternative.supportClause());
			}
		var preparation = model.realizationSupportPreparationStatistics();
		var inputPreparation = model.inputAuthorityPreparationStatistics();
		Assert.assertTrue(preparation.requiredSupportDerivations() > 0);
		Assert.assertTrue(preparation.canonicalReferenceDerivations() > 0);
		Assert.assertTrue(preparation.requiredSupportDerivations() <= uniqueClauses.size());
		Assert.assertTrue(preparation.canonicalReferenceDerivations() <= alternatives);
		Assert.assertTrue(inputPreparation.factorCount() > 0);
		Assert.assertTrue(inputPreparation.consumerAlternativeRows() > 0);
		Assert.assertTrue(inputPreparation.indexedDirectFoutRows() > 0);
		Assert.assertTrue(inputPreparation.canonicalReceiptDerivations() > 0);
		Assert.assertTrue(inputPreparation.canonicalReceiptDerivations() <= alternatives);

		Map<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < model.variables().size(); index++)
			positions.put(model.variables().get(index), index);
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		long cells = 0;
		for(int factorIndex = 0; factorIndex < model.hardFactors().size(); factorIndex++) {
			ExactCategoricalSolver.Factor factor = model.hardFactors().get(factorIndex);
			update(digest, "factor=" + factorIndex + "|scope=");
			for(var variable : factor.scope())
				update(digest, positions.get(variable) + ":" + variable.domainSize() + ",");
			int factorCells = factor.scope().stream().mapToInt(
				ExactCategoricalSolver.Variable::domainSize).reduce(1, Math::multiplyExact);
			int[] values = new int[factor.scope().size()];
			for(int cell = 0; cell < factorCells; cell++) {
				int remainder = cell;
				for(int position = values.length - 1; position >= 0; position--) {
					int radix = factor.scope().get(position).domainSize();
					values[position] = remainder % radix;
					remainder /= radix;
				}
				update(digest, Long.toUnsignedString(Double.doubleToRawLongBits(factor.cost(values))) + ",");
			}
			cells += factorCells;
			update(digest, "\n");
		}
		Assert.assertTrue("fixture must enumerate more cells than alternatives", cells >
			model.domains().stream().mapToLong(domain -> domain.alternatives().size()).sum());
		Assert.assertTrue("support derivation must be bounded by identities, not factor cells",
			preparation.requiredSupportDerivations() < cells
				&& preparation.canonicalReferenceDerivations() < cells);
		Assert.assertTrue("input-authority preparation must be bounded by rows, not factor cells",
			inputPreparation.consumerAlternativeRows() < cells
				&& inputPreparation.canonicalReceiptDerivations() < cells);
		Assert.assertEquals(EXPECTED_TRUTH_SHA256, HexFormat.of().formatHex(digest.digest()));
	}

	@Test
	public void flatPrivateAggregateRetainsSameDurableOutputAcrossSupplyRoutes() throws Exception {
		ExactPhysicalModel model = ExactPhysicalModel.build(flatPrivateAnalysis());
		assertDifferentRoutePremise(model);
		Assert.assertTrue("fixture must use a finite-row production realization-support factor",
			model.hardFactorEncodings().stream().anyMatch(encoding ->
				encoding.decomposition().descriptor().startsWith("realization-support|")
					&& encoding.canonicalFactor().isFiniteSupport()));
		assertRealizationSupportFactorCells(model);
	}

	private static void update(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
	}

	private static void assertDifferentRoutePremise(ExactPhysicalModel model) {
		Set<DurableOutput> requiredDurableOutputs = new HashSet<>();
		model.domains().stream().flatMap(domain -> domain.alternatives().stream())
			.filter(alternative -> alternative.supportClause() != null)
			.flatMap(alternative -> alternative.supportClause().requiredInputSupport().stream())
			.filter(reference -> reference.realization().layoutKind() == PlacementLayoutKind.DURABLE_MAP)
			.forEach(reference -> requiredDurableOutputs.add(new DurableOutput(
				reference.rule().parentOccurrence(), reference.realization())));
		Map<DurableOutput,Set<CandidateRuleKey>> routesByDurableOutput = new HashMap<>();
		for(var domain : model.domains())
			for(var alternative : domain.alternatives()) {
				if(alternative.realization() == null
					|| alternative.realization().key().layoutKind() != PlacementLayoutKind.DURABLE_MAP)
					continue;
				var fact = alternative.captured()
					? alternative.candidateRule() : alternative.executionRule();
				if(fact != null)
					routesByDurableOutput.computeIfAbsent(new DurableOutput(
						alternative.decision(), alternative.realization().key()), ignored -> new HashSet<>())
						.add(fact.key());
			}
		Assert.assertTrue("fixture must exercise one required durable output through different input routes",
			routesByDurableOutput.entrySet().stream().anyMatch(entry ->
				requiredDurableOutputs.contains(entry.getKey()) && entry.getValue().size() > 1));
	}

	/** Independent oracle for the exact required-output relation encoded by hard factors. */
	private static void assertRealizationSupportFactorCells(ExactPhysicalModel model) {
		Map<ExactCategoricalSolver.Variable,ExactPhysicalModel.DecisionDomain> domains =
			new IdentityHashMap<>();
		for(var domain : model.domains())
			domains.put(domain.variable(), domain);
		long checked = 0;
		long admittedDifferentRoute = 0;
		for(var encoding : model.hardFactorEncodings()) {
			if(!encoding.decomposition().descriptor().startsWith("realization-support|"))
				continue;
			var factor = encoding.canonicalFactor();
			Assert.assertTrue("realization-support factor must couple one consumer and source",
				factor.scope().size() == 1 || factor.scope().size() == 2);
			var consumer = domains.get(factor.scope().get(0));
			var source = domains.get(factor.scope().get(factor.scope().size() - 1));
			Assert.assertNotNull(consumer);
			Assert.assertNotNull(source);
			for(int consumerValue = 0; consumerValue < consumer.alternatives().size(); consumerValue++)
				for(int sourceValue = 0; sourceValue < source.alternatives().size(); sourceValue++) {
					int[] values = factor.scope().size() == 1
						? new int[] {consumerValue} : new int[] {consumerValue, sourceValue};
					if(factor.scope().size() == 1 && consumerValue != sourceValue)
						continue;
					var consumerAlternative = consumer.alternatives().get(consumerValue);
					var sourceAlternative = source.alternatives().get(sourceValue);
					List<CandidateRealizationReference> required = consumerAlternative.supportClause() == null
						? List.of() : consumerAlternative.supportClause().requiredInputSupport().stream()
							.filter(reference -> reference.rule().parentOccurrence().equals(source.node().key()))
							.toList();
					Set<SupportIdentity> requiredIdentities = new HashSet<>();
					for(var reference : required)
						requiredIdentities.add(independentSupportIdentity(reference));
					CandidateRealizationReference selected = selectedReference(sourceAlternative);
					boolean expected = requiredIdentities.isEmpty()
						|| requiredIdentities.size() == 1 && selected != null
							&& requiredIdentities.contains(independentSupportIdentity(selected));
					double actual = factor.cost(values);
					Assert.assertEquals("independent realization-support cell disagrees|descriptor="
						+ encoding.decomposition().descriptor() + "|consumer=" + consumerValue
						+ "|source=" + sourceValue, expected ? 0d : Double.POSITIVE_INFINITY,
						actual, 0d);
					checked++;
					if(expected && selected != null && required.stream().anyMatch(reference ->
						!reference.equals(selected)
							&& independentSupportIdentity(reference).equals(
								independentSupportIdentity(selected))))
						admittedDifferentRoute++;
				}
		}
		Assert.assertTrue("fixture must expose encoded realization-support cells", checked > 0);
		Assert.assertTrue("fixture must admit the same durable output through a different input route",
			admittedDifferentRoute > 0);
	}

	private static CandidateRealizationReference selectedReference(
		ExactPhysicalModel.Alternative alternative) {
		if(alternative.realization() == null)
			return null;
		var fact = alternative.captured()
			? alternative.candidateRule() : alternative.executionRule();
		return fact == null ? null : CandidateRealizationReference.of(fact.key(), alternative.realization());
	}

	private static SupportIdentity independentSupportIdentity(CandidateRealizationReference reference) {
		boolean durable = reference.realization().layoutKind() == PlacementLayoutKind.DURABLE_MAP;
		return new SupportIdentity(reference.rule().parentOccurrence(),
			durable ? null : reference.rule(), reference.realization());
	}

	private record DurableOutput(CompiledHopKey owner, PlacementRealizationKey realization) { }
	private record SupportIdentity(CompiledHopKey owner, CandidateRuleKey exactRule,
		PlacementRealizationKey realization) { }

	private static org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis flatPrivateAnalysis()
		throws Exception {
		String script = String.join("\n",
			"UA=federated(addresses=list(\"localhost:13001/UA\"),ranges=list(list(0,0),list(16,4096)));",
			"UB=federated(addresses=list(\"localhost:13002/UB\"),ranges=list(list(0,0),list(16,4096)));",
			"S0=federated(addresses=list(\"localhost:13001/S0\"),ranges=list(list(0,0),list(16,4096)));",
			"total=0;energy=0;",
			"for(i in 1:3) {",
			"  QA0=UA+S0;QB0=UB+S0;QC0=UB*S0;",
			"  QM0=QB0/QC0;",
			"  total=total+sum(QA0)+sum(QM0);",
			"  energy=energy+sum(QA0*QA0)+sum(QM0*QM0);",
			"  S0=S0+i;",
			"}",
			"print(total+energy);") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		program.getStatementBlocks().stream().filter(block -> block.getHops() != null)
			.flatMap(block -> block.getHops().stream())
			.forEach(root -> markPublicFederatedSource(root, "S0", visited));
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static void markPublicFederatedSource(Hop hop, String name, Set<Hop> visited) {
		if(hop == null || !visited.add(hop))
			return;
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
			&& name.equals(data.getName()))
			FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data, Privacy.PUBLIC);
		for(Hop input : hop.getInput())
			markPublicFederatedSource(input, name, visited);
	}

	private static org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis analysis()
		throws Exception {
		var program = ProductionShadowFixtureFactory.compile("B-11");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}
