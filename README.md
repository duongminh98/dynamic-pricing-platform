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