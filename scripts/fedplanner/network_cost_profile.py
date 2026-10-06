"""Bind offline planning to one explicitly measured experiment cost profile."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path


DEFAULT_IMAGE = "cofee-experiment:content-0861f4ff197c868f42abf6478b66505f650325c267caa8be2e82b4b6317c7ff0"
PROFILE_ENV = "COFEE_COST_PROFILE"
PROVIDER_PATH = Path(__file__).resolve().parents[3] / "cofee-evaluation/calibration/experiment_profile.py"


def _provider():
    if not PROVIDER_PATH.is_file():
        raise ValueError(f"measured cost profile provider is absent: {PROVIDER_PATH}")
    spec = importlib.util.spec_from_file_location("cofee_experiment_profile_provider", PROVIDER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _selected_profile_source(profile_path):
    selected = profile_path if profile_path is not None else os.environ.get(PROFILE_ENV)
    if selected is None or not str(selected).strip():
        raise ValueError(f"an explicitly measured cost profile is required via {PROFILE_ENV} or profile_path")
    path = Path(selected).expanduser().resolve()
    if not path.exists() or not (path.is_file() or path.is_dir()):
        raise ValueError(f"measured cost profile source is missing: {path}")
    return path


def _load_profiles(provider, profile_path):
    source = _selected_profile_source(profile_path)
    paths = [source] if source.is_file() else sorted(source.glob("*.json"))
    if not paths:
        raise ValueError(f"measured cost profile directory is empty: {source}")
    profiles = []
    for path in paths:
        profile = provider.load_profile(path)
        provider.validate_profile(profile)
        profiles.append((path, profile))
    return source, profiles


def profile_reference(profile_path=None):
    provider = _provider()
    source, profiles = _load_profiles(provider, profile_path)
    if source.is_file():
        return {"path": str(source), "sha256": profiles[0][1]["profile_sha256"]}
    inventory = [{"path": path.name, "profile_sha256": profile["profile_sha256"]}
                 for path, profile in profiles]
    digest = hashlib.sha256(json.dumps(inventory, sort_keys=True,
        separators=(",", ":")).encode()).hexdigest()
    return {"path": str(source), "sha256": digest, "profiles": inventory}


def cost_binding(profile_name, network, *, coordinator_host, worker_hosts, image,
                 profile_path=None):
    """Return a validated measured binding; never synthesize rates or CPU defaults."""
    if not profile_name or not coordinator_host:
        raise ValueError("network profile name and coordinator host are required")
    workers = tuple(worker_hosts)
    if not workers or len(workers) != len(set(workers)):
        raise ValueError("unique worker hosts are required")
    provider = _provider()
    source, profiles = _load_profiles(provider, profile_path)
    matches = []
    for path, profile in profiles:
        try:
            provider.validate_selection(profile, coordinator_host=coordinator_host,
                worker_hosts=workers, image=image, network=network)
        except provider.ProfileError:
            continue
        matches.append((path, profile))
    if not matches:
        raise ValueError(f"no measured cost profile matches the selected environment: {source}")
    if len(matches) != 1:
        raise ValueError(f"measured cost profile selection is ambiguous: {source}")
    path, profile = matches[0]
    environment = profile.get("cost_environment")
    if not isinstance(environment, dict) or not environment:
        raise ValueError("measured cost profile has no cost_environment")
    return {
        "status": "measured",
        "profile_sha256": profile["profile_sha256"],
        "profile_path": str(path),
        "profile_name": profile_name,
        "selected_hosts": {"coordinator": coordinator_host, "workers": list(workers)},
        "cost_environment": dict(environment),
    }


def network_cost(profile_name, network, *, coordinator_host, worker_hosts, image=DEFAULT_IMAGE,
                 profile_path=None):
    return cost_binding(profile_name, network, coordinator_host=coordinator_host,
        worker_hosts=worker_hosts, image=image, profile_path=profile_path)["cost_environment"]
