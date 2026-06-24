#!/usr/bin/env bash
set -euo pipefail

# Apply Alembic migrations to pricing_db (idempotent), then serve.
echo "Running alembic upgrade head..."
alembic upgrade head || { echo "alembic upgrade failed"; exit 1; }

echo "Starting uvicorn on :8000..."
exec uvicorn app.main:app --host 0.0.0.0 --port 8000
