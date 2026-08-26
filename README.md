# Event Ticketing Platform

A production-oriented, event-driven microservices system for booking event tickets — built as a 30-day hands-on learning project covering Kafka, Spring WebClient, JPA/Hibernate, Spring Security/JWT, Docker, Kubernetes, resilience patterns, and third-party payment integration.

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Services](#services)
- [Key Design Decisions](#key-design-decisions)
- [The Booking Saga](#the-booking-saga)
- [Tech Stack](#tech-stack)
- [Local Development](#local-development)
- [Observability](#observability)
- [Known Limitations & Future Work](#known-limitations--future-work)

---

## Architecture Overview

```
                                   ┌─────────────┐
                                   │   Ingress   │
                                   └──────┬──────┘
                                          │
        ┌──────────────┬─────────────────┼─────────────────┬──────────────┐
        │               │                 │                 │              │
   ┌────▼────┐    ┌─────▼─────┐    ┌──────▼──────┐   ┌──────▼──────┐  ┌────▼─────┐
   │  auth-  │    │ catalog-  │    │  inventory- │   │  booking-   │  │ payment- │
   │ service │    │ service   │    │  service    │   │  service    │  │ service  │
   └────┬────┘    └─────┬─────┘    └──────┬──────┘   └──────┬──────┘  └────┬─────┘
        │               │                  │                 │              │
   ┌────▼────┐    ┌─────▼─────┐    ┌──────▼──────┐   ┌──────▼──────┐       │
   │ auth_db │    │ catalog_db│    │ inventory_db│   │ booking_db  │       │
   └─────────┘    └───────────┘    └─────────────┘   └─────────────┘       │
                         │                                                  │
                    ┌────▼────┐                                     ┌──────▼──────┐
                    │  Redis  │◄────────────────────────────────────┤   Razorpay  │
                    └─────────┘                                     │  (external) │
                                                                      └─────────────┘
        ┌───────────────────────────── Kafka ─────────────────────────────┐
        │  seat-events   payment-events   booking-events                  │
        └───────┬─────────────────┬──────────────────┬────────────────────┘
                 │                 │                  │
          ┌──────▼──────┐   ┌──────▼──────┐    ┌──────▼──────┐
          │ notification│   │  booking-   │    │  inventory- │
          │  -service   │   │  service    │    │  service    │
          └─────────────┘   └─────────────┘    └─────────────┘
```

Six independently deployable Spring Boot services, each owning its own database (database-per-service pattern), communicating synchronously via WebClient for request/response needs and asynchronously via Kafka for event-driven workflows.

## Services

| Service | Port | Owns | Responsibility |
|---|---|---|---|
| `auth-service` | 8085 | `auth_db` | User registration, login, JWT issuance |
| `catalog-service` | 8081 | `catalog_db` | Venues, Events (Redis-cached reads) |
| `inventory-service` | 8082 | `inventory_db` | Seat inventory, optimistic-locked holds, hold expiry |
| `booking-service` | 8083 | `booking_db` | Saga orchestration, booking state machine |
| `payment-service` | 8086 | — (stateless) | Razorpay integration, webhook handling |
| `notification-service` | 8084 | — (stateless) | Kafka consumer, transactional email |

## Key Design Decisions

### Why microservices, and why this particular split
Each service boundary maps to a distinct business capability with a different scaling profile: `catalog-service` is read-heavy and cacheable, `inventory-service` is write-heavy and contention-prone (hence dedicated HPA scaling), `payment-service` isolates third-party credentials to the smallest possible blast radius.

### Database-per-service
No service reads another's tables directly. Cross-service data needs are met via WebClient (synchronous) or Kafka events (asynchronous) — never a shared database or cross-database JOIN. This trades relational integrity for service autonomy and independent deployability.

### Choreography-based saga (not orchestration)
The booking flow (`hold seat → create payment order → webhook → confirm/cancel`) is coordinated by services independently reacting to Kafka events, rather than a central orchestrator directing each step. Simpler to build and reason about at this scale; a more complex saga with many steps would favor orchestration (e.g., via a dedicated coordinator service).

### Optimistic locking over pessimistic locking for seat holds
`Seat.version` (`@Version`) rejects concurrent writes at commit time rather than blocking readers with row locks. Chosen because seat contention is expected to be brief and infrequent per-row (many different seats, not many requests on one seat) — pessimistic locking would serialize unrelated bookings unnecessarily.

### Stateless JWT authentication
`auth-service` issues tokens; every other service verifies them independently using a shared HMAC-SHA256 secret — no network call back to `auth-service` per request. Avoids `auth-service` becoming a bottleneck/single point of failure for every authenticated request across the system.

## The Booking Saga

```
1. POST /bookings                       booking-service
   → Booking row created: PENDING_PAYMENT
   → WebClient → inventory-service: hold seat (optimistic lock)
   → Kafka: SeatHeldEvent published

2. Seat held, 5-minute TTL starts        inventory-service
   → SeatExpiryScheduler (every 60s) checks for expired holds
   → On expiry: Kafka SeatHoldExpiredEvent → booking-service
     cancels the booking if still PENDING_PAYMENT

3. POST /bookings/{id}/create-payment-order   booking-service → payment-service
   → Razorpay order created
   → Order tracked in Redis (razorpayOrderId → bookingId, seatId)

4. Customer pays on Razorpay checkout    (external, not our system)

5. Razorpay webhook: payment.captured    payment-service
   → Signature verified (HMAC-SHA256, X-Razorpay-Signature header)
   → Kafka: PaymentSucceededEvent published
   → Redis tracking entry removed

6. booking-service consumes PaymentSucceededEvent
   → Guard: only confirm if Booking.status == PENDING_PAYMENT
     (protects against a late payment arriving after hold expiry)
   → Booking → CONFIRMED
   → Kafka: BookingConfirmedEvent → notification-service sends email

   OR (payment.failed / hold expired first)
   → Booking → CANCELLED
   → Compensating action: inventory-service releases the seat
```

**Compensating transaction example:** if payment fails after a seat was held, `booking-service`'s `PaymentEventListener` calls `InventoryClient.releaseSeat()` (blocking, with explicit error logging) to undo the earlier hold — this is the saga's rollback mechanism, since there's no distributed transaction spanning both databases.

## Tech Stack

- **Language/Framework:** Java 21, Spring Boot 4.0.7 (Spring Framework 7)
- **Persistence:** PostgreSQL 17, Spring Data JPA/Hibernate, Flyway migrations
- **Messaging:** Apache Kafka (KRaft mode), Spring Kafka
- **Caching:** Redis 7.2
- **Security:** Spring Security, JWT (jjwt), BCrypt
- **Resilience:** Resilience4j (circuit breaker, retry, time limiter)
- **Payments:** Razorpay (sandbox), webhook signature verification
- **Email:** Brevo SMTP via Spring Mail
- **Containerization:** Docker (multi-stage builds), Docker Compose
- **Orchestration:** Kubernetes (Minikube), HPA, Ingress
- **Observability:** Micrometer, Prometheus, Grafana, Zipkin, structured JSON logging (Logback)
- **API Docs:** springdoc-openapi / Swagger UI

## Local Development

```bash
# 1. Start infrastructure
docker compose up -d

# 2. Initialize Kafka topics (first run only)
bash kafka-init/create-topics.sh

# 3. Run each service from IntelliJ, or:
docker compose up -d --build

# 4. Verify
curl http://localhost:8081/actuator/health
```

See `.env.example` for all required environment variables.

## Observability

- **Logs:** structured JSON in production (Logback + logstash-encoder), correlation via `X-Trace-Id` propagated through every WebClient call and enriched into MDC
- **Metrics:** `/actuator/prometheus` on every service; Grafana dashboard covers request rate, p99 latency, circuit breaker state, seat-hold outcome ratio, Kafka consumer lag
- **Tracing:** Zipkin, full cross-service trace visualization for any request

## Known Limitations & Future Work

Documented honestly — these are the trade-offs made for a 30-day learning scope, not oversights:

- **No refund automation.** If payment succeeds after a seat hold has already expired, the booking cannot be confirmed and a manual refund is currently just logged as a warning — a real system would trigger an automatic Razorpay refund via their API.
- **Shared `common-events` Maven module** couples services at compile time. A schema registry (Avro/Protobuf + Confluent Schema Registry) would be the production-grade alternative, decoupling producer/consumer schemas.
- **Single-broker Kafka, single-instance Postgres/Redis.** No replication factor > 1 anywhere — acceptable for local/demo, not for production durability.
- **`payment-service` has no persistent order store** — Redis (with TTL) is the source of truth for `razorpayOrderId → bookingId` mapping. A production system would likely also persist this in a proper table for audit/reconciliation.
- **No frontend yet.** All testing is via curl/Postman. React frontend and AI/LLM-based recommendations are planned future extensions.
- **Service-to-service calls are unauthenticated internally** (`permitAll()` on internal endpoints), relying on Kubernetes NetworkPolicy for protection rather than mTLS or service-mesh-level auth — a reasonable trade-off at this scale, revisited if the system grew.
