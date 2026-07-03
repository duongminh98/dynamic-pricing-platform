import datetime
import os
import uuid
from sqlalchemy import create_engine, Column, String, Integer, BigInteger, Boolean, DateTime, Float, ForeignKey, JSON
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker

DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://platform_user:platform_password_dev_only@localhost:5440/pricing_db",
)
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
    trip_duration_days = Column(Integer, nullable=True)
    coverage_amount_vnd = Column(BigInteger, nullable=True)
    deductible_vnd = Column(BigInteger, nullable=True)
    geo_risk_version_id = Column(String, nullable=True)
    cost_index_version_id = Column(String, nullable=True)
    profile = Column(JSON, nullable=True)
    pure_premium_vnd = Column(Integer, nullable=False)
    final_premium_vnd = Column(Integer, nullable=False)
    explanation = Column(JSON, nullable=True)
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
    family = Column(String, nullable=True)
    status = Column(String, nullable=False, default="CANDIDATE")
    dataset_version_id = Column(String, nullable=True)
    artifact_uri = Column(String, nullable=True)
    artifact_checksum = Column(String, nullable=True)
    feature_schema_hash = Column(String, nullable=True)
    comparison_report_uri = Column(String, nullable=True)
    validation_report_uri = Column(String, nullable=True)
    fairness_report_uri = Column(String, nullable=True)
    registered_at = Column(DateTime(timezone=True), nullable=True)
    registered_by = Column(String, nullable=True)
    training_code_version = Column(String, nullable=True)
    quality_gates = Column(JSON, nullable=True)

class TrainingDatasetVersion(Base):
    __tablename__ = 'training_dataset_version'
    dataset_version_id = Column(String, primary_key=True)
    source_type = Column(String, nullable=False)
    artifact_uri = Column(String, nullable=False)
    manifest_uri = Column(String, nullable=False)
    data_hash = Column(String, nullable=False)
    window_start = Column(DateTime(timezone=True), nullable=True)
    window_end = Column(DateTime(timezone=True), nullable=True)
    export_started_at = Column(DateTime(timezone=True), nullable=False)
    export_completed_at = Column(DateTime(timezone=True), nullable=False)
    status = Column(String, nullable=False, default="EXPORTED")
    frequency_rows = Column(Integer, nullable=False, default=0)
    severity_rows = Column(Integer, nullable=False, default=0)
    exposure_rows = Column(Integer, nullable=False, default=0)
    settled_claim_rows = Column(Integer, nullable=False, default=0)
    quote_snapshot_rows = Column(Integer, nullable=False, default=0)
    created_by = Column(String, nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False)

class TrainingDatasetFile(Base):
    __tablename__ = 'training_dataset_file'
    file_id = Column(String, primary_key=True)
    dataset_version_id = Column(String, ForeignKey('training_dataset_version.dataset_version_id'), nullable=False)
    line = Column(String, nullable=True)
    kind = Column(String, nullable=False)
    artifact_uri = Column(String, nullable=False)
    row_count = Column(Integer, nullable=False, default=0)
    checksum_sha256 = Column(String, nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False)

class ChampionAssignment(Base):
    __tablename__ = 'champion_assignment'
    assignment_id = Column(String, primary_key=True)
    line = Column(String, nullable=False)
    model_version_id = Column(String, nullable=False)
    is_current = Column(Boolean, nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=True)

class EventOutbox(Base):
    __tablename__ = 'event_outbox'
    event_id = Column(String, primary_key=True)
    event_type = Column(String, nullable=False)
    routing_key = Column(String, nullable=False)
    payload = Column(JSON, nullable=False)
    status = Column(String, nullable=False, default='NEW')
    created_at = Column(DateTime(timezone=True), nullable=False)

class ModelDriftFlag(Base):
    __tablename__ = 'model_drift_flag'
    flag_id = Column(String, primary_key=True)
    line = Column(String, nullable=False)
    metric = Column(String, nullable=False)
    value = Column(Float, nullable=False)
    threshold = Column(Float, nullable=False)
    needs_recalibration = Column(Boolean, nullable=False, default=False)
    computed_at = Column(DateTime(timezone=True), nullable=False)

class ClaimOutcome(Base):
    __tablename__ = 'claim_outcome'
    claim_id = Column(String, primary_key=True)
    customer_id = Column(String, nullable=True)
    quote_id = Column(String, nullable=True)
    policy_id = Column(String, nullable=True)
    exposure_segment_seq = Column(Integer, nullable=True)
    line = Column(String, nullable=True)
    loss_type = Column(String, nullable=True)
    incurred_amount_vnd = Column(BigInteger, nullable=True)
    paid_amount_vnd = Column(BigInteger, nullable=True)
    actual_loss_vnd = Column(BigInteger, nullable=True)
    claim_status = Column(String, nullable=True)
    occurrence_date = Column(DateTime(timezone=True), nullable=True)
    reported_at = Column(DateTime(timezone=True), nullable=True)
    settled_at = Column(DateTime(timezone=True), nullable=True)
    recorded_at = Column(DateTime(timezone=True), nullable=False)

class QuoteFeatureSnapshot(Base):
    __tablename__ = 'quote_feature_snapshot'
    quote_id = Column(String, primary_key=True)
    customer_id = Column(String, nullable=False)
    product_id = Column(String, nullable=False)
    line = Column(String, nullable=False)
    input_profile = Column(JSON, nullable=False)
    enriched_profile = Column(JSON, nullable=False)
    feature_set = Column(JSON, nullable=False)
    model_version_id = Column(String, nullable=True)
    rate_version_id = Column(String, nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False)

class PolicyExposure(Base):
    __tablename__ = 'policy_exposure'
    exposure_id = Column(String, primary_key=True)
    policy_id = Column(String, nullable=False)
    quote_id = Column(String, nullable=True)
    customer_id = Column(String, nullable=False)
    product_id = Column(String, nullable=True)
    line = Column(String, nullable=False)
    exposure_segment_seq = Column(Integer, nullable=False)
    segment_start = Column(DateTime(timezone=True), nullable=False)
    segment_end = Column(DateTime(timezone=True), nullable=False)
    earned_exposure_years = Column(Float, nullable=False)
    coverage_amount_vnd = Column(BigInteger, nullable=True)
    deductible_vnd = Column(BigInteger, nullable=True)
    final_premium_vnd = Column(BigInteger, nullable=True)
    status = Column(String, nullable=True)
    risk_snapshot = Column(JSON, nullable=True)
    source_event_type = Column(String, nullable=True)
    recorded_at = Column(DateTime(timezone=True), nullable=False)

class CustomerRiskProfile(Base):
    __tablename__ = 'customer_risk_profile'
    customer_id = Column(String, primary_key=True)
    profile_version = Column(Integer, nullable=False)
    effective_at = Column(DateTime(timezone=True), nullable=False)
    common_risk_attributes = Column(JSON, nullable=False)
    line_risk_attributes = Column(JSON, nullable=False)
    last_event_id = Column(String, nullable=True)
    updated_at = Column(DateTime(timezone=True), nullable=False)

class QuoteReadyProfile(Base):
    __tablename__ = 'quote_ready_profile'
    customer_id = Column(String, primary_key=True)
    line = Column(String, primary_key=True)
    profile_version = Column(Integer, nullable=False)
    enriched_profile = Column(JSON, nullable=False)
    claim_features = Column(JSON, nullable=False)
    last_profile_event_id = Column(String, nullable=True)
    last_claim_event_id = Column(String, nullable=True)
    updated_at = Column(DateTime(timezone=True), nullable=False)

class ProductCatalogItem(Base):
    __tablename__ = 'product_catalog_item'
    product_id = Column(String, primary_key=True)
    category = Column(String, nullable=False)
    product_name = Column(String, nullable=True)
    coverage_amount_vnd = Column(BigInteger, nullable=False, default=0)
    deductible_vnd = Column(BigInteger, nullable=False, default=0)
    base_premium_vnd = Column(BigInteger, nullable=False, default=0)
    admin_fee_vnd = Column(BigInteger, nullable=False, default=0)
    active = Column(Boolean, nullable=False, default=True)
    last_event_id = Column(String, nullable=True)
    updated_at = Column(DateTime(timezone=True), nullable=False)

class ProductLoadingFactor(Base):
    __tablename__ = 'product_loading_factor'
    line = Column(String, primary_key=True)
    rate_version_id = Column(String, nullable=True)
    loading_value = Column(Float, nullable=False, default=1.0)
    last_event_id = Column(String, nullable=True)
    updated_at = Column(DateTime(timezone=True), nullable=False)

class GeoRiskReferenceRow(Base):
    __tablename__ = 'geo_risk_reference_row'
    version_id = Column(String, primary_key=True)
    province = Column(String, primary_key=True)
    region = Column(String, nullable=True)
    urban_tier_geo = Column(String, nullable=True)
    traffic_density_score = Column(Float, nullable=False, default=0.0)
    vehicle_theft_risk_score = Column(Float, nullable=False, default=0.0)
    accident_frequency_index = Column(Float, nullable=False, default=0.0)
    flood_risk_score = Column(Float, nullable=False, default=0.0)
    storm_risk_score = Column(Float, nullable=False, default=0.0)
    fire_risk_score = Column(Float, nullable=False, default=0.0)
    crime_risk_score = Column(Float, nullable=False, default=0.0)
    healthcare_access_score = Column(Float, nullable=False, default=0.0)
    hospital_cost_index = Column(Float, nullable=False, default=0.0)
    repair_cost_index = Column(Float, nullable=False, default=0.0)
    construction_cost_index = Column(Float, nullable=False, default=0.0)
    status = Column(String, nullable=False, default='ACTIVE')
    checksum = Column(String, nullable=True)
    last_event_id = Column(String, nullable=True)
    updated_at = Column(DateTime(timezone=True), nullable=False)

class CostIndexReferenceRow(Base):
    __tablename__ = 'cost_index_reference_row'
    version_id = Column(String, primary_key=True)
    month_start = Column(String, primary_key=True)
    year = Column(Integer, nullable=False)
    month = Column(Integer, nullable=False)
    medical_inflation_index = Column(Float, nullable=False, default=1.0)
    vehicle_repair_inflation_index = Column(Float, nullable=False, default=1.0)
    construction_inflation_index = Column(Float, nullable=False, default=1.0)
    travel_medical_cost_index = Column(Float, nullable=False, default=1.0)
    general_expense_index = Column(Float, nullable=False, default=1.0)
    status = Column(String, nullable=False, default='ACTIVE')
    checksum = Column(String, nullable=True)
    last_event_id = Column(String, nullable=True)
    updated_at = Column(DateTime(timezone=True), nullable=False)
