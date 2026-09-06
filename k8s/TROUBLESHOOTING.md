# Kubernetes setup — build log & troubleshooting history

This is a record of what was actually built to run this platform on Minikube,
and every problem hit along the way with its root cause and fix. For "how do
I run this," see `k8s/README.md` — this file is the "why does it look like
this" and "what already went wrong once" reference.

## What was built

- `00-namespace.yaml` — the `ticketing` namespace, everything lives here.
- `01-configmap.yaml` — non-secret config: DB URLs, Kafka bootstrap address,
  Redis host/port, inter-service URLs, SMTP/Razorpay non-secret settings.
- `02-secrets.yaml` — **placeholder template** (base64 of `CHANGE_ME`), safe
  to commit. **Not** part of `kustomization.yaml` on purpose (see Problem 5).
- `02-secrets.local.yaml` — the real credentials, generated from `.env`,
  gitignored (`k8s/*.local.yaml`). Applied as its own separate step.
- `10`–`13-*-postgres.yaml` — one Deployment + PVC + Service per DB
  (catalog, inventory, booking, auth), mirroring `docker-compose.yml`.
- `14-redis.yaml` — single Redis instance + PVC.
- `15-kafka.yaml` — single-node KRaft Kafka broker as a StatefulSet, with a
  **headless** Service (see Problem 4 — this took several iterations).
- `16-kafka-topics-job.yaml` — a Job that waits for Kafka and creates the
  four topics (`seat-events`, `payment-events`, `booking-events`,
  `notification-events`), replacing the old `kafka-init/create-topics.sh`
  docker-exec script.
- `20`–`25-*-service.yaml` — the six Spring Boot services, each with
  `initContainers` that wait on their real dependencies (Postgres, Kafka,
  upstream services) before the main container starts, plus
  startup/readiness/liveness probes against `/actuator/health`.
- `30-hpa.yaml` — CPU-based HPA (min 1 / max 3, 70% target) on all six app
  services.
- `40-ingress.yaml` — one hostname per service (`catalog.ticketing.local`,
  etc.) via the `ingress` addon.
- `build-images.ps1` — builds all six images directly into Minikube's own
  Docker daemon (no registry needed).

## Key decisions made along the way

- **Ingress**: per-service hostnames, not a single host with path routing —
  chosen before confirming actual controller paths; later verified the
  actual `@RequestMapping` prefixes (`/api/v1/events`, `/api/v1/bookings`,
  etc.) would also have supported path-based routing on one host, but
  hostnames were already in place and work fine.
- **Secrets**: committed placeholder template + gitignored local file with
  real values, rather than fully imperative `kubectl create secret`
  commands — keeps the expected keys documented and reviewable in git while
  keeping real credentials out of it.
- **HPA scope**: all six stateless app services, not just the hot-path ones
  — Postgres/Redis/Kafka stay single-replica since they're stateful.

## Problems encountered, in order, with root cause and fix

### 1. Kafka crash-looping — `kafka-topics.sh` probe too expensive
**Symptom**: `kafka-0` restarting repeatedly; events showed liveness/readiness
probe timeouts.
**Root cause**: the readiness/liveness probes ran
`kafka-topics.sh --list --bootstrap-server localhost:9092`, which spins up a
brand-new JVM on every single probe call. Under Kafka's original 500m CPU
limit, that JVM startup routinely blew past the probe's timeout, and a failed
**liveness** probe kills the container outright.
**Fix**: switched the liveness probe to a cheap `tcpSocket` check on port
9092 (no JVM spawn), kept the exec-based readiness probe but with a longer
timeout/period, and raised Kafka's CPU limit (400m→1000m).

### 2. Rolling updates doubled resource usage and starved new pods
**Symptom**: after any manifest change, both the old and new pod for a
service existed simultaneously, node CPU requests spiked to ~90%, and new
pods took even longer to start (or never did).
**Root cause**: default Deployment `RollingUpdate` strategy creates the new
pod *before* removing the old one (to stay zero-downtime). With `replicas: 1`
per service and only 3 CPUs on the node, having two generations of the same
pod alive at once left too little CPU for either to finish starting.
**Fix**: added `strategy: { type: Recreate }` to all six app Deployments —
acceptable since this is a single-replica dev/local setup with no
zero-downtime requirement; old pod is killed before the new one starts.

### 3. CPU **limits** (not contention) throttling JVM startup to 100–240s
**Symptom**: `auth-service` (and others) killed by their `startupProbe`
despite the application log showing it was about to finish starting; one
instance logged "Started AuthServiceApplication in 106.919 seconds."
**Root cause**: a container's CPU *limit* is a hard cgroup CFS quota,
enforced regardless of whether the rest of the node is busy. The original
limits (250m for auth/payment/notification, 500m for catalog/inventory/
booking) were simply too little compute for a Spring Boot + Hibernate +
Flyway startup, independent of node contention.
**Fix**: raised CPU limits (catalog/inventory/booking → 750m,
auth/payment/notification → 600m) without touching CPU *requests* (so
scheduling headroom was unaffected), and widened `startupProbe.
failureThreshold` to give a ~300s startup budget.

### 4. Kafka readiness still timing out — hairpin NAT via its own Service
**Symptom**: even after fixes #1 and #3, `kafka-topics.sh --bootstrap-server
localhost:9092` (run manually via `kubectl exec`) hung for over a minute and
failed with `Connection to node 1 (kafka/<ClusterIP>:9092) could not be
established`.
**Root cause**: `KAFKA_ADVERTISED_LISTENERS` was set to the ClusterIP
Service name (`kafka:9092`). A Kafka client connects to the bootstrap
address, then the broker tells it "the real address for broker 1 is X," and
the client reconnects to X. Here, X was the broker's *own* Service VIP — and
a pod connecting to a Service VIP that routes back to itself is a
hairpin-NAT self-connection, which this cluster's CNI doesn't support.
Any pod other than kafka-0 itself would have been fine; it was specifically
kafka-0 talking to kafka-0 through the Service that broke.
**Fix** (the standard StatefulSet-for-Kafka pattern):
- Made the `kafka` Service **headless** (`clusterIP: None`), giving the pod
  a stable per-pod DNS name: `kafka-0.kafka.ticketing.svc.cluster.local`.
- Set `KAFKA_ADVERTISED_LISTENERS` to that per-pod name instead of the
  Service name.
- Added `publishNotReadyAddresses: true` on the Service — without it, DNS
  for `kafka-0` is only published once `kafka-0` is Ready, but its own
  readiness probe needs to resolve that very name, which is a deadlock.
  This is a documented Kubernetes StatefulSet pattern for exactly this case.
- Simplified from two listeners (`PLAINTEXT` + `PLAINTEXT_INTERNAL`, a
  leftover from Compose's host-vs-container-network split, which doesn't
  exist in Kubernetes) down to one.
- Updated `KAFKA_BOOTSTRAP_SERVERS` in the ConfigMap, the `nc -z ...`
  init-container checks in all app manifests, and the topics-init Job to
  all point at `kafka-0.kafka.ticketing.svc.cluster.local:9092`.

### 5. Editing the Secret template clobbered the real secrets
**Symptom**: after converting `02-secrets.yaml` from `stringData` to base64
`data:` and re-running `kubectl apply -k k8s/`, `catalog-service` and
`auth-service` started crash-looping with `FATAL: password authentication
failed`.
**Root cause**: `02-secrets.yaml` (the `CHANGE_ME` placeholder template) was
listed in `kustomization.yaml`'s `resources:`. Any `kubectl apply -k k8s/`
therefore re-applied the placeholders over whatever real values had been set
via `02-secrets.local.yaml`.
**Fix**: reapplied `02-secrets.local.yaml` to restore real credentials, and
— to make sure this can never happen again — **removed `02-secrets.yaml`
from `kustomization.yaml` entirely**. Secrets are now always a deliberate,
separate `kubectl apply -f` step, documented in `k8s/README.md`.

### 6. Restoring the Secret didn't actually fix the password
**Symptom**: same `password authentication failed` error, even after
confirming the Secret object had the correct real password.
**Root cause**: Postgres only applies the `POSTGRES_PASSWORD` env var when
it initializes a **brand-new, empty** data directory. Since the placeholder
secret was briefly live during initial bring-up, `catalog-postgres` (etc.)
had already initialized its PVC with the role password baked in as
`CHANGE_ME`. Updating the Kubernetes Secret afterward doesn't retroactively
change a password Postgres already committed to disk.
**Fix**: rather than wiping the PVCs (which would have lost all seeded
data), ran `ALTER USER <user> WITH PASSWORD '<real password>'` via
`kubectl exec <pod> -- psql -U <user> -d <db> -c "..."` against all four
Postgres pods, matching each role's password to what's in
`02-secrets.local.yaml`.
**Takeaway for next time**: rotating a DB password later needs the same
`ALTER USER` treatment (or a full PVC wipe) — just re-applying the Secret is
not enough once the volume has been initialized once.

### 7. `notification-service` crash-looping despite starting successfully
**Symptom**: logs clearly showed `Started NotificationServiceApplication in
25.464 seconds` and Tomcat/DispatcherServlet initializing — yet the pod kept
getting killed by its startup probe with `context deadline exceeded`.
**Root cause**: none of the six services set `timeoutSeconds` on their
`httpGet` probes, so it defaulted to Kubernetes' built-in **1 second**. Under
this VM's CPU limits, with notification-service's three concurrent Kafka
consumer-group listener containers all rebalancing at once, `/actuator/health`
occasionally took just over 1 second to respond — a real, working response,
just slightly late — and the probe treated that as a hard failure.
**Fix**: added `timeoutSeconds: 5` to every startup/readiness/liveness
`httpGet` probe across all six app manifests. This was a systemic gap, not
notification-service-specific — it just happened to be the one that hit it
first and consistently.

### 8. `415 Unsupported Media Type` on the Postman batch-import requests
**Symptom**: `POST /api/v1/admin/imports/venues-events` returned 415 from
Postman even though the collection's request body looked correctly
configured as `multipart/form-data`.
**Root cause**: Postman does not trust local file paths embedded in an
*imported* collection JSON (a deliberate security behavior) — it clears the
file attachment on import, leaving an empty "select a file" placeholder. With
no file actually attached, Postman doesn't send a proper multipart
boundary, and Spring's `consumes = "multipart/form-data"` rejects it.
**Fix**: manually re-select each file in Postman's Body tab (`venuesFile`,
`eventsFile`, `seatsFile`) pointing at the CSVs under `sample-data/` — this
has to be done once per machine/Postman install after importing the
collection; it can't be fixed from the collection JSON itself.

## Known issue found, not yet fixed

`BookingService.initiateBooking` reads the customer's email out of the JWT
principal, then **ignores it** and hardcodes a literal email address onto
every `Booking` it creates — so every booking is attributed to that same
email regardless of which account made it. Found while tracing the booking
flow for API testing; not touched since it's an application-code bug, not a
Kubernetes config issue.

## Final verified state

All pods `1/1 Running` (4x Postgres, Redis, Kafka, 6 app services), the
`kafka-topics-init` Job `Completed`, all 6 HPAs settled to 1 replica at low
CPU, all 4 Kafka topics present, and all six services returning `HTTP 200`
on `/actuator/health` — confirmed via a throwaway `curl` pod hitting each
service's ClusterIP DNS name directly.

The Postman collection (`event-ticket-system.postman_collection.json`) was
also reworked to auto-chain requests (JWT and IDs captured into collection
variables via test scripts) instead of relying on stale hardcoded values —
see the collection itself for the current request order.
