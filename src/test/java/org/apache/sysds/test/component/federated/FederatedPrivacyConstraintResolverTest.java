/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.component.federated;

import static org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.resolve;
import static org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Origin.LOCAL_PRE_REQUEST;
import static org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Origin.WORKER_RESPONSE;
import static org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Origin.LOCAL_POST_REQUEST;
import static org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Origin.UNRESOLVED;
import static org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Failure.NONE;
import static org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Failure.NULL_RESPONSE;
import static org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Failure.NULL_PAYLOAD;
import static org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Failure.ERROR_RESPONSE;
import static org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Failure.UNSUPPORTED_FINE_GRAINED_PRIVACY;
import static org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Failure.REQUEST_EXCEPTION;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Resolution;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Origin;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPrivacyConstraintResolver.Failure;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.apache.sysds.runtime.meta.MetaDataAll;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Runtime privacy acquisition semantics, independent of any planner implementation. */
public class FederatedPrivacyConstraintResolverTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void coordinatorLocalMetadataWinsWithoutWorkerRequest() throws Exception {
		Path dataPath = workerDataPath("local-first");
		writePrivacyMetadata(dataPath, "private");
		FederatedData data = workerData(dataPath);

		Resolution result = resolve(data);

		assertResolution(result, "private", LOCAL_PRE_REQUEST, NONE, null, null);
		verify(data, never()).requestPrivacyConstraints();
	}

	@Test
	public void mountedRelativeMetadataWinsWithoutWorkerRequest() throws Exception {
		Path absolute = workerDataPath("relative-mounted-worker");
		writePrivacyMetadata(absolute, "private-aggregate");
		Path relative = Path.of("").toAbsolutePath().relativize(absolute);
		FederatedData data = workerData(relative);

		Resolution result = resolve(data);

		assertResolution(result, "private-aggregate", LOCAL_PRE_REQUEST, NONE, null, null);
		verify(data, never()).requestPrivacyConstraints();
	}

	@Test
	public void malformedMountedMetadataFailsClosedWithoutWorkerRequest() throws Exception {
		Path absolute = workerDataPath("malformed-mounted-worker");
		Files.writeString(Path.of(absolute + ".mtd"), "{not-json");
		Path relative = Path.of("").toAbsolutePath().relativize(absolute);
		FederatedData data = workerData(relative);

		try {
			resolve(data);
			Assert.fail("A present malformed sidecar must not fall through to another privacy authority");
		}
		catch(IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage().contains("Cannot read local privacy metadata"));
		}
		verify(data, never()).requestPrivacyConstraints();
	}

	@Test
	public void successfulWorkerResponsePreservesRawPrivacyAndResponse() throws Exception {
		Path dataPath = workerDataPath("worker-success");
		FederatedData data = workerData(dataPath);
		FederatedResponse response = new FederatedResponse(ResponseType.SUCCESS, "private-aggregate");
		when(data.requestPrivacyConstraints()).thenReturn(CompletableFuture.completedFuture(response));

		Resolution result = resolve(data);

		assertResolution(result, "private-aggregate", WORKER_RESPONSE, NONE, response, null);
		verify(data).requestPrivacyConstraints();
	}

	@Test
	public void workerEncodesAbsentPrivacyAsExplicitPublic() throws Exception {
		Path dataPath = workerDataPath("worker-public-default");
		Files.writeString(Path.of(dataPath + ".mtd"), "{\"data_type\":\"matrix\","
			+ "\"value_type\":\"double\",\"rows\":1,\"cols\":1,\"nnz\":1,\"format\":\"csv\"}");

		FederatedResponse response = new FederatedData.GetPrivacyConstraints(dataPath.toString()).execute(null);

		Assert.assertTrue(response.isSuccessful());
		Assert.assertArrayEquals(new Object[] {"public"}, response.getData());
	}

	@Test
	public void emptyFineGrainedPrivacyRetainsOrdinaryPublicDefault() throws Exception {
		Path dataPath = workerDataPath("worker-empty-fine-privacy");
		Files.writeString(Path.of(dataPath + ".mtd"), "{\"data_type\":\"matrix\","
			+ "\"value_type\":\"double\",\"rows\":1,\"cols\":1,\"nnz\":1,\"format\":\"csv\","
			+ "\"fine_grained_privacy\":{\"Private\":[],\"PrivateAggregation\":[]}}");

		FederatedResponse response = new FederatedData.GetPrivacyConstraints(dataPath.toString()).execute(null);

		Assert.assertTrue(response.isSuccessful());
		Assert.assertArrayEquals(new Object[] {"public"}, response.getData());
	}

	@Test
	public void nonemptyFineGrainedPrivacyFailsWithTypedUnsupportedState() throws Exception {
		Path dataPath = workerDataPath("worker-nonempty-fine-privacy");
		Files.writeString(Path.of(dataPath + ".mtd"), "{\"data_type\":\"matrix\","
			+ "\"value_type\":\"double\",\"rows\":2,\"cols\":2,\"nnz\":4,\"format\":\"csv\","
			+ "\"fine_grained_privacy\":{\"Private\":[[[0,0],[0,1]]]}}");

		try {
			new FederatedData.GetPrivacyConstraints(dataPath.toString()).execute(null);
			Assert.fail("Fine-grained privacy must not be reported as public");
		}
		catch(MetaDataAll.UnsupportedFineGrainedPrivacyException expected) {
			Assert.assertEquals(MetaDataAll.UNSUPPORTED_FINE_GRAINED_PRIVACY, expected.code());
		}

		FederatedData mounted = workerData(dataPath);
		try {
			resolve(mounted);
			Assert.fail("Coordinator-local acquisition must preserve the typed unsupported state");
		}
		catch(MetaDataAll.UnsupportedFineGrainedPrivacyException expected) {
			verify(mounted, never()).requestPrivacyConstraints();
		}
	}

	@Test
	public void malformedFineGrainedPrivacyFailsClosed() throws Exception {
		Path dataPath = workerDataPath("worker-malformed-fine-privacy");
		Files.writeString(Path.of(dataPath + ".mtd"),
			"{\"fine_grained_privacy\":{\"Private\":\"not-a-range-array\"}}");

		try {
			new FederatedData.GetPrivacyConstraints(dataPath.toString()).execute(null);
			Assert.fail("Malformed fine-grained privacy must not default to public");
		}
		catch(RuntimeException expected) {
			Throwable cause = expected;
			while(cause.getCause() != null) cause = cause.getCause();
			Assert.assertTrue(cause instanceof DMLRuntimeException);
			Assert.assertTrue(cause.getMessage().contains("Malformed fine_grained_privacy"));
		}
	}

	@Test
	public void remoteUnsupportedFineGrainedResponseRetainsTypedFailure() throws Exception {
		FederatedData data = workerData(workerDataPath("remote-fine-privacy"));
		FederatedResponse response = new FederatedResponse(ResponseType.ERROR,
			MetaDataAll.UNSUPPORTED_FINE_GRAINED_PRIVACY + ": worker metadata contains private ranges");
		when(data.requestPrivacyConstraints()).thenReturn(CompletableFuture.completedFuture(response));

		assertResolution(resolve(data), null, UNRESOLVED, UNSUPPORTED_FINE_GRAINED_PRIVACY,
			response, null);
	}

	@Test
	public void nullWorkerResponseRecoversFromMetadataCreatedAfterRequest() throws Exception {
		Path dataPath = workerDataPath("null-response");
		FederatedData data = workerData(dataPath);
		when(data.requestPrivacyConstraints()).thenAnswer(invocation -> {
			writePrivacyMetadata(dataPath, "private");
			return CompletableFuture.completedFuture(null);
		});

		Resolution result = resolve(data);

		assertResolution(result, "private", LOCAL_POST_REQUEST, NULL_RESPONSE, null, null);
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

		Resolution result = resolve(data);

		assertResolution(result, "private", LOCAL_POST_REQUEST, NULL_PAYLOAD, response, null);
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

		Resolution result = resolve(data);

		assertResolution(result, "private-aggregate", LOCAL_POST_REQUEST, ERROR_RESPONSE, response, null);
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

		Resolution result = resolve(data);

		assertResolution(result, "private", LOCAL_POST_REQUEST, REQUEST_EXCEPTION, null, failure);
		verify(data).requestPrivacyConstraints();
	}

	@Test
	public void unresolvedWorkerResultsRetainFailureEvidenceWithoutLocalMetadata() throws Exception {
		FederatedData nullResponseData = workerData(workerDataPath("unresolved-null-response"));
		when(nullResponseData.requestPrivacyConstraints()).thenReturn(CompletableFuture.completedFuture(null));
		assertResolution(resolve(nullResponseData), null, UNRESOLVED, NULL_RESPONSE, null, null);

		FederatedData nullPayloadData = workerData(workerDataPath("unresolved-null-payload"));
		FederatedResponse nullPayload = new FederatedResponse(ResponseType.SUCCESS, (Object) null);
		when(nullPayloadData.requestPrivacyConstraints()).thenReturn(CompletableFuture.completedFuture(nullPayload));
		assertResolution(resolve(nullPayloadData), null, UNRESOLVED, NULL_PAYLOAD, nullPayload, null);

		FederatedData errorData = workerData(workerDataPath("unresolved-error"));
		FederatedResponse error = new FederatedResponse(ResponseType.ERROR, "worker-error");
		when(errorData.requestPrivacyConstraints()).thenReturn(CompletableFuture.completedFuture(error));
		assertResolution(resolve(errorData), null, UNRESOLVED, ERROR_RESPONSE, error, null);

		FederatedData exceptionData = workerData(workerDataPath("unresolved-exception"));
		IllegalStateException failure = new IllegalStateException("request-failed");
		when(exceptionData.requestPrivacyConstraints()).thenReturn(CompletableFuture.failedFuture(failure));
		assertResolution(resolve(exceptionData), null, UNRESOLVED, REQUEST_EXCEPTION, null, failure);

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

	private static void assertResolution(Resolution result, String privacy, Origin origin, Failure failure,
		FederatedResponse response, Throwable cause) {
		Assert.assertNotNull(result);
		Assert.assertEquals(privacy, result.privacyConstraint());
		Assert.assertEquals(origin, result.origin());
		Assert.assertEquals(failure, result.failure());
		Assert.assertSame(response, result.response());
		Assert.assertSame(cause, result.cause());
	}
}
