"""S3-compatible object storage helpers for offline model lifecycle artifacts."""
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
    return str(uri).startswith("s3://")


def parse_s3_uri(uri: str) -> tuple[str, str]:
    parsed = urlparse(uri)
    if parsed.scheme != "s3" or not parsed.netloc or not parsed.path.strip("/"):
        raise ValueError(f"Invalid S3 URI: {uri}")
    return parsed.netloc, parsed.path.lstrip("/")


def client():
    return boto3.client(
        "s3",
        endpoint_url=os.environ.get("OBJECT_STORAGE_ENDPOINT_URL", DEFAULT_ENDPOINT),
        aws_access_key_id=os.environ.get("OBJECT_STORAGE_ACCESS_KEY", DEFAULT_ACCESS_KEY),
        aws_secret_access_key=os.environ.get("OBJECT_STORAGE_SECRET_KEY", DEFAULT_SECRET_KEY),
        region_name=os.environ.get("OBJECT_STORAGE_REGION", DEFAULT_REGION),
        config=Config(signature_version="s3v4"),
    )


def ensure_bucket(bucket: str) -> None:
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
    bucket, key = parse_s3_uri(uri)
    ensure_bucket(bucket)
    client().upload_file(str(path), bucket, key)
    return uri


def download_file(uri: str, destination: pathlib.Path | None = None) -> pathlib.Path:
    bucket, key = parse_s3_uri(uri)
    if destination is None:
        suffix = pathlib.Path(key).suffix
        fd, name = tempfile.mkstemp(prefix="dpp-artifact-", suffix=suffix)
        os.close(fd)
        destination = pathlib.Path(name)
    destination.parent.mkdir(parents=True, exist_ok=True)
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
