# Running the platform on Kubernetes (Minikube)

Manifests here mirror `docker-compose.yml`: 4 Postgres instances, Redis, a
single-node KRaft Kafka broker, and the 6 Spring Boot services, all in the
`ticketing` namespace, plus HPAs (CPU-based, needs `metrics-server`) and an
Ingress with one hostname per service.

For the full history of what broke during setup and how it was fixed
(Kafka's hairpin-NAT issue, the probe-timeout crash loops, the Postgres
password gotcha, etc.), see [`TROUBLESHOOTING.md`](./TROUBLESHOOTING.md).

## Prerequisites (you already have these)

- `minikube start --driver=docker --memory=6144 --cpus=3`
- `minikube addons enable ingress`
- `minikube addons enable metrics-server`

## 1. Build the app images into Minikube's Docker daemon

Minikube runs its own Docker daemon, separate from your host's. Point your
shell at it and build there so the cluster can find the images locally
without a registry:

```powershell
.\k8s\build-images.ps1
```

This builds `ticketing/<service>:latest` for all six services, matching the
tags already referenced in the Deployment manifests.

> Rerun this script (and then roll the affected deployment, e.g.
> `kubectl -n ticketing rollout restart deployment/catalog-service`) whenever
> you change service code.

## 2. Load your secrets (a separate step, on purpose)

`k8s/02-secrets.yaml` (committed) only has placeholder values, base64 of
`CHANGE_ME`. It is **deliberately excluded** from `kustomization.yaml` — a
Secret is not something `kubectl apply -k` should ever be able to silently
reset back to placeholders.

A filled-in copy, `k8s/02-secrets.local.yaml`, has already been generated
from your existing `.env` file — it's gitignored (`k8s/*.local.yaml`), so it
never gets committed. Apply it first:

```powershell
kubectl apply -f k8s/02-secrets.local.yaml
```

If you rotate a credential later, edit `k8s/02-secrets.local.yaml` and
re-apply it, then restart the pods that consume it, e.g.:

```powershell
kubectl -n ticketing rollout restart deployment/auth-service deployment/booking-service
```

## 3. Apply the rest of the manifests

```powershell
kubectl apply -k k8s/
```

This creates the namespace, ConfigMap, infra (Postgres x4, Redis, Kafka +
topic-creation Job), the 6 app Deployments + Services, HPAs, and the Ingress.
Since Secrets aren't part of this set, re-running it any time later (e.g.
after a code change + rebuild) is always safe and won't touch credentials.

## 4. Watch things come up

```powershell
kubectl -n ticketing get pods -w
```

Postgres/Redis/Kafka come up first; app pods sit in `Init:N/M` while their
`initContainers` wait on Postgres/Kafka/upstream-service health (mirroring
`depends_on: condition: service_healthy` from Compose), then start and pass
their `startupProbe` against `/actuator/health`. The `kafka-topics-init` Job
retries until Kafka is ready, then creates the four topics and completes.

```powershell
kubectl -n ticketing get jobs
kubectl -n ticketing logs job/kafka-topics-init
```

## 5. Reach the services via Ingress

Add these to `C:\Windows\System32\drivers\etc\hosts` (edit as Administrator),
pointing at your Minikube IP:

```powershell
minikube ip
```

```
<minikube-ip>  catalog.ticketing.local
<minikube-ip>  inventory.ticketing.local
<minikube-ip>  booking.ticketing.local
<minikube-ip>  auth.ticketing.local
<minikube-ip>  payment.ticketing.local
<minikube-ip>  notification.ticketing.local
```

With the Docker driver on Windows, Minikube's IP usually isn't directly
routable from the host — if the hostnames above don't respond, run
`minikube tunnel` in a separate terminal (keep it running) and use `127.0.0.1`
in the hosts entries instead.

Then e.g.:

```
http://catalog.ticketing.local/swagger-ui.html
http://auth.ticketing.local/swagger-ui.html
```

## Quick alternative: port-forward instead of Ingress

```powershell
kubectl -n ticketing port-forward svc/catalog-service 8081:8081
kubectl -n ticketing port-forward svc/auth-service 8085:8085
# etc.
```

## Notes / things you may want to tune

- **Resources**: requests/limits were sized to comfortably fit your
  `--memory=6144 --cpus=3` Minikube VM alongside 4 Postgres + Redis + Kafka +
  6 JVMs. If pods get `OOMKilled` or stay `Pending`, check
  `kubectl -n ticketing describe node minikube` for allocatable headroom.
- **HPA** is only wired for the 6 stateless app services (`k8s/30-hpa.yaml`),
  min 1 / max 3 replicas at 70% CPU. Databases/Kafka/Redis stay single-replica
  since they're stateful and not designed to scale horizontally here.
- **Storage**: each Postgres, Redis, and Kafka gets its own PVC on the
  `standard` (hostpath) storage class — data survives pod restarts but lives
  on the Minikube VM's disk, not your Windows filesystem.
- **Ingress hostnames vs Compose ports**: Compose exposed each service on a
  fixed host port (8081-8086); the Ingress instead gives each service its own
  hostname on port 80. Use port-forward (above) if you want the old
  port-per-service behavior.
