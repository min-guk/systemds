#!/usr/bin/env python3
"""Audit unresolved source discoveries before a FULL_CURRENT claim.

The audit is intentionally conservative.  A filename or lack of a direct
launcher reference is evidence, but is never enough to exclude an
UNSUPPORTED discovery from the current workload contract.
"""

import argparse
import hashlib
import json
import re
import tempfile
from collections import Counter, defaultdict
from pathlib import Path


HERE = Path(__file__).resolve()
ROOT = HERE.parents[2]
DEFAULT_CATALOG = Path(
    "/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/"
    "frozen-capture-cohort-derived-argv-v4/catalog.json")
DEFAULT_FROZEN_EVALUATION = DEFAULT_CATALOG.parent / "evaluation"
DEFAULT_INVENTORY = ROOT / "src/test/resources/fedplanner/plan-space/workloads.json"
DEFAULT_EVALUATION = ROOT.parent / "cofee-evaluation"
DEFAULT_LEGACY = ROOT.parent / "COFEE-Experiment"
DEFAULT_OUTPUT = Path(
    "/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/"
    "current-scope-applicability-audit-v1/ledger.json")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical_sha(value):
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"),
                         ensure_ascii=False).encode()
    return hashlib.sha256(encoded).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text())


def resolve_inventory_source(entry, evaluation, legacy):
    kind = entry["kind"]
    if kind == "unclassified-source":
        label, relative = entry["sourcePath"].split(":", 1)
        root = evaluation if label == "evaluation" else legacy
        return [(root / relative, entry["sourceSha256"])]
    if kind == "unclassified-template":
        return [(evaluation / "planning_study/native/input_templates/common/code/exp" /
                 f"{entry['name']}.dml", entry["templateSha256"])]
    if kind == "historical-template":
        return [(legacy / "experiments/archive/submitted_results/code/exp" /
                 f"{entry['id'].split(':', 1)[1]}.dml", entry["templateSha256"])]
    if kind == "optional-template":
        name = entry["name"] + ".dml"
        candidates = []
        for key, digest in sorted(entry["_inventoryDiscoveredFiles"].items()):
            label, relative = key.split(":", 1)
            if Path(relative).name != name:
                continue
            root = evaluation if label == "evaluation" else legacy
            candidates.append((root / relative, digest))
        return candidates
    raise ValueError(f"unsupported audited inventory kind: {kind}")


def resolve_pinned_files(inventory, evaluation, legacy):
    resolved = []
    for name, digest in sorted(inventory["sources"].items()):
        if name.startswith("legacy:"):
            path = legacy / name.split(":", 1)[1]
        else:
            path = evaluation / name
        if not path.is_file() or sha256(path) != digest:
            raise ValueError(f"pinned launcher/config source changed: {path}")
        resolved.append((name, path, digest))
    return resolved


def direct_references(paths, pinned_files, evaluation, legacy):
    exact_terms = set()
    name_terms = set()
    for path, _ in paths:
        exact_terms.add(str(path))
        for root in (evaluation, legacy):
            if path.is_relative_to(root):
                exact_terms.add(path.relative_to(root).as_posix())
        name_terms.add(path.name)
        name_terms.add(path.stem)
    exact_evidence = []
    name_evidence = []
    for label, path, digest in pinned_files:
        text = path.read_text(errors="replace")
        exact_matches = sorted(term for term in exact_terms if term and term in text)
        name_matches = sorted(term for term in name_terms if term and
                         re.search(r"(?<![A-Za-z0-9_])" + re.escape(term) +
                                   r"(?![A-Za-z0-9_])", text))
        if exact_matches:
            exact_evidence.append({"path": str(path), "sha256": digest,
                                   "matchedTerms": exact_matches})
        if name_matches:
            name_evidence.append({"path": str(path), "sha256": digest,
                                  "matchedTerms": name_matches})
    return exact_evidence, name_evidence


def dml_references(paths, inventory, evaluation, legacy):
    basenames = {path.name for path, _ in paths}
    result = []
    for key, digest in sorted(inventory["discoveredSourceFiles"].items()):
        label, relative = key.split(":", 1)
        root = evaluation if label == "evaluation" else legacy
        path = root / relative
        if not path.is_file() or sha256(path) != digest:
            raise ValueError(f"discovered DML source changed: {path}")
        if path in {candidate for candidate, _ in paths}:
            continue
        text = path.read_text(errors="replace")
        matches = sorted(name for name in basenames if name in text)
        if matches:
            result.append({"path": str(path), "sha256": digest,
                           "matchedBasenames": matches})
    return result


def role_candidate(entry, source_paths, dml_refs):
    kind = entry["kind"]
    if kind == "historical-template":
        return "ARCHIVE_REVISION"
    if kind == "optional-template":
        return "ENTRYPOINT_CANDIDATE"
    relative = " ".join(str(path).lower() for path, _ in source_paths)
    if "/datagen/" in relative or "/fedmapgenerators/" in relative:
        return "DATA_OR_FEDMAP_GENERATOR_CANDIDATE"
    if "/reference/" in relative:
        return "REFERENCE_IMPLEMENTATION_CANDIDATE"
    if dml_refs:
        return "DML_DEPENDENCY_CANDIDATE"
    if "/code/exp/" in relative:
        return "ENTRYPOINT_OR_TEMPLATE_CANDIDATE"
    return "UNKNOWN_SOURCE_ROLE"


def frozen_cohort_supersession(catalog, evaluation):
    """Bind unresolved discovery placeholders to all frozen successor cells."""
    groups = defaultdict(list)
    in_scope = [row for row in catalog["cells"]
                if row.get("inventoryStatus") == "IN_SCOPE"]
    if len(in_scope) != 671 or len({row.get("id") for row in in_scope}) != 671:
        raise ValueError("frozen in-scope denominator or cell identities changed")
    for row in in_scope:
        groups[row["discoveryId"]].append(row)
    snapshot_rows = []
    records = []
    successor_counts = Counter()
    file_digests = {}
    for discovery_id, rows in sorted(groups.items()):
        placeholders = [row for row in rows if row.get("conditionId") == "unresolved"]
        successors = [row for row in rows if row.get("conditionId") != "unresolved"]
        if len(placeholders) > 1 or any(row.get("sourceBinding", {}).get("conditionStatus")
                                        != "UNRESOLVED" for row in placeholders):
            raise ValueError("ambiguous frozen discovery placeholder: " + discovery_id)
        if not successors or any(row.get("sourceBinding", {}).get("conditionStatus")
                                 != "SNAPSHOT_ONLY" for row in successors):
            raise ValueError("frozen discovery has no complete successor set: " + discovery_id)
        if len({(row["conditionId"], row["sourceBinding"].get("conditionSha256"))
                for row in successors}) != len(successors):
            raise ValueError("duplicate frozen successor condition: " + discovery_id)
        for row in successors:
            binding = row["sourceBinding"]
            if binding.get("discoveryId") != discovery_id:
                raise ValueError("successor source binding changed: " + row["id"])
            files = row.get("sourceFiles")
            if not isinstance(files, dict) or not files:
                raise ValueError("frozen successor has no source files: " + row["id"])
            for name, expected in files.items():
                path = (evaluation / name).resolve()
                if not path.is_relative_to(evaluation) or not path.is_file():
                    raise ValueError("frozen successor source missing: " + name)
                actual = file_digests.setdefault(path, sha256(path))
                if actual != expected:
                    raise ValueError("frozen successor source changed: " + name)
            snapshot_rows.append(row)
        if placeholders:
            successor_counts[len(successors)] += 1
            placeholder = placeholders[0]
            records.append({
                "discoveryId": discovery_id,
                "placeholderCellId": placeholder["id"],
                "placeholderBindingSha256": canonical_sha(placeholder["sourceBinding"]),
                "resolution": "SUPERSEDED_IN_FROZEN_COHORT",
                "successors": [{"cellId": row["id"], "conditionId": row["conditionId"],
                                "conditionSha256": row["sourceBinding"]["conditionSha256"],
                                "sourceFilesSha256": canonical_sha(row["sourceFiles"])}
                               for row in sorted(successors, key=lambda item: item["id"])],
            })
    if (len(snapshot_rows) != 612 or len(records) != 59 or
            successor_counts != Counter({1: 28, 4: 8, 12: 10, 16: 13})):
        raise ValueError("frozen cohort supersession denominator changed")
    return {"status": "COMPLETE", "placeholderCount": len(records),
            "successorCount": sum(len(row["successors"]) for row in records),
            "frozenSnapshotCount": len(snapshot_rows),
            "successorsPerPlaceholder": {str(size): count for size, count in
                                         sorted(successor_counts.items())},
            "verifiedDistinctSourceFiles": len(file_digests),
            "recordsSha256": canonical_sha(records), "records": records}


def build(catalog_path=DEFAULT_CATALOG, inventory_path=DEFAULT_INVENTORY,
          evaluation=DEFAULT_EVALUATION, legacy=DEFAULT_LEGACY,
          frozen_evaluation=DEFAULT_FROZEN_EVALUATION):
    catalog_path = Path(catalog_path)
    inventory_path = Path(inventory_path)
    evaluation = Path(evaluation)
    legacy = Path(legacy)
    frozen_evaluation = Path(frozen_evaluation)
    catalog = read_json(catalog_path)
    inventory = read_json(inventory_path)
    if catalog.get("schema") != "closed-comparison-cases-v1":
        raise ValueError("unexpected frozen catalog schema")
    if inventory.get("schemaVersion") != 1:
        raise ValueError("unexpected source inventory schema")
    inventory_digest = sha256(inventory_path)
    if catalog.get("discoveryInventorySha256") != inventory_digest:
        raise ValueError("catalog is not bound to the supplied source inventory")

    supersession = frozen_cohort_supersession(catalog, frozen_evaluation)

    inventory_by_id = {row["id"]: row for row in inventory["entries"]}
    if len(inventory_by_id) != len(inventory["entries"]):
        raise ValueError("duplicate source inventory ID")
    pinned_files = resolve_pinned_files(inventory, evaluation, legacy)
    audited = [row for row in catalog["cells"]
               if row.get("inventoryStatus") in ("UNSUPPORTED", "HISTORICAL")]
    if len(audited) != 164:
        raise ValueError(f"expected 164 applicability rows, found {len(audited)}")
    audited_cell_ids = [row.get("id") for row in audited]
    audited_discovery_ids = [row.get("discoveryId") for row in audited]
    if (any(not isinstance(value, str) or not value for value in audited_cell_ids) or
            len(set(audited_cell_ids)) != len(audited_cell_ids)):
        raise ValueError("audited catalog cell IDs must be unique non-empty strings")
    if (any(not isinstance(value, str) or not value for value in audited_discovery_ids) or
            len(set(audited_discovery_ids)) != len(audited_discovery_ids)):
        raise ValueError("audited catalog discovery IDs must be unique non-empty strings")
    expected_discovery_ids = {row["id"] for row in inventory["entries"]
                              if row.get("status") in ("UNSUPPORTED", "HISTORICAL")}
    if set(audited_discovery_ids) != expected_discovery_ids:
        missing = sorted(expected_discovery_ids - set(audited_discovery_ids))
        extra = sorted(set(audited_discovery_ids) - expected_discovery_ids)
        raise ValueError(f"audited discovery set differs from inventory: "
                         f"missing={missing}, extra={extra}")

    records = []
    for cell in sorted(audited, key=lambda row: row["id"]):
        discovery_id = cell["discoveryId"]
        entry = inventory_by_id.get(discovery_id)
        if entry is None:
            raise ValueError(f"catalog discovery absent from inventory: {discovery_id}")
        if entry["status"] != cell["inventoryStatus"]:
            raise ValueError(f"inventory status mismatch: {discovery_id}")
        enriched = dict(entry)
        enriched["_inventoryDiscoveredFiles"] = inventory["discoveredSourceFiles"]
        sources = resolve_inventory_source(enriched, evaluation, legacy)
        source_evidence = []
        for path, expected in sources:
            present = path.is_file()
            actual = sha256(path) if present else None
            source_evidence.append({"path": str(path), "expectedSha256": expected,
                                    "actualSha256": actual, "verified": actual == expected})
        sources_verified = bool(sources) and all(row["verified"] for row in source_evidence)
        refs, name_mentions = direct_references(sources, pinned_files, evaluation, legacy)
        dependencies = dml_references(sources, inventory, evaluation, legacy) if sources else []
        role = role_candidate(entry, sources, dependencies)

        archive_prefix = legacy / "experiments/archive/submitted_results/code/exp"
        archived = (entry["kind"] == "historical-template" and sources_verified and
                    all(path.parent == archive_prefix for path, _ in sources))
        resolution = "UNRESOLVED"
        applicability = "UNRESOLVED"
        rationale = ("Role/reachability evidence is insufficient to exclude this discovery "
                     "from the owned current workload contract.")
        records.append({
            "catalogCellId": cell["id"],
            "discoveryId": discovery_id,
            "priorInventoryStatus": cell["inventoryStatus"],
            "priorInventoryReason": cell.get("inventoryReason"),
            "inventoryKind": entry["kind"],
            "roleCandidate": role,
            "archiveLocationVerified": archived,
            "launcherReachability": ("DIRECT_PINNED_REFERENCE" if refs else
                                     "NO_DIRECT_REFERENCE_IN_PINNED_SET"),
            "currentApplicability": applicability,
            "resolution": resolution,
            "rationale": rationale,
            "sourceEvidence": source_evidence,
            "pinnedLauncherReferences": refs,
            "pinnedLauncherNameMentions": name_mentions,
            "dmlDependencyReferences": dependencies,
        })

    resolutions = Counter(row["resolution"] for row in records)
    roles = Counter(row["roleCandidate"] for row in records)
    prior = Counter(row["priorInventoryStatus"] for row in records)
    result = {
        "schema": "current-scope-applicability-audit-v1",
        "status": "COMPLETE" if not resolutions["UNRESOLVED"] else "INCOMPLETE",
        "claimScope": "S0_CURRENT_SCOPE_APPLICABILITY_ONLY",
        "inputs": {
            "producerScript": {"path": str(HERE), "sha256": sha256(HERE)},
            "catalog": {"path": str(catalog_path), "sha256": sha256(catalog_path)},
            "inventory": {"path": str(inventory_path), "sha256": inventory_digest},
            "evaluationRoot": str(evaluation),
            "legacyRoot": str(legacy),
            "frozenEvaluationRoot": str(frozen_evaluation),
            "pinnedLauncherFiles": [{"label": label, "path": str(path), "sha256": digest}
                                    for label, path, digest in pinned_files],
        },
        "counts": {
            "records": len(records),
            "priorStatus": dict(sorted(prior.items())),
            "resolution": dict(sorted(resolutions.items())),
            "roleCandidate": dict(sorted(roles.items())),
        },
        "limitations": [
            "NO_DIRECT_REFERENCE_DOES_NOT_PROVE_UNREACHABLE",
            "UNSUPPORTED_DISCOVERIES_ARE_NOT_SILENTLY_EXCLUDED",
            "DML_DEPENDENCY_TEXT_MATCHES_ARE_ROLE_HINTS_ONLY",
            "RUNTIME_DATA_EXECUTION_NOT_ASSESSED",
        ],
        "records": records,
        "frozenCohortSupersession": supersession,
    }
    result["recordsSha256"] = canonical_sha(records)
    return result


def render(value):
    return json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def publish(path, content, check):
    path = Path(path)
    if check:
        if not path.is_file() or path.read_text() != content:
            raise SystemExit(f"applicability ledger changed: {path}")
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
    parser.add_argument("--legacy-root", type=Path, default=DEFAULT_LEGACY)
    parser.add_argument("--frozen-evaluation-root", type=Path,
                        default=DEFAULT_FROZEN_EVALUATION)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    ledger = build(args.catalog, args.inventory, args.evaluation_root,
                   args.legacy_root, args.frozen_evaluation_root)
    publish(args.output, render(ledger), args.check)
    print(json.dumps({"output": str(args.output), "status": ledger["status"],
                      "counts": ledger["counts"]}, sort_keys=True))
    if ledger["status"] != "COMPLETE" and not args.check:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
