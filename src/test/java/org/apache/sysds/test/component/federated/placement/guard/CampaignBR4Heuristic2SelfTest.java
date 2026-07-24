/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.List;
import java.util.Set;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.junit.Assert;
import org.junit.Test;

/** Adapter-independent literal, uniqueness, fresh/repeat, and typed-legality gates. */
public class CampaignBR4Heuristic2SelfTest {
	@Test public void literalFixturesAreFreshRepeatExactAndDeeplyImmutable()throws Exception{
		for(String id:CampaignBProvenanceFixtureBridge.ids()){
			var a=CampaignBProvenanceFixtureBridge.fresh(id);var b=CampaignBProvenanceFixtureBridge.fresh(id);
			Assert.assertEquals(R4Heuristic2LiteralExpectations.exact(id),CampaignBProvenanceFixtureBridge.literalDescription(a));
			Assert.assertFalse("non-vacuous exclusions "+id,a.removedCandidates().isEmpty());Assert.assertEquals(a.removedCandidates(),a.candidateProofs().keySet());
			for(var p:a.candidateProofs().values()){
				var node=a.analysis().graph().node(p.provenNode()).orElseThrow();
				Assert.assertTrue("graph-aware CandidateProof "+p.candidate(),CampaignBProvenanceFixtureBridge.valid(a.analysis(),node,a.markerKey(),p));
			}
			Assert.assertEquals(a.structuralDigest(),b.structuralDigest());Assert.assertEquals(a.roles(),b.roles());Assert.assertEquals(a.descendants(),b.descendants());Assert.assertEquals(a.unrelated(),b.unrelated());
			R4Heuristic2Probe.immutable(a.roles());R4Heuristic2Probe.immutable(a.anchors());R4Heuristic2Probe.immutable(a.baseAlternatives());R4Heuristic2Probe.immutable(a.candidateProofs());R4Heuristic2Probe.immutable(a.removedCandidates());R4Heuristic2Probe.immutable(a.descendants());R4Heuristic2Probe.immutable(a.unrelated());R4Heuristic2Probe.immutable(a.analysis().graph().nodes());
			for(var p:a.candidateProofs().values()){R4Heuristic2Probe.immutable(p.anchor().partitions());R4Heuristic2Probe.immutable(p.relocation().compatibleConsumers());}
			for(var states:a.baseAlternatives().values())R4Heuristic2Probe.immutable(states);for(var n:a.analysis().graph().nodes()){R4Heuristic2Probe.immutable(n.legalAlternatives());R4Heuristic2Probe.immutable(n.anchors());}
		}
	}

	@Test public void durableAnchorIdentityMayRecurAcrossExactATWriteTReadOccurrences()throws Exception{
		var h3=CampaignBProvenanceFixtureBridge.fresh("H-03-LOOP-RECOMPILE");
		Assert.assertFalse("H-03 candidate proofs are non-vacuous",h3.candidateProofs().isEmpty());
		var anchor=h3.candidateProofs().values().iterator().next().anchor();
		var owners=h3.analysis().graph().nodes().stream().filter(n->n.anchors().stream().anyMatch(anchor::equals))
			.map(n->n.valueVersion().lexicalVariable()+":"+n.kind()).collect(java.util.stream.Collectors.toList());
		Assert.assertEquals("A source, TWrite A, and TRead A share the same durable anchor value",3,owners.size());
		Assert.assertTrue("A source owner present",owners.stream().anyMatch(x->x.equals("A:OPERATION")));
		Assert.assertTrue("TWrite A owner present",owners.stream().anyMatch(x->x.equals("A:TRANSIENT_WRITE")));
		Assert.assertTrue("TRead A owner present",owners.stream().anyMatch(x->x.equals("A:TRANSIENT_READ")));
		for(var p:h3.candidateProofs().values()){
			var node=h3.analysis().graph().node(p.provenNode()).orElseThrow();
			Assert.assertTrue("recurring value-equal policy anchor remains valid for "+p.candidate(),
				CampaignBProvenanceFixtureBridge.valid(h3.analysis(),node,h3.markerKey(),p));
		}
	}

	@Test public void h08StructuralDigestIsRepeatStableWithExactRawRelocationAfterAnchorGate()throws Exception{
		var a=CampaignBProvenanceFixtureBridge.fresh("H-08-LATER-ANCHOR-NO-REFED");
		var b=CampaignBProvenanceFixtureBridge.fresh("H-08-LATER-ANCHOR-NO-REFED");
		Assert.assertEquals(a.structuralDigest(),b.structuralDigest());
		Assert.assertEquals(CampaignBProvenanceFixtureBridge.literalDescription(a),CampaignBProvenanceFixtureBridge.literalDescription(b));
		Assert.assertEquals("H-08 Y remains anchorless but local Z has one exact raw no-refed relocation",1,
			a.removedRelocations().size());
		String relocation = a.removedRelocations().iterator().next();
		Assert.assertTrue(relocation.contains("BinaryOp:b(+):Y"));
		Assert.assertTrue(relocation.contains("FED/FOUT/ROW/SHAPE_DEPENDENT"));
	}

	@Test public void positionalAmbiguityIsRejected(){try{CampaignBProvenanceFixtureBridge.requireUnique("H-X","ROLE",List.of(1,2));Assert.fail();}catch(AssertionError e){Assert.assertTrue(String.valueOf(e.getMessage()).contains("FIXTURE_ROLE_AMBIGUOUS"));}}

	@Test public void independentAndEqualShapeAnchorsRemainProvenanceScoped()throws Exception{
		var h9=CampaignBProvenanceFixtureBridge.fresh("H-09-INDEPENDENT-ANCHOR-RELEASE");String y=h9.roles().get("Y_INDEPENDENT").normalizedSignature();Assert.assertTrue(h9.baseAlternatives().get(y).stream().anyMatch(s->s.contains("FED/FOUT")));Assert.assertTrue(h9.removedCandidates().stream().noneMatch(s->s.startsWith(y+'=')));
		var h10=CampaignBProvenanceFixtureBridge.fresh("H-10-SAME-SHAPE-DISTINCT-ANCHORS");String a=h10.anchors().get("ANCHOR_A").normalizedSignature(),b=h10.anchors().get("ANCHOR_B").normalizedSignature(),bNode=h10.roles().get("ANCHOR_B_NODE").normalizedSignature();Assert.assertNotEquals(a,b);Assert.assertEquals(Set.of(a),h10.candidateProofs().values().stream().map(p->p.anchor().normalizedSignature()).collect(java.util.stream.Collectors.toSet()));Assert.assertTrue(h10.baseAlternatives().get(bNode).stream().anyMatch(s->s.contains("FED/FOUT")));Assert.assertTrue(h10.removedCandidates().stream().noneMatch(s->s.startsWith(bNode+'=')));
	}

	@Test public void missingVarSelfPartUnknownShapeAndUnknownFunctionInputAreRejected()throws Exception{
		var f=CampaignBProvenanceFixtureBridge.fresh("H-01-TWRITE-TREAD");var good=f.candidateProofs().values().iterator().next();var node=f.analysis().graph().node(good.provenNode()).orElseThrow();
		Assert.assertTrue(CampaignBProvenanceFixtureBridge.candidateProof(f.analysis(),node,null,f.markerKey()).isEmpty());
		Assert.assertTrue(CampaignBProvenanceFixtureBridge.candidateProof(f.analysis(),node,new DurableAnchorKey("var:X",FType.ROW,good.anchor().partitions()),f.markerKey()).isEmpty());
		Assert.assertTrue(CampaignBProvenanceFixtureBridge.candidateProof(f.analysis(),node,new DurableAnchorKey(node.valueVersion().lexicalVariable(),FType.ROW,good.anchor().partitions()),f.markerKey()).isEmpty());
		Assert.assertTrue(CampaignBProvenanceFixtureBridge.candidateProof(f.analysis(),node,new DurableAnchorKey("fed-part",FType.PART,good.anchor().partitions()),f.markerKey()).isEmpty());
		Assert.assertTrue("real compiler-unknown matrix metadata is rejected without mutation",CampaignBProvenanceFixtureBridge.unknownShapeFixtureIsRejected());
		var h4=CampaignBProvenanceFixtureBridge.fresh("H-04-FUNCTION-CALLSITE");var input=h4.analysis().graph().node(h4.roles().get("FUNCTION_INPUT")).orElseThrow();Assert.assertTrue(CampaignBProvenanceFixtureBridge.candidateProof(h4.analysis(),input,h4.candidateProofs().values().iterator().next().anchor(),h4.markerKey()).isEmpty());
	}

	@Test public void everyCandidateProofRelationHasAFieldSpecificNegativeControl()throws Exception{
		var f=CampaignBProvenanceFixtureBridge.fresh("H-01-TWRITE-TREAD");var g=f.candidateProofs().values().iterator().next();var n=f.analysis().graph().node(g.provenNode()).orElseThrow();var other=f.analysis().graph().nodes().stream().filter(x->!x.key().equals(n.key())).findFirst().orElseThrow();
		java.util.function.Consumer<CampaignBProvenanceFixtureBridge.CandidateProof> rejects=p->Assert.assertFalse(CampaignBProvenanceFixtureBridge.valid(f.analysis(),n,f.markerKey(),p));
		rejects.accept(copy(g,new CampaignBProvenanceFixtureBridge.CandidateAtom(other.key(),g.state()),g.provenNode(),g.state(),g.anchor(),g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),g.obligation(),g.provenanceMarker(),g.provenanceValue()));
		rejects.accept(copy(g,g.atom(),other.key(),g.state(),g.anchor(),g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),g.obligation(),g.provenanceMarker(),g.provenanceValue()));
		var wrongState=new org.apache.sysds.hops.fedplanner.placement.PlacementState(org.apache.sysds.common.Types.ExecType.CP,org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT,null,false);
		rejects.accept(copy(g,g.atom(),g.provenNode(),wrongState,g.anchor(),g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),g.obligation(),g.provenanceMarker(),g.provenanceValue()));
		rejects.accept(copy(g,g.atom(),g.provenNode(),g.state(),g.anchor(),g.rows()+1,g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),g.obligation(),g.provenanceMarker(),g.provenanceValue()));
		rejects.accept(copy(g,g.atom(),g.provenNode(),g.state(),g.anchor(),g.rows(),g.cols(),CampaignBProvenanceFixtureBridge.ShapeBasis.UNTRUSTED_SHAPE_INDEPENDENT_LABEL,g.relocationKind(),g.relocation(),g.obligation(),g.provenanceMarker(),g.provenanceValue()));
		rejects.accept(copy(g,g.atom(),g.provenNode(),g.state(),g.anchor(),g.rows(),g.cols(),g.shapeBasis(),CampaignBProvenanceFixtureBridge.RelocationKind.DOWNLOAD_UNSUPPORTED,g.relocation(),g.obligation(),g.provenanceMarker(),g.provenanceValue()));
		var badGeometry=new DurableAnchorKey("bad-geometry",FType.ROW,List.of(new AnchorPartition("w1",List.of(0L,0L),List.of(1L,g.cols())),new AnchorPartition("w2",List.of(2L,0L),List.of(g.rows(),g.cols()))));
		rejects.accept(copy(g,g.atom(),g.provenNode(),g.state(),badGeometry,g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),g.obligation(),g.provenanceMarker(),g.provenanceValue()));
		var externalAnchor=new DurableAnchorKey("external-same-geometry",g.anchor().fType(),g.anchor().partitions());rejects.accept(copy(g,g.atom(),g.provenNode(),g.state(),externalAnchor,g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),g.obligation(),g.provenanceMarker(),g.provenanceValue()));
		var wrongSource=new RelocationActionKey(other.valueVersion(),g.state(),g.anchor(),n.key().controlRegion().normalizedSignature(),List.of(n.key()));var wrongSourceOb=new ObligationKey(n.key(),0,other.valueVersion(),g.state(),wrongSource,n.key().recompileContext());
		rejects.accept(copy(g,g.atom(),g.provenNode(),g.state(),g.anchor(),g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),wrongSource,wrongSourceOb,g.provenanceMarker(),g.provenanceValue()));
		var wrongConsumerReloc=new RelocationActionKey(n.valueVersion(),g.state(),g.anchor(),n.key().controlRegion().normalizedSignature(),List.of(other.key()));var wrongConsumer=new ObligationKey(other.key(),0,n.valueVersion(),g.state(),wrongConsumerReloc,n.key().recompileContext());
		rejects.accept(copy(g,g.atom(),g.provenNode(),g.state(),g.anchor(),g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),wrongConsumerReloc,wrongConsumer,g.provenanceMarker(),g.provenanceValue()));
		var wrongInput=new ObligationKey(n.key(),1,n.valueVersion(),g.state(),g.relocation(),n.key().recompileContext());rejects.accept(copy(g,g.atom(),g.provenNode(),g.state(),g.anchor(),g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),wrongInput,g.provenanceMarker(),g.provenanceValue()));
		var wrongContext=new ObligationKey(n.key(),0,n.valueVersion(),g.state(),g.relocation(),"wrong-context");rejects.accept(copy(g,g.atom(),g.provenNode(),g.state(),g.anchor(),g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),wrongContext,g.provenanceMarker(),g.provenanceValue()));
		rejects.accept(new CampaignBProvenanceFixtureBridge.CandidateProof(g.atom(),g.provenNode(),g.state(),g.anchor(),FType.COL,g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),g.obligation(),g.obligationReason(),g.provenanceMarker(),g.provenanceValue()));
		rejects.accept(new CampaignBProvenanceFixtureBridge.CandidateProof(g.atom(),g.provenNode(),g.state(),g.anchor(),g.supportedFType(),g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),g.obligation(),CampaignBProvenanceFixtureBridge.ObligationReason.UNTRUSTED_SHAPE_LABEL,g.provenanceMarker(),g.provenanceValue()));
		rejects.accept(copy(g,g.atom(),g.provenNode(),g.state(),g.anchor(),g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),g.obligation(),other.key(),g.provenanceValue()));
		rejects.accept(copy(g,g.atom(),g.provenNode(),g.state(),g.anchor(),g.rows(),g.cols(),g.shapeBasis(),g.relocationKind(),g.relocation(),g.obligation(),g.provenanceMarker(),other.valueVersion()));
	}

	private static CampaignBProvenanceFixtureBridge.CandidateProof copy(CampaignBProvenanceFixtureBridge.CandidateProof g,CampaignBProvenanceFixtureBridge.CandidateAtom atom,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey node,org.apache.sysds.hops.fedplanner.placement.PlacementState state,
		DurableAnchorKey anchor,long rows,long cols,CampaignBProvenanceFixtureBridge.ShapeBasis shape,CampaignBProvenanceFixtureBridge.RelocationKind relocationKind,RelocationActionKey relocation,ObligationKey obligation,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey marker,org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey value){
		return new CampaignBProvenanceFixtureBridge.CandidateProof(atom,node,state,anchor,g.supportedFType(),rows,cols,shape,relocationKind,relocation,obligation,g.obligationReason(),marker,value);
	}
}
