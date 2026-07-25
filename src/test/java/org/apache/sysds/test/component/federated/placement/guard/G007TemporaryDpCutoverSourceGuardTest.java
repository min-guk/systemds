/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class G007TemporaryDpCutoverSourceGuardTest {
	private static final Path DP_PLANNER = Path.of("src/main/java/org/apache/sysds/hops/fedplanner/"
		+ "fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java");

	@Test
	public void dpProductionSourceHasNoTemporaryCutoverControlsButKeepsStrictCheaperBundleEvaluation()
		throws Exception {
		String source = sanitizeJava(Files.readString(DP_PLANNER));

		List<String> temporaryControls = Arrays.asList(
			"ENABLE_TRANSIENT_FOUT_BUNDLE_NEAR_TIE",
			"TRANSIENT_FOUT_BUNDLE_TIE_REL_TOL",
			"keepFoutOnNearFamilyTie",
			"ENABLE_LOCKED_TRANSIENT_READ_PROPAGATION",
			"ENABLE_FORCED_TRANSIENT_NEIGHBORHOOD_REEVAL",
			"forceTransientNeighborhoodReeval");
		for(String temporaryControl : temporaryControls)
			assertFalse("G007 temporary DP cutover control must be deleted: " + temporaryControl,
				source.contains(temporaryControl));

		assertTrue("G007 cleanup must keep contextually feasible transient-bundle evaluation",
			source.contains("collectContextuallyFeasibleTransientBundleHopIDs"));
		assertTrue("G007 cleanup must keep the active lower-cost bundle acceptance path",
			source.contains("bundleAltScore.totalCost + 1e-9 < altScore.totalCost"));
		assertTrue("G007 cleanup must keep strict lower-cost final acceptance",
			source.contains("candidateScore.totalCost + 1e-9 < currentScore.totalCost"));
	}

	private enum LexState {
		CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR
	}

	private static String sanitizeJava(String source) {
		StringBuilder result = new StringBuilder(source.length());
		LexState state = LexState.CODE;
		boolean escaped = false;
		for(int i = 0; i < source.length(); i++) {
			char current = source.charAt(i);
			char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
			switch(state) {
				case CODE:
					if(current == '/' && next == '/') {
						result.append("  ");
						i++;
						state = LexState.LINE_COMMENT;
					}
					else if(current == '/' && next == '*') {
						result.append("  ");
						i++;
						state = LexState.BLOCK_COMMENT;
					}
					else if(current == '"') {
						result.append(' ');
						state = LexState.STRING;
						escaped = false;
					}
					else if(current == '\'') {
						result.append(' ');
						state = LexState.CHAR;
						escaped = false;
					}
					else
						result.append(current);
					break;
				case LINE_COMMENT:
					result.append(current == '\n' ? '\n' : ' ');
					if(current == '\n')
						state = LexState.CODE;
					break;
				case BLOCK_COMMENT:
					if(current == '*' && next == '/') {
						result.append("  ");
						i++;
						state = LexState.CODE;
					}
					else
						result.append(current == '\n' ? '\n' : ' ');
					break;
				case STRING:
				case CHAR:
					result.append(current == '\n' ? '\n' : ' ');
					if(escaped)
						escaped = false;
					else if(current == '\\')
						escaped = true;
					else if((state == LexState.STRING && current == '"') || (state == LexState.CHAR && current == '\''))
						state = LexState.CODE;
					break;
				default:
					throw new IllegalStateException("Unhandled lexical state " + state);
			}
		}
		return result.toString();
	}
}
