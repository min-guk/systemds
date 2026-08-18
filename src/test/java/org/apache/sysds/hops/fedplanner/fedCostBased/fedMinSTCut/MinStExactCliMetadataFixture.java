/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.nio.file.Files;
import java.nio.file.Path;

/** Local metadata fixture for compile-only Exact-planner CLI tests. */
final class MinStExactCliMetadataFixture {
	private MinStExactCliMetadataFixture() {
		// utility class
	}

	static String privateAggregateAddress(String host, int port, Path data,
		long rows, long cols, long nnz) throws Exception {
		Files.createDirectories(data.toAbsolutePath().getParent());
		Files.writeString(data, "");
		Files.writeString(Path.of(data + ".mtd"), "{\"data_type\":\"matrix\","
			+ "\"value_type\":\"double\",\"format\":\"binary\","
			+ "\"rows\":" + rows + ",\"cols\":" + cols + ",\"rows_in_block\":1000,"
			+ "\"cols_in_block\":1000,\"nnz\":" + nnz + ','
			+ "\"privacy\":\"private-aggregate\"}");
		return host + ':' + port + '/' + escape(data);
	}

	static void delete(Path... dataPaths) throws Exception {
		for(Path data : dataPaths) {
			Files.deleteIfExists(Path.of(data + ".mtd"));
			Files.deleteIfExists(data);
		}
	}

	private static String escape(Path path) {
		return path.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
