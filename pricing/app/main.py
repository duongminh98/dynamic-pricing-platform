import uuid
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .pricing_engine.loader import load_artifacts


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_artifacts()
    from .consumers.claim_settled_consumer import start_consumer, stop_consumer
    start_consumer()
    yield
    stop_consumer()


app = FastAPI(title="Pricing Service", lifespan=lifespan)

# Convention K: register common middleware (correlation-id, /metrics, structured
# 7.1 error handlers, /health) BEFORE including business routers (R19.x, R21.2).
from common import setup_common_middleware
setup_common_middleware(app)

from .routers import router as api_router
app.include_router(api_router)
