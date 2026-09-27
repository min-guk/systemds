#!/usr/bin/env python3
"""Certify two source discoveries as function-only libraries of active cells."""

import argparse
import hashlib
import json
import shutil
import subprocess
import tempfile
from collections import Counter
from pathlib import Path


HERE = Path(__file__).resolve()
ROOT = HERE.parents[2]
DEFAULT_CATALOG = Path(
    "/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/"
    "frozen-capture-cohort-derived-argv-v4/catalog.json")
DEFAULT_EVALUATION = DEFAULT_CATALOG.parent / "evaluation"
DEFAULT_INVENTORY = ROOT / "src/test/resources/fedplanner/plan-space/workloads.json"
DEFAULT_OUTPUT = Path(
    "/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/"
    "current-scope-library-closure-v3/receipt.json")
PROBE_SOURCE = ROOT / (
    "src/test/java/org/apache/sysds/parser/dml/ScopeLibrarySyntaxProbe.java")
PROBE_CLASS_OUTPUT = Path("org/apache/sysds/parser/dml/ScopeLibrarySyntaxProbe.class")
PARSER_CLASS_PREFIXES = ("DmlParser", "DmlLexer", "DmlListener")
ANTLR_RUNTIME_RELATIVE = Path("target/lib/antlr4-runtime-4.8.jar")
PROBE_MAIN = "org.apache.sysds.parser.dml.ScopeLibrarySyntaxProbe"

GMM_COMMON = "common:gmm_p1_compat"
GMM_OPTIONAL = "optional:gmm_p1_compat"
SLICE_DISCOVERY = (
    "unclassified:evaluation:planning_study/native/input_templates/common/"
    "code/workloads/sliceline/slicefinder_core.dml")
TARGET_DISCOVERIES = (GMM_COMMON, GMM_OPTIONAL, SLICE_DISCOVERY)
PROFILES = ("lan", "wan_heavy", "wan_light", "wan_mid")
WORKERS = (1, 3, 5, 7)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical_sha(value):
    data = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text())


def render(value):
    return json.dumps(value, indent=2, sort_keys=True) + "\n"


def parser_runtime_binding(build_root):
    build_root = Path(build_root)
    class_root = build_root / "target/classes/org/apache/sysds/parser/dml"
    files = sorted(path for path in class_root.glob("*.class")
                   if any(path.name == prefix + ".class"
                          or path.name.startswith(prefix + "$")
                          for prefix in PARSER_CLASS_PREFIXES))
    required = {prefix + ".class" for prefix in PARSER_CLASS_PREFIXES}
    if not class_root.is_dir() or not required.issubset({path.name for path in files}):
        raise ValueError("compiled DML parser class tree is incomplete")
    records = [{"path": path.relative_to(build_root).as_posix(), "sha256": sha256(path)}
               for path in files]
    antlr = build_root / ANTLR_RUNTIME_RELATIVE
    if not antlr.is_file():
        raise ValueError("ANTLR runtime jar is missing")
    return {
        "classRoot": str(class_root), "classCount": len(records),
        "classTreeSha256": canonical_sha(records), "classes": records,
        "antlrRuntimeJar": {"path": str(antlr), "sha256": sha256(antlr)},
    }


def parser_probe(paths, build_root=ROOT):
    build_root = Path(build_root)
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise ValueError("java and javac are required for the parser probe")
    dependency_classpath = f"{build_root}/target/classes:{build_root}/target/lib/*"
    with tempfile.TemporaryDirectory(prefix="scope-library-probe-") as directory:
        classes = Path(directory)
        compile_command = [javac, "-encoding", "UTF-8", "-cp", dependency_classpath,
                           "-d", str(classes), str(PROBE_SOURCE)]
        subprocess.run(compile_command, check=True, text=True, capture_output=True)
        compiled_class = classes / PROBE_CLASS_OUTPUT
        if not compiled_class.is_file():
            raise ValueError("fresh parser probe compilation produced no class")
        command = [java, "-cp", f"{classes}:{dependency_classpath}", PROBE_MAIN]
        completed = subprocess.run(command + [str(Path(path).resolve()) for path in paths],
                                   check=True, text=True, capture_output=True)
        compiled_class_sha = sha256(compiled_class)
    rows = [json.loads(line) for line in completed.stdout.splitlines() if line.strip()]
    if len(rows) != len(paths):
        raise ValueError("parser probe result count differs")
    result = {}
    for expected, row in zip(paths, rows):
        if Path(row["path"]) != Path(expected).resolve():
            raise ValueError("parser probe path/order differs")
        result[str(Path(expected).resolve())] = row
    version = subprocess.run([javac, "-version"], check=True, text=True,
                             capture_output=True)
    return result, {
        "sourcePath": str(PROBE_SOURCE), "sourceSha256": sha256(PROBE_SOURCE),
        "compiledClassSha256": compiled_class_sha,
        "compiler": str(Path(javac).resolve()),
        "compilerVersion": (version.stdout or version.stderr).strip(),
        "compileArguments": ["-encoding", "UTF-8", "-cp", dependency_classpath,
                             "-d", "<fresh-temporary-directory>", str(PROBE_SOURCE)],
        "executionClasspathPolicy": "FRESH_CLASS_ONLY_THEN_TARGET_CLASSES_AND_LIB",
    }


def _inventory_targets(inventory, evaluation):
    entries = {row["id"]: row for row in inventory["entries"]}
    if set(TARGET_DISCOVERIES) - entries.keys():
        raise ValueError("one of the three target discoveries is absent")
    discovered = inventory["discoveredSourceFiles"]
    gmm_key = "evaluation:planning_study/native/input_templates/common/code/exp/gmm_p1_compat.dml"
    slice_key = ("evaluation:planning_study/native/input_templates/common/code/workloads/"
                 "sliceline/slicefinder_core.dml")
    if set(key for key in discovered if key.endswith("gmm_p1_compat.dml")) != {gmm_key}:
        raise ValueError("gmm source candidate set differs")
    common = entries[GMM_COMMON]
    optional = entries[GMM_OPTIONAL]
    sliceline = entries[SLICE_DISCOVERY]
    gmm_sha = discovered[gmm_key]
    if common.get("templateSha256") != gmm_sha:
        raise ValueError("common gmm registration differs from discovered source")
    if optional.get("name") != "gmm_p1_compat" or optional.get("kind") != "optional-template":
        raise ValueError("optional gmm registration contract differs")
    if sliceline.get("sourceSha256") != discovered.get(slice_key):
        raise ValueError("sliceline registration differs from discovered source")
    targets = {
        "gmm": {"path": Path(evaluation) / gmm_key.split(":", 1)[1],
                "sha256": gmm_sha, "literal": "code/exp/gmm_p1_compat.dml",
                "namespace": "GMMCompat"},
        "sliceline": {"path": Path(evaluation) / slice_key.split(":", 1)[1],
                      "sha256": discovered[slice_key],
                      "literal": "code/workloads/sliceline/slicefinder_core.dml",
                      "namespace": "SL"},
    }
    for target in targets.values():
        if not target["path"].is_file() or sha256(target["path"]) != target["sha256"]:
            raise ValueError(f"registered library source changed: {target['path']}")
    return entries, targets


def _cell_axes(row):
    binding = row["sourceBinding"]
    planned = binding["plannedCondition"]
    return planned["workers"], row["conditionId"]


def _bound_cells(catalog, evaluation, target, discovery_ids, expected_count, probes):
    rows = [row for row in catalog["cells"]
            if (row.get("sourceBinding") or {}).get("kind") == "campaign-compile-condition"
            and row.get("discoveryId") in discovery_ids]
    if len(rows) != expected_count:
        raise ValueError(f"active cell count differs: {len(rows)} != {expected_count}")
    expected_axes = {(discovery, worker, profile) for discovery in discovery_ids
                     for worker in WORKERS for profile in PROFILES}
    actual_axes = {(row["discoveryId"], *_cell_axes(row)) for row in rows}
    if actual_axes != expected_axes or len(actual_axes) != len(rows):
        raise ValueError("active cell axes are not the exact expected cartesian product")

    result = []
    for row in sorted(rows, key=lambda value: value["id"]):
        binding = row["sourceBinding"]
        case = binding["plannedCondition"]["case"]
        imports = case.get("imports")
        if not isinstance(imports, dict) or set(imports) != {target["literal"]}:
            raise ValueError(f"cell import map differs: {row['id']}")
        program_relative = case["program"]
        import_relative = imports[target["literal"]]
        program = Path(evaluation) / program_relative
        imported = Path(evaluation) / import_relative
        source_files = row.get("sourceFiles") or {}
        if (source_files.get(program_relative) != case["program_sha256"]
                or source_files.get(import_relative) != target["sha256"]
                or sha256(program) != case["program_sha256"]
                or sha256(imported) != target["sha256"]):
            raise ValueError(f"cell source/import byte binding differs: {row['id']}")
        parsed = probes[str(program.resolve())]
        expected_import = [{"path": target["literal"], "namespace": target["namespace"]}]
        if parsed["syntaxErrors"] != 0 or parsed["imports"] != expected_import:
            raise ValueError(f"cell literal import syntax differs: {row['id']}")
        result.append({
            "cellId": row["id"], "conditionId": row["conditionId"],
            "discoveryId": row["discoveryId"], "workers": binding["plannedCondition"]["workers"],
            "conditionSha256": binding["conditionSha256"],
            "programPath": str(program), "programSha256": case["program_sha256"],
            "literalImport": expected_import[0], "importCopyPath": str(imported),
            "importCopySha256": target["sha256"],
        })
    return result


def build(catalog_path=DEFAULT_CATALOG, inventory_path=DEFAULT_INVENTORY,
          evaluation=DEFAULT_EVALUATION, build_root=ROOT, probe=parser_probe):
    catalog_path, inventory_path = Path(catalog_path), Path(inventory_path)
    evaluation, build_root = Path(evaluation), Path(build_root)
    parser_runtime = parser_runtime_binding(build_root)
    catalog, inventory = read_json(catalog_path), read_json(inventory_path)
    if catalog.get("discoveryInventorySha256") != sha256(inventory_path):
        raise ValueError("catalog is not bound to the supplied source inventory")
    entries, targets = _inventory_targets(inventory, evaluation)

    primary_rows = [row for row in catalog["cells"]
                    if (row.get("sourceBinding") or {}).get("kind") == "campaign-compile-condition"
                    and row.get("discoveryId") in {
                        "base:p1:P1_FULL", "base:sliceline:sliceline-adult",
                        "base:sliceline:sliceline-covtype"}]
    paths = [targets["gmm"]["path"], targets["sliceline"]["path"]]
    paths.extend(Path(evaluation) / row["sourceBinding"]["plannedCondition"]["case"]["program"]
                 for row in primary_rows)
    unique_paths = list(dict.fromkeys(Path(path).resolve() for path in paths))
    probes, probe_binding = probe(unique_paths, build_root)
    library_syntax = {}
    for name, target in targets.items():
        parsed = probes[str(target["path"].resolve())]
        if (parsed["syntaxErrors"] != 0 or parsed["topLevelStatementCount"] != 0
                or parsed["functionCount"] <= 0 or parsed["imports"]):
            raise ValueError(f"target is not a parser-proven function-only library: {name}")
        library_syntax[name] = parsed

    p1 = _bound_cells(catalog, evaluation, targets["gmm"],
                      ("base:p1:P1_FULL",), 16, probes)
    sliceline = _bound_cells(catalog, evaluation, targets["sliceline"],
                             ("base:sliceline:sliceline-adult",
                              "base:sliceline:sliceline-covtype"), 32, probes)

    target_digests = {target["sha256"] for target in targets.values()}
    other_consumers = []
    primary_ids = {row["cellId"] for row in p1 + sliceline}
    for row in catalog["cells"]:
        if row["id"] in primary_ids:
            continue
        matches = sorted((path, digest) for path, digest in (row.get("sourceFiles") or {}).items()
                         if digest in target_digests)
        if matches:
            other_consumers.append({"cellId": row["id"], "discoveryId": row["discoveryId"],
                                    "conditionId": row["conditionId"], "sourceCopies": matches,
                                    "sourceBindingKind": (row.get("sourceBinding") or {}).get("kind")})
    kinds = Counter(row["sourceBindingKind"] for row in other_consumers)

    registrations = []
    for discovery, library, basis in (
            (GMM_COMMON, "gmm", "templateSha256"),
            (SLICE_DISCOVERY, "sliceline", "sourcePath+sourceSha256")):
        entry = entries[discovery]
        registrations.append({
            "discoveryId": discovery, "inventoryKind": entry["kind"],
            "inventoryStatus": entry["status"], "sourceIdentityBasis": basis,
            "library": library, "sourcePath": str(targets[library]["path"]),
            "sourceSha256": targets[library]["sha256"],
            "resolution": "ACTIVE_IMPORTED_FUNCTION_LIBRARY",
        })
    optional = entries[GMM_OPTIONAL]
    candidate_alias_evidence = [{
        "discoveryId": GMM_OPTIONAL, "inventoryKind": optional["kind"],
        "inventoryStatus": optional["status"],
        "candidatePath": str(targets["gmm"]["path"]),
        "candidateSha256": targets["gmm"]["sha256"],
        "candidateSelectionBasis": "UNIQUE_MATCHING_BASENAME_IN_DISCOVERED_SOURCE_FILES",
        "resolution": "UNRESOLVED",
        "rationale": ("The optional inventory row contains no source path, source SHA, or "
                      "provenance edge that identifies this candidate as the registered row."),
    }]

    result = {
        "schema": "current-scope-library-closure-v3",
        "status": "COMPLETE",
        "claimScope": "EXACTLY_TWO_RESOLVED_SOURCE_DISCOVERIES",
        "inputs": {
            "producerScript": {"path": str(HERE), "sha256": sha256(HERE)},
            "parserProbe": probe_binding,
            "parserRuntime": parser_runtime,
            "catalog": {"path": str(catalog_path), "sha256": sha256(catalog_path)},
            "inventory": {"path": str(inventory_path), "sha256": sha256(inventory_path)},
            "evaluationRoot": str(evaluation),
        },
        "registrations": registrations,
        "candidateAliasEvidence": candidate_alias_evidence,
        "libraries": {
            name: {"sourcePath": str(target["path"]), "sourceSha256": target["sha256"],
                   "syntax": library_syntax[name]}
            for name, target in targets.items()
        },
        "activeCellBindings": {"p1": p1, "sliceline": sliceline},
        "counts": {"resolvedRegistrations": 2, "unresolvedCandidateAliases": 1,
                   "p1ActiveCells": len(p1),
                   "slicelineActiveCells": len(sliceline),
                   "otherSourceConsumers": len(other_consumers),
                   "otherSourceConsumerKinds": dict(sorted(kinds.items()))},
        "otherSourceConsumers": sorted(other_consumers, key=lambda row: row["cellId"]),
        "limitations": [
            "THIS_RECEIPT_CLOSES_ONLY_COMMON_GMM_AND_UNCLASSIFIED_SLICELINE",
            "OPTIONAL_GMM_REMAINS_UNRESOLVED_WITH_CANDIDATE_ALIAS_EVIDENCE_ONLY",
            "SOURCE_SHA_OR_BASENAME_ALONE_IS_NOT_USED_AS_APPLICABILITY_EVIDENCE",
            "ADDITIONAL_PLANNING_AND_DERIVED_CONSUMERS_ARE_RECORDED_BUT_NOT_RECLASSIFIED",
            "THE_EXISTING_V1_APPLICABILITY_LEDGER_REQUIRES_AN_EXPLICIT_RECEIPT_CONSUMER_UPDATE",
        ],
    }
    result["recordsSha256"] = canonical_sha({
        "registrations": registrations, "libraries": result["libraries"],
        "candidateAliasEvidence": candidate_alias_evidence,
        "activeCellBindings": result["activeCellBindings"],
        "otherSourceConsumers": result["otherSourceConsumers"],
    })
    return result


def publish(path, content, check):
    path = Path(path)
    if check:
        if not path.is_file() or path.read_text() != content:
            raise SystemExit(f"scope library receipt changed: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False) as stream:
        stream.write(content)
        temporary = Path(stream.name)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--inventory", type=Path, default=DEFAULT_INVENTORY)
    parser.add_argument("--evaluation-root", type=Path, default=DEFAULT_EVALUATION)
    parser.add_argument("--build-root", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    receipt = build(args.catalog, args.inventory, args.evaluation_root, args.build_root)
    publish(args.output, render(receipt), args.check)
    print(json.dumps({"output": str(args.output), "status": receipt["status"],
                      "counts": receipt["counts"]}, sort_keys=True))


if __name__ == "__main__":
    main()
