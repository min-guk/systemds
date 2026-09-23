#!/usr/bin/env python3
"""Freeze base/ML10 campaign candidates into reproducible compile inputs.

The output is a capture-ready condition manifest.  It does not claim that P or
E has compiled a condition.  A candidate is ready only when its DML template,
input metadata, privacy, row partitions, worker endpoints, and network cost
binding can all be reconstructed from frozen inputs.
"""

import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import tempfile


BASE_DRIVER = "driver/run_multihost_campaign_network_quality_v2.py"
ML10_DRIVER = "campaign/run_ml10_campaign.py"
PLANNING_COMMON = Path("planning_study/native/input_templates/common")
PLANNING_WORKER = Path("planning_study/native/input_templates")
STAGE_EXPERIMENTS = Path("harness/sigmod2021-exdra-p523/experiments")
TOKEN = re.compile(r"__[A-Z0-9_]+__")
P2_METADATA_RELEASE_OPTION = "-Dsysds.privacy.allowPublicRecodeMetadata=true"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _literal(node):
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) \
            and node.func.id == "frozenset" and len(node.args) == 1 and not node.keywords:
        return frozenset(ast.literal_eval(node.args[0]))
    return ast.literal_eval(node)


def constants(path, names):
    tree = ast.parse(Path(path).read_text(), filename=str(path))
    found = {}
    for statement in tree.body:
        if isinstance(statement, ast.Assign) and len(statement.targets) == 1:
            target = statement.targets[0]
            if isinstance(target, ast.Name) and target.id in names:
                found[target.id] = _literal(statement.value)
    missing = set(names) - set(found)
    if missing:
        raise ValueError(f"constants missing in {path}: {sorted(missing)}")
    return found


def verify_source(path, expected=None):
    path = Path(path)
    if not path.is_file():
        raise ValueError(f"required source absent: {path}")
    actual = file_sha(path)
    if expected is not None and actual != expected:
        raise ValueError(f"required source digest differs: {path}")
    return actual


def load_topology(path, allowed_workers):
    path = Path(path).resolve()
    raw = json.loads(path.read_text())
    workers = raw.get("workers")
    sweep = tuple(raw.get("worker_sweep", ()))
    if not isinstance(workers, list) or sweep != tuple(allowed_workers):
        raise ValueError(f"topology worker sweep differs: {path}")
    if len(workers) < max(sweep):
        raise ValueError(f"topology lacks workers: {path}")
    for index, worker in enumerate(workers, 1):
        if worker.get("index") != index or worker.get("port") != 8000 + index:
            raise ValueError(f"topology worker order/port differs: {path}")
        if not all(isinstance(worker.get(key), str) and worker[key]
                   for key in ("host", "ip")):
            raise ValueError(f"topology worker identity incomplete: {path}")
    return {"path": str(path), "sha256": file_sha(path), "coordinator": raw.get("coordinator"),
            "workerSweep": list(sweep), "workers": workers}


def parse_seal(stage_root):
    stage_root = Path(stage_root).resolve()
    manifest = stage_root / "STAGE_CONTENT.sha256"
    if not manifest.is_file():
        raise ValueError(f"stage content seal absent: {manifest}")
    entries = {}
    for number, line in enumerate(manifest.read_text().splitlines(), 1):
        if not line:
            continue
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not match or match.group(2) in entries:
            raise ValueError(f"invalid stage seal line {number}")
        entries[match.group(2)] = match.group(1)
    return stage_root, {"path": str(manifest), "sha256": file_sha(manifest),
                        "entryCount": len(entries)}, entries


def sealed_file(stage_root, entries, relative):
    relative = Path(relative).as_posix()
    expected = entries.get(relative)
    if expected is None:
        raise ValueError(f"required file absent from stage seal: {relative}")
    path = (stage_root / relative).resolve()
    if not path.is_relative_to(stage_root) or verify_source(path, expected) != expected:
        raise ValueError(f"sealed stage file differs: {relative}")
    return path, expected


def metadata_contract(raw, label):
    required = ("rows", "cols", "nnz", "format", "data_type", "value_type", "privacy")
    if any(key not in raw for key in required):
        raise ValueError(f"metadata fields missing: {label}")
    result = {key: raw[key] for key in raw if key not in ("filename",)}
    if int(result["rows"]) <= 0 or int(result["cols"]) <= 0 or int(result["nnz"]) < 0:
        raise ValueError(f"invalid metadata dimensions: {label}")
    if str(result["format"]).lower() != "binary" or result["data_type"] != "matrix" \
            or result["value_type"] != "double":
        raise ValueError(f"unsupported metadata type: {label}")
    if result["privacy"] not in ("private-aggregate", "public"):
        raise ValueError(f"unsupported privacy: {label}")
    return result


def partition_contract(base, workers, topology_workers, metadata_loader):
    global_meta, global_ref = metadata_loader(base, None)
    rows, cols = int(global_meta["rows"]), int(global_meta["cols"])
    split = (rows + workers - 1) // workers
    partitions = []
    addresses = []
    ranges = []
    for offset in range(workers):
        begin = min(split * offset, rows)
        end = min(split * (offset + 1), rows)
        suffix = "" if workers == 1 else f"_{workers}_{offset + 1}"
        partition_meta, reference = metadata_loader(base, suffix)
        if int(partition_meta["rows"]) != end - begin \
                or int(partition_meta["cols"]) != cols \
                or partition_meta["privacy"] != global_meta["privacy"]:
            raise ValueError(f"partition geometry/privacy differs: {base}{suffix}")
        worker = topology_workers[offset]
        address = f"{worker['ip']}:{worker['port']}/data/{base}{suffix}.data"
        addresses.append(address)
        ranges.extend(([begin, 0], [end, cols]))
        partitions.append({"worker": offset + 1, "begin": [begin, 0],
                           "end": [end, cols], "address": address,
                           "metadata": partition_meta, "metadataRef": reference})
    return {"base": base, "globalMetadata": global_meta, "globalMetadataRef": global_ref,
            "partitioning": "ROW", "addresses": addresses, "ranges": ranges,
            "partitions": partitions}


def dml_list_strings(values):
    return "list(" + ", ".join(json.dumps(value) for value in values) + ")"


def dml_ranges(values):
    return "list(" + ", ".join(f"list({row}, {col})" for row, col in values) + ")"


def render_program(template, inputs, output, rows, cols, seeds):
    x = inputs[0]
    replacements = {
        "__X_ADDRS__": dml_list_strings(x["addresses"]),
        "__X_RANGES__": dml_ranges(x["ranges"]),
        "__OUT__": output,
        "__N__": str(rows),
        "__D__": str(cols),
        "__KMEANS_SEED__": str(seeds["KMEANS_SEED"]),
        "__ALS_SEED__": str(seeds["ALS_SEED"]),
        "__GNMF_SEED__": str(seeds["GNMF_SEED"]),
        "__Y_LOCAL_PATH__": "",
    }
    if len(inputs) == 2:
        replacements.update({"__Y_ADDRS__": dml_list_strings(inputs[1]["addresses"]),
                             "__Y_RANGES__": dml_ranges(inputs[1]["ranges"])})
    text = template
    for token, value in replacements.items():
        text = text.replace(token, value)
    remaining = sorted(set(TOKEN.findall(text)))
    if remaining:
        raise ValueError(f"unresolved DML template tokens: {remaining}")
    return text


def base_workload(discovery):
    fields = discovery.split(":")
    if len(fields) != 3 or fields[0] != "base":
        raise ValueError(f"invalid base discovery ID: {discovery}")
    return fields[2]


def load_catalog(catalog_path, evaluation_root):
    catalog_path = Path(catalog_path).resolve()
    catalog = json.loads(catalog_path.read_text())
    rows = catalog.get("cells")
    if catalog.get("schema") != "closed-comparison-cases-v1" or not isinstance(rows, list):
        raise ValueError("wrong closed comparison catalog schema")
    planning = {}
    for row in rows:
        binding = row.get("sourceBinding") or {}
        if binding.get("kind") != "planning-snapshot" \
                or binding.get("conditionStatus") != "SNAPSHOT_ONLY":
            continue
        key = (row["discoveryId"], row["conditionId"])
        if key in planning:
            raise ValueError(f"duplicate frozen planning condition: {key}")
        planned = binding.get("plannedCondition")
        expected_condition = {"discoveryId": row["discoveryId"],
                              "conditionId": row["conditionId"], **(planned or {})}
        if not isinstance(planned, dict) or digest(expected_condition) != binding.get("conditionSha256"):
            raise ValueError(f"frozen planning condition digest differs: {key}")
        if not row.get("sourceFiles"):
            raise ValueError(f"frozen planning condition has no source binding: {key}")
        for relative, expected in sorted(row.get("sourceFiles", {}).items()):
            verify_source(Path(evaluation_root) / relative, expected)
        planning[key] = row
    return catalog_path, planning


def base_condition(candidate, planning, evaluation_root, topology, network, seeds):
    workload = base_workload(candidate["discoveryId"])
    key = (f"planning-w{candidate['workers']}:{workload}", candidate["networkProfile"])
    row = planning.get(key)
    if row is None:
        return None, ["FROZEN_PLANNING_INPUT_CONTRACT_ABSENT"]
    planned = row["sourceBinding"]["plannedCondition"]
    case = planned["case"]
    protocol_relative = Path("planning_study/native") / f"protocol-w{candidate['workers']}.json"
    protocol_path = Path(evaluation_root) / protocol_relative
    protocol_sha = row.get("sourceFiles", {}).get(protocol_relative.as_posix())
    if protocol_sha is None or verify_source(protocol_path, protocol_sha) != protocol_sha:
        raise ValueError(f"planning protocol is not source-bound: {protocol_relative}")
    protocol = json.loads(protocol_path.read_text())
    options_by_workload = protocol.get("workload_jvm_options")
    if not isinstance(options_by_workload, dict):
        raise ValueError(f"planning protocol lacks workload JVM options: {protocol_relative}")
    workload_options = options_by_workload.get(workload, [])
    if not isinstance(workload_options, list) or any(
            not isinstance(option, str) or not option.startswith("-D") or "=" not in option[2:]
            for option in workload_options):
        raise ValueError(f"planning workload JVM options are malformed: {workload}")
    if len(workload_options) != len(set(workload_options)):
        raise ValueError(f"planning workload JVM options are duplicated: {workload}")
    if workload == "P2_PREP" and workload_options != [P2_METADATA_RELEASE_OPTION]:
        raise ValueError("P2 metadata release differs from frozen planning protocol")
    expected_network = network.get(candidate["networkProfile"])
    if planned["workers"] != candidate["workers"] or expected_network is None:
        raise ValueError(f"base candidate axes differ from planning snapshot: {candidate['candidateId']}")
    expected_cost = network_cost(expected_network)
    planned_cost = planned["network"].get("cost_environment")
    if not isinstance(planned_cost, dict) \
            or {key: expected_cost.get(key) for key in planned_cost} != planned_cost:
        raise ValueError(f"network cost binding differs: {candidate['candidateId']}")
    worker_root = Path(evaluation_root) / PLANNING_WORKER / f"w{candidate['workers']}"
    partition_index = json.loads((worker_root / "metadata/worker-partitions.json").read_text())
    if case["workers"] != candidate["workers"]:
        raise ValueError(f"case worker count differs: {candidate['candidateId']}")

    metadata_by_base = {
        base: metadata_contract(case["metadata"]["X" if offset == 0 else "Y"], base)
        for offset, base in enumerate(case["worker_input_mappings"])
    }

    def load(base, suffix):
        indexed = partition_index["partitions"].get(base)
        if indexed is None:
            raise ValueError(f"base partition absent from frozen index: {base}")
        if suffix is None:
            indexed_global = metadata_contract(indexed["global_metadata"], base)
            if indexed_global != metadata_by_base[base]:
                raise ValueError(f"catalog and partition-index metadata differ: {base}")
            return indexed_global, {"catalogCellId": row["id"],
                                    "conditionSha256": row["sourceBinding"]["conditionSha256"]}
        name = f"{base}{suffix or ''}.data.mtd"
        path = worker_root / "metadata/matrix_metadata" / name
        raw = metadata_contract(json.loads(path.read_text()), name)
        expected = row["sourceFiles"].get(path.relative_to(evaluation_root).as_posix())
        if expected is None or verify_source(path, expected) != expected:
            raise ValueError(f"base metadata is not bound by frozen catalog: {name}")
        worker = int(suffix.rsplit("_", 1)[1]) if suffix else 1
        matches = [item for item in indexed["partitions"] if item.get("worker") == worker]
        if len(matches) != 1 or Path(matches[0].get("metadata", "")).name != name:
            raise ValueError(f"partition index does not bind metadata: {name}")
        return raw, {"path": str(path), "sha256": expected}

    inputs = [partition_contract(base, candidate["workers"], topology["workers"], load)
              for base in case["worker_input_mappings"]]
    template_path = Path(evaluation_root) / PLANNING_COMMON / case["template"]
    template_sha = verify_source(template_path, case["template_sha256"])
    output = campaign_output("base-campaign", workload, case["dataset"], candidate["workers"])
    program = render_program(template_path.read_text(), inputs, output,
                             int(inputs[0]["globalMetadata"]["rows"]),
                             int(inputs[0]["globalMetadata"]["cols"]), seeds)
    dependencies = dependency_refs(template_path.read_text(), Path(evaluation_root) / PLANNING_COMMON,
                                   row.get("sourceFiles", {}), Path(evaluation_root))
    return make_condition(candidate, workload, case["dataset"], expected_cost, topology,
                          inputs, template_path, template_sha, dependencies, program, output,
                          "FROZEN_PLANNING_INPUT_CONTRACT_REBOUND", seeds,
                          workload_options), []


def network_cost(profile):
    c2w = float(profile["c2w_mbit"])
    w2c = float(profile["w2c_mbit"])
    harmonic = 2.0 / ((1.0 / c2w) + (1.0 / w2c))
    return {"SYSDS_FED_COST_MEM_BW": "25000",
            "SYSDS_FED_COST_FLOPS": "2147483648",
            "SYSDS_FED_COST_NET_BW": f"{harmonic / 8.0:.6f}",
            "SYSDS_FED_COST_NET_BW_C2W": f"{c2w / 8.0:.6f}",
            "SYSDS_FED_COST_NET_BW_W2C": f"{w2c / 8.0:.6f}",
            "SYSDS_FED_COST_NET_SERDES_BW": "210",
            "SYSDS_FED_COST_NET_SERDES_BW_C2W": "210",
            "SYSDS_FED_COST_NET_SERDES_BW_W2C": "14.7",
            "SYSDS_FED_COST_NET_LATENCY": f"{float(profile['rtt_ms']) / 1000.0:.6f}",
            "SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS": "0.35"}


def dependency_refs(template, source_root, source_files, evaluation_root):
    result = []
    for relative in re.findall(r'^\s*source\("([^"]+)"\)', template, re.MULTILINE):
        path = source_root / relative
        expected = source_files.get(path.relative_to(evaluation_root).as_posix())
        if expected is None:
            raise ValueError(f"DML dependency is not frozen: {relative}")
        verify_source(path, expected)
        result.append({"path": str(path), "sha256": expected})
    return result


def campaign_output(kind, workload, dataset, workers):
    if kind == "ml10-campaign":
        unsupervised = workload in {"pca", "als", "kmeans", "gnmf", "gmm"}
    else:
        unsupervised = workload in {"pca", "als", "kmeans"}
    if workload.startswith("sliceline-"):
        return f"tmp/sliceline-{dataset}.csv"
    if workload in ("P1_FULL", "P2_PREP"):
        return f"tmp/{workload}-{dataset}.res"
    return f"tmp/fed_{dataset}_{workers}.res" if unsupervised else f"tmp/{workload}-{dataset}.res"


def ml10_condition(candidate, stage_root, seal_entries, topology, network, seeds, sets):
    fields = candidate["discoveryId"].split(":")
    if len(fields) != 2 or fields[0] != "ml10" or fields[1] not in sets["WORKLOADS"]:
        raise ValueError(f"invalid ML10 discovery ID: {candidate['discoveryId']}")
    workload = fields[1]
    view = "binary" if workload in sets["BINARY_LABEL_WORKLOADS"] else "continuous"
    dataset = "ADULT"
    exp = STAGE_EXPERIMENTS

    def load(base, suffix):
        relative = Path("data") / view / f"{base}{suffix or ''}.data.mtd"
        path, expected = sealed_file(stage_root, seal_entries, relative)
        return metadata_contract(json.loads(path.read_text()), relative.as_posix()), \
            {"path": str(path), "sha256": expected}

    bases = ["ADULT_features"]
    if workload in sets["SUPERVISED"]:
        bases.append("ADULT_labels")
    inputs = [partition_contract(base, candidate["workers"], topology["workers"], load)
              for base in bases]
    template_relative = exp / f"code/exp/{workload}_fed.dml"
    template_path, template_sha = sealed_file(stage_root, seal_entries, template_relative)
    expected_network = network.get(candidate["networkProfile"])
    if expected_network is None:
        raise ValueError(f"unknown ML10 network profile: {candidate['networkProfile']}")
    output = campaign_output("ml10-campaign", workload, dataset, candidate["workers"])
    program = render_program(template_path.read_text(), inputs, output,
                             int(inputs[0]["globalMetadata"]["rows"]),
                             int(inputs[0]["globalMetadata"]["cols"]), seeds)
    return make_condition(candidate, workload, dataset, network_cost(expected_network), topology,
                          inputs, template_path, template_sha, [], program, output,
                          "SEALED_STAGE_STATIC_RENDER", seeds, []), []


def make_condition(candidate, workload, dataset, cost, topology, inputs, template_path,
                   template_sha, dependencies, program, output, method, seeds,
                   workload_jvm_options):
    selected_workers = topology["workers"][:candidate["workers"]]
    payload = {"workers": candidate["workers"], "workerEndpoints": selected_workers,
               "networkProfile": candidate["networkProfile"], "networkCost": cost,
               "privacyMode": "private-aggregate", "workload": workload, "dataset": dataset,
               "inputs": inputs, "template": {"path": str(template_path), "sha256": template_sha},
               "dependencies": dependencies, "program": {"sha256": hashlib.sha256(program.encode()).hexdigest(),
                                                            "text": program},
               "output": output,
               "seedBindings": seeds,
               "compilerArgv": ["-exec", "singlenode", "-seed", str(seeds["SYSTEMDS_SEED"]),
                                "-noFedRuntimeConversion", "-stats", "100"],
               "runtimeDataExecutionAssessed": False}
    if workload_jvm_options:
        payload["workloadJvmOptions"] = list(workload_jvm_options)
    return {"candidateId": candidate["candidateId"], "placeholderCellId": candidate["placeholderCellId"],
            "discoveryId": candidate["discoveryId"], "kind": candidate["kind"],
            "resolutionStatus": "READY_FOR_NATIVE_CAPTURE", "freezeMethod": method,
            "conditionId": candidate["networkProfile"], "conditionSha256": digest(payload),
            "topologySha256": topology["sha256"], "compileModelInput": payload}


def build(unresolved_path, catalog_path, evaluation_root, base_topology_path,
          ml10_topology_path, ml10_stage_root):
    unresolved_path = Path(unresolved_path).resolve()
    evaluation_root = Path(evaluation_root).resolve()
    unresolved = json.loads(unresolved_path.read_text())
    if unresolved.get("schema") != "current-pe-corpus-unresolved-v1":
        raise ValueError("wrong unresolved corpus schema")
    candidates = [row for row in unresolved.get("records", [])
                  if row.get("kind") in ("base-campaign", "ml10-campaign")]
    if len(candidates) != len({row.get("candidateId") for row in candidates}):
        raise ValueError("campaign candidate IDs are duplicated")
    for row in candidates:
        for relative, expected in row.get("sourceFiles", {}).items():
            verify_source(evaluation_root / relative, expected)

    base_values = constants(evaluation_root / BASE_DRIVER,
                            ("NETWORK", "PROFILES", "WORKERS", "SEEDS"))
    ml10_values = constants(evaluation_root / ML10_DRIVER,
                            ("WORKLOADS", "PROFILES", "WORKERS", "BINARY_LABEL_WORKLOADS",
                             "SUPERVISED", "GNMF_SEED", "BASE_DRIVER_SHA256"))
    if ml10_values["BASE_DRIVER_SHA256"] != file_sha(evaluation_root / BASE_DRIVER):
        raise ValueError("ML10 adapter base driver binding differs")
    if tuple(base_values["NETWORK"]) != tuple(base_values["PROFILES"]) \
            or tuple(ml10_values["PROFILES"]) != tuple(base_values["PROFILES"]):
        raise ValueError("campaign network profile registry differs")
    for kind, workers, profiles in (
            ("base-campaign", base_values["WORKERS"], base_values["PROFILES"]),
            ("ml10-campaign", ml10_values["WORKERS"], ml10_values["PROFILES"])):
        groups = {}
        for candidate in candidates:
            if candidate["kind"] == kind:
                groups.setdefault(candidate["discoveryId"], set()).add(
                    (candidate["workers"], candidate["networkProfile"]))
        expected_axes = {(worker, profile) for worker in workers for profile in profiles}
        if not groups or any(axes != expected_axes for axes in groups.values()):
            raise ValueError(f"{kind} candidate axes are incomplete")
    base_topology = load_topology(base_topology_path, base_values["WORKERS"])
    ml10_topology = load_topology(ml10_topology_path, ml10_values["WORKERS"])
    catalog_path, planning = load_catalog(catalog_path, evaluation_root)
    stage_root, stage_seal, seal_entries = parse_seal(ml10_stage_root)
    generator_bindings = []
    for relative in (STAGE_EXPERIMENTS / "code/distributedExpNew.sh",
                     STAGE_EXPERIMENTS / "parameters.sh"):
        path, expected = sealed_file(stage_root, seal_entries, relative)
        generator_bindings.append({"path": str(path), "sha256": expected})
    seeds = {**base_values["SEEDS"], "GNMF_SEED": ml10_values["GNMF_SEED"]}
    if not all(str(seeds.get(key, "")).isdigit()
               for key in ("KMEANS_SEED", "ALS_SEED", "GNMF_SEED")):
        raise ValueError("campaign seeds are not frozen unsigned integers")

    ready, blocked = [], []
    for candidate in sorted(candidates, key=lambda row: row["candidateId"]):
        if candidate["kind"] == "base-campaign":
            condition, reasons = base_condition(candidate, planning, evaluation_root,
                                                base_topology, base_values["NETWORK"], seeds)
        else:
            condition, reasons = ml10_condition(candidate, stage_root, seal_entries,
                                                ml10_topology, base_values["NETWORK"], seeds,
                                                ml10_values)
        if condition is None:
            blocked.append({key: candidate.get(key) for key in
                            ("candidateId", "placeholderCellId", "discoveryId", "kind",
                             "workers", "networkProfile")} | {
                                 "resolutionStatus": "BLOCKED", "reasons": reasons})
        else:
            ready.append(condition)
    counts_by_kind = {}
    for kind in ("base-campaign", "ml10-campaign"):
        total = sum(row["kind"] == kind for row in candidates)
        resolved = sum(row["kind"] == kind for row in ready)
        counts_by_kind[kind] = {"candidates": total, "readyForNativeCapture": resolved,
                                "blocked": total - resolved}
    return {"schema": "campaign-compile-condition-freeze-v1",
            "status": "COMPLETE" if not blocked else "INCOMPLETE",
            "claimScope": "COMPILE_MODEL_INPUT_FREEZE_ONLY",
            "sourceBindings": {
                "unresolved": {"path": str(unresolved_path), "sha256": file_sha(unresolved_path)},
                "catalog": {"path": str(catalog_path), "sha256": file_sha(catalog_path)},
                "evaluationRoot": str(evaluation_root),
                "baseDriver": {"path": str(evaluation_root / BASE_DRIVER),
                               "sha256": file_sha(evaluation_root / BASE_DRIVER)},
                "ml10Driver": {"path": str(evaluation_root / ML10_DRIVER),
                                "sha256": file_sha(evaluation_root / ML10_DRIVER)},
                "baseTopology": base_topology,
                "ml10Topology": ml10_topology,
                "ml10Stage": {"path": str(stage_root), "seal": stage_seal,
                               "generatorBindings": generator_bindings,
                               "verificationScope": "REQUIRED_SEALED_FILES_ONLY"},
                "producerSha256": file_sha(Path(__file__))},
            "counts": {"campaignCandidates": len(candidates),
                       "readyForNativeCapture": len(ready), "blocked": len(blocked),
                       "byKind": counts_by_kind},
            "conditions": ready, "blocked": blocked,
            "limitations": ["P_AND_E_NATIVE_CAPTURE_NOT_RUN",
                            "RUNTIME_DATA_EXECUTION_NOT_ASSESSED",
                            "BLOCKED_CANDIDATES_REMAIN_OUTSIDE_READY_SET"]}


def render(value):
    return json.dumps(value, indent=2, sort_keys=True) + "\n"


def publish(path, content, check):
    path = Path(path)
    if check:
        if not path.is_file() or path.read_text() != content:
            raise SystemExit(f"campaign condition artifact changed: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False) as stream:
        stream.write(content)
        temporary = Path(stream.name)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unresolved", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--base-topology", type=Path, required=True)
    parser.add_argument("--ml10-topology", type=Path, required=True)
    parser.add_argument("--ml10-stage", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    result = build(args.unresolved, args.catalog, args.evaluation_root, args.base_topology,
                   args.ml10_topology, args.ml10_stage)
    publish(args.output, render(result), args.check)
    print(json.dumps({"status": result["status"], **result["counts"],
                      "output": str(args.output)}, sort_keys=True))


if __name__ == "__main__":
    main()
