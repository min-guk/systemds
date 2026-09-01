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
BRACKET_KEY_RE = re.compile(r"\[(?P<key>[A-Za-z0-9_]+)\]:")


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


def parse_bracket_kv(line: str) -> Dict[str, str]:
    values: Dict[str, str] = {}
    matches = list(BRACKET_KEY_RE.finditer(line))
    for idx, match in enumerate(matches):
        key = match.group("key")
        start = match.end()
        end = matches[idx + 1].start() if idx + 1 < len(matches) else len(line)
        value = line[start:end].strip()
        if value.endswith(","):
            value = value[:-1].rstrip()
        values[key] = value
    return values


def normalize_hop_id_list(value: str) -> str:
    text = value.strip()
    if not (text.startswith("(") and text.endswith(")")):
        return text
    inner = text[1:-1].strip()
    if not inner:
        return "[]"
    items = [item.strip() for item in inner.split(",") if item.strip()]
    return "[" + ", ".join(items) + "]"


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


def parse_hopid_lines(path: Path) -> Dict[int, List[OracleEntry]]:
    by_hop: Dict[int, List[OracleEntry]] = {}
    with path.open("r", errors="replace") as handle:
        for line_no, line in enumerate(handle, start=1):
            if "[HopID]:" not in line:
                continue
            raw_fields = parse_bracket_kv(line.rstrip())
            hop_id_raw = raw_fields.get("HopID")
            if not hop_id_raw:
                continue
            try:
                hop_id = int(hop_id_raw)
            except ValueError:
                continue
            op = raw_fields.get("Name", "").strip()
            fields: Dict[str, str] = {}
            exec_type = raw_fields.get("ExecType")
            if exec_type:
                fields["exec"] = exec_type
            placement = raw_fields.get("OutputType") or raw_fields.get("FOutType")
            if placement:
                fields["placement"] = placement
            fout_type = raw_fields.get("FType")
            if fout_type:
                fields["foutType"] = fout_type
            child_ids = raw_fields.get("ChildHopIDs")
            if child_ids is not None:
                fields["childIDs"] = normalize_hop_id_list(child_ids)
            parent_ids = raw_fields.get("ParentHopIDs")
            if parent_ids is not None:
                fields["parentIDs"] = normalize_hop_id_list(parent_ids)
            entry = OracleEntry(hop_id=hop_id, op=op, fields=fields, line_no=line_no)
            by_hop.setdefault(hop_id, []).append(entry)
    return by_hop


def decision_signature(entry: OracleEntry, fields: Sequence[str]) -> Tuple[str, ...]:
    return tuple(normalize_field_value(entry.fields.get(field, "<missing>")) for field in fields)


def normalize_field_value(value: str) -> str:
    text = value.strip()
    lowered = text.lower()
    if lowered in ("null", "none"):
        return "null"
    return text


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
            "Compare federated plan decisions across logs and show hops with "
            "different decisions."
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
        "--log-format",
        choices=("oracle", "hopid"),
        default="oracle",
        help="Log format to parse for all logs (default: oracle).",
    )
    parser.add_argument(
        "--log-formats",
        help=(
            "Comma-separated log formats per log file, e.g. 'hopid,oracle,oracle'. "
            "Overrides --log-format."
        ),
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
    if args.log_formats:
        log_formats = [item.strip() for item in args.log_formats.split(",") if item.strip()]
        if len(log_formats) != len(log_paths):
            raise SystemExit(
                "Expected log format count to match logs "
                f"({len(log_paths)}), got {len(log_formats)}."
            )
    else:
        log_formats = [args.log_format] * len(log_paths)
    per_file: Dict[str, Dict[int, List[OracleEntry]]] = {}
    for path, name, log_format in zip(log_paths, display_names, log_formats):
        parser = parse_oracle_lines if log_format == "oracle" else parse_hopid_lines
        per_file[name] = parser(path)

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
