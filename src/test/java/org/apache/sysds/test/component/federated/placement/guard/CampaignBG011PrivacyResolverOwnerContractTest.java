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
