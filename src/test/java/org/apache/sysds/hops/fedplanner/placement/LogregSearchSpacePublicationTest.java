/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import org.junit.Assert;
import org.junit.Test;

/** Compile-only regression: no worker or workload execution; not a performance measurement. */
public class LogregSearchSpacePublicationTest {
	@Test
	public void protectedBuiltinLogregPublishesCompleteLogicalRelations() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:18101/X\"),"
			+ "ranges=list(list(0,0),list(50000,128)));\n"
			+ "Y=federated(addresses=list(\"localhost:18101/Y\"),"
			+ "ranges=list(list(0,0),list(50000,1)));\n"
			+ "Y=(Y<0)+1;\n"
			+ "m=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,"
			+ "icpt=0,numclasses=2,numrows=50000,numcols=128);\n"
			+ "write(m,\"tmp/logreg-publication-test\",format=\"csv\");\n";
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(script));
		Assert.assertFalse(analysis.logicalTransientInputsInCanonicalOrder().isEmpty());
	}
}
