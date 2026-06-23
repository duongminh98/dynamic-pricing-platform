import uuid
import datetime
from sqlalchemy import create_engine, Column, String, Integer, Boolean, DateTime, Float, ForeignKey, JSON
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker

DATABASE_URL = "postgresql://platform_user:platform_password_dev_only@localhost:5440/pricing_db"
engine = create_engine(DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

Base = declarative_base()

class Quote(Base):
    __tablename__ = 'quote'
    
    quote_id = Column(String, primary_key=True)
    customer_id = Column(String, nullable=False)
    product_id = Column(String, nullable=False)
    line = Column(String, nullable=False)
    pure_premium_vnd = Column(Integer, nullable=False)
    final_premium_vnd = Column(Integer, nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False)

class AuditTrail(Base):
    __tablename__ = 'audit_trail'
    
    audit_id = Column(String, primary_key=True)
    quote_id = Column(String, nullable=True)
    feature_set = Column(JSON, nullable=True)
    model_version = Column(String, nullable=True)
    rate_version_id = Column(String, nullable=True)
    event_type = Column(String, nullable=False)
    change_detail = Column(JSON, nullable=True)
    actor = Column(String, nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False)

class ModelVersion(Base):
    __tablename__ = 'model_version'
    
    model_version_id = Column(String, primary_key=True)
    line = Column(String, nullable=False)
    algorithm = Column(String, nullable=False)
    gini = Column(Float, nullable=False)
    rmse = Column(Float, nullable=False)
    mae = Column(Float, nullable=False)
    deviance = Column(Float, nullable=False)
    trained_at = Column(DateTime(timezone=True), nullable=False)
    dataset_desc = Column(String, nullable=False)
    monotonic_applied = Column(Boolean, nullable=False)

class ChampionAssignment(Base):
    __tablename__ = 'champion_assignment'
    
    line = Column(String, primary_key=True)
    model_version_id = Column(String, nullable=False)
    is_current = Column(Boolean, nullable=False)
