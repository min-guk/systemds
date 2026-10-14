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
package org.apache.sysds.runtime.meta;

import java.io.BufferedReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;

/** Trusted input facts, not estimated HOP dimensions. No payload or worker access. */
public record InputColumnMetadata(Dataset dataset, Binding binding) {
	public static final String KEY = "cofee_input_metadata";
	public static final String SCHEMA = "cofee-input-column-metadata-v1";
	public static final String RELEASE_PROPERTY = "sysds.privacy.allowInputColumnMetadata";
	public enum Kind { CATEGORY_CODE, BIN_CODE, BINARY, NUMERIC }
	public enum Encoding { RECODE, EQUI_WIDTH_BIN, NONE }
	public enum Missing { NONE, ZERO, NAN, CATEGORY, ENCODER_DEFAULT, UNKNOWN }

	public record Column(int id, int sourceColumn, Kind kind, Encoding encoding,
		Missing missing, boolean contiguousCodes, Long codeBase, Long observedMaxCode, Long codeUpperBound) {
		public Column {
			Objects.requireNonNull(kind); Objects.requireNonNull(encoding); Objects.requireNonNull(missing);
			if(id < 1 || sourceColumn < 1) throw invalid("column indexes must be positive");
			if(kind == Kind.NUMERIC) {
				if(encoding != Encoding.NONE || codeBase != null || observedMaxCode != null
					|| codeUpperBound != null || contiguousCodes) throw invalid("numeric column is not a code domain");
			}
			else {
				if(codeBase == null || codeBase < 0) throw invalid("code base is required");
				if((kind == Kind.CATEGORY_CODE && (encoding != Encoding.RECODE || codeBase != 1))
					|| (kind == Kind.BIN_CODE && (encoding != Encoding.EQUI_WIDTH_BIN || codeBase != 1))
					|| (kind == Kind.BINARY && (encoding != Encoding.NONE || codeBase != 0
						|| codeUpperBound == null || codeUpperBound != 1)))
					throw invalid("encoding and code domain disagree");
				if(observedMaxCode != null && observedMaxCode < codeBase
					|| codeUpperBound != null && codeUpperBound < codeBase
					|| observedMaxCode != null && codeUpperBound != null && observedMaxCode > codeUpperBound)
					throw invalid("observed maximum is not a valid code/bound");
			}
		}
	}

	public record Dataset(String datasetId, String datasetVersion, String producerVersion,
		String transformId, String rowSetId, long rows, long cols, List<Column> columns) {
		public Dataset {
			for(String id : List.of(datasetId,datasetVersion,producerVersion,transformId,rowSetId))
				if(id.isBlank()) throw invalid("empty identity");
			columns = List.copyOf(columns);
			if(rows <= 0 || cols <= 0 || cols != columns.size()) throw invalid("global dimensions/columns disagree");
			for(int i=0; i<columns.size(); i++)
				if(columns.get(i).id() != i+1) throw invalid("columns must be ordered and complete");
		}
		/** Exact column maxima only. Bounds do not satisfy this query. */
		public Optional<List<Long>> exactFdom() {
			List<Long> result = new ArrayList<>();
			for(Column column : columns) {
				if((column.kind()!=Kind.CATEGORY_CODE && column.kind()!=Kind.BIN_CODE)
					|| column.observedMaxCode()==null) return Optional.empty();
				result.add(column.observedMaxCode());
			}
			return Optional.of(List.copyOf(result));
		}
		/** Logical row order only; does not establish equal physical worker geometry. */
		public boolean sameRowOrder(Dataset other) {
			return other != null && rows == other.rows && rowSetId.equals(other.rowSetId)
				&& datasetVersion.equals(other.datasetVersion) && producerVersion.equals(other.producerVersion);
		}
	}

	public record Binding(String host, int port, String path, List<Long> begin, List<Long> end) {
		public Binding {
			if(host==null || host.isBlank() || path==null || path.isBlank() || port<1 || port>65535)
				throw invalid("invalid endpoint/path");
			begin=List.copyOf(begin); end=List.copyOf(end);
			if(begin.size()!=2 || end.size()!=2) throw invalid("two-dimensional range required");
			for(int i=0;i<2;i++) if(begin.get(i)<0 || end.get(i)<=begin.get(i)) throw invalid("invalid range");
		}
	}

	public InputColumnMetadata {
		Objects.requireNonNull(dataset); Objects.requireNonNull(binding);
		if(binding.end().get(0)>dataset.rows() || binding.end().get(1)>dataset.cols())
			throw invalid("range exceeds global shape");
	}

	/** Parse a metadata extension and bind it to the unchanged base sidecar. */
	public static InputColumnMetadata parse(JSONObject mtd) {
		if(!mtd.containsKey(KEY)) return null;
		JSONObject value = object(mtd,KEY);
		keys(value,Set.of("schema","scope","release","datasetId","datasetVersion","producerVersion",
			"transformId","rowSetId","rows","cols","columns","binding","baseMetadata"));
		if(!SCHEMA.equals(string(value,"schema")) || !"GLOBAL".equals(string(value,"scope"))
			|| !"PUBLIC_COLUMN_STATISTICS".equals(string(value,"release")))
			throw invalid("unsupported schema, scope, or release declaration");
		JSONObject base = new JSONObject(mtd);
		base.remove(KEY);
		if(!base.equals(object(value,"baseMetadata"))) throw invalid("base metadata identity changed");
		JSONObject b=object(value,"binding");
		keys(b,Set.of("host","port","path","begin","end"));
		Binding binding = new Binding(string(b,"host"),Math.toIntExact(number(b,"port")),string(b,"path"),
			coordinates(b,"begin"),coordinates(b,"end"));
		if(number(base,"rows") != binding.end().get(0)-binding.begin().get(0)
			|| number(base,"cols") != binding.end().get(1)-binding.begin().get(1)
			|| !"matrix".equals(string(base,"data_type")) || !"double".equals(string(base,"value_type")))
			throw invalid("base shape/type disagrees with bound range");
		List<Column> columns=new ArrayList<>();
		Object raw=value.opt("columns");
		if(!(raw instanceof JSONArray)) throw invalid("columns must be an array");
		for(Object item : (JSONArray)raw) {
			if(!(item instanceof JSONObject c)) throw invalid("column must be an object");
			keys(c,Set.of("id","sourceColumn","kind","encoding","missing","contiguousCodes",
				"codeBase","observedMaxCode","codeUpperBound"));
			if(!(c.opt("contiguousCodes") instanceof Boolean)) throw invalid("contiguousCodes must be boolean");
			columns.add(new Column(Math.toIntExact(number(c,"id")),Math.toIntExact(number(c,"sourceColumn")),
				Kind.valueOf(string(c,"kind")),Encoding.valueOf(string(c,"encoding")),Missing.valueOf(string(c,"missing")),
				(Boolean)c.opt("contiguousCodes"),optionalNumber(c,"codeBase"),optionalNumber(c,"observedMaxCode"),
				optionalNumber(c,"codeUpperBound")));
		}
		return new InputColumnMetadata(new Dataset(string(value,"datasetId"),string(value,"datasetVersion"),
			string(value,"producerVersion"),string(value,"transformId"),string(value,"rowSetId"),
			number(value,"rows"),number(value,"cols"),columns),binding);
	}

	/** Exact coordinator-local mirror only. Missing/remote sidecars never trigger an RPC. */
	public static MetaDataAll readLocal(String dataPath) {
		try {
			if(dataPath==null || dataPath.isBlank()) return null;
			Path payload;
			if(dataPath.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
				URI uri=new URI(dataPath);
				if(!"file".equals(uri.getScheme())) return null;
				payload=Path.of(uri);
			}
			else payload=Path.of(dataPath);
			Path sidecar=Path.of(payload + ".mtd");
			if(!Files.exists(sidecar)) return null;
			if(!Files.isRegularFile(sidecar)) throw invalid("sidecar is not a regular file");
			try(BufferedReader reader=Files.newBufferedReader(sidecar)) { return new MetaDataAll(reader); }
		}
		catch(Exception ex) { throw new DMLRuntimeException("Cannot read input column metadata",ex); }
	}

	private static JSONObject object(JSONObject parent,String key) {
		if(!(parent.opt(key) instanceof JSONObject value)) throw invalid("missing object: "+key);
		return value;
	}
	private static String string(JSONObject parent,String key) {
		if(!(parent.opt(key) instanceof String value) || value.isBlank()) throw invalid("missing string: "+key);
		return value;
	}
	private static long number(JSONObject parent,String key) {
		return integer(parent.opt(key),key);
	}
	private static long integer(Object value,String key) {
		// JSON integer tokens only: accepting a parsed double can silently round a large exact code.
		if(!(value instanceof Integer || value instanceof Long)) throw invalid("int64 integer token required: "+key);
		return ((Number)value).longValue();
	}
	private static Long optionalNumber(JSONObject parent,String key) { return parent.containsKey(key) ? number(parent,key) : null; }
	private static List<Long> coordinates(JSONObject parent,String key) {
		if(!(parent.opt(key) instanceof JSONArray values) || values.size()!=2) throw invalid("invalid range: "+key);
		List<Long> result=new ArrayList<>();
		for(Object value : values) {
			result.add(integer(value,key));
		}
		return result;
	}
	private static void keys(JSONObject value,Set<String> allowed) {
		for(Object key : value.keySet()) if(!allowed.contains(key)) throw invalid("unsupported field: "+key);
	}
	private static IllegalArgumentException invalid(String message) {
		return new IllegalArgumentException("Invalid input column metadata: "+message);
	}
}
