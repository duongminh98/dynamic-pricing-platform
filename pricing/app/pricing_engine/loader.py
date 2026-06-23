import json
import pathlib
import joblib
import warnings

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
MODELS_DIR = ROOT / "reports" / "modeling" / "models"
DATA_DIR = ROOT / "data" / "synthetic_real"
METADATA_PATH = DATA_DIR / "pricing_modeling_metadata.json"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]

artifacts = {}
champion_config = {}
metadata = {}

def load_artifacts():
    global artifacts, champion_config, metadata
    
    config_path = MODELS_DIR / "champion_config.json"
    if not config_path.exists():
        raise RuntimeError("champion_config.json not found")
        
    with open(config_path) as f:
        champion_config = json.load(f)
        
    if not METADATA_PATH.exists():
        raise RuntimeError("pricing_modeling_metadata.json not found")
        
    with open(METADATA_PATH) as f:
        metadata = json.load(f)
        
    for line in LINES:
        artifacts[line] = {}
        for family in ["freq", "sev", "tw"]:
            # travel uses glm, others lgb. Wait, task 7.2 says "reports/modeling/models/{line}__{glm|lgb}_{freq|sev|tw}.joblib"
            # But task 6.1 says "re-fit Champion monotonic (LightGBM)", meaning we produced {line}__lgb_{family}.joblib
            # Let's try lgb first, fallback to glm
            model_path_lgb = MODELS_DIR / f"{line}__lgb_{family}.joblib"
            model_path_glm = MODELS_DIR / f"{line}__glm_{family}.joblib"
            
            if model_path_lgb.exists():
                artifacts[line][family] = joblib.load(model_path_lgb)
            elif model_path_glm.exists():
                artifacts[line][family] = joblib.load(model_path_glm)
            else:
                warnings.warn(f"Model artifact not found for {line} {family}")
                
def get_line_for_product(product_id: str) -> str:
    # Simulated for now, as DB lookup or products.csv is needed
    if "HEALTH" in product_id: return "health"
    if "MOTORBIKE" in product_id: return "motorbike"
    if "CAR" in product_id: return "car"
    if "TRAVEL" in product_id: return "travel"
    if "ACCIDENT" in product_id: return "accident"
    if "HOME" in product_id: return "home"
    return "health"

def get_features(line: str) -> list[str]:
    # Extract features matching the model
    if line in artifacts and "freq" in artifacts[line]:
        model = artifacts[line]["freq"]
        if hasattr(model, "feature_name_"):
            return model.feature_name_
    return []
