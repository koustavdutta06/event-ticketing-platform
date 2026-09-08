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

## Observability stack (Prometheus, Grafana, Zipkin, Logstash, Kafka lag) — build log & troubleshooting

Added later, on top of the already-working platform above: `50-observability-
configmap.yaml`, `51-prometheus.yaml`, `52-grafana.yaml`, `53-zipkin.yaml`,
`54-observability-ingress.yaml`, plus two more not in the original ask but
required for the stack to actually function — `55-logstash.yaml` (the log
pipeline itself) and `56-kafka-exporter.yaml` (the actual source of Kafka
consumer-lag metrics). App-side: each service gained `micrometer-registry-
prometheus`, `spring-boot-starter-zipkin`, `logstash-logback-encoder`, a
per-service `logback-spring.xml`, and `management.tracing`/`management.zipkin`
config. Logstash has **no storage backend by design** — it enriches incoming
JSON logs and writes to stdout only, no Elasticsearch/Kibana, to stay light on
the Minikube VM.

This platform runs **Spring Boot 4.0.7**, a major version released after most
of the internet's Spring Boot 3.x tracing/metrics tutorials and Stack Overflow
answers were written. Every problem below has the same underlying shape: Boot
4 split what used to be one auto-configured module in Boot 3.x into several
much smaller, independently-added modules, and the classic Boot 3.x recipes
silently do less than they used to instead of failing to compile.

### 9. `Tracer` bean not found — `micrometer-tracing-bridge-brave` alone isn't enough anymore
**Symptom**: every service with the new `TraceIdHeaderFilter` failed to start:
`Parameter 0 of constructor ... required a bean of type
'io.micrometer.tracing.Tracer' that could not be found.`
**Root cause**: in Boot 3.x, adding `micrometer-tracing-bridge-brave` +
`zipkin-reporter-brave` as plain dependencies was the whole recipe — Boot's
own actuator-autoconfigure module wired the `Tracer` bean from there. In Boot
4.0.7, `spring-boot-actuator-autoconfigure` has **no `tracing` package at
all** anymore (confirmed by extracting the jar — metrics and tracing
autoconfiguration moved out into their own dedicated modules,
`spring-boot-micrometer-metrics` and `spring-boot-micrometer-tracing-brave`).
Metrics still worked because `spring-boot-starter-actuator` transitively pulls
in `spring-boot-starter-micrometer-metrics` automatically; nothing pulls in
the tracing equivalent automatically.
**Fix**: replaced the manual `micrometer-tracing-bridge-brave` +
`zipkin-reporter-brave` dependency pair with the single
`org.springframework.boot:spring-boot-starter-zipkin` starter in all 6
service POMs — this is Boot 4's bundle that pulls in
`spring-boot-micrometer-tracing-brave` (the actual autoconfiguration glue)
alongside the same Micrometer/Zipkin libraries.

### 10. `WebClient.Builder` bean not found in booking-service
**Symptom**: after fixing #9 and separately fixing `WebClientConfig` to
inject `WebClient.Builder` instead of calling `WebClient.builder()` directly
(needed so outbound calls carry trace headers), booking-service failed to
start: `Parameter 0 of method catalogWebClient ... required a bean of type
'org.springframework.web.reactive.function.client.WebClient$Builder' that
could not be found.`
**Root cause**: same pattern as #9 — `spring-boot-starter-webflux` in Boot 4
only pulls in `spring-boot-webflux` (the reactive **server** side). The
`WebClient.Builder` autoconfiguration now lives in a separate
`spring-boot-webclient` module, activated via its own
`spring-boot-starter-webclient`. This gap existed from day one but was
invisible before, because the original code called the static
`WebClient.builder()` factory method and never asked Spring for the
autoconfigured builder at all.
**Fix**: added `org.springframework.boot:spring-boot-starter-webclient` to
booking-service's POM.

### 11. Prometheus couldn't scrape `auth-service` (403) or `booking-service` (401)
**Symptom**: Prometheus's `/targets` page showed 6 app targets, but
`auth-service` was `down` with `403 Forbidden` and `booking-service` was
`down` with `401 Unauthorized`; the other 4 services scraped fine.
**Root cause**: both services' Spring Security configs explicitly permitted
only `/actuator/health` and `/actuator/info`, falling back to
`.anyRequest().authenticated()` for everything else — including the new
`/actuator/prometheus` endpoint. (`payment-service` also has Spring Security
but its fallback is `.anyRequest().permitAll()`, so it was unaffected.)
**Fix**: added `/actuator/prometheus` alongside `/actuator/health` and
`/actuator/info` in both `SecurityConfig` classes' permit-list.

### 12. Zipkin spans silently dropped — `ClosedChannelException` on every send
**Symptom**: `Tracer.currentSpan()` worked fine (the `X-Trace-Id` response
header was populated correctly), but *nothing* ever showed up in Zipkin —
`GET /api/v2/traces` always returned `[]`, even though a manual `curl POST` of
a span straight to Zipkin's `/api/v2/spans` worked and was queryable
immediately. A one-time `WARN` at each pod's startup
(`z.r.i.AsyncReporter$BoundedAsyncReporter : Dropped N spans due to
ConnectException()`) turned out to repeat on every single flush — invisible
after the first occurrence because the reporter logs subsequent identical
failures at `FINE` level only. Turning that logger up to `DEBUG` (via
`kubectl set env`, no rebuild needed) confirmed it: every attempt failed with
`java.nio.channels.ClosedChannelException` at `SocketChannelImpl.beginConnect`
— the socket was closed *before* `connect()` was even called.
**Root cause**: Boot 4.0.7's `spring-boot-zipkin` module
(`ZipkinAutoConfiguration$ZipkinHttpClientConfiguration`) replaced the
classic `URLConnectionSender` with a new sender built on Java's
`java.net.http.HttpClient`. That JDK HttpClient-based sender reproducibly hits
`ClosedChannelException` against Zipkin's Armeria server in this environment,
even though the exact same endpoint is reachable via `wget`/`curl` from
inside the same pod and via a manual span POST from outside the cluster. This
looks like a genuine bug/incompatibility in Boot 4.0.7's new sender, not a
network or config problem.
**Fix**: defined a `zipkin2.reporter.Sender` bean per service using the
classic `zipkin2.reporter.urlconnection.URLConnectionSender` (added
`io.zipkin.reporter2:zipkin-sender-urlconnection` as a dependency). Boot's
`httpClientSender` bean is guarded by
`@ConditionalOnMissingBean(BytesMessageSender.class)` — confirmed by
decompiling the class file's annotations — and `URLConnectionSender`'s type
hierarchy (`SenderAdapter extends Sender implements BytesMessageSender`)
satisfies that check, so defining this bean makes Boot back off from its own
broken sender entirely.

### 13. `traceId`/`spanId` never appeared in logs (console pattern or JSON)
**Symptom**: even after tracing worked end-to-end (spans reaching Zipkin), no
log line — console or the JSON shipped to Logstash — ever carried a
`traceId`/`spanId`. The console pattern showed an empty correlation slot:
`INFO 1 --- [auth-service] [ main] [                    ] c.t.a.AuthServiceApplication : ...`
(that bracketed blank is where `[traceId,spanId]` belongs).
**Root cause**: in Boot 3.x, `BraveAutoConfiguration` wired an
`MDCScopeDecorator` into the `CurrentTraceContext` automatically. Decompiling
Boot 4.0.7's `spring-boot-micrometer-tracing-brave` module showed its
`braveCurrentTraceContext` bean method accepts a
`List<CurrentTraceContext.ScopeDecorator>` — i.e. it's an extension point —
but nothing in that module actually **supplies** an `MDCScopeDecorator` bean
into that list anymore. The class needed to build one
(`brave.context.slf4j.MDCScopeDecorator`) was present on the classpath the
whole time (`brave-context-slf4j`, pulled in transitively at `runtime` scope
by `micrometer-tracing-bridge-brave`) — it just wasn't being registered as a
bean by anything.
**Fix**: added `io.zipkin.brave:brave-context-slf4j` at `compile` scope
(needed to reference the class directly) and a one-line
`@Bean CurrentTraceContext.ScopeDecorator mdcScopeDecorator() { return
MDCScopeDecorator.get(); }` to each service. Verified by hitting an endpoint
with a real `log.info(...)` call and confirming both the console bracket and
the `traceId` field in the Logstash JSON populated with the exact same trace
ID shown in Zipkin.

### Operational gotchas hit while deploying this (not code bugs)
- **Fresh cluster, secrets before namespace exists**: `kubectl apply -f
  k8s/02-secrets.local.yaml` on a brand-new cluster fails with `namespaces
  "ticketing" not found` — the namespace only gets created as part of
  `kubectl apply -k k8s/`. On a first-ever setup, apply
  `k8s/00-namespace.yaml` by itself first.
- **Building an image without pointing at Minikube's Docker daemon**: running
  `docker build` directly (instead of through `k8s/build-images.ps1`, which
  runs `minikube docker-env | Invoke-Expression` first) builds the image into
  the **host's** Docker Desktop daemon. The pod then can't find it under
  `imagePullPolicy: IfNotPresent` inside Minikube. Always rebuild through the
  script, not a bare `docker build`.
- **A single init container stuck `running` indefinitely despite its own log
  showing success**: one `booking-service` pod's `wait-for-postgres` init
  container printed `booking-postgres:5432 - accepting connections` (a
  successful `pg_isready`) and then never exited for 9+ minutes — every other
  pod's identical init-container logic worked normally. Never
  root-caused (no config difference from the working services); resolved by
  `kubectl delete pod` and letting the Deployment recreate it, which worked
  first try. Treat as a one-off kubelet/containerd fluke, not a manifest bug
  — but worth knowing the fix if it happens again.

### 14. HPA scaled up on JVM startup CPU bursts, extra replicas failed to schedule/start
**Symptom**: on a fresh `kubectl apply -k k8s/`, several `catalog-service`/
`booking-service`/`payment-service` pods showed `Error` and disappeared
within a few minutes of each other, replaced by differently-named pods —
looked alarming in `kubectl get pods -w`, but the single pod that mattered
for each service was `1/1 Running` with 0 restarts throughout.
**Root cause**: `kubectl get events` told the real story:
`SuccessfulRescale ... New size: 3` on those three HPAs, followed by
`FailedScheduling: 0/1 nodes are available: 1 Insufficient cpu` for some of
the new replicas, `Unhealthy: Startup probe failed: connection refused` for
the ones that did get scheduled, then `ScalingReplicaSet from 3 to 1` a few
minutes later. Spring Boot + Hibernate + Flyway briefly saturate their CPU
*limit* during startup (see Problem #3) — `metrics-server` reads that as
70%+ sustained utilization, and the default `HorizontalPodAutoscaler` has
**no scale-up stabilization window** (`stabilizationWindowSeconds: 0`), so it
reacts to that one-time burst instantly. The extra replicas then had to
compete for CPU with the observability stack's added ~750m request/1Gi
memory footprint on the same 4-CPU node, so some never got scheduled at all
and others were too CPU-starved to pass their startup probe before the HPA
scaled back down and deleted them anyway.
**Fix**: added `behavior.scaleUp.stabilizationWindowSeconds: 120` to all six
HPAs in `30-hpa.yaml` — requires CPU to stay elevated for 2 minutes before
scaling up, which a one-time startup burst never does, while still reacting
normally to real sustained load. **Deployment gotcha hit while applying
this**: `kubectl apply -f k8s/30-hpa.yaml` (bypassing kustomize) creates the
HPAs in whatever namespace your current context defaults to — `30-hpa.yaml`
has no `namespace:` field of its own; that only gets injected by
`kustomization.yaml`'s top-level `namespace: ticketing` when applied via
`kubectl apply -k k8s/`. Applying the bare file created a second, broken set
of HPAs in the `default` namespace (targeting Deployments that don't exist
there, so `TARGETS: <unknown>`) without touching the real ones. Always use
`kubectl apply -k k8s/` (or `-n ticketing` explicitly) for anything in this
directory, never a bare `-f`.

### Final verified state (observability stack)
Prometheus `/targets`: all 9 up (6 app services + `kafka-exporter` + `zipkin`
+ self). A real request through `auth-service`/`catalog-service` produces a
matching `X-Trace-Id` response header, a full span tree in Zipkin, and a
`traceId`-tagged JSON log line on the `logstash` pod's stdout. Both Grafana
dashboards ("Service Overview", "Kafka Consumer Lag") provisioned and
rendering against both datasources (Prometheus, Zipkin). `kafka_consumergroup
_lag` populated for both real consumer groups (`booking-service`,
`notification-service`) across all topics, currently at 0. Both alerting
rules (`KafkaConsumerLagHigh`, `ServiceDown`) loaded and `inactive`.

### 15. Zipkin's trace list dominated by actuator/Prometheus/Security noise, not real requests
**Symptom**: Zipkin's "Find a trace" list was almost entirely `catalog-service: http
get /actuator/health`, `payment-service: http get /actuator/prometheus`, etc. — one
new entry every few seconds from Kubernetes' own liveness/readiness probes (every
5-20s per service) and Prometheus's scrape (every 15s per service), burying real
user-triggered traces.
**Root cause / fix, in three layers** (each layer's fix exposed the next):
1. **The HTTP request itself.** `management.tracing.sampling.probability: 1.0` traces
   *everything*, including infrastructure calls with no business meaning. Fixed with an
   `ObservationPredicate` bean per service that returns `false` for any
   `ServerRequestObservationContext` (MVC: `org.springframework.http.server.observation`;
   WebFlux: `org.springframework.http.server.reactive.observation` — different package
   per style, see Problem #9-13's pattern of MVC/WebFlux needing separate handling)
   whose request path starts with `/actuator`. Suppressing an `Observation` this way
   stops it from creating a span *and* a metric — same fix also cleaned up the
   `http_server_requests_seconds` noise in the Grafana "Service Overview" dashboard.
2. **Spring Security's own instrumentation.** With #1 in place, `auth-service`,
   `booking-service`, and `payment-service` (the three with Spring Security) still
   produced orphaned traces named `secured request`, `authorize request`/
   `authorize exchange`, `security filterchain before`/`after` for every `/actuator/*`
   call — Spring Security auto-instruments its own filter chain/authorization/
   authentication the moment it detects an `ObservationRegistry` bean, independent of
   Boot's own HTTP observation. Worse, its filter-chain context
   (`ObservationFilterChainDecorator$FilterChainObservationContext` /
   `ObservationWebFilterChainDecorator$WebFilterChainObservationContext`) is
   **package-private and carries no request information at all** — there's no path to
   filter on, so the `ObservationPredicate` approach from #1 is structurally
   impossible to apply here. Fixed with the actual supported mechanism: a
   `SecurityObservationSettings.noObservations()` bean in each of the three
   `SecurityConfig` classes, which disables Security's self-instrumentation entirely.
3. **Downstream Redis noise, once its parent was gone.** `catalog-service` and
   `payment-service` (the two with Redis) then showed orphaned `info`, `client`, and
   `hello` traces — the actuator Redis health indicator issues those raw commands on
   every health check, and once its parent HTTP observation was suppressed by #1, they
   had nowhere to nest and surfaced as their own root traces. Rather than an
   ever-growing allowlist of command names, fixed generally: an `ObservationPredicate`
   on `io.lettuce.core.tracing.LettuceObservationContext` that suppresses the command
   *unless* `ObservationRegistry.getCurrentObservation()` is non-null and not itself a
   no-op — i.e., unless it's running inside a real traced request. Real business Redis
   calls always run inside an active HTTP request's observation scope; health-check
   commands never do, so this cleanly separates the two without naming individual
   commands. **Gotcha hit while implementing this**: injecting `ObservationRegistry`
   directly into this bean's factory method creates a circular dependency — Boot's
   `ObservationAutoConfiguration` builds the registry *from* all `ObservationPredicate`
   beans, so a predicate bean that itself depends on the registry is asking for
   something not built yet (`BeanCurrentlyInCreation`). Fixed by injecting
   `ObjectProvider<ObservationRegistry>` instead, which defers the actual lookup from
   bean-construction time to predicate-evaluation time (long after the registry exists).
**Result**: Zipkin's trace list now shows only real traffic — actual HTTP requests and
`inventory-service`'s legitimate recurring `seatExpiryScheduler.releaseExpiredHolds`
job — nothing else.

### 16. Docker Desktop/minikube interruption — Kafka topics gone, Logstash crash-looping
**Symptom** (noticed ~45h into this cluster's life, after a period of the host machine
being asleep/restarted): `kubectl -n ticketing get pods` showed low restart counts
(1-2) on *nearly every* pod simultaneously — a signature of the whole node having
restarted, not an application bug. Two real problems fell out of that:
1. `booking-service`'s Kafka consumer logged a stream of `WARN ... NetworkClient :
   The metadata response from the cluster reported a recoverable issue ...
   {payment-events=UNKNOWN_TOPIC_OR_PARTITION}` (and `seat-events` too).
   `kafka-topics.sh --list` against `kafka-0` confirmed it: only `__consumer_offsets`
   existed — all four application topics were gone.
2. `logstash` showed `23 restarts`, most recent `3s ago` — an active crash loop, not a
   one-time blip.
**Root cause**:
1. Kafka's KRaft log data didn't survive the node restart (whether that's the
   hostpath PVC itself or just Kafka's own metadata reinitializing on that path wasn't
   dug into further — not worth it for a single-node dev broker). `16-kafka-topics-
   -job.yaml` is a Kubernetes `Job`, which runs to completion exactly once and never
   re-triggers on its own — so once the topics it created were gone, nothing was going
   to recreate them without manual intervention.
2. `55-logstash.yaml`'s `readinessProbe`/`livenessProbe` had no accompanying
   `startupProbe` — exactly the same class of bug as Problems #3 and #7: on a
   contended node, Logstash's actual JVM+pipeline startup took **~2 minutes**
   (confirmed by timing the fix's rollout), well past the liveness probe's
   `initialDelaySeconds: 60`. Once that first liveness check fires against a JVM
   that's still booting, kubelet kills it, it restarts, and repeats — a self-sustaining
   loop that doesn't need the node to still be under load to persist, since Logstash's
   *own* startup time already exceeds the probe budget. `kubectl describe pod` showed
   `Unhealthy: Liveness probe failed ... x48 over 45h` — this had been happening
   intermittently for the pod's entire life, not just from this one incident.
**Fix**:
1. `kubectl delete job kafka-topics-init` (Jobs can't be re-triggered in place) then
   re-`apply` the same manifest — the script is already idempotent
   (`--create --if-not-exists`), so this is always safe to rerun.
2. Added a `startupProbe` (`tcpSocket` on 5000, `failureThreshold: 30` ×
   `periodSeconds: 5` = 150s budget) to `55-logstash.yaml`, matching the pattern
   already used on all six app services — gates liveness/readiness off until startup
   genuinely finishes instead of racing it.
**Takeaway for next time**: after any host sleep/restart that takes Minikube down
uncleanly, check `kubectl -n ticketing get pods` for a cluster-wide low-restart-count
pattern as the tell, then specifically verify Kafka's topics are still there
(`kafka-topics.sh --list`) before assuming the stack is healthy just because pods show
`Running`.

### 17. Logstash OOMKilled under real traffic, even after the startupProbe fix (#16)
**Symptom**: after fixing the startup-probe crash loop in #16, `logstash` ran stable
for ~31 minutes, then restarted again — this time all 6 app services logged the same
`LogstashTcpSocketAppender ... connection failed. java.net.ConnectException:
Connection refused` burst simultaneously (their `neverBlock: true` async appender
means this never blocks the app itself — see the appender config in each
`logback-spring.xml` — but it does mean the logs sent during Logstash's downtime are
silently dropped, not queued).
**Root cause**: `kubectl get pod -o jsonpath='{...lastState}'` told the real story
immediately: `"reason":"OOMKilled","exitCode":137`. `LS_JAVA_OPTS` capped the JVM
heap at `-Xmx256m` inside a container `memory` limit of only `512Mi` — Logstash's
*actual* memory footprint is heap plus JRuby runtime, Netty buffers for the TCP
input, and metaspace, and Elastic's own sizing guidance calls for real headroom above
`-Xmx`, not just enough container memory to fit the heap. 512Mi was too tight to
begin with (confirmed after the fix: idle usage alone sits around 564Mi, already
past the *old* limit), and once real log volume from all 6 services arrived, the
container blew past it and the cgroup OOM-killed the process outright — invisible in
Logstash's own JVM logs since a `SIGKILL` from the outside gives the process no
chance to log anything on the way down.
**Fix**: raised `-Xmx`/`-Xms` to `384m` and the container's `memory` limit to `896Mi`
(request `640Mi`) in `55-logstash.yaml`. Confirmed via `kubectl top pod` after the
fix: ~564Mi at idle against the new 896Mi limit, comfortable headroom instead of
already-exhausted.
**Takeaway**: #16 and #17 look identical from the app-service side (the same
`LogstashTcpSocketAppender ... Connection refused` burst in every service's logs) but
have different root causes and fixes — a probe-timing bug (dead on arrival every
time, pattern of failures from the moment the pod starts) versus an OOM under load
(runs fine for a while, then dies once traffic accumulates). `kubectl get pod
-o jsonpath='{.status.containerStatuses[0].lastState}'` distinguishes them
immediately — `"reason":"OOMKilled"` versus no `lastState.terminated` reason tied to
a probe failure — so check that first rather than assuming it's a repeat of #16.

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
