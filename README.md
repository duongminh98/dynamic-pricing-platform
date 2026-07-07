# Dynamic Pricing Platform

---

## Overview

Dynamic Pricing Platform is a **microservices-based insurance platform** that delivers AI-powered dynamic pricing across six insurance lines. It covers the full customer journey: profile capture, quoting with explainable premiums, order review, payment, policy issuance, endorsements, and claims, backed by an offline model lifecycle for governed champion/challenger management.

### Architecture Highlights

- **Microservices Architecture** - Six Java/Spring Boot services plus one Python/FastAPI pricing engine
- **AI-Powered Pricing** - Frequency x Severity LightGBM models with SHAP explainability and monotonic constraints
- **Event-Driven Communication** - RabbitMQ async messaging via the transactional outbox pattern
- **Centralized API Gateway** - Kong for routing, JWT verification, and trusted identity header injection
- **Identity Management** - Keycloak (OAuth 2.0 / OpenID Connect) with two realm roles
- **Governed Model Lifecycle** - Offline retrain, drift monitoring, and champion promotion outside the serving path
- **Monitoring & Observability** - Prometheus metrics and Grafana dashboards
- **Cloud-Native Deployment** - GKE Autopilot, Cloud SQL, and Cloud Run Jobs on Google Cloud

---

## Project Structure

```
Dynamic Pricing Platform/
├── services/                         # Java/Spring Boot 3.3 microservices (Java 17)
│   ├── common/                       # Shared module (outbox, security, error handling)
│   ├── customer-service/             # Auth events, profile management, line validation
│   ├── product-service/              # Product catalog, admin config, rate versions
│   ├── order-service/                # Order lifecycle, policy issuance, endorsements
│   ├── billing-service/              # Invoice creation, VNPay payment, pro-rata adjustments
│   ├── claims-service/               # FNOL, approve/reject, misrepresentation
│   ├── notification-service/         # Event-driven notifications
│   └── build.gradle                  # Gradle multi-module build config
│
├── pricing/                          # Python/FastAPI pricing engine
│   ├── app/                          # Routers, pricing engine, feature store, governance
│   ├── common/                       # Shared Python module (errors, health, metrics)
│   └── migrations/                   # Alembic DB migrations
│
├── offline/                          # Offline model lifecycle (never in serving path)
│   ├── build_training_dataset_from_pricing_db.py  # Immutable dataset export + registry
│   ├── train_pricing_models.py       # Frequency x Severity training
│   ├── compare_candidate_to_champion.py           # Holdout comparison gate
│   ├── register_candidate_model.py   # Register after gates pass
│   ├── model_lifecycle_pipeline.py   # Coarse export→train→compare→gate→register job
│   ├── retrain_trigger.py            # Schedule / data-threshold / drift trigger
│   └── drift_monitor.py              # Feature PSI, prediction PSI, calibration
│
├── frontend/                         # React 18 + Vite 5 + TypeScript mini app
│   └── src/
│       ├── pages/                    # Route pages (customer + admin)
│       ├── components/               # Reusable components
│       ├── api/                      # API client
│       ├── auth/                     # Keycloak integration
│       └── lib/                      # Formatting, domain, UI helpers
│
├── infra/                            # Infrastructure configs
│   ├── kong/                         # Kong declarative config (routes, JWT, upstreams)
│   ├── keycloak/                     # Realm export (roles, users, clients)
│   ├── prometheus/                   # Prometheus scrape config
│   ├── grafana/                      # Dashboards and provisioning
│   └── rabbitmq/                     # Exchange/queue definitions
│
├── deploy/                           # GCP deployment (GKE + Cloud Run lifecycle)
│   ├── provision.sh                  # Project, VPC, Cloud SQL, buckets, IAM
│   ├── build_images.sh               # Cloud Build image builds
│   ├── deploy.sh                     # Render + apply k8s manifests
│   ├── lifecycle_deploy.sh           # Cloud Run Jobs + Workflow + Scheduler
│   ├── smoke.sh                      # Post-deploy smoke checks
│   └── k8s/                          # Rendered Kubernetes manifests
│
├── reports/modeling/models/          # 36 trained model artifacts + champion_config.json
├── data/                             # Training datasets, geo risk, cost indices
├── scripts/                          # Model training and EDA scripts
├── docker-compose.yml                # Local orchestration (full stack)
├── .github/workflows/                # CI + CD pipelines
└── README.md                         # This file
```

---

## Quick Start

### Prerequisites

- **Docker Desktop** - with WSL2 backend, for containerized deployment
- **Java 17+** - for backend development (Gradle toolchain auto-resolves)
- **Node 20+** - for frontend development
- **Python 3.11+** - for pricing service development

### 1. Clone & Configure

```bash
git clone https://github.com/duongminh98/dynamic-pricing-platform.git
cd "Dynamic Pricing Platform"

# Copy environment template
cp .env.example .env

# Edit .env with your configuration
# Required: DB passwords, Keycloak credentials, VNPay sandbox keys, etc.
```

### 2. Start All Services

```bash
# Build and start the full stack in containers
docker compose up --build -d
```

This builds and starts the full stack: 7 PostgreSQL databases, RabbitMQ, Keycloak, Kong, Prometheus, Grafana, all six Spring Boot services (two replicas each), the Pricing service (two replicas, FastAPI), and the frontend (nginx). Java services run Flyway migrations on startup; the Pricing container runs `alembic upgrade head` before serving.

### 3. Verify Services

```bash
# Check all services are running
docker compose ps

# Service health checks
curl http://localhost:8000                              # Kong API Gateway
curl http://localhost:8000/pricing/health               # Pricing Service
curl http://localhost:8080/realms/dynamic-pricing       # Keycloak realm
```

### 4. Access Applications

| Application | URL | Credentials |
|-------------|-----|-------------|
| **Frontend** | http://localhost:3001 | See Demo Accounts below |
| **API Gateway (Kong)** | http://localhost:8000 | JWT via Keycloak |
| **Keycloak** | http://localhost:8080 | admin/admin (realm: dynamic-pricing) |
| **Grafana** | http://localhost:3000 | admin/admin |
| **RabbitMQ Management** | http://localhost:15672 | See .env |
| **Prometheus** | http://localhost:9090 | - |

---

## Cloud Deployment (GCP Staging)

A live staging environment runs on Google Cloud (GKE Autopilot + Cloud SQL, region `asia-southeast1`). Public traffic enters through Kong with managed TLS certificates.

### Live Endpoints

| Endpoint | URL | Description |
|----------|-----|-------------|
| **Frontend (SPA)** | https://app.dpp-pricing.dev | React mini app |
| **API Gateway (Kong)** | https://api.dpp-pricing.dev | All service APIs (JWT enforced) |
| **Keycloak** | https://auth.dpp-pricing.dev | Identity provider (realm: dynamic-pricing) |

Quick checks:

```bash
curl https://api.dpp-pricing.dev/products                          # public catalog (200)
curl https://auth.dpp-pricing.dev/realms/dynamic-pricing/.well-known/openid-configuration
curl https://api.dpp-pricing.dev/pricing/quote                     # 401 without JWT
```

### Cloud Architecture

- **GKE Autopilot** cluster `dpp` (namespace `dpp`) runs all seven app services, Kong, Keycloak, and RabbitMQ.
- **Cloud SQL** PostgreSQL 16 (`dpp-pg`, private IP, 8 databases) with a Cloud SQL Auth Proxy sidecar per pod.
- **Two GKE Ingress + managed certificates** front Kong (app + api) and Keycloak (auth).
- **Offline model lifecycle** runs as **Cloud Run Jobs** (`pricing-lifecycle`, `pricing-drift-monitor`) orchestrated by a **Cloud Workflow** and a daily **Cloud Scheduler** trigger, all outside the serving path.
- **Artifact Registry** hosts service images; **Secret Manager** holds DB URLs and client secrets; **Workload Identity Federation** provides keyless CI/CD auth.

> Staging burns roughly $10-20/day. When idle, the cluster and Cloud SQL instance may be torn down to save credit and later re-provisioned from `deploy/provision.sh` + `deploy/deploy.sh`.

---

## Demo Accounts

Two seeded accounts cover the two realm roles. These are development-only credentials.

| Role | Username | Password | Keycloak Subject |
|------|----------|----------|------------------|
| **Customer** | demo.customer | demo_customer_dev_only | 01e0e4c0-3671-4e84-a97b-7fdfd51fa901 |
| **Administrator** | demo.admin | demo_admin_dev_only | 48cd30e3-a9ba-40e0-b4f1-93d1f408cfe3 |

Acquire a JWT via the Keycloak password grant (swap the host for `localhost:8080` locally or `auth.dpp-pricing.dev` on staging):

```bash
curl -X POST https://auth.dpp-pricing.dev/realms/dynamic-pricing/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=mini-app" \
  -d "username=demo.customer" \
  -d "password=demo_customer_dev_only" \
  -d "scope=openid"
```

Use the returned `access_token` as `Authorization: Bearer <token>` against the API gateway.

---

## Services Overview

### Backend Services (Java / Spring Boot 3.3, Java 17)

All Java services listen on private port 8080 inside the Docker network and are reached only through Kong. Each owns its own PostgreSQL database.

| Service | Database | Description |
|---------|----------|-------------|
| **customer-service** | customer_db | Profile management, line-specific validation, auth events |
| **product-service** | product_db | Product catalog, admin config, append-only rate versions |
| **order-service** | order_db | Order lifecycle, policy issuance, endorsements |
| **billing-service** | billing_db | Invoice creation, VNPay payment, pro-rata adjustments |
| **claims-service** | claims_db | FNOL, approve/reject, misrepresentation |
| **notification-service** | notification_db | Event-driven notifications |

### Pricing Service (Python / FastAPI)

| Service | Database | Description |
|---------|----------|-------------|
| **pricing-service** | pricing_db | Explainable dynamic pricing across six lines |

**Features:**
- Frequency x Severity modeling with LightGBM
- SHAP explainability with per-feature contribution narratives
- 36 model artifacts (6 lines x 3 families x 2 algorithms), champion-only serving
- Monotonic constraints and smoothness gates enforced offline
- VND integer premiums (no floating point money)

### Frontend (React + TypeScript)

- **Framework:** React 18 with TypeScript 5.6
- **Build Tool:** Vite 5
- **Routing:** React Router v6
- **Auth:** Keycloak (OIDC) via API client
- **Features:**
  - Profile capture across six insurance lines
  - Quote flow with premium and SHAP explanation
  - Order review, payment, and policy tracking
  - Claims (FNOL) submission and history
  - Admin dashboard: order/claims review, model lifecycle, drift monitoring

---

## Technology Stack

### Backend
```
├── Spring Boot 3.3.5
├── Spring Data JPA
├── PostgreSQL (Flyway migrations)
├── RabbitMQ (transactional outbox)
├── Kong Gateway (JWT enforcement)
└── Java 17
```

### Frontend
```
├── React 18
├── TypeScript 5.6
├── Vite 5 (Build Tool)
├── React Router v6
└── Keycloak (OIDC)
```

### AI / ML
```
├── FastAPI (Web Framework)
├── LightGBM (ML Models)
├── SHAP (Explainability)
├── scikit-learn
├── pandas, numpy
├── SQLAlchemy + Alembic
└── Python 3.11
```

### Infrastructure
```
├── Docker Compose (Local Orchestration)
├── Kong (API Gateway)
├── Keycloak (Identity)
├── RabbitMQ (Event Streaming)
├── Prometheus (Metrics)
├── Grafana (Dashboards)
└── PostgreSQL (Database, one per service)
```

---

## Key Integrations

### 1. Pricing Service Integration

The frontend calls the pricing engine through Kong to generate an explainable quote:

```
POST /pricing/quote        (Customer JWT)
→ champion model selection, feature derivation, premium calculation, SHAP explanation
→ returns final_premium_vnd, risk breakdown, and per-feature contributions
```

### 2. Event Flow (RabbitMQ + Outbox)

Each service owns an `outbox` table. An `OutboxRelay` polls it and publishes to the RabbitMQ exchange `platform.events` with the event type as routing key:

```
Order Service   → InvoicePaid consumer → issues policy → PolicyIssued
Billing Service → InvoicePaid          → Notification Service
Product Service → product events        → Pricing read-model
```

### 3. Kong Gateway

Kong validates JWTs at the edge, strips any spoofed `X-Authenticated-*` headers, injects trusted identity from JWT claims, and forwards to private upstreams. Services perform role checks (Customer vs Administrator) using those trusted headers.

---

## API Endpoints

All endpoints are reached through Kong. On staging the base is `https://api.dpp-pricing.dev`; locally it is `http://localhost:8000`.

### Customer Service

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/customers/me` | Customer | Get current customer info |
| PUT | `/customers/me/profile` | Customer | Upsert insurance profile (line-specific validation) |
| GET | `/customers/me/profile` | Customer | Get latest profile |

### Product Service

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/products` | Public | List all products |
| GET | `/products/{id}` | Public | Get product detail with coverage options |
| POST | `/admin/products` | Administrator | Create product |
| PUT | `/admin/products` | Administrator | Update product |
| PUT | `/admin/loading-factors` | Administrator | Update loading factor |
| GET | `/admin/rate-versions` | Administrator | List rate versions (append-only) |

### Pricing Service

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/pricing/health` | Public | Health check |
| POST | `/pricing/quote` | Customer | Generate quote (real premium + SHAP explanation) |
| GET | `/pricing/quote/{id}` | Customer | Retrieve stored quote |
| GET | `/pricing/models` | Administrator | List model versions with dataset/artifact lineage and champion flag |
| GET | `/pricing/drift` | Administrator | Per-line drift metrics |
| POST | `/admin/champion/promote` | Administrator | Promote a model to champion |
| POST | `/admin/models/reject` | Administrator | Explicitly reject a candidate model |
| POST | `/admin/champion/rollback` | Administrator | Rollback champion to previous |

### Order Service

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/orders` | Customer | Create order from quote (status: PENDING_REVIEW) |
| GET | `/admin/orders/review-queue` | Administrator | List pending orders |
| GET | `/admin/orders/{id}` | Administrator | Get order detail |
| POST | `/admin/orders/{id}/approve` | Administrator | Approve order (creates invoice) |
| POST | `/admin/orders/{id}/reject` | Administrator | Reject order |

### Billing Service

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/billing/invoices` | Public | Create invoice |
| POST | `/billing/invoices/{id}/pay` | Authenticated | Pay invoice (publishes InvoicePaid event) |
| POST | `/billing/invoices/{id}/payment-url` | Customer | Create VNPay payment URL |
| GET | `/billing/vnpay/return` | Public | VNPay browser redirect (display only) |
| GET | `/billing/vnpay/ipn` | Public | VNPay server-to-server callback (source of truth) |
| GET | `/billing/vnpay/status` | Customer | Query payment status |

### Claims Service

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/claims/fnol` | Customer | First notification of loss |
| GET | `/claims` | Customer | List own claims |
| GET | `/claims/{id}` | Customer | Get claim detail (ownership enforced) |
| POST | `/claims/{id}/approve` | Administrator | Approve claim payout |
| POST | `/claims/{id}/reject` | Administrator | Reject claim |
| POST | `/claims/{id}/misrepresentation` | Administrator | Flag misrepresentation |

### Notification Service

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/notifications` | Customer | List own notifications |

---

## End-to-End Flow

The complete customer journey:

1. **Register/Login** -> Keycloak hosted UI -> JWT (Customer or Administrator role)
2. **Profile** -> `PUT /customers/me/profile` (line-specific validation: health, motorbike, car, home, accident, travel)
3. **Quote** -> `POST /pricing/quote` (champion model selection, feature derivation, premium calculation, SHAP explanation)
4. **Order** -> `POST /orders` (status: PENDING_REVIEW)
5. **Admin Approve** -> `POST /admin/orders/{id}/approve` (triggers invoice creation, status: PENDING_PAYMENT)
6. **Pay Invoice** -> `POST /billing/invoices/{id}/pay` or VNPay flow (publishes InvoicePaid via outbox -> RabbitMQ)
7. **Policy Issued** -> order-service consumes InvoicePaid, creates policy (status: active), publishes PolicyIssued
8. **Claims** -> `POST /claims/fnol` (requires active policy, ownership enforced)

---

## Development

### Backend Development

```bash
# Build and test all services
./gradlew build test

# Run a single service from a built jar
./gradlew :services:product-service:bootJar
java -jar services/product-service/build/libs/*.jar
```

Java services run jqwik property tests (>=100 iterations) with Mockito. The `product-service` integration tests (`@SpringBootTest`) require `postgres-product` on port 5434.

### Frontend Development

```bash
cd frontend

npm install        # Install dependencies
npm run dev        # Run dev server
npm run build      # Build for production
```

### Pricing Service Development

```bash
cd pricing

python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

alembic upgrade head                                  # apply migrations
uvicorn app.main:app --port 9001 --reload             # run dev server

python -m pytest tests/ -q                            # pricing engine tests
python -m pytest common/tests/ -q                     # common module tests
```

---

## Model Lifecycle (Offline, No A/B)

The platform runs offline model lifecycle management entirely outside the serving path. Runtime pricing is champion-only: no A/B tables, no shadow scoring, no canary rollout, and no candidate-served customer quotes.

### Dataset + Candidate Flow

1. Export and register an immutable dataset version (manifest, checksums, row counts):

```bash
python offline/build_training_dataset_from_pricing_db.py \
  --database-url postgresql://platform_user:platform_password_dev_only@localhost:5440/pricing_db \
  --output-dir data/pricing_read_model_export \
  --dataset-version-id ds-2026-q3 \
  --register-registry --created-by offline-operator
```

2. Train a candidate, compare it to the current champion on the same holdout, then register only after comparison, validation, monotonic, and smoothness gates pass. On GCP this runs as the coarse `model_lifecycle_pipeline` Cloud Run Job.

Administrators then promote, reject, or roll back via `/admin/champion/promote`, `/admin/models/reject`, and `/admin/champion/rollback`. The admin dashboard surfaces model lineage, quality gates, and per-line drift.

### Retrain Trigger and Drift Monitor

```bash
python offline/retrain_trigger.py --dry-run    # schedule / data-threshold / drift trigger
python offline/drift_monitor.py --dry-run      # feature PSI, prediction PSI, calibration
```

Triggers create a candidate model version only; promotion always follows governance. On GCP a daily Cloud Scheduler drives the drift monitor and lifecycle Workflow.

---

## Monitoring

### Prometheus Metrics

Services expose metrics for scraping (JVM, HTTP request rates and latency, custom business metrics, RabbitMQ consumer state).

### Grafana Dashboards

Pre-configured dashboards for service health, request rate and latency, database connection pools, and pricing metrics.

---

## Security

### Authentication & Authorization

- **Keycloak** for identity management (OAuth 2.0 / OpenID Connect)
- **JWT (RS256)** validated centrally at Kong
- **Role-based access control** with two realm roles: Customer and Administrator
- Kong strips spoofed identity headers and injects trusted claims to upstreams

### Data Protection

- TLS termination via GKE managed certificates on staging
- SQL injection prevention (JPA / SQLAlchemy parameterized queries)
- XSS protection (React)
- Secrets via environment variables locally, Secret Manager on GCP

---

## CI/CD

### CI Pipeline (`.github/workflows/ci.yml`)

Runs on every push and pull request to `master`:

- **java job:** `./gradlew clean test` + JaCoCo coverage with a 70% gate
- **python job:** `pytest --cov --cov-fail-under=70` for the pricing service
- **docker-build job:** builds all service images + frontend (no push)

### CD Pipeline (`.github/workflows/cd.yml`)

Continuous deployment to GKE staging, running only after CI passes. Uses keyless auth to GCP via Workload Identity Federation (no service-account key in the repo).

---

## License

Internal use - Dynamic Pricing Platform.
