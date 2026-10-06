/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-negative integer carrier for costs on a caller-certified common dyadic lattice.
 *
 * <p>The digits are stored as binary64 values so a future dense factor can keep two
 * primitive-double tables. Every digit is an integer in {@code [0, 2^53)}, and all
 * arithmetic first converts the digits to {@code long}; binary64 addition is never
 * used for the carrier.</p>
 */
final class ExactDyadicCosts implements Comparable<ExactDyadicCosts> {
	private static final long WORD_BASE = 1L << 53;
	static final long MAX_DIGIT = WORD_BASE - 1;
	private static final long FRACTION_MASK = (1L << 52) - 1;

	private final double high;
	private final double low;

	private ExactDyadicCosts(long high, long low) {
		this.high = high;
		this.low = low;
	}

	static ExactDyadicCosts ofWords(long high, long low) {
		if(high < 0 || high > MAX_DIGIT || low < 0 || low > MAX_DIGIT)
			throw new IllegalArgumentException("DYADIC_WORD_OUT_OF_RANGE|high=" + high + "|low=" + low);
		return new ExactDyadicCosts(high, low);
	}

	static ExactDyadicCosts fromRawBits(long rawBits, int latticeExponent) {
		double value = Double.longBitsToDouble(rawBits);
		if(!Double.isFinite(value) || value < 0 || rawBits == Double.doubleToRawLongBits(-0.0))
			throw new IllegalArgumentException("INVALID_DYADIC_COST|value=" + value);
		if(latticeExponent < -1074)
			throw new IllegalArgumentException("INVALID_LATTICE_EXPONENT|q=" + latticeExponent);
		if(value == 0)
			return ofWords(0, 0);

		long fraction = rawBits & FRACTION_MASK;
		int exponentBits = (int)((rawBits >>> 52) & 0x7ffL);
		long significand;
		int exponent;
		if(exponentBits == 0) {
			significand = fraction;
			exponent = -1074;
		}
		else {
			significand = (1L << 52) | fraction;
			exponent = exponentBits - 1023 - 52;
		}
		int trailingZeros = Long.numberOfTrailingZeros(significand);
		significand >>>= trailingZeros;
		exponent += trailingZeros;
		int shift = exponent - latticeExponent;
		if(shift < 0)
			throw new IllegalArgumentException("VALUE_OFF_COMMON_LATTICE|q=" + latticeExponent
				+ "|value=" + value);
		int bits = 64 - Long.numberOfLeadingZeros(significand);
		if(shift > 105 || bits + shift > 106)
			throw new IllegalArgumentException("DYADIC_VALUE_EXCEEDS_106_BITS|q=" + latticeExponent
				+ "|value=" + value);
		if(shift >= 53)
			return ofWords(significand << (shift - 53), 0);
		long low = (significand << shift) & MAX_DIGIT;
		long high = shift == 0 ? 0 : significand >>> (53 - shift);
		return ofWords(high, low);
	}

	ExactDyadicCosts add(ExactDyadicCosts that) {
		long lowSum = lowWord() + that.lowWord();
		long highSum = highWord() + that.highWord() + (lowSum >>> 53);
		if(highSum >= WORD_BASE)
			throw new ArithmeticException("DYADIC_106_BIT_OVERFLOW");
		return ofWords(highSum, lowSum & MAX_DIGIT);
	}

	@Override
	public int compareTo(ExactDyadicCosts that) {
		int highComparison = Long.compare(highWord(), that.highWord());
		return highComparison != 0 ? highComparison : Long.compare(lowWord(), that.lowWord());
	}

	long highWord() {
		return (long)high;
	}

	long lowWord() {
		return (long)low;
	}

	double highDigit() {
		return high;
	}

	double lowDigit() {
		return low;
	}

	long toDoubleBits(int latticeExponent) {
		if(latticeExponent < -1074)
			throw new IllegalArgumentException("INVALID_LATTICE_EXPONENT|q=" + latticeExponent);
		int bitLength = bitLength();
		if(bitLength == 0)
			return Double.doubleToRawLongBits(0.0);
		long topExponent = (long)bitLength - 1 + latticeExponent;
		if(topExponent < -1022) {
			int shift = latticeExponent + 1074;
			// A subnormal result has fewer than 53 significant integer bits, so high is zero.
			return lowWord() << shift;
		}

		int discarded = Math.max(0, bitLength - 53);
		long significand;
		if(discarded == 0)
			significand = lowWord() << (53 - bitLength);
		else {
			significand = shiftRightToLong(discarded);
			long guard = (lowWord() >>> (discarded - 1)) & 1L;
			long stickyMask = discarded == 1 ? 0 : (1L << (discarded - 1)) - 1;
			boolean sticky = (lowWord() & stickyMask) != 0;
			if(guard != 0 && (sticky || (significand & 1L) != 0)) {
				significand++;
				if(significand == WORD_BASE) {
					significand = 1L << 52;
					topExponent++;
				}
			}
		}
		if(topExponent > 1023)
			return Double.doubleToRawLongBits(Double.POSITIVE_INFINITY);
		long exponentBits = (topExponent + 1023) << 52;
		return exponentBits | (significand & FRACTION_MASK);
	}

	int bitLength() {
		return highWord() == 0 ? 64 - Long.numberOfLeadingZeros(lowWord())
			: 53 + 64 - Long.numberOfLeadingZeros(highWord());
	}

	private long shiftRightToLong(int shift) {
		return shift == 53 ? highWord()
			: (highWord() << (53 - shift)) | (lowWord() >>> shift);
	}

	/** Numeric eligibility helper for bounded kernel tests; it carries no physical authority. */
	static Certificate certify(int latticeExponent, int maximumBits, int canonicalContributionCount,
		boolean canonicalEncodedMaximumCorrespondence) {
		if(!canonicalEncodedMaximumCorrespondence)
			return Certificate.rejected(latticeExponent, maximumBits, canonicalContributionCount,
				"CANONICAL_ENCODED_MAXIMUM_CORRESPONDENCE_NOT_PROVED");
		if(latticeExponent < -1074)
			return Certificate.rejected(latticeExponent, maximumBits, canonicalContributionCount,
				"LATTICE_EXPONENT_BELOW_BINARY64_MINIMUM");
		if(maximumBits < 0 || maximumBits > 106)
			return Certificate.rejected(latticeExponent, maximumBits, canonicalContributionCount,
				"MAXIMUM_DOES_NOT_FIT_106_BITS");
		if(canonicalContributionCount < 0)
			return Certificate.rejected(latticeExponent, maximumBits, canonicalContributionCount,
				"NEGATIVE_CANONICAL_CONTRIBUTION_COUNT");
		if(canonicalContributionCount == 0 && maximumBits > 0)
			return Certificate.rejected(latticeExponent, maximumBits, canonicalContributionCount,
				"NONZERO_MAXIMUM_WITHOUT_CANONICAL_CONTRIBUTIONS");
		int ceilLog2 = canonicalContributionCount <= 1 ? 0
			: 32 - Integer.numberOfLeadingZeros(canonicalContributionCount - 1);
		int headroom = maximumBits + ceilLog2;
		if(headroom > 106)
			return new Certificate(latticeExponent, maximumBits, canonicalContributionCount, false,
				"INSUFFICIENT_ACCUMULATION_HEADROOM", ceilLog2, headroom);
		if(maximumBits > 53 && (long)maximumBits + latticeExponent > 1023)
			return new Certificate(latticeExponent, maximumBits, canonicalContributionCount, false,
				"BINARY64_OVERFLOW_HEADROOM", ceilLog2, headroom);
		if(maximumBits > 0 && maximumBits <= 53 && (long)maximumBits + latticeExponent > 1024)
			return new Certificate(latticeExponent, maximumBits, canonicalContributionCount, false,
				"BINARY64_FINITE_MAXIMUM", ceilLog2, headroom);
		return new Certificate(latticeExponent, maximumBits, canonicalContributionCount, true,
			"CERTIFIED", ceilLog2, headroom);
	}

	/** Derives physical authority only from the frozen transport owned by the surface. */
	static Certificate certify(ExactPhysicalCostModel.PhysicalCostSurface surface) {
		if(surface == null)
			throw new IllegalArgumentException("EXACT_DYADIC_SURFACE_REQUIRED");
		ExactPhysicalCostModel.FrozenDyadicCostTransport authority = surface.dyadicCostTransport();
		authority.validate(surface);
		List<ExactPhysicalCostModel.ContributionEncoding> encodings = authority.canonical();
		double[] maxima = new double[encodings.size()];
		int q = Integer.MAX_VALUE;
		for(int ordinal = 0; ordinal < encodings.size(); ordinal++) {
			ExactCategoricalSolver.Factor factor = monetaryFactor(encodings.get(ordinal).transport());
			if(factor == null)
				continue;
			int cells = 1;
			for(ExactCategoricalSolver.Variable variable : factor.scope())
				cells = Math.multiplyExact(cells, variable.domainSize());
			for(int cell = 0; cell < cells; cell++) {
				double value;
				try {
					value = factor.denseCostAt(cell);
				}
				catch(IllegalStateException notFrozen) {
					return Certificate.physicalRejected("MONETARY_FACTOR_NOT_FROZEN", authority);
				}
				long bits = Double.doubleToRawLongBits(value);
				if(!Double.isFinite(value) || value < 0
					|| bits == Double.doubleToRawLongBits(-0.0))
					return Certificate.physicalRejected("INVALID_MONETARY_FACTOR_VALUE", authority);
				maxima[ordinal] = Math.max(maxima[ordinal], value);
				if(value > 0)
					q = Math.min(q, dyadicExponent(bits));
			}
		}
		if(q == Integer.MAX_VALUE)
			q = 0;
		List<BigInteger> orderedMaxima = new ArrayList<>(maxima.length);
		for(double value : maxima) {
			long bits = Double.doubleToRawLongBits(value);
			orderedMaxima.add(value == 0 ? BigInteger.ZERO : BigInteger.valueOf(dyadicSignificand(bits))
				.shiftLeft(dyadicExponent(bits) - q));
		}
		// A producer-proved +0 contribution leaves both Neumaier accumulators unchanged.
		// Count potentially positive canonical ordinals, not distinct tables or values in
		// one selected assignment. All cells were validated above; actual canonical
		// evaluation/order and the full transport inventory remain unchanged.
		return certifyOrderedMaxima(q, orderedMaxima).withPhysicalAuthority(authority);
	}

	/**
	 * Numeric-only certificate for ordered nonnegative maxima in common-lattice units.
	 * This helper never supplies physical authority; only the validated frozen transport
	 * above establishes correspondence to canonical contributions.
	 */
	static Certificate certifyOrderedMaxima(int q, List<BigInteger> orderedMaxima) {
		BigInteger maximum = BigInteger.ZERO;
		int positive = 0;
		for(BigInteger value : orderedMaxima) {
			if(value.signum() < 0)
				return Certificate.rejected(q, 0, orderedMaxima.size(), "NEGATIVE_ORDERED_MAXIMUM");
			maximum = maximum.add(value);
			if(value.signum() != 0)
				positive++;
		}
		Certificate coarse = certify(q, maximum.bitLength(), positive, true);
		boolean supported = coarse.supported();
		String reason = coarse.reason();
		long residualUnits = -1;
		if("INSUFFICIENT_ACCUMULATION_HEADROOM".equals(reason)) {
			// The scalar helper checks headroom before the exponent gate. Recheck all
			// base gates independently; the count of one is never the final metadata.
			Certificate base = certify(q, maximum.bitLength(), 1, true);
			if(!base.supported())
				reason = base.reason();
			else {
				BigInteger prefix = BigInteger.ZERO;
				BigInteger residual = BigInteger.ZERO;
				BigInteger exactCorrectionLimit = BigInteger.ONE.shiftLeft(53);
				for(BigInteger value : orderedMaxima) {
					BigInteger mainBound = prefix.add(residual);
					BigInteger additionBound = mainBound.add(value);
					if(additionBound.bitLength() > 53) {
						// Half an ULP of the largest possible binade bounds the exact
						// addition residual. For nonnegative operands it is also no
						// larger than either operand. Zero ordinals add no error.
						BigInteger error = BigInteger.ONE.shiftLeft(additionBound.bitLength() - 54)
							.min(mainBound).min(value);
						residual = residual.add(error);
					}
					prefix = prefix.add(value);
					if(residual.compareTo(exactCorrectionLimit) > 0)
						break;
				}
				// Every signed partial correction is a q-lattice integer of magnitude
				// <=2^53, hence exactly representable. Neumaier's magnitude-ordered
				// residual transform and correction additions are therefore exact.
				// The final canonical addition rounds the exact total once. A bound
				// on the main accumulator may have 107 bits; cost sums still fit106.
				residualUnits = residual.longValueExact();
				if(residual.compareTo(exactCorrectionLimit) <= 0) {
					supported = true;
					reason = "CERTIFIED_ORDERED_RESIDUAL_BOUND";
				}
			}
		}
		return new Certificate(q, maximum.bitLength(), orderedMaxima.size(), positive, supported, reason,
			coarse.ceilLog2AccumulationCount(), coarse.bitsWithAccumulationHeadroom(), residualUnits, null, null);
	}

	/** Numeric only; the order and duplicate table occurrences are significant. */
	static Certificate certifyTables(List<double[]> tables) {
		double[] maxima = new double[tables.size()];
		int q = Integer.MAX_VALUE;
		for(int factor = 0; factor < maxima.length; factor++)
			for(double value : tables.get(factor)) {
				if(value == Double.POSITIVE_INFINITY)
					continue; // An infeasible tuple contributes to no finite plan.
				long bits = Double.doubleToRawLongBits(value);
				if(!Double.isFinite(value) || value < 0 || bits == Long.MIN_VALUE)
					return Certificate.rejected(0, 0, maxima.length, "INVALID_FACTOR_COST");
				maxima[factor] = Math.max(maxima[factor], value);
				if(value > 0)
					q = Math.min(q, dyadicExponent(bits));
			}
		if(q == Integer.MAX_VALUE)
			q = 0;
		List<BigInteger> orderedMaxima = new ArrayList<>(maxima.length);
		for(double value : maxima) {
			long bits = Double.doubleToRawLongBits(value);
			orderedMaxima.add(value == 0 ? BigInteger.ZERO : BigInteger.valueOf(dyadicSignificand(bits))
				.shiftLeft(dyadicExponent(bits) - q));
		}
		return certifyOrderedMaxima(q, orderedMaxima);
	}

	private static ExactCategoricalSolver.Factor monetaryFactor(
		ExactPhysicalCostModel.FrozenCostTransport transport) {
		if(transport instanceof ExactPhysicalCostModel.FrozenCostTransport.Identity identity)
			return identity.factor();
		if(transport instanceof ExactPhysicalCostModel.FrozenCostTransport.OneMonetaryTable one)
			return one.factor();
		return null;
	}

	private static long dyadicSignificand(long bits) {
		long fraction = bits & FRACTION_MASK;
		int exponentBits = (int)((bits >>> 52) & 0x7ffL);
		long significand = exponentBits == 0 ? fraction : (1L << 52) | fraction;
		return significand >>> Long.numberOfTrailingZeros(significand);
	}

	private static int dyadicExponent(long bits) {
		long fraction = bits & FRACTION_MASK;
		int exponentBits = (int)((bits >>> 52) & 0x7ffL);
		long significand = exponentBits == 0 ? fraction : (1L << 52) | fraction;
		int exponent = exponentBits == 0 ? -1074 : exponentBits - 1023 - 52;
		return exponent + Long.numberOfTrailingZeros(significand);
	}

	static final class Certificate {
		private final int q;
		private final int maximumSumBits;
		private final int canonicalContributionCount;
		private final int accumulationContributionCount;
		private final boolean supported;
		private final String reason;
		private final int ceilLog2AccumulationCount;
		private final int bitsWithAccumulationHeadroom;
		private final long maximumResidualUnits;
		private final ExactPhysicalCostModel.FrozenDyadicCostTransport physicalAuthority;
		private final ExactCategoricalSolver.CompiledProblem boundCompiledProblem;

		private Certificate(int q, int maximumSumBits, int canonicalContributionCount,
			boolean supported, String reason, int ceilLog2AccumulationCount,
			int bitsWithAccumulationHeadroom) {
			this(q, maximumSumBits, canonicalContributionCount, canonicalContributionCount, supported, reason,
				ceilLog2AccumulationCount, bitsWithAccumulationHeadroom, -1, null, null);
		}

		private Certificate(int q, int maximumSumBits, int canonicalContributionCount,
			int accumulationContributionCount, boolean supported, String reason, int ceilLog2AccumulationCount,
			int bitsWithAccumulationHeadroom, long maximumResidualUnits,
			ExactPhysicalCostModel.FrozenDyadicCostTransport physicalAuthority,
			ExactCategoricalSolver.CompiledProblem boundCompiledProblem) {
			this.q = q;
			this.maximumSumBits = maximumSumBits;
			this.canonicalContributionCount = canonicalContributionCount;
			this.accumulationContributionCount = accumulationContributionCount;
			this.supported = supported;
			this.reason = reason;
			this.ceilLog2AccumulationCount = ceilLog2AccumulationCount;
			this.bitsWithAccumulationHeadroom = bitsWithAccumulationHeadroom;
			this.maximumResidualUnits = maximumResidualUnits;
			this.physicalAuthority = physicalAuthority;
			this.boundCompiledProblem = boundCompiledProblem;
		}

		int q() { return q; }
		int maximumSumBits() { return maximumSumBits; }
		int canonicalContributionCount() { return canonicalContributionCount; }
		int accumulationContributionCount() { return accumulationContributionCount; }
		boolean supported() { return supported; }
		String reason() { return reason; }
		int ceilLog2AccumulationCount() { return ceilLog2AccumulationCount; }
		int bitsWithAccumulationHeadroom() { return bitsWithAccumulationHeadroom; }
		long maximumResidualUnits() { return maximumResidualUnits; }
		boolean hasPhysicalAuthority() { return physicalAuthority != null; }

		void validateSurface(ExactPhysicalCostModel.PhysicalCostSurface surface) {
			if(physicalAuthority == null)
				throw new IllegalArgumentException("EXACT_DYADIC_PHYSICAL_AUTHORITY_REQUIRED");
			physicalAuthority.validate(surface);
		}

		void validateSourceFactors(List<ExactCategoricalSolver.Factor> factors) {
			if(physicalAuthority == null)
				throw new IllegalArgumentException("EXACT_DYADIC_PHYSICAL_AUTHORITY_REQUIRED");
			physicalAuthority.validateSolverFactors(factors);
		}

		Certificate bindDerived(ExactPhysicalSharedSourceEncoding.Encoding encoding,
			ExactPhysicalReducedSolver.Prepared prepared) {
			if(physicalAuthority == null)
				throw new IllegalArgumentException("EXACT_DYADIC_PHYSICAL_AUTHORITY_REQUIRED");
			if(boundCompiledProblem != null)
				throw new IllegalArgumentException("EXACT_DYADIC_CERTIFICATE_ALREADY_BOUND");
			if(encoding == null || prepared == null)
				throw new IllegalArgumentException("EXACT_DYADIC_DERIVED_PROVENANCE_REQUIRED");
			validateSurface(encoding.sourceSurface());
			validateSourceFactors(encoding.sourceSurface().exactSolverFactors());
			if(!encoding.statistics().transformed())
				throw new IllegalArgumentException("EXACT_DYADIC_ENCODING_NOT_TRANSFORMED");
			prepared.requireSource(encoding.variables(), encoding.factors());
			ExactCategoricalSolver.CompiledProblem compiled = prepared.compiledProblem();
			return new Certificate(q, maximumSumBits, canonicalContributionCount, accumulationContributionCount,
				supported, reason, ceilLog2AccumulationCount, bitsWithAccumulationHeadroom,
				maximumResidualUnits, physicalAuthority, compiled);
		}

		void validateCompiledProblem(ExactCategoricalSolver.CompiledProblem compiled) {
			if(physicalAuthority != null && (boundCompiledProblem == null
				|| boundCompiledProblem != compiled))
				throw new IllegalArgumentException("EXACT_DYADIC_COMPILED_PROVENANCE_MISMATCH");
		}

		private Certificate withPhysicalAuthority(
			ExactPhysicalCostModel.FrozenDyadicCostTransport authority) {
			return new Certificate(q, maximumSumBits, authority.canonical().size(), accumulationContributionCount,
				supported, reason, ceilLog2AccumulationCount, bitsWithAccumulationHeadroom,
				maximumResidualUnits, authority, null);
		}

		private static Certificate physicalRejected(String reason,
			ExactPhysicalCostModel.FrozenDyadicCostTransport authority) {
			return new Certificate(0, 0, authority.canonical().size(), 0, false, reason, 0, 0, -1, authority, null);
		}

		private static Certificate rejected(int q, int maximumSumBits, int canonicalContributionCount,
			String reason) {
			return new Certificate(q, maximumSumBits, canonicalContributionCount, false, reason, 0, 0);
		}
	}
}
