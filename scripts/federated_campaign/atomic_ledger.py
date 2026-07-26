# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

"""Crash-consistent local evidence ledger for Docker campaign results."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import uuid
from dataclasses import dataclass
from pathlib import Path

from scripts.federated_campaign.determinism_contract import CampaignContractError, validate_phase_bundle


_PHASE_ARTIFACTS = (
	("raw_coordinator.log", "raw_log"),
	("output.bin", "output"),
	("semantic_oracle.json", "semantic_oracle"),
	("return_code.txt", "return_code"),
	("scan.json", "scan"),
	("metric.json", "metric"),
	("checksums.json", "checksums_manifest"),
)

PUBLICATION_BOUNDARIES = (
	"after_stage_created",
	"after_record_state_fsync",
	*(f"after_cold_{label}_fsync" for _, label in _PHASE_ARTIFACTS),
	"after_cold_bundle_fsync",
	*(f"after_warm_{label}_fsync" for _, label in _PHASE_ARTIFACTS),
	"after_warm_bundle_fsync",
	"after_shared_manifest_fsync",
	"after_bundle_manifest_fsync",
	"after_staging_dir_fsync",
	"after_commit_rename",
	"after_index_temp_fsync",
	"after_index_replace",
)



class LedgerContractError(ValueError):
	"""Raised when evidence or identity violates the ledger contract."""


class InjectedPublicationCrash(RuntimeError):
	"""Test-only crash injected immediately after a durable boundary."""


@dataclass(frozen=True)
class DiscoveryKey:
	cell: str
	attempt: int
	run_token: str
	manifest_hash: str

	def as_dict(self) -> dict[str, object]:
		_validate_text("cell", self.cell)
		_validate_positive_int("attempt", self.attempt)
		_validate_text("run_token", self.run_token)
		_validate_text("manifest_hash", self.manifest_hash)
		return {
			"kind": "discovery",
			"cell": self.cell,
			"attempt": self.attempt,
			"run_token": self.run_token,
			"manifest_hash": self.manifest_hash,
		}


@dataclass(frozen=True)
class PerformanceKey:
	cell: str
	lifecycle_replicate: int
	period: int
	order: str
	manifest_hash: str

	def as_dict(self) -> dict[str, object]:
		_validate_text("cell", self.cell)
		_validate_positive_int("lifecycle_replicate", self.lifecycle_replicate)
		_validate_positive_int("period", self.period)
		_validate_text("order", self.order)
		_validate_text("manifest_hash", self.manifest_hash)
		return {
			"kind": "performance",
			"cell": self.cell,
			"lifecycle_replicate": self.lifecycle_replicate,
			"period": self.period,
			"order": self.order,
			"manifest_hash": self.manifest_hash,
		}


LedgerKey = DiscoveryKey | PerformanceKey


def _validate_text(name: str, value: object) -> None:
	if not isinstance(value, str) or not value or value.strip() != value:
		raise LedgerContractError(f"{name} must be a non-empty normalized string")


def _validate_positive_int(name: str, value: object) -> None:
	if isinstance(value, bool) or not isinstance(value, int) or value < 1:
		raise LedgerContractError(f"{name} must be a positive integer")


def _canonical_bytes(value: object) -> bytes:
	return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def _validate_identity(identity: object) -> dict[str, object]:
	if not isinstance(identity, dict):
		raise LedgerContractError("record identity must be a JSON object")
	kind = identity.get("kind")
	try:
		if kind == "discovery" and set(identity) == {"kind", "cell", "attempt", "run_token", "manifest_hash"}:
			validated = DiscoveryKey(
				identity["cell"], identity["attempt"], identity["run_token"], identity["manifest_hash"]
			).as_dict()
		elif kind == "performance" and set(identity) == {
			"kind", "cell", "lifecycle_replicate", "period", "order", "manifest_hash"
		}:
			validated = PerformanceKey(
				identity["cell"],
				identity["lifecycle_replicate"],
				identity["period"],
				identity["order"],
				identity["manifest_hash"],
			).as_dict()
		else:
			raise LedgerContractError("record identity has an unknown or non-exact schema")
	except (KeyError, TypeError) as error:
		raise LedgerContractError("record identity has invalid field types") from error
	return validated


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


def _write_fsynced(path: Path, contents: bytes) -> None:
	with path.open("xb") as handle:
		handle.write(contents)
		handle.flush()
		os.fsync(handle.fileno())


def _read_json(path: Path, label: str) -> dict[str, object]:
	try:
		value = json.loads(path.read_text(encoding="utf-8"))
	except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
		raise LedgerContractError(f"invalid {label}: {path}") from error
	if not isinstance(value, dict):
		raise LedgerContractError(f"invalid {label}: expected JSON object")
	return value


class AtomicEvidenceLedger:
	"""Publishes complete evidence rows with local filesystem atomicity."""

	def __init__(self, root: Path):
		self.root = Path(root)
		self.staging = self.root / "staging"
		self.committed = self.root / "committed"
		self.quarantine = self.root / "quarantine"
		self.index_path = self.root / "index.json"
		for path in (self.root, self.staging, self.committed, self.quarantine):
			path.mkdir(parents=True, exist_ok=True)
		devices = {path.stat().st_dev for path in (self.root, self.staging, self.committed, self.quarantine)}
		if len(devices) != 1:
			raise LedgerContractError("ledger staging, committed, quarantine, and index must share one filesystem")
		self._quarantine_orphans()
		self._replace_index(self._inspect_records(self._load_prior_index()))

	def publish_success(
		self,
		key: LedgerKey,
		cold_bundle: Path,
		warm_bundle: Path,
		shared_replicate_manifest: Path,
		*,
		crash_after: str | None = None,
	) -> Path:
		identity = key.as_dict()
		if crash_after is not None and crash_after not in PUBLICATION_BOUNDARIES:
			raise LedgerContractError(f"unknown crash boundary: {crash_after}")
		cold_metric = self._validate_source_phase(cold_bundle, "docker_e2e")
		warm_metric = self._validate_source_phase(warm_bundle, "systemds_total_execution_time")
		shared = self._validate_shared_manifest(shared_replicate_manifest, identity, cold_bundle, warm_bundle)
		self._reject_ambiguous_discovery_attempt(identity)

		record_id = hashlib.sha256(_canonical_bytes(identity)).hexdigest()
		kind_dir = self.committed / str(identity["kind"])
		kind_dir.mkdir(exist_ok=True)
		_fsync_directory(self.committed)
		destination = kind_dir / record_id
		if destination.exists():
			record = self._inspect_committed(destination, None)
			if record.get("valid") and record.get("identity") == identity and record.get("status") == "success":
				return destination
			raise LedgerContractError(f"committed identity collision: {destination}")

		stage = self.staging / f"{record_id}.{uuid.uuid4().hex}"
		stage.mkdir()
		self._crash(crash_after, "after_stage_created")
		self._write_record_state(stage, identity, "success")
		self._crash(crash_after, "after_record_state_fsync")
		self._copy_phase(Path(cold_bundle), stage / "cold", "docker_e2e", "cold", crash_after)
		self._crash(crash_after, "after_cold_bundle_fsync")
		self._copy_phase(
			Path(warm_bundle), stage / "warm", "systemds_total_execution_time", "warm", crash_after
		)
		self._crash(crash_after, "after_warm_bundle_fsync")
		shared_path = stage / "shared_replicate_manifest.json"
		_write_fsynced(shared_path, _canonical_bytes(shared) + b"\n")
		self._crash(crash_after, "after_shared_manifest_fsync")

		bundle_manifest = {
			"schema": "systemds-federated-evidence/v1",
			"identity": identity,
			"status": "success",
			"cold_metric": cold_metric,
			"warm_metric": warm_metric,
			"cold_checksums_sha256": _sha256(stage / "cold" / "checksums.json"),
			"warm_checksums_sha256": _sha256(stage / "warm" / "checksums.json"),
			"shared_replicate_manifest_sha256": _sha256(shared_path),
		}
		_write_fsynced(stage / "bundle_manifest.json", _canonical_bytes(bundle_manifest) + b"\n")
		self._crash(crash_after, "after_bundle_manifest_fsync")
		_fsync_directory(stage)
		self._crash(crash_after, "after_staging_dir_fsync")
		os.rename(stage, destination)
		_fsync_directory(self.staging)
		_fsync_directory(kind_dir)
		self._crash(crash_after, "after_commit_rename")
		self._publish_derived_index(crash_after)
		return destination

	def publish_failure(self, key: LedgerKey, reason: str) -> Path:
		identity = key.as_dict()
		_validate_text("failure reason", reason)
		self._reject_ambiguous_discovery_attempt(identity)
		record_id = hashlib.sha256(_canonical_bytes(identity)).hexdigest()
		kind_dir = self.committed / str(identity["kind"])
		kind_dir.mkdir(exist_ok=True)
		_fsync_directory(self.committed)
		destination = kind_dir / record_id
		if destination.exists():
			raise LedgerContractError(f"committed identity already exists: {destination}")
		stage = self.staging / f"{record_id}.{uuid.uuid4().hex}"
		stage.mkdir()
		self._write_record_state(stage, identity, "failed")
		manifest = {
			"schema": "systemds-federated-evidence/v1",
			"identity": identity,
			"status": "failed",
			"reason": reason,
		}
		_write_fsynced(stage / "bundle_manifest.json", _canonical_bytes(manifest) + b"\n")
		_fsync_directory(stage)
		os.rename(stage, destination)
		_fsync_directory(self.staging)
		_fsync_directory(kind_dir)
		self._publish_derived_index(None)
		return destination

	def latest_discovery_success(self, cell: str, manifest_hash: str) -> dict[str, object] | None:
		_validate_text("cell", cell)
		_validate_text("manifest_hash", manifest_hash)
		inspected = self._inspect_records(self._load_prior_index())
		if any(
			Path(str(record.get("path", ""))).parent.name == "discovery"
			and record.get("identity") == {}
			for record in inspected
		):
			return None
		records = [
			record
			for record in inspected
			if record.get("identity", {}).get("kind") == "discovery"
			and record.get("identity", {}).get("cell") == cell
			and record.get("identity", {}).get("manifest_hash") == manifest_hash
		]
		if not records:
			return None
		latest_attempt = max(int(record["identity"]["attempt"]) for record in records)
		latest = [record for record in records if record["identity"]["attempt"] == latest_attempt]
		if len(latest) != 1:
			return None
		record = latest[0]
		if record.get("status") != "success" or record.get("valid") is not True:
			return None
		manifest = dict(record["manifest"])
		manifest["committed_path"] = record["path"]
		return manifest

	def record_summaries(self) -> list[dict[str, object]]:
		"""Return current valid/invalid local record facts for archive arbitration."""
		return self._inspect_records(self._load_prior_index())

	def validate_committed(self, record_dir: Path) -> dict[str, object]:
		"""Validate a committed-format directory before or after archive transport."""
		record = self._inspect_committed(Path(record_dir), None)
		if record.get("valid") is not True or record.get("status") != "success":
			raise LedgerContractError(f"committed success is invalid: {record_dir}")
		return dict(record["manifest"])

	def reconcile_index(self) -> None:
		"""Atomically rebuild the derived local index after verified eviction."""
		self._replace_index(self._inspect_records(self._load_prior_index()))

	def performance_success(self, key: PerformanceKey) -> dict[str, object] | None:
		identity = key.as_dict()
		for record in self._inspect_records(self._load_prior_index()):
			if record.get("identity") == identity:
				if record.get("status") == "success" and record.get("valid") is True:
					manifest = dict(record["manifest"])
					manifest["committed_path"] = record["path"]
					return manifest
				return None
		return None

	def _validate_source_phase(self, path: Path, metric_kind: str) -> dict[str, object]:
		try:
			return validate_phase_bundle(Path(path), metric_kind)
		except CampaignContractError as error:
			raise LedgerContractError(str(error)) from error

	def _validate_shared_manifest(
		self, path: Path, identity: dict[str, object], cold: Path, warm: Path
	) -> dict[str, object]:
		shared = _read_json(Path(path), "shared replicate manifest")
		if shared.get("identity") != identity:
			raise LedgerContractError("shared replicate manifest identity mismatch")
		expected = {
			"cold_checksums_sha256": _sha256(Path(cold) / "checksums.json"),
			"warm_checksums_sha256": _sha256(Path(warm) / "checksums.json"),
		}
		for name, digest in expected.items():
			if shared.get(name) != digest:
				raise LedgerContractError(f"shared replicate manifest {name} mismatch")
		return shared

	def _copy_phase(
		self,
		source: Path,
		destination: Path,
		metric_kind: str,
		phase_label: str,
		crash_after: str | None,
	) -> None:
		destination.mkdir()
		for filename, artifact_label in _PHASE_ARTIFACTS:
			source_path = source / filename
			if source_path.is_symlink() or not source_path.is_file():
				raise LedgerContractError(f"missing or unsafe phase artifact: {source_path}")
			with source_path.open("rb") as reader, (destination / filename).open("xb") as writer:
				shutil.copyfileobj(reader, writer, length=1024 * 1024)
				writer.flush()
				os.fsync(writer.fileno())
			self._crash(crash_after, f"after_{phase_label}_{artifact_label}_fsync")
		_fsync_directory(destination)
		self._validate_source_phase(destination, metric_kind)

	def _reject_ambiguous_discovery_attempt(self, identity: dict[str, object]) -> None:
		if identity["kind"] != "discovery":
			return
		for record in self._inspect_records(self._load_prior_index()):
			other = record.get("identity", {})
			if (
				other.get("kind") == "discovery"
				and other.get("cell") == identity["cell"]
				and other.get("manifest_hash") == identity["manifest_hash"]
				and other.get("attempt") == identity["attempt"]
				and other != identity
			):
				raise LedgerContractError("discovery attempt number already belongs to another run token")

	def _crash(self, requested: str | None, boundary: str) -> None:
		if requested == boundary:
			raise InjectedPublicationCrash(boundary)

	def _write_record_state(self, stage: Path, identity: dict[str, object], status: str) -> None:
		state = {
			"schema": "systemds-federated-record-state/v1",
			"identity": identity,
			"status": status,
		}
		_write_fsynced(stage / "record_state.json", _canonical_bytes(state) + b"\n")

	def _quarantine_orphans(self) -> None:
		orphans = list(self.staging.iterdir()) + list(self.root.glob(".index.*.tmp"))
		for orphan in orphans:
			destination = self.quarantine / f"{orphan.name}.{uuid.uuid4().hex}"
			os.replace(orphan, destination)
		_fsync_directory(self.staging)
		_fsync_directory(self.quarantine)
		_fsync_directory(self.root)

	def _load_prior_index(self) -> dict[str, dict[str, object]]:
		if not self.index_path.is_file():
			return {}
		try:
			value = json.loads(self.index_path.read_text(encoding="utf-8"))
		except (OSError, UnicodeDecodeError, json.JSONDecodeError):
			return {}
		if not isinstance(value, dict) or not isinstance(value.get("records"), list):
			return {}
		return {
			str(record.get("path")): record
			for record in value["records"]
			if isinstance(record, dict) and isinstance(record.get("path"), str)
		}

	def _inspect_records(self, prior: dict[str, dict[str, object]]) -> list[dict[str, object]]:
		records = []
		for kind_dir in sorted(path for path in self.committed.iterdir() if path.is_dir()):
			for record_dir in sorted(path for path in kind_dir.iterdir() if path.is_dir()):
				records.append(self._inspect_committed(record_dir, prior.get(str(record_dir))))
		return records

	def _inspect_committed(self, record_dir: Path, prior: dict[str, object] | None) -> dict[str, object]:
		result: dict[str, object] | None = None
		state_path = record_dir / "record_state.json"
		if state_path.is_file():
			try:
				state = _read_json(state_path, "record state")
				if set(state) != {"schema", "identity", "status"} or state.get("schema") != "systemds-federated-record-state/v1":
					raise LedgerContractError("record state schema is invalid")
				state_identity = _validate_identity(state.get("identity"))
				state_status = state.get("status")
				if state_status not in ("success", "failed"):
					raise LedgerContractError("record state status is invalid")
				self._validate_record_location(record_dir, state_identity)
				result = {
					"path": str(record_dir),
					"identity": state_identity,
					"status": state_status,
					"valid": False,
					"manifest": {},
				}
			except (LedgerContractError, OSError):
				result = None
		manifest_path = record_dir / "bundle_manifest.json"
		try:
			manifest = _read_json(manifest_path, "bundle manifest")
			if manifest.get("schema") != "systemds-federated-evidence/v1":
				raise LedgerContractError("bundle manifest schema is invalid")
			identity = _validate_identity(manifest.get("identity"))
			status = manifest.get("status")
			if status not in ("success", "failed"):
				raise LedgerContractError("bundle manifest status is invalid")
			self._validate_record_location(record_dir, identity)
			if result is not None and (result["identity"] != identity or result["status"] != status):
				return result
			result = {"path": str(record_dir), "identity": identity, "status": status, "valid": False, "manifest": manifest}
		except (LedgerContractError, OSError):
			if result is not None:
				return result
			if prior is not None:
				preserved = dict(prior)
				preserved["path"] = str(record_dir)
				preserved["valid"] = False
				return preserved
			return {"path": str(record_dir), "identity": {}, "status": "invalid", "valid": False, "manifest": {}}
		try:
			if status == "failed":
				result["valid"] = isinstance(manifest.get("reason"), str) and bool(manifest["reason"])
				return result
			cold = self._validate_source_phase(record_dir / "cold", "docker_e2e")
			warm = self._validate_source_phase(record_dir / "warm", "systemds_total_execution_time")
			shared_path = record_dir / "shared_replicate_manifest.json"
			shared = self._validate_shared_manifest(shared_path, identity, record_dir / "cold", record_dir / "warm")
			checks = {
				"cold_checksums_sha256": _sha256(record_dir / "cold" / "checksums.json"),
				"warm_checksums_sha256": _sha256(record_dir / "warm" / "checksums.json"),
				"shared_replicate_manifest_sha256": _sha256(shared_path),
			}
			if any(manifest.get(name) != digest for name, digest in checks.items()):
				return result
			if manifest.get("cold_metric") != cold or manifest.get("warm_metric") != warm:
				return result
			if shared.get("identity") != identity:
				return result
			result["valid"] = True
			return result
		except (LedgerContractError, OSError):
			return result

	def _validate_record_location(self, record_dir: Path, identity: dict[str, object]) -> None:
		if record_dir.parent.name != identity["kind"]:
			raise LedgerContractError("committed kind directory does not match identity")
		if record_dir.name != hashlib.sha256(_canonical_bytes(identity)).hexdigest():
			raise LedgerContractError("committed directory does not match identity hash")

	def _publish_derived_index(self, crash_after: str | None) -> None:
		records = self._inspect_records(self._load_prior_index())
		index = {"schema": "systemds-federated-evidence-index/v1", "records": records}
		temp = self.root / f".index.{uuid.uuid4().hex}.tmp"
		_write_fsynced(temp, _canonical_bytes(index) + b"\n")
		self._crash(crash_after, "after_index_temp_fsync")
		os.replace(temp, self.index_path)
		_fsync_directory(self.root)
		self._crash(crash_after, "after_index_replace")

	def _replace_index(self, records: list[dict[str, object]]) -> None:
		index = {"schema": "systemds-federated-evidence-index/v1", "records": records}
		temp = self.root / f".index.{uuid.uuid4().hex}.tmp"
		_write_fsynced(temp, _canonical_bytes(index) + b"\n")
		os.replace(temp, self.index_path)
		_fsync_directory(self.root)
