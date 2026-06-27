import sys
import os
sys.path.append('d:/Dynamic Pricing Platform/pricing/app')
from pricing_engine.loader import ensure_loaded
ensure_loaded()
from pricing_engine.engine import quote
from pricing_engine.features import build_features
from pricing_engine.selection import select_model
from pricing_engine.explain import explain
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
    from pricing_engine.loader import required_columns
    line = "health"
    feature_names = required_columns(line)
    feature_df = build_features(line, "HEALTH_BASIC", profile, feature_names)
    selection = select_model(line)
    model = selection['model']
    
    # Run explain without catch
    from pricing_engine.explain import _extract_model_and_features, _get_tree_explainer
    est, _ = _extract_model_and_features(model)
    print("Estimator:", type(est))
    explainer = _get_tree_explainer(est)
    values = explainer.shap_values(feature_df)
    print("SHAP successful!")
except Exception as e:
    import traceback
    traceback.print_exc()
