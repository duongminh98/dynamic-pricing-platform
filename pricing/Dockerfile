# Pricing_Service (FastAPI + scikit-learn/LightGBM). Runs alembic migrations at
# startup then serves uvicorn on :8000. Model artifacts under reports/ and data/
# lookups are mounted at runtime (see docker-compose volumes) since they are large
# and gitignored.
FROM python:3.11-slim AS runtime
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        libgomp1 curl \
    && rm -rf /var/lib/apt/lists/*

COPY pricing/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY pricing/ /app/

ENV PYTHONPATH=/app
EXPOSE 8000

# entrypoint: apply DB migrations then launch the API.
COPY pricing/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
