# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

"""Verified HDFS archive tier for locally committed campaign evidence."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import tarfile
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Mapping, Protocol, Sequence, cast

from scripts.federated_campaign.atomic_ledger import (
	AtomicEvidenceLedger,
	AttemptLease,
	DiscoveryKey,
	LedgerContractError,
	LedgerKey,
	PerformanceKey,
	ResumeDecision,
	ResumeState,
)
from scripts.federated_campaign.determinism_contract import (
	CampaignContractError,
	CAMPAIGN_PLANNERS,
	PILOT_CLASSES,
	PILOT_REGIMES,
	PILOT_REPRESENTATIVE_WORKLOADS,
	campaign_cell_ids,
	build_canonical_discovery_invocation,
	build_canonical_final_invocation,
	build_canonical_pilot_invocation,
	build_discovery_completion_receipt,
	build_campaign_manifest,
	_build_final_campaign_manifest,
	build_frozen_manifest,
	_build_pilot_resource_reservation,
	select_pilot_repeats,
	select_campaign_pilot_repeats,
	validate_campaign_preregistration_manifest,
	validate_discovery_completion_receipt,
	validate_final_campaign_manifest,
	validate_block_counterbalanced_schedule,
)


class ArchiveContractError(RuntimeError):
	"""Raised when archive or host-readiness evidence is unsafe."""


class InjectedArchiveCrash(RuntimeError):
	"""Test-only archive crash at an explicit durable boundary."""


ARCHIVE_BOUNDARIES = (
	"after_archive_tar_fsync",
	"after_archive_put",
	"after_archive_rename",
	"after_archive_download_verify",
	"after_archive_catalog_replace",
)

ABSOLUTE_DISK_FLOOR_BYTES = 5 * 1024**3


class ArchiveBackend(Protocol):
	def mkdirs(self, remote_uri: str) -> None: ...
	def put(self, local_path: Path, remote_uri: str) -> None: ...
	def rename(self, source_uri: str, destination_uri: str) -> None: ...
	def get(self, remote_uri: str, local_path: Path) -> None: ...
	def exists(self, remote_uri: str) -> bool: ...
	def delete(self, remote_uri: str) -> None: ...


class HdfsCliBackend:
	"""Minimal command backend; callers inject a fake in unit tests."""

	def __init__(self, executable: str = "hdfs"):
		self.executable = executable

	def _run(self, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
		return subprocess.run(
			[self.executable, "dfs", *arguments], check=check, text=True, capture_output=True
		)

	def mkdirs(self, remote_uri: str) -> None:
		self._run("-mkdir", "-p", remote_uri)

	def put(self, local_path: Path, remote_uri: str) -> None:
		self._run("-put", str(local_path), remote_uri)

	def rename(self, source_uri: str, destination_uri: str) -> None:
		self._run("-mv", source_uri, destination_uri)

	def get(self, remote_uri: str, local_path: Path) -> None:
		self._run("-get", remote_uri, str(local_path))

	def exists(self, remote_uri: str) -> bool:
		result = self._run("-test", "-e", remote_uri, check=False)
		if result.returncode == 0:
			return True
		if result.returncode == 1 and not result.stdout.strip() and not result.stderr.strip():
			return False
		detail = result.stderr.strip() or result.stdout.strip() or "no diagnostic output"
		raise ArchiveContractError(
			f"HDFS existence check failed for {remote_uri} (rc={result.returncode}): {detail}"
		)

	def delete(self, remote_uri: str) -> None:
		self._run("-rm", "-f", remote_uri)


@dataclass(frozen=True)
class HostResourceSnapshot:
	free_bytes: int
	free_inodes: int
	remaining_seconds: float
	io_utilization: float
	read_bytes_per_second: float
	write_bytes_per_second: float


def _receipt_attempt(receipt: Mapping[str, object]) -> int:
	identity = receipt.get("identity")
	if not isinstance(identity, dict):
		raise ArchiveContractError("archive receipt identity is invalid")
	attempt = identity.get("attempt")
	if isinstance(attempt, bool) or not isinstance(attempt, int) or attempt < 1:
		raise ArchiveContractError("archive receipt attempt is invalid")
	return attempt


class HdfsArchiveAdapter:
	"""Archives complete local rows, verifies remote bytes, then bounds retention."""

	def __init__(
		self,
		ledger: AtomicEvidenceLedger,
		backend: ArchiveBackend,
		work_root: Path,
		remote_base_uri: str,
		*,
		max_local_raw_bundles: int,
	):
		if not isinstance(max_local_raw_bundles, int) or isinstance(max_local_raw_bundles, bool) or max_local_raw_bundles < 0:
			raise ArchiveContractError("max_local_raw_bundles must be a non-negative integer")
		if "/tmp/logs/mchoi-g007-" not in remote_base_uri or ".." in remote_base_uri:
			raise ArchiveContractError("remote archive must use a unique /tmp/logs/mchoi-g007-<campaign> base")
		self.ledger = ledger
		self.backend = backend
		self.work_root = Path(work_root)
		self.work_root.mkdir(parents=True, exist_ok=True)
		self.catalog_path = self.work_root / "archive_catalog.json"
		self.remote_base_uri = remote_base_uri.rstrip("/")
		self.max_local_raw_bundles = max_local_raw_bundles
		self.__receipt_anchor_index_cache: tuple[
			tuple[int, int, int, int] | None,
			dict[bytes, dict[str, object] | None],
		] | None = None

	def archive(self, committed_path: Path, *, crash_after: str | None = None) -> dict[str, object]:
		committed = Path(committed_path)
		if crash_after is not None and crash_after not in ARCHIVE_BOUNDARIES:
			raise ArchiveContractError(f"unknown archive crash boundary: {crash_after}")
		try:
			manifest = self.ledger.validate_committed(committed, require_success=False)
		except LedgerContractError as error:
			raise ArchiveContractError(f"local commit validation failed: {committed}") from error
		identity_value = manifest.get("identity")
		if not isinstance(identity_value, dict):
			raise ArchiveContractError("local commit manifest identity is invalid")
		identity = cast(dict[str, object], identity_value)
		resource_evidence = manifest.get("resource_evidence")
		if identity.get("kind") == "performance" and manifest.get("status") == "success":
			resource_evidence = self._validate_resource_evidence(resource_evidence)
		elif resource_evidence is not None:
			raise ArchiveContractError("non-performance archive must not claim performance resource evidence")
		record_id = committed.name
		for receipt in self.catalog():
			if receipt.get("identity") == identity:
				self._verify_receipt(receipt)
				self._enforce_retention()
				return next(entry for entry in self.catalog() if entry.get("identity") == identity)

		operation = uuid.uuid4().hex
		archive_path = self.work_root / f"archive-{record_id}-{operation}.tar"
		download_path = self.work_root / f"verify-{record_id}-{operation}.tar"
		remote_stage = f"{self.remote_base_uri}/.staging/{record_id}.tar"
		remote_final = f"{self.remote_base_uri}/committed/{record_id}.tar"
		try:
			self._create_deterministic_tar(committed, archive_path)
			self._crash(crash_after, "after_archive_tar_fsync")
			archive_hash = _sha256(archive_path)
			archive_bytes = archive_path.stat().st_size
			self.backend.mkdirs(f"{self.remote_base_uri}/.staging")
			self.backend.mkdirs(f"{self.remote_base_uri}/committed")
			if not self.backend.exists(remote_final):
				if self.backend.exists(remote_stage):
					stage_download = self.work_root / f"stage-verify-{record_id}-{operation}.tar"
					try:
						self.backend.get(remote_stage, stage_download)
						if _sha256(stage_download) != archive_hash:
							raise ArchiveContractError(f"archive staging-object conflict: {remote_stage}")
					finally:
						stage_download.unlink(missing_ok=True)
				else:
					self.backend.put(archive_path, remote_stage)
					self._crash(crash_after, "after_archive_put")
				self.backend.rename(remote_stage, remote_final)
				self._crash(crash_after, "after_archive_rename")
			self.backend.get(remote_final, download_path)
			if _sha256(download_path) != archive_hash:
				raise ArchiveContractError(f"archive final-object conflict: {remote_final}")
			self._verify_downloaded_archive(download_path, identity, record_id)
			self._crash(crash_after, "after_archive_download_verify")
		except (ArchiveContractError, InjectedArchiveCrash):
			raise
		except Exception as error:
			raise ArchiveContractError(f"archive transport failed for {record_id}") from error
		finally:
			archive_path.unlink(missing_ok=True)
			download_path.unlink(missing_ok=True)

		receipt: dict[str, object] = {
			"schema": "systemds-federated-hdfs-archive/v1",
			"identity": identity,
			"archive_uri": remote_final,
			"archive_sha256": archive_hash,
			"archive_bytes": archive_bytes,
			"local_committed_path": str(committed),
			"local_raw_bundle_present": True,
			"status": manifest.get("status"),
			"resource_evidence": resource_evidence,
		}
		receipt["receipt_sha256"] = hashlib.sha256(_canonical_bytes(receipt)).hexdigest()
		try:
			entries = self.catalog()
			entries.append(receipt)
			self._replace_catalog(entries)
			self._crash(crash_after, "after_archive_catalog_replace")
			self._enforce_retention()
		except (ArchiveContractError, InjectedArchiveCrash):
			raise
		except Exception as error:
			raise ArchiveContractError(f"archive catalog publication failed for {record_id}") from error
		return next(entry for entry in self.catalog() if entry.get("identity") == identity)

	def catalog(self) -> list[dict[str, object]]:
		if not self.catalog_path.is_file():
			return []
		try:
			value = json.loads(self.catalog_path.read_text(encoding="utf-8"))
		except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
			raise ArchiveContractError("local archive catalog is corrupt") from error
		if not isinstance(value, dict) or value.get("schema") != "systemds-federated-hdfs-catalog/v1":
			raise ArchiveContractError("local archive catalog schema is invalid")
		entries = value.get("entries")
		if not isinstance(entries, list) or any(not isinstance(entry, dict) for entry in entries):
			raise ArchiveContractError("local archive catalog entries are invalid")
		return [dict(entry) for entry in entries]

	def latest_discovery_success(self, cell: str, manifest_hash: str) -> dict[str, object] | None:
		candidates = []
		local_records = self.ledger.record_summaries()
		if any(
			record.get("identity") == {}
			for record in local_records
		):
			return None
		for record in local_records:
			identity = record.get("identity")
			if (
				isinstance(identity, dict)
				and identity.get("kind") == "discovery"
				and identity.get("cell") == cell
				and identity.get("manifest_hash") == manifest_hash
			):
				candidates.append((int(identity["attempt"]), "local", record))
		for receipt in self.catalog():
			identity = receipt.get("identity")
			if (
				isinstance(identity, dict)
				and identity.get("kind") == "discovery"
				and identity.get("cell") == cell
				and identity.get("manifest_hash") == manifest_hash
			):
				candidates.append((int(identity["attempt"]), "archive", receipt))
		if not candidates:
			return None
		latest_attempt = max(candidate[0] for candidate in candidates)
		latest = [candidate for candidate in candidates if candidate[0] == latest_attempt]
		local = next((candidate[2] for candidate in latest if candidate[1] == "local"), None)
		if local is not None:
			if local.get("status") != "success" or local.get("valid") is not True:
				return None
			return self.ledger.latest_discovery_success(cell, manifest_hash)
		if len(latest) != 1:
			raise ArchiveContractError("ambiguous archived latest discovery attempt")
		receipt = latest[0][2]
		manifest = self._verify_receipt(receipt)
		if manifest.get("status") != "success" or cast(dict[str, object], manifest.get("identity", {})).get("kind") != "discovery":
			return None
		manifest["archive_uri"] = receipt["archive_uri"]
		manifest["archive_sha256"] = receipt["archive_sha256"]
		return manifest

	def performance_success(self, key: PerformanceKey) -> dict[str, object] | None:
		identity = key.as_dict()
		local_records = self.ledger.record_summaries()
		if any(record.get("identity") == {} for record in local_records):
			return None
		local = [record for record in local_records if record.get("identity") == identity]
		if local:
			if len(local) != 1 or local[0].get("status") != "success" or local[0].get("valid") is not True:
				return None
			return self.ledger.performance_success(key)
		archived = [receipt for receipt in self.catalog() if receipt.get("identity") == identity]
		if not archived:
			return None
		if len(archived) != 1:
			raise ArchiveContractError("ambiguous archived performance identity")
		receipt = archived[0]
		manifest = self._verify_receipt(receipt)
		if manifest.get("status") != "success" or manifest.get("identity") != identity:
			return None
		manifest["archive_uri"] = receipt["archive_uri"]
		manifest["archive_sha256"] = receipt["archive_sha256"]
		return manifest

	def exact_resume(self, key: DiscoveryKey | PerformanceKey) -> ResumeDecision:
		identity = key.as_dict()
		if isinstance(key, DiscoveryKey):
			local = self.ledger.resume_discovery(key.cell, key.manifest_hash)
			base_names = ("kind", "cell", "manifest_hash")
		else:
			local = self.ledger.resume_performance(key)
			base_names = ("kind", "cell", "lifecycle_replicate", "period", "order", "manifest_hash")
		base = {name: identity[name] for name in base_names}
		if local.state is ResumeState.CORRUPT_OR_AMBIGUOUS:
			return local
		archived = [
			receipt for receipt in self.catalog()
			if isinstance(receipt.get("identity"), dict)
			and all(cast(dict[str, object], receipt["identity"]).get(name) == value for name, value in base.items())
		]
		archive_attempts = [_receipt_attempt(receipt) for receipt in archived]
		latest_attempt = max([attempt for attempt in (local.attempt, *archive_attempts) if attempt is not None], default=None)
		if latest_attempt is None:
			return self._require_v2_resume(local)
		latest_archived = [
			receipt for receipt in archived
			if _receipt_attempt(receipt) == latest_attempt
		]
		if not latest_archived:
			return self._require_v2_resume(local)
		if len(latest_archived) != 1:
			return ResumeDecision(
				ResumeState.CORRUPT_OR_AMBIGUOUS, latest_attempt,
				detail="duplicate archived receipts for the same latest attempt",
			)
		try:
			archive_manifest = self._verify_receipt(latest_archived[0])
		except ArchiveContractError as error:
			return ResumeDecision(ResumeState.CORRUPT_OR_AMBIGUOUS, latest_attempt, detail=str(error))
		if local.attempt == latest_attempt and local.evidence is not None:
			committed_path = local.evidence.get("committed_path")
			if not isinstance(committed_path, str):
				return ResumeDecision(ResumeState.CORRUPT_OR_AMBIGUOUS, latest_attempt, detail="local evidence has no committed path")
			try:
				local_manifest = self.ledger.validate_committed(Path(committed_path), require_success=False)
			except LedgerContractError as error:
				return ResumeDecision(ResumeState.CORRUPT_OR_AMBIGUOUS, latest_attempt, detail=f"local evidence validation failed: {error}")
			if local_manifest != archive_manifest:
				return ResumeDecision(ResumeState.CORRUPT_OR_AMBIGUOUS, latest_attempt, detail="local/archive same-attempt disagreement")
		if local.attempt == latest_attempt and local.state in (
			ResumeState.IN_PROGRESS_OR_ABANDONED,
			ResumeState.LATEST_FAILED,
			ResumeState.CORRUPT_OR_AMBIGUOUS,
		):
			return self._require_v2_resume(local)
		manifest = archive_manifest
		manifest["archive_uri"] = latest_archived[0]["archive_uri"]
		manifest["archive_sha256"] = latest_archived[0]["archive_sha256"]
		if not self._is_v2_manifest(manifest):
			return ResumeDecision(ResumeState.CORRUPT_OR_AMBIGUOUS, latest_attempt, detail="legacy v1 archive cannot satisfy v2 resume")
		state = ResumeState.LATEST_SUCCESS if manifest.get("status") == "success" else ResumeState.LATEST_FAILED
		return ResumeDecision(state, latest_attempt, manifest)

	@staticmethod
	def _is_v2_manifest(manifest: Mapping[str, object]) -> bool:
		identity = manifest.get("identity")
		base_valid = (
			manifest.get("schema") == "systemds-federated-evidence/v2"
			and isinstance(identity, dict)
			and manifest.get("evidence_kind") == identity.get("kind")
			and isinstance(manifest.get("invocation_manifest_sha256"), str)
		)
		if not base_valid:
			return False
		if cast(dict[str, object], identity).get("kind") == "performance" and manifest.get("status") == "success":
			try:
				HdfsArchiveAdapter._validate_resource_evidence(manifest.get("resource_evidence"))
			except ArchiveContractError:
				return False
		return True

	def _require_v2_resume(self, decision: ResumeDecision) -> ResumeDecision:
		if decision.state not in (ResumeState.LATEST_SUCCESS, ResumeState.LATEST_FAILED):
			return decision
		if decision.evidence is None or not self._is_v2_manifest(decision.evidence):
			return ResumeDecision(
				ResumeState.CORRUPT_OR_AMBIGUOUS, decision.attempt,
				detail="legacy v1 evidence cannot satisfy campaign v2 resume",
			)
		return decision

	def preflight_next_lifecycle(
		self,
		snapshot_provider: Callable[[], HostResourceSnapshot],
		*,
		required_free_bytes: int,
		required_free_inodes: int,
		required_seconds: float,
		max_io_utilization: float,
		max_combined_io_bps: float,
	) -> HostResourceSnapshot:
		# Publication, exact-resume/barrier checks, and the final seal retain full
		# remote round-trip verification.  The per-cell gate only validates the
		# signed local catalog; re-downloading every prior archive here is O(n^2).
		for receipt in self.catalog():
			_ = self._validate_receipt_metadata(receipt)
		if isinstance(required_free_bytes, bool) or not isinstance(required_free_bytes, int) or required_free_bytes <= 0:
			raise ArchiveContractError("required free bytes must be a positive integer")
		if isinstance(required_free_inodes, bool) or not isinstance(required_free_inodes, int) or required_free_inodes <= 0:
			raise ArchiveContractError("required free inodes must be a positive integer")
		for name, value in (
			("required seconds", required_seconds),
			("maximum I/O utilization", max_io_utilization),
			("maximum combined I/O throughput", max_combined_io_bps),
		):
			if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
				raise ArchiveContractError(f"{name} must be positive and finite")
		if max_io_utilization > 1:
			raise ArchiveContractError("maximum I/O utilization must be in (0, 1]")
		snapshot = snapshot_provider()
		if (
			isinstance(snapshot.free_bytes, bool) or not isinstance(snapshot.free_bytes, int) or snapshot.free_bytes < 0
			or isinstance(snapshot.free_inodes, bool) or not isinstance(snapshot.free_inodes, int) or snapshot.free_inodes < 0
		):
			raise ArchiveContractError("host disk snapshot is invalid")
		for name, value in (
			("remaining seconds", snapshot.remaining_seconds),
			("I/O utilization", snapshot.io_utilization),
			("read throughput", snapshot.read_bytes_per_second),
			("write throughput", snapshot.write_bytes_per_second),
		):
			if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
				raise ArchiveContractError(f"host {name} snapshot is invalid")
		if snapshot.io_utilization > 1:
			raise ArchiveContractError("host I/O utilization snapshot must be in [0, 1]")
		if snapshot.free_bytes < max(required_free_bytes, ABSOLUTE_DISK_FLOOR_BYTES):
			raise ArchiveContractError("host free-byte resource gate failed")
		if snapshot.free_inodes < required_free_inodes:
			raise ArchiveContractError("host inode resource gate failed")
		if not math.isfinite(snapshot.remaining_seconds) or snapshot.remaining_seconds < required_seconds:
			raise ArchiveContractError("host remaining-time resource gate failed")
		combined_io = snapshot.read_bytes_per_second + snapshot.write_bytes_per_second
		if (
			not math.isfinite(snapshot.io_utilization)
			or snapshot.io_utilization > max_io_utilization
			or not math.isfinite(combined_io)
			or combined_io > max_combined_io_bps
		):
			raise ArchiveContractError("host I/O quiescence gate failed")
		return snapshot

	@staticmethod
	def _validate_receipt_metadata(
		receipt: dict[str, object],
	) -> tuple[str, str, dict[str, object], int]:
		expected_fields = {
			"schema", "identity", "archive_uri", "archive_sha256", "archive_bytes", "local_committed_path",
			"local_raw_bundle_present", "status", "resource_evidence", "receipt_sha256",
		}
		if set(receipt) != expected_fields or receipt.get("schema") != "systemds-federated-hdfs-archive/v1":
			raise ArchiveContractError("archive receipt schema is not exact")
		receipt_hash = receipt.get("receipt_sha256")
		unsigned = dict(receipt)
		unsigned.pop("receipt_sha256")
		if (
			not isinstance(receipt_hash, str) or re.fullmatch(r"[0-9a-f]{64}", receipt_hash) is None
			or hashlib.sha256(_canonical_bytes(unsigned)).hexdigest() != receipt_hash
		):
			raise ArchiveContractError("archive receipt checksum is invalid")
		uri = receipt.get("archive_uri")
		expected_hash = receipt.get("archive_sha256")
		identity = receipt.get("identity")
		archive_bytes = receipt.get("archive_bytes")
		if (
			not isinstance(uri, str) or not isinstance(expected_hash, str) or not isinstance(identity, dict)
			or type(archive_bytes) is not int or cast(int, archive_bytes) < 1
			or re.fullmatch(r"[0-9a-f]{64}", expected_hash) is None
			or type(receipt.get("local_raw_bundle_present")) is not bool
			or receipt.get("status") not in ("success", "failed")
			or not isinstance(receipt.get("local_committed_path"), str)
		):
			raise ArchiveContractError("archive receipt is invalid")
		return uri, expected_hash, cast(dict[str, object], identity), cast(int, archive_bytes)

	def _verify_receipt(self, receipt: dict[str, object]) -> dict[str, object]:
		uri, expected_hash, identity, archive_bytes = self._validate_receipt_metadata(receipt)
		if not self.backend.exists(uri):
			raise ArchiveContractError(f"remote archive is missing: {uri}")
		download = self.work_root / f"receipt-verify-{uuid.uuid4().hex}.tar"
		try:
			self.backend.get(uri, download)
			if download.stat().st_size != archive_bytes:
				raise ArchiveContractError(f"remote archive size mismatch: {uri}")
			if _sha256(download) != expected_hash:
				raise ArchiveContractError(f"remote archive checksum mismatch: {uri}")
			record_id = hashlib.sha256(_canonical_bytes(identity)).hexdigest()
			manifest = self._verify_downloaded_archive(download, identity, record_id)
			if receipt.get("status") != manifest.get("status"):
				raise ArchiveContractError("archive receipt status disagrees with archived manifest")
			manifest_resource = manifest.get("resource_evidence")
			if receipt.get("resource_evidence") != manifest_resource:
				raise ArchiveContractError("archive receipt resource evidence disagrees with archived manifest")
			if manifest_resource is not None:
				self._validate_resource_evidence(manifest_resource)
			return manifest
		except ArchiveContractError:
			raise
		except Exception as error:
			raise ArchiveContractError(f"remote archive verification failed: {uri}") from error
		finally:
			download.unlink(missing_ok=True)

	def _receipt_scope_digest(self, identities: Sequence[Mapping[str, object]]) -> str | None:
		"""Hash immutable receipt facts for an already fully verified evidence scope.

		Local retention fields and the receipt checksum legitimately change when a
		raw bundle is evicted.  Remote identity, URI, content hash, byte length,
		status, and resource evidence do not.  A missing or duplicate archived
		identity cannot satisfy a cached scope.
		"""
		index = self.__receipt_anchor_index()
		anchors: list[dict[str, object]] = []
		for requested in identities:
			identity = dict(requested)
			anchor = index.get(_canonical_bytes(identity))
			if anchor is None:
				return None
			if anchor.get("identity") != identity:
				return None
			anchors.append(anchor)
		return hashlib.sha256(_canonical_bytes(anchors)).hexdigest()

	def __catalog_version(self) -> tuple[int, int, int, int] | None:
		try:
			stat = self.catalog_path.stat()
		except FileNotFoundError:
			return None
		return stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns

	def __receipt_anchor_index(self) -> dict[bytes, dict[str, object] | None]:
		for _ in range(3):
			before = self.__catalog_version()
			cached = self.__receipt_anchor_index_cache
			if cached is not None and cached[0] == before:
				return cached[1]
			entries = self.catalog()
			after = self.__catalog_version()
			if before != after:
				continue
			index: dict[bytes, dict[str, object] | None] = {}
			for receipt in entries:
				uri, archive_hash, identity, archive_bytes = self._validate_receipt_metadata(receipt)
				identity_key = _canonical_bytes(identity)
				anchor: dict[str, object] = {
					"identity": identity,
					"archive_uri": uri,
					"archive_sha256": archive_hash,
					"archive_bytes": archive_bytes,
					"status": receipt.get("status"),
					"resource_evidence": receipt.get("resource_evidence"),
				}
				index[identity_key] = None if identity_key in index else anchor
			self.__receipt_anchor_index_cache = (after, index)
			return index
		raise ArchiveContractError("archive catalog changed while building verified receipt index")

	def _verify_downloaded_archive(
		self, archive_path: Path, identity: dict[str, object], record_id: str
	) -> dict[str, object]:
		extract_root = self.work_root / f"extract-{uuid.uuid4().hex}"
		extract_root.mkdir()
		try:
			with tarfile.open(archive_path, "r") as archive:
				archive.extractall(extract_root, filter="data")
			record_dir = extract_root / str(identity["kind"]) / record_id
			manifest = self.ledger.validate_committed(record_dir, require_success=False)
			if manifest.get("identity") != identity:
				raise ArchiveContractError("downloaded archive identity mismatch")
			return manifest
		except (tarfile.TarError, LedgerContractError, KeyError) as error:
			raise ArchiveContractError("downloaded archive manifest/checksum validation failed") from error
		finally:
			shutil.rmtree(extract_root, ignore_errors=True)

	@staticmethod
	def _validate_resource_evidence(value: object) -> dict[str, float | int]:
		if not isinstance(value, dict) or set(value) != {
			"artifact_bytes", "artifact_inodes", "lifecycle_wall_seconds",
		}:
			raise ArchiveContractError("archive resource evidence schema is not exact")
		for name in ("artifact_bytes", "artifact_inodes"):
			if type(value[name]) is not int or cast(int, value[name]) < 1:
				raise ArchiveContractError(f"archive resource evidence {name} is invalid")
		wall = value["lifecycle_wall_seconds"]
		if isinstance(wall, bool) or not isinstance(wall, (int, float)) or not math.isfinite(wall) or wall <= 0:
			raise ArchiveContractError("archive resource evidence lifecycle wall time is invalid")
		return {
			"artifact_bytes": cast(int, value["artifact_bytes"]),
			"artifact_inodes": cast(int, value["artifact_inodes"]),
			"lifecycle_wall_seconds": float(wall),
		}

	@staticmethod
	def _crash(requested: str | None, boundary: str) -> None:
		if requested == boundary:
			raise InjectedArchiveCrash(boundary)

	def _create_deterministic_tar(self, record_dir: Path, destination: Path) -> None:
		arc_root = Path(record_dir.parent.name) / record_dir.name
		with tarfile.open(destination, "w") as archive:
			for path in (record_dir, *sorted(record_dir.rglob("*"))):
				if path.is_symlink():
					raise ArchiveContractError(f"archive input contains symlink: {path}")
				relative = Path() if path == record_dir else path.relative_to(record_dir)
				name = (arc_root / relative).as_posix()
				info = tarfile.TarInfo(name=name)
				info.uid = info.gid = 0
				info.uname = info.gname = ""
				info.mtime = 0
				info.mode = 0o755 if path.is_dir() else 0o644
				if path.is_dir():
					info.type = tarfile.DIRTYPE
					archive.addfile(info)
				elif path.is_file():
					info.size = path.stat().st_size
					with path.open("rb") as handle:
						archive.addfile(info, handle)
				else:
					raise ArchiveContractError(f"archive input is not a regular file/directory: {path}")
		with destination.open("rb") as handle:
			os.fsync(handle.fileno())

	def _enforce_retention(self) -> None:
		entries = self.catalog()
		local_entries = [entry for entry in entries if entry.get("local_raw_bundle_present") is True]
		while len(local_entries) > self.max_local_raw_bundles:
			receipt = local_entries.pop(0)
			self._verify_receipt(receipt)
			local_path = self._validated_eviction_path(receipt)
			if local_path.is_dir():
				shutil.rmtree(local_path)
			receipt["local_raw_bundle_present"] = False
			receipt.pop("receipt_sha256", None)
			receipt["receipt_sha256"] = hashlib.sha256(_canonical_bytes(receipt)).hexdigest()
			for index, entry in enumerate(entries):
				if entry.get("archive_uri") == receipt.get("archive_uri"):
					entries[index] = receipt
					break
			self._replace_catalog(entries)
			self.ledger.reconcile_index()

	def _validated_eviction_path(self, receipt: dict[str, object]) -> Path:
		identity = receipt.get("identity")
		if not isinstance(identity, dict) or identity.get("kind") not in ("discovery", "performance"):
			raise ArchiveContractError("archive receipt identity cannot derive a committed path")
		record_id = hashlib.sha256(_canonical_bytes(identity)).hexdigest()
		committed_root = self.ledger.committed.resolve()
		expected_raw = self.ledger.committed / str(identity["kind"]) / record_id
		local_raw = Path(str(receipt.get("local_committed_path", "")))
		if local_raw.is_symlink() or expected_raw.is_symlink():
			raise ArchiveContractError("archive receipt local committed path is a symlink")
		expected = expected_raw.resolve()
		local_path = local_raw.resolve()
		if not expected.is_relative_to(committed_root) or local_path != expected:
			raise ArchiveContractError("archive receipt local committed path is outside its identity-derived path")
		return local_path

	def _replace_catalog(self, entries: list[dict[str, object]]) -> None:
		value = {"schema": "systemds-federated-hdfs-catalog/v1", "entries": entries}
		temp = self.work_root / f".archive-catalog-{uuid.uuid4().hex}.tmp"
		with temp.open("xb") as handle:
			handle.write(_canonical_bytes(value) + b"\n")
			handle.flush()
			os.fsync(handle.fileno())
		os.replace(temp, self.catalog_path)
		_fsync_directory(self.work_root)
		self.__receipt_anchor_index_cache = None


class CampaignHarnessAdapter:
	"""The sole typed lifecycle surface used by the Docker campaign driver."""

	integration_operations: frozenset[str] = frozenset({
		"begin_discovery",
		"begin_pilot",
		"begin_final_performance",
		"publish_discovery_success",
		"publish_performance_success",
		"publish_failure",
		"archive",
		"exact_resume",
		"select_pilot_repeats",
		"normalize_resume_row",
		"assert_planner_barrier",
		"complete_discovery",
		"build_pilot_resource_reservation",
		"build_final_campaign_manifest",
		"preflight",
	})

	def __init__(self, ledger: AtomicEvidenceLedger, archive: HdfsArchiveAdapter):
		if archive.ledger is not ledger:
			raise ArchiveContractError("harness adapter ledger/archive mismatch")
		self._ledger = ledger
		self._archive = archive
		self.__pending_allocation_authorities: dict[str, dict[str, object]] = {}
		self.__planner_barrier_cache: dict[
			tuple[str, str], tuple[tuple[dict[str, object], ...], str, dict[str, object]]
		] = {}
		try:
			self._ledger._bind_typed_allocation_validator(self.__consume_allocation_authority)
		except LedgerContractError as error:
			raise ArchiveContractError(str(error)) from error

	def begin(self, **_ignored: object) -> AttemptLease:
		raise ArchiveContractError(
			"generic begin is deprecated; use begin_discovery, begin_pilot, or begin_final_performance"
		)

	def begin_discovery(
		self, *, preregistration_manifest: Mapping[str, object], cell: str,
		crash_after: str | None = None,
	) -> AttemptLease:
		"""Allocate one canonical discovery cell with identity derived solely from P."""
		try:
			prereg = validate_campaign_preregistration_manifest(preregistration_manifest)
			invocation = build_canonical_discovery_invocation(prereg, cell)
		except CampaignContractError as error:
			raise ArchiveContractError(str(error)) from error
		manifest_hash = cast(str, prereg["preregistration_manifest_sha256"])
		planner = next(name for name in CAMPAIGN_PLANNERS if f"planner={name}|" in cell)
		planner_index = CAMPAIGN_PLANNERS.index(planner)
		if planner_index > 0:
			self.assert_planner_barrier(CAMPAIGN_PLANNERS[planner_index - 1], manifest_hash)
		minimum_attempt = self._minimum_archive_attempt("discovery", cell, manifest_hash, None, None, None)
		request: dict[str, object] = {
			"kind": "discovery", "cell": cell, "manifest_hash": manifest_hash,
			"invocation_manifest": invocation, "lifecycle_replicate": None, "period": None, "order": None,
			"minimum_attempt": minimum_attempt, "crash_after": crash_after,
		}
		nonce = uuid.uuid4().hex
		authority: dict[str, object] = {"nonce": nonce, "phase": "discovery", "evidence_roots": {"P": manifest_hash}, "request": request}
		self.__pending_allocation_authorities[nonce] = cast(dict[str, object], json.loads(json.dumps(authority)))
		return self._ledger._begin_attempt_from_adapter(
			kind="discovery", cell=cell, manifest_hash=manifest_hash, invocation_manifest=invocation,
			minimum_attempt=minimum_attempt, crash_after=crash_after, _allocation_authority=authority,
		)

	def begin_pilot(
		self, *, pilot_class: str, planner: str, workers: int, profile: str, pilot_repeat: int,
		preregistration_manifest_sha256: str, discovery_completion_receipt: Mapping[str, object],
		crash_after: str | None = None,
	) -> AttemptLease:
		"""Allocate one canonical pilot attempt only after live D revalidation."""
		if pilot_class not in PILOT_CLASSES or planner not in CAMPAIGN_PLANNERS:
			raise ArchiveContractError("pilot class/planner is not canonical")
		if type(pilot_class) is not str or type(planner) is not str or type(profile) is not str or type(preregistration_manifest_sha256) is not str:
			raise ArchiveContractError("pilot string identity requires exact built-in strings")
		if type(workers) is not int or (workers, profile) not in PILOT_REGIMES:
			raise ArchiveContractError("pilot worker/profile regime is not canonical")
		if type(pilot_repeat) is not int or pilot_repeat not in range(1, 6):
			raise ArchiveContractError("pilot repeat must be exact integer 1..5")
		try:
			completion = validate_discovery_completion_receipt(
				discovery_completion_receipt, evidence_validator=self._verify_discovery_completion_row,
			)
		except CampaignContractError as error:
			raise ArchiveContractError(str(error)) from error
		if completion["preregistration_manifest_sha256"] != preregistration_manifest_sha256:
			raise ArchiveContractError("pilot attempt D does not bind exact preregistration")
		workload = PILOT_REPRESENTATIVE_WORKLOADS[pilot_class]
		cell = f"pilot_class={pilot_class}|workload={workload}|planner={planner}|workers={workers}|profile={profile}"
		try:
			invocation_manifest = build_canonical_pilot_invocation(
				preregistration_manifest_sha256=preregistration_manifest_sha256,
				discovery_completion_sha256=cast(str, completion["discovery_completion_sha256"]),
				pilot_class=pilot_class, planner=planner, workers=workers, profile=profile,
				pilot_repeat=pilot_repeat,
			)
		except CampaignContractError as error:
			raise ArchiveContractError(str(error)) from error
		period = cast(int, invocation_manifest["period"])
		order = cast(str, invocation_manifest["order"])
		minimum_attempt = self._minimum_archive_attempt(
			"performance", cell, preregistration_manifest_sha256, pilot_repeat, period, order,
		)
		request: dict[str, object] = {
			"kind": "performance", "cell": cell, "manifest_hash": preregistration_manifest_sha256,
			"invocation_manifest": invocation_manifest, "lifecycle_replicate": pilot_repeat,
			"period": period, "order": order, "minimum_attempt": minimum_attempt, "crash_after": crash_after,
		}
		nonce = uuid.uuid4().hex
		authority: dict[str, object] = {
			"nonce": nonce, "phase": "pilot",
			"evidence_roots": {"P": preregistration_manifest_sha256, "D": completion["discovery_completion_sha256"]},
			"request": request,
		}
		self.__pending_allocation_authorities[nonce] = cast(dict[str, object], json.loads(json.dumps(authority)))
		return self._ledger._begin_attempt_from_adapter(
			kind="performance", cell=cell, manifest_hash=preregistration_manifest_sha256,
			invocation_manifest=invocation_manifest, lifecycle_replicate=pilot_repeat, period=period, order=order,
			minimum_attempt=minimum_attempt, crash_after=crash_after, _allocation_authority=authority,
		)

	def begin_final_performance(
		self, *, preregistration_manifest: Mapping[str, object],
		discovery_completion_receipt: Mapping[str, object],
		pilot_selection_receipt: Mapping[str, object],
		pilot_resource_reservation: Mapping[str, object],
		final_campaign_manifest: Mapping[str, object], cell: str,
		lifecycle_replicate: int, period: int, order: str, crash_after: str | None = None,
	) -> AttemptLease:
		"""Rebuild F from live-validated P/D/S/R, then allocate its exact schedule identity."""
		try:
			manifest = _build_final_campaign_manifest(
				preregistration_manifest=preregistration_manifest,
				pilot_selection_receipt=pilot_selection_receipt,
				pilot_resource_reservation=pilot_resource_reservation,
				discovery_completion_receipt=discovery_completion_receipt,
				pilot_evidence_validator=self._verify_pilot_row,
				discovery_evidence_validator=self._verify_discovery_completion_row,
			)
			provided = validate_final_campaign_manifest(final_campaign_manifest)
			if provided != manifest:
				raise CampaignContractError("final campaign manifest is not the live canonical P/D/S/R derivation")
			invocation = build_canonical_final_invocation(manifest, cell, lifecycle_replicate)
		except CampaignContractError as error:
			raise ArchiveContractError(str(error)) from error
		if type(period) is not int or period != invocation["period"]:
			raise ArchiveContractError("final performance period does not match F schedule")
		if type(order) is not str or order != invocation["order"]:
			raise ArchiveContractError("final performance order does not match F schedule")
		manifest_hash = cast(str, manifest["manifest_hash"])
		minimum_attempt = self._minimum_archive_attempt(
			"performance", cell, manifest_hash, lifecycle_replicate, period, order,
		)
		request: dict[str, object] = {
			"kind": "performance", "cell": cell, "manifest_hash": manifest_hash,
			"invocation_manifest": invocation, "lifecycle_replicate": lifecycle_replicate,
			"period": period, "order": order, "minimum_attempt": minimum_attempt, "crash_after": crash_after,
		}
		nonce = uuid.uuid4().hex
		lineage = cast(Mapping[str, object], manifest["lineage"])
		authority: dict[str, object] = {
			"nonce": nonce, "phase": "final_performance",
			"evidence_roots": {
				"P": lineage["preregistration_manifest_sha256"], "D": lineage["discovery_completion_sha256"],
				"S": lineage["pilot_selection_sha256"], "R": lineage["pilot_resource_reservation_sha256"],
				"F": manifest_hash,
			},
			"request": request,
		}
		self.__pending_allocation_authorities[nonce] = cast(dict[str, object], json.loads(json.dumps(authority)))
		return self._ledger._begin_attempt_from_adapter(
			kind="performance", cell=cell, manifest_hash=manifest_hash, invocation_manifest=invocation,
			lifecycle_replicate=lifecycle_replicate, period=period, order=order,
			minimum_attempt=minimum_attempt, crash_after=crash_after, _allocation_authority=authority,
		)

	def _minimum_archive_attempt(
		self, kind: str, cell: str, manifest_hash: str, lifecycle_replicate: int | None,
		period: int | None, order: str | None,
	) -> int:
		base: dict[str, object] = {"kind": kind, "cell": cell, "manifest_hash": manifest_hash}
		if kind == "performance":
			base.update({"lifecycle_replicate": lifecycle_replicate, "period": period, "order": order})
		archive_attempts = [
			_receipt_attempt(receipt)
			for receipt in self._archive.catalog()
			if isinstance(receipt.get("identity"), dict)
			and all(cast(dict[str, object], receipt["identity"]).get(name) == value for name, value in base.items())
		]
		return max(archive_attempts, default=0) + 1

	def __consume_allocation_authority(
		self, authority: object, request: Mapping[str, object],
	) -> None:
		if not isinstance(authority, Mapping) or set(authority) != {"nonce", "phase", "evidence_roots", "request"}:
			raise ArchiveContractError("typed allocation authority schema is invalid")
		nonce = authority.get("nonce")
		if type(nonce) is not str:
			raise ArchiveContractError("typed allocation authority nonce is invalid")
		expected = self.__pending_allocation_authorities.pop(nonce, None)
		if expected != authority or authority.get("request") != request:
			raise ArchiveContractError("typed allocation authority is absent, stale, or mismatched")

	def complete_discovery(self, preregistration_manifest: Mapping[str, object]) -> dict[str, object]:
		"""Revalidate all four planner barriers and seal their exact latest successes as D."""
		try:
			prereg = validate_campaign_preregistration_manifest(preregistration_manifest)
		except CampaignContractError as error:
			raise ArchiveContractError(str(error)) from error
		manifest_hash = cast(str, prereg["preregistration_manifest_sha256"])
		self.assert_planner_barrier(CAMPAIGN_PLANNERS[-1], manifest_hash)
		rows: list[dict[str, object]] = []
		for cell in campaign_cell_ids():
			decision = self.exact_resume(DiscoveryKey(cell, 1, "completion-query", manifest_hash))
			if decision.state is not ResumeState.LATEST_SUCCESS or decision.evidence is None:
				raise ArchiveContractError(f"discovery completion blocked by {cell}")
			evidence = decision.evidence
			identity = evidence.get("identity")
			if not isinstance(identity, dict):
				raise ArchiveContractError("discovery completion evidence identity is missing")
			if isinstance(evidence.get("archive_sha256"), str):
				status = "archive"
				location = {"archive_uri": evidence["archive_uri"], "archive_sha256": evidence["archive_sha256"]}
				digest = evidence["archive_sha256"]
			else:
				path = Path(cast(str, evidence.get("committed_path")))
				status = "committed"; location = {"committed_path": str(path)}
				digest = _sha256(path / "bundle_manifest.json")
			rows.append({
				"cell": cell, "identity": identity,
				"invocation_manifest_sha256": evidence.get("invocation_manifest_sha256"), "evidence_status": status,
				"evidence_sha256": digest, "evidence_location": location,
			})
		try:
			return build_discovery_completion_receipt(
				preregistration_manifest=prereg, discovery_rows=rows,
				evidence_validator=self._verify_discovery_completion_row,
			)
		except CampaignContractError as error:
			raise ArchiveContractError(str(error)) from error

	def _verify_discovery_completion_row(self, row: Mapping[str, object]) -> None:
		identity = row.get("identity")
		location = row.get("evidence_location")
		if not isinstance(identity, dict) or not isinstance(location, dict):
			raise ArchiveContractError("discovery completion identity/location is invalid")
		try:
			key = DiscoveryKey(
				cast(str, identity["cell"]), cast(int, identity["attempt"]),
				cast(str, identity["run_token"]), cast(str, identity["manifest_hash"]),
			)
		except (KeyError, TypeError) as error:
			raise ArchiveContractError("discovery completion identity is invalid") from error
		if key.as_dict() != identity:
			raise ArchiveContractError("discovery completion identity schema is not exact")
		decision = self.exact_resume(key)
		if decision.state is not ResumeState.LATEST_SUCCESS or decision.evidence is None:
			raise ArchiveContractError("discovery completion row is not canonical latest success")
		evidence = decision.evidence
		if evidence.get("identity") != identity:
			raise ArchiveContractError("discovery completion latest identity changed")
		if evidence.get("invocation_manifest_sha256") != row.get("invocation_manifest_sha256"):
			raise ArchiveContractError("discovery completion invocation identity changed")
		if row.get("evidence_status") == "archive":
			if set(location) != {"archive_uri", "archive_sha256"} or row.get("evidence_sha256") != evidence.get("archive_sha256"):
				raise ArchiveContractError("discovery completion archive evidence changed")
			if location.get("archive_uri") != evidence.get("archive_uri") or location.get("archive_sha256") != evidence.get("archive_sha256"):
				raise ArchiveContractError("discovery completion archive location changed")
		elif row.get("evidence_status") == "committed":
			if set(location) != {"committed_path"} or location.get("committed_path") != evidence.get("committed_path"):
				raise ArchiveContractError("discovery completion committed location changed")
			if row.get("evidence_sha256") != _sha256(Path(cast(str, location["committed_path"])) / "bundle_manifest.json"):
				raise ArchiveContractError("discovery completion committed digest changed")
		else:
			raise ArchiveContractError("discovery completion evidence status is invalid")

	def publish_performance_success(
		self,
		lease: AttemptLease,
		cold_bundle: Path,
		warm_bundle: Path,
		shared_replicate_manifest: Path,
		*,
		crash_after: str | None = None,
	) -> Path:
		if not isinstance(lease, AttemptLease):
			raise ArchiveContractError("v2 performance publication requires a durable AttemptLease")
		return self._ledger.publish_performance_success(
			lease, cold_bundle, warm_bundle, shared_replicate_manifest, crash_after=crash_after
		)

	def publish_discovery_success(
		self, lease: AttemptLease, bundle: Path, *, crash_after: str | None = None
	) -> Path:
		if not isinstance(lease, AttemptLease):
			raise ArchiveContractError("v2 discovery publication requires a durable AttemptLease")
		return self._ledger.publish_discovery_success(lease, bundle, crash_after=crash_after)

	def publish_failure(
		self,
		lease: AttemptLease,
		failure_bundle: Path,
		*,
		crash_after: str | None = None,
	) -> Path:
		if not isinstance(lease, AttemptLease):
			raise ArchiveContractError("v2 failure publication requires a durable AttemptLease")
		return self._ledger.publish_failure(lease, failure_bundle, crash_after=crash_after)

	def archive(self, committed_path: Path, *, crash_after: str | None = None) -> dict[str, object]:
		return self._archive.archive(committed_path, crash_after=crash_after)

	def exact_resume(self, key: DiscoveryKey | PerformanceKey) -> ResumeDecision:
		return self._archive.exact_resume(key)

	def preflight(
		self, snapshot_provider: Callable[[], HostResourceSnapshot], **requirements: float | int
	) -> HostResourceSnapshot:
		expected = {
			"required_free_bytes", "required_free_inodes", "required_seconds",
			"max_io_utilization", "max_combined_io_bps",
		}
		if set(requirements) != expected:
			raise ArchiveContractError("resource preflight requirements schema is not exact")
		free_bytes = requirements["required_free_bytes"]
		free_inodes = requirements["required_free_inodes"]
		if isinstance(free_bytes, bool) or not isinstance(free_bytes, int):
			raise ArchiveContractError("required free bytes must be an integer")
		if isinstance(free_inodes, bool) or not isinstance(free_inodes, int):
			raise ArchiveContractError("required free inodes must be an integer")
		return self._archive.preflight_next_lifecycle(
			snapshot_provider,
			required_free_bytes=free_bytes,
			required_free_inodes=free_inodes,
			required_seconds=requirements["required_seconds"],
			max_io_utilization=requirements["max_io_utilization"],
			max_combined_io_bps=requirements["max_combined_io_bps"],
		)

	def assert_planner_barrier(self, planner: str, manifest_hash: str) -> dict[str, object]:
		if planner not in CAMPAIGN_PLANNERS:
			raise ArchiveContractError("planner barrier planner is invalid")
		planner_index = CAMPAIGN_PLANNERS.index(planner)
		cache_key = (planner, manifest_hash)
		cached = self.__planner_barrier_cache.get(cache_key)
		if cached is not None:
			identities, expected_scope, receipt = cached
			current_scope = self._archive._receipt_scope_digest(identities)
			if current_scope != expected_scope:
				raise ArchiveContractError(
					f"planner barrier {planner} evidence changed after full verification"
				)
			return dict(receipt)
		checked = 0
		verified_identities: list[dict[str, object]] = []
		for required_planner in CAMPAIGN_PLANNERS[: planner_index + 1]:
			for cell in campaign_cell_ids():
				if f"planner={required_planner}|" not in cell:
					continue
				decision = self.exact_resume(DiscoveryKey(cell, 1, "barrier-query", manifest_hash))
				if decision.state is not ResumeState.LATEST_SUCCESS:
					raise ArchiveContractError(
						f"planner barrier {planner} blocked by {required_planner} cell {cell}: {decision.state.value}"
					)
				if decision.evidence is None or not isinstance(decision.evidence.get("identity"), dict):
					raise ArchiveContractError(
						f"planner barrier {planner} has no canonical identity for {required_planner} cell {cell}"
					)
				verified_identities.append(dict(cast(dict[str, object], decision.evidence["identity"])))
				checked += 1
		receipt: dict[str, object] = {
			"planner": planner,
			"prior_planners": list(CAMPAIGN_PLANNERS[:planner_index]),
			"verified_cells": checked,
		}
		scope = self._archive._receipt_scope_digest(verified_identities)
		if scope is not None:
			self.__planner_barrier_cache[cache_key] = (
				tuple(verified_identities), scope, dict(receipt),
			)
		return receipt

	def select_pilot_repeats(
		self, rows: Sequence[Mapping[str, object]], *,
		expected_manifest_hash: str,
		preregistration_manifest: Mapping[str, object],
		discovery_completion_receipt: Mapping[str, object],
	) -> dict[str, object]:
		try:
			return select_campaign_pilot_repeats(
				rows, self._verify_pilot_row, expected_manifest_hash=expected_manifest_hash,
				preregistration_manifest=preregistration_manifest,
				discovery_completion_receipt=discovery_completion_receipt,
				discovery_evidence_validator=self._verify_discovery_completion_row,
			)
		except CampaignContractError as error:
			raise ArchiveContractError(str(error)) from error

	def build_pilot_resource_reservation(
		self, *, pilot_selection_receipt: Mapping[str, object],
		preregistration_manifest: Mapping[str, object], discovery_completion_receipt: Mapping[str, object],
	) -> dict[str, object]:
		try:
			return _build_pilot_resource_reservation(
				pilot_selection_receipt=pilot_selection_receipt,
				preregistration_manifest=preregistration_manifest,
				discovery_completion_receipt=discovery_completion_receipt,
				pilot_evidence_validator=self._verify_pilot_row,
				discovery_evidence_validator=self._verify_discovery_completion_row,
			)
		except CampaignContractError as error:
			raise ArchiveContractError(str(error)) from error

	def build_final_campaign_manifest(
		self, *, preregistration_manifest: Mapping[str, object], pilot_selection_receipt: Mapping[str, object],
		pilot_resource_reservation: Mapping[str, object], discovery_completion_receipt: Mapping[str, object],
	) -> dict[str, object]:
		try:
			return _build_final_campaign_manifest(
				preregistration_manifest=preregistration_manifest,
				pilot_selection_receipt=pilot_selection_receipt,
				pilot_resource_reservation=pilot_resource_reservation,
				discovery_completion_receipt=discovery_completion_receipt,
				pilot_evidence_validator=self._verify_pilot_row,
				discovery_evidence_validator=self._verify_discovery_completion_row,
			)
		except CampaignContractError as error:
			raise ArchiveContractError(str(error)) from error

	def _verify_pilot_row(self, row: Mapping[str, object]) -> None:
		identity_value = row.get("identity")
		location_value = row.get("evidence_location")
		if not isinstance(identity_value, dict) or not isinstance(location_value, dict):
			raise ArchiveContractError("pilot evidence identity/location is missing")
		identity = cast(dict[str, object], identity_value)
		if (
			type(row.get("workers")) is not int
			or type(row.get("pilot_repeat")) is not int or row.get("pilot_repeat") not in range(1, 6)
			or type(row.get("period")) is not int or row.get("period") not in range(1, len(CAMPAIGN_PLANNERS) + 1)
			or any(type(identity.get(name)) is not int for name in ("lifecycle_replicate", "period", "attempt"))
		):
			raise ArchiveContractError("pilot integer schedule identity is invalid")
		lifecycle_value = row.get("lifecycle")
		if not isinstance(lifecycle_value, Mapping) or any(
			type(lifecycle_value.get(name)) is not int or lifecycle_value.get(name) != 0
			for name in ("coordinator_restart_count", "worker_restart_count")
		):
			raise ArchiveContractError("pilot lifecycle restart counts must be exact integer zero")
		try:
			key: DiscoveryKey | PerformanceKey
			if identity.get("kind") == "discovery":
				key = DiscoveryKey(
					cast(str, identity["cell"]), cast(int, identity["attempt"]),
					cast(str, identity["run_token"]), cast(str, identity["manifest_hash"]),
				)
			else:
				key = PerformanceKey(
					cast(str, identity["cell"]), cast(int, identity["lifecycle_replicate"]), cast(int, identity["period"]),
					cast(str, identity["order"]), cast(str, identity["manifest_hash"]), cast(int, identity["attempt"]),
					cast(str, identity["run_token"]),
				)
		except (KeyError, TypeError) as error:
			raise ArchiveContractError("pilot evidence identity is invalid") from error
		if key.as_dict() != identity:
			raise ArchiveContractError("pilot evidence identity schema is not exact")
		if not isinstance(key, PerformanceKey):
			raise ArchiveContractError("campaign pilot requires performance evidence")
		expected_cell = (
			f"pilot_class={row.get('pilot_class')}|workload={row.get('workload')}|planner={row.get('planner')}|"
			f"workers={row.get('workers')}|profile={row.get('profile')}"
		)
		if (
			key.cell != row.get("cell") or key.cell != expected_cell
			or key.lifecycle_replicate != row.get("pilot_repeat")
			or key.period != row.get("period")
			or key.order != row.get("order")
		):
			raise ArchiveContractError("pilot evidence scheduling identity disagrees with preregistered row")
		decision = self.exact_resume(key)
		if decision.state is not ResumeState.LATEST_SUCCESS or decision.evidence is None:
			raise ArchiveContractError("pilot evidence is not a freshly verified success")
		evidence = decision.evidence
		if evidence.get("identity") != identity:
			raise ArchiveContractError("pilot evidence identity changed during revalidation")
		if evidence.get("invocation_manifest_sha256") != row.get("invocation_manifest_sha256"):
			raise ArchiveContractError("pilot invocation manifest binding changed during revalidation")
		if evidence.get("host_load") != row.get("host_load") or evidence.get("lifecycle") != row.get("lifecycle"):
			raise ArchiveContractError("pilot diagnostics disagree with checksummed evidence")
		if evidence.get("resource_evidence") != row.get("resource_evidence"):
			raise ArchiveContractError("pilot resource evidence disagrees with checksummed evidence")
		status, digest = row.get("evidence_status"), row.get("evidence_sha256")
		if status == "committed":
			if set(location_value) != {"committed_path"} or evidence.get("committed_path") != location_value.get("committed_path"):
				raise ArchiveContractError("pilot committed location is not exact")
			path = Path(cast(str, location_value["committed_path"]))
			if digest != _sha256(path / "bundle_manifest.json"):
				raise ArchiveContractError("pilot committed evidence checksum mismatch")
		elif status == "archive":
			if set(location_value) != {"archive_uri", "archive_sha256"}:
				raise ArchiveContractError("pilot archive location is not exact")
			if evidence.get("archive_uri") != location_value.get("archive_uri") or evidence.get("archive_sha256") != location_value.get("archive_sha256"):
				raise ArchiveContractError("pilot archive location changed during revalidation")
			if digest != location_value.get("archive_sha256"):
				raise ArchiveContractError("pilot archive checksum mismatch")
		else:
			raise ArchiveContractError("pilot evidence status is invalid")
		warm_metric = evidence.get("warm_metric")
		if not isinstance(warm_metric, dict) or warm_metric.get("seconds") != row.get("warm_seconds"):
			raise ArchiveContractError("pilot timing does not match revalidated evidence")

	def _normalize_verified_row_v1(self, decision: ResumeDecision, pilot_repeat: int) -> dict[str, object]:
		if decision.state is not ResumeState.LATEST_SUCCESS or decision.evidence is None:
			raise ArchiveContractError("only latest verified success evidence can become a pilot row")
		if isinstance(pilot_repeat, bool) or not isinstance(pilot_repeat, int) or pilot_repeat not in range(1, 6):
			raise ArchiveContractError("pilot_repeat must be one of the preregistered repeats 1..5")
		evidence = dict(decision.evidence)
		identity = evidence.get("identity")
		warm_metric = evidence.get("warm_metric")
		if not isinstance(identity, dict) or not isinstance(warm_metric, dict):
			raise ArchiveContractError("verified success lacks identity or warm metric")
		seconds = warm_metric.get("seconds")
		if isinstance(seconds, bool) or not isinstance(seconds, (int, float)):
			raise ArchiveContractError("verified success warm metric is invalid")
		archive_hash = evidence.get("archive_sha256")
		if isinstance(archive_hash, str):
			status = "archive"
			digest = archive_hash
		else:
			committed_path = evidence.get("committed_path")
			if not isinstance(committed_path, str):
				raise ArchiveContractError("verified local success lacks committed path")
			manifest = self._ledger.validate_committed(Path(committed_path))
			if manifest.get("identity") != identity:
				raise ArchiveContractError("verified local success identity changed")
			status = "committed"
			digest = _sha256(Path(committed_path) / "bundle_manifest.json")
		return {
			"cell": identity.get("cell"),
			"pilot_repeat": pilot_repeat,
			"warm_seconds": float(seconds),
			"evidence_status": status,
			"evidence_sha256": digest,
		}

	def normalize_resume_row(
		self,
		decision: ResumeDecision,
		*,
		requested_identity: Mapping[str, object],
		schedule: Mapping[str, object] | None,
		host_load: Mapping[str, object],
		lifecycle: Mapping[str, object],
	) -> dict[str, object]:
		"""Normalize success, failure, in-progress, and corrupt evidence without dropping identity facts."""
		identity = dict(requested_identity)
		kind = identity.get("kind")
		if kind not in ("discovery", "performance"):
			raise ArchiveContractError("normalized row kind is invalid")
		try:
			if kind == "discovery" and set(identity) == {"kind", "cell", "attempt", "run_token", "manifest_hash"}:
				typed_key: DiscoveryKey | PerformanceKey = DiscoveryKey(
					cast(str, identity["cell"]), cast(int, identity["attempt"]),
					cast(str, identity["run_token"]), cast(str, identity["manifest_hash"]),
				)
			elif kind == "performance" and set(identity) == {
				"kind", "cell", "lifecycle_replicate", "period", "order", "manifest_hash", "attempt", "run_token"
			}:
				typed_key = PerformanceKey(
					cast(str, identity["cell"]), cast(int, identity["lifecycle_replicate"]), cast(int, identity["period"]),
					cast(str, identity["order"]), cast(str, identity["manifest_hash"]), cast(int, identity["attempt"]),
					cast(str, identity["run_token"]),
				)
			else:
				raise ArchiveContractError("normalized requested identity schema is not exact")
		except (LedgerContractError, TypeError) as error:
			raise ArchiveContractError("normalized requested identity is invalid") from error
		if identity != typed_key.as_dict():
			raise ArchiveContractError("normalized requested identity is not canonical")
		canonical_decision = self.exact_resume(typed_key)
		supplied_evidence = dict(decision.evidence or {})
		canonical_evidence = dict(canonical_decision.evidence or {})
		def evidence_location(value: Mapping[str, object]) -> dict[str, object]:
			return {
				name: value[name] for name in ("committed_path", "archive_uri", "archive_sha256") if name in value
			}
		def exact_optional_identity(left: object, right: object) -> bool:
			if left is None or right is None:
				return left is None and right is None
			if not isinstance(left, Mapping) or not isinstance(right, Mapping) or set(left) != set(right):
				return False
			return all(type(left[name]) is type(right[name]) and left[name] == right[name] for name in left)
		supplied_identity_matches_canonical = exact_optional_identity(
			supplied_evidence.get("identity"), canonical_evidence.get("identity")
		)
		supplied_identity_matches_requested = exact_optional_identity(supplied_evidence.get("identity"), identity)
		decision_mismatch = (
			decision.state is not canonical_decision.state
			or type(decision.attempt) is not type(canonical_decision.attempt)
			or decision.attempt != canonical_decision.attempt
			or not supplied_identity_matches_canonical
			or evidence_location(supplied_evidence) != evidence_location(canonical_evidence)
		)
		decision = canonical_decision
		evidence = canonical_evidence
		blocker: dict[str, object] | None = (
			{
				"code": (
					"IDENTITY_MISMATCH"
					if supplied_evidence.get("identity") is not None and not supplied_identity_matches_requested
					else "STALE_OR_NONCANONICAL_RESUME_DECISION"
				),
				"detail": (
					"evidence does not exactly match requested identity"
					if supplied_evidence.get("identity") is not None and not supplied_identity_matches_requested
					else "supplied resume decision does not match the freshly resolved canonical latest attempt"
				),
			}
			if decision_mismatch else None
		)
		terminal = decision.state in (ResumeState.LATEST_SUCCESS, ResumeState.LATEST_FAILED)
		if terminal and blocker is None:
			evidence_identity = evidence.get("identity")
			if evidence_identity != identity:
				blocker = {"code": "IDENTITY_MISMATCH", "detail": "evidence does not exactly match requested identity"}
			elif not self._archive._is_v2_manifest(evidence):
				blocker = {"code": "LEGACY_EVIDENCE", "detail": "legacy evidence cannot satisfy v2 normalization"}
			else:
				blocker, revalidated = self._revalidate_normalized_evidence(evidence, identity)
				if blocker is None and revalidated is not None:
					evidence = revalidated
		elif blocker is None:
			blocker = {"code": decision.state.value, "detail": decision.detail or "resume evidence is not valid"}
		evidence_revalidated = blocker is None
		canonical_status = evidence.get("status") if evidence_revalidated else None
		verified_success = canonical_status == "success"
		verified_failure = canonical_status == "failed"
		if verified_failure:
			blocker = {
				"code": "EXECUTION_FAILURE", "detail": evidence.get("failure_category", "failed execution"),
			}
		normalized_state = (
			ResumeState.LATEST_SUCCESS.value if verified_success
			else ResumeState.LATEST_FAILED.value if verified_failure
			else decision.state.value
		)
		trusted_evidence = evidence if evidence_revalidated else {}
		row: dict[str, object] = {
			"schema": "systemds-federated-normalized-row/v2",
			"kind": kind,
			"identity": identity,
			"resume_state": normalized_state,
			"valid": verified_success,
			"failure": (
				verified_failure or decision_mismatch or decision.state is ResumeState.CORRUPT_OR_AMBIGUOUS
				or (terminal and blocker is not None)
			),
			"detail": decision.detail,
			"blocker": blocker,
			"schedule": dict(schedule) if schedule is not None else None,
			"metrics": {
				"discovery": trusted_evidence.get("discovery_metric"),
				"cold": trusted_evidence.get("cold_metric"),
				"warm": trusted_evidence.get("warm_metric"),
				"failure": {
					"return_code": trusted_evidence.get("return_code"),
					"category": trusted_evidence.get("failure_category"),
					"semantic_oracle": trusted_evidence.get("semantic_oracle_summary"),
					"semantic_oracle_sha256": trusted_evidence.get("semantic_oracle_sha256"),
					"parser": trusted_evidence.get("parser_summary"),
					"parser_sha256": trusted_evidence.get("parser_sha256"),
					"scan": trusted_evidence.get("scan_summary"),
					"scan_sha256": trusted_evidence.get("scan_sha256"),
				} if verified_failure else None,
			},
			"host_load": trusted_evidence.get("host_load"),
			"lifecycle": trusted_evidence.get("lifecycle"),
			"resource_evidence": trusted_evidence.get("resource_evidence"),
			"evidence_location": {
				"committed_path": trusted_evidence.get("committed_path"),
				"archive_uri": trusted_evidence.get("archive_uri"),
				"archive_sha256": trusted_evidence.get("archive_sha256"),
			},
		}
		if kind == "discovery" and schedule is not None:
			raise ArchiveContractError("discovery rows must not claim a performance schedule")
		if kind == "performance" and schedule is None:
			raise ArchiveContractError("performance rows require exact schedule facts")
		if kind == "performance" and schedule is not None:
			period, order = schedule.get("period"), schedule.get("order")
			if (
				type(period) is not int or period not in range(1, len(CAMPAIGN_PLANNERS) + 1)
				or type(order) is not str
				or period != identity.get("period") or order != identity.get("order")
			):
				raise ArchiveContractError("performance row schedule disagrees with requested identity")
		return row

	def _revalidate_normalized_evidence(
		self, evidence: Mapping[str, object], identity: Mapping[str, object]
	) -> tuple[dict[str, object] | None, dict[str, object] | None]:
		validated: list[dict[str, object]] = []
		committed_path = evidence.get("committed_path")
		if isinstance(committed_path, str):
			try:
				validated.append(self._ledger.validate_committed(Path(committed_path), require_success=False))
			except LedgerContractError as error:
				return {"code": "LOCAL_REVALIDATION_FAILED", "detail": str(error)}, None
		archive_uri, archive_hash = evidence.get("archive_uri"), evidence.get("archive_sha256")
		if isinstance(archive_uri, str) or isinstance(archive_hash, str):
			matches = [
				receipt for receipt in self._archive.catalog()
				if receipt.get("identity") == identity
				and receipt.get("archive_uri") == archive_uri
				and receipt.get("archive_sha256") == archive_hash
			]
			if len(matches) != 1:
				return {"code": "ARCHIVE_RECEIPT_AMBIGUOUS", "detail": "archive evidence lacks one unique verified receipt"}, None
			try:
				validated.append(self._archive._verify_receipt(matches[0]))
			except ArchiveContractError as error:
				return {"code": "ARCHIVE_REVALIDATION_FAILED", "detail": str(error)}, None
		if not validated:
			return {"code": "EVIDENCE_LOCATION_MISSING", "detail": "terminal evidence has no revalidatable location"}, None
		for manifest in validated:
			if (
				manifest.get("identity") != identity
				or not self._archive._is_v2_manifest(manifest)
			):
				return {"code": "EVIDENCE_REVALIDATION_MISMATCH", "detail": "revalidated evidence changed identity or schema"}, None
		canonical = dict(validated[0])
		if any(manifest != canonical for manifest in validated[1:]):
			return {"code": "EVIDENCE_REVALIDATION_MISMATCH", "detail": "local and archived manifests disagree"}, None
		if isinstance(committed_path, str):
			canonical["committed_path"] = committed_path
		if isinstance(archive_uri, str) and isinstance(archive_hash, str):
			canonical["archive_uri"] = archive_uri
			canonical["archive_sha256"] = archive_hash
		return None, canonical

def _canonical_bytes(value: object) -> bytes:
	return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def _sha256(path: Path) -> str:
	digest = hashlib.sha256()
	with path.open("rb") as handle:
		for block in iter(lambda: handle.read(1024 * 1024), b""):
			digest.update(block)
	return digest.hexdigest()


def _fsync_directory(path: Path) -> None:
	fd = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
	try:
		os.fsync(fd)
	finally:
		os.close(fd)
