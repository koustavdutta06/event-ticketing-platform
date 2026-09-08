# Startup guide — Minikube, transactions, and the observability stack

Quick-reference companion to [`README.md`](./README.md) (full explanation of what
each manifest does) and [`TROUBLESHOOTING.md`](./TROUBLESHOOTING.md) (what already
broke once and why). This file is just the commands, in order.

## 1. Fresh start (cluster was deleted)

```powershell
minikube start --driver=docker --memory=9216 --cpus=4
minikube addons enable ingress
minikube addons enable metrics-server

# Namespace must exist before secrets can be applied — on a brand-new cluster
# there's nothing to apply -k against yet, so create it standalone first.
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/02-secrets.local.yaml

# Builds all 6 app images into Minikube's own Docker daemon (~2-3 min)
.\k8s\build-images.ps1

# Namespace, configmaps, Postgres x4, Redis, Kafka, the 6 app services,
# Prometheus, Grafana, Zipkin, Logstash, Kafka Exporter, HPAs, both Ingresses
kubectl apply -k k8s/

kubectl -n ticketing get pods -w
```

Wait for all 18 pods to reach `1/1 Running` — infra and observability pods
first, then the 6 app services once their init containers clear (they wait on
Postgres/Kafka/upstream-service health).

## 2. Resuming after `minikube stop` (cluster still exists)

```powershell
minikube start --driver=docker --memory=9216 --cpus=4
kubectl -n ticketing get pods -w
```

Normally nothing else is needed — images, manifests, and PVC data are all
still there. **But** if the host machine slept/restarted uncleanly rather
than going through an explicit `minikube stop` (see `TROUBLESHOOTING.md`
#16), Minikube's node can come back with most pods showing low restart
counts (1-2) simultaneously — the tell that the whole node bounced, not just
one container. When you see that pattern, Kafka's topics are the one thing
that doesn't self-heal, so verify them explicitly:

```powershell
# Should list seat-events, payment-events, booking-events, notification-events
# (plus __consumer_offsets). If it's just __consumer_offsets, the topics are gone.
kubectl -n ticketing exec kafka-0 -- /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092
```

If they're missing, recreate them — the topics-init script is idempotent
(`--create --if-not-exists`), but it's a Kubernetes `Job` so it won't
re-trigger itself; delete and reapply it:

```powershell
kubectl -n ticketing delete job kafka-topics-init
kubectl apply -f k8s/16-kafka-topics-job.yaml -n ticketing
kubectl -n ticketing logs job/kafka-topics-init -f
```

You'll know things were actually broken (rather than just still settling) if
a consumer service's logs show `UNKNOWN_TOPIC_OR_PARTITION`:
```powershell
kubectl -n ticketing logs deploy/booking-service --tail=20 | Select-String "kafka"
```

## 3. Port-forwarding reference

Run whichever of these you need, each in its own terminal (or background
with `&`). Local ports for the databases match the existing `.env`/
`docker-compose.yml` conventions, so any local DB client / tool already
configured against those ports works unmodified.

### App services (ports match the Postman collection's hardcoded URLs)
```powershell
kubectl -n ticketing port-forward svc/auth-service 8085:8085
kubectl -n ticketing port-forward svc/catalog-service 8081:8081
kubectl -n ticketing port-forward svc/inventory-service 8082:8082
kubectl -n ticketing port-forward svc/booking-service 8083:8083
kubectl -n ticketing port-forward svc/payment-service 8086:8086
kubectl -n ticketing port-forward svc/notification-service 8084:8084
```

### Databases, Redis, Kafka
```powershell
kubectl -n ticketing port-forward svc/catalog-postgres 5433:5432
kubectl -n ticketing port-forward svc/inventory-postgres 5434:5432
kubectl -n ticketing port-forward svc/booking-postgres 5435:5432
kubectl -n ticketing port-forward svc/auth-postgres 5436:5432
kubectl -n ticketing port-forward svc/redis 6379:6379

# kafka's Service is headless (clusterIP: None) for the StatefulSet's stable
# DNS name — port-forward the pod directly if `svc/kafka` doesn't work:
kubectl -n ticketing port-forward svc/kafka 9092:9092
# fallback: kubectl -n ticketing port-forward pod/kafka-0 9092:9092
```
Connect with the same credentials as `k8s/02-secrets.local.yaml` /
`.env` (`catalog_user`/`catalog_pass` on `catalog_db`, etc.), e.g.:
```powershell
psql -h localhost -p 5433 -U catalog_user -d catalog_db
```

### Observability stack
```powershell
kubectl -n ticketing port-forward svc/zipkin 9411:9411
kubectl -n ticketing port-forward svc/prometheus 9090:9090
kubectl -n ticketing port-forward svc/grafana 3000:3000
```
(Logstash and Kafka Exporter are internal-only — no UI to port-forward to;
read them via `kubectl logs` instead, see §5.)

### Or: Ingress instead of port-forwarding
Add the hostnames from `README.md` §5 to your hosts file and skip
port-forwarding entirely for anything with a browser UI (all six app
services' Swagger UIs, Grafana, Prometheus, Zipkin). The Postman collection
itself still needs the port-forwards above, since its requests are hardcoded
to `localhost:808x`.

## 4. Do transactions (Postman)

With the 5 app-service port-forwards from §3 running:

1. **Auth Service → Register** (or **Login** if already registered) — JWT is
   auto-captured into collection variables by the request's test script.
2. **Admin - Batch Import → Import venues + events (CSV)**, then
   **Import seats (CSV)** — fastest way to seed real data from
   `sample-data/`. **Gotcha** (see `TROUBLESHOOTING.md` #8): after importing
   the collection into Postman, you must manually re-select each CSV file in
   the request's Body tab once — Postman strips file attachments from
   imported collections, and an empty attachment causes a `415`.
   - Manual alternative: **Catalog Service → Add venue** → **Create Event** →
     **Change Event status** (to `PUBLISHED`) → **Inventory Service → Add
     seats**.
3. **Catalog Service → Get published events** — grab an `eventId`.
4. **Inventory Service → Get Seats for an event** — grab a `seatId`.
5. **Booking Service → Book seat** — the interesting one: fans out to
   catalog/inventory, publishes `seat-events` to Kafka, and is the best
   single request to watch across Zipkin/Grafana/Kafka lag.
6. **Booking Service → Create Payment Order**.

(Real payment confirmation needs the Razorpay webhook via an ngrok tunnel —
token/URL already noted in `Important_Commands_for_project.txt`. Not needed
just to exercise the observability stack.)

## 5. Watch it: Zipkin, Grafana, Prometheus, logs

With the observability port-forwards from §3 running:

**Zipkin — `http://localhost:9411`**
Every app response carries an `X-Trace-Id` header — paste it into Zipkin's
search box, or hit "Run Query" with no filter to browse recent traces. Open
one to see the full span waterfall across services (and the Kafka
producer/consumer hop, if the request published an event).

**Grafana — `http://localhost:3000`**
Log in `admin` / the `GRAFANA_ADMIN_PASSWORD` value in
`k8s/02-secrets.local.yaml`.
- *Dashboards → Service Overview* — pick a service from the dropdown: request
  rate, p95 latency, 5xx rate, JVM heap/threads/GC, CPU.
- *Dashboards → Kafka Consumer Lag* — watch lag move per group/topic/
  partition live as you book seats.
- *Explore* (left nav) → switch datasource to Zipkin to search traces from
  inside Grafana too.

**Prometheus — `http://localhost:9090`**
*Graph* tab — try `http_server_requests_seconds_count`,
`kafka_consumergroup_lag`, `jvm_memory_used_bytes`. *Alerts* tab shows
`KafkaConsumerLagHigh` / `ServiceDown` state live. *Status → Targets* shows
scrape health for all 6 services + `kafka-exporter` + `zipkin`.

**Logs**
```powershell
kubectl -n ticketing logs deploy/logstash -f
```
Every line is JSON with a `traceId` field — grep for a trace ID from a
response header or Zipkin to see that exact request's log trail across the
services it touched:
```powershell
kubectl -n ticketing logs deploy/logstash -f | Select-String "<traceId>"
```
Or watch one service's own console output directly:
```powershell
kubectl -n ticketing logs deploy/booking-service -f
```

## 6. Shutting down

```powershell
# Preserves the cluster, images, and PVC data — `minikube start` resumes
# everything in under a minute, no rebuilding.
minikube stop

# Wipes the VM entirely — cluster, images, PVC data all gone. Next start
# needs the full "Fresh start" sequence in §1 again (~5-10 min).
minikube delete
```
