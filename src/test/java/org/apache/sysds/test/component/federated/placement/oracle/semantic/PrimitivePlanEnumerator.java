/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.component.federated.placement.oracle.semantic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** No pruning: every mixed-radix assignment in [start,end) is visited exactly once. */
public final class PrimitivePlanEnumerator {
	private PrimitivePlanEnumerator() { }

	public record Range(BigInteger start, BigInteger end) {
		public Range {
			Objects.requireNonNull(start);
			Objects.requireNonNull(end);
			if (start.signum() < 0 || end.compareTo(start) < 0)
				throw new IllegalArgumentException("invalid range");
		}
		public BigInteger size() { return end.subtract(start); }
	}

	public static BigInteger size(List<List<PrimitiveChoice>> domains) {
		BigInteger product = BigInteger.ONE;
		for (List<PrimitiveChoice> domain : domains)
			product = product.multiply(BigInteger.valueOf(domain.size()));
		return product;
	}

	public static void enumerate(List<List<PrimitiveChoice>> domains, Range range, Consumer<JointPlan> consumer) {
		Objects.requireNonNull(domains);
		Objects.requireNonNull(range);
		Objects.requireNonNull(consumer);
		BigInteger total = size(domains);
		if (range.end().compareTo(total) > 0)
			throw new IllegalArgumentException("range exceeds product");
		for (BigInteger index = range.start(); index.compareTo(range.end()) < 0; index = index.add(BigInteger.ONE)) {
			BigInteger rest = index;
			List<PrimitiveChoice> choices = new ArrayList<>(domains.size());
			for (List<PrimitiveChoice> domain : domains) {
				BigInteger[] qr = rest.divideAndRemainder(BigInteger.valueOf(domain.size()));
				choices.add(domain.get(qr[1].intValueExact()));
				rest = qr[0];
			}
			consumer.accept(new JointPlan(choices));
		}
	}

	/** Rejects gaps, overlaps, duplicates, and incomplete shard manifests. */
	public static void checkPartition(BigInteger total, List<Range> ranges) {
		List<Range> sorted = new ArrayList<>(ranges);
		sorted.sort(Comparator.comparing(Range::start));
		BigInteger cursor = BigInteger.ZERO;
		for (Range range : sorted) {
			if (!range.start().equals(cursor) || range.size().signum() == 0)
				throw new IllegalArgumentException("range gap, overlap, or empty shard at " + cursor);
			cursor = range.end();
		}
		if (!cursor.equals(total))
			throw new IllegalArgumentException("incomplete range coverage");
	}
}
