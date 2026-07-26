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
	campaign_cell_ids,
	build_campaign_manifest,
	build_frozen_manifest,
	select_pilot_repeats,
	select_campaign_pilot_repeats,
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
		}
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
		return receipt

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
		if local.attempt == latest_attempt and local.evidence is not None:
			local_identity = local.evidence.get("identity")
			local_status = local.evidence.get("status")
			if any(
				receipt.get("identity") != local_identity or receipt.get("status") != local_status
				for receipt in latest_archived
			):
				return ResumeDecision(ResumeState.CORRUPT_OR_AMBIGUOUS, latest_attempt, detail="local/archive same-attempt disagreement")
		if local.attempt == latest_attempt and local.state in (
			ResumeState.IN_PROGRESS_OR_ABANDONED,
			ResumeState.LATEST_FAILED,
			ResumeState.CORRUPT_OR_AMBIGUOUS,
		):
			return self._require_v2_resume(local)
		try:
			manifest = self._verify_receipt(latest_archived[0])
		except ArchiveContractError as error:
			return ResumeDecision(ResumeState.CORRUPT_OR_AMBIGUOUS, latest_attempt, detail=str(error))
		manifest["archive_uri"] = latest_archived[0]["archive_uri"]
		manifest["archive_sha256"] = latest_archived[0]["archive_sha256"]
		if not self._is_v2_manifest(manifest):
			return ResumeDecision(ResumeState.CORRUPT_OR_AMBIGUOUS, latest_attempt, detail="legacy v1 archive cannot satisfy v2 resume")
		state = ResumeState.LATEST_SUCCESS if manifest.get("status") == "success" else ResumeState.LATEST_FAILED
		return ResumeDecision(state, latest_attempt, manifest)

	@staticmethod
	def _is_v2_manifest(manifest: Mapping[str, object]) -> bool:
		identity = manifest.get("identity")
		return (
			manifest.get("schema") == "systemds-federated-evidence/v2"
			and isinstance(identity, dict)
			and manifest.get("evidence_kind") == identity.get("kind")
			and isinstance(manifest.get("invocation_manifest_sha256"), str)
		)

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
		for receipt in self.catalog():
			self._verify_receipt(receipt)
		snapshot = snapshot_provider()
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

	def _verify_receipt(self, receipt: dict[str, object]) -> dict[str, object]:
		uri = receipt.get("archive_uri")
		expected_hash = receipt.get("archive_sha256")
		identity = receipt.get("identity")
		if not isinstance(uri, str) or not isinstance(expected_hash, str) or not isinstance(identity, dict):
			raise ArchiveContractError("archive receipt is invalid")
		if not self.backend.exists(uri):
			raise ArchiveContractError(f"remote archive is missing: {uri}")
		download = self.work_root / f"receipt-verify-{uuid.uuid4().hex}.tar"
		try:
			self.backend.get(uri, download)
			if _sha256(download) != expected_hash:
				raise ArchiveContractError(f"remote archive checksum mismatch: {uri}")
			record_id = hashlib.sha256(_canonical_bytes(identity)).hexdigest()
			manifest = self._verify_downloaded_archive(download, identity, record_id)
			if receipt.get("status") != manifest.get("status"):
				raise ArchiveContractError("archive receipt status disagrees with archived manifest")
			return manifest
		except ArchiveContractError:
			raise
		except Exception as error:
			raise ArchiveContractError(f"remote archive verification failed: {uri}") from error
		finally:
			download.unlink(missing_ok=True)

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


class CampaignHarnessAdapter:
	"""The sole typed lifecycle surface used by the Docker campaign driver."""

	integration_operations: frozenset[str] = frozenset({
		"begin",
		"publish_discovery_success",
		"publish_performance_success",
		"publish_failure",
		"archive",
		"exact_resume",
		"select_pilot_repeats",
		"normalize_resume_row",
		"assert_planner_barrier",
		"preflight",
	})

	def __init__(self, ledger: AtomicEvidenceLedger, archive: HdfsArchiveAdapter):
		if archive.ledger is not ledger:
			raise ArchiveContractError("harness adapter ledger/archive mismatch")
		self._ledger = ledger
		self._archive = archive

	def begin(
		self,
		*,
		kind: str,
		cell: str,
		manifest_hash: str,
		invocation_manifest: Mapping[str, object],
		lifecycle_replicate: int | None = None,
		period: int | None = None,
		order: str | None = None,
		crash_after: str | None = None,
	) -> AttemptLease:
		if kind == "discovery" and cell in campaign_cell_ids():
			planner = next(name for name in CAMPAIGN_PLANNERS if f"planner={name}|" in cell)
			planner_index = CAMPAIGN_PLANNERS.index(planner)
			if planner_index > 0:
				self.assert_planner_barrier(CAMPAIGN_PLANNERS[planner_index - 1], manifest_hash)
		base: dict[str, object] = {"kind": kind, "cell": cell, "manifest_hash": manifest_hash}
		if kind == "performance":
			base.update({"lifecycle_replicate": lifecycle_replicate, "period": period, "order": order})
		archive_attempts = [
			_receipt_attempt(receipt)
			for receipt in self._archive.catalog()
			if isinstance(receipt.get("identity"), dict)
			and all(cast(dict[str, object], receipt["identity"]).get(name) == value for name, value in base.items())
		]
		return self._ledger.begin_attempt(
			kind=kind,
			cell=cell,
			manifest_hash=manifest_hash,
			invocation_manifest=invocation_manifest,
			lifecycle_replicate=lifecycle_replicate,
			period=period,
			order=order,
			minimum_attempt=max(archive_attempts, default=0) + 1,
			crash_after=crash_after,
		)

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
		return self._archive.preflight_next_lifecycle(
			snapshot_provider,
			required_free_bytes=int(requirements["required_free_bytes"]),
			required_free_inodes=int(requirements["required_free_inodes"]),
			required_seconds=float(requirements["required_seconds"]),
			max_io_utilization=float(requirements["max_io_utilization"]),
			max_combined_io_bps=float(requirements["max_combined_io_bps"]),
		)

	def assert_planner_barrier(self, planner: str, manifest_hash: str) -> dict[str, object]:
		if planner not in CAMPAIGN_PLANNERS:
			raise ArchiveContractError("planner barrier planner is invalid")
		planner_index = CAMPAIGN_PLANNERS.index(planner)
		checked = 0
		for required_planner in CAMPAIGN_PLANNERS[: planner_index + 1]:
			for cell in campaign_cell_ids():
				if f"planner={required_planner}|" not in cell:
					continue
				decision = self.exact_resume(DiscoveryKey(cell, 1, "barrier-query", manifest_hash))
				if decision.state is not ResumeState.LATEST_SUCCESS:
					raise ArchiveContractError(
						f"planner barrier {planner} blocked by {required_planner} cell {cell}: {decision.state.value}"
					)
				checked += 1
		return {"planner": planner, "prior_planners": list(CAMPAIGN_PLANNERS[:planner_index]), "verified_cells": checked}

	def select_pilot_repeats(self, rows: Sequence[Mapping[str, object]]) -> dict[str, object]:
		try:
			return select_campaign_pilot_repeats(rows, self._verify_pilot_row)
		except CampaignContractError as error:
			raise ArchiveContractError(str(error)) from error

	def _verify_pilot_row(self, row: Mapping[str, object]) -> None:
		identity_value = row.get("identity")
		location_value = row.get("evidence_location")
		if not isinstance(identity_value, dict) or not isinstance(location_value, dict):
			raise ArchiveContractError("pilot evidence identity/location is missing")
		identity = cast(dict[str, object], identity_value)
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
		decision = self.exact_resume(key)
		if decision.state is not ResumeState.LATEST_SUCCESS or decision.evidence is None:
			raise ArchiveContractError("pilot evidence is not a freshly verified success")
		evidence = decision.evidence
		if evidence.get("identity") != identity:
			raise ArchiveContractError("pilot evidence identity changed during revalidation")
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
		evidence = dict(decision.evidence or {})
		identity = dict(requested_identity)
		kind = identity.get("kind")
		if kind not in ("discovery", "performance"):
			raise ArchiveContractError("normalized row kind is invalid")
		try:
			if kind == "discovery" and set(identity) == {"kind", "cell", "attempt", "run_token", "manifest_hash"}:
				canonical_identity = DiscoveryKey(
					cast(str, identity["cell"]), cast(int, identity["attempt"]),
					cast(str, identity["run_token"]), cast(str, identity["manifest_hash"]),
				).as_dict()
			elif kind == "performance" and set(identity) == {
				"kind", "cell", "lifecycle_replicate", "period", "order", "manifest_hash", "attempt", "run_token"
			}:
				canonical_identity = PerformanceKey(
					cast(str, identity["cell"]), cast(int, identity["lifecycle_replicate"]), cast(int, identity["period"]),
					cast(str, identity["order"]), cast(str, identity["manifest_hash"]), cast(int, identity["attempt"]),
					cast(str, identity["run_token"]),
				).as_dict()
			else:
				raise ArchiveContractError("normalized requested identity schema is not exact")
		except (LedgerContractError, TypeError) as error:
			raise ArchiveContractError("normalized requested identity is invalid") from error
		if identity != canonical_identity:
			raise ArchiveContractError("normalized requested identity is not canonical")
		blocker: dict[str, object] | None = None
		terminal = decision.state in (ResumeState.LATEST_SUCCESS, ResumeState.LATEST_FAILED)
		if terminal:
			evidence_identity = evidence.get("identity")
			if evidence_identity != identity:
				blocker = {"code": "IDENTITY_MISMATCH", "detail": "evidence does not exactly match requested identity"}
			elif not self._archive._is_v2_manifest(evidence):
				blocker = {"code": "LEGACY_EVIDENCE", "detail": "legacy evidence cannot satisfy v2 normalization"}
			else:
				blocker, revalidated = self._revalidate_normalized_evidence(evidence, identity)
				if blocker is None and revalidated is not None:
					evidence = revalidated
		else:
			blocker = {"code": decision.state.value, "detail": decision.detail or "resume evidence is not valid"}
		verified_success = decision.state is ResumeState.LATEST_SUCCESS and blocker is None
		verified_failure = decision.state is ResumeState.LATEST_FAILED and blocker is None
		if verified_failure:
			blocker = {
				"code": "EXECUTION_FAILURE", "detail": evidence.get("failure_category", "failed execution"),
			}
		row: dict[str, object] = {
			"schema": "systemds-federated-normalized-row/v2",
			"kind": kind,
			"identity": identity,
			"resume_state": decision.state.value,
			"valid": verified_success,
			"failure": (
				decision.state in (ResumeState.LATEST_FAILED, ResumeState.CORRUPT_OR_AMBIGUOUS)
				or (terminal and blocker is not None)
			),
			"detail": decision.detail,
			"blocker": blocker,
			"schedule": dict(schedule) if schedule is not None else None,
			"metrics": {
				"discovery": evidence.get("discovery_metric"),
				"cold": evidence.get("cold_metric"),
				"warm": evidence.get("warm_metric"),
				"failure": {
					"return_code": evidence.get("return_code"),
					"category": evidence.get("failure_category"),
					"semantic_oracle": evidence.get("semantic_oracle_summary"),
					"semantic_oracle_sha256": evidence.get("semantic_oracle_sha256"),
					"parser": evidence.get("parser_summary"),
					"parser_sha256": evidence.get("parser_sha256"),
					"scan": evidence.get("scan_summary"),
					"scan_sha256": evidence.get("scan_sha256"),
				} if decision.state is ResumeState.LATEST_FAILED else None,
			},
			"host_load": dict(host_load),
			"lifecycle": dict(lifecycle),
			"evidence_location": {
				"committed_path": evidence.get("committed_path"),
				"archive_uri": evidence.get("archive_uri"),
				"archive_sha256": evidence.get("archive_sha256"),
			},
		}
		if kind == "discovery" and schedule is not None:
			raise ArchiveContractError("discovery rows must not claim a performance schedule")
		if kind == "performance" and schedule is None:
			raise ArchiveContractError("performance rows require exact schedule facts")
		if kind == "performance" and schedule is not None and (
			schedule.get("period") != identity.get("period") or schedule.get("order") != identity.get("order")
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
				or manifest.get("status") != evidence.get("status")
			):
				return {"code": "EVIDENCE_REVALIDATION_MISMATCH", "detail": "revalidated evidence changed identity, schema, or status"}, None
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
