"""Register Champion Model_Version rows + champion_assignment in pricing_db,
and keep champion_config.json in sync (task 6.2).

Uses DETERMINISTIC model_version ids (UUID5) so the DB rows and the
champion_config.json file reference the same identifier (risk: file/DB drift).
Prerequisite: the offline monotonic gate (task 6.3) MUST pass for a line
before it is registered as champion (BR-23 / C-8).

Run:  python offline/register_models.py
Requires: pricing_db (postgres-pricing) running + champion_config.json present.
"""
import json
import uuid
import pathlib
import datetime
import os

import psycopg2
import psycopg2.extras

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data" / "synthetic_real"
MODELS_DIR = ROOT / "reports" / "modeling" / "models"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]

# Deterministic namespace so model_version ids are stable across runs.
NAMESPACE = uuid.UUID("00000000-0000-0000-0000-cab000000001")


def get_db_connection():
    host = os.environ.get("PRICING_DB_HOST", "localhost")
    port = os.environ.get("PRICING_DB_PORT", "5440")
    user = os.environ.get("POSTGRES_USER", "platform_user")
    password = os.environ.get("POSTGRES_PASSWORD", "platform_password_dev_only")
    dbname = os.environ.get("PRICING_DB_NAME", "pricing_db")
    return psycopg2.connect(host=host, port=port, user=user,
                            password=password, dbname=dbname)


def model_version_for(line: str) -> str:
    return str(uuid.uuid5(NAMESPACE, "champion:" + line))


def main():
    config_path = MODELS_DIR / "champion_config.json"
    with open(config_path, encoding="utf-8") as f:
        config = json.load(f)
    champion_by_line = config["champion_by_line"]

    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            for line in LINES:
                cfg = champion_by_line[line]
                model_version_id = model_version_for(line)
                # Keep config + DB in sync: overwrite the file id with the
                # deterministic one (idempotent across runs).
                cfg["model_version"] = model_version_id

                # BR-19 travel exemption (task 20.8b): GLM champions on
                # monotonic-exempt lines do not carry artifact-level
                # monotone_constraints (monotonic_applied=false). The exemption
                # is recorded in champion_config.json via "monotonic_exempt" and
                # honoured by the promote gate in pricing_engine/governance.py.
                # We surface it here so registration is auditable; the model_version
                # row still records the actual monotonic_applied value.
                if cfg.get("monotonic_exempt"):
                    print(f"  NOTE: {line} is MONOTONIC-EXEMPT "
                          f"(algorithm={cfg.get('algorithm')}): "
                          f"{cfg.get('monotonic_exempt_reason', 'no reason given')}")

                cur.execute(
                    """
                    INSERT INTO model_version
                    (model_version_id, line, algorithm, gini, rmse, mae, deviance,
                     trained_at, dataset_desc, monotonic_applied)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (model_version_id) DO UPDATE SET
                      gini = EXCLUDED.gini,
                      monotonic_applied = EXCLUDED.monotonic_applied
                    """,
                    (model_version_id, line, "LightGBM" if cfg["algorithm"] == "lgb" else "GLM",
                     float(cfg["gini"]), 0.0, 0.0, 0.0,
                     datetime.datetime.now(datetime.timezone.utc),
                     cfg.get("dataset_desc", "synthetic_real"),
                     bool(cfg["monotonic_applied"])),
                )

                # Append-only champion assignment: retire previous current, insert new.
                cur.execute(
                    "UPDATE champion_assignment SET is_current = FALSE WHERE line = %s",
                    (line,),
                )
                cur.execute(
                    """
                    INSERT INTO champion_assignment
                    (assignment_id, line, model_version_id, is_current)
                    VALUES (%s, %s, %s, TRUE)
                    """,
                    (str(uuid.uuid4()), line, model_version_id),
                )
            conn.commit()
            print("Registered model_version + champion_assignment for all 6 lines.")
    finally:
        conn.close()

    with open(config_path, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2, ensure_ascii=False)
    print(f"Wrote synchronized champion_config.json to {config_path}")


if __name__ == "__main__":
    main()