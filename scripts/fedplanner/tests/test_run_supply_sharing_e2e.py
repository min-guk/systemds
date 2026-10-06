import copy
import importlib.util
from pathlib import Path
import unittest
import tempfile


MODULE = Path(__file__).resolve().parents[1] / "run_supply_sharing_e2e.py"
SPEC = importlib.util.spec_from_file_location("run_supply_sharing_e2e", MODULE)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class SupplySharingE2ETest(unittest.TestCase):
    def test_worker_cache_budget_does_not_change_coordinator_configuration(self):
        spec = {"rows": 2, "inner": 2, "width": 2, "copies": 1, "iterations": 3,
                "kinds": ["invariant"], "planners": ["local"], "workerBufferPercent": 1}
        with tempfile.TemporaryDirectory() as folder:
            stage = Path(folder)
            runner.prepare_inputs(stage, spec)
            case = stage / "cases/invariant-local"
            self.assertNotIn("bufferpoollimit", (case / "config.xml").read_text())
            for port in (13001, 13002):
                config = (case / f"worker-{port}-config.xml").read_text()
                self.assertIn("<sysds.caching.bufferpoollimit>1</sysds.caching.bufferpoollimit>", config)
                self.assertIn(f"/worker-{port}/tmp</sysds.localtmpdir>", config)
                self.assertNotIn(f"/{case.name}/tmp</sysds.localtmpdir>", config)
                self.assertIn(f"/worker-{port}/scratch</sysds.scratch>", config)
                self.assertNotIn(f"/{case.name}/scratch</sysds.scratch>", config)

    def test_spill_gate_requires_target_restore_not_only_write_or_source_io(self):
        spec = {"workerCache": "static", "requireWorkerSpill": True}
        observations = {"workerObservations": [
            {"address": "localhost/127.0.0.1:13001", "cachingActive": True, "fsWrites": 5, "fsHits": 5,
             "cacheManager": "STATIC", "unifiedMemoryManagerEnabled": False,
             "cachePath": "/test/worker-13001/tmp/cache/"},
            {"address": "localhost/127.0.0.1:13002", "cachingActive": True, "fsWrites": 5, "fsHits": 0,
             "cacheManager": "STATIC", "unifiedMemoryManagerEnabled": False,
             "cachePath": "/test/worker-13002/tmp/cache/"}]}
        self.assertFalse(runner.worker_cache_gate(observations, spec)["passed"])
        observations["workerObservations"][1]["fsHits"] = 2
        self.assertTrue(runner.worker_cache_gate(observations, spec)["passed"])
        observations["workerObservations"][1]["cacheManager"] = "UNIFIED"
        self.assertFalse(runner.worker_cache_gate(observations, spec)["passed"])
        observations["workerObservations"][1]["cacheManager"] = "STATIC"
        observations["workerObservations"][1]["cachePath"] = "/test/worker-13001/tmp/cache/"
        self.assertFalse(runner.worker_cache_gate(observations, spec)["passed"])
        observations["workerObservations"][1]["cachePath"] = "/test/worker-13002/tmp/cache/"
        observations["workerObservations"][1]["cachingActive"] = False
        self.assertFalse(runner.worker_cache_gate(observations, spec)["passed"])

    def test_cache_gate_rejects_missing_worker_or_wrong_cache_profile(self):
        observations = {"workerObservations": [
            {"address": "localhost:13001", "cachingActive": False},
            {"address": "localhost:13002", "cachingActive": False}]}
        self.assertTrue(runner.worker_cache_gate(observations, {})["passed"])
        observations["workerObservations"][1]["address"] = "localhost:13001"
        self.assertFalse(runner.worker_cache_gate(observations, {})["passed"])
        observations["workerObservations"][1]["address"] = "localhost:13002"
        self.assertFalse(runner.worker_cache_gate(observations, {"workerCache": "static"})["passed"])
        observations["workerObservations"].pop()
        self.assertFalse(runner.worker_cache_gate(observations, {})["passed"])

    def probe(self, updated=False, uses_per_version=1):
        shared = not updated or uses_per_version > 1
        group = "shared-group" if shared else ""
        layout = "layout-digest"
        if updated:
            events = []
            for version in range(3):
                for use in range(uses_per_version):
                    events.append({"nanoTime": 100 + version * 100 + use * 20,
                                   "staged": True, "created": use == 0,
                                   "sourceUniqueId": 42 + version,
                                   "publishedMapId": 100 + version * uses_per_version + use,
                                   "sourceVersion": 0, "groupDigest": group, "layoutDigest": layout})
        else:
            events = [{"nanoTime": 40 + index * 20, "staged": True, "created": index == 0,
                       "sourceUniqueId": 42, "publishedMapId": 100 + index,
                       "sourceVersion": 0, "groupDigest": group, "layoutDigest": layout}
                      for index in range(3)]
        lifecycle = []
        if updated and uses_per_version == 1:
            for index, event in enumerate(events):
                lifecycle += [self.lifecycle(event, "CREATION_ATTEMPT", "SINGLE_USE", -1,
                                             event["nanoTime"] - 2),
                              self.lifecycle(event, "CREATION_SUCCESS", "SINGLE_USE",
                                             event["publishedMapId"], event["nanoTime"] - 1)]
        elif updated:
            for version in range(3):
                version_events = events[version * uses_per_version:(version + 1) * uses_per_version]
                canonical = 900 + version
                lifecycle += [self.lifecycle(version_events[0], "CREATION_ATTEMPT", "PLANNED", -1,
                                              version_events[0]["nanoTime"] - 3),
                              self.lifecycle(version_events[0], "CREATION_SUCCESS", "PLANNED", canonical,
                                              version_events[0]["nanoTime"] - 2),
                              self.lifecycle(version_events[0], "RETAINED", "PLANNED", canonical,
                                              version_events[0]["nanoTime"] - 1)]
                for use, event in enumerate(version_events):
                    if use:
                        lifecycle.append(self.lifecycle(event, "HIT", "PLANNED", canonical,
                                                        event["nanoTime"] - 2))
                    lifecycle.append(self.lifecycle(event, "ALIAS", "PLANNED", canonical,
                                                    event["nanoTime"] - 1))
                lifecycle += [self.lifecycle(version_events[-1], "RETIREMENT", "PLANNED", canonical,
                                              version_events[-1]["nanoTime"] + 1),
                              self.lifecycle(version_events[-1], "CLEANUP", "PLANNED", canonical,
                                              version_events[-1]["nanoTime"] + 2,
                                              reason="WORKER_RESET", cleanup=True)]
        else:
            lifecycle = [self.lifecycle(events[0], "CREATION_ATTEMPT", "PLANNED", -1, 10),
                         self.lifecycle(events[0], "CREATION_SUCCESS", "PLANNED", 9, 20),
                         self.lifecycle(events[0], "RETAINED", "PLANNED", 9, 21),
                         self.lifecycle(events[0], "ALIAS", "PLANNED", 9, 30),
                         self.lifecycle(events[0], "HIT", "PLANNED", 9, 50),
                         self.lifecycle(events[0], "ALIAS", "PLANNED", 9, 51),
                         self.lifecycle(events[0], "HIT", "PLANNED", 9, 70),
                         self.lifecycle(events[0], "ALIAS", "PLANNED", 9, 71),
                         self.lifecycle(events[0], "RETIREMENT", "PLANNED", 9, 90),
                         self.lifecycle(events[0], "CLEANUP", "PLANNED", 9, 91,
                                        reason="WORKER_RESET", cleanup=None)]
        return {"refedReuseAudit": {"enabled": True, "droppedEvents": 0, "loggingFailures": 0,
                                     "events": lifecycle},
                "runtimeFallbackCount": 0, "runtimeRepairCount": 0,
                "canonicalProof": {"objectiveMatches": True, "sharedLifetimesMatch": True},
                "selectedRelocations": [{"physicalEmissionIdentity": "supply-S0",
                    "sourceLexicalVariable": "S0", "runtimeSupplyEvents": events,
                    "expectedSharingGroupDigest": group, "sharedAcrossExecutions": shared,
                    "materializationFType": "FULL"}]}

    @staticmethod
    def lifecycle(supply, event, mode, canonical, nano, reason=None, cleanup=None):
        return {"nanoTime": nano, "event": event, "mode": mode,
                "sourceUniqueId": supply["sourceUniqueId"], "sourceVersion": supply["sourceVersion"],
                "groupDigest": supply["groupDigest"], "layoutDigest": supply["layoutDigest"],
                "outType": "FULL", "canonicalRemoteId": canonical,
                "reason": reason, "cleanupSuccess": cleanup}

    def test_requires_actual_staged_supply_execution(self):
        probe = self.probe()
        self.assertTrue(runner.sharing_gate(probe, "invariant", 1, 3)["passed"])
        probe["selectedRelocations"][0]["runtimeSupplyEvents"] = []
        self.assertFalse(runner.sharing_gate(probe, "invariant", 1, 3)["passed"])

    def test_rejects_repeated_creation_of_invariant_value(self):
        probe = self.probe()
        probe["selectedRelocations"][0]["runtimeSupplyEvents"][1]["created"] = True
        self.assertFalse(runner.sharing_gate(probe, "invariant", 1, 3)["passed"])

    def test_updated_requires_all_versions_and_no_cross_version_share(self):
        probe = self.probe(updated=True)
        self.assertTrue(runner.sharing_gate(probe, "updated", 1, 3)["passed"])
        probe["selectedRelocations"][0]["runtimeSupplyEvents"][2]["sourceUniqueId"] = 42
        self.assertFalse(runner.sharing_gate(probe, "updated", 1, 3)["passed"])

    def test_rejects_missing_or_inconsistent_planned_lifecycle(self):
        mutations = (
            lambda p: p["refedReuseAudit"]["events"].__setitem__(2,
                {**p["refedReuseAudit"]["events"][2], "event": "HIT"}),
            lambda p: p["refedReuseAudit"]["events"][4].update(canonicalRemoteId=999),
            lambda p: p["refedReuseAudit"]["events"][4].update(layoutDigest="wrong"),
            lambda p: p["refedReuseAudit"]["events"][4].update(outType="ROW"),
            lambda p: p["refedReuseAudit"]["events"][4].update(mode="LEGACY"),
        )
        for mutation in mutations:
            probe = self.probe()
            mutation(probe)
            self.assertFalse(runner.sharing_gate(probe, "invariant", 1, 3)["passed"])

    def test_updated_requires_single_use_lifecycle_without_retention(self):
        probe = self.probe(updated=True)
        self.assertTrue(runner.sharing_gate(probe, "updated", 1, 3)["passed"])
        probe["refedReuseAudit"]["events"].append(
            self.lifecycle(probe["selectedRelocations"][0]["runtimeSupplyEvents"][0],
                           "RETAINED", "SINGLE_USE", 100, 39))
        self.assertFalse(runner.sharing_gate(probe, "updated", 1, 3)["passed"])

    def test_flat_checks_real_shared_supply_and_records_local_optimum_gap(self):
        probe = self.probe(updated=True)
        probe["selectedRelocations"][0]["expectedStaged"] = True
        probe["flatUpdatedDiagnostic"] = {
            "status": "checked", "selectedHardCost": 0, "bestForcedHardCost": 0,
            "selectedCommonRelocation": True, "cheaperLegalAlternativeFound": True,
            "selectedCommonPhysicalEmissionIdentity": probe["selectedRelocations"][0]["physicalEmissionIdentity"],
            "selectedCost": 61.5, "bestForcedCost": 60.5}
        self.assertTrue(runner.flat_plan_gate(probe, "local", 3)["passed"])
        self.assertFalse(runner.flat_plan_gate(probe, "global", 3)["passed"])
        probe["flatUpdatedDiagnostic"]["cheaperLegalAlternativeFound"] = False
        self.assertTrue(runner.flat_plan_gate(probe, "global", 3)["passed"])
        probe["flatUpdatedDiagnostic"]["selectedCommonRelocation"] = False
        self.assertFalse(runner.flat_plan_gate(probe, "local", 3)["passed"])
        probe["flatUpdatedDiagnostic"]["selectedCommonRelocation"] = True
        identity = probe["flatUpdatedDiagnostic"].pop("selectedCommonPhysicalEmissionIdentity")
        self.assertFalse(runner.flat_plan_gate(probe, "local", 3)["passed"])
        probe["flatUpdatedDiagnostic"]["selectedCommonPhysicalEmissionIdentity"] = "another-copy"
        self.assertFalse(runner.flat_plan_gate(probe, "local", 3)["passed"])
        probe["flatUpdatedDiagnostic"]["selectedCommonPhysicalEmissionIdentity"] = identity
        probe["selectedRelocations"][0]["runtimeSupplyEvents"].pop()
        self.assertFalse(runner.flat_plan_gate(probe, "local", 3)["passed"])

    def test_updated_reuses_only_within_each_version(self):
        probe = self.probe(updated=True, uses_per_version=2)
        result = runner.sharing_gate(probe, "updated", 1, 3, 2)
        self.assertTrue(result["passed"])
        action = result["actions"][0]
        self.assertEqual(6, action["executions"])
        self.assertEqual(3, action["creations"])
        self.assertEqual(3, len(action["canonicalIds"]))
        self.assertEqual(3, action["lifecycleCounts"]["HIT"])
        self.assertEqual(6, action["lifecycleCounts"]["ALIAS"])
        self.assertEqual(3, action["lifecycleCounts"]["RETAINED"])

    def test_phi_checks_each_selected_layout_without_requiring_fout_source(self):
        probe = self.probe(updated=True, uses_per_version=3)
        first = probe["selectedRelocations"][0]
        first["expectedStaged"] = False
        for event in first["runtimeSupplyEvents"]:
            event["staged"] = False
        second = copy.deepcopy(first)
        second["physicalEmissionIdentity"] = "second-target"
        second["expectedSharingGroupDigest"] = "second-group"
        for event in second["runtimeSupplyEvents"]:
            event.update(groupDigest="second-group", layoutDigest="second-layout")
            event["publishedMapId"] += 1000
        second_lifecycle = copy.deepcopy(probe["refedReuseAudit"]["events"])
        for event in second_lifecycle:
            event.update(groupDigest="second-group", layoutDigest="second-layout")
            if event["canonicalRemoteId"] >= 0:
                event["canonicalRemoteId"] += 1000
        probe["selectedRelocations"].append(second)
        probe["refedReuseAudit"]["events"] += second_lifecycle
        self.assertTrue(runner.sharing_gate(probe, "phi", 1, 3, 3)["passed"])
        for event in second_lifecycle:
            if event["canonicalRemoteId"] >= 0:
                event["canonicalRemoteId"] -= 1000
        self.assertFalse(runner.sharing_gate(probe, "phi", 1, 3, 3)["passed"])

    def test_updated_multiuse_rejects_invalid_cross_version_or_lifecycle_sharing(self):
        def second_supply(probe):
            return probe["selectedRelocations"][0]["runtimeSupplyEvents"][1]

        def clear_group(probe):
            probe["selectedRelocations"][0]["expectedSharingGroupDigest"] = ""
            for event in probe["selectedRelocations"][0]["runtimeSupplyEvents"]:
                event["groupDigest"] = ""
            for event in probe["refedReuseAudit"]["events"]:
                event["groupDigest"] = ""

        def reuse_canonical_across_versions(probe):
            for event in probe["refedReuseAudit"]["events"]:
                if event["sourceUniqueId"] == 43 and event["event"] != "CREATION_ATTEMPT":
                    event["canonicalRemoteId"] = 900

        mutations = (
            lambda p: second_supply(p).update(created=True),
            lambda p: p["refedReuseAudit"]["events"].remove(next(
                event for event in p["refedReuseAudit"]["events"]
                if event["event"] == "HIT")),
            reuse_canonical_across_versions,
            lambda p: next(event for event in p["refedReuseAudit"]["events"]
                           if event["event"] == "RETIREMENT").update(nanoTime=0),
            lambda p: second_supply(p).update(sourceUniqueId=999),
            lambda p: p["selectedRelocations"][0].update(sharedAcrossExecutions=False),
            clear_group,
        )
        for mutation in mutations:
            probe = self.probe(updated=True, uses_per_version=2)
            mutation(probe)
            self.assertFalse(runner.sharing_gate(probe, "updated", 1, 3, 2)["passed"])

    def test_rejects_lost_group_cost_mismatch_or_incomplete_audit(self):
        for mutation in (lambda p: p["refedReuseAudit"].update(droppedEvents=1),
                         lambda p: p["refedReuseAudit"].update(loggingFailures=1),
                         lambda p: p["canonicalProof"].update(objectiveMatches=False),
                         lambda p: p["selectedRelocations"][0]["runtimeSupplyEvents"][0].update(groupDigest="wrong"),
                         lambda p: p.update(runtimeRepairCount=1)):
            probe = self.probe()
            mutation(probe)
            self.assertFalse(runner.sharing_gate(probe, "invariant", 1, 3)["passed"])

    def test_separate_sources_cannot_satisfy_missing_copy(self):
        probe = self.probe()
        self.assertFalse(runner.sharing_gate(probe, "invariant", 2, 3)["passed"])
        second = copy.deepcopy(probe["selectedRelocations"][0])
        second["physicalEmissionIdentity"] = "supply-S1"
        probe["selectedRelocations"].append(second)
        self.assertFalse(runner.sharing_gate(probe, "invariant", 2, 3)["passed"])
        second["sourceLexicalVariable"] = "S1"
        second["expectedSharingGroupDigest"] = "shared-group-2"
        for event in second["runtimeSupplyEvents"]:
            event["nanoTime"] += 100
            event["sourceUniqueId"] += 100
            event["publishedMapId"] += 100
            event["groupDigest"] = "shared-group-2"
        probe["refedReuseAudit"]["events"] += [
            {**event, "nanoTime": event["nanoTime"] + 100, "sourceUniqueId": 142,
             "groupDigest": "shared-group-2", "canonicalRemoteId": 109}
            for event in probe["refedReuseAudit"]["events"]
        ]
        self.assertTrue(runner.sharing_gate(probe, "invariant", 2, 3)["passed"])

    def test_unrelated_shared_supply_does_not_cover_requested_source(self):
        probe = self.probe()
        probe["selectedRelocations"][0]["sourceLexicalVariable"] = "M0"
        self.assertFalse(runner.sharing_gate(probe, "invariant", 1, 3)["passed"])

    def transport_log(self, gets=1, puts=1):
        return "\n".join([
            "[PlannerRuntimeAudit][Lowering-Synthetic] status=MATCH plan=p token=s stage=REFED_STAGED auditKey=k",
            "[PlannerRuntimeAudit][Execution] status=MATCH plan=p auditKey=k count=3",
            f"[PlannerRuntimeAudit][Federated-Dispatch] status=MATCH plan=p parentAuditKey=k requestType=GET_VAR fragmentOpcode=- count={gets}",
            f"[PlannerRuntimeAudit][Federated-Dispatch] status=MATCH plan=p parentAuditKey=k requestType=PUT_VAR fragmentOpcode=MatrixBlock count={puts}",
            "[PlannerRuntimeAudit][Federated-Dispatch] status=MATCH plan=p parentAuditKey=other requestType=GET_VAR fragmentOpcode=- count=17",
            "[PlannerRuntimeAudit][Federated-Dispatch] status=MATCH plan=p parentAuditKey=k requestType=PUT_VAR fragmentOpcode=DoubleObject count=8",
        ])

    def test_transport_requires_real_get_and_put_for_selected_action(self):
        probe = self.probe()
        probe["selectedRelocations"][0]["actionKeyDigest"] = "s"
        for gets, puts, expected in ((1, 1, True), (3, 1, False), (1, 0, False)):
            result = runner.transport_gate(self.transport_log(gets, puts), probe, {"S0"}, 3, 1)
            self.assertEqual(expected, result["passed"])
        self.assertFalse(runner.transport_gate("", probe, {"S0"}, 3, 1)["passed"])

    def test_native_refed_requires_put_without_staging_get(self):
        probe = self.probe()
        action = probe["selectedRelocations"][0]
        action.update(actionKeyDigest="s", expectedStaged=False)
        for event in action["runtimeSupplyEvents"]:
            event["staged"] = False
        log = self.transport_log(0, 1).replace("stage=REFED_STAGED", "stage=REFED")
        self.assertTrue(runner.transport_gate(log, probe, {"S0"}, 3, 1, allow_native=True)["passed"])
        self.assertFalse(runner.transport_gate(log, probe, {"S0"}, 3, 1)["passed"])
        log = self.transport_log(1, 1).replace("stage=REFED_STAGED", "stage=REFED")
        self.assertFalse(runner.transport_gate(log, probe, {"S0"}, 3, 1, allow_native=True)["passed"])

    def test_transport_must_match_observed_creation_count(self):
        probe = self.probe()
        probe["selectedRelocations"][0]["actionKeyDigest"] = "s"
        probe["selectedRelocations"][0]["runtimeSupplyEvents"][1]["created"] = True
        self.assertFalse(runner.transport_gate(self.transport_log(1, 1), probe, {"S0"}, 3, 1)["passed"])

    def test_transport_cumulative_snapshots_are_not_double_counted(self):
        probe = self.probe(updated=True)
        probe["selectedRelocations"][0]["actionKeyDigest"] = "s"
        log = self.transport_log(1, 1) + "\n" + self.transport_log(3, 3)
        self.assertTrue(runner.transport_gate(log, probe, {"S0"}, 3, 3)["passed"])

    def test_cleanup_requires_zero_residency_and_no_failed_cleanup(self):
        audit = {"currentCanonicalCount": 0, "currentEstimatedBytes": 0, "events": []}
        self.assertTrue(runner.cleanup_gate({"refedReuseAudit": audit})["passed"])
        audit["currentCanonicalCount"] = 1
        self.assertFalse(runner.cleanup_gate({"refedReuseAudit": audit})["passed"])
        audit["currentCanonicalCount"] = 0
        audit["events"] = [{"event": "CLEANUP", "cleanupSuccess": False}]
        self.assertFalse(runner.cleanup_gate({"refedReuseAudit": audit})["passed"])

    def test_async_source_cleanup_is_not_a_remote_acknowledgement(self):
        audit = {"currentCanonicalCount": 0, "currentEstimatedBytes": 0,
                 "events": [{"event": "CLEANUP", "cleanupSuccess": True, "reason": "SOURCE_REMOVAL"}]}
        result = runner.cleanup_gate({"refedReuseAudit": audit})
        self.assertTrue(result["passed"])
        self.assertEqual(1, result["asynchronousCleanupDispatchCount"])
        self.assertEqual(0, result["confirmedRemoteCleanupCount"])
        self.assertFalse(result["remoteCleanupConfirmed"])
    def test_worker_reset_is_labeled_logical_only_and_clear_warning_fails(self):
        audit = {"currentCanonicalCount": 0, "currentEstimatedBytes": 0,
                 "events": [{"event": "CLEANUP", "cleanupSuccess": None, "reason": "WORKER_RESET"}]}
        result = runner.cleanup_gate({"refedReuseAudit": audit})
        self.assertTrue(result["passed"])
        self.assertTrue(result["logicalRetirementOnly"])
        self.assertFalse(result["remoteCleanupConfirmed"])
        failed = runner.cleanup_gate({"refedReuseAudit": audit},
                                     "Failed to execute CLEAR request on existing federated sites.")
        self.assertFalse(failed["passed"])
        self.assertTrue(failed["workerClearFailureDetected"])


if __name__ == "__main__":
    unittest.main()
