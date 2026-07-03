"""Object storage helpers for offline model lifecycle artifacts.

Supports two backends selected per-URI (or via ``OBJECT_STORAGE_PROVIDER``):

* ``s3``  — S3-compatible (MinIO in dev) via ``boto3`` and static keys.
* ``gcs`` — Google Cloud Storage via ``google-cloud-storage`` and Application
  Default Credentials / Workload Identity (no keys in env).

A ``gs://`` URI always uses GCS and an ``s3://`` URI always uses S3, regardless
of the env default.
"""
from __future__ import annotations

import hashlib
import os
import pathlib
import tempfile
from urllib.parse import urlparse

import boto3
from botocore.client import Config

DEFAULT_ENDPOINT = "http://localhost:9000"
DEFAULT_ACCESS_KEY = "minioadmin"
DEFAULT_SECRET_KEY = "minioadmin"
DEFAULT_REGION = "us-east-1"


def is_object_uri(uri: str | pathlib.Path) -> bool:
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
        endpoint_url=os.environ.get("OBJECT_STORAGE_ENDPOINT_URL", DEFAULT_ENDPOINT),
        aws_access_key_id=os.environ.get("OBJECT_STORAGE_ACCESS_KEY", DEFAULT_ACCESS_KEY),
        aws_secret_access_key=os.environ.get("OBJECT_STORAGE_SECRET_KEY", DEFAULT_SECRET_KEY),
        region_name=os.environ.get("OBJECT_STORAGE_REGION", DEFAULT_REGION),
        config=Config(signature_version="s3v4"),
    )


def gcs_client():
    # Imported lazily so the S3/MinIO dev path never requires the GCS SDK.
    from google.cloud import storage

    project = os.environ.get("GOOGLE_CLOUD_PROJECT") or os.environ.get("GCP_PROJECT")
    return storage.Client(project=project) if project else storage.Client()


def ensure_bucket(bucket: str) -> None:
    # GCS buckets are provisioned out-of-band (Terraform); nothing to do.
    s3 = client()
    try:
        s3.head_bucket(Bucket=bucket)
    except Exception:
        s3.create_bucket(Bucket=bucket)


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def upload_file(path: pathlib.Path, uri: str) -> str:
    bucket, key = parse_object_uri(uri)
    if _provider(uri) == "gcs":
        gcs_client().bucket(bucket).blob(key).upload_from_filename(str(path))
        return uri
    ensure_bucket(bucket)
    client().upload_file(str(path), bucket, key)
    return uri


def download_file(uri: str, destination: pathlib.Path | None = None) -> pathlib.Path:
    bucket, key = parse_object_uri(uri)
    if destination is None:
        suffix = pathlib.Path(key).suffix
        fd, name = tempfile.mkstemp(prefix="dpp-artifact-", suffix=suffix)
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


def upload_directory(local_dir: pathlib.Path, base_uri: str) -> list[tuple[pathlib.Path, str]]:
    if not is_object_uri(base_uri):
        return [(path, str(path)) for path in sorted(local_dir.rglob("*")) if path.is_file()]
    uploaded = []
    prefix = base_uri.rstrip("/")
    for path in sorted(local_dir.rglob("*")):
        if not path.is_file():
            continue
        key_suffix = path.relative_to(local_dir).as_posix()
        uri = f"{prefix}/{key_suffix}"
        upload_file(path, uri)
        uploaded.append((path, uri))
    return uploaded
