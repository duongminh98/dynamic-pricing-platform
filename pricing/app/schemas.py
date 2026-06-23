import json
from pydantic import BaseModel
from typing import Optional, Dict, Any, List
from datetime import datetime

class Product(BaseModel):
    product_id: str
    category: str
    product_name: str
    coverage_amount_vnd: int
    deductible_vnd: int
    base_premium_vnd: int
    admin_fee_vnd: int
    active: bool

class Profile(BaseModel):
    age: int
    gender: str
    province: str
    region: str
    urban_tier: str
    occupation: str
    income_level: str
    monthly_income_vnd: int
    marital_status: str
    line: str
    line_attributes: Dict[str, Any]
