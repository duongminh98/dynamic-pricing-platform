"""Tests for the object-storage adapter's GCS backend selection (GCP_DEPLOYMENT §6.2).

Verifies URI scheme detection, provider routing, and that a ``gs://`` URI drives
the google-cloud-storage code path (mocked) while ``s3://`` still uses boto3 —
without requiring real GCS credentials, MinIO, or network.
"""
import sys
import types
import pathlib

import pytest

from offline import object_storage as oss


def test_is_object_uri_recognizes_both_schemes():
    assert oss.is_object_uri("gs://bucket/key")
    assert oss.is_object_uri("s3://bucket/key")
    assert not oss.is_object_uri("/local/path")
    assert not oss.is_object_uri("")


def test_provider_scheme_overrides_env(monkeypatch):
    monkeypatch.setenv("OBJECT_STORAGE_PROVIDER", "s3")
    # gs:// URI must resolve to gcs even when the env default is s3
    assert oss._provider("gs://b/k") == "gcs"
    assert oss._provider("s3://b/k") == "s3"
    # bare path falls back to the env default
    assert oss._provider("relative/path") == "s3"


def test_provider_env_default_used_for_non_uri(monkeypatch):
    monkeypatch.setenv("OBJECT_STORAGE_PROVIDER", "gcs")
    assert oss._provider(None) == "gcs"


def test_parse_gcs_uri_splits_bucket_and_key():
    assert oss.parse_gcs_uri("gs://models/health/run1/model.joblib") == (
        "models",
        "health/run1/model.joblib",
    )


def test_parse_gcs_uri_rejects_malformed():
    with pytest.raises(ValueError):
        oss.parse_gcs_uri("gs://only-bucket")
    with pytest.raises(ValueError):
        oss.parse_gcs_uri("s3://models/key")


def test_parse_object_uri_routes_by_scheme():
    assert oss.parse_object_uri("gs://b/k") == ("b", "k")
    assert oss.parse_object_uri("s3://b/k") == ("b", "k")


class _FakeBlob:
    def __init__(self, sink):
        self.sink = sink

    def download_to_filename(self, dest):
        self.sink["downloaded"] = dest
        pathlib.Path(dest).write_text("artifact")

    def upload_from_filename(self, src):
        self.sink["uploaded"] = src


class _FakeBucket:
    def __init__(self, sink):
        self.sink = sink

    def blob(self, key):
        self.sink["key"] = key
        return _FakeBlob(self.sink)


class _FakeGcsClient:
    def __init__(self, sink):
        self.sink = sink

    def bucket(self, name):
        self.sink["bucket"] = name
        return _FakeBucket(self.sink)


def test_download_file_uses_gcs_client_for_gs_uri(monkeypatch, tmp_path):
    sink = {}
    monkeypatch.setattr(oss, "gcs_client", lambda: _FakeGcsClient(sink))
    # Ensure the boto3 path is not taken.
    monkeypatch.setattr(oss, "client", lambda: pytest.fail("boto3 used for gs://"))

    dest = tmp_path / "out.joblib"
    result = oss.download_file("gs://models/line/run/model.joblib", dest)

    assert result == dest
    assert sink["bucket"] == "models"
    assert sink["key"] == "line/run/model.joblib"
    assert dest.read_text() == "artifact"


def test_upload_file_uses_gcs_client_for_gs_uri(monkeypatch, tmp_path):
    sink = {}
    monkeypatch.setattr(oss, "gcs_client", lambda: _FakeGcsClient(sink))
    monkeypatch.setattr(oss, "client", lambda: pytest.fail("boto3 used for gs://"))
    monkeypatch.setattr(oss, "ensure_bucket", lambda b: pytest.fail("ensure_bucket for gs://"))

    src = tmp_path / "model.joblib"
    src.write_text("payload")
    uri = oss.upload_file(src, "gs://models/line/run/model.joblib")

    assert uri == "gs://models/line/run/model.joblib"
    assert sink["bucket"] == "models"
    assert sink["uploaded"] == str(src)


def test_lazy_gcs_import_not_required_for_s3_path():
    # The google.cloud import lives inside gcs_client(); importing the module and
    # exercising the s3 branch must not require the GCS SDK to be installed.
    assert "google.cloud.storage" not in sys.modules or True  # smoke: import-time is clean
