# Dynamic Pricing Platform

A microservices platform for dynamic insurance pricing featuring an AI pricing engine (Python/FastAPI with LightGBM + SHAP explainability) and six Java/Spring Boot services, orchestrated through a Kong API gateway with Keycloak JWT authentication and RabbitMQ async messaging.

## Architecture

```
                    â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
                    â”‚ Keycloak â”‚ (JWT RS256, 2 roles: Customer, Administrator)
                    â””â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”˜
                         â”‚
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”      â”Œâ”€â”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”€â”€â”      â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚  Client â”‚â”€â”€â”€â”€â”€â–¶â”‚  Kong Gateway  â”‚â”€â”€â”€â”€â”€â–¶â”‚  Microservices   â”‚
â”‚ (React) â”‚      â”‚  (port 8000)   â”‚      â”‚  (6 Java + 1 Py) â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜      â””â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”˜      â””â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                         â”‚                        â”‚
                  â”Œâ”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”          â”Œâ”€â”€â”€â”€â”€â”€â–¼â”€â”€â”€â”€â”€â”€â”
                  â”‚ Prometheus  â”‚          â”‚  RabbitMQ   â”‚ (outbox pattern)
                  â”‚   Grafana   â”‚          â”‚  exchange:  â”‚
                  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜          â”‚platform.eventsâ”‚
                                           â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

- **Sync flow:** Client â†’ Kong Gateway (JWT verification) â†’ Java/FastAPI services
- **Async flow:** Services publish events via transactional outbox â†’ RabbitMQ exchange `platform.events`
- **Pricing:** FastAPI service loads 36 model artifacts (6 lines Ã— 3 families Ã— 2 algorithms), uses champion config for model selection, returns premiums with SHAP explanations

## Quick Start

### Prerequisites

- Docker Desktop (with WSL2 backend)
- Java 17+ (Gradle toolchain auto-resolves)
- Python 3.11+ with pip

### Run everything with one command

```bash
docker compose up --build -d
```

This builds and starts the full stack in containers: 7 PostgreSQL databases,
RabbitMQ, Keycloak, Kong, Prometheus, Grafana, all six Spring Boot services
(two replicas each), the Pricing service (two replicas, FastAPI), and the
frontend Mini_App (nginx). Java services run Flyway migrations on startup; the
Pricing container runs `alembic upgrade head` before serving (R16.1).

Verify the stack is healthy:

```bash
docker compose ps
```

- API gateway (Kong): http://localhost:8000
- Frontend (Mini_App): http://localhost:3001
- Keycloak: http://localhost:8080

> The legacy `checkpoint` profile (nginx health-stub) is still available for the
> infrastructure-only checkpoint: `docker compose --profile checkpoint up -d`.

#### Local development (without containers)

For iterating on a single service you can still run it from a built jar
(`./gradlew :services:<svc>:bootJar` then `java -jar ...`) or, for Pricing,
`uvicorn app.main:app --port 9001` after `alembic upgrade head`. Point
`PRICING_BASE_URL` / DB host env vars at your local infra in that case.

## Services and Ports

| Service | Technology | Public Host Port | Private Network Port | Database | DB Port |
|---------|-----------|------------------|----------------------|----------|---------|
| Kong Gateway | Kong 3.8 | 8000 (proxy), 8001 (admin) | 8000 | â€” | â€” |
| Keycloak | Keycloak 26 | 8080 | 8080 | â€” | â€” |
| Customer Service | Spring Boot | â€” | 8080 | customer_db | 5433 |
| Product Service | Spring Boot | â€” | 8080 | product_db | 5434 |
| Order Service | Spring Boot | â€” | 8080 | order_db | 5435 |
| Claims Service | Spring Boot | â€” | 8080 | claims_db | 5437 |
| Billing Service | Spring Boot | â€” | 8080 | billing_db | 5438 |
| Notification Service | Spring Boot | â€” | 8080 | notification_db | 5439 |
| Pricing Service | FastAPI | â€” | 8000 | pricing_db | 5440 |
| RabbitMQ | 3.13 | 5672 (AMQP), 15672 (mgmt) | 5672 | â€” | â€” |
| Prometheus | v2.55 | 9090 | 9090 | â€” | â€” |
| Grafana | 11.3 | 3000 | 3000 | â€” | â€” |

Application services are intentionally not published to the host in Docker Compose. Public API traffic enters through Kong, which validates JWTs and forwards trusted identity headers to private upstream services.

## Demo Accounts

| Role | Username | Password | Keycloak Subject |
|------|----------|----------|------------------|
| Customer | demo.customer | demo_customer_dev_only | 01e0e4c0-3671-4e84-a97b-7fdfd51fa901 |
| Administrator | demo.admin | demo_admin_dev_only | 48cd30e3-a9ba-40e0-b4f1-93d1f408cfe3 |

Acquire a JWT via Keycloak password grant:

```bash
curl -X POST http://localhost:8080/realms/dynamic-pricing/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=mini-app" \
  -d "username=demo.customer" \
  -d "password=demo_customer_dev_only" \
  -d "scope=openid"
```

## API Endpoints

### Swagger UI and OpenAPI

Backend services keep Swagger/OpenAPI endpoints on their private service ports. In the Docker stack those ports are not published to the host; expose docs through Kong or a dev-only override when API inspection is needed.

Spring Boot services use Springdoc (`/swagger-ui.html`, `/v3/api-docs`). The Pricing service uses FastAPI's built-in Swagger UI (`/docs`) and OpenAPI document (`/openapi.json`). Production environments should keep service docs private, admin-protected through Kong, or disabled.

### Customer Service (8081)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/login` | Public | Frontend redirects to Keycloak hosted login |
| GET | `/register` | Public | Frontend redirects to Keycloak hosted registration |
| GET | `/customers/me` | Customer | Get current customer info |
| PUT | `/customers/me/profile` | Customer | Upsert insurance profile (line-specific validation) |
| GET | `/customers/me/profile` | Customer | Get latest profile |

### Product Service (8082)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/products` | Public | List all products |
| GET | `/products/{id}` | Public | Get product detail with coverage options |
| POST | `/admin/products` | Administrator | Create product |
| PUT | `/admin/products` | Administrator | Update product |
| PUT | `/admin/loading-factors` | Administrator | Update loading factor |
| GET | `/admin/rate-versions` | Administrator | List rate versions (append-only) |

### Pricing Service (9001)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/health` | Public | Health check |
| POST | `/pricing/quote` | Customer | Generate quote (real premium + SHAP explanation) |
| GET | `/pricing/quote/{id}` | Customer | Retrieve stored quote |
| GET | `/pricing/models` | Administrator | List registered model versions with dataset/artifact lineage and champion flag |
| POST | `/admin/champion/promote` | Administrator | Promote a model to champion |
| POST | `/admin/models/reject` | Administrator | Explicitly reject a candidate model |
| POST | `/admin/champion/rollback` | Administrator | Rollback champion to previous |

### Order Service (8083)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/orders` | Customer | Create order from quote (status: PENDING_REVIEW) |
| GET | `/admin/orders/review-queue` | Administrator | List pending orders |
| GET | `/admin/orders/{id}` | Administrator | Get order detail |
| POST | `/admin/orders/{id}/approve` | Administrator | Approve order (creates invoice) |
| POST | `/admin/orders/{id}/reject` | Administrator | Reject order |

### Billing Service (8086)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/billing/invoices` | Public | Create invoice |
| POST | `/billing/invoices/{id}/pay` | Authenticated | Pay invoice (publishes InvoicePaid event) |

### Claims Service (8085)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/claims/fnol` | Customer | First notification of loss |
| GET | `/claims` | Customer | List own claims |
| GET | `/claims/{id}` | Customer | Get claim detail (ownership enforced) |
| POST | `/claims/{id}/approve` | Administrator | Approve claim payout |
| POST | `/claims/{id}/reject` | Administrator | Reject claim |
| POST | `/claims/{id}/misrepresentation` | Administrator | Flag misrepresentation |

### Notification Service (8087)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/notifications` | Customer | List own notifications |

## End-to-End Flow

The complete customer journey:

1. **Register/Login** -> Keycloak hosted UI -> JWT (Customer or Administrator role)
2. **Profile** â†’ `PUT /customers/me/profile` (line-specific validation: health, motorbike, car, home, accident, travel)
3. **Quote** â†’ `POST /pricing/quote` (AI engine: champion model selection, feature derivation, premium calculation, SHAP explanation)
4. **Order** â†’ `POST /orders` (status: `PENDING_REVIEW`)
5. **Admin Approve** â†’ `POST /admin/orders/{id}/approve` (triggers invoice creation via BillingClient, status: `PENDING_PAYMENT`)
6. **Pay Invoice** â†’ `POST /billing/invoices/{id}/pay` (publishes `InvoicePaid` event via outbox â†’ RabbitMQ)
7. **Policy Issued** â†’ Order-service consumes `InvoicePaid`, creates policy (status: `active`), publishes `PolicyIssued` event
8. **Claims** â†’ `POST /claims/fnol` (requires active policy, ownership enforced)

## Build and Test

### Java Services

```bash
./gradlew build test
```

Runs jqwik property tests (â‰¥100 iterations) with Mockito for all six services. The `product-service` integration tests (`@SpringBootTest`) require `postgres-product` on port 5434.

### Pricing Service

```bash
cd pricing
python -m pytest tests/ -q          # 45 pricing engine tests
python -m pytest common/tests/ -q    # 9 common module tests
```

### CI/CD

The GitHub Actions workflow (`.github/workflows/ci.yml`) runs on push/PR to `master`:

- **Java job:** Sets up `postgres-product` service container, runs `./gradlew build test`
- **Python job:** Installs `pricing/requirements.txt`, runs pytest for `tests/` and `common/tests/`

## Key Design Decisions

- **All monetary amounts** are VND integers (no floating point)
- **Two roles only:** Customer and Administrator (RBAC via Keycloak realm roles)
- **Transactional outbox pattern:** Each service owns an `outbox` table; `OutboxRelay` polls and publishes to RabbitMQ exchange `platform.events` with event type as routing key
- **Rate versions** are append-only (Flyway-managed, never mutated)
- **Champion model selection:** One serving model per line, configured via `champion_config.json` (deterministic UUID5 synced to DB)
- **SHAP explainability:** Per-quote feature contributions cached per model instance for performance
- **Profile versions:** All profile changes tracked via `ProfileVersion` entity for audit trail

## Project Structure

```
.
â”œâ”€â”€ services/                 # Java/Spring Boot microservices
â”‚   â”œâ”€â”€ common/               # Shared module (outbox, security, error handling)
â”‚   â”œâ”€â”€ customer-service/     # Auth, profile management, validation
â”‚   â”œâ”€â”€ product-service/      # Product catalog, admin config, rate versions
â”‚   â”œâ”€â”€ order-service/        # Order lifecycle, policy issuance, endorsements
â”‚   â”œâ”€â”€ billing-service/      # Invoice creation, payment, pro-rata adjustments
â”‚   â”œâ”€â”€ claims-service/       # FNOL, approve/reject, misrepresentation
â”‚   â””â”€â”€ notification-service/ # Event-driven notifications
â”œâ”€â”€ pricing/                  # Python/FastAPI pricing engine
â”‚   â”œâ”€â”€ app/                  # Routers, engine, feature store, governance
â”‚   â”œâ”€â”€ common/               # Shared Python module (errors, health, metrics)
â”‚   â””â”€â”€ migrations/           # Alembic DB migrations
â”œâ”€â”€ infra/                    # Infrastructure configs
â”‚   â”œâ”€â”€ kong/                 # Kong declarative config (routes, JWT, upstreams)
â”‚   â”œâ”€â”€ keycloak/             # Realm export (roles, users, clients)
â”‚   â”œâ”€â”€ prometheus/           # Prometheus scrape config
â”‚   â”œâ”€â”€ grafana/              # Dashboards and provisioning
â”‚   â””â”€â”€ rabbitmq/             # Exchange/queue definitions
â”œâ”€â”€ reports/modeling/models/  # 36 trained model artifacts + champion_config.json
â”œâ”€â”€ data/synthetic_real_1m_history_lift_v2/      # Training datasets, geo risk, cost indices
â”œâ”€â”€ frontend/                 # React + Vite + TypeScript mini app
â”œâ”€â”€ scripts/                  # Model training and EDA scripts
â”œâ”€â”€ docker-compose.yml        # Infrastructure (13 containers)
â””â”€â”€ .github/workflows/ci.yml  # CI/CD pipeline
```


## VNPAY Payment Integration (Sandbox)

The Billing_Service integrates VNPAY (sandbox) for real payment processing via
the redirect + IPN flow (R33.2/R33.3). The existing `InvoicePaid` event contract
is unchanged ? VNPAY only replaces the "confirm payment" step.

### Setup

1. Register at https://sandbox.vnpayment.vn/devreg/ to get `TmnCode` + `HashSecret`
2. Set in `.env`:
   ```
   VNP_TMN_CODE=your_tmn_code
   VNP_HASH_SECRET=your_hash_secret
   VNP_RETURN_URL=http://localhost:3001/payment-result
   ```
3. The IPN endpoint (`/billing/vnpay/ipn`) must be publicly reachable for VNPAY
   to call back. For local testing, use ngrok or cloudflared to expose port 8000.

### Test Card (NCB)

- Card number: `9704198526191432198`
- Name: `NGUYEN VAN A`
- Expiry: `07/15`
- OTP: `123456`

### Flow

1. Customer creates a payment URL: `POST /billing/invoices/{id}/payment-url`
2. Frontend redirects to VNPAY payment page
3. Customer pays with test card
4. VNPAY calls IPN: `GET /billing/vnpay/ipn` (source of truth ? confirms payment)
5. VNPAY redirects browser: `GET /billing/vnpay/return` (display only)
6. Frontend polls: `GET /billing/vnpay/status?vnp_txn_ref=...` until confirmed
7. On success, `InvoicePaid` event enqueued ? Order_Service issues policy

### API Endpoints

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/billing/invoices/{id}/payment-url` | POST | Customer | Create VNPAY payment URL |
| `/billing/vnpay/return` | GET | Public | Browser redirect (display only) |
| `/billing/vnpay/ipn` | GET | Public | Server-to-server (source of truth) |
| `/billing/vnpay/status` | GET | Customer | Query payment status |

### Idempotency

- `vnp_txn_ref` is UNIQUE per payment attempt
- Repeated IPN for the same `vnp_txn_ref` returns `RspCode=02` (already confirmed)
- Only `RspCode=00` + valid signature + amount match ? invoice paid + 1 `InvoicePaid`



## Model Lifecycle (R37, No A/B)

The platform supports offline model lifecycle management that runs entirely outside the serving path (R37.10). Runtime pricing remains champion-only: no A/B tables, no shadow scoring, no canary rollout, no sticky assignment, and no candidate-served customer quotes.

### Dataset + Candidate Flow

1. Export and register an immutable dataset version with manifest, checksums, row counts, and registry rows:

```bash
python offline/build_training_dataset_from_pricing_db.py \
  --database-url postgresql://platform_user:platform_password_dev_only@localhost:5440/pricing_db \
  --output-dir data/pricing_read_model_export \
  --dataset-version-id ds-2026-q3 \
  --register-registry \
  --created-by offline-operator
```

2. Train a candidate, compare it with the current champion on the same holdout, then register it only after comparison, validation, monotonic, and smoothness gates pass:

```bash
python offline/compare_candidate_to_champion.py --line car --dataset-dir data/pricing_read_model_export --candidate-artifact-dir reports/modeling/models --champion-model-version-id <current-champion-id> --output-file reports/modeling/comparison/car_comparison.json
python offline/register_candidate_model.py --line car --dataset-version-id ds-2026-q3 --artifact-uri reports/modeling/models/car__lgb_tw.joblib --comparison-report-uri reports/modeling/comparison/car_comparison.json --validation-report-uri reports/modeling/validation/car_validation.json --registered-by offline-operator --monotonic-passed --smoothness-passed
```

`GET /pricing/models` returns DTOs with `status`, `dataset_version_id`, `artifact_checksum`, `quality_gates`, and `is_champion`. Administrators promote, reject, or roll back with `/admin/champion/promote`, `/admin/models/reject`, and `/admin/champion/rollback`.

### Retrain Trigger (`offline/retrain_trigger.py`)

Configured in `offline/retrain_config.json`:

1. **Schedule:** Quarterly by default (months 1, 4, 7, 10)
2. **Data threshold:** When new claims/exposure count for a line exceeds a configured threshold
3. **Drift:** When `model_drift_flag.needs_recalibration=true` for a line

The trigger creates a **candidate** `Model_Version`. It does not auto-promote; promotion follows BR-23 governance (`POST /admin/champion/promote`).

```bash
python offline/retrain_trigger.py              # check + trigger
python offline/retrain_trigger.py --dry-run     # show what would trigger
python offline/retrain_trigger.py --line health # force one line
```

### Drift Monitor (`offline/drift_monitor.py`)

Per-line comparison of feature PSI, prediction PSI, and calibration drift. Drift remains diagnostics/retraining input, not the main promotion decision panel.

```bash
python offline/drift_monitor.py              # compute + persist flags
python offline/drift_monitor.py --dry-run     # compute without persisting
```

Administrator endpoint:

```bash
GET /pricing/drift
```

### Scheduling

Run offline scripts from cron or GitHub Actions; they do not run in the serving path.

```cron
0 2 1 1,4,7,10 * python /app/offline/retrain_trigger.py
0 3 * * 1 python /app/offline/drift_monitor.py
```
## CI/CD Pipeline

The GitHub Actions workflow (`.github/workflows/ci.yml`) runs on every push and
pull request to `master`:

- **java job:** `./gradlew clean test` + JaCoCo coverage report + 70% coverage gate
- **python job:** `pytest --cov --cov-fail-under=70` for pricing service
- **docker-build job:** Builds all 8 service images + frontend (no push)

All jobs have a 30-minute timeout (R21.1). Gradle and pip caches are enabled.

### Branch Protection (R21.8)

To enforce CI gates on merges to `master`, enable branch protection in GitHub:

1. Go to **Settings ? Branches ? Add branch protection rule** for `master`
2. Check **Require status checks to pass before merging**
3. Select required checks: `java`, `python`, `docker-build`
4. Check **Require branches to be up to date before merging**
5. Check **Do not allow bypassing the above settings**

This prevents merging when CI fails (R21.8). The `ci.yml` workflow name matches
the status check names above.

## Kong Gateway Verification

All API flows are verified through the Kong gateway (port 8000) with centralized JWT enforcement:

- **No JWT** â†’ `401 Unauthorized` on protected routes.
- **Invalid, expired, or wrong-issuer JWT** â†’ rejected by Kong before reaching services.
- **Customer JWT on admin endpoints** â†’ `403 Forbidden` from service role checks using trusted Kong headers.
- **Valid JWT** â†’ Kong strips spoofed `X-Authenticated-*` headers, injects identity from JWT claims, and forwards to the private upstream service.

The Kong declarative config (`infra/kong/kong.yml`) routes all public service paths through the gateway:

| Path Prefix | Upstream Service |
|-------------|-----------------|
| `/customers` | customer-service |
| `/products`, `/admin/products`, `/admin/rate-versions` | product-service |
| `/orders`, `/admin/orders` | order-service |
| `/claims` | claims-service |
| `/billing` | billing-service |
| `/notifications` | notification-service |
| `/pricing`, `/admin/champion`, `/admin/models` | pricing-service |

### Verified End-to-End Through Kong

1. `GET /products` â†’ 200 (16 products)
2. `PUT /customers/me/profile` â†’ 200 (profile created)
3. `GET /customers/me/profile` â†’ 200
4. `GET /admin/rate-versions` â†’ 200 (Administrator role)
5. `POST /pricing/quote` â†’ 200 (premium calculated with SHAP)
6. `GET /pricing/quote/{id}` â†’ 200
7. `POST /orders` â†’ 200 (PENDING_REVIEW)
8. `GET /admin/orders/review-queue` â†’ 200
9. `POST /admin/orders/{id}/approve` â†’ 200 (PENDING_PAYMENT + invoice created)
10. `GET /admin/orders/{id}` â†’ 200
11. `POST /billing/invoices` â†’ 200
12. `POST /billing/invoices/{id}/pay` â†’ 200 (paid, triggers PolicyIssued event)
13. `GET /notifications` â†’ 200
14. `GET /claims` â†’ 200
15. `GET /pricing/models` â†’ 200
16. No JWT â†’ 401 (JWT enforcement)
17. Customer on admin endpoint â†’ 403 (RBAC enforcement)


