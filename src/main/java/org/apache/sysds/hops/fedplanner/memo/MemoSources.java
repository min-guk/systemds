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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
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
import org.apache.sysds.runtime.meta.InputColumnMetadata;
import org.apache.sysds.runtime.util.HDFSTool;

/** One acquisition of exact ingress metadata, independent of candidate selection. */
public final class MemoSources {
	public record Source(int owner, MemoPartition partition, Privacy privacy, long rows, long columns) { }
	private record Pending(int owner,DataOp data,FederatedSourceMetadata resolved,FType type,
		long rows,long columns,FederationMap map) { }
	private record PartitionSnapshot(long[] begin,long[] end,DataType dataType,
		String host,int port,String filepath) {
		private PartitionSnapshot {
			begin=begin.clone(); end=end.clone();
		}
		private Pair<FederatedRange,FederatedData> materialize() {
			return Pair.of(new FederatedRange(begin.clone(),end.clone()),FederatedData.forPlannerMetadata(
				dataType,java.net.InetSocketAddress.createUnresolved(host,port),filepath));
		}
	}
	private record SourceMetadataSnapshot(Privacy privacy,List<PartitionSnapshot> partitions) {
		private SourceMetadataSnapshot { partitions=List.copyOf(partitions); }
		private FederatedSourceMetadata materialize() {
			return new FederatedSourceMetadata(privacy,partitions.stream()
				.map(PartitionSnapshot::materialize).toList());
		}
	}
	private final CanonicalWorkerPool pool;
	private final Map<Integer,Source> sources;
	private final Map<Integer,Privacy> privacyFacts;
	private final Map<Integer,SourceMetadataSnapshot> sourceMetadata;
	private final Map<Integer,InputColumnMetadata.Dataset> inputMetadata;
	private final List<String> inputMetadataDiagnostics;
	private MemoSources(CanonicalWorkerPool pool, Map<Integer,Source> sources,
		Map<Integer,Privacy> privacyFacts,Map<Integer,SourceMetadataSnapshot> sourceMetadata,
		Map<Integer,InputColumnMetadata.Dataset> inputMetadata,List<String> inputMetadataDiagnostics) {
		this.pool = pool;
		this.sources = Map.copyOf(sources);
		this.privacyFacts = Map.copyOf(privacyFacts);
		this.sourceMetadata=Map.copyOf(sourceMetadata);
		this.inputMetadata=Map.copyOf(inputMetadata);
		this.inputMetadataDiagnostics=List.copyOf(inputMetadataDiagnostics);
	}
	public static MemoSources acquire(MemoProgramFacts facts, CanonicalWorkerPool configuredPool) {
		return acquire(facts,configuredPool,FederatedPlannerUtils::resolveFederatedSourceMetadata);
	}
	static MemoSources acquire(MemoProgramFacts facts, CanonicalWorkerPool configuredPool,
		Function<DataOp,FederatedSourceMetadata> metadata) {
		CanonicalWorkerPool fixed = configuredPool;
		List<Pending> pending=new ArrayList<>();
		Map<Integer,Source> sources = new LinkedHashMap<>();
		Map<Integer,Privacy> privacyFacts = new LinkedHashMap<>();
		Map<Integer,SourceMetadataSnapshot> sourceMetadata=new LinkedHashMap<>();
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
			if(data.getDim1() > 0 && data.getDim1() != rows || data.getDim2() > 0 && data.getDim2() != columns)
				throw new IllegalStateException("Federated source shape disagrees with its exact ranges: " + data.getName());
			pending.add(new Pending(owner,data,resolved,type,rows,columns,map));
		}
		if(fixed!=null && !pending.isEmpty()) {
			Map<Long,long[]> observed=new java.util.TreeMap<>();
			for(Pending source : pending) {
				validateEndpointsAndRectangles(fixed,source);
				if(source.type()==FType.ROW || source.type()==FType.COL) {
					long length=source.type()==FType.ROW ? source.rows() : source.columns();
					long[] cuts=sourceCuts(source.map(),source.type(),length,fixed.size());
					long[] previous=observed.putIfAbsent(length,cuts);
					if(previous!=null && !Arrays.equals(previous,cuts))
						throw new IllegalStateException("Conflicting canonical source cuts for shared axis length "+length+".");
					if(fixed.hasAxisProfile(length) && !Arrays.equals(fixed.axisCuts(length),cuts))
						throw new IllegalStateException("Federated source cuts conflict with configured geometry for axis length "+length+".");
				}
			}
			Map<Long,long[]> profiles=new java.util.TreeMap<>(fixed.axisProfiles());
			for(Map.Entry<Long,long[]> entry : observed.entrySet())
				if(!Arrays.equals(entry.getValue(),defaultCuts(entry.getKey(),fixed.size())))
					profiles.put(entry.getKey(),entry.getValue());
			fixed=CanonicalWorkerPool.of(fixed.workers(),profiles);
			for(Pending source : pending) {
				// Canonical source ingress is strict. It never reorders workers, drops a
				// shard or silently collects protected input to normalize its layout.
				fixed.validateIngress(source.map(),source.data().getDataType(),source.type(),source.rows(),source.columns());
				sources.put(source.owner(),new Source(source.owner(),MemoPartition.fromRuntime(source.type(),fixed.size()),
					source.resolved().privacy(),source.rows(),source.columns()));
				privacyFacts.put(source.owner(),source.resolved().privacy());
				sourceMetadata.put(source.owner(),snapshot(source.resolved()));
			}
		}
		Map<Integer,InputColumnMetadata.Dataset> inputMetadata = new LinkedHashMap<>();
		List<String> inputMetadataDiagnostics = new ArrayList<>();
		if(Boolean.getBoolean(InputColumnMetadata.RELEASE_PROPERTY)) {
			Map<String,Optional<MetaDataAll>> sidecars = new LinkedHashMap<>();
			for(Pending source : pending) {
				// A local_matrix source is not the on-disk source described by worker paths.
				if(source.data().hasParameter(DataExpression.FED_LOCAL_OBJECT)) continue;
				try {
					InputColumnMetadata.Dataset columnFacts = readInputMetadata(source,sidecars);
					if(columnFacts != null) inputMetadata.put(source.owner(),columnFacts);
				}
				catch(RuntimeException ex) {
					inputMetadataDiagnostics.add("owner="+source.owner()+" INPUT_COLUMN_METADATA_REJECTED: "+ex.getMessage());
				}
			}
		}
		return new MemoSources(fixed,sources,privacyFacts,sourceMetadata,inputMetadata,inputMetadataDiagnostics);
	}
	/** Original source facts only; no propagation to derived HOPs or exact dimensions. */
	public InputColumnMetadata.Dataset inputMetadata(int owner) { return inputMetadata.get(owner); }
	public List<String> inputMetadataDiagnostics() { return inputMetadataDiagnostics; }

	private static InputColumnMetadata.Dataset readInputMetadata(Pending source,
		Map<String,Optional<MetaDataAll>> sidecars) {
		InputColumnMetadata.Dataset agreed = null;
		int present=0;
		for(Pair<FederatedRange,FederatedData> part : source.resolved().partitions()) {
			FederatedData data = part.getValue();
			MetaDataAll mtd = sidecars.computeIfAbsent(data.getFilepath(),
				path -> Optional.ofNullable(InputColumnMetadata.readLocal(path))).orElse(null);
			if(mtd == null) continue;
			String privacy = mtd.getPrivacyConstraintsFailClosed();
			InputColumnMetadata artifact = mtd.getInputColumnMetadata();
			if(artifact == null) continue;
			present++;
			InputColumnMetadata.Binding binding = artifact.binding();
			long[] begin=part.getKey().getBeginDims(), end=part.getKey().getEndDims();
			if(data.getDataType()!=DataType.MATRIX || source.data().getValueType()!=ValueType.FP64
				|| !binding.host().equalsIgnoreCase(data.getAddress().getHostString())
				|| binding.port()!=data.getAddress().getPort() || !binding.path().equals(data.getFilepath())
				|| !binding.begin().equals(List.of(begin[0],begin[1])) || !binding.end().equals(List.of(end[0],end[1]))
				|| !(privacy==null ? "public" : privacy).equals(source.resolved().privacy().name().toLowerCase(java.util.Locale.ROOT).replace('_','-'))
				|| artifact.dataset().rows()!=source.rows() || artifact.dataset().cols()!=source.columns())
				throw new IllegalArgumentException("source endpoint/path/range/type/privacy/global shape mismatch");
			if(agreed != null && !agreed.equals(artifact.dataset()))
				throw new IllegalArgumentException("global input facts differ between shards");
			agreed=artifact.dataset();
		}
		if(present!=0 && present!=source.resolved().partitions().size())
			throw new IllegalArgumentException("input metadata is present on only some shards");
		return agreed;
	}

	public CanonicalWorkerPool pool() { return pool; }
	public Source source(int owner) { return sources.get(owner); }
	public Map<Integer,Source> sources() { return sources; }
	public Privacy privacy(int owner) { return privacyFacts.get(owner); }
	public Map<Integer,Privacy> privacyFacts() { return privacyFacts; }
	FederatedSourceMetadata sourceMetadata(int owner) {
		SourceMetadataSnapshot snapshot=sourceMetadata.get(owner);
		return snapshot==null ? null : snapshot.materialize();
	}
	boolean matchesSourceMetadata(int owner,FederatedSourceMetadata candidate) {
		SourceMetadataSnapshot expected=sourceMetadata.get(owner);
		return expected!=null && candidate!=null && sameMetadata(expected,candidate);
	}
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

	private static void validateEndpointsAndRectangles(CanonicalWorkerPool pool,Pending source) {
		List<Pair<FederatedRange,FederatedData>> parts=source.map().getMap();
		if(parts.size()!=pool.size())
			throw new IllegalStateException("Federated source worker count disagrees with the canonical pool.");
		for(int i=0;i<parts.size();i++) {
			Pair<FederatedRange,FederatedData> part=parts.get(i);
			if(part==null || part.getKey()==null || part.getValue()==null || part.getValue().getAddress()==null
				|| part.getValue().getDataType()!=source.data().getDataType())
				throw new IllegalStateException("Malformed federated source partition at worker "+i+".");
			var expected=pool.workers().get(i); var actual=part.getValue().getAddress();
			if(expected.getPort()!=actual.getPort()
				|| !expected.getHostString().equalsIgnoreCase(actual.getHostString()))
				throw new IllegalStateException("Federated source endpoint order disagrees with the canonical pool.");
			long[] begin=part.getKey().getBeginDims(), end=part.getKey().getEndDims();
			if(begin.length!=2 || end.length!=2 || begin[0]<0 || begin[1]<0
				|| end[0]<=begin[0] || end[1]<=begin[1] || end[0]>source.rows() || end[1]>source.columns())
				throw new IllegalStateException("Invalid federated source rectangle at worker "+i+".");
		}
	}

	private static long[] sourceCuts(FederationMap map,FType type,long length,int workers) {
		long[] cuts=new long[workers+1];
		int axis=type==FType.ROW ? 0 : 1, orthogonal=1-axis;
		for(int i=0;i<workers;i++) {
			FederatedRange range=map.getMap().get(i).getKey();
			long[] begin=range.getBeginDims(),end=range.getEndDims();
			if(begin[orthogonal]!=0)
				throw new IllegalStateException("Federated source does not cover the full orthogonal axis.");
			if(i==0) cuts[0]=begin[axis];
			else if(begin[axis]!=cuts[i])
				throw new IllegalStateException("Federated source ranges contain a gap or overlap.");
			cuts[i+1]=end[axis];
		}
		if(cuts[0]!=0 || cuts[workers]!=length)
			throw new IllegalStateException("Federated source ranges do not cover the complete partition axis.");
		for(int i=1;i<cuts.length;i++) if(cuts[i]<=cuts[i-1])
			throw new IllegalStateException("Federated source contains an empty or reversed shard.");
		return cuts;
	}

	private static long[] defaultCuts(long length,int workers) {
		long[] cuts=new long[workers+1];
		long base=length/workers,remainder=length%workers;
		for(int i=0;i<=workers;i++) cuts[i]=i*base+Math.min(i,remainder);
		return cuts;
	}

	private static SourceMetadataSnapshot snapshot(FederatedSourceMetadata metadata) {
		List<PartitionSnapshot> partitions=new ArrayList<>(metadata.partitions().size());
		for(Pair<FederatedRange,FederatedData> part : metadata.partitions()) {
			FederatedData data=part.getValue();
			partitions.add(new PartitionSnapshot(part.getKey().getBeginDims(),part.getKey().getEndDims(),
				data.getDataType(),data.getAddress().getHostString(),data.getAddress().getPort(),data.getFilepath()));
		}
		return new SourceMetadataSnapshot(metadata.privacy(),partitions);
	}

	private static boolean sameMetadata(SourceMetadataSnapshot expected,FederatedSourceMetadata candidate) {
		if(expected.privacy()!=candidate.privacy()
			|| expected.partitions().size()!=candidate.partitions().size()) return false;
		for(int i=0;i<expected.partitions().size();i++) {
			PartitionSnapshot left=expected.partitions().get(i);
			Pair<FederatedRange,FederatedData> right=candidate.partitions().get(i);
			if(right==null || right.getKey()==null || right.getValue()==null
				|| right.getValue().getAddress()==null
				|| !Arrays.equals(left.begin(),right.getKey().getBeginDims())
				|| !Arrays.equals(left.end(),right.getKey().getEndDims())
				|| left.dataType()!=right.getValue().getDataType()
				|| left.port()!=right.getValue().getAddress().getPort()
				|| !left.host().equalsIgnoreCase(right.getValue().getAddress().getHostString())
				|| !Objects.equals(left.filepath(),right.getValue().getFilepath())) return false;
		}
		return true;
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
