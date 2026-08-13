/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.rewrite;

import java.util.ArrayList;

import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ReorgOp;

/**
 * Expands physical algebraic choices that compiled federated planners must
 * price explicitly.  This rule runs after final memory-estimate refresh and
 * before immutable placement analysis is bound.
 */
public class RewriteFederatedPlannerPhysicalNormalization extends HopRewriteRule {
	@Override
	public ArrayList<Hop> rewriteHopDAGs(ArrayList<Hop> roots, ProgramRewriteStatus state) {
		if(roots == null)
			return null;
		for(Hop root : roots)
			normalizeChildren(root);
		Hop.resetVisitStatus(roots, true);
		return roots;
	}

	@Override
	public Hop rewriteHopDAG(Hop root, ProgramRewriteStatus state) {
		if(root == null)
			return null;
		normalizeChildren(root);
		root.resetVisitStatus();
		return root;
	}

	private static void normalizeChildren(Hop parent) {
		if(parent.isVisited())
			return;
		for(int inputPosition = 0; inputPosition < parent.getInput().size(); inputPosition++) {
			Hop child = parent.getInput(inputPosition);
			normalizeChildren(child);
			normalizeLeftTransposeMatrixMultiply(child);
		}
		parent.setVisited();
	}

	/**
	 * Makes the historical lowering rewrite {@code t(X)%*%Y ->
	 * t(t(Y)%*%X)} part of the HOP DAG.  Consequently every planner sees and
	 * costs the two small transposes and the direct local-left/FED-right
	 * multiply instead of an artificial full-matrix transpose boundary.
	 */
	private static void normalizeLeftTransposeMatrixMultiply(Hop child) {
		if(!(child instanceof AggBinaryOp aggregate) || !aggregate.isMatrixMultiply())
			return;

		Hop leftTranspose = child.getInput(0);
		if(!HopRewriteUtils.isTransposeOperation(leftTranspose))
			return;
		boolean applicable = aggregate.isLeftTransposeRewriteApplicableForPlannerNormalization();
		if(child.isPlannerPlacementSelected() || child.getParent().isEmpty() || !applicable)
			return;
		Hop x = leftTranspose.getInput(0);
		Hop y = child.getInput(1);
		ReorgOp transposeY = HopRewriteUtils.createTranspose(y);
		AggBinaryOp physicalMultiply = HopRewriteUtils.createMatrixMultiply(transposeY, x);
		ReorgOp resultTranspose = HopRewriteUtils.createTranspose(physicalMultiply);
		HopRewriteUtils.copyLineNumbers(child, transposeY);
		HopRewriteUtils.copyLineNumbers(child, physicalMultiply);
		HopRewriteUtils.copyLineNumbers(child, resultTranspose);
		// A single expression can feed multiple transient writes (for example both
		// g_old and s in L2SVM).  Rewire the shared result once rather than either
		// duplicating the physical operation or leaving one consumer on the old DAG.
		HopRewriteUtils.rewireAllParentChildReferences(child, resultTranspose);
		HopRewriteUtils.cleanupUnreferenced(child, leftTranspose);
		LOG.debug("Applied final pre-planner left-transpose matrix-multiply normalization (line "
			+ resultTranspose.getBeginLine() + ")");
	}
}
