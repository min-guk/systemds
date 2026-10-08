/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationSupportKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Immutable result of constructing one neutral placement universe for a compiled program. */
public final class PlacementAnalysis {
	private abstract static class HashCachedImmutableList<T> extends java.util.AbstractList<T>
		implements java.util.RandomAccess {
		private int cachedHash;
		private volatile boolean hashComputed;
		protected abstract List<T> values();
		@Override public T get(int index) { return values().get(index); }
		@Override public int size() { return values().size(); }
		@Override public boolean equals(Object other) {
			if(this == other)
				return true;
			List<T> values = values();
			if(!(other instanceof List<?> that) || values.size() != that.size())
				return false;
			if(that instanceof java.util.RandomAccess)
				for(int index = 0; index < values.size(); index++) {
					if(!Objects.equals(values.get(index), that.get(index)))
						return false;
				}
			else {
				java.util.Iterator<?> iterator = that.iterator();
				for(T value : values)
					if(!Objects.equals(value, iterator.next()))
						return false;
			}
			return true;
		}
		@Override public int hashCode() {
			// Values are immutable for the lifetime of this wrapper. Publish the cached
			// value only after computing it, including the valid zero hash.
			if(!hashComputed) {
				cachedHash = values().hashCode();
				hashComputed = true;
			}
			return cachedHash;
		}
	}

	private static final class CachedImmutableList<T> extends HashCachedImmutableList<T> {
		private final List<T> values;
		private CachedImmutableList(List<T> values) { this.values = List.copyOf(values); }
		@Override protected List<T> values() { return values; }
	}

	private static final class SharedCanonicalList<T> extends HashCachedImmutableList<T> {
		private static final int IDENTITY_INDEX_MIN_SIZE = 16;
		// Retain this field on the canonical marker for its existing reflection/test contract.
		private final List<T> values;
		private final List<CanonicalText> orderingKeys;
		private volatile IdentityHashMap<T,Integer> identityFirstOrdinals;
		private SharedCanonicalList(List<T> values) { this(values, null); }
		private SharedCanonicalList(List<T> values, List<CanonicalText> orderingKeys) {
			this.values = values;
			this.orderingKeys = orderingKeys;
			if(orderingKeys != null && orderingKeys.size() != values.size())
				throw new IllegalArgumentException("Canonical descriptor count differs from value count");
		}
		@Override protected List<T> values() { return values; }
		private int firstIdentityOrdinal(Object value) {
			if(values.size() < IDENTITY_INDEX_MIN_SIZE) {
				for(int index = 0; index < values.size(); index++)
					if(values.get(index) == value)
						return index;
				return -1;
			}
			IdentityHashMap<T,Integer> ordinals = identityFirstOrdinals;
			if(ordinals == null)
				synchronized(this) {
					ordinals = identityFirstOrdinals;
					if(ordinals == null) {
						ordinals = new IdentityHashMap<>();
						for(int index = 0; index < values.size(); index++)
							ordinals.putIfAbsent(values.get(index), index);
						identityFirstOrdinals = ordinals;
					}
				}
			Integer ordinal = ordinals.get(value);
			return ordinal == null ? -1 : ordinal;
		}
		@Override public List<T> subList(int fromIndex, int toIndex) {
			return new SharedCanonicalList<>(List.copyOf(values.subList(fromIndex, toIndex)),
				orderingKeys == null ? null : List.copyOf(orderingKeys.subList(fromIndex, toIndex)));
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<? super T>> List<T> canonicalComparableList(
		java.util.Collection<T> values, String label) {
		return canonicalComparableList(values, label, false);
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<? super T>> List<T> canonicalComparableList(
		java.util.Collection<T> values, String label, boolean retainOrderingKeys) {
		Objects.requireNonNull(values, label + "s");
		if(values instanceof SharedCanonicalList<?>)
			return (List<T>) values;
		if(values.isEmpty())
			return List.of();
		if(values.size() == 1)
			return List.of(Objects.requireNonNull(values.iterator().next(), label));
		SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
		if(metrics != null)
			metrics.recordCanonicalSort(values.size());
		List<CanonicalEntry<T>> decorated = new ArrayList<>(values.size());
		CanonicalTextContext textContext = new CanonicalTextContext();
		for(T value : values) {
			if(metrics != null)
				metrics.recordCanonicalOrderingKey();
			decorated.add(new CanonicalEntry<>(Objects.requireNonNull(value, label),
				canonicalOrderingKey(value, textContext)));
		}
		// Do not ask Comparable for the same recursively serialized signature O(log n)
		// times.  The legacy lexical bytes remain the ordering contract, but each
		// value materializes them at most once for this canonicalization boundary.
		CanonicalTextComparison comparison = new CanonicalTextComparison();
		decorated.sort((left, right) -> {
			if(metrics != null)
				metrics.recordCanonicalComparison();
			return comparison.compare(left.orderingKey(), right.orderingKey());
		});
		List<T> canonical = new ArrayList<>(decorated.size());
		List<CanonicalText> orderingKeys = retainOrderingKeys
			? new ArrayList<>(decorated.size()) : null;
		for(CanonicalEntry<T> entry : decorated) {
			canonical.add(entry.value());
			if(orderingKeys != null)
				orderingKeys.add(entry.orderingKey());
		}
		for(int i = 1; i < canonical.size(); i++)
			if(canonical.get(i - 1).equals(canonical.get(i)))
				throw new IllegalArgumentException("Duplicate " + label);
		return new SharedCanonicalList<>(List.copyOf(canonical),
			orderingKeys == null ? null : List.copyOf(orderingKeys));
	}

	private record CanonicalEntry<T>(T value, CanonicalText orderingKey) { }

	/**
	 * Immutable segmented UTF-16 text used only for canonical comparison. It keeps
	 * shared child signatures as segments and therefore does not allocate the full
	 * recursively concatenated clause/realization string on an intermediate sort.
	 */
	private static final class CanonicalText implements Comparable<CanonicalText> {
		private static final long NODE_AND_LIST_OVERHEAD = 64;
		private static final long PIECE_REFERENCE_OVERHEAD = 8;
		private static final long LITERAL_OVERHEAD = 40;
		private final Object[] pieces;
		private final int length;
		private final long retainedWeight;
		private int stringHash;
		private int hashPower;
		private volatile boolean hashComputed;

		private CanonicalText(List<Object> pieces) {
			this.pieces = pieces.toArray();
			long total = 0;
			long weight = saturatedAdd(NODE_AND_LIST_OVERHEAD,
				PIECE_REFERENCE_OVERHEAD * this.pieces.length);
			for(Object piece : this.pieces) {
				Objects.requireNonNull(piece, "canonical text piece");
				total += piece instanceof String text ? text.length() : ((CanonicalText) piece).length;
				weight = saturatedAdd(weight, piece instanceof String text
					? saturatedAdd(LITERAL_OVERHEAD, 2L * text.length())
					: ((CanonicalText)piece).retainedWeight);
			}
			if(total > Integer.MAX_VALUE)
				throw new IllegalArgumentException("Canonical ordering text exceeds JVM string length");
			length = (int) total;
			retainedWeight = weight;
		}

		private static long saturatedAdd(long left, long right) {
			return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
		}

		private static CanonicalText literal(String value) {
			return new CanonicalText(value.isEmpty() ? List.of() : List.of(value));
		}

		private int stringHash() {
			if(hashComputed)
				return stringHash;
			// Exact String hash composition: h(a+b) = h(a)*31^length(b)+h(b).
			// Iterative postorder hashes shared child text once, without flattening
			// or recursively traversing deeply nested proof/receipt signatures.
			ArrayDeque<TextHashFrame> pending = new ArrayDeque<>();
			pending.addLast(new TextHashFrame(this));
			while(!pending.isEmpty()) {
				TextHashFrame frame = pending.getLast();
				CanonicalText text = frame.text;
				if(text.hashComputed) {
					pending.removeLast();
					continue;
				}
				if(frame.index == text.pieces.length) {
					text.stringHash = frame.hash;
					text.hashPower = frame.power;
					// Publish both integers together; concurrent redundant computation
					// can only write the same immutable-text result.
					text.hashComputed = true;
					pending.removeLast();
					continue;
				}
				Object piece = text.pieces[frame.index];
				int hash;
				int power;
				if(piece instanceof String literal) {
					hash = literal.hashCode();
					power = hashPower(literal.length());
				}
				else {
					CanonicalText child = (CanonicalText)piece;
					if(!child.hashComputed) {
						pending.addLast(new TextHashFrame(child));
						continue;
					}
					hash = child.stringHash;
					power = child.hashPower;
				}
				frame.hash = frame.hash * power + hash;
				frame.power *= power;
				frame.index++;
			}
			return stringHash;
		}

		private static int hashPower(int length) {
			int result = 1;
			int power = 31;
			for(int remaining = length; remaining != 0; remaining >>>= 1) {
				if((remaining & 1) != 0)
					result *= power;
				power *= power;
			}
			return result;
		}

		@Override public int compareTo(CanonicalText that) {
			return this == that ? 0 : new CanonicalTextComparison().compare(this, that);
		}
	}

	private static final class TextHashFrame {
		private final CanonicalText text;
		private int index;
		private int hash;
		private int power = 1;
		private TextHashFrame(CanonicalText text) { this.text = text; }
	}

	private static final class CanonicalPieceIterator {
		private final Object[] pieces;
		private int index;

		private CanonicalPieceIterator(CanonicalText text) { pieces = text.pieces; }
		private boolean hasNext() { return index < pieces.length; }
		private Object next() { return pieces[index++]; }
	}

	/** Reusable only inside one canonical sort invocation; ordinary comparisons create a fresh instance. */
	private static final class CanonicalTextComparison {
		private final CanonicalTextCursor leftCursor = new CanonicalTextCursor();
		private final CanonicalTextCursor rightCursor = new CanonicalTextCursor();

		private int compare(CanonicalText left, CanonicalText right) {
			if(left == right) {
				leftCursor.clear();
				rightCursor.clear();
				return 0;
			}
			leftCursor.reset(left);
			rightCursor.reset(right);
			int compared = 0;
			while(compared < left.length && compared < right.length) {
				int shared = leftCursor.skipSharedSubtree(rightCursor);
				if(shared != 0) {
					compared += shared;
					continue;
				}
				String leftText = leftCursor.text();
				String rightText = rightCursor.text();
				// Structural caches deliberately share immutable child signatures. When
				// two texts reach the same whole segment, its complete UTF-16 contents
				// are equal by identity and need not be rescanned character by character.
				if(leftCursor.offset() == 0 && rightCursor.offset() == 0 && leftText == rightText) {
					compared += leftText.length();
					leftCursor.skipText();
					rightCursor.skipText();
					continue;
				}
				int leftOffset = leftCursor.offset(), rightOffset = rightCursor.offset();
				int count = Math.min(leftText.length() - leftOffset, rightText.length() - rightOffset);
				// Compare a contiguous literal once, not one rope traversal per UTF-16
				// character. Whole literals use the JDK's optimized String comparator.
				if(leftOffset == 0 && rightOffset == 0) {
					int order = leftText.compareTo(rightText);
					if(order != 0) {
						int lengthDifference = leftText.length() - rightText.length();
						// A literal prefix is not a rope prefix: its next segment may
						// reverse the order. A character difference can also equal the
						// length delta, so verify that ambiguous case exactly.
						if(order != lengthDifference || !(lengthDifference < 0
							? rightText.startsWith(leftText) : leftText.startsWith(rightText)))
							return order;
					}
				}
				else {
					for(int index = 0; index < count; index++) {
						char leftCharacter = leftText.charAt(leftOffset + index);
						char rightCharacter = rightText.charAt(rightOffset + index);
						if(leftCharacter != rightCharacter)
							return Character.compare(leftCharacter, rightCharacter);
					}
				}
				leftCursor.advance(count);
				rightCursor.advance(count);
				compared += count;
			}
			return Integer.compare(left.length, right.length);
		}

		private void clear() {
			leftCursor.clear();
			rightCursor.clear();
		}
	}

	/** Depth-first cursor over a shared CanonicalText rope; no flattened segment list is created. */
	private static final class CanonicalTextCursor {
		private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
		private CanonicalText[] nodes = new CanonicalText[8];
		private int[] indices = new int[8];
		private int depth;
		private String text;
		private int offset;

		private String text() { return text; }
		private int offset() { return offset; }

		private void reset(CanonicalText root) {
			clear();
			push(root);
			advanceText();
		}

		private void clear() {
			while(depth != 0) {
				nodes[--depth] = null;
				indices[depth] = 0;
			}
			text = null;
			offset = 0;
		}

		private void push(CanonicalText value) {
			if(depth == nodes.length)
				grow();
			nodes[depth] = value;
			indices[depth] = 0;
			depth++;
		}

		private void grow() {
			if(depth == Integer.MAX_VALUE)
				throw new OutOfMemoryError("Canonical text nesting exceeds JVM array limit");
			int required = depth + 1;
			int grown = nodes.length + (nodes.length >> 1) + 1;
			if(grown < 0 || grown > MAX_ARRAY_SIZE)
				grown = required > MAX_ARRAY_SIZE ? Integer.MAX_VALUE : MAX_ARRAY_SIZE;
			if(grown < required)
				grown = required;
			nodes = java.util.Arrays.copyOf(nodes, grown);
			indices = java.util.Arrays.copyOf(indices, grown);
		}

		private void pop() {
			nodes[--depth] = null;
			indices[depth] = 0;
		}

		private void advance(int count) {
			offset += count;
			if(offset == text.length())
				advanceText();
		}

		private void skipText() { advanceText(); }

		/** Skip a shared immutable rope suffix only when both cursors are at the same position in it. */
		private int skipSharedSubtree(CanonicalTextCursor that) {
			if(text == null || text != that.text || offset != that.offset)
				return 0;
			int leftPosition = depth - 1;
			int rightPosition = that.depth - 1;
			int frames = 0;
			int remaining = text.length() - offset;
			while(leftPosition >= 0 && rightPosition >= 0) {
				CanonicalText leftNode = nodes[leftPosition];
				CanonicalText rightNode = that.nodes[rightPosition];
				int leftIndex = indices[leftPosition];
				if(leftNode != rightNode || leftIndex != that.indices[rightPosition])
					break;
				for(int index = leftIndex; index < leftNode.pieces.length; index++) {
					Object piece = leftNode.pieces[index];
					remaining += piece instanceof String literal ? literal.length() : ((CanonicalText) piece).length;
				}
				frames++;
				leftPosition--;
				rightPosition--;
			}
			if(frames == 0)
				return 0;
			for(int index = 0; index < frames; index++) {
				pop();
				that.pop();
			}
			advanceText();
			that.advanceText();
			return remaining;
		}

		private void advanceText() {
			text = null;
			offset = 0;
			while(depth != 0) {
				int position = depth - 1;
				CanonicalText node = nodes[position];
				int index = indices[position];
				if(index == node.pieces.length) {
					pop();
					continue;
				}
				Object piece = node.pieces[index];
				indices[position] = index + 1;
				if(piece instanceof String literal) {
					text = literal;
					return;
				}
				push((CanonicalText) piece);
			}
		}
	}

	private static final class CanonicalTextBuilder {
		// Length/delimiter metadata is immutable and independent of every authority
		// and payload. Share the small prefixes without interning field values.
		private static final String[][] FIELD_PREFIXES = fieldPrefixes();

		private static String[][] fieldPrefixes() {
			String[][] prefixes = new String[2][4096];
			for(int length = 0; length < prefixes[0].length; length++) {
				prefixes[0][length] = length + ":";
				prefixes[1][length] = "|" + prefixes[0][length];
			}
			return prefixes;
		}

		private final List<Object> pieces = new ArrayList<>();

		private CanonicalTextBuilder append(String value) {
			if(!value.isEmpty())
				pieces.add(value);
			return this;
		}

		private CanonicalTextBuilder append(CanonicalText value) {
			if(value.length != 0)
				pieces.add(value);
			return this;
		}

		private CanonicalTextBuilder appendFields(Object... values) {
			for(int index = 0; index < values.length; index++) {
				Object value = Objects.requireNonNull(values[index], "canonical field");
				int length;
				if(value instanceof String literal)
					length = literal.length();
				else if(value instanceof CanonicalText text)
					length = text.length;
				else
					throw new IllegalArgumentException("Unsupported canonical field type " + value.getClass().getName());
				append(length < FIELD_PREFIXES[0].length ? FIELD_PREFIXES[index == 0 ? 0 : 1][length]
					: index == 0 ? length + ":" : "|" + length + ":");
				// Literals already carry their UTF-16 text; only actual structural
				// children need a rope node. Preserve those children by identity.
				if(length != 0)
					pieces.add(value);
			}
			return this;
		}

		private CanonicalText build() { return new CanonicalText(pieces); }
	}

	private static final class CanonicalTextContext {
		private final IdentityHashMap<Object,CanonicalText> values = new IdentityHashMap<>();
		private CanonicalText get(Object key) {
			CanonicalText value = values.get(key);
			if(value != null)
				return value;
			ScopedCanonicalTextCache active = ACTIVE_CANONICAL_TEXT_CACHE.get();
			value = active == null ? null : active.get(key);
			if(value != null)
				values.put(key, value);
			return value;
		}
		private CanonicalText put(Object key, CanonicalText value) {
			values.put(key, value);
			ScopedCanonicalTextCache active = ACTIVE_CANONICAL_TEXT_CACHE.get();
			if(active != null)
				active.retain(key, value);
			return value;
		}
	}

	private static final int ANALYSIS_CANONICAL_TEXT_MAX_ENTRIES = 131_072;
	private static final long ANALYSIS_CANONICAL_TEXT_MAX_WEIGHT = 64L * 1024 * 1024;
	private static final ThreadLocal<ScopedCanonicalTextCache> ACTIVE_CANONICAL_TEXT_CACHE = new ThreadLocal<>();

	private static final class ScopedCanonicalTextCache {
		private static final long ENTRY_OVERHEAD = 64;
		private static final long LEDGER_BASE_OVERHEAD = 256;
		private static final long LEDGER_IDENTITY_OVERHEAD = 128;
		private IdentityHashMap<Object,CanonicalText> values = new IdentityHashMap<>();
		private IdentityHashMap<Object,Boolean> retainedDescriptors = new IdentityHashMap<>();
		private final int maxEntries;
		private final long maxWeight;
		private long retainedWeight;

		private ScopedCanonicalTextCache(int maxEntries, long maxWeight) {
			if(maxEntries < 0 || maxWeight < 0)
				throw new IllegalArgumentException("Canonical text cache budget is negative");
			this.maxEntries = maxEntries;
			this.maxWeight = maxWeight;
		}

		private CanonicalText get(Object key) {
			return cacheableCanonicalType(key) ? values.get(key) : null;
		}

		private record PreparedGeneration(IdentityHashMap<Object,CanonicalText> values,
			IdentityHashMap<Object,Boolean> descriptors, long weight) { }

		private PreparedGeneration prepareGeneration(Object key, CanonicalText value) {
			if(maxEntries == 0)
				return null;
			long weight = ENTRY_OVERHEAD + LEDGER_BASE_OVERHEAD;
			if(weight > maxWeight)
				return null;
			IdentityHashMap<Object,Boolean> staged = new IdentityHashMap<>();
			ArrayDeque<CanonicalPieceIterator> pending = new ArrayDeque<>();
			Object next = value;
			while(true) {
				if(!staged.containsKey(next)) {
					long additional = descriptorWeight(next);
					if(additional > maxWeight - weight)
						return null;
					weight += additional;
					staged.put(next, Boolean.TRUE);
					if(next instanceof CanonicalText text && text.pieces.length != 0)
						pending.addLast(new CanonicalPieceIterator(text));
				}
				while(!pending.isEmpty() && !pending.getLast().hasNext())
					pending.removeLast();
				if(pending.isEmpty())
					break;
				next = pending.getLast().next();
			}
			IdentityHashMap<Object,CanonicalText> stagedValues = new IdentityHashMap<>();
			stagedValues.put(key, value);
			return new PreparedGeneration(stagedValues, staged, weight);
		}

		private void replaceGenerationIfAloneFits(Object key, CanonicalText value) {
			PreparedGeneration prepared = prepareGeneration(key, value);
			if(prepared == null)
				return;
			values = prepared.values();
			retainedDescriptors = prepared.descriptors();
			retainedWeight = prepared.weight();
		}

		private void retain(Object key, CanonicalText value) {
			if(!cacheableCanonicalType(key) || values.containsKey(key))
				return;
			if(values.size() >= maxEntries) {
				replaceGenerationIfAloneFits(key, value);
				return;
			}
			long remaining = maxWeight - retainedWeight;
			long weight = ENTRY_OVERHEAD + (values.isEmpty() ? LEDGER_BASE_OVERHEAD : 0);
			if(weight > remaining) {
				replaceGenerationIfAloneFits(key, value);
				return;
			}
			// A retained parent already paid for every descriptor reachable from it.
			// The recursive tree weight counts shared children repeatedly; this ledger
			// instead charges their identity union without interning any authority.
			if(!retainedDescriptors.containsKey(value)) {
				if(descriptorWeight(value) > remaining - weight) {
					replaceGenerationIfAloneFits(key, value);
					return;
				}
				IdentityHashMap<Object,Boolean> staged = new IdentityHashMap<>();
				ArrayDeque<CanonicalPieceIterator> pending = new ArrayDeque<>();
				Object next = value;
				while(true) {
					if(!retainedDescriptors.containsKey(next) && !staged.containsKey(next)) {
						long additional = descriptorWeight(next);
						if(additional > remaining - weight) {
							replaceGenerationIfAloneFits(key, value);
							return; // Failed admission must not leave a partial retained ledger.
						}
						weight += additional;
						staged.put(next, Boolean.TRUE);
						if(next instanceof CanonicalText text && text.pieces.length != 0)
							pending.addLast(new CanonicalPieceIterator(text));
					}
					while(!pending.isEmpty() && !pending.getLast().hasNext())
						pending.removeLast();
					if(pending.isEmpty())
						break;
					next = pending.getLast().next();
				}
				retainedDescriptors.putAll(staged);
			}
			values.put(key, value);
			retainedWeight += weight;
		}

		/**
		 * Conservative descriptor model, not an exact heap bound: charge the node,
		 * list/piece references or UTF-16 literal, plus ledger/staging table slack
		 * and traversal overhead. Authority-key graphs and pre-existing scope/map
		 * objects remain outside this estimate. Recursive append-session accounting
		 * still uses CanonicalText.retainedWeight unchanged.
		 */
		private static long descriptorWeight(Object descriptor) {
			if(descriptor instanceof String literal)
				return LEDGER_IDENTITY_OVERHEAD + CanonicalText.LITERAL_OVERHEAD + 2L * literal.length();
			CanonicalText text = (CanonicalText)descriptor;
			return LEDGER_IDENTITY_OVERHEAD + CanonicalText.NODE_AND_LIST_OVERHEAD
				+ CanonicalText.PIECE_REFERENCE_OVERHEAD * text.pieces.length;
		}

		private void clear() {
			values.clear();
			retainedDescriptors.clear();
			retainedWeight = 0;
		}
	}

	static final class CanonicalTextScope implements AutoCloseable {
		private ScopedCanonicalTextCache parent;
		private final ScopedCanonicalTextCache cache;
		private boolean closed;

		private CanonicalTextScope(int maxEntries, long maxWeight) {
			parent = ACTIVE_CANONICAL_TEXT_CACHE.get();
			cache = new ScopedCanonicalTextCache(maxEntries, maxWeight);
			ACTIVE_CANONICAL_TEXT_CACHE.set(cache);
		}

		int retainedEntries() { return cache.values.size(); }
		long retainedWeight() { return cache.retainedWeight; }

		@Override public void close() {
			if(closed)
				return;
			if(ACTIVE_CANONICAL_TEXT_CACHE.get() != cache)
				throw new IllegalStateException("Canonical text scopes closed out of order");
			closed = true;
			ScopedCanonicalTextCache restored = parent;
			parent = null;
			if(restored == null)
				ACTIVE_CANONICAL_TEXT_CACHE.remove();
			else
				ACTIVE_CANONICAL_TEXT_CACHE.set(restored);
			cache.clear();
		}
	}

	static CanonicalTextScope beginCanonicalTextScope() {
		return beginCanonicalTextScope(
			ANALYSIS_CANONICAL_TEXT_MAX_ENTRIES, ANALYSIS_CANONICAL_TEXT_MAX_WEIGHT);
	}

	static CanonicalTextScope beginCanonicalTextScope(int maxEntries, long maxWeight) {
		return new CanonicalTextScope(maxEntries, maxWeight);
	}

	static boolean canonicalTextScopeActive() {
		return ACTIVE_CANONICAL_TEXT_CACHE.get() != null;
	}

	private static boolean cacheableCanonicalType(Object value) {
		return genericCanonicalType(value)
			|| value instanceof CandidateRuleKey
			|| value instanceof PlacementEmissionState
			|| value instanceof RelocationActionKey;
	}

	private static boolean genericCanonicalType(Object value) {
		return value instanceof PlacementProofKey
			|| value instanceof CandidateRealizationReference
			|| value instanceof CandidateRealizationInputBinding
			|| value instanceof PlacementRealizationKey
			|| value instanceof CandidateRealizationSupportClause
			|| value instanceof CandidateEmissionRealization
			|| value instanceof TransientCompatibilityProof
			|| value instanceof TransientPlacementCompatibility;
	}

	/** Public immutable facade over the canonical segmented UTF-16 representation. */
	public static final class NormalizedText implements Comparable<NormalizedText> {
		private final CanonicalText text;
		private volatile String materialized;

		private NormalizedText(CanonicalText text) { this.text = text; }

		public static NormalizedText literal(String value) {
			return new NormalizedText(CanonicalText.literal(Objects.requireNonNull(value, "value")));
		}

		public int length() { return text.length; }

		public boolean isBlank() {
			CanonicalTextCursor cursor = new CanonicalTextCursor();
			cursor.reset(text);
			while(cursor.text() != null) {
				String literal = cursor.text();
				for(int index = cursor.offset(); index < literal.length(); index++)
					if(!Character.isWhitespace(literal.charAt(index)))
						return false;
				cursor.skipText();
			}
			return true;
		}

		public void appendTo(java.util.function.Consumer<String> consumer) {
			Objects.requireNonNull(consumer, "consumer");
			CanonicalTextCursor cursor = new CanonicalTextCursor();
			cursor.reset(text);
			while(cursor.text() != null) {
				String literal = cursor.text();
				consumer.accept(cursor.offset() == 0 ? literal : literal.substring(cursor.offset()));
				cursor.skipText();
			}
		}

		public String materialize() {
			String value = materialized;
			if(value != null)
				return value;
			StringBuilder builder = new StringBuilder(text.length);
			appendTo(builder::append);
			value = builder.toString();
			materialized = value;
			return value;
		}

		@Override public int compareTo(NormalizedText that) {
			return text.compareTo(Objects.requireNonNull(that, "that").text);
		}

		@Override public boolean equals(Object other) {
			return this == other || other instanceof NormalizedText that
				&& text.length == that.text.length && compareTo(that) == 0;
		}

		@Override public int hashCode() {
			return text.stringHash();
		}

		@Override public String toString() { return materialize(); }
	}

	/** Returns one thread-confined exact UTF-16 comparator with reusable traversal cursors. */
	static java.util.Comparator<NormalizedText> normalizedTextComparator() {
		return new ReusableNormalizedTextComparator();
	}

	private static final class ReusableNormalizedTextComparator
		implements java.util.Comparator<NormalizedText> {
		private final CanonicalTextComparison comparison = new CanonicalTextComparison();

		@Override public int compare(NormalizedText left, NormalizedText right) {
			try {
				Objects.requireNonNull(left, "left normalized text");
				Objects.requireNonNull(right, "right normalized text");
				return comparison.compare(left.text, right.text);
			}
			finally {
				comparison.clear();
			}
		}
	}

	/**
	 * Opt-in, invocation-local replay of small immutable signature subtrees.
	 * Only consumers insensitive to UTF-16 chunk boundaries may use this session;
	 * ordinary appendTo and canonical comparisons keep their existing traversal.
	 * Admission limits retention, never the accepted text. The byte charge is a
	 * conservative descriptor estimate, not a measurement of JVM heap occupancy.
	 * Sessions are thread-confined; they do support same-thread consumer reentry.
	 */
	public static final class NormalizedTextAppendSession {
		private final Map<CanonicalText,String> chunks = new IdentityHashMap<>();
		private final int maximumEntryCharacters;
		private final int maximumEntries;
		private final long maximumRetainedBytes;
		private long retainedBytes;

		public NormalizedTextAppendSession(int maximumEntryCharacters, int maximumEntries,
			long maximumRetainedBytes) {
			if(maximumEntryCharacters < 0 || maximumEntries < 0 || maximumRetainedBytes < 0)
				throw new IllegalArgumentException("Normalized text replay limits must be nonnegative");
			this.maximumEntryCharacters = maximumEntryCharacters;
			this.maximumEntries = maximumEntries;
			this.maximumRetainedBytes = maximumRetainedBytes;
		}

		public void appendTo(NormalizedText value, java.util.function.Consumer<String> consumer) {
			Objects.requireNonNull(value, "value");
			Objects.requireNonNull(consumer, "consumer");
			// Do not spend the cache on one-shot top-level alternatives. Local frames
			// also keep deep ropes stack-safe and permit consumer reentry/exceptions.
			ArrayDeque<CanonicalPieceIterator> pending = new ArrayDeque<>();
			pending.addLast(new CanonicalPieceIterator(value.text));
			while(!pending.isEmpty()) {
				var pieces = pending.getLast();
				if(!pieces.hasNext()) {
					pending.removeLast();
					continue;
				}
				Object piece = pieces.next();
				if(piece instanceof String literal)
					consumer.accept(literal);
				else {
					CanonicalText child = (CanonicalText)piece;
					String replay = chunk(child);
					if(replay != null)
						consumer.accept(replay);
					else
						pending.addLast(new CanonicalPieceIterator(child));
				}
			}
		}

		private String chunk(CanonicalText text) {
			String cached = chunks.get(text);
			if(cached != null)
				return cached;
			long weight = CanonicalText.saturatedAdd(text.retainedWeight, 64L + 2L * text.length);
			if(text.length == 0 || text.length > maximumEntryCharacters
				|| chunks.size() >= maximumEntries || weight > maximumRetainedBytes - retainedBytes)
				return null;
			StringBuilder flattened = new StringBuilder(text.length);
			new NormalizedText(text).appendTo(flattened::append);
			String result = flattened.toString();
			chunks.put(text, result);
			retainedBytes += weight;
			return result;
		}
	}

	/** Builder for normalized text that retains shared child segments by identity. */
	public static final class NormalizedTextBuilder {
		private final CanonicalTextBuilder builder = new CanonicalTextBuilder();
		public NormalizedTextBuilder append(String value) {
			builder.append(Objects.requireNonNull(value, "value"));
			return this;
		}
		public NormalizedTextBuilder append(NormalizedText value) {
			builder.append(Objects.requireNonNull(value, "value").text);
			return this;
		}
		public NormalizedText build() { return new NormalizedText(builder.build()); }
	}

	/** Identity-scoped adapter for existing canonical placement signature ropes. */
	public static final class NormalizedTextContext {
		private final CanonicalTextContext context = new CanonicalTextContext();
		public NormalizedText candidateRule(CandidateRuleKey value) {
			return new NormalizedText(canonicalRuleOrderingText(
				Objects.requireNonNull(value, "value"), context));
		}
		public NormalizedText realizationKey(PlacementRealizationKey value) {
			return new NormalizedText(canonicalOrderingKey(
				Objects.requireNonNull(value, "value"), context));
		}
		public NormalizedText supportClause(CandidateRealizationSupportClause value) {
			return new NormalizedText(canonicalOrderingKey(
				Objects.requireNonNull(value, "value"), context));
		}
		public NormalizedText emissionRealization(CandidateEmissionRealization value) {
			return new NormalizedText(canonicalOrderingKey(
				Objects.requireNonNull(value, "value"), context));
		}
		public NormalizedText binding(CandidateRealizationInputBinding value) {
			return new NormalizedText(canonicalOrderingKey(
				Objects.requireNonNull(value, "value"), context));
		}
	}

	/** Compares the exact UTF-16 lexical bytes produced by PlacementIdentity.fields without concatenation. */
	static int compareLengthPrefixedFieldSequences(List<String> left, List<String> right) {
		int common = Math.min(left.size(), right.size());
		for(int index = 0; index < common; index++) {
			String leftValue = Objects.requireNonNull(left.get(index), "left field");
			String rightValue = Objects.requireNonNull(right.get(index), "right field");
			int order = (Integer.toString(leftValue.length()) + ':').compareTo(
				Integer.toString(rightValue.length()) + ':');
			if(order != 0)
				return order;
			order = leftValue.compareTo(rightValue);
			if(order != 0)
				return order;
		}
		return Integer.compare(left.size(), right.size());
	}

	private static CanonicalText canonicalOrderingKey(Object value, CanonicalTextContext context) {
		if(!genericCanonicalType(value))
			throw new IllegalArgumentException("Unsupported canonical comparable type "
				+ value.getClass().getName());
		CanonicalText cached = context.get(value);
		if(cached != null)
			return cached;
		CanonicalText computed;
		if(value instanceof PlacementProofKey proof)
			computed = canonicalProofOrderingText(proof, context);
		else if(value instanceof CandidateRealizationReference reference)
			computed = canonicalReferenceOrderingText(reference, context);
		else if(value instanceof CandidateRealizationInputBinding binding)
			computed = canonicalBindingOrderingText(binding, context);
		else if(value instanceof PlacementRealizationKey realizationKey)
			computed = canonicalRealizationKeyOrderingText(realizationKey, context);
		else if(value instanceof CandidateRealizationSupportClause clause)
			computed = canonicalClauseOrderingText(clause, context);
		else if(value instanceof CandidateEmissionRealization realization)
			computed = canonicalRealizationOrderingText(realization, context);
		else if(value instanceof TransientCompatibilityProof proof)
			computed = canonicalTransientProofOrderingText(proof, context);
		else if(value instanceof TransientPlacementCompatibility compatibility)
			computed = canonicalTransientCompatibilityOrderingText(compatibility, context);
		else
			throw new IllegalArgumentException("Unsupported canonical comparable type "
				+ value.getClass().getName());
		return context.put(value, computed);
	}

	/** Package-visible differential-test seam for the exact segmented sort contract. */
	static int compareCanonicalOrdering(Object left, Object right) {
		CanonicalTextContext context = new CanonicalTextContext();
		return canonicalOrderingKey(Objects.requireNonNull(left, "left canonical value"), context)
			.compareTo(canonicalOrderingKey(Objects.requireNonNull(right, "right canonical value"), context));
	}

	/** One sort-scoped rope cache; avoids rebuilding structural text on every comparator call. */
	static <T> java.util.Comparator<T> canonicalComparator() {
		CanonicalTextContext context = new CanonicalTextContext();
		CanonicalTextComparison comparison = new CanonicalTextComparison();
		return (left, right) -> comparison.compare(canonicalOrderingKey(
			Objects.requireNonNull(left, "left canonical value"), context), canonicalOrderingKey(
				Objects.requireNonNull(right, "right canonical value"), context));
	}

	private static CanonicalText canonicalTransientProofOrderingText(
		TransientCompatibilityProof proof, CanonicalTextContext context) {
		CanonicalTextBuilder text = new CanonicalTextBuilder()
			.append(proof.sourceAnchor() == null ? "-" : proof.sourceAnchor().normalizedSignature())
			.append("|reader=")
			.append(proof.readerAnchor() == null ? "-" : proof.readerAnchor().normalizedSignature())
			.append("|nativePool=")
			.append(proof.nativeWorkerPoolWitness() == null ? "-" : proof.nativeWorkerPoolWitness().normalizedSignature());
		if(proof.nativeWorkerPoolWitness() != null && !proof.nativeWorkerPoolLayoutExact())
			text.append("|nativePoolLayout=dynamic");
		text.append("|proofs=[");
		for(int index = 0; index < proof.dependencies().size(); index++) {
			if(index > 0) text.append(", ");
			text.append(canonicalOrderingKey(proof.dependencies().get(index), context));
		}
		return text.append("]").build();
	}

	private static CanonicalText canonicalTransientCompatibilityOrderingText(
		TransientPlacementCompatibility compatibility, CanonicalTextContext context) {
		return new CanonicalTextBuilder()
			.append(canonicalOrderingKey(compatibility.sourceRealization(), context)).append("|reader=")
			.append(canonicalOrderingKey(compatibility.readerRealization(), context)).append("|sourceInput=")
			.append(compatibility.sourceInput().normalizedSignature()).append("|readerInput=")
			.append(compatibility.readerInput().normalizedSignature()).append("|proof=")
			.append(canonicalOrderingKey(compatibility.proof(), context)).build();
	}

	private static CanonicalText canonicalClauseOrderingText(
		CandidateRealizationSupportClause clause, CanonicalTextContext context) {
		return canonicalClauseOrderingText(clause.proofDependencies(), clause.inputBindings(),
			clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact(), context);
	}

	private static CanonicalText canonicalClauseOrderingText(
		List<PlacementProofKey> proofs, List<CandidateRealizationInputBinding> bindings,
		DurableAnchorKey nativeWorkerPoolWitness, boolean nativeWorkerPoolLayoutExact,
		CanonicalTextContext context) {
		CanonicalTextBuilder text = new CanonicalTextBuilder().append("proofs=[");
		for(int index = 0; index < proofs.size(); index++) {
			if(index > 0) text.append(", ");
			text.append(canonicalOrderingKey(proofs.get(index), context));
		}
		text.append("]|inputs=[");
		for(int index = 0; index < bindings.size(); index++) {
			if(index > 0) text.append(", ");
			text.append(canonicalOrderingKey(bindings.get(index), context));
		}
		text.append("]|nativePool=");
		if(nativeWorkerPoolWitness == null)
			text.append("-");
		else {
			text.append(nativeWorkerPoolWitness.normalizedSignature());
			if(!nativeWorkerPoolLayoutExact)
				text.append("|nativePoolLayout=dynamic");
		}
		return text.build();
	}

	private static CanonicalText canonicalIndexedClauseOrderingText(
		IndexedSupportClauses relation, int row, CanonicalTextContext context) {
		return canonicalClauseOrderingText(relation.proofsAt(row), relation.bindingsAt(row),
			relation.witnessAt(row), relation.layoutExactAt(row), context);
	}

	private static CanonicalText canonicalRealizationOrderingText(
		CandidateEmissionRealization realization, CanonicalTextContext context) {
		CanonicalTextBuilder text = new CanonicalTextBuilder()
			.append(canonicalOrderingKey(realization.key(), context)).append("|support=[");
		if(realization.supportClauses() instanceof IndexedSupportClauses indexed) {
			for(int row = 0; row < indexed.size(); row++) {
				if(row > 0) text.append(", ");
				text.append(canonicalIndexedClauseOrderingText(indexed, row, context));
			}
			return text.append("]").build();
		}
		List<CanonicalText> retained = retainedCanonicalOrderingKeys(realization.supportClauses());
		for(int index = 0; index < realization.supportClauses().size(); index++) {
			if(index > 0) text.append(", ");
			// Reuse descriptors already owned by this immutable canonical list.
			// Singleton/trusted lists without descriptors retain the cold path.
			text.append(retained == null
				? canonicalOrderingKey(realization.supportClauses().get(index), context) : retained.get(index));
		}
		return text.append("]").build();
	}

	private static CanonicalText canonicalProofOrderingText(PlacementProofKey proof,
		CanonicalTextContext context) {
		return new CanonicalTextBuilder().appendFields(
			proof.kind().name(),
			proof.owner() == null ? "-" : proof.owner().normalizedSignature(),
			proof.authoritySignature()).build();
	}

	private static CanonicalText canonicalReferenceOrderingText(
		CandidateRealizationReference reference, CanonicalTextContext context) {
		return new CanonicalTextBuilder().appendFields(
			canonicalRuleOrderingText(reference.rule(), context),
			canonicalOrderingKey(reference.realization(), context)).build();
	}

	private static CanonicalText canonicalRuleOrderingText(CandidateRuleKey rule,
		CanonicalTextContext context) {
		CanonicalText cached = context.get(rule);
		if(cached != null)
			return cached;
		CanonicalTextBuilder text = new CanonicalTextBuilder()
			.append(rule.parentOccurrence().normalizedSignature()).append("|inputs=[");
		for(int index = 0; index < rule.orderedInputs().size(); index++) {
			if(index > 0) text.append(", ");
			text.append(rule.orderedInputs().get(index).normalizedSignature());
		}
		return context.put(rule, text.append("]").build());
	}

	private static CanonicalText canonicalRealizationKeyOrderingText(PlacementRealizationKey key,
		CanonicalTextContext context) {
		return new CanonicalTextBuilder().appendFields(
			canonicalEmissionOrderingText(key.emissionState(), context),
			key.layoutKind().name(),
			key.durableAnchor() == null ? "-" : key.durableAnchor().normalizedSignature(),
			key.nativeLineage() == null ? "-" : key.nativeLineage()).build();
	}

	private static CanonicalText canonicalEmissionOrderingText(PlacementEmissionState emission,
		CanonicalTextContext context) {
		CanonicalText cached = context.get(emission);
		if(cached != null)
			return cached;
		return context.put(emission, new CanonicalTextBuilder()
			.append(emission.placementState().normalizedSignature())
			.append("|derivedFedFout=").append(Boolean.toString(emission.derivedFedFout())).build());
	}

	private static CanonicalText canonicalBindingOrderingText(
		CandidateRealizationInputBinding binding, CanonicalTextContext context) {
		Object action = binding.relocationAction() == null ? "-"
			: canonicalRelocationActionOrderingText(binding.relocationAction(), context);
		return new CanonicalTextBuilder().appendFields(
			Integer.toString(binding.inputPosition()),
			canonicalOrderingKey(binding.source(), context),
			binding.kind().name(), action).build();
	}

	private static CanonicalText canonicalRelocationActionOrderingText(RelocationActionKey action,
		CanonicalTextContext context) {
		CanonicalText cached = context.get(action);
		if(cached != null)
			return cached;
		CanonicalTextBuilder consumers = new CanonicalTextBuilder();
		for(int index = 0; index < action.compatibleConsumers().size(); index++) {
			if(index > 0) consumers.append(",");
			String consumer = action.compatibleConsumers().get(index).normalizedSignature();
			consumers.append(Integer.toString(consumer.length())).append(":").append(consumer);
		}
		return context.put(action, new CanonicalTextBuilder().appendFields(
			action.sourceValueVersion().normalizedSignature(),
			action.targetPlacement().normalizedSignature(),
			action.materializationFType().name(),
			action.durableAnchor().normalizedSignature(),
			action.statementBlockScope(), consumers.build()).build());
	}

	static <T extends Comparable<? super T>> List<T> sharedCanonicalComparableList(
		java.util.Collection<T> values, String label) {
		List<T> canonical = canonicalComparableList(values, label);
		return canonical instanceof SharedCanonicalList<?> ? canonical
			: new SharedCanonicalList<>(canonical);
	}

	static <T extends Comparable<? super T>> List<T> sharedAlreadyCanonicalComparableList(
		List<T> values, String label) {
		Objects.requireNonNull(values, label + "s");
		if(values instanceof SharedCanonicalList<?>)
			return values;
		List<T> copy = new ArrayList<>(values.size());
		for(T value : values)
			copy.add(Objects.requireNonNull(value, label));
		for(int index = 1; index < copy.size(); index++)
			if(copy.get(index - 1).equals(copy.get(index)))
				throw new IllegalArgumentException("Duplicate " + label);
		return new SharedCanonicalList<>(List.copyOf(copy));
	}

	/** Immutable List semantics with a cached hash, without canonical-order marker authority. */
	@SuppressWarnings("unchecked")
	static <T> List<T> sharedImmutableList(java.util.Collection<T> values, String label) {
		Objects.requireNonNull(values, label + "s");
		if(values instanceof CachedImmutableList<?>)
			return (List<T>)values;
		List<T> copy = new ArrayList<>(values.size());
		for(T value : values)
			copy.add(Objects.requireNonNull(value, label));
		return new CachedImmutableList<>(copy);
	}

	private static List<CanonicalText> retainedCanonicalOrderingKeys(List<?> values) {
		return values instanceof SharedCanonicalList<?> shared ? shared.orderingKeys : null;
	}

	private static List<CanonicalText> canonicalOrderingKeys(List<?> values,
		CanonicalTextContext context) {
		if(values instanceof IndexedSupportClauses indexed) {
			List<CanonicalText> orderingKeys = new ArrayList<>(indexed.size());
			for(int row = 0; row < indexed.size(); row++)
				orderingKeys.add(canonicalIndexedClauseOrderingText(indexed, row, context));
			return List.copyOf(orderingKeys);
		}
		List<CanonicalText> orderingKeys = new ArrayList<>(values.size());
		for(Object value : values)
			orderingKeys.add(canonicalOrderingKey(value, context));
		return List.copyOf(orderingKeys);
	}

	public enum InputPresence { ABSENT_LOCAL, PRESENT }
	public enum CoordinatorInputAccess { PAYLOAD, FEDERATION_MAP_METADATA }

	/** Neutral explicit absence/value state; present-null cannot be represented. */
	public record CandidateInputState(InputPresence presence, FType fType) {
		public CandidateInputState {
			Objects.requireNonNull(presence, "presence");
			if(presence == InputPresence.ABSENT_LOCAL && fType != null
				|| presence == InputPresence.PRESENT && fType == null)
				throw new IllegalArgumentException("Candidate input presence and FType differ");
		}
		public static CandidateInputState absentLocal() {
			return new CandidateInputState(InputPresence.ABSENT_LOCAL, null);
		}
		public static CandidateInputState present(FType fType) {
			return new CandidateInputState(InputPresence.PRESENT, Objects.requireNonNull(fType, "fType"));
		}
		public boolean present() { return presence == InputPresence.PRESENT; }
		public String normalizedSignature() {
			return presence.name() + ':' + (fType == null ? "-" : fType.name());
		}
	}

	/** Exact analysis-owned parent plus edge-position-ordered candidate input states. */
	public record CandidateRuleKey(CompiledHopKey parentOccurrence,
		List<CandidateInputState> orderedInputs) {
		public CandidateRuleKey {
			SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
			if(metrics != null) metrics.recordCandidateRuleKeyCreated();
			Objects.requireNonNull(parentOccurrence, "parentOccurrence");
			Objects.requireNonNull(orderedInputs, "orderedInputs");
			for(int i = 0; i < orderedInputs.size(); i++)
				Objects.requireNonNull(orderedInputs.get(i), "orderedInputs[" + i + "]");
			orderedInputs = List.copyOf(orderedInputs);
		}
		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof CandidateRuleKey that)
				|| !parentOccurrence.equals(that.parentOccurrence)
				|| orderedInputs.size() != that.orderedInputs.size())
				return false;
			// The constructor snapshots a random-access list. Keep structural record
			// equality without allocating a List iterator for every candidate lookup.
			for(int index = 0; index < orderedInputs.size(); index++)
				if(!orderedInputs.get(index).equals(that.orderedInputs.get(index)))
					return false;
			return true;
		}
		public String normalizedSignature() {
			String cached = PlacementIdentity.cachedSignature(this);
			return cached != null ? cached : PlacementIdentity.rememberSignature(this,
				parentOccurrence.normalizedSignature() + "|inputs=" + orderedInputs.stream()
					.map(CandidateInputState::normalizedSignature).toList());
		}
	}

	public record CandidateConsumerProfileKey(CompiledHopKey consumerOccurrence, int inputPosition) {
		public CandidateConsumerProfileKey {
			Objects.requireNonNull(consumerOccurrence, "consumerOccurrence");
			if(inputPosition < 0)
				throw new IllegalArgumentException("Consumer input position must be non-negative");
		}
	}

	/** Explicit immutable original-occurrence candidate domain, frozen before synthetic boundary expansion. */
	public static final class CandidateRuleDomain {
		private final String analysisFingerprint;
		private final List<CandidateRuleKey> orderedRuleKeys;
		private final List<CandidateConsumerProfileKey> orderedConsumerKeys;
		private final Map<CompiledHopKey,Boolean> parentsByIdentity;
		private final List<CandidatePrivacyInputPruning> privacyPrunedInputs;
		private final Map<CompiledHopKey,CandidatePrivacyInputPruning> pruningByParent;

		public CandidateRuleDomain(String analysisFingerprint, List<CandidateRuleKey> ruleKeys,
			List<CandidateConsumerProfileKey> consumerKeys) {
			this(analysisFingerprint, ruleKeys, consumerKeys, List.of(), List.of());
		}

		CandidateRuleDomain(String analysisFingerprint, List<CandidateRuleKey> ruleKeys,
			List<CandidateConsumerProfileKey> consumerKeys, List<CandidatePrivacyInputPruning> pruning) {
			this(analysisFingerprint, ruleKeys, consumerKeys, pruning, List.of());
		}

		CandidateRuleDomain(String analysisFingerprint, List<CandidateRuleKey> ruleKeys,
			List<CandidateConsumerProfileKey> consumerKeys, List<CandidatePrivacyInputPruning> pruning,
			List<CpRuleFamily> cpFamilies) {
			this(analysisFingerprint, ruleKeys, consumerKeys, pruning, cpFamilies, List.of());
		}

		CandidateRuleDomain(String analysisFingerprint, List<CandidateRuleKey> ruleKeys,
			List<CandidateConsumerProfileKey> consumerKeys, List<CandidatePrivacyInputPruning> pruning,
			List<CpRuleFamily> cpFamilies, List<CandidateRuleRelation> candidateRelations) {
			if(analysisFingerprint == null || analysisFingerprint.isBlank())
				throw new IllegalArgumentException("Candidate domain fingerprint must not be blank");
			this.analysisFingerprint = analysisFingerprint;
			orderedRuleKeys = copyDistinctRuleKeys(ruleKeys);
			orderedConsumerKeys = copyDistinctConsumerKeys(consumerKeys);
			Map<CompiledHopKey,Boolean> parents = new IdentityHashMap<>();
			for(CandidateRuleKey key : orderedRuleKeys)
				parents.put(key.parentOccurrence(), Boolean.TRUE);
			for(CpRuleFamily family : cpFamilies)
				parents.put(Objects.requireNonNull(family, "CP rule family").parent(), Boolean.TRUE);
			for(CandidateRuleRelation relation : candidateRelations)
				parents.put(Objects.requireNonNull(relation, "candidate rule relation").parent(), Boolean.TRUE);
			privacyPrunedInputs = List.copyOf(pruning);
			Map<CompiledHopKey,CandidatePrivacyInputPruning> byParent = new IdentityHashMap<>();
			for(CandidatePrivacyInputPruning evidence : privacyPrunedInputs) {
				CompiledHopKey owner = evidence.consumer().occurrence();
				if(byParent.put(owner, evidence) != null)
					throw new IllegalArgumentException("Multiple privacy domain revisions for one consumer");
				parents.put(owner, Boolean.TRUE);
			}
			pruningByParent = Collections.unmodifiableMap(byParent);
			for(CandidateConsumerProfileKey key : orderedConsumerKeys)
				if(!parents.containsKey(key.consumerOccurrence()))
					throw new IllegalArgumentException("Consumer profile owner is outside the candidate domain");
			parentsByIdentity = Collections.unmodifiableMap(parents);
		}

		public String analysisFingerprint() { return analysisFingerprint; }
		public List<CandidateRuleKey> orderedRuleKeys() { return orderedRuleKeys; }
		public List<CandidateConsumerProfileKey> orderedConsumerKeys() { return orderedConsumerKeys; }
		public boolean containsExactParent(CompiledHopKey key) { return parentsByIdentity.containsKey(key); }
		public List<CandidatePrivacyInputPruning> privacyPrunedInputs() { return privacyPrunedInputs; }
		boolean privacyRejects(CompiledHopKey owner, List<CandidateInputState> inputs) {
			CandidatePrivacyInputPruning evidence = pruningByParent.get(owner);
			return evidence != null && evidence.rejects(inputs);
		}

		private static List<CandidateRuleKey> copyDistinctRuleKeys(List<CandidateRuleKey> source) {
			Objects.requireNonNull(source, "ruleKeys");
			List<CandidateRuleKey> copied = new java.util.ArrayList<>(source.size());
			Map<CompiledHopKey,Set<List<CandidateInputState>>> byParent = new IdentityHashMap<>();
			for(CandidateRuleKey key : source) {
				Objects.requireNonNull(key, "candidate rule domain key");
				Set<List<CandidateInputState>> inputs = byParent.computeIfAbsent(key.parentOccurrence(),
					ignored -> new java.util.HashSet<>());
				if(!inputs.add(key.orderedInputs()))
					throw new IllegalArgumentException("Duplicate candidate rule domain key");
				copied.add(key);
			}
			return List.copyOf(copied);
		}

		private static List<CandidateConsumerProfileKey> copyDistinctConsumerKeys(
			List<CandidateConsumerProfileKey> source) {
			Objects.requireNonNull(source, "consumerKeys");
			List<CandidateConsumerProfileKey> copied = new java.util.ArrayList<>(source.size());
			Map<CompiledHopKey,java.util.Set<Integer>> byConsumer = new IdentityHashMap<>();
			for(CandidateConsumerProfileKey key : source) {
				Objects.requireNonNull(key, "candidate consumer domain key");
				if(!byConsumer.computeIfAbsent(key.consumerOccurrence(), ignored -> new java.util.LinkedHashSet<>())
					.add(key.inputPosition()))
					throw new IllegalArgumentException("Duplicate candidate consumer profile domain key");
				copied.add(key);
			}
			return List.copyOf(copied);
		}
	}

	/** Immutable copy of one rule note; no mutable oracle capability object is retained. */
	public record CandidateRuleNote(org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode code,
		String message) {
		public CandidateRuleNote {
			Objects.requireNonNull(code, "code");
			message = message == null ? "" : message;
		}
	}

	/** Immutable primitive/enum projection of the exact rule capability result. */
	public record CandidateCapabilityFact(OpCategory category, String opcode, ExecType nativeExec,
		FederatedOutput nativeOutput, FType nativeFoutFType,
		org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode reasonCode, String detail,
		List<CandidateRuleNote> notes) {
		public CandidateCapabilityFact {
			Objects.requireNonNull(category, "category");
			opcode = opcode == null ? "" : opcode;
			Objects.requireNonNull(nativeExec, "nativeExec");
			Objects.requireNonNull(nativeOutput, "nativeOutput");
			Objects.requireNonNull(reasonCode, "reasonCode");
			detail = detail == null ? "" : detail;
			notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
		}
	}

	/** Immutable copy of the shape facts consulted by the exact capability rule. */
	public record CandidateShapeProofFact(Map<String,String> consultedFacts, List<String> requiredFacts,
		List<String> missingRequiredFacts) {
		public CandidateShapeProofFact {
			consultedFacts = Collections.unmodifiableMap(new java.util.TreeMap<>(
				Objects.requireNonNull(consultedFacts, "consultedFacts")));
			requiredFacts = Objects.requireNonNull(requiredFacts, "requiredFacts").stream()
				.map(fact -> Objects.requireNonNull(fact, "required fact")).sorted().toList();
			missingRequiredFacts = Objects.requireNonNull(missingRequiredFacts, "missingRequiredFacts").stream()
				.map(fact -> Objects.requireNonNull(fact, "missing required fact")).sorted().toList();
		}
	}

	/** Immutable producer-profile evidence obtained in the same canonical builder combination. */
	public record CandidateProfileFact(List<FType> producerOutputs, String evaluationFailure) {
		public CandidateProfileFact {
			producerOutputs = List.copyOf(Objects.requireNonNull(producerOutputs, "producerOutputs"));
			evaluationFailure = evaluationFailure == null ? "" : evaluationFailure;
			if(!evaluationFailure.isEmpty() && !producerOutputs.isEmpty())
				throw new IllegalArgumentException("Failed profile evaluation cannot publish outputs");
		}
		public boolean available() { return evaluationFailure.isEmpty(); }
	}

	public enum CandidateEvaluationStatus { AVAILABLE, PRIVACY_EXCLUDED, RULE_ERROR, PROFILE_ERROR }

	public static final class CandidateConsumerProfileFact {
		private final CandidateConsumerProfileKey key;
		private final CandidateEvaluationStatus status;
		private final List<FType> allowedTargetTypes;
		private final String failureCode;

		public CandidateConsumerProfileFact(CandidateConsumerProfileKey key,
			CandidateEvaluationStatus status, List<FType> allowedTargetTypes, String failureCode) {
			this.key = Objects.requireNonNull(key, "key");
			this.status = Objects.requireNonNull(status, "status");
			this.allowedTargetTypes = List.copyOf(Objects.requireNonNull(allowedTargetTypes,
				"allowedTargetTypes"));
			this.failureCode = failureCode == null ? "" : failureCode;
			if(status == CandidateEvaluationStatus.RULE_ERROR
				|| status == CandidateEvaluationStatus.AVAILABLE && !this.failureCode.isEmpty()
				|| status == CandidateEvaluationStatus.PROFILE_ERROR
					&& (this.failureCode.isEmpty() || !this.allowedTargetTypes.isEmpty()))
				throw new IllegalArgumentException("Consumer profile status and evidence differ");
		}

		public CandidateConsumerProfileKey key() { return key; }
		public CandidateEvaluationStatus status() { return status; }
		public List<FType> allowedTargetTypes() { return allowedTargetTypes; }
		public String failureCode() { return failureCode; }
	}

	/** Primitive producer-scoped profile evidence for a consumer absent from the analysis occurrence graph. */
	public record DetachedConsumerProfileKey(CompiledHopKey producerOccurrence, int parentOrdinal,
		String normalizedConsumerSignature, List<Integer> producerInputPositions) {
		public DetachedConsumerProfileKey {
			Objects.requireNonNull(producerOccurrence, "producerOccurrence");
			if(parentOrdinal < 0)
				throw new IllegalArgumentException("Detached consumer parent ordinal must be non-negative");
			if(normalizedConsumerSignature == null || normalizedConsumerSignature.isBlank())
				throw new IllegalArgumentException("Detached consumer signature must not be blank");
			producerInputPositions = List.copyOf(Objects.requireNonNull(producerInputPositions,
				"producerInputPositions"));
			if(producerInputPositions.isEmpty())
				throw new IllegalArgumentException("Detached consumer must reference its producer");
			int previous = -1;
			for(int position : producerInputPositions) {
				if(position <= previous)
					throw new IllegalArgumentException("Detached consumer input positions must be strictly ordered");
				previous = position;
			}
		}
	}

	public record DetachedConsumerProfileFact(DetachedConsumerProfileKey key,
		CandidateEvaluationStatus status, List<FType> allowedTargetTypes, String failureCode) {
		public DetachedConsumerProfileFact {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(status, "status");
			allowedTargetTypes = List.copyOf(Objects.requireNonNull(allowedTargetTypes, "allowedTargetTypes"));
			failureCode = failureCode == null ? "" : failureCode;
			if(status == CandidateEvaluationStatus.RULE_ERROR
				|| status == CandidateEvaluationStatus.AVAILABLE && !failureCode.isEmpty()
				|| status == CandidateEvaluationStatus.PROFILE_ERROR
					&& (failureCode.isEmpty() || !allowedTargetTypes.isEmpty()))
				throw new IllegalArgumentException("Detached consumer profile status and evidence differ");
		}
	}

	/** One conjunctive proof/input-support route for an exact physical realization. */
	public static final class CandidateRealizationSupportClause
		implements Comparable<CandidateRealizationSupportClause> {
		private final List<PlacementProofKey> proofDependencies;
		private final List<CandidateRealizationInputBinding> inputBindings;
		private final DurableAnchorKey nativeWorkerPoolWitness;
		private final boolean nativeWorkerPoolLayoutExact;
		private final IndexedSupportClauses indexedRelation;
		private final int indexedRowOrdinal;
		private final int hash;

		public CandidateRealizationSupportClause(List<PlacementProofKey> proofDependencies,
			List<CandidateRealizationInputBinding> inputBindings) {
			this(proofDependencies, inputBindings, null, true);
		}
		public CandidateRealizationSupportClause(List<PlacementProofKey> proofDependencies,
			List<CandidateRealizationInputBinding> inputBindings,
			DurableAnchorKey nativeWorkerPoolWitness) {
			this(proofDependencies, inputBindings, nativeWorkerPoolWitness, true);
		}
		public CandidateRealizationSupportClause(List<PlacementProofKey> proofDependencies,
			List<CandidateRealizationInputBinding> inputBindings,
			DurableAnchorKey nativeWorkerPoolWitness, boolean nativeWorkerPoolLayoutExact) {
			SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
			if(metrics != null) metrics.recordSupportClauseCreated(false);
			this.proofDependencies = canonicalComparableList(proofDependencies, "realization proof dependency");
			this.inputBindings = canonicalComparableList(inputBindings, "realization input binding");
			if(nativeWorkerPoolWitness == null && !nativeWorkerPoolLayoutExact)
				throw new IllegalArgumentException("Dynamic native layout requires a worker-pool witness");
			if(nativeWorkerPoolWitness != null && proofDependencies.stream().noneMatch(proof ->
				proof.kind() == PlacementIdentity.PlacementProofKind.NATIVE_CONTINUITY
					&& proof.owner() != null))
				throw new IllegalArgumentException(
					"Native worker-pool witness requires owned native-continuity proof");
			this.nativeWorkerPoolWitness = nativeWorkerPoolWitness;
			this.nativeWorkerPoolLayoutExact = nativeWorkerPoolLayoutExact;
			indexedRelation = null;
			indexedRowOrdinal = -1;
			hash = supportClauseHash(this.proofDependencies, this.inputBindings,
				this.nativeWorkerPoolWitness, this.nativeWorkerPoolLayoutExact);
		}

		private CandidateRealizationSupportClause(IndexedSupportClauses relation, int row) {
			SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
			if(metrics != null) metrics.recordSupportClauseCreated(true);
			indexedRelation = Objects.requireNonNull(relation, "indexed support relation");
			indexedRowOrdinal = Objects.checkIndex(row, relation.size());
			// The immutable relation only admits previously constructed legal rows.
			// Keep dictionary-backed views, never recreate or revalidate their tuples.
			proofDependencies = relation.proofsAt(row);
			inputBindings = relation.bindingsAt(row);
			nativeWorkerPoolWitness = relation.witnessAt(row);
			nativeWorkerPoolLayoutExact = relation.layoutExactAt(row);
			hash = supportClauseHash(proofDependencies, inputBindings,
				nativeWorkerPoolWitness, nativeWorkerPoolLayoutExact);
		}

		static CandidateRealizationSupportClause indexed(IndexedSupportClauses relation, int row) {
			return new CandidateRealizationSupportClause(relation, row);
		}
		IndexedSupportClauses indexedRelation() { return indexedRelation; }
		int indexedRowOrdinal() { return indexedRowOrdinal; }
		public boolean isIndexed() { return indexedRelation != null; }
		/** Relation-scoped full-combination ID, including proof and native-pool metadata. */
		public java.math.BigInteger indexedCombinationId() {
			if(indexedRelation == null)
				throw new IllegalStateException("Support clause is not indexed");
			return indexedRelation.combinationIdAt(indexedRowOrdinal);
		}
		public List<PlacementProofKey> proofDependencies() { return proofDependencies; }
		public List<CandidateRealizationInputBinding> inputBindings() { return inputBindings; }
		public DurableAnchorKey nativeWorkerPoolWitness() { return nativeWorkerPoolWitness; }
		public boolean nativeWorkerPoolLayoutExact() { return nativeWorkerPoolLayoutExact; }

		@Override public boolean equals(Object other) {
			return this == other || other instanceof CandidateRealizationSupportClause that
				&& nativeWorkerPoolLayoutExact == that.nativeWorkerPoolLayoutExact
				&& Objects.equals(nativeWorkerPoolWitness, that.nativeWorkerPoolWitness)
				&& proofDependencies.equals(that.proofDependencies)
				&& inputBindings.equals(that.inputBindings);
		}
		private static int supportClauseHash(List<PlacementProofKey> proofs,
			List<CandidateRealizationInputBinding> bindings,
			DurableAnchorKey witness, boolean layoutExact) {
			// Preserve the former record hash, also used by Cartesian list hashing.
			int result = proofs.hashCode();
			result = 31 * result + bindings.hashCode();
			result = 31 * result + Objects.hashCode(witness);
			return 31 * result + Boolean.hashCode(layoutExact);
		}
		@Override public int hashCode() { return hash; }
		@Override public String toString() {
			return "CandidateRealizationSupportClause[proofDependencies=" + proofDependencies
				+ ", inputBindings=" + inputBindings + ", nativeWorkerPoolWitness=" + nativeWorkerPoolWitness
				+ ", nativeWorkerPoolLayoutExact=" + nativeWorkerPoolLayoutExact + "]";
		}
		public List<CandidateRealizationReference> requiredInputSupport() {
			List<CandidateRealizationReference> cached =
				PlacementIdentity.cachedRequiredInputSupport(this);
			return cached != null ? cached : PlacementIdentity.rememberRequiredInputSupport(this,
				inputBindings.stream().map(CandidateRealizationInputBinding::source)
					.distinct().sorted(PlacementAnalysis.canonicalComparator()).toList());
		}
		public String normalizedSignature() {
			String cached = PlacementIdentity.cachedSignature(this);
			return cached != null ? cached : PlacementIdentity.rememberSignature(this,
				"proofs=" + proofDependencies.stream().map(PlacementProofKey::normalizedSignature).toList()
				+ "|inputs=" + inputBindings.stream()
					.map(CandidateRealizationInputBinding::normalizedSignature).toList()
				+ "|nativePool=" + (nativeWorkerPoolWitness == null ? "-"
					: nativeWorkerPoolWitness.normalizedSignature())
				+ (nativeWorkerPoolWitness != null && !nativeWorkerPoolLayoutExact
					? "|nativePoolLayout=dynamic" : ""));
		}
		@Override public int compareTo(CandidateRealizationSupportClause that) {
			return compareCanonicalOrdering(this, that);
		}
	}

	/** One independent source choice in an exact Cartesian support axis. */
	public record IndependentSupportOption(CandidateRealizationSupportKey supportKey,
		CandidateRealizationInputBinding binding, DurableAnchorKey deliveredLayout) {
		public IndependentSupportOption(CandidateRealizationSupportKey supportKey,
			CandidateRealizationInputBinding binding) {
			this(supportKey, binding, binding.relocationAction() != null
				? binding.relocationAction().durableAnchor() : binding.source().realization().durableAnchor());
		}
		public IndependentSupportOption {
			Objects.requireNonNull(supportKey, "independent support key");
			Objects.requireNonNull(binding, "independent support binding");
		}
	}

	/** An exact support axis whose physical authority is invariant across its options. */
	public record IndependentSupportAxis(int inputPosition, CompiledHopKey sourceOwner,
		PlacementIdentity.CandidateInputBindingKind kind,
		PlacementIdentity.RelocationActionKey relocationAction,
		List<IndependentSupportOption> options) {
		public IndependentSupportAxis {
			if(inputPosition < 0)
				throw new IllegalArgumentException("Independent support position must be nonnegative");
			Objects.requireNonNull(sourceOwner, "independent support source owner");
			Objects.requireNonNull(kind, "independent support binding kind");
			if((kind == PlacementIdentity.CandidateInputBindingKind.RELOCATION)
				!= (relocationAction != null))
				throw new IllegalArgumentException(
					"Only relocation support axes carry a relocation action");
			options = List.copyOf(options);
			if(options.isEmpty())
				throw new IllegalArgumentException("Independent support axis must not be empty");
			DurableAnchorKey directAnchor = kind == PlacementIdentity.CandidateInputBindingKind.DIRECT
				? options.get(0).deliveredLayout() : null;
			if(kind == PlacementIdentity.CandidateInputBindingKind.DIRECT && directAnchor == null)
				throw new IllegalArgumentException("Direct support axis requires durable source layouts");
			for(IndependentSupportOption option : options) {
				CandidateRealizationInputBinding binding = option.binding();
				if(binding.inputPosition() != inputPosition
					|| binding.source().rule().parentOccurrence() != sourceOwner
					|| binding.kind() != kind
					|| binding.relocationAction() != relocationAction
					|| !option.supportKey().equals(
						CandidateSelections.requiredInputSupportIdentity(binding.source())))
					throw new IllegalArgumentException(
						"Independent support option differs from its axis authority");
				if(directAnchor != null && (option.deliveredLayout() == null
					|| !PlacementIdentity.samePhysicalLayout(directAnchor,
						option.deliveredLayout())))
					throw new IllegalArgumentException(
						"Direct support axis mixes physical source layouts");
			}
		}

		/** Exact layout delivered by every option on this independent axis. */
		public DurableAnchorKey deliveredAnchor() {
			return relocationAction != null ? relocationAction.durableAnchor()
				: options.get(0).deliveredLayout();
		}
	}

	/** Non-enumerating storage view of every factorized support relation. */
	public static final class FactorizedSupportProduct {
		private final FactorizedSupportClauses relation;

		FactorizedSupportProduct(FactorizedSupportClauses relation) {
			this.relation = Objects.requireNonNull(relation, "factorized support relation");
		}

		public List<PlacementProofKey> proofDependencies() { return relation.proofs(); }
		public List<List<CandidateRealizationInputBinding>> factors() {
			return relation.factors();
		}
		public DurableAnchorKey nativeWorkerPoolWitness() {
			return relation.nativeWorkerPoolWitness();
		}
		public boolean nativeWorkerPoolLayoutExact() {
			return relation.nativeWorkerPoolLayoutExact();
		}
		public int logicalClauseCount() { return relation.size(); }
	}

	/**
	 * Immutable physical-authority region of a support relation. Axes describe
	 * source choices; a correlated region additionally retains its admitted row
	 * indices. The backing relation owns the final selected clause identity.
	 */
	public static final class IndependentSupportProduct {
		private final List<CandidateRealizationSupportClause> relation;
		private final List<IndependentSupportAxis> axes;
		private final int[] admittedRows;
		private final boolean correlated;

		IndependentSupportProduct(FactorizedSupportClauses relation,
			List<IndependentSupportAxis> axes) {
			this.relation = Objects.requireNonNull(relation, "independent support relation");
			this.axes = List.copyOf(axes);
			admittedRows = null;
			correlated = false;
		}

		IndependentSupportProduct(IndexedSupportClauses relation,
			List<IndependentSupportAxis> axes) {
			this(relation, axes, null);
		}

		IndependentSupportProduct(IndexedSupportClauses relation,
			List<IndependentSupportAxis> axes, int[] admittedRows) {
			this.relation = Objects.requireNonNull(relation, "indexed support relation");
			this.axes = List.copyOf(axes);
			this.admittedRows = admittedRows == null ? null : admittedRows.clone();
			correlated = !coversOwnerProduct(relation);
		}
		private int sourceRow(int row) { return admittedRows == null ? row : admittedRows[row]; }

		/** Proves rectangle coverage from admitted indices; never generates its Cartesian product. */
		private boolean coversOwnerProduct(IndexedSupportClauses indexed) {
			Map<CompiledHopKey,Set<CandidateRealizationSupportKey>> choices = new IdentityHashMap<>();
			List<CompiledHopKey> owners = new ArrayList<>();
			for(IndependentSupportAxis axis : axes) {
				Set<CandidateRealizationSupportKey> keys = new java.util.HashSet<>();
				for(IndependentSupportOption option : axis.options())
					keys.add(option.supportKey());
				if(choices.containsKey(axis.sourceOwner()))
					choices.get(axis.sourceOwner()).retainAll(keys);
				else {
					owners.add(axis.sourceOwner());
					choices.put(axis.sourceOwner(), keys);
				}
			}
			long size = 1;
			for(Set<CandidateRealizationSupportKey> keys : choices.values()) {
				if(keys.isEmpty() || size > logicalClauseCount() / keys.size())
					return false;
				size *= keys.size();
			}
			if(size != logicalClauseCount())
				return false;
			Set<List<CandidateRealizationSupportKey>> rows = new java.util.HashSet<>();
			for(int row = 0; row < logicalClauseCount(); row++) {
				Map<CompiledHopKey,CandidateRealizationSupportKey> selected = new IdentityHashMap<>();
				for(var binding : indexed.bindingsAt(sourceRow(row))) {
					var owner = binding.source().rule().parentOccurrence();
					var key = CandidateSelections.requiredInputSupportIdentity(binding.source());
					var previous = selected.putIfAbsent(owner, key);
					if(previous != null && !previous.equals(key)
						|| !choices.get(owner).contains(key))
						return false;
				}
				if(!rows.add(owners.stream().map(selected::get).toList()))
					return false;
			}
			return true;
		}

		public List<IndependentSupportAxis> axes() { return axes; }
		public List<PlacementProofKey> proofDependencies() {
			return relation instanceof FactorizedSupportClauses product ? product.proofs()
				: ((IndexedSupportClauses)relation).proofsAt(sourceRow(0));
		}
		public DurableAnchorKey nativeWorkerPoolWitness() {
			return relation instanceof FactorizedSupportClauses product ? product.nativeWorkerPoolWitness()
				: ((IndexedSupportClauses)relation).witnessAt(sourceRow(0));
		}
		public boolean nativeWorkerPoolLayoutExact() {
			return relation instanceof FactorizedSupportClauses product ? product.nativeWorkerPoolLayoutExact()
				: ((IndexedSupportClauses)relation).layoutExactAt(sourceRow(0));
		}
		public int logicalClauseCount() { return admittedRows == null ? relation.size() : admittedRows.length; }
		public CandidateRealizationSupportClause representativeClause() { return relation.get(sourceRow(0)); }
		/** Correlated rows must additionally be enforced as a joint relation in the model. */
		public boolean correlated() { return correlated; }
		public void forEachAdmittedBindingRow(
			java.util.function.Consumer<List<CandidateRealizationInputBinding>> consumer) {
			if(!(relation instanceof IndexedSupportClauses indexed))
				throw new IllegalStateException("An independent product must not be enumerated");
			for(int row = 0; row < logicalClauseCount(); row++)
				consumer.accept(indexed.bindingsAt(sourceRow(row)));
		}
		public String admittedIndexSignature() {
			if(!(relation instanceof IndexedSupportClauses indexed))
				return "*";
			StringBuilder result = new StringBuilder("[");
			for(int row = 0; row < logicalClauseCount(); row++) {
				if(row != 0) result.append(',');
				result.append(indexed.combinationIdAt(sourceRow(row)));
			}
			return result.append(']').toString();
		}

		public CandidateRealizationSupportClause select(
			java.util.function.Function<CompiledHopKey,CandidateRealizationSupportKey> selected) {
			Objects.requireNonNull(selected, "selected support lookup");
			List<CandidateRealizationInputBinding> bindings = new ArrayList<>(axes.size());
			for(IndependentSupportAxis axis : axes) {
				CandidateRealizationSupportKey key = selected.apply(axis.sourceOwner());
				IndependentSupportOption match = null;
				for(IndependentSupportOption option : axis.options())
					if(option.supportKey().equals(key)) {
						if(match != null)
							throw new IllegalArgumentException(
								"Selected support key is ambiguous within an independent axis");
						match = option;
					}
				if(match == null)
					throw new IllegalArgumentException(
						"Selected producer does not satisfy an independent support axis");
				bindings.add(match.binding());
			}
			int ordinal = relation instanceof FactorizedSupportClauses product
				? product.ordinalOfBindings(bindings)
				: ((IndexedSupportClauses)relation).ordinalOfBindings(bindings, admittedRows);
			if(ordinal < 0)
				throw new IllegalStateException("Independent support selection is outside its relation");
			return relation.get(ordinal);
		}
	}

	/** One exact physical layout with alternative executable support clauses. */
	public record CandidateEmissionRealization(PlacementRealizationKey key,
		List<CandidateRealizationSupportClause> supportClauses)
		implements Comparable<CandidateEmissionRealization> {
		public CandidateEmissionRealization(PlacementRealizationKey key,
			List<PlacementProofKey> proofs, List<CandidateRealizationInputBinding> inputBindings) {
			this(key, List.of(new CandidateRealizationSupportClause(proofs, inputBindings)));
		}
		public CandidateEmissionRealization {
			Objects.requireNonNull(key, "key");
			supportClauses = supportClauses instanceof FactorizedSupportClauses
				|| supportClauses instanceof IndexedSupportClauses
				? Objects.requireNonNull(supportClauses, "realization support clauses")
				: canonicalComparableList(supportClauses, "realization support clause", true);
			if(supportClauses.isEmpty())
				throw new IllegalArgumentException("Candidate realization requires support authority");
			SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
			if(metrics != null && !(supportClauses instanceof FactorizedSupportClauses)
				&& !(supportClauses instanceof IndexedSupportClauses))
				metrics.recordDuplicateMergeClauseOrigins(supportClauses);
			if(supportClauses instanceof FactorizedSupportClauses factorized) {
				DurableAnchorKey witness = factorized.nativeWorkerPoolWitness();
				if(witness != null && (key.layoutKind() != PlacementLayoutKind.NATIVE_LINEAGE
					|| key.emissionState().placementState().fType() != witness.fType()))
					throw new IllegalArgumentException(
						"Native worker-pool witness and realization layout differ");
			}
			else if(supportClauses instanceof IndexedSupportClauses indexed)
				indexed.validateRealizationKey(key);
			else {
				for(CandidateRealizationSupportClause clause : supportClauses)
					if(clause.nativeWorkerPoolWitness() != null
						&& (key.layoutKind() != PlacementLayoutKind.NATIVE_LINEAGE
							|| key.emissionState().placementState().fType()
								!= clause.nativeWorkerPoolWitness().fType()))
						throw new IllegalArgumentException(
							"Native worker-pool witness and realization layout differ");
				DurableAnchorKey nativeWitness = supportClauses.get(0).nativeWorkerPoolWitness();
				boolean nativeLayoutExact = supportClauses.get(0).nativeWorkerPoolLayoutExact();
				for(CandidateRealizationSupportClause clause : supportClauses) {
					DurableAnchorKey candidate = clause.nativeWorkerPoolWitness();
					if((nativeWitness == null) != (candidate == null)
						|| nativeWitness != null && (nativeLayoutExact != clause.nativeWorkerPoolLayoutExact()
							|| !(nativeLayoutExact
								? PlacementIdentity.samePhysicalLayout(nativeWitness, candidate)
								: PlacementIdentity.samePhysicalWorkerEndpoints(nativeWitness, candidate))))
						throw new IllegalArgumentException(
							"One realization cannot mix unproven or physically distinct native worker pools");
				}
			}
		}

		/** Exact Cartesian support with uniform proof and native-pool authority. */
		static CandidateEmissionRealization factorized(PlacementRealizationKey key,
			List<PlacementProofKey> proofs,
			List<List<CandidateRealizationInputBinding>> independentInputChoices,
			DurableAnchorKey nativeWorkerPoolWitness, boolean nativeWorkerPoolLayoutExact) {
			return new CandidateEmissionRealization(key, FactorizedSupportClauses.of(proofs,
				independentInputChoices, nativeWorkerPoolWitness, nativeWorkerPoolLayoutExact));
		}

		/** Compresses only a complete rectangular relation; correlated holes remain explicit. */
		static Optional<CandidateEmissionRealization> tryFactorize(
			PlacementRealizationKey key, List<CandidateRealizationSupportClause> supportClauses) {
			return FactorizedSupportClauses.fromExplicitIfRectangular(supportClauses)
				.map(factorized -> new CandidateEmissionRealization(key, factorized));
		}

		/** Exact independent product view when every axis has invariant relocation authority. */
		public Optional<IndependentSupportProduct> independentSupportProduct() {
			return key.layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP
				&& supportClauses instanceof FactorizedSupportClauses factorized
				? factorized.independentRelocationProduct() : Optional.empty();
		}

		/** Physical axes retain uniform authority; sparse correlations remain explicit constraints. */
		public Optional<IndependentSupportProduct> compactSupportRelation() {
			if(key.layoutKind() == PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
				return Optional.empty();
			return supportClauses instanceof IndexedSupportClauses indexed
				? indexed.uniformSupportRelation() : independentSupportProduct();
		}

		/** Exact partition by physical authority, retaining correlated IDs within each branch. */
		public List<IndependentSupportProduct> compactSupportRegions() {
			return compactSupportRegions(binding -> binding.relocationAction() != null
				? binding.relocationAction().durableAnchor() : binding.source().realization().durableAnchor());
		}

		public List<IndependentSupportProduct> compactSupportRegions(
			java.util.function.Function<CandidateRealizationInputBinding,DurableAnchorKey> deliveredLayout) {
			if(key.layoutKind() == PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
				return List.of();
			return supportClauses instanceof IndexedSupportClauses indexed
				? indexed.physicalSupportRegions(deliveredLayout)
				: independentSupportProduct().map(List::of).orElseGet(List::of);
		}

		/** Storage-level factor view; unlike independentSupportProduct this has no DP eligibility claim. */
		public Optional<FactorizedSupportProduct> factorizedSupportProduct() {
			return supportClauses instanceof FactorizedSupportClauses factorized
				? Optional.of(new FactorizedSupportProduct(factorized)) : Optional.empty();
		}

		/** Freeze already admitted whole tuples as legal IDs without enumerating a product. */
		public CandidateEmissionRealization withIndexedSupport() {
			if(supportClauses instanceof IndexedSupportClauses
				|| supportClauses instanceof FactorizedSupportClauses)
				return this;
			return new CandidateEmissionRealization(key,
				IndexedSupportClauses.fromCanonical(supportClauses));
		}
		public boolean indexedSupport() { return supportClauses instanceof IndexedSupportClauses; }
		/** Resolve an admitted whole-combination ID within this immutable relation only. */
		public CandidateRealizationSupportClause supportClauseForCombinationId(java.math.BigInteger id) {
			Objects.requireNonNull(id, "support combination ID");
			if(!(supportClauses instanceof IndexedSupportClauses indexed))
				throw new IllegalStateException("Realization support is not indexed");
			int ordinal = indexed.ordinalOfCombinationId(id);
			if(ordinal < 0)
				throw new IllegalArgumentException("Combination ID is outside the admitted support relation");
			return indexed.get(ordinal);
		}
		/** Support clause objects currently materialized, including selected indexed-row handles. */
		public int fullyMaterializedSupportClauseCount() {
			return supportClauses instanceof IndexedSupportClauses indexed
				? indexed.materializedHandleCount()
				: supportClauses instanceof FactorizedSupportClauses factorized
					? factorized.materializedClauseCount() : supportClauses.size();
		}

		/**
		 * Reuses a stable-order subset or equality-preserving map of an already
		 * canonical support list. Callers must not use this for newly unordered rows.
		 */
		static CandidateEmissionRealization fromAlreadyCanonicalSupportClauses(
			PlacementRealizationKey key, List<CandidateRealizationSupportClause> supportClauses) {
			return new CandidateEmissionRealization(key, sharedAlreadyCanonicalComparableList(
				supportClauses, "realization support clause"));
		}

		public static CandidateEmissionRealization local(PlacementEmissionState emission) {
			return new CandidateEmissionRealization(PlacementRealizationKey.local(emission), List.of(), List.of());
		}
		public static CandidateEmissionRealization local(PlacementEmissionState emission,
			List<PlacementProofKey> proofs, List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(PlacementRealizationKey.local(emission), proofs, inputBindings);
		}

		public static CandidateEmissionRealization durable(PlacementEmissionState emission,
			DurableAnchorKey anchor, List<PlacementProofKey> proofs,
			List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(PlacementRealizationKey.durable(emission, anchor),
				proofs, inputBindings);
		}

		public static CandidateEmissionRealization sourceLineage(PlacementEmissionState emission,
			String sourceIdentity) {
			return new CandidateEmissionRealization(
				PlacementRealizationKey.sourceLineage(emission, sourceIdentity), List.of(), List.of());
		}
		public static CandidateEmissionRealization sourceLineage(PlacementEmissionState emission,
			String sourceIdentity, List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(
				PlacementRealizationKey.sourceLineage(emission, sourceIdentity), List.of(), inputBindings);
		}

		public static CandidateEmissionRealization nativeLineage(PlacementEmissionState emission,
			String lineage, List<PlacementProofKey> proofs,
			List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(PlacementRealizationKey.nativeLineage(emission, lineage),
				proofs, inputBindings);
		}
		public static CandidateEmissionRealization nativeLineage(PlacementEmissionState emission,
			String lineage, DurableAnchorKey nativeWorkerPoolWitness, List<PlacementProofKey> proofs,
			List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(PlacementRealizationKey.nativeLineage(emission, lineage),
				List.of(new CandidateRealizationSupportClause(
					proofs, inputBindings, nativeWorkerPoolWitness)));
		}
		public static CandidateEmissionRealization nativeLineageDynamicLayout(PlacementEmissionState emission,
			String lineage, DurableAnchorKey nativeWorkerPoolWitness, List<PlacementProofKey> proofs,
			List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(PlacementRealizationKey.nativeLineage(emission, lineage),
				List.of(new CandidateRealizationSupportClause(
					proofs, inputBindings, nativeWorkerPoolWitness, false)));
		}
		public static CandidateEmissionRealization valueMap(PlacementEmissionState emission,
			String relation, List<CandidateRealizationSupportClause> supportClauses) {
			return new CandidateEmissionRealization(
				PlacementRealizationKey.valueMap(emission, relation), supportClauses);
		}

		public CandidateRealizationSupportClause requireSingletonSupportClause() {
			if(supportClauses.size() != 1)
				throw new IllegalArgumentException("Candidate realization support clause is ambiguous");
			return supportClauses.get(0);
		}
		/** Compatibility fast path; callers handling alternatives must iterate supportClauses(). */
		public List<PlacementProofKey> proofDependencies() {
			return requireSingletonSupportClause().proofDependencies();
		}
		/** Compatibility fast path; callers handling alternatives must iterate supportClauses(). */
		public List<CandidateRealizationInputBinding> inputBindings() {
			return requireSingletonSupportClause().inputBindings();
		}
		/** Compatibility fast path retained only for singleton support authority. */
		public List<CandidateRealizationReference> requiredInputSupport() {
			return requireSingletonSupportClause().requiredInputSupport();
		}

		public PlacementState placementState() { return key.emissionState().placementState(); }
		public DurableAnchorKey anchor() { return key.durableAnchor(); }
		/** Exact runtime worker-pool layout, including ROW/COL partition-axis ranges. */
		public DurableAnchorKey provenWorkerPool(CandidateRealizationSupportClause clause) {
			if(!ownsSupportClauseIdentity(clause))
				throw new IllegalArgumentException("Support clause is not owned by realization");
			return key.durableAnchor() != null ? key.durableAnchor()
				: clause.nativeWorkerPoolLayoutExact() ? clause.nativeWorkerPoolWitness() : null;
		}
		/** Native worker residency proof. Dynamic-layout witnesses prove endpoints/FType only. */
		public DurableAnchorKey nativeWorkerPoolResidencyWitness(CandidateRealizationSupportClause clause) {
			if(!ownsSupportClauseIdentity(clause))
				throw new IllegalArgumentException("Support clause is not owned by realization");
			return key.durableAnchor() != null ? key.durableAnchor() : clause.nativeWorkerPoolWitness();
		}
		public boolean nativeWorkerPoolLayoutExact(CandidateRealizationSupportClause clause) {
			if(!ownsSupportClauseIdentity(clause))
				throw new IllegalArgumentException("Support clause is not owned by realization");
			return key.durableAnchor() != null || clause.nativeWorkerPoolLayoutExact();
		}
		public boolean ownsSupportClauseIdentity(CandidateRealizationSupportClause clause) {
			return supportClauseIdentityOrdinal(clause) >= 0;
		}
		int supportClauseIdentityOrdinal(CandidateRealizationSupportClause clause) {
			if(supportClauses instanceof IndexedSupportClauses indexed)
				return indexed.firstIdentityOrdinal(clause);
			if(supportClauses instanceof SharedCanonicalList<?> shared)
				return shared.firstIdentityOrdinal(clause);
			if(supportClauses instanceof FactorizedSupportClauses factorized)
				return factorized.firstIdentityOrdinal(clause);
			for(int index = 0; index < supportClauses.size(); index++)
				if(supportClauses.get(index) == clause)
					return index;
			return -1;
		}
		/** Linear bulk query used instead of repeating the public identity guard for every owned clause. */
		boolean allOwnedSupportClausesHaveExactNativeLayout() {
			if(key.layoutKind() == PlacementLayoutKind.VALUE_MAP)
				return false;
			if(key.durableAnchor() != null)
				return true;
			if(supportClauses instanceof FactorizedSupportClauses factorized)
				return factorized.nativeWorkerPoolLayoutExact();
			if(supportClauses instanceof IndexedSupportClauses indexed)
				return indexed.allRowsHaveExactNativeLayout();
			return supportClauses.stream().allMatch(
				CandidateRealizationSupportClause::nativeWorkerPoolLayoutExact);
		}
		/**
		 * Package-internal fast path; the caller must obtain {@code clause} by iterating
		 * {@link #supportClauses()} or from a constructor-validated immutable candidate receipt.
		 */
		DurableAnchorKey provenWorkerPoolForOwnedClause(CandidateRealizationSupportClause clause) {
			return key.durableAnchor() != null ? key.durableAnchor()
				: clause.nativeWorkerPoolLayoutExact() ? clause.nativeWorkerPoolWitness() : null;
		}
		/**
		 * Package-internal fast path; the caller must obtain {@code clause} by iterating
		 * {@link #supportClauses()} or from a constructor-validated immutable candidate receipt.
		 */
		public DurableAnchorKey nativeWorkerPoolResidencyForOwnedClause(CandidateRealizationSupportClause clause) {
			return key.durableAnchor() != null ? key.durableAnchor() : clause.nativeWorkerPoolWitness();
		}
		/** Package-internal fast path; caller must obtain {@code clause} by iterating {@link #supportClauses()}. */
		public boolean nativeWorkerPoolLayoutExactForOwnedClause(CandidateRealizationSupportClause clause) {
			return key.durableAnchor() != null || clause.nativeWorkerPoolLayoutExact();
		}
		public String normalizedSignature() {
			String cached = PlacementIdentity.cachedSignature(this);
			return cached != null ? cached : PlacementIdentity.rememberSignature(this,
				key.normalizedSignature() + "|support=" + supportClauses.stream()
					.map(CandidateRealizationSupportClause::normalizedSignature).toList());
		}
		@Override public int compareTo(CandidateEmissionRealization that) {
			return compareCanonicalOrdering(this, that);
		}
	}

	/** One exact immutable rule/profile emission with its executable physical realizations. */
	public record CandidateEmissionFact(PlacementEmissionState emissionState, FType executionFType,
		DerivedFoutMaterializationActionKey derivedFoutAction,
		List<CandidateEmissionRealization> realizations) {
		public CandidateEmissionFact(PlacementEmissionState emissionState, FType executionFType) {
			this(emissionState, executionFType, null, defaultRealizations(emissionState));
		}
		public CandidateEmissionFact(PlacementEmissionState emissionState, FType executionFType,
			DerivedFoutMaterializationActionKey derivedFoutAction) {
			this(emissionState, executionFType, derivedFoutAction,
				derivedFoutAction == null ? defaultRealizations(emissionState)
					: List.of(CandidateEmissionRealization.nativeLineage(emissionState,
						"candidate-emission:" + emissionState.normalizedSignature()
							+ "|derived-action:" + derivedFoutAction.normalizedSignature(),
						List.of(), List.of())));
		}
		public CandidateEmissionFact {
			Objects.requireNonNull(emissionState, "emissionState");
			PlacementState state = emissionState.placementState();
			if(state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
				&& executionFType == null)
				throw new IllegalArgumentException("Federated FOUT candidate requires an exact execution FType");
			if(state.execType() != ExecType.FED && executionFType != null)
				throw new IllegalArgumentException("Local candidate emission cannot publish a federated execution FType");
			if(emissionState.derivedFedFout()
				&& (state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT))
				throw new IllegalArgumentException("Derived FOUT authority requires a FED/FOUT emission state");
			boolean cpFout = state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.FOUT;
			if(emissionState.derivedFedFout() && derivedFoutAction == null)
				throw new IllegalArgumentException("Derived FED/FOUT emission requires one exact materialization action");
			if(derivedFoutAction != null && !emissionState.derivedFedFout() && !cpFout)
				throw new IllegalArgumentException(
					"Only CP/FOUT or derived FED/FOUT emissions may carry an output materialization action");
			if(derivedFoutAction != null && (derivedFoutAction.targetPlacement() != state
				|| derivedFoutAction.materializationFType() != state.fType()))
				throw new IllegalArgumentException("FOUT materialization action and emission identities differ");
			realizations = mergeRealizations(realizations);
			if(realizations.isEmpty())
				throw new IllegalArgumentException("Candidate emission requires at least one physical realization");
			for(CandidateEmissionRealization realization : realizations)
				if(!realization.key().emissionState().equals(emissionState))
					throw new IllegalArgumentException("Candidate realization belongs to a different emission");
		}

		private static List<CandidateEmissionRealization> mergeRealizations(
			java.util.Collection<CandidateEmissionRealization> alternatives) {
			Objects.requireNonNull(alternatives, "candidate emission realizations");
			// A realization already owns a canonical clause list. Keep that complete
			// authority (including every OR clause) without rebuilding sorted sets.
			if(alternatives.size() == 1)
				return List.of(Objects.requireNonNull(alternatives.iterator().next(),
					"candidate emission realization"));
			SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
			// Direct/closure publication overwhelmingly joins two immutable authorities.
			// Merge that exact relation before allocating maps or recursively hashing the
			// already-canonical support graph. The general 3+ path remains below.
			if(alternatives.size() == 2) {
				var iterator = alternatives.iterator();
				CandidateEmissionRealization left = Objects.requireNonNull(iterator.next(),
					"candidate emission realization");
				CandidateEmissionRealization right = Objects.requireNonNull(iterator.next(),
					"candidate emission realization");
				if(!left.key().equals(right.key())) {
					if(metrics != null) {
						metrics.recordRealizationMergeInput();
						metrics.recordRealizationMergeInput();
						for(int index = 0; index < left.supportClauses().size()
							+ right.supportClauses().size(); index++)
							metrics.recordRealizationMergeClause(true);
					}
					return canonicalRealizationList(List.of(left, right));
				}
				List<CandidateRealizationSupportClause> leftClauses = left.supportClauses();
				List<CandidateRealizationSupportClause> rightClauses = right.supportClauses();
				if(supportClauseListsEqual(leftClauses, rightClauses)) {
					if(metrics != null) {
						metrics.recordRealizationMergeInput();
						metrics.recordRealizationMergeInput();
						metrics.recordRealizationMergeClauses(leftClauses.size(), leftClauses.size());
						metrics.recordRealizationMergeReuse();
						if(metrics.hasDuplicateMergeDiagnostics()) {
							long immutableReplay = leftClauses == rightClauses ? leftClauses.size() : 0;
							long sharedReplay = 0;
							long[] provenance = new long[SearchSpaceMetrics.DuplicateClauseProvenance.values().length];
							if(leftClauses != rightClauses)
								for(int index = 0; index < leftClauses.size(); index++)
									if(leftClauses.get(index) == rightClauses.get(index))
										sharedReplay++;
									else
										recordDuplicateProvenance(metrics, leftClauses.get(index),
											rightClauses.get(index), provenance);
							metrics.recordDuplicateMergeDiagnostics("TWO_EQUAL_LISTS", 2,
								immutableReplay, sharedReplay,
								leftClauses.size() - immutableReplay - sharedReplay,
								duplicateProvenanceCounts(provenance), true);
						}
					}
					return List.of(leftClauses instanceof FactorizedSupportClauses ? left
						: rightClauses instanceof FactorizedSupportClauses ? right : left);
				}
				if((metrics == null || !metrics.hasDuplicateMergeDiagnostics())
					&& leftClauses instanceof FactorizedSupportClauses leftFactorized
					&& rightClauses instanceof FactorizedSupportClauses rightFactorized) {
					Optional<FactorizedSupportClauses> factorizedUnion =
						leftFactorized.oneAxisUnion(rightFactorized);
					if(factorizedUnion.isPresent()) {
						FactorizedSupportClauses union = factorizedUnion.get();
						long uniqueClauses = union.size();
						long duplicateClauses = (long)leftClauses.size()
							+ rightClauses.size() - uniqueClauses;
						if(metrics != null) {
							metrics.recordRealizationMergeInput();
							metrics.recordRealizationMergeInput();
							metrics.recordRealizationMergeClauses(uniqueClauses, duplicateClauses);
						}
						if(union.equals(leftFactorized)) {
							if(metrics != null)
								metrics.recordRealizationMergeReuse();
							return List.of(left);
						}
						if(union.equals(rightFactorized)) {
							if(metrics != null)
								metrics.recordRealizationMergeReuse();
							return List.of(right);
						}
						return List.of(new CandidateEmissionRealization(left.key(), union));
					}
				}

				List<CanonicalText> leftOrderingKeys = retainedCanonicalOrderingKeys(leftClauses);
				List<CanonicalText> rightOrderingKeys = retainedCanonicalOrderingKeys(rightClauses);
				if(leftOrderingKeys == null || rightOrderingKeys == null) {
					CanonicalTextContext missingContext = new CanonicalTextContext();
					if(leftOrderingKeys == null)
						leftOrderingKeys = canonicalOrderingKeys(leftClauses, missingContext);
					if(rightOrderingKeys == null)
						rightOrderingKeys = canonicalOrderingKeys(rightClauses, missingContext);
				}
				List<CandidateRealizationSupportClause> union =
					new ArrayList<>(leftClauses.size() + rightClauses.size());
				List<CanonicalText> unionOrderingKeys =
					new ArrayList<>(leftClauses.size() + rightClauses.size());
				boolean rightAdded = false;
				boolean ambiguousOrderingTie = false;
				int uniqueClauses = 0, duplicateClauses = 0, comparisons = 0;
				int sharedDuplicateClauses = 0, equalDistinctDuplicateClauses = 0;
				boolean diagnoseDuplicates = metrics != null && metrics.hasDuplicateMergeDiagnostics();
				long[] duplicateProvenance = diagnoseDuplicates
					? new long[SearchSpaceMetrics.DuplicateClauseProvenance.values().length] : null;
				int leftIndex = 0, rightIndex = 0;
				CanonicalTextComparison comparison = new CanonicalTextComparison();
				while(leftIndex < leftClauses.size() && rightIndex < rightClauses.size()) {
					CandidateRealizationSupportClause leftClause = leftClauses.get(leftIndex);
					CandidateRealizationSupportClause rightClause = rightClauses.get(rightIndex);
					comparisons++;
					int order = comparison.compare(leftOrderingKeys.get(leftIndex),
						rightOrderingKeys.get(rightIndex));
					if(order < 0) {
						union.add(leftClause);
						unionOrderingKeys.add(leftOrderingKeys.get(leftIndex));
						leftIndex++;
						uniqueClauses++;
					}
					else if(order > 0) {
						union.add(rightClause);
						unionOrderingKeys.add(rightOrderingKeys.get(rightIndex));
						rightIndex++;
						rightAdded = true;
						uniqueClauses++;
					}
					else if(leftClause.equals(rightClause)) {
						// Equality, never a hash or comparator collision alone, removes an
						// exact duplicate. Preserve the first authority object's identity.
						union.add(leftClause);
						unionOrderingKeys.add(leftOrderingKeys.get(leftIndex));
						leftIndex++;
						rightIndex++;
						uniqueClauses++;
						duplicateClauses++;
						if(diagnoseDuplicates) {
							if(leftClause == rightClause)
								sharedDuplicateClauses++;
							else
							{
								equalDistinctDuplicateClauses++;
								recordDuplicateProvenance(metrics, leftClause, rightClause,
									duplicateProvenance);
							}
						}
					}
					else {
						// A comparator tie is not semantic identity. Advancing both cursors
						// could miss an equal object later in either tie group. Fall back to
						// the exact map-based merge, which retains stable concatenation order.
						ambiguousOrderingTie = true;
						break;
					}
				}
				while(!ambiguousOrderingTie && leftIndex < leftClauses.size()) {
					union.add(leftClauses.get(leftIndex++));
					unionOrderingKeys.add(leftOrderingKeys.get(leftIndex - 1));
					uniqueClauses++;
				}
				while(!ambiguousOrderingTie && rightIndex < rightClauses.size()) {
					union.add(rightClauses.get(rightIndex++));
					unionOrderingKeys.add(rightOrderingKeys.get(rightIndex - 1));
					rightAdded = true;
					uniqueClauses++;
				}
				if(metrics != null)
					for(int index = 0; index < comparisons; index++)
						metrics.recordCanonicalComparison();
				if(!ambiguousOrderingTie && metrics != null) {
					metrics.recordRealizationMergeInput();
					metrics.recordRealizationMergeInput();
					for(int index = 0; index < uniqueClauses; index++)
						metrics.recordRealizationMergeClause(true);
					for(int index = 0; index < duplicateClauses; index++)
						metrics.recordRealizationMergeClause(false);
					if(diagnoseDuplicates)
						metrics.recordDuplicateMergeDiagnostics("TWO_SORTED_UNION", 2, 0,
							sharedDuplicateClauses, equalDistinctDuplicateClauses,
							duplicateProvenanceCounts(duplicateProvenance), !rightAdded);
				}
				if(!ambiguousOrderingTie && !rightAdded) {
					if(metrics != null)
						metrics.recordRealizationMergeReuse();
					return List.of(left);
				}
				if(!ambiguousOrderingTie)
					return List.of(CandidateEmissionRealization.fromAlreadyCanonicalSupportClauses(
						left.key(), new SharedCanonicalList<>(List.copyOf(union),
							List.copyOf(unionOrderingKeys))));
			}
			Map<PlacementRealizationKey,List<CandidateEmissionRealization>> groupsByKey =
				new java.util.LinkedHashMap<>();
			for(CandidateEmissionRealization realization : alternatives) {
				Objects.requireNonNull(realization, "candidate emission realization");
				if(metrics != null)
					metrics.recordRealizationMergeInput();
				groupsByKey.computeIfAbsent(realization.key(), ignored -> new ArrayList<>())
					.add(realization);
			}
			List<CandidateEmissionRealization> merged = new ArrayList<>(groupsByKey.size());
			for(List<CandidateEmissionRealization> group : groupsByKey.values()) {
				if(group.size() == 1) {
					merged.add(group.get(0));
					if(metrics != null)
						metrics.recordRealizationMergeReuse();
				}
				else
					merged.add(mergeRealizationGroup(group, metrics));
			}
			return canonicalRealizationList(merged);
		}

		private static boolean supportClauseListsEqual(
			List<CandidateRealizationSupportClause> left,
			List<CandidateRealizationSupportClause> right) {
			if(left == right)
				return true;
			if(left instanceof FactorizedSupportClauses leftFactorized
				&& right instanceof FactorizedSupportClauses rightFactorized)
				return leftFactorized.sameExactAuthority(rightFactorized);
			if(left instanceof FactorizedSupportClauses factorized)
				return factorized.equals(right);
			if(right instanceof FactorizedSupportClauses factorized)
				return factorized.equals(left);
			return left.equals(right);
		}

		/** Typed boundary for final realization ordering; specialized by Gate B after behavior RED. */
		@SuppressWarnings("unchecked")
		static List<CandidateEmissionRealization> canonicalRealizationList(
			java.util.Collection<CandidateEmissionRealization> realizations) {
			Objects.requireNonNull(realizations, "candidate emission realizations");
			if(realizations instanceof SharedCanonicalList<?>)
				return (List<CandidateEmissionRealization>) realizations;
			if(realizations.isEmpty())
				return List.of();
			if(realizations.size() == 1)
				return List.of(Objects.requireNonNull(realizations.iterator().next(),
					"candidate emission realization"));
			SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
			if(metrics != null)
				metrics.recordCanonicalSort(realizations.size());
			CanonicalTextContext context = new CanonicalTextContext();
			CanonicalTextComparison comparison = new CanonicalTextComparison();
			List<RealizationOrderingEntry> decorated = new ArrayList<>(realizations.size());
			for(CandidateEmissionRealization realization : realizations) {
				if(metrics != null)
					metrics.recordCanonicalOrderingKey();
				CandidateEmissionRealization value = Objects.requireNonNull(
					realization, "candidate emission realization");
				decorated.add(new RealizationOrderingEntry(value,
					canonicalOrderingKey(value.key(), context)));
			}
			decorated.sort((left, right) -> {
				if(metrics != null)
					metrics.recordCanonicalComparison();
				int keyOrder = comparison.compare(left.keyText(), right.keyText());
				return keyOrder != 0 ? keyOrder
					: comparison.compare(canonicalOrderingKey(left.value(), context),
						canonicalOrderingKey(right.value(), context));
			});
			List<CandidateEmissionRealization> canonical = new ArrayList<>(decorated.size());
			for(RealizationOrderingEntry entry : decorated)
				canonical.add(entry.value());
			for(int index = 1; index < canonical.size(); index++)
				if(canonical.get(index - 1).equals(canonical.get(index)))
					throw new IllegalArgumentException("Duplicate candidate emission realization");
			return new SharedCanonicalList<>(List.copyOf(canonical));
		}

		private record RealizationOrderingEntry(
			CandidateEmissionRealization value, CanonicalText keyText) { }


		private static CandidateEmissionRealization mergeRealizationGroup(
			List<CandidateEmissionRealization> group, SearchSpaceMetrics metrics) {
			CandidateEmissionRealization factorized = mergeFactorizedRealizationGroup(group, metrics);
			if(factorized != null)
				return factorized;
			CandidateEmissionRealization first = group.get(0);
			List<CandidateRealizationSupportClause> firstClauses = first.supportClauses();
			boolean allEqual = true;
			for(int index = 1; index < group.size() && allEqual; index++)
				allEqual = supportClauseListsEqual(
					firstClauses, group.get(index).supportClauses());
			if(allEqual) {
				if(metrics != null) {
					for(int groupIndex = 0; groupIndex < group.size(); groupIndex++)
						for(int clauseIndex = 0; clauseIndex < firstClauses.size(); clauseIndex++)
							metrics.recordRealizationMergeClause(groupIndex == 0);
					metrics.recordRealizationMergeReuse();
					if(metrics.hasDuplicateMergeDiagnostics()) {
						long immutableReplay = 0, sharedReplay = 0, equalDistinct = 0;
						long[] provenance = new long[SearchSpaceMetrics.DuplicateClauseProvenance.values().length];
						for(int groupIndex = 1; groupIndex < group.size(); groupIndex++) {
							List<CandidateRealizationSupportClause> clauses =
								group.get(groupIndex).supportClauses();
							if(clauses == firstClauses)
								immutableReplay += clauses.size();
							else
								for(int clauseIndex = 0; clauseIndex < clauses.size(); clauseIndex++)
									if(clauses.get(clauseIndex) == firstClauses.get(clauseIndex))
										sharedReplay++;
									else
									{
										equalDistinct++;
										recordDuplicateProvenance(metrics, firstClauses.get(clauseIndex),
											clauses.get(clauseIndex), provenance);
									}
						}
						metrics.recordDuplicateMergeDiagnostics("K_EQUAL_LISTS", group.size(),
							immutableReplay, sharedReplay, equalDistinct,
							duplicateProvenanceCounts(provenance), true);
					}
				}
				return first;
			}

			List<List<CandidateRealizationSupportClause>> clausesByGroup = new ArrayList<>(group.size());
			List<List<CanonicalText>> keysByGroup = new ArrayList<>(group.size());
			CanonicalTextContext missingContext = null;
			int totalClauses = 0;
			for(CandidateEmissionRealization realization : group) {
				List<CandidateRealizationSupportClause> clauses = realization.supportClauses();
				clausesByGroup.add(clauses);
				totalClauses = Math.addExact(totalClauses, clauses.size());
				List<CanonicalText> keys = retainedCanonicalOrderingKeys(clauses);
				if(keys == null) {
					if(missingContext == null)
						missingContext = new CanonicalTextContext();
					keys = canonicalOrderingKeys(clauses, missingContext);
				}
				keysByGroup.add(keys);
			}

			MergedClauseRuns mergedRuns = mergeCanonicalClauseRuns(
				clausesByGroup, keysByGroup, totalClauses, metrics);
			if(metrics != null && metrics.hasDuplicateMergeDiagnostics())
				metrics.recordDuplicateMergeDiagnostics("K_CANONICAL_UNION", group.size(),
					mergedRuns.immutableListReplayClauses(), mergedRuns.sharedClauseReplayClauses(),
					mergedRuns.equalDistinctClauseClauses(), mergedRuns.provenance(),
					firstClauses.equals(mergedRuns.clauses()));
			List<CandidateRealizationSupportClause> union = mergedRuns.clauses();
			List<CanonicalText> unionKeys = mergedRuns.keys();
			if(firstClauses.equals(union)) {
				if(metrics != null)
					metrics.recordRealizationMergeReuse();
				return first;
			}
			return CandidateEmissionRealization.fromAlreadyCanonicalSupportClauses(first.key(),
				new SharedCanonicalList<>(List.copyOf(union), List.copyOf(unionKeys)));
		}

		private static CandidateEmissionRealization mergeFactorizedRealizationGroup(
			List<CandidateEmissionRealization> group, SearchSpaceMetrics metrics) {
			if(metrics != null && metrics.hasDuplicateMergeDiagnostics())
				return null;
			List<FactorizedSupportClauses> pending = new ArrayList<>(group.size());
			long inputClauses = 0;
			for(CandidateEmissionRealization realization : group) {
				if(!(realization.supportClauses() instanceof FactorizedSupportClauses clauses))
					return null;
				pending.add(clauses);
				inputClauses += clauses.size();
			}
			while(pending.size() > 1) {
				boolean combined = false;
				for(int left = 0; left < pending.size() && !combined; left++)
					for(int right = left + 1; right < pending.size(); right++) {
						Optional<FactorizedSupportClauses> union =
							pending.get(left).oneAxisUnion(pending.get(right));
						if(union.isEmpty())
							continue;
						pending.set(left, union.get());
						pending.remove(right);
						combined = true;
						break;
					}
				if(!combined)
					return null;
			}
			FactorizedSupportClauses union = pending.get(0);
			if(metrics != null)
				metrics.recordRealizationMergeClauses(
					union.size(), inputClauses - union.size());
			for(CandidateEmissionRealization realization : group)
				if(union.equals(realization.supportClauses())) {
					if(metrics != null)
						metrics.recordRealizationMergeReuse();
					return realization;
				}
			return new CandidateEmissionRealization(group.get(0).key(), union);
		}

		private record ClauseRunEntry(CandidateRealizationSupportClause clause,
			CanonicalText key) { }

		private record MergedClauseRuns(List<CandidateRealizationSupportClause> clauses,
			List<CanonicalText> keys, long immutableListReplayClauses,
			long sharedClauseReplayClauses, long equalDistinctClauseClauses,
			SearchSpaceMetrics.DuplicateProvenanceCounts provenance) { }

		/** Stable exact union; remove identical authorities before comparing their ordering text. */
		private static MergedClauseRuns mergeCanonicalClauseRuns(
			List<List<CandidateRealizationSupportClause>> clausesByGroup,
			List<List<CanonicalText>> keysByGroup, int totalClauses, SearchSpaceMetrics metrics) {
			CanonicalTextComparison comparison = new CanonicalTextComparison();
			List<ClauseRunEntry> ordered = new ArrayList<>(totalClauses);
			Set<CandidateRealizationSupportClause> seenIdentities =
				Collections.newSetFromMap(new IdentityHashMap<>());
			Set<CandidateRealizationSupportClause> seenAuthorities = new java.util.HashSet<>();
			boolean diagnoseDuplicates = metrics != null && metrics.hasDuplicateMergeDiagnostics();
			Set<List<CandidateRealizationSupportClause>> seenLists = diagnoseDuplicates
				? Collections.newSetFromMap(new IdentityHashMap<>()) : null;
			long immutableListReplay = 0, sharedClauseReplay = 0, equalDistinct = 0;
			long[] provenance = diagnoseDuplicates
				? new long[SearchSpaceMetrics.DuplicateClauseProvenance.values().length] : null;
			Map<CandidateRealizationSupportClause,CandidateRealizationSupportClause> firstAuthorities =
				diagnoseDuplicates ? new java.util.HashMap<>() : null;
			for(int group = 0; group < clausesByGroup.size(); group++) {
				boolean repeatedList = diagnoseDuplicates && !seenLists.add(clausesByGroup.get(group));
				for(int position = 0; position < clausesByGroup.get(group).size(); position++) {
					CandidateRealizationSupportClause clause = clausesByGroup.get(group).get(position);
					// Shared immutable clauses avoid another structural hash. Equal distinct
					// authorities still use full equality, retaining the first descriptor.
					boolean uniqueIdentity = seenIdentities.add(clause);
					boolean unique = uniqueIdentity && seenAuthorities.add(clause);
					CandidateRealizationSupportClause retained = diagnoseDuplicates && uniqueIdentity
						? firstAuthorities.putIfAbsent(clause, clause) : null;
					if(metrics != null)
						metrics.recordRealizationMergeClause(unique);
					if(diagnoseDuplicates && !unique) {
						if(repeatedList)
							immutableListReplay++;
						else if(!uniqueIdentity)
							sharedClauseReplay++;
						else
						{
							equalDistinct++;
							recordDuplicateProvenance(metrics, retained, clause, provenance);
						}
					}
					if(unique)
						ordered.add(new ClauseRunEntry(clause, keysByGroup.get(group).get(position)));
				}
			}
			// Full immutable clause equality implies equal canonical text. Deduplication
			// therefore keeps the same first authority and descriptor as the stable sort
			// followed by equality checks. Hash collisions still require full equality.
			// The remaining entries retain sorted runs and stable order for text ties.
			ordered.sort((left, right) ->
				compareCanonicalText(left.key(), right.key(), metrics, comparison));

			List<CandidateRealizationSupportClause> union = new ArrayList<>(ordered.size());
			List<CanonicalText> unionKeys = new ArrayList<>(ordered.size());
			for(ClauseRunEntry current : ordered) {
				union.add(current.clause());
				unionKeys.add(current.key());
			}
			if(metrics != null)
				metrics.recordCanonicalSort(union.size());
			return new MergedClauseRuns(List.copyOf(union), List.copyOf(unionKeys),
				immutableListReplay, sharedClauseReplay, equalDistinct,
				diagnoseDuplicates ? duplicateProvenanceCounts(provenance)
					: SearchSpaceMetrics.DuplicateProvenanceCounts.EMPTY);
		}

		private static void recordDuplicateProvenance(SearchSpaceMetrics metrics,
			CandidateRealizationSupportClause retained, CandidateRealizationSupportClause duplicate,
			long[] counts) {
			counts[metrics.classifyDuplicateClause(retained, duplicate).ordinal()]++;
		}

		private static SearchSpaceMetrics.DuplicateProvenanceCounts duplicateProvenanceCounts(
			long[] counts) {
			return new SearchSpaceMetrics.DuplicateProvenanceCounts(
				counts[SearchSpaceMetrics.DuplicateClauseProvenance.SAME_BATCH.ordinal()],
				counts[SearchSpaceMetrics.DuplicateClauseProvenance.SAME_ROUTE_SAME_REVISION.ordinal()],
				counts[SearchSpaceMetrics.DuplicateClauseProvenance.CROSS_ROUTE.ordinal()],
				counts[SearchSpaceMetrics.DuplicateClauseProvenance.UNCHANGED_REVISION_REPLAY.ordinal()],
				counts[SearchSpaceMetrics.DuplicateClauseProvenance.UNRESOLVED.ordinal()]);
		}

		private static int compareCanonicalText(
			CanonicalText left, CanonicalText right, SearchSpaceMetrics metrics,
			CanonicalTextComparison comparison) {
			if(metrics != null)
				metrics.recordCanonicalComparison();
			return comparison.compare(left, right);
		}

		private static List<CandidateEmissionRealization> defaultRealizations(PlacementEmissionState emission) {
			Objects.requireNonNull(emission, "emissionState");
			return emission.placementState().output() == FederatedOutput.LOUT
				? List.of(CandidateEmissionRealization.local(emission))
				: List.of(CandidateEmissionRealization.nativeLineage(emission,
					"candidate-emission:" + emission.normalizedSignature(), List.of(), List.of()));
		}

		public String normalizedSignature() {
			return selectionSignature()
				+ "|realizations=" + realizations.stream().map(CandidateEmissionRealization::normalizedSignature)
					.toList();
		}

		/** Shallow emission identity used by one selected support-clause receipt. */
		public String selectionSignature() {
			return emissionState.normalizedSignature() + "|executionFType="
				+ (executionFType == null ? "-" : executionFType.name()) + "|derivedAction="
				+ (derivedFoutAction == null ? "-" : derivedFoutAction.normalizedSignature());
		}
	}

	/** One exact immutable rule/profile fact captured by the canonical builder pass. */
	public record CandidateRuleFact(CandidateRuleKey key, CandidateEvaluationStatus status,
		CandidateCapabilityFact capability, CandidateShapeProofFact shapeProof, CandidateProfileFact profile,
		List<CandidateEmissionFact> allowedEmissionFacts, String failureCode) {
		public CandidateRuleFact {
			SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
			if(metrics != null) metrics.recordCandidateRuleFactCreated();
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(shapeProof, "shapeProof");
			Objects.requireNonNull(profile, "profile");
			allowedEmissionFacts = List.copyOf(Objects.requireNonNull(allowedEmissionFacts,
				"allowedEmissionFacts"));
			failureCode = failureCode == null ? "" : failureCode;
			if(status == CandidateEvaluationStatus.AVAILABLE
					&& (capability == null || !profile.available() || !failureCode.isEmpty()
						|| allowedEmissionFacts.isEmpty())
				|| status == CandidateEvaluationStatus.PRIVACY_EXCLUDED
					&& (capability == null || !profile.available() || failureCode.isEmpty()
						|| !allowedEmissionFacts.isEmpty())
				|| status == CandidateEvaluationStatus.RULE_ERROR
					&& (profile.available() || failureCode.isEmpty() || !allowedEmissionFacts.isEmpty())
				|| status == CandidateEvaluationStatus.PROFILE_ERROR
					&& (capability == null || profile.available() || failureCode.isEmpty()
						|| !allowedEmissionFacts.isEmpty()))
				throw new IllegalArgumentException("Candidate rule status and evidence differ");
			Set<String> identities = new java.util.HashSet<>();
			for(CandidateEmissionFact fact : allowedEmissionFacts) {
				Objects.requireNonNull(fact, "allowed emission fact");
				// One output tuple can be uploaded to different concrete worker pools.
				// The selected action is part of emission identity, not a duplicate state.
				if(!identities.add(fact.selectionSignature()))
					throw new IllegalArgumentException("Duplicate exact candidate emission state");
			}
		}

		public List<PlacementEmissionState> allowedEmissionStates() {
			return allowedEmissionFacts.stream().map(CandidateEmissionFact::emissionState).distinct().toList();
		}
	}


	public enum CandidateLookupFailure {
		FOREIGN_PARENT, NON_CANDIDATE_PARENT, MISSING_FACT, REORDERED_INPUTS, PRESENT_NULL, PRIVACY_EXCLUDED
	}

	public static final class CandidateRuleLookupException extends IllegalArgumentException {
		private static final long serialVersionUID = 1L;
		private final CandidateLookupFailure failure;
		public CandidateRuleLookupException(CandidateLookupFailure failure, String message) {
			super(message);
			this.failure = Objects.requireNonNull(failure, "failure");
		}
		public CandidateLookupFailure failure() { return failure; }
	}

	/** Ordered, deeply immutable exact-candidate fact universe. */
	public static final class CandidateRuleFacts {
		private record IndexedRealization(CandidateEmissionRealization realization, int matches) { }
		private record RealizationIndex(Map<PlacementRealizationKey,IndexedRealization> realizations) { }

		private final List<CandidateRuleFact> orderedFacts;
		private final Map<CandidateRuleKey,CandidateRuleFact> factsByKey;
		private final Map<CompiledHopKey,List<CandidateRuleFact>> factsByParent;
		private final Map<CandidateRuleKey,RealizationIndex> realizationsByRule;
		private final CandidateRuleDomain domain;
		private final List<CpRuleFamily> cpFamilies;
		private final Map<CompiledHopKey,List<CpRuleFamily>> cpFamiliesByParent;
		private final List<CandidateRuleRelation> candidateRelations;
		private final Map<CompiledHopKey,List<CandidateRuleRelation>> candidateRelationsByParent;

		public CandidateRuleFacts(CandidateRuleDomain domain, List<CandidateRuleFact> facts) {
			this(domain, facts, List.of());
		}

		CandidateRuleFacts(CandidateRuleDomain domain, List<CandidateRuleFact> facts,
			List<CpRuleFamily> cpFamilies) {
			this(domain, facts, cpFamilies, List.of());
		}

		CandidateRuleFacts(CandidateRuleDomain domain, List<CandidateRuleFact> facts,
			List<CpRuleFamily> cpFamilies, List<CandidateRuleRelation> candidateRelations) {
			this.domain = Objects.requireNonNull(domain, "domain");
			Objects.requireNonNull(facts, "facts");
			if(facts.size() != domain.orderedRuleKeys().size())
				throw new IllegalArgumentException("Candidate rule fact/domain count differs");
			LinkedHashMap<CandidateRuleKey,CandidateRuleFact> indexed = new LinkedHashMap<>();
			for(int i = 0; i < facts.size(); i++) {
				CandidateRuleKey expected = domain.orderedRuleKeys().get(i);
				CandidateRuleFact fact = facts.get(i);
				Objects.requireNonNull(fact, "candidate rule fact");
				if(fact.key().parentOccurrence() != expected.parentOccurrence()
					|| !fact.key().orderedInputs().equals(expected.orderedInputs()))
					throw new IllegalArgumentException("Candidate rule fact order/domain key differs");
				if(indexed.putIfAbsent(fact.key(), fact) != null)
					throw new IllegalArgumentException("Duplicate exact candidate rule fact: " + fact.key());
			}
			orderedFacts = List.copyOf(indexed.values());
			factsByKey = Collections.unmodifiableMap(indexed);
			Map<CompiledHopKey,List<CandidateRuleFact>> parentIndex = new IdentityHashMap<>();
			Map<CandidateRuleKey,RealizationIndex> realizationIndex = new IdentityHashMap<>();
			for(CandidateRuleFact fact : orderedFacts)
				parentIndex.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(fact);
			for(CandidateRuleFact fact : orderedFacts) {
				Map<PlacementRealizationKey,IndexedRealization> indexedRealizations = new java.util.HashMap<>();
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations()) {
						IndexedRealization prior = indexedRealizations.get(realization.key());
						indexedRealizations.put(realization.key(), prior == null
							? new IndexedRealization(realization, 1)
							: new IndexedRealization(prior.realization(), Math.incrementExact(prior.matches())));
					}
				realizationIndex.put(fact.key(), new RealizationIndex(
					Collections.unmodifiableMap(indexedRealizations)));
			}
			parentIndex.replaceAll((ignored, parentFacts) -> List.copyOf(parentFacts));
			factsByParent = Collections.unmodifiableMap(parentIndex);
			realizationsByRule = Collections.unmodifiableMap(realizationIndex);
			this.cpFamilies = List.copyOf(Objects.requireNonNull(cpFamilies, "CP rule families"));
			Map<CompiledHopKey,List<CpRuleFamily>> familyIndex = new IdentityHashMap<>();
			for(CpRuleFamily family : this.cpFamilies) {
				if(!domain.containsExactParent(family.parent()))
					throw new IllegalArgumentException("CP rule family parent is outside candidate domain");
				familyIndex.computeIfAbsent(family.parent(), ignored -> new ArrayList<>()).add(family);
			}
			for(List<CpRuleFamily> families : familyIndex.values())
				for(int left = 0; left < families.size(); left++)
					for(int right = left + 1; right < families.size(); right++)
						if(overlap(families.get(left), families.get(right)))
							throw new IllegalArgumentException("Overlapping CP rule families for one parent");
			for(CandidateRuleFact explicit : orderedFacts)
				for(CpRuleFamily family : familyIndex.getOrDefault(
					explicit.key().parentOccurrence(), List.of()))
					if(family.contains(explicit.key().orderedInputs()))
						throw new IllegalArgumentException(
							"Explicit candidate row overlaps a CP rule family");
			familyIndex.replaceAll((ignored, families) -> List.copyOf(families));
			cpFamiliesByParent = Collections.unmodifiableMap(familyIndex);
			this.candidateRelations = List.copyOf(Objects.requireNonNull(
				candidateRelations, "candidate rule relations"));
			Map<CompiledHopKey,List<CandidateRuleRelation>> relationIndex = new IdentityHashMap<>();
			for(CandidateRuleRelation relation : this.candidateRelations) {
				if(!domain.containsExactParent(relation.parent()))
					throw new IllegalArgumentException("Candidate relation parent is outside candidate domain");
				relationIndex.computeIfAbsent(relation.parent(), ignored -> new ArrayList<>()).add(relation);
			}
			for(List<CandidateRuleRelation> relations : relationIndex.values())
				for(int left = 0; left < relations.size(); left++)
					for(int right = left + 1; right < relations.size(); right++)
						if(overlap(relations.get(left), relations.get(right)))
							throw new IllegalArgumentException("Overlapping candidate relations for one parent");
			for(CandidateRuleFact explicit : orderedFacts)
				for(CandidateRuleRelation relation : relationIndex.getOrDefault(
					explicit.key().parentOccurrence(), List.of()))
					if(relation.contains(explicit.key().orderedInputs()))
						throw new IllegalArgumentException(
							"Explicit candidate row overlaps a candidate relation");
			for(CpRuleFamily family : this.cpFamilies)
				for(CandidateRuleRelation relation : relationIndex.getOrDefault(family.parent(), List.of()))
					if(overlap(family.axes(), relation))
						throw new IllegalArgumentException("CP family overlaps a candidate relation");
			relationIndex.replaceAll((ignored, relations) -> List.copyOf(relations));
			candidateRelationsByParent = Collections.unmodifiableMap(relationIndex);
		}

		private static boolean overlap(CpRuleFamily left, CpRuleFamily right) {
			if(left.axes().size() != right.axes().size())
				return false;
			for(int position = 0; position < left.axes().size(); position++)
				if(left.axes().get(position).stream().noneMatch(right.axes().get(position)::contains))
					return false;
			return true;
		}

		private static boolean overlap(CandidateRuleRelation left, CandidateRuleRelation right) {
			return left.regions().stream().anyMatch(leftRegion -> right.regions().stream()
				.anyMatch(rightRegion -> overlap(leftRegion.axes(), rightRegion.axes())));
		}

		private static boolean overlap(List<List<CandidateInputState>> axes,
			CandidateRuleRelation relation) {
			return relation.regions().stream().anyMatch(region -> overlap(axes, region.axes()));
		}

		private static boolean overlap(List<List<CandidateInputState>> left,
			List<List<CandidateInputState>> right) {
			if(left.size() != right.size())
				return false;
			for(int position = 0; position < left.size(); position++)
				if(left.get(position).stream().noneMatch(right.get(position)::contains))
					return false;
			return true;
		}

		public List<CandidateRuleFact> orderedFacts() { return orderedFacts; }
		public List<CpRuleFamily> cpFamilies() { return cpFamilies; }
		public List<CpRuleFamily> cpFamiliesForParent(CompiledHopKey parentOccurrence) {
			return parentOccurrence == null ? List.of()
				: cpFamiliesByParent.getOrDefault(parentOccurrence, List.of());
		}
		public List<CandidateRuleRelation> candidateRelations() { return candidateRelations; }
		public List<CandidateRuleRelation> candidateRelationsForParent(
			CompiledHopKey parentOccurrence) {
			return parentOccurrence == null ? List.of()
				: candidateRelationsByParent.getOrDefault(parentOccurrence, List.of());
		}

		/** Exact candidate rows for one analysis-owned parent in canonical domain order. */
		public List<CandidateRuleFact> orderedFactsForParent(CompiledHopKey parentOccurrence) {
			if(parentOccurrence == null || !domain.containsExactParent(parentOccurrence))
				return List.of();
			return factsByParent.getOrDefault(parentOccurrence, List.of());
		}

		public CandidateRuleFact requireExact(CompiledHopKey parentOccurrence,
			List<CandidateInputState> orderedInputs) {
			if(parentOccurrence == null || !domain.containsExactParent(parentOccurrence))
				throw new CandidateRuleLookupException(CandidateLookupFailure.NON_CANDIDATE_PARENT,
					"Parent is foreign, copied, or outside the canonical candidate domain");
			if(orderedInputs == null)
				throw new CandidateRuleLookupException(CandidateLookupFailure.PRESENT_NULL,
					"Candidate input vector is null");
			for(CandidateInputState input : orderedInputs)
				if(input == null)
					throw new CandidateRuleLookupException(CandidateLookupFailure.PRESENT_NULL,
						"Present-null cannot be a candidate input state");
			CandidateRuleFact explicit = factsByKey.get(new CandidateRuleKey(parentOccurrence, orderedInputs));
			if(explicit != null)
				return explicit;
			CpRuleFamily matching = null;
			for(CpRuleFamily family : cpFamiliesForParent(parentOccurrence))
				if(family.contains(orderedInputs)) {
					if(matching != null)
						throw new IllegalArgumentException("Exact inputs match multiple CP rule families");
					matching = family;
				}
			if(matching != null)
				return matching.requireExact(orderedInputs);
			CandidateRuleRelation relation = matchingRelation(parentOccurrence, orderedInputs);
			if(relation != null)
				return relation.requireExact(orderedInputs);
			return requireExact(new CandidateRuleKey(parentOccurrence, orderedInputs));
		}

		private CandidateRuleFact requireExact(CandidateRuleKey requested) {
			CompiledHopKey parentOccurrence = requested.parentOccurrence();
			List<CandidateInputState> orderedInputs = requested.orderedInputs();
			CandidateRuleFact fact = factsByKey.get(requested);
			if(fact == null) {
				CpRuleFamily matching = null;
				for(CpRuleFamily family : cpFamiliesForParent(parentOccurrence))
					if(family.contains(orderedInputs)) {
						if(matching != null)
							throw new IllegalArgumentException("Exact inputs match multiple CP rule families");
						matching = family;
					}
				if(matching != null)
					return matching.requireExact(orderedInputs);
				CandidateRuleRelation relation = matchingRelation(parentOccurrence, orderedInputs);
				if(relation != null)
					return relation.requireExact(orderedInputs);
			}
			if(fact == null) {
				if(domain.privacyRejects(parentOccurrence, orderedInputs))
					throw new CandidateRuleLookupException(CandidateLookupFailure.PRIVACY_EXCLUDED,
						"Certified protected-payload local tuple: " + parentOccurrence.normalizedSignature());
				boolean reordered = orderedFacts.stream().filter(candidate ->
					candidate.key().parentOccurrence() == parentOccurrence
						&& candidate.key().orderedInputs().size() == orderedInputs.size())
					.anyMatch(candidate -> sameMultiplicity(candidate.key().orderedInputs(), orderedInputs));
				throw new CandidateRuleLookupException(reordered ? CandidateLookupFailure.REORDERED_INPUTS
					: CandidateLookupFailure.MISSING_FACT, "Exact candidate rule fact is missing: parent="
						+ parentOccurrence.normalizedSignature() + ", requested=" + orderedInputs
						+ ", available=" + orderedFacts.stream()
							.filter(candidate -> candidate.key().parentOccurrence() == parentOccurrence)
							.map(candidate -> candidate.key().orderedInputs().toString()).toList());
			}
			if(fact.key().parentOccurrence() != parentOccurrence
				|| !fact.key().orderedInputs().equals(orderedInputs))
				throw new IllegalArgumentException("Candidate rule lookup identity or order differs");
			return fact;
		}

		private CandidateRuleRelation matchingRelation(CompiledHopKey parentOccurrence,
			List<CandidateInputState> orderedInputs) {
			CandidateRuleRelation matching = null;
			for(CandidateRuleRelation relation : candidateRelationsForParent(parentOccurrence))
				if(relation.contains(orderedInputs)) {
					if(matching != null)
						throw new IllegalArgumentException("Exact inputs match multiple candidate relations");
					matching = relation;
				}
			return matching;
		}

		CandidateEmissionRealization requireExactRealization(CandidateRealizationReference reference) {
			Objects.requireNonNull(reference, "reference");
			CandidateRuleKey requested = reference.rule();
			if(!domain.containsExactParent(requested.parentOccurrence()))
				throw new CandidateRuleLookupException(CandidateLookupFailure.NON_CANDIDATE_PARENT,
					"Parent is foreign, copied, or outside the canonical candidate domain");
			CandidateRuleFact fact = requireExact(requested);
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				throw new IllegalArgumentException(
					"Transient compatibility references an unavailable candidate row");
			RealizationIndex index = realizationsByRule.get(fact.key());
			IndexedRealization indexed = index == null
				? indexRealizations(fact).realizations().get(reference.realization())
				: index.realizations().get(reference.realization());
			int matches = indexed == null ? 0 : indexed.matches();
			if(matches != 1)
				throw new IllegalArgumentException(
					"Transient compatibility realization is missing or ambiguous: reference="
						+ reference.normalizedSignature() + ", matching=" + matches
						+ ", available=" + fact.allowedEmissionFacts().stream()
							.flatMap(emission -> emission.realizations().stream())
							.map(CandidateEmissionRealization::normalizedSignature).toList());
			return indexed.realization();
		}

		private static RealizationIndex indexRealizations(CandidateRuleFact fact) {
			Map<PlacementRealizationKey,IndexedRealization> indexed = new java.util.HashMap<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				for(CandidateEmissionRealization realization : emission.realizations()) {
					IndexedRealization prior = indexed.get(realization.key());
					indexed.put(realization.key(), prior == null
						? new IndexedRealization(realization, 1)
						: new IndexedRealization(prior.realization(), Math.incrementExact(prior.matches())));
				}
			return new RealizationIndex(Collections.unmodifiableMap(indexed));
		}

		private static boolean sameMultiplicity(List<CandidateInputState> left, List<CandidateInputState> right) {
			Map<CandidateInputState,Integer> counts = new LinkedHashMap<>();
			for(CandidateInputState value : left) counts.merge(value, 1, Integer::sum);
			for(CandidateInputState value : right) counts.merge(value, -1, Integer::sum);
			return counts.values().stream().allMatch(count -> count == 0);
		}
	}

	/**
	 * Analysis-owned canonical candidate receipts and their exact structural order.
	 * Candidate rule/emission identities are immutable, so selectors must reuse this
	 * domain instead of rebuilding receipts and their nested textual signatures in
	 * every assignment, closure pass, and validation call.
	 */
	private static final class CandidateReceiptDomain {
		private record ReceiptGroupOrderKey(String rule, String emission, String realization)
			implements Comparable<ReceiptGroupOrderKey> {
			@Override public int compareTo(ReceiptGroupOrderKey that) {
				int order = compareField(rule, that.rule);
				if(order == 0)
					order = compareField(emission, that.emission);
				return order != 0 ? order : compareField(realization, that.realization);
			}

			private static int compareField(String left, String right) {
				int order = compareLengthPrefixes(left.length(), right.length());
				return order != 0 ? order : left.compareTo(right);
			}

			private long retainedCharacters() {
				return (long) rule.length() + emission.length() + realization.length();
			}
		}

		private static final class ReceiptGroup {
			private final CandidateRuleKey rule;
			private final CandidateEmissionFact emission;
			private final CandidateEmissionRealization realization;
			private final List<CandidateRealizationSupportClause> clauses;
			private final SearchSpaceMetrics metrics;
			private Map<CandidateRealizationSupportClause,CandidateSelectionReceipt> receipts;
			private Map<CandidateRealizationSupportClause,Integer> factorizedRanks;
			private FactorizedRankIndex factorizedRankIndex;
			private int rankBase = -1;
			private int[] clauseRanks;
			private void resetRanks() {
				rankBase = -1;
				clauseRanks = null;
				factorizedRanks = null;
				factorizedRankIndex = null;
			}

			private ReceiptGroup(CandidateRuleKey rule, CandidateEmissionFact emission,
				CandidateEmissionRealization realization, SearchSpaceMetrics metrics) {
				this.rule = rule;
				this.emission = emission;
				this.realization = realization;
				this.clauses = realization.supportClauses();
				this.metrics = metrics;
			}

			private int ownedClauseIndex(CandidateRealizationSupportClause clause) {
				int index = realization.supportClauseIdentityOrdinal(clause);
				if(index >= 0)
					return index;
				throw new IllegalArgumentException(
					"Candidate support clause is outside the analysis-owned receipt domain");
			}

			private synchronized CandidateSelectionReceipt receipt(
				CandidateRealizationSupportClause clause) {
				ownedClauseIndex(clause);
				if(receipts == null)
					receipts = new IdentityHashMap<>();
				CandidateSelectionReceipt current = receipts.get(clause);
				if(current == null) {
					current = new CandidateSelectionReceipt(
						rule, emission, realization, clause, List.of());
					receipts.put(clause, current);
					if(metrics != null)
						metrics.recordCandidateReceiptCreated();
				}
				return current;
			}

			private int rank(CandidateRealizationSupportClause clause) {
				int index = ownedClauseIndex(clause);
				if(clauseRanks != null)
					return clauseRanks[index];
				if(rankBase < 0)
					throw new IllegalStateException("Canonical candidate receipt group has no structural rank");
				if(clauses instanceof FactorizedSupportClauses factorized) {
					synchronized(this) {
						if(factorizedRanks == null)
							factorizedRanks = new IdentityHashMap<>();
						Integer current = factorizedRanks.get(clause);
						if(current == null) {
							if(factorizedRankIndex == null)
								factorizedRankIndex = FactorizedRankIndex.create(factorized, clause);
							current = Math.addExact(rankBase,
								factorizedRankIndex.canonicalRank(clause));
							factorizedRanks.put(clause, current);
						}
						return current;
					}
				}
				return Math.addExact(rankBase, index);
			}

			/** Immutable exact rank index shared by all lazy receipt requests in one group. */
			private static final class FactorizedRankIndex {
				private final List<List<CandidateRealizationInputBinding>> factors;
				private final List<List<CanonicalText>> optionKeys;
				private final List<Map<Integer,Integer>> suffixCounts;
				private final int fixedLength;

				private FactorizedRankIndex(List<List<CandidateRealizationInputBinding>> factors,
					List<List<CanonicalText>> optionKeys, List<Map<Integer,Integer>> suffixCounts,
					int fixedLength) {
					this.factors = factors;
					this.optionKeys = optionKeys;
					this.suffixCounts = suffixCounts;
					this.fixedLength = fixedLength;
				}

				private static FactorizedRankIndex create(FactorizedSupportClauses factorized,
					CandidateRealizationSupportClause selected) {
					List<List<CandidateRealizationInputBinding>> factors = factorized.factors();
					CanonicalTextContext context = new CanonicalTextContext();
					List<List<CanonicalText>> optionKeys = new ArrayList<>(factors.size());
					int selectedBindingLength = 0;
					for(int axis = 0; axis < factors.size(); axis++) {
						List<CanonicalText> keys = new ArrayList<>(factors.get(axis).size());
						for(CandidateRealizationInputBinding option : factors.get(axis))
							keys.add(canonicalOrderingKey(option, context));
						optionKeys.add(List.copyOf(keys));
						int selectedOption = factors.get(axis).indexOf(selected.inputBindings().get(axis));
						if(selectedOption < 0)
							throw new IllegalArgumentException(
								"Factorized receipt clause contains a foreign input binding");
						selectedBindingLength = Math.addExact(selectedBindingLength,
							keys.get(selectedOption).length);
					}
					int fixedLength = Math.subtractExact(
						canonicalOrderingKey(selected, context).length, selectedBindingLength);
					List<List<CanonicalText>> immutableKeys = List.copyOf(optionKeys);
					return new FactorizedRankIndex(factors, immutableKeys,
						factorizedSuffixLengthCounts(immutableKeys), fixedLength);
				}

				/** Exact length-prefix then lexical rank without expanding the Cartesian relation. */
				private int canonicalRank(CandidateRealizationSupportClause selected) {
					int selectedBindingLength = 0;
					int[] selectedOptions = new int[factors.size()];
					for(int axis = 0; axis < factors.size(); axis++) {
						List<CandidateRealizationInputBinding> options = factors.get(axis);
						List<CanonicalText> keys = optionKeys.get(axis);
						CandidateRealizationInputBinding binding = selected.inputBindings().get(axis);
						int selectedOption = options.indexOf(binding);
						if(selectedOption < 0)
							throw new IllegalArgumentException(
								"Factorized receipt clause contains a foreign input binding");
						selectedOptions[axis] = selectedOption;
						selectedBindingLength = Math.addExact(selectedBindingLength,
							keys.get(selectedOption).length);
					}

					int clauseLength = Math.addExact(fixedLength, selectedBindingLength);
					Map<Integer,Integer> allLengths = suffixCounts.get(0);
					int rank = 0;
					for(Map.Entry<Integer,Integer> entry : allLengths.entrySet())
						if(compareLengthPrefixes(Math.addExact(fixedLength, entry.getKey()), clauseLength) < 0)
							rank = Math.addExact(rank, entry.getValue());

					int prefixLength = 0;
					for(int axis = 0; axis < factors.size(); axis++) {
						List<CanonicalText> keys = optionKeys.get(axis);
						for(int option = 0; option < selectedOptions[axis]; option++) {
							int requiredSuffixLength = selectedBindingLength - prefixLength
								- keys.get(option).length;
							rank = Math.addExact(rank,
								suffixCounts.get(axis + 1).getOrDefault(requiredSuffixLength, 0));
						}
						prefixLength = Math.addExact(prefixLength,
							keys.get(selectedOptions[axis]).length);
					}
					return rank;
				}
			}

			private static List<Map<Integer,Integer>> factorizedSuffixLengthCounts(
				List<List<CanonicalText>> optionKeys) {
				List<Map<Integer,Integer>> suffix = new ArrayList<>(
					Collections.nCopies(optionKeys.size() + 1, null));
				suffix.set(optionKeys.size(), Map.of(0, 1));
				for(int axis = optionKeys.size() - 1; axis >= 0; axis--) {
					Map<Integer,Integer> counts = new java.util.HashMap<>();
					for(CanonicalText option : optionKeys.get(axis))
						for(Map.Entry<Integer,Integer> tail : suffix.get(axis + 1).entrySet())
							counts.merge(Math.addExact(option.length, tail.getKey()),
								tail.getValue(), Math::addExact);
					suffix.set(axis, counts);
				}
				return suffix;
			}

			private int assignCanonicalRanks(int firstRank) {
				int size = clauses.size();
				if(size == 1) {
					rankBase = firstRank;
					return Math.incrementExact(firstRank);
				}
				// Rank requested clauses algebraically; tied group identities still use
				// the exact interleaved legacy path in CandidateReceiptDomain.ensureRanks.
				if(clauses instanceof FactorizedSupportClauses) {
					rankBase = firstRank;
					return Math.addExact(firstRank, size);
				}
				int[] order = new int[size];
				int[] work = new int[size];
				int[] lengths = new int[size];
				List<CanonicalText> keys;
				if(clauses instanceof IndexedSupportClauses indexed) {
					CanonicalTextContext context = new CanonicalTextContext();
					List<CanonicalText> indexedKeys = new ArrayList<>(size);
					for(int row = 0; row < size; row++)
						indexedKeys.add(canonicalIndexedClauseOrderingText(indexed, row, context));
					keys = List.copyOf(indexedKeys);
				}
				else {
					keys = retainedCanonicalOrderingKeys(clauses);
					if(keys == null)
						keys = canonicalOrderingKeys(clauses, new CanonicalTextContext());
				}
				for(int index = 0; index < size; index++) {
					order[index] = index;
					lengths[index] = keys.get(index).length;
				}
				stableSortByLengthPrefix(order, work, lengths, 0, size);
				boolean alreadyCanonical = true;
				for(int index = 0; index < size; index++)
					alreadyCanonical &= order[index] == index;
				if(alreadyCanonical)
					rankBase = firstRank;
				else {
					clauseRanks = new int[size];
					for(int index = 0; index < size; index++)
						clauseRanks[order[index]] = Math.addExact(firstRank, index);
				}
				return Math.addExact(firstRank, size);
			}
		}

		private record RankedReceiptGroup(ReceiptGroup group, ReceiptGroupOrderKey orderKey) { }
		private record RankedClause(ReceiptGroup group, CanonicalText key, int clauseIndex) { }

		private final Map<CandidateRuleKey,
			Map<CandidateEmissionFact,Map<CandidateEmissionRealization,ReceiptGroup>>> groupsByIdentity;
		private final List<ReceiptGroup> groups;
		private final CandidateRuleFacts facts;
		private final SearchSpaceMetrics metrics;
		private volatile boolean ranksInitialized;

		private CandidateReceiptDomain(CandidateRuleFacts facts) {
			this.facts = facts;
			Map<CandidateRuleKey,Map<CandidateEmissionFact,
				Map<CandidateEmissionRealization,ReceiptGroup>>> indexed = new IdentityHashMap<>();
			List<ReceiptGroup> allGroups = new ArrayList<>();
			metrics = PlacementIdentity.activeMetrics();
			for(CandidateRuleFact fact : facts.orderedFacts()) {
				Map<CandidateEmissionFact,Map<CandidateEmissionRealization,ReceiptGroup>> byEmission =
					new IdentityHashMap<>();
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
					Map<CandidateEmissionRealization,ReceiptGroup> byRealization = new IdentityHashMap<>();
					for(CandidateEmissionRealization realization : emission.realizations()) {
						ReceiptGroup group = new ReceiptGroup(fact.key(), emission, realization, metrics);
						byRealization.put(realization, group);
						allGroups.add(group);
						if(metrics != null)
							metrics.recordReceiptRelationSlots(realization.supportClauses().size());
					}
					byEmission.put(emission, Collections.unmodifiableMap(byRealization));
				}
				indexed.put(fact.key(), Collections.unmodifiableMap(byEmission));
			}
			groups = allGroups;
			groupsByIdentity = indexed;
		}

		private synchronized void registerFamilyFact(CandidateRuleFact fact) {
			if(groupsByIdentity.containsKey(fact.key()))
				return;
			Map<CandidateEmissionFact,Map<CandidateEmissionRealization,ReceiptGroup>> byEmission =
				new IdentityHashMap<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				Map<CandidateEmissionRealization,ReceiptGroup> byRealization = new IdentityHashMap<>();
				for(CandidateEmissionRealization realization : emission.realizations()) {
					ReceiptGroup group = new ReceiptGroup(fact.key(), emission, realization, metrics);
					byRealization.put(realization, group);
					groups.add(group);
					if(metrics != null)
						metrics.recordReceiptRelationSlots(realization.supportClauses().size());
				}
				byEmission.put(emission, byRealization);
			}
			groupsByIdentity.put(fact.key(), byEmission);
			for(ReceiptGroup group : groups)
				group.resetRanks();
			ranksInitialized = false;
		}

		private synchronized void ensureRanks() {
			if(ranksInitialized)
				return;
			List<RankedReceiptGroup> ranked = new ArrayList<>(groups.size());
			for(ReceiptGroup group : groups) {
				ReceiptGroupOrderKey orderKey = new ReceiptGroupOrderKey(
					group.rule.normalizedSignature(), group.emission.selectionSignature(),
					group.realization.key().normalizedSignature());
				ranked.add(new RankedReceiptGroup(group, orderKey));
				if(metrics != null)
					metrics.recordReceiptRankKeyCharacters(orderKey.retainedCharacters());
			}
			ranked.sort(java.util.Comparator.comparing(RankedReceiptGroup::orderKey));
			int rank = 0;
			for(int start = 0; start < ranked.size();) {
				int end = start + 1;
				while(end < ranked.size() && ranked.get(start).orderKey().equals(ranked.get(end).orderKey()))
					end++;
				if(end == start + 1) {
					ReceiptGroup group = ranked.get(start).group();
					rank = group.assignCanonicalRanks(rank);
				}
				else {
					List<RankedClause> clauses = new ArrayList<>();
					CanonicalTextContext context = new CanonicalTextContext();
					CanonicalTextComparison comparison = new CanonicalTextComparison();
					for(int groupIndex = start; groupIndex < end; groupIndex++) {
						ReceiptGroup group = ranked.get(groupIndex).group();
						group.clauseRanks = new int[group.clauses.size()];
						for(int clauseIndex = 0; clauseIndex < group.clauses.size(); clauseIndex++) {
							CanonicalText key = group.clauses instanceof IndexedSupportClauses indexed
								? canonicalIndexedClauseOrderingText(indexed, clauseIndex, context)
								: canonicalOrderingKey(group.clauses.get(clauseIndex), context);
							clauses.add(new RankedClause(group, key, clauseIndex));
						}
					}
					clauses.sort((left, right) -> {
						int order = compareLengthPrefixes(left.key().length, right.key().length);
						return order != 0 ? order : comparison.compare(left.key(), right.key());
					});
					for(RankedClause clause : clauses) {
						clause.group().clauseRanks[clause.clauseIndex()] = rank;
						rank = Math.incrementExact(rank);
					}
				}
				start = end;
			}
			ranksInitialized = true;
		}

		private static void stableSortByLengthPrefix(int[] order, int[] work, int[] lengths,
			int start, int end) {
			if(end - start < 2)
				return;
			int middle = (start + end) >>> 1;
			stableSortByLengthPrefix(order, work, lengths, start, middle);
			stableSortByLengthPrefix(order, work, lengths, middle, end);
			int left = start;
			int right = middle;
			int output = start;
			while(left < middle && right < end) {
				int comparison = compareLengthPrefixes(lengths[order[left]], lengths[order[right]]);
				work[output++] = comparison <= 0 ? order[left++] : order[right++];
			}
			while(left < middle)
				work[output++] = order[left++];
			while(right < end)
				work[output++] = order[right++];
			System.arraycopy(work, start, order, start, end - start);
		}

		private static int compareLengthPrefixes(int left, int right) {
			int leftDigits = decimalDigits(left);
			int rightDigits = decimalDigits(right);
			int positions = Math.max(leftDigits, rightDigits) + 1;
			for(int position = 0; position < positions; position++) {
				char leftCharacter = position < leftDigits
					? decimalDigit(left, leftDigits, position) : ':';
				char rightCharacter = position < rightDigits
					? decimalDigit(right, rightDigits, position) : ':';
				if(leftCharacter != rightCharacter)
					return Character.compare(leftCharacter, rightCharacter);
			}
			return 0;
		}

		private static int decimalDigits(int value) {
			if(value < 0)
				throw new IllegalArgumentException("Canonical text length must be non-negative");
			int digits = 1;
			for(int remaining = value; remaining >= 10; remaining /= 10)
				digits++;
			return digits;
		}

		private static char decimalDigit(int value, int digits, int position) {
			int divisor = 1;
			for(int index = position + 1; index < digits; index++)
				divisor *= 10;
			return (char) ('0' + value / divisor % 10);
		}

		private synchronized ReceiptGroup requireGroup(CandidateRuleKey rule,
			CandidateEmissionFact emission, CandidateEmissionRealization realization) {
			Map<CandidateEmissionFact,Map<CandidateEmissionRealization,ReceiptGroup>> byEmission =
				groupsByIdentity.get(rule);
			if(byEmission == null) {
				CandidateRuleFact exact = facts.requireExact(rule.parentOccurrence(), rule.orderedInputs());
				if(exact.key() == rule)
					registerFamilyFact(exact);
				byEmission = groupsByIdentity.get(rule);
			}
			Map<CandidateEmissionRealization,ReceiptGroup> byRealization =
				byEmission == null ? null : byEmission.get(emission);
			ReceiptGroup group = byRealization == null ? null : byRealization.get(realization);
			if(group == null)
				throw new IllegalArgumentException(
					"Candidate rule/emission/realization is outside the analysis-owned receipt domain");
			return group;
		}

		private CandidateSelectionReceipt require(CandidateRuleKey rule,
			CandidateEmissionFact emission, CandidateEmissionRealization realization,
			CandidateRealizationSupportClause clause) {
			return requireGroup(rule, emission, realization).receipt(clause);
		}

		private CandidateSelectionReceipt requireSingleton(CandidateRuleKey rule,
			CandidateEmissionFact emission) {
			if(emission.realizations().size() != 1)
				throw new IllegalArgumentException("Candidate emission realization is ambiguous");
			CandidateEmissionRealization realization = emission.realizations().get(0);
			return require(rule, emission, realization, realization.requireSingletonSupportClause());
		}

		private CandidateSelectionReceipt require(CandidateRuleKey rule,
			CandidateEmissionFact emission, CandidateEmissionRealization realization) {
			return require(rule, emission, realization, realization.requireSingletonSupportClause());
		}

		private List<CandidateSelectionReceipt> requireAll(CandidateRuleKey rule,
			CandidateEmissionFact emission) {
			return emission.realizations().stream().flatMap(realization -> realization.supportClauses().stream()
				.map(clause -> require(rule, emission, realization, clause))).toList();
		}

		private int rank(CandidateSelectionReceipt receipt) {
			ReceiptGroup group = requireGroup(receipt.rule(), receipt.emission(), receipt.realization());
			ensureRanks();
			return group.rank(receipt.supportClause());
		}

		private List<CandidateSelectionReceipt> canonicalize(
			java.util.Collection<CandidateSelectionReceipt> receipts) {
			List<CandidateSelectionReceipt> canonical = new ArrayList<>(receipts.size());
			Map<CandidateSelectionReceipt,Boolean> seen = new IdentityHashMap<>();
			for(CandidateSelectionReceipt receipt : receipts) {
				Objects.requireNonNull(receipt, "candidate receipt");
				CandidateSelectionReceipt exact = require(receipt.rule(), receipt.emission(),
					receipt.realization(), receipt.supportClause());
				if(seen.put(exact, Boolean.TRUE) == null)
					canonical.add(exact);
			}
			if(canonical.size() > 1) {
				ensureRanks();
				canonical.sort(java.util.Comparator.comparingInt(this::rank));
			}
			return List.copyOf(canonical);
		}
	}

	public static final class CandidateConsumerProfileFacts {
		private final CandidateRuleDomain domain;
		private final List<CandidateConsumerProfileFact> orderedFacts;
		private final Map<CandidateConsumerProfileKey,CandidateConsumerProfileFact> factsByKey;

		public CandidateConsumerProfileFacts(CandidateRuleDomain domain,
			List<CandidateConsumerProfileFact> facts) {
			this.domain = Objects.requireNonNull(domain, "domain");
			Objects.requireNonNull(facts, "facts");
			if(facts.size() != domain.orderedConsumerKeys().size())
				throw new IllegalArgumentException("Consumer profile fact/domain count differs");
			LinkedHashMap<CandidateConsumerProfileKey,CandidateConsumerProfileFact> indexed = new LinkedHashMap<>();
			for(int i = 0; i < facts.size(); i++) {
				CandidateConsumerProfileKey expected = domain.orderedConsumerKeys().get(i);
				CandidateConsumerProfileFact fact = Objects.requireNonNull(facts.get(i), "consumer profile fact");
				if(fact.key().consumerOccurrence() != expected.consumerOccurrence()
					|| fact.key().inputPosition() != expected.inputPosition())
					throw new IllegalArgumentException("Consumer profile fact order/domain key differs");
				if(indexed.putIfAbsent(fact.key(), fact) != null)
					throw new IllegalArgumentException("Duplicate consumer profile fact");
			}
			orderedFacts = List.copyOf(indexed.values());
			factsByKey = Collections.unmodifiableMap(indexed);
		}

		public List<CandidateConsumerProfileFact> orderedFacts() { return orderedFacts; }
		public CandidateConsumerProfileFact requireExact(CompiledHopKey consumer, int inputPosition) {
			if(!domain.containsExactParent(consumer))
				throw new CandidateRuleLookupException(CandidateLookupFailure.NON_CANDIDATE_PARENT,
					"Consumer is outside the canonical candidate domain");
			CandidateConsumerProfileFact fact = factsByKey.get(new CandidateConsumerProfileKey(consumer, inputPosition));
			if(fact == null || fact.key().consumerOccurrence() != consumer)
				throw new CandidateRuleLookupException(CandidateLookupFailure.MISSING_FACT,
					"Exact consumer profile fact is missing");
			return fact;
		}
	}

	/** Ordered, deeply immutable detached consumer evidence indexed by exact producer identity. */
	public static final class DetachedConsumerProfileFacts {
		private final List<DetachedConsumerProfileFact> orderedFacts;
		private final Map<CompiledHopKey,List<DetachedConsumerProfileFact>> factsByProducer;

		public DetachedConsumerProfileFacts(List<DetachedConsumerProfileFact> facts,
			Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
			Objects.requireNonNull(facts, "facts");
			Map<CompiledHopKey,List<DetachedConsumerProfileFact>> indexed = new IdentityHashMap<>();
			Set<DetachedConsumerProfileKey> keys = new java.util.HashSet<>();
			List<DetachedConsumerProfileFact> copied = new java.util.ArrayList<>(facts.size());
			for(DetachedConsumerProfileFact fact : facts) {
				Objects.requireNonNull(fact, "detached consumer profile fact");
				if(!analysisKeysByIdentity.containsKey(fact.key().producerOccurrence()))
					throw new IllegalArgumentException("Detached consumer producer is not analysis-owned");
				if(!keys.add(fact.key()))
					throw new IllegalArgumentException("Duplicate detached consumer profile fact");
				indexed.computeIfAbsent(fact.key().producerOccurrence(), ignored -> new java.util.ArrayList<>()).add(fact);
				copied.add(fact);
			}
			orderedFacts = List.copyOf(copied);
			Map<CompiledHopKey,List<DetachedConsumerProfileFact>> immutable = new IdentityHashMap<>();
			indexed.forEach((producer, producerFacts) -> immutable.put(producer, List.copyOf(producerFacts)));
			factsByProducer = Collections.unmodifiableMap(immutable);
		}

		public List<DetachedConsumerProfileFact> orderedFacts() { return orderedFacts; }
		public List<DetachedConsumerProfileFact> requireExactProducer(CompiledHopKey producer) {
			List<DetachedConsumerProfileFact> facts = factsByProducer.get(producer);
			return facts == null ? List.of() : facts;
		}
	}
	/** Exact producer/value pair for one immutable Heuristic demotion fact. */
	public record HeuristicPolicyFact(CompiledHopKey producer, ValueVersionKey valueVersion)
		implements Comparable<HeuristicPolicyFact> {
		public HeuristicPolicyFact {
			Objects.requireNonNull(producer, "producer");
			Objects.requireNonNull(valueVersion, "valueVersion");
			if(!producer.programFingerprint().equals(valueVersion.programFingerprint()))
				throw new IllegalArgumentException("Heuristic policy producer and value fingerprints differ");
		}

		@Override
		public int compareTo(HeuristicPolicyFact that) {
			int producerOrder = producer.compareTo(that.producer);
			return producerOrder != 0 ? producerOrder : valueVersion.compareTo(that.valueVersion);
		}
	}

	public enum HeuristicPathEdgeKind { COMPILED_INPUT, CFG_TRANSIENT_FORWARD }

	/** Exact occurrence-to-occurrence edge used to prove one local Heuristic prefix. */
	public record HeuristicPathEdgeFact(CompiledHopKey producer, CompiledHopKey consumer,
		int inputPosition, ValueVersionKey sourceValueVersion, ValueVersionKey consumerValueVersion,
		HeuristicPathEdgeKind kind) implements Comparable<HeuristicPathEdgeFact> {
		public HeuristicPathEdgeFact {
			Objects.requireNonNull(producer, "producer");
			Objects.requireNonNull(consumer, "consumer");
			if(inputPosition < 0)
				throw new IllegalArgumentException("Heuristic path input position must be non-negative");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(consumerValueVersion, "consumerValueVersion");
			Objects.requireNonNull(kind, "kind");
		}

		@Override public int compareTo(HeuristicPathEdgeFact that) {
			int producerOrder = producer.compareTo(that.producer);
			if(producerOrder != 0) return producerOrder;
			int consumerOrder = consumer.compareTo(that.consumer);
			if(consumerOrder != 0) return consumerOrder;
			int positionOrder = Integer.compare(inputPosition, that.inputPosition);
			return positionOrder != 0 ? positionOrder : kind.compareTo(that.kind);
		}
	}

	/**
	 * Exact common-analysis proof for one path-local LOUT-to-FOUT re-entry. The cost is the
	 * neutral selector's modeled distinct-relocation unit, not an unmodeled runtime estimate.
	 */
	public record HeuristicPathwiseReentryFact(CompiledHopKey localProducer,
		ValueVersionKey sourceValueVersion, CompiledHopKey consumer, int inputPosition,
		CompiledHopKey siblingProducer, ValueVersionKey siblingValueVersion, int siblingInputPosition,
		PlacementState siblingFoutState, DurableAnchorKey durableAnchor,
		PlacementState consumerFoutState, CandidateRuleFact runtimeCandidate,
		RelocationActionKey relocationAction, ObligationKey obligation,
		int modeledDistinctRelocationCost) implements Comparable<HeuristicPathwiseReentryFact> {
		public HeuristicPathwiseReentryFact {
			Objects.requireNonNull(localProducer, "localProducer");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(consumer, "consumer");
			if(inputPosition < 0 || siblingInputPosition < 0 || inputPosition == siblingInputPosition)
				throw new IllegalArgumentException("Heuristic re-entry input positions differ and are non-negative");
			Objects.requireNonNull(siblingProducer, "siblingProducer");
			Objects.requireNonNull(siblingValueVersion, "siblingValueVersion");
			Objects.requireNonNull(siblingFoutState, "siblingFoutState");
			Objects.requireNonNull(durableAnchor, "durableAnchor");
			Objects.requireNonNull(consumerFoutState, "consumerFoutState");
			Objects.requireNonNull(runtimeCandidate, "runtimeCandidate");
			Objects.requireNonNull(relocationAction, "relocationAction");
			Objects.requireNonNull(obligation, "obligation");
			if(modeledDistinctRelocationCost != 1)
				throw new IllegalArgumentException("One re-entry fact models one distinct relocation unit");
		}

		@Override public int compareTo(HeuristicPathwiseReentryFact that) {
			int consumerOrder = consumer.compareTo(that.consumer);
			if(consumerOrder != 0) return consumerOrder;
			int positionOrder = Integer.compare(inputPosition, that.inputPosition);
			return positionOrder != 0 ? positionOrder : relocationAction.compareTo(that.relocationAction);
		}
	}

	/**
	 * Exact common-analysis proof that one coordinator-local input can remain local while the
	 * runtime executes its consumer natively over one exactly resident federated sibling. Unlike a
	 * pathwise re-entry, this fact owns no reusable relocation action: the exact runtime candidate
	 * consumes the local input as {@link InputPresence#ABSENT_LOCAL}. The sibling may itself be a
	 * derived FOUT, so this proof deliberately depends on its exact placement state rather than on
	 * a source-data durable anchor. The consumer may retain FOUT, or emit an exact legal LOUT
	 * result when a protected sibling prevents a nested reduction from executing in CP.
	 */
	public record HeuristicNativeContinuationFact(CompiledHopKey localProducer,
		ValueVersionKey localValueVersion, CompiledHopKey consumer, int localInputPosition,
		CompiledHopKey siblingProducer, ValueVersionKey siblingValueVersion, int siblingInputPosition,
		PlacementState siblingFoutState, PlacementState consumerState,
		CandidateRuleFact runtimeCandidate)
		implements Comparable<HeuristicNativeContinuationFact> {
		public HeuristicNativeContinuationFact {
			Objects.requireNonNull(localProducer, "localProducer");
			Objects.requireNonNull(localValueVersion, "localValueVersion");
			Objects.requireNonNull(consumer, "consumer");
			if(localInputPosition < 0 || siblingInputPosition < 0
				|| localInputPosition == siblingInputPosition)
				throw new IllegalArgumentException(
					"Heuristic native-continuation input positions differ and are non-negative");
			Objects.requireNonNull(siblingProducer, "siblingProducer");
			Objects.requireNonNull(siblingValueVersion, "siblingValueVersion");
			Objects.requireNonNull(siblingFoutState, "siblingFoutState");
			Objects.requireNonNull(consumerState, "consumerState");
			Objects.requireNonNull(runtimeCandidate, "runtimeCandidate");
		}

		@Override public int compareTo(HeuristicNativeContinuationFact that) {
			int consumerOrder = consumer.compareTo(that.consumer);
			if(consumerOrder != 0)
				return consumerOrder;
			int localPositionOrder = Integer.compare(localInputPosition, that.localInputPosition);
			if(localPositionOrder != 0)
				return localPositionOrder;
			return Integer.compare(siblingInputPosition, that.siblingInputPosition);
		}
	}

	/** One exact marker-local path projection; no dominance or descendant closure is implied. */
	public record HeuristicPathFact(HeuristicPolicyFact demotion, List<CompiledHopKey> localPrefix,
		List<HeuristicPathEdgeFact> edges, List<HeuristicPathwiseReentryFact> reentries,
		List<HeuristicNativeContinuationFact> nativeContinuations)
		implements Comparable<HeuristicPathFact> {
		public HeuristicPathFact(HeuristicPolicyFact demotion, List<CompiledHopKey> localPrefix,
			List<HeuristicPathEdgeFact> edges, List<HeuristicPathwiseReentryFact> reentries) {
			this(demotion, localPrefix, edges, reentries, List.of());
		}

		public HeuristicPathFact {
			Objects.requireNonNull(demotion, "demotion");
			localPrefix = Objects.requireNonNull(localPrefix, "localPrefix").stream()
				.map(key -> Objects.requireNonNull(key, "local prefix key"))
				.distinct().sorted().toList();
			if(!localPrefix.contains(demotion.producer()))
				throw new IllegalArgumentException("Heuristic local prefix omits its demotion producer");
			edges = Objects.requireNonNull(edges, "edges").stream()
				.map(edge -> Objects.requireNonNull(edge, "path edge")).distinct().sorted().toList();
			reentries = Objects.requireNonNull(reentries, "reentries").stream()
				.map(fact -> Objects.requireNonNull(fact, "re-entry fact"))
				.distinct().sorted().toList();
			nativeContinuations = Objects.requireNonNull(nativeContinuations, "nativeContinuations").stream()
				.map(fact -> Objects.requireNonNull(fact, "native continuation fact"))
				.distinct().sorted().toList();
		}

		@Override public int compareTo(HeuristicPathFact that) {
			return demotion.compareTo(that.demotion);
		}
	}

	/** Deterministic, deeply immutable set of producer-scoped Heuristic demotions. */
	public record HeuristicPolicyFacts(List<HeuristicPolicyFact> demotions, List<HeuristicPathFact> paths) {
		public HeuristicPolicyFacts(List<HeuristicPolicyFact> demotions) {
			this(demotions, List.of());
		}

		public HeuristicPolicyFacts {
			Objects.requireNonNull(demotions, "demotions");
			List<HeuristicPolicyFact> sorted = demotions.stream()
				.map(fact -> Objects.requireNonNull(fact, "demotion fact")).sorted().toList();
			Map<CompiledHopKey, ValueVersionKey> valuesByProducer = new LinkedHashMap<>();
			Map<ValueVersionKey, CompiledHopKey> producersByValue = new LinkedHashMap<>();
			HeuristicPolicyFact previous = null;
			for(HeuristicPolicyFact fact : sorted) {
				if(fact.equals(previous))
					throw new IllegalArgumentException("Duplicate Heuristic policy fact");
				ValueVersionKey priorValue = valuesByProducer.putIfAbsent(fact.producer(), fact.valueVersion());
				if(priorValue != null && !priorValue.equals(fact.valueVersion()))
					throw new IllegalArgumentException("Heuristic policy producer maps to multiple values");
				CompiledHopKey priorProducer = producersByValue.putIfAbsent(fact.valueVersion(), fact.producer());
				if(priorProducer != null && !priorProducer.equals(fact.producer()))
					throw new IllegalArgumentException("Heuristic policy value maps to multiple producers");
				previous = fact;
			}
			demotions = List.copyOf(sorted);
			paths = Objects.requireNonNull(paths, "paths").stream()
				.map(path -> Objects.requireNonNull(path, "Heuristic path fact")).sorted().toList();
			Set<HeuristicPolicyFact> pathDemotions = new java.util.HashSet<>();
			for(HeuristicPathFact path : paths) {
				if(!demotions.contains(path.demotion()))
					throw new IllegalArgumentException("Heuristic path has an unknown demotion");
				if(!pathDemotions.add(path.demotion()))
					throw new IllegalArgumentException("Duplicate Heuristic path demotion");
			}
		}
	}

	public record NodeShapeFact(DataType dataType, long rows, long cols) {
		public NodeShapeFact { Objects.requireNonNull(dataType, "dataType"); }
		public boolean knownPositiveMatrix() { return dataType == DataType.MATRIX && rows > 0 && cols > 0; }
	}

	/** Cost inputs captured before runtime recompilation can refresh mutable Hop estimates. */
	public record PhysicalCostEstimateFact(DataType dataType, boolean dimensionsKnown,
		long rows, long cols, long nnz, double outputMemEstimate,
		double effectiveOutputMemEstimate, double effectiveUploadMemEstimate,
		double multiReturnOutputMemEstimate, long multiReturnRows, long multiReturnCols) { }

	/** Finite dimension lattice used by the occurrence-scoped common analysis. */
	public enum DimensionKnowledge { BOTTOM, EXACT, UNKNOWN }

	public record DimensionFact(DimensionKnowledge knowledge, long value) {
		public DimensionFact {
			Objects.requireNonNull(knowledge, "knowledge");
			if(knowledge == DimensionKnowledge.EXACT && value < 0)
				throw new IllegalArgumentException("An exact dimension must be non-negative");
			if(knowledge != DimensionKnowledge.EXACT && value != -1)
				throw new IllegalArgumentException("A non-exact dimension must use value -1");
		}

		public static DimensionFact bottom() {
			return new DimensionFact(DimensionKnowledge.BOTTOM, -1);
		}

		public static DimensionFact unknown() {
			return new DimensionFact(DimensionKnowledge.UNKNOWN, -1);
		}

		public static DimensionFact exact(long value) {
			return new DimensionFact(DimensionKnowledge.EXACT, value);
		}

		public boolean isExact(long expected) {
			return knowledge == DimensionKnowledge.EXACT && value == expected;
		}

		public boolean isExact() {
			return knowledge == DimensionKnowledge.EXACT;
		}

		public DimensionFact join(DimensionFact that) {
			Objects.requireNonNull(that, "that");
			if(knowledge == DimensionKnowledge.BOTTOM)
				return that;
			if(that.knowledge == DimensionKnowledge.BOTTOM)
				return this;
			if(knowledge == DimensionKnowledge.UNKNOWN || that.knowledge == DimensionKnowledge.UNKNOWN)
				return unknown();
			return value == that.value ? this : unknown();
		}

		public String normalizedSignature() {
			return knowledge.name() + (isExact() ? ":" + value : "");
		}
	}

	public enum MatrixOrientation { UNKNOWN, MATRIX, ROW_VECTOR, COLUMN_VECTOR, SCALAR_MATRIX }

	/**
	 * Abstract matrix shape that remains sound when concrete HOP dimensions are unknown.
	 * In particular, one exact dimension is sufficient to prove vector orientation.
	 */
	public record AbstractShapeFact(DataType dataType, DimensionFact rows, DimensionFact cols) {
		public AbstractShapeFact {
			Objects.requireNonNull(dataType, "dataType");
			Objects.requireNonNull(rows, "rows");
			Objects.requireNonNull(cols, "cols");
		}

		public static AbstractShapeFact bottom(DataType dataType) {
			return new AbstractShapeFact(dataType, DimensionFact.bottom(), DimensionFact.bottom());
		}

		public static AbstractShapeFact fromConcrete(NodeShapeFact shape) {
			return new AbstractShapeFact(shape.dataType(),
				shape.rows() >= 0 ? DimensionFact.exact(shape.rows()) : DimensionFact.bottom(),
				shape.cols() >= 0 ? DimensionFact.exact(shape.cols()) : DimensionFact.bottom());
		}

		public AbstractShapeFact join(AbstractShapeFact that) {
			Objects.requireNonNull(that, "that");
			DataType joinedType = dataType == DataType.UNKNOWN ? that.dataType
				: that.dataType == DataType.UNKNOWN || dataType == that.dataType ? dataType : DataType.UNKNOWN;
			return new AbstractShapeFact(joinedType, rows.join(that.rows), cols.join(that.cols));
		}

		public boolean isMatrix() {
			return dataType == DataType.MATRIX;
		}

		public boolean provablyRowVector() {
			return isMatrix() && rows.isExact(1);
		}

		public boolean provablyColumnVector() {
			return isMatrix() && cols.isExact(1);
		}

		public boolean provablyVector() {
			return provablyRowVector() || provablyColumnVector();
		}

		public MatrixOrientation orientation() {
			if(!isMatrix())
				return MatrixOrientation.UNKNOWN;
			if(rows.isExact(1) && cols.isExact(1))
				return MatrixOrientation.SCALAR_MATRIX;
			if(rows.isExact(1))
				return MatrixOrientation.ROW_VECTOR;
			if(cols.isExact(1))
				return MatrixOrientation.COLUMN_VECTOR;
			return rows.isExact() && cols.isExact() ? MatrixOrientation.MATRIX : MatrixOrientation.UNKNOWN;
		}

		public String normalizedSignature() {
			return dataType.name() + '|' + rows.normalizedSignature() + '|' + cols.normalizedSignature();
		}
	}

	/** Exact occurrence-scoped scalar constant, after safe CFG/function joins. */
	public record ScalarLiteralFact(ValueType valueType, String canonicalValue) {
		public ScalarLiteralFact {
			Objects.requireNonNull(valueType, "valueType");
			if(canonicalValue == null)
				throw new IllegalArgumentException("Scalar literal value must not be null");
		}

		public String normalizedSignature() {
			return valueType.name() + ':' + canonicalValue;
		}
	}
	/** Exact structural matrix/frame input edge between two compiled Hop owners. */
	public static final class CompiledInputEdgeFact {
		private final CompiledHopKey producer;
		private final CompiledHopKey consumer;
		private final int inputPosition;

		CompiledInputEdgeFact(CompiledHopKey producer, CompiledHopKey consumer, int inputPosition) {
			this.producer = Objects.requireNonNull(producer, "producer");
			this.consumer = Objects.requireNonNull(consumer, "consumer");
			if(inputPosition < 0)
				throw new IllegalArgumentException("inputPosition must be non-negative");
			this.inputPosition = inputPosition;
		}

		public CompiledHopKey producer() { return producer; }
		public CompiledHopKey consumer() { return consumer; }
		public int inputPosition() { return inputPosition; }
	}

	/** Common identity surface for non-physical candidate inputs. */
	public interface LogicalCandidateInputFact {
		CompiledHopKey sourceOccurrence();
		CompiledHopKey targetRead();
		int logicalPosition();
	}

	/** Exact typed evidence for one compatible writer-reader physical realization pair. */
	public record TransientCompatibilityProof(DurableAnchorKey sourceAnchor,
		DurableAnchorKey readerAnchor, DurableAnchorKey nativeWorkerPoolWitness,
		boolean nativeWorkerPoolLayoutExact, List<PlacementProofKey> dependencies)
		implements Comparable<TransientCompatibilityProof> {
		public TransientCompatibilityProof(DurableAnchorKey sourceAnchor,
			DurableAnchorKey readerAnchor, List<PlacementProofKey> dependencies) {
			this(sourceAnchor, readerAnchor, null, true, dependencies);
		}
		public TransientCompatibilityProof {
			dependencies = canonicalComparableList(dependencies, "transient compatibility proof dependency");
			if((sourceAnchor == null) != (readerAnchor == null))
				throw new IllegalArgumentException("Transient compatibility anchor proof must name both layouts");
			if(sourceAnchor != null && !PlacementIdentity.samePhysicalLayout(sourceAnchor, readerAnchor))
				throw new IllegalArgumentException("Transient compatibility anchors have different physical layouts");
			if(sourceAnchor != null && nativeWorkerPoolWitness != null)
				throw new IllegalArgumentException("Transient compatibility cannot mix durable and native authority");
			if(nativeWorkerPoolWitness == null && !nativeWorkerPoolLayoutExact)
				throw new IllegalArgumentException("Dynamic transient compatibility requires a worker-pool witness");
		}
		public boolean provesNativeContinuity(CompiledHopKey source, CompiledHopKey reader) {
			return dependencies.stream().anyMatch(proof -> proof.kind()
				== PlacementIdentity.PlacementProofKind.NATIVE_CONTINUITY
				&& (proof.owner() == source || proof.owner() == reader));
		}
		public String normalizedSignature() {
			return (sourceAnchor == null ? "-" : sourceAnchor.normalizedSignature()) + "|reader="
				+ (readerAnchor == null ? "-" : readerAnchor.normalizedSignature()) + "|nativePool="
				+ (nativeWorkerPoolWitness == null ? "-" : nativeWorkerPoolWitness.normalizedSignature())
				+ (nativeWorkerPoolWitness != null && !nativeWorkerPoolLayoutExact
					? "|nativePoolLayout=dynamic" : "") + "|proofs="
				+ dependencies.stream().map(PlacementProofKey::normalizedSignature).toList();
		}
		@Override public int compareTo(TransientCompatibilityProof that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	public static PlacementProofKey transientValueIdentityProof(CompiledHopKey sourceWrite,
		ValueVersionKey sourceVersion, ValueVersionKey readVersion) {
		Objects.requireNonNull(sourceVersion, "sourceVersion");
		Objects.requireNonNull(readVersion, "readVersion");
		return new PlacementProofKey(PlacementIdentity.PlacementProofKind.VALUE_IDENTITY,
			Objects.requireNonNull(sourceWrite, "sourceWrite"),
			sourceVersion.normalizedSignature() + "->" + readVersion.normalizedSignature());
	}

	/** One executable source-realization to reader-realization compatibility edge. */
	public record TransientPlacementCompatibility(CandidateRealizationReference sourceRealization,
		CandidateRealizationReference readerRealization, CandidateInputState sourceInput,
		CandidateInputState readerInput, TransientCompatibilityProof proof)
		implements Comparable<TransientPlacementCompatibility> {
		public TransientPlacementCompatibility {
			Objects.requireNonNull(sourceRealization, "sourceRealization");
			Objects.requireNonNull(readerRealization, "readerRealization");
			Objects.requireNonNull(sourceInput, "sourceInput");
			Objects.requireNonNull(readerInput, "readerInput");
			Objects.requireNonNull(proof, "proof");
			PlacementRealizationKey source = sourceRealization.realization();
			PlacementRealizationKey reader = readerRealization.realization();
			if(source.layoutKind() == PlacementLayoutKind.LOCAL
				|| reader.layoutKind() == PlacementLayoutKind.LOCAL) {
				if(source.layoutKind() != PlacementLayoutKind.LOCAL
					|| reader.layoutKind() != PlacementLayoutKind.LOCAL
					|| sourceInput.present() || readerInput.present()
					|| proof.sourceAnchor() != null)
					throw new IllegalArgumentException("LOCAL transient compatibility semantics differ");
			}
			else {
				FType sourceType = source.emissionState().placementState().fType();
				FType readerType = reader.emissionState().placementState().fType();
				if(sourceType == null || sourceType != readerType
					|| !sourceInput.equals(CandidateInputState.present(sourceType))
					|| !readerInput.equals(CandidateInputState.present(readerType)))
					throw new IllegalArgumentException("Federated transient compatibility projection differs");
			}
		}
		public String normalizedSignature() {
			return sourceRealization.normalizedSignature() + "|reader=" + readerRealization.normalizedSignature()
				+ "|sourceInput=" + sourceInput.normalizedSignature() + "|readerInput="
				+ readerInput.normalizedSignature() + "|proof=" + proof.normalizedSignature();
		}
		@Override public int compareTo(TransientPlacementCompatibility that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	/** One analysis-owned logical compatibility relation across an exact CFG transient forward. */
	public record LogicalTransientInputFact(CompiledHopKey sourceWrite, CompiledHopKey targetRead,
		int logicalPosition, ValueVersionKey sourceValueVersion, ValueVersionKey readValueVersion,
		List<TransientPlacementCompatibility> compatibility)
		implements LogicalCandidateInputFact, Comparable<LogicalTransientInputFact> {
		public LogicalTransientInputFact {
			Objects.requireNonNull(sourceWrite, "sourceWrite");
			Objects.requireNonNull(targetRead, "targetRead");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(readValueVersion, "readValueVersion");
			if(logicalPosition != 0)
				throw new IllegalArgumentException("Transient logical input position must be zero");
			compatibility = canonicalComparableList(compatibility, "transient placement compatibility");
			if(compatibility.isEmpty())
				throw new IllegalArgumentException("Logical transient input requires compatible realizations");
		}

		public List<CandidateInputState> supportedReaderInputs() {
			return compatibility.stream().map(TransientPlacementCompatibility::readerInput).distinct().sorted(
				java.util.Comparator.comparing(CandidateInputState::normalizedSignature)).toList();
		}

		public List<TransientPlacementCompatibility> compatibilityForReader(
			CandidateRealizationReference reader) {
			return compatibility.stream().filter(edge -> edge.readerRealization().equals(reader)).toList();
		}

		/** Legacy singleton views; generalized callers must consume {@link #compatibility()}. */
		public PlacementState federatedSourceState() {
			return requireUniqueLegacy(compatibility.stream().map(TransientPlacementCompatibility::sourceRealization)
				.map(CandidateRealizationReference::realization).map(PlacementRealizationKey::emissionState)
				.map(PlacementEmissionState::placementState).filter(state -> state.output() == FederatedOutput.FOUT)
				.distinct().toList(), "federated source state");
		}
		public PlacementState localSourceState() {
			List<PlacementState> values = compatibility.stream().map(TransientPlacementCompatibility::sourceRealization)
				.map(CandidateRealizationReference::realization).map(PlacementRealizationKey::emissionState)
				.map(PlacementEmissionState::placementState).filter(state -> state.output() == FederatedOutput.LOUT)
				.distinct().toList();
			return values.isEmpty() ? null : requireUniqueLegacy(values, "local source state");
		}
		public DurableAnchorKey anchor() {
			List<DurableAnchorKey> values = compatibility.stream().flatMap(edge -> java.util.stream.Stream.of(
				edge.proof().readerAnchor(), edge.readerRealization().realization().durableAnchor()))
				.filter(Objects::nonNull).distinct().toList();
			return values.isEmpty() ? null : requireUniqueLegacy(values, "transient anchor");
		}
		public FType federatedFType() { return federatedSourceState().fType(); }
		public CandidateInputState localInput() {
			List<CandidateInputState> values = compatibility.stream().map(TransientPlacementCompatibility::readerInput)
				.filter(input -> !input.present()).distinct().toList();
			return values.isEmpty() ? null : requireUniqueLegacy(values, "local input");
		}
		public CandidateInputState federatedInput() {
			return requireUniqueLegacy(compatibility.stream().map(TransientPlacementCompatibility::readerInput)
				.filter(CandidateInputState::present).distinct().toList(), "federated input");
		}
		private static <T> T requireUniqueLegacy(List<T> values, String label) {
			if(values.size() != 1)
				throw new IllegalStateException("Legacy " + label + " view is missing or ambiguous");
			return values.get(0);
		}

		@Override
		public int compareTo(LogicalTransientInputFact that) {
			int readOrder = targetRead.compareTo(that.targetRead);
			if(readOrder != 0) return readOrder;
			int positionOrder = Integer.compare(logicalPosition, that.logicalPosition);
			return positionOrder != 0 ? positionOrder : sourceWrite.compareTo(that.sourceWrite);
		}

		@Override public CompiledHopKey sourceOccurrence() { return sourceWrite; }
	}

	/**
	 * One exact caller argument -> synthetic boundary -> compiled formal-read path.
	 * This is a logical input edge, not a fabricated physical Hop input.
	 */
	public record LogicalFunctionInputFact(CompiledHopKey sourceArgument, CompiledHopKey boundary,
		CompiledHopKey targetRead, int callInputPosition, int logicalPosition,
		ValueVersionKey sourceValueVersion, ValueVersionKey boundaryValueVersion,
		ValueVersionKey readValueVersion)
		implements LogicalCandidateInputFact, Comparable<LogicalFunctionInputFact> {
		public LogicalFunctionInputFact {
			Objects.requireNonNull(sourceArgument, "sourceArgument");
			Objects.requireNonNull(boundary, "boundary");
			Objects.requireNonNull(targetRead, "targetRead");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(boundaryValueVersion, "boundaryValueVersion");
			Objects.requireNonNull(readValueVersion, "readValueVersion");
			if(callInputPosition < 0 || logicalPosition != 0)
				throw new IllegalArgumentException("Function logical input positions differ");
		}

		@Override
		public int compareTo(LogicalFunctionInputFact that) {
			int readOrder = targetRead.compareTo(that.targetRead);
			if(readOrder != 0) return readOrder;
			int callOrder = Integer.compare(callInputPosition, that.callInputPosition);
			if(callOrder != 0) return callOrder;
			int sourceOrder = sourceArgument.compareTo(that.sourceArgument);
			return sourceOrder != 0 ? sourceOrder : boundary.compareTo(that.boundary);
		}

		@Override public CompiledHopKey sourceOccurrence() { return sourceArgument; }
	}

	/** Exact compiler-owned argument -> trace-only boundary relation for one AST-inlined call. */
	public record LogicalInlinedFunctionInputFact(Optional<CompiledHopKey> sourceArgument, CompiledHopKey boundary,
		int callInputPosition, Optional<ValueVersionKey> sourceValueVersion, ValueVersionKey boundaryValueVersion)
		implements Comparable<LogicalInlinedFunctionInputFact> {
		public LogicalInlinedFunctionInputFact {
			Objects.requireNonNull(sourceArgument, "sourceArgument");
			Objects.requireNonNull(boundary, "boundary");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(boundaryValueVersion, "boundaryValueVersion");
			if(callInputPosition < 0)
				throw new IllegalArgumentException("Inlined function input position must be non-negative");
			if(sourceArgument.isPresent() != sourceValueVersion.isPresent())
				throw new IllegalArgumentException("Inlined function input source key/value presence differs");
		}

		@Override public int compareTo(LogicalInlinedFunctionInputFact that) {
			int boundaryOrder = boundary.compareTo(that.boundary);
			if(boundaryOrder != 0) return boundaryOrder;
			int positionOrder = Integer.compare(callInputPosition, that.callInputPosition);
			if(positionOrder != 0) return positionOrder;
			if(sourceArgument.isEmpty()) return that.sourceArgument.isEmpty() ? 0 : -1;
			return that.sourceArgument.isEmpty() ? 1
				: sourceArgument.orElseThrow().compareTo(that.sourceArgument.orElseThrow());
		}
	}

	/** Stable association between a neutral graph key and its concrete compiled Hop origin. */
	public record HopOccurrenceProjection(CompiledHopKey key, Hop hop, long scopeId, int normalizedOrdinal,
		String normalizedSignature) {
		public HopOccurrenceProjection {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(hop, "hop");
			if(normalizedOrdinal < 0)
				throw new IllegalArgumentException("normalizedOrdinal must be non-negative");
			if(normalizedSignature == null || normalizedSignature.isBlank())
				throw new IllegalArgumentException("normalizedSignature must not be blank");
		}
	}

	private final NeutralPlacementGraph graph;
	private final List<HopOccurrenceProjection> occurrences;
	private final List<HopOccurrenceProjection> compiledHopOccurrences;
	private final Map<HopOccurrenceProjection,Boolean> occurrenceOwnershipByIdentity;
	private final Map<CompiledHopKey,Boolean> occurrenceKeysByIdentity;
	private final Map<CompiledHopKey,Boolean> compiledOccurrenceKeysByIdentity;
	private final Map<CompiledHopKey,String> occurrenceSignaturesByIdentity;
	private final List<StatementBlock> topLevelStatementBlocks;
	private final Map<CompiledHopKey, Hop> hopsByKey;
	private final PlacementShapeFacts shapeFacts;
	private final Map<CompiledHopKey,PhysicalCostEstimateFact> physicalCostEstimateFactsByIdentity;
	private final Map<CompiledHopKey,Map<CompiledHopKey,Double>> physicalFunctionInputEstimatesByIdentity;
	private final PlacementPrivacyFacts privacyFacts;
	private final Optional<CandidatePrivacyClosureEvidence> candidatePrivacyClosureEvidence;
	private final OccurrenceExecutionFrequencyFacts executionFrequencyFacts;
	private final PlacementCostSizeBounds costSizeBounds;
	private final String analysisFingerprint;
	private final HeuristicPolicyFacts heuristicPolicyFacts;
	private final CandidateRuleDomain candidateRuleDomain;
	private final CandidateRuleFacts candidateRuleFacts;
	private final CandidateReceiptDomain candidateReceiptDomain;
	private final RelocationSelections.CanonicalOrderIndex relocationOrder;
	private volatile RelocationSelections.RelocationPrivacyIndex relocationPrivacy;
	private final Map<NeutralPlacementGraph.DerivedFoutMaterializationAction,
		DerivedFoutAnchorCompatibility.Prepared> derivedFoutAnchorCompatibility = new IdentityHashMap<>();
	private final Map<NeutralPlacementGraph.RelocationAction,Boolean> relocationActionsByIdentity;
	private final CandidateConsumerProfileFacts candidateConsumerProfileFacts;
	private final DetachedConsumerProfileFacts detachedConsumerProfileFacts;
	private final List<CompiledInputEdgeFact> compiledInputEdgesInCanonicalOrder;
	private final Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>>> inputEdgesByIdentity;
	private final Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> inputEdgesByConsumerIdentity;
	private final Map<CompiledInputEdgeFact,CoordinatorInputAccess> coordinatorInputAccessByIdentity;
	private final Map<CompiledHopKey,List<CompiledHopKey>> cfgDefinitionSourcesByIdentity;
	private final LogicalBoundaryRealizations logicalBoundaryRealizations;
	private final List<LogicalTransientInputFact> logicalTransientInputsInCanonicalOrder;
	private final Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,LogicalTransientInputFact>>> logicalInputsByIdentity;
	private final Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> logicalInputsByReaderSlot;
	private final List<LogicalFunctionInputFact> logicalFunctionInputsInCanonicalOrder;
	private final Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,List<LogicalFunctionInputFact>>>>
		logicalFunctionInputsByIdentity;
	private final List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputsInCanonicalOrder;
	private final DMLProgram programOwner;
	private final Optional<PlacementJointInputAnalysis> jointInputAnalysis;
	private final Map<String,FunctionStatementBlock> namedFunctionStatementBlocks;
	private final Runnable programMutationGuard;
	private final boolean guardedFunctionRoots;

	/**
	 * Opaque construction-boundary authority. PlacementAnalysis can validate or advance a
	 * transaction-owned snapshot, but it cannot traverse the program or derive a second structural
	 * universe. The canonical construction boundary owns the concrete fingerprint implementation.
	 */
	interface ProgramStructureAuthority extends Runnable {
		void authorizeCommittedEmission();
	}

	private static PhysicalCostEstimateFact capturePhysicalCostEstimate(Hop hop) {
		FunctionOp multiReturn = exactMultiReturnBuiltinParent(hop);
		double multiReturnEstimate = Double.NaN;
		long multiReturnRows = -1L;
		long multiReturnCols = -1L;
		if(multiReturn != null) {
			multiReturnEstimate = multiReturn.getMultiReturnBuiltinOutputMemEstimate(hop);
			long[] dimensions = multiReturn.getMultiReturnBuiltinOutputDims(hop);
			if(dimensions != null && dimensions.length >= 2) {
				multiReturnRows = dimensions[0];
				multiReturnCols = dimensions[1];
			}
		}
		return new PhysicalCostEstimateFact(hop.getDataType(), hop.dimsKnown(),
			hop.getDim1(), hop.getDim2(), hop.getNnz(), hop.getOutputMemEstimate(),
			FederatedCostModel.getEffectiveOutputMemEstimate(hop),
			FederatedCostModel.getEffectiveUploadMemEstimate(hop), multiReturnEstimate,
			multiReturnRows, multiReturnCols);
	}

	private static FunctionOp exactMultiReturnBuiltinParent(Hop hop) {
		if(!(hop instanceof DataOp data) || data.getOp() != OpOpData.FUNCTIONOUTPUT
			|| hop.getInput() == null || hop.getInput().isEmpty() || hop.getInput().get(0) == null)
			return null;
		FunctionOp resolved = null;
		for(Hop parent : hop.getInput().get(0).getParent()) {
			if(!(parent instanceof FunctionOp function)
				|| function.getFunctionType() != FunctionType.MULTIRETURN_BUILTIN
				|| function.getOutputs() == null
				|| function.getOutputs().stream().noneMatch(output -> output == hop))
				continue;
			if(resolved != null && resolved != function)
				return null;
			resolved = function;
		}
		return resolved;
	}

	private Map<CompiledHopKey,Map<CompiledHopKey,Double>> capturePhysicalFunctionInputEstimates() {
		IdentityHashMap<CompiledHopKey,Map<CompiledHopKey,Double>> estimates = new IdentityHashMap<>();
		for(LogicalFunctionInputFact fact : logicalFunctionInputsInCanonicalOrder)
			capturePhysicalFunctionInputEstimate(estimates, fact.sourceArgument(), fact.targetRead());
		for(LogicalTransientInputFact transientFact : logicalTransientInputsInCanonicalOrder) {
			for(Constraint binding : graph.constraints()) {
				if(binding.kind() != ConstraintKind.SAME_PLACEMENT
					|| !"function-input-binding".equals(binding.evidence())
					|| binding.right() != transientFact.sourceWrite())
					continue;
				for(LogicalFunctionInputFact authority : logicalFunctionInputsInCanonicalOrder)
					if(authority.targetRead() == binding.left())
						capturePhysicalFunctionInputEstimate(estimates, authority.sourceArgument(),
							transientFact.targetRead());
			}
		}
		IdentityHashMap<CompiledHopKey,Map<CompiledHopKey,Double>> frozen = new IdentityHashMap<>();
		estimates.forEach((source, byTarget) -> frozen.put(source, Collections.unmodifiableMap(byTarget)));
		return Collections.unmodifiableMap(frozen);
	}

	private void capturePhysicalFunctionInputEstimate(
		IdentityHashMap<CompiledHopKey,Map<CompiledHopKey,Double>> estimates,
		CompiledHopKey source, CompiledHopKey targetRead) {
		double estimate = FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(
			hopsByKey.get(targetRead), hopsByKey.get(source));
		Map<CompiledHopKey,Double> byTarget = estimates.computeIfAbsent(source,
			ignored -> new IdentityHashMap<>());
		Double previous = byTarget.putIfAbsent(targetRead, estimate);
		if(previous != null && Double.doubleToLongBits(previous) != Double.doubleToLongBits(estimate))
			throw new IllegalArgumentException("Conflicting physical function-input estimates");
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, List.of(), null);
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges, Runnable programMutationGuard) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, List.of(), programMutationGuard);
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges,
		List<LogicalTransientInputFact> logicalTransientInputs, Runnable programMutationGuard) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, logicalTransientInputs, defaultPrivacyFacts(graph), programMutationGuard);
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges,
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		Runnable programMutationGuard) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, logicalTransientInputs, privacyFacts,
			null, programMutationGuard);
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges,
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence,
		Runnable programMutationGuard) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, logicalTransientInputs, privacyFacts, candidatePrivacyClosureEvidence,
			null, programMutationGuard);
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges,
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence,
		List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputs,
		Runnable programMutationGuard) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts, candidateConsumerDomainKeys,
			candidateConsumerProfileFacts, detachedConsumerProfileFacts, compiledInputEdges, logicalTransientInputs,
			privacyFacts, candidatePrivacyClosureEvidence, logicalInlinedFunctionInputs, programMutationGuard, List.of());
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges,
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence,
		List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputs,
		Runnable programMutationGuard, List<CandidatePrivacyInputPruning> privacyPrunedInputs) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts, candidateConsumerDomainKeys,
			candidateConsumerProfileFacts, detachedConsumerProfileFacts, compiledInputEdges, logicalTransientInputs,
			privacyFacts, candidatePrivacyClosureEvidence, logicalInlinedFunctionInputs, programMutationGuard,
			privacyPrunedInputs, null);
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges,
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence,
		List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputs,
		Runnable programMutationGuard, List<CandidatePrivacyInputPruning> privacyPrunedInputs,
		PlacementJointInputAnalysis jointInputAnalysis) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts, candidateConsumerDomainKeys,
			candidateConsumerProfileFacts, detachedConsumerProfileFacts, compiledInputEdges, logicalTransientInputs,
			privacyFacts, candidatePrivacyClosureEvidence, logicalInlinedFunctionInputs, programMutationGuard,
			privacyPrunedInputs, jointInputAnalysis, false, List.of(), List.of());
	}

	/** Publish the canonical fingerprint directly, without a discarded preliminary digest. */
	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges,
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence,
		List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputs,
		Runnable programMutationGuard, List<CandidatePrivacyInputPruning> privacyPrunedInputs,
		PlacementJointInputAnalysis jointInputAnalysis) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, null,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts, candidateConsumerDomainKeys,
			candidateConsumerProfileFacts, detachedConsumerProfileFacts, compiledInputEdges, logicalTransientInputs,
			privacyFacts, candidatePrivacyClosureEvidence, logicalInlinedFunctionInputs, programMutationGuard,
			privacyPrunedInputs, jointInputAnalysis, true, List.of(), List.of());
	}

	/** Canonical construction with non-enumerated scalar CP rule families. */
	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges,
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence,
		List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputs,
		Runnable programMutationGuard, List<CandidatePrivacyInputPruning> privacyPrunedInputs,
		PlacementJointInputAnalysis jointInputAnalysis, List<CpRuleFamily> cpRuleFamilies) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, logicalTransientInputs, privacyFacts, candidatePrivacyClosureEvidence,
			logicalInlinedFunctionInputs, programMutationGuard, privacyPrunedInputs,
			jointInputAnalysis, cpRuleFamilies, List.of());
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges,
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence,
		List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputs,
		Runnable programMutationGuard, List<CandidatePrivacyInputPruning> privacyPrunedInputs,
		PlacementJointInputAnalysis jointInputAnalysis, List<CpRuleFamily> cpRuleFamilies,
		List<CandidateRuleRelation> candidateRelations) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, null,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts, candidateConsumerDomainKeys,
			candidateConsumerProfileFacts, detachedConsumerProfileFacts, compiledInputEdges, logicalTransientInputs,
			privacyFacts, candidatePrivacyClosureEvidence, logicalInlinedFunctionInputs, programMutationGuard,
			privacyPrunedInputs, jointInputAnalysis, true, cpRuleFamilies, candidateRelations);
	}

	private PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges,
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence,
		List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputs,
		Runnable programMutationGuard, List<CandidatePrivacyInputPruning> privacyPrunedInputs,
		PlacementJointInputAnalysis jointInputAnalysis, boolean canonicalFingerprint,
		List<CpRuleFamily> cpRuleFamilies, List<CandidateRuleRelation> candidateRelations) {
		this.graph = Objects.requireNonNull(graph, "graph");
		this.privacyFacts = Objects.requireNonNull(privacyFacts, "privacyFacts");
		this.candidatePrivacyClosureEvidence = Optional.ofNullable(candidatePrivacyClosureEvidence);
		this.programOwner = programOwner;
		this.jointInputAnalysis = Optional.ofNullable(jointInputAnalysis);
		this.programMutationGuard = programMutationGuard == null ? () -> { } : programMutationGuard;
		this.guardedFunctionRoots = programOwner != null && programMutationGuard != null;
		Map<String,FunctionStatementBlock> functions = new java.util.TreeMap<>();
		if(guardedFunctionRoots)
			functions.putAll(programOwner.getNamedNSFunctionStatementBlocks());
		this.namedFunctionStatementBlocks = Collections.unmodifiableMap(functions);
		this.occurrences = List.copyOf(occurrences);
		this.topLevelStatementBlocks = List.copyOf(topLevelStatementBlocks);
		Map<CompiledHopKey, Hop> indexed = new LinkedHashMap<>();
		Map<HopOccurrenceProjection,Boolean> ownedOccurrences = new IdentityHashMap<>();
		Map<CompiledHopKey,Boolean> ownedKeys = new IdentityHashMap<>();
		Map<CompiledHopKey,Boolean> compiledKeys = new IdentityHashMap<>();
		Map<CompiledHopKey,String> occurrenceSignatures = new IdentityHashMap<>();
		List<HopOccurrenceProjection> compiledOccurrences = new ArrayList<>();
		for(HopOccurrenceProjection occurrence : this.occurrences) {
			if(indexed.put(occurrence.key(), occurrence.hop()) != null)
				throw new IllegalArgumentException("Duplicate compiled Hop projection key: " + occurrence.key());
			ownedOccurrences.put(occurrence, Boolean.TRUE);
			ownedKeys.put(occurrence.key(), Boolean.TRUE);
			occurrenceSignatures.put(occurrence.key(), occurrence.normalizedSignature());
			NodeKind kind = graph.node(occurrence.key()).orElseThrow(() ->
				new IllegalArgumentException("Occurrence has a foreign graph key")).kind();
			if(isCompiledHopOccurrenceKey(occurrence.key(), kind)) {
				compiledKeys.put(occurrence.key(), Boolean.TRUE);
				compiledOccurrences.add(occurrence);
			}
		}
		if(indexed.size() != graph.nodes().size())
			throw new IllegalArgumentException("Projection does not cover the neutral placement graph");
		this.compiledHopOccurrences = List.copyOf(compiledOccurrences);
		this.occurrenceOwnershipByIdentity = Collections.unmodifiableMap(ownedOccurrences);
		this.occurrenceKeysByIdentity = Collections.unmodifiableMap(ownedKeys);
		this.compiledOccurrenceKeysByIdentity = Collections.unmodifiableMap(compiledKeys);
		this.occurrenceSignaturesByIdentity = Collections.unmodifiableMap(occurrenceSignatures);
		this.shapeFacts = Objects.requireNonNull(shapeFacts, "shapeFacts");
		if(!shapeFacts.keys().equals(indexed.keySet()))
			throw new IllegalArgumentException("Shape facts do not exactly cover indexed placement projections");
		IdentityHashMap<CompiledHopKey,PhysicalCostEstimateFact> costEstimates = new IdentityHashMap<>();
		IdentityHashMap<Hop,PhysicalCostEstimateFact> costEstimatesByHop = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : this.occurrences)
			costEstimates.put(occurrence.key(), costEstimatesByHop.computeIfAbsent(
				occurrence.hop(), PlacementAnalysis::capturePhysicalCostEstimate));
		this.physicalCostEstimateFactsByIdentity = Collections.unmodifiableMap(costEstimates);
		hopsByKey = Map.copyOf(indexed);
		if(!canonicalFingerprint && (analysisFingerprint == null || analysisFingerprint.isBlank()))
			throw new IllegalArgumentException("analysisFingerprint must not be blank");
		this.analysisFingerprint = canonicalFingerprint ? canonicalAnalysisFingerprint()
			: canonicalizeSuppliedAnalysisFingerprint(analysisFingerprint);
		this.heuristicPolicyFacts = Objects.requireNonNull(heuristicPolicyFacts, "heuristicPolicyFacts");
		for(CpRuleFamily family : Objects.requireNonNull(cpRuleFamilies, "CP rule families")) {
			if(!ownedKeys.containsKey(Objects.requireNonNull(family, "CP rule family").parent()))
				throw new IllegalArgumentException("CP rule family parent is not analysis-owned");
			PlacementState state = family.emission().emissionState().placementState();
			NeutralPlacementGraph.Node owner = graph.node(family.parent()).orElseThrow();
			if(owner.legalAlternatives().stream().noneMatch(legal -> legal == state)
				|| state.execType() != ExecType.CP || state.output() != FederatedOutput.LOUT
				|| state.fType() != null || family.capability().nativeExec() != ExecType.CP
				|| family.capability().nativeOutput() != FederatedOutput.LOUT
				|| family.capability().nativeFoutFType() != null
				|| !family.profile().available() || !family.profile().producerOutputs().isEmpty())
				throw new IllegalArgumentException("CP rule family header is not graph-owned scalar CP/LOUT");
		}
		for(CandidateRuleRelation relation : Objects.requireNonNull(
			candidateRelations, "candidate rule relations")) {
			if(!ownedKeys.containsKey(Objects.requireNonNull(relation,
				"candidate rule relation").parent()))
				throw new IllegalArgumentException("Candidate relation parent is not analysis-owned");
			NeutralPlacementGraph.Node owner = graph.node(relation.parent()).orElseThrow();
			for(CandidateRuleRelation.ConditionalRegion region : relation.regions()) {
				if(!region.header().profile().available())
					throw new IllegalArgumentException("Candidate relation profile is unavailable");
				for(CandidateEmissionFact emission : region.header().emissions())
					if(owner.legalAlternatives().stream().noneMatch(legal ->
						legal == emission.emissionState().placementState()))
						throw new IllegalArgumentException(
							"Candidate relation emission state is not graph-owned");
			}
		}
		this.candidateRuleDomain = new CandidateRuleDomain(this.analysisFingerprint, candidateRuleDomainKeys,
			candidateConsumerDomainKeys, privacyPrunedInputs, cpRuleFamilies, candidateRelations);
		Map<CompiledHopKey,Boolean> analysisKeysByIdentity = this.occurrenceKeysByIdentity;
		for(CandidateRuleKey key : this.candidateRuleDomain.orderedRuleKeys())
			if(!analysisKeysByIdentity.containsKey(key.parentOccurrence()))
				throw new IllegalArgumentException("Candidate domain parent is not analysis-owned");
		this.candidateRuleFacts = new CandidateRuleFacts(
			this.candidateRuleDomain, candidateRuleFacts, cpRuleFamilies, candidateRelations);
		this.candidateReceiptDomain = new CandidateReceiptDomain(this.candidateRuleFacts);
		for(var action : graph.derivedFoutMaterializationActions())
			for(var authority : action.nativeAnchorAuthorities()) {
				var reference = authority.reference();
				var fact = this.candidateRuleFacts.requireExact(reference.rule().parentOccurrence(),
					reference.rule().orderedInputs());
				if(fact.key() != reference.rule()
					|| requireReferencedRealization(reference) != authority.realization()
					|| !authority.realization().ownsSupportClauseIdentity(authority.clause()))
					throw new IllegalArgumentException("Derived FOUT native anchor certificate is not analysis-owned");
			}
		Map<NeutralPlacementGraph.RelocationAction,Boolean> ownedRelocationActions = new IdentityHashMap<>();
		for(NeutralPlacementGraph.RelocationAction action : graph.relocationActions())
			ownedRelocationActions.put(action, Boolean.TRUE);
		this.relocationActionsByIdentity = Collections.unmodifiableMap(ownedRelocationActions);
		this.relocationOrder = RelocationSelections.canonicalOrderIndex(graph.relocationActions());
		this.candidateConsumerProfileFacts = new CandidateConsumerProfileFacts(this.candidateRuleDomain,
			candidateConsumerProfileFacts);
		this.detachedConsumerProfileFacts = new DetachedConsumerProfileFacts(detachedConsumerProfileFacts,
			analysisKeysByIdentity);
		this.compiledInputEdgesInCanonicalOrder = validateCompiledInputEdges(compiledInputEdges);
		this.inputEdgesByIdentity = indexCompiledInputEdges(this.compiledInputEdgesInCanonicalOrder);
		this.inputEdgesByConsumerIdentity = indexCompiledInputEdgesByConsumer(
			this.compiledInputEdgesInCanonicalOrder);
		this.logicalBoundaryRealizations = new LogicalBoundaryRealizations(graph.nodes(), graph.constraints(),
			hopsByKey, this.candidateRuleFacts.orderedFacts());
		this.logicalBoundaryRealizations.validate(this.candidateRuleFacts.orderedFacts());
		validateCandidateRealizationSupport();
		this.coordinatorInputAccessByIdentity = deriveCoordinatorInputAccess(
			this.compiledInputEdgesInCanonicalOrder);
		this.cfgDefinitionSourcesByIdentity = indexCfgDefinitionSources(graph, hopsByKey);
		this.logicalTransientInputsInCanonicalOrder = validateLogicalTransientInputs(logicalTransientInputs,
			analysisKeysByIdentity);
		this.logicalInputsByIdentity = indexLogicalTransientInputs(this.logicalTransientInputsInCanonicalOrder);
		this.logicalInputsByReaderSlot = indexLogicalTransientInputsByReader(
			this.logicalTransientInputsInCanonicalOrder);
		this.logicalFunctionInputsInCanonicalOrder = validateLogicalFunctionInputs(
			deriveLogicalFunctionInputs(), analysisKeysByIdentity);
		this.logicalFunctionInputsByIdentity = indexLogicalFunctionInputs(
			this.logicalFunctionInputsInCanonicalOrder);
		this.logicalInlinedFunctionInputsInCanonicalOrder = logicalInlinedFunctionInputs == null
			? deriveLogicalInlinedFunctionInputs()
			: validateLogicalInlinedFunctionInputs(logicalInlinedFunctionInputs, analysisKeysByIdentity);
		this.physicalFunctionInputEstimatesByIdentity = capturePhysicalFunctionInputEstimates();
		for(HeuristicPolicyFact fact : heuristicPolicyFacts.demotions()) {
			NeutralPlacementGraph.Node producer = graph.node(fact.producer()).orElseThrow(() ->
				new IllegalArgumentException("Heuristic policy producer is missing from the analysis graph"));
			if(!producer.valueVersion().equals(fact.valueVersion()))
				throw new IllegalArgumentException("Heuristic policy producer/value pair does not match the analysis graph");
		}
		validateHeuristicPaths(analysisKeysByIdentity);
		for(CandidatePrivacyInputPruning evidence : privacyPrunedInputs)
			evidence.validate(this);
		this.executionFrequencyFacts = OccurrenceExecutionFrequencyFacts.from(this);
		this.costSizeBounds = PlacementCostSizeBounds.from(this);
	}

	private void validateHeuristicPaths(Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		for(HeuristicPathFact path : heuristicPolicyFacts.paths()) {
			for(CompiledHopKey key : path.localPrefix())
				if(!analysisKeysByIdentity.containsKey(key))
					throw new IllegalArgumentException("Heuristic path contains a foreign local-prefix occurrence");
			for(HeuristicPathEdgeFact edge : path.edges()) {
				if(!analysisKeysByIdentity.containsKey(edge.producer())
					|| !analysisKeysByIdentity.containsKey(edge.consumer()))
					throw new IllegalArgumentException("Heuristic path edge contains a foreign occurrence");
				NeutralPlacementGraph.Node producer = graph.node(edge.producer()).orElseThrow();
				NeutralPlacementGraph.Node consumer = graph.node(edge.consumer()).orElseThrow();
				if(producer.valueVersion() != edge.sourceValueVersion()
					|| consumer.valueVersion() != edge.consumerValueVersion())
					throw new IllegalArgumentException("Heuristic path edge value identity differs");
				if(edge.kind() == HeuristicPathEdgeKind.COMPILED_INPUT)
					requireExactCompiledInputEdge(edge.producer(), edge.consumer(), edge.inputPosition());
				else if(!isCompiledAcyclicTransientForwardAccess(
					hopsByKey.get(edge.producer()), producer, OpOpData.TRANSIENTWRITE)
					|| !isCompiledAcyclicTransientForwardAccess(
						hopsByKey.get(edge.consumer()), consumer, OpOpData.TRANSIENTREAD)
					|| edge.inputPosition() != 0)
					throw new IllegalArgumentException("Heuristic CFG edge is not an exact transient forward: "
						+ edge + ", producerKind=" + producer.kind() + ", consumerKind=" + consumer.kind());
			}
			for(HeuristicPathwiseReentryFact fact : path.reentries())
				validateHeuristicReentry(path, fact, analysisKeysByIdentity);
			for(HeuristicNativeContinuationFact fact : path.nativeContinuations())
				validateHeuristicNativeContinuation(path, fact, analysisKeysByIdentity);
		}
	}

	private void validateHeuristicReentry(HeuristicPathFact path, HeuristicPathwiseReentryFact fact,
		Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		if(!analysisKeysByIdentity.containsKey(fact.localProducer())
			|| !analysisKeysByIdentity.containsKey(fact.consumer())
			|| !analysisKeysByIdentity.containsKey(fact.siblingProducer()))
			throw new IllegalArgumentException("Heuristic re-entry contains a foreign occurrence");
		if(!path.localPrefix().contains(fact.localProducer()))
			throw new IllegalArgumentException("Heuristic re-entry source is outside its exact local prefix");
		NeutralPlacementGraph.Node local = graph.node(fact.localProducer()).orElseThrow();
		NeutralPlacementGraph.Node sibling = graph.node(fact.siblingProducer()).orElseThrow();
		NeutralPlacementGraph.Node consumer = graph.node(fact.consumer()).orElseThrow();
		if(local.valueVersion() != fact.sourceValueVersion()
			|| sibling.valueVersion() != fact.siblingValueVersion())
			throw new IllegalArgumentException("Heuristic re-entry value identity differs");
		requireExactCompiledInputEdge(fact.localProducer(), fact.consumer(), fact.inputPosition());
		requireExactCompiledInputEdge(fact.siblingProducer(), fact.consumer(), fact.siblingInputPosition());
		if(!sibling.anchors().contains(fact.durableAnchor())
			|| !sibling.legalAlternatives().contains(fact.siblingFoutState())
			|| fact.siblingFoutState().execType() != ExecType.FED
			|| fact.siblingFoutState().output() != FederatedOutput.FOUT
			|| fact.siblingFoutState().fType() != fact.durableAnchor().fType())
			throw new IllegalArgumentException("Heuristic re-entry sibling FOUT authority differs");
		if(!consumer.legalAlternatives().contains(fact.consumerFoutState())
			|| fact.consumerFoutState().execType() != ExecType.FED
			|| fact.consumerFoutState().output() != FederatedOutput.FOUT
			|| fact.consumerFoutState().fType() != fact.durableAnchor().fType())
			throw new IllegalArgumentException("Heuristic re-entry consumer FOUT state differs");
		CandidateRuleFact exactCandidate = candidateRuleFacts.requireExact(fact.consumer(),
			fact.runtimeCandidate().key().orderedInputs());
		if(exactCandidate != fact.runtimeCandidate() || exactCandidate.status() != CandidateEvaluationStatus.AVAILABLE
			|| exactCandidate.capability() == null
			|| exactCandidate.capability().nativeExec() != fact.consumerFoutState().execType()
			|| exactCandidate.capability().nativeOutput() != fact.consumerFoutState().output()
			|| exactCandidate.capability().nativeFoutFType() != fact.consumerFoutState().fType())
			throw new IllegalArgumentException("Heuristic re-entry runtime candidate differs");
		if(!fact.relocationAction().sourceValueVersion().equals(fact.sourceValueVersion())
			|| !fact.relocationAction().targetPlacement().equals(fact.consumerFoutState())
			|| fact.relocationAction().durableAnchor() != fact.durableAnchor()
			|| fact.obligation().consumer() != fact.consumer()
			|| fact.obligation().inputPosition() != fact.inputPosition()
			|| fact.obligation().relocationAction() != fact.relocationAction())
			throw new IllegalArgumentException("Heuristic re-entry relocation obligation differs");
		boolean exactAction = graph.relocationActions().stream().anyMatch(action -> action.key() == fact.relocationAction()
			&& action.obligations().stream().anyMatch(obligation -> obligation == fact.obligation()));
		if(!exactAction)
			throw new IllegalArgumentException("Heuristic re-entry relocation is not analysis-owned");
		if(List.of(local, sibling, consumer).stream().anyMatch(node -> !node.emittedWork()
			|| node.valueVersion().versionKind() == PlacementIdentity.VersionKind.CLONE_RECOMPILE
			|| node.kind() == NodeKind.CLONE || node.kind() == NodeKind.FUNCTION_CALL
			|| node.kind() == NodeKind.FUNCTION_INPUT || node.kind() == NodeKind.FUNCTION_OUTPUT
			|| node.kind() == NodeKind.FUNCTION_BODY_NON_EMITTED))
			throw new IllegalArgumentException(
				"Heuristic re-entry must bind exact emitted non-boundary occurrences");
	}

	private void validateHeuristicNativeContinuation(HeuristicPathFact path,
		HeuristicNativeContinuationFact fact,
		Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		if(!analysisKeysByIdentity.containsKey(fact.localProducer())
			|| !analysisKeysByIdentity.containsKey(fact.consumer())
			|| !analysisKeysByIdentity.containsKey(fact.siblingProducer()))
			throw new IllegalArgumentException("Heuristic native continuation contains a foreign occurrence");
		if(!path.localPrefix().contains(fact.localProducer()))
			throw new IllegalArgumentException(
				"Heuristic native-continuation source is outside its exact local prefix");
		NeutralPlacementGraph.Node local = graph.node(fact.localProducer()).orElseThrow();
		NeutralPlacementGraph.Node sibling = graph.node(fact.siblingProducer()).orElseThrow();
		NeutralPlacementGraph.Node consumer = graph.node(fact.consumer()).orElseThrow();
		if(local.valueVersion() != fact.localValueVersion()
			|| sibling.valueVersion() != fact.siblingValueVersion())
			throw new IllegalArgumentException("Heuristic native-continuation value identity differs");
		requireExactCompiledInputEdge(fact.localProducer(), fact.consumer(), fact.localInputPosition());
		requireExactCompiledInputEdge(fact.siblingProducer(), fact.consumer(), fact.siblingInputPosition());
		if(!sibling.legalAlternatives().contains(fact.siblingFoutState())
			|| fact.siblingFoutState().execType() != ExecType.FED
			|| fact.siblingFoutState().output() != FederatedOutput.FOUT
			|| fact.siblingFoutState().fType() == null)
			throw new IllegalArgumentException(
				"Heuristic native-continuation sibling FOUT authority differs");
		if(!consumer.legalAlternatives().contains(fact.consumerState())
			|| fact.consumerState().execType() != ExecType.FED
			|| (fact.consumerState().output() != FederatedOutput.FOUT
				&& fact.consumerState().output() != FederatedOutput.LOUT)
			|| fact.consumerState().fType() != fact.siblingFoutState().fType())
			throw new IllegalArgumentException("Heuristic native-continuation consumer state differs");
		CandidateRuleFact exactCandidate = candidateRuleFacts.requireExact(fact.consumer(),
			fact.runtimeCandidate().key().orderedInputs());
		List<CandidateInputState> inputs = exactCandidate.key().orderedInputs();
		if(exactCandidate != fact.runtimeCandidate()
			|| exactCandidate.status() != CandidateEvaluationStatus.AVAILABLE
			|| exactCandidate.capability() == null
			|| exactCandidate.capability().nativeExec() != fact.consumerState().execType()
			|| !(fact.consumerState().output() == FederatedOutput.FOUT
				&& exactCandidate.capability().nativeOutput() == FederatedOutput.FOUT
				&& exactCandidate.capability().nativeFoutFType() == fact.consumerState().fType()
				|| fact.consumerState().output() == FederatedOutput.LOUT)
			|| fact.localInputPosition() >= inputs.size()
			|| !inputs.get(fact.localInputPosition()).equals(CandidateInputState.absentLocal())
			|| fact.siblingInputPosition() >= inputs.size()
			|| !inputs.get(fact.siblingInputPosition()).equals(
				CandidateInputState.present(fact.siblingFoutState().fType()))
			|| inputs.stream().filter(CandidateInputState::present).count() != 1
			|| exactCandidate.allowedEmissionFacts().stream().noneMatch(emission ->
				emission.emissionState().placementState().equals(fact.consumerState())
					&& emission.executionFType() == fact.siblingFoutState().fType()))
			throw new IllegalArgumentException("Heuristic native-continuation runtime candidate differs");
		if(List.of(local, sibling, consumer).stream().anyMatch(node -> !node.emittedWork()
			|| node.valueVersion().versionKind() == PlacementIdentity.VersionKind.CLONE_RECOMPILE
			|| node.kind() == NodeKind.CLONE || node.kind() == NodeKind.FUNCTION_CALL
			|| node.kind() == NodeKind.FUNCTION_INPUT || node.kind() == NodeKind.FUNCTION_OUTPUT
			|| node.kind() == NodeKind.FUNCTION_BODY_NON_EMITTED))
			throw new IllegalArgumentException(
				"Heuristic native continuation must bind exact emitted non-boundary occurrences");
	}

	private List<LogicalTransientInputFact> validateLogicalTransientInputs(
		List<LogicalTransientInputFact> supplied, Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		Objects.requireNonNull(supplied, "logicalTransientInputs");
		List<LogicalTransientInputFact> sorted = supplied.stream()
			.map(fact -> Objects.requireNonNull(fact, "logical transient input fact")).sorted().toList();
		if(!sorted.equals(supplied))
			throw new IllegalArgumentException("Logical transient input facts are not in canonical order");
		Map<CompiledHopKey,Map<CompiledHopKey,Set<Integer>>> slots = new IdentityHashMap<>();
		Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> byReaderSlot = new IdentityHashMap<>();
		for(LogicalTransientInputFact fact : sorted) {
			if(!analysisKeysByIdentity.containsKey(fact.sourceWrite())
				|| !analysisKeysByIdentity.containsKey(fact.targetRead()))
				throw new IllegalArgumentException("Logical transient input has a foreign occurrence");
			NeutralPlacementGraph.Node source = graph.node(fact.sourceWrite()).orElseThrow();
			NeutralPlacementGraph.Node read = graph.node(fact.targetRead()).orElseThrow();
			if(!isCompiledTransientAccess(hopsByKey.get(source.key()), source, OpOpData.TRANSIENTWRITE)
				|| !isCompiledTransientAccess(hopsByKey.get(read.key()), read, OpOpData.TRANSIENTREAD))
				throw new IllegalArgumentException("Logical transient input endpoints have wrong node kinds");
			if(source.valueVersion() != fact.sourceValueVersion() || read.valueVersion() != fact.readValueVersion())
				throw new IllegalArgumentException("Logical transient input value identity differs");
			if(!hopsByKey.get(fact.targetRead()).getInput().isEmpty())
				throw new IllegalArgumentException("Logical transient read has physical inputs");
			if(compiledInputEdgesInCanonicalOrder.stream().anyMatch(edge -> edge.producer() == fact.sourceWrite()
				&& edge.consumer() == fact.targetRead() && edge.inputPosition() == fact.logicalPosition()))
				throw new IllegalArgumentException("Logical transient input fabricated a physical edge");

			Set<CandidateRealizationReference> supportedReaderRealizations = new java.util.HashSet<>();
			for(TransientPlacementCompatibility edge : fact.compatibility()) {
				if(edge.sourceRealization().rule().parentOccurrence() != fact.sourceWrite()
					|| edge.readerRealization().rule().parentOccurrence() != fact.targetRead())
					throw new IllegalArgumentException("Transient compatibility realization owner differs");
				CandidateEmissionRealization sourceRealization = requireReferencedRealization(edge.sourceRealization());
				CandidateEmissionRealization readerRealization = requireReferencedRealization(edge.readerRealization());
				validateTransientRealizationTuple(source, sourceRealization, "source");
				validateTransientRealizationTuple(read, readerRealization, "reader");
		List<CandidateInputState> readerInputs = edge.readerRealization().rule().orderedInputs();
				if(readerInputs.size() != 1 || !readerInputs.get(0).equals(edge.readerInput()))
					throw new IllegalArgumentException("Transient reader realization candidate input differs");
				validateTransientCompatibilityProof(fact, edge, sourceRealization, readerRealization,
					analysisKeysByIdentity);
				supportedReaderRealizations.add(edge.readerRealization());
			}
			Set<CandidateRealizationReference> actualReaderRealizations = candidateRuleFacts.orderedFacts().stream()
				.filter(candidate -> candidate.key().parentOccurrence() == fact.targetRead()
					&& candidate.status() == CandidateEvaluationStatus.AVAILABLE)
				.flatMap(candidate -> candidate.allowedEmissionFacts().stream()
					.flatMap(emission -> emission.realizations().stream()
						.map(realization -> CandidateRealizationReference.of(candidate.key(), realization))))
				.collect(java.util.stream.Collectors.toSet());
			if(!actualReaderRealizations.equals(supportedReaderRealizations)) {
				Set<CandidateRealizationReference> missing = new java.util.HashSet<>(actualReaderRealizations);
				missing.removeAll(supportedReaderRealizations);
				Set<CandidateRealizationReference> extra = new java.util.HashSet<>(supportedReaderRealizations);
				extra.removeAll(actualReaderRealizations);
				throw new IllegalArgumentException(
					"Logical transient candidate domain differs from realizable relation: reader="
						+ fact.targetRead().normalizedSignature() + ", source="
						+ fact.sourceWrite().normalizedSignature() + ", missing=" + missing.stream()
							.map(CandidateRealizationReference::normalizedSignature).sorted().toList()
						+ ", extra=" + extra.stream().map(CandidateRealizationReference::normalizedSignature)
							.sorted().toList());
			}
			if(!slots.computeIfAbsent(fact.sourceWrite(), ignored -> new IdentityHashMap<>())
				.computeIfAbsent(fact.targetRead(), ignored -> new java.util.HashSet<>())
				.add(fact.logicalPosition()))
				throw new IllegalArgumentException("Duplicate logical transient input slot");
			byReaderSlot.computeIfAbsent(fact.targetRead(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(fact.logicalPosition(), ignored -> new ArrayList<>()).add(fact);
		}
		validateReachingDefinitionSupport(byReaderSlot);
		return List.copyOf(sorted);
	}

	private CandidateEmissionRealization requireReferencedRealization(CandidateRealizationReference reference) {
		return candidateRuleFacts.requireExactRealization(reference);
	}

	private void validateCandidateRealizationSupport() {
		for(CandidateRuleFact fact : candidateRuleFacts.orderedFacts())
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				for(CandidateEmissionRealization realization : emission.realizations()) {
					if(realization.supportClauses() instanceof IndexedSupportClauses indexed) {
						if(realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE
							&& indexed.hasMissingNativeWitness())
							throw new IllegalArgumentException(
								"Published native lineage lacks exact worker-pool authority");
						for(CandidateRealizationInputBinding binding : indexed.uniqueBindings())
							validateCandidateRealizationBinding(fact, binding);
						continue;
					}
					FactorizedSupportProduct product = realization.factorizedSupportProduct().orElse(null);
					if(product != null) {
						if(realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE
							&& product.nativeWorkerPoolWitness() == null)
							throw new IllegalArgumentException(
								"Published native lineage lacks exact worker-pool authority");
						for(List<CandidateRealizationInputBinding> factor : product.factors())
							for(CandidateRealizationInputBinding binding : factor)
								validateCandidateRealizationBinding(fact, binding);
						continue;
					}
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						if(realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE
							&& clause.nativeWorkerPoolWitness() == null)
							throw new IllegalArgumentException(
								"Published native lineage lacks exact worker-pool authority");
						for(CandidateRealizationInputBinding binding : clause.inputBindings())
							validateCandidateRealizationBinding(fact, binding);
					}
				}
	}

	private void validateCandidateRealizationBinding(CandidateRuleFact fact,
		CandidateRealizationInputBinding binding) {
		CandidateEmissionRealization source = requireReferencedRealization(binding.source());
		// Logical transient bindings select a reaching boundary realization. They
		// are provenance dependencies rather than physical Hop input positions;
		// synthetic function-return aliases therefore legitimately have no
		// ordered physical input row to validate here.
		if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.LOGICAL_TRANSIENT)
			return;
		if(binding.inputPosition() >= fact.key().orderedInputs().size())
			throw new IllegalArgumentException("Candidate realization input binding position differs");
		CandidateInputState rowInput = fact.key().orderedInputs().get(binding.inputPosition());
		FType effectiveType = binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION
			? binding.relocationAction().materializationFType()
			: source.placementState().output() == FederatedOutput.FOUT
				? source.placementState().fType() : null;
		CandidateInputState expectedInput = effectiveType == null
			? CandidateInputState.absentLocal() : CandidateInputState.present(effectiveType);
		if(!rowInput.equals(expectedInput))
			throw new IllegalArgumentException("Candidate realization binding and oracle input row differs: rule="
				+ fact.key().normalizedSignature() + ", position=" + binding.inputPosition()
				+ ", expected=" + expectedInput + ", binding=" + binding);
		if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT) {
			CompiledInputEdgeFact physical = inputEdgesByConsumerIdentity
				.getOrDefault(fact.key().parentOccurrence(), Map.of()).get(binding.inputPosition());
			if(physical == null || physical.producer() != binding.source().rule().parentOccurrence())
				throw new IllegalArgumentException("DIRECT realization binding lacks its exact physical edge");
		}
		if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION) {
			RelocationActionKey action = binding.relocationAction();
			boolean graphOwned = graph.relocationActions().stream()
				.anyMatch(candidate -> candidate.key() == action);
			boolean consumerCompatible = action.compatibleConsumers()
				.contains(fact.key().parentOccurrence());
			ValueVersionKey actualSource = graph.node(
				binding.source().rule().parentOccurrence()).orElseThrow().valueVersion();
			boolean sourceCompatible = action.sourceValueVersion().equals(actualSource);
			if(!graphOwned || !consumerCompatible || !sourceCompatible)
				throw new IllegalArgumentException(
					"Candidate realization relocation binding lacks exact graph authority: graphOwned="
						+ graphOwned + ", consumerCompatible=" + consumerCompatible
						+ ", sourceCompatible=" + sourceCompatible + ", action="
						+ action.normalizedSignature() + ", consumer="
						+ fact.key().parentOccurrence().normalizedSignature() + ", source="
						+ binding.source().normalizedSignature() + ", actualSourceValue="
						+ actualSource.normalizedSignature());
		}
	}

	private static void validateTransientRealizationTuple(NeutralPlacementGraph.Node owner,
		CandidateEmissionRealization realization, String endpoint) {
		PlacementState state = realization.placementState();
		boolean local = state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
			&& state.fType() == null && !state.shapeDependent()
			&& realization.key().layoutKind() == PlacementLayoutKind.LOCAL;
		boolean federated = state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
			&& state.fType() != null && state.fType() != FType.PART && state.fType() != FType.OTHER
			&& realization.key().layoutKind() != PlacementLayoutKind.LOCAL;
		if(!local && !federated)
			throw new IllegalArgumentException("Illegal transient " + endpoint + " realization tuple");
		if(owner.legalAlternatives().stream().noneMatch(candidate -> candidate == state))
			throw new IllegalArgumentException("Transient " + endpoint
				+ " realization state is not analysis-owned: owner=" + owner.key().normalizedSignature()
				+ ", realization=" + realization.key().normalizedSignature() + ", state="
				+ state.normalizedSignature() + '@' + System.identityHashCode(state) + ", legal="
				+ owner.legalAlternatives().stream().map(candidate -> candidate.normalizedSignature()
					+ '@' + System.identityHashCode(candidate)).toList());
	}

	private static void validateTransientCompatibilityProof(LogicalTransientInputFact fact,
		TransientPlacementCompatibility edge, CandidateEmissionRealization source,
		CandidateEmissionRealization reader, Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		for(PlacementProofKey proof : edge.proof().dependencies())
			if(proof.owner() != null && !analysisKeysByIdentity.containsKey(proof.owner()))
				throw new IllegalArgumentException("Transient compatibility proof has a foreign owner");
		boolean valueProof = edge.proof().dependencies().stream().anyMatch(proof ->
			proof.kind() == PlacementIdentity.PlacementProofKind.VALUE_IDENTITY
				&& proof.owner() == fact.sourceWrite()
				&& proof.authoritySignature().equals(fact.sourceValueVersion().normalizedSignature()
					+ "->" + fact.readValueVersion().normalizedSignature()));
		if(!valueProof)
			throw new IllegalArgumentException("Transient compatibility lacks exact value-identity proof");
		PlacementLayoutKind sourceKind = source.key().layoutKind();
		PlacementLayoutKind readerKind = reader.key().layoutKind();
		if(sourceKind == PlacementLayoutKind.LOCAL)
			return;
		if(readerKind == PlacementLayoutKind.VALUE_MAP) {
			boolean bound = reader.supportClauses().stream().anyMatch(clause ->
				clause.inputBindings().stream().anyMatch(binding ->
					binding.kind() == PlacementIdentity.CandidateInputBindingKind.LOGICAL_TRANSIENT
						&& binding.source().equals(edge.sourceRealization())));
			if(!bound)
				throw new IllegalArgumentException(
					"Value-map transient compatibility is not owned by a reader support clause");
			if(sourceKind == PlacementLayoutKind.VALUE_MAP)
				return; // Acyclic alias replay retains the upstream selected clause authority.
			if(sourceKind != PlacementLayoutKind.DURABLE_MAP
				&& sourceKind != PlacementLayoutKind.NATIVE_LINEAGE)
				throw new IllegalArgumentException(
					"Value-map transient source lacks grounded exact worker-pool authority");
			DurableAnchorKey proofWitness = edge.proof().nativeWorkerPoolWitness();
			if(proofWitness == null || !edge.proof().nativeWorkerPoolLayoutExact())
				throw new IllegalArgumentException(
					"Value-map transient compatibility lacks an exact source worker pool");
			return;
		}
		if(sourceKind == PlacementLayoutKind.DURABLE_MAP && readerKind == PlacementLayoutKind.DURABLE_MAP) {
			if(edge.proof().sourceAnchor() == null
				|| !PlacementIdentity.samePhysicalLayout(source.key().durableAnchor(), edge.proof().sourceAnchor())
				|| !PlacementIdentity.samePhysicalLayout(reader.key().durableAnchor(), edge.proof().readerAnchor()))
				throw new IllegalArgumentException("Transient compatibility durable-map proof differs");
		}
		else {
			if(!edge.proof().provesNativeContinuity(fact.sourceWrite(), fact.targetRead()))
				throw new IllegalArgumentException(
					"Transient native-lineage compatibility lacks owned continuity proof");
			DurableAnchorKey proofWitness = edge.proof().nativeWorkerPoolWitness();
			if(proofWitness == null || readerKind != PlacementLayoutKind.NATIVE_LINEAGE)
				throw new IllegalArgumentException(
					"Transient native-lineage compatibility lacks typed reader worker-pool authority");
			CandidateRealizationSupportClause readerClause = reader.supportClauses().get(0);
			DurableAnchorKey readerWitness = reader.nativeWorkerPoolResidencyWitness(readerClause);
			boolean readerExact = reader.nativeWorkerPoolLayoutExact(readerClause);
			if(readerWitness == null)
				throw new IllegalArgumentException(
					"Transient native-lineage reader lacks a worker-pool witness");
			if(!edge.proof().nativeWorkerPoolLayoutExact() && readerExact)
				throw new IllegalArgumentException(
					"Dynamic transient compatibility cannot publish exact reader geometry");
			boolean sameReaderPool = readerExact && edge.proof().nativeWorkerPoolLayoutExact()
				? PlacementIdentity.samePhysicalLayout(readerWitness, proofWitness)
				: PlacementIdentity.samePhysicalWorkerEndpoints(readerWitness, proofWitness);
			if(!sameReaderPool)
				throw new IllegalArgumentException(
					"Transient native-lineage reader worker-pool authority differs");
		}
	}

	private void validateReachingDefinitionSupport(
		Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> byReaderSlot) {
		for(Map.Entry<CompiledHopKey,List<CompiledHopKey>> entry : cfgDefinitionSourcesByIdentity.entrySet()) {
			if(entry.getValue().isEmpty())
				continue;
			NeutralPlacementGraph.Node reader = graph.node(entry.getKey()).orElseThrow();
			Hop readerHop = hopsByKey.get(reader.key());
			if(reader.kind() == NodeKind.FUNCTION_INPUT
				|| readerHop == null || !(readerHop.getDataType().isMatrix() || readerHop.getDataType().isFrame())
				|| !isCompiledTransientAccess(readerHop, reader, OpOpData.TRANSIENTREAD))
				continue;
			if(logicalBoundaryRealizations.hasCompleteBoundary(reader.key())) {
				// Mixed formal/result reads use the compiler-declared boundary relation,
				// including every ordinary writer. Exact selection checks its pool per source.
				if(!logicalBoundaryRealizations.sources(reader.key()).containsAll(entry.getValue()))
					throw new IllegalArgumentException("Function boundary omits an ordinary reaching writer");
				continue;
			}
			boolean executable = candidateRuleFacts.orderedFacts().stream().anyMatch(candidate ->
				candidate.key().parentOccurrence() == reader.key()
					&& candidate.status() == CandidateEvaluationStatus.AVAILABLE
					&& !candidate.allowedEmissionFacts().isEmpty());
			if(executable && (byReaderSlot.get(reader.key()) == null
				|| byReaderSlot.get(reader.key()).getOrDefault(0, List.of()).isEmpty()))
				throw new IllegalArgumentException(
					"Executable transient reader lacks reaching-definition compatibility relations: reader="
						+ reader.key().normalizedSignature() + ", sources=" + entry.getValue().stream()
							.map(CompiledHopKey::normalizedSignature).toList());
		}
		for(Map.Entry<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> readEntry : byReaderSlot.entrySet())
			for(List<LogicalTransientInputFact> facts : readEntry.getValue().values())
				validateReachingDefinitionSupportForSlot(
					cfgDefinitionSourcesByIdentity.getOrDefault(readEntry.getKey(), List.of()), facts);
	}

	/** Package-private pure validator used by focused authority tests. */
	static void validateReachingDefinitionSupportForSlot(List<CompiledHopKey> reaching,
		List<LogicalTransientInputFact> facts) {
		Objects.requireNonNull(reaching, "reaching");
		Objects.requireNonNull(facts, "facts");
		Set<CompiledHopKey> suppliedSources = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		facts.forEach(fact -> suppliedSources.add(fact.sourceWrite()));
		if(suppliedSources.size() != reaching.size()
			|| reaching.stream().anyMatch(source -> !suppliedSources.contains(source)))
			throw new IllegalArgumentException("Logical transient relation does not cover every reaching definition"
				+ "|reaching=" + reaching.stream().map(CompiledHopKey::normalizedSignature).toList()
				+ "|supplied=" + suppliedSources.stream().map(CompiledHopKey::normalizedSignature).toList()
				+ "|readers=" + facts.stream().map(fact -> fact.targetRead().normalizedSignature()).distinct().toList());
		Set<CandidateRealizationReference> commonReaders = null;
		for(LogicalTransientInputFact fact : facts) {
			Set<CandidateRealizationReference> readers = fact.compatibility().stream()
				.map(TransientPlacementCompatibility::readerRealization)
				.collect(java.util.stream.Collectors.toSet());
			if(commonReaders == null) commonReaders = new java.util.HashSet<>(readers);
			else commonReaders.retainAll(readers);
		}
		if(commonReaders == null || commonReaders.isEmpty())
			throw new IllegalArgumentException("No reader realization is supported by every reaching definition");
		for(LogicalTransientInputFact fact : facts)
			for(TransientPlacementCompatibility edge : fact.compatibility())
				if(!commonReaders.contains(edge.readerRealization()))
					throw new IllegalArgumentException(
						"Logical transient relation retains a partially supported join realization");
	}

	private static Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,LogicalTransientInputFact>>>
		indexLogicalTransientInputs(List<LogicalTransientInputFact> facts) {
		Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,LogicalTransientInputFact>>> indexed = new IdentityHashMap<>();
		for(LogicalTransientInputFact fact : facts) {
			Map<CompiledHopKey,Map<Integer,LogicalTransientInputFact>> byRead = indexed.computeIfAbsent(
				fact.sourceWrite(), ignored -> new IdentityHashMap<>());
			Map<Integer,LogicalTransientInputFact> byPosition = byRead.computeIfAbsent(fact.targetRead(),
				ignored -> new LinkedHashMap<>());
			if(byPosition.putIfAbsent(fact.logicalPosition(), fact) != null)
				throw new IllegalArgumentException("Duplicate logical transient input fact");
		}
		return Collections.unmodifiableMap(indexed);
	}

	private static Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>>
		indexLogicalTransientInputsByReader(List<LogicalTransientInputFact> facts) {
		Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> mutable = new IdentityHashMap<>();
		for(LogicalTransientInputFact fact : facts)
			mutable.computeIfAbsent(fact.targetRead(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(fact.logicalPosition(), ignored -> new ArrayList<>()).add(fact);
		Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> result = new IdentityHashMap<>();
		for(Map.Entry<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> read : mutable.entrySet()) {
			Map<Integer,List<LogicalTransientInputFact>> slots = new LinkedHashMap<>();
			for(Map.Entry<Integer,List<LogicalTransientInputFact>> slot : read.getValue().entrySet())
				slots.put(slot.getKey(), List.copyOf(slot.getValue()));
			result.put(read.getKey(), Collections.unmodifiableMap(slots));
		}
		return Collections.unmodifiableMap(result);
	}

	/** Joint CFG facts share the canonical compiled occurrence identities of this analysis. */
	public Optional<PlacementJointInputAnalysis> jointInputAnalysis() {
		programMutationGuard.run();
		return jointInputAnalysis;
	}

	private List<LogicalFunctionInputFact> deriveLogicalFunctionInputs() {
		Map<CompiledHopKey,List<Constraint>> incomingArguments = new IdentityHashMap<>();
		for(Constraint constraint : graph.constraints())
			if(FunctionInputTransfer.isArgumentConstraint(constraint))
				incomingArguments.computeIfAbsent(constraint.right(), ignored -> new java.util.ArrayList<>())
					.add(constraint);
		List<LogicalFunctionInputFact> result = new java.util.ArrayList<>();
		for(Constraint formal : graph.constraints()) {
			if(formal.kind() != ConstraintKind.SAME_PLACEMENT
				|| !"function-formal-input".equals(formal.evidence()))
				continue;
			List<Constraint> arguments = incomingArguments.getOrDefault(formal.left(), List.of());
			if(arguments.size() != 1)
				throw new IllegalArgumentException("Function input boundary has no unique caller argument");
			Constraint argument = arguments.get(0);
			NeutralPlacementGraph.Node source = graph.node(argument.left()).orElseThrow();
			NeutralPlacementGraph.Node boundary = graph.node(formal.left()).orElseThrow();
			NeutralPlacementGraph.Node read = graph.node(formal.right()).orElseThrow();
			result.add(new LogicalFunctionInputFact(source.key(), boundary.key(), read.key(),
				argument.inputPosition(), 0, source.valueVersion(), boundary.valueVersion(), read.valueVersion()));
		}
		return result.stream().sorted().toList();
	}

	private List<LogicalInlinedFunctionInputFact> deriveLogicalInlinedFunctionInputs() {
		List<LogicalInlinedFunctionInputFact> result = new ArrayList<>();
		Map<CompiledHopKey,Set<Integer>> slots = new LinkedHashMap<>();
		for(Constraint constraint : graph.constraints()) {
			if(constraint.kind() != ConstraintKind.CONJUNCTIVE
				|| !constraint.evidence().startsWith("inlined-function-argument:"))
				continue;
			NeutralPlacementGraph.Node source = graph.node(constraint.left()).orElseThrow();
			NeutralPlacementGraph.Node boundary = graph.node(constraint.right()).orElseThrow();
			if(!source.emittedWork() || boundary.kind() != NodeKind.FUNCTION_INPUT
				|| boundary.emittedWork() || constraint.inputPosition() < 0)
				throw new IllegalArgumentException("Inlined function input constraint endpoints differ");
			if(!slots.computeIfAbsent(boundary.key(), ignored -> new java.util.LinkedHashSet<>())
				.add(constraint.inputPosition()))
				throw new IllegalArgumentException("Inlined function input has ambiguous compiler authority");
			result.add(new LogicalInlinedFunctionInputFact(Optional.of(source.key()), boundary.key(),
				constraint.inputPosition(), Optional.of(source.valueVersion()), boundary.valueVersion()));
		}
		return result.stream().sorted().toList();
	}

	private List<LogicalInlinedFunctionInputFact> validateLogicalInlinedFunctionInputs(
		List<LogicalInlinedFunctionInputFact> supplied, Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		Objects.requireNonNull(supplied, "logicalInlinedFunctionInputs");
		Map<CompiledHopKey,Set<Integer>> slots = new LinkedHashMap<>();
		for(LogicalInlinedFunctionInputFact fact : supplied) {
			if(!analysisKeysByIdentity.containsKey(fact.boundary())
				|| fact.sourceArgument().isPresent()
					&& !analysisKeysByIdentity.containsKey(fact.sourceArgument().orElseThrow()))
				throw new IllegalArgumentException("Inlined function input fact references a foreign occurrence");
			NeutralPlacementGraph.Node boundary = graph.node(fact.boundary()).orElseThrow();
			if(boundary.kind() != NodeKind.FUNCTION_INPUT || boundary.emittedWork()
				|| !boundary.valueVersion().equals(fact.boundaryValueVersion()))
				throw new IllegalArgumentException("Inlined function input boundary differs from graph authority");
			if(fact.sourceArgument().isPresent()) {
				NeutralPlacementGraph.Node source = graph.node(fact.sourceArgument().orElseThrow()).orElseThrow();
				if(!source.emittedWork() || !source.valueVersion().equals(fact.sourceValueVersion().orElseThrow()))
					throw new IllegalArgumentException("Inlined function input source differs from graph authority");
				boolean constraint = graph.constraints().stream().anyMatch(edge ->
					edge.kind() == ConstraintKind.CONJUNCTIVE
						&& edge.left().equals(source.key()) && edge.right().equals(boundary.key())
						&& edge.inputPosition() == fact.callInputPosition()
						&& edge.evidence().startsWith("inlined-function-argument:"));
				if(!constraint)
					throw new IllegalArgumentException("Inlined function input source lacks compiler constraint");
			}
			else if(graph.constraints().stream().anyMatch(edge -> edge.right().equals(boundary.key())
				&& edge.evidence().startsWith("inlined-function-argument:")))
				throw new IllegalArgumentException("Eliminated inlined input retains a physical source constraint");
			if(!slots.computeIfAbsent(fact.boundary(), ignored -> new java.util.LinkedHashSet<>())
				.add(fact.callInputPosition()))
				throw new IllegalArgumentException("Inlined function input has ambiguous compiler outcome");
		}
		return supplied.stream().sorted().toList();
	}

	private List<LogicalFunctionInputFact> validateLogicalFunctionInputs(
		List<LogicalFunctionInputFact> supplied, Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		Objects.requireNonNull(supplied, "logicalFunctionInputs");
		List<LogicalFunctionInputFact> sorted = supplied.stream()
			.map(fact -> Objects.requireNonNull(fact, "logical function input fact")).sorted().toList();
		if(!sorted.equals(supplied))
			throw new IllegalArgumentException("Logical function input facts are not in canonical order");
		Set<String> identities = new java.util.LinkedHashSet<>();
		for(LogicalFunctionInputFact fact : sorted) {
			if(!analysisKeysByIdentity.containsKey(fact.sourceArgument())
				|| !analysisKeysByIdentity.containsKey(fact.boundary())
				|| !analysisKeysByIdentity.containsKey(fact.targetRead()))
				throw new IllegalArgumentException("Logical function input has a foreign occurrence");
			NeutralPlacementGraph.Node source = graph.node(fact.sourceArgument()).orElseThrow();
			NeutralPlacementGraph.Node boundary = graph.node(fact.boundary()).orElseThrow();
			NeutralPlacementGraph.Node read = graph.node(fact.targetRead()).orElseThrow();
			if(boundary.kind() != NodeKind.FUNCTION_INPUT || !isLogicalFunctionRead(read))
				throw new IllegalArgumentException("Logical function input endpoints have wrong node kinds");
			if(source.valueVersion() != fact.sourceValueVersion()
				|| boundary.valueVersion() != fact.boundaryValueVersion()
				|| read.valueVersion() != fact.readValueVersion())
				throw new IllegalArgumentException("Logical function input value identity differs");
			if(!hopsByKey.get(fact.targetRead()).getInput().isEmpty())
				throw new IllegalArgumentException("Logical function read has physical inputs");
			long argumentEdges = graph.constraints().stream().filter(constraint ->
				FunctionInputTransfer.isArgumentConstraint(constraint)
					&& constraint.left() == fact.sourceArgument() && constraint.right() == fact.boundary()
					&& constraint.inputPosition() == fact.callInputPosition()
					&& constraint.evidence().startsWith("function-argument:")).count();
			long formalEdges = graph.constraints().stream().filter(constraint ->
				constraint.kind() == ConstraintKind.SAME_PLACEMENT
					&& constraint.left() == fact.boundary() && constraint.right() == fact.targetRead()
					&& "function-formal-input".equals(constraint.evidence())).count();
			if(argumentEdges != 1 || formalEdges != 1)
				throw new IllegalArgumentException("Logical function input constraint authority differs");
			// The function-input replay owns the exact caller-input rows. A later
			// fixed-point pass may add another caller output (for example BROADCAST)
			// without adding it to this formal read's closed input domain. This constructor
			// therefore validates boundary authority and row shape only; candidate legality,
			// physical input compatibility, and selectors validate the stored rows themselves.
			List<CandidateInputState> inputs = candidateRuleFacts.orderedFacts().stream()
				.filter(candidate -> candidate.key().parentOccurrence() == fact.targetRead()
					&& candidate.status() == CandidateEvaluationStatus.AVAILABLE)
				.map(candidate -> {
					if(candidate.key().orderedInputs().size() != 1)
						throw new IllegalArgumentException(
							"Logical function read candidate must have one caller input");
					return candidate.key().orderedInputs().get(0);
				}).distinct().toList();
			if(inputs.isEmpty())
				throw new IllegalArgumentException("Logical function read has no available caller-input row");
			String identity = System.identityHashCode(fact.sourceArgument()) + ":"
				+ System.identityHashCode(fact.boundary()) + ":" + System.identityHashCode(fact.targetRead())
				+ ':' + fact.callInputPosition();
			if(!identities.add(identity))
				throw new IllegalArgumentException("Duplicate logical function input fact");
		}
		return List.copyOf(sorted);
	}

	static boolean isCompiledTransientAccess(Hop hop, NeutralPlacementGraph.Node node, OpOpData operation) {
		if(operation != OpOpData.TRANSIENTREAD && operation != OpOpData.TRANSIENTWRITE
			|| !(hop instanceof DataOp data) || data.getOp() != operation
			|| !isCompiledHopOccurrenceKey(node.key(), node.kind()))
			return false;
		NodeKind physicalKind = operation == OpOpData.TRANSIENTREAD
			? NodeKind.TRANSIENT_READ : NodeKind.TRANSIENT_WRITE;
		// Phi is the value/control classification of a real compiled read or write,
		// not a replacement for its runtime operation. Clones/synthetic boundaries
		// remain outside this exact CFG forwarding authority.
		return node.kind() == physicalKind || node.kind() == NodeKind.LOOP_PHI
			|| node.kind() == NodeKind.BRANCH_JOIN;
	}

	static boolean isCompiledAcyclicTransientForwardAccess(Hop hop,
		NeutralPlacementGraph.Node node, OpOpData operation) {
		// A branch join is an acyclic merge whose complete reaching-definition set can
		// prove one local value. A LOOP_PHI is cyclic: admitting its back edge can make
		// a later demotion marker appear downstream of itself and replace its native
		// FED/LOUT execution with CP/LOUT. Loop-carried locality remains governed by the
		// existing concrete TRead/TWrite paths until an iteration-aware marker contract
		// can distinguish entry and back-edge states.
		return node.kind() != NodeKind.LOOP_PHI
			&& isCompiledTransientAccess(hop, node, operation);
	}

	private static boolean isLogicalFunctionRead(NeutralPlacementGraph.Node read) {
		return (read.kind() == NodeKind.TRANSIENT_READ || read.kind() == NodeKind.BRANCH_JOIN
			|| read.kind() == NodeKind.LOOP_PHI)
			&& (read.valueVersion().versionKind() == PlacementIdentity.VersionKind.FUNCTION_INPUT
				|| read.valueVersion().predecessorVersions().stream()
					.anyMatch(value -> value.startsWith("cfg-function-input:")));
	}

	private static Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,List<LogicalFunctionInputFact>>>>
		indexLogicalFunctionInputs(List<LogicalFunctionInputFact> facts) {
		Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,List<LogicalFunctionInputFact>>>> indexed =
			new IdentityHashMap<>();
		for(LogicalFunctionInputFact fact : facts) {
			Map<CompiledHopKey,Map<Integer,List<LogicalFunctionInputFact>>> byRead = indexed.computeIfAbsent(
				fact.sourceArgument(), ignored -> new IdentityHashMap<>());
			Map<Integer,List<LogicalFunctionInputFact>> byPosition = byRead.computeIfAbsent(fact.targetRead(),
				ignored -> new LinkedHashMap<>());
			byPosition.computeIfAbsent(fact.logicalPosition(), ignored -> new java.util.ArrayList<>()).add(fact);
		}
		return Collections.unmodifiableMap(indexed);
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		DMLProgram programOwner, PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts) {
		this(graph, occurrences, List.of(), programOwner, shapeFacts, analysisFingerprint, heuristicPolicyFacts,
			candidateRuleDomainKeys, candidateRuleFacts, candidateConsumerDomainKeys, candidateConsumerProfileFacts,
			List.of(), deriveCompiledInputEdges(graph, occurrences, shapeFacts));
	}

	/** Compatibility surface for fixtures that predate canonical candidate-fact publication. */
	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		DMLProgram programOwner, PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts) {
		this(graph, occurrences, programOwner, shapeFacts, analysisFingerprint, heuristicPolicyFacts,
			List.of(), List.of(), List.of(), List.of());
	}


	private String canonicalizeSuppliedAnalysisFingerprint(String supplied) {
		if(!supplied.matches("[0-9a-f]{64}"))
			return supplied;
		return canonicalAnalysisFingerprint();
	}

	private String canonicalAnalysisFingerprint() {
		String graphSignature = graph.normalizedSignature();
		List<String> projectionSignatures = occurrences.stream()
			.map(occurrence -> stableSignature(occurrence.normalizedSignature())).sorted().toList();
		return PlacementGraphFingerprint.sha256(stableSignature(graphSignature) + '\n'
			+ String.join("\n", projectionSignatures) + '\n'
			+ stableSignature(privacyFacts.normalizedSignature()));
	}

	private static PlacementPrivacyFacts defaultPrivacyFacts(NeutralPlacementGraph graph) {
		Objects.requireNonNull(graph, "graph");
		List<PlacementPrivacyFacts.PrivacyFact> facts = graph.nodes().stream()
			.map(node -> new PlacementPrivacyFacts.PrivacyFact(node.key(), node.valueVersion(),
				Privacy.PUBLIC, List.of())).toList();
		return new PlacementPrivacyFacts(graph.nodes(), facts, 0);
	}

	private static String stableSignature(String signature) {
		return PlacementGraphFingerprint.stableSignature(signature);
	}

	private List<CompiledInputEdgeFact> validateCompiledInputEdges(List<CompiledInputEdgeFact> facts) {
		Objects.requireNonNull(facts, "compiledInputEdges");
		List<CompiledInputEdgeFact> expected = deriveCompiledInputEdges(
			graph, compiledHopOccurrences(), shapeFacts);
		if(facts.size() != expected.size())
			throw new IllegalArgumentException("Compiled input edge facts do not exactly cover compiled matrix inputs");
		List<CompiledInputEdgeFact> copied = new java.util.ArrayList<>(facts.size());
		Set<EdgeIdentity> seen = new java.util.HashSet<>();
		for(int i = 0; i < facts.size(); i++) {
			CompiledInputEdgeFact fact = Objects.requireNonNull(facts.get(i), "compiled input edge fact");
			CompiledInputEdgeFact exact = expected.get(i);
			if(fact.producer() != exact.producer() || fact.consumer() != exact.consumer()
				|| fact.inputPosition() != exact.inputPosition())
				throw new IllegalArgumentException("Compiled input edge fact order, identity, or topology differs");
			if(!seen.add(new EdgeIdentity(fact.producer(), fact.consumer(), fact.inputPosition())))
				throw new IllegalArgumentException("Duplicate compiled input edge fact");
			copied.add(fact);
		}
		return List.copyOf(copied);
	}

	private Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>>> indexCompiledInputEdges(
		List<CompiledInputEdgeFact> facts) {
		Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>>> indexed = new IdentityHashMap<>();
		for(CompiledInputEdgeFact fact : facts) {
			Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> byConsumer = indexed.computeIfAbsent(
				fact.producer(), ignored -> new IdentityHashMap<>());
			Map<Integer,CompiledInputEdgeFact> byPosition = byConsumer.computeIfAbsent(fact.consumer(),
				ignored -> new LinkedHashMap<>());
			if(byPosition.putIfAbsent(fact.inputPosition(), fact) != null)
				throw new IllegalArgumentException("Duplicate compiled input edge fact");
		}
		return Collections.unmodifiableMap(indexed);
	}

	private static Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> indexCompiledInputEdgesByConsumer(
		List<CompiledInputEdgeFact> facts) {
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> indexed = new IdentityHashMap<>();
		for(CompiledInputEdgeFact fact : facts) {
			Map<Integer,CompiledInputEdgeFact> byPosition = indexed.computeIfAbsent(
				fact.consumer(), ignored -> new LinkedHashMap<>());
			if(byPosition.putIfAbsent(fact.inputPosition(), fact) != null)
				throw new IllegalArgumentException("Duplicate compiled consumer input edge fact");
		}
		return Collections.unmodifiableMap(indexed);
	}

	private static List<CompiledInputEdgeFact> deriveCompiledInputEdges(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> occurrences, PlacementShapeFacts shapeFacts) {
		Objects.requireNonNull(graph, "graph");
		Objects.requireNonNull(occurrences, "occurrences");
		Objects.requireNonNull(shapeFacts, "shapeFacts");
		List<HopOccurrenceProjection> compiled = occurrences.stream()
			.filter(occurrence -> isCompiledHopOccurrenceKey(occurrence.key(), graph.node(occurrence.key())
				.orElseThrow(() -> new IllegalArgumentException("Occurrence has a foreign graph key")).kind())).toList();
		Map<CompiledHopKey,HopOccurrenceProjection> occurrencesByIdentity = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : compiled)
			if(occurrencesByIdentity.put(occurrence.key(), occurrence) != null)
				throw new IllegalArgumentException("Duplicate compiled Hop occurrence identity");
		Map<CompiledHopKey,Map<Integer,Constraint>> inputsByConsumer = new IdentityHashMap<>();
		for(Constraint constraint : graph.constraints()) {
			if(!isCompiledInputConstraint(constraint))
				continue;
			HopOccurrenceProjection producer = occurrencesByIdentity.get(constraint.left());
			HopOccurrenceProjection consumer = occurrencesByIdentity.get(constraint.right());
			if(producer == null || consumer == null)
				throw new IllegalArgumentException("Compiled-input constraint has a foreign compiled owner");
			if(constraint.inputPosition() < 0)
				throw new IllegalArgumentException("Compiled-input constraint has no exact input position");
			NodeShapeFact producerShape = shapeFacts.shapeFact(producer.key()).orElseThrow(() ->
				new IllegalArgumentException("Compiled-input producer has no builder-owned shape fact"));
			if(!isPlacementDataType(producerShape.dataType()))
				continue;
			Map<Integer,Constraint> byPosition = inputsByConsumer.computeIfAbsent(consumer.key(),
				ignored -> new LinkedHashMap<>());
			if(byPosition.putIfAbsent(constraint.inputPosition(), constraint) != null)
				throw new IllegalArgumentException("Compiled consumer input position has multiple producers");
		}
		List<CompiledInputEdgeFact> edges = new java.util.ArrayList<>();
		for(HopOccurrenceProjection consumer : compiled) {
			Map<Integer,Constraint> byPosition = inputsByConsumer.get(consumer.key());
			if(byPosition == null)
				continue;
			byPosition.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
				Constraint constraint = entry.getValue();
				edges.add(new CompiledInputEdgeFact(constraint.left(), constraint.right(), entry.getKey()));
			});
		}
		return List.copyOf(edges);
	}

	private static boolean isCompiledInputConstraint(Constraint constraint) {
		return constraint.kind() == ConstraintKind.DOMINATES && "data-input".equals(constraint.evidence())
			|| constraint.kind() == ConstraintKind.SAME_PLACEMENT
				&& "function-input-binding".equals(constraint.evidence());
	}

	private static boolean isPlacementDataType(DataType dataType) {
		return dataType == DataType.MATRIX || dataType == DataType.FRAME;
	}

	private record EdgeIdentity(CompiledHopKey producer, CompiledHopKey consumer, int inputPosition) { }

	static boolean isCompiledHopOccurrenceKey(CompiledHopKey key, NodeKind kind) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(kind, "kind");
		if(kind == NodeKind.FUNCTION_INPUT || kind == NodeKind.FUNCTION_OUTPUT)
			return false;
		for(String part : key.controlRegion().regionPath())
			if(part.startsWith("function-boundary:"))
				return false;
		return true;
	}

	public NeutralPlacementGraph graph() {
		return graph;
	}

	public List<HopOccurrenceProjection> occurrences() {
		return occurrences;
	}

	/**
	 * Return projections backed by compiled Hop occurrences. Synthetic function
	 * boundary projections remain part of the semantic graph but are not
	 * independent selected-plan assignments.
	 *
	 * @return immutable compiled occurrence projections in analysis order
	 */
	public List<HopOccurrenceProjection> compiledHopOccurrences() {
		return compiledHopOccurrences;
	}

	public boolean isCompiledHopOccurrence(HopOccurrenceProjection occurrence) {
		Objects.requireNonNull(occurrence, "occurrence");
		if(!occurrenceOwnershipByIdentity.containsKey(occurrence))
			throw new IllegalArgumentException("Occurrence is not owned by this placement analysis");
		return compiledOccurrenceKeysByIdentity.containsKey(occurrence.key());
	}

	public boolean isCompiledHopOccurrence(CompiledHopKey key) {
		Objects.requireNonNull(key, "key");
		if(!occurrenceKeysByIdentity.containsKey(key))
			throw new IllegalArgumentException("Key is not owned by this placement analysis");
		return compiledOccurrenceKeysByIdentity.containsKey(key);
	}

	/** Stable constructor-captured signature of an exact analysis-owned occurrence. */
	public String normalizedOccurrenceSignature(CompiledHopKey key) {
		String signature = occurrenceSignaturesByIdentity.get(Objects.requireNonNull(key, "key"));
		if(signature == null)
			throw new IllegalArgumentException("Key is not owned by this placement analysis");
		return signature;
	}

	public List<StatementBlock> topLevelStatementBlocks() {
		return topLevelStatementBlocks;
	}

	/** Shared immutable control-flow frequency authority captured before selector execution. */
	public OccurrenceExecutionFrequencyFacts executionFrequencyFacts() {
		return executionFrequencyFacts;
	}

	/**
	 * Immutable namespace index over the compiled program's named function roots.
	 * The indexed blocks remain compiler-owned and are protected by the program
	 * structure fingerprint rather than copied into a second semantic universe.
	 */
	public Map<String,FunctionStatementBlock> namedFunctionStatementBlocks() {
		assertProgramStructureUnchanged();
		return namedFunctionStatementBlocks;
	}

	/** Whether named compiler-owned function roots are protected by the analysis guard. */
	public boolean hasGuardedFunctionRoots() {
		return guardedFunctionRoots;
	}

	/** Fail closed if compiler-owned program structure changed after analysis. */
	public void assertProgramStructureUnchanged() {
		programMutationGuard.run();
	}

	/** Transaction-internal authorization of one completely committed exact planner emission. */
	void authorizeCommittedProgramStructure() {
		if(programMutationGuard instanceof ProgramStructureAuthority authority)
			authority.authorizeCommittedEmission();
		else
			programMutationGuard.run();
	}

	public Optional<Hop> hop(CompiledHopKey key) {
		return Optional.ofNullable(hopsByKey.get(Objects.requireNonNull(key, "key")));
	}

	public Optional<NodeShapeFact> shapeFact(CompiledHopKey key) {
		return shapeFacts.shapeFact(key);
	}

	/** Source-compiled dimensions captured before conservative CFG shape closure. */
	public Optional<NodeShapeFact> sourceCompiledShapeFact(CompiledHopKey key) {
		return shapeFacts.sourceCompiledShapeFact(key);
	}

	public Optional<AbstractShapeFact> abstractShapeFact(CompiledHopKey key) {
		return shapeFacts.abstractShapeFact(key);
	}

	public PhysicalCostEstimateFact physicalCostEstimateFact(CompiledHopKey key) {
		PhysicalCostEstimateFact fact = physicalCostEstimateFactsByIdentity.get(
			Objects.requireNonNull(key, "key"));
		if(fact == null)
			throw new IllegalArgumentException("Physical cost estimate key is not analysis-owned");
		return fact;
	}

	/** Compile-time function-boundary payload fallback for an analysis-owned source/read pair. */
	public double physicalFunctionInputEstimate(CompiledHopKey source, CompiledHopKey targetRead) {
		Map<CompiledHopKey,Double> byTarget = physicalFunctionInputEstimatesByIdentity.get(
			Objects.requireNonNull(source, "source"));
		Double estimate = byTarget == null ? null : byTarget.get(
			Objects.requireNonNull(targetRead, "targetRead"));
		if(estimate == null)
			throw new IllegalArgumentException("Physical function-input estimate pair is not analysis-owned");
		return estimate;
	}

	/** Cost-only normal-completion bounds; never proof of exact geometry or legality. */
	public Optional<PlacementCostSizeBounds.Bounds> costSizeBound(CompiledHopKey key) {
		return costSizeBounds.get(key);
	}

	public Optional<ScalarLiteralFact> scalarLiteralFact(CompiledHopKey key) {
		return shapeFacts.scalarLiteralFact(key);
	}

	public PlacementPrivacyFacts privacyFactAuthority() {
		return privacyFacts;
	}

	/** Empty unless the builder explicitly enabled privacy audit evidence capture. */
	public Optional<CandidatePrivacyClosureEvidence> candidatePrivacyClosureEvidence() {
		return candidatePrivacyClosureEvidence;
	}

	public Map<CompiledHopKey,Privacy> privacyFacts() {
		return privacyFacts.asMap();
	}

	public Privacy requirePrivacy(CompiledHopKey key) {
		return privacyFacts.requirePrivacy(key);
	}

	public int numWorkers() {
		return privacyFacts.numWorkers();
	}

	public String analysisFingerprint() {
		return analysisFingerprint;
	}

	public HeuristicPolicyFacts heuristicPolicyFacts() {
		return heuristicPolicyFacts;
	}

	public CandidateRuleDomain candidateRuleDomain() { return candidateRuleDomain; }
	public CandidateRuleFacts candidateRuleFacts() { return candidateRuleFacts; }
	public CandidateConsumerProfileFacts candidateConsumerProfileFacts() { return candidateConsumerProfileFacts; }
	public DetachedConsumerProfileFacts detachedConsumerProfileFacts() { return detachedConsumerProfileFacts; }

	/** Exact immutable candidate receipt owned by this analysis. */
	public CandidateSelectionReceipt canonicalCandidateReceipt(CandidateRuleKey rule,
		CandidateEmissionFact emission) {
		return candidateReceiptDomain.requireSingleton(rule, emission);
	}

	/** Exact immutable receipt for one physical realization of an emission. */
	public CandidateSelectionReceipt canonicalCandidateReceipt(CandidateRuleKey rule,
		CandidateEmissionFact emission, CandidateEmissionRealization realization) {
		return candidateReceiptDomain.require(rule, emission, realization);
	}

	/** Exact immutable receipt for one conjunctive support clause of a realization. */
	public CandidateSelectionReceipt canonicalCandidateReceipt(CandidateRuleKey rule,
		CandidateEmissionFact emission, CandidateEmissionRealization realization,
		CandidateRealizationSupportClause supportClause) {
		return candidateReceiptDomain.require(rule, emission, realization, supportClause);
	}

	/** All canonical receipts for one emission, in realization order. */
	public List<CandidateSelectionReceipt> canonicalCandidateReceipts(CandidateRuleKey rule,
		CandidateEmissionFact emission) {
		return candidateReceiptDomain.requireAll(rule, emission);
	}

	/** Canonicalizes without reconstructing nested candidate identity strings. */
	public List<CandidateSelectionReceipt> canonicalCandidateReceipts(
		java.util.Collection<CandidateSelectionReceipt> receipts) {
		return candidateReceiptDomain.canonicalize(Objects.requireNonNull(receipts, "receipts"));
	}

	/** Exact structural rank corresponding to CandidateSelectionReceipt.compareTo. */
	public int candidateReceiptRank(CandidateSelectionReceipt receipt) {
		return candidateReceiptDomain.rank(Objects.requireNonNull(receipt, "receipt"));
	}

	/** Shared canonical order for the immutable relocation-action universe. */
	public RelocationSelections.CanonicalOrderIndex relocationOrder() {
		return relocationOrder;
	}

	/**
	 * Reuses the common index only for the exact analysis-owned action objects.
	 * Policy projections may clone/filter actions and therefore need one selector-
	 * local index whose embedded obligation objects belong to that projection.
	 */
	public RelocationSelections.CanonicalOrderIndex relocationOrderFor(
		java.util.Collection<NeutralPlacementGraph.RelocationAction> actions) {
		Objects.requireNonNull(actions, "actions");
		if(actions.size() == relocationActionsByIdentity.size()
			&& actions.stream().allMatch(relocationActionsByIdentity::containsKey))
			return relocationOrder;
		return RelocationSelections.canonicalOrderIndex(actions);
	}

	/**
	 * Reuses the immutable full-analysis source-privacy authority for the exact graph,
	 * including copied or filtered action collections. A projected authority graph
	 * owns a different source-occurrence scope and must rebuild its own index.
	 */
	RelocationSelections.RelocationPrivacyIndex relocationPrivacyFor(
		NeutralPlacementGraph authorityGraph,
		java.util.Collection<NeutralPlacementGraph.RelocationAction> actions) {
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actions, "actions");
		if(authorityGraph == graph) {
			RelocationSelections.RelocationPrivacyIndex common = relocationPrivacy;
			if(common == null) {
				synchronized(this) {
					common = relocationPrivacy;
					if(common == null) {
						common = RelocationSelections.buildRelocationPrivacyIndex(
							this, graph, graph.relocationActions());
						relocationPrivacy = common;
					}
				}
			}
			if(actions == graph.relocationActions() || common.containsAllSources(actions))
				return common;
		}
		return RelocationSelections.buildRelocationPrivacyIndex(this, authorityGraph, actions);
	}


	public List<CompiledInputEdgeFact> compiledInputEdgesInCanonicalOrder() {
		return compiledInputEdgesInCanonicalOrder;
	}

	/** O(1) exact compiled matrix/frame input lookup by consumer occurrence and position. */
	public Optional<CompiledInputEdgeFact> compiledInputEdge(CompiledHopKey consumer,
		int inputPosition) {
		if(inputPosition < 0)
			throw new IllegalArgumentException("inputPosition must be non-negative");
		NeutralPlacementGraph.Node target = graph.node(Objects.requireNonNull(consumer, "consumer"))
			.orElseThrow(() -> new IllegalArgumentException("Compiled input consumer is outside the analysis"));
		Map<Integer,CompiledInputEdgeFact> byPosition = inputEdgesByConsumerIdentity.get(target.key());
		return Optional.ofNullable(byPosition == null ? null : byPosition.get(inputPosition));
	}

	/**
	 * Runtime-native coordinator access required by one exact compiled data edge.
	 * Matrix/frame {@code nrow}, {@code ncol}, and {@code length} read dimensions
	 * directly from {@code FederationMap} ranges in
	 * {@code AggregateUnaryCPInstruction}; they do not acquire or download the
	 * partition payload. The fact is captured once by the shared analysis so every
	 * selector, cost model, and lowering projection consumes the same boundary
	 * semantics.
	 */
	public CoordinatorInputAccess coordinatorInputAccess(CompiledInputEdgeFact edge) {
		CoordinatorInputAccess access = coordinatorInputAccessByIdentity.get(
			Objects.requireNonNull(edge, "compiled input edge"));
		if(access == null)
			throw new IllegalArgumentException(
				"Coordinator input access requires an exact analysis-owned compiled edge");
		return access;
	}

	public boolean isCoordinatorMetadataOnlyInput(CompiledInputEdgeFact edge) {
		return coordinatorInputAccess(edge) == CoordinatorInputAccess.FEDERATION_MAP_METADATA;
	}

	/** O(1) exact producer/consumer boundary query used by DP reconciliation. */
	public boolean isCoordinatorMetadataOnlyInputBoundary(CompiledHopKey producer,
		CompiledHopKey consumer) {
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> byConsumer =
			inputEdgesByIdentity.get(Objects.requireNonNull(producer, "producer"));
		Map<Integer,CompiledInputEdgeFact> byPosition = byConsumer == null ? null
			: byConsumer.get(Objects.requireNonNull(consumer, "consumer"));
		return byPosition != null && byPosition.size() == 1
			&& isCoordinatorMetadataOnlyInput(byPosition.values().iterator().next());
	}

	private Map<CompiledInputEdgeFact,CoordinatorInputAccess> deriveCoordinatorInputAccess(
		List<CompiledInputEdgeFact> edges) {
		Map<CompiledInputEdgeFact,CoordinatorInputAccess> result = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : edges) {
			Hop producer = hopsByKey.get(edge.producer());
			Hop consumer = hopsByKey.get(edge.consumer());
			result.put(edge, coordinatorInputAccess(producer, consumer, edge.inputPosition()));
		}
		return Collections.unmodifiableMap(result);
	}

	/** Construction-time kernel shared with the pre-selector privacy closure. */
	static CoordinatorInputAccess coordinatorInputAccess(Hop producer, Hop consumer, int inputPosition) {
		boolean federationMapMetadata = inputPosition == 0
			&& producer != null && producer.getDataType() != null
			&& (producer.getDataType().isMatrix() || producer.getDataType().isFrame())
			&& consumer instanceof UnaryOp unary
			&& (unary.getOp() == OpOp1.NROW || unary.getOp() == OpOp1.NCOL
				|| unary.getOp() == OpOp1.LENGTH);
		return federationMapMetadata ? CoordinatorInputAccess.FEDERATION_MAP_METADATA
			: CoordinatorInputAccess.PAYLOAD;
	}

	/**
	 * Exact raw CFG reaching definitions for one occurrence.  This relation is
	 * intentionally broader than {@link #logicalTransientInputsInCanonicalOrder()}:
	 * the latter exists only when the writer/read candidate tuples can be replayed
	 * as one physical planner decision, while shape and cost proofs may still need
	 * the immutable value-flow source of a non-replayable read.
	 */
	public List<CompiledHopKey> cfgDefinitionSourcesInCanonicalOrder(CompiledHopKey key) {
		NeutralPlacementGraph.Node target = graph.node(Objects.requireNonNull(key, "key"))
			.orElseThrow(() -> new IllegalArgumentException("CFG definition target is outside the analysis"));
		return cfgDefinitionSourcesByIdentity.getOrDefault(target.key(), List.of());
	}

	private static Map<CompiledHopKey,List<CompiledHopKey>> indexCfgDefinitionSources(
		NeutralPlacementGraph graph, Map<CompiledHopKey,Hop> hopsByKey) {
		Map<String,List<CompiledHopKey>> sourcesByReference = new java.util.HashMap<>();
		for(NeutralPlacementGraph.Node node : graph.nodes()) {
			Hop owner = hopsByKey.get(node.key());
			// cfg-definition references are minted only by real definition operations.
			// A TRead can legitimately share the same ValueVersionKey/reference with a
			// later TWrite (for example old_norm_R2 = norm_R2), but it is not another
			// reaching writer and must not inflate the compatibility relation's source set.
			if(!isCompiledHopOccurrenceKey(node.key(), node.kind()) || !(owner instanceof DataOp data)
				|| data.getOp() != OpOpData.TRANSIENTWRITE && data.getOp() != OpOpData.FUNCTIONOUTPUT)
				continue;
			sourcesByReference.computeIfAbsent(node.valueVersion().cfgReferenceSignature(),
				ignored -> new java.util.ArrayList<>()).add(node.key());
		}
		for(List<CompiledHopKey> sources : sourcesByReference.values())
			sources.sort(null);

		Map<CompiledHopKey,List<CompiledHopKey>> indexed = new IdentityHashMap<>();
		for(NeutralPlacementGraph.Node target : graph.nodes()) {
			Set<String> references = target.valueVersion().predecessorVersions().stream()
				.filter(value -> value.startsWith("cfg-definition:"))
				.map(value -> value.substring("cfg-definition:".length()))
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
			if(references.isEmpty())
				continue;
			List<CompiledHopKey> sources = new java.util.ArrayList<>();
			for(String reference : references) {
				List<CompiledHopKey> owners = sourcesByReference.get(reference);
				if(owners == null || owners.isEmpty())
					throw new IllegalStateException("CFG definition reference has no exact placement owner");
				sources.addAll(owners);
			}
			sources.sort(null);
			indexed.put(target.key(), List.copyOf(sources));
		}
		return Collections.unmodifiableMap(indexed);
	}

	/**
	 * A DML {@link FunctionOp} is a coordinator-side call placeholder. Its matrix arguments are
	 * forwarded through the explicit logical actual/formal boundary and are not consumed locally by
	 * the call Hop itself. Consequently, a FED/FOUT argument followed by a CP/LOUT call placeholder
	 * does not imply a FED-to-local matrix materialization.
	 */
	public boolean isDmlFunctionCallBoundary(CompiledHopKey key) {
		Hop owner = hop(Objects.requireNonNull(key, "function call key")).orElseThrow(
			() -> new IllegalArgumentException("Function call boundary key is outside the analysis"));
		return isDmlFunctionCallBoundary(graph.node(key).orElseThrow(), owner);
	}

	static boolean isDmlFunctionCallBoundary(NeutralPlacementGraph.Node node, Hop owner) {
		// Synthetic formals may share a FunctionOp origin but carry actual payloads.
		return node.kind() == NodeKind.FUNCTION_CALL && owner instanceof FunctionOp function
			&& function.getFunctionType() == FunctionType.DML;
	}

	public List<LogicalTransientInputFact> logicalTransientInputsInCanonicalOrder() {
		return logicalTransientInputsInCanonicalOrder;
	}

	/** All reaching-writer relations for one exact reader slot. */
	public List<LogicalTransientInputFact> logicalTransientInputsForReader(CompiledHopKey targetRead,
		int logicalPosition) {
		Map<Integer,List<LogicalTransientInputFact>> bySlot = logicalInputsByReaderSlot.get(
			Objects.requireNonNull(targetRead, "targetRead"));
		return bySlot == null ? List.of() : bySlot.getOrDefault(logicalPosition, List.of());
	}

	/** Resolves a stable reference back to the one analysis-owned realization it names. */
	public CandidateEmissionRealization requireExactCandidateRealization(
		CandidateRealizationReference reference) {
		return requireReferencedRealization(Objects.requireNonNull(reference, "reference"));
	}

	synchronized DerivedFoutAnchorCompatibility.Prepared derivedFoutAnchorCompatibility(
		NeutralPlacementGraph.DerivedFoutMaterializationAction action) {
		if(graph.derivedFoutMaterializationActions().stream().noneMatch(owned -> owned == action))
			throw new IllegalArgumentException("Derived FOUT action is not analysis-owned");
		return derivedFoutAnchorCompatibility.computeIfAbsent(action,
			owned -> new DerivedFoutAnchorCompatibility.Prepared(this, owned));
	}

	/** Exact compatibility edges for one chosen reader realization across all reaching writers. */
	public List<TransientPlacementCompatibility> transientCompatibilityForReader(
		CandidateRealizationReference reader) {
		Objects.requireNonNull(reader, "reader");
		return logicalTransientInputsForReader(reader.rule().parentOccurrence(), 0).stream()
			.flatMap(fact -> fact.compatibilityForReader(reader).stream()).sorted().toList();
	}

	/** Exact relation query used after a planner has selected both endpoint receipts. */
	public boolean isTransientPlacementCompatible(LogicalTransientInputFact fact,
		CandidateSelectionReceipt source, CandidateSelectionReceipt reader) {
		Objects.requireNonNull(fact, "fact");
		CandidateSelectionReceipt exactSource = candidateReceiptDomain.require(source.rule(), source.emission(),
			source.realization());
		CandidateSelectionReceipt exactReader = candidateReceiptDomain.require(reader.rule(), reader.emission(),
			reader.realization());
		CandidateRealizationReference sourceRef = CandidateRealizationReference.of(
			exactSource.rule(), exactSource.realization());
		CandidateRealizationReference readerRef = CandidateRealizationReference.of(
			exactReader.rule(), exactReader.realization());
		return fact.compatibility().stream().anyMatch(edge -> edge.sourceRealization().equals(sourceRef)
			&& edge.readerRealization().equals(readerRef));
	}

	public LogicalTransientInputFact requireExactLogicalTransientInput(CompiledHopKey sourceWrite,
		CompiledHopKey targetRead, int logicalPosition) {
		Map<CompiledHopKey,Map<Integer,LogicalTransientInputFact>> byRead = logicalInputsByIdentity.get(
			Objects.requireNonNull(sourceWrite, "sourceWrite"));
		Map<Integer,LogicalTransientInputFact> byPosition = byRead == null ? null
			: byRead.get(Objects.requireNonNull(targetRead, "targetRead"));
		LogicalTransientInputFact fact = byPosition == null ? null : byPosition.get(logicalPosition);
		if(fact == null)
			throw new IllegalArgumentException("Exact logical transient input fact is missing");
		return fact;
	}

	public LogicalBoundaryRealizations logicalBoundaryRealizations() {
		return logicalBoundaryRealizations;
	}

	public List<LogicalFunctionInputFact> logicalFunctionInputsInCanonicalOrder() {
		return logicalFunctionInputsInCanonicalOrder;
	}

	public List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputsInCanonicalOrder() {
		return logicalInlinedFunctionInputsInCanonicalOrder;
	}

	public LogicalInlinedFunctionInputFact requireExactLogicalInlinedFunctionInput(
		CompiledHopKey boundary, int callInputPosition) {
		Objects.requireNonNull(boundary, "boundary");
		List<LogicalInlinedFunctionInputFact> matches = logicalInlinedFunctionInputsInCanonicalOrder.stream()
			.filter(fact -> fact.boundary().equals(boundary)
				&& fact.callInputPosition() == callInputPosition)
			.toList();
		if(matches.size() != 1)
			throw new IllegalArgumentException("Inlined function input outcome is not unique: boundary="
				+ boundary.normalizedSignature() + " position=" + callInputPosition
				+ " matches=" + matches.size());
		return matches.get(0);
	}

	public LogicalFunctionInputFact requireExactLogicalFunctionInput(CompiledHopKey sourceArgument,
		CompiledHopKey targetRead, int logicalPosition) {
		Map<CompiledHopKey,Map<Integer,List<LogicalFunctionInputFact>>> byRead =
			logicalFunctionInputsByIdentity.get(Objects.requireNonNull(sourceArgument, "sourceArgument"));
		Map<Integer,List<LogicalFunctionInputFact>> byPosition = byRead == null ? null
			: byRead.get(Objects.requireNonNull(targetRead, "targetRead"));
		List<LogicalFunctionInputFact> facts = byPosition == null ? null : byPosition.get(logicalPosition);
		if(facts == null || facts.size() != 1)
			throw new IllegalArgumentException("Exact logical function input fact is missing or ambiguous");
		return facts.get(0);
	}

	/** Resolves one call occurrence when equal actual/formal identities occur at multiple call sites. */
	public LogicalFunctionInputFact requireExactLogicalFunctionInput(CompiledHopKey sourceArgument,
		CompiledHopKey boundary, CompiledHopKey targetRead, int callInputPosition, int logicalPosition) {
		Objects.requireNonNull(boundary, "boundary");
		Map<CompiledHopKey,Map<Integer,List<LogicalFunctionInputFact>>> byRead =
			logicalFunctionInputsByIdentity.get(Objects.requireNonNull(sourceArgument, "sourceArgument"));
		Map<Integer,List<LogicalFunctionInputFact>> byPosition = byRead == null ? null
			: byRead.get(Objects.requireNonNull(targetRead, "targetRead"));
		List<LogicalFunctionInputFact> facts = byPosition == null ? null : byPosition.get(logicalPosition);
		List<LogicalFunctionInputFact> matches = facts == null ? List.of() : facts.stream()
			.filter(fact -> fact.boundary().equals(boundary)
				&& fact.callInputPosition() == callInputPosition)
			.toList();
		if(matches.size() != 1)
			throw new IllegalArgumentException(
				"Exact logical function input occurrence is missing or ambiguous");
		return matches.get(0);
	}

	/**
	 * Validates one complete analysis-owned call-site binding. Multiple calls may
	 * intentionally reuse the same source occurrence, formal read, and logical
	 * position; their synthetic boundary is the remaining call-site identity.
	 */
	public LogicalFunctionInputFact requireExactLogicalFunctionInput(
		LogicalFunctionInputFact supplied) {
		Objects.requireNonNull(supplied, "logical function input fact");
		for(LogicalFunctionInputFact fact : logicalFunctionInputsInCanonicalOrder)
			if(fact == supplied)
				return fact;
		throw new IllegalArgumentException("Logical function input fact is not analysis-owned");
	}

	/**
	 * Resolves the physical DML {@link FunctionOp} input that carries one exact logical
	 * caller-argument/formal binding. Matrix and frame arguments additionally require the frozen
	 * compiled-input fact used by placement transfers. Scalar/control arguments are
	 * validated against the concrete FunctionOp input itself because the compiled-input
	 * index intentionally excludes non-data operands and no placement transfer exists for them.
	 */
	public CompiledHopKey requireExactPhysicalFunctionInputConsumer(LogicalFunctionInputFact supplied) {
		LogicalFunctionInputFact fact = requireExactLogicalFunctionInput(supplied);
		List<Constraint> owners = graph.constraints().stream().filter(constraint ->
			constraint.kind() == ConstraintKind.DOMINATES
				&& constraint.right() == fact.boundary()
				&& constraint.inputPosition() == fact.callInputPosition()
				&& "function-callsite-control".equals(constraint.evidence())).toList();
		if(owners.size() != 1)
			throw new IllegalArgumentException(
				"Logical function input has no unique physical call-site authority");
		CompiledHopKey consumer = owners.get(0).left();
		if(!isDmlFunctionCallBoundary(consumer))
			throw new IllegalArgumentException(
				"Logical function input consumer is not a compiled DML FunctionOp");
		Hop sourceHop = hopsByKey.get(fact.sourceArgument());
		Hop consumerHop = hopsByKey.get(consumer);
		if(sourceHop == null || !(consumerHop instanceof org.apache.sysds.hops.FunctionOp)
			|| fact.callInputPosition() < 0 || fact.callInputPosition() >= consumerHop.getInput().size()
			|| consumerHop.getInput(fact.callInputPosition()) != sourceHop)
			throw new IllegalArgumentException(
				"Logical function input does not match the exact physical FunctionOp operand");
		NodeShapeFact sourceShape = shapeFacts.shapeFact(fact.sourceArgument()).orElseThrow(() ->
			new IllegalArgumentException("Logical function input has no builder-owned shape fact"));
		if(isPlacementDataType(sourceShape.dataType()))
			requireExactCompiledInputEdge(fact.sourceArgument(), consumer, fact.callInputPosition());
		return consumer;
	}

	public CompiledInputEdgeFact requireExactCompiledInputEdge(CompiledHopKey producer,
		CompiledHopKey consumer, int inputPosition) {
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> byConsumer = inputEdgesByIdentity.get(
			Objects.requireNonNull(producer, "producer"));
		Map<Integer,CompiledInputEdgeFact> byPosition = byConsumer == null ? null
			: byConsumer.get(Objects.requireNonNull(consumer, "consumer"));
		CompiledInputEdgeFact fact = byPosition == null ? null : byPosition.get(inputPosition);
		if(fact == null) {
			boolean producerOwned = inputEdgesByIdentity.keySet().stream().anyMatch(key -> key == producer);
			boolean producerValueMatch = inputEdgesByIdentity.keySet().stream().anyMatch(key -> key.equals(producer));
			boolean consumerOwned = byConsumer != null
				&& byConsumer.keySet().stream().anyMatch(key -> key == consumer);
			boolean consumerValueMatch = byConsumer != null
				&& byConsumer.keySet().stream().anyMatch(key -> key.equals(consumer));
			throw new IllegalArgumentException(
				"Exact compiled input edge fact is missing or foreign: producerOwned=" + producerOwned
					+ " producerValueMatch=" + producerValueMatch
					+ " consumerOwned=" + consumerOwned
					+ " consumerValueMatch=" + consumerValueMatch
					+ " inputPosition=" + inputPosition
					+ " producer=" + producer.normalizedSignature()
					+ " consumer=" + consumer.normalizedSignature());
		}
		return fact;
	}

	public void assertProgramOwner(DMLProgram program) {
		if(program == null || program != programOwner)
			throw new IllegalArgumentException("Placement analysis is foreign to the supplied program");
	}

	public void assertCanonicalProgramAuthority(DMLProgram program) {
		assertProgramOwner(program);
		program.requirePlacementAnalysisAuthority(this);
	}
}
