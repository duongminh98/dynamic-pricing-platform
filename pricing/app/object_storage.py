"""S3-compatible object storage helpers for pricing runtime artifacts."""
from __future__ import annotations

import hashlib
import os
import pathlib
import tempfile
from urllib.parse import urlparse

import boto3
from botocore.client import Config


def is_object_uri(uri: str | pathlib.Path | None) -> bool:
    return bool(uri) and str(uri).startswith("s3://")


def parse_s3_uri(uri: str) -> tuple[str, str]:
    parsed = urlparse(uri)
    if parsed.scheme != "s3" or not parsed.netloc or not parsed.path.strip("/"):
        raise ValueError(f"Invalid S3 URI: {uri}")
    return parsed.netloc, parsed.path.lstrip("/")


def client():
    return boto3.client(
        "s3",
        endpoint_url=os.environ.get("OBJECT_STORAGE_ENDPOINT_URL", "http://localhost:9000"),
        aws_access_key_id=os.environ.get("OBJECT_STORAGE_ACCESS_KEY", "minioadmin"),
        aws_secret_access_key=os.environ.get("OBJECT_STORAGE_SECRET_KEY", "minioadmin"),
        region_name=os.environ.get("OBJECT_STORAGE_REGION", "us-east-1"),
        config=Config(signature_version="s3v4"),
    )


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_file(uri: str, destination: pathlib.Path | None = None) -> pathlib.Path:
    bucket, key = parse_s3_uri(uri)
    if destination is None:
        suffix = pathlib.Path(key).suffix
        fd, name = tempfile.mkstemp(prefix="dpp-pricing-artifact-", suffix=suffix)
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
