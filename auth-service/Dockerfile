# auth-service/Dockerfile
# ─────────────────────────────────────────────
# Stage 1: Build
# ─────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy parent POM first — Docker caches this layer separately.
# If only source code changes (not POMs), Maven dependency
# download is skipped entirely on rebuild. Significant time saver.
COPY pom.xml .
COPY common-events/pom.xml common-events/
COPY catalog-service/pom.xml catalog-service/
COPY inventory-service/pom.xml inventory-service/
COPY booking-service/pom.xml booking-service/
COPY notification-service/pom.xml notification-service/
COPY auth-service/pom.xml auth-service/
COPY payment-service/pom.xml payment-service/

# common-events is a REAL compile-time dependency of catalog-service (if catalog-service
# uses it) or at minimum needs its own source to be buildable as part of the reactor —
# copy its source now too, since -am (also-make) will try to build it as a dependency.
COPY common-events/src common-events/src

# Download dependencies before copying source —
# this layer is cached as long as pom.xml files don't change
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -pl auth-service -am -q

# Now copy source and build
COPY auth-service/src auth-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -pl auth-service -am -DskipTests -q

# ─────────────────────────────────────────────
# Stage 2: Runtime
# ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Never run as root inside a container
# Create a dedicated non-root user for the app
RUN addgroup -S ticketing && adduser -S ticketing -G ticketing

WORKDIR /app

# Copy only the fat JAR from the builder stage
COPY --from=builder /build/auth-service/target/*.jar app.jar

# Set file ownership to non-root user
RUN chown ticketing:ticketing app.jar

# Switch to non-root user before running
USER ticketing

# Document which port the service listens on
EXPOSE 8085

# JVM tuning for containers:
# -XX:+UseContainerSupport  → JVM respects container CPU/memory limits (not host machine)
# -XX:MaxRAMPercentage=75.0 → use up to 75% of container's allocated memory for heap
# -Duser.timezone=UTC       → consistent timezone (matches your application.yml fix)
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Duser.timezone=UTC", \
    "-jar", "app.jar"]