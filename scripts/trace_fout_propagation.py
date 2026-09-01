#!/usr/bin/env python3
import argparse
import re
import sys
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional, Tuple


LINE_RE = re.compile(
    r"^\[Oracle\] hop=(\d+) \((.*)\), exec=([^,]+), placement=([^,]+), "
    r"foutType=([^,]+), reason=([^,]+), childIDs=\[([^\]]*)\], "
    r"parentIDs=\[([^\]]*)\], .*? inputs=\[([^\]]*)\], notes=\[.*\]$"
)


@dataclass
class Node:
    hop_id: int
    op: str
    exec_type: str
    placement: str
    fout_type: str
    reason: str
    child_ids: List[int]
    parent_ids: List[int]
    inputs: List[str]
    line_no: int

    def input_for_child(self, child_id: int) -> Tuple[Optional[str], Optional[int]]:
        try:
            idx = self.child_ids.index(child_id)
        except ValueError:
            return None, None
        if idx >= len(self.inputs):
            return None, idx
        return self.inputs[idx], idx


def parse_id_list(text: str) -> List[int]:
    text = text.strip()
    if not text:
        return []
    return [int(item.strip()) for item in text.split(",") if item.strip()]


def parse_str_list(text: str) -> List[str]:
    text = text.strip()
    if not text:
        return []
    return [item.strip() for item in text.split(",")]


def parse_oracle_line(line: str, line_no: int) -> Optional[Node]:
    match = LINE_RE.match(line.rstrip())
    if not match:
        return None
    (
        hop_id,
        op,
        exec_type,
        placement,
        fout_type,
        reason,
        child_ids,
        parent_ids,
        inputs,
    ) = match.groups()
    return Node(
        hop_id=int(hop_id),
        op=op,
        exec_type=exec_type,
        placement=placement,
        fout_type=fout_type,
        reason=reason,
        child_ids=parse_id_list(child_ids),
        parent_ids=parse_id_list(parent_ids),
        inputs=parse_str_list(inputs),
        line_no=line_no,
    )


def is_none_type(value: Optional[str]) -> bool:
    if value is None:
        return True
    value = value.strip().lower()
    return value in ("none", "null", "")


def row_like(value: str) -> bool:
    return value.strip().upper().startswith("ROW")


def load_nodes(lines: Iterable[str]) -> Tuple[Dict[int, Node], Dict[int, int]]:
    nodes: Dict[int, Node] = {}
    dup_counts: Dict[int, int] = {}
    for idx, line in enumerate(lines, start=1):
        node = parse_oracle_line(line, idx)
        if not node:
            continue
        if node.hop_id in nodes:
            dup_counts[node.hop_id] = dup_counts.get(node.hop_id, 1) + 1
            continue
        nodes[node.hop_id] = node
    return nodes, dup_counts


def pick_starts(
    nodes: Dict[int, Node],
    hop_ids: List[int],
    names: List[str],
    name_regexes: List[re.Pattern],
    ignore_case: bool,
) -> List[Node]:
    starts: Dict[int, Node] = {}

    def add(node: Node) -> None:
        starts[node.hop_id] = node

    if hop_ids:
        for hop_id in hop_ids:
            node = nodes.get(hop_id)
            if node:
                add(node)
        return list(starts.values())

    for node in nodes.values():
        if names:
            for name in names:
                haystack = node.op
                needle = name
                if ignore_case:
                    haystack = haystack.lower()
                    needle = needle.lower()
                if needle in haystack:
                    add(node)
                    break
        if name_regexes:
            for pattern in name_regexes:
                if pattern.search(node.op):
                    add(node)
                    break

    if starts:
        return list(starts.values())

    for node in nodes.values():
        if row_like(node.fout_type):
            add(node)
    return list(starts.values())


def trace_paths(
    nodes: Dict[int, Node],
    start: Node,
    max_depth: int,
) -> List[List[Tuple[Node, Node, Optional[str], Optional[int], List[str]]]]:
    paths: List[List[Tuple[Node, Node, Optional[str], Optional[int], List[str]]]] = []

    def walk(current: Node, path, visited, depth):
        if depth >= max_depth:
            paths.append(path)
            return
        if not current.parent_ids:
            paths.append(path)
            return

        for parent_id in current.parent_ids:
            if parent_id in visited:
                paths.append(path)
                continue
            parent = nodes.get(parent_id)
            if not parent:
                paths.append(path)
                continue
            input_type, input_idx = parent.input_for_child(current.hop_id)
            drop_reasons: List[str] = []
            if is_none_type(input_type):
                drop_reasons.append("input none")
            if is_none_type(parent.fout_type):
                drop_reasons.append("parent foutType none")
            next_path = path + [(current, parent, input_type, input_idx, drop_reasons)]
            walk(parent, next_path, visited | {parent_id}, depth + 1)

    walk(start, [], {start.hop_id}, 0)
    return paths


def slice_path_for_recovery(
    path: List[Tuple[Node, Node, Optional[str], Optional[int], List[str]]]
) -> List[Tuple[Node, Node, Optional[str], Optional[int], List[str]]]:
    drop_idx = None
    for idx, step in enumerate(path):
        parent = step[1]
        if is_none_type(parent.fout_type):
            drop_idx = idx
            break

    if drop_idx is None:
        return path

    recover_idx = None
    for idx in range(drop_idx + 1, len(path)):
        parent = path[idx][1]
        if not is_none_type(parent.fout_type):
            recover_idx = idx
            break

    if recover_idx is not None:
        for idx in range(recover_idx + 1, len(path)):
            parent = path[idx][1]
            if is_none_type(parent.fout_type):
                return path
        return path[: recover_idx + 1]

    end = min(drop_idx + 2, len(path))
    return path[:end]


def slice_path_drop_only(
    path: List[Tuple[Node, Node, Optional[str], Optional[int], List[str]]]
) -> List[Tuple[Node, Node, Optional[str], Optional[int], List[str]]]:
    for idx, step in enumerate(path):
        parent = step[1]
        if is_none_type(parent.fout_type):
            return path[: idx + 1]
    return path


def format_step_label(step, marker: str = "") -> str:
    child, parent, input_type, input_idx, drop_reasons = step
    idx_label = f"idx={input_idx}" if input_idx is not None else ""
    if idx_label:
        idx_label = f" ({idx_label})"
    label = (
        f"{child.hop_id} ({child.op}) "
        f"{child.exec_type}/{child.placement}/{child.fout_type}"
        f"{idx_label} -> "
        f"{parent.hop_id} ({parent.op}) "
        f"{parent.exec_type}/{parent.placement}/{parent.fout_type} "
        f"(reason={parent.reason})"
    )
    if marker:
        return f"{marker} {label}"
    return label


def build_path_labels(
    path: List[Tuple[Node, Node, Optional[str], Optional[int], List[str]]]
) -> List[str]:
    labels: List[str] = []
    prev_parent_none = False
    for step in path:
        parent = step[1]
        curr_parent_none = is_none_type(parent.fout_type)
        marker = ""
        if curr_parent_none and not prev_parent_none:
            marker = "[DROP]"
        elif not curr_parent_none and prev_parent_none:
            marker = "[Recover]"
        labels.append(format_step_label(step, marker))
        prev_parent_none = curr_parent_none
    return labels


def build_tree(paths: List[List[str]]) -> Dict[str, Dict]:
    root: Dict[str, Dict] = {}
    for path in paths:
        node = root
        for label in path:
            node = node.setdefault(label, {})
    return root


def print_tree(node: Dict[str, Dict], prefix: str = "") -> None:
    items = list(node.items())
    for idx, (label, child) in enumerate(items):
        is_last = idx == len(items) - 1
        connector = "`-- " if is_last else "|-- "
        print(f"{prefix}{connector}{label}")
        extension = "    " if is_last else "|   "
        print_tree(child, prefix + extension)


def print_paths(label_paths: List[List[str]]) -> None:
    for idx, path in enumerate(label_paths, start=1):
        print(f"Path {idx}:")
        for label in path:
            print(f"  {label}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Trace when foutType propagation becomes none using [Oracle] logs."
        )
    )
    parser.add_argument("--log", default="test.log", help="Path to log file.")
    parser.add_argument("--hop", type=int, action="append", default=[])
    parser.add_argument("--name", action="append", default=[])
    parser.add_argument("--name-regex", action="append", default=[])
    parser.add_argument("--ignore-case", action="store_true")
    parser.add_argument("--max-depth", type=int, default=25)
    parser.add_argument(
        "--continue-after-drop",
        action="store_true",
        help="Print full ancestry paths even after foutType becomes none.",
    )
    parser.add_argument(
        "--show-recover",
        action="store_true",
        help="Show recovery segment after first drop when applicable.",
    )
    parser.add_argument(
        "--tree",
        action="store_true",
        help="Render shared prefixes as a tree.",
    )
    args = parser.parse_args()

    if args.log == "-":
        nodes, dup_counts = load_nodes(sys.stdin)
    else:
        try:
            with open(args.log, "r", encoding="utf-8") as handle:
                nodes, dup_counts = load_nodes(handle)
        except OSError as exc:
            print(f"Failed to read log: {exc}", file=sys.stderr)
            return 2

    if not nodes:
        print("No [Oracle] entries found.", file=sys.stderr)
        return 1

    name_regexes = []
    for pattern in args.name_regex:
        flags = re.IGNORECASE if args.ignore_case else 0
        name_regexes.append(re.compile(pattern, flags))

    starts = pick_starts(
        nodes,
        hop_ids=args.hop,
        names=args.name,
        name_regexes=name_regexes,
        ignore_case=args.ignore_case,
    )

    if not starts:
        print("No start hops matched.", file=sys.stderr)
        return 1

    if dup_counts:
        for hop_id, count in sorted(dup_counts.items()):
            print(
                f"Warning: hop {hop_id} appears {count} times; using first entry.",
                file=sys.stderr,
            )

    for start in sorted(starts, key=lambda n: n.hop_id):
        paths = trace_paths(
            nodes,
            start=start,
            max_depth=args.max_depth,
        )
        if not paths:
            continue
        filtered_paths: List[List[Tuple[Node, Node, Optional[str], Optional[int], List[str]]]] = []
        for path in paths:
            if not path:
                continue
            has_drop = any(is_none_type(step[1].fout_type) for step in path)
            if not has_drop:
                continue
            if args.continue_after_drop:
                pass
            elif args.show_recover:
                path = slice_path_for_recovery(path)
            else:
                path = slice_path_drop_only(path)
            filtered_paths.append(path)
        if not filtered_paths:
            continue

        print(
            f"Start hop {start.hop_id} ({start.op}) "
            f"line {start.line_no} foutType={start.fout_type} "
            f"exec={start.exec_type} placement={start.placement}"
        )
        label_paths = [build_path_labels(path) for path in filtered_paths]
        if args.tree:
            tree = build_tree(label_paths)
            print_tree(tree, prefix="  ")
        else:
            print_paths(label_paths)
        print()

    return 0


if __name__ == "__main__":
    sys.exit(main())
