import json
import logging
import uuid
import datetime
from fastapi import FastAPI, Depends, Request
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session
from .database import get_db

app = FastAPI(title="Pricing Service")

# Simulate setup_common_middleware
@app.middleware("http")
async def add_correlation_id(request: Request, call_next):
    # Dummy middleware for now, just sets request.state.correlation_id
    request.state.correlation_id = str(uuid.uuid4())
    response = await call_next(request)
    response.headers["X-Correlation-Id"] = request.state.correlation_id
    return response

@app.get("/actuator/health")
def health():
    return {"status": "UP"}
