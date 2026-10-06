/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** The normalized loop join must not erase native proof from a protected slice. */
public class LogregProtectedLoopSliceTest {
	private static final String SCRIPT = """
		f=function(matrix[double] X, matrix[double] Y, int numrows, int numclasses)
		 return (double z) {
		 N=numrows;
		 Y=cbind(Y,Y);
		 K=1;
		 P=Y*0+1;
		 P=P/(K+1);
		 B=matrix(0,rows=2,cols=K);
		 Grad=t(X)%*%(P[,1:K]-Y[,1:K]);
		 obj=sum(P);
		 outer=1;
		 while(outer<=2) {
		  P_1K=P[,1:K];
		  inner=1;
		  while(inner<=2) {
		   Q=P_1K*(X%*%matrix(1,rows=2,cols=1));
		   HV=t(X)%*%(Q-P_1K*(rowSums(Q)%*%matrix(1,rows=1,cols=1)));
		   inner=inner+1;
		  }
		  if(sum(HV)>0) {
		   B=B+matrix(1,rows=2,cols=K);
		   LT=cbind(X%*%B,matrix(0,rows=N,cols=1));
		   LT=LT-rowMaxs(LT)%*%matrix(1,rows=1,cols=2);
		   exp_LT=exp(LT);
		   P=exp_LT/(rowSums(exp_LT)%*%matrix(1,rows=1,cols=2));
		   Grad=t(X)%*%(P[,1:K]-Y[,1:K]);
		   obj=sum(P);
		  }
		  outer=outer+1;
		 }
		 z=sum(Q)+sum(Grad)+obj+sum(B);
		}
		X=federated(addresses=list("localhost:18101/X"),ranges=list(list(0,0),list(8,2)));
		Y=federated(addresses=list("localhost:18101/Y"),ranges=list(list(0,0),list(8,1)));
		N=as.integer(read("target/logreg-protected-loop-rows",data_type="scalar",value_type="int"));
		z=f(X,Y,N,2);
		print(z);
		""";

	@Test
	public void normalizedProtectedLoopSliceRetainsFullPlacement() throws Exception {
		assertProtectedLoopSlice(SCRIPT, FType.FULL);
	}

	@Test
	public void normalizedProtectedLoopSliceRetainsRowPlacement() throws Exception {
		String script = SCRIPT
			.replace("addresses=list(\"localhost:18101/X\"),ranges=list(list(0,0),list(8,2))",
				"addresses=list(\"localhost:18101/X\",\"localhost:18102/X\"),"
					+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2))")
			.replace("addresses=list(\"localhost:18101/Y\"),ranges=list(list(0,0),list(8,1))",
				"addresses=list(\"localhost:18101/Y\",\"localhost:18102/Y\"),"
					+ "ranges=list(list(0,0),list(4,1),list(4,0),list(8,1))");
		assertProtectedLoopSlice(script, FType.ROW);
	}

	private static void assertProtectedLoopSlice(String script, FType type) throws Exception {
		var program = NeutralPlacementFixedPointCompositionTest.compileProtected(script);
		BranchPlacementNormalization.prepare(program);
		Assert.assertTrue("fixture must include the production branch-exit placement identities",
			PlacementGraphFingerprint.orderedOccurrences(program).stream()
				.anyMatch(occurrence -> BranchPlacementNormalization.isPlacementAlias(occurrence.hop())));
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var slice = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof IndexingOp
				&& "P_1K".equals(occurrence.hop().getName()))
			.findFirst().orElseThrow(AssertionError::new);
		Assert.assertTrue("the slice needs executable worker authority, not an ungrounded native template",
			analysis.candidateRuleFacts().orderedFactsForParent(slice.key()).stream()
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.filter(emission -> emission.derivedFoutAction() == null)
				.flatMap(emission -> emission.realizations().stream())
				.anyMatch(realization -> realization.supportClauses().stream().anyMatch(clause ->
					realization.nativeWorkerPoolResidencyWitness(clause) != null
						&& !clause.inputBindings().isEmpty())));

		var reads = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "P_1K".equals(data.getName()))
			.toList();
		Assert.assertFalse("fixture must retain the inner-loop P_1K reads", reads.isEmpty());
		for(var read : reads) {
			Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(read.key()));
			var states = analysis.graph().node(read.key()).orElseThrow().legalAlternatives();
			Assert.assertFalse("protected transient domain must not be empty", states.isEmpty());
			Assert.assertTrue(states.stream().allMatch(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == type));
			var relations = analysis.logicalTransientInputsForReader(read.key(), 0);
			Assert.assertFalse("a coarse FED state alone is not a writer/read compatibility proof",
				relations.isEmpty());
			Assert.assertTrue(relations.stream().flatMap(relation -> relation.compatibility().stream())
				.anyMatch(edge -> edge.proof().nativeWorkerPoolWitness() != null));
		}
	}
}
