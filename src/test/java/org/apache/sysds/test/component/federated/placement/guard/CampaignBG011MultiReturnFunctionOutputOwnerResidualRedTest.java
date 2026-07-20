/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.junit.Assert;
import org.junit.Test;

/** RED contract for moving multi-return output-source selection to its FunctionOp owner. */
public class CampaignBG011MultiReturnFunctionOutputOwnerResidualRedTest {
	private static final String METHOD = "getPreferredMultiReturnFunctionOutputSourceForTransientRead";
	private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
	private static final Path FUNCTION_OP = ROOT.resolve("src/main/java/org/apache/sysds/hops/FunctionOp.java");
	private static final Path PLANNER_UTILS = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerUtils.java");
	private static final Path DP = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java");
	private static final Path MIN_ST = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCostEstimator.java");
	private static final Path FED_PLANNER = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner");

	@Test
	public void selectorIsOwnedByFunctionOpAndAllPlannerCallersUseItDirectly() throws Exception {
		String functionOp = compact(Files.readString(FUNCTION_OP));
		String plannerUtils = compact(Files.readString(PLANNER_UTILS));
		String dp = compact(Files.readString(DP));
		String minST = compact(Files.readString(MIN_ST));
		String declaration = "public static Hop " + METHOD
			+ "(DataOp transientRead, List<Hop> sourceHops)";
		String ownerCall = "FunctionOp." + METHOD + "(";
		String legacyCall = "FederatedPlannerUtils." + METHOD + "(";
		Pattern wrapper = Pattern.compile("(?:public|protected|private)?\\s*static\\s+Hop\\s+" + METHOD
			+ "\\s*\\(");
		List<String> failures = new ArrayList<>();

		if(!functionOp.contains(declaration))
			failures.add("FunctionOp.ownerDeclarationMissing");
		if(plannerUtils.contains(declaration))
			failures.add("FederatedPlannerUtils.legacyDeclarationRemains");
		if(!dp.contains(ownerCall) || dp.contains(legacyCall))
			failures.add("DP.mustCallFunctionOpDirectly");
		if(!minST.contains(ownerCall) || minST.contains(legacyCall))
			failures.add("MinST.mustCallFunctionOpDirectly");
		try(var sources = Files.walk(FED_PLANNER)) {
			List<String> wrappers = sources.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> {
					try {
						return wrapper.matcher(Files.readString(path)).find();
					}
					catch(Exception ex) {
						throw new IllegalStateException(ex);
					}
				})
				.map(ROOT::relativize)
				.map(Path::toString)
				.sorted()
				.toList();
			if(!wrappers.isEmpty())
				failures.add("fedplannerWrappers=" + wrappers);
		}

		Assert.assertEquals("G011_MULTIRETURN_SOURCE_OWNER_MUST_BE_FUNCTIONOP", List.of(), failures);
	}

	@Test
	public void invalidInputsReturnNull() throws Exception {
		DataOp read = transientRead("target", 10, 20);
		DataOp write = new DataOp("target", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTWRITE, "target", 10, 20, -1, 1024);
		Hop eligible = eligibleOutput("target", 10, 20);

		Assert.assertNull(select(null, List.of(eligible)));
		Assert.assertNull(select(write, List.of(eligible)));
		Assert.assertNull(select(read, null));
		Assert.assertNull(select(read, List.of()));
	}

	@Test
	public void onlyOwnedMultiReturnFunctionOutputsAreEligible() throws Exception {
		DataOp read = transientRead("target", 10, 20);
		DataOp ordinary = transientRead("target", 10, 20);
		DataOp parentless = functionOutput("target", 10, 20, transientRead("parentlessInput", 10, 20));
		DataOp dmlOutput = ownedOutput("target", 10, 20, FunctionType.DML, true);
		DataOp unlisted = ownedOutput("target", 10, 20, FunctionType.MULTIRETURN_BUILTIN, false);
		Hop eligible = eligibleOutput("target", 10, 20);

		Assert.assertSame(eligible, select(read,
			list(null, ordinary, parentless, dmlOutput, unlisted, eligible)));
	}

	@Test
	public void returnedCandidatePreservesOriginalIdentity() throws Exception {
		Hop eligible = eligibleOutput("target", 10, 20);
		Hop selected = select(transientRead("target", 10, 20), List.of(eligible));

		Assert.assertSame(eligible, selected);
	}

	@Test
	public void firstEligibleFallbackPreservesCallerOrder() throws Exception {
		Hop first = eligibleOutput("first", 1, 2);
		Hop second = eligibleOutput("second", 3, 4);
		DataOp read = transientRead("target", 10, 20);

		Assert.assertSame(first, select(read, List.of(first, second)));
		Assert.assertSame(second, select(read, List.of(second, first)));
	}

	@Test
	public void exactNameAndDimensionsOutrankEveryFallbackTier() throws Exception {
		Hop firstEligible = eligibleOutput("first", 1, 2);
		Hop dimensions = eligibleOutput("dimensions", 10, 20);
		Hop name = eligibleOutput("target", 30, 40);
		Hop exact = eligibleOutput("target", 10, 20);

		Assert.assertSame(exact, select(transientRead("target", 10, 20),
			List.of(firstEligible, dimensions, name, exact)));
	}

	@Test
	public void nameThenDimensionsThenFirstEligibleDefineFallbackPriority() throws Exception {
		Hop firstEligible = eligibleOutput("first", 1, 2);
		Hop dimensions = eligibleOutput("dimensions", 10, 20);
		Hop name = eligibleOutput("target", 30, 40);
		DataOp read = transientRead("target", 10, 20);

		Assert.assertSame(name, select(read, List.of(firstEligible, dimensions, name)));
		Assert.assertSame(dimensions, select(read, List.of(firstEligible, dimensions)));
		Assert.assertSame(firstEligible, select(read, List.of(firstEligible)));
	}

	private static Hop select(DataOp transientRead, List<Hop> sourceHops) throws Exception {
		Method selector;
		try {
			selector = FunctionOp.class.getMethod(METHOD, DataOp.class, List.class);
		}
		catch(NoSuchMethodException ex) {
			selector = FederatedPlannerUtils.class.getMethod(METHOD, DataOp.class, List.class);
		}
		return (Hop) selector.invoke(null, transientRead, sourceHops);
	}

	private static DataOp transientRead(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name, rows, cols, -1, 1024);
	}

	private static Hop eligibleOutput(String name, long rows, long cols) {
		return ownedOutput(name, rows, cols, FunctionType.MULTIRETURN_BUILTIN, true);
	}

	private static DataOp ownedOutput(String name, long rows, long cols, FunctionType type, boolean listed) {
		DataOp input = transientRead(name + "_input", rows, cols);
		DataOp output = functionOutput(name, rows, cols, input);
		DataOp listedOutput = listed ? output : functionOutput(name + "_listed", rows, cols, input);
		new FunctionOp(type, ".builtinNS", "test_multi_return", new String[] {"X"}, List.of(input),
			new String[] {listedOutput.getName()}, new ArrayList<>(List.of(listedOutput)));
		return output;
	}

	private static DataOp functionOutput(String name, long rows, long cols, Hop input) {
		DataOp output = new DataOp(name, DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, null);
		output.setDim1(rows);
		output.setDim2(cols);
		return output;
	}

	@SafeVarargs
	private static <T> List<T> list(T... values) {
		ArrayList<T> result = new ArrayList<>();
		for(T value : values)
			result.add(value);
		return result;
	}

	private static String compact(String source) {
		return source.replaceAll("\\s+", " ");
	}
}
