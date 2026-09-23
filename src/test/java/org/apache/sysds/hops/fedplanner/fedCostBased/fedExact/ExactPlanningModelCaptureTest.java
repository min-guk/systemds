/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.Assert;
import org.junit.Test;

public class ExactPlanningModelCaptureTest {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

	@Test public void packedTruthMatchesNativeFactorStatusesExactly() throws Exception {
		var left = new ExactCategoricalSolver.Variable("left", 2);
		var right = new ExactCategoricalSolver.Variable("right", 3);
		var factor = ExactCategoricalSolver.Factor.lazy(List.of(left, right), values -> {
			if(values[0] == 0 && values[1] == 0) return 0.0;
			if(values[0] == 0 && values[1] == 1) return Double.POSITIVE_INFINITY;
			if(values[0] == 0 && values[1] == 2) throw new IllegalStateException("unknown");
			if(values[0] == 1 && values[1] == 0) return -0.0d;
			if(values[0] == 1 && values[1] == 1) return 1.0d;
			return 0.0;
		});

		ExactPlanningModelCapture.PackedTruth packed =
			ExactPlanningModelCapture.packFactorTruth(factor, 6);
		Assert.assertArrayEquals(new int[] {0, 1, 2, 2, 2, 0},
			java.util.stream.IntStream.range(0, 6).map(packed::statusCode).toArray());
		Assert.assertEquals(2, packed.allow());
		Assert.assertEquals(1, packed.reject());
		Assert.assertEquals(3, packed.unknown());
	}

	@Test public void streamedArtifactReceiptDescribesUncompressedJson() throws Exception {
		Path directory = Files.createTempDirectory("e-model-stream-");
		Path destination = directory.resolve("artifact.json.gz");
		Map<String,Object> artifact = new LinkedHashMap<>();
		artifact.put("schema", "closed-e-native-model-artifact-v1");
		artifact.put("values", java.util.List.of("alpha", "beta", "gamma"));
		artifact.put("nested", Map.of("z", 3, "a", 1));
		byte[] expected = JSON.writeValueAsBytes(artifact);

		ExactPlanningModelCapture.ArtifactWrite receipt =
			ExactPlanningModelCapture.writeArtifact(destination, artifact);
		byte[] actual;
		try(GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(destination));
			ByteArrayOutputStream plain = new ByteArrayOutputStream()) {
			gzip.transferTo(plain);
			actual = plain.toByteArray();
		}

		Assert.assertArrayEquals(expected, actual);
		Assert.assertEquals(expected.length, receipt.bytes());
		Assert.assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(expected)),
			receipt.sha256());
	}

	@Test public void failedStreamDoesNotReplaceExistingArtifact() throws Exception {
		Path directory = Files.createTempDirectory("e-model-stream-failure-");
		Path destination = directory.resolve("artifact.json.gz");
		byte[] prior = new byte[] {1, 2, 3, 4};
		Files.write(destination, prior);
		try {
			ExactPlanningModelCapture.writeArtifact(destination, Map.of("broken", new BrokenValue()));
			Assert.fail("Serialization failure must propagate");
		}
		catch(Exception expected) {
			Assert.assertTrue(expected.getMessage().contains("boom"));
		}
		Assert.assertArrayEquals(prior, Files.readAllBytes(destination));
		try(var entries = Files.list(directory)) {
			Assert.assertEquals(1, entries.count());
		}
	}

	public static final class BrokenValue {
		public String getValue() { throw new IllegalStateException("boom"); }
	}

	private static String hex(byte[] digest) {
		StringBuilder result = new StringBuilder(digest.length * 2);
		for(byte value : digest) result.append(String.format("%02x", value & 0xff));
		return result.toString();
	}
}
