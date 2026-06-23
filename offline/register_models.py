import json
import uuid
import pathlib
import datetime
import os
import psycopg2
import psycopg2.extras

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / 'data' / 'synthetic_real'
MODELS_DIR = ROOT / 'reports' / 'modeling' / 'models'
REPORTS_DIR = ROOT / 'reports' / 'modeling_real'

LINES = ['health', 'motorbike', 'car', 'home', 'accident', 'travel']

def get_db_connection():
    # Use environment variables matching docker-compose output
    host = os.environ.get('PRICING_DB_HOST', 'localhost')
    port = os.environ.get('PRICING_DB_PORT', '5440')
    user = os.environ.get('POSTGRES_USER', 'platform_user')
    password = os.environ.get('POSTGRES_PASSWORD', 'platform_password_dev_only')
    dbname = os.environ.get('PRICING_DB_NAME', 'pricing_db')
    
    return psycopg2.connect(
        host=host,
        port=port,
        user=user,
        password=password,
        dbname=dbname
    )

def main():
    # 1. Gather stats from reports
    champion_by_line = {}
    
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            for line in LINES:
                report_path = REPORTS_DIR / f'{line}_validation.json'
                gini = 0.0
                rmse = 0.0
                mae = 0.0
                deviance = 0.0
                
                if report_path.exists():
                    with open(report_path) as f:
                        report = json.load(f)
                        gini = report.get('gini', 0.0)
                        rmse = report.get('rmse', 0.0)
                        mae = report.get('mae', 0.0)
                        deviance = report.get('deviance', 0.0)
                
                model_version_id = str(uuid.uuid4())
                
                # Insert into model_version
                cur.execute(
                    '''
                    INSERT INTO model_version 
                    (model_version_id, line, algorithm, gini, rmse, mae, deviance, trained_at, dataset_desc, monotonic_applied)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ''',
                    (model_version_id, line, 'LightGBM', gini, rmse, mae, deviance, datetime.datetime.now(datetime.timezone.utc), 'synthetic_real', True)
                )
                
                # Unset previous champions
                cur.execute(
                    'UPDATE champion_assignment SET is_current = FALSE WHERE line = %s',
                    (line,)
                )
                
                # Insert into champion_assignment
                cur.execute(
                    '''
                    INSERT INTO champion_assignment 
                    (line, model_version_id, is_current)
                    VALUES (%s, %s, TRUE)
                    ''',
                    (line, model_version_id)
                )
                
                champion_by_line[line] = {
                    'family': 'tw' if line != 'travel' else 'glm',
                    'gini': gini,
                    'model_version': model_version_id
                }
            
            conn.commit()
            print('Successfully registered models in DB.')
            
    finally:
        conn.close()
        
    # Write champion_config.json
    config_path = MODELS_DIR / 'champion_config.json'
    with open(config_path, 'w') as f:
        json.dump({'champion_by_line': champion_by_line}, f, indent=2)
    print(f'Wrote champion config to {config_path}')

if __name__ == '__main__':
    main()
