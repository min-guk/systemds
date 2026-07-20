/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** RED contract for the complete per-worker privacy acquisition and local-recovery owner. */
public class CampaignBG011PrivacyResolverOwnerContractTest {
	private static final String RESOLVER_CLASS =
		"org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver";
	private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
	private static final Path RESOLVER_SOURCE = ROOT.resolve(
		"src/main/java/org/apache/sysds/runtime/controlprogram/federated/FederatedPrivacyConstraintResolver.java");
	private static final Path PLANNER_UTILS = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerUtils.java");

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void completeWorkerStateMachineHasOneRuntimeOwnerAndPlannerDelegates() throws Exception {
		List<String> failures = new ArrayList<>();
		if(!Files.isRegularFile(RESOLVER_SOURCE))
			failures.add("resolverOwnerSourceMissing");
		else {
			String resolverSource = Files.readString(RESOLVER_SOURCE);
			if(!resolverSource.contains("public final class FederatedPrivacyConstraintResolver"))
				failures.add("resolverOwnerMustBeFinal");
			if(resolverSource.contains("hops.fedplanner") || resolverSource.contains("FTypes.Privacy"))
				failures.add("resolverMustRemainPlannerNeutral");
		}

		String plannerBody = JavaSourceBoundaryScanner.methodBody(
			Files.readString(PLANNER_UTILS), "getFedWorkerMetaData", "List<Pair<FederatedRange, FederatedData>> fedMap");
		List<JavaSourceTokenScanner.Token> plannerTokens = JavaSourceTokenScanner.tokens(plannerBody);
		int delegations = countSequence(plannerTokens,
			"FederatedPrivacyConstraintResolver", ".", "resolve", "(");
		if(delegations != 1)
			failures.add("resolverDelegations=" + delegations);
		for(String plannerOwnedMechanic : List.of("requestPrivacyConstraints", "Future", "tryLocalPrivacyFallback")) {
			int occurrences = countIdentifier(plannerTokens, plannerOwnedMechanic);
			if(occurrences != 0)
				failures.add("plannerStillOwns=" + plannerOwnedMechanic + ":" + occurrences);
		}

		Assert.assertEquals("G011_PRIVACY_RESOLVER_COMPLETE_OWNER_MISSING", List.of(), failures);
	}

	@Test
	public void plannerRetainsFatalMergeLoggingAndRegistersOnlyAfterResolution() throws Exception {
		String plannerBody = JavaSourceBoundaryScanner.methodBody(
			Files.readString(PLANNER_UTILS), "getFedWorkerMetaData", "List<Pair<FederatedRange, FederatedData>> fedMap");
		List<JavaSourceTokenScanner.Token> tokens = JavaSourceTokenScanner.tokens(plannerBody);
		List<String> failures = new ArrayList<>();

		if(countIdentifier(tokens, "mergePrivacyConstraint") == 0)
			failures.add("plannerPrivacyMergeMissing");
		if(countIdentifier(tokens, "FederatedPlannerLogger") == 0)
			failures.add("plannerContextLoggingMissing");
		int fatalCheck = sequence(tokens, 0,
			"if", "(", "privacyConstraint", "==", "null", "||", "hadPrivacyFailure", ")");
		int fatalLog = sequence(tokens, Math.max(0, fatalCheck),
			"FederatedPlannerLogger", ".", "logErrorMessage", "(");
		int fatalThrow = sequence(tokens, Math.max(0, fatalCheck),
			"throw", "new", "DMLRuntimeException", "(", "errorMsg", ")", ";");
		int register = sequence(tokens, Math.max(0, fatalCheck), "registerFedInitVar", "(");
		if(!(fatalCheck >= 0 && fatalLog > fatalCheck && fatalThrow > fatalLog && register > fatalThrow))
			failures.add("fatalLogThrowMustPrecedeRegistration");
		if(countIdentifier(tokens, "registerFedInitVar") != 1)
			failures.add("fedInitRegistrations=" + countIdentifier(tokens, "registerFedInitVar"));

		Assert.assertEquals("G011_PRIVACY_PLANNER_RETAINED_BOUNDARIES", List.of(), failures);
	}

	@Test
	public void plannerPrivacyMergePreservesStrongestValueOrdering() throws Exception {
		Assert.assertEquals(Privacy.PUBLIC, merge(null, "public"));
		Assert.assertEquals(Privacy.PRIVATE_AGGREGATE_TO_PUBLIC,
			merge(Privacy.PUBLIC, "private-aggregate-to-public"));
		Assert.assertEquals(Privacy.PRIVATE_AGGREGATE,
			merge(Privacy.PRIVATE_AGGREGATE_TO_PUBLIC, "private-aggregate"));
		Assert.assertEquals(Privacy.PRIVATE, merge(Privacy.PRIVATE_AGGREGATE, "private"));
		Assert.assertEquals(Privacy.PRIVATE, merge(Privacy.PRIVATE, "public"));
	}

	@Test
	public void coordinatorLocalMetadataWinsWithoutWorkerRequest() throws Exception {
		Path dataPath = workerDataPath("local-first");
		writePrivacyMetadata(dataPath, "private");
		FederatedData data = workerData(dataPath);

		Object result = resolve(data);

		assertResolution(result, "private", "LOCAL_PRE_REQUEST", "NONE", null, null);
		verify(data, never()).requestPrivacyConstraints();
	}

	@Test
	public void successfulWorkerResponsePreservesRawPrivacyAndResponse() throws Exception {
		Path dataPath = workerDataPath("worker-success");
		FederatedData data = workerData(dataPath);
		FederatedResponse response = new FederatedResponse(ResponseType.SUCCESS, "private-aggregate");
		when(data.requestPrivacyConstraints()).thenReturn(CompletableFuture.completedFuture(response));

		Object result = resolve(data);

		assertResolution(result, "private-aggregate", "WORKER_RESPONSE", "NONE", response, null);
		verify(data).requestPrivacyConstraints();
	}

	@Test
	public void nullWorkerResponseRecoversFromMetadataCreatedAfterRequest() throws Exception {
		Path dataPath = workerDataPath("null-response");
		FederatedData data = workerData(dataPath);
		when(data.requestPrivacyConstraints()).thenAnswer(invocation -> {
			writePrivacyMetadata(dataPath, "private");
			return CompletableFuture.completedFuture(null);
		});

		Object result = resolve(data);

		assertResolution(result, "private", "LOCAL_POST_REQUEST", "NULL_RESPONSE", null, null);
		verify(data).requestPrivacyConstraints();
	}

	@Test
	public void successfulNullPayloadRecoversAndRetainsResponse() throws Exception {
		Path dataPath = workerDataPath("null-payload");
		FederatedData data = workerData(dataPath);
		FederatedResponse response = new FederatedResponse(ResponseType.SUCCESS, (Object) null);
		when(data.requestPrivacyConstraints()).thenAnswer(invocation -> {
			writePrivacyMetadata(dataPath, "private");
			return CompletableFuture.completedFuture(response);
		});

		Object result = resolve(data);

		assertResolution(result, "private", "LOCAL_POST_REQUEST", "NULL_PAYLOAD", response, null);
		verify(data).requestPrivacyConstraints();
	}

	@Test
	public void unsuccessfulWorkerResponseRecoversAndRetainsResponse() throws Exception {
		Path dataPath = workerDataPath("error-response");
		FederatedData data = workerData(dataPath);
		FederatedResponse response = new FederatedResponse(ResponseType.ERROR, "worker-error");
		when(data.requestPrivacyConstraints()).thenAnswer(invocation -> {
			writePrivacyMetadata(dataPath, "private-aggregate");
			return CompletableFuture.completedFuture(response);
		});

		Object result = resolve(data);

		assertResolution(result, "private-aggregate", "LOCAL_POST_REQUEST", "ERROR_RESPONSE", response, null);
		verify(data).requestPrivacyConstraints();
	}

	@Test
	public void requestExceptionRecoversAndRetainsCause() throws Exception {
		Path dataPath = workerDataPath("request-exception");
		FederatedData data = workerData(dataPath);
		IllegalStateException failure = new IllegalStateException("request-failed");
		when(data.requestPrivacyConstraints()).thenAnswer(invocation -> {
			writePrivacyMetadata(dataPath, "private");
			return CompletableFuture.failedFuture(failure);
		});

		Object result = resolve(data);

		assertResolution(result, "private", "LOCAL_POST_REQUEST", "REQUEST_EXCEPTION", null, failure);
		verify(data).requestPrivacyConstraints();
	}

	@Test
	public void unresolvedWorkerResultsRetainFailureEvidenceWithoutLocalMetadata() throws Exception {
		FederatedData nullResponseData = workerData(workerDataPath("unresolved-null-response"));
		when(nullResponseData.requestPrivacyConstraints()).thenReturn(CompletableFuture.completedFuture(null));
		assertResolution(resolve(nullResponseData), null, "UNRESOLVED", "NULL_RESPONSE", null, null);

		FederatedData nullPayloadData = workerData(workerDataPath("unresolved-null-payload"));
		FederatedResponse nullPayload = new FederatedResponse(ResponseType.SUCCESS, (Object) null);
		when(nullPayloadData.requestPrivacyConstraints()).thenReturn(CompletableFuture.completedFuture(nullPayload));
		assertResolution(resolve(nullPayloadData), null, "UNRESOLVED", "NULL_PAYLOAD", nullPayload, null);

		FederatedData errorData = workerData(workerDataPath("unresolved-error"));
		FederatedResponse error = new FederatedResponse(ResponseType.ERROR, "worker-error");
		when(errorData.requestPrivacyConstraints()).thenReturn(CompletableFuture.completedFuture(error));
		assertResolution(resolve(errorData), null, "UNRESOLVED", "ERROR_RESPONSE", error, null);

		FederatedData exceptionData = workerData(workerDataPath("unresolved-exception"));
		IllegalStateException failure = new IllegalStateException("request-failed");
		when(exceptionData.requestPrivacyConstraints()).thenReturn(CompletableFuture.failedFuture(failure));
		assertResolution(resolve(exceptionData), null, "UNRESOLVED", "REQUEST_EXCEPTION", null, failure);

		verify(nullResponseData).requestPrivacyConstraints();
		verify(nullPayloadData).requestPrivacyConstraints();
		verify(errorData).requestPrivacyConstraints();
		verify(exceptionData).requestPrivacyConstraints();
	}

	private FederatedData workerData(Path dataPath) {
		FederatedData data = mock(FederatedData.class);
		when(data.getFilepath()).thenReturn(dataPath.toString());
		return data;
	}

	private Path workerDataPath(String name) {
		return temporaryFolder.getRoot().toPath().resolve(name);
	}

	private static void writePrivacyMetadata(Path dataPath, String privacy) throws Exception {
		Files.writeString(Path.of(dataPath + ".mtd"), "{\"privacy\":\"" + privacy + "\"}");
	}

	private static Object resolve(FederatedData data) throws Exception {
		Class<?> resolver = resolverClass();
		Method resolve = resolver.getMethod("resolve", FederatedData.class);
		Assert.assertTrue("resolver must expose one static per-worker entrypoint",
			Modifier.isPublic(resolve.getModifiers()) && Modifier.isStatic(resolve.getModifiers()));
		try {
			return resolve.invoke(null, data);
		}
		catch(InvocationTargetException ex) {
			Throwable cause = ex.getCause();
			if(cause instanceof Exception)
				throw (Exception) cause;
			throw ex;
		}
	}

	private static Class<?> resolverClass() throws Exception {
		Assume.assumeTrue("complete privacy resolver owner is not implemented yet",
			Files.isRegularFile(RESOLVER_SOURCE));
		return Class.forName(RESOLVER_CLASS);
	}

	private static void assertResolution(Object result, String privacy, String origin, String failure,
		FederatedResponse response, Throwable cause) throws Exception {
		Assert.assertNotNull(result);
		Assert.assertEquals(privacy, accessor(result, "privacyConstraint"));
		Assert.assertEquals(origin, String.valueOf(accessor(result, "origin")));
		Assert.assertEquals(failure, String.valueOf(accessor(result, "failure")));
		Assert.assertSame(response, accessor(result, "response"));
		Assert.assertSame(cause, accessor(result, "cause"));
	}

	private static Object accessor(Object target, String name) throws Exception {
		return target.getClass().getMethod(name).invoke(target);
	}

	private static Privacy merge(Privacy current, String rawPrivacy) throws Exception {
		Method merge = FederatedPlannerUtils.class.getDeclaredMethod(
			"mergePrivacyConstraint", Privacy.class, String.class);
		merge.setAccessible(true);
		return (Privacy) merge.invoke(null, current, rawPrivacy);
	}

	private static int countIdentifier(List<JavaSourceTokenScanner.Token> tokens, String identifier) {
		return (int) tokens.stream().filter(token -> token.text().equals(identifier)).count();
	}

	private static int countSequence(List<JavaSourceTokenScanner.Token> tokens, String... expected) {
		int count = 0;
		for(int from = 0;;) {
			int found = sequence(tokens, from, expected);
			if(found < 0)
				return count;
			count++;
			from = found + expected.length;
		}
	}

	private static int sequence(List<JavaSourceTokenScanner.Token> tokens, int from, String... expected) {
		outer: for(int i = Math.max(0, from); i + expected.length <= tokens.size(); i++) {
			for(int j = 0; j < expected.length; j++)
				if(!tokens.get(i + j).text().equals(expected[j]))
					continue outer;
			return i;
		}
		return -1;
	}
}
