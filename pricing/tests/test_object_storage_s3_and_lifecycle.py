"""S3-path coverage for the object-storage adapter + consumer/relay lifecycle.

The GCS path is covered by test_object_storage_gcs.py; here we cover the S3
branch (parse/client/download/list) with mocked boto3 and the start/stop thread
lifecycle of the consumers and the outbox relay with mocked threading.
"""
from __future__ import annotations

import pathlib
from unittest.mock import MagicMock, patch

import pytest

from app import object_storage as oss


# ── S3 URI parsing ──

def test_parse_s3_uri_valid_and_invalid():
    assert oss.parse_s3_uri("s3://models/line/model.joblib") == ("models", "line/model.joblib")
    with pytest.raises(ValueError):
        oss.parse_s3_uri("s3://bucket-only")
    with pytest.raises(ValueError):
        oss.parse_s3_uri("gs://wrong/scheme")


def test_parse_object_uri_routes_by_scheme():
    assert oss.parse_object_uri("s3://b/k") == ("b", "k")
    assert oss.parse_object_uri("gs://b/k") == ("b", "k")


def test_provider_env_default(monkeypatch):
    monkeypatch.delenv("OBJECT_STORAGE_PROVIDER", raising=False)
    assert oss._provider("relative/path") == "s3"
    monkeypatch.setenv("OBJECT_STORAGE_PROVIDER", "gcs")
    assert oss._provider("relative/path") == "gcs"


# ── client() builds an S3 client from env ──

def test_client_builds_boto3_from_env(monkeypatch):
    captured = {}

    def fake_boto3_client(service, **kwargs):
        captured["service"] = service
        captured["kwargs"] = kwargs
        return MagicMock()

    monkeypatch.setenv("OBJECT_STORAGE_ENDPOINT_URL", "http://minio:9000")
    monkeypatch.setenv("OBJECT_STORAGE_ACCESS_KEY", "key-x")
    monkeypatch.setenv("OBJECT_STORAGE_SECRET_KEY", "secret-y")
    with patch("app.object_storage.boto3.client", side_effect=fake_boto3_client):
        oss.client()

    assert captured["service"] == "s3"
    assert captured["kwargs"]["endpoint_url"] == "http://minio:9000"
    assert captured["kwargs"]["aws_access_key_id"] == "key-x"
    assert captured["kwargs"]["aws_secret_access_key"] == "secret-y"


# ── download_file / materialize on the S3 path ──

def test_download_file_s3_uses_boto3(monkeypatch, tmp_path):
    fake_client = MagicMock()

    def fake_download(bucket, key, dest):
        pathlib.Path(dest).write_text("s3-artifact")

    fake_client.download_file.side_effect = fake_download
    monkeypatch.setattr(oss, "client", lambda: fake_client)
    monkeypatch.setattr(oss, "gcs_client", lambda: pytest.fail("gcs used for s3://"))

    out = oss.download_file("s3://models/line/model.joblib", tmp_path / "m.joblib")
    assert out.read_text() == "s3-artifact"
    fake_client.download_file.assert_called_once()


def test_materialize_local_path_is_passthrough(tmp_path):
    p = tmp_path / "local.joblib"
    p.write_text("x")
    assert oss.materialize(str(p)) == pathlib.Path(str(p))


def test_materialize_s3_uri_downloads(monkeypatch):
    monkeypatch.setattr(oss, "download_file", lambda uri: pathlib.Path("/tmp/materialized"))
    assert oss.materialize("s3://b/k") == pathlib.Path("/tmp/materialized")


# ── download_prefix on the S3 path (paginator) ──

def test_download_prefix_s3_paginator(monkeypatch, tmp_path):
    fake_client = MagicMock()
    paginator = MagicMock()
    paginator.paginate.return_value = [
        {"Contents": [{"Key": "data/meta.json"}, {"Key": "data/sub/geo.csv"}]},
        {"Contents": [{"Key": "data/"}]},  # dir marker, skipped
    ]
    fake_client.get_paginator.return_value = paginator
    monkeypatch.setattr(oss, "client", lambda: fake_client)

    downloaded = []

    def fake_download(uri, target):
        pathlib.Path(target).parent.mkdir(parents=True, exist_ok=True)
        pathlib.Path(target).write_text("d")
        downloaded.append(uri)
        return pathlib.Path(target)

    monkeypatch.setattr(oss, "download_file", fake_download)

    dest = tmp_path / "ref"
    written = oss.download_prefix("s3://bkt/data", dest)

    rel = sorted(p.relative_to(dest).as_posix() for p in written)
    assert rel == ["meta.json", "sub/geo.csv"]
    assert all(u.startswith("s3://bkt/") for u in downloaded)


# ── sha256_file ──

def test_sha256_file_matches_hashlib(tmp_path):
    import hashlib
    p = tmp_path / "blob.bin"
    p.write_bytes(b"hello world" * 1000)
    assert oss.sha256_file(p) == hashlib.sha256(b"hello world" * 1000).hexdigest()


# ── consumer / relay start-stop lifecycle ──

def test_claim_settled_start_is_idempotent_when_alive():
    from app.consumers import claim_settled_consumer as c
    c._consumer_thread = MagicMock()
    c._consumer_thread.is_alive.return_value = True
    with patch("threading.Thread") as thread_cls:
        c.start_consumer()
    thread_cls.assert_not_called()
    c._consumer_thread = None
    c._stop_event = None


def test_claim_settled_start_then_stop():
    from app.consumers import claim_settled_consumer as c
    c._consumer_thread = None
    c._stop_event = None
    fake_thread = MagicMock()
    with patch("threading.Thread", return_value=fake_thread):
        c.start_consumer()
    fake_thread.start.assert_called_once()
    c.stop_consumer()
    assert c._stop_event is None
    assert c._consumer_thread is None


def test_read_model_start_is_idempotent_when_alive():
    from app.consumers import read_model_consumer as c
    c._consumer_thread = MagicMock()
    c._consumer_thread.is_alive.return_value = True
    with patch("threading.Thread") as thread_cls:
        c.start_consumer()
    thread_cls.assert_not_called()
    c._consumer_thread = None
    c._stop_event = None


def test_read_model_start_then_stop():
    from app.consumers import read_model_consumer as c
    c._consumer_thread = None
    c._stop_event = None
    fake_thread = MagicMock()
    with patch("threading.Thread", return_value=fake_thread):
        c.start_consumer()
    fake_thread.start.assert_called_once()
    c.stop_consumer()
    assert c._stop_event is None
    assert c._consumer_thread is None


def test_outbox_relay_start_then_stop():
    import app.outbox_relay as relay
    relay._relay_thread = None
    relay._stop_event = None
    fake_thread = MagicMock()
    with patch("threading.Thread", return_value=fake_thread):
        relay.start_outbox_relay()
    fake_thread.start.assert_called_once()
    relay.stop_outbox_relay()
    assert relay._stop_event is None
    assert relay._relay_thread is None
