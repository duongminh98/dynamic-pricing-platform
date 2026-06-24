# Dynamic Pricing Platform

A microservices platform for dynamic insurance pricing featuring an AI pricing engine (Python/FastAPI with LightGBM + SHAP explainability) and six Java/Spring Boot services, orchestrated through a Kong API gateway with Keycloak JWT authentication and RabbitMQ async messaging.

## Architecture

```
                    ┌──────────┐
                    │ Keycloak │ (JWT RS256, 2 roles: Customer, Administrator)
                    └────┬─────┘
                         │
┌─────────┐      ┌───────▼────────┐      ┌──────────────────┐
│  Client │─────▶│  Kong Gateway  │─────▶│  Microservices   │
│ (React) │      │  (port 8000)   │      │  (6 Java + 1 Py) │
└─────────┘      └───────┬────────┘      └────────┬─────────┘
                         │                        │
                  ┌──────▼──────┐          ┌──────▼──────┐
                  │ Prometheus  │          │  RabbitMQ   │ (outbox pattern)
                  │   Grafana   │          │  exchange:  │
                  └─────────────┘          │platform.events│
                                           └─────────────┘
```

- **Sync flow:** Client → Kong Gateway (JWT verification) → Java/FastAPI services
- **Async flow:** Services publish events via transactional outbox → RabbitMQ exchange `platform.events`
- **Pricing:** FastAPI service loads 36 model artifacts (6 lines × 3 families × 2 algorithms), uses champion config for model selection, returns premiums with SHAP explanations

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

| Service | Technology | Port | Database | DB Port |
|---------|-----------|------|----------|---------|
| Kong Gateway | Kong 3.8 | 8000 (proxy), 8001 (admin) | — | — |
| Keycloak | Keycloak 26 | 8080 | — | — |
| Customer Service | Spring Boot | 8081 | customer_db | 5433 |
| Product Service | Spring Boot | 8082 | product_db | 5434 |
| Order Service | Spring Boot | 8083 | order_db | 5435 |
| Claims Service | Spring Boot | 8085 | claims_db | 5437 |
| Billing Service | Spring Boot | 8086 | billing_db | 5438 |
| Notification Service | Spring Boot | 8087 | notification_db | 5439 |
| Pricing Service | FastAPI | 8000 (in-container) | pricing_db | 5440 |
| RabbitMQ | 3.13 | 5672 (AMQP), 15672 (mgmt) | — | — |
| Prometheus | v2.55 | 9090 | — | — |
| Grafana | 11.3 | 3000 | — | — |

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

### Customer Service (8081)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/customers/register` | Public | Register new account (creates Keycloak user) |
| POST | `/customers/login` | Public | Login (returns JWT via Keycloak) |
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
| GET | `/admin/pricing/models` | Administrator | List registered model versions |
| POST | `/admin/champion/promote` | Administrator | Promote a model to champion |
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

1. **Register/Login** → Keycloak JWT (Customer or Administrator role)
2. **Profile** → `PUT /customers/me/profile` (line-specific validation: health, motorbike, car, home, accident, travel)
3. **Quote** → `POST /pricing/quote` (AI engine: champion model selection, feature derivation, premium calculation, SHAP explanation)
4. **Order** → `POST /orders` (status: `PENDING_REVIEW`)
5. **Admin Approve** → `POST /admin/orders/{id}/approve` (triggers invoice creation via BillingClient, status: `PENDING_PAYMENT`)
6. **Pay Invoice** → `POST /billing/invoices/{id}/pay` (publishes `InvoicePaid` event via outbox → RabbitMQ)
7. **Policy Issued** → Order-service consumes `InvoicePaid`, creates policy (status: `active`), publishes `PolicyIssued` event
8. **Claims** → `POST /claims/fnol` (requires active policy, ownership enforced)

## Build and Test

### Java Services

```bash
./gradlew build test
```

Runs jqwik property tests (≥100 iterations) with Mockito for all six services. The `product-service` integration tests (`@SpringBootTest`) require `postgres-product` on port 5434.

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
├── services/                 # Java/Spring Boot microservices
│   ├── common/               # Shared module (outbox, security, error handling)
│   ├── customer-service/     # Auth, profile management, validation
│   ├── product-service/      # Product catalog, admin config, rate versions
│   ├── order-service/        # Order lifecycle, policy issuance, endorsements
│   ├── billing-service/      # Invoice creation, payment, pro-rata adjustments
│   ├── claims-service/       # FNOL, approve/reject, misrepresentation
│   └── notification-service/ # Event-driven notifications
├── pricing/                  # Python/FastAPI pricing engine
│   ├── app/                  # Routers, engine, feature store, governance
│   ├── common/               # Shared Python module (errors, health, metrics)
│   └── migrations/           # Alembic DB migrations
├── infra/                    # Infrastructure configs
│   ├── kong/                 # Kong declarative config (routes, JWT, upstreams)
│   ├── keycloak/             # Realm export (roles, users, clients)
│   ├── prometheus/           # Prometheus scrape config
│   ├── grafana/              # Dashboards and provisioning
│   └── rabbitmq/             # Exchange/queue definitions
├── reports/modeling/models/  # 36 trained model artifacts + champion_config.json
├── data/synthetic_real/      # Training datasets, geo risk, cost indices
├── frontend/                 # React + Vite + TypeScript mini app
├── scripts/                  # Model training and EDA scripts
├── docker-compose.yml        # Infrastructure (13 containers)
└── .github/workflows/ci.yml  # CI/CD pipeline
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



## Model Lifecycle (Task 23, R37)

The platform supports offline model lifecycle management ? retraining triggers
and drift monitoring ? that runs entirely outside the serving path (R37.10).

### Retrain Trigger (`offline/retrain_trigger.py`)

Two independently configurable mechanisms (in `offline/retrain_config.json`):

1. **Schedule:** Quarterly by default (months 1, 4, 7, 10)
2. **Data threshold:** When new claims/exposure count for a line exceeds a
   configured threshold, trigger retrain for that line only

The trigger calls `train_pricing_models.py` ? `register_models.py` to create a
**candidate** Model_Version. It does NOT auto-promote ? promotion follows BR-23
governance (`POST /admin/champion/promote`).

```bash
python offline/retrain_trigger.py              # check + trigger
python offline/retrain_trigger.py --dry-run     # show what would trigger
python offline/retrain_trigger.py --line health # force one line
```

### Drift Monitor (`offline/drift_monitor.py`)

Per-line comparison of:

1. **Feature distribution drift:** PSI (Population Stability Index) between
   training and current input distributions
2. **Calibration drift:** actual-vs-predicted deviation by bin

When a metric exceeds its threshold (`drift_threshold_psi=0.2`,
`drift_threshold_calibration=0.15`), the `needs_recalibration` flag is set for
the line in the `model_drift_flag` table.

```bash
python offline/drift_monitor.py              # compute + persist flags
python offline/drift_monitor.py --dry-run     # compute without persisting
```

### Admin Drift Endpoint

```bash
GET /admin/pricing/drift    # Administrator role
```

Returns per-line drift status with PSI and calibration metrics, thresholds, and
the `needs_recalibration` flag. This can feed into the retrain trigger (23.1).

### Scheduling

The offline scripts are designed to be run via a cron job or GitHub Actions
schedule ? they do NOT run in the serving path. Example cron:

```cron
# Quarterly retrain check (Jan/Apr/Jul/Oct 1st at 2am)
0 2 1 1,4,7,10 * python /app/offline/retrain_trigger.py
# Weekly drift check (every Monday at 3am)
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

All API flows are verified through the Kong gateway (port 8000) with JWT enforcement:

- **No JWT** → `401 Unauthorized` (Kong JWT plugin verifies RS256 signature + expiration)
- **Customer JWT on admin endpoints** → `403 Forbidden` (role-based access control)
- **Valid JWT** → request forwarded to upstream service via `host.docker.internal`

The Kong declarative config (`infra/kong/kong.yml`) routes all service paths through the gateway:

| Path Prefix | Upstream Service |
|-------------|-----------------|
| `/customers` | customer-service |
| `/products`, `/admin/products`, `/admin/rate-versions` | product-service |
| `/orders`, `/admin/orders` | order-service |
| `/claims` | claims-service |
| `/billing` | billing-service |
| `/notifications` | notification-service |
| `/pricing`, `/admin/champion`, `/admin/pricing` | pricing-service |

### Verified End-to-End Through Kong

1. `GET /products` → 200 (16 products)
2. `PUT /customers/me/profile` → 200 (profile created)
3. `GET /customers/me/profile` → 200
4. `GET /admin/rate-versions` → 200 (Administrator role)
5. `POST /pricing/quote` → 200 (premium calculated with SHAP)
6. `GET /pricing/quote/{id}` → 200
7. `POST /orders` → 200 (PENDING_REVIEW)
8. `GET /admin/orders/review-queue` → 200
9. `POST /admin/orders/{id}/approve` → 200 (PENDING_PAYMENT + invoice created)
10. `GET /admin/orders/{id}` → 200
11. `POST /billing/invoices` → 200
12. `POST /billing/invoices/{id}/pay` → 200 (paid, triggers PolicyIssued event)
13. `GET /notifications` → 200
14. `GET /claims` → 200
15. `GET /admin/pricing/models` → 200
16. No JWT → 401 (JWT enforcement)
17. Customer on admin endpoint → 403 (RBAC enforcement)