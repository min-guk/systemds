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

package org.apache.sysds.test.component.federated.placement.oracle.builder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Builder;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.ConstraintKind;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.FType;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Graph;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Kind;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Placement;

/** Declarative B-01..B-22 corpus for the independent builder oracle. */
public final class BuilderOracleFixtures {
	private static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(
		"B-01", "B-02", "B-03", "B-04", "B-05", "B-06", "B-07", "B-08", "B-09", "B-10", "B-11",
		"B-12", "B-13", "B-14", "B-15", "B-16", "B-17", "B-18", "B-19", "B-20", "B-21", "B-22"));
	private static final Placement CP = Placement.cpLout();
	private static final Placement CPF = Placement.cpFout(FType.ROW);
	private static final Placement FL = Placement.fedLout(FType.ROW);
	private static final Placement FLS = Placement.fedLoutShapeIndependent(FType.ROW);
	private static final Placement FF = Placement.fedFout(FType.ROW);
	private static final Placement FB = Placement.fedFout(FType.BROADCAST);

	public static List<String> ids() { return IDS; }

	public static Graph fixture(String id) {
		switch (id) {
			case "B-01": return sequentialWrites();
			case "B-02": return equalBranchWrites();
			case "B-03": return divergentBranchWrites();
			case "B-04": return constantBranch();
			case "B-05": return longLoopClosure();
			case "B-06": return localOverwrite();
			case "B-07": return functionIo();
			case "B-08": return deadFunctionOutput();
			case "B-09": return recompileClone();
			case "B-10": return sharedDag();
			case "B-11": return anchoredUpload();
			case "B-12": return missingAnchor();
			case "B-13": return unsupportedAnchor();
			case "B-14": return privateInput();
			case "B-15": return unsupportedShape();
			case "B-16": return transientLegality();
			case "B-17": return conflictingCallSites();
			case "B-18": return loopPhi();
			case "B-19": return nonConstantBranch();
			case "B-20": return nestedBranchLoop();
			case "B-21": return unknownMetadata();
			case "B-22": return sharedMaterialization();
			default: throw new IllegalArgumentException("Unknown builder fixture " + id);
		}
	}

	private static Graph sequentialWrites() {
		return new Builder().node("write@10", Kind.TWRITE, CP, FF).identity("write@10", "X#1", "write@10", "main")
			.node("write@20", Kind.TWRITE, CP, FF).identity("write@20", "X#2", "write@20", "main")
			.node("read@30", Kind.TREAD, CP, FF).identity("read@30", "X#2", "read@30", "main")
			.constraint(ConstraintKind.DOMINATES, "write@10", "write@20")
			.constraint(ConstraintKind.DOMINATES, "write@20", "read@30").build();
	}

	private static Graph equalBranchWrites() {
		return new Builder().node("then", Kind.TWRITE, FF).node("else", Kind.TWRITE, FF)
			.node("join", Kind.JOIN, CP, FF).constraint(ConstraintKind.SAME_PLACEMENT, "then", "join")
			.constraint(ConstraintKind.SAME_PLACEMENT, "else", "join").build();
	}

	private static Graph divergentBranchWrites() {
		return new Builder().node("then", Kind.TWRITE, FF).node("else", Kind.TWRITE, CP)
			.node("join", Kind.JOIN, CP, FF).constraint(ConstraintKind.CONJUNCTIVE, "then", "join")
			.constraint(ConstraintKind.CONJUNCTIVE, "else", "join").build();
	}

	static Graph divergentBranchWritesReversed() {
		return new Builder().node("else", Kind.TWRITE, CP).node("then", Kind.TWRITE, FF)
			.node("join", Kind.JOIN, CP, FF).constraint(ConstraintKind.CONJUNCTIVE, "else", "join")
			.constraint(ConstraintKind.CONJUNCTIVE, "then", "join").build();
	}

	private static Graph constantBranch() {
		return new Builder().node("taken", Kind.OP, CP, FF).node("untaken", Kind.OP, CP, FF)
			.reachable("untaken", false).node("join", Kind.JOIN, CP, FF)
			.constraint(ConstraintKind.CONJUNCTIVE, "taken", "join").build();
	}

	private static Graph longLoopClosure() {
		Builder b = new Builder();
		for (int i = 0; i < 7; i++) b.node("phi" + i, Kind.PHI, i == 6 ? FF : CP, FF);
		for (int i = 0; i < 6; i++) b.constraint(ConstraintKind.SAME_PLACEMENT, "phi" + i, "phi" + (i + 1));
		return b.build();
	}

	private static Graph localOverwrite() {
		return new Builder().node("entry", Kind.PHI, CP, FF).node("overwrite", Kind.TWRITE, CP)
			.node("backedge", Kind.PHI, CP, FF).constraint(ConstraintKind.SAME_PLACEMENT, "overwrite", "backedge")
			.constraint(ConstraintKind.SAME_PLACEMENT, "backedge", "entry").build();
	}

	private static Graph functionIo() {
		return new Builder().node("call#1", Kind.CALL, CP, FF).identity("call#1", "call#1", "f", "site#1")
			.node("f.in", Kind.FUNCTION_INPUT, CP, FF).identity("f.in", "arg#1", "f.in", "site#1")
			.node("f.read", Kind.TREAD, CP, FF).identity("f.read", "arg#1", "f.read", "site#1")
			.node("f.write", Kind.TWRITE, CP, FF).identity("f.write", "ret#1", "f.write", "site#1")
			.node("f.out", Kind.FUNCTION_OUTPUT, CP, FF).identity("f.out", "ret#1", "f.out", "site#1")
			.constraint(ConstraintKind.CONJUNCTIVE, "call#1", "f.in").build();
	}

	private static Graph deadFunctionOutput() {
		return new Builder().node("analysis-root", Kind.ROOT, CP).emitted("analysis-root", false)
			.node("dead-output", Kind.FUNCTION_OUTPUT, CP, FF).emitted("dead-output", false)
			.constraint(ConstraintKind.CONJUNCTIVE, "analysis-root", "dead-output").build();
	}

	private static Graph recompileClone() {
		return new Builder().node("origin", Kind.OP, CP, CPF, FF).anchor("origin", "fed:X", FType.ROW)
			.node("clone", Kind.CLONE, CP, CPF, FF).identity("clone", "v#1", "origin", "recompile#1")
			.anchor("clone", "fed:X", FType.ROW).recompile("clone").build();
	}

	private static Graph sharedDag() {
		return new Builder().node("shared", Kind.OP, CP, FF).node("root-a", Kind.ROOT, CP, FF)
			.node("root-b", Kind.ROOT, CP, FF).constraint(ConstraintKind.CONJUNCTIVE, "shared", "root-a")
			.constraint(ConstraintKind.CONJUNCTIVE, "shared", "root-b").build();
	}

	private static Graph anchoredUpload() {
		return new Builder().node("upload", Kind.OP, CP, CPF).anchor("upload", "fed:X", FType.ROW)
			.relocation("mat:upload:fed:X", "upload", "fed:X", "consumer#1").build();
	}

	private static Graph missingAnchor() { return new Builder().node("upload", Kind.OP, CP, CPF).build(); }
	private static Graph unsupportedAnchor() {
		return new Builder().node("upload", Kind.OP, CP, CPF).anchor("upload", "fed:X", FType.PART).build();
	}

	private static Graph privateInput() { return new Builder().node("private", Kind.OP, CP, FF).privacy("private").build(); }
	private static Graph unsupportedShape() {
		return new Builder().node("unsupported", Kind.OP, CP, FL, FF).unsupportedShape("unsupported").build();
	}

	private static Graph transientLegality() {
		return new Builder().node("read", Kind.TREAD, CP, CPF, FL, FF)
			.node("write", Kind.TWRITE, CP, CPF, FL, FF).build();
	}

	private static Graph conflictingCallSites() {
		return new Builder().node("call#1", Kind.CALL, FF).identity("call#1", "arg#1", "f", "site#1")
			.node("call#2", Kind.CALL, CP).identity("call#2", "arg#2", "f", "site#2")
			.node("f.body", Kind.OP, CP, FF).identity("f.body", "body", "f.body", "shared-body")
			.constraint(ConstraintKind.DISTINCT_CONTEXT, "call#1", "call#2")
			.constraint(ConstraintKind.CONJUNCTIVE, "call#1", "f.body")
			.constraint(ConstraintKind.CONJUNCTIVE, "call#2", "f.body").build();
	}

	private static Graph loopPhi() {
		return new Builder().node("local-entry", Kind.OP, CP).node("fed-backedge", Kind.OP, FF)
			.node("phi-local-fed", Kind.PHI, CP, FF)
			.node("fed-entry", Kind.OP, FF).node("local-backedge", Kind.OP, CP)
			.node("phi-fed-local", Kind.PHI, CP, FF)
			.constraint(ConstraintKind.CONJUNCTIVE, "local-entry", "phi-local-fed")
			.constraint(ConstraintKind.CONJUNCTIVE, "fed-backedge", "phi-local-fed")
			.constraint(ConstraintKind.CONJUNCTIVE, "fed-entry", "phi-fed-local")
			.constraint(ConstraintKind.CONJUNCTIVE, "local-backedge", "phi-fed-local").build();
	}

	private static Graph nonConstantBranch() {
		return new Builder().node("fed-path", Kind.OP, FF).node("local-path", Kind.OP, CP)
			.node("join", Kind.JOIN, CP, FF).constraint(ConstraintKind.CONJUNCTIVE, "fed-path", "join")
			.constraint(ConstraintKind.CONJUNCTIVE, "local-path", "join").build();
	}

	private static Graph nestedBranchLoop() {
		return new Builder().node("loop-entry", Kind.PHI, CP, FF).node("then", Kind.OP, FF)
			.node("else", Kind.OP, CP).node("branch-join", Kind.JOIN, CP, FF)
			.node("backedge", Kind.PHI, CP, FF)
			.constraint(ConstraintKind.CONJUNCTIVE, "then", "branch-join")
			.constraint(ConstraintKind.CONJUNCTIVE, "else", "branch-join")
			.constraint(ConstraintKind.CONJUNCTIVE, "branch-join", "backedge")
			.constraint(ConstraintKind.CONJUNCTIVE, "backedge", "loop-entry").build();
	}

	private static Graph unknownMetadata() {
		return new Builder().node("unknown", Kind.OP, CP, FLS, FF, FB).unknownMetadata("unknown").build();
	}

	private static Graph sharedMaterialization() {
		return new Builder().node("source", Kind.OP, CP, CPF).anchor("source", "fed:X", FType.ROW)
			.node("consumer#1", Kind.OP, FF).node("consumer#2", Kind.OP, FF)
			.relocation("mat:source:fed:X", "source", "fed:X", "consumer#1")
			.relocation("mat:source:fed:X", "source", "fed:X", "consumer#2").build();
	}

	private BuilderOracleFixtures() {
		// utility class
	}
}
