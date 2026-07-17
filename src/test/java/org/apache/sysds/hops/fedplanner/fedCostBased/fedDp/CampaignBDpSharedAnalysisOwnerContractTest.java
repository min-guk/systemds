/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.AppliedPlanReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.InvocationCounters;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.PlannerRecompileState;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.hops.ipa.IPAPassRewriteFederatedPlan;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry.MaterializeSpec;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry.LocalMaterializeSpec;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry.AnchorSpec;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Test-only RED for the single exact shared-analysis owner at the typed DP root. */
public class CampaignBDpSharedAnalysisOwnerContractTest {
	private static final Path DP_ROOT = Path.of(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java");
	private static final Path DML_PROGRAM = Path.of("src/main/java/org/apache/sysds/parser/DMLProgram.java");
	private static final Path DML_TRANSLATOR = Path.of("src/main/java/org/apache/sysds/parser/DMLTranslator.java");
	private static final Path GRAPH_BUILDER = Path.of(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java");
	private static final Path PLACEMENT_ANALYSIS = Path.of(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java");
	private static final Path IPA_PASS = Path.of(
		"src/main/java/org/apache/sysds/hops/ipa/IPAPassRewriteFederatedPlan.java");
	private static final Path SHADOW_COORDINATOR = Path.of(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementShadowCoordinator.java");
	private static final Path DML_SCRIPT = Path.of("src/main/java/org/apache/sysds/api/DMLScript.java");
	private static final Path JMLC_CONNECTION = Path.of("src/main/java/org/apache/sysds/api/jmlc/Connection.java");
	private static final Path ML_CONTEXT_EXECUTOR = Path.of(
		"src/main/java/org/apache/sysds/api/mlcontext/ScriptExecutor.java");
	private static final Path RESOURCE_COMPILER = Path.of(
		"src/main/java/org/apache/sysds/resource/ResourceCompiler.java");
	private static final Path ARCHITECTURE_GUARD = Path.of(
		"src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBArchitectureGuardTest.java");
	private static final Path OWNERSHIP_CLOSURE = Path.of(
		"src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBPlannerOwnershipClosure.java");
	private static final Path TOKEN_SCANNER = Path.of(
		"src/test/java/org/apache/sysds/test/component/federated/placement/guard/JavaSourceTokenScanner.java");
	private static final String ARCHITECTURE_GUARD_SHA =
		"8263e06a82f9f17823a1d281a5ab5f2932887ef17b950a7f5bcccaec8ea6fa90";
	private static final String OWNERSHIP_CLOSURE_SHA =
		"a6286fe39edad061225405023d707c676429bb6707fa84c852628db1185c57ab";
	private static final String TOKEN_SCANNER_SHA =
		"a80bb1b061b07743fa283631097a2966a9cf946f54bf53468ead9bfab5ac33c3";
	// Sole-verifier artifact authority; the test freezes its source machinery separately below.
	private static final String DIRECT_DP_CLOSURE_EVIDENCE_SHA =
		"8dcaf54c5993865315cc0c2e565ab766adffc159fb0c74eb911ee8bc07c0ac27";
	private static final String FROZEN_DIRECT_DP_CLOSURE =
		"PLANNER=DP UNITS=34 VIOLATIONS=140 ADAPTER=DpPlacementAdapter POSITIVE_BOUNDARY=PASS";
	private static final int FROZEN_DP_UNITS = 34;
	private static final int FROZEN_DP_VIOLATIONS = 140;
	private static final int PREDICTED_H1_DP_VIOLATIONS = 139;
	private static final String SENTINEL_VAR = "__campaign_b_dp_owner_sentinel";
	private static final String SENTINEL_SIGNATURE = "fed://campaign-b/dp-owner";
	private static final String SENTINEL_ANCHOR = SENTINEL_SIGNATURE + "|ROW";
	private static final long SENTINEL_SCOPE = -911L;
	private static final long SENTINEL_HOP = -912L;
	private static final long SENTINEL_ANCHOR_HOP = -913L;

	@Test
	public void exactBuilderOwnerFlowsThroughMemoAdapterAndInvocationReceipt() {
		Fixture fixture = fixture("B-05");
		AnalysisSnapshot analysisBefore = snapshotAnalysis(fixture.analysis());

		DpInvocationReceipt receipt = new FederatedPlannerDpFedCostBased().rewriteProgram(fixture.program(),
			new FunctionCallGraph(fixture.program()), null, fixture.analysis());

		Assert.assertSame(fixture.analysis(), receipt.analysis());
		Assert.assertSame(fixture.analysis(), receipt.memo().analysis());
		Assert.assertSame(fixture.analysis(), receipt.exactSelection().analysis());
		Assert.assertSame(receipt.memo(), receipt.exactSelection().memo());
		Assert.assertSame(receipt.legacyOptimalPlan(), receipt.exactSelection().legacyOptimalPlan());
		Assert.assertEquals(receipt.analysisFingerprintBefore(), receipt.analysisFingerprintAfter());
		Assert.assertEquals(fixture.analysis().analysisFingerprint(), receipt.analysisFingerprintAfter());
		assertAnalysisSame(analysisBefore, snapshotAnalysis(fixture.analysis()));

		assertIdentityList(receipt.legacyOptimalPlan().getChildFedPlans(),
			receipt.exactSelection().aggregateChildEdges(), "aggregate edges");
		Assert.assertEquals(receipt.exactSelection().selectedRootPlans().size(),
			receipt.exactSelection().selectedRootHops().size());
		for(int i = 0; i < receipt.exactSelection().selectedRootPlans().size(); i++) {
			FedPlan selected = receipt.exactSelection().selectedRootPlans().get(i);
			AppliedPlanReceipt applied = receipt.appliedPlans().get(i);
			Assert.assertSame(selected, applied.plan());
			Assert.assertSame(selected.getHopRef(), applied.planningHop());
			Assert.assertSame(receipt.exactSelection().selectedRootHops().get(i), applied.planningHop());
			Assert.assertEquals(Double.doubleToRawLongBits(selected.getCumulativeCost()),
				Double.doubleToRawLongBits(applied.plan().getCumulativeCost()));
		}

		InvocationCounters counters = receipt.counters();
		Assert.assertEquals(1, counters.enumerationCount());
		Assert.assertEquals(1, counters.exactSelectionCount());
		Assert.assertEquals(1, counters.applicationPhaseCount());
		Assert.assertEquals(receipt.appliedPlans().size(), counters.appliedPlanCount());
		Assert.assertEquals(receipt.additionalRootInvocations().size(), counters.additionalRootInvocationCount());
		Assert.assertEquals(0, counters.internalAnalysisBuildCount());
		Assert.assertEquals(0, counters.oldOverloadCount());
		Assert.assertEquals(0, counters.reenumerationCount());
		Assert.assertEquals(0, counters.repairCount());
		Assert.assertEquals(0, counters.fallbackCount());
		Assert.assertEquals(0, counters.doubleApplicationCount());
		assertImmutable(receipt.appliedPlans(), "applied plans");
		assertImmutable(receipt.additionalRootInvocations(), "additional-root invocations");
		assertImmutable(receipt.exactSelection().selectedRootPlans(), "selected root plans");
		assertImmutable(receipt.exactSelection().selectedRootHops(), "selected root hops");
	}

	@Test
	public void secondAnalysisBuildForSameProgramRejectsBeforeEveryObservableMutation() {
		Fixture owner = fixture("B-05");
		PlacementAnalysis copied = new NeutralPlacementGraphBuilder().buildAnalysis(owner.program());
		Assert.assertNotSame(owner.analysis(), copied);
		owner.analysis().assertProgramOwner(owner.program());
		copied.assertProgramOwner(owner.program());
		assertSameHopOrigins(owner.analysis(), copied);
		ProgramSnapshot ownerBefore = snapshotProgram(owner.program(), owner.analysis());
		AnalysisSnapshot copiedBefore = snapshotAnalysis(copied);
		Hop recompileSentinel = owner.analysis().occurrences().stream().map(HopOccurrenceProjection::hop)
			.filter(hop -> FederatedPlannerUtils.plannerRecompileSignature(hop) != null).findFirst()
			.orElseThrow(() -> new AssertionError("fixture has no source-owned recompile sentinel Hop"));

		seedRunState(recompileSentinel);
		RunStateSnapshot runStateBefore = snapshotRunState(recompileSentinel);
		try {
			DpInvocationReceipt accepted = new FederatedPlannerDpFedCostBased().rewriteProgram(owner.program(),
				new FunctionCallGraph(owner.program()), null, copied);
			RunStateSnapshot afterAcceptance = snapshotRunState(recompileSentinel);
			Assert.fail("same-program second analysis was accepted as a DP owner; counters=" + accepted.counters()
				+ " runStatePreserved=" + runStateBefore.sameIdentities(afterAcceptance));
		}
		catch(IllegalArgumentException expected) {
			assertProgramSame(ownerBefore, snapshotProgram(owner.program(), owner.analysis()));
			assertAnalysisSame(copiedBefore, snapshotAnalysis(copied));
			assertRunStateSame(runStateBefore, snapshotRunState(recompileSentinel));
		}
		finally {
			clearRunState();
		}
	}

	@Test
	public void typedRootUsesExactOwnerBoundaryWithoutDuplicateProgramWalk() throws Exception {
		Assert.assertEquals("architecture guard authority changed", ARCHITECTURE_GUARD_SHA,
			sha256(ARCHITECTURE_GUARD));
		Assert.assertEquals("complete ownership-closure authority changed", OWNERSHIP_CLOSURE_SHA,
			sha256(OWNERSHIP_CLOSURE));
		Assert.assertEquals("Java source token scanner authority changed", TOKEN_SCANNER_SHA,
			sha256(TOKEN_SCANNER));
		Assert.assertEquals("H1 prediction must remove exactly one violation", FROZEN_DP_VIOLATIONS - 1,
			PREDICTED_H1_DP_VIOLATIONS);
		Assert.assertEquals("H1 must preserve the complete DP closure unit count", 34, FROZEN_DP_UNITS);
		Assert.assertEquals("complete frozen direct DP closure authority",
			"PLANNER=DP UNITS=" + FROZEN_DP_UNITS + " VIOLATIONS=" + FROZEN_DP_VIOLATIONS
				+ " ADAPTER=DpPlacementAdapter POSITIVE_BOUNDARY=PASS", FROZEN_DIRECT_DP_CLOSURE);
		Assert.assertEquals("frozen direct-closure evidence SHA", 64, DIRECT_DP_CLOSURE_EVIDENCE_SHA.length());
		String source = Files.readString(DP_ROOT);
		String typedSignature = "public DpInvocationReceipt rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph,";
		int typedStart = source.indexOf(typedSignature);
		int dynamicStart = source.indexOf("public void rewriteFunctionDynamic", typedStart);
		Assert.assertTrue("typed DP root source boundary missing", typedStart >= 0 && dynamicStart > typedStart);
		String typedRoot = source.substring(typedStart, dynamicStart);
		int ownerCheck = typedRoot.indexOf("analysis.assertProgramOwner(prog);");
		int reset = typedRoot.indexOf("FederatedPlannerUtils.resetFederatedPlannerRunState();");

		Assert.assertTrue("exact PlacementAnalysis owner must reject before planner reset", ownerCheck >= 0
			&& reset > ownerCheck);
		Assert.assertFalse("H1 duplicate owner walk remains; sole verifier must classify the predicted "
			+ "direct DP closure delta 34/140 -> 34/139", typedRoot.contains("validateSuppliedAnalysis"));
		Assert.assertFalse("H1 must not rebuild membership from program statement blocks",
			typedRoot.contains("getStatementBlocks"));
		Assert.assertFalse("H1 helper relocation is not an owner repair",
			source.contains("private static void validateSuppliedAnalysis"));
		Assert.assertFalse("H1 recursive program-root reconstruction is not an owner repair",
			source.contains("private static void collectProgramRoots"));
	}

	@Test
	public void concreteProgramAuthorityIsPrivateFinalAndNonPoisonable() throws Exception {
		Field cell = requiredAuthorityField();
		Assert.assertSame("CAMPAIGN_B_DP_AUTHORITY_CELL_RAW_TYPE", AtomicReference.class, cell.getType());
		Assert.assertEquals("CAMPAIGN_B_DP_AUTHORITY_CELL_GENERIC_TYPE",
			"java.util.concurrent.atomic.AtomicReference<org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis>",
			cell.getGenericType().getTypeName());
		Assert.assertTrue("CAMPAIGN_B_DP_AUTHORITY_CELL_NOT_PRIVATE", Modifier.isPrivate(cell.getModifiers()));
		Assert.assertTrue("CAMPAIGN_B_DP_AUTHORITY_CELL_NOT_FINAL", Modifier.isFinal(cell.getModifiers()));
		Assert.assertTrue("CAMPAIGN_B_DP_ANALYSIS_MUST_BE_FINAL",
			Modifier.isFinal(PlacementAnalysis.class.getModifiers()));

		Method bind;
		try {
			bind = DMLProgram.class.getDeclaredMethod("bindPlacementAnalysisAtFinalHopBoundary");
		}
		catch(NoSuchMethodException missing) {
			throw new AssertionError("CAMPAIGN_B_DP_FINAL_BOUNDARY_BIND_MISSING", missing);
		}
		Assert.assertSame(PlacementAnalysis.class, bind.getReturnType());
		Assert.assertFalse("CAMPAIGN_B_DP_BIND_MUST_NOT_BE_PUBLIC", Modifier.isPublic(bind.getModifiers()));
		Assert.assertFalse("CAMPAIGN_B_DP_BIND_MUST_NOT_BE_PROTECTED", Modifier.isProtected(bind.getModifiers()));
		Assert.assertEquals("CAMPAIGN_B_DP_BIND_MUST_ACCEPT_NO_CANDIDATE", 0, bind.getParameterCount());
		List<Method> bindSurfaces = Arrays.stream(DMLProgram.class.getDeclaredMethods())
			.filter(method -> method.getName().toLowerCase().matches(".*(bind|install|set).*placement.*analysis.*"))
			.toList();
		Assert.assertEquals("CAMPAIGN_B_DP_CANDIDATE_BIND_SURFACE_COUNT", List.of(bind), bindSurfaces);
		for(Method method : DMLProgram.class.getDeclaredMethods())
			Assert.assertFalse("CAMPAIGN_B_DP_CANDIDATE_AUTHORITY_PARAMETER|" + method,
				Arrays.asList(method.getParameterTypes()).contains(PlacementAnalysis.class)
					&& !method.getName().equals("requirePlacementAnalysisAuthority"));
		List<Field> placementFields = Arrays.stream(DMLProgram.class.getDeclaredFields())
			.filter(field -> field.getGenericType().getTypeName().contains("PlacementAnalysis")
				|| field.getName().toLowerCase().contains("placementanalysis"))
			.toList();
		Assert.assertEquals("CAMPAIGN_B_DP_ALTERNATE_AUTHORITY_FIELD", List.of(cell), placementFields);
		List<Method> authorityMethods = Arrays.stream(DMLProgram.class.getDeclaredMethods())
			.filter(method -> method.getReturnType() == PlacementAnalysis.class
				|| Arrays.asList(method.getParameterTypes()).contains(PlacementAnalysis.class))
			.toList();
		Assert.assertEquals("CAMPAIGN_B_DP_ALTERNATE_NULLABLE_AUTHORITY_SURFACE", 3, authorityMethods.size());
		Assert.assertEquals("CAMPAIGN_B_DP_AUTHORITY_METHOD_NAMES",
			List.of("bindPlacementAnalysisAtFinalHopBoundary", "requirePlacementAnalysisAuthority"),
			authorityMethods.stream().map(Method::getName).distinct().sorted().toList());
		List<String> namedFields = Arrays.stream(DMLProgram.class.getDeclaredFields())
			.filter(field -> authorityNamed(field.getName()))
			.map(field -> Modifier.toString(field.getModifiers()) + " " + field.getGenericType().getTypeName()
				+ " " + field.getName()).sorted().toList();
		Assert.assertEquals("CAMPAIGN_B_DP_EXACT_NAMED_AUTHORITY_FIELDS", List.of(
			"private final java.util.concurrent.atomic.AtomicReference<org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis> _placementAnalysisAuthority"),
			namedFields);
		List<String> namedMethods = Arrays.stream(DMLProgram.class.getDeclaredMethods())
			.filter(method -> authorityNamed(method.getName())).map(CampaignBDpSharedAnalysisOwnerContractTest::methodShape)
			.sorted().toList();
		Assert.assertEquals("CAMPAIGN_B_DP_EXACT_NAMED_AUTHORITY_METHODS", List.of(
			" bindPlacementAnalysisAtFinalHopBoundary():PlacementAnalysis",
			" requirePlacementAnalysisUnboundForHopRewrite():void",
			"public requirePlacementAnalysisAuthority():PlacementAnalysis",
			"public requirePlacementAnalysisAuthority(PlacementAnalysis):void"), namedMethods);

		String programSource = Files.readString(DML_PROGRAM);
		String authoritySurface = authoritySurface(stripCommentsAndStrings(programSource));
		Assert.assertFalse("CAMPAIGN_B_DP_ALTERNATE_AUTHORITY_OBJECT",
			Pattern.compile("\\bObject\\b").matcher(authoritySurface).find());
		Assert.assertFalse("CAMPAIGN_B_DP_ALTERNATE_AUTHORITY_MAP",
			Pattern.compile("\\b(?:Map|HashMap|IdentityHashMap|ConcurrentHashMap)\\b").matcher(authoritySurface).find());
		Assert.assertFalse("CAMPAIGN_B_DP_ALTERNATE_AUTHORITY_REFLECTION",
			Pattern.compile("\\b(?:Class|Method|Field|Constructor|reflect|getClass|forName|loadClass|getDeclared\\w*|"
				+ "getCanonicalName|getTypeName)\\b").matcher(authoritySurface).find());
		Assert.assertFalse("CAMPAIGN_B_DP_ALTERNATE_AUTHORITY_MUTATOR",
			Pattern.compile("(?i)\\b(?:get|set|install|clear|rebind)\\w*(?:authority|placement)|"
				+ "\\b(?:authority|placement)\\w*(?:get|set|install|clear|rebind)\\b")
				.matcher(authoritySurface).find());
		Assert.assertTrue("CAMPAIGN_B_DP_BIND_MUST_BUILD_DETACHED_CANDIDATE",
			programSource.contains("buildDetachedAnalysis(this)"));
		Assert.assertTrue("CAMPAIGN_B_DP_BIND_MUST_CAS",
			programSource.contains("_placementAnalysisAuthority.compareAndSet(null, candidate)"));
		Assert.assertTrue("CAMPAIGN_B_DP_CAS_MUST_RETURN_WINNER",
			programSource.indexOf("_placementAnalysisAuthority.get()",
				programSource.indexOf("compareAndSet(null, candidate)")) > 0);
		for(String forbidden : List.of("AtomicReference<Object>", "PlacementAnalysisAuthorityMarker",
			"clearPlacementAnalysisAuthority", "rebindPlacementAnalysisAuthority",
			"installPlacementAnalysisAuthority(", "Object _placementAnalysisAuthority",
			"AtomicReference _placementAnalysisAuthority", "_placementAnalysisAuthority.clear",
			"java.lang.reflect", "Class.forName("))
			Assert.assertFalse("CAMPAIGN_B_DP_POISONABLE_AUTHORITY_TOKEN|" + forbidden,
				programSource.contains(forbidden));
		Assert.assertFalse("CAMPAIGN_B_DP_CAS_LOSER_ESCAPES", programSource.contains("return candidate;"));

		DMLProgram unbound = ProductionShadowFixtureFactory.compile("B-01");
		Assert.assertNull("CAMPAIGN_B_DP_FRESH_PROGRAM_ALREADY_BOUND", authorityValue(cell, unbound));
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
		builder.buildAnalysis(unbound);
		Assert.assertNull("CAMPAIGN_B_DP_BUILD_ANALYSIS_BOUND_PROGRAM", authorityValue(cell, unbound));
		Method detached = NeutralPlacementGraphBuilder.class.getMethod("buildDetachedAnalysis", DMLProgram.class);
		detached.invoke(builder, unbound);
		Assert.assertNull("CAMPAIGN_B_DP_DETACHED_ANALYSIS_BOUND_PROGRAM", authorityValue(cell, unbound));
		Fixture bound = fixture("B-01");
		Assert.assertSame("CAMPAIGN_B_DP_FINAL_BOUNDARY_CELL_OWNER", bound.analysis(),
			authorityValue(cell, bound.program()));
		builder.buildAnalysis(bound.program());
		Assert.assertSame("CAMPAIGN_B_DP_DETACHED_BUILD_REBOUND_OWNER", bound.analysis(),
			authorityValue(cell, bound.program()));
	}

	@Test
	public void finalBoundaryIsSoleInstallerAndDetachedApisCannotRebind() throws Exception {
		String translator = Files.readString(DML_TRANSLATOR);
		String program = Files.readString(DML_PROGRAM);
		String builder = Files.readString(GRAPH_BUILDER);
		String analysis = Files.readString(PLACEMENT_ANALYSIS);
		String cleanTranslator = stripCommentsAndStrings(translator);
		Assert.assertEquals("CAMPAIGN_B_DP_BIND_CALL_SITE_COUNT", 2,
			countProductionPattern("\\bbindPlacementAnalysisAtFinalHopBoundary\\s*\\("));
		Assert.assertEquals("CAMPAIGN_B_DP_TRANSLATOR_BIND_CALL_SITE_COUNT", 1,
			countPattern(cleanTranslator, "\\.bindPlacementAnalysisAtFinalHopBoundary\\s*\\("));
		int finalBoundary = translator.indexOf("runFederatedPlannerAtFinalHopBoundary");
		int bind = translator.indexOf(".bindPlacementAnalysisAtFinalHopBoundary()", finalBoundary);
		int clear = translator.indexOf("FederatedRefedRegistry.clear()", finalBoundary);
		Assert.assertTrue("CAMPAIGN_B_DP_BIND_NOT_IN_PRIVATE_FINAL_BOUNDARY",
			finalBoundary >= 0 && translator.lastIndexOf("private", finalBoundary) >= 0 && bind > finalBoundary);
		Assert.assertTrue("CAMPAIGN_B_DP_BIND_MUST_PRECEDE_REGISTRY_CLEAR", clear > bind);
		Assert.assertTrue("CAMPAIGN_B_DP_CONSTRUCT_LOPS_MUST_ENTER_FINAL_BOUNDARY",
			translator.indexOf("runFederatedPlannerAtFinalHopBoundary", translator.indexOf("constructLops(DMLProgram")) >= 0);
		String boundaryMethod = methodDeclarationAndBody(cleanTranslator,
			"private\\s+static\\s+void\\s+runFederatedPlannerAtFinalHopBoundary\\s*\\([^)]*\\)");
		Assert.assertTrue("CAMPAIGN_B_DP_FINAL_BOUNDARY_HELPER_NOT_PRIVATE",
			boundaryMethod.stripLeading().startsWith("private static void runFederatedPlannerAtFinalHopBoundary"));
		String constructBody = methodDeclarationAndBody(cleanTranslator,
			"public\\s+void\\s+constructLops\\s*\\(\\s*DMLProgram\\s+\\w+\\s*,\\s*Consumer<[^>]+>\\s+\\w+\\s*\\)");
		Assert.assertEquals("CAMPAIGN_B_DP_BIND_OUTSIDE_FINAL_BOUNDARY", 1,
			countPattern(boundaryMethod, "\\.bindPlacementAnalysisAtFinalHopBoundary\\s*\\("));
		Assert.assertEquals("CAMPAIGN_B_DP_CONSTRUCT_MUST_CALL_BOUNDARY_ONCE", 1,
			countPattern(constructBody, "\\brunFederatedPlannerAtFinalHopBoundary\\s*\\("));
		Assert.assertEquals("CAMPAIGN_B_DP_BOUNDARY_CALL_OUTSIDE_CONSUMER_CONSTRUCT", 0,
			countPattern(cleanTranslator.replace(constructBody, " ").replace(boundaryMethod, " "),
				"\\brunFederatedPlannerAtFinalHopBoundary\\s*\\("));
		Assert.assertTrue("CAMPAIGN_B_DP_UNBOUND_REWRITE_GUARD_MISSING",
			translator.contains("requirePlacementAnalysisUnboundForHopRewrite"));
		Assert.assertTrue("CAMPAIGN_B_DP_DETACHED_BUILDER_MISSING", builder.contains("buildDetachedAnalysis"));
		Assert.assertTrue("CAMPAIGN_B_DP_BUILD_ANALYSIS_MUST_DELEGATE_DETACHED",
			builder.indexOf("buildDetachedAnalysis(program)", builder.indexOf("buildAnalysis(DMLProgram")) >= 0);
		Assert.assertFalse("CAMPAIGN_B_DP_BUILDER_MUST_NOT_BIND", builder.contains("bindPlacementAnalysisAtFinalHopBoundary"));
		Assert.assertTrue("CAMPAIGN_B_DP_COMMON_CANONICAL_ASSERT_MISSING",
			analysis.contains("requirePlacementAnalysisAuthority(this)"));
		Assert.assertFalse("CAMPAIGN_B_DP_AUTHORITY_CLEAR_API_FORBIDDEN", program.contains("clearPlacementAnalysis"));
	}

	@Test
	public void everyDetachedSeamPreservesUnboundAuthorityAndRunState() throws Exception {
		Field cell = requiredAuthorityField();
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-05");
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
		PlacementAnalysis detached = builder.buildAnalysis(program);
		Hop sentinel = detached.occurrences().stream().map(HopOccurrenceProjection::hop)
			.filter(hop -> FederatedPlannerUtils.plannerRecompileSignature(hop) != null).findFirst().orElseThrow();
		FunctionCallGraph fgraph = new FunctionCallGraph(program);
		seedRunState(sentinel);
		ProgramSnapshot before = snapshotProgram(program, detached);
		RunStateSnapshot runBefore = snapshotRunState(sentinel);
		try {
			Assert.assertNull(authorityValue(cell, program));
			invokeOptional(builder, "buildDetachedAnalysis", new Class<?>[] {DMLProgram.class}, program);
			Assert.assertNull(authorityValue(cell, program));
			builder.build(program); Assert.assertNull(authorityValue(cell, program));
			builder.selectedProjection(program); Assert.assertNull(authorityValue(cell, program));
			builder.selectedMembershipViolations(program, detached.graph());
			Assert.assertNull(authorityValue(cell, program));
			Class<?> coordinator = Class.forName("org.apache.sysds.hops.fedplanner.placement.PlacementShadowCoordinator");
			Object legacy = coordinator.getMethod("begin", DMLProgram.class).invoke(null, program);
			Assert.assertNull(authorityValue(cell, program));
			legacy.getClass().getMethod("observe", DMLProgram.class).invoke(legacy, program);
			Assert.assertNull(authorityValue(cell, program));
			Class<?> seam = Class.forName(coordinator.getName() + "$ShadowAnalysis");
			Object injected = Proxy.newProxyInstance(seam.getClassLoader(), new Class<?>[] {seam}, (proxy, method, args) ->
				switch(method.getName()) {
					case "build" -> builder.build(program);
					case "selectedProjection" -> builder.selectedProjection(program);
					case "selectedMembershipViolations" -> builder.selectedMembershipViolations(program, detached.graph());
					default -> throw new AssertionError(method);
				});
			Method beginInjected = coordinator.getDeclaredMethod("begin", DMLProgram.class, seam);
			beginInjected.setAccessible(true); beginInjected.invoke(null, program, injected);
			Assert.assertNull(authorityValue(cell, program));
			assertProgramSame(before, snapshotProgram(program, detached));
			assertRunStateSame(runBefore, snapshotRunState(sentinel));
		}
		finally { clearRunState(); }
	}

	@Test
	public void callableIpaRejectsUnboundBeforeEverySentinel() throws Exception {
		Field cell = requiredAuthorityField();
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-05");
		PlacementAnalysis detached = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Hop sentinel = detached.occurrences().stream().map(HopOccurrenceProjection::hop)
			.filter(hop -> FederatedPlannerUtils.plannerRecompileSignature(hop) != null).findFirst().orElseThrow();
		seedRunState(sentinel);
		ProgramSnapshot before = snapshotProgram(program, detached); RunStateSnapshot runBefore = snapshotRunState(sentinel);
		AtomicInteger receipts = new AtomicInteger(); Object shadowBefore = shadowLastRecorded();
		try {
			Method ipa = IPAPassRewriteFederatedPlan.class.getMethod("rewriteProgram", DMLProgram.class,
				FunctionCallGraph.class, FunctionCallSizeInfo.class, Consumer.class);
			try {
				ipa.invoke(new IPAPassRewriteFederatedPlan(), program, fgraph, null,
					(Consumer<Object>) receipt -> receipts.incrementAndGet());
				Assert.fail("CAMPAIGN_B_DP_IPA_ACCEPTED_UNBOUND_PROGRAM");
			}
			catch(InvocationTargetException expected) {
				Assert.assertTrue("CAMPAIGN_B_DP_IPA_UNBOUND_WRONG_FAILURE",
					expected.getCause() instanceof IllegalStateException);
			}
			Assert.assertNull(authorityValue(cell, program)); Assert.assertEquals(0, receipts.get());
			Assert.assertSame(shadowBefore, shadowLastRecorded());
			assertProgramSame(before, snapshotProgram(program, detached));
			assertRunStateSame(runBefore, snapshotRunState(sentinel));
		}
		finally { clearRunState(); }
	}

	@Test
	public void supportedFrontendsIpaAndShadowPreserveFinalBoundaryOwner() throws Exception {
		assertOrdered(Files.readString(DML_SCRIPT), "rewriteHopsDAG(prog)", "constructLops(prog)",
			"CAMPAIGN_B_DP_CLI_FINAL_BOUNDARY_ORDER");
		assertOrdered(Files.readString(JMLC_CONNECTION), "rewriteHopsDAG(prog)", "constructLops(prog)",
			"CAMPAIGN_B_DP_JMLC_FINAL_BOUNDARY_ORDER");
		assertOrdered(Files.readString(ML_CONTEXT_EXECUTOR), "rewritePersistentReadsAndWrites()", "constructLops()",
			"CAMPAIGN_B_DP_MLCONTEXT_FINAL_BOUNDARY_ORDER");
		assertOrdered(Files.readString(RESOURCE_COMPILER), "rewriteHopsDAG(dmlProgram)",
			"constructLops(dmlProgram)", "CAMPAIGN_B_DP_RESOURCE_FINAL_BOUNDARY_ORDER");

		String ipa = Files.readString(IPA_PASS);
		int entry = ipa.indexOf("rewriteProgram(DMLProgram");
		int require = ipa.indexOf("requireAuthoritativeAnalysis", entry);
		int clear = ipa.indexOf("FederatedRefedRegistry.clear()", entry);
		int shadowBegin = ipa.indexOf("PlacementShadowCoordinator.begin(prog, analysis)", entry);
		Assert.assertTrue("CAMPAIGN_B_DP_IPA_UNBOUND_CHECK_AFTER_CLEAR",
			entry >= 0 && require > entry && clear > require && shadowBegin > clear);
		String shadow = Files.readString(SHADOW_COORDINATOR);
		Assert.assertTrue("CAMPAIGN_B_DP_SHADOW_A1_BEGIN_MISSING",
			shadow.contains("begin(DMLProgram program, PlacementAnalysis analysis)"));
		Assert.assertTrue("CAMPAIGN_B_DP_SHADOW_MUST_REUSE_A1_GRAPH", shadow.contains("analysis.graph()"));
		Assert.assertFalse("CAMPAIGN_B_DP_SHADOW_MUST_NOT_BIND", shadow.contains("bindPlacementAnalysisAtFinalHopBoundary"));
	}

	@Test
	public void shadowBeginUsesExactA1GraphAndRejectsDetachedA2() throws Exception {
		Fixture owner = fixture("B-05");
		PlacementAnalysis detached = new NeutralPlacementGraphBuilder().buildAnalysis(owner.program());
		Assert.assertNotSame(owner.analysis(), detached);
		Class<?> coordinator = Class.forName(
			"org.apache.sysds.hops.fedplanner.placement.PlacementShadowCoordinator");
		Method begin;
		try {
			begin = coordinator.getMethod("begin", DMLProgram.class, PlacementAnalysis.class);
		}
		catch(NoSuchMethodException missing) {
			throw new AssertionError("CAMPAIGN_B_DP_SHADOW_A1_BEGIN_MISSING", missing);
		}
		Object session = begin.invoke(null, owner.program(), owner.analysis());
		Assert.assertSame("CAMPAIGN_B_DP_SHADOW_DID_NOT_REUSE_A1_GRAPH", owner.analysis().graph(),
			session.getClass().getMethod("graph").invoke(session));
		Field cell = requiredAuthorityField();
		Hop sentinel = owner.analysis().occurrences().stream().map(HopOccurrenceProjection::hop)
			.filter(hop -> FederatedPlannerUtils.plannerRecompileSignature(hop) != null).findFirst().orElseThrow();
		seedRunState(sentinel); RunStateSnapshot runBefore = snapshotRunState(sentinel);
		ProgramSnapshot ownerBefore = snapshotProgram(owner.program(), owner.analysis()); Object shadowBefore = shadowLastRecorded();
		try {
			expectShadowReject(begin, owner.program(), detached, IllegalArgumentException.class,
				"CAMPAIGN_B_DP_SHADOW_ACCEPTED_DETACHED_A2");
			Assert.assertSame(owner.analysis(), authorityValue(cell, owner.program()));
			DMLProgram unbound = ProductionShadowFixtureFactory.compile("B-01");
			PlacementAnalysis unboundAnalysis = new NeutralPlacementGraphBuilder().buildAnalysis(unbound);
			expectShadowReject(begin, unbound, unboundAnalysis, IllegalArgumentException.class,
				"CAMPAIGN_B_DP_SHADOW_ACCEPTED_UNBOUND");
			Assert.assertNull(authorityValue(cell, unbound));
			DMLProgram foreignProgram = ProductionShadowFixtureFactory.compile("B-02");
			PlacementAnalysis foreign = new NeutralPlacementGraphBuilder().buildAnalysis(foreignProgram);
			expectShadowReject(begin, owner.program(), foreign, IllegalArgumentException.class,
				"CAMPAIGN_B_DP_SHADOW_ACCEPTED_FOREIGN");
			Assert.assertSame(owner.analysis(), authorityValue(cell, owner.program()));
			Assert.assertNull(authorityValue(cell, foreignProgram));
			assertProgramSame(ownerBefore, snapshotProgram(owner.program(), owner.analysis()));
			assertRunStateSame(runBefore, snapshotRunState(sentinel)); Assert.assertSame(shadowBefore, shadowLastRecorded());
		}
		finally { clearRunState(); }
	}

	private static void expectShadowReject(Method begin, DMLProgram program, PlacementAnalysis analysis,
		Class<? extends Throwable> type, String code) throws Exception {
		try { begin.invoke(null, program, analysis); Assert.fail(code); }
		catch(InvocationTargetException expected) {
			Assert.assertTrue(code + "|cause=" + expected.getCause(), type.isInstance(expected.getCause()));
		}
	}

	private static Object authorityValue(Field cell, DMLProgram program) throws Exception {
		cell.setAccessible(true);
		return ((AtomicReference<?>) cell.get(program)).get();
	}

	static Field requiredAuthorityField() {
		try {
			Field field = DMLProgram.class.getDeclaredField("_placementAnalysisAuthority");
			if(field.getType() != AtomicReference.class || !Modifier.isPrivate(field.getModifiers())
				|| !Modifier.isFinal(field.getModifiers()) || !field.getGenericType().getTypeName().equals(
					"java.util.concurrent.atomic.AtomicReference<org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis>"))
				throw new AssertionError("CAMPAIGN_B_DP_CONCRETE_AUTHORITY_CELL_MISSING|wrong-shape=" + field);
			return field;
		}
		catch(NoSuchFieldException missing) {
			throw new AssertionError("CAMPAIGN_B_DP_CONCRETE_AUTHORITY_CELL_MISSING", missing);
		}
	}

	private static boolean authorityNamed(String name) {
		String lower = name.toLowerCase(); return lower.contains("authority") || lower.contains("placement");
	}

	private static String methodShape(Method method) {
		return Modifier.toString(method.getModifiers()) + " " + method.getName() + "("
			+ Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).collect(java.util.stream.Collectors.joining(","))
			+ "):" + method.getReturnType().getSimpleName();
	}

	private static String authoritySurface(String source) {
		StringBuilder surface = new StringBuilder();
		Matcher methods = Pattern.compile(
			"(?:public|protected|private|static|final|synchronized|native|abstract|\\s)+"
				+ "[\\w.$<>, ?\\[\\]]+\\s+(\\w+)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[^{}]+)?\\{")
			.matcher(source);
		while(methods.find()) {
			String method = braceDelimited(source, methods.start(), source.indexOf('{', methods.start()));
			if(authorityNamed(methods.group(1)) || method.contains("_placementAnalysisAuthority"))
				surface.append(method).append('\n');
		}
		source.lines().filter(line -> Pattern.compile("(?i)\\b[\\w<>?,. ]+\\s+\\w*(?:authority|placement)\\w*\\s*(?:=|;)")
			.matcher(line).find()).forEach(line -> surface.append(line).append('\n'));
		return surface.toString();
	}

	private static Object shadowLastRecorded() throws Exception {
		return Class.forName("org.apache.sysds.hops.fedplanner.placement.PlacementShadowCoordinator")
			.getMethod("lastRecordedObservation").invoke(null);
	}

	private static Object invokeOptional(Object target, String name, Class<?>[] types, Object... args) throws Exception {
		return target.getClass().getMethod(name, types).invoke(target, args);
	}

	private static int countProductionPattern(String regex) throws Exception {
		try(var files = Files.walk(Path.of("src/main/java"))) {
			return files.filter(path -> path.toString().endsWith(".java")).mapToInt(path -> {
				try {
					return countPattern(stripCommentsAndStrings(Files.readString(path)), regex);
				}
				catch(Exception e) {
					throw new AssertionError("Unable to inspect " + path, e);
				}
			}).sum();
		}
	}

	private static int countPattern(String source, String regex) {
		int count = 0; Matcher matcher = Pattern.compile(regex).matcher(source);
		while(matcher.find()) count++;
		return count;
	}

	private static String methodDeclarationAndBody(String source, String declarationRegex) {
		Matcher method = Pattern.compile(declarationRegex + "\\s*(?:throws\\s+[^{}]+)?\\{").matcher(source);
		if(!method.find()) throw new AssertionError("CAMPAIGN_B_DP_METHOD_DECLARATION_MISSING|" + declarationRegex);
		return braceDelimited(source, method.start(), source.indexOf('{', method.start()));
	}

	private static String braceDelimited(String source, int start, int open) {
		int depth = 1;
		for(int i = open + 1; i < source.length(); i++) {
			if(source.charAt(i) == '{') depth++;
			else if(source.charAt(i) == '}' && --depth == 0) return source.substring(start, i + 1);
		}
		throw new AssertionError("CAMPAIGN_B_DP_DELIMITED_BLOCK_UNCLOSED|start=" + start);
	}

	private static String stripCommentsAndStrings(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", " ");
	}

	private static void assertOrdered(String source, String first, String second, String code) {
		int left = source.indexOf(first), right = source.indexOf(second, left + Math.max(0, first.length()));
		Assert.assertTrue(code + "|first=" + first + "|second=" + second, left >= 0 && right > left);
	}

	private static Fixture fixture(String id) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			return new Fixture(program, finalBoundaryOwnerOrDetached(program));
		}
		catch(Exception e) {
			throw new AssertionError("Unable to compile DP shared-analysis fixture " + id, e);
		}
	}

	@SuppressWarnings("unchecked")
	private static PlacementAnalysis finalBoundaryOwnerOrDetached(DMLProgram program) throws Exception {
		Method boundary;
		try {
			boundary = DMLTranslator.class.getMethod("constructLops", DMLProgram.class, Consumer.class);
		}
		catch(NoSuchMethodException prePatch) {
			return new NeutralPlacementGraphBuilder().buildAnalysis(program);
		}
		String oldPlanner = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
		AtomicReference<Object> receipt = new AtomicReference<>();
		try {
			ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
			boundary.invoke(new DMLTranslator(program), program,
				(Consumer<Object>) value -> Assert.assertTrue("multiple final-boundary receipts",
					receipt.compareAndSet(null, value)));
		}
		finally {
			ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, oldPlanner);
		}
		Assert.assertNotNull("CAMPAIGN_B_DP_FINAL_BOUNDARY_RECEIPT_MISSING", receipt.get());
		return (PlacementAnalysis) receipt.get().getClass().getMethod("analysis").invoke(receipt.get());
	}

	private static AnalysisSnapshot snapshotAnalysis(PlacementAnalysis analysis) {
		return new AnalysisSnapshot(analysis, analysis.graph(), analysis.analysisFingerprint(),
			List.copyOf(analysis.occurrences()), analysis.occurrences().stream()
				.map(OccurrenceSnapshot::new).toList());
	}

	private static void assertSameHopOrigins(PlacementAnalysis owner, PlacementAnalysis copied) {
		Assert.assertEquals(owner.occurrences().size(), copied.occurrences().size());
		for(int i = 0; i < owner.occurrences().size(); i++) {
			HopOccurrenceProjection left = owner.occurrences().get(i), right = copied.occurrences().get(i);
			Assert.assertSame("same-program Hop origin " + i, left.hop(), right.hop());
			Assert.assertEquals("same-program compiled key " + i, left.key(), right.key());
			Assert.assertEquals("same-program normalized ordinal " + i,
				left.normalizedOrdinal(), right.normalizedOrdinal());
			Assert.assertEquals("same-program normalized signature " + i,
				left.normalizedSignature(), right.normalizedSignature());
		}
	}

	private static void seedRunState(Hop recompileSentinel) {
		clearRunState();
		FederatedPlannerUtils.registerFedInitVar(SENTINEL_VAR, FType.ROW, SENTINEL_SIGNATURE);
		FederatedPlannerUtils.registerFedAnchorKey(SENTINEL_VAR, SENTINEL_ANCHOR);
		FederatedPlannerUtils.registerPlannerRecompileState(recompileSentinel, ExecType.CP, FederatedOutput.LOUT);
		FederatedRefedRegistry.register(SENTINEL_SCOPE, SENTINEL_HOP, SENTINEL_ANCHOR_HOP, SENTINEL_ANCHOR);
		FederatedFoutMaterializeRegistry.register(SENTINEL_SCOPE, SENTINEL_HOP, SENTINEL_ANCHOR_HOP,
			FType.ROW.name(), SENTINEL_VAR, SENTINEL_ANCHOR);
		FederatedLocalMaterializeRegistry.register(SENTINEL_SCOPE, SENTINEL_HOP,
			List.of(SENTINEL_ANCHOR_HOP), FType.ROW.name(), "campaign-b-dp-owner-sentinel");
	}

	private static RunStateSnapshot snapshotRunState(Hop recompileSentinel) {
		String recompileSignature = FederatedPlannerUtils.plannerRecompileSignature(recompileSentinel);
		return new RunStateSnapshot(FederatedPlannerUtils.isFedInitVar(SENTINEL_VAR),
			FederatedPlannerUtils.getFedInitFType(SENTINEL_VAR),
			FederatedPlannerUtils.getFedInitSignature(SENTINEL_VAR),
			FederatedPlannerUtils.getFedAnchorKey(SENTINEL_VAR), recompileSignature,
			FederatedPlannerUtils.getPlannerRecompileState(recompileSignature),
			FederatedRefedRegistry.snapshot(SENTINEL_SCOPE),
			FederatedFoutMaterializeRegistry.snapshot(SENTINEL_SCOPE),
			FederatedLocalMaterializeRegistry.snapshotScopes(SENTINEL_SCOPE));
	}

	private static void clearRunState() {
		FederatedPlannerUtils.removeFedAnchorKey(SENTINEL_VAR);
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}

	private static void assertRunStateSame(RunStateSnapshot expected, RunStateSnapshot actual) {
		Assert.assertEquals("fed-init sentinel changed", expected.fedInit(), actual.fedInit());
		Assert.assertSame("fed-init FType changed", expected.fedInitType(), actual.fedInitType());
		Assert.assertEquals("fed-init signature changed", expected.fedInitSignature(), actual.fedInitSignature());
		Assert.assertEquals("fed anchor changed", expected.anchorKey(), actual.anchorKey());
		Assert.assertEquals("recompile signature changed", expected.recompileSignature(), actual.recompileSignature());
		Assert.assertSame("recompile state changed", expected.recompileState(), actual.recompileState());
		assertRegistrySame(expected.refed(), actual.refed(), "refed registry");
		assertRegistrySame(expected.fout(), actual.fout(), "FOUT registry");
		Assert.assertEquals("local-materialize scopes changed", expected.local().keySet(), actual.local().keySet());
		for(long scope : expected.local().keySet())
			assertRegistrySame(expected.local().get(scope), actual.local().get(scope),
				"local-materialize registry scope " + scope);
	}

	private static void assertRegistrySame(Map<?, ?> expected, Map<?, ?> actual, String label) {
		Assert.assertEquals(label + " keys", expected.keySet(), actual.keySet());
		for(Object key : expected.keySet())
			Assert.assertSame(label + " value " + key, expected.get(key), actual.get(key));
	}

	private static String sha256(Path path) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
		StringBuilder value = new StringBuilder(digest.length * 2);
		for(byte item : digest)
			value.append(String.format("%02x", item & 0xff));
		return value.toString();
	}

	private static ProgramSnapshot snapshotProgram(DMLProgram program, PlacementAnalysis analysis) {
		return new ProgramSnapshot(program, PlacementGraphFingerprint.capture(program), snapshotAnalysis(analysis),
			analysis.occurrences().stream().map(HopOccurrenceProjection::hop).distinct()
				.map(HopSnapshot::new).toList());
	}

	private static void assertProgramSame(ProgramSnapshot expected, ProgramSnapshot actual) {
		Assert.assertSame(expected.program(), actual.program());
		Assert.assertEquals(expected.fingerprint(), actual.fingerprint());
		assertAnalysisSame(expected.analysis(), actual.analysis());
		Assert.assertEquals(expected.hops().size(), actual.hops().size());
		for(int i = 0; i < expected.hops().size(); i++)
			assertHopSame(expected.hops().get(i), actual.hops().get(i));
	}

	private static void assertAnalysisSame(AnalysisSnapshot expected, AnalysisSnapshot actual) {
		Assert.assertSame(expected.analysis(), actual.analysis());
		Assert.assertSame(expected.graph(), actual.graph());
		Assert.assertEquals(expected.fingerprint(), actual.fingerprint());
		assertIdentityList(expected.occurrences(), actual.occurrences(), "analysis occurrences");
		Assert.assertEquals(expected.states().size(), actual.states().size());
		for(int i = 0; i < expected.states().size(); i++) {
			OccurrenceSnapshot left = expected.states().get(i), right = actual.states().get(i);
			Assert.assertSame(left.occurrence(), right.occurrence());
			Assert.assertSame(left.key(), right.key());
			Assert.assertSame(left.hop(), right.hop());
			Assert.assertEquals(left.ordinal(), right.ordinal());
			Assert.assertEquals(left.signature(), right.signature());
		}
	}

	private static void assertHopSame(HopSnapshot expected, HopSnapshot actual) {
		Assert.assertSame(expected.hop(), actual.hop());
		Assert.assertEquals(expected.hopId(), actual.hopId());
		Assert.assertSame(expected.execType(), actual.execType());
		Assert.assertSame(expected.output(), actual.output());
		Assert.assertEquals(expected.requiresRecompile(), actual.requiresRecompile());
		assertIdentityList(expected.inputs(), actual.inputs(), "hop inputs");
		assertIdentityList(expected.parents(), actual.parents(), "hop parents");
	}

	private static void assertIdentityList(List<?> expected, List<?> actual, String label) {
		Assert.assertEquals(label, expected.size(), actual.size());
		for(int i = 0; i < expected.size(); i++)
			Assert.assertSame(label + '[' + i + ']', expected.get(i), actual.get(i));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(List<?> values, String label) {
		try {
			((List) values).add(null);
			Assert.fail("mutable " + label);
		}
		catch(UnsupportedOperationException expected) {
			// expected
		}
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis) { }
	private record AnalysisSnapshot(PlacementAnalysis analysis,
		org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph graph, String fingerprint,
		List<HopOccurrenceProjection> occurrences, List<OccurrenceSnapshot> states) { }
	private record OccurrenceSnapshot(HopOccurrenceProjection occurrence,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey key, Hop hop, int ordinal,
		String signature) {
		private OccurrenceSnapshot(HopOccurrenceProjection occurrence) {
			this(occurrence, occurrence.key(), occurrence.hop(), occurrence.normalizedOrdinal(),
				occurrence.normalizedSignature());
		}
	}
	private record HopSnapshot(Hop hop, long hopId, org.apache.sysds.common.Types.ExecType execType,
		FederatedOutput output, boolean requiresRecompile, List<Hop> inputs, List<Hop> parents) {
		private HopSnapshot(Hop hop) {
			this(hop, hop.getHopID(), hop.getForcedExecType(), hop.getFederatedOutput(), hop.requiresRecompile(),
				List.copyOf(hop.getInput()), List.copyOf(hop.getParent()));
		}
	}
	private record ProgramSnapshot(DMLProgram program, String fingerprint, AnalysisSnapshot analysis,
		List<HopSnapshot> hops) { }
	private record RunStateSnapshot(boolean fedInit, FType fedInitType, String fedInitSignature, String anchorKey,
		String recompileSignature, PlannerRecompileState recompileState, Map<Long, AnchorSpec> refed,
		Map<Long, MaterializeSpec> fout, Map<Long, Map<Long, LocalMaterializeSpec>> local) {
		private boolean sameIdentities(RunStateSnapshot that) {
			return fedInit == that.fedInit && fedInitType == that.fedInitType
				&& java.util.Objects.equals(fedInitSignature, that.fedInitSignature)
				&& java.util.Objects.equals(anchorKey, that.anchorKey)
				&& recompileState == that.recompileState && refed.equals(that.refed)
				&& fout.equals(that.fout) && local.equals(that.local);
		}
	}
}
