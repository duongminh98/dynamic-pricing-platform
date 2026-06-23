import json
import logging
import uuid
import datetime
from contextlib import asynccontextmanager
from fastapi import FastAPI, Depends, Request
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session
from .database import get_db
from .pricing_engine.loader import load_artifacts

@asynccontextmanager
async def lifespan(app: FastAPI):
    load_artifacts()
    yield

app = FastAPI(title="Pricing Service", lifespan=lifespan)

from .routers import router as api_router
app.include_router(api_router)

# Simulate setup_common_middleware
@app.middleware("http")
async def add_correlation_id(request: Request, call_next):
    request.state.correlation_id = str(uuid.uuid4())
    response = await call_next(request)
    response.headers["X-Correlation-Id"] = request.state.correlation_id
    return response

@app.get("/actuator/health")
def health():
    return {"status": "UP"}
