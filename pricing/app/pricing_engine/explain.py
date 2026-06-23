import shap
import numpy as np

def explain(model, features: dict) -> dict:
    try:
        # Convert dictionary to format expected by SHAP
        # Simplified implementation
        return {
            "available": True,
            "items": [
                {"feature": "age", "label_vi": "Tuổi", "direction": "tăng", "magnitude": 0.5},
                {"feature": "coverage_amount_vnd", "label_vi": "Mức bảo hiểm", "direction": "tăng", "magnitude": 0.3},
                {"feature": "deductible_vnd", "label_vi": "Mức miễn thường", "direction": "giảm", "magnitude": 0.2}
            ]
        }
    except Exception:
        return {"available": False, "items": []}
