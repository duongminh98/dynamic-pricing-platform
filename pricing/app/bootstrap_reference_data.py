"""Startup bootstrap: hydrate the pricing reference-data tree from object storage.

Cloud Run / GKE have no host volumes, so the reference files the loader hard-
requires (``pricing_modeling_metadata.json``, ``champion_config.json``, the
geo/cost/products CSVs) must be present in the container before uvicorn starts
when a GCS-FUSE volume mount is not used (GCP_DEPLOYMENT §2.6 / §6.3).

When ``REFERENCE_DATA_URI`` is set, this pulls:
    <REFERENCE_DATA_URI>/data/   -> PRICING_REFERENCE_DIR
    <REFERENCE_DATA_URI>/models/ -> PRICING_MODELS_DIR
using the same s3/gcs adapter the runtime uses (no gcloud/aws CLI needed). When
unset (e.g. docker-compose, which mounts the volumes), it is a no-op.
"""
from __future__ import annotations

import os
import pathlib

from .object_storage import download_prefix, is_object_uri


def bootstrap_reference_data() -> None:
    base = os.environ.get("REFERENCE_DATA_URI")
    if not base:
        return
    if not is_object_uri(base):
        raise ValueError(f"REFERENCE_DATA_URI must be an s3:// or gs:// URI, got: {base}")

    reference_dir = pathlib.Path(
        os.environ.get("PRICING_REFERENCE_DIR", "/app/data/synthetic_real_1m_history_lift_v2")
    )
    models_dir = pathlib.Path(
        os.environ.get("PRICING_MODELS_DIR", "/app/reports/modeling/models")
    )
    base = base.rstrip("/")

    data_files = download_prefix(f"{base}/data", reference_dir)
    model_files = download_prefix(f"{base}/models", models_dir)
    print(
        f"Reference bootstrap: {len(data_files)} file(s) -> {reference_dir}, "
        f"{len(model_files)} file(s) -> {models_dir}",
        flush=True,
    )


if __name__ == "__main__":
    bootstrap_reference_data()
