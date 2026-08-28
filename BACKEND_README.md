# Event Ticketing Platform — Backend

A production-oriented, event-driven microservices backend for booking event tickets. Built incrementally over a structured 30-day plan covering Kafka, WebClient, JPA/Hibernate, Spring Security/JWT, Resilience4j, Docker, Kubernetes, and observability.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Services](#2-services)
3. [Tech Stack](#3-tech-stack)
4. [Build Timeline — What Was Done, Phase by Phase](#4-build-timeline--what-was-done-phase-by-phase)
5. [Project Structure](#5-project-structure)
6. [Prerequisites](#6-prerequisites)
7. [Local Development Setup](#7-local-development-setup)
8. [Environment Variables](#8-environment-variables)
9. [Database Migrations (Flyway)](#9-database-migrations-flyway)
10. [Kafka Topics](#10-kafka-topics)
11. [The Booking Saga](#11-the-booking-saga)
12. [Security Model](#12-security-model)
13. [Resilience Patterns](#13-resilience-patterns)
14. [Docker & Kubernetes](#14-docker--kubernetes)
15. [Observability](#15-observability)
16. [Testing](#16-testing)
17. [Known Issues & Gotchas Hit During Development](#17-known-issues--gotchas-hit-during-development)
18. [Known Limitations & Future Work](#18-known-limitations--future-work)

---

## 1. Architecture Overview

Six independently deployable Spring Boot services, each owning its own PostgreSQL database (database-per-service pattern). Services communicate synchronously via WebClient for request/response needs and asynchronously via Kafka for event-driven workflows (seat holds, payment outcomes, booking state transitions).

```
                                   ┌─────────────┐
                                   │   Ingress   │  (Kubernetes only)
                                   └──────┬──────┘
                                          │
        ┌──────────────┬─────────────────┼─────────────────┬──────────────┐
        │              │                 │                 │              │
   ┌────▼────┐   ┌─────▼─────┐    ┌──────▼──────┐   ┌──────▼──────┐  ┌────▼─────┐
   │  auth-  │   │ catalog-  │    │  inventory- │   │  booking-   │  │ payment- │
   │ service │   │ service   │    │  service    │   │  service    │  │ service  │
   └────┬────┘   └─────┬─────┘    └──────┬──────┘   └──────┬──────┘  └────┬─────┘
        │              │                  │                 │              │
   ┌────▼────┐   ┌─────▼─────┐    ┌──────▼──────┐   ┌──────▼──────┐       │
   │ auth_db │   │ catalog_db│    │ inventory_db│   │ booking_db  │       │
   └─────────┘   └───────────┘    └─────────────┘   └─────────────┘       │
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

---

## 2. Services

| Service | Port | Database | Responsibility |
|---|---|---|---|
| `auth-service` | 8085 | `auth_db` | User registration, login, JWT issuance (HS256, jjwt) |
| `catalog-service` | 8081 | `catalog_db` | Venues & Events CRUD, Redis-cached reads |
| `inventory-service` | 8082 | `inventory_db` | Seat inventory, optimistic-locked holds, scheduled hold expiry |
| `booking-service` | 8083 | `booking_db` | Saga orchestration, booking state machine (WebFlux) |
| `payment-service` | 8086 | — (stateless, Redis-backed) | Razorpay order creation, webhook handling |
| `notification-service` | 8084 | — (stateless) | Kafka consumer, transactional email (SMTP) |
| `common-events` | — | — | Shared Kafka event contract records (Maven module, not a runnable service) |

---

## 3. Tech Stack

- **Language / Framework:** Java 21, Spring Boot 4.0.7 (Spring Framework 7)
- **Build:** Maven, multi-module reactor (`common-events` + 6 service modules)
- **Persistence:** PostgreSQL 17, Spring Data JPA / Hibernate, **Flyway** migrations
- **Messaging:** Apache Kafka (KRaft mode, no Zookeeper), Spring Kafka
- **Caching:** Redis 7.2 (catalog read cache + payment order tracking)
- **Security:** Spring Security, JWT (`io.jsonwebtoken` / jjwt), BCrypt password hashing
- **Resilience:** Resilience4j — circuit breaker, retry, time limiter
- **Payments:** Razorpay (sandbox), HMAC-SHA256 webhook signature verification
- **Email:** SMTP (Brevo / Resend / SMTP2GO — interchangeable via `spring-boot-starter-mail`)
- **Containerization:** Docker multi-stage builds, Docker Compose
- **Orchestration:** Kubernetes (Minikube), HPA, Ingress (nginx)
- **Observability:** Micrometer, Prometheus, Grafana, Zipkin, structured JSON logging (Logback + logstash-encoder)
- **API Docs:** springdoc-openapi / Swagger UI

---

## 4. Build Timeline — What Was Done, Phase by Phase

This backend was built incrementally, each phase adding a working, testable increment:

1. **Project selection & scaffolding** — evaluated 3 candidate projects (ticketing, delivery/fleet, SaaS billing); selected multi-vendor event ticketing for its natural fit with concurrency, Kafka, payment gateway, and JWT requirements.
2. **`catalog-service`** — Venue/Event entities, JPA repositories, REST CRUD, Swagger, Postgres via Docker Compose.
3. **`inventory-service`** — Seat entity extracted into its own service/database (database-per-service pattern), optimistic locking via `@Version`, proven with a `CountDownLatch`-based concurrency test.
4. **`booking-service`** — WebClient composition calling `catalog-service` and `inventory-service`; introduced custom exceptions, `@Qualifier`-based bean disambiguation, explicit `@PathVariable`/`@RequestParam` naming (required due to Spring Boot 4's parameter-name-retention behavior).
5. **Kafka integration** — `SeatHeldEvent` / `SeatHoldExpiredEvent` published from `inventory-service`; `notification-service` introduced as a dedicated Kafka consumer; resolved several deserialization/listener-signature issues along the way (see [§17](#17-known-issues--gotchas-hit-during-development)).
6. **Booking saga** — `booking-service` given its own database; `Booking` entity with a state machine (`PENDING_PAYMENT → CONFIRMED / CANCELLED`); choreography-based saga reacting to `PaymentSucceededEvent` / `PaymentFailedEvent`; compensating action (`releaseSeat`) on payment failure; guarded against late-payment-after-hold-expiry race condition.
7. **Spring Security + JWT** — `auth-service` added (registration/login, BCrypt, JWT issuance); JWT verification added to `booking-service` (WebFlux-specific `WebFilter` + `ServerHttpSecurity`, distinct from the servlet-based approach used in `auth-service`).
8. **Payment gateway (Razorpay)** — initially built inside `booking-service`, then **extracted into a dedicated `payment-service`** to keep payment credentials and logic isolated; webhook signature verification, idempotent webhook processing, Redis-backed order tracking (`razorpayOrderId → bookingId/seatId`) replacing an initial in-memory `ConcurrentHashMap`.
9. **Email + Redis caching** — real transactional email via SMTP (`@Async`); Redis caching added to `catalog-service` for Venue/Event reads with per-cache TTLs and explicit `@CacheEvict` on writes.
10. **Hardening pass** — replaced `ddl-auto: update` with **Flyway** migrations across all four database-owning services; externalized all credentials to environment variables (`.env` / `.env.example`); disabled Kafka topic auto-creation in favor of an explicit, idempotent topic-init script with defined partition counts.
11. **Resilience4j** — circuit breaker + retry + time limiter on all cross-service WebClient calls (`InventoryClient`, `CatalogClient`, `PaymentClient`), with fallback methods and `ignore-exceptions` tuned to avoid treating normal business outcomes (e.g. 409 seat-already-held) as service failures.
12. **Docker** — multi-stage Dockerfiles (JDK builder stage, JRE-alpine runtime stage, non-root user), production-style Docker Compose with health checks, resource limits, and dual Kafka listeners (`localhost:9092` external / `kafka:29092` internal).
13. **Kubernetes** — full Minikube deployment: namespace, ConfigMaps/Secrets, PVC-backed infrastructure (Postgres ×4, Redis, Kafka), Deployments + Services for all six app services, HPA on `inventory-service` (CPU/memory-based, tuned scale-down stabilization window), Ingress routing by path.
14. **Observability** — structured JSON logging with trace-ID correlation propagated across WebClient calls via MDC; Micrometer + Prometheus metrics (including custom business counters); Grafana dashboard; Zipkin distributed tracing.
15. **Security follow-up** — CORS configuration added per-service once the React frontend began consuming the APIs directly from the browser; a hardcoded secret accidentally committed to a config file was caught by GitHub push protection and removed from history via interactive rebase (see [§17](#17-known-issues--gotchas-hit-during-development)).

---

## 5. Project Structure

```
event-ticketing-platform/
├── pom.xml                          # parent (multi-module) POM
├── common-events/                   # shared Kafka event record contracts
├── auth-service/
├── catalog-service/
├── inventory-service/
├── booking-service/
├── payment-service/
├── notification-service/
├── frontend/                        # React + TypeScript SPA
├── docker-compose.yml
├── .env.example
├── kafka-init/
│   └── create-topics.sh
├── k8s/
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── secrets.yaml.example
│   ├── ingress.yaml
│   ├── infrastructure/              # Postgres x4, Redis, Kafka manifests
│   ├── services/                    # 6 app Deployments/Services + HPA
│   └── monitoring/                  # Prometheus/Grafana/Zipkin manifests
└── monitoring/
    ├── prometheus.yml
    └── grafana/provisioning/
```

Each service module follows the same internal package layout:
```
com.ticketing.<service>/
├── domain/            # JPA entities
│   └── enums/         # entity status enums
├── dto/               # request/response records
├── repository/        # Spring Data JPA repositories
├── service/           # business logic
├── controller/        # REST endpoints
├── config/            # beans: WebClient, Redis, Security, CORS, etc.
├── listener/          # @KafkaListener classes
├── exception/         # custom exceptions + @RestControllerAdvice
└── security/          # JWT filter + SecurityConfig (where applicable)
```

---

## 6. Prerequisites

- JDK 21
- Maven (or the bundled `mvnw` wrapper)
- Docker Desktop
- Git
- IntelliJ IDEA (Community is sufficient for backend; Ultimate not required)
- (For frontend) Node.js + npm, VS Code recommended
- (For payment testing) ngrok, Razorpay sandbox account
- (For Kubernetes phase) Minikube, kubectl

---

## 7. Local Development Setup

```bash
# 1. Clone and enter the repo
git clone https://github.com/<you>/event-ticketing-platform.git
cd event-ticketing-platform

# 2. Copy env template and fill in real values
cp .env.example .env

# 3. Start infrastructure (4x Postgres, Redis, Kafka)
docker compose up -d catalog-postgres inventory-postgres booking-postgres auth-postgres redis kafka

# 4. Initialize Kafka topics (idempotent — safe to re-run)
bash kafka-init/create-topics.sh

# 5. Build everything (installs common-events into local Maven repo first)
mvn clean install

# 6. Run each service (from IntelliJ run configurations, or individually)
#    Order doesn't strictly matter, but auth/catalog/inventory before booking/payment
#    is a sensible startup order since booking-service calls the others.
```

Verify each service is up:
```bash
curl http://localhost:8081/actuator/health   # catalog-service
curl http://localhost:8082/actuator/health   # inventory-service
curl http://localhost:8083/actuator/health   # booking-service
curl http://localhost:8084/actuator/health   # notification-service
curl http://localhost:8085/actuator/health   # auth-service
curl http://localhost:8086/actuator/health   # payment-service
```

### Standard restart sequence after `docker compose down -v`

Wiping volumes means Flyway will recreate every schema from scratch on next service startup — no manual SQL needed. Kafka topics must be recreated via the init script since auto-create is disabled.

---

## 8. Environment Variables

All secrets and environment-specific config are externalized — **nothing sensitive lives in a committed `application.yml`**. See `.env.example` at the repo root for the full list, grouped by service:

- Database URLs/credentials (`CATALOG_DB_*`, `INVENTORY_DB_*`, `BOOKING_DB_*`, `AUTH_DB_*`)
- `JWT_SECRET` — shared HMAC-SHA256 key between `auth-service` (signs) and `booking-service` (verifies); minimum 256 bits, generate with `openssl rand -base64 32`
- `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`
- `KAFKA_BOOTSTRAP_SERVERS`, `REDIS_HOST`, `REDIS_PORT`
- SMTP credentials for the email provider in use
- Inter-service base URLs (`CATALOG_SERVICE_URL`, `INVENTORY_SERVICE_URL`, `PAYMENT_SERVICE_URL`)

Every `application.yml` uses the `${VAR_NAME:default}` pattern so local development works with sensible defaults while production/Docker/Kubernetes environments override via real environment variables. `JWT_SECRET` and the Razorpay secrets deliberately have **no default** — the application fails fast at startup if they're missing, rather than running insecurely.

---

## 9. Database Migrations (Flyway)

Every database-owning service uses Flyway instead of `ddl-auto: update`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate   # Hibernate checks schema matches entities, never modifies it
  flyway:
    enabled: true
    locations: classpath:db/migration
```

**Important (Spring Boot 4 specific):** the dependency required is the dedicated starter, not the raw Flyway core artifacts:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
```
`flyway-core` + `flyway-database-postgresql` alone are **not sufficient** on Spring Boot 4 — the starter is required to trigger Flyway's autoconfiguration. Omitting it causes Flyway to silently not run, which then surfaces as a confusing `SchemaManagementException: missing table` from Hibernate's `validate` check at startup.

Migration files live at `src/main/resources/db/migration/V{n}__{description}.sql` per service (naming is strict: capital `V`, double underscore, `.sql` extension).

---

## 10. Kafka Topics

Auto-topic-creation is disabled in production-style config (`KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"`). Topics are created explicitly via `kafka-init/create-topics.sh`:

| Topic | Partitions | Retention |
|---|---|---|
| `seat-events` | 3 | 7 days |
| `payment-events` | 3 | 7 days |
| `booking-events` | 3 | 7 days |
| `notification-events` | 3 | 7 days |

Run the init script after every fresh Kafka container start:
```bash
bash kafka-init/create-topics.sh
```

---

## 11. The Booking Saga

Choreography-based (not orchestrated) — each service reacts independently to Kafka events rather than a central coordinator directing steps:

```
1. POST /bookings                          booking-service
   → Booking row: PENDING_PAYMENT
   → WebClient → inventory-service: hold seat (optimistic lock, @Version)
   → Kafka: SeatHeldEvent

2. Seat held, 5-minute TTL                 inventory-service
   → SeatExpiryScheduler (every 60s)
   → On expiry: Kafka SeatHoldExpiredEvent → booking-service cancels
     the booking if it's still PENDING_PAYMENT

3. POST /bookings/{id}/create-payment-order   booking-service → payment-service
   → Razorpay order created; tracked in Redis (razorpayOrderId → bookingId, seatId)

4. Customer pays via Razorpay Checkout      (external)

5. Razorpay webhook: payment.captured/failed   payment-service
   → HMAC-SHA256 signature verified
   → Kafka: PaymentSucceededEvent / PaymentFailedEvent
   → Redis tracking entry removed

6. booking-service consumes the payment event
   → Guard: only confirm if Booking.status == PENDING_PAYMENT
     (protects against a late payment arriving after the hold already expired)
   → CONFIRMED → BookingConfirmedEvent → notification-service sends email
   OR
   → CANCELLED → compensating action: inventory-service releases the seat
```

---

## 12. Security Model

- **`auth-service`** issues JWTs (HS256) on register/login; passwords hashed with BCrypt.
- **Every other service verifies JWTs independently** using the same shared secret — no per-request network call back to `auth-service`.
- `booking-service` is WebFlux — JWT verification uses a reactive `WebFilter` + `ServerHttpSecurity`, distinct from the servlet-based `OncePerRequestFilter` + `HttpSecurity` pattern used elsewhere.
- **Webhook endpoints** (`payment-service`) are `permitAll()` at the Spring Security layer but protected independently via Razorpay's HMAC-SHA256 signature verification — a different, non-JWT trust mechanism appropriate for a caller (Razorpay) that has no user identity.
- **Internal service-to-service calls** (e.g. `booking-service → payment-service`) are currently unauthenticated at the application layer, relying on network-level isolation (Docker network / Kubernetes NetworkPolicy) rather than mTLS or per-call tokens — an explicit, documented trade-off for this project's scale.
- **CORS** is configured per-service (`CorsFilter` for servlet-based services, `CorsWebFilter` for `booking-service`) to allow the React frontend's dev origin.

---

## 13. Resilience Patterns

Applied via Resilience4j on all cross-service WebClient calls (`booking-service`'s `InventoryClient`/`CatalogClient`, `payment-service`'s Razorpay calls):

- **Circuit Breaker** — opens after a configurable failure-rate threshold within a sliding window; short-circuits to a fallback method while open; probes recovery in `HALF_OPEN` state.
- **Retry** — retries only on transient/IO exceptions; explicitly configured `ignore-exceptions` for legitimate business outcomes (e.g. `SeatAlreadyHeldException`) so they are never treated as failures worth retrying or counting against the circuit breaker.
- **Time Limiter** — bounds how long any single call attempt can take, paired with `CompletableFuture`-returning client methods.
- **Composition order:** CircuitBreaker → Retry → TimeLimiter (outermost to innermost) — this ordering is deliberate; reversing it changes failure-detection speed and behavior.

All annotated methods have fallback methods that degrade gracefully (a clean, typed "unavailable" response) rather than surfacing a raw 500.

---

## 14. Docker & Kubernetes

**Docker:** multi-stage builds (`eclipse-temurin:21-jdk-alpine` builder → `eclipse-temurin:21-jre-alpine` runtime), non-root container user, BuildKit cache mounts for Maven, container-aware JVM flags (`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`).

**Docker Compose:** full stack (6 services + 4 Postgres + Redis + Kafka), health checks with `depends_on: condition: service_healthy`, per-service resource limits, dual Kafka listener setup (`localhost:9092` for host-side tools, `kafka:29092` for inter-container traffic).

**Kubernetes (Minikube):** namespace isolation, Secrets vs. ConfigMaps split for sensitive vs. non-sensitive config, PVC-backed stateful infrastructure, readiness/liveness probes via dedicated Spring Boot Actuator health groups, HPA on `inventory-service` (the system's contention hotspot) with a tuned 5-minute scale-down stabilization window to prevent thrashing, Ingress routing all API paths through a single external entry point.

---

## 15. Observability

- **Logging:** structured JSON in production profile (Logback + `logstash-logback-encoder`); human-readable console output in local/dev profile; `X-Trace-Id` generated/propagated across every WebClient call and enriched into MDC alongside business context (`bookingId`, `seatId`, `customerEmail`).
- **Metrics:** `/actuator/prometheus` on every service; custom business counters (`seat.hold.attempts`, `booking.initiated`, `payment.webhook.processed`); p50/p75/p95/p99 latency histograms; Grafana dashboard covering request rate, p99 latency, circuit breaker state, seat-hold outcome ratio, Kafka consumer lag.
- **Tracing:** Micrometer Tracing (Brave bridge) + Zipkin; 100% sampling in dev; full cross-service trace visualization for any request.

---

## 16. Testing

- **Concurrency correctness:** `SeatConcurrencyTest` in `inventory-service` uses `ExecutorService` + a two/three-`CountDownLatch` pattern (ready-latch, start-latch, done-latch) to force genuinely simultaneous seat-hold requests, proving the `@Version` optimistic lock allows exactly one success under true contention — not just sequential requests that happen not to race.
- **Manual/API testing:** Postman collection maintained alongside curl-based verification at each phase; ngrok used to expose `payment-service` locally for real Razorpay sandbox webhook delivery during development.

---

## 17. Known Issues & Gotchas Hit During Development

Documented because they're non-obvious and likely to resurface:

- **Spring Boot 4 parameter-name retention:** `@PathVariable`, `@RequestParam`, and `@Qualifier` all require either the `-parameters` javac flag (configured at the parent POM's `maven-compiler-plugin`) or explicit string values (`@PathVariable("id")`) — omitting both produces a runtime `IllegalArgumentException`. Explicit naming is used throughout as the more robust, self-documenting choice regardless of the compiler flag.
- **Spring Boot 4 Jackson 2 vs Jackson 3:** Spring Kafka's `JsonSerializer`/`JsonDeserializer` still depend on classic Jackson 2 (`com.fasterxml.jackson.*`), which Spring Boot 4 no longer pulls in by default (it ships Jackson 3). `jackson-databind` and `jackson-datatype-jsr310` must be added explicitly to any service publishing/consuming Kafka messages with `LocalDateTime` fields.
- **Spring Boot 4 modularized starters:** both `spring-boot-starter-kafka` and `spring-boot-starter-flyway` are now **required** dedicated starters — the older pattern of adding raw client/core libraries (`spring-kafka`, `flyway-core`) directly is insufficient on Spring Boot 4 and causes silent autoconfiguration gaps.
- **Spring Data Redis 4.0:** `GenericJackson2JsonRedisSerializer` is deprecated in favor of `GenericJacksonJsonRedisSerializer` (Jackson 3-based).
- **`@KafkaListener(Object event)` pitfall:** binding a listener parameter to `Object` instead of the concrete event type (or `ConsumerRecord<K, V>`) can result in receiving the raw `ConsumerRecord` wrapper rather than the deserialized payload, depending on configuration — the reliable pattern used throughout this project is `ConsumerRecord<String, Object> record` with `record.value() instanceof X` checks.
- **Docker/Postgres credential drift:** `POSTGRES_USER`/`POSTGRES_PASSWORD` env vars only take effect on a volume's *first* initialization — changing them later without `docker compose down -v` leaves the old credentials active, producing password-auth failures that look unrelated to the actual cause.
- **JVM timezone mismatch:** a JDK/Postgres timezone parameter mismatch (`Asia/Calcutta` vs. `Asia/Kolkata`) can manifest as a misleading Hibernate dialect-detection error; fixed both via JVM flag (`-Duser.timezone=UTC`) and, more robustly, via `hibernate.jdbc.time_zone: UTC` in `application.yml` so it doesn't depend on any particular run configuration.
- **Secret leaked into a committed config file:** a real SMTP API key was briefly committed to an `application.yaml`. GitHub's push protection blocked the push before it reached the remote. Remediated via `git rebase -i` (marking the offending commit for `edit`, amending the file, continuing the rebase) followed by rotating the exposed key regardless, since local commit history had already contained the plaintext value.

---

## 18. Known Limitations & Future Work

- **No refund automation** — a payment that succeeds after its seat hold has already expired currently only logs a warning; a production system would trigger an automatic Razorpay refund.
- **`common-events` is a shared compile-time Maven module**, coupling producer/consumer schemas. A schema registry (Avro/Protobuf + Confluent Schema Registry) is the production-grade alternative.
- **Single-broker Kafka, single-instance Postgres/Redis** — no replication factor above 1 anywhere; acceptable for local/demo, not for production durability.
- **`payment-service` has no persistent order store** — Redis (with TTL) is the sole source of truth for `razorpayOrderId → bookingId` mapping; a production system would likely also persist this for audit/reconciliation.
- **No live seat-map updates** — the frontend's seat selection screen is a fetch-once snapshot; a real-time system would use WebSockets or polling to reflect concurrent holds.
- **Service-to-service calls are unauthenticated internally**, relying on network-level isolation rather than mTLS or a service mesh — a reasonable trade-off at this scale.
