/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.test.component.federated.placement.guard.R4SharedFedAllAdapterBridge.Bound;
import org.apache.sysds.test.component.federated.placement.guard.R4SharedFedAllAdapterBridge.Certificate;
import org.apache.sysds.test.component.federated.placement.guard.R4SharedFedAllAdapterBridge.Score;
import org.apache.sysds.test.component.federated.placement.guard.R4SharedFedAllAdapterBridge.Selection;
import org.apache.sysds.test.component.federated.placement.selector.CampaignBSelectorFixtureBridge;
import org.junit.Assert;
import org.junit.Test;

/** Complete-but-wrong controls prove every field gate rejects a full, legal-looking result. */
public class R4SharedFedAllSemanticValidatorTest {
	@Test public void completeButWrongResultsFailAtTheirExactFields() {
		var fixture = CampaignBSelectorFixtureBridge.all().stream().filter(c -> c.id().equals("S-04")).findFirst().orElseThrow();
		PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(fixture.production());
		Selection good = selection(analysis, fixture.production().relocationActions().stream().map(a -> a.key()).toList());
		var expected = expected(good);
		R4SharedFedAllSemanticValidator.shared(analysis, good); R4SharedFedAllSemanticValidator.fedAll(expected, good);

		PlacementAnalysis twin = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(fixture.production());
		expect("R4_ANALYSIS_IDENTITY", () -> R4SharedFedAllSemanticValidator.shared(analysis,
			new Selection(twin, good.assignment(), good.relocations(), good.score(), good.certificate())));

		Map<CompiledHopKey,PlacementState> wrong = new LinkedHashMap<>(good.assignment());
		var node = fixture.production().nodes().stream().filter(n -> n.legalAlternatives().size() > 1).findFirst().orElseThrow();
		wrong.put(node.key(), node.legalAlternatives().get(1));
		expect("R4_ASSIGNMENT_STATE", () -> R4SharedFedAllSemanticValidator.fedAll(expected,
			new Selection(analysis, Map.copyOf(wrong), good.relocations(), good.score(), good.certificate())));
		expect("R4_RELOCATION_KEY", () -> R4SharedFedAllSemanticValidator.fedAll(expected,
			new Selection(analysis, good.assignment(), List.of(), good.score(), good.certificate())));

		var foreignFixture = CampaignBSelectorFixtureBridge.all().stream().filter(c -> c.id().equals("S-06")).findFirst().orElseThrow();
		RelocationActionKey foreign = foreignFixture.production().relocationActions().get(0).key();
		expect("R4_RELOCATION_KEY", () -> R4SharedFedAllSemanticValidator.shared(analysis,
			new Selection(analysis, good.assignment(), List.of(foreign), good.score(), good.certificate())));

		expect("R4_SCORE_SIGNATURE", () -> R4SharedFedAllSemanticValidator.fedAll(expected,
			withScore(good, new Score(good.score().fed(), good.score().fout(), good.score().relocations(), "forged"))));
		expect("R4_GRAPH_HASH", () -> R4SharedFedAllSemanticValidator.fedAll(expected,
			withCertificate(good, certificate(good, "forged", good.certificate().assignmentHash(), good.certificate().bounds(),
				good.certificate().universe(), good.certificate().boundDerivation(), "EXHAUSTED", false))));
		expect("R4_ASSIGNMENT_HASH", () -> R4SharedFedAllSemanticValidator.fedAll(expected,
			withCertificate(good, certificate(good, good.certificate().graphFingerprint(), "forged", good.certificate().bounds(),
				good.certificate().universe(), good.certificate().boundDerivation(), "EXHAUSTED", false))));
		expect("R4_BOUND_COMPONENT", () -> R4SharedFedAllSemanticValidator.fedAll(expected,
			withCertificate(good, certificate(good, good.certificate().graphFingerprint(), good.certificate().assignmentHash(), List.of(),
				good.certificate().universe(), good.certificate().boundDerivation(), "EXHAUSTED", false))));
		expect("R4_BOUND_DERIVATION", () -> R4SharedFedAllSemanticValidator.fedAll(expected,
			withCertificate(good, certificate(good, good.certificate().graphFingerprint(), good.certificate().assignmentHash(), good.certificate().bounds(),
				good.certificate().universe(), "copied-incumbent", "EXHAUSTED", false))));
		expect("R4_UNIVERSE", () -> R4SharedFedAllSemanticValidator.fedAll(expected,
			withCertificate(good, certificate(good, good.certificate().graphFingerprint(), good.certificate().assignmentHash(), good.certificate().bounds(),
				good.certificate().universe() + 1, good.certificate().boundDerivation(), "EXHAUSTED", false))));
		expect("R4_TERMINATION", () -> R4SharedFedAllSemanticValidator.fedAll(expected,
			withCertificate(good, certificate(good, good.certificate().graphFingerprint(), good.certificate().assignmentHash(), good.certificate().bounds(),
				good.certificate().universe(), good.certificate().boundDerivation(), "PARTIAL", false))));
		expect("R4_FALLBACK", () -> R4SharedFedAllSemanticValidator.fedAll(expected,
			withCertificate(good, certificate(good, good.certificate().graphFingerprint(), good.certificate().assignmentHash(), good.certificate().bounds(),
				good.certificate().universe(), good.certificate().boundDerivation(), "EXHAUSTED", true))));
		expect("R4_ORDER_STABILITY", () -> R4SharedFedAllSemanticValidator.stable(good,
			withScore(good, new Score(good.score().fed(), good.score().fout(), good.score().relocations(), "order-dependent")),
			"R4_ORDER_STABILITY"));
	}

	@Test public void mutableTopLevelAndNestedProductionCollectionsAreRejected() throws Exception {
		var fixture = CampaignBSelectorFixtureBridge.all().get(0);
		PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(fixture.production());
		Selection good = selection(analysis, List.of());
		expect("R4_RESULT_MUTABILITY|assignment", () -> invokeNormalize(new FakeResult(good, true, false)));
		expect("R4_RESULT_MUTABILITY|bounds", () -> invokeNormalize(new FakeResult(good, false, true)));
	}

	private static Selection selection(PlacementAnalysis analysis, List<RelocationActionKey> relocations) {
		Map<CompiledHopKey,PlacementState> assignment = new LinkedHashMap<>();
		analysis.graph().nodes().forEach(n -> assignment.put(n.key(), n.legalAlternatives().get(0)));
		int fed = (int)assignment.values().stream().filter(s -> s.execType().name().equals("FED")).count();
		int fout = (int)assignment.values().stream().filter(s -> s.output().name().equals("FOUT")).count();
		Score score = new Score(fed, fout, relocations.size(), "correct-signature");
		List<Bound> bounds = R4SharedFedAllSemanticValidator.componentBounds(analysis.graph());
		long universe = 7;
		Certificate c = new Certificate(R4SharedFedAllAdapterBridge.graphHash(analysis),
			R4SharedFedAllAdapterBridge.assignmentHash(assignment), 4, 3, universe, score, score, bounds,
			analysis.graph().nodes().size(), analysis.graph().constraints().size(), bounds.size(),
			"complete-cartesian-enumeration-with-partial-legality-pruning", "EXHAUSTED", false);
		return new Selection(analysis, Map.copyOf(assignment), List.copyOf(relocations), score, c);
	}
	private static R4SharedFedAllSemanticValidator.Expected expected(Selection s) {
		var c=s.certificate(); return new R4SharedFedAllSemanticValidator.Expected(s.assignment(),s.relocations(),s.score(),
			c.graphFingerprint(),c.assignmentHash(),c.explored(),c.pruned(),c.universe(),c.graphNodes(),c.graphConstraints(),
			c.graphComponents(),c.boundDerivation(),c.bounds());
	}
	private static Selection withScore(Selection s, Score score) { return new Selection(s.analysis(),s.assignment(),s.relocations(),score,s.certificate()); }
	private static Selection withCertificate(Selection s, Certificate c) { return new Selection(s.analysis(),s.assignment(),s.relocations(),s.score(),c); }
	private static Certificate certificate(Selection s,String graph,String assignment,List<Bound> bounds,long universe,String derivation,String termination,boolean fallback) {
		var c=s.certificate(); return new Certificate(graph,assignment,c.explored(),c.pruned(),universe,c.incumbent(),c.upperBound(),bounds,
			c.graphNodes(),c.graphConstraints(),c.graphComponents(),derivation,termination,fallback);
	}
	private static void expect(String code, Runnable action) {
		try { action.run(); Assert.fail("expected " + code); }
		catch(AssertionError e) { if(e.getMessage()!=null && e.getMessage().startsWith("expected ")) throw e;
			Assert.assertTrue("expected="+code+" actual="+e.getMessage(), e.getMessage()!=null && e.getMessage().startsWith(code)); }
	}
	private static void invokeNormalize(Object value) { try { R4SharedFedAllAdapterBridge.normalize(value); }
		catch(AssertionError e) { throw e; } catch(Exception e) { throw new AssertionError(e); } }

	public static final class FakeResult {
		private final Selection s; private final boolean mutableAssignment, mutableBounds;
		FakeResult(Selection s,boolean mutableAssignment,boolean mutableBounds){this.s=s;this.mutableAssignment=mutableAssignment;this.mutableBounds=mutableBounds;}
		public PlacementAnalysis analysis(){return s.analysis();}
		public Map<CompiledHopKey,PlacementState> assignment(){return mutableAssignment?new LinkedHashMap<>(s.assignment()):s.assignment();}
		public List<RelocationActionKey> selectedRelocations(){return s.relocations();}
		public FakeScore score(){return new FakeScore(s.score());}
		public FakeCertificate certificate(){return new FakeCertificate(s.certificate(),mutableBounds);}
	}
	public static final class FakeScore { private final Score s; FakeScore(Score s){this.s=s;} public int fedCount(){return s.fed();}
		public int foutCount(){return s.fout();} public int relocationCount(){return s.relocations();} public String normalizedSignature(){return s.signature();} }
	public static final class FakeBound { private final Bound b; FakeBound(Bound b){this.b=b;} public String componentId(){return b.id();}
		public List<CompiledHopKey> nodeKeys(){return b.nodes();} public int upperFed(){return b.upperFed();} public int upperFout(){return b.upperFout();}
		public int lowerRelocations(){return b.lowerRelocations();} public String derivation(){return b.derivation();} }
	public static final class FakeCertificate { private final Certificate c; private final boolean mutable; FakeCertificate(Certificate c,boolean mutable){this.c=c;this.mutable=mutable;}
		public String graphFingerprint(){return c.graphFingerprint();} public String assignmentHash(){return c.assignmentHash();}
		public long exploredCount(){return c.explored();} public long prunedCount(){return c.pruned();} public long legalUniverseSize(){return c.universe();}
		public FakeScore incumbentScore(){return new FakeScore(c.incumbent());} public FakeScore finalUpperBound(){return new FakeScore(c.upperBound());}
		public List<FakeBound> boundComponents(){List<FakeBound> x=c.bounds().stream().map(FakeBound::new).toList();return mutable?new ArrayList<>(x):x;}
		public int graphNodeCount(){return c.graphNodes();} public int graphConstraintCount(){return c.graphConstraints();} public int graphComponentCount(){return c.graphComponents();}
		public String boundDerivation(){return c.boundDerivation();} public String terminationReason(){return c.termination();} public boolean fallbackUsed(){return c.fallbackUsed();} }
}
