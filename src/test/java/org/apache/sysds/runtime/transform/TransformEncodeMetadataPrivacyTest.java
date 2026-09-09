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

package org.apache.sysds.runtime.transform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.frame.data.FrameBlock;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.transform.encode.EncoderFactory;
import org.apache.sysds.runtime.transform.encode.MultiColumnEncoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TransformEncodeMetadataPrivacyTest {
	private String previousProperty;

	@Before
	public void rememberProperty() {
		previousProperty = System.getProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
	}

	@After
	public void restoreProperty() {
		if(previousProperty == null)
			System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		else
			System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, previousProperty);
	}

	@Test
	public void releaseIsOffByDefault() {
		String spec = releaseSpec("\"recode\":[1]");
		assertFalse(TransformEncodeMetadataPrivacy.allowsPublicRecodeMetadata(spec));
		assertThrows(DMLRuntimeException.class,
			() -> TransformEncodeMetadataPrivacy.validateReleaseRequest(spec));
		assertThrows(DMLRuntimeException.class,
			() -> TransformEncodeMetadataPrivacy.validateAndStripReleaseRequest(spec));
	}

	@Test
	public void validMetadataOnlyReleaseRequiresDeploymentOptIn() {
		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		assertTrue(TransformEncodeMetadataPrivacy.allowsPublicRecodeMetadata(
			releaseSpec("\"recode\":[1,2],\"dummycode\":[2]")));
		assertTrue(TransformEncodeMetadataPrivacy.allowsPublicRecodeMetadata(
			releaseSpec("\"dummycode\":[3]")));
		TransformEncodeMetadataPrivacy.validateReleaseRequest(releaseSpec("\"recode\":[1]"));
	}

	@Test
	public void otherEncodersAndPrivacyDeclarationsAreDenied() {
		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		assertDenied(releaseSpec("\"recode\":[1],\"bin\":[1]"));
		assertDenied(releaseSpec("\"recode\":[1],\"impute\":[1]"));
		assertDenied(releaseSpec("\"recode\":[1],\"omit\":[1]"));
		assertDenied(releaseSpec("\"recode\":[1],\"privacy\":\"private\""));
		assertDenied(releaseSpec("\"recode\":[1],\"udf\":{}"));
	}

	@Test
	public void releaseRequiresIdsAndAtLeastOneSupportedEncoder() {
		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		assertDenied("{\"recode\":[1],\"cofeePublicRecodeMetadata\":true}");
		assertDenied("{\"ids\":false,\"recode\":[1],\"cofeePublicRecodeMetadata\":true}");
		assertDenied("{\"ids\":true,\"cofeePublicRecodeMetadata\":true}");
	}

	@Test
	public void columnIndicesMustBeNonemptyPositiveJsonIntegers() {
		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		assertDenied(releaseSpec("\"recode\":[]"));
		assertDenied(releaseSpec("\"recode\":[0]"));
		assertDenied(releaseSpec("\"recode\":[-1]"));
		assertDenied(releaseSpec("\"recode\":[2147483648]"));
		assertDenied(releaseSpec("\"recode\":[1.0]"));
		assertDenied(releaseSpec("\"recode\":[\"1\"]"));
		assertDenied(releaseSpec("\"recode\":1"));
	}

	@Test
	public void malformedAndUnknownSpecsFailClosedWithoutChangingOrdinaryValidation() {
		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		assertFalse(TransformEncodeMetadataPrivacy.allowsPublicRecodeMetadata("not json"));
		assertFalse(TransformEncodeMetadataPrivacy.allowsPublicRecodeMetadata(null));

		// No explicit true release request: existing transform validation remains authoritative.
		TransformEncodeMetadataPrivacy.validateReleaseRequest("not json");
		String ordinarySpec = "{\"ids\":true,\"bin\":[1]}";
		assertEquals(ordinarySpec, TransformEncodeMetadataPrivacy.validateAndStripReleaseRequest(ordinarySpec));
		String disabledDeclaration =
			"{\"ids\":true,\"recode\":[1],\"cofeePublicRecodeMetadata\":false}";
		assertEquals(disabledDeclaration,
			TransformEncodeMetadataPrivacy.validateAndStripReleaseRequest(disabledDeclaration));
	}

	@Test
	public void encoderFactoryIgnoresAuthorizedReleaseDeclaration() {
		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		String ordinarySpec = "{\"ids\":true,\"recode\":[1],\"dummycode\":[1]}";
		String publicSpec = releaseSpec("\"recode\":[1],\"dummycode\":[1]");
		String normalizedSpec = TransformEncodeMetadataPrivacy.validateAndStripReleaseRequest(publicSpec);
		assertFalse(normalizedSpec.contains(TransformEncodeMetadataPrivacy.RELEASE_SPEC_KEY));

		FrameBlock input = new FrameBlock(new ValueType[] {ValueType.STRING}, new String[] {"C1"},
			new String[][] {{"alpha"}, {"beta"}, {"alpha"}});
		assertThrows(DMLRuntimeException.class,
			() -> EncoderFactory.createEncoder(publicSpec, input.getColumnNames(), 1, null));
		MultiColumnEncoder ordinary = EncoderFactory.createEncoder(ordinarySpec, input.getColumnNames(), 1, null);
		MultiColumnEncoder released = EncoderFactory.createEncoder(normalizedSpec, input.getColumnNames(), 1, null);
		MatrixBlock ordinaryData = ordinary.encode(input, 1);
		MatrixBlock releasedData = released.encode(input, 1);
		FrameBlock ordinaryMeta = ordinary.getMetaData(new FrameBlock(1, ValueType.STRING), 1);
		FrameBlock releasedMeta = released.getMetaData(new FrameBlock(1, ValueType.STRING), 1);

		assertEquals(ordinaryData.getNumRows(), releasedData.getNumRows());
		assertEquals(ordinaryData.getNumColumns(), releasedData.getNumColumns());
		assertEquals(ordinaryMeta.getNumRows(), releasedMeta.getNumRows());
		assertEquals(ordinaryMeta.getNumColumns(), releasedMeta.getNumColumns());
		for(int row = 0; row < ordinaryMeta.getNumRows(); row++)
			assertEquals(ordinaryMeta.get(row, 0), releasedMeta.get(row, 0));
	}

	private static void assertDenied(String spec) {
		assertFalse(TransformEncodeMetadataPrivacy.allowsPublicRecodeMetadata(spec));
		assertThrows(DMLRuntimeException.class,
			() -> TransformEncodeMetadataPrivacy.validateReleaseRequest(spec));
	}

	private static String releaseSpec(String encoders) {
		return "{\"ids\":true," + encoders + ",\"cofeePublicRecodeMetadata\":true}";
	}
}
