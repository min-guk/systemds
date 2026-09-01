#!/usr/bin/env python3
"""Canonical exact-discovery authority envelope shared by all audit stages."""

from __future__ import annotations

import re
from typing import Any, Iterable, Mapping


EXACT_AUTHORITY_FIELDS = (
	"authoritySha256",
	"authorityTreeSha256",
	"treeChecksumSha256",
	"inventorySha256",
	"sourceReceiptSha256",
	"mainBuildWitnessSha256",
	"testBuildWitnessSha256",
)

RUN_MANIFEST_EXACT_AUTHORITY_FIELDS = {
	"exact_discovery_authority_sha256": "authoritySha256",
	"exact_discovery_authority_tree_sha256": "authorityTreeSha256",
	"exact_discovery_tree_checksum_sha256": "treeChecksumSha256",
	"exact_discovery_inventory_sha256": "inventorySha256",
	"exact_discovery_source_receipt_sha256": "sourceReceiptSha256",
	"exact_discovery_main_build_witness_sha256": "mainBuildWitnessSha256",
	"exact_discovery_test_build_witness_sha256": "testBuildWitnessSha256",
}


def normalize_exact_authority_envelope(authority: Any, *, label: str = "exact authority",
		expected_source_receipt_sha256: str | None = None) -> dict[str, str]:
	"""Validate the complete envelope and return it in canonical field order."""
	if not isinstance(authority, Mapping) or set(authority) != set(EXACT_AUTHORITY_FIELDS):
		raise ValueError(f"{label}: missing/invalid exactDiscoveryAuthority")
	normalized = {field: authority[field] for field in EXACT_AUTHORITY_FIELDS}
	if any(not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
		for value in normalized.values()):
		raise ValueError(f"{label}: malformed exact authority digest")
	if expected_source_receipt_sha256 is not None \
		and normalized["sourceReceiptSha256"] != expected_source_receipt_sha256:
		raise ValueError(f"{label}: exact discovery source authority differs from caller source receipt")
	return normalized


def validate_exact_authority_chain(builder_receipt: Any, manifest_rows: Iterable[Mapping[str, Any]],
		expected_authority: Any) -> dict[str, str]:
	"""Bind builder output and every forced row to caller-pinned authority."""
	expected = normalize_exact_authority_envelope(expected_authority, label="caller authority")
	builder = normalize_exact_authority_envelope(builder_receipt, label="manifest builder receipt")
	if builder != expected:
		raise ValueError("manifest builder exact-discovery authority differs from caller authority")
	for index, row in enumerate(manifest_rows, 1):
		authority = normalize_exact_authority_envelope(
			row.get("exactDiscoveryAuthority"), label=f"row {index}")
		if authority != expected:
			raise ValueError(f"row {index}: exact-discovery authority differs from caller authority")
	return expected
