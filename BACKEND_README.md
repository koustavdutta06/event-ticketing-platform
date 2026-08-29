# Event Ticketing Platform — Backend

A production-oriented, event-driven microservices backend for booking event tickets. Built incrementally following a structured 30-day plan covering Kafka, WebClient, JPA/Hibernate, Spring Security/JWT, Resilience4j, and Docker — with Kubernetes and observability tooling (Logstash, Prometheus, Grafana, Zipkin) planned but not yet started.

**Status:** Days 1–24 complete — all six services implemented, hardened (Flyway, externalized config, explicit Kafka topics), resilience-wrapped (Resilience4j), and fully containerized (multi-stage Docker builds + production Docker Compose). All five app services plus four Postgres instances, Kafka, and Redis run healthy together via `docker compose up -d`. Kubernetes and observability tooling are not implemented yet — see [§17](#17-known-limitations--future-work).

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
14. [Docker](#14-docker)
15. [Testing](#15-testing)
16. [Known Issues & Gotchas Hit During Development](#16-known-issues--gotchas-hit-during-development)
17. [Known Limitations & Future Work](#17-known-limitations--future-work)

---

## 1. Architecture Overview

Six independently deployable Spring Boot services, each owning its own PostgreSQL database (database-per-service pattern). Services communicate synchronously via WebClient for request/response needs and asynchronously via Kafka for event-driven workflows (seat holds, payment outcomes, booking state transitions).

```
        ┌──────────────┬─────────────────┬─────────────────┬──────────────┐
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
- **API Docs:** springdoc-openapi / Swagger UI

---

## 4. Build Timeline — What Was Done, Phase by Phase

This backend was built incrementally, each phase adding a working, testable increment:

1. **Project selection & scaffolding** — evaluated 3 candidate projects (ticketing, delivery/fleet, SaaS billing); selected multi-vendor event ticketing for its natural fit with concurrency, Kafka, payment gateway, and JWT requirements.
2. **`catalog-service`** — Venue/Event entities, JPA repositories, REST CRUD, Swagger, Postgres via Docker Compose.
3. **`inventory-service`** — Seat entity extracted into its own service/database (database-per-service pattern), optimistic locking via `@Version`, proven with a `CountDownLatch`-based concurrency test.
4. **`booking-service`** — WebClient composition calling `catalog-service` and `inventory-service`; introduced custom exceptions, `@Qualifier`-based bean disambiguation, explicit `@PathVariable`/`@RequestParam` naming (required due to Spring Boot 4's parameter-name-retention behavior).
5. **Kafka integration** — `SeatHeldEvent` / `SeatHoldExpiredEvent` published from `inventory-service`; `notification-service` introduced as a dedicated Kafka consumer; resolved several deserialization/listener-signature issues along the way (see [§16](#16-known-issues--gotchas-hit-during-development)).
6. **Booking saga** — `booking-service` given its own database; `Booking` entity with a state machine (`PENDING_PAYMENT → CONFIRMED / CANCELLED`); choreography-based saga reacting to `PaymentSucceededEvent` / `PaymentFailedEvent`; compensating action (`releaseSeat`) on payment failure; guarded against late-payment-after-hold-expiry race condition.
7. **Spring Security + JWT** — `auth-service` added (registration/login, BCrypt, JWT issuance); JWT verification added to `booking-service` (WebFlux-specific `WebFilter` + `ServerHttpSecurity`, distinct from the servlet-based approach used in `auth-service`).
8. **Payment gateway (Razorpay)** — initially built inside `booking-service`, then **extracted into a dedicated `payment-service`** to keep payment credentials and logic isolated; webhook signature verification, idempotent webhook processing, Redis-backed order tracking (`razorpayOrderId → bookingId/seatId`) replacing an initial in-memory `ConcurrentHashMap`.
9. **Email + Redis caching** — real transactional email via SMTP (`@Async`); Redis caching added to `catalog-service` for Venue/Event reads with per-cache TTLs and explicit `@CacheEvict` on writes.
10. **Hardening pass** — replaced `ddl-auto: update` with **Flyway** migrations across all four database-owning services; externalized all credentials to environment variables (`.env` / `.env.example`); disabled Kafka topic auto-creation in favor of an explicit, idempotent topic-init script with defined partition counts.
11. **Resilience4j** — circuit breaker + retry + time limiter on all cross-service WebClient calls (`InventoryClient`, `CatalogClient`, `PaymentClient`), with fallback methods and `ignore-exceptions` tuned to avoid treating normal business outcomes (e.g. 409 seat-already-held) as service failures.
12. **Docker** — multi-stage Dockerfiles (JDK builder stage, JRE-alpine runtime stage, non-root user), production-style Docker Compose with health checks, resource limits, and dual Kafka listeners (`localhost:9092` external / `kafka:29092` internal).
13. **Security follow-up** — CORS configuration added per-service once the React frontend began consuming the APIs directly from the browser; a hardcoded secret accidentally committed to a config file was caught by GitHub push protection and removed from history via interactive rebase (see [§16](#16-known-issues--gotchas-hit-during-development)).

Kubernetes deployment and the observability stack (structured logging, metrics, tracing) are the next planned phases — not started yet; see [§17](#17-known-limitations--future-work).

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
└── kafka-init/
    └── create-topics.sh
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

## 14. Docker

**Docker:** multi-stage builds (`eclipse-temurin:21-jdk-alpine` builder → `eclipse-temurin:21-jre-alpine` runtime), non-root container user, BuildKit cache mounts for Maven, container-aware JVM flags (`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`).

**Docker Compose:** full stack (6 services + 4 Postgres + Redis + Kafka), health checks with `depends_on: condition: service_healthy`, per-service resource limits, dual Kafka listener setup (`localhost:9092` for host-side tools, `kafka:29092` for inter-container traffic).

Kubernetes deployment (Minikube) is planned but not yet started — see [§17](#17-known-limitations--future-work).

---

## 15. Testing

- **Concurrency correctness:** `SeatConcurrencyTest` in `inventory-service` uses `ExecutorService` + a two/three-`CountDownLatch` pattern (ready-latch, start-latch, done-latch) to force genuinely simultaneous seat-hold requests, proving the `@Version` optimistic lock allows exactly one success under true contention — not just sequential requests that happen not to race.
- **Manual/API testing:** Postman collection maintained alongside curl-based verification at each phase; ngrok used to expose `payment-service` locally for real Razorpay sandbox webhook delivery during development.

---

## 16. Known Issues & Gotchas Hit During Development

Documented because they're non-obvious and likely to resurface. Grouped roughly by phase.

### Framework / dependency version gaps (Spring Boot 4 is very new)

- **Spring Boot 4 parameter-name retention:** `@PathVariable`, `@RequestParam`, and `@Qualifier` all require either the `-parameters` javac flag (configured at the parent POM's `maven-compiler-plugin`) or explicit string values (`@PathVariable("id")`) — omitting both produces a runtime `IllegalArgumentException`. Explicit naming is used throughout as the more robust, self-documenting choice regardless of the compiler flag.
- **Spring Boot 4 Jackson 2 vs Jackson 3:** Spring Kafka's `JsonSerializer`/`JsonDeserializer` still depend on classic Jackson 2 (`com.fasterxml.jackson.*`), which Spring Boot 4 no longer pulls in by default (it ships Jackson 3). `jackson-databind` and `jackson-datatype-jsr310` must be added explicitly to any service publishing/consuming Kafka messages with `LocalDateTime` fields.
- **Spring Boot 4 modularized starters:** `spring-boot-starter-kafka`, `spring-boot-starter-flyway`, and `spring-boot-starter-aspectj` (renamed from `spring-boot-starter-aop`) are all **required** dedicated starters — the older pattern of adding raw client/core libraries (`spring-kafka`, `flyway-core`, `spring-boot-starter-aop`) directly is insufficient on Spring Boot 4 and causes silent autoconfiguration gaps or missing-version build errors.
- **Resilience4j on Spring Boot 4 requires `resilience4j-spring-boot4`** (introduced in Resilience4j 2.4.0), not `resilience4j-spring-boot3` — the latter doesn't resolve against Spring Framework 7. Requires `spring-boot-starter-aspectj` alongside it for the AOP proxying that powers `@CircuitBreaker`/`@Retry`/`@TimeLimiter`.
- **Resilience4j BOM version conflict via `spring-cloud-dependencies`:** a Spring Cloud circuit-breaker starter was evaluated (then dropped in favor of the pure annotation-based `resilience4j-spring-boot4` approach), but its `spring-cloud-dependencies` BOM import was left in the parent POM — and `spring-cloud-dependencies` transitively imports `spring-cloud-circuitbreaker-dependencies`, which imports `resilience4j-bom:2.2.0`. In Maven, when multiple imported BOMs manage the same artifact, **whichever `dependencyManagement` import appears first wins** — not the more specific one, not the newer one. That transitive `2.2.0` pin silently overrode the project's explicit `resilience4j-spring-boot4:2.4.0` dependency's own transitive versions, causing a runtime `NoClassDefFoundError` for `RxJava3FallbackDecorator` (only present in `resilience4j-spring6 >= 2.3.0`). Fixed by explicitly importing `resilience4j-bom:2.4.0` **before** `spring-cloud-dependencies` in the root `pom.xml`'s `dependencyManagement`, so the correct version wins the first-import-wins tiebreak. `spring-cloud-dependencies` itself is otherwise unused (the Spring Cloud circuit-breaker starter it was originally added for is commented out in `booking-service`/`payment-service`'s POMs) and is a candidate for removal in a future cleanup pass.
- **Spring Data Redis 4.0:** `GenericJackson2JsonRedisSerializer` is deprecated in favor of `GenericJacksonJsonRedisSerializer` (Jackson 3-based).
- **`@KafkaListener(Object event)` pitfall:** binding a listener parameter to `Object` instead of the concrete event type (or `ConsumerRecord<K, V>`) can result in receiving the raw `ConsumerRecord` wrapper rather than the deserialized payload, depending on configuration — the reliable pattern used throughout this project is `ConsumerRecord<String, Object> record` with `record.value() instanceof X` checks.

### Local environment / infrastructure

- **Docker/Postgres credential drift:** `POSTGRES_USER`/`POSTGRES_PASSWORD` env vars only take effect on a volume's *first* initialization — changing them later without `docker compose down -v` leaves the old credentials active, producing password-auth failures that look unrelated to the actual cause. The same class of bug reappears as a **Postgres major-version mismatch**: swapping `postgres:16` → `postgres:17-alpine` in `docker-compose.yml` against volumes still holding `postgres:16`-formatted data files causes an immediate crash-loop (`Restarting (1)`) on every affected container, since Postgres data directories aren't compatible across major versions. Fix in both cases is the same: `docker compose down` + `docker volume rm` the affected volumes, then let the containers reinitialize clean.
- **JVM timezone mismatch:** a JDK/Postgres timezone parameter mismatch (`Asia/Calcutta` vs. `Asia/Kolkata`) can manifest as a misleading Hibernate dialect-detection error; fixed both via JVM flag (`-Duser.timezone=UTC`) and, more robustly, via `hibernate.jdbc.time_zone: UTC` in `application.yml` so it doesn't depend on any particular run configuration.
- **`.sh` scripts with CRLF line endings:** a shell script (`kafka-init/create-topics.sh`) edited/saved on Windows picked up `\r\n` line endings, producing `$'\r': command not found` and syntax errors when run via Git Bash. Fixed by converting to LF and adding `*.sh text eol=lf` to `.gitattributes` so it can't regress for any future contributor on Windows.

### Docker multi-stage build (Days 22–24)

- **`mvn: not found` in the builder stage:** `eclipse-temurin:*-jdk-alpine` provides a JDK but not Maven. The builder stage must use a dedicated `maven:3.9-eclipse-temurin-21-alpine` image; the runtime stage correctly stays on plain `eclipse-temurin:21-jre-alpine`.
- **Multi-module reactor POM parsing fails inside Docker:** copying only the target module's `pom.xml` into the build context isn't enough — the parent `pom.xml` declares `<module>` entries for all seven modules, so Maven fails to parse the project (`Child module ... does not exist`) unless every module's `pom.xml` is copied in, plus `common-events`' full source (since it's an actual compile-time dependency built via `-am`, not just a POM that needs to exist).
- **`no main manifest attribute, in app.jar`:** since the project uses a custom parent POM rather than `spring-boot-starter-parent`, `spring-boot-maven-plugin`'s `repackage` goal is not auto-bound to the `package` phase — it must be declared explicitly with an `<executions><execution><goals><goal>repackage</goal></goals></execution></executions>` block in every service's `pom.xml`, or the build produces a plain "thin" jar with no runnable manifest. The presence of a `*.jar.original` file alongside the main jar in `target/` is the tell that repackaging genuinely ran.
- **Wrong jar shipped in five of six Dockerfiles:** all six Dockerfiles were authored from one working template (`catalog-service/Dockerfile`), but five of them were never updated to `COPY`/build their *own* module's source and jar — every one of those five images silently built and ran `catalog-service`'s jar instead of their own. This didn't fail silently: each affected container crash-looped at startup, since the wrong jar's app fell back to `catalog-service`'s own DB connection defaults (e.g. `localhost:5433`), which don't resolve to anything inside a differently-named container. The tell was in the stack trace itself — `com.ticketing.catalog.CatalogServiceApplication.main` appearing in, say, the `inventory-service` container's logs — not the container's health status. **Lesson: when a container's exception trace names a class from a different service than the container itself, check which jar actually got built into the image before debugging the exception on its face value.**
- **Host-mapped port vs. container-internal port confusion, repeated across four services:** every application service's `*_DB_URL` in `docker-compose.yml` (`CATALOG_DB_URL`, `INVENTORY_DB_URL`, `AUTH_DB_URL`, `BOOKING_DB_URL`) was initially set to the **host-mapped** Postgres port (`5433`/`5434`/`5435`/`5436` — the port used by `localhost` from outside Docker) instead of the container's actual internal listening port, which is always `5432` regardless of external mapping. Container-to-container traffic on the same Docker network never goes through the host port mapping at all; it addresses the target container by name on its real internal port. This surfaced as `Connection to <container-name>:<wrong-port> refused` and had to be fixed in all four places identically.
- **Stale/cached container state masking a fix:** after correcting `docker-compose.yml`, `docker compose up -d` alone sometimes reused an already-created container rather than picking up the new environment variable, making a genuine fix appear not to work. `docker compose down` followed by `docker compose up -d --force-recreate` (and `--build` when source/image changes are also involved) removes any ambiguity.
- **Schema drift caught by Flyway's `validate` mode:** an entity field (`Seat.bookingId`) had no corresponding column in the existing Flyway migration, surfacing as a clean `SchemaManagementException: missing table/column` at startup rather than a silent auto-alter. Fixed with an additive `V2__add_booking_id_to_seats.sql` migration — this is the intended failure mode Flyway's `ddl-auto: validate` is designed to produce, versus the old `ddl-auto: update` behavior which would have made the change silently with no audit trail.

### Git / secrets

- **Secret leaked into a committed config file:** a real SMTP API key was briefly committed to an `application.yaml`. GitHub's push protection blocked the push before it reached the remote. Remediated via `git rebase -i` (marking the offending commit for `edit`, amending the file, continuing the rebase) followed by rotating the exposed key regardless, since local commit history had already contained the plaintext value.
- **IntelliJ Git askpass timeout under system load:** with the full Docker stack (12+ containers, several JVMs) running simultaneously, `git push` occasionally failed with `HttpConnectTimeoutException` from IntelliJ's internal askpass helper (used to relay the credential prompt), ultimately surfacing as `fatal: could not read Username for 'https://github.com'`. Transient — resolved by retrying, or falling back to a Personal Access Token embedded directly in the remote URL when it recurred.

---

## 17. Known Limitations & Future Work

- **No Kubernetes deployment yet** — the project currently runs via Docker Compose only; a Minikube-based deployment (namespaces, ConfigMaps/Secrets, PVC-backed infra, HPA, Ingress) is planned but not started.
- **No observability stack yet** — structured/correlated logging, metrics (Micrometer/Prometheus/Grafana), and distributed tracing (Zipkin) are planned but not implemented; currently only default Spring Boot Actuator health endpoints are available per service.
- **No refund automation** — a payment that succeeds after its seat hold has already expired currently only logs a warning; a production system would trigger an automatic Razorpay refund.
- **`common-events` is a shared compile-time Maven module**, coupling producer/consumer schemas. A schema registry (Avro/Protobuf + Confluent Schema Registry) is the production-grade alternative.
- **Single-broker Kafka, single-instance Postgres/Redis** — no replication factor above 1 anywhere; acceptable for local/demo, not for production durability.
- **`payment-service` has no persistent order store** — Redis (with TTL) is the sole source of truth for `razorpayOrderId → bookingId` mapping; a production system would likely also persist this for audit/reconciliation.
- **No live seat-map updates** — the frontend's seat selection screen is a fetch-once snapshot; a real-time system would use WebSockets or polling to reflect concurrent holds.
- **Service-to-service calls are unauthenticated internally**, relying on network-level isolation rather than mTLS or a service mesh — a reasonable trade-off at this scale.
