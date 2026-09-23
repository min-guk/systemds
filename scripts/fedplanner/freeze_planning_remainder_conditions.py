#!/usr/bin/env python3
"""Freeze the unresolved planning-snapshot remainder without filling metadata gaps.

The planning snapshot has two descriptors for each worker count: the capture
context consumed by PlanningNativeModelCapture and the broader input template
manifest.  A candidate is compile-model ready only when both descriptors carry
one identical, worker-correct case and its program digest is verified.  Missing
or conflicting descriptors remain explicit unresolved records.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re


PRODUCER = Path(__file__).resolve()
DISCOVERY = re.compile(r"^planning-w([1-9][0-9]*):(.+)$")
WORKER_AUTHORITY = re.compile(r'"worker([1-9][0-9]*):')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text())


def candidates(path):
    document = read_json(path)
    records = document.get("records", document.get("unresolved"))
    if not isinstance(records, list):
        raise ValueError("candidate manifest has no records/unresolved list")
    selected = [record for record in records
                if record.get("kind") == "planning-snapshot"]
    if not selected:
        raise ValueError("candidate manifest has no planning-snapshot records")
    ids = [record.get("candidateId") for record in selected]
    if any(not isinstance(item, str) or not item for item in ids) \
            or len(ids) != len(set(ids)):
        raise ValueError("planning candidate IDs are missing or duplicated")
    axes = [(record.get("discoveryId"), record.get("networkProfile"))
            for record in selected]
    if len(axes) != len(set(axes)):
        raise ValueError("planning discovery/network axes are duplicated")
    return sorted(selected, key=lambda row: row["candidateId"])


def one_case(document, workload, label):
    matches = [case for case in document.get("cases", [])
               if case.get("workload") == workload]
    if len(matches) > 1:
        raise ValueError(f"duplicate {label} cases for {workload}")
    return matches[0] if matches else None


def verified_sources(candidate, evaluation_root):
    sources = candidate.get("sourceFiles")
    if not isinstance(sources, dict) or not sources:
        raise ValueError(f"candidate has no source files: {candidate.get('candidateId')}")
    for relative, expected in sorted(sources.items()):
        path = (evaluation_root / relative).resolve()
        if (not path.is_relative_to(evaluation_root) or not path.is_file()
                or file_sha(path) != expected):
            raise ValueError(
                f"candidate source missing or changed: {candidate['candidateId']}:{relative}")
    return dict(sorted(sources.items()))


def evaluate(candidate, evaluation_root):
    match = DISCOVERY.fullmatch(candidate.get("discoveryId", ""))
    if not match:
        raise ValueError(f"malformed planning discovery ID: {candidate.get('discoveryId')}")
    workers = int(match.group(1))
    workload = match.group(2)
    if candidate.get("workers") != workers:
        raise ValueError(f"candidate worker axis differs: {candidate['candidateId']}")
    profile_name = candidate.get("networkProfile")
    if not isinstance(profile_name, str) or not profile_name:
        raise ValueError(f"candidate network axis is absent: {candidate['candidateId']}")
    sources = verified_sources(candidate, evaluation_root)

    native = evaluation_root / "planning_study/native"
    context_path = native / f"context-w{workers}.json"
    protocol_path = native / f"protocol-w{workers}.json"
    inputs_path = native / f"input_templates/w{workers}/metadata/inputs.json"
    context = read_json(context_path)
    protocol = read_json(protocol_path)
    inputs = read_json(inputs_path)
    if context.get("workers") != workers or protocol.get("workers") != workers:
        raise ValueError(f"snapshot worker descriptor differs for w{workers}")
    if profile_name not in protocol.get("profiles", []):
        raise ValueError(f"profile absent from protocol-w{workers}: {profile_name}")
    context_profiles = context.get("profiles")
    input_profiles = inputs.get("profiles")
    if not isinstance(context_profiles, dict) or profile_name not in context_profiles:
        raise ValueError(f"profile absent from context-w{workers}: {profile_name}")
    if not isinstance(input_profiles, dict) or input_profiles.get(profile_name) != \
            context_profiles[profile_name]:
        raise ValueError(f"input/context profile differs for w{workers}:{profile_name}")

    context_case = one_case(context, workload, "capture context")
    input_case = one_case(inputs, workload, "input manifest")
    program_relative = f"planning_study/native/input_templates/w{workers}/programs/{workload}.dml"
    program_path = evaluation_root / program_relative
    program_sha = file_sha(program_path) if program_path.is_file() else None
    observed_program_workers = sorted({int(item) for item in
                                       WORKER_AUTHORITY.findall(program_path.read_text())}) \
        if program_path.is_file() else []
    blockers = []
    if context_case is None:
        blockers.append("CASE_ABSENT_FROM_CAPTURE_CONTEXT")
    if input_case is None:
        blockers.append("CASE_ABSENT_FROM_INPUT_MANIFEST")
    if input_case is not None and input_case.get("workers") != workers:
        blockers.append("INPUT_MANIFEST_WORKER_COUNT_MISMATCH")
    if context_case is not None and context_case.get("workers") != workers:
        blockers.append("CAPTURE_CONTEXT_WORKER_COUNT_MISMATCH")
    if context_case is not None and context_case.get("program") != f"programs/{workload}.dml":
        blockers.append("CAPTURE_CONTEXT_PROGRAM_PATH_MISMATCH")
    if context_case is not None and context_case.get("program_sha256") != program_sha:
        blockers.append("CAPTURE_CONTEXT_PROGRAM_DIGEST_MISSING_OR_MISMATCHED")
    if input_case is not None and input_case.get("program") != f"programs/{workload}.dml":
        blockers.append("INPUT_MANIFEST_PROGRAM_PATH_MISMATCH")
    declared_program_sha = inputs.get("files", {}).get(f"programs/{workload}.dml")
    if declared_program_sha != program_sha:
        blockers.append("INPUT_MANIFEST_PROGRAM_DIGEST_MISSING_OR_MISMATCHED")
    if sources.get(program_relative) != program_sha:
        blockers.append("CANDIDATE_PROGRAM_DIGEST_MISMATCH")
    if observed_program_workers != list(range(1, workers + 1)):
        blockers.append("PROGRAM_WORKER_AUTHORITIES_MISMATCH")

    if context_case is not None and input_case is not None:
        shared = set(context_case) & set(input_case)
        if any(context_case[key] != input_case[key] for key in shared):
            blockers.append("CAPTURE_CONTEXT_AND_INPUT_CASE_CONFLICT")
    blockers = sorted(set(blockers))
    planned = None
    condition_sha = None
    if not blockers:
        planned = {"workers": workers, "case": context_case,
                   "network": context_profiles[profile_name]}
        condition_sha = digest({"discoveryId": candidate["discoveryId"],
                                "conditionId": profile_name, **planned})

    return {
        "candidateId": candidate["candidateId"],
        "conditionId": profile_name,
        "conditionSha256": condition_sha,
        "discoveryId": candidate["discoveryId"],
        "inputValidation": {
            "captureContextCasePresent": context_case is not None,
            "compileModelReady": not blockers,
            "inputManifestCasePresent": input_case is not None,
            "networkProfileCrossChecked": True,
            "programDigestVerified": declared_program_sha == program_sha
                and sources.get(program_relative) == program_sha,
            "programWorkerAuthoritiesVerified":
                observed_program_workers == list(range(1, workers + 1)),
            "runtimeDataBytesAttested": False,
            "runtimeWorkerReachabilityAssessed": False,
            "sourceDigestsVerified": True,
        },
        "observedInputManifestCase": input_case,
        "observedNetwork": context_profiles[profile_name],
        "observedProgramWorkers": observed_program_workers,
        "placeholderCellId": candidate.get("placeholderCellId"),
        "plannedCondition": planned,
        "resolutionBlockers": blockers,
        "sourceFiles": sources,
        "status": "READY_FOR_COMPILE_MODEL_CAPTURE" if not blockers else "UNRESOLVED",
        "workers": workers,
    }


def build(candidate_manifest, evaluation_root):
    candidate_manifest = Path(candidate_manifest).resolve()
    evaluation_root = Path(evaluation_root).resolve()
    rows = [evaluate(candidate, evaluation_root)
            for candidate in candidates(candidate_manifest)]
    ready = sum(row["status"] == "READY_FOR_COMPILE_MODEL_CAPTURE" for row in rows)
    unresolved = len(rows) - ready
    return {
        "schema": "planning-remainder-condition-freeze-v1",
        "status": "COMPLETE" if unresolved == 0 else "INCOMPLETE",
        "claimScope": "STATIC_COMPILE_MODEL_INPUT_BINDING_ONLY",
        "candidateManifest": {"path": str(candidate_manifest),
                              "sha256": file_sha(candidate_manifest)},
        "evaluationRoot": str(evaluation_root),
        "producerScriptSha256": file_sha(PRODUCER),
        "counts": {"candidates": len(rows), "ready": ready,
                   "sourceVerified": len(rows), "unresolved": unresolved},
        "conditions": rows,
        "promotionRule": (
            "A row is ready only when the capture context and input manifest both contain "
            "a worker-correct case, their shared fields agree, the program digest is pinned, "
            "and the network profile agrees across context, protocol, and input manifest."),
    }


def render(value):
    return json.dumps(value, indent=2, sort_keys=True) + "\n"


def publish(manifest, output, check=False):
    output = Path(output)
    content = render(manifest)
    if check:
        if not output.is_file() or output.read_text() != content:
            raise ValueError(f"planning remainder artifact differs: {output}")
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    temporary.write_text(content)
    temporary.replace(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-manifest", type=Path, required=True)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    manifest = build(args.candidate_manifest, args.evaluation_root)
    publish(manifest, args.output, args.check)
    print(json.dumps({"status": manifest["status"], **manifest["counts"]},
                     sort_keys=True))


if __name__ == "__main__":
    main()
