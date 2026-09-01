#!/usr/bin/env python3
import unittest

from validate_forced_campaign_summary import validate


class ForcedCampaignSummaryValidationTest(unittest.TestCase):
	def all_success(self):
		return {"infrastructureStatus": "PASS", "classificationStatus": "ALL_SUCCESS",
			"expectedTargets": 1, "missingResultTargetIds": [],
			"unexpectedResultTargetIds": [], "duplicateResultRows": 0,
			"missingRuntimeCapabilityTargetIds": [],
			"unexpectedRuntimeCapabilityTargetIds": [],
			"resultOutcomes": {"SUCCESS": 1},
			"runtimeCapabilityOutcomes": {"SUCCESS": 2},
			"targetsWithRuntimeCapability": 1}

	def test_authoritative_requires_all_success(self):
		validate(self.all_success())
		with self.assertRaisesRegex(ValueError, "not ALL_SUCCESS"):
			validate({"infrastructureStatus": "PASS", "classificationStatus": "NEEDS_TRIAGE"})
		invalid = self.all_success(); invalid["missingRuntimeCapabilityTargetIds"] = ["t1"]
		with self.assertRaisesRegex(ValueError, "explicit empty"):
			validate(invalid)
		invalid = self.all_success(); invalid["runtimeCapabilityOutcomes"] = {"FAILURE": 1}
		with self.assertRaisesRegex(ValueError, "exclusively successful"):
			validate(invalid)

	def test_diagnostic_override_only_accepts_needs_triage(self):
		validate({"infrastructureStatus": "PASS", "classificationStatus": "NEEDS_TRIAGE"}, True)
		with self.assertRaisesRegex(ValueError, "infrastructureStatus"):
			invalid = self.all_success(); invalid["infrastructureStatus"] = "FAIL"
			validate(invalid, True)


if __name__ == "__main__":
	unittest.main()
