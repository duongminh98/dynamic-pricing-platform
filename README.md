# Dynamic Pricing Platform

A microservices platform for dynamic insurance pricing with an AI pricing engine (Python/FastAPI) and 6 Java/Spring Boot services.

## Quick Start

`ash
docker-compose up -d
`

This starts PostgreSQL (per-service DBs), Keycloak, RabbitMQ, Kong, Prometheus, and Grafana.

## Services and Ports

| Service | Port | DB |
|---------|------|----|
| Kong Gateway | 8000 | - |
| Keycloak | 8080 | - |
| Customer Service | 8081 | 5433 |
| Product Service | 8082 | 5434 |
| Order Service | 8083 | 5435 |
| Claims Service | 8085 | 5437 |
| Billing Service | 8086 | 5438 |
| Notification Service | 8087 | 5439 |
| Pricing Service (FastAPI) | 8000 (via Kong) | 5440 |
| RabbitMQ Management | 15672 | - |
| Prometheus | 9090 | - |
| Grafana | 3000 | - |

## Demo Accounts

| Role | Username | Password |
|------|----------|----------|
| Customer | demo.customer | demo_customer_dev_only |
| Administrator | demo.admin | demo_admin_dev_only |

## API Documentation

- Each Java service: http://localhost:8000/<service>/swagger-ui.html (via Kong)
- Pricing Service: http://localhost:8000/pricing/docs

## Build and Test

`ash
# Java services
./gradlew build test

# Pricing service
cd pricing
python -m pytest tests/ -q
python -m pytest common/tests/ -q
`

## Architecture

Sync via Kong gateway, async via RabbitMQ (outbox pattern, exchange platform.events).
All monetary amounts are VND integers. Two roles: Customer and Administrator.

