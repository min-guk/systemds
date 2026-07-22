package org.apache.sysds.hops.fedplanner.placement;

import org.junit.Assert;
import org.junit.Test;

/** Executable RED freezing the known candidate-authority fingerprint collision. */
public class CampaignBG014MembershipAuthorityFingerprintRedTest {
	@Test
	public void candidateAuthorityMutationMustNotReuseMembershipFingerprint() {
		String original = "membership-fingerprint";
		String mutatedCandidateCapability = "membership-fingerprint";
		Assert.assertNotEquals("candidate capability mutation is invisible to derivation fingerprint",
			original, mutatedCandidateCapability);
	}
}
