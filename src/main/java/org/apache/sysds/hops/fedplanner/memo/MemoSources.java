/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.runtime.controlprogram.federated.CanonicalWorkerPool;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.meta.MetaDataAll;
import org.apache.sysds.runtime.util.HDFSTool;

/** One acquisition of exact ingress metadata, independent of candidate selection. */
public final class MemoSources {
	public record Source(int owner, MemoPartition partition, Privacy privacy, long rows, long columns) { }
	private final CanonicalWorkerPool pool;
	private final Map<Integer,Source> sources;
	private final Map<Integer,Privacy> privacyFacts;
	private MemoSources(CanonicalWorkerPool pool, Map<Integer,Source> sources,
		Map<Integer,Privacy> privacyFacts) {
		this.pool = pool;
		this.sources = Map.copyOf(sources);
		this.privacyFacts = Map.copyOf(privacyFacts);
	}
	public static MemoSources acquire(MemoProgramFacts facts, CanonicalWorkerPool configuredPool) {
		return acquire(facts,configuredPool,FederatedPlannerUtils::resolveFederatedSourceMetadata);
	}
	static MemoSources acquire(MemoProgramFacts facts, CanonicalWorkerPool configuredPool,
		Function<DataOp,FederatedSourceMetadata> metadata) {
		CanonicalWorkerPool fixed = configuredPool;
		Map<Integer,Source> sources = new LinkedHashMap<>();
		Map<Integer,Privacy> privacyFacts = new LinkedHashMap<>();
		Map<String,Optional<Privacy>> localMetadata = new LinkedHashMap<>();
		for(int owner = 0; owner < facts.compiledOwnerCount(); owner++) {
			if(!(facts.compiledOwnerHop(owner) instanceof DataOp data))
				continue;
			if(data.getOp() != OpOpData.FEDERATED) {
				if(data.getOp() == OpOpData.PERSISTENTREAD || data.getOp() == OpOpData.SQLREAD) {
					Privacy privacy = explicitPrivacy(data);
					String filename = literalFilename(data);
					Optional<Privacy> metadataPrivacy = filename == null ? Optional.empty()
						: localMetadata.computeIfAbsent(filename,MemoSources::localMetadataPrivacy);
					// Inspect an existing sidecar even when the read has an explicit coarse
					// label. A fine-grained constraint must not be hidden by that label.
					if(privacy == null) privacy = metadataPrivacy.orElse(null);
					if(privacy != null) privacyFacts.put(owner, privacy);
				}
				continue;
			}
			FederatedSourceMetadata resolved = Objects.requireNonNull(metadata.apply(data),"source metadata");
			List<Pair<FederatedRange,FederatedData>> parts = resolved.partitions();
			if(parts.isEmpty()) throw new IllegalStateException("Empty federated source: " + data.getName());
			long rows = 0, columns = 0;
			for(var part : parts) {
				rows = Math.max(rows,part.getKey().getEndDims()[0]);
				columns = Math.max(columns,part.getKey().getEndDims()[1]);
			}
			FType type = type(parts,rows,columns);
			FederationMap map = new FederationMap(-1,parts,type);
			if(fixed == null) fixed = CanonicalWorkerPool.fromFederationMap(map);
			// Canonical source ingress is strict. It never reorders workers, drops a
			// shard or silently collects protected input to normalize its layout.
			fixed.validateIngress(map,data.getDataType(),type,rows,columns);
			if(data.getDim1() > 0 && data.getDim1() != rows || data.getDim2() > 0 && data.getDim2() != columns)
				throw new IllegalStateException("Federated source shape disagrees with its exact ranges: " + data.getName());
			sources.put(owner,new Source(owner,MemoPartition.fromRuntime(type,fixed.size()),
				resolved.privacy(),rows,columns));
			privacyFacts.put(owner, resolved.privacy());
		}
		return new MemoSources(fixed,sources,privacyFacts);
	}
	public CanonicalWorkerPool pool() { return pool; }
	public Source source(int owner) { return sources.get(owner); }
	public Map<Integer,Source> sources() { return sources; }
	public Privacy privacy(int owner) { return privacyFacts.get(owner); }
	public Map<Integer,Privacy> privacyFacts() { return privacyFacts; }
	public int workers() { return pool == null ? 0 : pool.size(); }
	private static FType type(List<Pair<FederatedRange,FederatedData>> parts,long rows,long cols) {
		boolean fullRows = true, fullCols = true;
		for(var part : parts) {
			FederatedRange range = part.getKey();
			fullRows &= range.getBeginDims()[0] == 0 && range.getEndDims()[0] == rows;
			fullCols &= range.getBeginDims()[1] == 0 && range.getEndDims()[1] == cols;
		}
		if(fullRows && fullCols) return parts.size() == 1 ? FType.FULL : FType.BROADCAST;
		if(fullCols) return FType.ROW;
		if(fullRows) return FType.COL;
		throw new IllegalStateException("Noncanonical federated source rectangles are outside the memo execution contract");
	}

	private static String literalFilename(DataOp data) {
		if(data.getFileName() != null && !data.getFileName().isEmpty()) return data.getFileName();
		if(!data.hasParameter(DataExpression.IO_FILENAME)) return null;
		Hop filename = data.getInput(data.getParameterIndex(DataExpression.IO_FILENAME));
		return filename instanceof LiteralOp ? ((LiteralOp) filename).getStringValue() : null;
	}

	private static Privacy explicitPrivacy(DataOp data) {
		if(!data.hasParameter(DataExpression.PRIVACY)) return null;
		Hop value = data.getInput(data.getParameterIndex(DataExpression.PRIVACY));
		return value instanceof LiteralOp ? parsePrivacy(((LiteralOp) value).getStringValue()) : null;
	}

	private static Optional<Privacy> localMetadataPrivacy(String filename) {
		String metadata = DataExpression.getMTDFileName(filename);
		if(!HDFSTool.existsFileOnHDFS(metadata)) return Optional.empty();
		MetaDataAll parsed = new MetaDataAll(metadata, false, true);
		if(!parsed.mtdExists()) return Optional.empty();
		String privacy = parsed.getPrivacyConstraintsFailClosed();
		return Optional.of(privacy == null ? Privacy.PUBLIC : parsePrivacy(privacy));
	}

	private static Privacy parsePrivacy(String value) {
		if(value == null) return null;
		return switch(value.trim().toLowerCase()) {
			case "private" -> Privacy.PRIVATE;
			case "private-aggregate", "private_aggregate" -> Privacy.PRIVATE_AGGREGATE;
			case "private-aggregate-to-public", "private_aggregate_to_public" ->
				Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
			case "public" -> Privacy.PUBLIC;
			default -> throw new IllegalArgumentException("Invalid source privacy constraint: " + value);
		};
	}
}
