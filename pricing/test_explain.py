import sys
import os
sys.path.append('d:/Dynamic Pricing Platform/pricing/app')
from pricing_engine.loader import load_models
load_models()
from pricing_engine.engine import quote
from database import get_db
import json

profile = json.loads('''{
    "age": 30,
    "gender": "male",
    "province": "Ha Noi",
    "region": "Red River Delta",
    "urban_tier": "Tier 1",
    "occupation": "Engineer",
    "income_level": "High",
    "marital_status": "Single",
    "line_attributes": {
      "smoker": false,
      "height_cm": 170,
      "weight_kg": 65,
      "bmi": 22.5,
      "diabetes": false,
      "blood_pressure_problem": false,
      "chronic_disease": false,
      "major_surgeries_count": 0,
      "medical_visit_count_12m": 0,
      "hospitalized_last_12m": false,
      "sport_activity_flag": false,
      "sport_risk_level": "low",
      "workplace_risk_level": "low",
      "hazardous_activity_exclusion_flag": false,
      "coverage_amount_vnd": 500000000,
      "deductible_vnd": 1000000
    }
}''')

try:
    res = quote(None, 'HEALTH_BASIC', profile)
    print(res['explanation'])
except Exception as e:
    import traceback
    traceback.print_exc()
