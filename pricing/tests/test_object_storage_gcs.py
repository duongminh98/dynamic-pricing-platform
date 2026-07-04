"""Tests for the pricing runtime object-storage adapter's GCS support and the
reference-data bootstrap (GCP_DEPLOYMENT §2.6 / §6.2 / §6.3).

Uses mocked GCS/boto3 clients — no real credentials, MinIO, or network.
"""
import pathlib

import pytest

from app import object_storage as oss
from app import bootstrap_reference_data as boot


def test_is_object_uri_and_provider():
    assert oss.is_object_uri("gs://b/k") and oss.is_object_uri("s3://b/k")
    assert not oss.is_object_uri(None)
    assert oss._provider("gs://b/k") == "gcs"
    assert oss._provider("s3://b/k") == "s3"


def test_parse_gcs_uri():
    assert oss.parse_gcs_uri("gs://models/line/model.joblib") == ("models", "line/model.joblib")
    with pytest.raises(ValueError):
        oss.parse_gcs_uri("gs://bucket-only")


class _Blob:
    def __init__(self, name=None):
        self.name = name

    def download_to_filename(self, dest):
        p = pathlib.Path(dest)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("artifact")


class _Bucket:
    def blob(self, key):
        return _Blob(key)


class _GcsClient:
    def __init__(self, names):
        self._names = names

    def list_blobs(self, bucket, prefix=None):
        return [_Blob(n) for n in self._names]

    def bucket(self, name):
        return _Bucket()


def test_materialize_gs_uri_downloads(monkeypatch, tmp_path):
    monkeypatch.setattr(oss, "gcs_client", lambda: _GcsClient([]))
    monkeypatch.setattr(oss, "client", lambda: pytest.fail("boto3 used for gs://"))
    out = oss.download_file("gs://models/line/model.joblib", tmp_path / "m.joblib")
    assert out.read_text() == "artifact"


def test_download_prefix_preserves_layout_and_skips_dir_marker(monkeypatch, tmp_path):
    names = ["data/meta.json", "data/sub/geo.csv", "data/"]  # last is a dir marker
    monkeypatch.setattr(oss, "gcs_client", lambda: _GcsClient(names))
    monkeypatch.setattr(oss, "client", lambda: pytest.fail("boto3 used for gs://"))

    dest = tmp_path / "ref"
    written = oss.download_prefix("gs://bkt/data", dest)

    rel = sorted(p.relative_to(dest).as_posix() for p in written)
    assert rel == ["meta.json", "sub/geo.csv"]
    assert (dest / "meta.json").exists()
    assert (dest / "sub" / "geo.csv").exists()


def test_bootstrap_noop_without_env(monkeypatch):
    monkeypatch.delenv("REFERENCE_DATA_URI", raising=False)
    boot.bootstrap_reference_data()  # must not raise


def test_bootstrap_rejects_non_uri(monkeypatch):
    monkeypatch.setenv("REFERENCE_DATA_URI", "/local/path")
    with pytest.raises(ValueError):
        boot.bootstrap_reference_data()


def test_bootstrap_downloads_data_and_models(monkeypatch, tmp_path):
    calls = []

    def fake_download_prefix(base_uri, destination):
        calls.append((base_uri, str(destination)))
        return [destination / "f"]

    monkeypatch.setattr(boot, "download_prefix", fake_download_prefix)
    monkeypatch.setenv("REFERENCE_DATA_URI", "gs://ref/prod")
    monkeypatch.setenv("PRICING_REFERENCE_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("PRICING_MODELS_DIR", str(tmp_path / "models"))

    boot.bootstrap_reference_data()

    assert calls == [
        ("gs://ref/prod/data", str(tmp_path / "data")),
        ("gs://ref/prod/models", str(tmp_path / "models")),
    ]
