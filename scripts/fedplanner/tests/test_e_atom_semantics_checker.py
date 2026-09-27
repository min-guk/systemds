import copy
import gzip
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from diagnose_e_physical_forest import translate_physical_forest
from e_atom_semantics_checker import (certify_atom_semantics,
                                        verify_atom_semantics_certificate)
from test_e_target_preimage import REAL_P2_MODEL, model


class EAtomSemanticsCheckerTest(unittest.TestCase):
    def write_model(self, root, source):
        root.mkdir(parents=True, exist_ok=True)
        path = root / "model.json.gz"
        with gzip.open(path, "wt", encoding="utf-8") as stream:
            json.dump(source, stream, sort_keys=True)
        return path

    def test_complete_certificate_replays_hard_and_local_atom_factors(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_model(Path(directory), model())
            translated = translate_physical_forest(path)
            certificate = certify_atom_semantics(path, translated)
            verification = verify_atom_semantics_certificate(
                certificate, path, translated)
        self.assertEqual("COMPLETE", certificate["status"])
        self.assertEqual([], certificate["blockers"])
        self.assertEqual("PASS", verification["status"])
        self.assertGreater(certificate["proof"]["semanticCellsChecked"], 0)
        self.assertEqual(len(translated["atomDictionary"]),
                         certificate["proof"]["atomCount"])

    def test_atom_truth_and_hard_truth_tampering_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_model(Path(directory), model())
            translated = translate_physical_forest(path)
            atom_tampered = copy.deepcopy(translated)
            atom = next(row for row in atom_tampered["allFactors"]
                        if row["name"].startswith("atom-equivalence:"))
            atom["truth"] = tuple([not atom["truth"][0]] +
                                  list(atom["truth"][1:]))
            with self.assertRaisesRegex(ValueError,
                                        "atom equivalence truth differs"):
                certify_atom_semantics(path, atom_tampered)
            hard_tampered = copy.deepcopy(translated)
            hard_tampered["factors"][0]["truth"] = tuple(
                [not hard_tampered["factors"][0]["truth"][0]] +
                list(hard_tampered["factors"][0]["truth"][1:]))
            with self.assertRaisesRegex(ValueError,
                                        "hard factors differ"):
                certify_atom_semantics(path, hard_tampered)
            all_hard_tampered = copy.deepcopy(translated)
            all_rows = list(all_hard_tampered["allFactors"])
            hard_index = next(index for index, row in enumerate(all_rows)
                              if row["name"].startswith("e-factor:"))
            all_hard = copy.deepcopy(all_rows[hard_index])
            all_hard["truth"] = tuple(
                [not all_hard["truth"][0]] + list(all_hard["truth"][1:]))
            all_rows[hard_index] = all_hard
            all_hard_tampered["allFactors"] = tuple(all_rows)
            with self.assertRaisesRegex(
                    ValueError, "allFactors hard payload differs"):
                certify_atom_semantics(path, all_hard_tampered)

    def test_phi_unknown_and_budget_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = model()
            path = self.write_model(root / "ordinary", source)
            translated = translate_physical_forest(path)
            with patch("e_atom_semantics_checker._projection_emissions") as emit:
                limited = certify_atom_semantics(
                    path, translated, max_semantic_cells=1)
                emit.assert_not_called()

            unknown_source = copy.deepcopy(source)
            unknown_source["factors"][0]["truth"][0] = "UNKNOWN"
            unknown_path = self.write_model(root / "unknown", unknown_source)
            unknown_translated = translate_physical_forest(unknown_path)
            unknown = certify_atom_semantics(
                unknown_path, unknown_translated)

            phi_source = copy.deepcopy(source)
            template = phi_source["physicalProjection"]["variables"][1][
                "alternatives"][0]["bindings"][0]
            template["mode"] = "PHI"
            template.pop("producerDomain")
            template["producerAlternatives"] = [
                {"producerDomain": 0, "controlArm": "then"}]
            phi_path = self.write_model(root / "phi", phi_source)
            # The producer translator rejects PHI only at the later target
            # layer, so construct from the otherwise valid translation input.
            phi_translated = translate_physical_forest(phi_path)
            phi = certify_atom_semantics(phi_path, phi_translated)

        self.assertEqual("INCOMPLETE", limited["status"])
        self.assertIn("ATOM_SEMANTICS_LOCAL_EMISSION_BUDGET_EXHAUSTED",
                      limited["blockers"])
        self.assertEqual("INCOMPLETE", unknown["status"])
        self.assertIn("SOURCE_UNKNOWN_NOT_DEFINITELY_ALLOWED",
                      unknown["blockers"])
        self.assertEqual("INCOMPLETE", phi["status"])
        self.assertIn("PHI_ATOM_SEMANTICS_UNSUPPORTED", phi["blockers"])

    def test_resigned_certificate_mutation_does_not_regenerate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_model(Path(directory), model())
            translated = translate_physical_forest(path)
            certificate = certify_atom_semantics(path, translated)
            certificate["proof"]["atomCount"] += 1
            from e_atom_semantics_checker import _digest
            certificate["certificateSha256"] = _digest({
                key: value for key, value in certificate.items()
                if key != "certificateSha256"})
            with self.assertRaisesRegex(ValueError, "does not regenerate"):
                verify_atom_semantics_certificate(
                    certificate, path, translated)

    def test_codec_geometry_identity_with_divergent_payload_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            source = model()
            left = source["physicalProjection"]["variables"][0][
                "alternatives"][0]
            right = source["physicalProjection"]["variables"][0][
                "alternatives"][1]
            left["geometry"] = [copy.deepcopy(left["geometry"][0])]
            right["geometry"] = [copy.deepcopy(left["geometry"][0])]
            right["geometry"][0]["blocksize"] += 1
            path = self.write_model(Path(directory), source)
            translated = translate_physical_forest(path)
            certificate = certify_atom_semantics(path, translated)
        self.assertEqual("INCOMPLETE", certificate["status"])
        self.assertEqual(["AMBIGUOUS_GEOMETRY_IDENTITY"],
                         certificate["blockers"])

    def test_self_domain_binding_uses_only_selected_producer_alternative(self):
        with tempfile.TemporaryDirectory() as directory:
            source = model()
            variable = source["physicalProjection"]["variables"][1]
            for fragment in variable["alternatives"]:
                fragment["bindings"][0]["producerDomain"] = 1
            path = self.write_model(Path(directory), source)
            translated = translate_physical_forest(path)
            certificate = certify_atom_semantics(path, translated)
        self.assertEqual("COMPLETE", certificate["status"])
        self.assertLessEqual(certificate["proof"]["localConditionCount"],
                             certificate["proof"]["localEmissionUpperBound"])

    @unittest.skipUnless(REAL_P2_MODEL.is_file(), "real P2 model unavailable")
    def test_real_p2_local_certificate_is_complete(self):
        translated = translate_physical_forest(REAL_P2_MODEL)
        certificate = certify_atom_semantics(REAL_P2_MODEL, translated)
        proof = certificate["proof"]
        self.assertEqual("COMPLETE", certificate["status"])
        self.assertEqual(144, proof["nativeDomainCount"])
        self.assertEqual(37, proof["varyingNativeDomainCount"])
        self.assertEqual(639, proof["atomCount"])
        self.assertEqual(6516, proof["semanticCellsChecked"])
        self.assertEqual({"nodeOwners": 144, "authorityIdentities": 152,
                          "actionIdentities": 9,
                          "geometryIdentities": 87,
                          "relocationTemplates": 24}, proof["assembly"])


if __name__ == "__main__":
    unittest.main()
