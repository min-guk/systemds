#!/usr/bin/env python3
import argparse
import csv
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


DEFAULT_FIELDS = ["exec", "placement", "foutType"]
PREFERRED_OUTPUT_FIELDS = [
    "exec",
    "placement",
    "foutType",
    "reason",
    "detail",
    "namespace",
    "type",
    "childIDs",
    "parentIDs",
    "rewireChildIDs",
    "rewireParentIDs",
    "privacy",
    "inputs",
    "notes",
]
ORACLE_RE = re.compile(r"\[Oracle\]\s+hop=(\d+)\s+\((.*?)\),\s*(.*)$")
KEY_RE = re.compile(r"\b([A-Za-z_]+)=")


@dataclass
class OracleEntry:
    hop_id: int
    op: str
    fields: Dict[str, str]
    line_no: int


def parse_kv(kv_str: str) -> Dict[str, str]:
    values: Dict[str, str] = {}
    matches = list(KEY_RE.finditer(kv_str))
    for idx, match in enumerate(matches):
        key = match.group(1)
        start = match.end()
        end = matches[idx + 1].start() if idx + 1 < len(matches) else len(kv_str)
        value = kv_str[start:end].strip()
        if value.endswith(","):
            value = value[:-1].rstrip()
        values[key] = value
    return values


def parse_oracle_lines(path: Path) -> Dict[int, List[OracleEntry]]:
    by_hop: Dict[int, List[OracleEntry]] = {}
    with path.open("r", errors="replace") as handle:
        for line_no, line in enumerate(handle, start=1):
            if "[Oracle]" not in line:
                continue
            match = ORACLE_RE.search(line.rstrip())
            if not match:
                continue
            hop_id = int(match.group(1))
            op = match.group(2).strip()
            kv_str = match.group(3).strip()
            fields = parse_kv(kv_str)
            entry = OracleEntry(hop_id=hop_id, op=op, fields=fields, line_no=line_no)
            by_hop.setdefault(hop_id, []).append(entry)
    return by_hop


def decision_signature(entry: OracleEntry, fields: Sequence[str]) -> Tuple[str, ...]:
    return tuple(entry.fields.get(field, "<missing>") for field in fields)


def unique_entries(
    entries: Iterable[OracleEntry],
    fields: Sequence[str],
    include_op: bool,
) -> List[OracleEntry]:
    seen: Dict[Tuple[str, ...], OracleEntry] = {}
    for entry in entries:
        sig = decision_signature(entry, fields)
        if include_op:
            sig = (entry.op,) + sig
        if sig not in seen:
            seen[sig] = entry
    return list(seen.values())


def decisions_set(entries: Iterable[OracleEntry], fields: Sequence[str]) -> List[Tuple[str, ...]]:
    return list({decision_signature(entry, fields) for entry in entries})


def hop_has_diff(
    display_names: Sequence[str],
    entries_by_file: Dict[str, List[OracleEntry]],
    fields: Sequence[str],
    compare_op: bool,
    show_all: bool,
) -> bool:
    if show_all:
        return True
    if any(not entries_by_file[name] for name in display_names):
        return True
    if compare_op:
        ops = {entry.op for name in display_names for entry in entries_by_file[name]}
        if len(ops) > 1:
            return True
    decision_sets = [decisions_set(entries_by_file[name], fields) for name in display_names]
    if len({frozenset(items) for items in decision_sets}) != 1:
        return True
    if any(len(items) != 1 for items in decision_sets):
        return True
    return False


def format_decision(entry: OracleEntry, fields: Sequence[str], include_op: bool) -> str:
    parts = []
    if include_op:
        parts.append(f"op={entry.op}")
    for field in fields:
        value = entry.fields.get(field, "<missing>")
        parts.append(f"{field}={value}")
    parts.append(f"(line {entry.line_no})")
    return " ".join(parts)


def choose_display_names(paths: Sequence[Path]) -> List[str]:
    names = [path.name for path in paths]
    if len(set(names)) != len(names):
        return [str(path) for path in paths]
    return names


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Compare Oracle decisions across logs and show hops with different "
            "decisions."
        )
    )
    parser.add_argument(
        "logs",
        nargs="*",
        help="Log files to compare (default: test_cost.log test_fedall.log test_heuristic.log)",
    )
    parser.add_argument(
        "--fields",
        default=",".join(DEFAULT_FIELDS),
        help="Comma-separated decision fields to compare.",
    )
    parser.add_argument(
        "--compare-op",
        action="store_true",
        help="Include op name in the comparison criteria.",
    )
    parser.add_argument(
        "--show-all",
        action="store_true",
        help="Show all hops, not only differences.",
    )
    parser.add_argument(
        "--format",
        choices=("text", "tsv", "csv"),
        default="csv",
        help="Output format.",
    )
    parser.add_argument(
        "--output",
        help="Output file path (use - for stdout).",
    )
    return parser.parse_args()


def pick_output_path(fmt: str, output: Optional[str]) -> Optional[Path]:
    if output:
        if output == "-":
            return None
        return Path(output)
    if fmt in ("csv", "tsv"):
        return Path(f"oracle_decision_diff.{fmt}")
    return None


def build_output_fields(all_keys: Sequence[str]) -> List[str]:
    ordered: List[str] = []
    seen = set()
    for key in PREFERRED_OUTPUT_FIELDS:
        if key in all_keys:
            ordered.append(key)
            seen.add(key)
    extras = sorted(key for key in all_keys if key not in seen)
    ordered.extend(extras)
    return ordered


def main() -> int:
    args = parse_args()
    log_paths = [Path(p) for p in args.logs] if args.logs else [
        Path("test_cost.log"),
        Path("test_fedall.log"),
        Path("test_heuristic.log"),
    ]
    for path in log_paths:
        if not path.exists():
            raise SystemExit(f"File not found: {path}")

    fields = [field.strip() for field in args.fields.split(",") if field.strip()]
    display_names = choose_display_names(log_paths)
    per_file: Dict[str, Dict[int, List[OracleEntry]]] = {}
    for path, name in zip(log_paths, display_names):
        per_file[name] = parse_oracle_lines(path)

    all_keys = {
        key
        for data in per_file.values()
        for entries in data.values()
        for entry in entries
        for key in entry.fields.keys()
    }
    output_fields = build_output_fields(all_keys)

    output_path = pick_output_path(args.format, args.output)
    if output_path is None:
        output_handle = sys.stdout
        close_output = False
    else:
        output_handle = output_path.open("w", newline="")
        close_output = True

    all_hops = sorted({hop for data in per_file.values() for hop in data.keys()})
    writer = None
    if args.format in ("tsv", "csv"):
        delimiter = "\t" if args.format == "tsv" else ","
        writer = csv.writer(output_handle, delimiter=delimiter, lineterminator="\n")
        header = ["hop", "file", "op", "line"] + output_fields
        writer.writerow(header)

    shown = 0
    try:
        for hop_id in all_hops:
            entries_by_file = {
                name: per_file[name].get(hop_id, []) for name in display_names
            }
            if not hop_has_diff(
                display_names, entries_by_file, fields, args.compare_op, args.show_all
            ):
                continue

            ops = {entry.op for name in display_names for entry in entries_by_file[name]}
            op_varies = len(ops) > 1
            op_value = next(iter(ops)) if ops else "<missing>"

            if args.format == "text":
                header = f"hop={hop_id} op={op_value}"
                if op_varies:
                    header = f"hop={hop_id} op=<varies>"
                print(header, file=output_handle)

            for name in display_names:
                entries = entries_by_file[name]
                if writer:
                    if not entries:
                        row = (
                            [str(hop_id), name, "<missing>", ""]
                            + ["<missing>"] * len(output_fields)
                        )
                        writer.writerow(row)
                        continue
                    for entry in unique_entries(entries, output_fields, include_op=True):
                        row = [
                            str(hop_id),
                            name,
                            entry.op,
                            str(entry.line_no),
                        ] + [entry.fields.get(field, "<missing>") for field in output_fields]
                        writer.writerow(row)
                    continue

                if not entries:
                    print(f"  {name}: <missing>", file=output_handle)
                    continue

                unique = unique_entries(entries, fields, include_op=op_varies)
                decisions = [
                    format_decision(entry, fields, include_op=op_varies) for entry in unique
                ]
                print(f"  {name}: " + " ; ".join(decisions), file=output_handle)

            if args.format == "text":
                print(file=output_handle)
            shown += 1
        if shown == 0 and args.format == "text":
            print("No differences found.", file=output_handle)
    finally:
        if close_output:
            output_handle.close()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BrokenPipeError:
        # Allow piping to head without noisy stack traces.
        raise SystemExit(0)
