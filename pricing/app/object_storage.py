"""Object storage helpers for pricing runtime artifacts.

Supports two backends selected per-URI (or via ``OBJECT_STORAGE_PROVIDER``):

* ``s3``  — S3-compatible (MinIO in dev) via ``boto3`` and static keys.
* ``gcs`` — Google Cloud Storage via ``google-cloud-storage`` and Application
  Default Credentials / Workload Identity (no keys in env).

A ``gs://`` URI always uses GCS and an ``s3://`` URI always uses S3, regardless
of the env default, so mixed environments (e.g. reading a ``gs://`` champion
artifact from a MinIO-defaulted dev box) resolve correctly.
"""
from __future__ import annotations

import hashlib
import os
import pathlib
import tempfile
from urllib.parse import urlparse

import boto3
from botocore.client import Config


def is_object_uri(uri: str | pathlib.Path | None) -> bool:
    s = str(uri or "")
    return s.startswith("s3://") or s.startswith("gs://")


def _provider(uri: str | None = None) -> str:
    """Resolve the backend for a URI: scheme wins, else the env default."""
    s = str(uri or "")
    if s.startswith("gs://"):
        return "gcs"
    if s.startswith("s3://"):
        return "s3"
    return os.environ.get("OBJECT_STORAGE_PROVIDER", "s3").lower()


def parse_s3_uri(uri: str) -> tuple[str, str]:
    parsed = urlparse(uri)
    if parsed.scheme != "s3" or not parsed.netloc or not parsed.path.strip("/"):
        raise ValueError(f"Invalid S3 URI: {uri}")
    return parsed.netloc, parsed.path.lstrip("/")


def parse_gcs_uri(uri: str) -> tuple[str, str]:
    parsed = urlparse(uri)
    if parsed.scheme != "gs" or not parsed.netloc or not parsed.path.strip("/"):
        raise ValueError(f"Invalid GCS URI: {uri}")
    return parsed.netloc, parsed.path.lstrip("/")


def parse_object_uri(uri: str) -> tuple[str, str]:
    """Return (bucket, key) for either an ``s3://`` or ``gs://`` URI."""
    if _provider(uri) == "gcs":
        return parse_gcs_uri(uri)
    return parse_s3_uri(uri)


def client():
    return boto3.client(
        "s3",
        endpoint_url=os.environ.get("OBJECT_STORAGE_ENDPOINT_URL", "http://localhost:9000"),
        aws_access_key_id=os.environ.get("OBJECT_STORAGE_ACCESS_KEY", "minioadmin"),
        aws_secret_access_key=os.environ.get("OBJECT_STORAGE_SECRET_KEY", "minioadmin"),
        region_name=os.environ.get("OBJECT_STORAGE_REGION", "us-east-1"),
        config=Config(signature_version="s3v4"),
    )


def gcs_client():
    # Imported lazily so the S3/MinIO dev path never requires the GCS SDK.
    from google.cloud import storage

    project = os.environ.get("GOOGLE_CLOUD_PROJECT") or os.environ.get("GCP_PROJECT")
    return storage.Client(project=project) if project else storage.Client()


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_file(uri: str, destination: pathlib.Path | None = None) -> pathlib.Path:
    bucket, key = parse_object_uri(uri)
    if destination is None:
        suffix = pathlib.Path(key).suffix
        fd, name = tempfile.mkstemp(prefix="dpp-pricing-artifact-", suffix=suffix)
        os.close(fd)
        destination = pathlib.Path(name)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if _provider(uri) == "gcs":
        gcs_client().bucket(bucket).blob(key).download_to_filename(str(destination))
    else:
        client().download_file(bucket, key, str(destination))
    return destination


def materialize(uri_or_path: str | pathlib.Path) -> pathlib.Path:
    raw = str(uri_or_path)
    if is_object_uri(raw):
        return download_file(raw)
    return pathlib.Path(raw)


def _list_keys(bucket: str, prefix: str, provider: str) -> list[str]:
    prefix = prefix.lstrip("/")
    if provider == "gcs":
        return [b.name for b in gcs_client().list_blobs(bucket, prefix=prefix)]
    keys: list[str] = []
    paginator = client().get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        keys.extend(obj["Key"] for obj in page.get("Contents", []))
    return keys


def download_prefix(base_uri: str, destination: pathlib.Path) -> list[pathlib.Path]:
    """Download every object under ``base_uri`` into ``destination``, preserving
    the key layout below the prefix. Used to hydrate the reference-data tree at
    container startup when a GCS/S3 volume mount is not used (GCP_DEPLOYMENT §6.3).
    """
    provider = _provider(base_uri)
    bucket, prefix = parse_object_uri(base_uri.rstrip("/") + "/")
    destination.mkdir(parents=True, exist_ok=True)
    written: list[pathlib.Path] = []
    for key in _list_keys(bucket, prefix, provider):
        if key.endswith("/"):
            continue
        rel = key[len(prefix):].lstrip("/")
        if not rel:
            continue
        target = destination / rel
        download_file(f"{'gs' if provider == 'gcs' else 's3'}://{bucket}/{key}", target)
        written.append(target)
    return written
